package dev.tgfeed.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.tgfeed.core.feed.UniverseRhythm
import dev.tgfeed.ui.components.kit.AppSheetMotion
import dev.tgfeed.ui.components.kit.SheetAnchor
import dev.tgfeed.ui.theme.TgFeedTokens

/**
 * R204 WK-2 频道宇宙作息表（「TG 世界几点醒」）：
 * 全网已索引频道的发布密度视图——24 小时柱状墙（峰时 TgBlue 高亮）+ 星期条 +
 * 人话洞察行（高峰时段/沉睡时段/爆更星期）+ 覆盖足注（本地深索引 N 频道 M 条、
 * 本机时区口径——诚实标注，不冒充服务端全网）。
 *
 * 红线：纯本地索引 rollup（R170 后台单飞+快照），零网络零请求；聚合计数无任何
 * 个体维度（DP-2 的 B3 最严档口径：只有「N 条/小时」，绝不出现发送者维度数据）。
 */
@Composable
internal fun UniverseRhythmSheet(
    rhythm: UniverseRhythm.Rhythm,
    onDismiss: () -> Unit,
) {
    // 触发点在发现页中部入口卡 → 底部 sheet（R173 就近锚定原则）
    AppSheetMotion(onDismiss = onDismiss, title = "频道宇宙作息表", anchor = SheetAnchor.BOTTOM) { _ ->
        Column(Modifier.fillMaxWidth()) {
            val peak = UniverseRhythm.peakHour(rhythm)
            val quiet = UniverseRhythm.quietHour(rhythm)
            val peakWd = UniverseRhythm.peakWeekday(rhythm)
            // 标题行：人话主结论（无数据时回落诚实空态文案）
            Text(
                if (rhythm.hasData() && peak >= 0) {
                    "TG 世界最爱在 ${peak} 点醒来" + if (peakWd >= 0) " · 周${UniverseRhythm.WEEKDAY_LABELS[peakWd]}最热闹" else ""
                } else {
                    "深索引还没建立，建立后这里能看到全网发布节律"
                },
                color = TgFeedTokens.TextHigh,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (rhythm.hasData() && quiet >= 0) "发布最少的时段是 ${quiet} 点 · 凌晨的世界在睡觉"
                else UniverseRhythm.coverageLine(rhythm),
                color = TgFeedTokens.TextLow,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(TgFeedTokens.GapLg))
            // 24 小时柱状墙（峰桶 TgBlue、其余白 α0.30；0/6/12/18/23 时标）
            UniverseHourWall(rhythm = rhythm, peakHour = peak, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("0", "6", "12", "18", "23").forEach { h ->
                    Text(
                        "$h 时",
                        color = TgFeedTokens.TextLow,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Spacer(Modifier.height(TgFeedTokens.GapLg))
            // 星期条（7 桶；峰日 TgBlue）
            Text(
                "按星期",
                color = TgFeedTokens.TextMedium,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            UniverseWeekdayStrip(rhythm = rhythm, peakWeekday = peakWd, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(TgFeedTokens.GapLg))
            // 覆盖足注（诚实口径三件：本地索引覆盖、时区、聚合无个体维度）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(TgFeedTokens.TextLow),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    UniverseRhythm.coverageLine(rhythm) + " · 本机时区口径 · 只出聚合计数",
                    color = TgFeedTokens.TextLow,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.height(TgFeedTokens.GapSm))
        }
    }
}

/** 24 小时柱状墙（纯展示；峰桶 TgBlue 高亮撑「几点醒」的视觉锚点） */
@Composable
private fun UniverseHourWall(
    rhythm: UniverseRhythm.Rhythm,
    peakHour: Int,
    modifier: Modifier = Modifier,
) {
    val bars = UniverseRhythm.hourBars(rhythm)
    val wallHeight = 88.dp
    Canvas(modifier = modifier.height(wallHeight)) {
        val n = 24
        val gap = 3.dp.toPx()
        val barW = (size.width - gap * (n - 1)) / n
        val h = size.height
        val floor = 2.dp.toPx()
        for (i in 0 until n) {
            val v = bars[i]
            if (v <= 0f) continue
            val barH = (v * (h - floor)).coerceAtLeast(floor)
            drawRect(
                color = if (i == peakHour) TgFeedTokens.TgBlue else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.30f),
                topLeft = Offset(i * (barW + gap), h - barH),
                size = Size(barW, barH),
            )
        }
    }
}

/** 星期条（7 桶横排；峰桶 TgBlue；标签取 WEEKDAY_LABELS） */
@Composable
private fun UniverseWeekdayStrip(
    rhythm: UniverseRhythm.Rhythm,
    peakWeekday: Int,
    modifier: Modifier = Modifier,
) {
    val bars = UniverseRhythm.weekdayBars(rhythm)
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
        for (w in 0 until 7) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
            ) {
                val wallHeight = 40.dp
                Canvas(Modifier.fillMaxWidth().height(wallHeight)) {
                    val v = bars[w]
                    val barH = if (v <= 0f) 2.dp.toPx() else (v * (size.height - 2.dp.toPx())).coerceAtLeast(2.dp.toPx())
                    drawRect(
                        color = if (w == peakWeekday) TgFeedTokens.TgBlue else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.30f),
                        topLeft = Offset(0f, size.height - barH),
                        size = Size(size.width, barH),
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "周${UniverseRhythm.WEEKDAY_LABELS[w]}",
                    color = if (w == peakWeekday) TgFeedTokens.TgBlue else TgFeedTokens.TextLow,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
