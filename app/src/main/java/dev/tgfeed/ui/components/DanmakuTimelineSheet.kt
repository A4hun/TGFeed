package dev.tgfeed.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tgfeed.core.feed.DanmakuTimelineUiState
import dev.tgfeed.core.plaza.PlazaDanmakuEvent
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.ui.components.kit.AppSheetMotion
import dev.tgfeed.ui.components.kit.SheetAnchor
import dev.tgfeed.ui.theme.TgFeedTokens
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * TL-9 弹幕时间旅行（R205 v1 纯回放视图）：广场弹幕 × 时间轴。
 *
 * 定位（timeline-opportunities.md TL-9 + 任务书边界）：按时间回看弹幕流——拉
 * 广场历史消息流（[dev.tgfeed.core.plaza.PlazaService.fetchDanmakuTimeline]，
 * 有界 3 页）→ [D] 弹幕行按真实发布时间渲染时间轴。**不做社交依赖**（无发送者
 * 维度、无计数、无热力推断）；广场数据不足诚实空态（R200 判词「广场生态薄」——
 * 「广场弹幕还太少」不冒充「没有回放功能」）。
 *
 * 视图结构：
 *  - 切换：本视频（有视频上下文时默认）/ 全站弹幕流；
 *  - 统计行：回放深度诚实口径（扫过 N 条广场消息 · M 条弹幕 · 跨 K 天）；
 *  - 流：按日分组（今天/昨天/日期头），行 = 时刻 + 弹幕文本（本视频模式下
 *    弹幕即上下文，全站模式纯流叙事「那时候大家在刷什么」）；
 *  - 空态三分：广场未开启（引导设置）/ 拉取中 / 弹幕不足（诚实+引导发弹幕）。
 *
 * 动效/视觉：AppSheetMotion 壳（BOTTOM 锚定——触发点在长按面板下方；退场动画
 * 播完再 onDismiss 的契约由壳统一保证）。
 *
 * @param target 打开回放的视频上下文（null = 无上下文，恒「全站弹幕流」）
 * @param state 回放状态（loading + 时间轴）
 * @param plazaEnabled 广场总开关（关 = 空态引导设置，不再拉取）
 * @param onRefresh 手动重拉（用户点刷新；有界页数纪律在 fetch 内收口）
 * @param onDismiss 请求关窗
 */
@Composable
internal fun DanmakuTimelineSheet(
    target: VideoItem?,
    state: DanmakuTimelineUiState,
    plazaEnabled: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 视图模式：true = 只看 target 的弹幕；无视频上下文恒 false（全站）
    var onlyTarget by remember(target) { mutableStateOf(target != null) }
    // 进面板拉一次（拉取有界 3 页、不轮询；plaza 关着不拉）
    LaunchedEffect(Unit) {
        if (plazaEnabled) onRefresh()
    }
    val fmtTime = remember { SimpleDateFormat("HH:mm", Locale.CHINA) }
    val fmtDay = remember { SimpleDateFormat("yyyy-MM-dd", Locale.CHINA) }
    val cal = remember { Calendar.getInstance() }
    // 展示流（本视频 = 按 key 过滤；全站 = 全量）
    val events = remember(state, onlyTarget, target) {
        val tl = state.timeline
        if (onlyTarget && target != null) tl.events.filter { it.key == target.key } else tl.events
    }
    val dayGroups = remember(events) {
        events.groupBy { fmtDay.format(Date(it.timestampSec * 1000L)) }
            .toSortedMap(compareByDescending { it }) // 新→旧（与 Feed 刷流习惯一致）
    }
    val spanDays = remember(events) {
        if (events.size < 2) 0
        else ((events.last().timestampSec - events.first().timestampSec) / 86400L).toInt()
    }

    AppSheetMotion(
        onDismiss = onDismiss,
        title = "弹幕时间轴 · 回放",
        anchor = SheetAnchor.BOTTOM,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = TgFeedTokens.GapLg)) {
            // 模式切换 + 刷新（弹幕入口旁的回放口：本视频/全站二态 + 手动重拉）
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (target != null) {
                    TimelineModeChip("本视频", onlyTarget) { onlyTarget = true }
                    TimelineModeChip("全站弹幕流", !onlyTarget) { onlyTarget = false }
                } else {
                    TimelineModeChip("全站弹幕流", selected = true) { }
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = "重拉",
                    tint = TgFeedTokens.TextMedium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                        .clickable(enabled = plazaEnabled && !state.loading) { onRefresh() }
                        .padding(TgFeedTokens.GapXs)
                        .size(20.dp),
                )
            }
            Spacer(Modifier.height(TgFeedTokens.GapSm))
            when {
                !plazaEnabled -> TimelineEmpty("广场还没开启", "回放需要广场里有历史弹幕；\n到 设置 → TG广场 打开后，先发一条弹幕试试")
                state.loading -> Row(
                    Modifier.fillMaxWidth().padding(vertical = TgFeedTokens.GapLg),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = TgFeedTokens.TextHigh,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.padding(horizontal = TgFeedTokens.GapXs))
                    Text("正在回看广场历史…", color = TgFeedTokens.TextMedium, style = MaterialTheme.typography.bodySmall)
                }
                events.isEmpty() -> TimelineEmpty(
                    if (state.timeline.scannedRows == 0) "广场还没就绪" else "广场弹幕还太少",
                    if (state.timeline.scannedRows == 0) "暂时拉不到广场消息，稍后再试"
                    else "已回看 ${state.timeline.scannedRows} 条广场消息，还没有弹幕。\n广场生态还小（回放会随着弹幕积累变厚）——你也可以现在发一条",
                )
                else -> {
                    // 统计行（回放深度诚实口径）
                    Text(
                        "回看 ${state.timeline.scannedRows} 条广场消息 · ${events.size} 条弹幕 · 跨 $spanDays 天",
                        color = TgFeedTokens.TextLow,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(TgFeedTokens.GapSm))
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                    ) {
                        dayGroups.forEach { (day, list) ->
                            item(key = "day_$day") {
                                Text(
                                    dayLabel(day, cal),
                                    color = TgFeedTokens.TextLow,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(top = TgFeedTokens.GapSm, bottom = TgFeedTokens.GapXs),
                                )
                            }
                            items(list.size) { i ->
                                DanmakuEventRow(list[i], fmtTime)
                            }
                        }
                        item(key = "bottom_pad") { Spacer(Modifier.height(TgFeedTokens.GapLg)) }
                    }
                }
            }
            Spacer(Modifier.height(TgFeedTokens.GapSm))
        }
    }
}

/** 模式切换胶囊（选中=电报蓝底白字；回放入口旁的轻量切换，非筛选语义） */
@Composable
private fun TimelineModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
            .background(if (selected) TgFeedTokens.TgBlue.copy(alpha = 0.28f) else TgFeedTokens.SurfaceHigh)
            .border(
                0.5.dp,
                if (selected) TgFeedTokens.TgBlue else TgFeedTokens.OverlayHairline,
                RoundedCornerShape(TgFeedTokens.RadiusPill),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = TgFeedTokens.GapMd, vertical = TgFeedTokens.GapXs),
    ) {
        Text(
            label,
            color = if (selected) TgFeedTokens.TextHigh else TgFeedTokens.TextMedium,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

/** 空态块（诚实口径：不冒充、给出口） */
@Composable
private fun TimelineEmpty(title: String, subtitle: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = TgFeedTokens.GapLg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.History,
            contentDescription = null,
            tint = TgFeedTokens.TextLow,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(TgFeedTokens.GapSm))
        Text(title, color = TgFeedTokens.TextMedium, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(TgFeedTokens.GapXs))
        Text(
            subtitle,
            color = TgFeedTokens.TextLow,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** 单条弹幕事件行：时刻 + 文本（时间轴的最小渲染单元） */
@Composable
private fun DanmakuEventRow(event: PlazaDanmakuEvent, fmtTime: SimpleDateFormat) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = TgFeedTokens.GapXs),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            fmtTime.format(Date(event.timestampSec * 1000L)),
            color = TgFeedTokens.TextLow,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.padding(horizontal = TgFeedTokens.GapSm))
        Text(
            event.text,
            color = TgFeedTokens.TextMedium,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 日标签：今天/昨天/MM-dd（同年）/yyyy-MM-dd（跨年） */
private fun dayLabel(day: String, cal: Calendar): String {
    val today = cal.clone() as Calendar
    val fmtToday = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    if (day == fmtToday.format(today.time)) return "今天"
    today.add(Calendar.DAY_OF_YEAR, -1)
    if (day == fmtToday.format(today.time)) return "昨天"
    return day.removePrefix(cal.get(Calendar.YEAR).toString() + "-")
}
