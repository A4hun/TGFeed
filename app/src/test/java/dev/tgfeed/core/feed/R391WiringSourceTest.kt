package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R391 播放编排二期（播放台+口味智能编排）wiring 源码锚（R387WiringSourceTest
 * 同款 user.dir 源定位模式——VM/UI 接线无 JVM 直测面处以源锚锁结构面；
 * r391_notes §2/§3）：
 *  - 多台 core 锚：PlayQueue id 一刀+MAX_STATIONS 单源+legacy 常量；
 *  - VM 台级编排锚：台级五委托+今日单委托+台列表状态+活跃台纪律（切台 flush/
 *    失联回落/哨兵空模型）+今日台覆盖语义（同名替换+满员淘汰最旧）+入单自动
 *    开默认台；
 *  - UI 台列表锚：QueueStationsScreen（台卡一键开台+编辑入口+长按管理+新建
 *    上限 Toast+今日台生成钮+空态 nonce 引导）+路由四级（App 状态+MainActivity
 *    分支序=嵌套覆盖先于宿主+overlay 门+我的页行）+QueueScreen 标题=台名；
 *  - 红线负锚：星图渲染域零依赖（TodayQueueGen 零 Android 引用+GL 两件零
 *    PlayQueue/TodayQueue 引用=R388 在飞域零碰）+今日单原料只走 p2ChannelWeights
 *    （口味画像统一出口）+SmartRooms 聚类未引（挂账口径）。
 */
class R391WiringSourceTest {

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

    private fun core(): String = src("dev/tgfeed/core/feed/PlayQueue.kt")

    private fun gen(): String = src("dev/tgfeed/core/feed/TodayQueueGen.kt")

    private fun part(): String = src("dev/tgfeed/core/feed/FeedPlayQueuePart.kt")

    private fun vm(): String = src("dev/tgfeed/core/feed/FeedViewModel.kt")

    private fun store(): String = src("dev/tgfeed/core/store/LocalStore.kt")

    // ---------------- ① 多台 core 锚 ----------------

    @Test
    fun `PlayQueue台身份_id字段_哨兵语义_legacy常量`() {
        assertEquals(
            "id 字段恰一次声明（本件唯一一刀——copy 天然保留=模型/装配/源零改判词）",
            1,
            Regex("val id: String = \"\",").findAll(core()).count(),
        )
        assertTrue(
            "哨兵语义 KDoc 在场（空 id=无活跃台/冷启动空单；codec 读侧坏台跳过）",
            core().contains("\"\" = 哨兵语义") || core().contains("空串=哨兵语义") ||
                core().contains("= 哨兵语义"),
        )
        assertTrue("legacy 迁移台常量（v1 幂等）", core().contains("const val LEGACY_ID = \"legacy\""))
        assertEquals(
            "MAX_STATIONS=12 单源恰一次（存档体积+可用性）",
            1,
            Regex("const val MAX_STATIONS = 12").findAll(core()).count(),
        )
    }

    @Test
    fun `v2档_结构面锚_v1迁移链`() {
        val s = store()
        assertEquals("v2 版本位恰一次（put(\"v\", 2)）", 1, Regex("put\\(\"v\", 2\\)").findAll(s).count())
        assertTrue("stations 数组键在场", s.contains("put(\"stations\", JSONArray().apply"))
        assertTrue("activeId NULL 哨兵写侧", s.contains("put(\"activeId\", state.activeId ?: JSONObject.NULL)"))
        // v1 迁移链：has 判别（stations 在场=v2 否则 v1）+迁移台解析+legacy id
        assertTrue("v2/v1 判别（has(stations)）", s.contains("if (obj.has(\"stations\")) parseStationsV2(obj) else parseStationV1(obj)"))
        assertTrue("迁移台 id=LEGACY_ID（幂等重迁移不漂移）", s.contains("id = PlayQueue.LEGACY_ID"))
        assertTrue("条目解析 v1/v2 共用（parseEntries 私有单源）", s.contains("private fun parseEntries(entriesArr: JSONArray?)"))
        assertTrue("断点解析 v1/v2 共用（parseResume 私有单源）", s.contains("private fun parseResume(obj: JSONObject)"))
        // 台级坏档防御：id 空跳台（同条目 id 口径）
        assertTrue("台级坏档跳台（id 空=坏台）", s.contains("if (id.isEmpty()) continue"))
    }

    // ---------------- ② VM 台级编排锚 ----------------

    @Test
    fun `台级五委托_今日单委托_台列表状态`() {
        val v = vm()
        for (fn in listOf(
            "createPlayStation", "renamePlayStation", "deletePlayStation",
            "editPlayStation", "openPlayStation", "generateTodayStation",
        )) {
            assertTrue("委托 $fn 在场", Regex("fun $fn\\(").containsMatchIn(v))
        }
        assertTrue(
            "台列表状态（发布序=updatedAt 降序=最近动的在前）",
            v.contains("internal val _playQueueStations = MutableStateFlow<List<PlayQueue>>(emptyList())") &&
                v.contains("val playQueueStations: StateFlow<List<PlayQueue>>"),
        )
        assertTrue(
            "台列表底账+活跃台 id（主线程内存纪律）",
            v.contains("internal var playStations: List<PlayQueue> = emptyList()") &&
                v.contains("internal var activePlayStationId: String? = null"),
        )
        assertTrue(
            "今日单空态 nonce（一次性事件 nonce 单调递增防快照流去重吞）",
            v.contains("internal val _todayQueueEmptyHint = MutableStateFlow(0)"),
        )
        // 冷启动活跃台纪律：activeId 失联回落第一台（诚实降级不硬指）
        assertTrue(
            "冷启动活跃台=activeId 档失联回落第一台",
            v.contains("firstOrNull { it.id == restored.activeId }") &&
                v.contains("?: restored.stations.firstOrNull()"),
        )
    }

    @Test
    fun `台级impl_切台flush_覆盖语义_入单自动开台`() {
        val p = part()
        // 切台 flush（在途窗收窄：显式切台前旧活跃台最末笔同步进底账）
        assertTrue(
            "切台 flush 判词在场（editPlayStation 与 openPlayStation 共用切台链）",
            p.contains("internal fun FeedViewModel.editPlayStationImpl(stationId: String): Boolean") &&
                p.contains("afterPlayQueueMutation()") &&
                p.contains("playQueueModel.replaceAll(target)"),
        )
        // 一键开台=切台+一期开单链（零改全继承判词）
        assertTrue(
            "openPlayStationImpl 复用 editPlayStationImpl+一期开单链",
            p.contains("if (!editPlayStationImpl(stationId)) return") && p.contains("openPlayQueueImpl(fromStart)"),
        )
        // 今日台覆盖语义（R402 真缺陷修：原位替换+尾追=同 id 双插崩溃，收敛为
        // upsertTodayStation 纯函数单口——滤除旧今日台后单次追加+毒态自愈）
        assertTrue(
            "今日台覆盖=upsertTodayStation 纯函数单口（滤旧今日台后单次追加）",
            p.contains("playStations = upsertTodayStation(playStations, generated)"),
        )
        assertTrue(
            "覆盖键=name 同名台 ∨ 同 id 重入（TodayQueueGen 单源判据）",
            gen().contains("it.name == TodayQueueRecipe.STATION_NAME || it.id == generated.id"),
        )
        assertTrue(
            "满员淘汰最久未动台（今日台优先保）",
            gen().contains("sortedByDescending { it.updatedAt }.dropLast(1)"),
        )
        assertTrue(
            "生成后设活跃+直接开台（fromStart=false 今日单新断点 null=从头播）",
            p.contains("activePlayStationId = generated.id") &&
                p.contains("openPlayQueueImpl(fromStart = false)"),
        )
        // 入单自动开默认台（新装空库一期体感连续判词）
        assertTrue(
            "ensureActiveStationForEnqueue 在场（enqueue 三入口接）",
            p.contains("private fun FeedViewModel.ensureActiveStationForEnqueue()"),
        )
        // afterPlayQueueMutation 同步台列表（一切一期 ops 自动同步多台）
        assertTrue(
            "afterPlayQueueMutation 同步活跃台进台列表",
            p.contains("val idx = playStations.indexOfFirst { it.id == aid }"),
        )
        // 删除活跃台→切列表第一台或哨兵
        assertTrue(
            "删除活跃台切第一台/哨兵空模型",
            p.contains("val next = playStations.firstOrNull()") &&
                p.contains("playQueueModel.replaceAll(next ?: PlayQueue())"),
        )
    }

    @Test
    fun `今日单impl_原料快照_统一口径出口_诚实空态`() {
        val p = part()
        // 探索侧原料=p2ChannelWeights 统一口径（零另起炉灶判词）
        assertTrue(
            "channelWeights=p2ChannelWeights 统一口径",
            p.contains("runCatching { p2ChannelWeights() }"),
        )
        // 原料快照四件
        assertTrue("收藏账快照", p.contains("store.loadFavorites()"))
        assertTrue("已看账快照（收藏分桶用）", p.contains("store.loadSeenIdentities().first"))
        // 诚实空态：null→探针+nonce（不编造）
        assertTrue(
            "诚实空态：生成 null→nonce+探针（原料缺不编造）",
            p.contains("withContext(Dispatchers.Main) { _todayQueueEmptyHint.value += 1 }"),
        )
        // 生成探针行=计数口径（脱敏铁律：零频道名）
        assertTrue(
            "生成探针计数口径（探索N台/重温M条）",
            p.contains("今日台生成:探索=\${exploreCount}台 重温=\${favoriteCount}条"),
        )
        // 标题拼表：channels 覆盖订阅（L2646 先例）
        assertTrue(
            "频道标题拼表（订阅先+channels 覆盖）",
            p.contains("private fun FeedViewModel.todayChannelTitles()") &&
                p.contains("_channels.value.forEach { if (it.title.isNotBlank()) map[it.id] = it.title }"),
        )
    }

    // ---------------- ③ UI 台列表锚（路由四级+生成钮） ----------------

    @Test
    fun `台列表路由四级_嵌套覆盖先于宿主`() {
        val app = src("dev/tgfeed/App.kt")
        assertTrue("App 容器状态（镜像 showPlayQueue 模式）", app.contains("val showQueueStations: StateFlow<Boolean> = _showQueueStations"))
        val main = src("dev/tgfeed/MainActivity.kt")
        assertTrue(
            "MainActivity when 分支：QueueScreen（编辑页）在 showQueueStations（宿主）之前=嵌套覆盖先于宿主匹配",
            main.indexOf("showPlayQueue -> dev.tgfeed.ui.QueueScreen(") < main.indexOf("showQueueStations -> dev.tgfeed.ui.QueueStationsScreen("),
        )
        assertTrue("overlayPageOpen 门控加位", main.contains("showPlayQueue || showQueueStations ||"))
        assertTrue(
            "编辑入口=container.openPlayQueue（台列表内嵌跳转）",
            main.contains("onOpenStationEditor = { container.openPlayQueue() }"),
        )
        assertTrue(
            "我的页入口改开台列表（一期「播放队列」行的二期升级位）",
            main.contains("onOpenPlayQueue = { container.openQueueStations() }"),
        )
    }

    @Test
    fun `QueueStationsScreen_台卡一键开台_编辑管理_今日台生成钮`() {
        val q = src("dev/tgfeed/ui/QueueStationsScreen.kt")
        // 一键开台（空台守卫+切源剥落）
        assertTrue(
            "台卡点击=一键开台（vm.openPlayStation+onBack 剥落，一期 playQueue 口径）",
            q.contains("vm.openPlayStation(station.id)") && q.contains("fun openStation(station: PlayQueue)"),
        )
        assertTrue(
            "空台守卫 Toast（一期同款口径）",
            q.contains("这台还是空的，先编辑排单"),
        )
        // 编辑入口（切活跃台+onOpenStationEditor）
        assertTrue(
            "行尾编辑钮=切活跃台+进一期编辑面",
            q.contains("if (vm.editPlayStation(station.id)) {") && q.contains("onOpenStationEditor()"),
        )
        // 长按管理+新建上限+删除确认
        assertTrue("长按管理菜单（combinedClickable）", q.contains("combinedClickable(onClick = onOpen, onLongClick = onLongClick)"))
        assertTrue("新建上限 Toast", q.contains("播放台已达上限，先清理旧台"))
        assertTrue("删除二次确认", q.contains("断点将一并移除"))
        // 断点徽记（一期断点链视觉直读）
        assertTrue("断点徽记「播到一半」", q.contains("播到一半"))
        // 今日台生成钮（一键生成+直接开台+文案分流）
        assertTrue(
            "生成钮（vm.generateTodayStation+文案分流）",
            q.contains("vm.generateTodayStation()") &&
                q.contains("if (hasTodayStation) \"重新生成今日单\" else \"生成今日单\""),
        )
        // 空态 nonce 引导（诚实口径）
        assertTrue(
            "空态 nonce LaunchedEffect Toast 引导",
            q.contains("LaunchedEffect(todayEmptyHint)") && q.contains("口味数据还不够"),
        )
        // 共享 VM key（QueueScreen 同款口径）
        assertTrue(q.contains("key = \"gw\" + System.identityHashCode(gateway)"))
    }

    @Test
    fun `QueueScreen标题=活跃台名`() {
        val q = src("dev/tgfeed/ui/QueueScreen.kt")
        assertTrue(
            "一期硬编码「播放队列」升级为台名置题（与开台 feed 顶栏同源）",
            q.contains("title = queue.name"),
        )
    }

    // ---------------- ④ 红线负锚（星图渲染域/统一口径/零碰） ----------------

    @Test
    fun `红线负锚_星图渲染域零依赖`() {
        val g = gen()
        // TodayQueueGen 纯件：零 Android 依赖（JVM 直测纪律）
        assertTrue(
            "TodayQueueGen 零 android 依赖",
            !g.contains("import android") && !g.contains("import androidx"),
        )
        // GL 渲染域零碰（R388 在飞域）：两件零 PlayQueue/TodayQueue 引用
        val glShaders = src("dev/tgfeed/ui/gl/GlShaders.kt")
        assertTrue("GlShaders 零 PlayQueue/TodayQueue 引用（R388 域零碰）", !glShaders.contains("PlayQueue") && !glShaders.contains("TodayQueue"))
        val glFrame = src("dev/tgfeed/ui/gl/GlFrameBuilder.kt")
        assertTrue("GlFrameBuilder 零 PlayQueue/TodayQueue 引用（R388 域零碰）", !glFrame.contains("PlayQueue") && !glFrame.contains("TodayQueue"))
        // TasteMapScreen 渲染链零 TodayQueue 引用（星图侧只读口味数据源判词——
        // 原料在 VM 侧注入，渲染域不知道今日单存在）
        val taste = src("dev/tgfeed/ui/TasteMapScreen.kt")
        assertTrue("TasteMapScreen 零 TodayQueue 引用", !taste.contains("TodayQueue"))
    }

    @Test
    fun `红线负锚_今日单只读不写原料账`() {
        val p = part()
        // 只读三账：零写口（收藏/已看/watch_events 原样）
        assertTrue("今日单零写收藏账（loadFavorites 单向消费）", p.contains("store.loadFavorites()") && !p.contains("saveFavorites"))
        assertTrue("今日单零写已看账（loadSeenIdentities 单向消费）", !p.contains("saveSeenIdentities"))
        // SmartRooms 聚类未引（挂账口径：潜在后续增强，本期不引）
        assertTrue("SmartRooms 聚类未引（挂账）", !p.contains("SmartRooms"))
        // 配方常量单源：今日单件内零第二份配比数字
        assertEquals(
            "EXPLORE_CHANNELS_MAX 单源恰一次",
            1,
            Regex("EXPLORE_CHANNELS_MAX = 5").findAll(gen()).count(),
        )
        assertEquals(
            "FAVORITE_ITEMS_MAX 单源恰一次",
            1,
            Regex("FAVORITE_ITEMS_MAX = 8").findAll(gen()).count(),
        )
        assertEquals(
            "EXPLORE_WEIGHT_GATE 单源恰一次",
            1,
            Regex("EXPLORE_WEIGHT_GATE = 0.55").findAll(gen()).count(),
        )
    }
}
