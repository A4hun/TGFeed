package dev.tgfeed.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tgfeed.core.feed.PursueTimelineCard
import dev.tgfeed.core.feed.PursueViewModel
import dev.tgfeed.core.feed.PursueViewModelFactory
import dev.tgfeed.core.feed.PursueViewportAction
import dev.tgfeed.core.feed.pursueAnchorRestoreIndex
import dev.tgfeed.core.feed.pursueViewportAction
import dev.tgfeed.core.feed.VisibilityGate
import dev.tgfeed.core.feed.titleLine
import dev.tgfeed.core.td.MockTdGateway
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.ui.components.CoverStore
import dev.tgfeed.ui.components.HomeTabsRow
import dev.tgfeed.ui.components.OverlayInfoChip
import dev.tgfeed.ui.components.formatDuration
import dev.tgfeed.ui.components.kit.AppEmptyState
import dev.tgfeed.ui.components.kit.AppLoading
import dev.tgfeed.ui.components.kit.LocalBottomBarInset
import dev.tgfeed.ui.components.kit.overlaySwipeHost
import dev.tgfeed.ui.components.rememberChannelAvatarImage
import dev.tgfeed.ui.components.relativeTimeZh
import dev.tgfeed.ui.theme.ChannelIdentity
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------- R239 时间线（首页一等 tab·扁平完整流·最新在下）· R240 卡片流呈现 ----------------

/**
 * 呼吸灯脉冲周期（ms）：连续指示器周期（「正在更新」呼吸），非转场——
 * MotionTokensPolicy 具名场景本地常数口径（AudioCard 呼吸/Discover 骨架同族）。
 */
private const val PursueBreathPulseMs = 1400

/**
 * R240 卡头频道头像直径（dp）：卡流的认脸面（用户实锤「瀑布格看不出哪个是哪个
 * 频道的」——14dp 迷你徽升级为 36dp 头像+全名行）。
 */
private val PursueCardAvatarSize = 36.dp

/**
 * R240 卡内缩略排宽帽（dp）：多条横排/单条大缩略的宽度上限——宽屏（平板/横屏）
 * 防巨图+朋友圈式左对齐读法（卡片内容按消息列读，右侧留白呼吸）。
 */
private val PursueCardThumbsMaxWidth = 420.dp

/**
 * R240 单条大缩略宽度系数（卡内容宽的比例）：单条媒体=「大缩略」档（3:4 竖幅），
 * 比横排缩略大一圈但不吞整卡（微信单图朋友圈同款读法）。
 */
private const val PursueCardSingleThumbWidthFraction = 0.6f

/**
 * 时间线（R239 首页一等 tab：**关注 | 时间线 | 推荐** 居中；feed 页内 overlay 层，
 * AppContainer.homeTimelineOn 驱动）。
 *
 * 形态（R240 卡片流改版——用户实锤「9 列瀑布格太丑/看不出哪个消息是哪个频道的」：
 * 改微信/微博式卡片流，纯 UI 层，数据链零改动）：
 * - **每卡=一个频道的同批更新**（[pursueTimelineCards] 纯分批：相邻同频道+间隔
 *   连续合卡；缩略横排 1~3 张+溢出「+N」角标；单条大缩略）——不是每卡=单条媒体；
 * - **卡头认脸**：36dp 频道头像（R194 共享件）+频道名（单行省略）+相对时间
 *   （卡尾最新条目的「N 分钟前」）；
 * - 卡壳=R207 视觉语法（Surface 底+RadiusMd 12dp 圆角+GapMd 间距呼吸）；
 * - **最新在最下面**（用户拍板不变量延续）：卡序=流序升序，进 tab 自动滚到底；
 *   上滑=更新鲜，近顶=触发历史轮（每频道游标推进+写回索引，无上限回看）；
 * - 实时推入（TDLib push 0 延迟）：新卡从底部滑入；用户不在底部时底部浮现
 *   「N 条新内容」胶囊点按滚到底；
 * - 页头=HomeTabsRow 三 tab（与 feed 顶栏同一事实源渲染件——切 tab 视觉零漂移）
 *   +左区呼吸灯「正在更新」（bootstrap 渐进期显示「拉取 N/总数」）；
 * - **点缩略**=邻域窗（±60 条）进 Feed 播放（R239 onPlay 链原样），返回先回时间线
 *   **且保滚动位**（R245 实锤③：listState 由调用方（feedPage 作用域）托管+返回保位
 *   令分轨——播放往返回原位，新开 tab 才滚到底）；
 * - **点卡（非缩略区）**=[onOpenChannelHome] 进频道主页覆盖层（z 序盖时间线，
 *   back 先关主页回时间线）。
 *
 * 纪律：MotionTokens 红线（零 spring；入场 tween AnimMed；呼吸灯=具名场景常数）；
 * Scrim 红线（零 Color.Black.copy 字面量——角标底走 OverlayInfoChip token）；
 * 页级返回=关时间线回 feed（BackHandler 声明在 FeedScreen 之后=后注册者优先）。
 */
@Composable
internal fun PursueScreen(
    gateway: TdGateway,
    deepIndex: dev.tgfeed.core.feed.DeepSearchIndex?,
    pageActive: Boolean,
    onSelectFollowing: () -> Unit,
    onSelectAggregate: () -> Unit,
    onBack: () -> Unit,
    onPlay: (items: List<VideoItem>, startKey: String, label: String) -> Unit,
    onOpenChannelHome: (VideoItem) -> Unit,
    refreshTick: Int = 0,
    onRefreshTimeline: () -> Unit = {},
    // R245 播放往返保位（实锤③，新参默认值——其他调用方/单测零改动）：
    // hostedListState=外部托管滚动位（MainActivity feedPage 作用域 remember，声明在
    // homeTimelineOn 门控之外——onPlay 走 showFeed 收层只撤 PursueScreen 组合，state
    // 不清位，返回重开原位恢复）；null=未托管回落本地 remember。
    hostedListState: LazyListState? = null,
    // returnLanding=true：本次激活是「播放往返返回」（PURSUE 返回消费处置位）——
    // 跳过进页滚底，保留用户离开时的滚动位；激活后经 onReturnLandingConsumed 复位
    // （后续经 tab/底栏新开仍走 R239「进 tab 滚到底」语义，两路分轨）。
    returnLanding: Boolean = false,
    onReturnLandingConsumed: () -> Unit = {},
    // R245 键锚（可变单槽，宿主 feedPage 作用域 remember）：PursueScreen 离开组合时
    // 回写「首可见卡 key+偏移」、返回激活时消费重定位——流在离开期间增长（bootstrap
    // 渐进合并/实时推入会在中间插行=平移索引）下托管 index 只是近似位，按卡 key
    // 重定位恒回「刚才那条」；null=未接（其他调用方）走纯 index 恢复。
    positionAnchor: androidx.compose.runtime.MutableState<Pair<String?, Int>>? = null,
    // R418 可见性谓词源（共享 FeedViewModel.visibilityGate()；null=不过滤）
    visibility: () -> VisibilityGate? = { null },
) {
    val vm: PursueViewModel = viewModel(
        factory = PursueViewModelFactory(gateway, deepIndex, visibility),
        key = "pursue-gw" + System.identityHashCode(gateway),
    )
    val stream by vm.items.collectAsState()
    val loading by vm.loading.collectAsState()
    val bootstrapped by vm.bootstrapped.collectAsState()
    // R432 重组收敛：bootstrapProgress 的 collect 下移进 PursueHeader（该流在拉取期
    // 高频变更「N/总数」，顶层取值会让 PursueScreen 整页每变更全量重组；收进
    // 头部叶子后只有头部那一小行文字重组）
    val historyLoading by vm.historyLoading.collectAsState()
    val historyExhausted by vm.historyExhausted.collectAsState()
    val newBelow by vm.newBelow.collectAsState()
    val recentLiveKeys by vm.recentLiveKeys.collectAsState()

    // 前台门控（R189 保活：三页常驻组合，pageActive=落定到首页才干活——时间线
    // 只在首页页 0 组合，pageActive 即「时间线 tab 可见」）
    LaunchedEffect(pageActive) { vm.setActive(pageActive) }
    // 双击刷新令（timelineRefreshRequest nonce：顶栏/底栏双击已激活时间线 → 强刷）
    LaunchedEffect(refreshTick) { if (refreshTick > 0) vm.requestRefresh() }
    // 页级返回：时间线在台前按返回 = 关层回 feed 内容（声明晚于 FeedScreen 的
    // 处理器=后注册者优先，双击退出链被压住只在时间线关闭后接管）
    BackHandler(enabled = pageActive, onBack = onBack)

    val scope = rememberCoroutineScope()
    // R245 滚动位托管：外部 listState 优先（播放往返撤组合不清位——实锤③病根即
    // rememberLazyListState 随 PursueScreen 撤组合销毁，返回重进=新 state+拽底）；
    // 本地回落恒先 remember（组合调用不得条件化，槽位结构稳定）
    val localListState = rememberLazyListState()
    val listState = hostedListState ?: localListState
    // R240 呈现分批（纯函数，流实例变才重算）：完整流语义在数据面保持，
    // 这里只决定怎么「摆盘」（相邻同频道同批合卡）
    // R418b 卡片流升序（最新卡在底）：VM 在后台线程随流算好（尾追加增量分卡），
    // 主线程组合期零分卡开销
    val cards by vm.cards.collectAsState()
    // 进 tab 自动滚到底：落定进 tab（或首个非空批次到达）即落到最新卡——「从最新
    // 开始往上回看」的用户终审直觉。实时推入**不**拽位（用户在上方回看时新内容
    // 安静排队——胶囊计数）。
    // R245 返回保位：returnLanding=true（播放往返返回）时首组合即视为已落位——不
    // 滚底，位置由托管 listState 原样恢复；激活消费复位令（后续新开恢复滚底语义）。
    // 横滑往返（组合不撤、landed 保持）本就不该重拽底——App.kt「滑回首页时间线
    // 原样恢复」口径对齐，原 pageActive 复位拽底行为随本刀撤。
    var landedForEntry by remember { mutableStateOf(returnLanding) }
    // R245 键锚捕获：离开组合（showFeed 收层进播放/关层）时把「首可见卡 key+偏移」
    // 回写宿主锚。DisposableEffect 键随 cards 变（快照恒新鲜，流更新即重建即重快照），
    // 最后一次 dispose=真实离开时刻的视口。卡 key 用 pursueCardKey 单一事实源
    // （与 LazyColumn items key 同式——两处各写一份会漂移）。
    androidx.compose.runtime.DisposableEffect(cards, listState) {
        onDispose {
            val idx = listState.firstVisibleItemIndex
            // getOrNull 防御：流重建（强刷）后 index 可能超界——null 锚=回落 index 恢复
            val key = cards.getOrNull(idx)?.let { pursueCardKey(it) }
            positionAnchor?.value = key to listState.firstVisibleItemScrollOffset
        }
    }
    LaunchedEffect(pageActive) {
        if (pageActive && returnLanding) {
            // 键锚重定位：离开期间流增长平移索引时托管 index 只是近似位——按锚 key
            // 重 scrollToItem 恒准；锚失配（卡被合并/流重建）回落托管 index（近似位
            // 仍远优于拽底）。滚完再消费复位令（新开路径不受影响）。
            val anchor = positionAnchor?.value
            if (anchor?.first != null) {
                val anchorIdx = pursueAnchorRestoreIndex(
                    cards.map { pursueCardKey(it) }, anchor.first, listState.firstVisibleItemIndex,
                )
                if (anchorIdx != null) listState.scrollToItem(anchorIdx, anchor.second)
            }
            onReturnLandingConsumed()
        }
    }
    LaunchedEffect(pageActive, cards.size) {
        if (pageActive && !landedForEntry && cards.isNotEmpty()) {
            listState.scrollToItem(cards.lastIndex) // 进页落在最新端（底）
            landedForEntry = true
        }
    }
    // 用户在底部判定（底=最新端；胶囊清零判据）：滚不动向前=在最新端
    val atBottom by remember {
        derivedStateOf { !listState.canScrollForward }
    }
    LaunchedEffect(atBottom) { vm.setUserAtBottom(atBottom) }
    // R418b 视口跟随/保位：卡片流每次发布后按纯函数判定——用户在底且尾部新增 → 跟随到最新；
    // 头部插入（历史预取）或用户在中段回看时尾部追加 → 按首可见卡 key 锚定回位（不跳）。
    // 视口快照（锚 key/偏移/是否在底）只随滚动更新——内容增长不改「刚才是否在底」。
    val viewport = remember { arrayOfNulls<Any>(5) } // 0 首卡 key,1 末卡 key,2 锚 key,3 锚偏移,4 在底
    LaunchedEffect(cards) {
        val newFirst = cards.firstOrNull()?.let { pursueCardKey(it) }
        val newLast = cards.lastOrNull()?.let { pursueCardKey(it) }
        val wasAtBottom = (viewport[4] as? Boolean) ?: true
        when (
            pursueViewportAction(wasAtBottom, viewport[0] as? String, newFirst, viewport[1] as? String, newLast)
        ) {
            PursueViewportAction.FOLLOW_BOTTOM -> if (cards.isNotEmpty()) listState.scrollToItem(cards.lastIndex)
            PursueViewportAction.HOLD_ANCHOR -> {
                val idx = pursueAnchorRestoreIndex(
                    cards.map { pursueCardKey(it) }, viewport[2] as? String, listState.firstVisibleItemIndex,
                )
                if (idx != null) listState.scrollToItem(idx, (viewport[3] as? Int) ?: 0)
            }
            PursueViewportAction.NONE -> Unit
        }
        viewport[0] = newFirst
        viewport[1] = newLast
        // R418b 探针：等下一帧真正上屏后回报（首屏/推送毫秒）
        if (cards.isNotEmpty()) {
            androidx.compose.runtime.withFrameNanos { }
            vm.reportRendered(cards.size)
        }
        snapshotFlow {
            Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, !listState.canScrollForward)
        }.collect { (i, off, bottom) ->
            viewport[2] = cards.getOrNull(i)?.let { pursueCardKey(it) }
            viewport[3] = off
            viewport[4] = bottom
        }
    }
    // 历史轮触发（游标推进+写回索引；VM 侧幂等闸兜底防抖）：**近顶预取**——第一可见卡
    // 进入距顶 PURSUE_HISTORY_PREFETCH_ROWS 行内即触发（更早内容在到顶前就位，头部插入
    // 由卡 key 锚定保位）。键含 cards.size/historyLoading/loading：合并完成/轮收拍后重估一次
    // 近顶态（快照流只在滚动时发射，纯状态推进不滚动时会漏）。
    LaunchedEffect(cards.size, historyLoading, loading) {
        snapshotFlow { listState.firstVisibleItemIndex <= dev.tgfeed.core.feed.PURSUE_HISTORY_PREFETCH_ROWS }
            .collect { nearTop ->
                if (nearTop && cards.isNotEmpty()) vm.requestHistoryRound()
            }
    }

    val avatarLoader: suspend (Long) -> ByteArray? = remember(gateway) {
        when (gateway) {
            is TdLibGateway -> { id -> gateway.getChannelAvatar(id) }
            is MockTdGateway -> { id -> gateway.getChannelAvatar(id) }
            else -> { _ -> null }
        }
    }

    // R308 横滑归宿（用户实锤：时间线左滑错进发现页——overlay 组合在 pager 页体
    // 内，顶层 pager 是事件链祖先，横滑无人消费被 pager 吃页；修复=overlay 根拦
    // 横滑）：**左滑=返回推荐**（回上一级语义）、右滑=关注（顶栏视觉序
    // [关注|时间线|推荐] 左右邻居）。方向裁决单一来源=MediaRailPolicy
    // （resolveCommit+resolveTimelineSwipe）；阈值与 L1 步进同口径（commit 半程
    // 44dp / fling 400dp/s）。overlaySwipeHost 不消费 down、竖滑/深层横滚让位——
    // 卡列表竖滚/点按/卡内横排缩略零影响；pageActive 闸（非落定页不吃手势）。
    val swipeDensity = androidx.compose.ui.platform.LocalDensity.current
    val swipeCommitPx = with(swipeDensity) {
        MediaRailPolicy.commitDistancePx(MediaRailPolicy.DRAG_STEP_DP.dp.toPx())
    }
    val swipeFlingPx = with(swipeDensity) { MediaRailPolicy.FLING_VELOCITY_DP.dp.toPx() }

    Column(
        Modifier
            .fillMaxSize()
            .background(TgFeedTokens.InkBlack)
            .overlaySwipeHost(enabled = pageActive) { dx, vx ->
                val target = MediaRailPolicy.resolveTimelineSwipe(
                    MediaRailPolicy.resolveCommit(dx, vx, swipeCommitPx, swipeFlingPx),
                )
                // R308 实机事件级验证探针（纪律§四）：一次定局一行（含慢漂移不动）
                android.util.Log.i(
                    "R308",
                    "时间线横滑定局 dx=%d v=%d → %s".format(
                        dx.toInt(),
                        vx.toInt(),
                        when (target) {
                            FeedColumn.RECOMMEND -> "回推荐"
                            FeedColumn.FOLLOWING -> "去关注"
                            null -> "不动"
                        },
                    ),
                )
                when (target) {
                    FeedColumn.RECOMMEND -> onSelectAggregate()
                    FeedColumn.FOLLOWING -> onSelectFollowing()
                    null -> Unit
                }
            }
            .statusBarsPadding(),
    ) {
        PursueHeader(
            bootstrapProgressFlow = vm.bootstrapProgress,
            onSelectFollowing = onSelectFollowing,
            onSelectTimeline = {}, // 时间线恒激活态：本 tab 单击无操作（TopLevelTab 已激活语义）
            onSelectAggregate = onSelectAggregate,
            onRefreshTimeline = onRefreshTimeline,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                // 加载中（首拉进行时/未完成过首拉）：AppLoading 口径（与空态分清）
                stream.isEmpty() && (loading || !bootstrapped) -> AppLoading("正在拉取频道动态…")
                stream.isEmpty() -> PursueEmptyState()
                else -> {
                    // 底衬让位：末卡落在底栏上沿之上（LocalBottomBarInset 同拍同曲线；
                    // 系统导航栏让位与 MineScreen/DiscoverScreen 同口径 navigationBarsPadding）
                    val barInset = LocalBottomBarInset.current
                    // R240 卡片流：微信/微博式卡列表（Surface 卡+12dp 圆角+GapMd 间距
                    // 呼吸感——R207 视觉语法；换掉 R239 的 9 列瀑布格=用户「认不出频道」痛点）
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding()
                            .testTag(UiTestTags.TIMELINE_LIST),
                        verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
                        contentPadding = PaddingValues(
                            top = TgFeedTokens.GapSm,
                            bottom = barInset + TgFeedTokens.GapXl,
                            start = TgFeedTokens.GapMd,
                            end = TgFeedTokens.GapMd,
                        ),
                    ) {
                        // 卡 key=chatId:卡首条目键（pursueCardKey 单一事实源）——实时推入
                        // （流尾追加）合并进末卡时 key 不变=实例保位不重播入场；历史
                        // prepend 的「拼进现首卡」罕见边角换 key（锚定漂移≤一卡高度，接受）
                        items(cards, key = { pursueCardKey(it) }) { card ->
                            PursueTimelineCardItem(
                                card = card,
                                liveEntrance = card.items.any { it.key in recentLiveKeys },
                                avatarLoader = avatarLoader,
                                gateway = gateway,
                                onThumbClick = { item ->
                                    // 邻域窗（±60 条）：时间线上下文进 Feed 播放
                                    // ——整流过大（几千条），窗口给上下文够用的有界集
                                    // （R239 链路原样——点缩略→播放不动）
                                    val idx = stream.indexOfFirst { it.key == item.key }
                                    val around = if (idx >= 0) {
                                        stream.subList(
                                            maxOf(0, idx - 60),
                                            minOf(stream.size, idx + 61),
                                        )
                                    } else {
                                        listOf(item)
                                    }
                                    onPlay(around, item.key, "时间线")
                                },
                                onCardClick = { onOpenChannelHome(card.items.last()) },
                            )
                        }
                    }
                }
            }
            // 顶缘状态胶囊：历史轮在飞/诚实终态（滑到顶才可见的反馈，不遮内容）
            // R432 重组收敛：derivedStateOf 包裹裸读 canScrollBackward（对照同文件
            // atBottom 的正确写法）——裸读让本 lambda 所在的整个 Box 作用域订阅
            // 每帧滚动位置，滚动全程全页重组；派生后只在越过顶/非顶边界时重组
            val atTopNow by remember {
                derivedStateOf { !listState.canScrollBackward }
            }
            val topStatus = when {
                historyLoading -> "正在翻更早的内容…"
                historyExhausted && atTopNow && stream.isNotEmpty() -> "已经到最早了"
                else -> null
            }
            topStatus?.let { status ->
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = TgFeedTokens.GapSm),
                ) {
                    OverlayInfoChip(text = status)
                }
            }
            // 底部「N 条新内容」胶囊（实时推入且用户不在底部时浮现；点按滚到底）。
            // 全限定调用：外层 Column 的 ColumnScope 在隐式接收链上，其成员扩展
            // AnimatedVisibility 会抢先解析——非 Column 语义的顶/底对齐胶囊要走
            // 顶层 androidx 版（r232 工程坑族：作用域解析歧义显式消歧）
            androidx.compose.animation.AnimatedVisibility(
                visible = newBelow > 0 && !atBottom,
                enter = slideInVertically(animationSpec = tween(TgFeedTokens.AnimMed)) { it } +
                    fadeIn(animationSpec = tween(TgFeedTokens.AnimMed)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Row(
                    Modifier
                        .padding(bottom = LocalBottomBarInset.current + TgFeedTokens.GapXl)
                        .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                        .background(TgFeedTokens.AccentRed)
                        .clickable {
                            vm.consumeNewBelow()
                            scope.launch {
                                if (cards.isNotEmpty()) {
                                    listState.animateScrollToItem(cards.lastIndex)
                                }
                            }
                        }
                        .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapSm + 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "$newBelow 条新内容",
                        color = TgFeedTokens.TextHigh,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

// ---------------- 页头（三 tab 一等公民行 + 呼吸灯） ----------------

/**
 * R245 卡 key 单一事实源：LazyColumn items key 与键锚（播放往返返回保位）共用同一
 * 式——两处各写一份会漂移（锚找不回 items 的 key=保位退化为近似 index）。
 */
private fun pursueCardKey(card: PursueTimelineCard): String =
    "${card.chatId}:${card.items.first().key}"

/**
 * 时间线页头：与 feed 顶栏同一视觉骨架（左区 132dp 假位对齐 + 中区 HomeTabsRow +
 * 右区 ~96dp 假位）——切 tab 时三 tab 位置零漂移（同一事实源渲染件 + 同一布局
 * 骨架，R157 双 tab 失衡教训的预防）。左区放呼吸灯「正在更新」（bootstrap
 * 渐进期显示「拉取 N/总数」）。
 */
@Composable
private fun PursueHeader(
    // R432 重组收敛：流本体下传、collect 收在本头部内——拉取期「N/总数」高频变更
    // 只重组本头部那一小行，PursueScreen 整页不再跟着全量重组
    bootstrapProgressFlow: StateFlow<String?>,
    onSelectFollowing: () -> Unit,
    onSelectTimeline: () -> Unit,
    onSelectAggregate: () -> Unit,
    onRefreshTimeline: () -> Unit,
) {
    val bootstrapProgress by bootstrapProgressFlow.collectAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左区（132dp 假位=feed 顶栏 ☰ 区宽度）：呼吸灯+「正在更新」/bootstrap 进度
        Row(
            Modifier.width(132.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PursueBreathingDot()
            Spacer(Modifier.width(TgFeedTokens.GapSm))
            Text(
                bootstrapProgress?.let { "拉取 $it" } ?: "正在更新",
                style = MaterialTheme.typography.labelMedium,
                color = TgFeedTokens.TextMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 中区：三 tab（关注|时间线|推荐）——HomeTabsRow 唯一事实源（与 feed 顶栏
        // 同件，排布/文案/双击语义零漂移）；时间线恒为激活态（本页即时间线）
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            HomeTabsRow(
                followingActive = false,
                timelineActive = true,
                aggregateActive = false,
                onSelectFollowing = onSelectFollowing,
                onSelectTimeline = onSelectTimeline,
                onSelectAggregate = onSelectAggregate,
                onRefreshFeed = {}, // 时间线态下关注/推荐均未激活：双击刷新不可达（单击=切回 feed）
                onRefreshTimeline = onRefreshTimeline,
            )
        }
        // 右区（96dp 假位=feed 顶栏搜索/分屏两位钮宽度）：留白对齐
        Spacer(Modifier.width(96.dp))
    }
}

/** 呼吸灯：AccentRed 圆点 α/缩放连续脉动（「频道都在更新」的活感指示——R229 同款） */
@Composable
private fun PursueBreathingDot() {
    val pulse = rememberInfiniteTransition(label = "pursueBreath")
    val t by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.30f,
        animationSpec = infiniteRepeatable(
            animation = tween(PursueBreathPulseMs),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pursueBreathT",
    )
    Box(
        Modifier
            .size(8.dp)
            .graphicsLayer {
                alpha = t
                val s = 0.72f + 0.28f * t
                scaleX = s
                scaleY = s
            }
            .clip(CircleShape)
            .background(TgFeedTokens.AccentRed),
    )
}

// ---------------- 空态 ----------------

@Composable
private fun PursueEmptyState() {
    AppEmptyState(
        icon = Icons.Outlined.RssFeed,
        title = "你加入的频道更新会出现在这里",
        subtitle = "最近 3 天全部频道的视频/图片/音频汇成时间流，最新排在最下面",
    )
}

// ---------------- R240 时间线卡片（卡流单元） ----------------

/**
 * 卡片（LazyColumn 单元）：实时推入的新卡（[liveEntrance]=true）从底部
 * 滑入（slideInVertically{+it}+fadeIn，tween=AnimMed）；已组合卡重组合不重播
 * （MutableTransitionState 落定态静止；滚动回看的老卡无入场）。
 */
@Composable
private fun PursueTimelineCardItem(
    card: PursueTimelineCard,
    liveEntrance: Boolean,
    avatarLoader: suspend (Long) -> ByteArray?,
    gateway: TdGateway,
    onThumbClick: (VideoItem) -> Unit,
    onCardClick: () -> Unit,
) {
    if (!liveEntrance) {
        PursueTimelineCardContent(card, avatarLoader, gateway, onThumbClick, onCardClick)
        return
    }
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = state,
        enter = slideInVertically(animationSpec = tween(TgFeedTokens.AnimMed)) { it } +
            fadeIn(animationSpec = tween(TgFeedTokens.AnimMed)),
    ) {
        PursueTimelineCardContent(card, avatarLoader, gateway, onThumbClick, onCardClick)
    }
}

/**
 * 卡片本体（微信/微博式）：
 * - 卡壳：Surface 底+RadiusMd 12dp 圆角+GapMd 内衬（R207 视觉语法）；整卡可点=
 *   进频道主页（[onCardClick]——缩略的内层 clickable 先消费点按，其余落卡壳）；
 * - 卡头：36dp 头像+频道名（单行省略，空名回落「频道尾号」——脱敏口径的 UI 面）
 *   +相对时间（卡尾最新一条，右置）；
 * - 缩略区：单条=3:4 大缩略（60% 宽）；多条=1~3 张 1:1 横排+末位「+N」溢出角标
 *   （整排 widthIn 帽——宽屏防巨图+朋友圈式左对齐读法）。
 */
@Composable
private fun PursueTimelineCardContent(
    card: PursueTimelineCard,
    avatarLoader: suspend (Long) -> ByteArray?,
    gateway: TdGateway,
    onThumbClick: (VideoItem) -> Unit,
    onCardClick: () -> Unit,
) {
    // 频道名兜底：标题懒解析失败（空串）时用尾号代称（不猜名字——脱敏口径的 UI 面）
    val title = card.channelTitle.ifBlank { "频道 ${card.chatId.toString().takeLast(4)}" }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
            .background(TgFeedTokens.Surface)
            .clickable(onClick = onCardClick)
            .padding(TgFeedTokens.GapMd),
    ) {
        // 卡头：头像+频道名+相对时间（点卡头=进频道主页——onCardClick 承载）
        Row(verticalAlignment = Alignment.CenterVertically) {
            PursueCardAvatar(chatId = card.chatId, title = title, avatarLoader = avatarLoader)
            Spacer(Modifier.width(TgFeedTokens.GapSm))
            Text(
                title,
                color = TgFeedTokens.TextHigh,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(TgFeedTokens.GapSm))
            Text(
                "· ${relativeTimeZh(card.newestSec * 1000L)}",
                color = TgFeedTokens.TextMedium,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
        // R245 内容标题行（实锤②）：用户要看「这条内容是什么」不只是「谁发的」——
        // 批内最新条目的标题（与卡头时间同源）：parsed_title 优先/NULL 回退 caption
        // 首个非空行（R228 titleLine 共享件唯一实现，零新抄）；TextMedium 单行省略
        // （R207 视觉语法）；空白标题跳行不占位（媒体卡无文案时不留空行）
        val contentTitle = card.items.last().titleLine()
        if (contentTitle.isNotBlank()) {
            Spacer(Modifier.height(TgFeedTokens.GapSm))
            Text(
                contentTitle,
                color = TgFeedTokens.TextMedium,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(TgFeedTokens.GapMd))
        if (card.items.size == 1) {
            // 单条=大缩略（3:4 竖幅，60% 宽+宽帽）
            PursueCardThumb(
                item = card.items.single(),
                gateway = gateway,
                onThumbClick = onThumbClick,
                modifier = Modifier
                    .widthIn(max = PursueCardThumbsMaxWidth)
                    .fillMaxWidth(PursueCardSingleThumbWidthFraction)
                    .aspectRatio(3f / 4f),
            )
        } else {
            // 多条=1~3 张 1:1 横排（旧→新从左到右）+末位溢出「+N」角标
            Row(
                Modifier
                    .widthIn(max = PursueCardThumbsMaxWidth)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
            ) {
                card.thumbs.forEachIndexed { i, thumb ->
                    PursueCardThumb(
                        item = thumb,
                        gateway = gateway,
                        onThumbClick = onThumbClick,
                        overflowCount = if (i == card.thumbs.lastIndex) card.overflowCount else 0,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                    )
                }
            }
        }
    }
}

/**
 * 缩略格：两级封面链（minithumbnail 即时垫底 → CoverStore 命中/按消息拉取高清——
 * R239 格同款原样迁移）+右下类型角标（视频/音频=时长、图/图集）+空封面类型图标
 * 兜底+末位溢出「+N」角标（非交互——被折叠条目经邻域窗上滑可达）。点格=播该条。
 */
@Composable
private fun PursueCardThumb(
    item: VideoItem,
    gateway: TdGateway,
    onThumbClick: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
    overflowCount: Int = 0,
) {
    val cover by produceState<ImageBitmap?>(initialValue = null, item.key) {
        // 第一级：minithumbnail（IO 解码——组合期解码是 R170 家族帧预算窃贼）
        item.coverBytes?.let { bytes ->
            value = withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            }?.asImageBitmap()
        }
        // 第二级：真封面（抽帧共享表命中 → 按消息拉取；仅真网关，Mock 无封面链）
        val lib = gateway as? TdLibGateway ?: return@produceState
        CoverStore.map[item.key]?.let { value = it }
        val bytes = withContext(Dispatchers.IO) {
            runCatching { lib.loadCoverBytesByMessage(item) }.getOrNull()
        } ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }?.asImageBitmap()
    }
    Box(
        modifier
            .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
            .background(TgFeedTokens.SurfaceHigh)
            .clickable { onThumbClick(item) },
    ) {
        if (cover != null) {
            Image(
                bitmap = cover!!,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = pursueTypeIcon(item.mediaType),
                contentDescription = null,
                tint = TgFeedTokens.TextLow,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(22.dp),
            )
        }
        // 右下：类型角标（OverlayInfoChip 唯一实现——Scrim 红线零字面量）
        OverlayInfoChip(
            text = pursueTypeBadge(item),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(3.dp),
        )
        // 顶右：溢出「+N」角标（末位缩略=本卡被折叠的更早条数）
        if (overflowCount > 0) {
            OverlayInfoChip(
                text = "+$overflowCount",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp),
            )
        }
    }
}

/**
 * 卡头频道头像：真头像圆 36dp（R194④ 共享件 rememberChannelAvatarImage——加载+解码
 * 双 IO 化）/身份色字母圆兜底（ChannelIdentity 共享件——身份函数非强调色）。
 */
@Composable
private fun PursueCardAvatar(
    chatId: Long,
    title: String,
    avatarLoader: suspend (Long) -> ByteArray?,
) {
    val avatarImage = rememberChannelAvatarImage(chatId, avatarLoader)
    if (avatarImage != null) {
        Image(
            bitmap = avatarImage,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(PursueCardAvatarSize)
                .clip(CircleShape),
        )
    } else {
        Box(
            Modifier
                .size(PursueCardAvatarSize)
                .clip(CircleShape)
                .background(ChannelIdentity.colorOf(chatId)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title.firstOrNull()?.uppercase() ?: "频",
                color = TgFeedTokens.TextHigh,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** 类型图标：0 视频 / 1 图片 / 2 音频（R224 mockup 同一映射口径） */
private fun pursueTypeIcon(mediaType: Int) = when (mediaType) {
    1 -> Icons.Outlined.Photo
    2 -> Icons.Outlined.MusicNote
    else -> Icons.Outlined.PlayArrow
}

/** 类型徽文案：视频/音频=时长，图片=图集归组时「图集」否则「图」 */
private fun pursueTypeBadge(item: VideoItem): String = when (item.mediaType) {
    1 -> if (item.albumId != 0L) "图集" else "图"
    else -> formatDuration(item.durationSec)
}
