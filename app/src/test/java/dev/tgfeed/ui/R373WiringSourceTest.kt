package dev.tgfeed.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R373 深空星云复合体 P2 wiring 源码锚（R367 同款 user.dir 源定位模式——GL 渲染
 * 管线无 JVM 直测面，以源锚锁结构面；design-nebula §3.2/§4.1/§4.3/§5.1/§十一）：
 *  - 双源并置锚：同一低通管线两原料（摇篮场+富集场各一次 lowpassField 消费
 *    ——类收敛元规则一的字面兑现）+位置真源 agnostic（世界质心只吃成员 sp）；
 *  - 预算锚：复合体 ≤4+富集 ≤4 core 帽与 GL 独立预算行 MAX_NEBULA_CPX=16
 *    （≤4×3 视差层+富集 ≤4 单层=16 顶格；与 MAX_NEBULAS=24 合并审计总帽 40
 *    ——nebBuf 容量锚）；
 *  - 物理色板锚：Hα/OIII 发射线色板 core 单源+NEBULA_FS 零接触（R358 uniform
 *    四件锚不破——本批对 GlShaders.kt 零引用）+零 hue(t)（色无时间参）；
 *  - 诚实缺省锚：演示预标定样本仅 demoCalibration 分支可触达（真实库永不掺）；
 *  - 装配接线锚：星图装配期 remember(mapData) 密度场派生+探针行（TasteMapX
 *    tag 脱敏纯计数）+帧字段直通+R282 同款亚感知摇曳（场冻结+drift）；
 *  - 画位锚：画家序 2.5 层雾团前（buildComplexesR373 先于 buildNebulas）。
 */
class R373WiringSourceTest {

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

    private fun core(): String = src("dev/tgfeed/core/tastemap/DeepSpaceNebula.kt")

    private fun builder(): String = src("dev/tgfeed/ui/gl/GlFrameBuilder.kt")

    private fun mapScreen(): String = src("dev/tgfeed/ui/TasteMapScreen.kt")

    private fun frameRenderer(): String = src("dev/tgfeed/ui/TasteFlightRenderer.kt")

    private fun glShaders(): String = src("dev/tgfeed/ui/gl/GlShaders.kt")

    /** 双源并置：同一低通管线（lowpassField）两原料（cradleMass/enrichMass）各消费一次 */
    private fun complexesOfBody(text: String): String {
        val start = text.indexOf("fun complexesOf(")
        assertTrue("complexesOf 必须在场", start >= 0)
        val rest = text.substring(start)
        val next = rest.indexOf("\n    fun ", 10)
        return if (next > 0) rest.substring(0, next) else rest
    }

    /**
     * ① 双源并置锚：摇篮场与富集场同走 [lowpassField]（同一低通管线两原料——
     * 类收敛）；摇篮原料=未探索质量+候选加权（cradleMassOf 单口）。
     */
    @Test
    fun 双源并置_同一低通管线两原料() {
        val body = complexesOfBody(core())
        val calls = Regex("lowpassField\\(").findAll(body).count()
        assertEquals("complexesOf 内 lowpassField 须恰好两次消费（摇篮场+富集场——同一管线两原料）", 2, calls)
        assertTrue("摇篮场原料=cradleMass（未探索质量+候选加权）", body.contains("lowpassField(cradleMass"))
        assertTrue("富集场原料=enrichMass（全星表——含已探索星，差异位即语义）", body.contains("lowpassField(enrichMass"))
        assertTrue("摇篮质量单口在场（未探索 1/候选 3/已探索 0）", core().contains("private fun cradleMassOf"))
        // 位置真源 agnostic：世界质心只吃成员 sp（buildComplex sp 加权——非口味位直映射）
        val bc = core().substring(core().indexOf("private fun buildComplex"))
        assertTrue("世界质心=成员 sp 质量加权（位置真源 agnostic）", bc.substring(0, bc.indexOf("private fun buildEnrichCloud")).contains("m * s.spX"))
    }

    /**
     * ② 预算锚：core ≤4 团/≤4 云帽+GL 独立预算行 16（≤4×3 视差层+富集 4 单层
     * 顶格）+与 MAX_NEBULAS=24 合并审计总帽 40（nebBuf 容量）+独立计数互不挤压。
     */
    @Test
    fun 预算_独立预算行16与合并审计总帽40() {
        val c = core()
        assertEquals("摇篮复合体帽=4（§3.2 帧预算）", "4", Regex("const val MAX_COMPLEXES = (\\d+)").find(c)?.groupValues?.get(1))
        assertEquals("富集云帽=4（§十一 双源并置对称帧预算行）", "4", Regex("const val MAX_ENRICH_CLOUDS = (\\d+)").find(c)?.groupValues?.get(1))
        val b = builder()
        assertEquals("复合体独立预算行帽=16（§5.1「≤4 团×层×blob ≤16 quads 独立预算行」）", "16", Regex("const val MAX_NEBULA_CPX = (\\d+)").find(b)?.groupValues?.get(1))
        assertEquals("雾行帽 MAX_NEBULAS=24 原样（P1 结论零碰）", "24", Regex("const val MAX_NEBULAS = (\\d+)").find(b)?.groupValues?.get(1))
        assertTrue(
            "nebBuf 容量=(24+16)×NEB_STRIDE 合并审计总帽 40（两行分段计数=画家序与预算序解耦）",
            b.contains("val nebBuf = FloatArray((MAX_NEBULAS + MAX_NEBULA_CPX) * NEB_STRIDE)"),
        )
        assertTrue("复合体独立计数 nebCpxCount（帽独立不挤压雾行）", b.contains("if (nebCpxCount >= MAX_NEBULA_CPX) return"))
        assertTrue("复合体写 nebBuf 后段（opA=MAX_NEBULAS+序号）", b.contains("val i = (MAX_NEBULAS + nebCpxCount) * NEB_STRIDE"))
        assertTrue("帧首复合体行计数复位", b.contains("nebCpxCount = 0"))
    }

    /**
     * ③ 物理色板锚：Hα/OIII 发射线色板 core 单源+亮度预算表常量（0.12/0.08
     * 落位）+量级压缩注释自认+零 hue(t)（结构面：色函数无时间参+色板常量互异）。
     */
    @Test
    fun 物理色板_发射线单源与预算表常量自认() {
        val c = core()
        assertTrue("Hα 玫红色板单源在场", c.contains("val HA_ARGB: Int = TasteStarColor.hsvToArgb(338f"))
        assertTrue("OIII 青色板单源在场", c.contains("val OIII_ARGB: Int = TasteStarColor.hsvToArgb(172f"))
        assertEquals("复合体远景云气帽=0.12（§4.3 克制档终稿）", 0.12f, Regex("const val CPX_ALPHA_MAX = ([\\d.]+)f").find(c)?.groupValues?.get(1)?.toFloat())
        assertEquals("富集反射云帽=0.08（§十一 终审增补——反射暗于发射=物理诚实）", 0.08f, Regex("const val ENRICH_ALPHA_MAX = ([\\d.]+)f").find(c)?.groupValues?.get(1)?.toFloat())
        // 量级压缩注释自认（诚实量级——真实星云肉眼不可见）
        assertTrue("量级压缩自认在案（宪法一章三条）", c.contains("肉眼不可见"))
        assertTrue("全表自认在案（R358 0.13+联动约 0.21+0.12+0.08）", c.contains("0.13") && c.contains("0.21") && c.contains("0.12") && c.contains("0.08"))
        // fBm 权混场：FogFlocFx.fbm01 单源驱动逐层混权
        assertTrue("fBm 权混场（FogFlocFx.fbm01 单源）", c.contains("FogFlocFx.fbm01(cellKey + 911"))
        // 电离侧亮暗=嵌入候选活跃度（R323 判据件单口）
        assertTrue("电离侧亮暗由嵌入候选活跃度驱动（isIonizingSource R323 单口）", c.contains("EmissionNebulaFx.isIonizingSource(s.recent7, s.lastActivityDays)"))
        // 零 hue(t)：色函数签名无时间参+函数体内零 hue/hsv（结构面负锚——限定
        // 函数体防 KDoc 判词行误伤）
        val layerColor = Regex("fun emissionLayerColor\\(([^)]*)\\)").find(c)?.groupValues?.get(1) ?: ""
        assertFalse("层色函数零时间参（零 hue(t) 禁令 12 结构锚）", layerColor.contains("t:"))
        val layerColorIdx = c.indexOf("fun emissionLayerColor(")
        val churnIdx = c.indexOf("fun churnSeedOf(")
        assertTrue("层色函数体可切片", layerColorIdx in 0 until churnIdx)
        val layerBody = c.substring(layerColorIdx, churnIdx)
        assertFalse("层色函数体零 hue/hsv 调用（色板常量单源不循环）", layerBody.contains("hue") || layerBody.contains("hsv"))
    }

    /**
     * ④ P1 零碰负锚：NEBULA_FS 文本零接触（R358 uniform 四件锚不破——本批对
     * GlShaders.kt 零引用零改动）+雾行语义件零改。
     */
    @Test
    fun P1零碰_GlShaders零接触雾行原样() {
        val gs = glShaders()
        assertFalse("GlShaders.kt 零 DeepSpaceNebula 引用（复合体走同程序 uniform 变体=零新程序零 shader 改写）", gs.contains("DeepSpaceNebula"))
        assertFalse("GlShaders.kt 零 R373 字样（NEBULA_FS 文本零接触）", gs.contains("R373"))
        // R358 uniform 四件锚镜像（R388 翻新保义：四件→五件=刀2 uFreqScale 频率
        // 标度维护性扩展——「丝缕基因的尺度变换非基因改变」，scale=1 恒等既有
        // 基因；判词保义 084499b 先例；R392 翻新：五件→六件=判词① uNoiseVol
        // sampler 载体件——烘焙 3D 噪声体，五浮点基因零增益）
        val m = Regex("const val NEBULA_FS = \"\"\"([\\s\\S]*?)\"\"\"").find(gs)
        assertTrue("NEBULA_FS 块在场", m != null)
        assertEquals("NEBULA_FS uniform 六件（R392 判词① uNoiseVol sampler 第六件——R358/R388 锚保义翻新）", 6, Regex("uniform\\s").findAll(m!!.groupValues[1]).count())
        val b = builder()
        // 雾族消费零改负锚：buildNebulas 仍走 addNebula（MAX_NEBULAS 行）+发射链原样
        assertTrue("雾族装填口 addNebula 原样在场", Regex("private fun addNebula\\(").containsMatchIn(b))
    }

    /**
     * ⑤ 诚实缺省锚：演示预标定样本仅 demoCalibration 分支可触达（when 分支字面
     * 锚——真实库调用面 false 永不掺）。
     */
    @Test
    fun 诚实缺省_演示样本仅演示分支可触达() {
        val body = complexesOfBody(core())
        val whenStart = body.indexOf("val src = when {")
        assertTrue("demo 分支在场", whenStart > 0)
        val branch = body.substring(whenStart, body.indexOf("}", whenStart))
        assertTrue("演示样本只在 demoCalibration 分支并入（真实库永不掺）", branch.contains("stars.isEmpty() -> DEMO_SAMPLES"))
        assertTrue("真实库分支=demoCalibration=false 直通星表", branch.contains("!demoCalibration -> stars"))
        // 装配侧 demo 判据=gateway 类型（MockTdGateway 演示宇宙）
        val ms = mapScreen()
        assertTrue("装配侧 demo 判据 gateway 非 TdLibGateway（演示宇宙口径）", ms.contains("val demoModeR373 = gateway !is TdLibGateway"))
    }

    /**
     * ⑥ 装配接线锚：星图装配期 remember(mapData) 密度场派生（数据换代重算）+
     * 探针行（TasteMapX tag 脱敏纯计数：摇篮数/质量/富集数/两帽）+帧字段直通
     * （TasteFlightFrame complexes/enrichClouds）+R282 同款亚感知摇曳镜像。
     */
    @Test
    fun 装配接线_装配期密度场与探针与帧字段直通() {
        val ms = mapScreen()
        // 装配期派生（remember(mapData) 键控——数据换代重算）
        assertTrue(
            "装配期密度场派生（remember(mapData, demoModeR373)——数据换代自动重算）",
            ms.contains("val deepSpaceR373 = remember(mapData, demoModeR373) { mapDeepSpaceR373(mapData, demoModeR373) }"),
        )
        // 探针行（任务书口径：摇篮=N 质量=… 富集=M 帽=…——TasteMapX tag）
        assertTrue("探针行在场（摇篮/质量/富集/帽——脱敏纯计数）", ms.contains("R373 复合体: 摇篮=\${out.complexes.size} 质量="))
        assertTrue(
            "探针 TasteMapX tag（\\r?\\n 环境稳健——R328 惯例：autocrlf 检出 CRLF 勿碎锚）",
            Regex("\"TasteMapX\",\\r?\\n\\s*\"R373 复合体").containsMatchIn(ms),
        )
        // 派生体顶层函数（主方法预算纪律——计算体迁出）
        assertTrue("mapDeepSpaceR373 顶层函数在场", Regex("private fun mapDeepSpaceR373\\(").containsMatchIn(ms))
        // 摇曳=既有 drift 亚感知（镜像 driftNebulaeR282——场冻结+drift 摇曳）
        assertTrue("R373 漂移镜像函数在场（R282 同款量化缓存）", Regex("private fun driftDeepSpaceR373\\(").containsMatchIn(ms))
        // R385c 翻新：滑移层前置后调用形态多行化——语义原判词保义（一次求值两
        // 字段共用；恒等直通位=装配原值 complexes/enrichClouds 原名在调用体）
        assertTrue(
            "帧组装走漂移（assembleFlightFrame 内一次求值两字段共用——R385 滑移恒等直通位前置）",
            Regex("val driftedR373 = driftDeepSpaceR373\\(\\s*slidR385\\?\\.complexes \\?: complexes,").containsMatchIn(ms) &&
                Regex("slidR385\\?\\.enrichClouds \\?: enrichClouds,").containsMatchIn(ms) &&
                Regex("universeDirector,\\s*\\)").containsMatchIn(ms),
        )
        // 帧字段直通
        val fr = frameRenderer()
        assertTrue("帧字段 complexes（默认空=旧构造点零变化）", fr.contains("val complexes: List<DeepSpaceNebula.Complex> = emptyList()"))
        assertTrue("帧字段 enrichClouds（默认空）", fr.contains("val enrichClouds: List<DeepSpaceNebula.EnrichCloud> = emptyList()"))
        // CanvasLayer 参数直供
        assertTrue("画布层参数直供 complexes", ms.contains("complexes = deepSpaceR373.complexes"))
        assertTrue("画布层参数直供 enrichClouds", ms.contains("enrichClouds = deepSpaceR373.enrichClouds"))
    }

    /**
     * ⑦ 画位锚：画家序 2.5 层雾团前（buildComplexesR373 先于 buildNebulas 落
     * op——远风景画在近距墨水之下）+雾族管线家族扩展（FogFloc 絮化单源沿用+
     * 层深 ±0.5R）+近距渐出窗（P3 crossfade 远景半边单边）+远档层数裁剪。
     */
    @Test
    fun 画位_画家序雾团前与家族扩展与近距渐出() {
        val b = builder()
        val cpxCall = b.indexOf("buildComplexesR373(f, cam, focal, w, h, cx, cy)")
        val nebCall = b.indexOf("buildNebulas(f, cam, focal, w, h, cx, cy)")
        assertTrue("复合体绘制调用在场", cpxCall > 0)
        assertTrue("雾团绘制调用在场", nebCall > 0)
        assertTrue("画家序：复合体 op 先于雾团（2.5 层雾团前——远风景在近墨水之下）", cpxCall < nebCall)
        // FogFloc 絮化单源沿用+层深 ±0.5R
        val cpxBody = b.substring(b.indexOf("private fun buildComplexesR373"), b.indexOf("private fun buildNebulas"))
        assertTrue("FogFloc 絮化单源沿用（blob 族）", cpxBody.contains("FogFlocFx.blobAlphaPack01"))
        assertTrue("层深 ±0.5R（R281 ±0.35R 的大尺度外推）", cpxBody.contains("(if (k == 0) 0.5f else -0.5f) * c.radius"))
        assertTrue("种子=质心格键哈希（churnSeedOf 消费）", cpxBody.contains("DeepSpaceNebula.churnSeedOf"))
        // 近距渐出窗（R252 负锚防线——P3 crossfade 远景半边）
        assertTrue("近距渐出窗在场（nearFade01）", cpxBody.contains("DeepSpaceNebula.nearFade01"))
        // 远档层数裁剪（fbm3 octave 恒定锚所迫的层数裁剪口径；R386 阈 dp 化翻新：
        // CPX_FAR_MIN_DP 单源+帧字段 cpxFarMinPx 消费——裸 px 名 CPX_FAR_MIN_PX 退役）
        assertTrue("远档裁剪在场（R386 dp 阈帧字段 cpxFarMinPx 消费）", cpxBody.contains("cpxFarMinPx") && cpxBody.contains("blobCount = 1"))
        assertTrue("远档裸 px 阈名退役（密度岔路病根拔除）", !b.contains("CPX_FAR_MIN_PX"))
        // 独立预算行装填口（addNebulaCpx）
        assertTrue("复合体行装填口 addNebulaCpx", cpxBody.contains("addNebulaCpx("))
        // 富集微光云平档 alpha（enrichAlpha01 单源）
        assertTrue("富集 alpha=帽平档（enrichAlpha01 单源消费）", cpxBody.contains("DeepSpaceNebula.enrichAlpha01()"))
    }
}
