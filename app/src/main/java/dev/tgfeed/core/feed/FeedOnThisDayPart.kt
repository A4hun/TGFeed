package dev.tgfeed.core.feed

import dev.tgfeed.core.onthisday.DeepSearchOnThisDaySource
import dev.tgfeed.core.onthisday.EpochRange
import dev.tgfeed.core.onthisday.OnThisDayOutcome
import dev.tgfeed.core.onthisday.OnThisDayYearStat
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.time.ZoneId

// ---------------- R203 那年今日（TL-2 页面查询口；策展纯函数见 core/onthisday/OnThisDayPolicy） ----------------

/**
 * 那年今日的 VM 侧查询实现：deepIndex 归 FeedViewModel 私有（单源纪律），页面经
 * [FeedViewModel.onThisDayQuery] / [FeedViewModel.onThisDayYearStats] 消费——不把
 * deepIndex 句柄漏给 UI 层。数据通道全在 [DeepSearchOnThisDaySource]（R202
 * timeWindowItems 共享原语，接缝单一）。
 */
internal suspend fun FeedViewModel.onThisDayQueryImpl(
    window: EpochRange,
    limit: Int,
): OnThisDayOutcome<List<VideoItem>> =
    DeepSearchOnThisDaySource(app, indexProvider = { deepIndex }).query(window, limit)

/** 年轮统计（页面 pills 徽标；同推送的「有内容的周年日」判定原料） */
internal suspend fun FeedViewModel.onThisDayYearStatsImpl(
    today: LocalDate,
    zone: ZoneId,
): OnThisDayOutcome<List<OnThisDayYearStat>> =
    DeepSearchOnThisDaySource(app, indexProvider = { deepIndex }).yearStats(today, zone)

/** R202 时间索引就绪流（页面「构建中→就绪自动补刷」；本 VM 实例的流） */
internal val FeedViewModel.onThisDayIndexReadyImpl: StateFlow<Boolean>
    get() = deepIndex.dateIndexReady
