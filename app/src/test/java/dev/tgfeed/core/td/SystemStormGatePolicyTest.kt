package dev.tgfeed.core.td

import dev.tgfeed.core.feed.IndexCrawlGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SystemStormPolicy]（R208 开机风暴治理·纯策略）行为单测：
 * 开机静默期边界（uptime 语义）、CPU 忙闲退避（首采样未知防御/阈值边界/
 * iowait 计忙侧口径）、loadavg 辅助信号（D 态地板教训：默认关但函数在）、
 * 综合放行与 kill switch、等待超限兜底、派工间歇决策第三参（stormHeld）。
 */
class SystemStormGatePolicyTest {

    private companion object {
        const val BOOT_QUIET_MS = 5L * 60_000L
    }

    // ---------------- 开机静默期 ----------------

    @Test
    fun `开机静默期内持有_边界恰好到期不持有`() {
        // 行为：uptime<静默窗即持有；恰好到窗长（=）不持有（闭区间到期放行）
        assertTrue(SystemStormPolicy.bootQuietHeld(uptimeMs = 0L, bootQuietMs = BOOT_QUIET_MS))
        assertTrue(SystemStormPolicy.bootQuietHeld(BOOT_QUIET_MS - 1, BOOT_QUIET_MS))
        assertFalse(SystemStormPolicy.bootQuietHeld(BOOT_QUIET_MS, BOOT_QUIET_MS))
        assertFalse(SystemStormPolicy.bootQuietHeld(BOOT_QUIET_MS + 1, BOOT_QUIET_MS))
    }

    @Test
    fun `静默窗零值关闭_任何 uptime 不持有`() {
        // 行为：0 = 关闭开机静默闸（用户平时冷启动系统已运行多小时，恒放行路径）
        assertFalse(SystemStormPolicy.bootQuietHeld(uptimeMs = 0L, bootQuietMs = 0L))
    }

    // ---------------- CPU 余量退避（主负载信号） ----------------

    @Test
    fun `首采样无基线不持有_读不到信号不能锁死后台`() {
        // 行为：busyFrac=null（首拍无差分/读盘失败）按不持有——防御口径，
        // 开机静默期闸独立生效
        assertFalse(SystemStormPolicy.cpuBusyHeld(busyFrac = null, busyThresholdPct = 85))
    }

    @Test
    fun `忙闲超阈值持有_恰好等于不持有`() {
        // 行为：阈值 85——0.8499 不持有、0.85 恰等不持有（严格大于）、0.8501 持有
        assertFalse(SystemStormPolicy.cpuBusyHeld(busyFrac = 0.0, busyThresholdPct = 85))
        assertFalse(SystemStormPolicy.cpuBusyHeld(busyFrac = 0.849, busyThresholdPct = 85))
        assertFalse(SystemStormPolicy.cpuBusyHeld(busyFrac = 0.85, busyThresholdPct = 85))
        assertTrue(SystemStormPolicy.cpuBusyHeld(busyFrac = 0.851, busyThresholdPct = 85))
        assertTrue(SystemStormPolicy.cpuBusyHeld(busyFrac = 1.0, busyThresholdPct = 85))
    }

    @Test
    fun `阈值零关闭CPU闸_全忙也不持有`() {
        assertFalse(SystemStormPolicy.cpuBusyHeld(busyFrac = 1.0, busyThresholdPct = 0))
    }

    // ---------------- loadavg 辅助信号（D 态地板教训） ----------------

    @Test
    fun `负载读不到不持有_倍率零关闭负载闸`() {
        // 行为：null（读不到 /proc/loadavg）不持有；倍率 0=关（默认关——
        // 事故机 27 个常驻 D 态内核线程把 loadavg 地板顶到 27，直判会永远持有）
        assertFalse(SystemStormPolicy.loadHeld(load1 = null, cores = 8, loadPerCore = 1))
        assertFalse(SystemStormPolicy.loadHeld(load1 = 999.0, cores = 8, loadPerCore = 0))
    }

    @Test
    fun `倍率开启时负载超核数倍率持有_恰好等于不持有`() {
        // 行为：8 核×1 倍率=8 为阈值；>8 退避、=8 不退避；核数防御 0 核按 1 核
        assertFalse(SystemStormPolicy.loadHeld(load1 = 8.0, cores = 8, loadPerCore = 1))
        assertTrue(SystemStormPolicy.loadHeld(load1 = 8.1, cores = 8, loadPerCore = 1))
        assertTrue(SystemStormPolicy.loadHeld(load1 = 35.0, cores = 8, loadPerCore = 1))
        assertFalse(SystemStormPolicy.loadHeld(load1 = 1.0, cores = 0, loadPerCore = 1))
        assertTrue(SystemStormPolicy.loadHeld(load1 = 1.5, cores = 0, loadPerCore = 1))
        assertFalse(SystemStormPolicy.loadHeld(load1 = 8.0, cores = 4, loadPerCore = 2))
        assertTrue(SystemStormPolicy.loadHeld(load1 = 8.1, cores = 4, loadPerCore = 2))
    }

    // ---------------- 综合放行 ----------------

    @Test
    fun `总开关关闭恒放行_全部闸持有也不拦`() {
        // 行为：kill switch（stormGateEnabled=false）= R208 前行为
        assertTrue(
            SystemStormPolicy.stormClear(
                uptimeMs = 0L, bootQuietMs = BOOT_QUIET_MS,
                busyFrac = 1.0, busyThresholdPct = 85,
                load1 = 99.0, cores = 8, loadPerCore = 1, enabled = false,
            ),
        )
    }

    @Test
    fun `任一闸持有即不放行_全清才放行`() {
        fun clear(
            uptime: Long, busy: Double?, load: Double?,
            busyPct: Int = 85, perCore: Int = 0,
        ) = SystemStormPolicy.stormClear(
            uptimeMs = uptime, bootQuietMs = BOOT_QUIET_MS,
            busyFrac = busy, busyThresholdPct = busyPct,
            load1 = load, cores = 8, loadPerCore = perCore, enabled = true,
        )
        // 全清：静默期外+CPU 闲+负载闸关
        assertTrue(clear(BOOT_QUIET_MS + 1, 0.2, null))
        // 开机静默期单独持有
        assertFalse(clear(0L, 0.2, null))
        // CPU 忙单独持有
        assertFalse(clear(BOOT_QUIET_MS + 1, 0.9, null))
        // CPU 未知（null）不拦——静默期/CPU 闸之外只剩负载闸（默认关=放行）
        assertTrue(clear(BOOT_QUIET_MS + 1, null, null))
        // 负载闸开启且超阈：单独持有
        assertFalse(clear(BOOT_QUIET_MS + 1, 0.2, 9.0, perCore = 1))
        // 多闸同时持有
        assertFalse(clear(0L, 0.9, 99.0, perCore = 1))
    }

    // ---------------- 等待超限兜底 ----------------

    @Test
    fun `等待超限兜底_零值永不超限`() {
        // 行为：maxHold=0 = 永不兜底放行（由 stormClear 自身条件解除）；
        // 正常值下 waited>=maxHold 即超限
        assertFalse(SystemStormPolicy.holdExpired(waitedMs = 10L * 60_000L, maxHoldMs = 0L))
        assertFalse(SystemStormPolicy.holdExpired(599_999, 600_000))
        assertTrue(SystemStormPolicy.holdExpired(600_000, 600_000))
        assertTrue(SystemStormPolicy.holdExpired(600_001, 600_000))
    }

    // ---------------- 派工间歇决策（stormHeld 第三参，R208 时机闸同家族） ----------------

    @Test
    fun `风暴闸持有走退避间歇_与预算健康同档`() {
        // 行为：预算有+健康正常+风暴持有 → 15s 退避拍（不空转轮询 0.5s）
        assertEquals(
            IndexCrawlGate.DISPATCH_DRY_TICK_MS,
            IndexCrawlGate.dispatchTickDelayMs(budgetRemaining = 100, healthCrawlPaused = false, stormHeld = true),
        )
    }

    @Test
    fun `三闸全清走正常间歇_既有两参调用零变化`() {
        assertEquals(
            IndexCrawlGate.DISPATCH_TICK_MS,
            IndexCrawlGate.dispatchTickDelayMs(budgetRemaining = 100, healthCrawlPaused = false, stormHeld = false),
        )
        // 既有两参调用点（默认参）行为不变
        assertEquals(
            IndexCrawlGate.DISPATCH_TICK_MS,
            IndexCrawlGate.dispatchTickDelayMs(100, false),
        )
        assertEquals(
            IndexCrawlGate.DISPATCH_DRY_TICK_MS,
            IndexCrawlGate.dispatchTickDelayMs(0, false),
        )
        assertEquals(
            IndexCrawlGate.DISPATCH_DRY_TICK_MS,
            IndexCrawlGate.dispatchTickDelayMs(100, true),
        )
        // 负预算防御（既有口径）
        assertEquals(
            IndexCrawlGate.DISPATCH_DRY_TICK_MS,
            IndexCrawlGate.dispatchTickDelayMs(-5, false),
        )
    }
}
