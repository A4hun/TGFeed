package dev.tgfeed.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R361 开源收口批 wiring 源码锚（R358WiringSourceTest 同款 user.dir 源定位模式——
 * BuildConfig.DEBUG 编译期分岔在 JVM 单测恒为 debug 臂，release 行为无 JVM 直测面，
 * 以源锚锁结构面）。锚定四件（用户 2026-10-23 四裁决）：
 *  - **创建闸①**：PlazaService.ensurePlaza 创建分支 BuildConfig.DEBUG 硬闸在场——
 *    正式版广场只找不建（创建=作者 debug 构建专属），release 零创建路径；
 *  - **名单源②**：官方广场名 tgfeed_stl 唯一出处 AppPrefs.PLAZA_CHANNEL_OFFICIAL，
 *    plazaChannel 读取侧 release 恒锁官方名（旧值用户被锁死名覆盖），
 *    UI 两处引用同一常量（零第二份字面量）；
 *  - **诊断闸③**：设置页「诊断通道」卡 BuildConfig.DEBUG 整卡闸在场（R220/R300 两
 *    清理入口随卡同隐）+ 端点本体 R146 硬闸复核（DiagServer.start 零改仍守）；
 *  - **UI 同步闸④**：设置页广场卡与广场管理面板（同一 prefs 事实源）两处同闸——
 *    频道名输入框 DEBUG 闸 + 只读行「官方广场」+「我是广场创建者」开关 DEBUG 闸 +
 *    「加入失败」文案闸（release 去掉「请开启我是广场创建者」指路，改指官方广场
 *    暂不可用）；备用广场输入框两形态保留（逃生口语义不动）。
 *
 * R369 官方名换轨（用户 2026-10-24 拍板「就这个吧」）：官方广场名换为用户在 TG
 * 手动创建的真群 tgfeed_stl（R361 历史占位名只存档于 r361 笔记不改写）；本测试
 * 断言语义不变（名单源唯一出处+release 锁死），字面量随换轨同步沿用。
 *
 * R370 防回归补锚：tgfeed_scp（R361 至 R369 期历史官方占位名，R369 换轨 tgfeed_stl
 * 后退役）UI 层清零负锚——SettingsScreen 与 PlazaBoardScreen 源零 tgfeed_scp 字面量
 * 残留（tgfeed_plaza 负锚同款写法），旧官方名不得复活。
 */
class R361WiringSourceTest {

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

    private fun appPrefs(): String = src("dev/tgfeed/core/prefs/AppPrefs.kt")

    private fun plazaService(): String = src("dev/tgfeed/core/plaza/PlazaService.kt")

    private fun settingsScreen(): String = src("dev/tgfeed/ui/SettingsScreen.kt")

    private fun plazaBoardScreen(): String = src("dev/tgfeed/ui/PlazaBoardScreen.kt")

    private fun diagServer(): String = src("dev/tgfeed/core/diag/DiagServer.kt")

    // ==================== ① 创建闸：release 构建零创建路径 ====================

    @Test
    fun `创建闸_release构建零创建路径硬闸在场`() {
        val ps = plazaService()
        // 硬闸在场：NotFound 分支先过 BuildConfig.DEBUG，release 只记日志绝不创建
        assertTrue(
            "ensurePlaza 创建分支须有 BuildConfig.DEBUG 硬闸（release 只找不建）",
            Regex("if \\(!BuildConfig\\.DEBUG\\)").containsMatchIn(ps),
        )
        assertTrue(
            "硬闸日志须含「正式版只找不建」判词",
            ps.contains("正式版只找不建"),
        )
        // KDoc 判词在场：正式版广场创建=作者 debug 构建专属
        assertTrue(
            "ensurePlaza KDoc 须记「正式版广场创建=作者 debug 构建专属」判词",
            ps.contains("创建=作者 debug 构建专属"),
        )
        // 创建调用仍在（debug 臂保留），且被闸包裹——闸前于创建冷却段
        val gateIdx = ps.indexOf("if (!BuildConfig.DEBUG)")
        val createIdx = ps.indexOf("gateway.plazaCreateChat(channel)")
        assertTrue("创建调用 plazaCreateChat 应在场（debug 臂保留）", createIdx > 0)
        assertTrue("DEBUG 硬闸必须先于创建调用（release 零创建路径）", gateIdx in 1 until createIdx)
    }

    // ==================== ② 名单源：官方名 tgfeed_stl 唯一出处+release 锁死 ====================

    @Test
    fun `名单源_官方名常量在场且读取侧release锁死`() {
        val ap = appPrefs()
        assertTrue(
            "官方广场名常量 PLAZA_CHANNEL_OFFICIAL=tgfeed_stl 应在场（唯一出处）",
            ap.contains("const val PLAZA_CHANNEL_OFFICIAL = \"tgfeed_stl\""),
        )
        // plazaChannel 读取侧：debug 臂自定义回落常量、release 臂恒锁官方名
        val getter = Regex("var plazaChannel: String[\\s\\S]*?set\\(").find(ap)
        assertTrue("plazaChannel 属性块应在场", getter != null)
        val body = getter!!.groupValues[0]
        assertTrue("plazaChannel 读取侧须有 BuildConfig.DEBUG 分岔", body.contains("BuildConfig.DEBUG"))
        assertTrue("plazaChannel 读取侧 release 臂须恒返回官方名常量", body.contains("PLAZA_CHANNEL_OFFICIAL"))
        // 负锚：旧默认名字面量不再作为代码默认（KDoc 史注不受限）
        assertFalse(
            "旧默认名 tgfeed_plaza 不得再作代码默认/回落字面量",
            ap.contains("?: \"tgfeed_plaza\""),
        )
        // UI 两处引用同一常量（零第二份字面量）
        assertTrue(
            "设置页广场卡须引用 AppPrefs.PLAZA_CHANNEL_OFFICIAL（只读行/提示单源）",
            settingsScreen().contains("AppPrefs.PLAZA_CHANNEL_OFFICIAL"),
        )
        assertTrue(
            "广场管理面板须引用 AppPrefs.PLAZA_CHANNEL_OFFICIAL（只读行/提示单源）",
            plazaBoardScreen().contains("AppPrefs.PLAZA_CHANNEL_OFFICIAL"),
        )
    }

    @Test
    fun `名单源_旧默认名tgfeed_plaza在UI层清零`() {
        assertFalse(
            "SettingsScreen 不得再残留 tgfeed_plaza 字面量（提示/默认值一律改引常量）",
            settingsScreen().contains("tgfeed_plaza"),
        )
        assertFalse(
            "PlazaBoardScreen 不得再残留 tgfeed_plaza 字面量",
            plazaBoardScreen().contains("tgfeed_plaza"),
        )
    }

    // ==================== ②附 R370 补锚：历史名 tgfeed_scp UI 层清零（防复活负锚） ====================

    @Test
    fun `名单源_历史名tgfeed_scp在UI层清零_R370负锚`() {
        assertFalse(
            "SettingsScreen 不得残留历史名 tgfeed_scp 字面量（R369 换轨后旧官方名不得复活）",
            settingsScreen().contains("tgfeed_scp"),
        )
        assertFalse(
            "PlazaBoardScreen 不得残留历史名 tgfeed_scp 字面量",
            plazaBoardScreen().contains("tgfeed_scp"),
        )
    }

    // ==================== ③ 诊断闸：设置卡 DEBUG 整卡闸 + 端点 R146 硬闸复核 ====================

    @Test
    fun `诊断闸_诊断卡DEBUG整卡闸在场且端点R146硬闸零改`() {
        val ss = settingsScreen()
        // 整卡闸：SettingsCard("诊断通道") 被 BuildConfig.DEBUG 直接包裹
        assertTrue(
            "诊断通道卡须被 BuildConfig.DEBUG 整卡闸包裹（release 隐藏，R220/R300 两清理入口随卡同隐）",
            Regex("if \\(BuildConfig\\.DEBUG\\) \\{\\s*\\n\\s*SettingsCard\\(\"诊断通道\"\\)").containsMatchIn(ss),
        )
        // 端点本体 R146 复核（零改）：start() 的 BuildConfig.DEBUG 硬闸仍在场
        assertTrue(
            "DiagServer.start 的 R146 BuildConfig.DEBUG 硬闸须在场（release 零端口零监听）",
            diagServer().contains("if (!BuildConfig.DEBUG)"),
        )
    }

    // ==================== ④ UI 同步闸：两处同闸+文案闸+备用广场保留 ====================

    @Test
    fun `UI同步闸_设置页与管理面板两处同闸`() {
        val ss = settingsScreen()
        val pb = plazaBoardScreen()
        // 创建者开关两处同闸：DEBUG 且未建成才显示
        val ownerGate = "if (BuildConfig.DEBUG && !prefs.plazaCreated)"
        assertTrue("设置页「我是广场创建者」开关须有 DEBUG 闸", ss.contains(ownerGate))
        assertTrue("管理面板「我是广场创建者」开关须有 DEBUG 闸", pb.contains(ownerGate))
        // 只读行两处同闸：release 展示官方广场行
        val readonlyRow = "官方广场 @\${AppPrefs.PLAZA_CHANNEL_OFFICIAL}"
        assertTrue("设置页须有「官方广场」只读行（release 臂）", ss.contains(readonlyRow))
        assertTrue("管理面板须有「官方广场」只读行（release 臂）", pb.contains(readonlyRow))
        // 备用广场输入框保留（逃生口，release 也在）
        assertTrue(
            "管理面板备用广场输入框须保留（主广场被炸逃生口，只找不建语义不变）",
            pb.contains("备用广场用户名（可空）"),
        )
    }

    @Test
    fun `文案闸_release加入失败改指官方广场暂不可用`() {
        val ss = settingsScreen()
        val pb = plazaBoardScreen()
        // release 文案在场（两处）：去掉「请开启我是广场创建者」指路，改指暂不可用
        assertTrue("设置页加入失败文案闸在场", ss.contains("官方广场可能暂不可用，请稍后再试"))
        assertTrue("管理面板加入失败文案闸在场", pb.contains("官方广场可能暂不可用，请稍后再试"))
        // debug 臂旧指路文案仍在场（两处保留作者通道指引）
        assertTrue("设置页 debug 臂保留创建者指路", ss.contains("请开启下方「我是广场创建者」后重试"))
        assertTrue("管理面板 debug 臂保留创建者指路", pb.contains("请开启下方「我是广场创建者」后重试"))
    }
}
