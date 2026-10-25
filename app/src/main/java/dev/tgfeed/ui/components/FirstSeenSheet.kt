package dev.tgfeed.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tgfeed.core.feed.FirstSeenLedger
import dev.tgfeed.core.feed.FirstSeenOutcome
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.ui.components.kit.AppSheetMotion
import dev.tgfeed.ui.components.kit.SheetAnchor
import dev.tgfeed.ui.theme.TgFeedTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * R204 DS-5 首发溯源面板（FirstSeenSheet）：同 fileUniqueId 跨频道副本的最早出现
 * =首发（「关注源头永远比搬运号快半拍」——DISCOVERY-GRAPH 最缺的「谁先发」证据）。
 *
 * 触发成本口径（诚实呈现）：fileUniqueId 不在任何索引——查证是深索引全表扫
 * （秒级~十秒级），**用户点按才触发**（WK-1 考古学家先例：点按付费零被动成本），
 * 面板开着转圈承接；三级缓存（会话内存 → 磁盘账本 LRU 512 → 全表扫回写）保证
 * 同指纹第二次查看零成本；「重新查证」绕缓存（索引长出新副本后的兜底口）。
 * R202 索引侧若补 idx_items_fingerprint，本查询一行 SQL 换索引序（挂账见 r204_notes）。
 *
 * 覆盖诚实口径：只看得见本地深索引已收录的频道（订阅/金矿宇宙）——索引外搬运频道
 * 不可见，足注如实标注。
 */
@Composable
internal fun FirstSeenSheet(
    item: VideoItem,
    lookup: suspend (VideoItem, forceRescan: Boolean) -> FirstSeenOutcome,
    onDismiss: () -> Unit,
) {
    // 查证态：面板打开即起查（LaunchedEffect 主上下文）；「重新查证」带 forceRescan
    // 重掷（转圈期间防连点）
    var outcome by remember(item.key) { mutableStateOf<FirstSeenOutcome?>(null) }
    var busy by remember(item.key) { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(item.key) {
        busy = true
        outcome = lookup(item, false)
        busy = false
    }

    AppSheetMotion(
        onDismiss = onDismiss,
        title = "首发溯源",
        anchor = SheetAnchor.BOTTOM, // 触发点=长按面板动作格（屏幕下方）→ 就近锚定
    ) { _ ->
        Column(Modifier.fillMaxWidth()) {
            when {
                busy -> Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 全表扫秒级~十秒级：过程感文案说清成本（不是卡了）
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = TgFeedTokens.TextHigh,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.height(TgFeedTokens.GapSm))
                    Text(
                        "正在翻遍本地深索引查这个文件的每一份副本…",
                        color = TgFeedTokens.TextMedium,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                outcome is FirstSeenOutcome.NotApplicable -> Text(
                    "这条内容没有文件指纹（本地文件/演示模式），无从溯源",
                    color = TgFeedTokens.TextMedium,
                    style = MaterialTheme.typography.bodySmall,
                )
                outcome is FirstSeenOutcome.Missed -> Column {
                    Text(
                        "本地深索引里没有找到同指纹副本",
                        color = TgFeedTokens.TextHigh,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "索引只收录你订阅/金矿覆盖的频道——它可能首发在覆盖之外的频道",
                        color = TgFeedTokens.TextLow,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                outcome is FirstSeenOutcome.Found -> FirstFoundBody(
                    verdict = (outcome as FirstSeenOutcome.Found).verdict,
                    currentItem = item,
                    onRescan = {
                        if (!busy) scope.launch {
                            busy = true
                            outcome = null
                            outcome = lookup(item, true)
                            busy = false
                        }
                    },
                )
                else -> Unit
            }
            Spacer(Modifier.height(TgFeedTokens.GapSm))
        }
    }
}

/** 溯源判定体：头条结论 + 副本时间线（首行=首发，本条行=「本条」）+ 足注 + 重新查证 */
@Composable
private fun FirstFoundBody(
    verdict: FirstSeenLedger.Verdict,
    currentItem: VideoItem,
    onRescan: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        // 头条：本条就是首发（蓝） / 搬运（灰）——结论先行
        val first = verdict.copies.first()
        val isMine = verdict.currentIsFirst
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(if (isMine) TgFeedTokens.TgBlue else TgFeedTokens.TextLow),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (isMine) "本条就是首发——最早出现在这里" else "搬运：首发于 ${formatFirstSeenDate(first.dateSec)}",
                color = if (isMine) TgFeedTokens.TgBlue else TgFeedTokens.TextHigh,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "${FirstSeenLedger.copiesLine(verdict)} · 覆盖 ${verdict.channels} 个已索引频道",
            color = TgFeedTokens.TextLow,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(TgFeedTokens.GapMd))
        // 副本时间线：按时间升序；首行标「首发」、本条所在频道行标「本条」
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
                .background(TgFeedTokens.SurfaceHigh)
                .padding(TgFeedTokens.GapMd),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(TgFeedTokens.GapSm),
        ) {
            verdict.copies.forEachIndexed { idx, copy ->
                val isFirst = idx == 0
                val isCurrent = copy.chatId == currentItem.chatId && copy.messageId == currentItem.messageId
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(width = 3.dp, height = 14.dp)
                            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                            .background(
                                when {
                                    isFirst -> TgFeedTokens.TgBlue
                                    isCurrent -> TgFeedTokens.AccentRed
                                    else -> TgFeedTokens.TextLow
                                },
                            ),
                    )
                    Spacer(Modifier.width(TgFeedTokens.GapSm))
                    Text(
                        copy.channelTitle.ifBlank { "频道 ${copy.chatId}" },
                        color = if (isFirst || isCurrent) TgFeedTokens.TextHigh else TgFeedTokens.TextMedium,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (isFirst) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        formatFirstSeenDate(copy.dateSec) +
                            when {
                                isFirst -> " · 首发"
                                isCurrent -> " · 本条"
                                else -> ""
                            },
                        color = when {
                            isFirst -> TgFeedTokens.TgBlue
                            isCurrent -> TgFeedTokens.AccentRed
                            else -> TgFeedTokens.TextLow
                        },
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(TgFeedTokens.GapSm))
        // 覆盖足注（诚实口径）+ 重新查证（索引长出新副本后的兜底）
        Text(
            "基于本地深索引检索 · 索引外的频道不可见 · 判定随索引增长可刷新",
            color = TgFeedTokens.TextLow,
            style = MaterialTheme.typography.labelSmall,
        )
        TextButton(onClick = onRescan) {
            Text("重新查证", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** 副本日期文案（中文体：2024年3月14日；无时间戳回退「时间未知」） */
private fun formatFirstSeenDate(dateSec: Long): String =
    if (dateSec > 0) {
        SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(dateSec * 1000L))
    } else {
        "时间未知"
    }
