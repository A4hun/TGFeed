package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IndexCrawlGate]（R163 B2 时机窗口状态机+探索配额）的行为单测：
 * smartTiming 关=恒放行（现状等效，纪律①）、计量网络硬线（任何放宽级不放）、
 * 一级窗口（充电+后台静默+结算宽限）、前台/播放挂起、饿死兜底两级放宽与回归、
 * 轮转兜底车道闸（仅一级窗口+队列空闲+分档开）、探索配额 10 槽周期轮转。
 */
class IndexCrawlGateTest {

    private companion object {
        const val NOW = 1_000_000_000_000L
        val TIMING_ON = IndexCrawlGate.CrawlTimingConfig(smartTiming = true)
    }

    // ---------------- 构造助手 ----------------

    /** 系统状态快照构造器：默认=理想爬窗（后台+无播放+非计量+充电+宽限已过） */
    private fun inputs(
        foreground: Boolean = false,
        playbackActive: Boolean = false,
        networkMetered: Boolean = false,
        charging: Boolean = true,
        lastWatchSettleMs: Long = NOW - 20L * 60_000L, // 20 分钟前结算：宽限(10min)已过
        nowMs: Long = NOW,
        starveLevel: Int = 0,
    ) = IndexCrawlGate.TimingInputs(
        foreground = foreground, playbackActive = playbackActive, networkMetered = networkMetered,
        charging = charging, lastWatchSettleMs = lastWatchSettleMs, nowMs = nowMs, starveLevel = starveLevel,
    )

    // ---------------- 纪律①：smartTiming 关 = 现状等效 ----------------

    @Test
    fun `智能时机关闭恒放行_前台计量充电全不看`() {
        // 行为：默认配置（smartTiming=false）下巡游爬取不受任何窗口约束——
        // 与现状（退后台即爬）完全一致，开关关=行为零变化
        val off = IndexCrawlGate.CrawlTimingConfig() // 默认关
        assertTrue(IndexCrawlGate.roamingCrawlAllowed(inputs(foreground = true, networkMetered = true), off))
        assertTrue(IndexCrawlGate.roamingCrawlAllowed(inputs(charging = false), off))
    }

    // ---------------- 一级窗口判定 ----------------

    @Test
    fun `理想爬窗放行_后台静默加充电加非计量`() {
        // 行为：0 级兜底下，后台+无播放+充电+非计量+宽限过 → 放行
        assertTrue(IndexCrawlGate.roamingCrawlAllowed(inputs(), TIMING_ON))
    }

    @Test
    fun `前台一律挂起_用户在浏览也是活跃`() {
        // 行为：前台挂起（保守口径，§7.1——TDLib 请求通道与首拉/换台抢的 R145 实证）
        assertFalse(IndexCrawlGate.roamingCrawlAllowed(inputs(foreground = true), TIMING_ON))
        // 饿死 2 级也不放开前台
        assertFalse(IndexCrawlGate.roamingCrawlAllowed(inputs(foreground = true, starveLevel = 2), TIMING_ON))
    }

    @Test
    fun `播放在跑挂起_后台音频在听与PiP都算用户在消费`() {
        // 行为：audioBackground 在听/PiP 小窗 = 播放活跃 = 挂起（§7.1）
        assertFalse(IndexCrawlGate.roamingCrawlAllowed(inputs(playbackActive = true), TIMING_ON))
        assertFalse(IndexCrawlGate.roamingCrawlAllowed(inputs(playbackActive = true, starveLevel = 2), TIMING_ON))
    }

    @Test
    fun `计量网络是硬线_任何兜底级都不放`() {
        // 行为：流量费用红线，永不放宽（§7.4）——即使充电+闲置+已饿死 2 级
        assertFalse(IndexCrawlGate.roamingCrawlAllowed(inputs(networkMetered = true), TIMING_ON))
        assertFalse(
            IndexCrawlGate.roamingCrawlAllowed(
                inputs(networkMetered = true, starveLevel = 2), TIMING_ON,
            ),
        )
    }

    @Test
    fun `未充电0级不放_结算宽限未过也不放`() {
        // 行为：0 级需要充电；刚结算完（宽限 10 分钟内）不算闲置——防刚退后台就起跑
        assertFalse(IndexCrawlGate.roamingCrawlAllowed(inputs(charging = false), TIMING_ON))
        assertFalse(
            IndexCrawlGate.roamingCrawlAllowed(
                inputs(lastWatchSettleMs = NOW - 5L * 60_000L), // 5 分钟前结算：宽限未过
                TIMING_ON,
            ),
        )
    }

    // ---------------- 饿死兜底两级放宽与回归 ----------------

    @Test
    fun `1级兜底去充电条件_未充电但后台静默放行`() {
        // 行为：24h 窗口累计不足 → 放宽一级（去充电）——夜里躺着刷没充电的设备也有更新
        assertTrue(IndexCrawlGate.roamingCrawlAllowed(inputs(charging = false, starveLevel = 1), TIMING_ON))
        // 0 级同条件仍不放（对照）
        assertFalse(IndexCrawlGate.roamingCrawlAllowed(inputs(charging = false, starveLevel = 0), TIMING_ON))
    }

    @Test
    fun `2级兜底再松结算宽限_刚结算也放行`() {
        // 行为：再 24h 无窗口 → 二级放宽（后台+无播放即可，忽略结算宽限）
        assertTrue(
            IndexCrawlGate.roamingCrawlAllowed(
                inputs(lastWatchSettleMs = NOW - 1L * 60_000L, starveLevel = 2), // 1 分钟前结算
                TIMING_ON,
            ),
        )
        // 1 级同条件仍不放（对照）
        assertFalse(
            IndexCrawlGate.roamingCrawlAllowed(
                inputs(lastWatchSettleMs = NOW - 1L * 60_000L, starveLevel = 1),
                TIMING_ON,
            ),
        )
    }

    @Test
    fun `兜底级重估_窗口充足归零_逐级推进_二级封顶`() {
        // 行为：§7.4 推进规则——
        // ① 24h 累计 ≥30min → 归 0（窗口充足回到最严格条件最省电）；
        // ② 0 级满 24h 且累计不足 → 1；
        // ③ 1 级满 24h 且累计为 0（真的一窗没有）→ 2；
        // ④ 2 级封顶；时长不足不推进
        val d24 = IndexCrawlGate.STARVE_LEVEL_INTERVAL_MS
        val min30 = IndexCrawlGate.STARVE_MIN_WINDOW_MS
        // ① 窗口充足：任何级都归 0
        assertEquals(0, IndexCrawlGate.nextStarveLevel(2, min30, d24 * 3))
        assertEquals(0, IndexCrawlGate.nextStarveLevel(1, min30 + 1, d24 * 2))
        // ② 0 → 1
        assertEquals(1, IndexCrawlGate.nextStarveLevel(0, min30 - 1, d24))
        assertEquals(0, IndexCrawlGate.nextStarveLevel(0, min30 - 1, d24 - 1)) // 时长不足
        // ③ 1 → 2（需累计为 0）
        assertEquals(2, IndexCrawlGate.nextStarveLevel(1, 0L, d24))
        assertEquals(1, IndexCrawlGate.nextStarveLevel(1, 1L, d24)) // 有过 1ms 窗口就不进二级
        // ④ 封顶
        assertEquals(2, IndexCrawlGate.nextStarveLevel(2, 0L, d24 * 10))
    }

    // ---------------- 轮转全量兜底车道闸（机制④） ----------------

    @Test
    fun `轮转车道仅一级窗口且队列空闲且双开关全开`() {
        // 行为：机制④是「最奢侈的爬取」——只配最好的窗口（充电+非计量+闲置，不受饿死放宽），
        // 优先级最低（常规/探索队列空闲才跑），分档/智能时机任一关闭即不存在
        val idle = inputs() // 默认即理想窗口
        assertTrue(IndexCrawlGate.rotatorCrawlAllowed(idle, TIMING_ON, tieringEnabled = true, queuesIdle = true))
        // 双开关任一关：不存在
        assertFalse(IndexCrawlGate.rotatorCrawlAllowed(idle, TIMING_ON, tieringEnabled = false, queuesIdle = true))
        assertFalse(
            IndexCrawlGate.rotatorCrawlAllowed(
                idle, IndexCrawlGate.CrawlTimingConfig(), // smartTiming 默认关
                tieringEnabled = true, queuesIdle = true,
            ),
        )
        // 队列非空：让路（优先级最低）
        assertFalse(IndexCrawlGate.rotatorCrawlAllowed(idle, TIMING_ON, tieringEnabled = true, queuesIdle = false))
        // 非充电：轮转不吃饿死放宽（对照：巡游主闸 1 级会放）
        assertFalse(
            IndexCrawlGate.rotatorCrawlAllowed(
                inputs(charging = false, starveLevel = 1), TIMING_ON, tieringEnabled = true, queuesIdle = true,
            ),
        )
        assertTrue(
            IndexCrawlGate.roamingCrawlAllowed(inputs(charging = false, starveLevel = 1), TIMING_ON), // 对照
        )
    }

    // ---------------- 探索配额（机制②，ε-greedy） ----------------

    @Test
    fun `探索配额按10槽周期轮转_默认30即每10槽3探索`() {
        // 行为：quota=30 → 槽 0,1,2 探索、3~9 常规，周期性重复（制度化 ε-greedy）
        val c = TIMING_ON // 默认 30%
        val explore = (0 until 10).map { IndexCrawlGate.isExploreSlot(it, c) }
        assertEquals(listOf(true, true, true, false, false, false, false, false, false, false), explore)
        // 周期重复（槽 10 又是探索）
        assertTrue(IndexCrawlGate.isExploreSlot(10, c))
        assertFalse(IndexCrawlGate.isExploreSlot(13, c))
    }

    @Test
    fun `配额零关探索_现状等效_配额上限封顶五十`() {
        // 行为：quota=0 → 恒常规（探索车道关=现状等效）；超上限截到 50%（5/10 槽）
        val off = IndexCrawlGate.CrawlTimingConfig(smartTiming = true, exploreQuotaPct = 0)
        (0 until 20).forEach { assertFalse(IndexCrawlGate.isExploreSlot(it, off)) }
        val huge = IndexCrawlGate.CrawlTimingConfig(smartTiming = true, exploreQuotaPct = 70)
        val slots = (0 until 10).count { IndexCrawlGate.isExploreSlot(it, huge) }
        assertEquals(5, slots) // 70% 截到 50%
    }

    @Test
    fun `负序号防御不崩溃`() {
        // 行为：派工计数器异常负值按绝对值相位处理（防御性，不假设调用方永远正确）
        assertTrue(IndexCrawlGate.isExploreSlot(-10, TIMING_ON)) // 同槽 10：探索
        assertFalse(IndexCrawlGate.isExploreSlot(-3, TIMING_ON)) // 同槽 3：常规
    }

    // ---------------- R165 预算耗尽派工退避（热循环止血） ----------------

    @Test
    fun `预算干涸或健康喊停时派工tick拉长到退避间隔`() {
        // 行为：预算有余量且健康正常 → 现状 0.5s/拍（行为零变化）；
        // 预算余量≤0（派了也是采样闸秒退的空转）或健康监视器喊停 → 15s/拍
        // （干涸期空转从实测 14 派工/秒压回 1/30）
        assertEquals(
            IndexCrawlGate.DISPATCH_TICK_MS,
            IndexCrawlGate.dispatchTickDelayMs(budgetRemaining = 1, healthCrawlPaused = false),
        )
        assertEquals(
            IndexCrawlGate.DISPATCH_DRY_TICK_MS,
            IndexCrawlGate.dispatchTickDelayMs(budgetRemaining = 0, healthCrawlPaused = false),
        )
        assertEquals(
            IndexCrawlGate.DISPATCH_DRY_TICK_MS,
            IndexCrawlGate.dispatchTickDelayMs(budgetRemaining = -5, healthCrawlPaused = false),
        )
        assertEquals(
            IndexCrawlGate.DISPATCH_DRY_TICK_MS,
            IndexCrawlGate.dispatchTickDelayMs(budgetRemaining = 999, healthCrawlPaused = true),
        )
    }

    @Test
    fun `退避间歇两档常量比例锁死_干涸拍至少三十倍慢于常态`() {
        // 口径锁：DISPATCH_TICK_MS=500ms（现状）、DISPATCH_DRY_TICK_MS=15s（R165 退避）。
        // 两档比例 ≥30 是止血有效性下限（实测热循环 14 派工/秒 → ≤1/30 派工频率）
        assertEquals(500L, IndexCrawlGate.DISPATCH_TICK_MS)
        assertEquals(15_000L, IndexCrawlGate.DISPATCH_DRY_TICK_MS)
        assertTrue(IndexCrawlGate.DISPATCH_DRY_TICK_MS >= 30 * IndexCrawlGate.DISPATCH_TICK_MS)
    }

    // ---------------- R165 白嫖冷却（harvestLocalCache 防重扫） ----------------

    @Test
    fun `白嫖冷却距上次尝试满间隔才放行`() {
        // 行为：同一频道 1h 冷却——间隔未满不放行（0 产频道整夜重扫的止血），
        // 满间隔/从未白嫖（last=0 且系统时钟口径 now 足够大）放行
        val now = System.currentTimeMillis()
        assertFalse(IndexCrawlGate.shouldHarvestLocal(now - 1000L, now)) // 1s 前刚试过：拦
        assertFalse(IndexCrawlGate.shouldHarvestLocal(now - IndexCrawlGate.HARVEST_LOCAL_MIN_INTERVAL_MS + 1, now)) // 差 1ms：拦
        assertTrue(IndexCrawlGate.shouldHarvestLocal(now - IndexCrawlGate.HARVEST_LOCAL_MIN_INTERVAL_MS, now)) // 恰满：放
        assertTrue(IndexCrawlGate.shouldHarvestLocal(0L, now)) // 从未白嫖：放
    }

    @Test
    fun `白嫖冷却间隔常量锁一小时`() {
        // 口径锁：1h。过短挡不住整夜重扫（每 5min 枚举拍都重试），过长浪费零成本加菜机会
        assertEquals(60L * 60_000L, IndexCrawlGate.HARVEST_LOCAL_MIN_INTERVAL_MS)
    }
}
