package dev.tgfeed.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.runtime.State
import androidx.compose.ui.unit.dp
import dev.tgfeed.core.tastemap.GestureFeelPolicy
import kotlin.math.abs

/**
 * R342 卡片手势门（星图内弹出卡/sheet 的**统一隔离政策件**——用户拍板「卡片
 * 弹出时卡上滑动只作用于卡片」的机制化收敛，不能每张新卡踩一遍穿透坑）：
 *
 *  - **占用即隔离（本体）**：挂本门的卡根进入 Compose 命中路径——兄弟层排他
 *    （AGENT-DISCIPLINE §四 R174 实锤：后台兄弟收不到事件）→ 卡区手势与星图
 *    画布槽手势层（fillMaxSize 的 slotMod pointerInput 族）互斥，**卡上滑动
 *    不再触达星图**。⚠️ 穿透根因正是「卡根无 pointerInput=非交互区命中
 *    透明，down 沿 z 序下探画布槽」（R342 前世界卡实况）——**红线：隔离全在
 *    卡侧（占用），星图本体手势一行不动**，绝不在星图侧加阻挡（粗暴方案
 *    红线；负锚=V46-② 卡上滑动星图相机零位移）。
 *  - **Initial 纯观察不消费**（三禁一许的「许」位=卡内容共同祖先+Initial
 *    pass，事件必经且先于后代层）：卡内滚动/LazyRow/按钮/3D 模型手势零干扰。
 *  - **下滑收卡**（R253② 语义收编单一出处——原 StarInfoCard 内联观察器退役
 *    为本件）：净下滑≥[GestureFeelPolicy.CARD_DISMISS_DRAG_DP]（64dp）且纵向
 *    主导才收；[canStart] 承接「起手滚动在顶才计」类前置（无滚动卡恒
 *    `{ true }`——起手即计）。
 *  - **模型区防误收**（[modelDragging]）：3D 模型拖动中（纵向分量是俯仰旋转
 *    的一半）跳过收卡判定——旗由 StarSystemMiniModel.onDragStateChange 置位
 *    （down 的 Main pass 即置，早于首个 move 的 Initial 观察——时序成立）。
 *  - **一票纪律**：凡星图内弹出的卡/sheet，卡根必挂本门（新卡不挂=穿透复发）。
 *
 * 探针（logcat TasteMap，事件级取证）：收卡行+卡内手势行（V46-② 门侧在场
 * 证据——卡上滑动手势被门收到=星图侧零位移的对照面）。
 */
fun Modifier.tasteSheetGestureGate(
    key: Any?,
    onDismissDown: (() -> Unit)?,
    canStart: () -> Boolean = { true },
    modelDragging: State<Boolean>? = null,
): Modifier = pointerInput(key) {
    val thresholdPx = GestureFeelPolicy.CARD_DISMISS_DRAG_DP.dp.toPx() // R268b 收口政策件
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial,
        )
        val atTopAtStart = canStart()
        var dy = 0f
        var dx = 0f
        var probeMoved = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: continue
            if (!change.pressed) break
            // 模型拖动中：手势属于模型（俯仰旋转），不累计收卡（防误收）
            if (modelDragging?.value == true) continue
            // 净下滑累计（上移回退清零——只认单方向一划）；
            // dx 门=纵向主导（预览 LazyRow 横滑的垂直漂移不误收）
            val delta = change.positionChange()
            dy = (dy + delta.y).coerceAtLeast(0f)
            dx += delta.x
            if (!probeMoved && (abs(dx) > thresholdPx || abs(dy) > thresholdPx)) {
                probeMoved = true
                android.util.Log.i(
                    "TasteMap",
                    "R342 手势门: 卡内手势 横=${(dx / 1.dp.toPx()).toInt()}dp " +
                        "纵=${(dy / 1.dp.toPx()).toInt()}dp（星图隔离——卡上滑动只作用于卡片）",
                )
            }
            if (atTopAtStart && onDismissDown != null && dy >= thresholdPx && dy > abs(dx)) {
                android.util.Log.i(
                    "TasteMap",
                    "R342 手势门: 下滑收卡（净 dy=${(dy / 1.dp.toPx()).toInt()}dp 纵向主导）",
                )
                onDismissDown()
                break
            }
        }
    }
}
