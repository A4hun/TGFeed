package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * R231 三带纵深分布单测：三带成立与界内 / XY 一概不动 / 确定性 / MDS 序与活跃度
 * 语义方向 / 椭球渐缩 / λ3 塌缩退化兜底。
 */
class TasteDepthSpreadTest {

    private fun run(
        n: Int,
        zMds: FloatArray,
        actMs: LongArray = LongArray(n),
        x: FloatArray = FloatArray(n) { 500f },
        y: FloatArray = FloatArray(n) { 500f },
    ): Pair<FloatArray, IntArray> {
        val ids = LongArray(n) { 1000L + it * 7L }
        val out = FloatArray(n)
        val bands = TasteDepthSpread.spread(n, zMds, ids, x, y, actMs, out)
        return out to bands
    }

    @Test
    fun `三带-分布成立且界内`() {
        val n = 90
        val (z, bands) = run(n, FloatArray(n) { it * 10f })
        assertEquals(n, bands[0] + bands[1] + bands[2])
        // 每带都有星（分布不塌缩到单带）
        for (b in bands) assertTrue("带内应有星（$b）", b in 12..60)
        // 全部界内（中心星无渐缩：|z| ≤ 0.95×半幅）
        for (v in z) assertTrue("z 越界：$v", abs(v) <= TasteDepthSpread.Z_HALF * 0.96f)
    }

    @Test
    fun `三带-XY一概不动`() {
        val n = 30
        val x = FloatArray(n) { 300f + it * 13f }
        val y = FloatArray(n) { 700f - it * 7f }
        val xCopy = x.copyOf()
        val yCopy = y.copyOf()
        run(n, FloatArray(n) { it.toFloat() }, x = x, y = y)
        assertTrue(x.contentEquals(xCopy))
        assertTrue(y.contentEquals(yCopy))
    }

    @Test
    fun `确定性-同输入同输出`() {
        val n = 64
        val zMds = FloatArray(n) { (it * 37) % 11 * 13f }
        val act = LongArray(n) { if (it % 3 == 0) 1_700_000_000_000L else 0L }
        val a = run(n, zMds, act)
        val b = run(n, zMds, act)
        assertTrue(a.first.contentEquals(b.first))
        assertTrue(a.second.contentEquals(b.second))
    }

    @Test
    fun `语义-MDS低z3偏近带`() {
        // 活跃度全同：z3 低半区的星纵深均值应小于高半区（55% 权重主导）
        val n = 40
        val zMds = FloatArray(n) { if (it < n / 2) it * 1f else 1000f + it * 1f }
        val (z, _) = run(n, zMds)
        val lo = z.take(n / 2).average()
        val hi = z.drop(n / 2).average()
        assertTrue("低 z3 半区均值应更近（$lo < $hi）", lo < hi)
    }

    @Test
    fun `语义-活跃星偏近带`() {
        // zMds 全同（rank 退化）：活跃组（act 高）纵深均值应小于沉睡组
        val n = 40
        val zMds = FloatArray(n) { 100f }
        val act = LongArray(n) { if (it < n / 2) 1_700_000_000_000L else 0L }
        val (z, _) = run(n, zMds, act)
        val active = z.take(n / 2).average()
        val dormant = z.drop(n / 2).average()
        assertTrue("活跃组均值应更近（$active < $dormant）", active < dormant)
    }

    @Test
    fun `椭球-XY边缘星纵深向中平面收`() {
        // 中心组 vs 远缘组：远缘 |z| 上界被渐缩压到 ≤0.36×半幅
        val n = 60
        val zMds = FloatArray(n) { it * 10f }
        val zCenter = FloatArray(n) { 500f }
        val zEdge = FloatArray(n) { 5000f }
        val (zc, _) = run(n, zMds, x = zCenter)
        val (ze, _) = run(n, zMds, x = zEdge)
        val maxEdge = ze.map { abs(it) }.max()
        val maxCenter = zc.map { abs(it) }.max()
        assertTrue("远缘纵深上界应被压（$maxEdge ≤ ${0.36f * TasteDepthSpread.Z_HALF}）", maxEdge!! <= 0.36f * TasteDepthSpread.Z_HALF)
        assertTrue("中心纵深应显著更深（$maxCenter > $maxEdge）", maxCenter!! > maxEdge)
    }

    @Test
    fun `退化-λ3塌缩不崩且仍三带`() {
        val n = 33
        val (z, bands) = run(n, FloatArray(n) { 42f })
        assertEquals(n, bands.sum())
        var nonZero = 0
        for (v in z) if (abs(v) > 1f) nonZero++
        assertTrue("塌缩输入仍应产出纵深散布（非零 $nonZero/$n）", nonZero >= n / 2)
        // 再跑一遍=确定性（退化路径也不许随机）
        val (z2, _) = run(n, FloatArray(n) { 42f })
        assertTrue(z.contentEquals(z2))
    }

    @Test
    fun `退化-空输入直通`() {
        val bands = TasteDepthSpread.spread(0, FloatArray(0), LongArray(0), FloatArray(0), FloatArray(0), LongArray(0), FloatArray(0))
        assertEquals(0, bands.sum())
    }
}
