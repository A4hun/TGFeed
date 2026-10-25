package dev.tgfeed.ui.components.kit

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.tgfeed.ui.MediaRailPolicy
import dev.tgfeed.ui.RailDragVisual
import kotlin.math.abs

/**
 * R181 四层滑动轨道的 Compose 薄壳（策略纯函数见 [dev.tgfeed.ui.MediaRailPolicy]，
 * 数值口径全部在那层钉死；本文件只做「测速 + 查表 + 上抛」三个动作）。
 *
 * 两件执法器同挂 FeedScreen 内容根（共同祖先位——事件必经，Main pass 子先父后）：
 *
 * ① [railGestureLoop]（L1 拖拽探测，R164b SeekGestureEngine 同款事件循环范式）：
 *    - 轴锁竞速：竖轴 slop 先越界（更深的 feed VerticalPager 翻页）→ 弃权；
 *      横轴 slop 先越界 → 消费本事件宣告轨道归属——**顶层 pager 的 slop 探测在本
 *      事件上出局**（R177 矩阵 #4「内层先消费」同款机制，Main pass 顺序保证）；
 *    - 位移被更深层消费（捏合/zoom 平移/图集/seek/竖滑翻页接管）→ 抢占判定成立
 *      → 弃权（各归各层，零新冲突；接管前后一律判定——中途转竖滑也让位）；
 *    - 位移被消费后松手 → 测速上抛（VelocityTracker 历史采样），调用方查
 *      [MediaRailPolicy.resolveGesture] 定夺轨道动作；
 *    - 拖拽中实时上抛 [RailDragVisual]（媒体 tab 带跟手，底栏 targetPage 范式）。
 *
 * ② [MediaRailCascadeConnection]（L0 级联连接，nestedScroll 链序在页级
 *    TopLevelPageScrollGuard **之内**）：图集/Stories/LazyRow 等内层横滚容器的
 *    边界甩尾（fling 剩余速度）先到本连接——过 [MediaRailPolicy.CASCADE_VELOCITY_DP]
 *    门槛 → 消费并触发轨道步进（级联替代吞没）；低于门槛原样上抛 → guard 吞掉
 *    （R177 慢推/慢甩语义零改动）。拖拽通道（onPostScroll）一律不级联——慢推
 *    不升级（用户设计：慢漂移不级联，R177 图集防误触初衷）。
 *
 * 手势闸：与 R177 顶层 pager 同一枚 [dev.tgfeed.ui.topLevelSwipeEnabled]（chrome
 * 隐藏/覆盖层压顶期间 L1 与级联同样关闸——关闸路径上横滑本来就是死路，行为一致）。
 */
@Composable
internal fun Modifier.mediaRailHost(
    enabled: Boolean,
    currentTab: () -> Int,
    onDragVisual: (RailDragVisual?) -> Unit,
    onRailGestureEnd: (dragDeltaX: Float, velocityX: Float) -> Unit,
    onSpillCascade: (velocityX: Float) -> Unit,
): Modifier {
    val density = LocalDensity.current
    // dp 常量单一来源（MediaRailPolicy），px 换算只在本组合做一次
    val stepPx = with(density) { MediaRailPolicy.DRAG_STEP_DP.dp.toPx() }
    val cascadePx = with(density) { MediaRailPolicy.CASCADE_VELOCITY_DP.dp.toPx() }
    // rememberUpdatedState：闭包活性（循环/连接的存活期长于组合，直捕参数会过期）
    val enabledState = rememberUpdatedState(enabled)
    val tabState = rememberUpdatedState(currentTab)
    val visualState = rememberUpdatedState(onDragVisual)
    val endState = rememberUpdatedState(onRailGestureEnd)
    val spillState = rememberUpdatedState(onSpillCascade)
    val connection = remember(cascadePx) {
        MediaRailCascadeConnection(
            enabled = { enabledState.value },
            cascadeVelocityPx = { cascadePx },
            onSpillFling = { v -> spillState.value(v) },
        )
    }
    return this
        .nestedScroll(connection)
        .pointerInput(enabled, stepPx) {
            if (!enabled) return@pointerInput
            railGestureLoop(
                stepPx = stepPx,
                currentTab = { tabState.value() },
                onDragVisual = { visualState.value(it) },
                onGestureEnd = { d, v -> endState.value(d, v) },
            )
        }
}

/**
 * R308 页内 overlay 横滑宿主（时间线 overlay 用——用户实锤「时间线左滑进了
 * 发现」：overlay 组合在 pager 页体内部，顶层 pager 是其事件链**祖先**，overlay
 * 内横滑无人消费 → Main pass 冒泡被 pager 吃掉切页。本宿主挂 overlay 根
 * （pager 后代位），横轴 slop 先越界即消费位移宣告归属——pager 的 slop 竞速
 * 出局（railGestureLoop 同款机制，Main pass 子先父后）；松手测速上抛，调用方
 * 查 [MediaRailPolicy.resolveCommit]+[MediaRailPolicy.resolveTimelineSwipe] 定夺。
 *
 * 让位纪律与 railGestureLoop 全同：不消费 down（tap/长按/点按全归更深层）；
 * 竖轴 slop 先越界 → 弃权（overlay 内竖滑列表照常）；位移被更深层消费
 * （卡内横排缩略等内层横滚）→ 抢占弃权。数值阈值不在本层——单一来源
 * MediaRailPolicy（commit=半程 44dp / fling=400dp/s，与 L1 步进同口径）。
 */
@Composable
internal fun Modifier.overlaySwipeHost(
    enabled: Boolean,
    onSwipeEnd: (dragDeltaX: Float, velocityX: Float) -> Unit,
): Modifier {
    val endState = rememberUpdatedState(onSwipeEnd)
    return this.pointerInput(enabled) {
        if (!enabled) return@pointerInput
        overlaySwipeLoop { d, v -> endState.value(d, v) }
    }
}

/**
 * overlay 横滑手势循环（railGestureLoop 同范式裁剪版：无轨道跟手视觉上抛——
 * overlay 归宿是整层切换，不做 tab 带渐变；其余轴锁竞速/抢占弃权/测速上抛
 * 逐字同款）。输出契约：仅「横向接管后正常抬起且未被抢占」时上抛
 * (累计位移, 松手速度)；弃权/取消/纯点按不上抛。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class) // historical 历史采样（同 railGestureLoop）
private suspend fun PointerInputScope.overlaySwipeLoop(
    onGestureEnd: (dragDeltaX: Float, velocityX: Float) -> Unit,
) {
    awaitEachGesture {
        // requireUnconsumed=false（R164b/R181 工程坑：Compose 1.7 起 tap 固定消费
        // 首 down）；与 tap 层消歧靠位移消费——横向接管后 tap 检测见已消费位移即取消
        val down = awaitFirstDown(requireUnconsumed = false)
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var totalDx = 0f
        var totalDy = 0f
        var crossed = false // 横向 slop 越界（本 overlay 归属成立）
        var stolen = false // 被更深层抢占或竖向意图明确 → 弃权
        var released = false
        val slop = viewConfiguration.touchSlop
        while (true) {
            val ev = awaitPointerEvent()
            // 抢占判定（接管前后一律生效）：本层未消费的位移被消费=更深层接管
            // （overlay 内竖滑列表/卡内横排缩略）→ 弃权，手势归它
            if (ev.changes.any {
                    it.isConsumed && it.positionChangeIgnoreConsumed() != Offset.Zero
                }
            ) {
                stolen = true
            }
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
            ch.historical.forEach { tracker.addPosition(it.uptimeMillis, it.position) }
            tracker.addPosition(ch.uptimeMillis, ch.position)
            if (ch.changedToUpIgnoreConsumed()) {
                if (crossed) ev.changes.forEach { it.consume() }
                released = true
                break
            }
            if (!ch.pressed) break // 指针被系统取消
            if (stolen) continue // 已弃权：只等抬起收尾
            val delta = ch.positionChangeIgnoreConsumed()
            if (!crossed) {
                totalDx += delta.x
                totalDy += delta.y
                if (abs(totalDy) > slop) {
                    // 竖向意图明确：让位 overlay 内竖滑列表
                    stolen = true
                } else if (abs(totalDx) > slop) {
                    // 横向 slop 越界：消费本事件宣告归属（顶层 pager slop 竞速出局）
                    crossed = true
                    ev.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } else {
                totalDx += delta.x
                ev.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        }
        if (released && crossed && !stolen) {
            val velocity = tracker.calculateVelocity()
            tracker.resetTracking()
            onGestureEnd(totalDx, velocity.x)
        }
    }
}

/**
 * L0 级联连接（nestedScroll）：内层横滚容器边界甩尾的 fling 通道裁决。
 *
 * 链序（内祖先→外祖先）：内层容器 → [feed VerticalPager 连接（纵向不消费横向）]
 * → **本连接** → 页级 TopLevelPageScrollGuard（R177）→ 顶层 pager。横向剩余速度：
 * - ≥ 级联门槛（[MediaRailPolicy.CASCADE_VELOCITY_DP]）：消费并向调用方上抛
 *   （调用方查 [MediaRailPolicy.commitFromSpill]+resolveStep 执行轨道步进）；
 * - 低于门槛：不消费原样上抛——guard 吞掉（R177 语义保留，慢推/慢甩防误触）。
 *
 * 拖拽通道（onPostScroll）恒不消费：慢推不级联（用户设计；上抛给 guard 吞）。
 * 纵向剩余恒放行（返回 Velocity 的 y 分量 = 0 消费，与 guard 同口径）。
 */
private class MediaRailCascadeConnection(
    private val enabled: () -> Boolean,
    private val cascadeVelocityPx: () -> Float,
    private val onSpillFling: (Float) -> Unit,
) : NestedScrollConnection {

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset = Offset.Zero // 拖拽残余不级联（慢推），原样上抛给 guard 吞

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val vx = available.x
        if (!enabled() || vx == 0f || abs(vx) < cascadeVelocityPx()) {
            return Velocity.Zero // 低于门槛：不消费，交给上游 guard（R177 吞没路径）
        }
        // R181 实机事件级验证探针（纪律§四）：过门槛的甩尾级联一行
        android.util.Log.i("R181", "L0甩尾级联 v=%d（门槛=%d）".format(vx.toInt(), cascadeVelocityPx().toInt()))
        onSpillFling(vx)
        // 消费横向剩余速度宣告级联归属；纵向（y=0 消费）原样放行
        return Velocity(vx, 0f)
    }
}

/**
 * L1 轨道手势循环（SeekGestureEngine.seekGestureLoop 同款范式，差异：
 * 不消费 down——tap/长按/点按全归更深层各层；只在横向 slop 越界后接管位移）。
 *
 * 输出契约：
 * - [onDragVisual]：拖拽中实时上抛（含协程取消路径的复位 null——finally 唯一出口）；
 * - [onGestureEnd]：仅「横向接管后正常抬起且未被抢占」时上抛 (累计位移, 松手速度)，
 *   调用方查 [MediaRailPolicy.resolveGesture] 定夺；其余路径（弃权/取消/纯点按）不上抛。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class) // PointerInputChange.historical 历史采样仍是实验 API（BOM 2024.09）
private suspend fun PointerInputScope.railGestureLoop(
    stepPx: Float,
    currentTab: () -> Int,
    onDragVisual: (RailDragVisual?) -> Unit,
    onGestureEnd: (dragDeltaX: Float, velocityX: Float) -> Unit,
) {
    awaitEachGesture {
        // requireUnconsumed=false：Compose 1.7 起一切 detectTapGestures（视频点按/轨道
        // 按钮的 clickable）固定消费首 down——等未消费 down 永远等不到（R164b 工程坑
        // 同源）。本层与 tap 层的消歧靠**位移消费**：横向 slop 越界后消费 move，tap
        // 检测见到已消费位移即取消（拖拽不会误触点击；纯点按零位移不受影响）
        val down = awaitFirstDown(requireUnconsumed = false)
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var totalDx = 0f
        var totalDy = 0f
        var crossed = false // 横向 slop 越界（轨道归属成立）
        var stolen = false // 被更深层抢占或竖向意图明确 → 弃权
        var released = false
        val slop = viewConfiguration.touchSlop
        try {
            while (true) {
                val ev = awaitPointerEvent()
                // 抢占判定（接管前后一律生效）：本层此阶段未消费的位移被消费，必来自
                // 更深层（竖滑翻页/捏合/zoom 平移/图集/seek，或拖拽中途转竖滑的翻页
                // 接管）——弃权，手势归它
                if (ev.changes.any {
                        it.isConsumed && it.positionChangeIgnoreConsumed() != Offset.Zero
                    }
                ) {
                    stolen = true
                }
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                // 历史采样先入测速器（速度拟合精度）
                ch.historical.forEach { tracker.addPosition(it.uptimeMillis, it.position) }
                tracker.addPosition(ch.uptimeMillis, ch.position)
                if (ch.changedToUpIgnoreConsumed()) {
                    // 接管期的 up 消费（作废更深层 waitForUpOrCancellation——拖拽收尾不漏出）
                    if (crossed) ev.changes.forEach { it.consume() }
                    released = true
                    break
                }
                if (!ch.pressed) break // 指针被系统取消
                if (stolen) continue // 已弃权：只等抬起收尾
                val delta = ch.positionChangeIgnoreConsumed()
                if (!crossed) {
                    totalDx += delta.x
                    totalDy += delta.y
                    if (abs(totalDy) > slop) {
                        // 竖向意图明确：让位竖滑翻页（feed VerticalPager 接管）
                        stolen = true
                    } else if (abs(totalDx) > slop) {
                        // 横向 slop 越界：消费本事件宣告轨道归属（顶层 pager slop 竞速出局）
                        crossed = true
                        ev.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } else {
                    totalDx += delta.x
                    // 跟手视觉：方向侧相邻 tab 为目标；越界（边界拖拽）无目标不上抛
                    val forward = totalDx < 0f
                    val target = MediaRailPolicy.stepTab(currentTab(), forward)
                    if (target != null) {
                        val fraction = (abs(totalDx) / stepPx).coerceIn(0f, 1f)
                        onDragVisual(RailDragVisual(targetTab = target, fraction = fraction))
                    }
                    ev.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        } finally {
            // 协程取消（切页/离组合）也必须复位跟手视觉（唯一出口）
            onDragVisual(null)
        }
        if (released && crossed && !stolen) {
            val velocity = tracker.calculateVelocity()
            tracker.resetTracking()
            // R181 实机事件级验证探针（纪律§四）：一次手势一行（定局原语，非刷屏源）
            android.util.Log.i(
                "R181",
                "轨道手势定局 dx=%d v=%d".format(totalDx.toInt(), velocity.x.toInt()),
            )
            onGestureEnd(totalDx, velocity.x)
        }
    }
}
