package dev.tgfeed.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R352 反茧房·探索边界段接线源码锚（R319WiringSourceTest 同款 user.dir 源定位
 * 模式——面板/穿透链无 JVM 直测面的结构件以源锚把守；行为半体由
 * AstroFactsTest（R352 组：视界行/产房行/第七问追加句）承重）：
 *  - **面板段在场**：TasteMapAchievementPanel 体含「🕳 探索边界」段头+视界行
 *    （edgeHorizonLine）+产房行（edgeNurseryLine）+R331 终态奇观册段
 *    （wonderCountsLine+unseenWonderHints——r331_notes ①节挂账规格抄行收口）；
 *  - **面板三原料穿透**：舞台件调用位 mapData/olbers/nebulae 直供（olbers 与
 *    画布层底幕同一 zone 单源——数字同拍同源铁令）；
 *  - **信息卡穿透链五环**：舞台（rawDiff 直供）→StarInfoCard→VitalsRows→
 *    ScienceSection（remember 键）→WhyInput.horizonBeyondN——缺一环即红；
 *  - **主方法零接线**（R315 工程约束）：主 composable 本体零 R352 标记（接线
 *    全在舞台件/面板件/顶层函数——回填主方法=预算闸被顶的入口）；
 *  - **产房单口**：面板派生走 mapEmissionFogsR323 平行表单口（零第二判据
 *    纪律§一）+logProbe=false 静默复用（画布层装配口径默认 true 零变化）。
 */
class R352WiringSourceTest {

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

    private fun src(rel: String): String {
        val file = File(mainSourceRoot, rel)
        assertTrue("源文件必须存在：$rel", file.isFile)
        return file.readText()
    }

    /** 函数体提取（签名行→`) {` 体开行→brace 配对；R317b mainFunctionBody 同款
     *  两段式——多行参数表若直接配对会在参数行深度恒 0 处提前收口） */
    private fun funBody(text: String, signature: Regex): String {
        val lines = text.lines()
        val start = lines.indexOfFirst { signature.containsMatchIn(it) }
        assertTrue("函数未找到：$signature", start >= 0)
        var bodyStart = -1
        for (j in start until lines.size) {
            if (Regex("\\)\\s*\\{\\s*(//.*)?$").containsMatchIn(lines[j])) { bodyStart = j; break }
        }
        assertTrue("函数体开括号未找到：$signature", bodyStart >= 0)
        var depth = 0
        val sb = StringBuilder()
        for (j in bodyStart until lines.size) {
            val code = lines[j].split("//")[0]
            depth += code.count { it == '{' } - code.count { it == '}' }
            sb.appendLine(lines[j])
            if (depth == 0 && j > bodyStart) return sb.toString()
        }
        error("函数体闭括号未配平：$signature")
    }

    /** 锚计数断言（穿透环位数机检——断环即计数错位） */
    private fun assertAnchorCount(t: String, anchor: String, expect: Int) {
        val n = Regex(Regex.escape(anchor)).findAll(t).count()
        assertTrue(
            "穿透锚计数错位：$anchor 期望 $expect 处实际 $n 处（穿透链断环）",
            n == expect,
        )
    }

    // ==================== 面板段在场（三行族+R331 挂账收口） ====================

    @Test
    fun `面板段-探索边界段头与三行族在场`() {
        val t = src("dev/tgfeed/ui/TasteMapScreen.kt")
        val body = funBody(t, Regex("private fun BoxScope\\.TasteMapAchievementPanel\\("))
        // 段头（🕳=olbers 词条 emoji 镜像——视觉语言同源）
        assertTrue("探索边界段头丢失", body.contains("\"🕳 探索边界\""))
        // 行 1 视界统计（rawDiff 全量真值——缺据行不出归 AstroFacts 单口）
        assertTrue(
            "视界行 edgeHorizonLine 消费丢失",
            Regex("AstroFacts\\.edgeHorizonLine\\(edge\\.beyondN\\)\\?\\.let \\{ line ->").containsMatchIn(body),
        )
        // 行 3 产房指引（段尾 call-to-action）
        assertTrue(
            "产房行 edgeNurseryLine 消费丢失",
            Regex("AstroFacts\\.edgeNurseryLine\\(edge\\.fogN, edge\\.nurseryN, edge\\.nurseryChannels, edge\\.fogChannels\\)\\?\\.let \\{ line ->")
                .containsMatchIn(body),
        )
        // 段序铁令：视界行在图鉴缺口段之前、产房行在图鉴缺口段之后（任务书三行族
        // 原序=视界统计→图鉴缺口→金矿摇篮指引）
        val iHorizon = body.indexOf("edgeHorizonLine(edge.beyondN)")
        val iKind = body.indexOf("天体图鉴 · 还没见过")
        val iNursery = body.indexOf("edgeNurseryLine(edge.fogN")
        assertTrue("段序被破（视界→图鉴→产房）", iHorizon >= 0 && iKind > iHorizon && iNursery > iKind)
        // R331 挂账收口：奇观册两行族（wonderCountsLine+unseenWonderHints）在段内
        assertTrue(
            "R331 奇观册计数行消费丢失（挂账收口被回退？）",
            Regex("AstroFacts\\.wonderCountsLine\\(mapData\\?\\.kindCounts\\.orEmpty\\(\\)\\)").containsMatchIn(body),
        )
        assertTrue(
            "R331 奇观册缺口行消费丢失",
            Regex("for \\(hint in AstroFacts\\.unseenWonderHints\\(kindsSeen\\)\\)").containsMatchIn(body),
        )
        // 六态册既有行族保活（R312 口径零改——kindsSeen 面板内派生）
        assertTrue(
            "六态册 kindHints 消费丢失（R312 回归）",
            Regex("val kindsSeen = mapData\\?\\.kindCounts\\?\\.keys\\.orEmpty\\(\\)").containsMatchIn(body) &&
                body.contains("AstroFacts.unseenKindHints(kindsSeen)"),
        )
        // 诚实注脚扩行（金矿 6h 口径——数据时点标注）
        assertTrue(
            "探索边界诚实注脚丢失（金矿快照口径）",
            body.contains("探索边界按金矿快照"),
        )
    }

    // ==================== 面板三原料穿透（舞台件调用位） ====================

    @Test
    fun `面板穿透-三原料舞台件直供`() {
        val t = src("dev/tgfeed/ui/TasteMapScreen.kt")
        // olbers 与画布层底幕同一 zone 单拍读数（数字同源铁令——面板视界行/信息卡
        // ⑦ 追加句/底幕暗点三消费同一 OlbersZone）
        assertTrue(
            "面板 olbers 直供锚丢失（cmbZoneR319 同源）",
            Regex("olbers = cmbZoneR319\\.value,").containsMatchIn(t),
        )
        assertTrue(
            "面板 nebulae 直供锚丢失（舞台件单消费位）",
            Regex("nebulae = nebulae,").containsMatchIn(t),
        )
        assertTrue(
            "面板 mapData 直供锚丢失（三原料相邻位）",
            Regex("mapData = mapData,[\\s\\S]{0,60}?olbers = cmbZoneR319\\.value,").containsMatchIn(t),
        )
        // 旧参 kindsSeen 穿透退役（原料改 mapData 整体——键集面板内派生零口径变化；
        // 调用位形态=尾逗号命名参，面板内派生形态=val 局部声明——本锚只禁前者）
        assertTrue(
            "旧 kindsSeen 参数穿透残留（应改 mapData 整体穿透）",
            !Regex("kindsSeen = mapData\\?\\.kindCounts\\?\\.keys\\.orEmpty\\(\\),").containsMatchIn(t),
        )
    }

    // ==================== 信息卡穿透链五环（第七问追加句原料） ====================

    @Test
    fun `穿透链-茧房外计数五环齐`() {
        val t = src("dev/tgfeed/ui/TasteMapScreen.kt")
        // 环 1：舞台件 rawDiff 直供（-1=zone 缺席缺据——与 rObs 同拍同源）
        assertTrue(
            "舞台 rawDiff 直供锚丢失",
            Regex("cmbHorizonBeyondN = cmbZoneR319\\.value\\?\\.rawDiff \\?: -1,").containsMatchIn(t),
        )
        // 环 2~4：三层参数穿透（三签名+两调用——穿透环位数机检；R319 cmbHorizonR
        // 同族穿透模式）
        assertAnchorCount(t, "cmbHorizonBeyondN: Int = -1,", 3)
        assertAnchorCount(t, "cmbHorizonBeyondN = cmbHorizonBeyondN,", 2)
        // 环 5：ScienceSection remember 键+WhyInput 字段（缺一环=zone 换图后计数不跟新）
        assertTrue(
            "ScienceSection pack remember 键缺 cmbHorizonBeyondN（zone 换图后计数不跟新）",
            Regex("remember\\(st\\.chatId, volumeRank01, meanItems, bin\\?\\.weight, mateTitle, cmbHorizonR, cmbHorizonBeyondN, st\\.drift\\)")
                .containsMatchIn(t),
        )
        assertTrue(
            "WhyInput horizonBeyondN 装配锚丢失",
            Regex("horizonBeyondN = cmbHorizonBeyondN,").containsMatchIn(t),
        )
    }

    // ==================== 主方法零接线 + 产房单口 ====================

    @Test
    fun `主方法零接线-R352标记不进主composable本体`() {
        val t = src("dev/tgfeed/ui/TasteMapScreen.kt")
        val body = funBody(t, Regex("^fun TasteMapScreen\\("))
        // 主方法本体零 R352 接线标记（接线全在舞台件/面板件/顶层函数——回填主方法
        // =寄存器压力回顶 ART 校验线，本锚即红）
        assertTrue(
            "主 composable 本体出现 R352 接线标记（主方法零接线铁令被破——迁舞台件/面板件！）",
            !Regex("edgeStats|EdgeStats|edgeHorizon|edgeNursery|cmbHorizonBeyond|olbers|nebulae|wonderCounts|unseenWonder|logProbe")
                .containsMatchIn(body),
        )
    }

    @Test
    fun `产房单口-面板派生走平行表零第二判据`() {
        val t = src("dev/tgfeed/ui/TasteMapScreen.kt")
        // 派生收口顶层函数（面板 remember 体零计算体——大方法字节码瘦身纪律）
        assertTrue(
            "edgeStatsR352 顶层定义丢失（独立函数铁令）",
            Regex("private fun edgeStatsR352\\(").containsMatchIn(t),
        )
        // 产房计数=mapEmissionFogsR323 平行表单口复用（团级门唯一判据——纪律§一
        // 零第二判据复制；logProbe=false 面板静默复用防重复 K⑤ 探针行）
        assertTrue(
            "面板产房派生未走 mapEmissionFogsR323 单口",
            Regex("mapEmissionFogsR323\\(mapData, nebulae, logProbe = false\\)").containsMatchIn(t),
        )
        // logProbe 默认 true（画布层装配口径零变化——旧调用点不传参即原样）
        assertTrue(
            "logProbe 默认值锚丢失（画布层装配探针口径被破）",
            Regex("logProbe: Boolean = true,").containsMatchIn(t),
        )
        // 面板 edge remember 键三件（换图自动跟新——栏四时序口径）
        assertTrue(
            "面板 edge remember 键丢失（mapData/nebulae/olbers 三键）",
            Regex("remember\\(mapData, nebulae, olbers\\) \\{ edgeStatsR352\\(mapData, nebulae, olbers\\) \\}")
                .containsMatchIn(t),
        )
        // 行不出的裁决归 AstroFacts 单口（面板只派生数字不做文案分叉）
        val body = funBody(t, Regex("private fun edgeStatsR352\\("))
        assertTrue(
            "edgeStatsR352 出现文案分叉（裁决归 AstroFacts 单口）",
            !Regex("茧房|产房|路上").containsMatchIn(body),
        )
    }
}
