package dev.tgfeed.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tgfeed.core.feed.ChannelChronicle
import dev.tgfeed.core.feed.ChannelFeedSource
import dev.tgfeed.core.feed.ChannelGenome
import dev.tgfeed.core.feed.ChannelHomePaging
import dev.tgfeed.core.feed.ChronicleResult
import dev.tgfeed.core.feed.DiscoveryGoldmine
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.feed.FeedViewModelFactory
import dev.tgfeed.core.feed.SearchFeedSource
import dev.tgfeed.core.feed.SeekHeatPolicy
import dev.tgfeed.core.feed.episodeChains
import dev.tgfeed.core.feed.formatChannelGenome
import dev.tgfeed.core.feed.loadIndexGenome
import dev.tgfeed.core.td.ArchaeoDig
import dev.tgfeed.core.td.ChannelBrief
import dev.tgfeed.core.td.ChannelStatTriple
import dev.tgfeed.core.td.MockTdGateway
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.ui.components.CoverStore
import dev.tgfeed.ui.components.OverlayInfoChip
import dev.tgfeed.ui.components.OfflineHintPill
import dev.tgfeed.ui.components.kit.AppEmptyState
import dev.tgfeed.ui.components.kit.AppIconButton
import dev.tgfeed.ui.components.kit.AppLoading
import dev.tgfeed.ui.components.kit.AppPill
import dev.tgfeed.ui.components.kit.AppSheetMotion
import dev.tgfeed.ui.components.kit.AppTopBar
import dev.tgfeed.ui.components.kit.SheetAnchor
import dev.tgfeed.ui.theme.MotionTokens
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 频道主页（抖音作者主页式重排版，UI-UX-REDESIGN-PLAN §4 P1-2 整改落地）。
 *
 * 全屏覆盖层：由 MainActivity 压在 Feed 之上渲染（Feed 保持组合、pagerState 不动；
 * 打开期间 Feed 播放由 FeedScreen 的 channelHomeOpen 播放闸暂停，关闭后恢复）。
 * ← / 系统返回键收口（本屏 BackHandler + MainActivity 级 BackHandler 双保险）。
 * 不开新路由、不切 source、不动 pagerState。
 *
 * 页面骨架（自上而下，全部在一个 LazyVerticalGrid 里，整页一体滚动）：
 *  1. 顶栏：kit [AppTopBar]（返回圆钮 + 频道名标题 + 动作槽 🔍频道内搜索），
 *     修 CH-B"无页面标题"；头部在视口内时透明压在 banner 上（标题描边可读，
 *     R162 起删 banner 顶部渐变遮罩），头部滚出视口后实色 InkBlack 底
 *     （开关式，不做渐变动画）。
 *  2. 沉浸头部：banner（最新若干条里第一张封面铺满，minithumbnail 低清小图 Crop
 *     放大后天然柔焦；CoverStore 有播过抽帧的高清图优先用；无封面回落 TgBlue→黑
 *     渐变）——R162 起无底部渐变遮罩，封面原色直出、底缘硬切由头像 InkBlack
 *     描边圈出切口感；64dp 圆形头像半压 banner 下缘。
 *  3. 信息区：频道名（17sp SemiBold）→ 简介行（@username，2 行 clamp + 溢出时
 *     "展开/收起"，真简介等 getChannelFullInfo 网关口落地后换内容源，终案 §3.3
 *     排 P3）→ 统计行（CH-A 修复：作品数只由作品 tab 角标承担，统计行不再重复，
 *     只余 订阅状态·合集数·各类型计数 一行）→ 操作行（AppPill 胶囊：
 *     追更/已追更、合集、分享）。
 *  4. Tab 行：作品（默认，带数量角标）/ 合集（频道有图集才出现，按 albumId 归组，
 *     一格一合集，角标"🖼 图集 ×N"）；作品 tab 下挂类型筛选小 tab
 *     （全部/视频/图文/音频，下划线式，与 Feed/搜索页同一语言，只出现该频道
 *     实际有的类型，单一类型不显示）。
 *  5. 作品网格：3 列封面墙，格子圆角 RadiusSm、封面 ContentScale.Crop，
 *     带类型角标 + 时长（视频）；空态 AppEmptyState，加载态 AppLoading。
 *
 * 频道内搜索（顶栏 🔍）：本屏内搜索模式——顶栏变形为胶囊搜索框，网格切换为
 * 搜索结果。复用现成单频道搜索通道 SearchFeedSource(gateway, query, chatId)
 * （FeedRequest(channelId+searchQuery) → 网关 searchInChannel 服务端搜，
 * 失败回退 GetChatHistory+本地过滤），不新开路由、不改 MainActivity。
 *
 * 时间机器·考古（R134 底座，R140 叙事重造）：操作行"考古"胶囊 → 底部年代面板
 * （AppSheet）：一年前/三年前/五年前年代卡 + 随机穿越宽卡，网关侧 GetChatHistory
 * 日期二分收敛落点（跳跃层数/误差只打 logcat），挖掘 1–5s 走"穿越过场"（年份倒滚
 * + 目标日期大字）；挖到出年代卡（大字日期 + 距今 + 挖到 N 条）与 3 列预览网格，
 * 点任意格走 onOpenItem 同通道进频道 Feed（锚定首拉 + 定位，从这条往更旧刷）——
 * 老频道长尾库存的盘活入口，预览格即入口、无主按钮。
 *
 * 媒体网格：ChannelFeedSource 分页（网关 fetchFromChannel 单频道游标，零新网关口），
 * 封面三级链（CoverStore 抽帧 → coverBytes minithumbnail → coverFileId 高清，
 * 图片无独立缩略图 fileId，走 ensurePlayable 原图采样解码）。
 * 加载提速（修"主页加载慢"）：进页先读本地深度索引（FeedViewModel.loadChannelIndexedPage
 * 有界首屏）秒出已索引内容铺网格，网络分页到达后按 key 覆盖升级薄条目（封面/类型/图集
 * 字段补全）。
 * R212 无限滚动（修用户走查实锤「上滑到底不触发继续加载」）：滚动到底触发 next page
 * 拉取——网络游标翻页为主、深索引 OFFSET 补页兜底（网络失败/到底而索引有余量时），
 * 失败≠到底（超时/异常可重试不永久断更）、每轮有界等待、到货再装填（墙仍短自动续拉，
 * 「可一直滑」）；决策纯函数见 core/feed/ChannelHomePaging.kt。
 * 点格子 → onOpenItem（外层先复位媒体 tab/筛选，再走 FeedViewModel.openChannelAt
 * 锚定首拉 + contextJumpKey 定位回 Feed，与"查看上下文"同一通道）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelHomeScreen(
    gateway: TdGateway,
    brief: ChannelBrief,
    onBack: () -> Unit,
    onOpenItem: (VideoItem) -> Unit,
) {
    BackHandler(onBack = onBack)

    // 共享 FeedViewModel（与 FeedScreen 同 key 的同一实例）：追更状态/追/退追都走它，
    // 与右栏头像徽标、操作行"追更"胶囊天然同源同步
    val app = LocalContext.current.applicationContext as android.app.Application
    val context = LocalContext.current
    val vm: FeedViewModel = viewModel(
        factory = FeedViewModelFactory(gateway, app),
        key = "gw" + System.identityHashCode(gateway),
    )
    val subscriptions by vm.subscriptions.collectAsState()
    val subscribed = subscriptions.any { it.id == brief.id }
    // 频道屏蔽状态（共享 VM 同源）：操作行"屏蔽"胶囊的文案/点击行为按是否已屏蔽切换
    val blockedChannels by vm.blockedChannels.collectAsState()
    val blocked = brief.id in blockedChannels
    // R324 离线降级（频道页·降级档）：NetworkWatch 单源离线态——网络失败时索引
    // 兜底供给（R212 既有），顶部 banner 诚实标注「本地缓存内容」（在线 null 不显示）
    val networkOnline by dev.tgfeed.core.net.NetworkWatch.online.collectAsState()
    // "屏蔽"确认对话框显隐（防误触：点胶囊先弹确认，确认才执行）
    var showBlockConfirm by remember(brief.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 频道媒体分页：复用现成单频道源（游标语义 = fetchFromChannel/GetChatHistory）；
    // 不进 FeedViewModel——主页是自己的控制器（终案 §3.1 ChannelHomeController 精神），
    // 网格数据与 Feed 列表互不污染
    // R183 自愈线索：brief 的 title/username（深索引候选无本地会话时解析建会话）
    val source = remember(brief.id, gateway) {
        ChannelFeedSource(gateway, brief.id, openTitle = brief.title, openUsername = brief.username)
    }
    var mediaItems by remember(brief.id) { mutableStateOf<List<VideoItem>>(emptyList()) }
    var loading by remember(brief.id) { mutableStateOf(false) }
    var exhausted by remember(brief.id) { mutableStateOf(false) }
    // R212 无限滚动三件套（修「上滑到底不触发继续加载」的三个死点，决策纯函数见
    // core/feed/ChannelHomePaging.kt）：
    //  1. 失败≠到底：网络页超时/异常不再吞成「空页+null游标」置 exhausted——旧版
    //     任何一次抖动（限流/超时/Chat not found 自愈失败）都永久断更；
    //  2. 有界等待：nextPage 包 withTimeoutOrNull（网关链路里 channelTitle/评论级联的
    //     裸 send 无超时，拥塞时可挂数分钟——loading 卡真后触发器加载闸永久早退，
    //     实机实锤「正在加载…」常驻而 logcat 零请求）；
    //  3. 到货再装填：pageArrivals 记并入新货的轮次，进 LaunchedEffect 键——旧触发器
    //     只在 shouldLoadMore 翻转时发射，一轮失败后保持 true 不再翻转=永不重试。
    // 索引分页游标（深索引源频道的兜底供给，有界 LIMIT+OFFSET）：
    //  indexTotal=-1 尚未探测；indexOffset=已读行数（OFFSET 口径，按读到的行计）
    var pageArrivals by remember(brief.id) { mutableStateOf(0) }
    var indexTotal by remember(brief.id) { mutableStateOf(-1) }
    var indexOffset by remember(brief.id) { mutableStateOf(0) }
    // 本轮失败（网络超时/异常且索引无货）——状态行诚实提示「上滑重试」，成功到货即清
    var loadFailed by remember(brief.id) { mutableStateOf(false) }

    fun loadMore() {
        if (loading || exhausted) return
        loading = true
        loadFailed = false
        scope.launch(Dispatchers.IO) {
            // R221 防御兜底：整段包异常（含大表合并/排序的分配失败类 Throwable）——
            // 兜住后必须复位 loading 闸（R212 修的「加载闸永久早退」死点不能因兜底
            // 复发）；取消异常原样上抛走结构化取消（离开页面/换台）
            try {
                // 网络页有界等待：超时（拥塞/挂死）与异常（限流/自愈失败）统一按「本轮
                // 失败」处理——游标不推进（GatewayFeedSource 成功才步进）、exhausted 不置、
                // loading 复位后由触发器/用户上滑重试
                val net = withTimeoutOrNull(ChannelHomePaging.NET_PAGE_TIMEOUT_MS) {
                    runCatching { source.nextPage(ChannelHomePaging.PAGE_LIMIT) }.getOrNull()
                }
                val d = ChannelHomePaging.decide(net)
                // 索引补页：网络这轮没到货且索引还有余量——深索引源频道（无本地会话/
                // Chat not found 自愈失败）的主供给路径，有界查询秒回
                val idxItems = if (d.fetchIndex && indexOffset < indexTotal) {
                    vm.loadChannelIndexedPage(brief.id, ChannelHomePaging.INDEX_PAGE, indexOffset)
                } else emptyList()
                // R221 空安全：decide 契约里 mergeNet 蕴含 net!=null，orEmpty 防
                // 契约漂移时空指针崩进程（失败轮语义不变）
                val newItems = if (d.mergeNet) net?.items.orEmpty() else idxItems
                withContext(Dispatchers.Main) {
                    if (newItems.isNotEmpty()) {
                        // 合并升级而非纯追加：索引缓存的薄条目可能已在表里，
                        // 网络页字段更全（minithumbnail/高清封面链/图集 id）——同 key 覆盖；
                        // 统一按 messageId 新→旧排序（索引落盘与网络分页同序，排序同时修正
                        // "索引建完后频道新发的内容"被追加到尾部的错位）
                        val merged = LinkedHashMap<String, VideoItem>(mediaItems.size + newItems.size)
                        for (it in mediaItems) merged[it.key] = it
                        for (it in newItems) merged[it.key] = it
                        mediaItems = merged.values.sortedByDescending { it.messageId }
                        pageArrivals++
                    }
                    if (idxItems.isNotEmpty()) indexOffset += idxItems.size
                    // 真「到底了」= 网络源到底 且 索引取尽（无索引频道 total=0 恒取尽）
                    if (d.netDone && indexOffset >= maxOf(indexTotal, 0)) exhausted = true
                    // 失败标记：网络空手而归且索引没兜上（net==null 超时/异常，或空页且无索引）
                    if (newItems.isEmpty() && !d.netDone) loadFailed = true
                    loading = false
                    android.util.Log.d(
                        "R212",
                        "主页翻页 chatId=${brief.id} 网络=${net?.items?.size ?: -1}(${if (net == null) "超时/异常" else if (d.netDone) "到底" else "有游标"})" +
                            " 索引补=${idxItems.size} 偏移=$indexOffset/$indexTotal 到货=$pageArrivals 到底=$exhausted 失败=$loadFailed",
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.w("R212", "主页翻页兜底异常: ${e.javaClass.simpleName}: ${e.message}")
                // NonCancellable：离开页面（scope 已取消）也必须复位闸，否则下轮
                // 进页 loading 初值残留触发器永久早退
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    loading = false
                    loadFailed = true
                }
            }
        }
    }
    LaunchedEffect(brief.id) { loadMore() }

    // 索引秒出（修"主页加载慢"）：进主页先读本地深度索引把已索引内容铺网格——
    // R212 起有界：旧版 channelItems 全量读，数万条的怪兽频道一次全进内存+整列表排序
    // 是启动大 IO；首屏只取 [ChannelHomePaging.INDEX_PAGE] 条，余量由 loadMore 的
    // 索引补页通道按 OFFSET 续供（「可一直滑」对深索引源频道恒成立）。
    // 索引未建过则为空，行为与旧版一致（纯网络分页）
    LaunchedEffect(brief.id) {
        // R221 防御兜底：DB 读本身有 runCatching（indexedCount/channelItemsPage），
        // 这里兜的是合并/排序段的分配失败类 Throwable（数千条大表的极端情形）
        try {
            val total = withContext(Dispatchers.IO) { vm.deepIndexedCount(brief.id) }
            indexTotal = total
            if (total > 0) {
                val cached = withContext(Dispatchers.IO) {
                    vm.loadChannelIndexedPage(brief.id, ChannelHomePaging.INDEX_PAGE, 0)
                }
                if (cached.isNotEmpty()) {
                    indexOffset = cached.size
                    // 只补缺不覆盖：网络首页若已先到，保留网络条目的完整字段；
                    // 两条 effect 都经主线程写 mediaItems，无并发竞态
                    val merged = LinkedHashMap<String, VideoItem>(mediaItems.size + cached.size)
                    for (it in mediaItems) merged[it.key] = it
                    for (it in cached) merged.putIfAbsent(it.key, it)
                    mediaItems = merged.values.sortedByDescending { it.messageId }
                    android.util.Log.d(
                        "R212", "索引种子 chatId=${brief.id} 总数=$total 取到=${cached.size} 网络在途=$loading",
                    )
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            android.util.Log.w("R212", "索引种子兜底异常: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // 频道头像字节（头部 64dp；网关侧进程内缓存，Mock 返回 null 回落字母圆）
    var avatarBytes by remember(brief.id) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(brief.id, gateway) {
        avatarBytes = when (gateway) {
            is TdLibGateway -> runCatching { gateway.getChannelAvatar(brief.id) }.getOrNull()
            is MockTdGateway -> runCatching { gateway.getChannelAvatar(brief.id) }.getOrNull()
            else -> null
        }
    }

    // R49 真实统计口径探测：进页一次（网关进程内缓存 1h TTL，反复进出不重复发请求）。
    // 直连网关（与上方头像加载同款"is TdLibGateway 才调"模式，不进共享 VM——
    // 探测结果只服务本页统计行，无跨页消费方）；
    // null = 探测不可用（Mock 网关或全路失败）→ 统计行回落"已加载口径"旧文案
    var channelStats by remember(brief.id) { mutableStateOf<ChannelStatTriple?>(null) }
    var statsProbing by remember(brief.id) { mutableStateOf(false) }
    LaunchedEffect(brief.id, gateway) {
        val gw = gateway as? TdLibGateway ?: return@LaunchedEffect
        statsProbing = true
        // 网关内部逐路 runCatching 静默降级，理论上不抛；外层再兜一层防万一
        channelStats = runCatching { gw.probeChannelStats(brief.id) }.getOrNull()
        statsProbing = false
    }

    // ---------------- 频道内搜索（顶栏 🔍 进本屏搜索模式） ----------------
    // 复用现成单频道搜索通道 SearchFeedSource（FeedRequest(channelId+searchQuery)
    // → 网关 searchInChannel 服务端搜，异常回退本地 caption 过滤）；
    // 不开 SearchScreen——它是 MainActivity 整页路由（showSearch when 分支），
    // 本屏对外签名锁定、无法加跳转回调，故搜索态做成本屏内模式。
    var searchActive by remember(brief.id) { mutableStateOf(false) }
    var searchInput by remember(brief.id) { mutableStateOf("") }
    var searchItems by remember(brief.id) { mutableStateOf<List<VideoItem>>(emptyList()) }
    var searchLoading by remember(brief.id) { mutableStateOf(false) }
    var searchExhausted by remember(brief.id) { mutableStateOf(false) }
    // R212 搜索分页的到货记次（触发器再装填键之一，与普通网格的 pageArrivals 同族）
    var searchArrivals by remember(brief.id) { mutableStateOf(0) }
    // 当前搜索源实例：换词/退出搜索即换实例，旧实例的迟到分页结果被丢弃（防串词）
    var searchSource by remember(brief.id) { mutableStateOf<SearchFeedSource?>(null) }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // 退出搜索模式：清词清结果回普通网格（顶栏 ← 与搜索框清空后的回退共用）
    val exitSearch: () -> Unit = {
        searchActive = false
        searchInput = ""
        keyboard?.hide()
    }

    fun searchLoadMore() {
        val src = searchSource ?: return
        if (searchLoading || searchExhausted) return
        searchLoading = true
        scope.launch(Dispatchers.IO) {
            // R221 防御兜底：与普通网格 loadMore 同刀（Cancellation 上抛/Throwable
            // 复位闸；过期源不写状态语义不变）
            try {
                // R212 与普通网格同刀：有界等待 + 失败≠到底（旧版异常吞成 null 游标
                // → searchExhausted=true，一次抖动搜索翻页即死；这里超时/异常按失败轮
                // 处理，游标不动、不判到底，上滑/再装填重试）
                val page = withTimeoutOrNull(ChannelHomePaging.NET_PAGE_TIMEOUT_MS) {
                    runCatching { src.nextPage(ChannelHomePaging.PAGE_LIMIT) }.getOrNull()
                }
                withContext(Dispatchers.Main) {
                    // 源已换（用户改了词/退出搜索）→ 丢弃过期页，不写状态
                    if (searchSource === src) {
                        if (page != null) {
                            if (page.items.isNotEmpty()) {
                                val merged = LinkedHashMap<String, VideoItem>(searchItems.size + page.items.size)
                                for (it in searchItems) merged[it.key] = it
                                for (it in page.items) merged[it.key] = it
                                searchItems = merged.values.sortedByDescending { it.messageId }
                                searchArrivals++
                            }
                            if (page.nextCursor == null) searchExhausted = true
                        }
                        searchLoading = false
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.w("R212", "频道内搜索翻页兜底异常: ${e.javaClass.simpleName}: ${e.message}")
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    // 过期源不碰新源的闸（R105 改词死锁修复语义保留）
                    if (searchSource === src) searchLoading = false
                }
            }
        }
    }

    // 停笔 400ms 起搜；换词即重建搜索源（游标归零重新分页），清空输入回引导态
    LaunchedEffect(searchInput) {
        val q = searchInput.trim()
        if (q.isEmpty()) {
            searchSource = null
            searchItems = emptyList()
            searchExhausted = false
            searchLoading = false
            return@LaunchedEffect
        }
        delay(400)
        searchSource = SearchFeedSource(gateway, q, brief.id)
        searchItems = emptyList()
        searchExhausted = false
        // R105 修复改词死锁：重建搜索源前先复位加载闸。
        // 旧源的在途 IO 回来后会被 searchLoadMore 里的 `searchSource === src`
        // 守卫整段丢弃（含闸复位），闸若不复位则新词首请求被吞且永久卡死。
        searchLoading = false
        searchLoadMore()
    }

    // 进入搜索模式自动获焦弹键盘
    LaunchedEffect(searchActive) { if (searchActive) searchFocus.requestFocus() }

    // 分享频道：系统分享面板（t.me 链接 + 频道名；无 username 时只分享频道名）
    val shareChannel: () -> Unit = {
        val link = brief.username?.takeIf { it.isNotBlank() }?.let { "https://t.me/$it" }
        val text = if (link != null) "${brief.title}\n$link" else brief.title
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
        runCatching {
            context.startActivity(android.content.Intent.createChooser(send, "分享频道"))
        }
    }

    // ---------------- 时间机器·频道编年史（R134/R140 考古底座 + R204 TL-5 时间轴升级） ----------------
    // 网关侧 TdLibGateway.archaeoJumpToTime（GetChatHistory 日期二分）/ archaeoRandomDig
    // 出落点 + 落点向下的一页媒体；这里只做面板状态与调度（与频道内搜索同款本屏自治
    // 模式，不进共享 FeedViewModel）。R140：挖掘请求收进 ArchaeoPlan（穿越过场文案与
    // "再挖一次"重放的唯一来源），跳跃层数/误差/容差只留在 logcat——UI 全程只用
    // 穿越/年代/这一层的叙事（design-redesign §2.4/§3.5 禁词纪律）。
    // R204 TL-5：年代档位卡三连升级为**年历热力墙时间轴**（月历直方图 + 基因下界 →
    // 年块；数据口 vm.channelChronicle，会话缓存 6h）；档位跳跃改由年块承接
    // （直跳当年峰日），随机穿越宽卡保留。
    var showArchaeo by remember(brief.id) { mutableStateOf(false) }
    var archaeoDig by remember(brief.id) { mutableStateOf<ArchaeoDig?>(null) }
    var archaeoBusy by remember(brief.id) { mutableStateOf(false) }
    // 最近一次挖掘请求（挖完不清零——"再挖一次"按钮重放它；换频道/重进面板自然归零）
    var archaeoPlan by remember(brief.id) { mutableStateOf<ArchaeoPlan?>(null) }
    // 本铲彻底失败（未授权/超时/空频道）——区别于"挖到落点但这一层没有媒体"
    var archaeoFailed by remember(brief.id) { mutableStateOf(false) }

    // 编年史时间轴数据（R204 TL-5）：月历直方图 + 基因下界 → 年块墙；面板首开拉
    // 首批 12 个月（快出墙），「回溯更早」再翻到 24（网关钳制上限）
    var chronicle by remember(brief.id) { mutableStateOf<ChronicleResult?>(null) }
    var chronicleBusy by remember(brief.id) { mutableStateOf(false) }

    fun loadChronicle(monthsCap: Int) {
        if (chronicleBusy) return
        chronicleBusy = true
        scope.launch(Dispatchers.IO) {
            val result = runCatching { vm.channelChronicle(brief.id, monthsCap) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (result != null) chronicle = result
                chronicleBusy = false
            }
        }
    }

    // 面板首开触发一次取数（缓存命中零请求；TTL 内重开不重拉）
    LaunchedEffect(showArchaeo, brief.id) {
        if (showArchaeo && chronicle == null) {
            loadChronicle(dev.tgfeed.core.feed.FeedChroniclePart.FIRST_MONTHS_CAP)
        }
    }

    // R204 TL-7 频道级回放热力（chatId 级聚合，纯内存微秒级）：样本 ≥3 才出洞察行；
    // 洞察面板（ChannelSeekHeatSheet）显隐
    var showSeekHeat by remember(brief.id) { mutableStateOf(false) }
    val seekInsight by produceState<SeekHeatPolicy.ChannelInsight?>(null, brief.id) {
        value = runCatching { vm.channelSeekInsight(brief.id) }.getOrNull()
    }

    /** 档位 → 目标时刻（秒）：按 365 天折算，考古语境足够准 */
    fun archaeoTarget(yearsAgo: Int): Int =
        (System.currentTimeMillis() / 1000L - yearsAgo * 365L * 86400L).toInt()

    fun runDig(random: Boolean, yearsAgo: Int = 0, targetEpochSec: Int = 0, tier: String) {
        val lib = gateway as? TdLibGateway
        if (lib == null) {
            // Mock 演示源没有真实频道历史，考古无从挖起（胶囊层已置灰拦截，这里只兜底）
            android.widget.Toast.makeText(context, "演示模式没有考古库存", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (archaeoBusy) return
        archaeoBusy = true
        archaeoFailed = false
        archaeoPlan = ArchaeoPlan(random, yearsAgo, targetEpochSec, tier)
        scope.launch(Dispatchers.IO) {
            val dig = if (random) lib.archaeoRandomDig(brief.id)
            else lib.archaeoJumpToTime(brief.id, targetEpochSec)
            withContext(Dispatchers.Main) {
                archaeoDig = dig
                archaeoFailed = dig == null
                archaeoBusy = false
            }
        }
    }

    // 频道基因（R204 起一源三用：考古年龄断言 / 编年史年表下界 / 断更徽章频率口径）
    // ——本地深度索引单频道毫秒级聚合，未索引返回 null（各消费位诚实回落）
    val channelGenome by produceState<dev.tgfeed.core.feed.ChannelGenome?>(null, brief.id) {
        value = runCatching {
            withContext(Dispatchers.IO) { vm.loadIndexGenome(brief.id) }
        }.getOrNull()
    }
    // 开场文案数据化（§2.4.2）：深度索引里该频道最早帖子的距今年数（null = 未索引到，
    // 回落通用文案）——只读聚合（与基因鉴定行同库同源），不新增任何写路径；
    // minDateSec 是"翻得到的最早帖子"，作频道年龄的诚实下界
    val archaeoAgeYears: Int? = channelGenome?.minDateSec?.let { minSec ->
        ((System.currentTimeMillis() / 1000L - minSec) / (365L * 86400L)).toInt()
    }
    // 当前年（年块穿越的档位文案用；跨年瞬间误差以月计，可接受）
    val currentYearNow = remember { java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) }

    /**
     * 进考古层：以这一层任一条媒体走 onOpenItem（点格子回 Feed 的同一条链：复位
     * tab/筛选 → FeedViewModel.openChannelAt 锚定首拉 + contextJumpKey 定位）。
     * Feed 从这条开始向更旧翻页——考古层的"往下刷"语境即此。先收面板再跳，
     * 面板状态随主页覆盖层一起消亡，重进主页自然归零。
     */
    fun browseArchaeoLayer(item: VideoItem) {
        showArchaeo = false
        onOpenItem(item)
    }

    // ---------------- 派生视图状态（Tab / 类型筛选 / 合集归组） ----------------

    // 各类型计数（视频/图文/音频）：驱动类型筛选小 tab 的显隐；
    // 统计行只在 R49 探测不可用时拿它兜底（"已加载口径"，翻页越多越接近全集）
    val typeCounts by remember {
        derivedStateOf {
            var video = 0
            var photo = 0
            var audio = 0
            for (it in mediaItems) when (it.mediaType) {
                0 -> video++
                1 -> photo++
                else -> audio++
            }
            Triple(video, photo, audio)
        }
    }

    // 合集归组：albumId 非 0 的条目按 albumId 分组，组内最新一条当代表格
    //（封面=代表格封面链），角标带组内条数；按代表格 messageId 新→旧排
    val albums by remember {
        derivedStateOf {
            mediaItems.asSequence()
                .filter { it.albumId != 0L }
                .groupBy { it.albumId }
                .map { (albumId, group) ->
                    AlbumEntry(albumId, group.maxBy { it.messageId }, group.size)
                }
                .sortedByDescending { it.rep.messageId }
        }
    }
    val hasAlbums = albums.isNotEmpty()

    // R142 追剧模式（可选加成）：作品 tab 的剧集链聚合卡——识别出的链（≥2 集，
    // 识别纯函数见 core/feed/EpisodeChain.kt：caption 编号正则族 + 相册组 +
    // 时间邻接 + 时长接近）聚成一张格：封面取链内最新一集（代表格占原位，顺序
    // 不动），角标"全集 N 集"；点开从第 1 集进频道 Feed（onOpenItem → openChannelAt
    // 锚定首拉定位第 1 集，播完 ENDED 后自动连播走链内下一集）。
    // 只在"全部/视频"筛选下生效；图文/音频档链成员天然为零走原分支。识别对已
    // 加载条目集生效，深翻补页后链自动补全（聚合卡数量随之变化）。
    // R221 ANR 修复：链聚合移出主线程。小米 2026-10-18 00:26 ANR 尸检实锤（用户
    // 口径「频道主页闪退」）：主线程 Runnable 烧在 episodeChains 的相册块两两配对
    // （EpisodeChain.kt:403 与 R212 提交行号逐字节吻合），Input dispatching timed
    // out 5s → 系统杀进程。旧版 derivedStateOf 在主线程对 mediaItems 整表重算：
    // R212 无限滚动把表从一页十来条喂到数百上千条，每次到货（pageArrivals++）都
    // 重算一遍——平方级聚类 × 无限增长的 N × 每页重算 = 计算风暴。
    // 改为防抖 + Default 线程异步算：
    //  - 150ms 防抖：填坑式连发到货只算末态（中间态被取消，不做无用功）；
    //  - 新到货重启 effect ＝ 在途计算作废，永不写陈旧结果；
    //  - 追剧卡语义不变（仍是对已加载条目集的全量口径），到货后晚一拍落卡。
    var chainAgg by remember(brief.id) { mutableStateOf(ChainAggregation()) }
    LaunchedEffect(mediaItems) {
        if (mediaItems.size < 2) return@LaunchedEffect
        delay(150)
        val agg = withContext(Dispatchers.Default) {
            // runCatching 只兜非取消 Throwable（episodeChains 是纯函数、体内无挂起点，
            // 取消只可能落在 withContext 边界上，不会吞结构化取消）
            runCatching {
                val chains = episodeChains(mediaItems)
                val cards = chains.map { c ->
                    EpisodeChainEntry(
                        rep = c.episodes.maxBy { it.item.messageId }.item,
                        first = c.episodes.first().item,
                        count = c.episodes.size,
                    )
                }
                val repMap = cards.associateBy { it.rep.key }
                // 链成员全集（除代表格）＝散格里要剔除的 key；代表格留在原位
                val members = chains.flatMapTo(HashSet()) { c -> c.episodes.map { it.item.key } }
                members.removeAll(repMap.keys)
                ChainAggregation(cards, repMap, members)
            }.getOrNull()
        } ?: return@LaunchedEffect
        chainAgg = agg
    }

    var selectedTab by remember(brief.id) { mutableStateOf(0) } // 0=作品 1=合集
    var typeFilter by remember(brief.id) { mutableStateOf(-1) } // -1=全部 0=视频 1=图文 2=音频
    // 防呆回落：合集 tab 在合集归组变空（筛选源数据被刷新）时回落作品 tab；
    // 类型筛选在该类型计数归零时回落"全部"
    LaunchedEffect(hasAlbums) { if (!hasAlbums && selectedTab == 1) selectedTab = 0 }
    LaunchedEffect(typeCounts) {
        val countOfFilter = when (typeFilter) {
            0 -> typeCounts.first
            1 -> typeCounts.second
            2 -> typeCounts.third
            else -> 1 // "全部"不回落
        }
        if (typeFilter >= 0 && countOfFilter == 0) typeFilter = -1
    }

    // 当前网格要展示的条目：作品 tab = mediaItems 按类型过滤；合集 tab = 各合集代表格
    val gridItems by remember {
        derivedStateOf {
            when {
                selectedTab == 1 -> albums.map { it.rep }
                // R142 追剧模式：剧集链成员（除代表格）从散格里剔除，聚成一张
                // 剧集卡（见 chainAgg 注释）——链外条目原样散格，顺序不动
                typeFilter < 0 || typeFilter == 0 ->
                    mediaItems.filter { it.key !in chainAgg.memberKeysWithoutRep }
                else -> mediaItems.filter { it.mediaType == typeFilter }
            }
        }
    }
    // 合集代表格的角标覆盖文本（key → "🖼 图集 ×N"）；作品 tab 恒为空表不查
    val albumBadges by remember {
        derivedStateOf {
            if (selectedTab == 1) albums.associate { it.rep.key to "🖼 图集 ×${it.count}" }
            else emptyMap()
        }
    }

    // banner 封面：取最新 10 条里第一张可用封面。CoverStore 抽帧（高清）优先于
    // coverBytes minithumbnail（低清柔焦）；都没有则 UI 回落渐变 banner。
    // key 用首条 key——首页到达/刷新换头时重取，翻页不打断
    val bannerHeadKey = mediaItems.firstOrNull()?.key
    val bannerCover by produceState<ImageBitmap?>(initialValue = null, bannerHeadKey) {
        value = null
        for (it in mediaItems.take(10)) {
            val fromStore = CoverStore.map[it.key]
            if (fromStore != null) {
                value = fromStore
                return@produceState
            }
            val decoded = it.coverBytes?.let { bytes ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }.getOrNull()
                }
            }
            if (decoded != null) {
                value = decoded
                return@produceState
            }
        }
    }

    val gridState = rememberLazyGridState()
    // 深翻触发：距底部 6 格内自动拉下一页；搜索模式下同一触发口改拉搜索结果下一页。
    // R212 再装填键：到货轮次（pageArrivals/searchArrivals）进 LaunchedEffect 键——
    // 旧版只在 shouldLoadMore **翻转**时发射，一轮翻页后若墙仍短（或翻页失败游标
    // 没推进），shouldLoadMore 保持 true 不再翻转 = 永不续拉（「上滑到底不再加载」
    // 的触发器侧死点）。到货记次变更即重启 effect：still-in-zone → 立即续拉下一页
    // （填坑式补页，「可一直滑」）；失败轮不记次（防失败风暴），用户上滑翻转即重试。
    // 一次翻页仍一发请求：loading 闸串行化（effect 重启撞上在途轮即早退）。
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(shouldLoadMore, pageArrivals, searchArrivals) {
        if (shouldLoadMore) {
            if (searchActive) searchLoadMore() else loadMore()
        }
    }

    // 头部是否已滚出视口：驱动顶栏实色 InkBlack 底的开关式"渐显"
    val headerGone by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            info.totalItemsCount > 0 && info.visibleItemsInfo.none { it.key == "home_header" }
        }
    }

    // 抖音作者主页头部尺寸：banner 高 + 64dp 头像（半径半压 banner 下缘）
    val bannerHeight = 190.dp
    val avatarSizeDp = 64

    // ---------------- R175 封面墙双指缩放列数（pinch 档位 = 封面大小偏好，落盘） ----------------
    // 手势语义与 R174 视频页 pinch 清屏同家族（design-proposal §3：双指即 pinch、
    // Initial pass 消费多指事件饿死滚动/点击），目标不同：那边连 chrome 渐隐、这边
    // 换列数档位。档位序列按当前网格实测宽涌现（[mediaWallPinchDetents]），档位落点
    // 换算成格子最小宽（[mediaWallDetentCellMin]）驱动 GridCells.Adaptive——偏好跨
    // 设备口径自洽（存"格子多大"不存"几列"），落盘 AppPrefs.mediaWallCellPref；
    // 未捏过（0）= R171 默认锚 105dp，行为逐字节不变（回归红线）。
    val wallPrefs = remember { dev.tgfeed.core.prefs.AppPrefs(app) }
    var mediaWallCellMin by remember(brief.id) {
        mutableStateOf(wallPrefs.mediaWallCellPref.takeIf { it > 0f } ?: MediaWallCellMin.value)
    }
    // 网格可用宽（px，onSizeChanged 实测——分屏/旋转/折叠自动跟随）与密度/间距
    var gridWidthPx by remember { mutableStateOf(0) }
    val gridDensity = LocalDensity.current.density
    val gridGapDp = TgFeedTokens.GridGap.value
    // 生效格子最小宽：存档偏好夹回当前屏合法档域（derivedStateOf 读两个状态，
    // 手势写 mediaWallCellMin 即时跟随；px≤0 未量好时用默认锚。密度进 remember
    // 键：折叠/切换显示密度时重建派生链，防捕获值陈旧）
    val effectiveCellMin by remember(gridDensity) {
        derivedStateOf {
            if (gridWidthPx <= 0 || gridDensity <= 0f) MediaWallCellMin.value
            else clampMediaWallCellPref(mediaWallCellMin, gridWidthPx / gridDensity, gridDensity, gridGapDp)
        }
    }
    // 当前列数（手势锚定/列数徽章共用；与 GridCells.Adaptive(effectiveCellMin) 同口径）
    val effectiveColumns by remember(gridDensity) {
        derivedStateOf {
            if (gridWidthPx <= 0 || gridDensity <= 0f) -1
            else mediaWallColumns(gridWidthPx / gridDensity, gridDensity, gridGapDp, effectiveCellMin)
        }
    }
    // pinch 进行中（列数徽章显隐）：徽章在松手后滞留 ~0.9s 再熄灭（GridScreen 捏合
    // 徽章同款节律——detectTransformGestures 类手势无结束回调，用时间戳熄灯）
    var pinchActive by remember { mutableStateOf(false) }
    var lastPinchAt by remember { mutableStateOf(0L) }
    LaunchedEffect(pinchActive) {
        while (pinchActive) {
            if (System.currentTimeMillis() - lastPinchAt > 900) pinchActive = false
            delay(100)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(TgFeedTokens.InkBlack),
    ) {
        LazyVerticalGrid(
            // R171 分辨率适配：列数随实测宽涌现（Adaptive），替写死 Fixed(3)——平板竖屏
            // 640dp 下旧 3 列格宽 212dp、横屏 340dp（"没适配"实锤）；手机竖屏仍 3 列
            // （MediaWallCellMin 口径回归红线，政策表见 Responsive.mediaWallColumns）。
            // R175：min 锚换成 pinch 偏好（默认=同 105dp 锚零变化），捏合换档即时生效。
            columns = GridCells.Adaptive(effectiveCellMin.dp),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                // 网格可用宽实测（档位序列/夹域/列数换算的输入）
                .onSizeChanged { gridWidthPx = it.width }
                // R175 pinch 手势层：挂网格本体（比内部 scrollable 外层，Initial pass
                // 先于其 Main pass）——双指落下瞬间起消费全部位移（竖滑滚动/操作行横滑/
                // 格子点击的 slop 全饿死），down/up 不碰（单指行为与旧版一致）。
                // 手势期间 scale = 双指距/起手距 → 目标档位 round(起始列/scale) 实时
                // 换档（跨阈值步进）；松手 snap 已在整数档上，偏好落盘。
                .pointerInput(brief.id) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var engaged = false
                        var startColumns = 0
                        var startSpan = 0f
                        var lastPressedCount = 0
                        while (true) {
                            val event = awaitPointerEvent(
                                androidx.compose.ui.input.pointer.PointerEventPass.Initial,
                            )
                            val pressed = event.changes.filter { it.pressed }
                            // 指数变化（含第二指落下/换指）即重锚：span 基线换新指对，
                            // 列数基线取当前生效列数（无累积漂移）
                            if (pressed.size >= 2 && pressed.size != lastPressedCount) {
                                engaged = true
                                startSpan = pinchSpan(pressed)
                                startColumns = mediaWallColumns(
                                    gridWidthPx / gridDensity, gridDensity, gridGapDp,
                                    clampMediaWallCellPref(
                                        mediaWallCellMin, gridWidthPx / gridDensity, gridDensity, gridGapDp,
                                    ),
                                )
                            }
                            lastPressedCount = pressed.size
                            if (engaged) {
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                                lastPinchAt = System.currentTimeMillis()
                                val span = pinchSpan(pressed)
                                if (startSpan > 0f && span > 0f) {
                                    val detents = mediaWallPinchDetents(
                                        gridWidthPx / gridDensity, gridDensity, gridGapDp,
                                    )
                                    val target = mediaWallPinchTargetColumns(startColumns, span / startSpan, detents)
                                    val targetCellMin = mediaWallDetentCellMin(
                                        target, gridWidthPx / gridDensity, gridDensity, gridGapDp,
                                    )
                                    if (targetCellMin != mediaWallCellMin) {
                                        mediaWallCellMin = targetCellMin
                                    }
                                }
                                pinchActive = true
                            }
                            if (pressed.isEmpty()) break
                        }
                        if (engaged) {
                            // 松手落盘：当前档代表宽写 AppPrefs（下次进来保持；夹域在
                            // 生效侧做，存档值可能带旧屏宽的档位落点）
                            wallPrefs.mediaWallCellPref = mediaWallCellMin
                        }
                    }
                },
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GridGap),
            verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GridGap),
        ) {
            // ---- 1~3：banner + 压边头像 + 信息区（占满整行） ----
            item(key = "home_header", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            // 容器高 = banner 高 + 头像半高 → 头像正好半压 banner 下缘
                            .height(bannerHeight + (avatarSizeDp / 2).dp),
                    ) {
                        // 顶部横幅：封面柔焦铺满 / 渐变回落。
                        // R162 零遮罩：banner 双端渐变遮罩（顶部 0.45 供状态栏/顶栏
                        // 可读、底部 0.35→1.0 融黑底）全删——顶栏标题改描边可读
                        // （AppTopBar overlayTitle），banner 原色直出底缘硬切
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(bannerHeight)
                                .align(Alignment.TopCenter),
                        ) {
                            val cover = bannerCover
                            if (cover != null) {
                                Image(
                                    bitmap = cover,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                // 无封面回落：TgBlue→黑 纵向渐变（与深色主题一体）
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(
                                                    TgFeedTokens.TgBlue.copy(alpha = 0.30f),
                                                    TgFeedTokens.Surface,
                                                    TgFeedTokens.InkBlack,
                                                ),
                                            ),
                                        ),
                                )
                            }
                        }
                        // 圆形头像半压 banner 下缘（左），InkBlack 描边圈出切口
                        Box(
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 16.dp),
                        ) {
                            HomeChannelAvatar(
                                chatId = brief.id,
                                title = brief.title,
                                avatarBytes = avatarBytes,
                                sizeDp = avatarSizeDp,
                                ringColor = TgFeedTokens.InkBlack,
                            )
                        }
                    }
                    // 信息区：频道名（17sp SemiBold）→ 简介（2 行可展开）→ 统计行 → 操作行
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(top = 10.dp),
                    ) {
                        // R324 离线降级 banner（频道页）：网络页失败→R212 索引兜底供给的
                        // 诚实标注（本地缓存内容）；在线 null 不显示。文案/判定全在
                        // OfflineDegradePolicy 单源，视觉件 OfflineHintPill 与 feed 共用
                        dev.tgfeed.core.net.OfflineDegradePolicy.channelHomeOfflineLine(networkOnline)?.let { line ->
                            OfflineHintPill(
                                text = line,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                        Text(
                            brief.title,
                            color = TgFeedTokens.TextHigh,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        // 简介行：2 行 clamp，溢出时给"展开/收起"。
                        // 真简介依赖 getChannelFullInfo 网关口（终案 §3.3 排 P3），
                        // 当前内容暂承载 @username 标识，clamp/展开机制一次就位
                        var bioExpanded by remember(brief.id) { mutableStateOf(false) }
                        var bioOverflow by remember(brief.id) { mutableStateOf(false) }
                        Text(
                            brief.username?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "暂无简介",
                            color = TgFeedTokens.TextMedium,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = if (bioExpanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { if (!bioExpanded) bioOverflow = it.hasVisualOverflow },
                        )
                        if (bioOverflow || bioExpanded) {
                            Text(
                                if (bioExpanded) "收起" else "展开",
                                color = TgFeedTokens.TgBlue,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
                                    .clickable { bioExpanded = !bioExpanded }
                                    .padding(vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        // 统计行（CH-A 修复）：作品数只由作品 tab 角标承担，这里不再重复；
                        // 一行 = 订阅状态 · [成员数 ·] 合集数 · 各类型计数
                        // R49：计数优先用服务端真实口径（probeChannelStats：SearchChatMessages
                        // totalCount + SupergroupFullInfo.memberCount）——本地 typeCounts 是
                        // "已加载口径"，翻页越多数字越大、并非真实总量（用户实测不准）。
                        // 探测中显示"统计中…"；探测不可用（Mock/全路失败）回落已加载口径，
                        // 文案与旧版一致、行为与现状一致
                        // R175 落实"如实标注来源"：回落分支补「已加载口径」后缀——服务端
                        // 拿不到时数字的语义（爬到多少算多少）不再冒充真实总量
                        val stats = buildString {
                            append(if (subscribed) "已追更" else "未追更")
                            val probed = channelStats
                            when {
                                // 探测中：合集数照显（与口径无关），类型计数位给占位文案
                                statsProbing && probed == null -> {
                                    if (albums.isNotEmpty()) append(" · 合集 ").append(albums.size)
                                    append(" · 统计中…")
                                }
                                probed != null -> {
                                    // 成员数：0/-1（隐藏/未知/失败）不显示；≥1万按"X.X万"一位小数
                                    if (probed.memberCount > 0) {
                                        append(" · 成员 ").append(formatHomeStatCount(probed.memberCount))
                                    }
                                    if (albums.isNotEmpty()) append(" · 合集 ").append(albums.size)
                                    // -1 = 该类探测失败，不显示该项
                                    if (probed.videoCount >= 0) append(" · 视频 ").append(formatHomeStatCount(probed.videoCount))
                                    if (probed.photoCount >= 0) append(" · 图文 ").append(formatHomeStatCount(probed.photoCount))
                                    if (probed.audioCount >= 0) append(" · 音频 ").append(formatHomeStatCount(probed.audioCount))
                                }
                                else -> {
                                    // 兜底：已加载口径（探测不可用，文案与旧版一致）+ 来源标注
                                    if (albums.isNotEmpty()) append(" · 合集 ").append(albums.size)
                                    if (typeCounts.first > 0) append(" · 视频 ").append(typeCounts.first)
                                    if (typeCounts.second > 0) append(" · 图文 ").append(typeCounts.second)
                                    if (typeCounts.third > 0) append(" · 音频 ").append(typeCounts.third)
                                    append(" · 已加载口径")
                                }
                            }
                        }
                        Text(
                            stats,
                            color = TgFeedTokens.TextLow,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        // R133 频道基因鉴定行（#19，简介区收口位）：本地深度索引对该频道
                        // 一次 SQL 聚合（条数/时间跨度/三类媒体构成/平均时长）拼成的一行签
                        //（「日更 3 条 · 主打视频 · 平均 8 分钟 · 几乎无图文」）——
                        // R204：聚合源上移为 channelGenome（考古年龄/编年史下界/断更
                        // 频率同一份基因，一源三用零重复查询），此处只做格式化
                        val genomeLine = channelGenome?.let { formatChannelGenome(it) }
                        genomeLine?.let { line ->
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // 青绿引导点：与统计行的纯文本行区分开（鉴定 = 新信息段）
                                Box(
                                    Modifier
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(TgFeedTokens.SuccessGreen),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "基因鉴定 · $line",
                                    color = TgFeedTokens.TextMedium,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        // R204 DS-3 断更/僵尸徽章行：基因口径（最近发布距今 + 历史频率
                        // 低频缓冲）出档才渲染——「47 天未更」/「疑似停更 132 天」事实
                        // 陈述体，警示色与基因行的青绿点区分语义
                        val nowSec = System.currentTimeMillis() / 1000L
                        val dormancyLine = channelGenome?.let { g ->
                            val spanSec = (g.maxDateSec - g.minDateSec).coerceAtLeast(7L * 86_400)
                            dev.tgfeed.core.feed.ChannelDormancy.homeLineOf(
                                lastActivitySec = g.maxDateSec,
                                nowSec = nowSec,
                                postsPerWeek = g.count.toDouble() * 7.0 * 86_400 / spanSec,
                            )
                        }
                        dormancyLine?.let { line ->
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(TgFeedTokens.WarningRed),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    line,
                                    color = TgFeedTokens.WarningRed,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        // R204 TL-7 频道级回放热力行（chatId 级聚合，样本 ≥3 才出）：
                        // 点开「精彩段落」洞察面板（底部 sheet——触发点在页面中部）
                        seekInsight?.let { insight ->
                            Spacer(Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
                                    .clickable { showSeekHeat = true }
                                    .padding(vertical = 2.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(TgFeedTokens.TgBlue),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "回放热力 · ${SeekHeatPolicy.insightHeadline(insight)}",
                                    color = TgFeedTokens.TgBlue,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        // 操作行（kit AppPill 胶囊）：追更（未追=红实心主操作；已追=描边
                        // 次要态，再点退追——主页是管理页，与右栏头像 ✓ 防呆互补）/
                        // 合集（有图集才出现，点了切到合集 tab）/ 分享（系统分享面板）/
                        // 屏蔽（频道级硬屏蔽；已屏蔽时变"已屏蔽"，再点=解除）。
                        // 胶囊最多 4 枚（追更/合集/分享/屏蔽），窄屏可能超出可用宽——整行可横滑
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        ) {
                            AppPill(
                                text = if (subscribed) "已追更" else "追更",
                                selected = !subscribed,
                                onClick = {
                                    if (subscribed) vm.removeSubscription(brief.id)
                                    else vm.addSubscription(brief)
                                },
                                icon = if (subscribed) Icons.Outlined.Check else Icons.Outlined.Add,
                            )
                            if (hasAlbums) {
                                AppPill(
                                    text = "合集",
                                    selected = selectedTab == 1,
                                    onClick = {
                                        // 从搜索模式点合集：先退回普通网格再切 tab
                                        if (searchActive) exitSearch()
                                        selectedTab = 1
                                    },
                                    icon = Icons.Outlined.Collections,
                                )
                            }
                            AppPill(
                                text = "分享",
                                selected = false,
                                onClick = shareChannel,
                                icon = Icons.Outlined.Share,
                            )
                            // 编年史（R134 时间机器 / R140 叙事重造 / R204 TL-5 时间轴升级）：
                            // 老频道长尾库存的入口——左抽屉年历热力墙（年块=热度+条数+直跳
                            // 当年峰日）+ 随机穿越 → 穿越过场 → 年代卡 + 这一层预览网格，
                            // 点任意格进层。Mock 演示网关没有考古库存：胶囊置灰
                            //（§2.4.7 置防），点击只提示不开面板
                            val archaeoMockDemo = gateway is MockTdGateway
                            AppPill(
                                text = "编年史",
                                selected = showArchaeo,
                                onClick = {
                                    if (archaeoMockDemo) {
                                        android.widget.Toast.makeText(
                                            context, "演示模式没有考古库存", android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    } else {
                                        showArchaeo = true
                                    }
                                },
                                icon = Icons.Outlined.History,
                                modifier = Modifier.alpha(if (archaeoMockDemo) 0.4f else 1f),
                            )
                            // 屏蔽：未屏蔽时点按弹确认框（防误触，对话框在本屏尾部）；
                            // 已屏蔽时胶囊变"已屏蔽"，点按=定向解除（unblockChannel，非翻转语义）
                            AppPill(
                                text = if (blocked) "已屏蔽" else "屏蔽",
                                selected = false,
                                onClick = {
                                    if (blocked) {
                                        vm.unblockChannel(brief.id)
                                        android.widget.Toast.makeText(
                                            context, "已解除屏蔽", android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    } else {
                                        showBlockConfirm = true
                                    }
                                },
                                icon = Icons.Outlined.Block,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            // ---- 4：Tab 行（作品/合集）+ 作品 tab 下的类型筛选小 tab ----
            // 搜索模式下收起（搜索结果不按 tab/类型再分）
            if (!searchActive) {
                item(key = "home_tabs", span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(TgFeedTokens.InkBlack),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            // 数量角标留在 tab 上（CH-A：统计行不再重复"作品 N"）。
                            // R175 资源总数如实：角标 = 服务端真实总数（probeChannelStats 的
                            // 三类 SearchChatMessages totalCount 求和，"频道一共多少资源"），
                            // 探测不可用回落已加载数（旧文案数字）——本地爬到多少算多少的
                            // 口径不再冒充总数；统计行的「已加载口径」后缀兜住来源标注
                            val worksCount = channelStats?.resourceTotalOrNull() ?: mediaItems.size
                            HomeTab(
                                label = "作品 " + formatHomeStatCount(worksCount),
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                            )
                            // 合集 tab：频道有图集才出现
                            if (hasAlbums) {
                                HomeTab(
                                    label = "合集 " + albums.size,
                                    selected = selectedTab == 1,
                                    onClick = { selectedTab = 1 },
                                )
                            }
                        }
                        // 类型筛选小 tab（全部/视频/图文/音频）：下划线式，与 Feed/搜索页
                        // tab 同一语言（替掉旧 chip 胶囊排）；只在作品 tab 显示，频道只有
                        // 单一类型时整排收起（无筛选意义）
                        val chipTypes = buildList {
                            if (typeCounts.first > 0) add(0 to "视频")
                            if (typeCounts.second > 0) add(1 to "图文")
                            if (typeCounts.third > 0) add(2 to "音频")
                        }
                        if (selectedTab == 0 && chipTypes.size >= 2) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .padding(top = 2.dp, bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(18.dp),
                            ) {
                                HomeFilterTab(
                                    label = "全部",
                                    selected = typeFilter < 0,
                                    onClick = { typeFilter = -1 },
                                )
                                for ((type, label) in chipTypes) {
                                    HomeFilterTab(
                                        label = label,
                                        selected = typeFilter == type,
                                        onClick = { typeFilter = type },
                                    )
                                }
                            }
                        }
                        // Tab 块底部分隔发丝线：网格内容从其下滚过时勾出边界
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(TgFeedTokens.OverlayHairline),
                        )
                    }
                }
            }

            // ---- 5：3 列封面墙（作品 / 合集代表格 / 搜索结果） ----
            val displayItems = if (searchActive) searchItems else gridItems
            items(displayItems, key = { it.key }) { item ->
                // R142：代表格命中剧集链 → 聚合卡（角标"全集 N 集"，点开从第 1 集连播）；
                // 搜索模式不聚合（结果集是搜索快照，链语义留在频道网格）
                val chainCard = if (searchActive) null else chainAgg.repByKey[item.key]
                MediaGridCell(
                    item = item,
                    gateway = gateway,
                    badgeOverride = chainCard?.let { "全集 ${it.count} 集" }
                        ?: if (searchActive) null else albumBadges[item.key],
                    onClick = { onOpenItem(chainCard?.first ?: item) },
                )
            }

            // 搜索模式空态：空词 = 引导；有词搜完仍空 = 没有找到
            if (searchActive) {
                if (searchInput.isBlank()) {
                    item(key = "home_search_hint", span = { GridItemSpan(maxLineSpan) }) {
                        AppEmptyState(
                            icon = Icons.Outlined.Search,
                            title = "搜索本频道内容",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp),
                            subtitle = "输入关键词，只在这个频道里找",
                        )
                    }
                } else if (!searchLoading && searchItems.isEmpty() && searchExhausted) {
                    item(key = "home_search_empty", span = { GridItemSpan(maxLineSpan) }) {
                        AppEmptyState(
                            icon = Icons.Outlined.Search,
                            title = "没有找到相关内容",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp),
                            subtitle = "换个关键词试试",
                        )
                    }
                }
            }

            // 普通模式空态：首拉完成仍为空 / 筛选或合集 tab 下无内容
            if (!searchActive && gridItems.isEmpty() && !loading && exhausted) {
                item(key = "home_empty", span = { GridItemSpan(maxLineSpan) }) {
                    AppEmptyState(
                        icon = if (selectedTab == 1) Icons.Outlined.Collections else Icons.Outlined.Inbox,
                        title = when {
                            selectedTab == 1 -> "这个频道还没有合集"
                            typeFilter >= 0 -> "该类型下暂无内容"
                            else -> "这个频道还没有媒体内容"
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                    )
                }
            }

            // 底部状态行：加载中（kit AppLoading，内建 40dp/3dp/TextHigh+trackColor
            // 白点修复规格，勿回退）/ 到底（合集 tab 同理——分页继续拉会有新合集出现）/
            // R212 失败（网络超时/异常且索引没兜上——失败≠到底，上滑即重试）
            item(key = "home_status", span = { GridItemSpan(maxLineSpan) }) {
                when {
                    searchActive && searchLoading -> AppLoading(
                        text = if (searchItems.isEmpty()) "正在搜索…" else "正在加载…",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (searchItems.isEmpty()) 300.dp else 96.dp),
                    )
                    searchActive && searchExhausted && searchItems.isNotEmpty() -> HomeEndText()
                    !searchActive && loading -> AppLoading(
                        text = if (gridItems.isEmpty()) "正在加载频道内容…" else "正在加载…",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (gridItems.isEmpty()) 300.dp else 96.dp),
                    )
                    !searchActive && exhausted && gridItems.isNotEmpty() -> HomeEndText()
                    !searchActive && loadFailed -> HomeRetryText()
                }
            }
        }

        // 顶栏：普通态 = kit AppTopBar（返回 + 频道名标题 + 动作槽 🔍频道内搜索，
        // 修 CH-B"无页面标题"；头部滚出视口后实色 InkBlack 底，头部在视口内时透明
        // 压在 banner 上，标题靠 overlayTitle 描边可读——R162 起不再有 banner 顶部
        // 渐变遮罩兜底）；搜索态 = 变形为胶囊搜索框
        if (searchActive) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TgFeedTokens.InkBlack)
                    .statusBarsPadding()
                    .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapSm),
            ) {
                AppIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "退出搜索",
                    onClick = exitSearch,
                )
                Spacer(Modifier.width(TgFeedTokens.GapMd))
                // 胶囊搜索框：与 SearchScreen 同一套 TextField 样式（全圆角 SurfaceHigh 底）
                TextField(
                    value = searchInput,
                    onValueChange = { searchInput = it }, // 只更新状态，搜索交给防抖 effect
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(searchFocus),
                    placeholder = {
                        Text("搜索本频道内容", color = TgFeedTokens.TextLow)
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TgFeedTokens.TextHigh),
                    shape = CircleShape,
                    trailingIcon = {
                        if (searchInput.isNotEmpty()) {
                            Icon(
                                Icons.Outlined.Clear,
                                contentDescription = "清空",
                                tint = TgFeedTokens.TextLow,
                                modifier = Modifier
                                    .size(TgFeedTokens.IconMd)
                                    .clickable { searchInput = "" },
                            )
                        }
                    },
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = TgFeedTokens.TextHigh,
                        unfocusedTextColor = TgFeedTokens.TextHigh,
                        cursorColor = TgFeedTokens.TextHigh,
                        focusedContainerColor = TgFeedTokens.SurfaceHigh,
                        unfocusedContainerColor = TgFeedTokens.SurfaceHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                )
            }
        } else {
            AppTopBar(
                title = brief.title,
                onBack = onBack,
                // 头部在视口内时顶栏透明压在 banner 封面上：标题描边可读（零遮罩政策）
                overlayTitle = !headerGone,
                modifier = Modifier.background(
                    if (headerGone) TgFeedTokens.InkBlack else Color.Transparent,
                ),
                actions = {
                    AppIconButton(
                        icon = Icons.Outlined.Search,
                        contentDescription = "频道内搜索",
                        onClick = { searchActive = true },
                    )
                },
            )
        }

        // "屏蔽"确认对话框（防误触）：确认 = 屏蔽本频道 + Toast 指路设置页 + 返回 Feed
        //（toggleBlockChannel 会立即从 Feed 列表剔除该频道条目，留在主页没有意义）；
        // 取消则留在本页。brief.id 已在屏蔽表时不会走到这里（胶囊点按直接解除，见操作行）
        if (showBlockConfirm) {
            AlertDialog(
                onDismissRequest = { showBlockConfirm = false },
                title = { Text("屏蔽该频道？") },
                text = { Text("屏蔽后「${brief.title}」从推荐/搜索/聚合中消失，可在设置→屏蔽的频道里解除") },
                confirmButton = {
                    TextButton(onClick = {
                        showBlockConfirm = false
                        vm.toggleBlockChannel(brief.id)
                        android.widget.Toast.makeText(
                            context, "已屏蔽，可在设置→屏蔽的频道里解除", android.widget.Toast.LENGTH_SHORT,
                        ).show()
                        onBack()
                    }) { Text("屏蔽") }
                },
                dismissButton = {
                    TextButton(onClick = { showBlockConfirm = false }) { Text("取消") }
                },
            )
        }

        // 时间机器·频道编年史面板（R134 底座 / R140 叙事重造 / R204 TL-5 年历热力墙）：
        // 年块时间轴 + 随机穿越 → 穿越过场 → 年代卡 + 这一层预览网格（点任意格进层）
        if (showArchaeo) {
            ArchaeoSheet(
                dig = archaeoDig,
                busy = archaeoBusy,
                plan = archaeoPlan,
                failed = archaeoFailed,
                ageYears = archaeoAgeYears,
                chronicle = chronicle,
                chronicleBusy = chronicleBusy,
                onDigYears = { y -> runDig(random = false, yearsAgo = y, archaeoTarget(y), "${y}年前") },
                onDigEpoch = { yearsAgo, targetSec ->
                    // 年块穿越：直跳当年峰日（R204 TL-5；穿越过场/落地卡走 R140 原件）
                    runDig(random = false, yearsAgo = yearsAgo, targetEpochSec = targetSec, "${currentYearNow - yearsAgo}年")
                },
                onExtendChronicle = {
                    loadChronicle(dev.tgfeed.core.feed.FeedChroniclePart.EXTENDED_MONTHS_CAP)
                },
                onDigRandom = { runDig(random = true, tier = "随机穿越") },
                onRedig = {
                    // 再挖一次：重放最近一次挖掘请求（随机 = 重摇，时间档位 = 重取这一层）
                    archaeoPlan?.let { runDig(it.random, it.yearsAgo, it.targetEpochSec, it.tierLabel) }
                },
                onBrowse = ::browseArchaeoLayer,
                onDismiss = { showArchaeo = false },
            )
        }

        // R204 TL-7 频道级回放热力洞察面板（「精彩段落」）：chatId 级聚合的分布视图
        if (showSeekHeat) {
            ChannelSeekHeatSheet(
                channelTitle = brief.title,
                insight = seekInsight,
                onDismiss = { showSeekHeat = false },
            )
        }

        // R175 pinch 列数徽章：捏合进行中在顶栏下方中央报当前档位（"N 列"）——
        // 档位序列肉眼可读（录屏取证自证），松手 ~0.9s 滞留后熄灭（GridScreen 捏合
        // 徽章同款节律）。浮层底走 OverlayBg token（R162 家，勿私写数字）
        if (pinchActive && effectiveColumns > 0) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 60.dp)
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                    .background(TgFeedTokens.OverlayBg)
                    .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusPill))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    "$effectiveColumns 列",
                    color = TgFeedTokens.TextHigh,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** 合集归组条目：albumId + 代表格（组内最新一条）+ 组内条数 */
private data class AlbumEntry(
    val albumId: Long,
    val rep: VideoItem,
    val count: Int,
)

/** R142 剧集链聚合卡：rep=代表格（链内最新一集，占散格原位）；first=第 1 集（点击落点）；count=链内集数 */
private data class EpisodeChainEntry(
    val rep: VideoItem,
    val first: VideoItem,
    val count: Int,
)

/** R142 剧集链聚合的派生产物：卡列表 + 代表格路由表 + 散格里要剔除的链成员 key 集 */
private data class ChainAggregation(
    val cards: List<EpisodeChainEntry> = emptyList(),
    val repByKey: Map<String, EpisodeChainEntry> = emptyMap(),
    val memberKeysWithoutRep: Set<String> = emptySet(),
)

/** 网格底部"到底了"行（普通/搜索模式共用） */
@Composable
private fun HomeEndText() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "到底了",
            color = TgFeedTokens.TextLow,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * R212 网格底部"加载失败"行：网络超时/异常且索引没兜上时的诚实提示——
 * 失败≠到底（旧版直接吞成「到底了」/空态），上滑重试零成本（触发器翻转即重拉）
 */
@Composable
private fun HomeRetryText() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "加载失败，上滑重试",
            color = TgFeedTokens.TextLow,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** 主页 Tab（作品/合集）：选中 = 白粗体 + 抖音红短下划线；未选 = 二级灰 */
@Composable
private fun HomeTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = if (selected) TgFeedTokens.TextHigh else TgFeedTokens.TextMedium,
            style = if (selected) {
                MaterialTheme.typography.titleSmall
            } else {
                MaterialTheme.typography.bodyMedium
            },
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .width(22.dp)
                .height(2.5.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) TgFeedTokens.AccentRed else Color.Transparent),
        )
    }
}

/** 类型筛选小 tab（全部/视频/图文/音频）：下划线式，与 Feed/搜索页 tab 同一语言 */
@Composable
private fun HomeFilterTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (selected) TgFeedTokens.TextHigh else TgFeedTokens.TextLow,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .width(16.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) TgFeedTokens.AccentRed else Color.Transparent),
        )
    }
}

/** 主页头部频道头像：真头像圆形裁剪；无头像回落首字字母圆（底色按 chatId 稳定取色） */
@Composable
private fun HomeChannelAvatar(
    chatId: Long,
    title: String,
    avatarBytes: ByteArray?,
    sizeDp: Int,
    ringColor: Color? = null, // 非空时描一圈边（压 banner 下缘用页面底色圈出切口）
) {
    val image = remember(avatarBytes) {
        avatarBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    val shape = CircleShape
    val ring = if (ringColor != null) {
        Modifier.border(3.dp, ringColor, shape)
    } else {
        Modifier
    }
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(sizeDp.dp)
                .then(ring)
                .clip(shape),
        )
    } else {
        Box(
            Modifier
                .size(sizeDp.dp)
                .then(ring)
                .clip(shape)
                .background(homeAvatarColor(chatId)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title.firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
            )
        }
    }
}

/** 统计行计数格式化（R49 真实口径分支专用）：≥1万 按"X.X万"一位小数截断；不足 1万 原样 */
private fun formatHomeStatCount(n: Int): String =
    if (n >= 10000) "${n / 10000}.${(n % 10000) / 1000}万" else n.toString()

/** 头像回落字母圆的稳定配色（与右栏头像/BottomInfoPanel 同思路） */
private val HomeAvatarPalette = listOf(
    Color(0xFFE17055), Color(0xFF6C5CE7), Color(0xFF00B894),
    Color(0xFF0984E3), Color(0xFFFD79A8), Color(0xFFE1A200),
)

private fun homeAvatarColor(chatId: Long): Color =
    HomeAvatarPalette[Math.floorMod(chatId, HomeAvatarPalette.size)]

/**
 * 媒体网格单格：3:4 封面 + 类型角标（视频时长 / 图集 / ♪ 音频）。
 * badgeOverride 非空时替换类型角标（合集 tab 代表格的"🖼 图集 ×N"走这里）。
 * 封面链（修"封面模糊"）：CoverStore 抽帧（播过就有）→ coverBytes minithumbnail
 * 秒出占位 → 高清按需换上：视频/动图走 coverFileId（loadCoverBytes）、音频走
 * audioCoverFileId（loadAudioCoverBytes）、图片无独立缩略图 fileId（网关映射只给
 * minithumbnail 糊图）——走 ensurePlayable 原图 + 采样解码（loadPhotoCoverBitmap）。
 */
@Composable
private fun MediaGridCell(
    item: VideoItem,
    gateway: TdGateway,
    badgeOverride: String? = null,
    onClick: () -> Unit,
) {
    val cover: ImageBitmap? = produceState<ImageBitmap?>(initialValue = null, item.key) {
        CoverStore.map[item.key]?.let { value = it }
        if (value == null) {
            // minithumbnail 占位解码（分支①）：随全链下放 IO（见下方 R169 注释）
            value = item.coverBytes?.let { bytes ->
                withContext(Dispatchers.IO) {
                    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap() }.getOrNull()
                }
            }
        }
        val lib = gateway as? TdLibGateway
        if (lib != null) {
            // R169（F8，R83b 同病止血）：分支②取字节（内含文件读+下载等待）+ 全尺寸解码
            // 原跑在 produceState 主派发器——频道主页网格几十格成串主线程解码掉帧；
            // 与分支③（图片，原本就有 IO）统一：三分支全链下放 IO 派发器。
            // 取字节+解码整段下放（decode 失败保留旧占位语义不变）
            val hd: ImageBitmap? = withContext(Dispatchers.IO) {
                val bytes = when {
                    item.mediaType == 2 && item.audioCoverFileId >= 0 ->
                        runCatching { lib.loadAudioCoverBytes(item) }.getOrNull()
                    item.coverFileId >= 0 ->
                        runCatching { lib.loadCoverBytes(item) }.getOrNull()
                    else -> null
                }
                bytes?.let {
                    runCatching { BitmapFactory.decodeByteArray(it, 0, it.size).asImageBitmap() }.getOrNull()
                }
            }
            if (hd != null) value = hd
            // 图片（mediaType=1）高清封面：coverFileId 恒为 -1，走原图采样解码链
            //（IO 线程读字节+解码，不挡组合线程；失败保留 minithumbnail 占位）
            if (item.mediaType == 1 && item.fileId >= 0) {
                val photoHd = withContext(Dispatchers.IO) {
                    runCatching { loadPhotoCoverBitmap(lib, item) }.getOrNull()
                }
                if (photoHd != null) value = photoHd
            }
        }
    }.value

    Box(
        Modifier
            // R175 pinch 档位过渡（最外层：animateContentSize 要包住定尺寸链才能拦截
            // 汇报尺寸的变化）：列数档位切换时格子宽随槽位变（126→157dp），格子尺寸
            // 平滑过渡——内容用 tween 纪律（动效总表「布局重排」= AnimSlow 320ms；
            // 手势跟手换档期间连续重定目标，tween 天然平滑追赶）。行高随格子同步动画
            .animateContentSize(animationSpec = tween(TgFeedTokens.AnimSlow))
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
            .background(TgFeedTokens.SurfaceHigh)
            .clickable(onClick = onClick),
    ) {
        if (cover != null) {
            Image(
                bitmap = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 类型角标（左下）：视频=时长；图集=🖼 图集；单图=图；音频=♪；
        // 合集 tab 由 badgeOverride 覆盖为"🖼 图集 ×N"。
        // R162 收敛：样式本体收编 OverlayInfoChip（黑 α0.35+发丝描边，一处改全局）
        val badge = badgeOverride ?: when (item.mediaType) {
            0 -> formatCellDuration(item.durationSec)
            1 -> if (item.albumId != 0L) "🖼 图集" else "图"
            else -> "♪ 音频"
        }
        OverlayInfoChip(
            text = badge,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp),
            contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

/** 格子角标时长：m:ss，超过一小时为 h:mm:ss（与 ui/components/Formatters 同口径，避免跨包 internal 引用） */
private fun formatCellDuration(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    val h = s / 3600
    val m = s % 3600 / 60
    val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}

/**
 * R175 pinch：当前按下指针集前两指的直线距（<2 指返回 0，调用方守卫）。
 * 手势比例 = 当前距/起手距；指数变化（换指/第三指）由调用方重锚基线防跳变。
 */
private fun pinchSpan(
    pressed: List<androidx.compose.ui.input.pointer.PointerInputChange>,
): Float {
    if (pressed.size < 2) return 0f
    val a = pressed[0].position
    val b = pressed[1].position
    return kotlin.math.hypot(a.x - b.x, a.y - b.y)
}

// ---------------- 图片网格封面：原图采样解码（高清链的图片分支） ----------------

/** 网格封面采样短边目标像素：3:4 格子在主流屏约 240~360dp 宽，720 足够清晰且控内存 */
private const val CELL_COVER_SHORT_EDGE = 720

/** 单格原图体积上限：超过不拉（防个别特大原图撑内存；该格保留 minithumbnail 占位） */
private const val CELL_PHOTO_MAX_BYTES = 15L * 1024 * 1024

/**
 * R221 图片格高清封面并发闸（4 路）：小米 ANR 尸检同期 ≥3 路 DefaultDispatch 同时
 * 卡在 [loadPhotoCoverBitmap]（ensurePlayable 整图下载 + 读字节 + 解码）——R212
 * 无限墙快速甩滑时进入组合的格子以百计，每格自发一发高清链 = 下载/堆瞬时峰值风暴。
 * 闸内排队者随格子滑出组合被取消（不再补发）；minithumbnail 占位链不经闸（格子
 * 先见低清图）。收敛在共享件内部：频道主页墙（R221）与收藏墙（R199 复用本函数）
 * 一处生效。
 */
private val photoCoverHdGate = Semaphore(4)

/**
 * 图片网格封面的高清路径（调用方已在 IO 协程）：
 * ensurePlayable 取原图（已下载完成秒回本地路径；未下载回本地流服务器 127.0.0.1 地址，
 * 边下边读）→ 读字节 → 先量尺寸再按 2 的幂采样解码（与 decodeSharePhotoSampled 同手法）。
 * 只认本地路径与本机流服务器——索引薄条目可能带 http 外链 playUri，不为封面去拉远程图。
 * 任一步失败返回 null（调用方保留 minithumbnail/灰底占位，不报错）。
 * R199 起可见性提升 internal：收藏墙（FavoritesScreen）复用同一件（图片格高清封面
 * 同一条链），不再复制第二份采样解码实现——R171/R199 媒体墙家族共享件。
 */
internal suspend fun loadPhotoCoverBitmap(lib: TdLibGateway, item: VideoItem): ImageBitmap? {
    if (item.sizeBytes > CELL_PHOTO_MAX_BYTES) return null
    // R221：并发闸（4 路）——整图下载+读字节+解码的瞬时风暴兜底，取消安全
    return photoCoverHdGate.withPermit {
        val uri = runCatching { lib.ensurePlayable(item) }.getOrNull() ?: return@withPermit null
        val bytes = when {
            uri.startsWith("http://127.0.0.1") ->
                runCatching { java.net.URL(uri).openStream().use { it.readBytes() } }.getOrNull()
            uri.startsWith("http") -> null // 外链不为封面拉取
            else -> {
                val f = java.io.File(uri.removePrefix("file://"))
                if (f.isFile) runCatching { f.readBytes() }.getOrNull() else null
            }
        } ?: return@withPermit null
        if (bytes.isEmpty() || bytes.size > CELL_PHOTO_MAX_BYTES) return@withPermit null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withPermit null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= CELL_COVER_SHORT_EDGE) {
            sample *= 2
        }
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )?.asImageBitmap()
    }
}

// ---------------- 时间机器·频道编年史（R134 底座 / R140 叙事重造 / R381 时间脊柱·光流带） ----------------

/**
 * 编年史面板（R178 起为 AppSheetMotion 左抽屉，R140 时间机器叙事，design-redesign §2.4；
 * R204 TL-5 年历热力墙 → R381 升级为时间脊柱·光流带——数据底座一概原样）：
 *  - 开场文案按频道真实年龄说话（§2.4.2 数据化）+ 编年史摘要行（首年→今天 + 覆盖月数）；
 *  - 档位区 = **时间脊柱·光流带**（R381：左缘垂直年代脊柱=年代色族色温隐喻，年轻=炽蓝、
 *    中年=琥珀、古老=赭石；脊柱挂年代段卡——年份大字+当年条数+峰日行（峰日金标，
 *    StarYellow 实心小点+同色低 α 紧晕），点段卡=穿越到当年最热闹的一天；卷身=月历
 *    12 月桶连续光流带（列高∝当月条数连续映射，列顶 quadratic 连线成光流轮廓）；
 *    段卡进场 stagger 揭示）+ 覆盖外早年块（暗节点，照可穿越）+「回溯更早」扩展按钮
 *    （首批 12 个月→上限 24）+ 一行"随机穿越"宽卡（盲盒情绪与怀旧情绪分流，§2.4.1）；
 *  - 挖掘 1–5s 不裸转：结果区做"穿越过场"（年份倒滚 + 目标日期大字 + 做旧色底，
 *    §2.4.3 过程感强制位）；
 *  - 挖到 = 年代卡（大字日期 + 距今 + 挖到 N 条）+ 3 列预览网格；点任意格直接进
 *    这一层（[onBrowse]，R134 的通道原样保留——"不改清单"点格即进层）；
 *    "逛考古层"主按钮删除（§2.4.4 预览格即入口），主行动位换"再挖一次"次按钮；
 *  - 失败/空态 = 状态图标 + 原因 + 重试（§2.4.6）；
 *  - 跳跃层数/探测/误差只在 logcat，用户可见词只用穿越/年代/挖到/这一层（§2.4.8）。
 */
@Composable
private fun ArchaeoSheet(
    dig: ArchaeoDig?,
    busy: Boolean,
    plan: ArchaeoPlan?,
    failed: Boolean,
    ageYears: Int?,
    chronicle: ChronicleResult?,
    chronicleBusy: Boolean,
    onDigYears: (Int) -> Unit,
    onDigEpoch: (yearsAgo: Int, targetSec: Int) -> Unit,
    onExtendChronicle: () -> Unit,
    onDigRandom: () -> Unit,
    onRedig: () -> Unit,
    onBrowse: (VideoItem) -> Unit,
    onDismiss: () -> Unit,
) {
    // 年代卡的年份大字 = 当前年 − 档位年数（跨年瞬间的误差以月计，可接受）
    val currentYear = remember { java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) }
    // 结果区四态：0 引导 / 1 穿越过场 / 2 年代卡+这一层网格 / 3 失败——切换走
    // "快出慢进 + 轻微放大"的揭示节律（§3.4 结果揭示 scaleIn 0.92→1 AnimSlow）
    val stage = when {
        busy -> 1
        dig != null -> 2
        failed -> 3
        else -> 0
    }
    // R178 就近锚定迁移：考古入口在频道主页头部胶囊行（顶部域）→ 左抽屉形态
    // （全高抽屉无半展开概念，旧「挖到自动全展开」的 expand 逻辑随之退役）
    AppSheetMotion(onDismiss = onDismiss, title = "频道编年史", anchor = SheetAnchor.LEFT) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TgFeedTokens.GapLg),
        ) {
            Text(
                archaeoIntroLine(ageYears),
                color = TgFeedTokens.TextMedium,
                style = MaterialTheme.typography.bodySmall,
            )
            // R204 TL-5 编年史摘要行（首年→今天 + 详细档案覆盖月数；null = 两源皆无，
            // 面板回落纯考古叙事——诚实空态不硬凑）
            chronicle?.chronicle?.let { c ->
                ChannelChronicle.summaryLine(c)?.let { line ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        line,
                        color = TgFeedTokens.TextLow,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Spacer(Modifier.height(TgFeedTokens.GapMd))
            // 年代档位（§2.4.1 → R204 时间轴化 → R381 时间脊柱·光流带）：垂直脊柱
            // 挂年代段卡（新→旧逐段揭示，点段卡=穿越到当年最热闹的一天）；月历在途
            // 出加载行；拉满 24 个月出「回溯更早」扩展按钮；两源皆无回落三张年代卡
            // （老体验兜底）
            when {
                chronicleBusy && chronicle == null -> Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
                        .background(TgFeedTokens.SurfaceHigh)
                        .padding(vertical = TgFeedTokens.GapLg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = TgFeedTokens.TextHigh,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.height(TgFeedTokens.GapSm))
                    Text(
                        "正在翻阅频道的历史…",
                        color = TgFeedTokens.TextLow,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                chronicle != null && chronicle.chronicle.blocks.isNotEmpty() -> Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                ) {
                    // R381 时间脊柱·光流带：年块墙整退——脊柱+段卡+月历光流（数据口零改，
                    // 直接消费 ChannelChronicle.Chronicle.blocks）
                    ChronicleSpineColumn(
                        blocks = chronicle.chronicle.blocks,
                        busy = busy,
                        plan = plan,
                        onDigEpoch = onDigEpoch,
                    )
                    // 回溯扩展：首批 12 个月 → 上限 24（网关钳制；拉满即隐藏）
                    if (!chronicle.atMaxDepth) {
                        ChronicleExtendRow(busy = chronicleBusy, onClick = onExtendChronicle)
                    }
                }
                else -> Row(
                    horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // 兜底：月历与基因两源皆无（新频道/未索引）——保留 R140 三档年代卡
                    ArchaeoEraCard(
                        label = "一年前", yearsAgo = 1, currentYear = currentYear,
                        modifier = Modifier.weight(1f),
                        busy = busy && plan?.let { !it.random && it.yearsAgo == 1 } == true,
                        enabled = !busy,
                        onClick = { onDigYears(1) },
                    )
                    ArchaeoEraCard(
                        label = "三年前", yearsAgo = 3, currentYear = currentYear,
                        modifier = Modifier.weight(1f),
                        busy = busy && plan?.let { !it.random && it.yearsAgo == 3 } == true,
                        enabled = !busy,
                        onClick = { onDigYears(3) },
                    )
                    ArchaeoEraCard(
                        label = "五年前", yearsAgo = 5, currentYear = currentYear,
                        modifier = Modifier.weight(1f),
                        busy = busy && plan?.let { !it.random && it.yearsAgo == 5 } == true,
                        enabled = !busy,
                        onClick = { onDigYears(5) },
                    )
                }
            }
            Spacer(Modifier.height(TgFeedTokens.GapSm))
            // 随机穿越（§2.4.1 情绪分流）：与时间档位分开的一行宽卡
            ArchaeoRandomCard(
                busy = busy && plan?.random == true,
                enabled = !busy,
                onClick = onDigRandom,
            )
            Spacer(Modifier.height(TgFeedTokens.GapLg))
            // 结果区四态：引导 → 穿越过场 → 年代卡+这一层网格 → 失败
            AnimatedContent(
                targetState = stage,
                transitionSpec = {
                    (fadeIn(tween(TgFeedTokens.AnimMed)) +
                        scaleIn(animationSpec = tween(TgFeedTokens.AnimSlow), initialScale = 0.92f)) togetherWith
                        fadeOut(tween(TgFeedTokens.AnimFast))
                },
                label = "archaeoStage",
            ) { s ->
                when (s) {
                    1 -> ArchaeoTravelStage(plan = plan, currentYear = currentYear)
                    2 -> dig?.let {
                        ArchaeoLandedStage(dig = it, onBrowse = onBrowse, onRedig = onRedig)
                    }
                    3 -> ArchaeoFailedStage(onRetry = onRedig)
                    else -> Text(
                        "点上面的年份穿越回去，挖到的内容会显示在这里",
                        color = TgFeedTokens.TextLow,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(TgFeedTokens.GapLg))
        }
    }
}

// ---------------- R381 时间脊柱·光流带几何（单源常量——R381bWiringSourceTest 锚定） ----------------

/** 脊柱节点槽宽（脊柱线+节点的横向占位；段卡左缘由此内缩） */
private val ChronicleSpineSlotWidth = 20.dp
/** 脊柱竖线宽 */
private val ChronicleSpineLineWidth = 2.dp
/** 年代节点圆点直径（脊柱上的年块节点） */
private val ChronicleSpineNodeDiameter = 8.dp
/** 节点圆心纵向位（对齐段卡年份大字的视觉中心） */
private val ChronicleSpineNodeDotY = 24.dp
/** 段卡进场 stagger 封顶段数（防超长列拖沓；步进=MotionTokens.SheetStaggerStepMs 既有节律） */
private const val CHRONICLE_SPINE_STAGGER_CAP = 6
/** 月度光流带高度（12 月列画布带；旧 53×7 热力墙 46dp 同量级） */
private val ChronicleFlowBandHeight = 40.dp
/** 月度光流桶数（月历日直方图呈现层折 12 月桶） */
private const val CHRONICLE_FLOW_MONTHS = 12
/** 峰日金标实心点直径 */
private val ChroniclePeakDotDiameter = 6.dp
/** 峰日金标紧晕外扩（宪法第一条：紧晕 ≤2dp、非空心环） */
private val ChroniclePeakDotHalo = 2.dp

/**
 * R381 时间脊柱列（LP1/LP2 正治：年块灰卡孤岛列表整退）：左缘垂直脊柱——色取
 * [archaeoEraColor] 年代色族（恒星色温隐喻：年轻=炽蓝、中年=琥珀、古老=赭石），
 * 沿卡序逐段渐变；脊柱挂年代段卡（[ChronicleEraCard]），段间零缝拼成整列脊柱。
 * 数据口零改：直接消费 [ChannelChronicle.Chronicle.blocks]（新→旧）。
 */
@Composable
private fun ChronicleSpineColumn(
    blocks: List<ChannelChronicle.YearBlock>,
    busy: Boolean,
    plan: ArchaeoPlan?,
    onDigEpoch: (yearsAgo: Int, targetSec: Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            val era = archaeoEraColor(block.yearsAgo.toDouble())
            // 下一段的 era 色（脊柱段线渐变的终点；末段回落本段色）
            val eraNext = blocks.getOrNull(index + 1)
                ?.let { archaeoEraColor(it.yearsAgo.toDouble()) }
                ?: era
            ChronicleEraCard(
                block = block,
                era = era,
                eraNext = eraNext,
                index = index,
                busy = busy,
                digging = busy && plan?.let { !it.random && it.yearsAgo == block.yearsAgo } == true,
                onClick = { onDigEpoch(block.yearsAgo, block.peakDaySec) },
            )
        }
    }
}

/**
 * R381 年代段卡（LP3 正治：整卡可点=穿越到当年最热闹的一天，「穿越」文字钮退役）：
 * 年份大字（era 色）+ 当年条数 + 峰日行（峰日金标 [ChroniclePeakDot]——点卡穿越的
 * 落点）+ 月度光流带（[ChronicleMonthFlowCanvas]）；覆盖外年块不出峰日行与光流
 * （缺据不出——诚实门），年节点转暗（脊柱位）。进场揭示（一次性转场）：段卡淡入
 * +轻上滑，逐段 stagger（R173 纪律三：stagger 只发生在进场）。
 */
@Composable
private fun ChronicleEraCard(
    block: ChannelChronicle.YearBlock,
    era: Color,
    eraNext: Color,
    index: Int,
    busy: Boolean,
    digging: Boolean,
    onClick: () -> Unit,
) {
    // 进场揭示：首组合即真（MutableTransitionState false→true 触发 enter 动画；
    // 面板每次重开 = ArchaeoSheet 重组 = remember 复位 = 逐段重新揭示）
    val reveal = remember { MutableTransitionState(false).apply { targetState = true } }
    val staggerDelay = index.coerceAtMost(CHRONICLE_SPINE_STAGGER_CAP) * MotionTokens.SheetStaggerStepMs
    AnimatedVisibility(
        visibleState = reveal,
        enter = fadeIn(tween(TgFeedTokens.AnimMed, delayMillis = staggerDelay)) +
            slideInVertically(tween(TgFeedTokens.AnimMed, delayMillis = staggerDelay)) { it / 8 },
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            ChronicleSpineNode(era = era, eraNext = eraNext, covered = block.hasCalendar, digging = digging)
            Spacer(Modifier.width(TgFeedTokens.GapXs))
            Column(
                Modifier
                    .weight(1f)
                    .padding(vertical = TgFeedTokens.GapXs)
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
                    .background(TgFeedTokens.SurfaceHigh)
                    .clickable(enabled = !busy, onClick = onClick)
                    .padding(horizontal = TgFeedTokens.GapMd, vertical = TgFeedTokens.GapSm),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // 年份大字（年代色随远近走，R140 年代色族原样沿用）
                    Text(
                        "${block.year}",
                        color = era,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(TgFeedTokens.GapSm))
                    Text(
                        if (block.hasCalendar) {
                            "${DiscoveryGoldmine.fmtCount(block.count)} 条"
                        } else {
                            "早年 · 详细档案未覆盖"
                        },
                        color = TgFeedTokens.TextLow,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (block.hasCalendar) {
                    Spacer(Modifier.height(TgFeedTokens.GapXs))
                    // 峰日行：当年最热闹的一天（峰日金标——点卡即穿越至此）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                    ) {
                        ChroniclePeakDot()
                        Text(
                            "峰日 ${formatArchaeoDateCn(block.peakDaySec)}",
                            color = TgFeedTokens.TextLow,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (block.dayCells.isNotEmpty()) {
                    Spacer(Modifier.height(TgFeedTokens.GapXs))
                    ChronicleMonthFlowCanvas(
                        dayCells = block.dayCells,
                        era = era,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * R381 脊柱节点槽（左缘 2dp 段线 + 8dp 年代节点）：段线 era→eraNext 纵向渐变
 * （沿卡序=年代色温谱流动；段间零缝）；节点 busy 呼吸=既有连续指示器族（与穿越
 * 行呼吸同族 700ms 非转场——MotionTokensPolicyTest 豁免清单已登记本文件「连续
 * 指示器周期」类）；覆盖外年块=暗节点 α0.4。
 */
@Composable
private fun ChronicleSpineNode(
    era: Color,
    eraNext: Color,
    covered: Boolean,
    digging: Boolean,
) {
    // 节点呼吸（busy 信号：挖掘目标段的活体指示；闲置值不被读取即零失效省电）
    val breath = rememberInfiniteTransition(label = "chronicleSpineNode")
    val breathAlpha by breath.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "chronicleSpineNodeBreath",
    )
    Box(Modifier.width(ChronicleSpineSlotWidth).fillMaxHeight()) {
        Canvas(Modifier.matchParentSize()) {
            val lineX = size.width / 2f
            // 脊柱段线（era→eraNext 渐变）
            drawRect(
                brush = Brush.verticalGradient(listOf(era, eraNext)),
                topLeft = androidx.compose.ui.geometry.Offset(lineX - ChronicleSpineLineWidth.toPx() / 2f, 0f),
                size = androidx.compose.ui.geometry.Size(ChronicleSpineLineWidth.toPx(), size.height),
            )
            // 年代节点（覆盖外=暗节点；挖掘中=呼吸）
            val nodeAlpha = (if (covered) 1f else 0.4f) * (if (digging) breathAlpha else 1f)
            drawCircle(
                color = era.copy(alpha = nodeAlpha.coerceIn(0f, 1f)),
                radius = ChronicleSpineNodeDiameter.toPx() / 2f,
                center = androidx.compose.ui.geometry.Offset(lineX, ChronicleSpineNodeDotY.toPx()),
            )
        }
    }
}

/**
 * R381 峰日金标（宪法第一条紧晕口径）：StarYellow 实心小点 + 同色低 α 紧贴晕
 * （晕外扩 [ChroniclePeakDotHalo] ≤2dp；非空心环、非大圆盘）。
 */
@Composable
private fun ChroniclePeakDot() {
    Canvas(Modifier.size(ChroniclePeakDotDiameter + ChroniclePeakDotHalo * 2f)) {
        val c = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        // 紧晕（同色低 α，半径=点半径+2dp）
        drawCircle(
            color = TgFeedTokens.StarYellow.copy(alpha = 0.22f),
            radius = ChroniclePeakDotDiameter.toPx() / 2f + ChroniclePeakDotHalo.toPx(),
            center = c,
        )
        // 实心峰标点
        drawCircle(
            color = TgFeedTokens.StarYellow,
            radius = ChroniclePeakDotDiameter.toPx() / 2f,
            center = c,
        )
    }
}

/**
 * R381 月度光流带（LP1 正治：53×7 方格墙整退）：月历日直方图在呈现层折
 * [CHRONICLE_FLOW_MONTHS] 月桶（[foldChronicleMonthCounts] 呈现层派生，数据侧
 * 零动），每月一列，列高∝当月条数（对当年 max 归一——诚实量级，连续映射非
 * 档位）；列身顶亮底暗纵向渐变，列顶 quadratic 连线成连续「光流」轮廓——
 * 活跃度从「四档椒盐」升为「连续亮度流」（r358 丝缕范式：结构承载量级）。
 * 空月零绘制（缺据不出）。
 */
@Composable
private fun ChronicleMonthFlowCanvas(
    dayCells: List<Pair<Int, Int>>,
    era: Color,
    modifier: Modifier = Modifier,
) {
    val monthCounts = remember(dayCells) { foldChronicleMonthCounts(dayCells) }
    Canvas(modifier.height(ChronicleFlowBandHeight)) {
        val yearMax = monthCounts.maxOrNull() ?: 0
        if (yearMax <= 0) return@Canvas
        val colW = size.width / CHRONICLE_FLOW_MONTHS
        val barW = colW * 0.82f
        val headroom = 3.dp.toPx() // 列顶与轮廓线上方余量（含 1dp 描边半宽）
        val tops = FloatArray(CHRONICLE_FLOW_MONTHS) { i ->
            val frac = monthCounts[i].toFloat() / yearMax
            size.height - (size.height - headroom) * frac
        }
        // 列身：顶亮底暗渐变（连续亮度承载量级；空月零绘制）
        for (i in 0 until CHRONICLE_FLOW_MONTHS) {
            if (monthCounts[i] <= 0) continue
            val left = i * colW + (colW - barW) / 2f
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(era.copy(alpha = 0.58f), era.copy(alpha = 0.05f)),
                    startY = tops[i],
                    endY = size.height,
                ),
                topLeft = androidx.compose.ui.geometry.Offset(left, tops[i]),
                size = androidx.compose.ui.geometry.Size(barW, size.height - tops[i]),
            )
        }
        // 光流轮廓：列顶锚点为控制点、月中点为轨的 quadratic 平滑连线
        if (CHRONICLE_FLOW_MONTHS > 1) {
            val xs = FloatArray(CHRONICLE_FLOW_MONTHS) { (it + 0.5f) * colW }
            val path = Path()
            path.moveTo(xs[0], tops[0])
            path.lineTo((xs[0] + xs[1]) / 2f, (tops[0] + tops[1]) / 2f)
            for (i in 1 until CHRONICLE_FLOW_MONTHS - 1) {
                path.quadraticTo(xs[i], tops[i], (xs[i] + xs[i + 1]) / 2f, (tops[i] + tops[i + 1]) / 2f)
            }
            path.lineTo(xs[CHRONICLE_FLOW_MONTHS - 1], tops[CHRONICLE_FLOW_MONTHS - 1])
            drawPath(path, era.copy(alpha = 0.85f), style = Stroke(width = 1.dp.toPx()))
        }
    }
}

/** 月桶折算（呈现层派生，数据侧零动）：UTC 日 epoch 秒 → 当年 12 月条数（UTC 月口径与数据侧同族） */
private fun foldChronicleMonthCounts(dayCells: List<Pair<Int, Int>>): IntArray {
    val counts = IntArray(CHRONICLE_FLOW_MONTHS)
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    for ((dayEpochSec, n) in dayCells) {
        cal.timeInMillis = dayEpochSec.toLong() * 1000L
        val m = cal.get(java.util.Calendar.MONTH)
        if (m in 0 until CHRONICLE_FLOW_MONTHS) counts[m] += n
    }
    return counts
}

/** 「回溯更早的 12 个月」扩展行（首批 12 → 上限 24；拉取中转圈） */
@Composable
private fun ChronicleExtendRow(busy: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
            .background(TgFeedTokens.SurfaceHigh)
            .clickable(enabled = !busy, onClick = onClick)
            .padding(vertical = TgFeedTokens.GapSm),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = TgFeedTokens.TextHigh,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(TgFeedTokens.GapSm))
        }
        Text(
            if (busy) "正在翻更早的历史…" else "回溯更早的 12 个月",
            color = if (busy) TgFeedTokens.TextLow else TgFeedTokens.TgBlue,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** 年代档位卡（§2.4.1）：年代色渐变底 + 大号年份；挖动中副题换"穿越中…"（busy 期间全档位禁点防连打） */
@Composable
private fun ArchaeoEraCard(
    label: String,
    yearsAgo: Int,
    currentYear: Int,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val era = archaeoEraColor(yearsAgo.toDouble())
    Column(
        modifier
            .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
            .background(TgFeedTokens.SurfaceHigh)
            // 年代色纵向渐变（上深下浅）：做旧底色随年代档位走
            .background(
                Brush.verticalGradient(
                    listOf(era.copy(alpha = 0.34f), Color.Transparent, era.copy(alpha = 0.12f)),
                ),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = TgFeedTokens.GapSm, vertical = TgFeedTokens.GapMd),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = if (enabled) TgFeedTokens.TextMedium else TgFeedTokens.TextLow,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "${currentYear - yearsAgo}",
            color = if (enabled) TgFeedTokens.TextHigh else TgFeedTokens.TextLow,
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            if (busy) "穿越中…" else "那年的今天",
            color = if (busy) TgFeedTokens.TgBlue else TgFeedTokens.TextLow,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** 随机穿越宽卡（§2.4.1 情绪分流）：骰子 + 全历史盲盒；挖动中骰子缓转做过程感 */
@Composable
private fun ArchaeoRandomCard(
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val spin = rememberInfiniteTransition(label = "archaeoDice")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "archaeoDiceAngle",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
            .background(TgFeedTokens.SurfaceHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = TgFeedTokens.GapMd, vertical = TgFeedTokens.GapMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Casino,
            contentDescription = null,
            tint = if (enabled) TgFeedTokens.StarYellow else TgFeedTokens.TextLow,
            modifier = Modifier
                .size(TgFeedTokens.IconLg)
                // 挖动中骰子缓转（盲盒摇动感；闲置时静止省电）
                .graphicsLayer { rotationZ = if (busy) angle else 0f },
        )
        Spacer(Modifier.width(TgFeedTokens.GapMd))
        Column(Modifier.weight(1f)) {
            Text(
                "随机穿越",
                color = if (enabled) TgFeedTokens.TextHigh else TgFeedTokens.TextLow,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                if (busy) "穿越中…" else "全历史盲盒",
                color = if (busy) TgFeedTokens.TgBlue else TgFeedTokens.TextLow,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * 穿越过场（§2.4.3 过程感强制位）：挖掘 1–5s 的等待不裸转——
 * 定向档位 = 年份倒滚（时间机器里程表）+ "穿越到 X…"目标日期呼吸；
 * 随机 = 骰子缓转 + "全看手气"。做旧色底随档位年代走，等待变成内容。
 */
@Composable
private fun ArchaeoTravelStage(plan: ArchaeoPlan?, currentYear: Int) {
    val random = plan?.random ?: true // busy 必有 plan；防御性兜随机文案
    val era = if (random) ArchaeoEraMid else archaeoEraColor((plan?.yearsAgo ?: 2).toDouble())
    // "穿越到…"行的呼吸（明暗 700ms 往返）：仍在途的活体信号
    // （1600/700=连续指示器周期非转场——MotionTokensPolicyTest 豁免清单登记）
    val pulse = rememberInfiniteTransition(label = "archaeoTravel")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "archaeoTravelPulse",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
            .background(TgFeedTokens.SurfaceHigh)
            .background(
                Brush.verticalGradient(
                    listOf(era.copy(alpha = 0.30f), TgFeedTokens.SurfaceHigh, era.copy(alpha = 0.10f)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (!random && plan != null) {
                // 定向档位：年份倒滚到目标年 + 目标日期（"那年的今天"）
                ArchaeoRollingYear(fromYear = currentYear, toYear = currentYear - plan.yearsAgo)
                Spacer(Modifier.height(TgFeedTokens.GapXs))
                Text(
                    "穿越到 ${formatArchaeoDateCn(plan.targetEpochSec)}…",
                    color = TgFeedTokens.TextMedium.copy(alpha = pulseAlpha),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                // 随机：落点未知，悬念本身就是内容
                Icon(
                    Icons.Filled.Casino,
                    contentDescription = null,
                    tint = TgFeedTokens.StarYellow,
                    modifier = Modifier.size(TgFeedTokens.IconXl),
                )
                Spacer(Modifier.height(TgFeedTokens.GapSm))
                Text(
                    "正在随机穿越…",
                    color = TgFeedTokens.TextHigh,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(TgFeedTokens.GapXs))
                Text(
                    "落到哪个年代，全看手气…",
                    color = TgFeedTokens.TextMedium.copy(alpha = pulseAlpha),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** 年份倒滚（时间机器里程表）：从当前年按 AnimSlow 节律逐格退到目标年，到位后驻留 */
@Composable
private fun ArchaeoRollingYear(fromYear: Int, toYear: Int) {
    var year by remember(fromYear, toYear) { mutableStateOf(fromYear) }
    LaunchedEffect(fromYear, toYear) {
        while (year > toYear) {
            delay(TgFeedTokens.AnimSlow.toLong())
            year--
        }
    }
    AnimatedContent(
        targetState = year,
        transitionSpec = {
            (slideInVertically(tween(TgFeedTokens.AnimFast)) { it / 2 } +
                fadeIn(tween(TgFeedTokens.AnimFast))) togetherWith
                (slideOutVertically(tween(TgFeedTokens.AnimFast)) { -it / 2 } +
                    fadeOut(tween(TgFeedTokens.AnimFast)))
        },
        label = "archaeoYear",
    ) { y ->
        Text(
            "$y",
            color = TgFeedTokens.TextHigh,
            style = MaterialTheme.typography.displaySmall,
        )
    }
}

/**
 * 挖到后的结果区（§2.4.4）：年代卡（大字日期 + 距今 + 挖到 N 条 + "那天频道刚发了
 * 这些"）+ 3 列预览网格；预览格即入口（点任意格进这一层，R134 通道原样），主行动位
 * = "再挖一次"次按钮。空层 = 年代卡 + 诚实空态文案（也有"再挖一次"出口）。
 */
@Composable
private fun ArchaeoLandedStage(
    dig: ArchaeoDig,
    onBrowse: (VideoItem) -> Unit,
    onRedig: () -> Unit,
) {
    val yearsAgo = (System.currentTimeMillis() / 1000L - dig.anchorDateSec) / (365L * 86400L)
    val era = archaeoEraColor(yearsAgo.toDouble())
    Column(Modifier.fillMaxWidth()) {
        // 年代卡：大字日期（displaySmall 级，§2.4.3）+ 距今 + 挖到 N 条
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
                .background(TgFeedTokens.SurfaceHigh)
                .background(
                    Brush.verticalGradient(listOf(era.copy(alpha = 0.34f), era.copy(alpha = 0.08f))),
                )
                .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapMd),
        ) {
            Text(
                formatArchaeoDateCn(dig.anchorDateSec),
                color = TgFeedTokens.TextHigh,
                style = MaterialTheme.typography.displaySmall,
            )
            Spacer(Modifier.height(TgFeedTokens.GapXs))
            Text(
                "距今 ${formatArchaeoAgo(dig.anchorDateSec)} · 挖到 ${dig.items.size} 条",
                color = TgFeedTokens.TextMedium,
                style = MaterialTheme.typography.bodySmall,
            )
            if (dig.items.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "那天频道刚发了这些",
                    color = TgFeedTokens.TextLow,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (dig.items.isEmpty()) {
            // 空层：挖到了年代，这一层没有媒体（休眠频道如实呈现，R134 同语义）
            Spacer(Modifier.height(TgFeedTokens.GapSm))
            Text(
                "这一层没挖到媒体——换个年代，或再挖一次",
                color = TgFeedTokens.TextLow,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            // 3 列预览网格：预览格即入口，点哪格就从哪条进这一层（R134 通道原样）
            Spacer(Modifier.height(TgFeedTokens.GapSm))
            Column(
                verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                for (rowItems in dig.items.take(ARCHAEO_PREVIEW_CELLS).chunked(3)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        for (item in rowItems) {
                            ArchaeoPreviewCell(
                                item,
                                onClick = { onBrowse(item) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // 行末补位：保持 3 列等宽（不满行时格子不摊大）
                        repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Spacer(Modifier.height(TgFeedTokens.GapXs))
            Text(
                if (dig.items.size > ARCHAEO_PREVIEW_CELLS) {
                    "这一层共 ${dig.items.size} 条——点任意一格，从这一格开始逛"
                } else {
                    "点任意一格，从这一格开始逛这一层"
                },
                color = TgFeedTokens.TextLow,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // 主行动位（§2.4.4）："逛考古层"主按钮删除，预览格即入口——这里只留
        // "再挖一次"次按钮（随机 = 重摇；时间档位 = 重取这一层）
        Spacer(Modifier.height(TgFeedTokens.GapMd))
        ArchaeoSecondaryButton(text = "再挖一次", onClick = onRedig)
    }
}

/** 失败态（§2.4.6）：状态图标 + 原因 + 重试，不做纯文字替换 */
@Composable
private fun ArchaeoFailedStage(onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = TgFeedTokens.GapXl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.CloudOff,
            contentDescription = null,
            tint = TgFeedTokens.TextLow,
            modifier = Modifier.size(TgFeedTokens.IconXl),
        )
        Spacer(Modifier.height(TgFeedTokens.GapMd))
        Text(
            "这次没挖到（网络抖动或频道没有历史库存）",
            color = TgFeedTokens.TextMedium,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(TgFeedTokens.GapLg))
        ArchaeoSecondaryButton(text = "再试一次", onClick = onRetry)
    }
}

/** 次级行动胶囊（§2.4.4 主行动位降级）：SurfaceHigh 底 + 发丝描边 + titleSmall */
@Composable
private fun ArchaeoSecondaryButton(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(TgFeedTokens.RadiusPill)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(TgFeedTokens.SurfaceHigh)
            .border(0.5.dp, TgFeedTokens.OverlayHairline, shape)
            .clickable(onClick = onClick)
            .padding(vertical = TgFeedTokens.GapMd),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = TgFeedTokens.TextHigh,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

/** 这一层预览格：3 列网格里的 3:4 满宽封面 + 类型角标（CoverStore 抽帧 → minithumbnail，与网格格同链降级） */
@Composable
private fun ArchaeoPreviewCell(
    item: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cover: ImageBitmap? = produceState<ImageBitmap?>(initialValue = null, item.key) {
        CoverStore.map[item.key]?.let { value = it }
        if (value == null) {
            value = item.coverBytes?.let { bytes ->
                runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
                }.getOrNull()
            }
        }
    }.value
    Box(
        modifier
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
            .background(TgFeedTokens.SurfaceHigh)
            .clickable(onClick = onClick),
    ) {
        if (cover != null) {
            Image(
                bitmap = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 类型角标（左下）：视频=时长；图集=🖼；单图=图；音频=♪（与网格格同口径，
        // R162 收敛 OverlayInfoChip）
        val badge = when (item.mediaType) {
            0 -> formatCellDuration(item.durationSec)
            1 -> if (item.albumId != 0L) "🖼 图集" else "图"
            else -> "♪"
        }
        OverlayInfoChip(
            text = badge,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(3.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

// ---------------- 考古叙事件（文案/年代色/常量，纯呈现层） ----------------

/** 一次挖掘请求的全部呈现参数：穿越过场文案与"再挖一次"重放的唯一来源 */
private data class ArchaeoPlan(
    /** true = 随机穿越（无定向目标）；false = 时间档位跳跃 */
    val random: Boolean,
    /** 时间档位年数（随机 = 0；年代色与过场年份滚动据此定档） */
    val yearsAgo: Int,
    /** 定向目标时刻（Unix 秒；随机 = 0，只作过场文案，不作结果解释） */
    val targetEpochSec: Int,
    /** 档位标签（档位卡 busy 副题对位显示用） */
    val tierLabel: String,
)

/** 预览网格最多展示的格数（3 列 × 3 行；这一层整页 30 条由网格点进后自见） */
private const val ARCHAEO_PREVIEW_CELLS = 9

// 年代色（§2.4.1 年代卡渐变底）：越远越"旧"——近=品牌蓝、中=琥珀、远=赭石；
// 只做装饰性渐变底，不占 §3.2 语义色（红/黄/绿/蓝的动作语义）的位
private val ArchaeoEraRecent = Color(0xFF54A9EB)
private val ArchaeoEraMid = Color(0xFFC99A4E)
private val ArchaeoEraOld = Color(0xFFB4713F)

/** 年代色分档：1 年内=蓝 / 3 年上下=琥珀 / 4 年以上=赭石（档位卡与年代卡同源） */
private fun archaeoEraColor(yearsAgo: Double): Color = when {
    yearsAgo < 2.0 -> ArchaeoEraRecent
    yearsAgo < 4.0 -> ArchaeoEraMid
    else -> ArchaeoEraOld
}

/**
 * 开场文案（§2.4.2 数据化）：索引里确有 N 年前的帖子才说"N 年前都还翻得到"（诚实下界，
 * 只往老的方向断言）；未知/不足一年回落通用版——深度索引对怪兽频道只按月采样近两年，
 * "年轻"断言会把老频道说小，故不做年轻分支。
 */
private fun archaeoIntroLine(ageYears: Int?): String =
    if (ageYears != null && ageYears >= 1) {
        "这个频道 ${ageYears} 年前的帖子都还翻得到——挑个年代穿越回去看看"
    } else {
        "老频道的库存不止最近两周——挑个年代穿越回去，或随机来一次全历史盲盒"
    }

/** 考古日期文案（中文体，§2.4.5）：2021年3月14日（设备时区；与网关日志同一 Unix 秒口径） */
private fun formatArchaeoDateCn(epochSec: Int): String =
    java.text.SimpleDateFormat("yyyy年M月d日", java.util.Locale.CHINA)
        .format(java.util.Date(epochSec * 1000L))

/** 距今时间量（不带"距今"前缀，调用处拼）：满年按年、不足年按天、当天"不到 1 天"（全 App 中文时间体同口径） */
private fun formatArchaeoAgo(epochSec: Int): String {
    val days = ((System.currentTimeMillis() / 1000L - epochSec) / 86400L).toInt()
    return when {
        days / 365 >= 1 -> "${days / 365} 年"
        days >= 1 -> "$days 天"
        else -> "不到 1 天"
    }
}
