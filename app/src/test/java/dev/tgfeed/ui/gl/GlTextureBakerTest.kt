package dev.tgfeed.ui.gl

import dev.tgfeed.core.tastemap.TasteStarGlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R277 GlTextureBaker 单测（纯函数像素烘焙的不变式锚——确定性/硬边/明暗结构；
 * 不锚具体像素值（观感参数演进自由），锚结构性质）。
 * R281 注：行星位图族与黑洞吸积盘纹随行星真球化/吸积盘真 3D 环带化退役
 * （烘焙件与锚同撤——真身锚在 GlFrameBuilderTest 的 op 装配口径）。
 */
class GlTextureBakerTest {

    private fun alphaOf(px: IntArray, x: Int, y: Int, w: Int): Int = (px[y * w + x] ushr 24) and 0xFF

    // ==================== 小件 ====================

    @Test
    fun `细环 中心空+环带有峰+外缘零`() {
        val px = GlTextureBaker.bakeRing()
        val c = GlTextureBaker.RING_SIZE / 2
        val w = GlTextureBaker.RING_SIZE
        assertEquals(0, alphaOf(px, c, c, w)) // 中心空
        // 环带峰位（d≈0.905×c）
        val ringX = c + (c * 0.905f).toInt()
        assertTrue("环带应有 alpha", alphaOf(px, ringX, c, w) > 100)
        assertEquals(0, alphaOf(px, 1, 1, w)) // 外角零
    }

    @Test
    fun `光刺 横向中心最亮+纵向中行最亮+端点归零`() {
        val px = GlTextureBaker.bakeSpike()
        val w = GlTextureBaker.SPIKE_W
        val midY = GlTextureBaker.SPIKE_H / 2 - 1 // 中行（3）
        val centerA = alphaOf(px, w / 2, midY, w)
        val edgeA = alphaOf(px, 0, midY, w)
        assertTrue("光刺中心应最亮", centerA > 150)
        assertTrue("光刺端点应归零附近", edgeA < 10)
        // 纵向：中行亮于边行
        assertTrue(alphaOf(px, w / 2, 0, w) < alphaOf(px, w / 2, midY, w))
    }

    @Test
    fun `光刺 R339 快衰减剖面（根=SPIKE_A 半芒近灭 0_094 单调非增——core 单源）`() {
        val px = GlTextureBaker.bakeSpike()
        val w = GlTextureBaker.SPIKE_W
        val midY = 3
        // 根=SPIKE_A 0.75（芒基 alpha 烘焙入纹理——绘制端 quad alpha 只乘星亮×让位；
        // 容差 0.05=像素中心采样 t=0.508 的 (1−u)³ 根损耗）
        assertEquals(TasteStarGlow.SPIKE_A, alphaOf(px, w / 2, midY, w) / 255f, 0.05f)
        // 半芒 u≈0.53：(1−u)³×0.75≈0.08——「刺」不是「棒」（R242 线性渐变随 r 门十字退役）
        assertEquals(TasteStarGlow.spikeAlpha01(0.5f), alphaOf(px, w / 2 + w / 4, midY, w) / 255f, 0.03f)
        assertTrue("半芒即近灭（≤0.11）", alphaOf(px, w / 2 + w / 4, midY, w) / 255f < 0.11f)
        // 沿芒单调非增（中心→1/4→端）
        val a0 = alphaOf(px, w / 2, midY, w)
        val a1 = alphaOf(px, w / 2 + 6, midY, w)
        val a2 = alphaOf(px, w - 1, midY, w)
        assertTrue("沿芒单调非增", a0 > a1 && a1 > a2)
    }

    @Test
    fun `光柱 底实顶虚（衰减向远端）`() {
        val px = GlTextureBaker.bakeBeam()
        val w = GlTextureBaker.BEAM_W
        val bottom = alphaOf(px, w / 2, GlTextureBaker.BEAM_H - 1, w)
        val top = alphaOf(px, w / 2, 0, w)
        assertTrue("光柱底部（星端）应实", bottom > 150)
        assertTrue("光柱顶端（远端）应虚", top < 20)
        // 中列亮于边列
        assertTrue(alphaOf(px, 2, GlTextureBaker.BEAM_H - 4, w) < bottom)
    }

    @Test
    fun `圆盘 中心全实+硬边（九成半径内满、边缘归零）`() {
        val px = GlTextureBaker.bakeDisc()
        val c = GlTextureBaker.DISC_SIZE / 2
        val w = GlTextureBaker.DISC_SIZE
        assertEquals(255, alphaOf(px, c, c, w))
        assertEquals(255, alphaOf(px, c + (c * 0.90f).toInt(), c, w)) // 90% 仍全实（硬边）
        assertEquals(0, alphaOf(px, 1, 1, w))
    }

    @Test
    fun `雾 心亮缘零单调衰减（R280 未探索星云件）`() {
        val px = GlTextureBaker.bakeFog()
        val c = GlTextureBaker.FOG_SIZE / 2
        val w = GlTextureBaker.FOG_SIZE
        val heart = alphaOf(px, c, c, w)
        assertTrue("雾心 ~75% alpha", heart in 170..210)
        assertEquals(0, alphaOf(px, 1, 1, w)) // 角零
        val mid = alphaOf(px, c + c / 2, c, w)
        assertTrue("半径中段应弱于心", mid in 1 until heart)
    }

    @Test
    fun `纯色白件 全实（虫洞暗幕着色底）`() {
        val px = GlTextureBaker.bakeWhite()
        assertEquals(GlTextureBaker.WHITE_SIZE * GlTextureBaker.WHITE_SIZE, px.size)
        assertTrue(px.all { it == -1 }) // 0xFFFFFFFF
    }

    @Test
    fun `调色板 HSV 双程回转（色相饱和明度保真）`() {
        // 纯红
        val red = GlPalette.hsvToRgb(0f, 1f, 1f)
        assertEquals(0xFFFF0000.toInt(), red)
        // 双程：rgb→hsv→rgb 误差 ≤1/255
        val hsv = FloatArray(3)
        GlPalette.hsvOf(0xFF3A7BD5.toInt(), hsv)
        val back = GlPalette.hsvToRgb(hsv[0], hsv[1], hsv[2])
        assertTrue(kotlin.math.abs(0x3A - ((back shr 16) and 0xFF)) <= 1)
        assertTrue(kotlin.math.abs(0x7B - ((back shr 8) and 0xFF)) <= 1)
        assertTrue(kotlin.math.abs(0xD5 - (back and 0xFF)) <= 1)
    }

    @Test
    fun `微星桶量化（低饱和归白或暗桶；其余 8 扇区×明暗）`() {
        val hsv = FloatArray(3)
        // 纯白（s=0）→ 白桶/暗桶按亮度分
        assertEquals(GlPalette.MICRO_WHITE, GlPalette.microBucketOf(0xFFFFFFFF.toInt(), bright = true, hsv))
        assertEquals(GlPalette.MICRO_DIM, GlPalette.microBucketOf(0xFF555555.toInt(), bright = false, hsv))
        // 高饱和红（h=0）→ 扇区 0
        val b = GlPalette.microBucketOf(0xFFFF2020.toInt(), bright = true, hsv)
        assertTrue("高饱和红应落扇区 0 亮档", b == 1)
        assertNotEquals(GlPalette.MICRO_WHITE, b)
    }
}
