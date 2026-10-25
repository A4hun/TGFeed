package dev.tgfeed.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 拖拽状态快照（上抛给调用方在顶层画气泡/帧预览用）：fraction=目标比例，
 * lineWidthPx=进度条像素宽（气泡横向定位基准），targetMs/durationMs 为时刻
 */
internal class SeekDragState(
    val fraction: Float,
    val lineWidthPx: Int,
    val targetMs: Long,
    val durationMs: Long,
)

/**
 * 页面最底部的播放进度细线（可拖拽 seek 版）。
 * - 协程每 200ms 轮询 player 位置/缓冲位置刷新；平时半透明，暂停/缓冲/拖拽时提亮
 * - R164b 三层带（YouTube 式，用户点名）：未下载=0.18 白 / 已缓冲未看=TextLow 浅色带
 *   （ExoPlayer bufferedPosition 真实播放口径——本地流服务器+分段下载下它就是
 *   「本地已有+可即时喂」的范围）/ 已播放=0.95 白。v1 连续带；R166 分段快进落地后
 *   若缓冲区稀疏非连续，碎带展示挂账与其联动（不在本轮过度设计）
 * - 按下加粗（抖音手感 R67）：手指落进热区立即进入按压态——细线 spring 加粗
 *   （2dp→6dp，阻尼 0.75 一中高刚度：Q 弹一下到位不持续晃）+ 拇指圆点弹簧弹出，
 *   不等横向 slop；抬起/被抢占即回弹细线
 * - R164b 手势重做（修「按在非当前播放位置不生效/漏给长按」用户实锤）：
 *   ① 热区带（R164b 72dp → R194⑥ 58dp）：线上方 48dp + 线下方 24→10dp（贴底
 *      加档，线下容差带收窄、热区其余纪律不变）。旧结构 pointerInput 在
 *      padding(bottom) 之内——线下垫是触摸死区：手指瞄着视觉细线
 *      按下，触点中心天然落在线下方，落死区=点按漏给视频层（暂停/长按面板），
 *      即用户「按了没反应/漏长按」的根因。现 onSizeChanged/pointerInput 提到
 *      padding 之前，整条占位可摸；线的画法与位置零位移、右轨挂钩（上沿
 *      58dp）不变
 *   ② awaitFirstDown(requireUnconsumed=true)：信息区/右轨控件层（更高 z、先收
 *      到事件）已消费的 down 不再被本层抢——旧 requireUnconsumed=false 会把
 *      chip 点击二次劫持成 seek；本层拿到的 down 当场消费
 *   ③ 统一事件循环（旧两段式把「过 slop」交给 awaitHorizontalTouchSlopOrCancellation
 *      ——它在内部观察 UP 即返回 null，随后的等待循环拿不到已分发的 UP，手势结束
 *      整块被取消，点按 seek 链实际是死的；且被 pager 抢走后回落还会误判成点按）：
 *      自己盯住每个事件——横向位移过 touchSlop 即进拖拽（消费位移，与竖滑翻页
 *      公平竞争，谁先过谁得）；竖向位移过 slop 或位移被别人消费=弃权（抬起不
 *      seek）；正常抬起且未拖未弃=点按跳转（点哪跳哪）
 *   ④ 按下即上报拖拽目标（seekDrag）：气泡+帧预览在按压瞬间出场（YouTube 式），
 *      不再等横向 slop；按压期间线随落点即时跳显（shown=pressFraction）
 *   ⑤ 漏出防守（Compose 1.7 语义对齐，真机实锤）：1.7 起 detectTapGestures 用
 *      awaitFirstDown(requireUnconsumed=false) 且固定消费首 down——只消费 down
 *      已挡不住视频手势层 arm：静止长按 400ms 照样弹面板、点按照样叠暂停。
 *      防守三件：down 即消费（保留）；up 恒消费（作废 waitForUpOrCancellation，
 *      点按/双击不漏出）；按下 ~150ms 后 move 也消费（作废长按等待的抖动 move
 *      路径，长按不再漏出）。代价：底部带内起手的慢速竖滑翻页被拦（快速竖滑
 *      <150ms 过 pager slop 仍可翻页）——YouTube 式进度条领地语义，用户点名
 *      「不漏给长按」优先
 * - z-order 纪律（走查 P0-3 实锤）：热区是全列宽条带且 down 即消费，调用方必须
 *   把它声明在右轨/底部信息区控件层【之前】（VideoPage 层 5.9），让控件层压在
 *   上面各收各的点击；若压在最上，「译」chip/大小 chip/追按钮下缘的点击会被吞成跳进度
 * - 拖拽气泡（时间+帧预览）不在本组件内画：本组件 z 序在控件层之下，气泡会被
 *   信息区/右轨压盖（历史实锤）。改为经 [onDragState] 上抛状态，由调用方
 *   在所有控件层【之后】声明顶层气泡层，永远不被遮挡
 */
@Composable
internal fun PlaybackProgressLine(
    player: ExoPlayer?,
    isActive: Boolean,
    emphasize: Boolean,
    modifier: Modifier = Modifier,
    // 拖拽状态上抛：进入/移动/结束都报（结束报 null）；顶层气泡层据此显隐
    onDragState: (SeekDragState?) -> Unit = {},
    // R166 duration 兜底（毫秒）：player.duration 未就绪（容器 moov 未解析——moov 在
    // 尾的流式视频起播窗口内是常态）时，点按/拖拽 seek 若只认 player.duration 会被
    // if(d>0) 静默吞掉（「拖了没反应」，用户实锤 seek 失灵的确定病灶之一）。调用方传
    // 条目元数据时长（TG 的 durationSec 恒有），让手势在 duration 就绪前也立即生效；
    // player.duration 到位后自然接管（两者至多差个位数百分比，细线比例轻微跳变一次）
    fallbackDurationMs: Long = 0L,
    // R204 TL-7 本机回放热力（60 桶归一化密度，null=样本不足不出墙）：纯展示刻度墙
    // 画在轨道上方（B 站高能条本机版）；**零交互**——不新增任何点按/跳转行为
    //（R195 seek 节流红线的规避位：热力不可点，拖拽/按压期间让位隐藏）
    heat: FloatArray? = null,
) {
    // R166：本连接有效的 seek 目标时长——player.duration 就绪优先，未就绪用兜底
    fun effectiveDurationMs(): Long = player?.duration?.takeIf { it > 0 } ?: fallbackDurationMs
    var progress by remember { mutableStateOf(0f) }
    // R164b 缓冲带：已缓冲位置占总时长比例（ExoPlayer bufferedPosition 口径）
    var bufferedFraction by remember { mutableStateOf(0f) }
    // 拖拽态：目标比例 / 拖拽时锁定的总时长（毫秒）
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }
    var dragDurationMs by remember { mutableStateOf(0L) }
    // 按压态（抖音手感）：手指落进热区即置位（不等横向 slop），细线立刻 spring 加粗、
    // 拇指圆点锚到落点弹出；抬起/手势被抢占（竖滑翻页）即回弹细线。
    // pressFraction = 落点比例，按压未拖动期间拇指的横向锚点
    var pressing by remember { mutableStateOf(false) }
    var pressFraction by remember { mutableStateOf(0f) }
    // 容器像素宽，用于拖拽定位手柄
    var widthPx by remember { mutableStateOf(0) }

    // 常驻时间数字：已播/总时长（拖拽时让位给拖拽气泡）
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }

    // 轮询播放位置与缓冲位置；拖拽时暂停，由拖拽比例接管显示（缓冲带冻结在拖前值）
    LaunchedEffect(player, isActive, dragging) {
        if (player == null || !isActive || dragging) return@LaunchedEffect
        while (true) {
            val duration = player.duration
            // R166：duration 未就绪时用条目元数据兜底——进度条提前可用（位置比例
            // 按 position/兜底时长显示），seek 手势也有目标
            val effD = if (duration > 0) duration else fallbackDurationMs
            durationMs = effD
            positionMs = player.currentPosition
            progress = if (effD > 0) {
                (player.currentPosition.toFloat() / effD.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            bufferedFraction = if (effD > 0) {
                (player.bufferedPosition.toFloat() / effD.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            delay(200)
        }
    }

    // 交互态 = 按压或拖拽：两态共用同一套加粗线 + 拇指圆点视觉
    val interacting = pressing || dragging
    // R164b：按压期间线随落点即时跳显（落点即 seek 的视觉半边；实际 seek 落在抬起）
    val shown = when {
        dragging -> dragFraction
        pressing -> pressFraction
        else -> progress
    }
    val alpha = if (emphasize || interacting) 0.95f else 0.7f
    // 发丝细度：平时 2dp，按下/拖拽加粗到 6dp；spring 规格（阻尼 0.75 + 中高刚度）
    // = 抖音式 Q 弹：加粗/回弹都一下弹到位、只轻晃一次不持续抖
    // （手势应答单现场物理，MotionTokensPolicyTest 豁免清单登记——三枚转场 spring
    // 不覆盖此域；改值须连豁免清单一起改）
    val lineHeight by animateDpAsState(
        targetValue = if (interacting) 6.dp else 2.dp,
        animationSpec = spring(
            dampingRatio = 0.75f,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "progressLineHeight",
    )
    // 拇指圆点弹出动画：按下瞬间从 0.4x 弹簧放大到 1x；松手后随手柄整体移除
    // （高刚度快闪=按下同帧可感，同上豁免清单登记）
    val thumbScale = remember { Animatable(0.4f) }
    LaunchedEffect(interacting) {
        if (interacting) {
            thumbScale.snapTo(0.4f)
            thumbScale.animateTo(
                1f,
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessHigh,
                ),
            )
        }
    }

    // 拖拽状态上抛（顶层气泡层用）：按压落点/移动中每帧报最新目标；结束/取消报 null。
    // currentOnDragState 现读：重组换回调不重启手势协程
    val currentOnDragState by rememberUpdatedState(onDragState)
    fun reportTarget() {
        val d = dragDurationMs
        if (d > 0) {
            currentOnDragState(
                SeekDragState(
                    fraction = dragFraction,
                    lineWidthPx = widthPx,
                    targetMs = (d * dragFraction).roundToLong(),
                    durationMs = d,
                ),
            )
        }
    }

    // R164b 观测日志去刷屏：手势带窗口位置只在变化时打一行（回归排查用锚点）
    var lastBandLog by remember { mutableStateOf("") }

    Box(
        modifier
            .fillMaxWidth()
            // R164b：onSizeChanged/pointerInput 提到 padding 之前——命中区覆盖整条
            // 占位（R194⑥ 后线上 48dp + 线下 10dp）；细线视觉仍在占位内原位置（线下
            // 垫只是不再挡触摸：系统底部手势区由调用方 navigationBarsPadding
            // 承担，垫内的触摸属于应用领地，收编为线下容差带）
            .onSizeChanged { widthPx = it.width }
            .onGloballyPositioned { coords ->
                val b = coords.boundsInRoot()
                val desc = "y=[${b.top.toInt()},${b.bottom.toInt()}] x=[${b.left.toInt()},${b.right.toInt()}]"
                if (desc != lastBandLog) {
                    lastBandLog = desc
                    android.util.Log.i("R164b", "进度条手势带 $desc")
                }
            }
            .pointerInput(player) {
                if (player == null) return@pointerInput
                // R164b：统一手势引擎（SeekGestureEngine.kt，共享件）——语义与实锤
                // 见该文件类注释；站点侧只留状态编排与 seek 落点
                seekGestureLoop(
                    // 按下即进入按压态 + 把落点当 seek 目标上报：气泡+帧预览立即出场
                    onPress = { f ->
                        pressing = true
                        pressFraction = f
                        dragFraction = f
                        dragDurationMs = effectiveDurationMs()
                        reportTarget()
                    },
                    onDragStart = { f ->
                        dragging = true
                        dragFraction = f
                        dragDurationMs = effectiveDurationMs()
                        reportTarget()
                    },
                    onDragTo = { f ->
                        dragFraction = f
                        reportTarget()
                    },
                    onOutcome = { outcome, f ->
                        val d = dragDurationMs.takeIf { it > 0 } ?: effectiveDurationMs()
                        if (d > 0) {
                            when (outcome) {
                                SeekGestureOutcome.TAP_SEEK,
                                SeekGestureOutcome.DRAG_SEEK,
                                -> {
                                    player.seekTo((d * f).roundToLong())
                                    // 顺滑收尾：seek 生效需要解码一帧，轮询（200ms 间隔）
                                    // 读到旧位置会把细线拉回拖前——先把显示进度钉在落点，
                                    // 等下一轮轮询读到真实新位置自然接管
                                    progress = f
                                }
                                SeekGestureOutcome.ABANDONED -> Unit
                            }
                        }
                    },
                    // 协程被取消（翻页/dispose）也必须退出拖拽/按压态
                    onCleanup = {
                        dragging = false
                        pressing = false
                        currentOnDragState(null)
                    },
                )
            }
            // R194⑥ 底部贴拢（用户实测「进度条和信息区还在高位、与 dock 之间空地大，
            // 要整体往下贴」）：线下避让垫 24→10dp——细线贴到 LocalBottomBarInset（dock
            // 上沿）+10dp（半透明 dock 与视频媒体延伸其后，chrome 与 dock 间只留一线
            // 呼吸）。线下容差带 10dp 仍兜住瞄线偏下的按压（垫内触摸属应用领地语义
            // 不变，见上方 R164b 注释）；线上 48dp 绘区/热区不动（R164b 手势纪律）。
            .padding(bottom = 10.dp)
            .height(48.dp),
    ) {
        // 轨道（全圆角胶囊，末端圆润）——R164b 三层：底 0.18 / 缓冲带 TextLow / 已播
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(lineHeight)
                .clip(RoundedCornerShape(percent = 50))
                .background(Color.White.copy(alpha = 0.18f)),
        ) {
            // 已缓冲未看段（浅色带）：取色走 TgFeedTokens.TextLow（token 家取色纪律）；
            // 画在已播层之下——已播段自身必已缓冲，两层重叠时已播覆盖
            if (bufferedFraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(bufferedFraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(percent = 50))
                        .background(TgFeedTokens.TextLow),
                )
            }
            Box(
                Modifier
                    .fillMaxWidth(shown)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.White.copy(alpha = alpha)),
            )
        }
        // R204 TL-7 本机回放热力刻度墙：轨道上方的细刻度（60 桶，密度=高度）；拖拽/
        // 按压期间隐藏（让位手势视觉，不与拇指/加粗线打架）；纯展示不可点。
        // 与轨道同底对齐、贴着轨道上缘生长（SoundCloud 迷你波形位）
        if (!interacting && heat != null && heat.isNotEmpty()) {
            Canvas(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(18.dp),
            ) {
                val n = heat.size
                val gap = 1.dp.toPx()
                val tickW = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
                val maxH = size.height - 2.dp.toPx()
                for (i in 0 until n) {
                    val v = heat[i]
                    if (v <= 0f) continue
                    val tickH = (v * maxH).coerceAtLeast(2.dp.toPx())
                    drawRect(
                        color = Color.White.copy(alpha = 0.30f),
                        topLeft = Offset(i * (tickW + gap), size.height - tickH),
                        size = Size(tickW, tickH),
                    )
                }
            }
        }
        // 常驻时间数字：已播 / 总时长（等宽数字防跳动；按压/拖拽时让位给拇指与拖拽卡）。
        // 放右下：左下是频道名/信息区（放左边会跟它重叠——QA 实测踩坑）
        if (!interacting && durationMs > 0) {
            Text(
                "${formatDuration((positionMs / 1000).toInt())} / ${formatDuration((durationMs / 1000).toInt())}",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
                    .withOverlayShadow(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 14.dp, bottom = 10.dp),
            )
        }
        if (interacting) {
            // 拇指圆点锚点：拖拽中跟手指（dragFraction 每帧上抛刷新，零延迟滞后）；
            // 仅按压未拖动时锚在落点（抖音：点哪拇指贴哪）
            val thumbFraction = if (dragging) dragFraction else pressFraction
            // 圆点手柄：垂直中心对齐进度线，带柔光投影更立体
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset {
                        val thumbPx = 12.dp.toPx()
                        val x = (widthPx * thumbFraction - thumbPx / 2f)
                            .roundToInt()
                            .coerceIn(0, (widthPx - thumbPx).roundToInt().coerceAtLeast(0))
                        // offset 的 y 向下为正；手柄比线粗，需要上抬一半差值
                        val y = ((lineHeight.toPx() - thumbPx) / 2f).roundToInt()
                        IntOffset(x, y)
                    }
                    .size(12.dp)
                    .overlayIconShadow()
                    // 按下时手柄弹簧弹出（缩投放投影之后：投影不跟手柄一起跳）
                    .graphicsLayer {
                        scaleX = thumbScale.value
                        scaleY = thumbScale.value
                    }
                    .background(Color.White, CircleShape),
            )
            // 拖拽预览卡（时间+帧预览）在 VideoPage 顶层（SeekDragBubbleLayer）：本组件
            // z 序在信息区/右轨之下，卡在这里画必被压盖（历史实锤）；顶层画永不被遮挡
        }
    }
}
