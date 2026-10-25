package dev.tgfeed.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R316b 星图主方法大小预算闸（防第三次复发——R258 拆完无人把守，六轮加料
 * R271/R282/R301/R302/R312/R314 把主方法顶回 15062 DEX units/267 寄存器=
 * 16384 进星图即 VerifyError 崩溃档）。
 *
 * 口径：brace 配对量 `TasteMapScreen` 主 composable 源行数（剥 `//` 行注释的
 * 简化配对——本函数体内花括号字符串模板均行内自平衡，r316_extract_main.py
 * 两版实测 943/1418 行与 dexdump 10201/15062 units 单调对应，口径够用）。
 *
 * 预算：主函数 ≤ [MAIN_BUDGET_LINES] 源行。锚定 R258 实测安全档（943 行 ↔
 * dexdump 10201 units/200 寄存器，16384 真机无 VerifyError）+ 6% 余量。
 * 精确仪见 scripts/r316_dex_budget.py（dexdump units/寄存器断言，出包后跑）。
 * 超线=又该照 R258/R316b 模式拆分了：语句原样搬移+GalaxyLogicCtx 句柄只加不改。
 */
class TasteMapMethodBudgetTest {

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

    /** brace 配对量主函数体行数（签名 `) {` 起至配平闭括号止——r316_extract_main.py 同款） */
    private fun mainFunctionLines(file: File): Int {
        val lines = file.readLines()
        val start = lines.indexOfFirst { Regex("^fun TasteMapScreen\\(").containsMatchIn(it) }
        require(start >= 0) { "主 composable TasteMapScreen 未找到" }
        var bodyStart = -1
        for (j in start until lines.size) {
            if (Regex("\\)\\s*\\{\\s*(//.*)?$").containsMatchIn(lines[j])) { bodyStart = j; break }
        }
        require(bodyStart >= 0) { "主函数体开括号未找到" }
        var depth = 0
        for (j in bodyStart until lines.size) {
            val code = lines[j].split("//")[0]
            depth += code.count { it == '{' } - code.count { it == '}' }
            if (depth == 0) return j - start + 1
        }
        error("主函数体闭括号未配平")
    }

    @Test
    fun `星图主函数行数在预算内`() {
        val file = File(mainSourceRoot, "dev/tgfeed/ui/TasteMapScreen.kt")
        assertTrue("TasteMapScreen.kt 必须存在：${file.path}", file.isFile)
        val n = mainFunctionLines(file)
        assertTrue(
            "TasteMapScreen 主函数 $n 行超预算 $MAIN_BUDGET_LINES 行——R258/R316b 结构拆分" +
                "被加料顶回（按 ~10.7 units/行折算约 ${n * 107 / 10} units 量级，ART 校验线" +
                " 10200 units/200 寄存器）。照 r258_notes/r316_notes 模式再拆：语句原样" +
                "搬移+GalaxyLogicCtx 句柄只加不改",
            n <= MAIN_BUDGET_LINES,
        )
    }

    @Test
    fun `二次拆分锚在位（防回填式复发）`() {
        val text = File(mainSourceRoot, "dev/tgfeed/ui/TasteMapScreen.kt").readText()
        // 舞台件定义与主方法内唯一调用（Box 内容回填主方法=本锚即红）
        assertTrue(
            "TasteMapStageR316 舞台件定义丢失（R316b 拆分被回退？）",
            Regex("private fun BoxScope\\.TasteMapStageR316\\(").containsMatchIn(text),
        )
        // 世界卡/导出六件顶层函数（局部函数回填主方法=本锚即红）
        for (fn in listOf(
            "evaluateAchievementsR316", "exportTasteMapPosterR316", "buildVoyageTrailR316",
            "exportVoyagePosterR316", "openWorldCardR316", "closeWorldCardR316", "startWorldDiveR316",
        )) {
            assertTrue(
                "顶层函数 $fn 丢失（R316b 拆分被回退？）",
                Regex("private fun $fn\\(").containsMatchIn(text),
            )
        }
    }

    private companion object {
        /** R258 安全档 943 行 + 6% 余量（≈dexdump 10200 units 线） */
        const val MAIN_BUDGET_LINES = 1000
    }
}
