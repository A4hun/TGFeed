package dev.tgfeed.ui.floating

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.tgfeed.core.playback.PlayerPool
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.core.td.safeInitialPlayUri
import dev.tgfeed.ui.PlaybackTogglePolicy
import dev.tgfeed.ui.components.SeekGestureOutcome
import dev.tgfeed.ui.components.fatalReasonOf
import dev.tgfeed.ui.components.seekGestureLoop
import dev.tgfeed.ui.components.formatDuration
import dev.tgfeed.ui.components.withOverlayShadow
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** 控制条无操作自动隐藏时长（毫秒） */
private const val ControlsAutoHideMs = 2500L

/** 窗口最小宽度 / 拖动后至少保留在屏内的可见尺寸 */
private val MinWindowWidth = 160.dp
private val KeepVisible = 48.dp

/**
 * 单个悬浮小窗（无边框版）：窗口本体就是一块 8dp 圆角的纯视频画面，
 * 无标题栏、无边框、无阴影，频道名等文字信息一律取消。
 *
 * 手势分工（全部挂在窗口自身的 Box 链上，不外溢）：
 *  - 任意处手指落下 → 抢焦点（置顶 + 解除静音），不消费事件；
 *  - 整窗都是热区：单指拖动 = 移动（边界钳制：至少保留 48dp 可见），
 *    双指捏合 = 缩放（宽度 160dp ~ 屏宽，窗口宽高比恒定跟随视频）；
 *  - 窗口宽高比跟随 VideoItem.width/height：横屏视频 16:9 宽窗、竖屏视频 9:16 竖窗；
 *    无尺寸信息（如本地文件 width/height=0）时保持 16:9，捏合缩放始终保持该比例。
 *  - 单击画面 = 只切换迷你控制条显隐（失败态 = 重试；不可重试的稳定失败除外——
 *    R78 分诊：解码失败/资源失效点击不再假重试），单击不再直接暂停；
 *  - 控制条 = 半透明黑胶囊（暂停/继续 + 音量滑杆 + 关闭 ×），
 *    2.5s 无操作自动隐藏，条上任意操作重置计时；
 *  - 控制条隐藏时右上角常驻一个 18dp 半透明小 ×，可直接关窗（失败态同样可用）；
 *  - 控制条带"手势护盾"：在 Main 阶段消费位移，拖滑杆不会误触发整窗拖动。
 *    （点按无需护盾：胶囊是单击手势层上方的兄弟节点，命中路径不含手势层，
 *    点胶囊空白处不会误触"切换显隐"。）
 *  - 窗口底部贴一条 2dp 播放进度细线（白 α0.25 轨道 + 白 α0.9 激活段），
 *    500ms 轮询 player 位置刷新；player 为空 / 时长未知 / 失败态时整条隐藏。
 *    细线外扩 12dp 透明触摸热区：点按 = 按比例 seekTo，落点起全程消费事件，
 *    既不触发整窗拖动/缩放，也不会误触"单击切换控制条显隐"。
 *  - 控制条胶囊内附"当前位置/总时长"小字（components 包 internal 的
 *    formatDuration，同模块可直接引用），时长未知时不显示。
 *
 * 播放接线与 Feed 页同一模式：条件式 acquire（"float:" 前缀 key）、
 * 地址变化才 setMediaItem、listener 自加自删、窗口销毁 release 回池。
 */
@Composable
internal fun FloatingWindow(
    item: VideoItem,
    gateway: TdGateway,
    playerPool: PlayerPool,
    focused: Boolean,
    cascadeIndex: Int,
    onFocus: () -> Unit,
    onClose: () -> Unit,
    // 系统画中画（PiP）：pipMode=true 时本窗作为 PiP 内容满窗纯视频渲染
    //（无偏移/圆角/控件/进度/手势）；hiddenInPip=true（PiP 中的非焦点窗）不渲染
    // 任何内容但保持组合——播放器存活、静音续播，退出 PiP 立即复原。
    // 两者默认 false，App 内悬浮窗原行为不受影响
    pipMode: Boolean = false,
    hiddenInPip: Boolean = false,
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    // R230 追加4「播完自动连播」开关（默认开，用户拍板=可控开关非强制）：
    // 播完经容器队列取下一条原位换片；关=维持定格现状
    val prefs = remember { dev.tgfeed.core.prefs.AppPrefs(context) }
    // 屏幕尺寸（px），用于拖动/缩放边界钳制
    val screenWpx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHpx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val minWpx = with(density) { MinWindowWidth.toPx() }
    val keepVisiblePx = with(density) { KeepVisible.toPx() }

    // R230 追加4：窗内容可原位换片（播完自动连播）——[currentItem] 驱动内容族状态
    // （key(currentItem.key) 包裹下方内容族：换片全量重置+自动起播）；窗几何
    // （位置/尺寸/音量）属窗本体，换片不重置。队列真身在 App.container.floatingQueues
    //（分屏悬浮落区弹出时供给），播完 advanceFloatingQueue 消费并前移
    var currentItem by remember { mutableStateOf(item) }

    // 窗口宽高比跟随视频真实尺寸：横屏 16:9 宽窗、竖屏 9:16 竖窗；
    // 无尺寸信息（width/height=0，如本地文件）回落 16:9；限幅防脏数据出极端比例
    val videoAspect = remember(currentItem.key) {
        if (currentItem.width > 0 && currentItem.height > 0) {
            (currentItem.width.toFloat() / currentItem.height.toFloat()).coerceIn(0.45f, 2.4f)
        } else {
            16f / 9f
        }
    }

    // 窗口宽（px）：初始约屏宽 45%；高度 = 宽 / 视频比例（跟随内容，横屏宽扁竖屏高瘦）
    // R230 追加4：几何三件（宽/位置/音量）改窗本体记忆（不随换片重置——连播时
    // 窗口位置尺寸不跳变）
    var widthPx by remember {
        // R109 F-1：窄容器（分屏/自由窗口屏宽 < 160dp）下 minWpx > screenWpx，
        // coerceIn(min>max) 直接抛异常——下限先 minOf 收编，保证 min<=max
        val w0 = (screenWpx * 0.45f).coerceIn(minOf(minWpx, screenWpx), screenWpx)
        // 竖屏视频防过高：初始高度不超过屏高 70%，超出则按视频比例反推宽度
        val maxH = screenHpx * 0.7f
        mutableFloatStateOf(if (w0 / videoAspect > maxH) maxH * videoAspect else w0)
    }
    // 窗口左上角位置：多窗层叠错位（28dp 阶梯），并保证初始落在屏内
    var offset by remember {
        val step = with(density) { 28.dp.toPx() }
        val startX = (step * cascadeIndex)
            .coerceIn(0f, (screenWpx - widthPx).coerceAtLeast(0f))
        val startY = (with(density) { 96.dp.toPx() } + step * cascadeIndex)
            .coerceIn(0f, screenHpx * 0.5f)
        mutableStateOf(Offset(startX, startY))
    }
    // 独立音量：按窗 key 记忆；仅焦点窗实际写入 player，非焦点窗恒静音
    var volume by remember { mutableFloatStateOf(1f) }

    key(currentItem.key) {
        // 内容族影子变量：块内全部 item 引用都指当前内容（换片随 key 重置）
        val item = currentItem
        val poolKey = "float:" + item.key

        // 播放地址 / 失败态 / 重试计数 / 暂停态 / 缓冲态 / 恢复播放闪现 ⏸
        // R80：自带地址先过收口判定（含 '#'/'?' 的裸路径置 null 走 ensurePlayable 换流地址）
        var playUri by remember(item.key) { mutableStateOf(item.safeInitialPlayUri()) }
        var failed by remember(item.key) { mutableStateOf(false) }
    // R78 分诊：不可重试失败的病因（解码失败/资源失效等稳定终态，与 VideoPage
    // 同一 fatalReasonOf 口径）；非空时不再给"点击重试"引导——重试救不了，不假装在努力
    var fatalReason by remember(item.key) { mutableStateOf<String?>(null) }
    var retryTick by remember(item.key) { mutableIntStateOf(0) }
    // 重试换路副本（R109 F-2，照 VideoPage 换路法）：点按重试时剥掉条目缓存的 playUri——
    // ensurePlayable 对 item.playUri 短路直返，缓存路径一旦被清/失效，
    // 点重试原样拿回同一死地址，永远失败；副本置 null 强制重新解析
    var retryItem by remember(item.key) { mutableStateOf(item) }
    var paused by remember(item.key) { mutableStateOf(false) }
    var buffering by remember(item.key) { mutableStateOf(true) }
    var flashResume by remember(item.key) { mutableStateOf(false) }

    // 播放进度：500ms 轮询写入；durationMs<=0 视为"时长未知"，进度条整体隐藏。
    // R164b scrubbing：拖动进度条期间冻结 positionMs 轮询写入——细线跟手不回跳
    var scrubbing by remember(item.key) { mutableStateOf(false) }
    var positionMs by remember(item.key) { mutableLongStateOf(0L) }
    var durationMs by remember(item.key) { mutableLongStateOf(0L) }

    // 控制条显隐 + 操作心跳（条上任意操作 +1，重置 2.5s 自动隐藏计时）
    var controlsVisible by remember(item.key) { mutableStateOf(false) }
    var controlsTick by remember(item.key) { mutableIntStateOf(0) }

    // R230 追加4：连播开关镜像（真身 AppPrefs 全局共享；随内容换片重读外部变更）
    var autoAdvanceOn by remember(item.key) { mutableStateOf(prefs.floatingAutoAdvance) }

    // 封面占位（minithumbnail），加载中/失败时兜底显示
    val cover: ImageBitmap? = remember(item.key) {
        item.coverBytes?.let { bytes ->
            runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
            }.getOrNull()
        }
    }

    // playUri 为空时挂起拉取地址；失败进重试态（retryTick 变化触发重拉）
    LaunchedEffect(item.key, retryTick) {
        if (playUri == null && !failed) {
            // 用换路副本而非原条目：点按重试已剥掉缓存 playUri，避免原样拿回死地址
            playUri = runCatching { gateway.ensurePlayable(retryItem) }.getOrElse {
                failed = true
                // R78 分诊：内部逻辑错误（条目缺 fileId 等）重试也是同一堵墙，亮明病因
                if (it is IllegalArgumentException) fatalReason = "视频信息缺失，无法播放"
                null
            }
        }
    }

    // 地址就绪且非失败态才从悬浮池取播放器（与 Feed 页同一条件式 acquire 模式）
    val player = if (playUri != null && !failed) playerPool.acquire(poolKey) else null

    LaunchedEffect(player, playUri) {
        val p = player ?: return@LaunchedEffect
        val uri = playUri ?: return@LaunchedEffect
        if (p.currentMediaItem?.localConfiguration?.uri.toString() != uri) {
            // R230 顺手补（R217 ② 挂账同病）：mediaId 带内容 key——LoudnessNormalizer
            // 响度归一靠它查每内容记忆增益，fromUri 无 id 是死层（分屏格/VideoPage
            // 主路径早已同口径接通）
            p.setMediaItem(MediaItem.Builder().setUri(uri).setMediaId(item.key).build())
            p.prepare()
        }
        p.playWhenReady = true
        paused = false
    }

    // 音量即时生效：焦点窗用本窗记忆值，非焦点窗静音
    LaunchedEffect(player, focused, volume) {
        player?.volume = if (focused) volume else 0f
    }

    // 进度轮询：每 500ms 读一次当前位置/总时长（与 ProgressLine 同款
    // while(true)+delay 模式，只是周期放宽到 500ms）。
    // player 为空不启动；duration 可能是 C.TIME_UNSET（负值），统一压成 0 表示未知。
    // R166 duration 兜底：未就绪时用条目元数据时长（TG durationSec 恒有）——悬浮窗
    // 细线与点按比例 seek 不再因 duration==0 整条隐藏/手势被吞（同 ProgressLine 修法）
    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        val fallbackMs = item.durationSec * 1000L
        while (true) {
            val d = p.duration
            durationMs = if (d > 0) d else fallbackMs
            if (!scrubbing) {
                positionMs = if (d > 0) p.currentPosition.coerceAtLeast(0L) else 0L
            }
            delay(500)
        }
    }

    // 失败兜底：立刻停播，避免残响
    LaunchedEffect(failed) {
        if (failed) player?.pause()
    }

    // 播放状态监听：自加自删
    // item.key 入 key：R230 追加4 连播换片时池可能复用同一播放器实例（旧 key 释放
    // 归 idle、新 key acquire 弹回同台），仅按 player 实例判等不会重建监听——
    // 换片后 END 回调会拿着旧 item 查错队列，故按内容也换新监听
    DisposableEffect(player, item.key) {
        val p = player ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) {
                    // R230 追加4 播完自动连播（开关默认开）：容器队列取下一条原位
                    // 换片（窗几何不动，key(currentItem.key) 块全量重置+自动起播）；
                    // 开关关 / 队列尽（Feed 侧弹出无供给）→ 维持定格现状
                    if (prefs.floatingAutoAdvance) {
                        val next = (context.applicationContext as? dev.tgfeed.App)?.container
                            ?.advanceFloatingQueue(item.key)
                        if (next != null) {
                            android.util.Log.i(
                                "FloatAdvance",
                                "小窗连播：…${item.key.takeLast(10)} → …${next.key.takeLast(10)}",
                            )
                            currentItem = next
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                failed = true
                // R78 分诊（与 VideoPage 同一口径）：不可重试的稳定失败亮明病因、
                // 不再给"点击重试"引导；真网络类 fatalReason=null，保持点击重试
                fatalReason = fatalReasonOf(error)
                // R80 埋点：悬浮窗失败错误码 + 分诊结果
                dev.tgfeed.core.playback.PlaybackTrace.log(
                    "floatErr", item.key, "code=${error.errorCode} fatal=${fatalReason ?: "可重试"}",
                )
            }
        }
        p.addListener(listener)
        onDispose { p.removeListener(listener) }
    }

    // 窗口销毁（关窗/宿主销毁）：归还播放器到悬浮池
    DisposableEffect(item.key) {
        onDispose { playerPool.release(poolKey) }
    }

    // ⏸ 闪现 600ms 后自动消失
    LaunchedEffect(flashResume) {
        if (flashResume) {
            delay(600)
            flashResume = false
        }
    }

    // 控制条 2.5s 无操作自动隐藏；controlsTick 变化（条上任意操作）会重启本计时
    LaunchedEffect(controlsVisible, controlsTick) {
        if (controlsVisible) {
            delay(ControlsAutoHideMs)
            controlsVisible = false
        }
    }

    // 单击画面：失败态 = 彻底重试（还实例、清地址、重拉）——但不可重试的稳定失败
    //（fatalReason 非空：解码失败/资源失效）重试救不了，不做假重试，按常规切换控制条；
    // 否则只切换控制条显隐——暂停/继续只能走控制条上的明确按钮。
    // rememberUpdatedState 避免 pointerInput 抓住旧闭包
    val onTapVideo = rememberUpdatedState<() -> Unit>(newValue = {
        if (failed && fatalReason == null) {
            playerPool.release(poolKey)
            retryItem = item.copy(playUri = null) // 换路：不信条目缓存的播放地址（R109 F-2）
            playUri = null
            failed = false
            retryTick++
        } else {
            controlsVisible = !controlsVisible
        }
    })

    // 高度按视频宽高比推导：横屏视频宽扁、竖屏视频高瘦，窗口即画面无黑边
    val videoHpx = widthPx / videoAspect
    val winWDp = with(density) { widthPx.toDp() }
    val winHDp = with(density) { videoHpx.toDp() }

    // 旋转/分屏等容器尺寸变化时兜底重钳：offset 此前只在拖动手势里按当时屏宽钳制，
    // 旋转后旧坐标可能整体飞出屏外（看不到也拖不回）。这里在屏尺寸变化时
    // 把窗口宽与 offset 重新钳进新边界，超出的拉回屏内（比拖动钳制更严格：
    // 要求整窗可见，而非只保留 48dp）。
    LaunchedEffect(screenWpx, screenHpx) {
        // 横→竖旋转后窗口可能比新屏还宽，先按新边界钳窗口宽度（与捏合缩放同款上限）
        val maxW = minOf(screenWpx, screenHpx * videoAspect)
        // R109 F-1：小窗口/高瘦视频时 maxW 可能小于 minWpx，下限收编防 coerceIn(min>max) 抛异常
        widthPx = widthPx.coerceIn(minOf(minWpx, maxW), maxW)
        // 再把左上角钳回 x ∈ 0..(屏宽-窗宽)、y ∈ 0..(屏高-窗高)
        val maxX = (screenWpx - widthPx).coerceAtLeast(0f)
        val maxY = (screenHpx - videoHpx).coerceAtLeast(0f)
        offset = Offset(
            x = offset.x.coerceIn(0f, maxX),
            y = offset.y.coerceIn(0f, maxY),
        )
    }

    // 系统画中画分支（只动渲染层，上方 acquire/装填/监听等播放 effect 全保持原样）：
    //  - hiddenInPip：PiP 中的非焦点窗——不渲染但保持组合，播放器静音续播，
    //    退出 PiP 同一组合复原（不重新 acquire，无二次缓冲）；
    //  - pipMode：PiP 焦点窗——满窗纯视频（系统小窗即画面），无偏移/圆角/控件/进度/手势；
    //    PiP 触摸由系统托管（单击出系统控件、拖动移窗、双击切尺寸），本层不接手势
    if (hiddenInPip) {
        Box(Modifier.size(0.dp))
    } else if (pipMode) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            if (player != null) {
                AndroidView(
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
            } else {
                // 视频未就绪：封面兜底 + 缓冲小圈（PiP 内不可点按，失败重试回全屏后操作）
                if (cover != null) {
                    Image(
                        bitmap = cover,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                if (!failed) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
    } else
    Box(
        Modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .size(width = winWDp, height = winHDp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            // 手指落下即抢焦点（不消费事件，不影响后续手势链）
            .pointerInput(item.key) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onFocus()
                }
            }
            // 单指拖动移动 + 双指捏合缩放；位置钳制保证至少 48dp 可见。
            // 缩放只改宽度，高度由 videoAspect 推导，故捏合全程保持视频比例
            .pointerInput(item.key, screenWpx, screenHpx) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (zoom != 1f) {
                        // 宽度上限取"屏宽"与"屏高对应宽"的较小者，竖屏视频不会高到出屏
                        val maxW = minOf(screenWpx, screenHpx * videoAspect)
                        // R109 F-1：同上，下限收编防 maxW < minWpx 时 coerceIn 抛异常
                        widthPx = (widthPx * zoom).coerceIn(minOf(minWpx, maxW), maxW)
                    }
                    offset = Offset(
                        x = (offset.x + pan.x)
                            .coerceIn(keepVisiblePx - widthPx, screenWpx - keepVisiblePx),
                        y = (offset.y + pan.y)
                            .coerceIn(0f, screenHpx - keepVisiblePx),
                    )
                }
            },
    ) {
        // 视频层：有播放器渲染 PlayerView；否则封面兜底（加载中转圈 / 失败点按重试）
        if (player != null) {
            AndroidView(
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
        } else {
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                // R162 零遮罩：旧版加载态 0.35 黑罩压暗封面（0b19725 同病已否），
                // 封面原色直出，失败文案/转圈可读性走描边与高对比本色
            }
            if (failed) {
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
                        // R78：不可重试失败亮明病因（不再给假重试引导）；网络类保持"点击重试"
                        fatalReason ?: "点击重试",
                        color = Color.White.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.labelSmall.withOverlayShadow(),
                    )
                }
            } else {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(24.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            }
        }

        // 单击手势层：只切换控制条显隐（失败态 = 重试）。
        // 不消费位移，整窗拖动/捏合缩放不受影响
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(item.key, failed) {
                    detectTapGestures(onTap = { onTapVideo.value() })
                },
        )

        // 暂停常驻 ▶ / 恢复闪现 ⏸ / 缓冲小圈
        if (paused && player != null && !failed) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "已暂停",
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(40.dp),
            )
        }
        if (flashResume) {
            Icon(
                Icons.Filled.Pause,
                contentDescription = "继续播放",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp),
            )
        }
        if (buffering && player != null && !paused && !failed) {
            // 内联缓冲小转圈统一 22dp/2dp（动画禁用退化态为 2dp 圆点，仍依稀可辨）
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(22.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        }

        // 底部播放进度细线：2dp 白 α0.25 轨道 + 白 α0.9 激活段，贴窗底；
        // player 为空 / 时长未知 / 失败态时整条隐藏。
        // 声明在控制条之前：与胶囊重叠的区域由胶囊（后声明、更上层）优先命中
        if (player != null && !failed && durationMs > 0) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // 12dp 透明触摸热区（细线本身只有 2dp，点不中）
                    .height(12.dp)
                    // R164b：统一手势引擎（SeekGestureEngine.kt 共享件）——点按跳转
                    // + 拖拽 scrub（旧版只支持点按）+ Compose 1.7 漏出防守（下层
                    // 「单击切换控制条」不再被叠触发）。从 down 起由引擎接管事件
                    // 消费：既不触发外层整窗拖动/捏合缩放，也不会穿透下层单击
                    .pointerInput(player) {
                        val p = player ?: return@pointerInput
                        fun fToTarget(f: Float): Long = (durationMs * f).toLong()
                        seekGestureLoop(
                            // 按下/拖拽即跳显到目标（500ms 轮询被 scrubbing 冻结，不回跳）
                            onPress = { f -> scrubbing = true; positionMs = fToTarget(f) },
                            onDragStart = { f -> positionMs = fToTarget(f) },
                            onDragTo = { f -> positionMs = fToTarget(f) },
                            onOutcome = { outcome, f ->
                                when (outcome) {
                                    SeekGestureOutcome.TAP_SEEK,
                                    SeekGestureOutcome.DRAG_SEEK,
                                    -> {
                                        val d = durationMs
                                        if (d > 0) {
                                            val target = (d * f).toLong()
                                            p.seekTo(target)
                                            positionMs = target
                                        }
                                    }
                                    SeekGestureOutcome.ABANDONED -> Unit
                                }
                            },
                            onCleanup = { scrubbing = false },
                        )
                    },
            ) {
                // 轨道 + 激活段，贴热区底部
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
                                (positionMs.toFloat() / durationMs.toFloat())
                                    .coerceIn(0f, 1f),
                            )
                            .fillMaxHeight()
                            .background(Color.White.copy(alpha = 0.9f)),
                    )
                }
            }
        }

        if (controlsVisible && player != null && !failed) {
            // 迷你控制条：半透明黑胶囊（暂停/继续 + 音量滑杆 + 关闭 ×）。
            // R162 收敛共享 token（值同 0.55，一处改全局生效）
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 10.dp, vertical = 10.dp)
                    .fillMaxWidth()
                    .background(TgFeedTokens.OverlayBg, RoundedCornerShape(50))
                    // 手势护盾：在 Main 阶段吃掉位移事件（Slider 是子节点，
                    // 更早读到未消费的事件不受影响；窗口的 transform 探测器
                    // 在更外层后读到，calculatePan 会跳过已消费位移），
                    // 防止拖滑杆时误触发整窗拖动/缩放
                    .pointerInput(item.key) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach {
                                    if (it.positionChanged()) it.consume()
                                }
                                if (event.changes.all { it.changedToUp() }) break
                            }
                        }
                    }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 暂停/继续按钮（操作即重置自动隐藏计时）
                Icon(
                    imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (paused) "继续" else "暂停",
                    tint = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable {
                            controlsTick++
                            val p = player
                            if (p != null) {
                                // R375：判定换 playWhenReady 意图口径（PlaybackTogglePolicy
                                // 单源）——缓冲中 isPlaying=false 但用户语义上是"在播"
                                //（用户实锤「正在加载缓冲时单击暂停/播放不生效」的病根：
                                // 旧口径缓冲期点按→play() 落在已是 true 的意图上零变化）。
                                // ENDED/IDLE 例外在策略内：播完由小窗连播/定格链处置，
                                // 例外态点按行为与旧口径逐字一致。
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
                        .padding(7.dp),
                )
                // 当前位置/总时长小字（tnum 等宽数字防跳动）；时长未知时不占位
                if (durationMs > 0) {
                    Text(
                        text = formatDuration((positionMs / 1000).toInt()) +
                            "/" + formatDuration((durationMs / 1000).toInt()),
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelSmall
                            .copy(fontFeatureSettings = "tnum"),
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
                // 音量：图标随音量切换，滑杆 0f~1f 即时写入 player.volume
                Icon(
                    imageVector = if (volume <= 0.01f) Icons.Filled.VolumeOff
                    else Icons.Filled.VolumeUp,
                    contentDescription = "音量",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(15.dp),
                )
                Slider(
                    value = volume,
                    onValueChange = {
                        controlsTick++
                        volume = it.coerceIn(0f, 1f)
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .padding(horizontal = 4.dp),
                )
                // R230 追加4「播完自动连播」开关（默认开）：本窗即时切换+落盘
                //（AppPrefs 全局共享，系统悬浮窗右上常驻组同款开关同源）
                Icon(
                    imageVector = Icons.Filled.Autorenew,
                    contentDescription = "播完自动连播",
                    tint = Color.White.copy(alpha = if (autoAdvanceOn) 0.92f else 0.35f),
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable {
                            controlsTick++
                            autoAdvanceOn = !autoAdvanceOn
                            prefs.floatingAutoAdvance = autoAdvanceOn
                        }
                        .padding(7.dp),
                )
                // 关闭 ×（控制条态的关窗入口）
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭悬浮窗",
                    tint = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onClose)
                        .padding(7.dp),
                )
            }
        } else {
            // 非控制条态：右上角 18dp 半透明小 ×（失败/加载态也可直接关窗）。
            // clickable 只消费点按；按住拖动时位移未被消费，整窗拖动照常生效
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "关闭悬浮窗",
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose)
                    .padding(3.dp),
            )
        }
    }
    } // key(currentItem.key)：R230 追加4 内容族收尾（换片=本块全量重置+自动起播）
}
