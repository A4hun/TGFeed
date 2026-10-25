package dev.tgfeed.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
// R149 ④ CellChromeAutoHide 的稳定性标注
import androidx.compose.runtime.Stable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.tgfeed.core.feed.DownloadStatus
import dev.tgfeed.core.feed.FeedSource
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.feed.FeedViewModelFactory
import dev.tgfeed.core.feed.Playlist
import dev.tgfeed.core.feed.SearchFeedSource
import dev.tgfeed.core.playback.PlayerPool
import dev.tgfeed.core.store.GridCanvasSlot
import dev.tgfeed.core.store.GridCanvasSnapshot
import dev.tgfeed.core.store.LocalStore
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.core.td.safeInitialPlayUri
import dev.tgfeed.ui.components.HeartBurst
import dev.tgfeed.ui.components.LikeHeartsLayer
import dev.tgfeed.ui.components.OverlayChipBg
import dev.tgfeed.ui.components.fatalReasonOf
import dev.tgfeed.ui.components.kit.AppListItem
import dev.tgfeed.ui.components.kit.AppLoading
import dev.tgfeed.ui.components.kit.AppSheet
import dev.tgfeed.ui.components.withOverlayShadow
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 格子层装饰件合集（R129 从 GridScreen.kt 按职责拆出，纯搬运零逻辑改动）。
 * 收纳：L0 底部频道名行 / × 关闭钮 / 焦点环 / 出声声波 / 焦点格音量胶囊 /
 * 调整大小角柄 glyph——三种媒体格（视频/图片/音频）与画布层共用的格子级视觉件。
 * 媒体格本体在 GridMediaCells.kt / GridVideoCell.kt。
 */

// ---------------- R149 ④ 格子 chrome 空闲自动隐藏 ----------------

/** R149 ④：真在播且无交互静默这么久后收起格子 chrome（×/音量/声波/频道名），点按即唤回 */
internal const val CELL_CHROME_AUTO_HIDE_MS = 3_500L

/**
 * R149 ④ 格子 chrome 自动隐藏的会话状态：播放中无交互 [CELL_CHROME_AUTO_HIDE_MS]
 * 后静默收起（配合 alpha 渐隐，"播放界面的组件会静默消失"），任何点按/双击/拖拽/
 * 调音量 [kick] 唤回并重置计时。暂停/未播不计时（控制常驻是暂停态的标准语义）。
 */
@Stable
internal class CellChromeAutoHide {
    var on by mutableStateOf(true)
    var tick by mutableIntStateOf(0)

    /** 交互：唤回 chrome 并重置自动隐藏计时 */
    fun kick() {
        on = true
        tick++
    }
}

/**
 * 每格一份的 chrome 自动隐藏状态（[key] 变化 = 换片，chrome 复位可见）。
 * [active] 为 true（在播）且无交互时才计时收起。
 */
@Composable
internal fun rememberCellChrome(key: Any?, active: Boolean): CellChromeAutoHide {
    val chrome = remember(key) { CellChromeAutoHide() }
    LaunchedEffect(chrome.tick, active) {
        if (chrome.on && active) {
            delay(CELL_CHROME_AUTO_HIDE_MS)
            chrome.on = false
        }
    }
    return chrome
}

/**
 * L0 底部频道名行（§3.1/§3.3）：28dp 高行，频道名 11sp（左，换片 Crossfade 150ms）
 * + 已赞小红心 12dp（右，仅已赞）。
 * R162 零遮罩：旧版是 28dp 透明→黑0.5 渐变 scrim（频道名可读性靠压暗画面）——
 * 与用户五次同向裁决冲突，渐变删除；频道名文字改 withOverlayShadow 描边
 * （视频页干净基准同款），28dp 行高与排布保持不变（进度条压最底缘的布局不动）。
 */
@Composable
internal fun CellBottomLabel(
    title: String,
    liked: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(28.dp),
    ) {
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 6.dp, end = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Crossfade(
                targetState = title,
                modifier = Modifier.weight(1f),
                animationSpec = tween(TgFeedTokens.AnimFast),
                label = "title",
            ) { t ->
                Text(
                    t,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelSmall.withOverlayShadow(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (liked) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = "已点赞",
                    tint = TgFeedTokens.AccentRed,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(TgFeedTokens.IconXs),
                )
            }
        }
    }
}

/**
 * 格子 × 关闭钮（§4.3）：仅焦点格可见（L1），fade+scale 0.8→1 随焦点进出；
 * 视觉 26dp 圆 / 热区 40dp；OverlayBg + 0.5dp 白 α0.12 发丝描边（亮画面勾轮廓）；
 * 按压 scale 0.88 + 轻触觉。
 */
@Composable
internal fun CellCloseButton(
    t: Float,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = tween(TgFeedTokens.AnimFast),
        label = "press",
    )
    Box(
        modifier
            .size(TgFeedTokens.TouchMin)
            .graphicsLayer {
                alpha = t
                val s = (0.8f + 0.2f * t) * pressScale
                scaleX = s
                scaleY = s
            }
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClose()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(TgFeedTokens.OverlayBg)
                .border(0.5.dp, Color.White.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "关闭此格",
                tint = Color.White,
                modifier = Modifier.size(TgFeedTokens.IconSm),
            )
        }
    }
}

/**
 * 焦点环（§4.1）：2dp AccentRed 内嵌圆角环（inset 1dp、圆角跟随格子 CellRadius）、
 * 静态不呼吸；环内侧 4dp 柔光带（外发光会被格子圆角 clip 裁掉，故向内做）——
 * "光"代替"闪"表达活跃。[t] = 焦点错峰进度（0~1），落位动画 scale 1.06→1 + alpha 0→1。
 */
@Composable
internal fun CellFocusRing(t: Float) {
    if (t <= 0f) return
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = t
                val s = 1.06f - 0.06f * t
                scaleX = s
                scaleY = s
            },
    ) {
        val inset = 1.dp.toPx()
        val glowInset = inset + 2.dp.toPx()
        // 柔光：环内侧 4dp 低透明光带
        drawRoundRect(
            color = TgFeedTokens.AccentRed.copy(alpha = 0.30f),
            topLeft = Offset(glowInset, glowInset),
            size = Size(size.width - glowInset * 2, size.height - glowInset * 2),
            cornerRadius = CornerRadius(
                (TgFeedTokens.CellRadius.toPx() - glowInset).coerceAtLeast(0f),
            ),
            style = Stroke(width = 4.dp.toPx()),
        )
        // 主环：2dp 描边、inset 1dp、圆角跟随格子
        drawRoundRect(
            color = TgFeedTokens.AccentRed,
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2, size.height - inset * 2),
            cornerRadius = CornerRadius(
                (TgFeedTokens.CellRadius.toPx() - inset).coerceAtLeast(0f),
            ),
            style = Stroke(width = TgFeedTokens.FocusRingWidth.toPx()),
        )
    }
}

/**
 * 出声指示·声波 3 柱（§4.1）：3×2.5dp 圆头柱，高度在 4/10/6dp 间三角波错相动画
 * （600ms 循环，相位各错 1/3）；仅 [active]（正在出声 = isPlaying 且有音量）时播动画，
 * 否则定格成等高小柱。（600ms=连续指示器周期非转场——MotionTokensPolicyTest 豁免清单登记）
 */
@Composable
internal fun CellSoundBars(active: Boolean, modifier: Modifier = Modifier) {
    val phase = if (active) {
        val t by rememberInfiniteTransition(label = "soundBars").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Restart),
            label = "phase",
        )
        t
    } else {
        -1f
    }
    Canvas(modifier.size(width = 14.dp, height = 12.dp)) {
        val barW = 2.5.dp.toPx()
        val gap = 2.dp.toPx()
        val minH = 4.dp.toPx()
        val maxHs = floatArrayOf(10.dp.toPx(), 6.dp.toPx(), 10.dp.toPx())
        for (i in 0..2) {
            val h = if (phase < 0f) {
                minH
            } else {
                val p = (phase + i / 3f) % 1f
                val tri = 1f - kotlin.math.abs(2f * p - 1f)
                minH + tri * (maxHs[i] - minH)
            }
            drawRoundRect(
                color = Color.White.copy(alpha = 0.9f),
                topLeft = Offset(i * (barW + gap), size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}

/**
 * 音量胶囊（P0-2 整改，视觉对齐 kit AppSlider；R149 ⑥ 去掉灰色底）：只剩内嵌
 * 2dp 细轨——未激活段白 20% / 激活段 AccentRed；胶囊位高 [TgFeedTokens.VolumePillHeight]
 * 28dp 本身即热区，点/拖轨道直接调音量（手势换算与旧细线版一致），拖动中格顶中央
 * 飘百分比小字（不常驻）。
 * 仅焦点格组合（外层 focusT 门控，非焦点格零 chrome）；muteAll 时外层不组合本件。
 */
@Composable
internal fun CellVolumePill(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 轨道触控区宽度实测（点按/拖动按位置比例换算音量）
    var draggingVol by remember { mutableStateOf(false) }
    var trackW by remember { mutableIntStateOf(0) }
    Box(modifier) {
        // 热区：28dp 高的通栏（R149 ⑥ 拆掉 OverlayBg 胶囊底，只留细轨本体；
        // 热区保留整高，点按容错不变）
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(TgFeedTokens.VolumePillHeight),
            contentAlignment = Alignment.Center,
        ) {
            // 轨道触控区：左右留 12dp 视觉内边距，热区 18dp 垂直居中于胶囊内；
            // 手势换算是旧细线版原样迁移（trackW 即本区宽，position.x 与本区同坐标系）
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .height(18.dp)
                    .onSizeChanged { trackW = it.width }
                    .pointerInput(Unit) {
                        detectTapGestures { pos ->
                            if (trackW > 0) onVolumeChange((pos.x / trackW).coerceIn(0f, 1f))
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { pos ->
                                draggingVol = true
                                if (trackW > 0) onVolumeChange((pos.x / trackW).coerceIn(0f, 1f))
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                if (trackW > 0) {
                                    onVolumeChange((change.position.x / trackW).coerceIn(0f, 1f))
                                }
                            },
                            onDragEnd = { draggingVol = false },
                            onDragCancel = { draggingVol = false },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                // 2dp 细轨：白 20% 底 + AccentRed 激活段（kit AppSlider 同一规格）
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                        .background(TgFeedTokens.TextHigh.copy(alpha = 0.20f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(volume.coerceIn(0f, 1f))
                            .height(2.dp)
                            .background(TgFeedTokens.AccentRed),
                    )
                }
            }
        }
        // 拖动中：格顶中央飘百分比（不常驻；R162 收敛共享 token，不自造 alpha）
        if (draggingVol) {
            Text(
                "${(volume.coerceIn(0f, 1f) * 100).roundToInt()}%",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp)
                    .background(OverlayChipBg, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * 调整大小角柄 glyph（§4.4）：两条 45° 平行短斜线（长 10/6dp、2dp 圆头、白 α0.9），
 * 替代 Fullscreen 图标误用（与"全屏单播"菜单项、ActionRail 全屏三处撞语义）。
 * 视觉 24dp 圆（SurfaceHigh α0.85 + 1dp 白 α0.25 描边）；拖动中 scale 1.15、描边变 AccentRed。
 */
@Composable
internal fun GridResizeHandleGlyph(active: Boolean) {
    val borderColor by animateColorAsState(
        targetValue = if (active) TgFeedTokens.AccentRed else Color.White.copy(alpha = 0.25f),
        animationSpec = tween(TgFeedTokens.AnimFast),
        label = "border",
    )
    val scale by animateFloatAsState(
        targetValue = if (active) 1.15f else 1f,
        animationSpec = tween(TgFeedTokens.AnimFast),
        label = "scale",
    )
    Box(
        Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .size(24.dp)
            .clip(CircleShape)
            .background(TgFeedTokens.OverlayBgStrong)
            .border(1.dp, borderColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(12.dp)) {
            val w = 2.dp.toPx()
            val c = Color.White.copy(alpha = 0.9f)
            // 长斜线 10dp
            drawLine(
                color = c,
                start = Offset(2.5.dp.toPx(), 9.5.dp.toPx()),
                end = Offset(9.5.dp.toPx(), 2.5.dp.toPx()),
                strokeWidth = w,
                cap = StrokeCap.Round,
            )
            // 短斜线 6dp（平行、向右下错位）
            drawLine(
                color = c,
                start = Offset(6.dp.toPx(), 10.5.dp.toPx()),
                end = Offset(10.5.dp.toPx(), 6.dp.toPx()),
                strokeWidth = w,
                cap = StrokeCap.Round,
            )
        }
    }
}
