package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * R321 拉格朗日点单测（r315 I⑤ 锚表+任务书单测锚全套）：L4/L5 等边三角形坐标
 * 确定性（|CA|=|CB|=|AB| 几何恒等式）/退化轴安全/共源判定（forward 交非空）/粒数帽
 * 9/亮度归一域/粒子确定性。
 */
class TrojanFxTest {

    // ---------------- L4/L5 等边三角形几何（确定性坐标） ----------------

    @Test
    fun `L4L5-等边三角形几何恒等式`() {
        val out = FloatArray(9)
        // 任意双星位（含负象限/大间距）
        val cases = arrayOf(
            floatArrayOf(100f, 200f, 300f, 400f, 100f, 50f),
            floatArrayOf(-500f, 300f, -200f, 100f, 400f, 800f),
            floatArrayOf(0f, 0f, 0f, 300f, 0f, 0f),
            floatArrayOf(12.5f, -7.25f, 3.75f, -88f, 41f, 96f),
        )
        for ((i, c) in cases.withIndex()) {
            val ax = c[0]; val ay = c[1]; val az = c[2]
            val bx = c[3]; val by = c[4]; val bz = c[5]
            for (sign in floatArrayOf(1f, -1f)) {
                TrojanFx.lPointOf(ax, ay, az, bx, by, bz, sign, out)
                val ab = dist(ax, ay, az, bx, by, bz)
                val ca = dist(out[0], out[1], out[2], ax, ay, az)
                val cb = dist(out[0], out[1], out[2], bx, by, bz)
                // 等边三角形：第三顶点到两端距离 = |AB|（60° 处 L4/L5 几何字面）
                assertEquals("案例$i sign=$sign |CA|=|AB|", ab, ca, 0.001f)
                assertEquals("案例$i sign=$sign |CB|=|AB|", ab, cb, 0.001f)
                // 两顶点（L4/L5）关于 AB 中点对称
                val out2 = FloatArray(9)
                TrojanFx.lPointOf(ax, ay, az, bx, by, bz, -sign, out2)
                assertEquals((out[0] + out2[0]) / 2f, (ax + bx) / 2f, 0.001f)
                assertEquals((out[1] + out2[1]) / 2f, (ay + by) / 2f, 0.001f)
                assertEquals((out[2] + out2[2]) / 2f, (az + bz) / 2f, 0.001f)
                // 抖动轴：u ⊥ AB ∧ v=AB 单位向（粒子散布面内基）
                val vLen = dist(0f, 0f, 0f, out[6], out[7], out[8])
                assertEquals(1f, vLen, 0.001f)
                val uLen = dist(0f, 0f, 0f, out[3], out[4], out[5])
                assertEquals(1f, uLen, 0.001f)
                val dotUAb = out[3] * (bx - ax) + out[4] * (by - ay) + out[5] * (bz - az)
                assertTrue("u ⊥ AB（案例$i）", abs(dotUAb) < 0.01f * ab)
            }
        }
    }

    @Test
    fun `L4L5-确定性与退化安全`() {
        val out = FloatArray(9)
        val out2 = FloatArray(9)
        // 同输入两跑逐位一致（帧推导刚性跟随的确定性锚）
        TrojanFx.lPointOf(10f, 20f, 30f, 200f, 60f, -90f, 1f, out)
        TrojanFx.lPointOf(10f, 20f, 30f, 200f, 60f, -90f, 1f, out2)
        for (k in 0 until 9) assertEquals(out[k], out2[k], 0.0001f)
        // 退化：AB∥up（竖直对）——垂线回落 z 轴（无 NaN 几何安全）
        TrojanFx.lPointOf(0f, 0f, 0f, 0f, 500f, 0f, 1f, out)
        assertTrue(!out[0].isNaN() && !out[1].isNaN() && !out[2].isNaN())
        assertEquals(0f, out[3], 0.0001f)
        assertEquals(0f, out[4], 0.0001f)
        assertEquals(1f, out[5], 0.0001f)
        val ab = dist(0f, 0f, 0f, 0f, 500f, 0f)
        assertEquals(ab, dist(out[0], out[1], out[2], 0f, 0f, 0f), 0.001f) // 等边仍成立
        // 极退化：AB 重合（|AB|<1e-4）——返 A 位安全
        TrojanFx.lPointOf(50f, 50f, 50f, 50f, 50f, 50f, 1f, out)
        assertEquals(50f, out[0], 0.0001f)
        assertEquals(50f, out[1], 0.0001f)
    }

    // ---------------- 共源判定（forward 上游交集——诚实红线） ----------------

    @Test
    fun `共源判定-交集语义与无共源零尘埃`() {
        // 无账/单边空=零（不冒充驿站）
        assertEquals(0, TrojanFx.sharedUpstreamWeight(emptyMap(), emptyMap()))
        assertEquals(0, TrojanFx.sharedUpstreamWeight(mapOf(-100L to 3), emptyMap()))
        assertEquals(0, TrojanFx.sharedUpstreamWeight(emptyMap(), mapOf(-100L to 3)))
        // 无交集=零
        assertEquals(
            0,
            TrojanFx.sharedUpstreamWeight(mapOf(-100L to 3), mapOf(-200L to 5)),
        )
        // 交集 X：Σ min(countA, countB)（两侧都搬运过的量=交集最诚实口径）。
        // 案一交集={-100}：min(3,5)=3；案例二交集={-100,-300}：min(4,4)+min(2,2)=6
        assertEquals(
            3,
            TrojanFx.sharedUpstreamWeight(
                mapOf(-100L to 3, -300L to 7),
                mapOf(-100L to 5, -400L to 9),
            ),
        )
        assertEquals(
            6,
            TrojanFx.sharedUpstreamWeight(
                mapOf(-100L to 4, -300L to 2),
                mapOf(-100L to 4, -300L to 2, -500L to 99),
            ),
        )
    }

    // ---------------- 簇粒数帽与亮度归一 ----------------

    @Test
    fun `粒数帽-共源量单调与帽9域外零`() {
        // 无共源=零渲染（域外零成本——诚实红线字面）
        assertEquals(0, TrojanFx.particleCountOf(0))
        assertEquals(0, TrojanFx.particleCountOf(-1))
        // 域：3~9（帽 9——r315-I⑤ 字面；W=1 低档≈4 粒、饱和档 9 粒）
        assertEquals(4, TrojanFx.particleCountOf(1))
        assertEquals(TrojanFx.CLUSTER_MAX, TrojanFx.particleCountOf(999))
        assertTrue(TrojanFx.particleCountOf(2) >= TrojanFx.CLUSTER_MIN)
        // 单调：共源量越大粒越多
        var prev = 0
        for (w in intArrayOf(1, 2, 3, 5, 8, 12, 20, 40)) {
            val n = TrojanFx.particleCountOf(w)
            assertTrue("粒数随共源量单调不减（w=$w n=$n）", n >= prev)
            assertTrue(n in TrojanFx.CLUSTER_MIN..TrojanFx.CLUSTER_MAX)
            prev = n
        }
    }

    @Test
    fun `亮度归一-域与单调`() {
        assertEquals(0f, TrojanFx.brightness01Of(0), 0.0001f)
        assertEquals(0f, TrojanFx.brightness01Of(-3), 0.0001f)
        assertEquals(1f, TrojanFx.brightness01Of(TrojanFx.W_SATURATION), 0.0001f)
        assertEquals(1f, TrojanFx.brightness01Of(999), 0.0001f) // 饱和钳域
        assertTrue(TrojanFx.brightness01Of(3) < TrojanFx.brightness01Of(6))
        assertTrue(TrojanFx.brightness01Of(3) in 0f..1f)
        assertTrue(TrojanFx.brightness01Of(6) in 0f..1f)
    }

    // ---------------- 粒子抖动（双端同式确定性） ----------------

    @Test
    fun `粒子抖动-散布域与确定性`() {
        val out = FloatArray(3)
        val out2 = FloatArray(3)
        val sep = 400f
        for (k in 0 until TrojanFx.CLUSTER_MAX) {
            TrojanFx.particleOf(
                100f, 200f, 300f,
                0f, 1f, 0f,
                1f, 0f, 0f,
                sep, 77, k, out,
            )
            TrojanFx.particleOf(
                100f, 200f, 300f,
                0f, 1f, 0f,
                1f, 0f, 0f,
                sep, 77, k, out2,
            )
            assertEquals("同种子同粒序确定性（k=$k）", out[0], out2[0], 0.0001f)
            assertEquals(out[1], out2[1], 0.0001f)
            assertEquals(out[2], out2[2], 0.0001f)
            // 散布域：|粒子−L| ≤ √2×SPREAD×sep（抖动两轴各 ±SPREAD×sep）
            val off = dist(out[0], out[1], out[2], 100f, 200f, 300f)
            assertTrue(
                "散布域（k=$k off=$off）",
                off <= 1.415f * TrojanFx.CLUSTER_SPREAD * sep + 0.001f,
            )
        }
    }

    private fun dist(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val dz = bz - az
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }
}
