package dev.tgfeed.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R317b 宣判接线源码锚（TasteMapMethodBudgetTest 同款 user.dir 源定位模式——
 * UI 侧状态机无 JVM 直测面（Android File/Log 依赖），以源锚锁「异步宣判状态机
 * （IO 协程完成→kinds 原子换装→缓存拒旧）」的结构件；行为半体由
 * TasteTerminalJudgmentTest/TasteTerminalFxTest（core 纯件）承重）：
 *  - **payload v5 换代**（R327 随诞生注记 4→5）：`TASTE_MAP_CACHE_VERSION = 5`
 *    字面量（旧 v4 缓存直拒重算=诞生 diff 检出窗——R317b v3→v4 同轴沿革）+
 *    hist/birth/verdictApplied 读写端同位；
 *  - **状态机四件**（R327 合流迁移——断言随行为更新，AstroFactsTest quasar
 *    断言同例）：宣判合流管线函数（runLifeVerdictR325——R317b 终态半场语句
 *    原样收编+独立方法铁令）、诞生半场（runBirthVerdictR325）、合流迁移件
 *    （migrateLifeVerdictsR325）、复用件（applyCachedVerdictR317b——函数名
 *    保留=复用门扩展锚）、runGalaxyLoadR254 尾段的调用与原子换装
 *    （setMap(verdictMap)）；
 *  - **主方法零接线铁令**（R315 工程约束）：主 composable 函数体内零宣判标记
 *    ——宣判接线全在顶层函数（回填主方法=本锚即红）。
 */
class R317bWiringSourceTest {

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

    private fun tms(): String {
        val file = File(mainSourceRoot, "dev/tgfeed/ui/TasteMapScreen.kt")
        assertTrue("TasteMapScreen.kt 必须存在：${file.path}", file.isFile)
        return file.readText()
    }

    // ==================== payload 换代（旧缓存直拒=宣判/诞生/双星对扩展 diff 检出） ====================

    @Test
    fun `缓存换代-payload版本为7（v6直拒）`() {
        // R323 锚随行为翻正：v6（R321 双星对附加账）→v7（R323 自行漂移注记
        // 星尾 drift——旧 v6 直拒重算=漂移装载窗；换代=设计动作非回归）
        assertTrue(
            "TASTE_MAP_CACHE_VERSION 必须=7（R323 自行漂移注记换代——v6 旧缓存直拒重算）",
            Regex("TASTE_MAP_CACHE_VERSION\\s*=\\s*7\\b").containsMatchIn(tms()),
        )
        // gen7 轴零触碰（R303c 地盘共存不动——别撞轴）
        assertTrue(
            "LAYOUT_ALGO_GEN 必须=7（R317b/R321/R323 只走 payload 轴，gen7 归 R303c）",
            Regex("LAYOUT_ALGO_GEN\\s*=\\s*7\\b").containsMatchIn(tms()),
        )
    }

    @Test
    fun `缓存载荷-hist与birth与verdictApplied读写端同位`() {
        val t = tms()
        // 写端：星尾 hist（PublishFeatures 十字段）+ 星尾 birth（含内嵌诞生史 hist）
        // + 尾 verdictApplied
        assertTrue("写端 hist 序列化丢失（PublishFeatures 字段）", Regex("val sh = s\\.hist").containsMatchIn(t))
        assertTrue("写端 birth 序列化丢失（R327 星尾注记）", Regex("val sb = s\\.birth").containsMatchIn(t))
        assertTrue(
            "写端 verdictApplied 尾字段丢失",
            Regex("out\\.writeBoolean\\(d\\.verdictApplied\\)").containsMatchIn(t),
        )
        // 读端：hist 回读（PublishFeatures 构造）+ birth 回读 + 尾 verdictApplied
        assertTrue(
            "读端 hist 回读丢失（PublishFeatures 构造）",
            Regex("hist = if \\(ins\\.readBoolean\\(\\)\\) \\{").containsMatchIn(t),
        )
        assertTrue(
            "读端 birth 回读丢失（BirthFeatures 构造——R327）",
            Regex("birth = if \\(ins\\.readBoolean\\(\\)\\) \\{").containsMatchIn(t),
        )
        assertTrue(
            "读端 verdictApplied 回读丢失",
            Regex("val verdictFlag = ins\\.readBoolean\\(\\)").containsMatchIn(t),
        )
        // 回读深等自检覆盖宣判档（round-trip 精确性闸；birth 在 StarNode data class
        // 内=星表深等 stars== 自动覆盖）
        assertTrue(
            "回读自检未覆盖 verdictApplied（深等闸缺口）",
            Regex("back\\.verdictApplied == fresh\\.verdictApplied").containsMatchIn(t),
        )
    }

    // ==================== 异步宣判状态机（结构四件——R327 合流迁移形态） ====================

    @Test
    fun `状态机-宣判合流管线独立函数与原子换装在场`() {
        val t = tms()
        // 管线独立方法铁令（装配走独立方法——R315 工程约束；R327 合流：终态半场
        // 语句原样收编进 runLifeVerdictR325——单函数不复数状态机）
        for (fn in listOf("runLifeVerdictR325", "runBirthVerdictR325", "migrateLifeVerdictsR325", "applyCachedVerdictR317b")) {
            assertTrue(
                "宣判管线顶层函数 $fn 丢失（独立方法铁令被破？）",
                Regex("private (suspend )?fun $fn\\(").containsMatchIn(t),
            )
        }
        // 旧终态管线函数已收编退役（残留=两处状态机——r325 B①-3 互斥单点违例）
        for (gone in listOf("runTerminalVerdictR317b", "migrateTerminalVerdictsR317b")) {
            assertTrue(
                "终态半场旧函数 $gone 未收编（必须语句原样搬进 runLifeVerdictR325/migrateLifeVerdictsR325——双状态机违例）",
                !Regex("private (suspend )?fun $gone\\(").containsMatchIn(t),
            )
        }
        // runGalaxyLoadR254 尾段旁路调用（方案①异步后置——装配+布局+缓存写盘之后；
        // 合流旁路一行调用）
        assertTrue(
            "runGalaxyLoadR254 宣判合流旁路段丢失（方案①接线被拆？）",
            Regex("runLifeVerdictR325\\(gateway, vm, fresh, assembly\\)").containsMatchIn(t),
        )
        // 原子换装：深等比对有变化才 setMap（kinds 原子换装=宣判演出唯一时刻）
        assertTrue(
            "原子换装比对丢失（setMap(verdictMap) 深等门）",
            Regex("setMap\\(verdictMap\\)").containsMatchIn(t),
        )
        assertTrue(
            "换装深等比对（stars/kindCounts）丢失",
            Regex("verdictMap\\.stars != displayed\\.stars").containsMatchIn(t),
        )
        // 复用门（零 IO 防每进页重查——verdictApplied 单旗覆盖两侧）
        assertTrue(
            "宣判复用门（cached.verdictApplied）丢失",
            Regex("cached\\.verdictApplied").containsMatchIn(t) && Regex("applyCachedVerdictR317b\\(fresh, cached\\)").containsMatchIn(t),
        )
        // 点查 IO 协程（异步后置——同步装配路径零增量）
        assertTrue(
            "点查未走 IO 协程（Dispatchers.IO）",
            Regex("vm\\.deepIndex\\.chatPublishHistogram\\(c\\.chatId\\)").containsMatchIn(t) &&
                Regex("vm\\.deepIndex\\.chatPublishHistogram\\(b\\.chatId\\)").containsMatchIn(t),
        )
    }

    // ==================== 主方法零接线铁令（R315 工程约束） ====================

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
    fun `主方法零接线-宣判标记不进主composable本体`() {
        val body = mainFunctionBody(File(mainSourceRoot, "dev/tgfeed/ui/TasteMapScreen.kt"))
        // 宣判接线全在顶层函数/数据模型——主方法本体零宣判标记
        // （回填主方法=寄存器压力回顶 ART 校验线——本锚即红）
        assertTrue(
            "主 composable 本体出现宣判接线标记（主方法零接线铁令被破——迁顶层函数！）",
            !Regex("R317b|R325|R327|verdictApplied|runTerminalVerdict|runLifeVerdict|migrateTerminal|migrateLife|runBirthVerdict").containsMatchIn(body),
        )
    }
}
