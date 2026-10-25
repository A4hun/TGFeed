package dev.tgfeed.ui.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R277 GlEnginePolicy 单测（**R345 GL-only 收口版**——用户裁决「新引擎已经好了，
 * 把老的干掉」：Canvas 档位退役后的解析+诊断账全矩阵锚——政策件即唯一判定口，
 * 矩阵锁死防漂移）。
 *  - resolve：es3 门唯一分叉（OPENGL/_DIAGNOSTIC 诚实降级——无 Canvas 可回）；
 *  - 旧偏好读侧收口：legacyCanvasPref（退役值 1 标记→调用侧日志一行）；
 *  - 崩溃账本（会话旗标决算+健康自愈+提示线）随诊断口径保留。
 */
class GlEnginePolicyTest {

    // ==================== GL-only 解析矩阵 ====================

    @Test
    fun `ES3 可用 = 恒 OPENGL（gl_only——无 Canvas 可回）`() {
        val r = GlEnginePolicy.resolve(es3Available = true)
        assertEquals(GlEnginePolicy.Engine.OPENGL, r.engine)
        assertEquals("gl_only", r.reason)
    }

    @Test
    fun `ES3 缺失 = DIAGNOSTIC 诚实降级（探针闸——黑屏+诊断行不静默换渲染器）`() {
        val r = GlEnginePolicy.resolve(es3Available = false)
        assertEquals(GlEnginePolicy.Engine.DIAGNOSTIC, r.engine)
        assertEquals("es3_missing", r.reason)
    }

    @Test
    fun `诊断档不在 OPENGL 域（枚举二态完备）`() {
        // GL-only 后枚举域恰两态：OPENGL/DIAGNOSTIC——Canvas 档位退役防复活锚
        assertEquals(2, GlEnginePolicy.Engine.entries.size)
        assertTrue(GlEnginePolicy.Engine.OPENGL in GlEnginePolicy.Engine.entries)
        assertTrue(GlEnginePolicy.Engine.DIAGNOSTIC in GlEnginePolicy.Engine.entries)
    }

    // ==================== 旧偏好键读侧收口（R345 语义） ====================

    @Test
    fun `旧偏好值 1 = Canvas 退役值标记（读侧归一化——调用侧日志一行后照常 GL）`() {
        assertTrue(GlEnginePolicy.legacyCanvasPref(GlEnginePolicy.PREF_CANVAS))
        assertTrue(GlEnginePolicy.legacyCanvasPref(1))
        // 其余值（自动 0/强制 GL 2/损坏值）均非退役标记
        assertFalse(GlEnginePolicy.legacyCanvasPref(GlEnginePolicy.PREF_AUTO))
        assertFalse(GlEnginePolicy.legacyCanvasPref(GlEnginePolicy.PREF_OPENGL))
        assertFalse(GlEnginePolicy.legacyCanvasPref(-1))
        assertFalse(GlEnginePolicy.legacyCanvasPref(99))
    }

    @Test
    fun `prefs 键域常数恒定（防御读域——退役值 1 保留在域内做归一化标记）`() {
        assertEquals(0, GlEnginePolicy.PREF_AUTO)
        assertEquals(1, GlEnginePolicy.PREF_CANVAS)
        assertEquals(2, GlEnginePolicy.PREF_OPENGL)
    }

    // ==================== 会话旗标冷启动决算（诊断账本——native 崩覆盖） ====================

    @Test
    fun `旗标仍立 = 上次死于 GL 会话 = 计数加一`() {
        assertEquals(1, GlEnginePolicy.crashCountOnColdStart(sessionActiveWas = true, crashCount = 0))
        assertEquals(3, GlEnginePolicy.crashCountOnColdStart(sessionActiveWas = true, crashCount = 2))
    }

    @Test
    fun `旗标未立 = 上次干净 = 计数原样保留（不清零——同机连续崩才越提示线）`() {
        assertEquals(0, GlEnginePolicy.crashCountOnColdStart(sessionActiveWas = false, crashCount = 0))
        assertEquals(2, GlEnginePolicy.crashCountOnColdStart(sessionActiveWas = false, crashCount = 2))
    }

    // ==================== 健康会话自愈（强停假阳性的兜回——16384 实锤教训） ====================

    @Test
    fun `自愈门 30s 零异常且计数非零才清零`() {
        // 过门且有账 → 清
        assertTrue(
            GlEnginePolicy.shouldResetCrashCount(GlEnginePolicy.HEALTHY_RESET_MS, crashCount = 1),
        )
        // 无账不清（零写省一次 prefs 落盘）
        assertTrue(
            !GlEnginePolicy.shouldResetCrashCount(GlEnginePolicy.HEALTHY_RESET_MS, crashCount = 0),
        )
        // 门槛前不清（真实崩溃会话活不到门——保守口径不破）
        assertTrue(
            !GlEnginePolicy.shouldResetCrashCount(GlEnginePolicy.HEALTHY_RESET_MS - 1, crashCount = 3),
        )
    }

    @Test
    fun `崩溃提示线口径（≥阈值=诊断行升级反复崩溃提示——引擎选择不受账本影响）`() {
        // 账本不参与引擎选择（旧「自动回退 Canvas」闸退役）——账本只在诊断行升级提示
        assertEquals(2, GlEnginePolicy.CRASH_FALLBACK_THRESHOLD)
        // 高账本下解析结果不变（GL 恒 GL——失败可见+重试可修，不静默换引擎）
        assertEquals(GlEnginePolicy.Engine.OPENGL, GlEnginePolicy.resolve(es3Available = true).engine)
    }
}
