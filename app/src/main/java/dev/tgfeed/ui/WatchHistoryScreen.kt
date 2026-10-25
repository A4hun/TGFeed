package dev.tgfeed.ui

import android.app.Application
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.feed.FeedViewModelFactory
import dev.tgfeed.core.store.LocalStore
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.ui.components.CoverStore
import dev.tgfeed.ui.components.formatDuration
import dev.tgfeed.ui.components.kit.AppDialog
import dev.tgfeed.ui.components.kit.AppEmptyState
import dev.tgfeed.ui.components.kit.AppLoading
import dev.tgfeed.ui.components.kit.AppTopBar
import dev.tgfeed.ui.components.kit.adaptiveContentWidth
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 观看历史页（抖音/B站"观看历史"形态）：
 * - 数据源：LocalStore history.json（G4 起存完整 VideoItem，重启后可还原）。
 *   分组按"观看时刻"（HistoryEntry.ts）分今天/昨天/本周/更早——不按条目发布时间
 *   dateEpochSec（老视频今天看应归"今天"，否则分组对用户无意义）；
 * - 每行：封面缩略图（右下时长角标 + 底部观看进度条）+ caption 一行 + 频道名·时长
 *   + "观看到 x:xx / 已看完"（progress 来自 watch_progress.json，VideoPage 起播即按它续播）；
 * - 点击：回 Feed 并定位到该条继续播（跳转编排在 MainActivity：切历史源 + 轮询进列表
 *   + requestFeedJump，与下载页/搜索页 onPlay 同一手法）；
 * - 长按：AppDialog 确认后删除单条；顶栏右侧"清空"：AppDialog 二次确认后清空全部。
 *
 * 一致性说明（重要）：history.json 的全局写入方是 FeedViewModel 持有的 LocalStore 实例
 *（其 historyCache 已预热）。本页所有"写"操作都走共享 VM 的公共 API（删除单条
 * vm.removeHistory / 清空 vm.clearHistory），保持单写入方语义——若经屏侧自建实例
 * 落盘，VM 侧缓存不感知，其后续 appendHistory 会把旧缓存整体重写回盘（删除复活）。
 * 屏侧自建 store 实例只用于"读"（列表/进度加载，GridScreen 已有同款先例）；
 * 写后屏侧实例缓存需同步清（见清空分支），否则本页重进读到旧缓存。
 */
@Composable
fun WatchHistoryScreen(
    gateway: TdGateway,
    onBack: () -> Unit,
    onPlay: (VideoItem) -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    // 系统返回键 = 返回上一页（状态驱动路由，不在返回栈里，不拦会退到桌面）
    BackHandler(onBack = onBack)

    // 与 FeedScreen 共享 activity 作用域的同一个 ViewModel：本页所有"写"操作
    //（删除单条 / 清空）都走它的公共 API，保持 history.json 单写入方语义；
    // 屏侧自建 store 实例只承担"读"（GridScreen 已有同款先例）
    val vm: FeedViewModel = viewModel(
        factory = FeedViewModelFactory(gateway, app),
        key = "gw" + System.identityHashCode(gateway),
    )
    val store = remember { LocalStore(app) }
    val scope = rememberCoroutineScope()

    // null = 加载中；非 null 即已加载（可能为空列表）
    var rows by remember { mutableStateOf<List<HistoryRow>?>(null) }
    // 待确认删除的单条 / 是否显示清空确认框
    var pendingDelete by remember { mutableStateOf<HistoryRow?>(null) }
    var showClearAll by remember { mutableStateOf(false) }

    // 进页加载一次：历史条目（倒序）+ 观看时刻表（key→ts）+ 续播进度表（key→posMs）
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val items = store.loadHistoryItems()
                val tsMap = store.loadHistory().toMap()
                val progress = store.loadProgressMap()
                items.map { HistoryRow(it, tsMap[it.key] ?: 0L, progress[it.key] ?: 0L) }
            }.getOrDefault(emptyList())
        }
        rows = loaded
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(TgFeedTokens.InkBlack),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                // 自适应内容列宽必须在前：fillMaxSize 会先把约束钉成全宽，其后的限宽是死代码（P1-6 教训。
                // 本页此前正是 fillMaxSize().widthIn() 反序，平板上实际全宽——此次一并纠正）
                .adaptiveContentWidth()
                .fillMaxSize(),
        ) {
            // 统一二级页顶栏（内建 statusBarsPadding）；右侧"清空"仅在非空时出现
            AppTopBar(
                title = "观看历史",
                onBack = onBack,
                actions = {
                    if (!rows.isNullOrEmpty()) {
                        Text(
                            "清空",
                            style = MaterialTheme.typography.labelLarge,
                            color = TgFeedTokens.TextMedium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
                                .clickable { showClearAll = true }
                                .padding(
                                    horizontal = TgFeedTokens.GapMd,
                                    vertical = TgFeedTokens.GapSm,
                                ),
                        )
                    }
                },
            )

            val list = rows
            when {
                // 加载中
                list == null -> AppLoading(
                    text = "正在加载观看历史…",
                    modifier = Modifier.weight(1f),
                )
                // 空态
                list.isEmpty() -> AppEmptyState(
                    icon = Icons.Outlined.History,
                    title = "还没有观看历史",
                    subtitle = "刷到的内容会自动记录在这里",
                    modifier = Modifier.weight(1f),
                )
                else -> {
                    // 分组平铺：组头 + 条目（列表本身已是观看时间倒序，遇组界插一个组头）
                    val flat = remember(list) { buildHistorySections(list) }
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(
                            start = TgFeedTokens.GapLg,
                            end = TgFeedTokens.GapLg,
                            bottom = TgFeedTokens.GapXl,
                        ),
                    ) {
                        items(
                            items = flat,
                            key = {
                                when (it) {
                                    is HistoryListRow.Header -> "header_${it.title}"
                                    is HistoryListRow.Entry -> it.row.item.key
                                }
                            },
                            contentType = {
                                when (it) {
                                    is HistoryListRow.Header -> "header"
                                    is HistoryListRow.Entry -> "entry"
                                }
                            },
                        ) { row ->
                            when (row) {
                                is HistoryListRow.Header -> HistoryGroupHeader(row.title)
                                is HistoryListRow.Entry -> HistoryEntryRow(
                                    row = row.row,
                                    gateway = gateway,
                                    onClick = { onPlay(row.row.item) },
                                    onLongClick = { pendingDelete = row.row },
                                )
                            }
                        }
                    }
                }
            }

            // 底部让开系统导航条
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    // 删除单条确认（长按触发）
    pendingDelete?.let { target ->
        AppDialog(
            onDismissRequest = { pendingDelete = null },
            title = "删除这条观看记录？",
            confirmText = "删除",
            onConfirm = {
                pendingDelete = null
                rows = rows?.filterNot { it.item.key == target.item.key }
                // 走共享 VM 的公共 API（单写入方语义，见文件头"一致性说明"）；
                // 屏侧实例是只读角色，无需同步——重进页面时从磁盘重读即得新值
                vm.removeHistory(target.item.key)
            },
            dismissText = "取消",
            content = {
                Text(
                    text = target.item.caption.lineSequence().firstOrNull()
                        ?.takeIf { it.isNotBlank() } ?: target.item.channelTitle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
    }

    // 清空全部确认（顶栏"清空"触发）：走 vm.clearHistory() 保持单写入方语义，
    // 屏侧自建实例的缓存同步清掉（否则本页重进读到旧缓存）
    if (showClearAll) {
        AppDialog(
            onDismissRequest = { showClearAll = false },
            title = "清空观看历史？",
            confirmText = "清空",
            onConfirm = {
                showClearAll = false
                rows = emptyList()
                vm.clearHistory()
                scope.launch(Dispatchers.IO) {
                    runCatching { store.clearHistory() }
                }
            },
            dismissText = "取消",
            content = {
                Text("共 ${rows?.size ?: 0} 条记录，清空后不可恢复")
            },
        )
    }
}

// ==================== 以下为本页私有组件与模型 ====================

/** 列表行模型：条目本体 + 观看时刻（毫秒）+ 已播位置（毫秒，0=无记录） */
private data class HistoryRow(
    val item: VideoItem,
    val watchedAtMs: Long,
    val progressMs: Long,
)

/** 分组平铺后的列表元素：组头 或 条目 */
private sealed interface HistoryListRow {
    data class Header(val title: String) : HistoryListRow
    data class Entry(val row: HistoryRow) : HistoryListRow
}

/** 组头：今天/昨天/本周/更早（小节标题档 TextLow + labelMedium） */
@Composable
private fun HistoryGroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = TgFeedTokens.TextLow,
        modifier = Modifier.padding(
            top = TgFeedTokens.GapLg,
            bottom = TgFeedTokens.GapSm,
        ),
    )
}

/**
 * 历史条目行：封面缩略图（124×70，右下时长角标 + 底部进度条）
 * + 右侧 caption 一行 / 频道名·时长 / "观看到 x:xx"。
 * 单击播放（回 Feed 定位续播），长按弹删除确认。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryEntryRow(
    row: HistoryRow,
    gateway: TdGateway,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val item = row.item
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = TgFeedTokens.GapSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HistoryCoverThumb(item = item, gateway = gateway, row = row)
        Spacer(Modifier.width(TgFeedTokens.GapMd))
        Column(Modifier.weight(1f)) {
            // caption 一行（空则回落频道名）
            Text(
                text = item.caption.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
                    ?: item.channelTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = TgFeedTokens.TextHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(TgFeedTokens.GapXs))
            // 频道名 · 时长/类型
            Text(
                text = buildString {
                    append(item.channelTitle)
                    when {
                        item.mediaType == 1 -> append(" · 图文")
                        item.durationSec > 0 -> append(" · ${formatDuration(item.durationSec)}")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = TgFeedTokens.TextLow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 观看进度行：有进度显示"观看到 x:xx"，基本看完显示"已看完"，无记录不显示
            progressLabel(row)?.let { label ->
                Spacer(Modifier.height(TgFeedTokens.GapXs))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextMedium,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 观看进度文案：null = 不显示进度行 */
private fun progressLabel(row: HistoryRow): String? {
    val item = row.item
    if (item.mediaType == 1 || row.progressMs <= 0L) return null // 图文/无记录不显示
    val posSec = (row.progressMs / 1000L).toInt()
    return if (item.durationSec > 0 && row.progressMs >= item.durationSec * 1000L * 97 / 100) {
        "已看完"
    } else {
        "观看到 ${formatDuration(posSec)}"
    }
}

/**
 * 封面缩略图：124×70 圆角 8dp，右下时长角标，底部观看进度条。
 * 封面链（与搜索结果格同链路的轻量版）：CoverStore 抽帧 → minithumbnail →
 * 高清缩略图（coverFileId）→ 消息反解（loadCoverBytesByMessage）→ 类型字占位。
 * 历史条目由磁盘还原，coverBytes 恒 null，实际命中后两条链。
 */
@Composable
private fun HistoryCoverThumb(
    item: VideoItem,
    gateway: TdGateway,
    row: HistoryRow,
) {
    val cover by produceState<ImageBitmap?>(initialValue = null, item.key) {
        CoverStore.map[item.key]?.let { value = it }
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                item.coverBytes?.let { bytes ->
                    runCatching {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
                    }.getOrNull()
                }
            }
        }
        val lib = gateway as? TdLibGateway
        if (value == null && lib != null && item.coverFileId >= 0) {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { lib.loadCoverBytes(item) }.getOrNull()
            }
            if (bytes != null) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
                    }.getOrNull()
                }
            }
        }
        // 老历史记录没有 coverFileId：按 chatId+messageId 反解消息真缩略图
        //（网关侧内存+磁盘双缓存，同 key 不重复下载）
        if (value == null && lib != null && item.messageId > 0) {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { lib.loadCoverBytesByMessage(item) }.getOrNull()
            }
            if (bytes != null) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
                    }.getOrNull()
                }
            }
        }
    }

    Box(
        Modifier
            .size(width = 124.dp, height = 70.dp)
            .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
            .background(TgFeedTokens.SurfaceHigh),
    ) {
        if (cover != null) {
            Image(
                bitmap = cover!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            // 无封面占位：类型字（与搜索结果格同款兜底）
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
        // 时长角标（右下，OverlayBg 胶囊）
        if (item.mediaType != 1 && item.durationSec > 0) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
                    .background(TgFeedTokens.OverlayBg)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(
                    text = formatDuration(item.durationSec),
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextHigh,
                )
            }
        }
        // 底部观看进度条（已播占比，AccentRed；无进度/图文不显示）
        if (item.mediaType != 1 && item.durationSec > 0 && row.progressMs > 0L) {
            val frac = (row.progressMs / 1000f / item.durationSec).coerceIn(0f, 1f)
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(TgFeedTokens.OverlayHairline),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(frac)
                        .height(3.dp)
                        .background(TgFeedTokens.AccentRed),
                )
            }
        }
    }
}

// ---------------- 分组（按观看时刻：今天/昨天/本周/更早） ----------------

/** 距今 offsetDays 天的当地 0 点时间戳（offsetDays=0 即今天 0 点） */
private fun localDayStart(offsetDays: Int): Long {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    cal.add(Calendar.DAY_OF_YEAR, offsetDays)
    return cal.timeInMillis
}

/** 本周一 0 点时间戳（周一为一周开始，与"本周"口语一致；与设备 Locale 无关） */
private fun localWeekStart(): Long {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val dow = cal.get(Calendar.DAY_OF_WEEK) // SUNDAY=1 … SATURDAY=7
    val back = (dow - Calendar.MONDAY + 7) % 7
    cal.add(Calendar.DAY_OF_YEAR, -back)
    return cal.timeInMillis
}

/**
 * 把观看时间倒序的历史行平铺成"组头 + 条目"列表：
 * 今天（≥今天0点）/ 昨天 / 本周（≥本周一0点）/ 更早；ts=0 的老数据一律归"更早"。
 */
private fun buildHistorySections(rows: List<HistoryRow>): List<HistoryListRow> {
    val today0 = localDayStart(0)
    val yesterday0 = localDayStart(-1)
    val week0 = localWeekStart()
    val out = ArrayList<HistoryListRow>(rows.size + 4)
    var lastLabel: String? = null
    for (row in rows) {
        val ts = row.watchedAtMs
        val label = when {
            ts >= today0 -> "今天"
            ts >= yesterday0 -> "昨天"
            ts >= week0 -> "本周"
            else -> "更早"
        }
        if (label != lastLabel) {
            out += HistoryListRow.Header(label)
            lastLabel = label
        }
        out += HistoryListRow.Entry(row)
    }
    return out
}
