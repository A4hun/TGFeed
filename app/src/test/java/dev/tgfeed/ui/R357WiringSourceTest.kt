package dev.tgfeed.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R357 移动 GPU fp16 纹理塌缩根治 wiring 源码锚（R355WiringSourceTest 同款
 * user.dir 源定位模式——GL 渲染管线无 JVM 直测面，以源锚锁结构面）：
 *  - **精度锚①噪声 FS 全 highp**：SPHERE/SPHERE_I/PLANET/ROCK/RING/STRIP/NEBULA/
 *    CORONA——mediump 在移动 GPU 上=真 fp16（上限 65504），hash 中间积
 *    p.x·p.y·p.z·(分量之和) 最大约 25 万溢出致噪声塌成常数（小米光滑球实锤——
 *    米粒/黑子/火苗/行星条带/环颗粒/彗尾侵蚀/星云密度场/日珥拱全塌；桌面 GL
 *    mediump 按 fp32 跑不塌=「模拟器 PASS 不等于小米 PASS」精度版）；
 *  - **守档锚②非噪声 FS 保 mediump**：POINT/LINE/QUAD/SOFT_POINT/KEPLER_LINE/
 *    BLUR/COMPOSITE——性能账（无噪声件不吃 highp 税）；
 *  - **类闸③噪声消费 FS 一律 highp**：块内文本含噪声调用（vnoise/fbm/hash 族）
 *    或噪声库引用（NOISE_3D/SPHERE_SHADE_BODY）的 FS 程序一律 highp——类病
 *    防复发闸（AGENT-DISCIPLINE 一节「发现一个问题解决一类问题」——新增噪声
 *    着色器忘了 highp 即红）。
 */
class R357WiringSourceTest {

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

    private fun glShaders(): String = src("dev/tgfeed/ui/gl/GlShaders.kt")

    private fun fsHeaderPrecision(gs: String, name: String): String? {
        val m = Regex("const val ${name} = \"\"\"#version 300 es\\s+precision (\\w+) float;").find(gs)
        return m?.groupValues?.get(1)
    }

    // ==================== ① 精度锚：噪声 FS 全 highp（fp16 塌纹理病根绝迹） ====================

    @Test
    fun `精度-噪声着色族全highp（fp16塌纹理病根绝迹）`() {
        val gs = glShaders()
        val noisy = listOf(
            "SPHERE_FS",   // 全模球——米粒组织/黑子/火苗/胞界暗脉
            "SPHERE_I_FS", // 低模球 instancing——同体共源
            "PLANET_FS",   // 气态条带/风暴斑/大陆/陨击坑
            "ROCK_FS",     // 凿面低频色斑
            "RING_FS",     // 环带颗粒纹（一百六十倍高频采样）
            "STRIP_FS",    // 彗尾侵蚀
            "NEBULA_FS",   // 星云域扭曲密度场
            "CORONA_FS",   // 日冕冕流/日珥拱（sin 大数角同族退化）
        )
        for (name in noisy) {
            assertTrue(
                "噪声 FS ${name} 必须 precision highp float（mediump=fp16 塌噪声病根），实读=${fsHeaderPrecision(gs, name)}",
                fsHeaderPrecision(gs, name) == "highp",
            )
        }
    }

    // ==================== ② 守档锚：非噪声 FS 保 mediump（性能账） ====================

    @Test
    fun `守档-非噪声件保mediump（无噪声件不吃highp税）`() {
        val gs = glShaders()
        val quiet = listOf(
            "POINT_FS", "LINE_FS", "QUAD_FS", "SOFT_POINT_FS",
            "KEPLER_LINE_FS", "BLUR_FS", "COMPOSITE_FS",
        )
        for (name in quiet) {
            assertTrue(
                "非噪声 FS ${name} 应保 mediump（性能守档），实读=${fsHeaderPrecision(gs, name)}",
                fsHeaderPrecision(gs, name) == "mediump",
            )
        }
    }

    // ==================== ③ 类闸：噪声消费 FS 一律 highp（类病防复发） ====================

    @Test
    fun `类闸-噪声消费着色器一律highp（新增噪声件忘抬档即红）`() {
        val gs = glShaders()
        val fsBlocks = Regex("const val (\\w+_FS) = \"\"\"#version 300 es\\s+precision (\\w+) float;([\\s\\S]*?)\"\"\"")
        var checked = 0
        for (m in fsBlocks.findAll(gs)) {
            val name = m.groupValues[1]
            val precision = m.groupValues[2]
            val body = m.groupValues[3]
            val noisy = Regex("vnoise|fbm|hash\\d|NOISE_3D|SPHERE_SHADE_BODY").containsMatchIn(body)
            if (noisy) {
                checked++
                assertTrue(
                    "噪声消费 FS ${name} 必须 highp（fp16 塌噪声类病闸），实读=${precision}",
                    precision == "highp",
                )
            }
        }
        assertTrue("类闸应至少覆盖八件噪声 FS（实检=${checked}）", checked >= 8)
    }
}
