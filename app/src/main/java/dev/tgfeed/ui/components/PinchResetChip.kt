package dev.tgfeed.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.tgfeed.ui.theme.TgFeedTokens

/**
 * R174b 自由缩放三件套之三：悬浮还原标识（VideoPage / PhotoCard 共用，纪律§一）。
 *
 * 显隐判据在宿主（[PinchCleanHost.isTransformed]：scale≠1 或有平移）；点按走
 * 双击退通道②同一套（dispatch FullReset + 0.82/500 复位弹簧，MotionTokens
 * ZoomReset——复用现成弹簧不自造），复位落 1x 后 chip 随判据翻假淡出。
 *
 * 视觉与动效纪律：
 * - **避让**：TopStart（右轨=BottomEnd、进度线=BottomCenter、教学气泡=TopCenter、
 *   全屏退出钮=TopEnd、降档胶囊=TopEnd@56——本位与谁都不撞；且**不进渐隐组**
 *   ——chrome α 包裹组里的成员会跟着清屏退场，复位出口恰恰要在清屏里活着）；
 * - **R162 token 取色**：OverlayBg 胶囊底 + OverlayHairline 发丝描边 + TextHigh，
 *   与 BoostStatusPill/教学气泡同一浮层语言（零遮罩政策：不压染色渐变）；
 * - **MotionTokens 动效**：显隐走 fadeIn/fadeOut(AnimFast)（与教学气泡同档同纪律
 *   「退场 ≤ 进场」150/150）；复位本身走 ZoomReset 弹簧（调用方持有）。
 */
@Composable
internal fun BoxScope.PinchResetChip(
    visible: Boolean,
    onReset: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .align(Alignment.TopStart)
            .statusBarsPadding()
            .padding(top = 56.dp, start = 16.dp),
        enter = fadeIn(tween(TgFeedTokens.AnimFast)),
        exit = fadeOut(tween(TgFeedTokens.AnimFast)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(TgFeedTokens.OverlayBg, RoundedCornerShape(TgFeedTokens.RadiusPill))
                .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusPill))
                .clickable(onClick = onReset)
                // 高度凑 TouchMin：label 行 ~20dp + 上下 10dp ≈ 40dp 最小热区
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .semantics { contentDescription = "复位缩放" },
        ) {
            Icon(
                Icons.Outlined.FitScreen,
                contentDescription = null,
                tint = TgFeedTokens.TextHigh,
                modifier = Modifier.size(16.dp),
            )
            Text(
                "复位",
                color = TgFeedTokens.TextHigh,
                style = MaterialTheme.typography.labelMedium.withOverlayShadow(),
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}
