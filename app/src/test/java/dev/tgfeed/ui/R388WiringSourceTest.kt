package dev.tgfeed.ui

import dev.tgfeed.core.tastemap.DeepSpaceNebula
import dev.tgfeed.core.tastemap.FogFlocFx
import dev.tgfeed.core.tastemap.TasteFlight
import dev.tgfeed.core.tastemap.TasteNebula
import dev.tgfeed.core.tastemap.TasteSpace
import dev.tgfeed.ui.gl.GlFrameBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R388 星云圆饼根治批 wiring 源码锚+行为例（R373/R386 同款 user.dir 源定位——GL
 * 渲染管线无 JVM 直测面，以源锚锁结构面+GlFrameBuilder 纯 JVM 行为例双承重）：
 *  - **病灶锚**：用户小米 400dpi 新装机直读=星云复合体**纯色圆饼**（r386_notes
 *    §3 修正块三层根因：①NEBULA_FS 径向 falloff 圆包络 shader 内建（R351 弥散
 *    保义红线：边缘必须弥散不许锐化——圆饼不可锐边治）；②R386 阈上复合体仍两
 *    层错位叠（±0.5R 层深远距投影≈同心+≤0.45R 横移）→丝缕互填+叠亮；③阈下
 *    单层丝缕空间频率（≈quad 宽 1/2.3）在屏上几十像素时低于视觉分辨极限→平均
 *    成均匀色+圆包络=纯色圆饼。R386（裁层+阈 dp 化）只治多层叠雾子病——本批
 *    治「圆饼」病类本体（用户 2026-10-25 14:1x 裁决组合方案）；
 *  - **刀1 锚（远档隐没——判词「看不清就不画」诚实缺省同族）**：隐没带 dp 双阈
 *    单源×build 帧首 lodDensity 直乘（R386 CPX_FAR_MIN_DP 同式先例）——带下
 *    continue 零装填+带内 farHide01 C¹ 渐隐+**三口同因子**（复合体 blob alpha/
 *    富集反射微光 alpha/消光账 cpxPresence——看不见的云不减光，物理一致；
 *    R385c 锚字面 baseA 行与 enrichAlpha01 乘积零翻新=淡出乘在装填口保义）；
 *    观感=星图全景纯星空，飞近才见云（判词链：星场→稀粗丝→纱幔→raymarch）；
 *  - **刀2 锚（丝缕频率锁定——判词「丝缕基因的尺度变换非基因改变」）**：
 *    NEBULA_FS 第五件 uFreqScale（默认 1.0=既有设计基因原样）+q.xy 标度一行式
 *    （q 构造行字面原样=R383 家族基因锚存活）+稀疏化 gateLo（scale=1 恒等
 *    0.40）+装配 scale=clamp(rPx/CPX_FIL_REF_DP×密度, CPX_FIL_FLOOR, 1) 槽 9
 *    装填+雾族行恒 1+TasteGlRenderer 三行管路；
 *  - 行为例（GlFrameBuilder 纯 JVM mdpi 恒等档）：刀1 带下零装填/带内 C¹ 渐隐
 *    闭环重导（含 R386 裁层带叠加保义）/带外满档恒等/密度档带随密度换算；
 *    刀2 远档地板值/中档 rPx÷REF 闭环/近档恒 1/雾族行恒 1 零外溢。
 */
class R388WiringSourceTest {

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

    private fun builder(): String = src("dev/tgfeed/ui/gl/GlFrameBuilder.kt")

    private fun glShaders(): String = src("dev/tgfeed/ui/gl/GlShaders.kt")

    private fun renderer(): String = src("dev/tgfeed/ui/gl/TasteGlRenderer.kt")

    /** buildComplexesR373 函数体（到 buildNebulas 前——R373/R386 同款切法） */
    private fun cpxBodyOf(b: String): String {
        val start = b.indexOf("private fun buildComplexesR373")
        assertTrue("buildComplexesR373 必须在场", start >= 0)
        return b.substring(start, b.indexOf("private fun buildNebulas"))
    }

    /** NEBULA_FS 程序文本（GlShaders——刀2 频率标度件+丝缕基因保义旁证锚原料） */
    private fun nebulaFsOf(gs: String): String {
        val start = gs.indexOf("const val NEBULA_FS =")
        assertTrue("NEBULA_FS 必须在场", start >= 0)
        return gs.substring(start, gs.indexOf("\"\"\"", start + 30))
    }

    // ==================== 刀1 源锚：远档隐没带（看不清就不画） ====================

    /**
     * ① 隐没带 dp 双阈单源锚：CPX_FAR_HIDE_MIN_DP=60f/CPX_FAR_HIDE_MAX_DP=110f
     * 各恰一次声明（dp 基准=mdpi 恒等档 60/110px 数值原样）+帧字段初值恒等档+
     * build 帧首单口直乘双条（R386 CPX_FAR_MIN_DP 同式第五/六条——同 dp 同路由
     * =跨密度形态一致）。
     */
    @Test
    fun `刀1隐没带_dp双阈单源与帧首直乘`() {
        val b = builder()
        assertEquals(
            "隐没带内沿单源恰一次（dp 基准 90dp——校准轮 1 定值：带下不画）",
            1,
            Regex("const val CPX_FAR_HIDE_MIN_DP = 90f").findAll(b).count(),
        )
        assertEquals(
            "隐没带外沿单源恰一次（dp 基准 200dp——校准轮 1 定值：带内 C¹ 渐隐）",
            1,
            Regex("const val CPX_FAR_HIDE_MAX_DP = 200f").findAll(b).count(),
        )
        assertTrue(
            "build 帧首单口直乘双条在场（隐没带双阈 dp×density——R386 同式第五/六条）",
            Regex("cpxFarHideMinPx = CPX_FAR_HIDE_MIN_DP \\* lodDensity").containsMatchIn(b) &&
                Regex("cpxFarHideMaxPx = CPX_FAR_HIDE_MAX_DP \\* lodDensity").containsMatchIn(b),
        )
        assertTrue(
            "帧字段初值=mdpi 恒等档（build 必重算——JVM 缺省档与基线一致）",
            Regex("private var cpxFarHideMinPx = CPX_FAR_HIDE_MIN_DP").containsMatchIn(b) &&
                Regex("private var cpxFarHideMaxPx = CPX_FAR_HIDE_MAX_DP").containsMatchIn(b),
        )
    }

    /**
     * ② 三口同因子锚：farHide01 单源（全文件恰一定义+消费恰两处全在复合体域——
     * 雾族行零接触）+带下 continue 两处（复合体 hideR/富集 rPx 各一）+presence
     * 同乘（消光账同步出让——看不见的云不减光）+blob/enrich 装填口随乘（baseA
     * 行与 enrichAlpha01 原乘积字面零翻新=R385c 锚保义旁证）。
     */
    @Test
    fun `刀1三口同因子_farHide单源与带下continue`() {
        val b = builder()
        val cpx = cpxBodyOf(b)
        assertEquals(
            "farHide01 消费恰两处（复合体带+富集带——同一病类同带）",
            2,
            Regex("farHide01\\(").findAll(cpx).count(),
        )
        assertTrue(
            "farHide01 单源定义恰一次（带内渐隐半边单源——C¹）",
            Regex("private fun farHide01\\(rPx: Float\\): Float").containsMatchIn(b),
        )
        assertTrue(
            "复合体带下 continue 在场（hideR < cpxFarHideMinPx——看不清就不画）",
            Regex("if \\(hideR < cpxFarHideMinPx\\) continue").containsMatchIn(cpx),
        )
        assertTrue(
            "富集带下 continue 在场（rPx < cpxFarHideMinPx——同一病类同带）",
            Regex("if \\(rPx < cpxFarHideMinPx\\) continue").containsMatchIn(cpx),
        )
        assertTrue(
            "消光账同因子（cpxPresence 乘 farHide——看不见的云不减光，物理一致）",
            cpx.contains("cpxPresence[ci] = (baseCap * nearFade * farHide) / DeepSpaceNebula.CPX_ALPHA_MAX"),
        )
        assertTrue(
            "复合体 blob 装填口随乘（baseA 行原字面零改——淡出乘在装填口）",
            cpx.contains("baseA * flocAlphaPack[k] * farHide") &&
                cpx.contains("val baseA = baseCap * nearFade + cpxPulse[ci]"),
        )
        assertTrue(
            "富集装填口随乘（enrichAlpha01 原乘积字面前缀保义——R385c 锚零翻新）",
            cpx.contains("DeepSpaceNebula.enrichAlpha01() * reflectMult * nearFade * farHide"),
        )
    }

    // ==================== 刀1 行为例（GlFrameBuilder 纯 JVM——mdpi 恒等档） ====================

    private val w = 1080f
    private val h = 2400f
    private val focal = TasteSpace.focalOf(w, h) // ≈1006.86——GlFrameBuilderTest 同款

    private fun frame(
        complexes: List<DeepSpaceNebula.Complex> = emptyList(),
        enrichClouds: List<DeepSpaceNebula.EnrichCloud> = emptyList(),
        nebulae: List<TasteNebula.NebulaCluster> = emptyList(),
    ) = TasteFlightFrame(
        cam = TasteSpace.SpaceCam.ORIGIN,
        stars = emptyList(),
        edges = emptyList(),
        engine = TasteFlight.FxEngine(),
        genesisOn = false,
        genesisSpan = TasteFlight.GenesisSpan(0L, 0L),
        genesisT01 = 1f,
        playingChatId = 0L,
        selectedId = 0L,
        labelMinPx = 24f,
        labelTextPx = 30f,
        nebulae = nebulae,
        complexes = complexes,
        enrichClouds = enrichClouds,
    )

    private fun cpxOps(b: GlFrameBuilder): List<Int> =
        (0 until b.opCount).filter { b.opType[it] == GlFrameBuilder.OP_NEBULA }

    /** 带内渐隐因子闭环重导（镜像 farHide01 装配式——测试侧独立再推导；校准轮 1 带 [90,200]dp） */
    private fun farHideRef(rPx: Float, lo: Float = 90f, hi: Float = 200f): Float {
        val k = ((rPx - lo) / (hi - lo)).coerceIn(0f, 1f)
        return k * k * (3f - 2f * k)
    }

    @Test
    fun `刀1行为_带下隐没零装填（星图全景纯星空）`() {
        // rPx0=800×focal/20000≈40px<90px（mdpi 恒等档带下）——看不清就不画
        val cpx = DeepSpaceNebula.Complex(0f, 0f, 20000f, 800f, 20f, 0f, 77, 0.5f, 0f, 9)
        val enrich = DeepSpaceNebula.EnrichCloud(0f, 0f, 20000f, 600f, 0xFF8090A8.toInt(), 88, 12)
        val b = GlFrameBuilder()
        b.build(frame(listOf(cpx), listOf(enrich)), w, h)
        assertEquals("带下隐没=复合体+富集全零装填（诚实缺省同族）", 0, cpxOps(b).size)
        assertEquals("复合体独立预算行计数零", 0, b.nebCpxCount)
    }

    @Test
    fun `刀1行为_带内C1渐隐alpha闭环（R386裁层带叠加保义）`() {
        // rPx0=800×focal/6196≈130px∈(90,200) 带内——C¹ 三成档渐隐（校准轮 1 带）
        val cpx = DeepSpaceNebula.Complex(0f, 0f, 6196f, 800f, 20f, 0f, 77, 0.5f, 0f, 9)
        val b = GlFrameBuilder()
        b.build(frame(listOf(cpx)), w, h)
        val ops = cpxOps(b)
        // R386 裁层（rPx0≈130<150px）单层——隐没带与裁层带叠加=单 blob op
        assertEquals("带内仍走 R386 裁层单层（op 恰一）", 1, ops.size)
        // 闭环重导（镜像装配：投影→rPx0→farHide→配比包→baseA 乘链）
        val cam = TasteSpace.SpaceCam.ORIGIN
        val basis = TasteSpace.basis(cam.yawDeg, cam.pitchDeg, FloatArray(9))
        val projT = FloatArray(4)
        val fOff = FloatArray(3)
        val pack = FloatArray(FogFlocFx.BLOB_COUNT_MAX)
        val seed = DeepSpaceNebula.churnSeedOf(77, 20f)
        val flocSeed = FogFlocFx.flocSeedOf(seed)
        var blobCount = FogFlocFx.blobCountOf(flocSeed)
        assertTrue(TasteSpace.project(0f, 0f, 6196f, cam, focal, w / 2f, h / 2f, projT, basis))
        val rPx0 = TasteNebula.fogPx(800f, projT[3])
        if (blobCount > 1 && rPx0 < 150f) blobCount = 1 // R386 裁层镜像
        FogFlocFx.blobAlphaPack01(flocSeed, pack, blobCount)
        // blob k=0 屏位闭环（层深 +0.5R 前层——复合体质心 6196 加层深与错位向）
        FogFlocFx.blobOffset01(flocSeed, 0, fOff)
        val lx = basis[0] * 0.5f * 800f + fOff[0] * 800f
        val ly = basis[1] * 0.5f * 800f + fOff[1] * 800f
        val lz = 6196f + basis[2] * 0.5f * 800f + fOff[2] * 800f
        assertTrue(TasteSpace.project(lx, ly, lz, cam, focal, w / 2f, h / 2f, projT, basis))
        val halfExp = TasteNebula.fogPx(800f * FogFlocFx.FRONT_R * FogFlocFx.blobRadius01(flocSeed, 0), projT[3])
        val i = b.opA[ops[0]] * GlFrameBuilder.NEB_STRIDE
        assertEquals("blob 屏位 x 闭环（远档隐没不改几何）", projT[0], b.nebBuf[i], 1e-3f)
        assertEquals("blob 半径闭环（隐没只动 alpha 不动几何）", halfExp, b.nebBuf[i + 2], 1e-3f)
        val baseA = DeepSpaceNebula.emissionAlpha01(0f) // ion01=0 → 0.066（gas 关=1 恒等）
        val expected = baseA * 1f * pack[0] * farHideRef(rPx0)
        assertEquals("带内 alpha=baseA×配比×farHide 闭环（三口同因子半边）", expected, b.nebBuf[i + 8], 1e-4f)
        assertTrue("带内渐隐档 alpha 低于满档（C¹ 带内减光）", b.nebBuf[i + 8] < baseA * pack[0] * 0.9f)
    }

    @Test
    fun `刀1行为_富集带内同带渐隐闭环`() {
        // cz=3300：富集 blob rPx=600×[0.58,1]×focal/3300≈[106,183]px——恒落带内
        val enrich = DeepSpaceNebula.EnrichCloud(0f, 0f, 3300f, 600f, 0xFF8090A8.toInt(), 88, 12)
        val b = GlFrameBuilder()
        b.build(frame(enrichClouds = listOf(enrich)), w, h)
        val ops = cpxOps(b)
        assertEquals("富集单层微光块 op 恰一（同一病类同带）", 1, ops.size)
        val cam = TasteSpace.SpaceCam.ORIGIN
        val basis = TasteSpace.basis(cam.yawDeg, cam.pitchDeg, FloatArray(9))
        val projT = FloatArray(4)
        assertTrue(TasteSpace.project(0f, 0f, 3300f, cam, focal, w / 2f, h / 2f, projT, basis))
        val flocSeed = FogFlocFx.flocSeedOf(DeepSpaceNebula.churnSeedOf(88, 12f))
        val rPxE = TasteNebula.fogPx(600f * FogFlocFx.blobRadius01(flocSeed, 0), projT[3])
        val i = b.opA[ops[0]] * GlFrameBuilder.NEB_STRIDE
        val expected = DeepSpaceNebula.enrichAlpha01() * 1f * 1f * farHideRef(rPxE)
        assertEquals("富集 alpha=帽×reflectMult×nearFade×farHide 闭环", expected, b.nebBuf[i + 8], 1e-4f)
    }

    @Test
    fun `刀1行为_带外满档恒等（farHide=1 既有alpha原样）`() {
        // rPx0=800×focal/3800≈212px>200px 带外——满档=既有行为恒等（R386 阈上多层档）
        val cpx = DeepSpaceNebula.Complex(0f, 0f, 3800f, 800f, 20f, 0f, 77, 0.5f, 0f, 9)
        val b = GlFrameBuilder()
        b.build(frame(listOf(cpx)), w, h)
        val ops = cpxOps(b)
        assertTrue("带外多层档照常装填（≥R386 阈=blob 2~3）", ops.size in 2..3)
        val cam = TasteSpace.SpaceCam.ORIGIN
        val basis = TasteSpace.basis(cam.yawDeg, cam.pitchDeg, FloatArray(9))
        val projT = FloatArray(4)
        val pack = FloatArray(FogFlocFx.BLOB_COUNT_MAX)
        val seed = DeepSpaceNebula.churnSeedOf(77, 20f)
        val flocSeed = FogFlocFx.flocSeedOf(seed)
        val blobCount = FogFlocFx.blobCountOf(flocSeed)
        FogFlocFx.blobAlphaPack01(flocSeed, pack, blobCount)
        assertTrue(TasteSpace.project(0f, 0f, 3800f, cam, focal, w / 2f, h / 2f, projT, basis))
        val rPx0 = TasteNebula.fogPx(800f, projT[3])
        assertEquals("带外 rPx0>R386 阈（多层档域）", true, rPx0 > 150f)
        val baseA = DeepSpaceNebula.emissionAlpha01(0f)
        val i = b.opA[ops[0]] * GlFrameBuilder.NEB_STRIDE
        assertEquals("带外 alpha=baseA×配比（×1 恒等——隐没带零外溢）", baseA * pack[0], b.nebBuf[i + 8], 1e-4f)
    }

    @Test
    fun `刀1行为_密度档带随密度换算（同dp同路由）`() {
        // 同一世界复合体 rPx0≈140px（cz=5753）：mdpi 档∈(90,150) 带内渐隐且
        // R386 裁层单层（op 恰一）；2.5 档（小米 400dpi）带=[225,500]px——
        // 140px<225px=带下隐没
        val cpx = DeepSpaceNebula.Complex(0f, 0f, 5753f, 800f, 20f, 0f, 77, 0.5f, 0f, 9)
        val bMdpi = GlFrameBuilder()
        bMdpi.build(frame(listOf(cpx)), w, h)
        assertEquals("mdpi 恒等档：rPx0≈140px 带内（渐隐装填在场）", 1, cpxOps(bMdpi).size)
        val bXhdpi = GlFrameBuilder()
        bXhdpi.lodDensity = 2.5f
        bXhdpi.build(frame(listOf(cpx)), w, h)
        assertEquals("2.5 密度档：同 rPx0 落带下（隐没带随密度换算=同 dp 同路由）", 0, cpxOps(bXhdpi).size)
    }

    // ==================== 刀2 源锚：丝缕频率锁定（尺度变换非基因改变） ====================

    /**
     * ③ 频率锁定常量单源锚：CPX_FIL_REF_DP=300f/CPX_FIL_FLOOR=0.35f 各恰一次
     * 声明+build 帧首单口直乘第七条（帧字段初值=mdpi 恒等档——300dp 参考屏
     * 半径=校准轮 1 定值：病灶复合体全景 blob 落 0.40~0.92 标度域=稀粗丝档）。
     */
    @Test
    fun `刀2频率锁定_REF与FLOOR单源与帧首直乘`() {
        val b = builder()
        assertEquals(
            "频率锁定参考屏半径单源恰一次（dp 基准 300dp——校准轮 1 定值）",
            1,
            Regex("const val CPX_FIL_REF_DP = 300f").findAll(b).count(),
        )
        assertEquals(
            "频率标度下限单源恰一次（纯比值 0.35——防退化单团地板）",
            1,
            Regex("const val CPX_FIL_FLOOR = 0\\.35f").findAll(b).count(),
        )
        assertTrue(
            "build 帧首单口直乘第七条在场（REF dp×density——帧首 dp 阈族）",
            Regex("cpxFilRefPx = CPX_FIL_REF_DP \\* lodDensity").containsMatchIn(b),
        )
        assertTrue(
            "帧字段初值=mdpi 恒等档（build 必重算）",
            Regex("private var cpxFilRefPx = CPX_FIL_REF_DP").containsMatchIn(b),
        )
    }

    /**
     * ④ shader 第五件与基因保义锚：uFreqScale 声明在场（默认 1.0=既有设计基因
     * 原样）+q.xy 标度一行式（**q 构造行 `vec3(st * 2.1, uSeed)` 字面原样**
     * ——R383 家族基因锚存活）+稀疏化 gateLo 式（scale=1 位移零=R358 门基因
     * 恒等 0.40）+弥散保义/脊线/各向异性/暗尘带/色温分层五件基因字面原样
     * （R351/R358 锚——本批零接触旁证）。
     */
    @Test
    fun `刀2shader_第五件与qxy一行式基因保义`() {
        val fs = nebulaFsOf(glShaders())
        assertTrue(
            "第五件 uFreqScale 声明在场（默认 1.0=既有设计基因原样）",
            fs.contains("uniform float uFreqScale;"),
        )
        assertTrue(
            "q 构造行字面原样（R383 家族基因 * 2.1, uSeed 锚存活——z=种子身份槽不动）",
            fs.contains("vec3 q = vec3(st * 2.1, uSeed);"),
        )
        assertTrue(
            "q.xy 标度一行式在场（同一噪声场 zoom——尺度变换非基因改变）",
            fs.contains("q.xy *= uFreqScale;"),
        )
        assertTrue(
            "稀疏化 gateLo 式在场（远档只留最亮数缕；scale=1 位移零=门基因 0.40 恒等）",
            fs.contains("float gateLo = 0.40 + 0.24 * (1.0 - uFreqScale);") &&
                fs.contains("float a = smoothstep(gateLo, 0.82, d2) * fall * uAlpha;"),
        )
        // 基因五件字面原样（弥散保义红线+R358 四件——本批零接触旁证）
        assertTrue("径向 falloff 原样（R351 弥散保义——边缘零锐化）", fs.contains("float fall = 1.0 - smoothstep(0.12, 1.0, d);"))
        assertTrue("脊线噪声原样（R358 ①丝缕层——R392 判词①载体=fieldAt 烘焙体采样，构造基因字面原样）", fs.contains("float ridge = 1.0 - abs(2.0 * fieldAt(q * 2.3 + warp * 1.1) - 1.0);"))
        assertTrue("各向异性 1.8:1 原样（R358 ②）", fs.contains("st.y *= 1.8;"))
        assertTrue("暗尘带减法剪影原样（R358 ③）", fs.contains("a *= 1.0 - 0.55 * lane;"))
        assertTrue("色温分层原样（R358 ④零新色源）", fs.contains("vec3 col = uColor * (0.70 + 1.05 * d2);"))
    }

    /**
     * ⑤ 装填与回放管路锚：NEB_STRIDE=10（槽 9=频率标度）+雾族行 addNebula 槽 9
     * 恒 1f（既有基因恒等——本批病灶=复合体行）+addNebulaCpx 槽 9=freqScale+
     * 装配口 clamp 单源恰两处（复合体+富集：rPx/cpxFilRefPx coerceIn
     * (CPX_FIL_FLOOR, 1f)）+TasteGlRenderer 三行管路（uniform 位+定位查询+
     * 槽 9 上传——装配值进 shader 必经管路，缺省 0=零频纯色新病防）。
     */
    @Test
    fun `刀2管路_槽9装填与renderer三行管路`() {
        val b = builder()
        val r = renderer()
        assertEquals(
            "NEB_STRIDE=10（槽 9=频率标度——随步长同步扩容）",
            "10",
            Regex("const val NEB_STRIDE = (\\d+)").find(b)?.groupValues?.get(1),
        )
        assertTrue("雾族行槽 9 恒 1f（既有基因恒等）", b.contains("nebBuf[i + 9] = 1f"))
        assertTrue("复合体行槽 9=freqScale 装填", b.contains("nebBuf[i + 9] = freqScale"))
        val cpx = cpxBodyOf(b)
        assertEquals(
            "装配口 clamp 单源恰两处（复合体+富集——每 quad 自身 rPx 口径）",
            2,
            Regex("\\(rPx / cpxFilRefPx\\)\\.coerceIn\\(CPX_FIL_FLOOR, 1f\\)").findAll(cpx).count(),
        )
        assertTrue("renderer uniform 位声明（第五件管路）", r.contains("private var uFreqNeb = -1"))
        assertTrue("renderer 定位查询在场（uFreqScale）", r.contains("glGetUniformLocation(progNebula, \"uFreqScale\")"))
        assertTrue("renderer 槽 9 上传在场（OP_NEBULA 回放）", r.contains("glUniform1f(uFreqNeb, nb[i + 9])"))
    }

    @Test
    fun `刀2行为_中档槽9闭环（rPx比REF线性档）`() {
        // cz=5000：复合体 rPx0≈161px∈(90,200) 带内渐隐可见；blob k=0 rPx=
        // 0.9×blobR×800×focal/5400≈[78,134]px——标度域（地板..0.45）闭环可验
        val cpx = DeepSpaceNebula.Complex(0f, 0f, 5000f, 800f, 20f, 0f, 77, 0.5f, 0f, 9)
        val b = GlFrameBuilder()
        b.build(frame(listOf(cpx)), w, h)
        val ops = cpxOps(b)
        assertTrue("带内渐隐档照常装填（blob 2~3）", ops.size in 2..3)
        val cam = TasteSpace.SpaceCam.ORIGIN
        val basis = TasteSpace.basis(cam.yawDeg, cam.pitchDeg, FloatArray(9))
        val projT = FloatArray(4)
        val fOff = FloatArray(3)
        val flocSeed = FogFlocFx.flocSeedOf(DeepSpaceNebula.churnSeedOf(77, 20f))
        // k=0 前层 blob 屏位闭环（层深 +0.5R——复合体质心 5000 加层深与错位向）
        FogFlocFx.blobOffset01(flocSeed, 0, fOff)
        val lz = 5000f + basis[2] * 0.5f * 800f + fOff[2] * 800f
        val lx = basis[0] * 0.5f * 800f + fOff[0] * 800f
        val ly = basis[1] * 0.5f * 800f + fOff[1] * 800f
        assertTrue(TasteSpace.project(lx, ly, lz, cam, focal, w / 2f, h / 2f, projT, basis))
        val rPx0 = TasteNebula.fogPx(800f * FogFlocFx.FRONT_R * FogFlocFx.blobRadius01(flocSeed, 0), projT[3])
        val i = b.opA[ops[0]] * GlFrameBuilder.NEB_STRIDE
        val scaleExp = (rPx0 / 300f).coerceIn(0.35f, 1f)
        assertEquals("槽 9=clamp(rPx/REF, floor, 1) 闭环（每 quad 自身 rPx 口径）", scaleExp, b.nebBuf[i + 9], 1e-4f)
        assertTrue("标度域下沿=地板 0.35（远档粗丝档=clamp 下沿在身）", b.nebBuf[i + 9] >= 0.35f)
    }

    @Test
    fun `刀2行为_近档槽9恒1（既有设计基因原样）`() {
        // 富集云 cz=2000 radius=1600：blob rPx=1600×[0.58,1]×focal/2000≥467px≫
        // 300px——scale=1 恒等（近档=既有 NEBULA_FS 基因原样；rPx>500px=隐没
        // 带外满档同帧验证）
        val enrich = DeepSpaceNebula.EnrichCloud(0f, 0f, 2000f, 1600f, 0xFF8090A8.toInt(), 88, 12)
        val b = GlFrameBuilder()
        b.build(frame(enrichClouds = listOf(enrich)), w, h)
        val ops = cpxOps(b)
        assertEquals(1, ops.size)
        val i = b.opA[ops[0]] * GlFrameBuilder.NEB_STRIDE
        assertEquals("近档槽 9=1.0（scale→1=既有设计基因原样——判词）", 1f, b.nebBuf[i + 9], 0f)
    }

    @Test
    fun `刀2行为_雾族行槽9恒1（本批病灶复合体行零外溢）`() {
        // 雾团（R281 雾族）：槽 9 恒 1f——雾族既有基因恒等（GlFrameBuilderTest
        // 雾絮化闭环同款布点）
        val neb = TasteNebula.NebulaCluster(
            cx = 0f, cy = 0f, cz = 800f, radius = 200f, colorArgb = 0xFF8855AA.toInt(),
            count = 7, dominant = "视频",
        )
        val b = GlFrameBuilder()
        b.build(frame(nebulae = listOf(neb)), w, h)
        val ops = cpxOps(b)
        assertTrue("雾族 blob op 在场（2~3）", ops.size in 2..3)
        for (op in ops) {
            val i = b.opA[op] * GlFrameBuilder.NEB_STRIDE
            assertEquals("雾族行槽 9 恒 1f（既有基因恒等——远档形态另案）", 1f, b.nebBuf[i + 9], 0f)
        }
    }
}
