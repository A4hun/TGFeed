package dev.tgfeed.ui.components

import androidx.media3.common.VideoSize
import dev.tgfeed.core.td.VideoVariant

/**
 * 清晰度档位与画面几何的纯函数件（R131 从 VideoPage.kt 按职责拆出，纯搬运零逻辑改动）。
 * 收纳：清晰度就近匹配 [pickVariantByHeight]、智能降档映射 [degradeQualityHeight]、
 * 播放器上报尺寸 → 真实显示宽高比 [displayRatioOf]。
 * 起播选档/热切换/降档提示的状态编排仍在 VideoPage.kt。
 */

/**
 * 清晰度档位就近匹配（R122 修"精确相等匹配静默回落原画"）：
 * variants 的高度来自 TDLib alternativeVideos 的真实像素高（640/958/1284 这类任意值），
 * 全局偏好（480/720/1080 等标准档）跨视频应用时大概率不精确命中——
 * 旧实现 firstOrNull{height==偏好} 不命中即回落主档原画（设 480p 想省流反而播原画，方向相反）。
 *
 * 现改为 nearest-not-above：
 *  - 优先取"≤目标高度的最高档"——不超过用户预算的前提下给最好画质（省流语义不超标）；
 *  - 目标低于全部档位时取最低档（最接近偏好的上方档，仍比原画省流）；
 *  - 目标 ≤0（自动档）或 variants 为空返回 null（调用方回落主档）。
 */
internal fun pickVariantByHeight(variants: List<VideoVariant>, targetHeight: Int): VideoVariant? {
    if (targetHeight <= 0 || variants.isEmpty()) return null
    return variants.filter { it.height in 1..targetHeight }.maxByOrNull { it.height }
        ?: variants.minByOrNull { it.height }
}

/**
 * 智能降档映射（计量网络/省电模式生效时，目标清晰度高度降一档）：
 * 1080 及以上 → 720；720 档 → 480；自动档（0）→ 720；
 * 480 及以下已是最低档，不再降（继续降会变"全糊"，体验损失大于省流收益）。
 * 仅作用于起播选档入参；功能面板显示的仍是用户原设定档。
 */
internal fun degradeQualityHeight(h: Int): Int = when {
    h <= 0 -> 720   // 自动档 → 720（降档后仍需一个具体目标档去匹配 variants）
    h > 720 -> 720  // 1080/1440/2160 → 720
    h > 480 -> 480  // 720 → 480
    else -> h       // 480 及以下：触底不降
}

/**
 * 由播放器上报的 VideoSize 算"真实显示宽高比"（宽/高，>1 横屏）：
 * raw = width×像素宽高比/height，旋转 90°/270° 时取倒数——
 * 与 media3 1.4.1 PlayerView.updateAspectRatio 的字节码实现逐行对应，
 * 保证方向判定与播放器实际装帧完全一致；尺寸无效（0×0/未就绪）返回 0
 */
internal fun displayRatioOf(videoSize: VideoSize): Float {
    val w = videoSize.width
    val h = videoSize.height
    if (w <= 0 || h <= 0) return 0f
    val raw = w * videoSize.pixelWidthHeightRatio / h
    return if (videoSize.unappliedRotationDegrees == 90 || videoSize.unappliedRotationDegrees == 270) {
        1f / raw
    } else {
        raw
    }
}
