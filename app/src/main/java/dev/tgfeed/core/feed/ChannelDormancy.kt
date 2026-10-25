package dev.tgfeed.core.feed

/**
 * R204 DS-3 断更/僵尸标记（timeline-opportunities DS-3，W3 断更识别）：
 * 频道活性 → 休眠档的**纯判定**，发现页候选卡（⚠ 胶囊）与频道主页（徽章行）共用，
 * 一处定义零镜像。
 *
 * 口径（诚实优先，防低频优质频道误伤——W3 设计明示的缓冲档）：
 *  - `daysSince` = 现在 − 最近发布（head 窗/索引 MAX 双供给，都是第一手时间戳）；
 *  - **疑似停更**：≥ [SUSPECTED_DEAD_DAYS]（90 天）——僵尸档（ChannelHealth 90 天
 *    僵尸判定同量级）；
 *  - **N 天未更**：≥ [STALE_DAYS]（30 天）且低于历史节奏 3 倍（低频缓冲：月更/
 *    季更频道不按日更节奏断罪——`postsPerWeek` 不可知时不做缓冲，只按硬档）；
 *  - 30 天内 / 缓冲未过 → null（没有证据不臆测）。
 *
 * 档位文案刻意是**事实陈述**（「47 天未更」）而非结论（「已死」）——lastActivity
 * 对月采样频道只反映 head 窗（256 行，约近月），陈度无害但不冒充全知。
 */
object ChannelDormancy {

    /** 硬档一：30 天未更（事实陈述档） */
    const val STALE_DAYS = 30

    /** 硬档二：疑似停更（僵尸档，与 ChannelHealth 90 天判定同量级） */
    const val SUSPECTED_DEAD_DAYS = 90

    /** 低频缓冲：最近发布距今 < 历史发帖间隔 × 此倍数 → 不出 30 天档（防误伤月更/季更频道） */
    const val LOW_FREQ_BUFFER_FACTOR = 3.0

    /** 休眠档（tier 升序 = 越来越像尸体） */
    enum class Tier { STALE, SUSPECTED_DEAD }

    /** 判定结果：档位 + 实际天数（文案/测试共用） */
    data class Dormancy(val tier: Tier, val days: Int)

    /**
     * 断更判定（纯函数）。
     * @param lastActivitySec 最近发布时刻（epoch 秒；0/负 = 未知 → null 不臆测）
     * @param nowSec 当前时刻（epoch 秒）
     * @param postsPerWeek 历史发帖频率（条/周，索引基因口径）；null/≤0 = 未知（只走硬档）
     */
    fun of(lastActivitySec: Long, nowSec: Long, postsPerWeek: Double? = null): Dormancy? {
        if (lastActivitySec <= 0L) return null
        val days = ((nowSec - lastActivitySec) / 86_400L).toInt()
        if (days < STALE_DAYS) return null
        // 低频缓冲：历史间隔×3 还没到就不出档（月更频道 35 天没更=正常节奏内）
        if (postsPerWeek != null && postsPerWeek > 0.0) {
            val expectedDays = 7.0 / postsPerWeek
            if (days < expectedDays * LOW_FREQ_BUFFER_FACTOR) return null
        }
        return if (days >= SUSPECTED_DEAD_DAYS) Dormancy(Tier.SUSPECTED_DEAD, days)
        else Dormancy(Tier.STALE, days)
    }

    /** 胶囊/徽章文案：事实陈述体（「47 天未更」/「疑似停更 132 天」） */
    fun label(d: Dormancy): String =
        if (d.tier == Tier.SUSPECTED_DEAD) "疑似停更 ${d.days} 天" else "${d.days} 天未更"

    /**
     * 发现页候选卡胶囊文案（⚠ 前缀=警示家族，沿「⚠ 全站 N 人标记了避雷」先例——
     * UI 侧按前缀染 WarningRed）。无档位 null（不渲染）。
     */
    fun chipOf(g: DiscoveryGoldmine.ChannelGold, nowSec: Long = System.currentTimeMillis() / 1000L): String? =
        of(g.lastActivitySec, nowSec)?.let { "⚠ ${label(it)}" }

    /**
     * 频道主页徽章行文案：基因（索引首尾+条数）供给的频率走低频缓冲，
     * 档位为 null 时返回 null（行不渲染）。
     */
    fun homeLineOf(
        lastActivitySec: Long,
        nowSec: Long,
        postsPerWeek: Double?,
    ): String? = of(lastActivitySec, nowSec, postsPerWeek)?.let { label(it) }
}
