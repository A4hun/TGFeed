package dev.tgfeed.core.td

import android.util.Log
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi

// ---------------- Inline bot 盲盒流（R135）：inline 查询原语 ----------------
//
// 原理：@gif/@vid/@pic 这类"为 inline 而生"的大公共 bot 是全 TG 机器人宇宙的
// 即席内容水龙头——GetInlineQueryResults 用关键词现拉一批结果（Photo/Video/
// Animation 等），翻页靠 nextOffset 续拉。inline 结果里的 File 不带稳定下载
// 语义（要经消息上下文才能真正下载），所以本链路把结果逐条
// SendInlineQueryResultMessage 发进收藏夹（Saved Messages，chatId = 本人
// user id，网关已有 getSavedMessagesChatIdImpl 现成通道），再从发出的消息里
// 取媒体转 VideoItem——消息是 TDLib 文件下载的合法锚点，fileId/fileUniqueId
// 从此稳定（播放/预载/判重链全复用既有视频链路）。
//
// 本文件只搬 TDLib 原语、不做降级决策（换 bot/节流/验证墙透传的编排都在
// core/feed/InlineBotFeedSource.kt）；失败一律返回 null/空并记日志，不向上抛。
//
// 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
// GetInlineQueryResults(long botUserId, long chatId, Location userLocation,
//                       String query, String offset) -> InlineQueryResults
//   InlineQueryResults{long inlineQueryId, InlineQueryResultsButton button,
//                      InlineQueryResult[] results, String nextOffset}
//   —— 任务书假设的"botChatId+query+offset"三参实为五参：botUserId 是 bot 的
//      user id（resolveBotUserId 用户名解析），chatId 是发起查询的会话
//     （取收藏夹会话，私有会话对 inline bot 是最宽容的环境），userLocation
//      传 null（@gif/@vid/@pic 不消费位置）
// InlineQueryResultVideo{id: String, video: Video, title, description}
// InlineQueryResultPhoto{id: String, photo: Photo, title, description}
// InlineQueryResultAnimation{id: String, animation: Animation, title}
//   —— 视频/图/动图三类可播结果；Animation 是 TG 的 GIF（mp4 动画），
//      toVideoItem 的 MessageAnimation 分支按视频出（ExoPlayer 直接可播）
// SendInlineQueryResultMessage(long chatId, MessageTopic topic,
//                              InputMessageReplyTo replyTo, MessageSendOptions options,
//                              long queryId, String resultId, boolean hideViaBot) -> Message
//   —— "inline 结果引用消息"的正名：任务书假设的 InputMessageInlineQueryResult
//      在本 AAR 不存在（javap 找不到类），TDLib 把它做成了这条专用 Function；
//      发出的消息 content 即真实 MessageVideo/MessagePhoto/MessageAnimation
//      （javap 实证 SendInlineQueryResultMessage extends Function<Message>）
// InlineQueryResultsButton{text: String, type: InlineQueryResultsButtonType}
// InlineQueryResultsButtonTypeStartBot{parameter: String}
//   —— bot 的验证墙/引导按钮（如"点此启动机器人"）：results 为空 + button 存在
//      时即验证墙信号。本链路如实透传文案给上层（不绕过、不代点），由
//      InlineBotFeedSource 报告给用户

/** inline 查询一轮的原始结果页（网关只搬事实，编排/降级在上层） */
internal class InlineBotQueryPage(
    /** 本轮查询的服务端会话 id：SendInlineQueryResultMessage 必须用它（短时效，随取随用） */
    val queryId: Long,
    /** 原始 inline 结果（Photo/Video/Animation 等类型混排） */
    val results: List<TdApi.InlineQueryResult>,
    /** 翻页游标：null = 该 bot 在本查询上到底了 */
    val nextOffset: String?,
    /** bot 验证墙文案（InlineQueryResultsButton.text，类型为 StartBot 时）；null = 无墙 */
    val startBotText: String?,
)

/**
 * inline bot 用户名 → userId（防熔断版，盲盒流专用正路），按成本从低到高：
 *  1. 正路 [resolveBotUserIdImpl]（SearchPublicChat 家族，进程内缓存）；
 *  2. [getRecentInlineBotUserId]（GetRecentInlineBots——账号最近用过的 inline
 *     bot 名册，TDLib 本地/轻请求，服务端搜索前先白嫖这一手）；
 *  3. 旁路 [resolveInlineBotViaServerSearch]——SearchChatsOnServer（与熔断表
 *     封的方法族**不同族**）：用户名精确搜 → Bot 过滤器搜 → 无过滤器搜 →
 *     官方 bot 展示名搜（用户名"vid"翻不出时用展示名兜，
 *     [INLINE_BOT_DISPLAY_NAMES]），逐候选 GetChat+GetUser 验身；
 *  4. 命中的 userId 回填 [botUserIdCache]，后续调用直接缓存命中零请求。
 *
 * 防轰炸纪律：全链失败记入负缓存（[INLINE_BOT_RESOLVE_NEG_TTL_MS] 5 分钟），
 * 期间再来问直接 null——绝不反复打熔断中的方法族或服务端搜索。
 */
internal suspend fun TdLibGateway.resolveInlineBotUserIdImpl(bot: String): Long? {
    val key = bot.trim().removePrefix("@").lowercase()
    if (key.isEmpty()) return null
    // 负缓存：近期解析失败过（正路熔断+旁路也未中）→ 直接 null，防死磕
    inlineBotResolveFailedAt[key]?.let { failedAt ->
        if (System.currentTimeMillis() - failedAt < INLINE_BOT_RESOLVE_NEG_TTL_MS) {
            Log.w(TAG, "inline bot 解析负缓存命中 @$key（5 分钟内不再重试）")
            return null
        }
    }
    // 正路（含 botUserIdCache 进程内缓存；R184：盲盒流是用户动作 → 用户动作车道，
    // 且 Ex 内置 resolve-once 缓存——解析过一次的 bot 跨进程零搜索直通）
    resolveBotUserIdImpl(key, SearchQuotaLedger.Lane.USER_ACTION)?.let { return it }
    // 名册白嫖：账号最近用过的 inline bot（本地态，服务端搜索前先试）
    getRecentInlineBotUserId(key)?.let { return it }
    // 旁路：SearchPublicChat 熔断期的保命链（不同方法族）
    val userId = resolveInlineBotViaServerSearch(key)
    if (userId != null && userId > 0) {
        botUserIdCache[key] = userId
        Log.i(TAG, "inline bot 旁路解析成功 @$key -> userId=$userId（已回填缓存）")
        return userId
    }
    inlineBotResolveFailedAt[key] = System.currentTimeMillis()
    Log.w(TAG, "inline bot 解析失败 @$key（正路熔断/旁路未中），负缓存 5 分钟")
    return null
}

/**
 * 官方三大 inline bot 的展示名（用户名→展示名，t.me 页面 og:title 实证）：
 * 账号被搜索限流时，用户名查询在服务端搜索里翻不出官方 bot（实测"vid"只回
 * 3 个野 bot、pic 零候选），展示名全文搜索仍有希望命中官方本体——展示名轮在
 * [resolveInlineBotViaServerSearch] 兜底。只收池内官方 bot，第三方 bot 不猜名。
 * 展示名以 t.me/<bot> 的 og:title 为准：曾按记忆猜"Video Bot/GIF Bot"全错
 * （实测 GetUser/服务端搜索均无此名），t.me 实证真名是 Video Search for
 * YouTube / GIF Search / Yandex Image Search。
 */
internal val INLINE_BOT_DISPLAY_NAMES = mapOf(
    "vid" to "Video Search for YouTube",
    "gif" to "GIF Search",
    "pic" to "Yandex Image Search",
)

/**
 * GetRecentInlineBots 白嫖通道：返回本账号最近用过 inline 模式的 bot 名册，
 * 从中找用户名精确等于 [bot] 的。纯名册查询（TDLib 自维护），成本远低于
 * 服务端搜索；账号从没用过 inline bot 时名册为空，直接跳过。
 * 签名已 javap 实证：GetRecentInlineBots() -> Users{userIds: long[]}
 * （注意是 userId 数组不是 User 数组，逐个 GetUser 取用户名）。
 */
private suspend fun TdLibGateway.getRecentInlineBotUserId(bot: String): Long? = runCatching {
    val ids = sendReq(TdApi.GetRecentInlineBots()).userIds?.toList().orEmpty()
    for (id in ids) {
        val u = runCatching { sendReq(TdApi.GetUser(id)) }.getOrNull() ?: continue
        val names = (u.usernames?.activeUsernames ?: emptyArray()) +
            (u.usernames?.editableUsername?.takeIf { it.isNotBlank() }?.let { arrayOf(it) } ?: emptyArray()) +
            (u.usernames?.collectibleUsernames ?: emptyArray())
        if (names.any { it.equals(bot, ignoreCase = true) }) {
            Log.i(TAG, "inline bot 名册命中 @$bot -> userId=${u.id}")
            return@runCatching u.id
        }
    }
    null
}.onFailure {
    Log.d(TAG, "GetRecentInlineBots 失败（静默跳过）: ${it.message}")
}.getOrNull()

/**
 * 旁路服务端搜索解析 inline bot：SearchChatsOnServer + 逐候选验身。
 *
 * 为什么不复用 GatewayBotSearch.resolveBotChatViaServerSearch：它的候选上限 3
 * 实测不够——官方 @vid 在 Bot 过滤器搜索里排不进前 3（实测"命中 3 候选但
 * 用户名验身全不符"），本函数把量放大到 [INLINE_SEARCH_LIMIT]（15），且
 * Bot 过滤器搜空手时再用无过滤器搜一轮（实测 @pic 在 Bot 过滤器下返回 0
 * 候选）。验身只认**用户名精确等于查询名**的私聊用户，防同名仿冒 bot
 * 冒名顶替（R65 旁路链同款纪律）。
 *
 * 签名已 javap 实证：SearchChatsOnServer(String, SearchChatTypeFilter, int)
 * -> Chats{chatIds: long[]}；GetChat(long) -> Chat{type: ChatType}；
 * GetUser(long) -> User{usernames: Usernames{activeUsernames: String[],
 * editableUsername: String, collectibleUsernames: String[], ...}}
 * （javap 实证本 AAR 的 User 无旧字段 username，用户名只在 Usernames 里）。
 */
private suspend fun TdLibGateway.resolveInlineBotViaServerSearch(bot: String): Long? = runCatching {
    awaitAuthorized()
    // 搜索轮次（每轮一次服务端请求，全部只在解析未命中时逐轮推进）：
    // ① 用户名精确查询 + Bot 过滤器；② 用户名无过滤器（实测 @pic 在 Bot 过滤器
    //    下零候选）；③ 官方展示名 + Bot 过滤器（实测账号被搜索限流时用户名
    //    查询翻不出官方 bot，但展示名全文仍能命中官方本体）
    val queries = buildList {
        add(bot to TdApi.SearchChatTypeFilterBot())
        add(bot to null)
        INLINE_BOT_DISPLAY_NAMES[bot]?.let { add(it to TdApi.SearchChatTypeFilterBot()) }
    }
    for ((query, filter) in queries) {
        // R184：过统一闸（熔断+账号账本——双族同静默期此旁路不再硬闯，防续封）
        val ids = searchChatsOnServerGuarded(query, filter, INLINE_SEARCH_LIMIT, SearchQuotaLedger.Lane.USER_ACTION)
            ?.chatIds?.toList().orEmpty()
        if (ids.isEmpty()) continue
        for (id in ids) {
            val chat = runCatching { sendReq(TdApi.GetChat(id)) }.getOrNull() ?: continue
            val userId = (chat.type as? TdApi.ChatTypePrivate)?.userId ?: continue
            val user = runCatching { sendReq(TdApi.GetUser(userId)) }.getOrNull() ?: continue
            // 验身：活跃/可编辑/可收藏用户名，任一精确等于查询的**用户名**
            // （展示名轮也按用户名验——搜"GIF Bot"只认 username=gif 的那一个，
            // 防同名仿冒 bot 冒名顶替）
            val names = (user.usernames?.activeUsernames ?: emptyArray()) +
                (user.usernames?.editableUsername?.takeIf { it.isNotBlank() }?.let { arrayOf(it) } ?: emptyArray()) +
                (user.usernames?.collectibleUsernames ?: emptyArray())
            if (names.any { it.equals(bot, ignoreCase = true) }) {
                return@runCatching userId
            }
            Log.d(
                TAG,
                "inline bot 旁路候选不符：chat=${chat.title} user=" +
                    (user.usernames?.activeUsernames?.joinToString("/") ?: "?") + " 查询=$query",
            )
        }
        Log.w(TAG, "inline bot 旁路搜索 @$bot 轮「$query」命中 ${ids.size} 候选但验身全不符")
    }
    Log.w(TAG, "inline bot 旁路搜索 @$bot 全轮未中")
    null
}.onFailure {
    Log.w(TAG, "inline bot 旁路解析异常 @$bot: ${it.message}")
}.getOrNull()

/** 旁路服务端搜索候选上限：3 实测不够（官方 @vid 排不进前 3），放宽到 15 */
internal const val INLINE_SEARCH_LIMIT = 15

/** inline bot 解析负缓存：用户名 → 最近一次全失败时刻（毫秒） */
private val inlineBotResolveFailedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

/** inline bot 解析负缓存 TTL：全失败后 5 分钟内不再发起任何解析请求（防轰炸） */
internal const val INLINE_BOT_RESOLVE_NEG_TTL_MS = 5 * 60_000L

/**
 * 对指定 inline bot 发一次关键词查询（[offset] 传 null 从头开始）。
 * 失败/超时返回 null——调用方据此换下一个 bot（克制降级，不重试轰炸）。
 * botUserId 经 [resolveBotUserIdImpl] 解析（含进程内缓存），解析失败同样 null。
 * 内置 15 秒硬超时：bot 拒答/网络卡死时不永久挂起调用方的 loadMore。
 */
internal suspend fun TdLibGateway.getInlineQueryResultsImpl(
    botUserId: Long,
    query: String,
    offset: String?,
): InlineBotQueryPage? {
    if (botUserId <= 0L || query.isBlank()) return null
    return runCatching {
        awaitAuthorized()
        // 查询会话取收藏夹（本人私有会话）：对 inline bot 最宽容的环境，
        // 也正好是结果稍后要发回去的地方，一次解析两用
        val chatId = getSavedMessagesChatIdImpl() ?: return@runCatching null
        val resp = withTimeout(INLINE_QUERY_TIMEOUT_MS) {
            client.send(
                TdApi.GetInlineQueryResults(
                    botUserId, chatId, null, query.trim(), offset ?: "",
                ),
            )
        }
        // 验证墙透传：results 空 + button（StartBot 类型）= bot 要用户先互动，
        // 不绕过、不代点——把按钮文案原样带回上层报告给用户
        val startBotText = resp.button
            ?.takeIf { it.type is TdApi.InlineQueryResultsButtonTypeStartBot }
            ?.text
            ?.takeIf { it.isNotBlank() }
        if (startBotText != null) {
            Log.w(TAG, "inline 查询遇验证墙：botUserId=$botUserId q=$query 按钮文案=「$startBotText」（如实透传不绕过）")
        }
        InlineBotQueryPage(
            queryId = resp.inlineQueryId,
            results = resp.results.filterNotNull(),
            nextOffset = resp.nextOffset?.takeIf { it.isNotBlank() },
            startBotText = startBotText,
        )
    }.onFailure {
        Log.w(TAG, "getInlineQueryResults 失败 botUserId=$botUserId q=$query offset=$offset: ${it.message}")
    }.getOrNull()
}

/**
 * 把一条 inline 查询结果发进收藏夹并转成可播 [VideoItem]。
 * inline 结果的 File 只有经消息上下文才能稳定下载（本链路的已知坑），
 * SendInlineQueryResultMessage 发出的消息自带真实媒体 content，
 * toVideoItem 现成转换链直接出可播条目（视频/图片/动图三分支全覆盖）。
 *
 * TDLib 错误（含限流）向上抛给调用方（InlineBotFeedSource 逐条兜住并按
 * 错误内容分诊降级）；返回 null = 消息发出但内容不可转（非媒体类消息等），
 * 只丢单条不伤整页。
 *
 * hideViaBot=true：消息不带"经 @bot 转发"角标，收藏夹保持整洁；
 * 来路信息走条目标题（titleOverride）呈现。
 *
 * @param titleOverride 条目标题（如"盲盒流 @vid"）：toVideoItem 的现成通道，
 *        避免条目频道名显示成"收藏夹"让用户不知道内容来路
 */
internal suspend fun TdLibGateway.sendInlineResultToSavedImpl(
    queryId: Long,
    resultId: String,
    titleOverride: String,
): VideoItem? {
    if (queryId <= 0L || resultId.isBlank()) return null
    awaitAuthorized()
    val chatId = getSavedMessagesChatIdImpl() ?: return null
    val msg = withTimeout(INLINE_SEND_TIMEOUT_MS) {
        client.send(
            TdApi.SendInlineQueryResultMessage(
                chatId, null, null, null, queryId, resultId, true,
            ),
        )
    }
    return toVideoItem(chatId, msg, null, titleOverride = titleOverride)
}

/** inline 查询硬超时（毫秒）：bot 秒回是常态，15 秒仍无响应按拒答降级换 bot */
internal const val INLINE_QUERY_TIMEOUT_MS = 15_000L

/** 单条 inline 结果发收藏夹硬超时（毫秒）：发送链路出问题不拖死整页 */
internal const val INLINE_SEND_TIMEOUT_MS = 15_000L
