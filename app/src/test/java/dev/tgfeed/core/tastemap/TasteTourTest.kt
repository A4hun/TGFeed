package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * R271 开局引导运镜政策件锚定：频控矩阵 / 选星（亮星已知优先、点亮目标未知最近）/
 * 绕转轨道数学（基正交、θ=0 起点无跳变、半圈对跖、半径收口、缓动端点）。
 */
class TasteTourTest {

    // ==================== 频控 ====================

    @Test
    fun `首播无条件放行`() {
        assertTrue(TasteTour.shouldAutoPlay(0, 0, 577))
        assertTrue(TasteTour.shouldAutoPlay(0, 0, 8))
    }

    @Test
    fun `第二播需星数大幅增长`() {
        assertFalse(TasteTour.shouldAutoPlay(1, 577, 577)) // 同数不播
        assertFalse(TasteTour.shouldAutoPlay(1, 577, 860)) // +49% 未到 ×1.5
        assertTrue(TasteTour.shouldAutoPlay(1, 577, 866)) // 866 ≥ 577×1.5=865.5
        assertFalse(TasteTour.shouldAutoPlay(1, 0, 0)) // 零星不播
    }

    @Test
    fun `计数到顶永不自动播`() {
        assertFalse(TasteTour.shouldAutoPlay(2, 100, 99999))
        assertFalse(TasteTour.shouldAutoPlay(5, 0, 99999))
    }

    @Test
    fun `基线脏数据宽容口径`() {
        // count=1 但基线 0（旧版只记过计数）——有星即视为增长放行
        assertTrue(TasteTour.shouldAutoPlay(1, 0, 10))
    }

    // ==================== 选星 ====================

    private fun star(id: Long, vol: Int, x: Float, y: Float, z: Float, known: Boolean) =
        TasteTour.TourStar(id, vol, x, y, z, known)

    @Test
    fun `亮星已知优先于大体量未知`() {
        val stars = listOf(
            star(1L, 9999, 100f, 0f, 0f, known = false), // 最大但未知
            star(2L, 100, 0f, 100f, 0f, known = true),
        )
        assertEquals(2L, TasteTour.pickBright(stars)?.id)
    }

    @Test
    fun `亮星全未知回退体量最大`() {
        val stars = listOf(
            star(1L, 10, 0f, 0f, 0f, known = false),
            star(2L, 500, 0f, 0f, 0f, known = false),
        )
        assertEquals(2L, TasteTour.pickBright(stars)?.id)
        assertNull(TasteTour.pickBright(emptyList()))
    }

    @Test
    fun `点亮目标取离亮星最近的未知星`() {
        val bright = star(1L, 900, 0f, 0f, 0f, known = true)
        val near = star(2L, 50, 100f, 0f, 0f, known = false)
        val far = star(3L, 50, 900f, 0f, 0f, known = false)
        val knownNear = star(4L, 50, 50f, 0f, 0f, known = true) // 已知不参与
        assertEquals(2L, TasteTour.pickLightTarget(listOf(bright, near, far, knownNear), 1L)?.id)
    }

    @Test
    fun `点亮目标有体量优先且排除亮星自身`() {
        val bright = star(1L, 900, 0f, 0f, 0f, known = false) // 亮星自身未知也不许当目标
        val noVol = star(2L, 0, 10f, 0f, 0f, known = false) // 更近但无体量
        val withVol = star(3L, 5, 800f, 0f, 0f, known = false)
        assertEquals(3L, TasteTour.pickLightTarget(listOf(bright, noVol, withVol), 1L)?.id)
    }

    @Test
    fun `点亮目标全已知或独星时为空`() {
        val bright = star(1L, 900, 0f, 0f, 0f, known = true)
        assertNull(TasteTour.pickLightTarget(listOf(bright, star(2L, 5, 1f, 0f, 0f, true)), 1L))
        assertNull(TasteTour.pickLightTarget(listOf(bright), 1L))
        assertNull(TasteTour.pickLightTarget(listOf(bright), 999L)) // 亮星不在表
    }

    // ==================== 绕转轨道数学 ====================

    @Test
    fun `轨道基 u 指向相机且三轴正交`() {
        val out = FloatArray(6)
        assertTrue(TasteTour.orbitBasis(300f, 0f, 400f, 0f, 0f, 0f, out))
        // u = normalize(相机−星心)
        val len = sqrt(300f * 300f + 400f * 400f)
        assertEquals(300f / len, out[0], 1e-4f)
        assertEquals(0f, out[1], 1e-4f)
        assertEquals(400f / len, out[2], 1e-4f)
        // v 单位化且 ⊥ u
        val vl = sqrt(out[3] * out[3] + out[4] * out[4] + out[5] * out[5])
        assertEquals(1f, vl, 1e-4f)
        val dot = out[0] * out[3] + out[1] * out[4] + out[2] * out[5]
        assertEquals(0f, dot, 1e-4f)
    }

    @Test
    fun `轨道基竖直视线的回退轴可用`() {
        val out = FloatArray(6)
        assertTrue(TasteTour.orbitBasis(0f, 500f, 0f, 0f, 0f, 0f, out)) // u≈+y
        val vl = sqrt(out[3] * out[3] + out[4] * out[4] + out[5] * out[5])
        assertEquals(1f, vl, 1e-4f)
        // 退化：相机贴星心不可绕
        assertFalse(TasteTour.orbitBasis(0f, 0f, 0f, 0f, 0f, 0f, out))
    }

    @Test
    fun `轨道点起点无跳变终点对跖且半径守恒`() {
        val basis = FloatArray(6)
        assertTrue(TasteTour.orbitBasis(300f, 0f, 400f, 10f, 20f, 30f, basis))
        val out = FloatArray(3)
        val r = 250f
        // θ=0：星心 + r·u（推近到位点同方向——无径向跳变）
        TasteTour.orbitPos(10f, 20f, 30f, basis[0], basis[1], basis[2], basis[3], basis[4], basis[5], r, 0f, out)
        assertEquals(10f + r * basis[0], out[0], 1e-3f)
        assertEquals(20f + r * basis[1], out[1], 1e-3f)
        assertEquals(30f + r * basis[2], out[2], 1e-3f)
        // θ=π：对跖点（半圈绕到星另一侧）
        TasteTour.orbitPos(10f, 20f, 30f, basis[0], basis[1], basis[2], basis[3], basis[4], basis[5], r, PI.toFloat(), out)
        assertEquals(10f - r * basis[0], out[0], 1e-3f)
        assertEquals(20f - r * basis[1], out[1], 1e-3f)
        assertEquals(30f - r * basis[2], out[2], 1e-3f)
        // 全程半径守恒（取 16 采样点）
        for (k in 0..16) {
            val th = (PI * k / 16).toFloat()
            TasteTour.orbitPos(10f, 20f, 30f, basis[0], basis[1], basis[2], basis[3], basis[4], basis[5], r, th, out)
            val dx = out[0] - 10f
            val dy = out[1] - 20f
            val dz = out[2] - 30f
            assertEquals(r, sqrt(dx * dx + dy * dy + dz * dz), 1e-2f)
        }
    }

    @Test
    fun `绕转角半圈缓入缓出`() {
        assertEquals(0f, TasteTour.orbitTheta(0f), 1e-6f)
        assertEquals(PI.toFloat(), TasteTour.orbitTheta(1f), 1e-4f)
        assertEquals(PI.toFloat() / 2f, TasteTour.orbitTheta(0.5f), 1e-4f)
        // 单调不减 + 缓入（前 10% 行程角位移小于线性的 10%）
        var prev = -1f
        for (k in 0..20) {
            val th = TasteTour.orbitTheta(k / 20f)
            assertTrue(th >= prev)
            prev = th
        }
        assertTrue(TasteTour.orbitTheta(0.1f) < PI.toFloat() * 0.1f)
    }

    @Test
    fun `绕转半径窗口内收口无跳变`() {
        assertEquals(550f, TasteTour.orbitRadiusAt(550f, 250f, 0f), 1e-4f)
        assertEquals(250f, TasteTour.orbitRadiusAt(550f, 250f, 1f), 1e-4f)
        // 窗口（25%）之后恒为终半径
        assertEquals(250f, TasteTour.orbitRadiusAt(550f, 250f, 0.3f), 1e-4f)
        // 窗口中点 = 均值（smoothstep 中点对称）
        assertEquals(400f, TasteTour.orbitRadiusAt(550f, 250f, 0.125f), 1e-2f)
        // 单调收敛
        var prev = Float.MAX_VALUE
        for (k in 0..20) {
            val r = TasteTour.orbitRadiusAt(550f, 250f, k / 20f)
            assertTrue(r <= prev + 1e-4f)
            prev = r
        }
    }

    @Test
    fun `推近停距远星中距落点近星真推近`() {
        val r = 248f
        // 远星（1500 > 2.2R=546）：停 2.2R
        assertEquals(546f, TasteTour.pushStop(r, 1500f), 0.5f)
        // 近星（400 < 546）：停 max(1.3R=322, 400×0.6=240)=322——真平移 400→322
        assertEquals(322f, TasteTour.pushStop(r, 400f), 0.5f)
        // 中距（700）：min(546, max(322, 420))=420
        assertEquals(420f, TasteTour.pushStop(r, 700f), 0.5f)
        // 贴脸（100 < 1.3R）：停 1.3R=322（不越过绕转轨道；approach 只进不退此处零平移=合理兜底）
        assertEquals(322f, TasteTour.pushStop(r, 100f), 0.5f)
        // 不变量：停距恒 ∈ [1.3R, 2.2R]
        for (d in listOf(50f, 200f, 400f, 800f, 3000f)) {
            val s = TasteTour.pushStop(r, d)
            assertTrue(s >= r * TasteTour.PUSH_MIN_FACTOR - 1e-3f)
            assertTrue(s <= r * TasteTour.PUSH_STOP_FACTOR + 1e-3f)
        }
    }

    @Test
    fun `段名全齐非空`() {
        for (leg in listOf(TasteTour.LEG_PUSH, TasteTour.LEG_ORBIT, TasteTour.LEG_LIGHT, TasteTour.LEG_DWELL, TasteTour.LEG_EASE)) {
            assertNotNull(TasteTour.legName(leg))
            assertTrue(TasteTour.legName(leg).isNotBlank())
        }
        assertEquals("?", TasteTour.legName(99))
        // 扫角语义锚：半圈=π
        assertEquals(PI.toFloat(), TasteTour.ORBIT_SWEEP_RAD, 1e-6f)
        assertTrue(abs(TasteTour.ORBIT_SWEEP_RAD - PI.toFloat()) < 1e-6f)
    }
}
