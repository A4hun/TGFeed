package dev.tgfeed.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R319 宇宙底幕接线源码锚（R317bWiringSourceTest 同款 user.dir 源定位模式——
 * 渲染层无 JVM 直测面的结构件以源锚把守；行为半体由 CmbBackdropFxTest
 * （core 纯件）+AstroFactsTest（第七问门）承重）：
 *  - **开关门**：关=纯黑零产出——GL buildCmbR319/buildOlbersR319 两构建函数
 *    **首行 if(!cmbOn) return**（R345 后 GL 唯一引擎——Canvas 侧同名件已随渲染器
 *    整类退役；回填成「先画再判」=本锚即红）+帧字段默认值锚（旧构造点零变化）；
 *  - **帧预算源锚**：绘制函数体零 Bitmap.createBitmap/零 noise01At——烘焙只在
 *    cmbBitmapOf 缓存件内（LRU ≤2 帽锚——帧路径零分配零重算）；
 *  - **主方法零接线**（R315 工程约束）：主 composable 本体零 cmb 标记（接线全在
 *    舞台件/画布层/顶层函数——回填主方法=预算闸被顶的入口）；
 *  - **装配零接触**：assembleTasteGalaxy/runGalaxyLoadR254 函数体零 CmbBackdrop
 *    引用（差集不走装配期——r319 设计定案③裁决，余量 42 units 加块必爆）；
 *  - **接线链在场**：buildOlbersZoneR319 顶层定义+舞台 effect 调用+预热口+帧
 *    三字段装配+信息卡半径比穿透+设置行+AppPrefs 键+词条在场。
 */
class R319WiringSourceTest {

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

    /** 函数体提取（签名行→先找 `) {` 体开行→brace 配对；R317b mainFunctionBody
     *  同款两段式——多行参数表若直接配对会在参数行深度恒 0 处提前收口） */
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

    // ==================== 开关门（关=纯黑零产出） ====================
    // R345：Canvas 侧 drawCmbBackdropR319/drawOlbersR319 两绘制函数随 Canvas 渲染器
    // 整类退役（原「Canvas 两绘制函数首行归零」测试随之退役——GL 侧同门锚在下保活）

    @Test
    fun `开关门-GL两构建函数首行归零`() {
        val t = src("dev/tgfeed/ui/gl/GlFrameBuilder.kt")
        for (fn in listOf("buildCmbR319", "buildOlbersR319")) {
            val body = funBody(t, Regex("private fun $fn\\("))
            val gateIdx = body.indexOf("if (!f.cmbOn) return")
            assertTrue("$fn 缺开关门（关=纯黑铁令被破？）", gateIdx >= 0)
            val firstEmit = body.indexOf("addQuad(").let { if (it < 0) body.length else it }
                .coerceAtMost(body.indexOf("addNebula(").let { if (it < 0) body.length else it })
                .coerceAtMost(body.indexOf("addPoint(").let { if (it < 0) body.length else it })
            assertTrue("$fn 开关门不在发射调用之前", gateIdx < firstEmit)
        }
    }

    @Test
    fun `纹理动态上传-种子变更窗才重传`() {
        val t = src("dev/tgfeed/ui/gl/TasteGlRenderer.kt")
        // 帧字段 cmbSeedFp 变更窗才重烘重传（帧内只查种子相等=零成本）
        assertTrue(
            "CMB 纹理动态上传段丢失（种子变更窗重传铁令）",
            Regex("if \\(f\\.cmbOn\\)[\\s\\S]*if \\(seed != cmbTexSeedR319\\)").containsMatchIn(t),
        )
        assertTrue(
            "旧纹理释放锚丢失（换种子必须删旧纹理防泄漏）",
            Regex("glDeleteTextures").containsMatchIn(t),
        )
    }

    @Test
    fun `帧契约-三字段默认值锚（旧构造点零变化）`() {
        val t = src("dev/tgfeed/ui/TasteFlightRenderer.kt")
        assertTrue(
            "cmbOn 默认值锚丢失",
            Regex("val cmbOn: Boolean = false").containsMatchIn(t),
        )
        assertTrue(
            "cmbSeedFp 默认值锚丢失",
            Regex("val cmbSeedFp: Long = 0L").containsMatchIn(t),
        )
        assertTrue(
            "cmbZone 默认值锚丢失",
            Regex("val cmbZone: dev\\.tgfeed\\.core\\.tastemap\\.CmbBackdropFx\\.OlbersZone\\? = null").containsMatchIn(t),
        )
    }

    // ==================== 帧预算源锚（帧路径零分配零重算） ====================
    // R345：Canvas 侧 cmbBitmapOf 缓存件/LRU 帽随 Canvas 渲染器整类退役（烘焙单源=
    // GlTextureBaker.bakeCmbCached @Synchronized 记忆化——LRU 口径由 GL 侧持有；
    // 原 Canvas 帧预算测试随之退役）

    // ==================== 主方法零接线 + 装配零接触（R315 工程约束） ====================

    @Test
    fun `主方法零接线-宇宙底幕标记不进主composable本体`() {
        val t = src("dev/tgfeed/ui/TasteMapScreen.kt")
        val body = funBody(t, Regex("^fun TasteMapScreen\\("))
        // 主方法本体零 cmb/olbers 标记（接线全在舞台件/画布层/顶层函数——
        // 回填主方法=寄存器压力回顶 ART 校验线，本锚即红）
        assertTrue(
            "主 composable 本体出现底幕接线标记（主方法零接线铁令被破——迁舞台件/顶层函数！）",
            !Regex("cmb|CmbBackdrop|[Oo]lbers").containsMatchIn(body),
        )
    }

    @Test
    fun `装配零接触-差集不走装配期`() {
        val t = src("dev/tgfeed/ui/TasteMapScreen.kt")
        for (fn in listOf("assembleTasteGalaxy", "runGalaxyLoadR254")) {
            val body = funBody(t, Regex("private (suspend )?fun $fn\\("))
            assertTrue(
                "$fn 函数体出现 CmbBackdrop 引用（差集不走装配期——r319 设计定案③裁决被破，预算余量 42 units 必爆）",
                !Regex("CmbBackdrop|cmbZone|buildOlbers").containsMatchIn(body),
            )
        }
        // 差集收口=顶层独立函数（舞台件 LaunchedEffect 消费）
        assertTrue(
            "buildOlbersZoneR319 顶层定义丢失（独立函数铁令）",
            Regex("private fun buildOlbersZoneR319\\(").containsMatchIn(t),
        )
        // 星表空=null zone（缺据诚实缺省）
        assertTrue(
            "星表空缺省锚丢失",
            Regex("if \\(stars\\.isEmpty\\(\\)\\) return null").containsMatchIn(t),
        )
    }

    // ==================== 接线链在场（穿透链全环） ====================

    @Test
    fun `接线链-舞台effect与帧字段与信息卡穿透`() {
        val t = src("dev/tgfeed/ui/TasteMapScreen.kt")
        // 舞台件装载 effect：预热+差集装配（IO 协程进页一次）——R345 Canvas 退役后
        // 预热直调 GlTextureBaker 烘焙单源（@Synchronized——GL 线程上传帧内缓存命中）
        assertTrue(
            "舞台 effect 预热调用丢失（R345 平移口径=GlTextureBaker.bakeCmbCached 直调）",
            Regex("GlTextureBaker\\.bakeCmbCached\\(seedFp\\)").containsMatchIn(t),
        )
        assertTrue(
            "舞台 effect 差集装配调用丢失",
            Regex("buildOlbersZoneR319\\(vm, stars\\)").containsMatchIn(t),
        )
        assertTrue(
            "差集未走 IO 协程（Dispatchers.IO）",
            Regex("withContext\\(Dispatchers\\.IO\\)[\\s\\S]{0,400}buildOlbersZoneR319").containsMatchIn(t),
        )
        // 帧三字段装配（两引擎同一组装段）
        for (anchor in listOf("cmbOn = cmbOn", "cmbSeedFp = cmbSeedFp", "cmbZone = cmbZone")) {
            assertTrue("帧字段装配锚丢失：$anchor", t.contains(anchor))
        }
        // 信息卡穿透链：rObs 直供+whyChain 半径比
        assertTrue(
            "信息卡视界半径直供锚丢失",
            Regex("cmbHorizonR = cmbZoneR319\\.value\\?\\.rObs \\?: 0f").containsMatchIn(t),
        )
        assertTrue(
            "whyChain 半径比计算锚丢失",
            Regex("horizonEdge01 = if \\(cmbHorizonR > 0f\\)").containsMatchIn(t),
        )
    }

    @Test
    fun `接线链-设置行与偏好键与词条在场`() {
        // AppPrefs 键：taste_cmb 默认开（「要纯黑就全黑」）
        val prefs = src("dev/tgfeed/core/prefs/AppPrefs.kt")
        assertTrue(
            "taste_cmb 键锚丢失（默认开）",
            Regex("getBoolean\\(\"taste_cmb\", true\\)").containsMatchIn(prefs),
        )
        // R356 入口内迁：设置行退役——唯一写入口=星图内图标快捷组（迁移语义
        // 零变化：同键同默认同写法，入口从设置页搬进星图）
        val tms = src("dev/tgfeed/ui/TasteMapScreen.kt")
        assertTrue(
            "星图内写锚丢失（R356 内迁后唯一入口）",
            Regex("stagePrefsR356\\.tasteCmb = next").containsMatchIn(tms),
        )
        val settings = src("dev/tgfeed/ui/SettingsScreen.kt")
        assertTrue(
            "设置页应零星图行（R356 退役——口味星图开关已内迁星图）",
            !Regex("tasteCmb").containsMatchIn(settings),
        )
        // 词条：cmb/olbers 在表（kind=null 语义天体——底幕不是天体无枚举对位）
        val facts = src("dev/tgfeed/core/tastemap/AstroFacts.kt")
        assertTrue(
            "cmb 词条丢失",
            Regex("key = \"cmb\"").containsMatchIn(facts),
        )
        assertTrue(
            "olbers 词条丢失",
            Regex("key = \"olbers\"").containsMatchIn(facts),
        )
        // related 互链在场（词条浏览器浮现路径）
        assertTrue(
            "cmb↔olbers 互链丢失",
            Regex("key = \"cmb\"[\\s\\S]{0,600}related = listOf\\(\"olbers\"").containsMatchIn(facts) &&
                Regex("key = \"olbers\"[\\s\\S]{0,600}related = listOf\\(\"cmb\"").containsMatchIn(facts),
        )
        // 第七问门（≥0.85 观测带内才出——缺据/内部星整行不出）
        assertTrue(
            "第七问门锚丢失（0.85 观测带）",
            Regex("if \\(i\\.horizonEdge01 >= 0\\.85f\\)").containsMatchIn(facts),
        )
        assertEquals(
            "cmb/olbers 词条必须是 kind=null（语义天体——无枚举对位）",
            null,
            dev.tgfeed.core.tastemap.AstroFacts.topic("cmb")?.kind,
        )
    }
}
