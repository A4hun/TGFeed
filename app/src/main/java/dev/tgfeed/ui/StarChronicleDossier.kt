package dev.tgfeed.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tgfeed.core.tastemap.ChannelEraPolicy
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.ui.theme.MotionTokens
import dev.tgfeed.ui.theme.TgFeedTokens
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * 星图信息卡「展开档案」编年史呈现层（R416）。
 * 取数层沿用 loadChronicleVolumes；此处只负责呈现：
 * 顶部贯穿时间轴 + 下方结论先行的卷宗卡横向分页。
 */

private const val CardWidthDp = 280
private const val CardHeightDp = 320
private const val TimelineHeightDp = 92

// R429 动效红线（PursueScreen 具名场景常数先例）：入场/扫光的 tween 时长一律走
// 换算式具名常数——tween 时长实参不许含裸数字（AnimMed*2 入场、AnimMed*4 光带扫掠）
private val DossierRevealDurMs = TgFeedTokens.AnimMed * 2
private val DossierSweepDurMs = TgFeedTokens.AnimMed * 4

private fun monthIndex(year: Int, month1: Int): Int = year * 12 + (month1 - 1)

private fun epochDayToMonthPos(epochDay: Int): Float {
    val d = LocalDate.ofEpochDay(epochDay.toLong())
    return monthIndex(d.year, d.monthValue) + (d.dayOfMonth - 1f) / d.lengthOfMonth()
}

private fun ymLabel(mi: Int): String = "${mi / 12}.${(mi % 12 + 1).toString().padStart(2, '0')}"

private fun dayLabel(epochDay: Int): String {
    val d = LocalDate.ofEpochDay(epochDay.toLong())
    return "${d.year}.${d.monthValue.toString().padStart(2, '0')}.${d.dayOfMonth.toString().padStart(2, '0')}"
}

private fun secLabel(sec: Long): String {
    val d = Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault()).toLocalDate()
    return "${d.year}.${d.monthValue.toString().padStart(2, '0')}.${d.dayOfMonth.toString().padStart(2, '0')}"
}

/** 时间轴模型：月份序列（首发→今天，空月补零）、巅峰月、沉寂段与复活点。 */
private class DossierTimeline(
    val startMi: Int,
    val endMi: Int,
    val counts: IntArray,
    val peakMi: Int,
    val peakCount: Int,
    val gaps: List<Pair<Float, Float>>,
    val revivals: List<Float>,
)

private fun buildTimeline(
    monthly: List<ChannelEraPolicy.MonthCount>,
    dormant: List<DormantRow>,
): DossierTimeline? {
    if (monthly.isEmpty()) return null
    val byMi = HashMap<Int, Int>(monthly.size * 2)
    for (m in monthly) byMi[monthIndex(m.year, m.month1)] = (byMi[monthIndex(m.year, m.month1)] ?: 0) + m.count
    val startMi = byMi.keys.min()
    val now = LocalDate.now()
    val endMi = maxOf(byMi.keys.max(), monthIndex(now.year, now.monthValue))
    val counts = IntArray(endMi - startMi + 1) { byMi[startMi + it] ?: 0 }
    var peakIdx = 0
    for (i in counts.indices) if (counts[i] > counts[peakIdx]) peakIdx = i
    val gaps = dormant.map { epochDayToMonthPos(it.h.lastPostDay) to epochDayToMonthPos(it.h.returnDay) }
    val revivals = dormant.filter { it.h.returned }.map { epochDayToMonthPos(it.h.returnDay) }
    return DossierTimeline(startMi, endMi, counts, startMi + peakIdx, counts[peakIdx], gaps, revivals)
}

@Composable
internal fun StarChronicleDossier(
    st: StarNode,
    chronicleOpen: Boolean,
    chron: ChannelEraPolicy.EraDigest?,
    chronicleLoading: Boolean,
    chronVolumes: ChronicleVolumes?,
    neighborRels: List<NeighborRel>,
    starsById: Map<Long, StarNode>,
    gateway: TdGateway,
    onFlyStar: (StarNode) -> Unit,
    onSelectStar: (Long?) -> Unit,
    onPlayDust: (item: VideoItem, batch: List<VideoItem>, label: String) -> Unit,
) {
    var interlinkOpen by remember(st.chatId) { mutableStateOf(true) }
    AnimatedVisibility(visible = chronicleOpen) {
        Column(verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd)) {
            val rels = neighborRels.filter { it.total > 0 }
            Text(
                "编年档案",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = TgFeedTokens.TextHigh,
            )
            if (chronicleLoading) {
                Text("档案整理中…", style = MaterialTheme.typography.labelSmall, color = TgFeedTokens.TextLow)
                return@Column
            }
            val volumes = chronVolumes
            val cards = ArrayList<@Composable () -> Unit>()
            if (chron == null) {
                Text("记录不足，暂无法编年", style = MaterialTheme.typography.labelSmall, color = TgFeedTokens.TextLow)
            } else {
                val tl = remember(chron, volumes) { buildTimeline(chron.monthly, volumes?.dormant.orEmpty()) }
                if (tl != null) DossierTimelineBand(tl)

                val playWorks = volumes?.works?.mapNotNull { it.item }.orEmpty()
                val playMs = volumes?.milestones?.mapNotNull { it.item }.orEmpty()
                val playReturns = volumes?.dormant?.mapNotNull { it.returnItem }.orEmpty()

                // ① 总览卷
                cards += {
                    val monthsActive = tl?.counts?.count { it > 0 } ?: 0
                    DossierCard(
                        tag = "总览",
                        accent = TgFeedTokens.TgBlue,
                        verdict = "共 ${chron.totalCount} 件 · 覆盖 ${chron.coverageDays} 天",
                        evidence = buildList {
                            if (tl != null) add("首发 ${ymLabel(tl.startMi)} · 活跃 ${monthsActive} 个月")
                            chron.verdict?.let { add(it) }
                            chron.orientation?.let { add(it) }
                            chron.trendLabel?.let { add(it) }
                        },
                    ) {}
                }
                // ② 巅峰卷
                if (tl != null && tl.peakCount > 0) {
                    cards += {
                        val avg = if (tl.counts.isNotEmpty()) tl.counts.sum().toFloat() / tl.counts.size else 0f
                        DossierCard(
                            tag = "巅峰",
                            accent = TgFeedTokens.StarYellow,
                            verdict = "巅峰 ${tl.peakCount} 件",
                            evidence = buildList {
                                add("峰值月 ${ymLabel(tl.peakMi)} · 月均 ${"%.1f".format(avg)} 件")
                                chron.peakLabel?.let { add(it) }
                            },
                        ) {
                            volumes?.works.orEmpty().forEach { row ->
                                DossierEntry(
                                    title = row.work.headline,
                                    sub = "${secLabel(row.work.dateSec)} · ${row.work.era} · ${row.work.reason}",
                                    accent = TgFeedTokens.StarYellow,
                                    playable = row.item != null,
                                ) {
                                    val item = row.item ?: return@DossierEntry
                                    onPlayDust(item, playWorks, "${st.title} · 巅峰作品")
                                }
                            }
                        }
                    }
                }
                // ③ 里程碑卷
                if (volumes != null && volumes.milestones.isNotEmpty()) {
                    cards += {
                        val first = volumes.milestones.minOf { it.ms.dateSec }
                        val last = volumes.milestones.maxOf { it.ms.dateSec }
                        DossierCard(
                            tag = "里程碑",
                            accent = TgFeedTokens.TgBlue,
                            verdict = "里程碑 ${volumes.milestones.size} 个",
                            evidence = listOf("${secLabel(first)} → ${secLabel(last)}"),
                        ) {
                            volumes.milestones.forEach { row ->
                                DossierEntry(
                                    title = row.ms.label,
                                    sub = listOfNotNull(secLabel(row.ms.dateSec), row.ms.sub).joinToString(" · "),
                                    accent = TgFeedTokens.TgBlue,
                                    playable = row.item != null,
                                ) {
                                    val item = row.item ?: return@DossierEntry
                                    onPlayDust(item, playMs, "${st.title} · 里程碑")
                                }
                            }
                        }
                    }
                }
                // ④ 沉寂与复活卷
                if (volumes != null && (volumes.dormant.isNotEmpty() || volumes.shortPauses > 0)) {
                    cards += {
                        val longest = volumes.dormant.maxOfOrNull { it.h.days } ?: 0
                        val revived = volumes.dormant.count { it.h.returned }
                        DossierCard(
                            tag = "沉寂",
                            accent = TgFeedTokens.SuccessGreen,
                            verdict = if (volumes.dormant.isNotEmpty()) {
                                "沉寂 ${volumes.dormant.size} 段 · 最长 ${longest} 天"
                            } else {
                                "无长期沉寂"
                            },
                            evidence = buildList {
                                if (revived > 0) add("复活 ${revived} 次")
                                if (volumes.shortPauses > 0) add("另有短暂停更 ${volumes.shortPauses} 次")
                                volumes.hiatusSummary?.let { add(it) }
                            },
                        ) {
                            volumes.dormant.forEach { row ->
                                val h = row.h
                                DossierEntry(
                                    title = if (h.returned) "沉寂 ${h.days} 天后复活" else "已沉寂 ${h.days} 天",
                                    sub = if (h.returned) {
                                        "${dayLabel(h.lastPostDay)} → ${dayLabel(h.returnDay)}"
                                    } else {
                                        "自 ${dayLabel(h.lastPostDay)} 起"
                                    },
                                    accent = if (h.returned) TgFeedTokens.SuccessGreen else TgFeedTokens.TextLow,
                                    playable = row.returnItem != null,
                                ) {
                                    val item = row.returnItem ?: return@DossierEntry
                                    onPlayDust(item, playReturns, "${st.title} · 复活之作")
                                }
                            }
                        }
                    }
                }
                // ⑤ 律动卷
                if (chron.monthly.isNotEmpty()) {
                    cards += {
                        DossierCard(
                            tag = "律动",
                            accent = TgFeedTokens.AccentRed,
                            verdict = chron.weekdayLabel ?: "月度律动",
                            evidence = listOfNotNull(chron.trendLabel),
                        ) {
                            ChronicleRhythmFlowCanvas(chron.monthly)
                        }
                    }
                }
            }
            // ⑥ 互联卷
            if (rels.isNotEmpty()) {
                cards += {
                    DossierCard(
                        tag = "互联",
                        accent = TgFeedTokens.TgBlue,
                        verdict = "关联频道 ${rels.size} 个",
                        evidence = emptyList(),
                    ) {
                        ChronicleInterlinkVolume(
                            rels = rels,
                            starsById = starsById,
                            open = interlinkOpen,
                            onToggle = { interlinkOpen = !interlinkOpen },
                            onFly = onFlyStar,
                        )
                    }
                }
            }
            if (cards.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().height(CardHeightDp.dp),
                    horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                    contentPadding = PaddingValues(end = TgFeedTokens.GapMd),
                ) {
                    itemsIndexed(cards) { idx, content ->
                        DossierReveal(key = st.chatId, index = idx) { content() }
                    }
                }
            }
        }
    }
}

/** 克制入场：淡入 + 轻微上移，按卷序错峰。 */
@Composable
private fun DossierReveal(key: Long, index: Int, content: @Composable () -> Unit) {
    val alpha = remember(key) { Animatable(0f) }
    val dy = remember(key) { Animatable(12f) }
    LaunchedEffect(key) {
        delay(index * 60L)
        launch { alpha.animateTo(1f, tween(DossierRevealDurMs, easing = MotionTokens.EmphasizedEasing)) }
        dy.animateTo(0f, tween(DossierRevealDurMs, easing = MotionTokens.EmphasizedEasing))
    }
    Box(
        Modifier.graphicsLayer {
            this.alpha = alpha.value
            translationY = dy.value * density
        },
    ) { content() }
}

@Composable
private fun DossierCard(
    tag: String,
    accent: Color,
    verdict: String,
    evidence: List<String>,
    body: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(TgFeedTokens.RadiusLg)
    Column(
        Modifier
            .width(CardWidthDp.dp)
            .fillMaxHeight()
            .background(
                Brush.verticalGradient(listOf(accent.copy(alpha = 0.14f), TgFeedTokens.SurfaceHigh)),
                shape,
            )
            .border(1.dp, TgFeedTokens.OverlayHairline, shape)
            .padding(TgFeedTokens.GapMd)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(accent, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(tag, style = MaterialTheme.typography.labelSmall, color = accent)
        }
        Text(
            verdict,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = TgFeedTokens.TextHigh,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        evidence.forEach {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = TgFeedTokens.TextMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        body()
    }
}

@Composable
private fun DossierEntry(
    title: String,
    sub: String,
    accent: Color,
    playable: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(TgFeedTokens.Surface.copy(alpha = 0.6f), RoundedCornerShape(TgFeedTokens.RadiusMd))
            .clickable(enabled = playable, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(28.dp).background(accent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = TgFeedTokens.TextHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                sub,
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextLow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (playable) {
            Spacer(Modifier.width(6.dp))
            Text("▶", style = MaterialTheme.typography.labelMedium, color = accent)
        }
    }
}

/** 首发→今天的连续光带：高度=月发布量，金点=巅峰月，灰带=沉寂段，绿点=复活点。 */
@Composable
private fun DossierTimelineBand(tl: DossierTimeline) {
    val sweep = remember(tl) { Animatable(0f) }
    LaunchedEffect(tl) { sweep.animateTo(1f, tween(DossierSweepDurMs, easing = MotionTokens.EmphasizedEasing)) }
    val blue = TgFeedTokens.TgBlue
    val gold = TgFeedTokens.StarYellow
    val green = TgFeedTokens.SuccessGreen
    val hair = TgFeedTokens.OverlayHairline
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(TimelineHeightDp.dp)) {
            val span = (tl.endMi - tl.startMi + 1).toFloat()
            val baseY = size.height - 10.dp.toPx()
            val topPad = 14.dp.toPx()
            val maxC = tl.peakCount.coerceAtLeast(1).toFloat()
            fun xOf(pos: Float): Float = ((pos - tl.startMi) / span).coerceIn(0f, 1f) * size.width
            // 沉寂段底纹
            for ((a, b) in tl.gaps) {
                val x0 = xOf(a)
                val x1 = xOf(b)
                drawRect(Color.White.copy(alpha = 0.06f), Offset(x0, topPad), Size((x1 - x0).coerceAtLeast(1f), baseY - topPad))
            }
            drawLine(hair, Offset(0f, baseY), Offset(size.width, baseY), 1.dp.toPx())
            // 光带（面积 + 描边），按 sweep 从左向右展开
            val path = Path()
            val line = Path()
            path.moveTo(0f, baseY)
            tl.counts.forEachIndexed { i, c ->
                val x = xOf(tl.startMi + i + 0.5f)
                val y = baseY - (c / maxC) * (baseY - topPad)
                path.lineTo(x, y)
                if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
            }
            path.lineTo(size.width, baseY)
            path.close()
            val clipW = size.width * sweep.value
            drawContext.canvas.save()
            drawContext.canvas.clipRect(0f, 0f, clipW, size.height)
            drawPath(path, Brush.verticalGradient(listOf(blue.copy(alpha = 0.55f), blue.copy(alpha = 0.04f)), topPad, baseY))
            drawPath(line, blue, style = Stroke(1.5.dp.toPx()))
            drawContext.canvas.restore()
            // 巅峰月
            val px = xOf(tl.peakMi + 0.5f)
            drawLine(gold.copy(alpha = 0.6f), Offset(px, topPad), Offset(px, baseY), 1.dp.toPx())
            drawCircle(gold, 4.dp.toPx(), Offset(px, topPad))
            // 复活点
            for (r in tl.revivals) drawCircle(green, 3.dp.toPx(), Offset(xOf(r), baseY))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("首发 ${ymLabel(tl.startMi)}", style = MaterialTheme.typography.labelSmall, color = TgFeedTokens.TextLow)
            Text("巅峰 ${ymLabel(tl.peakMi)} · ${tl.peakCount} 件", style = MaterialTheme.typography.labelSmall, color = gold)
            Text("今天", style = MaterialTheme.typography.labelSmall, color = TgFeedTokens.TextLow)
        }
        if (tl.gaps.isNotEmpty()) {
            Text(
                "灰带 沉寂 ${tl.gaps.size} 段 · 绿点 复活 ${tl.revivals.size} 次",
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextLow,
            )
        }
    }
}
