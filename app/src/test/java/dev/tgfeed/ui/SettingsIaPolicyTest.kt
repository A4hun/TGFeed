package dev.tgfeed.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R198 设置页信息架构红线守护（MotionTokensPolicyTest 同款范式：源码文本机检，
 * 本测试与 MotionTokensPolicyTest 各自内嵌扫描器属仓内既定范式，不引共享测试工具件）。
 *
 * 背景：用户实锤「太乱了，萌新根本看不明白什么是什么」——16 张卡无分层平铺是
 * 一类问题；R198 治理成「常用区 ≤6 卡 + 高级折叠区 + 底部惯例区」三层后，用本测试
 * 锁住结构红线，防 IA 腐化回「全平铺」：
 *
 * 1. **常用卡数红线**：高级折叠区标记之前的 SettingsCard 张数 ≤ 6——新增设置默认
 *    进高级折叠区（AdvancedCardTitles 同步登记）；要顶进常用区必须先想清楚顶掉谁；
 * 2. **高级区清单同步红线**：AdvancedCardTitles（头部「高级 · N 项」计数行的唯一
 *    数据源）必须与折叠区内实际卡片逐一对应（数量与顺序都相等）——清单漂移会让
 *    计数行对用户说谎；
 * 3. **底部惯例红线**：数据迁移 / 危险区必须位于高级折叠区之后（用户拍板的底部
 *    惯例不动）；
 * 4. **术语红线**：白嫖 / 代次闸 / FFmpeg / 分档 / 配额不得出现在设置页 UI 文案
 *    （剥注释后扫描——代码注释是开发者语境不受限，UI 字符串露给用户才管；术语要
 *    说清的机理收进 SettingsHelpHint 问号气泡时也必须先翻译成人话）。
 *
 * 维护口径：新增/删除/改名高级区卡片时，同步改 SettingsScreen.kt 的
 * AdvancedCardTitles 与折叠区内实际卡片（两处一起动，本测试红就是漏了一处）。
 */
class SettingsIaPolicyTest {

    /** 定位主源码根：Gradle 单测工作目录是 app/ 或仓库根（与 MotionTokensPolicyTest 同款） */
    private fun mainSourceRoot(): File {
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

    /**
     * 剥离注释（保换行——行号不漂移）：块注释整个变空格、行注释截到行尾；
     * 字符串字面量里的注释记号不受影响（先过字符串状态机再判注释）。
     * （与 MotionTokensPolicyTest.stripComments 同款逻辑，内嵌副本）
     */
    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var inBlock = false
        var inString = false
        var inChar = false
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else ' '
            if (inBlock) {
                if (c == '*' && next == '/') {
                    inBlock = false
                    i += 2
                } else {
                    sb.append(if (c == '\n') '\n' else ' ')
                    i++
                }
            } else if (inString || inChar) {
                if (c == '\\') {
                    sb.append(c).append(next)
                    i += 2
                } else {
                    if (inString && c == '"') inString = false
                    if (inChar && c == '\'') inChar = false
                    sb.append(c)
                    i++
                }
            } else {
                when {
                    c == '"' -> { inString = true; sb.append(c); i++ }
                    c == '\'' -> { inChar = true; sb.append(c); i++ }
                    c == '/' && next == '/' -> {
                        val nl = src.indexOf('\n', i)
                        if (nl < 0) {
                            i = src.length
                        } else {
                            sb.append('\n')
                            i = nl + 1
                        }
                    }
                    c == '/' && next == '*' -> { inBlock = true; i += 2 }
                    else -> { sb.append(c); i++ }
                }
            }
        }
        return sb.toString()
    }

    /** 读 SettingsScreen.kt 源码（相对 app/src/main/java 的路径与 MotionTokens 一致） */
    private fun settingsSource(): File {
        val f = File(mainSourceRoot(), "dev/tgfeed/ui/SettingsScreen.kt")
        assertTrue("设置页源码应存在（改名/移动请同步本测试）", f.isFile)
        return f
    }

    /** 卡片调用位（带引号形态——`SettingsCard("` 只匹配调用，函数定义无引号不误伤） */
    private fun cardCallSites(raw: String): List<Pair<String, Int>> {
        val re = Regex("SettingsCard\\(\"([^\"]+)\"")
        return re.findAll(raw).map { m -> m.groupValues[1] to m.range.first }
            .sortedBy { it.second }.toList()
    }

    // ---------------- 红线 1：常用卡数 ≤ 6 ----------------

    @Test
    fun `常用区卡片数不超六张_防IA腐化回平铺`() {
        val raw = settingsSource().readText()
        val advStart = raw.indexOf(ADV_START_MARKER)
        val advEnd = raw.indexOf(ADV_END_MARKER)
        assertTrue("高级折叠区开始标记应在（改名请同步本测试）", advStart >= 0)
        assertTrue("高级折叠区结束标记应在（改名请同步本测试）", advEnd > advStart)
        val visible = cardCallSites(raw).filter { it.second < advStart }
        assertEquals(
            "默认视图（高级折叠区之前）常用卡应恰为 R198 五张目标卡：播放/内容类型/" +
                "省流与存储/发现新内容/账号与数据——增卡默认进高级折叠区；确需进常用区" +
                "先裁一张再进（发现=${visible.map { it.first }}）",
            listOf("播放", "内容类型", "省流与存储", "发现新内容", "账号与数据"),
            visible.map { it.first },
        )
    }

    // ---------------- 红线 2：高级区清单与实际卡片逐一对应 ----------------

    @Test
    fun `高级区清单与实际卡片数量顺序逐一相等_计数行不说谎`() {
        val raw = settingsSource().readText()
        val advStart = raw.indexOf(ADV_START_MARKER)
        val advEnd = raw.indexOf(ADV_END_MARKER)
        assertTrue(ADV_START_MARKER + " 标记应在", advStart >= 0)
        assertTrue(ADV_END_MARKER + " 标记应在", advEnd > advStart)
        // 清单侧：AdvancedCardTitles = listOf(...) 里逐项解析
        val listMatch = Regex("AdvancedCardTitles\\s*=\\s*listOf\\(([^)]*)\\)").find(raw)
        assertTrue("AdvancedCardTitles 清单应在源码中（增删高级卡必须同步它）", listMatch != null)
        val declared = Regex("\"([^\"]+)\"").findAll(listMatch!!.groupValues[1])
            .map { it.groupValues[1] }.toList()
        // 实际侧：折叠区标记之间的卡片调用（按出现顺序）
        val advanced = cardCallSites(raw).filter { it.second in (advStart + 1) until advEnd }
            .map { it.first }
        assertEquals(
            "AdvancedCardTitles 与折叠区内实际卡片必须逐一对应（数量与顺序）：清单=" +
                "$declared，实际=$advanced——两处一起改，漏一处计数行「高级 · N 项」就对用户说谎",
            declared, advanced,
        )
        assertTrue("高级区不应是空的", declared.isNotEmpty())
    }

    // ---------------- 红线 3：数据迁移/危险区永远在底部 ----------------

    @Test
    fun `数据迁移与危险区永远在高级折叠区之后_底部惯例不动`() {
        val raw = settingsSource().readText()
        val advEnd = raw.indexOf(ADV_END_MARKER)
        assertTrue(ADV_END_MARKER + " 标记应在", advEnd >= 0)
        val bottom = cardCallSites(raw).filter { it.second > advEnd }.map { it.first }
        assertTrue(
            "数据迁移应在高级折叠区之后的底部区（用户拍板惯例）——实际尾部序=$bottom",
            "数据迁移" in bottom,
        )
        assertTrue(
            "危险区应是最后一张卡（用户拍板惯例）——实际尾部序=$bottom",
            bottom.last() == "危险区",
        )
    }

    // ---------------- 红线 4：术语不出现在 UI 文案 ----------------

    @Test
    fun `术语不进UI文案_白嫖代次闸FFmpeg分档配额剥注释后零出现`() {
        val stripped = stripComments(settingsSource().readText())
        val banned = listOf("白嫖", "代次闸", "FFmpeg", "分档", "配额")
        val leaked = banned.filter { it in stripped }
        assertTrue(
            "设置页 UI 文案出现内部术语（剥注释后仍可见=露给用户）：$leaked——" +
                "机理要讲清先翻译成人话（问号气泡/副文案都算 UI 文案；代码注释不受限）",
            leaked.isEmpty(),
        )
    }

    // ---------------- 附件：头部计数行与清单同源 ----------------

    @Test
    fun `高级计数行与清单同源_不许手写数字`() {
        val raw = settingsSource().readText()
        // R361 开源收口锚翻正：「诊断通道」卡 release 整卡隐藏（BuildConfig.DEBUG 闸），
        // 计数行改引 AdvancedVisibleCount（=清单尺寸减去 release 隐藏卡数，定义处注释
        // 记裁决）——直接引清单尺寸会对 release 用户说谎，手写数字同罪
        assertTrue(
            "头部计数行应引用 AdvancedVisibleCount（R361：诊断卡 release 隐藏后计数诚实；手写数字会在增删卡后说谎）",
            "高级 · \${AdvancedVisibleCount} 项" in raw,
        )
        assertTrue(
            "AdvancedVisibleCount 必须由 AdvancedCardTitles 派生（不许手写数字）",
            Regex("AdvancedVisibleCount\\s*=\\s*AdvancedCardTitles\\.size").containsMatchIn(raw),
        )
    }

    private companion object {
        const val ADV_START_MARKER = "高级折叠区开始"
        const val ADV_END_MARKER = "高级折叠区结束"
    }
}
