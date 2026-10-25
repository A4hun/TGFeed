package dev.tgfeed.ui.components.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.tgfeed.ui.theme.TgFeedTokens

/**
 * 音量/进度通用滑条：细轨 + 小拇指，视频浮层与设置页共用同一副面孔。
 *
 * 什么时候用它：
 * - 音量调节、亮度调节、进度拖动等连续值输入；
 * - 浮在深色/视频画面上的滑条（轨色是半透明白，任何底上都看得清）。
 *
 * 什么时候不用：
 * - 离散档位（3 档清晰度之类）需要 step 标记时，本组件不画刻度，
 *   请用 chips（[AppPill]）或自行扩展；
 * - 双滑块范围选择不用它（那是 RangeSlider 的场景，kit 暂未提供）。
 *
 * 视觉规格（统一）：
 * - 轨高 2dp、圆头；未激活段白 20%，激活段 [TgFeedTokens.AccentRed]；
 * - 拇指 10dp 纯白圆点，无默认 Material 大圆饼。
 *
 * @param value 当前值，落在 [valueRange] 内。
 * @param onValueChange 拖动回调（实时，调用方自己存 state）。
 * @param modifier 外部布局修饰符。
 * @param valueRange 取值区间，默认 0f..1f（音量）；进度可传 0f..durationSec。
 * @param onValueChangeFinished 松手回调，适合做一次性提交（如 seek）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        thumb = {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(TgFeedTokens.TextHigh),
            )
        },
        track = { sliderState ->
            val range = sliderState.valueRange
            val fraction = if (range.endInclusive == range.start) 0f
            else ((sliderState.value - range.start) / (range.endInclusive - range.start))
                .coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(TgFeedTokens.TextHigh.copy(alpha = 0.20f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(TgFeedTokens.AccentRed),
                )
            }
        },
    )
}
