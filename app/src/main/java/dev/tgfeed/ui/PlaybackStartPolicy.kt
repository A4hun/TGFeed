package dev.tgfeed.ui

/**
 * R159b 起播一拍对齐（用户第三时序实锤「起播瞬间背景/宽度跳变」）：
 * 加载态与播放态的交界拍 = 首帧真正画进渲染面（onRenderedFirstFrame）。
 * 交界拍上只许「占位→画面」一处可见变化；两类迟发校正都挂到同一指挥信号上：
 *
 * ①宽度校正（ExoPlayer videoSize 实测比 vs 封面/元数据预判，偏差 ≥15% 才换目标）：
 *   冻结到首帧渲染后才释放（[shouldReleaseMeasuredAspect]）——释放后走外层既有
 *   300ms tween（与 R159 翻页换形态同一动画口径）。预判链（元数据/封面代理）
 *   不受影响，加载态宽度照旧；冻结期间列宽维持预判目标，起拍瞬间列宽不动。
 * ②背景换血（封面底衬 → R89 live 实帧）：首帧上屏前不采纳 live 帧
 *   （[shouldAdoptLiveBackdrop]），底衬保持与列内占位同图；首帧后短拍追赶
 *   （[liveBackdropPollIntervalMs]），首拍换血与「占位→画面」同拍合并
 *   （snap 口径见 [BackdropPolicy.isBackdropSnapSwitch]）。
 * ③列内占位模糊底衬只在「外层无底衬」的形态保留（手动旋转 90/270°，
 *   [keepPlaceholderBackdrop]）——常规 FIT 形态删除：外层全屏底衬本就铺在透明页下
 *   （同图同拍），列内再画一份口径不同（列裁剪/30dp/1.25x vs 窗裁剪/28dp/1.3x）
 *   的模糊层，退场时制造「背景跳变」；ZOOM 填满形态封面 Crop 铺满整列，
 *   模糊层本就不可见。
 *
 * 方向判定（useZoom/resizeMode/封面 ContentScale）**不在本策略内**：PlayerView 的
 * resizeMode 必须在首帧渲染前落位（否则首帧按旧模式裁完再跳正）——方向链保持
 * 「首帧前就位」（R77/R93 旧口径），与宽度/底衬的「首帧后释放」分野是刻意的。
 *
 * 纯函数拆出的目的：Compose 侧保持薄壳（调用零逻辑），起拍规则矩阵进单测枚举；
 * 轮询常数单一来源（VideoPage 旧私有 BACKDROP_POLL_MS 已收编进来）。
 */
object PlaybackStartPolicy {

    /**
     * R194①② 镜像帧快照节拍（毫秒，≈12.5fps）：TextureView.getBitmap 缩读进
     * 预分配的短边 144px 小位图（~100KB 回读）——双镜像播放（架构师 17:00 终案，
     * 用户原话「背景图就像全屏版的播放视频——前台播放视频，后台跟着做模糊背景
     * 地播放，两画面同步，用户无感」）的生产端；节流是 v1 保守案（模糊掩盖
     * 低刷率）。快照不打扰前台解码为红线（帧统计前后对照验证）。
     * 旧 R89 预览条供图路径退役（LIVE_BACKDROP_POLL_MS/短拍追赶两常数与取值
     * 函数一并删除——1.5s 粒度跳帧+逐拍渐变=「切 tab 疯狂闪烁追赶」病根；
     * 预览条本身保留，进度拖拽帧预览仍用）。
     */
    const val MIRROR_FRAME_INTERVAL_MS = 80L

    /**
     * 实测宽高比是否允许上报外层（外层据此换列宽目标）。
     * 首帧渲染前一律 false——实测比一旦入表，预判偏差 ≥15% 的换目标即刻生效，
     * 校正 tween 会压在首帧上屏窗口内（用户实测「起播跳宽」）；首帧后才放行。
     */
    fun shouldReleaseMeasuredAspect(firstFrameSeen: Boolean): Boolean = firstFrameSeen

    /**
     * 镜像帧是否允许采纳。首帧上屏前一律 false——底衬维持封面（与列内占位
     * 同图同拍），不许底衬先于画面换血；首帧后镜像制度开启（首拍=SOFT 渐入，
     * 其后 SNAP 直换——模式裁决在外层 FeedScreen）。
     */
    fun shouldAdoptLiveBackdrop(firstFrameSeen: Boolean): Boolean = firstFrameSeen

    /**
     * 列内占位模糊底衬是否保留：仅手动旋转 90/270°（外层底衬被显式 CLEAR_NULL
     * 清屏、letterbox 无图可透）保留；FIT 常规形态删（外层底衬同图同拍透上来）、
     * ZOOM 填满形态封面 Crop 铺满整列本就不可见。
     */
    fun keepPlaceholderBackdrop(rotatedOdd: Boolean): Boolean = rotatedOdd
}
