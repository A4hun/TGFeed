package dev.tgfeed.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R350 特殊天体 3D 批 wiring 源码锚（R349WiringSourceTest 同款 user.dir 源定位
 * 模式——GL 渲染管线无 JVM 直测面，以源锚锁**弧线族双端退役+mode=2 环+进动槽
 * 启用+纤维点串单源**的结构面；数值半体由 TasteTerminalFxTest/GlFrameBuilderTest
 * 承重）：
 *  - **绝迹锚①弧线族**（r341 §3.3-A/B 退役件清单——「扁圈消灭」收官笔）：
 *    8 段折线弧三件套（`for (seg in 0..8)` 循环/`sin(ad) * rr * 0.82f` 假透视
 *    压扁系数/`addLine(lax, lay, ax2, ay2` 折线段）在 GlFrameBuilder 绝迹
 *    （Canvas 端已随 R345 物理删除——「双端」=本批 GL 侧收口）；
 *  - **绝迹锚②2D 遗迹环**：addRingQuad 遗迹环/夸克内环（COMPACT_TINT 实参式）
 *    绝迹——mode=2 环带接棒；
 *  - **接线锚③mode=2+进动槽 15**：addRing mode=2f 两调用（主环+夸克内环）+
 *    precRad 槽 15 装填+remnantRingPrecessDeg 消费+RING_VS uPrec/RING_FS
 *    bandC 单源内插+渲染器 rb[i+15] 上传（R321 契约跟改申报件）；
 *  - **接线锚④纤维点串单源**：shellFiberPoint/torusFiberPoint/remnantFiberErode01/
 *    remnantFiberPoints 在 GlFrameBuilder 双消费（壳/遗迹）+TasteTerminalFx
 *    单一定义（一类一件）；
 *  - **边界锚⑤海报域显式边界**：TastePosterV2 侧 remnant 弧族**保留**（分享海报
 *    静态位图域——非星图实时渲染；r341 退役件清单只点名 Canvas/GL 双端；
 *    显式边界不静默——挂账待用户过目）。
 */
class R350WiringSourceTest {

    private val mainSourceRoot: File
        get() {
            var dir = File(System.getProperty("user.dir")).absoluteFile
            repeat(5) {
                val candidate = File(dir, "src/main/java")
                if (candidate.isDirectory) return candidate
                val nested = File(dir, "app/src/main/java")
                if (nested.isDirectory) return nested
                dir = dir.parentFile ?: return@repeat
            }
            error("找不到主源码根（user.dir=${System.getProperty("user.dir")}）")
        }

    private fun src(path: String): String {
        val file = File(mainSourceRoot, path)
        assertTrue("源文件必须存在：${file.path}", file.isFile)
        return file.readText()
    }

    private fun glFrameBuilder(): String = src("dev/tgfeed/ui/gl/GlFrameBuilder.kt")
    private fun glShaders(): String = src("dev/tgfeed/ui/gl/GlShaders.kt")
    private fun renderer(): String = src("dev/tgfeed/ui/gl/TasteGlRenderer.kt")
    private fun terminalFx(): String = src("dev/tgfeed/core/tastemap/TasteTerminalFx.kt")
    private fun posterV2(): String = src("dev/tgfeed/ui/TastePosterV2.kt")

    // ==================== ① 绝迹锚：弧线族（8 段折线×y0_82 假透视） ====================

    @Test
    fun `绝迹-弧线族三件套（折线环 假透视系数 折线段）`() {
        val fb = glFrameBuilder()
        // 8 段折线弧循环体（超新星/中子星共用件——双现场同灭）
        assertTrue(
            "8 段折线弧循环（for (seg in 0..8)）应绝迹（R350 弧线族双端退役）",
            !Regex("for \\(seg in 0\\.\\.8\\)").containsMatchIn(fb),
        )
        // y×0.82 假透视压扁系数（屏空间椭圆=「转身即露馅」的病根——r341 §3.3-A）
        assertTrue(
            "假透视压扁系数（sin(ad) * rr * 0.82f）应绝迹（扁圈消灭收官）",
            !Regex("sin\\(ad\\) \\* rr \\* 0\\.82f").containsMatchIn(fb),
        )
        // 折线段 addLine(ax2/ay2 式)——弧线族的绘制原语
        assertTrue(
            "弧线折线段（addLine(lax, lay, ax2, ay2）应绝迹",
            !Regex("addLine\\(lax, lay, ax2, ay2").containsMatchIn(fb),
        )
    }

    // ==================== ② 绝迹锚：2D 遗迹环（addRingQuad COMPACT_TINT 实参式） ====================

    @Test
    fun `绝迹-2D 遗迹环与夸克内环（addRingQuad 遗迹实参式）`() {
        val fb = glFrameBuilder()
        assertTrue(
            "中子星 2D 遗迹环（addRingQuad ringR COMPACT_TINT）应绝迹（mode=2 接棒）",
            !Regex("addRingQuad\\(sx, sy, ringR, GlPalette\\.COMPACT_TINT").containsMatchIn(fb),
        )
        assertTrue(
            "夸克 2D 内环（addRingQuad QUARK_INNER_RING_R COMPACT_TINT）应绝迹（mode=2 接棒）",
            !Regex("QUARK_INNER_RING_R, GlPalette\\.COMPACT_TINT").containsMatchIn(fb),
        )
    }

    // ==================== ③ 接线锚：mode=2 环带+进动槽 15 契约 ====================

    @Test
    fun `接线-mode2 环带与进动槽15（addRing 契约跟改）`() {
        val fb = glFrameBuilder()
        // 主环 mode=2（窄带档——带中心 REMNANT_RING_R 单源内插进 FS）
        assertTrue(
            "遗迹环主带 mode=2f 必须在场（含 a3×0.34 量级原值）",
            Regex("seed\\.toFloat\\(\\), a3 \\* 0\\.34f, mode = 2f,").containsMatchIn(fb),
        )
        // 夸克内环 mode=2（QUARK_INNER_RING_R/REMNANT_RING_R 换算）
        assertTrue(
            "夸克内环 mode=2f 必须在场（含 a3×0.30 量级原值）",
            Regex("seed\\.toFloat\\(\\), a3 \\* 0\\.30f, mode = 2f,").containsMatchIn(fb),
        )
        // 进动角消费（remnantRingPrecessDeg 单源+deg→rad）
        assertTrue(
            "进动角必须消费 remnantRingPrecessDeg（槽 15 取值单源）",
            Regex("Math\\.toRadians\\(TasteTerminalFx\\.remnantRingPrecessDeg\\(seed, t\\)\\.toDouble\\(\\)\\)\\.toFloat\\(\\)").containsMatchIn(fb),
        )
        // 槽 15 装填（R321 契约：全槽位无条件写——precRad 真值入位）
        assertTrue(
            "addRing 槽 15 装填（precRad）必须在场",
            Regex("ringBuf\\[i \\+ 15\\] = precRad").containsMatchIn(fb),
        )
        val gs = glShaders()
        // RING_VS uPrec（进动=rotY 绕世界 Y——0=既有恒等）
        assertTrue(
            "RING_VS uPrec uniform 必须在场（进动角 VS 侧——零跨阶段同名）",
            Regex("uniform float uPrec;").containsMatchIn(gs),
        )
        // RING_FS mode=2 窄带（带中心/带宽=core 单源内插）
        assertTrue(
            "RING_FS mode=2 带中心必须=TasteTerminalFx.REMNANT_RING_R 单源内插",
            Regex("float bandC = \\$\\{TasteTerminalFx\\.REMNANT_RING_R\\};").containsMatchIn(gs) &&
                Regex("float bandW = \\$\\{TasteTerminalFx\\.REMNANT_RING_BAND_HALF\\};").containsMatchIn(gs),
        )
        val r = renderer()
        // 渲染器读侧（槽 15 无条件上传——0=恒等既有调用零回破）
        assertTrue(
            "渲染器 OP_RING 回放必须上传 rb[i+15]（uPrecRing 槽位查找+消费）",
            Regex("uPrecRing = GLES30\\.glGetUniformLocation\\(progRing, \"uPrec\"\\)").containsMatchIn(r) &&
                Regex("glUniform1f\\(uPrecRing, rb\\[i \\+ 15\\]\\)").containsMatchIn(r),
        )
    }

    // ==================== ④ 接线锚：纤维点串单源（一类一件） ====================

    @Test
    fun `接线-纤维点串单源（壳与遗迹双消费 一类一件）`() {
        val fb = glFrameBuilder()
        // 球面版（超新星壳）+环面版（遗迹丝缕）双消费
        assertTrue(
            "球面纤维点串（shellFiberPoint）壳消费必须在场",
            Regex("TasteTerminalFx\\.shellFiberPoint\\(seed, k, u, t, fiber3, 0\\)").containsMatchIn(fb),
        )
        assertTrue(
            "环面纤维点串（torusFiberPoint）遗迹丝缕消费必须在场",
            Regex("TasteTerminalFx\\.torusFiberPoint\\(seed, k, u, t, majorR, fiber3, 0\\)").containsMatchIn(fb),
        )
        // 密度两档+侵蚀共用（同一族的共享半体——壳/遗迹双现场；R355 dp 化=帧字段
        // [lodLowMeshMaxPx] 透传——密度档分界随全模型门槛 4dp 同族）
        val densityHits = Regex("TasteTerminalFx\\.remnantFiberPoints\\(r(?:Cap)?, lodLowMeshMaxPx\\)").findAll(fb).count()
        assertTrue(
            "纤维密度两档（remnantFiberPoints）双消费必须在场（壳/遗迹两现场）",
            densityHits >= 2,
        )
        assertTrue(
            "沿线侵蚀（remnantFiberErode01）双消费必须在场（点亮度=α×侵蚀）",
            Regex("fa \\* TasteTerminalFx\\.remnantFiberErode01\\(seed, k, u\\)").containsMatchIn(fb),
        )
        // WPOINT 深度=不测不写（壳/遗迹叠加层同位——flushWPoints 非 Depth 版）
        assertTrue(
            "壳/遗迹纤维 flushWPoints（opC=0 不测不写）双消费必须在场",
            Regex("flushWPoints\\(wpStart\\)").containsMatchIn(fb),
        )
        val tf = terminalFx()
        // 单一定义（一类一件——core 单源件，壳/遗迹两消费零第二套）
        assertTrue(
            "纤维点串族必须在 TasteTerminalFx 单一定义（壳=球面版/遗迹=环面版）",
            Regex("fun shellFiberPoint\\(").containsMatchIn(tf) &&
                Regex("fun torusFiberPoint\\(").containsMatchIn(tf) &&
                Regex("fun remnantFiberErode01\\(").containsMatchIn(tf) &&
                Regex("fun remnantFiberPoints\\(").containsMatchIn(tf),
        )
        // 漂移率单源（壳基相/环进动同族）
        assertTrue(
            "REMNANT_DRIFT_DEG_S 单源常量+双消费（壳基相/环进动同漂率族）",
            Regex("const val REMNANT_DRIFT_DEG_S = 0\\.4f").containsMatchIn(tf) &&
                Regex("t \\* REMNANT_DRIFT_DEG_S").containsMatchIn(tf),
        )
    }

    // ==================== ⑤ 边界锚：海报域显式边界（不静默挂账） ====================

    @Test
    fun `边界-海报域 remnant 弧族保留（静态出版物 非星图实时渲染域）`() {
        val pv = posterV2()
        // TastePosterV2=分享海报静态位图域——r341 退役件清单只点名 Canvas/GL 双端；
        // 海报 2D 弧=静态出版物语义不在本批刀口（挂账待用户过目——不静默）
        assertTrue(
            "海报域 remnant 弧族应保留（显式边界——非本批刀口）",
            Regex("TasteTerminalFx\\.remnantArcSweep\\(seed, k\\)").containsMatchIn(pv),
        )
    }
}
