package dev.tgfeed.ui

/**
 * R174 捏合清屏（design-proposal.md §3 终审稿）的纯模型层：
 * zoomScale → chrome 透明度连续映射 + cleanMode 滞回状态机。
 *
 * 背景（提案 §3.1 实锤）：捏合缩放 1-4x 已存在但零 chrome 联动零教学；清屏唯一
 * 入口藏在长按面板第 3 分区行——两块能力未合流。本轮把「缩放」焊成「清屏手势」：
 * zoomScale 1.0→1.3 与 chrome（顶栏/底栏信息区/右轨）透明度线性映射（跟手、无
 * 时长、无模式跳变）；≥1.3 清屏态生效；捏合回 ≤1.05 按同曲线渐回。
 *
 * 映射基线（fadeOrigin，提案反跳变原则的工程化）：
 * α = 1 − (zoom − fadeOrigin)/(1.3 − 1)，clamp [0,1]。fadeOrigin 默认 1.0 时
 * 与提案 §3.2 原式逐点一致（主路径全按规范）；退通道①「单击恢复 chrome、缩放
 * 保留」后把基线重臂到当前缩放——否则 restore 后任何缩放事件都会把 α 瞬间拉回
 * 绝对曲线值（1.4x 处 α=0，悬崖跳变，正撞提案「离散式瞬间全藏会有跳变惊吓」
 * 红线；且缩回方向（1.4→1.3）会把刚恢复的 chrome 又瞬间全藏，方向语义全反）。
 * 重臂后：从当前缩放连续渐隐/渐回；zoom 完全回到 1x 时基线归 1（下轮从零起算）。
 *
 * 纯函数/纯状态机拆出的目的（BackdropPolicy/PlaybackStartPolicy 同款惯例）：
 * Compose 侧保持薄壳（调用零逻辑），映射曲线与滞回矩阵进单测枚举。
 * zoomScale/zoomOffset 的真身仍是页级手势状态（本模型只收事件载荷，不持有，
 * 单一事实来源不重复）。
 */
object PinchCleanPolicy {

    /** 清屏态生效阈值：zoom 超出基线 0.3 时 chrome 完全隐没（提案 §3.2 上端） */
    const val CLEAN_ENTER_SCALE = 1.3f

    /** 清屏态退出阈值：zoom 跌回基线 +0.05 以内 cleanMode 复位（提案 §3.2 反向通道） */
    const val CLEAN_EXIT_SCALE = 1.05f

    /** 首次教学气泡触发阈值：zoom 首过此值弹「双指放大已清屏」胶囊（提案 §3.2） */
    const val TEACH_TRIGGER_SCALE = 1.15f

    /** 捏合缩放上界（收编 VideoPage 原私有 4f 字面量，单一来源） */
    const val ZOOM_MAX_SCALE = 4f

    /**
     * R174b 捏合缩放下界（自由缩放：去掉 1x 地板，「捏到多大就多大」）：
     * 软界 0.25x——视频可缩到窗口中间、周围透底衬；与 [ZOOM_MAX_SCALE] 对称成界，
     * 手势层 coerce 用（超界值钳回，不弹回不回弹）。
     */
    const val ZOOM_MIN_SCALE = 0.25f

    /**
     * R174b 手势收尾贴 1x 吸附带：|zoom−1| < ε 时手势收尾把 zoom 吸回整 1x。
     * 防两类浮点残值卡死：①「捏回几乎 1x」时 1.0001x 残值让复位 chip 判据
     * （scale≠1）永真、单指平移闸（≠1x）吞掉翻页手势；②复位弹簧从 <1x 起点
     * 弹回时的终值收敛残差。0.1% 尺度差肉眼不可见，吸附无观感代价。
     */
    const val ZOOM_SNAP_EPSILON = 0.001f

    /**
     * R194③ 自由平移：放大态（内容比视口大）允许内容部分出屏的保守界——
     * 内容在窗轴上的可见覆盖至少保留 1−本值（25%）。出屏让位量随内容超出量
     * 线性长出、以 25% 视口封顶，跨 1x 夹域宽连续（无跳变），见
     * [freePanMaxOffsetAxis]。
     */
    const val FREE_PAN_EXIT_FRACTION = 0.25f

    /**
     * R194③ 自由平移夹域（单轴）：夹「内容矩形」而不是「图层矩形」。
     *
     * 背景（用户实锤「拖不到屏幕各个角落」）：旧对称式 |s−1|·边/2 夹的是
     * 图层——图层含 FIT letterbox 的不可见余量（视频带只占图层中部），按图层
     * 夹会把**可见内容**锁死在窗心附近（缩小态内容够不着四角；放大态内容边
     * 缘永远贴窗边）。新口径按内容带算：
     * - 内容 ≤ 视口（含缩小态）：内容整框在窗内任意位置可达（四角贴角、零出屏）；
     * - 内容 > 视口（放大态）：内容可部分出屏，保守界 = 窗轴可见覆盖
     *   ≥ 1−[FREE_PAN_EXIT_FRACTION]（出屏让位 = min(超出量, 25% 视口)）；
     * - 内容恰 = 视口（s=1）：域宽 0——offset 自然归零，跨 1x 连续。
     */
    fun freePanMaxOffsetAxis(contentSpan: Float, viewport: Float): Float = when {
        viewport <= 0f -> 0f
        contentSpan <= viewport -> (viewport - contentSpan) / 2f
        else -> (contentSpan - viewport) / 2f +
            minOf(contentSpan - viewport, viewport * FREE_PAN_EXIT_FRACTION)
    }

    /**
     * R194③ 视口内 FIT 内容带（scale=1 时内容的两轴尺寸；aspect = 宽/高）。
     * aspect 非法（≤0）时回退 (viewportW, viewportH)——即旧图层口径，零回归兜底。
     */
    fun fitContentSpan(aspect: Float, viewportW: Float, viewportH: Float): Pair<Float, Float> {
        if (viewportW <= 0f || viewportH <= 0f || aspect <= 0f) return viewportW to viewportH
        return if (aspect >= viewportW / viewportH) {
            viewportW to viewportW / aspect
        } else {
            viewportH * aspect to viewportH
        }
    }

    // 双击复位弹簧 0.82/500 已收编 MotionTokens.ZoomResetSpring*（R177 兑现 r174_notes
    // 挂账：提案 §5.2 动效总表的最终收敛件，全 App spring 仅 MotionTokens 三枚单一来源）；
    // 消费方 PinchCleanHost 直取 MotionTokens。

    /**
     * chrome 透明度纯映射：α = 1 − (zoom − [fadeOrigin])/0.3，clamp [0,1]。
     * [fadeOrigin]=1f（默认）即提案 §3.2 原式：1.0x 全显 → 1.15x 半隐 →
     * 1.3x 完全隐没 → 更高恒 0。跟手连续系统（提案 §5.2「chrome 渐隐无时长」行）。
     */
    fun chromeAlphaFor(zoomScale: Float, fadeOrigin: Float = 1f): Float =
        (1f - (zoomScale - fadeOrigin) / (CLEAN_ENTER_SCALE - 1f)).coerceIn(0f, 1f)

    /**
     * cleanMode 滞回矩阵（提案 §3.2；阈值相对基线，基线=1 时即规范的 1.3/1.05）：
     * - zoom ≥ 基线+0.3 → true（清屏态：单击语义翻转为恢复 chrome、返回键拦截、
     *   顶栏上报 hidden）；
     * - zoom ≤ 基线+0.05 → false（反向通道：捏合回 1x 附近状态自然复位；
     *   退出点 α 恒 ≈0.83，与规范绝对口径退出点同值）；
     * - 中间带 → 保持（中间态松手 chrome 停在当前比例对应的透明度，连续系统里
     *   不存在「非法中间态」）。
     */
    fun cleanModeFor(zoomScale: Float, current: Boolean, fadeOrigin: Float = 1f): Boolean = when {
        zoomScale >= fadeOrigin + (CLEAN_ENTER_SCALE - 1f) -> true
        zoomScale <= fadeOrigin + (CLEAN_EXIT_SCALE - 1f) -> false
        else -> current
    }
}

/**
 * 捏合清屏的页级 UI 状态（[PinchCleanMachine] 的载体）。
 * [PinchCleanUiState.zoomScale] 刻意不在内（手势/弹簧持有的页级状态是真身，
 * 本状态机只收事件载荷，单一事实来源不重复）。
 *
 * @property cleanMode 清屏态（行为语义位：单击=恢复 chrome、返回键拦截、上报 hidden）
 * @property chromeAlpha chrome 透明度目标值（渲染侧：[alphaAnimated]=true 走
 *   tween(AnimMed=220) 过渡；false 跟手 snap/复位 snap）
 * @property alphaAnimated 本次 α 目标变化的过渡方式（见上）
 * @property fadeOrigin 渐隐基线（见文件头；1f = 提案绝对口径）
 * @property teachFired 本页生命周期内 zoom 是否已首过 1.15（跨沿判定，翻页复位；
 *   气泡真正只弹一次由 AppPrefs 一次性 flag 把门）
 * @property fullscreen 全屏互斥位（§3.5：进全屏退清屏；全屏内 pinch 只缩放不清屏）
 */
data class PinchCleanUiState(
    val cleanMode: Boolean = false,
    val chromeAlpha: Float = 1f,
    val alphaAnimated: Boolean = false,
    val fadeOrigin: Float = 1f,
    val teachFired: Boolean = false,
    val fullscreen: Boolean = false,
)

/** 捏合清屏状态机的输入事件（全纯数据，单测直接枚举） */
sealed interface PinchCleanEvent {

    /**
     * 捏合缩放变化（手势层逐事件上抛；oldZoom 供教学跨沿判定）。
     * 全屏态下只冻结不动（α/clean/teach 全保持——§3.5 全屏内 pinch 可叠加缩放，
     * 但 chrome 本就全隐、tap 语义不许被清屏翻转）。非清屏态 zoom 完全回到 1x
     * 时基线归 1（下轮捏合从提案绝对曲线起算）。
     */
    data class ZoomChanged(val oldZoom: Float, val newZoom: Float) : PinchCleanEvent

    /**
     * 单击/返回键通道：恢复 chrome、缩放保留（用户可能还在看局部，提案 §3.2 ①③）。
     * [zoom] = 恢复时的当前缩放，供基线重臂（见文件头反跳变说明）。
     */
    data class ExitCleanKeepZoom(val zoom: Float) : PinchCleanEvent

    /** 双击通道：缩放复位 1x + chrome 全恢复（②；zoom 复位弹簧由渲染侧驱动） */
    data object FullReset : PinchCleanEvent

    /**
     * 长按面板「清屏播放」行（备份显式入口，提案 §3.5 保留）。
     * [zoom] = 进入时的当前缩放：面板入口把 α 与 zoom 解耦（任意 zoom 都置 α=0），
     * 基线随之下移 0.3 让曲线零点恰落在当前 zoom 上——后续捏合沿曲线无跳变；
     * 退出带（基线+0.05）随之落到 zoom 下界之下，面板清屏只能经单击/返回键退出
     * （捏合缩小把 chrome 渐回仍有效：α 是曲线、退出带只管 cleanMode 状态位）。
     */
    data class PanelCleanEntered(val zoom: Float) : PinchCleanEvent

    /** 翻页滑走：全复位 snap（抖音同款：下一条是干净的新开始） */
    data object PageLeft : PinchCleanEvent

    /** 全屏互斥同步（进全屏自动退清屏；退出全屏 chrome 本就全恢复） */
    data class FullscreenChanged(val fullscreen: Boolean) : PinchCleanEvent
}

/**
 * 捏合清屏状态机（纯 reducer，无副作用）。
 * 渲染侧职责只剩：把 α 目标值同步到 Animatable（animated→tween，否则 snap）、
 * 把 zoom 目标值驱动成双击复位弹簧。
 */
object PinchCleanMachine {

    fun reduce(state: PinchCleanUiState, event: PinchCleanEvent): PinchCleanUiState = when (event) {
        is PinchCleanEvent.ZoomChanged -> {
            if (state.fullscreen) state else {
                val clean = PinchCleanPolicy.cleanModeFor(event.newZoom, state.cleanMode, state.fadeOrigin)
                state.copy(
                    cleanMode = clean,
                    chromeAlpha = PinchCleanPolicy.chromeAlphaFor(event.newZoom, state.fadeOrigin),
                    alphaAnimated = false,
                    // 非清屏态完全归 1x：基线重臂 1（清屏态保持——α=0 是面板/手势进清屏
                    // 的合法静止点，不许被「z 恰好=1」的基线归位打破）
                    fadeOrigin = if (!clean && event.newZoom <= 1f) 1f else state.fadeOrigin,
                    teachFired = state.teachFired ||
                        (event.oldZoom < PinchCleanPolicy.TEACH_TRIGGER_SCALE &&
                            event.newZoom >= PinchCleanPolicy.TEACH_TRIGGER_SCALE),
                )
            }
        }
        // 单击/返回键与双击对 cleanMode/α 的目标一致（false/1，走 chrome 进出
        // 通用 220ms tween）；区别在 zoom：保留（基线重臂到当前值）vs 复位（基线归 1）
        is PinchCleanEvent.ExitCleanKeepZoom -> state.copy(
            cleanMode = false,
            chromeAlpha = 1f,
            alphaAnimated = true,
            fadeOrigin = event.zoom.coerceAtLeast(1f),
        )
        PinchCleanEvent.FullReset -> state.copy(
            cleanMode = false,
            chromeAlpha = 1f,
            alphaAnimated = true,
            fadeOrigin = 1f,
        )
        is PinchCleanEvent.PanelCleanEntered -> state.copy(
            cleanMode = true,
            chromeAlpha = 0f,
            alphaAnimated = true,
            fadeOrigin = event.zoom - (PinchCleanPolicy.CLEAN_ENTER_SCALE - 1f),
        )
        PinchCleanEvent.PageLeft -> state.copy(
            cleanMode = false,
            chromeAlpha = 1f,
            alphaAnimated = false, // 滑走复位 snap：页面已在屏外，下一帧直接干净全显
            fadeOrigin = 1f,
            teachFired = false,
            fullscreen = false,
        )
        is PinchCleanEvent.FullscreenChanged -> {
            val next = state.copy(fullscreen = event.fullscreen)
            if (event.fullscreen) {
                // §3.5 互斥：进全屏自动退清屏（全屏 chrome 门控本就全隐，α 归位即可）
                next.copy(cleanMode = false, chromeAlpha = 1f, alphaAnimated = false)
            } else next
        }
    }
}
