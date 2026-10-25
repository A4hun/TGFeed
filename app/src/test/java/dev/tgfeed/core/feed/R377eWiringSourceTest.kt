package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R377e 发现页懒加载门 wiring 源码锚（R372/R376 同款 user.dir 源定位模式）。
 *
 * 施工判词=前任 R377 §2 门设计定案（Phase A 落库 ebf23bb）：用户 2026-10-24 11:4x
 * 拍板「发现页要调 SearchPublicChat 类接口可能触发限流——改成用户点击发现页时再
 * 请求，不要软件一打开就在后台酷酷工作，容易封号/被限」。实锚：TdLibGateway
 * Ready+20s 收割总闸门点火三分支（收割主循环含热词榜/EntityResolver 积压轮
 * BACKGROUND 车道/直荐验证轮 USER_ACTION 车道）全不感知用户是否打开过发现页；
 * 发现刷新另有两背景暗道（R211 直荐监听/金矿首建补刷）。
 *
 * 席位传承：R377 PhaseA 落库 → R377b 施工（六点接线+测试草稿成文，测试④终点锚
 * 顺序 bug 在场：onDiscoverPageSettled 位于 tasteDiscoveryCandidate 之后，起点>
 * 终点 substring 必抛 IndexOutOfBounds）中途死 → R377c 捡起 12 分钟即死 →
 * R377d 修正成文未及落库 → R377e 本席：WIP 六点接线核对全在零缺漏（笔1 落库
 * dec3470），本测试承 R377d 全案改挂 R377e 名落库（五例对表 §2.6 内容零改）。
 *
 * 门语义三档（§2.1）：关门=发现侧网络零发起（本地读照常）；开门（首开/距上次授权
 * ≥30min）=授权一轮（kickOnce 预算内收割+网络臂开）；窗内再开=零网络本地聚合。
 * 恒许=下拉刷新/换一批/骰子/双击（显式用户动作）网络臂恒开不受门。
 * 红线：加门不是拆预算（预算/熔断/账本语义零改）；liveAsk/搜索页显式提交链零碰；
 * 深索引/广场/存储治理非 Search 族按 §1.1 判词不进门。
 */
class R377eWiringSourceTest {

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

    private fun gate(): String = src("dev/tgfeed/core/feed/DiscoveryLazyGate.kt")
    private fun harvester(): String = src("dev/tgfeed/core/feed/GroupHarvester.kt")
    private fun resolver(): String = src("dev/tgfeed/core/feed/EntityResolver.kt")
    private fun recommend(): String = src("dev/tgfeed/core/feed/SearchGroupRecommend.kt")
    private fun vm(): String = src("dev/tgfeed/core/feed/FeedViewModel.kt")
    private fun part(): String = src("dev/tgfeed/core/feed/FeedDiscoveryPart.kt")
    private fun goldPart(): String = src("dev/tgfeed/core/feed/FeedDiscoveryGoldPart.kt")
    private fun screen(): String = src("dev/tgfeed/ui/DiscoverScreen.kt")
    private fun mainActivity(): String = src("dev/tgfeed/MainActivity.kt")

    /** [file] 中 [from] 锚（含）到 [to] 锚（不含）的切片；两锚必须在场且序正确 */
    private fun sliceBetween(file: String, from: String, to: String, what: String): String {
        val start = file.indexOf(from)
        assertTrue("$what 起点[$from]应在场", start >= 0)
        val end = file.indexOf(to, start + from.length)
        assertTrue("$what 终点[$to]应在场（且在起点之后）", end > start)
        return file.substring(start, end)
    }

    /**
     * ① 门常量单源：[DiscoveryLazyGate.WINDOW_MS] 唯一出处+30min 值锚（纯 JVM
     * 行为证）+接线七文件零镜像字面量（30min 档只许住在单源类里——改名/复制即红）。
     */
    @Test
    fun gate_window_single_source_30min() {
        val g = gate()
        val decls = Regex("const val WINDOW_MS[^\\n]*").findAll(g).toList()
        assertEquals("WINDOW_MS 必须唯一出处（单源）", 1, decls.size)
        assertEquals(
            "授权节流窗必须 30min 档（任务书「如 ≥30 分钟」取 30）",
            "const val WINDOW_MS: Long = 30 * 60_000L",
            decls.first().value.trim(),
        )
        // 30min 值锚（毫秒）：防表达式改写偷梁换柱
        assertEquals(1_800_000L, DiscoveryLazyGate.WINDOW_MS)
        // 接线文件零镜像：门窗值不得以字面量散落（经 discoveryGate.sessionActive() 消费）
        for (text in listOf(harvester(), resolver(), recommend(), vm(), part(), goldPart(), screen())) {
            assertTrue("接线文件禁止镜像 30min 懒窗字面量（单源消费）", !text.contains("30 * 60_000"))
        }
    }

    /**
     * ② 启动零发起负锚（源锚逐个点名）：收割循环条件含 sessionActive（热词榜同体
     * 收编）/解析循环含门/直荐循环含门；refreshDiscoveryImpl 两网络臂受臂参数门控；
     * R211 监听+金矿首建补刷两背景暗道走臂关；落定页改走门公共口；候门/臂探针在档。
     */
    @Test
    fun cold_start_zero_discovery_network_negative_anchors() {
        // 收割主循环：enabled&&Ready 加懒门（热词榜+自动发现+白名单同体收编）
        val h = harvester()
        val harvestLoop = sliceBetween(h, "fun start()", "fun stop()", "GroupHarvester.start")
        assertTrue(
            "收割循环条件必须含懒门（enabled&&ready&&sessionActive）",
            harvestLoop.contains("val gateOpen = enabled && ready && discoveryGate.sessionActive()"),
        )
        assertTrue("关门段循环体必须整体跳过（if (gateOpen)）", harvestLoop.contains("if (gateOpen) {"))
        assertTrue("收割轮候门探针应在场", harvestLoop.contains("""discoveryGate.noteGateHold("收割轮")"""))
        // 解析积压轮：ready 加懒门（「启动即消化积压」改「门内会话消化」）
        val r = resolver()
        val resolveLoop = sliceBetween(r, "fun start()", "fun stop()", "EntityResolver.start")
        assertTrue("解析循环条件必须含懒门", resolveLoop.contains("val gateOpen = ready && gate.sessionActive()"))
        assertTrue("解析循环体必须整体跳过", resolveLoop.contains("if (gateOpen) {"))
        assertTrue("解析轮候门探针应在场", resolveLoop.contains("""gate.noteGateHold("解析轮")"""))
        // 直荐验证轮：同上门（顺治背景轮烧 USER_ACTION 车道实锚）
        val rec = recommend()
        val recLoop = sliceBetween(rec, "fun start()", "fun stop()", "SearchGroupRecommender.start")
        assertTrue("直荐循环条件必须含懒门", recLoop.contains("val gateOpen = ready && gate.sessionActive()"))
        assertTrue("直荐循环体必须整体跳过", recLoop.contains("if (gateOpen) {"))
        assertTrue("直荐轮候门探针应在场", recLoop.contains("""gate.noteGateHold("直荐轮")"""))
        // 两网络臂受 allowNetworkArm 门控（runTasteRadar=SearchPublicPosts 日配额；
        // resolvePendingChannels=SearchPublicChat 族）
        val p = part()
        val impl = p.substring(p.indexOf("internal fun FeedViewModel.refreshDiscoveryImpl"))
        assertTrue("口味雷达臂必须受 allowNetworkArm 门控", impl.contains("if (allowNetworkArm) runTasteRadar()"))
        assertTrue(
            "待解析队列臂必须受 allowNetworkArm 门控",
            impl.contains("if (allowNetworkArm && real != null) runCatching { real.resolvePendingChannels() }"),
        )
        // 两背景暗道走臂关（背景刷新只做本地聚合——网络工作恒挂用户意图时刻+
        // 门内常驻循环两处，不再有第三条暗道）
        assertTrue("R211 直荐监听补刷必须网络臂关", vm().contains("refreshDiscoveryImpl(allowNetworkArm = false)"))
        assertTrue("金矿首建补刷必须网络臂关", goldPart().contains("refreshDiscoveryImpl(allowNetworkArm = false)"))
        // 落定页唯一授权源：进页效果改走门公共口
        assertTrue("落定页必须走 onDiscoverPageSettled（门授权源）", screen().contains("vm.onDiscoverPageSettled()"))
        // 探针口径在档（§2.5）：候门 D 级一关门段一行+开门行 I 级+臂行两形态
        assertTrue("候门行口径应在场", gate().contains("候门（未开门）"))
        assertTrue("开门行口径应在场", gate().contains("→授权一轮（预算内收割+发现刷新）"))
        val settledFn = vm().substring(vm().indexOf("fun onDiscoverPageSettled()"))
        assertTrue("授权轮臂行应在场", settledFn.contains("网络臂开（授权轮）"))
        assertTrue("窗内再开臂行应在场", settledFn.contains("窗内再开，复用缓存（网络臂关）"))
    }

    /**
     * ③ 开门节流窗三档（纯 JVM 单测 [DiscoveryLazyGate]，注入时钟）：
     * 首开授权/窗内再开拒绝/窗外再开授权/sessionActive 三态/新实例（=冷启）恒关门。
     */
    @Test
    fun open_grant_throttle_three_tiers_and_cold_start() {
        var now = 1_000_000L
        val gate = DiscoveryLazyGate { now }
        // 新实例（=冷启，零持久化）：恒关门
        assertFalse("冷启必须恒关门（零持久化设计裁决）", gate.sessionActive())
        // 首开=授权
        assertTrue("首开必须授权", gate.onUserOpen())
        assertTrue("开门后门内会话必须激活", gate.sessionActive())
        // 窗内（<30min）再开=拒绝（零网络，复用缓存）
        now += 29 * 60_000L
        assertFalse("窗内再开必须拒绝", gate.onUserOpen())
        assertTrue("窗内会话仍激活（常驻循环照干活）", gate.sessionActive())
        // 窗外（≥30min）再开=授权
        now += 60_000L
        assertTrue("窗外再开必须重新授权", gate.onUserOpen())
        // 窗过期=常驻循环停（诚实陈旧优于烧账号）
        now += DiscoveryLazyGate.WINDOW_MS
        assertFalse("窗过期会话必须失效（常驻循环停）", gate.sessionActive())
        // 窗过期后再开=授权（再交互恢复）
        assertTrue(gate.onUserOpen())
    }

    /**
     * ④ 下拉刷新恒许：公共口 refreshDiscovery 签名零改网络臂恒开；换一批/骰子
     * （triggerBatchRefresh）+底栏双击发现（MainActivity）三调用点不受门；
     * 授权公共口 granted→kickOnce+臂开 / 窗内→臂关。
     */
    @Test
    fun pull_refresh_always_allowed_three_call_sites() {
        // 公共口签名零改：不透传臂参数（默认 true=恒许）
        val vmText = vm()
        val apiDecl = Regex("fun refreshDiscovery\\(excludeIds: Set<Long> = emptySet\\(\\)\\)[^\\n]*")
            .findAll(vmText).toList()
        assertEquals("refreshDiscovery 公共口应唯一", 1, apiDecl.size)
        assertEquals(
            "公共口必须整臂委托（显式用户动作恒许，不受门）",
            "fun refreshDiscovery(excludeIds: Set<Long> = emptySet()) = refreshDiscoveryImpl(excludeIds)",
            apiDecl.first().value.trim(),
        )
        // 授权公共口：granted 路径 kickOnce+整臂刷新；窗内路径显式臂关。
        // 终点锚=acceptDiscoveryCandidate（onDiscoverPageSettled 落在 tasteDiscoveryCandidate
        // 之后——R377b 草稿曾以 tasteDiscoveryCandidate 为终点锚致起点>终点必炸，R377d 修正）
        val settled = sliceBetween(vmText, "fun onDiscoverPageSettled()", "fun acceptDiscoveryCandidate", "授权公共口")
        assertTrue("授权轮必须 kickOnce 预算内收割", settled.contains("real.groupHarvester.kickOnce()"))
        assertTrue("授权轮必须整臂刷新", settled.contains("refreshDiscovery()"))
        assertTrue("窗内再开必须显式臂关", settled.contains("refreshDiscoveryImpl(allowNetworkArm = false)"))
        // 调用点①②：换一批/骰子（DiscoverScreen triggerBatchRefresh）直走公共口
        val s = screen()
        val batch = sliceBetween(s, "val triggerBatchRefresh", "LaunchedEffect(loading, batchRefreshing)", "换一批切片")
        assertTrue("换一批/骰子必须走 refreshDiscovery（恒许）", batch.contains("vm.refreshDiscovery(excludeIds = excludeIds)"))
        assertFalse("换一批切片不得走门公共口（恒许语义）", batch.contains("onDiscoverPageSettled"))
        // 调用点③：底栏双击发现（MainActivity）直走公共口
        assertTrue(
            "双击发现必须走 refreshDiscovery（恒许）",
            mainActivity().contains("dev.tgfeed.ui.TopLevelRoute.DISCOVER -> feedVmNow?.refreshDiscovery()"),
        )
        // 落定页与恒许两路并存：进页效果走门、恒许三调用点不走门
        val settledEffect = sliceBetween(s, "LaunchedEffect(pageActive)", "LaunchedEffect(timeBand, candidates)", "落定页效果")
        assertTrue("落定页必须走门公共口", settledEffect.contains("vm.onDiscoverPageSettled()"))
        assertFalse("落定页不得直走恒许公共口（授权语义唯一源）", settledEffect.contains("vm.refreshDiscovery()"))
    }

    /**
     * ⑤ 收割链语义零改：kickOnce 镜像 groupRecommend.kick 模式（enabled+Ready+
     * 单飞闸+跑一轮 harvestAllGroups）——预算/熔断/账本调用点原样；负锚：门内
     * 零新发送口零预算绕过（§九统一发送口纪律）；主循环侧挂起包装保顺序节拍。
     */
    @Test
    fun kick_once_reuses_harvest_budget_chain_unchanged() {
        val h = harvester()
        val kick = sliceBetween(h, "fun kickOnce()", "private suspend fun harvestAllGroups()", "kickOnce")
        // 镜像三件：enabled+Ready 检查+单飞闸
        assertTrue("kickOnce 必须查收割开关", kick.contains("prefs.groupHarvestEnabled"))
        assertTrue("kickOnce 必须查网关 Ready", kick.contains("GatewayState.Ready"))
        assertTrue("kickOnce 必须走单飞闸（防与主循环双跑）", kick.contains("harvestAllGroupsGuarded()"))
        // 负锚：kickOnce 本体零新发送口零预算/账本直呼（全在 harvestAllGroups 既有链内照记）
        for (token in listOf("SendMessage", "client.send", "searchPublicChat", "tryAcquire", "SearchQuotaLedger")) {
            assertTrue("kickOnce 本体禁止出现 $token（§九统一发送口纪律）", !kick.contains(token))
        }
        // 复用同一轮收割：单飞闸包装内跑的就是 harvestAllGroups（零第二条收割路径）
        val guarded = sliceBetween(h, "private suspend fun harvestAllGroupsGuarded()", "fun kickOnce()", "单飞闸包装")
        assertTrue("包装必须复用 harvestAllGroups", guarded.contains("harvestAllGroups()"))
        assertTrue("包装必须挂起式（主循环侧顺序节拍保留）", h.contains("private suspend fun harvestAllGroupsGuarded()"))
        // 主循环同一包装（与 kick 互斥同一闸）；失败记账壳原样
        val harvestLoop = sliceBetween(h, "fun start()", "fun stop()", "GroupHarvester.start")
        assertTrue("主循环必须走同一单飞闸包装", harvestLoop.contains("harvestAllGroupsGuarded()"))
        assertTrue("主循环不得绕闸直呼 harvestAllGroups", !harvestLoop.contains("harvestAllGroups()"))
        // harvestAllGroups 本体零改锚：预算刷新/均分/R211 kick 尾钩三件在位
        val body = h.substring(h.indexOf("private suspend fun harvestAllGroups()"))
        assertTrue("轮首预算上限刷新应原样", body.contains("harvestBudget.maxOpsPerHour = runCatching { prefs.floodHarvestPerHour }.getOrDefault(60)"))
        assertTrue("预算均分应原样", body.contains("harvestBudget.remaining() / entities.size"))
        assertTrue("R211 收割轮尾 kick 应原样", body.contains("runCatching { groupRecommend.kick() }"))
    }
}
