package dev.tgfeed.core.td
import android.util.Log
import dev.tgfeed.core.plaza.PlazaHistoryRow
import org.drinkless.tdlib.TdApi
// ---------------- TG 广场（去中心化全站动态后端） ----------------
// 以下方法为 core/plaza/PlazaService 的薄 TDLib 封装：只搬数据、不做协议解析，
// 全程 runCatching 静默（广场是锦上添花，任何失败都不得影响主功能）。

/**
 * 按用户名查找广场公开会话（R70 三态版，替代旧 Long? 版——旧版把「查询失败」和
 * 「不存在」都压成 null，是无限建群 bug 的根）：
 *  - Found：SearchPublicChat 成功命中；
 *  - NotFound：TDLib 400 用户名类稳定终态（查无此名）——只有它允许调用方进创建分支；
 *  - Failed：429/熔断静默/网络/其他异常——「查不了」≠「不存在」，调用方绝不许据此创建。
 * 实现复用 resolvePublicChatByUsername：R44 熔断闸 + R58 半开三态 + 429 上报熔断表
 * 全在那一条路径，广场不再另开一套闸逻辑（防两套熔断状态漂移）。
 * R363：整段查找套 [withTdTimeout] 超时兜底——TDLib 隐性限流（SearchPublicChat 挂起
 * 无响应）时 12s 到点按 Failed 诚实回落，协程不再永挂（「查不了」语义，绝不进创建分支）。
 */
internal suspend fun TdLibGateway.plazaFindChatImpl(
    username: String,
    lane: SearchQuotaLedger.Lane = SearchQuotaLedger.Lane.BACKGROUND,
): PlazaFindResult {
    val name = username.trim().removePrefix("@")
    if (name.isEmpty()) return PlazaFindResult.Failed("广场频道名为空") // 配置错误，不是「不存在」
    runCatching { awaitAuthorized() }.onFailure {
        Log.w(TAG, "plazaFindChat 未授权 @$name: ${it.message}")
        return PlazaFindResult.Failed("未授权/授权等待失败: ${it.message}")
    }
    val lookup = withTdTimeout(what = "plazaFindChat/SearchPublicChat @$name") {
        resolvePublicChatByUsername(name, lane)
    } ?: return PlazaFindResult.Failed("网络超时（TDLib 请求无响应，可能限流）")
    return when {
        lookup.ok -> PlazaFindResult.Found(lookup.chatId)
        lookup.permanent -> PlazaFindResult.NotFound // 400 用户名类稳定终态
        lookup.floodWait -> PlazaFindResult.Failed("429 限流/熔断静默，retryAfter=${lookup.floodRetryAfterSec}s")
        else -> PlazaFindResult.Failed("SearchPublicChat 瞬态失败（网络/未知）")
    }
}

/**
 * 验证落盘的广场 chatId 当前仍可访问（R70 闸二的健康检查）：GetChat 成功 = true。
 * 任何失败（群被炸/被踢/未授权/网络）都 false——调用方随即走正常查找流程，
 * 但【不会】据此清除落盘值：瞬态失败不该丢掉复用凭据，真没了由创建分支覆盖新值。
 * GetChat(long chatId) -> Chat（签名已 javap 实证，本文件多处同用法）。
 */
internal suspend fun TdLibGateway.plazaCheckChatImpl(chatId: Long): Boolean = runCatching {
    awaitAuthorized()
    // R363：超时兜底——隐性限流挂起时 12s 到点 null→false（健康检查失败走正常查找流程）
    withTdTimeout(what = "plazaCheckChat/GetChat chatId=$chatId") { client.send(TdApi.GetChat(chatId)) } != null
}.onFailure {
    Log.d(TAG, "plazaCheckChat 失败 chatId=$chatId: ${it.message}")
}.getOrDefault(false)

/**
 * 创建广场公共会话并绑定用户名，返回 chatId；任何失败返回 null。
 *
 * ⚠️ 设计纠偏（务必阅读）：任务定案原文是 isChannel=true 的「频道」，
 * 但 TG 广播频道只有管理员能发帖——普通成员 SendMessage 会撞 400 CHAT_WRITE_FORBIDDEN，
 * 与「全站用户互通（人人可写）」的目标直接冲突。故实际创建公开超级群组
 * （isChannel=false）：对用户感知同样是「一个带 @用户名的公开房间」，
 * 搜索/加入/读历史/写消息链路完全一致。若未来坚持频道形态，需改
 * 「频道 + 关联讨论组」双对象方案（读写都在讨论组），涉及清单外 API，另行评估。
 *
 * 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
 * CreateNewSupergroupChat(String title, boolean isForum, boolean isChannel,
 *   String description, ChatLocation location, int messageAutoDeleteTime, boolean forImport)
 *   -> Chat{type: ChatTypeSupergroup{supergroupId: long, isChannel: boolean}}
 * SetSupergroupUsername(long supergroupId, String username) -> Ok
 */
internal suspend fun TdLibGateway.plazaCreateChatImpl(username: String): Long? = runCatching {
    awaitAuthorized()
    val name = username.trim().removePrefix("@")
    require(name.isNotEmpty()) { "广场频道名为空" }
    // R363：超时兜底——隐性限流挂起时 12s 到点 null→error→null 返回（创建失败诚实回落，
    // ensurePlaza 走冷却/备用路径，不再永挂）
    val chat = withTdTimeout(what = "plazaCreateChat/CreateNewSupergroupChat @$name") {
        client.send(
            TdApi.CreateNewSupergroupChat(
                "TGFeed 广场", false, false,
                "TGFeed 广场：全站用户的「在看 / 点赞 / 弹幕」动态（协议消息，请勿闲聊）",
                null, 0, false,
            )
        )
    } ?: error("TDLib 请求超时（可能限流）")
    val sg = chat.type as? TdApi.ChatTypeSupergroup ?: error("创建结果不是超级群组")
    // 用户名绑定失败（被占用等）只记日志不整体失败：chatId 已可用；
    // 但其他用户将无法按用户名搜到广场——需创建者在 TG 客户端手动改用户名后重开开关
    runCatching {
        withTdTimeout(what = "plazaCreateChat/SetSupergroupUsername @$name") {
            client.send(TdApi.SetSupergroupUsername(sg.supergroupId, name))
        } ?: error("TDLib 请求超时（可能限流）")
    }.onFailure { Log.w(TAG, "广场用户名 @$name 绑定失败（可能被占用）: ${it.message}") }
    Log.i(TAG, "广场已创建: chatId=${chat.id} supergroupId=${sg.supergroupId} @$name")
    chat.id
}.onFailure {
    Log.w(TAG, "plazaCreateChat 失败 @$username: ${it.message}")
}.getOrNull()

/**
 * 加入广场（幂等，永不抛异常）：已是成员/刚创建完会撞 "USER_ALREADY_PARTICIPANT"
 * 类错误，属正常语义；真没加入成功时，后续读历史/发消息会自然失败并静默降级。
 * JoinChat(long chatId) -> ChatJoinResult（签名已 javap 实证）
 */
internal suspend fun TdLibGateway.plazaJoinImpl(chatId: Long) {
    runCatching {
        awaitAuthorized()
        // R363：超时兜底——隐性限流挂起时 12s 到点按失败静默（加入幂等，后续读/发自然降级）
        withTdTimeout(what = "plazaJoin/JoinChat chatId=$chatId") { client.send(TdApi.JoinChat(chatId)) }
            ?: error("TDLib 请求超时（可能限流）")
    }.onFailure {
        Log.d(TAG, "plazaJoin chatId=$chatId: ${it.message}（多半是已是成员，忽略）")
    }
}

/**
 * 会话改名（SetChatTitle）：仅创建者/有权限的管理员能改，无权限/失败静默 false。
 * 签名：SetChatTitle(long chatId, String title) -> Ok
 */
internal suspend fun TdLibGateway.renameChatTitleImpl(chatId: Long, title: String): Boolean = runCatching {
    awaitAuthorized()
    // R363：超时兜底——隐性限流挂起时 12s 到点按 false 诚实回落
    withTdTimeout(what = "renameChatTitle/SetChatTitle chatId=$chatId") { client.send(TdApi.SetChatTitle(chatId, title)) }
        ?: error("TDLib 请求超时（可能限流）")
    Log.i(TAG, "会话改名成功 chatId=$chatId → $title")
    true
}.getOrDefault(false)

/**
 * 往广场发一条纯文本协议消息；成功 true，任何失败（无权限 / flood wait / 网络）false。
 * SendMessage 6 参签名已 javap 实证（见 searchViaBot 注释）：
 * SendMessage(long, MessageTopic, InputMessageReplyTo, MessageSendOptions, ReplyMarkup,
 *   InputMessageContent)；InputMessageText(FormattedText(text, 空 entities 数组), null, false)
 */
internal suspend fun TdLibGateway.plazaSendTextImpl(chatId: Long, text: String): Boolean = runCatching {
    awaitAuthorized()
    // R363：超时兜底——隐性限流挂起时 12s 到点按 false 诚实回落（写入失败静默降级）
    withTdTimeout(what = "plazaSendText/SendMessage chatId=$chatId") {
        client.send(
            TdApi.SendMessage(
                chatId, null, null, null, null,
                TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, false),
            )
        )
    } ?: error("TDLib 请求超时（可能限流）")
    Log.d(TAG, "plazaSendText -> chatId=$chatId: $text")
    true
}.onFailure {
    Log.w(TAG, "plazaSendText 失败 chatId=$chatId: ${it.message}")
}.getOrDefault(false)

/**
 * 拉广场最近 limit 条文本消息：Triple(纯文本, 发布时间 Unix 秒, 发送者 key)。
 * 发送者 key = "u:<userId>" / "c:<chatId>" / "?"（未知），供 PlazaService 按人去重计数
 * （在看/点赞的"人数"语义靠它避免同一用户重复报到被反复计数）。
 * 只取 MessageText；图片/服务消息等其他内容类型天然跳过。
 *
 * 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
 * GetChatHistory(long, long, int, int, boolean) -> Messages（同 fetchFromChannel）
 * Message{senderId: MessageSender, date: int}
 * MessageSenderUser{userId: long} / MessageSenderChat{chatId: long}
 */
internal suspend fun TdLibGateway.plazaFetchRecentTextsImpl(chatId: Long, limit: Int = 100): List<Triple<String, Int, String>> =
    runCatching {
        awaitAuthorized()
        // R363：超时兜底——隐性限流挂起时 12s 到点按空列表诚实回落（本轮无广场动态）
        val history = withTdTimeout(what = "plazaFetchRecentTexts/GetChatHistory chatId=$chatId") {
            client.send(TdApi.GetChatHistory(chatId, 0, 0, limit.coerceIn(1, 100), false))
        } ?: error("TDLib 请求超时（可能限流）")
        history.messages.filterNotNull().mapNotNull { m -> rowOf(m)?.let { Triple(it.text, it.dateSec.toInt(), it.sender) } }
    }.onFailure {
        Log.w(TAG, "plazaFetchRecentTexts 失败 chatId=$chatId: ${it.message}")
    }.getOrDefault(emptyList())

/**
 * 广场历史分页（R205 TL-9 弹幕时间旅行的拉取原语）：拉「fromMessageId 之前」的
 * 一页文本消息行（[PlazaHistoryRow] 携带 messageId——翻页游标与 [D] 行 key 反解
 * 的必需字段）。fromMessageId=0 = 从最新开始；返回新→旧序；不足整页 = 到底。
 * 与 [plazaFetchRecentTextsImpl] 同一消息映射（[rowOf]/[senderKeyOf] 单一定义），
 * 只差保留 messageId 与返回形状——GetChatHistory 是 Get 族不走 Search 配额，
 * 调用方（PlazaService.fetchDanmakuTimeline）有界页数封顶守 flood 红线。
 */
internal suspend fun TdLibGateway.plazaFetchHistoryPageImpl(chatId: Long, fromMessageId: Long, limit: Int = 100): List<PlazaHistoryRow> =
    runCatching {
        awaitAuthorized()
        // R363：超时兜底——拉历史放宽档 20s（大群首拉更重），到点按空页诚实回落
        //（空页=到底语义，翻页循环自然收工，不再永挂）
        val history = withTdTimeout(PLAZA_TD_HISTORY_TIMEOUT_MS, what = "plazaFetchHistoryPage/GetChatHistory chatId=$chatId from=$fromMessageId") {
            client.send(TdApi.GetChatHistory(chatId, fromMessageId, 0, limit.coerceIn(1, 100), false))
        } ?: error("TDLib 请求超时（可能限流）")
        history.messages.filterNotNull().mapNotNull(::rowOf)
    }.onFailure {
        Log.w(TAG, "plazaFetchHistoryPage 失败 chatId=$chatId from=$fromMessageId: ${it.message}")
    }.getOrDefault(emptyList())

/** 广场消息 → 历史行（messageId + 纯文本 + 时刻 + 发送者 key）：非 MessageText 返回 null（图片/服务消息跳过） */
private fun rowOf(m: TdApi.Message): PlazaHistoryRow? {
    val text = (m.content as? TdApi.MessageText)?.text?.text?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    return PlazaHistoryRow(
        messageId = m.id,
        text = text,
        dateSec = m.date.toLong(),
        sender = senderKeyOf(m.senderId),
    )
}

/** MessageSender → 发送者 key（"u:<userId>" / "c:<chatId>" / "?"）：plazaFetchRecentTexts/HistoryPage 同一映射单一定义 */
private fun senderKeyOf(s: TdApi.MessageSender?): String = when (s) {
    is TdApi.MessageSenderUser -> "u:${s.userId}"
    is TdApi.MessageSenderChat -> "c:${s.chatId}"
    else -> "?" // JNI 平台类型防御 null/未来新类型：未知发送者全部归为同一人
}
