package dev.tgfeed.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import dev.tgfeed.ui.PinchCleanEvent
import dev.tgfeed.ui.PinchCleanMachine
import dev.tgfeed.ui.PinchCleanPolicy
import dev.tgfeed.ui.PinchCleanUiState
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * R174 捏合清屏的页级宿主（VideoPage / PhotoCard 共用，纪律§一收敛件）：
 * 持有 zoom/offset 手势状态 + [PinchCleanUiState] 状态机本体 + chrome α 的
 * 渲染真身（[MutableState]，非 Animatable——跟手 snap 必须同步写，Animatable
 * 的 snapTo 是 suspend，走协程调度会丢一帧跟手性），收敛三条页侧职责——
 *
 * ① α 目标值同步：状态机 [PinchCleanEvent] 产生的 α 目标，离散转移（单击/
 *    返回键/面板/双击）走协程 animate·tween(AnimMed=220)（chrome 进出通用档），
 *    跟手/滑走复位直接同步写（无时长连续映射，提案 §5.2「chrome 渐隐」行）；
 * ② 双击复位弹簧：zoom 逐帧写回 + offset 按剩余比例随动归零
 *    （§5.2 spring(0.82, 500f)≈260ms）；手势接入即取消（防双写互搏）；
 * ③ 滑走复位：zoom + 清屏态 + α 一次全清（抖音同款：下一条干净开始）。
 *
 * 教学气泡的 prefs 把门与展示计时留在调用方（VideoPage/PhotoCard 各自持
 * pinchTeachBubble——UI 归页面、机制归宿主）。
 * 纯模型/映射曲线/滞回矩阵见 [PinchCleanPolicy]（单测枚举）。
 */
internal class PinchCleanHost(
    /** 捏合缩放手势状态（1x~4x；graphicsLayer/平移夹域共用真身） */
    val zoomScale: MutableState<Float>,
    /** 缩放态平移偏移（夹域在手势层 pinchZoomTracking 内完成） */
    val zoomOffset: MutableState<Offset>,
    /** 清屏状态机本体（cleanMode/α 目标/基线/教学位/全屏互斥位） */
    val ui: MutableState<PinchCleanUiState>,
    /** chrome α 渲染真身（渐隐组 graphicsLayer 直接读 .value；跟手同步写） */
    val chromeAlpha: MutableState<Float>,
    private val scope: CoroutineScope,
) {
    private var alphaJob: Job? = null
    private var zoomResetJob: Job? = null

    /** 清屏态读取视图（行为语义位：单击=恢复 chrome、返回键拦截、上报 hidden） */
    val cleanMode: Boolean get() = ui.value.cleanMode

    /** 教学触发位读取视图（zoom 首过 1.15，页面级一次性） */
    val teachFired: Boolean get() = ui.value.teachFired

    /**
     * R174b 悬浮复位标识判据（三件套之三）：scale≠1 或有平移——组合内读点
     * 自动订阅两状态，翻转（含复位弹簧终值收敛）即时驱动 chip 显隐。
     * （手势层收尾吸附 [PinchCleanPolicy.ZOOM_SNAP_EPSILON] 保证近 1x 残值
     * 不残留，本判据用精确比较即可。）
     */
    val isTransformed: Boolean
        get() = zoomScale.value != 1f || zoomOffset.value != Offset.Zero

    /** 状态机事件入口：α 目标变化时同步进渲染真身（animated→tween 协程，否则同步写） */
    fun dispatch(event: PinchCleanEvent) {
        val prev = ui.value
        val next = PinchCleanMachine.reduce(prev, event)
        if (next != prev) {
            // R174 探针降频后的存留线（R178 父代理指令）：只留 dispatch 级一行
            // （状态机转移轨迹，Enter/Exit/FullReset 级频度）；per-event 级
            // （PinchZoomTracking 每事件/逐帧）已删——排查捏合问题看这一条就够
            android.util.Log.d(
                "R174",
                "dispatch ${event::class.simpleName}: clean=${prev.cleanMode}->" +
                    "${next.cleanMode} a=${"%.2f".format(prev.chromeAlpha)}->" +
                    "${"%.2f".format(next.chromeAlpha)} origin=${"%.2f".format(prev.fadeOrigin)}->" +
                    "${"%.2f".format(next.fadeOrigin)}",
            )
            ui.value = next
            if (next.chromeAlpha != prev.chromeAlpha) {
                alphaJob?.cancel()
                if (next.alphaAnimated) {
                    val target = next.chromeAlpha
                    alphaJob = scope.launch {
                        animate(
                            initialValue = chromeAlpha.value,
                            targetValue = target,
                            animationSpec = tween(TgFeedTokens.AnimMed),
                        ) { v, _ -> chromeAlpha.value = v }
                    }
                } else {
                    // 跟手/滑走复位：同步写（无协程调度延迟，逐事件零掉帧）
                    chromeAlpha.value = next.chromeAlpha
                }
            }
        }
    }

    /**
     * 双击退通道②的 zoom 复位弹簧：逐帧写回 zoomScale，zoomOffset 按剩余比例
     * 随动归零；与 α 的 220ms tween 并行（≈220/260ms 同拍观感）。α 归位由
     * dispatch(FullReset) 独立负责（状态机目标值），本方法只管几何。
     * R174b：<1x 起点（自由缩放下界）同样弹回 1x——offset 比例式对两侧对称
     * 成立；f 夹 [0,1] 防过冲帧（0.82 欠阻尼跨 1 上冲）offset 翻符号瞬闪。
     */
    fun launchZoomResetSpring() {
        zoomResetJob?.cancel()
        val startZoom = zoomScale.value
        val startOffset = zoomOffset.value
        // 已在 1x：无几何可复位（防御性归零 offset——正常路径 1x 夹域本就为 0）
        if (startZoom == 1f) {
            zoomOffset.value = Offset.Zero
            return
        }
        zoomResetJob = scope.launch {
            animate(
                initialValue = startZoom,
                targetValue = 1f,
                // R177 收编：双击复位弹簧 0.82/500 单一来源 = MotionTokens（r174_notes 挂账兑现）
                animationSpec = spring(
                    dampingRatio = dev.tgfeed.ui.theme.MotionTokens.ZoomResetSpringDamping,
                    stiffness = dev.tgfeed.ui.theme.MotionTokens.ZoomResetSpringStiffness,
                ),
            ) { value, _ ->
                zoomScale.value = value
                val f = ((value - 1f) / (startZoom - 1f)).coerceIn(0f, 1f)
                zoomOffset.value = Offset(startOffset.x * f, startOffset.y * f)
            }
            zoomScale.value = 1f
            zoomOffset.value = Offset.Zero
            // R174b 转移级探针（每复位一条）：弹簧终值落定证据（chip 判据此刻翻假）
            android.util.Log.d("R174b", "reset spring done (1x, zero off)")
        }
    }

    /** 手势接入让位：取消正在动画写 zoom 的复位弹簧（pinchZoomTracking 回调） */
    fun cancelZoomReset() {
        zoomResetJob?.cancel()
    }

    /** 滑走复位：zoom + 清屏态 + α 全清（PageLeft 走 snap，页面已在屏外） */
    fun resetForPageLeft() {
        cancelZoomReset()
        dispatch(PinchCleanEvent.PageLeft)
        zoomScale.value = 1f
        zoomOffset.value = Offset.Zero
    }
}

/**
 * 页级宿主的组合侧入口：全部状态 remember 挂 [itemKey]（翻页复用组合时整套
 * 重置），host 本体也挂同 key（闭包捕获的必须是 key 作用域内的新鲜状态对象）。
 */
@Composable
internal fun rememberPinchCleanHost(itemKey: Any?): PinchCleanHost {
    val scope = rememberCoroutineScope()
    val zoomScale = remember(itemKey) { mutableStateOf(1f) }
    val zoomOffset = remember(itemKey) { mutableStateOf(Offset.Zero) }
    val ui = remember(itemKey) { mutableStateOf(PinchCleanUiState()) }
    val chromeAlpha = remember(itemKey) { mutableStateOf(1f) }
    return remember(itemKey) { PinchCleanHost(zoomScale, zoomOffset, ui, chromeAlpha, scope) }
}
