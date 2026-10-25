package dev.tgfeed.core.plaza

// ---------------- TL-9 弹幕时间旅行（R205）：广场弹幕时间轴模型 + 解析纯函数 ----------------
//
// v1 边界（任务书口径 + R200 广场薄警示）：**纯回放视图**——拉广场历史消息流
// （[PlazaService.fetchDanmakuTimeline] 有界分页）→ 解析 [D] 弹幕行 → 按真实
// 发布时间渲染时间轴。不做任何社交依赖（无发送者维度、无互动计数、无热力推断）；
// 广场数据量不足时诚实空态（「广场弹幕还太少」），不造数据。
//
// 信任层与 fetchPlaza 同口径同常量（[PlazaService.SPAM_PROTOCOL_LIMIT] 单一定义）：
// 单发送者本窗口内协议消息数超限 → 该发送者整窗忽略；[D] 行文法解析复用
// [PlazaService.parseDanmakuLine]（「key + 内容」拆分只有一处实现，绝不镜像）。

/**
 * 广场历史行（分页拉取的原始行）：messageId 必须携带——它是 GetChatHistory
 * 翻页游标（下一页 fromMessageId = 本页最老一行的 id），也是弹幕事件回溯
 * 「哪条视频」的唯一线索（[D] 行 key 反解）。
 */
data class PlazaHistoryRow(
    val messageId: Long,
    val text: String,
    val dateSec: Long,
    /** 发送者 key（"u:<userId>" / "c:<chatId>" / "?"，网关侧同一映射产出） */
    val sender: String,
)

/**
 * 一条弹幕事件：发布时刻 + 所评视频 + 文本。
 * key = VideoItem.key（"tg:<chatId>:<messageId>"）——UI 层可按 key 过滤「只看本视频」。
 */
data class PlazaDanmakuEvent(
    val key: String,
    val chatId: Long,
    val messageId: Long,
    val text: String,
    /** 发布时刻（Unix 秒）——时间轴的真实时间维（回放的核心字段） */
    val timestampSec: Long,
)

/**
 * 弹幕时间轴（回放视图的数据契约）：
 *  - [events] 按发布时间**升序**（回放=从最早开始看，与「时间旅行」叙事一致）；
 *  - [scannedRows] / [pagesFetched]：本次回放扫过的广场行数与页数（诚实口径：
 *    「已回看近 N 条广场消息」——回放深度有限，不冒充全量历史）。
 */
data class PlazaDanmakuTimeline(
    val events: List<PlazaDanmakuEvent>,
    val scannedRows: Int,
    val pagesFetched: Int,
) {
    companion object {
        /** 空时间轴（广场未开启/未就绪/拉取失败——与「拉到了但没有弹幕」语义区分靠 events 空 + scannedRows=0） */
        val EMPTY = PlazaDanmakuTimeline(emptyList(), 0, 0)
    }
}

/** "[D] tg:<chatId>:<messageId> 内容" → (key, chatId, messageId, 内容) 四元组；不合规返回 null（文法单一定义：PlazaService.parseDanmakuLine） */
internal fun parseDanmakuEventLine(line: String): PlazaDanmakuEventLine? {
    val (key, content) = PlazaService.parseDanmakuLine(line) ?: return null
    val parts = key.removePrefix("tg:").split(":")
    if (parts.size != 2) return null
    val chatId = parts[0].toLongOrNull() ?: return null
    val messageId = parts[1].toLongOrNull() ?: return null
    return PlazaDanmakuEventLine(key, chatId, messageId, content)
}

/** [parseDanmakuEventLine] 的中间载体（timestampSec 由调用方按所在行的消息时刻补齐） */
internal data class PlazaDanmakuEventLine(
    val key: String,
    val chatId: Long,
    val messageId: Long,
    val content: String,
)

/**
 * 广场历史行 → 弹幕时间轴（纯函数）：
 *  1. 信任层：先数每个发送者的协议消息数（[W] 采样行不计——与 fetchPlaza
 *     R130 修正同口径），超 [spamProtocolLimit] 的发送者整窗忽略；
 *  2. 逐行解析 [D] 弹幕行（一条消息多行时逐行处理——批量协议消息同款语义），
 *     事件时刻 = 所在消息的发布时刻（弹幕行的唯一真实时间源）；
 *  3. 按时间升序输出（同刻保持稳定序）。
 */
fun parseDanmakuTimeline(
    rows: List<PlazaHistoryRow>,
    spamProtocolLimit: Int = PlazaService.SPAM_PROTOCOL_LIMIT,
): PlazaDanmakuTimeline {
    // —— 信任层第一遍：刷屏检测（与 PlazaService.fetchPlaza 完全同口径）——
    val protoMsgCount = HashMap<String, Int>()
    for (row in rows) {
        if (row.text.lineSequence().any { PlazaService.isProtocolLine(it) && !it.startsWith("[W] ") }) {
            protoMsgCount[row.sender] = (protoMsgCount[row.sender] ?: 0) + 1
        }
    }
    val spamSenders = protoMsgCount.filterValues { it > spamProtocolLimit }.keys
    // —— 第二遍：解析 [D] 行 → 事件 ——
    val events = ArrayList<PlazaDanmakuEvent>(rows.size)
    for (row in rows) {
        if (row.sender in spamSenders) continue
        for (line in row.text.lines()) {
            if (!line.startsWith("[D] ")) continue
            val parsed = parseDanmakuEventLine(line) ?: continue
            events += PlazaDanmakuEvent(
                key = parsed.key,
                chatId = parsed.chatId,
                messageId = parsed.messageId,
                text = parsed.content,
                timestampSec = row.dateSec,
            )
        }
    }
    val sorted = events.sortedBy { it.timestampSec }
    // pagesFetched 由拉取方（fetchDanmakuTimeline）补——解析层只知道行数不知道页数
    return PlazaDanmakuTimeline(sorted, scannedRows = rows.size, pagesFetched = 0)
}
