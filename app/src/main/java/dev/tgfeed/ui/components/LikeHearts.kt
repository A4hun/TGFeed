package dev.tgfeed.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlin.math.roundToInt

/** 抖音点赞红（= 设计系统强调色，色值单源在 TgFeedTokens） */
internal val LikeRed = TgFeedTokens.AccentRed

/** 收藏星标黄（= 设计系统星标色） */
internal val FavYellow = TgFeedTokens.StarYellow

/** 一颗正在飘的心（x/y 为手势层内像素坐标，即双击落点） */
internal class HeartBurst(
    val id: Long,
    val x: Float,
    val y: Float,
    val rotation: Float,
)

/** 双击点赞的飘心层：每颗心放大、上浮、淡出后回调移除 */
@Composable
internal fun LikeHeartsLayer(
    hearts: List<HeartBurst>,
    onHeartDone: (Long) -> Unit,
) {
    hearts.forEach { heart ->
        FlyingHeart(heart, onHeartDone)
    }
}

@Composable
private fun FlyingHeart(heart: HeartBurst, onDone: (Long) -> Unit) {
    val progress = remember(heart.id) { Animatable(0f) }
    val currentOnDone by rememberUpdatedState(onDone)
    LaunchedEffect(heart.id) {
        // 粒子物理：时长=飘升行程/速度（非转场——MotionTokensPolicyTest 豁免清单登记）
        progress.animateTo(1f, tween(durationMillis = 850, easing = FastOutSlowInEasing))
        currentOnDone(heart.id)
    }
    val p = progress.value
    Icon(
        imageVector = Icons.Filled.Favorite,
        contentDescription = null,
        tint = LikeRed,
        modifier = Modifier
            // 心的中心对准双击点，随进度上浮
            .offset {
                IntOffset(
                    (heart.x - 40.dp.toPx()).roundToInt(),
                    (heart.y - 40.dp.toPx() - 220.dp.toPx() * p).roundToInt(),
                )
            }
            .size(80.dp)
            // 柔光投影让红心压在亮画面上也立体
            .overlayIconShadow()
            .graphicsLayer {
                // 先弹出放大，再缓慢回缩；尾段淡出
                val scale = if (p < 0.2f) 0.4f + p * 4f else 1.2f - (p - 0.2f) * 0.25f
                scaleX = scale
                scaleY = scale
                rotationZ = heart.rotation
                alpha = if (p < 0.7f) 1f else 1f - (p - 0.7f) / 0.3f
            },
    )
}
