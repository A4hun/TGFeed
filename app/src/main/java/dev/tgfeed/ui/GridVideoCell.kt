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
// R230 追加3：自绘长按拖拽探测器（等待窗微动吃掉，修「拖动时屏幕跟着滚」）
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material.icons.filled.ScreenRotation
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
// R230 追加3：自绘长按探测器的位移判定（positionChange/positionChanged）
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.PointerInputScope
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
import dev.tgfeed.ui.components.FrameAdjustStore
import dev.tgfeed.ui.components.LikeHeartsLayer
import dev.tgfeed.ui.components.frameTransform
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
 * 画布视频单格（R129 从 GridScreen.kt 按职责拆出，纯搬运零逻辑改动）。
 * 收纳：GridVideoCell 本体 + 其专属播放常量（连续失败换片上限 / 起播错峰间隔 /
 * 背景氛围音量）+ 换片交叉淡入的垫底封面持有器 GridCellCrossfade。
 * 播放器池策略、音量策略、失败分诊换片逻辑一行不动；由 GridMediaCells.kt 的
 * GridCellDispatch 按 mediaType 分派组合。
 */

/** 格子连续解码/拉流失败达到该次数后自动换片（走播完换片链），不再原地死磕坏片 */
private const val MAX_CELL_FAIL_RETRIES = 3

/** 起播错峰间隔：分屏开局可见格按位次各错开这么久再拉流，削平流服务器并发尖峰 */
private const val START_STAGGER_MS = 200L

/** 非焦点格的背景氛围音量（可闻但不抢焦点主声音）；全局静音时全部 0 */
internal const val BACKGROUND_VOLUME = 0.25f

/** 换片交叉淡入的垫底封面持有器（普通类：组合期纯值推导，不进快照、不驱动重组） */
private class GridCellCrossfade {
    var key: String = ""
    var prev: ImageBitmap? = null
    var cur: ImageBitmap? = null
}

/**
 * 画布单格：完整播放器（暂停/继续、独立音量、点赞飘心、长按菜单、关闭）。
 * [slotId] 是格子的稳定身份（播放器池 key、焦点、音量都按它）；
 * [item] 是当前播放内容，外层"播完换片"会让 item 变化而 slotId 不变，
 * 播放器原位复用，remember(item.key) 的格内状态自动重置。
 * [playable] 由外层按播放授权（grant，R230 ⑥）计算：true = 用户点过播放的格，
 * acquire 拉流起播；false = 封面+淡▶待播水印（起播只由手动点击触发）。
 * 资源纪律：listener 自加自删；playable 翻转或销毁时 release 回池。
 *
 * 宽高比自适应：槽位尺寸由外层 span 决定（1×1 / 2×1 / 2×2），格内视频 FIT
 * 完整显示，黑边区域垫封面放大模糊底衬（TextureView 无穿透问题，FIT 安全）。
 *
 * 拖拽换格：长按起拖，位移上抛给外层做画布坐标系命中测试；
 * [dragging] 本格正被拖（降透明度）、[dragTarget] 本格是落点（红框高亮）。
 *
 * R230 点按状态机（两步制+手动播放）：
 *  - 失败格点按 → 焦点 + 重试（重试先过 [onRequestPlay] 授权口，满路 Toast 拒绝）
 *  - 非焦点格点按 → 仅焦点移动（红环走，不播不停——两步制第一步）
 *  - 焦点格未授权再点 → [onRequestPlay] 授权开播（两步制第二步；满 4 路 Toast 拒）
 *  - 已授权格点按 → 手动暂停/续播（暂停只由用户手动触发，别格不受影响）
 */
@Composable
internal fun GridVideoCell(
    slotId: String,
    item: VideoItem,
    playable: Boolean,
    // 本格在可播集合中的序号：用于起播错峰（首拉流按位次延迟，削平流服务器并发尖峰）
    playableOrdinal: Int,
    // 池淘汰自愈代际：值本身不参与运算，仅作参数在可播集变化时驱动本格重组——
    // 重组会重走下面的 acquire，被池满 FIFO 误淘汰的格（引用着已 release 的实例）
    // 借此拿回新播放器；busy 命中的格幂等返回原实例，零代价
    poolEpoch: Int,
    // 入场错落（§6.2）：-1 = 不播；≥0 = 延迟毫秒（首屏前 12 格按阅读序 28ms 错峰，分页格 0）
    entranceDelayMs: Int,
    // true = 首屏格（fade+scale+translateY 完整入场）；false = 分页格（仅 200ms 淡入）
    entranceFull: Boolean,
    focused: Boolean,
    // R432 重组收敛：每格音量记忆表（SnapshotStateMap 本体下传，本格叶子内按 slotId
    // 现查）——音量拖动每帧写表不再把外层画布循环拖进重组，只重组本格
    volumes: SnapshotStateMap<String, Float>,
    muteAll: Boolean,
    // R230 ⑥ 手动播放授权口：两步制第二步（焦点格再点=开播）与失败格重试都经它
    // 向外层申请播放授权（满 MAX_GRID_PLAYERS 路时外层 Toast 拒绝并返回 false）
    onRequestPlay: () -> Boolean,
    // R432 重组收敛：点赞/收藏/下载三表 State 本体下传，本格叶子内按 item.key 现查
    // （订阅作用域=本格）——下载 1Hz 进度更新只重组在读表的单格，不再全视口重组
    likes: State<Set<String>>,
    favorites: State<Set<String>>,
    downloads: State<Map<String, DownloadStatus>>,
    watched: Boolean,
    // 分屏 LOD：true = 格子视觉短边已缩到 80dp 以下，chrome 全隐（×钮/音量线/
    // 频道名行/已看弱化/焦点环/声波都不画），只留纯画面；
    // 手势层、双击飘心、中央暂停/失败反馈保留（小格也要能点能播）
    chromeless: Boolean,
    gateway: TdGateway,
    playerPool: PlayerPool,
    replayTick: Int,
    onFocus: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onEnded: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownload: () -> Unit,
    onClose: () -> Unit,
    onFullscreen: () -> Unit,
    // 不感兴趣：长按菜单项，外层接 vm.toggleNotInterested
    onNotInterested: () -> Unit,
    // R227 画面调整：长按菜单「画面调整」项，外层（GridScreen）托管 FrameAdjustHubSheet
    // 枢纽小菜单（向左/向右旋转/左右镜像/恢复默认——与 Feed 播放页长按面板同一枢纽
    // 同一 FrameAdjustStore，Feed 里调过的分屏里也生效）
    onOpenFrameAdjust: () -> Unit,
    // 播放状态上报（isPlaying 变化 / 播放器归还时 false）：外层自动轮播据此判断"没在播"
    onPlayingChanged: (Boolean) -> Unit,
    // R380 意图口径上报（PlaybackTogglePolicy.userPlayingIntent；播放器归还时 false）：
    // 外层顶栏"全停/全播"钮状态源（anyPlaying）与全停音频快照的数据面——isPlaying
    // 真出声口径继续走 onPlayingChanged 喂轮播/记账，两口径分职（R375 判词）
    onPlayIntentChanged: (Boolean) -> Unit,
    dragging: Boolean,
    dragTarget: Boolean,
    onDragStartCell: () -> Unit,
    onDragCell: (Offset) -> Unit,
    // 返回 true = 松手已按悬浮落区处理（本格不再弹长按菜单）
    onDragEndCell: () -> Boolean,
    onDragCancelCell: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 池内 key 按槽位（加前缀避免与 Feed 模式冲突）：换片不换格，播放器原位复用
    val poolKey = "grid:$slotId"
    // R432 重组收敛：三表 + 音量表的叶子查表点（组合期单次读，语义与旧值参链一致——
    // 旧链在外层循环取值后传入，本格组合时读到的就是同一快照值）
    val liked = item.key in likes.value
    val favorited = item.key in favorites.value
    val downloadStatus = downloads.value[item.key]
    val volume = volumes[slotId] ?: 1f
    // R80：自带地址先过收口判定（含 '#'/'?' 的裸路径置 null 走 ensurePlayable 换流地址）
    var playUri by remember(item.key) { mutableStateOf(item.safeInitialPlayUri()) }
    var buffering by remember(item.key) { mutableStateOf(true) }
    // 解码失败退化标记：true 时本格显示封面 + 重试按钮
    var failed by remember(item.key) { mutableStateOf(false) }
    // 连续失败计数：同一内容每失败一次 +1，达到 MAX_CELL_FAIL_RETRIES 自动换片；
    // remember(item.key) 保证换片后对新内容重新计数
    var failCount by remember(item.key) { mutableIntStateOf(0) }
    // 重试计数：每次点重试 +1，驱动播放 effect 重新 setMediaItem
    var retryTick by remember(item.key) { mutableIntStateOf(0) }
    var paused by remember(item.key) { mutableStateOf(false) }
    // 恢复播放时中央闪现 ⏸ 标记
    var flashResume by remember(item.key) { mutableStateOf(false) }
    // 长按弹出的小菜单
    var showMenu by remember(item.key) { mutableStateOf(false) }
    // 双击点赞飘心列表
    var hearts by remember(item.key) { mutableStateOf(listOf<HeartBurst>()) }
    // R218 镜像/R227 复合态（与 VideoPage 同一 FrameAdjustStore：每条视频独立的
    // 旋转+镜像记忆、跨会话保持，Feed 播放页/分屏格共享同一真身）：组合期读快照表
    // 即订阅——菜单/枢纽调整后本格画面（含封面/换片垫底封面）即时重绘；换片
    // remember(item.key) 自动对新内容重查记忆表。分屏格 R227 起同口径渲染旋转
    //（FrameTransform 宽高互换布局+rotationZ+换轴镜像——Feed 里转过的分屏里也转）
    val cellContext = LocalContext.current
    val frameState = FrameAdjustStore.stateOf(cellContext, item.key)
    val frameRotationDeg = frameState.rotationDeg
    val mirrored = frameState.mirrored

    // 换片回调用 updatedState：listener 挂在槽位级播放器上（跨换片存活），
    // 闭包必须始终读到最新的 onEnded，否则换片后会把旧内容再推进一次。
    // 声明位置靠前：起播 effect / onPlayerError 的"连续失败自动换片"也要用
    val currentOnEnded = rememberUpdatedState(onEnded)
    // 同理：在播上报也挂槽位级 listener / dispose，必须用 updatedState 读最新闭包
    val currentOnPlayingChanged = rememberUpdatedState(onPlayingChanged)
    // R380 同理：意图口径上报挂槽位级 listener（跨换片存活），updatedState 读最新闭包
    val currentOnPlayIntentChanged = rememberUpdatedState(onPlayIntentChanged)

    // 封面占位：抽帧封面（播过就抽好了）→ minithumbnail 秒出 → 高清缩略图换上
    val cover: ImageBitmap? = produceState<ImageBitmap?>(initialValue = null, item.key) {
        dev.tgfeed.ui.components.CoverStore.map[item.key]?.let { value = it }
        if (value == null) {
            value = item.coverBytes?.let { bytes ->
                runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
                }.getOrNull()
            }
        }
        if (item.coverFileId >= 0) {
            val lib = gateway as? TdLibGateway
            val bytes = lib?.loadCoverBytes(item)
            if (bytes != null) {
                value = runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
                }.getOrNull() ?: value
            }
        }
    }.value

    // ── 动效状态（重设计 P0：错落入场 / 交叉淡入 / 焦点错峰 / 落点下沉）──────
    // 本族时长（240/200/200·150/120）为 design-redesign §6.1/§6.3 指定节律、多轮走查
    // 调定的格子内容微动效——三档表（AnimFast/Med/Slow）管通用 chrome/状态节律，不覆盖
    // 此族；R178 守护单测（MotionTokensPolicyTest）豁免清单已登记理由
    // 入场错落：首屏前 12 格 fade 240ms + scale 0.94→1 + translateY 12→0，按阅读序延迟；
    // 分页格只做 200ms 淡入；entranceDelayMs<0（滚动回收回来的格）直接满态不播
    val entrance = remember { Animatable(if (entranceDelayMs >= 0) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (entranceDelayMs >= 0) {
            if (entranceDelayMs > 0) delay(entranceDelayMs.toLong())
            entrance.animateTo(1f, tween(if (entranceFull) 240 else 200, easing = FastOutSlowInEasing))
        }
    }
    // 换片交叉淡入（§6.3）：旧封面垫底、新内容层 alpha 0→1 200ms LinearOutSlowIn；
    // 播放器复用不动（TextureView 容器 alpha 有效）。
    // crossfadeHolder 是普通对象（非快照 State）：组合期纯值推导，不驱动重组
    val crossfadeHolder = remember { GridCellCrossfade() }
    if (crossfadeHolder.key != item.key) {
        crossfadeHolder.prev = if (crossfadeHolder.key.isEmpty()) null else crossfadeHolder.cur
        crossfadeHolder.cur = cover
        crossfadeHolder.key = item.key
    }
    val crossfadePrev = crossfadeHolder.prev
    val contentFade = remember(item.key) { Animatable(if (crossfadePrev != null) 0f else 1f) }
    LaunchedEffect(item.key) {
        if (contentFade.value < 1f) {
            contentFade.animateTo(1f, tween(200, easing = LinearOutSlowInEasing))
        }
    }
    // 焦点错峰过渡（§6.1）：切走 150ms 淡出、落上 200ms 淡入（错峰 50ms），
    // L1 层（焦点环/声波/×/音量胶囊）统一吃这个进度，旧格先暗、新格后亮
    val focusT by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (focused) 200 else 150,
            delayMillis = if (focused) 50 else 0,
        ),
        label = "focusT",
    )
    // "光落上去"而不是"线跳过去"：新焦点格内容 120ms 亮度 0.92→1 轻抬（只在一拍内）
    val focusLift = remember { Animatable(1f) }
    LaunchedEffect(focused) {
        if (focused) {
            focusLift.snapTo(0.92f)
            focusLift.animateTo(1f, tween(120))
        }
    }
    // 拖拽落点：内容 0.96 下沉 + 松手弹簧归位（§4.7）
    // R178 收编（R177 挂账③）：落点归位=内容层弹性族，与缩放双击复位同一物理——
    // spring 只准 MotionTokens（ZoomReset 0.82/500），观感时长 ~260ms
    val dropScale by animateFloatAsState(
        targetValue = if (dragTarget) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = dev.tgfeed.ui.theme.MotionTokens.ZoomResetSpringDamping,
            stiffness = dev.tgfeed.ui.theme.MotionTokens.ZoomResetSpringStiffness,
        ),
        label = "dropScale",
    )
    // 本格"正在出声"状态（声波动效的开关源）：listener 写入，组合期只读
    var isPlaying by remember(item.key) { mutableStateOf(false) }

    // R149 ④ chrome 空闲自动隐藏：在播且无交互 3.5s 后收起 ×/音量/声波/频道名，
    // 点按/双击/拖拽/调音量唤回；暂停/换片复位可见（换片靠 remember(item.key) 重建）
    val chrome = rememberCellChrome(item.key, active = isPlaying)
    val chromeA by animateFloatAsState(
        targetValue = if (chrome.on) 1f else 0f,
        animationSpec = tween(TgFeedTokens.AnimMed),
        label = "chromeA",
    )
    // 拖拽中 = 交互时刻：唤回 chrome
    LaunchedEffect(dragging) { if (dragging) chrome.kick() }

    // 只有可见格才拉流式地址；不可见格连 ensurePlayable 都不发，失败走封面 + 重试
    // （retryTick 入 key：拉流失败时点重试会先复位 playUri，靠它重新触发本 effect）
    LaunchedEffect(item.key, playable, retryTick) {
        if (playable && playUri == null && !failed) {
            // 起播错峰：分屏开局所有可见格同时 acquire + 拉流，TgStreamServer 的 TDLib
            // 同步分段下载在并发尖峰下大量报错/空响应（logcat 实证：9 格齐开 → 多格
            // "解码失败"）。按格子的可播位次各错开 200ms 削峰；换片/批量换一拨也多格
            // 并发，同样受益。只有手动重试（retryTick>0）不等尖峰、直接拉
            if (retryTick == 0 && playableOrdinal > 0) {
                delay(playableOrdinal * START_STAGGER_MS)
            }
            val playResult = runCatching { gateway.ensurePlayable(item) }
            playUri = playResult.getOrNull()
            if (playUri == null) {
                // 拉流地址都拿不到：计入连续失败，走 onPlayerError 同款"满 3 次自动换片"。
                // R78 分诊：内部逻辑错误（条目缺 fileId 等 require 失败）重试也是同一堵墙，
                // 不给假重试按钮，直接按失败上限同款路径自动换片
                val fatalInternal = playResult.exceptionOrNull() is IllegalArgumentException
                failCount++
                failed = true
                if (fatalInternal || failCount >= MAX_CELL_FAIL_RETRIES) {
                    failCount = 0
                    currentOnEnded.value()
                }
            }
        }
    }

    // 池淘汰自愈：poolEpoch 不入任何 key，只靠它驱动的这次重组重走 acquire
    val player: ExoPlayer? = if (playable && !failed && playUri != null) playerPool.acquire(poolKey) else null

    // 手动暂停的保持轨迹（remember(item.key)：换片后对新内容重新计数）——
    // 旧逻辑在 effect 重跑时无条件 paused=false，划出可见区/进出专注模式后
    // 重新 acquire 会把用户的手动暂停偷跑成自动续播
    var lastPreparedUri by remember(item.key) { mutableStateOf<String?>(null) }
    var lastRetryTick by remember(item.key) { mutableIntStateOf(-1) }
    LaunchedEffect(player, playUri, retryTick) {
        val p = player ?: return@LaunchedEffect
        val uri = playUri ?: return@LaunchedEffect
        // R149 ② 焦点环与"正在出声的格"错位修复：关掉本格播放器的系统音频焦点自动接管。
        // PlayerPool 建机时统一 setAudioAttributes(attrs, handleAudioFocus=true)——
        // 单播池需要它（来电闪避），但分屏多格同播是"主声音+背景音"的音量策略
        // （焦点格 volume / 其余格 BACKGROUND_VOLUME），系统焦点却是排他的：后起播
        // 的格子请求焦点会把先起播的格子（通常是焦点格）打AUDIOFOCUS_LOSS暂停——
        // 实测 logcat 连串 onAudioFocusChange(-1)，结果是红环格定格无声、最后起播
        // 的格在出声，用户看到"正在播放的视频没有高亮、其他的视频高亮了"。
        // 分屏池是 GridScreen 私有实例（退出即整池释放），关焦点接管不影响单播池；
        // 退后台的暂停由容器层 backgroundAction→pauseAllPlayback 统一管（HIGH-8），
        // 悬浮窗池同款手法（OverlayWindowHolder 对 acquire 后的播放器同样关接管）。
        p.audioAttributes?.let { attrs ->
            runCatching { p.setAudioAttributes(attrs, false) }
        }
        if (p.currentMediaItem?.localConfiguration?.uri.toString() != uri) {
            // R217 ②：mediaId 带内容 key——PlayerPool 的 LoudnessNormalizer（音量均衡）
            // 靠 onMediaItemTransition→beginContent(mediaId) 查每内容的记忆增益；
            // 旧 fromUri 无 mediaId，分屏所有格的响度归一是死层，各内容原始响度
            // 差异直出（用户观感「重试后音量混乱」）。与 VideoPage 主路径同口径
            p.setMediaItem(MediaItem.Builder().setUri(uri).setMediaId(item.key).build())
            p.prepare()
        }
        // 手动暂停只在两种时机清零：① 真正换了片（新内容新开始）② 用户点了重试；
        // 同片重挂（池自愈代际重 acquire）一律保留用户的暂停选择。
        // R230 ②：globalPaused（全停粘滞标记）退役——旧口径下它随 HIGH-8 后台/全停
        // 置位后无人清，本守卫会把手动点播打回暂停=「点播放又自己停」实锤现场
        if (lastPreparedUri != uri || lastRetryTick != retryTick) {
            paused = false
        }
        lastPreparedUri = uri
        lastRetryTick = retryTick
        // R230 ⑥：授权=用户点过播放——装填就绪即开播；手动暂停由 paused 保留
        p.playWhenReady = !paused
    }

    // 音量策略（主声音 + 背景音）：全局静音 → 全部 0；焦点格 → 本格记忆的独立音量
    // （默认 1.0 主声音）；其余格 → BACKGROUND_VOLUME 可闻的背景氛围音
    // R217 ② 观测：焦点→音量链实机裁决探针（点某格=注意力在那=音量高，重试后
    // 是否失效——走查读此行比对 focused 与结果音量）
    LaunchedEffect(player, focused, muteAll, volume) {
        val v = when {
            muteAll -> 0f
            focused -> volume
            else -> BACKGROUND_VOLUME
        }
        player?.volume = v
        Log.i(
            "GridVol",
            "格=${slotId.takeLast(6)} focused=$focused muteAll=$muteAll 记忆音量=$volume → 生效=$v",
        )
    }

    // 整池无片可换时的兜底重播：外层 bump replayTick，本格归零重播。
    // handledReplayTick 记住已处理的值：换片重组（item.key 变化）/ 首次组合不触发
    var handledReplayTick by remember(item.key) { mutableIntStateOf(replayTick) }
    // player 入 key：兜底重播 bump 的瞬间若本格恰好不持播放器（R230 后=授权已收，
    // 如播完换片无新片可换），先记账再等——用户重新点播（授权回来）时本 effect 因
    // player 键变化重跑，seek(0)+play 补播；PK 擂台两格授权常驻（连打语义）时即时重播
    LaunchedEffect(replayTick, player) {
        if (replayTick != handledReplayTick) {
            val p = player ?: return@LaunchedEffect
            handledReplayTick = replayTick
            p.seekTo(0)
            paused = false
            p.play()
        }
    }

    // currentOnEnded / currentOnPlayingChanged 已提到格内状态声明区（起播 effect 也用）

    DisposableEffect(player) {
        val p = player ?: return@DisposableEffect onDispose { }
        // R380 初值同步：重挂/池自愈重 acquire 时监听器后到，播放器可能已带播放意图
        // （R375 AudioCard 四点同步同款——漏初值会让外层钮态在重挂瞬间读旧档）
        currentOnPlayIntentChanged.value(
            PlaybackTogglePolicy.userPlayingIntent(p.playWhenReady, p.playbackState)
        )
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) {
                    // 播完自动换片：上抛给外层按"每格已播游标"推进下一条
                    currentOnEnded.value()
                }
                // R380 意图口径上报：播完/空机例外态由策略内判（不计"在播"）
                currentOnPlayIntentChanged.value(
                    PlaybackTogglePolicy.userPlayingIntent(p.playWhenReady, state)
                )
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                // 格内"正在出声"状态（声波动效开关源）+ 上报"正在播放中"：
                // 外层自动轮播只换没在播的格（暂停/ENDED/缓冲停播都报 false）
                isPlaying = playing
                currentOnPlayingChanged.value(playing)
            }

            // R380 意图口径上报：全停 pauseAll / 手动 pause / 恢复 play 都经此翻转——
            // 外层 anyPlaying 钮态即时跟手（缓冲中 isPlaying=false 但意图在＝在播，
            // R375 同判词的全停钮形态收口）
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                currentOnPlayIntentChanged.value(
                    PlaybackTogglePolicy.userPlayingIntent(playWhenReady, p.playbackState)
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                // 失败统一处理：退化为封面 + 停掉本格播放
                failed = true
                p.playWhenReady = false
                p.stop()
                p.clearMediaItems()
                // R78 分诊（与 VideoPage 同一 fatalReasonOf 口径）：解码失败/资源失效
                // 是稳定终态，原地重试只是假装在努力——不给假重试按钮，直接按
                // "连续失败上限"同款路径自动换片；真网络类（断连/超时/限流/流服务
                // 500/503 抖动）才走封面 + 重试按钮、计 3 次换片
                // R80 埋点：分屏格失败错误码 + 分诊结果
                val fatal = fatalReasonOf(error)
                dev.tgfeed.core.playback.PlaybackTrace.log(
                    "gridErr", item.key, "code=${error.errorCode} fatal=${fatal ?: "可重试"}",
                )
                if (fatal != null) {
                    Log.i("TGFlow", "分屏格不可重试失败直接换片（code=${error.errorCode}）")
                    failCount = 0
                    currentOnEnded.value()
                    return
                }
                failCount++
                if (failCount >= MAX_CELL_FAIL_RETRIES) {
                    // 连续失败达到上限：自动换片（复用播完换片链，坏片同时被记入已播、
                    // 不会再被换回来），不再原地死磕——实证存在同一 fileId 稳定拉不出
                    // 流的坏片，重试无用。换片后 remember(item.key) 自动复位失败态与计数
                    failCount = 0
                    currentOnEnded.value()
                }
            }
        }
        p.addListener(listener)
        onDispose { p.removeListener(listener) }
    }

    // 不可见 / 失败 / 销毁即归还播放器（release 幂等，重复调用安全）；
    // 归还同时上报"没在播"，避免轮播把已交还播放器的格当成"播放中"而跳过。
    // failed 入 key：失败格（封面+重试态）旧逻辑持着播放器不还——busy 占位顶爆
    // 池容后会触发 FIFO 误淘汰，把仍在正常播放的格的播放器 release 掉（画面定格）
    DisposableEffect(playable, failed, poolKey) {
        onDispose {
            playerPool.release(poolKey)
            isPlaying = false
            currentOnPlayingChanged.value(false)
            // R380 意图口径同步清零（格亡/归还不留陈意图，外层钮态不读死档）
            currentOnPlayIntentChanged.value(false)
        }
    }

    // ⏸ 闪现 600ms 后自动消失
    LaunchedEffect(flashResume) {
        if (flashResume) {
            delay(600)
            flashResume = false
        }
    }

    // 单击状态机（R230 两步制+手动播放，见文件头注释）：
    // 失败格=焦点+重试（重试先过授权口）；非焦点格=纯焦点移动；焦点格未授权=授权
    // 开播（两步制第二步）；已授权格=手动暂停/续播。任何点按先唤回 chrome。
    val currentOnTap = rememberUpdatedState<() -> Unit>(newValue = {
        chrome.kick()
        // R217 ④/R230 探针：tap 层活证（含 playable 授权态，走查比对授权链）
        Log.i("GridGesture", "单击：格=${slotId.takeLast(6)} focused=$focused failed=$failed playable=$playable")
        when {
            failed -> {
                // 点失败格 = 明确的对焦+重试意图：重试先过授权口（满 4 路时留在失败态，
                // Toast 已说明），过闸才清失败态重拉
                onFocus()
                if (onRequestPlay()) {
                    failed = false
                    buffering = true
                    playUri = item.playUri // 复位成原始值：拉流失败的条目借此重新走 ensurePlayable
                    retryTick++
                }
            }
            // R230 追加1 两步制第一步：首点=注意力移到该格（红环焦点标记），不播不停
            // ——别格播放状态不受任何影响（「点击新视频时上一个视频不暂停」）
            !focused -> {
                onFocus()
            }
            // 两步制第二步：焦点格再点=授权开播（onRequestPlay 内含满 4 路拒绝+焦点）
            !playable -> {
                onRequestPlay()
            }
            // 已授权格：手动暂停/续播（暂停只由用户手动点暂停触发）
            else -> {
                val p = player
                if (p != null) {
                    // R375：判定换 playWhenReady 意图口径（PlaybackTogglePolicy 单源）——
                    // 缓冲中 isPlaying=false 但用户语义上是"在播"（用户实锤「正在加载
                    // 缓冲时单击暂停/播放不生效」的病根：旧 isPlaying 口径把缓冲期点按
                    // 判成"没在播"→play() 落在已是 true 的播放意图上零变化）。ENDED/
                    // IDLE 例外在策略内：格内播完即自动换片，例外态点按行为与旧口径
                    // 逐字一致。
                    if (PlaybackTogglePolicy.userPlayingIntent(p.playWhenReady, p.playbackState)) {
                        p.pause()
                        paused = true
                    } else {
                        p.play()
                        paused = false
                        flashResume = true
                    }
                }
            }
        }
    })
    // 双击：未赞则点赞 + 落点飘心（已赞只飘心不取消）；R149 ④ 点按唤回 chrome
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

    val downloadLabel = when (downloadStatus) {
        is DownloadStatus.Running -> "下载中…"
        is DownloadStatus.Done -> "已下载到相册"
        is DownloadStatus.Failed -> "下载失败 · 点按重试"
        null -> "下载到相册"
    }

    // 圆角卡片格（§4.7）：10dp CellRadius + 外层 2dp 黑缝，取代直角无缝拼贴的"监控墙感"
    Box(
        modifier
            .clip(RoundedCornerShape(TgFeedTokens.CellRadius))
            .background(Color.Black)
            // 合并层变换（单次 graphicsLayer，零额外重组）：
            // 被拖降透明（原位留虚位）× 落点下沉 0.96 × 入场错落 fade/scale/translateY
            .graphicsLayer {
                val e = entrance.value
                alpha = (if (dragging) 0.35f else 1f) * e
                val s = (if (entranceFull) 0.94f + 0.06f * e else 1f) * dropScale
                scaleX = s
                scaleY = s
                if (entranceFull && e < 1f) translationY = (1f - e) * 12.dp.toPx()
            },
    ) {
        // 0) 换片交叉淡入：旧封面垫底，新内容层在上层做 alpha 0→1
        //    R218：垫底旧封面同翻（200ms 淡入窗内与翻转画面观感一致）；
        //    R227：走 frameTransform（旋转+换轴镜像同口径——记忆态带旋转时
        //    垫底封面同转，淡入窗内姿态一致）
        if (contentFade.value < 1f && crossfadePrev != null) {
            Image(
                bitmap = crossfadePrev,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .frameTransform(frameRotationDeg, mirrored),
                contentScale = ContentScale.Crop,
            )
        }
        // 1) 画面层：失败 → 封面 + 重试文案；播放中 → PlayerView；不可见/加载中 → 封面占位。
        //    换片淡入 × 焦点"光落上去"轻抬只作用这一层。
        //    R217 ③ 已看压暗退役（用户实锤「每格视频都很暗」）：旧版 watched 时
        //    alpha 0.55 + WatchedDimLayer 黑 25% 双层压暗——R150(B)「显示即落 seen」
        //    后快照恢复/历史展示内容几乎全部命中已看，观感即全格变暗（0.55×0.75
        //    ≈41% 亮度）。按要求去掉压暗、亮度统一；「已看过」信息保留在焦点格
        //    声波旁的小胶囊（纯信息不压暗）
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = contentFade.value * focusLift.value
                },
        ) {
        when {
            failed -> {
                if (cover != null) {
                    Image(
                        bitmap = cover,
                        contentDescription = null,
                        // R218：失败态封面同翻（画面族口径一致；重试文案/转圈不翻）；
                        // R227：frameTransform 同口径带旋转
                        modifier = Modifier
                            .fillMaxSize()
                            .frameTransform(frameRotationDeg, mirrored),
                        contentScale = ContentScale.Crop,
                    )
                    // R162 零遮罩：旧版失败态叠 0.5 黑罩压暗封面（0b19725 同病）——
                    // 失败文案/重试钮可读性已由 withOverlayShadow 描边与实底钮承担
                }
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        // 文案从"解码失败"改"加载失败"：大多数失败其实是网络/源拉取问题
                        //（假 200 空 body、超时、坏片），不是解码器问题——别误导用户
                        "加载失败",
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodySmall.withOverlayShadow(),
                    )
                    Spacer(Modifier.height(8.dp))
                    // 重试按钮：视觉按钮。格子上层压着全尺寸手势层（单击=重试），
                    // 真实点击由手势层处理，这里只给出明确的可点目标引导
                    Text(
                        "重试",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 22.dp, vertical = 6.dp),
                    )
                    // 失败过一次就提示自动换片策略，用户知道不会卡死在坏片上
                    if (failCount > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "已失败 $failCount 次，连续 $MAX_CELL_FAIL_RETRIES 次将自动换片",
                            color = Color.White.copy(alpha = 0.55f),
                            style = MaterialTheme.typography.labelSmall.withOverlayShadow(),
                        )
                    }
                }
            }

            player != null -> {
                // 宽高比自适应：视频 FIT 完整显示（横屏宽扁、竖屏高瘦），
                // 留下的黑边用封面放大模糊底衬填充（VideoPage 同款模式：
                // Crop 铺满 + 放大 1.25 倍 + 高斯模糊；blur 仅 Android 12+ 生效，
                // 低版本退化为无模糊的放大封面，依然可用）
                if (cover != null) {
                    Image(
                        bitmap = cover,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = 1.25f
                                scaleY = 1.25f
                            }
                            .blur(30.dp),
                        contentScale = ContentScale.Crop,
                    )
                    // R162 零遮罩：旧版底衬上再叠 0.35 黑压暗"让底衬不抢主体"——
                    // 与 becc50e/R83 全屏基准口径相悖，分屏底衬对齐只模糊不压暗
                }
                AndroidView(
                    factory = { ctx ->
                        // 用 TextureView 渲染（player_cell.xml：surface_type=texture_view）：
                        // SurfaceView 是独立硬件图层，会穿透格子裁剪把视频画到别的格子上。
                        // 槽位尺寸不变、格内改 FIT 完整显示（xml 里是 zoom，
                        // 这里代码覆盖回 fit；TextureView 无穿透问题，FIT 安全，
                        // 画布 graphicsLayer 缩放对 TextureView 同样生效）
                        // R217 ④ 长按菜单在「播放中格子」触发不了的根因：PlayerView 默认
                        // useController=true 自带触摸处理（点按切控制器），AndroidView 的
                        // View 层把事件整路吃掉——上方兄弟位的手势层（点按/双击/长按拖拽）
                        // 全部收不到事件。16384 实证：封面/失败格长按全活，播放格零响应。
                        // 关控制器让 View 不再消费触摸，事件回流 Compose 手势层（VideoPage
                        // 的 VideoSurface 同款 useController=false 先例；格内控制本就全走
                        // 自绘 chrome，PlayerView 控制器从未被用）
                        (android.view.LayoutInflater.from(ctx)
                            .inflate(dev.tgfeed.R.layout.player_cell, null) as PlayerView).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    update = { it.player = player },
                    // R218 镜像翻转：渲染层变换（零布局侵入；手势层/转圈/菜单是
                    // 兄弟浮层不翻——「只翻画面不翻交互层」）。R227：frameTransform
                    // 统一旋转+换轴镜像（宽高互换布局+rotationZ——与 VideoSurface
                    // 播放层同一套 FrameAdjustPolicy 换轴裁决）
                    modifier = Modifier
                        .fillMaxSize()
                        .frameTransform(frameRotationDeg, mirrored),
                )
                if (buffering && !paused) {
                    // 内联小转圈统一 22dp/2dp（动画禁用时退化成 2dp 圆点仍依稀可辨）
                    CircularProgressIndicator(
                        Modifier.align(Alignment.Center).size(22.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                }
            }

            else -> {
                if (cover != null) {
                    Image(
                        bitmap = cover,
                        contentDescription = null,
                        // R218：占位封面同翻（与起播后翻转画面观感一致）；
                        // R227：frameTransform 同口径带旋转
                        modifier = Modifier
                            .fillMaxSize()
                            .frameTransform(frameRotationDeg, mirrored),
                        contentScale = ContentScale.Crop,
                    )
                    // R162 零遮罩：旧版加载中叠 0.45 黑罩压暗封面（0b19725 同病已否），
                    // 封面原色直出，转圈/待播箭头高对比本色直压
                }
                if (playable) {
                    // 已可见、流地址未就绪：转圈（内联统一 22dp/2dp，退化态圆点不至消失）
                    CircularProgressIndicator(
                        Modifier.align(Alignment.Center).size(22.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                } else {
                    // 尚不可见：淡 ▶ 水印表示待播
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.25f),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(36.dp),
                    )
                }
            }
        }
        }

        // 1.5) R217 ③ 已看降饱和叠层退役（与上方内容层 0.55 降亮同刀）：双层压暗
        //      是「每格视频都很暗」的实锤现场（显示即落 seen 后几乎全格命中），
        //      已看信息只留焦点格声波旁的灰胶囊，画面亮度与未看格统一

        // 2) 手势层：单击焦点/暂停/重试，双击点赞飘心，长按拖拽换格
        //    （PlayerView 会吞触摸，故叠一层；不消费位移，画布平移与捏合不受影响）
        Box(
            Modifier
                .fillMaxSize()
                // 点按探测：单击 / 双击。长按刻意不在这里处理，交给下面的拖拽探测，
                // 两个探测器共存于同一节点：快速点按时拖拽探测等不到长按超时自动取消，
                // 长按起拖后位移被消费，点按探测不会再触发
                .pointerInput(poolKey) {
                    detectTapGestures(
                        onTap = { currentOnTap.value() },
                        onDoubleTap = { offset -> currentOnDoubleTap.value(offset) },
                    )
                }
                // 长按拖拽换格：起拖即上抛；累计位移小于阈值就松手 = 传统长按，弹小菜单
                // R217 ④ 探针：长按链实机裁决打点保留（dragStart→dragEnd/cancel→menu）
                // R230 追加3：换用自绘 detectCellLongPressDrag（等待窗微动吃掉——
                // 库版等待期不消费位移，画布平移在 slop 处抢跑滚屏+长按被位移取消，
                // 实锤「长按拖动时屏幕跟着滚」；详见该函数头注释）
                .pointerInput(poolKey) {
                    detectCellLongPressDrag(
                        onDragStart = {
                            Log.i("GridGesture", "长按起拖：格=${slotId.takeLast(6)}")
                            onDragStartCell()
                        },
                        onDrag = { dragAmount ->
                            onDragCell(dragAmount)
                        },
                        onDragEnd = { totalPx ->
                            // 松手已按悬浮落区处理（内容飞出去了）就不再弹长按菜单，
                            // 否则悬浮窗与菜单同时出现、菜单还挂在已换片的格子上
                            val popped = onDragEndCell()
                            Log.i(
                                "GridGesture",
                                "长按松手：格=${slotId.takeLast(6)} 位移=${totalPx.toInt()}px popped=$popped → 菜单=${!popped && totalPx < 24.dp.toPx()}",
                            )
                            if (!popped && totalPx < 24.dp.toPx()) showMenu = true
                        },
                        onDragCancel = {
                            Log.i("GridGesture", "长按取消：格=${slotId.takeLast(6)}")
                            onDragCancelCell()
                        },
                    )
                },
        )

        // 2.5) 三层 chrome 模型（§3.1）：手势层上方不再有常驻浮层——
        //      L0 只剩画面 + 底部渐变频道名；×/音量/声波/焦点环全部收进 L1 焦点态

        // 3) 中央反馈（R149 ⑤ 图标语义=表达当前状态；R230 ② 改实际状态驱动）：
        //    ⏸ 常驻条件 = 播放器在手且确实没在播，且（手动暂停 R375 起不被 !buffering
        //    吞——用户在缓冲窗内按下暂停时转圈被 !paused 闸收起，⏸ 必须顶上（与
        //    VideoPage 中央 ⏸ 同口径，修「缓冲窗内暂停后既无转圈也无 ⏸」的双无窗）；
        //    非手动项（全停/后台暂停/全格 pauseAll 的 playWhenReady=false）保持
        //    旧门：没在缓冲才挂 ⏸——旧 (paused||globalPaused) 标记口径在后台/全停
        //    粘滞时视频已在播仍挂着 ⏸ 不走=「播放按钮悬停」实锤现场，口径不回退）；
        //    恢复播闪现 ▶ 600ms（播放中）
        if (player != null && !isPlaying && (paused || (!buffering && !player.playWhenReady))) {
            Icon(
                Icons.Filled.Pause,
                contentDescription = "已暂停",
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(52.dp),
            )
        }
        if (flashResume) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "播放中",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(44.dp),
            )
        }

        // 4) 双击飘心层
        LikeHeartsLayer(
            hearts = hearts,
            onHeartDone = { id -> hearts = hearts.filterNot { it.id == id } },
        )

        // 5) 右上角 × 关闭此格：仅焦点格可见（L1，§4.3），随焦点 fade+scale 0.8→1 进出；
        //    非焦点格关格走长按菜单——9 格不再挂 9 个常驻 ×。
        //    R149 ④：在播空闲 3.5s 后随 chrome 静默收起（t 乘 chromeA 渐隐），点按唤回
        if (focusT > 0f && !chromeless && chromeA > 0.01f) {
            CellCloseButton(
                t = focusT * chromeA,
                onClose = onClose,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }

        // 7) L0 底部频道名行（§3.1）：28dp 行，频道名 11sp（左）+ 已赞小红心 12dp（右，
        //    仅已赞）。R162 零遮罩：渐变 scrim 已删，频道名靠 withOverlayShadow 描边可读
        // LOD：格子缩到极小时底部频道名行（频道名 + 已赞心）不画（chromeless）
        // R149 ④：chrome 空闲静默收起的一部分（连频道名一起消失，画面纯净）
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

        // 7.5) L1 音量胶囊（P0-2）：2dp 细轨（白20%/AccentRed）浮动在频道名行上方；
        //      仅焦点格挂载（非焦点格零 chrome），全局静音时整体不显示。
        //      R149 ④：随 chrome 空闲收起；调音量 = 交互，kick 唤回并重置计时
        if (focusT > 0f && !muteAll && !chromeless && chromeA > 0.01f) {
            CellVolumePill(
                volume = volume,
                onVolumeChange = {
                    chrome.kick()
                    onVolumeChange(it)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 36.dp)
                    .graphicsLayer {
                        alpha = focusT * chromeA
                        translationY = (1f - focusT) * 8.dp.toPx()
                    },
            )
        }

        // 9) 焦点环（§4.1）：2dp AccentRed 内嵌圆角环，静态不呼吸 + 内侧柔光，
        //    落位 scale 1.06→1 + alpha 0→1 由 focusT 驱动——"光"代替"闪"，
        //    取代旧版顶部 2dp 呼吸红线（廉价闪烁感来源）
        // LOD：格子缩到极小时焦点环不画（chromeless）
        if (!chromeless) CellFocusRing(t = focusT)

        // 9.5) 出声指示（§4.1）：左上 3 柱声波，正在出声才播动画（否则定格等高小柱）；
        //      焦点格已看时右侧补一颗 10sp 灰胶囊——信息不丢、常驻噪音没有。
        //      R149 ④：随 chrome 空闲静默收起（已看胶囊同命运），点按唤回
        if (focusT > 0f && !chromeless && chromeA > 0.01f) {
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .graphicsLayer { alpha = focusT * chromeA },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CellSoundBars(active = isPlaying && !muteAll && volume > 0f)
                if (watched) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "已看",
                        color = TgFeedTokens.TextLow,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        modifier = Modifier
                            .background(
                                TgFeedTokens.OverlayBg,
                                RoundedCornerShape(TgFeedTokens.RadiusPill),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }

        // 10) 拖拽落点高亮（§4.7）：白色 2dp 内环 + 内容下沉 0.96（dropScale 在根层），
        //     松手弹簧归位——红只留焦点语义，不再撞焦点红
        if (dragTarget) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(1.dp)
                    .border(
                        2.dp,
                        Color.White.copy(alpha = 0.9f),
                        RoundedCornerShape(TgFeedTokens.CellRadius),
                    ),
            )
        }

        // 8) 长按小菜单：收藏 / 下载 / 关闭此格 / 全屏单播
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text(if (favorited) "取消收藏" else "收藏") },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = if (favorited) TgFeedTokens.StarYellow else MaterialTheme.colorScheme.onSurface,
                    )
                },
                onClick = {
                    onToggleFavorite()
                    showMenu = false
                },
            )
            DropdownMenuItem(
                text = { Text(downloadLabel) },
                leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                enabled = downloadStatus !is DownloadStatus.Running && downloadStatus !is DownloadStatus.Done,
                onClick = {
                    onDownload()
                    showMenu = false
                },
            )
            // 不感兴趣：视频粒度拉黑（vm 内持久化 + 0 秒强负反馈事件），
            // 本格内容不主动撤下——等换片链自然轮掉，避免菜单操作连带重排
            DropdownMenuItem(
                text = { Text("不感兴趣") },
                leadingIcon = { Icon(Icons.Filled.ThumbDown, contentDescription = null) },
                onClick = {
                    onNotInterested()
                    showMenu = false
                },
            )
            // R227 画面调整（R218 镜像项的同槽升级——与 Feed 播放页长按面板
            // 「画面调整」格同口径）：点按开枢纽小菜单四选一（向左旋转/向右旋转/
            // 左右镜像/恢复默认）；复合态记忆在 FrameAdjustStore（键级缓存跨会话
            // 保持，与 Feed 页共享同一真身——Feed 里调过的分屏里也生效）；
            // 换片 remember(item.key) 自动对新内容重查记忆表
            DropdownMenuItem(
                text = { Text("画面调整") },
                leadingIcon = { Icon(Icons.Filled.ScreenRotation, contentDescription = null) },
                onClick = {
                    onOpenFrameAdjust()
                    showMenu = false
                },
            )
            DropdownMenuItem(
                text = { Text("关闭此格") },
                leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
                onClick = {
                    onClose()
                    showMenu = false
                },
            )
            DropdownMenuItem(
                text = { Text("全屏单播") },
                leadingIcon = { Icon(Icons.Filled.Fullscreen, contentDescription = null) },
                onClick = {
                    showMenu = false
                    onFullscreen()
                },
            )
        }
    }
}

/**
 * R230 追加3（拖动不滚屏）：格子长按拖拽自绘探测器，替代库版
 * detectDragGesturesAfterLongPress（等待/起拖/结束三段行为等价 + 两处关键差异）。
 *
 * 差异①（等待窗容忍放宽）：库版等待窗（longPressTimeout≈400ms）内位移超过
 * touchSlop 即放弃——用户长按时手指微抖 20px 就把长按抖死，随后画布平移接管
 * （用户实锤「长按拖动时屏幕跟着滚」的主现场）。本版等待窗容忍 3×slop 的累计
 * 微动，配合画布侧同刀（首按落在格子上时平移启动门槛抬到 4×slop，见
 * GridScreen.detectCanvasTransformGestures）：长按等待期画布不抢跑，起拖后位移
 * 全消费=拖拽期画布静默跳过；真要平移（快速大幅移动超 4×slop）时本探测器放弃
 * 让位、事件流交还画布。
 * 差异②（等待窗不消费位移）：等待窗内刻意**不消费**任何位移——同节点的点按层
 * （detectTapGestures，Main pass 在本层之后读同一事件）会把「被消费的 move」判为
 * 手势取消，input tap 自带的 1~3px 抖动 move 被吃掉=全部点按变哑（16384 实锤：
 * 点格子零日志）。防滚屏由画布侧门槛承担，本层保持事件流干净。
 *
 * 事件纪律：up 事件永不消费（点按层需要）；起拖后位移全消费（换格拖拽独占）；
 * 超时=长按成立（withTimeout 包 awaitPointerEvent 循环，与库版
 * awaitLongPressOrCancellation 同一模式）；[onDragEnd] 携带累计位移 px（调用方判
 * 「原地长按松手 <24dp = 弹菜单」）。
 */
private suspend fun PointerInputScope.detectCellLongPressDrag(
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: (dragPx: Float) -> Unit,
    onDragCancel: () -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        // 阶段一：长按等待窗（容忍 3×slop 累计微动；不消费位移——点按层同事件后读）。
        // null=超时（长按成立）；非 null=放弃（抬指/位移超容忍/被别层抢占）
        val timedOut = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var drift = 0f
            while (true) {
                val event = awaitPointerEvent()
                // 抬指（含部分抬指，单指拖拽语义）→ 放弃，点按层处理 up
                if (!event.changes.any { it.pressed }) break
                // 别层抢占位移（拖柄等，防御性让位）
                if (event.changes.any { it.isConsumed && it.positionChanged() }) break
                drift += event.changes.sumOf {
                    it.positionChange().getDistance().toDouble()
                }.toFloat()
                if (drift > slop * 3f) break // 用户要平移画布 → 让位（画布 4×slop 门槛后接管）
            }
        }
        if (timedOut != null) return@awaitEachGesture // 等待窗放弃：本轮无拖拽
        // 阶段二：长按成立 → 起拖；此后位移全消费，up=结束（累计位移上抛判菜单）
        onDragStart()
        var dragDistance = Offset.Zero
        var ended = false
        while (!ended) {
            val event = awaitPointerEvent()
            if (!event.changes.any { it.pressed }) {
                ended = true
                onDragEnd(dragDistance.getDistance())
            } else if (event.changes.any { it.isConsumed && it.positionChanged() }) {
                // 起拖后被别层抢占（防御：正常无并存子层）——按取消处理
                ended = true
                onDragCancel()
            } else {
                val change = event.changes.firstOrNull() ?: continue
                val delta = change.positionChange()
                if (delta != Offset.Zero) {
                    dragDistance += delta
                    onDrag(delta)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        }
    }
}
