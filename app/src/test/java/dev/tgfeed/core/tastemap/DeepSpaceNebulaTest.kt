package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2

/**
 * R373 深空星云复合体纯件单测（design-nebula §3.2/§4.1/§4.3/§十一——确定性
 * （同数据同云）/空库诚实/双源并置各自诚实/均匀场防线/≤4 帽/发射线色板与
 * 亮度帽/电离侧活跃度驱动/近距渐出窗/演示保底/位置真源 agnostic 质心）。
 */
class DeepSpaceNebulaTest {

    private fun star(
        chatId: Long,
        x: Float,
        y: Float,
        watchSec: Long = 0L,
        subscribed: Boolean = false,
        gold: Boolean = false,
        recent7: Int = -1,
        lastActDays: Long = -1L,
        tempK: Float = 0f,
        vol01: Float = 0.5f,
    ): DeepSpaceNebula.StarIn {
        // 世界位=V3 同口径（口味角+哈希 φ+体量半径——与装配映射同一数学）
        val sp = FloatArray(3)
        TasteSpace.spherifyV3(atan2(-(y - 500f), x - 500f), chatId, vol01, sp)
        return DeepSpaceNebula.StarIn(
            chatId, x, y, sp[0], sp[1], sp[2],
            watchSec, subscribed, gold, recent7, lastActDays, tempK,
        )
    }

    /** 候选聚集云（8 未探索+4 候选 @ (700,350) 邻域）+热区（12 已探索 @ (250,650)） */
    private fun mixedUniverse(): List<DeepSpaceNebula.StarIn> {
        val out = ArrayList<DeepSpaceNebula.StarIn>(24)
        for (i in 0 until 8) {
            val gold = i < 4
            out += star(
                chatId = 10L + i, x = 700f + (i % 3) * 12f, y = 350f + (i / 3) * 12f,
                gold = gold, recent7 = if (gold) 9 else -1, lastActDays = if (gold) 3L else 60L,
            )
        }
        for (i in 0 until 12) {
            out += star(
                100L + i, 250f + (i % 4) * 10f, 650f + (i / 4) * 10f,
                watchSec = 5000L, tempK = 3600f + 500f * i,
            )
        }
        return out
    }

    @Test
    fun `摇篮-候选聚集处成云且留重帽内`() {
        val r = DeepSpaceNebula.complexesOf(mixedUniverse())
        assertTrue("候选聚集处应成摇篮复合体", r.complexes.isNotEmpty())
        assertTrue("复合体数 ≤4 帽", r.complexes.size <= DeepSpaceNebula.MAX_COMPLEXES)
        val c = r.complexes[0]
        // 质量账：8 未探索（4 候选×3+4×1）=16
        assertEquals(16f, c.mass, 1e-4f)
        assertEquals(8, c.members)
        assertTrue("世界半径在复合体量级域内", c.radius in DeepSpaceNebula.CPX_R_MIN..DeepSpaceNebula.CPX_R_MAX)
        assertTrue("主轴角有限值", !c.axisRad.isNaN())
    }

    @Test
    fun `确定性-同数据同云（顺序无关）`() {
        val a = DeepSpaceNebula.complexesOf(mixedUniverse())
        val b = DeepSpaceNebula.complexesOf(mixedUniverse().shuffled())
        assertEquals(a.complexes.size, b.complexes.size)
        assertEquals(a.enrichClouds.size, b.enrichClouds.size)
        for (i in a.complexes.indices) {
            assertEquals(a.complexes[i].cx, b.complexes[i].cx, 1e-4f)
            assertEquals(a.complexes[i].cy, b.complexes[i].cy, 1e-4f)
            assertEquals(a.complexes[i].cz, b.complexes[i].cz, 1e-4f)
            assertEquals(a.complexes[i].radius, b.complexes[i].radius, 1e-4f)
            assertEquals(a.complexes[i].mass, b.complexes[i].mass, 1e-4f)
            assertEquals(a.complexes[i].cellKey, b.complexes[i].cellKey)
            assertEquals(a.complexes[i].haMix01, b.complexes[i].haMix01, 1e-6f)
        }
        // 哈希稳定：同键同种子
        assertEquals(
            DeepSpaceNebula.churnSeedOf(123, 40f),
            DeepSpaceNebula.churnSeedOf(123, 40f),
            0f,
        )
        assertTrue(DeepSpaceNebula.churnSeedOf(124, 40f) != DeepSpaceNebula.churnSeedOf(123, 40f))
    }

    @Test
    fun `空库-两源全零诚实`() {
        val r = DeepSpaceNebula.complexesOf(emptyList())
        assertTrue(r.complexes.isEmpty())
        assertTrue(r.enrichClouds.isEmpty())
        assertEquals(0f, r.cradleMass, 0f)
    }

    @Test
    fun `全库看完-摇篮云零而富集微光仍在（两源各自诚实）`() {
        // 全部已探索（全库看完）：摇篮场空=复合体零；热区场非空=富集微光在
        val explored = (0 until 12).map {
            star(500L + it, 400f + (it % 4) * 10f, 550f + (it / 4) * 10f, watchSec = 9999L, tempK = 5200f)
        }
        val r = DeepSpaceNebula.complexesOf(explored)
        assertTrue("全库看完=摇篮云零（缺据不出）", r.complexes.isEmpty())
        assertTrue("热区富集微光仍在（终审修订版诚实缺省）", r.enrichClouds.isNotEmpty())
        assertEquals(0f, r.cradleMass, 0f)
    }

    @Test
    fun `均匀场-平坦绝对门不出假云`() {
        // 100 未探索星均匀 10×10 网格铺满口味平面（无聚集）——峰≈均值=全拒
        val uniform = ArrayList<DeepSpaceNebula.StarIn>(100)
        var id = 0L
        for (gy in 0 until 10) {
            for (gx in 0 until 10) {
                uniform += star(id++, 50f + gx * 100f, 50f + gy * 100f)
            }
        }
        val r = DeepSpaceNebula.complexesOf(uniform)
        assertTrue("均匀分布无聚集=无云（平坦绝对门）", r.complexes.isEmpty())
    }

    @Test
    fun `双源并置-摇篮在未探索位富集绕热区`() {
        val r = DeepSpaceNebula.complexesOf(mixedUniverse())
        // 摇篮复合体质心=未探索成员 sp **质量加权**均值（候选 ×3——位置真源
        // agnostic 的加权口径字面锚）
        val members = mixedUniverse().filter { it.watchSec == 0L && !it.subscribed }
        var wx = 0f
        var wy = 0f
        var wz = 0f
        var wm = 0f
        for (m in members) {
            val w = if (m.goldCandidate) DeepSpaceNebula.GOLD_WEIGHT else 1f
            wx += w * m.spX
            wy += w * m.spY
            wz += w * m.spZ
            wm += w
        }
        assertEquals(wx / wm, r.complexes[0].cx, 1e-3f)
        assertEquals(wy / wm, r.complexes[0].cy, 1e-3f)
        assertEquals(wz / wm, r.complexes[0].cz, 1e-3f)
        // 富集云=热区（12 已探索比 8 未探索更密）在场且帽 4
        assertTrue(r.enrichClouds.isNotEmpty())
        assertTrue(r.enrichClouds.size <= DeepSpaceNebula.MAX_ENRICH_CLOUDS)
        // 富集半径克制域
        assertTrue(
            r.enrichClouds[0].radius in DeepSpaceNebula.ENRICH_R_MIN..DeepSpaceNebula.ENRICH_R_MAX,
        )
    }

    @Test
    fun `富集色温从动-热区温度决定反射色`() {
        // 热星团（9800K 蓝白）与冷星团（2600K 暗红）两处富集云色互异（黑体谱从动）
        val hot = (0 until 12).map { star(700L + it, 200f + (it % 4) * 9f, 200f + (it / 4) * 9f, watchSec = 1L, tempK = 9800f) }
        val cold = (0 until 10).map { star(800L + it, 800f + (it % 4) * 9f, 800f + (it / 4) * 9f, watchSec = 1L, tempK = 2600f) }
        val r = DeepSpaceNebula.complexesOf(hot + cold)
        assertEquals(2, r.enrichClouds.size)
        val colors = r.enrichClouds.map { it.colorArgb }
        assertTrue("冷热星区反射色互异（色温从动判词一）", colors[0] != colors[1])
        // 方向锚：质量留重=热区 12 星先于冷区 10 星；blackbody 9800K 蓝通道 255 档
        // 对 2600K 约 79 档——压暗后高温区反射云蓝通道仍高于低温区（温度→颜色方向）
        val bHot = colors[0] and 0xFF
        val bCold = colors[1] and 0xFF
        assertTrue("高温星区反射云蓝通道高于低温星区（色温从动方向）", bHot > bCold)
    }

    @Test
    fun `富集中性缺省-零温度证据不冒充彩色反射`() {
        val noTemp = (0 until 12).map { star(750L + it, 400f + (it % 4) * 9f, 500f + (it / 4) * 9f, watchSec = 1L, tempK = 0f) }
        val rnt = DeepSpaceNebula.complexesOf(noTemp)
        assertTrue(rnt.enrichClouds.isNotEmpty())
        assertEquals(DeepSpaceNebula.ENRICH_NEUTRAL_ARGB, rnt.enrichClouds[0].colorArgb)
    }

    @Test
    fun `电离侧亮暗-嵌入候选活跃度驱动且帽不越`() {
        // O/B 炽热候选（recent7 12）→ ion01 高；零候选 → ion01=0
        val hot = (0 until 6).map {
            star(900L + it, 600f + (it % 3) * 10f, 300f + (it / 3) * 10f, gold = it < 3, recent7 = 12, lastActDays = 2L)
        }
        val rHot = DeepSpaceNebula.complexesOf(hot)
        assertTrue(rHot.complexes[0].ion01 > 0.5f)
        val cold = (0 until 6).map {
            star(950L + it, 600f + (it % 3) * 10f, 300f + (it / 3) * 10f)
        }
        val rCold = DeepSpaceNebula.complexesOf(cold)
        assertEquals(0f, rCold.complexes[0].ion01, 0f)
        // alpha 口：电离增益调制 0.55~1.0×帽，帽恒不越
        assertTrue(DeepSpaceNebula.emissionAlpha01(0f) < DeepSpaceNebula.emissionAlpha01(1f))
        assertEquals(DeepSpaceNebula.CPX_ALPHA_MAX, DeepSpaceNebula.emissionAlpha01(1f), 1e-6f)
        assertTrue(DeepSpaceNebula.emissionAlpha01(2f) <= DeepSpaceNebula.CPX_ALPHA_MAX)
        // 富集平档 0.08（暗于发射=物理诚实）
        assertEquals(DeepSpaceNebula.ENRICH_ALPHA_MAX, DeepSpaceNebula.enrichAlpha01(), 0f)
        assertTrue(DeepSpaceNebula.ENRICH_ALPHA_MAX < DeepSpaceNebula.CPX_ALPHA_MAX)
    }

    @Test
    fun `发射线色板-fBm权混场逐层确定性且不出色板凸包`() {
        val c0 = DeepSpaceNebula.emissionLayerColor(0.5f, 4321, 0)
        val c0b = DeepSpaceNebula.emissionLayerColor(0.5f, 4321, 0)
        assertEquals(c0, c0b)
        val c1 = DeepSpaceNebula.emissionLayerColor(0.5f, 4321, 1)
        val c2 = DeepSpaceNebula.emissionLayerColor(0.5f, 4321, 2)
        // 权混场=两发射线凸组合：逐层逐通道落在 Hα 与 OIII 极值凸包内（零新色出板）
        for (c in listOf(c0, c1, c2)) {
            for (ch in intArrayOf(16, 8, 0)) {
                val ha = (DeepSpaceNebula.HA_ARGB shr ch) and 0xFF
                val oi = (DeepSpaceNebula.OIII_ARGB shr ch) and 0xFF
                val v = (c shr ch) and 0xFF
                assertTrue("层色通道须在两发射线极值凸包内", v in minOf(ha, oi)..maxOf(ha, oi))
            }
        }
        // 结构性零 hue(t)：函数无时间参（固定色板+数据权混——禁令 12 不犯的签名级证据）
        assertTrue(DeepSpaceNebula.HA_ARGB != DeepSpaceNebula.OIII_ARGB)
    }

    @Test
    fun `近距渐出-smoothstep窗满远零近单调`() {
        val r = 1000f
        assertEquals(1f, DeepSpaceNebula.nearFade01(2000f, r), 1e-4f)
        assertTrue(DeepSpaceNebula.nearFade01(500f, r) > 0f)
        assertEquals(0f, DeepSpaceNebula.nearFade01(0f, r), 1e-4f)
        // 单调：更远更亮（0.8R 窗=§5.2 触发门外沿同值）
        assertTrue(DeepSpaceNebula.nearFade01(700f, r) > DeepSpaceNebula.nearFade01(300f, r))
        assertEquals(0.8f, DeepSpaceNebula.NEAR_FADE_OUTER, 0f)
    }

    @Test
    fun `演示保底-预标定样本形态在场且真实库不掺`() {
        // 演示宇宙（空星表+demoCalibration）→ 摇篮与富集保底形态在场
        val demo = DeepSpaceNebula.complexesOf(emptyList(), demoCalibration = true)
        assertTrue("演示摇篮保底", demo.complexes.isNotEmpty())
        assertTrue("演示富集保底", demo.enrichClouds.isNotEmpty())
        // 同调同表（确定性）
        val demo2 = DeepSpaceNebula.complexesOf(emptyList(), demoCalibration = true)
        assertEquals(demo.complexes.size, demo2.complexes.size)
        assertEquals(demo.complexes[0].cx, demo2.complexes[0].cx, 0f)
        // 真实库（demoCalibration=false）永不掺入
        val real = DeepSpaceNebula.complexesOf(emptyList(), demoCalibration = false)
        assertTrue(real.complexes.isEmpty())
        assertTrue(real.enrichClouds.isEmpty())
        // 演示样本并入真实表：复合体数仍在帽内
        val mixed = DeepSpaceNebula.complexesOf(mixedUniverse(), demoCalibration = true)
        assertTrue(mixed.complexes.size <= DeepSpaceNebula.MAX_COMPLEXES)
    }
}
