package dev.tgfeed.core.feed

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.PowerManager
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.tgfeed.MainActivity
import dev.tgfeed.core.playback.PlayerPool
import dev.tgfeed.core.prefs.AppPrefs
import dev.tgfeed.core.store.BlockedItem
import dev.tgfeed.core.store.ChannelExposureLedger
import dev.tgfeed.core.store.DownloadRecord
import dev.tgfeed.core.store.LocalStore
import dev.tgfeed.core.store.PlaylistMeta
import dev.tgfeed.core.store.PlayStationsState
import dev.tgfeed.core.store.Subscription
import dev.tgfeed.core.store.WatchEvent
import dev.tgfeed.core.td.ChannelBrief
import dev.tgfeed.core.td.FeedRequest
import dev.tgfeed.core.td.GatewayState
import dev.tgfeed.core.td.MockTdGateway
import dev.tgfeed.core.td.SearchTextKit
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.core.td.fetchAggregatedMediaAllFast
import dev.tgfeed.core.td.warmupForPlayback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import org.json.JSONObject

class FeedViewModel(
    internal val gateway: TdGateway,
    internal val app: Application,
) : ViewModel() {

    // ── 初始化顺序守卫（R123 立约，勿违反）──
    // 本类有 20+ 个 init 块，多数在构造期向 viewModelScope 点火 IO 协程；协程体里
    // 读到的字段必须先于该 init 块声明，否则构造竞态 NPE（fresh install 演示模式
    // 必现，R86 实测复现过两次：_channelCardMeta、ledgerMutex；progressSync 的
    // Lazy 委托同款教训）。维护规则：
    //  1. 新增 init 块前，先确认它（含其协程体、其调用的 Impl 函数链）触达的每个
    //     字段都已声明在本 init 块之前；不满足就把字段上移，别赌时序。
    //  2. 新增字段若会被任何既有 init 协程间接触达，声明位置必须前移到所有相关
    //     init 块之前。
    //  3. 已核实的顺序敏感点（就近有守卫注释）：_channelCardMeta、ledgerMutex、
    //     progressSync、crawlBudget→deepIndex。
    // 评估过收敛成 suspend fun start() 显式编排：构造期点火改为外部调用会改变
    // 各协程的就绪时序（首屏预载/恢复定位等都吃构造即启动语义），行为无法等价，
    // 故保留 init 块模式、以本约 + 就近守卫注释管顺序。

    /** 本地持久化层（收藏 / 点赞 / 观看历史） */
    internal val store = LocalStore(app)

    // ---- 广场公共 API 委托（实现见 FeedPlazaPart.kt） ----
    fun plazaPostLike(item: VideoItem) = plazaPostLikeImpl(item)
    fun plazaPostDanmaku(item: VideoItem, text: String) = plazaPostDanmakuImpl(item, text)
    fun plazaPostWatching(item: VideoItem) = plazaPostWatchingImpl(item)
    fun plazaPostChannelRecommend(chatId: Long) = plazaPostChannelRecommendImpl(chatId)
    fun plazaPostGraphEdges(edges: List<Pair<Long, Long>>) = plazaPostGraphEdgesImpl(edges)
    fun plazaPostChannelBlock(chatId: Long, reason: String) = plazaPostChannelBlockImpl(chatId, reason)
    fun plazaTrending(): List<Pair<String, Int>> = plazaTrendingImpl()
    fun refreshPlazaBoard(forceFetch: Boolean = false) = refreshPlazaBoardImpl(forceFetch)
    fun startPlazaPolling() = startPlazaPollingImpl()

    internal val plazaService by lazy {
        (gateway as? dev.tgfeed.core.td.TdLibGateway)?.let {
            dev.tgfeed.core.plaza.PlazaService(it, dev.tgfeed.core.prefs.AppPrefs(app))
        }
    }
    /** 广场快照：key→在看数/点赞数/弹幕列表；null = 未加载或未开启 */
    internal val _plaza = MutableStateFlow<dev.tgfeed.core.plaza.PlazaSnapshot?>(null)
    val plaza: StateFlow<dev.tgfeed.core.plaza.PlazaSnapshot?> = _plaza.asStateFlow()

    internal val _plazaBoard = MutableStateFlow<List<PlazaBoardEntry>>(emptyList())
    val plazaBoard: StateFlow<List<PlazaBoardEntry>> = _plazaBoard.asStateFlow()

    /** 热榜解析中标记（兼作防重入闸——解析中重复触发直接忽略） */
    internal val _plazaBoardLoading = MutableStateFlow(false)
    val plazaBoardLoading: StateFlow<Boolean> = _plazaBoardLoading.asStateFlow()

    // ---- TL-9 弹幕时间旅行（R205；实现见 FeedPlazaPart.kt 的 refreshPlazaDanmakuTimelineImpl） ----
    /** 弹幕时间轴回放状态（loading + 时间轴本体；纯回放不轮询，进回放面板触发一次） */
    internal val _plazaDanmakuTimeline = MutableStateFlow(DanmakuTimelineUiState())
    val plazaDanmakuTimeline: StateFlow<DanmakuTimelineUiState> = _plazaDanmakuTimeline.asStateFlow()

    /** TL-9 弹幕时间轴刷新（回放数据口；防重入在实现内） */
    fun refreshPlazaDanmakuTimeline() = refreshPlazaDanmakuTimelineImpl()

    internal val plazaEdgeBuffer = java.util.concurrent.ConcurrentLinkedQueue<Pair<Long, Long>>()

    init {
        // R52 冷启动打点：VM 创建时刻 ≈ FeedScreen 首次组合起点
        dev.tgfeed.core.diag.ColdStartProbe.mark("FeedViewModel 创建（FeedScreen 组合起点）")
        // 图谱新边挂点：DiscoveryGraph 只在"新建边"时回调（命中累加不触发）。
        // 监听契约要求轻量非阻塞——这里只做抽样 + 入队 + 满批时转发（转发自身
        // 走 viewModelScope 异步，真正的 30s 全局限速在 PlazaService.postGraphEdges）
        (gateway as? TdLibGateway)?.discoveryGraph?.newEdgeListener = { from, to, _ ->
            onNewGraphEdgeForPlaza(from, to)
        }
        // P4-A 金矿钩子挂接：GroupHarvester 入库点的一行钩子（GoldmineHooks.onHarvested）
        // 据此拿到落盘目录与图谱只读引用；Mock 网关不挂接，钩子全程空转
        (gateway as? TdLibGateway)?.let { GoldmineHooks.attach(app.filesDir, it.discoveryGraph) }
        // R211 直荐自动上屏：直荐注册表新条目（用户勾选群 USER 车道验证过的频道）
        // → 节流补刷一轮发现页（用户停在发现页时候选自动落地，不等手动刷新/换一批）。
        // 节流 90s：直荐一轮最多 3 条、收割 15min 一轮——密集注册窗也刷不出风暴；
        // refreshDiscoveryImpl 自带 _discoveryLoading 防重入闸，与在飞刷新互斥。
        // R377 懒门：背景补刷走网络臂关（allowNetworkArm=false）——纯本地聚合，
        // 直荐验证轮本身已在门内常驻循环收编，背景刷新不再另烧搜索族。
        (gateway as? TdLibGateway)?.let { real ->
            real.groupHarvester.groupRecommend.registerListener { count ->
                val now = System.currentTimeMillis()
                val last = recommendRefreshThrottle.get()
                if (now - last < RECOMMEND_REFRESH_MIN_INTERVAL_MS) return@registerListener
                if (!recommendRefreshThrottle.compareAndSet(last, now)) return@registerListener
                android.util.Log.d("FeedViewModel", "R211 直荐 +$count，节流补刷发现页（距上次 ${now - last}ms，网络臂关）")
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching { refreshDiscoveryImpl(allowNetworkArm = false) }
                        .onFailure { android.util.Log.w("FeedViewModel", "R211 直荐补刷失败（下轮正常刷新兜底）: ${it.message}") }
                }
            }
        }
    }

    /** R211 直荐补刷节流戳（毫秒；0=从未——首条直荐必然放行） */
    internal val recommendRefreshThrottle = java.util.concurrent.atomic.AtomicLong(0)

    /** R211 直荐补刷最小间隔（毫秒）：两轮直荐间至少隔 90s，防注册窗刷新风暴 */
    private companion object {
        const val RECOMMEND_REFRESH_MIN_INTERVAL_MS = 90_000L
    }

    // 广场轮询：开启时每 60s 拉一次广场快照（在看数/点赞数/弹幕）
    internal val plazaPollerStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    internal val _items = MutableStateFlow<List<VideoItem>>(emptyList())
    val items: StateFlow<List<VideoItem>> = _items.asStateFlow()

    /**
     * R52 首屏快照上屏标记（"缓存"标的数据源）：true = 当前列表头部是上次退出前
     * 聚合首页的落盘快照，网络首拉的首个结论轮收尾后归位 false；切源/刷新立即归位。
     * 本版不碰 FeedScreen（邻任务地盘），状态先行外露，UI 角标由后续任务接线。
     */
    internal val _feedSnapshotActive = MutableStateFlow(false)
    val feedSnapshotActive: StateFlow<Boolean> = _feedSnapshotActive.asStateFlow()

    /**
     * R52 当前快照条目的 key 集（seed 时登记，切源/刷新清空）：快照落盘时据此把
     * 本轮网络新货排前、旧快照条目垫底——防"快照存的还是上次那批"越用越陈。
     */
    @Volatile
    internal var snapshotKeys: Set<String> = emptySet()

    /**
     * R170b 可复用预热协调器：后台低优先级预热（关注流快照/tier 刷新/后续可扩展）
     * 的错峰+优先级+去重+静默重试统一收口（用户设计原则：扫描不暴露给用户）。
     * 随 viewModelScope 生灭；任务提交见 init 块。
     */
    internal val warmup = WarmupCoordinator(viewModelScope)

    init {
        // R52 冷启动秒上屏：先把上次退出前聚合首页第一屏的落盘快照摆进列表
        //（对标抖音/头条冷启动：先出缓存内容，新货随后接续）。
        // 注意"接续"而非"整表替换"：分页器按 index 键控，整表替换会把在播页的画面
        // 撕掉重来；快照垫底 + 新货经 loadMore 既有去重链接在后面，播放零打断。
        // 全程 IO 线程；坏快照/网关模式不匹配/网络比磁盘先到货一律静默跳过。
        viewModelScope.launch(Dispatchers.IO) {
            // R52b：快照数据优先吃进程级预读（App.onCreate 即起、与 TDLib 初始化并行）；
            // 预读未就绪（授权 Ready 太快）回落锁外读盘——loadFeedSnapshot 已无锁，
            // 不再排 VM init 各预载协程共用同一把 store 锁的车队（平板实测车队 3.6s）
            val t0 = android.os.SystemClock.elapsedRealtime()
            val preload = (app as? dev.tgfeed.App)?.container?.feedSnapshotPreload
            val snap = preload ?: runCatching { store.loadFeedSnapshot() }.getOrNull()
            dev.tgfeed.core.diag.ColdStartProbe.mark(
                "快照数据就绪（来源=${if (preload != null) "进程预读" else "锁外读盘"}，" +
                    "耗时 ${android.os.SystemClock.elapsedRealtime() - t0}ms）",
            )
            if (snap == null) return@launch
            val wantMode = if (gateway is MockTdGateway) "demo" else "real"
            if (snap.mode != wantMode) return@launch
            // 首拉可能已先于磁盘读到货：列表非空则快照让位，照常追新
            if (_items.value.isNotEmpty()) return@launch
            // R94：快照上屏前先过已看底账（修"冷启动首屏又出现上次看过的"）——
            // 快照存的是"上次首拉到货时的列表头"，其中条目随后多已被看完入底账；
            // 不过滤则每次冷启动首屏必重播旧片。整页皆已看 = 等同无快照，交给首拉
            val (snapSeenK, snapSeenU) = runCatching { store.loadSeenIdentities() }
                .getOrDefault(emptySet<String>() to emptySet())
            // R390 冷启主快照同口径（与 R170b 关注流快照垫底同一病类——发现一个修
            // 一类）：类型/筛选对齐谓词补齐。冷启时 _mediaTypeTab/_filter 为默认值
            // （全部 tab/无筛选），设置页类型开关持久生效（关「图片」后冷启快照
            // 不再冒图）；屏蔽/不感兴趣集合由 init 各预载协程供给，与快照协程并发
            // 时按当时在场值放行（维度与修前一致，如实记——本刀主疗效=类型过滤，
            // 确定性来自 prefs 持久层与 tab 状态）。
            val typePrefsR390 = dev.tgfeed.core.prefs.AppPrefs(app)
            // R413：关键词屏蔽同口径（词表由 init 预载协程供给，未到位时按空表放行——同屏蔽集合）
            val kwR413 = keywordMatcher()
            val snapFresh = snap.items.filter {
                !kwR413.hits(it) && it.key !in snapSeenK && (it.fileUniqueId.isEmpty() || it.fileUniqueId !in snapSeenU) &&
                    SnapshotReplayPolicy.typeAligned(
                        it,
                        _mediaTypeTab.value,
                        typePrefsR390.showVideos,
                        typePrefsR390.showPhotos,
                        typePrefsR390.showAudio,
                        _filter.value,
                    )
            }
            if (snapFresh.isEmpty()) {
                dev.tgfeed.core.diag.ColdStartProbe.mark("首屏快照全为已看，跳过（${snap.items.size} 条滤净）")
                return@launch
            }
            _items.value = snapFresh
            snapshotKeys = snapFresh.mapTo(HashSet()) { it.key }
            _feedSnapshotActive.value = true
            // R324 离线降级：快照落盘时点入流（离线 banner「N 小时前的快照」的诚实
            // 原料；旧档缺 ts=0=无时点不带标注——零编造红线）
            _snapshotSavedAtMs.value = snap.savedAtMs
            dev.tgfeed.core.diag.ColdStartProbe.mark("首屏快照上屏（${snapFresh.size} 条缓存）")
            // R145 并行化：换发（视口条目逐条 GetMessage 换新 fileId）与首拉点火
            // 从串行改为并行——旧序"3 条换发走完才点火"，Ready 后每秒都在等网络
            // 往返；两者互不依赖（换发改的是同 key 条目的 fileId，首拉的种子选取/
            // 去重/排序全用 key 与 chatId，不受换发影响），快车道再省一段串行等待。
            // 快照条目的 fileId 跨进程已失效：前几条先发制人换发本会话新 fileId
            //（GetMessage 按 chatId+messageId 反解，与收藏复活同一口径），覆盖视口与
            // 预载位——播到时直接走正常链路，不吃"起播失败重试才反解"的亏。
            // 换发成功即触发头块预热（R145 A2：首条 p32/其余 p16 预拉 2MB 头——
            // offset=0 与后续整载同锚点，TDLib 侧自然合并无互杀），播放器开流时
            // moov+首块大概率已在本地缓存，起播不等网络。
            val real = gateway as? TdLibGateway
            val reviveJob = if (real != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    snapFresh.take(SNAPSHOT_REVIVE_AHEAD).forEachIndexed { idx, s ->
                        val revived = runCatching { reviveOne(s, real) }.getOrNull() ?: return@forEachIndexed
                        _items.update { list -> list.map { if (it.key == revived.key) revived else it } }
                        if (revived.fileId >= 0) {
                            val warmPriority = if (idx == 0) 32 else 16
                            runCatching { real.warmupForPlayback(revived.fileId, warmPriority) }
                        }
                    }
                }
            } else {
                null
            }
            // 快照上屏不等于放弃追新：补一发首拉（FeedScreen 的兜底 loadMore 见列表
            // 非空会跳过，这里必须自己点火；loadMore 的 _loading 闸保证不与 UI 路径双拉）。
            // R52b 修复（双保险原样保留，与换发并行后依然成立）：
            //  1) 显式 Ready 闸：等授权 Ready 再点火（换发不再挡在前面，本闸独立
            //     兜底，Mock/换发被跳过的直通路同样被压到 Ready 之后）；
            //  2) 再经主线程派发点火：VM 构造跑在主线程，主线程派发必然排在构造完成
            //     之后——即便极端时序（Ready 早于 VM 创建、协程抢到构造中途）也不存在
            //     读未初始化字段的窗口（R52b 曾实测 NPE 崩启动，此为双保险之二）。
            if (gateway.state.value !is GatewayState.Ready) {
                runCatching { gateway.state.first { it is GatewayState.Ready } }
            }
            withContext(Dispatchers.Main) { loadMore() }
            reviveJob?.join()
        }
    }

    internal val _channels = MutableStateFlow<List<ChannelBrief>>(emptyList())
    val channels: StateFlow<List<ChannelBrief>> = _channels.asStateFlow()

    // ---- 内容源切换/相似频道/筛选公共 API 委托（实现见 FeedSourcePart.kt） ----
    fun loadSimilar(channelId: Long) = loadSimilarImpl(channelId)
    fun clearSimilar() = clearSimilarImpl()
    fun setMediaTypeTab(t: Int) = setMediaTypeTabImpl(t)
    // R189 程序复位路（试吃/热榜/深链等跳转点的「防过滤吃掉目标」复位——不碰快照表，
    // 语义=旧行为逐字保留；与用户导航路 setMediaTypeTab 分流）
    fun resetMediaTypeTab(t: Int) = resetMediaTypeTabImpl(t)
    fun setFilter(f: FeedFilter) = setFilterImpl(f)
    fun switchSource(newSource: FeedSource) = switchSourceImpl(newSource)
    fun showFavorites() = showFavoritesImpl()
    fun showWatchLater() = showWatchLaterImpl()
    fun showHistory() = showHistoryImpl()
    fun showAggregate() = showAggregateImpl()
    fun showSubscriptions() = showSubscriptionsImpl()
    fun showFollowing() = showFollowingImpl() // R154 关注流：订阅频道的完整内容场
    fun showCollection(c: ChannelCollection) = showCollectionImpl(c)
    fun createCollection(name: String, channelIds: List<Long>) = createCollectionImpl(name, channelIds)
    fun deleteCollection(name: String) = deleteCollectionImpl(name)

    /** 最近一次"找相似"的结果列表；clearSimilar / 新一轮 loadSimilar 时清空 */
    internal val _similarChannels = MutableStateFlow<List<ChannelBrief>>(emptyList())
    val similarChannels: StateFlow<List<ChannelBrief>> = _similarChannels.asStateFlow()

    /** "找相似"加载中标记（对话框转圈用） */
    internal val _similarLoading = MutableStateFlow(false)
    val similarLoading: StateFlow<Boolean> = _similarLoading.asStateFlow()

    internal val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /**
     * 首轮内容加载已完成（成功或失败都算——语义是"首轮有结论了"）。
     * 修"启动闪空态"：Feed 空态（零频道引导卡/重温提示）在首轮结论前不许展示，
     * 否则网关 Ready → 首页第一页到达之间的窗口会闪一下空态卡片。
     */
    internal val _initialContentReady = MutableStateFlow(false)
    val initialContentReady: StateFlow<Boolean> = _initialContentReady.asStateFlow()

    /**
     * EDGE-HIGH-9 拉页代次：loadMore 每开一轮 +1，switchSource/refresh 也 +1（作废旧轮）。
     * 旧轮协程在写回/放闸前比对代次，不等即静默退出——防"旧源结果灌进新源列表"
     * 与"旧轮收尾误放 _loading 闸导致同源双拉并发"。
     */
    internal val loadGen = java.util.concurrent.atomic.AtomicInteger(0)

    /** R52 冷启动打点闸：首内容到货 / 首轮结论各只打一次（进程粒度） */
    internal val firstContentMarked = java.util.concurrent.atomic.AtomicBoolean(false)
    internal val firstRoundMarked = java.util.concurrent.atomic.AtomicBoolean(false)

    internal val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // ---------------- 互动状态（LocalStore 持久化） ----------------

    internal val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    internal val _likes = MutableStateFlow<Set<String>>(emptySet())
    val likes: StateFlow<Set<String>> = _likes.asStateFlow()

    internal val _downloads = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadStatus>> = _downloads.asStateFlow()

    // ---------------- R196 存储管理（设置页「存储管理」卡数据面；实现在 FeedStoragePart.kt） ----------------

    /** 存储概览（null=还没量过；进设置页/手动清理后 [refreshStorageOverview] 刷新） */
    internal val _storageOverview = MutableStateFlow<dev.tgfeed.core.storage.StorageOverview?>(null)
    val storageOverview: StateFlow<dev.tgfeed.core.storage.StorageOverview?> = _storageOverview.asStateFlow()

    /** 清理日志一行（上次清理时间/释放量；prefs 单源，governor 落盘） */
    internal val _storageCleanLog = MutableStateFlow(CLEAN_LOG_PLACEHOLDER)
    val storageCleanLog: StateFlow<String> = _storageCleanLog.asStateFlow()

    // ---------------- 订阅追更 / 播放位置 ----------------

    /** 已订阅频道列表（LocalStore 持久化，含追更进度） */
    internal val _subscriptions = MutableStateFlow<List<ChannelBrief>>(emptyList())
    val subscriptions: StateFlow<List<ChannelBrief>> = _subscriptions.asStateFlow()

    /** 当前播放页下标（onPage 时更新，供分屏/连播 UI 定位） */
    internal val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    // ---------------- 记忆播放：条目位置持久化 + 会话内恢复（仅本进程） ----------------

    /** 最后浏览条目的 item.key 落盘节流：同 key 不重复写（feed_resume.json） */
    @Volatile
    internal var lastViewedKey: String? = null

    /** 待恢复的条目 key（init 从 feed_resume.json 读入，只认本会话标记；恢复尝试后清空） */
    @Volatile
    internal var pendingResumeKey: String? = null

    /** 磁盘预载（progressMap + lastViewedKey）是否完成：恢复定位必须等它就绪 */
    @Volatile
    internal var resumeReady = false

    /** 恢复定位只试一次（进程重启后的首屏）；切源/刷新也置位，防旧源位置误恢复到新源 */
    @Volatile
    internal var positionRestored = false

    /**
     * 恢复定位成功的一次性滚动事件（条目 key）：pager 可能已用 initialPage=0 创建，
     * UI collect 后自行 indexOfFirst + scrollToPage；缓冲 1 发，pager 晚建也能收到。
     */
    internal val _resumeScroll = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val resumeScroll: SharedFlow<String> = _resumeScroll.asSharedFlow()

    /**
     * 收藏的完整条目（与 [_favorites] 的 key 一一对应），收藏时间倒序。
     * 用不可变 List 整体替换，避免跨线程读写可变集合。
     */
    @Volatile
    internal var favoriteItems: List<VideoItem> = emptyList()

    /** 观看历史节流：最近一次写入历史的 key，同 key 不重复写 */
    @Volatile
    internal var lastHistoryKey: String? = null

    // ---------------- 长按面板状态 ----------------

    /** 应用配置（自动连播开关落盘处） */
    internal val prefs = AppPrefs(app)

    /** 被屏蔽的频道 id 集合（LocalStore 持久化） */
    internal val _blockedChannels = MutableStateFlow<Set<Long>>(emptySet())
    val blockedChannels: StateFlow<Set<Long>> = _blockedChannels.asStateFlow()

    /** "不感兴趣"的 item.key 集合（视频粒度黑名单，区别于频道级屏蔽；LocalStore 持久化） */
    internal val _dislikedKeys = MutableStateFlow<Set<String>>(emptySet())
    val dislikedKeys: StateFlow<Set<String>> = _dislikedKeys.asStateFlow()

    /** R413 用户屏蔽词集合（LocalStore muted_keywords.json 持久化，插入序） */
    internal val _mutedKeywords = MutableStateFlow<Set<String>>(emptySet())
    val mutedKeywords: StateFlow<Set<String>> = _mutedKeywords.asStateFlow()

    /** R413 关键词屏蔽是否同时匹配频道名（AppPrefs 持久化，默认关） */
    internal val _muteMatchChannelTitle = MutableStateFlow(false)
    val muteMatchChannelTitle: StateFlow<Boolean> = _muteMatchChannelTitle.asStateFlow()

    /** R413 预编译匹配器缓存（词表/开关变更时重建；读侧 [keywordMatcher]） */
    @Volatile internal var keywordMatcherCache: Pair<Pair<Set<String>, Boolean>, KeywordMutePolicy.Matcher>? = null
    /**
     * 永久屏蔽表（blocked_items.json：连续 ≥[PERMANENT_BLOCK_THRESHOLD] 次"不感兴趣"的
     * 内容指纹黑名单）。与 disliked（单次、按 item.key 挡单条消息）并存：
     * 本表按 fileUniqueId 挡内容——同一广告换消息重发/被转发到别的频道也永不出现。
     * 同时是"屏蔽管理"页的数据源。
     */
    internal val _blockedItems = MutableStateFlow<List<BlockedItem>>(emptyList())
    val blockedItems: StateFlow<List<BlockedItem>> = _blockedItems.asStateFlow()

    /** 稍后再看的 item.key 集合（完整条目落盘，模式同收藏） */
    internal val _watchLater = MutableStateFlow<Set<String>>(emptySet())
    val watchLater: StateFlow<Set<String>> = _watchLater.asStateFlow()

    /** 自动连播开关（默认 true，存 AppPrefs） */
    internal val _autoAdvance = MutableStateFlow(true)
    val autoAdvance: StateFlow<Boolean> = _autoAdvance.asStateFlow()

    // ---------------- 自动连播"往回翻回看"豁免（修"往回翻被自动再弹走"） ----------------
    // 状态机（会话内存态，切源/刷新清零）：
    //  ① 某条播完触发自动翻页：noteAutoEjected 把它记入 autoEjectedKeys，并记"计划落点"key；
    //  ② onPage 落定：落点 == 计划落点 → 自动连播落子，清标记不消费（不算手动翻回）；
    //     其余落定（手动翻页/被用户打断的连播动画）命中 autoEjectedKeys → 消费该记录并
    //     转入 replayExemptKeys（"翻回即消费"）；
    //  ③ replayExemptKeys 会话内 sticky：命中条再播完绝不自动跳页（VideoPage 原地归零
    //     定格待点播；图/音卡不自动跳页）——手动回看过一次的条，本会话永不二次弹走。
    internal val autoAdvanceLock = Any()
    internal val autoEjectedKeys = HashSet<String>()
    internal val replayExemptKeys = HashSet<String>()
    internal var autoAdvancePlannedKey: String? = null

    // ---- 互动动作/睡眠定时/数据管理公共 API 委托（实现见 FeedActionsPart.kt） ----
    fun noteAutoEjected(ejectedKey: String, plannedTargetKey: String?) = noteAutoEjectedImpl(ejectedKey, plannedTargetKey)
    fun isAutoAdvanceExempt(key: String): Boolean = isAutoAdvanceExemptImpl(key)
    fun toggleBlockChannel(chatId: Long) = toggleBlockChannelImpl(chatId)
    fun toggleNotInterested(item: VideoItem) = toggleNotInterestedImpl(item)
    fun unblockItem(identity: String) = unblockItemImpl(identity)
    fun toggleWatchLater(item: VideoItem) = toggleWatchLaterImpl(item)
    fun removeWatchLaterBatch(keys: Set<String>) = removeWatchLaterBatchImpl(keys)
    fun setAutoAdvance(v: Boolean) = setAutoAdvanceImpl(v)
    fun reviveItems() = reviveItemsImpl()
    fun setSleepTimer(minutes: Int?) = setSleepTimerImpl(minutes)
    fun clearHistory() = clearHistoryImpl()
    fun removeHistory(key: String) = removeHistoryImpl(key)
    fun clearDisliked() = clearDislikedImpl()
    fun recordSearch(term: String) = recordSearchImpl(term)
    fun setExcludeWatched(v: Boolean) = setExcludeWatchedImpl(v)
    fun unblockChannel(chatId: Long) = unblockChannelImpl(chatId)

    // ---- R413 关键词屏蔽公共 API（实现见 FeedKeywordMutePart.kt） ----
    /** 添加屏蔽词；返回 null=成功（附本次剔除条数经 onRemoved 回调），非 null=拒绝原因 */
    fun addMutedKeyword(raw: String, onRemoved: (Int) -> Unit = {}): String? = addMutedKeywordImpl(raw, onRemoved)
    fun removeMutedKeyword(word: String) = removeMutedKeywordImpl(word)
    fun clearMutedKeywords() = clearMutedKeywordsImpl()
    fun setMuteMatchChannelTitle(v: Boolean) = setMuteMatchChannelTitleImpl(v)
    // ---- 点赞收藏公共 API 委托（实现见 FeedActionsPart.kt） ----
    fun toggleLike(item: VideoItem) = toggleLikeImpl(item)
    fun toggleFavorite(item: VideoItem) = toggleFavoriteImpl(item)
    fun removeFavorites(keys: Set<String>) = removeFavoritesImpl(keys)

    /** 定时关闭到点信号：缓冲 1 发不挂起，UI collect 后自行暂停/退出 */
    internal val _sleepNow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sleepNow: SharedFlow<Unit> = _sleepNow.asSharedFlow()

    /**
     * 稍后再看的完整条目（与 [_watchLater] 的 key 一一对应），加入时间倒序。
     * 用不可变 List 整体替换，避免跨线程读写可变集合。
     */
    @Volatile
    internal var watchLaterItems: List<VideoItem> = emptyList()

    /** 定时关闭的倒计时任务；null = 未设置 */
    @Volatile
    internal var sleepJob: Job? = null

    /**
     * 观看行为采集：正在计量的条目 + 心跳累计的内容消费毫秒；翻页/切源/刷新时结算。
     * P0-2 口径修正：second 曾是"进入时刻"（墙钟起表），暂停/看评论/亮屏挂机全被记成观看；
     * 现改为"已累计真实播放毫秒"——由 [watchHeartbeatTick] 每 [WATCH_HEARTBEAT_MS]
     * 确认主位播放器"真在播"（isPlaying）才累加，墙钟不再参与 watchSec 供给。
     * R110：每跳累计量乘当前播放倍速（内容消费口径），倍速播放完播率不再失真。
     */
    @Volatile
    internal var watchingAccum: Pair<VideoItem, Long>? = null

    // ---------------- 互动信号加分表（任务：likes/favorites/download 入频道权重） ----------------

    /**
     * 互动加分表：channelId → 加分值，单频道累计 clamp 到 [0, [INTERACTION_BOOST_CAP]]。
     *
     * 分层权重（P1 #1，CES 口径定标，来源：ALGO-RESEARCH §4 小红书 CES=赞×1 藏×1
     * 评×4 转×4 关注×8 + §1 TikTok「信号按强度分级」；任务口径：读完×1 / 点开·收藏×2 /
     * 评论·转发·分享×4 / 不感兴趣×-4）：
     *  基准单位 = [BOOST_LIKE]=0.15（沿用旧点赞值，历史落盘数据语义不突变）；
     *  点赞 +[BOOST_LIKE]（×1）/ 收藏 +[BOOST_FAVORITE]（×2）/
     *  下载成功 +[BOOST_DOWNLOAD]（×4，"深度保存"按分享级计）；
     *  评论/转发/分享 +[BOOST_COMMENT]/[BOOST_SHARE]（×4，常量预留，UI 接线即用）；
     *  不感兴趣 [BOOST_DISLIKE]（×-4，负 delta 当日即削既有光环，见 toggleNotInterested）；
     *  取消点赞/收藏对称回退对应分值（防反复点刷分，回退到 0 为止）。
     * 此外观看质量分档（WATCH_QUALITY_*，完播率 <25%/25~60%/60~95%/≥95% 四档）
     * 与会话内实时适应（sessionAdjust ×0.5/×1.3）也经本表/本表衍生口径进权重，
     * 分别见 applyWatchQualityBoost 与"会话内实时适应"区块。
     *
     * 落地取舍（边界约束：LocalStore/AppPrefs 本轮冻结）：
     * 互动信号不写 watch_events——该表结构与 appendWatchEvent 签名都不能加 kind 字段，
     * 写成完播事件会与真实完播混淆、污染观看比例；故在 VM 侧维护加分表，经
     * [decayedChannelWeights] 的 interactionBoost 参数注入权重。
     * 持久化用独立 SharedPreferences 文件 [BOOST_PREFS_NAME]（JSON map），
     * 不改 AppPrefs 类；读写均走本表内存副本 + IO 线程异步落盘。
     *
     * R75-D 时间衰减：账面附带各频道最后互动时刻（interactionBoostAt，同一 JSON
     * 的 {"v","t"} 形态落盘），一切消费方经 [effectiveInteractionBoost] 取衰减后
     * 有效值（30 天线性归 0）——修"boost 只增不减、三个月前的赞还在抬权重"。
     */
    @Volatile
    internal var interactionBoost: Map<Long, Double> = emptyMap()

    /**
     * R75-D 各频道最后一次互动时刻（epoch ms）：互动加分的时间衰减基准。
     * 旧版 boost 只增不减（撤销除外），三个月前的赞至今仍在抬权重——是"越看越推"
     * 通胀回路里唯一不随时间消退的分量。现读取时按距上次互动的时长线性衰减，
     * 超过 [BOOST_DECAY_WINDOW_MS]（30 天）衰减到 0。
     */
    @Volatile
    internal var interactionBoostAt: Map<Long, Long> = emptyMap()

    /** 互动加分的独立 prefs 句柄（lazy 首次访问才打开，不阻塞构造） */
    internal val boostPrefs by lazy {
        app.getSharedPreferences(BOOST_PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }

    init {
        // 启动时把互动加分表从磁盘预载到内存（IO 线程加载，不阻塞首帧）
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = runCatching {
                val raw = boostPrefs.getString(KEY_BOOST_MAP, null)
                    ?: return@runCatching null
                val obj = JSONObject(raw)
                val out = HashMap<Long, Double>()
                val outAt = HashMap<Long, Long>()
                var migrated = false
                val now = System.currentTimeMillis()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val id = k.toLongOrNull() ?: continue // 坏 key 跳过
                    // R75-D 新格式 {"v": 加分, "t": 最后互动时刻}；旧格式裸数字
                    // 迁移宽限：t 记为此刻（老数据再享一个完整衰减周期，不突变清零）
                    val entry = obj.optJSONObject(k)
                    val v: Double
                    val t: Long
                    if (entry != null) {
                        v = entry.optDouble("v", Double.NaN)
                        t = entry.optLong("t", now)
                    } else {
                        v = obj.optDouble(k, Double.NaN)
                        t = now
                        migrated = true
                    }
                    if (v.isNaN() || v <= 0.0) continue // 非正分值无意义
                    out[id] = v.coerceAtMost(INTERACTION_BOOST_CAP)
                    outAt[id] = t
                }
                Triple(out.toMap(), outAt.toMap(), migrated) // 拷不可变快照，防外部持有可变表
            }.getOrNull()
            if (loaded != null) {
                interactionBoost = loaded.first
                interactionBoostAt = loaded.second
                // 旧格式迁移落盘一次：时刻钉住，否则每次冷启动都重新宽限 30 天
                if (loaded.third) persistInteractionBoost()
                // R75-D 衰减观测：老互动（>1 天）原始合计 vs 衰减后有效合计
                val rawSum = loaded.first.values.sum()
                val eff = effectiveInteractionBoost()
                val aged = loaded.second.count { System.currentTimeMillis() - it.value > 86_400_000L }
                if (loaded.first.isNotEmpty()) {
                    android.util.Log.d(
                        "FeedViewModel",
                        "互动加分时间衰减：账面 ${loaded.first.size} 频道（超 1 天 $aged 个），" +
                            "合计 %.2f → 衰减后 %.2f".format(rawSum, eff.values.sum()),
                    )
                }
            }
        }
    }

    init {
        // 真实观看时长心跳（P0-2，设计稿 ALGORITHMS §2.2 的 VM 侧落地）：
        // 每 [WATCH_HEARTBEAT_MS] 由 watchHeartbeatTick 确认一次主位播放器是否
        // "真在播"当前计量条目，在播才累计播放毫秒——暂停/缓冲/切后台
        // （onStop 后台闸 pause 当前页）/Feed 页离场（帧提供者注销）都不计入。
        // 跑在主线程：ExoPlayer 状态读取要求在回放线程（池内实例均在主线程使用），
        // 且与 settleWatchEvent 对 watchingAccum 的读写同线程，无竞态。
        viewModelScope.launch {
            while (isActive) {
                delay(WATCH_HEARTBEAT_MS)
                watchHeartbeatTick()
                // R392 #5 段尽接棒挂点(视频路):供给尽∧末位视频真播完(主位播放器
                // STATE_ENDED+归属判式)→段尽消费单口;非队列源/未尽/非末位时零开销直通
                maybeAdvancePlayChainOnTick()
            }
        }
    }

    // ---- 权重排序/行为信号公共 API 委托（实现见 FeedRankingPart.kt） ----
    fun noteActiveUnmute(item: VideoItem) = noteActiveUnmuteImpl(item)
    fun noteActiveFullscreen(item: VideoItem) = noteActiveFullscreenImpl(item)
    fun noteSeekTarget(chatId: Long, targetMs: Long, durationMs: Long, key: String) =
        noteSeekTargetImpl(chatId, targetMs, durationMs, key)

    // ---------------- 指标诊断（P1 #3：互动统计，仅诊断不回哺排序，口径详见 InteractionStats 类注释） ----------------

    /**
     * 互动统计器：自采自算近 7 天 曝光/完播率/平均观看时长/互动率/负反馈率。
     * 自管 interaction_stats.json（tmp+rename 原子写，LocalStore 冻结故自立门户），
     * 与 taste_radar.json 同放 store/ 目录便于管理。
     * 消费边界（R122 标注）：指标仅诊断——只进 Log.i 与 lastStats 落盘快照供调参，
     * 不接回任何排序/权重参数（有意开环，防指标噪声自激，详见 InteractionStats 类注释）。
     */
    internal val interactionStats = InteractionStats(File(app.filesDir, "store/interaction_stats.json"))

    // ---- R163 B5 死媒体记账 API 委托（实现见 FeedWatchPart.kt） ----
    /** 播放失败终态记账（死链率数据源；VideoPlayerListener fatal 分支调用） */
    fun recordDeadMedia(chatId: Long) = recordDeadMediaImpl(chatId)

    /**
     * R163 B5 死链降权配置（默认关=纪律①：记账照常积累、降权不生效，排序口径
     * 与现状一致）。运行时态，B6 接 AppPrefs（deadMediaDemote 持久化开关，默认
     * 亦关）；翻默认值等用户终审后接线层决定。消费方：FeedRankingPart 两出口。
     */
    @Volatile
    internal var deadMediaDemotion: DeadMediaPolicy.Config = DeadMediaPolicy.Config()

    /**
     * R223b 口味星图「按互链关系聚拢」运行时态（@Volatile——设置页翻转即时回写；
     * 消费方=星图装配 LaunchedEffect 进页取值。默认 true=用户终审裁定）。
     */
    @Volatile
    internal var galaxyLinkPerturb: Boolean = true

    /**
     * R303c 口味星图「涌现布局」运行时态（@Volatile——设置页翻转即时回写；消费方
     * =星图装配 runGalaxyLoadR254 进页取值。默认 true=用户亲批主线换轨；关=V3
     * 经典降级位。翻转后下次打开星图生效（缓存按开关口径作废重算）。
     */
    @Volatile
    internal var galaxyEmergentLayout: Boolean = true

    // ---- R163 B4 分档调度运行时态（B6 接 AppPrefs；默认全关=行为零变化） ----

    /**
     * R163 智能分档配置（默认关=全库 LEGACY 现状等效）。消费方：FeedIndexPart
     * 派工前评估落档（evaluateAndSetTierImpl）+ indexChannel 参数化 + 枚举拍预筛。
     */
    @Volatile
    internal var indexTiering: IndexTierPolicy.TieringConfig = IndexTierPolicy.TieringConfig()

    /**
     * R163 智能时机配置（默认关=巡游爬取无窗口约束现状等效）。消费方：FeedIndexPart
     * 爬取 ticker 派工闸（roamingCrawlAllowed）+ 轮转兜底闸（rotatorCrawlAllowed）。
     */
    @Volatile
    internal var crawlTiming: IndexCrawlGate.CrawlTimingConfig = IndexCrawlGate.CrawlTimingConfig()

    /**
     * R163 B4 充电状态（BatteryManager 60s 轮询——零权限，与 batterySaver 同款节拍；
     * 充电态切换低频，轮询足够）。时机窗口闸的 L0 条件之一。
     */
    internal val _charging = MutableStateFlow(false)
    val charging: StateFlow<Boolean> = _charging.asStateFlow()

    /**
     * 最近一次播放活动时刻（主位结算/心跳时刷新，主线程写）——时机窗口的闲置
     * 宽限基准（B4 crawlTimingInputsImpl）。0 = 本会话无。
     */
    @Volatile
    internal var lastPlaybackActivityMs: Long = 0L

    /**
     * R163 B4 降档滞回计数（chatId → T1 维持且双弱信号的连续窗口数；两窗才降
     * T1→T2）。会话级不持久化——重启重算保守向（降档只会更晚）。
     */
    internal val tierDownStreaks = HashMap<Long, Int>()

    // ---- R191 手动钉住深索引运行时态（实现见 ManualDeepIndexPart.kt；Prefs 集合镜像 + 行台账） ----

    /**
     * 钉住深索引的频道集合镜像（AppPrefs.deepIndexPinnedChatIds 的内存快照，
     * init 载入 + 钉/取消钉时锁内更新）。消费方：[IndexTieringPart.evaluateAndSetTierImpl]
     * 钉住分支（恒 T0 不降档）。@Volatile：主线程/IO 协程跨线程读写。
     */
    @Volatile
    internal var deepIndexPinnedChatIds: Set<Long> = emptySet()

    /**
     * 钉住集合落盘互斥锁（读 prefs → 合并 → 写 prefs → 更新镜像 串行化）——
     * 快速连点不同频道时防互相覆盖（R108 账本串行通道同款教训）。
     */
    internal val manualPinMutex = Mutex()

    /**
     * R191 频道行「深度索引」按钮台账（chatId → 行状态；**条目只存在于钉住频道**——
     * 调度器全库爬取零扰动）。UI：左抽屉 SourcePickerSheet 频道卡（FeedScreen 接线）。
     */
    internal val _manualIndexRows = MutableStateFlow<Map<Long, ManualIndexRowState>>(emptyMap())
    val manualIndexRows: StateFlow<Map<Long, ManualIndexRowState>> = _manualIndexRows.asStateFlow()

    /**
     * 索引调度器存活标记（[FeedIndexPart.autoBuildDeepIndex] 起止维护）：点按「深度
     * 索引」时选建库路径——活着走优先车道（预算内插队），没跑走直接建库协程。
     */
    @Volatile
    internal var indexSchedulerAlive = false

    /** R419 深索引管线运行态（手动车道 + 纯计数；跨管线重建存活） */
    internal val indexPipeline = IndexPipelineState()

    init {
        // R191 启动载入：钉住集合 prefs → 镜像 + 行台账（全部 building=false——
        // 进程重启后没有幸存爬取）。同步毫秒级（prefs 内存缓存读）。
        loadManualDeepIndexRowsImpl()
    }

    init {
        // 启动即强制算一次近 7 天指标并 Log.i 输出（评估闭环的最小可见出口，供调参）；
        // IO 线程执行，顺带完成统计文件的首次加载，避免主线程首条记录时同步读盘
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { interactionStats.computeStats(force = true) }
        }
    }

    // ---------------- P2 新生代算法第一批：双轨兴趣画像 + 马尔可夫口味转移 ----------------

    /**
     * P2 兴趣画像引擎（短期轨会话权重 + 马尔可夫转移模型；算法与参数口径详见
     * InterestProfile 类注释）。马尔可夫模型落盘 store/interest_profile.json
     *（tmp+rename 原子写，LocalStore 冻结故自立门户）；短期轨纯内存随进程清零。
     */
    internal val interestProfile = InterestProfile(File(app.filesDir, "store/interest_profile.json"))

    /**
     * R79 隐式负反馈账本（秒划计数衰减升级 + LinUCB 条目级奖励归因；
     * 算法与参数口径详见 ImplicitFeedback 类注释）。秒划时刻流水持久化
     * store/implicit_feedback.json（tmp+rename 原子写，LocalStore 冻结故自立门户）；
     * 条目级结算纯内存（与 LinUCB pending 同生命周期哲学：进程死即弃）。
     * 注册进程级单例供签名冻结的 rankWeightedPage 取用（排序乘区 + settle 奖励回调）。
     */
    internal val implicitFeedback = ImplicitFeedback(File(app.filesDir, "store/implicit_feedback.json"))

    init {
        ImplicitFeedbackHolder.register(implicitFeedback)
    }

    /**
     * R298 tag 轴 + 类型轴兴趣账本（真推荐引擎 T/M 两轴；算法与参数口径见
     * TagInterestLedger 类注释）。落盘 store/tag_interest.json（tmp+rename 原子写，
     * LocalStore 冻结故自立门户）。注册进程级单例供签名冻结的 rankWeightedPage 取用
     * （兴趣乘区；后注册生效——VM 是记账方，与 ImplicitFeedback 同语义）。
     */
    internal val tagInterestLedger = TagInterestLedger(File(app.filesDir, "store/tag_interest.json"))

    init {
        TagInterestHolder.register(tagInterestLedger)
    }

    /** R79 当前生效的节奏分型（上次 recordWatchRhythm 返回值；分型跃迁时才调系数/打日志） */
    @Volatile
    internal var lastRhythmStyle: RhythmStyle = RhythmStyle.UNKNOWN

    /**
     * 马尔可夫转移的上下文：上一次有效观看的（打分词集, 频道 id）。仅内存——
     * 会话起点无上下文时转移分为 0（模型退化为纯 DIN）；跨进程不恢复，
     * 重启后的第一次有效观看只重建上下文不记账（相邻关系已断，跨进程补记会引入伪转移）。
     * @Volatile：结算在 IO 协程写、页内重排在 IO 协程读。
     */
    @Volatile
    internal var lastWatchedTaste: Pair<Set<String>, Long>? = null

    init {
        // P2 预载（IO 线程，不阻塞首帧）：加载马尔可夫模型；模型文件缺失（升级后首跑）
        // 时用既有 watch_events 自举频道级转移——词级无历史可推（watch_events 不存
        // caption），从本功能上线后逐次累积
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { interestProfile.preload(store.loadWatchEvents()) }
        }
    }

    // ---------------- 会话内实时适应（P1 #4：内存态会话系数，不落盘、重启清零） ----------------

    /**
     * 会话系数表：channelId → 权重乘数。本次 App 会话内的即时反馈：
     *  - 连续 [SESSION_STREAK_THRESHOLD]=2 次快划（watchSec<[SESSION_SKIP_SEC]=3s）同一频道，
     *    或 1 次"不感兴趣" → 该频道本会话权重 ×[SESSION_DOWN_FACTOR]=0.5（立降一半，
     *    当日生效、不等 7 天衰减——负反馈要快的依据见 ALGO-RESEARCH §3 Shorts
     *    "not interested 是强负信号，立刻生效"）；
     *  - 连续 2 次强正信号（完播 ratio≥0.9 / 点赞 / 收藏 / 下载）→ ×[SESSION_UP_FACTOR]=1.3；
     *  - 中性观看（≥3s 未完播）只清零"连续"计数、不动系数；反向阈值达成时系数翻转
     *    （降过的能被挽救，升过的也会被打回）。
     * 叠加方式：decayedChannelWeights 的输出再乘系数（applySessionAdjust），
     * WeightedAggregateFeedSource 内部路径则经 sessionAdjustedBoost 折算注入
     * （FeedSource 冻结，只能靠加分参数近似，见该函数注释）。
     * 内存态、不落盘、重启清零——会话系数是"今天手感"不是长期画像。
     */
    @Volatile
    internal var sessionAdjust: Map<Long, Double> = emptyMap()

    /** 逐频道连续信号计数（[0]=连续快划, [1]=连续强正）；只在本类会话函数内、锁保护下访问 */
    internal val sessionStreaks = HashMap<Long, IntArray>()
    internal val sessionLock = Any()

    // ---------------- P1.5 行为信号采集（全部本地、零新 UI、用户无感；会话态不落盘，重启清零） ----------------
    // 五路新信号的总挂接区（实现各带注释；与 P1 #4 会话适应机制同生命周期：内存态、重启清零）：
    //  #1 重看 replay：同一条目翻回来/再次起播记回看，≥[REPLAY_STRONG_THRESHOLD] 次 = 超强正信号
    //     （比点赞高一点 [BOOST_REPLAY]）→ 经 addInteractionBoost 进频道权重；
    //  #2 拖拽落点：VideoPage 位置跳变检测 → noteSeekTarget → InteractionStats 聚合指标
    //     （只进 computeStats 分布指标，不进权重）；
    //  #3 主动开声音/主动全屏：高投入强正信号（[BOOST_UNMUTE]/[BOOST_FULLSCREEN]，权重不低于点赞）
    //     → 经 addInteractionBoost 进频道权重；
    //  #4 序列动量：会话内口味词动量表（逐事件衰减）→ loadMore 页内有界前移（轻微加权）；
    //  #5 会话疲劳：会话观看 ≥[SESSION_FATIGUE_MS] 后 loadMore 页内错开补探索位（≈10%→20%）。

    /** P1.5 区块统一锁：下列会话态集合都在锁内读写（主线程入场回调/IO 线程结算都可能触碰） */
    internal val p15Lock = Any()

    /** #1 本会话已"有效观看"（结算 watchSec≥2s）的条目 key 集（重看检测底表） */
    internal val sessionWatchedKeys = HashSet<String>()

    /** #1 各条目本会话回看次数（翻回来/再次起播 +1） */
    internal val sessionReplayCounts = HashMap<String, Int>()

    /** #1 已发放过重看加分的条目（每条目每会话只加一次，防反复翻看刷分） */
    internal val sessionReplayBoosted = HashSet<String>()

    /** #1 最近一次有效观看结算的条目 key：刷新/切源后直接回到同一条不算回看（没有"换条再回来"的动作） */
    internal var lastSettledWatchKey: String? = null

    /** #3 本会话已计分的主动开声音 / 主动全屏条目（每条目每会话各计一次，防反复切换刷分） */
    internal val sessionUnmuteCredited = HashSet<String>()
    internal val sessionFullscreenCredited = HashSet<String>()

    /** #5 本次会话真实观看毫秒累计（心跳口径，只增不减；过 [SESSION_FATIGUE_MS] 探索翻倍） */
    internal val sessionWatchMs = java.util.concurrent.atomic.AtomicLong(0)

    /** #4 会话序列动量词表：口味词 → 会话内权重（逐事件衰减 ×[SESSION_TOPIC_DECAY]，重启清零） */
    internal val sessionTopicWords = HashMap<String, Double>()

    /** 当前内容源：加权混排聚合 / 单频道 / 搜索 / 收藏夹 / 稍后再看 / 历史，可随时切换 */
    var source: FeedSource = WeightedAggregateFeedSource(
        // R66：加分提供者叠加稀缺上浮折算（低频宝藏频道 48h 内新帖 +0.9，口径见 scarcityAdjustedBoost）
        gateway, store, { scarcityAdjustedBoost() },
        // 源内已看硬剔除接"已看内容不再出现"设置开关（默认开；关掉恢复软降权旧行为）。
        // 与 loadMore 的页级过滤双保险：源内先剔（保住页长），页级再兜（覆盖网关直拉路径）。
        // R337：判定收口 SeenDedupPolicy.hardExcludeOn——唯一输入=用户开关，无兜底态
        // 输入（旧 R94 rewatchRelaxSeen 同步解除路已退役，已看排除永不静默放宽）。
        excludeSeenProvider = { SeenDedupPolicy.hardExcludeOn(_excludeWatched.value) },
    )
        // 写入口径放宽为 internal：switchSource 等实现已按 R100 拆到同包 FeedSourcePart.kt 扩展里
        internal set

    // ---------------- R182 源历史栈（返回=回上一级，见 FeedBackStackPart.kt） ----------------

    /**
     * 顶栏当前源标题（R182 上收 VM）：原为 FeedScreen 的 remember 本地态——弹栈
     * 恢复须随快照回写标题，本地态外够不着故上收。FeedScreen 侧 23 处赋值点照旧
     * （一行 `var sourceTitle by vm.sourceTitle` 接线），赋值/重组语义与原本地态一致。
     */
    val sourceTitle = mutableStateOf("推荐")

    /**
     * 「进入当前源时设定的标题」抄录（切源时刻的现值=FeedScreen 刚设定的新标题）。
     * 下一跳入栈时，被离开态的显示标题即取本值（切源族调用点全部先设标题后切源，
     * 现值恰是新标题，被离开态的标题须用上一跳的抄录——口径见 SourceBackStackEntry）。
     */
    internal var titleAtLastSwitch = "推荐"

    /** 源历史栈（compose 可观察：MainActivity 返回消费器的 enabled 读它触发重组） */
    val sourceBackStack = mutableStateListOf<SourceBackStackEntry>()

    /**
     * 「切源回顶」抑制位（一次性）：popSourceBack 原位恢复前置位，FeedScreen 的
     * LaunchedEffect(vm.source){ scrollToPage(0) } 消费后让路一跳——防恢复页位被
     * 顶回 0 条闪跳。普通切源不置位，回顶行为不变。
     */
    @Volatile
    internal var suppressSourceResetScroll = false

    /** 弹源栈回上一源（返回消费器 L1 动作）；空栈返回 false */
    fun popSourceBack(): Boolean = popSourceBackImpl()

    /** 切源回顶抑制位的消费口（FeedScreen 效应体内一次性取走） */
    fun consumeSourceResetScrollSuppression(): Boolean {
        val v = suppressSourceResetScroll
        suppressSourceResetScroll = false
        return v
    }

    // ---------------- 内容筛选器 ----------------

    /** 当前生效的内容筛选条件（方向/时长/发布时间）；默认 FeedFilter() = 不筛选 */
    internal val _filter = MutableStateFlow(FeedFilter())
    val filter: StateFlow<FeedFilter> = _filter.asStateFlow()

    /** 内容分区 tab：-1 全部 / 0 视频 / 1 图文 / 2 音频。
     *  默认 0（视频）：进 App 默认刷视频，图文/音频手动切 tab */
    internal val _mediaTypeTab = MutableStateFlow(0)
    val mediaTypeTab: StateFlow<Int> = _mediaTypeTab.asStateFlow()

    /** 切换内容分区 tab；变化时重刷当前源 */
    /** 类型 tab 专用游标（服务端媒体过滤拉取的分页位置）；切 tab/切源/刷新都重置 */
    internal var mediaTabCursor: String? = null

    /**
     * 媒体 tab 抽空标记（仅聚合媒体路径）：上一轮 fetchAggregatedMediaAll 见过 allEnded
     * 即置位。置位后第一轮（cursor==null = 网关"换窗重扫"语义）在 loadMore 里被收窄成
     * 探测轮（REVIVE_PROBE_MAX_PULLS 窗，探新帖足够），直到某轮真有入列才清零恢复全量连拉；
     * 切源/刷新清零。防"抽空后下一轮无节制全量重扫、全是已知内容被过滤链吃掉"的空转。
     */
    @Volatile
    internal var mediaTabExhausted = false

    // ---- R189 轨道内存状态缓存（媒体 tab/换列往返；实现见 FeedTrackCachePart.kt） ----

    /**
     * 媒体 tab 快照表：键=(源实例, tab)、值=离场快照。复合键的意义：换列往返回槽的
     * 是同一源实例（列槽持有实例），tab 快照跨换列往返仍有效；容量 LRU 自限
     * （[FeedTrackCachePolicy.TAB_SNAPSHOT_CAP]）。陈旧键（源实例已被换走）永远
     * 扑空，无需 eager 清理。
     */
    internal val mediaTabSnapshots = LinkedHashMap<Pair<FeedSource, Int>, MediaTabSnapshot>()

    /** 推荐列单槽快照（换列往返——showAggregate 回列的原位恢复源） */
    internal var recommendColumnSnapshot: ColumnTrackSnapshot? = null

    /** 关注列单槽快照（换列往返对称——showFollowing 回列的原位恢复源） */
    internal var followingColumnSnapshot: ColumnTrackSnapshot? = null

    /**
     * 队列保底链式续拉的连续空轮计数（0 入列即 +1，有入列即清零；切源/刷新清零）。
     * 超过 AUTO_CHAIN_DRY_MAX 即停止链式续拉——真没货/过滤链全吃时不能烧请求空转。
     * R337：连续空轮的饥饿处置=诚实停链（本计数即停链闸）——R94 触底自愈的
     * rewatchRelaxSeen 静默放宽已随本刀退役（已看排除永不解除，见 SeenDedupPolicy
     * 契约与 loadMore 尾段「R337 供给饥饿」观测行）。
     */
    @Volatile
    internal var dryRoundStreak = 0

    // ---------------- 频道轮换记忆（fetchAggregated 轮换采样配套，修"每次刷都是同一批频道"） ----------------

    /** 轮换记忆锁：rotationPools 的全部读写都在锁内（VM 侧记录、网关 IO 线程经 provider 读） */
    internal val rotationLock = Any()

    /**
     * R75-C 频道级入池曝光账（跨会话持久，channel_exposure.json）：
     * 保底配额车道（网关 poolRecencyProvider）的数据源 + 覆盖度度量底座。
     * lazy：首次访问才读盘，不阻塞 VM 构造
     */
    internal val channelExposureLedger by lazy { ChannelExposureLedger(app) }

    /** 最近露脸频道记忆：最近 2 轮刷新（fetchAggregated 新轮）的进池频道 id 集，
     *  新轮进、旧轮出。进程内存态、重启清零——轮换重来可接受，不落盘 LocalStore */
    internal val rotationPools = ArrayDeque<Set<Long>>()

    /**
     * R92 媒体路径保底名册显式接力：fetchAggregatedMediaAll 的保底坑位经内容分层后，
     * 本侧 (lastPooled, chatId) 复算口径与网关实际选取已不一致——网关改为
     * poolReporter 之前同轮同步显式回报名册（guaranteedRosterReporter），这里暂存，
     * recordPoolRound 消费即清；fetchAggregated 路径不回报，维持原复算口径。
     */
    @Volatile
    internal var pendingMediaGuaranteedRoster: Set<Long>? = null

    init {
        // v2 拉取配额倾斜：向真实网关注入频道权重表（watch_events 现算的时间衰减权重，
        // 与 WeightedAggregateFeedSource 混排打分同一份画像）。provider 在 fetchAggregated
        // 每轮开头被调用一次（IO 线程，store 读走内存缓存）。Mock 网关无聚合配额概念，跳过。
        (gateway as? TdLibGateway)?.channelWeightProvider = {
            // 互动加分表叠加进权重（与混排打分同一份画像）；provider 在 IO 线程被调用，读 @Volatile 内存表；
            // P1 #4：输出再乘会话系数（快划/不感兴趣的当日即降与完播/互动的当日即升直达拉取配额）；
            // P2：双轨融合统一出口——短期轨（本会话快衰减）按 α 融入长期轨，
            // "今天突然想看 XX"直达拉取配额，不等 7 天窗口稀释
            p2ChannelWeights()
        }
        // 频道轮换采样（修"每次刷都是同一批频道"）：向网关注入"最近露脸"供给与进池回报。
        // 网关新轮（refresh/首刷后 fetchAggregated 首页，cursor==null）从全量频道按
        // "口味权重×轮换惩罚"抽 24 个进池；命中最近 2 轮记忆的频道本轮排除（候选不足
        // 一池时 ×0.1 降权回补）。轮内翻页复用同一池，游标按 chatId 键控天然兼容。
        (gateway as? TdLibGateway)?.let { gw ->
            gw.recentPoolProvider = { rotationRecentIds() }
            gw.poolReporter = { ids -> recordPoolRound(ids) }
            // R75-A/C：保底车道的"最久未进池"账面 + 进页条数回报（跨会话频道曝光账）
            gw.poolRecencyProvider = { channelExposureLedger.lastPooledAt() }
            gw.poolItemReporter = { counts -> channelExposureLedger.recordPooledItems(counts) }
            // R92：保底选池内容分层数据源（深度索引"有该类型内容"分层）+
            // 保底名册显式接力（分层后 VM 复算口径对不上，名册以网关显式回报为准）
            gw.poolContentProvider = { mediaType -> indexedMediaTier(mediaType) }
            gw.guaranteedRosterReporter = { ids -> pendingMediaGuaranteedRoster = ids }
            // R150 已看源头过滤（A 主根因）：网关聚合直拉（fetchAggregatedMediaAll/
            // AllFast = 媒体 tab 正路 + 首屏快车道）产出源头剔除已看条目——纯内存
            // 集合查（loadSeenIdentities 命中 store 内存缓存），快车道延迟零增量；
            // 源头滤后采样在幸存池上做，页长不再被已看稀释、刷新后同批已看内容不再
            // 整窗重进页。口径与下方 loadMore 的 pass 谓词同步：开关关闭时传空集解除；
            // 双保险不变：VM 侧 pass 谓词照旧全量再滤一遍（网关未消费的路径也有兜底）。
            // R337：判定收口 SeenDedupPolicy.hardExcludeOn——唯一输入=用户开关
            //（旧 R94 rewatchRelaxSeen 解除路已退役：供给饥饿不再静默放宽已看排除）。
            gw.seenIdentityProvider = {
                if (SeenDedupPolicy.hardExcludeOn(_excludeWatched.value)) {
                    runCatching { store.loadSeenIdentities() }
                        .getOrDefault(emptySet<String>() to emptySet())
                } else {
                    emptySet<String>() to emptySet()
                }
            }
        }
    }

    // R68 频道卡元数据表（声明必须在 init 块之前：init 块里点火的 IO 协程会经
    // refreshFollowSignal→refreshChannelCardMeta 访问它，声明在后=构造竞态 NPE，
    // fresh install 演示模式必现——R86 实测复现）
    internal val _channelCardMeta = MutableStateFlow<Map<Long, ChannelCardMeta>>(emptyMap())
    val channelCardMeta: StateFlow<Map<Long, ChannelCardMeta>> = _channelCardMeta.asStateFlow()

    /**
     * R108 账本落盘统一串行通道（修 H2 冷启动恢复竞态丢收藏 + M1 快速连点落盘乱序）：
     * favorites / likes / watchLater / blocked_channels 四本账的"重读磁盘→按意图合并→
     * 写回→回校内存"全流程必须在本锁内完成，恢复协程的"磁盘→内存"搬运与复活体检的
     * "读盘→换发 fileId→写回"也在本锁内——任一落盘方读到的必是上一方合并完的终值，
     * 从根上消灭"空内存快照全量覆盖磁盘"与"后到写回滚先到写"。
     * 声明必须在下方 init 块之前：init 点火的 IO 协程会访问本锁，声明在后=构造竞态
     * （与 _channelCardMeta 同款教训，R86 实测复现过）。
     */
    internal val ledgerMutex = Mutex()

    init {
        // 启动时把收藏、点赞、订阅、稍后再看、屏蔽列表、自动连播从磁盘恢复到内存（IO 线程加载，不阻塞首帧）
        viewModelScope.launch(Dispatchers.IO) {
            // R108：恢复+复活全程持账本串行锁——恢复完成前到达的互动落盘（toggleFavorite 等）
            // 在锁外等待，获锁后重读到的必是恢复后的完整账本；复活体检跨网络调用，
            // 不持锁则其旧快照写回会回滚窗口内的互动合并结果
            ledgerMutex.withLock {
                val favs = runCatching { store.loadFavorites() }.getOrDefault(emptyList())
                favoriteItems = favs
                _favorites.value = favs.mapTo(LinkedHashSet<String>()) { it.key }
                _likes.value = runCatching { store.loadLikes() }.getOrDefault(emptySet())
                _subscriptions.value = runCatching { store.loadSubscriptions() }.getOrDefault(emptyList())
                    .map { ChannelBrief(it.chatId, it.title, null) }
                // R66：订阅列表到位后重估追更红点（进 Feed 时的首次检测可能赶在订阅加载之前）
                refreshFollowSignal("订阅就绪")
                val later = runCatching { store.loadWatchLater() }.getOrDefault(emptyList())
                watchLaterItems = later
                _watchLater.value = later.mapTo(LinkedHashSet<String>()) { it.key }
                _blockedChannels.value = runCatching { store.loadBlockedChannels() }.getOrDefault(emptySet())
                _dislikedKeys.value = runCatching { store.loadDisliked() }.getOrDefault(emptySet())
                _mutedKeywords.value = runCatching { store.loadMutedKeywords() }.getOrDefault(emptySet())
                _muteMatchChannelTitle.value = runCatching { prefs.muteMatchChannelTitle }.getOrDefault(false)
                _blockedItems.value = runCatching { store.loadBlockedItems() }.getOrDefault(emptyList())
                _autoAdvance.value = runCatching { prefs.autoAdvance }.getOrDefault(true)
                // CRITICAL-2：收藏/稍后恢复完成后，同一协程串行体检复活（换发本会话新 fileId）——
                // 与恢复写回同协程串行，规避并发覆盖；真实网关注定才可复活，Mock 内部直接跳过
                reviveStoredItems()
                // R298 冷启动先验：订阅/收藏/稍后/点赞四路原料到位后灌 tag 轴先验
                //（内容哈希守卫幂等，原料没变=零写；设计 §6「订阅即先验，零行为也有画像」）
                runCatching { maybeSeedTagInterestPrior() }
            }
        }
    }

    fun loadChannels() {
        viewModelScope.launch {
            // R145 冷启动让路：频道全量枚举（LoadChats×3 列表 + 逐会话 GetChat，
            // 数百次 TDLib 往返）延到首轮内容结论之后——它和首拉的 listSearchableChats
            // 枚举同型同量，华为平板基线实锤两者在同一毫秒并行（20:42:51.695 双日志），
            // 冷启动最挤的头几秒双倍枚举纯属内耗。频道清单只喂源面板/快拍/发现页，
            // 晚几秒用户无感；首轮拉取 15s 内没结论（坏网络）按超时兜底照常枚举。
            kotlinx.coroutines.withTimeoutOrNull(15_000) {
                _initialContentReady.first { it }
            }
            runCatching { gateway.listChannels() }
                .onSuccess { _channels.value = it }
                .onFailure { _error.value = it.message }
        }
        // 频道加载完后自动后台建/续深度索引（断点续传：游标落盘，中断下次接着爬）。
        // R52 冷启动降噪：延到首屏内容到货再起步——索引枚举（GetChats 全量会话）
        // 与爬取泳道和首页首拉抢同一条 TDLib 请求通道，实测是"Ready 后首拉 25~36s"
        // 的共谋之一；首屏一直没结论（如停在登录页）则不启动——没授权也没东西可索引。
        // R145 二次校时 3s→20s：快车道让首屏内容 5s 级到货后，"+3s"把爬虫风暴
        // 拉进了用户刚开始看前几条的播放窗口（实测换台 ensurePlayable 被爬虫请求
        // 挤到 10~15s）——与存储治理让路闸同节拍（内容+20s 宽限），用户落座看片
        // 的头 20 秒后台一律静默；爬虫是常驻背景事，晚 17s 无感
        viewModelScope.launch(Dispatchers.IO) {
            _initialContentReady.first { it }
            delay(20_000)
            autoBuildDeepIndex()
        }
    }

    /** 自动深度索引：频道列表到位后启动常驻低强度爬取循环（防重入；用户可在设置里关） */
    internal val autoIndexStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** R286 新帖快通道在飞闸：feed 页顺手入索引的 fire-and-forget 协程同一时刻
     *  最多一份在跑（页页叠加=写锁排队白烧；丢页由头刷/下一页兜底，幂等不丢数据） */
    private val feedIngestInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 搜索证据插队队列（R46 方案 d）：fullSearch 命中答案库/图谱证据指向的频道插到最前。
     * [priorityQueuedSet] 是有效性令牌——普通队列先派工到的频道会作废其优先拷贝，
     * 防同一频道被两条队列各爬一次（DeepSearchIndex 单飞闸兜底防并发双爬）。
     */
    internal val priorityCrawlQueue =
        kotlinx.coroutines.channels.Channel<Long>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    internal val priorityQueuedSet = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** 近期浏览频道集（R46 方案 b 白嫖名单）：近 [LOCAL_GRAB_LOOKBACK_MS] 观看事件的 chatId 集，
     *  枚举拍刷新（纯本地读）；@Volatile：枚举协程写、爬取协程读 */
    @Volatile
    internal var recentBrowsedChatIds: Set<Long> = emptySet()

    /** 自动索引当前爬取轮次（写入 IndexStatus.round 供设置页展示"第 N 轮"）；0 = 未开跑 */
    internal val autoIndexRound = java.util.concurrent.atomic.AtomicInteger(0)

    /** 爬取并发度（并发 AIMD：连续无 flood 节拍 +1 路、出 flood 降回 4；范围 [4,8]，仅爬取协程读写。
     *  R40 提速：清扫单页请求已从 5 降到 1（DeepSearchIndex expandCaptions=false），
     *  账号级请求压力比旧版 3 车道更低，故区间整体上移） */
    internal var crawlConcurrency = 4
    // ---------------- 索引状态可见性（修"索引是黑盒"） ----------------

    /** 索引状态：已建频道数 / 总条目数 / 是否在建中 / 总会话数。设置页显示 */
    internal val _indexStatus = MutableStateFlow(IndexStatus(0, 0, false, 0))
    val indexStatus: StateFlow<IndexStatus> = _indexStatus.asStateFlow()

    /** R294 P3 压实进度（null=不在跑；设置页深度搜索卡进度行） */
    internal val _indexCompactProgress = MutableStateFlow<String?>(null)
    val indexCompactProgress: StateFlow<String?> = _indexCompactProgress.asStateFlow()

    /** R294 压实完成报告（一次性消费——UI Toast 后调 [clearIndexCompactDone] 复位） */
    internal val _indexCompactDone = MutableStateFlow<String?>(null)
    val indexCompactDone: StateFlow<String?> = _indexCompactDone.asStateFlow()

    // ---- 深度索引公共 API 委托（实现见 FeedIndexPart.kt / FeedHashtagPart.kt） ----
    fun deepIndexedCount(chatId: Long): Int = deepIndexedCountImpl(chatId)
    fun buildDeepIndex(chatId: Long, onProgress: (Int) -> Unit) = buildDeepIndexImpl(chatId, onProgress)
    suspend fun buildDeepIndexAwait(chatId: Long, onProgress: (Int) -> Unit) = buildDeepIndexAwaitImpl(chatId, onProgress)
    fun refreshDeepIndexStatus() = refreshDeepIndexStatusImpl()
    fun clearAndRebuildDeepIndex() = clearAndRebuildDeepIndexImpl()
    /** R294 P3 物理压实：预估（确认弹窗用）与执行（显式保 rowid 重建换装） */
    suspend fun estimateIndexCompaction() = estimateIndexCompactionImpl()
    fun compactDeepIndex() = compactDeepIndexImpl()
    fun clearIndexCompactDone() {
        _indexCompactDone.value = null
    }
    fun deepSearch(query: String) = deepSearchImpl(query)

    // ---- R191 手动钉住深索引公共 API（实现见 ManualDeepIndexPart.kt） ----
    /** 点按「深度索引」：钉住（T0 等待遇+不降档）+ 立即建库（优先车道/直接建库） */
    fun requestDeepIndex(chatId: Long) = requestDeepIndexImpl(chatId)
    /** 取消钉住：移除集合，回自动评估（已建内容保留可搜） */
    fun cancelDeepIndexPin(chatId: Long) = cancelDeepIndexPinImpl(chatId)

    /** R167 #话题搜索（深索引 hashtag 精确词主路 + 服务端补位混合源），实现见 FeedHashtagPart.kt */
    fun hashtagSearch(tag: String) = hashtagSearchImpl(tag)

    /** R269 行星内容流（星图点行星/飞入门「看内容」行 → 该 tag 全宇宙内容直刷；纯本地
     *  tag_items 倒排 + R267 LIKE 兜底，零 Search 族），实现见 FeedHashtagPart.kt */
    fun playTagContent(tag: String) = playTagContentImpl(tag)

    // ---------------- 频道合集 ----------------

    /** 用户自组的频道合集列表（LocalStore collections.json 持久化，名称为主键） */
    internal val _collections = MutableStateFlow<List<ChannelCollection>>(emptyList())
    val collections: StateFlow<List<ChannelCollection>> = _collections.asStateFlow()

    init {
        // 启动时从磁盘恢复合集列表（IO 线程加载，不阻塞首帧；独立 init 块便于追加维护）
        viewModelScope.launch(Dispatchers.IO) {
            _collections.value = runCatching { store.loadCollections() }.getOrDefault(emptyList())
        }
    }

    // ---------------- 长视频续播进度（LocalStore watch_progress.json 持久化） ----------------

    /**
     * 续播进度内存表：item.key → 已播位置毫秒。
     * init 时 IO 线程从磁盘预载，之后读路径（[getProgress]）为纯内存同步读；
     * 写路径（[saveProgress]）先更新本表再异步落盘，保证起播 seek 立即读到最新值。
     */
    @Volatile
    internal var progressMap: Map<String, Long> = emptyMap()

    /**
     * 进度同步服务：仅真实 TDLib 网关可用（Mock 演示模式无云端，为 null）。
     * lazy 构造：未开启过功能时不创建；ProgressSync 本身只是两个引用的轻包装。
     * 注意声明顺序：必须在下方进度预载 init 块之前——init 里的协程可能
     * 在构造器走到原声明位置前就触发读取，Lazy 委托还是 null 会直接 NPE（实测崩过）。
     */
    internal val progressSync: ProgressSync? by lazy {
        (gateway as? TdLibGateway)?.let {
            ProgressSync(it, store, File(app.filesDir, "store/progress_sync_state.json"))
        }
    }

    /**
     * R300 多设备状态同步引擎（四域：收藏/稍后/追更订阅+已读水位/屏蔽三件套）：
     * 仅真实 TDLib 网关可用（Mock 演示模式无云端，为 null）。lazy 构造同 progressSync 先例。
     * 合并落盘走 [ledgerMutex]（R108 串行通道纪律）；onApplied 回校 VM 内存态。
     */
    internal val multiSync: MultiSync? by lazy {
        (gateway as? TdLibGateway)?.let { gw ->
            MultiSync(
                gateway = gw,
                store = store,
                stateFile = File(app.filesDir, "store/cloud_sync_state.json"),
                scope = viewModelScope,
                ledgerMutex = ledgerMutex,
                enabledProvider = { prefs.cloudSyncEnabled },
                onApplied = { changed -> reloadSyncedStateFromStore(changed) },
            )
        }
    }

    /** 状态同步最近一次自动推送发起时刻（@Volatile：动作多在主线程被调，推送协程在 IO） */
    @Volatile
    internal var lastStateAutoPushAtMs = 0L

    /** 状态同步延迟补推任务（FeedCloudSyncPart.scheduleStatePushRetry 单飞闸） */
    internal var statePushRetryJob: kotlinx.coroutines.Job? = null

    init {
        // 启动时把续播进度表从磁盘预载到内存（IO 线程加载，不阻塞首帧）
        viewModelScope.launch(Dispatchers.IO) {
            progressMap = runCatching { store.loadProgressMap() }.getOrDefault(emptyMap())
            // 记忆播放（仅会话内）：同协程读"最后浏览条目"——feed_resume.json 带进程会话标记，
            // 只认本进程写入的记录；杀进程重开会话标记对不上 → 不恢复（跨进程"接着看"走播放历史）
            pendingResumeKey = runCatching { store.loadLastViewedKeyForSession(RESUME_SESSION_ID) }.getOrNull()
            resumeReady = true
            // 跨设备接力：磁盘预载完成后自动拉一次云端进度（开关开时）。
            // 与预载同协程串行：拉取内部会重建 progressMap，若与预载并发可能把合并结果覆盖回旧值
            if (prefs.progressSyncEnabled) pullProgressIntoMemory()
            // 首屏列表可能已先于磁盘预载到达（网络快于小文件读）：补一次恢复定位
            tryRestorePosition()
        }
    }

    // ---- 续播进度/跨设备同步公共 API 委托（实现见 FeedProgressPart.kt） ----
    fun getProgress(key: String): Long = getProgressImpl(key)
    fun saveProgress(item: VideoItem, posMs: Long) = saveProgressImpl(item, posMs)
    fun saveProgressSync(item: VideoItem, posMs: Long) = saveProgressSyncImpl(item, posMs)
    fun offerJumpTarget(item: VideoItem) = offerJumpTargetImpl(item)
    fun addSubscription(c: ChannelBrief) = addSubscriptionImpl(c)
    fun syncProgressPush() = syncProgressPushImpl()
    fun syncProgressPull(onResult: (String) -> Unit) = syncProgressPullImpl(onResult)
    /** R300 期零③回滚口：清理收藏夹里全部云同步消息（UI 已用户显式确认后才调本口） */
    fun clearCloudSyncMessages(onResult: (String) -> Unit) = clearCloudSyncMessagesImpl(onResult)

    // ---- 订阅/追更红点/追更通知公共 API 委托（实现见 FeedFollowPart.kt） ----
    fun removeSubscription(chatId: Long) = removeSubscriptionImpl(chatId)
    fun refreshFollowSignal(reason: String) = refreshFollowSignalImpl(reason)
    fun noteFollowChipOpened() = noteFollowChipOpenedImpl()
    fun noteChannelVisited(chatId: Long) = noteChannelVisitedImpl(chatId)
    suspend fun scanFreshSubscriptionItems(): List<VideoItem> = scanFreshSubscriptionItemsImpl()
    fun setNotifyNewContent(v: Boolean) = setNotifyNewContentImpl(v)

    init {
        // 追更轮询协程：项目无 WorkManager 依赖（任务边界：不加新依赖），
        // 用 viewModelScope 常驻轮询替代——进程存活且本 VM 存活期间，
        // 每 FOLLOW_POLL_INTERVAL_MS 扫一遍订阅频道的最新消息；进程被杀自然停止。
        // VM 是 activity 作用域，App 退后台轮询仍继续（这正是"追更通知"想要的）。
        viewModelScope.launch(Dispatchers.IO) {
            // 首轮延迟：避开启动/登录高峰，也给 MainActivity 的通知权限请求留出时间
            delay(FOLLOW_POLL_INITIAL_DELAY_MS)
            while (isActive) {
                // H2：App 退后台暂停轮询（appInForeground 由 MainActivity onStart/onStop 置位）；
                // 暂停期间不发起任何 TDLib 请求，回前台后下一轮自然恢复
                if (appInForeground) {
                    // 单轮失败静默（下轮再来）：不写 _error，避免打扰当前界面
                    runCatching { pollSubscribedChannelsOnce() }
                }
                delay(FOLLOW_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * 追更红点状态（顶栏"订阅追更"chip 的角标数据源）：任一已订阅频道在深度索引里的
     * 最新条目时间戳 > 该频道 lastVisitTs（用户上次刷该频道/点该 chip 的时刻）即亮。
     * 小红点不显示数字（UI 只读布尔）。
     */
    internal val _followHasNew = MutableStateFlow(false)
    val followHasNew: StateFlow<Boolean> = _followHasNew.asStateFlow()

    /**
     * 各订阅频道的最近查看时刻（毫秒）。落盘用 VM 自持的小 JSON 文件
     * filesDir/[FOLLOW_VISIT_FILE]（tmp+rename 原子写）——LocalStore 本轮冻结，
     * 与 InteractionStats/InterestProfile 同手法自立门户。
     * @Volatile：账本读写都在 IO 协程，红点重估跨调用点。
     */
    @Volatile
    internal var followVisits: Map<Long, Long> = emptyMap()

    /**
     * 深度索引频道统计快照（chatId → 条数/最早/最新条目发布时间），追更红点判定与
     * 低频宝藏上浮共用同一份：刷新走一次只读聚合（见 loadIndexChannelStats——R318 起
     * 为 date 索引覆盖分片走查，5.8M 行真库分钟级→秒级），时机 = 进 Feed + 索引轮次推进
     * （indexStatus 状态流轮次号变化），绝不轮询猛查。
     */
    @Volatile
    internal var indexChannelStats: Map<Long, IndexChannelStat> = emptyMap()

    /**
     * R318 统计走查单飞标记：同刻至多一路在刷统计快照（走查虽已降到秒级，但轮次流
     * 每轮都会触发——叠扫浪费 IO；在途时后到的触发直接跳过，红点等下一轮再刷）。
     */
    @Volatile
    internal var indexStatsWalkInFlight = false

    init {
        // 追更查看账本预载（IO 线程，不阻塞首帧）；坏文件静默回落空表（全频道按"从未看过"计）
        viewModelScope.launch(Dispatchers.IO) {
            followVisits = runCatching { loadFollowVisits() }.getOrDefault(emptyMap())
        }
        // 索引轮次推进时刷统计快照并重估红点：挂在自有 indexStatus 状态流上，
        // 只认轮次号变化（refreshIndexStatus 的常规状态刷新不触发）；
        // 每拍一次只读聚合（R318 起走查口径，秒级；走查在途单飞跳过不叠扫）
        viewModelScope.launch(Dispatchers.IO) {
            var lastRound = -1
            _indexStatus.collect { st ->
                if (st.round != lastRound) {
                    lastRound = st.round
                    refreshFollowSignal("索引轮次#${st.round}")
                }
            }
        }
    }

    /** 分层快照缓存（5 分钟 TTL，与 listSearchableChats 缓存同节拍；@Volatile 跨线程可见） */
    @Volatile
    internal var indexedMediaTiers: IndexedMediaTiers? = null

    /**
     * R170 tier 扫描单飞标记：全表 GROUP BY（2M 行，冷缓存进程内实测 ~4~10s）
     * 同一时刻只允许一路在扫——同步消费者（快车道类型感知种子/窗口保底分层）
     * 与后台刷新/启动预热并发到来时，后来者走各自既有回退路（null→种子回落
     * 快照/订阅、保底回退最久未进窗），不叠扫。见 FeedFollowPart 的
     * indexedMediaTier / refreshIndexedMediaTiersAsync / warmIndexedMediaTiers。
     */
    @Volatile
    internal var tierScanInFlight = false

    /** 刷新当前源：重置游标、清空列表、重新拉第一页 */
    fun refresh() {
        settleWatchEvent(null) // 刷新即离开当前条目，先结算观看计时
        loadGen.incrementAndGet() // 作废旧轮（同 switchSource）：旧页结果不再写回清空后的列表
        // R189：显式刷新=当前轨道旧快照作废（双击刷新后往返不得回旧内容——刷新起点
        // 列表已清，若残留快照会在切回时还魂）
        evictCurrentTrackSnapshotsImpl()
        source.reset()
        _feedSnapshotActive.value = false // R52：显式刷新，快照标记归位（列表即将清空重拉）
        snapshotKeys = emptySet()
        mediaTabCursor = null // 类型 tab 专用游标重置
        mediaTabExhausted = false // 媒体 tab 抽空标记随刷新作废（重新全量连拉）
        dryRoundStreak = 0 // 队列保底链式续拉的连续空轮计数随刷新清零（R337：唯一停链闸，重温兜底旗已退役）
        clearAutoAdvanceMemory() // 连播"往回翻回看"豁免记忆随刷新清零
        backfillPages = 0 // 历史回溯注入节拍器随源重置（与列表同生命周期）
        // R98 新面孔露脸账与名册缓存随刷新清零（与列表同生命周期）
        synchronized(newFaceLock) { newFaceShownAt.clear() }
        newFaceRosterCache = null
        _items.value = emptyList()
        _currentIndex.value = 0 // 刷新回顶
        _loading.value = false // 放行本次首拉：旧轮收尾无权放闸，不清则新拉被闸吞
        // 记忆播放：刷新即放弃重启恢复（用户显式回顶，不再追旧位置）
        positionRestored = true
        pendingResumeKey = null
        loadMore()
    }

    /**
     * 翻页回调：结算上一条观看时长、记录观看历史（同 key 节流），接近尾部时自动加载下一页。
     * 观看计时与历史记录相互独立：同 key 翻回历史不重复写，但观看时长会重新起表。
     */
    fun onPage(index: Int) {
        _currentIndex.value = index
        val item = _items.value.getOrNull(index)
        // 连播回看豁免：落定即消费——用户手动翻回被自动弹走过的页 → 转入豁免集
        //（自动连播自己的落子不算翻回，见 consumeAutoEjectedOnSettle 内部判定）
        if (item != null) consumeAutoEjectedOnSettle(item.key)
        settleWatchEvent(item) // 翻走即结算上一条，并为新条目重新起表
        if (item != null) plazaPostWatching(item) // 广场"在看"上报（服务内采样限速）
        if (item != null && item.key != lastHistoryKey) {
            lastHistoryKey = item.key
            viewModelScope.launch(Dispatchers.IO) {
                // G4：完整条目入库（不再只存 key），重启后历史源可从磁盘还原回放
                runCatching { store.appendHistory(item) }
            }
        }
        // 记忆播放（仅会话内）：翻到即记"最后浏览条目"并打上进程会话标记（同 key 节流）。
        // 与 watch_progress 分工：这里不受 5s/97% 阈值影响——看过几秒的条目
        // 同进程内回来也该回到它（片内进度按 watch_progress 的既有语义另算）；
        // 杀进程重开后会话标记对不上，本条记录不会被恢复
        if (item != null && item.key != lastViewedKey) {
            lastViewedKey = item.key
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { store.saveLastViewedKey(item.key, RESUME_SESSION_ID) }
            }
        }
        // R387 队列级断点续播：在播源是播放队列时，落定条目→（来源段 entryId,key）
        // 记账（队列专用通道，不打扰 watch/history 既有账；同条不重写——
        // FeedPlayQueuePart.notePlayQueueResume）
        if (item != null && source is PlayQueueFeedSource) notePlayQueueResume(item)
        // 预取提前（修"刷到头干等"）：旧阈值剩 4 条才拉——一轮聚合拉取秒级到十几秒
        //（40 频道窗口并发 SearchChatMessages + caption 展开），快刷 4 条只要几秒，
        // 剩余太少必到底干等；提到 8 条，配合 loadMore 的队列保底链式续拉不再刷到头
        if (index >= _items.value.size - LOAD_MORE_REMAINING_TRIGGER) loadMore()
    }

    // ---- 智能降档公共 API 委托（实现见 FeedSmartQualityPart.kt） ----
    fun smartDegradeActive(): Boolean = smartDegradeActiveImpl()
    fun preloadAheadFor(chatId: Long): Int = preloadAheadForImpl(chatId)

    /** 智能降档总开关（默认开，存 AppPrefs.smartQuality；设置页暂无入口，预留） */
    internal val _smartQuality = MutableStateFlow(true)
    val smartQuality: StateFlow<Boolean> = _smartQuality.asStateFlow()

    /** 当前默认网络是否计量（按流量计费，如移动数据；Wi-Fi/无网络=false）。切网即更新 */
    internal val _networkMetered = MutableStateFlow(false)
    val networkMetered: StateFlow<Boolean> = _networkMetered.asStateFlow()

    /**
     * R324 离线态（NetworkWatch 单源——弱网离线可玩降级矩阵的触发信号）：
     * false = 系统无默认网络。消费面：FeedScreen 离线提示行/空态副文案（文案
     * 单源 core/net/OfflineDegradePolicy）。不拦截任何既有动作——只做诚实标注。
     */
    internal val _networkOnline = MutableStateFlow(true)
    val networkOnline: StateFlow<Boolean> = _networkOnline.asStateFlow()

    /**
     * R324 feed 数据时点①：快照落盘时刻（冷启快照垫底时捕获；0=无快照）。
     * 离线 banner 的「N 小时前的快照」时点=max(本值, [lastFreshAtMs])。
     */
    internal val _snapshotSavedAtMs = MutableStateFlow(0L)
    val snapshotSavedAtMs: StateFlow<Long> = _snapshotSavedAtMs.asStateFlow()

    /**
     * R324 feed 数据时点②：本会话最近一次「真有页到账」时刻（loadMore 收尾
     * pagesArrived>0 时戳；0=本会话尚无到货）。网络中断后 banner 靠它把时点
     * 诚实推进到「最后一次在线拉到内容的时刻」。
     */
    internal val _lastFreshAtMs = MutableStateFlow(0L)
    val lastFreshAtMs: StateFlow<Long> = _lastFreshAtMs.asStateFlow()

    /**
     * R195 信号链收敛：计量网络监听上移到进程级 [NetworkMeteredBus]（App.onCreate
     * start，网关侧激进整载器/本 VM 共用单源，系统默认网络回调全进程一个监听者）。
     * 本 VM 改为订阅总线（viewModelScope 内 collect，VM 销毁随 scope 自动取消）——
     * 对外 StateFlow 与消费面（VideoPage/IndexTiering/智能降档/WiFi 预载）口径零
     * 改动；旧的 VM 私有 meteredCallback（registerDefaultNetworkCallback/
     * onCleared 注销那一套）随迁退役。
     */

    /** 系统省电模式（PowerManager.isPowerSaveMode，60s 轮询；省电模式切换属低频事件，轮询足够） */
    internal val _batterySaver = MutableStateFlow(false)
    val batterySaver: StateFlow<Boolean> = _batterySaver.asStateFlow()

    init {
        // 智能降档状态初始化：开关取 AppPrefs；计量先取总线现值再订阅；省电模式 60s 轮询
        _smartQuality.value = runCatching { prefs.smartQuality }.getOrDefault(true)
        // R195：总线 start 幂等（App.onCreate 已起，这里兜底防早期路径未装机）；
        // 初值同步取总线现值，再以订阅跟随系统回调（跳变即时反映）
        runCatching { dev.tgfeed.core.net.NetworkMeteredBus.start(app) }
        _networkMetered.value = dev.tgfeed.core.net.NetworkMeteredBus.metered
        // R163 B6：分档/时机/降权配置启动载入（AppPrefs 默认全关=行为零变化；
        // 设置页翻转 @Volatile 运行时态即时生效，无需重启）
        runCatching {
            // 修订令：evict 扳机=磁盘水位线（默认 2GiB，分档开即生效）∨ 用户索引库
            // 存储预算（evictStorageBudgetGb，0=关；GB→字节口径与 AppPrefs KDoc 同）
            indexTiering = IndexTierPolicy.TieringConfig(
                enabled = prefs.indexTieringEnabled,
                evictIndexBudgetBytes = prefs.evictStorageBudgetGb.toLong() * (1L shl 30),
            )
            crawlTiming = IndexCrawlGate.CrawlTimingConfig(
                smartTiming = prefs.smartCrawlTiming,
                // 探索配额开关→百分比映射（开=默认 30%，关=0=探索车道停用）
                exploreQuotaPct = if (prefs.indexExploreQuota) IndexCrawlGate.EXPLORE_QUOTA_PCT_DEFAULT else 0,
            )
            deadMediaDemotion = DeadMediaPolicy.Config(demoteEnabled = prefs.deadMediaDemotion)
            // R223b 口味星图微扰开关（默认开=终审裁定；星图装配侧进页取值，
            // 翻转后下次打开星图生效——确定性布局重算）
            galaxyLinkPerturb = runCatching { prefs.tasteLinkPerturb }.getOrDefault(true)
            // R303c 涌现布局开关（默认开=主线换轨；星图装配侧进页取值，
            // 翻转后下次打开星图生效——缓存口径作废重算）
            galaxyEmergentLayout = runCatching { prefs.tasteEmergentLayout }.getOrDefault(true)
        }
        runCatching {
            // R195：订阅计量总线（取代 VM 私有 registerDefaultNetworkCallback——
            // 监听单源化，见 _networkMetered 声明处注释）；VM 销毁随 scope 取消
            viewModelScope.launch {
                dev.tgfeed.core.net.NetworkMeteredBus.state.collect { _networkMetered.value = it }
            }
        }
        runCatching {
            // R324：离线态订阅（NetworkWatch 单源；_networkOnline 初值默认 true=
            // 乐观在线，装机前不误报离线——App.onCreate 链式 start 后首值即真值）
            dev.tgfeed.core.net.NetworkWatch.start(app)
            _networkOnline.value = dev.tgfeed.core.net.NetworkWatch.online.value
            viewModelScope.launch {
                dev.tgfeed.core.net.NetworkWatch.online.collect { _networkOnline.value = it }
            }
        }
        // R170b 预热任务注册（用户设计原则：扫描不暴露——后台静默预热，前台永不等待）：
        // 全部经 WarmupCoordinator 错峰（主 Feed 首拉落定+宽限才起跑，R158 教训不与
        // 冷启动抢）+优先级+去重+静默重试。任务真身见 FeedSourcePart/FeedFollowPart。
        // ① 关注流首页快照预热（用户可感知，高优）：订阅频道并发拉首页→落盘垫底，
        //   用户切「关注」tab 时秒出内容（真无缓存才见加载文案）。
        // ② tier 快照刷新（纯后台保鲜，低优）：R170 的 VM-init 即时预热挪到这里——
        //   tier 磁盘快照已让首开消费者毫秒级秒读（R170 四修），init 期抢跑的
        //   2M 行扫描违背「预热低于一切前台路径」原则，改由协调器在错峰窗刷新。
        warmup.submit("followingSnapshot", priority = WARMUP_PRIORITY_FOLLOWING) {
            warmFollowingSnapshot()
        }
        warmup.submit("tierSnapshot", priority = WARMUP_PRIORITY_TIER) {
            refreshTiersForWarmup()
        }
        // ③ R183 金矿快照预热（纯后台保鲜，低优——与 tier 快照同档）：发现页的数据
        //    地基在错峰窗构建/落盘，用户进发现页毫秒级命中（陈值先回）；无快照时
        //    首建也在后台完成（真库实测冷缓存首建可分钟级——绝不让它出现在前台路径）
        warmup.submit("goldSnapshot", priority = WARMUP_PRIORITY_TIER) {
            warmGoldSnapshot()
        }
        viewModelScope.launch(Dispatchers.IO) {
            val pm = app.getSystemService(PowerManager::class.java)
            while (isActive) {
                _batterySaver.value = runCatching { pm?.isPowerSaveMode == true }.getOrDefault(false)
                delay(BATTERY_SAVER_POLL_MS)
            }
        }
        // R163 B4：充电态 60s 轮询（BatteryManager 直查——零权限零接收器；与省电模式
        // 轮询同款节拍，时机窗口闸 L0 条件的原料）。首拍先读一次（闸启动即有真值）
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                _charging.value = readChargingNow()
                delay(BATTERY_SAVER_POLL_MS)
            }
        }
    }

    /**
     * R163 B4：现读充电态。口径（B7 实测修正）：**插电**（ACTION_BATTERY_CHANGED
     * 粘性广播 EXTRA_PLUGGED——AC/USB/无线任一）**∨ 状态 CHARGING/FULL**。
     * 只看 BATTERY_PROPERTY_STATUS 不够：MuMu 模拟器报 AC powered=true 但
     * status=DISCHARGING(3)——真机也可能有电池服务怪癖；时机闸 L0 的语义是
     * 「不耗电池电」= 插着电，EXTRA_PLUGGED 才是主判据。registerReceiver(null)
     * 只取粘性广播零注册零权限；异常回落 false（没证据宁可不充电=闸更保守）。
     */
    internal fun readChargingNow(): Boolean = runCatching {
        val bm = app.getSystemService(android.os.BatteryManager::class.java)
        val status = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS) ?: 0
        val statusCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
        val sticky = app.registerReceiver(
            null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
        )
        val plugged = sticky?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        plugged != 0 || statusCharging
    }.getOrDefault(false)

    /** 智能降档当前是否生效：开关开 且（计量网络 或 省电模式）。VideoPage 起播选档与预载深度共用此口径 */

    // ---- 观看记账公共 API 委托（实现见 FeedWatchPart.kt） ----
    fun recordMediaView(item: VideoItem, watchMs: Long) = recordMediaViewImpl(item, watchMs)

    /** 观看序列共现：上一次"有效观看"的频道 + 时刻（30 分钟会话窗判定用；@Volatile 跨线程读写） */
    @Volatile
    internal var lastWatchedChannel: Pair<Long, Long>? = null

    /**
     * 加载下一页。
     * 竞态防护（EDGE-HIGH-9 切源竞态 / P1-11 _items 跨线程 read-modify-write）：
     *  - 源捕获：进入时把可变的 source 抓到局部 val src，整轮拉取/已看判定都用它，
     *    不逐轮重读——切源后旧轮续拉的仍是旧源，且结果在写回前被代次检查丢弃；
     *  - 代次（loadGen）：switchSource/refresh/新一轮 loadMore 都会 +1，
     *    本协程每页返回后与收尾时比对，不等即静默退出（结果与错误都不写回）；
     *  - 放闸归谁：finally 里仅当代次仍匹配才清 _loading/写 _error——切源/刷新已把闸
     *    交给新一轮 loadMore，旧轮不得误放（误放会让同源两轮并发 nextPage 抢游标）；
     *  - _items 一律 CAS update{}：IO 线程翻页与主线程屏蔽/不感兴趣剔除并发时互不覆盖。
     */
    fun loadMore() {
        if (_loading.value) return
        _loading.value = true
        val gen = loadGen.incrementAndGet()
        val src = source // 捕获当前源：整轮不再重读可变的 source
        // 追更置顶判定：仅聚合源（Aggregate/WeightedAggregate）且本轮是首页加载
        //（列表为空 = 初始/切源/刷新后的第一轮 loadMore；R52：快照垫底的首轮同算——
        // 快照只是过渡，新帖置顶照常）时，本轮收尾后触发置顶扫描
        val pinFreshAfterLoad = (_items.value.isEmpty() || _feedSnapshotActive.value) &&
            (src is AggregateFeedSource || src is WeightedAggregateFeedSource)
        // IO 调度：nextPage 内部有本地过滤/打分/落盘等同步工作，不能占用主线程
        viewModelScope.launch(Dispatchers.IO) {
            var errorMsg: String? = null
            // 队列保底链式续拉标记：本轮收尾判定后置位，finally 放闸后立即再拉一轮
            var chainMore = false
            try {
                var pulls = 0
                // 类型 tab 的正路（音频/图文 tab 修"刷不出来"）：聚合源 + 选定类型时，
                // 不走"全量爬再本地过滤"（稀疏音频 ~1 条/页，深挖慢且被已看过滤吃光），
                // 改走服务端媒体过滤（SearchChatMessages+filter，每页全是该类型）
                val tab = _mediaTypeTab.value
                // 类型 tab 的正路：聚合源 + 单频道源都走服务端媒体过滤
                //（频道页+音频 tab 实测：本地过滤挖 12 页挖不到老音频，服务端过滤直接命中）
                val useMediaFetch = tab != -1 &&
                    (src is AggregateFeedSource || src is WeightedAggregateFeedSource || src is ChannelFeedSource) &&
                    gateway is TdLibGateway
                // 整页滤空时自动续拉下一页（防空屏），单次 loadMore 最多连拉 FILTER_MAX_PULLS 页；
                // 有存活条目、已到底（nextCursor=null）或拉取失败时停止。
                // 修了音频 tab 空屏：稀疏类型（音频在聚合里 ~1 条/页）在 3 页上限内找不到货，
                // 选定具体类型 tab（非全部）时上限提到 12 页——继续往深挖直到找到为止。
                // 媒体 tab 抽空期间（mediaTabExhausted 置位到某轮真有入列为止）每一轮都是
                // "复活探测轮"：收窄到 REVIVE_PROBE_MAX_PULLS 窗——探测新帖足够；全深度重扫
                // 全是已知内容被过滤链吃掉，纯烧请求还不出货
                // 轮首快照：本轮是否"抽空后的探测轮"（收尾判定链式续拉要用——探测轮颗粒无收
                // 就不再链，避免沿着已知内容一路深扫）
                val wasMediaExhausted = mediaTabExhausted
                val maxPulls = when {
                    useMediaFetch && src !is ChannelFeedSource && wasMediaExhausted ->
                        REVIVE_PROBE_MAX_PULLS
                    tab == -1 -> FILTER_MAX_PULLS
                    else -> FILTER_MAX_PULLS * 4
                }
                // 聚合源的频道清单不再在这里预取（旧实现 take(40) 把 40 名以后的频道永久挡在
                // 类型 tab 门外，是"图文频道没展示全部来源"的根因）：fetchAggregatedMediaAll
                // 在网关内自取全量清单（listSearchableChats 含群组，TTL 缓存）并按窗口逐页轮转
                // 覆盖全部；这里只保留单频道源的 id（单频道无"频道覆盖"问题，维持原路径）
                val mediaChatIds: List<Long> = when {
                    !useMediaFetch -> emptyList()
                    src is ChannelFeedSource -> listOf(src.channelId)
                    else -> emptyList()
                }

                // R145 首屏快车道种子（仅聚合媒体路径首轮：mediaTabCursor==null 且
                // 列表为快照垫底/真空）：取"上次会话确认有货"的频道集合——快照条目的
                // chatId（按兴趣序）优先，无快照回落订阅清单（磁盘秒读，零网络）。
                // 种子交给网关快车道（fetchAggregatedMediaAllFast）：跳过全量枚举与
                // 40 频道窗口，8 路一波出首屏内容（正路整窗聚合由后续翻页接力）。
                // 刷新（用户主动下拉/双击）不走快车道：那时已有内容，整窗口径不变。
                // R170 种子类型感知（榜单 #7：音频/图文 tab 冷缓存 60~80s 暗骨架）：
                // 稀疏类型（图文/音频）的快照/订阅种子是视频频道，类型盲查询≈零命中
                // ——快车道空手而回，首屏退化为整窗慢扫（枚举+40 频道窗口）暗等一分钟。
                // 稀疏类型改用深索引 tier 有货频道（indexedMediaTier，R92 内容分层
                // 同一把尺），详见 computeFastLaneSeeds(mediaType)。
                val fastLaneSeeds: List<Long> =
                    if (useMediaFetch && src !is ChannelFeedSource && mediaTabCursor == null &&
                        (_feedSnapshotActive.value || _items.value.isEmpty())
                    ) {
                        computeFastLaneSeedsAwaitingTier(tab)
                    } else {
                        emptyList()
                    }

                var appendedThisRound = 0 // 本轮实际入列条数（过滤/去重/排序后）
                var sawExhausted = false // 本轮见过"游标到底"（nextCursor == null）
                var revivedThisRound = false // 本轮已做过一次"抽空换窗续拉"（一轮最多一次）
                // R216 供给受阻计数：本轮真正拿到页（拉取成功返回）的次数。区分
                // 「网络失败轮」（一页都没回来，errorMsg 非空——不该计入触底自愈武装，
                // 网络恢复后还有货）与「供给受阻轮」（页都回来了但 0 入列——过滤墙
                // 实锤，见下方 stuck 判据）
                var pagesArrived = 0
                while (pulls < maxPulls) {
                    pulls++
                    val result = runCatching {
                        if (useMediaFetch) {
                            val lib = gateway as TdLibGateway
                            if (src is ChannelFeedSource) {
                                // 单频道源：维持原服务端过滤拉取（频道内分页，无频道覆盖问题）
                                lib.fetchAggregatedMedia(
                                    chatIds = mediaChatIds,
                                    mediaType = tab,
                                    cursor = mediaTabCursor,
                                    // 每页批量 100：这是"每批拉多少"不是"总量上限"——
                                    // 翻页是无限的（滑到底自动续拉），批量大自然装得满
                                    limit = 100,
                                )
                            } else if (mediaTabCursor == null && fastLaneSeeds.isNotEmpty()) {
                                // R145 首屏快车道：首轮用种子频道抢跑（快照/订阅而来），
                                // 返回的游标带种子频道断点，下一轮起回落正路整窗聚合
                                lib.fetchAggregatedMediaAllFast(
                                    mediaType = tab,
                                    limit = 100,
                                    seedChatIds = fastLaneSeeds,
                                )
                            } else {
                                // 聚合源：全频道分页窗口版（修"图文 tab 只出前 40 频道"）——
                                // 网关内按窗口轮转逐页覆盖全部频道，单页请求包络不变（≤40 频道/页）
                                lib.fetchAggregatedMediaAll(
                                    mediaType = tab,
                                    cursor = mediaTabCursor,
                                    limit = 100,
                                )
                            }
                        } else {
                            src.nextPage()
                        }
                    }
                    // 协程取消不吞：runCatching 会连 CancellationException 一起兜住，必须转抛
                    result.exceptionOrNull()?.let {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                    }
                    // 拉取返回后验明代次与源：已被切源/刷新取代 → 本页直接丢弃（旧源不灌新列表）
                    if (loadGen.get() != gen || source !== src) break
                    val page = result.getOrNull()
                    if (page == null) {
                        errorMsg = result.exceptionOrNull()?.message
                        break
                    }
                    pagesArrived++ // R216：页回来了（拉取成功）——供给受阻判定的原料
                    // R286 新帖快通道：feed 拉到的页（已过 toVideoItem 媒体过滤）顺手
                    // 入索引——「刚发的」不等头刷立刻可搜。fire-and-forget（独立协程，
                    // 不占拉页循环；在飞闸防页页叠加——丢页由头刷兜底不饿死）；
                    // 用户拉黑频道不写入；失败静默绝不影响主链路（R239 同款纪律）
                    if (page.items.isNotEmpty() && gateway is TdLibGateway &&
                        feedIngestInFlight.compareAndSet(false, true)
                    ) {
                        val blockedNow = _blockedChannels.value
                        val ingestItems = page.items.filter { it.chatId !in blockedNow }
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                val n = deepIndex.ingestBrowsedPages(ingestItems)
                                if (n > 0) {
                                    android.util.Log.d("FeedViewModel", "R286 新帖快通道：feed 页顺手入索引 +$n 条（页 ${page.items.size} 条）")
                                }
                            } catch (_: Throwable) {
                            } finally {
                                feedIngestInFlight.set(false)
                            }
                        }
                    }
                    // R109 M2：游标推进挪到代次验明之后——在飞旧轮迟到返回时本轮已 break，
                    // 旧轮不再推进游标；旧写法拉取一返回就写 mediaTabCursor，
                    // 切源/刷新刚重置的游标被旧轮覆盖，新轮从错位位置续拉（跳页漏内容）
                    if (useMediaFetch) mediaTabCursor = page.nextCursor
                    if (page.nextCursor == null) sawExhausted = true
                    // 媒体 tab 抽空标记（聚合媒体路径专用）：本轮见过 allEnded 即置位——
                    // 置位期间每一轮都被上方 maxPulls 收窄成探测轮（首探测轮的 cursor==null
                    // 触发网关"换窗重扫"语义），直到某轮真有入列（新帖到了）才在收尾清零
                    // 恢复全量连拉
                    if (useMediaFetch && src !is ChannelFeedSource && page.nextCursor == null) {
                        mediaTabExhausted = true
                    }
                    // 轮内换窗续拉（修"抽空→等下次刷新"）：全部 tab 聚合路整页空且游标到底
                    // = 本轮窗口池抽干——立刻 reset 换下一轮（fetchAggregated 重抽新轮换池，
                    // 轮换采样与游标格式都不动），同一轮里接着拉，不再推给用户的下一次刷新。
                    // 一轮最多一次防空转；媒体 tab 不走这里（它的 cursor==null 是全频道从顶
                    // 重扫语义，必须收窄成探测轮，由上方 maxPulls 分支处理）。
                    // GatewayFeedSource 到底后 nextPage 无网秒回 (空, null)，正好落进本分支
                    if (!useMediaFetch && page.items.isEmpty() && page.nextCursor == null &&
                        !revivedThisRound &&
                        (src is AggregateFeedSource || src is WeightedAggregateFeedSource)
                    ) {
                        revivedThisRound = true
                        src.reset()
                        android.util.Log.i("FeedViewModel", "聚合源抽空，轮内换窗续拉（重抽轮换池）")
                        continue
                    }
                    val blocked = _blockedChannels.value
                    val disliked = _dislikedKeys.value
                    // 永久屏蔽（内容指纹黑名单）：拆成 key 集与指纹集，便于 O(1) 判定；
                    // 每页现读 StateFlow，屏蔽管理解封后下一轮拉取即生效
                    val permBlockedKeys = _blockedItems.value.mapTo(HashSet()) { it.key }
                    val permBlockedUids = _blockedItems.value.mapNotNullTo(HashSet()) { b ->
                        b.fileUniqueId.takeIf { it.isNotEmpty() }
                    }
                    val f = _filter.value
                    val known = _items.value.mapTo(HashSet()) { it.key }
                    // 已看彻底过滤（硬剔除，双轨判重）：仅聚合场景（Aggregate/WeightedAggregate）生效；
                    // 收藏夹/稍后再看/历史等本地源与单频道/搜索源不受影响（否则本地源会被自己滤空）。
                    // 判定依据 = item.key 精确命中 + 内容指纹 fileUniqueId 命中（同一视频被转发到
                    // 多个频道时 key 不同、指纹相同，只按 key 滤会漏掉"换台又见同片"）；
                    // 底账 seen_ids.log 容量 100000，不受观看历史 500 条上限淘汰影响。
                    // store.loadSeenIdentities 命中内存缓存，IO 线程上读取开销可忽略。
                    // R337：判定收口 SeenDedupPolicy（双轨同尺+严格契约）——R94 触底重温
                    // 兜底态（rewatchRelaxSeen）的已看段暂时解除路已退役：刷过了不重复推
                    // =严格排除（用户 19:12 原话语义），饥饿处置见下方「R337 供给饥饿」
                    val (seenKeys, seenUids) = if (excludeWatchedActive(src)) {
                        store.loadSeenIdentities()
                    } else {
                        emptySet<String>() to emptySet()
                    }
                    // 内容类型开关（设置页：视频/图片/音频）；prefs 内存读，IO 线程安全
                    val prefsNow = dev.tgfeed.core.prefs.AppPrefs(app)
                    // 内容分区 tab 用轮首捕获的 tab（整轮同一源同一 tab；切 tab 走 refresh
                    // 作废旧轮，不再每页重读——旧写法切 tab 中途会把新 tab 的过滤口径
                    // 混进旧源页里）
                    // 去重 + 剔除被屏蔽频道 + 剔除"不感兴趣"单条 + 剔除永久屏蔽（内容指纹，
                    // 跨频道转发同片也滤掉）+ 剔除已看（聚合源且开关开时）
                    // + 内容筛选谓词（屏蔽/不喜欢/已看/设筛选后新拉的结果也不再进入列表）
                    // + 内容类型开关
                    // 过滤谓词提为共享 lambda（屏蔽/不感兴趣/永久指纹/已看/内容筛选/类型开关/tab）：
                    // 正页条目与历史回溯注入条目走完全相同的过滤链——老内容也要守规矩。
                    // R337：永久指纹段与已看段的"key+fileUniqueId 双轨"判定收口
                    // SeenDedupPolicy.isSeen（同一把尺三链共用，不再各写一份）
                    // R413 关键词屏蔽：经 keywordMuteActive 豁免用户显式圈定的源（单频道/
                    // 收藏/稍后/历史/播放队列——主动进去看的内容不替用户藏）；其余源与
                    // 回溯/召回/新面孔注入共用本 pass，一处接入全链生效
                    val kwMatcher = if (keywordMuteActive(src)) keywordMatcher() else KeywordMutePolicy.EMPTY
                    val pass: (VideoItem) -> Boolean = {
                        it.chatId !in blocked && it.key !in disliked && !kwMatcher.hits(it) &&
                            !SeenDedupPolicy.isSeen(it, permBlockedKeys, permBlockedUids) &&
                            !SeenDedupPolicy.isSeen(it, seenKeys, seenUids) &&
                            f.matches(it) &&
                            when (it.mediaType) {
                                0 -> prefsNow.showVideos
                                1 -> prefsNow.showPhotos
                                else -> prefsNow.showAudio
                            } && (tab == -1 || it.mediaType == tab)
                    }
                    // 去重（key 不在当前列表=会话未供给）+ 已看双轨 + 共享过滤谓词
                    // （R337：查重段收口 SeenDedupPolicy.notServedAndUnseen——
                    // 「会话内不重复供给 ∧ 已刷不再推」的判定体唯一出处）
                    val kept = page.items.filter {
                        SeenDedupPolicy.notServedAndUnseen(it, known, seenKeys, seenUids) && pass(it)
                    }
                    // R150 观测证据链：本页已看过滤命中数（A 修复的 logcat 证据——
                    // 网关源头过滤（R150）+ 本 pass 谓词双轨合计口径；0 也打，
                    // 与"本页折叠重复 0 条"同款对照验证口径。开关关闭态下 seenKeys
                    // 为空集，本行恒 0（口径随开关如实呈现）。
                    // R337：判定同收口 SeenDedupPolicy.isSeen
                    val seenHitCount = page.items.count { SeenDedupPolicy.isSeen(it, seenKeys, seenUids) }
                    if (!kwMatcher.isEmpty && page.items.isNotEmpty()) {
                        logKeywordMuteProbe("主Feed", kwMatcher, page.items.count { kwMatcher.hits(it) })
                    }
                    if (page.items.isNotEmpty()) {
                        android.util.Log.i(
                            "FeedViewModel",
                            "本页已看过滤命中 $seenHitCount 条（页 ${page.items.size} 条→入列候选 ${kept.size} 条）",
                        )
                    }
                    // 历史回溯注入（老内容挖掘）：每 [BACKFILL_EVERY_PAGES] 页从深度索引随机抽
                    // [BACKFILL_PER_PAGE] 条老内容并入本页；excludeKeys = 已出现过的 key
                    //（当前列表 + 本页，防重复刷到）；索引未建/为空/失败静默跳过（返回空表）
                    backfillPages++
                    val backfill = if (backfillPages % BACKFILL_EVERY_PAGES == 0) {
                        // SQL 层 NOT IN 减负（超上限截断）+ sampleHistoryBackfill 内全量复查双保险
                        val exclude = known + kept.map { it.key }
                        // 已看双轨身份：回溯是算法推送（专挖老内容，正是"很久以前看过"的重灾区），
                        // 与正页不同——对所有可注入源生效，包括单频道源（其正页不过滤已看是
                        // 刻意保留的翻频道历史能力，但注入的老内容必须守"已看不出现"）；
                        // 开关关掉时传 null，回溯完全恢复旧行为（R337：唯一解除条件=
                        // 用户开关——旧 R94 重温兜底解除路已退役）
                        val backfillSeen = if (SeenDedupPolicy.hardExcludeOn(_excludeWatched.value)) {
                            store.loadSeenIdentities()
                        } else {
                            null
                        }
                        sampleHistoryBackfill(src, tab, exclude, pass, backfillSeen)
                    } else {
                        emptyList()
                    }
                    // R298 真推荐引擎四路召回的 R2/R3 两路注入（design-recommendation.md §3）：
                    // 仅聚合源生效（单频道/本地清单/搜索源语义明确不污染，与回溯注入同边界）。
                    // R2=tag 兴趣路（兴趣轴 top 标签轮转 × tagItemsByRelevance 毫秒级倒排）；
                    // R3=互链图谱路（C 轴 top 频道邻居×金矿验货×深索引近端直出，零网络）。
                    // 已看双轨口径与回溯注入完全一致（开关关=传 null 不排除——R337 起
                    // 唯一解除条件=用户开关，旧 R94 重温兜底解除路已退役）；
                    // 每页都注（兴趣召回是主供给增强，不是 R4 探索那种节拍注入）。
                    val recallSeen = if (SeenDedupPolicy.hardExcludeOn(_excludeWatched.value)) {
                        store.loadSeenIdentities()
                    } else {
                        null
                    }
                    val aggregateRecallSrc =
                        src is AggregateFeedSource || src is WeightedAggregateFeedSource
                    val recallExclude = known + kept.map { it.key } + backfill.map { it.key }
                    val interestRecall = if (aggregateRecallSrc) {
                        runCatching {
                            recallByInterest(tab, recallExclude, pass, recallSeen, page.items.size)
                        }.getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }
                    val graphRecall = if (aggregateRecallSrc) {
                        runCatching {
                            recallByGraph(tab, recallExclude, pass, recallSeen, page.items.size)
                        }.getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }
                    // P0-1：媒体 tab 网关直拉页接 v2 加权排序（修"默认视频 tab 是纯 shuffled()、
                    // v2 排序机器成死代码"）——fetchAggregatedMediaAll 是网关公共方法不能就地改口径，
                    // 故在入列前对页结果过一遍 v2 机器：decayedChannelWeights（时间衰减权重 +
                    // 互动加分表）打分 → 文件级去重 → 加权随机重排 → 同频道打散，
                    // 与「全部」tab 的 WeightedAggregateFeedSource 同一套口径；
                    // 常看/高互动频道的内容在页内明显靠前。排序是页内的，跨页仍是拉取先后。
                    // 单频道源（ChannelFeedSource）页内全同频道，打分无区分度，跳过维持网关序。
                    // 历史回溯条目与本页合并：聚合源并入后统一过排序机器（加权随机天然把注入
                    // 条目分散到页内随机位，不硬插队；无数据频道按 EXPLORE_WEIGHT 探索权重兜底，
                    // 老内容自然获得曝光机会）；单频道源注入条目改为随机位分散插入（不堆尾部）
                    val merged = if (backfill.isEmpty() && interestRecall.isEmpty() && graphRecall.isEmpty()) {
                        kept
                    } else if (src is ChannelFeedSource) {
                        spreadInsertRandom(kept, backfill)
                    } else {
                        kept + backfill + interestRecall + graphRecall
                    }
                    // 排序条件 = 原口径（媒体 tab 网关直拉页）∪ 有注入（让老内容同守 v2 排序规矩）；
                    // 「全部」tab 聚合源无注入时不重排（页已被 WeightedAggregateFeedSource 排过），
                    // 有注入时合并重排一次（同一套机器，口径一致）；
                    // R298：兴趣/图谱召回注入同算「有注入」——注入条目必须过同一套排序机器
                    //（兴趣乘区在 rankWeightedPage 内生效：注入条目按兴趣分参与主序竞争，
                    //  不是硬插队——多样性硬约束最后执行，不可击穿）
                    // P1.5：本页排序实际使用的频道权重表（疲劳探索补位复用，避免二次计算）；
                    // 未走排序分支的路径保持 null（补位函数内部按需现算）
                    var pageWeights: Map<Long, Double>? = null
                    val ranked = if (src !is ChannelFeedSource && merged.size > 1 &&
                        (useMediaFetch || backfill.isNotEmpty() ||
                            interestRecall.isNotEmpty() || graphRecall.isNotEmpty())
                    ) {
                        // P1 #4：权重输出乘会话系数——本会话连续快划/不感兴趣的频道立即沉底，
                        // 连续完播/互动的频道立即靠前（内存态，当日生效不等衰减）；
                        // P2：双轨融合统一出口（短期轨按 α 融入，"今天的手感"直接参与页内排序）；
                        // R66：再过稀缺性上浮——低频宝藏频道的 48h 内新帖权重 ×2.5
                        //（口径与日志见 applyScarcityBoost；无命中频道时零开销直通）
                        val weights = applyScarcityBoost(p2ChannelWeights(), merged)
                        pageWeights = weights // P1.5：疲劳补位复用本表，不重复计算
                        // 已看集合：已看过滤关时上方 seenKeys 为空集，这里现读判重底账补一份供
                        // SEEN_PENALTY 软降权（loadSeenIdentities 命中内存缓存，IO 线程开销可忽略；
                        // key 集覆盖比 500 条历史更全——底账容量 100000 不受历史淘汰影响）
                        val seenForRank = seenKeys.ifEmpty {
                            store.loadSeenIdentities().first
                        }
                        rankWeightedPage(merged, weights, seenForRank)
                    } else {
                        merged
                    }
                    // P1.5 行为信号页内后处理（纯追加，不动上游六段链与过滤结论）：
                    // #4 会话序列动量（同主题条目有界前移 ≤2 位）→ #5 疲劳探索补位
                    //（会话观看 >45 分钟后探索 ≈10%→20%）；均无信号时原样直通
                    // R387 队列序保全：播放队列源跳过 P1.5/P2 页内后处理（有界前移
                    // 会打乱显式节目单的段序/轮序——这两环对一切源生效无源判别，
                    // 队列=显式清单语境须点名豁免；P1.5/P2 的重排价值在兴趣流，
                    // 节目单用户要的是「我排的顺序」字面兑现）
                    val queueSrc = src is PlayQueueFeedSource
                    val rankedP15 = if (queueSrc) ranked else applyP15PostRank(ranked, pageWeights)
                    // P2 序列三件套页内消费（DIN 目标注意力 + 马尔可夫转移加分，有界前移
                    // ≤[P2_PROMOTE_MAX] 位；画像为空/无马尔可夫上下文时原样直通）
                    val rankedP2 = if (queueSrc) rankedP15 else applyP2SequenceRank(rankedP15)
                    // R98 每页新面孔保底（分页层硬保证：本页没有就不算完）——不足配额从
                    // 新面孔名册经深度索引抽样补位（供给接 R92 内容分层"有货"口径，
                    // 空频道不进名册），观测日志每页一行"本页新面孔：N 个（频道尾号…）"
                    val rankedR98 = ensureNewFacesInPage(src, tab, rankedP2, known, pass)
                    // R307b 观测：本页入列内容的发布年代桶分布（只出桶计数，零原文零
                    // 时间戳——§七脱敏口径；排查「老内容刷不到」的供给面实锤件，与
                    // 「本页新面孔」「本页折叠重复」同款每页一行观测纪律）
                    if (rankedR98.isNotEmpty()) {
                        val nowSec = System.currentTimeMillis() / 1000
                        var b7 = 0; var b30 = 0; var b90 = 0; var b365 = 0; var bOld = 0; var b0 = 0
                        for (it in rankedR98) {
                            val age = nowSec - it.dateEpochSec
                            when {
                                it.dateEpochSec <= 0 -> b0++
                                age <= 7L * 86400 -> b7++
                                age <= 30L * 86400 -> b30++
                                age <= 90L * 86400 -> b90++
                                age <= 365L * 86400 -> b365++
                                else -> bOld++
                            }
                        }
                        android.util.Log.i(
                            "FeedViewModel",
                            "本页年代桶：7天内=$b7 30天内=$b30 90天内=$b90 一年内=$b365 " +
                                "一年以上=$bOld 未知=$b0（页 ${rankedR98.size} 条）",
                        )
                    }
                    // R126 全网重发折叠（入列前收尾闸，同片多源只出现一次）：
                    // 判据 = contentFoldId（硬判据 fileUniqueId——TG 服务端级文件身份证，
                    // 同一视频被搬运到多个频道时 key 不同、指纹相同；无指纹时 caption+时长
                    // 软判据兜底，宁漏勿错杀）。页内同片已由 rankWeightedPage ① 折叠，
                    // 本闸兜"跨页/跨路径"：回溯注入、新面孔补位、单频道源直排页，以及
                    // 网关在后续页再次吐出同片——身份命中当前列表或本页先前者直接滤掉，
                    // 先入列者保留。已看过的同片不走这里：上方已看双轨硬剔除早已拦下。
                    // 生效面 = 一切"刷"型源（聚合/单频道/搜索/合集/订阅追更）；收藏夹/
                    // 稍后再看/历史三个本地清单源刻意豁免——那里每条都是用户显式收藏/
                    // 暂存/看过的记录，折叠会让"清单条数与记录数对不上"（与已看过滤的
                    // "刻意不生效的面"同一原则：显式语境不做隐式归并）。
                    // 身份集在 CAS 内从当前列表现算（P1-11：与主线程的 filterNot 剔除并发
                    // 时互不丢更新），且切源/刷新清空列表后折叠口径自动归零，无需额外会话状态。
                    val foldable = src !is FavoritesFeedSource &&
                        src !is WatchLaterFeedSource && src !is HistoryFeedSource &&
                        // R387 播放队列=显式清单语境（条目用户显式排入）：折叠会让
                        //「清单条数与记录数对不上」——三本地清单源同原则（前任遗言
                        // 「重发折叠需 1 行豁免」的落点）；重发条目在队列里按段各就各位
                        src !is PlayQueueFeedSource
                    var foldDropped = 0
                    var foldSurvived = 0
                    // R189 追加前代次复检（前任移交 H②，预存 TOCTOU——轨道切换高频化放大
                    // 暴露面）：下方代次检查在「页返回后」，而本 _items.update 在几秒宽的
                    // 过滤/排序机器之后——主线程切 tab/换列恰落窗内则旧轨道页仍灌进新列表
                    //（切源/刷新同样预存）。追加前补一发同款 break，旧轮产物就地作废
                    if (loadGen.get() != gen || source !== src) break
                    _items.update { cur ->
                        if (!foldable) {
                            foldSurvived = rankedR98.size
                            cur + rankedR98
                        } else {
                            val foldKnown = cur.mapNotNullTo(HashSet()) { contentFoldId(it) }
                            val (survivors, dropped) = foldReposts(rankedR98, foldKnown)
                            foldDropped = dropped
                            foldSurvived = survivors.size
                            cur + survivors
                        }
                    }
                    // R126 观测：每页一行折叠计数（0 也打，供刷 Feed 时对照验证）
                    if (foldable) {
                        android.util.Log.i("FeedViewModel", "本页折叠重复 $foldDropped 条（同片多源）")
                    }
                    appendedThisRound += foldSurvived
                    // R52 冷启动打点：首屏内容首次到货（每进程只打一次）
                    if (rankedP2.isNotEmpty() && firstContentMarked.compareAndSet(false, true)) {
                        dev.tgfeed.core.diag.ColdStartProbe.mark(
                            "首页首拉到货（本轮入列 ${rankedP2.size} 条，累计 ${_items.value.size} 条）",
                        )
                    }
                    if (page.nextCursor == null) break // 到底即收（抽空换窗已在上面处理过）
                    // 队列保底（修"刷到头干等"的同谋）：旧口径"一页有一条幸存即收工"，
                    // 快刷时补给速度跟不上消耗速度；新口径——当前位置之后不足
                    // FEED_QUEUE_MIN 条就继续连拉补足（上限 maxPulls 不变）
                    val remainingNow = _items.value.size - _currentIndex.value
                    if (kept.isNotEmpty() && remainingNow >= FEED_QUEUE_MIN) break
                    // R216 干墙熔断（用户实锤「音频 tab 固定数量后上滑不加载」的干爬半价刀）：
                    // 媒体 tab 本轮入列为零且列表非空 = 已看/判重墙（页页回货全被过滤链
                    // 吃光）——旧行为一轮干爬满额 12 窗（数百请求、数十秒~分钟级零产出，
                    // 期间 _loading 闸锁死一切触发器）。收窄到 MEDIA_TAB_DRY_PULLS_CAP
                    // 让供给受阻更快浮出（轮尾武装计数+断链闸接管）。冷启动（列表空，
                    // 稀疏类型靠满额连拉找首批货）与有入列轮（上方队列保底 break 先行）
                    // 都不进本分支，行为不变。决策收在 MediaTabPaging（单测锚）。
                    if (dev.tgfeed.core.feed.MediaTabPaging.dryPullsBreak(
                            useMediaFetch, appendedThisRound, _items.value.size, pulls,
                        )
                    ) {
                        android.util.Log.i(
                            "FeedViewModel",
                            "R216 干墙熔断：本轮连拉 ${pulls} 页 0 入列（列表 ${_items.value.size} 条），" +
                                "提前收轮进供给受阻判定",
                        )
                        break
                    }
                }
                // R324 离线降级时点戳：本轮真有页到货（拉取成功返回，pagesArrived>0）
                // ——本会话数据时点推进到现在（TDLib 本地库离线出活也算到货，诚实）；
                // 全败轮不戳（时点停在最后一次真到货，banner 不编造新鲜度）
                if (pagesArrived > 0) {
                    _lastFreshAtMs.value = System.currentTimeMillis()
                }
                // R52：聚合首页首拉到货收尾 → 覆盖写"上次退出前第一屏"快照（下次冷启动
                // 秒上屏用）。仅默认视频 tab（tab==0）的聚合首页入选——冷启动落的正是这屏；
                // 本协程在 IO 线程，小文件（≤20 条 + minithumbnail Base64）原子写同步完成无妨
                if (pinFreshAfterLoad && tab == 0 && _items.value.isNotEmpty()) {
                    runCatching {
                        // 本轮有快照垫底时：新货排前、旧快照条目垫底——快照随每轮首拉滚动保鲜，
                        // 不会把"上次那批"越存越陈；无快照则原样存列表头
                        val snapKeys = snapshotKeys
                        val base = if (snapKeys.isEmpty()) {
                            _items.value
                        } else {
                            _items.value.filter { it.key !in snapKeys } +
                                _items.value.filter { it.key in snapKeys }
                        }
                        // R94：落盘前剔除此刻已看的条目（首屏落定即入底账，快照存的就是
                        // 列表头，不剔则下次冷启动把"刚看过的"原样摆回首屏）；
                        // 上屏侧的同款过滤是兜底（覆盖会话中途看过的）。
                        // R337：判定收口 SeenDedupPolicy.isSeen（双轨同尺）
                        val (snapSaveSeenK, snapSaveSeenU) = runCatching { store.loadSeenIdentities() }
                            .getOrDefault(emptySet<String>() to emptySet())
                        val baseFresh = base.filter { !SeenDedupPolicy.isSeen(it, snapSaveSeenK, snapSaveSeenU) }
                        store.saveFeedSnapshot(
                            baseFresh,
                            if (gateway is MockTdGateway) "demo" else "real",
                        )
                        dev.tgfeed.core.diag.ColdStartProbe.mark("首页第一屏快照已落盘")
                    }
                }
                // 追更强信号：聚合首页第一页加载完成 → 异步扫描订阅频道 24h 内未看的新内容，
                // 插入到当前播放位置之后（禁区原则：不动前缀，避免索引锚定的 pager 把已看内容
                // 推回用户面前；另起 IO 协程，不拖慢本路径的 _loading 放闸——第一页先渲染，
                // 追更条目随后到位；扫描期间若发生切源/刷新，由插入前的代次与源校验兜底丢弃，
                // 见 pinFreshSubscriptionItems）
                if (pinFreshAfterLoad && loadGen.get() == gen && source === src) {
                    pinFreshSubscriptionItems(gen, src)
                }
                // R170b 关注流首页快照落盘：真实关注轮收尾滚动保鲜垫底内容（与预热任务
                // 同一份快照——预热先写、真实浏览后到覆盖，切 tab/下次冷启秒出）。
                // 落盘前过已看（R94 口径，与 feed 快照同纪律）；空列表不写。
                if (src is FollowingFeedSource && _items.value.isNotEmpty()) {
                    runCatching {
                        val (snapK, snapU) = runCatching { store.loadSeenIdentities() }
                            .getOrDefault(emptySet<String>() to emptySet())
                        val head = _items.value.filter { !SeenDedupPolicy.isSeen(it, snapK, snapU) }
                        if (head.isNotEmpty()) {
                            store.saveFollowingSnapshot(
                                head,
                                if (gateway is MockTdGateway) "demo" else "real",
                            )
                        }
                    }
                }
                // 记忆播放：首屏第一页到位后恢复上次浏览的条目位置（幂等一次；
                // 代次/源校验与上方置顶同款——切源/刷新后的旧轮收尾不得触发恢复）
                if (loadGen.get() == gen && source === src) {
                    tryRestorePosition()
                }
                // 队列保底链式续拉判定（修"刷到头干等"的核心，finally 放闸后触发）：
                //  - 队列不足 FEED_QUEUE_MIN 才链；
                //  - 连续空轮（0 入列）超过 AUTO_CHAIN_DRY_MAX 即停（真没货/过滤链全吃，
                //    不烧请求空转；等用户翻页/刷新/新帖重新激活）；
                //  - 抽空即终态的源（本地一次性源/单频道/搜索到底）不链——它们到底就是
                //    真到底；聚合源抽空可链：链出的下一轮走"换窗复活"（全部 tab 轮内续拉
                //    重抽轮换池 / 媒体 tab 探测轮）。
                // onPage 只在翻页落定时触发——用户停在尾页不动时旧实现永远不再拉，
                // "等好一阵子才出来"等的就是用户自己蹭一下；这里放闸后主动续拉补掉这个死等
                if (appendedThisRound > 0) {
                    dryRoundStreak = 0
                    mediaTabExhausted = false // 真有入列：媒体 tab 复活成功，恢复全量连拉
                } else {
                    dryRoundStreak++
                }
                val remainingTail = _items.value.size - _currentIndex.value
                val aggregateSrc = src is AggregateFeedSource || src is WeightedAggregateFeedSource
                val canChain = !sawExhausted || aggregateSrc
                // R337 诚实供给饥饿（取代 R94/R216 触底自愈武装，用户 19:12 实锤
                // 「刷过了就不要重复推」的语义收口）：聚合路连续空轮说明未看内容已被
                // 抽干/滤净（媒体 tab：抽空或已看墙——R216 判据「页回而 0 入列 /
                // mediaTabExhausted」沿用；全部 tab：轮内换窗续拉后仍空）——
                // 旧路在此置 rewatchRelaxSeen=true 静默解除全链已看硬剔除+游标归位
                // 重扫，稀疏类型（图/音）未看池小、两轮即武装，已刷条目整窗回灌
                // （R216 修「音频 tab 上滑不加载」时引入的另一半账）。本刀退役该路：
                // **已看排除永不静默放宽**（判定唯一输入=用户开关，见
                // SeenDedupPolicy 契约）——饥饿的诚实降级=断链停供（下方
                // mediaDryProbeStop/dryRoundStreak 链闸接管，不烧请求深扫已知内容）
                // + 本观测行现形；重温只走用户显式入口（空态「新内容刷完了，点击
                // 重温已看内容」钮=setExcludeWatched(false)，FeedScreen 既有件）。
                // 换源论证：媒体 tab 供给宇宙=全频道该类型全历史（游标链逐页深挖）
                // +R2/R3/回溯四路注入（同过已看双轨）——排除后无第三源可换，
                // 唯一诚实出路=停+显式重温入口。
                if (appendedThisRound == 0 && aggregateSrc && _excludeWatched.value) {
                    val stuck = if (useMediaFetch) {
                        dev.tgfeed.core.feed.MediaTabPaging.supplyStuck(mediaTabExhausted, pagesArrived)
                    } else revivedThisRound
                    if (stuck) {
                        android.util.Log.i(
                            "FeedViewModel",
                            "R337 供给饥饿：本轮 0 入列（页回而全被已看/判重吃光或抽空），" +
                                "保持严格排除不回灌已刷（停链由断链闸接管；重温走用户显式入口）",
                        )
                    }
                }
                // 媒体 tab 探测轮颗粒无收 → 不再链（等用户翻页/刷新/新帖再探）——
                // 否则沿着已知内容一路深扫，纯烧请求；首轮抽空（wasMediaExhausted=false）
                // 不受此限，照常链出第一个探测轮。
                // R337：旧「重温兜底态（rewatchRelaxSeen）解除本闸」随兜底旗退役——
                // 探测轮 0 收恒停链（诚实到底，不以回灌已刷换假续流）
                val mediaDryProbeStop = useMediaFetch && aggregateSrc && wasMediaExhausted &&
                    appendedThisRound == 0
                chainMore = remainingTail < FEED_QUEUE_MIN && canChain &&
                    dryRoundStreak <= AUTO_CHAIN_DRY_MAX && !mediaDryProbeStop
                if (chainMore) {
                    android.util.Log.i(
                        "FeedViewModel",
                        "队列保底续拉: remaining=$remainingTail 本轮入列=$appendedThisRound " +
                            "抽空=$sawExhausted 连续空轮=$dryRoundStreak",
                    )
                }
            } finally {
                // 只有仍是在册代次才放闸/写错误：切源/刷新已开新一轮，旧轮静默退出
                if (loadGen.get() == gen) {
                    _error.value = errorMsg
                    _loading.value = false
                    _initialContentReady.value = true // 首轮结论已出（成败都算），空态内容方可展示
                    // R52 冷启动打点：首轮拉取有结论（每进程只打一次）
                    if (firstRoundMarked.compareAndSet(false, true)) {
                        dev.tgfeed.core.diag.ColdStartProbe.mark("首轮拉取有结论（initialContentReady=true）")
                        // R145 让路闸放行：首轮结论已出，存储治理等低优先重活可以起步
                        dev.tgfeed.core.td.ColdStartQuietGate.markFeedSettled()
                    }
                    // R52：首轮结论已出（成败都算），快照过渡使命结束，"缓存"标归位
                    _feedSnapshotActive.value = false
                    // 放闸后链式再拉：loadMore 自带 _loading 闸与新一轮代次，安全重入
                    if (chainMore) loadMore()
                }
            }
        }
    }

    /**
     * R145 首屏快车道种子选取（纯本地决策，零网络零枚举）：
     *  - 快照垫底（冷启动常态）：取快照条目的 chatId 去重保序——快照存的就是
     *    上次会话首屏的兴趣序列表头，其频道已被证实"有该类型内容且值得进首屏"；
     *  - 无快照（真空启动）：回落订阅清单（磁盘已恢复，兴趣最强声明）；
     *  - 两者皆空返回空表，调用方自动回落正路整窗聚合（快车道零副作用）。
     * 数量取 [dev.tgfeed.core.td.FAST_LANE_CONCURRENCY]（8）：一波拉平。
     *
     * R170 种子类型感知（榜单 #7，修音频/图文 tab 冷缓存首开 60~80s 暗骨架）：
     *  - 根因：稀疏类型（图文/音频）的快照/订阅种子来自视频首屏/订阅清单，类型盲
     *    ——拿视频频道查音频≈零命中，快车道空手而回（16384 基线另实测：订阅异步
     *    恢复未就绪时种子直接为空、快车道静默跳过），首屏退化为"频道枚举+整窗
     *    慢扫"暗等几十秒（本机 22s，R161c 60~80s）；
     *  - 修法：稀疏类型优先深索引 tier 有货频道（[indexedMediaTier]，与 R92 保底
     *    分层同一把尺：索引内该类型条数>0 按量降序），坑位不满再按原链补位；
     *    库不可用/无货回落原快照/订阅链（行为不变）；
     *  - tab=0（视频）不动：快照本身是类型正确的兴趣序，深索引按量排序反而稀释
     *    兴趣相关性（类内覆盖原则：本修治"类型盲"，不碰"兴趣序"）。
     */
    /**
     * R170 种子路径的 tier 短等包装：首开 tap 撞上启动预热扫描的尾巴时
     * （tierScanInFlight=true 且无陈值），宁可等 [TIER_SEED_WAIT_MS] 也不立刻退回
     * 类型盲种子——退回=快车道空手回落整窗慢扫（#7 的 60~80s 暗骨架链），
     * 等 3s 换快车道命中稳赚。有陈值/扫描结束即刻继续（等待通常为 0）。
     */
    internal suspend fun computeFastLaneSeedsAwaitingTier(mediaType: Int): List<Long> {
        val deadline = System.currentTimeMillis() + TIER_SEED_WAIT_MS
        while (tierScanInFlight && indexedMediaTiers == null &&
            System.currentTimeMillis() < deadline
        ) {
            kotlinx.coroutines.delay(200)
        }
        return computeFastLaneSeeds(mediaType)
    }

    internal fun computeFastLaneSeeds(mediaType: Int = -1): List<Long> {
        val fromSnapshot = if (_feedSnapshotActive.value && _items.value.isNotEmpty()) {
            _items.value.mapTo(LinkedHashSet()) { it.chatId }
        } else {
            emptySet()
        }
        // R170 稀疏类型 tier 优先（图文/音频）：有货频道按索引条数降序 = 最可能装满
        // 首屏的种子。查表纯本地 SQLite（5 分钟 TTL 缓存与保底分层共享），零网络。
        // 本方法只在 loadMore 的 IO 协程里调（R145 既有语境），磁盘读不落主线程。
        if (mediaType == 1 || mediaType == 2) {
            val tierCounts = runCatching { indexedMediaTier(mediaType)?.first }.getOrNull()
            android.util.Log.i(
                "FeedViewModel",
                "R170 种子决策：mediaType=$mediaType tier有货频道=" +
                    (tierCounts?.count { it.value > 0 } ?: -1) + "（-1=无tier）",
            )
            if (!tierCounts.isNullOrEmpty()) {
                val tierSeeds = tierCounts.entries
                    .filter { it.value > 0 }
                    .sortedByDescending { it.value }
                    .map { it.key }
                    .take(dev.tgfeed.core.td.FAST_LANE_CONCURRENCY)
                if (tierSeeds.isNotEmpty()) {
                    // 坑位不满按原链补位（tier 有货优先，余位给兴趣序；去重保序）
                    val tierSet = tierSeeds.toHashSet()
                    val existing = fromSnapshot.ifEmpty {
                        _subscriptions.value.mapTo(LinkedHashSet()) { it.id }
                    }
                    return (tierSeeds + existing.filterNot { it in tierSet })
                        .take(dev.tgfeed.core.td.FAST_LANE_CONCURRENCY)
                }
            }
        }
        val seeds = fromSnapshot.ifEmpty {
            _subscriptions.value.mapTo(LinkedHashSet()) { it.id }
        }
        return seeds.take(dev.tgfeed.core.td.FAST_LANE_CONCURRENCY)
    }

    // ---- 下载/清理公共 API 委托（实现见 FeedDownloadPart.kt；清理 R196 起走 FeedStoragePart） ----
    fun download(item: VideoItem) = downloadImpl(item)
    fun cleanupStorage(onDone: (String) -> Unit) = cleanupStorageImpl(onDone)
    fun removeDownload(key: String) = removeDownloadImpl(key)
    fun removeDownloads(keys: Set<String>) = removeDownloadsImpl(keys)

    // ---- R196 存储管理公共 API 委托（实现见 FeedStoragePart.kt） ----
    fun refreshStorageOverview() = refreshStorageOverviewImpl()

    // ---------------- 下载记录（LocalStore downloads.json 持久化，下载管理页数据源） ----------------

    /** 已下载完成记录（下载完成时间倒序，最新在前）；DownloadsScreen 列表数据源 */
    internal val _downloadRecords = MutableStateFlow<List<DownloadRecord>>(emptyList())
    val downloadRecords: StateFlow<List<DownloadRecord>> = _downloadRecords.asStateFlow()

    init {
        // 启动时把下载记录从磁盘预载到内存（IO 线程加载，不阻塞首帧；独立 init 块便于追加维护）
        viewModelScope.launch(Dispatchers.IO) {
            _downloadRecords.value = runCatching { store.listDownloads() }.getOrDefault(emptyList())
            // 顺带预热观看历史缓存：showHistory() 的磁盘半边自 R165 起在 IO 线程现读
            //（HistoryFeedSource.nextPage），本地搜索 localSearchInstant 也全程 IO——
            // 预热从「防主线程卡 UI」降级为纯优化（早一步把缓存焐热，首切少一次读盘）
            runCatching { store.loadHistory() }
        }
    }

    /** VM 销毁时把最后一条未结算的观看时长（心跳累计的真实播放毫秒）同步落盘（量小，直接写）；顺带注销系统回调与 SP 监听 */
    override fun onCleared() {
        val prev = watchingAccum
        watchingAccum = null
        if (prev != null) {
            val (item, playedMs) = prev
            val watchSec = (playedMs / 1000L).coerceIn(0L, MAX_WATCH_SEC).toInt()
            runCatching { store.appendWatchEvent(item.chatId, watchSec, item.durationSec) }
        }
        // 记忆播放兜底：viewModelScope 取消后在途的异步 saveProgress 落盘不再执行，
        // 这里把内存进度表一次性同步写盘（已有 ts 保留，见 store.saveProgressAll）
        runCatching { store.saveProgressAll(progressMap) }
        // 智能降档：计量网络订阅随 viewModelScope 自动取消（R195 起监听单源在
        // 进程级 NetworkMeteredBus，VM 不再持有系统回调，无需注销）
        // 跨设备接力：注销 SharedPreferences 开关监听
        unregisterSyncToggleListener?.invoke()
        unregisterSyncToggleListener = null
        // R300：注销多设备状态同步开关监听
        unregisterCloudSyncToggleListener?.invoke()
        unregisterCloudSyncToggleListener = null
        // 指标闭环：VM 销毁前把统计事件流水兜底落盘（常规路径有 20s 节流自动写，
        // 这里防"最后 20 秒的事件丢档"）
        runCatching { interactionStats.flush() }
        // P2：马尔可夫转移模型兜底落盘（常规路径攒满 10 条自动写，这里防尾巴丢失）
        runCatching { interestProfile.flush() }
        // R79：隐式负反馈账本兜底落盘（常规路径有节流自动写，这里防尾巴丢失）
        runCatching { implicitFeedback.flush() }
        // R298：tag/类型兴趣账本兜底落盘（同节流口径，防尾巴丢失）
        runCatching { tagInterestLedger.flush() }
        // P4-A：金矿钩子兜底落盘（热词事件流 + 新发现表，常规路径有阈值自动写）
        runCatching { GoldmineHooks.flush() }
        // P1-d：注销图谱新边监听（广场边共享挂点；图谱归网关持有，VM 销毁必须摘钩防泄漏）
        runCatching { (gateway as? TdLibGateway)?.discoveryGraph?.newEdgeListener = null }
        super.onCleared()
    }

    // ---- 播放列表公共 API 委托（实现见 FeedPlaylistPart.kt） ----
    fun createPlaylist(name: String, firstItem: VideoItem? = null) = createPlaylistImpl(name, firstItem)
    fun deletePlaylist(id: String) = deletePlaylistImpl(id)
    fun renamePlaylist(id: String, name: String) = renamePlaylistImpl(id, name)
    fun addToPlaylist(playlistId: String, item: VideoItem) = addToPlaylistImpl(playlistId, item)
    fun removeFromPlaylist(playlistId: String, key: String) = removeFromPlaylistImpl(playlistId, key)
    fun openPlaylist(id: String) = openPlaylistImpl(id)

    /**
     * 片单元数据内存副本（与 store 缓存同源）。
     * @Volatile：写路径在 IO 线程（先重读磁盘再合并，与 addSubscription 同一纪律），读路径任意线程。
     */
    @Volatile
    internal var playlistMetas: List<PlaylistMeta> = emptyList()

    /** 片单列表（UI 卡片数据源：id/名称/条数/最新更新时间；顺序即创建先后） */
    internal val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    init {
        // 启动时把片单元数据从磁盘预载到内存（IO 线程加载，不阻塞首帧；独立 init 块便于追加维护）
        viewModelScope.launch(Dispatchers.IO) {
            playlistMetas = runCatching { store.loadPlaylists() }.getOrDefault(emptyList())
            _playlists.value = playlistMetas.map { it.toPlaylist() }
        }
    }

    // ---- R387 播放队列（节目单）公共 API 委托（实现见 FeedPlayQueuePart.kt） ----
    fun enqueueChannelToPlayQueue(chatId: Long, title: String): Boolean =
        enqueueChannelToPlayQueueImpl(chatId, title)
    fun enqueueItemToPlayQueue(item: VideoItem): Boolean = enqueueItemToPlayQueueImpl(item)
    fun enqueueWatchLaterToPlayQueue() = enqueueWatchLaterToPlayQueueImpl()
    fun removeFromPlayQueue(entryId: String) = removeFromPlayQueueImpl(entryId)
    fun movePlayQueueEntry(from: Int, to: Int) = movePlayQueueEntryImpl(from, to)
    fun movePlayQueueEntryToTop(entryId: String) = movePlayQueueEntryToTopImpl(entryId)
    fun togglePlayQueueSkip(entryId: String) = togglePlayQueueSkipImpl(entryId)
    fun setPlayQueueOrder(mode: PlayQueueOrder) = setPlayQueueOrderImpl(mode)
    fun clearPlayQueue() = clearPlayQueueImpl()
    fun openPlayQueue(fromStart: Boolean) = openPlayQueueImpl(fromStart)

    // ---- R391 播放台（多队列）台级 CRUD/开台委托（实现见 FeedPlayQueuePart.kt） ----
    fun createPlayStation(name: String): Boolean = createPlayStationImpl(name)
    fun renamePlayStation(id: String, name: String): Boolean = renamePlayStationImpl(id, name)
    fun deletePlayStation(id: String): Boolean = deletePlayStationImpl(id)
    fun editPlayStation(id: String): Boolean = editPlayStationImpl(id)
    fun openPlayStation(id: String, fromStart: Boolean = false) = openPlayStationImpl(id, fromStart)

    // ---- R391 #7 今日单生成委托（实现见 FeedPlayQueuePart.kt） ----
    fun generateTodayStation() = generateTodayStationImpl()

    // ---- R392 播放编排三期(#5 接棒/#6 定时)委托(实现见 FeedPlayChainPart/FeedPlayQueuePart) ----
    fun setPlayStationChain(stationId: String, chainTo: String?) =
        setPlayStationChainImpl(stationId, chainTo)

    /** 晨间定时设置口(保存钮:开关+HH:mm→落盘+重挂循环) */
    fun setMorningStationAlarm(enabled: Boolean, minuteOfDay: Int) =
        setMorningStationAlarmImpl(enabled, minuteOfDay)

    // ---- R398 播放编排四期(#9 离线联动·整单下载)委托(实现见 FeedStationDownloadPart.kt) ----
    /** 台级「下载整单」：台内点名单条逐条走既有单条下载链（断续幂等）；返回实发条数 */
    fun downloadPlayStation(stationId: String): Int = downloadPlayStationImpl(stationId)

    // ---- R398 播放编排四期(#10 队列语义跳转)委托(实现见 FeedQueueJumpPart.kt) ----
    /** 「跳到讲…」语义跳转：本地 caption 匹配+跳播开单（零网络）；false=无果 */
    fun jumpToQueueSemantic(query: String): Boolean = jumpToQueueSemanticImpl(query)

    /**
     * 段尽单发门(R392 #5):已结算的队列源实例身份(=== 判同)——同源只消费
     * 一次,睡眠停/自然停/静默待命/接棒均算已结算;接棒后新源实例=天然重置
     * (链可续传 A→B→C)。见 FeedPlayChainPart.advancePlayChainAtQueueEnd。
     */
    internal var playChainSettledSource: FeedSource? = null

    /**
     * 晨间循环 Job(R392 #6①):进程内协程闸点循环(DailyDigest 判词,启动
     * 补查+到点自查);设置变更重挂(setMorningStationAlarmImpl)。null 语义=
     * 循环未起(仅构造瞬间)。
     */
    internal var morningAlarmJob: Job? = null

    init {
        // R392 #6① 晨间定时循环装机:错峰闸后启动补查+到点自查(零新后台常驻
        // ——delay 协程不持锁不占线程;已知局限=进程死则当日不触发,下次启动
        // 补查兜底(迟到窗口内),r392b_notes §3.2 判词)
        startMorningAlarmLoop()
    }

    /**
     * 今日台生成空态 nonce（每次原料缺+1；UI 层 LaunchedEffect 消费=Toast 引导
     * ——core/feed 域零 Toast 判词，一次性事件走 nonce 单调递增防快照流去重吞）。
     */
    internal val _todayQueueEmptyHint = MutableStateFlow(0)
    val todayQueueEmptyHint: StateFlow<Int> = _todayQueueEmptyHint.asStateFlow()

    /**
     * 队列纯模型（ListenQueueModel 模型纪律：非线程安全，全部变更走
     * FeedPlayQueuePart 的 impl——UI 动作与 onPage 记账都在主线程）。
     * R391 起模型=「活跃台」的编辑面（台级 CRUD 在 VM 编排域，r391_notes §2.3）。
     */
    internal val playQueueModel = PlayQueueModel()

    /** 队列实况（QueueScreen 订阅：条目列表/档位/断点；每次变更后发布）＝活跃台实况 */
    internal val _playQueue = MutableStateFlow(PlayQueue())
    val playQueue: StateFlow<PlayQueue> = _playQueue.asStateFlow()

    /** 播放台列表实况（QueueStationsScreen 订阅；发布序=updatedAt 降序=「最近动的在前」） */
    internal val _playQueueStations = MutableStateFlow<List<PlayQueue>>(emptyList())
    val playQueueStations: StateFlow<List<PlayQueue>> = _playQueueStations.asStateFlow()

    /** 台列表底账（主线程内存；序=持久化序=追加序，与发布序分立） */
    internal var playStations: List<PlayQueue> = emptyList()

    /** 活跃台 id（null/失联=哨兵空模型；切换与删除的同步纪律见 FeedPlayQueuePart） */
    internal var activePlayStationId: String? = null

    /**
     * 落盘串流（extraBufferCapacity=1 + DROP_OLDEST = conflate 语义：磁盘只关心
     * 最新态，高频断点记账的中间态自然去重；单收集者串行写盘→写序不可能乱）。
     * R391 起负载=全量台列表态（stations+activeId，v2 档整库落盘）。
     */
    internal val _playQueuePersist = MutableSharedFlow<PlayStationsState>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    init {
        // 冷启动载入（play_queue.json v2→台列表恢复+活跃台装载→状态发布；空库也是
        // 合法态（哨兵空模型），ListenQueueController 冷启动恢复同款；读盘在 IO，
        // 模型恢复回主线程。v1 旧档在读侧自动迁移为迁移台（LocalStore 判词））
        viewModelScope.launch(Dispatchers.IO) {
            val restored = runCatching { store.loadPlayStations() }
                .getOrDefault(PlayStationsState(emptyList(), null))
            withContext(Dispatchers.Main) {
                playStations = restored.stations
                // 活跃台=activeId 档；失联回落第一台（诚实降级不硬指）
                val active = restored.stations.firstOrNull { it.id == restored.activeId }
                    ?: restored.stations.firstOrNull()
                activePlayStationId = active?.id
                playQueueModel.replaceAll(active ?: PlayQueue())
                _playQueue.value = playQueueModel.queue
                _playQueueStations.value = playStations.sortedByDescending { it.updatedAt }
            }
        }
        // 落盘串流收集者：单协程串行写盘（写失败静默——下笔落盘覆盖同内容；
        // VM 清场时最末笔在途丢失=断点略旧，可接受——一期挂账沿续）
        viewModelScope.launch(Dispatchers.IO) {
            _playQueuePersist.collect { state ->
                runCatching { store.savePlayStations(state) }
            }
        }
    }

    // ---------------- 本地数据管理（设置页入口） ----------------

    // ---------------- 搜索历史（LocalStore search_history.json 持久化） ----------------

    /** 搜索历史（新词在前，上限 [LocalStore.MAX_SEARCH_HISTORY] 条）；init 预载，顶栏历史 chips 数据源 */
    internal val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    init {
        // 启动时把搜索历史从磁盘预载到内存（IO 线程加载，不阻塞首帧；独立 init 块便于追加维护）
        viewModelScope.launch(Dispatchers.IO) {
            _searchHistory.value = runCatching { store.loadSearchHistory() }.getOrDefault(emptyList())
        }
    }

    // ---------------- 已看内容彻底过滤（"已看内容不再出现"） ----------------
    //
    // 生效面（开关开时，全部为硬剔除而非降权；判定依据 = item.key + fileUniqueId 双轨，
    // 底账 LocalStore.seen_ids.log 容量 100000，不受观看历史 500 条淘汰影响）：
    //  1. 聚合 Feed 页级过滤（本区块 seenKeys/seenUids + pass 谓词，loadMore 内）；
    //  2. WeightedAggregateFeedSource 源内硬剔除（excludeSeenProvider，「全部」tab 与
    //     分屏自持源同接本开关；分屏类型 tab 的网关直拉路径在 GridScreen.gridLoadMore
    //     内另有同口径过滤）；
    //  3. 历史回溯注入（sampleHistoryBackfill 的 seenIds 参数，对含单频道源在内的
    //     所有可注入源生效——回溯专挖老内容，是"很久以前看过又出现"的重灾区）；
    //  4. 追更置顶扫描（scanFreshSubscriptionItems 双轨剔除）。
    // 刻意不生效的面（用户显式语境，"我就是来找/来翻的"）：单频道源正页、搜索源
    //（含分屏搜索模式）、关键词订阅源、合集、订阅追更源、收藏/稍后/历史本地源、
    // 盲盒（切频道源中段开刷）、记忆播放恢复（tryRestorePosition 把上次那条插回首位
    // 是功能本体）。

    /** 已看过滤开关（默认 true，存 AppPrefs）；开启时聚合 Feed 直接剔除已看条目 */
    internal val _excludeWatched = MutableStateFlow(true)
    val excludeWatched: StateFlow<Boolean> = _excludeWatched.asStateFlow()

    init {
        // 启动时从 AppPrefs 恢复已看过滤开关（IO 线程加载，不阻塞首帧；独立 init 块便于追加维护）
        viewModelScope.launch(Dispatchers.IO) {
            _excludeWatched.value = runCatching { prefs.excludeWatched }.getOrDefault(true)
        }
    }

    // ---------------- 本地深度搜索索引 ----------------

    /**
     * 爬取泳道 flood 预算（R46 §5 落地：收割器同款 FloodBudget 独立车道，滑窗 1 小时）：
     * 清扫页/锚点按 1 次实记，旧路逻辑页按估值记账；额度上限每枚举拍从
     * [AppPrefs.floodCrawlPerHour] 刷新（设置改动免重启生效）。AIMD 退化为
     * 撞 429 后的反应层，不再承担唯一限速。
     * 声明顺序注意：必须在 [deepIndex] 之前（构造参数依赖）。
     */
    internal val crawlBudget = FloodBudget(3_600_000L, prefs.floodCrawlPerHour)

    /**
     * 本地全量索引（filesDir/deepindex/search.db，SQLite FTS5 磁盘索引，内存零常驻），
     * 解决 TG 服务端搜索对老内容索引很浅的问题；索引结构/迁移/续爬见 [DeepSearchIndex] 注释。
     */
    internal val deepIndex = DeepSearchIndex(File(app.filesDir, "deepindex"), crawlBudget)

    /** 旧 JSON→SQLite 一次性迁移进度（已处理, 总数），null=未在迁移；导入期间搜索可用（库里有多少搜多少） */
    val migrationProgress: StateFlow<Pair<Int, Int>?> = deepIndex.migrationProgress

    /** 旧"倒排表建表进度"口（机制已删除；现映射为迁移进度——迁移期间召回可能不全，搜索页提示复用） */
    val invertedBuildProgress: StateFlow<Pair<Int, Int>?> = deepIndex.invertedBuildProgress

    /** R287 多设备索引合并进度（人话行，null=无在跑合并）；设置页合并导入完成提示的观察口 */
    val importMergeProgress: StateFlow<String?> = deepIndex.importMergeProgress

    /**
     * R287：合并导入点火（设置页在 BackupManager.importBackupMergeIndex 解包就绪后调用）。
     * 返回 true=有挂起合并已接单（后台批迁开跑/排队等指纹索引）；false=无挂起合并。
     * 中断自愈：进程死亡后下次 openDb 自动续跑（断点 import_merge_v1.json 每批原子落盘）。
     */
    fun requestImportMerge(): Boolean = deepIndex.requestImportMerge()

    // ---- R292 封面 OCR 试点（T1a）点火（实现见 FeedOcrPart.kt） ----
    // 初始化顺序守卫：本 init 协程触达 deepIndex（L2227）/gateway（构造参）/prefs（L401）/
    // _charging（L660）——全部声明于本块之前，合规。
    init {
        startCoverOcrImpl()
    }

    // ---- 那年今日公共 API 委托（R203；实现见 FeedOnThisDayPart.kt） ----
    /** R202 时间索引就绪流（那年今日页「构建中→就绪自动补刷」） */
    val onThisDayIndexReady: StateFlow<Boolean> get() = onThisDayIndexReadyImpl

    /** 那年今日窗查询（订阅频道横截面；R202 timeWindowItems 共享原语） */
    suspend fun onThisDayQuery(
        window: dev.tgfeed.core.onthisday.EpochRange,
        limit: Int,
    ): dev.tgfeed.core.onthisday.OnThisDayOutcome<List<VideoItem>> = onThisDayQueryImpl(window, limit)

    /** 那年今日年轮统计（pills 徽标/推送择年原料） */
    suspend fun onThisDayYearStats(
        today: java.time.LocalDate,
        zone: java.time.ZoneId,
    ): dev.tgfeed.core.onthisday.OnThisDayOutcome<List<dev.tgfeed.core.onthisday.OnThisDayYearStat>> =
        onThisDayYearStatsImpl(today, zone)

    // ---------------- 事件研究器（R205 WK-3；实现见 FeedEventResearchPart.kt） ----------------
    // 纯状态字段：无 init 块点火、无构造期协程间接触达，声明位置不参与初始化顺序守卫。

    /** 研究器 UI 状态（Idle/Loading/Ready/EnginePending/Failed——源三态诚实直通） */
    internal val _eventResearchState = MutableStateFlow<EventResearchUiState>(EventResearchUiState.Idle)
    val eventResearch: StateFlow<EventResearchUiState> = _eventResearchState.asStateFlow()

    /** 上一次研究查询（频道二次筛选 refine 的基准；null = 未跑过） */
    internal var eventResearchLastQuery: EventResearchQuery? = null

    /** 上一次召回的窗内条目（refine 原料——二次筛选不重新召回） */
    @Volatile
    internal var eventResearchRawItems: List<dev.tgfeed.core.td.VideoItem> = emptyList()

    // ---- 事件研究器公共 API 委托（实现见 FeedEventResearchPart.kt） ----
    fun runEventResearch(query: EventResearchQuery) = runEventResearchImpl(query)
    fun refineEventResearchChannels(chatIds: Set<Long>) = refineEventResearchChannelsImpl(chatIds)
    fun clearEventResearch() = clearEventResearchImpl()

    // ---------------- R222 hashtag 时间轴（实现见 FeedHashtagTimelinePart.kt） ----------------
    // 与上方 eventResearch 状态刻意分离：两入口（发现页研究器 / caption #标签双选弹层）
    // 各自的召回缓存互不洗掉（同 VM 长生命周期，共享状态会串台）。纯状态字段：
    // 无 init 块点火，声明位置不参与初始化顺序守卫。

    /** hashtag 时间轴 UI 状态（复用 EventResearchUiState 三态语义——Ready 里才是可渲染结果） */
    internal val _hashtagTimelineState = MutableStateFlow<EventResearchUiState>(EventResearchUiState.Idle)
    val hashtagTimeline: StateFlow<EventResearchUiState> = _hashtagTimelineState.asStateFlow()

    /** 当前时间轴的 tag（无 # 前缀；同 tag 重进=缓存命中不重扫） */
    internal var hashtagTimelineTag: String? = null

    // ---- hashtag 时间轴公共 API 委托（实现见 FeedHashtagTimelinePart.kt） ----
    fun runHashtagTimeline(tag: String) = runHashtagTimelineImpl(tag)
    fun clearHashtagTimeline() = clearHashtagTimelineImpl()

    // ---------------- 观看报告（设置页"观看报告"页数据源） ----------------

    /**
     * 最近一次聚合出的观看报告；null = 尚未计算/计算中（UI 显示加载态）。
     * 进入报告页时调用 [refreshWatchReport] 触发重算；不自动刷新（报告页是低频入口，
     * 每次进入现算即可，watch_events 走 LocalStore 内存缓存，成本是一次内存遍历）。
     */
    internal val _watchReport = MutableStateFlow<WatchReport?>(null)
    val watchReport: StateFlow<WatchReport?> = _watchReport.asStateFlow()

    // ---- 观看报告公共 API 委托（实现见 FeedReportPart.kt） ----
    fun refreshWatchReport() = refreshWatchReportImpl()
    fun buildWatchReport(): WatchReport = buildWatchReportImpl()

    // ---------------- 频道体检（R132 我的页"频道体检"数据源，实现见 FeedChannelHealthPart.kt） ----------------

    /**
     * 最近一次聚合出的频道体检报告；null = 尚未计算/计算中（UI 显示加载态）。
     * 进入体检页时调用 [refreshChannelHealth] 触发重算；不自动刷新（低频入口，
     * 判据全部本地现查：索引统计 + watch_events + 广告黑名单——R318 起索引统计走
     * date 索引覆盖分片走查（见 IndexStatsWalk.kt），5.8M 行真库分钟级→秒级，
     * 走查进度见 [channelHealthProgress]。
     */
    internal val _channelHealth = MutableStateFlow<ChannelHealthReport?>(null)
    val channelHealth: StateFlow<ChannelHealthReport?> = _channelHealth.asStateFlow()

    /**
     * 体检重算是否在途（重算期间旧报告继续可见，本位让 UI 明示"正在重新体检"）。
     * 全默认值 = 空报告/不在途。
     */
    internal val _channelHealthRefreshing = MutableStateFlow(false)
    val channelHealthRefreshing: StateFlow<Boolean> = _channelHealthRefreshing.asStateFlow()

    /**
     * R318 体检走查进度（0..100 整数百分比；null=闲/已完成/已取消）。首跑加载态与
     * 重算在途条共用一个口径（分片走查逐片回报，片粒度=进度/取消响应粒度）。
     */
    internal val _channelHealthProgress = MutableStateFlow<Int?>(null)
    val channelHealthProgress: StateFlow<Int?> = _channelHealthProgress.asStateFlow()

    /** R318 体检重算协程句柄（取消口用；refresh 入口先作废旧轮防叠算） */
    internal var channelHealthJob: Job? = null

    // ---- 频道体检/批量订阅增删公共 API 委托（实现见 FeedChannelHealthPart.kt） ----
    fun refreshChannelHealth() = refreshChannelHealthImpl()
    // 同步全量口（默认参无取消哨兵，?: 兜底为死代码保险——签名不变，公开 API 只加不改）
    fun buildChannelHealth(): ChannelHealthReport = buildChannelHealthImpl() ?: ChannelHealthReport()
    /** R318：取消在途体检走查（取消保旧报告直通，不触发兜底重跑） */
    fun cancelChannelHealth() = cancelChannelHealthImpl()
    /** 批量取关（体检页断舍离；单次读改写防循环调用竞态，链路与 removeSubscription 同源） */
    fun removeSubscriptionsBatch(chatIds: Set<Long>) = removeSubscriptionsBatchImpl(chatIds)
    /** 批量重订（体检页取关后的撤销；链路与 addSubscription 同源） */
    fun addSubscriptionsBatch(briefs: List<ChannelBrief>) = addSubscriptionsBatchImpl(briefs)

    // ---------------- 智能主题房间（SmartRooms：观看共现聚类，算法详见 SmartRooms.kt） ----------------

    /**
     * 房间聚类引擎。标题表现拼：channels 为主、订阅标题兜底
     *（channels 需 UI 调过 loadChannels 才全；订阅里存过用户追更频道的标题）。
     * 被屏蔽频道经 blockedProvider 排除，不进房间。引擎不持有 Flow，每次 build 惰性取值。
     */
    internal val smartRoomsEngine = SmartRooms(
        store = store,
        titleProvider = {
            val map = HashMap<Long, String>()
            _subscriptions.value.forEach { map[it.id] = it.title }
            _channels.value.forEach { map[it.id] = it.title } // channels 覆盖订阅（更新更全）
            map
        },
        blockedProvider = { _blockedChannels.value },
        // 与混排/拉取配额/发现页种子同一份口味画像（互动加分 + 订阅加成；R75-D 起互动为时间衰减后有效值）
        boostProvider = { feedBoostWithSubscription() },
    )

    /** 智能房间列表（聚类结果缓存内存，重启不持久化；UI 打开合集弹窗时调 [refreshSmartRooms] 重算） */
    internal val _smartRooms = MutableStateFlow<List<ChannelCollection>>(emptyList())
    val smartRooms: StateFlow<List<ChannelCollection>> = _smartRooms.asStateFlow()

    init {
        // 启动后在 IO 线程算一次房间（读走 store 内存缓存、计算量微秒级，不阻塞首帧；
        // 独立 init 块便于追加维护，与合集/搜索历史同款）。
        // 注意：此刻 channels 多半还没加载，房间名可能先回退成"频道 <id>"，
        // 等 UI 打开合集弹窗触发 refreshSmartRooms() 后即换成真标题。
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { _smartRooms.value = smartRoomsEngine.buildSmartRooms() }
        }
    }

    // ---- 智能房间/盲盒/关键词订阅公共 API 委托（实现见 FeedRoomsPart.kt） ----
    fun refreshSmartRooms() = refreshSmartRoomsImpl()
    fun showSmartRoom(room: ChannelCollection) = showSmartRoomImpl(room)
    fun openBlindBox() = openBlindBoxImpl()
    fun showKeywordFeed() = showKeywordFeedImpl()

    /** 追更通知开关（默认开，存 AppPrefs）；设置页开关的数据源 */
    internal val _notifyNewContent = MutableStateFlow(true)
    val notifyNewContent: StateFlow<Boolean> = _notifyNewContent.asStateFlow()

    /**
     * H2：App 是否在前台（追更轮询退后台即暂停的置位点）。
     * 项目不加 ProcessLifecycleOwner 依赖，由 MainActivity 的 onStart/onStop 直接置位
     * （主代理负责接线）。
     * R195 信号链收敛：本属性改为**直通进程级 [dev.tgfeed.core.net.AppForeground]**
     * 的读写代理——MainActivity 既有写入语句原样生效（写穿透到总线，本 VM 与网关
     * 侧激进整载器 B 档前台闸共享同一真值）；本 VM 各消费面（追更轮询/
     * IndexCrawlGate/WiFi 预载）读口径零改动。默认 true：未接线时退化为旧行为
     * （后台照跑），不会静默丢功能。
     */
    var appInForeground: Boolean
        get() = dev.tgfeed.core.net.AppForeground.inForeground
        set(v) {
            dev.tgfeed.core.net.AppForeground.inForeground = v
        }

    init {
        // 启动时从 AppPrefs 恢复追更通知开关（IO 线程加载，不阻塞首帧；独立 init 块便于追加维护）
        viewModelScope.launch(Dispatchers.IO) {
            _notifyNewContent.value = runCatching { prefs.notifyNewContent }.getOrDefault(true)
        }
    }

    /** 最近一次自动推送发起时刻（@Volatile：saveProgress 多在主线程被调，推送协程在 IO） */
    @Volatile
    internal var lastAutoPushAtMs = 0L

    /** 注销"跨设备续播开关"SP 监听的函数（下方 init 注册；onCleared 注销） */
    internal var unregisterSyncToggleListener: (() -> Unit)? = null

    /** R300：注销"多设备状态同步开关"SP 监听的函数（下方 init 注册；onCleared 注销） */
    internal var unregisterCloudSyncToggleListener: (() -> Unit)? = null

    init {
        // 设置页开关直写 SharedPreferences、不经 VM——注册 SP 变更监听，
        // 开关由关转开时立即自动拉一次云端进度（接力即开即用，不必等下次启动）
        unregisterSyncToggleListener = runCatching {
            prefs.registerProgressSyncEnabledListener { enabled ->
                if (enabled) {
                    viewModelScope.launch(Dispatchers.IO) { pullProgressIntoMemory() }
                }
            }
        }.getOrNull()
        // R300 多设备状态同步：启动拉四域 + 点火事件驱动/周期拉保底 + 开关开即时拉
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { multiSync?.start() }
            if (prefs.cloudSyncEnabled) pullStateIntoMemory()
        }
        unregisterCloudSyncToggleListener = runCatching {
            prefs.registerCloudSyncEnabledListener { enabled ->
                if (enabled) {
                    viewModelScope.launch(Dispatchers.IO) { pullStateIntoMemory() }
                }
            }
        }.getOrNull()
    }

    // ---- 发现页/PK 投票公共 API 委托（实现见 FeedDiscoveryPart.kt） ----
    fun recordPkVote(winnerChatId: Long, loserChatId: Long) = recordPkVoteImpl(winnerChatId, loserChatId)
    fun refreshDiscovery(excludeIds: Set<Long> = emptySet()) = refreshDiscoveryImpl(excludeIds)
    fun tasteDiscoveryCandidate(chatId: Long) = tasteDiscoveryCandidateImpl(chatId)

    /**
     * R377 发现页懒加载门公共口：发现页落定（LaunchedEffect(pageActive)）的唯一
     * 授权源（只加不改——refreshDiscovery 公共口语义零动，换一批/骰子/双击三调用
     * 点恒许不受门）。
     *  - 首开/距上次授权 ≥[DiscoveryLazyGate.WINDOW_MS]（30min）：开门行+授权一轮
     *    =kickOnce 预算内收割 + refreshDiscoveryImpl 网络臂开（USER_ACTION 既有口径）；
     *  - 窗内再开：网络臂关零网络——本地聚合照跑（「窗内复用缓存」页面语义）。
     * Mock 网关（预览/JVM 测试）无收割器：照旧整臂刷新（无门无暗道可收）。
     * 探针口径见 r377_notes §2.5：臂行由本口记录（授权轮开臂/窗内再开关臂）。
     */
    fun onDiscoverPageSettled() {
        val real = gateway as? TdLibGateway
        if (real == null) {
            refreshDiscovery()
            return
        }
        val granted = real.groupHarvester.discoveryGate.onUserOpen()
        if (granted) {
            android.util.Log.i("R377Gate", "网络臂开（授权轮）")
            real.groupHarvester.kickOnce()
            refreshDiscovery()
        } else {
            android.util.Log.i("R377Gate", "窗内再开，复用缓存（网络臂关）")
            refreshDiscoveryImpl(allowNetworkArm = false)
        }
    }
    fun acceptDiscoveryCandidate(c: ChannelBrief) = acceptDiscoveryCandidateImpl(c)
    fun dismissDiscoveryCandidate(chatId: Long) = dismissDiscoveryCandidateImpl(chatId)
    fun loadDiscoveryPreview(chatId: Long, priority: Boolean = false) = loadDiscoveryPreviewImpl(chatId, priority)

    // ---- R133 基因鉴定/反向探索公共 API 委托（实现见 FeedGenomePart.kt） ----
    fun refreshReverseExploreCard() = refreshReverseExploreCardImpl()
    suspend fun channelGenomeLine(chatId: Long): String? = channelGenomeLineImpl(chatId)

    /** 发现页候选列表：DiscoveryRanker 输出序（P1-c：利用桶 → 探索域按臂 θ 采样降序），已过滤已订阅/已屏蔽/软拒绝 */
    internal val _discoveryCandidates = MutableStateFlow<List<ChannelBrief>>(emptyList())
    val discoveryCandidates: StateFlow<List<ChannelBrief>> = _discoveryCandidates.asStateFlow()

    /**
     * 本轮真实打分池大小（v4"换一批"收口依据）：合并过滤后、排除集剔除与出榜截断
     * （[DISCOVERY_CANDIDATE_LIMIT]）前定格——UI 判断"池子是否比当前展示的多"只能靠它：
     * 发布数恒 ≤ 50，看不出池深；旧版按"发布数触顶 50"猜，小池子刷新等于原地洗牌
     */
    internal val _discoveryPoolSize = MutableStateFlow(0)
    val discoveryPoolSize: StateFlow<Int> = _discoveryPoolSize.asStateFlow()

    /** 候选来源角标：chatId → "图谱" / "相似" / "图谱+相似"（与 [_discoveryCandidates] 同批刷新） */
    internal val _discoverySources = MutableStateFlow<Map<Long, String>>(emptyMap())
    val discoverySources: StateFlow<Map<Long, String>> = _discoverySources.asStateFlow()

    /** 候选证据文案：chatId → 证据行（「你的 N 个订阅频道引用过它」等，与候选同批刷新） */
    internal val _discoveryEvidence = MutableStateFlow<Map<Long, List<String>>>(emptyMap())
    val discoveryEvidence: StateFlow<Map<Long, List<String>>> = _discoveryEvidence.asStateFlow()

    /** 候选刷新中标记（发现页转圈；兼作防重入闸——刷新中重复触发直接忽略） */
    internal val _discoveryLoading = MutableStateFlow(false)
    val discoveryLoading: StateFlow<Boolean> = _discoveryLoading.asStateFlow()

    /**
     * 候选标题进程内缓存：chatId → 真标题。
     * 互链嗅探（t.me/c/ 直链）产出的候选只有 chatId 没有标题（裸 ID 卡"频道 -100..."太丑），
     * refreshDiscovery 对占位标题候选做一次懒解析（见下），命中这里就不再发请求；
     * 解析成功同时回写图谱 recordNodeTitle 落盘（跨重启自愈，本缓存只是免 IO 的快路径）。
     */
    internal val discoveryTitleCache = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /** 本轮出榜候选的来源臂（chatId → 臂名）：refreshDiscovery/runTasteRadar 发布时填充，
     *  收/弃时向 discovery_arms.json 记账（P1-a #3 建账，P1-c Thompson 采样已消费此底账） */
    internal val discoveryArmById = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /** 候选成员数进程内缓存（P1-b #8 懒加载产物）：chatId → memberCount；图谱节点落盘是
     *  跨重启的持久层，本缓存是同会话内相似/雷达来源候选（无图边）的免 IO 快路径 */
    internal val discoveryMemberCounts = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    /**
     * P1-d 周榜切片数据源：chatId → 近 7 天**发布条数**（R183 语义换血：深索引 items
     * 时间戳第一手——沉底「本周口碑」榜；旧口径=图谱 7 天新增边计数属二手共现噪音，
     * 已废）。与 [_discoveryCandidates] 同批刷新，缺席 = 无近期更新按 0 处理）
     */
    internal val _discoveryWeeklyEdgeCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val discoveryWeeklyEdgeCounts: StateFlow<Map<Long, Int>> = _discoveryWeeklyEdgeCounts.asStateFlow()

    /** P4-A 热词榜：群友近 24h 搜索热词（词, 次数）按次数降序；与候选同批刷新，空 = 收割暂无数据 */
    internal val _discoveryHotWords = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    val discoveryHotWords: StateFlow<List<Pair<String, Int>>> = _discoveryHotWords.asStateFlow()

    /**
     * R372 「群友正在搜」雷达频道卡：热词头部词→答案库返回频道的只读卡（词→频道
     * 配对的「返回了 YYY 频道」侧；聚合见 FeedDiscoveryRadarPart.buildRadarCards，
     * 纯本地零网络）。与热词行同批刷新，空 = 无过闸卡整行隐藏（诚实空态）。
     */
    internal val _discoveryRadarCards = MutableStateFlow<List<RadarChannelCard>>(emptyList())
    val discoveryRadarCards: StateFlow<List<RadarChannelCard>> = _discoveryRadarCards.asStateFlow()

    /** P4-A 新片雷达：近窗口"图谱首见"频道 id 集（候选卡"刚冒出来的"角标数据源；与候选同批刷新） */
    internal val _discoveryFreshIds = MutableStateFlow<Set<Long>>(emptySet())
    val discoveryFreshIds: StateFlow<Set<Long>> = _discoveryFreshIds.asStateFlow()

    /** 发现页升级·候选成员数视图：chatId → 成员数（出榜时从打分输入 + 会话懒加载缓存发布一次；
     *  [enrichDiscoveryProfiles] 补抓到位后增量合并；缺 key = 成员数未知，UI 不渲染该段） */
    internal val _discoveryMemberCountView = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val discoveryMemberCountView: StateFlow<Map<Long, Int>> = _discoveryMemberCountView.asStateFlow()

    /** 发现页升级·群搜来源词：chatId → 用户搜过的代表词（答案库聚合 answerAgg 的首词——
     *  搜索历史倒序遍历的首个命中词（R211 词源换血：答案库关键词按最新命中聚合 +
     *  直荐注册表覆盖——群友搜过的词直出，用户自己的搜索历史不再当词源）；
     *  卡片推荐语「群友搜『XX』找到」/「你的搜索群里有人搜过『XX』」的数据源；
     *  与候选同批刷新，非答案库来源的候选缺席） */
    internal val _discoverySourceWords = MutableStateFlow<Map<Long, String>>(emptyMap())
    val discoverySourceWords: StateFlow<Map<Long, String>> = _discoverySourceWords.asStateFlow()

    /** 发现页升级·"刚冒出来的"横滑区：7 天内图谱首见、chatId 与标题均已解析的频道小卡
     *  （按最近再被搜出降序；与候选同批刷新，空 = 无新发现整区隐藏） */
    internal val _discoveryFreshCards = MutableStateFlow<List<DiscoveryFreshCard>>(emptyList())
    val discoveryFreshCards: StateFlow<List<DiscoveryFreshCard>> = _discoveryFreshCards.asStateFlow()

    /**
     * R133 反向探索卡（#17 每周一张"信息茧房打破器"）：与口味画像负相关的频道 +
     * 广场推荐人数 + 基因鉴定行。null = 无卡（画像太薄/池空/Mock）整区隐藏；
     * 选卡与周轮换逻辑见 FeedGenomePart（落盘 store/reverse_explore.json）
     */
    internal val _discoveryReverseCard = MutableStateFlow<ReverseExploreCard?>(null)
    val discoveryReverseCard: StateFlow<ReverseExploreCard?> = _discoveryReverseCard.asStateFlow()

    /**
     * R133 候选卡样本基因行（#19）：chatId → "近期主打X · 平均 N 分钟"。
     * 候选频道不在深度索引里（发现页只推未加入频道），基因行用预览页样本现算
     * （loadDiscoveryPreview 顺带聚合，零额外请求）；索引口径的基因行在频道主页
     * （channelGenomeLine）与反向探索卡。缺 key = 预览未回/样本不足，UI 不渲染
     */
    internal val _discoveryGenomeLines = MutableStateFlow<Map<Long, String>>(emptyMap())
    val discoveryGenomeLines: StateFlow<Map<Long, String>> = _discoveryGenomeLines.asStateFlow()

    /** 发现页升级·顶部统计胶囊：今日收割词事件数 / 今日首见新频道数 / 广告黑名单累计拦截数；
     *  null = 三项全 0 或无数据（UI 整行隐藏，绝不显示 0） */
    internal val _discoveryHarvestStats = MutableStateFlow<DiscoveryHarvestStats?>(null)
    val discoveryHarvestStats: StateFlow<DiscoveryHarvestStats?> = _discoveryHarvestStats.asStateFlow()

    // ---------------- R183 双宝藏发现页（深索引金矿 + 用户搜索群，状态与快照） ----------------

    /**
     * R183 候选主推荐语（客观事实口径——用户终版设计「不写口味，写事实」）：
     * chatId → 人话推荐语一行（候选卡推荐语录条位：「深索引验货（丰度 92 分）· 你的
     * 搜索群在传」/「近 7 天更新 N 条」/「冷门但存货扎实」…）。与候选同批刷新。
     */
    internal val _discoveryRecommendLines = MutableStateFlow<Map<Long, String>>(emptyMap())
    val discoveryRecommendLines: StateFlow<Map<Long, String>> = _discoveryRecommendLines.asStateFlow()

    /**
     * R183 佐证徽章（终版修正：广场/群搜/上升计数降级为佐证胶囊，不进主排序）：
     * chatId → 徽章文案列表（「N 群在搜」「本周 N 条」「系统验货 T1」「全站 N 人推荐」
     * 「冷门宝藏」，≤3 枚）。与候选同批刷新。
     */
    internal val _discoveryCorroborations = MutableStateFlow<Map<Long, List<String>>>(emptyMap())
    val discoveryCorroborations: StateFlow<Map<Long, List<String>>> = _discoveryCorroborations.asStateFlow()

    /**
     * 金矿快照（深索引频道档案，R183 宝藏一）：@Volatile——快照读盘/后台单飞重建后
     * 发布；TTL 内陈值先回（见 FeedDiscoveryGoldPart.ensureDiscoveryGoldSnapshot）。
     * 纯档案不含订阅/屏蔽过滤（那些是刷新时的动态口径）。
     */
    @Volatile
    internal var discoveryGoldSnapshot: DiscoveryGoldmine.Snapshot? = null

    /** 快照重建单飞闸（604 频道 head 采样是重查询——并发刷新只建一次） */
    internal val discoveryGoldBuilding = java.util.concurrent.atomic.AtomicBoolean(false)

    /** R62 收录规模播报：今日新收录频道数（24h 首见）/ 图谱累计节点计数（有入边的目标节点总数）；
     *  null = 暂无数据（UI 整行隐藏；Mock 网关恒 null） */
    internal val _discoveryIntakeStats = MutableStateFlow<DiscoveryIntakeStats?>(null)
    val discoveryIntakeStats: StateFlow<DiscoveryIntakeStats?> = _discoveryIntakeStats.asStateFlow()

    // ---------------- R204 时间维度轻快+工具族（TL-5/TL-7/WK-2/DS-3/DS-5 状态与缓存） ----------------

    /**
     * R204 WK-2 宇宙作息表：全网已索引频道的小时×星期发布密度（R170 后台单飞+磁盘
     * 快照，读快照毫秒级；重建期陈值照用）。hasData()=false = 索引未建（诚实空态）。
     */
    internal val _universeRhythm = MutableStateFlow<UniverseRhythm.Rhythm?>(null)
    val universeRhythm: StateFlow<UniverseRhythm.Rhythm?> = _universeRhythm.asStateFlow()

    /** 作息表 rollup 单飞闸（全表 GROUP BY 4-10s，并发触发只建一次） */
    internal val universeRhythmBuilding = java.util.concurrent.atomic.AtomicBoolean(false)

    /** R204 DS-5 首发溯源：查证串行锁 + 会话内存缓存（指纹 → 判定；磁盘账本跨会话） */
    internal val firstSeenLock = Any()
    internal val firstSeenMemory = java.util.concurrent.ConcurrentHashMap<String, FirstSeenOutcome>()

    // ---- R204 公共 API 委托（实现见对应 Part 文件；internal=同模块 UI 可直调，
    //      返回类型 ChronicleResult/FirstSeenOutcome 是 internal 纯数据件） ----
    /** WK-2：进发现页触发（快照读毫秒级；过期后台单飞重建） */
    internal fun refreshUniverseRhythm() = refreshUniverseRhythmImpl()
    /** TL-5：频道编年史取数（月历直方图+基因下界 → 年块时间轴；会话缓存 6h） */
    internal suspend fun channelChronicle(chatId: Long, monthsCap: Int): ChronicleResult? =
        channelChronicleImpl(chatId, monthsCap)
    /** TL-7：单视频本机回放热力（60 桶密度；样本不足 null） */
    internal fun seekHeatForKey(key: String): FloatArray? = seekHeatForKeyImpl(key)
    /** TL-7：频道级「精彩段落」洞察（样本不足 null） */
    internal fun channelSeekInsight(chatId: Long): SeekHeatPolicy.ChannelInsight? = channelSeekInsightImpl(chatId)
    /** DS-5：首发溯源（用户点按触发；三级缓存；forceRescan=重新查证） */
    internal suspend fun firstSeenFor(item: VideoItem, forceRescan: Boolean = false): FirstSeenOutcome =
        firstSeenOutcomeFor(item, forceRescan)
    /** TL-3：掷一次时间盲盒（随机时刻+随机频道；R202 索引采样，未就绪/掷尽返回 null） */
    internal suspend fun rollTimeBlindbox(): TimeBlindboxRoll? = rollTimeBlindboxImpl()


    /** 读本轮候选的来源臂（P1-d 收下接力"同臂"判定用；不在本轮榜/未归因返回 null） */
    fun discoveryArmOf(chatId: Long): String? = discoveryArmById[chatId]

    /** GetSupergroupFullInfo 懒加载防重入集合：每候选每会话至多取一次（预算闸见下） */
    internal val discoveryFullInfoRequested = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    // ---------------- 发现页名片卡预览（最近 N 条真缩略图） ----------------

    /**
     * 候选预览：chatId → [DiscoveryPreview]（最近条目总数 + 逐条真缩略图字节）。
     * 进程内缓存，每候选每 VM 生命周期至多拉一页（行级懒加载：卡片入组合时才触发）；
     * thumbs 元素为 null = 该条无封面文件或下载失败（UI 回落渐变色块）。
     * v3 修复（用户实测"缩略图加载不出来/糊成一团"）：弃用 minithumbnail（几十字节
     * 超低清占位，放大全铺必糊），改走 [TdLibGateway.loadCoverBytes] 真缩略图下载通道——
     * 与 Feed 网格/全屏播放封面同一条链（视频 Video.thumbnail.file 独立文件，
     * 网关进程内按 fileId 缓存）。
     */
    internal val _discoveryPreviews = MutableStateFlow<Map<Long, DiscoveryPreview>>(emptyMap())
    val discoveryPreviews: StateFlow<Map<Long, DiscoveryPreview>> = _discoveryPreviews.asStateFlow()

    /** 预览拉取防重入集合（含"已拉过"的候选：结果空表也在 _discoveryPreviews 里留痕） */
    internal val discoveryPreviewRequested = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** 预览真图下载并发闸（全 VM 共享，同时 ≤[DISCOVERY_PREVIEW_DOWNLOAD_CONCURRENCY] 路）：
     *  候选卡集体入屏时防下载洪峰抢播放/Feed 带宽 */
    internal val discoveryPreviewDownloadSem = Semaphore(DISCOVERY_PREVIEW_DOWNLOAD_CONCURRENCY)

    /**
     * 普通档预览下载二级闸（v4 首屏优先：≤ 总闸 − [DISCOVERY_PREVIEW_PRIORITY_LANES] 路）。
     * 两档纪律：优先卡（可见前 3 张/盲盒抽中）不过本闸、直接占全局闸——恒有一条预留
     * 车道可插；普通卡先占本闸再占全局闸。总并发恒 ≤ 全局闸，限流总量不变，
     * 只是排队分两档，让首屏先出图
     */
    internal val discoveryPreviewDownloadLowSem =
        Semaphore(DISCOVERY_PREVIEW_DOWNLOAD_CONCURRENCY - DISCOVERY_PREVIEW_PRIORITY_LANES)

    // ---- 全量搜索/秒问/答案区公共 API 委托（实现见 FeedSearchPart.kt） ----
    suspend fun resolveAnswerChatId(link: String, username: String): Long? = resolveAnswerChatIdImpl(link, username)
    fun liveAskSearch(query: String) = liveAskSearchImpl(query)
    fun setSearchMediaTab(t: Int) = setSearchMediaTabImpl(t)
    fun setSearchFilter(f: FeedFilter) = setSearchFilterImpl(f)
    fun fullSearch(query: String) = fullSearchImpl(query)
    fun cancelFullSearch() = cancelFullSearchImpl()
    fun fullSearchMore() = fullSearchMoreImpl()
    /** R371 排序·频道热度档数据源（纯本地零网络；IO 线程内部消化，UI 可直接协程调用） */
    suspend fun channelMemberCountsForSort(chatIds: Set<Long>): Map<Long, Int> = channelMemberCountsForSortImpl(chatIds)

    internal val searchSessionCache = LinkedHashMap<String, CachedSearchSession>()
    internal val searchSessionCacheLock = Any()

    /**
     * 把一次会话的聚合结果写入缓存（搜索完成/被停止时调用，finally 里触发）。
     * 空结果不缓存（与机器人缓存同纪律：空多半是网络差/全熔断的临时态，
     * 缓存会挡住用户立即重试；真实 0 命中的词 10 分钟后也能再搜，代价可接受）。
     */
    internal val _fullSearchResults = MutableStateFlow<List<VideoItem>>(emptyList())
    val fullSearchResults: StateFlow<List<VideoItem>> = _fullSearchResults.asStateFlow()

    /**
     * 当前全量搜索结果所属的关键词（R107/H5 观测口）：结果与搜索会话同生共死
     * （fullSearch 入口清结果并整体换会话），故 searchSession.query 即结果关联词；
     * 从未搜索（无会话）时为 null。搜索页据此判定"VM 残留结果是否属于进页词"——
     * 只读观测，不改任何搜索状态（修复：旧口径只看"VM 有无结果"，搜 A 退出后
     * 带词 B 进页会把 A 的残留误判成"已搜过 B"，显示旧结果且不发新搜索）。
     */
    val fullSearchQuery: String?
        get() = searchSession?.query

    /** 覆盖度：已搜频道数 to 应搜频道总数（搜索页底部常驻覆盖度条数据源） */
    internal val _fullSearchCoverage = MutableStateFlow(0 to 0)
    val fullSearchCoverage: StateFlow<Pair<Int, Int>> = _fullSearchCoverage.asStateFlow()

    /**
     * 金矿 P3·资源群答案（答案库缓存层）：当前搜索词 → 群友历史搜索沉淀的答案链接列表。
     * fullSearch 入口刷新（与搜索会话同生共死）；Pair.first 是所属关键词——
     * UI 只对"当前已提交词"展示，防连续打字时旧词答案在新词加载态里闪现。
     * null = 从未搜索/ Mock 网关；second 空表 = 答案库未命中（既有搜索路径不受影响）。
     */
    internal val _groupAnswers = MutableStateFlow<Pair<String, List<GroupAnswer>>?>(null)
    val groupAnswers: StateFlow<Pair<String, List<GroupAnswer>>?> = _groupAnswers.asStateFlow()

    // ---------------- 搜索联动·资源群秒回（显式提交时并行问已发现搜索实体） ----------------

    /**
     * "资源群秒回"区状态流（[liveAskSearch] 一轮一换；实时流入）。
     * UI 只对"当前已提交词"展示（LiveAskState.query == 当前词，与 groupAnswers 同口径）；
     * null = 从未发起/ Mock 网关。
     */
    internal val _liveAsk = MutableStateFlow<LiveAskState?>(null)
    val liveAsk: StateFlow<LiveAskState?> = _liveAsk.asStateFlow()

    /** 本轮秒回协程；新一轮发起时取消（旧轮已回灌 AnswerStore 的数据保留，监听不再继续） */
    internal var liveAskJob: Job? = null

    /** 秒回轮次代次：防旧轮收尾 publish 覆盖新轮状态（取消是异步的，收尾 finally 可能晚到） */
    @Volatile
    internal var liveAskGen = 0

    /** 一词一轮冷却表：fold 后关键词 → 最近真实发词时刻（毫秒）。进程内存态，重启清零（与 FloodBudget 同口径） */
    internal val liveAskSentAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // ---- 答案区 eager 解析状态（秒回区/历史区共用同一 link 空间；进程级，重启清零） ----

    /** link → 已解析出的可播条目（跨词/跨轮复用，同链接零重复解析） */
    internal val answerItemCache = java.util.concurrent.ConcurrentHashMap<String, dev.tgfeed.core.td.VideoItem>()

    /** 解析失败死信（进程级不反复重试——文本帖/频道主页/失效链接恒落空，重试只是白烧请求） */
    internal val answerResolveDead = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 解析在飞集合（防同一链接并发重复解析） */
    internal val answerResolveInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 历史区 eager 解析词级闸：fold 后一词每进程最多一轮（8 条上限随轮定格；防抖换词不累积成本） */
    internal val answerResolvedWords = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 用户名链接（chatId==0）eager 解析成功后回填的 link→chatId 表：标题行借它升级查真频道标题 */
    internal val answerLinkChatCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    internal val _groupAnswerRows = MutableStateFlow<Pair<String, List<GroupAnswerRow>>?>(null)
    val groupAnswerRows: StateFlow<Pair<String, List<GroupAnswerRow>>?> = _groupAnswerRows.asStateFlow()

    /**
     * 历史答案区 eager 解析入口（fullSearch 查出答案后调用）：一词每进程一轮
     * （[answerResolvedWords] 闸），前 [TdLibGateway.ANSWER_INLINE_RESOLVE_MAX] 条进解析窗，
     * 整批走网关 resolveGroupAnswerItems（查询车道 1 次，与 searchViaGroup 同口径）；
     * 失败链接进死信不反复重试；预算耗尽（null）不进死信、下轮搜索可重试。
     */
    internal val _searchMediaTab = MutableStateFlow(-1)
    val searchMediaTab: StateFlow<Int> = _searchMediaTab.asStateFlow()

    /** 搜索结果内容筛选（方向/时长/发布时间，复用 FeedFilter；默认全 0 = 不筛选） */
    internal val _searchFilter = MutableStateFlow(FeedFilter())
    val searchFilter: StateFlow<FeedFilter> = _searchFilter.asStateFlow()

    /** 搜索被用户手动停止标记（修复包 #2）：挡 fullSearchMore 复活扇出；新一轮 fullSearch 复位 */
    internal val _fullSearchStopped = MutableStateFlow(false)
    val fullSearchStopped: StateFlow<Boolean> = _fullSearchStopped.asStateFlow()

    /** 全量搜索进行中（首页扇出或翻页续搜；兼作 fullSearchMore 的防重入闸） */
    internal val _fullSearchLoading = MutableStateFlow(false)
    val fullSearchLoading: StateFlow<Boolean> = _fullSearchLoading.asStateFlow()

    /** 后台波段标记（第九轮）：30s 前台硬顶后剩余频道转低强度续搜时为 true——
     *  搜索页胶囊据此切低亮"后台继续搜索中…"（可点停），扫完/被停自动消失 */
    internal val _fullSearchBackground = MutableStateFlow(false)
    val fullSearchBackground: StateFlow<Boolean> = _fullSearchBackground.asStateFlow()

    /** 当前搜索会话；新 fullSearch 整体替换，旧会话协程靠引用比对丢弃结果（不灌新词列表） */
    @Volatile
    internal var searchSession: SearchSession? = null

    /** 当前搜索协程：新搜索取消旧搜索（防旧词结果灌进新词列表） */
    internal var fullSearchJob: Job? = null

    /**
     * 发起一次全量搜索（M1 主动脉）：listSearchableChats（网关侧 5min TTL 缓存，含频道/群组/私聊）
     * → 剔除被屏蔽频道 → 按 decayedChannelWeights 口味权重降序 → 首波前 [FULL_SEARCH_FIRST_WAVE] 个频道
     * Semaphore([SEARCH_FANOUT_CONCURRENCY]) 并发扇出 searchChannelAll 首页，结果边到边聚合上屏；
     * 尾部频道随 [fullSearchMore] 惰性扇出。空词忽略；新搜索取消并替换旧会话。
     * Mock 降级（SEARCH-ARCH §7 红线）：无服务端搜索，覆盖度定格 0/0、空结果静默，不崩溃。
     *
     * SEARCH-PERF §1：扇出前先查同词会话缓存（[SEARCH_SESSION_CACHE_TTL_MS] 内），
     * 命中则上次聚合结果直接上屏、不重新扇出（见协程内命中分支）。
     *
     * 频道腿双波段（第九轮，修"全局搜不全"）：前台波段 [FULL_SEARCH_TOTAL_BUDGET_MS]（30 秒）
     * 内按既有节奏扫；到点不再丢弃剩余 pending 频道——转**后台波段**低强度续搜
     * （并发 [BACKGROUND_FANOUT_CONCURRENCY]、波间隔 [BACKGROUND_WAVE_INTERVAL_MS]），
     * 结果经同一 scored 池流式并入上屏，直至全频道扫完；期间胶囊切低亮"后台继续搜索中…"
     * （[_fullSearchBackground]，点"停止"走 cancelFullSearch 一并取消），扫完自动消失。
     * 内存安全闸（结果池上限/堆水位）两波段同一套。
     */
    internal val foldCache = HashMap<String, String>()
    internal val foldCacheLock = Any()

    /** 折叠文本缓存读：未命中现算并写回。cacheKey 建议：caption 用 item.key，音频用 "key|a"，频道名用 "ch:chatId" */

    // ---------------- T0-5 追加区：搜索回跳筛选复位探测 ----------------

    /**
     * 当前源的搜索标识：一次性搜索结果源带 searchQuery（MainActivity 搜索/机器人 onPlay 链、
     * DeepSearchIndex 源的共同约定），普通源为 null。
     * FeedScreen 用它侦测"搜索源刚接管 Feed"，把可能吃掉所点条目的旧筛选器复位
     * （MainActivity 只复位了 mediaTypeTab，方向/时长/时间筛选会静默滤掉所点条目）。
     * 只读现值，不做 StateFlow——FeedScreen 侧低频轮询读取即可（源切换是低频事件）。
     */
    val currentSourceSearchQuery: String?
        get() = source.request.searchQuery

    // ---- 音频在播/分屏画布恢复公共 API 委托（实现见 FeedAudioPart.kt） ----
    fun reportAudioPlaying(item: VideoItem, playing: Boolean) = reportAudioPlayingImpl(item, playing)
    fun clearNowPlayingAudio() = clearNowPlayingAudioImpl()
    fun jumpToAudio(item: VideoItem) = jumpToAudioImpl(item)
    suspend fun reviveDetachedItems(items: List<VideoItem>): List<VideoItem> = reviveDetachedItemsImpl(items)
    fun fetchCoverFrame(item: VideoItem) = fetchCoverFrameImpl(item)

    /**
     * 当前音频条目（mediaType=2）。AudioCard 起播/暂停时经 [reportAudioPlaying] 上报；
     * 暂停不归空——迷你播放条保留展示可恢复（音乐 App 惯例）；
     * 播完/共享播放器清空时经 [clearNowPlayingAudio] 移除。
     */
    internal val _nowPlayingAudio = MutableStateFlow<VideoItem?>(null)
    val nowPlayingAudio: StateFlow<VideoItem?> = _nowPlayingAudio.asStateFlow()

    /**
     * 在播音频是否正在出声（迷你条播放/暂停图标）。
     * 卡片存活期间由 AudioCard 上报；卡片划出销毁后（音频继续放、迷你条接管）
     * 由 FeedScreen 挂在共享播放器上的监听续报（焦点抢占暂停等路径的兜底）。
     */
    internal val _audioPlaying = MutableStateFlow(false)
    val audioPlaying: StateFlow<Boolean> = _audioPlaying.asStateFlow()

    /** AudioCard 播放态上报：item 立为当前音频，playing 同步出声态（重复上报幂等） */

    // ---- 口味雷达公共 API 委托（实现见 FeedRadarPart.kt） ----
    fun runTasteRadar() = runTasteRadarImpl()

    /**
     * 口味雷达引擎（W5 落地，算法/红线详见 TasteRadar 类注释）。
     * titleProvider 注入内存态频道标题表（订阅 + 频道列表 + 当前 Feed 条目，
     * 订阅优先先填、其余不覆盖补位），让"看过但未订阅"的频道也能贡献口味词；
     * stateFile 是"每日一次"节流戳的落盘位置——LocalStore 在任务冻结边界内不能加字段，
     * 节流戳自立小文件 taste_radar.json（走 LocalStore 同款临时文件 + rename 原子写，
     * 放在同一个 store/ 目录下便于管理）。
     */
    internal val tasteRadar = TasteRadar(
        store = store,
        gateway = gateway,
        titleProvider = {
            val map = HashMap<Long, String>()
            _subscriptions.value.forEach { if (it.title.isNotBlank()) map[it.id] = it.title }
            _channels.value.forEach { if (it.title.isNotBlank()) map.putIfAbsent(it.id, it.title) }
            _items.value.forEach { if (it.channelTitle.isNotBlank()) map.putIfAbsent(it.chatId, it.channelTitle) }
            map
        },
        stateFile = File(app.filesDir, "store/taste_radar.json"),
    )

    /**
     * 雷达候选：最近一次巡逻命中的频道（命中条数降序，已过滤已订阅/已屏蔽）。
     * 发现页 refreshDiscovery 合并候选时把它作为第三路来源（角标"雷达"）；
     * 巡逻完成时也直接并入 [_discoveryCandidates]/[_discoverySources]，不等下一次刷新。
     */
    internal val _radarCandidates = MutableStateFlow<List<ChannelBrief>>(emptyList())
    val radarCandidates: StateFlow<List<ChannelBrief>> = _radarCandidates.asStateFlow()

    /** 雷达巡逻进行中标记（兼作防重入闸；每日节流由 TasteRadar 内部兜底） */
    internal val _radarRunning = MutableStateFlow(false)
    val radarRunning: StateFlow<Boolean> = _radarRunning.asStateFlow()

    // ---- WiFi 追更预载公共 API 委托（实现见 FeedPrefetchPart.kt） ----
    fun setWifiPrefetch(v: Boolean) = setWifiPrefetchImpl(v)
    fun runWifiPrefetch() = runWifiPrefetchImpl()

    /** WiFi 预载开关内存态（默认开，存 AppPrefs.wifiPrefetch）；设置页开关的数据源 */
    internal val _wifiPrefetch = MutableStateFlow(true)
    val wifiPrefetch: StateFlow<Boolean> = _wifiPrefetch.asStateFlow()

    init {
        // 启动时从 AppPrefs 恢复 WiFi 预载开关（IO 线程加载，不阻塞首帧；独立 init 块便于追加维护）
        viewModelScope.launch(Dispatchers.IO) {
            _wifiPrefetch.value = runCatching { prefs.wifiPrefetch }.getOrDefault(true)
        }
    }

    /** WiFi 预载进行中标记（兼作防重入闸——一轮没完时再次触发直接忽略）；UI 可用它做轻提示 */
    internal val _wifiPrefetching = MutableStateFlow(false)
    val wifiPrefetching: StateFlow<Boolean> = _wifiPrefetching.asStateFlow()

    /** 最近一次 WiFi 预载发起时刻（毫秒）；距上次不足 [WIFI_PREFETCH_MIN_INTERVAL_MS] 的触发直接忽略（防 WiFi 抖动反复预载） */
    @Volatile
    internal var lastWifiPrefetchAtMs = 0L

    init {
        // WiFi 预载触发：常驻收集计量态 StateFlow（R195 起由 NetworkMeteredBus 驱动），
        // 只在"计量 → 非计量"的跳变沿上发起一轮（即"到家连上 WiFi 的那一刻"）。
        // 冷启动已在 WiFi 下不构成跳变沿，不触发——预载语义就是"刚切到 WiFi 时补一轮"。
        // 项目无 WorkManager 依赖（任务边界：不加新依赖），App 存活期间 viewModelScope
        // 常驻收集即可，进程被杀自然停止；前台约束在执行侧复检（见 runWifiPrefetch）。
        viewModelScope.launch(Dispatchers.IO) {
            var wasMetered = _networkMetered.value
            _networkMetered.collect { metered ->
                if (wasMetered && !metered) runWifiPrefetch()
                wasMetered = metered
            }
        }
    }

    // ---- 本地即时搜索公共 API 委托（实现见 FeedLocalSearchPart.kt） ----
    suspend fun localSearchInstant(query: String): List<VideoItem> = localSearchInstantImpl(query)
    fun tasteSuggestions(limit: Int = 6): List<String> = tasteSuggestionsImpl(limit)

    /** 本地即时搜索代次（SEARCH-PERF §4）：每次调用 +1，旧词扫描在检查点发现代次变迁即中止 */
    internal val localSearchGen = java.util.concurrent.atomic.AtomicInteger(0)

    /** 本地即时搜索 query 结果缓存：key=trim 后 query，TTL [LOCAL_SEARCH_CACHE_TTL_MS]（打字删字回退直接命中） */
    internal val localQueryCache = LinkedHashMap<String, Pair<Long, List<VideoItem>>>()
    internal val localCacheLock = Any()

    /** 本地缓存 LRU 写：put 后超出容量淘汰最旧条目（LinkedHashMap 插入序，首位最旧） */

    // ---- 查看上下文/相册导航/历史回溯公共 API 委托（实现见 FeedNavPart.kt） ----
    fun consumeContextJump() = consumeContextJumpImpl()
    fun openChannelAt(item: VideoItem) = openChannelAtImpl(item)
    fun albumGroupOf(item: VideoItem): List<VideoItem> = albumGroupOfImpl(item)
    fun jumpToAlbumSibling(item: VideoItem, delta: Int): Boolean = jumpToAlbumSiblingImpl(item, delta)
    fun loadChannelIndexedItems(chatId: Long): List<VideoItem> = loadChannelIndexedItemsImpl(chatId)
    /** R212：频道主页索引分页补页（有界 LIMIT+OFFSET，见 FeedNavPart.loadChannelIndexedPageImpl） */
    fun loadChannelIndexedPage(chatId: Long, limit: Int, offset: Int): List<VideoItem> =
        loadChannelIndexedPageImpl(chatId, limit, offset)
    fun patchCaption(key: String, text: String) = patchCaptionImpl(key, text)
    /** R238 评论线程载体（弹幕/评论流共用同一选取尺子，见 FeedNavPart.commentThreadCarrierImpl） */
    fun commentThreadCarrier(item: VideoItem): VideoItem = commentThreadCarrierImpl(item)
    /** R238 评论媒体流：拉线程媒体回复插入当前条目后并滚到首条，返回 Toast 文案 */
    suspend fun openCommentStream(item: VideoItem): String = openCommentStreamImpl(item)

    /**
     * R238 评论媒体流会话级去重账（chatId:messageId 线程键）：同一线程只展开一次。
     * 纯内存态，随 VM 生灭——切源/重启后重开是合理语义（列表已换血）。
     */
    internal val commentStreamOpened = HashSet<String>()

    // ---- R142 追剧模式：剧集链连播公共 API 委托（实现见 FeedEpisodePart.kt） ----
    /** 当前条目在剧集链上有下一集 → 跳链内下一集并返回 true；否则 false（回落顺序连播） */
    fun advanceEpisodeChain(item: VideoItem): Boolean = advanceEpisodeChainImpl(item)

    /**
     * 待定位的上下文条目 key（"查看上下文"专用）。
     * [openChannelAt] 置位；FeedScreen 观察到后等目标进列表、滚动定位并调 [consumeContextJump] 清除。
     * 用 StateFlow 持值而非 resumeScroll 一次性事件：定位必须等切源后的异步首拉完成，
     * 事件发了但列表没到会落空；持值让 UI 侧等得起。
     */
    internal val _contextJumpKey = MutableStateFlow<String?>(null)
    val contextJumpKey: StateFlow<String?> = _contextJumpKey.asStateFlow()

    /** FeedScreen 定位收口后清除待定位标记（幂等；超时放弃也由它清，防残留误跳） */
    /**
     * 历史回溯节拍器：loadMore 每处理一页 +1，逢 [BACKFILL_EVERY_PAGES] 倍数页触发注入。
     * 切源/刷新清零（与列表同生命周期）；IO 线程自增、主线程清零，@Volatile 保证可见性，
     * 偶发竞态只是节拍早/晚一页，无正确性影响。
     */
    @Volatile
    internal var backfillPages = 0

    /**
     * R298 兴趣召回 tag 轮转游标：recallByInterest 每页从兴趣轴 top 标签窗取
     * [INTEREST_TAGS_PER_PAGE] 个后推进（模 top.size 收敛），防每页都召回同一批话题。
     * 仅 IO 线程（loadMore 拉页循环）读写；切源/刷新不清零——兴趣轴本身连续，
     * 轮转跨源续推无伤（与 backfillPages 的列表生命周期口径不同，有意为之）。
     */
    internal var interestTagRotation = 0

    /** 名册缓存（[INDEXED_MEDIA_TIERS_TTL_MS]，与 R92 分层快照同节拍；@Volatile 跨线程可见） */
    @Volatile
    internal var newFaceRosterCache: NewFaceRoster? = null

    /**
     * R98 会话级新面孔露脸账（chatId → 本会话最近一次进页时刻，纯内存态）：
     * 补位选频道时优先还没露脸的（跨页轮换，防一页到一页都是同几张生面孔）；
     * 名册重算时并入"最久未露面"口径。切源/刷新清零（与列表同生命周期）。
     */
    internal val newFaceLock = Any()
    internal val newFaceShownAt = HashMap<Long, Long>()

    // ---- 分屏会话托管公共 API 委托（实现见 FeedGridPart.kt） ----
    fun gridFeedSource( store: LocalStore, excludeSeenProvider: () -> Boolean, ): WeightedAggregateFeedSource = gridFeedSourceImpl(store, excludeSeenProvider)
    fun noteGridDisplayed(items: List<VideoItem>) = noteGridDisplayedImpl(items)
    fun gridSeenIdentities(): Pair<Set<String>, Set<String>> = gridSeenIdentitiesImpl()
    fun markGridWatched(item: VideoItem) = markGridWatchedImpl(item)
    fun markSeenBatch(items: List<VideoItem>) = markSeenBatchImpl(items)
    /** R150 分屏"显示即看过"落账（B）：会话双轨登记 + seen 底账批量落盘 */
    fun markGridDisplayedSeen(items: List<VideoItem>) = markGridDisplayedSeenImpl(items)
    fun gridMediaCursorFor(tab: Int): String? = gridMediaCursorForImpl(tab)
    fun updateGridMediaCursor(tab: Int, next: String?) = updateGridMediaCursorImpl(tab, next)

    /** 分屏自持加权混排源（懒建一次后复用，游标随 VM 存活） */
    @Volatile
    internal var gridSourceRef: WeightedAggregateFeedSource? = null

    /**
     * 分屏会话已看双轨（key 集 + fileUniqueId 指纹集）：GridScreen 实际渲染格的
     * 当前内容登记处。copy-on-write 线程安全兜底（登记在快照流协程、读取在 IO 翻页协程）；
     * 极端并发下偶发丢一条登记只会导致旧片多留一页，无正确性影响。
     */
    @Volatile
    internal var gridSeenKeys: Set<String> = emptySet()

    @Volatile
    internal var gridSeenUids: Set<String> = emptySet()

    /**
     * 分屏类型 tab 直拉（fetchAggregatedMedia）游标：按 tab 归属保存——
     * 换 tab 后旧游标对新媒体类型无效（服务端游标语义随过滤条件），
     * 存取都带 tab 判定防"跨 tab 游标串味"；与自持源同理跨进出分屏存活。
     */
    @Volatile
    internal var gridMediaCursorPair: Pair<Int, String?>? = null

}

class FeedViewModelFactory(
    private val gateway: TdGateway,
    private val app: Application,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = FeedViewModel(gateway, app) as T
}
