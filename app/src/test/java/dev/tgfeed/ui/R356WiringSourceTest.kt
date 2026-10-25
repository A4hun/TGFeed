package dev.tgfeed.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R356 口味星图 UI 收纳重设计接线源码锚（R319WiringSourceTest 同款 user.dir 源
 * 定位模式——UI 层无 JVM 直测面的结构件以源锚把守）：
 *  - **迁移语义零变化锚**：AppPrefs 五键（taste_cmb/taste_constellation/
 *    taste_link_lines/taste_emergent_layout/taste_link_perturb）键名/默认值/
 *    写法逐字原样（入口搬家≠语义搬家——本批红线）；
 *  - **入口在场锚**：星图内图标快捷组（TasteMapQuickTogglesR356——顶栏单触发
 *    钮+五件图标组+舞台态直供画布层）与启航径向菜单（TasteMapVoyageHubR356
 *    ——五件接线含 genesis 入口块与导出 busy 态）在场；vm 双同步
 *    （galaxyEmergentLayout/galaxyLinkPerturb）与设置页退役行同款；
 *  - **旧入口绝迹锚**：TasteMapTopRightTools/ExploreBar/ExportButtons/
 *    GenesisControls 四旧件绝迹（旧关态延时胶囊与右上星钟两入口退役=收纳
 *    换代，宪法「换方法不是调参数」）；SettingsScreen 零「口味星图：」行+
 *    邻居行在场防过删（关键词订阅/订阅更新通知/我的搜索群）；
 *  - **文案红线锚**：TasteQuickToggleR356/TasteVoyageActionR356 首参（图标名）
 *    逐个 ≤2 字（宪法「克制」：图标名两字上限/文案≤4字）。
 */
class R356WiringSourceTest {

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

    // ==================== 迁移语义零变化锚（AppPrefs 五键原样） ====================

    @Test
    fun `迁移语义零变化-五键键名默认值写法逐字原样`() {
        val prefs = src("dev/tgfeed/core/prefs/AppPrefs.kt")
        // (键名, 默认值) 五元组——与 R356 前逐字一致（入口内迁≠语义搬家）
        val contract = listOf(
            "taste_cmb" to "true",
            "taste_constellation" to "true",
            "taste_link_lines" to "false",
            "taste_emergent_layout" to "true",
            "taste_link_perturb" to "true",
        )
        for ((key, default) in contract) {
            assertTrue(
                "键 $key 读取口径丢失（getBoolean(\"$key\", $default)）",
                Regex("getBoolean\\(\"$key\", $default\\)").containsMatchIn(prefs),
            )
            assertTrue(
                "键 $key 写入口径丢失（putBoolean）",
                Regex("putBoolean\\(\"$key\", v\\)").containsMatchIn(prefs),
            )
        }
    }

    // ==================== 入口在场锚（星图内图标组+启航径向菜单） ====================

    @Test
    fun `入口在场-快捷组与启航菜单两新组件件`() {
        val quick = src("dev/tgfeed/ui/TasteMapQuickToggles.kt")
        assertTrue(
            "快捷组数据类丢失",
            Regex("internal data class TasteQuickToggleR356").containsMatchIn(quick),
        )
        assertTrue(
            "快捷组组件丢失（顶栏单触发钮+下拉图标组）",
            Regex("internal fun BoxScope\\.TasteMapQuickTogglesR356").containsMatchIn(quick),
        )
        val voyage = src("dev/tgfeed/ui/TasteMapVoyageMenu.kt")
        assertTrue(
            "启航菜单数据类丢失",
            Regex("internal data class TasteVoyageActionR356").containsMatchIn(voyage),
        )
        assertTrue(
            "启航径向菜单组件丢失",
            Regex("internal fun BoxScope\\.TasteMapVoyageHubR356").containsMatchIn(voyage),
        )
    }

    @Test
    fun `入口在场-舞台件五开关接线与vm双同步`() {
        val t = src("dev/tgfeed/ui/TasteMapScreen.kt")
        // 五开关接线（写键=迁移语义零变化；vm 同步=与设置页退役行逐句同款）
        val wiring = listOf(
            "stagePrefsR356.tasteConstellation = next" to "星座开关写键",
            "stagePrefsR356.tasteCmb = next" to "底幕开关写键",
            "stagePrefsR356.tasteLinkLines = next" to "连线开关写键",
            "stagePrefsR356.tasteEmergentLayout = next" to "涌现开关写键",
            "stagePrefsR356.tasteLinkPerturb = next" to "聚拢开关写键",
            "vm.galaxyEmergentLayout = next" to "涌现 vm 运行时同步",
            "vm.galaxyLinkPerturb = next" to "聚拢 vm 运行时同步",
        )
        for ((anchor, name) in wiring) {
            assertTrue("${name}丢失：${anchor}", t.contains(anchor))
        }
        // 两组件调用位
        assertTrue("快捷组调用丢失", Regex("TasteMapQuickTogglesR356\\(").containsMatchIn(t))
        assertTrue("启航菜单调用丢失", Regex("TasteMapVoyageHubR356\\(").containsMatchIn(t))
        // 舞台态直供画布层（底幕/连线即时翻转——键/默认/渲染门零变化）
        assertTrue("画布层底幕态直供丢失", t.contains("cmbOn = cmbOnR356,"))
        assertTrue("画布层连线态直供丢失", t.contains("linkLinesOn = linkLinesOnR356,"))
        assertTrue("帧字段底幕读态丢失", t.contains("cmbOn = cmbOn.value,"))
        assertTrue("帧字段连线读态丢失", t.contains("linkLines = linkLinesOn.value,"))
    }

    @Test
    fun `入口在场-启航五件接线与genesis入口块`() {
        val t = src("dev/tgfeed/ui/TasteMapScreen.kt")
        // 五件动作接线（飞往两件沿用旧函数体）
        for (anchor in listOf(
            "flyToNearestStarR258(ctx, channelStars, stars, wellMaxVol)",
            "flyToRandomStarR258(ctx, channelStars, wellMaxVol)",
            "exportVoyagePosterR316(logicCtx, starsById, nebulae.size)",
            "exportTasteMapPosterR316(logicCtx, stars, links)",
        )) {
            assertTrue("启航件接线丢失：$anchor", t.contains(anchor))
        }
        // 延时入口块（大爆炸自动正放=旧底中胶囊语义；诚实空态门保留）
        assertTrue(
            "延时入口块丢失（genesisOn/01/playing 三行）",
            Regex("enabled = genesisSpan\\.valid\\) \\{\\s*\\n\\s*ctx\\.genesisOn\\.value = true\\s*\\n\\s*ctx\\.genesis01\\.value = 0f\\s*\\n\\s*ctx\\.genesisPlaying\\.value = true").containsMatchIn(t),
        )
        // busy 态（导出中禁用）
        assertTrue("日志 busy 态丢失", Regex("busy = exportingVoyage").containsMatchIn(t))
        assertTrue("海报 busy 态丢失", Regex("busy = exporting").containsMatchIn(t))
        // 开态控制条换代在场（只在 genesisOn 期）
        assertTrue(
            "开态控制条条件丢失（应含 genesisOn 门）",
            Regex("if \\(selectedStar == null && stars\\.isNotEmpty\\(\\) && genesisOn && genesisSpan\\.valid\\) \\{\\s*\\n\\s*TasteMapGenesisActiveBar\\(").containsMatchIn(t),
        )
    }

    // ==================== 旧入口绝迹锚（收纳换代——宪法「换方法不是调参数」） ====================

    @Test
    fun `旧入口绝迹-四旧件与关态胶囊退役`() {
        val t = src("dev/tgfeed/ui/TasteMapScreen.kt")
        for (fn in listOf("TasteMapTopRightTools", "TasteMapExploreBar", "TasteMapExportButtons", "TasteMapGenesisControls")) {
            assertTrue(
                "旧件 $fn 应整体绝迹（定义与调用一并退役——回收入口=R356 收纳换代）",
                !t.contains(fn),
            )
        }
        // 旧关态延时胶囊（点开即播入口）绝迹——启航菜单「延时」件承载
        assertTrue(
            "旧关态胶囊残留（genesisPlaying.value = true 点开即播的胶囊位应只在启航菜单）",
            Regex("Text\\(\"延时摄影\"").containsMatchIn(t).not(),
        )
    }

    @Test
    fun `设置页空洞检查-星图行绝迹邻居行在场`() {
        val settings = src("dev/tgfeed/ui/SettingsScreen.kt")
        // 星图域五行绝迹（区块收拢无空洞）
        assertTrue(
            "设置页残留口味星图开关行（R356 应零星图域行）",
            !Regex("口味星图：").containsMatchIn(settings),
        )
        assertTrue(
            "设置页残留星图键写法（应零 prefs.tasteX 写点）",
            !Regex("prefs\\.taste(Cmb|Constellation|LinkLines|EmergentLayout|LinkPerturb)").containsMatchIn(settings),
        )
        // 邻居行在场（防过删——卡内其余行原位）
        for (anchor in listOf("关键词订阅", "订阅更新通知", "我的搜索群")) {
            assertTrue("邻居行 $anchor 被误删（过删检查）", settings.contains(anchor))
        }
        // 迁移指针行在场（防老用户迷路——恒一行）
        assertTrue(
            "迁移指针行丢失",
            Regex("口味星图的开关已收进星图内").containsMatchIn(settings),
        )
    }

    // ==================== 文案红线锚（图标名两字上限） ====================

    @Test
    fun `文案红线_图标名逐个不超两字`() {
        val t = src("dev/tgfeed/ui/TasteMapScreen.kt")
        // 快捷组：label = "X", hint = 形态（接线位在舞台件）
        val toggleLabels = Regex("label = \"([^\"]+)\", hint =").findAll(t)
            .map { it.groupValues[1] }.toList()
        assertTrue("快捷组接线位不存在（组件未接线？）", toggleLabels.isNotEmpty())
        for (label in toggleLabels) {
            assertTrue(
                "快捷组图标名「${label}」超两字上限（宪法克制：图标名两字上限）",
                label.length <= 2,
            )
        }
        // 启航菜单：TasteVoyageActionR356("X", …) 形态
        val voyageLabels = Regex(Regex.escape("TasteVoyageActionR356(") + "\\s*\"([^\"]+)\"").findAll(t)
            .map { it.groupValues[1] }.toList()
        assertTrue("启航菜单接线位不存在（组件未接线？）", voyageLabels.isNotEmpty())
        for (label in voyageLabels) {
            assertTrue(
                "启航菜单图标名「${label}」超两字上限（图标名两字上限）",
                label.length <= 2,
            )
        }
        // 五件对五件（收纳清单完整性：恒星/随机/延时/日志/海报）
        assertEquals(
            "启航菜单应恰五件（收纳清单完整性）",
            listOf("恒星", "随机", "延时", "日志", "海报"),
            voyageLabels,
        )
        assertEquals(
            "快捷组应恰五件（迁移五行完整性）",
            listOf("星座", "底幕", "连线", "涌现", "聚拢"),
            toggleLabels,
        )
    }
}
