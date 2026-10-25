package dev.tgfeed.core.td

import android.util.Log
import org.drinkless.tdlib.TdApi

/**
 * 拉取频道消息的评论（讨论线程）纯文本，供弹幕层滚动展示。
 * 频道评论 = 频道消息的讨论线程：GetMessageThread 拿线程信息（replyInfo.replyCount
 * 快速判空），GetMessageThreadHistory 拉线程内消息（fromMessageId=0/offset=0 从最新
 * 开始，倒序返回）。只保留 MessageText 的 text.text，去空白、去重、超长截断。
 *
 * 该消息无评论 / 频道关评论 / 任何网络错误 → 返回空表，绝不向上抛（弹幕不该影响播放）。
 * R169（P0-2 / F1）：4 处 send 过 [sendReq] 统一 10s 超时——弹幕路径每条在播
 * 视频都拉，native 冻死时旧口径协程永挂（onFailure 日志都打不出，F12 连坐）；
 * 超时按失败走本文件既有 runCatching 回退（空表不抛），弹幕层无感。
 *
 * 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
 * GetMessageThread(long chatId, long messageId) -> MessageThreadInfo
 * MessageThreadInfo{chatId: long, messageThreadId: long, replyInfo: MessageReplyInfo, ...}
 * MessageReplyInfo{replyCount: int, recentReplierIds, lastReadInboxMessageId, lastMessageId, ...}
 * GetMessageThreadHistory(long chatId, long messageId, long fromMessageId, int offset, int limit)
 *   -> Messages{totalCount: int, messages: Message[]}
 * MessageText{text: FormattedText}；FormattedText{text: String, entities}
 *   —— 注意鉴别：1.8.67 的 Message 已没有 canGetMessageThread 字段（javap 实证），
 *      线程是否存在只能靠 GetMessageThread 的成功/失败判断，故整体 runCatching 兜底
 */
internal suspend fun TdLibGateway.getCommentsImpl(item: VideoItem, limit: Int = 50): List<String> = runCatching {
    commentsCache[item.key]?.let { return@runCatching it }
    awaitAuthorized()
    // 1) 线程信息：频道关评论/消息无讨论线程时 TDLib 直接报错，走 onFailure 返回空表
    val thread = sendReq(TdApi.GetMessageThread(item.chatId, item.messageId))
    val replyCount = thread.replyInfo?.replyCount ?: 0
    if (replyCount <= 0) {
        return@runCatching emptyList<String>().also { commentsCache[item.key] = it }
    }
    // 2) 线程历史：从最新开始倒序取评论，弹幕不在乎顺序（UI 侧会洗牌）
    val history = sendReq(
        TdApi.GetMessageThreadHistory(item.chatId, item.messageId, 0L, 0, limit.coerceIn(1, 100))
    )
    val texts = history.messages.filterNotNull()
        .mapNotNull { (it.content as? TdApi.MessageText)?.text?.text?.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .map { if (it.length > MAX_COMMENT_CHARS) it.take(MAX_COMMENT_CHARS) + "…" else it }
        .take(limit)
    commentsCache[item.key] = texts
    Log.d(TAG, "getComments: chatId=${item.chatId} msg=${item.messageId} replyCount=$replyCount texts=${texts.size}")
    texts
}.onFailure {
    Log.w(TAG, "getComments 失败（可能无评论/频道关评论）chatId=${item.chatId} msg=${item.messageId}: ${it.message}")
}.getOrDefault(emptyList())

/**
 * 弹幕评论的"问出处上下文"增强版（与 [getComments] 并存的新增方法，后者保持原样）。
 *
 * 场景：群组讨论区里有人发视频问出处、别人回复"这是某某资源"——这种问答案对看视频
 * 的人很有价值，但弹幕只滚回复正文时会丢掉"答的是哪一问"的上下文。本方法把命中的
 * 回复评论扩成 "回复「被回复摘要」<回复正文>" 的标记格式输出（DanmakuOverlay 识别
 * 该前缀做高亮渲染；不认识前缀的消费方按普通文本显示也无碍）。
 *
 * DANMAKU-TG 任务 2 升级：返回 [DanmakuItem]（带真实发布时间戳 + 回复标记），
 * 供弹幕层做"评论真实时间间隔 → 视频进度"映射（算法见 docs/DANMAKU-TG.md §3）：
 *  - timestampSec = Message.date（评论发布的 Unix 秒，javap 实证 int 字段）；
 *  - isReply = replyTo 指向同讨论组内消息（replyTo.chatId == 本条消息 chatId）：
 *    顶级评论的 replyTo 为 null（线程归属由 GetMessageThreadHistory 的线程上下文表达），
 *    若个别客户端把顶级评论的 replyTo 指向频道主帖，其 chatId 是频道 id ≠ 讨论组 id，
 *    该判定依然正确排除。
 *
 * 被回复摘要来源（零额外请求优先，全程不为摘要补发任何请求）：
 *  1. MessageReplyToMessage.quote（TextQuote{text: FormattedText}，javap 实证）：
 *     回复者手动引用的原文片段——最精准的上下文；
 *  2. MessageReplyToMessage.content（javap 实证 1.8.67 该字段存在，可空）：
 *     被回复消息内容直接嵌在 replyTo 里，文字消息取正文、媒体消息给占位标签；
 *  3. 同页线程历史的 id→消息 映射：被回复消息就在本页时从页内取摘要；
 *  4. 都拿不到：不加前缀（退化为纯文本，与 getComments 输出一致）。
 *
 * "命中资源"闸门（只对资源向问答加前缀，普通闲聊回复保持原样防弹幕冗长）：
 *  回复正文含资源链接（t.me / magnet: / ed2k:// / http(s)）或高精确度资源关键词
 * （出处/资源/番号/车牌/磁力/神秘代码），或被回复摘要里含链接。
 *
 * 缓存/失败语义与 getComments 完全一致：无评论/关评论/任何网络错误 → 空表不抛。
 *
 * 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
 * Message{date: int, replyTo: MessageReplyTo, chatId: long}；
 * MessageReplyToMessage{chatId: long, messageId: long, quote: TextQuote,
 *   origin: MessageOrigin, originSendDate: int, content: MessageContent}
 *   （quote/content 可空 = 无引用/被回复消息不可用）
 * TextQuote{text: FormattedText, position: int, isManual: boolean}
 * GetMessageThread / GetMessageThreadHistory 签名同 [getComments] 注释
 *   —— 注意鉴别：1.8.67 的 Supergroup 没有 linkedChatId 字段（javap 实证只有
 *      hasLinkedChat: boolean），讨论组 id 无需也拿不到，线程 API 内部完成解析
 */
internal suspend fun TdLibGateway.getCommentsRichImpl(item: VideoItem, limit: Int = 50): List<DanmakuItem> = runCatching {
    commentsRichCache[item.key]?.let { return@runCatching it }
    awaitAuthorized()
    // 1) 线程信息：频道关评论/消息无讨论线程时 TDLib 直接报错，走 onFailure 返回空表
    val thread = sendReq(TdApi.GetMessageThread(item.chatId, item.messageId))
    val replyCount = thread.replyInfo?.replyCount ?: 0
    if (replyCount <= 0) {
        return@runCatching emptyList<DanmakuItem>().also { commentsRichCache[item.key] = it }
    }
    // 2) 线程历史：从最新开始倒序取评论（弹幕层按 timestampSec 自行升序重排，这里无需保序）
    val history = sendReq(
        TdApi.GetMessageThreadHistory(item.chatId, item.messageId, 0L, 0, limit.coerceIn(1, 100))
    )
    val msgs = history.messages.filterNotNull()
    // 同页 id→消息 映射：被回复的消息常与回复同页出现，零请求补摘要
    val byId = msgs.associateBy { it.id }
    val items = msgs.mapNotNull { m ->
        val text = (m.content as? TdApi.MessageText)?.text?.text?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val replyTo = m.replyTo as? TdApi.MessageReplyToMessage
        // 回复判定：replyTo 指向同讨论组内消息 = "评论的回复"（见方法头注释的排除论证）
        val isReply = replyTo != null && replyTo.chatId == m.chatId
        val outText = if (replyTo == null) {
            truncateComment(text) // 非回复：原样纯文本
        } else {
            // 被回复摘要：引用片段 > replyTo.content > 同页消息；都拿不到不加前缀
            val snippet = replySnippet(replyTo, byId)
            if (snippet == null || !isResourceAnswer(text, snippet)) {
                truncateComment(text) // 非资源向问答：保持原样
            } else {
                // 命中"问出处"问答：加"回复「摘要」"上下文前缀（DanmakuOverlay 识别高亮）
                "回复「$snippet」" + truncateComment(text)
            }
        }
        DanmakuItem(
            text = outText,
            timestampSec = m.date.toLong(),
            isReply = isReply,
        )
    }
        .distinctBy { it.text } // 同文本只留一条（列表新→旧，保留最新一条的时间戳）
        .take(limit)
    commentsRichCache[item.key] = items
    Log.d(TAG, "getCommentsRich: chatId=${item.chatId} msg=${item.messageId} replyCount=$replyCount items=${items.size}")
    items
}.onFailure {
    Log.w(TAG, "getCommentsRich 失败（可能无评论/频道关评论）chatId=${item.chatId} msg=${item.messageId}: ${it.message}")
}.getOrDefault(emptyList())

/** 弹幕单条正文截断（与 getComments 的 MAX_COMMENT_CHARS 同口径） */
internal fun TdLibGateway.truncateComment(s: String): String =
    if (s.length > MAX_COMMENT_CHARS) s.take(MAX_COMMENT_CHARS) + "…" else s

/**
 * 取"被回复消息"的短摘要（≤[REPLY_SNIPPET_MAX] 字）：
 * 引用片段 > replyTo.content 内嵌 > 同页消息；都拿不到返回 null（调用方不加前缀）。
 */
internal fun TdLibGateway.replySnippet(
    replyTo: TdApi.MessageReplyToMessage,
    byId: Map<Long, TdApi.Message>,
): String? {
    // 1) 回复者手动引用的原文片段（TextQuote，最精准的上下文；JNI 平台类型逐层防御 null）
    replyTo.quote?.text?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.toSnippet() }
    // 2) replyTo.content（被回复消息内容内嵌，可空 = 消息不可用/已删除）
    contentSnippet(replyTo.content)?.let { return it }
    // 3) 同页线程历史里找被回复消息
    return byId[replyTo.messageId]?.let { contentSnippet(it.content) }
}

/** 消息内容 → 短摘要：文字取正文，媒体给占位标签；不支持的内容返回 null */
internal fun TdLibGateway.contentSnippet(c: TdApi.MessageContent?): String? = when (c) {
    is TdApi.MessageText -> c.text.text.trim().takeIf { it.isNotEmpty() }?.toSnippet()
    is TdApi.MessageVideo -> "[视频]"
    is TdApi.MessagePhoto -> "[图片]"
    is TdApi.MessageAnimation -> "[动图]"
    is TdApi.MessageAudio -> "[音频]"
    is TdApi.MessageVoiceNote -> "[语音]"
    else -> null
}

/** 摘要截断：≤[REPLY_SNIPPET_MAX] 字，超长截断加省略号（摘要只是定位"答的是哪一问"，宁短勿长） */
internal fun String.toSnippet(): String =
    if (length > REPLY_SNIPPET_MAX) take(REPLY_SNIPPET_MAX) + "…" else this

/**
 * "命中资源"判定：回复正文含资源链接/资源关键词，或被回复摘要含链接。
 * 关键词只收高精确度的（见 RESOURCE_KEYWORDS），宁缺勿滥——误加前缀只是弹幕长一点，
 * 漏加则丢失问答上下文。
 */
internal fun TdLibGateway.isResourceAnswer(replyText: String, snippet: String): Boolean {
    if (RESOURCE_LINK_REGEX.containsMatchIn(replyText)) return true
    if (RESOURCE_LINK_REGEX.containsMatchIn(snippet)) return true
    return RESOURCE_KEYWORDS.any { it in replyText }
}

// ---------------- 弹幕"问出处"上下文常量（getCommentsRich 配套，文件级私有） ----------------

/** 回复上下文摘要的最大字符数：摘要只是定位"答的是哪一问"，宁短勿长 */
private const val REPLY_SNIPPET_MAX = 12

/** 资源链接判定（t.me / magnet / ed2k / http(s)）：命中即认为该回复在"给资源" */
private val RESOURCE_LINK_REGEX = Regex("""t\.me/|magnet:\?|ed2k://|https?://""", RegexOption.IGNORE_CASE)

/** 资源关键词（高精确度小词表：命中即给回复加"问出处"上下文前缀；宁缺勿滥防弹幕冗长） */
private val RESOURCE_KEYWORDS = listOf("出处", "资源", "番号", "车牌", "磁力", "神秘代码")
