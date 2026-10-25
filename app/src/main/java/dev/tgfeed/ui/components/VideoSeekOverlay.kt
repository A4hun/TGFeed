package dev.tgfeed.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 进度条与 seek 链的顶层气泡件（R131 从 VideoPage.kt 按职责拆出，纯搬运零逻辑改动）。
 * 收纳：拖拽悬浮预览卡 [SeekDragBubbleLayer]、P1.5 #2 拖拽位置信号采集常量
 * （轮询间隔 / 跳变阈值 / 防抖窗口）。
 * 预抽带引擎（SeekPreviewer）与拖拽状态编排仍在 VideoPage.kt / ProgressLine.kt。
 */

/**
 * 进度拖拽顶层悬浮预览卡（抖音范式 R67）：圆角小卡 = 目标时刻帧预览缩略图（16:9 定窗裁切）
 * + "目标 / 总时长"时间行。
 * - 调用方声明在页内所有控件层之后（z 序最顶）：卡片永远不被信息区/右轨遮挡
 *   （历史实锤：画在 ProgressLine 内部时被底部信息区压盖）
 * - 横向跟随拇指位置（目标比例 × 进度条宽），两端夹取不越出页面
 * - 垂直悬在进度条热区上沿（R194⑥ 后 58dp）之上 16dp：不压播放控制、不遮画面主体；
 *   底部锚定，卡片再高也不顶顶栏
 * - 缩略图三态：抽帧成功 → 帧图（切帧期间保留上一帧，不闪）；加载中 → 封面模糊底
 *   （R162 起只模糊不压暗）+ 小转圈占位；抽帧通道不可用（远端流/打不开）→ 整卡降级为纯时间气泡（兜底保留）
 */
@Composable
internal fun SeekDragBubbleLayer(
    drag: SeekDragState,
    preview: ImageBitmap?,
    previewUsable: Boolean,
    cover: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // 卡片模式 = 抽帧通道可用或已有帧；否则纯时间气泡兜底
        val cardMode = previewUsable || preview != null
        // 卡片宽 176dp（16:9 缩略图 + 时间行刚好）；纯时间 112dp 足够
        val bubbleWidth = if (cardMode) 176.dp else 112.dp
        val bubbleWidthPx = with(density) { bubbleWidth.toPx() }
        val pageWidthPx = constraints.maxWidth.toFloat()
        // 进度条铺满页宽，目标点 x = 比例 × 条宽；卡片居中于目标点，夹取在页内
        val thumbX = drag.fraction * drag.lineWidthPx.toFloat()
        val leftPx = (thumbX - bubbleWidthPx / 2f)
            .coerceIn(0f, (pageWidthPx - bubbleWidthPx).coerceAtLeast(0f))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomStart)
                // 热区上沿距页底 58dp（R194⑥ 后：10 避让 + 48 热区），卡片再让 16dp 间距
                .padding(bottom = 74.dp)
                .offset { IntOffset(leftPx.roundToInt(), 0) }
                .width(bubbleWidth)
                .background(
                    OverlayChipBg,
                    if (cardMode) RoundedCornerShape(12.dp) else OverlayChipShape,
                )
                .padding(if (cardMode) 6.dp else 8.dp),
        ) {
            if (cardMode) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        // 16:9 定窗：帧图裁切铺满，窗口尺寸不随源帧宽高比跳动。
                        // R162 收敛：窗口本底用 OverlayChipBg token（原 0.5 散装数字）
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(OverlayChipBg),
                ) {
                    when {
                        preview != null -> Image(
                            bitmap = preview,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        cover != null -> {
                            // 加载中占位：封面模糊底（R62 起删 0.35 压暗——只模糊不压暗，
                            // Modifier.blur 仅 Android 12+ 生效，低版本自动退化为清晰封面底）
                            Image(
                                bitmap = cover,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .blur(16.dp),
                            )
                        }
                    }
                    if (preview == null) {
                        CircularProgressIndicator(
                            color = Color.White.copy(alpha = 0.85f),
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            // 2026-10-17 用户实锤时间样式丑：原「12:34 / 45:67」白字平铺无层级。
            // 重排版：目标时间是主角（17sp SemiBold 白+tnum），总时长退灰（12sp TextMedium），
            // 斜杠噪音去掉（进度条常驻行已有已播/总时长，气泡里总时长只是参照）。
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
            ) {
                Text(
                    formatDuration((drag.targetMs / 1000).toInt()),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "tnum",
                    ),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    formatDuration((drag.durationMs / 1000).toInt()),
                    color = TgFeedTokens.TextMedium,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFeatureSettings = "tnum",
                    ),
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}

// ---------------- P1.5 #2 拖拽位置信号采集（位置跳变轮询检测，零 UI、用户无感） ----------------

/** 位置跳变检测轮询间隔（毫秒）：500ms 一拍，兼顾检测灵敏度与轮询开销 */
internal const val P15_SEEK_POLL_MS = 500L

/**
 * 跳变判定阈值（毫秒）：两拍间实际位移与"墙钟×倍速"预期位移的差超过此值才记跳转。
 * 取值覆盖 2x 长按快进（500ms×2=1000ms/拍）+ 轮询调度抖动余量，正常播放不会误报；
 * 真实主动跳转（拖拽/±10s）一跳就是数千毫秒，远超阈值。
 */
internal const val P15_SEEK_DRIFT_MS = 1_600L

/** 跳转防抖（毫秒）：连续步进/连点 ±10s 整段手势合并为一条"最终落点"事件（停顿超此值才上报） */
internal const val P15_SEEK_DEBOUNCE_MS = 1_500L

/**
 * P1.5 #2 拖拽位置信号采集（零 UI、用户无感）：
 * 进度条拖拽的 seekTo 落在 ProgressLine.kt（本轮冻结，不能加回调），故改为轮询播放
 * 位置检测"位置跳变"：连续在播的两拍之间，实际位移与"墙钟×倍速"预期位移的差超过
 * [P15_SEEK_DRIFT_MS] 即判定一次主动跳转，落点/总时长经 VM 入统计聚合（不进权重）。
 * 连带覆盖：双击 ±10s、长按连续步进、进度条点按跳转（同属主动跳转家族）；
 * 防抖 [P15_SEEK_DEBOUNCE_MS]：连续步进/连点整段合并为一条"最终落点"事件；
 * 基线纪律：非连续在播的两拍只同步不计数（暂停/缓冲/换路重拉/续播归位都不会误报）；
 * 自动循环回绕（loopOne 播完原地 seekTo(0)）按签名滤掉——那是机器行为不是主动跳转。
 */
@Composable
internal fun VideoSeekSignalEffect(
    player: Player?,
    isActive: Boolean,
    item: VideoItem,
    loopOneNowState: State<Boolean>,
    feedVm: FeedViewModel,
) {
    val loopOneNow by loopOneNowState
    LaunchedEffect(player, isActive, item.key) {
        val p = player ?: return@LaunchedEffect
        if (!isActive) return@LaunchedEffect
        var lastPos = -1L         // 上一拍位置基线（-1 = 待重建）
        var lastPlaying = false   // 上一拍是否在播（两拍都在播才做跳变判定）
        var lastTickMs = 0L
        var pendingTargetMs = -1L // 防抖中的待上报落点
        var pendingDurMs = 0L     // 落点对应的总时长（finally 补报时播放器可能已复位，需自带）
        var lastJumpMs = 0L
        try {
            while (true) {
                delay(P15_SEEK_POLL_MS)
                val now = System.currentTimeMillis()
                val dur = runCatching { p.duration }.getOrDefault(-1L)
                val pos = runCatching { p.currentPosition }.getOrDefault(-1L)
                val playing = runCatching { p.isPlaying }.getOrDefault(false)
                if (dur > 0 && pos >= 0) {
                    if (lastPos >= 0 && lastPlaying && playing && lastTickMs > 0) {
                        val spd = runCatching { p.playbackParameters.speed }.getOrDefault(1f)
                        val expected = lastPos + ((now - lastTickMs) * spd).toLong()
                        val drift = pos - expected
                        if (kotlin.math.abs(drift) > P15_SEEK_DRIFT_MS) {
                            // 自动循环回绕签名：单集循环开启且从尾部跳回开头 —— 非用户主动跳转
                            val loopWrap = loopOneNow && pos <= P15_SEEK_DRIFT_MS &&
                                lastPos >= dur - P15_SEEK_DRIFT_MS
                            if (!loopWrap) {
                                pendingTargetMs = pos
                                pendingDurMs = dur
                                lastJumpMs = now
                            }
                        }
                    }
                    // 防抖结算：距最近一次跳变超过阈值，把最终落点上报 VM（整段手势只记一条）
                    if (pendingTargetMs >= 0 && now - lastJumpMs > P15_SEEK_DEBOUNCE_MS) {
                        feedVm.noteSeekTarget(item.chatId, pendingTargetMs, pendingDurMs, item.key)
                        pendingTargetMs = -1L
                    }
                    lastPos = pos
                } else {
                    lastPos = -1L // 时长未知/位置读不到：基线作废，下一拍重建
                }
                lastPlaying = playing
                lastTickMs = now
            }
        } finally {
            // effect 取消（翻页/失焦/换源/销毁）：防抖中的落点立即补报，不丢信号
            if (pendingTargetMs >= 0) {
                feedVm.noteSeekTarget(item.chatId, pendingTargetMs, pendingDurMs, item.key)
            }
        }
    }
}
