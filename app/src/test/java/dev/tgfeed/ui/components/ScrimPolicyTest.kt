package dev.tgfeed.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R162 遮罩红线守护（scrim-audit.md 政策 P4）：
 * Color.Black.copy(alpha=…) 这类"黑色半透明层"字面量只准出现在 token 家——
 *   ui/theme/Theme.kt（OverlayBg）与 ui/components/OverlayStyles.kt（政策家，
 *   含 OverlayChipBg/OverlayInfoChipBg/WatchedDimLayer/OverlayBadgeBorder/ModalDimLayer）。
 * 其余任何源文件再出现该字面量 = 又在自造遮罩 alpha（遮罩补丁病复发），测试即红。
 *
 * 合规路径：需要黑色半透明层时，先在 OverlayStyles.kt 立具名 token（带用途注释），
 * 并在仓库根 scrim-audit.md 盘点表登记，再引用 token。
 * 白色/品牌色的半透明（描边、轨道、占位渐变）不在红线内——本测只守"黑纱"一族。
 */
class ScrimPolicyTest {

    /** token 家（相对 app/src/main/java 的路径，正斜杠统一） */
    private val tokenHomes = setOf(
        "dev/tgfeed/ui/theme/Theme.kt",
        "dev/tgfeed/ui/components/OverlayStyles.kt",
    )

    /** 定位主源码根：Gradle 单测工作目录是 app/（src/main/java）或仓库根（app/src/main/java） */
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

    @Test
    fun `黑色半透明字面量只准出现在 token 家`() {
        val root = mainSourceRoot()
        assertTrue("主源码根必须存在：${root.path}", root.isDirectory)
        val blackLiteral = Regex("Color\\.Black\\.copy\\s*\\(")
        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val rel = file.relativeTo(root).invariantSeparatorsPath
                if (rel in tokenHomes) return@forEach
                file.readLines().forEachIndexed { i, line ->
                    if (blackLiteral.containsMatchIn(line)) {
                        offenders += "$rel:${i + 1}: ${line.trim()}"
                    }
                }
            }
        assertTrue(
            "遮罩红线被突破——以下位置自造了黑色半透明层（先在 OverlayStyles.kt 立 token" +
                "并在 scrim-audit.md 登记，再引用 token；内容区一律零遮罩走 withOverlayShadow）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `token 家仍在原位_红线测试没有跑空`() {
        val root = mainSourceRoot()
        tokenHomes.forEach { rel ->
            val f = File(root, rel)
            assertTrue("token 家文件应存在：$rel（改名请同步更新 ScrimPolicyTest）", f.isFile)
        }
        // 关键 token 各至少存活一处（防止 token 被误删后红线测试失去意义）
        val overlayStyles = File(root, "dev/tgfeed/ui/components/OverlayStyles.kt").readText()
        listOf(
            "OverlayTextShadow",
            "withOverlayShadow",
            "OverlayChipBg",
            "OverlayInfoChip",
            "OverlayInfoChipBg",
            "WatchedDimLayer",
        ).forEach { token ->
            assertTrue("OverlayStyles.kt 应保有 token：$token", token in overlayStyles)
        }
    }
}
