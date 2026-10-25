package dev.tgfeed.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tgfeed.core.feed.EventResearchQuery
import dev.tgfeed.core.feed.EventResearchUiState
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.feed.FeedViewModelFactory
import dev.tgfeed.core.td.ChannelBrief
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.ui.components.kit.AppEmptyState
import dev.tgfeed.ui.components.kit.AppLoading
import dev.tgfeed.ui.components.kit.AppTopBar
import dev.tgfeed.ui.theme.TgFeedTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WK-3 事件研究器（R205）：时间维度的高级研究 UI。
 *
 * 结构（timeline-opportunities.md WK-3 口径：研究党工具，发现页深功能区进）：
 *  - 表单：关键词（v1 必填——纯时间窗查询等 R202 时间索引原语接线，届时开放
 *    无关键词横截面）+ 时间窗快捷档（24h/72h/7天/30天/90天/1年/自定义天数）
 *    + 共现窗（24h/72h/7天，W4 默认 72h）；
 *  - 结果：时间轴分桶条形图（哪里密哪里空）→ 内容流（日分组时间序）→ 共现
 *    事件簇卡（传播路径：首发→各频道+滞后小时数——「这次内容是怎么在频道生态
 *    里传开的」）；
 *  - 频道二次筛选：结果内 chips 纯客户端过滤（refineEventResearchChannels，
 *    零新查询）；
 *  - 诚实态：EnginePending（R202 待接）/ Failed（索引未建引导）/ 空窗空态
 *    （窗口里真没有，不冒充）。
 *
 * 数据链：本屏只消费 [FeedViewModel.eventResearch] 状态；查询走
 * vm.runEventResearch（VM part 内挂 FtsKeywordWindowSource——深索引 fts 只读
 * 召回，不改 DeepSearchIndex 一行）。点簇中继可跳试吃该频道（onOpenChannel，
 * 宿主与发现页候选卡同一条链）。
 */
@Composable
fun EventResearchScreen(
    gateway: TdGateway,
    onBack: () -> Unit,
    onOpenChannel: (ChannelBrief) -> Unit = {},
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
    BackHandler(onBack = onBack)
    val vm: FeedViewModel = viewModel(
        factory = FeedViewModelFactory(gateway, app),
        key = "gw" + System.identityHashCode(gateway),
    )
    val state by vm.eventResearch.collectAsState()

    // ---- 表单状态（页面私有，不落盘：研究是即席工具） ----
    var keyword by remember { mutableStateOf("") }
    var windowSpanHours by remember { mutableIntStateOf(DEFAULT_WINDOW_SPAN_HOURS) }
    var customDaysText by remember { mutableStateOf("") }
    var coWindowHours by remember { mutableIntStateOf(EventResearchQuery.DEFAULT_CO_OCCURRENCE_WINDOW_HOURS) }

    // 跑研究（四处入口共用一处实现：Idle/EnginePending/Failed 表单的「开始研究」+
    // Ready 结果尾的「重新研究」——同参数同语义，绝不四份抄写）
    val onRunResearch: () -> Unit = {
        val span = resolveWindowSpanHours(windowSpanHours, customDaysText)
        if (span > 0 && keyword.isNotBlank()) {
            val now = System.currentTimeMillis() / 1000L
            vm.runEventResearch(
                EventResearchQuery(
                    fromEpochSec = now - span * 3600L,
                    toEpochSec = now,
                    keywords = keyword.trim(),
                    coOccurrenceWindowHours = coWindowHours,
                ),
            )
        }
    }
    val form: @Composable () -> Unit = {
        ResearchForm(
            keyword = keyword,
            onKeyword = { keyword = it },
            windowSpanHours = windowSpanHours,
            onWindowSpan = { windowSpanHours = it },
            customDaysText = customDaysText,
            onCustomDays = { customDaysText = it },
            coWindowHours = coWindowHours,
            onCoWindow = { coWindowHours = it },
            onRun = onRunResearch,
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(TgFeedTokens.InkBlack),
    ) {
        AppTopBar(
            title = "事件研究器",
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
            EventResearchUiState.Idle, EventResearchUiState.Loading -> {
                form()
                if (s == EventResearchUiState.Loading) {
                    AppLoading("正在召回时间窗内容并做共现分析…")
                } else {
                    AppEmptyState(
                        icon = Icons.AutoMirrored.Outlined.ManageSearch,
                        title = "给定时间窗和关键词，回看事件怎么在频道生态里传开",
                        subtitle = "数据来自本地深索引（离线、零网络请求）；\n纯时间窗查询将在时间索引引擎（R202）接线后开放",
                    )
                }
            }
            EventResearchUiState.EnginePending -> {
                form()
                AppEmptyState(
                    icon = Icons.Outlined.Schedule,
                    title = "纯时间窗查询还没接线（时间索引引擎 R202 待接入）",
                    subtitle = "当前版本请输入关键词：关键词召回路径已可用；\n关键词留空时的全网时间横截面，等时间索引共享原语落地后开放",
                )
            }
            is EventResearchUiState.Failed -> {
                form()
                AppEmptyState(
                    icon = Icons.AutoMirrored.Outlined.ManageSearch,
                    title = "这轮研究没跑成",
                    subtitle = s.reason,
                )
            }
            is EventResearchUiState.Ready -> {
                ResearchResult(
                    result = s.result,
                    onRefineChannels = { vm.refineEventResearchChannels(it) },
                    onOpenChannel = onOpenChannel,
                    onRerun = onRunResearch,
                )
            }
        }
    }
}

// ---------------- 表单 ----------------

/** 默认时间窗：近 7 天（事件传播的自然观察尺度，与共现窗解耦） */
private const val DEFAULT_WINDOW_SPAN_HOURS = 24 * 7

/** 自定义天数输入上限（1 年：研究窗太长时等 R202 索引路径更合适） */
private const val MAX_CUSTOM_DAYS = 365

/** 快捷窗档位（小时）：chips 顺序即展示顺序；-1 = 自定义档 */
private val WINDOW_SPAN_CHOICE_HOURS = listOf(24, 72, 24 * 7, 24 * 30, 24 * 90, 24 * 365, -1)

/** 共现窗档位（小时）：簇内相邻中继的最大时间差（W4 证据窗默认 72h） */
private val CO_WINDOW_CHOICE_HOURS = listOf(24, 72, 24 * 7)

/** 自定义天数文本 → 窗小时数（非法/超限回 0=不跑） */
private fun resolveWindowSpanHours(spanHours: Int, customDaysText: String): Long {
    if (spanHours != -1) return spanHours.toLong()
    val d = customDaysText.trim().toIntOrNull() ?: return 0L
    return if (d in 1..MAX_CUSTOM_DAYS) d * 24L else 0L
}

@Composable
private fun ResearchForm(
    keyword: String,
    onKeyword: (String) -> Unit,
    windowSpanHours: Int,
    onWindowSpan: (Int) -> Unit,
    customDaysText: String,
    onCustomDays: (String) -> Unit,
    coWindowHours: Int,
    onCoWindow: (Int) -> Unit,
    onRun: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = TgFeedTokens.GapLg),
    ) {
        Spacer(Modifier.height(TgFeedTokens.GapSm))
        OutlinedTextField(
            value = keyword,
            onValueChange = onKeyword,
            label = { Text("关键词（v1 必填·多个词空格分隔）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(TgFeedTokens.RadiusField),
        )
        Spacer(Modifier.height(TgFeedTokens.GapMd))
        FormLabel("时间窗（往回看多远）")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
            items(WINDOW_SPAN_CHOICE_HOURS.size) { i ->
                val h = WINDOW_SPAN_CHOICE_HOURS[i]
                val label = if (h == -1) "自定义" else windowSpanLabel(h)
                FilterChip(
                    label = label,
                    selected = windowSpanHours == h,
                    onClick = { onWindowSpan(h) },
                )
            }
        }
        if (windowSpanHours == -1) {
            Spacer(Modifier.height(TgFeedTokens.GapSm))
            OutlinedTextField(
                value = customDaysText,
                onValueChange = { t -> if (t.length <= 3) onCustomDays(t.filter { ch -> ch.isDigit() }) },
                label = { Text("自定义天数（1~$MAX_CUSTOM_DAYS）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(0.6f),
                shape = RoundedCornerShape(TgFeedTokens.RadiusField),
            )
        }
        Spacer(Modifier.height(TgFeedTokens.GapMd))
        FormLabel("共现窗（同内容跨频道相隔多久还算一个事件）")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
            items(CO_WINDOW_CHOICE_HOURS.size) { i ->
                val h = CO_WINDOW_CHOICE_HOURS[i]
                FilterChip(
                    label = if (h < 24) "${h}小时" else "${h / 24}天",
                    selected = coWindowHours == h,
                    onClick = { onCoWindow(h) },
                )
            }
        }
        Spacer(Modifier.height(TgFeedTokens.GapLg))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                .background(
                    if (keyword.isNotBlank()) TgFeedTokens.AccentRed
                    else TgFeedTokens.SurfaceHigh,
                )
                .clickable(enabled = keyword.isNotBlank(), onClick = onRun)
                .padding(vertical = TgFeedTokens.GapMd),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (keyword.isBlank()) "输入关键词后开始研究" else "开始研究",
                color = if (keyword.isNotBlank()) TgFeedTokens.TextHigh else TgFeedTokens.TextLow,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Spacer(Modifier.height(TgFeedTokens.GapMd))
    }
}

@Composable
private fun FormLabel(text: String) {
    Text(
        text,
        color = TgFeedTokens.TextLow,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
    )
    Spacer(Modifier.height(TgFeedTokens.GapXs))
}

/** 轻量筛选 chip（选中=红底白字；本地私有实现，避免引入 Material3 FilterChip 大件） */
@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
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

private fun windowSpanLabel(hours: Int): String = when {
    hours < 48 -> "${hours}小时"
    hours < 24 * 90 -> "${hours / 24}天"
    else -> "${hours / (24 * 30)}个月"
}

// ---------------- 结果 ----------------

@Composable
private fun ResearchResult(
    result: dev.tgfeed.core.feed.EventResearchResult,
    onRefineChannels: (Set<Long>) -> Unit,
    onOpenChannel: (ChannelBrief) -> Unit,
    onRerun: () -> Unit,
) {
    val fmtDay = remember { SimpleDateFormat("yyyy-MM-dd", Locale.CHINA) }
    val fmtTime = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }
    // 频道二次筛选选中集：null = 全部（未筛选）；选中集驱动 refine（纯客户端）
    var selectedChatIds by remember(result) { mutableStateOf<Set<Long>?>(null) }
    val buckets = remember(result) {
        dev.tgfeed.core.feed.EventResearcher.bucketTimeline(
            result.items, result.query.fromEpochSec, result.query.toEpochSec,
        )
    }
    // 结果内频道 chips 数据（按条目数降序，前 12 个防长尾刷屏）
    val channels = remember(result) {
        result.items
            .groupBy { it.chatId }
            .entries
            .sortedByDescending { it.value.size }
            .take(12)
            .map { (chatId, list) -> chatId to (list.firstOrNull()?.channelTitle ?: "频道 $chatId") }
    }
    // 日分组（内容流：新→旧与 Feed 习惯一致，日内时间升序=传播顺序可读）
    val dayGroups = remember(result, selectedChatIds) {
        val filtered = if (selectedChatIds == null) result.items
        else result.items.filter { it.chatId in selectedChatIds!! }
        val desc = filtered.sortedByDescending { it.dateEpochSec.toLong() }
        desc.groupBy { fmtDay.format(Date(it.dateEpochSec.toLong() * 1000L)) }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapSm,
        ),
    ) {
        // 引擎口径（诚实上屏）
        item(key = "note") {
            Text(
                result.engineNote,
                color = TgFeedTokens.TextLow,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(vertical = TgFeedTokens.GapXs),
            )
        }
        // 频道筛选 chips（全部 + 各频道）
        if (channels.size > 1) {
            item(key = "channel_filter") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
                    item {
                        FilterChip(
                            label = "全部 ${result.items.size}",
                            selected = selectedChatIds == null,
                            onClick = {
                                selectedChatIds = null
                                onRefineChannels(emptySet())
                            },
                        )
                    }
                    items(channels.size) { i ->
                        val (chatId, title) = channels[i]
                        val on = selectedChatIds?.contains(chatId) == true
                        FilterChip(
                            label = title,
                            selected = on,
                            onClick = {
                                val next = if (on) selectedChatIds!! - chatId else (selectedChatIds ?: emptySet()) + chatId
                                selectedChatIds = next
                                onRefineChannels(next)
                            },
                        )
                    }
                }
                Spacer(Modifier.height(TgFeedTokens.GapSm))
            }
        }
        // 时间轴分桶条形图
        item(key = "timeline") {
            TimelineBars(buckets)
            Spacer(Modifier.height(TgFeedTokens.GapSm))
        }
        // 共现事件簇（传播路径）
        if (result.clusters.isNotEmpty()) {
            item(key = "clusters_header") {
                SectionTitle("共现事件 · ${result.clusters.size} 个（频道同传 = 传播路径）")
            }
            items(result.clusters.size) { i ->
                ClusterCard(result.clusters[i], fmtTime, onOpenChannel)
                Spacer(Modifier.height(TgFeedTokens.GapSm))
            }
        }
        // 内容流时间轴（日分组）
        item(key = "stream_header") {
            SectionTitle("内容流 · ${result.items.size} 条（新 → 旧）")
        }
        dayGroups.forEach { (day, list) ->
            item(key = "day_$day") {
                Text(
                    day,
                    color = TgFeedTokens.TextLow,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(vertical = TgFeedTokens.GapXs),
                )
            }
            items(list.size) { i ->
                StreamRow(list[i], fmtTime)
            }
        }
        item(key = "rerun") {
            Spacer(Modifier.height(TgFeedTokens.GapMd))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                    .background(TgFeedTokens.SurfaceHigh)
                    .clickable(onClick = onRerun)
                    .padding(vertical = TgFeedTokens.GapMd),
                contentAlignment = Alignment.Center,
            ) {
                Text("重新研究", color = TgFeedTokens.TextMedium, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(TgFeedTokens.GapLg))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(TgFeedTokens.AccentRed),
        )
        Spacer(Modifier.width(TgFeedTokens.GapXs))
        Text(
            text,
            color = TgFeedTokens.TextHigh,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
    Spacer(Modifier.height(TgFeedTokens.GapSm))
}

/** 时间轴条形图：等宽桶柱（高∝计数），仅首/中/末桶落标签防拥挤 */
@Composable
private fun TimelineBars(buckets: List<dev.tgfeed.core.feed.TimelineBucket>) {
    if (buckets.isEmpty()) return
    val maxCount = buckets.maxOf { it.count }.coerceAtLeast(1)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(72.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            buckets.forEachIndexed { i, b ->
                val hFrac = b.count.toFloat() / maxCount
                Column(
                    Modifier.weight(1f).fillMaxSize(),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.86f)
                            .height((8 + 56 * hFrac).dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (b.count > 0) TgFeedTokens.TgBlue.copy(alpha = 0.55f + 0.45f * hFrac)
                                else TgFeedTokens.SurfaceHigh,
                            ),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(buckets.first().label, color = TgFeedTokens.TextLow, style = MaterialTheme.typography.labelSmall)
            Text("峰值 ${maxCount}", color = TgFeedTokens.TextLow, style = MaterialTheme.typography.labelSmall)
            Text(buckets.last().label, color = TgFeedTokens.TextLow, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** 簇卡：代表标题 + 首发信息 + 传播路径（首发→各频道+滞后小时，可点跳试吃） */
@Composable
private fun ClusterCard(
    cluster: dev.tgfeed.core.feed.EventCluster,
    fmtTime: SimpleDateFormat,
    onOpenChannel: (ChannelBrief) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
            .background(TgFeedTokens.Surface)
            .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusMd))
            .padding(TgFeedTokens.GapMd),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                cluster.representativeTitle.ifBlank { "（无标题事件）" },
                color = TgFeedTokens.TextHigh,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(TgFeedTokens.GapSm))
            Text(
                if (cluster.exact) "同片转发" else "同文案模板",
                color = TgFeedTokens.StarYellow,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(
            "首发 ${fmtTime.format(Date(cluster.firstEpochSec * 1000L))} · ${cluster.channelCount} 个频道参与",
            color = TgFeedTokens.TextLow,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(TgFeedTokens.GapSm))
        // 传播路径横滑：首发徽章 + 各中继（频道名 + 滞后时长）
        LazyRow(horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
            items(cluster.relays.size) { i ->
                val relay = cluster.relays[i]
                Row(
                    Modifier
                        .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                        .background(
                            if (relay.isFirst) TgFeedTokens.AccentRed.copy(alpha = 0.24f)
                            else TgFeedTokens.SurfaceHigh,
                        )
                        .border(
                            0.5.dp,
                            if (relay.isFirst) TgFeedTokens.AccentRed else TgFeedTokens.OverlayHairline,
                            RoundedCornerShape(TgFeedTokens.RadiusPill),
                        )
                        .clickable {
                            onOpenChannel(
                                ChannelBrief(relay.item.chatId, relay.item.channelTitle, null),
                            )
                        }
                        .padding(horizontal = TgFeedTokens.GapMd, vertical = TgFeedTokens.GapXs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (relay.isFirst) {
                        Text("首发 ", color = TgFeedTokens.AccentRed, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(
                        relay.item.channelTitle.ifBlank { "频道 ${relay.item.chatId}" },
                        color = TgFeedTokens.TextHigh,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                    if (!relay.isFirst) {
                        Text(
                            "  +${lagLabel(relay.offsetHours)}",
                            color = TgFeedTokens.TextLow,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

private fun lagLabel(hours: Long): String = when {
    hours <= 0L -> "同步"
    hours < 48L -> "${hours}h"
    else -> "${"%.1f".format(hours / 24.0)}天"
}

/** 内容流行：时间 + 频道 + 标题首行 */
@Composable
private fun StreamRow(item: VideoItem, fmtTime: SimpleDateFormat) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = TgFeedTokens.GapXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            fmtTime.format(Date(item.dateEpochSec.toLong() * 1000L)),
            color = TgFeedTokens.TextLow,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.width(TgFeedTokens.GapMd))
        Text(
            item.channelTitle.ifBlank { "频道 ${item.chatId}" },
            color = TgFeedTokens.TgBlue,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(92.dp),
        )
        Spacer(Modifier.width(TgFeedTokens.GapSm))
        Text(
            item.caption.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "（无文案）",
            color = TgFeedTokens.TextMedium,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
