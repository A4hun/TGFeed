package dev.tgfeed

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import dev.tgfeed.core.diag.DiagLog
import dev.tgfeed.core.diag.DiagServer
import dev.tgfeed.core.playback.NormalizingRenderersFactory
import dev.tgfeed.core.playback.PlayerPool
import dev.tgfeed.core.prefs.AppPrefs
import dev.tgfeed.core.td.ChannelBrief
import dev.tgfeed.core.td.FeedPage
import dev.tgfeed.core.td.FeedRequest
import dev.tgfeed.core.td.GatewayState
import dev.tgfeed.core.td.MockTdGateway
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // R51：诊断采集装机要赶在一切业务之前——崩溃处理器早挂早生效，全程异常才抓得到
        DiagLog.install(this)
        // R165：进程健康监视器（主线程心跳取证 + 内存压力分级 + 深索引派工喊停）
        // ——16384 夜事故全仓零内存感知，OOM 僵死全程无一处能留痕；装机即开、只读旁路
        dev.tgfeed.core.diag.AppHealthMonitor.start()
        // R80：播放链路结构化埋点装机（紧随 DiagLog，播放事件从进程起步就可复盘）
        dev.tgfeed.core.playback.PlaybackTrace.install(this)
        // R195：计量网络进程级总线装机（App.onCreate 起系统回调常驻——网关侧
        // WiFi 激进整载器与 FeedViewModel 共用单源；幂等，VM 侧另有兜底 start）
        runCatching { dev.tgfeed.core.net.NetworkMeteredBus.start(this) }
        DiagLog.log("App", "进程启动")
        // R52：冷启动分段计时起点（探针首响即 t0）
        dev.tgfeed.core.diag.ColdStartProbe.mark("App.onCreate 进程创建")
        container = AppContainer(this)
        dev.tgfeed.core.diag.ColdStartProbe.mark("App 容器就绪")
    }

    /**
     * R165：系统回收压力接线——深索引派工限时喊停（AppHealthMonitor 消费）。
     * 夜事故根因链里有「LMKD 从未出手、进程自己僵死」一环：系统给过信号没人接。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        dev.tgfeed.core.diag.AppHealthMonitor.onTrimMemory(level)
    }
}

class AppContainer(private val context: Application) {
    val prefs = AppPrefs(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * R51 局域网只读诊断端点（独立常驻，与 TgStreamServer 的按需回环流服务互不干扰）。
     * 构造即按开关启动：开发者随时 curl /diag 拉诊断日志，用户零操作。
     * R146 安全收口：仅 debug 构建会真正启动（init 处 BuildConfig.DEBUG 闸 +
     * DiagServer.start 自身硬闸双保险）；release 构造实例但不 bind 端口——
     * 诊断落盘（DiagLog/crash.log、PlaybackTrace）不经本端点，照常保留。
     */
    val diagServer = DiagServer(context)

    /**
     * R63 每日 digest 留存钩子：订阅频道当天有新内容时，傍晚发一条系统通知拉回 app。
     * 数据只读（直连索引库与订阅表，不碰网关/网络），调度与文案全在 core/digest/DailyDigest。
     */
    val dailyDigest = dev.tgfeed.core.digest.DailyDigest(context, prefs)

    /**
     * R203 RT-2 那年今日回忆杀推送：周年日（有存货的年轮）晨间一条系统通知，
     * 点进直开 TL-2 页。DailyDigest 骨架照抄（调度/幂等/文案脱敏三纪律）；
     * 数据走 OnThisDaySource 卫星只读实例（无 VM 场景，见该类注释），索引未就绪
     * 自然不推（R202 后台构建完成后下一次触发补上）。
     */
    val onThisDayPush = dev.tgfeed.core.onthisday.OnThisDayPush(
        context,
        prefs,
        dev.tgfeed.core.onthisday.DeepSearchOnThisDaySource(context),
    )

    /**
     * R52b 首屏快照的进程级预读结果（App.onCreate 起的后台读盘；null = 还没读完/无快照）。
     * 每进程只供 FeedViewModel 冷启动上屏读一次；快照的后续写（saveFeedSnapshot）不回填
     * 本字段——它只是"本次冷启动"的起步垫脚石，不是常驻缓存。
     */
    @Volatile
    var feedSnapshotPreload: dev.tgfeed.core.store.FeedSnapshot? = null
        private set

    init {
        // R208 风暴闸装机接线（全局启动协调·单源的配置源）：一切后台重活的
        // 「系统开机静默期+负载退避」判定从这里读 prefs（未 attach 时按默认值
        // 工作）。必须早于任何消费方起跑（StorageGovernor/预热/爬虫都在其后）。
        dev.tgfeed.core.td.SystemStormGate.attach(prefs)
        // R146 安全收口：诊断端点只在 debug 构建启动（用户口径："发正式版的话诊断通道得隐藏"）；
        // release 里 prefs 开关再开也不 bind——端口不开、无监听（DiagServer.start 另有硬闸兜底）
        if (BuildConfig.DEBUG && prefs.diagServerEnabled) diagServer.start()
        // R63：digest 调度随容器启动（进程内协程；"app 不开就不发"的冷启动局限见 DailyDigest 头注释）
        dailyDigest.start(scope)
        // R203：那年今日推送同随容器启动（同款进程内协程局限；默认闸点 10 点与 digest 错峰）
        onThisDayPush.start(scope)
        // R52b：首屏快照进程级预读——App.onCreate 即在后台 IO 读盘解析，与 TDLib
        // 初始化全程并行；FeedViewModel 冷启动上屏直接吃内存，零磁盘零锁等待
        //（修平板"授权 Ready 后快照上屏再等数秒"：快照读原本排在 VM init 预载车队里）
        scope.launch(Dispatchers.IO) {
            feedSnapshotPreload = runCatching {
                dev.tgfeed.core.store.LocalStore(context).loadFeedSnapshot()
            }.getOrNull()
            dev.tgfeed.core.diag.ColdStartProbe.mark("首屏快照进程预读完成（${feedSnapshotPreload?.items?.size ?: 0} 条）")
        }
        // R218 建表/R227 扩展：画面姿态记忆表（mirrored.json，旋转+镜像复合态）
        // 进程级预读——后台 IO 装进 UI 侧 FrameAdjustStore 快照表，视频页/分屏格
        // 首读零盘读（不预读也可用：首读同步装载兜底，只是多一次小文件盘读）
        scope.launch(Dispatchers.IO) {
            runCatching { dev.tgfeed.ui.components.FrameAdjustStore.warmup(context) }
        }
    }

    /**
     * 设置页开关回调：即时落盘 + 即时启停（无需重启）。
     * R146：release 构建里不启停（开关只落盘偏好，端口永远不开；DiagServer.start 硬闸兜底）
     */
    fun setDiagServerEnabled(enabled: Boolean) {
        prefs.diagServerEnabled = enabled
        if (BuildConfig.DEBUG) {
            if (enabled) diagServer.start() else diagServer.stop()
        }
    }

    // H6：冷启动不在 Application.onCreate 主线程建 TDLib 客户端
    // （System.loadLibrary("tdjni") 的 aar 38MB + TdClient 构造 + init 估 50~300ms，挡首帧）。
    // 先放轻量占位网关（状态恒 Loading，UI 走原有 GatewayState.Loading 路由兜底），
    // 真正的创建推迟到 MainActivity setContent 里的 LaunchedEffect(Unit) 调 ensureGateway()。
    private val _gateway = MutableStateFlow<TdGateway>(PlaceholderGateway())
    val gateway: StateFlow<TdGateway> = _gateway

    /** H6：真实网关是否已创建（ensureGateway 幂等闸；useMock/useReal 显式切换同样置位） */
    private var gatewayEnsured = false

    /**
     * H6 首帧后触发：真正创建 TDLib 客户端（有凭证）或 Mock（无凭证）。
     * 幂等、线程安全；由 MainActivity 的 LaunchedEffect(Unit) 调用——
     * 此时首帧已绘制，loadLibrary/TdClient 开销不再挡冷启动。
     */
    @Synchronized
    fun ensureGateway(): TdGateway {
        if (!gatewayEnsured) {
            gatewayEnsured = true
            // R52：网关创建含 System.loadLibrary("tdjni") + TdClient 构造，打点量耗时
            dev.tgfeed.core.diag.ColdStartProbe.mark("TDLib 网关创建开始")
            _gateway.value = createDefaultGateway()
            dev.tgfeed.core.diag.ColdStartProbe.mark("TDLib 网关创建完成")
        }
        return _gateway.value
    }

    /** 是否处于演示模式（决定未配置凭证时是否直接进 Feed） */
    private val _demoMode = MutableStateFlow(prefs.lastMode == "demo" && !hasApiCredentials())
    val demoMode: StateFlow<Boolean> = _demoMode

    /** 视图模式："feed" 单屏 / "grid" 分屏 */
    private val _viewMode = MutableStateFlow("feed")
    val viewMode: StateFlow<String> = _viewMode

    fun showGrid() { _viewMode.value = "grid" }

    /** 回单屏 Feed 模式（分屏退出/各跳转链落点）——R177 起同时请求落定到首页页位；
     * R239 起归位同时收时间线 overlay（各子页 onPlay 落点都是「去 Feed 看播放」，
     * 时间线 tab 必须让位露出播放） */
    fun showFeed() {
        _viewMode.value = "feed"
        homeTimelineOn.value = false
        requestTopLevelPage(0)
    }

    /**
     * R182「从分屏全屏单播进了 Feed」标记（viewMode 级跳转，返回语义矩阵 L2）：
     * 分屏格子点全屏 → viewMode 切回 feed 定位播放；按返回先回分屏（格子布局在
     * container.gridPlaylist 里还在）。分屏自身退出（onExit→showFeed）= 用户显式
     * 离开，由退出路径清位；真关分屏不回跳。
     */
    val gridReturnPending = mutableStateOf(false)

    /** gridReturnPending 的置位时刻（BackNavClock；返回消费器按「最新跳转先回」定序） */
    var gridReturnStamp = 0L

    // ---------------- R182 返回消费序时刻戳（L1 源栈 ∪ L2 路由 pending 统一定序） ----------------
    // 各 pending 的置位时刻由跳转点写入（BackNavClock.next()）——同一处理器内
    // 「切源入栈（戳 n）→ 置 pending（戳 n+1）」，故路由跳转第一次返回恒先回子页
    // （R177 语义保持），子页关闭后再弹源栈；feed 内后续跳转戳更新者先回。
    // 见 FeedBackStackPart.kt 与 r182_notes.md 矩阵。

    /** searchReturnPending 的置位时刻（BackNavClock） */
    var searchReturnStamp = 0L

    /** discoverReturnPending 的置位时刻（BackNavClock） */
    var discoverReturnStamp = 0L

    /** channelHomeReturnPending 的置位时刻（BackNavClock） */
    var channelHomeReturnStamp = 0L

    // ---------------- R180 App 内导航播控（需求①页面切换暂停 + 需求②音视频共存） ----------------

    /**
     * R180 Feed 页台前信号（导航闸的输入，需求①）：**MainActivity 合成写入**——
     * 顶层页位==首页（滚动中跟手取 targetPage、静止取 settledPage）&& 无子页整页路由
     * && 无频道主页覆盖层 && 视图模式==feed。FeedScreen 的导航闸 effect 消费（叠加
     * 本页内的满屏沉浸态后走 NavPlaybackPolicy 纯函数裁决）。初始 true（冷启动在首页）。
     * 与 feedChromeHidden 的分工：那是"chrome 退场"（沉浸态，页面仍在播）；
     * 本信号是"页面不在台前"（导航暂停的触发面），两者语义正交。
     */
    val feedPageActive = mutableStateOf(true)

    /**
     * R180 共享音频播放器（进程级持有；原为 FeedScreen remember 级）。
     * 上提动机=需求②开档的背景听模式：切到别的页面（FeedScreen dispose）音频必须
     * 存活继续播——原 remember 实现在页面 dispose 时 release，音频必死。
     * - 懒构造：首次取用才建（ExoPlayer 构建不进 App.onCreate 冷启动路径）；
     * - 音量均衡挂点/焦点语义原样搬移（NormalizingRenderersFactory + attachTo）；
     * - handleAudioFocus = 共存开关取反（关=参与系统焦点竞争=互斥语义；开=退出竞争
     *   =可与视频同时出声，来电等系统焦点也一并豁免——开档已知取舍，设置行文案明示）；
     * - 后台闸登记（R158 纪律继续覆盖：FeedScreen 已 dispose 时 currentPlayerPauser
     *   够不到本实例，经 [PlayerPool.attachExternalBackgroundGate] 挂迟到拦截+落闸暂停）。
     * 生命周期=进程级：不主动 release（空闲实例无缓冲无解码器，占用可忽略）。
     * 仅主线程访问（FeedScreen 组合/SettingsScreen 回调，两者都在主线程）。
     */
    private var _sharedAudioPlayer: ExoPlayer? = null
    val sharedAudioPlayer: ExoPlayer
        get() = _sharedAudioPlayer ?: buildSharedAudioPlayer().also { _sharedAudioPlayer = it }

    /**
     * R299 B1 听单控制器（进程级，与共享音频播放器同寿）：可编辑持久听单（插队/
     * 移除/存播放列表——PlaylistMeta 底座）+ 后台连播补全（ENDED 仲裁唯一收口，
     * 队列推进权在播放器层，FeedScreen dispose 后连播照活）。懒构造：冷启动恢复
     * listen_queue.json 走内部 IO 协程，不挡首帧。
     */
    val listenQueueController: dev.tgfeed.core.playback.ListenQueueController by lazy {
        dev.tgfeed.core.playback.ListenQueueController(
            store = dev.tgfeed.core.store.LocalStore(context),
            gatewayProvider = { _gateway.value },
        )
    }

    private val sharedAudioAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    private fun buildSharedAudioPlayer(): ExoPlayer {
        val audioRenderersFactory = NormalizingRenderersFactory(context)
        return ExoPlayer.Builder(context)
            .setRenderersFactory(audioRenderersFactory)
            // 语义见类属性注释（handleAudioFocus = 共存开关取反）
            .setAudioAttributes(sharedAudioAttrs, !prefs.allowAudioVideoSimultaneous)
            .build()
            .also {
                audioRenderersFactory.attachTo(it)
                PlayerPool.attachExternalBackgroundGate(it)
                // R299 B1：听单 ENDED 仲裁挂上（监听器随播放器建造注册，先于一切
                // UI 层监听——仲裁顺序确定性）；锁屏上下曲的 shared 兜底=听单前进/
                // 上一首（FeedScreen 接线 playerPool.onSkipNext 期间经 setter 镜像
                // 覆盖本兜底，dispose 时由 FeedScreen 恢复本兜底——纯背景期锁屏
                // 「下一首」从「按了无反应」升为听单连播）
                listenQueueController.attachTo(it)
                PlayerPool.sharedSkipNextHandler = { listenQueueController.skipNext() }
                PlayerPool.sharedSkipPrevHandler = { listenQueueController.skipPrev() }
            }
    }

    /**
     * R180 共存开关运行时切换（SettingsScreen 开关行回调）：落盘 + 即时生效到共享
     * 音频播放器的焦点策略（池内视频播放器保持焦点参与不变——"同时出声"由音频侧
     * 让位实现，与互斥现状=系统焦点的机制兼容）。未构造过（从未播过音频）时只落盘，
     * 懒构造时按新值起手。
     */
    fun setAllowAudioVideoSimultaneous(on: Boolean) {
        prefs.allowAudioVideoSimultaneous = on
        _sharedAudioPlayer?.setAudioAttributes(sharedAudioAttrs, !on)
    }


    /** 设置页显隐（独立于登录/演示路由，任何模式下可进） */
    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings

    fun closeSettings() { _showSettings.value = false }

    /** 下载管理页显隐 */
    private val _showDownloads = MutableStateFlow(false)
    val showDownloads: StateFlow<Boolean> = _showDownloads

    fun openDownloads() { _showDownloads.value = true }

    fun closeDownloads() {
        _showDownloads.value = false
        downloadsReturnPending.value = false // 真关下载页时清掉"回下载页"标记（R182）
    }

    /**
     * R182「从下载页点条目进了 Feed」标记（返回语义矩阵 L2，镜像 searchReturnPending
     * 模式）：Feed 里按返回键先回下载管理页（列表在 VM 里还在）。置位方：MainActivity
     * 下载页 onPlay（置位须在 closeDownloads 之后——它清位）。
     */
    val downloadsReturnPending = mutableStateOf(false)

    /** downloadsReturnPending 的置位时刻（BackNavClock；返回消费器按「最新跳转先回」定序） */
    var downloadsReturnStamp = 0L

    /** 观看报告页显隐 */
    private val _showWatchReport = MutableStateFlow(false)
    val showWatchReport: StateFlow<Boolean> = _showWatchReport

    fun openWatchReport() { _showWatchReport.value = true }
    fun closeWatchReport() { _showWatchReport.value = false }

    /** 频道体检页显隐（R132 僵尸/变质频道一键断舍离；入口：我的页"频道体检"行） */
    private val _showChannelHealth = MutableStateFlow(false)
    val showChannelHealth: StateFlow<Boolean> = _showChannelHealth

    fun openChannelHealth() { _showChannelHealth.value = true }
    fun closeChannelHealth() { _showChannelHealth.value = false }

    /** 口味星图页显隐（R143 互链图谱+观看行为渲染的星系图，可导出海报；入口：我的页"口味星图"行） */
    private val _showTasteMap = MutableStateFlow(false)
    val showTasteMap: StateFlow<Boolean> = _showTasteMap

    fun openTasteMap() { _showTasteMap.value = true }

    /**
     * R233④ 从 feed 长按「在星图中查看」的一次性运镜意图（timeMachineIntent 同
     * 模式）：open 时写入目标 chatId，星图页装配完成后运镜飞向该频道恒星并回调
     * 消费；close 时清空（一次性语义）。
     */
    val tasteMapFlyTarget = mutableStateOf<Long?>(null)

    fun openTasteMapFly(chatId: Long) {
        tasteMapFlyTarget.value = chatId
        _showTasteMap.value = true
    }

    fun clearTasteMapFlyTarget() { tasteMapFlyTarget.value = null }

    fun closeTasteMap() {
        _showTasteMap.value = false
        tasteMapFlyTarget.value = null // R233 一次性运镜意图随关页清位
        tasteMapReturnPending.value = false // 真关星图页时清掉"回星图"标记（R182）
    }

    /**
     * R205 事件研究器页显隐（WK-3 时间维度高级研究 UI；状态驱动路由，与口味星图
     * 同级）。入口：设置页高级区「时间维度工具」卡（R215 自发现页迁入；发现页入口
     * 隐藏不删码）。数据走深索引只读召回（零网络），无返回栈复杂度（返回=剥落回
     * 来源页，无 Feed 跳转）。
     * R305：开页不关设置（与 openDownloads/openWatchReport 同口径——设置页垫底下，
     * 研究器返回回设置）；层级正确性由 MainActivity when 链分支序保证（研究器分支
     * 在 showSettings 之前）。
     */
    private val _showEventResearch = MutableStateFlow(false)
    val showEventResearch: StateFlow<Boolean> = _showEventResearch

    fun openEventResearch() { _showEventResearch.value = true }
    fun closeEventResearch() { _showEventResearch.value = false }

    /**
     * R182「从口味星图点星试吃进了 Feed」标记（L2 缺口补齐，镜像 searchReturnPending
     * 模式）：Feed 里按返回键先回星图页。置位方：MainActivity 星图 onOpenChannel
     * （置位须在 closeTasteMap 之后——它清位）。
     */
    val tasteMapReturnPending = mutableStateOf(false)

    /** tasteMapReturnPending 的置位时刻（BackNavClock） */
    var tasteMapReturnStamp = 0L

    /** 观看历史页显隐（与观看报告同级，状态驱动路由；入口：我的页 / Feed 源面板"已看历史"chip） */
    private val _showWatchHistory = MutableStateFlow(false)
    val showWatchHistory: StateFlow<Boolean> = _showWatchHistory

    fun openWatchHistory() { _showWatchHistory.value = true }

    fun closeWatchHistory() {
        _showWatchHistory.value = false
        watchHistoryReturnPending.value = false // 真关历史页时清掉"回历史页"标记（R182）
    }

    /**
     * R182「从观看历史点条目进了 Feed」标记（L2 缺口补齐，镜像 searchReturnPending
     * 模式）：Feed 里按返回键先回观看历史页（历史源条目在 VM 里还在）。置位方：
     * MainActivity 历史页 onPlay（置位须在 closeWatchHistory 之后——它清位）。
     */
    val watchHistoryReturnPending = mutableStateOf(false)

    /** watchHistoryReturnPending 的置位时刻（BackNavClock） */
    var watchHistoryReturnStamp = 0L

    /**
     * R199 我的收藏页显隐（收藏查看模块；与观看历史同级，状态驱动路由）。
     * 入口：我的页「我的收藏」行（带计数徽章）。注意与 FeedViewModel.showFavorites()
     * （源面板收藏夹 chip 切源刷流）语义不同：本页是管理/回味墙（纯读侧），
     * 那边是 Feed 源——两个入口、两个消费面，勿混。
     */
    private val _showFavorites = MutableStateFlow(false)
    val showFavorites: StateFlow<Boolean> = _showFavorites

    fun openFavorites() { _showFavorites.value = true }

    fun closeFavorites() {
        _showFavorites.value = false
        favoritesReturnPending.value = false // 真关收藏页时清掉"回收藏页"标记（R182 口径）
    }

    /**
     * R182「从收藏页点条目进了 Feed」标记（L2 pending，镜像 watchHistory 模式）：
     * Feed 里按返回键先回收藏页（收藏夹源条目在 VM 里还在）。置位方：MainActivity
     * 收藏页 onPlay（置位须在 closeFavorites 之后——它清位；时刻戳晚于切源入栈）。
     */
    val favoritesReturnPending = mutableStateOf(false)

    /** favoritesReturnPending 的置位时刻（BackNavClock） */
    var favoritesReturnStamp = 0L

    /**
     * R203 那年今日页显隐（TL-2；与观看历史/收藏同级，状态驱动路由）。
     * 入口：我的页「那年今日」行（时间回看语义组）+ RT-2 通知点进（带 [onThisDayInitialYear]）。
     * [onThisDayInitialYear]：通知直达的落点年轮（1..5）；null = 页面自动择今日最佳年轮
     * （pickYearForPush 同一纯函数）。open 时写入、close 时清空（一次性意图）。
     */
    private val _showOnThisDay = MutableStateFlow(false)
    val showOnThisDay: StateFlow<Boolean> = _showOnThisDay

    /** 通知/入口携带的落点年轮（null=自动择最佳）；openOnThisDay 写入，closeOnThisDay 清空 */
    val onThisDayInitialYear = mutableStateOf<Int?>(null)

    fun openOnThisDay(yearsAgo: Int? = null) {
        onThisDayInitialYear.value = yearsAgo
        _showOnThisDay.value = true
    }

    fun closeOnThisDay() {
        _showOnThisDay.value = false
        onThisDayInitialYear.value = null
        onThisDayReturnPending.value = false // 真关页时清"回那年今日页"标记（R182 口径）
    }

    /**
     * R182 L2 pending（R203，镜像 favorites 模式）：从那年今日页点条目进了 Feed
     * 后，Feed 里按返回键先回本页（一次性源条目在 VM 里还在）。置位方：MainActivity
     * 本页 onPlay（置位须在 closeOnThisDay 之后——它清位；时刻戳晚于切源入栈）。
     */
    val onThisDayReturnPending = mutableStateOf(false)

    /** onThisDayReturnPending 的置位时刻（BackNavClock） */
    var onThisDayReturnStamp = 0L

    /**
     * R202 时间维度旗舰页显隐（全网时光机 TL-1 / 事件考古 TL-4 / 此刻横截面 DS-1
     * 三模式一屏，与收藏/那年今日同级，状态驱动路由）。入口：发现页时光机主位行
     *（Now 模式）+ 视频长按功能面板「事件考古」格（Event 模式）。[timeMachineIntent]
     * = 打开意图（三模式全部差异面）；open 时写入、close 时清空（一次性意图）。
     */
    private val _showTimeMachine = MutableStateFlow(false)
    val showTimeMachine: StateFlow<Boolean> = _showTimeMachine

    /** 当前打开的时光机意图（null 恒随 show=false）；openTimeMachine 写入 */
    val timeMachineIntent = mutableStateOf<dev.tgfeed.ui.TimeMachineIntent?>(null)

    fun openTimeMachine(intent: dev.tgfeed.ui.TimeMachineIntent) {
        timeMachineIntent.value = intent
        _showTimeMachine.value = true
    }

    fun closeTimeMachine() {
        _showTimeMachine.value = false
        timeMachineIntent.value = null
        timeMachineReturnPending.value = false // 真关时光机页时清"回时光机"标记（R182 口径）
    }

    /**
     * R202 L2 pending（镜像 favorites/onThisDay 模式）：从时光机墙点格子进了 Feed
     * 后，Feed 里按返回键先回本页（横截面源条目在 VM 里还在）。置位方：MainActivity
     * 时光机 onPlay（置位须在 closeTimeMachine 之后——它清位；时刻戳晚于切源入栈）。
     */
    val timeMachineReturnPending = mutableStateOf(false)

    /** timeMachineReturnPending 的置位时刻（BackNavClock） */
    var timeMachineReturnStamp = 0L

    /**
     * R222 hashtag 时间轴页显隐（#关键字双选弹层「时间轴探索」进；与时光机同级的
     * 状态驱动路由）。[hashtagTimelineTag] = 打开意图（无 # 前缀的标签词）；
     * open 时写入、close 时清空（一次性意图，timeMachineIntent 同模式）。
     */
    private val _showHashtagTimeline = MutableStateFlow(false)
    val showHashtagTimeline: StateFlow<Boolean> = _showHashtagTimeline

    /** 当前打开的时间轴标签（null 恒随 show=false）；openHashtagTimeline 写入 */
    val hashtagTimelineTag = mutableStateOf<String?>(null)

    fun openHashtagTimeline(tag: String) {
        hashtagTimelineTag.value = tag
        _showHashtagTimeline.value = true
    }

    fun closeHashtagTimeline() {
        _showHashtagTimeline.value = false
        hashtagTimelineTag.value = null
        hashtagTimelineReturnPending.value = false // 真关时间轴页时清"回时间轴"标记（R182 口径）
        hashtagTimelineReturnTag.value = null
    }

    /**
     * R222 L2 pending（镜像 timeMachine 模式）：从时间轴墙/轴点节点进了 Feed 后，
     * Feed 里按返回键先回本页（VM 里 hashtagTimeline 状态还在，同 tag 重进不重扫）。
     * 置位方：MainActivity 时间轴 onPlay（置位须在 closeHashtagTimeline 之后——它
     * 清位；时刻戳晚于切源入栈——r202 pending 戳序竞态口径照抄）。
     * [hashtagTimelineReturnTag] = 回跳载荷（回跳要用的 tag——close 已清一次性
     * 意图 hashtagTimelineTag，回跳意图由 pending 家族自己携带，CHANNEL_HOME 的
     * brief 载荷同模式）。
     */
    val hashtagTimelineReturnPending = mutableStateOf(false)

    /** hashtagTimelineReturnPending 的置位时刻（BackNavClock） */
    var hashtagTimelineReturnStamp = 0L

    /** hashtag 时间轴回跳载荷：回跳要重开的 tag（无 # 前缀；随 pending 置位/清理） */
    val hashtagTimelineReturnTag = mutableStateOf<String?>(null)

    /** 播放列表管理页显隐（与下载/观看报告同级，状态驱动路由；入口在"加入片单"选择器底部） */
    private val _showPlaylists = MutableStateFlow(false)
    val showPlaylists: StateFlow<Boolean> = _showPlaylists

    fun openPlaylists() { _showPlaylists.value = true }
    fun closePlaylists() { _showPlaylists.value = false }

    /**
     * R177 顶层页滚页请求（一次性，nonce 单调递增）：路由侧（底栏点按/返回键回首页/
     * 深链落点/子页 onPlay 跳转）经 [AppContainer.requestTopLevelPage] 写入，
     * MainActivity 的顶层 pager 宿主消费——`animateScrollToPage` 与手势共用同一枚
     * 落定 spring（MotionTokens.PageSpring*，提案 §2.2「点底栏=与手势同一条动画路径」）。
     * nonce 递增防同页连发被快照流去重吞掉；消费即清空槽位（pager 因二级页往返重建后
     * 不会重放已消费的旧请求）。
     */
    data class TopLevelScrollRequest(val page: Int, val nonce: Long)

    /**
     * R177 顶层页落定态（0=首页 / 1=发现 / 2=我的，与 ui.TopLevelRoute 枚举序
     * 一一对应，design-proposal §2.2 横滑序列；R229~R238 期间曾是四页——R239
     * 时间线撤顶层页回首页 tab，页序复原为三页）。第一刀（R173）的
     * showDiscover/showMine 两位布尔在此收敛为单值：横滑结构上排除叠加态，一次只有
     * 一个台前页。写入方恰两个：① 路由侧（requestTopLevelPage，意图即刻生效——底栏
     * 高亮/返回语义零延迟）；② 顶层 pager 滑动落定回写（[onTopLevelPageSettled]）。
     * 二级页开着时 pager 离场，本值就是重建时的 initialPage（「关掉设置回到我的页」
     * 的落点记忆）。
     */
    val topLevelPage = mutableStateOf(0)

    /** 待消费的滚页请求（null=无）；见 [TopLevelScrollRequest] */
    val topLevelPageRequest = mutableStateOf<TopLevelScrollRequest?>(null)
    private var topLevelPageRequestSeq = 0L

    /** 页码合法域（TopLevelRoute.entries.size） */
    private fun coercePage(page: Int): Int = page.coerceIn(0, dev.tgfeed.ui.TopLevelRoute.entries.size - 1)

    /**
     * 路由侧滚页（底栏/返回键/深链/子页跳转共用入口）：落定态即刻置位 + 发一次性请求
     * 由 pager 动画到达（与手势共用 spring）。幂等：已在目标页时请求仍发出但消费侧
     * no-op（nonce 保证不被去重吞掉）。
     */
    fun requestTopLevelPage(page: Int) {
        val target = coercePage(page)
        topLevelPage.value = target
        topLevelPageRequestSeq += 1
        topLevelPageRequest.value = TopLevelScrollRequest(target, topLevelPageRequestSeq)
    }

    /** pager 宿主消费完请求后清槽（防 pager 重建重放） */
    fun consumeTopLevelPageRequest() {
        topLevelPageRequest.value = null
    }

    /** 顶层 pager 滑动落定回写（手势路径的事实源；与路由侧写入同一位） */
    fun onTopLevelPageSettled(page: Int) {
        topLevelPage.value = coercePage(page)
    }

    /** 发现页（候选频道试吃/收/弃 + 热榜入口行 sheet；顶层 pager 第 1 页） */
    fun openDiscover() { requestTopLevelPage(1) }

    /**
     * R239 时间线（首页一等 tab）：开时间线 overlay——回首页页位 + 置
     * [homeTimelineOn]（feed 页内 PursueScreen 整层压上，FeedScreen 保持组合
     * （R189 保活），播放由 feedActive 合成闸暂停——R180 navGate 机制）。
     * 消费方：① 首页顶栏「时间线」tab 点击（FeedScreen→FeedTopBarOverlay）；
     * ② 时间线页头「关注/推荐」外的自身入口；③ Feed 播放返回链
     * （pursueReturnPending 消费）——三个入口同一落点。
     */
    fun openPursue() {
        homeTimelineOn.value = true
        requestTopLevelPage(0)
    }

    /**
     * 关时间线 overlay：时间线在台前按返回 = 回 feed 内容（homeTimelineOn=false，
     * feed 侧源/位置在共享 VM 里还在）。不需要回页码——时间线只存在于首页页 0
     * （openPursue 已保证落点；经底栏去发现/我的后关时间线——R374 起时间线台前
     * 期间用户横滑切页已封，页码由 pager 事实源管）。
     */
    fun closePursue() {
        homeTimelineOn.value = false
    }

    /**
     * R239 时间线 overlay 在场标记：true = feed 页（pager 页 0）上压着 PursueScreen
     * 整层（首页三 tab「关注|时间线|推荐」的时间线位）。**只在首页域内有意义**——
     * R374 起时间线台前期间顶层 pager 的用户横滑通道已封（topLevelSwipeEnabled 的
     * timelineInFront 位），去发现/我的只走底栏点按/程序滚页；保活语义只剩底栏
     * 通道（经底栏去发现/我的后本值保持原值，滑回首页时间线原样恢复）；
     * [showFeed] 归位收层（各子页 onPlay 落点）；MainActivity 的 feedActive 合成
     * 读取本值把播放闸让位（R180 navGate 既有机制的第二消费方）。
     */
    val homeTimelineOn = mutableStateOf(false)

    /**
     * 时间线（首页 tab）——从时间线格子进了 Feed 播放后的返回键落点标记
     * （R229 语义原样：Feed 里按返回先回时间线——openPursue 的新落点）
     */
    val pursueReturnPending = mutableStateOf(false)

    /** pursueReturnPending 的置位时刻（BackNavClock） */
    var pursueReturnStamp = 0L

    /**
     * R239 时间线刷新令（一次性，nonce 单调递增，0=无令）：双击已激活的时间线
     * tab / 时间线在台前时双击底栏「首页」→ 递增；PursueScreen 的 LaunchedEffect
     * 消费触发 PursueViewModel.requestRefresh（重拉 bootstrap）。discoverPlaza
     * TabRequest 同款 nonce 模式（递增防同值连发被快照流去重吞掉）。
     */
    val timelineRefreshRequest = mutableStateOf(0)

    /** 双击刷新统一入口：时间线 tab 在台前时由底栏/顶栏双击触发（见 KDoc） */
    fun requestTimelineRefresh() {
        timelineRefreshRequest.value += 1
    }

    /**
     * R209 发现页热榜入口令（一次性，nonce 单调递增，0=无令）：外部热榜入口的
     * 统一唯一通道。写入方：我的页「TG 广场」行、未来的深链/搜索侧入口。消费方：
     * DiscoverScreen 的 LaunchedEffect（等本页落定）置 showPlazaSheet=true 后即刻
     * [consumeDiscoverPlazaTabRequest] 清槽——R232 起热榜=发现页入口行点开的
     * [PlazaBoardSheet]（R209 双 tab 已撤），令的落点语义从「选热榜 tab」改为
     * 「开热榜 sheet」：递增防同值连发被快照流去重吞掉；清槽防二级页往返（pager
     * 整页离场重建）重放已消费的旧令（用户此后手动关掉 sheet 不许被旧令重新拉回）；
     * 保活组合期（R189 四页常驻）照常收令。
     */
    val discoverPlazaTabRequest = mutableStateOf(0)

    /** R209 统一唯一热榜入口（R232 落点=热榜 sheet）：跳发现页 + 开热榜 sheet（滚页请求与底栏点按同通道） */
    fun openDiscoverPlaza() {
        requestTopLevelPage(1)
        discoverPlazaTabRequest.value += 1
    }

    /** DiscoverScreen 消费完热榜选择令后清槽（防二级页往返重放旧令——同 topLevelPageRequest） */
    fun consumeDiscoverPlazaTabRequest() {
        discoverPlazaTabRequest.value = 0
    }

    fun closeDiscover() {
        // 显式关发现页：清「回发现页」标记（老语义，试吃/热榜链路的置位顺序约定不变）；
        // 正落在发现页时落回首页——返回语义矩阵（R177）：发现/我的按返回 = 回首页，
        // 不是退出 App。决策口径抽纯函数 ui.topLevelBackTargetPage（单测锚定）。
        discoverReturnPending.value = false
        dev.tgfeed.ui.topLevelBackTargetPage(topLevelPage.value)?.let { requestTopLevelPage(it) }
    }

    /**
     * "从发现页试吃进了频道 Feed"标记：Feed 里按返回键先回发现页（候选数据在共享 VM
     * 里还在，页面临走关掉的只是路由显隐）。与 searchReturnPending / channelHomeReturnPending
     * 同一模式；从别的入口进频道不置位，返回行为保持不变。
     */
    val discoverReturnPending = mutableStateOf(false)

    /**
     * 「我的」页（信息架构收敛：设置/下载/片单/报告/体检/星图的统一入口；顶层 pager
     * 第 2 页——R239 时间线撤顶层页后从 3 复原为 2，枚举序契约见 ui.TopLevelRoute）。
     * 从我的页进子页时子页整页压在 pager 之上（本值不动），子页返回即回我的页
     * （pager 重建 initialPage 落点记忆）。
     */
    fun openMine() { requestTopLevelPage(2) }

    fun closeMine() {
        // 显式关我的页：非首页的一级页在台前时落回首页（返回键矩阵，同 closeDiscover
        // 共用 topLevelBackTargetPage 单一定义）；子页 onPlay 剥落调用
        // （closeMine+showFeed 成对出现、同一快照内合并）不改落点——目标页由紧随的
        // showFeed() 请求裁定（与旧语义「剥落不改变落点」逐点对齐）
        dev.tgfeed.ui.topLevelBackTargetPage(topLevelPage.value)?.let { requestTopLevelPage(it) }
    }

    /** M1 全量搜索页显隐（已加入频道 100% 覆盖；与发现页同级，状态驱动路由） */
    private val _showSearch = MutableStateFlow(false)
    val showSearch: StateFlow<Boolean> = _showSearch

    /**
     * 频道主页显隐（CHANNEL-HOME-FINAL P0）：非 null = 打开该频道主页。
     * 与上面各"整页路由"不同：MainActivity 把它作为覆盖层压在 Feed 之上渲染
     * （Feed 保持组合、播放不中断），不是 when 分支互斥路由。
     */
    private val _showChannelHome = MutableStateFlow<ChannelBrief?>(null)
    val showChannelHome: StateFlow<ChannelBrief?> = _showChannelHome

    fun openChannelHome(brief: ChannelBrief) { _showChannelHome.value = brief }
    fun closeChannelHome() { _showChannelHome.value = null }

    /** "从频道主页点了条目进播放"标记：Feed 里按返回键先回该频道主页（位置还在） */
    val channelHomeReturnPending = mutableStateOf<ChannelBrief?>(null)

    /** 进搜索页时预填并自动开搜的关键词（顶栏提交搜索时写入；Compose 态，SearchScreen 进页即读） */
    val pendingSearchQuery = mutableStateOf("")

    /** "从搜索点了结果进播放"标记：Feed 里按返回键先回搜索结果页（结果在 VM 里还在，不重搜） */
    val searchReturnPending = mutableStateOf(false)

    /**
     * R173 Feed 沉浸 chrome 隐藏信号（清屏/全屏/搜索音频页/源面板开窗）：
     * FeedScreen 上报、MainActivity 消费——底栏 AnimatedVisibility 退场 +
     * LocalBottomBarInset 让位高度归零（清屏的定义就是 chrome 全退，底栏若残留
     * 就不是清屏，design-proposal §2.1 沉浸联动）。Feed 离组合时由上报方复位。
     */
    val feedChromeHidden = mutableStateOf(false)

    fun openSearch(query: String = "") {
        pendingSearchQuery.value = query
        _showSearch.value = true
    }

    fun closeSearch() {
        _showSearch.value = false
        searchReturnPending.value = false // 真关搜索页时清掉"回搜索"标记
    }

    /** 悬浮窗栈：可无限叠加，每个元素一个悬浮播放器 */
    val floatingItems = mutableStateListOf<VideoItem>()

    fun addFloating(item: VideoItem) {
        floatingItems.add(item)
    }

    fun removeFloating(item: VideoItem) {
        floatingItems.removeAll { it.key == item.key }
    }

    // ---- R230 追加4：小窗连播队列（会话内存；供给方=分屏悬浮落区弹出） ----

    /**
     * 悬浮窗连播队列：窗首条 key → 后续待播列表。弹出方（分屏拖格到悬浮落区）在
     * addFloating 前把分屏池的后续供给登记进来；窗播完经 [advanceFloatingQueue]
     * 取下一条原位续播。进程内存态（条目 VideoItem 自包含，跨屏存活、随进程消亡）。
     */
    private val floatingQueues =
        java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<VideoItem>>()

    /** 供给某悬浮窗的连播队列（空清单不占位；重复供给以后到为准） */
    fun supplyFloatingQueue(key: String, items: List<VideoItem>) {
        if (items.isEmpty()) return
        floatingQueues[key] = ArrayDeque(items)
    }

    /**
     * 取下一条连播内容：队首出列，余队改挂到新条目名下（下一次连播从新 key 查——
     * 队列随窗滚动前移）；队列耗尽即摘除。无队列/已耗尽返回 null（调用方维持
     * 各自的现状：App 内浮窗定格 / 系统窗关窗）。
     */
    fun advanceFloatingQueue(currentKey: String): VideoItem? {
        val q = floatingQueues[currentKey] ?: return null
        val next = q.removeFirstOrNull() ?: run {
            floatingQueues.remove(currentKey)
            return null
        }
        if (q.isNotEmpty()) floatingQueues[next.key] = q
        floatingQueues.remove(currentKey)
        return next
    }

    // ---- 系统画中画（PiP）通道：MainActivity 进/出 PiP 时驱动，FloatingPlayerHost 注册 ----

    /**
     * 是否处于系统画中画（PiP）：MainActivity.onPictureInPictureModeChanged 置位。
     * Compose 订阅后悬浮层切"满窗纯视频"（藏全部 UI 控件/进度条/按钮），退出即复原。
     */
    val isInPipMode = mutableStateOf(false)

    /**
     * PiP 候选提供者（FloatingPlayerHost 注册；无悬浮窗时注销为 null）。
     * MainActivity.onUserLeaveHint 调它判断"有在播的悬浮视频窗"才进 PiP——
     * PiP 只服务视频；音频卡退后台继续走 MediaSession 通知栏路线，不进 PiP。
     */
    var pipCandidateProvider: (() -> VideoItem?)? = null

    /** 悬浮层注册的"暂停全部悬浮窗播放"（FloatingPlayerHost 注册；关掉 PiP 小窗=停播用） */
    var floatingPauser: (() -> Unit)? = null

    /** 关掉 PiP 小窗=停播：暂停全部悬浮窗播放（无注册/无悬浮窗时空转） */
    fun pauseFloatingPlayback() {
        floatingPauser?.invoke()
    }

    // ---- 后台行为通道（backgroundAction 用；由当前活跃页注册，离开时注销） ----

    /** 当前活跃页注册的"暂停当前播放"动作（FeedScreen 注册；无注册时 pauseAllPlayback 空转） */
    var currentPlayerPauser: (() -> Unit)? = null

    /** 当前活跃页注册的"恢复播放"动作（FeedScreen 注册；只恢复"被后台闸暂停的"播放，用户手动暂停的不动） */
    var currentPlayerResumer: (() -> Unit)? = null

    /** 当前活跃页注册的"当前条目"提供者（自动弹悬浮窗时取当前 VideoItem） */
    var currentItemProvider: (() -> VideoItem?)? = null

    /**
     * 当前活跃页注册的"保存当前播放进度"动作（FeedScreen 注册；记忆播放用）。
     * MainActivity.onStop 无条件调用——杀进程前的最后落盘机会，实现侧当场同步写盘。
     */
    var currentProgressSaver: (() -> Unit)? = null

    /** 记忆播放：退后台时落盘当前条进度（无注册时空转；异常不外抛，不干扰后台分派） */
    fun saveCurrentProgress() {
        runCatching { currentProgressSaver?.invoke() }
    }

    /** 后台行为=暂停：暂停当前页正在播的内容 */
    fun pauseAllPlayback() {
        currentPlayerPauser?.invoke()
    }

    /** 回前台自动续播（仅后台行为=暂停 时调用）：恢复"被后台闸暂停的"播放；无注册/无事可恢复时空转 */
    fun resumeAllPlayback() {
        currentPlayerResumer?.invoke()
    }

    /** 后台行为=自动弹悬浮窗：取当前条目进悬浮栈，并暂停页内播放避免双重出声。
     *  仅视频可弹（mediaType=0）：图文/音频弹悬浮窗只会得到无内容的空白窗（B13 残留路径守卫） */
    fun popoutCurrent() {
        val item = currentItemProvider?.invoke() ?: return
        if (item.mediaType != 0) return
        addFloating(item)
        currentPlayerPauser?.invoke()
    }

    /** 自定义分屏片单：Feed 里"加入分屏"攒的（也可含本地文件），进分屏时优先播它 */
    val gridPlaylist = mutableStateListOf<VideoItem>()

    fun addToGrid(item: VideoItem) {
        if (gridPlaylist.none { it.key == item.key }) gridPlaylist.add(item)
    }

    fun removeFromGrid(item: VideoItem) {
        gridPlaylist.removeAll { it.key == item.key }
    }

    /** 分屏"全屏单播"跳转通道：格子里的内容 key，Feed 读取后定位到该条 */
    var pendingJumpKey = mutableStateOf<String?>(null)
        private set

    fun requestFeedJump(key: String) {
        pendingJumpKey.value = key
    }

    fun consumeJump() {
        pendingJumpKey.value = null
    }

    /** 应用内填写的凭证优先，其次才是编译期 BuildConfig */
    private fun effectiveApiId(): Int = prefs.apiId.takeIf { it != 0 } ?: BuildConfig.API_ID
    private fun effectiveApiHash(): String = prefs.apiHash.ifBlank { BuildConfig.API_HASH }

    /** 设置页预填用：生效中的凭证（prefs 优先，BuildConfig 兜底） */
    val displayApiId: Int get() = effectiveApiId()
    val displayApiHash: String get() = effectiveApiHash()

    fun hasApiCredentials(): Boolean = effectiveApiId() != 0 && effectiveApiHash().isNotBlank()

    private fun createDefaultGateway(): TdGateway =
        if (hasApiCredentials()) realGateway() else MockTdGateway()

    private fun realGateway(): TdGateway = TdLibGateway(
        context = context,
        scope = scope,
        apiId = effectiveApiId(),
        apiHash = effectiveApiHash(),
        proxyEnabled = prefs.proxyEnabled,
        proxyHost = prefs.proxyHost,
        proxyPort = prefs.proxyPort,
        // 存储水位：超过限额自动清最旧视频缓存（0=不启用）
        cacheLimitBytes = prefs.cacheLimitGb * 1024L * 1024L * 1024L,
    ).apply {
        // 扫码登录兜底锚点：TDLib 无原生"取消扫码"接口，正常路径是扫码等待态下直接
        // 提交手机号（TDLib 隐式取消扫码查询）；万一被本版 TDLib 拒绝，网关回调这里
        // close+重建客户端，干净回到手机号登录起点（正常版本永远走不到这里）
        recreateHook = { useRealGateway() }
        // R244 代理自动检测落盘：网关首启探测到本地代理软件端口后写回 prefs——
        // 下次启动直达（不再重探）、设置页同步显示生效值。治实锤②「app 走直连
        // 连不上」：用户机实测 proxy_enabled=false + 模拟器默认地址，自动检测
        // 会把它纠正为真实可用的本地代理并开启。
        proxyPersistHook = { host, port ->
            prefs.proxyHost = host
            prefs.proxyPort = port
            prefs.proxyEnabled = true
        }
    }

    fun useMockGateway() {
        prefs.lastMode = "demo"
        _demoMode.value = true
        gatewayEnsured = true // 显式选择优先于 ensureGateway 的惰性创建
        closeOldGateway()
        _gateway.value = MockTdGateway()
    }

    fun useRealGateway() {
        prefs.lastMode = "real"
        _demoMode.value = false
        gatewayEnsured = true // 显式选择优先于 ensureGateway 的惰性创建
        closeOldGateway()
        _gateway.value = realGateway()
    }

    /** 换网关前关掉旧的 TDLib 客户端（防旧实例泄漏 + 双会话） */
    private fun closeOldGateway() {
        (_gateway.value as? TdLibGateway)?.close()
    }

    /** 后台行为=暂停 且 App 在后台时，音频卡（AudioCard 自管播放器）也要停。
     *  MainActivity.onStop/onStart 置位；Compose 状态，AudioCard 直接订阅 */
    val audioCardsPaused = mutableStateOf(false)

    /** 当前活跃页注册的"当前帧提供者"（分享卡片抓帧用；VideoPage 在播时注册） */
    var currentFrameProvider: (() -> android.graphics.Bitmap?)? = null

    /** 从 Feed 页打开设置（修 bug 前只翻转 demoMode，有凭证时路由不切换，等于没反应） */
    fun openConfig() {
        _showSettings.value = true
    }

    /** 保存配置并切换到真实模式。
     *  凭证隐藏后：apiId==0/空 apiHash = 用户只想改代理——保留现有凭证不覆盖；
     *  两栏都填了才视为要换凭证（登录页首次配置路径不受影响，那里两栏必填） */
    fun applyConfigAndConnect(apiId: Int, apiHash: String, proxyEnabled: Boolean, proxyHost: String, proxyPort: Int) {
        if (apiId != 0 && apiHash.isNotBlank()) {
            prefs.apiId = apiId
            prefs.apiHash = apiHash
        }
        prefs.proxyEnabled = proxyEnabled
        prefs.proxyHost = proxyHost
        prefs.proxyPort = proxyPort
        useRealGateway()
    }
}

/**
 * H6 轻量占位网关：TDLib 客户端创建前的空壳——状态恒 Loading（UI 走原有
 * GatewayState.Loading 路由兜底），所有操作空转/返回空，不碰 libtdjni。
 * 只应存活一瞬：MainActivity setContent 后的 LaunchedEffect(Unit) 随即调
 * AppContainer.ensureGateway() 把它替换掉。
 */
private class PlaceholderGateway : TdGateway {
    override val state: StateFlow<GatewayState> = MutableStateFlow(GatewayState.Loading)
    override val connectionState: StateFlow<String> = MutableStateFlow("连接中")

    /** R244 代理决策提示（占位网关无代理概念，恒 null） */
    override val proxyHint: StateFlow<String?> = MutableStateFlow(null)
    override var includeCommentResources: Boolean = true
    override suspend fun submitPhone(phone: String) {}
    override suspend fun submitCode(code: String) {}
    override suspend fun submitPassword(password: String) {}
    override suspend fun listChannels(): List<ChannelBrief> = emptyList()
    override suspend fun fetchVideos(request: FeedRequest, cursor: String?, limit: Int): FeedPage =
        FeedPage(emptyList(), null)
    override suspend fun ensurePlayable(item: VideoItem, priority: Int): String =
        throw IllegalStateException("网关尚未创建（占位实例）")
}
