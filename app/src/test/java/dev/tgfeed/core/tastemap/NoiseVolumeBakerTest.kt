package dev.tgfeed.core.tastemap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * R392 噪声载体革命·烘焙器行为五例（r392_notes §2 烘焙器契约锚）：
 *  - 确定性：两次 bake 逐字节相等（纯函数零 Random 零系统钟）；
 *  - 平铺无缝：整周期平移位级相等（三轴 × k=±1..2）+ octave 偏移复合域周期；
 *  - 分布 parity：fbm3Tiled vs fbm3Reference 同族同统计基因（mean/std/双门比例）；
 *  - 值域：字节回映 [0,1] 且非常数灰场（std 大于 0.1 量级锚）；
 *  - 几何：长度 SIZE³ + bakeMemo 同引用 + 采样点对表。
 */
class NoiseVolumeBakerTest {

    @Test
    fun `确定性-两次烘焙逐字节相等`() {
        val a = NoiseVolumeBaker.bake()
        val b = NoiseVolumeBaker.bake()
        assertArrayEquals("两次 bake 必须逐字节相等（纯函数确定性铁律）", a, b)
    }

    @Test
    fun `平铺无缝-三轴整周期位级相等`() {
        // 采样点取 1/16 网格（fp32 精确可表示、±16k 平移无进位差——位级判等的前提）
        val rng = Random(42)
        val w = NoiseVolumeBaker.WINDOW
        repeat(200) {
            val px = rng.nextInt(0, 256) / 16f
            val py = rng.nextInt(0, 256) / 16f
            val pz = rng.nextInt(0, 256) / 16f
            val base = NoiseVolumeBaker.fbm3Tiled(px, py, pz)
            for (k in -2..2) {
                if (k == 0) continue
                val d = w * k
                assertEquals("x 轴整周期平移 k=${k}", base, NoiseVolumeBaker.fbm3Tiled(px + d, py, pz), 0f)
                assertEquals("y 轴整周期平移 k=${k}", base, NoiseVolumeBaker.fbm3Tiled(px, py + d, pz), 0f)
                assertEquals("z 轴整周期平移 k=${k}", base, NoiseVolumeBaker.fbm3Tiled(px, py, pz + d), 0f)
            }
        }
    }

    @Test
    fun `平铺无缝-octave偏移复合域周期相等`() {
        val w = NoiseVolumeBaker.WINDOW
        // 复合域网格点（含负域与大域，1/16 网格）：三轴同移整周期位级相等
        val rng = Random(7)
        repeat(100) {
            val rx = rng.nextInt(-512, 512) / 16f
            val ry = rng.nextInt(-512, 512) / 16f
            val rz = rng.nextInt(-512, 512) / 16f
            val base = NoiseVolumeBaker.fbm3Tiled(rx, ry, rz)
            for (k in -2..2) {
                if (k == 0) continue
                val d = w * k
                assertEquals(
                    "复合域整周期平移 k=${k}",
                    base, NoiseVolumeBaker.fbm3Tiled(rx + d, ry + d, rz + d), 0f
                )
            }
        }
        // 真 7.3 偏移形落点（p×2+7.3 域——非二进分数，跨周期有 fp32 进位差）：
        // 容差 1e-4 验周期近似（位级精确性由入口裹窗保证在网格点上，此处验数值连续性量级）
        repeat(100) {
            val px = rng.nextFloat() * 8f
            val py = rng.nextFloat() * 8f
            val pz = rng.nextFloat() * 8f
            val rx = px * 2f + 7.3f
            val ry = py * 2f + 7.3f
            val rz = pz * 2f + 7.3f
            val base = NoiseVolumeBaker.fbm3Tiled(rx, ry, rz)
            assertEquals(base, NoiseVolumeBaker.fbm3Tiled(rx + w, ry, rz), 1e-4f)
            assertEquals(base, NoiseVolumeBaker.fbm3Tiled(rx, ry + w, rz), 1e-4f)
            assertEquals(base, NoiseVolumeBaker.fbm3Tiled(rx, ry, rz + w), 1e-4f)
        }
    }

    @Test
    fun `分布parity-均值标准差与双门比例对表`() {
        val n = 20000
        val rng = Random(42)
        var sumT = 0.0
        var sumT2 = 0.0
        var sumR = 0.0
        var sumR2 = 0.0
        var loT = 0
        var loR = 0
        var hiT = 0
        var hiR = 0
        repeat(n) {
            val x = rng.nextFloat() * 12f - 6f
            val y = rng.nextFloat() * 12f - 6f
            val z = rng.nextFloat() * 12f - 6f
            val t = NoiseVolumeBaker.fbm3Tiled(x, y, z).toDouble()
            val r = NoiseVolumeBaker.fbm3Reference(x, y, z).toDouble()
            sumT += t
            sumT2 += t * t
            sumR += r
            sumR2 += r * r
            if (t > 0.40) loT++
            if (r > 0.40) loR++
            if (t > 0.82) hiT++
            if (r > 0.82) hiR++
        }
        val meanT = sumT / n
        val meanR = sumR / n
        val stdT = sqrt(sumT2 / n - meanT * meanT)
        val stdR = sqrt(sumR2 / n - meanR * meanR)
        val pLoT = loT.toDouble() / n
        val pLoR = loR.toDouble() / n
        val pHiT = hiT.toDouble() / n
        val pHiR = hiR.toDouble() / n
        println(
            "R392 parity 实测： meanT=${"%.5f".format(meanT)} meanR=${"%.5f".format(meanR)}" +
                " stdT=${"%.5f".format(stdT)} stdR=${"%.5f".format(stdR)}" +
                " pLoT=${"%.5f".format(pLoT)} pLoR=${"%.5f".format(pLoR)}" +
                " pHiT=${"%.5f".format(pHiT)} pHiR=${"%.5f".format(pHiR)}"
        )
        assertEquals("mean 差应小于 0.01", meanR, meanT, 0.01)
        assertEquals("std 差应小于 0.01", stdR, stdT, 0.01)
        assertEquals("P(x 大于 0.40) 比例差应小于 0.02", pLoR, pLoT, 0.02)
        assertEquals("P(x 大于 0.82) 比例差应小于 0.02", pHiR, pHiT, 0.02)
    }

    @Test
    fun `值域-字节回映01区间且非常数灰场`() {
        val vol = NoiseVolumeBaker.bake()
        var sum = 0.0
        var sum2 = 0.0
        var min = 1.0
        var max = 0.0
        for (b in vol) {
            val v = (b.toInt() and 0xFF) / 255.0
            assertTrue("字节回映必须落在 [0,1]，实测 ${v}", v in 0.0..1.0)
            sum += v
            sum2 += v * v
            if (v < min) min = v
            if (v > max) max = v
        }
        val n = vol.size.toDouble()
        val mean = sum / n
        val std = sqrt(sum2 / n - mean * mean)
        println(
            "R392 值域实测： mean=${"%.5f".format(mean)} std=${"%.5f".format(std)}" +
                " min=${"%.5f".format(min)} max=${"%.5f".format(max)}"
        )
        assertTrue("场 std 应大于 0.1 量级锚（防退化成全灰），实测 ${std}", std > 0.1)
        assertTrue("场不应恒等常数", max > min)
    }

    @Test
    fun `几何-长度memo同引用与格点对表`() {
        val s = NoiseVolumeBaker.SIZE
        val vol = NoiseVolumeBaker.bake()
        assertEquals("长度必须等于 SIZE³", s * s * s, vol.size)
        val m1 = NoiseVolumeBaker.bakeMemo()
        val m2 = NoiseVolumeBaker.bakeMemo()
        assertSame("bakeMemo 重复调用必须返回同一引用", m1, m2)
        // 格点对表：bake 采样点 = 体素中心 q=(i+0.5)/SIZE×WINDOW，值=clamp01×255 截断取整
        val w = NoiseVolumeBaker.WINDOW
        val probes = listOf(
            intArrayOf(0, 0, 0), intArrayOf(1, 2, 3), intArrayOf(63, 63, 63),
            intArrayOf(63, 0, 0), intArrayOf(0, 63, 0), intArrayOf(0, 0, 63),
            intArrayOf(17, 29, 41), intArrayOf(32, 32, 32), intArrayOf(5, 60, 23)
        )
        for (p in probes) {
            val qx = (p[0] + 0.5f) / s * w
            val qy = (p[1] + 0.5f) / s * w
            val qz = (p[2] + 0.5f) / s * w
            val expect = (NoiseVolumeBaker.fbm3Tiled(qx, qy, qz).coerceIn(0f, 1f) * 255f).toInt().toByte()
            assertEquals(
                "格点 (${p[0]},${p[1]},${p[2]}) 对表",
                expect, vol[p[0] + s * (p[1] + s * p[2])]
            )
        }
    }

    @Test
    fun `耗时-烘焙一次性预算打点`() {
        NoiseVolumeBaker.bake() // 暖机（JIT 稳定后计时才反映稳态一次性税）
        val t0 = System.nanoTime()
        NoiseVolumeBaker.bake()
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("R392 噪声体 64³ 烘焙耗时： ${ms}ms（预期 100~300ms 一次性）")
    }
}
