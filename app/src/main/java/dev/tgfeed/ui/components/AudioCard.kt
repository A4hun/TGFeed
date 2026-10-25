package dev.tgfeed.ui.components

import android.graphics.BitmapFactory
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import dev.tgfeed.ui.components.kit.LocalBottomBarInset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.tgfeed.core.feed.DownloadStatus
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.core.td.safeInitialPlayUri
import dev.tgfeed.ui.SheetMaxWidth
import dev.tgfeed.ui.PlaybackTogglePolicy
import dev.tgfeed.ui.WindowSize
import dev.tgfeed.ui.audioCoverMax
import dev.tgfeed.ui.fontScale
import dev.tgfeed.ui.landscapeFeedChromeOrNull
import dev.tgfeed.ui.rememberWindowSize
import dev.tgfeed.ui.theme.TgFeedTokens
import dev.tgfeed.ui.uiScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Feed 音频页卡（mediaType=2 的条目）——音乐播放器级体验重做（v6）：
 * 垂直布局占满页：大专辑封面（圆角 16dp，播放中 1.0↔1.02 呼吸缩放）→ 标题 + 歌手·频道 →
 * 可拖进度区（语音条显示 TG 波形柱并随进度染色，音乐走细轨+圆点手柄）→
 * 控制排（⏪15s / ▶大圆钮 / ⏩15s / 倍速循环 1x→1.5x→2x）→ 底部操作排（点赞/收藏/下载/分享）。
 *
 * TG 特性融入：
 * - 专辑封面高清化：Audio.albumCoverThumbnail.file（javap 实证）经
 *   TdLibGateway.loadAudioCoverBytes 下载（与视频封面同一缓存模式）；
 *   未就绪回落 albumCoverMinithumbnail（coverBytes），语音/无封面回落渐变底 + 音符 + 波形
 * - 语音波形：VoiceNote.waveform（javap 实证 byte[]）每字节高/低 5bit 各一个 0..31 幅度，
 *   解码重采样成波形柱，已播部分染主色
 *
 * 播放行为（v7 音乐播放器级改造）：
 * - 共享模式（[sharedPlayer] 非空，FeedScreen 持有全页唯一音频播放器）：
 *   卡片只在激活时接管共享播放器（mediaId=item.key 作归属判据），划出销毁**不释放**——
 *   离开音频卡音频继续放，底部迷你播放条（[MiniAudioPlayerBar]）接管展示与控制；
 *   起播/暂停经 [onPlayingChanged] 上报外层（FeedViewModel.nowPlayingAudio 数据源）。
 * - 自管模式（[sharedPlayer]=null，旧行为）：自管 ExoPlayer 随条目重建，dispose 即释放。
 * 两模式共有：AudioAttributes + handleAudioFocus（来电/视频起播自动闪避暂停）、
 * 播完上抛 onEnded（自动连播）、backgroundPause 后台闸。
 *
 * @param gateway 真实 TDLib 条目用来 ensurePlayable / loadAudioCoverBytes；Mock 自带 playUri 可不传
 * @param onEnded 播完回调（外层接自动连播跳页）
 * @param backgroundPause 后台行为=暂停时外层（FeedScreen 感知 onStop）传入 true：
 *   共享模式下仅当本卡仍持有音轨时拉停（防误停别卡已接管的新音频）
 * @param sharedPlayer 外层持有的共享音频播放器；非空即共享模式（见上）
 * @param onPlayingChanged 播放态上报（起播/暂停/恢复），仅在本卡持有音轨时触发
 * @param onShare 分享（R201 起改开分享枢纽小菜单：复制链接/分享到其他应用/分享卡片
 *   三选一；原「点按即复制链接」语义收编进菜单），底部操作排触发
 * @param onShowPanel 长按卡面弹功能面板（ActionSheet 由 FeedScreen 宿主，与视频卡同一块）
 * @param danmakuComments 弹幕文本（纯展示层，滚过画面上 2/3 区域；null = 未加载）
 * @param danmakuEnabled 弹幕总开关（AppPrefs.danmakuEnabled），false 不渲染也不起协程
 * @param plazaWatching / plazaLikes TG 广场全站在看数/点赞数（来源行下方胶囊，0/0 不显示）
 * @param onBack 左上角返回箭头回调（null 不渲染；从搜索/详情跳入音频页时外层传入返回逻辑）
 */
@Composable
internal fun AudioCard(
    item: VideoItem,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    gateway: TdGateway? = null,
    onEnded: () -> Unit = {},
    backgroundPause: Boolean = false,
    // v7：共享播放器 + 播放态上报（迷你播放条/锁屏）；默认值保证旧调用可编译
    sharedPlayer: ExoPlayer? = null,
    onPlayingChanged: (VideoItem, Boolean) -> Unit = { _, _ -> },
    // v6 底部操作排：复用 FeedScreen 与视频卡同一套回调；默认值保证旧调用可编译
    liked: Boolean = false,
    favorited: Boolean = false,
    downloadStatus: DownloadStatus? = null,
    onToggleLike: () -> Unit = {},
    onToggleFavorite: () -> Unit = {},
    onDownload: () -> Unit = {},
    onShare: (VideoItem) -> Unit = {},
    // 打开播放队列（当前音频列表，点哪播哪）；默认空实现保底旧调用
    onOpenQueue: () -> Unit = {},
    // 打开来源频道（音频出处）；默认空实现保底旧调用
    onOpenChannel: () -> Unit = {},
    // 上一曲/下一曲（Feed 翻页）；默认空实现保底旧调用
    onPrevTrack: () -> Unit = {},
    onNextTrack: () -> Unit = {},
    // 长按卡面弹功能面板（ActionSheet 由 FeedScreen 宿主，与视频卡同一块面板）
    onShowPanel: () -> Unit = {},
    // 弹幕层：当前条评论列表（null=未加载）+ 总开关（getComments 对音频消息同样成立）
    danmakuComments: List<String>? = null,
    danmakuEnabled: Boolean = false,
    // TG 广场角标：全站在看数 / 点赞数（0/0 = 不显示）
    plazaWatching: Int = 0,
    plazaLikes: Int = 0,
    // 左上角返回箭头（可选）：外层（FeedScreen）提供返回语义时传入才显示；
    // null = 不渲染返回钮，行为与旧版完全一致
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    // 响应式档位（Responsive.kt）：封面封顶 + 控件/间距缩放走 dp 通道；
    // 文本缩放走 sp 通道（TierFontScale 包裹全卡下发，不动 dp 布局与手势热区）
    val windowSize = rememberWindowSize()
    val uiScale = windowSize.uiScale
    val tierDensity = LocalDensity.current.let {
        Density(it.density, it.fontScale * windowSize.fontScale)
    }
    // R80：自带地址先过收口判定（含 '#'/'?' 的裸路径置 null 走 ensurePlayable 换流地址）
    var playUri by remember(item.key) { mutableStateOf(item.safeInitialPlayUri()) }
    // 共享模式跳回：播放态初值直接对齐播放器实况（卡片重建期间音频一直在放）
    var playing by remember(item.key) {
        mutableStateOf(
            sharedPlayer != null &&
                sharedPlayer.currentMediaItem?.mediaId == item.key &&
                sharedPlayer.isPlaying,
        )
    }
    var buffering by remember(item.key) { mutableStateOf(false) }
    // R375 播放意图态（大圆钮/迷你条暂停-播放钮的动作与呈现源）：onIsPlayingChanged
    // 的 isPlaying 是"真出声"口径（缓冲中 false——封面呼吸/上报外层照用它），按钮
    // 口径换 playWhenReady 意图——缓冲中用户语义"在播"（用户实锤「加载音频也不能
    // 暂停，要等完全加载成功」的病根：旧口径缓冲期点按被判"没在播"→play() 落在
    // 已是 true 的意图上零变化；单源判定见 PlaybackTogglePolicy，ENDED/IDLE 例外
    // 在策略内——播完点播放照旧走先归零再播链）。
    var playIntent by remember(item.key) { mutableStateOf(false) }
    var positionSec by remember(item.key) { mutableIntStateOf(0) }
    // 时长优先用播放器实际值（秒），未就绪时用条目元数据兜底
    var durationSec by remember(item.key) { mutableIntStateOf(item.durationSec) }
    // 倍速档位（卡级状态，不进全局）：1x → 1.5x → 2x 循环
    var speedIndex by remember(item.key) { mutableIntStateOf(0) }

    // 播放器：共享模式直接用外层实例（归 FeedScreen 所有，卡片销毁不释放、音频继续放）；
    // 自管模式照旧随条目 remember 新建（AudioAttributes + handleAudioFocus=true：
    // 来电/其他 App 发声自动闪避暂停，恢复后续播），dispose 即释放
    val player = sharedPlayer ?: remember(item.key) {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC) // 音频内容（视频池用 MOVIE）
                    .build(),
                true, // handleAudioFocus
            )
            .build()
    }
    DisposableEffect(item.key) {
        onDispose {
            // 共享模式只摘监听不释放（播放器是外层的）；自管模式照旧释放
            if (sharedPlayer == null) player.release()
        }
    }

    // 真实 TDLib 条目首次激活时触发下载拿播放地址
    LaunchedEffect(isActive, item.key) {
        if (isActive && playUri == null && gateway != null) {
            playUri = runCatching { gateway.ensurePlayable(item) }.getOrNull()
        }
    }

    // 地址就绪即装填：两模式分叉——
    if (sharedPlayer == null) {
        // 自管模式：同一卡片 playUri 只会从 null → 有值，无需判重（原逻辑）
        LaunchedEffect(playUri) {
            val uri = playUri ?: return@LaunchedEffect
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
        }
    } else {
        // 共享模式：仅激活时接管共享播放器——翻页经过/未激活不装填，
        // 防"滑过音频卡把在播音频切断"；已装载本条（从迷你条跳回）则跳过重载，
        // 进度与播放态无缝续上。mediaId=item.key 是多卡共用一台播放器的归属判据。
        LaunchedEffect(playUri, isActive) {
            val uri = playUri ?: return@LaunchedEffect
            if (!isActive) return@LaunchedEffect
            if (player.currentMediaItem?.mediaId == item.key) return@LaunchedEffect
            player.setMediaItem(MediaItem.Builder().setUri(uri).setMediaId(item.key).build())
            player.prepare()
            // 接管即开播：清掉上一条可能残留的 playWhenReady=false（暂停态被新条继承就不出声了）
            player.playWhenReady = !backgroundPause
        }
    }

    // 激活态驱动播放；失焦暂停。backgroundPause 并入守卫。
    LaunchedEffect(isActive, playUri, backgroundPause) {
        if (sharedPlayer == null) {
            // 自管模式（原逻辑）：后台暂停期间即使翻页重组触发本 effect 重启，
            // 也不会把 playWhenReady 重新拉起（否则退后台滑到音频页会立刻出声）
            player.playWhenReady = isActive && playUri != null && !backgroundPause
        } else {
            // 共享模式：失活不再拉停（离开音频卡继续放，迷你播放条接管）；
            // 只在"本条持有播放器"时动作，防误动别卡已接管的新音轨
            when {
                backgroundPause && player.currentMediaItem?.mediaId == item.key ->
                    player.playWhenReady = false
                !backgroundPause && isActive && playUri != null &&
                    player.currentMediaItem?.mediaId == item.key -> {
                    // 播完后跳回本条：ENDED 态下拉 playWhenReady 不会重播，先归零
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                    player.playWhenReady = true
                }
            }
        }
    }

    // 状态监听：缓冲转圈 / 播完上抛 / 播放态同步按钮图标与封面呼吸 + 上报外层（迷你条数据源）。
    // 共享模式下多卡共用一台播放器：一切状态变更先过归属闸（mediaId==item.key），
    // 音轨被别的卡接管（mediaId 换走）立即复位本地态，不抢新主的展示
    val currentOnEnded by rememberUpdatedState(onEnded)
    val currentItem by rememberUpdatedState(item)
    val currentOnPlayingChanged by rememberUpdatedState(onPlayingChanged)
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            // 共享模式归属闸：自管模式恒 true
            private fun owned() =
                sharedPlayer == null || player.currentMediaItem?.mediaId == currentItem.key

            // R375：意图口径重算——playWhenReady 与 playbackState 任一翻转都要刷新
            //（ENDED/IDLE 例外依赖两者合判，见 PlaybackTogglePolicy）
            private fun syncIntent() {
                playIntent = PlaybackTogglePolicy.userPlayingIntent(player.playWhenReady, player.playbackState)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (!owned()) return
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) currentOnEnded()
                syncIntent()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!owned()) return
                playing = isPlaying
                currentOnPlayingChanged(currentItem, isPlaying)
            }

            // R375：播放意图翻转（用户点按 pause/play、后台/接管闸拉停都走这里）
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!owned()) return
                syncIntent()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (sharedPlayer == null) return
                if (mediaItem?.mediaId == currentItem.key) {
                    // 跳回重挂：listener 只在变化时回调，立即对齐一次实况
                    playing = player.isPlaying
                    buffering = player.playbackState == Player.STATE_BUFFERING
                    syncIntent()
                } else {
                    // 音轨被顶掉：本地态复位
                    playing = false
                    buffering = false
                    playIntent = false
                }
            }
        }
        player.addListener(listener)
        // 共享模式跳回时立即同步一次（初值对齐，不等首个事件）
        if (sharedPlayer != null && player.currentMediaItem?.mediaId == item.key) {
            playing = player.isPlaying
            buffering = player.playbackState == Player.STATE_BUFFERING
            playIntent = PlaybackTogglePolicy.userPlayingIntent(player.playWhenReady, player.playbackState)
        }
        onDispose { player.removeListener(listener) }
    }

    // 位置/时长轮询（0.5s 足够文字刷新；进度区自己有 120ms 轮询）。
    // 共享模式仅在本卡持有音轨时采样——别卡接管期间不展示它的进度
    LaunchedEffect(player) {
        while (true) {
            if (sharedPlayer == null || player.currentMediaItem?.mediaId == currentItem.key) {
                positionSec = (player.currentPosition / 1000L).toInt().coerceAtLeast(0)
                val d = player.duration
                if (d > 0) durationSec = (d / 1000L).toInt()
            }
            delay(500)
        }
    }

    // ---------- 专辑封面：高清 → minithumbnail → null（渐变音符占位） ----------
    // 初始值先解码 minithumbnail（糊但立即可见），HD 到位后无缝替换
    // R170 解码挪 IO（榜单 #6 输入响应家族，与 VideoPage 封面解码同口径）：
    // 旧写法 withContext(IO) 只包了网关下载，decodeCoverBytes（高清专辑封面可达
    // 数百 KB）仍在主调度器上跑——produceState 协程=组合作用域=主线程
    val coverBitmap by produceState<ImageBitmap?>(
        initialValue = decodeCoverBytes(item.coverBytes),
        item.key,
    ) {
        val lib = gateway as? TdLibGateway ?: return@produceState
        val bytes = withContext(Dispatchers.IO) {
            runCatching { lib.loadAudioCoverBytes(item) }.getOrNull()
        } ?: return@produceState
        withContext(Dispatchers.IO) { decodeCoverBytes(bytes) }?.let { value = it }
    }

    // 语音波形解码：每字节低 5bit + 高 5bit 各一个 0..31 幅度，归一化到 0..1。
    // 仅语音消息（MessageVoiceNote）有；空表 = 走细轨进度条
    val waveBars = remember(item.key) { decodeWaveform(item.waveBytes) }

    // 委托态不能智能转换：本组合内提为本地 val，封面/底衬共用同一份
    val cover = coverBitmap

    // 封面呼吸：播放中 1.0↔1.02，600ms 半程（1200ms 一周），暂停即复位
    // （连续呼吸周期=物理非转场——MotionTokensPolicyTest 豁免清单登记）
    val breathTransition = rememberInfiniteTransition(label = "coverBreath")
    val breathScale by breathTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )
    val coverScale = if (playing) breathScale else 1f

    // 标题：结构化 audioTitle（Audio.title）→ caption 首行 → 占位文案
    val title = item.audioTitle.ifBlank {
        item.caption.lineSequence().firstOrNull()?.trim().orEmpty()
    }.ifBlank { "音频消息" }
    // 副标题：歌手 · 频道（次要色）
    val subtitle = listOf(item.audioPerformer, item.channelTitle)
        .filter { it.isNotBlank() }
        .joinToString(" · ")

    // 长按卡面 = 弹功能面板（rememberUpdatedState 防 pointerInput 抓旧闭包）
    val currentOnShowPanel = rememberUpdatedState(newValue = onShowPanel)

    Box(
        modifier
            .fillMaxSize()
            .background(TgFeedTokens.InkBlack)
            // 长按弹功能面板：按钮/进度条等子级消费了 down 的位置不会触发
            // （detectTapGestures 默认只认未消费的按下），不挡任何现有交互
            .pointerInput(item.key) {
                detectTapGestures(
                    onLongPress = { currentOnShowPanel.value() },
                )
            },
    ) {
        // 档位文本缩放：标题/歌手/时间/芯片等 sp 文本统一随档位放大，dp 布局与热区不变
        TierFontScale(tierDensity) {
        // 模糊底衬：有封面时铺满全屏（1.25x + 30dp 高斯模糊，照 VideoPage 基准），
        // 让封面色渗进背景，音乐 App 氛围感的主要来源。
        // R162 零遮罩：旧版这里再叠 45% 黑纱（R6 抄的 VideoPage 旧模式，视频侧 R83
        // 起早已删黑纱）——深灰透明遮罩正是用户刚报的问题，本底衬只模糊不压暗，
        // 标题/歌手/时间文字可读性由 withOverlayShadow 描边承担（信息区已有）
        cover?.let { bg ->
            Image(
                bitmap = bg,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = 1.25f; scaleY = 1.25f }
                    .blur(30.dp),
                contentScale = ContentScale.Crop,
            )
        }

        // 弹幕层：评论滚过画面上 2/3 区域（纯展示不挡手势；压在封面/控件之下，
        // 与视频卡同一挂载语义——弹幕是内容氛围，不遮标题与操作排）
        DanmakuOverlay(
            comments = danmakuComments ?: emptyList(),
            enabled = danmakuEnabled && isActive,
            modifier = Modifier.fillMaxSize(),
        )

        // 横屏（宽>高）判定：420dp 级封面在 480dp 高视口里会把标题/作者行压出屏底、
        // 控制排首屏完全不可见（横屏走查实锤）；横屏改「左封面、右信息+控制」分栏，竖屏原样
        val isLandscape = windowSize == WindowSize.PhoneLandscape ||
            windowSize == WindowSize.TabletLandscape

        // 封面区抽成 lambda：竖屏（宽 78% 居中）与横屏（视口高约 60% 见方、居左栏）
        // 共用同一份封面实现（呼吸缩放 / 缓冲转圈 / 渐变占位 / 装饰波形），防两份实现漂移
        val coverContent: @Composable (Modifier) -> Unit = { coverModifier ->
            // ---------- 大专辑封面：圆角 16dp 方图 ----------
            Box(
                coverModifier
                    .graphicsLayer { scaleX = coverScale; scaleY = coverScale }
                    .overlayIconShadow()
                    .clip(RoundedCornerShape(16.dp)),
            ) {
                if (cover != null) {
                    Image(
                        bitmap = cover,
                        contentDescription = "专辑封面",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    // 语音/无封面占位：渐变底 + 居中音符 + 装饰波形
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
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.92f),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(96.dp),
                        )
                        if (waveBars.isNotEmpty()) {
                            WaveBars(
                                bars = waveBars,
                                progress = 0f, // 封面区装饰用，不染进度
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 14.dp)
                                    .height(36.dp),
                                playedColor = Color.White.copy(alpha = 0.85f),
                                trackColor = Color.White.copy(alpha = 0.35f),
                            )
                        }
                    }
                }
                // 缓冲转圈压在封面中央（起播等待的焦虑治理）。
                // R162 零遮罩：旧版 30% 黑压暗与视频页加载态黑罩同病（0b19725 已裁决删除），
                // 转圈 40dp/TextHigh 直出，封面原色不被染色。
                // R375：转圈闸并入意图口径（buffering && playIntent）——用户在缓冲窗内
                // 按下暂停（意图翻 false、ExoPlayer 缓冲继续填）时转圈收起、大圆钮呈
                // 播放态，与视频页「缓冲指示停+⏸ 呈现」同口径（旧无条件转圈会把暂停
                // 态误读成"还在加载"）。
                if (buffering && playIntent) {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        // 统一规格 40dp/3dp/TextHigh+20% 轨道色：M3 spinner 在动画比例=0（首帧）
                        // 退化成 0° 弧+圆头端帽的小圆点，trackColor 让退化态也显示完整圆环
                        CircularProgressIndicator(
                            modifier = Modifier.size(40.dp),
                            color = TgFeedTokens.TextHigh,
                            strokeWidth = 3.dp,
                            trackColor = TgFeedTokens.TextHigh.copy(alpha = 0.2f),
                        )
                    }
                }
            }
        }

        // 封面以下的信息 + 控制区（标题/歌手/来源/广场角标/进度/控制排/操作排）
        // 抽成 lambda：竖屏接在封面之下，横屏放右栏，两个方向共用同一份
        val infoAndControls: @Composable ColumnScope.() -> Unit = {
            Spacer(Modifier.height(22.dp * uiScale))

            // ---------- 标题 + 歌手/频道 ----------
            Text(
                title,
                color = TgFeedTokens.TextHigh,
                style = MaterialTheme.typography.headlineSmall.withOverlayShadow(),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(6.dp * uiScale))
                Text(
                    subtitle,
                    color = TgFeedTokens.TextMedium,
                    style = MaterialTheme.typography.bodyMedium.withOverlayShadow(),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // ---------- 来源出处（用户反馈"看不见音频的具体来源"）----------
            // 频道头像 + 频道名 + 跳转箭头，点击进该频道
            Spacer(Modifier.height(10.dp * uiScale))
            Row(
                Modifier
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable(onClick = onOpenChannel)
                    .padding(horizontal = 12.dp * uiScale, vertical = 6.dp * uiScale),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val avatar = produceState<ImageBitmap?>(null, item.chatId) {
                    val lib = gateway as? dev.tgfeed.core.td.TdLibGateway
                    value = withContext(Dispatchers.IO) {
                        lib?.getChannelAvatar(item.chatId)?.let { bytes ->
                            runCatching {
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
                            }.getOrNull()
                        }
                    }
                }.value
                if (avatar != null) {
                    Image(
                        bitmap = avatar,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    "来自 ${item.channelTitle}",
                    color = TgFeedTokens.TextMedium,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "查看频道",
                    tint = TgFeedTokens.TextLow,
                    modifier = Modifier.size(14.dp),
                )
            }

            // ---------- 广场角标："👥 N 在看 · ❤️ M"胶囊（与视频卡 BottomInfoPanel 同口径同样式）----------
            // R162 收敛：样式本体收编 OverlayInfoChip（黑 α0.35 + 发丝描边，一处改全局）
            if (plazaWatching > 0 || plazaLikes > 0) {
                Spacer(Modifier.height(8.dp * uiScale))
                OverlayInfoChip(
                    text = buildString {
                        if (plazaWatching > 0) append("👥 $plazaWatching 在看")
                        if (plazaWatching > 0 && plazaLikes > 0) append(" · ")
                        if (plazaLikes > 0) append("❤️ $plazaLikes")
                    },
                )
            }

            Spacer(Modifier.height(26.dp * uiScale))

            // ---------- 进度区：语音走波形柱（进度染色），音乐走细轨+圆点；均可拖可点 ----------
            AudioSeekArea(
                player = player,
                waveBars = waveBars,
                // 共享模式传归属 key：本卡不持有音轨时进度区只读（防误拖别卡的音轨）
                ownerKey = if (sharedPlayer != null) item.key else null,
                modifier = Modifier.fillMaxWidth(),
                // R166 duration 兜底：音频/语音条目元数据时长恒有（GatewayFeedFetch
                // 的 a.duration / vn.duration 口径），波形 seek 不再被 if(d>0) 吞
                fallbackDurationMs = item.durationSec * 1000L,
            )
            // 两端时间：已播 / 总时长（等宽数字防抖动）
            Row(Modifier.fillMaxWidth()) {
                Text(
                    formatDuration(positionSec),
                    color = TgFeedTokens.TextMedium,
                    style = MaterialTheme.typography.labelSmall
                        .copy(fontFeatureSettings = "tnum")
                        .withOverlayShadow(),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatDuration(durationSec),
                    color = TgFeedTokens.TextMedium,
                    style = MaterialTheme.typography.labelSmall
                        .copy(fontFeatureSettings = "tnum")
                        .withOverlayShadow(),
                )
            }

            Spacer(Modifier.height(18.dp * uiScale))

            // ---------- 控制排：⏪15s | ▶大圆钮 | ⏩15s | 倍速 ----------
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 上一曲（Feed 前翻一页）
                IconButton(onClick = onPrevTrack) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = "上一曲",
                        tint = TgFeedTokens.TextHigh,
                        modifier = Modifier.size(28.dp * uiScale),
                    )
                }
                // 快退 15s：Material 标准弧线钮（Replay 弧 + 中心秒数，Replay10/30 同族造型）
                SeekJumpButton(
                    icon = Icons.Outlined.Replay,
                    label = "15",
                    contentDescription = "快退 15 秒",
                    enabled = playUri != null,
                    onClick = {
                        player.seekTo((player.currentPosition - 15_000L).coerceAtLeast(0L))
                    },
                )
                // 播放/暂停大圆钮：主色实心圆 + 白图标
                Box(
                    Modifier
                        .size(76.dp * uiScale)
                        .overlayIconShadow()
                        .background(TgFeedTokens.TgBlue, CircleShape)
                        // R375：摘掉 !buffering 闸——旧口径把缓冲窗打成"不可点"
                        //（用户实锤「加载音频也不能暂停，要等完全加载成功才能暂停」
                        // 的第二病根，与 isPlaying 判定误判同刀治）：缓冲中意图在，
                        // 点按=暂停（缓冲继续填、就绪不自动出声）；缓冲指示的收
                        // 起与否交给下方意图口径闸，不再用 enabled 吞点按。
                        .clickable(enabled = playUri != null) {
                            // 播完态点播放先归零（ENDED 态下拉 playWhenReady 不会
                            // 重播）——R375 换意图口径后此链原样：ENDED 在策略内不
                            // 算在播，恒落归零分支，行为与旧 isPlaying 口径逐字一致
                            if (!playIntent && player.playbackState == Player.STATE_ENDED) {
                                player.seekTo(0)
                            }
                            if (playIntent) player.pause() else player.play()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (playIntent) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playIntent) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(42.dp * uiScale),
                    )
                }
                // 快进 15s：AU-D 修复——Material 图标集没有 Forward15（只有 Forward5/10/30），
                // 旧版用 Icons.Outlined.Forward（实心右三角）叠数字，造型怪异且与快退不对称；
                // Material 官方 Forward10/30 本身就是 Replay 弧线的水平镜像，这里做同样处理：
                // 标准 Replay 图标镜像 + 中心 15，与快退钮成对、与底部操作排同为 Outlined 风格
                SeekJumpButton(
                    icon = Icons.Outlined.Replay,
                    mirror = true,
                    label = "15",
                    contentDescription = "快进 15 秒",
                    enabled = playUri != null,
                    onClick = {
                        val d = player.duration
                        val target = player.currentPosition + 15_000L
                        player.seekTo(if (d > 0) target.coerceAtMost(d) else target)
                    },
                )
                // 下一曲（Feed 后翻一页）
                IconButton(onClick = onNextTrack) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = "下一曲",
                        tint = TgFeedTokens.TextHigh,
                        modifier = Modifier.size(28.dp * uiScale),
                    )
                }
                // 倍速循环：1x → 1.5x → 2x → 1x（胶囊文字钮，即点即生效）
                val speedSteps = listOf(1f, 1.5f, 2f)
                Box(
                    Modifier
                        // B10 修复：胶囊是控制排最后测量的子项，Row 只分给它剩余宽度，
                        // 窄屏/大字体档位下被挤成窄条导致 "1x" 折成两行；
                        // 保底最小宽度，让胶囊始终按内容自适应（内容 + 水平 padding）
                        .defaultMinSize(minWidth = 52.dp * uiScale)
                        .border(
                            1.dp,
                            Color.White.copy(alpha = if (speedIndex == 0) 0.30f else 0.85f),
                            RoundedCornerShape(percent = 50),
                        )
                        .clickable(enabled = playUri != null) {
                            speedIndex = (speedIndex + 1) % speedSteps.size
                            player.setPlaybackSpeed(speedSteps[speedIndex])
                        }
                        .padding(horizontal = 12.dp * uiScale, vertical = 6.dp * uiScale),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when (speedIndex) {
                            1 -> "1.5x"
                            2 -> "2x"
                            else -> "1x"
                        },
                        color = if (speedIndex == 0) TgFeedTokens.TextMedium else TgFeedTokens.TextHigh,
                        // B10 修复：字号降到 13sp + 强制单行（softWrap=false），
                        // 1.5x/2x 等更长文本也绝不折行
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }

            Spacer(Modifier.height(22.dp * uiScale))

            // ---------- 底部操作排：队列 / 点赞 / 收藏 / 下载 / 分享 ----------
            // 队列在最左（用户要求：音频的播放列表入口挪到左侧）
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AudioActionIcon(
                    icon = Icons.Outlined.QueueMusic,
                    tint = TgFeedTokens.TextHigh,
                    contentDescription = "播放队列",
                    onClick = onOpenQueue,
                )
                AudioActionIcon(
                    icon = if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    tint = if (liked) LikeRed else TgFeedTokens.TextHigh,
                    contentDescription = if (liked) "已点赞" else "点赞",
                    onClick = onToggleLike,
                )
                AudioActionIcon(
                    icon = if (favorited) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    tint = if (favorited) FavYellow else TgFeedTokens.TextHigh,
                    contentDescription = if (favorited) "已收藏" else "收藏",
                    onClick = onToggleFavorite,
                )
                // 下载按状态换图标：未下载 ↓ / 下载中转圈 / 已完成 ✓ / 失败 ⚠（点按重试）
                when (downloadStatus) {
                    is DownloadStatus.Running -> Box(
                        Modifier.size(TgFeedTokens.TouchMin * uiScale),
                        contentAlignment = Alignment.Center,
                    ) {
                        // 统一规格 40dp/3dp/TextHigh+20% 轨道色（与封面缓冲转圈一致）：
                        // M3 spinner 首帧退化成小圆点，trackColor 保证退化态显示完整圆环
                        CircularProgressIndicator(
                            modifier = Modifier.size(40.dp),
                            color = TgFeedTokens.TextHigh,
                            strokeWidth = 3.dp,
                            trackColor = TgFeedTokens.TextHigh.copy(alpha = 0.2f),
                        )
                    }
                    is DownloadStatus.Done -> AudioActionIcon(
                        Icons.Outlined.CheckCircle, TgFeedTokens.SuccessGreen, "已下载", onDownload,
                    )
                    is DownloadStatus.Failed -> AudioActionIcon(
                        Icons.Outlined.Warning, TgFeedTokens.WarningRed, "下载失败，点按重试", onDownload,
                    )
                    else -> AudioActionIcon(
                        Icons.Outlined.FileDownload, TgFeedTokens.TextHigh, "下载", onDownload,
                    )
                }
                AudioActionIcon(
                    icon = Icons.Outlined.IosShare,
                    tint = TgFeedTokens.TextHigh,
                    contentDescription = "分享",
                    onClick = { onShare(item) },
                )
            }
        }

        if (isLandscape) {
            // ---------- 横屏：左封面、右信息+控制 分栏 ----------
            Row(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(bottom = LocalBottomBarInset.current) // R173 底栏让位：内容列随底栏收窄（清屏/搜索音频时归零）
                    .padding(horizontal = 28.dp * uiScale),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 封面取视口高约 60% 见方：横屏矮视口下不再顶破首屏，标题/控制排首屏可见
                val coverSide = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
                coverContent(Modifier.size(coverSide))
                Spacer(Modifier.width(28.dp * uiScale))
                // 右栏：放得下时垂直居中，极端矮屏（或字体档位拉大）时仍可滚动兜底
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    infoAndControls()
                }
            }
        } else {
            // ---------- 竖屏：原垂直布局不变（封面宽 78% 居中，整列可滚动）----------
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(bottom = LocalBottomBarInset.current) // R173 底栏让位：内容列随底栏收窄（清屏/搜索音频时归零）
                    // 竖屏/矮屏可滚动：封面 78%×1:1 + 控件区总高可能超屏，
                    // 不加滚动整排控制会被裁出屏（响应式审查实锤）
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp * uiScale),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))

                // 宽 78% 居中；平板封顶：0.78×放宽后的内容区会爆出 600dp+ 巨图，
                // 竖屏一页放不下；手机 0.78w 远低于封顶值、行为不变
                // （封顶值见 Responsive.kt audioCoverMax）
                coverContent(
                    Modifier
                        .fillMaxWidth(0.78f)
                        .widthIn(max = windowSize.audioCoverMax)
                        .aspectRatio(1f),
                )

                infoAndControls()

                Spacer(Modifier.weight(1f))
            }
        }
        } // TierFontScale 收尾（档位文本缩放包裹，开头见根 Box 首行）

        // 左上角返回箭头：外层传入 onBack（搜索/详情跳入音频页的返回语义）才显示，
        // null 时零开销零占位；白色 90% 与封面上方内容保持可读性
        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 4.dp, top = 4.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(26.dp * uiScale),
                )
            }
        }
    }
}

/**
 * 档位文本缩放包裹层（与 VideoPage 同款，file-private）：把 LocalDensity 的 fontScale
 * 乘上档位系数后再下发，只放大 sp 文本（标题/歌手/时间/芯片）；
 * dp 布局、手势热区、封面尺寸不受影响。
 */
@Composable
private fun TierFontScale(density: Density, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDensity provides density, content = content)
}

/** 解码封面字节为 ImageBitmap；失败/空返回 null */
private fun decodeCoverBytes(bytes: ByteArray?): ImageBitmap? {
    if (bytes == null || bytes.isEmpty()) return null
    return runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

/**
 * TG 语音波形解码（VoiceNote.waveform，javap 实证 byte[]）：
 * 每字节低 5bit = 偶数采样、高 5bit = 奇数采样，各 0..31，归一化到 0..1。
 * 空/ null 输入返回空表（调用方回落细轨进度条）。
 */
private fun decodeWaveform(bytes: ByteArray?): List<Float> {
    if (bytes == null || bytes.isEmpty()) return emptyList()
    val out = ArrayList<Float>(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt()
        out += (v and 31) / 31f
        out += ((v ushr 5) and 31) / 31f
    }
    return out
}

/**
 * ±15s 跳转钮：48dp 圆形描边钮，标准弧线图标（Icons.Outlined.Replay）垫底 + 中心秒数数字。
 * [mirror]=true 时图标水平镜像——Material 官方 Forward10/Forward30 即 Replay 的镜像形态，
 * 用同一标准图标保证快退/快进成对且与底部操作排（Outlined 系列）同一视觉风格。
 */
@Composable
private fun SeekJumpButton(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    mirror: Boolean = false,
) {
    // 档位缩放（平板 ±15s 钮跟着整体放大）
    val uiScale = rememberWindowSize().uiScale
    Box(
        Modifier
            .size(52.dp * uiScale)
            .border(
                1.dp,
                Color.White.copy(alpha = if (enabled) 0.35f else 0.15f),
                CircleShape,
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = if (enabled) 0.85f else 0.35f),
            modifier = Modifier
                .size(30.dp * uiScale)
                // 镜像只作用于图形层，不产生额外布局/重排成本
                .graphicsLayer { scaleX = if (mirror) -1f else 1f },
        )
        // 数字压图标空心区；Replay 弧线中心天然留白
        Text(
            label,
            color = Color.White.copy(alpha = if (enabled) 0.95f else 0.35f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** 底部操作排单钮：图标 + 最小触摸热区（40dp），样式与 ActionRail 对齐
 *  R225 横屏可伸展档：触区提至政策 48dp 铁律（SpaceEvenly 排布吸收增量，
 *  行宽零风险；竖屏/矮横屏 null → 40dp×uiScale 现状零变化） */
@Composable
private fun AudioActionIcon(
    icon: ImageVector,
    tint: Color,
    contentDescription: String,
    onClick: () -> Unit,
) {
    // 档位缩放：触摸热区与图标随档位（平板操作排不"迷你"）
    val windowSize = rememberWindowSize()
    val uiScale = windowSize.uiScale
    val touch = maxOf(
        TgFeedTokens.TouchMin * uiScale,
        landscapeFeedChromeOrNull(
            windowSize,
            LocalConfiguration.current.screenHeightDp,
        )?.railTouchMin ?: 0.dp,
    )
    Box(
        Modifier
            .size(touch)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(28.dp * uiScale),
        )
    }
}

/**
 * 音频进度区：语音（waveBars 非空）画波形柱、音乐画细轨+圆点手柄；
 * 统一手势：横拖 seek（过 slop 进拖拽态，松手按比例 seekTo）、点按跳哪算哪。
 * 手势实现复用 PlaybackProgressLine 的成熟模式（down 立即消费防穿透、
 * 只认横向 slop 不抢竖滑翻页、协程取消必退拖拽态）。
 * [ownerKey] 非空（共享播放器模式）时先过归属闸：本卡不持有音轨期间不采样、不响应手势。
 */
@Composable
private fun AudioSeekArea(
    player: ExoPlayer,
    waveBars: List<Float>,
    modifier: Modifier = Modifier,
    ownerKey: String? = null,
    // R166 duration 兜底（毫秒）：player.duration 未就绪时点按/拖拽 seek 不再被
    // if(d>0) 吞掉（同 PlaybackProgressLine 修法，调用方传条目元数据时长）
    fallbackDurationMs: Long = 0L,
) {
    // 档位缩放：热区高度/轨道粗细/手柄尺寸随档位（拖拽判定不受影响，只是视觉加粗）
    val uiScale = rememberWindowSize().uiScale
    var progress by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }
    var dragDurationMs by remember { mutableLongStateOf(0L) }
    // 容器像素宽：细轨模式定位圆点手柄用
    var widthPx by remember { mutableIntStateOf(0) }

    // 共享播放器归属闸：null（自管）恒 true
    fun owned() = ownerKey == null || player.currentMediaItem?.mediaId == ownerKey

    // 播放位置轮询（120ms：波形染色/手柄移动比文字更吃帧率）；拖拽时停轮询由拖拽比例接管
    LaunchedEffect(player, dragging) {
        if (dragging) return@LaunchedEffect
        while (true) {
            val d = player.duration
            progress = if (owned() && d > 0) {
                (player.currentPosition.toFloat() / d.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            delay(120)
        }
    }

    val shown = if (dragging) dragFraction else progress

    Box(
        modifier
            // 波形区高一点才有柱状观感；细轨模式也用同高热区（好拖）
            .height((if (waveBars.isNotEmpty()) 56.dp else 28.dp) * uiScale)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(player) {
                // R164b：统一手势引擎（SeekGestureEngine.kt 共享件）——修旧两段式的
                // 「点按链死路/被抢误判」与 Compose 1.7 漏出（卡面长按弹面板/
                // 点按叠动作），实锤与语义见引擎类注释；音频卡侧只留 owned 归属闸
                // 与 duration 兜底（R166-F4）
                seekGestureLoop(
                    onDragStart = { f ->
                        dragging = true
                        dragFraction = f
                        // R166：duration 未就绪用元数据兜底，拖拽目标不再为 0
                        dragDurationMs = player.duration.takeIf { it > 0 } ?: fallbackDurationMs
                    },
                    onDragTo = { f -> dragFraction = f },
                    onOutcome = { outcome, f ->
                        if (owned()) {
                            when (outcome) {
                                SeekGestureOutcome.DRAG_SEEK -> {
                                    val d = dragDurationMs
                                    if (d > 0) player.seekTo((d * f).roundToLong())
                                }
                                // R166：duration 未就绪用元数据兜底——点按跳转不再被吞
                                SeekGestureOutcome.TAP_SEEK -> {
                                    val d = player.duration.takeIf { it > 0 } ?: fallbackDurationMs
                                    if (d > 0) player.seekTo((d * f).roundToLong())
                                }
                                SeekGestureOutcome.ABANDONED -> Unit
                            }
                        }
                    },
                    // 协程被取消（翻页/dispose）也必须退出拖拽态
                    onCleanup = { dragging = false },
                )
            },
    ) {
        if (waveBars.isNotEmpty()) {
            // 语音波形：已播部分主色、未播白色低透明；拖拽中跟拖拽比例染色
            WaveBars(
                bars = waveBars,
                progress = shown,
                modifier = Modifier.fillMaxSize(),
                playedColor = TgFeedTokens.TgBlue,
                trackColor = Color.White.copy(alpha = 0.28f),
            )
        } else {
            // 音乐细轨：2dp 轨道（拖拽加粗到 4dp）+ 圆点手柄
            // 进度轨粗细跟档位：平板 2dp 发丝线在远处看不见，加粗到 ~2.3dp
            val lineHeight = (if (dragging) 4.dp else 2.dp) * uiScale
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(lineHeight)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.White.copy(alpha = 0.18f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(shown)
                        .height(lineHeight)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(TgFeedTokens.TgBlue),
                )
            }
            // 圆点手柄：常显（音乐 App 语义），拖拽时略放大；
            // offset 用测量到的容器宽定位（两端各收半个手柄宽，防溢出裁剪）
            val thumbSize = (if (dragging) 14.dp else 10.dp) * uiScale // 手柄随档位
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset {
                        val thumbPx = thumbSize.toPx()
                        val x = (widthPx * shown - thumbPx / 2f)
                            .roundToInt()
                            .coerceIn(0, (widthPx - thumbPx).roundToInt().coerceAtLeast(0))
                        IntOffset(x, 0)
                    }
                    .size(thumbSize)
                    .overlayIconShadow()
                    .background(Color.White, CircleShape),
            )
        }
    }
}

/**
 * 波形柱绘制：把 TG 5bit 幅度序列重采样到"屏宽 / (柱宽+间距)"根数，
 * 每柱圆角胶囊垂直居中；progress 左侧染 playedColor，右侧 trackColor。
 * 幅度钳到 [0.08, 1]：静音段也留一点柱高，波形不断裂。
 */
@Composable
private fun WaveBars(
    bars: List<Float>,
    progress: Float,
    modifier: Modifier = Modifier,
    playedColor: Color,
    trackColor: Color,
) {
    // 柱宽/间距随档位缩放（平板波形不稀疏、进度染色同步变粗）
    val uiScale = rememberWindowSize().uiScale
    Canvas(modifier) {
        if (bars.isEmpty() || size.width <= 0f || size.height <= 0f) return@Canvas
        val barW = (3.dp * uiScale).toPx()
        val gap = (2.dp * uiScale).toPx()
        val count = (size.width / (barW + gap)).toInt().coerceAtLeast(1)
        val p = progress.coerceIn(0f, 1f)
        for (i in 0 until count) {
            val srcIdx = (i.toFloat() / count * bars.size).toInt().coerceIn(0, bars.lastIndex)
            val amp = bars[srcIdx].coerceIn(0.08f, 1f)
            val h = size.height * amp
            val x = i * (barW + gap)
            drawRoundRect(
                color = if (i.toFloat() / count <= p) playedColor else trackColor,
                topLeft = Offset(x, (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}

/**
 * 迷你播放条（音乐播放器级）：音频在播且当前页不是该音频卡时，FeedScreen 底部常驻此条。
 * 布局照网易云/Spotify：封面小图 + 标题/歌手·频道 + 播放/暂停 + 队列入口，底部 2dp 进度细线。
 * 点条体跳回该音频条目（[onClick]，外层接 pagerState.scrollToPage）；
 * 播放/暂停直接操作共享播放器（[player]）——音频卡销毁后此条即唯一控制口。
 * R375：[playing]（外层 isPlaying 真出声上报）不再驱动播放/暂停钮——钮的判定与
 * 呈现换播放意图口径（本条自挂监听，见函数体 R375 注释；参数保留兼容既有调用面，
 * FeedScreen 侧上报链不动）。缓冲窗内点条上暂停/播放自此生效（用户实锤修复）。
 */
@Composable
internal fun MiniAudioPlayerBar(
    item: VideoItem,
    playing: Boolean,
    player: ExoPlayer,
    onClick: () -> Unit,
    onQueueClick: () -> Unit,
    // 关闭迷你条（停播 + 条消失）：用户反馈"不想听关不了"
    onClose: () -> Unit = {},
    // R299 B1：听单计数角标（>0 时队列图标右上挂「N」——队列化后迷你条同步显示
    // 「队列 N 首」，design §2.1 连播语义行）
    queueSize: Int = 0,
    modifier: Modifier = Modifier,
) {
    // 档位缩放：封面/图标/进度细线随档位放大（平板迷你条不"迷你"）
    val uiScale = rememberWindowSize().uiScale
    // 进度细线轮询（250ms：细线级流畅足够）
    var progress by remember { mutableStateOf(0f) }
    LaunchedEffect(player) {
        while (true) {
            val d = player.duration
            progress = if (d > 0) {
                (player.currentPosition.toFloat() / d.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            delay(250)
        }
    }
    // R375 播放意图态：外层传入的 playing（isPlaying 真出声口径）在缓冲期为 false，
    // 钮的呈现与动作会退回旧病（缓冲窗内点按零变化）。迷你条常驻期音频卡可能已
    // 销毁（卡片侧监听器不可复用），本条自挂同款意图监听（listener 自加自删，
    // 仓内统一模式）；单源判定见 PlaybackTogglePolicy（ENDED/IDLE 例外：播完点
    // 播放先归零再播，与音频卡大圆钮同一链）。
    var playIntent by remember {
        mutableStateOf(PlaybackTogglePolicy.userPlayingIntent(player.playWhenReady, player.playbackState))
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                playIntent = PlaybackTogglePolicy.userPlayingIntent(playWhenReady, player.playbackState)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                playIntent = PlaybackTogglePolicy.userPlayingIntent(player.playWhenReady, playbackState)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // 封面小图：minithumbnail 即时解码；无封面回落渐变音符（与音频卡占位同一语言）
    val cover = remember(item.key) { decodeCoverBytes(item.coverBytes) }
    val title = item.audioTitle.ifBlank {
        item.caption.lineSequence().firstOrNull()?.trim().orEmpty()
    }.ifBlank { "音频消息" }
    val subtitle = listOf(item.audioPerformer, item.channelTitle)
        .filter { it.isNotBlank() }
        .joinToString(" · ")

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
            .background(TgFeedTokens.SurfaceHigh.copy(alpha = 0.96f))
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 队列入口挪到最左（用户要求）：弹当前列表音频条目 sheet（AudioQueueSheet）
            Box(
                Modifier
                    .size(TgFeedTokens.TouchMin * uiScale)
                    .clickable(onClick = onQueueClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.QueueMusic,
                    contentDescription = "播放队列",
                    tint = TgFeedTokens.TextHigh,
                    modifier = Modifier.size(24.dp * uiScale),
                )
                // R299 B1 听单计数角标（右上小圆点+数字；0=不渲染）
                if (queueSize > 0) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 6.dp, y = (-4).dp)
                            .defaultMinSize(minWidth = 16.dp)
                            .clip(RoundedCornerShape(50))
                            .background(TgFeedTokens.TgBlue)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "$queueSize",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(40.dp * uiScale)
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
                    .background(TgFeedTokens.Surface),
            ) {
                if (cover != null) {
                    Image(
                        bitmap = cover,
                        contentDescription = "专辑封面",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = TgFeedTokens.TgBlue,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(22.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = TgFeedTokens.TextHigh,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        color = TgFeedTokens.TextMedium,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 播放/暂停：直接操作共享播放器（播完态先归零再播，与音频卡大圆钮同一处理）
            // R375：判定与呈现换意图口径（playIntent，本条自挂监听）——缓冲窗内
            // 点按=暂停生效；参数 playing（外层 isPlaying 真出声上报）不再驱动钮
            Box(
                Modifier
                    .size(TgFeedTokens.TouchMin)
                    .clickable {
                        if (!playIntent && player.playbackState == Player.STATE_ENDED) {
                            player.seekTo(0)
                        }
                        if (playIntent) player.pause() else player.play()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playIntent) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playIntent) "暂停" else "播放",
                    tint = TgFeedTokens.TextHigh,
                    modifier = Modifier.size(26.dp * uiScale),
                )
            }
            // 关闭迷你条：停播 + 条消失（不想听时的一键清场）
            Box(
                Modifier
                    .size(TgFeedTokens.TouchMin * uiScale)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = TgFeedTokens.TextLow,
                    modifier = Modifier.size(20.dp * uiScale),
                )
            }
        }
        // 进度细线：贴条底 2dp，已播主色
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp * uiScale)
                .background(Color.White.copy(alpha = 0.15f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .height(2.dp * uiScale)
                    .background(TgFeedTokens.TgBlue),
            )
        }
    }
}

/**
 * 播放队列面板（迷你条右侧"队列"图标触发）——R299 B1 听单一等化升级：
 *  - 「听单」段：持久化可编辑队列（进程级 ListenQueueController 本体）——
 *    点行跳播、行尾移除、头部「存为片单」（PlaylistMeta 底座）/「清空」；
 *    在播队列条目主色高亮 + 音符标。
 *  - 「当前列表」段：现列表音频投影打底（R295 前的旧队列语义保留）——点行
 *    Feed 定位跳播；行尾「下一首」「入听单」两枚快捷钮（ActionSheet 同族入口）。
 * 面板样式对齐 FilterSheet/ActionSheet（深色、顶圆角、跳过半展开）。
 */
@OptIn(ExperimentalMaterial3Api::class) // 与 FilterSheet 一致：ModalBottomSheet 已转正，保留注解兼容旧版
@Composable
internal fun AudioQueueSheet(
    audioItems: List<VideoItem>,
    playingKey: String?,
    playing: Boolean,
    onJump: (VideoItem) -> Unit,
    onDismiss: () -> Unit,
    // ---- R299 B1 听单段（默认空=旧调用零变化；FeedScreen 已全接线） ----
    queueItems: List<VideoItem> = emptyList(),
    queuePlayingKey: String? = null,
    onQueueJump: (VideoItem) -> Unit = {},
    onQueueRemove: (VideoItem) -> Unit = {},
    onQueueClear: () -> Unit = {},
    onQueueSaveAsPlaylist: () -> Unit = {},
    onPlayNext: (VideoItem) -> Unit = {},
    onEnqueue: (VideoItem) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // 关窗：先播收起动画，动画结束后再真正移除面板（同 FilterSheet）
    val dismiss: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetMaxWidth = SheetMaxWidth, // R171 收敛：Responsive.SheetMaxWidth（平板限宽居中，同 FilterSheet）
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = TgFeedTokens.RadiusLg, topEnd = TgFeedTokens.RadiusLg),
        containerColor = TgFeedTokens.Surface,
        contentColor = TgFeedTokens.TextHigh,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                // R226 sheet 族漏网补齐（88fcb74 同款钥匙）：播放队列 sheet 从迷你条
                // 右侧队列图标就地开——Feed 页 dock 可见域，内容消费 inset 抬到栏上沿
                .padding(bottom = LocalBottomBarInset.current + 16.dp),
        ) {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    // 队列很长时 panel 不顶满屏：限高内滚动（双段共用一轨）
                    .heightIn(max = 460.dp),
            ) {
                // ── 听单段（可编辑持久队列；空=不渲染整段） ──
                if (queueItems.isNotEmpty()) {
                    item(key = "listen_queue_header") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "听单（${queueItems.size}）",
                                color = TgFeedTokens.TextHigh,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            // 整单存为播放列表（PlaylistMeta 底座；design §2.1 单向导出）
                            Text(
                                "存为片单",
                                color = TgFeedTokens.TgBlue,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(onClick = onQueueSaveAsPlaylist)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "清空",
                                color = TgFeedTokens.TextMedium,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(onClick = onQueueClear)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                    }
                    items(queueItems, key = { "q_" + it.key }) { audio ->
                        AudioQueueRow(
                            item = audio,
                            current = audio.key == queuePlayingKey,
                            playing = playing,
                            onClick = { onQueueJump(audio) },
                            // 听单段行尾=移除（可编辑队列）
                            trailing = {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "从听单移除",
                                    tint = TgFeedTokens.TextLow,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(50))
                                        .clickable { onQueueRemove(audio) }
                                        .padding(8.dp),
                                )
                            },
                        )
                    }
                }
                // ── 当前列表音频投影段（打底；点跳播+行尾插队/入听单） ──
                item(key = "projection_header") {
                    Text(
                        "当前列表（${audioItems.size}）",
                        color = TgFeedTokens.TextHigh,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
                if (audioItems.isEmpty()) {
                    item(key = "projection_empty") {
                        Text(
                            "当前列表没有音频条目",
                            color = TgFeedTokens.TextMedium,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                        )
                    }
                } else {
                    items(audioItems, key = { it.key }) { audio ->
                        AudioQueueRow(
                            item = audio,
                            current = audio.key == playingKey,
                            playing = playing,
                            onClick = { onJump(audio) },
                            trailing = {
                                Row {
                                    Icon(
                                        Icons.Outlined.SkipNext,
                                        contentDescription = "下一首播放",
                                        tint = TgFeedTokens.TextMedium,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(50))
                                            .clickable { onPlayNext(audio) }
                                            .padding(8.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        Icons.Outlined.PlaylistAdd,
                                        contentDescription = "加入听单",
                                        tint = TgFeedTokens.TextMedium,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(50))
                                            .clickable { onEnqueue(audio) }
                                            .padding(8.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 队列行：序号/封面小图 + 标题 + 歌手·频道 + 时长；在播条目主色高亮 + 行尾音符标。
 *  R299 B1：[trailing] 行尾动作槽（听单段=移除钮；投影段=下一首/入听单两钮） */
@Composable
private fun AudioQueueRow(
    item: VideoItem,
    current: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val cover = remember(item.key) { decodeCoverBytes(item.coverBytes) }
    val title = item.audioTitle.ifBlank {
        item.caption.lineSequence().firstOrNull()?.trim().orEmpty()
    }.ifBlank { "音频消息" }
    val subtitle = listOf(item.audioPerformer, item.channelTitle)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
    val titleColor = if (current) TgFeedTokens.TgBlue else TgFeedTokens.TextHigh

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
                .background(TgFeedTokens.SurfaceHigh),
        ) {
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = if (current) TgFeedTokens.TgBlue else TgFeedTokens.TextLow,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = titleColor,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = TgFeedTokens.TextMedium,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (item.durationSec > 0) {
            Text(
                formatDuration(item.durationSec),
                color = TgFeedTokens.TextMedium,
                style = MaterialTheme.typography.labelSmall
                    .copy(fontFeatureSettings = "tnum"),
            )
        }
        if (current) {
            Spacer(Modifier.width(10.dp))
            // 在播标：播放中实心音符主色；暂停中降透明度（状态一眼可读）
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = if (playing) "正在播放" else "已暂停",
                tint = TgFeedTokens.TgBlue.copy(alpha = if (playing) 1f else 0.5f),
                modifier = Modifier.size(18.dp),
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            trailing()
        }
    }
}
