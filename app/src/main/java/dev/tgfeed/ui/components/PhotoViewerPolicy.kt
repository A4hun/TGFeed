package dev.tgfeed.ui.components

import androidx.compose.ui.geometry.Offset

/**
 * R299 B2/B3 查看器第二态 + 漫画模式的手势/形态政策（纯函数，JVM 单测锚定；
 * 零 Android 依赖——Offset 是 compose-ui 的纯数据类）。
 *
 * 设计唯一出处 design-audio-photo.md §3.1/§3.2（用户终审稿）：
 *  - 进入：pinch 收尾时缩放 ≥ [ENTER_SCALE]（清屏阈值 1.3 之上「继续推」的落点），
 *    或操作栏「放大」钮直达；
 *  - 查看器内：双指无级缩放 [MIN_SCALE]~[MAX_SCALE] + 平移（放大后可拖动）、
 *    双击 [DOUBLE_TAP_SCALE]↔复位、横滑=图集内切图（1x 态让位内层 Pager）、
 *    下滑甩回=退出（1x 态；条漫页须滚到顶后再下甩）；
 *  - 漫画模式：高宽比 ≥ [LONG_STRIP_RATIO]:1 自动竖滚阅读（不切图），误判无害
 *    （竖滚同样可看全图）。
 */
internal object PhotoViewerPolicy {

    /** pinch 收尾进入查看器的缩放阈值（清屏 1.3 之上留缓冲带，防「想清屏却进了查看器」） */
    const val ENTER_SCALE = 1.8f

    const val MIN_SCALE = 1f
    const val MAX_SCALE = 5f

    /** 双击放大档（Telegram 查看器同款 2.5x 落点） */
    const val DOUBLE_TAP_SCALE = 2.5f

    /** 下滑退出的位移阈值（相对查看器高度比例） */
    const val DISMISS_DISTANCE_FRACTION = 0.22f

    /** 条漫检测阈值：高/宽 ≥ 3（design §3.2 保守起步） */
    const val LONG_STRIP_RATIO = 3f

    /** 进入判定：pinch 手势收尾缩放值过阈即进查看器态 */
    fun shouldEnterViewer(endScale: Float): Boolean = endScale >= ENTER_SCALE

    /** 条漫判定（元数据宽高；未知尺寸=false 不误判） */
    fun isLongStrip(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && height.toFloat() / width.toFloat() >= LONG_STRIP_RATIO

    /** 位图口径条漫判定（解码后更准；同阈值同函数族） */
    fun isLongStrip(aspectWidthOverHeight: Float): Boolean =
        aspectWidthOverHeight > 0f && 1f / aspectWidthOverHeight >= LONG_STRIP_RATIO

    /** 双击目标档：放大态→回 1x；1x→[DOUBLE_TAP_SCALE] */
    fun doubleTapTarget(scale: Float): Float =
        if (scale > MIN_SCALE + 0.01f) MIN_SCALE else DOUBLE_TAP_SCALE

    /**
     * 下滑退出判定：仅 1x 态（放大态的竖向位移是平移语义）；位移过阈值即退。
     * [dragPx] 只取向下分量（向上甩不退出——查看器没有「上滑去哪」的语义）。
     */
    fun shouldDismiss(dragPx: Float, viewHeightPx: Float, scale: Float): Boolean =
        scale <= MIN_SCALE + 0.01f && viewHeightPx > 0f &&
            dragPx > viewHeightPx * DISMISS_DISTANCE_FRACTION

    /** 下滑跟手的背景透明度（1=全黑 → 0=全透）：随位移线性衰减 */
    fun dismissBackdropAlpha(dragPx: Float, viewHeightPx: Float): Float =
        if (viewHeightPx <= 0f) 1f else (1f - dragPx / viewHeightPx * 1.4f).coerceIn(0f, 1f)

    /**
     * 平移夹域（内容带口径，与卡级 pinch 同族政策）：放大态内容边缘不外露窗口背景
     * ——半窗 + 半内容差的保守夹域（zoom 1x 时夹域恒 0=内容锁死居中）。
     * [contentSpan]=内容在某轴的尺寸，[windowSpan]=窗口同轴尺寸，[scale]=当前缩放。
     */
    fun panClampAxis(contentSpan: Float, windowSpan: Float, scale: Float): Float {
        if (contentSpan <= 0f || windowSpan <= 0f || scale <= MIN_SCALE) return 0f
        val scaled = contentSpan * scale
        return ((scaled - windowSpan) / 2f).coerceAtLeast(0f)
    }

    /** 应用夹域（双轴一次夹完；窗口中心为原点） */
    fun clampPan(
        offset: Offset,
        contentW: Float,
        contentH: Float,
        windowW: Float,
        windowH: Float,
        scale: Float,
    ): Offset = Offset(
        offset.x.coerceIn(-panClampAxis(contentW, windowW, scale), panClampAxis(contentW, windowW, scale)),
        offset.y.coerceIn(-panClampAxis(contentH, windowH, scale), panClampAxis(contentH, windowH, scale)),
    )
}
