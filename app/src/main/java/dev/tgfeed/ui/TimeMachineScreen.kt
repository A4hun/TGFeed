package dev.tgfeed.ui

import android.app.Application
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Schedule
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tgfeed.core.feed.DeepSearchIndex
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.feed.FeedViewModelFactory
import dev.tgfeed.core.feed.TimeWindowPolicy
import dev.tgfeed.core.feed.loadTimeBounds
import dev.tgfeed.core.feed.loadTimeHistogram
import dev.tgfeed.core.feed.loadTimeWindowItems
import dev.tgfeed.core.feed.timeIndexReadyFlow
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.ui.components.CoverStore
import dev.tgfeed.ui.components.OverlayInfoChip
import dev.tgfeed.ui.components.formatDuration
import dev.tgfeed.ui.components.kit.AppEmptyState
import dev.tgfeed.ui.components.kit.AppLoading
import dev.tgfeed.ui.components.kit.AppPill
import dev.tgfeed.ui.components.kit.AppTopBar
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 时光机入口意图（三模式——AppContainer 路由参数与 [TimeMachineScreen] 的全部差异面）：
 *  - [Now]：DS-1 此刻横截面（锚点=打开时刻、窗宽 ±30 分、头部新鲜度诚实口径）；
 *  - [Free]：TL-1 全网时光机（锚点 null=自动近窗最密日，也可外部指定）；
 *  - [Event]：TL-4 事件考古（锚点=该条发布时刻、窗宽 ±48h、头部带锚点事件上下文）。
 */
sealed interface TimeMachineIntent {
    data object Now : TimeMachineIntent

    /** anchorSec=null：量程到货后自动跳近窗最密日（首开有货保证） */
    data class Free(val anchorSec: Long? = null) : TimeMachineIntent

    data class Event(val item: VideoItem) : TimeMachineIntent
}

/**
 * R202 时间维度族旗舰屏：全网时光机（TL-1）/ 事件考古（TL-4）/ 此刻横截面（DS-1）
 * ——**三入口一屏三模式**（[TimeMachineIntent] 只差初始锚点/窗宽/文案，引擎同一套：
 * 时间滑块 → [DeepSearchIndex.timeWindowItems] 有界横截面查询（索引毫秒级）→
 * [TimeWindowPolicy.refineCrossSection] 精炼（广告剔除/同片去重保首发/频道封顶
 * 轮转）→ 封面墙马赛克 → 点格子进 Feed 播放（横截面即播放列表——墙显什么，
 * 滑进去刷的就是什么）。
 *
 * 数据纪律（R183 金矿同款）：
 *  - 首开陈值先回：量程/密度后台到货前滑杆按默认量程可用；查询索引未就绪时空态
 *    承接 + [timeIndexReadyFlow] 建完自动补刷（绝不为查询触发建库/全表扫）；
 *  - 查询防抖 200ms（拖拽跟手的是锚点大标签，网络/磁盘查询只在停顿时发生）；
 *  - 诚实口径：空窗文案说明采样稀疏性；此刻模式标注索引新鲜度量级（头部 1~6h
 *    节流）；统计行如实报同片折叠/广告剔除数。
 *
 * 播放编排（宿主 MainActivity）：onPlay(batch, label, target) → 复位 tab/筛选 →
 * IO 复活 DB 条目（reviveDetachedItems，R203 那年今日同款）→ TimeWindowFeedSource
 * 一次性源 → 轮询 requestFeedJump 定位 → 返回键先回本页（RoutePendingKind.
 * TIME_MACHINE）。
 */
@Composable
fun TimeMachineScreen(
    gateway: TdGateway,
    intent: TimeMachineIntent,
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

    val nowSec = remember { System.currentTimeMillis() / 1000L }

    // 索引就绪流：后台建索引完成后自动补刷一轮（R183 金矿「建完自动补刷」同款）
    val indexReady by vm.timeIndexReadyFlow.collectAsState()

    // 量程 + 密度直方图（后台一次；到货前滑杆用回落量程照样可玩=陈值先回）
    var bounds by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var histogram by remember { mutableStateOf<List<Pair<Int, Int>>>(emptyList()) }
    LaunchedEffect(intent) {
        withContext(Dispatchers.IO) {
            bounds = vm.loadTimeBounds()
            histogram = vm.loadTimeHistogram()
        }
    }
    val effectiveBounds = bounds ?: (nowSec - 2L * 365 * TimeWindowPolicy.DAY_SEC) to nowSec
    val minSec = effectiveBounds.first
    val maxSec = maxOf(effectiveBounds.second, minSec + TimeWindowPolicy.DAY_SEC)

    // 锚点与窗宽（三模式的全部差异都在这两个初值+文案，引擎同一套）
    var anchorSec by remember(intent) {
        mutableStateOf(
            when (intent) {
                is TimeMachineIntent.Event ->
                    intent.item.dateEpochSec.takeIf { it > 0 }?.toLong() ?: (nowSec - TimeWindowPolicy.DAY_SEC)
                is TimeMachineIntent.Free -> intent.anchorSec ?: (nowSec - TimeWindowPolicy.DAY_SEC)
                TimeMachineIntent.Now -> nowSec
            },
        )
    }
    var halfWidthSec by remember(intent) {
        mutableStateOf(
            when (intent) {
                is TimeMachineIntent.Event -> 2L * TimeWindowPolicy.DAY_SEC
                TimeMachineIntent.Now -> 30L * 60
                is TimeMachineIntent.Free -> 6L * TimeWindowPolicy.HOUR_SEC
            },
        )
    }
    // 用户是否已动过锚点（动过=不再接受默认锚点重置）
    var anchorTouched by remember(intent) { mutableStateOf(false) }
    LaunchedEffect(bounds, histogram) {
        // 量程到货且用户未动过：Free 模式重置到近窗最密日（首开有货保证）
        if (!anchorTouched && intent is TimeMachineIntent.Free && bounds != null) {
            anchorSec = TimeWindowPolicy.defaultAnchorSec(histogram, nowSec, minSec, maxSec)
        }
    }
    val anchorSecClamped = anchorSec.coerceIn(minSec, maxSec)

    // 横截面查询（防抖 200ms：拖拽中只更新标签，停顿才查——索引毫秒级+IO 派发
    // 合计仍远快于一帧动画，但网络封面的墙格加载留给格子自己的 produceState）
    var querying by remember { mutableStateOf(false) }
    var wall by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var stats by remember { mutableStateOf<TimeWindowPolicy.CrossSectionStats?>(null) }
    var queriedOnce by remember(intent) { mutableStateOf(false) }
    LaunchedEffect(anchorSecClamped, halfWidthSec, indexReady, minSec, maxSec) {
        if (!indexReady) return@LaunchedEffect
        delay(200)
        querying = true
        val (from, to) = TimeWindowPolicy.windowOf(anchorSecClamped, halfWidthSec)
        val raw = withContext(Dispatchers.IO) {
            vm.loadTimeWindowItems(
                DeepSearchIndex.TimeWindowQuery(
                    fromSec = from,
                    toSec = to,
                    channelIds = null,
                    limit = WALL_QUERY_LIMIT,
                ),
            )
        }
        wall = TimeWindowPolicy.refineCrossSection(raw, perChannelCap = 3, limit = WALL_QUERY_LIMIT)
        stats = TimeWindowPolicy.statsOf(raw, wall)
        querying = false
        queriedOnce = true
    }

    val presets = when (intent) {
        TimeMachineIntent.Now -> TimeWindowPolicy.NOW_PRESETS
        is TimeMachineIntent.Event -> TimeWindowPolicy.EVENT_PRESETS
        is TimeMachineIntent.Free -> TimeWindowPolicy.FREE_PRESETS
    }
    val title = when (intent) {
        TimeMachineIntent.Now -> "此刻横截面"
        // R215 ④：长按面板格已更名「看同期」，页标题同语言（同一流程的用户面文案）
        is TimeMachineIntent.Event -> "看同期"
        is TimeMachineIntent.Free -> "全网时光机"
    }
    val windowLabel = remember(anchorSecClamped, title) {
        "$title · ${TimeWindowPolicy.formatAnchorDate(anchorSecClamped)}"
    }

    val densities = remember(histogram, minSec, maxSec) {
        TimeWindowPolicy.densities(histogram, minSec, maxSec, DENSITY_BUCKETS)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(TgFeedTokens.InkBlack),
    ) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = title,
                onBack = onBack,
                actions = {
                    val s = stats
                    if (s != null && s.total > 0) {
                        Text("${s.total} 条", style = MaterialTheme.typography.labelMedium, color = TgFeedTokens.TextLow)
                    }
                },
            )

            // ---- 事件考古：锚点事件上下文（被考古的那条） ----
            if (intent is TimeMachineIntent.Event) {
                Text(
                    text = intent.item.caption.lineSequence().firstOrNull()?.take(60)
                        ?: intent.item.channelTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TgFeedTokens.TextMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapXs),
                )
            }

            // ---- 锚点大标签 + 统计行 ----
            Text(
                text = TimeWindowPolicy.formatAnchorDateTime(anchorSecClamped),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = TgFeedTokens.TextHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapXs),
            )
            stats?.let { s ->
                Text(
                    text = "${s.total} 条 · ${s.channels} 个频道 · 同片折叠 ${s.repostsFolded} · 广告剔除 ${s.adsFolded}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapXs),
                )
            }
            if (intent is TimeMachineIntent.Now) {
                // DS-1 诚实口径：索引新鲜度=头部刷新节流的量级（1h/6h 档）
                Text(
                    text = "新鲜度取决于后台索引节奏（约 1~6 小时一刷）",
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapXs),
                )
            }

            // ---- 窗宽预设 chips ----
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapXs),
                horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
            ) {
                for (p in presets) {
                    AppPill(
                        text = p.label,
                        selected = halfWidthSec == p.halfWidthSec,
                        onClick = { halfWidthSec = p.halfWidthSec },
                    )
                }
            }

            // ---- 时间滑块（密度刻度 + 年刻度 + 拖/点跳锚点） ----
            TimelineSlider(
                minSec = minSec,
                maxSec = maxSec,
                anchorSec = anchorSecClamped,
                densities = densities,
                onAnchorChange = { sec ->
                    anchorSec = sec
                    anchorTouched = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TgFeedTokens.GapLg),
            )

            // ---- 主体：构建中 / 查询中 / 空窗 / 封面墙 ----
            if (!indexReady) {
                AppEmptyState(
                    icon = Icons.Outlined.Schedule,
                    title = "时间档案整理中",
                    subtitle = "首次升级在后台建时间索引（几十万到几百万条，几分钟）——完成自动刷新。期间可以先把玩滑块。",
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            } else if (querying && wall.isEmpty()) {
                AppLoading(
                    text = "正在切到 ${TimeWindowPolicy.formatAnchorDateTime(anchorSecClamped)}",
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            } else if (wall.isEmpty() && queriedOnce) {
                AppEmptyState(
                    icon = Icons.Outlined.History,
                    title = "这个时刻的全网很安静",
                    subtitle = "试试更大的窗宽，或拖滑块换年代——怪兽频道早期按月采样覆盖，老时刻偏稀是采样口径不是没货。",
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            } else {
                val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                LazyVerticalGrid(
                    // R171 口径：列数随实测宽涌现（与频道主页墙/收藏墙同款）
                    columns = GridCells.Adaptive(MediaWallCellMin),
                    state = gridState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                    contentPadding = PaddingValues(
                        top = TgFeedTokens.GapXs,
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
                        TimeWallCell(
                            item = item,
                            gateway = gateway,
                            onPlay = { onPlay(wall, windowLabel, item) },
                        )
                    }
                }
            }
        }
    }
}

/** 墙格数据量上限（查询与精炼同值：墙显什么，滑进去刷的就是什么） */
private const val WALL_QUERY_LIMIT = 240

/** 滑杆密度分桶数（像素级精细度足够：120 桶在 400dp 宽≈3dp/桶） */
private const val DENSITY_BUCKETS = 120

// ==================== 时间滑块（私有组件） ====================

/**
 * 时间滑块：密度墙（哪里有货哪里亮）+ 轨道 + 年刻度 + 锚点拇指。
 * 交互=**单个 pointerInput**（纪律§四「同节点双 pointerInput=互杀」禁例的规避）：
 * detectDragGestures 一个 handler 全包——按下即跳锚点（onDragStart，点按=零位移
 * 拖动，天然覆盖 tap-to-seek）+ 拖拽跟手（锚点大标签实时随动；查询由上层 200ms
 * 防抖收口）。
 */
@Composable
private fun TimelineSlider(
    minSec: Long,
    maxSec: Long,
    anchorSec: Long,
    densities: List<Float>,
    onAnchorChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ticks = remember(minSec, maxSec) { TimeWindowPolicy.yearTicks(minSec, maxSec) }
    Canvas(
        modifier
            .height(64.dp)
            .pointerInput(minSec, maxSec) {
                detectDragGestures(
                    // 按下即跳（点按=零位移拖动；scrubber 语义：落指处即锚点）
                    onDragStart = { ofs ->
                        val f = (ofs.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onAnchorChange(TimeWindowPolicy.anchorFromFraction(f, minSec, maxSec))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val f = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onAnchorChange(TimeWindowPolicy.anchorFromFraction(f, minSec, maxSec))
                    },
                )
            },
    ) {
        val w = size.width
        val densityTop = 4.dp.toPx()
        val densityBottom = 28.dp.toPx()
        val trackY = 34.dp.toPx()
        val tickBottom = 40.dp.toPx()
        // 密度墙（刻度糖：归一亮度，直方图未到时全空=均匀轨照样可拖）
        if (densities.isNotEmpty()) {
            val slot = w / densities.size
            val barW = 2.dp.toPx().coerceAtMost(slot * 0.7f)
            for ((i, d) in densities.withIndex()) {
                if (d <= 0f) continue
                val h = ((densityBottom - densityTop) * d).coerceAtLeast(1f)
                drawRect(
                    color = TgFeedTokens.TextHigh.copy(alpha = 0.10f + 0.28f * d),
                    topLeft = Offset(slot * i + (slot - barW) / 2f, densityBottom - h),
                    size = Size(barW, h),
                )
            }
        }
        // 轨道
        drawLine(
            color = TgFeedTokens.TextHigh.copy(alpha = 0.25f),
            start = Offset(0f, trackY),
            end = Offset(w, trackY),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        // 年刻度（native 文本：Compose Canvas 画原生 Paint 最省一层组件）
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
        // 锚点拇指（AccentRed 主点 + 白芯，滑杆家族面孔）
        val cx = w * TimeWindowPolicy.fractionFromAnchor(anchorSec, minSec, maxSec)
        drawCircle(color = TgFeedTokens.AccentRed, radius = 7.dp.toPx(), center = Offset(cx, trackY))
        drawCircle(color = TgFeedTokens.TextHigh, radius = 2.5f.dp.toPx(), center = Offset(cx, trackY))
    }
}

// ==================== 封面墙格子（私有组件） ====================

/**
 * 横截面墙格：3:4 封面 + 类型角标（底左）+ 频道名 scrim（顶横——全网横截面的
 * 「谁在发」信息位）。封面链与收藏墙同族（CoverStore 抽帧 → coverBytes →
 * loadCoverBytes/loadAudioCoverBytes 高清 → loadCoverBytesByMessage 消息反解
 * 兜底 → 图片原图采样解码）——索引条目 coverBytes 恒 null，实际走高清/反解两档。
 */
@Composable
private fun TimeWallCell(
    item: VideoItem,
    gateway: TdGateway,
    onPlay: () -> Unit,
) {
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
            // 无封面占位：类型字（收藏墙同款兜底）
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
        // 频道名 scrim（顶横渐隐——横截面的「谁在发」）
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
            Text(
                text = item.channelTitle,
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 3.dp),
            )
        }
        // 类型角标（底左，收藏墙同款口径）
        val badge = when (item.mediaType) {
            0 -> formatDuration(item.durationSec)
            1 -> if (item.albumId != 0L) "图集" else "图"
            else -> "♪"
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
