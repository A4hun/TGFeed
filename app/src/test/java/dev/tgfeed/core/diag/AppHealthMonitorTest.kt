package dev.tgfeed.core.diag

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppHealthMonitor]（R165 F3）纯函数行为单测：
 * 内存压力分级（pressureLevelOf）与主线程停跳判定（isStalled）。
 * 只测纯函数——不触碰 Handler/Looper 等需真机运行时的心跳机制本体
 * （心跳链路的设备验证归 anr-analysis.md §7 阶段 3 取证清单）。
 */
class AppHealthMonitorTest {

    // ---------------- 内存压力分级 ----------------

    @Test
    fun `RSS分级_采样失败按OK宁漏勿误`() {
        // 读不到 /proc（0/负数）不该喊停爬取——取证缺口不能反过来变成误杀
        assertTrue(AppHealthMonitor.pressureLevelOf(0L) == AppHealthMonitor.PRESSURE_OK)
        assertTrue(AppHealthMonitor.pressureLevelOf(-1L) == AppHealthMonitor.PRESSURE_OK)
    }

    @Test
    fun `RSS分级_正常HIGH_SEVERE三档边界`() {
        // 边界值恰好落在档上（>= 口径）：HIGH 下沿=1.8G、SEVERE 下沿=2.6G
        assertTrue(AppHealthMonitor.pressureLevelOf(1L) == AppHealthMonitor.PRESSURE_OK)
        assertTrue(AppHealthMonitor.pressureLevelOf(AppHealthMonitor.RSS_HIGH_KB) == AppHealthMonitor.PRESSURE_HIGH)
        assertTrue(
            AppHealthMonitor.pressureLevelOf(AppHealthMonitor.RSS_HIGH_KB - 1) == AppHealthMonitor.PRESSURE_OK,
        )
        assertTrue(AppHealthMonitor.pressureLevelOf(AppHealthMonitor.RSS_SEVERE_KB) == AppHealthMonitor.PRESSURE_SEVERE)
        assertTrue(
            AppHealthMonitor.pressureLevelOf(AppHealthMonitor.RSS_SEVERE_KB - 1) == AppHealthMonitor.PRESSURE_HIGH,
        )
        assertTrue(AppHealthMonitor.pressureLevelOf(Long.MAX_VALUE) == AppHealthMonitor.PRESSURE_SEVERE)
    }

    // ---------------- 主线程停跳判定 ----------------

    @Test
    fun `停跳判定_距上次心跳超阈值即停跳`() {
        val beat = 1_000_000L
        val th = AppHealthMonitor.STALL_THRESHOLD_MS
        // 恰好等于阈值不算（须严格超过——20s 整的心跳延迟是 GC 停顿上限量级，不算事故）
        assertFalse(AppHealthMonitor.isStalled(beat, beat + th, th))
        // 超过 1ms 即事故态
        assertTrue(AppHealthMonitor.isStalled(beat, beat + th + 1, th))
        // 远超（夜事故形态：整夜 2h48m）
        assertTrue(AppHealthMonitor.isStalled(beat, beat + 2L * 3_600_000L, th))
    }

    @Test
    fun `停跳判定_时钟倒退或同刻不算停跳`() {
        val beat = 1_000_000L
        // now == beat（尚未跳下一拍）与 now < beat（uptime 单调但防御）都不算
        assertFalse(AppHealthMonitor.isStalled(beat, beat, 1000L))
        assertFalse(AppHealthMonitor.isStalled(beat, beat - 1, 1000L))
    }

    @Test
    fun `阈值与档位常量口径锁`() {
        // 口径锁：停跳 20s（ANR 派发超时 5s 的事故级上界，GC/重组毫秒级摸不到）；
        // HIGH=1.8G / SEVERE=2.6G（16384 夜事故进程击穿前量级，见 anr-analysis.md §6 F3）
        assertTrue(AppHealthMonitor.STALL_THRESHOLD_MS == 20_000L)
        assertTrue(AppHealthMonitor.RSS_HIGH_KB == 1_800_000L)
        assertTrue(AppHealthMonitor.RSS_SEVERE_KB == 2_600_000L)
        assertTrue(AppHealthMonitor.PRESSURE_OK < AppHealthMonitor.PRESSURE_HIGH)
        assertTrue(AppHealthMonitor.PRESSURE_HIGH < AppHealthMonitor.PRESSURE_SEVERE)
    }
}
