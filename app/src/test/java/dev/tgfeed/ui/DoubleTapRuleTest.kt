package dev.tgfeed.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R189 双击刷新规则纯判定单测（用户拍板：刷新触发收窄为双击）。
 *
 * 判定矩阵（resolveDoubleTap）：未激活单击=SWITCH（清窗）；已激活第一发=NONE
 * （记窗）；已激活且窗口内第二发=REFRESH（清窗防三连）；窗口过期=重新记窗不刷。
 */
class DoubleTapRuleTest {

    private val window = 300L

    @Test
    fun `未激活单击=纯切换且清窗`() {
        val d = resolveDoubleTap(active = false, nowMs = 1000L, lastActiveTapAtMs = 900L, windowMs = window)
        assertEquals(DoubleTapAction.SWITCH, d.action)
        assertEquals(0L, d.newLastActiveTapAtMs)
    }

    @Test
    fun `未激活单击_无历史窗_也是切换`() {
        val d = resolveDoubleTap(active = false, nowMs = 1000L, lastActiveTapAtMs = 0L, windowMs = window)
        assertEquals(DoubleTapAction.SWITCH, d.action)
        assertEquals(0L, d.newLastActiveTapAtMs)
    }

    @Test
    fun `已激活第一发=无操作且记窗`() {
        val d = resolveDoubleTap(active = true, nowMs = 1000L, lastActiveTapAtMs = 0L, windowMs = window)
        assertEquals(DoubleTapAction.NONE, d.action)
        assertEquals(1000L, d.newLastActiveTapAtMs)
    }

    @Test
    fun `已激活窗口内第二发=刷新且清窗`() {
        val d = resolveDoubleTap(active = true, nowMs = 1000L, lastActiveTapAtMs = 800L, windowMs = window)
        assertEquals(DoubleTapAction.REFRESH, d.action)
        assertEquals(0L, d.newLastActiveTapAtMs)
    }

    @Test
    fun `已激活窗口边界=恰在窗口内刷新`() {
        // now - last == windowMs 边界：按 ≤ 判定（系统双击窗口同口径）
        val d = resolveDoubleTap(active = true, nowMs = 1100L, lastActiveTapAtMs = 800L, windowMs = window)
        assertEquals(DoubleTapAction.REFRESH, d.action)
    }

    @Test
    fun `已激活窗口过期=重记窗不刷新`() {
        val d = resolveDoubleTap(active = true, nowMs = 1200L, lastActiveTapAtMs = 800L, windowMs = window)
        assertEquals(DoubleTapAction.NONE, d.action)
        assertEquals(1200L, d.newLastActiveTapAtMs)
    }

    @Test
    fun `三连发只在第二发刷新_第三发重记窗`() {
        // 模拟连点三下已激活 tab：第一发记窗→第二发刷新清窗→第三发（last=0）重记窗
        val first = resolveDoubleTap(active = true, nowMs = 1000L, lastActiveTapAtMs = 0L, windowMs = window)
        val second = resolveDoubleTap(active = true, nowMs = 1150L, lastActiveTapAtMs = first.newLastActiveTapAtMs, windowMs = window)
        val third = resolveDoubleTap(active = true, nowMs = 1300L, lastActiveTapAtMs = second.newLastActiveTapAtMs, windowMs = window)
        assertEquals(DoubleTapAction.NONE, first.action)
        assertEquals(DoubleTapAction.REFRESH, second.action)
        assertEquals(DoubleTapAction.NONE, third.action)
        assertEquals(1300L, third.newLastActiveTapAtMs)
    }

    @Test
    fun `双击未激活tab=只切换不刷新`() {
        // 第一发（未激活）= SWITCH 清窗；第二发（此刻已激活，last=0）= NONE 重记窗
        val first = resolveDoubleTap(active = false, nowMs = 1000L, lastActiveTapAtMs = 0L, windowMs = window)
        val second = resolveDoubleTap(active = true, nowMs = 1150L, lastActiveTapAtMs = first.newLastActiveTapAtMs, windowMs = window)
        assertEquals(DoubleTapAction.SWITCH, first.action)
        assertEquals(DoubleTapAction.NONE, second.action)
    }
}
