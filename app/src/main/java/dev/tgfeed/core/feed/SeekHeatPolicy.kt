package dev.tgfeed.core.feed

/**
 * R204 TL-7 进度条回放热力（timeline-opportunities TL-7，种子=P1.5 seek 信号）：
 * 拖拽落点 → 热力分布的**纯分箱/文案层**。两层消费（B 站高能进度条的本机版）：
 *  - **单视频精确层**（本机 key 级）：SEEK 事件 sub 字段带条目 key，60 桶归一化密度
 *    → 进度条上方细刻度墙（ProgressLine heat 参数）；
 *  - **频道级聚合层**（chatId 级）：本机对某频道全部拖拽落点的分布洞察 →
 *    「精彩段落」视图（峰段/拖开头率/奔后段率）。
 *
 * 红线：
 *  - R195 seek 节流：热力**纯展示**，不新增任何点按/跳转交互（交互面零扩张）；
 *  - 消费边界（R122）：只进洞察 UI 不进权重（InteractionStats 全家族口径）；
 *  - 隐私（B3）：文案只做分布陈述（「峰段 62%~78%」），不做个体时间线推断。
 */
object SeekHeatPolicy {

    /** 单视频热力分桶数：60 桶（2 秒级分辨率按分钟级视频折算，进度条刻度可辨） */
    const val BINS = 60

    /** 频道级洞察分桶数：20 桶（3 档/5% 一格——60 桶对小样本过细，散点各占一桶
     *  看不出「段」；20 桶下真聚集才成峰，见 insightOf 的峰段判定） */
    const val INSIGHT_BINS = 20

    /** 出热力的最小样本数：低于它不出墙（宁缺勿假——小样本密度是噪声不是信号） */
    const val MIN_SAMPLES = 3

    /** 峰段判定：密度 ≥ 峰值 × 此比例的连续桶聚成「峰段」（B 站高能段同思路） */
    const val PEAK_THRESHOLD = 0.7

    /** 「拖开头」边界（InteractionStats SEEK_HEAD_MAX 同口径，值镜像注释） */
    const val HEAD_MAX = 0.2

    /** 「奔后段」边界（InteractionStats SEEK_TAIL_MIN 同口径，值镜像注释） */
    const val TAIL_MIN = 0.7

    /** 单次拖拽落点（比例 0..1；聚合层与统计层共用形状） */
    data class Landing(val ratio: Double)

    /**
     * 落点列表 → 归一化密度桶（长度 [bins]，值 0..1，峰值=1）。
     * 越界比例夹取；空表返回全 0 桶（调用方按样本闸决定是否渲染）。
     */
    fun binsOf(landings: List<Landing>, bins: Int = BINS): FloatArray {
        val out = FloatArray(bins)
        if (landings.isEmpty()) return out
        for (l in landings) {
            val r = l.ratio.coerceIn(0.0, 1.0)
            val idx = (r * (bins - 1)).toInt().coerceIn(0, bins - 1)
            out[idx] += 1f
        }
        var max = 0f
        for (v in out) if (v > max) max = v
        if (max > 0f) for (i in out.indices) out[i] /= max
        return out
    }

    /**
     * 频道级洞察聚合（纯函数）：样本数 + 平均落点 + 拖开头/奔后段占比 + 峰段区间
     * （[INSIGHT_BINS] 桶网格上「连续高密度段」的首尾比例，无合格段时峰段 null——
     * 分布平就承认平）+ [bins] 归一化密度墙（视图直画）。
     */
    data class ChannelInsight(
        val samples: Int,
        /** 平均落点比例（0..1；偏高=内容高潮靠后/偏低=前奏被拖掉） */
        val avgRatio: Double,
        /** 落点 <[HEAD_MAX] 占比（「前奏被拖掉」证据） */
        val headRatio: Double,
        /** 落点 ≥[TAIL_MIN] 占比（「奔高潮」证据） */
        val tailRatio: Double,
        /** 峰段起止比例（0..1 起含、1..1 止含；null=无明显峰） */
        val peakStart: Double?,
        val peakEnd: Double?,
        /** 归一化密度墙（[INSIGHT_BINS] 长，0..1；频道路径视图直画） */
        val bins: FloatArray = FloatArray(INSIGHT_BINS),
    )

    /** 频道级洞察聚合：样本 <[MIN_SAMPLES] 返回 null（小样本不出伪结论） */
    fun insightOf(landings: List<Landing>): ChannelInsight? {
        if (landings.size < MIN_SAMPLES) return null
        var sum = 0.0
        var head = 0
        var tail = 0
        for (l in landings) {
            val r = l.ratio.coerceIn(0.0, 1.0)
            sum += r
            if (r < HEAD_MAX) head++
            if (r >= TAIL_MIN) tail++
        }
        val n = landings.size
        val bins = binsOf(landings, INSIGHT_BINS)
        // 峰段：密度 ≥ 峰值×阈值的最长连续桶段（首尾桶换算回比例）。
        // 三道防线（R205 实测中间态反馈收口）：
        //  - 重复度地板：最高桶计数 <2 不算（每个桶只中过一次=散点，不是「段」）；
        //  - 单桶段不算（孤立桶=噪声）；
        //  - ≥ 半轴的连续段不算（均匀铺满=分布平，不是峰——平态回落平均落点文案）。
        val maxCount = landings
            .groupingBy { (it.ratio.coerceIn(0.0, 1.0) * (INSIGHT_BINS - 1)).toInt().coerceIn(0, INSIGHT_BINS - 1) }
            .eachCount().values.maxOrNull() ?: 0
        var peakStart: Double? = null
        var peakEnd: Double? = null
        if (maxCount >= 2) {
            var runStart = -1
            var runLen = 0
            var bestStart = -1
            var bestLen = 0
            for (i in bins.indices) {
                if (bins[i] >= PEAK_THRESHOLD) {
                    if (runStart < 0) runStart = i
                    runLen++
                    if (runLen > bestLen) {
                        bestLen = runLen
                        bestStart = runStart
                    }
                } else {
                    runStart = -1
                    runLen = 0
                }
            }
            if (bestLen in 2..(INSIGHT_BINS / 2)) {
                peakStart = bestStart / (INSIGHT_BINS - 1).toDouble()
                peakEnd = (bestStart + bestLen - 1) / (INSIGHT_BINS - 1).toDouble()
            }
        }
        return ChannelInsight(
            samples = n,
            avgRatio = sum / n,
            headRatio = head.toDouble() / n,
            tailRatio = tail.toDouble() / n,
            peakStart = peakStart,
            peakEnd = peakEnd,
            bins = bins,
        )
    }

    /** 比例 → 百分比整数（62%~78% 文案用；四舍五入） */
    private fun pct(r: Double): Int = (r * 100).toInt().coerceIn(0, 100)

    /**
     * 洞察主文案（频道级「精彩段落」视图标题行）：
     * 「你常跳到 62%~78%——这段多半是精彩段落」/「你常把开头的 12% 拖掉」/分布平态。
     */
    fun insightHeadline(i: ChannelInsight): String {
        val start = i.peakStart
        val end = i.peakEnd
        if (start != null && end != null && end > start) {
            return "你常跳到 ${pct(start)}%~${pct(end)}% · 这段多半是精彩段落"
        }
        if (i.headRatio >= 0.34) return "这个频道的开头你常拖掉（${pct(i.headRatio)}% 落在头 20%）"
        if (i.tailRatio >= 0.34) return "这个频道你总奔后段（${pct(i.tailRatio)}% 落在后 30%）"
        return "你的落点分布很平均 · 平均落在 ${pct(i.avgRatio)}%"
    }

    /** 洞察副文案（样本口径，诚实标注本机）：「近 7 天 · 12 次拖拽 · 平均落点 61%」 */
    fun insightSubline(i: ChannelInsight, windowDays: Int): String =
        "近 $windowDays 天 · ${i.samples} 次拖拽 · 平均落点 ${pct(i.avgRatio)}%"
}
