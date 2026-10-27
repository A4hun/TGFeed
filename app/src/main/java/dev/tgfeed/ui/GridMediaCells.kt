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
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateMap
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
 * 分屏媒体格合集（R129 从 GridScreen.kt 按职责拆出，纯搬运零逻辑改动）：
 * 图片格 GridPhotoCell / 音频格 GridAudioCell / 三媒体分派器 GridCellDispatch。
 * 格子装饰件在 GridCellChrome.kt；视频格本体在 GridScreen.kt（后续刀次外移）。
 */

// ==================== T0-2 追加：图片格 / 音频格（AUDIT-GAPS3 #2） ====================

/**
 * 分屏图片格（mediaType=1，PhotoCard 的格子简化版）：
 * 纯图铺满（Crop）+ 图集角标（albumId≠0）+ 底部频道名，不进播放器池、不占解码路数。
 * 修前图片条目被喂给 ExoPlayer：jpg 必解码失败 → 重试×3 → 连环自动换片。
 *
 * 解码链照 PhotoCard（W1+W2 同步重修）：先 minithumbnail（coverBytes）秒出占位，
 * 组合后经 TdLibGateway.loadPhotoViewPath 同步拉"浏览档"（≈1280px，photoViewFileId）
 * 拿本地路径，IO 线程解码换上。不再走 ensurePlayable——文件未下完时它返回
 * http 流服务器地址，decodeFile 必败且无重试 → 永久马赛克（与 PhotoCard 同病同修）。
 * 浏览档 ≤1280px（最大约 6.5MB 解码内存），格子小仍不做采样降级。
 * 手势（简化）：单击 = 聚焦；双击 = 点赞飘心；焦点格右上角 × 关格。
 * 无长按拖拽换格/长按菜单（小格空间与手势复杂度权衡，见交付风险）。
 */
@Composable
internal fun GridPhotoCell(
    item: VideoItem,
    gateway: TdGateway,
    focused: Boolean,
    // R432 重组收敛：点赞表 State 本体下传，本格叶子内现查（订阅作用域=本格）
    likes: State<Set<String>>,
    watched: Boolean,
    chromeless: Boolean,
    onFocus: () -> Unit,
    onToggleLike: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val liked = item.key in likes.value
    var playUri by remember(item.key) { mutableStateOf(item.playUri) }
    // 双击点赞飘心列表（与视频格同一套 HeartBurst）
    var hearts by remember(item.key) { mutableStateOf(listOf<HeartBurst>()) }

    // 组合即触发浏览档拉取（本格只在可见区才 compose，天然带可见性闸）。
    // W2：图片条目走 loadPhotoViewPath 同步下载拿真实本地路径；priority=32（W3）
    LaunchedEffect(item.key) {
        if (playUri == null) {
            val lib = gateway as? TdLibGateway
            playUri = if (lib != null) {
                runCatching { lib.loadPhotoViewPath(item) }.getOrNull()
            } else {
                // 非 TDLib 网关（Mock 等）：保持原 ensurePlayable 链
                runCatching { gateway.ensurePlayable(item) }.getOrNull()
            }
        }
    }

    // 图片解码：IO 线程；本地文件优先，退化 minithumbnail 占位
    val image: ImageBitmap? by produceState<ImageBitmap?>(initialValue = null, item.key, playUri) {
        value = withContext(Dispatchers.IO) {
            val uri = playUri
            val fromFile = uri?.takeIf { it.isNotBlank() }?.let { path ->
                runCatching {
                    BitmapFactory.decodeFile(path.removePrefix("file://"))?.asImageBitmap()
                }.getOrNull()
            }
            fromFile ?: item.coverBytes?.let { bytes ->
                runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
                }.getOrNull()
            }
        }
    }

    val currentOnTap = rememberUpdatedState<() -> Unit>(newValue = { onFocus() })
    val currentOnDoubleTap = rememberUpdatedState<(Offset) -> Unit>(newValue = { offset ->
        if (!liked) onToggleLike()
        hearts = hearts + HeartBurst(
            id = Random.nextLong(),
            x = offset.x,
            y = offset.y,
            rotation = Random.nextInt(-24, 25).toFloat(),
        )
    })

    Box(
        modifier
            .clip(RoundedCornerShape(TgFeedTokens.CellRadius))
            .background(Color.Black),
    ) {
        val bmp = image
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = item.caption.ifBlank { null },
                // R217 ③ 已看压暗退役（同 GridVideoCell 一刀）：亮度统一
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            // 封面未就绪占位转圈：内联统一 22dp/2dp（动画禁用退化态为 2dp 圆点）
            CircularProgressIndicator(
                Modifier.align(Alignment.Center).size(22.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        }
        // 已看降饱和叠层退役（R217 ③ 同视频格一刀）：亮度统一
        // 手势层：单击聚焦 / 双击点赞飘心（不消费位移，画布平移捏合不受影响）
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(item.key) {
                    detectTapGestures(
                        onTap = { currentOnTap.value() },
                        onDoubleTap = { offset -> currentOnDoubleTap.value(offset) },
                    )
                },
        )
        // 图集角标（左上）：同一条相册消息拆出的多图条目，提示"这是一组"
        if (item.albumId != 0L && !chromeless) {
            Text(
                "图集",
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(
                        TgFeedTokens.OverlayBg,
                        RoundedCornerShape(TgFeedTokens.RadiusPill),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        // 双击飘心层
        LikeHeartsLayer(
            hearts = hearts,
            onHeartDone = { id -> hearts = hearts.filterNot { it.id == id } },
        )
        // × 关格：仅焦点格可见（复用视频格同一组件；t 直接给 1f，不做错峰动画）
        if (focused && !chromeless) {
            CellCloseButton(
                t = 1f,
                onClose = onClose,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        // 底部频道名行（与视频格同一组件；R162 起无渐变，描边可读）
        if (!chromeless) {
            CellBottomLabel(
                title = item.channelTitle,
                liked = liked,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
        // 焦点环（复用视频格同一组件）
        if (!chromeless) CellFocusRing(t = if (focused) 1f else 0f)
    }
}

/**
 * 分屏音频格（mediaType=2，AudioCard 的格子简化版）：
 * 专辑封面铺满（无封面回落渐变+音符"♪"）+ 中央播放/暂停钮 + 底部进度条 + 频道名。
 * 自管 ExoPlayer（不进 PlayerPool、不占视频解码路数）；修前音频格是黑画面出声。
 *
 * 播放语义（简化）：
 * - 不自动开播：单击 = 聚焦 + 播放/暂停切换；播完上抛 onEnded 走既有换片链
 * - 音量随分屏声音策略：焦点格 1.0 / 非焦点 BACKGROUND_VOLUME / 全局静音 0
 * - 划出可见区/关格 = 组合销毁，DisposableEffect 释放播放器并上报"没在播"
 * - R380 收编进「一键全停/全播」：自管播放器经 onSelfPlayerChanged 登记进 PlayerPool
 *   externals（组合期登记/销毁注销，pauseAll 广播域覆盖本格），「全部播放」由外层
 *   按全停快照恢复——旧注释「不参与一键全停/全播（自管播放器不在池里，见交付风险）」
 *   的已知风险本批收口；点按开关与中央图标换 PlaybackTogglePolicy 意图口径
 *   （R375 挂账 2 收口）
 */
@Composable
internal fun GridAudioCell(
    item: VideoItem,
    gateway: TdGateway,
    focused: Boolean,
    muteAll: Boolean,
    // R432 重组收敛：点赞表 State 本体下传，本格叶子内现查（订阅作用域=本格）
    likes: State<Set<String>>,
    watched: Boolean,
    chromeless: Boolean,
    onFocus: () -> Unit,
    onToggleLike: () -> Unit,
    onEnded: () -> Unit,
    onPlayingChanged: (Boolean) -> Unit,
    // R380 意图口径上报（PlaybackTogglePolicy；释放时 false）：外层顶栏全停/全播钮
    // 状态源（anyPlaying）与全停音频快照的数据面
    onPlayIntentChanged: (Boolean) -> Unit,
    // R380 池外自管播放器收编：组合期上报非 null（外层登记进 PlayerPool externals，
    // pauseAll 广播域覆盖），销毁/换片上报 null（注销）——登记生命周期与格子同进退
    onSelfPlayerChanged: (ExoPlayer?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val liked = item.key in likes.value
    val context = LocalContext.current
    // R80：自带地址先过收口判定（含 '#'/'?' 的裸路径置 null 走 ensurePlayable 换流地址）
    var playUri by remember(item.key) { mutableStateOf(item.safeInitialPlayUri()) }
    var playing by remember(item.key) { mutableStateOf(false) }
    // R380 意图口径态（PlaybackTogglePolicy 单源判定）：点按开关与中央图标呈现换轨
    // （R375 挂账 2 收口——缓冲中 isPlaying=false 但播放意图在＝"在播"，旧 isPlaying
    // 口径点按被判成"没在播"→play() 落在已是 true 的意图上零变化）
    var playIntent by remember(item.key) { mutableStateOf(false) }
    var progress by remember(item.key) { mutableStateOf(0f) }
    var hearts by remember(item.key) { mutableStateOf(listOf<HeartBurst>()) }

    // 自管播放器：随条目重建，dispose 即释放（照 AudioCard 模式，只是不进池）
    val player = remember(item.key) { ExoPlayer.Builder(context).build() }
    val currentOnEnded = rememberUpdatedState(onEnded)
    val currentOnPlayingChanged = rememberUpdatedState(onPlayingChanged)
    val currentOnPlayIntentChanged = rememberUpdatedState(onPlayIntentChanged)
    val currentOnSelfPlayerChanged = rememberUpdatedState(onSelfPlayerChanged)
    DisposableEffect(item.key) {
        // R380 池外收编：组合即上报登记（外层挂进 PlayerPool externals——pauseAll
        // 全停广播域对本格生效，修「全部暂停后音频格照播」）
        currentOnSelfPlayerChanged.value(player)
        onDispose {
            // 先注销再释放：登记生命周期与格子组合同进退（外层按 key 幂等注销）
            currentOnSelfPlayerChanged.value(null)
            player.release()
            // 释放同时上报"没在播"，避免轮播把已销毁的格当成"播放中"而跳过
            currentOnPlayingChanged.value(false)
            currentOnPlayIntentChanged.value(false)
        }
    }

    // 组合即拉流地址（本格只在可见区才 compose，天然带可见性闸）
    LaunchedEffect(item.key) {
        if (playUri == null) {
            playUri = runCatching { gateway.ensurePlayable(item) }.getOrNull()
        }
    }
    // 地址就绪即装填（同一格 playUri 只会从 null → 有值）
    LaunchedEffect(playUri) {
        val uri = playUri ?: return@LaunchedEffect
        // R217 ②：mediaId 带内容 key（与视频格同刀）——分屏音频格同样接通
        // LoudnessNormalizer 响度归一（旧 fromUri 无 id 是死层）
        player.setMediaItem(MediaItem.Builder().setUri(uri).setMediaId(item.key).build())
        player.prepare()
    }
    // 音量策略（主声音 + 背景氛围音），与视频格同一口径。
    // player 必须入 key：换片重建播放器后 effect 重启才能写新实例，
    // 否则音量写到已释放的旧 player 上
    LaunchedEffect(player, focused, muteAll) {
        player.volume = when {
            muteAll -> 0f
            focused -> 1f
            else -> BACKGROUND_VOLUME
        }
    }
    // 状态监听：播完上抛换片链；播放态同步按钮图标并上报自动轮播
    DisposableEffect(player) {
        // R380 初值同步（R375 AudioCard 四点同步同款）：挂监听时播放器可能已带意图
        val initialIntent =
            PlaybackTogglePolicy.userPlayingIntent(player.playWhenReady, player.playbackState)
        playIntent = initialIntent
        currentOnPlayIntentChanged.value(initialIntent)
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) currentOnEnded.value()
                // R380 意图口径：播完/空机例外态由策略内判（不计"在播"）
                playIntent = PlaybackTogglePolicy.userPlayingIntent(player.playWhenReady, state)
                currentOnPlayIntentChanged.value(playIntent)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                currentOnPlayingChanged.value(isPlaying)
            }

            // R380 意图口径上报：手动暂停/续播与全停广播（externals.pause）都经此
            // 翻转——外层 anyPlaying 钮态即时跟手（缓冲中 isPlaying=false 但意图在
            // ＝在播，R375 同判词）
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                playIntent = PlaybackTogglePolicy.userPlayingIntent(playWhenReady, player.playbackState)
                currentOnPlayIntentChanged.value(playIntent)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // 进度轮询（500ms 足够底部细条刷新）
    LaunchedEffect(player) {
        while (true) {
            val d = player.duration
            progress = if (d > 0) {
                (player.currentPosition.toFloat() / d.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            delay(500)
        }
    }

    // 专辑封面：minithumbnail 字节（小格够看；高清封面按需链路在 Feed 页 AudioCard）
    val cover: ImageBitmap? = remember(item.key) {
        item.coverBytes?.let { bytes ->
            runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
            }.getOrNull()
        }
    }

    // R149 ④ chrome 空闲自动隐藏（与视频格同一套）：在播且无交互 3.5s 收起
    // ×/频道名行；中央状态图标与底部进度条是"状态"不是"控制"，保持常驻
    val chrome = rememberCellChrome(item.key, active = playing)
    val chromeA by animateFloatAsState(
        targetValue = if (chrome.on) 1f else 0f,
        animationSpec = tween(TgFeedTokens.AnimMed),
        label = "chromeA",
    )

    Box(
        modifier
            .clip(RoundedCornerShape(TgFeedTokens.CellRadius))
            .background(TgFeedTokens.SurfaceHigh),
    ) {
        if (cover != null) {
            Image(
                bitmap = cover,
                contentDescription = "专辑封面",
                // R217 ③ 已看压暗退役（同 GridVideoCell 一刀）：亮度统一
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            // 无封面占位：渐变底 + 居中音符字符（不引新图标资源）
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                TgFeedTokens.TgBlue.copy(alpha = 0.55f),
                                TgFeedTokens.SurfaceHigh,
                                TgFeedTokens.AccentRed.copy(alpha = 0.35f),
                            ),
                        ),
                    ),
            ) {
                Text(
                    "♪",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 32.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        // 已看降饱和叠层退役（R217 ③ 同视频格一刀）：亮度统一
        // 中央播放/暂停状态图标（R149 ⑤ 与视频格同口径对调：图标表达当前状态——
        // 播放中显示 ▶（小），已暂停显示 ⏸（大，明确可点恢复）；旧版播放中挂 ⏸，
        // 用户读作"已播放还显示暂停图标"）。R380/R375 挂账 2：判定换意图口径
        // （PlaybackTogglePolicy 单源）——缓冲中 isPlaying=false 但意图在＝"在播"，
        // 旧 isPlaying 口径在缓冲窗误挂 ⏸
        if (!chromeless) {
            Icon(
                if (playIntent) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = if (playIntent) "播放中" else "已暂停",
                tint = Color.White.copy(alpha = if (playIntent) 0.55f else 0.9f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(if (playIntent) 28.dp else 44.dp),
            )
        }
        // 手势层：R230 追加1 两步制（与视频格同口径）：非焦点首点=纯焦点（红环移动
        // 不播不停）；焦点格点按=播放/暂停切换。双击 = 点赞飘心（不消费位移）。
        // R149 ④：点按先唤回 chrome（自动隐藏计时重置），再执行既有动作
        val currentOnTap = rememberUpdatedState<() -> Unit>(newValue = {
            chrome.kick()
            if (!focused) {
                onFocus()
            } else if (playUri != null) {
                // R380/R375 挂账 2 收口：判定换意图口径（PlaybackTogglePolicy 单源）——
                // 缓冲中 isPlaying=false 但播放意图在，旧 isPlaying 口径点按被判成
                // "没在播"→play() 落在已是 true 的意图上零变化＝「缓冲时点暂停不生效」
                if (playIntent) player.pause() else player.play()
            }
        })
        val currentOnDoubleTap = rememberUpdatedState<(Offset) -> Unit>(newValue = { offset ->
            chrome.kick()
            if (!liked) onToggleLike()
            hearts = hearts + HeartBurst(
                id = Random.nextLong(),
                x = offset.x,
                y = offset.y,
                rotation = Random.nextInt(-24, 25).toFloat(),
            )
        })
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(item.key) {
                    detectTapGestures(
                        onTap = { currentOnTap.value() },
                        onDoubleTap = { offset -> currentOnDoubleTap.value(offset) },
                    )
                },
        )
        // 双击飘心层
        LikeHeartsLayer(
            hearts = hearts,
            onHeartDone = { id -> hearts = hearts.filterNot { it.id == id } },
        )
        // × 关格：仅焦点格可见（R149 ④：随 chrome 空闲静默收起，点按唤回）
        if (focused && !chromeless && chromeA > 0.01f) {
            CellCloseButton(
                t = chromeA,
                onClose = onClose,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        // 底部频道名行（进度条压在其最底缘上，见下；R162 起无渐变，描边可读）。
        // R149 ④：随 chrome 空闲收起（渐隐）
        if (!chromeless && chromeA > 0.01f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = chromeA },
            ) {
                CellBottomLabel(
                    title = item.channelTitle,
                    liked = liked,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }
        // 底部进度条：2dp 细轨 + 已播主色段（最底缘）
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(2.dp)
                .background(Color.White.copy(alpha = 0.18f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .height(2.dp)
                    .background(TgFeedTokens.TgBlue),
            )
        }
        // 焦点环
        if (!chromeless) CellFocusRing(t = if (focused) 1f else 0f)
    }
}

/**
 * 格子内容分派（从主渲染循环抽出，防 GridScreen 主函数字节码超 DEX 校验上限）：
 * 0=视频格 / 1=图片格 / 2=音频格。
 */
@Composable
internal fun GridCellDispatch(
    item: VideoItem,
    slotId: String,
    gateway: TdGateway,
    playerPool: PlayerPool,
    vm: FeedViewModel,
    focused: Boolean,
    // R432 重组收敛：三表 State 本体下传格叶子（叶子内按 item.key 现查），本分派层
    // 与外层画布循环都不读表——下载 1Hz / 音量拖动不再触发全视口重组
    likes: State<Set<String>>,
    favorites: State<Set<String>>,
    watched: Boolean,
    downloads: State<Map<String, DownloadStatus>>,
    volumes: SnapshotStateMap<String, Float>,
    muteAll: Boolean,
    // R230 ⑥ 手动播放授权口（视频格：两步制第二步/失败重试；外层满 4 路 Toast 拒）
    onRequestPlay: () -> Boolean,
    chromeless: Boolean,
    playable: Boolean,
    playableOrdinal: Int,
    poolEpoch: Int,
    firstEnter: Boolean,
    slotIndex: Int,
    replayTick: Int,
    dragging: Boolean,
    dragTarget: Boolean,
    onFocus: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onEnded: () -> Unit,
    onClose: () -> Unit,
    onFullscreen: () -> Unit,
    // R227 画面调整枢纽入口（视频格长按菜单 → GridScreen 托管 FrameAdjustHubSheet）
    onOpenFrameAdjust: () -> Unit,
    onDragStartCell: () -> Unit,
    onDragCell: (androidx.compose.ui.geometry.Offset) -> Unit,
    onDragEndCell: () -> Boolean,
    onDragCancelCell: () -> Unit,
    onPlayingChanged: (Boolean) -> Unit,
    // R380 意图口径上报（PlaybackTogglePolicy 单源）：视频格/音频格都上报——外层
    // 顶栏全停/全播钮状态源（anyPlaying）与全停音频快照的数据面；isPlaying 真出声
    // 口径继续走 onPlayingChanged（两口径分职，R375 判词）
    onPlayIntentChanged: (Boolean) -> Unit,
    // R380 池外自管播放器登记口（音频格专用）：非 null=格子组合了自管 ExoPlayer
    // （外层登记进 PlayerPool externals，pauseAll 广播域覆盖），null=销毁/换片（注销）
    onSelfPlayerChanged: (ExoPlayer?) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (item.mediaType) {
        // 1=图片格（纯图+频道名+图集角标，不进播放器池）
        1 -> GridPhotoCell(
            item = item,
            gateway = gateway,
            focused = focused,
            likes = likes,
            watched = watched,
            chromeless = chromeless,
            onFocus = onFocus,
            onToggleLike = { vm.toggleLike(item) },
            onClose = onClose,
            modifier = modifier,
        )
        // 2=音频格（封面+播放/暂停+进度条，自管 ExoPlayer 不占池；R380 自管播放器
        // 经登记口收编进全停广播域，意图口径上报接通外层钮态）
        2 -> GridAudioCell(
            item = item,
            gateway = gateway,
            focused = focused,
            muteAll = muteAll,
            likes = likes,
            watched = watched,
            chromeless = chromeless,
            onFocus = onFocus,
            onToggleLike = { vm.toggleLike(item) },
            onEnded = onEnded,
            onPlayingChanged = onPlayingChanged,
            onPlayIntentChanged = onPlayIntentChanged,
            onSelfPlayerChanged = onSelfPlayerChanged,
            onClose = onClose,
            modifier = modifier,
        )
        else -> GridVideoCell(
            slotId = slotId,
            item = item,
            playable = playable,
            entranceDelayMs = when {
                !firstEnter -> -1
                slotIndex < 12 -> slotIndex * 28
                else -> 0
            },
            entranceFull = slotIndex < 12,
            playableOrdinal = playableOrdinal,
            poolEpoch = poolEpoch,
            focused = focused,
            volumes = volumes,
            muteAll = muteAll,
            onRequestPlay = onRequestPlay,
            likes = likes,
            favorites = favorites,
            downloads = downloads,
            watched = watched,
            chromeless = chromeless,
            gateway = gateway,
            playerPool = playerPool,
            replayTick = replayTick,
            onFocus = onFocus,
            onVolumeChange = onVolumeChange,
            onEnded = onEnded,
            onToggleLike = { vm.toggleLike(item) },
            onToggleFavorite = { vm.toggleFavorite(item) },
            onDownload = { vm.download(item) },
            onNotInterested = { vm.toggleNotInterested(item) },
            onOpenFrameAdjust = onOpenFrameAdjust,
            onPlayingChanged = onPlayingChanged,
            // R380 意图口径上报（池内视频格）：外层全停/全播钮状态源数据面
            onPlayIntentChanged = onPlayIntentChanged,
            onClose = onClose,
            onFullscreen = onFullscreen,
            dragging = dragging,
            dragTarget = dragTarget,
            onDragStartCell = onDragStartCell,
            onDragCell = onDragCell,
            onDragEndCell = onDragEndCell,
            onDragCancelCell = onDragCancelCell,
            modifier = modifier,
        )
    }
}
