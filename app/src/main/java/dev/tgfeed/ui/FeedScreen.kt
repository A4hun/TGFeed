package dev.tgfeed.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import dev.tgfeed.core.feed.ChannelFeedSource
import dev.tgfeed.core.feed.FeedFilter
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.feed.FeedViewModelFactory
import dev.tgfeed.core.feed.SearchFeedSource
// R299 B1 听单 VM 代理（FeedListenQueuePart 扩展件；FeedViewModel.kt 本体在
// R292 在飞面，本任务零触碰）
import dev.tgfeed.core.feed.clearListenQueue
import dev.tgfeed.core.feed.enqueueListen
import dev.tgfeed.core.feed.jumpListen
import dev.tgfeed.core.feed.listenQueueItems
import dev.tgfeed.core.feed.playNextInListen
import dev.tgfeed.core.feed.removeFromListen
import dev.tgfeed.core.feed.saveListenQueueAsPlaylistImpl
// R299 B3 漫画模式 VM 代理（FeedComicPart 扩展件）
import dev.tgfeed.core.feed.comicChapterNeighborImpl
import dev.tgfeed.core.feed.comicProgressOf
import dev.tgfeed.core.feed.saveComicProgress
import dev.tgfeed.core.onthisday.OnThisDayMemory
import dev.tgfeed.core.onthisday.onThisDayHistoryMemory
import dev.tgfeed.core.playback.PlayerPool
import dev.tgfeed.core.playback.PreloadScheduler
import dev.tgfeed.core.store.LocalStore
import dev.tgfeed.core.td.PublicChannelResolve
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.ui.components.FeedTopBarOverlay
import dev.tgfeed.ui.components.OfflineHintPill
import dev.tgfeed.ui.components.VideoPage
import dev.tgfeed.ui.components.formatSpeed
import dev.tgfeed.ui.components.kit.avoidBottomBar
import dev.tgfeed.ui.components.kit.mediaRailHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZoneId

/** 倍速档位：循环 1x → 1.5x → 2x */
private val SPEED_STEPS = floatArrayOf(1f, 1.5f, 2f)

// 摇一摇判定参数：相邻两帧加速度模长差超阈值记一次尖峰；
// 500ms 窗口内凑满 3 个尖峰 = 摇一摇；触发后 1.5s 防抖
private const val SHAKE_DELTA_THRESHOLD = 25f
private const val SHAKE_WINDOW_MS = 500L
private const val SHAKE_SPIKE_COUNT = 3
private const val SHAKE_DEBOUNCE_MS = 1500L

/** 抖音式沉浸式 Feed：全屏竖滑 + 渐变顶栏浮层 + 手势互动 */
@Composable
fun FeedScreen(
    gateway: TdGateway,
    prefs: dev.tgfeed.core.prefs.AppPrefs,
    onOpenConfig: () -> Unit,
    onGridMode: () -> Unit,
    onPopout: (dev.tgfeed.core.td.VideoItem) -> Unit,
    onAddToGrid: (dev.tgfeed.core.td.VideoItem) -> Unit,
    // 分屏"全屏单播"跳转：待定位条目 key；定位成功后回调消费掉
    pendingJumpKey: String? = null,
    onJumpHandled: () -> Unit = {},
    // 频道主页入口（CHANNEL-HOME-FINAL P0）：视频页右栏头像点击，外层（App 容器路由）开频道主页
    onOpenChannelHome: (dev.tgfeed.core.td.VideoItem) -> Unit = {},
    // R202 TL-4 事件考古（功能面板「看同期」格，R215 ④ 更名）：视频/图/音条目以发布时刻
    // 为锚开时光机 ±48h 全网横截面——外层（MainActivity 容器路由）开时光机页（Event 模式）
    onOpenTimeMachine: (dev.tgfeed.core.td.VideoItem) -> Unit = {},
    // R222 #话题双选弹层「时间轴探索」：caption #标签点击整合入口的新路径——外层
    //（MainActivity 容器路由）开 hashtag 时间轴页（引擎=R205 事件研究器复用）
    onOpenHashtagTimeline: (String) -> Unit = {},
    // R233 口味星图二期（长按功能面板「在星图中查看」格）：视频/图/音条目跳进
    // 星图并运镜飞向该条目所属恒星——外层（MainActivity 容器路由）写一次性运镜
    // 意图（openTasteMapFly 同 timeMachineIntent 模式）
    onOpenTasteMap: (dev.tgfeed.core.td.VideoItem) -> Unit = {},
    // R215 ② feed 那年今日卡：第 3~5 条位置注入「N 年前的今天，你看过这些」特殊卡；
    // 点缩略 → 宿主编排「那年今日播放流」（一次性源=整批记忆条目，revive+切源+定位
    // 跳转，OnThisDayScreen onPlay 同款成熟链；L1 源栈返回键回正常 feed）。默认空
    // 实现，调用方不接线也可编译
    onPlayOnThisDay: (item: dev.tgfeed.core.td.VideoItem, batch: List<dev.tgfeed.core.td.VideoItem>, label: String) -> Unit = { _, _, _ -> },
    // caption t.me 频道链接入口：应用内解析出公开频道（未加入也能浏览）后开频道主页，
    // 与 onOpenChannelHome 同一覆盖层路由（主页里有"追更"入口）；参数是已解析的频道摘要
    onOpenChannelBrief: (dev.tgfeed.core.td.ChannelBrief) -> Unit = {},
    // 频道主页覆盖层是否开着（MainActivity 注入）：开着时暂停 Feed 播放（修"进主页
    // Feed 声音不停"），关掉时恢复——主页是覆盖层路由，Feed 保持组合不会自行停声
    channelHomeOpen: Boolean = false,
) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val vm: FeedViewModel = viewModel(factory = FeedViewModelFactory(gateway, app), key = "gw" + System.identityHashCode(gateway))
    val items by vm.items.collectAsState()
    val channels by vm.channels.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    // R324 离线降级三流：离线态（NetworkWatch 单源经 VM 镜像）+ feed 数据时点两源
    //（快照落盘时刻/本会话最近到货时刻——banner「N 小时前的快照」的诚实原料）
    val networkOnline by vm.networkOnline.collectAsState()
    val snapshotSavedAtMs by vm.snapshotSavedAtMs.collectAsState()
    val lastFreshAtMs by vm.lastFreshAtMs.collectAsState()
    val likes by vm.likes.collectAsState()
    val favorites by vm.favorites.collectAsState()
    // R432 重组收敛：下载表只 collect 不在此取值——值参链（本层 downloads[key] →
    // VideoPage/PhotoCard/AudioCard → ActionRail）会让下载 1Hz 进度更新把整页
    // （含本处 pager 循环）拖进重组。State 本体下传，卡片叶子内按 item.key 现查。
    val downloadsState = vm.downloads.collectAsState()
    val subscriptions by vm.subscriptions.collectAsState()
    // R66 追更红点：任一订阅频道有未看新内容 → 顶栏"订阅追更"chip 叠小红点
    val followHasNew by vm.followHasNew.collectAsState()
    // R68 频道卡元数据：源面板"直达频道"段的鲜活信号行 + 最近看过优先排序
    val channelCardMeta by vm.channelCardMeta.collectAsState()
    // R191 手动钉住深索引：源面板频道行「深度索引」按钮的行状态台账（钉/爬/✓）
    val manualIndexRows by vm.manualIndexRows.collectAsState()
    val autoAdvance by vm.autoAdvance.collectAsState()
    val searchHistory by vm.searchHistory.collectAsState()
    // 当前落定页下标（onPage 回报）：B2 顶栏显隐要按"当前页媒体类型"判定
    val currentIndex by vm.currentIndex.collectAsState()
    // R144 频道 Stories 快拍条：真实网关才有活跃快拍流（演示模式无 story，条不显示不占位）
    val realGateway = gateway as? dev.tgfeed.core.td.TdLibGateway
    val storiesFlow = remember(realGateway) {
        realGateway?.chatActiveStories
            ?: kotlinx.coroutines.flow.MutableStateFlow<Map<Long, org.drinkless.tdlib.TdApi.ChatActiveStories>>(emptyMap())
    }
    val storiesMap by storiesFlow.collectAsState()
    val storyWatchedFlow = remember(realGateway) {
        realGateway?.storyWatched
            ?: kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>())
    }
    val storyWatched by storyWatchedFlow.collectAsState()
    // 渲染单元投影（TDLib order 降序；watched 变化即重投影——连播看一条，条上红环实时转灰）
    val storyBriefs = remember(realGateway, storiesMap, storyWatched) {
        realGateway?.chatStoriesBriefs() ?: emptyList()
    }
    // 全量快照：频道列表到位后拉（网关侧 10 分钟 TTL 闸防重跑）。延 20s 让路——
    // TDLib 授权后的头几十秒请求队列全在喂首页首拉/媒体窗（几百频道账号实测会把
    // 快拍快照排队排到数十秒级），快拍是锦上添花，晚 20s 用户无感（收割循环同款手法）
    LaunchedEffect(realGateway, channels) {
        if (realGateway != null && channels.isNotEmpty()) {
            kotlinx.coroutines.delay(20_000L)
            runCatching { realGateway.refreshChatActiveStories(channels) }
        }
    }
    // 连播查看器（打开时刻冻结 briefs 快照——连播中红环转灰不重排连播列表；关闭时只翻开关，
    // briefs 保留供退出动画帧继续组合）
    var storiesViewerOpen by remember { mutableStateOf(false) }
    var storiesViewerBriefs by remember { mutableStateOf<List<dev.tgfeed.core.td.ChannelStoriesBrief>>(emptyList()) }
    var storiesViewerChatId by remember { mutableStateOf(0L) }
    val openStories: (dev.tgfeed.core.td.ChannelStoriesBrief) -> Unit = { brief ->
        storiesViewerBriefs = storyBriefs
        storiesViewerChatId = brief.chatId
        storiesViewerOpen = true
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playerPool = remember { PlayerPool(context) }
    val preloader = remember { PreloadScheduler(gateway, scope) }
    // R180 前置：AppContainer 引用（共享音频播放器/台前信号/共存开关/音频卡暂停位都在容器上；
    // 原在下方音频闸处声明，播放器上提后需要在更早处取用，声明随之提前）
    val appContainer = (context.applicationContext as dev.tgfeed.App).container

    // （锁屏/耳机"下一条"接线挪到 pagerState 声明之后，见下）

    // 全局播放偏好（跨页面共享）：静音 / 倍速（float 直存，支持面板任意档位）/ 填充模式
    var muted by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1f) }
    // 填充模式：默认填满（抖音式 Crop），面板可切"完整"留黑边
    var fillToScreen by remember { mutableStateOf(true) }
    // 清晰度偏好镜像：prefs 是持久层，本状态是重组扳机——换档时写 prefs 同时翻状态，
    // 当前条 VideoPage 立刻收到新 qualityHeight 走热切换链（原只写 prefs，热切换断链）
    var qualityHeight by remember { mutableStateOf(prefs.qualityHeight) }
    // 循环单条（功能面板 Switch）
    var loopOne by remember { mutableStateOf(false) }
    // 筛选面板 + 当前筛选条件
    var showFilter by remember { mutableStateOf(false) }
    // 机器人搜索进行中标记：空态区"机器人搜索"按钮的防重入 + 文案切换（最长 12 秒）
    var botSearching by remember { mutableStateOf(false) }
    val filter by vm.filter.collectAsState()
    // 内容分区 tab（全部/视频/图文/音频）
    val mediaTypeTab by vm.mediaTypeTab.collectAsState()
    // 弹幕：item.key → 评论列表（按需加载，网关侧另有一层缓存）。
    // DANMAKU-TG 任务 2：元素带真实发布时间戳/回复标记（dev.tgfeed.core.td.DanmakuItem）
    // 总开关镜像成状态：播放页操作栏弹幕钮点击即写 prefs 并翻转本状态，弹幕即时开/关
    var danmakuOn by remember { mutableStateOf(prefs.danmakuEnabled) }
    var commentsMap by remember { mutableStateOf(mapOf<String, List<dev.tgfeed.core.td.DanmakuItem>>()) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    // R201 分享链路公开链接统一解析口：原「视频面板复制链接 / 右轨直跳系统分享 /
    // 音频底排复制链接 / 图音面板复制链接 / 图音面板分享卡片」五处各写一份的网关
    // 分支收口成一处（TDLib/Mock 双实现各走 getMessageLink，其余 null——分支语义
    // 与原五处逐字节一致）。私有频道/无权限/解析失败 → null（菜单态据此禁用）
    suspend fun messageLinkOf(v: dev.tgfeed.core.td.VideoItem): String? = when (gateway) {
        is dev.tgfeed.core.td.TdLibGateway -> gateway.getMessageLink(v)
        is dev.tgfeed.core.td.MockTdGateway -> gateway.getMessageLink(v)
        else -> null
    }

    // 顶栏显示的当前源标题（R182 上收 VM：源历史栈弹回时随快照回写标题——本地态
    // 外够不着；其余 23 处赋值点照旧，赋值/重组语义与原本地态一致）
    var sourceTitle by vm.sourceTitle
    // R154 关注流在播判定：当前源是 FollowingFeedSource 时，顶栏"关注"tab 高亮、
    // 单击 = 刷新一批。切源必伴随列表清空→新内容到达的重组，组合期现读 vm.source
    // 即可（pager 区 remember { vm.source } 同款手法）
    val followingActive = vm.source is dev.tgfeed.core.feed.FollowingFeedSource
    // R157 推荐流在播判定：顶栏"推荐"tab 的高亮/在播单击刷新依据，与"关注"tab
    // 平级对称。聚合推荐流有两个实现（普通/加权混排），都算推荐（与 VM 内部
    // 判定口径一致）；组合期现读 vm.source，同上的重组手法
    val aggregateActive = vm.source is dev.tgfeed.core.feed.AggregateFeedSource ||
        vm.source is dev.tgfeed.core.feed.WeightedAggregateFeedSource
    // 频道合集管理弹窗
    var showCollections by remember { mutableStateOf(false) }
    // 片单选择对话框目标（功能面板"加入片单"经 LocalAddToPlaylist 穿透上来；非空即弹选择器）
    var playlistPickTarget by remember { mutableStateOf<dev.tgfeed.core.td.VideoItem?>(null) }
    // 转发/翻译动作层（包着网关的友好封装）
    val feedActions = remember { dev.tgfeed.core.feed.FeedActions(gateway) }
    // 翻译结果缓存：item.key → 译文
    var translations by remember { mutableStateOf(mapOf<String, String>()) }

    // 已订阅频道 id 集（顶栏与条目信息区"追"快捷钮共用）
    val subscribedIds = subscriptions.mapTo(HashSet()) { it.id }
    // 频道真头像加载器（顶栏与条目信息区共用；网关侧带进程内缓存，Mock 返回 null 回落字母圆）
    val avatarLoader: suspend (Long) -> ByteArray? = remember(gateway) {
        { chatId ->
            when (gateway) {
                is dev.tgfeed.core.td.TdLibGateway -> gateway.getChannelAvatar(chatId)
                is dev.tgfeed.core.td.MockTdGateway -> gateway.getChannelAvatar(chatId)
                else -> null
            }
        }
    }
    // caption 链接统一出口：
    //  - https://t.me/<name> 频道链接 → 应用内解析（公开频道未加入也能浏览）→ 开频道主页；
    //    用户名不存在/已失效、网络错误一律 Toast 反馈（修"点未加入频道毫无反应"——
    //    原实现把 t.me 链接一律抛系统浏览器，官方客户端又拒开受限/失效频道，两头落空）；
    //    t.me/+ 邀请、t.me/c/ 私有内部链 → Toast 提示"私有频道需要邀请"
    //  - 其余 http(s) 链接维持系统浏览器
    val openExternalLink: (String) -> Unit = { url ->
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)),
            )
        }.onFailure {
            // 原实现静默吞 ActivityNotFoundException——无浏览器/TG 可接球时同样"毫无反应"
            android.widget.Toast.makeText(context, "没有能打开该链接的应用", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val openCaptionLink: (String) -> Unit = { url ->
        val tgTarget = parseTmeTarget(url)
        val tgGateway = gateway as? dev.tgfeed.core.td.TdLibGateway
        when {
            // 非 t.me 链接 / 非用户名形态（addlist 合集等）→ 系统浏览器
            tgTarget == null -> openExternalLink(url)
            // 邀请链接 / 私有频道内部链：无公开用户名可解析，给明确提示不装死
            tgTarget.inviteOnly -> android.widget.Toast.makeText(
                context,
                "私有频道需要邀请链接，请先在 Telegram 中加入",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            // 演示模式（Mock 网关）无公开解析能力，回落浏览器
            tgGateway == null -> openExternalLink(url)
            else -> scope.launch {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    tgGateway.resolvePublicChannel(tgTarget.name)
                }
                when (result) {
                    // 公开频道（含未加入）→ 频道主页覆盖层（内有"追更"订阅入口）
                    is PublicChannelResolve.Channel -> onOpenChannelBrief(result.brief)
                    // 公开用户名存在但不是频道（用户/机器人/群）→ 不拦截，回落浏览器
                    PublicChannelResolve.NotChannel -> openExternalLink(url)
                    PublicChannelResolve.NotFound -> android.widget.Toast.makeText(
                        context, "频道不存在或已失效", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    is PublicChannelResolve.Failed -> android.widget.Toast.makeText(
                        context, "频道暂时无法打开，请稍后重试", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }
    // @提及：用户意图是开频道（不是全文搜索）——解析成频道直接进它的 Feed
    val openCaptionMention: (String) -> Unit = { name ->
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val brief = (gateway as? dev.tgfeed.core.td.TdLibGateway)
                ?.let { runCatching { it.resolveChannelBrief(name) }.getOrNull() }
            if (brief != null) {
                sourceTitle = brief.title
                vm.switchSource(ChannelFeedSource(gateway, brief.id))
            } else {
                // 解析不到（不是频道/不存在）回落搜索
                sourceTitle = "搜索：@$name"
                vm.switchSource(SearchFeedSource(gateway, name))
            }
        }
    }
    // 顶栏显隐：当前页清屏/全屏时隐藏顶栏（修"清屏顶栏残留"）
    var hideTopBar by remember { mutableStateOf(false) }
    // R174 捏合清屏连续映射：当前页 chrome α（1=全显）。顶栏+快拍条以
    // graphicsLayer α 承接页内上抛的连续渐隐（跟手）；α=0 时 cleanMode 必已
    // 置位 → hideTopBar=true → 整块退组合（旧 hidden 语义不变，兼容无 α 的调用方）
    var feedChromeAlpha by remember { mutableStateOf(1f) }
    // 源面板开窗镜像（FeedTopBarOverlay 上抛）：true 时藏快拍条（快拍条在顶栏后声明，
    // 会浮在面板 scrim 之上破坏模态）+ 计入底栏沉浸隐藏信号（模态盖顶期间底栏退场）
    var sourceSheetOpen by remember { mutableStateOf(false) }

    // R141 手气不错/盲盒流接线：面板（AppSheet 双选项卡）与开场卡（流头"本轮关键词"卡）
    // 的显隐；startInlineBlindBox = 开一轮"抽一串内容"（词现场摇号，标题/开场卡全程可见）
    var showLuckyPicker by remember { mutableStateOf(false) }
    var luckyCardVisible by remember { mutableStateOf(false) }

    // ---------------- R215 ② feed 那年今日卡（每天至多注入一张） ----------------
    // 探测闸：本会话只探一次（等 feed 长到注入位之下仍有内容再探——卡要有上下文才像
    // 「刷着刷着撞见回忆」）；prefs 幂等闸（lastOnThisDayCardDate=今天 → 当天已注入
    // 不再注）；读到记忆才落闸（无货不落，口径见 AppPrefs KDoc）。
    // 卡只在**注入时所在源**显示（source 实例同一性判定）：切源（进频道/搜索/盲盒流）
    // 即隐藏，popSourceBack 弹回原源重现——「刷完回正常 feed」的诚实闭环
    var onThisDayCard by remember { mutableStateOf<OnThisDayFeedCard?>(null) }
    // 屏侧只读 store（WatchHistoryScreen 先例：自建实例仅用于读，写归 VM 单写入方）
    val onThisDayStore = remember { LocalStore(app) }
    // 键必须稳定（Unit）：探一次只跑一轮——曾用 items.size 当键，翻页把列表从 20 加到
    // 40 的瞬间效果重启、协程在 IO 挂起点被取消（实机探针实锤：只有「探测」行没有
    // 「结果」行）；改 snapshotFlow 内部等位（feed 长过注入位才探），键永不变
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { items.size }.first { it > ON_THIS_DAY_FEED_CARD_PAGE + 1 }
        val today = LocalDate.now()
        if (prefs.lastOnThisDayCardDate == today.toString()) return@LaunchedEffect
        val zone = ZoneId.systemDefault()
        // R215 实机事件级探针（纪律§四：存在性断言不靠读码）：探测触发/记忆结果/闸位
        android.util.Log.i(
            "R215",
            "那年今日卡探测: items=${items.size} 闸=${prefs.lastOnThisDayCardDate} today=$today",
        )
        val memory: OnThisDayMemory? = withContext(Dispatchers.IO) {
            runCatching {
                val tsMap = onThisDayStore.loadHistory().toMap()
                if (tsMap.isEmpty()) null
                else onThisDayHistoryMemory(
                    onThisDayStore.loadHistoryItems().mapNotNull { it to (tsMap[it.key] ?: 0L) },
                    today, zone,
                )
            }.getOrNull()
        }
        android.util.Log.i(
            "R215",
            "那年今日卡记忆结果: ${memory?.let { "yearsAgo=${it.yearsAgo} items=${it.items.size}" } ?: "null"}",
        )
        if (memory != null) {
            onThisDayCard = OnThisDayFeedCard(memory = memory, hostSource = vm.source)
            prefs.lastOnThisDayCardDate = today.toString()
            android.util.Log.i("R215", "那年今日卡成卡: 注入位=$ON_THIS_DAY_FEED_CARD_PAGE hostSource=${vm.source.javaClass.simpleName}")
        }
    }

    // R141 盲盒流在播判定：sourceTitle 前缀承载（全 App 每个源切换点都会改写
    // sourceTitle，天然免维护"切走即清标记"；沿用搜索空态对 sourceTitle 的
    // 既有前缀判定手法）。null = 当前不是盲盒流
    val blindKeyword: String? =
        sourceTitle.takeIf { it.startsWith("盲盒流·") }?.removePrefix("盲盒流·")

    // R141 开一轮盲盒流：null = 现场随机摇号（避开当前词，保证"摇一把"真换词）。
    // 演示模式（Mock 网关）没有 inline 链路，如实提示不可用
    val startInlineBlindBox: (String?) -> Unit = { q ->
        val realGateway = gateway as? dev.tgfeed.core.td.TdLibGateway
        if (realGateway == null) {
            android.widget.Toast.makeText(
                context,
                "盲盒流需要真实 Telegram 登录（演示模式不可用）",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        } else {
            val word = q ?: rollBlindWordExcluding(blindKeyword)
            sourceTitle = "盲盒流·$word"
            luckyCardVisible = true // 新一轮：开场卡复位到流头
            vm.switchSource(dev.tgfeed.core.feed.InlineBotFeedSource(realGateway, word))
        }
    }

    // R141 摇一把（换词重抽）：显式按钮的落点。换新词 + 新源重开一轮（开场卡随之复位）；
    // 原"双击标题刷新"（reset：同词换一批）保留为普通刷新，不再是盲盒核心机关的唯一入口
    val shakeBlindBox: () -> Unit = { startInlineBlindBox(null) }

    // 旋转修复：方向变化触发自动进/退全屏时，VideoPage 的清屏/全屏上报可能丢失，
    // hideTopBar 会卡在 true 导致顶栏消失 —— 方向一变就强制恢复顶栏（α 一并复位）
    val orientation = LocalConfiguration.current.orientation
    LaunchedEffect(orientation) {
        hideTopBar = false
        feedChromeAlpha = 1f
    }
    // 相似频道弹窗
    var showSimilar by remember { mutableStateOf(false) }
    val similarChannels by vm.similarChannels.collectAsState()
    val similarLoading by vm.similarLoading.collectAsState()
    // TG 广场快照（全站在看/点赞/弹幕）
    val plaza by vm.plaza.collectAsState()
    // 弹幕发送：目标条目（非空即弹输入框）
    var danmakuTarget by remember { mutableStateOf<dev.tgfeed.core.td.VideoItem?>(null) }
    // R205 TL-9 弹幕时间旅行：回放面板目标条目（非空即弹 DanmakuTimelineSheet——
    // 长按面板「弹幕回放」格置位；null 条目进入时 sheet 自身持「全站弹幕流」态）
    var danmakuTimelineTarget by remember { mutableStateOf<dev.tgfeed.core.td.VideoItem?>(null) }
    // 图文/音频卡长按功能面板：目标条目（非空即弹 VideoActionSheet，与视频卡同一块面板）
    var mediaPanelTarget by remember { mutableStateOf<dev.tgfeed.core.td.VideoItem?>(null) }
    // R201 分享枢纽：目标条目（非空即弹 ShareHubSheet 小菜单）。三个分享入口
    //（视频右轨分享钮 / AudioCard 底排分享钮 / 图音长按面板「分享」格）统一收口到
    // 这一个状态——菜单内三选一（复制链接/分享到其他应用/分享卡片），链体见下方枢纽接线
    var shareHubTarget by remember { mutableStateOf<dev.tgfeed.core.td.VideoItem?>(null) }
    // R227 画面调整枢纽：目标条目（非空即弹 FrameAdjustHubSheet 小菜单）。视频
    // 长按面板「画面调整」格（原「旋转」+「镜像翻转」两格收编）收口到这一个状态
    //——菜单内四选一（向左旋转/向右旋转/左右镜像/恢复默认）；面板自持
    // FrameAdjustStore 读写（姿态单一真源，无链体接线——与分享枢纽的差别）
    var frameAdjustTarget by remember { mutableStateOf<dev.tgfeed.core.td.VideoItem?>(null) }
    // R222 #话题双选弹层：目标标签（非空即弹 HashtagChoiceSheet）。caption #标签
    // 点击（视频卡/图文卡两条链）统一收口到这一个状态——菜单内两选一（时间轴探索=
    // 新路径开 hashtag 时间轴页 / 搜索播放=原路径混合搜索直接开刷），链体见下方接线
    var hashtagChoiceTag by remember { mutableStateOf<String?>(null) }
    // R301 星图舷窗（design-dual-form §2 落地）：目标条目（非空即弹 GalaxyPortholeSheet
    // 半屏舷窗——「你在这里」坐标行 + 视频/图音长按格「在星图中查看」两入口统一收口
    // 到这一个状态；跳图不再是第一动作（终审批准的行为变更），舷窗内首键「进入星图」
    // /邻星 pill 经 onEnterGalaxy → 下方 warpTarget 出场动画后再走真实跳图链）
    var portholeTarget by remember { mutableStateOf<dev.tgfeed.core.td.VideoItem?>(null) }
    // R302 Lv7 舷窗门（design-rpg-loop §2.2「星图舷窗=等级授予」终审口径）：星辉
    // ≥Lv7=开舷窗（R301 现行为）；未到级=onOpenTasteMap 旧跳图链原样（不回收——
    // 跳图是既有行为；等级=本地账本三源重算一次装载，零 IO 常驻零网络）
    var starlightLevel by remember { mutableStateOf(1) }
    LaunchedEffect(Unit) {
        starlightLevel = withContext(Dispatchers.IO) {
            runCatching {
                dev.tgfeed.core.tastemap.TasteStarlight.loadProgress(
                    java.io.File(context.filesDir, "galaxy"),
                ).level
            }.getOrDefault(1)
        }
    }
    val openGalaxyEntry: (dev.tgfeed.core.td.VideoItem) -> Unit = { v ->
        if (dev.tgfeed.core.tastemap.TasteStarlight.isUnlocked(starlightLevel, "porthole")) {
            portholeTarget = v
        } else {
            onOpenTasteMap(v) // 未到 Lv7=旧跳图链（终审「等级授予」口径）
        }
    }
    // R301 错觉拼接运镜 A 案（feed 侧出场）：封面缩成光点+白闪藏接缝——
    // （封面源条目, 目标 chatId）；非空即播 600ms 叠加层，白闪峰值帧触发真实跳图
    // （openTasteMapFly 链原样——R254 缓存秒开是接缝成立的另一半）
    var warpTarget by remember { mutableStateOf<Pair<dev.tgfeed.core.td.VideoItem, Long>?>(null) }
    // R301 A 案到达收束（星图→feed 方向）：tasteMapReturnPending 置位=星图源跳转
    //（试吃/尘埃/内容流——R182/R269 既有旗标，只读零写），feed 首帧从白闪收束 300ms
    // （一次性：remember 组合期取初值，跳图进 feed=新组合即播；普通冷启 flag=false
    // 零开销零视觉变化）
    val galaxyArriveFlash = remember {
        androidx.compose.animation.core.Animatable(
            if (appContainer.tasteMapReturnPending.value) 1f else 0f,
        )
    }
    LaunchedEffect(Unit) {
        if (galaxyArriveFlash.value > 0f) {
            galaxyArriveFlash.animateTo(
                0f,
                androidx.compose.animation.core.tween(dev.tgfeed.ui.theme.MotionTokens.GalaxyArriveFlashMs),
            )
        }
    }
    // R204 DS-5 首发溯源：目标条目（非空即弹 FirstSeenSheet）。两入口收口到这
    // 一个状态——视频长按面板「首发溯源」格 / 图音长按面板「首发溯源」格；
    // 查证链（三级缓存+全表扫）在 vm.firstSeenFor 内收口
    var firstSeenTarget by remember { mutableStateOf<dev.tgfeed.core.td.VideoItem?>(null) }
    // 图/音面板"定时关闭"档位的本地镜像（仅用于 chips 高亮；真实计时状态由 vm 持有）
    var panelSleepLocal by remember { mutableStateOf<Int?>(null) }
    // R83 全屏动态模糊底衬：当前页上报的底衬图（播放中为当前帧小图，起播前为封面；
    // 异向/FIT 时铺满整个窗口，含限宽列外两侧）
    var backdropCover by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    // R160 加载态一次成型：底衬换主（跨条目切换）时 Crossfade 走 snap() 同帧切换、
    // 同条目内 live 帧更新走 300ms 短渐变——用"当前底衬归属哪条"区分两种换图：
    // owner key 变了 = 换主（新页进场，要求与列内占位同帧同图），否则 = R89 换帧
    var backdropOwnerKey by remember { mutableStateOf<String?>(null) }
    // R194①② 换图模式（BackdropTransition）：镜像帧=SNAP 视频式直换 / 换主与
    // live 首拍=SOFT 无黑谷软切 / 离散切换（源/tab）=THROUGH_DARK 暗场溶解
    //（架构师 16:55「两式过渡」+17:00「双镜像」终案）。替代旧 backdropSnap 布尔。
    var backdropMode by remember {
        mutableStateOf(dev.tgfeed.ui.components.BackdropTransition.SOFT)
    }
    // R194② 离散切换待决位：轨道切换冻结窗收口后的第一拍换图走暗场溶解
    //（「旧图暗下→新图从暗中亮起」，不做亮对亮直切）；翻页换主不受影响
    var backdropDiscretePending by remember { mutableStateOf(false) }
    // R194⑥ 顶栏实测底缘（架构师布局终案：媒体顶锚贴 tab 条小间距）——onSizeChanged
    // 测得即回填（顶栏布局恒定无展开态，一次定型）；供 VideoPage/PhotoCard 的
    // 媒体带顶锚预留计算（视频勿融入底部三导航钮的悬停式几何）
    var topBarBottomPx by remember { mutableStateOf(0) }
    // R159b 起播一拍：当前底衬归属条目是否已进入 R89 live 实帧制度——本条首次把
    // 封面底衬换成 live 实帧的那一拍（liveRegimeStart）并入同帧 snap（背景换血与
    // 首帧上屏同拍合并成「占位→画面」的一部分）；换主时复位（新条目重新起制度）。
    // 判据并入 BackdropPolicy.isBackdropSnapSwitch（单测枚举）
    var backdropLiveAdopted by remember { mutableStateOf(false) }
    // R194② 轨道切换窗底衬冻结：切媒体 tab/换列的恢复窗内连环换主（旧页码条目→
    // resumeScroll 落位条目→封面→live 首拍，每拍全屏一换 = 用户实测「切 tab 背景
    // 疯狂闪烁，一口气加载到最新一帧」的机理链）——冻结窗内上报一律缓收（旧帧
    // 冻结在屏、零闪），窗收口用代次位让当前页重报一次最新帧、一次软切到位。
    // 窗长与驱动见 BackdropPolicy.TRACK_SWITCH_FREEZE_MS + 下方 LaunchedEffect(trackKey)
    var backdropFrozen by remember { mutableStateOf(false) }
    val backdropAdoptGen = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // 新手引导覆盖层（仅首启）
    var showCoach by remember { mutableStateOf(prefs.firstRunFeed) }

    LaunchedEffect(Unit) {
        vm.startPlazaPolling() // 广场轮询（内部按 plazaEnabled 总闸）
        vm.loadChannels()
        // R66：进 Feed 时重估追更红点 + 刷频道统计快照（索引只读聚合，便宜，不轮询）
        vm.refreshFollowSignal("进Feed")
        if (vm.items.value.isEmpty()) vm.loadMore()
    }

    // T0-5 探测环已删：筛选复位挪到 MainActivity 切源前做（vm.setFilter(FeedFilter())）。
    // 这个 300ms 轮询 refresh 会把已完成的跳转打回第 0 条（审计 TOP2 实锤）。

    // 后台行为通道：向容器注册"暂停当前页"与"当前条目"提供者（MainActivity.onStop 分派时调用）。
    // lambda 内现读 vm.items/vm.currentIndex，无需随翻页重注册；离开时先注销再释放播放器，
    // 避免 onStop 晚到时调到已释放的 playerPool（声明顺序先于下面的释放 DisposableEffect）

    // ---------------- 共享音频播放器（音乐播放器级：迷你播放条/锁屏的载体） ----------------
    // 全页唯一一台音频播放器（传给 AudioCard.sharedPlayer）：卡片划出销毁后音频继续放，
    // 底部迷你播放条接管展示与控制；单实例天然保证"同时只有一条音轨"（新音频接管即停旧）。
    // R180 上提到 AppContainer 进程级（原 remember 级 + dispose release）：
    // 需求②开档的背景听模式要求切页（FeedScreen dispose）后音频存活继续播；
    // 焦点策略（handleAudioFocus=共存开关取反）与后台闸登记在容器侧统一管理。
    // 音量均衡：与视频池同一挂点（NormalizingRenderersFactory 挂 LoudnessNormalizer），
    // attachTo 把 mediaId(=item.key，AudioCard 装填时已设置) 转接给归一器
    val audioPlayer = appContainer.sharedAudioPlayer
    // 迷你播放条/队列数据源（AudioCard 起播/暂停上报；卡片销毁后由下方播放器监听续报）
    val nowPlayingAudio by vm.nowPlayingAudio.collectAsState()
    val audioPlaying by vm.audioPlaying.collectAsState()
    // R299 B1：持久听单实况（队列 sheet 编辑段 + 迷你条「N 首」角标的数据源）
    val listenQueueItems by vm.listenQueueItems.collectAsState()
    // 播放队列 sheet 开关（迷你条右侧"队列"图标触发）
    var showAudioQueue by remember { mutableStateOf(false) }

    // R189 切走轨道停播（任务书「切走 tab 停播」的音频半边，R180 关档矩阵沿用）：
    // 切媒体 tab / 换列 = 离开当前内容场——关档（音视频不共存）音频暂停不清状态
    //（与 R180 ⑥「切页音频暂停+回页续」同族对齐）；开档 = 背景听豁免继续播。
    // 视频停播由列表换血自然发生（VideoPage dispose → 池 release，R112 先记账后
    // 释放）。回轨续播：AudioCard 共享模式接管效应（mediaId==key + isActive →
    // playWhenReady=true）自动续，位置随播放器实例保留。唯一调用口径见
    // executeRailAction / 顶栏双 tab / 媒体条三处（单一 choke 点不许散写）
    val pauseAudioOnTrackSwitch: () -> Unit = {
        if (!prefs.allowAudioVideoSimultaneous && audioPlayer.playWhenReady) {
            audioPlayer.pause()
            android.util.Log.i("NavGate", "R189 轨道切换：音频暂停（不清状态，回轨自动续播）")
        }
    }

    // 播放态桥：起播 → 进媒体会话（锁屏/耳机可控，镜像链 PlayerPool.notifyExternalActive）；
    // 出声态续报 VM（卡片销毁后无人上报的路径——焦点抢占暂停等——由此兜底）。
    // R299 B1：ENDED 仲裁已收口到进程级 ListenQueueController（随播放器建造注册，
    // 先于本监听——循环单条/队列连播/队列空回落三分支都在那里；本屏只留出声态续报）。
    DisposableEffect(audioPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) PlayerPool.notifyExternalActive(audioPlayer)
                vm.nowPlayingAudio.value?.let { vm.reportAudioPlaying(it, isPlaying) }
            }
        }
        audioPlayer.addListener(listener)
        onDispose { audioPlayer.removeListener(listener) }
    }

    // R299 B1：听单控制器的本屏语义回调接线（仲裁本体在控制器，FeedScreen dispose
    // 后连播照活——回调注销即回落控制器的纯后台语义）：
    //  loopOneProvider = 循环单条开关（本屏态）；onQueueEmptyEnded = 队列空播完回落
    // （现状：退会话+清在播）；nowPlayingSink = 队列开播上抛（迷你条/锁屏数据源）
    DisposableEffect(audioPlayer) {
        val queue = appContainer.listenQueueController
        queue.loopOneProvider = { loopOne }
        queue.onQueueEmptyEnded = {
            PlayerPool.notifyExternalActive(null)
            vm.clearNowPlayingAudio()
        }
        queue.nowPlayingSink = { item ->
            if (item != null) vm.jumpToAudio(item) else vm.clearNowPlayingAudio()
        }
        onDispose {
            queue.loopOneProvider = null
            queue.onQueueEmptyEnded = null
            queue.nowPlayingSink = null
        }
    }

    // 定时关闭对音频同样生效（G9：音频卡长按面板可设，恰是对音频价值最高的能力）：
    // 到点拉停共享音频播放器（视频路径由 VideoPage 自行 collect 暂停，互不重复）
    LaunchedEffect(audioPlayer) {
        vm.sleepNow.collect { audioPlayer.pause() }
    }

    // 后台行为=暂停：共享音频播放器也听闸（含迷你条接管/锁屏续播期间）。
    // 回前台不自动续播——用户可能已在锁屏主动暂停，恢复权交给迷你条/锁屏/音频卡
    // （appContainer 声明已提前到本函数开头：R180 播放器上提容器后需要更早取用）
    LaunchedEffect(appContainer.audioCardsPaused.value) {
        if (appContainer.audioCardsPaused.value) audioPlayer.pause()
    }
    DisposableEffect(Unit) {
        // 后台闸暂停时的"谁在播"记录：回前台只恢复这些（用户本来就暂停着的不动）——
        // 与 R180 导航闸同一语义（navPausedVideoKeys/navPausedAudio 记账恢复）
        var bgPausedVideoKey: String? = null
        var bgPausedAudio = false
        // R437 H2/H7：四槽先落局部 val 再注册——dispose 只清自己注册的实例（===
        // 守卫，GridScreen:1724-1728 先例）：when 分支切换（viewMode 切分屏等）时
        // 新页先注册、旧 FeedScreen 的 dispose 晚到，无条件置 null 会把新页的
        // 注册一并抹掉（覆盖页在位期间的暂停/续播/进度落盘/取条全失效）
        val pauser: () -> Unit = {
            val key = vm.items.value.getOrNull(vm.currentIndex.value)?.key
            // 一刀切暂停整池：池内可同时存在多路 busy（翻页在途旧页未收/邻页残留），
            // 只按 currentIndex 暂停一路会漏音——实测后台仍有另一路 AudioTrack 处于 started。
            // 恢复清单仍只记"当前页且处于播放意图"的那一路（用户手动暂停着的不该被回前台拉起）。
            // 判定用 playWhenReady 而非 isPlaying：缓冲中 isPlaying=false 但用户语义上
            // 是"在播"，回前台应当续播；且闸落前的快照读取顺序也要求这里的判定不被
            // 先行暂停影响（playWhenReady 反映意图，不被瞬时状态干扰）
            val wasPlaying = key != null && playerPool.peek(key)?.playWhenReady == true
            playerPool.pauseAll()
            android.util.Log.i(
                "BGGate",
                "后台暂停执行: currentKey=${key?.takeLast(12)} 当前页在播=$wasPlaying",
            )
            bgPausedVideoKey = if (wasPlaying) key else null
            // 共享音频播放器同步拉停（音频卡划出销毁后 currentPlayerPauser 原本够不到它）
            // R437 H4：判定用 playWhenReady 而非 isPlaying（对齐 :675 视频侧口径）——
            // 缓冲中音频 isPlaying=false 但用户语义在播，回前台应续播
            bgPausedAudio = audioPlayer.playWhenReady
            if (bgPausedAudio) audioPlayer.pause()
        }
        val resumer: () -> Unit = {
            // 恢复前校验目标仍是当前条（切源/切条过就不回放旧声音，与主页闸同口径）
            bgPausedVideoKey?.let { k ->
                if (vm.items.value.getOrNull(vm.currentIndex.value)?.key == k) {
                    playerPool.peek(k)?.play()
                }
            }
            bgPausedVideoKey = null
            if (bgPausedAudio) {
                audioPlayer.play()
                bgPausedAudio = false
            }
        }
        // 记忆播放：退后台（onStop）时同步落盘当前条进度——杀进程前的最后保存机会，
        // 必须走 saveProgressSync 当场写盘（异步落盘可能在进程被杀前来不及执行）
        val progressSaver: () -> Unit = {
            val item = vm.items.value.getOrNull(vm.currentIndex.value)
            val pos = item?.let { playerPool.peek(it.key)?.currentPosition }
            if (item != null && pos != null) vm.saveProgressSync(item, pos)
        }
        val itemProvider: () -> dev.tgfeed.core.td.VideoItem? = {
            // 音频接管媒体会话期间（共享音频播放器为主位且装有音频条目）：
            // 元数据锁定在播音频——锁屏/通知显示歌名·歌手而不是当前浏览页；
            // 其余情况回当前页条目（视频场景原行为）。mediaId=item.key 是音频卡的归属约定
            val audioKey = audioPlayer.currentMediaItem?.mediaId
            val parkedAudio = if (audioKey != null && PlayerPool.lastActivePlayer === audioPlayer) {
                vm.items.value.firstOrNull { it.key == audioKey }
            } else {
                null
            }
            parkedAudio ?: vm.items.value.getOrNull(vm.currentIndex.value)
        }
        appContainer.currentPlayerPauser = pauser
        appContainer.currentPlayerResumer = resumer
        appContainer.currentProgressSaver = progressSaver
        appContainer.currentItemProvider = itemProvider
        onDispose {
            // R437 H2/H7：只注销自己注册的（=== 比较）——防分支切换时晚到的 dispose
            // 清掉别页（分屏 GridScreen 等）刚注册上的实例
            if (appContainer.currentPlayerPauser === pauser) appContainer.currentPlayerPauser = null
            if (appContainer.currentPlayerResumer === resumer) appContainer.currentPlayerResumer = null
            if (appContainer.currentProgressSaver === progressSaver) appContainer.currentProgressSaver = null
            if (appContainer.currentItemProvider === itemProvider) appContainer.currentItemProvider = null
        }
    }

    // R173 Feed 沉浸 chrome 隐藏信号（复用顶栏同一信号源扩一位，design-proposal §2.1）：
    // 清屏/全屏（hideTopBar）、源面板开窗（模态盖顶）、搜索音频页（全屏播放器形态）、
    // 快拍连播查看器（满屏沉浸黑底——R177 挂账①②收口：底栏压在查看器之上、连播内
    // 横滑切走页面，两症同根=本信号缺位；一位修复三消费方：底栏退场+让位归零+顶层
    // 横滑闸关闭）→ 上报 AppContainer，MainActivity 底栏据此退场 + LocalBottomBarInset
    // 让位归零（清屏的定义就是 chrome 全退，底栏若残留就不是清屏）。离组合时复位，防陈旧值
    // 把别的页面的底栏藏掉。
    val searchAudioPlaying = appContainer.searchReturnPending.value &&
        items.getOrNull(currentIndex)?.mediaType == 2
    // R178 主窗抽屉位（Filter/Lucky/Collections 迁 AppSheetMotion 后失守补位）：这三个
    // 面板旧为 ModalBottomSheet 独立窗口（z 序天然盖底栏），迁主窗后渲染层级在 pager
    // 页内、低于 MainActivity 尾部挂载的底栏——不进本信号底栏会浮在抽屉之上；
    // 搜索页版 FilterSheet 无此问题（搜索页开着 = overlayPageOpen 底栏已退）
    val feedChromeHidden = hideTopBar || sourceSheetOpen || searchAudioPlaying ||
        storiesViewerOpen || showFilter || showLuckyPicker || showCollections
    LaunchedEffect(feedChromeHidden) {
        appContainer.feedChromeHidden.value = feedChromeHidden
    }
    DisposableEffect(Unit) {
        onDispose { appContainer.feedChromeHidden.value = false }
    }

    // ---------------- R180 App 内导航播放闸（需求①：切页/进全屏子页面 → 在播内容暂停，不清状态回页可续播） ----------------
    // 与 R158 退后台闸同一哲学（一刀切+记账+迟到播放拦截），驱动源换成 App 内页面态：
    // AppContainer.feedPageActive（MainActivity 合成写入：顶层页位/子页整页路由/频道主页
    // 覆盖层/视图模式）+ 本页 storiesViewerOpen（快拍连播满屏覆盖，同属"feed 不在台前"）。
    // 裁决矩阵 = core/playback/NavPlaybackPolicy 纯函数（页面态×内容类型×共存开关，
    // 单测同源）；视频走池的 setNavGate（一刀切暂停+记账+闸期迟到播放拦截——池内每台
    // 播放器的监听器把闸期的 playWhenReady=true 打回，修"进主页时还在缓冲的视频缓冲
    // 完成后照样开播"的迟到漏音），音频按矩阵豁免/暂停（共存开=背景听：切页继续播）。
    // 类收敛：取代旧 channelHomeOpen / storiesViewerOpen 两处同模式闸——旧闸病根
    // （isPlaying 口径漏缓冲中条目、只暂停当前单 key 漏多路 busy、无迟到拦截）导致
    // 实测实锤整条 feed 在频道主页之下 autoAdvance 连锁连播近 10 分钟（r180_notes ③）。
    // 回页恢复口径：池侧"记账（落闸前在播的）+拦截（闸期被吃掉的自动起播）"合并恢复；
    // 音频侧本闸只记 playWhenReady=true 的（用户本来就暂停着的不动）。
    // R417：私有导航闸收敛进共享件 PlaybackOwnershipGate（持有者=FEED；页内快拍满屏
    // 压顶=localYield 让位）——修旧闸重复落闸时空账本覆盖记账的漏恢复
    PlaybackOwnershipGate(
        pool = playerPool,
        owner = appContainer.playbackOwner.value,
        holder = dev.tgfeed.core.playback.PlaybackOwner.FEED,
        allowAudioVideoSimultaneous = prefs.allowAudioVideoSimultaneous,
        localYield = storiesViewerOpen,
        audioPlayer = audioPlayer,
    )
    // 横滑切页/进子页路由会让本页整个 dispose（R177 离场制）——导航闸 effect 随组合
    // 消亡（新决策来不及跑），池的 releaseAll 仍是那条路的视频停播点；音频在 dispose
    // 点按矩阵收口：共存关=暂停不清状态（回页迷你条可续），开=豁免继续播（背景听）。
    // 判定必须**现读**（prefs/播放器实时态）而非闭包捕获——dispose 闭包抓到的是上一帧
    // 的旧决策（台前态），会把该停的音频漏掉。
    DisposableEffect(Unit) {
        onDispose {
            if (!prefs.allowAudioVideoSimultaneous && audioPlayer.playWhenReady) {
                audioPlayer.pause()
                android.util.Log.i("NavGate", "页面离场：音频随导航闸暂停（不清状态，回页可续）")
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 记忆播放：整页离开（切设置/分屏/下载页）时先保存当前条进度再释放播放器——
            // 激活页不经过 VideoPage 的失焦分支，这里的兜底是它在该路径上唯一的保存点
            // （release 后 peek 拿不到实例，顺序不能反）
            val item = vm.items.value.getOrNull(vm.currentIndex.value)
            val pos = item?.let { playerPool.peek(it.key)?.currentPosition }
            if (item != null && pos != null) vm.saveProgress(item, pos)
            preloader.cancel()
            playerPool.releaseAll()
        }
    }

    // 摇一摇换一批：加速度计监听。DisposableEffect 保证进 Feed 注册、离开（切页/退分屏）即注销；
    // key 挂开关，设置页关掉后重组即停监听。分屏页走 GridScreen 不经过本 Composable，天然不注册。
    // R189 保活门控再挂 feedPageActive：三页常驻后滑去发现/我的本页不 dispose——
    // 传感器若照常在册，用户在别的页摇设备会给**不可见的 feed** 触发一轮网络重拉
    //（带宽纪律：不可见不拉流）。回台前自动重注册，行为零变化。
    DisposableEffect(prefs.shakeToRefresh, appContainer.feedPageActive.value) {
        if (!prefs.shakeToRefresh) {
            onDispose { } // 开关关闭：不注册任何监听
        } else {
            val sensorManager = context.getSystemService(android.content.Context.SENSOR_SERVICE)
                as android.hardware.SensorManager
            val accelerometer = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
            // 500ms 滑动窗口内的尖峰时间戳
            val spikeTimes = ArrayDeque<Long>()
            var lastMag = -1f      // 上一帧加速度模长，-1 表示未初始化
            var lastTriggerAt = 0L // 上次触发时间（防抖用）
            val listener = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(event: android.hardware.SensorEvent) {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    val mag = kotlin.math.sqrt(x * x + y * y + z * z)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (lastMag >= 0f) {
                        val delta = kotlin.math.abs(mag - lastMag)
                        if (delta > SHAKE_DELTA_THRESHOLD) {
                            // 踢出窗口外的旧尖峰，再计入本次
                            while (spikeTimes.isNotEmpty() && now - spikeTimes.first() > SHAKE_WINDOW_MS) {
                                spikeTimes.removeFirst()
                            }
                            spikeTimes.addLast(now)
                            if (spikeTimes.size >= SHAKE_SPIKE_COUNT && now - lastTriggerAt > SHAKE_DEBOUNCE_MS) {
                                lastTriggerAt = now
                                spikeTimes.clear()
                                vm.refresh()
                                android.widget.Toast.makeText(context, "换了一批", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    lastMag = mag
                }

                override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
            }
            // GAME 档（约 20-50ms 一帧）：NORMAL 太稀，500ms 窗口内凑不满 3 个尖峰
            if (accelerometer != null) {
                sensorManager.registerListener(listener, accelerometer, android.hardware.SensorManager.SENSOR_DELAY_GAME)
            }
            onDispose { sensorManager.unregisterListener(listener) }
        }
    }

    // R189 台前让位（架构师转交线索②，与摇一摇门控同族带宽纪律）：保活后滑去发现/
    // 我的，feed 页组合着但非台前——旧离场制的 preloader.cancel() 随 dispose 不再触
    // 发，预载队列会在后台继续磨带宽。显式拉闸：非台前即 cancel；回台前由既有
    // schedule 链（落定页 LaunchedEffect）自然重排，行为零变化
    LaunchedEffect(appContainer.feedPageActive.value) {
        if (!appContainer.feedPageActive.value) preloader.cancel()
    }

    // "加入片单"穿透：功能面板（VideoActionSheet）渲染在 VideoPage 内部，VideoPage 参数表
    // 本轮冻结不能加回调——用 CompositionLocal 把处理器递下去；面板触发时把目标条目
    // （当前停留页）提到 playlistPickTarget，由本页在下方宿主片单选择对话框
    androidx.compose.runtime.CompositionLocalProvider(
        dev.tgfeed.ui.components.LocalAddToPlaylist provides {
            playlistPickTarget = vm.items.value.getOrNull(vm.currentIndex.value)
        },
    ) {
    // ================= R181 四层滑动轨道（横滑切媒体 tab，策略见 MediaRailPolicy） =================
    // 跟手视觉：拖拽中 tab 带权重从当前 tab 渐变到目标 tab（null=空闲；复位出口在
    // 手势循环的 finally，协程取消路径也覆盖）。手势闸与 R177 顶层 pager 同一枚
    // topLevelSwipeEnabled——chrome 隐藏/覆盖层压顶期间 L1 拖拽与 L0 甩尾级联同关闸。
    var railDragVisual by remember { mutableStateOf<dev.tgfeed.ui.RailDragVisual?>(null) }
    val railEnabled = dev.tgfeed.ui.topLevelSwipeEnabled(feedChromeHidden, overlayOpen = channelHomeOpen)
    // 轨道动作执行器（L1 拖拽与 L0 甩尾级联共用同一条查表执行链——单一实现两个触发点）：
    // StepTab=setMediaTypeTab（R189 起用户导航路：快照落袋→命中恢复/未命中 refresh——
    // 横滑切 tab 再切回内容与位置保留）；SwitchColumn=与顶栏关注/推荐 tab 点击同一
    // showFollowing/showAggregate（媒体 tab 保持、sourceTitle 同步、R189 列快照缓存，
    // 语义逐字一致）；StepTopPage=与底栏点按同一 requestTopLevelPage 滚页请求
    //（PageSpring 落定）。R189 切走轨道停播：音频按关档矩阵先停（视频由列表换血自然停）
    val executeRailAction: (dev.tgfeed.ui.RailAction) -> Unit = { action ->
        when (action) {
            is dev.tgfeed.ui.RailAction.StepTab -> {
                pauseAudioOnTrackSwitch()
                vm.setMediaTypeTab(action.tab)
            }
            is dev.tgfeed.ui.RailAction.SwitchColumn -> {
                pauseAudioOnTrackSwitch()
                if (action.column == dev.tgfeed.ui.FeedColumn.FOLLOWING) {
                    sourceTitle = "关注"
                    vm.showFollowing()
                } else {
                    sourceTitle = "推荐"
                    vm.showAggregate()
                }
            }
            is dev.tgfeed.ui.RailAction.StepTopPage -> appContainer.requestTopLevelPage(action.page)
            dev.tgfeed.ui.RailAction.None -> {}
        }
    }
    // px 换算（dp 常量单一来源 MediaRailPolicy，密度只读一次）
    val railDensity = androidx.compose.ui.platform.LocalDensity.current
    val railStepPx = with(railDensity) { dev.tgfeed.ui.MediaRailPolicy.DRAG_STEP_DP.dp.toPx() }
    val railFlingPx = with(railDensity) { dev.tgfeed.ui.MediaRailPolicy.FLING_VELOCITY_DP.dp.toPx() }
    val railCascadePx = with(railDensity) { dev.tgfeed.ui.MediaRailPolicy.CASCADE_VELOCITY_DP.dp.toPx() }
    // 手势定局 → 查表执行（轨道位置调用时现读 VM/容器，闭包不过期）
    val resolveRailAndExecute: (Float, Float) -> Unit = { deltaX, velocityX ->
        executeRailAction(
            dev.tgfeed.ui.MediaRailPolicy.resolveGesture(
                dragDeltaX = deltaX,
                velocityX = velocityX,
                stepPx = railStepPx,
                flingVelocityPx = railFlingPx,
                cascadeVelocityPx = railCascadePx,
                mediaTab = vm.mediaTypeTab.value,
                column = dev.tgfeed.ui.MediaRailPolicy.columnOf(vm.source),
                topPage = appContainer.topLevelPage.value,
            ),
        )
    }
    // L0 甩尾级联（图集/Stories/LazyRow 边界甩尾）：级联门槛已在连接判过，此处查表执行
    val cascadeSpillAndExecute: (Float) -> Unit = { vx ->
        val commit = dev.tgfeed.ui.MediaRailPolicy.commitFromSpill(vx, railCascadePx)
        executeRailAction(
            dev.tgfeed.ui.MediaRailPolicy.resolveStep(
                commit,
                vx,
                railCascadePx,
                mediaTab = vm.mediaTypeTab.value,
                column = dev.tgfeed.ui.MediaRailPolicy.columnOf(vm.source),
                topPage = appContainer.topLevelPage.value,
            ),
        )
    }
    // 视频内容延伸到状态栏/导航栏后面，浮层自行处理 insets
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // R181 轨道宿主（共同祖先位）：L1 拖拽探测（pointerInput，Main pass 子先父后
            // 饿死顶层 pager 的横滑探测）+ L0 甩尾级联连接（nestedScroll 链序在页级
            // guard 之内，低于门槛原样上抛给 guard 吞）
            .mediaRailHost(
                enabled = railEnabled,
                currentTab = { vm.mediaTypeTab.value },
                onDragVisual = { railDragVisual = it },
                onRailGestureEnd = resolveRailAndExecute,
                onSpillCascade = cascadeSpillAndExecute,
            ),
    ) {
        // 响应式：平板/横屏内容区限宽居中，手机竖屏铺满
        val ws = rememberWindowSize()
        // R159 内容感知列宽：宽高比记账表（条目 key → (宽高比, 是否实测)）。
        // 预判：条目元数据（下方现算，不经表）→ 元数据缺尺寸回落封面代理（VideoPage 上报）；
        // 实测：起播后 ExoPlayer 真实画面比（VideoPage 上报，onVideoSizeChanged 链路）。
        // 翻回已播过的条目时 VideoPage 侧 playedVideoRatio 随 remember(item.key) 归零，
        // 但表里留着实测值，列宽落定即正确、不重新动画。容量防御：超 512 清表
        //（会话内记账，清空后起播的页面重报自愈，漏报窗口只是一次翻页的动画）
        val videoAspectBoard = remember { mutableStateMapOf<String, Pair<Float, Boolean>>() }
        // R189 内容域转场（任务书②，D 节）：媒体 tab 切换/级联换列的内容平滑过渡——
        // key=(源实例, 媒体tab) 任一变化即「单容器淡入+方向性轻位移」（tween AnimMed
        // + EmphasizedEasing，内容域 tween 纪律；不做双分支 Crossfade——视频负载下
        // 双声重叠/播放器 churn 不可接受，见 kit 头注释）。缓存恢复的瞬时换血（零网络
        // 命中）与 miss 路径的空态登场同款触发——有快照=内容淡入、无快照=加载态淡入
        //（R167/R170b 加载文案 UX 原样，spinner 即反馈）。
        val trackKey = vm.source to mediaTypeTab
        // 方向裁决：同源切 tab = tab 轨道序符号（全部-1→…→音频2，左滑前进=+1）；
        // 换源（级联换列/跳转/弹栈）= 列轨道序符号（推荐0→关注1）；其余 +1（前进观感）
        val trackDirectionOf: (Any?, Any?) -> Float = { old, new ->
            val o = old as? Pair<*, *>
            val n = new as? Pair<*, *>
            when {
                o == null || n == null -> 1f
                o.first === n.first ->
                    kotlin.math.sign(((n.second as Int) - (o.second as Int)).toFloat())
                else -> {
                    val oc = dev.tgfeed.ui.MediaRailPolicy.columnOf(o.first as? dev.tgfeed.core.feed.FeedSource)
                    val nc = dev.tgfeed.ui.MediaRailPolicy.columnOf(n.first as? dev.tgfeed.core.feed.FeedSource)
                    if (oc != null && nc != null) kotlin.math.sign((nc.ordinal - oc.ordinal).toFloat()) else 1f
                }
            }
        }
        // 全限定调用（kit 函数形态）：免 import 混入并发代理 hunk（共享树暂存纪律）
        val trackContentModifier = dev.tgfeed.ui.components.kit.contentDomainTransition(
            Modifier.fillMaxSize(),
            trackKey,
            trackDirectionOf,
        )
        // R194② 冻结窗驱动：trackKey 变化（四消费面唯一汇点：媒体条 band/L1 轨道
        // 拖拽/顶栏关注推荐/底栏回底）→ 冻结 [TRACK_SWITCH_FREEZE_MS] → 解冻 +
        // 代次+1（VideoPage 上报 effect 键挂代次——当前页重报最新帧，一次软切）。
        // 窗内再切轨道：effect 重启=计时重置（冻结顺延）；首组合不冻结（冷启动
        // 首帧封面照常上报）。
        var trackKeySeenForFreeze by remember { mutableStateOf<Any?>(null) }
        LaunchedEffect(trackKey) {
            val prev = trackKeySeenForFreeze
            trackKeySeenForFreeze = trackKey
            if (prev != null && prev != trackKey) {
                backdropFrozen = true
                backdropDiscretePending = true
                android.util.Log.i("R194", "底衬冻结（轨道切换窗 ${dev.tgfeed.ui.BackdropPolicy.TRACK_SWITCH_FREEZE_MS}ms）")
                delay(dev.tgfeed.ui.BackdropPolicy.TRACK_SWITCH_FREEZE_MS)
                backdropFrozen = false
                backdropAdoptGen.intValue++
                android.util.Log.i("R194", "底衬解冻（代次+1，当前页重报最新帧）")
            }
        }
        Box(trackContentModifier) {
        if (items.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 修"启动闪空态"：首轮内容结论出来之前（网关 Ready → 首页第一页到达的窗口），
                // 一切空态内容（零频道引导卡/重温提示/筛选空态）一律不许展示——只显加载中。
                // 否则启动瞬间会闪一下"还没有频道"卡片（channels 是异步拉的，窗口期恒为空）
                val initialContentReady by vm.initialContentReady.collectAsState()
                if (!initialContentReady) {
                    FeedEmptyState(loading = true, error = null, onRetry = { vm.loadMore() })
                } else {
                // GAPS3 零频道引导：真实模式（TdLibGateway）且一个频道都没加入时，
                // 普通空态换成新用户引导卡（"死城"→ 给出下一步动作）；
                // 演示模式（MockTdGateway）频道恒非空、且演示数据不需要引导，本分支不触发
                val showZeroChannelGuide = !loading && error == null &&
                    channels.isEmpty() && gateway is dev.tgfeed.core.td.TdLibGateway
                // R154 关注流空态：一个订阅都没有时给引导（关注页 = 订阅频道的内容主场，
                // 空订阅 = 这里永远没有内容，必须指路订阅入口，别空屏）。优先于通用空态
                val showFollowingGuide = followingActive && !loading && error == null &&
                    subscribedIds.isEmpty()
                // R176 关注流永不见底自愈：供给分层（未看深挖→已看回收→推荐兜底）后，
                // 「有订阅却空屏」不该是终态——有界自动重拉（≤3 次、间隔 800ms），
                // 兜住任何漏网的枯竭死角；计数随内容到货自然复位（离开空态即丢弃）。
                // 仍空则走通用空态——关注流仅剩的正当空态=无订阅引导/加载失败重试
                var followingEmptyRetry by remember { mutableStateOf(0) }
                LaunchedEffect(
                    followingActive, items.isEmpty(), loading, error,
                    initialContentReady, subscribedIds.isNotEmpty(),
                ) {
                    if (followingActive && items.isEmpty() && !loading && error == null &&
                        initialContentReady && subscribedIds.isNotEmpty() &&
                        followingEmptyRetry < 3
                    ) {
                        kotlinx.coroutines.delay(800)
                        followingEmptyRetry++
                        vm.loadMore()
                    }
                }
                // R167 #话题源标识：点击 caption 标签切来的「标签：#tag」源（hashtagSearch
                // 混合源）。提取手法沿用 blindKeyword 的 sourceTitle 前缀判定先例
                val hashtagTag = sourceTitle.takeIf { it.startsWith("标签：") }?.removePrefix("标签：")
                if (showFollowingGuide) {
                    FollowingEmptyGuideCard(
                        onOpenDiscover = { appContainer.openDiscover() },
                        onOpenSearch = { appContainer.openSearch() },
                    )
                } else if (showZeroChannelGuide) {
                    ZeroChannelGuideCard(
                        onOpenSearch = { appContainer.openSearch() },
                        onOpenDiscover = { appContainer.openDiscover() },
                    )
                } else if (hashtagTag != null) {
                    // R167 #话题源专属三态：45s 级深索引扫描必须让用户知道在等什么
                    //（加载文案），真没命中时诚实说"深索引未覆盖"并给两条出路——
                    // 不再笼统"这个源暂时没有视频"（用户实锤投诉文案）
                    if (loading) {
                        FeedEmptyState(
                            loading = true, error = null, onRetry = {},
                            loadingText = "正在搜索 $hashtagTag…\n深索引正在扫 4GB 本地索引（约需几十秒，先保持页面开着）",
                        )
                    } else if (error != null) {
                        FeedEmptyState(loading = false, error = error, onRetry = { vm.loadMore() })
                    } else {
                        HashtagEmptyCard(
                            tag = hashtagTag,
                            onOpenSearch = { appContainer.openSearch(hashtagTag) },
                            onRetry = { vm.refresh() },
                        )
                    }
                } else if (followingActive && loading) {
                    // R170b 关注流加载文案（真最坏情况兜底：预热未完成且无快照垫底——
                    // 用户设计原则「扫描不暴露」的正常路径下本文案根本不会出现）：
                    // 切关注 tab 秒出快照垫底内容，网络刷新后台接续；仅当快照缺位
                    // （首次使用/预热失败）且首拉未到时显示。
                    FeedEmptyState(
                        loading = true, error = null, onRetry = {},
                        loadingText = "正在拉取关注频道的新内容…\n" +
                            "首次冷拉约需几十秒，内容一到即上屏（之后打开会快很多）",
                    )
                } else if (loading && mediaTypeTab != -1 && !followingActive) {
                    // R170 媒体类型 tab 冷加载进度文案（榜单 #7，R167 hashtag 三态同范式）：
                    // 音频/图文 tab 冷缓存首开是"频道枚举+整窗聚合"的几十秒级慢路（R161c
                    // 实测 60~80s 暗骨架一跳满载，用户感知"点了没反应"）——裸 spinner 必须
                    // 配一句用户语言的说明（R167 判据：慢路径等待必须有"在等什么"）。
                    // 快车道（R170 类型感知种子）正常时首屏几秒即到；本文案兜慢路/兜底。
                    // !followingActive：关注流有自己的文案（上分支），媒体 tab 文案
                    // 不在关注源上误现（用户实锤「切关注显示扫描频道」的口径修正）。
                    FeedEmptyState(
                        loading = true, error = null, onRetry = {},
                        loadingText = "正在扫描全部频道的${tabLabelOf(mediaTypeTab)}内容…\n" +
                            "冷缓存首扫约需几十秒，首批内容一到即上屏",
                    )
                } else {
                    // R324 离线降级：通用空态（在线失败/真空态）挂离线解释行——空列表+
                    // 离线时「暂无缓存内容」的诚实出口（在线 null=行为与旧版逐字节一致）
                    FeedEmptyState(
                        loading = loading, error = error, onRetry = { vm.loadMore() },
                        offlineHint = dev.tgfeed.core.net.OfflineDegradePolicy.feedOfflineEmptyHint(networkOnline),
                    )
                }
                // 筛选非默认（方向/时长/日期任一启用）时的空态：给一键重置入口（setFilter 内部自带 refresh）
                if (!loading && error == null && filter != FeedFilter()) {
                    OutlinedButton(onClick = { vm.setFilter(FeedFilter()) }) {
                        Text("重置筛选")
                    }
                }
                // 服务端搜索为空时给深度搜索入口（本地全量索引，需在设置页先建索引）
                val q = sourceTitle.removePrefix("搜索：").removePrefix("#")
                if (!loading && error == null && sourceTitle.startsWith("搜索：")) {
                    TextButton(onClick = {
                        sourceTitle = "深度搜索：$q"
                        vm.deepSearch(q)
                    }) {
                        Text("本地深度搜索（已索引的全量内容）", color = dev.tgfeed.ui.theme.TgFeedTokens.TgBlue)
                    }
                    // 机器人搜索（全网资源）：把关键词私聊发给第三方搜索机器人（如极搜，设置页可换），
                    // 机器人回复 t.me 资源链接，网关侧解析成可播条目后切到一次性源直接刷。
                    // 一次性源参考 DeepSearchFeedSource 模式：结果一页给完、nextCursor 恒 null、reset 可重放；
                    // 匿名实现即可，无需改动 FeedSource 接口/ViewModel
                    TextButton(
                        onClick = {
                            if (botSearching) return@TextButton // 防重入：一轮搜索最多 12 秒
                            botSearching = true
                            scope.launch {
                                val results = when (gateway) {
                                    is dev.tgfeed.core.td.TdLibGateway ->
                                        gateway.searchViaBot(prefs.searchBotUsername, q)
                                    is dev.tgfeed.core.td.MockTdGateway ->
                                        gateway.searchViaBot(prefs.searchBotUsername, q)
                                    else -> emptyList()
                                }
                                botSearching = false
                                if (results.isEmpty()) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "机器人未回复或未搜到资源（已等待 12 秒）",
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                } else {
                                    sourceTitle = "机器人搜索：$q"
                                    vm.switchSource(object : dev.tgfeed.core.feed.FeedSource {
                                        // 带 searchQuery 标识搜索源（与 DeepSearchFeedSource 同一约定）
                                        override val request = dev.tgfeed.core.td.FeedRequest(searchQuery = q)
                                        private var served = false
                                        override suspend fun nextPage(limit: Int): dev.tgfeed.core.td.FeedPage {
                                            if (served) return dev.tgfeed.core.td.FeedPage(emptyList(), null)
                                            served = true
                                            return dev.tgfeed.core.td.FeedPage(results, nextCursor = null)
                                        }
                                        override fun reset() { served = false }
                                    })
                                }
                            }
                        },
                        enabled = !botSearching,
                    ) {
                        Text(
                            if (botSearching) "机器人搜索中…（最长 12 秒）" else "机器人搜索（全网资源）",
                            color = dev.tgfeed.ui.theme.TgFeedTokens.TgBlue,
                        )
                    }
                }
                // 已看过滤滤空了（推荐页，无新内容）时给"重温"兜底：关掉过滤重刷
                //（R176：关注流摘除本钮——供给已分层（未看深挖→已看回收→推荐兜底），
                //  枯竭态由源内自动降层续供到底，「刷完/到底」不再报给用户；且此钮
                //  全局关 excludeWatched 会连带推荐 tab 行为，只留给推荐页）
                if (!loading && error == null && prefs.excludeWatched && !showFollowingGuide &&
                    sourceTitle == "推荐"
                ) {
                    TextButton(onClick = {
                        prefs.excludeWatched = false
                        vm.setExcludeWatched(false)
                        vm.refresh()
                    }) {
                        Text("新内容刷完了，点击重温已看内容", color = dev.tgfeed.ui.theme.TgFeedTokens.TgBlue)
                    }
                }
                } // 结束 initialContentReady 的 else（启动闪空态修复）
            }
        } else {
            // pager 状态提升出 key(vm.source)：切源手动回顶（替代重建），
            // 分屏"全屏单播"可定位到指定条目
            run {
                val pagerState = rememberPagerState(
                    initialPage = vm.currentIndex.value.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
                    pageCount = { items.size },
                )

                // 切源回顶。守卫：只在组合存活期间源"真的切换"时执行——
                // 首次组合（含从设置/分屏/下载页返回重建 pager）不触发，
                // 否则 initialPage 恢复到的位置会被这里立即打回第 0 条。
                // R182：源栈弹回（popSourceBack 原位恢复）经抑制位让路一跳——
                // 恢复的页位由 VM 侧 resumeScroll 事件滚回，不再被顶回 0 条。
                val sourceAtCreation = remember { vm.source }
                LaunchedEffect(vm.source) {
                    if (vm.source !== sourceAtCreation && !vm.consumeSourceResetScrollSuppression()) {
                        pagerState.scrollToPage(0)
                    }
                }

                // R215 ② 注入位活跃判定（页渲染/seen 补记/弹幕守卫共用）：卡在场且仍挂
                // 在注入时的源实例上（切源即失效——记忆卡不跨源，popSourceBack 弹回重现）
                val onThisDayCardActive: (Int) -> Boolean = { p ->
                    val card = onThisDayCard
                    card != null && p == ON_THIS_DAY_FEED_CARD_PAGE && vm.source === card.hostSource
                }

                // 记忆播放：进程重启后 VM 首屏恢复定位成功 → 滚动到该条目。
                // 事件缓冲 1 发，pager 晚于定位创建也能收到；items 现读（闭包不随重组重启）
                val itemsNow = androidx.compose.runtime.rememberUpdatedState(items)
                LaunchedEffect(pagerState) {
                    vm.resumeScroll.collect { key ->
                        val idx = itemsNow.value.indexOfFirst { it.key == key }
                        if (idx >= 0 && pagerState.settledPage != idx) {
                            pagerState.scrollToPage(idx)
                        }
                    }
                }

                // R336 图片档底衬清场（A 方案）：当前页翻成图片项（或换轨换血后当前位
                // 落图片项）时底衬淡出至纯深色——不留上一条视频的模糊镜像悬在图片卡后面
                //（残留比双层更怪）。流读「当前页条目的 mediaType」：currentPage 翻转
                //（拖拽过半——与 R160 采纳同一时机锚）与条目表换血（切 tab 同下标类型
                // 变）都触发；值不变不发射（图→图翻页天然幂等）。收养门
                //（shouldAdoptBackdropForMedia，下方 adoptBackdrop）已把图片上报全拒，
                // 本观察者是图片档唯一底衬事件源。冻结窗有意不查：R194② 冻结防的是恢复
                // 窗内连环采纳闪屏，图片档窗内零采纳、清场即正确终态（无闪烁面）；反之
                // 窗内跳过=解冻后图片重报被拒→旧视频底衬永久残留（推演见 r336_notes
                // §2.2）。模式走 BackdropPolicy.photoBackdropClearTransition（既有
                // BackdropTransition 双态，零新动画体系）；ownerKey 置空=归属清账，翻回
                // 视频时上报照常走换主软切（视频腿零变化）。
                LaunchedEffect(pagerState) {
                    androidx.compose.runtime.snapshotFlow {
                        items.getOrNull(pagerState.currentPage)?.mediaType
                    }.collect { mediaType ->
                        if (mediaType == 1 && backdropCover != null) {
                            backdropOwnerKey = null
                            backdropMode = dev.tgfeed.ui.BackdropPolicy.photoBackdropClearTransition(
                                backdropDiscretePending,
                            )
                            backdropDiscretePending = false
                            backdropCover = null
                            android.util.Log.i("R336", "图片项在场底衬清场（淡出至纯深色）")
                        }
                    }
                }

                // R194①② 底衬采纳共用体（视频卡/图文卡双消费面——镜像上报的统一
                // 收口）：采纳守卫（currentPage 过半）+ 冻结闸 + 换主/live 制度记账 +
                // 换图模式裁决（离散暗场溶解/换主与 live 首拍软切/镜像帧 SNAP 直换）。
                // 规则矩阵见 BackdropPolicy（单测枚举）；模式语义见 BackdropStackFade。
                // R336：守卫升级 shouldAdoptBackdropForMedia——图片项上报（含图集翻页
                // 重报）恒拒收（A 方案图片档底衬退役；清场由上方 R336 观察者驱动）。
                val adoptBackdrop: (Int, dev.tgfeed.core.td.VideoItem, androidx.compose.ui.graphics.ImageBitmap?, Boolean) -> Unit =
                    { page, item, cover, liveFrame ->
                        if (!backdropFrozen && BackdropPolicy.shouldAdoptBackdropForMedia(page, pagerState.currentPage, item.mediaType)) {
                            val ownerSwitch = BackdropPolicy.isBackdropOwnerSwitch(backdropOwnerKey, item.key)
                            if (ownerSwitch) {
                                backdropOwnerKey = item.key
                                // 换主：live 制度随归属复位（新条目从封面底衬重新起制度）
                                backdropLiveAdopted = false
                                android.util.Log.i(
                                    "R160",
                                    "底衬换主 key=…${item.key.takeLast(12)} " +
                                        "帧=${if (cover != null) "有" else "null"}",
                                )
                            }
                            // R159b 起播一拍：本条镜像制度首拍（封面底衬首次换成镜像实帧）
                            // 走 SOFT 淡入——与首帧上屏同拍合并成「占位→画面」的一部分
                            //（「新视频首帧起镜像渐入」，架构师 17:00 终案）；其后镜像帧
                            // SNAP 直换。判据沿用 isBackdropSnapSwitch 的 live 首拍标记。
                            val liveRegimeStart = liveFrame && !backdropLiveAdopted
                            if (liveFrame) backdropLiveAdopted = true
                            // R194①② 换图模式裁决：离散切换（源/tab，冻结窗收口第一拍）
                            // =暗场溶解；翻页换主/live 首拍=无黑谷软切；同条目镜像常规帧
                            // =视频式 SNAP 直换；图文卡上报（liveFrame 恒 false）=软切。
                            backdropMode = when {
                                backdropDiscretePending -> {
                                    backdropDiscretePending = false
                                    android.util.Log.i("R194", "底衬暗场溶解（离散轨道切换收口）")
                                    dev.tgfeed.ui.components.BackdropTransition.THROUGH_DARK
                                }
                                ownerSwitch || liveRegimeStart ->
                                    dev.tgfeed.ui.components.BackdropTransition.SOFT
                                liveFrame -> dev.tgfeed.ui.components.BackdropTransition.SNAP
                                else -> dev.tgfeed.ui.components.BackdropTransition.SOFT
                            }
                            if (liveRegimeStart) {
                                android.util.Log.i(
                                    "R159",
                                    "底衬live首拍 key=…${item.key.takeLast(12)}（与首帧同拍，镜像渐入）",
                                )
                            }
                            backdropCover = cover
                        }
                    }

                // 锁屏/耳机/车机"下一条"→ Feed 翻页（媒体会话 skip 信号接线）。
                // R299 B1：听单优先——队列非空时 skip 走听单前进/上一首（控制器
                // 返回 true 即已消费）；队列空回落现状翻页。dispose 时把 shared
                // 镜像回落到控制器兜底（纯背景期锁屏上下曲照活，现状是 null 死钮）。
                DisposableEffect(playerPool) {
                    val queue = appContainer.listenQueueController
                    playerPool.onSkipNext = {
                        if (!queue.skipNext()) {
                            scope.launch {
                                val next = pagerState.settledPage + 1
                                if (next <= items.lastIndex) pagerState.animateScrollToPage(next)
                            }
                        }
                    }
                    PlayerPool.sharedSkipPrevHandler = {
                        if (!queue.skipPrev()) {
                            scope.launch {
                                val prev = pagerState.settledPage - 1
                                if (prev >= 0) pagerState.animateScrollToPage(prev)
                            }
                        }
                    }
                    onDispose {
                        playerPool.onSkipNext = null
                        PlayerPool.sharedSkipNextHandler = { queue.skipNext() }
                        PlayerPool.sharedSkipPrevHandler = { queue.skipPrev() }
                    }
                }

                // 弹幕：当前条目变化时按需拉评论（已拉过不重复拉）。
                // DANMAKU-TG 任务 2：评论带真实发布时间戳（DanmakuItem），
                // 弹幕层按"真实时间间隔 → 视频进度"映射（docs/DANMAKU-TG.md §3）
                LaunchedEffect(pagerState.settledPage, danmakuOn) {
                    if (!danmakuOn) return@LaunchedEffect
                    val it = items.getOrNull(pagerState.settledPage) ?: return@LaunchedEffect
                    // R215 ② 守卫：卡片位压住的条目不在播，不为它拉评论（省一条请求）
                    if (onThisDayCardActive(pagerState.settledPage)) return@LaunchedEffect
                    if (commentsMap.containsKey(it.key)) return@LaunchedEffect
                    // R238：相册成员的讨论线程只挂在载体成员上（其余成员 GetMessageThread
                    // 报 "Message has no thread" 弹幕拉空）——经 commentThreadCarrier 解析
                    // 载体（组内 commentCount>0 的成员，无则组内首位）拉取；VM 内缓存按
                    // 载体 key 归并（同组共享一次线程拉取）。
                    val carrier = vm.commentThreadCarrier(it)
                    val list: List<dev.tgfeed.core.td.DanmakuItem> = when (gateway) {
                        // 富评论（带真实时间戳 + 回复标记；"问出处"上下文弹幕：
                        // 回复命中资源的带"回复「摘要」"前缀）
                        is dev.tgfeed.core.td.TdLibGateway -> gateway.getCommentsRich(carrier)
                        is dev.tgfeed.core.td.MockTdGateway -> gateway.getComments(carrier).mapIndexed { idx, text ->
                            // Mock 无真实评论时间：合成均匀小时间隔（1h/条）的时间戳，
                            // 弹幕的真实间隔映射退化为均匀分布（与旧版观感一致）
                            dev.tgfeed.core.td.DanmakuItem(
                                text = text,
                                timestampSec = it.dateEpochSec.toLong() + (idx + 1) * 3600L,
                                isReply = false,
                            )
                        }
                        else -> emptyList()
                    }
                    commentsMap = commentsMap + (it.key to list)
                }

                // 分屏跳转定位
                LaunchedEffect(pendingJumpKey) {
                    val k = pendingJumpKey ?: return@LaunchedEffect
                    // 等目标真正进列表再滚（GridScreen 侧 offerJumpTarget 已同步登记，
                    // 通常首轮即中；3s 超时兜底竞态）。无论成败最后都消费跳转键——
                    // 旧实现落空时不消费，残留 key 会在下次进出分屏重组时误触发过期跳转
                    val landed = withTimeoutOrNull(3_000) {
                        vm.items.first { list -> list.any { it.key == k } }
                    }
                    val idx = itemsNow.value.indexOfFirst { it.key == k }
                    android.util.Log.i("TGFlow", "Feed 收到跳转: key=${k.takeLast(12)} 列表=${itemsNow.value.size} 条 idx=$idx")
                    if (landed != null && idx >= 0) {
                        pagerState.scrollToPage(idx)
                    } else {
                        android.util.Log.w("TGFlow", "跳转落空：3s 内列表没有 key=${k.takeLast(12)}")
                    }
                    onJumpHandled()
                }

                // "查看上下文"定位（与分屏跳转同一手法，但目标是切源后的异步首拉）：
                // vm.openChannelAt 已把源锚定在目标 messageId（首页必含目标），
                // 这里等它真正进列表再滚过去；6s 超时放弃（目标被过滤/评论级联条目
                // 不在主帖时间线等极端情况），停留在频道顶部并清标记防残留误跳
                val contextJumpKey by vm.contextJumpKey.collectAsState()
                LaunchedEffect(contextJumpKey) {
                    val k = contextJumpKey ?: return@LaunchedEffect
                    val landed = withTimeoutOrNull(6_000) {
                        vm.items.first { list -> list.any { it.key == k } }
                    }
                    if (landed != null) {
                        val idx = itemsNow.value.indexOfFirst { it.key == k }
                        if (idx >= 0 && pagerState.settledPage != idx) {
                            pagerState.scrollToPage(idx)
                        }
                    } else {
                        android.util.Log.w("TGFlow", "查看上下文定位超时：key=${k.takeLast(12)} 未进列表")
                    }
                    vm.consumeContextJump()
                }

                // R94 快甩补记的上一次落定页：只认前向小跨页（2..4 页的一次甩），
                // 搜索/上下文/记忆恢复的程序化大跳不补记（中间页并未真正上屏）
                // R189 穿 tab 记账坑修复（前任移交 H①）：缓存恢复路径不走红态重建
                //（pager run{} 块跨 tab/换列存续），本基线若跨轨道存续，恢复落定时
                // gap 恰落 2..4 会把**新轨道未上屏的中间页** markSeenBatch（未看被记
                // 已看，R94 语义破坏）——remember 键挂 (mediaTypeTab, 源实例)，轨道
                // 一换即归 -1（旧 refresh 路径走空态→重建→remember 全复位的等价物）
                var lastSettledForSeen by remember(mediaTypeTab, vm.source) { mutableStateOf(-1) }

                LaunchedEffect(pagerState.settledPage) {
                    val settled = pagerState.settledPage
                    // R141 盲盒流开场卡：滑离第一页即收（开场时刻只属于流头，翻回不重现）
                    if (settled > 0) luckyCardVisible = false
                    // R94 fling 经过页补记（修"手动上划划过的又被推"）：一次甩多页时
                    // 中间页上过屏但从未 settle，onPage 只记落定页——这里按"上过屏即看过"
                    // 把跨过的中间页补登进 seen 底账（只记身份，不写观看历史/不计时长）
                    val prevSettled = lastSettledForSeen
                    lastSettledForSeen = settled
                    val gap = settled - prevSettled
                    if (prevSettled >= 0 && gap in 2..4) {
                        // R215 ② 守卫：卡片位压住的条目没真正上屏，不许被补记成"看过"
                        val passed = ((prevSettled + 1) until settled)
                            .mapNotNull { p -> if (onThisDayCardActive(p)) null else items.getOrNull(p) }
                        if (passed.isNotEmpty()) vm.markSeenBatch(passed)
                    }
                    vm.onPage(settled)
                    playerPool.releaseAllExcept(items.getOrNull(pagerState.settledPage)?.key)
                    // 预载深度接跳过率：下一项所属频道常被秒滑就少预载
                    val nextChatId = items.getOrNull(pagerState.settledPage + 1)?.chatId ?: 0L
                    preloader.schedule(items, pagerState.settledPage, ahead = vm.preloadAheadFor(nextChatId))
                }

                // R94 尾页越界拖动自救（修"停在最后一页滑不动、没下一个"）：onPage 只在
                // 翻页落定时触发，停在末页继续上划永远没有新拉取——末页前向拖动越界
                // 超过阈值即主动 loadMore（其 _loading 闸与代次闸保证重复触发零副作用；
                // 触底/抽空语义由 VM 侧链式续拉与重温兜底接管）
                LaunchedEffect(pagerState) {
                    androidx.compose.runtime.snapshotFlow {
                        pagerState.currentPage to pagerState.currentPageOffsetFraction
                    }.collect { (page, offset) ->
                        val size = itemsNow.value.size
                        if (size > 0 && page >= size - 1 && offset > 0.35f) vm.loadMore()
                    }
                }

                // R159 内容感知列宽：列宽随内容形态现算（替代档位一刀切——
                // 实测华为平板横屏：横屏视频被 56% 屏宽中栏夹住，44% 屏幕在放模糊底衬）。
                // R159b 时序修正（用户实测点名"先窄后宽突然撑开"）：形态解析挂
                // currentPage（拖拽/甩动过半即翻新页）而不是 settledPage——列宽过渡
                // 与翻页滚动并发执行，落定时宽度已就位，杜绝"落定后才撑开"。
                // pager 共享列下翻页窗口内新页短暂用旧列宽渲染是固有形态，
                // 宽度过渡跟着页滑动走（跟手）而不是晚一拍。
                // 视频条目（mediaType=0）宽高比两级来源：预判 = 元数据（帧同步、
                // 加载态即生效）→ 元数据缺尺寸回落 VideoPage 上报的封面代理（封面
                // 一到即顶上）；实测 = 记账表里的 ExoPlayer 真实画面比，与预判差
                // <15% 维持预判（加载→播放不跳变），差 ≥15% 才换目标（单次过渡）。
                // R338 起图片条目（mediaType=1）横屏同语义参与（用户实锤「图片界面
                // 没有像视频界面那样做好适配特别是横屏」——横图被 56% 中栏夹住只显
                // 半屏高）：单级来源元数据比（feedPhotoShapeAspect 门），竖屏档/
                // 竖图/方图在 contentAware 内恒档位宽=现状（竖屏零变化红线）；
                // 音频卡形态已知=非视频 → 档位宽（设计红线维持）。
                // R159b 粘滞宽度：宽高比未知（元数据缺+封面未到+未起播）时不回落
                // 档位窄列，维持最近一次已知目标——空间"只放宽不收窄"升级为时间
                // 原则：任何瞬间不许出现"先窄后宽"中间态；真比到达后单次过渡到位
                val shapeItem = items.getOrNull(pagerState.currentPage)
                val knownAspect: Float? = if (shapeItem != null && shapeItem.mediaType == 0) {
                    val meta = if (shapeItem.width > 0 && shapeItem.height > 0) {
                        shapeItem.width.toFloat() / shapeItem.height
                    } else 0f
                    val rec = videoAspectBoard[shapeItem.key]
                    val predicted = if (meta > 0f) meta else rec?.first ?: 0f
                    val measured = if (rec?.second == true) rec.first else 0f
                    when {
                        measured > 0f && predicted > 0f &&
                            kotlin.math.abs(measured - predicted) / predicted < 0.15f -> predicted
                        measured > 0f -> measured
                        predicted > 0f -> predicted
                        else -> null // 两级来源都未知：交由粘滞兜底
                    }
                } else feedPhotoShapeAspect(
                    // R338 图片档门（纯函数单口）：图片给比/缺尺寸 0f=档位宽兜底；
                    // null（视频走上面两级链/音频/无条目）→ 0f 原链逐字节现状
                    shapeItem?.mediaType ?: -1,
                    shapeItem?.width ?: 0,
                    shapeItem?.height ?: 0,
                ) ?: 0f // 音频/无条目：形态已知 → 档位宽
                // 粘滞记忆：最近一次已知形态的目标（null = 冷启动首帧前）。
                // 写入走 SideEffect（组合完成后）：组合体内直写状态是 backwards-write
                // 坏味道（写同值虽可收敛，但会多一轮重组）
                val stickyTarget = remember { mutableStateOf<Dp?>(null) }
                val sticky = stickyTarget.value
                val targetMaxWidth = when {
                    knownAspect != null -> contentAwareFeedMaxWidth(
                        ws, LocalConfiguration.current.screenWidthDp, LocalConfiguration.current.screenHeightDp,
                        knownAspect,
                    )
                    // 未知比：维持当前列宽（粘滞）；冷启动无记忆才用档位宽
                    sticky != null -> sticky
                    else -> contentAwareFeedMaxWidth(
                        ws, LocalConfiguration.current.screenWidthDp, LocalConfiguration.current.screenHeightDp, 0f,
                    )
                }
                // R338：图片页的粘滞写值=档位宽（改前同值——组合期现算；SideEffect
                // 的 lambda 非组合上下文不可调 @Composable LocalConfiguration，先提出）
                val photoStickySeed = feedContentMaxWidth(ws, LocalConfiguration.current.screenWidthDp)
                SideEffect {
                    if (knownAspect != null) {
                        // R338：图片页的形态比只驱动列宽现算，不进粘滞账——粘滞是
                        // 视频未知比的兜底记忆；图片页维持改前记账值（档位宽，
                        // feedPhotoShapeAspect 缺尺寸档同值），「视频未知比紧跟图片页
                        // 的种子值」逐字节现状（视频零变化红线）
                        stickyTarget.value = if (shapeItem?.mediaType == 1) {
                            photoStickySeed
                        } else {
                            targetMaxWidth
                        }
                    }
                }
                // 动画：单次 tween（翻页换形态/起播校正换目标共用一条过渡；R178 收编
                // AnimSlow——布局重排族三档节奏，300 裸字面量不再自造第二种节奏）。
                // 目标为 Infinity（手机竖屏）不经动画直接取值——Infinity 不可插值，
                // 且旋转本身是 config 变化触发全量重组，无需动画衔接
                // 动画：单次 tween（翻页换形态/起播校正换目标共用一条过渡）。
                // 目标为 Infinity（手机竖屏）不经动画直接取值——Infinity 不可插值，
                // 且旋转本身是 config 变化触发全量重组，无需动画衔接
                val contentMaxWidth = if (targetMaxWidth == Dp.Infinity) targetMaxWidth
                else animateDpAsState(
                    targetMaxWidth,
                    tween(dev.tgfeed.ui.theme.TgFeedTokens.AnimSlow),
                    label = "R159内容列宽",
                ).value
                // R159 观测日志：列宽目标只在"形态页/目标值变化"时打一行（LaunchedEffect
                // 键控去抖），真机复测可直接 logcat 对照（华为平板预期：横屏片 1121dp/92% 屏宽，
                // 竖屏片/图文/音频 683dp/56%）；粘滞兜底命中时 aspect 标"未知(粘滞)"
                LaunchedEffect(shapeItem?.key, targetMaxWidth) {
                    if (shapeItem != null) {
                        android.util.Log.i(
                            "R159",
                            "列宽目标 key=…${shapeItem.key.takeLast(12)} type=${shapeItem.mediaType} " +
                                "meta=${shapeItem.width}x${shapeItem.height} aspect=" +
                                (if (knownAspect != null) "%.3f".format(knownAspect) else "未知(粘滞)") +
                                " 目标=%.0fdp".format(targetMaxWidth.value),
                        )
                    }
                }
                // R83 动态模糊填充（抖音同款）：视频留边时用 VideoPage 上报的当前播放帧
                //（起播前回落封面帧）模糊放大铺满整个窗口（含限宽列之外的两侧），
                // 空区颜色跟随视频内容走、换条视频跟着换。不叠黑罩压暗——
                // 旧版"灰罩"发灰正是黑罩压出来的，这里只模糊不压暗。
                // 图源是短边 ~120px 的小图：模糊后无人看细节，读回/模糊开销都可忽略。
                // 填满（ZOOM）模式 VideoPage 不上报，无底衬照旧。
                // R83 动态模糊填充（抖音同款）：视频留边时用 VideoPage 上报的当前播放帧
                //（起播前回落封面帧）模糊放大铺满整个窗口（含限宽列之外的两侧），
                // 空区颜色跟随视频内容走、换条视频跟着换，不叠黑罩压暗；填满（ZOOM）
                // 模式 VideoPage 不上报，无底衬照旧。换主时机锚定不变：currentPage
                // 过半翻转（与列内封面占位/首帧同拍触发，R160——全程有底衬、无黑窗）。
                // R194①② 双镜像播放（架构师 17:00 终案，用户原话「背景图就像全屏版
                // 的播放视频——前台播放视频，后台跟着做模糊背景地播放，两画面同步，
                // 用户无感」）：底衬源=VideoPage 的 TextureView 节流快照（~12fps，镜像
                // 帧 SNAP 直换、模糊掩盖低刷率）；换主/live 首拍=SOFT 无黑谷软切
                //（AnimMed）；离散轨道切换（源/tab）=THROUGH_DARK 暗场溶解——三模式
                // 见 BackdropStackFade。旧 R89 预览条供图路径退役（1.5s 粒度跳帧+
                // 交叉渐变=「切 tab 疯狂闪烁追赶」病根）；旧 Crossfade 的中点 25% 黑底
                // 渗出谷=「还是突然闪一下」本体（javap 实证，见组件头注释）。
                val backdropNow = backdropCover
                // R194① 渲染层降采样：模糊层以短边≈144px 离屏分辨率渲染再放大
                // 铺满（源图短边本就 ~120px ≈ 原生，零细节损失）——软切窗双层
                // 模糊的 GPU 像素量随分辨率平方下降（两张全屏模糊位图叠加的
                // 掉帧嫌疑项归零）。倍率与目标短边见 BackdropPolicy（单测锚定）
                val backdropDownFactor = with(androidx.compose.ui.platform.LocalDensity.current) {
                    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
                    dev.tgfeed.ui.BackdropPolicy.backdropDownsampleFactor(
                        kotlin.math.min(cfg.screenWidthDp, cfg.screenHeightDp).dp.toPx(),
                    )
                }
                dev.tgfeed.ui.components.BackdropStackFade(
                    target = backdropNow,
                    mode = backdropMode,
                    // SOFT 淡入/暗场亮起档（换主/live 首拍/离散收口）；镜像 SNAP 不走时长
                    enterSpec = androidx.compose.animation.core.tween(dev.tgfeed.ui.theme.TgFeedTokens.AnimMed),
                    // null 清屏与暗场暗下档（退场 ≤ 进场纪律）
                    exitSpec = androidx.compose.animation.core.tween(dev.tgfeed.ui.theme.TgFeedTokens.AnimFast),
                    modifier = Modifier.fillMaxSize(),
                ) { frame ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            // 外层 f 倍放大铺满（左上角锚定）：内层 1/f 尺寸布局，
                            // blur 与 1.3 放大在内层坐标系等比折算——RenderEffect
                            // 作用于节点自身分辨率，放大发生在合成期
                            .graphicsLayer {
                                scaleX = backdropDownFactor
                                scaleY = backdropDownFactor
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                            },
                    ) {
                        Image(
                            bitmap = frame,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize(1f / backdropDownFactor)
                                // 放大 1.3 倍遮住模糊边缘的羽化带；blur 仅 Android 12+ 生效，
                                // 低版本退化为放大铺色（小图双线性放大本身已近色块，观感可接受）
                                .graphicsLayer {
                                    scaleX = 1.3f
                                    scaleY = 1.3f
                                }
                                .blur(28.dp / backdropDownFactor),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }

                VerticalPager(
                    state = pagerState,
                    modifier = if (contentMaxWidth == androidx.compose.ui.unit.Dp.Infinity) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier
                            .widthIn(max = contentMaxWidth)
                            .fillMaxHeight()
                            .align(Alignment.Center)
                    },
                ) { page ->
                    // R156 稳定性：屏蔽/不感兴趣经 CAS 同步缩表时，pager 缓存页可能晚一拍
                    // 重组，旧下标越过新表长度直接 IndexOutOfBounds 崩进程——越界即弃帧
                    val item = items.getOrNull(page) ?: return@VerticalPager
                    // 翻页过渡（抖音观感）：整页随滚动偏移轻微缩放 + 透明度渐变——
                    // 新页带着 0.94x/半透明从下方长回 1x/不透明，离屏页对称收缩淡出。
                    // 偏移计算放进 graphicsLayer 块内：滚动期间只触发重绘，不触发重组
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                // 0 = 本页落定；±1 ≈ 相邻页（上滑时下方新页偏移为负）
                                val pageOffset = (pagerState.currentPage - page) +
                                    pagerState.currentPageOffsetFraction
                                val t = kotlin.math.abs(pageOffset).coerceIn(0f, 1f)
                                val s = 1f - 0.06f * t
                                scaleX = s
                                scaleY = s
                                alpha = 1f - 0.45f * t
                            },
                    ) {
                    // R215 ② 那年今日卡：注入位整页换装（原条目被压住不渲染不进 seen
                    // 底账——settledPage 效果的卡片位守卫兜着）。同页过渡复用外层 Box
                    // 的 graphicsLayer（缩放/渐隐与正常页一致）
                    val otdCard = onThisDayCard
                    if (otdCard != null && page == ON_THIS_DAY_FEED_CARD_PAGE && vm.source === otdCard.hostSource) {
                        FeedOnThisDayCardPage(
                            card = otdCard,
                            gateway = gateway,
                            onOpen = { target ->
                                onThisDayCard = null // 点开即消费：进那年今日播放流，翻回不重现（开场卡 R141 同语义）
                                android.util.Log.i(
                                    "R215",
                                    "那年今日卡点开: 进入一次性播放流 target=${target.key.takeLast(12)} batch=${otdCard.memory.items.size}",
                                )
                                onPlayOnThisDay(
                                    target,
                                    otdCard.memory.items,
                                    "${otdCard.memory.yearsAgo} 年前的今天",
                                )
                            },
                        )
                    } else
                    // 按内容类型分派卡片：0 视频 / 1 图片（幻灯片） / 2 音频
                    when (item.mediaType) {
                    1 -> dev.tgfeed.ui.components.PhotoCard(
                        item = item,
                        isActive = page == pagerState.settledPage,
                        // 上报口保留（解码位图如实上报，liveFrame 恒 false 无帧流；图集
                        // 只认当前显示页——卡内判定）。R194①② 图文卡同镜像口径（架构师
                        // 终案④ 同图双绘）曾由此收养为全屏模糊底衬——R336 起收养门按
                        // 媒体类型恒拒图片项上报（2026-10-22 用户拍板 A 方案：图片页
                        // 底衬退役=纯深色背景+清晰图单层，华为平板两层皮实锤）；清场由
                        // 上方 R336 观察者驱动（见 BackdropPolicy 注记）
                        onBackdropChange = { cover, liveFrame ->
                            adoptBackdrop(page, item, cover, liveFrame)
                        },
                        backdropReportGen = backdropAdoptGen.intValue,
                        // R194⑥ 顶栏实测底缘（悬停式顶锚预留基准，与视频卡同口径）
                        topChromeBottomPx = topBarBottomPx.toFloat(),
                        gateway = gateway,
                        liked = item.key in likes,
                        onToggleLike = { vm.toggleLike(item) },
                        favorited = item.key in favorites,
                        downloads = downloadsState,
                        onToggleFavorite = { vm.toggleFavorite(item) },
                        onDownload = { vm.download(item) },
                        // R299 B2/B3：查看器保存原图（按当前显示页条目，图集内横滑后
                        // 保存的是正在看的那张）+ 条漫断点续读 + 上一话/下一话
                        //（深索引毫秒级邻话查询；deepIndex 未覆盖该频道时 null=无更多话）
                        onSavePhoto = { v -> vm.download(v) },
                        comicProgressOf = { v -> vm.comicProgressOf(v) },
                        onSaveComicProgress = { v, px -> vm.saveComicProgress(v, px) },
                        chapterNavigator = { pages, dir -> vm.comicChapterNeighborImpl(pages, dir) },
                        // 同相册其余条目合成图集卡（横滑翻页）
                        siblings = if (item.albumId != 0L) {
                            items.filter { it.albumId == item.albumId && it.key != item.key }
                                .sortedBy { it.messageId }
                        } else {
                            emptyList()
                        },
                        onOpenChannel = {
                            sourceTitle = item.channelTitle
                            vm.switchSource(ChannelFeedSource(gateway, item.chatId))
                        },
                        // R167 主路改道→R222 入口整合：#话题的语义是"全 TG 频道带该标签
                        // 的内容"——深索引 hashtag 精确词+服务端补位的混合搜索源（原路径，
                        // 链体零改动）。R222 起点标签先弹双选弹层（时间轴探索/搜索播放），
                        // 原直达链路收进弹层「搜索播放」行（整合不是替换）
                        onSearchTag = { tag -> hashtagChoiceTag = tag },
                        // 信息区：频道头像 / "追"快捷钮 / caption 链接与@（与视频页同一套接线）
                        avatarLoader = avatarLoader,
                        subscribed = item.chatId in subscribedIds,
                        onSubscribe = {
                            vm.addSubscription(
                                dev.tgfeed.core.td.ChannelBrief(item.chatId, item.channelTitle, null),
                            )
                        },
                        onOpenLink = openCaptionLink,
                        onOpenMention = openCaptionMention,
                        // R269/R301 「在星图中定位」：R234「你在这里」宇宙坐标行——
                        // **R301 起=开星图舷窗**（feed 内半屏 sheet 不跳页：邻域快照+
                        // 脉动光环+邻星+时期；跳图降级为舷窗内首键「进入星图」，
                        // design-dual-form §2 终审批准的行为变更）；与视频页同一回调；
                        // 音频卡无坐标行（BottomInfoPanel 不在其内）由长按面板格覆盖；
                        // R302：Lv7 舷窗门——未到级回退旧跳图链（openGalaxyEntry 单口）
                        onLocateInGalaxy = { openGalaxyEntry(item) },
                        // 长按画面弹功能面板（面板宿主在本屏底部，与视频卡同一块）
                        onShowPanel = { mediaPanelTarget = item },
                        // 一键翻译标题（与视频卡同一链路：结果进 translations，BottomInfoPanel 展示）
                        translatedCaption = translations[item.key],
                        onTranslate = { v ->
                            if (translations[v.key] == null) {
                                scope.launch {
                                    val t = feedActions.translateCaption(v)
                                    if (!t.startsWith("翻译失败")) {
                                        translations = translations + (v.key to t)
                                    } else {
                                        android.widget.Toast.makeText(context, t, android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        // 广场角标：全站在看/点赞（与视频卡同口径）
                        plazaWatching = plaza?.watching?.get(item.key) ?: 0,
                        plazaLikes = plaza?.likes?.get(item.key) ?: 0,
                        // R238 评论媒体流入口（与视频卡同口径：VM 契约返回 Toast 文案）
                        onOpenComments = { v ->
                            scope.launch {
                                val msg = vm.openCommentStream(v)
                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        // R174 图文同语义（提案 §3.4）：pinch 渐隐上抛顶栏（与视频卡同守卫同通道）
                        onOverlayStateChange = { hidden, chromeAlpha ->
                            if (page == pagerState.settledPage) {
                                hideTopBar = hidden
                                feedChromeAlpha = chromeAlpha
                            }
                        },
                        onAutoNext = {
                            // 图文观看进画像：幻灯片走完一轮按完播记
                            vm.recordMediaView(item, 4000)
                            // 回看豁免：手动翻回过的条目不再自动跳页（与视频链同一状态机）；
                            // 长按功能面板开着时不许自动跳页（修"面板开着 Feed 自己跳走"）
                            if (autoAdvance && !vm.isAutoAdvanceExempt(item.key) &&
                                page < items.lastIndex && mediaPanelTarget == null
                            ) {
                                // 登记"被自动弹走的 key + 计划落点"：翻回即消费豁免
                                vm.noteAutoEjected(item.key, items.getOrNull(page + 1)?.key)
                                scope.launch { pagerState.animateScrollToPage(page + 1) }
                            }
                        },
                    )
                    2 -> dev.tgfeed.ui.components.AudioCard(
                        item = item,
                        isActive = page == pagerState.settledPage,
                        gateway = gateway,
                        // v7 音乐播放器级：共享播放器（卡片销毁音频继续放，迷你条接管）
                        // + 播放态上报（迷你条/队列数据源 nowPlayingAudio/audioPlaying）
                        sharedPlayer = audioPlayer,
                        onPlayingChanged = { v, playing -> vm.reportAudioPlaying(v, playing) },
                        // 后台行为=暂停时退后台音频卡也停（容器状态，MainActivity 置位）
                        backgroundPause = (context.applicationContext as dev.tgfeed.App).container.audioCardsPaused.value,
                        // v6 音频卡重做：底部操作排（点赞/收藏/下载/分享）与视频卡同一套回调
                        liked = item.key in likes,
                        favorited = item.key in favorites,
                        downloads = downloadsState,
                        onToggleLike = { vm.toggleLike(item) },
                        onToggleFavorite = { vm.toggleFavorite(item) },
                        onDownload = { vm.download(item) },
                        // R201 分享枢纽：底排分享钮改开枢纽小菜单（复制链接/分享到其他
                        // 应用/分享卡片三选一）——原「点按即复制链接」的单一语义收编进
                        // 菜单；链体见下方 shareHubTarget 枢纽接线
                        onShare = { v -> shareHubTarget = v },
                        onOpenQueue = { showAudioQueue = true },
                        // B2 配套返回箭头：仅"从搜索点结果进播放"且本卡是落定页时传入——
                        // 语义 = 退出音频播放态、回搜索结果列表（与 MainActivity 的
                        // searchReturnPending BackHandler 走同一条通道，结果在 VM 里还在不重搜）。
                        // 箭头显示期间顶栏已被下方 searchAudioPlaying 条件隐藏，二者不同屏；
                        // 普通浏览（非搜索跳入）传 null，卡片不渲染返回钮，行为与旧版一致
                        onBack = if (appContainer.searchReturnPending.value && page == pagerState.settledPage) {
                            {
                                appContainer.searchReturnPending.value = false
                                appContainer.openSearch(appContainer.pendingSearchQuery.value)
                            }
                        } else {
                            null
                        },
                        // 长按卡面弹功能面板（面板宿主在本屏底部，与视频卡同一块）
                        onShowPanel = { mediaPanelTarget = item },
                        // 弹幕：getComments 对音频消息同样成立；与视频卡同一合并口径
                        //（remember 缓存保证引用稳定，弹幕引擎不因无关重组重启）。
                        // 注意：AudioCard 弹幕层仍是纯文本入口（本任务边界外不动 AudioCard），
                        // 带时间戳的 DanmakuItem 在此降维成 text——音频卡无视频进度，不走时机映射
                        danmakuComments = remember(item.key, commentsMap, plaza) {
                            (commentsMap[item.key] ?: emptyList()).map { it.text } +
                                (plaza?.danmaku?.get(item.key) ?: emptyList())
                        },
                        danmakuEnabled = danmakuOn,
                        // 广场角标：全站在看/点赞（与视频卡同口径）
                        plazaWatching = plaza?.watching?.get(item.key) ?: 0,
                        plazaLikes = plaza?.likes?.get(item.key) ?: 0,
                        // 上一曲/下一曲：Feed 前/后翻一页
                        onPrevTrack = {
                            if (page > 0) scope.launch { pagerState.animateScrollToPage(page - 1) }
                        },
                        onNextTrack = {
                            if (page < items.lastIndex) scope.launch { pagerState.animateScrollToPage(page + 1) }
                        },
                        // 音频来源出处：跳该频道（保持当前类型 tab：音频 tab 跳过去仍是音频流）
                        onOpenChannel = {
                            sourceTitle = item.channelTitle
                            vm.switchSource(ChannelFeedSource(gateway, item.chatId))
                        },
                        onEnded = {
                            // 音频播完进画像
                            vm.recordMediaView(item, item.durationSec * 1000L)
                            // 循环单条：共享播放器已由外层 ENDED 监听归零重播，不再跳页
                            // R299 B1：本次播完被听单认领（队列连播已接管，ENDED 仲裁
                            // 收口在进程级控制器且先于卡片监听跑）→ Feed 连播让位
                            val queueClaimed =
                                appContainer.listenQueueController.lastEndedClaimKey == item.key
                            // 回看豁免：手动翻回过的条目不再自动跳页（与视频链同一状态机）
                            if (!queueClaimed && !loopOne && autoAdvance && !vm.isAutoAdvanceExempt(item.key) &&
                                page < items.lastIndex
                            ) {
                                vm.noteAutoEjected(item.key, items.getOrNull(page + 1)?.key)
                                scope.launch { pagerState.animateScrollToPage(page + 1) }
                            }
                        },
                    )
                    else -> VideoPage(
                        item = item,
                        isActive = page == pagerState.settledPage,
                        // R160 加载态一次成型：本页成为"当前页"（拖拽/甩动过半即翻转）的信号，
                        // 与 R159 列宽形态解析同一时机——底衬在翻页过半瞬间换主，
                        // 与列宽 tween、页滑动三者并发执行，落定时全部就位
                        isCurrent = page == pagerState.currentPage,
                        gateway = gateway,
                        playerPool = playerPool,
                        liked = item.key in likes,
                        favorited = item.key in favorites,
                        downloads = downloadsState,
                        muted = muted,
                        speed = speed,
                        speedLabel = formatSpeed(speed),
                        onToggleLike = { vm.toggleLike(item) },
                        onToggleFavorite = { vm.toggleFavorite(item) },
                        onDownload = { vm.download(item) },
                        onToggleMute = { muted = !muted },
                        onCycleSpeed = {
                            // 操作栏循环 1x → 1.5x → 2x；面板设的非常规档位（0.75 等）一键回到 1x
                            speed = SPEED_STEPS[(SPEED_STEPS.indexOfFirst { it == speed } + 1) % SPEED_STEPS.size]
                        },
                        onSetSpeed = { speed = it },
                        // 不感兴趣是视频粒度（屏蔽单条并降权，不是封频道）
                        onNotInterested = { vm.toggleNotInterested(it) },
                        onPopout = { v ->
                            onPopout(v)
                            playerPool.pause(v.key) // 弹出悬浮窗后 Feed 里暂停，避免双重出声
                        },
                        onAddToGrid = { v ->
                            onAddToGrid(v)
                            android.widget.Toast.makeText(context, "已加入分屏片单", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onWatchLater = { vm.toggleWatchLater(it) },
                        // 续播：起播恢复到上次位置；离开时上报
                        progressToRestore = vm.getProgress(item.key),
                        onSaveProgress = { v, ms -> vm.saveProgress(v, ms) },
                        // 转存到 Telegram 收藏夹 / 一键翻译
                        onForward = { v ->
                            scope.launch {
                                val msg = feedActions.forwardToSaved(v)
                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        onTranslate = { v ->
                            if (translations[v.key] == null) {
                                scope.launch {
                                    val t = feedActions.translateCaption(v)
                                    if (!t.startsWith("翻译失败")) {
                                        translations = translations + (v.key to t)
                                    } else {
                                        android.widget.Toast.makeText(context, t, android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        translatedCaption = translations[item.key],
                        onOverlayStateChange = { hidden, chromeAlpha ->
                            // 守卫：只采纳当前生效页的上报（旋转时非活跃页可能发来陈旧的清屏/全屏事件）
                            if (page == pagerState.settledPage) {
                                hideTopBar = hidden
                                feedChromeAlpha = chromeAlpha
                            }
                        },
                        fillToScreen = fillToScreen,
                        onSetFillMode = { fillToScreen = it },
                        // 清晰度偏好（全局）：状态镜像驱动重组，当前条即切即换
                        qualityHeight = qualityHeight,
                        onSetQuality = {
                            prefs.qualityHeight = it
                            qualityHeight = it
                        },
                        loopOne = loopOne,
                        onToggleLoop = { loopOne = it },
                        // H7 弹幕列表引用稳定：commentsMap+plaza 的合并结果按 key 缓存。
                        // 旧写法每次重组都新建 List（downloads 1Hz 进度、plaza 60s 快照、
                        // 点赞/收藏都会触发），DanmakuOverlay 里 remember(comments) 重洗队列、
                        // LaunchedEffect(laneIndex, queue, widthPx) 取消并重启全部轨道协程
                        // → 弹幕视觉整体重置。引用稳定后，数据没变就复用旧实例，引擎不重启。
                        danmakuComments = remember(item.key, commentsMap, plaza) {
                            (commentsMap[item.key] ?: emptyList()) +
                                // 广场弹幕没有评论时间戳（PlazaSnapshot.danmaku 是纯文本）：
                                // 锚到视频发布时刻 → 真实间隔映射下落在视频开头段
                                //（与旧版"拼列表尾部 → 反转后落在开头段"的观感一致）
                                (plaza?.danmaku?.get(item.key) ?: emptyList()).map { dmText ->
                                    dev.tgfeed.core.td.DanmakuItem(
                                        text = dmText,
                                        timestampSec = item.dateEpochSec.toLong(),
                                        isReply = false,
                                    )
                                }
                        },
                        danmakuEnabled = danmakuOn,
                        // 播放页操作栏弹幕开关：写 prefs 持久化 + 翻转本地状态即时生效
                        onToggleDanmaku = {
                            val next = !danmakuOn
                            prefs.danmakuEnabled = next
                            danmakuOn = next
                        },
                        plazaWatching = plaza?.watching?.get(item.key) ?: 0,
                        plazaLikes = plaza?.likes?.get(item.key) ?: 0,
                        onBackdropChange = { cover, liveFrame ->
                            // R194①② 统一收口（视频卡/图文卡双消费面共用体，上方
                            // adoptBackdrop：采纳守卫/冻结闸/换主记账/模式裁决单一实现
                            // ——R160 时机锚定（currentPage 过半）与二修反向翻页竞态
                            // 根治的守卫语义都在共用体内，规则矩阵见 BackdropPolicy）
                            adoptBackdrop(page, item, cover, liveFrame)
                        },
                        // R194② 轨道切换冻结窗代次（VideoPage 上报 effect 键挂它——
                        // 解冻时代次+1 = 当前页重报最新帧，冻结缓收的换血一次补齐）
                        backdropReportGen = backdropAdoptGen.intValue,
                        // R194⑥ 顶栏实测底缘（悬停式顶锚预留基准）
                        topChromeBottomPx = topBarBottomPx.toFloat(),
                        // R201 分享枢纽：视频页的「复制链接」回调随长按面板该行收编删除
                        //（链体移驻下方 shareHubTarget 枢纽接线的 onCopyLink——剪贴板
                        // +Toast 原文原样）；面板侧不再有独立的复制链接入口
                        // 用户 2026-10-16 裁决沿革→R201 整合：右轨分享原直跳 ACTION_SEND
                        //（分享链接/分享到其他应用），现点按弹枢纽小菜单三选一——三个
                        // 分享能力入口唯一化，链体零改动
                        onOpenShareHub = { v -> shareHubTarget = v },
                        // R227 画面调整枢纽：长按面板「画面调整」格（原「旋转」+
                        //「镜像翻转」两格收编）→ FrameAdjustHubSheet 四选一小菜单
                        //（向左/向右旋转/左右镜像/恢复默认；复合态记忆 FrameAdjustStore）
                        onOpenFrameAdjust = { v -> frameAdjustTarget = v },
                        onSendDanmaku = { v -> danmakuTarget = v },
                        // R205 TL-9 弹幕回放：长按面板「弹幕回放」格 → 时间轴回放面板
                        onOpenDanmakuTimeline = { v -> danmakuTimelineTarget = v },
                        // R204 DS-5 首发溯源：长按面板「首发溯源」格 → 溯源面板
                        onOpenFirstSeen = { v -> firstSeenTarget = v },
                        // R202 TL-4 事件考古：长按面板「事件考古」格 → 时光机页
                        //（外层容器路由；±48h 全网横截面）
                        onOpenEventDig = { v -> onOpenTimeMachine(v) },
                        // R233④/R301 在星图中查看：**R301 起=开星图舷窗**（feed 内不
                        // 跳页看见位置——design-dual-form §2；跳图链由舷窗内首键承接）；
                        // R302：Lv7 舷窗门——未到级回退旧跳图链（openGalaxyEntry 单口）
                        onOpenTasteMap = { v -> openGalaxyEntry(v) },
                        autoAdvance = autoAdvance,
                        onSetAutoAdvance = { vm.setAutoAdvance(it) },
                        onSetSleepTimer = { vm.setSleepTimer(it) },
                        sleepNow = vm.sleepNow,
                        onOpenChannel = {
                            sourceTitle = item.channelTitle
                            vm.switchSource(ChannelFeedSource(gateway, item.chatId))
                        },
                        // 右栏头像点击 → 频道主页（App 容器路由；主页里点媒体回跳走
                        // vm.openChannelAt 链路，与"查看上下文"同一通道）
                        onOpenChannelHome = { onOpenChannelHome(item) },
                        // R159 内容感知列宽：本页宽高比上报入记账表。预判（measured=false）
                        // 不覆盖实测——实测一旦入表（可信级最高），翻回本页永远用实测值
                        onVideoAspectChange = { aspect, measured ->
                            val prev = videoAspectBoard[item.key]
                            if (measured || prev?.second != true) {
                                if (videoAspectBoard.size > 512) videoAspectBoard.clear()
                                videoAspectBoard[item.key] = aspect to measured
                            }
                        },
                        // 查看上下文：切该频道 Feed 并定位到本条（锚点源保证首页含目标；
                        // 定位滚动由上方 contextJumpKey effect 完成）
                        onViewContext = { v ->
                            sourceTitle = v.channelTitle
                            vm.openChannelAt(v)
                        },
                        // R167 主路改道→R222 入口整合：与图文卡（onSearchTag）同一接线——
                        // 深索引 hashtag 精确词+服务端补位混合搜索为原路径（链体零改动），
                        // R222 起点标签先弹双选弹层（时间轴探索/搜索播放），详见上处注释
                        onSearchTag = { tag -> hashtagChoiceTag = tag },
                        // 信息区：频道头像 / "追"快捷钮 / caption 链接与@（与图文卡同一套接线）
                        avatarLoader = avatarLoader,
                        subscribed = item.chatId in subscribedIds,
                        onSubscribe = {
                            vm.addSubscription(
                                dev.tgfeed.core.td.ChannelBrief(item.chatId, item.channelTitle, null),
                            )
                        },
                        onOpenLink = openCaptionLink,
                        onOpenMention = openCaptionMention,
                        onEnded = {
                            // 播完自动滑到下一个（自动连播开关关闭时停在当前条）。
                            // VideoPage 的 ENDED 监听已先查回看豁免（命中即原地定格，
                            // 根本不会走到这里）；走到这里 = 确认弹走，登记"被自动弹走的
                            // key + 计划落点"——用户手动翻回它时转入回看豁免，不再弹走
                            if (autoAdvance && page < items.lastIndex) {
                                vm.noteAutoEjected(item.key, items.getOrNull(page + 1)?.key)
                                scope.launch { pagerState.animateScrollToPage(page + 1) }
                            }
                        },
                        // 加载失败终态（自动重试 4 次全败）的"换一条看"：
                        // 用户显式跳过坏片，不看自动连播开关；已是最后一页则无操作
                        onSkip = {
                            if (page < items.lastIndex) {
                                scope.launch { pagerState.animateScrollToPage(page + 1) }
                            }
                        },
                    )
                    }
                    }
                }

                // ---------------- 迷你播放条 + 播放队列（音乐播放器级） ----------------
                // 在播音频且当前页不是该音频卡时显示：离开音频卡音频继续放（共享播放器
                // 归本页持有、卡片销毁不停），迷你条接管展示与控制；点条体跳回该音频条目
                val nowPlaying = nowPlayingAudio
                if (nowPlaying != null &&
                    items.getOrNull(pagerState.settledPage)?.key != nowPlaying.key
                ) {
                    dev.tgfeed.ui.components.MiniAudioPlayerBar(
                        item = nowPlaying,
                        playing = audioPlaying,
                        player = audioPlayer,
                        // R299 B1：队列 N 首角标（design §2.1 迷你条同步显示听单存量）
                        queueSize = listenQueueItems.size,
                        onClick = {
                            val idx = items.indexOfFirst { it.key == nowPlaying.key }
                            if (idx >= 0) scope.launch { pagerState.scrollToPage(idx) }
                        },
                        onQueueClick = { showAudioQueue = true },
                        // 关闭迷你条：停播共享播放器 + 清在播态（条消失）
                        onClose = {
                            audioPlayer.pause()
                            vm.clearNowPlayingAudio()
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            // R226 用户实锤主案：迷你播放条贴屏底正好埋进三导航图标（点按
                            // 先命中导航）——consume LocalBottomBarInset，dock 可见时抬到
                            // 栏上沿（56/44dp 同拍动画），清屏/全屏时归零照常贴底
                            .avoidBottomBar()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
                // 播放队列 sheet（R299 B1 双段）：上段=可编辑持久听单（点跳播/移除/
                // 清空/存为片单），下段=当前列表音频投影（点跳 Feed 定位+行尾插队/入听单）
                if (showAudioQueue) {
                    val listenQueue = appContainer.listenQueueController
                    dev.tgfeed.ui.components.AudioQueueSheet(
                        audioItems = items.filter { it.mediaType == 2 },
                        playingKey = nowPlaying?.key,
                        playing = audioPlaying,
                        onJump = { target ->
                            showAudioQueue = false
                            val idx = items.indexOfFirst { it.key == target.key }
                            if (idx >= 0) {
                                vm.jumpToAudio(target)
                                scope.launch { pagerState.scrollToPage(idx) }
                            }
                        },
                        queueItems = listenQueueItems,
                        queuePlayingKey = listenQueue.playingKey,
                        onQueueJump = { target ->
                            showAudioQueue = false
                            vm.jumpListen(target)
                        },
                        onQueueRemove = { target -> vm.removeFromListen(target.key) },
                        onQueueClear = {
                            vm.clearListenQueue()
                            android.widget.Toast.makeText(context, "听单已清空", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onQueueSaveAsPlaylist = {
                            // R299 B1 整单存为片单（PlaylistMeta 底座；impl 直调——
                            // FeedViewModel.kt 在 R292 在飞面，本任务不碰该文件）
                            vm.saveListenQueueAsPlaylistImpl("听单 ${listenQueueItems.size} 首")
                            android.widget.Toast.makeText(context, "已存为片单", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onPlayNext = { target ->
                            vm.playNextInListen(target)
                            android.widget.Toast.makeText(context, "已插队为下一首", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onEnqueue = { target ->
                            val added = vm.enqueueListen(target)
                            android.widget.Toast.makeText(
                                context,
                                if (added) "已加入听单" else "已在听单中",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        },
                        onDismiss = { showAudioQueue = false },
                    )
                }
            }
        }
        } // R189 内容域转场包裹 Box 收尾（trackKey 变化=tab/换列转场）

        // R141 盲盒流开场卡：新一轮开刷时浮在流头（本轮关键词 + 摇一把 + 开刷），
        // 点「开刷」或滑离第一页即收；每次摇一把（新一轮）复位重现。
        // 拉取报错时让位给错误态（原因+动作），别把"加载失败"盖在开场卡底下；
        // 此时顶栏"摇一把"胶囊仍在，换词重抽走那条路。
        // 揭示节律按 §3.4：fadeIn AnimMed + scaleIn 0.92→1 AnimSlow
        val currentBlindKeyword = blindKeyword
        if (currentBlindKeyword != null && error == null) {
            androidx.compose.animation.AnimatedVisibility(
                visible = luckyCardVisible,
                enter = androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core.tween(dev.tgfeed.ui.theme.TgFeedTokens.AnimMed),
                ) + androidx.compose.animation.scaleIn(
                    animationSpec = androidx.compose.animation.core.tween(
                        dev.tgfeed.ui.theme.TgFeedTokens.AnimSlow,
                    ),
                    initialScale = 0.92f,
                ),
                exit = androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core.tween(dev.tgfeed.ui.theme.TgFeedTokens.AnimFast),
                ),
                modifier = Modifier.align(Alignment.Center),
            ) {
                BlindBoxOpeningCard(
                    keyword = currentBlindKeyword,
                    onShake = shakeBlindBox,
                    onDismiss = { luckyCardVisible = false },
                )
            }
        }

        // 顶栏半透明渐变浮层，最后声明压在视频之上；当前页清屏/全屏时隐藏
        // B2 修复（覆盖层路由没藏主层 chrome）：从搜索点结果跳进音频播放页时，
        // 音频页是全屏播放器形态（进度条/控制排/返回箭头齐备），主层顶栏必须隐藏——
        // 否则两层同在互叠，且顶栏标题还错显示"推荐"。限定"搜索跳入 + 落定页是音频"
        // 两个条件：视频/图文卡本就是叠加顶栏的浏览形态，搜索会话内滑到它们时
        // 顶栏照常可用；按返回键回搜索页时 searchReturnPending 由 MainActivity
        // 清掉，顶栏随之恢复
        // （R173：searchAudioPlaying 计算上提到 appContainer 声明附近——底栏沉浸
        // 信号与顶栏门控共用同一表达式，两处各写一份会漂移）
        if (!hideTopBar && !searchAudioPlaying) {
        // R174 捏合清屏连续映射：顶栏+快拍条整组随页内 chrome α 跟手渐隐（α=0 时
        // hideTopBar 已置位整块退组合——两口径零跳变衔接）。graphicsLayer 块内读
        // 状态只重记录层不触发本屏重组（60fps 跟手零重组开销）
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = feedChromeAlpha },
        ) {
        // R324 离线降级提示行（feed 面·降级档）：离线+有内容时顶部胶囊「离线中 ·
        // 展示的是 N 小时前的快照」（时点=max(快照落盘时刻, 本会话最近到货)——零
        // 编造：无时点只说「离线前拉到的内容」）；列表空时本行不出（空态副文案接
        // 管，见 FeedEmptyState offlineHint）；在线恒不显示。落位 108dp：避让 2x 胶囊
        // 带（56dp）与续播提示（148dp），与 ±10s 步进（100dp·瞬态 700ms）容忍短叠。
        // 提示行随 chrome 渐隐组走（清屏=用户主动要干净画面，提示行同退）。
        dev.tgfeed.core.net.OfflineDegradePolicy.feedOfflineBannerLine(
            online = networkOnline,
            itemsEmpty = items.isEmpty(),
            dataAtMs = maxOf(snapshotSavedAtMs, lastFreshAtMs),
            nowMs = System.currentTimeMillis(),
        )?.let { offlineLine ->
            OfflineHintPill(
                text = offlineLine,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 108.dp),
            )
        }
        FeedTopBarOverlay(
            title = sourceTitle,
            channels = channels,
            subscribedIds = subscribedIds,
            // R194⑥ 顶栏实测底缘（媒体带顶锚预留的基准，见上方声明处注释）
            modifier = Modifier.onSizeChanged { topBarBottomPx = it.height },
            followHasNew = followHasNew, // R66："订阅追更"chip 追更红点角标
            channelMeta = channelCardMeta, // R68："直达频道"频道卡鲜活信号行 + 排序
            // R191 手动钉住深索引：频道行「深度索引」按钮（钉住+立即建库+行内进度+取消钉）
            manualIndexRows = manualIndexRows,
            onRequestDeepIndex = { c -> vm.requestDeepIndex(c.id) },
            onCancelDeepIndexPin = { c -> vm.cancelDeepIndexPin(c.id) },
            // R189 双击刷新规则：单击已激活 = 无操作（R157「在播时单击=刷新一批」废止
            //——判定在 TopLevelTab 共享窗口状态机内，onClick 只在未激活时触发）；双击
            // 已激活 = onRefreshFeed（=vm.refresh 刷新该流，下方回调接线）。切走轨道
            // 音频按关档矩阵先停（R180 ⑥ 同族），回轨 AudioCard 接管效应自动续播
            followingActive = followingActive,
            aggregateActive = aggregateActive,
            // R239 时间线 tab（首页一等公民居中）：在场标记直读容器（时间线=feed 页内
            // overlay 层，本顶栏在该态下被 PursueScreen 同款 HomeTabsRow 页头覆盖，
            // 此参数恒语义完备）；点按=开 overlay（openPursue：回首页页位+置
            // homeTimelineOn）；双击已激活=时间线重拉 nonce（R189 双击刷新同族）
            timelineActive = appContainer.homeTimelineOn.value,
            onSelectTimeline = { appContainer.openPursue() },
            onRefreshTimeline = { appContainer.requestTimelineRefresh() },
            onSelectFollowing = {
                sourceTitle = "关注"
                pauseAudioOnTrackSwitch()
                vm.showFollowing()
            },
            onSelectAggregate = {
                sourceTitle = "推荐"
                pauseAudioOnTrackSwitch()
                vm.showAggregate()
            },
            onSelectChannel = { c ->
                sourceTitle = c.title
                vm.switchSource(ChannelFeedSource(gateway, c.id))
            },
            onSelectFavorites = {
                sourceTitle = "收藏夹"
                vm.showFavorites()
            },
            onSelectSubscriptions = {
                sourceTitle = "订阅追更"
                // R66：点"订阅追更"chip 记录本次查看时刻并清红点（账本落盘 follow_visit.json）
                vm.noteFollowChipOpened()
                vm.showSubscriptions()
            },
            onOpenCollections = { showCollections = true },
            onSelectWatchLater = {
                sourceTitle = "稍后再看"
                vm.showWatchLater()
            },
            onToggleSubscription = { c ->
                if (c.id in subscriptions.map { it.id }) vm.removeSubscription(c.id) else vm.addSubscription(c)
            },
            onGridMode = onGridMode,
            onRefreshFeed = {
                // R157：刷新手势落在"· 源名"面包屑的双击上（只刷新当前源、不动标题）——
                // 旧实现双击标题会把标题误重置成"推荐"（收藏夹/频道流/盲盒流在播时
                // 标题与源错位）；面包屑形态下源名即标题，刷新不再碰标题
                vm.refresh()
            },
            onSearch = { q ->
                // M1：搜索入口改道全屏搜索页（已加入频道 100% 覆盖的全量搜索 SearchScreen），
                // 关键词经容器 pendingSearchQuery 传递，搜索页进页自动开搜；
                // 顶栏只在显式提交（回车/历史 chip）时触发 onSearch（见 FeedTopBarOverlay）
                appContainer.openSearch(q)
            },
            // R173 源面板开窗镜像：藏快拍条（模态盖顶期间快拍条浮在 scrim 上破坏纪律）
            onSheetOpenChange = { sourceSheetOpen = it },
            searchHistory = searchHistory,
            onRecordSearch = vm::recordSearch,
            onOpenFilter = { showFilter = true },
            mediaTypeTab = mediaTypeTab,
            // R189：媒体条 band 点击（用户导航路）——切走轨道音频按关档矩阵先停；
            // setMediaTypeTab 内部走快照落袋→命中恢复/未命中 refresh；单击已激活 =
            // VM 幂等守卫直接 no-op（双击刷新判定在 band 单元格共享窗口状态机内）
            onSelectMediaType = {
                pauseAudioOnTrackSwitch()
                vm.setMediaTypeTab(it)
            },
            // R181 轨道跟手：拖拽进度上抛（tab 带高亮/指示条权重渐变）
            railDrag = railDragVisual,
            onOpenKeywordFeed = {
                sourceTitle = "关键词订阅"
                vm.showKeywordFeed()
            },
            // R141 手气不错合并入口：源面板收起后开 AppSheet 双选项卡面板（宿主在下方）
            onOpenLuckyPicker = { showLuckyPicker = true },
            // R141 盲盒流在播：顶栏骰子标 + 显式"摇一把"胶囊（换词重抽）
            blindBoxActive = blindKeyword != null,
            onShakeBlindBox = shakeBlindBox,
            onFindSimilar = { c ->
                vm.loadSimilar(c.id)
                showSimilar = true
            },
            // 频道真头像加载器（上方统一构建，与条目信息区共用；Mock 返回 null 回落字母圆）
            avatarLoader = avatarLoader,
        )

        // R144 快拍条：顶栏下方的 stories 圆环带（没看完=红环/"刚冒出来的"红环语言，
        // 看完=灰环；名称下"剩 N 小时"过期紧迫感标签）。briefs 为空 = 没有任何频道有
        // 活跃快拍（多数中小频道要 boost 等级才能发 story），整条不渲染不占位（空态优雅）。
        // 与顶栏同显隐（清屏/全屏/搜索音频页都藏）；R173 源面板开窗也藏——快拍条声明
        // 在顶栏之后，会浮在面板 scrim 之上破坏模态（feedChromeHidden 同一信号源）
        if (storyBriefs.isNotEmpty() && !sourceSheetOpen) {
            dev.tgfeed.ui.components.StoriesBar(
                briefs = storyBriefs,
                watched = storyWatched,
                titleOf = { id -> realGateway?.peekChatTitle(id) },
                avatarLoader = avatarLoader,
                onOpen = openStories,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(top = 84.dp),
            )
        }
        } // R174 chrome α 渐隐包裹 Box 收尾（顶栏+快拍条）
        }

        // 频道合集管理弹窗（实现见 ui/components/CollectionsDialog.kt；R178 左抽屉形态）
        if (showCollections) {
            dev.tgfeed.ui.components.CollectionsDialog(
                gateway = gateway,
                onDismiss = { showCollections = false },
                onSelect = { c ->
                    sourceTitle = c.name
                    vm.showCollection(c)
                    // 关窗不再自己置 false：行点击侧 sheet.dismiss 播完退场动画后
                    // 统一走 onDismiss（落组合一处收口）
                },
                // R397 批量入分屏（合集行「分屏播放」收口）：对话框只负责现拉（空结果
                // 留面板如实提示），这里种进容器片单 + 如实报账 + 开分屏——showGrid 后
                // viewMode="grid"，FeedScreen（含本抽屉）整层退场让位 GridScreen
                onBatchToGrid = { items ->
                    val added = appContainer.addBatchToGrid("合集", items)
                    android.widget.Toast.makeText(
                        context,
                        if (added == 0) "这批都已在分屏片单里"
                        else "已加入分屏 $added 条" +
                            if (added < items.size) "（重复 ${items.size - added} 条已跳过）" else "",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    showCollections = false
                    appContainer.showGrid()
                },
            )
        }

        // 相似频道弹窗
        if (showSimilar) {
            dev.tgfeed.ui.components.SimilarChannelsDialog(
                channels = similarChannels,
                loading = similarLoading,
                onOpen = { c ->
                    sourceTitle = c.title
                    vm.switchSource(ChannelFeedSource(gateway, c.id))
                    vm.clearSimilar()
                    showSimilar = false
                },
                onDismiss = {
                    vm.clearSimilar()
                    showSimilar = false
                },
            )
        }

        // 弹幕发送输入框（广场协议 [D]）
        val dmTarget = danmakuTarget
        if (dmTarget != null) {
            var dmText by remember { mutableStateOf("") }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { danmakuTarget = null },
                title = { Text("发弹幕到广场") },
                text = {
                    androidx.compose.material3.OutlinedTextField(
                        value = dmText,
                        onValueChange = { dmText = it },
                        label = { Text("说点什么…（会显示给全站用户）") },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        danmakuTarget = null
                        if (!prefs.plazaEnabled) {
                            // T0-7 修复（AUDIT-GAPS3 #7）：广场关着时 plazaPostDanmaku
                            // 静默丢弃，恒报"弹幕已发到广场"是说谎——如实提示并指路设置
                            android.widget.Toast.makeText(context, "广场未开启，请到设置开启", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            vm.plazaPostDanmaku(dmTarget, dmText)
                            android.widget.Toast.makeText(context, "弹幕已发到广场", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("发送") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { danmakuTarget = null }) { Text("取消") }
                },
            )
        }

        // R205 TL-9 弹幕时间旅行：时间轴回放面板（长按面板「弹幕回放」格置位；
        // 纯回放视图——拉广场历史弹幕流按真实时间渲染；空态诚实承接广场薄现状）
        val dmTimelineTarget = danmakuTimelineTarget
        if (dmTimelineTarget != null) {
            val dmTimelineState by vm.plazaDanmakuTimeline.collectAsState()
            dev.tgfeed.ui.components.DanmakuTimelineSheet(
                target = dmTimelineTarget,
                state = dmTimelineState,
                plazaEnabled = prefs.plazaEnabled,
                onRefresh = { vm.refreshPlazaDanmakuTimeline() },
                onDismiss = { danmakuTimelineTarget = null },
            )
        }

        // ---------------- 图文/音频卡功能面板（G9：图/音卡不再是二等公民） ----------------
        // 长按图/音卡触发（卡片只上抛 onShowPanel，面板宿主在这里）：复用视频卡
        // VideoActionSheet——稍后再看/不感兴趣/推荐/缓存（图→存图片、音→存音频）/
        // 复制链接/分享卡片/翻译/转存收藏夹/发弹幕的回调与视频卡同一套；
        // 定时关闭、自动连播、循环单条对图/音同样成立（本屏已把 sleepNow/loopOne 接到音频）。
        // 视频专属动作（悬浮播放/加入分屏）如实 Toast 说明，不做静默假动作；
        // 画面填充/清屏播放对图/音无语义、倍速对图文无语义——经 mediaType 让面板直接不渲染这些行。
        val panelItem = mediaPanelTarget
        if (panelItem != null) {
            dev.tgfeed.ui.components.VideoActionSheet(
                // 按媒体类型裁剪面板行（0=视频 1=图片 2=音频，见 VideoActionSheet.mediaType）
                mediaType = panelItem.mediaType,
                // 倍速行：音频直连共享播放器即点即生效；图文该行已被 mediaType 裁掉不渲染
                speed = if (panelItem.mediaType == 2) audioPlayer.playbackParameters.speed else 1f,
                autoAdvance = autoAdvance,
                sleepTimerMinutes = panelSleepLocal,
                onDismiss = { mediaPanelTarget = null },
                // 推荐 = 现有点赞回调（已赞时再点取消赞，与操作栏拇指行为一致）
                onRecommend = { vm.toggleLike(panelItem); mediaPanelTarget = null },
                onWatchLater = { vm.toggleWatchLater(panelItem); mediaPanelTarget = null },
                onNotInterested = { vm.toggleNotInterested(panelItem); mediaPanelTarget = null },
                // 屏蔽频道（频道级硬屏蔽，与内容级"不感兴趣"并存）：确认框在面板内弹（防误触），
                // 确认后才走到这里。toggleBlockChannel 是翻转语义——已在屏蔽表的不再重复调
                //（再调一次 = 解除屏蔽），如实 Toast 指路设置页
                onBlockChannel = {
                    if (panelItem.chatId in vm.blockedChannels.value) {
                        android.widget.Toast.makeText(
                            context, "该频道已在屏蔽列表中，可在设置里解除", android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        vm.toggleBlockChannel(panelItem.chatId)
                        android.widget.Toast.makeText(
                            context, "已屏蔽，可在设置→屏蔽的频道里解除", android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                    mediaPanelTarget = null
                },
                onCache = { vm.download(panelItem); mediaPanelTarget = null },
                // R299 B1 听单入口（仅音频宿主渲染；面板内两格不假动作）
                onEnqueueListen = if (panelItem.mediaType == 2) {
                    {
                        val added = vm.enqueueListen(panelItem)
                        android.widget.Toast.makeText(
                            context,
                            if (added) "已加入听单" else "已在听单中",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                        mediaPanelTarget = null
                    }
                } else {
                    null
                },
                onPlayNextListen = if (panelItem.mediaType == 2) {
                    {
                        vm.playNextInListen(panelItem)
                        android.widget.Toast.makeText(
                            context, "已插队为下一首", android.widget.Toast.LENGTH_SHORT,
                        ).show()
                        mediaPanelTarget = null
                    }
                } else {
                    null
                },
                onPopout = {
                    android.widget.Toast.makeText(
                        context, "悬浮播放暂仅支持视频", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    mediaPanelTarget = null
                },
                onAddToGrid = {
                    android.widget.Toast.makeText(
                        context, "分屏暂仅支持视频", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    mediaPanelTarget = null
                },
                onForward = {
                    scope.launch {
                        val msg = feedActions.forwardToSaved(panelItem)
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                    }
                    mediaPanelTarget = null
                },
                onTranslate = {
                    if (translations[panelItem.key] == null) {
                        scope.launch {
                            val t = feedActions.translateCaption(panelItem)
                            if (!t.startsWith("翻译失败")) {
                                translations = translations + (panelItem.key to t)
                            } else {
                                android.widget.Toast.makeText(context, t, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    mediaPanelTarget = null
                },
                onSetSpeed = {
                    if (panelItem.mediaType == 2) audioPlayer.setPlaybackSpeed(it)
                    mediaPanelTarget = null
                },
                onClearScreen = { mediaPanelTarget = null },
                onSetSleepTimer = { minutes ->
                    panelSleepLocal = minutes
                    vm.setSleepTimer(minutes)
                },
                onSetAutoAdvance = { vm.setAutoAdvance(it) },
                loopOne = loopOne,
                onToggleLoop = { loopOne = it },
                // R201 分享两行收编：原「复制链接」「分享卡片」两格各自直跳链路，收编为
                // 单格「分享」——点按关本面板、弹分享枢纽小菜单三选一（复制链接/分享到
                // 其他应用/分享卡片）；两条链体整体移驻下方 shareHubTarget 枢纽接线
                //（卡片链原样搬运，复制链接链改走 messageLinkOf 统一解析口）
                onShare = { shareHubTarget = panelItem; mediaPanelTarget = null },
                onSendDanmaku = {
                    danmakuTarget = panelItem
                    mediaPanelTarget = null
                },
                // R205 TL-9 弹幕回放：图/音宿主同口径（弹幕协议不挑媒体类型）
                onOpenDanmakuTimeline = {
                    danmakuTimelineTarget = panelItem
                    mediaPanelTarget = null
                },
                // R204 DS-5 首发溯源：图/音宿主同口径（fileUniqueId 不挑媒体类型）
                onFirstSeen = {
                    firstSeenTarget = panelItem
                    mediaPanelTarget = null
                },
                // R202 TL-4 事件考古：图/音宿主同口径（发布时刻锚点不挑媒体类型）
                onEventDig = {
                    onOpenTimeMachine(panelItem)
                    mediaPanelTarget = null
                },
                // R233④/R301 在星图中查看：图/音宿主同口径——**R301 起=开星图舷窗**
                //（feed 内半屏 sheet 不跳页；跳图链由舷窗内首键承接）；
                // R302：Lv7 舷窗门——未到级回退旧跳图链（openGalaxyEntry 单口）
                onOpenTasteMap = {
                    openGalaxyEntry(panelItem)
                    mediaPanelTarget = null
                },
                // 查看上下文：图/音条目同样成立（锚点源按 messageId 定位，不挑媒体类型）
                onViewContext = {
                    sourceTitle = panelItem.channelTitle
                    vm.openChannelAt(panelItem)
                    mediaPanelTarget = null
                },
                // R227 画面调整：图/音宿主不接线（画面姿态仅视频有语义）→ 面板该格
                // 不渲染（原「旋转」格在此宿主的 Toast 说明随之退役——如实隐藏优于
                // 假动作，R218 镜像格 null 不渲染同口径）
            )
        }

        // ---------------- R201 分享枢纽面板（三入口统一收口，菜单态见 ShareHubPolicy） ----------------
        // 视频右轨分享钮 / AudioCard 底排分享钮 / 图音长按面板「分享」格都置
        // shareHubTarget 弹本面板，菜单三选一。链体沿用三条既有能力链（零改动）：
        //  - 复制链接：剪贴板 + Toast「链接已复制」（原 onShareLink 链路原文原样；
        //    链接已由面板解析到手，不再二次解析）；
        //  - 分享到其他应用：ACTION_SEND chooser（原右轨直跳链路；标题按媒体类型，
        //    视频沿用「分享视频链接」原文，图/音为枢纽统一后新开的入口补齐同族文案）；
        //  - 分享卡片：ShareCard.generate + share（原图音面板链路原样搬运；条目不限
        //    媒体类型——视频=coverBytes 解码封面，图卡优先 playUri 采样解码更清晰）。
        // 无公开链接条目（私有频道/无权限）：复制/系统分享两行由菜单态如实禁用
        //（「该视频没有公开链接」口径沿用），分享卡片不受牵连（卡链 link=null 跳链接行）
        val hubItem = shareHubTarget
        if (hubItem != null) {
            dev.tgfeed.ui.components.ShareHubSheet(
                item = hubItem,
                resolveLink = { v -> messageLinkOf(v) },
                onCopyLink = { url ->
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(url))
                    android.widget.Toast.makeText(context, "链接已复制", android.widget.Toast.LENGTH_SHORT).show()
                },
                onSendToApps = { url ->
                    // R235 成就账本：分享到其他应用计入「分享 N 次」（纯本地计数——星图成就 funnel）
                    runCatching { dev.tgfeed.ui.TasteVoyageStore.bumpShare(context) }
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, url)
                    }
                    context.startActivity(
                        android.content.Intent.createChooser(
                            intent,
                            dev.tgfeed.ui.shareHubChooserTitle(hubItem.mediaType),
                        ),
                    )
                },
                onShareCard = { v ->
                    // R235 成就账本：分享卡片落相册计入「分享 N 次」（同上——本地计数 funnel）
                    runCatching { dev.tgfeed.ui.TasteVoyageStore.bumpShare(context) }
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        // 卡链沿用面板时代行为：IO 线程自行解析链接（不与菜单解析互等）
                        val link = messageLinkOf(v)
                        // 封面：图卡优先采样解码本地原图（卡片更清晰），其余回落 minithumbnail
                        val mini = v.coverBytes?.let { bytes ->
                            runCatching {
                                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }.getOrNull()
                        }
                        val cover = if (v.mediaType == 1) {
                            v.playUri
                                ?.takeIf { it.isNotBlank() && !it.startsWith("http") }
                                ?.let { p ->
                                    runCatching {
                                        decodeSharePhotoSampled(p.removePrefix("file://"))
                                    }.getOrNull()
                                } ?: mini
                        } else {
                            mini
                        }
                        val card = dev.tgfeed.core.playback.ShareCard.generate(context, v, cover, link)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            dev.tgfeed.core.playback.ShareCard.share(context, card)
                        }
                    }
                },
                onDismiss = { shareHubTarget = null },
            )
        }

        // ---------------- R227 画面调整枢纽面板（旋转+镜像统一小菜单） ----------------
        // 视频长按面板「画面调整」格（原「旋转」+「镜像翻转」两格收编）置
        // frameAdjustTarget 弹本面板，四选一：向左旋转 / 向右旋转 / 左右镜像 /
        // 恢复默认。与分享枢纽的架构差别：姿态链体就是 FrameAdjustStore 本身
        //（自持读写，宿主零接线）；托管在 FeedScreen 层与分享枢纽同位——sheet
        // 在 pager 之上，不被页面横滑联动/裁剪
        val frameAdjustItem = frameAdjustTarget
        if (frameAdjustItem != null) {
            dev.tgfeed.ui.components.FrameAdjustHubSheet(
                itemKey = frameAdjustItem.key,
                onDismiss = { frameAdjustTarget = null },
            )
        }

        // ---------------- R222 #话题双选弹层（caption 标签点击的入口整合） ----------------
        // 视频/图文两条 onSearchTag 链都置 hashtagChoiceTag 弹本面板，两选一：
        //  - 时间轴探索：容器路由开 hashtag 时间轴页（R205 引擎复用的全史时间轴，
        //    点轴上节点从那块开始播放——新路径）；
        //  - 搜索播放：原路径原样（「标签：#tag」源 + 混合双腿搜索直接开刷——
        //    R167 链体零改动，整合不是替换）。
        val choiceTag = hashtagChoiceTag
        if (choiceTag != null) {
            dev.tgfeed.ui.components.HashtagChoiceSheet(
                tag = choiceTag,
                onExploreTimeline = { onOpenHashtagTimeline(choiceTag) },
                onSearchPlay = {
                    sourceTitle = "标签：#$choiceTag"
                    vm.hashtagSearch(choiceTag)
                },
                onDismiss = { hashtagChoiceTag = null },
            )
        }

        // ---------------- R204 DS-5 首发溯源面板（点按付费：查证链在 vm.firstSeenFor） ----------------
        // 视频长按面板 / 图音长按面板的「首发溯源」格都置 firstSeenTarget 弹本面板。
        // 同指纹跨频道最早出现=首发（W4 口径：dateEpochSec 升序最早者，同 5 分钟内
        // 多发按 messageId 序兜底——SQL ORDER BY 双键同口径）；fileUniqueId 不在任何
        // 索引→查证是全表扫（秒级~十秒级），用户点按才触发 + 三级缓存（会话内存→
        // 磁盘账本 LRU 512→扫完回写）+「重新查证」绕缓存兜底
        val fsItem = firstSeenTarget
        if (fsItem != null) {
            dev.tgfeed.ui.components.FirstSeenSheet(
                item = fsItem,
                lookup = { v, forceRescan -> vm.firstSeenFor(v, forceRescan) },
                onDismiss = { firstSeenTarget = null },
            )
        }

        // 新手引导覆盖层（仅首次进入 Feed）
        if (showCoach) {
            dev.tgfeed.ui.components.CoachOverlay(
                page = dev.tgfeed.ui.components.CoachPage.Feed,
                onDismiss = {
                    showCoach = false
                    prefs.firstRunFeed = false
                },
            )
        }

        // 内容筛选面板（方向/时长/发布时间）
        if (showFilter) {
            dev.tgfeed.ui.components.FilterSheet(
                current = filter,
                onApply = { f ->
                    vm.setFilter(f)
                    showFilter = false
                },
                onDismiss = { showFilter = false },
            )
        }

        // R141 手气不错面板（AppSheet 双选项卡：开一个频道 / 抽一串内容）。
        // 源面板先收起再开（FeedTopBarOverlay pickLucky），不出现两层 sheet 叠层
        if (showLuckyPicker) {
            dev.tgfeed.ui.components.LuckyPickerSheet(
                onDismiss = { showLuckyPicker = false },
                // 开一个频道：现手气不错逻辑（随机频道随机位置开刷）
                onOpenChannelBlindBox = {
                    sourceTitle = "手气不错"
                    vm.openBlindBox()
                },
                // 抽一串内容：词选定即开刷（null = 随机摇，现场摇号）
                onOpenInlineBlindBox = { q -> startInlineBlindBox(q) },
            )
        }

        // 片单选择对话框（"加入片单"动作经 LocalAddToPlaylist 穿透到此处宿主；
        // 实现见 ui/components/ActionSheet.kt 的 AddToPlaylistSheet，与弹幕输入框同级）
        val pickTarget = playlistPickTarget
        if (pickTarget != null) {
            dev.tgfeed.ui.components.AddToPlaylistSheet(
                item = pickTarget,
                onDismiss = { playlistPickTarget = null },
            )
        }

        // R144 快拍沉浸连播（满屏竖卡沉浸流 + 顶部进度条段，design-redesign §3.7）：
        // 上下滑切 story，频道内旧→新、频道尾自动接下一个频道；图片 5s 计时/视频播完
        // 自动进下一条，最后一条播完=整轮看完收起。揭示按 §3.4 节律
        // （fadeIn AnimMed + scaleIn 0.92→1 AnimSlow，退场 fadeOut AnimFast）；
        // briefs 用打开时刻冻结的快照，退出动画帧仍拿得到数据（开关翻转不清快照）
        androidx.compose.animation.AnimatedVisibility(
            visible = storiesViewerOpen,
            enter = androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(dev.tgfeed.ui.theme.TgFeedTokens.AnimMed),
            ) + androidx.compose.animation.scaleIn(
                animationSpec = androidx.compose.animation.core.tween(dev.tgfeed.ui.theme.TgFeedTokens.AnimSlow),
                initialScale = 0.92f,
            ),
            exit = androidx.compose.animation.fadeOut(
                androidx.compose.animation.core.tween(dev.tgfeed.ui.theme.TgFeedTokens.AnimFast),
            ),
        ) {
            val rg = realGateway
            if (rg != null && storiesViewerBriefs.isNotEmpty()) {
                dev.tgfeed.ui.components.StoryViewer(
                    gateway = rg,
                    briefs = storiesViewerBriefs,
                    initialChatId = storiesViewerChatId,
                    watched = storyWatched,
                    titleOf = { id -> realGateway?.peekChatTitle(id) },
                    avatarLoader = avatarLoader,
                    onDismiss = { storiesViewerOpen = false },
                )
            }
        }

        // ---------------- R301 星图舷窗（design-dual-form §2——不跳页看见「这条视频
        //      在星图哪里」；坐标行/长按格两入口统一宿主） ----------------
        val portholeItem = portholeTarget
        if (portholeItem != null) {
            dev.tgfeed.ui.components.GalaxyPortholeSheet(
                item = portholeItem,
                tag = dev.tgfeed.ui.components.galaxyTagOf(portholeItem),
                gateway = gateway,
                vm = vm,
                onEnterGalaxy = { id ->
                    // A 案出场：封面缩成光点+白闪藏接缝——真实跳图链在白闪峰值帧触发
                    //（sheet.dismiss 已播完退场再走到这里——两层面板不叠层，R222 纪律）
                    if (warpTarget == null) warpTarget = portholeItem to id
                },
                onDismiss = { portholeTarget = null },
            )
        }

        // ---------------- R301 错觉拼接运镜 A 案叠加层（顶位——覆盖一切 chrome）：
        //      出场=光点收缩+白闪（warpTarget）；到达=白闪收束（galaxyArriveFlash） ----------------
        val warp = warpTarget
        if (warp != null) {
            dev.tgfeed.ui.components.GalaxyWarpOverlay(
                item = warp.first,
                onFlashPeak = {
                    // 白闪峰值帧=真实跳图（接缝藏白闪——R254 缓存秒开让对侧即开）
                    appContainer.openTasteMapFly(warp.second)
                },
                onDone = { warpTarget = null },
            )
        }
        if (galaxyArriveFlash.value > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = galaxyArriveFlash.value }
                    .background(androidx.compose.ui.graphics.Color.White),
            )
        }
    }
    }
}

/**
 * 零频道引导卡（GAPS3）：新用户一个频道都没加入时替代普通空态。
 * 三步引导文案 + "去搜索 / 去发现"双入口（分别走容器的搜索页 / 发现页路由）
 */
@Composable
private fun ZeroChannelGuideCard(
    onOpenSearch: () -> Unit,
    onOpenDiscover: () -> Unit,
) {
    Column(
        Modifier
            .padding(horizontal = 32.dp)
            .background(
                dev.tgfeed.ui.theme.TgFeedTokens.SurfaceHigh,
                RoundedCornerShape(20.dp),
            )
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "还没有频道",
            color = dev.tgfeed.ui.theme.TgFeedTokens.TextHigh,
            style = MaterialTheme.typography.titleLarge,
        )
        // 三步引导：告诉新用户内容从哪来、关注后会发生什么
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "① 用搜索找感兴趣的频道",
                color = dev.tgfeed.ui.theme.TgFeedTokens.TextMedium,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "② 或在发现页挑推荐的",
                color = dev.tgfeed.ui.theme.TgFeedTokens.TextMedium,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "③ 关注后内容自动流进来",
                color = dev.tgfeed.ui.theme.TgFeedTokens.TextMedium,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onOpenSearch) { Text("去搜索") }
            OutlinedButton(onClick = onOpenDiscover) { Text("去发现") }
        }
    }
}

/**
 * R154 关注流空订阅引导卡：关注页是订阅频道的内容主场——一个订阅都没有时
 * 这里永远没有内容，替代普通空态给出去向（发现页挑频道 / 搜索找频道；
 * 两处界面的频道卡都有"追"订阅入口，订阅后回到关注即有内容）。
 */
@Composable
private fun FollowingEmptyGuideCard(
    onOpenDiscover: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    Column(
        Modifier
            .padding(horizontal = 32.dp)
            .background(
                dev.tgfeed.ui.theme.TgFeedTokens.SurfaceHigh,
                RoundedCornerShape(20.dp),
            )
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "还没有订阅频道",
            color = dev.tgfeed.ui.theme.TgFeedTokens.TextHigh,
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            "关注是你订阅频道的内容主场\n订阅后，它们的全部内容都会流到这里",
            color = dev.tgfeed.ui.theme.TgFeedTokens.TextMedium,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onOpenDiscover) { Text("去发现页挑频道") }
            OutlinedButton(onClick = onOpenSearch) { Text("去搜索") }
        }
    }
}

/**
 * R167 #话题空态卡：标签点击走深索引混合源后真没命中（深索引未覆盖 + 服务端
 * 已加入聊天也没有）时的诚实口径——替代笼统的"这个源暂时没有视频"。
 * 两条出路：去搜索页用全网通道再搜一次（openSearch 带 # 前缀，搜索页本地腿
 * 自动获得 hashtag 精确语义）/ 重试（刷新当前源，后台爬取可能已补全）。
 * 卡片结构与视觉规格沿用 FollowingEmptyGuideCard 先例（同族引导卡）。
 */
@Composable
private fun HashtagEmptyCard(
    tag: String,
    onOpenSearch: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        Modifier
            .padding(horizontal = 32.dp)
            .background(
                dev.tgfeed.ui.theme.TgFeedTokens.SurfaceHigh,
                RoundedCornerShape(20.dp),
            )
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "深索引还没有 $tag 的内容",
            color = dev.tgfeed.ui.theme.TgFeedTokens.TextHigh,
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            "本地索引只收录已爬到的频道内容\n标签所在频道可能还没爬到，后台爬取会持续补全\n也可以去搜索页用全网通道再搜一次",
            color = dev.tgfeed.ui.theme.TgFeedTokens.TextMedium,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onOpenSearch) { Text("去搜索页搜") }
            OutlinedButton(onClick = onRetry) { Text("重试") }
        }
    }
}

/** 媒体类型 tab 的中文名（R170 类型 tab 冷加载文案用；口径与 FeedTopBarOverlay 的
 *  内容分区 tab 带一致：0 视频 / 1 图文 / 2 音频） */
private fun tabLabelOf(tab: Int): String = when (tab) {
    0 -> "视频"
    1 -> "图文"
    2 -> "音频"
    else -> "媒体"
}

/** 空列表三态：加载中 / 错误可重试 / 空内容。
 *  R167 增 [loadingText]：加载中在 spinner 下补一句用户语言说明（默认 null 不显示，
 *  三处既有调用行为不变）——#话题源 45s 级全库扫描必须让用户知道在等什么 */
@Composable
private fun FeedEmptyState(
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    loadingText: String? = null,
    // R324 离线降级：离线解释行（OfflineDegradePolicy.feedOfflineEmptyHint 单源文案；
    // null=在线/不显示——三处既有调用行为不变）
    offlineHint: String? = null,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            // 统一 spinner 规格：40dp/3dp/TextHigh + trackColor（修"白点"——
            // M3 1.3.0 spinner 动画首帧/比例 0 时退化成小圆点，轨道色兜底显示完整圆环）
            loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    color = dev.tgfeed.ui.theme.TgFeedTokens.TextHigh,
                    strokeWidth = 3.dp,
                    trackColor = dev.tgfeed.ui.theme.TgFeedTokens.TextHigh.copy(alpha = 0.2f),
                )
                if (loadingText != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        loadingText,
                        color = dev.tgfeed.ui.theme.TgFeedTokens.TextMedium,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
            error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "加载失败：$error",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
                // R324 离线降级：加载失败撞上离线=环境解释行（OfflineDegradePolicy 单源
                // 文案——错误本身照常展示，离线行只补上下文，不掩盖不编造）
                if (offlineHint != null) {
                    Text(
                        offlineHint,
                        color = dev.tgfeed.ui.theme.TgFeedTokens.TextLow,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
                OutlinedButton(onClick = onRetry) { Text("点击重试") }
            }

            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "这个源暂时没有视频",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp),
                )
                // R324 离线降级：真空态撞上离线——「暂无缓存内容」的解释行（诚实空态
                // 不假转圈：不假装在加载，明说此刻没有可刷的缓存）
                if (offlineHint != null) {
                    Text(
                        offlineHint,
                        color = dev.tgfeed.ui.theme.TgFeedTokens.TextLow,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
                OutlinedButton(onClick = onRetry) { Text("刷新试试") }
            }
        }
    }
}

/**
 * 分享卡片用图（图/音面板"分享卡片"动作）：采样解码本地图片，短边压到 ~1600px
 * （与 PhotoCard.decodeSampled 同一手法：先量尺寸再按 2 的幂采样，避免全尺寸大图撑爆内存）。
 * 量尺寸失败返回 null，调用方回落 minithumbnail 小图。
 */
private fun decodeSharePhotoSampled(path: String): android.graphics.Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) {
        sample *= 2
    }
    return android.graphics.BitmapFactory.decodeFile(
        path,
        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
    )
}

/** caption t.me 链接的解析目标：name=首段原文；inviteOnly=true 表示邀请/私有内部链 */
private class TmeTarget(val name: String, val inviteOnly: Boolean)

/**
 * 从 caption 链接里识别 t.me 目标（openCaptionLink 的前置分流）：
 *  - https://t.me/<name>[/<msgId>…] → 公开用户名（TG 规则 4~32 位 [A-Za-z0-9_]），
 *    交给 resolvePublicChannel 应用内解析；
 *  - t.me/+xxx 邀请链接、joinchat、t.me/c/ 私有频道内部链 → inviteOnly（无公开用户名
 *    可解析，UI 提示"私有频道需要邀请"）；
 *  - 非 t.me 链接、addlist 合集等其余形态 → null（回落系统浏览器）。
 * 注意 caption 正则 t\.me/\S+ 会把尾随中文标点一并吞进 URL——用户名合法性校验
 * 在此完成，带脏字符的头段会自然落选回落浏览器（与原行为一致，不会更差）。
 */
private fun parseTmeTarget(url: String): TmeTarget? {
    val path = url.removePrefix("https://t.me/").removePrefix("http://t.me/")
    if (path == url) return null // 非 t.me 链接
    val head = path.substringBefore('/').substringBefore('?')
    if (head.startsWith("+") || head.equals("joinchat", ignoreCase = true) ||
        head.equals("c", ignoreCase = true)
    ) {
        return TmeTarget(head, inviteOnly = true)
    }
    if (!head.matches(Regex("[A-Za-z0-9_]{4,32}"))) return null // 非用户名形态
    if (head.equals("addlist", ignoreCase = true)) return null // 合集邀请链接，回落浏览器
    return TmeTarget(head, inviteOnly = false)
}

/**
 * R141 盲盒流摇号：从混沌词池随机挑一个，避开 [exclude]（当前词）保证
 * "摇一把"真换词；exclude 不在池内（自定词）或池尽时回落全池随机。
 */
private fun rollBlindWordExcluding(exclude: String?): String {
    val pool = dev.tgfeed.core.feed.InlineBotFeedSource.CHAOS_KEYWORDS
        .filter { it != exclude }
    return if (pool.isEmpty()) dev.tgfeed.core.feed.InlineBotFeedSource.rollChaosKeyword()
    else pool.random()
}

/**
 * R141 盲盒流开场卡（特殊物种卡 §3.2：满宽 + 标识带 + 内容区 + 显式按钮区）：
 * 新一轮开刷时浮在流头——标识带（骰子 + 盲盒流）、大字"本轮关键词：XXX"、
 * 一句人话说明 + 「摇一把」（主按钮，红）/「开刷」（次按钮）。
 * 摇一把 = 换词重抽新的一轮；开刷 = 收起本卡直接看内容。
 */
@Composable
private fun BlindBoxOpeningCard(
    keyword: String,
    onShake: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier
            .padding(horizontal = 32.dp)
            .clip(RoundedCornerShape(dev.tgfeed.ui.theme.TgFeedTokens.RadiusLg))
            .background(dev.tgfeed.ui.theme.TgFeedTokens.SurfaceHigh)
            .border(
                width = 0.5.dp,
                color = dev.tgfeed.ui.theme.TgFeedTokens.OverlayHairline,
                shape = RoundedCornerShape(dev.tgfeed.ui.theme.TgFeedTokens.RadiusLg),
            )
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 标识带：骰子（星黄 = 新鲜语义族）+ 盲盒流
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Casino,
                contentDescription = null,
                tint = dev.tgfeed.ui.theme.TgFeedTokens.StarYellow,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "盲盒流",
                color = dev.tgfeed.ui.theme.TgFeedTokens.TextMedium,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.height(12.dp))
        // 大字：本轮关键词（结论先行）
        Text(
            "本轮关键词：$keyword",
            color = dev.tgfeed.ui.theme.TgFeedTokens.TextHigh,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "机器人现抽现配，每一批都是新的；不满意就摇一把。",
            color = dev.tgfeed.ui.theme.TgFeedTokens.TextMedium,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // 主按钮唯一（红）：摇一把 = 换词重抽
            Button(
                onClick = onShake,
                shape = RoundedCornerShape(dev.tgfeed.ui.theme.TgFeedTokens.RadiusPill),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = dev.tgfeed.ui.theme.TgFeedTokens.AccentRed,
                    contentColor = Color.White,
                ),
            ) {
                Text("摇一把", style = MaterialTheme.typography.titleSmall)
            }
            // 次按钮：开刷 = 收起本卡直接看内容
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(dev.tgfeed.ui.theme.TgFeedTokens.RadiusPill),
            ) {
                Text(
                    "开刷",
                    color = dev.tgfeed.ui.theme.TgFeedTokens.TextHigh,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

// ---------------- R215 ② feed 那年今日卡（特殊物种卡，BlindBoxOpeningCard 同族） ----------------

/** 注入页位：第 3~5 条位置的中位（第 4 条——上有浏览节奏、下不沉底） */
private const val ON_THIS_DAY_FEED_CARD_PAGE = 3

/** 卡片页载荷：记忆本体 + 宿主源实例（同一性判定：切源即隐藏、弹回重现） */
private class OnThisDayFeedCard(
    val memory: OnThisDayMemory,
    val hostSource: dev.tgfeed.core.feed.FeedSource,
)

/**
 * 「N 年前的今天，你看过这些」特殊卡（feed 第 4 条整页换装，每天至多一张）：
 * 年轮图标（TrackChanges 同心环）+ 大字年轮 + 时间语言推荐语 + 2~3 条 3:4 真封面
 * 缩略横排（点缩略进那年今日播放流——一次性源，刷完按返回回正常 feed）。
 * 视觉与 R141 开场卡同族（SurfaceHigh+发丝+RadiusLg 卡壳居中）；中性阶差零强调色
 * ——回忆卡是内容不是行动位（红只归主行动）。缩略图缺封面回落中性占位（类型字）。
 */
@Composable
private fun FeedOnThisDayCardPage(
    card: OnThisDayFeedCard,
    gateway: TdGateway,
    onOpen: (dev.tgfeed.core.td.VideoItem) -> Unit,
) {
    // 零遮罩红线（R162 P1）：卡壳直接浮在底衬上（BlindBoxOpeningCard 同族——不垫
    // 半透明黑层，可读性由卡壳 SurfaceHigh 不透明底自己保证）
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(dev.tgfeed.ui.theme.TgFeedTokens.RadiusLg))
                .background(dev.tgfeed.ui.theme.TgFeedTokens.SurfaceHigh)
                .border(
                    width = 0.5.dp,
                    color = dev.tgfeed.ui.theme.TgFeedTokens.OverlayHairline,
                    shape = RoundedCornerShape(dev.tgfeed.ui.theme.TgFeedTokens.RadiusLg),
                )
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 标识带：年轮图标（同心环=年轮语义）+ 那年今日
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.TrackChanges,
                    contentDescription = null,
                    tint = dev.tgfeed.ui.theme.TgFeedTokens.TextMedium,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "那年今日",
                    color = dev.tgfeed.ui.theme.TgFeedTokens.TextMedium,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Spacer(Modifier.height(12.dp))
            // 大字：N 年前的今天（结论先行）
            Text(
                "${card.memory.yearsAgo} 年前的今天",
                color = dev.tgfeed.ui.theme.TgFeedTokens.TextHigh,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
            )
            Spacer(Modifier.height(8.dp))
            // 时间语言推荐语（只有年数与条数——脱敏红线：零频道名零标题）
            Text(
                "你看过这些 · 那天前后刷了 ${card.memory.items.size} 条",
                color = dev.tgfeed.ui.theme.TgFeedTokens.TextMedium,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
            Spacer(Modifier.height(20.dp))
            // 2~3 条 3:4 缩略横排（整块可点=进播放流定位到点中的那条）
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                card.memory.items.take(3).forEach { m ->
                    OnThisDayThumb(item = m, gateway = gateway, onClick = { onOpen(m) })
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "点一张回去看看 · 刷完自动回这里",
                color = dev.tgfeed.ui.theme.TgFeedTokens.TextLow,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

/** 3:4 记忆缩略（84×112dp 圆角 RadiusSm）：真封面链 CoverStore→coverBytes→
 *  coverFileId→消息反解（WatchHistoryScreen 同链）；缺封面回落中性类型字占位 */
@Composable
private fun OnThisDayThumb(
    item: dev.tgfeed.core.td.VideoItem,
    gateway: TdGateway,
    onClick: () -> Unit,
) {
    val cover by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, item.key) {
        dev.tgfeed.ui.components.CoverStore.map[item.key]?.let { value = it }
        val lib = gateway as? TdLibGateway
        if (value == null && lib != null) {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { lib.loadCoverBytesByMessage(item) }.getOrNull()
            }
            if (bytes != null) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }.getOrNull()
                }
            }
        }
    }
    Box(
        Modifier
            .size(width = 84.dp, height = 112.dp)
            .clip(RoundedCornerShape(dev.tgfeed.ui.theme.TgFeedTokens.RadiusSm))
            .background(dev.tgfeed.ui.theme.TgFeedTokens.Surface)
            .clickable(onClick = onClick),
    ) {
        if (cover != null) {
            Image(
                bitmap = cover!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            // 无封面占位：类型字（视频/图/音——与历史页/搜索格同款兜底）
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    when (item.mediaType) {
                        1 -> "图"
                        2 -> "音"
                        else -> "播"
                    },
                    color = dev.tgfeed.ui.theme.TgFeedTokens.TextLow,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
