package dev.tgfeed.ui.gl

import dev.tgfeed.core.tastemap.CelestialKind
import dev.tgfeed.core.tastemap.TasteFlight
import dev.tgfeed.core.tastemap.TasteSpace
import dev.tgfeed.core.tastemap.TasteStarColor
import dev.tgfeed.core.tastemap.TasteTagPlanets
import dev.tgfeed.ui.TasteFlightFrame
import dev.tgfeed.ui.TastePlanetEntry
import dev.tgfeed.ui.TasteFxStar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R399 行星缩放消失根治·LOD 退化行为例（判词引 r399_notes §2——用户 21:1x
 * 实锤「双指缩小画面时有些行星会消失，全程不见像隐身」；星图捏合=dolly 后退
 * →井深比升，吞门在行星屏半径仍醒目处整吞）。
 *
 * 行为锚五例（GlFrameBuilder JVM 直测面——同 GlFrameBuilderTest 口径：
 * focal=1006.86/wellR=54(vol=100 满档井)/mdpi 阈=1.2/1.6px/微点地板 2.2px）：
 *  - **远档吞门退役**：ratio≥APPEAR(6.0) 旧=整系统 return（行星 0 球 0 点=
 *    隐身实锚）；新=球身 0（性能纪律：重层全灭照旧）+**行星色微点 1**
 *    （并入母星光晕）+KEPLER/ROCK 重层 op 全不在场；
 *  - **交叉淡出共存**：坡道域（a∈(0,1)）球与微点同帧（count 轴双态叠加——
 *    R349 blend 带同族语言）；
 *  - **亚像素画出门退役**：rP<1.5px（旧=连点腿都不给）→ 尺寸地板点在场
 *    （最小可见亮点）+count 轴微点在场；
 *  - **远档 top-K 帽**：深位行星在坡道窗内退场途中（全量）→窗外只 top-K
 *    （点预算纪律：全场系统×K 有界）；
 *  - **近档零变化**：a=1 满档域微点零（近距观感零改——红线锚）。
 */
class R399LodDecayTest {

    private val w = 1080f
    private val h = 2400f

    private fun star(
        chatId: Long, x: Float, y: Float, z: Float, bodyR: Float,
        vol: Int = 100,
    ) = TasteFxStar(
        chatId = chatId, x = x, y = y, z = z,
        radius = bodyR, colorArgb = 0xFFA0C8FF.toInt(), alpha = 0.9f, label = null,
        firstSeenMs = 0L, hasComp = true,
        kind = CelestialKind.STAR, bodyR = bodyR, bodyAlpha = 0.9f,
        bodyColor = 0xFFA0C8FF.toInt(), vol = vol, title = "",
        members = -1, recent7 = -1, gVideo = 0, gPhoto = 0, gAudio = 0,
    )

    private fun planet(tag: String, orbitR01: Float) = TasteTagPlanets.TagPlanet(
        tag = tag, count = 5, orbitR01 = orbitR01, sizeR01 = 0.8f, phase01 = 0.3f,
        omegaRadPerSec = 0.01f, itemIdx = intArrayOf(0, 1),
        dustMinSec = 100L, dustMaxSec = 200L, ecc = 0.2f,
    )

    private fun videoItem(key: String, dateSec: Int) = dev.tgfeed.core.td.VideoItem(
        key = key, chatId = 1L, messageId = 1L, channelTitle = "频道甲", caption = "",
        durationSec = 10, width = 640, height = 360, fileId = -1, sizeBytes = 1000L,
        playUri = null, dateEpochSec = dateSec,
    )

    /** 单星单行星帧（vol=100/wellMaxVol=100 → wellR=54——ratio=z/54 数学锚） */
    private fun planetSystemFrame(bodyR: Float, z: Float): TasteFlightFrame {
        val sys = TasteTagPlanets.PlanetSystem(
            chatId = 201L, planets = listOf(planet("音乐", 0.5f)), sampleCount = 2, taggedCount = 2,
        )
        val s = star(201L, 0f, 0f, z, bodyR)
        return TasteFlightFrame(
            cam = TasteSpace.SpaceCam.ORIGIN,
            stars = listOf(s), edges = emptyList(), engine = TasteFlight.FxEngine(),
            genesisOn = false, genesisSpan = TasteFlight.GenesisSpan(0L, 0L), genesisT01 = 1f,
            playingChatId = 0L, selectedId = 0L, labelMinPx = 24f, labelTextPx = 30f,
            planetSys = mapOf(
                201L to TastePlanetEntry(
                    201L, sys, listOf(videoItem("k0", 120), videoItem("k1", 180)),
                ),
            ),
            dustChatId = 201L, wellMaxVol = 100,
        )
    }

    /** 单星 N 行星帧（远档 top-K 帽例——N>LOD_MID 深位在场） */
    private fun multiPlanetFrame(n: Int, z: Float): TasteFlightFrame {
        val planets = (0 until n).map { planet("标签$it", 0.1f + 0.05f * it) }
        val sys = TasteTagPlanets.PlanetSystem(
            chatId = 201L, planets = planets, sampleCount = n, taggedCount = n,
        )
        val s = star(201L, 0f, 0f, z, 20f)
        return TasteFlightFrame(
            cam = TasteSpace.SpaceCam.ORIGIN,
            stars = listOf(s), edges = emptyList(), engine = TasteFlight.FxEngine(),
            genesisOn = false, genesisSpan = TasteFlight.GenesisSpan(0L, 0L), genesisT01 = 1f,
            playingChatId = 0L, selectedId = 0L, labelMinPx = 24f, labelTextPx = 30f,
            planetSys = mapOf(
                201L to TastePlanetEntry(
                    201L, sys, (0 until n).map { videoItem("k$it", 120 + it) },
                ),
            ),
            dustChatId = 0L, wellMaxVol = 100,
        )
    }

    private fun opTypes(b: GlFrameBuilder): List<Int> = (0 until b.opCount).map { b.opType[it] }

    /** 行星色微点（count 轴/远档）在点缓冲中的命中（尺寸=微点地板+核比 0.75+alpha 带域） */
    private fun hasPlanetMicroDot(b: GlFrameBuilder, alphaMin: Float, alphaMax: Float): Boolean {
        for (i in 0 until b.pointCount) {
            val base = i * GlFrameBuilder.POINT_STRIDE
            val size = b.pointBuf[base + 2]
            val core = b.pointBuf[base + 3]
            val a = b.pointBuf[base + 7]
            if (size == 2.2f && core == 0.75f && a in alphaMin..alphaMax) return true
        }
        return false
    }

    // ==================== ① 远档吞门退役（用户实锤的主病灶行为锚） ====================

    @Test
    fun `远档吞门退役-ratio过阈行星不整吞微点在场重层全灭`() {
        // z=400 → ratio=400/54=7.41 ≥ APPEAR(6.0)：旧=整系统 return（行星 0 球
        // 0 点=「隐身」实锚）；新=球身 0+行星色微点 1
        val b = GlFrameBuilder()
        b.build(planetSystemFrame(bodyR = 20f, z = 400f), w, h)
        assertEquals("远档球身应零（性能纪律：降级省 draw）", 0, b.planetCount)
        assertEquals("远档行星微点应恰 1（吞门退役）", 1, b.statPlanetDots)
        // 微点本体：尺寸=地板 2.2px、alpha=微星近档 0.86（distStar=400<900）
        assertTrue("行星色微点应在点缓冲（2.2px/核0.75/α≈0.86）", hasPlanetMicroDot(b, 0.85f, 0.87f))
        // 重层全灭照旧（轨道线/宜居带/尘埃/岩石 op 全不在场——性能刀收益零回退）
        val ops = opTypes(b)
        assertFalse("远档无 KEPLER 轨道线", ops.contains(GlFrameBuilder.OP_KEPLER_LINES))
        assertFalse("远档无 KEPLER 点（尘埃/冰粒）", ops.contains(GlFrameBuilder.OP_KEPLER_POINTS))
        assertFalse("远档无岩石", ops.contains(GlFrameBuilder.OP_ROCK))
    }

    // ==================== ② 交叉淡出共存（count 轴双态叠加） ====================

    @Test
    fun `坡道交叉淡出-球与微点同帧镜像交接`() {
        // z=320 → ratio=5.926：a=(6.0−5.926)/0.8≈0.093——球@0.093 与微点@0.86×
        // (1−a)≈0.78 同帧（R349 blend 带双态叠加同族语言——count 轴零跳变交接）
        val b = GlFrameBuilder()
        b.build(planetSystemFrame(bodyR = 20f, z = 320f), w, h)
        assertEquals("坡道域球身应在场（淡出中）", 1, b.planetCount)
        assertEquals("坡道域行星微点应恰 1（淡入中）", 1, b.statPlanetDots)
        // α 带=亮色 0.86×(1−a)≈0.780 与凌星暗微点 ×0.94≈0.734 两变体兼容
        //（轨道相位确定性——凌星与否由 seed 定，双带皆=「微点在场」判词）
        assertTrue("微点 α≈0.86×(1−a)（亮/剪影两变体）", hasPlanetMicroDot(b, 0.72f, 0.79f))
    }

    // ==================== ③ 亚像素画出门退役（rP<1.5px 也亮） ====================

    @Test
    fun `亚像素画出门退役-rP过小也成最小可见亮点`() {
        // bodyR=2 z=320 → rP=2×0.183×1006.86/320≈1.15px <1.5（旧=连点腿都不给
        // =小屏真实隐身缝）；新=count 轴微点+buildPlanetBody 点腿尺寸地板 2.3px
        val b = GlFrameBuilder()
        b.build(planetSystemFrame(bodyR = 2f, z = 320f), w, h)
        assertEquals("亚像素档球身应零（rP<形态线）", 0, b.planetCount)
        assertEquals("亚像素档行星微点应恰 1", 1, b.statPlanetDots)
        var floorPoint = false
        for (i in 0 until b.pointCount) {
            val base = i * GlFrameBuilder.POINT_STRIDE
            if (b.pointBuf[base + 2] in 2.29f..2.31f && b.pointBuf[base + 3] == 0.75f) floorPoint = true
        }
        assertTrue("点腿尺寸地板 max(rP×2,2.2)≈2.303px 应在场（最小可见亮点）", floorPoint)
    }

    // ==================== ④ 远档 top-K 帽（点预算纪律） ====================

    @Test
    fun `远档topK帽-深位窗内退场途中全量窗外只topK`() {
        // 10 行星系：z=340 → ratio=6.296<APPEAR+ramp(6.8)——深位（index≥6）退场
        // 途中（da≈0.63）→ 全量 10 微点
        val bIn = GlFrameBuilder()
        bIn.build(multiPlanetFrame(10, z = 340f), w, h)
        assertEquals("退场窗内应全量 10 微点", 10, bIn.statPlanetDots)
        assertEquals("退场窗内球身应零", 0, bIn.planetCount)
        // z=400 → ratio=7.41≥6.8 窗外——只 top-K（LOD_MID_PLANETS=6）
        val bOut = GlFrameBuilder()
        bOut.build(multiPlanetFrame(10, z = 400f), w, h)
        assertEquals("窗外应只 top-6 微点（点预算纪律）", 6, bOut.statPlanetDots)
        assertEquals("窗外球身应零", 0, bOut.planetCount)
    }

    // ==================== ⑤ 近档零变化（红线锚） ====================

    @Test
    fun `近档零变化-满档域微点零球身照旧`() {
        // z=250 → ratio=4.63：a=(6.0−4.63)/0.8>1 → a=1 满档——微点 0（近距
        // 观感零改=「星系渲染既有结论零碰」行为锚）；球身/轨道/尘埃照旧
        val b = GlFrameBuilder()
        b.build(planetSystemFrame(bodyR = 20f, z = 250f), w, h)
        assertEquals("近档球身应照旧 1", 1, b.planetCount)
        assertEquals("近档行星微点应零", 0, b.statPlanetDots)
        val ops = opTypes(b)
        assertTrue("近档轨道线照旧", ops.contains(GlFrameBuilder.OP_KEPLER_LINES))
        assertTrue("近档尘埃照旧", ops.contains(GlFrameBuilder.OP_KEPLER_POINTS))
    }
}
