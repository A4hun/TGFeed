package dev.tgfeed.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R340 行星光照回归 wiring 源码锚（R343WiringSourceTest 同款 user.dir 源定位
 * 模式——渲染器无 JVM 直测面（Android/OpenGL 依赖），接线以源锚锁死；判定
 * 行为半体由 EclipseFxTest（core 纯件）承重）：
 *
 *  - **A1 GL 渲染侧前置判定闸**（R340 主刀口）：buildPlanetSystem 凌星判定必须
 *    消费 [dev.tgfeed.core.tastemap.EclipseFx.isTransitingFront] 且携带行星
 *    proj[2]+恒星深度 starDepthR340（纯屏距旧判定=黑圈回归病根——不得复发）；
 *  - **A2 GL 预扫食深前置闸**：scanTransitDepthR320 消费 transitDepthFront01
 *    （恒星微暗只吃真前遮挡——掩星行星不遮恒星）；
 *  - **B1 行星光向锚**（任务书嫌疑③的负证固化）：PLANET_FS uLightDir+
 *    夜面底光 PLANET_NIGHT_FLOOR 在场（R309 兜底不灭）+ buildPlanetBody 光向
 *    =恒星−行星（行星→恒星——受光面朝恒星）；
 *  - **B2 剪影档窄化锚**：凌星剪影绘制（pr×0.08 暗球+缘光环）原样在场（真
 *    凌星=金星凌日观感保真——R320 特性回归本义，前置门只挡误触发不删特性）；
 *  - **C1 R321 潮汐槽清零锚**（任务书嫌疑②的负证固化）：addSphere/addHorizon
 *    装填即写恒等值（slots 22..26——sphereBuf 帧间复用残留形变病防复发）。
 */
class R340WiringSourceTest {

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

    private fun source(relPath: String): String {
        val file = File(mainSourceRoot, relPath)
        assertTrue("源文件必须存在：$relPath", file.isFile)
        return file.readText()
    }

    private fun gl(): String = source("dev/tgfeed/ui/gl/GlFrameBuilder.kt")

    private fun shaders(): String = source("dev/tgfeed/ui/gl/GlShaders.kt")

    private fun eclipse(): String = source("dev/tgfeed/core/tastemap/EclipseFx.kt")

    // ==================== A1 GL 渲染侧前置判定（主刀口） ====================

    @Test
    fun `A1-渲染侧凌星判定必须前置深度分量`() {
        val g = gl()
        // 主刀口：isTransitingFront 消费 + 行星 proj[2]/恒星 starDepthR340 双深度入参
        assertTrue(
            "buildPlanetSystem 凌星判定必须消费 isTransitingFront（R340 前置深度门）",
            Regex("EclipseFx\\.isTransitingFront\\(\\s*rP,\\s*starPx,\\s*hypot\\(proj\\[0\\] - starSx, proj\\[1\\] - starSy\\),\\s*proj\\[2\\],\\s*starDepthR340,?\\s*\\)").containsMatchIn(g),
        )
        // 恒星深度在星投影处先存（proj[2] 行星投影覆写前——原料单源）
        assertTrue(
            "恒星相机系深度必须在星投影处先存（starDepthR340=proj[2]）",
            Regex("val starDepthR340 = if \\(starVis\\) proj\\[2\\] else Float\\.NaN").containsMatchIn(g),
        )
        // 病根复发闸：渲染侧不得回退纯屏距旧判定（黑圈回归形态）
        assertFalse(
            "渲染侧不得回退纯屏距 isTransiting（黑圈病根复发）",
            Regex("EclipseFx\\.isTransiting\\(rP,\\s*starPx,\\s*hypot\\(proj\\[0\\] - starSx, proj\\[1\\] - starSy\\)\\)").containsMatchIn(g),
        )
    }

    // ==================== A2 GL 预扫食深前置闸 ====================

    @Test
    fun `A2-预扫食深必须前置深度分量`() {
        val g = gl()
        assertTrue(
            "scanTransitDepthR320 食深必须消费 transitDepthFront01（掩星行星不遮恒星）",
            Regex("EclipseFx\\.transitDepthFront01\\(rP,\\s*starPx,\\s*dTr,\\s*proj\\[2\\],\\s*starDepthR340\\)").containsMatchIn(g),
        )
        // 预扫恒星深度原料在场（proj 覆写前先存）
        assertTrue(
            "预扫恒星深度必须先存（starDepthR340=proj[2]）",
            Regex("val starDepthR340 = proj\\[2\\]").containsMatchIn(g),
        )
    }

    // ==================== B1 行星光向+夜面底光锚（嫌疑③负证） ====================

    @Test
    fun `B1-行星光向朝恒星与夜面底光在场`() {
        val s = shaders()
        // PLANET_FS：真 3D 平行光 uniform + ndl 消费（受光面朝恒星=晨昏线）
        assertTrue(
            "PLANET_FS 必须有 uLightDir（行星→恒星平行光）",
            Regex("uniform vec3 uLightDir;").containsMatchIn(s),
        )
        assertTrue(
            "PLANET_FS 光照必须消费 dot(n, uLightDir)（真光向）",
            Regex("float ndl = dot\\(n, uLightDir\\);").containsMatchIn(s),
        )
        // R309 夜面冷蓝底光兜底在场（全黑回归的反证——夜面也有可发现性底光）
        assertTrue(
            "夜面底光 PLANET_NIGHT_FLOOR 必须在场（R309 兜底不灭）",
            Regex("nightAmb = alb \\* \\$\\{TasteTagPlanets\\.PLANET_NIGHT_FLOOR\\}").containsMatchIn(s),
        )
        // buildPlanetBody：光向=恒星−行星（lx = scx - wx——受光面恒朝恒星）
        val g = gl()
        assertTrue(
            "buildPlanetBody 光向必须=恒星−行星（scx - wx 归一）",
            Regex("var lx = scx - wx").containsMatchIn(g),
        )
    }

    // ==================== B2 剪影档窄化锚（真凌星特性保真） ====================

    @Test
    fun `B2-剪影档绘制原样在场`() {
        val g = gl()
        // 凌星剪影绘制（暗球 base×0.08+缘光环）原样——前置门只挡误触发不删特性
        assertTrue(
            "凌星剪影暗球分支必须在场（真凌星=金星凌日观感）",
            Regex("pr \\* 0\\.08f, pg \\* 0\\.08f, pb \\* 0\\.10f").containsMatchIn(g),
        )
        // 缘光环（大气折光亮环）原样
        assertTrue(
            "凌星缘光环必须在场（addRingQuad@rP）",
            Regex("addRingQuad\\(psx, psy, rP, 0xFFE8F0FF\\.toInt\\(\\), appear \\* 0\\.55f\\)").containsMatchIn(g),
        )
    }

    // ==================== C1 R321 潮汐槽清零锚（嫌疑②负证） ====================

    @Test
    fun `C1-sphereBuf潮汐槽恒等清零在场`() {
        val g = gl()
        // addSphere：slots 22..26 恒等清零（帧间复用残留形变病——R321 纪律）
        assertTrue(
            "addSphere 潮汐槽必须恒等清零（slots 22..26——sphereBuf 帧间复用残留防线）",
            Regex("sphereBuf\\[i \\+ 22\\] = 0f[\\s\\S]{0,200}?(?:sphereBuf\\[i \\+ 2[3-6]\\] = 0f\\s*){4}").containsMatchIn(g),
        )
        // addHorizon：同族清零纪律
        val horizonSection = g.substringAfter("private fun addHorizon(")
        assertTrue(
            "addHorizon 潮汐槽清零必须在场（同族防线）",
            Regex("潮汐槽清零").containsMatchIn(horizonSection.substringBefore("private fun")),
        )
    }

    // ==================== 附：core 判定件契约（双函数同域） ====================

    @Test
    fun `附-core前置判定件双口径同域`() {
        val e = eclipse()
        // isTransitingFront 委托旧 isTransiting + 深度门（单源——不重写屏距域）
        assertTrue(
            "isTransitingFront 必须委托旧 isTransiting（屏距域单源不重写）",
            Regex("fun isTransitingFront\\([\\s\\S]*?= isTransiting\\(planetRpx, starRpx, screenDist\\) && planetDepth < starDepth").containsMatchIn(e),
        )
        // transitDepthFront01 同委托（食深账单源）
        assertTrue(
            "transitDepthFront01 必须条件委托 transitDepth01（食深单源）",
            Regex("fun transitDepthFront01\\([\\s\\S]*?= if \\(planetDepth < starDepth\\) transitDepth01\\(planetRpx, starRpx, screenDist\\) else 0f").containsMatchIn(e),
        )
    }
}
