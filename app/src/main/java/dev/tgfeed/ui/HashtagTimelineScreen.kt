package dev.tgfeed.ui

import android.app.Application
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tgfeed.core.feed.EventResearchResult
import dev.tgfeed.core.feed.EventResearcher
import dev.tgfeed.core.feed.EventResearchUiState
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.feed.FeedViewModelFactory
import dev.tgfeed.core.feed.TimeWindowPolicy
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.ui.components.CoverStore
import dev.tgfeed.ui.components.OverlayInfoChip
import dev.tgfeed.ui.components.formatDuration
import dev.tgfeed.ui.components.kit.AppEmptyState
import dev.tgfeed.ui.components.kit.AppLoading
import dev.tgfeed.ui.components.kit.AppTopBar
import dev.tgfeed.ui.theme.ChannelIdentity
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * R222 #关键字时间轴页：caption #标签 → 双选弹层「时间轴探索」的落点。
 *
 * **引擎复用（R205 EventResearcher，零重写）**：runHashtagTimeline(tag)（VM part）
 * 以全史窗 EventResearchQuery(keywords="#tag") 走 FtsKeywordWindowSource →
 * DeepSearchIndex hashtag 精确词召回 → filterWindow/bucketTimeline 纯函数；
 * 本屏只消费 [FeedViewModel.hashtagTimeline] 状态。与发现页研究器入口
 * （EventResearchScreen）共享引擎但状态分离（互不洗缓存）。
 *
 * **时间轴视图**（任务口径逐点落地）：
 *  - 横向时间轴：**从该关键字首次出现到最新**（量程=召回实际跨度，非预设窗宽）；
 *  - 节点=内容缩略：封面墙（Adaptive 网格，时间倒序=Feed 翻看习惯）；
 *  - 频道维度三件套：节点按频道**身份色**着色（ChannelIdentity 共享件，R207
 *    「身份函数非强调色」口径）+ chips 行分组过滤（纯客户端，墙/轴/播放批同步）
 *    + 统计行（侧栏统计的收敛形态）；
 *  - 热度分布：轴上密度墙（bucketTimeline 分桶驱动，R202 时光机同视觉语法：
 *    归一亮度竖条 + 年刻度）；
 *  - **点节点→播放**：轴上点任意位置=命中最近节点（|Δt| 最小）；墙格点按=该条
 *    播放。batch=过滤后整墙（「墙显什么，滑进去刷的就是什么」——时光机口径）。
 *
 * 播放编排（宿主 MainActivity）：onPlay(batch, label, target) → 复位 tab/筛选 →
 * IO 复活（reviveDetachedItems）→ TimeWindowFeedSource 一次性源 → 轮询
 * requestFeedJump 定位 → 返回键先回本页（RoutePendingKind.HASHTAG_TIMELINE，
 * pending 置位在切源之后——r202 戳序竞态口径）。
 */
@Composable
fun HashtagTimelineScreen(
    gateway: TdGateway,
    tag: String,
    onBack: () -> Unit,
    onPlay: (batch: List<VideoItem>, label: String, target: VideoItem) -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    // 系统返回键 = 返回上一页（状态驱动路由，不在返回栈里，不拦会退到桌面）
    BackHandler(onBack = onBack)
    // 与 FeedScreen 共享 activity 作用域同一个 ViewModel（"gw"+identityHashCode 约定）
    val vm: FeedViewModel = viewModel(
        factory = FeedViewModelFactory(gateway, app),
        key = "gw" + System.identityHashCode(gateway),
    )
    val state by vm.hashtagTimeline.collectAsState()

    // 打开即跑（同 tag 缓存命中不重扫：返回键回本页零成本——LIKE 设备 45s 级
    // 深扫不重烧；换 tag 重查）
    LaunchedEffect(tag) { vm.runHashtagTimeline(tag) }

    Column(
        Modifier
            .fillMaxSize()
            .background(TgFeedTokens.InkBlack),
    ) {
        AppTopBar(
            title = "#$tag",
            onBack = onBack,
            actions = {
                Icon(
                    Icons.Outlined.Timeline,
                    contentDescription = null,
                    tint = TgFeedTokens.TextLow,
                    modifier = Modifier.size(TgFeedTokens.IconMd),
                )
            },
        )
        when (val s = state) {
            // 打开即跑：Idle 只存在于首帧（LaunchedEffect 尚未起跑）
            EventResearchUiState.Idle, EventResearchUiState.Loading -> AppLoading(
                "正在召回 #$tag 从首次出现到最新的全部内容…\n" +
                    "tags 索引倒排检索（毫秒级；冷缓存首查略慢）",
            )
            // hashtags 恒非空 → 引擎不会走 EnginePending；诚实兜底（不装作无结果）
            EventResearchUiState.EnginePending -> AppEmptyState(
                icon = Icons.Outlined.Schedule,
                title = "时间轴引擎还没接线",
                subtitle = "话题关键词路径已可用；若见此文案请反馈（不可达兜底态）",
            )
            is EventResearchUiState.Failed -> AppEmptyState(
                icon = Icons.AutoMirrored.Outlined.ManageSearch,
                title = "时间轴没拉出来",
                subtitle = s.reason,
            )
            is EventResearchUiState.Ready -> TimelineBody(
                gateway = gateway,
                tag = tag,
                result = s.result,
                onPlay = onPlay,
            )
        }
    }
}

// ---------------- 结果主体 ----------------

/** 频道聚合行（chips/统计行数据源） */
private data class ChannelStat(val chatId: Long, val title: String, val count: Int)

/** 时间轴密度墙分桶数（2000 条召回上限下 60 桶≈3dp/桶像素级足够） */
private const val AXIS_BUCKETS = 60

/** 频道 chips 展示上限（长尾频道不刷屏；与 EventResearchScreen 的 12 同口径） */
private const val CHANNEL_CHIPS_MAX = 12

/** 时间轴墙格数据量上限（墙与播放批同源：墙显什么，滑进去刷的就是什么） */
private const val WALL_LIMIT = 240

@Composable
private fun TimelineBody(
    gateway: TdGateway,
    tag: String,
    result: EventResearchResult,
    onPlay: (batch: List<VideoItem>, label: String, target: VideoItem) -> Unit,
) {
    val fmtDate = remember { SimpleDateFormat("yyyy-MM-dd", Locale.CHINA) }
    // 引擎输出已按时间升序：首=首次出现，末=最新
    val items = result.items
    if (items.isEmpty()) {
        AppEmptyState(
            icon = Icons.Outlined.Tag,
            title = "本地索引里还没有 #$tag 的内容",
            subtitle = "深索引边爬边长，之后再来；\n也可以用「搜索播放」走服务端路径碰碰运气。",
        )
        return
    }
    val minSec = items.first().dateEpochSec.toLong()
    val maxSec = items.last().dateEpochSec.toLong()
    val spanDays = ((maxSec - minSec) / 86400L).coerceAtLeast(1L)

    // 频道聚合（按条目数降序）——chips + 统计行的数据源
    val channels = remember(result) {
        items.groupBy { it.chatId }
            .entries
            .sortedByDescending { it.value.size }
            .take(CHANNEL_CHIPS_MAX)
            .map { e ->
                ChannelStat(
                    chatId = e.key,
                    title = e.value.firstOrNull()?.channelTitle ?: "频道 ${e.key}",
                    count = e.value.size,
                )
            }
    }
    val channelTotal = remember(result) { items.map { it.chatId }.distinct().size }

    // 频道筛选（纯客户端二次过滤——墙/轴/播放批同源同步；空选中回落「全部」，
    // 反选最后一片 chip 不落「什么都看不见」的死屏）
    var selectedChatIds by remember(result) { mutableStateOf<Set<Long>?>(null) }
    val filtered = remember(result, selectedChatIds) {
        val active = selectedChatIds?.takeIf { it.isNotEmpty() }
        if (active == null) items else items.filter { it.chatId in active }
    }
    // 墙序：时间倒序（Feed 翻看习惯「新 → 旧」）；播放批=整墙
    val wall = remember(filtered) {
        filtered.sortedByDescending { it.dateEpochSec.toLong() }.take(WALL_LIMIT)
    }
    // 密度墙分桶（筛选变化即重算——纯函数，毫秒级）
    val buckets = remember(filtered, minSec, maxSec) {
        EventResearcher.bucketTimeline(filtered, minSec, maxSec, AXIS_BUCKETS)
    }
    // 播放批次里被 take(WALL_LIMIT) 截掉的长尾：播放批=墙（墙显什么刷什么）；
    // 播放标签（潜进源面包屑）
    val playLabel = "#$tag 时间轴"

    Column(Modifier.fillMaxSize()) {
        // ---- 统计行（侧栏统计的收敛形态：计数+跨度）----
        Text(
            "${filtered.size} 条内容 · $channelTotal 个频道 · 首次 ${fmtDate.format(Date(minSec * 1000L))} → 最新 ${fmtDate.format(Date(maxSec * 1000L))} · 跨 $spanDays 天",
            style = MaterialTheme.typography.labelMedium,
            color = TgFeedTokens.TextLow,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapXs),
        )
        // 引擎口径（诚实上屏）
        Text(
            result.engineNote,
            style = MaterialTheme.typography.labelSmall,
            color = TgFeedTokens.TextLow,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TgFeedTokens.GapLg),
        )
        // ---- 频道 chips（分组/过滤维度）----
        if (channelTotal > 1) {
            LazyRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapXs),
                horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
            ) {
                item {
                    ChannelChip(
                        dotColor = null,
                        label = "全部 ${items.size}",
                        selected = selectedChatIds == null || selectedChatIds!!.isEmpty(),
                        onClick = { selectedChatIds = null },
                    )
                }
                items(channels.size) { i ->
                    val stat = channels[i]
                    val on = selectedChatIds?.contains(stat.chatId) == true
                    ChannelChip(
                        dotColor = ChannelIdentity.colorOf(stat.chatId),
                        label = "${stat.title} ${stat.count}",
                        selected = on,
                        onClick = {
                            selectedChatIds = if (on) selectedChatIds!! - stat.chatId
                            else (selectedChatIds ?: emptySet()) + stat.chatId
                        },
                    )
                }
            }
        }
        // ---- 横向时间轴（密度墙+节点行，点任意位置=最近节点播放）----
        HashtagTimelineAxis(
            items = filtered,
            buckets = buckets,
            minSec = minSec,
            maxSec = maxSec,
            fmtDate = fmtDate,
            onNodeTap = { node -> onPlay(wall, playLabel, node) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TgFeedTokens.GapLg),
        )
        // ---- 节点墙（内容缩略）----
        val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        LazyVerticalGrid(
            // R171 口径：列数随实测宽涌现（与时光机/收藏墙同款；私有名避免与同包
            // TimeMachineScreen 的 MediaWallCellMin 顶层冲突）
            columns = GridCells.Adaptive(HashtagWallCellMin),
            state = gridState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(
                top = TgFeedTokens.GapSm,
                bottom = TgFeedTokens.GapLg,
            ),
            horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GridGap),
            verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GridGap),
        ) {
            items(
                count = wall.size,
                key = { i -> wall[i].key },
            ) { i ->
                val item = wall[i]
                HashtagWallCell(
                    item = item,
                    gateway = gateway,
                    onPlay = { onPlay(wall, playLabel, item) },
                )
            }
        }
    }
}

// ---------------- 横向时间轴（私有组件） ----------------

/**
 * 横向时间轴：密度墙（热度分布，R202 同视觉语法）+ 节点行（每条内容一枚色点，
 * 按频道身份色着色）+ 轨道 + 年刻度 + 首末日期标签。
 *
 * 交互=**单个 pointerInput**（纪律§四「同节点双 pointerInput=互杀」禁例规避）：
 * detectTapGestures 点按 → 位置比例 → 绝对时刻 → |Δt| 最近节点上抛播放
 * （「点事件轴任意节点从那块开始播放」）。
 */
@Composable
private fun HashtagTimelineAxis(
    items: List<VideoItem>,
    buckets: List<dev.tgfeed.core.feed.TimelineBucket>,
    minSec: Long,
    maxSec: Long,
    fmtDate: SimpleDateFormat,
    onNodeTap: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (maxSec <= minSec) return
    val ticks = remember(minSec, maxSec) { TimeWindowPolicy.yearTicks(minSec, maxSec) }
    val maxCount = buckets.maxOfOrNull { it.count } ?: 0
    // 节点数据预投影（时间+身份色 argb——Canvas 内不做 Compose 侧转换）
    val nodes = remember(items, minSec, maxSec) {
        items.mapNotNull { item ->
            val t = item.dateEpochSec.toLong()
            if (t <= 0L) null
            else Triple(
                TimeWindowPolicy.fractionFromAnchor(t, minSec, maxSec),
                ChannelIdentity.colorOf(item.chatId).toArgb(),
                t,
            )
        }
    }
    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(84.dp)
                .pointerInput(items, minSec, maxSec) {
                    detectTapGestures { ofs ->
                        val f = (ofs.x / size.width.toFloat()).coerceIn(0f, 1f)
                        val t = minSec + ((maxSec - minSec) * f).toLong()
                        // 最近节点（|Δt| 最小；minBy 稳定取首个并列=先发）
                        val nearest = items.minByOrNull {
                            kotlin.math.abs(it.dateEpochSec.toLong() - t)
                        }
                        if (nearest != null && nearest.dateEpochSec > 0) onNodeTap(nearest)
                    }
                },
        ) {
            val w = size.width
            val densityTop = 4.dp.toPx()
            val densityBottom = 34.dp.toPx()
            val nodeY = 52.dp.toPx()
            val trackY = 66.dp.toPx()
            val tickBottom = 72.dp.toPx()
            // 密度墙（热度分布：桶计数归一亮度竖条——哪里密哪里亮）
            if (buckets.isNotEmpty() && maxCount > 0) {
                val slot = w / buckets.size
                val barW = 2.dp.toPx().coerceAtMost(slot * 0.7f)
                for ((i, b) in buckets.withIndex()) {
                    if (b.count <= 0) continue
                    val d = b.count.toFloat() / maxCount
                    val h = ((densityBottom - densityTop) * d).coerceAtLeast(1f)
                    drawRect(
                        color = TgFeedTokens.TextHigh.copy(alpha = 0.10f + 0.32f * d),
                        topLeft = Offset(slot * i + (slot - barW) / 2f, densityBottom - h),
                        size = Size(barW, h),
                    )
                }
            }
            // 节点行（每条内容一枚色点，按频道身份色——密集处自然堆成热度带）
            for ((f, argb, _) in nodes) {
                drawCircle(
                    color = androidx.compose.ui.graphics.Color(argb),
                    radius = 2.dp.toPx(),
                    center = Offset(w * f, nodeY),
                )
            }
            // 轨道
            drawLine(
                color = TgFeedTokens.TextHigh.copy(alpha = 0.25f),
                start = Offset(0f, trackY),
                end = Offset(w, trackY),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            // 年刻度（native 文本：Compose Canvas 画原生 Paint 最省一层组件——
            // TimeMachineScreen 同手法）
            val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = TgFeedTokens.TextLow.toArgb()
                textSize = 10.dp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
            }
            for (t in ticks) {
                val x = w * TimeWindowPolicy.fractionFromAnchor(t.sec, minSec, maxSec)
                drawLine(
                    color = TgFeedTokens.TextLow.copy(alpha = 0.6f),
                    start = Offset(x, trackY - 5.dp.toPx()),
                    end = Offset(x, tickBottom),
                    strokeWidth = 1.dp.toPx(),
                )
                drawContext.canvas.nativeCanvas.drawText(t.label, x, tickBottom + 12.dp.toPx(), labelPaint)
            }
            // 首末节点高亮拇指（AccentRed 主点=最新端；白芯=首次端——「从头到尾」
            // 的两端可读性锚；滑杆家族面孔）
            if (nodes.isNotEmpty()) {
                drawCircle(
                    color = TgFeedTokens.AccentRed,
                    radius = 6.dp.toPx(),
                    center = Offset(w * nodes.first().first, nodeY),
                )
                drawCircle(
                    color = TgFeedTokens.TextHigh,
                    radius = 2f.dp.toPx(),
                    center = Offset(w * nodes.first().first, nodeY),
                )
                drawCircle(
                    color = TgFeedTokens.TextHigh,
                    radius = 4.dp.toPx(),
                    center = Offset(w * nodes.last().first, nodeY),
                )
            }
        }
        // 首末日期标签（轴下沿，与统计行互为印证）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = TgFeedTokens.GapXs),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "首次 ${fmtDate.format(Date(minSec * 1000L))}",
                color = TgFeedTokens.TextLow,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                "最新 ${fmtDate.format(Date(maxSec * 1000L))}",
                color = TgFeedTokens.TextLow,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

// ---------------- 频道 chip（私有组件） ----------------

/** 频道 chip：身份色点 + 名称 + 计数（选中=TgBlue 淡底描边——tap 目标视觉档） */
@Composable
private fun ChannelChip(
    dotColor: androidx.compose.ui.graphics.Color?,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(TgFeedTokens.RadiusPill)
    Row(
        Modifier
            .clip(shape)
            .background(if (selected) TgFeedTokens.TgBlue.copy(alpha = 0.28f) else TgFeedTokens.SurfaceHigh)
            .border(
                0.5.dp,
                if (selected) TgFeedTokens.TgBlue else TgFeedTokens.OverlayHairline,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = TgFeedTokens.GapMd, vertical = TgFeedTokens.GapXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dotColor != null) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(Modifier.width(TgFeedTokens.GapXs))
        }
        Text(
            label,
            color = if (selected) TgFeedTokens.TextHigh else TgFeedTokens.TextMedium,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

// ---------------- 封面墙格子（私有组件） ----------------

/** 墙格最小宽（R171 自适应列口径，时光机/收藏墙同值；私有名避同包冲突） */
private val HashtagWallCellMin = 104.dp

/**
 * 时间轴墙格：3:4 封面 + 频道名 scrim（顶横）+ 时间戳 chip（底左，本屏特有：
 * 时间轴的「何时」是第一信息位）+ 类型角标（底右）。
 * 封面链与时光机墙/收藏墙同族（CoverStore 抽帧 → coverBytes → loadCoverBytes/
 * loadAudioCoverBytes 高清 → loadCoverBytesByMessage 消息反解兜底 → 图片原图
 * 采样解码）——索引条目 coverBytes 恒 null，实际走高清/反解两档。
 */
@Composable
private fun HashtagWallCell(
    item: VideoItem,
    gateway: TdGateway,
    onPlay: () -> Unit,
) {
    val fmtTime = remember { SimpleDateFormat("MM-dd", Locale.CHINA) }
    val cover: ImageBitmap? = produceState<ImageBitmap?>(initialValue = null, item.key) {
        CoverStore.map[item.key]?.let { value = it }
        if (value == null) {
            value = item.coverBytes?.let { bytes ->
                withContext(Dispatchers.IO) {
                    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap() }.getOrNull()
                }
            }
        }
        val lib = gateway as? TdLibGateway
        if (value == null && lib != null) {
            // 高清封面（取字节+解码整段下放 IO，几十格成串解码不挡组合线程）
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
            // 消息反解兜底（索引条目跨会话 fileId 失效时：chatId+messageId 反解真缩略图）
            if (value == null && item.messageId > 0) {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { lib.loadCoverBytesByMessage(item) }.getOrNull()
                }
                if (bytes != null) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap() }.getOrNull()
                    }
                }
            }
            // 图片（mediaType=1）：原图采样解码链（共享件）
            if (value == null && item.mediaType == 1 && item.fileId >= 0) {
                val photoHd = withContext(Dispatchers.IO) {
                    runCatching { loadPhotoCoverBitmap(lib, item) }.getOrNull()
                }
                if (photoHd != null) value = photoHd
            }
        }
    }.value

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
            .background(TgFeedTokens.SurfaceHigh)
            .clickable(onClick = onPlay),
    ) {
        if (cover != null) {
            Image(
                bitmap = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 无封面占位：类型字（收藏墙/时光机墙同款兜底）
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = when (item.mediaType) {
                        1 -> "图"
                        2 -> "音"
                        else -> "视"
                    },
                    color = TgFeedTokens.TextLow,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        // 频道名 scrim（顶横渐隐——时间轴的「谁在发」；点色=频道身份色）
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            TgFeedTokens.OverlayBg,
                            TgFeedTokens.OverlayBg.copy(alpha = 0f),
                        ),
                    ),
                ),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(ChannelIdentity.colorOf(item.chatId)),
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = item.channelTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextHigh,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 时间戳 chip（底左——时间轴的「何时」是第一信息位；类型角标底右）
        OverlayInfoChip(
            text = if (item.dateEpochSec > 0) fmtTime.format(Date(item.dateEpochSec.toLong() * 1000L)) else "—",
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp),
            contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp),
        )
        val badge = when (item.mediaType) {
            0 -> formatDuration(item.durationSec)
            1 -> if (item.albumId != 0L) "图集" else "图"
            else -> "♪"
        }
        OverlayInfoChip(
            text = badge,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp),
            contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}
