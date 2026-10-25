package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem

/**
 * R202 时间窗查询的 VM 薄封装（TimeMachineScreen 页内自治消费；R203/R204 及
 * 后续时间族同样可复用——接口公告见 r202_notes.md）。
 *
 * 三个只读口全部**请在 IO 协程调用**（金矿只读连接同款纪律）；库未建/索引未
 * 就绪/查询异常一律空手返回（DeepSearchIndex 内部已兜，这里再包一层 runCatching
 * 防御调用面异常逃逸）。本文件刻意**零改动 FeedViewModel.kt**（扩展函数=独立
 * 文件承载，R203/R204 并发在岗期最小重叠面）。
 */
internal fun FeedViewModel.loadTimeWindowItems(query: DeepSearchIndex.TimeWindowQuery): List<VideoItem> =
    runCatching { deepIndex.timeWindowItems(query) }.getOrDefault(emptyList())

/** 索引时间量程（滑杆 min/max）；未就绪 null（调用方回落默认量程） */
internal fun FeedViewModel.loadTimeBounds(): Pair<Long, Long>? =
    runCatching { deepIndex.dateBounds() }.getOrNull()

/** 日粒度密度直方图（滑杆刻度糖）；未就绪空表（滑杆回落均匀刻度） */
internal fun FeedViewModel.loadTimeHistogram(): List<Pair<Int, Int>> =
    runCatching { deepIndex.monthHistogram() }.getOrDefault(emptyList())

/** 时间索引就绪流（页内观察建完自动补刷——R183 金矿同款） */
internal val FeedViewModel.timeIndexReadyFlow: kotlinx.coroutines.flow.StateFlow<Boolean>
    get() = deepIndex.dateIndexReady
