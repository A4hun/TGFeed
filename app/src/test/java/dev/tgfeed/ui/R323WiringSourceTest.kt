package dev.tgfeed.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R323 自行+发射星云 wiring 源码锚（R325WiringSourceTest 同款 user.dir 源定位模式
 * ——UI 侧半场/渲染端无 JVM 直测面（Android File/Log/DeepSearchIndex 依赖），以
 * 源锚锁「漂移注记+发射星云+循环联动口进星图」的结构件；行为半体由
 * ProperMotionFxTest/EmissionNebulaFxTest（core 判据族）承重）：
 *  - **漂移半场结构**（runDriftVerdictR323）：S3 两窗构成点查消费+有界池+
 *    windowVotes/driftOf core 单口+runCatching 诚实降级；
 *  - **漂移注入与复用门**：星尾 drift 注记（copy(drift)）+缓存 v7 载荷（两窗三票
 *    原值 6 int+回读 driftOf 纯函数重导）+复用门 drift 冻结车道；
 *  - **发射星云数据面**：mapEmissionFogsR323（NEBULA_CANDIDATE 注记消费+
 *    nebulaCellMembers 成员表+isEmission/embryoCountOf core 单口）；
 *  - **帧面**（R345 Canvas 退役后 GL 单端）：TasteFlightFrame.emissionFogs+
 *    TasteFxStar.drift 契约锚+GL 消费锚（发射雾/星胚/电离侧辉/自行尾巴）；
 *  - **循环联动口接线**（R328 挂账兑现）：supernovaFlash 显式账（SUPERNOVA 档
 *    喂账）+CYCLE_PULSE_* 参数位消费（EmissionNebulaFx.cyclePulse01→
 *    TasteBirthFx 单源）；
 *  - **主方法零接线铁令**：主 composable 本体零 R323 接线标记。
 */
class R323WiringSourceTest {

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

    private fun tms(): String = src("dev/tgfeed/ui/TasteMapScreen.kt")
    private fun canvas(): String = src("dev/tgfeed/ui/TasteFlightRenderer.kt")
    private fun gl(): String = src("dev/tgfeed/ui/gl/GlFrameBuilder.kt")
    private fun flight(): String = src("dev/tgfeed/core/tastemap/TasteFlight.kt")
    private fun emissionFx(): String = src("dev/tgfeed/core/tastemap/EmissionNebulaFx.kt")

    // ==================== 漂移半场结构（runDriftVerdictR323） ====================

    @Test
    fun `漂移半场-点查消费与有界池与core单口`() {
        val t = tms()
        // S3 两窗构成点查（chatPublishMediaHistogram——漂移候选的原料）
        assertTrue(
            "漂移半场两窗构成点查丢失（S3 件消费）",
            Regex("vm\\.deepIndex\\.chatPublishMediaHistogram\\(s\\.chatId\\)").containsMatchIn(t),
        )
        // 有界池（DRIFT_POOL_CAP 帽+入口体量门——BIRTH_POOL 同款姿势）
        assertTrue(
            "漂移池帽消费丢失",
            Regex("ProperMotionFx\\.DRIFT_POOL_CAP").containsMatchIn(t) &&
                Regex("ProperMotionFx\\.DRIFT_POOL_MIN_ITEMS").containsMatchIn(t),
        )
        // core 单口（两窗归桶+漂移宣判——判据族零另写）
        assertTrue(
            "windowVotes/driftOf core 单口消费丢失",
            Regex("ProperMotionFx\\.windowVotes\\(entries, nowSec\\)").containsMatchIn(t) &&
                Regex("ProperMotionFx\\.driftOf\\(near, far\\)").containsMatchIn(t),
        )
        // 诚实降级：半场失败=漂移零注记零行为（runCatching 包裹）
        assertTrue(
            "漂移半场 runCatching 诚实降级丢失",
            Regex("runCatching \\{ runDriftVerdictR323\\(").containsMatchIn(t),
        )
        // 半场挂点=runLifeVerdictR325 第三半场（同旁路同取消路径）
        assertTrue(
            "漂移半场未挂 runLifeVerdictR325",
            Regex("runDriftVerdictR323\\(vm, fresh, nowSec\\)").containsMatchIn(t),
        )
    }

    @Test
    fun `漂移注入-星尾注记与缓存v7载荷与复用门冻结车道`() {
        val t = tms()
        // 注入：migrate 后纯注记 copy（kind/kindCounts 不动）
        assertTrue(
            "漂移注记注入丢失（copy(drift)）",
            Regex("driftById\\[s\\.chatId\\]\\?\\.let \\{ s\\.copy\\(drift = it\\) \\} \\?: s").containsMatchIn(t),
        )
        // 缓存载荷：写端两窗三票 6 int（val sd = s.drift 块）
        assertTrue(
            "缓存写端 drift 序列化丢失（v7 星尾载荷）",
            Regex("val sd = s\\.drift").containsMatchIn(t),
        )
        // 回读：driftOf 纯函数重导（公式单一出处——不存导出矢量）
        assertTrue(
            "缓存回读 driftOf 重导丢失",
            Regex("ProperMotionFx\\.driftOf\\(\\s*ProperMotionFx\\.Votes\\(nv, np, na\\),").containsMatchIn(t),
        )
        // 复用门 drift 冻结车道（birth 同款——两窗构成差慢变量缓存直读）
        assertTrue(
            "复用门 drift 冻结车道丢失",
            Regex("val driftCopiedFlag = c\\.drift != null && s\\.drift == null").containsMatchIn(t) &&
                Regex("birth = c\\.birth, drift = c\\.drift").containsMatchIn(t),
        )
        // StarNode 契约（尾字段默认 null=旧构造零变化）
        assertTrue(
            "StarNode.drift 尾字段丢失",
            Regex("val drift: ProperMotionFx\\.Drift\\? = null").containsMatchIn(t),
        )
    }

    // ==================== 发射星云数据面（mapEmissionFogsR323） ====================

    @Test
    fun `发射星云-判据面与成员表单口消费`() {
        val t = tms()
        // 判据面：NEBULA_CANDIDATE 注记消费（r325 F5 团级门组合星级单口）
        assertTrue(
            "金矿候选位 NEBULA_CANDIDATE 注记消费丢失",
            Regex("s\\.birth\\?\\.stage == TasteAstroPhysics\\.BirthStage\\.NEBULA_CANDIDATE").containsMatchIn(t),
        )
        // 成员表：nebulaCellMembers（nebulaCells 同内脏同序——零聚类逻辑复制）
        assertTrue(
            "雾团成员表 nebulaCellMembers 消费丢失",
            Regex("TasteNebula\\.nebulaCellMembers\\(starsIn\\)").containsMatchIn(t),
        )
        // 团级门+星胚粒数 core 单口
        assertTrue(
            "isEmission/embryoCountOf core 单口消费丢失",
            Regex("EmissionNebulaFx\\.isEmission\\(members\\.size, gold\\)").containsMatchIn(t) &&
                Regex("EmissionNebulaFx\\.embryoCountOf\\(gold\\)").containsMatchIn(t),
        )
        // 亮档色 core 单口（饱和度升——装配期烘焙）
        assertTrue(
            "亮档色 brightColorOf 单口消费丢失",
            Regex("EmissionNebulaFx\\.brightColorOf\\(nebulae\\[i\\]\\.colorArgb\\)").containsMatchIn(t),
        )
        // 平行表派生收口 TasteMapCanvasLayer（组件内 remember——主方法零新增行）
        assertTrue(
            "emissionFogs 派生未收口 TasteMapCanvasLayer",
            Regex("val emissionFogsR323 = remember\\(nebulae\\) \\{ mapEmissionFogsR323\\(ctx\\.mapData\\.value, nebulae\\) \\}").containsMatchIn(t),
        )
        // 帧字段透传（assembleFlightFrame 尾字段）
        assertTrue(
            "emissionFogs 帧字段透传丢失",
            Regex("emissionFogs = emissionFogsR323").containsMatchIn(t),
        )
    }

    // ==================== 帧面（R345 后 GL 单端——契约面仍锚 Canvas 契约文件） ====================

    @Test
    fun `帧面-发射雾与星胚与自行尾巴GL单端`() {
        val c = canvas()
        val g = gl()
        // 契约：TasteFlightFrame.emissionFogs + TasteFxStar.drift（默认值=旧构造零变化
        // ——R345 后 TasteFlightRenderer.kt 为帧契约单源文件，GL 唯一消费端）
        assertTrue(
            "TasteFlightFrame.emissionFogs 帧字段丢失",
            Regex("val emissionFogs: List<EmissionNebulaFx\\.Fog> = emptyList\\(\\)").containsMatchIn(c),
        )
        assertTrue(
            "TasteFxStar.drift 帧字段丢失",
            Regex("val drift: ProperMotionFx\\.Drift\\? = null").containsMatchIn(c),
        )
        // 发射雾消费：亮档 alpha（GL rgb 直供——R345 后 Canvas 位图桶/普通雾位图分支
        // 已随渲染器整类退役）
        assertTrue(
            "GL 发射档亮档消费丢失",
            Regex("EmissionNebulaFx\\.emissionAlphaOf\\(a\\) else a").containsMatchIn(g),
        )
        // 星胚：embryoOffset01/embryoAlpha01/embryoSizePx（core 三单口）
        for (anchor in listOf("embryoOffset01", "embryoAlpha01", "embryoSizePx")) {
            assertTrue(
                "GL 星胚单口 $anchor 消费丢失",
                Regex("EmissionNebulaFx\\.$anchor").containsMatchIn(g),
            )
        }
        // 电离：最近邻单遍扫+侧辉偏置（GL 单端——R345 前双端同式锚随 Canvas 退役收单）
        assertTrue(
            "GL 电离预扫 scanEmissionFogsR323 丢失",
            Regex("private fun scanEmissionFogsR323\\(f: TasteFlightFrame\\)").containsMatchIn(g),
        )
        assertTrue(
            "GL 电离侧辉偏置消费丢失",
            Regex("EmissionNebulaFx\\.ionizationSideGlowAlpha\\(\\w+, 1f\\)").containsMatchIn(g),
        )
        // 自行尾巴：焦点件（选中/聚焦星门+ProperMotionFx 参数单源）
        assertTrue(
            "GL 自行尾巴焦点件丢失",
            Regex("private fun buildProperMotionTailR323\\(").containsMatchIn(g),
        )
    }

    // ==================== 循环联动口接线（R328 挂账兑现——CYCLE_PULSE_* 消费） ====================

    @Test
    fun `联动口-supernova显式账与CYCLE_PULSE参数位消费`() {
        val t = tms()
        val f = flight()
        val e = emissionFx()
        // FxEngine 超新星显式账（TTL 域撞种别不可反推——SUPERNOVA 档独立喂账）
        assertTrue(
            "FxEngine supernovaFlashes 显式账丢失",
            Regex("val supernovaFlashes = HashMap<Long, Float>").containsMatchIn(f) &&
                Regex("fun supernovaFlash\\(chatId: Long, ttlSec: Float\\)").containsMatchIn(f),
        )
        // step 衰减同式（零额外时序源）
        assertTrue(
            "supernovaFlashes step 衰减丢失",
            Regex("val it = supernovaFlashes\\.entries\\.iterator\\(\\)").containsMatchIn(f),
        )
        // executeDirectiveR282 SUPERNOVA 档喂账（种别显式）
        assertTrue(
            "SUPERNOVA 档 supernovaFlash 喂账丢失",
            Regex("if \\(d\\.kind == UniverseDirector\\.EventKind\\.SUPERNOVA\\) \\{\\s*\\n\\s*ctx\\.engine\\.supernovaFlash\\(d\\.chatId, d\\.flashTtlSec\\)").containsMatchIn(t),
        )
        // CYCLE_PULSE_* 参数位消费兑现（EmissionNebulaFx.cyclePulse01→TasteBirthFx 单源；
        // R328 挂账「批 5 星胚点落件时消费 TasteBirthFx.CYCLE_PULSE_*」字面落地）
        assertTrue(
            "cyclePulse01 未消费 TasteBirthFx.cyclePulseAlpha（CYCLE_PULSE_MIN/SPAN 参数位）",
            Regex("TasteBirthFx\\.cyclePulseAlpha\\(t, seed\\) \\* TasteBirthFx\\.cyclePulseDecay01\\(x01\\)").containsMatchIn(e),
        )
        // 空间共在门：窗外恒零+active 门（门外零行为）
        assertTrue(
            "空间共在门 active 早退丢失",
            Regex("if \\(!active\\) return 0f").containsMatchIn(e),
        )
        // 渲染消费 fogPulse（脉冲增亮接线——R345 后 GL 单端）
        assertTrue(
            "GL fogPulse 消费丢失",
            Regex("alpha \\+= fogPulse\\[i\\]").containsMatchIn(gl()),
        )
        // 空间共在窗常量与 r327 循环行门同值单源（数值收口）
        assertTrue(
            "CYCLE_PULSE_WINDOW_D2 常量丢失（300²同值单源）",
            Regex("CYCLE_PULSE_WINDOW_D2 = 300f \\* 300f").containsMatchIn(e) &&
                Regex("BIRTH_CYCLE_NEIGHBOR_D2 = EmissionNebulaFx\\.CYCLE_PULSE_WINDOW_D2").containsMatchIn(t),
        )
    }

    // ==================== 科普穿透（whyChain 漂移行+WhyInput） ====================

    @Test
    fun `科普穿透-WhyInput漂移参与buildFlightScene传参`() {
        val t = tms()
        // StarCardScienceSection 构造面穿透（drift = st.drift——缺据行不出）
        assertTrue(
            "WhyInput drift 穿透丢失（构造面）",
            Regex("drift = st\\.drift").containsMatchIn(t),
        )
        // buildFlightScene 传参（TasteFxStar 尾参——渲染端焦点件原料）
        assertTrue(
            "buildFlightScene drift 传参丢失",
            Regex("it\\.drift,").containsMatchIn(t),
        )
    }

    // ==================== 主方法零接线铁令（R323 标记版） ====================

    /** brace 配对量主函数体（TasteMapMethodBudgetTest 同款口径） */
    private fun mainFunctionBody(file: File): String {
        val lines = file.readLines()
        val start = lines.indexOfFirst { Regex("^fun TasteMapScreen\\(").containsMatchIn(it) }
        require(start >= 0) { "主 composable TasteMapScreen 未找到" }
        var bodyStart = -1
        for (j in start until lines.size) {
            if (Regex("\\)\\s*\\{\\s*(//.*)?$").containsMatchIn(lines[j])) { bodyStart = j; break }
        }
        require(bodyStart >= 0) { "主函数体开括号未找到" }
        val sb = StringBuilder()
        var depth = 0
        for (j in bodyStart until lines.size) {
            val code = lines[j].split("//")[0]
            depth += code.count { it == '{' } - code.count { it == '}' }
            sb.appendLine(lines[j])
            if (depth == 0 && j > bodyStart) return sb.toString()
        }
        error("主函数体闭括号未配平")
    }

    @Test
    fun `主方法零接线-R323接线不进主composable本体`() {
        val body = mainFunctionBody(File(mainSourceRoot, "dev/tgfeed/ui/TasteMapScreen.kt"))
        // R323 接线全在顶层函数/组件（TasteMapCanvasLayer）/数据模型——主方法本体
        // 零 R323 标记（回填主方法=寄存器压力回顶——本锚即红）
        assertTrue(
            "主 composable 本体出现 R323 接线标记（主方法零接线铁令被破——迁组件/顶层函数！）",
            !Regex("runDriftVerdictR323|mapEmissionFogsR323|emissionFogsR323|driftById|EmissionNebulaFx|ProperMotionFx|drift = st\\.drift|supernovaFlash").containsMatchIn(body),
        )
    }
}
