package dev.tgfeed.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.ui.theme.MotionTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * R301 错觉拼接运镜 A 案·feed 侧出场叠加层（design-dual-form §4 落地）：
 * 「正在看的视频缩成光点 + 背景沉入深空 + 白闪」（全程 MotionTokens.GalaxyWarpMs）
 * ——白闪峰值帧触发真实跳图
 * （[onFlashPeak]，R233 openTasteMapFly 链原样），接缝两帧断裂藏在白闪里；
 * R254 成品缓存秒开是接缝成立的前提（对侧即开即落）。
 *
 * 时间轴（p01，LinearOutSlowIn 加速感=被吸入）：
 *  - 0.00~0.72：封面整屏 Crop → 中心光点（scale 1→0.05），背景沉入黑；
 *  - 0.72~1.00：白闪 alpha 0→1（接缝隐藏区）；
 *  - ~0.87（540ms）：[onFlashPeak]——真实跳图在此帧触发（闪将满，下一帧就是星图）；
 *  - 播完 [onDone]（宿主清叠加层状态；多数情况下此时本页已离组合=自然消亡）。
 *
 * 交互闸：叠加期间吞掉一切触摸（运镜全程不许滑走/点开别的——运镜叙事完整性）。
 * 封面来源：CoverStore 抽帧表 → coverBytes minithumbnail 解码（IO）→ 纯黑 fallback
 * （光点收缩照常——无封面不阻塞运镜）。
 */
@Composable
internal fun GalaxyWarpOverlay(
    item: VideoItem,
    onFlashPeak: () -> Unit,
    onDone: () -> Unit,
) {
    val p = remember { Animatable(0f) }
    var peakFired by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        launch {
            delay(MotionTokens.GalaxyWarpFlashPeakMs.toLong()) // 白闪峰值帧（~87% 处）——真实跳图在闪将满时触发
            if (!peakFired) {
                peakFired = true
                onFlashPeak()
            }
        }
        p.animateTo(1f, tween(MotionTokens.GalaxyWarpMs, easing = LinearOutSlowInEasing))
        onDone()
    }
    // 封面：抽帧表（零成本）→ minithumbnail 解码（IO）——皆空=黑底纯光点
    val cover by produceState<ImageBitmap?>(null, item.key) {
        CoverStore.map[item.key]?.let {
            value = it
            return@produceState
        }
        val bytes = item.coverBytes ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
            }.getOrNull()
        }
    }
    val v = p.value
    Box(
        Modifier
            .fillMaxSize()
            .background(galaxyWarpSinkLayer(v))
            // 运镜期吞触摸（单 pointerInput 节点——纪律§四同节点双 pointerInput 互杀；
            // 全程消费按下/移动链，pager/卡片手势全断路）
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val coverNow = cover
        if (coverNow != null) {
            // 视频缩成光点（「我变成了那颗星」的空间指向性——A 案哲学：运镜的连续感
            // 是叙事不是工程事实）
            val shrink = 1f - (v * 0.95f).coerceIn(0f, 0.95f)
            Image(
                bitmap = coverNow,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = shrink
                        scaleY = shrink
                        alpha = if (v < 0.75f) 1f else (1f - (v - 0.75f) / 0.25f).coerceIn(0f, 1f)
                    },
            )
        }
        // 白闪（接缝隐藏区：0.72 起燃、1.0 满白——下一帧对侧星图从白里收束）
        val flash = ((v - 0.72f) / 0.28f).coerceIn(0f, 1f)
        if (flash > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = flash }
                    .background(Color.White),
            )
        }
    }
}
