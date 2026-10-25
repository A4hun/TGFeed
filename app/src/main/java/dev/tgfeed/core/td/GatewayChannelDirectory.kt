package dev.tgfeed.core.td

import android.util.Log
import io.github.tdlibandroid.ktx.TdException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.drinkless.tdlib.TdApi

/** listSearchableChats 进程内缓存快照（TTL 同 [CHANNEL_LIST_TTL_MS]）；@Volatile 保证跨线程可见 */
internal class SearchableChatsSnapshot(val atMs: Long, val chats: List<ChannelBrief>)

/**
 * 可搜索的会话全集：频道（isChannel=true）+ 群组（isChannel=false 超级群）。
 * 修"搜不全"的大漏：listChannels 只收频道，群组里的资源永远搜不到
 *（用户实测：群组里明明有很多 #知心妈妈，搜索就是搜不到）。
 *
 * SEARCH-PERF §3 提速（两处）：
 *  1. TTL 缓存（5 分钟，与 listChannels 同口径）：全量搜索每发起一次（防抖 300ms
 *     后每词一次）都要这份清单，全量枚举 = LoadChats 全量加载 + GetChats×(2+文件夹数)
 *     + 逐 chat GetChat（数百次 JNI 往返），不能每词都付。代价：新加入/退出的会话
 *     最迟 5 分钟才反映进搜索范围（与 listChannels 缓存语义一致，可接受）；
 *  2. 冷路径逐 chat GetChat 并发化（Semaphore([CONCURRENCY]) 限流）：原串行
 *     数百次 JNI 往返是首波扇出前的固定延迟，并发 5 路后冷枚举提速约 5 倍。
 *
 * R88 去上限：每列表先 LoadChats 循环到 404 全量加载再全取，长尾会话不再漏。
 */
internal suspend fun TdLibGateway.listSearchableChatsImpl(): List<ChannelBrief> {
    // TTL 内直接命中缓存——同词/换词连续搜索不再重复全量枚举
    searchableChatsSnapshot
        ?.takeIf { System.currentTimeMillis() - it.atMs < CHANNEL_LIST_TTL_MS }
        ?.let { return it.chats }
    awaitAuthorized()
    // R88：主列表/归档/每个文件夹各自 LoadChats 循环到 404（列表互相独立，一个不漏），
    // 再 GetChats 用足够大的 limit 一次全取；整个枚举放 IO 调度器（调用方可能在主线程起协程）
    // R216 全 4 处裸 client.send 过 sendReq（R169 F1 家族收口）：本枚举在
    // fetchAggregatedMediaAll 每轮开头（TTL 5 分钟到期即重跑）——原生管线拥塞时
    // 裸 send 可挂数分钟，loadMore 的 _loading 闸随之卡真，媒体 tab 三触发器
    //（onPage 尾部/末页越界拖动/兜底）全部永久早退=「上滑不触发继续加载」的
    // 卡真形态（R212 频道主页同族死点①，本仓实锤）。超时=普通失败异常，语义同
    // 既有失败路径（GetChats 主列表失败照旧上抛失败本轮；归档/文件夹/逐 chat
    // GetChat 失败照旧跳过不阻塞整表）
    return kotlinx.coroutines.withContext(Dispatchers.IO) {
        loadAllChatsInList(TdApi.ChatListMain())
        val mainIds = sendReq(TdApi.GetChats(TdApi.ChatListMain(), CHAT_ENUM_ALL_LIMIT)).chatIds.toList()
        val archIds = runCatching {
            loadAllChatsInList(TdApi.ChatListArchive())
            sendReq(TdApi.GetChats(TdApi.ChatListArchive(), CHAT_ENUM_ALL_LIMIT)).chatIds.toList()
        }.getOrDefault(emptyList())
        val folderIds = chatFolderIds.flatMap { folderId ->
            runCatching {
                loadAllChatsInList(TdApi.ChatListFolder(folderId))
                sendReq(TdApi.GetChats(TdApi.ChatListFolder(folderId), CHAT_ENUM_ALL_LIMIT)).chatIds.toList()
            }.getOrDefault(emptyList())
        }
        val chatIdSet = LinkedHashSet<Long>()
        chatIdSet += mainIds
        chatIdSet += archIds
        chatIdSet += folderIds
        Log.d(TAG, "频道枚举分列表：主=${mainIds.size} 归档=${archIds.size} 文件夹=${folderIds.size} 合计=${chatIdSet.size}（listSearchableChats）")
        // R90 诊断：归档成员与"文件夹独有"（不在主列表/归档）成员尾号落日志，
        // 供"归档/文件夹频道是否进窗/产出"交叉核对；文件夹频道绝大多数同时
        // 在主列表（文件夹只是主列表的分类视图），差集通常为 0
        val folderOnly = folderIds.filter { it !in mainIds && it !in archIds }
        Log.d(TAG, "频道枚举归档成员：共 ${archIds.size} 个，尾号=${archIds.joinToString(",") { it.toString().takeLast(6) }}")
        Log.d(TAG, "频道枚举文件夹独有成员：共 ${folderOnly.size} 个，尾号=${folderOnly.joinToString(",") { it.toString().takeLast(6) }}")
        // 逐 chat GetChat 并发化（原串行）：awaitAll 保序（与 chatIdSet 同序）；
        // 单个 GetChat 失败只跳过该会话，不影响整表（R216 起超时也按失败跳过）
        val ids = chatIdSet.toList()
        val sem = Semaphore(CONCURRENCY)
        val result = coroutineScope {
            ids.map { id ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        runCatching { sendReq(TdApi.GetChat(id)) }.getOrNull()
                    }
                }
            }.awaitAll().mapIndexedNotNull { i, chat ->
                // 全覆盖：频道 + 超级群 + 普通小群 + 私聊（Saved Messages/与人私聊都可能有资源）。
                // 用户反馈"还有没有覆盖到的频道都适配一下"——群/私聊里的媒体之前全漏
                chat ?: return@mapIndexedNotNull null
                val id = ids[i]
                channelTitleCache[id] = chat.title
                // R284 时效感知：顺手带出 GetChat 响应现成字段（零新增请求）——
                // messageAutoDeleteTime=自毁 TTL 秒（javap 实证本 AAR 在场）；
                // lastMessage.date=新鲜度信号。落库在调度侧枚举拍（noteChatTtls）
                ChannelBrief(
                    id, chat.title, null,
                    ttlSec = chat.messageAutoDeleteTime,
                    lastMessageEpochSec = chat.lastMessage?.date?.toLong() ?: 0L,
                )
            }
        }
        Log.d(TAG, "频道枚举：共 ${result.size} 个（含群组/私聊/归档/文件夹）")
        searchableChatsSnapshot = SearchableChatsSnapshot(System.currentTimeMillis(), result)
        result
    }
}

/** R216 id-only 枚举快照（TTL 同 [CHANNEL_LIST_TTL_MS]） */
internal class SearchableChatIdsSnapshot(val atMs: Long, val ids: List<Long>)

/**
 * R216 媒体 tab 路径专用：**免 GetChat 标题清扫**的会话 id 枚举。
 *
 * 根因（用户实锤「音频/图文 tab 固定数量后上滑不加载」的楔死形态，01:04~01:12 实机
 * 复现：频道枚举分列表日志出现后 8 分钟无完成行）：[listSearchableChatsImpl] 的
 * 逐 chat GetChat 清扫（598 次、并发 5）在深度索引爬虫风暴下每个 GetChat 排队 ~4s
 * ——整轮枚举 ~8 分钟起步（R216 前（裸 send）可永久挂死，R216 Fix D 的 10s 单发
 * 超时也只把无上限封到 ~20 分钟）。媒体 tab 的 loadMore 每轮都要付这次枚举
 * （TTL 5 分钟 < 枚举耗时 → 快照从未落成、缓存永不命中）——期间 `_loading` 闸
 * 锁死 onPage 尾部/末页拖动/兜底三触发器，列表冻结在固定数量。与 R212 频道主页
 * 「loading 卡真」同族死点①，本仓 feed 媒体路径的现场。
 *
 * 媒体窗口环形轮转只消费 id（fetchAggregatedMediaAll 的 `allIds`，标题走 toVideoItem
 * 的 channelTitle 懒解析——频道级一次、缓存、失败回落空串的既有链）——本方法只做
 * LoadChats×3 + GetChats×3（全部过 [sendReq] 10s 出口，总数个位数、聚合有界），
 * 598 次 GetChat 的标题清扫整段跳过。全量枚举快照 TTL 内则直接取其 id（免费命中，
 * 爬虫节拍器/搜索路径刚枚举过时零请求）。
 */
internal suspend fun TdLibGateway.listSearchableChatIdsImpl(): List<Long> {
    // 全量快照 TTL 内：白拿它的 id（别人刚付过枚举成本）
    searchableChatsSnapshot
        ?.takeIf { System.currentTimeMillis() - it.atMs < CHANNEL_LIST_TTL_MS }
        ?.let { return it.chats.map { c -> c.id } }
    searchableChatIdsSnapshot
        ?.takeIf { System.currentTimeMillis() - it.atMs < CHANNEL_LIST_TTL_MS }
        ?.let { return it.ids }
    awaitAuthorized()
    return kotlinx.coroutines.withContext(Dispatchers.IO) {
        loadAllChatsInList(TdApi.ChatListMain())
        val mainIds = sendReq(TdApi.GetChats(TdApi.ChatListMain(), CHAT_ENUM_ALL_LIMIT)).chatIds.toList()
        val archIds = runCatching {
            loadAllChatsInList(TdApi.ChatListArchive())
            sendReq(TdApi.GetChats(TdApi.ChatListArchive(), CHAT_ENUM_ALL_LIMIT)).chatIds.toList()
        }.getOrDefault(emptyList())
        val folderIds = chatFolderIds.flatMap { folderId ->
            runCatching {
                loadAllChatsInList(TdApi.ChatListFolder(folderId))
                sendReq(TdApi.GetChats(TdApi.ChatListFolder(folderId), CHAT_ENUM_ALL_LIMIT)).chatIds.toList()
            }.getOrDefault(emptyList())
        }
        val chatIdSet = LinkedHashSet<Long>()
        chatIdSet += mainIds
        chatIdSet += archIds
        chatIdSet += folderIds
        val ids = chatIdSet.toList()
        Log.d(TAG, "媒体路径 id 枚举：共 ${ids.size} 个（免 GetChat 清扫，主=${mainIds.size} 归档=${archIds.size}）")
        searchableChatIdsSnapshot = SearchableChatIdsSnapshot(System.currentTimeMillis(), ids)
        ids
    }
}

/** 按用户名解析频道（@xxx → ChannelBrief）；不是频道/不存在返回 null */
internal suspend fun TdLibGateway.resolveChannelBriefImpl(username: String): ChannelBrief? = runCatching {
    awaitAuthorized()
    // R44 过闸（R184 升级：Ex 内含 resolve-once 缓存/账号账本/双族 429 上报）；
    // 调用方 FeedScreen 的 @提及点击=用户动作车道
    val chat = sendSearchPublicChatGuarded(username.trim().removePrefix("@"), SearchQuotaLedger.Lane.USER_ACTION)
        ?: return@runCatching null
    val type = chat.type
    if (type is TdApi.ChatTypeSupergroup && type.isChannel) {
        channelTitleCache[chat.id] = chat.title
        ChannelBrief(chat.id, chat.title, null)
    } else {
        null
    }
}.getOrNull()

/**
 * R183 开频道自愈（阶段C 真机实锤 9/9「Chat not found」的修复；R184 配额治理重构）：
 * 深索引候选频道 TDLib 本地无会话记录（SearchChatMessages 爬取不建会话），GetChatHistory
 * 必炸——按线索解析建会话后即可正常读历史：
 *  0) GetChat 直查（fast path——本地已知毫秒级零网络，正常路径无感）；
 *  0.5) resolve-once 缓存（R184）：username 有历史解析记录 → 该会话曾被打开过，
 *     重试 GetChat 验证（缓存命中但 GetChat 未中=条目失效，移除后走搜索路）；
 *  1) username 路（[searchPublicChatGuardedEx] 统一发送口：熔断+账号账本+搜完建缓存；
 *     用户名过期命中别的频道时不认，继续标题路）；
 *  2) 标题路（[searchChatsOnServerGuarded]——R184 过闸：SearchChatsOnServer 与
 *     SearchPublicChat 双族同静默，封禁窗内连试都不试防续封）。
 *     全标题 + 首段（「|」前）最多两查。
 *
 * **只认 chatId 严格相等**：搜索只是定位手段，绝不接受标题相似但 id 不同的频道——
 * 候选的丰度/周更/群搜数据全部绑定在索引 chatId 上，仿冒频道会张冠李戴。
 * 限流（封禁窗/熔断/账号预算拒/429）返回 [ChannelOpenHeal.RateLimited]——调用方
 * （ChannelFeedSource）诚实上抛「搜索暂时被限流」并入队后台重试；其余失败
 * [ChannelOpenHeal.NotFound]（调用方走既有错误态；调用侧须加超时，参考 ChannelFeedSource）。
 */
internal suspend fun TdLibGateway.ensureChannelOpenableImpl(
    chatId: Long,
    title: String,
    username: String?,
): ChannelOpenHeal {
    awaitAuthorized()
    // 0) 本地已知：毫秒级本地读，正常路径零网络零感知
    // R366：超时兜底——GetChat 挂起 12s 到点按「未命中」续走解析路（与既有失败口径一致，
    // 不再永挂；「查不了」≠「打不开」的 R169 F6 判词不破——超时落失败分类由后续路径承担）
    if (runCatching {
        withTdTimeout(what = "ensureChannelOpenable/GetChat chatId=$chatId") { client.send(TdApi.GetChat(chatId)) }
    }.getOrNull() != null) return ChannelOpenHeal.Success
    val name = username?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() }
    // 0.5) resolve-once 缓存：解析史里有此名 → 会话曾建过，GetChat 再验一次
    //      （首查未中可能是瞬态，缓存证身后再验；仍未中=条目失效，移除走搜索路）
    if (name != null) {
        resolveOnceCache.lookup(name)?.let { cached ->
            if (cached.chatId == chatId) {
                if (runCatching {
                    withTdTimeout(what = "ensureChannelOpenable/GetChat 缓存验身 chatId=$chatId") { client.send(TdApi.GetChat(chatId)) }
                }.getOrNull() != null) {
                    Log.d(TAG, "ensureChannelOpenable: @$name resolve-once 缓存命中（GetChat 直通 chatId=$chatId，零搜索）")
                    return ChannelOpenHeal.Success
                }
                resolveOnceCache.remove(name)
                Log.d(TAG, "ensureChannelOpenable: @$name 缓存条目失效（GetChat 未命中），走搜索路")
            }
        }
    }
    // 1) username 路（精确；统一发送口=熔断+账号账本+搜完建缓存全在此内）
    if (name != null) {
        when (val r = searchPublicChatGuardedEx(name, SearchQuotaLedger.Lane.USER_ACTION)) {
            is GuardedSearchResult.Hit -> {
                if (r.chat.id == chatId) {
                    Log.d(TAG, "ensureChannelOpenable: @$name 解析建会话成功 chatId=$chatId（已建 resolve-once 缓存）")
                    return ChannelOpenHeal.Success
                }
                Log.d(TAG, "ensureChannelOpenable: @$name 命中异频道 id=${r.chat.id}（期望 $chatId）不认，走标题路")
            }
            is GuardedSearchResult.FloodWait -> {
                Log.w(TAG, "ensureChannelOpenable: @$name 撞服务端 429（retryAfter=${r.retryAfterSec}s），限流降级")
                return ChannelOpenHeal.RateLimited(r.retryAfterSec)
            }
            GuardedSearchResult.Rejected -> {
                Log.w(TAG, "ensureChannelOpenable: @$name 搜索被本地闸拒（封禁/熔断/账号预算），限流降级")
                return ChannelOpenHeal.RateLimited(0)
            }
            // 400 用户名失效/瞬态网络失败：username 路确无戏，落标题路继续找
            GuardedSearchResult.NotFound, GuardedSearchResult.Transient -> Unit
        }
    }
    // 2) 标题路（最多两查：全标题 + 首段；R184 过闸版——封禁窗内连试都不试）
    val t = title.trim()
    if (t.length < 2) return ChannelOpenHeal.NotFound
    val queries = ArrayList<String>(2)
    queries += t
    t.substringBefore('|').trim().takeIf { it.length >= 2 && it != t }?.let { queries += it }
    for (q in queries) {
        val found = searchChatsOnServerGuarded(q, null, 5, SearchQuotaLedger.Lane.USER_ACTION)
        if (found == null) {
            // null = 封禁/熔断/预算拒（未发送）或发送失败——封禁/熔断期不再硬闯标题路
            if (accountSearchBanned()) {
                val remain = searchQuotaLedger.remainingBanSec()
                Log.w(TAG, "ensureChannelOpenable: 标题「$q」搜索被闸拒（封禁窗剩 ${remain}s），限流降级")
                return ChannelOpenHeal.RateLimited(remain.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
            Log.d(TAG, "ensureChannelOpenable: 标题「$q」搜索失败（瞬态），下一查")
            continue
        }
        val ids = found.chatIds
        if (ids == null) {
            Log.d(TAG, "ensureChannelOpenable: SearchChatsOnServer「$q」返回空 ids")
            continue
        }
        if (chatId in ids) {
            // 搜索结果即本地会话（resolveBotChatViaServerSearch 先例）；GetChat 收尾确认
            // R366：超时兜底——确认读挂起 12s 到点按未确认（NotFound，与既有失败口径一致）
            val ok = runCatching {
                withTdTimeout(what = "ensureChannelOpenable/GetChat 搜后确认 chatId=$chatId") { client.send(TdApi.GetChat(chatId)) }
            }.getOrNull() != null
            if (ok) Log.d(TAG, "ensureChannelOpenable: 标题「$q」搜索建会话成功 chatId=$chatId")
            return if (ok) ChannelOpenHeal.Success else ChannelOpenHeal.NotFound
        }
        Log.d(TAG, "ensureChannelOpenable: SearchChatsOnServer「$q」候选 ${ids.size} 个无本 chatId")
    }
    Log.d(TAG, "ensureChannelOpenable: 解析未命中 chatId=$chatId title=$title username=$name")
    return ChannelOpenHeal.NotFound
}

/**
 * R379 诚实降级文案（最小 UX 钩）：熔断 REJECT 态消费方呈现「限流保护中，预计
 * HH:mm 恢复」（墙钟时刻；[FloodCircuitBreaker.remainingSec] 口已有——秒数在消费
 * 点折墙钟）。零剩余（如纯半开限量态）退回既有泛词不编时刻（诚实优先）。
 * 发现页降级=本地缓存+时间戳（R372 雷达段读库零网络天然兼容）；完整 UX 打磨
 * 归发现域后续批。
 */
private fun floodProtectionText(remainSec: Long): String {
    if (remainSec <= 0) return "频道搜索限流中，请稍后再试"
    val at = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(System.currentTimeMillis() + remainSec * 1000L))
    return "限流保护中，预计 $at 恢复（保护账号，稍后自动重试）"
}

/**
 * caption t.me 链接点击的公开频道解析（与 [resolveChannelBrief] 同为 SearchPublicChat，
 * 差别在**失败分类不静默**，UI 层按类别给反馈——修"点未加入频道毫无反应"）：
 *  - 解析成功且是频道 → 公开频道未加入也可浏览（SearchPublicChat 同时承担"打开会话"，
 *    之后 GetChatHistory 读历史无需加入；ChannelFeedSource/ChannelHomeScreen 链路直接可用）。
 *    敏感内容频道：init 时已尝试 SetOption(ignore_sensitive_content_restrictions=true)
 *    （见上文，能否生效取决于服务端 can_ignore_sensitive_content_restrictions，账号/地区
 *    被禁时官方客户端与咱们同样读不到内容，但主页仍可打开）；
 *  - 解析成功但不是频道（用户/机器人/普通群）→ [PublicChannelResolve.NotChannel]；
 *  - 用户名不存在/已注销（400 USERNAME_NOT_OCCUPIED / USERNAME_INVALID）
 *    → [PublicChannelResolve.NotFound]；
 *  - 其余请求失败（网络/限流等）→ [PublicChannelResolve.Failed]。
 */
internal suspend fun TdLibGateway.resolvePublicChannelImpl(username: String): PublicChannelResolve {
    awaitAuthorized()
    val name = username.trim().removePrefix("@")
    // R184 resolve-once 缓存：该名解析过一次 → GetChat 验证会话仍在 → 直通 Channel
    // （零搜索零预算——深链复验/R182 式冷启动循环不再重烧；封禁窗内已解析频道同样能开）
    resolveOnceCache.lookup(name)?.let { cached ->
        // R366：超时兜底——缓存验身挂起 12s 到点按「缓存失效」走真解析（与既有失败口径一致）
        val chat = runCatching {
            withTdTimeout(what = "resolvePublicChannel/GetChat 缓存验身 chatId=${cached.chatId}") {
                client.send(TdApi.GetChat(cached.chatId))
            }
        }.getOrNull()
        if (chat != null) {
            channelTitleCache[chat.id] = chat.title
            Log.d(TAG, "resolvePublicChannel: @$name resolve-once 缓存命中（GetChat 直通 chatId=${cached.chatId}，零搜索）")
            return if ((chat.type as? TdApi.ChatTypeSupergroup)?.isChannel == true) {
                PublicChannelResolve.Channel(ChannelBrief(chat.id, chat.title, name))
            } else {
                PublicChannelResolve.NotChannel
            }
        }
        resolveOnceCache.remove(name) // 会话不在（缓存失效）→ 走真解析
        Log.d(TAG, "resolvePublicChannel: @$name 缓存条目失效（GetChat 未命中），走真解析")
    }
    // 熔断/半开限量/账号封禁窗：按 Failed 交 UI「稍后再试」——半开试探额度仍留给
    // 后台专用通道（用户点击不烧试探发数，R58 政策保留）；R184 账号账本封禁窗同口径
    val family = FloodCircuitBreaker.FAMILY_SEARCH_PUBLIC_CHAT
    if (floodBreaker.stateOf(family) != FloodCircuitBreaker.BreakerState.CLOSED ||
        searchQuotaLedger.remainingBanSec() > 0
    ) {
        val remain = maxOf(floodBreaker.remainingSec(family), searchQuotaLedger.remainingBanSec())
        Log.d(TAG, "resolvePublicChannel: 熔断/半开限量/封禁窗中（剩 ${remain}s），跳过 @$name")
        // R379 最小 UX 钩：限流保护中+预计恢复时刻直出（不落泛词「稍后再试」）
        return PublicChannelResolve.Failed(floodProtectionText(remain))
    }
    // R184：真发送统一走 Ex（熔断+账号账本+搜完建缓存；429 双族上报+累进记忆全在内）
    return when (val r = searchPublicChatGuardedEx(name, SearchQuotaLedger.Lane.USER_ACTION)) {
        is GuardedSearchResult.Hit -> {
            val type = r.chat.type
            if (type is TdApi.ChatTypeSupergroup && type.isChannel) {
                PublicChannelResolve.Channel(ChannelBrief(r.chat.id, r.chat.title, name))
            } else {
                PublicChannelResolve.NotChannel
            }
        }
        GuardedSearchResult.NotFound -> PublicChannelResolve.NotFound
        is GuardedSearchResult.FloodWait, GuardedSearchResult.Rejected ->
            // R379 最小 UX 钩：预计恢复时刻直出（熔断静默/半开与账本封禁窗取大者）
            PublicChannelResolve.Failed(
                floodProtectionText(maxOf(floodBreaker.remainingSec(family), searchQuotaLedger.remainingBanSec())),
            )
        GuardedSearchResult.Transient ->
            PublicChannelResolve.Failed("频道解析失败，请检查网络后重试")
    }
}

// ---------------- t.me 深链路由（系统选择器 → MainActivity） ----------------

/**
 * t.me/c/<internalId>[/<msgId>] 私有频道内部链：已加入才能开。
 * internalId → 真实 chatId 的换算（Telegram 公开约定，调用方完成）：
 * chatId = -(1_000_000_000_000 + internalId)，即链接里那串数字是 -100 前缀被剥掉的部分。
 * GetChat 成功且是频道 → ChannelBrief；未加入（TDLib 本地无此会话，400）或不是频道 → null。
 * 已加入的会话 GetChat 必命中（TDLib 已同步过），未加入时唯一可靠信号就是查不到。
 */
internal suspend fun TdLibGateway.resolveJoinedChannelImpl(chatId: Long): ChannelBrief? = runCatching {
    awaitAuthorized()
    // R169：同文件同族裸 send 顺手收编（census F1 清单漏列的类内成员；本方法已无活跃
    // 调用方，被 resolveDeepLinkChat 三态取代，留着是深链回退口径——超时按既有 null 落空）
    val chat = sendReq(TdApi.GetChat(chatId))
    val type = chat.type
    if (type is TdApi.ChatTypeSupergroup && type.isChannel) {
        channelTitleCache[chat.id] = chat.title
        ChannelBrief(chat.id, chat.title, null)
    } else {
        null
    }
}.getOrNull()

/**
 * t.me/c/<internalId>[/<msgId>] 深链的解析（R47 三态 + R169 第四态 Failed）：
 *  1) GetChat(chatId) 命中 → [DeepLinkChatResolve.Joined]（已加入；群组也收，isChannel 标注）；
 *  2) GetChat 失败（未加入/TDLib 本地不认识）→ 回落 GetMessageLinkInfo(rawLink)——
 *     公开频道未加入也能探出消息与真实 chatId（R41 实证），探到再 GetChat 组 brief
 *     → [DeepLinkChatResolve.OpenableViaLink]；
 *  3) 探测请求成功但无消息 / TDLib 400 类稳定终态 → [DeepLinkChatResolve.Inaccessible]
 *     （真私有且未加入，或链接本身无消息——服务端权威说此路不通）；
 *  4) 探测请求失败（429/超时/网络）→ [DeepLinkChatResolve.Failed]（R169 F6：网络抖动
 *     误判"真私有不可达"是 R70 同病——「查不了」≠「打不开」，UI 给重试口径）。
 * 全部 send 过 [sendReq] 10s 超时（R169 F1）：深链挂死=点链接无响应且零日志。
 *
 * @param chatId 链接 internalId 已按 -(1e12+internalId) 口径换算好的真实 chatId
 * @param rawLink 规范化后的原始 t.me/c/... 链接（不含 query 参数），供 GetMessageLinkInfo 用
 */
internal suspend fun TdLibGateway.resolveDeepLinkChatImpl(chatId: Long, rawLink: String): DeepLinkChatResolve {
    awaitAuthorized()
    // 1) 已加入：TDLib 已同步过该会话，GetChat 必命中
    val chat = runCatching { sendReq(TdApi.GetChat(chatId)) }.getOrNull()
    if (chat != null) {
        val type = chat.type
        if (type is TdApi.ChatTypeSupergroup) {
            channelTitleCache[chat.id] = chat.title
            Log.i(TAG_FLOW, "深链解析: GetChat 命中 chatId=$chatId isChannel=${type.isChannel} 标题=${chat.title}")
            return DeepLinkChatResolve.Joined(
                ChannelBrief(chat.id, chat.title, null),
                isChannel = type.isChannel,
            )
        }
        // t.me/c/ 形态只会指向频道/超级群（-100 前缀口径）；其余类型按不可达处理
        Log.i(TAG_FLOW, "深链解析: GetChat 命中但非频道/群组 chatId=$chatId type=${type.javaClass.simpleName}")
        return DeepLinkChatResolve.Inaccessible
    }
    // 2) 未加入/TDLib 不认识：回落链接探测。
    // R169（F6 三态，PlazaFindResult/PublicChatLookup 同范式）：失败分类不落 Inaccessible——
    // 旧口径 runCatching 落空一律 Inaccessible，网络抖动/限流把可打开的公开链误报"真私有"
    Log.i(TAG_FLOW, "深链解析: GetChat 未命中 chatId=$chatId，回落 GetMessageLinkInfo link=$rawLink")
    val info = try {
        sendReq(TdApi.GetMessageLinkInfo(rawLink))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e // 协程取消不是解析失败，必须上抛（resolvePublicChatByUsername 同口径）
    } catch (e: Throwable) {
        return classifyDeepLinkProbeFailure(e, "GetMessageLinkInfo link=$rawLink")
    }
    val msgChatId = info.message?.chatId ?: 0L
    if (msgChatId == 0L) {
        // 请求成功但没探出消息：服务端答过话，才是权威的"真私有未加入/纯频道主页链"
        Log.i(TAG_FLOW, "深链解析: 链接探测未出消息（真私有未加入或纯频道主页链）link=$rawLink")
        return DeepLinkChatResolve.Inaccessible
    }
    val chat2 = try {
        sendReq(TdApi.GetChat(msgChatId))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        return classifyDeepLinkProbeFailure(e, "GetChat msgChatId=$msgChatId（链接已探到消息）")
    }
    val type2 = chat2.type
    if (type2 !is TdApi.ChatTypeSupergroup) {
        Log.w(TAG_FLOW, "深链解析: 链接探到消息但会话非频道/群组 msgChatId=$msgChatId type=${type2.javaClass.simpleName}")
        return DeepLinkChatResolve.Inaccessible
    }
    channelTitleCache[chat2.id] = chat2.title
    Log.i(TAG_FLOW, "深链解析: 未加入但链接可开 chatId=${chat2.id} isChannel=${type2.isChannel} 标题=${chat2.title}")
    return DeepLinkChatResolve.OpenableViaLink(ChannelBrief(chat2.id, chat2.title, null))
}

/**
 * 深链探测失败分类（R169 F6，resolveDeepLinkChatImpl 两处探测共用；纯函数便于单测锚定）：
 *  - TDLib 400 类稳定终态 → [DeepLinkChatResolve.Inaccessible]（服务端权威说此路不通，
 *    重试无益——与 PlazaFindResult.NotFound 的 code==400 口径一致）；
 *  - 429/超时（[sendReq] 已转 TimeoutException）/网络/未知异常 →
 *    [DeepLinkChatResolve.Failed]（「查不了」≠「打不开」，reason 供日志与 UI 参考）。
 */
internal fun classifyDeepLinkProbeFailure(e: Throwable, where: String): DeepLinkChatResolve {
    if (e is TdException && e.code == 400) {
        Log.i(TAG_FLOW, "深链解析: $where 服务端稳定终态（code=400 ${e.message}）→ Inaccessible")
        return DeepLinkChatResolve.Inaccessible
    }
    val reason = e.message?.takeIf { it.isNotBlank() }
        ?.let { "${e.javaClass.simpleName}: $it" }
        ?: e.javaClass.simpleName
    Log.w(TAG_FLOW, "深链解析: $where 瞬态失败 → Failed（$reason）")
    return DeepLinkChatResolve.Failed(reason)
}

/**
 * t.me/+xxx（含 t.me/joinchat/xxx）邀请链接：先看是否已是成员，不是就直接加入。
 *
 * 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
 *   CheckChatInviteLink(String inviteLink) -> ChatInviteLinkInfo{
 *       chatId: long,           // 加入前即可访问（=已是成员/公开可预览）时非 0
 *       title: String, type: InviteLinkChatType, memberCount, isPublic, …}
 *   JoinChatByInviteLink(String inviteLink) -> ChatJoinResult（抽象类，子类：
 *       ChatJoinResultSuccess{chatId: long} / ChatJoinResultRequestSent /
 *       ChatJoinResultDeclined / ChatJoinResultGuardBotApprovalRequired）
 *   GetChat(long) -> Chat{type: ChatTypeSupergroup{isChannel}}（同 resolveChannelBrief 口径）
 *
 * 注意 JoinChatByInviteLink 在本版返回 ChatJoinResult（不是旧版的 Chat），
 * 成功 id 从 ChatJoinResultSuccess.chatId 取，再 GetChat 组 brief。
 */
internal suspend fun TdLibGateway.resolveInviteLinkImpl(inviteLink: String): InviteLinkResolve {
    awaitAuthorized()
    // 1) 先查链接指向：已是成员（chatId != 0）零副作用直通，不再发 JoinChat
    // R366：超时兜底——隐性限流挂起 12s 到点按诚实失败回落（如实「超时」不落「无效」）；
    // 真协程取消不吞（与 resolveDeepLinkChat 同口径）
    val infoOrNull = try {
        withTdTimeout(what = "resolveInviteLink/CheckChatInviteLink") {
            client.send(TdApi.CheckChatInviteLink(inviteLink))
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        return InviteLinkResolve.Failed(friendlyError(e, "邀请链接无效或已失效"))
    }
    val info = infoOrNull
        ?: return InviteLinkResolve.Failed("链接检查超时（网络可能限流），请稍后重试")
    if (info.chatId != 0L) {
        val chat = runCatching {
            withTdTimeout(what = "resolveInviteLink/GetChat chatId=${info.chatId}") {
                client.send(TdApi.GetChat(info.chatId))
            }
        }.getOrNull()
            ?: return InviteLinkResolve.Failed("频道信息获取失败，请稍后重试")
        val t = chat.type
        return if (t is TdApi.ChatTypeSupergroup && t.isChannel) {
            channelTitleCache[chat.id] = chat.title
            InviteLinkResolve.Joined(ChannelBrief(chat.id, chat.title, null), justJoined = false)
        } else {
            InviteLinkResolve.Failed("该链接指向的不是频道（可能是群组）")
        }
    }
    // 2) 未加入：直接加入（用户点链接的意图就是进频道，与官方客户端行为一致）
    // R366：超时兜底——加入请求挂起 12s 到点按诚实失败回落（如实「超时」不落「加入失败」
    // 泛词；结果未知——可能已在服务端排队，提示重试由用户定夺）；真协程取消不吞
    val resultOrNull = try {
        withTdTimeout(what = "resolveInviteLink/JoinChatByInviteLink") {
            client.send(TdApi.JoinChatByInviteLink(inviteLink))
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        return InviteLinkResolve.Failed(friendlyError(e, "加入失败"))
    }
    val result = resultOrNull
        ?: return InviteLinkResolve.Failed("加入请求超时（网络可能限流），结果未知——请稍后开频道确认")
    return when (result) {
        is TdApi.ChatJoinResultSuccess -> {
            val chat = runCatching {
                withTdTimeout(what = "resolveInviteLink/GetChat 入后确认 chatId=${result.chatId}") {
                    client.send(TdApi.GetChat(result.chatId))
                }
            }.getOrNull()
                ?: return InviteLinkResolve.Failed("已加入，但频道信息获取失败")
            val t = chat.type
            if (t is TdApi.ChatTypeSupergroup && t.isChannel) {
                channelTitleCache[chat.id] = chat.title
                InviteLinkResolve.Joined(ChannelBrief(chat.id, chat.title, null), justJoined = true)
            } else {
                InviteLinkResolve.Failed("已加入，但这不是频道（可能是群组）")
            }
        }
        is TdApi.ChatJoinResultRequestSent -> InviteLinkResolve.RequestSent
        is TdApi.ChatJoinResultDeclined -> InviteLinkResolve.Declined
        // GuardBotApprovalRequired 等其余子类：安全机器人拦着，无法自动加入
        else -> InviteLinkResolve.Failed("加入被拦（频道安全机器人审核），请先在 Telegram 中加入")
    }
}
