package dev.tgfeed.core.feed

import dev.tgfeed.core.store.LocalStore

// ---------------- 智能降档（计量网络/省电模式：起播降一档 + 预载深度 -1） ----------------

internal fun FeedViewModel.smartDegradeActiveImpl(): Boolean =
    _smartQuality.value && (_networkMetered.value || _batterySaver.value)

/**
 * v2 预载深度决策（供 FeedScreen 调 PreloadScheduler.schedule 的 ahead 参数接线用）：
 * 取该频道最近 [PRELOAD_SAMPLE] 条观看事件（watch_events 时间倒序，取前 N 条即最近 N 条）：
 *  - 平均观看比例 < [SKIP_RATIO_THRESHOLD]（15%）视为高跳过率 → 1
 *    （大概率被划走，只预载下一条，别把带宽浪费在不会看的条目上）；
 *  - 否则完播率（观看比例 ≥ [COMPLETE_RATIO] 的事件占比）> [COMPLETE_RATE_THRESHOLD]
 *    （70%）→ 3（用户看得投入，往深处预载，防滑到未就绪条目干等）；
 *  - 其余 → 2（构造默认值）。
 * 事件不足 [PRELOAD_MIN_EVENTS] 条时不做判断，返回默认 2（小样本不可信）。
 * 注意：同步读 LocalStore（命中内存缓存；首次未缓存时有一次小文件 IO），
 * 请在翻页回调等非首帧关键路径调用。
 * 出口处叠加智能降档：[smartDegradeActive]（计量/省电）时深度 -1（最低 1）。
 */
internal fun FeedViewModel.preloadAheadForImpl(chatId: Long): Int {
    val events = runCatching { store.loadWatchEvents() }.getOrDefault(emptyList())
        .asSequence()
        .filter { it.chatId == chatId }
        .take(PRELOAD_SAMPLE)
        .toList()
    val depth = if (events.size < PRELOAD_MIN_EVENTS) {
        PRELOAD_AHEAD_DEFAULT
    } else {
        val ratios = events.map { e ->
            if (e.durationSec <= 0) 0.0
            else (e.watchSec.toDouble() / e.durationSec).coerceIn(0.0, 1.0)
        }
        when {
            ratios.average() < SKIP_RATIO_THRESHOLD -> PRELOAD_AHEAD_MIN
            ratios.count { it >= COMPLETE_RATIO }.toDouble() / events.size > COMPLETE_RATE_THRESHOLD ->
                PRELOAD_AHEAD_MAX
            else -> PRELOAD_AHEAD_DEFAULT
        }
    }
    // 智能降档：计量网络/省电模式下预载深度 -1（最低 1），少占带宽与电量；
    // PreloadScheduler.schedule 侧另有 coerceIn(1, MAX_AHEAD) 兜底，双保险
    return if (smartDegradeActive()) (depth - 1).coerceAtLeast(PRELOAD_AHEAD_MIN) else depth
}
