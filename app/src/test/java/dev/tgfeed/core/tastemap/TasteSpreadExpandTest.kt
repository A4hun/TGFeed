package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * R237 聚类自适应展开单测（用户真机实锤③「大部分星系聚拢在一起」的修法锚）：
 * 间距缺口→F 边界与单调 / 浓度联动 k 边界（紧核 k 小、舒展 k=1——映射方向锚，
 * 防反转回归）/ 紧核展开中位最近邻间距显著增大 / 舒展布局零扰动 / 径向保序
 * （簇结构不变）/ 离群星与质心星原地 / 确定性 / hasComp 门。
 */
class TasteSpreadExpandTest {

    // ---- expandFactor：间距缺口 → 目标放大倍数 ----

    @Test
    fun `expandFactor-边界与单调`() {
        assertEquals(1f, TasteSpreadExpand.expandFactor(2.2f), 0f)      // 舒展=不触发
        assertEquals(1f, TasteSpreadExpand.expandFactor(5f), 0f)
        assertEquals(TasteSpreadExpand.F_MAX, TasteSpreadExpand.expandFactor(0.8f), 0f) // 极紧=拉满
        assertEquals(TasteSpreadExpand.F_MAX, TasteSpreadExpand.expandFactor(0.2f), 0f) // 钳上限
        // 方向锚（R237 单测抓过反转 bug：间距越小 F 必须越大）
        val fTight = TasteSpreadExpand.expandFactor(0.9f)
        val fMid = TasteSpreadExpand.expandFactor(1.5f)
        val fLoose = TasteSpreadExpand.expandFactor(2.1f)
        assertTrue("F 随间距单调不增", fTight > fMid && fMid > fLoose)
        assertTrue(fTight <= TasteSpreadExpand.F_MAX && fLoose >= 1f)
    }

    // ---- radialK：浓度联动幂指数 ----

    @Test
    fun `radialK-锚定不变量中位星放大F倍`() {
        // 数学锚：k=1+lnF/ln(medR/maxR) → 中位半径星展开后恰 ≈F 倍（k 的排序
        // 无意义——紧核同 F 只需更温和的 k，径向映射对小 r 增益更深）
        for (f in floatArrayOf(1.3f, 2.0f, 2.6f)) {
            for ((medR, maxR) in listOf(15f to 100f, 50f to 100f, 170f to 300f)) {
                val k = TasteSpreadExpand.radialK(f, medR, maxR)
                if (k <= TasteSpreadExpand.K_MIN + 1e-4f) continue // 钳位档（均匀盘诚实部分修）
                val newR = maxR * (medR / maxR).pow(k)
                assertEquals("中位星应放大≈$f 倍（medR=$medR）", medR * f, newR, 0.5f)
            }
        }
        // 方向锚：钳位下界存在（巨大 F 的均匀盘）
        assertEquals(TasteSpreadExpand.K_MIN, TasteSpreadExpand.radialK(9f, 90f, 100f), 0.001f)
        // 紧核的 k 比中核温和（同 F——小半径映射增益更深，无需激进 k）
        val kTight = TasteSpreadExpand.radialK(2.0f, 15f, 100f)
        val kMid = TasteSpreadExpand.radialK(2.0f, 50f, 100f)
        assertTrue("紧核 k≥中核（kTight=$kTight kMid=$kMid）", kTight >= kMid)
        assertTrue(kTight < 1f && kMid < 1f)
    }

    @Test
    fun `radialK-边界防御`() {
        assertEquals(1f, TasteSpreadExpand.radialK(1f, 15f, 100f), 0f)      // F=1 恒等
        assertEquals(1f, TasteSpreadExpand.radialK(2f, 100f, 100f), 0f)     // medR=maxR 退化
        assertEquals(1f, TasteSpreadExpand.radialK(2f, 0f, 100f), 0f)       // medR=0 防御
        assertEquals(1f, TasteSpreadExpand.radialK(2f, 150f, 100f), 0f)     // medR>maxR 防御
        // 巨大 F 的 k 钳 K_MIN（均匀紧核盘的诚实部分修——径向映射的展开极限）
        assertEquals(TasteSpreadExpand.K_MIN, TasteSpreadExpand.radialK(9f, 90f, 100f), 0.001f)
    }

    // ---- apply：紧核场景（真实形状——大部分星挤小团+少数离群拉半径） ----

    /** 紧核云：90 颗星挤半径 8..28（黄金角散布）+ 4 颗离群半径 300；星体半径 6 */
    private fun tightCloud(): Triple<FloatArray, FloatArray, BooleanArray> {
        val n = 94
        val x = FloatArray(n)
        val y = FloatArray(n)
        val hasComp = BooleanArray(n) { true }
        val golden = 2.39996f
        for (i in 0 until 90) {
            val ang = i * golden
            val r = 8f + (i % 10) * 2.2f // 8..27.8 核内
            x[i] = 500f + r * cos(ang)
            y[i] = 500f + r * sin(ang)
        }
        for (j in 0 until 4) {
            val ang = j * 1.57f + 0.35f
            x[90 + j] = 500f + 300f * cos(ang)
            y[90 + j] = 500f + 300f * sin(ang)
        }
        return Triple(x, y, hasComp)
    }

    @Test
    fun `apply-紧核触发展开且中位最近邻间距增大`() {
        val (x, y, mask) = tightCloud()
        val bodyR = FloatArray(x.size) { 6f }
        // 展开前核内最近邻
        fun medNn(): Float {
            val d = ArrayList<Float>()
            for (a in 0 until 90) {
                var best = Float.MAX_VALUE
                for (b in 0 until 90) {
                    if (a == b) continue
                    best = minOf(best, hypot((x[a] - x[b]).toDouble(), (y[a] - y[b]).toDouble()).toFloat())
                }
                d += best
            }
            return TasteSpreadExpand.medianOf(d.toFloatArray())
        }
        val before = medNn()
        val k = TasteSpreadExpand.apply(x, y, x.size, mask, bodyR, 500f, 500f)
        assertTrue("紧核应触发展开 k<1（实际 k=$k）", k < 1f)
        val after = medNn()
        assertTrue("核内最近邻间距应显著增大（$before → $after）", after > before * 1.4f)
    }

    @Test
    fun `apply-舒展布局零扰动`() {
        // 半径均匀撒 60 颗（最近邻间距 ≥ 2.2×星体半径）→ k=1 恒等零改写
        val n = 60
        val x = FloatArray(n)
        val y = FloatArray(n)
        val mask = BooleanArray(n) { true }
        for (i in 0 until n) {
            val ang = i * 0.8f
            val r = 40f + i * 4.3f
            x[i] = 500f + r * cos(ang)
            y[i] = 500f + r * sin(ang)
        }
        val bodyR = FloatArray(n) { 4f }
        val snapX = x.copyOf()
        val snapY = y.copyOf()
        val k = TasteSpreadExpand.apply(x, y, n, mask, bodyR, 500f, 500f)
        assertEquals("舒展布局不触发", 1f, k, 0f)
        assertTrue(snapX.contentEquals(x) && snapY.contentEquals(y))
    }

    @Test
    fun `apply-径向保序簇结构不变`() {
        val (x, y, mask) = tightCloud()
        val bodyR = FloatArray(x.size) { 6f }
        fun rAt(i: Int) = hypot((x[i] - 500.0).toFloat().toDouble(), (y[i] - 500.0).toFloat().toDouble()).toFloat()
        val before = (0 until 90).map { rAt(it) }
        val k = TasteSpreadExpand.apply(x, y, x.size, mask, bodyR, 500f, 500f)
        assertTrue(k < 1f)
        val after = (0 until 90).map { rAt(it) }
        // 径向重映射是 r 的单调函数：任意对的径向序保号（近等径对留浮点容差）
        for (i in 0 until 90) {
            for (j in i + 1 until 90) {
                val db = before[i] - before[j]
                val da = after[i] - after[j]
                if (abs(db) < 0.05f || abs(da) < 0.05f) continue
                assertTrue("径向序应保号（i=$i j=$j 前=$db 后=$da）", db * da > 0f)
            }
        }
    }

    @Test
    fun `apply-离群星原地不动`() {
        val (x, y, mask) = tightCloud()
        val bodyR = FloatArray(x.size) { 6f }
        val bx = x[93]
        val by = y[93]
        val k = TasteSpreadExpand.apply(x, y, x.size, mask, bodyR, 500f, 500f)
        assertTrue(k < 1f)
        assertEquals("离群星（r≥R 参考半径）原地不动", bx, x[93], 0.01f)
        assertEquals(by, y[93], 0.01f)
    }

    @Test
    fun `apply-质心星不动`() {
        // 20 星云 + 1 颗正放质心星：展开触发条件下质心星必须原地
        val n = 21
        val x = FloatArray(n) { 500f }
        val y = FloatArray(n) { 500f }
        val mask = BooleanArray(n) { true }
        val golden = 2.39996f
        for (i in 1..19) {
            val ang = i * golden
            val r = 10f + (i % 5) * 4f
            x[i] = 500f + r * cos(ang)
            y[i] = 500f + r * sin(ang)
        }
        x[20] = 800f
        y[20] = 500f
        val bodyR = FloatArray(n) { 6f }
        val k = TasteSpreadExpand.apply(x, y, n, mask, bodyR, 500f, 500f)
        if (k < 1f) {
            assertEquals("质心星原地", 500f, x[0], 0.001f)
            assertEquals(500f, y[0], 0.001f)
        }
    }

    @Test
    fun `apply-确定性同数据同结果`() {
        val (xa, ya, mask) = tightCloud()
        val (xb, yb, _) = tightCloud()
        val bodyR = FloatArray(xa.size) { 6f }
        val ka = TasteSpreadExpand.apply(xa, ya, xa.size, mask, bodyR, 500f, 500f)
        val kb = TasteSpreadExpand.apply(xb, yb, xb.size, mask, bodyR, 500f, 500f)
        assertEquals(ka, kb, 0f)
        assertTrue(xa.contentEquals(xb))
        assertTrue(ya.contentEquals(yb))
    }

    @Test
    fun `apply-hasComp门-证据不足不触发`() {
        val n = 5
        val x = FloatArray(n) { 500f + it * 20f }
        val y = FloatArray(n) { 500f }
        val mask = BooleanArray(n) { false } // 全无构成证据
        val bodyR = FloatArray(n) { 6f }
        assertEquals("证据星 <3 不展开", 1f, TasteSpreadExpand.apply(x, y, n, mask, bodyR, 500f, 500f), 0f)
    }

    @Test
    fun `medianOf-奇偶与退化`() {
        assertEquals(3f, TasteSpreadExpand.medianOf(floatArrayOf(3f)), 0f)
        assertEquals(2.5f, TasteSpreadExpand.medianOf(floatArrayOf(1f, 2f, 3f, 4f)), 0f)
        assertEquals(0f, TasteSpreadExpand.medianOf(FloatArray(0)), 0f)
        assertEquals(8f, TasteSpreadExpand.medianOf(floatArrayOf(9f, 7f, 8f)), 0f)
    }
}
