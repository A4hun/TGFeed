package dev.tgfeed.core.feed

// ---------------- R204 TL-7 回放热力（InteractionStats 聚合出口，数据侧） ----------------

/**
 * TL-7 数据出口（纯内存聚合，[InteractionStats] 锁内一遍扫描——微秒级，无需切线程）：
 *  - [seekHeatForKeyImpl]：单视频本机热力（key 级精确层——进度条刻度墙）；
 *  - [channelSeekInsightImpl]：频道级「精彩段落」洞察（chatId 级聚合层）。
 * 消费边界（R122 家族）：只进洞察 UI，不进排序/权重。
 */
internal fun FeedViewModel.seekHeatForKeyImpl(key: String): FloatArray? {
    val landings = interactionStats.seekLandingsByKey(key)
    if (landings.size < SeekHeatPolicy.MIN_SAMPLES) return null
    return SeekHeatPolicy.binsOf(landings)
}

internal fun FeedViewModel.channelSeekInsightImpl(chatId: Long): SeekHeatPolicy.ChannelInsight? =
    SeekHeatPolicy.insightOf(interactionStats.seekLandingsByChannel(chatId))
