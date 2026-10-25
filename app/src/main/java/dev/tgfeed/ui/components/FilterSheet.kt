package dev.tgfeed.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tgfeed.core.feed.FeedFilter
import dev.tgfeed.ui.components.kit.AppSheetMotion
import dev.tgfeed.ui.components.kit.SheetAnchor
import dev.tgfeed.ui.components.kit.SheetStaggeredSection
import dev.tgfeed.ui.theme.MotionTokens
import dev.tgfeed.ui.theme.TgFeedTokens
import java.util.Calendar

/**
 * 时长档位（单选）：label 到 (minDurationSec, maxDurationSec)，0=不限。
 * R59：借鉴 soso 时长档（5/30 分钟分界）收敛为四档——
 *   不限 (0,0) / 短货 <5 分钟 (0,300) / 5-30 分钟 (300,1800) / 长货 >30 分钟 (1800,0)。
 * 边界 300/1800 秒与相邻档位有 1 秒交叠，可接受（chips 语义本就粗略）。
 * 四档一行即可排下，配合 FlowRow 折行保证全部可见（见 FilterGroup）。
 */
private val DURATION_OPTIONS: List<Pair<String, Pair<Int, Int>>> = listOf(
    "不限" to (0 to 0),
    "<5 分钟" to (0 to 300),
    "5-30 分钟" to (300 to 1800),
    ">30 分钟" to (1800 to 0),
)

/** 发布时间档位（单选）；档位 → 起始时刻由 [dateEpochFromIndex] 在应用时现算 */
private val DATE_LABELS = listOf("全部", "今天", "本周", "本月")

/** 把发布时间档位折算成起始 epoch 秒：今天 0 点 / 本周首日 0 点 / 本月 1 号 0 点（本地时区） */
private fun dateEpochFromIndex(idx: Int): Int {
    if (idx <= 0) return 0
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        when (idx) {
            // 本周：退到本周第一天（跟随系统区域设置，国内一般为周一）
            2 -> set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            // 本月：退到 1 号
            3 -> set(Calendar.DAY_OF_MONTH, 1)
            // idx==1 今天：仅清零时分秒即可
        }
    }
    return (cal.timeInMillis / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/** 草稿初始化：由已有筛选值反推时长档位；非预设组合回落"全部" */
private fun durationIndexOf(f: FeedFilter): Int {
    val idx = DURATION_OPTIONS.indexOfFirst {
        it.second.first == f.minDurationSec && it.second.second == f.maxDurationSec
    }
    return if (idx >= 0) idx else 0
}

/** 草稿初始化：由已有筛选值反推发布时间档位；与任一预设边界不一致时回落"全部" */
private fun dateIndexOf(f: FeedFilter): Int {
    if (f.dateFromEpochSec <= 0) return 0
    for (i in 1 until DATE_LABELS.size) {
        if (dateEpochFromIndex(i) == f.dateFromEpochSec) return i
    }
    return 0
}

/**
 * 内容筛选面板（R178 就近锚定迁移：触发点在顶部域——搜索页顶栏 Tune 漏斗 + 源面板
 * 「换着看」chip（抽屉→抽屉先收再开链）→ 左抽屉形态 [SheetAnchor.LEFT]，AppSheetMotion
 * 动效壳 spring(0.88/420) 无过冲 + scrim 180/150 + 三段 stagger）。
 * 三组单选 chips：方向（全部/竖屏/横屏）、时长（R59 四档：不限/<5 分钟/5-30 分钟/>30 分钟）、发布时间（全部/今天/本周/本月）；
 * 底部"应用筛选"主按钮 + "重置"。
 *
 * 面板内维护草稿态：点 chips 只改草稿，"应用筛选"才经 [onApply] 提交并关窗；
 * "重置"只把草稿清回默认（不直接上报，需再点"应用筛选"生效）。
 * [current] 变化（外部重置筛选）时草稿随之重建。
 * 关窗契约由壳统一保证：×/遮罩/返回键/拖拽/应用都先播完退场动画再回调 [onDismiss]。
 */
@Composable
fun FilterSheet(
    current: FeedFilter,
    onApply: (FeedFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    // ---------------- 草稿态（不即时生效，应用才提交） ----------------
    var orientation by remember(current) { mutableStateOf(current.orientation) }
    var durationIdx by remember(current) { mutableStateOf(durationIndexOf(current)) }
    var dateIdx by remember(current) { mutableStateOf(dateIndexOf(current)) }
    // 手动输入区（优先级高于 chips）：时长分钟区间 + 近 N 天
    var customMinMin by remember(current) {
        mutableStateOf(if (current.minDurationSec > 0) (current.minDurationSec / 60).toString() else "")
    }
    var customMaxMin by remember(current) {
        mutableStateOf(if (current.maxDurationSec > 0) (current.maxDurationSec / 60).toString() else "")
    }
    var customDays by remember(current) { mutableStateOf("") }

    AppSheetMotion(
        onDismiss = onDismiss,
        title = "内容筛选",
        anchor = SheetAnchor.LEFT,
    ) { sheet ->
        // 提交：把三组草稿折算成 FeedFilter 上报并关窗（退场动画壳内播完再 onDismiss）。
        // 手动输入区非空时覆盖 chips
        val apply: () -> Unit = {
            val useCustomDuration = customMinMin.isNotBlank() || customMaxMin.isNotBlank()
            val (minD, maxD) = if (useCustomDuration) {
                ((customMinMin.toIntOrNull() ?: 0) * 60) to ((customMaxMin.toIntOrNull() ?: 0) * 60)
            } else {
                DURATION_OPTIONS[durationIdx].second
            }
            val days = customDays.trim().toIntOrNull()
            val dateFrom = if (days != null && days > 0) {
                ((System.currentTimeMillis() / 1000L) - days * 86400L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            } else {
                dateEpochFromIndex(dateIdx)
            }
            onApply(
                FeedFilter(
                    orientation = orientation,
                    minDurationSec = minD,
                    maxDurationSec = maxD,
                    dateFromEpochSec = dateFrom,
                ),
            )
            sheet.dismiss()
        }

        Column(
            Modifier
                .fillMaxWidth()
                // 矮屏下内容可滚动，保证三组 chips 与底部按钮都够得着
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                // 状态栏/导航条/键盘让位由壳统一处理（壳内已 navigationBarsPadding+imePadding）
                .padding(bottom = 16.dp),
        ) {
            // ---------------- 方向（stagger 段一 t=0） ----------------
            SheetStaggeredSection(delayMs = 0) {
                FilterGroup("方向") {
                    FilterChip("全部", orientation == FeedFilter.ORIENTATION_ALL) {
                        orientation = FeedFilter.ORIENTATION_ALL
                    }
                    FilterChip("竖屏", orientation == FeedFilter.ORIENTATION_PORTRAIT) {
                        orientation = FeedFilter.ORIENTATION_PORTRAIT
                    }
                    FilterChip("横屏", orientation == FeedFilter.ORIENTATION_LANDSCAPE) {
                        orientation = FeedFilter.ORIENTATION_LANDSCAPE
                    }
                }
            }

            // ---------------- 时长（stagger 段二 t=+40ms） ----------------
            SheetStaggeredSection(delayMs = MotionTokens.SheetStaggerStepMs) {
                FilterGroup("时长") {
                    DURATION_OPTIONS.forEachIndexed { i, (label, _) ->
                        FilterChip(label, durationIdx == i) { durationIdx = i }
                    }
                }
            }

            // ---------------- 发布时间（stagger 段三 t=+80ms） ----------------
            SheetStaggeredSection(delayMs = MotionTokens.SheetStaggerStepMs * 2) {
                FilterGroup("发布时间") {
                    DATE_LABELS.forEachIndexed { i, label ->
                        FilterChip(label, dateIdx == i) { dateIdx = i }
                    }
                }
            }

            // ---------------- 手动输入（优先于 chips） ----------------
            Text(
                "手动输入（填了优先于上方档位）",
                color = TgFeedTokens.TextLow,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = TgFeedTokens.GapLg, bottom = TgFeedTokens.GapSm),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = customMinMin,
                    onValueChange = { customMinMin = it.filter(Char::isDigit) },
                    label = { Text("最短（分钟）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = customMaxMin,
                    onValueChange = { customMaxMin = it.filter(Char::isDigit) },
                    label = { Text("最长（分钟）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = customDays,
                onValueChange = { customDays = it.filter(Char::isDigit) },
                label = { Text("只看近 N 天（留空不限）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = TgFeedTokens.GapSm),
            )

            // ---------------- 底部：重置 + 应用筛选 ----------------
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = TgFeedTokens.GapXl),
                horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 重置：只清草稿回默认，不直接上报（避免误触立即重刷列表）
                Box(
                    Modifier
                        .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                        .background(TgFeedTokens.SurfaceHigh)
                        .clickable {
                            orientation = FeedFilter.ORIENTATION_ALL
                            durationIdx = 0
                            dateIdx = 0
                            customMinMin = ""
                            customMaxMin = ""
                            customDays = ""
                        }
                        .padding(horizontal = TgFeedTokens.GapXl, vertical = TgFeedTokens.GapMd),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "重置",
                        color = TgFeedTokens.TextMedium,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                // 应用筛选：主按钮，提交草稿并关窗
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                        .background(TgFeedTokens.TgBlue)
                        .clickable(onClick = apply)
                        .padding(vertical = TgFeedTokens.GapMd),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "应用筛选",
                        color = Color.White,
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
    }
}

/** 筛选分组：小节标题 + 流式折行 chips（SE-H：chips 多时折行全部可见，末位档不再藏屏外） */
@OptIn(ExperimentalLayoutApi::class) // FlowRow 仍是 ExperimentalLayoutApi（同 WatchReportScreen 的用法）
@Composable
private fun FilterGroup(title: String, chips: @Composable () -> Unit) {
    Text(
        title,
        color = TgFeedTokens.TextLow,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = TgFeedTokens.GapLg, bottom = TgFeedTokens.GapSm),
    )
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
        verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
    ) {
        chips()
    }
}

/** 筛选 chip：全圆角胶囊；选中态 TgBlue 淡底 + TgBlue 字，未选中 SurfaceHigh 底 */
@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
            .background(
                if (selected) TgFeedTokens.TgBlue.copy(alpha = 0.25f) else TgFeedTokens.SurfaceHigh,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapSm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) TgFeedTokens.TgBlue else TgFeedTokens.TextHigh,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}
