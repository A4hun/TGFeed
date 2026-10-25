package dev.tgfeed.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalViewConfiguration

/**
 * R189 双击刷新规则纯判定（用户拍板：刷新触发收窄为双击——单击已激活 tab=无操作、
 * 双击已激活=刷新该流、单击未激活=纯切换；底栏三键同口径）。
 *
 * **为什么手动记窗而不用 detectTapGestures(onDoubleTap)**：Compose 的双击检测会让
 * 一切单击延迟一个系统双击窗口（~300ms）才回调 onTap——切换是最高频动作不可迟滞；
 * 手动窗口判定让「切换/无操作」立即执行，只有「已激活的第一发」记窗等第二发。
 *
 * 判定矩阵（[resolveDoubleTap]）：
 * ```
 * active=false                      → SWITCH（纯切换，立即；清窗——双击未激活 tab 的
 *                                      第二发落在已激活态上按第一发处理，不误触发刷新）
 * active=true 且距上次激活点击≤窗口 → REFRESH（双击第二发；清窗防三连发连刷）
 * active=true 第一发                → NONE（无操作；记窗等可能的第二发）
 * ```
 */
enum class DoubleTapAction { SWITCH, REFRESH, NONE }

/** 一次点击的裁决：动作 + 新的窗口时间戳（供下一发比对） */
data class DoubleTapDecision(val action: DoubleTapAction, val newLastActiveTapAtMs: Long)

/** 窗口时间戳 0 = 无窗（清窗态：SWITCH/REFRESH 后归零） */
fun resolveDoubleTap(
    active: Boolean,
    nowMs: Long,
    lastActiveTapAtMs: Long,
    windowMs: Long,
): DoubleTapDecision = if (active) {
    if (lastActiveTapAtMs > 0 && nowMs - lastActiveTapAtMs <= windowMs) {
        DoubleTapDecision(DoubleTapAction.REFRESH, 0L)
    } else {
        DoubleTapDecision(DoubleTapAction.NONE, nowMs)
    }
} else {
    DoubleTapDecision(DoubleTapAction.SWITCH, 0L)
}

/**
 * [resolveDoubleTap] 的 Compose 薄壳（四消费面共用：TopLevelTab 关注/推荐、媒体条
 * band 单元格、底栏三键——单一实现不许各写一份窗口状态机）。返回点击处理器：
 * active/回调经 rememberUpdatedState 现读（闭包不过期，active 翻转不重启手势协程）。
 * 窗口时长取 [LocalViewConfiguration.doubleTapTimeoutMillis]（平台双击口径）。
 */
@Composable
internal fun rememberDoubleTapRule(
    active: Boolean,
    onSwitch: () -> Unit,
    onRefresh: () -> Unit,
): () -> Unit {
    var lastActiveTapAt by remember { mutableStateOf(0L) }
    val activeNow by rememberUpdatedState(active)
    val onSwitchNow by rememberUpdatedState(onSwitch)
    val onRefreshNow by rememberUpdatedState(onRefresh)
    val windowMs = LocalViewConfiguration.current.doubleTapTimeoutMillis
    return remember {
        {
            val d = resolveDoubleTap(
                active = activeNow,
                nowMs = android.os.SystemClock.elapsedRealtime(),
                lastActiveTapAtMs = lastActiveTapAt,
                windowMs = windowMs,
            )
            lastActiveTapAt = d.newLastActiveTapAtMs
            when (d.action) {
                DoubleTapAction.SWITCH -> onSwitchNow()
                DoubleTapAction.REFRESH -> onRefreshNow()
                DoubleTapAction.NONE -> {} // R189：单击已激活 = 无操作（R157「在播单击=刷新」废止）
            }
        }
    }
}
