package dev.tgfeed.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R328 批 C 演出层（两阶段）wiring 源码锚（R325WiringSourceTest 同款 user.dir 源
 * 定位模式——UI 侧演出件无 JVM 直测面（Compose/Android 依赖），以源锚锁「演出
 * 接线进对的面」的结构件；行为半体由 TasteLifeReplayTest/TasteTagPlanetsTest
 * （core 纯件）承重）：
 *  - **点燃拍 UI 半体**：AstroLitToast.ignition 尾字段（API 只加不改——默认
 *    false 老星零加）+生产位 youngIdsR327 同判别单口+AstroLitToastCard 白闪
 *    TasteBirthFx 参数单源消费（IGNITION_MS/IGNITION_FLASH_BOOST——时长/峰值
 *    零另写）+老星零视觉（ignition=false snapTo(0) 分支在场）；
 *  - **生灭回放数据面**：genesisStats 四元（GenesisLifeStats）+diedVisible/
 *    aliveVisible 单口消费+统计行「已熄」读法；
 *  - **坡道参重载委托**：TasteTagPlanets.appearAlpha 3 参委托 4 参（公式单一
 *    出处——API 只加不改的源锚面）；
 *  - **只消费不重定义**：TasteBirthFx/TasteAstroPhysics/AstroFacts（R326/R327
 *    收官件）零 R328 标记；
 *  - **第二阶段渲染消费锚**（原第一阶段冻结锚随批翻正——R320 套回后接线）：
 *    聚合拍焦点门 3f 字面量收口（ACCRETION_FOCUS_MIN_PX）+螺旋内落族/壳呼吸/
 *    进度亮度（probeProgress01 判据单口）双端同式；点燃拍（白闪+扩散环+年轻
 *    判别 youngBirthStarR328 单口）双端；凝缩档（planetsForming 门+坡道放缓+
 *    尘埃收缩+命中门同式+凌星预扫同门）双端；死星终态视觉（diedVisible/
 *    deathFlash01/暗核档+白矮星亮地板让位）双端；帧字段（TasteFxStar birth/
 *    lastActivitySec 尾字段+构造点传参）；
 *  - **主方法零接线铁令**：主 composable 本体零 R328 接线标记（director 句柄
 *    透传=既有薄调用面的参数位，逻辑全在顶层件）。
 */
class R328WiringSourceTest {

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
        assertTrue("源文件必须存在：$path", file.isFile)
        return file.readText()
    }

    private fun tms(): String = src("dev/tgfeed/ui/TasteMapScreen.kt")

    // ==================== 点燃拍 UI 半体（toast 白闪——TasteBirthFx 单源消费） ====================

    @Test
    fun `点燃拍-ignition数据位与youngIds单口生产`() {
        val t = tms()
        // 数据位：AstroLitToast 尾字段默认 false（API 只加不改——老星现状零加）
        assertTrue(
            "AstroLitToast ignition 尾字段（默认 false）丢失",
            Regex("val ignition: Boolean = false").containsMatchIn(t),
        )
        // 生产位：pickId in youngIdsR327（R327 通道①同判别单口——零第二判别式）
        assertTrue(
            "toast 点燃位生产丢失（youngIdsR327 同口）",
            Regex("pickId in youngIdsR327,").containsMatchIn(t),
        )
    }

    @Test
    fun `点燃拍-白闪TasteBirthFx单源消费与老星零视觉`() {
        val t = tms()
        // 时长/峰值=TasteBirthFx 单源（零另写数字——只消费不重定义）；**初值=峰值
        // 且老星初值恒 0**（§四 时序口径：t=0 峰值从登场帧即成立——免 LaunchedEffect
        // 首拍滞后；ignition=false 零视觉零重绘）
        assertTrue(
            "白闪峰值未走 TasteBirthFx.IGNITION_FLASH_BOOST 单源（初值双路）",
            Regex("Animatable\\(if \\(toast\\.ignition\\) TasteBirthFx\\.IGNITION_FLASH_BOOST else 0f\\)").containsMatchIn(t),
        )
        assertTrue(
            "白闪时长未走 TasteBirthFx.IGNITION_MS 单源",
            Regex("TasteBirthFx\\.IGNITION_MS\\.toInt\\(\\)").containsMatchIn(t),
        )
        // UI 幅常量在场（白洗/白边——回退位=闪值 0 时零绘制零行为残留）
        assertTrue(
            "白洗 UI 幅常量丢失",
            Regex("IGNITION_TOAST_WASH = 0\\.45f").containsMatchIn(t) &&
                Regex("IGNITION_TOAST_RIM = 0\\.55f").containsMatchIn(t),
        )
        // flashK=0 早退（窗外零绘制——静态件零帧循环铁律）
        assertTrue(
            "白洗 flashK>0 早退丢失",
            Regex("if \\(flashK > 0f\\) drawRect").containsMatchIn(t),
        )
    }

    // ==================== 生灭回放数据面（genesisStats 四元+单口消费） ====================

    @Test
    fun `生灭回放-数据面四元与TasteLifeReplay单口`() {
        val t = tms()
        // 四元（Triple 升四元——已熄计数并入）
        assertTrue(
            "GenesisLifeStats 四元丢失（已熄计数）",
            Regex("private data class GenesisLifeStats\\(val born: Int, val died: Int, val bornItems: Long, val tagKinds: Int\\)").containsMatchIn(t),
        )
        // diedVisible/aliveVisible 单口消费（数据轨单一出处——零另写死亡判别）
        assertTrue(
            "已熄计数未走 TasteLifeReplay.diedVisible 单口",
            Regex("TasteLifeReplay\\.diedVisible\\(s\\.lastActivitySec, genesisSpan, ctx\\.genesis01\\.value\\)").containsMatchIn(t),
        )
        assertTrue(
            "tag 行星生死门未走 TasteLifeReplay.aliveVisible 单口",
            Regex("TasteLifeReplay\\.aliveVisible\\(fs, lastActById\\[chatId\\] \\?: 0L, genesisSpan, ctx\\.genesis01\\.value\\)").containsMatchIn(t),
        )
        // 统计行「已熄」读法（r325 批D 回放统计行升级）
        assertTrue(
            "统计行「已熄 M 颗」读法丢失",
            Regex("已熄 \\\$diedCh 颗").containsMatchIn(t),
        )
        // import 在场（消费面成立的前置）
        assertTrue(
            "TasteLifeReplay import 丢失",
            Regex("import dev\\.tgfeed\\.core\\.tastemap\\.TasteLifeReplay").containsMatchIn(t),
        )
    }

    // ==================== 坡道参重载委托（TasteTagPlanets——公式单一出处） ====================

    @Test
    fun `坡道重载-三参委托四参单一公式出处`() {
        val p = src("dev/tgfeed/core/tastemap/TasteTagPlanets.kt")
        // 3 参旧签名委托 4 参（ramp=APPEAR_RAMP）——公式只写一处（委托行单独
        // 锚：该实参序列只出现在委托处）
        assertTrue(
            "appearAlpha 3 参委托 4 参丢失（公式单一出处）",
            Regex("appearAlpha\\(ratio, index, total, APPEAR_RAMP\\)").containsMatchIn(p),
        )
        // 4 参重载签名在场（批 C 参数面——渲染端成型档调制位）
        assertTrue(
            "appearAlpha 4 参重载丢失",
            Regex("fun appearAlpha\\(ratio: Float, index: Int, total: Int, ramp: Float\\): Float").containsMatchIn(p),
        )
    }

    // ==================== 只消费不重定义（R326/R327 收官件零 R328 标记） ====================

    @Test
    fun `单源零改-TasteBirthFx与判据族收官件零碰`() {
        // TasteBirthFx=R326 形状参数单源（本批只消费）；AstroFacts/TasteAstroPhysics
        // =判据族收官（R326/R327）——三件零 R328 标记（回填=越界即红）
        for (path in listOf(
            "dev/tgfeed/core/tastemap/TasteBirthFx.kt",
            "dev/tgfeed/core/tastemap/TasteAstroPhysics.kt",
            "dev/tgfeed/core/tastemap/AstroFacts.kt",
        )) {
            val text = src(path)
            assertTrue(
                "$path 出现 R328 标记（只消费不重定义/判据族收官零碰被破）",
                !Regex("R328").containsMatchIn(text),
            )
        }
    }

    // ==================== 第二阶段渲染消费锚（原冻结锚随批翻正——R320 套回后接线） ====================

    @Test
    fun `渲染消费-聚合拍焦点门与3f字面量收口`() {
        val g = src("dev/tgfeed/ui/gl/GlFrameBuilder.kt")
        // 3f 字面量收口（R327 挂账点名）：GL 端正则锚 ACCRETION_FOCUS_MIN_PX 常量
        // （R345 Canvas 退役后 GL 单端——Canvas 侧锚随渲染器整类退役）
        assertTrue(
            "渲染端 3f 焦点门字面量未收口 ACCRETION_FOCUS_MIN_PX（GL 缺失）",
            Regex("r >= TasteBirthFx\\.ACCRETION_FOCUS_MIN_PX").containsMatchIn(g),
        )
        // 螺旋内落族（infall/spiral/包络）+壳呼吸+进度亮度（coreK）——GL 单端
        for (anchor in listOf(
            "TasteBirthFx\\.accretionInfallRadius01\\(t, seed, k\\)",
            "TasteBirthFx\\.accretionSpiralAngleRad\\(t, seed, k\\)",
            "TasteBirthFx\\.accretionParticleAlpha01\\(rad01\\)",
            "TasteBirthFx\\.accretionShellBreath\\(t, seed\\)",
            "TasteBirthFx\\.accretionCoreK\\(",
        )) {
            assertTrue(
                "GL 聚合拍单源消费锚丢失：$anchor",
                Regex(anchor).containsMatchIn(g),
            )
        }
        // 进度亮度判据单口：probeProgress01 直供（与 coreK 组合）
        assertTrue(
            "核心进度亮度未走 probeProgress01 判据单口",
            Regex("TasteAstroPhysics\\.probeProgress01\\(birthNote\\.probeItems\\)").containsMatchIn(g),
        )
    }

    @Test
    fun `渲染消费-点燃拍与年轻判别单口`() {
        val g = src("dev/tgfeed/ui/gl/GlFrameBuilder.kt")
        // 核心白闪+扩散环（REVEAL_FX 窗内——ignitionFlash01/ignitionRingR01/
        // ignitionRingAlpha01 单源）——GL 单端（R345 Canvas 退役）
        for (anchor in listOf(
            "TasteBirthFx\\.ignitionFlash01\\(ageSec\\)",
            "TasteBirthFx\\.ignitionRingR01\\(ageSec\\)",
            "TasteBirthFx\\.ignitionRingAlpha01\\(ageSec\\)",
        )) {
            assertTrue(
                "GL 点燃拍单源消费锚丢失：$anchor",
                Regex(anchor).containsMatchIn(g),
            )
        }
        // 年轻判别单口（youngBirthStarR328——lit toast 与渲染门共用一处）
        assertTrue(
            "GL 点燃判别未走 youngBirthStarR328 单口",
            Regex("youngBirthStarR328\\(s\\.kind, s\\.birth\\)").containsMatchIn(g),
        )
        // 判别单口本体：kind ∨ 注记（与 youngIdsR327 生产同式）
        val t = tms()
        assertTrue(
            "youngBirthStarR328 单口本体丢失",
            Regex("internal fun youngBirthStarR328\\(").containsMatchIn(t),
        )
    }

    @Test
    fun `渲染消费-凝缩档与生灭回放死星视觉`() {
        val g = src("dev/tgfeed/ui/gl/GlFrameBuilder.kt")
        val t = tms()
        // 凝缩档：成型门（planetsForming）+坡道放缓+尘埃收缩——GL 单端（R345 Canvas 退役）
        for (anchor in listOf(
            "s\\.birth\\?\\.planetsForming == true",
            "TasteBirthFx\\.formingRampWidth\\(TasteTagPlanets\\.APPEAR_RAMP\\)",
            "TasteBirthFx\\.formingDustRadiusCoef\\(seed, idx\\)",
        )) {
            assertTrue(
                "GL 凝缩档消费锚丢失：$anchor",
                Regex(anchor).containsMatchIn(g),
            )
        }
        // 命中门同式（点=所见——TasteMapScreen tapPlanet 走同一坡道）
        assertTrue(
            "tapPlanet 成型档命中门同式丢失（点=所见）",
            Regex("TasteBirthFx\\.formingRampWidth\\(TasteTagPlanets\\.APPEAR_RAMP\\)").containsMatchIn(t),
        )
        // 凌星预扫同门（R320 scanTransitDepthR320 与绘制同门同闸——GL 侧
        // formingRampWidth ≥2 处：buildPlanetSystem+scanTransitDepthR320 各一）
        val glRampGates = Regex("TasteBirthFx\\.formingRampWidth\\(TasteTagPlanets\\.APPEAR_RAMP\\)").findAll(g).count()
        assertTrue(
            "GL 凌星预扫成型档同门丢失（与 buildPlanetSystem 同门同闸——formingRampWidth 应≥2 处，实得 $glRampGates）",
            glRampGates >= 2,
        )
        // 死星终态视觉（TasteLifeReplay 单口）：diedVisible+deathFlash01+暗核档——GL 单端
        for (anchor in listOf(
            "TasteLifeReplay\\.diedVisible\\(s\\.lastActivitySec, f\\.genesisSpan, f\\.genesisT01\\)",
            "TasteLifeReplay\\.deathFlash01\\(s\\.lastActivitySec, f\\.genesisSpan, f\\.genesisT01\\)",
            "TasteLifeReplay\\.REMNANT_CORE_K",
        )) {
            assertTrue(
                "GL 死星终态视觉单口锚丢失：$anchor",
                Regex(anchor).containsMatchIn(g),
            )
        }
        // 白矮星亮地板让位（remnantDead 条件——「死而不灭」只属正常态）
        assertTrue(
            "白矮星暗核化让位条件丢失（remnantDead）",
            Regex("if \\(remnantDead\\) alpha else max\\(alpha, 0\\.92f\\)").containsMatchIn(g),
        )
    }

    @Test
    fun `帧字段-演出原料双端同帧`() {
        val c = src("dev/tgfeed/ui/TasteFlightRenderer.kt")
        val t = tms()
        // TasteFxStar 尾字段（帧契约只加不改——birth/lastActivitySec）
        assertTrue(
            "TasteFxStar birth 尾字段丢失",
            Regex("val birth: TasteAstroPhysics\\.BirthFeatures\\? = null").containsMatchIn(c),
        )
        assertTrue(
            "TasteFxStar lastActivitySec 尾字段丢失",
            Regex("val lastActivitySec: Long = 0L").containsMatchIn(c),
        )
        // 构造点传参（TasteMapScreen 帧组装——原料进帧；行尾 CRLF/LF 无关锚）
        assertTrue(
            "帧构造点 birth/lastActivitySec 传参丢失",
            Regex("it\\.birth,\\r?\\n\\s*it\\.lastActivitySec,").containsMatchIn(t),
        )
    }

    // ==================== 主方法零接线铁令（R328 演出标记版） ====================

    /** brace 配对量主函数体（R325WiringSourceTest 同款口径） */
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
    fun `主方法零接线-R328演出接线不进主composable本体`() {
        val body = mainFunctionBody(File(mainSourceRoot, "dev/tgfeed/ui/TasteMapScreen.kt"))
        // 演出接线全在独立组件/顶层函数/core 件（AstroLitToastCard/
        // TasteMapStatsLegend/pollGalaxyBeatsR254/TasteLifeReplay）——主方法本体
        // 零 R328 标记（回填主方法=寄存器压力回顶——本锚即红）
        assertTrue(
            "主 composable 本体出现 R328 接线标记（主方法零接线铁令被破——迁独立组件！）",
            !Regex("ignitionFlash|IGNITION_TOAST|TasteLifeReplay|GenesisLifeStats|diedVisible|aliveVisible|pickId in youngIdsR327").containsMatchIn(body),
        )
    }
}
