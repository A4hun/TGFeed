package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R387 播放编排一期（节目单）wiring 源码锚（R373/R378b/R385c 同款 user.dir 源
 * 定位模式——VM/UI 接线无 JVM 直测面处以源锚锁结构面；r387_notes §1.7/§3/§4）：
 *  - 存储锚：play_queue.json codec 单源+坏档容错+NULL 哨兵判式；
 *  - VM 接线锚：onPage 断点钩子三行刀+loadMore 两处点名豁免（重发折叠名单+
 *    P1.5/P2 页内后处理门=队列序保全闭环）+自含块（模型/状态/落盘串流 conflate）；
 *  - 断点链锚：6s 有界等待+迟到守卫+sourceTitle 置题+resumeScroll 通道复用；
 *  - 入口锚：队列页路由（App 状态+MainActivity 分支+我的页行）+两入口格钮
 *    （ActionSheet 加入队列格内建兜底 VM/星图星卡入单钮）；
 *  - 红线负锚：播放链零碰（ListenQueue.kt 零 PlayQueue 引用）+搜索排序零碰
 *    （SearchSort.kt 零 PlayQueue 引用）+片单链路零碰（FeedPlaylistPart 原样）。
 */
class R387WiringSourceTest {

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

    private fun part(): String = src("dev/tgfeed/core/feed/FeedPlayQueuePart.kt")

    private fun vm(): String = src("dev/tgfeed/core/feed/FeedViewModel.kt")

    private fun store(): String = src("dev/tgfeed/core/store/LocalStore.kt")

    // ---------------- ① 存储锚 ----------------

    @Test
    fun `codec单源_坏档容错_NULL哨兵判式_前向兼容`() {
        // R391 v2 升格保义翻新：单队列 codec→播放台列表 codec（stations+activeId，
        // 判词 r391_notes §2.2——一期双函数退役防同文件双 codec 漂移）；v1 旧档
        // 读侧迁移（legacy 台幂等）。单源/防御语义原样沿续。
        val loadFn = Regex("fun loadPlayStations\\(\\): PlayStationsState \\{")
        assertEquals("loadPlayStations 恰一次定义（单源）", 1, loadFn.findAll(store()).count())
        assertEquals("savePlayStations 恰一次定义（单源）", 1, Regex("fun savePlayStations\\(state: PlayStationsState\\)").findAll(store()).count())
        assertEquals("play_queue.json 文件名恰一次（同文件 v2 升格口）", 1, Regex("\"play_queue\\.json\"").findAll(store()).count())
        // 坏档整体容错回落空库（runCatching 兜底——一条坏记录不拖垮整库的最后一道闸）
        assertTrue(
            "坏档整体容错回落空库",
            store().contains("}.getOrDefault(PlayStationsState(emptyList(), null))"),
        )
        // NULL 哨兵判式（Android org.json 对 NULL 值 optString 回字面 "null" 的坑防；
        // v2 收口进 parseResume 私有件，v1 顶层/v2 台对象共用）
        assertTrue(
            "断点字段须 has+isNull 先守（playUri 读侧同坑同防）",
            store().contains("obj.has(\"resumeEntryId\") && !obj.isNull(\"resumeEntryId\")") &&
                store().contains("obj.has(\"activeId\") && !obj.isNull(\"activeId\")"),
        )
        // 前向兼容：未知 type 跳过不炸整单
        assertTrue("未知 type 前向兼容跳过", store().contains("// 未知 type（未来版本条目）：跳过不炸整单"))
        // 档位反解单源（R371 fromName 先例；v2 台对象 s.optString 同单源口径）
        assertTrue("orderMode 经 fromName 单源反解", store().contains("PlayQueueOrder.fromName(s.optString(\"orderMode\"))"))
        // v1 迁移在读侧（幂等 legacy 台；旧装机首读即升级）
        assertTrue("v1 旧档读侧迁移（legacy 幂等）", store().contains("parseStationV1(obj)") && store().contains("PlayQueue.LEGACY_ID"))
    }

    @Test
    fun `MAX_ENTRIES单源200_ALIVE游标不透明`() {
        assertEquals(
            "MAX_ENTRIES=200 单源恰一次（ListenQueue MAX_SIZE 同族精神）",
            1,
            Regex("const val MAX_ENTRIES = 200").findAll(core()).count(),
        )
        assertTrue(
            "ALIVE 游标=不透明标记（外层只辨 null 与否，FollowingFeedSource 同口径）",
            core().contains("const val ALIVE_CURSOR = \"r387:alive\"") &&
                core().contains("if (exhausted) null else ALIVE_CURSOR"),
        )
    }

    // ---------------- ② VM 接线锚（队列序保全闭环+断点链） ----------------

    @Test
    fun `onPage断点钩子_队列专用通道不打扰既有账`() {
        val onPage = vm().substring(vm().indexOf("fun onPage(index: Int) {"))
            .let { it.substring(0, it.indexOf("fun ", 10)) }
        assertTrue(
            "onPage 三行刀在场：source is PlayQueueFeedSource → notePlayQueueResume",
            onPage.contains("source is PlayQueueFeedSource) notePlayQueueResume(item)"),
        )
        // 记账门=同条不重写（setResume 变更位）+反查失联跳过
        val note = part().substring(part().indexOf("fun FeedViewModel.notePlayQueueResume"))
        assertTrue("切源守卫（迟到页不记账）", note.contains("source as? PlayQueueFeedSource ?: return"))
        assertTrue("反查失联跳过（不硬记）", note.contains("entryIdOf(item) ?: return"))
        assertTrue("变更位门（同条不重写省落盘）", note.contains("setResume(entryId, item.key)"))
    }

    @Test
    fun `loadMore两处点名豁免_队列序保全闭环`() {
        val text = vm()
        // ①重发折叠：PlayQueueFeedSource 进显式清单豁免名单（前任遗言落点）
        val foldable = text.substring(text.indexOf("val foldable ="))
            .let { it.substring(0, it.indexOf("\n", it.indexOf("src !is PlayQueueFeedSource"))) }
        assertTrue(
            "foldable 名单含 PlayQueueFeedSource（显式清单语境：折叠会让清单条数与记录数对不上）",
            foldable.contains("src !is PlayQueueFeedSource"),
        )
        // ②P1.5/P2 页内后处理：queueSrc 门包住两调用点（笔2 补勘新实锤的落点）
        val p15Line = Regex("val rankedP15 = if \\(queueSrc\\) ranked else applyP15PostRank\\(ranked, pageWeights\\)")
        val p2Line = Regex("val rankedP2 = if \\(queueSrc\\) rankedP15 else applyP2SequenceRank\\(rankedP15\\)")
        assertEquals("P1.5 调用点被 queueSrc 门包住", 1, p15Line.findAll(text).count())
        assertEquals("P2 调用点被 queueSrc 门包住", 1, p2Line.findAll(text).count())
        assertTrue("queueSrc 判别式在场", text.contains("val queueSrc = src is PlayQueueFeedSource"))
    }

    @Test
    fun `自含块_落盘串流conflate语义_冷启动载入`() {
        val text = vm()
        assertTrue("playQueueModel 状态声明", text.contains("internal val playQueueModel = PlayQueueModel()"))
        assertTrue("_playQueue 状态发布", text.contains("internal val _playQueue = MutableStateFlow(PlayQueue())"))
        // R391 保义翻新：串流负载升 PlayStationsState（全量台列表态；conflate
        // 语义原样——DROP_OLDEST=磁盘只关心最新态）
        assertTrue(
            "落盘串流 conflate 语义（DROP_OLDEST=磁盘只关心最新态）",
            text.contains("_playQueuePersist = MutableSharedFlow<PlayStationsState>(") &&
                text.contains("kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST"),
        )
        // R391 保义翻新：冷启动 v2 化（loadPlayStations→活跃台装载，activeId 失联回落第一台）
        assertTrue(
            "冷启动载入（IO 读盘→主线程模型恢复）",
            text.contains("store.loadPlayStations()") && text.contains("playQueueModel.replaceAll(active ?: PlayQueue())"),
        )
        // 委托面十口在场（UI 消费口）
        for (fn in listOf(
            "enqueueChannelToPlayQueue", "enqueueItemToPlayQueue", "enqueueWatchLaterToPlayQueue",
            "removeFromPlayQueue", "movePlayQueueEntry", "movePlayQueueEntryToTop",
            "togglePlayQueueSkip", "setPlayQueueOrder", "clearPlayQueue", "openPlayQueue",
        )) {
            assertTrue("委托 $fn 在场", Regex("fun $fn\\(").containsMatchIn(text))
        }
    }

    @Test
    fun `断点链锚_6s有界_迟到守卫_置题`() {
        val text = part()
        assertTrue(
            "断点跳回 6s 有界等待（contextJumpKey 6s 同口径）",
            text.contains("withTimeoutOrNull(6_000)"),
        )
        assertTrue("迟到定位守卫（代次纪律）", text.contains("if (source !== src)"))
        assertTrue("断点定位走 resumeScroll 既有通道（FeedScreen 零改复用）", text.contains("_resumeScroll.tryEmit(resumeKey)"))
        assertTrue("开单置题（V 票①口径：feed 顶栏「播放队列」）", text.contains("sourceTitle.value = queue.name"))
        assertTrue(
            "从头播=清断点（resetResume+落盘）",
            text.contains("if (fromStart && playQueueModel.resetResume())"),
        )
        // 开单线程语义：主线程切源+IO 查表（openPlaylist/openBlindBox 同款）
        assertTrue("主线程切源（_mediaTypeTab=-1+switchSource）", text.contains("_mediaTypeTab.value = -1"))
        assertTrue("HEAT 查表失败静默回落空表（诚实降级）", text.contains("channelMemberCountsForSortImpl"))
    }

    // ---------------- ③ 入口锚（路由+两入口） ----------------

    @Test
    fun `队列页路由三级接线`() {
        val app = src("dev/tgfeed/App.kt")
        assertTrue("App 容器状态（镜像 showPlaylists 模式）", app.contains("val showPlayQueue: StateFlow<Boolean> = _showPlayQueue"))
        val main = src("dev/tgfeed/MainActivity.kt")
        assertTrue(
            "MainActivity when 分支（showPlaylists 之后=R305 层级先例）",
            main.indexOf("showPlayQueue -> dev.tgfeed.ui.QueueScreen(") > main.indexOf("showPlaylists -> dev.tgfeed.ui.PlaylistsScreen("),
        )
        assertTrue("overlayPageOpen 门控加位", main.contains("showPlayQueue ||"))
        val mine = src("dev/tgfeed/ui/MineScreen.kt")
        // R391 保义翻新：入口行升级「播放台」（一期「播放队列」行的二期位；参数名保留）
        assertTrue("我的页入口行（播放列表旁语义组）", mine.contains("onOpenPlayQueue: () -> Unit = {},") && mine.contains("title = \"播放台\""))
        val queueScreen = src("dev/tgfeed/ui/QueueScreen.kt")
        assertTrue(
            "队列页四档 chips+共享 VM 网关 key",
            queueScreen.contains("PlayQueueOrder.values().forEach") &&
                queueScreen.contains("key = \"gw\" + System.identityHashCode(gateway)"),
        )
        assertTrue(
            "拖拽换序接线（QueueDragState+movePlayQueueEntry）+itemCount 直读活值",
            queueScreen.contains("vm.movePlayQueueEntry(from, to)") &&
                queueScreen.contains("vm.playQueue.value.entries.size"),
        )
    }

    @Test
    fun `两入口格钮_feed腿内建兜底_星图腿入单`() {
        val sheet = src("dev/tgfeed/ui/components/ActionSheet.kt")
        assertTrue(
            "feed 腿「加入队列」格（QueueMusic 图标+行 1 动作格）",
            sheet.contains("Icons.Outlined.QueueMusic, \"加入队列\""),
        )
        assertTrue(
            "内建兜底 VM（blockFallbackVm 同款：同 key 共享实例）",
            sheet.contains("queueFallbackVm") && sheet.contains("System.identityHashCode(queueGateway)"),
        )
        assertTrue(
            "目标=当前停留页条目（屏蔽频道兜底同口径）",
            sheet.contains("vm?.items?.value?.getOrNull(vm.currentIndex.value)"),
        )
        val taste = src("dev/tgfeed/ui/TasteMapScreen.kt")
        val starBtn = taste.substring(taste.indexOf("private fun StarCardButtons("))
            .let { it.substring(0, it.indexOf("\n}", it.indexOf("\"入单\""))) }
        assertTrue(
            "星图腿「入单」钮（enqueueChannelToPlayQueue+幂等 Toast）",
            starBtn.contains("vm.enqueueChannelToPlayQueue(st.chatId, st.title.ifBlank { \"频道\" })"),
        )
    }

    // ---------------- ④ 红线负锚（播放链/搜索排序/片单链路零碰） ----------------

    @Test
    fun `红线负锚_播放链与搜索排序与片单链路零碰`() {
        val listenQueue = src("dev/tgfeed/core/playback/ListenQueue.kt")
        assertTrue(
            "ListenQueue.kt 零 PlayQueue 引用（播放链零碰判词——队列只是 items 流新供给方）",
            !listenQueue.contains("PlayQueue"),
        )
        val searchSort = src("dev/tgfeed/core/feed/SearchSort.kt")
        assertTrue("SearchSort.kt 零 PlayQueue 引用（搜索排序零碰只复用先例）", !searchSort.contains("PlayQueue"))
        val playlistPart = src("dev/tgfeed/core/feed/FeedPlaylistPart.kt")
        assertTrue(
            "FeedPlaylistPart 零 PlayQueue 引用（片单链路原样）",
            !playlistPart.contains("PlayQueue"),
        )
        // 稍后再看按钮/源零碰：watchlater 既有链路只被入单桥单向消费
        val watchLaterImpl = vm().substring(vm().indexOf("fun showWatchLater()"))
            .let { it.substring(0, it.indexOf("\n", it.indexOf("}"))) }
        assertTrue(
            "既有稍后再看源零碰（showWatchLater 原样）",
            watchLaterImpl.contains("showWatchLaterImpl()"),
        )
        val partText = part()
        assertTrue(
            "入单桥只单向消费 loadWatchLater（不写不删 watchlater.json）",
            partText.contains("store.loadWatchLater()") && !partText.contains("saveWatchLater"),
        )
    }
}
