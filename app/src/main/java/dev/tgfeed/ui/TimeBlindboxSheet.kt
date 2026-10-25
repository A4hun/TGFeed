package dev.tgfeed.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tgfeed.core.feed.TimeBlindboxPolicy
import dev.tgfeed.core.feed.TimeBlindboxRoll
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.feed.FeedViewModelFactory
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.ui.components.kit.AppSheetMotion
import dev.tgfeed.ui.components.kit.SheetAnchor
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * R204 TL-3 时间盲盒面板（随机时刻 + 随机频道 + 该秒起播）：
 * 骰子一掷 = [TimeBlindboxPolicy] 随机时刻采样（R202 idx_items_date 索引原语）→
 * 揭示卡（大字日期 + 距今 + 频道 + 条目标题 +「那个时刻还有 N 条在发」）→ 去播放。
 *
 * 三态：掷前（骰子静置引导）/ 掷中（骰子缓转）/ 揭示（年代卡）。掷尽仍空或索引
 * 未就绪 → 诚实空态文案（R202 的 dateIndexReady 流的「构建中」语义由
 * rollTimeBlindbox 返回 null 承接——不区分建没建，统一「再掷/稍后再来」兜底）。
 * 叙事口径沿 R140 禁词纪律：穿越/年代/挖到，不出现采样/查询等机器词。
 */
@Composable
internal fun TimeBlindboxSheet(
    gateway: TdGateway,
    onPlay: (VideoItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as android.app.Application
    // 共享 VM（同 key 约定——与发现页/FeedScreen 同一实例，deepIndex 状态同源）
    val vm: FeedViewModel = viewModel(
        factory = FeedViewModelFactory(gateway, app),
        key = "gw" + System.identityHashCode(gateway),
    )
    var rolling by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf<TimeBlindboxRoll?>(null) }
    // 掷尽仍空（诚实兜底：这个年代太安静/索引还没就绪）
    var emptyRoll by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // 骰子缓转（掷中过程感——ArchaeoRandomCard 同款节律）
    val diceSpin = rememberInfiniteTransition(label = "timeDice")
    val diceAngle by diceSpin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing)),
        label = "timeDiceAngle",
    )

    fun roll() {
        if (rolling) return
        rolling = true
        emptyRoll = false
        revealed = null
        scope.launch {
            val result = runCatching { vm.rollTimeBlindbox() }.getOrNull()
            revealed = result
            emptyRoll = result == null
            rolling = false
        }
    }

    // 触发点在发现页中部入口行 → 底部 sheet（R173 就近锚定原则）
    AppSheetMotion(onDismiss = onDismiss, title = "时间盲盒", anchor = SheetAnchor.BOTTOM) { _ ->
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "掷一个随机年代，抽一条那个时刻全网正在发的内容",
                color = TgFeedTokens.TextMedium,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(TgFeedTokens.GapLg))
            // 骰子（静置 0.9x / 掷中 1x 缓转）
            Box(
                Modifier
                    .size(64.dp)
                    .graphicsLayer { scaleX = if (rolling) 1f else 0.9f; scaleY = if (rolling) 1f else 0.9f }
                    .clip(CircleShape)
                    .background(TgFeedTokens.AccentRed.copy(alpha = 0.14f))
                    .padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Casino,
                    contentDescription = null,
                    tint = TgFeedTokens.AccentRed,
                    modifier = Modifier
                        .size(40.dp)
                        .rotate(if (rolling) diceAngle else 0f),
                )
            }
            Spacer(Modifier.height(TgFeedTokens.GapLg))
            when {
                rolling -> Text(
                    "掷出去了，看看落在哪个年代…",
                    color = TgFeedTokens.TextMedium,
                    style = MaterialTheme.typography.bodySmall,
                )
                revealed != null -> TimeBlindboxRevealCard(
                    roll = revealed!!,
                    onPlay = { onPlay(revealed!!.item) },
                )
                emptyRoll -> Text(
                    "这个年代太安静了，再掷一次试试",
                    color = TgFeedTokens.TextLow,
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> Text(
                    "5 年库存里随机落点，掷到的就是缘分",
                    color = TgFeedTokens.TextLow,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(TgFeedTokens.GapLg))
            // 主行动：掷 / 再掷；揭示态给「去播放」次按钮（揭示卡内）
            if (revealed == null) {
                Button(
                    onClick = { roll() },
                    enabled = !rolling,
                    shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TgFeedTokens.AccentRed,
                        contentColor = Color.White,
                    ),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Text(if (emptyRoll) "再掷一次" else "掷一个年代", style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(TgFeedTokens.GapSm))
        }
    }
}

/** 揭示卡：大字日期 + 距今 + 频道 + 条目标题 + 「那个时刻还有 N 条在发」+ 去播放 */
@Composable
private fun TimeBlindboxRevealCard(
    roll: TimeBlindboxRoll,
    onPlay: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
            .background(TgFeedTokens.SurfaceHigh)
            .padding(TgFeedTokens.GapLg),
    ) {
        // 大字日期（中文体，与考古年代卡 §2.4.5 同口径）
        Text(
            formatBlindboxDate(roll.anchorSec),
            color = TgFeedTokens.TextHigh,
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "距今 ${formatBlindboxAgo(roll.anchorSec)} · 那个时刻还有 ${roll.windowCount} 条在发",
            color = TgFeedTokens.TextLow,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(TgFeedTokens.GapSm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(TgFeedTokens.TgBlue),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                roll.item.channelTitle,
                color = TgFeedTokens.TextMedium,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            roll.item.caption.ifBlank { "（无标题）" },
            color = TgFeedTokens.TextLow,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(TgFeedTokens.GapMd))
        Row(horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
            OutlinedButton(
                onClick = onPlay,
                shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
                modifier = Modifier.weight(1f).height(40.dp),
            ) {
                Text("去播放", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** 掷骰协程（面板内触发；结果回主线程状态）——由 roll() 经 rememberCoroutineScope 发起 */

/** 盲盒日期文案（中文体：2023年11月4日 15:20） */
internal fun formatBlindboxDate(epochSec: Long): String {
    val fmt = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA)
    return fmt.format(Date(epochSec * 1000L))
}

/** 距今文案（年/月/天自适应；盲盒口径按天粒度足够） */
internal fun formatBlindboxAgo(epochSec: Long): String {
    val days = (System.currentTimeMillis() / 1000L - epochSec) / 86_400L
    return when {
        days >= 365 -> "${days / 365} 年"
        days >= 30 -> "${days / 30} 个月"
        days >= 1 -> "$days 天"
        else -> "不到一天"
    }
}
