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
import androidx.compose.ui.graphics.Brush
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
 * 画布/屏幕级覆盖层合集（R129 从 GridScreen.kt 按职责拆出，纯搬运零逻辑改动）。
 * 收纳：空画布引导态 / 「从片单添加」选择器 / 瞬态提示胶囊（捏合缩放徽章、
 * "换了一拨"）/ 悬浮拖拽落区提示 / "恢复 N 格"胶囊 / PK 擂台投票钮——
 * 全部由 GridScreen 主函数按状态组合调用，逻辑与视觉零改动。
 */

/**
 * 空画布引导态（P0-2 / 走查 G-A：替代"死黑屏 + 小白点"）：AppEmptyState 风格——
 * 居中大图标 + 主文案"分屏画布是空的" + 副文案引导 + AccentRed 主按钮「从片单添加」，
 * 可选次级文字动作（如"恢复全部格子"）。
 * 不直接复用 kit AppEmptyState 的原因：kit 版没有次级动作槽、尺寸不走 uiScale，
 * 且本轮纪律只允许改本文件——这里按 kit 同一视觉规格落地
 * （48dp 图标 TextLow / bodyLarge TextMedium 主文案 / bodySmall TextLow 副文案 /
 * RadiusPill 红底白字主钮），所有 dp 尺寸乘 [uiScale] 跟随窗口档位。
 */
@Composable
internal fun GridEmptyGuide(
    uiScale: Float,
    onAddFromPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(TgFeedTokens.GapXl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.GridView,
            contentDescription = null,
            modifier = Modifier.size(48.dp * uiScale),
            tint = TgFeedTokens.TextLow,
        )
        Spacer(Modifier.height(TgFeedTokens.GapLg * uiScale))
        Text(
            text = "分屏画布是空的",
            style = MaterialTheme.typography.bodyLarge,
            color = TgFeedTokens.TextMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(TgFeedTokens.GapSm * uiScale))
        Text(
            text = "从播放页长按 → 加入分屏，或点下方从片单添加",
            style = MaterialTheme.typography.bodySmall,
            color = TgFeedTokens.TextLow,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(TgFeedTokens.GapXl * uiScale))
        // 主按钮：AccentRed 底白字胶囊（kit AppEmptyState 主钮同一规格）
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                .background(TgFeedTokens.AccentRed)
                .clickable(onClick = onAddFromPlaylist)
                .padding(
                    horizontal = TgFeedTokens.GapXl * uiScale,
                    vertical = TgFeedTokens.GapMd * uiScale,
                ),
        ) {
            Text(
                text = "从片单添加",
                style = MaterialTheme.typography.labelLarge,
                color = TgFeedTokens.TextHigh,
            )
        }
        // 次级文字动作（可选）：低权重纯文字，不抢主按钮
        if (secondaryText != null && onSecondary != null) {
            Spacer(Modifier.height(TgFeedTokens.GapMd * uiScale))
            Text(
                text = secondaryText,
                style = MaterialTheme.typography.labelLarge,
                color = TgFeedTokens.TextMedium,
                modifier = Modifier
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                    .clickable(onClick = onSecondary)
                    .padding(
                        horizontal = TgFeedTokens.GapLg,
                        vertical = TgFeedTokens.GapSm,
                    ),
            )
        }
    }
}

/**
 * 「从片单添加」选择器（P0-2 新增，空画布引导态主按钮 / 工具岛添加槽共用）：
 * kit AppSheet 壳 + AppListItem 行，列出全部命名片单（与 Feed 同源）；
 * 点选 = 整单条目经 onAddCustom 倒入分屏片单（gridPlaylist，容器层去重）。
 */
// AppSheet 签名含实验性 SheetState（kit 内部已 OptIn，但调用方也需标注）
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GridPlaylistPickSheet(
    playlists: List<Playlist>,
    onPick: (Playlist) -> Unit,
    onDismiss: () -> Unit,
) {
    AppSheet(onDismiss = onDismiss, title = "从片单添加") {
        if (playlists.isEmpty()) {
            // 空片单引导：告诉用户片单从哪来（播放页长按 → 加入片单）
            Text(
                "还没有片单：在播放页长按 → 加入片单，再来这里整单上屏",
                color = TgFeedTokens.TextLow,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(
                    horizontal = TgFeedTokens.GapLg,
                    vertical = TgFeedTokens.GapMd,
                ),
            )
        } else {
            playlists.forEach { pl ->
                AppListItem(
                    title = pl.name,
                    subtitle = "${pl.count} 条",
                    onClick = { onPick(pl) },
                )
            }
        }
    }
}

/**
 * 统一瞬态提示胶囊（§4.6 OverlayToast）：缩放徽章 / "换了一拨" 等全部收编成同一形态——
 * 全圆角、13sp Medium、可带 14dp 前导图标、最小高 32dp；底色 R162 收敛 OverlayBg
 * （原 0.6 散装 alpha）；fade + scale 0.92→1 入 / fade 出（旧版全是 if 硬切；
 * R178 收编 AnimFast 150/150——原 120 出场裸字面量归三档，退场=进场合规）。
 */
@Composable
internal fun GridOverlayToast(
    visible: Boolean,
    text: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(TgFeedTokens.AnimFast)) +
            scaleIn(tween(TgFeedTokens.AnimFast), initialScale = 0.92f),
        exit = fadeOut(tween(TgFeedTokens.AnimFast)),
    ) {
        Row(
            Modifier
                .heightIn(min = 32.dp)
                .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                .background(TgFeedTokens.OverlayBg)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = TgFeedTokens.TextHigh,
                    modifier = Modifier.size(TgFeedTokens.IconSm),
                )
                Spacer(Modifier.size(6.dp))
            }
            Text(
                text,
                color = TgFeedTokens.TextHigh,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
            )
        }
    }
}

/**
 * 悬浮落区提示（§4.5）：顶部居中悬浮胶囊 + 屏幕顶缘 56dp 红色渐变光洗（暗示落区范围），
 * 取代旧版顶部 15% 高大色板。常态黑 α0.55 描边胶囊；指针进区整体填 AccentRed、
 * 文案切"松手，变成悬浮窗"、弹簧放大 1.05；fade + translateY -12→0dp 进出场
 * （R178 收编：原 180/120 裸字面量对归 AnimMed 进 / AnimFast 出，进>出关系保留）。
 */
@Composable
internal fun PopoutDropOverlay(
    visible: Boolean,
    inZone: Boolean,
    modifier: Modifier = Modifier,
) {
    val slidePx = with(LocalDensity.current) { 12.dp.roundToPx() }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(TgFeedTokens.AnimMed)) + slideInVertically(tween(TgFeedTokens.AnimMed)) { -slidePx },
        exit = fadeOut(tween(TgFeedTokens.AnimFast)) + slideOutVertically(tween(TgFeedTokens.AnimFast)) { -slidePx },
    ) {
        Box(Modifier.fillMaxWidth()) {
            // 顶缘渐变光洗：红 α0.12 → 透明
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(TgFeedTokens.AccentRed.copy(alpha = 0.12f), Color.Transparent),
                        ),
                    ),
            )
            val bg by animateColorAsState(
                targetValue = if (inZone) TgFeedTokens.AccentRed else TgFeedTokens.OverlayBg,
                animationSpec = tween(TgFeedTokens.AnimFast),
                label = "bg",
            )
            val scale by animateFloatAsState(
                targetValue = if (inZone) 1.05f else 1f,
                // 拖拽耦合反馈单现场物理（MotionTokensPolicyTest 豁免清单登记）
                animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
                label = "scale",
            )
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .height(40.dp)
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                    .background(bg)
                    .border(
                        0.5.dp,
                        Color.White.copy(alpha = 0.15f),
                        RoundedCornerShape(TgFeedTokens.RadiusPill),
                    )
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.PictureInPicture,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    if (inZone) "松手，变成悬浮窗" else "拖到此处悬浮播放",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                )
            }
        }
    }
}

// R230 追加2：GridRestorePill（"恢复 N 格"主界面胶囊）删除——用户实锤「没啥用」；
// 唯一逃生口保留在 GridFeedEmptyArea 空态「恢复全部格子」（全关格死局的出口）

/**
 * PK 擂台投票钮覆盖层：与格子同位的覆盖 Box（只有胶囊热区吃点击，其余区域透传给格子手势），
 * 底部半透明黑胶囊"👈 左边赢 / 👉 右边赢"（OverlayBg + 0.5dp 白 α0.2 发丝描边，
 * R162 收敛共享 token——原 0.45 散装 alpha）。
 * 底 padding 72dp 避开格内 L0 频道名行（28dp）与 L1 音量胶囊（底部 36dp 起）。
 * [cell]/[unitW]/[unitH] 是画布逻辑坐标，整个覆盖层在画布层内、随捏合缩放。
 */
@Composable
internal fun PkVoteOverlay(
    cell: CellRect,
    unitW: Float,
    unitH: Float,
    isLeft: Boolean,
    onVote: () -> Unit,
) {
    val density = LocalDensity.current
    Box(
        Modifier
            .offset {
                IntOffset(
                    (cell.x * unitW).roundToInt(),
                    (cell.y * unitH).roundToInt(),
                )
            }
            .size(
                width = with(density) { (cell.w * unitW).toDp() },
                height = with(density) { (cell.h * unitH).toDp() },
            )
            // 与格子同款半缝内缩，投票区与可视格对齐
            .padding(TgFeedTokens.GridGap / 2),
    ) {
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 72.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                .background(TgFeedTokens.OverlayBg)
                .border(
                    0.5.dp,
                    Color.White.copy(alpha = 0.2f),
                    RoundedCornerShape(TgFeedTokens.RadiusPill),
                )
                .clickable(onClick = onVote)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (isLeft) "👈 左边赢" else "👉 右边赢",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
            )
        }
    }
}
