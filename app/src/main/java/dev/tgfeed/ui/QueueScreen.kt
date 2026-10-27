package dev.tgfeed.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.feed.FeedViewModelFactory
import dev.tgfeed.core.feed.PlayQueueEntry
import dev.tgfeed.core.feed.PlayQueueOrder
import dev.tgfeed.core.feed.titleLine
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.ui.components.kit.AppDialog
import dev.tgfeed.ui.components.kit.AppEmptyState
import dev.tgfeed.ui.components.kit.AppIconButton
import dev.tgfeed.ui.components.kit.AppPill
import dev.tgfeed.ui.components.kit.AppTopBar
import dev.tgfeed.ui.components.kit.adaptiveContentWidth
import dev.tgfeed.ui.theme.TgFeedTokens

/**
 * 播放队列（节目单）管理页：R387 一期四件的编辑面（r387_notes §3.2 UI 母版
 * =PlaylistsScreen 骨架：AppTopBar/AppEmptyState/LazyColumn/adaptiveContentWidth）。
 *
 * - 顶栏双动作：播放（断点续播开单）/ 从头播（清断点）——空单只提示不切源；
 * - 排序 chips 行：四档 AppPill（R371 搜索 chips 同族视觉，选中=AccentRed 内建）；
 * - 条目行：拖拽手柄（长按整行起拖换序）+ 类型图标（频道=LiveTv / 单条=Movie）
 *   + 标题 + 行尾跳过开关/置顶/移除；skipped 行降透明度（播放期排除的视觉直读）；
 * - 底部动作行：加频道（已加入频道多选弹层，vm.channels 数据源——
 *   CollectionsDialog 同源）+ 稍后再看入单（watchlater 全量入单桥，一键）
 *   + 清空（AppDialog 二次确认）；
 * - 空态 AppEmptyState（「长按视频→加入队列，或把频道排进单」）。
 *
 * 撖放中编辑队列不改在播流（快照语义——下次开单生效，r387_notes §3.1）。
 * 与 FeedScreen 共享 activity 作用域同一个 ViewModel（队列数据同源，必带网关 key）。
 *
 * R397 批量入分屏（六入口之五·队列编辑面，私有线件）：底部动作行「分屏播放
 * N 条」钮——活跃台内**点名单条**（非跳过 Item 条目）整批种进分屏片单并直接
 * 开分屏（统一机制 App.addBatchToGrid）。频道条目不入格判词见
 * QueueStationsScreen 文件头（整台连播语义归一期开台链）；N=0 钮不出现。
 *
 * R398 #10 队列语义跳转（私有线件）：排序 chips 行下「跳到讲…」搜索条——
 * 本地 caption 匹配（零网络）+「跳播」钮=段前缀剪除开单+断点跳回落点
 * （跳到=从这继续听）；无果 Toast 如实（纯频道单恒无果）。编辑面其余
 * 零改（R397/R392/R391/R387 既有行零碰）。
 */
@Composable
fun QueueScreen(
    gateway: TdGateway,
    onBack: () -> Unit,
    // R397 批量入分屏：宿主（MainActivity）收口——种进容器片单 + 剥落本页 + 开分屏
    onBatchToGrid: (List<VideoItem>) -> Unit = {},
    /** R414 开播后落点（宿主收口：剥落编辑页/台列表/我的页回 Feed）；缺省=onBack */
    onPlayStarted: (() -> Unit)? = null,
) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val context = LocalContext.current
    // 系统返回键 = 返回上一页（状态驱动路由，不拦会退到桌面；从我的页进来即回我的页）
    BackHandler(onBack = onBack)
    // 与 FeedScreen 共享 activity 作用域的同一个 ViewModel（队列数据同源；不带 key
    // 会拿到另一个实例——CollectionsDialog「点了等于没点」同坑）
    val vm: FeedViewModel = viewModel(
        factory = FeedViewModelFactory(gateway, app),
        key = "gw" + System.identityHashCode(gateway),
    )
    val queue by vm.playQueue.collectAsState()
    val channels by vm.channels.collectAsState()
    val entries = queue.entries

    // 拖拽换序状态（长按起拖→跟手位移→越一行即换位；无自动滚屏——一期条目少，
    // 拖出可视区松手即落位=诚实口径）。itemCount 直读 StateFlow 活值——闭包捕获
    // 本地列表会在增删后拿到陈旧计数（首帧 val 快照坑）
    val dragState = remember {
        QueueDragState(
            onMove = { from, to -> vm.movePlayQueueEntry(from, to) },
            itemCount = { vm.playQueue.value.entries.size },
        )
    }

    // 加频道弹层显隐 + 多选草稿；清空二次确认
    var showAddChannels by remember { mutableStateOf(false) }
    var selectedChannelIds by remember { mutableStateOf(setOf<Long>()) }
    var showClearConfirm by remember { mutableStateOf(false) }

    /** 开单：空单只提示；否则切播放队列源并回上一页（Feed）连播（playAll 同款） */
    fun playQueue(fromStart: Boolean) {
        if (entries.isEmpty()) {
            Toast.makeText(context, "队列是空的，先加入频道或视频", Toast.LENGTH_SHORT).show()
            return
        }
        vm.openPlayQueue(fromStart)
        (onPlayStarted ?: onBack)()
    }

    // 大屏：内容列按 adaptiveContentWidth 三档限宽居中（与设置/下载/片单页同一规格）；整页纯黑沉浸底
    Box(
        Modifier
            .fillMaxSize()
            .background(TgFeedTokens.InkBlack),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                // 自适应内容列宽必须在前：fillMaxSize 会先把约束钉成全宽，其后的限宽是死代码（P1-6 教训）
                .adaptiveContentWidth()
                .fillMaxSize(),
        ) {
            // 顶栏：返回圆钮 + 标题（R391=活跃台名，一期硬编码「播放队列」升级为
            // 台名置题——与开台 feed 顶栏 sourceTitle=queue.name 同源）+ 双动作
            //（播放=断点续播开单 / 从头播=清断点）
            AppTopBar(
                title = queue.name,
                onBack = onBack,
                actions = {
                    AppIconButton(
                        icon = Icons.Outlined.RestartAlt,
                        contentDescription = "从头播",
                        onClick = { playQueue(fromStart = true) },
                    )
                    AppIconButton(
                        icon = Icons.Outlined.PlayArrow,
                        contentDescription = "播放",
                        onClick = { playQueue(fromStart = false) },
                    )
                },
            )

            // 排序档 chips：四档单选（档位随 play_queue.json 落盘跨会话记忆；
            // 切档只改档位——在播流是开单快照，重开单生效）
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TgFeedTokens.GapLg)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
            ) {
                PlayQueueOrder.values().forEach { mode ->
                    AppPill(
                        text = mode.label,
                        selected = queue.orderMode == mode,
                        onClick = { vm.setPlayQueueOrder(mode) },
                    )
                }
            }
            Spacer(Modifier.height(TgFeedTokens.GapSm))

            // R398 #10 语义跳转搜索条（排序 chips 行下）：「跳到讲…」——本地
            // caption 包含匹配（大小写不敏感零网络，纯函数即时返回）；跳播=
            // 段前缀剪除开单+断点跳回落点（跳到=从这继续听的电台语义）；
            // 无果 Toast 如实（纯频道单=恒无果判词同诚实在——频道段内容要
            // 现拉，零网络承诺下无从匹配）。空单不显示（无可跳）。
            if (entries.isNotEmpty()) {
                var jumpQuery by remember { mutableStateOf("") }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = TgFeedTokens.GapLg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                ) {
                    OutlinedTextField(
                        value = jumpQuery,
                        onValueChange = { jumpQuery = it },
                        placeholder = { Text("跳到讲…", color = TgFeedTokens.TextLow) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            val q = jumpQuery.trim()
                            if (q.isEmpty()) {
                                Toast.makeText(context, "先输入要跳到的话题", Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            if (vm.jumpToQueueSemantic(q)) {
                                (onPlayStarted ?: onBack)()
                            } else {
                                Toast.makeText(context, "没找到讲「$q」的条目", Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = null,
                            tint = TgFeedTokens.AccentRed,
                        )
                        Spacer(Modifier.width(TgFeedTokens.GapXs))
                        Text("跳播", color = TgFeedTokens.AccentRed)
                    }
                }
                Spacer(Modifier.height(TgFeedTokens.GapSm))
            }

            if (entries.isEmpty()) {
                // 空态：kit AppEmptyState 统一壳（大图标 + 主文案 + 引导副文案）
                AppEmptyState(
                    icon = Icons.Outlined.QueueMusic,
                    title = "播放队列还是空的",
                    subtitle = "长按视频 → 加入队列，或把频道排进单",
                    modifier = Modifier.weight(1f),
                )
            } else {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = TgFeedTokens.GapLg),
                ) {
                    itemsIndexed(entries, key = { _, e -> e.id }) { index, entry ->
                        QueueEntryRow(
                            entry = entry,
                            dragging = dragState.draggingIndex == index,
                            dragOffset = if (dragState.draggingIndex == index) dragState.dragOffset else 0f,
                            onDragStart = { heightPx ->
                                dragState.itemSizePx = heightPx.toFloat()
                                dragState.start(index)
                            },
                            onDrag = { dy -> dragState.drag(dy) },
                            onDragEnd = { dragState.end() },
                            onToggleSkip = { vm.togglePlayQueueSkip(entry.id) },
                            onMoveToTop = { vm.movePlayQueueEntryToTop(entry.id) },
                            onRemove = { vm.removeFromPlayQueue(entry.id) },
                        )
                        Spacer(Modifier.height(TgFeedTokens.GapMd))
                    }
                }
            }

            // 底部动作行：加频道 / 稍后再看入单 / 清空
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapSm),
                horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
            ) {
                TextButton(
                    onClick = {
                        selectedChannelIds = emptySet()
                        showAddChannels = true
                    },
                ) {
                    Icon(
                        Icons.Outlined.PlaylistAdd,
                        contentDescription = null,
                        tint = TgFeedTokens.TextMedium,
                    )
                    Spacer(Modifier.width(TgFeedTokens.GapXs))
                    Text("加频道", color = TgFeedTokens.TextHigh)
                }
                TextButton(onClick = {
                    vm.enqueueWatchLaterToPlayQueue()
                    Toast.makeText(context, "稍后再看开始入单（已在单的会跳过）", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(
                        Icons.Outlined.PlaylistPlay,
                        contentDescription = null,
                        tint = TgFeedTokens.TextMedium,
                    )
                    Spacer(Modifier.width(TgFeedTokens.GapXs))
                    Text("稍后再看入单", color = TgFeedTokens.TextHigh)
                }
                // R397 批量入分屏：活跃台内非跳过单条条目整批倒进分屏——N=实数；
                // 纯频道单（无单条条目）不出现（诚实缺席，频道段走开台链）
                val seedable = entries
                    .filterIsInstance<PlayQueueEntry.Item>()
                    .filter { !it.skipped }
                if (seedable.isNotEmpty()) {
                    TextButton(onClick = { onBatchToGrid(seedable.map { it.item }) }) {
                        Icon(
                            Icons.Outlined.GridView,
                            contentDescription = null,
                            tint = TgFeedTokens.AccentRed,
                        )
                        Spacer(Modifier.width(TgFeedTokens.GapXs))
                        Text("分屏播放 ${seedable.size} 条", color = TgFeedTokens.AccentRed)
                    }
                }
                if (entries.isNotEmpty()) {
                    TextButton(onClick = { showClearConfirm = true }) {
                        Text("清空", color = TgFeedTokens.WarningRed)
                    }
                }
            }
        }
    }

    // 加频道弹层（已加入频道多选；vm.channels 数据源=CollectionsDialog 同源，
    // 为空时先 loadChannels）。已入单的台勾选后走幂等（模型同 chatId 忽略），
    // Toast 只报新增数。
    if (showAddChannels) {
        // 频道列表为空时（如刚进页面还没拉过）先拉一次
        LaunchedEffect(channels.isEmpty()) {
            if (channels.isEmpty()) vm.loadChannels()
        }
        AppDialog(
            onDismissRequest = { showAddChannels = false },
            title = "把频道排进单",
            confirmText = "加入",
            onConfirm = {
                var added = 0
                for (c in channels) {
                    if (c.id in selectedChannelIds && vm.enqueueChannelToPlayQueue(c.id, c.title)) added++
                }
                showAddChannels = false
                Toast.makeText(
                    context,
                    if (added == 0) "所选频道都已在单里" else "已加入 ${added} 台",
                    Toast.LENGTH_SHORT,
                ).show()
            },
            dismissText = "取消",
            content = {
                if (channels.isEmpty()) {
                    Text(
                        "正在拉取频道清单…",
                        color = TgFeedTokens.TextMedium,
                        modifier = Modifier.padding(vertical = TgFeedTokens.GapLg),
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        itemsIndexed(channels, key = { _, c -> c.id }) { _, c ->
                            QueueChannelCheckRow(
                                title = c.title,
                                checked = c.id in selectedChannelIds,
                                onToggle = {
                                    selectedChannelIds =
                                        if (c.id in selectedChannelIds) selectedChannelIds - c.id
                                        else selectedChannelIds + c.id
                                },
                            )
                        }
                    }
                }
            },
        )
    }

    // 清空二次确认（删除语义=用户显式确认后才执行）
    if (showClearConfirm) {
        AppDialog(
            onDismissRequest = { showClearConfirm = false },
            title = "清空播放队列",
            confirmText = "清空",
            onConfirm = {
                vm.clearPlayQueue()
                showClearConfirm = false
                Toast.makeText(context, "队列已清空", Toast.LENGTH_SHORT).show()
            },
            dismissText = "取消",
            content = {
                Text(
                    "单内 ${entries.size} 条将全部移除（断点一并清账）",
                    color = TgFeedTokens.TextMedium,
                )
            },
        )
    }
}

/**
 * 拖拽换序状态机（自含实现，无三方依赖）：长按起拖 → 跟手位移累计 →
 * 越一行高度即与邻行换位（vm.movePlayQueueEntry → 列表重排 → 本行 key 跟随
 * 到新位）→ 松手复位。位移补偿=换位后 dragOffset 减一行高度，行视觉上
 * 留在手指下（经典 LazyColumn 换序口径）。
 */
private class QueueDragState(
    private val onMove: (Int, Int) -> Unit,
    private val itemCount: () -> Int,
) {
    /** 正在拖拽的条目下标（-1=无） */
    var draggingIndex by mutableStateOf(-1)
        private set

    /** 拖拽跟手位移（px；换位时按行高补偿） */
    var dragOffset by mutableStateOf(0f)
        private set

    /** 拖拽行高（px；起拖时由行 onSizeChanged 测得） */
    var itemSizePx = 0f

    fun start(index: Int) {
        draggingIndex = index
        dragOffset = 0f
    }

    fun drag(deltaY: Float) {
        if (draggingIndex < 0) return
        dragOffset += deltaY
        val size = itemSizePx
        if (size <= 0f) return
        // 上移：累计位移越一行且未到顶 → 与上一行换位
        while (dragOffset <= -size && draggingIndex > 0) {
            onMove(draggingIndex, draggingIndex - 1)
            draggingIndex -= 1
            dragOffset += size
        }
        // 下移：累计位移越一行且未到底 → 与下一行换位
        while (dragOffset >= size && draggingIndex < itemCount() - 1) {
            onMove(draggingIndex, draggingIndex + 1)
            draggingIndex += 1
            dragOffset -= size
        }
    }

    /** 松手/打断：复位（行落回列表位=数据位即视觉位，无需动画兜底） */
    fun end() {
        draggingIndex = -1
        dragOffset = 0f
    }
}

/**
 * 队列条目行：拖拽手柄 + 类型图标 + 标题/副题 + 行尾跳过开关/置顶/移除。
 * 拖拽=整行长按起拖（detectDragGesturesAfterLongPress，Grid 系手势先例）；
 * dragging 行抬 zIndex + 跟手 translationY + OverlayBg 底；skipped 行降透明度。
 */
@Composable
private fun QueueEntryRow(
    entry: PlayQueueEntry,
    dragging: Boolean,
    dragOffset: Float,
    onDragStart: (heightPx: Int) -> Unit,
    onDrag: (deltaY: Float) -> Unit,
    onDragEnd: () -> Unit,
    onToggleSkip: () -> Unit,
    onMoveToTop: () -> Unit,
    onRemove: () -> Unit,
) {
    var rowHeight by remember(entry.id) { mutableStateOf(0) }
    Row(
        Modifier
            .fillMaxWidth()
            // dragging 行抬升视觉：shadow 高程（Compose 1.7.2 无 Modifier.zIndex——
            // 本机构件字节级实证零命中；绘制序=布局序，跟手平移叠邻行时以投影分层）
            .shadow(
                elevation = if (dragging) 8.dp else 0.dp,
                shape = RoundedCornerShape(TgFeedTokens.RadiusLg),
            )
            .onSizeChanged { rowHeight = it.height }
            .graphicsLayer {
                if (dragging) {
                    translationY = dragOffset
                }
            }
            .alpha(if (entry.skipped) 0.45f else 1f)
            .clip(RoundedCornerShape(TgFeedTokens.RadiusLg))
            .background(if (dragging) TgFeedTokens.OverlayBg else TgFeedTokens.SurfaceHigh)
            .pointerInput(entry.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart(rowHeight) },
                    onDrag = { change, amount ->
                        change.consume()
                        onDrag(amount.y)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                )
            }
            .padding(horizontal = TgFeedTokens.GapMd, vertical = TgFeedTokens.GapSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 拖拽手柄（视觉示意=整行都是拖拽热区）
        Icon(
            Icons.Outlined.DragHandle,
            contentDescription = "拖拽换序",
            tint = TgFeedTokens.TextLow,
        )
        Spacer(Modifier.width(TgFeedTokens.GapSm))
        // 类型图标：频道台级=LiveTv / 单条=Movie
        Icon(
            if (entry is PlayQueueEntry.Channel) Icons.Outlined.LiveTv else Icons.Outlined.Movie,
            contentDescription = if (entry is PlayQueueEntry.Channel) "频道" else "单条",
            tint = TgFeedTokens.TextMedium,
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = TgFeedTokens.GapSm),
        ) {
            val title = when (entry) {
                is PlayQueueEntry.Channel -> entry.title
                is PlayQueueEntry.Item -> entry.item.titleLine().ifBlank { "（无标题）" }
            }
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = TgFeedTokens.TextHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(TgFeedTokens.GapXs))
            val subtitle = when (entry) {
                is PlayQueueEntry.Channel -> "频道 · 整台按序播"
                is PlayQueueEntry.Item -> "单条 · 稍后再看入单"
            }
            Text(
                subtitle + if (entry.skipped) " · 已跳过" else "",
                style = MaterialTheme.typography.bodySmall,
                color = TgFeedTokens.TextLow,
                maxLines = 1,
            )
        }
        // 行尾三动作：跳过开关（勾=播放期排除可恢复）/ 置顶 / 移除
        Checkbox(
            checked = entry.skipped,
            onCheckedChange = { onToggleSkip() },
            colors = CheckboxDefaults.colors(
                checkedColor = TgFeedTokens.AccentRed,
                checkmarkColor = TgFeedTokens.TextHigh,
                uncheckedColor = TgFeedTokens.TextLow,
            ),
        )
        IconButton(onClick = onMoveToTop) {
            Icon(
                Icons.Outlined.VerticalAlignTop,
                contentDescription = "置顶",
                tint = TgFeedTokens.TextMedium,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "移除",
                tint = TgFeedTokens.TextMedium,
            )
        }
    }
}

/** 频道多选行：checkbox + 频道名（ChannelCheckRow 同款视觉，本页内聚） */
@Composable
private fun QueueChannelCheckRow(
    title: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = TgFeedTokens.AccentRed,
                checkmarkColor = TgFeedTokens.TextHigh,
                uncheckedColor = TgFeedTokens.TextLow,
            ),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = TgFeedTokens.TextHigh,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
