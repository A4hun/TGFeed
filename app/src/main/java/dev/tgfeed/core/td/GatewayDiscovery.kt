package dev.tgfeed.core.td

import android.util.Log
import dev.tgfeed.core.feed.DiscoveryGraph
import dev.tgfeed.core.feed.GroupHarvester
import dev.tgfeed.core.feed.HarvestGroupCandidate
import io.github.tdlibandroid.ktx.TdException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.drinkless.tdlib.TdApi

// DiscoveryProfile 仍嵌套在 TdLibGateway 内（外部以 TdLibGateway.DiscoveryProfile 限定引用，保持 API 不变），
// 本文件内的裸名引用经此别名落到同一类型
private typealias DiscoveryProfile = TdLibGateway.DiscoveryProfile

/**
 * bot 用户名 → userId（收割白名单解析用）：SearchPublicChat 命中**私聊**才返回
 * （ChatTypePrivate.userId，javap 实证字段）；用户名指向频道/群/不存在返回 null。
 * 签名已 javap 实证：SearchPublicChat(String) -> Chat；ChatTypePrivate{userId: long}。
 */
internal suspend fun TdLibGateway.resolveBotUserIdImpl(
    username: String,
    lane: SearchQuotaLedger.Lane = SearchQuotaLedger.Lane.CRAWLER,
): Long? {
    val key = username.trim().removePrefix("@").lowercase()
    if (key.isEmpty()) return null
    botUserIdCache[key]?.let { return it }
    // R44 过闸（R184 升级：熔断+账号账本+resolve-once 缓存全在 Ex 内）；失败 null
    val chat = sendSearchPublicChatGuarded(key, lane) ?: return null
    val userId = (chat.type as? TdApi.ChatTypePrivate)?.userId ?: return null
    botUserIdCache[key] = userId
    return userId
}

/**
 * R378 刀3 白名单缓存免重记·公共只读口（**零网络零记账零 suspend**）：键归一与
 * [resolveBotUserIdImpl] 同口径同源（trim/去 @/小写）零第二份逻辑——botUserIdCache
 * 命中直出 userId，未命中 null。调用方（GroupHarvester.resolveWhitelist）缓存命中
 * 即零记账收编：记账与真实发送对齐（旧版先烧 1 收割令牌再查缓存=稳态 32 令牌/h
 * 纯浪费、池内最大单点病根的清偿口）。只读不写缓存（写入仍归 resolveBotUserIdImpl
 * 成功路径），公共 API 只加不改。
 */
internal fun TdLibGateway.peekBotUserIdImpl(username: String): Long? {
    val key = username.trim().removePrefix("@").lowercase()
    if (key.isEmpty()) return null
    return botUserIdCache[key]
}

/**
 * userId → 是否 bot（GetUser 实证）：白名单外 sender 的采信依据（trustAllBots 模式）。
 * 失败按非 bot 处理（收割侧宁漏勿错——错采广告号比漏收答案危害大）。
 * 签名已 javap 实证：GetUser(long) -> User；User.type: UserType；UserTypeBot 子类型。
 */
internal suspend fun TdLibGateway.isBotUserImpl(userId: Long): Boolean {
    botUserTypeCache[userId]?.let { return it }
    awaitAuthorized()
    // R366：超时兜底——隐性限流挂起 12s 到点按「非 bot」保守回落（与既有失败口径一致）
    val isBot = runCatching {
        withTdTimeout(what = "isBotUser/GetUser userId=$userId") {
            client.send(TdApi.GetUser(userId))
        }?.type is TdApi.UserTypeBot
    }
        .getOrDefault(false)
    botUserTypeCache[userId] = isBot
    return isBot
}

/** listDiscoveryProfiles 进程内缓存快照；@Volatile 保证跨线程可见 */
internal class DiscoveryProfilesSnapshot(val atMs: Long, val profiles: List<DiscoveryProfile>)

/** R197 群选择器全量群枚举快照；@Volatile 保证跨线程可见 */
internal class HarvestGroupsSnapshot(val atMs: Long, val groups: List<HarvestGroupCandidate>)

/**
 * R197 群选择器原始素材：用户**全部已加入群**（基础群 + 超级群，频道也在档——
 * 是否展示由选择器纯函数按建议/勾选口径决定）：LoadChats 全量加载（R88 到 404
 * 口径，长尾群不漏）+ 逐 chat GetChat 分类 + GetSupergroup/GetBasicGroup 取成员数。
 *
 * 用户裁决（R197）「改成用户手动选，不要替他筛选」——本方法**不做任何关键词
 * 预过滤**，检测降级为纯函数侧的建议角标。全部调用为本地 TDLib 读
 * （LoadChats/GetChats/GetChat/GetSupergroup/GetBasicGroup），**零 Search 族
 * 原语**，不触搜索配额账本（R184 纪律边界内）。
 *
 * 进程内缓存 TTL [HARVEST_PICKER_TTL_MS]（sheet 低频重开秒回；新加入的群最迟
 * 5 分钟可见——与 listChannels 同族口径）。签名复用本文件已 javap 实证链：
 * GetChats(ChatList, int) -> Chats；GetChat(long) -> Chat{type, title}；
 * ChatTypeSupergroup{supergroupId, isChannel}；ChatTypeBasicGroup{basicGroupId}；
 * GetSupergroup(long) -> Supergroup{memberCount: int}（javap 实证 1.8.67）；
 * GetBasicGroup(long) -> BasicGroup{memberCount: int}（javap 实证 1.8.67）。
 */
internal suspend fun TdLibGateway.listHarvestGroupCandidatesImpl(): List<HarvestGroupCandidate> {
    harvestGroupsSnapshot
        ?.takeIf { System.currentTimeMillis() - it.atMs < HARVEST_PICKER_TTL_MS }
        ?.let { return it.groups }
    awaitAuthorized()
    return kotlinx.coroutines.withContext(Dispatchers.IO) {
        // 主列表 + 归档 + 全部分组各自 LoadChats 循环到 404（与 listChannels/listSearchableChats
        // 同一口径——列表互相独立，一个不漏）再 GetChats 全取
        // R366：超时兜底——隐性限流挂起 12s 到点：主列表超时=本轮失败上抛（error→调用方
        // runCatching，选择器如实「加载失败」可重试，不落空表冒充「枚举成功但无群」）；
        // 归档/分组超时=该列表跳过不阻塞整表（与既有失败口径一致）
        loadAllChatsInList(TdApi.ChatListMain())
        val idSet = LinkedHashSet<Long>()
        idSet += (withTdTimeout(what = "listHarvestGroups/GetChats Main") {
            client.send(TdApi.GetChats(TdApi.ChatListMain(), CHAT_ENUM_ALL_LIMIT))
        } ?: error("TDLib 请求超时（可能限流）")).chatIds.toList()
        runCatching {
            loadAllChatsInList(TdApi.ChatListArchive())
            withTdTimeout(what = "listHarvestGroups/GetChats Archive") {
                client.send(TdApi.GetChats(TdApi.ChatListArchive(), CHAT_ENUM_ALL_LIMIT))
            }?.chatIds?.toList()?.let { idSet += it }
        }
        for (folderId in chatFolderIds) {
            runCatching {
                loadAllChatsInList(TdApi.ChatListFolder(folderId))
                withTdTimeout(what = "listHarvestGroups/GetChats Folder$folderId") {
                    client.send(TdApi.GetChats(TdApi.ChatListFolder(folderId), CHAT_ENUM_ALL_LIMIT))
                }?.chatIds?.toList()?.let { idSet += it }
            }
        }
        val ids = idSet.toList()
        val sem = Semaphore(CONCURRENCY)
        val groups = coroutineScope {
            ids.map { id ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        // R366：超时兜底——单会话 GetChat/成员数读取挂起 12s 到点按单条跳过
                        // /成员数 0（未知）回落，与既有失败口径一致，整表不连坐不永挂
                        runCatching {
                            withTdTimeout(what = "listHarvestGroups/GetChat chatId=$id") {
                                client.send(TdApi.GetChat(id))
                            }
                        }.getOrNull()?.let { chat ->
                            channelTitleCache[id] = chat.title
                            when (val t = chat.type) {
                                is TdApi.ChatTypeSupergroup -> HarvestGroupCandidate(
                                    id, chat.title, t.isChannel,
                                    runCatching {
                                        withTdTimeout(what = "listHarvestGroups/GetSupergroup sg=${t.supergroupId}") {
                                            client.send(TdApi.GetSupergroup(t.supergroupId))
                                        }?.memberCount ?: 0
                                    }
                                        .getOrDefault(0),
                                )
                                is TdApi.ChatTypeBasicGroup -> HarvestGroupCandidate(
                                    id, chat.title, false,
                                    runCatching {
                                        withTdTimeout(what = "listHarvestGroups/GetBasicGroup bg=${t.basicGroupId}") {
                                            client.send(TdApi.GetBasicGroup(t.basicGroupId))
                                        }?.memberCount ?: 0
                                    }
                                        .getOrDefault(0),
                                )
                                else -> null // 私聊/秘密聊天等：不是「群」，选择器不收
                            }
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        Log.i(
            TAG,
            "listHarvestGroupCandidates: 会话=${ids.size} 群=${groups.size} " +
                "（频道=${groups.count { it.isChannel }} 群组=${groups.count { !it.isChannel }}）",
        )
        harvestGroupsSnapshot = HarvestGroupsSnapshot(System.currentTimeMillis(), groups)
        groups
    }
}

/**
 * 全会话类型化枚举（发现扫描的原始素材）：主列表+归档+全部分组 GetChats，
 * 逐 chat GetChat（[CONCURRENCY] 路并发，与 listSearchableChats 同口径）取类型/标题。
 * 进程内缓存 TTL 1 小时（[DISCOVERY_PROFILES_TTL_MS]，与发现重扫节奏一致）。
 *
 * 注意口径：GetChats 只回"已加载前缀"（每列表最多 500），排在极后的老会话可能缺席——
 * 调用方（GroupHarvester）对"本轮缺席的旧实体"按保守保留处理（缺席≠已退群）。
 *
 * 签名均沿用本文件已 javap 实证链：GetChats(ChatList, int) -> Chats；
 * GetChat(long) -> Chat{type: ChatType, title: String}；
 * ChatTypeSupergroup{supergroupId, isChannel}；ChatTypeBasicGroup{basicGroupId}；
 * ChatTypePrivate{userId}。
 */
internal suspend fun TdLibGateway.listDiscoveryProfilesImpl(): List<DiscoveryProfile> {
    discoveryProfilesSnapshot
        ?.takeIf { System.currentTimeMillis() - it.atMs < DISCOVERY_PROFILES_TTL_MS }
        ?.let { return it.profiles }
    awaitAuthorized()
    // R366：超时兜底——隐性限流挂起 12s 到点：主列表超时=本轮失败上抛（error→调用方
    // 失败路，不落空表污染「本轮缺席」保守保留口径）；归档/分组/逐 chat 超时=跳过不阻塞
    val chatIdSet = LinkedHashSet<Long>()
    chatIdSet += (withTdTimeout(what = "listDiscoveryProfiles/GetChats Main") {
        client.send(TdApi.GetChats(TdApi.ChatListMain(), 500))
    } ?: error("TDLib 请求超时（可能限流）")).chatIds.toList()
    runCatching {
        withTdTimeout(what = "listDiscoveryProfiles/GetChats Archive") {
            client.send(TdApi.GetChats(TdApi.ChatListArchive(), 500))
        }?.chatIds?.toList()?.let { chatIdSet += it }
    }
    for (folderId in chatFolderIds) {
        runCatching {
            withTdTimeout(what = "listDiscoveryProfiles/GetChats Folder$folderId") {
                client.send(TdApi.GetChats(TdApi.ChatListFolder(folderId), 500))
            }?.chatIds?.toList()?.let { chatIdSet += it }
        }
    }
    val ids = chatIdSet.toList()
    val sem = Semaphore(CONCURRENCY)
    val result = coroutineScope {
        ids.map { id ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    runCatching {
                        withTdTimeout(what = "listDiscoveryProfiles/GetChat chatId=$id") {
                            client.send(TdApi.GetChat(id))
                        }
                    }.getOrNull()
                }
            }
        }.awaitAll().mapIndexedNotNull { i, chat ->
            chat ?: return@mapIndexedNotNull null
            val id = ids[i]
            channelTitleCache[id] = chat.title
            when (val t = chat.type) {
                is TdApi.ChatTypeSupergroup -> DiscoveryProfile(
                    id, chat.title, DiscoveryProfile.CHAT_SUPERGROUP, t.isChannel, t.supergroupId, 0,
                )
                is TdApi.ChatTypeBasicGroup -> DiscoveryProfile(
                    id, chat.title, DiscoveryProfile.CHAT_BASICGROUP, false, 0, 0,
                )
                is TdApi.ChatTypePrivate -> DiscoveryProfile(
                    id, chat.title, DiscoveryProfile.CHAT_PRIVATE, false, 0, t.userId,
                )
                else -> DiscoveryProfile(id, chat.title, DiscoveryProfile.CHAT_OTHER, false, 0, 0)
            }
        }
    }
    Log.d(TAG, "listDiscoveryProfiles: 会话=${result.size}")
    discoveryProfilesSnapshot = DiscoveryProfilesSnapshot(System.currentTimeMillis(), result)
    return result
}

/**
 * 超级群/频道简介纯读取（发现扫描的"发关键词"类说明判定用）：GetSupergroupFullInfo
 * 只取 description，不做图谱回写（与 fetchSupergroupProfile 的定位差异：那边服务
 * 发现页出榜、顺带建边/成员数；这边是收割清单分类，零副作用）。
 * 失败/异常返回 null（调用方下轮重扫重试）。签名已 javap 实证（见 fetchSupergroupProfile
 * 注释）：GetSupergroupFullInfo(long) -> SupergroupFullInfo{description: String, …}
 */
internal suspend fun TdLibGateway.fetchSupergroupDescriptionImpl(supergroupId: Long): String? {
    if (supergroupId == 0L) return null
    awaitAuthorized()
    // R366：超时兜底——隐性限流挂起 12s 到点按 null 失败回落（调用方下轮重扫重试）
    return runCatching {
        withTdTimeout(what = "fetchSupergroupDescription/GetSupergroupFullInfo sg=$supergroupId") {
            client.send(TdApi.GetSupergroupFullInfo(supergroupId))
        }?.description
    }
        .onFailure { Log.w(TAG, "fetchSupergroupDescription 失败 sg=$supergroupId: ${it.message}") }
        .getOrNull()
}

/** 私聊用户 bot 档案进程内缓存（正负都缓存：人不会变 bot，bot 不会变人） */
internal val userBotProfileCache = java.util.concurrent.ConcurrentHashMap<Long, Pair<Boolean, String>>()

/**
 * userId → (是否 bot, 主用户名)：发现扫描的 bot 身份实证（一次 GetUser 取两样）。
 * 用户名取法与 getMyProfile 同口径（javap 实证：User.usernames: Usernames{
 * editableUsername: String, activeUsernames: String[]}，editable 优先、active 首个兜底）。
 * 失败返回 null（调用方本轮跳过，下轮重扫重试——负结果不缓存失败，与 resolveBotUserId 同口径）。
 */
internal suspend fun TdLibGateway.probeUserBotProfileImpl(userId: Long): Pair<Boolean, String>? {
    userBotProfileCache[userId]?.let { return it }
    awaitAuthorized()
    // R366：超时兜底——挂起 12s 到点按 null 失败回落（负结果不缓存，下轮重扫重试）
    val user = runCatching {
        withTdTimeout(what = "probeUserBotProfile/GetUser userId=$userId") {
            client.send(TdApi.GetUser(userId))
        }
    }.getOrNull() ?: return null
    val username = user.usernames?.editableUsername?.takeIf { it.isNotBlank() }
        ?: user.usernames?.activeUsernames?.firstOrNull { it.isNotBlank() } ?: ""
    val result = (user.type is TdApi.UserTypeBot) to username
    userBotProfileCache[userId] = result
    return result
}

/** bot 用户名 → 私聊 chatId 进程内缓存（只缓存成功；失败下轮重试，防瞬时网络问题钉死） */
internal val botChatIdCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

/**
 * bot 用户名 → 私聊 chatId（发现扫描把 searchViaBot 既有配置的 bot 并入收割清单用）。
 * 与 resolveBotUserId 的差别：那边取 userId（群内 sender 白名单比对），
 * 这边取 chatId（收割 GetChatHistory 的入参）；SearchPublicChat 返回的 Chat.id
 * 即私聊会话 id（javap 实证链同 searchViaBot/resolveBotUserId）。
 * 注意：未开聊过的公开 bot 也能解析出 chatId——会话历史为空，收割自然空转，无害。
 */
internal suspend fun TdLibGateway.resolveBotChatIdImpl(
    username: String,
    lane: SearchQuotaLedger.Lane = SearchQuotaLedger.Lane.CRAWLER,
): Long? {
    val key = username.trim().removePrefix("@").lowercase()
    if (key.isEmpty()) return null
    botChatIdCache[key]?.let { return it }
    // R44 过闸（R184 升级：熔断+账号账本+resolve-once 缓存）；失败 null
    val chat = sendSearchPublicChatGuarded(key, lane) ?: return null
    if (chat.type !is TdApi.ChatTypePrivate) return null
    botChatIdCache[key] = chat.id
    return chat.id
}

/**
 * 公开用户名解析结论（EntityResolver 专用三态）：
 *  - [ok]=true：带 chatId/标题/是否频道；
 *  - [ok]=false 且 [permanent]=true：稳定终态（TDLib 400 用户名不存在/不可见/被封），
 *    调用方进死信永不重试；
 *  - [ok]=false 且 [permanent]=false：瞬态失败（[floodWait]=true 为 429 限流，
 *    其余为网络类异常），调用方按退避重试。
 */
class PublicChatLookup(
    val ok: Boolean,
    val permanent: Boolean,
    val floodWait: Boolean,
    val chatId: Long,
    val title: String,
    /** ChatTypeSupergroup && isChannel（只有频道才补写图谱边，与 resolvePendingChannels 同口径） */
    val isChannel: Boolean,
    /** 429 限流时服务端要求的等待秒数（从 "retry after N" 解析；非限流/解析不出 = 0） */
    val floodRetryAfterSec: Int = 0,
    /**
     * R211 频道活跃度（epoch 秒）：解析返回的 Chat.lastMessage 日期（SearchPublicChat
     * 建会话时的本地缓存值；0 = 未知/无消息）。直荐验证「有内容+活跃」的第一手
     * 零成本来源——不够时消费侧可用 GetChatHistory 探针兜底。
     */
    val lastActivitySec: Long = 0L,
) {
    companion object {
        fun success(chatId: Long, title: String, isChannel: Boolean, lastActivitySec: Long = 0L) =
            PublicChatLookup(ok = true, permanent = false, floodWait = false, chatId = chatId, title = title, isChannel = isChannel, lastActivitySec = lastActivitySec)

        fun failure(permanent: Boolean, floodWait: Boolean, floodRetryAfterSec: Int = 0) =
            PublicChatLookup(ok = false, permanent = permanent, floodWait = floodWait, chatId = 0L, title = "", isChannel = false, floodRetryAfterSec = floodRetryAfterSec)
    }
}

/**
 * 用户名 → 公开会话（SearchPublicChat，EntityResolver 的唯一网络通道）。
 * 与 resolveBotChatId/resolveBotUserId 的差异：那两个只认私聊 bot（白名单/清单用），
 * 本方法接受一切会话类型（频道/超级群/私聊都回传身份，是否建边由调用方按类型判定）。
 * 成功顺手喂 channelTitleCache（发现页候选卡免再 GetChat）。
 *
 * **R184：实现收敛到 [searchPublicChatGuardedEx] 统一发送口**——resolve-once 缓存
 * 命中零搜索直通（跨进程永不重解析）、账号级配额账本（[lane] 车道）、429 双族
 * 统一上报。**本方法不再自行计 flood 预算**——搜索原语的账号级记账已在发送口内
 * 完成调用方（EntityResolver 走收割车道令牌桶）的既有车道预算不变，口径不叠算。
 * 签名复用既有 javap 实证：SearchPublicChat(String) -> Chat{id, type, title}；
 * 错误通道为 ktx TdException（code/message，code 400 = 用户名类稳定终态）。
 */
internal suspend fun TdLibGateway.resolvePublicChatByUsernameImpl(
    username: String,
    lane: SearchQuotaLedger.Lane = SearchQuotaLedger.Lane.BACKGROUND,
): PublicChatLookup {
    val key = username.trim().removePrefix("@").lowercase()
    if (key.isEmpty()) return PublicChatLookup.failure(permanent = true, floodWait = false)
    return when (val r = searchPublicChatGuardedEx(key, lane)) {
        is GuardedSearchResult.Hit -> {
            val type = r.chat.type
            PublicChatLookup.success(
                r.chat.id, r.chat.title,
                type is TdApi.ChatTypeSupergroup && type.isChannel,
                // R211 活跃度直出：解析建会话返回的 Chat 自带 lastMessage（本地缓存值，
                // 零额外请求；无历史/未知 = 0，消费侧自行兜底）
                (r.chat.lastMessage?.date ?: 0).toLong(),
            )
        }
        GuardedSearchResult.Rejected -> {
            // 熔断/半开限量/账号预算拒（未发送）——按 429 失败语义返回，调用方的整轮
            // 收工/自动重踢直接吃真实节奏（闸剩余秒数或封禁窗剩余取大者）
            val remain = maxOf(
                floodBreaker.remainingSec(FloodCircuitBreaker.FAMILY_SEARCH_PUBLIC_CHAT),
                searchQuotaLedger.remainingBanSec(),
            )
            Log.d(TAG, "resolvePublicChatByUsername: 熔断/半开/账号预算拒 @$key（剩 ${remain}s，车道=${lane.label}）")
            PublicChatLookup.failure(
                permanent = false, floodWait = true,
                floodRetryAfterSec = remain.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
        }
        GuardedSearchResult.NotFound ->
            PublicChatLookup.failure(permanent = true, floodWait = false)
        is GuardedSearchResult.FloodWait ->
            PublicChatLookup.failure(permanent = false, floodWait = true, floodRetryAfterSec = r.retryAfterSec)
        GuardedSearchResult.Transient ->
            PublicChatLookup.failure(permanent = false, floodWait = false)
    }
}

/**
 * R184 限流降级的后台重试入队：把用户名塞进图谱 resolveQueue（from=0 占位——
 * 只解析建会话+回写缓存，不补边）。EntityResolver 常驻管线与进发现页的
 * [resolvePendingChannelsImpl] 都会消费该队列：解封后自动消化，SearchPublicChat
 * 副作用=建会话+建 resolve-once 缓存——用户下次点击 GetChat 直通零搜索。
 * ChannelFeedSource 自愈撞限流时调用（用户当下拿到诚实提示，重试交给后台）。
 */
internal fun TdLibGateway.enqueueBackgroundResolveImpl(username: String?) {
    val name = username?.trim()?.removePrefix("@").orEmpty()
    if (name.isEmpty()) return
    runCatching { discoveryGraph.recordPendingName(0L, name) }
        .onFailure { Log.w(TAG, "enqueueBackgroundResolve 失败 @$name: ${it.message}") }
        .onSuccess { Log.i(TAG, "enqueueBackgroundResolve: @$name 已入队后台重试（解禁后自动解析建会话）") }
}

/**
 * 相似频道递归发现（DISCOVERY-SIGNAL 方案二）：以用户高权重频道为种子做两层
 * GetChatSimilarChats。二层只对一层里"被 ≥2 个种子同时指向"的候选再展开——
 * 共指过滤：被多个种子共同指向是口味交集，置信度远高于单点推荐，
 * 同时恰好压制单层推荐被官方算法带进宽泛热门频道的噪声。
 *
 * 成本控制：
 *  - Semaphore([DISCOVER_SIMILAR_CONCURRENCY]) 控并发；
 *  - 会话级计数闸 [similarDiscoverCalls]：GetChatSimilarChats 查询总数 ≤
 *    [DISCOVER_SIMILAR_MAX_CALLS]（increment-then-check：闸外计数只增不发查询，
 *    并发下也不会多放）；标题补全走 [getSimilarChats] 既有通道
 *    （channelTitleCache 进程内缓存 + 幂等 GetChat，未命中才补查）；
 *  - depth 语义：1 = 只跑一层；≥2 = 一层 + 共指二层（更深层级暂不实现——
 *    官方相似图两跳已覆盖口味邻域，再深收益递减且闸内预算放不下）。
 *
 * 结果按共指次数（被多少个已查询频道同时指向）降序；任一单点失败/整体失败均静默，
 * 最坏返回空表（发现页对空列表有空态引导）。种子自身不进候选。
 *
 * 签名复用既有 javap 实证（见 [getSimilarChats] 注释）：
 * GetChatSimilarChats(long chatId) -> Chats{totalCount, chatIds}（1.8.67 无 ChatSimilarChats 类型）
 */
internal suspend fun TdLibGateway.discoverSimilarChannelsImpl(seedChatIds: List<Long>, depth: Int = 2): List<ChannelBrief> = runCatching {
    awaitAuthorized()
    val seeds = seedChatIds.distinct()
    if (seeds.isEmpty()) return@runCatching emptyList()
    val sem = Semaphore(DISCOVER_SIMILAR_CONCURRENCY)
    val pointCount = java.util.concurrent.ConcurrentHashMap<Long, Int>() // 候选 chatId → 共指次数
    val briefs = java.util.concurrent.ConcurrentHashMap<Long, ChannelBrief>()

    // 查询一个节点的相似推荐并收割共指证据（计数闸 + 并发闸 + 失败静默都在 getSimilarChats 内）
    suspend fun queryOne(chatId: Long) {
        sem.withPermit {
            if (similarDiscoverCalls.incrementAndGet() > DISCOVER_SIMILAR_MAX_CALLS) {
                return@withPermit // 超闸：计数照加（无害）但不发查询
            }
            for (b in getSimilarChats(chatId)) {
                if (b.id in seeds) continue // 种子自己不列候选
                briefs.putIfAbsent(b.id, b)
                pointCount.merge(b.id, 1, Int::plus)
            }
        }
    }
    // 一层：每种子的官方相似推荐（并发）
    coroutineScope { seeds.map { id -> async(Dispatchers.IO) { queryOne(id) } }.awaitAll() }
    // 二层：只对一层里"被 ≥2 个种子共指"的候选递归展开
    if (depth >= 2) {
        val layer2 = pointCount.entries.filter { it.value >= 2 }.map { it.key }
        Log.d(TAG, "discoverSimilarChannels: 一层候选=${briefs.size}，共指(≥2)进入二层=${layer2.size}")
        coroutineScope { layer2.map { id -> async(Dispatchers.IO) { queryOne(id) } }.awaitAll() }
    }
    Log.d(TAG, "discoverSimilarChannels: seeds=${seeds.size} 候选=${briefs.size} 会话累计查询=${similarDiscoverCalls.get()}")
    briefs.values.sortedByDescending { pointCount[it.id] ?: 0 }
}.onFailure {
    Log.w(TAG, "discoverSimilarChannels 失败: ${it.message}")
}.getOrDefault(emptyList())

/**
 * resolveQueue 懒解析：把嗅探攒下的 t.me/<name> 公开用户名逐条 SearchPublicChat
 * 解析成真频道——命中频道（ChatTypeSupergroup && isChannel；超级群不是 Feed 源，丢弃）
 * 则补写真实 link 边（from=引用方 → to=真 chatId）+ recordNodeTitle 补标题 + 喂标题缓存；
 * 无论成败都把该名字移出队列（防死信堆积，语义见 DiscoveryGraph.removePendingName）。
 *
 * IO 串行逐条执行（发现页进页时由 FeedViewModel.refreshDiscovery 调用一次；
 * 解析是增量收益，串行不抢 Feed 拉取带宽）。未授权时本轮整体跳过且队列保留
 * （什么都没尝试，不该按"失败"出队）。
 *
 * 签名复用既有 javap 实证：
 * SearchPublicChat(String username) -> Chat{id, type, title}（见 searchViaBot 注释）；
 * ChatTypeSupergroup{supergroupId, isChannel}（见 listChannels 注释）。
 */
internal suspend fun TdLibGateway.resolvePendingChannelsImpl(limit: Int = 20) {
    val pending = runCatching { discoveryGraph.pendingResolveNames(limit) }.getOrDefault(emptyList())
    if (pending.isEmpty()) return
    val authorized = runCatching { awaitAuthorized() }.isSuccess
    if (!authorized) {
        Log.w(TAG, "resolvePendingChannels: 未授权，本轮跳过（队列原样保留）")
        return
    }
    var resolved = 0
    val family = FloodCircuitBreaker.FAMILY_SEARCH_PUBLIC_CHAT
    for ((name, fromChatId) in pending) {
        // R44+R58 熔断闸：熔断静默/半开间隔未到的拒发都整轮收工——队列原样保留不出队
        // （把名字按"失败"出队是冤枉它们；429 是账号状态不是名字的问题）。
        // 半开期过闸天然限量：闸按间隔逐发放行试探，被拒即收工，下一发留给下轮
        val chat = sendSearchPublicChatGuarded(name)
        if (chat == null && floodBreaker.stateOf(family) != FloodCircuitBreaker.BreakerState.CLOSED) {
            Log.d(TAG, "resolvePendingChannels: 熔断/半开限量中（剩 ${floodBreaker.remainingSec(family)}s），本轮收工（队列保留）")
            break
        }
        val type = chat?.type
        if (chat != null && type is TdApi.ChatTypeSupergroup && type.isChannel) {
            // 引用方 id 有效才补边：from=0 是队列里的脏数据（缺省值），0→id 的脏边不写；
            // 自环由 recordEdge 内部丢弃，这里顺手也挡掉
            if (fromChatId != 0L && fromChatId != chat.id) {
                runCatching { discoveryGraph.recordEdge(fromChatId, chat.id, DiscoveryGraph.EDGE_LINK) }
            }
            runCatching { discoveryGraph.recordNodeTitle(chat.id, chat.title) }
            channelTitleCache[chat.id] = chat.title
            resolved++
        }
        // 成败都出队：失败（名字不存在/不可见/非频道）是稳定终态，重试无意义
        runCatching { discoveryGraph.removePendingName(name) }
    }
    Log.d(TAG, "resolvePendingChannels: 取出 ${pending.size} 个待解析名，命中频道 $resolved 个")
}

/**
 * 取本人资料（MineScreen 账号卡用）；未授权/任何一步失败返回 null（调用方回落占位卡）。
 *
 * 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
 * GetMe() -> User{
 *   firstName: String, lastName: String, phoneNumber: String,
 *   usernames: Usernames{activeUsernames: String[], editableUsername: String},
 *   profilePhoto: ProfilePhoto{id: long, small: File, big: File, minithumbnail: Minithumbnail}
 * }
 *   —— 注意鉴别：1.8.67 的 User 已没有 username: String 平铺字段，
 *      用户名在 Usernames 上（editableUsername 为主用户名，activeUsernames 首个兜底）；
 * File{id: int, local: LocalFile}；LocalFile{path: String, isDownloadingCompleted: boolean}
 * DownloadFile(int fileId, int priority, long offset, long limit, boolean synchronous) -> File
 *
 * 流程：GetMe 拿 User → profilePhoto.small 已下载完成直接读文件字节；未完成用
 * DownloadFile(..., synchronous=true) 同步拉（头像小图秒回）。无头像/下载失败不算
 * 整体失败——降级为 avatarBytes=null（调用方回落首字母圆）。成功结果进程内缓存一份。
 */
internal suspend fun TdLibGateway.getMyProfileImpl(): MyProfile? = runCatching {
    myProfileCache?.let { return@runCatching it }
    awaitAuthorized()
    // R366：超时兜底——挂起 12s 到点按整体失败回落 null（调用方回落占位卡）
    val me = withTdTimeout(what = "getMyProfile/GetMe") {
        client.send(TdApi.GetMe())
    } ?: return@runCatching null
    // 显示名：firstName + lastName 拼接，全空（TG 允许不设名字）降级 null
    val displayName = listOf(me.firstName, me.lastName)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { null }
    // 用户名：editableUsername（主用户名）优先，activeUsernames 首个非空兜底；空串视为没有
    val username = me.usernames?.editableUsername?.takeIf { it.isNotBlank() }
        ?: me.usernames?.activeUsernames?.firstOrNull { it.isNotBlank() }
    // 手机号：空串视为没有（防御；正常登录账号必有）
    val phone = me.phoneNumber.takeIf { it.isNotBlank() }
    // 头像小图字节：独立兜底，失败不影响文字资料（调用方画首字母圆）
    val avatarBytes = runCatching {
        val small = me.profilePhoto?.small ?: return@runCatching null
        var file = small
        if (!file.local.isDownloadingCompleted) {
            // R366：超时兜底——头像同步拉挂起 12s 到点按头像缺省回落（不算整体失败）
            file = withTdTimeout(what = "getMyProfile/DownloadFile avatar") {
                client.send(TdApi.DownloadFile(file.id, 16, 0L, 0L, true))
            } ?: return@runCatching null
            if (!file.local.isDownloadingCompleted) return@runCatching null
        }
        val path = file.local.path
        // EISDIR 防御：TDLib 偶发把目录路径标记为已完成，必须是真实文件（教训同 getChannelAvatar）
        if (path.isEmpty() || !java.io.File(path).isFile) return@runCatching null
        java.io.File(path).readBytes().takeIf { it.isNotEmpty() }
    }.getOrNull()
    MyProfile(
        name = displayName,
        username = username,
        phone = phone,
        avatarBytes = avatarBytes,
    ).also { myProfileCache = it }
}.onFailure {
    Log.w(TAG, "getMyProfile 失败: ${it.message}")
}.getOrNull()

/**
 * R189 底栏双击「我的」=刷新本人资料：清进程内缓存（下次 getMyProfile 真重拉
 * GetMe+头像）。调用方（MainActivity）先调本函数再递增 MineScreen 的 refreshTick。
 */
internal fun TdLibGateway.refreshMyProfileImpl() {
    myProfileCache = null
}

// ---------------- P1-b 发现增密：SupergroupFullInfo 懒加载 + 合集邀请链接收割 ----------------

/**
 * 候选频道超级群档案懒加载（DISCOVER-UPGRADE P1-b #8；调用纪律在 FeedViewModel 侧：
 * 候选出榜后才调、每候选每会话一次、每刷新预算闸——本方法不做闸，只做取数与顺手建边）。
 *
 * 一次 GetChat + 一次 GetSupergroupFullInfo 换四样：
 *  - memberCount → 回写图谱节点（recordNodeMemberCount 落盘），DiscoveryRanker 的
 *    Q 质量分/A 活跃度下次刷新即吃到（ranker 口径：成员数已知时 A 用 Q 代替）；
 *  - description 简介里的 t.me 互链 → 顺手建 link 边（候选 → 它简介挂的频道，
 *    简介互推是运营者强声明；t.me/<name> 形式进 resolveQueue 懒解析，同既有口径）；
 *  - linkedChatId（关联讨论群）/ communityId（TG Community 同运营家族 id）→
 *    随返回值透传，本轮只记日志，Community 家族扇出（P1 #11）下一轮消费；
 *  - 标题白拿 → recordNodeTitle + channelTitleCache。
 *
 * 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
 * GetChat(long chatId) -> Chat{id: long, type: ChatType, title: String}
 * ChatTypeSupergroup{supergroupId: long, isChannel: boolean}
 * GetSupergroupFullInfo(long supergroupId) -> SupergroupFullInfo{
 *   memberCount: int, description: String, linkedChatId: long, communityId: long, …}
 * 非频道（超级群/私聊/基础群）、无权限、任何失败一律返回 null（调用方跳过即可）。
 */
internal suspend fun TdLibGateway.fetchSupergroupProfileImpl(chatId: Long): SupergroupProfile? = runCatching {
    awaitAuthorized()
    // R366：超时兜底——隐性限流挂起 12s 到点按 null 失败回落（调用方跳过即可，不永挂）
    val chat = withTdTimeout(what = "fetchSupergroupProfile/GetChat chatId=$chatId") {
        client.send(TdApi.GetChat(chatId))
    } ?: return@runCatching null
    val sg = chat.type as? TdApi.ChatTypeSupergroup ?: return@runCatching null
    if (!sg.isChannel) return@runCatching null // 超级群不是 Feed 源（与 resolvePendingChannels 同口径）
    val full = withTdTimeout(what = "fetchSupergroupProfile/GetSupergroupFullInfo sg=${sg.supergroupId}") {
        client.send(TdApi.GetSupergroupFullInfo(sg.supergroupId))
    } ?: return@runCatching null
    // 标题白拿回写（图谱节点补全 + 进程内缓存）
    if (chat.title.isNotBlank()) {
        runCatching { discoveryGraph.recordNodeTitle(chatId, chat.title) }
        channelTitleCache[chatId] = chat.title
    }
    // 成员数回写图谱节点（ranker Q/A 的持久供给；≤0 = 隐藏成员/未知，不记）
    if (full.memberCount > 0) {
        runCatching { discoveryGraph.recordNodeMemberCount(chatId, full.memberCount) }
    }
    // 简介互链建边：description 是纯文本（无实体），走 TME_LINK_REGEX 正则路 + 既有归一化
    if (full.description.isNotBlank()) {
        for (m in TME_LINK_REGEX.findAll(full.description)) {
            normalizeTmeLink(m.value)?.let { runCatching { sniffLink(chatId, it) } }
        }
    }
    SupergroupProfile(
        chatId = chatId,
        memberCount = full.memberCount,
        description = full.description,
        linkedChatId = full.linkedChatId,
        communityId = full.communityId,
    )
}.onFailure {
    Log.w(TAG, "fetchSupergroupProfile 失败（跳过）chatId=$chatId: ${it.message}")
}.getOrNull()

/** 会话内已收割/收割中的合集链接去重闸（addlist 形态稀缺，同链接绝不重复发请求） */
internal val folderHarvestLinks = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

/** 合集收割会话计数闸（increment-then-check，与 similarDiscoverCalls 同款口径） */
internal val folderHarvestCalls = java.util.concurrent.atomic.AtomicInteger(0)

/**
 * 合集邀请链接收割（DISCOVER-UPGRADE P1-b #9）：CheckChatFolderInviteLink 免加入解析
 * t.me/addlist/<slug>，一次请求把合集里全部频道（missingChatIds 未加入 + addedChatIds
 * 已加入）建 link 边（引用方 → 合集成员频道）——合集是人工策展的频道包，一个链接
 * 换 10~30 个高质量候选。裸 ID 候选由发现页既有的懒解析标题链补标题出榜。
 *
 * 闸：同链接会话内只收一次（[folderHarvestLinks] 去重，并发重复触发天然合并）；
 * 会话累计 ≤ [FOLDER_HARVEST_MAX_CALLS] 次（兜底防"营销号刷屏塞合集链接"烧请求）。
 * 未授权/链接失效/任何失败静默（收割是增量收益，绝不影响主抓取链）。
 *
 * 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
 * CheckChatFolderInviteLink(String inviteLink) -> ChatFolderInviteLinkInfo{
 *   chatFolderInfo: ChatFolderInfo, missingChatIds: long[], addedChatIds: long[]}
 * ChatFolderInfo{id: int, name: ChatFolderName, …}；ChatFolderName{text: FormattedText, …}
 */
internal suspend fun TdLibGateway.harvestChatFolderInviteImpl(fromChatId: Long, inviteLink: String) {
    if (!folderHarvestLinks.add(inviteLink)) return // 同链接本会话已收过/在收
    if (folderHarvestCalls.incrementAndGet() > FOLDER_HARVEST_MAX_CALLS) return
    runCatching {
        awaitAuthorized()
        // R366：超时兜底——挂起 12s 到点按本轮收割失败静默（增量收益，绝不影响主链）
        val info = withTdTimeout(what = "harvestChatFolderInvite/CheckChatFolderInviteLink") {
            client.send(TdApi.CheckChatFolderInviteLink(inviteLink))
        } ?: return@runCatching
        // JNI 平台数组防御 null；missing+added 合并去重（是否已加入不过滤——
        // 已加入频道由发现页出榜前的 joinedNow 硬过滤统一拦截，建边无妨）
        val ids = (info.missingChatIds?.toList().orEmpty() + info.addedChatIds?.toList().orEmpty()).distinct()
        val folderName = info.chatFolderInfo?.name?.text?.text.orEmpty()
        var edges = 0
        for (id in ids) {
            if (id == 0L || id == fromChatId) continue // 坏 id / 自环跳过
            runCatching { discoveryGraph.recordEdge(fromChatId, id, DiscoveryGraph.EDGE_LINK) }
            edges++
        }
        Log.i(TAG, "harvestChatFolderInvite: 合集「$folderName」成员=${ids.size} 建边=$edges from=$fromChatId")
    }.onFailure {
        Log.w(TAG, "harvestChatFolderInvite 失败（跳过）link=$inviteLink: ${it.message}")
    }
}

// ---------------- 发现页终点判定：权威"已加入"逐点校验 ----------------

/**
 * 加入态三值（R183 深索引候选口径）：
 *  - [JOINED]：本人是成员（Member/Administrator/Creator/Restricted.isMember）；
 *  - [NOT_JOINED]：确认非成员（Left/Banned）；
 *  - [UNKNOWN]：chat 本地无记录（GetChat 失败）——TDLib 语义上加入必在本地留档
 *    （加入动作必经会话打开），**从未打开过 = 从未加入**；这正是深索引候选的常态
 *    （SearchChatMessages 爬取不建本地会话）。R64 保守口径把 UNKNOWN 当已加入剔除
 *    对图谱/相似来源候选合理（错杀代价低于撞脸），对深索引全量候选则把宝藏一整条
 *    管道拦死（R183 真机实锤：50/50 全被「chat 本地未知」误杀）——发现页主流程改
 *    用 [joinStatusOf] 三值口径，UNKNOWN 放行；既有调用方（刚冒出来的新鲜区 R57）
 *    继续用保守版 [isJoinedChat]。
 */
enum class JoinStatus { JOINED, NOT_JOINED, UNKNOWN }

/**
 * isJoinedChat 进程内缓存：只缓存"已加入"结论（成员身份由服务端同步维护，
 * 用户退频道后发现页本就不该再推，true 钉死无副作用）；"未加入"不缓存——
 * 本地读零成本，每轮重验可自愈"验证之后才加入"的场景（旧实现把 false 钉死，
 * 用户随后真加入的频道会永久残留在发现页）。
 */
internal val joinedChatCache = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()

/**
 * 权威"已加入"三值判定（R183：发现页主流程用）。
 *
 * 真值源（logcat 实锤修正，勿回退）：GetChat → ChatTypeSupergroup →
 * GetSupergroup.status（TDLib 本地已知即可读的本人成员身份，服务端同步维护；
 * 基础群同法取 BasicGroup.status）。
 * **不要改回 GetChatMember**：对"成员列表不可见"的频道，GetChatMember 连本人
 * 身份查询都返回 error 400 "Member list is inaccessible"——实测 sgStatus=
 * ChatMemberStatusMember 的已加入大频道（19.6 万/12.5 万成员）照样撞此错误；
 * 旧实现把它当"确认未加入"放行并缓存，构成已加入频道出榜泄漏的主机制。
 */
internal suspend fun TdLibGateway.joinStatusOfImpl(chatId: Long): JoinStatus {
    if (joinedChatCache[chatId] == true) return JoinStatus.JOINED
    // R366：超时兜底——GetChat 挂起 12s 到点按 UNKNOWN（与既有失败口径一致，
    // 「查不了」不落「确认未加入」）；成员身份读取超时按保守 JOINED（宁可少推不错推）
    val chat = runCatching {
        withTdTimeout(what = "joinStatusOf/GetChat chatId=$chatId") {
            client.send(TdApi.GetChat(chatId))
        }
    }.getOrNull()
        ?: return JoinStatus.UNKNOWN // chat 本地无记录/读取失败 = 从未打开/未知（见 JoinStatus KDoc）
    val status = when (val t = chat.type) {
        is TdApi.ChatTypeSupergroup ->
            runCatching {
                withTdTimeout(what = "joinStatusOf/GetSupergroup sg=${t.supergroupId}") {
                    client.send(TdApi.GetSupergroup(t.supergroupId))
                }?.status
            }.getOrNull()
        is TdApi.ChatTypeBasicGroup ->
            runCatching {
                withTdTimeout(what = "joinStatusOf/GetBasicGroup bg=${t.basicGroupId}") {
                    client.send(TdApi.GetBasicGroup(t.basicGroupId))
                }?.status
            }.getOrNull()
        else -> {
            // 私聊/秘密聊天混进发现候选 = 脏数据，按已加入剔除（不缓存——脏数据可能修复）
            Log.w(TAG, "joinStatusOf: 非群组类候选 chatId=$chatId type=${t.javaClass.simpleName}，按已加入剔除")
            return JoinStatus.JOINED
        }
    } ?: return JoinStatus.JOINED // 身份读取失败：宁可少推不错推（保守口径不变）
    return when (status) {
        is TdApi.ChatMemberStatusMember,
        is TdApi.ChatMemberStatusAdministrator,
        is TdApi.ChatMemberStatusCreator -> {
            joinedChatCache[chatId] = true
            JoinStatus.JOINED
        }
        is TdApi.ChatMemberStatusRestricted ->
            if (status.isMember) {
                joinedChatCache[chatId] = true
                JoinStatus.JOINED
            } else {
                JoinStatus.NOT_JOINED
            }
        else -> JoinStatus.NOT_JOINED // ChatMemberStatusLeft / ChatMemberStatusBanned
    }
}

/**
 * 权威"已加入"保守布尔版（既有调用方口径原样：R57 新鲜区/雷达并入等）。
 * 返回口径（发现页宁可少推不错推）：
 *  - true  = 已加入 **或不确定**（身份读取失败/非群组脏数据/chat 本地未知一律剔除）；
 *  - false = 确认未加入（Left/Banned/Restricted.isMember=false）。
 * R64 起"chat 本地未知"从放行改保守剔除：旧口径把从未加载过的频道当未加入放行，
 * 构成穿透窗口——已加入但本地恰好无记录的频道会漏进发现页新鲜区，与首页聚合
 * 首屏内容同源撞脸；新鲜区宁可少摆不可摆已加入（候选池足够大，错杀代价低于撞脸）。
 * R183 发现页**主流程**改用 [joinStatusOf]（UNKNOWN 放行——深索引候选的语义反转，
 * 见 JoinStatus KDoc）；本保守版保留给新鲜区等小流量调用方。
 */
internal suspend fun TdLibGateway.isJoinedChatImpl(chatId: Long): Boolean =
    when (joinStatusOf(chatId)) {
        JoinStatus.JOINED -> true
        JoinStatus.NOT_JOINED -> false
        JoinStatus.UNKNOWN -> true // R64 保守口径（既有调用方语义零变化）
    }

/** 合集链接收割的会话请求预算：addlist 形态稀缺，5 次足够覆盖正常刷 Feed 的偶遇量 */
private const val FOLDER_HARVEST_MAX_CALLS = 5
