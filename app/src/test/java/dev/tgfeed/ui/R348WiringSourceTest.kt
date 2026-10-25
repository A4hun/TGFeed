package dev.tgfeed.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R348 C 级清剿小刀批 wiring 源码锚（R343WiringSourceTest 同款 user.dir 源定位模式
 * ——渲染行为无 JVM 直测面（GL 管线），结构件以源锚锁死；行为语义半体由
 * TasteShipFxTest/TasteAchievementsTest（core 纯件）承重）：
 *
 *  - **C1 单色星尘闸**（宪法二章 12 条——彩虹色相循环=幼儿园视觉母题）：
 *    GlFrameBuilder hueShift 色相旋转与 ShipStyle.RAINBOW_STORM 渲染分叉绝迹
 *    （单色桶收口）；粒子门 hasParticlesOf 保活（等阶=密度/长度承担——粒子语义
 *    不伤）；shipColorOf 星尘暖白 0xFFF6F1E2 在场；「彩虹」字样三文件绝迹
 *    （文案诚实——视觉改了文案不许说谎）；
 *  - **C2 灯塔扫掠降速闸**（宪法二章 14 条）：BEACON_SWEEP_DEG_S 常量落在裁决域
 *    8~12°/s 内+消费式在场+66°/s 绝迹；
 *  - **C3 虫洞条纹退役闸**（宪法二章 15 条）：24 径向条纹角步长（0.2617994f）
 *    绝迹；暗幕+双隧道环保活（氛围底不伤）；穿越真运动保留锚——camPathSpace
 *    前冲鼓包（加速度半边）+到达爆散（白闪瞬态）原样；
 *  - **C7 文案字面闸**（宪法三章范式四——太空无声）：「等一道最亮的闪光」在
 *    场+「巨响」绝迹。
 */
class R348WiringSourceTest {

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

    private fun ach(): String = source("dev/tgfeed/core/tastemap/TasteAchievements.kt")

    private fun starlight(): String = source("dev/tgfeed/core/tastemap/TasteStarlight.kt")

    private fun shipfx(): String = source("dev/tgfeed/core/tastemap/TasteShipFx.kt")

    private fun worm(): String = source("dev/tgfeed/core/tastemap/TasteWormhole.kt")

    private fun tms(): String = source("dev/tgfeed/ui/TasteMapScreen.kt")

    // ==================== C1 彩虹粒子风暴→单色星尘（宪法二章 12 条） ====================

    @Test
    fun `C1-彩虹色相旋转绝迹与单色星尘桶收口`() {
        val g = gl()
        // hue 随 t 旋转机制绝迹（色相桶旋转=幼儿园视觉母题）
        assertFalse(
            "hueShift 色相旋转必须从 GL 渲染器绝迹（R348 C1 彩虹退役）",
            Regex("val hueShift").containsMatchIn(g),
        )
        // 渲染端档位分叉绝迹：拖尾粒子单色收口（黄金/传奇同一循环同一色源）
        assertFalse(
            "RAINBOW_STORM 渲染分叉必须绝迹（单色星尘桶收口——等阶语义由密度/长度承担）",
            Regex("ShipStyle\\.RAINBOW_STORM").containsMatchIn(g),
        )
        assertTrue("GL C1 退役标记必须在场", Regex("R348 C1").containsMatchIn(g))
        // 粒子语义不伤：粒子门保活（黄金/传奇两档仍产粒子——密度承担等阶）
        assertTrue(
            "hasParticlesOf 粒子门必须保活（等阶=密度/长度档差原样）",
            Regex("TasteShipFx\\.hasParticlesOf\\(style\\)").containsMatchIn(g),
        )
        // 单色循环在场：cr/cg/cb（shipColorOf 单色源）直接喂粒子
        assertTrue(
            "拖尾粒子必须单色桶绘制（cr/cg/cb 同色源直喂）",
            Regex("addPoint\\(ppx, ppy, 2\\.6f, 0\\.72f, cr, cg, cb, baseAlpha \\* 235f / 255f\\)").containsMatchIn(g),
        )
        // 流场 streak 保活（速度尘=真流速矢量驱动——C1 豁免件）
        assertTrue(
            "流场 streak 必须保活（速度尘=真流速矢量驱动，RPG 皮肤族 C1 豁免件）",
            Regex("val streakMax = TasteShipFx\\.streakLenOf\\(style, speed01, minSide\\)").containsMatchIn(g),
        )
    }

    @Test
    fun `C1-星尘暖白色锚与彩虹文案绝迹`() {
        val a = ach()
        // 色源单点：传奇档色=星尘暖白（光的颜色由温度和消光决定，不由成就等级决定）
        assertTrue(
            "shipColorOf(RAINBOW_STORM) 必须为星尘暖白 0xFFF6F1E2（星色系白段/中性暖白）",
            Regex("RAINBOW_STORM -> 0xFFF6F1E2\\.toInt\\(\\)").containsMatchIn(a),
        )
        assertFalse(
            "退役文案本体「彩虹粒子风暴」必须从 TasteAchievements 绝迹（文案诚实红线）",
            Regex("彩虹粒子风暴").containsMatchIn(a),
        )
        assertFalse(
            "退役 Rank 注释句「彩虹色」必须从 TasteAchievements 绝迹",
            Regex("彩虹色").containsMatchIn(a),
        )
        assertTrue(
            "shipDescOf 传奇档必须是「传说级飞船 · 星尘风暴」（视觉改了文案不许说谎）",
            Regex("RAINBOW_STORM -> \"传说级飞船 · 星尘风暴\"").containsMatchIn(a),
        )
        assertFalse(
            "退役文案本体「彩虹粒子风暴」必须从 TasteStarlight 绝迹（Lv8 解锁文案随刀）",
            Regex("彩虹粒子风暴").containsMatchIn(starlight()),
        )
        assertFalse(
            "退役机制句「彩虹色相旋转」必须从 TasteShipFx 绝迹（KDoc 语义随刀）",
            Regex("彩虹色相旋转").containsMatchIn(shipfx()),
        )
        assertTrue(
            "TasteShipFx 必须有单色星尘语义标记",
            Regex("单色星尘").containsMatchIn(shipfx()),
        )
    }

    // ==================== C2 灯塔光柱降速（宪法二章 14 条） ====================

    @Test
    fun `C2-灯塔扫掠角速度降速域锚`() {
        val g = gl()
        // 常量落在用户裁决域 8~12°/s 内
        val m = Regex("BEACON_SWEEP_DEG_S = (\\d+(?:\\.\\d+)?)f").find(g)
        assertNotNull("BEACON_SWEEP_DEG_S 常量必须在场（命名单源）", m)
        val degPerSec = m!!.groupValues[1].toFloat()
        assertTrue(
            "灯塔扫掠角速度必须落在裁决域 8~12°/s（实测 $degPerSec°/s——探照灯语感退场）",
            degPerSec in 8f..12f,
        )
        assertEquals("降速刀取中值 10°/s（36s/圈缓慢仪式感扫掠）", 10f, degPerSec, 0f)
        // 消费式在场（扫掠仍走 sweep 参数——恒亮+呼吸案弃选，论证见 r348_notes §二）
        assertTrue(
            "灯塔 sweep 必须消费 BEACON_SWEEP_DEG_S 单源常量",
            Regex("\\(t \\* BEACON_SWEEP_DEG_S\\) % 360f").containsMatchIn(g),
        )
        // 66°/s 绝迹（探照灯语感的原值）
        assertFalse(
            "66°/s 扫掠必须绝迹（机场探照灯语感——宪法二章 14 条）",
            Regex("t \\* 66f").containsMatchIn(g),
        )
        assertTrue("GL C2 降速标记必须在场", Regex("R348 C2").containsMatchIn(g))
        // 在播门原样（在播标记语义保真——在播=哪里）
        assertTrue(
            "灯塔 isPlaying 门必须保活（在播标记语义）",
            Regex("if \\(isPlaying\\) \\{[\\s\\S]{0,200}BEACON_SWEEP_DEG_S").containsMatchIn(g),
        )
    }

    // ==================== C3 虫洞 24 静态径向条纹退役（宪法二章 15 条） ====================

    @Test
    fun `C3-虫洞径向条纹绝迹与氛围底保活`() {
        val g = gl()
        // 条纹机制绝迹：2π/24 角步长（i×0.2617994f=24 条纹签名）全库绝迹
        assertFalse(
            "24 条纹角步长（0.2617994f）必须从 GL 渲染器绝迹（R348 C3 退役）",
            Regex("0\\.2617994f").containsMatchIn(g),
        )
        assertFalse(
            "24 条纹原注释签名必须绝迹",
            Regex("24 径向条纹（确定性角度").containsMatchIn(g),
        )
        assertTrue("GL C3 退役标记必须在场", Regex("R348 C3").containsMatchIn(g))
        // 氛围底保活：暗幕+双隧道环（对比底/定径框架——非扩散环族）
        assertTrue(
            "虫洞暗幕必须保活（对比底）",
            Regex("TEX_WHITE, cx, cy, w / 2f, h / 2f").containsMatchIn(g),
        )
        assertTrue(
            "虫洞双隧道环必须保活（0.42 定径环）",
            Regex("addRingQuad\\(cx, cy, radius \\* 0\\.42f, WORM_COLOR").containsMatchIn(g),
        )
        assertTrue(
            "虫洞双隧道环必须保活（0.64 定径环）",
            Regex("addRingQuad\\(cx, cy, radius \\* 0\\.64f, WORM_COLOR").containsMatchIn(g),
        )
    }

    @Test
    fun `C3-穿越真运动保留锚（加速度+白闪瞬态）`() {
        val w = worm()
        // 加速度半边：相机前冲鼓包原样（真运动——条纹退役不伤穿越语义）
        assertTrue(
            "TUNNEL_BULGE 前冲鼓包必须保活（穿越加速度的数学半边）",
            Regex("const val TUNNEL_BULGE = 700f").containsMatchIn(w),
        )
        assertTrue(
            "camPathSpace 相机路径必须保活（1.5s transit 真前冲）",
            Regex("fun camPathSpace\\(").containsMatchIn(w),
        )
        // 白闪瞬态半边：到达爆散原样（TasteMapScreen 到达口）
        assertTrue(
            "虫洞到达爆散（白闪瞬态）必须保活",
            Regex("engine\\.burst\\(arriveProj\\[0\\]").containsMatchIn(tms()),
        )
    }

    // ==================== C7 文案字面（宪法三章范式四——太空无声） ====================

    @Test
    fun `C7-超新星指引文案字面锚`() {
        val t = tms()
        assertTrue(
            "超新星指引必须改「等一道最亮的闪光」（太空无声——光 arrives）",
            Regex("驻留星图等一道最亮的闪光").containsMatchIn(t),
        )
        assertFalse(
            "「等一声巨响」原句必须绝迹（拟声修辞退役——超新星的「响」是引力波/中微子不是声音）",
            Regex("等一声巨响").containsMatchIn(t),
        )
    }
}
