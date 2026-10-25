package dev.tgfeed.core.feed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------- R215 ① 发现页时段切换条（timeWindowItems 窗口聚合查询口） ----------------

/**
 * 时段切换条的 VM 侧查询口（扩展函数独立文件——**零改动 FeedViewModel.kt**，
 * 并发期最小重叠面，FeedTimeWindowPart/FeedOnThisDayPart 同款范式）。
 *
 * 聚合口径（任务书「timeWindowItems 查近 N 天→按频道聚合→与金矿候选交并→
 * 时间权重主导」）：
 *  - 窗口 = [now - band.windowSec, now]（R202 闭区间原语直查，**零新日期口径**）；
 *  - channelIds = 当前候选频道集（「与金矿候选交并」：索引内频道过滤，候选集外的
 *    行零回表直接跳过；>999 分块在原语内部）；
 *  - limit = [TimeWindowPolicy.QUERY_LIMIT_MAX]（R202 有界红线硬顶，查询侧钳制）；
 *  - 返回 chatId → 窗内采样条数（重排权重，喂 [rerankCandidatesByTimeWindow]）。
 *
 * 三纪律继承（R183/R202）：IO 线程；库未建/索引未就绪/异常 → runCatching 空表
 * （调用方零重排诚实降级，绝不退化全表扫）。请从 UI 协程调用（suspend）。
 */
internal suspend fun FeedViewModel.discoverTimeBandCounts(
    band: DiscoverTimeBand,
    candidateIds: Set<Long>,
): Map<Long, Int> {
    val windowSec = band.windowSec ?: return emptyMap() // 全年默认档：不查询
    if (candidateIds.isEmpty()) return emptyMap() // 候选真空：无重排对象
    val nowSec = System.currentTimeMillis() / 1000L
    val fromSec = nowSec - windowSec
    val rows = withContext(Dispatchers.IO) {
        runCatching {
            deepIndex.timeWindowItems(
                DeepSearchIndex.TimeWindowQuery(
                    fromSec = fromSec,
                    toSec = nowSec,
                    channelIds = candidateIds,
                    limit = TimeWindowPolicy.QUERY_LIMIT_MAX,
                ),
            )
        }.getOrDefault(emptyList())
    }
    // 按频道聚合（时间权重 = 窗内采样条数；窗口查询按 dateEpochSec 倒序采样，
    // 600 硬顶下自然带「越活跃越可能被采到」的偏置——对「活跃度优先」语义安全）
    val out = HashMap<Long, Int>(rows.size * 2)
    for (r in rows) {
        out[r.chatId] = (out[r.chatId] ?: 0) + 1
    }
    return out
}
