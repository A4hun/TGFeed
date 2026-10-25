package dev.tgfeed.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R351 雾絮化 wiring 源码锚（R323WiringSourceTest 同款 user.dir 源定位模式——
 * 渲染端行为半体由 GlFrameBuilderTest 承重，本件锁「单源接线+旧病灶绝迹+
 * 零碰红线」的结构件；R351b 接棒批按 r351_notes §2 裁决八条逐条落锚）：
 *  - **接线锚**：GlFrameBuilder blob 循环消费 [FogFlocFx] 单源五口（flocSeedOf/
 *    blobCountOf/blobAlphaPack01/blobOffset01/blobRadius01）+层族常量直引
 *    （FRONT_R/BACK_R/LAYER_DEPTH_OFF——原值零调）+MAX_NEBULAS 12→24 帽账+
 *    core 件配比零增益单口（ALPHA_PACK_SUM=FRONT_ALPHA+BACK_ALPHA_TOTAL）；
 *  - **绝迹锚**：旧同心层循环退役——for(layer in 0..1)/seed+layer×13.7/固定
 *    配比 0.55/0.80 与固定基径 0.90/1.10 的层内硬编码三件全灭（病灶=中距离
 *    整团读作「同心 fbm 椭圆贴图」）；
 *  - **消光零碰锚**：R320 星际消光账（fogSx/fogSy/fogRpx/fogPresence 雾心
 *    口径——blob 循环前顺手记）四行 verbatim（消光几何以雾心为准=与 Canvas
 *    同口径同式，絮化错位不得渗入）；
 *  - **发射块零碰锚**：R323 发射星云块 verbatim（亮档 emissionAlphaOf(a) else a/
 *    电离侧辉偏置/星胚三单口/scanEmissionFogsR323/fogPulse——本批 blob 循环
 *    只动两型共用层绘制段，发射分化语义零回破）；
 *  - **零着色器锚**：NEBULA_VS/NEBULA_FS 零改动（块内丝缕结构=NEBULA_FS 既有
 *    fbm 密度场原样承担——絮化≠锐化红线；零新程序零新纹理零新 uniform）；
 *  - **帧零分配锚**：floc 暂存两枚（flocAlphaPack/flocOff）为预分配字段非局部
 *    ——blob 装配帧路径零分配纪律。
 */
class R351WiringSourceTest {

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

    private fun gl(): String = src("dev/tgfeed/ui/gl/GlFrameBuilder.kt")
    private fun floc(): String = src("dev/tgfeed/core/tastemap/FogFlocFx.kt")
    private fun shaders(): String = src("dev/tgfeed/ui/gl/GlShaders.kt")

    // ==================== 接线锚（单源消费+帽账） ====================

    @Test
    fun `接线锚-FogFlocFx 单源五口消费与层族常量直引与帽 24`() {
        val g = gl()
        // blob 循环消费 core 单源五口（渲染端只画不算——一类一件进 core）
        for (anchor in listOf(
            "FogFlocFx.flocSeedOf(",
            "FogFlocFx.blobCountOf(",
            "FogFlocFx.blobAlphaPack01(",
            "FogFlocFx.blobOffset01(",
            "FogFlocFx.blobRadius01(",
        )) {
            assertTrue("GL blob 循环单口 $anchor 消费丢失", Regex(Regex.escape(anchor)).containsMatchIn(g))
        }
        // R281 层族原值直引（原值迁 core 零调——量级红线）
        for (anchor in listOf("FogFlocFx.FRONT_R", "FogFlocFx.BACK_R", "FogFlocFx.LAYER_DEPTH_OFF")) {
            assertTrue("层族常量 $anchor 直引丢失（渲染端不得另写硬编码）", Regex(Regex.escape(anchor)).containsMatchIn(g))
        }
        // 帽账：12→24 扩容（雾 6×3=18+侧辉 ≤6=24；奥伯斯壳共用语义保持）
        assertTrue(
            "MAX_NEBULAS 24 帽账丢失",
            Regex("const val MAX_NEBULAS = 24").containsMatchIn(g),
        )
        // core 件单源结构：配比零增益单口（Σ恒 1.35 的目标值单一出处）
        val f = floc()
        assertTrue(
            "FogFlocFx 配比和恒 1_35 单口丢失（量级零增益）",
            Regex("const val ALPHA_PACK_SUM = FRONT_ALPHA \\+ BACK_ALPHA_TOTAL").containsMatchIn(f) &&
                Regex("fun blobAlphaPack01\\(").containsMatchIn(f),
        )
        // 折叠种子单口（团位哈希 ×8191 折叠 Int——同雾同絮入口）
        assertTrue(
            "flocSeedOf 折叠单口丢失（§2-2 种子裁决）",
            Regex("fun flocSeedOf\\(churnSeed: Float\\): Int = \\(churnSeed \\* SEED_FOLD\\)\\.toInt\\(\\)").containsMatchIn(f) &&
                Regex("const val SEED_FOLD = 8191f").containsMatchIn(f),
        )
    }

    // ==================== 绝迹锚（旧同心层循环退役） ====================

    /** buildNebulas 函数体切片（绝迹锚的作用域——彗尾离子/尘埃双层等同形循环不误伤） */
    private fun nebulaFnBody(g: String): String {
        val start = Regex("private fun buildNebulas\\(").find(g)?.range?.first
            ?: error("buildNebulas 函数未找到")
        val next = Regex("\n    (private fun|companion object|// ====================)").find(g, start + 1)
        return if (next != null) g.substring(start, next.range.first) else g.substring(start)
    }

    @Test
    fun `绝迹锚-旧同心层循环三件全灭（病灶收口——buildNebulas 域内）`() {
        val body = nebulaFnBody(gl())
        // 旧双层循环（同心叠——中距离整团读作一团 fbm 椭圆贴图的病根）
        assertTrue(
            "旧 for(layer in 0..1) 同心层循环应绝迹（blob 循环接棒）",
            !Regex("for \\(layer in 0\\.\\.1\\)").containsMatchIn(body),
        )
        // 旧 churn 相位层序号（blob 序号 k 接棒）
        assertTrue(
            "旧 seed+layer×13.7f 应绝迹（blob 槽 seed+k×13.7f）",
            !Regex("layer \\* 13\\.7f").containsMatchIn(body),
        )
        // 旧层内硬编码配比/基径（0.55/0.80 与 0.90/1.10——迁 core 常量直引）
        assertTrue(
            "旧固定配比 if(layer==0) 0.55f else 0.80f 应绝迹（配比包接棒）",
            !Regex("if \\(layer == 0\\) 0\\.55f else 0\\.80f").containsMatchIn(body),
        )
        assertTrue(
            "旧固定基径 if(layer==0) 0.90f else 1.10f 应绝迹（层族常量接棒）",
            !Regex("if \\(layer == 0\\) 0\\.90f else 1\\.10f").containsMatchIn(body),
        )
    }

    // ==================== 消光零碰锚（R320 雾心口径 verbatim） ====================

    @Test
    fun `消光零碰锚-雾心口径四行原样（絮化错位不得渗入消光几何）`() {
        val g = gl()
        // R320 星际消光账：blob 循环前顺手记——雾心（无层错位）为准=与 Canvas 同口径
        for (anchor in listOf(
            "fogSx[fogN] = proj[0]",
            "fogSy[fogN] = proj[1]",
            "fogRpx[fogN] = rPx0",
            "fogPresence[fogN] = a / TasteNebula.FOG_ALPHA_MAX",
        )) {
            assertTrue(
                "消光账 $anchor 零碰红线被破（雾心口径原样）",
                Regex(Regex.escape(anchor)).containsMatchIn(g),
            )
        }
        // 雾心半径口径（fogPx(c.radius, proj[3])——非 blob 缩径口径）
        assertTrue(
            "消光雾心半径口径丢失",
            Regex("val rPx0 = TasteNebula\\.fogPx\\(c\\.radius, proj\\[3\\]\\)").containsMatchIn(g),
        )
    }

    // ==================== 发射块零碰锚（R323 verbatim——零回破） ====================

    @Test
    fun `发射块零碰锚-R323 亮档电离星胚 verbatim（blob 循环只动共用层段）`() {
        val g = gl()
        // 亮档 alpha 直供（普通雾原值——两型分叉唯一口）
        assertTrue(
            "R323 发射档亮档 alpha 直供丢失（emissionAlphaOf(a) else a）",
            Regex("EmissionNebulaFx\\.emissionAlphaOf\\(a\\) else a").containsMatchIn(g),
        )
        // 电离：预扫+侧辉偏置（雾心投影消费——blob 错位不渗入侧辉几何）
        assertTrue(
            "R323 电离预扫 scanEmissionFogsR323 丢失",
            Regex("private fun scanEmissionFogsR323\\(f: TasteFlightFrame\\)").containsMatchIn(g),
        )
        assertTrue(
            "R323 电离侧辉偏置消费丢失",
            Regex("EmissionNebulaFx\\.ionizationSideGlowAlpha\\(\\w+, 1f\\)").containsMatchIn(g),
        )
        assertTrue(
            "R323 电离侧辉偏置常数丢失",
            Regex("EmissionNebulaFx\\.IONIZATION_OFFSET_COEF \\* rPx").containsMatchIn(g) &&
                Regex("EmissionNebulaFx\\.IONIZATION_RADIUS_COEF \\* rPx").containsMatchIn(g),
        )
        // 星胚三单口（offset/alpha/size——verbatim 零碰）
        for (anchor in listOf("embryoOffset01", "embryoAlpha01", "embryoSizePx")) {
            assertTrue(
                "R323 星胚单口 $anchor 消费丢失（零回破）",
                Regex("EmissionNebulaFx\\.$anchor").containsMatchIn(g),
            )
        }
        assertTrue(
            "R328 fogPulse 星胚脉冲消费丢失（零回破）",
            Regex("alpha \\+= fogPulse\\[i\\]").containsMatchIn(g),
        )
    }

    // ==================== 零着色器锚（絮化不进 shader——絮化≠锐化） ====================

    @Test
    fun `零着色器锚-NEBULA 程序零改动（零新 uniform 零新纹理）`() {
        val s = shaders()
        // NEBULA_VS 既有两 uniform 原样（blob 错位在装配层 CPU 侧一次求值——
        // VS 顶点扰动方案已否决：quad 仅 4 顶点 6 索引，扰动只能扭四角）
        assertTrue(
            "NEBULA_VS uCenterPx uniform 丢失",
            Regex("uniform vec2 uCenterPx;").containsMatchIn(s),
        )
        assertTrue(
            "NEBULA_VS uHalfPx uniform 丢失",
            Regex("uniform float uHalfPx;").containsMatchIn(s),
        )
        // 零新 blob/floc uniform（错位同式=装配层 blob 数 addNebula，shader 零感知）
        assertTrue(
            "NEBULA 程序不得新增 floc/blob uniform（零着色器改动红线）",
            !Regex("uFloc|uBlob").containsMatchIn(s),
        )
        // NEBULA_FS 既有结构原样：d>1 先 discard（片元账∝quad 面积）+域扭曲 fbm 密度场
        //（块内丝缕结构既有承担——絮化只拆块不锐化，弥散语义红线）
        assertTrue(
            "NEBULA_FS d>1 discard 先行丢失（片元账口径）",
            Regex("if \\(d > 1\\.0\\) discard;").containsMatchIn(s),
        )
        assertTrue(
            "NEBULA_FS 域扭曲 fbm 密度场丢失（块内丝缕 R281 既有）",
            Regex("fbm3\\(q \\+ warp \\* 1\\.4").containsMatchIn(s),
        )
    }

    // ==================== 帧零分配锚（blob 装配零分配纪律） ====================

    @Test
    fun `帧零分配锚-floc 暂存两枚预分配字段（非局部变量）`() {
        val g = gl()
        assertTrue(
            "flocAlphaPack 预分配暂存丢失（配比包出参——帧零分配）",
            Regex("private val flocAlphaPack = FloatArray\\(FogFlocFx\\.BLOB_COUNT_MAX\\)").containsMatchIn(g),
        )
        assertTrue(
            "flocOff 预分配暂存丢失（blob 错位向出参——帧零分配）",
            Regex("private val flocOff = FloatArray\\(3\\)").containsMatchIn(g),
        )
    }
}
