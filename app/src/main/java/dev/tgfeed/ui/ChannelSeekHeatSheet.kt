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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tgfeed.core.feed.SeekHeatPolicy
import dev.tgfeed.ui.components.kit.AppSheetMotion
import dev.tgfeed.ui.components.kit.SheetAnchor
import dev.tgfeed.ui.theme.TgFeedTokens

/**
 * R204 TL-7 频道级「精彩段落」洞察面板（回放热力 · 本机）：
 * P1.5 seek 落点信号在该频道的聚合分布——标题行（峰段/拖开头/奔后段人话）+
 * 密度墙（60 桶 0~100% 轴，峰段桶 TgBlue 高亮、其余白）+ 样本口径足注。
 *
 * 红线：
 *  - 隐私（BRAINSTORM-SIGNALS B3）：只做分布陈述（峰段比例/占比），不落任何
 *    「原始时间线以外」的个体推断文案；
 *  - 交互零扩张：纯展示面板，热力墙不可点不可跳（R195 seek 节流红线规避位）；
 *  - 全网层（广场协议 [D] key@秒数）按 R200 判词（生态薄）挂账不做——本面板是
 *    本机层，标题如实标注。
 */
@Composable
internal fun ChannelSeekHeatSheet(
    channelTitle: String,
    insight: SeekHeatPolicy.ChannelInsight?,
    onDismiss: () -> Unit,
) {
    // 触发点在频道主页中部信息行 → 底部 sheet（R173 就近锚定原则）
    AppSheetMotion(onDismiss = onDismiss, title = "回放热力", anchor = SheetAnchor.BOTTOM) { _ ->
        Column(Modifier.fillMaxWidth()) {
            if (insight == null) {
                // 防御位：入口行只在 insight 非空时渲染，这里只兜竞态（面板开着时数据被剪枝）
                Text(
                    "近 7 天的拖拽记录还不够，多看几天再来",
                    color = TgFeedTokens.TextMedium,
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }
            // 标题行：峰段人话（「你常跳到 62%~78% · 这段多半是精彩段落」）
            Text(
                SeekHeatPolicy.insightHeadline(insight),
                color = TgFeedTokens.TextHigh,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "频道 ${channelTitle.take(14)} · ${SeekHeatPolicy.insightSubline(insight, 7)}",
                color = TgFeedTokens.TextLow,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(TgFeedTokens.GapLg))
            // 密度墙：60 桶 0~100% 轴（峰段桶 TgBlue，其余白 α0.3；纯展示不可点）
            SeekHeatWall(insight = insight, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            // 轴标签（0%→100%，等宽数字）
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("开头 0%", color = TgFeedTokens.TextLow, style = MaterialTheme.typography.labelSmall)
                Text("50%", color = TgFeedTokens.TextLow, style = MaterialTheme.typography.labelSmall)
                Text("100% 结尾", color = TgFeedTokens.TextLow, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(TgFeedTokens.GapMd))
            // 覆盖足注（诚实口径：本机信号、样本闸、窗口）
            Text(
                "本机拖拽记录聚合 · 样本 ≥${SeekHeatPolicy.MIN_SAMPLES} 条才出洞察 · 只看分布不进推荐",
                color = TgFeedTokens.TextLow,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(TgFeedTokens.GapSm))
        }
    }
}

/** 密度墙（[SeekHeatPolicy.INSIGHT_BINS] 桶柱状；峰段区间桶 TgBlue 高亮，其余白 α0.30） */
@Composable
private fun SeekHeatWall(
    insight: SeekHeatPolicy.ChannelInsight,
    modifier: Modifier = Modifier,
) {
    val wallHeight = 64.dp
    val peakStart = insight.peakStart
    val peakEnd = insight.peakEnd
    Canvas(modifier = modifier.height(wallHeight)) {
        val n = insight.bins.size
        val gap = 2.dp.toPx()
        val barW = (size.width - gap * (n - 1)) / n
        val h = size.height
        for (i in 0 until n) {
            val v = insight.bins[i]
            if (v <= 0f) continue
            // 峰段区间内（比例坐标换算回桶）染 TgBlue，峰段缺失时全白
            val ratio = i / (n - 1).toDouble()
            val inPeak = peakStart != null && peakEnd != null &&
                ratio >= peakStart - 1e-9 && ratio <= peakEnd + 1e-9
            val barH = (v * (h - 4.dp.toPx())).coerceAtLeast(3.dp.toPx())
            drawRect(
                color = if (inPeak) TgFeedTokens.TgBlue else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.30f),
                topLeft = Offset(i * (barW + gap), h - barH),
                size = Size(barW, barH),
            )
        }
    }
}
