package dev.tgfeed.core.feed

/**
 * R163 智能索引分级 · 死媒体降权策略（B5，设计文档 §9.2）。
 *
 * R158 实锤（r158_notes.md §⑦）：推荐流被爬虫 bulk 波灌入**死媒体**（TDLib 已删/
 * 过期视频，「视频格式不支持或已损坏」错误卡连续 8+ 条跨两频道）。处置三层：
 *  1. **记账**（本策略的数据源）：播放失败终态 → [InteractionStats] 负反馈流水
 *     （sub=[DEAD_MEDIA_SUB]，写点 VideoPlayerListener fatal 分支——零 Kind 迁移、
 *     落盘格式零变化）；per-chatId 聚合见 [InteractionStats.deadChannelStats]；
 *  2. **降权**（本文件）：频道死链率（近 7 天 deadmedia/曝光）超阈值 → 权重乘
 *     [Config.penaltyFactor] 折扣，挂在 [FeedRankingPart] 的两条权重出口
 *     （p2ChannelWeights 统一出口乘性 + sessionAdjustedBoost 源内加性折算）；
 *  3. **死链自动跳**（错误卡 ≥2 张自动换一条）：涉及 UI 组件改造，**挂账**待放行
 *     （GridVideoCell「满 3 次自动换片」是同族先例）。
 *
 * 纯函数、零 Android 依赖；配置与阈值收敛在此一处（AGENT-DISCIPLINE 单一数字源）。
 *
 * 纪律①（行为变化默认保守，参照 R128 点赞回写先例）：[Config.demoteEnabled] 默认
 * **false**——记账照常积累数据（零用户可见影响），但**降权不生效**，排序口径与
 * 今天完全一致；开关由 B6 接 AppPrefs（默认关），翻默认值等用户终审。
 */
object DeadMediaPolicy {

    /**
     * 死链降权配置。[demoteEnabled] 默认 false（纪律①：记账开、降权关）。
     */
    data class Config(
        /** 降权总开关（默认关=排序口径与现状完全一致；记账不受此开关影响） */
        val demoteEnabled: Boolean = false,
        /** 降权死链率阈值（0..1，严格大于才降——30% 播放失败率已判坏频道） */
        val deadRateThreshold: Double = 0.30,
        /** 最小曝光分母（防小样本误杀：曝光不足不计死链率——「没有证据不臆测」） */
        val minImpressions: Int = 5,
        /** 最小死链计数（单次失败可能是偶发，两次起才算模式） */
        val minDeadEvents: Int = 2,
        /** 降权折扣因子（权重 ×0.5——压一半不是封杀：频道复活率回落自动恢复） */
        val penaltyFactor: Double = 0.5,
    )

    /**
     * 单频道是否该降权：开关开 ∧ 样本充分（曝光 ≥[Config.minImpressions] 且死链
     * ≥[Config.minDeadEvents]）∧ 死链率严格 > [Config.deadRateThreshold]。
     */
    fun isDemoted(stat: InteractionStats.DeadChannelStat, config: Config): Boolean =
        config.demoteEnabled &&
            stat.impressions >= config.minImpressions &&
            stat.deadEvents >= config.minDeadEvents &&
            stat.rate > config.deadRateThreshold

    /**
     * 频道降权系数表（VM 直算路径用）：仅**被降权**的频道入表（值=penaltyFactor），
     * 其余频道不占位（消费方按 1.0 缺省——零开销直通语义）。
     */
    fun demotionFactors(
        stats: Map<Long, InteractionStats.DeadChannelStat>,
        config: Config,
    ): Map<Long, Double> {
        if (!config.demoteEnabled || stats.isEmpty()) return emptyMap()
        val out = HashMap<Long, Double>()
        for ((id, st) in stats) if (isDemoted(st, config)) out[id] = config.penaltyFactor
        return out
    }

    /**
     * 乘性降权（VM 直算路径，p2ChannelWeights 统一出口消费）：
     * 命中频道的权重 ×factor；系数表空 = 原表直通（零分配零拷贝）。
     */
    fun applyPenalty(
        weights: Map<Long, Double>,
        factors: Map<Long, Double>,
    ): Map<Long, Double> {
        if (factors.isEmpty() || weights.isEmpty()) return weights
        val out = HashMap<Long, Double>(weights)
        for ((id, f) in factors) {
            val w = out[id] ?: continue // 不在当前权重表的频道无权重可降
            out[id] = w * f
        }
        return out
    }

    /**
     * 加性折算降权（源内注入路径，sessionAdjustedBoost 消费）：源内最终权重 =
     * clamp(基础权重 + boost', 0.3, 2.0)，乘性折扣折成 boost' 增量 =
     * **基础权重 ×（factor − 1）**（与 P1 #4 会话系数同款折算法，FeedRankingPart
     * sessionAdjustedBoost 既有先例）；近似点同款：源内 0.3 下限使 ×0.5 对
     * 低权重频道压不到理论值，可接受。boost' 语义是加性表，此处直接给增量。
     */
    fun foldFactorIntoBoost(boost: Double, baseWeight: Double, factor: Double): Double =
        boost + baseWeight * (factor - 1.0)
}
