package dev.tgfeed.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.tgfeed.core.playback.SeekPreviewer
import dev.tgfeed.core.td.VideoItem

/**
 * 拖拽帧预览缩略图带（R131 从 VideoPage.kt 按职责拆出）。
 * [rememberVideoSeekPreview] 建带并托管三条 effect（预抽触发 / 失焦释放 / 拖拽出预览），
 * 返回 [VideoSeekPreview]：frameState/usableState 驱动顶层气泡。
 */

/** 缩略图带句柄：previewer 实例 + 预览帧/可用性两态（State 引用供页面侧订阅重组） */
internal class VideoSeekPreview(
    val previewer: SeekPreviewer,
    val frameState: State<ImageBitmap?>,
    val usableState: State<Boolean>,
)

/**
 * 拖拽帧预览（R73 缩略图带，抖音同款实时观感）：播放地址就绪即后台预抽一批
 * 均匀间隔的小缩略图进内存表，拖动时只做最近邻查表——抽帧不再发生在拖动路径上，
 * 预览图挪动即变零等待。带未覆盖的时段（起播头几分钟波前未推进到/单帧失败）回落
 * R243 拖拽帧单飞通道现场抽帧（引擎侧 latest-wins 异步交付，不受本 effect 重启
 * 取消——旧 R67 现场抽帧挂在 effect 里，长视频慢拖下每 6ms 重启一次、IO 永远
 * 做不完就被取消=帧永远到不了，用户实锤「拖进度条缩略图预览没了」的根因）；
 * 源不可预览（远端流/打不开）整体降级为纯时间气泡。
 */
@Composable
internal fun rememberVideoSeekPreview(
    item: VideoItem,
    isActive: Boolean,
    playUri: String?,
    buffering: Boolean,
    seekDrag: SeekDragState?,
): VideoSeekPreview {
    val seekPreviewer = remember(item.key) { dev.tgfeed.core.playback.SeekPreviewer() }
    val previewFrameState = remember(item.key) { mutableStateOf<ImageBitmap?>(null) }
    var seekPreviewFrame by previewFrameState
    // 抽帧通道是否可用（回环流/本地文件可开 retriever 或缩略图带已有帧）：
    // false = 预览卡整体降级纯时间气泡
    val previewUsableState = remember(item.key) { mutableStateOf(false) }
    var seekPreviewUsable by previewUsableState
    DisposableEffect(item.key) {
        onDispose { seekPreviewer.close() }
    }
    // R73 预抽触发：地址就绪且本页激活即后台开抽（时长优先取容器元数据，
    // item.durationSec 仅作提示）；playUri 热切换（换档/换路重拉）自动换源重抽。
    // R76 门控：下载中（buffering）不起预抽——此时回环流首块未就绪，
    // retriever 的 setDataSource 必挂在 TgStreamServer 上（服务器刻意首块就绪前
    // 不发响应头），快速切页时每页挂一路不可中断的 native 调用，连接堆积抢
    // 下载泵/线程池，close 链被拖死（ANR 实锤）；且数据没下完也抽不出有效帧。
    // 等 READY（buffering=false，数据通路已建立）才开抽；seek 抖动引起的短暂
    // buffering 经 startPrefetch 幂等（同源在抽直接返回）零代价跳过
    LaunchedEffect(playUri, isActive, buffering) {
        val uri = playUri
        if (isActive && uri != null && !buffering) {
            android.util.Log.i("R164b", "预抽触发 uri=…${uri.takeLast(24)}")
            seekPreviewer.startPrefetch(uri, item.durationSec * 1000L)
        }
    }
    // R73 内存纪律：失焦（滑到相邻页但仍在 pager 缓存）即释放整条缩略图带与
    // retriever——防多页各挂几十兆位图堆积；回激活时上方 effect 自动重抽
    LaunchedEffect(isActive) {
        if (!isActive) seekPreviewer.close()
    }
    // 拖拽中按目标时刻出预览：250ms 量化做 key。主路查缩略图带最近邻（纯内存查表，
    // 主线程零等待，预览实时跟手）；查不到（带未覆盖该时段/预抽未就绪）派发 R243
    // 单飞请求——引擎侧 IO 不随本 effect 重启而取消，出帧后 latest-wins 交付写回
    // 这两个状态。抽帧期间保留上一帧不清空——卡片视觉连续不闪占位；单帧失败同理
    // 回退上一帧。拖拽结束（seekDrag=null）清帧与可用标记，并作废在途请求、解除
    // 带预抽的拖拽让路
    LaunchedEffect(seekDrag?.targetMs?.div(250)) {
        val drag = seekDrag
        if (drag == null) {
            seekPreviewFrame = null
            seekPreviewUsable = false
            seekPreviewer.cancelDragFrame()
            return@LaunchedEffect
        }
        // 主路：缩略图带查表（零等待）；带源与当前播放地址不匹配时内部直接判未命中
        val stripHit = seekPreviewer.peekStrip(drag.targetMs, playUri)
        if (stripHit != null) {
            seekPreviewUsable = true
            seekPreviewFrame = stripHit.asImageBitmap()
            android.util.Log.i("R164b", "拖拽预览主路命中 target=${drag.targetMs}ms")
            return@LaunchedEffect
        }
        // 兜底链：R243 单飞请求（旧 R67 现场抽帧整段废除——可取消性是病根）
        seekPreviewer.requestDragFrame(drag.targetMs, playUri) { opened, frame ->
            seekPreviewUsable = opened
            if (frame != null) seekPreviewFrame = frame.asImageBitmap()
        }
    }
    return VideoSeekPreview(seekPreviewer, previewFrameState, previewUsableState)
}
