package dev.tgfeed.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「关于」区/页红线守护(SettingsIaPolicyTest 同款范式:源码文本机检,
 * 本测试内嵌扫描器属仓内既定范式,不引共享测试工具件)。
 *
 * 守护面(关于页=公开件,后续滚进出开源仓库,口径必须对外自洽):
 *
 * 1. **入口红线**:设置页「关于」卡落在底部惯例区(屏蔽的频道之后、
 *    危险区之前——危险区恒为末卡是 SettingsIaPolicyTest 红线,本断言与之
 *    互锁);版本行经 onOpenAbout 直达关于页;
 * 2. **版本实读红线**:版本号(versionName/versionCode)与构建变体
 *    一律实读 BuildConfig,UI 源码零硬编码版本串;
 * 3. **链接单源红线**:仓库 URL 字面量全主源码恰一处(AboutLinks.REPO_URL),
 *    发布页必须由它派生,不得出现第二处字面量;
 * 4. **检查更新诚实红线**:判词(无服务器/零后端/不做应用内版本比较)
 *    进 KDoc;跳转一律系统浏览器外开(ACTION_VIEW);
 * 5. **免责口径红线**:三行要点逐字沿 README 口径;
 * 6. **许可清单红线**:GPL-3.0/Boost/CC-BY 3.0 与关键第三方组件逐个在场,
 *    清单维护口径(增删依赖须同步)在 KDoc;
 * 7. **零网络红线**:关于两件禁网关/HTTP 客户端引用;
 * 8. **公开件口径红线**:两 UI 新件零批次号零内部字样;
 * 9. **路由红线**:关于分支先于设置分支(覆盖页先于宿主的不变式),
 *    容器开合与回调接线在场。
 */
class AboutPolicyTest {

    /** 定位主源码根:Gradle 单测工作目录是 app/ 或仓库根(与 SettingsIaPolicyTest 同款) */
    private fun mainSourceRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            val candidate = File(dir, "src/main/java")
            if (candidate.isDirectory) return candidate
            val nested = File(dir, "app/src/main/java")
            if (nested.isDirectory) return nested
            dir = dir.parentFile ?: return@repeat
        }
        error("找不到主源码根(user.dir=${System.getProperty("user.dir")})")
    }

    private fun source(relative: String): File {
        val f = File(mainSourceRoot(), relative)
        assertTrue("源码应存在(改名/移动请同步本测试):$relative", f.isFile)
        return f
    }

    private fun settingsSource() = source("dev/tgfeed/ui/SettingsScreen.kt").readText()
    private fun aboutSource() = source("dev/tgfeed/ui/AboutScreen.kt").readText()
    private fun licensesSource() = source("dev/tgfeed/ui/AboutLicenses.kt").readText()
    private fun appSource() = source("dev/tgfeed/App.kt").readText()
    private fun mainActivitySource() = source("dev/tgfeed/MainActivity.kt").readText()

    /** 主源码全部 .kt 文件(链接单源全量扫描用) */
    private fun allMainKtFiles(): List<File> =
        mainSourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** 卡片调用位(带引号形态——`SettingsCard("` 只匹配调用,函数定义无引号不误伤) */
    private fun cardCallSites(raw: String): List<Pair<String, Int>> {
        val re = Regex("SettingsCard\\(\"([^\"]+)\"")
        return re.findAll(raw).map { m -> m.groupValues[1] to m.range.first }
            .sortedBy { it.second }.toList()
    }

    // ---------------- 红线 1:入口落位与直达回调 ----------------

    @Test
    fun `关于卡落底部惯例区_屏蔽频道之后危险区之前_版本行直达`() {
        val raw = settingsSource()
        val cards = cardCallSites(raw)
        val about = cards.firstOrNull { it.first == "关于" }
        val blocked = cards.firstOrNull { it.first == "屏蔽的频道" }
        val danger = cards.firstOrNull { it.first == "危险区" }
        assertTrue("设置页应有「关于」卡(删卡请同步本测试)", about != null)
        assertTrue("「屏蔽的频道」卡应在(改名请同步本测试)", blocked != null)
        assertTrue("「危险区」卡应在(改名请同步本测试)", danger != null)
        assertTrue(
            "「关于」卡应落在底部惯例区的「屏蔽的频道」之后——实际序=${cards.map { it.first }}",
            about!!.second > blocked!!.second,
        )
        assertTrue(
            "「关于」卡必须在「危险区」之前(危险区恒为末卡是 SettingsIaPolicyTest 红线,互锁)",
            about.second < danger!!.second,
        )
        assertTrue(
            "设置页签名应有 onOpenAbout 参数(版本行直达关于页的唯一通道)",
            "onOpenAbout: () -> Unit = {}" in raw,
        )
    }

    // ---------------- 红线 2:版本与变体实读 ----------------

    @Test
    fun `版本号与构建变体全实读BuildConfig_零硬编码版本串`() {
        for (raw in listOf(settingsSource(), aboutSource())) {
            assertTrue("应实读 BuildConfig.VERSION_NAME", "BuildConfig.VERSION_NAME" in raw)
            assertTrue("应实读 BuildConfig.VERSION_CODE", "BuildConfig.VERSION_CODE" in raw)
            assertTrue("应实读 BuildConfig.BUILD_TYPE(变体如实 debug/release)", "BuildConfig.BUILD_TYPE" in raw)
            assertFalse("UI 源码不得硬编码版本串(改版本只动 build.gradle)", "\"0.2.0\"" in raw)
            assertFalse("UI 源码不得硬编码版本码", "versionCode = 2" in raw)
        }
    }

    // ---------------- 红线 3:链接单源 ----------------

    @Test
    fun `仓库链接单源_全主源码恰一处字面量_发布页由其派生`() {
        val literal = "https://github.com/A4hun/TGFeed"
        val hits = allMainKtFiles().filter { literal in it.readText() }
        assertEquals(
            "仓库 URL 字面量应恰出现在一个文件(AboutLinks.REPO_URL 单源)——实际=${hits.map { it.name }}。" +
                "新增引用一律 import AboutLinks,不得写字面量",
            listOf("AboutScreen.kt"),
            hits.map { it.name },
        )
        val about = aboutSource()
        assertEquals(
            "AboutScreen.kt 中仓库 URL 字面量应恰一处(注释里也不许抄)",
            1,
            Regex(Regex.escape(literal)).findAll(about).count(),
        )
        assertTrue(
            "发布页必须由仓库主页派生(单源:改地址只动 REPO_URL)",
            "const val RELEASES_URL = \"\$REPO_URL/releases\"" in about,
        )
    }

    // ---------------- 红线 4:检查更新诚实口径 ----------------

    @Test
    fun `检查更新判词进KDoc_零后端不做应用内探测_外开走系统浏览器`() {
        val about = aboutSource()
        // 判词(注释/KDoc)三要素:无服务器、零后端、不做应用内版本比较
        assertTrue("KDoc 应含「无服务器」判词", "无服务器" in about)
        assertTrue("KDoc 应含「零后端」判词", "零后端" in about)
        assertTrue("KDoc 应含「不做应用内版本比较」判词", "不做应用内版本比较" in about)
        // 外开:ACTION_VIEW(系统浏览器),发布页走派生常量
        assertTrue("外开浏览器应走 ACTION_VIEW", "ACTION_VIEW" in about)
        assertTrue("检查更新应指向 RELEASES_URL(派生单源)", "AboutLinks.RELEASES_URL" in about)
    }

    // ---------------- 红线 5:免责三行逐字沿 README ----------------

    @Test
    fun `免责三行沿README口径逐字在场`() {
        val about = aboutSource()
        assertTrue(
            "第一行应逐字在场:不提供、不聚合任何内容",
            "本项目仅供学习与技术交流,不提供、不聚合任何内容。" in about,
        )
        assertTrue(
            "第二行关键句应逐字在场:内容来自本人账号已加入的频道",
            "所有内容均来自使用者本人 Telegram 账号已加入的频道" in about,
        )
        assertTrue(
            "第三行应逐字在场:与 Telegram 官方无任何关联",
            "本项目与 Telegram 官方无任何关联,不是 Telegram 官方客户端。" in about,
        )
    }

    // ---------------- 红线 6:许可清单要件 ----------------

    @Test
    fun `许可清单要件齐_关键许可与组件逐个在场_维护口径进KDoc`() {
        val licenses = licensesSource()
        for (needle in listOf(
            // 本项目与三张清单
            "GPL-3.0", "RUNTIME_LICENSES", "DEMO_CONTENT_LICENSES", "TEST_ONLY_LICENSES",
            // 关键第三方组件(与 build.gradle.kts 依赖一一对应)
            "TDLib", "nextlib", "Media3", "ML Kit", "Compose", "coroutines", "kotlin-stdlib",
            // 许可名如实(含测试专用三件)
            "Apache-2.0", "Boost Software License 1.0", "Eclipse Public License 1.0", "JSON License",
            // 演示内容口径
            "CC-BY 3.0", "Big Buck Bunny", "Sintel", "Jellyfish", "Blender",
        )) {
            assertTrue("许可清单缺要件「$needle」(依赖/许可增删必须同步 AboutLicenses.kt)", needle in licenses)
        }
        assertTrue(
            "清单维护口径应进 KDoc:增删依赖必须同步本清单",
            "增删依赖必须同步本清单" in licenses,
        )
        assertTrue(
            "测试专用件应如实标注不进安装包",
            "不打进安装包" in licenses,
        )
    }

    // ---------------- 红线 7:关于页零网络 ----------------

    @Test
    fun `关于两件零网络_禁网关禁HTTP客户端`() {
        for (raw in listOf(aboutSource(), licensesSource())) {
            for (banned in listOf("TdGateway", "okhttp", "OkHttp", "HttpURLConnection", "URLConnection")) {
                assertFalse(
                    "关于页是纯静态展示(唯一出口=ACTION_VIEW 系统浏览器),不得引用 $banned",
                    banned in raw,
                )
            }
        }
    }

    // ---------------- 红线 8:公开件口径 ----------------

    @Test
    fun `两UI新件零批次号零内部字样_可滚存开源仓库`() {
        val batchRe = Regex("R\\d{2,}")
        for (raw in listOf(aboutSource(), licensesSource())) {
            val hit = batchRe.findAll(raw).map { it.value }.distinct().toList()
            assertTrue(
                "公开件不得出现批次号(注释与文案都算,滚存开源仓库即泄露)——发现:$hit",
                hit.isEmpty(),
            )
            for (banned in listOf("批次", "内部口径", "挂账")) {
                assertFalse(
                    "公开件不得出现内部字样「$banned」",
                    banned in raw,
                )
            }
        }
    }

    // ---------------- 红线 9:路由接线 ----------------

    @Test
    fun `路由接线_关于分支先于设置分支_容器开合与回调在场`() {
        val main = mainActivitySource()
        val aboutBranch = main.indexOf("showAbout ->")
        val settingsBranch = main.indexOf("showSettings ->")
        assertTrue("MainActivity 应有 showAbout 分支", aboutBranch >= 0)
        assertTrue("MainActivity 应有 showSettings 分支", settingsBranch >= 0)
        assertTrue(
            "关于分支必须排在设置分支之前(覆盖页先于宿主匹配的层级不变式,返回剥落回设置)",
            aboutBranch < settingsBranch,
        )
        assertTrue("应收集 showAbout 状态", "val showAbout by container.showAbout.collectAsState()" in main)
        assertTrue("overlayPageOpen 应登记 showAbout", "showSettings || showAbout ||" in main)
        assertTrue("设置页调用点应接 onOpenAbout 回调", "container.openAbout()" in main)

        val app = appSource()
        assertTrue("容器应有 showAbout 状态流", "val showAbout: StateFlow<Boolean> = _showAbout" in app)
        assertTrue("容器应有 openAbout()", "fun openAbout()" in app)
        assertTrue("容器应有 closeAbout()", "fun closeAbout()" in app)
    }
}
