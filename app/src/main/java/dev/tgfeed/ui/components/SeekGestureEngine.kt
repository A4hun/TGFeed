package dev.tgfeed.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/**
 * 进度条类 seek 手势的结局：点按跳转 / 拖拽落点 / 弃权（竖滑翻页、被抢、系统取消
 * ——一律不 seek）。fraction 语义：TAP=按下落点、DRAG=松手落点（均已按容器宽夹取
 * 0..1），ABANDONED 时为最后一次目标（仅供日志）。
 */
internal enum class SeekGestureOutcome { TAP_SEEK, DRAG_SEEK, ABANDONED }

/**
 * R164b 进度条 seek 手势引擎（收敛共享件——「发现一个修一类」）：
 * ProgressLine（视频页）/ AudioSeekArea（音频卡）/ FloatingWindow（悬浮窗进度条）
 * 共用的统一事件循环，替代各处旧「slop 观察者 + 等待循环」两段式。
 *
 * 统一语义（真机 16416 实证，详见 r164_notes.md）：
 * ① awaitFirstDown(requireUnconsumed=true)：更高 z 层（chips/按钮）已消费的 down
 *    不抢——「控件层在上各收各的点击」；本层拿到的 down 当场消费。
 * ② 自己盯住每个事件：横向位移过 touchSlop → 拖拽（消费位移，与竖滑翻页公平竞争
 *    谁先过谁得）；竖向过 slop 或位移被别人消费 = 弃权（抬起不 seek——杀死
 *    「翻页后回落被误判成点按」）。
 * ③ Compose 1.7 漏出防守：1.7 起 detectTapGestures 用 awaitFirstDown(
 *    requireUnconsumed=false) 且固定消费首 down——只消费 down 挡不住下层手势层
 *    arm（静止长按照样弹面板/叠暂停，真机实锤）。防守三件：down 即消费；up 恒消费
 *    （作废 waitForUpOrCancellation——点按/双击不漏出）；按下 ~150ms 后 move 也
 *    消费（抖动 move 被消费→长按检测作废）。代价：热区内起手的慢速竖滑翻页被拦
 *    （快速竖滑 <150ms 过 pager slop 仍可翻页）——YouTube 式进度条领地语义。
 *
 * 站点差异全部经回调表达：duration 来源/兜底、owned 归属闸、seek 调用、
 * 视觉状态（按压/拖拽/上报）都留在调用方。
 *
 * @param onPress 按下即报落点（YouTube 式：气泡/帧预览在按压瞬间出场）
 * @param onDragStart 过横向 slop 进入拖拽（站点翻拖拽视觉态、锁定时长）
 * @param onDragTo 拖拽中目标更新（未过 slop 前不调）
 * @param onOutcome 手势定局（含被取消路径不会走到这里——走 [onCleanup]）
 * @param onCleanup 手势结束/被取消（翻页/dispose）的收尾：站点必在这里复位
 *        拖拽/按压态（协程取消路径唯一出口，finally 语义）
 */
internal suspend fun androidx.compose.ui.input.pointer.PointerInputScope.seekGestureLoop(
    onPress: ((fraction: Float) -> Unit)? = null,
    onDragStart: ((fraction: Float) -> Unit)? = null,
    onDragTo: ((fraction: Float) -> Unit)? = null,
    onOutcome: (outcome: SeekGestureOutcome, fraction: Float) -> Unit,
    onCleanup: (() -> Unit)? = null,
) {
    awaitEachGesture {
        val w = size.width.toFloat()
        fun xToFraction(x: Float) = if (w > 0f) (x / w).coerceIn(0f, 1f) else 0f
        val down = awaitFirstDown(requireUnconsumed = true)
        val pressFraction = xToFraction(down.position.x)
        down.consume()
        onPress?.invoke(pressFraction)
        try {
            var dragFraction = pressFraction
            var totalDx = 0f
            var totalDy = 0f
            var crossed = false
            var stolen = false
            var released = false
            val slop = viewConfiguration.touchSlop
            // 防守计时（类注释③）：150ms 后连抖动 move 一起消费
            val downAt = System.nanoTime()
            while (true) {
                val ev = awaitPointerEvent()
                val holdDefending = (System.nanoTime() - downAt) / 1_000_000 > 150
                // 抢占判定（仅过 slop 前且未进防守期）：本层此阶段未消费位移时，
                // 被消费的位移必来自竖滑翻页/整窗拖动/双指捏合
                if (!crossed && !holdDefending && ev.changes.any {
                        it.isConsumed &&
                            it.positionChangeIgnoreConsumed() != Offset.Zero
                    }
                ) {
                    stolen = true
                }
                val ch = ev.changes.firstOrNull { it.id == down.id }
                    ?: break // 跟踪指针从事件中消失（异常路径）——放弃
                if (ch.changedToUpIgnoreConsumed()) {
                    // up 恒消费：作废下层 waitForUpOrCancellation——点按/双击不漏出
                    ev.changes.forEach { it.consume() }
                    released = true
                    break
                }
                if (!ch.pressed) break // 指针被系统取消
                // 防守期（~150ms 起）或拖拽期：消费全部 change
                if (holdDefending || crossed) {
                    ev.changes.forEach { it.consume() }
                }
                if (stolen) continue // 已弃权：只等抬起收尾
                val delta = ch.positionChangeIgnoreConsumed()
                if (!crossed) {
                    totalDx += delta.x
                    totalDy += delta.y
                    if (abs(totalDy) > slop) {
                        // 竖向意图明确：弃权——防防守期消费拦死翻页后误判成点按
                        stolen = true
                    } else if (abs(totalDx) > slop) {
                        // 过横向 slop：消费本事件宣告拖拽归属（翻页 slop 竞争出局）
                        crossed = true
                        dragFraction = xToFraction(ch.position.x)
                        ev.changes.forEach { it.consume() }
                        onDragStart?.invoke(dragFraction)
                    }
                } else {
                    dragFraction = xToFraction(ch.position.x)
                    onDragTo?.invoke(dragFraction)
                }
            }
            if (released) {
                val outcome = when {
                    crossed -> SeekGestureOutcome.DRAG_SEEK
                    !stolen -> SeekGestureOutcome.TAP_SEEK
                    else -> SeekGestureOutcome.ABANDONED
                }
                onOutcome(outcome, if (crossed) dragFraction else pressFraction)
                android.util.Log.i(
                    "R164b",
                    "seek手势:${if (crossed) "拖拽" else if (stolen) "弃权" else "点按"}" +
                        " 目标=${"%.3f".format(if (crossed) dragFraction else pressFraction)}",
                )
            }
        } finally {
            // 协程被取消（翻页/dispose）也必须收尾（站点复位拖拽/按压态）
            onCleanup?.invoke()
        }
    }
}
