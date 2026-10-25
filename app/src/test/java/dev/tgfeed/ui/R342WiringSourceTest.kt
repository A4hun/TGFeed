package dev.tgfeed.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R342 天体卡片交互统一 wiring 源码锚（R323WiringSourceTest 同款 user.dir 源定位
 * 模式——UI 侧手势/卡片/运镜无 JVM 直测面，以源锚锁三件结构；数值半体由
 * GestureFeelPolicyTest（voyageSec 政策件）承重）：
 *  - **D2 手势门**：共享件在场（claim+Initial 纯观察不消费+下滑收卡 64dp+模型旗
 *    防误收）+信息卡收编（原内联 drag-to-dismiss 退役）+世界卡卡根新挂（穿透
 *    根治位）+门探针行（V46-② 事件级取证面）；
 *  - **D1 共享模型件**：信息卡 3D 模型段（StarSystemMiniModel 第二消费者+
 *    onDragStateChange 防误收旗+onFatal→TagOrbitCloud 2D 兜底+pill 行）+高度
 *    三档单一出处（PlanetWorldCard 内联 when 退役）；
 *  - **D3 航行节奏**：帧循环 voyage 分支（p01 时间归一+easePose+端点精确+到达
 *    探针）+四触发点收编 helper+**非航行族负锚**（feed 跳入/巡航段/glance/
 *    银河视角原样——节奏家族协调声明的机制面）；
 *  - **星图本体手势零改动红线**：slotMod 双 pointerInput/FlightGestureMachine
 *    零 R342 标记（隔离全在卡侧）；
 *  - **主方法零接线铁令**：主 composable 本体零 R342 标记。
 */
class R342WiringSourceTest {

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

    private fun tms(): String = src("dev/tgfeed/ui/TasteMapScreen.kt")
    private fun gate(): String = src("dev/tgfeed/ui/TasteSheetGestureGate.kt")
    private fun mini(): String = src("dev/tgfeed/ui/gl/GlMiniSystemView.kt")
    private fun gestures(): String = src("dev/tgfeed/ui/TasteFlightGestures.kt")

    // ==================== D2 手势门（隔离政策件） ====================

    @Test
    fun `手势门-共享件结构与纯观察红线`() {
        val g = gate()
        // 共享口在场（Modifier 扩展——两卡同用一处定义）
        assertTrue(
            "手势门口缺失（Modifier.tasteSheetGestureGate）",
            Regex("fun Modifier\\.tasteSheetGestureGate\\(").containsMatchIn(g),
        )
        // Initial 纯观察（三禁一许的「许」位=卡内容共同祖先+Initial pass）
        assertTrue(
            "手势门必须 Initial pass 观察（事件必经且先于后代层）",
            Regex("pass = PointerEventPass\\.Initial").containsMatchIn(g) &&
                Regex("awaitPointerEvent\\(PointerEventPass\\.Initial\\)").containsMatchIn(g),
        )
        // 纯观察红线：门不消费（消费=卡内滚动/LazyRow/按钮/模型被饿死——三禁复发）
        assertTrue(
            "手势门必须零消费（纯观察——消费即破卡内手势共存）",
            !Regex("\\.consume\\(\\)").containsMatchIn(g),
        )
        // 下滑收卡常数走政策件单源（64dp 零新造）
        assertTrue(
            "手势门收卡阈必须收口 GestureFeelPolicy.CARD_DISMISS_DRAG_DP",
            Regex("GestureFeelPolicy\\.CARD_DISMISS_DRAG_DP").containsMatchIn(g),
        )
        // 模型旗防误收（模型拖动中不累计收卡）
        assertTrue(
            "手势门必须接 modelDragging 防误收旗",
            Regex("modelDragging\\?\\.value == true").containsMatchIn(g),
        )
        // V46-② 门侧事件级探针（卡内手势行+收卡行）
        assertTrue(
            "手势门探针行缺失（V46-② 取证面）",
            Regex("R342 手势门: 卡内手势").containsMatchIn(g) &&
                Regex("R342 手势门: 下滑收卡").containsMatchIn(g),
        )
    }

    @Test
    fun `手势门-两卡卡根挂载与退役锚`() {
        val t = tms()
        // 信息卡：内联 drag-to-dismiss 退役→共享门（收编去重——同语义一处定义）
        assertTrue(
            "信息卡必须收编共享门（canStart=起手在顶才计）",
            Regex("tasteSheetGestureGate\\(\\s*key = st\\.chatId,").containsMatchIn(t) &&
                Regex("canStart = \\{ cardScrollState\\.value == 0 \\}").containsMatchIn(t),
        )
        // 世界卡：卡根新挂门（穿透根治位——原根 Column 无 pointerInput=命中透明）
        assertTrue(
            "世界卡卡根必须挂门（onDismissDown=onClose 相机回位链）",
            Regex("tasteSheetGestureGate\\(\\s*key = target\\.chatId,").containsMatchIn(t) &&
                Regex("onDismissDown = \\{ onClose\\(\\) \\}").containsMatchIn(t),
        )
        // 原内联 drag-to-dismiss 退役负锚（旧观察器绝迹——收编零双写）
        assertTrue(
            "旧内联 drag-to-dismiss 观察器必须退役（零双写——收编单一出处）",
            !Regex("CARD_DISMISS_DRAG_DP\\.dp\\.toPx\\(\\)").containsMatchIn(t),
        )
        // 两卡模型旗接线（onDragStateChange→cardModelDragging）
        assertTrue(
            "两卡必须接模型拖动旗（onDragStateChange→cardModelDragging）",
            Regex("onDragStateChange = \\{ cardModelDragging\\.value = it \\}").containsMatchIn(t),
        )
    }

    @Test
    fun `隔离-星图本体手势零改动红线`() {
        val t = tms()
        // slotMod 双 pointerInput 原样（tap 探测+飞行手势机器——一行不动红线）
        assertTrue(
            "slotMod 手势链必须原样（detectTapGestures+detectFlightGestures 同节点共存）",
            Regex("\\.pointerInput\\(stars\\) \\{").containsMatchIn(t) &&
                Regex("\\.pointerInput\\(Unit\\) \\{").containsMatchIn(t) &&
                Regex("detectFlightGestures\\(slopPx, engagePx, onGesture =").containsMatchIn(t),
        )
        // 飞行手势机器零 R342 标记（星图侧零改动的字面断言）
        assertTrue(
            "FlightGestureMachine 必须零 R342 标记（隔离全在卡侧——星图侧加阻挡=粗暴方案红线）",
            !Regex("R342").containsMatchIn(gestures()),
        )
    }

    // ==================== D1 信息卡 3D 模型段（共享件第二消费者） ====================

    @Test
    fun `信息卡-3D模型段与2D兜底与pill行`() {
        val t = tms()
        // 3D 模型段（StarSystemMiniModel 第二消费者——360° 拖动统一）
        assertTrue(
            "信息卡必须有 3D 模型段（StarSystemMiniModel 共享件第二消费者）",
            Regex("StarSystemMiniModel\\(\\s*chatId = st\\.chatId,").containsMatchIn(t),
        )
        // 全景档零高亮 + 点行星同 pill 链
        assertTrue(
            "信息卡模型 selectedTag=null 全景档 + onPlanetTap=tagClick 同链",
            Regex("selectedTag = null").containsMatchIn(t) &&
                Regex("onPlanetTap = tagClick").containsMatchIn(t),
        )
        // 诚实降级：GL 致命→TagOrbitCloud 2D 兜底（三闸哲学——撤 3D 留 2D）
        assertTrue(
            "信息卡模型必须有 2D 兜底（onFatal→miniGlOk=false→TagOrbitCloud）",
            Regex("onFatal = \\{\\s*miniGlOk = false").containsMatchIn(t) &&
                Regex("TagOrbitCloud\\(\\s*planets = planets,").containsMatchIn(t),
        )
        // pill 行（模型无逐行星名签——tag 名可读可点补偿层）
        assertTrue(
            "信息卡模型下必须有 tag pill 行（horizontalScroll+tagClick）",
            Regex("horizontalScroll\\(rememberScrollState\\(\\)\\)").containsMatchIn(t),
        )
        // 旗穿透链：卡根持有→模型置位→门消费
        assertTrue(
            "modelDragging 旗穿透链必须闭合（卡根声明+传 StarCardTagCloud）",
            Regex("val cardModelDragging = remember \\{ mutableStateOf\\(false\\) \\}").containsMatchIn(t) &&
                Regex("modelDragging = cardModelDragging,").containsMatchIn(t),
        )
    }

    @Test
    fun `共享口-高度三档单一出处`() {
        val m = mini()
        val t = tms()
        // 高度三档公开口在场（GlMiniSystemView 件域——API 只加）
        assertTrue(
            "miniModelHeightDp 共享口必须在场（180/148/118 三档）",
            Regex("internal fun miniModelHeightDp\\(screenHeightDp: Int\\)").containsMatchIn(m) &&
                Regex("screenHeightDp >= 700 -> 180\\.dp").containsMatchIn(m),
        )
        // 两卡同用共享口；世界卡内联 when 段退役负锚（单一出处）
        assertTrue(
            "两卡必须消费 miniModelHeightDp（import+调用）",
            Regex("import dev\\.tgfeed\\.ui\\.gl\\.miniModelHeightDp").containsMatchIn(t) &&
                Regex("miniModelHeightDp\\(LocalConfiguration\\.current\\.screenHeightDp\\)").containsMatchIn(t),
        )
        assertTrue(
            "世界卡内联三档必须退役（防两处写漂移）",
            !Regex("screenHdp >= 700 -> 180\\.dp").containsMatchIn(t),
        )
        // onDragStateChange 参口（默认 null=现状零行为——API 只加）
        assertTrue(
            "StarSystemMiniModel 必须有 onDragStateChange 参口（默认 null 零行为变化）",
            Regex("onDragStateChange: \\(\\(Boolean\\) -> Unit\\)\\? = null,").containsMatchIn(m),
        )
        // 重建复位卫（防陈旧 true 卡死门）
        assertTrue(
            "模型 pointerInput 重建必须先复位旗（防陈旧 true）",
            Regex("onDragStateChange\\?\\.invoke\\(false\\)\\s*// R342").containsMatchIn(m) ||
                Regex("// R342：重建即复位拖动态旗").containsMatchIn(m),
        )
    }

    // ==================== D3 航行节奏（voyage 模式） ====================

    @Test
    fun `voyage-帧循环分支与起手单口`() {
        val t = tms()
        // FocusFlightState voyage 字段+起手单口（只加不改——beginWorldFlight 清位）
        assertTrue(
            "FocusFlightState 必须有 voyage 字段组（voyageMode/voyageSec/voyageP01/voyageFrom）",
            Regex("var voyageMode = false").containsMatchIn(t) &&
                Regex("var voyageP01 = 0f").containsMatchIn(t) &&
                Regex("fun beginVoyage\\(from: SpaceCam, durationSec: Float\\)").containsMatchIn(t),
        )
        assertTrue(
            "beginWorldFlight 必须清 voyageMode（其余世界飞行默认非航行档）",
            Regex("beginWorldFlight\\(\\) \\{\\s*active = true\\s*poseMode = false\\s*voyageMode = false")
                .containsMatchIn(t),
        )
        // 帧循环 voyage 分支：p01 时间归一+easePose+端点精确+到达探针
        assertTrue(
            "帧循环必须有 voyage 分支（voyageP01 += dtSec / voyageSec）",
            Regex("focusFlight\\.voyageP01 \\+= dtSec / focusFlight\\.voyageSec").containsMatchIn(t),
        )
        assertTrue(
            "voyage 分支必须走 easePose（smoothstep=加速-巡航-缓出）",
            Regex("cam = TasteSpace\\.easePose\\(vFrom, to, p01\\)").containsMatchIn(t),
        )
        assertTrue(
            "voyage 到达必须时间驱动端点精确+到达探针（时长档断言锚）",
            Regex("R342 航行: 到达 用时=").containsMatchIn(t),
        )
        // 停船位每帧重解（N 体跟随）+停船距内=原地只转朝向（approach 同式）
        assertTrue(
            "voyage 停船位必须当帧重解（tx/ty/tz 重锚消费）",
            Regex("if \\(vLen > focusFlight\\.stopDist \\+ 0\\.5f").containsMatchIn(t),
        )
        // 起手 helper：距离口径=N 体当帧位+启航探针
        assertTrue(
            "beginStarVoyageR342 必须在场（距离正比时长起手单口+启航探针）",
            Regex("private fun beginStarVoyageR342\\(").containsMatchIn(t) &&
                Regex("R342 航行: 启航").containsMatchIn(t),
        )
    }

    @Test
    fun `voyage-四触发点收编`() {
        val t = tms()
        // 双击天体 GOTO（detectTapGestures onDoubleTap 块内）
        assertTrue(
            "双击 GOTO 必须走航行档（onDoubleTap 块收编 helper）",
            Regex("beginStarVoyageR342\\(ctx, s, stop\\)").containsMatchIn(t),
        )
        // 探索运镜族（flyToStarR258——引导带飞/点未知星/信息卡邻星一函数收三链）
        assertTrue(
            "flyToStarR258 必须走航行档（探索运镜族收编）",
            Regex("beginStarVoyageR342\\(ctx, s, stop\\) // R342：航行档").containsMatchIn(t),
        )
        // 弧命中（arc tap→运镜飞向对方恒星）
        assertTrue(
            "弧命中必须走航行档",
            Regex("beginStarVoyageR342\\(ctx, target, stop\\)").containsMatchIn(t),
        )
        // 世界卡泊入（openWorldCardR316）
        assertTrue(
            "世界卡泊入必须走航行档（到位后环绕自然开）",
            Regex("beginStarVoyageR342\\(ctx, s, stop\\) // R342 D3：泊入").containsMatchIn(t),
        )
    }

    @Test
    fun `voyage-非航行族负锚（节奏家族协调机制面）`() {
        val t = tms()
        // feed 跳入 A 案原样（beginWorldFlight+CRUISE τ——R301「睁眼就在星前」设计）
        assertTrue(
            "feed 跳入 flyTargetJumpR254 必须原样非航行（A 案特写起手）",
            Regex("flyTargetJumpR254").containsMatchIn(t) &&
                Regex("ctx\\.focusFlight\\.tau = GestureFeelPolicy\\.CRUISE_TAU_SEC").containsMatchIn(t),
        )
        // 单击注目 glance 原样（注目非航行）
        assertTrue(
            "单击注目 glance 必须原样 τ=0.18（注目非航行）",
            Regex("ctx\\.focusFlight\\.tau = GestureFeelPolicy\\.GLANCE_TAU_SEC").containsMatchIn(t),
        )
        // R271 巡航段原样（剧本节奏已验证）
        assertTrue(
            "R271 巡航段必须原样非航行（EASE_TAU_SEC 缓出在场）",
            Regex("ctx\\.focusFlight\\.tau = TasteTour\\.EASE_TAU_SEC").containsMatchIn(t),
        )
        // R303c 银河视角原样（poseMode 仪式位姿）
        assertTrue(
            "银河视角必须原样 poseMode（GALAXY_VIEW_TAU_SEC 在场）",
            Regex("ctx\\.focusFlight\\.tau = TasteScaleSigma\\.GALAXY_VIEW_TAU_SEC").containsMatchIn(t),
        )
    }

    // ==================== 主方法零接线铁令（R342 标记版） ====================

    /** brace 配对量主函数体（TasteMapMethodBudgetTest 同款口径） */
    private fun mainFunctionBody(file: File): String {
        val lines = file.readLines()
        val start = lines.indexOfFirst { Regex("^fun TasteMapScreen\\(").containsMatchIn(it) }
        require(start >= 0) { "主 composable TasteMapScreen 未找到" }
        var bodyStart = -1
        for (j in start until lines.size) {
            if (Regex("\\)\\s*\\{\\s*(//.*)?$").containsMatchIn(lines[j])) { bodyStart = j; break }
        }
        require(bodyStart >= 0) { "主函数体开括号未找到" }
        val sb = StringBuilder()
        var depth = 0
        for (j in bodyStart until lines.size) {
            val code = lines[j].split("//")[0]
            depth += code.count { it == '{' } - code.count { it == '}' }
            sb.appendLine(lines[j])
            if (depth == 0 && j > bodyStart) return sb.toString()
        }
        error("主函数体闭括号未配平")
    }

    @Test
    fun `主方法零接线-R342接线不进主composable本体`() {
        val body = mainFunctionBody(File(mainSourceRoot, "dev/tgfeed/ui/TasteMapScreen.kt"))
        // R342 接线全在组件函数（两张卡/StarCardTagCloud）/顶层函数（beginStar
        // VoyageR342/帧循环）/新件（TasteSheetGestureGate）——主方法本体零 R342
        // 标记（回填主方法=寄存器压力回顶——本锚即红）
        assertTrue(
            "主 composable 本体出现 R342 接线标记（主方法零接线铁令被破——迁组件/顶层函数！）",
            !Regex("R342|beginStarVoyage|tasteSheetGestureGate|voyageMode").containsMatchIn(body),
        )
    }
}
