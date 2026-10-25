package dev.tgfeed.ui.gl

import dev.tgfeed.core.tastemap.CelestialKind
import dev.tgfeed.core.tastemap.EmissionNebulaFx
import dev.tgfeed.core.tastemap.FogFlocFx
import dev.tgfeed.core.tastemap.TasteFlight
import dev.tgfeed.core.tastemap.TasteNebula
import dev.tgfeed.core.tastemap.TasteSpace
import dev.tgfeed.core.tastemap.TasteStarGlow
import dev.tgfeed.core.tastemap.TasteTerminalFx
import dev.tgfeed.ui.TasteArc
import dev.tgfeed.ui.TasteFlightFrame
import dev.tgfeed.ui.TasteFxEdge
import dev.tgfeed.ui.TasteFxStar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * R277 GlFrameBuilder 单测（DTO→GL 顶点装配器的纯函数锚）：
 * 三档路由/画家序/名签候选/创世纪门/吞没渐隐/边弧线段/缓冲复用零分配——
 * 逐值锚（R345 Canvas 退役后 GL 唯一引擎——历史镜像基线原样守护「同参数同产出」）。
 */
class GlFrameBuilderTest {

    private val w = 1080f
    private val h = 2400f
    private val focal = TasteSpace.focalOf(w, h) // 1006.86

    private val engine = TasteFlight.FxEngine()

    private fun star(
        chatId: Long, x: Float, y: Float, z: Float, bodyR: Float,
        kind: CelestialKind = CelestialKind.STAR,
        color: Int = 0xFFA0C8FF.toInt(),
        title: String = "",
        vol: Int = 10,
        alpha: Float = 0.9f,
        firstSeenMs: Long = 0L,
        members: Int = -1,
        recent7: Int = -1,
        gVideo: Int = 0,
        gPhoto: Int = 0,
        gAudio: Int = 0,
    ) = TasteFxStar(
        chatId = chatId, x = x, y = y, z = z,
        radius = bodyR, colorArgb = color, alpha = alpha, label = null,
        firstSeenMs = firstSeenMs, hasComp = true,
        kind = kind, bodyR = bodyR, bodyAlpha = alpha, bodyColor = color, vol = vol, title = title,
        members = members, recent7 = recent7, gVideo = gVideo, gPhoto = gPhoto, gAudio = gAudio,
    )

    private fun frame(
        stars: List<TasteFxStar>,
        edges: List<TasteFxEdge> = emptyList(),
        arcs: List<TasteArc> = emptyList(),
        genesisOn: Boolean = false,
        span: TasteFlight.GenesisSpan = TasteFlight.GenesisSpan(0L, 0L),
        t01: Float = 1f,
        known: Set<Long> = emptySet(),
        playing: Long = 0L,
        selected: Long = 0L,
        engine: TasteFlight.FxEngine = this.engine,
        nebulae: List<dev.tgfeed.core.tastemap.TasteNebula.NebulaCluster> = emptyList(),
        emissionFogs: List<EmissionNebulaFx.Fog> = emptyList(),
        spread: dev.tgfeed.ui.TasteSpreadRender? = null,
        spreadCursorMs: Long = Long.MAX_VALUE,
        constellation: dev.tgfeed.ui.TasteConstTrail? = null,
        showConstellation: Boolean = false,
        ship: dev.tgfeed.core.tastemap.TasteShipFx.ShipTrailEngine? = null,
        shipStyle: dev.tgfeed.core.tastemap.TasteAchievements.ShipStyle =
            dev.tgfeed.core.tastemap.TasteAchievements.ShipStyle.MICRO_TRAIL,
        wormholeP01: Float = -1f,
        revealIds: Map<Long, Long> = emptyMap(),
        nowMs: Long = 1000L,
        linkLines: Boolean = true,
    ) = TasteFlightFrame(
        cam = TasteSpace.SpaceCam.ORIGIN,
        stars = stars,
        edges = edges,
        engine = engine,
        genesisOn = genesisOn,
        genesisSpan = span,
        genesisT01 = t01,
        playingChatId = playing,
        selectedId = selected,
        labelMinPx = 24f,
        labelTextPx = 30f,
        discoveredIds = known,
        arcs = arcs,
        nowMs = nowMs,
        nebulae = nebulae,
        emissionFogs = emissionFogs,
        spread = spread,
        spreadCursorMs = spreadCursorMs,
        constellation = constellation,
        showConstellation = showConstellation,
        ship = ship,
        shipStyle = shipStyle,
        wormholeP01 = wormholeP01,
        revealIds = revealIds,
        linkLines = linkLines,
    )

    private fun opTypes(b: GlFrameBuilder): List<Int> = (0 until b.opCount).map { b.opType[it] }

    // ==================== 天幕 / 网格 ====================

    @Test
    fun `天幕 GPU 化（空宇宙也有 OP_SKY 且为首个 op——CPU 投影归零锚）`() {
        val b = GlFrameBuilder()
        b.build(frame(emptyList()), w, h)
        assertEquals("R281：天幕=静态 VBO+GPU 投影（op 首位）", GlFrameBuilder.OP_SKY, b.opType[0])
        assertEquals("天幕不再产 CPU 点（空宇宙点批为零）", 0, b.pointCount)
        // 网格线产出（y=-300 平面 18 线——原点平视可见一部分）
        assertTrue("网格应产线", opTypes(b).contains(GlFrameBuilder.OP_LINES))
    }

    // ==================== R349 形态线三态路由（全类同尺——r341 政策表；R355 dp 化 mdpi 档基准） ====================

    @Test
    fun `形态线三态路由（微星小于1_2 并点云_blend 带双态_低模球 1_6 到 4_全模大于等于 4）`() {
        // R355 dp 化（缺省 lodDensity=1=mdpi 恒等档）：点档 <1.2px / blend [1.2,1.6) /
        // 低模 [1.6,4) / 全模 ≥4（用户终裁全模型门槛降 4dp）
        val stars = listOf(
            star(1L, 0f, 0f, 500f, bodyR = 40f),   // r=80.5 → 全模球
            star(2L, 30f, 0f, 500f, bodyR = 3f * 500f / focal),  // r=3 → 低模球（旧 30→2 最大宗）
            star(3L, -30f, 0f, 500f, bodyR = 1.4f * 500f / focal),  // r=1.4 → blend 带（点+低模球）
            star(4L, -60f, 0f, 500f, bodyR = 1.1f * 500f / focal),  // r=1.1 → 微星并点云
        )
        val b = GlFrameBuilder()
        b.build(frame(stars), w, h)
        assertEquals(4, b.statVisibleStars)
        assertEquals("全模+低模球总账（statSphereStars 不分档合计）", 3, b.statSphereStars)
        assertEquals("全模=1（≥4px 逐实例 uniform 路径）", 1, b.statSphereFull)
        assertEquals("低模=2（blend 带 1.4px+solid 3px 双实例）", 2, b.statSphereLow)
        assertEquals(1, b.statMicroStars)
        assertEquals("blend 带双绘对=1", 1, b.statBlendPairs)
        assertEquals("低模球实例数", 2, b.lowSphereCount)
        // 全模世界半径反解=bodyR（r=bodyR×focal/depth 的逆运算闭环）
        assertEquals(40f, b.sphereBuf[3], 0.1f)
        // 低模世界半径反解同式（3px 星 bodyR=3×500/focal）
        var lowFound = false
        for (i in 0 until b.lowSphereCount) {
            if (kotlin.math.abs(b.lowSphereBuf[i * GlFrameBuilder.SPHERE_I_STRIDE + 3] - 3f * 500f / focal) < 0.1f) lowFound = true
        }
        assertTrue("低模球世界径闭环（≈bodyR）", lowFound)
        // OP_SPHERE_I 批 op 在场（攒批一 draw/连续段）
        assertTrue("低模 instanced op 在场", opTypes(b).contains(GlFrameBuilder.OP_SPHERE_I))
    }

    @Test
    fun `形态线 mesh 边界（4px 低模到全模换轨_全模档无低模实例）`() {
        val stars = listOf(
            star(11L, -60f, 0f, 500f, bodyR = 3.9f * 500f / focal),  // r=3.9 → 低模
            star(12L, 60f, 0f, 500f, bodyR = 4.1f * 500f / focal),   // r=4.1 → 全模
        )
        val b = GlFrameBuilder()
        b.build(frame(stars), w, h)
        assertEquals(2, b.statSphereStars)
        assertEquals("r=3.9 走低模批", 1, b.statSphereLow)
        assertEquals("r=4.1 走全模逐实例", 1, b.statSphereFull)
        assertEquals(1, b.lowSphereCount)
        assertEquals(1, b.sphereCount)
    }

    // ==================== R355 密度档矩阵（dp 化消费面——跨密度视觉一致性 JVM 半体） ====================

    @Test
    fun `R355 密度档矩阵（同 dp 半径同路由——小米 400dpi 与 mdpi 形态一致）`() {
        // 宪法「分辨出形状=3D」的「形状」=视觉尺寸非设备像素：同一 dp 半径在
        // mdpi 与 400dpi（density=2.5）上落同一形态档/同一 mesh 档（V55 两密度档
        // statLodLine 一致性断言的 JVM 半体——用户「小米上根本不是 3D 效果」的
        // 病根=旧裸 px 阈值让 400dpi 上 3D 出现时机失真的回归锚）
        val d = 2.5f
        fun dp(v: Float): Float = v * d
        // 四案同 dp 半径：0.6dp 微星 / 1.4dp blend / 2dp 低模 / 5dp 全模
        // （0.6dp>0.55px 帧裁下限——裁剪线为亚感知 px 地板非形态线档，域外注记）
        val mdpi = listOf(
            star(91L, 0f, 0f, 500f, bodyR = 0.6f * 500f / focal),
            star(92L, 30f, 0f, 500f, bodyR = 1.4f * 500f / focal),
            star(93L, -30f, 0f, 500f, bodyR = 2f * 500f / focal),
            star(94L, 60f, 0f, 500f, bodyR = 5f * 500f / focal),
        )
        val xhdpi = listOf(
            star(91L, 0f, 0f, 500f, bodyR = dp(0.6f) * 500f / focal),
            star(92L, 30f, 0f, 500f, bodyR = dp(1.4f) * 500f / focal),
            star(93L, -30f, 0f, 500f, bodyR = dp(2f) * 500f / focal),
            star(94L, 60f, 0f, 500f, bodyR = dp(5f) * 500f / focal),
        )
        val b1 = GlFrameBuilder() // mdpi 恒等档（缺省 lodDensity=1）
        b1.build(frame(mdpi), w, h)
        val b2 = GlFrameBuilder()
        b2.lodDensity = d // 400dpi：阈值 px=3/4/10（1.2/1.6/4dp×2.5）
        b2.build(frame(xhdpi), w, h)
        // 形态四案逐位一致（微星/blend对/低模/全模——设备间 3D 出现时机一致的本体）
        assertEquals("微星并点同 dp 同档", b1.statMicroStars, b2.statMicroStars)
        assertEquals("blend 对同 dp 同档", b1.statBlendPairs, b2.statBlendPairs)
        assertEquals("低模球同 dp 同档", b1.statSphereLow, b2.statSphereLow)
        assertEquals("全模球同 dp 同档", b1.statSphereFull, b2.statSphereFull)
        assertEquals(1, b1.statMicroStars)
        assertEquals(1, b1.statBlendPairs)
        assertEquals("低模账=blend 带球+solid 球两颗", 2, b1.statSphereLow)
        assertEquals(1, b1.statSphereFull)
        // 旧口径病根反向锚（px 裸值时代的设备间分叉）：同 px 半径 2px 在 mdpi=球
        // （2≥1.6）、在 400dpi=点档（2<3.0=1.2dp 视觉上不分辨形状）——dp 化后
        // 差异=视觉口径而非设备运气
        val px2mdpi = star(95L, 0f, 0f, 500f, bodyR = 2f * 500f / focal)
        val px2xhdpi = star(95L, 0f, 0f, 500f, bodyR = 2f * 500f / focal)
        val c1 = GlFrameBuilder()
        c1.build(frame(listOf(px2mdpi)), w, h)
        val c2 = GlFrameBuilder()
        c2.lodDensity = d
        c2.build(frame(listOf(px2xhdpi)), w, h)
        assertEquals("mdpi 2px=1_6dp 球（3D 真身）", 1, c1.statSphereLow)
        assertEquals("400dpi 2px=0_8dp 点（亚像素安静色点——不再提前变球）", 0, c2.statSphereStars + c2.statSphereLow)
    }

    @Test
    fun `真球屏位闭环（球心世界位=星位 世界径反解——R345 GL 唯一口径）`() {
        val s = star(7L, 120f, -60f, 500f, bodyR = 40f)
        val b = GlFrameBuilder()
        b.build(frame(listOf(s)), w, h)
        assertEquals(1, b.sphereCount)
        assertEquals(120f, b.sphereBuf[0], 0.01f)
        assertEquals(-60f, b.sphereBuf[1], 0.01f)
        assertEquals(500f, b.sphereBuf[2], 0.01f)
        // worldR × focal / depth = px 半径（pass1 的 r）——闭环：40×1006.86/500≈80.5px
        assertEquals(80.5f, b.sphereBuf[3] * focal / 500f, 0.5f)
    }

    // ==================== R349 blend 带（1.2~1.6dp 点→3D 能量守恒交接——R339 族重锚；R355 dp 化+真 3D 即刻生效） ====================

    @Test
    fun `R349 blend 带（1_2 到 1_6 点淡出球淡入能量守恒_真3D即刻生效_半径连续三态）`() {
        // 三态同带对照（mdpi 档）：r≈1.1 纯点 / r≈1.4 带内双形态 / r≈2.5 纯低模球
        val below = star(21L, -120f, 0f, 500f, bodyR = 1.1f * 500f / focal)
        val inside = star(22L, 0f, 0f, 500f, bodyR = 1.4f * 500f / focal)
        val above = star(23L, 120f, 0f, 500f, bodyR = 2.5f * 500f / focal)
        val b = GlFrameBuilder()
        b.build(frame(listOf(below, inside, above)), w, h)
        // 纯点（r<1.2）：零球；纯球（r≥1.6）：零点残影；带内双形态都在
        assertEquals("球总账=带内+纯球两颗", 2, b.statSphereStars)
        assertEquals("blend 双绘对=1", 1, b.statBlendPairs)
        // 带内星的点：size=2r（半径连续——与球投影直径同径交接）+coreRatio=
        // coreSwellCoef（核点膨胀 0.26→1.0——「放大时核点膨胀成球」的公式本体消费）
        var insidePoint: Int = -1
        var insideR = 0f
        for (i in 0 until b.pointCount) {
            val size = b.pointBuf[i * GlFrameBuilder.POINT_STRIDE + 2]
            if (abs(size - 1.4f * 2f) < 0.3f) {
                insidePoint = i
                insideR = size / 2f
            }
        }
        assertTrue("带内星应产点（点淡出在场）", insidePoint >= 0)
        assertEquals(TasteStarGlow.coreSwellCoef(insideR), b.pointBuf[insidePoint * GlFrameBuilder.POINT_STRIDE + 3], 0.01f)
        assertTrue("核膨胀中段应大于点核 0.26", TasteStarGlow.coreSwellCoef(insideR) > 0.26f)
        // 能量守恒精确锚（R339 族同式重锚——R355 终裁 blend 只留点淡出侧）：
        // 球 alpha=k·α 与点 alpha=0.92·(1−k)·α 同源同一 α（twinkle 公因子约掉——
        // k·0.92·(1−k)·α 两式恒等）
        var insideLow = -1
        for (k in 0 until b.lowSphereCount) {
            if (abs(b.lowSphereBuf[k * GlFrameBuilder.SPHERE_I_STRIDE + 3] * focal / 500f - 1.4f) < 0.2f) {
                insideLow = k
            }
        }
        assertTrue("带内星应产低模球（球淡入在场）", insideLow >= 0)
        val kk = dev.tgfeed.core.tastemap.TasteLodPolicy.blend01(insideR)
        val aSphere = b.lowSphereBuf[insideLow * GlFrameBuilder.SPHERE_I_STRIDE + 4]
        val aPoint = b.pointBuf[insidePoint * GlFrameBuilder.POINT_STRIDE + 7]
        assertTrue(
            "点淡出球淡入能量守恒（无亮峰无凹陷）：k=$kk",
            kotlin.math.abs(aSphere * 0.92f * (1f - kk) - aPoint * kk) < 1e-4f,
        )
        // R355 终裁：ambient 平涂退役=槽 22 恒 0（**真 3D 即刻生效**——带内球=真
        // 球面明暗从第一像素在场；球面明暗签名中心/边缘 ≥1.8 的装配半体：着色
        // 参数三段色/limbK 照常装填=shader 侧平盘路径已绝（R355WiringSourceTest 源锚）
        assertEquals(
            "带内球 ambient 退役位恒 0（平盘期彻底删除）",
            0f,
            b.lowSphereBuf[insideLow * GlFrameBuilder.SPHERE_I_STRIDE + 22],
            1e-6f,
        )
        var ambientAbove = -1f
        for (k in 0 until b.lowSphereCount) {
            if (abs(b.lowSphereBuf[k * GlFrameBuilder.SPHERE_I_STRIDE + 3] * focal / 500f - 2.5f) < 0.2f) {
                ambientAbove = b.lowSphereBuf[k * GlFrameBuilder.SPHERE_I_STRIDE + 22]
            }
        }
        assertEquals("纯球档槽 22 同恒 0（全档一致——R321 无条件写纪律）", 0f, ambientAbove, 1e-6f)
        // 1.6dp 界外纯球零点残影（pointFadeK 归零——r≥球门无点）
        var abovePoint = false
        for (i in 0 until b.pointCount) {
            if (abs(b.pointBuf[i * GlFrameBuilder.POINT_STRIDE + 2] - 2.5f * 2f) < 1f) abovePoint = true
        }
        assertFalse("r≥1_6dp 纯球零点残影（pointFadeK 让位归零）", abovePoint)
        // 微星层不动负锚：r<1.2 纯点（below 星零球）
        var belowSphere = false
        for (k in 0 until b.lowSphereCount) {
            if (abs(b.lowSphereBuf[k * GlFrameBuilder.SPHERE_I_STRIDE + 3] * focal / 500f - 1.1f) < 0.2f) belowSphere = true
        }
        assertFalse("r<1_2dp 纯点零球（点档零污染）", belowSphere)
    }

    @Test
    fun `R349 回退序演练（bias 阈值上抬带拉宽_帽收缩最小屏径优先出球籍_全低模）`() {
        // ①阈值上抬：floor 1.6→2.4——2px 星从纯球回带内（点+球双态=经 blend 回点档；
        // R355 dp 化后 bias 与密度正交——回退比例恒 1.5）
        val s20 = star(31L, 0f, 0f, 500f, bodyR = 2f * 500f / focal)
        val b0 = GlFrameBuilder()
        b0.build(frame(listOf(s20)), w, h)
        assertEquals("常态 2px=纯低模球（≥1_6dp）", 1, b0.statSphereLow)
        assertEquals("常态无 blend 对", 0, b0.statBlendPairs)
        val b1 = GlFrameBuilder()
        b1.lodSolidBias = 1.5f
        b1.build(frame(listOf(s20)), w, h)
        assertEquals("回退① 2px 回带内（双态在场——floor=2_4）", 1, b1.statBlendPairs)
        assertTrue("回退① 仍有球淡入（k<1）", b1.statSphereLow == 1)
        // ③实例帽收缩：两颗低模候选（r=3/2），帽=1 → 最小屏径者出球籍回点档
        val s6 = star(32L, -60f, 0f, 500f, bodyR = 3f * 500f / focal)
        val s2 = star(33L, 60f, 0f, 500f, bodyR = 2f * 500f / focal)
        val b2 = GlFrameBuilder()
        b2.build(frame(listOf(s6, s2)), w, h)
        assertEquals("常态两颗都成球", 2, b2.statSphereLow)
        val b3 = GlFrameBuilder()
        b3.lodInstanceCap = 1
        b3.build(frame(listOf(s6, s2)), w, h)
        assertEquals("③收缩帽=1：只有一球", 1, b3.statSphereLow)
        assertEquals("最小屏径者出球籍（截断记账=1）", 1, b3.statLodDropped)
        // 被踢星=回点档全亮点（α×0.92 非 ×(1−k)——帽界换籍 ≤8% 阶差的机制锚）
        var kickedPoint = false
        for (i in 0 until b3.pointCount) {
            if (abs(b3.pointBuf[i * GlFrameBuilder.POINT_STRIDE + 2] - 2f * 2f) < 1f &&
                b3.pointBuf[i * GlFrameBuilder.POINT_STRIDE + 7] > 0.7f
            ) {
                kickedPoint = true
            }
        }
        assertTrue("被踢星回点档全亮（α×0.92——非隐形非半暗）", kickedPoint)
        // ②全低模：4dp+ 星降 8×6 批档（meshBias=∞——「全模型门槛」整线抬没）
        val s9 = star(34L, 0f, 0f, 500f, bodyR = 9f * 500f / focal)
        val b4 = GlFrameBuilder()
        b4.build(frame(listOf(s9)), w, h)
        assertEquals("常态 9px=全模逐实例（≥4dp）", 1, b4.statSphereFull)
        val b5 = GlFrameBuilder()
        b5.lodMeshBias = Float.POSITIVE_INFINITY
        b5.build(frame(listOf(s9)), w, h)
        assertEquals("回退② 9px 降全低模", 1, b5.statSphereLow)
        assertEquals(0, b5.statSphereFull)
    }

    @Test
    fun `R339 星芒门（亮度 top 帽 5——点形态域 1_2 到 1_6；top1 六芒加冕 其余四芒 未选零芒 芒长∝亮度）`() {
        // 7 亮度降序点形态星（R349 形态线/R355 dp 化 mdpi 档：r≈1.4 ∈[1.2,1.6)
        // blend 带点档侧——resolved 无芒=≥1.6 球不参选）+1 暗小点星：top-5 入选，
        // 后两名+暗星零芒
        val stars = buildList {
            for (i in 0 until 7) {
                add(star(100L + i, (i - 3) * 40f, 0f, 500f, bodyR = 1.4f * 500f / focal, alpha = 0.90f - i * 0.08f))
            }
            add(star(200L, 0f, 200f, 500f, bodyR = 1.4f * 500f / focal, alpha = 0.30f))
        }
        val b = GlFrameBuilder()
        b.build(frame(stars), w, h)
        // 候选=点形态个体星（r≥1.2 且 r<1.6——8 颗全入域）；入选=帽 5 截断
        assertEquals(8, b.statSpikeCand)
        assertEquals(5, b.statSpikeStars)
        // TEX_SPIKE quad 账：top1 六芒（3 quad）+4 星四芒（2 quad）=11 张
        var spikeQuads = 0
        for (op in 0 until b.opCount) {
            if (b.opType[op] == GlFrameBuilder.OP_QUADS && b.opA[op] == GlFrameBuilder.TEX_SPIKE) {
                spikeQuads += b.opC[op]
            }
        }
        assertEquals("top1 六芒加冕+其余四芒=3+4×2 张 TEX_SPIKE", 11, spikeQuads)
        // 芒长∝亮度：top1（b01=1）=26px 满帽；入选第 5 名（b01=0.58/0.90）∈(10,26)
        // 且小于 top1；六芒（top1）3 张同长 26px
        val lens = mutableListOf<Float>()
        for (op in 0 until b.opCount) {
            if (b.opType[op] == GlFrameBuilder.OP_QUADS && b.opA[op] == GlFrameBuilder.TEX_SPIKE) {
                for (q in b.opB[op] until b.opB[op] + b.opC[op]) {
                    val o = q * 6 * GlFrameBuilder.QUAD_STRIDE
                    val dx = b.quadBuf[o + GlFrameBuilder.QUAD_STRIDE] - b.quadBuf[o]
                    val dy = b.quadBuf[o + GlFrameBuilder.QUAD_STRIDE + 1] - b.quadBuf[o + 1]
                    lens.add(kotlin.math.sqrt(dx * dx + dy * dy) / 2f)
                }
            }
        }
        assertEquals(TasteStarGlow.spikeLenPx(1f), lens.max(), 0.01f)
        assertTrue("门内最短芒应短于 top1（芒长∝亮度）", lens.min() < lens.max() - 2f)
        assertTrue("芒长下限 10px", lens.min() >= TasteStarGlow.SPIKE_LEN_MIN_PX - 0.01f)
        // 眩光点：5 颗 size=4px（GLARE_R_PX×2）coreRatio=0.5 白热核
        var glareN = 0
        for (i in 0 until b.pointCount) {
            if (abs(b.pointBuf[i * GlFrameBuilder.POINT_STRIDE + 2] - TasteStarGlow.GLARE_R_PX * 2f) < 0.01f &&
                abs(b.pointBuf[i * GlFrameBuilder.POINT_STRIDE + 3] - 0.5f) < 0.01f
            ) {
                glareN++
            }
        }
        assertEquals("5 颗眩光点（≤2px 白热饱和核）", 5, glareN)
    }

    @Test
    fun `R339 星芒让位与域外（resolved 零芒——球档不参选；blend 带随 pointFadeK 让位 微星不拆云）`() {
        // 球满档亮星（r=80.5）与低模球星（r≈3）在场但不参选（resolved 无芒——
        // r341 §5.2 物理立规）；blend 带星（r≈1.4）参选、芒 alpha×pointFadeK
        // （≈0.5 档半亮=让位系数消费）；微星（r≈1.0）并点云不拆云不参选
        // （R355 dp 化 mdpi 档窗 [1.2,1.6)）
        val ball = star(31L, 0f, 0f, 500f, bodyR = 40f)
        val low = star(34L, -240f, 0f, 500f, bodyR = 1.5f)
        val win = star(32L, 120f, 0f, 500f, bodyR = 1.4f * 500f / focal)
        val micro = star(33L, -120f, 0f, 500f, bodyR = 0.5f)
        val b = GlFrameBuilder()
        b.build(frame(listOf(ball, low, win, micro)), w, h)
        assertEquals("候选域=点形态个体星（球/低模=resolved 非点源+微星点云不参选）", 1, b.statSpikeCand)
        assertEquals(1, b.statSpikeStars)
        assertEquals(1, b.statMicroStars)
        val winX = w / 2f + 120f * focal / 500f
        var winSpikeA = 0f
        var spikeN = 0
        var nearBall = false
        var nearLow = false
        val lowX = w / 2f + (-240f) * focal / 500f
        for (op in 0 until b.opCount) {
            if (b.opType[op] == GlFrameBuilder.OP_QUADS && b.opA[op] == GlFrameBuilder.TEX_SPIKE) {
                for (q in b.opB[op] until b.opB[op] + b.opC[op]) {
                    spikeN++
                    val o = q * 6 * GlFrameBuilder.QUAD_STRIDE
                    val cxq = (b.quadBuf[o] + b.quadBuf[o + GlFrameBuilder.QUAD_STRIDE]) / 2f
                    val a = b.quadBuf[o + 7]
                    if (kotlin.math.abs(cxq - winX) < 40f && a > winSpikeA) winSpikeA = a
                    if (kotlin.math.abs(cxq - w / 2f) < 40f) nearBall = true
                    if (kotlin.math.abs(cxq - lowX) < 40f) nearLow = true
                }
            }
        }
        assertEquals("blend 带星芒在场（唯一候选=top1 六芒加冕 3 quad 双臂）", 3, spikeN)
        assertTrue("blend 带星芒半亮（pfa≈0.5——pointFadeK 让位系数消费）", winSpikeA > 0.1f)
        assertFalse("球满档星零芒（resolved 无芒——pointFadeK 让位归零红旗反向锚）", nearBall)
        assertFalse("低模球星零芒（resolved 无芒——2~8px 球同门）", nearLow)
    }

    // ==================== 画家序 ====================

    @Test
    fun `画家序 远星先画（真球 op 流按深度降序）`() {
        val far = star(11L, -100f, 0f, 800f, bodyR = 30f)  // r=37.8 → 球
        val near = star(12L, 100f, 0f, 400f, bodyR = 20f)  // r=50.3 → 球
        val b = GlFrameBuilder()
        b.build(frame(listOf(near, far)), w, h) // 输入故意近先远后
        val sphereOps = (0 until b.opCount).filter { b.opType[it] == GlFrameBuilder.OP_SPHERE }
        assertEquals(2, sphereOps.size)
        val firstSlot = b.opA[sphereOps[0]]
        val secondSlot = b.opA[sphereOps[1]]
        assertEquals("远星（worldR=30）应先画", 30f, b.sphereBuf[firstSlot * GlFrameBuilder.SPHERE_STRIDE + 3], 0.1f)
        assertEquals("近星（worldR=20）应后画", 20f, b.sphereBuf[secondSlot * GlFrameBuilder.SPHERE_STRIDE + 3], 0.1f)
    }

    @Test
    fun `排序配对 身份与世界位同槽（真球参数不错挂到别的星位上）`() {
        // 红远星 vs 蓝近星——球实例的世界位与三段色必须同星同源
        // （sortDesc 槽位/星索引口径疑案的防回归锚——见 r277_notes 裁决节；R345 后 GL 私有镜像为唯一实现）
        val farRed = star(13L, -100f, 0f, 800f, bodyR = 30f, color = 0xFFFF3020.toInt())
        val nearBlue = star(14L, 100f, 0f, 400f, bodyR = 20f, color = 0xFF2040FF.toInt())
        val b = GlFrameBuilder()
        b.build(frame(listOf(nearBlue, farRed)), w, h)
        val sphereOps = (0 until b.opCount).filter { b.opType[it] == GlFrameBuilder.OP_SPHERE }
        assertEquals(2, sphereOps.size)
        val first = b.opA[sphereOps[0]] * GlFrameBuilder.SPHERE_STRIDE
        // 首画=远星：世界位 z=800 ∧ 星色通道（sphereBuf[8..10]）红 dominant
        assertEquals(800f, b.sphereBuf[first + 2], 0.5f)
        assertEquals(-100f, b.sphereBuf[first], 0.5f)
        val rCh = b.sphereBuf[first + 8]
        val bCh = b.sphereBuf[first + 10]
        assertTrue("远星是红星（红通道强于蓝通道）", rCh > bCh)
        val second = b.opA[sphereOps[1]] * GlFrameBuilder.SPHERE_STRIDE
        assertEquals(400f, b.sphereBuf[second + 2], 0.5f)
        assertEquals(100f, b.sphereBuf[second], 0.5f)
        assertTrue("近星是蓝星", b.sphereBuf[second + 10] > b.sphereBuf[second + 8])
    }

    // ==================== 名签候选 ====================

    @Test
    fun `名签候选 已知星∧半径过门∧题名非空才入选`() {
        val big = star(21L, 0f, 0f, 500f, bodyR = 40f, title = "频道甲")
        val small = star(22L, 100f, 0f, 500f, bodyR = 8f, title = "频道乙") // r=16 < 24 门
        val blank = star(23L, -100f, 0f, 500f, bodyR = 40f, title = "")
        val b = GlFrameBuilder()
        b.build(frame(listOf(big, small, blank), known = setOf(21L, 22L, 23L)), w, h)
        assertEquals(1, b.labelCands.size)
        assertEquals("频道甲", b.labelCands[0].text)
    }

    @Test
    fun `名签候选 未知星无名（神秘感）+ cap 8（半径大者优先）`() {
        val b = GlFrameBuilder()
        // 未知星（不在 known）→ 无名签
        b.build(frame(listOf(star(31L, 0f, 0f, 500f, bodyR = 40f, title = "未知星")), known = emptySet()), w, h)
        assertEquals(0, b.labelCands.size)
        // 10 颗已知大星 → cap 8，淘汰最小的两颗
        val stars = (0 until 10).map { i ->
            star(100L + i, (i - 5) * 60f, 0f, 500f, bodyR = 40f + i, title = "星$i")
        }
        b.build(frame(stars, known = (100L until 110L).toSet()), w, h)
        assertEquals(8, b.labelCands.size)
        assertTrue("半径小的应被 cap 淘汰", labelCandsTexts(b).none { it == "星0" || it == "星1" })
    }

    private fun labelCandsTexts(b: GlFrameBuilder): List<String> = b.labelCands.map { it.text }

    // ==================== 创世纪 / 吞没 ====================

    @Test
    fun `创世纪 未诞生星不装配（光标前的星零产出）`() {
        val span = TasteFlight.GenesisSpan(1000L, 2000L)
        val born = star(41L, -30f, 0f, 500f, bodyR = 1f, firstSeenMs = 1200L)   // 已诞生
        val unborn = star(42L, 30f, 0f, 500f, bodyR = 40f, firstSeenMs = 1800L) // 光标 1500 后诞生
        val b = GlFrameBuilder()
        b.build(frame(listOf(born, unborn), genesisOn = true, span = span, t01 = 0.5f), w, h)
        assertEquals("pass1 两颗都过投影", 2, b.statVisibleStars)
        assertEquals("未诞生大星不得成球", 0, b.sphereCount)
        assertEquals("创世纪模式不走微星合并", 0, b.statMicroStars)
    }

    @Test
    fun `吞没渐隐 贴脸巨星 alpha 归零且半径钳阈`() {
        // bodyR=4000 depth=100 → r=40274px ≫ 阈（短边 1080×0.35=378）→ alpha=0
        val huge = star(51L, 0f, 0f, 100f, bodyR = 4000f)
        val b = GlFrameBuilder()
        b.build(frame(listOf(huge)), w, h)
        assertEquals(1, b.sphereCount)
        assertTrue("贴脸巨星应渐隐归零", b.sphereBuf[4] < 0.001f)
        // 世界径=钳后 378px 反解（378×100/1006.86≈37.5）
        assertEquals(37.5f, b.sphereBuf[3], 0.5f)
    }

    // ==================== 边 / 弧 ====================

    @Test
    fun `互链边 产线段且焦点两遍制加亮`() {
        val s1 = star(61L, -100f, 0f, 500f, bodyR = 5f)
        val s2 = star(62L, 100f, 0f, 500f, bodyR = 5f)
        val edge = TasteFxEdge(-100f, 0f, 500f, 100f, 0f, 500f, 61L, 62L, 0.4f)
        val b0 = GlFrameBuilder()
        b0.build(frame(listOf(s1, s2)), w, h)
        val b1 = GlFrameBuilder()
        b1.build(frame(listOf(s1, s2), edges = listOf(edge)), w, h)
        assertEquals("一条边=一对线段顶点（网格同增量对比）", 2, b1.lineVertCount - b0.lineVertCount)
    }

    @Test
    fun `关联弧 虚线折线产出（创世纪开即熄）`() {
        val arc = TasteArc(-100f, 0f, 500f, 100f, 0f, 500f, 62L, 0xFF8899FF.toInt(), 0.8f, 61L)
        val b0 = GlFrameBuilder()
        b0.build(frame(emptyList()), w, h)
        val b1 = GlFrameBuilder()
        b1.build(frame(emptyList(), arcs = listOf(arc)), w, h)
        assertTrue("弧 20 段采样应产线（虚线取舍后仍有过半段）", b1.lineVertCount - b0.lineVertCount >= 20)
        val b2 = GlFrameBuilder()
        b2.build(
            frame(
                emptyList(), arcs = listOf(arc),
                genesisOn = true, span = TasteFlight.GenesisSpan(1000L, 2000L), t01 = 0.5f,
            ),
            w, h,
        )
        assertEquals("创世纪开=弧不画（R345 GL 唯一口径）", 0, b2.lineVertCount - b0.lineVertCount)
    }

    // ==================== R314 互链连线治理（默认不划线+焦点引力弧） ====================

    @Test
    fun `R314 连线关-无焦点零产出 有焦点仅其互链成引力弧段`() {
        val s1 = star(61L, -100f, 0f, 500f, bodyR = 5f)
        val s2 = star(62L, 100f, 0f, 500f, bodyR = 5f)
        val s3 = star(63L, 0f, 100f, 500f, bodyR = 5f)
        val e12 = TasteFxEdge(-100f, 0f, 500f, 100f, 0f, 500f, 61L, 62L, 0.4f)
        val e23 = TasteFxEdge(100f, 0f, 500f, 0f, 100f, 500f, 62L, 63L, 0.6f)
        val b0 = GlFrameBuilder()
        b0.build(frame(listOf(s1, s2, s3)), w, h)
        // 关态无焦点：两条边零产出（全景零划线）
        val b1 = GlFrameBuilder()
        b1.build(frame(listOf(s1, s2, s3), edges = listOf(e12, e23), linkLines = false), w, h)
        assertEquals("关态无焦点=零划线", 0, b1.lineVertCount - b0.lineVertCount)
        // 关态焦点=61：仅 e12 成弧（20 段=40 顶点），e23 不沾焦点不画
        val b2 = GlFrameBuilder()
        b2.build(
            frame(listOf(s1, s2, s3), edges = listOf(e12, e23), selected = 61L, linkLines = false),
            w, h,
        )
        assertEquals(
            "焦点星互链=20 段引力弧（段数口径 TasteLinkLinesPolicy）",
            2 * dev.tgfeed.core.tastemap.TasteLinkLinesPolicy.FOCUS_ARC_SEGMENTS,
            b2.lineVertCount - b0.lineVertCount,
        )
        // 延时摄影期：引力弧同关联弧门径不画
        val b3 = GlFrameBuilder()
        b3.build(
            frame(
                listOf(s1, s2, s3), edges = listOf(e12, e23), selected = 61L, linkLines = false,
                genesisOn = true, span = TasteFlight.GenesisSpan(1000L, 2000L), t01 = 1f,
            ),
            w, h,
        )
        assertEquals("延时摄影期=引力弧不画", 0, b3.lineVertCount - b0.lineVertCount)
    }

    @Test
    fun `R314 连线开-旧网状行为逐位（直线非弧段）`() {
        val s1 = star(61L, -100f, 0f, 500f, bodyR = 5f)
        val s2 = star(62L, 100f, 0f, 500f, bodyR = 5f)
        val edge = TasteFxEdge(-100f, 0f, 500f, 100f, 0f, 500f, 61L, 62L, 0.4f)
        val b0 = GlFrameBuilder()
        b0.build(frame(listOf(s1, s2)), w, h)
        // 开态无焦点：一条边=一对线段顶点（直线，非 20 段弧）
        val b1 = GlFrameBuilder()
        b1.build(frame(listOf(s1, s2), edges = listOf(edge), linkLines = true), w, h)
        assertEquals("开态=旧直线网（一边两顶点）", 2, b1.lineVertCount - b0.lineVertCount)
        // 开态有焦点：两遍制（普通遍零（唯一边是焦点边）+焦点加亮遍）仍=一直线
        val b2 = GlFrameBuilder()
        b2.build(frame(listOf(s1, s2), edges = listOf(edge), selected = 61L, linkLines = true), w, h)
        assertEquals("开态焦点=直线加亮非弧", 2, b2.lineVertCount - b0.lineVertCount)
    }

    // ==================== 黑洞（R281 真 3D 化） ====================

    @Test
    fun `黑洞 视界黑球+真 3D 吸积盘+盘粒子世界点齐`() {
        val bh = star(71L, 0f, 0f, 500f, bodyR = 40f, kind = CelestialKind.BLACK_HOLE)
        val b = GlFrameBuilder()
        b.build(frame(listOf(bh)), w, h)
        // 视界=纯黑真球（深度写——盘带后缘遮挡的几何闸）
        assertEquals("视界应成黑球", 1, b.sphereCount)
        assertEquals("黑球三段色全黑", 0f, b.sphereBuf[5], 0.001f)
        assertEquals("黑球 alpha=1（全遮）", 1f, b.sphereBuf[4], 0.001f)
        assertEquals("黑球颗粒零", 0f, b.sphereBuf[14], 0.001f)
        // 吸积盘=真 3D 环带 op（mode=1）
        assertEquals("吸积盘环带在场", 1, b.ringCount)
        assertEquals("环带模式=吸积盘", 1f, b.ringBuf[11], 0.001f)
        assertTrue("环带 op 在场", opTypes(b).contains(GlFrameBuilder.OP_RING))
        // 盘粒子=世界点 36 粒（深度测版——opC=1 被视界真遮挡）
        assertEquals("盘粒子 36 粒", 36, b.wpCount)
        assertTrue("盘粒子 op 带深度旗", (0 until b.opCount).any {
            b.opType[it] == GlFrameBuilder.OP_WPOINTS && b.opC[it] == 1
        })
        // 光子环/波纹=屏空间件保留
        val quadTexKeys = (0 until b.opCount)
            .filter { b.opType[it] == GlFrameBuilder.OP_QUADS }
            .map { b.opA[it] }
        assertTrue("光子环/波纹 TEX_RING 应在场", quadTexKeys.contains(GlFrameBuilder.TEX_RING))
        // 画家序：黑球在环带前（先写深度）
        val ops = opTypes(b)
        assertTrue(
            "视界应先于吸积盘（深度写→测序）",
            ops.indexOf(GlFrameBuilder.OP_SPHERE) < ops.indexOf(GlFrameBuilder.OP_RING),
        )
    }

    // ==================== 缓冲纪律 ====================

    @Test
    fun `缓冲复用 连装两帧零重分配（同一数组引用）且计数一致`() {
        val stars = listOf(
            star(81L, 0f, 0f, 500f, bodyR = 40f),
            star(82L, 30f, 0f, 500f, bodyR = 1f),
        )
        val b = GlFrameBuilder()
        b.build(frame(stars), w, h)
        val pb = b.pointBuf
        val lb = b.lineBuf
        val qb = b.quadBuf
        val c1 = b.pointCount
        val s1 = b.sphereCount
        b.build(frame(stars), w, h)
        assertSame(pb, b.pointBuf)
        assertSame(lb, b.lineBuf)
        assertSame(qb, b.quadBuf)
        assertEquals(c1, b.pointCount)
        assertEquals(s1, b.sphereCount)
    }

    @Test
    fun `op 流类型切换即冲刷 且同键四边形合并`() {
        // 微星（点，R349 并点线 1.5——r≈1.26/1.34 均入微星层）→ 大星（全模球）
        // → 微星（点）：op 流应呈现 点-球-点 的序（含低模批=攒批段冲刷纪律）
        val stars = listOf(
            star(91L, -200f, 0f, 800f, bodyR = 1f),   // r≈1.26 远微星
            star(92L, 0f, 0f, 500f, bodyR = 40f),    // 中全模球 r=80.5
            star(93L, 30f, 0f, 300f, bodyR = 0.4f),  // 近微星 r≈1.34
        )
        val b = GlFrameBuilder()
        b.build(frame(stars), w, h)
        val ops = opTypes(b)
        val sphereIdx = ops.indexOf(GlFrameBuilder.OP_SPHERE)
        assertTrue("球 op 应存在", sphereIdx >= 0)
        assertTrue("首位=天幕 GPU op（R281）", ops[0] == GlFrameBuilder.OP_SKY)
        // 球后的星点批（近微星）应在球 op 之后
        val lastPointOp = ops.indexOfLast { it == GlFrameBuilder.OP_POINTS }
        assertTrue("近微星点批应在球后（画家序）", lastPointOp > sphereIdx)
    }

    // ==================== R280 P1 特效层 ====================

    @Test
    fun `未探索星云 雾絮化 blob 错位叠（R351——blob 数单源+配比和恒 1_35 零增益+只缩不胀+同雾同絮闭环）`() {
        val neb = dev.tgfeed.core.tastemap.TasteNebula.NebulaCluster(
            cx = 0f, cy = 0f, cz = 800f, radius = 200f, colorArgb = 0xFF8855AA.toInt(),
            count = 7, dominant = "视频",
        )
        val b = GlFrameBuilder()
        b.build(frame(emptyList(), nebulae = listOf(neb)), w, h)
        val nebOps = (0 until b.opCount).filter { b.opType[it] == GlFrameBuilder.OP_NEBULA }
        // blob 数=core 单源（团位哈希折叠 Int→blobCountOf——同雾同絮铁律）
        val churn = neb.cx * 0.7f + neb.cy * 1.3f + neb.cz * 1.7f
        val flocSeed = FogFlocFx.flocSeedOf(churn)
        val blobCount = FogFlocFx.blobCountOf(flocSeed)
        assertEquals("blob 数=单源 blobCountOf（2~3 哈希）", blobCount, nebOps.size)
        assertTrue("blob 数域 2~3", blobCount in FogFlocFx.BLOB_COUNT_MIN..FogFlocFx.BLOB_COUNT_MAX)
        // 闭环重导（渲染端只画不算的验尸口——core 公式逐位重算比对装配输出）：
        // blob 世界位=雾心+前向 ±0.35r 深错位+世界系错位向（blobOffset01）
        val cam = TasteSpace.SpaceCam.ORIGIN
        val basis = TasteSpace.basis(cam.yawDeg, cam.pitchDeg, FloatArray(9))
        val fOff = FloatArray(3)
        val pack = FloatArray(FogFlocFx.BLOB_COUNT_MAX)
        FogFlocFx.blobAlphaPack01(flocSeed, pack, blobCount)
        val fogA = TasteNebula.fogAlpha(TasteSpace.distTo(cam, neb.cx, neb.cy, neb.cz))
        val projT = FloatArray(4)
        val rgbT = FloatArray(3)
        GlPalette.rgbF(neb.colorArgb, rgbT)
        var sum = 0f
        var maxHalf = 0f
        for (k in 0 until blobCount) {
            val depthOff = (if (k == 0) FogFlocFx.LAYER_DEPTH_OFF else -FogFlocFx.LAYER_DEPTH_OFF) * neb.radius
            FogFlocFx.blobOffset01(flocSeed, k, fOff)
            val lx = neb.cx + basis[0] * depthOff + fOff[0] * neb.radius
            val ly = neb.cy + basis[1] * depthOff + fOff[1] * neb.radius
            val lz = neb.cz + basis[2] * depthOff + fOff[2] * neb.radius
            assertTrue("闭环前提：blob 应在前方", TasteSpace.project(lx, ly, lz, cam, focal, w / 2f, h / 2f, projT, basis))
            val layerBase = if (k == 0) FogFlocFx.FRONT_R else FogFlocFx.BACK_R
            val halfExp = TasteNebula.fogPx(
                neb.radius * layerBase * FogFlocFx.blobRadius01(flocSeed, k), projT[3],
            )
            val i = b.opA[nebOps[k]] * GlFrameBuilder.NEB_STRIDE
            assertEquals("blob $k 屏位 x（core 重导闭环）", projT[0], b.nebBuf[i], 1e-3f)
            assertEquals("blob $k 屏位 y（core 重导闭环）", projT[1], b.nebBuf[i + 1], 1e-3f)
            assertEquals("blob $k 半径（层基×缩径系数——只缩不胀）", halfExp, b.nebBuf[i + 2], 1e-3f)
            assertEquals("blob $k churn 相位（seed+k×13_7）", churn + k * 13.7f, b.nebBuf[i + 3], 1e-4f)
            assertEquals("blob $k 亮档 rgb（普通雾=类别色直供）", rgbT[0], b.nebBuf[i + 5], 1e-4f)
            assertEquals("blob $k 亮档 g", rgbT[1], b.nebBuf[i + 6], 1e-4f)
            assertEquals("blob $k 亮档 b", rgbT[2], b.nebBuf[i + 7], 1e-4f)
            assertEquals("blob $k alpha=配比包（fBm 权归一）", fogA * pack[k], b.nebBuf[i + 8], 1e-4f)
            assertTrue("blob $k alpha 恒正", b.nebBuf[i + 8] > 0f)
            sum += b.nebBuf[i + 8]
            maxHalf = maxOf(maxHalf, b.nebBuf[i + 2])
        }
        assertEquals(
            "配比和恒 1_35（量级零增益红线——絮化只重分布不放大）",
            fogA * FogFlocFx.ALPHA_PACK_SUM,
            sum,
            1e-4f,
        )
        // 只缩不胀硬上界（几何最坏：后层 −0.35r+错位向全程前向 0.45r→深度 ≥800−70−90）
        val capPx = TasteNebula.fogPx(neb.radius * FogFlocFx.BACK_R, focal / 640f)
        assertTrue("blob 半径 ≤ 1_10r 最坏深度投影（外廓零外扩）：maxHalf=$maxHalf capPx=$capPx", maxHalf <= capPx + 0.5f)
        // 同雾同絮（确定性）：两跑逐槽同值——零 Random 零系统钟
        val b2 = GlFrameBuilder()
        b2.build(frame(emptyList(), nebulae = listOf(neb)), w, h)
        for (k in 0 until blobCount) {
            val i1 = b.opA[nebOps[k]] * GlFrameBuilder.NEB_STRIDE
            val i2 = b2.opA[nebOps[k]] * GlFrameBuilder.NEB_STRIDE
            for (s in 0 until GlFrameBuilder.NEB_STRIDE) {
                assertEquals("同雾同絮（blob $k 槽 $s 逐位）", b.nebBuf[i1 + s], b2.nebBuf[i2 + s], 0f)
            }
        }
    }

    @Test
    fun `发射星云雾絮化零回破（R351——两型同絮+亮档 alpha 帽联动2x雾帽+电离侧辉+星胚三单口 verbatim）`() {
        val neb = dev.tgfeed.core.tastemap.TasteNebula.NebulaCluster(
            cx = 60f, cy = 30f, cz = 800f, radius = 200f, colorArgb = 0xFF8855AA.toInt(),
            count = 7, dominant = "视频",
        )
        // O 型电离源（recent7=RECENT7_SATURATION 满档→O/B 判据单口出 O——电离侧辉原料）
        val ob = star(991L, 120f, 60f, 800f, bodyR = 40f, recent7 = 14)
        val fog = EmissionNebulaFx.Fog(
            emission = true, embryoCount = 3, seed = 12345, brightArgb = 0xFFB070D0.toInt(),
        )
        val b = GlFrameBuilder()
        b.build(frame(listOf(ob), nebulae = listOf(neb), emissionFogs = listOf(fog)), w, h)
        val churn = neb.cx * 0.7f + neb.cy * 1.3f + neb.cz * 1.7f
        val flocSeed = FogFlocFx.flocSeedOf(churn)
        val blobCount = FogFlocFx.blobCountOf(flocSeed)
        val nebOps = (0 until b.opCount).filter { b.opType[it] == GlFrameBuilder.OP_NEBULA }
        // 两型同絮：发射雾 blob 段与普通雾同一单源（r344 B 表「雾团（普通+发射）」行授权）
        assertEquals("发射雾 blob 数=同源 blobCountOf", blobCount, nebOps.size - 1) // −1=电离侧辉 op
        // 亮档 alpha 直供（emissionAlphaOf(a) else a——R323 零回破锚）×配比包
        val cam = TasteSpace.SpaceCam.ORIGIN
        val fogA = EmissionNebulaFx.emissionAlphaOf(
            TasteNebula.fogAlpha(TasteSpace.distTo(cam, neb.cx, neb.cy, neb.cz)),
        )
        assertTrue("发射档 alpha ≤联动帽 2xFOG_ALPHA_MAX（不抢恒星——R358 帽随雾帽单源联动）", fogA <= EmissionNebulaFx.EMISSION_ALPHA_CAP + 1e-6f)
        val rgbT = FloatArray(3)
        GlPalette.rgbF(fog.brightArgb, rgbT)
        var sum = 0f
        var glowOps = 0
        val glowA = EmissionNebulaFx.ionizationSideGlowAlpha(fogA, 1f)
        for (op in nebOps) {
            val i = b.opA[op] * GlFrameBuilder.NEB_STRIDE
            val a = b.nebBuf[i + 8]
            if (abs(a - glowA) < 1e-4f) { glowOps++; continue } // 电离侧辉 op（雾心口径屏向偏置）
            assertTrue("发射雾 blob alpha=配比包分量（≤帽内）", a <= EmissionNebulaFx.EMISSION_ALPHA_CAP + 1e-4f)
            assertEquals("发射雾亮档 rgb 直供（饱和度升烘焙色）", rgbT[0], b.nebBuf[i + 5], 1e-4f)
            assertEquals("发射雾亮档 g", rgbT[1], b.nebBuf[i + 6], 1e-4f)
            assertEquals("发射雾亮档 b", rgbT[2], b.nebBuf[i + 7], 1e-4f)
            sum += a
        }
        assertEquals("电离侧辉 op 恰一个（O/B 源侧偏置）", 1, glowOps)
        assertEquals(
            "发射雾配比和恒 1_35（亮档 fogA×配比包——絮化零增益跨两型）",
            fogA * FogFlocFx.ALPHA_PACK_SUM,
            sum,
            1e-4f,
        )
        // 星胚三单口 verbatim（零回破）：embryoCount 个点、alpha 域（0.05~0.10×电离调制）
        var embryos = 0
        for (p in 0 until b.pointCount) {
            val a = b.pointBuf[p * GlFrameBuilder.POINT_STRIDE + 7]
            if (a in 0.03f..EmissionNebulaFx.EMISSION_ALPHA_CAP + 1e-4f) embryos++
        }
        assertEquals("星胚点数=embryoCount（三单口原样）", fog.embryoCount, embryos)
    }

    @Test
    fun `连线流光 沿边产点（flowActive 大于零且边表非空）`() {
        val eng = TasteFlight.FxEngine()
        eng.setLinks(floatArrayOf(0.5f), 1)
        val edge = TasteFxEdge(-100f, 0f, 500f, 100f, 0f, 500f, 1L, 2L, 0.5f)
        val b0 = GlFrameBuilder()
        b0.build(frame(emptyList(), edges = listOf(edge)), w, h)
        val b1 = GlFrameBuilder()
        b1.build(frame(emptyList(), edges = listOf(edge), engine = eng), w, h)
        assertTrue("流光应产点", b1.pointCount > b0.pointCount)
    }

    @Test
    fun `尘埃带 源环+带线+粒子点三件齐（光标越首发）`() {
        val band = dev.tgfeed.ui.TasteSpreadBand(
            toChatId = 2L,
            ax = -100f, ay = 0f, az = 500f, bx = 100f, by = 0f, bz = 500f,
            cx = 0f, cy = -120f, cz = 500f, firstMs = 100L, itemCount = 3,
            particleMs = longArrayOf(100L, 200L, 300L),
            particleX = floatArrayOf(-60f, 0f, 60f),
            particleY = floatArrayOf(-40f, -80f, -40f),
            particleZ = floatArrayOf(500f, 500f, 500f),
            particleBucket = byteArrayOf(0, 1, 2),
        )
        val spread = dev.tgfeed.ui.TasteSpreadRender(
            tag = "tag甲", sourceChatId = 1L, sx = 0f, sy = 0f, sz = 500f,
            t0Ms = 0L, t1Ms = 1000L, colorArgb = 0xFFAA8855.toInt(),
            recalled = 9, channelCount = 2, sampled = false, bands = listOf(band),
        )
        val b = GlFrameBuilder()
        b.build(frame(emptyList(), spread = spread, spreadCursorMs = 500L), w, h)
        assertTrue("源首发脉冲环应在场", (0 until b.opCount).any {
            b.opType[it] == GlFrameBuilder.OP_QUADS && b.opA[it] == GlFrameBuilder.TEX_RING
        })
        assertTrue("带基底折线+粒子应产线与点", b.lineVertCount > 0 && b.pointCount >= 3)
    }

    @Test
    fun `星座轨迹 折线+站点点（n大于等于2）`() {
        val trail = dev.tgfeed.ui.TasteConstTrail(
            n = 3,
            xs = floatArrayOf(-80f, 0f, 80f), ys = floatArrayOf(0f, -30f, 0f),
            zs = floatArrayOf(500f, 500f, 500f), tsMs = longArrayOf(1L, 2L, 3L),
        )
        val b0 = GlFrameBuilder()
        b0.build(frame(emptyList()), w, h)
        val b1 = GlFrameBuilder()
        b1.build(frame(emptyList(), constellation = trail, showConstellation = true), w, h)
        assertTrue("轨迹应产线", b1.lineVertCount > b0.lineVertCount)
        assertTrue("站点+头光应产点", b1.pointCount >= b0.pointCount + 3)
    }

    @Test
    fun `飞船航迹 移动产流场线（黄金档粒子同发）`() {
        val ship = dev.tgfeed.core.tastemap.TasteShipFx.ShipTrailEngine()
        val focalPx = focal
        ship.stepSpace(TasteSpace.SpaceCam.ORIGIN, focalPx, 0.016f,
            dev.tgfeed.core.tastemap.TasteAchievements.ShipStyle.GOLD_PARTICLES, 1080f)
        ship.stepSpace(TasteSpace.SpaceCam(60f, 0f, 0f, 0f, 0f), focalPx, 0.016f,
            dev.tgfeed.core.tastemap.TasteAchievements.ShipStyle.GOLD_PARTICLES, 1080f)
        ship.stepSpace(TasteSpace.SpaceCam(120f, 0f, 0f, 0f, 0f), focalPx, 0.016f,
            dev.tgfeed.core.tastemap.TasteAchievements.ShipStyle.GOLD_PARTICLES, 1080f)
        val b0 = GlFrameBuilder()
        b0.build(frame(emptyList()), w, h)
        val b1 = GlFrameBuilder()
        b1.build(
            frame(emptyList(), ship = ship,
                shipStyle = dev.tgfeed.core.tastemap.TasteAchievements.ShipStyle.GOLD_PARTICLES),
            w, h,
        )
        assertTrue("流场 streak 应产线", b1.lineVertCount > b0.lineVertCount)
        assertTrue("金粒子应产点", b1.pointCount > b0.pointCount)
    }

    @Test
    fun `虫洞 暗幕四边形+双环（transit 中段最强——R348 C3 条纹退役零线）`() {
        val b0 = GlFrameBuilder()
        b0.build(frame(emptyList()), w, h)
        val b1 = GlFrameBuilder()
        b1.build(frame(emptyList(), wormholeP01 = 0.5f), w, h)
        val quadTexKeys = (0 until b1.opCount)
            .filter { b1.opType[it] == GlFrameBuilder.OP_QUADS }
            .map { b1.opA[it] }
        assertTrue("暗幕 TEX_WHITE 应在场", quadTexKeys.contains(GlFrameBuilder.TEX_WHITE))
        assertTrue("隧道环 TEX_RING 应在场", quadTexKeys.contains(GlFrameBuilder.TEX_RING))
        // R348 C3：24 径向条纹退役——隧道叠加零线产出（旧 24 条纹=+48 线顶点；
        // 穿越真运动在相机前冲+到达爆散，叠加层只剩暗幕+双环氛围底）
        assertTrue("条纹退役=虫洞叠加零线产出", b1.lineVertCount == b0.lineVertCount)
    }

    @Test
    fun `流星 头点+晕在场（路线就位即活）`() {
        // R314：流星并入「星座」门径——showConstellation=true 才装配（旧常开口径退役）
        val eng = TasteFlight.FxEngine()
        eng.setRoute(floatArrayOf(-100f, 100f), floatArrayOf(0f, 0f), floatArrayOf(500f, 500f), 2)
        val b = GlFrameBuilder()
        b.build(frame(emptyList(), engine = eng, showConstellation = true), w, h)
        val quadTexKeys = (0 until b.opCount)
            .filter { b.opType[it] == GlFrameBuilder.OP_QUADS }
            .map { b.opA[it] }
        assertTrue("流星头晕 TEX_FOG 应在场", quadTexKeys.contains(GlFrameBuilder.TEX_FOG))
        // 门径关=同引擎零装配（R314 合并门径的反向锚）
        val bOff = GlFrameBuilder()
        bOff.build(frame(emptyList(), engine = eng, showConstellation = false), w, h)
        val offTexKeys = (0 until bOff.opCount)
            .filter { bOff.opType[it] == GlFrameBuilder.OP_QUADS }
            .map { bOff.opA[it] }
        assertTrue("星座关=流星不装配", !offTexKeys.contains(GlFrameBuilder.TEX_FOG))
    }

    @Test
    fun `爆散 点随生命渐隐（burst 即产点）`() {
        val eng = TasteFlight.FxEngine()
        eng.burst(540f, 1200f, 8, 220f, 7)
        val b0 = GlFrameBuilder()
        b0.build(frame(emptyList()), w, h)
        val b1 = GlFrameBuilder()
        b1.build(frame(emptyList(), engine = eng), w, h)
        assertTrue("爆散应产点", b1.pointCount >= b0.pointCount + 8)
    }

    @Test
    fun `星爆闪 提亮盘在场_爆闪环退役（flashes 账驱动）`() {
        val eng = TasteFlight.FxEngine()
        eng.starFlashes[61L] = 2.0f
        val s = star(61L, 0f, 0f, 500f, bodyR = 8f)
        val b = GlFrameBuilder()
        b.build(frame(listOf(s), engine = eng), w, h)
        val quadTexKeys = (0 until b.opCount)
            .filter { b.opType[it] == GlFrameBuilder.OP_QUADS }
            .map { b.opA[it] }
        assertTrue("爆闪提亮盘 TEX_DISC 应在场", quadTexKeys.contains(GlFrameBuilder.TEX_DISC))
        // R334 扩散环族清偿（r333 §2.3-3）：爆闪环退役——爆闪=亮斑+光芒非环跑圈
        // （GL 光刺=SPIKE 既有件随 alpha/尺寸照旧）
        assertFalse("爆闪环 TEX_RING 应退役（R334 扩散环清偿锚）", quadTexKeys.contains(GlFrameBuilder.TEX_RING))
    }

    @Test
    fun `超新星 3D 壳纤维点串（弧线退役 r 过门）`() {
        val sn = star(71L, 0f, 0f, 500f, bodyR = 6f, kind = CelestialKind.SUPERNOVA)
        val b0 = GlFrameBuilder()
        b0.build(frame(emptyList()), w, h)
        val b1 = GlFrameBuilder()
        b1.build(frame(listOf(sn)), w, h)
        // R350 弧线族退役收官（r341 §3.3-A）：8 段折线弧→球面纤维点云壳（WPOINT）
        assertEquals("弧线退役=超新星零线产出（绝迹锚）", b0.lineVertCount, b1.lineVertCount)
        assertTrue("壳=世界点云 op 在场", opTypes(b1).contains(GlFrameBuilder.OP_WPOINTS))
        val seed = 71
        val r = 6f * (focal / 500f) * 1.15f // SUPERNOVA r=max(r×1_15, 2_2) 路径
        val n = TasteTerminalFx.remnantFiberPoints(r)
        assertEquals("壳点数=缕数×点串密度（fil×n ≤48 预算帽）", TasteTerminalFx.remnantFilaments(seed) * n, b1.wpCount - b0.wpCount)
        // 深度=不测不写（叠加层语义与现弧线同位——opC=0）
        val wpOps = (0 until b1.opCount).filter { b1.opType[it] == GlFrameBuilder.OP_WPOINTS }
        assertTrue("壳 WPOINTS opC=0（不测不写）", wpOps.isNotEmpty() && wpOps.all { b1.opC[it] == 0 })
    }

    @Test
    fun `超新星 3D 壳几何域（半径带_面方位真 3D 散布_量级帽）`() {
        val sn = star(72L, 0f, 0f, 500f, bodyR = 6f, kind = CelestialKind.SUPERNOVA)
        val b = GlFrameBuilder()
        b.build(frame(listOf(sn)), w, h)
        val r = 6f * (focal / 500f) * 1.15f
        val shellRWorld = r * TasteStarGlow.SUPERNOVA_SHELL_R * 500f / focal
        var minDist = Float.MAX_VALUE
        var maxDist = 0f
        var maxA = 0f
        var ySpread = false
        for (i in 0 until b.wpCount) {
            val base = i * GlFrameBuilder.WP_STRIDE
            val dx = b.wpBuf[base]
            val dy = b.wpBuf[base + 1]
            val dz = b.wpBuf[base + 2] - 500f
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            minDist = min(minDist, d)
            maxDist = max(maxDist, d)
            maxA = max(maxA, b.wpBuf[base + 8])
            if (abs(dy) > shellRWorld * 0.25f) ySpread = true
        }
        assertTrue(b.wpCount > 0)
        assertTrue(
            "壳点半径域=[0_80,1_12]×shellR（remnantArcRCoef 单源带）",
            minDist >= shellRWorld * 0.79f && maxDist <= shellRWorld * 1.13f,
        )
        assertTrue("面方位散布=真 3D 壳（绕飞真视差——非屏面恒定椭圆）", ySpread)
        assertTrue(
            "点亮度帽=SHELL_ALPHA×A×侵蚀 不增益（宪法诚实量级）",
            maxA <= TasteStarGlow.SHELL_ALPHA * 1.0f + 1e-4f && maxA > 0f,
        )
    }

    @Test
    fun `超新星壳回退序一（bias 抬升壳延后登场_诚实降级）`() {
        val sn = star(78L, 0f, 0f, 500f, bodyR = 0.6f, kind = CelestialKind.SUPERNOVA)
        val b0 = GlFrameBuilder()
        b0.build(frame(listOf(sn)), w, h)
        val wpN0 = (0 until b0.opCount).count { b0.opType[it] == GlFrameBuilder.OP_WPOINTS }
        assertEquals("常态（floor=1_6dp）：小超新星 r=2_2 过门壳在场", 1, wpN0)
        val b1 = GlFrameBuilder()
        b1.lodSolidBias = 1.5f
        b1.build(frame(listOf(sn)), w, h)
        val wpN1 = (0 until b1.opCount).count { b1.opType[it] == GlFrameBuilder.OP_WPOINTS }
        assertEquals("回退①（floor=2_4dp）：r=2_2 域外壳零装配（壳/环与形态线同族降级）", 0, wpN1)
    }

    // ==================== R350 中子星/夸克星（致密核球+3D 遗迹环） ====================

    @Test
    fun `中子星 致密核球与 3D 遗迹环（mode2 进动槽 环面纤维_弧线与环 2D 双退役）`() {
        // bodyR=1.5：rCap=min(1.5×sizeK×0.95, 4.2×sizeK)≈2.87——R355 mdpi 档低模
        // 球域 [1.6,4)（终裁全模型门槛降 4dp 后中子核球=低模 SPHERE_I 批档）
        val ns = star(75L, 0f, 0f, 500f, bodyR = 1.5f, kind = CelestialKind.NEUTRON_STAR)
        val b0 = GlFrameBuilder()
        b0.build(frame(emptyList()), w, h)
        val b1 = GlFrameBuilder()
        b1.build(frame(listOf(ns)), w, h)
        // 致密核球=R349 形态线既有接线零改（buildStarBody 低模 SPHERE_I 路径——
        // r≈2_87 帽内低模；屏径帽 4_2×sizeK 判据零碰）
        assertEquals("致密核球=低模 SPHERE_I 1 颗（r341 表行 7 核球化）", 1, b1.statSphereLow)
        // CompactStarFx 参数族消费锚：死而不灭亮地板=球 alpha 槽（a2=max(α,0.92)）
        assertEquals(
            "核球 alpha=COMPACT_CORE_ALPHA_MIN 地板（CompactStarFx 单源直引）",
            TasteTerminalFx.COMPACT_CORE_ALPHA_MIN,
            b1.lowSphereBuf[4],
            1e-4f,
        )
        // 3D 遗迹环（mode=2 窄带+进动槽 15）
        assertEquals(1, b1.ringCount)
        val rb = b1.ringBuf
        assertEquals("mode=2 致密遗迹环", 2f, rb[11], 1e-6f)
        val rCap = min(1.5f * (focal / 500f) * 0.95f, 4.2f * (focal / 500f))
        val coreWorldR = rCap * 500f / focal
        assertEquals("环 uRadius=核世界径（带中心=1_9×核径单源内插）", coreWorldR, rb[3], 1e-4f)
        assertEquals(
            "进动槽 15=remnantRingPrecessDeg 弧度真值（t=0）",
            Math.toRadians(TasteTerminalFx.remnantRingPrecessDeg(75, 0f).toDouble()).toFloat(),
            rb[15],
            1e-4f,
        )
        assertTrue("主环量级零调（a3×0_34 原值）", rb[10] in 0.30f..0.35f)
        // 环面纤维点串（旧 8 段折线弧的 3D 升维）
        val n = TasteTerminalFx.remnantFiberPoints(rCap)
        assertEquals(
            "环面纤维=缕数×密度（壳/遗迹同一纤维点串族）",
            TasteTerminalFx.remnantFilaments(75) * n,
            b1.wpCount - b0.wpCount,
        )
        // 弧线族+2D 环双退役绝迹锚（r341 §3.3-B 退役件清单）
        assertEquals("弧线退役=中子星零线产出", b0.lineVertCount, b1.lineVertCount)
        val ringQuads = (0 until b1.opCount)
            .filter { b1.opType[it] == GlFrameBuilder.OP_QUADS && b1.opA[it] == GlFrameBuilder.TEX_RING }
        assertTrue("addRingQuad 遗迹环退役（2D 环绝迹——扁圈消灭收官）", ringQuads.isEmpty())
        // 画家序：核球先发（球盖点）→环带/纤维后发（测不写穿插）
        val ops = opTypes(b1)
        assertTrue(
            "画家序=核球（OP_SPHERE_I）先于遗迹环（OP_RING）",
            ops.indexOf(GlFrameBuilder.OP_SPHERE_I) in 0 until ops.indexOf(GlFrameBuilder.OP_RING),
        )
    }

    @Test
    fun `中子星 贴脸超帽段自然升全模（屏径帽 4_2 判据零碰）`() {
        // r341 §3.3-B：世界径帽 4.2 保留——帽内低模、贴脸超帽段（r≥4dp）自然升全模
        // （R355 终裁全模型门槛降 4dp——超帽段全模位=门槛上段即全模质感）
        val ns = star(80L, 0f, 0f, 500f, bodyR = 6f, kind = CelestialKind.NEUTRON_STAR)
        val b = GlFrameBuilder()
        b.build(frame(listOf(ns)), w, h)
        assertEquals("r 帽 8_46≥4dp=全模 SPHERE 1 颗", 1, b.statSphereFull)
        assertEquals("低模档 0（超帽段升全模）", 0, b.statSphereLow)
        assertEquals("全模同吃致密核球化（sphereBuf 1 颗）", 1, b.sphereCount)
        assertEquals("环照旧 mode=2（环带与核球两档独立）", 1, b.ringCount)
        assertEquals(2f, b.ringBuf[11], 1e-6f)
    }

    @Test
    fun `夸克星 双道 3D 遗迹环（主环 1_9 内环 1_30 同 mode 同进动）`() {
        // bodyR=1.5（中子星同款 rCap≈2_87——R355 mdpi 档低模域）
        val qs = star(76L, 0f, 0f, 500f, bodyR = 1.5f, kind = CelestialKind.QUARK_STAR)
        val b = GlFrameBuilder()
        b.build(frame(listOf(qs)), w, h)
        assertEquals("主环+内环两道（中子星单道对照）", 2, b.ringCount)
        val rCap = min(1.5f * (focal / 500f) * 0.95f, 4.2f * (focal / 500f))
        val coreWorldR = rCap * 500f / focal
        assertEquals("主环 uRadius=核径", coreWorldR, b.ringBuf[3], 1e-4f)
        assertEquals(
            "内环 uRadius=核径×QUARK_INNER_RING_R÷REMNANT_RING_R 换算（带中心落 1_30×核径）",
            coreWorldR * TasteTerminalFx.QUARK_INNER_RING_R / TasteTerminalFx.REMNANT_RING_R,
            b.ringBuf[GlFrameBuilder.RING_STRIDE + 3],
            1e-4f,
        )
        assertEquals("两环同 mode=2", 2f, b.ringBuf[11], 1e-6f)
        assertEquals(2f, b.ringBuf[GlFrameBuilder.RING_STRIDE + 11], 1e-6f)
        assertEquals(
            "两环同进动角（环带/丝缕同面族——槽 15 一致）",
            b.ringBuf[15],
            b.ringBuf[GlFrameBuilder.RING_STRIDE + 15],
            0f,
        )
        assertEquals(
            "内环量级零调（a3×0_30 vs 主环 a3×0_34——同 a3 比例锚）",
            b.ringBuf[10] * (0.30f / 0.34f),
            b.ringBuf[GlFrameBuilder.RING_STRIDE + 10],
            1e-4f,
        )
        assertEquals("夸克核球=低模 SPHERE_I（核球化随形态线）", 1, b.statSphereLow)
    }

    @Test
    fun `中子星 r 低于门槛 无环无纤维（核点档）`() {
        val ns = star(79L, 0f, 0f, 500f, bodyR = 0.5f, kind = CelestialKind.NEUTRON_STAR)
        val b0 = GlFrameBuilder()
        b0.build(frame(emptyList()), w, h)
        val b1 = GlFrameBuilder()
        b1.build(frame(listOf(ns)), w, h)
        assertEquals("r≈0_96<1_6dp=核点档零环", 0, b1.ringCount)
        assertEquals("核点档零环面纤维", b0.wpCount, b1.wpCount)
        assertEquals("核点档零低模球（点形态）", 0, b1.statSphereLow)
    }

    @Test
    fun `脉冲星 双束四边形（t0 相位 on）`() {
        val pulsar = star(73L, 0f, 0f, 500f, bodyR = 8f, kind = CelestialKind.PULSAR)
        val b = GlFrameBuilder()
        b.build(frame(listOf(pulsar)), w, h) // engine.timeSec=0 → phase=0 → on
        val quadTexKeys = (0 until b.opCount)
            .filter { b.opType[it] == GlFrameBuilder.OP_QUADS }
            .map { b.opA[it] }
        assertTrue("脉冲星双束 TEX_BEAM 应在场", quadTexKeys.contains(GlFrameBuilder.TEX_BEAM))
    }

    @Test
    fun `发现特效 扩散双环退役_名签打字机照旧（revealIds 新发现）`() {
        val s = star(81L, 0f, 0f, 500f, bodyR = 20f, title = "频道甲")
        val b = GlFrameBuilder()
        b.build(
            frame(listOf(s), known = setOf(81L), revealIds = mapOf(81L to 500L), nowMs = 1000L),
            w, h,
        )
        val ringOps = (0 until b.opCount)
            .filter { b.opType[it] == GlFrameBuilder.OP_QUADS && b.opA[it] == GlFrameBuilder.TEX_RING }
        // R334 扩散环族清偿（r333 §2.3-4）：发现双扩散环退役——「点亮」仪式感=既有
        // REVEAL alpha 脉冲进 bloom 亮部提取的自然抬升（GL 无位图光晕=r333 §2.4）；
        // R328 点燃第三道环=一次性 300ms 瞬态只在年轻星（本例普通星=零环）
        assertTrue("发现扩散双环应退役（R334 清偿锚）", ringOps.isEmpty())
        // 打字机：ageMs=500 <1200 → 名签截短（3 字→1~2 字）
        assertEquals(1, b.labelCands.size)
        assertTrue("打字机截字中（短于全名）", b.labelCands[0].text.length < 3)
    }

    @Test
    fun `屏缘 chevron 未知星在背后产指向线 屏内不产`() {
        // 背后星（相机朝 +z 看，星在 −z=背后）→ chevron 双线+信号环
        val behind = star(91L, 300f, 0f, -400f, bodyR = 8f)
        val b1 = GlFrameBuilder()
        b1.build(frame(listOf(behind), known = emptySet()), w, h)
        val base = GlFrameBuilder()
        base.build(frame(emptyList()), w, h)
        assertTrue("背后未知星应产 chevron 线", b1.lineVertCount >= base.lineVertCount + 4)
        // 屏内未知星 → 零 chevron（呼吸脉冲即引导）
        val onscreen = star(92L, 10f, 0f, 500f, bodyR = 8f)
        val b2 = GlFrameBuilder()
        b2.build(frame(listOf(onscreen), known = emptySet()), w, h)
        assertEquals("屏内未知星不产 chevron 线", base.lineVertCount, b2.lineVertCount)
    }

    @Test
    fun `日冕 r 过 90px 门才产 OP_CORONA 且实例参数闭环`() {
        val near = star(95L, 0f, 0f, 400f, bodyR = 40f) // r=100.7 ≥ 90
        val b1 = GlFrameBuilder()
        b1.build(frame(listOf(near)), w, h)
        assertEquals(1, b1.coronaCount)
        assertTrue("日冕 op 应在场", opTypes(b1).contains(GlFrameBuilder.OP_CORONA))
        assertEquals("四边形半幅=1.9r", 100.7f * 1.9f, b1.coronaBuf[2], 1f)
        // 中距星不产日冕
        val mid = star(96L, 0f, 0f, 500f, bodyR = 40f) // r=80.5 < 90
        val b2 = GlFrameBuilder()
        b2.build(frame(listOf(mid)), w, h)
        assertEquals(0, b2.coronaCount)
    }

    @Test
    fun `真球 R280 增强参数槽（黑子幅域 火苗幅域 热星旗）`() {
        val hot = star(97L, 0f, 0f, 500f, bodyR = 40f, color = 0xFF88BBFF.toInt(), recent7 = 20, gVideo = 3, gPhoto = 1, gAudio = 1)
        val b = GlFrameBuilder()
        b.build(frame(listOf(hot)), w, h)
        assertEquals(1, b.sphereCount)
        val spotAmp = b.sphereBuf[18]
        val flameAmp = b.sphereBuf[19]
        assertTrue("黑子幅=metallicity∈[0,1]", spotAmp in 0f..1f)
        assertTrue("火苗幅∈[0.45,1]", flameAmp in 0.45f..1.0f)
        assertEquals("蓝星=热星旗 1", 1f, b.sphereBuf[20], 0.001f)
    }

    // ==================== R311 色彩引擎/造父变星/宜居带环带 ====================

    @Test
    fun `真球 R311 连续色温支路（主序 tempK=黑体谱芯缘色+临边系数色温从动）`() {
        // 主序 STAR + tempK=5800K（太阳档）：芯=黑体(7830K)、缘=黑体(3480K)、
        // limbK=0.85−0.45×temp01(0.362)≈0.687（槽位 21 锚）
        val starK = TasteFxStar(
            chatId = 301L, x = 0f, y = 0f, z = 500f,
            radius = 40f, colorArgb = 0xFFFFE0B0.toInt(), alpha = 0.9f, label = null,
            firstSeenMs = 0L, hasComp = true,
            kind = CelestialKind.STAR, bodyR = 40f, bodyAlpha = 0.9f,
            bodyColor = 0xFFFFE0B0.toInt(), vol = 100, tempK = 5800f,
        )
        val b = GlFrameBuilder()
        b.build(frame(listOf(starK)), w, h)
        assertEquals(1, b.sphereCount)
        val temp01 = dev.tgfeed.core.tastemap.TasteStarColor.temp01OfKelvin(5800f)
        val expectLimb = dev.tgfeed.core.tastemap.TasteStarColor.limbKOfTemp01(temp01)
        assertEquals("临边昏暗系数=色温从动档", expectLimb, b.sphereBuf[21], 0.001f)
        // 芯色=黑体 T×1.35（7830K——偏白蓝）；缘色=黑体 T×0.60（3480K——偏橙红）：
        // 连续谱锚=缘色红通道显著高于蓝通道（橙红），芯色三通道近满档（白炽）
        val edgeR = b.sphereBuf[11]; val edgeB = b.sphereBuf[13]
        assertTrue("缘色应偏橙红（R>B）", edgeR > edgeB + 0.30f)
        // 对照组：旧桶星（tempK=0）limbK=旧映射档（蓝星色→TEMP_COOL→0.49）
        val legacy = star(302L, 0f, 0f, 500f, bodyR = 40f, color = 0xFFA0C8FF.toInt())
        val b2 = GlFrameBuilder()
        b2.build(frame(listOf(legacy)), w, h)
        assertEquals("旧桶=映射档 0.49", 0.49f, b2.sphereBuf[21], 0.01f)
    }

    @Test
    fun `造父变星 光变曲线驱动亮度与半径双搏动（GL pass2 锚）`() {
        val eng = TasteFlight.FxEngine()
        val cep = TasteFxStar(
            chatId = 303L, x = 0f, y = 0f, z = 500f,
            radius = 40f, colorArgb = 0xFFFFE0B0.toInt(), alpha = 0.9f, label = null,
            firstSeenMs = 0L, hasComp = true,
            kind = CelestialKind.STAR, bodyR = 40f, bodyAlpha = 0.9f,
            bodyColor = 0xFFFFE0B0.toInt(), vol = 100,
            pulsePeriodSec = 8f, pulseAmp = 0.20f, pulsePhase = 0f,
        )
        // 波谷（u≈0：shape≈0 → alpha×(1−amp)）；known 标定=排除未发现星呼吸脉冲干扰
        eng.step(0.01f)
        val bTrough = GlFrameBuilder()
        bTrough.build(frame(listOf(cep), known = setOf(303L), engine = eng), w, h)
        // 近峰（t=2.19：u≈0.274<0.28 快升顶——shape≈0.99 → alpha×(1+0.98·amp)）；
        // step dt 钳 0.1s/步（FxEngine 防跳帧钳）——22 步到 t≈2.19
        repeat(22) { eng.step(0.1f) }
        val bPeak = GlFrameBuilder()
        bPeak.build(frame(listOf(cep), known = setOf(303L), engine = eng), w, h)
        assertEquals(1, bTrough.sphereCount)
        assertEquals(1, bPeak.sphereCount)
        val aTrough = bTrough.sphereBuf[4]
        val aPeak = bPeak.sphereBuf[4]
        assertTrue("峰谷亮度差=变星搏动（±20% 档，微闪 ±8% 盖不住）", aPeak > aTrough * 1.15f)
        // 半径微搏（0.35×amp——径向搏动观感）
        val rTrough = bTrough.sphereBuf[3]
        val rPeak = bPeak.sphereBuf[3]
        assertTrue("峰相半径应大于谷相", rPeak > rTrough)
        // 对照组：非变星（pulsePeriodSec=0）同机两刻 alpha 差<10%（只有微闪）
        val calm = star(304L, 0f, 0f, 500f, bodyR = 40f)
        val eng2 = TasteFlight.FxEngine()
        eng2.step(0.01f)
        val c1 = GlFrameBuilder()
        c1.build(frame(listOf(calm), known = setOf(304L), engine = eng2), w, h)
        repeat(22) { eng2.step(0.1f) }
        val c2 = GlFrameBuilder()
        c2.build(frame(listOf(calm), known = setOf(304L), engine = eng2), w, h)
        assertTrue(
            "非变星无搏动",
            kotlin.math.abs(c2.sphereBuf[4] - c1.sphereBuf[4]) < c1.sphereBuf[4] * 0.10f,
        )
    }

    @Test
    fun `宜居带环带 冰粒 KEPLER 点（聚块成缝+径向抖动域+恒星光染色）+极淡均迹线`() {
        // bodyR=20 z=250：行星层全现档（同「真球化四件齐」几何锚）
        val b = GlFrameBuilder()
        b.build(planetSystemFrame(bodyR = 20f, z = 250f), w, h)
        // 均迹线=KEPLER 线 17 顶点（旧纯色圆圈退役后仍保底轨道可读，alpha≪旧档）
        val lineOp = (0 until b.opCount).firstOrNull {
            b.opType[it] == GlFrameBuilder.OP_KEPLER_LINES && b.opC[it] == 17
        }
        assertTrue("均迹线应在场", lineOp != null)
        // 冰粒=KEPLER 点 op（28 粒上限，聚块成缝=实产 <28；尘埃 op=2 点另算）
        val grainOp = (0 until b.opCount).firstOrNull {
            b.opType[it] == GlFrameBuilder.OP_KEPLER_POINTS && b.opC[it] > 2
        }
        assertTrue("冰粒环带 op 应在场（聚块成缝后仍有粒）", grainOp != null)
        val gStart = b.opA[grainOp!!]
        val gN = b.opC[grainOp]
        assertTrue("冰粒数 ≤28 上限", gN <= dev.tgfeed.core.tastemap.TasteStarColor.HABITABLE_GRAINS)
        var coefOk = true
        var alphaMax = 0f
        for (i in 0 until gN) {
            val base = (gStart + i) * GlFrameBuilder.KEP_STRIDE
            val coef = b.kepBuf[base + 1]
            val ga = b.kepBuf[base + 6]
            if (kotlin.math.abs(coef - 1f) > dev.tgfeed.core.tastemap.TasteStarColor.HABITABLE_RADIAL_JITTER + 1e-4f) coefOk = false
            if (ga > alphaMax) alphaMax = ga
        }
        assertTrue("粒径向系数域=1±0.03（环带厚度）", coefOk)
        assertTrue("粒 alpha 有亮有暗（聚块）", alphaMax > 0.10f)
    }

    @Test
    fun `行星色 色彩引擎三票（视频主导=暖色入槽——旧一族色公式退役锚）`() {
        // 构成三票视频主导（compV=9/compP=0/compA=0）→ 行星基色暖（R 显著大于 B）
        val planet = dev.tgfeed.core.tastemap.TasteTagPlanets.TagPlanet(
            tag = "音乐", count = 5, orbitR01 = 0.5f, sizeR01 = 0.8f, phase01 = 0.3f,
            omegaRadPerSec = 0.01f, itemIdx = intArrayOf(0, 1),
            dustMinSec = 100L, dustMaxSec = 200L, ecc = 0.2f,
            compV = 9, compP = 0, compA = 0,
        )
        val sys = dev.tgfeed.core.tastemap.TasteTagPlanets.PlanetSystem(
            chatId = 201L, planets = listOf(planet), sampleCount = 2, taggedCount = 2,
        )
        val s = star(201L, 0f, 0f, 250f, bodyR = 20f, vol = 100)
        val f = frame(listOf(s))
        val fr = TasteFlightFrame(
            cam = f.cam, stars = f.stars, edges = f.edges, engine = f.engine,
            genesisOn = false, genesisSpan = f.genesisSpan, genesisT01 = 1f,
            playingChatId = 0L, selectedId = 0L, labelMinPx = 24f, labelTextPx = 30f,
            planetSys = mapOf(
                201L to dev.tgfeed.ui.TastePlanetEntry(
                    201L, sys, listOf(videoItem("k0", 120), videoItem("k1", 180)),
                ),
            ),
            dustChatId = 201L, wellMaxVol = 100,
        )
        val b = GlFrameBuilder()
        b.build(fr, w, h)
        assertEquals("行星应成真球", 1, b.planetCount)
        val pr = b.planetBuf[7]; val pg = b.planetBuf[8]; val pb = b.planetBuf[9]
        assertTrue("视频主导行星应暖（R>B）", pr > pb + 0.10f)
        assertTrue("暖行星 R 应为主通道", pr >= pg)
    }

    @Test
    fun `星团成员球 3D 分布（R281 真身——世界点近大远小，量纲换算闭环）`() {
        // r=100 星团：tier0 sizePx=max(1.4, 100×0.16)=16 → sizeW=16×500/focal≈7.95
        val cluster = star(99L, 0f, 0f, 500f, bodyR = 50f, kind = CelestialKind.CLUSTER, members = 5000)
        val b = GlFrameBuilder()
        b.build(frame(listOf(cluster)), w, h)
        // 成员数=8+min(18, 5000/800)=14（同旧尘点口径）
        assertEquals("成员球粒数同口径", 14, b.wpCount)
        assertTrue("成员球 op 在场", opTypes(b).contains(GlFrameBuilder.OP_WPOINTS))
        // 世界尺寸换算闭环：sizeW×focal/depth=sizePx（近大远小由 GPU 投影细分）
        var foundTier0 = false
        for (i in 0 until b.wpCount) {
            val sizeW = b.wpBuf[i * GlFrameBuilder.WP_STRIDE + 3]
            val sizePx = sizeW * focal / 500f
            if (abs(sizePx - 16.1f) < 0.6f) foundTier0 = true
            // 3D 散布：成员不共面（z 偏移不全为零——球分布非屏圆阵）
        }
        assertTrue("近档成员 16px 等效档应在场", foundTier0)
        var zSpread = false
        val z0 = b.wpBuf[2]
        for (i in 1 until b.wpCount) {
            if (abs(b.wpBuf[i * GlFrameBuilder.WP_STRIDE + 2] - z0) > 1f) zSpread = true
        }
        assertTrue("成员应有真深度散布（球分布）", zSpread)
    }

    // ==================== R281 P2：行星系统真球化 / 光斑 / 彗星 / 流星体 ====================

    private fun videoItem(key: String, dateSec: Int) = dev.tgfeed.core.td.VideoItem(
        key = key, chatId = 1L, messageId = 1L, channelTitle = "频道甲", caption = "",
        durationSec = 10, width = 640, height = 360, fileId = -1, sizeBytes = 1000L,
        playUri = null, dateEpochSec = dateSec,
    )

    private fun planetSystemFrame(bodyR: Float, z: Float): TasteFlightFrame {
        val planet = dev.tgfeed.core.tastemap.TasteTagPlanets.TagPlanet(
            tag = "音乐", count = 5, orbitR01 = 0.5f, sizeR01 = 0.8f, phase01 = 0.3f,
            omegaRadPerSec = 0.01f, itemIdx = intArrayOf(0, 1),
            dustMinSec = 100L, dustMaxSec = 200L, ecc = 0.2f,
        )
        val sys = dev.tgfeed.core.tastemap.TasteTagPlanets.PlanetSystem(
            chatId = 201L, planets = listOf(planet), sampleCount = 2, taggedCount = 2,
        )
        val s = star(201L, 0f, 0f, z, bodyR = bodyR, vol = 100)
        val f = frame(listOf(s))
        return TasteFlightFrame(
            cam = f.cam, stars = f.stars, edges = f.edges, engine = f.engine,
            genesisOn = false, genesisSpan = f.genesisSpan, genesisT01 = 1f,
            playingChatId = 0L, selectedId = 0L, labelMinPx = 24f, labelTextPx = 30f,
            planetSys = mapOf(
                201L to dev.tgfeed.ui.TastePlanetEntry(
                    201L, sys, listOf(videoItem("k0", 120), videoItem("k1", 180)),
                ),
            ),
            dustChatId = 201L, wellMaxVol = 100,
        )
    }

    @Test
    fun `行星系统 真球化四件齐（轨道 KEPLER 线+行星真球+光向归一+尘埃 KEPLER 点）`() {
        // bodyR=20 z=250：井深比 4.6 < 6 门；distStar=250 ∈ 尘埃带 [150,260]；
        // 轨道半径 72——行星全程近裁前且全屏内（几何确定性锚——不赌相位）
        val b = GlFrameBuilder()
        b.build(planetSystemFrame(bodyR = 20f, z = 250f), w, h)
        // 行星真球 op（世界径=20×(0.115+0.085×0.8)=3.66）
        assertEquals("行星应成真球", 1, b.planetCount)
        assertEquals(3.66f, b.planetBuf[3], 0.05f)
        // 真 3D 平行光=行星→恒星归一世界向量
        val lx = b.planetBuf[4]; val ly = b.planetBuf[5]; val lz = b.planetBuf[6]
        assertEquals("光向应归一", 1f, kotlin.math.sqrt(lx * lx + ly * ly + lz * lz), 0.01f)
        // 轨道线=KEPLER op（17 顶点/轨）
        assertTrue("轨道 KEPLER 线 op 在场", (0 until b.opCount).any {
            b.opType[it] == GlFrameBuilder.OP_KEPLER_LINES && b.opC[it] == 17
        })
        // 尘埃=KEPLER 点（2 条目不满足岩石化门槛 dotSizePx=2.2<3 → 全点）
        assertTrue("尘埃 KEPLER 点 op 在场", (0 until b.opCount).any {
            b.opType[it] == GlFrameBuilder.OP_KEPLER_POINTS && b.opC[it] == 2
        })
        assertEquals("点档无岩石", 0, b.rockCount)
        // 土星环=真 3D 环带 op（ringed 由同式哈希判——与装配口径一致才锚）
        val tagSeed = "音乐".hashCode()
        val ringed = dev.tgfeed.ui.galaxyH01(tagSeed, 51) < 0.33f
        assertEquals("环带在场与否=哈希口径", if (ringed) 1 else 0, b.ringCount)
    }

    @Test
    fun `尘埃岩石化 近档前景粒换多面体（dotSizePx≥3 门）`() {
        // bodyR=60 z=200 → starPx≈302 → dotSizePx≈3.3 ≥3 → 岩石档
        val b = GlFrameBuilder()
        b.build(planetSystemFrame(bodyR = 60f, z = 200f), w, h)
        assertEquals("2 条目应全成岩石（≤24 帽）", 2, b.rockCount)
        assertTrue("岩石 op 在场（instancing）", opTypes(b).contains(GlFrameBuilder.OP_ROCK))
        // 岩石光向=粒→恒星方向（非零向量）
        val lx = b.rockBuf[12]; val ly = b.rockBuf[13]; val lz = b.rockBuf[14]
        assertTrue("岩石光向非零", lx * lx + ly * ly + lz * lz > 0.5f)
    }

    @Test
    fun `镜头光斑源 top3 亮星收集（强度×正对度口径）`() {
        val center = star(301L, 0f, 0f, 400f, bodyR = 40f)   // r≈100 正对屏心
        val edge = star(302L, 380f, 900f, 400f, bodyR = 40f)  // 屏缘（正对度低）
        val b = GlFrameBuilder()
        b.build(frame(listOf(center, edge)), w, h)
        assertTrue("光斑源应收（r≥24 亮星）", b.flareCount >= 1)
        // 屏心星正对度≈1
        assertEquals("屏心源 ndc≈0", 0f, b.flareBuf[0], 0.05f)
        assertEquals("屏心源 ndc_y≈0", 0f, b.flareBuf[1], 0.05f)
        assertTrue("屏心源正对度满档", b.flareBuf[6] > 0.95f)
        assertTrue("强度域 (0,1]", b.flareBuf[5] > 0.10f && b.flareBuf[5] <= 1f)
    }

    @Test
    fun `彗星真身 岩石核+世界三角带双尾+彗发雾（屏轨迹反投影）`() {
        val eng = TasteFlight.FxEngine()
        eng.spawnComet(seed = 42, lifeSec = 2.6f)
        repeat(8) { eng.step(0.05f) } // 步进攒尾迹环
        assertTrue("彗星应存活", eng.cometLife > 0f)
        assertTrue("尾迹环应有量", eng.cometTrailSize >= 6)
        val b = GlFrameBuilder()
        b.build(frame(emptyList(), engine = eng), w, h)
        assertEquals("彗核=岩石", 1, b.rockCount)
        // 双尾=两个 STRIP op（离子/尘埃层）
        val stripOps = (0 until b.opCount).filter { b.opType[it] == GlFrameBuilder.OP_STRIP }
        assertEquals("双尾两带", 2, stripOps.size)
        assertTrue("尾带顶点有量", b.stripVertCount >= 14)
        // 彗发雾 quad 保留
        val quadTexKeys = (0 until b.opCount)
            .filter { b.opType[it] == GlFrameBuilder.OP_QUADS }
            .map { b.opA[it] }
        assertTrue("彗发 TEX_FOG 应在场", quadTexKeys.contains(GlFrameBuilder.TEX_FOG))
        // 旧折线尾退役：彗星不再产 CPU 线（对比空引擎基线）
        val b0 = GlFrameBuilder()
        b0.build(frame(emptyList()), w, h)
        assertEquals("彗星零 CPU 线（世界带承接）", b0.lineVertCount, b.lineVertCount)
    }

    @Test
    fun `流星体 真身岩石化（METEOR 类 r≥6px 出 OP_ROCK 且不成球）`() {
        val meteor = star(401L, 0f, 0f, 400f, bodyR = 8f, kind = CelestialKind.METEOR) // r≈20
        val b = GlFrameBuilder()
        b.build(frame(listOf(meteor)), w, h)
        assertEquals("流星体=岩石", 1, b.rockCount)
        assertEquals("流星体不成球", 0, b.sphereCount)
        assertTrue("岩石 op 在场", opTypes(b).contains(GlFrameBuilder.OP_ROCK))
        // 烧蚀热缘参数（ember>0）
        assertTrue("热缘旗应在", b.rockBuf[15] > 0.5f)
    }
}
