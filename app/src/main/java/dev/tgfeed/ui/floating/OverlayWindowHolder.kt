package dev.tgfeed.ui.floating

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import dev.tgfeed.core.playback.PlayerPool
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.core.td.safeInitialPlayUri
import dev.tgfeed.ui.PlaybackTogglePolicy
import dev.tgfeed.ui.components.withOverlayShadow
import dev.tgfeed.ui.theme.TgFeedTheme
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 单个系统悬浮窗：WindowManager（TYPE_APPLICATION_OVERLAY）里挂一个 ComposeView，
 * 播放器从悬浮池独占一台（"sysfloat:" 前缀 key）。
 *
 * 手势（全部走 Compose 指针事件，不挂 View.OnTouchListener）：
 *  - 手指落下 → 抢焦点出声（不消费事件，不影响后续手势链）；
 *  - 单指拖动 → 跟手移动（直接改 LayoutParams.x/y，至少保留 48dp 可见）；
 *  - 抬手 → 贴边停靠：动画滑向最近纵边，并把几何（宽占比/停靠边/纵占比）写回记忆；
 *  - 单击画面 → 回 App 定位到该视频并关窗（按钮区点击被子节点消费，不会误触本手势）；
 *  - 右上角常驻：暂停/继续 + 关闭 ×。
 *
 * 视觉与 App 内 FloatingWindow 同款：8dp 圆角纯画面（宽高比跟随视频）+
 * 底部 2dp 进度细线（纯展示，点按归"回 App"手势）+ 缓冲圈 / 暂停 ▶ /
 * 封面兜底 / 失败态。失败重试不做窗内闭环——点按回 App 在 Feed 里重试。
 */
internal class OverlayWindowHolder(
    val key: String,
    val item: VideoItem,
    private val windowContext: Context,
    private val windowManager: WindowManager,
    private val screenW: Int,
    private val screenH: Int,
    private val gateway: TdGateway,
    private val playerPool: PlayerPool,
    parentScope: CoroutineScope,
    cascadeIndex: Int,
    geometry: OverlayGeometry,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        /** 手指落下（含拖动起点）：抢焦点出声 */
        fun onTouched(key: String)

        /** 关闭 × 点击 */
        fun onCloseRequested(key: String)

        /** 视频播完：自动关窗 */
        fun onPlaybackEnded(key: String)

        /** 单击画面：回 App 定位到该视频并关窗 */
        fun onReturnToApp(key: String)

        /** 拖动抬手（贴边停靠完成）：回写几何记忆 */
        fun onDragEnded(key: String, widthFraction: Float, dockLeft: Boolean, yFraction: Float)
    }

    private val poolKey = "sysfloat:$key"
    private val owner = OverlayViewOwner()

    // 窗级协程域：关窗即取消，拉地址/进度轮询都不会落到死窗上
    private val holderJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + holderJob)

    @Volatile
    var detached = false
        private set

    // ---- Compose 可观察状态（OverlayWindowContent 直接订阅） ----
    var player by mutableStateOf<ExoPlayer?>(null)
        private set
    var buffering by mutableStateOf(true)
        private set
    var failed by mutableStateOf(false)
        private set
    var paused by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set

    // R230 追加4「播完自动连播」开关镜像（真身 AppPrefs.floatingAutoAdvance 全局共享，
    // App 内浮窗控制条同款开关同源；连播裁决在控制器 onPlaybackEnded 读 prefs）：
    // 系统窗右上常驻组的切换钮即写盘
    var autoAdvanceOn by mutableStateOf(dev.tgfeed.core.prefs.AppPrefs(windowContext).floatingAutoAdvance)
        private set

    fun toggleAutoAdvance() {
        autoAdvanceOn = !autoAdvanceOn
        dev.tgfeed.core.prefs.AppPrefs(windowContext).floatingAutoAdvance = autoAdvanceOn
    }

    /** 本窗当前是否有声（音频纪律由控制器仲裁，这里只执行） */
    @Volatile
    private var audible = false

    // 窗口宽高比跟随视频真实尺寸（同 App 内浮层口径：限幅防脏数据出极端比例，未知回落 16:9）
    private val videoAspect: Float =
        if (item.width > 0 && item.height > 0) {
            (item.width.toFloat() / item.height.toFloat()).coerceIn(0.45f, 2.4f)
        } else {
            16f / 9f
        }

    private val minWpx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 160f, windowContext.resources.displayMetrics,
    ).toInt()
    private val keepVisiblePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 48f, windowContext.resources.displayMetrics,
    ).toInt()

    private val widthPx: Int
    private val heightPx: Int
    private val lp: WindowManager.LayoutParams

    init {
        // 初始宽 = 屏宽 × 记忆占比；竖屏视频防过高（≤屏高 70%，超出按视频比例反推宽度）
        // R110：极窄窗口（分屏小窗模式屏宽可低于 160dp 下限）minWpx > screenW 时
        // coerceIn(min>max) 直接抛异常——下限先 minOf 收边，与 R109 F-1 同法
        var w = (screenW * geometry.widthFraction).toInt().coerceIn(minOf(minWpx, screenW), screenW)
        if (w / videoAspect > screenH * 0.7f) w = (screenH * 0.7f * videoAspect).toInt()
        widthPx = w
        heightPx = (w / videoAspect).toInt()
        // 记忆位置 + 多窗 24dp 级联错位（避免新窗与在飞窗完全重叠找不到）
        val step = (24 * windowContext.resources.displayMetrics.density).toInt()
        val x0 = if (geometry.dockLeft) 0 else (screenW - widthPx).coerceAtLeast(0)
        val y0 = ((screenH * geometry.yFraction).toInt() + step * cascadeIndex)
            .coerceIn(0, (screenH - heightPx).coerceAtLeast(0))
        lp = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE：返回键/输入法等留给底层 App，本窗只吃触摸事件
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = x0
            y = y0
        }
    }

    val view: View = ComposeView(windowContext).apply {
        // ComposeView 挂 WindowManager 没有 Activity/Fragment 供给树属主，手工补三件套
        // （Kotlin 侧引用会被 KMP 元数据过滤，走 Java 桥 OverlayTreeWiring）
        OverlayTreeWiring.wire(this, owner, owner, owner)
        setContent {
            TgFeedTheme {
                OverlayWindowContent(this@OverlayWindowHolder)
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            buffering = state == Player.STATE_BUFFERING
            // 播完自动关窗（池构建口径 REPEAT_MODE_OFF，播完即 STATE_ENDED）
            if (state == Player.STATE_ENDED) callbacks.onPlaybackEnded(key)
        }

        override fun onPlayerError(error: PlaybackException) {
            failed = true
        }
    }

    /** 开窗：补生命周期 → 挂进 WindowManager → 异步装填播放。addView 失败返回 false */
    fun attach(): Boolean {
        owner.onAttach()
        val ok = runCatching {
            windowManager.addView(view, lp)
            true
        }.getOrElse {
            android.util.Log.w("SysFloat", "悬浮窗 addView 失败: ${it.message}")
            owner.destroy()
            false
        }
        if (ok) loadAndPlay()
        return ok
    }

    /** 播放接线与 Feed/浮层同一模式：地址就绪才 acquire、地址变化才 setMediaItem、listener 自加自删 */
    private fun loadAndPlay() {
        scope.launch {
            // R110：条目自带地址先过 safeInitialPlayUri 收口——裸本地路径含 '#'/'?' 时
            // 直喂 MediaItem.fromUri 会被 Uri.parse 截断（起播即败），收口判空后落入
            // ensurePlayable 换流服务器/编码地址（与 Feed/浮层同一纪律）
            val uri = item.safeInitialPlayUri() ?: runCatching { gateway.ensurePlayable(item) }.getOrElse {
                failed = true
                null
            }
            if (uri == null || detached) return@launch
            val p = playerPool.acquire(poolKey)
            if (detached) {
                // acquire 与关窗竞态：立即归还，防播放器泄漏
                playerPool.release(poolKey)
                return@launch
            }
            // 关闭系统音频焦点自动管理：多窗并发靠显式音量纪律仲裁
            //（理由见 SystemFloatingController 类头注释）
            runCatching { p.setAudioAttributes(p.audioAttributes, false) }
            if (p.currentMediaItem?.localConfiguration?.uri.toString() != uri) {
                // R230 顺手补（R217 ② 挂账同病）：mediaId 带内容 key——LoudnessNormalizer
                // 响度归一靠它查每内容记忆增益，fromUri 无 id 是死层
                p.setMediaItem(
                    androidx.media3.common.MediaItem.Builder()
                        .setUri(uri)
                        .setMediaId(item.key)
                        .build(),
                )
                p.prepare()
            }
            p.playWhenReady = true
            paused = false
            p.addListener(playerListener)
            player = p
            applyVolume()
            startProgressPoll()
        }
    }

    /** 进度轮询：500ms 一次（与 App 内浮层同口径）；时长未知（C.TIME_UNSET）回落条目元数据 */
    private fun startProgressPoll() {
        scope.launch {
            while (true) {
                val p = player ?: break
                val d = p.duration
                // R169（F10）：R166-F4 修了 FloatingWindow/ProgressLine/AudioSeekArea 三处
                // 同签名点漏了这里——else 0L 补 item.durationSec 兜底，时长未知期进度线
                // 不再整段隐藏（与 FloatingWindow.startProgressPoll 同口径）
                val fallbackMs = item.durationSec * 1000L
                durationMs = if (d > 0) d else fallbackMs
                positionMs = if (d > 0) p.currentPosition.coerceAtLeast(0L) else 0L
                delay(500)
            }
        }
    }

    /** 音频纪律执行口：焦点窗出声（固定 1.0），非焦点窗静音续播 */
    fun setAudible(audible: Boolean) {
        this.audible = audible
        applyVolume()
    }

    private fun applyVolume() {
        player?.volume = if (audible) 1f else 0f
    }

    fun togglePause() {
        val p = player ?: return
        // R375：判定换 playWhenReady 意图口径（PlaybackTogglePolicy 单源）——缓冲中
        // isPlaying=false 但用户语义上是"在播"（用户实锤「正在加载缓冲时单击暂停/
        // 播放不生效」的病根：旧口径缓冲期点按→play() 落在已是 true 的意图上零
        // 变化）。ENDED/IDLE 例外在策略内：本窗播完即自动关窗（onPlaybackEnded），
        // 例外态点按行为与旧口径逐字一致。
        if (PlaybackTogglePolicy.userPlayingIntent(p.playWhenReady, p.playbackState)) {
            p.pause()
            paused = true
        } else {
            p.play()
            paused = false
        }
    }

    fun requestClose() = callbacks.onCloseRequested(key)

    fun onWindowTouched() = callbacks.onTouched(key)

    fun onWindowTap() = callbacks.onReturnToApp(key)

    /** 跟手拖动：直接改 LayoutParams；边界钳制保证至少 48dp 可见（同 App 内浮层口径） */
    fun onDragBy(dx: Float, dy: Float) {
        if (detached) return
        lp.x = (lp.x + dx.roundToInt()).coerceIn(keepVisiblePx - widthPx, screenW - keepVisiblePx)
        lp.y = (lp.y + dy.roundToInt()).coerceIn(0, screenH - keepVisiblePx)
        runCatching { windowManager.updateViewLayout(view, lp) }
    }

    /** 抬手贴边停靠：动画滑向最近纵边，随后回写几何记忆 */
    fun onDragEnd() {
        if (detached) return
        val targetX = if (lp.x + widthPx / 2 < screenW / 2) 0 else (screenW - widthPx).coerceAtLeast(0)
        ValueAnimator.ofInt(lp.x, targetX).apply {
            duration = 160
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                if (!detached) {
                    lp.x = anim.animatedValue as Int
                    runCatching { windowManager.updateViewLayout(view, lp) }
                }
            }
            start()
        }
        callbacks.onDragEnded(
            key,
            widthPx.toFloat() / screenW.toFloat(),
            dockLeft = targetX == 0,
            yFraction = lp.y.toFloat() / screenH.toFloat(),
        )
    }

    /** 展示尺寸变化后把窗口钳回屏内（要求整窗可见，比拖动钳制更严格；同 App 内浮层的旋转兜底） */
    fun clampToBounds() {
        if (detached) return
        val nx = lp.x.coerceIn(0, (screenW - widthPx).coerceAtLeast(0))
        val ny = lp.y.coerceIn(0, (screenH - heightPx).coerceAtLeast(0))
        if (nx != lp.x || ny != lp.y) {
            lp.x = nx
            lp.y = ny
            runCatching { windowManager.updateViewLayout(view, lp) }
        }
    }

    /** 关窗回收：取消协程 → 摘监听 → 还播放器 → 摘 View → 销组合与树属主。幂等 */
    fun detach() {
        if (detached) return
        detached = true
        holderJob.cancel()
        player?.let { runCatching { it.removeListener(playerListener) } }
        runCatching { playerPool.release(poolKey) }
        // 权限被收回时系统已先行摘窗，removeView 会抛——必须 runCatching
        runCatching { windowManager.removeView(view) }
        (view as? ComposeView)?.disposeComposition()
        owner.destroy()
    }
}

/**
 * ComposeView 挂进 WindowManager 所需的树属主：Compose 组合依赖
 * LifecycleOwner/ViewModelStoreOwner/SavedStateRegistryOwner 三件套，
 * 悬浮窗没有 Activity/Fragment，这里手工驱动（开窗 RESUMED、关窗 DESTROYED）。
 */
private class OverlayViewOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry.createUnsafe(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun onAttach() {
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}

/** 系统悬浮窗的 Compose 内容：视觉与 App 内 FloatingWindow 对齐（纯画面 + 进度线 + 常驻小按钮） */
@Composable
private fun OverlayWindowContent(h: OverlayWindowHolder) {
    // 封面占位（minithumbnail），播放器就绪前/失败时兜底
    val cover = remember(h.key) {
        h.item.coverBytes?.let { bytes ->
            runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            // 落下即抢焦点出声（不消费事件，不影响后续手势链）
            .pointerInput(h.key) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    h.onWindowTouched()
                }
            }
            // 单击画面 = 回 App 定位到该视频（按钮区点击被子节点消费，走不到这里）
            .pointerInput(h.key) {
                detectTapGestures(onTap = { h.onWindowTap() })
            }
            // 单指拖动跟手；抬手贴边停靠。tap 与 drag 探测互不抢：越过 touch slop
            // 进拖动则 tap 取消，未越过则 drag 不启动
            .pointerInput(h.key) {
                detectDragGestures(
                    onDrag = { _, dragAmount -> h.onDragBy(dragAmount.x, dragAmount.y) },
                    onDragEnd = { h.onDragEnd() },
                )
            },
    ) {
        val player = h.player
        when {
            player != null -> AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
            cover != null -> Image(
                bitmap = cover,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        // 失败态：点按回 App（窗内不做重试闭环）。
        // R162 零遮罩：旧版 0.45 黑罩压暗画面已删（0b19725 同病），文案改描边可读
        if (h.failed) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "加载失败",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelMedium.withOverlayShadow(),
                )
                Text(
                    "点按回 App",
                    color = Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.labelSmall.withOverlayShadow(),
                )
            }
        }

        // 缓冲圈 / 暂停 ▶（与 App 内浮层同规格）
        if (h.buffering && player != null && !h.paused && !h.failed) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(22.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        }
        if (h.paused && player != null && !h.failed) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "已暂停",
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(40.dp),
            )
        }

        // 底部 2dp 播放进度细线：白 α0.25 轨道 + 白 α0.9 激活段，纯展示
        //（点按归"回 App"手势，不做窗内 seek——避免与单击手势抢热区）
        if (player != null && !h.failed && h.durationMs > 0) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Color.White.copy(alpha = 0.25f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(
                            (h.positionMs.toFloat() / h.durationMs.toFloat()).coerceIn(0f, 1f),
                        )
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.9f)),
                )
            }
        }

        // 右上角常驻：暂停/继续 + 连播开关 + 关闭 ×（半透明圆底，亮画面也可辨）
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp),
        ) {
            if (player != null && !h.failed) {
                Icon(
                    imageVector = if (h.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (h.paused) "继续" else "暂停",
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(TgFeedTokens.OverlayBg, CircleShape)
                        .clickable { h.togglePause() }
                        .padding(4.dp),
                )
            }
            // R230 追加4：播完自动连播开关（默认开，AppPrefs 全局共享）——
            // 亮=开（0.9）、暗=关（0.35），与 App 内浮窗控制条同款同源
            Icon(
                imageVector = Icons.Filled.Autorenew,
                contentDescription = "播完自动连播",
                tint = Color.White.copy(alpha = if (h.autoAdvanceOn) 0.9f else 0.35f),
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(TgFeedTokens.OverlayBg, CircleShape)
                    .clickable { h.toggleAutoAdvance() }
                    .padding(4.dp),
            )
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "关闭悬浮窗",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(TgFeedTokens.OverlayBg, CircleShape)
                    .clickable { h.requestClose() }
                    .padding(4.dp),
            )
        }
    }
}
