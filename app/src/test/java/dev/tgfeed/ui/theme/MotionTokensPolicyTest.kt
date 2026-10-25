package dev.tgfeed.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R178 动效常数红线守护（design-proposal §5.3 纪律三「不许第二种节奏」的机检化，
 * ScrimPolicyTest 同款范式）：
 *
 * 1. **spring 红线**：全仓 `spring(…)`（含 `spring<Float>(…)` 泛型形态）的实参必须引用
 *    [MotionTokens]——转场 spring 只许三枚（Sheet 0.88/420、Page 1.0/380、
 *    ZoomReset 0.82/500）+ 反馈应答 LikeBounce 一枚，任何新 spring 自造常数即测试红；
 * 2. **tween 红线**：`tween(…)` 的时长实参（首个实参，含 `durationMillis = …` 命名形态）
 *    不许含裸数字字面量——时长必须引用 TgFeedTokens.AnimFast/Med/Slow、
 *    MotionTokens 场景常数或具名场景本地常数（裸 300/120/600 这类就是第二种节奏）。
 *
 * 豁免清单（文件级，带理由——理由同时写进对应源码注释）：手势应答单现场物理
 * （ProgressLine 进度线 Q 弹/拇指弹出、GridOverlays 落区胶囊）、连续呼吸/脉动/
 * 指示器周期（AudioCard 封面呼吸、DiscoverScreen 骨架、ChannelHome 转圈/呼吸、
 * GridCellChrome 声波柱）、粒子物理（LikeHearts）、格子内容微动效族（GridVideoCell，
 * design-redesign §6.1/§6.3 指定节律）。**豁免不是免死金牌**：往豁免文件里新增别的
 * spring/tween 字面量同样会红（spring 线按「未引用 MotionTokens」判、tween 线按
 * 「时长含裸数字」判，与豁免理由无关的违规照样抓）——豁免只豁免既登记的那一类。
 *
 * 合规路径：需要新转场 spring/时长时，先在 MotionTokens（Theme.kt）立具名 token
 * （带用途注释与 §5.2 表行出处），再引用；确属新物理族（连续周期/粒子/微动效）
 * 才进豁免清单并写明理由。
 */
class MotionTokensPolicyTest {

    /** spring 红线豁免（相对 app/src/main/java 的路径，正斜杠统一）——理由见类注释 */
    private val springExemptions = mapOf(
        "dev/tgfeed/ui/components/ProgressLine.kt" to
            "进度线交互物理：按压加粗 Q 弹 0.75/1500、拇指弹出 0.5/10000——手势应答单现场",
        "dev/tgfeed/ui/GridOverlays.kt" to
            "悬浮落区胶囊 1.05 应答放大 0.6/1500——拖拽耦合反馈单现场",
    )

    /** tween 裸字面量红线豁免——理由见类注释 */
    private val tweenLiteralExemptions = mapOf(
        "dev/tgfeed/ui/components/AudioCard.kt" to
            "封面呼吸 600ms 半程：无限循环连续脉动（时长=呼吸物理周期，非转场）",
        "dev/tgfeed/ui/DiscoverScreen.kt" to
            "骨架卡微光呼吸 650ms：连续占位脉动周期（非转场）",
        "dev/tgfeed/ui/ChannelHomeScreen.kt" to
            "考古骰子转圈 1600ms / 穿越行呼吸 700ms：连续指示器周期（非转场）",
        "dev/tgfeed/ui/TimeBlindboxSheet.kt" to
            "时间盲盒骰子缓转 1200ms：连续指示器周期（非转场，考古骰子同族）",
        "dev/tgfeed/ui/GridCellChrome.kt" to
            "声波三柱 600ms 循环：连续指示器周期（非转场）",
        "dev/tgfeed/ui/components/LikeHearts.kt" to
            "爱心粒子飘升 850ms：粒子物理（时长=行程/速度）",
        "dev/tgfeed/ui/GridVideoCell.kt" to
            "格子内容微动效族 240/200/200·150/120：design-redesign §6.1/§6.3 指定节律，走查调定",
    )

    /** 动效常数 token 家（spring/tween 字面量唯一合法归宿） */
    private val tokenHomes = setOf(
        "dev/tgfeed/ui/theme/Theme.kt",
    )

    // ---------------- 扫描器（纯函数，注释剥离 + 括号配平） ----------------

    /**
     * 剥离注释（保换行——行号不漂移）：块注释整个变空格、行注释截到行尾；
     * 字符串/字符字面量里的注释记号（双斜杠、斜杠星）不受影响（先过字符串状态机再判注释）。
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

    /** 从 openParen 起做括号配平，返回实参区文本（含两端括号之间的内容，不含括号） */
    private fun balancedArgs(code: String, openParen: Int): String {
        var depth = 0
        var i = openParen
        while (i < code.length) {
            val c = code[i]
            if (c == '(') depth++
            if (c == ')') {
                depth--
                if (depth == 0) return code.substring(openParen + 1, i)
            }
            i++
        }
        return code.substring(openParen + 1) // 防御性：文件异常时取到尾
    }

    /** 首个实参（顶层逗号切分，支持 `durationMillis = …` 命名形态） */
    private fun firstArgument(args: String): String {
        var depth = 0
        for ((i, c) in args.withIndex()) {
            when (c) {
                '(', '<', '[' -> depth++
                ')', '>', ']' -> depth--
                ',' -> if (depth == 0) return args.substring(0, i)
            }
        }
        return args
    }

    /** 数字字面量判定（前一个字符不是标识符字符——ms2/AnimFast 之类不误伤） */
    private fun hasBareNumber(expr: String): Boolean =
        Regex("(^|[^A-Za-z0-9_.])\\d").containsMatchIn(expr.trim())

    /** 定位主源码根：Gradle 单测工作目录是 app/ 或仓库根 */
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

    /** 扫描产物：sourcePath:line -> 违规片段 */
    private data class Offender(val rel: String, val line: Int, val snippet: String)

    /** (relPath, 剥注释后源码, 行偏移表) 逐文件产出 */
    private fun forEachSource(action: (rel: String, stripped: String) -> Unit) {
        val root = mainSourceRoot()
        assertTrue("主源码根必须存在：${root.path}", root.isDirectory)
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .forEach { file ->
                val rel = file.relativeTo(root).invariantSeparatorsPath
                action(rel, stripComments(file.readText()))
            }
    }

    /** 剥注释后源码里的字符偏移 -> 行号（剥离保换行，行号与原文件一致） */
    private fun lineOf(stripped: String, offset: Int): Int =
        1 + stripped.substring(0, offset).count { ch -> ch == '\n' }

    // ---------------- 红线 1：spring 必须引用 MotionTokens ----------------

    @Test
    fun `spring 调用必须引用 MotionTokens 或在豁免清单`() {
        val offenders = mutableListOf<Offender>()
        var scannedCallSites = 0
        val springCall = Regex("spring\\s*(<[^>]*>)?\\s*\\(")
        forEachSource { rel, stripped ->
            if (rel in tokenHomes) return@forEachSource
            springCall.findAll(stripped).forEach { m ->
                scannedCallSites++
                val args = balancedArgs(stripped, m.range.last)
                if ("MotionTokens." !in args && rel !in springExemptions) {
                    offenders += Offender(rel, lineOf(stripped, m.range.first), m.value.trim())
                }
            }
        }
        // 防跑空：扫描器必须真的看到了 spring 调用（正则失效/源码根错位时这里先红）
        assertTrue(
            "spring 扫描跑空（全仓至少应有 MotionTokens 三消费者 + 豁免现场的调用在册）",
            scannedCallSites >= 6,
        )
        assertTrue(
            "spring 红线被突破——以下位置自造了 spring 常数（转场只许 MotionTokens 三枚" +
                "Sheet/Page/ZoomReset；反馈应答只许 LikeBounce。先在 Theme.kt 立 token 再引用；" +
                "确属新物理族进豁免清单并写理由）：\n" +
                offenders.joinToString("\n") { "  ${it.rel}:${it.line}  ${it.snippet}" },
            offenders.isEmpty(),
        )
    }

    // ---------------- 红线 2：tween 时长不许裸数字字面量 ----------------

    @Test
    fun `tween 时长实参不许裸数字字面量`() {
        val offenders = mutableListOf<Offender>()
        var scannedCallSites = 0
        forEachSource { rel, stripped ->
            if (rel in tokenHomes || rel in tweenLiteralExemptions) return@forEachSource
            Regex("tween\\s*\\(").findAll(stripped).forEach { m ->
                scannedCallSites++
                val args = balancedArgs(stripped, m.range.last)
                val durationArg = firstArgument(args)
                if (hasBareNumber(durationArg)) {
                    offenders += Offender(
                        rel, lineOf(stripped, m.range.first),
                        "tween($durationArg…)",
                    )
                }
            }
        }
        // 防跑空：全仓 token 引用型 tween 调用至少数十处在册（正则失效时这里先红）
        assertTrue("tween 扫描跑空（全仓应看到大量 token 引用型 tween 调用）", scannedCallSites >= 30)
        assertTrue(
            "tween 红线被突破——以下位置的 tween 时长是裸数字（AnimFast/Med/Slow 三档与" +
                "MotionTokens 场景常数之外不许第二种节奏；换算式/具名常数/进豁免清单三选一）：\n" +
                offenders.joinToString("\n") { "  ${it.rel}:${it.line}  ${it.snippet}" },
            offenders.isEmpty(),
        )
    }

    // ---------------- 红线测试没有跑空（ScrimPolicyTest 同款防呆） ----------------

    @Test
    fun `MotionTokens 关键常数仍在_红线测试没有跑空`() {
        val theme = File(mainSourceRoot(), "dev/tgfeed/ui/theme/Theme.kt")
        assertTrue("token 家文件应存在（改名请同步 MotionTokensPolicyTest）", theme.isFile)
        val text = theme.readText()
        listOf(
            "SheetSpringDamping",
            "PageSpringDamping",
            "ZoomResetSpringDamping",
            "LikeBounceDamping",
            "SheetScrimInMs",
            "SheetStaggerStepMs",
            "CountUpMs",
            "EmphasizedEasing",
        ).forEach { token ->
            assertTrue("MotionTokens 应保有 token：$token（被删则红线失去意义）", token in text)
        }
        // 三档时长主干（TgFeedTokens）也在同一家
        listOf("AnimFast", "AnimMed", "AnimSlow").forEach { token ->
            assertTrue("TgFeedTokens 应保有时长档：$token", token in text)
        }
        // 豁免清单里的文件都真实存在（防改名后清单跑空）
        (springExemptions.keys + tweenLiteralExemptions.keys).forEach { rel ->
            assertTrue("豁免清单文件应存在：$rel（改名/删除请同步更新豁免清单）", File(mainSourceRoot(), rel).isFile)
        }
    }
}
