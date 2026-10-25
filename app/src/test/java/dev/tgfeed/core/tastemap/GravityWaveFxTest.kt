package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * R320 引力波涟漪单测（r315 E⑤ 锚表）：波前半径单调/高斯剖面/时间衰减/位移帽
 * 6px/域外星零成本早退/确定性/TTL 档位映射/导演器档位参数位（R327 对接口）。
 */
class GravityWaveFxTest {

    private val out = FloatArray(2)

    // ---------------- 波前传播（位移场） ----------------

    @Test
    fun `波前半径-随龄单调不减且钳寿命`() {
        // r(t)=c_wave×t 单调；寿命外钳死（防陈波发散）
        assertEquals(0f, GravityWaveFx.waveRadiusPx(0f, 3f), 0.01f)
        assertEquals(420f, GravityWaveFx.waveRadiusPx(1f, 3f), 0.01f)
        assertEquals(840f, GravityWaveFx.waveRadiusPx(2f, 3f), 0.01f)
        assertTrue(GravityWaveFx.waveRadiusPx(1.5f, 3f) > GravityWaveFx.waveRadiusPx(1.4f, 3f))
        // 寿命钳：3s 寿命的波 10s 龄半径=寿命时刻半径（账本侧回收+域钳双保险）
        assertEquals(
            GravityWaveFx.waveRadiusPx(3f, 3f),
            GravityWaveFx.waveRadiusPx(10f, 3f),
            0.01f,
        )
    }

    @Test
    fun `高斯剖面-峰在前沿且对称衰减`() {
        // 波心 (100,100)，波前 r=200：屏距=200 处位移最大；两侧对称衰减
        val amp = 6f
        val age = 0f // 龄 0=时间衰减满档——纯剖面读数
        val r = 200f
        var peak = 0f
        for (d in intArrayOf(140, 170, 185, 200, 215, 230, 260)) {
            val px = 100f + d
            val ok = GravityWaveFx.displaceAt(px, 100f, 100f, 100f, r, age, amp, out)
            val mag = if (ok) hypot(out[0], out[1]) else 0f
            if (d == 200) peak = mag
            if (d != 200 && d in intArrayOf(185, 215)) {
                assertTrue("前沿 ±15px 仍有位移（d=$d）", ok)
            }
            if (d == 140 || d == 260) {
                assertFalse("±60px 域外早退（d=$d）", ok)
            }
            if (d in intArrayOf(170, 230)) {
                assertTrue("±30px 带内（d=$d）", ok)
            }
            // 对称：170 与 230 幅值同档（剖面以 r 为心对称）
            if (d == 170) assertTrue(ok)
        }
        assertTrue("前沿处位移峰 >0", peak > 3f)
        // 剖面对称锚：±15px 等幅
        val a = FloatArray(2)
        GravityWaveFx.displaceAt(315f, 100f, 100f, 100f, r, age, amp, a)
        val b = FloatArray(2)
        GravityWaveFx.displaceAt(285f, 100f, 100f, 100f, r, age, amp, b)
        assertEquals(hypot(a[0], a[1]), hypot(b[0], b[1]), 0.01f)
    }

    @Test
    fun `时间衰减-龄大位移弱且终归零`() {
        // e^(-age/tau)：龄 2.4s（2τ）≈ 13.5% 残幅；龄 5τ ≈ 0.7%（<0.05px 门=零输出）
        val r = 200f
        var young = 0f
        var old = 0f
        if (GravityWaveFx.displaceAt(300f, 100f, 100f, 100f, r, 0.1f, 6f, out)) young = hypot(out[0], out[1])
        if (GravityWaveFx.displaceAt(300f, 100f, 100f, 100f, r, 2.4f, 6f, out)) old = hypot(out[0], out[1])
        assertTrue("龄 2.4s 衰减后仍有残余（衰减非瞬断）", old > 0f)
        assertTrue("龄 2.4s 位移 < 龄 0.1s", old < young)
        // 5τ 外整体熄灭（一次性脉冲非持续位移）
        val dead = GravityWaveFx.displaceAt(300f, 100f, 100f, 100f, r, 6f, 6f, out)
        assertFalse("5τ 龄=波已消散", dead)
    }

    @Test
    fun `位移帽-6px 红线任意幅度不越界`() {
        // 传入超帽幅度（防御性输入）→ 终钳 6px
        val ok = GravityWaveFx.displaceAt(300f, 100f, 100f, 100f, 200f, 0f, 600f, out)
        assertTrue(ok)
        val mag = hypot(out[0], out[1])
        assertTrue("位移帽 A≤6px（实=$mag）", mag <= GravityWaveFx.AMP_PX_MAX + 0.001f)
    }

    @Test
    fun `位移方向-离波心径向外推`() {
        // 星在波心右侧 → 位移向右（波前推着星向外让位）
        val ok = GravityWaveFx.displaceAt(300f, 100f, 100f, 100f, 200f, 0f, 4f, out)
        assertTrue(ok)
        assertTrue("径向外推（x 正向）", out[0] > 0f)
        assertEquals(0f, out[1], 0.01f) // 同 y 高度 → 纵向分量零
    }

    @Test
    fun `域外早退-远带与未扫带零位移`() {
        // 波前 r=200：屏距 500（未扫到）与 20（早已扫过+剖面零）都零输出
        assertFalse(GravityWaveFx.displaceAt(600f, 100f, 100f, 100f, 200f, 0f, 4f, out))
        assertEquals(0f, out[0], 0.001f)
        assertEquals(0f, out[1], 0.001f)
        assertFalse(GravityWaveFx.displaceAt(110f, 100f, 100f, 100f, 200f, 0f, 4f, out))
        // 波心星自身（屏距≈0）方向退化=零输出（原地震荡归爆闪层）
        assertFalse(GravityWaveFx.displaceAt(100.0001f, 100f, 100f, 100f, 200f, 0f, 4f, out))
        // 零幅度/零半径防御
        assertFalse(GravityWaveFx.displaceAt(300f, 100f, 100f, 100f, 200f, 0f, 0f, out))
        assertFalse(GravityWaveFx.displaceAt(300f, 100f, 100f, 100f, 0f, 0f, 4f, out))
    }

    @Test
    fun `确定性-同参同位移逐位`() {
        val a = FloatArray(2)
        val b = FloatArray(2)
        for (d in floatArrayOf(150f, 185f, 200f, 215f, 260f)) {
            val okA = GravityWaveFx.displaceAt(100f + d, 100f, 100f, 100f, 200f, 1.3f, 5f, a)
            val okB = GravityWaveFx.displaceAt(100f + d, 100f, 100f, 100f, 200f, 1.3f, 5f, b)
            assertEquals(okA, okB)
            assertEquals(a[0], b[0], 0f)
            assertEquals(a[1], b[1], 0f)
        }
    }

    @Test
    fun `波心出屏判-带宽余量边界`() {
        // 800×600 屏：波心在 ±45px 余量内仍可用，超余量判出屏
        assertTrue(GravityWaveFx.originOnScreen(0f, 0f, 800f, 600f))
        assertTrue(GravityWaveFx.originOnScreen(-44f, 300f, 800f, 600f))
        assertFalse(GravityWaveFx.originOnScreen(-46f, 300f, 800f, 600f))
        assertFalse(GravityWaveFx.originOnScreen(851f, 300f, 800f, 600f))
        assertFalse(GravityWaveFx.originOnScreen(400f, -50f, 800f, 600f))
    }

    // ---------------- TTL 档位映射（flashStar 演出级闪光门） ----------------

    @Test
    fun `TTL门-演出级闪光独占`() {
        // 彗星爆闪 2.5/舷窗欢迎 3.5/NOVA 复燃 4.5 恒不入门；超新星 7.0/宣判 9.0 入
        assertFalse(GravityWaveFx.isPerformFlashTtl(2.5f))
        assertFalse(GravityWaveFx.isPerformFlashTtl(3.5f))
        assertFalse(GravityWaveFx.isPerformFlashTtl(4.5f))
        assertTrue(GravityWaveFx.isPerformFlashTtl(GravityWaveFx.RIPPLE_FLASH_TTL_SEC))
        assertTrue(GravityWaveFx.isPerformFlashTtl(GravityWaveFx.VERDICT_FLASH_TTL_SEC))
        assertTrue(GravityWaveFx.isPerformFlashTtl(GravityWaveFx.FLASH_GATE_SEC))
        assertFalse(GravityWaveFx.isPerformFlashTtl(GravityWaveFx.FLASH_GATE_SEC - 0.01f))
    }

    @Test
    fun `TTL档-涟漪4px与宣判6px_1_5倍物理诚实`() {
        assertEquals(0f, GravityWaveFx.ampPxOfFlashTtl(4.5f), 0.001f) // NOVA 不起波
        assertEquals(GravityWaveFx.RIPPLE_AMP_PX, GravityWaveFx.ampPxOfFlashTtl(7.0f), 0.001f)
        assertEquals(GravityWaveFx.VERDICT_AMP_PX, GravityWaveFx.ampPxOfFlashTtl(9.0f), 0.001f)
        // 宣判=涟漪 ×1.5（质量更大的事件=更强的波）；且恰为帽值（A≤6 红线同源）
        assertEquals(1.5f, GravityWaveFx.VERDICT_AMP_PX / GravityWaveFx.RIPPLE_AMP_PX, 0.001f)
        assertEquals(GravityWaveFx.AMP_PX_MAX, GravityWaveFx.VERDICT_AMP_PX, 0.001f)
        // 寿命域：宣判 3.8s / 涟漪 3.0s（2~4s 掠过全屏量级）
        assertEquals(3.8f, GravityWaveFx.lifeSecOfFlashTtl(9.0f), 0.001f)
        assertEquals(3.0f, GravityWaveFx.lifeSecOfFlashTtl(7.0f), 0.001f)
    }

    // ---------------- 导演器档位参数位（R327 跃迁 diff 对接口） ----------------

    @Test
    fun `导演器档位-强度单调且帽域`() {
        // rippleAmpPxOf/verdictAmpPxOf 随 intensity01 单调不减；域 [2.2,6]；verdict ≥ ripple
        val lo = GravityWaveFx.rippleAmpPxOf(0f)
        val hi = GravityWaveFx.rippleAmpPxOf(1f)
        assertTrue(hi > lo)
        assertEquals(4f, hi, 0.001f)
        assertEquals(2.2f, lo, 0.001f)
        val vLo = GravityWaveFx.verdictAmpPxOf(0f)
        val vHi = GravityWaveFx.verdictAmpPxOf(1f)
        assertTrue(vHi > vLo)
        assertEquals(GravityWaveFx.AMP_PX_MAX, vHi, 0.001f)
        assertTrue(vHi >= hi)
        assertTrue(GravityWaveFx.verdictAmpPxOf(1f) <= GravityWaveFx.AMP_PX_MAX + 0.001f)
        // 钳域：超界强度入参防御
        assertEquals(GravityWaveFx.rippleAmpPxOf(1f), GravityWaveFx.rippleAmpPxOf(5f), 0.001f)
        // 波前寿命：2.6+1.4×i 单调钳 [2.6,4.0]
        assertEquals(2.6f, GravityWaveFx.waveLifeSecOf(0f), 0.001f)
        assertEquals(4.0f, GravityWaveFx.waveLifeSecOf(1f), 0.001f)
        assertEquals(4.0f, GravityWaveFx.waveLifeSecOf(9f), 0.001f)
    }
}
