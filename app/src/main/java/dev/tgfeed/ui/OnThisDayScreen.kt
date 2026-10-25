package dev.tgfeed.ui

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.feed.FeedViewModelFactory
import dev.tgfeed.core.onthisday.CURATED_QUERY_LIMIT
import dev.tgfeed.core.onthisday.MAX_YEARS
import dev.tgfeed.core.onthisday.OnThisDayCuration
import dev.tgfeed.core.onthisday.OnThisDayOutcome
import dev.tgfeed.core.onthisday.OnThisDayYearStat
import dev.tgfeed.core.onthisday.Reason
import dev.tgfeed.core.onthisday.anniversaryDate
import dev.tgfeed.core.onthisday.anniversaryHeadline
import dev.tgfeed.core.onthisday.curateOnThisDay
import dev.tgfeed.core.onthisday.dateHeadline
import dev.tgfeed.core.onthisday.onThisDayWindow
import dev.tgfeed.core.onthisday.pickYearForPush
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.ui.components.formatDuration
import dev.tgfeed.ui.components.kit.AppEmptyState
import dev.tgfeed.ui.components.kit.AppIconButton
import dev.tgfeed.ui.components.kit.AppLoading
import dev.tgfeed.ui.components.kit.AppPill
import dev.tgfeed.ui.components.kit.AppTopBar
import dev.tgfeed.ui.components.kit.adaptiveContentWidth
import dev.tgfeed.ui.theme.TgFeedTokens
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * R203 那年今日页（TL-2）：「N 年前的今天你的频道们在传什么」。
 *
 * 结构：
 * - 顶栏（AppTopBar 统一二级页壳）：返回 + 标题「那年今日」 + 日历钮（任意日期回看）；
 * - 年轮 pills（1..5 年前，LazyRow 横滑）：周年模式的选择器（R200 频控口径「各一屏，
 *   别一次倾泻」）；进页自动择今日最佳年轮（[pickYearForPush] 同一纯函数——与 RT-2
 *   推送判定同口径，通知点进带 initialYearsAgo 落到同一轮）；
 * - 任意日期模式：选日历日期回看任意历史日期（仍 ±1 天窗）；「回到今天」pill 切回；
 * - 头部文案：anniversaryHeadline / dateHeadline（策展纯函数产出，用户向）；
 * - 内容：策展列表（curateOnThisDay → TimeWindowPolicy.refineCrossSection 共享管线
 *   + 窗防御；每频道上限 2 比时光机更紧——年轮要广度）；行卡=标题 + 频道·类型·
 *   时长·当日时刻；点击 onPlay（MainActivity 编排：一次性源回 Feed 定位连刷，
 *   返回键先回本页）。
 *
 * 空态（诚实三口径，OnThisDaySource.Reason 直译，不猜）：
 * - NO_DATABASE「还没有时间档案」（深索引从未建立/演示模式）；
 * - INDEX_PENDING「时间档案整理中」（R202 时间索引后台构建分钟级，就绪自动补刷）；
 * - NO_SUBSCRIPTIONS「还没有关注的频道」；
 * - 窗内无货「X 年前的今天暂无存货」+ 引导换年轮/换日期。
 *
 * 数据通道：共享 VM 的 onThisDayQuery/onThisDayYearStats（deepIndex 单源，
 * R202 timeWindowItems 共享原语）；就绪流翻转（构建中→就绪）自动重查补刷。
 */
@Composable
fun OnThisDayScreen(
    gateway: TdGateway,
    onBack: () -> Unit,
    // 通知直达的落点年轮（null=自动择今日最佳）；一次性意图（本页消费）
    initialYearsAgo: Int? = null,
    onPlay: (item: VideoItem, batch: List<VideoItem>) -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    // 系统返回键 = 返回上一页（状态驱动路由，不在返回栈里，不拦会退到桌面）
    BackHandler(onBack = onBack)

    // 与 FeedScreen 共享 activity 作用域的同一个 ViewModel（"gw"+identityHashCode key
    // 约定）：查询走它的 deepIndex（VM 私有单源），播放切源也落在同一实例上
    val vm: FeedViewModel = viewModel(
        factory = FeedViewModelFactory(gateway, app),
        key = "gw" + System.identityHashCode(gateway),
    )

    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now() }

    // ---------- 模式与选择 ----------
    // arbitraryDate 非 null = 任意日期回看模式；null = 周年模式（yearsAgo 生效）
    var arbitraryDate by remember { mutableStateOf<LocalDate?>(null) }
    // 默认 1 年前起步；统计到达时自动择今日最佳年轮（通知直达带 initialYearsAgo
    // 则定轮不覆盖）。有效年轮恒 ≥1——不引入哨兵值，防泄漏进文案/查询
    var yearsAgo by remember { mutableStateOf(initialYearsAgo ?: 1) }
    var showDatePicker by remember { mutableStateOf(false) }

    // ---------- 年轮统计（pills 徽标 + 自动择轮） ----------
    // null = 加载中；进页拉一次；索引就绪流翻转时重拉（构建中→就绪补刷）
    val indexReady by vm.onThisDayIndexReady.collectAsState()
    var stats by remember { mutableStateOf<OnThisDayOutcome<List<OnThisDayYearStat>>?>(null) }
    LaunchedEffect(Unit) { stats = vm.onThisDayYearStats(today, zone) }
    // 就绪翻转（false→true）重拉统计：首轮查询点亮就绪流后（R202 语义）补刷徽标
    var lastReadyTick by remember { mutableStateOf(false) }
    LaunchedEffect(indexReady) {
        if (indexReady && !lastReadyTick) stats = vm.onThisDayYearStats(today, zone)
        lastReadyTick = indexReady
    }

    // 自动择轮（initialYearsAgo 为空且用户未手动选过）：统计到达时择今日最佳年轮
    var autoPicked by remember { mutableStateOf(initialYearsAgo != null) }
    LaunchedEffect(stats) {
        if (!autoPicked) {
            val ready = (stats as? OnThisDayOutcome.Ready)?.value
            if (ready != null) {
                yearsAgo = pickYearForPush(ready)?.yearsAgo ?: 1
                autoPicked = true
            }
        }
    }

    // ---------- 当前窗与策展 ----------
    val window = remember(arbitraryDate, yearsAgo, zone, today) {
        when (val d = arbitraryDate) {
            null -> onThisDayWindow(anniversaryDate(today, yearsAgo), zone)
            else -> onThisDayWindow(d, zone)
        }
    }
    // null = 加载中；查询→策展全链（查询取满候选池硬顶，精选截断在策展层——
    // 先查后选的量纲分离，防候选池被查询 limit 过早掐死）
    val uiState by produceState<OnThisDayUiState?>(initialValue = null, window, indexReady) {
        when (val r = vm.onThisDayQuery(window, CURATED_QUERY_LIMIT)) {
            is OnThisDayOutcome.Unavailable -> value = OnThisDayUiState.Unavailable(r.reason)
            is OnThisDayOutcome.Ready -> value = OnThisDayUiState.Curated(curateOnThisDay(r.value, window))
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(TgFeedTokens.InkBlack),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                // 自适应列宽必须在前（P1-6 教训：fillMaxSize 先钉死全宽，其后限宽是死代码）
                .adaptiveContentWidth()
                .fillMaxSize(),
        ) {
            AppTopBar(
                title = "那年今日",
                onBack = onBack,
                actions = {
                    AppIconButton(
                        icon = Icons.Outlined.CalendarMonth,
                        contentDescription = "选日期回看",
                        onClick = { showDatePicker = true },
                    )
                },
            )

            // ---------- 模式行：年轮 pills / 任意日期头 ----------
            when (val d = arbitraryDate) {
                null -> LazyRow(
                    contentPadding = PaddingValues(
                        start = TgFeedTokens.GapLg,
                        end = TgFeedTokens.GapLg,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                ) {
                    items((1..MAX_YEARS).toList()) { y ->
                        AppPill(
                            text = "$y 年前",
                            selected = yearsAgo == y,
                            onClick = {
                                yearsAgo = y
                                autoPicked = true // 手选即定轮（自动择轮不再覆盖）
                            },
                        )
                    }
                }
                else -> Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = TgFeedTokens.GapLg),
                    horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 日期标签（当前回看目标）+ 回到今天
                    Text(
                        text = "${d.year} 年 ${d.monthValue} 月 ${d.dayOfMonth} 日",
                        style = MaterialTheme.typography.labelLarge,
                        color = TgFeedTokens.TextMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    AppPill(
                        text = "回到今天",
                        selected = false,
                        onClick = { arbitraryDate = null },
                    )
                }
            }

            // ---------- 内容区 ----------
            when (val state = uiState) {
                null -> AppLoading(
                    text = "正在翻那年的档案…",
                    modifier = Modifier.weight(1f),
                )
                is OnThisDayUiState.Unavailable -> UnavailableState(
                    reason = state.reason,
                    modifier = Modifier.weight(1f),
                )
                is OnThisDayUiState.Curated -> {
                    val curation = state.curation
                    if (curation.items.isEmpty()) {
                        AppEmptyState(
                            icon = Icons.Outlined.EventBusy,
                            title = if (arbitraryDate != null) {
                                "那一天暂时没有存货"
                            } else {
                                "$yearsAgo 年前的今天暂无存货"
                            },
                            subtitle = "老内容可能还没爬全；试试其它年轮，或点右上角日历换个日期",
                            actionText = "换个日期",
                            onAction = { showDatePicker = true },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        // 头部文案：用户向大标题（周年版/任意日期版）
                        Text(
                            text = when (val d = arbitraryDate) {
                                null -> anniversaryHeadline(yearsAgo, curation.stats.channels)
                                else -> dateHeadline(d, curation.stats.channels)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = TgFeedTokens.TextHigh,
                            modifier = Modifier.padding(
                                start = TgFeedTokens.GapLg,
                                end = TgFeedTokens.GapLg,
                                top = TgFeedTokens.GapLg,
                                bottom = TgFeedTokens.GapSm,
                            ),
                        )
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
                            items(curation.items, key = { it.key }) { item ->
                                OnThisDayRow(
                                    item = item,
                                    zone = zone,
                                    onClick = { onPlay(item, curation.items) },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    // ---------- 任意日期回看选择器 ----------
    if (showDatePicker) {
        OnThisDayDatePicker(
            zone = zone,
            today = today,
            onPick = { picked ->
                arbitraryDate = picked
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
        )
    }
}

// ==================== 以下为本页私有组件与模型 ====================

/** 页面状态机（查询+策展后）：加载中（null）/ 不可用 / 策展结果 */
private sealed interface OnThisDayUiState {
    data class Curated(val curation: OnThisDayCuration) : OnThisDayUiState
    data class Unavailable(val reason: Reason) : OnThisDayUiState
}

/** 不可用三态的诚实文案（OnThisDaySource.Reason 直译；不猜、不粉饰） */
@Composable
private fun UnavailableState(reason: Reason, modifier: Modifier = Modifier) {
    when (reason) {
        Reason.NO_DATABASE -> AppEmptyState(
            icon = Icons.Outlined.Inventory2,
            title = "还没有时间档案",
            subtitle = "深索引建立后，你频道的每一年都会沉淀在这里",
            modifier = modifier,
        )
        Reason.INDEX_PENDING -> AppEmptyState(
            icon = Icons.Outlined.HourglassTop,
            title = "时间档案整理中",
            subtitle = "首次启用正在后台建时间索引（大库约几分钟），整理好会自动出现",
            modifier = modifier,
        )
        Reason.NO_SUBSCRIPTIONS -> AppEmptyState(
            icon = Icons.Outlined.Explore,
            title = "还没有关注的频道",
            subtitle = "关注几个频道后，那年今日才有原料",
            modifier = modifier,
        )
    }
}

/**
 * 精选条目行：标题（caption 首行，空回落频道名）+ 频道·类型/时长·当日时刻。
 * 文本先行（DB 条目无封面字节——封面链走播放后消息反解，列表页不预取网络）。
 */
@Composable
private fun OnThisDayRow(
    item: VideoItem,
    zone: ZoneId,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
            .clickable(onClick = onClick)
            .padding(vertical = TgFeedTokens.GapSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = item.caption.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
                    ?: item.channelTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = TgFeedTokens.TextHigh,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(TgFeedTokens.GapXs))
            Text(
                text = buildString {
                    append(item.channelTitle)
                    when (item.mediaType) {
                        1 -> append(" · 图文")
                        2 -> if (item.durationSec > 0) append(" · 音频 ${formatDuration(item.durationSec)}")
                        else -> if (item.durationSec > 0) append(" · ${formatDuration(item.durationSec)}")
                    }
                    val moment = inWindowMoment(item, zone)
                    if (moment.isNotEmpty()) append(" · $moment")
                },
                style = MaterialTheme.typography.bodySmall,
                color = TgFeedTokens.TextLow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 当日时刻文案：窗内条目的发布时刻「10月17日 14:30」（本地时区） */
private fun inWindowMoment(item: VideoItem, zone: ZoneId): String {
    if (item.dateEpochSec <= 0) return ""
    val t = Instant.ofEpochSecond(item.dateEpochSec.toLong()).atZone(zone)
    return "${t.monthValue}月${t.dayOfMonth}日 %02d:%02d".format(t.hour, t.minute)
}

/**
 * 日期选择器（M3 DatePicker 深色适配）：回看任意历史日期（≤今天，未来日期钳到今天）。
 * 选中值是 UTC 零点毫秒（M3 口径）——转 LocalDate 用 UTC，窗计算回本机时区。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnThisDayDatePicker(
    zone: ZoneId,
    today: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val dp = rememberDatePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val ms = dp.selectedDateMillis ?: return@TextButton
                    val picked = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                    onPick(if (picked.isAfter(today)) today else picked)
                },
            ) { Text("回看") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        colors = androidx.compose.material3.DatePickerDefaults.colors(
            containerColor = TgFeedTokens.Surface,
        ),
    ) {
        DatePicker(state = dp, title = null)
    }
}
