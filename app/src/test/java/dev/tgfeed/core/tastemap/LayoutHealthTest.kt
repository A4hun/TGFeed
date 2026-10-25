package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R267 布局健康闸单测（塌方修复①的数学锚）：
 * 真机实锤形态——θ 全同（atan2(0,0)=0）/一维嵌入（θ∈{0,π}）→ 竖线；两形态都必须
 * 被判退化，健康球面（θ 全天球散布）必须放行；回退方位角必须确定性且破零散布。
 */
class LayoutHealthTest {

    // ---------------- θ 唯一桶数 ----------------

    @Test
    fun `θ全同塌缩-判退化`() {
        // 真机塌方原形：全部 taste 坐标=(500,500) → atan2(0,0)=0
        val thetas = FloatArray(500) { 0f }
        assertEquals(1, LayoutHealth.thetaUniqueCount(thetas))
        assertTrue(LayoutHealth.layoutDegenerate(LayoutHealth.thetaUniqueCount(thetas), 999))
    }

    @Test
    fun `θ两值一维嵌入-判退化`() {
        // 一维嵌入塌缩原形：λ2=λ3≈0 → x 散布 y 恒 500 → θ∈{0,π} 两值
        val thetas = FloatArray(500) { if (it % 2 == 0) 0f else Math.PI.toFloat() }
        assertEquals(2, LayoutHealth.thetaUniqueCount(thetas))
        assertTrue(LayoutHealth.layoutDegenerate(2, 999))
    }

    @Test
    fun `θ全天球散布-健康放行`() {
        val thetas = FloatArray(500) { (it / 500f) * 6.2831853f - 3.1415926f }
        val u = LayoutHealth.thetaUniqueCount(thetas)
        assertTrue("全天球散布应铺满桶（实测 $u）", u >= 32)
        assertFalse(LayoutHealth.layoutDegenerate(u, 999))
    }

    @Test
    fun `θ含NaN-防御进0桶且参与退化判定`() {
        val thetas = FloatArray(100) { Float.NaN }
        assertEquals(1, LayoutHealth.thetaUniqueCount(thetas))
        assertTrue(LayoutHealth.layoutDegenerate(1, 999))
    }

    // ---------------- xy 坐标唯一格数 ----------------

    @Test
    fun `坐标全塌中心-判退化`() {
        val x = FloatArray(500) { 500f }
        val y = FloatArray(500) { 500f }
        assertEquals(1, LayoutHealth.coordUniqueCount(x, y))
    }

    @Test
    fun `坐标散布-健康`() {
        val x = FloatArray(500) { 500f + (it % 37) * 16f }
        val y = FloatArray(500) { 500f + (it % 23) * 16f }
        assertTrue(LayoutHealth.coordUniqueCount(x, y) > 100)
    }

    // ---------------- 特征方差维 ----------------

    @Test
    fun `特征全单值-0维方差`() {
        // 真机塌方原料原形：所有频道特征向量全同（构成全视频+其余全零/全同）
        val n = 600
        val dims = 10
        val feat = FloatArray(n * dims)
        for (i in 0 until n) feat[i * dims] = 1f
        assertEquals(0, LayoutHealth.featureVarianceDims(feat, n, dims))
        assertTrue(LayoutHealth.sourceDegenerate(0, n))
    }

    @Test
    fun `特征两维有方差-原料健康`() {
        val n = 600
        val dims = 10
        val feat = FloatArray(n * dims)
        for (i in 0 until n) {
            feat[i * dims] = (i % 7) / 7f
            feat[i * dims + 7] = (i % 13) / 13f
        }
        assertEquals(2, LayoutHealth.featureVarianceDims(feat, n, dims))
        assertFalse(LayoutHealth.sourceDegenerate(2, n))
    }

    @Test
    fun `小库不启用源退化闸`() {
        // 演示/新装小库特征天然单薄（未知极哈希散布本就健康）——不启用重试风暴
        assertFalse(LayoutHealth.sourceDegenerate(0, LayoutHealth.SOURCE_CHECK_MIN_STARS - 1))
    }

    // ---------------- 回退方位角 ----------------

    @Test
    fun `回退θ-确定性同chatId同角`() {
        assertEquals(LayoutHealth.fallbackTheta(123456789L), LayoutHealth.fallbackTheta(123456789L))
    }

    @Test
    fun `回退θ-600星破零散布不塌线`() {
        // 塌方修复的回退承诺：哈希方位全天球散布（唯一桶数远超退化阈）
        val thetas = FloatArray(600) { LayoutHealth.fallbackTheta(1_000_000L + it) }
        val u = LayoutHealth.thetaUniqueCount(thetas)
        assertTrue("哈希方位应散布（实测 $u）", u >= 16)
        // 且与 spherifyV3 组合后 z 不塌（竖线病根=z≈0 ∀）
        val out = FloatArray(3)
        var zSpread = 0f
        var zPrev = 0f
        for (i in 0 until 64) {
            TasteSpace.spherifyV3(LayoutHealth.fallbackTheta(2_000_000L + i), 2_000_000L + i, 0.5f, out)
            if (i > 0) zSpread = maxOf(zSpread, kotlin.math.abs(out[2] - zPrev))
            zPrev = out[2]
        }
        assertTrue("回退布局 z 应展开（实测 $zSpread）", zSpread > 100f)
    }

    // ---------------- 基因指纹换装门 ----------------

    @Test
    fun `基因指纹-同值不漂移`() {
        val fp = LayoutHealth.genomeFp(524, 3_500_000L)
        assertFalse(LayoutHealth.genomeDrifted(fp, fp))
    }

    @Test
    fun `基因指纹-频道数±5门外漂移`() {
        val a = LayoutHealth.genomeFp(524, 3_500_000L)
        val b = LayoutHealth.genomeFp(600, 3_500_000L) // +14.5% 频道
        assertTrue(LayoutHealth.genomeDrifted(a, b))
    }

    @Test
    fun `基因指纹-总条数翻倍漂移`() {
        val a = LayoutHealth.genomeFp(524, 3_500_000L)
        val b = LayoutHealth.genomeFp(530, 7_200_000L) // 翻倍→对数桶+1
        assertTrue(LayoutHealth.genomeDrifted(a, b))
    }

    @Test
    fun `基因指纹-小幅爬行不漂移`() {
        val a = LayoutHealth.genomeFp(524, 3_500_000L)
        val b = LayoutHealth.genomeFp(530, 3_600_000L) // +1.1% 频道 +2.9% 条数（同对数桶）
        assertFalse(LayoutHealth.genomeDrifted(a, b))
    }

    @Test
    fun `基因指纹-零账不拦`() {
        val fp = LayoutHealth.genomeFp(524, 3_500_000L)
        assertFalse(LayoutHealth.genomeDrifted(0L, fp))
        assertFalse(LayoutHealth.genomeDrifted(fp, 0L))
    }

    // ---------------- 吞没渐隐（渲染侧纯函数锚） ----------------

    @Test
    fun `吞没渐隐-起点内零改动`() {
        // 手机竖屏口径：短边 1216 → 起点 146px 硬阈 425px
        val start = 1216f * TasteSpace.SWALLOW_FADE_START_FRAC
        val max = 1216f * TasteSpace.SWALLOW_MAX_R_FRAC
        assertEquals(1f, TasteSpace.swallowFade(100f, start, max))
        assertEquals(1f, TasteSpace.swallowFade(start, start, max))
    }

    @Test
    fun `吞没渐隐-起点到硬阈线性归零且单调`() {
        val start = 146f
        val max = 425f
        val mid = TasteSpace.swallowFade((start + max) / 2f, start, max)
        assertEquals(0.5f, mid, 1e-4f)
        assertEquals(0f, TasteSpace.swallowFade(max, start, max))
        assertEquals(0f, TasteSpace.swallowFade(max * 3f, start, max))
        assertTrue(TasteSpace.swallowFade(200f, start, max) > TasteSpace.swallowFade(300f, start, max))
    }

    @Test
    fun `吞没渐隐-异常阈防御不拦`() {
        assertEquals(1f, TasteSpace.swallowFade(9999f, 0f, 0f))
    }
}
