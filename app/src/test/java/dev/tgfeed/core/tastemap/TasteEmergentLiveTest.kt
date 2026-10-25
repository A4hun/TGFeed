package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * R303 在线活层单测（design-multiscale-emergent §6.5 条款二 + §6.4 机制一锚定）：
 *  - **刚体旋转保形**：两两距离数学恒等式保持（涌现的语义结构永不被差速剪切拧碎
 *    ——这是「不坍缩」机制一的角动量落法：旋转支持对抗向心收缩且零语义磨损）；
 *  - 质心锚：绕质心旋转质心不动；
 *  - **锚弹簧微扰有界**：全时间轴 |Δ|≤振幅上限（保形边界由构造保证）；确定性
 *    （同 chatId 同呼吸）；周期回归。
 */
class TasteEmergentLiveTest {

    private val E = TasteEmergentLive

    private fun synthCloud(n: Int): Triple<FloatArray, FloatArray, FloatArray> {
        var seed = 0x303L
        fun rnd(): Float {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 33) and 0xFFFFFFL).toFloat() / 16777216f
        }
        val x = FloatArray(n) { (rnd() - 0.5f) * 9000f }
        val y = FloatArray(n) { (rnd() - 0.5f) * 9000f }
        val z = FloatArray(n) { (rnd() - 0.5f) * 9000f }
        return Triple(x, y, z)
    }

    @Test
    fun `刚体旋转-两两距离恒等式保持`() {
        val n = 100
        val (x0, y0, z0) = synthCloud(n)
        val x1 = x0.copyOf()
        val y1 = y0.copyOf()
        val z1 = z0.copyOf()
        E.rigidRotateY(n, x1, y1, z1, 0f, 0f, E.OMEGA_DEG_PER_MIN, 37f)
        val dev = E.maxPairDistDeviation(n, x0, y0, z0, x1, y1, z1, samples = 4000)
        assertTrue("刚体旋转两两距离最大偏差 $dev 应 ≤0.05（浮点噪声级）", dev <= 0.05f)
        // 云确实转了（不是恒等变换摸鱼）
        var moved = 0f
        for (i in 0 until n) {
            val dx = x1[i] - x0[i]
            val dz = z1[i] - z0[i]
            moved += sqrt(dx * dx + dz * dz)
        }
        assertTrue("37 分钟 0.5°/分 云应已转动（位移和=${moved}）", moved > 100f)
    }

    @Test
    fun `刚体旋转-质心锚不动`() {
        val n = 60
        val (x, y, z) = synthCloud(n)
        var cx = 0f
        var cz = 0f
        for (i in 0 until n) {
            cx += x[i]
            cz += z[i]
        }
        cx /= n
        cz /= n
        E.rigidRotateY(n, x, y, z, cx, cz, E.OMEGA_DEG_PER_MIN, 120f)
        var cx1 = 0f
        var cz1 = 0f
        for (i in 0 until n) {
            cx1 += x[i]
            cz1 += z[i]
        }
        cx1 /= n
        cz1 /= n
        assertTrue("绕质心旋转质心不动：Δ=${abs(cx1 - cx)}+${abs(cz1 - cz)}", abs(cx1 - cx) < 0.5f && abs(cz1 - cz) < 0.5f)
    }

    @Test
    fun `微扰-全时间轴有界且确定`() {
        val out = FloatArray(3)
        var worst = 0f
        var t = 0f
        while (t < 7200f) {
            for (id in 1L..50L) {
                E.anchorPerturb(id, t, out)
                val m = sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2])
                if (m > worst) worst = m
                // 确定性：同时刻同 chatId 逐位一致
                val out2 = FloatArray(3)
                E.anchorPerturb(id, t, out2)
                assertTrue(out.contentEquals(out2))
            }
            t += 137f
        }
        assertTrue("微扰模峰 $worst 应 ≤ 上限 ${E.perturbBound()}", worst <= E.perturbBound() * 1.0001f)
    }

    @Test
    fun `微扰-周期回归`() {
        val a = FloatArray(3)
        val b = FloatArray(3)
        E.anchorPerturb(99L, 123f, a)
        E.anchorPerturb(99L, 123f + E.PERTURB_PERIOD_SEC, b)
        for (k in 0..2) assertEquals(a[k], b[k], 1e-3f)
    }
}
