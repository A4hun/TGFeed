package dev.tgfeed.ui

import dev.tgfeed.core.tastemap.AstroFacts
import dev.tgfeed.core.tastemap.DeepSpaceNebula
import dev.tgfeed.core.tastemap.EclipseFx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R385 P4 语义联动族 wiring 源码锚+行为例（R373/R386WiringSourceTest 同款 user.dir
 * 源定位——GL 渲染管线无 JVM 直测面以源锚锁结构面；core 纯件行为例直测）：
 *  - 消光分支单源：EclipseFx 复合体级新分支（透射下限 0.75 候选带恰一次+柱密度=
 *    团级 extinctionOverlap01 几何单源复用+色温偏移=EXTINCTION_SHIFT_K 团级常量
 *    单源消费零第二份数字）；团级语义四件原样（EclipseFxTest 行为半体承重）；
 *  - 起飞点消费：NOVA 档 novaTakeoffR385（complexIndexOf 容纳判定→云内聚尘
 *    novaGatherR385；SUPERNOVA 档不起飞=死亡非诞生）；complexesR385 快照链
 *    （Stage 装配期发布→帧循环→directorTickR282 穿透）；
 *  - enrich 门：scanComplexPulseR385 共在窗 CYCLE_PULSE_WINDOW_D2 单源直引
 *    零重定义+cyclePulse01 同口消费多源取 max+零超新星早退 O(0)；
 *  - 创世纪 gas 档：genesisGasMult01/genesisReflectMult01（CmbBackdropFx.temp01Of
 *    单源同源联动——行为例直测 1.35 帽/0.15 地板/关=1 恒等）；GL 两半边同乘
 *    同帽（GlFrameBuilder billboard+TasteGlRenderer alphaCap——crossfade α 能量
 *    守恒跨半边成立）；
 *  - 词条行：AstroFacts nebula 词条 inUniverse 增段+nebulaComplexLine 单口
 *    （摇篮=0=null 整行不出——诚实门）；TasteMapScreen 面板/词条浏览器双消费
 *    接线（complexIndexOf 容纳同口计数）；
 *  - 诚实门负锚：slideOf 恒等直通两档+新云半径自 0 凝出+消散云即刻退场
 *    （行为例）；DeepSpaceSlideR385 滑移账 frameOfR385 null=恒等直通帧零分配
 *    （源锚）——缺据不出/恒等不为无窗造行为。
 */
class R385cWiringSourceTest {

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

    private fun eclipse(): String = src("dev/tgfeed/core/tastemap/EclipseFx.kt")

    private fun deepSpace(): String = src("dev/tgfeed/core/tastemap/DeepSpaceNebula.kt")

    private fun builder(): String = src("dev/tgfeed/ui/gl/GlFrameBuilder.kt")

    private fun renderer(): String = src("dev/tgfeed/ui/gl/TasteGlRenderer.kt")

    private fun screen(): String = src("dev/tgfeed/ui/TasteMapScreen.kt")

    private fun facts(): String = src("dev/tgfeed/core/tastemap/AstroFacts.kt")

    private fun cpx(bodyStart: String, bodyEnd: String, text: String): String {
        val start = text.indexOf(bodyStart)
        assertTrue("$bodyStart 必须在场", start >= 0)
        val end = text.indexOf(bodyEnd, start)
        assertTrue("$bodyEnd 必须在 $bodyStart 之后", end > start)
        return text.substring(start, end)
    }

    /**
     * ① 消光分支单源：透射下限 0.75 候选带常量恰一次；复合体级三函数与团级
     * 单源复用关系（柱密度几何=extinctionOverlap01 调用方复用、变暗=透射下限
     * 推导、色温偏移=EXTINCTION_SHIFT_K 直引）；**零第二份数字**（-300f 全文件
     * 恰一次=团级常量唯一拷贝，改就一起改）；团级常量在场原样（EXTINCTION_
     * DIM_MAX01=0.20f——EclipseFxTest 行为半体锁定不变量）。行为例：柱密度=
     * 重合度×在场度钳域；复合体级变暗帽 0.25；色温偏移域 [-300,0] 与团级同源。
     */
    @Test
    fun 消光分支单源_透射下限候选带与团级常量单源复用() {
        val e = eclipse()
        assertEquals(
            "EXTINCTION_CPX_TRANSMISSION_MIN=0.75f 候选带单源恰一次（真机定稿单点改）",
            1,
            Regex("const val EXTINCTION_CPX_TRANSMISSION_MIN = 0\\.75f").findAll(e).count(),
        )
        assertEquals(
            "EXTINCTION_SHIFT_K=-300f 团级常量全文件恰一次（复合体级单源消费零第二份数字）",
            1,
            Regex("const val EXTINCTION_SHIFT_K = -300f").findAll(e).count(),
        )
        // 复合体级三函数体：单源复用关系逐条在场
        val column = cpx("fun extinctionCpxColumn01", "fun extinctionCpxDim01", e)
        assertTrue("柱密度=屏面重合度×在场度（两参钳域乘法）", column.contains("overlap01.coerceIn(0f, 1f) * presence01.coerceIn(0f, 1f)"))
        val dim = cpx("fun extinctionCpxDim01", "fun extinctionCpxShiftK", e)
        assertTrue("变暗量=1−透射下限推导（非独立数字）", dim.contains("(1f - EXTINCTION_CPX_TRANSMISSION_MIN)"))
        val shift = e.substring(e.indexOf("fun extinctionCpxShiftK"))
        assertTrue("色温偏移=EXTINCTION_SHIFT_K 直引（团级常量单源）", shift.contains("EXTINCTION_SHIFT_K"))
        // 团级语义零碰旁证：团级常量原样在场（行为不变量由 EclipseFxTest 承重）
        assertEquals("团级变暗帽 0.20f 原样恰一次（团级语义零碰）", 1, Regex("const val EXTINCTION_DIM_MAX01 = 0\\.20f").findAll(e).count())
        // 行为例（core 纯件直测）
        assertEquals(0.40f, EclipseFx.extinctionCpxColumn01(0.5f, 0.8f), 1e-4f)
        assertEquals(0.25f, EclipseFx.extinctionCpxDim01(1f), 1e-4f)
        assertEquals(0.125f, EclipseFx.extinctionCpxDim01(0.5f), 1e-4f)
        assertEquals(-300f, EclipseFx.extinctionCpxShiftK(1f), 1e-3f)
        assertEquals(-150f, EclipseFx.extinctionCpxShiftK(0.5f), 1e-3f)
        assertEquals(0f, EclipseFx.extinctionCpxShiftK(0f), 1e-4f)
    }

    /**
     * ② 起飞点消费：complexesR385 快照链（Stage 装配期 LaunchedEffect 发布→
     * GalaxyLogicCtx 字段→galaxyFrameLoop→directorTickR282 穿透）+NOVA 档门
     * （SUPERNOVA 不起飞=爆发是死亡非诞生）+novaTakeoffR385 容纳判定与聚尘
     * 消费（novaGatherR385——与 burst 共用爆散账）。行为例：complexIndexOf
     * 容纳（域内/域外/多团取最近/-1 诚实缺省）。
     */
    @Test
    fun 起飞点消费_novaTakeoff接线与NOVA档门() {
        val s = screen()
        // 快照链四环
        assertTrue("Stage 装配期发布在场（ctx.complexesR385 帧循环只读）", s.contains("logicCtx.complexesR385.value = deepSpaceR373.complexes"))
        assertTrue("GalaxyLogicCtx 字段在场（主线程单写读）", s.contains("val complexesR385: MutableState<List<DeepSpaceNebula.Complex>>"))
        assertTrue("帧循环经 ctx 只读穿透（directorTickR282 调用点尾参）", s.contains("logicCtx.complexesR385.value,"))
        assertTrue("directorTickR282 参数穿透在场", s.contains("complexesR385: List<DeepSpaceNebula.Complex>,"))
        // NOVA 档门（SUPERNOVA 负锚同体）
        assertTrue(
            "NOVA 档才起飞（SUPERNOVA 不起飞=死亡非诞生语义只归 enrich 半边）",
            s.contains("if (d.kind == UniverseDirector.EventKind.NOVA && complexesR385.isNotEmpty())"),
        )
        // novaTakeoffR385 体：容纳判定+聚尘消费+云外诚实缺省
        val takeoff = cpx("private fun novaTakeoffR385", "/** R206 彗星爬虫监视体", s)
        assertTrue("容纳判定走 complexIndexOf（-1=云外诚实缺省）", takeoff.contains("DeepSpaceNebula.complexIndexOf("))
        assertTrue("云内=聚尘消费 novaGatherR385（与 burst 共用爆散账）", takeoff.contains("ctx.engine.novaGatherR385("))
        assertTrue("云外=零起飞老演出原样（探针行诚实缺省非缺陷）", takeoff.contains("云外复活"))
        // 行为例：容纳判定（域内/域外/多团取最近）
        val c0 = DeepSpaceNebula.Complex(0f, 0f, 0f, 100f, 10f, 0f, 11, 0.5f, 0.5f, 8)
        val c1 = DeepSpaceNebula.Complex(300f, 0f, 0f, 100f, 10f, 0f, 22, 0.5f, 0.5f, 8)
        val cpxList = listOf(c0, c1)
        assertEquals("域内取该团", 0, DeepSpaceNebula.complexIndexOf(50f, 0f, 0f, cpxList))
        assertEquals("域外=-1 诚实缺省", -1, DeepSpaceNebula.complexIndexOf(150f, 0f, 0f, cpxList))
        assertEquals("多团容纳取最近（确定性破平距离序）", 1, DeepSpaceNebula.complexIndexOf(220f, 0f, 0f, cpxList))
        assertEquals("空表=-1（零团零成本）", -1, DeepSpaceNebula.complexIndexOf(0f, 0f, 0f, emptyList()))
    }

    /**
     * ③ enrich 门：scanComplexPulseR385 共在窗=EmissionNebulaFx.
     * CYCLE_PULSE_WINDOW_D2 **单源直引零重定义**（GlFrameBuilder 无本地重定义
     * 负锚）；cyclePulse01 同口消费（CYCLE_PULSE 参数族）；多源取 max；零超
     * 新星早退 O(0)；supernovaFlashes GL 线程直读（R323 同口径竞态良性）；
     * 脉冲叠加=星胚点脉冲同款加法（baseA=baseCap×nearFade+cpxPulse）。
     */
    @Test
    fun enrich门_共在窗单源直引与零超新星早退() {
        val b = builder()
        val scan = cpx("private fun scanComplexPulseR385", "private fun buildNebulas", b)
        assertTrue(
            "共在窗单源直引（EmissionNebulaFx.CYCLE_PULSE_WINDOW_D2——零重定义）",
            scan.contains("if (d2 >= EmissionNebulaFx.CYCLE_PULSE_WINDOW_D2) continue"),
        )
        assertTrue("CYCLE_PULSE 参数族同口消费（cyclePulse01）", scan.contains("val pulse = EmissionNebulaFx.cyclePulse01("))
        assertTrue("多源取 max（最近云=共在窗+max 脉冲实现）", scan.contains("if (pulse > cpxPulse[i]) cpxPulse[i] = pulse"))
        assertTrue("零超新星=恒零门外零行为（早退 O(0)）", scan.contains("val sn = f.engine.supernovaFlashes") && scan.contains("if (sn.isEmpty()) return"))
        // 单源负锚：GlFrameBuilder 全文件零 CYCLE_PULSE_WINDOW_D2 本地重定义
        assertEquals(
            "GlFrameBuilder 零 CYCLE_PULSE_WINDOW_D2 本地声明（R323 单源不破）",
            0,
            Regex("const val CYCLE_PULSE_WINDOW_D2").findAll(b).count(),
        )
        // 脉冲叠加口径：复合体 alpha 加法（R323 星胚点脉冲同款）
        assertTrue(
            "脉冲叠加=加法口径（baseA=baseCap×nearFade+cpxPulse——事件窗瞬态）",
            b.contains("val baseA = baseCap * nearFade + cpxPulse[ci]"),
        )
    }

    /**
     * ④ 创世纪 gas 档：两函数行为例（gas=1..1.35 帽满档+0.35 增浓单源+反射云
     * 1→0.15 地板+关=1 恒等诚实缺省——temp01Of 单源同源联动）；GL billboard
     * 半边（gasMult 帽内钳 CPX_ALPHA_MAX+反射云 reflectMult）+体积半边
     * （TasteGlRenderer alphaCap 同 genesisGasMult01 同帽=**两半边同乘同帽**，
     * crossfade α 能量守恒跨半边成立）；渲染端零本地温度推导负锚（温度档
     * 只在 core 单源）。
     */
    @Test
    fun 创世纪gas档_同源联动与两半边同帽() {
        // 行为例（core 纯件直测）
        assertEquals("延时摄影关=1 恒等（诚实缺省零行为）", 1f, DeepSpaceNebula.genesisGasMult01(false, 0.37f), 1e-5f)
        assertEquals("早期满档=1+0.35（与 CMB 温度档同源同光标）", 1.35f, DeepSpaceNebula.genesisGasMult01(true, 0f), 1e-5f)
        assertEquals("今天=1（云先凝星后亮的「现在」态）", 1f, DeepSpaceNebula.genesisGasMult01(true, 1f), 1e-5f)
        assertTrue(
            "单调（早期更浓）",
            DeepSpaceNebula.genesisGasMult01(true, 0.2f) > DeepSpaceNebula.genesisGasMult01(true, 0.6f),
        )
        assertEquals("反射云关=1 恒等", 1f, DeepSpaceNebula.genesisReflectMult01(false, 0.4f), 1e-5f)
        assertEquals("反射云早期=0.15 地板（不为不存在的星光造亮）", 0.15f, DeepSpaceNebula.genesisReflectMult01(true, 0f), 1e-5f)
        assertEquals("反射云今天=1", 1f, DeepSpaceNebula.genesisReflectMult01(true, 1f), 1e-5f)
        // 单源：GENESIS_GAS_BOOST=0.35 恰一次
        assertEquals("GENESIS_GAS_BOOST=0.35 单源恰一次", 1, Regex("const val GENESIS_GAS_BOOST = 0\\.35f").findAll(deepSpace()).count())
        // GL billboard 半边：gasMult 帽内钳+反射云 reflectMult
        val b = builder()
        val cpxBuild = cpx("private fun buildComplexesR373", "private fun scanComplexPulseR385", b)
        assertTrue("billboard 半边 gasMult 在场（摇篮云 alpha×gasMult）", cpxBuild.contains("val gasMult = DeepSpaceNebula.genesisGasMult01(genesis, f.genesisT01)"))
        assertTrue("gas 档帽内钳（克制档帽不被创世纪越过=CPX_ALPHA_MAX）", cpxBuild.contains(".coerceAtMost(DeepSpaceNebula.CPX_ALPHA_MAX)"))
        assertTrue("反射云 reflectMult 在场（星未亮黯至地板）", cpxBuild.contains("val reflectMult = DeepSpaceNebula.genesisReflectMult01(genesis, f.genesisT01)"))
        assertTrue("反射云消费=enrichAlpha01×reflectMult×nearFade（gas 不作用于反射云）", b.contains("DeepSpaceNebula.enrichAlpha01() * reflectMult * nearFade"))
        // 体积半边：TasteGlRenderer alphaCap 同源同帽（两半边同乘——P3 能量守恒不变量维护性扩展）
        val r = renderer()
        assertTrue(
            "体积半边 alphaCap 同 genesisGasMult01 单源乘子",
            r.contains("val gasMultR385 = DeepSpaceNebula.genesisGasMult01(f.genesisOn && f.genesisSpan.valid, f.genesisT01)"),
        )
        assertTrue("体积半边同帽钳制（CPX_ALPHA_MAX——跳变 ≤8% 判据不破）", r.contains(".coerceAtMost(DeepSpaceNebula.CPX_ALPHA_MAX) * bestFade * eased"))
        // 渲染端零本地温度推导负锚（温度档只在 core 单源——两渲染文件零 1f - genesisT01 本地式）
        assertEquals("GlFrameBuilder 零本地温度推导", 0, Regex("1f - f\\.genesisT01").findAll(b).count())
        assertEquals("TasteGlRenderer 零本地温度推导", 0, Regex("1f - f\\.genesisT01").findAll(r).count())
    }

    /**
     * ⑤ 词条行：AstroFacts nebula 词条 inUniverse 增段（P2/P3/P4 现实入文四
     * 叙事锚）+nebulaComplexLine 单口（行为例：摇篮=0=null 整行不出/富集缺席
     * 截去还尘分句/计数入行）；TasteMapScreen 双消费接线（nebulaComplexReportR385
     * 容纳同口计数+成就面板行+词条浏览器 nebula 档增段行）。
     */
    @Test
    fun 词条行_增段与nebulaComplexLine单口() {
        val f = facts()
        // 词条增段四叙事锚（§3.3-4 复合体在场叙述——P2/P3/P4 现实入文）
        assertTrue("增段在场（大云悬在星系尺度）", f.contains("远处星系尺度上还悬着几团大得多的复合体云"))
        assertTrue("增段在场（云前星真实变暗偏红=消光）", f.contains("云身后的星群会真实地变暗偏红"))
        assertTrue("增段在场（超新星还尘=enrich）", f.contains("超新星的遗迹会把灰烬还给最近的云"))
        assertTrue("增段在场（早期云更浓星未亮=创世纪档）", f.contains("云先凝、星后亮"))
        assertEquals("nebulaComplexLine 单口恰一次声明", 1, Regex("fun nebulaComplexLine\\(").findAll(f).count())
        // 行为例（core 纯件直测）
        assertNull("摇篮=0=null 整行不出（全库点亮=成就非缺口）", AstroFacts.nebulaComplexLine(0, 5, 2))
        val line = AstroFacts.nebulaComplexLine(2, 3, 0)
        assertTrue("摇篮/候选计数入行", line != null && line.contains("2 团") && line.contains("3 个金矿候选"))
        assertTrue("富集云缺席=截去还尘分句（不编造还尘 0 团）", line != null && !line.contains("还尘"))
        val rich = AstroFacts.nebulaComplexLine(2, 3, 1)
        assertTrue("富集在场=还尘分句入行", rich != null && rich.contains("还尘 1 团"))
        // TasteMapScreen 双消费接线
        val s = screen()
        assertTrue(
            "容纳同口计数（complexIndexOf 单口 O(n)——最活跃候选最诚实代理）",
            s.contains("if (DeepSpaceNebula.complexIndexOf(s.spX, s.spY, s.spZ, cpx) >= 0) candidates++"),
        )
        assertTrue("成就面板报告行消费（wonderCountsLine 行后同族样式）", s.contains("nebulaComplexLineR385?.let { line ->"))
        assertTrue(
            "词条浏览器 nebula 档增段行（null=缺据整行不出）",
            s.contains("""if (bt.key == "nebula" && nebulaComplexLineR385 != null)"""),
        )
    }

    /**
     * ⑥ 诚实门负锚：slideOf 行为例（恒等直通两档=prev null/t≥1+新云半径自 0
     * 凝出+消散云即刻退场+质心线性滑移中点）；DeepSpaceSlideR385 滑移账源锚
     * （SLIDE_MS 单源直引+frameOfR385 null=恒等直通帧零分配+t≥1 自动关窗+
     * 格键身份集 diff 换代检测+diff 探针行非静默）；帧组装消费源锚
     * （frameOfR385 一次求值两字段共用直供 driftDeepSpaceR373——恒等直通
     * 零分配）；消散云负锚（slideOf 输出零 prev-only 云=即刻退场非渐隐）。
     */
    @Test
    fun 诚实门负锚_恒等直通与新云凝出消散退场() {
        // ---- 行为例：slideOf（core 纯件直测） ----
        val cA = DeepSpaceNebula.Complex(0f, 0f, 0f, 100f, 10f, 0f, 11, 0.5f, 0.5f, 8)
        val cB = DeepSpaceNebula.Complex(10f, 0f, 0f, 200f, 10f, 0f, 11, 0.5f, 0.5f, 8) // 同格键=滑移对
        val cNew = DeepSpaceNebula.Complex(500f, 0f, 0f, 50f, 10f, 0f, 33, 0.5f, 0.5f, 4) // 新云
        val eOld = DeepSpaceNebula.EnrichCloud(0f, 0f, 0f, 30f, 0x112233, 44, 5)
        val eGone = DeepSpaceNebula.EnrichCloud(0f, 0f, 0f, 30f, 0x112233, 77, 5) // 消散云（prev 独有）
        val prev = DeepSpaceNebula.Result(listOf(cA), listOf(eOld, eGone), 1f)
        // prev=null=恒等直通（内容原样——半径不凝出）
        val idNull = DeepSpaceNebula.slideOf(listOf(cB), listOf(eOld), null, 0f)
        assertTrue("prev=null 恒等直通（半径原样非凝出）", idNull.complexes[0].radius == 200f && idNull.complexes.size == 1)
        // t≥1=恒等直通
        val idDone = DeepSpaceNebula.slideOf(listOf(cB, cNew), listOf(eOld), prev, 1f)
        assertTrue("t≥1 恒等直通（终态=当代真值）", idDone.complexes[0].cx == 10f && idDone.complexes[0].radius == 200f)
        assertTrue("t≥1 新云满半径（凝出完成）", idDone.complexes[1].radius == 50f)
        // t=0：滑移对=prev 位；新云=半径 0 凝出；消散云=即刻退场
        val t0 = DeepSpaceNebula.slideOf(listOf(cB, cNew), listOf(eOld), prev, 0f)
        assertEquals("t=0 滑移对回 prev 质心（平衡位不跳变）", 0f, t0.complexes[0].cx, 1e-4f)
        assertEquals("新云 t=0 半径自 0 凝出（云先凝字面）", 0f, t0.complexes[1].radius, 1e-4f)
        assertTrue("消散云即刻退场（输出零 prev-only 云）", t0.enrichClouds.size == 1 && t0.enrichClouds[0].cellKey == 44)
        // t=0.5：C1 smoothstep e=0.5 → 质心/半径中点
        val tMid = DeepSpaceNebula.slideOf(listOf(cB), listOf(eOld), prev, 0.5f)
        assertEquals("t=0.5 质心线性滑移中点（C1 smoothstep e=0.5）", 5f, tMid.complexes[0].cx, 1e-4f)
        assertEquals("t=0.5 半径线性滑移中点", 150f, tMid.complexes[0].radius, 1e-4f)
        // ---- 源锚：滑移账+帧组装消费 ----
        val d = deepSpace()
        assertEquals("SLIDE_MS=2000L 单源恰一次（R303c 口径）", 1, Regex("const val SLIDE_MS = 2000L").findAll(d).count())
        val s = screen()
        val slide = cpx("private class DeepSpaceSlideR385", "@Volatile", s)
        assertTrue("账层换代检测（格键身份集 diff——引用换代身份集不变=零行为）", slide.contains("curKeys - prevKeys") && slide.contains("prevKeys - curKeys"))
        assertTrue("diff 探针行非静默（宣判体系纪律）", slide.contains("R385 复合体diff:"))
        assertTrue("SLIDE_MS 单源直引（零本地第二份窗长）", slide.contains("DeepSpaceNebula.SLIDE_MS"))
        assertTrue("t≥1 自动关窗（窗起点清零）", slide.contains("t01 >= 1f"))
        assertTrue("frameOfR385 null=恒等直通契约（窗无=早退 null）", slide.contains("val f = from ?: return null"))
        assertTrue(
            "帧组装消费：一次求值两字段共用直供 driftDeepSpaceR373（恒等直通零分配）",
            s.contains("val slidR385 = slideR385.frameOfR385(complexes, enrichClouds)") &&
                s.contains("slidR385?.complexes ?: complexes") &&
                s.contains("slidR385?.enrichClouds ?: enrichClouds"),
        )
        assertTrue(
            "CanvasLayer 接线在场（会话级账经参直供——Stage remember+调用点）",
            s.contains("val deepSpaceSlideR385 = remember { DeepSpaceSlideR385() }") &&
                s.contains("slideR385 = deepSpaceSlideR385"),
        )
    }
}
