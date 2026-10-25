package dev.tgfeed.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tgfeed.core.feed.ChannelWatchStat
import dev.tgfeed.core.feed.DayWatch
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.feed.FeedViewModelFactory
import dev.tgfeed.core.feed.WatchBadge
import dev.tgfeed.core.feed.WatchRange
import dev.tgfeed.core.feed.WatchRangeStats
import dev.tgfeed.core.feed.WatchRecordItem
import dev.tgfeed.core.feed.WatchReport
import dev.tgfeed.core.playback.ShareCard
import dev.tgfeed.core.store.LocalStore
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.ui.components.kit.AppDialog
import dev.tgfeed.ui.components.kit.AppEmptyState
import dev.tgfeed.ui.components.kit.AppLoading
import dev.tgfeed.ui.components.kit.AppPill
import dev.tgfeed.ui.components.kit.AppSectionTitle
import dev.tgfeed.ui.components.kit.AppTopBar
import dev.tgfeed.ui.components.kit.adaptiveContentWidth
import dev.tgfeed.ui.components.kit.avoidBottomBar
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 观看报告页："刷了多久、什么时候刷、爱看谁、爱看什么"。
 * 从设置页"存储与下载 → 观看报告"进入（路由由容器层接，与设置页同为状态驱动、不在返回栈里）。
 *
 * 页面结构（卡片流，卡片间 GapLg）：
 *  1. 范围切换：今日 / 本周 / 总计（kit AppPill 单选 chip 行）；
 *  2. 总览卡：范围内大数字 + 次数/完播率/高峰时段副信息 + 连续/累计观看天数；
 *  3. 观看人格卡（花样层头牌）：主人格 emoji 大字 + 一句调侃 + 至多两个副标签胶囊；
 *  4. 近 7 天观看趋势：柱状图，点选某日显示当日详情（与范围无关）；
 *  5. 近 12 周观看热力图：GitHub 式 12×7 格子（白阶→AccentRed），点格看当日详情；
 *  6. 最常看的频道 TOP5：排名 + 首字头像 + 占比条 + 时长/占比；
 *  7. 常看话题 TOP8：#话题 胶囊（caption 提取，按观看时长加权）；
 *  8. 内容偏好：视频/图文/音频堆叠比例条 + 图例；
 *  9. 最常观看时段：24 小时柱状图；
 *  10. "最"时刻卡：最晚观看 / 单日最长 / 单场最久 / 频道蝉联（3~4 条，不够排面的不上榜）；
 *  11. 成就徽章墙：整卡默认折叠成"成就总览"（已达成 N/M + 最近达成 4 枚横排预览，按首次达成
 *      时刻倒序，绝不摆固定前 4 个），点总览行才展开分组墙（rememberSaveable 记整卡折叠态）；
 *      展开后 200 枚按 category 分 14 个小节（kit AppSectionTitle 红竖条款标题），节内 4 列网格，
 *      达成点亮（AccentRed 描边）、未达成灰锁；超类（>8 枚）默认折叠只露达成的，点节标题展开，长按看达成条件；
 *  12. "分享我的观看报告"：视口底部固定条（不随滚动走），生成 Wrapped 风卡片图
 *      （ShareCard.generateReport）→ 系统分享面板。
 *  13. R53 "分享报告"：范围切换行尾部的描边胶囊（同 AppPill 风格），生成称号/勋章版分享卡
 *      （ShareCard.generateBadgeShare，1080×1920：称号 + 数据摘要 + 各类目最高级勋章精选）
 *      → 系统分享面板。隐私红线：全卡只有数字与称号/勋章，绝无频道名/内容标题/搜索词。
 * 6~9 随范围切换联动；范围内无数据时折叠为总览卡内一句提示；
 * 3/5/10/11 与两张分享卡为全局口径（事件表现存全部 ≈ 最近 300 次观看），不随范围切换。
 *
 * 视觉纪律：一屏最多 2 个强调色（AccentRed 主 = 选中态/峰值/连续天数；TgBlue 辅 = 占比条/话题/视频段），
 * 图表其余部分一律白阶；卡片 SurfaceHigh + RadiusLg；标题字阶走 MaterialTheme.typography。
 *
 * 数据源：vm.watchReport（FeedViewModel 从 watch_events 聚合，IO 线程一次算齐三档范围）。
 * 空态：事件表内没有任何观看事件时显示"还没有观看记录"。
 */
@Composable
fun WatchReportScreen(
    gateway: TdGateway,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as android.app.Application
    // 系统返回键 = 返回设置页（状态驱动路由，不拦会退到桌面）
    BackHandler(onBack = onBack)
    // 与 FeedScreen 共享 activity 作用域的同一个 ViewModel（watch_events 同源）
    val vm: FeedViewModel = viewModel(factory = FeedViewModelFactory(gateway, app), key = "gw" + System.identityHashCode(gateway))
    val report by vm.watchReport.collectAsState()
    // 进入页面触发一次重算（IO 线程聚合 watch_events，结果写回 watchReport）
    LaunchedEffect(Unit) { vm.refreshWatchReport() }
    // 范围切换是纯 UI 态：三档统计在 buildWatchReport 里一次算齐，切 Tab 不重算
    var range by remember { mutableStateOf(WatchRange.WEEK) }

    Column(Modifier.fillMaxSize()) {
        // 二级页统一顶栏（kit AppTopBar：返回圆钮 + 17sp SemiBold 标题，内建 statusBarsPadding）
        AppTopBar(title = "观看报告", onBack = onBack)
        // 大屏：内容列按 adaptiveContentWidth 三档限宽居中（与设置页同一规格）
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    // 自适应内容列宽必须在前：fillMaxSize 会先把约束钉成全宽，其后的限宽是死代码（P1-6 教训）
                    .adaptiveContentWidth()
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    // 底部避开手势条，最后一张卡片不贴边
                    .navigationBarsPadding(),
            ) {
                // ---------- 范围切换：今日 / 本周 / 总计（kit AppPill 单选 chip 行） ----------
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = TgFeedTokens.GapMd),
                    horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                ) {
                    // 注意：AppPill 末位参数是 icon（非函数类型），onClick 必须显式具名，不能尾随 lambda
                    AppPill("今日", selected = range == WatchRange.TODAY, onClick = { range = WatchRange.TODAY })
                    AppPill("本周", selected = range == WatchRange.WEEK, onClick = { range = WatchRange.WEEK })
                    AppPill("总计", selected = range == WatchRange.ALL, onClick = { range = WatchRange.ALL })
                    // R53 顶部"分享报告"入口：称号/勋章版分享卡，仅非空报告显示（空报告没东西可晒）
                    val shareRep = report
                    if (shareRep != null && !shareRep.isEmpty) {
                        Spacer(Modifier.weight(1f))
                        ShareBadgePill(shareRep)
                    }
                }

                val r = report
                when {
                    // 加载态：首次进入报告尚未算完（通常一帧内完成，兜底转圈）
                    r == null -> AppLoading(
                        text = "正在统计…",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 96.dp),
                    )
                    // 空态：事件表内没有任何观看事件（"总计"口径）
                    r.isEmpty -> AppEmptyState(
                        icon = Icons.Outlined.History,
                        title = "还没有观看记录",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 64.dp),
                        subtitle = "刷几条内容后再来看看",
                    )
                    else -> ReportContent(r, range)
                }

                // 末尾留白：给底部固定分享条让位（条高约 108dp，仅非空报告显示，见下）
                Spacer(Modifier.height(108.dp))
            }

            // ---------------- 底部固定分享条：Wrapped 报告卡图 → 系统分享面板（仅非空报告显示） ----------------
            // 固定在视口底部、不随滚动走——报告页加长后，分享入口不能再埋在卡片流末尾
            val rep = report
            if (rep != null && !rep.isEmpty) {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        // 顶部渐隐过渡：内容从条下滑过时不生硬
                        .background(
                            Brush.verticalGradient(
                                0f to TgFeedTokens.InkBlack.copy(alpha = 0f),
                                0.35f to TgFeedTokens.InkBlack,
                            ),
                        )
                        .navigationBarsPadding()
                        .avoidBottomBar() // R226 统一避让钥匙（二级页 inset 恒 0，类覆盖防回归）
                        .padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ShareReportButton(
                        rep,
                        Modifier
                            // 与上方内容列同宽：同一自适应限宽，平板上按钮列不与正文错轴
                            .adaptiveContentWidth()
                            .fillMaxWidth()
                            .padding(top = 28.dp, bottom = TgFeedTokens.GapMd),
                    )
                }
            }
        }
    }
}

/** 报告主体：总览 / 趋势 / Top 频道 / 话题 / 内容偏好 / 时段直方图 */
// FlowRow 仍是 ExperimentalLayoutApi（foundation 1.7），此处集中声明
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportContent(r: WatchReport, range: WatchRange) {
    // 当前范围的统计（空报告的 ranges 为空表，兜底空范围；正常路径必有该档）
    val stats = r.ranges[range] ?: WatchRangeStats()
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapLg),
    ) {
        // ---------------- 总览：大数字 + 副信息 + 连续/累计观看天数 ----------------
        OverviewCard(r, range, stats)

        // ---------------- 观看人格卡（花样层头牌，全局口径） ----------------
        PersonaCard(r)

        // ---------------- 近 7 天观看趋势：点选某日看详情（与范围无关，不放空态里） ----------------
        if (r.dailyTrend.isNotEmpty()) {
            ReportCard("近 7 天观看趋势") {
                DailyTrendChart(trend = r.dailyTrend)
            }
        }

        // ---------------- 近 12 周观看热力图（GitHub 式，全局口径） ----------------
        HeatmapCard(r.heatmap)

        if (stats.isEmpty) {
            // 范围内无数据（如"今日"还没看）：范围相关卡片折叠成一句提示，趋势卡照常显示
            ReportCard("${rangeLabel(range)}明细") {
                Text(
                    "该时间段还没有观看记录，切到其他范围看看",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TgFeedTokens.TextLow,
                )
            }
        } else {
            // ---------------- 最常看的频道 TOP5 ----------------
            if (stats.topChannels.isNotEmpty()) {
                ReportCard("最常看的频道") {
                    TopChannelRows(stats)
                }
            }
            // ---------------- 常看话题 TOP8（caption #话题 提取） ----------------
            if (stats.topTopics.isNotEmpty()) {
                ReportCard("常看话题") {
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                        verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                    ) {
                        stats.topTopics.forEach { TopicChip(it.tag, it.watchSec) }
                    }
                }
            }
            // ---------------- 内容偏好：比例条 + 图例 ----------------
            val typeTotal = stats.videoSec + stats.photoSec + stats.audioSec
            if (typeTotal > 0) {
                ReportCard("内容偏好") {
                    // 堆叠比例条：各段按观看秒数占比分宽（0 秒的类型不占位）
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill)),
                    ) {
                        if (stats.videoSec > 0) {
                            Box(
                                Modifier
                                    .weight(stats.videoSec.toFloat())
                                    .fillMaxHeight()
                                    .background(VideoColor),
                            )
                        }
                        if (stats.photoSec > 0) {
                            Box(
                                Modifier
                                    .weight(stats.photoSec.toFloat())
                                    .fillMaxHeight()
                                    .background(PhotoColor),
                            )
                        }
                        if (stats.audioSec > 0) {
                            Box(
                                Modifier
                                    .weight(stats.audioSec.toFloat())
                                    .fillMaxHeight()
                                    .background(AudioColor),
                            )
                        }
                    }
                    // 图例：色点 + 类型名 + 百分比 + 时长
                    TypeLegendRow("视频", VideoColor, stats.videoSec, typeTotal)
                    TypeLegendRow("图文", PhotoColor, stats.photoSec, typeTotal)
                    TypeLegendRow("音频", AudioColor, stats.audioSec, typeTotal)
                }
            }
            // ---------------- 最常观看时段：24 小时柱状图 ----------------
            ReportCard("最常观看时段") {
                HourHistogram(hours = stats.hourHistogram, peakHour = stats.peakHour)
            }
        }

        // ---------------- "最"时刻卡（全局口径；范围无数据时也照常显示） ----------------
        RecordsCard(r.records)

        // ---------------- 成就徽章行（全局口径；长按看达成条件） ----------------
        BadgesCard(r.badges)

        // 分享入口不在这里——底部固定分享条见 WatchReportScreen 页面结构（报告页拉长后，埋在卡片流末尾没人找得到）
    }
}

// ==================== 以下为报告页私有组件 ====================

/** 总览卡：范围内大数字 + 副信息行 + 连续/累计观看天数（天数与范围无关，全局口径） */
@Composable
private fun OverviewCard(r: WatchReport, range: WatchRange, stats: WatchRangeStats) {
    // 标题防叠字：rangeLabel(ALL) 是"总计"，直拼会出"总计总观看"，故"总计"档单独成词
    ReportCard(if (range == WatchRange.ALL) "总计观看" else "${rangeLabel(range)}总观看") {
        Text(
            formatWatchDuration(stats.totalSec),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = TgFeedTokens.TextHigh,
        )
        // 副信息行：次数 · 视频完播率 · 高峰时段（各段有数据才出现）
        if (!stats.isEmpty) {
            val subs = buildList {
                add("${stats.eventCount} 次观看")
                stats.completionRate?.let { add("视频完播率 %.0f%%".format(it * 100)) }
                if (stats.peakHour >= 0) {
                    add("高峰 ${stats.peakHour}:00–${(stats.peakHour + 1) % 24}:00")
                }
            }
            Text(
                subs.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = TgFeedTokens.TextMedium,
            )
        }
        // "总计"口径说明：事件表 300 条封顶，重度用户的"总计"只覆盖最近一段
        if (range == WatchRange.ALL) {
            Text(
                "覆盖最近 ${stats.eventCount} 次观看记录（事件表上限 ${LocalStore.MAX_WATCH_EVENTS} 条）",
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextLow,
            )
        }
        // 连续 / 累计观看天数：范围无关的全局指标，放总览卡底部一对小块
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
        ) {
            DayStatBlock(
                value = "${r.streakDays} 天",
                label = "连续观看",
                // 连续天数是本页唯一的"成就"指标，>0 时用主强调色点亮
                valueColor = if (r.streakDays > 0) TgFeedTokens.AccentRed else TgFeedTokens.TextHigh,
                modifier = Modifier.weight(1f),
            )
            DayStatBlock(
                value = "${r.activeDays} 天",
                label = "累计观看",
                valueColor = TgFeedTokens.TextHigh,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 总览卡内的天数小块：深一层底（Surface）+ 中圆角，数值 + 标签 */
@Composable
private fun DayStatBlock(
    value: String,
    label: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
            .background(TgFeedTokens.Surface)
            .padding(TgFeedTokens.GapMd),
        verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapXs),
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = TgFeedTokens.TextLow,
        )
    }
}

/** 近 7 天柱状图：点选某日在顶部显示当日详情；选中柱 AccentRed，其余白阶 */
@Composable
private fun DailyTrendChart(trend: List<DayWatch>) {
    // 选中日：默认今日（列表末位，buildWatchReport 约定升序且末位=今日）
    var selectedIdx by remember { mutableStateOf<Int?>(null) }
    val sel = selectedIdx?.takeIf { it in trend.indices } ?: trend.lastIndex
    val day = trend[sel]
    val maxV = trend.maxOf { it.watchSec }

    Column(verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapXs)) {
        // 选中日详情："10月12日 · 观看 36 分钟" / 无观看记录
        Text(
            "${formatDayLabel(day.dayStartMs)} · " +
                if (day.watchSec > 0) "观看 ${formatWatchDuration(day.watchSec)}" else "无观看记录",
            style = MaterialTheme.typography.bodySmall,
            color = TgFeedTokens.TextMedium,
        )
        // 柱区：固定 96dp 高，整列可点（含空白处），柱从底部向上生长
        Row(
            Modifier
                .fillMaxWidth()
                .height(96.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            trend.forEachIndexed { i, d ->
                val frac = if (maxV > 0) d.watchSec.toFloat() / maxV.toFloat() else 0f
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { selectedIdx = i },
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(if (d.watchSec > 0) (72 * frac).dp.coerceAtLeast(4.dp) else 2.dp)
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(
                                when {
                                    i == sel -> TgFeedTokens.AccentRed // 选中日：主强调
                                    d.watchSec > 0 -> TgFeedTokens.TextHigh.copy(alpha = 0.30f)
                                    else -> TgFeedTokens.TextHigh.copy(alpha = 0.10f) // 0 值底柱保节律
                                },
                            ),
                    )
                }
            }
        }
        // 星期刻度：与柱一一对位，今日标"今"
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            trend.forEachIndexed { i, d ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        if (i == trend.lastIndex) "今" else weekdayLabel(d.dayStartMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (i == sel) TgFeedTokens.TextHigh else TgFeedTokens.TextLow,
                    )
                }
            }
        }
    }
}

/** 最常看的频道 TOP5：排名 + 首字头像 + 名称 + 占比条 + 时长/占比 */
@Composable
private fun ColumnScope.TopChannelRows(stats: WatchRangeStats) {
    val maxSec = stats.topChannels.maxOf { it.watchSec } // 占比条相对第 1 名归一
    stats.topChannels.forEachIndexed { i, ch ->
        ChannelRankRow(rank = i + 1, ch = ch, maxSec = maxSec, totalSec = stats.totalSec)
    }
}

/** 单个频道行 */
@Composable
private fun ChannelRankRow(rank: Int, ch: ChannelWatchStat, maxSec: Long, totalSec: Long) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
    ) {
        // 排名：第 1 名主强调色，其余弱文字
        Text(
            "$rank",
            style = MaterialTheme.typography.labelLarge,
            color = if (rank == 1) TgFeedTokens.AccentRed else TgFeedTokens.TextLow,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(20.dp),
        )
        // 首字头像：无频道封面数据，用"首字符圆托"（Telegram 默认头像的极简化）
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(TgFeedTokens.TextHigh.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                ch.title.trim().take(1).ifEmpty { "?" },
                style = MaterialTheme.typography.titleSmall,
                color = TgFeedTokens.TextHigh,
            )
        }
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapXs),
        ) {
            Text(
                ch.title,
                style = MaterialTheme.typography.bodyMedium,
                color = TgFeedTokens.TextHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 占比条：3dp 细条，相对第 1 名归一，TgBlue（辅助强调色）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                    .background(TgFeedTokens.TextHigh.copy(alpha = 0.08f)),
            ) {
                val frac = if (maxSec > 0) ch.watchSec.toFloat() / maxSec.toFloat() else 0f
                Box(
                    Modifier
                        .fillMaxWidth(frac)
                        .fillMaxHeight()
                        .background(TgFeedTokens.TgBlue),
                )
            }
        }
        // 尾部：时长 + 占范围总时长百分比
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatWatchDuration(ch.watchSec),
                style = MaterialTheme.typography.labelMedium,
                color = TgFeedTokens.TextHigh,
            )
            val percent = if (totalSec > 0) ch.watchSec.toFloat() * 100f / totalSec else 0f
            Text(
                "%.0f%%".format(percent),
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextLow,
            )
        }
    }
}

/** 话题胶囊：纯展示不可点（kit AppPill 文档口径），#tag 用 TgBlue、时长弱文字 */
@Composable
private fun TopicChip(tag: String, watchSec: Long) {
    Row(
        Modifier
            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
            .background(TgFeedTokens.OverlayBg)
            .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusPill))
            .padding(horizontal = TgFeedTokens.GapMd, vertical = TgFeedTokens.GapSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapXs),
    ) {
        Text(
            "#$tag",
            style = MaterialTheme.typography.labelLarge,
            color = TgFeedTokens.TgBlue,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 话题名限宽：单个超长话题不把胶囊撑出卡片
            modifier = Modifier.widthIn(max = 160.dp),
        )
        Text(
            formatWatchDuration(watchSec),
            style = MaterialTheme.typography.labelSmall,
            color = TgFeedTokens.TextLow,
        )
    }
}

/** 分组卡片：RadiusLg 圆角 + SurfaceHigh 底，组标题在卡片内顶部小字标签（与设置页 SettingsCard 同一语言） */
@Composable
private fun ReportCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TgFeedTokens.RadiusLg))
            .background(TgFeedTokens.SurfaceHigh)
            .padding(TgFeedTokens.GapLg),
        verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = TgFeedTokens.TextLow,
        )
        content()
    }
}

/** 类型图例行：色点 + 类型名 + 右侧"百分比 · 时长" */
@Composable
private fun TypeLegendRow(label: String, color: Color, sec: Long, totalSec: Long) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = TgFeedTokens.TextHigh,
            modifier = Modifier.weight(1f),
        )
        val percent = if (totalSec > 0) sec.toFloat() * 100f / totalSec else 0f
        Text(
            "%.0f%% · %s".format(percent, formatWatchDuration(sec)),
            style = MaterialTheme.typography.bodySmall,
            color = TgFeedTokens.TextMedium,
        )
    }
}

/** 24 小时柱状图：Box 高度比例自绘（免 Canvas），峰值时段主强调色，其余白阶；底部标注 0/6/12/18/23 点 */
@Composable
private fun HourHistogram(hours: List<Long>, peakHour: Int) {
    val maxV = hours.maxOrNull() ?: 0L
    Column(verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapXs)) {
        // 柱区：固定 96dp 高，柱从底部向上生长；0 值留 2dp 低透明底柱保持节律
        Row(
            Modifier
                .fillMaxWidth()
                .height(96.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            hours.forEachIndexed { i, v ->
                val frac = if (maxV > 0) v.toFloat() / maxV.toFloat() else 0f
                Box(
                    Modifier
                        .weight(1f)
                        .height(if (v > 0) (80 * frac).dp.coerceAtLeast(4.dp) else 2.dp)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(
                            when {
                                i == peakHour -> TgFeedTokens.AccentRed // 峰值：主强调
                                v > 0 -> TgFeedTokens.TextHigh.copy(alpha = 0.30f)
                                else -> TgFeedTokens.TextHigh.copy(alpha = 0.10f)
                            },
                        ),
                )
            }
        }
        // 小时刻度：与柱一一对位，只标 0/6/12/18/23 点
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            hours.indices.forEach { i ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (i == 0 || i == 6 || i == 12 || i == 18 || i == 23) {
                        Text(
                            "$i",
                            style = MaterialTheme.typography.labelSmall,
                            color = TgFeedTokens.TextLow,
                        )
                    }
                }
            }
        }
    }
}

// ==================== 花样层组件（人格 / 热力图 / 最时刻 / 徽章 / 分享） ====================

/** 观看人格卡（花样层头牌）：主人格 emoji + 大字 + 一句调侃文案，下面跟至多两个副标签胶囊 */
@Composable
private fun PersonaCard(r: WatchReport) {
    val persona = r.persona ?: return
    ReportCard("我的观看人格") {
        Text(
            "${persona.emoji} ${persona.title}",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = TgFeedTokens.TextHigh,
        )
        Text(
            persona.tagline,
            style = MaterialTheme.typography.bodyMedium,
            color = TgFeedTokens.TextMedium,
        )
        // 副标签：纯展示胶囊（白阶描边 + 弱文字），不抢主人格的戏
        if (r.personaSubs.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
                r.personaSubs.forEach { sub ->
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                            .background(TgFeedTokens.OverlayBg)
                            .border(
                                0.5.dp,
                                TgFeedTokens.OverlayHairline,
                                RoundedCornerShape(TgFeedTokens.RadiusPill),
                            )
                            .padding(horizontal = TgFeedTokens.GapMd, vertical = TgFeedTokens.GapXs),
                    ) {
                        Text(
                            "${sub.emoji} ${sub.title}",
                            style = MaterialTheme.typography.labelMedium,
                            color = TgFeedTokens.TextMedium,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 近 12 周观看热力图：12 列（周）× 7 行（周一在上，与"本周一"口径一致），
 * 颜色深度 = 当天观看时长（白阶 → AccentRed 相对峰值分 4 档）；点格在顶部看当日详情。
 */
@Composable
private fun HeatmapCard(heatmap: List<DayWatch>) {
    if (heatmap.isEmpty()) return
    // 选中日：默认最近一个有观看的日子，都没有则今天（列表末位）
    var selectedMs by remember { mutableStateOf<Long?>(null) }
    val maxSec = heatmap.maxOf { it.watchSec.coerceAtLeast(0) }
    val sel = heatmap.firstOrNull { it.dayStartMs == selectedMs }
        ?: heatmap.lastOrNull { it.watchSec > 0 }
        ?: heatmap.last()
    ReportCard("近 12 周观看热力") {
        // 选中日详情："10月12日 · 观看 36 分钟" / 无观看记录 / 还没到这天（未来占位格）
        Text(
            "${formatDayLabel(sel.dayStartMs)} · " + when {
                sel.watchSec > 0 -> "观看 ${formatWatchDuration(sel.watchSec)}"
                sel.watchSec == 0L -> "无观看记录"
                else -> "还没到这天"
            },
            style = MaterialTheme.typography.bodySmall,
            color = TgFeedTokens.TextMedium,
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 3.dp
            val cell = (maxWidth - gap * 11) / 12
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                // 84 格按周切列：每列一周（7 天，周一在上）
                for (w in 0 until 12) {
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        for (dow in 0 until 7) {
                            val day = heatmap.getOrNull(w * 7 + dow) ?: continue
                            Box(
                                Modifier
                                    .size(cell)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(heatCellColor(day.watchSec, maxSec))
                                    .clickable { selectedMs = day.dayStartMs },
                            )
                        }
                    }
                }
            }
        }
        Text(
            "每格一天（周一在上），颜色越深看得越久",
            style = MaterialTheme.typography.labelSmall,
            color = TgFeedTokens.TextLow,
        )
    }
}

/** 热力格配色：未来日更暗、无观看白阶底，其余按当日相对峰值分 4 档加深到 AccentRed（与分享卡热力同口径） */
private fun heatCellColor(sec: Long, maxSec: Long): Color {
    if (sec < 0) return TgFeedTokens.TextHigh.copy(alpha = 0.04f) // 未来日占位
    if (sec <= 0 || maxSec <= 0) return TgFeedTokens.TextHigh.copy(alpha = 0.08f) // 无观看
    val frac = sec.toFloat() / maxSec.toFloat()
    val alpha = when {
        frac < 0.25f -> 0.30f
        frac < 0.50f -> 0.50f
        frac < 0.75f -> 0.75f
        else -> 1f
    }
    return TgFeedTokens.AccentRed.copy(alpha = alpha)
}

/** "最"时刻卡：两列小块网格（大数字 + 指标名 + 日期/频道副信息），3~4 条由数据决定 */
@Composable
private fun RecordsCard(records: List<WatchRecordItem>) {
    if (records.isEmpty()) return
    ReportCard("「最」时刻") {
        records.chunked(2).forEach { rowItems ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
            ) {
                rowItems.forEach { RecordBlock(it, Modifier.weight(1f)) }
                // 单数行末尾补位，保持两列对齐
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** "最"时刻小块：与 DayStatBlock 同一语言（深一层底 + 中圆角），多一行弱副信息 */
@Composable
private fun RecordBlock(item: WatchRecordItem, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
            .background(TgFeedTokens.Surface)
            .padding(TgFeedTokens.GapMd),
        verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapXs),
    ) {
        Text(
            item.value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TgFeedTokens.TextHigh,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(item.label, style = MaterialTheme.typography.labelSmall, color = TgFeedTokens.TextLow)
        Text(
            item.sub,
            style = MaterialTheme.typography.labelSmall,
            color = TgFeedTokens.TextLow,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 徽章分组小节超过该枚数时默认折叠（折叠态只露达成的；点节标题展开/收起） */
private const val BADGE_COLLAPSE_THRESHOLD = 8

/** 总览卡"最近达成"预览枚数（横排 4 枚，与墙内网格同列宽语言） */
private const val BADGE_PREVIEW_COUNT = 4

/** 徽章"首次观测到达成"时刻表的持久化 key（复用 tgfeed 主配置文件，随数据迁移备份随迁） */
private const val BADGE_ACHIEVED_MS_KEY = "badge_first_achieved_ms"

/**
 * 成就徽章墙：整卡默认折叠成"成就总览"（已达成 N/M + 最近达成 4 枚横排预览），
 * 点总览行才展开分组墙——200 枚分 14 组的整墙不再无限拉长报告页。
 * 整卡折叠态用 rememberSaveable 记（子页往返/旋转后保持用户选择）。
 * 展开后：按 category 分小节（kit AppSectionTitle 红竖条款标题），节内 4 列网格；
 * 达成点亮（AccentRed 描边圆托）、未达成灰锁（emoji 降透明 + 弱文字）。
 * 超类（> [BADGE_COLLAPSE_THRESHOLD] 枚）默认折叠只露达成的——达成的永远可见；
 * 分组折叠态用 remember 记（报告重算后按新分组重置），不引入新状态库。
 * 长按（或点按）弹窗展示达成条件；预览区的徽章同样可点看条件。
 */
@Composable
private fun BadgesCard(badges: List<WatchBadge>) {
    if (badges.isEmpty()) return
    var detail by remember { mutableStateOf<WatchBadge?>(null) }
    val context = LocalContext.current
    // 整卡折叠态：默认折叠只露成就总览（用户投诉"一溜子排太长"的收纳点）
    var wallExpanded by rememberSaveable { mutableStateOf(false) }
    // 达成时间追踪：徽章模型不带达成时间戳，本地记录每枚徽章"首次观测到达成"的时刻
    // （见 recordBadgeAchievements），作为"最近达成"预览的排序依据
    var achievedMs by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(badges) { achievedMs = recordBadgeAchievements(context, badges) }
    // 最近达成预览：按首达成时刻倒序取前 4；同刻（首次启用本功能时同批打戳）
    // 按列表倒序 tie-break——同类目内高阈值徽章排在后，现实中也更晚达成。绝不摆固定前 4 个
    val recentAchieved = remember(badges, achievedMs) {
        badges.mapIndexedNotNull { idx, b -> if (b.achieved) Triple(b, achievedMs[b.id] ?: 0L, idx) else null }
            .sortedWith(
                compareByDescending<Triple<WatchBadge, Long, Int>> { it.second }
                    .thenByDescending { it.third },
            )
            .take(BADGE_PREVIEW_COUNT)
            .map { it.first }
    }
    val achievedTotal = badges.count { it.achieved }
    // 分组：groupBy 底层 LinkedHashMap 保序（类目顺序 = ViewModel 定义顺序）；组内"达成的在前"已在 VM 排好
    val groups = remember(badges) { badges.groupBy { it.category } }
    // 默认折叠集 = 超类（枚数 > 阈值）的类目名；点节标题在集合里加/减切换
    var collapsed by remember(badges) {
        mutableStateOf(groups.filterValues { it.size > BADGE_COLLAPSE_THRESHOLD }.keys)
    }
    ReportCard("成就徽章") {
        // 总览行（恒显）：红竖条款达成计数 + 右端 展开/收起；整行可点切换整卡折叠态
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
                .clickable { wallExpanded = !wallExpanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppSectionTitle(
                "成就总览 · 已达成 $achievedTotal/${badges.size}",
                Modifier.weight(1f),
            )
            Text(
                if (wallExpanded) "收起" else "展开",
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextLow,
            )
            Icon(
                if (wallExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (wallExpanded) "收起" else "展开",
                tint = TgFeedTokens.TextLow,
                modifier = Modifier.size(TgFeedTokens.IconMd),
            )
        }
        if (!wallExpanded) {
            // 折叠态：只露最近达成的 4 枚横排预览（点亮样式，可点看条件）
            if (recentAchieved.isEmpty()) {
                Text(
                    "还没有达成徽章，点上方展开看看条件",
                    style = MaterialTheme.typography.bodySmall,
                    color = TgFeedTokens.TextLow,
                )
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                ) {
                    recentAchieved.forEach { b ->
                        BadgeItem(b, Modifier.weight(1f)) { detail = b }
                    }
                }
                Text(
                    "最近达成预览，点上方展开查看全部 ${groups.size} 组徽章",
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextLow,
                )
            }
        } else {
        groups.forEach { (category, list) ->
            val achievedCount = list.count { it.achieved }
            val collapsible = list.size > BADGE_COLLAPSE_THRESHOLD
            val isCollapsed = category in collapsed
            // 小节标题行：红竖条款 + 达成计数；超类可点展开，尾部带 展开/收起 弱提示
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = collapsible) {
                        collapsed = if (isCollapsed) collapsed - category else collapsed + category
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppSectionTitle(
                    "$category · $achievedCount/${list.size}",
                    Modifier.weight(1f),
                )
                if (collapsible) {
                    Text(
                        if (isCollapsed) "展开" else "收起",
                        style = MaterialTheme.typography.labelSmall,
                        color = TgFeedTokens.TextLow,
                    )
                }
            }
            // 折叠态只露达成的；一个都没达成时给一句引导，不渲染空网格
            val visible = if (isCollapsed) list.filter { it.achieved } else list
            if (visible.isEmpty()) {
                Text(
                    "这一组还没有达成，点标题展开看看条件",
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextLow,
                )
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val gap = TgFeedTokens.GapSm
                    val itemW = (maxWidth - gap * 3) / 4
                    Column(verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd)) {
                        visible.chunked(4).forEach { rowBadges ->
                            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                rowBadges.forEach { b ->
                                    BadgeItem(b, Modifier.width(itemW)) { detail = b }
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(
            "长按徽章查看达成条件；徽章多的分组默认折叠，点标题展开",
            style = MaterialTheme.typography.labelSmall,
            color = TgFeedTokens.TextLow,
        )
        } // else（整卡展开态）
    }
    // 达成条件弹窗（kit AppDialog 统一壳）
    detail?.let { b ->
        AppDialog(
            onDismissRequest = { detail = null },
            title = "${if (b.achieved) "已达成" else "未达成"} · ${b.name}",
            confirmText = "知道了",
            onConfirm = { detail = null },
        ) {
            Text(
                b.condition,
                style = MaterialTheme.typography.bodyMedium,
                color = TgFeedTokens.TextMedium,
            )
        }
    }
}

/** 单个徽章：圆形 emoji 托 + 名称；达成 = AccentRed 描边点亮，未达成 = 灰锁（emoji 降透明） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BadgeItem(badge: WatchBadge, modifier: Modifier = Modifier, onPress: () -> Unit) {
    Column(
        modifier.combinedClickable(
            onClick = onPress, // 点按也出条件（照顾没发现长按的用户）
            onLongClick = onPress,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapXs),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (badge.achieved) TgFeedTokens.AccentRed.copy(alpha = 0.12f)
                    else TgFeedTokens.TextHigh.copy(alpha = 0.06f),
                )
                .border(
                    width = if (badge.achieved) 1.5.dp else 0.5.dp,
                    color = if (badge.achieved) TgFeedTokens.AccentRed else TgFeedTokens.OverlayHairline,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            // emoji 直渲（字号随 titleLarge ≈22sp，居中于 48dp 圆托）；未达成整体降透明
            Text(
                badge.emoji,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.alpha(if (badge.achieved) 1f else 0.35f),
            )
        }
        Text(
            badge.name,
            style = MaterialTheme.typography.labelSmall,
            color = if (badge.achieved) TgFeedTokens.TextHigh else TgFeedTokens.TextLow,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 记录/读取徽章"首次观测到达成"的时刻表（WatchBadge 模型本身不带达成时间戳，
 * 而"最近达成"预览需要排序依据）：已达成但未有记录的徽章补上"现在"，返回全量表。
 * 存储复用 AppPrefs 的 tgfeed 主配置文件（BackupManager 整目录备份 shared_prefs，
 * 天然随数据迁移随迁），格式 "id:毫秒,id:毫秒"——徽章 id 为纯字母数字+下划线，
 * 不含分隔符，单向解析安全。单条坏记录只跳过该条（LocalStore 同款防御口径）。
 */
private fun recordBadgeAchievements(context: Context, badges: List<WatchBadge>): Map<String, Long> {
    val sp = context.getSharedPreferences("tgfeed", Context.MODE_PRIVATE)
    val recorded = LinkedHashMap<String, Long>()
    sp.getString(BADGE_ACHIEVED_MS_KEY, "").orEmpty().split(',').forEach { seg ->
        val i = seg.lastIndexOf(':')
        if (i > 0) {
            val ms = seg.substring(i + 1).toLongOrNull()
            if (ms != null) recorded[seg.substring(0, i)] = ms
        }
    }
    val now = System.currentTimeMillis()
    var changed = false
    badges.forEach { b ->
        if (b.achieved && b.id !in recorded) {
            recorded[b.id] = now
            changed = true
        }
    }
    if (changed) {
        sp.edit()
            .putString(
                BADGE_ACHIEVED_MS_KEY,
                recorded.entries.joinToString(",") { "${it.key}:${it.value}" },
            )
            .apply()
    }
    return recorded
}

/**
 * "分享我的观看报告"主按钮（渲染在页面底部固定条里，见 WatchReportScreen 结构）：
 * IO 线程生成 Wrapped 风卡图（ShareCard.generateReport）→ 存公共相册 → 系统分享面板
 * （ShareCard.shareImage）；生成中按钮降透明防重入。
 */
@Composable
private fun ShareReportButton(r: WatchReport, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
            .background(if (sharing) TgFeedTokens.AccentRed.copy(alpha = 0.5f) else TgFeedTokens.AccentRed)
            .clickable(enabled = !sharing) {
                scope.launch {
                    sharing = true
                    try {
                        // 生成 + 存相册都是重活，整体放 IO；Toast/起分享面板由 ShareCard 内部切回主线程
                        withContext(Dispatchers.IO) {
                            val card = ShareCard.generateReport(buildReportCardData(r))
                            ShareCard.shareImage(context, card)
                        }
                    } finally {
                        sharing = false
                    }
                }
            }
            .padding(vertical = TgFeedTokens.GapMd),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
        ) {
            Icon(
                imageVector = Icons.Outlined.Share,
                contentDescription = null,
                modifier = Modifier.size(TgFeedTokens.IconMd),
                tint = TgFeedTokens.TextHigh,
            )
            Text(
                if (sharing) "正在生成卡片…" else "分享我的观看报告",
                style = MaterialTheme.typography.labelLarge,
                color = TgFeedTokens.TextHigh,
            )
        }
    }
}

/**
 * "分享报告"顶部入口（R53，渲染在范围切换行尾部，描边胶囊与 AppPill 同一语言）：
 * IO 线程生成称号/勋章版分享卡（ShareCard.generateBadgeShare，1080×1920）→ 存公共相册
 * → 系统分享面板（ShareCard.shareImage）；生成中文案变"生成中…"并拦截重入。
 */
@Composable
private fun ShareBadgePill(r: WatchReport) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }
    AppPill(
        text = if (sharing) "生成中…" else "分享报告",
        selected = false,
        onClick = {
            if (sharing) return@AppPill
            scope.launch {
                sharing = true
                try {
                    // 生成 + 存相册都是重活，整体放 IO；Toast/起分享面板由 ShareCard 内部切回主线程
                    withContext(Dispatchers.IO) {
                        val card = ShareCard.generateBadgeShare(buildBadgeShareData(r))
                        ShareCard.shareImage(context, card)
                    }
                } finally {
                    sharing = false
                }
            }
        },
        icon = Icons.Outlined.Share,
    )
}

/**
 * WatchReport → 称号勋章分享卡数据（R53）。
 * 隐私红线：只投影数字与称号/勋章，绝不带频道名/内容标题/搜索词。
 * 勋章精选"挑最高级"：badges 已按类目分组、组内达成的在前（阈值升序），
 * 故每个类目已达成的最后一枚 = 该类目当前最高级；跨类目各取一枚，凑至多 6 枚（2 行 × 3 列）。
 */
private fun buildBadgeShareData(r: WatchReport): ShareCard.BadgeShareData {
    val all = r.ranges[WatchRange.ALL] ?: WatchRangeStats()
    val persona = r.persona
    val topBadges = r.badges.filter { it.achieved }
        .groupBy { it.category }
        .values.mapNotNull { it.lastOrNull() }
        .take(6)
        .map { it.emoji to it.name }
    return ShareCard.BadgeShareData(
        personaTitle = persona?.let { "${it.emoji} ${it.title}" } ?: "🐣 见习刷手",
        personaTagline = persona?.tagline.orEmpty(),
        totalText = formatWatchDuration(all.totalSec),
        eventCount = all.eventCount,
        streakDays = r.streakDays,
        activeDays = r.activeDays,
        badgeAchieved = r.badges.count { it.achieved },
        badgeTotal = r.badges.size,
        topBadges = topBadges,
        // formatDayLabel 只读月/日字段，直接喂当前时刻即可
        brandLine = "TGFeed · 观看报告 · ${formatDayLabel(System.currentTimeMillis())}",
    )
}

/** WatchReport → 分享卡数据（"总计"口径 + 主人格 + TOP3 频道 + 热力图缩略；时长预先格式化） */
private fun buildReportCardData(r: WatchReport): ShareCard.ReportCardData {
    val all = r.ranges[WatchRange.ALL] ?: WatchRangeStats()
    val persona = r.persona
    return ShareCard.ReportCardData(
        personaTitle = persona?.let { "${it.emoji} ${it.title}" } ?: "🐣 见习刷手",
        personaTagline = persona?.tagline.orEmpty(),
        totalText = formatWatchDuration(all.totalSec),
        streakDays = r.streakDays,
        activeDays = r.activeDays,
        topChannels = all.topChannels.take(3).map { it.title to formatWatchDuration(it.watchSec) },
        heatmapSec = r.heatmap.map { it.watchSec },
        // formatDayLabel 只读月/日字段，直接喂当前时刻即可
        brandLine = "TGFeed · 观看报告 · ${formatDayLabel(System.currentTimeMillis())}",
    )
}

// ==================== 以下为报告页私有工具 ====================

/** 范围名（分段选择器与卡片标题共用） */
private fun rangeLabel(range: WatchRange): String = when (range) {
    WatchRange.TODAY -> "今日"
    WatchRange.WEEK -> "本周"
    WatchRange.ALL -> "总计"
}

/** 日界毫秒 → "M月d日" */
private fun formatDayLabel(dayStartMs: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = dayStartMs }
    return "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日"
}

/** 日界毫秒 → 星期单字（"日一二三四五六"） */
private fun weekdayLabel(dayStartMs: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = dayStartMs }
    // DAY_OF_WEEK：周日=1、周一=2…周六=7
    return "日一二三四五六".substring(cal.get(Calendar.DAY_OF_WEEK) - 1, cal.get(Calendar.DAY_OF_WEEK))
}

/** 观看时长人性化：≥1h 显示"X 小时 X 分钟"，≥1min 显示"X 分钟"，否则"X 秒" */
private fun formatWatchDuration(sec: Long): String {
    val s = sec.coerceAtLeast(0)
    val h = s / 3600
    val m = s % 3600 / 60
    return when {
        h > 0 -> "$h 小时 $m 分钟"
        m > 0 -> "$m 分钟"
        else -> "$s 秒"
    }
}

/** 内容偏好分段配色：一屏最多 2 个强调色——视频用 TgBlue（辅），图文/音频用白阶（不再用绿/橙） */
private val VideoColor = TgFeedTokens.TgBlue
private val PhotoColor = TgFeedTokens.TextHigh
private val AudioColor = TgFeedTokens.TextHigh.copy(alpha = 0.35f)
