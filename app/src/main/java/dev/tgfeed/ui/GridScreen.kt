package dev.tgfeed.ui

import android.content.Context
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
// R149 ① 自绘宽容版变换手势（awaitEachGesture/awaitFirstDown/计算族）所需
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
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
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
// R149 ① 自绘变换手势的取消判定（位移是否被消费）
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
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
// R149 ⑧ 分屏翻页补 R126 折叠闸（与主 Feed 同判据同口径）
import dev.tgfeed.core.feed.contentFoldId
import dev.tgfeed.core.feed.foldReposts
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
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.random.Random

// 画布几何常量与排位模型（CELL_SIZE/CANVAS_COLS/横屏扁格/缩放域/MAX_SPAN/
// PK 格位/CellRect/flowLayout/pkCellRect）已拆至 GridCanvasModel.kt（R129 纯搬运）
/**
 * 同时持有播放器的上限：可见格按顺序取前 N 个 acquire，其余显示封面（与池容量一致）。
 * 9 → 6（平板分屏"大量解码失败"修复，logcat 实证）：
 *  - 解码器侧：多数设备硬解并发上限 4~6 路，9 路必然超限；
 *  - 流源侧：9 格开局同时拉流，TgStreamServer 的 TDLib 同步分段下载
 *    （DownloadFile synchronous=true）在并发尖峰下大量报错/空响应，
 *    播放侧表现为 UnrecognizedInputFormatException（"解码失败"）。
 * 6 → 4（R217 ② 用户实锤「播放别的格子基本全失败」+16384 复现实证）：
 *  - 流源侧三重瓶颈在 6 路下仍然超载：TgStreamServer 全局下载许可
 *    downloadSemaphore=4（6 泵必排队，队尾连接等首块超时）、连接线程池
 *    MAX_THREADS=8（6 数据连接+accept+moov 探针并发即顶满，顶满回 503）、
 *    多数平板硬解上限 4~6 路；
 *  - 16384 复现现场：6 格齐开 → 其余格集中 gridErr code=2002/2004（IO 网络类）
 *    + 泵 20s 超时重试风暴 + 近窗速率 0~800KB/s 抖动，焦点格独活——正是用户
 *    「视频加载出来后，播放别的格子基本全失败；一个个点重试才好」的形态；
 *  - 4 与三重瓶颈对齐：第 5+ 格走既有降级（封面+▶ 待播，非失败态），点按即
 *    焦点优先占路开播（playableKeys 焦点先占位），失败面消失而非掩盖。
 * R230 ⑥ 起改为**授权口硬闸**：满 4 路直接拒绝新授权（Toast 提示），池的 LRU
 *  淘汰路径在分屏结构性不可达（授权数 ≤ 池容）——「第 5 个请求 FIFO 逐出最旧格」
 *  弃用：逐出=自动暂停最旧格，违反「暂停只能由用户手动点暂停」。
 */
private const val MAX_GRID_PLAYERS = 4

// 视频格播放常量（失败换片上限/起播错峰/背景音量）与换片垫底封面持有器已随
// GridVideoCell 拆至 GridVideoCell.kt（R129 纯搬运）
/** 距画布底不足该行数时预取下一页（约 2 屏高度） */
private const val LOAD_AHEAD_ROWS = 6

/**
 * 自持源翻页超时：nextPage 卡死（弱网/TDLib 挂起）时 gridLoading 不能永久占锁——
 * 占锁期间所有换片链（播完/轮播/换一拨/悬浮落区）都被去重吞掉，格子播完即定格。
 * 超时按"本页失败"处理，走既有的补试/兜底路径
 */
private const val PAGE_FETCH_TIMEOUT_MS = 15_000L

/** 悬浮落区高度占比：拖拽中屏幕顶部这一段是"拖到此处悬浮播放"落区 */
private const val POPOUT_ZONE_FRACTION = 0.15f

/** 自动轮播间隔：开启后每隔这么久给"没在播"的可见格换下一条 */
private const val AUTO_ROTATE_INTERVAL_MS = 20_000L

/**
 * 网格槽位：[slotId] 是格子的稳定身份（初始 = 首条内容 key，拖拽换序/关格/焦点都按它），
 * [content] 是该槽当前播放的内容——格子播完自动换片只改 content，槽位本身不动，
 * 其它格的位置、焦点、播放器归属完全不受影响。
 */
private data class GridSlot(val slotId: String, val content: VideoItem)

// 私色已全部收编回 TgFeedTokens（GRID-REDESIGN §8 待删项）：
// 焦点红 FOCUS_BORDER = AccentRed（重复定义）；星标黄 FAV_YELLOW #FDD835 系
// DESIGN-REVIEW F4 裁决淘汰的刺眼旧值，统一走 StarYellow #FBBC05

/** 画布记忆：单次落盘最多记的槽位数（前 N 个槽位是用户最近操作区，再往深处恢复价值递减） */
private const val GRID_CANVAS_MAX_SLOTS = 48

/**
 * 开屏换新风：从快照恢复布局后，前 N 个槽位（约一屏可见格数）不复活旧内容，
 * 挂起等第一页新片认领（走既有换片链）；更深槽位保留恢复内容，
 * 随用户浏览由播完/轮播/预取链自然换新
 */
private const val OPEN_REFRESH_SLOT_COUNT = 8

/** 自持源单次翻页的补试上限：一页被滤空（已看/类型开关/撞快照）时再补几页，
 *  有格挂起等片时给开屏换新风留够穿透深度（重启后前几页可能整页是会话已看）；
 *  源真到底时 GatewayFeedSource 对耗尽游标本地秒回空，多补的成本可忽略 */
private const val GRID_PAGE_MAX_ATTEMPTS = 4

/**
 * R217 ① 空画布补试上限（首进「什么都不加载」修复）：进分屏首拉时画布还没有
 * 任何内容（feedItems 空、无挂起格）——旧 break 条件只补试一次就判「源到底」，
 * 而自持源页内已看硬剔除（R150B 显示即落账后底账很厚）+ TDLib 冷启动 15s 超时
 * 都可能整页滤空，首进即空画布引导态；再进一次游标已前进才出片（用户实锤
 * 「再点一次才正常加载」）。画布为空时按本上限继续翻：源真到底时本地秒回空，
 * 多试只多几次本地轮转；非空画布（有格在播）维持 GRID_PAGE_MAX_ATTEMPTS 老口径
 */
private const val GRID_EMPTY_CANVAS_MAX_ATTEMPTS = 8

/** 画布记忆：画布状态变化后的落盘静默窗口（防抖；退出分屏时另有 onDispose 兜底直写一次） */
private const val GRID_CANVAS_SAVE_DEBOUNCE_MS = 1_000L

/** R94 分屏实际观看落账阈值：格内 isPlaying 真实播放累计达到该毫秒数即把内容记进
 *  seen_ids.log 已看底账（与 Feed 侧"有效观看 ≥2s"同值口径）；渲染即过的不算 */
private const val GRID_WATCH_MARK_MS = 2_000L

/**
 * 画布可持久化状态的相等性快照（GAPS3 #8）：snapshotFlow 的发射源——
 * 任一字段变化 = 画布脏了，bump 脏计数驱动防抖落盘。
 */
private data class GridCanvasDirtyTick(
    val scale: Float,
    val tx: Float,
    val ty: Float,
    val order: List<String>,
    val spans: Map<String, Pair<Int, Int>>,
    val closed: Set<String>,
    // 槽位 → 当前内容 key（换片链推进即变化，落盘要记的是"退出时每格在播什么"）
    val contents: List<Pair<String, String>>,
    val customMode: Boolean,
)

/**
 * 自由画布模式 v6（CanvasGrid：异尺寸格子 + 连续缩放 + 拖拽调整大小/换位）。
 * 与 FeedScreen 共享 activity 作用域的 FeedViewModel（互动数据互通：点赞/收藏/下载）。
 *
 * v6 变化（均分网格 → 自由画布）：
 *  - 坐标系：LazyVerticalGrid 换成一块虚拟画布。逻辑单元 cellSize=120dp，
 *    画布固定 [CANVAS_COLS]=4 单元宽，高度随条目流式排位（[flowLayout]）增长；
 *    每条内容占 spanW×spanH 单元（默认 1×1，[spans] 记录用户拖大的 2×1 / 2×2）
 *  - 连续缩放：detectTransformGestures 直接写 canvasScale（0.5~2.2 无档位），
 *    整个画布走 Modifier.graphicsLayer 变换（缩放期零重组），以捏合中心为 pivot
 *    做平移补偿保持双指下的内容点不动；单指拖动 = 平移画布（带边界钳制，不留空）
 *  - 调整大小：焦点格右下角出拖柄，拖动按位移跨档切换 span（右/下拖放大、
 *    左/上拖缩小，档位 1..MAX_SPAN，R149 ③ 起上限 4——横版默认 2×1 也能向右
 *    扩到整行），拖动中画布层画虚线预览框，
 *    松手落档 + 触觉反馈，相邻格子自动流式让位重排
 *  - 只渲染可见格：按 scale/translation/视口尺寸反算可见逻辑范围（外扩 1 单元
 *    预渲染边距），范围外的格子不 compose；可见格按顺序前 [MAX_GRID_PLAYERS]
 *    个才 acquire 播放器
 *
 * v5 继承（逻辑原样迁移，未重写）：
 *  - 播完自动换片：槽位（GridSlot）是格子的稳定身份，STATE_ENDED 时按"每格已播
 *    游标"把该槽内容换成内容池里下一个未播条目，到底取模回开头循环
 *  - 内容源自持：VM 托管的 WeightedAggregateFeedSource（游标跨进出分屏存活）+
 *    私有 feedItems + 自己的翻页协程，与 Feed 互不消费；片单模式（customItems 非空）仍优先用片单
 *  - 声音策略"主声音 + 背景音"：焦点格 1.0（可调），其余格 BACKGROUND_VOLUME
 *  - 拖拽换格：长按起拖，命中测试改为画布逻辑坐标（屏幕位移 ÷ 缩放折算），
 *    交换只动视图层 orderOverride；跟手幽灵改在视口层按屏幕坐标绘制
 *  - 片单头、本地视频、单击焦点/暂停、双击点赞飘心、独立音量滑杆、焦点红线、
 *    解码失败封面重试、关格语义（片单移除 / closedKeys 顶替）全部保留
 *
 * v7（横屏适配 + 一键全停/全播）：
 *  - 横屏逻辑单元改 16:10 扁格（cellH() = cellPx × 10/16，竖屏仍正方形），
 *    画布内全部坐标换算按轴走 cellW()/cellH()；refitCanvas 横屏时按
 *    "两行高度 = 视口高"反推 scale 上限与宽度 fit 取小——修复横屏下格子
 *    被拉得过高、一屏连两行都放不下的怪异观感
 *  - 顶栏"全停/全播"圆形钮：pause 全部可见格（不回收播放器，画面定格原位），
 *    再点 acquire（幂等，busy 中直接返回原实例）+ play() 全部续播；
 *    全停期间格内 playWhenReady 守卫保证换片链换上新片也不自动开播
 *
 * 手势约定：
 *  - 单击格：未聚焦 → 设为焦点（开始出声）；已聚焦 → 暂停/继续（中央 ▶ 反馈）
 *  - 双击格：点赞 + 落点飘心
 *  - 长按格：拖拽换格（拖到目标格松手交换）；拖到屏幕顶部 15% 悬浮落区松手 →
 *    该格内容弹悬浮窗（onPopoutToFloating，容器层接 container.addFloating），
 *    槽位留下自动换下一条；原地松手 → 小菜单（收藏 / 下载 / 不感兴趣 / 关闭此格 / 全屏单播）
 *  - 单指拖画布空白/格子：平移画布；双指捏合：连续缩放（中央出百分比徽章）
 *  - 画布空白处双击 / 顶栏换一批钮：整批换一拨（可见槽位一起走换片链，中央一闪"换了一拨"）
 *  - 顶栏自动轮播钮：开启后每 20s 给没在播的可见格换下一条（播放中未播完的不打断）
 *  - 顶栏擂台钮：进入 PK 对打模式——画布只留左右两个大格（进入时可见槽位前 2 个，
 *    各钉 2×2 单元），各带"👈 左边赢 / 👉 右边赢"半透明投票胶囊；投票后两格一起
 *    走现有换片链换下一对，再点擂台钮退出恢复正常画布
 *  - 焦点格右下角拖柄：调整大小（1×1 / 2×1 / 2×2 按拖向跨档）
 *
 * 注：需求中"双击全屏"与"双击点赞"冲突，双击归点赞（更高频），
 * 全屏单播放进长按菜单，点击即 onExit 回 Feed（Feed 侧状态不动）。
 *
 * v8（行为缺陷打磨，详见 docs/GRID-POLISH.md，视觉层零改动）：
 *  - 换片链：翻页加 15s 超时 + finally 防 gridLoading 永久占锁；认领/兜底写入
 *    收敛进同一可变快照；一轮没领完挂起格自动链式续翻；槽位序改 derivedStateOf
 *    （消掉分页后"effect 晚一拍"的一帧空窗）
 *  - 焦点/声音：playableKeys 焦点优先（格子超过路数上限时焦点格必可播）；
 *    关焦点格清悬挂焦点；点失败格重试顺带聚焦
 *  - 播放器池：失败格立即归还播放器（不再占位顶爆池容）；poolEpoch 代际自愈
 *    池满 FIFO 误淘汰（受害格下一帧重 acquire）；手动暂停在同片重挂后保持
 *    （划出可见区/进出专注不再偷跑成自动续播）
 *  - 模式互斥：进专注先退擂台；擂台不足两格不进/任一被关自动退；轮播不拆擂台对
 *  - 手势：悬浮落区加位移门槛（原地长按弹菜单不再误飞悬浮窗/闪落区提示）；
 *    落区松手与长按菜单不再双触发
 */
@Composable
fun GridScreen(
    gateway: TdGateway,
    onExit: () -> Unit,
    customItems: List<VideoItem>,          // 自定义片单（容器层持有，空 = 用 Feed 当前源）
    onAddCustom: (VideoItem) -> Unit,      // 加入片单（去重由容器做）
    onRemoveCustom: (VideoItem) -> Unit,   // 从片单移除
    onFullscreenItem: (VideoItem) -> Unit = {}, // 全屏单播：回 Feed 并定位到该条
    // 拖到顶部落区松手：该格当前内容弹成悬浮窗（容器层接 container.addFloating），
    // 槽位本身留下并自动换下一条；默认空实现保证旧调用点不用改
    onPopoutToFloating: (VideoItem) -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as android.app.Application
    // 新手引导状态（分屏首次进入时显示手势说明）
    val prefsCoach = remember { dev.tgfeed.core.prefs.AppPrefs(app) }
    var showCoach by remember { mutableStateOf(prefsCoach.firstRunGrid) }
    // 与 Feed 共享 activity 作用域 VM：本网格只用它做互动数据（点赞/收藏/下载），
    // 内容源刻意不再吃 vm.items——那是用户刚在 Feed 刷过的那批，直接用必然重样
    val vm: FeedViewModel = viewModel(factory = FeedViewModelFactory(gateway, app), key = "gw" + System.identityHashCode(gateway))
    val likesState = vm.likes.collectAsState()
    val likes by likesState
    val favoritesState = vm.favorites.collectAsState()
    val favorites by favoritesState
    val downloadsState = vm.downloads.collectAsState()
    val downloads by downloadsState
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    // 窗口档位缩放系数：新 UI 元素（空画布引导态 / 顶栏工具岛 / 片单选择器）整体乘它
    // 跟随手机/平板档位（既有画布/格子渲染逻辑不碰，见 P0-2 整改纪律第 5 条）
    val uiScale = rememberWindowSize().uiScale

    // 网格自有播放器池：与可见格 acquire 上限（MAX_GRID_PLAYERS=6）一致；低内存模式防 OOM
    val playerPool = remember { PlayerPool(context, maxPlayers = MAX_GRID_PLAYERS, lowMemoryMode = true) }

    // 画布记忆存取（GAPS3 #8）：grid_canvas.json，进分屏读回 + 防抖 1s 落盘 + 退出兜底落盘
    val store = remember { LocalStore(app) }

    val density = LocalDensity.current
    // 逻辑单元像素：画布逻辑坐标（px）= 单元数 × cellPx
    val cellPx = with(density) { CELL_SIZE.toPx() }
    // ── R278 项2 状态句柄收口（主方法 DEX 瘦身：20101 units/328 寄存器超线，
    // 逼近 R253 崩溃档 32k+/359；R258 TasteMapScreen 同款拆分先例）──
    // 画布/交互 remember 态与快照表全部收进单一 remember 的句柄类（GridUiCtx，
    // 本文件底部）；主函数经 `by gcx.xxx` / `= gcx.xxx` 委托读写——同一 State
    // 实例，快照语义逐位不变，委托声明只是本地别名；字段语义注释随字段迁入类定义。
    val gcx = remember {
        GridUiCtx(
            topPull = TopPullTracker(with(density) { 56.dp.toPx() }, 1_200L),
        )
    }
    // 格间 2dp 黑缝（TgFeedTokens.GridGap）：每格四边内缩一半，相邻格拼出整条缝，
    // 配合格子圆角形成"卡片悬浮在黑场上"的层次（替换旧 1px 发丝缝）
    // 片单模式：customItems 非空即进入（与搜索/Feed 源互斥，容器层负责去重与持有）
    // State 化（下游 derivedStateOf 链要跟踪到 customItems 的变化）
    val isCustomModeState = remember { derivedStateOf { customItems.isNotEmpty() } }
    val isCustomMode by isCustomModeState

    // ── 画布变换状态（屏幕 px 坐标系）────────────────────────────
    // 连续缩放系数：1.0 = 1 逻辑单元正好 120dp
    var canvasScale by gcx.canvasScale
    // 画布平移（px）：屏幕坐标 = 画布逻辑坐标 × scale + translation
    var canvasTx by gcx.canvasTx
    var canvasTy by gcx.canvasTy
    // 视口像素尺寸（onSizeChanged 写入）
    var viewportSize by gcx.viewportSize
    // 已做过"宽度适配"的视口宽：首次布局 / 旋转（宽变化）时重新 fit
    var fitWidth by gcx.fitWidth
    // 画布记忆恢复门闩（GAPS3 #8）：false = 进入时快照尚未读回（此间首帧 onSizeChanged
    // 不做宽度适配，避免 refit 把即将恢复的缩放/平移冲掉）；恢复路径走完后恒为 true
    var canvasRestoreChecked by gcx.canvasRestoreChecked
    // true = 本次进入已从快照恢复画布变换（scale/平移），不再做"按视口宽"自动适配
    var canvasTransformRestored by gcx.canvasTransformRestored
    // 画布记忆防抖：脏计数（可持久化状态每次变化 +1，落盘 effect 以它为 key 重启计时）
    var canvasDirtyTick by remember { mutableIntStateOf(0) }

    // ── 横屏适配：逻辑单元宽/高与横屏判定 ────────────────────────
    // 竖屏：单元是 cellPx 正方形；横屏：高 = 宽 × 10/16 的 16:10 扁格
    // （横屏视口矮，方格会把格子拉得过高，一屏连两行都放不下）。
    // 刻意用函数而不是 val：pointerInput / 手势回调等长驻闭包只在首次组合时捕获，
    // 函数体内现读 viewportSize（State），旋转后拿到的是新值而不是首帧旧值
    fun isLandscapeNow(): Boolean =
        viewportSize.height > 0 && viewportSize.width > viewportSize.height

    fun cellW(): Float = cellPx

    fun cellH(): Float =
        if (isLandscapeNow()) cellPx * LANDSCAPE_CELL_HEIGHT_FRACTION else cellPx
    // 捏合进行态：中央显示缩放百分比徽章（无档位，直接显示 scale）
    var pinching by gcx.pinching
    var lastPinchAt by gcx.lastPinchAt

    // ── 格子异尺寸（span）与调整大小 ─────────────────────────────
    // 槽位 id → spanW×spanH（默认 1×1 不入表）；只存在内存，不落库
    val spans = gcx.spans
    // 已播过入场错落动画的槽位 id（普通集合，非快照 State：只做动画门控，
    // 不驱动重组——滚动回收再回来的格不重播，换源时清空重新播）
    val entranceSeen = gcx.entranceSeen
    // 正在拖柄调整大小的槽位与预览 span（拖动中画虚线框，松手才落档）
    var resizeKey by gcx.resizeKey
    var resizeSpan by gcx.resizeSpan
    var resizeStart by gcx.resizeStart
    var resizeAccum by gcx.resizeAccum

    // ── 视图层交互状态（沿用 v5）──────────────────────────────────
    // 被 × 关闭的槽位 id 集合：过滤后下面的条目自动顶上
    var closedKeys by gcx.closedKeys
    // 焦点格槽位 id：主声音所在的格；null 时回落到首格
    var focusedKey by gcx.focusedKey
    // 全局静音总开关：true 时全部格 0 音量（含焦点格）
    val muteAllState = remember { mutableStateOf(false) }
    var muteAll by muteAllState
    // R230 ⑥ 手动播放授权集（grant）：槽位 id 的授权序集合。空 = 入场零自动播
    // （全格封面+淡▶待播水印，起播只由用户手动点击触发）；onRequestPlay 授权才
    // acquire 拉流起播；满 MAX_GRID_PLAYERS 拒绝新授权（Toast）——FIFO 逐出=自动暂停
    // 最旧格，违反「暂停只能由用户手动点暂停」故弃。授权格常驻组合（渲染循环放行）
    // → 滚动与播放解耦（实锤5）；授权释放：播完/关格/切源/换一批（显式批量动作）；
    // PK 擂台两格例外（对打连播语义）。Set 运行时实例恒 LinkedHashSet（授权序保留）
    var playGrants by gcx.playGrants
    // R230 ③ 池内择片的已看双轨（内存缓存快照，500ms 显示落账轮询同步刷新——
    // 新落账条目最多延迟半秒进入过滤口径；advanceFromPool 主线程读它，不直读盘）
    var seenFilterIds by remember {
        mutableStateOf<Pair<Set<String>, Set<String>>>(emptySet<String>() to emptySet())
    }
    // R230 ④ 顶缘 overscroll 累计器：钳在画布顶且继续下拉累计，过阈值触发顶部预补
    val topPull = gcx.topPull
    // R230 ④ 顶部补页一闪提示（「上方已补充新内容」，预补成功后短暂显示）
    var topGrowFlash by remember { mutableStateOf(false) }
    // 每格独立音量记忆（按槽位 id），未调过默认 1.0
    val volumes = gcx.volumes
    // 搜索框文本
    var searchText by remember { mutableStateOf("") }
    // 搜索框展开态：默认收起为圆形按钮，点击展开胶囊输入框
    var searchExpanded by remember { mutableStateOf(false) }
    // 「从片单添加」选择器显隐（空画布引导态主按钮 / 顶栏工具岛添加槽共用同一入口）
    var showPlaylistPicker by remember { mutableStateOf(false) }
    // R227 画面调整枢纽：目标条目 key（非空即弹 FrameAdjustHubSheet 小菜单——
    // 格子长按菜单「画面调整」项收口到这一个状态；面板自持 FrameAdjustStore
    // 读写，与 Feed 播放页长按面板同一枢纽同一记忆真身）
    var frameAdjustKey by gcx.frameAdjustKey
    // 命名片单列表（与 Feed 共享的 activity 作用域 VM 同源，选择器数据源）
    val playlists by vm.playlists.collectAsState()

    // ── 已看弱化（纯视觉）──────────────────────────────────────
    // 已看身份双轨（key 集 + 内容指纹 fileUniqueId 集）：LocalStore.loadSeenIdentities
    // 是公开同步方法（内部带内存缓存；底账 seen_ids.log 容量 100000，比旧口径
    // loadHistory 的 500 条历史上限覆盖更全，且指纹轨能认出"跨频道转发的同片"），
    // 仍按 IO 纪律切 Dispatchers.IO 读取，读完进 State；
    // 命中的格子由 GridVideoCell 叠 40% 黑纱 + "已看过"小标签
    val watchedIdsState = remember {
        mutableStateOf<Pair<Set<String>, Set<String>>>(emptySet<String>() to emptySet())
    }
    var watchedIds by watchedIdsState
    LaunchedEffect(Unit) {
        watchedIds = withContext(Dispatchers.IO) {
            // R150：读 VM 持有的同一 LocalStore 实例（缓存口径与落账方一致；
            // 分屏期间 display-seen 持续落账，"已看过"黑纱判定随重读刷新）
            vm.store.loadSeenIdentities()
        }
        // R230 ③：择片过滤的已看双轨同源初始化（一次 IO，两处消费）
        seenFilterIds = watchedIds
    }

    // ── 拖拽换格（沿用 v5，命中测试换画布坐标系）───────────────────
    // 被拖槽位 id：非空表示正处于"长按拖拽"中
    var dragKey by gcx.dragKey
    // 拖拽累计位移（屏幕 px；命中测试时 ÷ scale 折算逻辑位移）
    var dragAccum by gcx.dragAccum
    // 当前命中（指针格心落在其范围内）的目标槽位 id
    var dragTargetKey by gcx.dragTargetKey
    // 拖拽指针是否已进入顶部悬浮落区（松手 = 弹悬浮窗而非换格）
    var dragInPopoutZone by gcx.dragInPopoutZone
    // "换了一拨"中央一闪提示（空白双击 / 批量换片后短暂显示）
    var shuffleFlash by remember { mutableStateOf(false) }
    // 自动轮播开关：仅会话内记忆，不落库
    var autoRotate by remember { mutableStateOf(false) }
    // 专注模式开关：开启后 playableKeys 只剩焦点格，其余格 playable 翻 false
    // （播放器归还池、画面自然回落封面渲染，背景音随之全停）；仅会话内记忆，不落库
    var focusMode by gcx.focusMode
    // ── PK 擂台模式（分屏对打投票）────────────────────────────
    // 擂台开关：开启后画布只留左右两个大格对播，各配"哪边赢"投票钮；仅会话内记忆，不落库
    var pkMode by gcx.pkMode
    // 擂台槽位对：进入时从当前可见槽位快照前 2 个（退出清空）；槽位之后被 × 关/片单移除
    // 时渲染层按现存槽位过滤，空对由下面的守卫 effect 自动退出 PK
    var pkSlotIds by gcx.pkSlotIds
    // 每格"正在播放中"上报表（格内播放器的 isPlaying）：自动轮播只换没在播的格，
    // 不在表里 / false = 可换；格销毁时移除对应条目，表不会无限涨
    val playingNow = gcx.playingNow
    // R380 意图口径上报表（PlaybackTogglePolicy 单源判定）：与 playingNow 同源不同
    // 口径（isPlaying 喂轮播/记账零改，意图喂钮态与全停快照）——两表分工判词见
    // GridUiCtx.playingIntentNow
    val playingIntentNow = gcx.playingIntentNow
    // R380 全停音频快照（池外自管音频格的恢复面）：判词见 GridUiCtx.audioPausedByAll
    var audioPausedByAll by gcx.audioPausedByAll
    // R94 分屏实际观看记账（修"分屏看过的重启后又被推"）：格内 isPlaying 起表，
    // 停播/归还/换片时结算累计；累计 ≥GRID_WATCH_MARK_MS 的内容经 vm.markGridWatched
    // 落 seen_ids.log 持久底账（一条内容一会话只报一次）。只记"真播过"——
    // R150 起纯展示也落账（markGridDisplayedSeen：显示即看过，无播放阈值），
    // 本轨道保留"真播过"的更强语义标记（两轨都进同一本 seen 底账，幂等不重复写）
    val gridWatchStart = gcx.gridWatchStart // 槽位 → (内容, 起表时刻)
    val gridWatchAccum = remember { mutableMapOf<String, Long>() } // 内容 key → 累计真实播放毫秒
    val gridWatchReported = remember { mutableSetOf<String>() } // 已落账的内容 key（会话内幂等）
    // R380 有任一格"用户语义在播"：顶栏"全停/全播"钮的状态源（快照 Map，派生自动
    // 跟踪）。主口径=意图口径（PlaybackTogglePolicy 单源——缓冲中 isPlaying=false
    // 但播放意图在＝"在播"）；playingNow 真出声口径留在并集里兜上报时序洞
    // （isPlaying=true 必有意图在）。旧源只看 playingNow（isPlaying），缓冲窗误读
    // "全部播放"→点了 no-op（R375 病根的全停钮形态）
    val anyPlaying by remember {
        derivedStateOf { playingNow.values.any { it } || playingIntentNow.values.any { it } }
    }
    // 视图层换序表：null = 原始顺序；非空 = 拖拽交换后的槽位 id 顺序。
    // 刻意不落库、不改片单数据：只影响本网格的展示顺序
    var orderOverride by remember { mutableStateOf<List<String>?>(null) }

    // ── 自持内容源（修复"分屏和 Feed 完全重样"，沿用 v5）───────────
    // 加权混排聚合源提升到共享 VM 持有（vm.gridFeedSource）：游标跨"进出分屏"
    // 存活，再进分屏接着上次位置拉新——此前源在本屏 remember 里，每次进分屏都
    // cursor=null 从头拉第一页，叠加快照恢复即"每次打开都是那些视频"的主凶。
    // 搜索提交后整体换成 SearchFeedSource（只影响网格，Feed 侧状态不动）：
    // 搜索词属本次操作的会话语义，仍由本屏持有（gridSearchSource），不进 VM。
    // R150：gridStore 从"本屏 remember 新建 LocalStore(app)"改为直取 vm.store——
    // 源内过滤与 gridLoadMore 网关直拉路径的已看双轨判重共用 VM 持有的同一实例
    //（seen 底账唯一写入方）：旧写法每进分屏另起实例，其 seen_ids.log 内存镜像
    // 在 VM 落账（display-seen / ≥2s 落账 / Feed 观看）后不失效，分屏过滤读到
    // 旧账、把已看内容当新片再供一格（AUDIT-STABILITY #14 的实伤面）；
    // 单实例后写时失效重建的缓存口径对分屏侧同样生效（命中内存缓存零冷读）。
    // 已看硬剔除接"已看内容不再出现"设置开关（prefsCoach.excludeWatched，
    // 关掉恢复软降权旧行为，与 Feed 侧同一条款）
    val gridStore = vm.store
    var gridSearchSource by remember { mutableStateOf<FeedSource?>(null) }
    // VM 托管的加权源：同一 activity 会话内恒为同一实例（游标跨进出分屏存活）
    val gridWeightedSource = remember(gridStore) {
        vm.gridFeedSource(gridStore) { prefsCoach.excludeWatched }
    }
    // 当前生效源走 derivedStateOf 委托读取：滚动预取/轮播等长驻闭包读到的是
    // 当前值而不是首帧捕获——搜索换源后旧闭包不会继续给加权源翻页灌列表
    val gridSource by remember { derivedStateOf { gridSearchSource ?: gridWeightedSource } }
    // 网格私有内容列表（片单模式下不使用，片单内容来自容器层 customItems）
    var feedItems by remember { mutableStateOf(listOf<VideoItem>()) }
    // 自持源翻页中标记（gridLoadMore 并发去重）
    var gridLoading by remember { mutableStateOf(false) }

    // ── 播完自动换片（沿用 v5）────────────────────────────────────
    // 槽位当前内容覆盖表：槽位 id → 换片后的内容。格子的稳定身份是槽位 id
    // （初始 = 首条内容 key），换片只换槽位内容，槽位不动
    var slotContent by gcx.slotContent
    // 每格已播游标：槽位 id → 当前内容在内容池中的下标，换片从这里往后推进
    val slotCursor = gcx.slotCursor
    // 每格已播内容 key 集合：换片优先挑本格没播过的
    val slotPlayed = gcx.slotPlayed
    // 兜底重播计数：整池无片可换时 bump 它，对应格原地重播
    val slotReplay = gcx.slotReplay
    // 被"换片认领"的内容 key：不再生成自己的格子（只在认领它的槽位播），避免同屏撞片
    var claimedKeys by gcx.claimedKeys
    // 挂起等换片的槽位：Feed 模式下格子播完先挂起，等下一页新片到了按序认领
    var pendingAdvance by remember { mutableStateOf(setOf<String>()) }

    // ── T0-2（AUDIT-GAPS3 #2）：内容类型开关 ─────────────────────
    // 分屏自持源（WeightedAggregateFeedSource 直接 nextPage）绕过了 FeedViewModel.loadMore
    // 里的 showPhotos/showAudio/showVideos 过滤——设置里关了"图片"分屏照样冒图片格。
    // 这里在进入分屏时快照一次三个开关（设置页盖栈时本屏不组合，从设置回来即重组重读，
    // 快照天然拿到最新值），过滤落在 visibleItems 与 gridLoadMore 两处（双保险）：
    // 片单模式（customItems）是用户显式攒的内容，不吃这个过滤
    val gridAllowVideos = remember { prefsCoach.showVideos }
    val gridAllowPhotos = remember { prefsCoach.showPhotos }
    val gridAllowAudio = remember { prefsCoach.showAudio }
    // 内容分区 tab 配套（用户反馈"分屏也要配套筛选"）：Feed 顶栏选了"音频"进分屏也只放音频格
    val gridMediaTab by vm.mediaTypeTab.collectAsState()
    // 类型 tab 的分屏翻页游标提升到 VM（vm.gridMediaCursorFor / updateGridMediaCursor）：
    // 与自持源同理跨进出分屏存活（本屏 remember 游标是"每次打开从头拉"的同谋），
    // VM 侧按 tab 归属保存，换 tab 自动作废防"跨 tab 游标串味"

    // Feed 模式下实际入网格的槽位基底：自持源列表 - 已关闭 - 被认领 - 被内容类型开关禁掉的类型
    // 注意：必须 derivedStateOf（只跟踪 State 读取）；remember(keys) 产出的普通 List
    // 被下游 derivedStateOf 读到时不会触发重算——这是"画布全黑/格子和不播"的同一类 bug
    val visibleItems by remember {
        derivedStateOf {
            feedItems.filter {
                it.key !in closedKeys && it.key !in claimedKeys &&
                    (gridMediaTab == -1 || it.mediaType == gridMediaTab) &&
                    when (it.mediaType) {
                        0 -> gridAllowVideos
                        1 -> gridAllowPhotos
                        else -> gridAllowAudio
                    }
            }
        }
    }
    // 槽位基底列表：片单模式直接用 customItems（关格即移除，无需 closedKeys 过滤），
    // Feed 模式用过滤后的 visibleItems
    // State 化：baseByKey/gridSlots 的 derivedStateOf 只跟踪 State 读取，
    // 组合期的普通 val 被 derived 捕获后永世不重算（画布全黑"slots=0 byKey=0"的根因）
    val baseGridItems by remember { derivedStateOf { if (isCustomMode) customItems else visibleItems } }
    // 应用拖拽换序后的槽位 id 顺序：按 orderOverride 排名，新出现的槽位
    // （分页加载进来、不在覆盖表里）按原序追加到尾部（sortedBy 稳定排序保证）
    // 槽位 id 顺序。必须 State 化：下面的 layoutInfo 是 derivedStateOf，
    // 只跟踪 State 读取——如果读普通 List，内容更新后布局永世不重排（画布全黑就是这个 bug）。
    // 用 derivedStateOf 而非 effect+state：读取时同步重算，分页新片到达当帧即参与排位，
    // 不留"effect 晚一拍"的一帧空窗
    val orderedSlotIdsState = remember {
        derivedStateOf {
            val ids = baseGridItems.map { it.key }
            val order = orderOverride
            if (order == null) {
                ids
            } else {
                val rank = order.withIndex().associate { it.value to it.index }
                ids.sortedBy { rank[it] ?: Int.MAX_VALUE }
            }
        }
    }
    // 同样 State 化：下游 derivedStateOf 要跟踪到
    val baseByKeyState = remember { derivedStateOf { baseGridItems.associateBy { it.key } } }
    val baseByKey by baseByKeyState
    // 网格真实展示内容：槽位优先展示换片后的当前内容，未换过片展示基底内容
    val gridSlotsState = remember {
        derivedStateOf {
            orderedSlotIdsState.value.mapNotNull { id ->
                val content = slotContent[id] ?: baseByKey[id]
                if (content == null) null else GridSlot(id, content)
            }
        }
    }
    val gridSlots by gridSlotsState
    // PK 擂台实际渲染的槽位：快照里的槽位可能后来被 × 关掉/片单移除，按现存槽位过滤（保持左右序）
    val pkSlots by remember {
        derivedStateOf { gridSlots.filter { it.slotId in pkSlotIds } }
    }
    // 首次出现的槽位按内容宽高比给默认 span（R217 ⑤ masonry v1）：横版 2×1、
    // 竖版 1×2、方形 1×1——判定收口 GridCanvasModel.aspectSpan 纯函数（一处定义，
    // 快照恢复/拖拽档位上限不受影响：这里只做"没记忆过"的首派默认）
    LaunchedEffect(baseGridItems) {
        baseGridItems.forEach { item ->
            if (item.key !in spans) {
                val span = aspectSpan(item.width, item.height)
                if (span != (1 to 1)) spans[item.key] = span
            }
        }
    }
    // 供长驻 effect / IO 协程读取最新值（LaunchedEffect 重启代价大，用 updatedState 防闭包捕获旧值）
    val latestGridSlotsState = rememberUpdatedState(gridSlots)
    val latestGridSlots by latestGridSlotsState
    val latestIsCustomMode by rememberUpdatedState(isCustomMode)
    val latestCustomItems by rememberUpdatedState(customItems)

    // ── 画布布局与可见性 ─────────────────────────────────────────
    // 流式排位：槽位顺序 × span 表 → 每格的逻辑矩形 + 画布总行数。
    // derivedStateOf 内部读 spans（SnapshotStateMap），span 变化自动重排
    val layoutInfoState = remember {
        derivedStateOf { flowLayout(orderedSlotIdsState.value, spans, CANVAS_COLS) }
    }
    val layoutInfo by layoutInfoState
    val layout = layoutInfo.first
    val totalRows = layoutInfo.second
    // 组合期单元宽/高（px）：内部读 viewportSize（State），旋转触发重组自动拿新值；
    // 画布尺寸、格子偏移/大小、预览框、拖拽幽灵、拖柄坐标统一走这两个值
    val unitW = cellW()
    val unitH = cellH()

    // 可见槽位集合：由 scale/translation/视口尺寸反算可见逻辑范围，
    // 外扩 1 单元预渲染边距（滚动/缩放边缘不闪空）；范围外的格子不 compose
    val visibleSlotIdsState = remember {
        derivedStateOf {
            val vw = viewportSize.width.toFloat()
            val vh = viewportSize.height.toFloat()
            if (vw <= 0f || vh <= 0f) {
                emptySet()
            } else {
                val s = canvasScale
                val left = -canvasTx / s
                val right = (vw - canvasTx) / s
                val top = -canvasTy / s
                val bottom = (vh - canvasTy) / s
                val m = cellPx // 1 单元预渲染边距
                val unitW = cellW()
                val unitH = cellH()
                val out = HashSet<String>()
                for ((id, c) in layoutInfo.first) {
                    val x0 = c.x * unitW
                    val y0 = c.y * unitH
                    val x1 = x0 + c.w * unitW
                    val y1 = y0 + c.h * unitH
                    if (x1 >= left - m && x0 <= right + m && y1 >= top - m && y0 <= bottom + m) {
                        out += id
                    }
                }
                out
            }
        }
    }
    val visibleSlotIds by visibleSlotIdsState

    // 分屏会话已看登记：实际渲染（可见）格的当前内容进 VM 会话双轨——
    // 换片链换上的新片、轮播换掉的旧片都登记，自持源与直拉路径翻页不再供
    // 已展示过的（"游标向前走"的会话内闭环；与 vm.onPage 刻意解耦的口径一致：
    // 分屏播放不污染 Feed 侧观看历史）。
    // R150（B）升级：会话双轨之外同步落 seen_ids.log 持久底账（markGridDisplayedSeen
    // = noteGridDisplayed + 批量落盘）——分屏"显示即看过"：格子可见即记（无播放
    // 阈值，分屏小格本来就看了一眼），退分屏/重启后主 Feed 与分屏都不再供这批；
    // 旧版只记会话内存（重启清零、主侧读不到），是"全局刷新后又推已看"的最大漏账面。
    // 实现刻意不用 snapshotFlow：gridSlots/visibleSlotIds 是多层 derivedStateOf
    // 派生态链，snapshotFlow 观测块读派生态会踩 DerivedSnapshotState.isValid
    // 递归坑（启动即崩实证，与 collect 内是否回读无关）——改成 500ms 轮询 +
    // 指纹比对，有变化才登记；委托属性在协程里读到的恒为当前值，不需要变更驱动。
    // 位置约束：必须在 gridSlots / visibleSlotIds 的声明之后——局部 val 不能前向引用
    LaunchedEffect(Unit) {
        var lastFingerprints: List<String> = emptyList()
        while (true) {
            delay(500)
            val vis = visibleSlotIds
            val contents = gridSlots.filter { it.slotId in vis }.map { it.content }
            val fingerprints = contents.map { it.key + "|" + it.fileUniqueId }
            if (fingerprints != lastFingerprints) {
                lastFingerprints = fingerprints
                vm.markGridDisplayedSeen(contents)
                // R230 ③：择片已看口径随落账刷新（内存缓存读；显示即落账的新条目
                // 最多延迟半秒进入 advanceFromPool 的过滤口径）
                seenFilterIds = withContext(Dispatchers.IO) { gridStore.loadSeenIdentities() }
            }
        }
    }

    // 平移钳制：画布不许留出空白。内容比视口窄时水平居中；高度不足视口时顶对齐
    // （网格从顶部生长）。注意：函数体内只读 state（经委托属性），长驻手势闭包
    // 捕获旧实例也能拿到最新值
    fun clampTranslation(txIn: Float, tyIn: Float, s: Float): Offset {
        val vw = viewportSize.width.toFloat()
        val vh = viewportSize.height.toFloat()
        if (vw <= 0f || vh <= 0f) return Offset(txIn, tyIn)
        val cw = CANVAS_COLS * cellW() * s
        // PK 擂台模式画布固定 PK_CELL_H 行高（两格各 2×2 钉在顶部），其余走流式排位总行数
        val ch = (if (pkMode) PK_CELL_H else layoutInfo.second).coerceAtLeast(1) * cellH() * s
        val x = if (cw <= vw) (vw - cw) / 2f else txIn.coerceIn(vw - cw, 0f)
        val y = if (ch <= vh) 0f else tyIn.coerceIn(vh - ch, 0f)
        return Offset(x, y)
    }

    // 宽度适配：画布逻辑宽正好铺满视口（初始等效"4 单位 = 屏宽"，之后用户捏合自由调）。
    // 横屏时叠加高度约束：按"两行高度 = 视口高"反推 scale 上限，与宽度 fit 取较小值——
    // 横屏视口宽，只按宽度 fit 会把 scale 推得过大，格子被拉得过高，一屏连两行都放不下
    fun refitCanvas() {
        val vw = viewportSize.width.toFloat()
        val vh = viewportSize.height.toFloat()
        if (vw <= 0f) return
        val cw = CANVAS_COLS * cellW()
        var target = vw / cw
        if (isLandscapeNow() && vh > 0f) {
            target = minOf(target, vh / (LANDSCAPE_FIT_ROWS * cellH()))
        }
        canvasScale = target.coerceIn(MIN_SCALE, MAX_SCALE)
        // 初始平移：水平居中（内容窄于视口时 clampTranslation 本来也走居中分支，等价）；
        // 垂直顶对齐，网格从顶部生长
        val cl = clampTranslation((vw - cw * canvasScale) / 2f, 0f, canvasScale)
        canvasTx = cl.x
        canvasTy = cl.y
    }

    // 列数（密度）档位循环：4 列 → 3 列 → 2 列 → 4 列（顶栏工具岛「列数」槽）。
    // 纯新增入口：只写画布缩放/平移状态（经既有 clampTranslation 钳制），
    // 捏合/平移手势与流式排位逻辑一行不动——本质是给连续捏合缩放加三个快捷档位；
    // 画布逻辑列数恒为 CANVAS_COLS，"列数"对用户而言就是一格占屏宽的几分之一
    fun cycleGridDensity() {
        val vw = viewportSize.width.toFloat()
        if (vw <= 0f) return
        val cw = CANVAS_COLS * cellW()
        // 4 列基准与 refitCanvas 完全同口径：横屏叠加"两行高封顶"约束后钳进合法缩放域——
        // 原来只按宽度算 fit，横屏下与 refit 实际结果不一致，当前档定位落空（5% 容差对不上）
        var fit = vw / cw // 恰好 4 列铺满的缩放
        val vh = viewportSize.height.toFloat()
        if (isLandscapeNow() && vh > 0f) {
            fit = minOf(fit, vh / (LANDSCAPE_FIT_ROWS * cellH()))
        }
        fit = fit.coerceIn(MIN_SCALE, MAX_SCALE)
        val steps = floatArrayOf(fit, fit * CANVAS_COLS / 3f, fit * CANVAS_COLS / 2f)
        // 定位当前档位（5% 容差）；用户捏合停在档间时按未入档处理，先回 4 列基准
        val idx = steps.indexOfFirst { kotlin.math.abs(it - canvasScale) / it < 0.05f }
        val next = steps[(idx + 1) % steps.size]
        // 档位上限允许突破 MAX_SCALE：密度档是离散指令（非捏合跟手）。横屏下 4 列基准
        // 常已顶到 MAX_SCALE（如 2560×1080@360dpi：fit≈2.37 被钳到 2.2），若仍按
        // MAX_SCALE 钳，4/3/2 列三档被钳成同一值——"列数"切换零可见变化（P1-8 实测）
        val maxStep = maxOf(MAX_SCALE, fit * CANVAS_COLS / 2f)
        canvasScale = next.coerceIn(MIN_SCALE, maxStep)
        val cl = clampTranslation((vw - cw * canvasScale) / 2f, canvasTy, canvasScale)
        canvasTx = cl.x
        canvasTy = cl.y
    }

    // 命中测试（画布逻辑坐标）：找包含逻辑点 (lx, ly) 的格子
    fun hitTestCell(lx: Float, ly: Float, excludeKey: String): String? {
        val unitW = cellW()
        val unitH = cellH()
        for ((id, c) in layoutInfo.first) {
            if (id == excludeKey) continue
            if (lx >= c.x * unitW && lx < (c.x + c.w) * unitW &&
                ly >= c.y * unitH && ly < (c.y + c.h) * unitH
            ) {
                return id
            }
        }
        return null
    }

    /**
     * 池内循环换片：按本格已播游标 [slotCursor] 往后（取模回绕 = 到底回开头循环）
     * 给槽位挑下一条内容。择档收口 GridCanvasModel.pickAdvanceCandidate 纯函数
     * （单测锚定）：R230 ③ 起首选两档避开已看（展示过且点过播放的不主动再供），
     * 整池无更优候选才兜底回供/原地重播。
     * 整池都在别的格展示（片单比格子还少）或池里只有一条：bump [slotReplay] 原地重播。
     */
    fun advanceFromPool(slotId: String, currentKey: String) {
        val pool = if (latestIsCustomMode) latestCustomItems else feedItems
        if (pool.isEmpty()) return
        if (pool.size == 1) {
            // 池里只有一条：没有"下一个"可言，原地重播
            slotReplay[slotId] = (slotReplay[slotId] ?: 0) + 1
            return
        }
        val played = slotPlayed[slotId] ?: emptySet()
        // 展示中集合读 gridSlots 派生态现算（读取时同步重算，拿到的是当下真实排位）：
        // 批量换片（换一批/翻页兜底）在同一帧内连续调本函数，rememberUpdatedState 版
        // latestGridSlots 要等下次重组才更新，整轮循环读到同一旧值——各格游标相近时
        // 会先后挑中同一条新片 = 同屏撞片（R69 换一批主路径必踩）
        val displayed = gridSlots.mapTo(HashSet()) { it.content.key } - currentKey
        // 游标起点：首次换片时以当前内容在池中的位置起算（找不到则从池头起算）
        val startIdx = slotCursor[slotId]
            ?: pool.indexOfFirst { it.key == currentKey }.coerceAtLeast(0)
        // R230 ③：已看双轨（内存快照，主线程零 IO）参与择档
        val (seenK, seenU) = seenFilterIds
        val pick = pickAdvanceCandidate(pool, startIdx, played, displayed, seenK, seenU)
        if (pick == null) {
            // 整池都在格子上（片单比格子少）：本格原地重播
            slotReplay[slotId] = (slotReplay[slotId] ?: 0) + 1
            Log.i("GridRefresh", "池内循环无片可换原地重播：槽位=${slotId.takeLast(6)} 池=${pool.size}")
            return
        }
        slotCursor[slotId] = pick.second
        slotContent = slotContent + (slotId to pick.first)
        Log.i("GridRefresh", "池内循环换片：槽位=${slotId.takeLast(6)} 新内容=${pick.first.key.takeLast(10)} 池=${pool.size}")
    }

    /**
     * 自持源翻页：IO 协程拉下一页、按 key 去重追加到 [feedItems]。
     * 有格子挂起等换片（[pendingAdvance]）时，新片优先按挂起顺序认领给它们
     * （被认领的进 [claimedKeys]，不再生成自己的格子，避免同屏撞片）；
     * 一页拿不到新片时最多补试到 [GRID_PAGE_MAX_ATTEMPTS] 页（加权混排源可能整页被过滤），
     * 仍空 = 源到底，
     * 挂起的格子回落 [advanceFromPool] 池内循环（到底回开头，允许重播/撞片）。
     * 快照 state 支持跨线程读写；[gridLoading] 做并发去重。片单模式不调本函数。
     *
     * R230 ④ [direction]：BOTTOM=画布底追加（既有口径，平移预取触发）；TOP=画布顶
     * 预补（顶缘 overscroll 触发）——新片前插 [feedItems]/orderOverride，画布顶长出
     * 新内容（触发时视口恒在顶，即拉即见）。[chainDepth]=底部链式复查深度（翻页在途
     * 吞掉预取触发后收工自查近底续翻，上限 3 防失控）。
     */
    fun gridLoadMore(direction: GridLoadDirection = GridLoadDirection.BOTTOM, chainDepth: Int = 0) {
        if (latestIsCustomMode || gridLoading) {
            Log.i("GridRefresh", "翻页被去重跳过：片单=$latestIsCustomMode 翻页中=$gridLoading 挂起=${pendingAdvance.size}")
            return
        }
        gridLoading = true
        Log.i("GridRefresh", "翻页开始：方向=$direction 挂起=${pendingAdvance.size} 池=${feedItems.size}")
        scope.launch(Dispatchers.IO) {
            try {
                var fresh: List<VideoItem>
                var attempts = 0
                while (true) {
                    // 翻页加超时：源卡死时 gridLoading 若永久占锁，之后所有换片链
                    // （播完/轮播/换一拨）都被去重吞掉，格子播完即定格成死画面
                    val page = runCatching {
                        withTimeoutOrNull(PAGE_FETCH_TIMEOUT_MS) {
                            // 类型 tab 配套（用户："分屏也要配套筛选"）：选定类型时走服务端
                            // 媒体过滤（和 Feed 同一条正路），分屏自持源的全类型拉取退后。
                            // R109 #3：搜索源在役（gridSearchSource 非空）时不得被本路径劫持——
                            // 旧判定不看搜索源，搜索中的翻页被类型 tab 直拉劫走，搜索结果
                            // 永远翻不出第二页、灌进来的全是 tab 类型内容；搜索态优先走搜索源
                            if (gridMediaTab != -1 && gridSearchSource == null && gateway is TdLibGateway) {
                                val ids = vm.channels.value.map { it.id }
                                    .ifEmpty { gateway.listChannels().map { it.id } }
                                    .take(40)
                                (gateway as TdLibGateway).fetchAggregatedMedia(
                                    chatIds = ids,
                                    mediaType = gridMediaTab,
                                    cursor = vm.gridMediaCursorFor(gridMediaTab),
                                    limit = 24,
                                ).also { vm.updateGridMediaCursor(gridMediaTab, it.nextCursor) }
                            } else {
                                gridSource.nextPage()
                            }
                        }
                    }.getOrNull()
                    val known = feedItems.mapTo(HashSet()) { it.key }
                    // 已看双轨硬剔除（"已看内容不再出现"开关，prefsCoach.excludeWatched）：
                    // 类型 tab 下本路径走网关直拉（fetchAggregatedMedia），绕开 FeedSource
                    // 与 FeedViewModel 的过滤链，此前是已看内容的直通通道——这里补上与
                    // gridSource 源内硬剔除同一口径（key 精确命中 + fileUniqueId 跨频道同片）；
                    // 底账之外再并上分屏会话已看（vm.gridSeenIdentities：分屏播放不上报
                    // vm.onPage，磁盘底账不含分屏看过的，会话内存轨补上这个洞）；
                    // 搜索源模式跳过（搜索是显式召回，"我就是来找这条的"，与 Feed 侧
                    // 搜索源不吃已看过滤的口径一致）；gridStore 命中内存缓存，IO 线程开销可忽略
                    val (seenK, seenU) = if (gridSource !is SearchFeedSource && prefsCoach.excludeWatched) {
                        val (dk, du) = gridStore.loadSeenIdentities()
                        val (sk, su) = vm.gridSeenIdentities()
                        (dk + sk) to (du + su)
                    } else {
                        emptySet<String>() to emptySet()
                    }
                    // T0-2：翻页入口同样过内容类型开关——被禁类型不进内容池，
                    // 换片认领（claimedKeys）也不会把图片/音频领进格子
                    val filtered = page?.items?.filter {
                        it.key !in known && it.key !in seenK &&
                            (it.fileUniqueId.isEmpty() || it.fileUniqueId !in seenU) &&
                            when (it.mediaType) {
                                0 -> gridAllowVideos
                                1 -> gridAllowPhotos
                                else -> gridAllowAudio
                            }
                    } ?: emptyList()
                    // R149 ⑧ 分屏重复推荐修复：补上主 Feed R126 的全网重发折叠闸。
                    // 分屏自持源虽与主 Feed 同一条 rankWeightedPage（页内文件级去重），
                    // 但翻页收尾绕过了 FeedViewModel.loadMore 的 foldReposts 跨页闸——
                    // 跨页/跨批次/快照复活条目与新页里的同片重发（key 各异、fileUniqueId
                    // 相同）在分屏重复成多格。判据同一 contentFoldId（硬指纹优先，
                    // 无指纹 caption+时长软判据兜底），身份集从当前池现算（含快照
                    // 复活条目），先入池者保留——与主侧"先入列者保留"同口径；
                    // 搜索源同样折叠（主侧口径：一切"刷"型源都吃折叠闸）
                    val foldKnown = feedItems.mapNotNullTo(HashSet()) { contentFoldId(it) }
                    val (foldSurvivors, foldDropped) = foldReposts(filtered, foldKnown)
                    if (foldDropped > 0) {
                        Log.i("GridRefresh", "本页折叠重复 $foldDropped 条（同片多源，与主 Feed 同口径）")
                    }
                    fresh = foldSurvivors
                    if (fresh.isNotEmpty()) {
                        // R230 ④：TOP=前插（画布顶长出，当前视口在顶即拉即见）；BOTTOM=后追
                        feedItems = if (direction == GridLoadDirection.TOP) fresh + feedItems else feedItems + fresh
                    }
                    attempts++
                    Log.i("GridRefresh", "翻页第${attempts}次拉取：方向=$direction 原始=${page?.items?.size ?: -1} 新片=${fresh.size} 挂起=${pendingAdvance.size}")
                    // 拿到新片 / 没有格子等片 / 补试到上限 → 收工。
                    // 上限 [GRID_PAGE_MAX_ATTEMPTS]：有格挂起等片时多补几页——开屏换新风
                    // （进程重启后）前几页可能整页撞上"快照恢复 + 会话已看"被滤空，
                    // 只补试一次会误判"源到底"把挂起格兜回旧片；源真到底时
                    // GatewayFeedSource 对空游标本地秒回空，多补的成本可忽略
                    // R217 ①：画布仍空（feedItems 还没有任何内容）时不算"收工"——
                    // 空画布是最差状态，按 GRID_EMPTY_CANVAS_MAX_ATTEMPTS 继续翻
                    // （首进整页被已见剔除/超时滤空的洞：单发即判到底=白屏空画布）
                    val canvasStillEmpty = feedItems.isEmpty()
                    if (fresh.isNotEmpty() ||
                        (pendingAdvance.isEmpty() && !canvasStillEmpty) ||
                        attempts >= (
                            if (canvasStillEmpty) GRID_EMPTY_CANVAS_MAX_ATTEMPTS
                            else GRID_PAGE_MAX_ATTEMPTS
                            )
                    ) {
                        break
                    }
                }
                gridLoading = false
                // R230 ④ TOP 方向前插配套：拖拽换序表有值时新片 id 同步前插（非空
                // orderOverride 下未登记的新槽位一律沉尾，不前插顶就长不出来）；
                // 顶部补页成功给一闪提示（预补是异步秒级动作，无反馈=死手感）
                if (direction == GridLoadDirection.TOP && fresh.isNotEmpty()) {
                    topGrowFlash = true
                    val cur = orderOverride
                    if (cur != null) orderOverride = fresh.map { it.key } + cur
                }
                // 派生链读取一律挪出手工嵌套快照：withMutableSnapshot 的嵌套快照里求值
                // derivedStateOf 链（latestGridSlots → gridSlots → …）会把派生结果记录
                // 挂到嵌套快照上，主线程 snapshotFlow 的观测快照随后校验该记录即抛
                // "Reading a state that was created after the snapshot was taken" 崩进程
                // （换一批/播完换片实测 100% 必现）——嵌套快照内只写普通状态、不读派生链
                val aliveSlotIds = latestGridSlots.mapTo(HashSet()) { it.slotId }
                // 池内兜底同样读派生链（advanceFromPool 内部读 gridSlots 派生态判撞片），
                // 先在快照里清挂起、记下名单，出快照后再逐格补
                var poolFallback: List<Pair<String, String>>? = null
                // 认领/兜底的成批写入收敛进同一可变快照：避免主线程在两批写入之间
                // 重组出"新片既生成了自己的格子、又出现在挂起格"的一帧重影
                Snapshot.withMutableSnapshot {
                    if (fresh.isNotEmpty() && pendingAdvance.isNotEmpty()) {
                        // 认领：等片的格子按挂起顺序各领一条本页新片
                        // R230 ④：TOP 前插时新页条目在池头（下标 0 起）；BOTTOM 后追时在池尾
                        val base = if (direction == GridLoadDirection.TOP) 0 else feedItems.size - fresh.size
                        var i = 0
                        for (slotId in pendingAdvance.toList()) {
                            if (i >= fresh.size) break
                            if (slotId !in aliveSlotIds) {
                                pendingAdvance = pendingAdvance - slotId // 等片期间格子被关了
                                continue
                            }
                            val pick = fresh[i]
                            slotCursor[slotId] = base + i
                            slotContent = slotContent + (slotId to pick)
                            claimedKeys = claimedKeys + pick.key
                            pendingAdvance = pendingAdvance - slotId
                            Log.i("GridRefresh", "认领：槽位=${slotId.takeLast(6)} 新内容=${pick.key.takeLast(10)}")
                            i++
                        }
                    } else if (fresh.isEmpty() && pendingAdvance.isNotEmpty()) {
                        // 源到底：挂起的格子回池内循环挑片（"回到开头循环"的兜底路径）。
                        // advanceFromPool 读派生链，按上方说明挪出嵌套快照执行
                        val pendings = pendingAdvance
                        pendingAdvance = emptySet()
                        Log.i("GridRefresh", "翻页判定源到底：回池内循环格数=${pendings.size}")
                        poolFallback = pendings.map { it to (slotContent[it]?.key ?: it) }
                    }
                }
                poolFallback?.forEach { (slotId, key) -> advanceFromPool(slotId, key) }
                // 一轮没领完（本页新片比挂起格少，常见于批量换一拨/轮播多格同时挂起）：
                // 源既然还能出新片就接着翻，直到领完；到底由上面兜底分支接管，不会无限翻。
                // 不修的话：没领到片的格子定格在旧内容最后一帧，直到用户滚动/下次轮播才解套
                if (fresh.isNotEmpty() && pendingAdvance.isNotEmpty()) gridLoadMore(direction, chainDepth + 1)
                // R230 ① 底部链式复查：本页有新片且视口仍近底 → 收工自查接着翻——修
                // 「预取触发撞上在途翻页被 gridLoading 去重吞掉、翻页完成后无人补枪」
                // 的供给断链（fresh 空页自然退出，深度上限 3 防失控翻页）
                if (direction == GridLoadDirection.BOTTOM && fresh.isNotEmpty() && chainDepth < 3) {
                    val vh = viewportSize.height.toFloat()
                    if (vh > 0f) {
                        val bottomRow = (((vh - canvasTy) / canvasScale) / cellH()).toInt()
                        if (bottomRow >= layoutInfo.second - LOAD_AHEAD_ROWS) {
                            gridLoadMore(GridLoadDirection.BOTTOM, chainDepth + 1)
                        }
                    }
                }
            } finally {
                // 异常出逃也不能让 gridLoading 永久占锁（幂等赋值）
                gridLoading = false
            }
        }
    }

    /**
     * 格子播完回调（修复"播完就停"）：记录已播，然后给这个槽位换下一条。
     * R230 ⑥ 播完收回本格播放授权：换上的新内容回封面等用户再点（「不自动播」对
     * 换片成立）；PK 擂台两格例外——对打模式的连播语义（播完/投票都换下一对继续播）。
     * 片单模式池子固定 → 直接在片单内按游标循环挑片；
     * Feed 自持源 → 挂起等下一页新片认领（避免和别的格撞片），
     * 源到底时由 [gridLoadMore] 回落池内循环。
     */
    fun onCellEnded(slotId: String, currentKey: String) {
        slotPlayed[slotId] = (slotPlayed[slotId] ?: emptySet()) + currentKey
        if (!(pkMode && slotId in pkSlotIds)) playGrants = playGrants - slotId
        if (latestIsCustomMode) {
            advanceFromPool(slotId, currentKey)
        } else {
            pendingAdvance = pendingAdvance + slotId
            gridLoadMore()
        }
    }

    /**
     * R94 分屏实际观看结算：槽位在播计时停止（停播/播放器归还/换片）时调用——
     * 把本段播放时长累进 [gridWatchAccum]，跨阈值即把内容落进 seen_ids.log
     * 持久底账（[gridWatchReported] 幂等，一条内容一会话只报一次）。
     * 停播 false 上报是格子契约（含归还时补报），故格销毁/换片都能结算到。
     */
    fun settleGridWatch(slotId: String) {
        val (item, startedAt) = gridWatchStart.remove(slotId) ?: return
        val played = android.os.SystemClock.elapsedRealtime() - startedAt
        val total = (gridWatchAccum[item.key] ?: 0L) + played
        gridWatchAccum[item.key] = total
        if (total >= GRID_WATCH_MARK_MS && gridWatchReported.add(item.key)) {
            vm.markGridWatched(item)
        }
    }

    /**
     * 画布记忆落盘（GAPS3 #8）：把当前画布状态收成快照写 grid_canvas.json。
     * 主线程读快照 State、IO 协程写盘（LocalStore 同步阻塞 IO 的纪律）。
     * 槽位只记前 [GRID_CANVAS_MAX_SLOTS] 个；条目本体表含槽位基底 + 换片后的当前内容
     * （+ 片单模式的片单条目），供跨进程恢复时按 fileUniqueId 复活。
     */
    fun persistGridCanvas() {
        val layoutNow = layoutInfo.first
        val capped = latestGridSlots.take(GRID_CANVAS_MAX_SLOTS)
        if (capped.isEmpty()) return // 空画布没有记忆价值，也不许覆盖掉盘上快照
        val items = LinkedHashMap<String, VideoItem>()
        for (s in capped) {
            items[s.content.key] = s.content
            baseByKey[s.slotId]?.let { items.putIfAbsent(it.key, it) }
        }
        if (latestIsCustomMode) for (it in latestCustomItems) items.putIfAbsent(it.key, it)
        val snap = GridCanvasSnapshot(
            scale = canvasScale,
            tx = canvasTx,
            ty = canvasTy,
            slots = capped.map { s ->
                val c = layoutNow[s.slotId]
                GridCanvasSlot(
                    slotId = s.slotId,
                    contentKey = s.content.key,
                    chatId = s.content.chatId,
                    gridX = c?.x ?: 0,
                    gridY = c?.y ?: 0,
                    spanW = c?.w ?: 1,
                    spanH = c?.h ?: 1,
                )
            },
            closedKeys = closedKeys,
            items = items,
            customKeys = if (latestIsCustomMode) latestCustomItems.map { it.key } else emptyList(),
            savedAt = System.currentTimeMillis(),
        )
        scope.launch(Dispatchers.IO) { runCatching { store.saveGridCanvas(snap) } }
    }

    /**
     * 画布记忆恢复（GAPS3 #8）：把磁盘快照套用到视图层状态（仅首次进入分屏调一次）。
     *  - R230 ③ 已看过滤先行：「当前内容」命中 seen 双轨（R150B 显示轨 + R94 真播
     *    ≥2s 轨，同本 seen_ids.log）的槽位不复活——重开分屏不再见到展示过且点过
     *    播放的旧片（「下次不再打扰」）；被滤槽位连同认领内容一起收缩，画布由后续
     *    翻页链自然补位。过滤判定收口 filterRestorableSlots 纯函数（单测锚定）。
     *  - 槽位顺序 → orderOverride（格位由 flowLayout 按"顺序 × span"重算，存的 gridX/Y
     *    仅作记录不直接还原，避免与横屏扁格/新视口的排位口径打架）；
     *  - 基底条目先经 vm.reviveDetachedItems 按 fileUniqueId 换发新 fileId（进程重启后
     *    旧 fileId 已失效），再并入自持源头部——翻页链按 key 去重，与新鲜页撞 key 时
     *    以复活条目为准；救不回的条目（无直链且无 fileUniqueId）直接丢弃，新鲜页会按
     *    同 key 重新供片，槽位经 orderOverride 仍能复位，只是内容换成新片；
     *  - 槽位当前内容（换片后的）写回 slotContent：条目不在了就用同频道未占用的新片
     *    替位，连替位都没有则回落槽位基底内容；换片内容本体进 claimedKeys，不再生成
     *    自己的格子（与运行期认领链同口径，同屏不撞片）；
     *  - 片单模式不恢复内容（片单由容器层持有，进出分屏天然保留）。
     */
    suspend fun restoreCanvasSnapshot(snap: GridCanvasSnapshot) {
        // R230 ③：已看双轨合并（盘账 IO 读 + 会话内存轨），被滤槽位不进恢复链
        val (diskK, diskU) = withContext(Dispatchers.IO) { gridStore.loadSeenIdentities() }
        val (sessK, sessU) = vm.gridSeenIdentities()
        val restorable = filterRestorableSlots(snap.slots, snap.items, diskK + sessK, diskU + sessU)
        if (restorable.size < snap.slots.size) {
            Log.i("GridRefresh", "快照恢复已看过滤：剔除 ${snap.slots.size - restorable.size}/${snap.slots.size} 槽")
        }
        orderOverride = restorable.map { it.slotId }
        for (s in restorable) {
            if (s.spanW > 1 || s.spanH > 1) {
                spans[s.slotId] = s.spanW.coerceIn(1, MAX_SPAN) to s.spanH.coerceIn(1, MAX_SPAN)
            }
        }
        closedKeys = snap.closedKeys
        if (!latestIsCustomMode) {
            // 复活体检走 IO（reviveDetachedItems 内部串行 GetRemoteFile + 本地文件存在性
            // 检查都是磁盘/TDLib 往返，与 LocalStore 读写同一纪律，不占主线程）；
            // R230 ③：只复活可恢复槽位的条目（被滤已看条目不再花 TDLib 往返）
            val reviveList = restorable.flatMap {
                listOfNotNull(snap.items[it.contentKey], snap.items[it.slotId])
            }.distinctBy { it.key }
            val usable = withContext(Dispatchers.IO) {
                vm.reviveDetachedItems(reviveList).filter { item ->
                    // 可用性过滤：直链/content:// 本地视频（已取持久读权限）/本地文件仍在 →
                    // 可用；其余要求 fileUniqueId 非空（复活成功的凭据），都没有的救不回、丢弃
                    val uri = item.playUri
                    when {
                        uri == null -> item.fileUniqueId.isNotEmpty()
                        uri.startsWith("http") || uri.startsWith("asset:///") -> true
                        uri.startsWith("content://") -> true
                        else -> runCatching {
                            java.io.File(uri.removePrefix("file://")).isFile
                        }.getOrDefault(false)
                    }
                }
            }
            if (usable.isNotEmpty()) {
                val baseIds = restorable.mapTo(HashSet()) { it.slotId }
                feedItems = usable + feedItems
                claimedKeys = claimedKeys + restorable
                    .filter { it.contentKey != it.slotId }
                    .map { it.contentKey }
                val byKey = usable.associateBy { it.key }
                val usedKeys = HashSet<String>()
                for (s in restorable) {
                    usedKeys += s.slotId
                    if (s.contentKey == s.slotId) continue
                    var content = byKey[s.contentKey]?.takeIf { it.key !in usedKeys }
                    if (content == null) {
                        // 条目不在了：同频道新片替位（不占别的槽位的基底，避免同屏撞片）
                        content = usable.firstOrNull {
                            it.chatId == s.chatId && it.key !in usedKeys && it.key !in baseIds
                        }
                    }
                    if (content != null) {
                        usedKeys += content.key
                        slotContent = slotContent + (s.slotId to content)
                    }
                }
            }
        }
        // 画布变换：缩放钳到合法范围，平移按当前视口钳制（视口未量出时钳制幂等无害，
        // 视口就绪后既有的 totalRows/viewportSize 钳制 effect 会再校准一次）
        canvasScale = snap.scale.coerceIn(MIN_SCALE, MAX_SCALE)
        val cl = clampTranslation(snap.tx, snap.ty, canvasScale)
        canvasTx = cl.x
        canvasTy = cl.y
    }

    /**
     * 整批换一拨（画布空白处双击 / 顶栏"换一批"钮触发）：当前可见槽位一起换下一条。
     * 先各格记已播，然后——
     * 片单模式：池子固定，各格直接按游标池内循环挑片；
     * Feed 模式（R69 重写）：不再走"挂起等新页认领"链——聚合源翻一页要十秒级
     * （实测约 11.5s），期间画面零变化，用户感知就是"点了没反应"；旧实现自带的
     * 5s×3 补拉循环每轮都被 [gridLoadMore] 的 gridLoading 去重吞掉（在途翻页没回来
     * 就空转超时），撑满 15s 后还会抢在在途翻页落定前把挂起格兜回池内循环，
     * 等真新页到了已无人认领、白拉一页。改成：先各格立刻池内换片（三档择优保证
     * 本格没播过且当前没在任何格展示），点完即变；随后补拉一页入池（未认领新片按
     * 既有翻页语义入列表生成新格），给下一次换一批续上全新片源。
     * 池子供不上片的格（整池都在格子上）挂回 [pendingAdvance]，等补拉页认领。
     * 已在挂起等页的目标格（播完/轮播链）本轮由池直接服务、取消挂起，避免稍后
     * 新页到了又认领一遍（一闪双换）。最后亮一下"换了一拨"中央提示。
     */
    fun refreshAllSlots() {
        // PK 模式下"换了一拨"只换擂台两格（其余槽位未渲染，换了也看不见）
        val targets = latestGridSlots.filter {
            if (pkMode) it.slotId in pkSlotIds else it.slotId in visibleSlotIds
        }
        Log.i("GridRefresh", "换一批触发：目标格数=${targets.size} 可见格数=${visibleSlotIds.size} 总槽位=${latestGridSlots.size} 片单=$latestIsCustomMode")
        if (targets.isEmpty()) return
        for (s in targets) {
            slotPlayed[s.slotId] = (slotPlayed[s.slotId] ?: emptySet()) + s.content.key
        }
        // R230 ⑥ 换一批=显式批量换血：目标格播放授权一并收回（新内容回封面等点播）——
        // 「不自动播」对批量换片同样成立：用户点了换一批=主动结束这一批的播放
        playGrants = playGrants - targets.map { it.slotId }.toSet()
        if (latestIsCustomMode) {
            for (s in targets) advanceFromPool(s.slotId, s.content.key)
        } else {
            pendingAdvance = pendingAdvance - targets.map { it.slotId }
            var instant = 0
            val needFresh = mutableListOf<String>()
            for (s in targets) {
                val before = s.content.key
                advanceFromPool(s.slotId, before)
                if ((slotContent[s.slotId]?.key ?: s.slotId) == before) {
                    // 池内挑不到（整池都在格子上）：挂起等下方补拉的新页认领
                    needFresh += s.slotId
                } else {
                    instant++
                    Log.i("GridRefresh", "即时换片：槽位=${s.slotId.takeLast(6)} ${before.takeLast(10)}→${(slotContent[s.slotId]?.key ?: "").takeLast(10)}")
                }
            }
            if (needFresh.isNotEmpty()) pendingAdvance = pendingAdvance + needFresh
            Log.i("GridRefresh", "换一批即时完成：即换=$instant 等新页=${needFresh.size}")
            // 后台补拉一页入池：本轮消耗了池内未展示余量，给下一次换一批续新片
            // （内部 gridLoading 去重；pendingAdvance 非空时新页优先认领给供不上的格）
            gridLoadMore()
        }
        shuffleFlash = true
    }

    /**
     * 自动轮播一拍：给所有"没在播"的可见格换下一条。
     * 正在播放未播完的格（[playingNow] 报 true）不打断；
     * R230 ⑥ 授权格（含手动暂停的）不偷换——那是用户点过播放选中的内容；
     * ENDED 后未再点播 / 从未授权的可见格才会被换掉。
     * 换片路径与单格播完一致：片单池内循环，Feed 挂起等新页认领。
     */
    fun rotateIdleSlots() {
        // 擂台两格是绑定的"一对"：换片只能走投票链（两格一起换），
        // 轮播单独换掉其中一格会拆对、破坏对打语义
        if (pkMode) return
        val targets = latestGridSlots.filter {
            it.slotId in visibleSlotIds && playingNow[it.slotId] != true &&
                it.slotId !in playGrants
        }
        if (targets.isEmpty()) return
        if (latestIsCustomMode) {
            for (s in targets) advanceFromPool(s.slotId, s.content.key)
        } else {
            pendingAdvance = pendingAdvance + targets.map { it.slotId }
            gridLoadMore()
        }
    }

    // 自动轮播节拍器：开启后每 AUTO_ROTATE_INTERVAL_MS 触发一拍；关闭即协程退出。
    // 可见集/在播表都是快照 State，长驻协程里每次读到的是当时最新值，无需重启 effect
    LaunchedEffect(autoRotate) {
        if (!autoRotate) return@LaunchedEffect
        while (autoRotate) {
            delay(AUTO_ROTATE_INTERVAL_MS)
            rotateIdleSlots()
        }
    }

    // "换了一拨"提示一闪而过
    LaunchedEffect(shuffleFlash) {
        if (shuffleFlash) {
            delay(800)
            shuffleFlash = false
        }
    }

    // R230 ④ 顶部补页成功提示一闪而过（预补是异步秒级动作，无反馈=死手感）
    LaunchedEffect(topGrowFlash) {
        if (topGrowFlash) {
            delay(800)
            topGrowFlash = false
        }
    }

    // 焦点必须落在当前网格内，否则回落到首格（关格/切源/片单移除后旧焦点可能已消失）；
    // PK 模式焦点只允许落在擂台两格之一（旧焦点不在对上时回落左格）
    val effectiveFocusedKey =
        if (pkMode) {
            if (focusedKey != null && focusedKey in pkSlotIds &&
                gridSlots.any { it.slotId == focusedKey }
            ) {
                focusedKey
            } else {
                pkSlots.firstOrNull()?.slotId
            }
        } else if (focusedKey != null && gridSlots.any { it.slotId == focusedKey }) {
            focusedKey
        } else {
            gridSlots.firstOrNull()?.slotId
        }
    // R278 项2：迁出的画布视口格内事件回调要在点击时刻现读焦点（非组合期快照），
    // 包一层 updatedState 透传（主函数本地消费仍用上行组合期 val，语义不变）
    val effectiveFocusedKeyState = rememberUpdatedState(effectiveFocusedKey)

    // 可见才播→R230 ⑥ 改为「授权才播」：可播集 = 播放授权集（grant，用户点过播放
    // 的格），PK 模式 = 擂台两格。授权格常驻组合（渲染循环放行），滑出可见区播放
    // 不停——滚动/缩放只改可见集，不再触碰可播集（实锤5「滑动改变播放内容」的结构
    // 性根治：旧版可见集 top-N 驱动 acquire，滚动即换血可播集 → 池淘汰+重挂+重拉流
    // 风暴，在播格被打断换片）。授权数 ≤ MAX_GRID_PLAYERS=池容 → acquire 永不满员
    // 淘汰，R149⑦ 家族淘汰 bug 结构性不可达。derived 内只读快照 State。
    val playableKeysState = remember {
        derivedStateOf {
            if (pkMode) {
                // 只有擂台两格可播，其余槽位 playable 翻 false（格内归还播放器、
                // 画面回落封面渲染，背景音随之全停）；退出 PK 自动恢复重 acquire
                val out = LinkedHashSet<String>()
                for (slot in gridSlots) {
                    if (slot.slotId in pkSlotIds) out += slot.slotId
                    if (out.size >= 2) break
                }
                out
            } else {
                // R230 ⑥：正常模式可播集=授权集（授权序即起播错峰序）；
                // 授权只由 onRequestPlay 发放（点播/重试），不再由可见性驱动
                LinkedHashSet(playGrants)
            }
        }
    }
    val playableKeys by playableKeysState

    // 播放器池淘汰自愈代际：playableKeys 成员变化时 +1。
    // PlayerPool 满员时淘汰的是"最早 acquire"的 busy 实例（插入序 FIFO，通常是最先
    // 起播的焦点格！），且可见集变化的重组波里新格 acquire 先于旧格 dispose，
    // busy 可能瞬时超额触发误淘汰——受害格自身不知情，仍引用已 release 的实例，
    // 画面定格成死图。代际变化强制全部可见格重组并重走 acquire（busy 命中幂等
    // 返回原实例，零代价；被误淘汰的格拿到新实例，下一帧自愈）
    var poolEpoch by gcx.poolEpoch
    LaunchedEffect(playableKeys) { poolEpoch++ }

    /**
     * 一键全停/全播（顶栏圆形钮，R230 起按授权格口径；R380 收编池外自管音频格）。
     * 全停：pauseAll——含划出可见区、尚未归还的瞬态 key（不回收播放器，画面定格）
     * **与池外自管音频格**（PlayerPool externals 登记收编进广播域，修「全部暂停后
     * 音频格照播」）；被拉停的音频格同时落 [gcx.audioPausedByAll] 快照（恢复面）。
     * 全播：对全部授权格 peek+play（授权=用户点过播放的格；未授权格保持封面——
     * 「全部播放」不是「全部自动开播」。R230 安全口径实勘保留：MAX_GRID_PLAYERS
     * 硬解并发上限与「暂停/播放只由用户手动触发」纪律使然，视频侧零改）；池外
     * 音频格按快照恢复——只恢复被全停拉停且内容未换的（换片后的新内容不自动开播）。
     * R230 ② allPaused 状态退役：暂停态一律以格内实际播放状态呈现（isPlaying/
     * buffering），按钮语义由 anyPlaying 派生（R380 起意图口径，见声明处判词）。
     */
    fun toggleAllPlayback() {
        if (anyPlaying) {
            // R380 快照被本次全停拉停的池外音频格（槽位 → 内容 key）。判据用意图口径
            // （缓冲中的音频=会被全停拉停=该进恢复面）；视频格走授权恢复不需要快照
            audioPausedByAll = gridSlots.filter {
                it.content.mediaType == 2 && playingIntentNow[it.slotId] == true
            }.associate { it.slotId to it.content.key }
            playerPool.pauseAll()
        } else {
            for (id in playGrants) playerPool.peek("grid:$id")?.play()
            // R380 池外音频格恢复：只恢复快照内、槽位仍在且内容未换的（换片/关格后
            // 的新格不自动开播）；恢复后快照清空（一轮全停一轮恢复，不粘滞——
            // R230 ② allPaused 粘滞病不复活）
            for ((id, contentKey) in audioPausedByAll) {
                val slot = gridSlots.firstOrNull { it.slotId == id } ?: continue
                if (slot.content.key == contentKey) {
                    playerPool.peekExternal(gridAudioExternalKey(id))?.play()
                }
            }
            audioPausedByAll = emptyMap()
        }
    }

    /**
     * 顶栏"擂台"钮：进入/退出 PK 对打模式。
     * 进入：从当前可见槽位快照前 2 个钉成左右两格（可见集尚未算出时回落槽位序前 2），
     * 顺手退掉专注模式（专注会让 playable 只剩焦点格，与擂台双播互斥）；
     * 退出：清空擂台对，playableKeys 回落正常可见集，画布恢复正常流式排位。
     */
    fun togglePkMode() {
        if (pkMode) {
            pkMode = false
            pkSlotIds = emptyList()
        } else {
            val vis = visibleSlotIds
            // 可见格优先，不足两格用槽位序补足（可见集恰好 1 格时 ifEmpty 兜底不会触发）
            val picked = (gridSlots.filter { it.slotId in vis } + gridSlots)
                .distinctBy { it.slotId }.take(2).map { it.slotId }
            // 不足两格不进擂台：只捡到一个槽位时画布右半永远空黑，是残局不是对打
            if (picked.size < 2) {
                Toast.makeText(context, "至少需要两格内容才能打擂", Toast.LENGTH_SHORT).show()
                return
            }
            pkSlotIds = picked
            pkMode = true
            focusMode = false
            // R230 ⑥：进擂台=显式模式动作，授权整批换成擂台两格（对打自动连播，
            // 播完/投票都换下一对继续）；非擂台格不被组合，播放器随销毁自然归还
            playGrants = picked.toCollection(LinkedHashSet())
        }
    }

    /**
     * 顶栏"专注"钮：进入/退出专注模式。
     * 专注与擂台互斥（playableKeys 擂台分支优先，专注在擂台里是个点了没反应的哑开关）：
     * 进专注先退擂台，与 togglePkMode 里"进擂台顺手退专注"对称。
     * R230 ⑥ 起：进专注把非焦点授权格 pause（不回收、画面定格——"只听一格"）；
     * 退出不自动续（点格/全播手动恢复）——「不自动播」对模式退出同样成立。
     */
    fun toggleFocusMode() {
        if (!focusMode && pkMode) {
            pkMode = false
            pkSlotIds = emptyList()
        }
        focusMode = !focusMode
        if (focusMode) {
            val keep = effectiveFocusedKey
            for (id in playGrants) if (id != keep) playerPool.pause("grid:$id")
        }
    }

    /**
     * PK 投票：点了哪边 → Toast 报"已投给 XX 频道"，同时调 vm.recordPkVote 喂算法
     * （赢家频道 +0.4 / 输家频道 -0.2，走互动加分表，封顶逻辑沿用），
     * 然后擂台两格一起记已播、走现有换片链换下一对——
     * 片单模式池内循环（advanceFromPool），Feed 模式挂 pendingAdvance 等新页认领，
     * 与 rotateIdleSlots 同款的批量换片路径（gridLoadMore 内部有 gridLoading 去重）。
     */
    fun onPkVote(winnerSlotId: String) {
        val pair = pkSlotIds.mapNotNull { id -> latestGridSlots.firstOrNull { it.slotId == id } }
        val winner = pair.firstOrNull { it.slotId == winnerSlotId } ?: return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        Toast.makeText(context, "已投给「${winner.content.channelTitle}」", Toast.LENGTH_SHORT).show()
        // 投票喂算法：赢家 +0.4 / 输家 -0.2（vm 内部走互动加分表；本地文件 chatId=0 会被跳过）
        val loser = pair.firstOrNull { it.slotId != winnerSlotId }
        vm.recordPkVote(winner.content.chatId, loser?.content?.chatId ?: 0L)
        for (s in pair) {
            slotPlayed[s.slotId] = (slotPlayed[s.slotId] ?: emptySet()) + s.content.key
        }
        if (latestIsCustomMode) {
            for (s in pair) advanceFromPool(s.slotId, s.content.key)
        } else {
            pendingAdvance = pendingAdvance + pair.map { it.slotId }
            gridLoadMore()
        }
    }

    // 擂台任一格被 × 关/片单移除（不足两格对打不成立）时自动退出 PK，
    // 避免"只剩一格钉在左半、右半空黑"的残局擂台
    LaunchedEffect(pkMode, pkSlots) {
        if (pkMode && pkSlots.size < 2) {
            pkMode = false
            pkSlotIds = emptyList()
        }
    }

    // R230 ⑥ 授权集收编：授权槽位消失（不经 closeCell 的移除路径，如片单条目被
    // 别处移除/槽位收缩）时摘掉幽灵授权——防死授权占 4 路名额挡新点播
    LaunchedEffect(gridSlots) {
        val alive = gridSlots.mapTo(HashSet()) { it.slotId }
        if (playGrants.any { it !in alive }) {
            playGrants = playGrants.filterTo(LinkedHashSet()) { it in alive }
        }
    }

    // 切源（搜索新词）/ 首次进入：清全部视图层痕迹与换片状态，列表清零重拉，
    // span/换序一并清空，画布重新按视口宽适配
    LaunchedEffect(gridSource) {
        closedKeys = emptySet()
        focusedKey = null
        orderOverride = null
        slotContent = emptyMap()
        slotCursor.clear()
        slotPlayed.clear()
        slotReplay.clear()
        claimedKeys = emptySet()
        pendingAdvance = emptySet()
        spans.clear()
        entranceSeen.clear()
        feedItems = emptyList()
        // R230 ⑥：切源=整批内容作废，授权一并清零（旧授权指向已消失的槽位）
        playGrants = linkedSetOf()
        if (!canvasRestoreChecked) {
            // 画布记忆恢复（GAPS3 #8）：首次进入分屏读回上次退出落盘的快照；
            // 之后切源（搜索新词）不再恢复——新词对应新画布，旧快照语义不适用
            canvasRestoreChecked = true
            val snap = withContext(Dispatchers.IO) { store.loadGridCanvas() }
            if (snap != null) {
                restoreCanvasSnapshot(snap)
                canvasTransformRestored = true
                // 开屏换新风（"每次打开都是那些视频"修复）：快照恢复的是布局/槽位序，
                // 内容不原样定格——恢复批次整体登记进分屏会话已看（翻页不再供这批，
                // 进程重启后首两页与快照重合的部分也因此被滤掉、补试链自动翻到更深处），
                // 前 OPEN_REFRESH_SLOT_COUNT 个槽位记已播并挂起等第一页新片认领
                // （下方 gridLoadMore 走既有换片链）；弱网/到底时链内兜底回池内循环，
                // 布局仍在、格子可能暂回旧片，不阻塞进屏
                // R150（B）：恢复批次同步落 seen 底账（显示即看过）——快照复活的
                // 也是"画布上显示过"的内容，主 Feed 侧同样不再推
                if (!latestIsCustomMode) {
                    // R153 走查实锤 bug：此处必须直读 gridSlots 派生态（读取时同步重算，
                    // 拿到快照恢复后的真实槽位）——latestGridSlots 是 rememberUpdatedState，
                    // restoreCanvasSnapshot 刚写完 feedItems/slotContent，重组还没跑，
                    // 读到的仍是恢复前的空列表：恢复批次登记落空 + 前 OPEN_REFRESH_SLOT_COUNT
                    // 个槽位没挂起等片，开屏换新风整段失效（旧内容滞留到自然播完才换，
                    // "每次打开都是那些视频"的残留面）。实例1 日志实锤：恢复 47 条后
                    // 紧接的翻页「挂起=0 池=47」（本应挂起=8）。
                    val restoredSlots = gridSlots
                    vm.markGridDisplayedSeen(restoredSlots.map { it.content })
                    val head = restoredSlots.take(OPEN_REFRESH_SLOT_COUNT)
                    for (s in head) {
                        slotPlayed[s.slotId] = (slotPlayed[s.slotId] ?: emptySet()) + s.content.key
                    }
                    pendingAdvance = pendingAdvance + head.map { it.slotId }
                    Log.i("GridRefresh", "开屏换新风登记：恢复槽位=${restoredSlots.size} 挂起=${head.size}")
                }
            }
            // 无快照：维持原有"按视口宽适配"的初始变换
            if (!canvasTransformRestored) refitCanvas()
        } else {
            // 切源（搜索新词）：维持原有重适配
            refitCanvas()
        }
        if (!latestIsCustomMode) gridLoadMore()
    }

    // 退出片单模式时自持源可能还是空的：补第一页
    LaunchedEffect(isCustomMode) {
        if (!isCustomMode && feedItems.isEmpty()) gridLoadMore()
    }

    // 平移即翻页（自持源）：可见范围底缘距画布底不足 LOAD_AHEAD_ROWS 行时预取下一页
    // （gridLoadMore 内部有 gridLoading 去重）。
    // 观看记录 vm.onPage 刻意不再上报：网格内容已与 vm.items 解耦，
    // 拿网格位置去写 Feed 的 currentIndex/history 会记错对象
    LaunchedEffect(Unit) {
        snapshotFlow {
            val vh = viewportSize.height.toFloat()
            // 横屏单元更扁：可见底缘行号必须按当前单元高折算，否则翻页预取时机跑偏
            if (vh <= 0f) -1 else (((vh - canvasTy) / canvasScale) / cellH()).toInt()
        }.collect { bottomRow ->
            if (bottomRow < 0) return@collect
            // 片单模式内容固定，不做分页预取
            if (!latestIsCustomMode && bottomRow >= layoutInfo.second - LOAD_AHEAD_ROWS) {
                gridLoadMore()
            }
        }
    }

    // 内容变高 / 视口变化 / 进出 PK 后，当前平移可能越界（如翻页前拖到了底）：重新钳制
    LaunchedEffect(totalRows, viewportSize, pkMode) {
        val cl = clampTranslation(canvasTx, canvasTy, canvasScale)
        canvasTx = cl.x
        canvasTy = cl.y
    }

    // 画布记忆防抖落盘（GAPS3 #8）：可持久化状态（槽位/顺序/跨度/关格/缩放平移）任一变化
    // bump 脏计数 → 落盘 effect 以脏计数为 key 重启 1s 静默计时（新变化不断打断旧计时 = 防抖），
    // 静默到期才写盘。恢复快照未落定（canvasRestoreChecked=false）前不落盘：
    // 防止把"刚进分屏的空画布"盖掉磁盘上待恢复的快照
    LaunchedEffect(Unit) {
        snapshotFlow {
            GridCanvasDirtyTick(
                scale = canvasScale,
                tx = canvasTx,
                ty = canvasTy,
                order = orderedSlotIdsState.value,
                spans = spans.toMap(),
                closed = closedKeys,
                contents = gridSlots.map { it.slotId to it.content.key },
                customMode = isCustomMode,
            )
        }.collect { canvasDirtyTick++ }
    }
    LaunchedEffect(canvasDirtyTick) {
        if (canvasDirtyTick <= 0) return@LaunchedEffect
        delay(GRID_CANVAS_SAVE_DEBOUNCE_MS)
        if (canvasRestoreChecked) persistGridCanvas()
    }

    // 捏合徽章自动消失：detectTransformGestures 没有结束回调，
    // 用"最后一次含缩放的手势事件距今超过 220ms"判定捏合结束
    LaunchedEffect(pinching) {
        if (pinching) {
            while (pinching) {
                delay(240)
                if (System.currentTimeMillis() - lastPinchAt > 220) pinching = false
            }
        }
    }

    // HIGH-8 修复：分屏后台行为通道——照 FeedScreen 模式向容器注册"暂停全部格子"与
    // "当前条目"提供者（MainActivity.onStop 按 backgroundAction 分派：1=暂停 → pauseAllPlayback；
    // 2=自动弹悬浮窗 → popoutCurrent 取焦点格内容入悬浮栈并暂停）。
    // 此前分屏零生命周期处理：退后台最多 6 路播放器继续解码出声，三种后台行为在分屏全部失效。
    // lambda 内现读快照 State（focusedKey/gridSlots 均为 remember 代理，闭包内读取即最新值），
    // 无需随焦点/换片重注册；声明在本 effect、释放在下 effect，dispose 顺序先注销后释放池。
    val container = (context.applicationContext as dev.tgfeed.App).container
    DisposableEffect(Unit) {
        val pauser: () -> Unit = {
            // 暂停全部持有播放器的格（pauseAll 比逐 playableKeys 彻底：划出可见区、
            // 尚未归还的瞬态 key 也覆盖到）。R230 ② 起不再置全局暂停标记——旧口径
            // 的粘滞标记是「回前台手动点播被打回+⏸ 常驻」实锤根源；回前台由点格/
            // 顶栏「全播」手动恢复，「不自动播」对后台恢复同样成立。
            // R380：pauseAll 已含池外自管音频格（PlayerPool externals 收编）——
            // 后台暂停对音频格同样生效（修「分屏退后台音频格继续出声」同病位）
            playerPool.pauseAll()
        }
        val provider: () -> VideoItem? = {
            // 弹悬浮窗取焦点格当前内容（无焦点回落首格，与 effectiveFocusedKey 同口径）
            val fk = focusedKey?.takeIf { k -> gridSlots.any { it.slotId == k } }
                ?: gridSlots.firstOrNull()?.slotId
            gridSlots.firstOrNull { it.slotId == fk }?.content
        }
        container.currentPlayerPauser = pauser
        container.currentItemProvider = provider
        onDispose {
            // 只注销自己注册的（=== 比较）——防其他页晚到的 dispose 清掉别人的注册
            if (container.currentPlayerPauser === pauser) container.currentPlayerPauser = null
            if (container.currentItemProvider === provider) container.currentItemProvider = null
        }
    }

    // 退出多窗格：释放全部播放器资源
    DisposableEffect(Unit) {
        onDispose { playerPool.releaseAll() }
    }

    // 退出分屏（顶栏退出/系统返回/全屏单播 → onExit → 本组合销毁）兜底落盘一次：
    // 防抖窗口内可能有未落盘的最后改动；恢复未完成的早退不覆盖盘上快照
    DisposableEffect(Unit) {
        onDispose { if (canvasRestoreChecked) persistGridCanvas() }
    }

    fun submitSearch() {
        val q = searchText.trim()
        if (q.isEmpty()) return
        keyboard?.hide()
        // 网格自持源整体换成搜索源（只影响网格，Feed 侧状态不动）；
        // 覆盖的是本屏会话语义的 gridSearchSource，VM 托管的加权源游标不动——
        // 搜索结束后下次进分屏仍接着上次位置拉新
        gridSearchSource = SearchFeedSource(gateway, q)
    }

    // 选本地视频：OpenDocument 返回 content:// uri，先拿持久读权限（重启后仍可播），
    // 再构造 local: 前缀的 VideoItem 交给容器加入片单（去重由容器做）。
    // Media3 直接支持 content:// 播放，无需拷贝文件。
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            // 尽力查询文件名做 caption；拿不到（provider 不配合）就留空
            val displayName = runCatching {
                context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
            onAddCustom(
                VideoItem(
                    key = "local:$uri",
                    chatId = 0,
                    messageId = 0,
                    channelTitle = "本地文件",
                    caption = displayName ?: "",
                    durationSec = 0,
                    width = 0,
                    height = 0,
                    fileId = -1,
                    sizeBytes = 0,
                    playUri = uri.toString(),
                ),
            )
        }
    }

    // 「从片单添加」：把命名片单的全部条目倒入分屏片单（接现有 gridPlaylist 逻辑——
    // onAddCustom 即容器层 addToGrid，去重由容器做；片单非空即切自定义模式，画布立刻有内容）。
    // LocalStore.loadPlaylistItems 是同步 IO，按 IO 纪律切 Dispatchers.IO 读取
    val addPlaylistToCanvas: (Playlist) -> Unit = { pl ->
        scope.launch {
            val items = withContext(Dispatchers.IO) {
                runCatching { store.loadPlaylistItems(pl.id) }.getOrDefault(emptyList())
            }
            if (items.isEmpty()) {
                Toast.makeText(context, "「${pl.name}」是空片单", Toast.LENGTH_SHORT).show()
            } else {
                items.forEach(onAddCustom)
                Toast.makeText(context, "已从「${pl.name}」添加 ${items.size} 条", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── 拖拽换格回调（由格子手势层上抛，坐标系已换成画布）──────────
    val onCellDragStart: (String) -> Unit = { key ->
        // PK 擂台模式格位钉死左右对半：禁用长按拖拽换位/悬浮落区（落点红框/幽灵都不出）
        if (!pkMode) {
            dragKey = key
            dragAccum = Offset.Zero
            dragTargetKey = null
            dragInPopoutZone = false
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    val onCellDrag: (String, Offset) -> Unit = onCellDrag@{ key, delta ->
        if (dragKey != key) return@onCellDrag
        dragAccum += delta
        val cell = layoutInfo.first[key] ?: return@onCellDrag
        // 指针逻辑坐标 = 布局格心 + 手指累计位移 ÷ 缩放（屏幕位移折算回逻辑坐标）
        val cx = (cell.x + cell.w / 2f) * cellW() + dragAccum.x / canvasScale
        val cy = (cell.y + cell.h / 2f) * cellH() + dragAccum.y / canvasScale
        // 悬浮落区判定（屏幕坐标系）：指针屏幕 y 进入视口顶部 15% 即视为要悬浮
        val vh = viewportSize.height.toFloat()
        if (vh > 0f) {
            val pointerScreenY = canvasTy + cy * canvasScale
            val inZone = pointerScreenY < vh * POPOUT_ZONE_FRACTION
            if (inZone != dragInPopoutZone) {
                dragInPopoutZone = inZone
                // 进出落区各给一次轻震：不看屏幕也能感知"再松手就是悬浮"
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
        val target = hitTestCell(cx, cy, key)
        if (target != dragTargetKey) {
            dragTargetKey = target
            // 命中目标变化给一次轻震，松手前就能感知"会跟谁换"
            if (target != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    // 返回值：true = 本次松手按"悬浮落区"处理掉了——格内据此不再弹长按菜单
    // （原逻辑两个一起出：落区判定看的是格心+位移，顶部行的格子长按后哪怕原地
    // 微移松手也会同时弹悬浮窗+菜单）
    val onCellDragEnd: (String) -> Boolean = { key ->
        var handled = false
        // 悬浮落区需叠加位移门槛（与跟手幽灵出现阈值同口径）：原地长按等菜单时，
        // 靠顶部的格子格心本就在落区内，没有门槛会"想弹菜单却飞出去一个悬浮窗"
        val popoutThresholdPx = with(density) { 24.dp.toPx() }
        if (dragInPopoutZone && dragAccum.getDistance() > popoutThresholdPx) {
            // 拖出画布到顶部落区松手：当前内容弹悬浮窗（容器层接线），
            // 槽位留下并复用播完换片链自动换下一条（记已播 + 换片）
            val slot = latestGridSlots.firstOrNull { it.slotId == key }
            if (slot != null) {
                handled = true
                // R230 追加4 小窗连播队列：弹出时带上分屏池的后续供给（池内视频，
                // 只排除正在授权播放中的内容——封面格内容可入队；16384 实锤：按
                // 「画布展示过」全排除会把队列饿死，池≈展示集时窗播完无下文）——
                // 小窗播完按「播完自动连播」开关逐条原位续播
                (context.applicationContext as? dev.tgfeed.App)?.container?.supplyFloatingQueue(
                    slot.content.key,
                    buildFloatingQueue(
                        pool = feedItems,
                        playingKeys = gridSlots
                            .filter { it.slotId in playGrants }
                            .mapTo(HashSet()) { it.content.key },
                        excludeKey = slot.content.key,
                    ),
                )
                onPopoutToFloating(slot.content)
                onCellEnded(key, slot.content.key)
            }
        } else {
        val target = dragTargetKey
        if (target != null) {
            // 交换两格位置：只写视图层 orderOverride（槽位 id 顺序），片单/源数据不动
            val current = latestGridSlots.map { it.slotId }
            val i = current.indexOf(key)
            val j = current.indexOf(target)
            if (i >= 0 && j >= 0) {
                orderOverride = current.toMutableList().apply {
                    this[i] = current[j]
                    this[j] = current[i]
                }
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
        }
        dragKey = null
        dragAccum = Offset.Zero
        dragTargetKey = null
        dragInPopoutZone = false
        handled
    }
    val onCellDragCancel: (String) -> Unit = {
        dragKey = null
        dragAccum = Offset.Zero
        dragTargetKey = null
        dragInPopoutZone = false
    }

    // 系统返回键：退出分屏回 Feed（与顶栏退出钮同路径：先释放全部播放器再回调）。
    // 放在最外层：格子手势层/画布平移捏合都只消费触摸事件，不消费返回键，
    // BackHandler 挂组合根即可稳定接住系统返回，不与任何手势冲突
    BackHandler {
        playerPool.releaseAll()
        onExit()
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // 顶栏：工具岛胶囊容器 + 独立退出钮 + 片单胶囊（抽成独立组合函数：
        // GridScreen 主函数曾大到触发 Android 12 的 DEX VerifyError）
        GridTopBar(
            isCustomMode = isCustomMode,
            customCount = customItems.size,
            searchExpanded = searchExpanded,
            searchText = searchText,
            onSearchExpandedChange = { searchExpanded = it },
            onSearchTextChange = { searchText = it },
            onSubmitSearch = { submitSearch() },
            // R230 ② allPaused 状态退役：按钮语义派生自 anyPlaying（在播=可全停；
            // 全停/无播=可全播），无粘滞标记
            allPaused = !anyPlaying,
            anyPlaying = anyPlaying,
            onToggleAllPlayback = { toggleAllPlayback() },
            autoRotate = autoRotate,
            onToggleAutoRotate = { autoRotate = !autoRotate },
            focusMode = focusMode,
            onToggleFocusMode = { toggleFocusMode() },
            pkMode = pkMode,
            onTogglePkMode = { togglePkMode() },
            muteAll = muteAll,
            onToggleMuteAll = { muteAll = !muteAll },
            onExitGrid = { playerPool.releaseAll(); onExit() },
            onPickLocal = { pickVideo.launch(arrayOf("video/*")) },
            // 清空片单：customItems 是 SnapshotStateList 本体（App.kt gridPlaylist 透传），
            // 边迭代边删会抛 ConcurrentModificationException（≥2 条必崩，R104/H1）。
            // 先 toList() 拷贝快照，再逐个移除。
            onClearCustom = { customItems.toList().forEach { onRemoveCustom(it) } },
            // P0-2 新增槽位：从片单添加（弹命名片单选择器）/ 列数（密度档位循环）
            onAddFromPlaylist = { showPlaylistPicker = true },
            onCycleColumns = { cycleGridDensity() },
            // 换一批：可见格一起走换片链（与画布空白双击同一动作）
            onRefreshSlots = { refreshAllSlots() },
        )

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                // 自持源为空：加载中 / 空结果（片单模式不走这里，customItems 非空必有内容）
                !isCustomMode && feedItems.isEmpty() ->
                    // R217 ① 附带减负：空态分支抽独立组合函数——主函数逼近 DEX
                    // 校验上限（R129 同病：GridCellDispatch 抽取后二次复发，16384
                    // 实测 VerifyError），新增「重新加载」次级动作后需要瘦身
                    GridFeedEmptyArea(
                        uiScale = uiScale,
                        gridLoading = gridLoading,
                        secondaryText = "重新加载",
                        onSecondary = { gridLoadMore() },
                        onAddFromPlaylist = { showPlaylistPicker = true },
                    )
                // 所有格都被 × 关了：同一副空画布引导 + 次级文字动作「恢复全部格子」
                // （仅 Feed 模式有 closedKeys 概念；恢复入口保留，不丢原有逃生路径）
                !isCustomMode && visibleItems.isEmpty() -> GridFeedEmptyArea(
                    uiScale = uiScale,
                    gridLoading = false,
                    secondaryText = "恢复全部格子",
                    onSecondary = { closedKeys = emptySet() },
                    onAddFromPlaylist = { showPlaylistPicker = true },
                )
                else -> GridCanvasViewport(
                    gcx = gcx,
                    isCustomModeState = isCustomModeState,
                    gridSlotsState = gridSlotsState,
                    latestGridSlotsState = latestGridSlotsState,
                    baseByKeyState = baseByKeyState,
                    layoutInfoState = layoutInfoState,
                    visibleSlotIdsState = visibleSlotIdsState,
                    playableKeysState = playableKeysState,
                    effectiveFocusedKeyState = effectiveFocusedKeyState,
                    likesState = likesState,
                    favoritesState = favoritesState,
                    watchedIdsState = watchedIdsState,
                    downloadsState = downloadsState,
                    muteAllState = muteAllState,
                    density = density,
                    haptic = haptic,
                    context = context,
                    vm = vm,
                    gateway = gateway,
                    playerPool = playerPool,
                    cellW = { cellW() },
                    cellH = { cellH() },
                    clampTranslation = { tx, ty, sc -> clampTranslation(tx, ty, sc) },
                    refitCanvas = { refitCanvas() },
                    hitTestCell = { lx, ly, ex -> hitTestCell(lx, ly, ex) },
                    gridLoadMore = { dir -> gridLoadMore(dir) },
                    refreshAllSlots = { refreshAllSlots() },
                    onCellEnded = { slotId, key -> onCellEnded(slotId, key) },
                    settleGridWatch = { slotId -> settleGridWatch(slotId) },
                    onPkVote = { slotId -> onPkVote(slotId) },
                    onCellDragStart = onCellDragStart,
                    onCellDrag = onCellDrag,
                    onCellDragEnd = onCellDragEnd,
                    onCellDragCancel = onCellDragCancel,
                    onFullscreenItem = onFullscreenItem,
                    onRemoveCustom = onRemoveCustom,
                    onPopoutToFloating = onPopoutToFloating,
                )
            }

            // R278 项2：四件覆盖层（捏合徽章/悬浮落区/换一拨/顶部预补提示）迁出
            // BoxScope.GridViewportOverlays（本文件底部）——语句原样搬移，显隐值
            // 全部组合期现算透传（与原内联快照读同语义）
            GridViewportOverlays(
                pinching = pinching,
                canvasScale = canvasScale,
                shuffleFlash = shuffleFlash,
                topGrowFlash = topGrowFlash,
                // 与跟手幽灵同门槛：原地长按（等菜单）不闪落区提示
                popoutVisible = dragKey != null && viewportSize.height > 0 &&
                    dragAccum.getDistance() > with(density) { 24.dp.toPx() },
                dragInPopoutZone = dragInPopoutZone,
            )

            // R230 追加2：「恢复 N 格」悬浮胶囊删除（用户实锤「没啥用」）——关掉的格
            // 不再提供主界面恢复入口；唯一逃生口保留在"全部格被关"的空态引导
            //（GridFeedEmptyArea 的「恢复全部格子」次级动作——那是死局态的唯一出口，
            // 删了会把用户锁死在空画布）
        }
    }

    // R278 项2：三件套弹层（片单选择器/画面调整枢纽/新手引导）迁出 GridModalSheets
    //（本文件底部）——语句原样搬移，回调原位上提为参数
    GridModalSheets(
        showPlaylistPicker = showPlaylistPicker,
        playlists = playlists,
        onPlaylistPick = { pl ->
            showPlaylistPicker = false
            addPlaylistToCanvas(pl)
        },
        onPlaylistDismiss = { showPlaylistPicker = false },
        frameAdjustKey = frameAdjustKey,
        onFrameAdjustDismiss = { frameAdjustKey = null },
        showCoach = showCoach,
        onCoachDismiss = {
            showCoach = false
            prefsCoach.firstRunGrid = false
        },
    )
    }
}

// 画布视频单格（GridVideoCell）已拆至 GridVideoCell.kt（R129 纯搬运）
// 分屏顶栏与工具岛（GridTopBar/ToolIslandSlot/ToolIslandDivider/GridPlaylistCapsule）
// 已拆至 GridTopBar.kt（R129 纯搬运）
// 画布覆盖层（空引导/片单选择器/瞬态Toast/悬浮落区/恢复胶囊/PK投票）已拆至
// GridOverlays.kt（R129 纯搬运）
// 格子装饰件（CellBottomScrim/CellCloseButton/CellFocusRing/CellSoundBars/
// CellVolumePill/GridResizeHandleGlyph）已拆至 GridCellChrome.kt（R129 纯搬运）

/**
 * 分屏空态区（R217 ① 从主函数 when 分支抽出）：加载中 = AppLoading（转圈 +
 * 说明文案，替代"死黑屏 + 小白点"，走查 G-A / 确诊 B1）；空画布 = GridEmptyGuide
 * 引导态 + 可配置次级动作——首拉整页滤空/超时被判到底时用户就地「重新加载」
 * （不 reset 源：游标已前进，续拉下一页即新片，与「再点一次才正常加载」同语义
 * 且不用退出分屏）；全部格被 × 关时复用同件给「恢复全部格子」。
 * 抽取动机：GridScreen 主组合函数逼近 DEX 校验上限（R129 抽 GridCellDispatch
 * 的同病二次复发，16384 实测 VerifyError）——每次主函数增行都要考虑再拆。
 */
@Composable
private fun GridFeedEmptyArea(
    uiScale: Float,
    gridLoading: Boolean,
    secondaryText: String,
    onSecondary: () -> Unit,
    onAddFromPlaylist: () -> Unit,
) {
    if (gridLoading) {
        AppLoading("正在加载分屏内容…")
    } else {
        GridEmptyGuide(
            uiScale = uiScale,
            onAddFromPlaylist = onAddFromPlaylist,
            secondaryText = secondaryText,
            onSecondary = onSecondary,
        )
    }
}

/**
 * R278 项2 自 GridScreen 主函数迁出（语句原样搬移，仅去缩进）：自由画布视口整段——
 * 变换手势层 + 画布内容层（可见格循环/GridCellDispatch/PK 投票钮）+ 调整大小虚线
 * 预览 + 拖拽跟手幽灵 + 焦点格拖柄。状态经 [GridUiCtx] 句柄与 State 参数透传
 * （本地 `by` 委托=同一 State 实例，格内事件回调点击时刻现读，与原内联快照读
 * 同语义）；单元宽高/布局快照组合期现算（与主函数原 val 同口径）。
 */
@Composable
private fun GridCanvasViewport(
    gcx: GridUiCtx,
    isCustomModeState: State<Boolean>,
    gridSlotsState: State<List<GridSlot>>,
    latestGridSlotsState: State<List<GridSlot>>,
    baseByKeyState: State<Map<String, VideoItem>>,
    layoutInfoState: State<Pair<Map<String, CellRect>, Int>>,
    visibleSlotIdsState: State<Set<String>>,
    playableKeysState: State<Set<String>>,
    effectiveFocusedKeyState: State<String?>,
    likesState: State<Set<String>>,
    favoritesState: State<Set<String>>,
    watchedIdsState: State<Pair<Set<String>, Set<String>>>,
    downloadsState: State<Map<String, DownloadStatus>>,
    muteAllState: State<Boolean>,
    density: Density,
    haptic: HapticFeedback,
    context: Context,
    vm: FeedViewModel,
    gateway: TdGateway,
    playerPool: PlayerPool,
    cellW: () -> Float,
    cellH: () -> Float,
    clampTranslation: (Float, Float, Float) -> Offset,
    refitCanvas: () -> Unit,
    hitTestCell: (Float, Float, String) -> String?,
    gridLoadMore: (GridLoadDirection) -> Unit,
    refreshAllSlots: () -> Unit,
    onCellEnded: (String, String) -> Unit,
    settleGridWatch: (String) -> Unit,
    onPkVote: (String) -> Unit,
    onCellDragStart: (String) -> Unit,
    onCellDrag: (String, Offset) -> Unit,
    onCellDragEnd: (String) -> Boolean,
    onCellDragCancel: (String) -> Unit,
    onFullscreenItem: (VideoItem) -> Unit,
    onRemoveCustom: (VideoItem) -> Unit,
    onPopoutToFloating: (VideoItem) -> Unit,
) {
    // 状态句柄委托（本地别名：同一 State 实例，快照语义逐位不变）
    val isCustomMode by isCustomModeState
    val gridSlots by gridSlotsState
    val latestGridSlots by latestGridSlotsState
    val baseByKey by baseByKeyState
    val layoutInfo by layoutInfoState
    val visibleSlotIds by visibleSlotIdsState
    val playableKeys by playableKeysState
    val effectiveFocusedKey by effectiveFocusedKeyState
    val likes by likesState
    val favorites by favoritesState
    val watchedIds by watchedIdsState
    val downloads by downloadsState
    val muteAll by muteAllState
    var canvasScale by gcx.canvasScale
    var canvasTx by gcx.canvasTx
    var canvasTy by gcx.canvasTy
    var viewportSize by gcx.viewportSize
    var fitWidth by gcx.fitWidth
    val canvasRestoreChecked by gcx.canvasRestoreChecked
    val canvasTransformRestored by gcx.canvasTransformRestored
    var pinching by gcx.pinching
    var lastPinchAt by gcx.lastPinchAt
    val spans = gcx.spans
    val entranceSeen = gcx.entranceSeen
    var resizeKey by gcx.resizeKey
    var resizeSpan by gcx.resizeSpan
    var resizeStart by gcx.resizeStart
    var resizeAccum by gcx.resizeAccum
    var closedKeys by gcx.closedKeys
    var focusedKey by gcx.focusedKey
    var playGrants by gcx.playGrants
    val topPull = gcx.topPull
    val volumes = gcx.volumes
    var dragKey by gcx.dragKey
    val dragAccum by gcx.dragAccum
    val dragTargetKey by gcx.dragTargetKey
    var focusMode by gcx.focusMode
    val pkMode by gcx.pkMode
    val pkSlotIds by gcx.pkSlotIds
    val playingNow = gcx.playingNow
    // R380 意图口径表与全停音频快照（判词见 GridUiCtx 字位）：本渲染循环写
    // （意图上报/登记注销/关格清理），主函数 toggleAllPlayback 读
    val playingIntentNow = gcx.playingIntentNow
    var audioPausedByAll by gcx.audioPausedByAll
    val gridWatchStart = gcx.gridWatchStart
    var slotContent by gcx.slotContent
    val slotCursor = gcx.slotCursor
    val slotPlayed = gcx.slotPlayed
    val slotReplay = gcx.slotReplay
    var claimedKeys by gcx.claimedKeys
    var frameAdjustKey by gcx.frameAdjustKey
    val poolEpoch by gcx.poolEpoch
    // 组合期单元宽/高（px）与布局快照：与主函数原 val 同口径（内部现读视口 State）
    val unitW = cellW()
    val unitH = cellH()
    val layout = layoutInfo.first
    val totalRows = layoutInfo.second

    // ── 自由画布视口 ──────────────────────────────────
    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { sz ->
                val firstLayout = fitWidth == -1
                viewportSize = sz
                // 首次布局 / 旋转（宽变化）时，把画布宽一次性适配到视口宽
                if (sz.width > 0 && fitWidth != sz.width) {
                    fitWidth = sz.width
                    // 首帧布局且画布记忆恢复未落定：不抢跑 refit（恢复路径自带
                    // 初始变换，这里 refit 会把读回的缩放/平移冲掉）。
                    // 首帧之后宽再变 = 旋转/分窗（P1-8）：一律强制 refit——
                    // 快照里恢复的缩放/平移是按旧方向视口算的（竖屏 fit≈1.0），
                    // 横屏单元又换成 16:10 扁格（cellH 变小），沿用旧变换会让
                    // 格子缩成百余 dp 挤在左上、画布大片空置；refit 只重算
                    // 缩放/平移，槽位集合与排位不动（捏合自定义缩放过旋转即让位，
                    // 与"快照只记格子集合"的口径一致）
                    if (!firstLayout || (canvasRestoreChecked && !canvasTransformRestored)) {
                        refitCanvas()
                    }
                }
            }
            // 连续缩放 + 平移：变换手势直接写 scale/translation
            // 进 graphicsLayer（层变换零重组）。单指拖动 = 平移（tap 探测在
            // 格子层，位移超阈值后自然让给这里）；双指 = 以捏合中心为 pivot 缩放。
            // 格子长按拖拽在子层先消费位移，画布不会跟着动。
            // R149 ①：库版 detectTransformGestures 对"任一 change 被消费"零容忍，
            // 格子点按层对第二指 down 的常规消费会让捏合整轮作废（详见
            // detectCanvasTransformGestures 注释），换自绘宽容版修复双指缩放
            .pointerInput(Unit) {
                detectCanvasTransformGestures(
                    // R230 追加3：首按坐标命中测试（屏幕→画布逻辑坐标），
                    // 落在格子上=长按拖拽候选，画布平移门槛抬 4×slop
                    pressOnCell = { pos ->
                        val lx = (pos.x - canvasTx) / canvasScale
                        val ly = (pos.y - canvasTy) / canvasScale
                        hitTestCell(lx, ly, "") != null
                    },
                ) { centroid, pan, zoom, _ ->
                    val old = canvasScale
                    val newScale = (old * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                    // pivot 补偿：先反算捏合中心下的画布逻辑点，再按新缩放
                    // 回推平移，保证双指下的内容点跟手不动
                    val logicX = (centroid.x - canvasTx) / old
                    val logicY = (centroid.y - canvasTy) / old
                    val ntx = centroid.x - logicX * newScale + pan.x
                    val nty = centroid.y - logicY * newScale + pan.y
                    canvasScale = newScale
                    val cl = clampTranslation(ntx, nty, newScale)
                    canvasTx = cl.x
                    canvasTy = cl.y
                    // R230 ④ 上滑（顶缘下拉）加载：画布钳在顶（内容顶对齐/
                    // 矮于视口恒 y=0）且手指继续下拉 = 想往上看更多——
                    // 累计过阈值（56dp，1.2s 冷却）顶部预补一页，新内容
                    // 从画布顶长出，视口就在顶=即拉即见（四向 loadMore 的
                    // v1：上+下双向；左右向工程量评估见 r230_notes）
                    if (topPull.onPan(atTop = cl.y >= -0.5f, downward = pan.y > 0f, panPx = pan.y)) {
                        gridLoadMore(GridLoadDirection.TOP)
                    }
                    if (zoom != 1f) {
                        pinching = true
                        lastPinchAt = System.currentTimeMillis()
                    }
                }
            }
            // 空白处双击 = 整批换一拨：格子的双击在子层手势层先消费，
            // 能落到这里的只有画布空白/缝隙的双击，不与格子手势冲突
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { refreshAllSlots() })
            },
    ) {
        // 画布内容层：固定逻辑宽 CANVAS_COLS×unitW，高 = 总行数×unitH
        // （横屏单元为 16:10 扁格，unitH < unitW）；
        // 以 (0,0) 为变换原点做 scale+translation（屏幕 = 逻辑×scale + t）
        Box(
            Modifier
                .size(
                    width = with(density) { (CANVAS_COLS * unitW).toDp() },
                    // PK 擂台模式画布只有两格大格，行数固定 PK_CELL_H
                    height = with(density) {
                        ((if (pkMode) PK_CELL_H else totalRows).coerceAtLeast(1) * unitH).toDp()
                    },
                )
                .graphicsLayer {
                    scaleX = canvasScale
                    scaleY = canvasScale
                    translationX = canvasTx
                    translationY = canvasTy
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        ) {
            // 只 compose 可见格：格子身份 = 槽位 id（稳定），
            // 换片只换 slot.content，格子/播放器原位复用
            for ((slotIndex, slot) in gridSlots.withIndex()) {
                // PK 擂台模式：只渲染擂台两格，格位强制左右对半（各 2×2 钉在
                // 画布顶部），不走流式排位、不做可见性裁剪；非擂台格一律不 compose
                val pkIndex = if (pkMode) pkSlotIds.indexOf(slot.slotId) else -1
                if (pkMode && pkIndex < 0) continue
                val cell = if (pkMode) pkCellRect(pkIndex) else layout[slot.slotId] ?: continue
                // 专注模式：焦点格划出可见区也保持组合继续出声
                // （授权模型下全授权格都有此待遇；专注分支保留给
                // 未授权的焦点格——扫一眼别处不断"看"）
                // R230 ⑤ 授权格常驻组合：滚动/缩放改变的是可见集，
                // 播放状态只随授权/用户操作变化——滑动与播放解耦实件
                if (!pkMode && slot.slotId !in visibleSlotIds &&
                    slot.slotId !in playGrants &&
                    !(focusMode && slot.slotId == effectiveFocusedKey)
                ) continue
                key(slot.slotId) {
                    val item = slot.content
                    // 分屏 LOD：格子视觉边长 = 布局尺寸 × 画布缩放（ancestor graphicsLayer
                    // 的 scale 不进 onSizeChanged，故这里用 canvasScale 折算缩放后尺寸）；
                    // 短边缩到 80dp 以下时格内 chrome 全隐，只留纯画面
                    val chromeless = with(density) {
                        (minOf(cell.w * unitW, cell.h * unitH) * canvasScale).toDp() < 80.dp
                    }
                    // 入场错落（§6.2）：首屏前 12 格按阅读序延迟 28ms 播完整动画，
                    // 分页格只做 200ms 淡入；entranceSeen 是普通集合——只记"播过没"，
                    // 不驱动重组，滚动回收再回来的格不重播
                    val firstEnter = entranceSeen.add(slot.slotId)
                    // 三媒体格共用的位姿 modifier：画布逻辑坐标偏移 + 尺寸 + 格缝 + 裁剪
                    val cellModifier = Modifier
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
                        // 格间 2dp 黑缝（GridGap）：每格四边内缩一半
                        .padding(TgFeedTokens.GridGap / 2)
                        .clipToBounds()
                    // 关格语义随模式：片单 = 从片单移除；Feed = closedKeys 标记、后续条目顶替。
                    // 三个媒体格共用同一份（原 GridVideoCell 的 onClose 原样上提）
                    val closeCell: () -> Unit = {
                        // R149 ⑦ 单格 X"误杀全部"修复①（播放器侧）：先于重排归还本格播放器。
                        // 关格 → visibleItems 收缩 → 全画布流式重排 → 可播集换血 →
                        // 新可见格 acquire 时旧格还没 dispose（重组波里 acquire 先于 dispose，
                        // 见 poolEpoch 注释），池满 6 时触发 FIFO 淘汰——被销毁的恰是"最早
                        // acquire"的在播格（通常是首格焦点格）：全画布视频集体黑屏重拉流
                        // （logcat 实锤 CCodec 连串新建 = 全部换了新解码器），用户看到的
                        // 就是"所有视频都关掉然后换了一批"。关格瞬间先 release 腾出名额，
                        // 重排期新格直接复用它，不再顶掉在播格（release 幂等，格销毁时的
                        // 二次 release 落空安全）。② 的音频焦点修复是另一条同症状主因。
                        runCatching { playerPool.release("grid:${slot.slotId}") }
                        if (isCustomMode) {
                            // 把当前播的片子移出片单；槽位原生内容（若换过片）也一并移出，
                            // 否则关格后原片会"复活"回这个格子
                            onRemoveCustom(item)
                            if (item.key != slot.slotId) baseByKey[slot.slotId]?.let(onRemoveCustom)
                            // 别的槽位若换片换到了被移出的片子，清掉覆盖（回退到各自原生内容）
                            slotContent = (slotContent - slot.slotId)
                                .filterValues { it.key != item.key && it.key != slot.slotId }
                        } else {
                            closedKeys = closedKeys + slot.slotId
                            // 用户关的是"眼里正播的这片"：换片换上来的内容一并标记关闭，
                            // 否则它从认领表放生后会原地复活成自己的新格（"刚关的又出现"）
                            if (item.key != slot.slotId) closedKeys = closedKeys + item.key
                            claimedKeys = claimedKeys - item.key
                            slotContent = slotContent - slot.slotId
                        }
                        // 关掉的是焦点格：清掉悬挂焦点，让焦点确定性回落首格——
                        // 不清的话 focusedKey 一直指着死槽位，"恢复全部格子"时
                        // 焦点会突然跳回复活的旧格
                        if (focusedKey == slot.slotId) focusedKey = null
                        // 清掉这个槽位的换片痕迹与尺寸自定义
                        slotCursor.remove(slot.slotId)
                        slotPlayed.remove(slot.slotId)
                        slotReplay.remove(slot.slotId)
                        spans.remove(slot.slotId)
                        playingNow.remove(slot.slotId)
                        // R380 意图表与全停快照同步清（格亡不留陈意图/陈恢复面）
                        playingIntentNow.remove(slot.slotId)
                        audioPausedByAll = audioPausedByAll - slot.slotId
                        // R230 ⑥：关格即收授权（播放器已在块首先归还）
                        playGrants = playGrants - slot.slotId
                        // R149 ⑦ 观测：单格关闭只该收走一格——关格后立即报账
                        //（closedKeys/slotContent 已写入，派生链读取即重算后的值），
                        // 若"剩余可见槽位"大幅缩水即是误杀全部的现场（配合 logcat 定位）
                        Log.i(
                            "GridRefresh",
                            "关格：槽位=${slot.slotId.takeLast(6)} " +
                                "已关key=${closedKeys.size} 剩余可见槽位=${gridSlots.size}",
                        )
                    }
                    // 在播状态上报：自动轮播只换"没在播"的格（false 即移除，表不膨胀）；
                    // R94：同一上报口起停"实际观看"计时（isPlaying = 真在渲染出声，
                    // 与 Feed 侧心跳同语义），停播/归还/换片时 settleGridWatch 结算落账
                    val playingReporter: (Boolean) -> Unit = { playing ->
                        if (playing) {
                            playingNow[slot.slotId] = true
                            gridWatchStart[slot.slotId] =
                                item to android.os.SystemClock.elapsedRealtime()
                        } else {
                            playingNow.remove(slot.slotId)
                            settleGridWatch(slot.slotId)
                        }
                    }
                    // R380 意图口径上报（PlaybackTogglePolicy 单源）：顶栏全停/全播钮
                    // 状态源（anyPlaying）与全停音频快照的数据面——与 playingReporter
                    // 同源不同口径（isPlaying 喂轮播/观看记账零改，意图喂钮态，
                    // R375 两口径分职），视频格/音频格都走这一条
                    val playIntentReporter: (Boolean) -> Unit = { intent ->
                        if (intent) {
                            playingIntentNow[slot.slotId] = true
                        } else {
                            playingIntentNow.remove(slot.slotId)
                        }
                    }
                    // R380 池外自管播放器收编：音频格组合期把自管 ExoPlayer 登记进
                    // 本屏池的 externals（pauseAll 全停广播域覆盖），销毁/换片注销
                    // ——登记生命周期与格子组合同进退；key 单源 gridAudioExternalKey
                    val selfPlayerRegistrar: (ExoPlayer?) -> Unit = { p ->
                        val selfKey = gridAudioExternalKey(slot.slotId)
                        if (p != null) playerPool.attachExternal(selfKey, p) else playerPool.detachExternal(selfKey)
                    }
                    // T0-2（AUDIT-GAPS3 #2）：按媒体类型分派格子。
                    // 抽取成独立组合函数：本块 ~90 行嵌在主循环里让 GridScreen
                    // 主函数字节码超 DEX 校验上限（VerifyError 实测复发），抽出即修
                    GridCellDispatch(
                        item = item,
                        slotId = slot.slotId,
                        gateway = gateway,
                        playerPool = playerPool,
                        vm = vm,
                        focused = slot.slotId == effectiveFocusedKey,
                        liked = item.key in likes,
                        favorited = item.key in favorites,
                        watched = item.key in watchedIds.first ||
                            (item.fileUniqueId.isNotEmpty() &&
                                item.fileUniqueId in watchedIds.second),
                        downloadStatus = downloads[item.key],
                        volume = volumes[slot.slotId] ?: 1f,
                        muteAll = muteAll,
                        // R230 ⑥ 手动播放授权口（两步制第二步/失败格重试
                        // 都走这里）：满 MAX_GRID_PLAYERS 路 Toast 拒绝
                        //（FIFO 逐出=自动暂停最旧格，违反「暂停只能手动」
                        // 故弃）；授权即焦点；点别格时专注模式顺手退出
                        //（注意力已移走）。授权格继续持有播放器不变
                        onRequestPlay = {
                            if (slot.slotId in playGrants) {
                                true
                            } else if (playGrants.size >= MAX_GRID_PLAYERS) {
                                Toast.makeText(
                                    context,
                                    "同时最多播放 $MAX_GRID_PLAYERS 格，请先暂停一格",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                false
                            } else {
                                if (focusMode && slot.slotId != effectiveFocusedKey) {
                                    focusMode = false
                                }
                                playGrants = playGrants + slot.slotId
                                focusedKey = slot.slotId
                                Log.i(
                                    "GridRefresh",
                                    "播放授权：槽位=${slot.slotId.takeLast(6)} 在播数=${playGrants.size}",
                                )
                                true
                            }
                        },
                        chromeless = chromeless,
                        playable = slot.slotId in playableKeys,
                        playableOrdinal = playableKeys.indexOfFirst { it == slot.slotId },
                        poolEpoch = poolEpoch,
                        firstEnter = firstEnter,
                        slotIndex = slotIndex,
                        replayTick = slotReplay[slot.slotId] ?: 0,
                        dragging = slot.slotId == dragKey,
                        dragTarget = slot.slotId == dragTargetKey,
                        onFocus = { focusedKey = slot.slotId },
                        onVolumeChange = { volumes[slot.slotId] = it },
                        onEnded = { onCellEnded(slot.slotId, item.key) },
                        onClose = closeCell,
                        onFullscreen = {
                            // 全屏单播：先把格子内容登记进 Feed 列表——
                            // 分屏自持源与 vm.items 不同一份（R33 起游标独立续拉），
                            // 不登记 Feed 侧按 key 定位必落空（实测 idx=-1）
                            vm.offerJumpTarget(slot.content)
                            onFullscreenItem(slot.content)
                        },
                        // R227 画面调整：格长按菜单「画面调整」项 →
                        // GridScreen 托管的枢纽小菜单（Feed 播放页同款）
                        onOpenFrameAdjust = { frameAdjustKey = item.key },
                        onDragStartCell = { onCellDragStart(slot.slotId) },
                        onDragCell = { delta -> onCellDrag(slot.slotId, delta) },
                        onDragEndCell = { onCellDragEnd(slot.slotId) },
                        onDragCancelCell = { onCellDragCancel(slot.slotId) },
                        onPlayingChanged = playingReporter,
                        onPlayIntentChanged = playIntentReporter,
                        onSelfPlayerChanged = selfPlayerRegistrar,
                        modifier = cellModifier,
                    )
                    // PK 投票钮：半透明胶囊压在格底（"👈 左边赢 / 👉 右边赢"），
                    // 点哪边记哪边一票，然后两格一起走换片链换下一对
                    if (pkMode) {
                        PkVoteOverlay(
                            cell = cell,
                            unitW = unitW,
                            unitH = unitH,
                            isLeft = pkIndex <= 0,
                            onVote = { onPkVote(slot.slotId) },
                        )
                    }
                }
            }

            // 调整大小预览：虚线框画出"松手后会变成多大"（在画布层随缩放）
            val rk = resizeKey
            val rs = resizeSpan
            if (rk != null && rs != null) {
                val base = layout[rk]
                if (base != null) {
                    // 目标宽度超出画布右缘时把预览框左移回画布内
                    val px = (if (base.x + rs.first > CANVAS_COLS) CANVAS_COLS - rs.first else base.x) * unitW
                    val py = base.y * unitH
                    Canvas(
                        Modifier
                            .offset {
                                IntOffset(px.roundToInt(), py.roundToInt())
                            }
                            .size(
                                width = with(density) { (rs.first * unitW).toDp() },
                                height = with(density) { (rs.second * unitH).toDp() },
                            ),
                    ) {
                        // 调整语义=红、拖拽落点=白（与旧版相反：原先落点红框撞焦点红）；
                        // 圆角 10dp 与格子一致，dash 8/6
                        drawRoundRect(
                            color = TgFeedTokens.AccentRed.copy(alpha = 0.8f),
                            cornerRadius = CornerRadius(TgFeedTokens.CellRadius.toPx()),
                            style = Stroke(
                                width = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(8.dp.toPx(), 6.dp.toPx()),
                                ),
                            ),
                        )
                    }
                }
            }
        }

        // 拖拽跟手幽灵（视口层、屏幕坐标：不随画布二次缩放）：
        // 被拖格的封面缩略跟随手指。位移超过阈值才出现，
        // 避免"原地长按弹菜单"时幽灵闪一下
        val ghostThresholdPx = with(density) { 24.dp.toPx() }
        val dragCell = dragKey?.let { layout[it] }
        if (dragKey != null && dragCell != null && dragAccum.getDistance() > ghostThresholdPx) {
            val ghostCover = remember(dragKey) {
                latestGridSlots.firstOrNull { it.slotId == dragKey }?.content?.coverBytes?.let { bytes ->
                    runCatching {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
                    }.getOrNull()
                }
            }
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (canvasTx + dragCell.x * unitW * canvasScale + dragAccum.x).roundToInt(),
                            (canvasTy + dragCell.y * unitH * canvasScale + dragAccum.y).roundToInt(),
                        )
                    }
                    .size(
                        width = with(density) { (dragCell.w * unitW * canvasScale).toDp() },
                        height = with(density) { (dragCell.h * unitH * canvasScale).toDp() },
                    )
                    // 抬起感（§4.7）：16dp 投影 + scale 0.97；圆角与格子一致；
                    // 描边改 1dp 白 α0.5——幽灵不是焦点，不该红
                    .shadow(16.dp, RoundedCornerShape(TgFeedTokens.CellRadius), clip = false)
                    .graphicsLayer {
                        alpha = 0.85f
                        scaleX = 0.97f
                        scaleY = 0.97f
                    }
                    .clip(RoundedCornerShape(TgFeedTokens.CellRadius))
                    .border(
                        1.dp,
                        Color.White.copy(alpha = 0.5f),
                        RoundedCornerShape(TgFeedTokens.CellRadius),
                    )
                    .background(Color(0xFF222222)),
            ) {
                if (ghostCover != null) {
                    Image(
                        bitmap = ghostCover,
                        contentDescription = "拖拽中",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }

        // 调整大小拖柄：仅焦点格显示（不抢非焦点格的点击面），
        // 画在视口层屏幕坐标上——尺寸恒定、不随画布缩放；
        // 拖动中保持显示（柄自身 scale 1.15、描边变红 = 调整语义反馈，§4.4）
        val focusCell = effectiveFocusedKey?.let { layout[it] }
        // PK 擂台模式格位钉死 2×2，调整大小无意义：拖柄整体隐藏
        if (!pkMode && focusCell != null && dragKey == null &&
            (resizeKey == null || resizeKey == effectiveFocusedKey) &&
            effectiveFocusedKey in visibleSlotIds
        ) {
            // 焦点格右下角的屏幕坐标 = 平移 + 逻辑坐标 × 缩放
            val hx = canvasTx + (focusCell.x + focusCell.w) * unitW * canvasScale
            val hy = canvasTy + (focusCell.y + focusCell.h) * unitH * canvasScale
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (hx - 20.dp.toPx()).roundToInt(),
                            (hy - 20.dp.toPx()).roundToInt(),
                        )
                    }
                    // 热区 40dp（TouchMin），视觉 24dp 圆在内层 glyph
                    .size(40.dp)
                    // key = 焦点槽位：焦点切换时重启手势拿到新槽位
                    .pointerInput(effectiveFocusedKey) {
                        val k = effectiveFocusedKey ?: return@pointerInput
                        detectDragGestures(
                            onDragStart = {
                                resizeKey = k
                                resizeStart = spans[k] ?: (1 to 1)
                                resizeSpan = resizeStart
                                resizeAccum = Offset.Zero
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDrag = { change, delta ->
                                change.consume()
                                resizeAccum += delta
                                // 屏幕位移 → 逻辑单元数：÷（缩放 × 单元像素）。
                                // 每跨 1 单元换一档：右/下拖放大、左/上拖缩小，
                                // 档位范围 1..MAX_SPAN（R149 ③ 放开到 4：
                                // 横版默认 2×1 也能继续向右扩到 3×1/4×1 整行）
                                // 长驻手势闭包：单元宽高现算（横屏高 ≠ 宽）
                                val du = resizeAccum.x / (canvasScale * cellW())
                                val dv = resizeAccum.y / (canvasScale * cellH())
                                val w = (resizeStart.first + du.roundToInt()).coerceIn(1, MAX_SPAN)
                                val h = (resizeStart.second + dv.roundToInt()).coerceIn(1, MAX_SPAN)
                                if (resizeSpan != (w to h)) {
                                    resizeSpan = w to h
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            },
                            onDragEnd = {
                                val span = resizeSpan
                                if (span != null && span != resizeStart) {
                                    // 松手落档：写 span 表，流式排位自动让位重排
                                    spans[k] = span
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                resizeKey = null
                                resizeSpan = null
                                resizeAccum = Offset.Zero
                            },
                            onDragCancel = {
                                resizeKey = null
                                resizeSpan = null
                                resizeAccum = Offset.Zero
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                // 自定义双斜杠角柄 glyph：替代 Fullscreen 图标误用
                //（与"全屏单播"菜单项、ActionRail 全屏三处撞语义，§4.4/§10.3）
                GridResizeHandleGlyph(active = resizeKey != null)
            }
        }
    }
}

/**
 * R380 池外自管音频格在 PlayerPool externals 的登记 key：grid:audio: 前缀与池内
 * 视频格 grid: 命名域一眼可辨（externals 与 busy 本是两张表不冲突，前缀为日志/排查
 * 可读性）。登记方（渲染循环 selfPlayerRegistrar）与恢复消费方（toggleAllPlayback）
 * 共用本单源，禁各写一份（纪律§一）。
 */
private fun gridAudioExternalKey(slotId: String) = "grid:audio:$slotId"

/**
 * R278 项2 状态句柄（GridScreen 主方法 DEX 瘦身配套）：主方法曾 20101 DEX units /
 * 328 寄存器（R258 安全档 10201/200；R253 崩溃档 32k+/359 的 VerifyError copy1
 * 高寄存器型）——全部画布/交互 remember 态与快照表收口于此，主函数与迁出的
 * 布局 composable 经 `by gcx.xxx` 委托读写（同一 State 实例，快照语义逐位不变）。
 * 构造收进主函数单个 remember（State 对象只在首次组合创建一次）。
 * 字段注释自原主函数声明点原样随迁（语义出处不丢）。
 */
private class GridUiCtx(
    /** R230 ④ 顶缘 overscroll 累计器：钳在画布顶且继续下拉累计，过阈值触发顶部预补 */
    val topPull: TopPullTracker,
) {
    /** 连续缩放系数：1.0 = 1 逻辑单元正好 120dp */
    val canvasScale: MutableState<Float> = mutableStateOf(1f)

    /** 画布平移（px）：屏幕坐标 = 画布逻辑坐标 × scale + translation */
    val canvasTx: MutableState<Float> = mutableStateOf(0f)
    val canvasTy: MutableState<Float> = mutableStateOf(0f)

    /** 视口像素尺寸（onSizeChanged 写入） */
    val viewportSize: MutableState<IntSize> = mutableStateOf(IntSize.Zero)

    /** 已做过"宽度适配"的视口宽：首次布局 / 旋转（宽变化）时重新 fit */
    val fitWidth: MutableIntState = mutableIntStateOf(-1)

    /** 画布记忆恢复门闩（GAPS3 #8）：false = 进入时快照尚未读回（此间首帧 onSizeChanged
     *  不做宽度适配，避免 refit 把即将恢复的缩放/平移冲掉）；恢复路径走完后恒为 true */
    val canvasRestoreChecked: MutableState<Boolean> = mutableStateOf(false)

    /** true = 本次进入已从快照恢复画布变换（scale/平移），不再做"按视口宽"自动适配 */
    val canvasTransformRestored: MutableState<Boolean> = mutableStateOf(false)

    /** 捏合进行态：中央显示缩放百分比徽章（无档位，直接显示 scale） */
    val pinching: MutableState<Boolean> = mutableStateOf(false)
    val lastPinchAt: MutableLongState = mutableLongStateOf(0L)

    /** 槽位 id → spanW×spanH（默认 1×1 不入表）；只存在内存，不落库 */
    val spans: SnapshotStateMap<String, Pair<Int, Int>> = mutableStateMapOf()

    /** 已播过入场错落动画的槽位 id（普通集合，非快照 State：只做动画门控，
     *  不驱动重组——滚动回收再回来的格不重播，换源时清空重新播） */
    val entranceSeen: MutableSet<String> = mutableSetOf()

    /** 正在拖柄调整大小的槽位与预览 span（拖动中画虚线框，松手才落档） */
    val resizeKey: MutableState<String?> = mutableStateOf(null)
    val resizeSpan: MutableState<Pair<Int, Int>?> = mutableStateOf(null)
    val resizeStart: MutableState<Pair<Int, Int>> = mutableStateOf(1 to 1)
    val resizeAccum: MutableState<Offset> = mutableStateOf(Offset.Zero)

    /** 被 × 关闭的槽位 id 集合：过滤后下面的条目自动顶上 */
    val closedKeys: MutableState<Set<String>> = mutableStateOf(setOf())

    /** 焦点格槽位 id：主声音所在的格；null 时回落到首格 */
    val focusedKey: MutableState<String?> = mutableStateOf(null)

    /** R230 ⑥ 手动播放授权集（grant）：槽位 id 的授权序集合（LinkedHashSet 保留序） */
    val playGrants: MutableState<Set<String>> = mutableStateOf(LinkedHashSet())

    /** 每格独立音量记忆（按槽位 id），未调过默认 1.0 */
    val volumes: SnapshotStateMap<String, Float> = mutableStateMapOf()

    /** 被拖槽位 id：非空表示正处于"长按拖拽"中 */
    val dragKey: MutableState<String?> = mutableStateOf(null)

    /** 拖拽累计位移（屏幕 px；命中测试时 ÷ scale 折算逻辑位移） */
    val dragAccum: MutableState<Offset> = mutableStateOf(Offset.Zero)

    /** 当前命中（指针格心落在其范围内）的目标槽位 id */
    val dragTargetKey: MutableState<String?> = mutableStateOf(null)

    /** 拖拽指针是否已进入顶部悬浮落区（松手 = 弹悬浮窗而非换格） */
    val dragInPopoutZone: MutableState<Boolean> = mutableStateOf(false)

    /** 专注模式开关：开启后 playableKeys 只剩焦点格；仅会话内记忆，不落库 */
    val focusMode: MutableState<Boolean> = mutableStateOf(false)

    /** 擂台开关：开启后画布只留左右两个大格对播；仅会话内记忆，不落库 */
    val pkMode: MutableState<Boolean> = mutableStateOf(false)

    /** 擂台槽位对：进入时从当前可见槽位快照前 2 个（退出清空） */
    val pkSlotIds: MutableState<List<String>> = mutableStateOf(listOf())

    /** 每格"正在播放中"上报表（格内播放器的 isPlaying）：自动轮播只换没在播的格 */
    val playingNow: SnapshotStateMap<String, Boolean> = mutableStateMapOf()

    /**
     * R380 每格"用户语义在播"上报表（PlaybackTogglePolicy 意图口径）：顶栏全停/全播
     * 钮状态源 anyPlaying 与全停音频快照的数据面。playingNow（isPlaying 真出声）继续
     * 喂自动轮播与观看记账（R375 两口径分职负锚，零改）；意图表只喂钮态——缓冲中
     * isPlaying=false 但意图在＝"在播"，旧 anyPlaying 只看 playingNow 时按钮在缓冲窗
     * 误读"全部播放"且点了 no-op（play() 落在已是 true 的意图上零变化，R375 病根
     * 的全停钮形态）。
     */
    val playingIntentNow: SnapshotStateMap<String, Boolean> = mutableStateMapOf()

    /**
     * R380 全停音频快照（槽位 id → 内容 key）：被「全部暂停」拉停的池外自管音频格。
     * 「全部播放」的池外恢复面=只恢复这批且内容未换的（换片/换一批后的新内容不
     * 自动开播——「不自动播」对池外格同样成立）；视频侧维持 R230 授权格恢复口径零改。
     */
    val audioPausedByAll: MutableState<Map<String, String>> = mutableStateOf(emptyMap())

    /** R94 分屏实际观看记账：槽位 → (内容, 起表时刻) */
    val gridWatchStart: MutableMap<String, Pair<VideoItem, Long>> = mutableMapOf()

    /** 槽位当前内容覆盖表：槽位 id → 换片后的内容 */
    val slotContent: MutableState<Map<String, VideoItem>> = mutableStateOf(mapOf())

    /** 每格已播游标：槽位 id → 当前内容在内容池中的下标，换片从这里往后推进 */
    val slotCursor: SnapshotStateMap<String, Int> = mutableStateMapOf()

    /** 每格已播内容 key 集合：换片优先挑本格没播过的 */
    val slotPlayed: SnapshotStateMap<String, Set<String>> = mutableStateMapOf()

    /** 兜底重播计数：整池无片可换时 bump 它，对应格原地重播 */
    val slotReplay: SnapshotStateMap<String, Int> = mutableStateMapOf()

    /** 被"换片认领"的内容 key：不再生成自己的格子（只在认领它的槽位播） */
    val claimedKeys: MutableState<Set<String>> = mutableStateOf(setOf())

    /** R227 画面调整枢纽：目标条目 key（非空即弹 FrameAdjustHubSheet 小菜单） */
    val frameAdjustKey: MutableState<String?> = mutableStateOf(null)

    /** 播放器池淘汰自愈代际：playableKeys 成员变化时 +1 */
    val poolEpoch: MutableIntState = mutableIntStateOf(0)
}

/**
 * R278 项2 自 GridScreen 主函数迁出（语句原样搬移）：画布上方四件瞬态覆盖层——
 * 捏合缩放徽章 / 悬浮落区 / "换了一拨" / 顶部预补提示。显隐值全部组合期现算
 * 透传（与原内联快照读同语义）。
 */
@Composable
private fun BoxScope.GridViewportOverlays(
    pinching: Boolean,
    canvasScale: Float,
    shuffleFlash: Boolean,
    topGrowFlash: Boolean,
    popoutVisible: Boolean,
    dragInPopoutZone: Boolean,
) {
    // 捏合实时反馈徽章：统一 OverlayToast 胶囊（§4.6），位置从屏幕正中
    // 挪到顶栏下方——正中会挡被缩放的画面；fade+scale 0.92→1 进出（旧版 if 硬切）
    GridOverlayToast(
        visible = pinching,
        text = "缩放 ${(canvasScale * 100).roundToInt()}%",
        icon = Icons.Filled.GridView,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 8.dp),
    )

    // 悬浮落区（§4.5）：顶部居中悬浮胶囊 + 屏幕顶缘 56dp 红色渐变光洗，
    // 取代旧版一整块大色板。命中判定不变（屏幕顶部 15%），纯绘制覆盖层不挡手势；
    // 进区胶囊整体填红 + 文案切换 + 弹簧放大 1.05，fade+translateY 进出场
    PopoutDropOverlay(
        // 与跟手幽灵同门槛：原地长按（等菜单）不闪落区提示，
        // 否则每次想弹菜单顶部都跳一下红色光洗 + 胶囊，纯打扰
        visible = popoutVisible,
        inZone = dragInPopoutZone,
        modifier = Modifier.align(Alignment.TopCenter),
    )

    // "换了一拨"提示（空白双击 / 批量换片后）：统一 OverlayToast 胶囊
    GridOverlayToast(
        visible = shuffleFlash,
        text = "换了一拨",
        icon = Icons.Filled.Autorenew,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 8.dp),
    )

    // R230 ④ 顶部预补成功提示（顶缘下拉加载新内容后一闪）
    GridOverlayToast(
        visible = topGrowFlash,
        text = "上方补充了新内容",
        icon = Icons.Filled.Refresh,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 8.dp),
    )
}

/**
 * R278 项2 自 GridScreen 主函数迁出（语句原样搬移，回调原位上提为参数）：
 * 三件套弹层——「从片单添加」选择器 / R227 画面调整枢纽 / 新手引导覆盖层。
 */
@Composable
private fun GridModalSheets(
    showPlaylistPicker: Boolean,
    playlists: List<Playlist>,
    onPlaylistPick: (Playlist) -> Unit,
    onPlaylistDismiss: () -> Unit,
    frameAdjustKey: String?,
    onFrameAdjustDismiss: () -> Unit,
    showCoach: Boolean,
    onCoachDismiss: () -> Unit,
) {
    // 「从片单添加」选择器（P0-2 新增）：空画布引导态主按钮 / 工具岛添加槽共用；
    // 点选片单 = 整单条目经 onAddCustom 倒入分屏片单（gridPlaylist，容器层去重）
    if (showPlaylistPicker) {
        GridPlaylistPickSheet(
            playlists = playlists,
            onPick = onPlaylistPick,
            onDismiss = onPlaylistDismiss,
        )
    }

    // R227 画面调整枢纽（格子长按菜单「画面调整」项收口）：四选一小菜单与
    // Feed 播放页长按面板同一 FrameAdjustHubSheet 同一 FrameAdjustStore 真身
    //（Feed 里调过的姿态分屏里也生效）；面板自持读写，这里只托管弹层
    val frameAdjustOpenKey = frameAdjustKey
    if (frameAdjustOpenKey != null) {
        dev.tgfeed.ui.components.FrameAdjustHubSheet(
            itemKey = frameAdjustOpenKey,
            onDismiss = onFrameAdjustDismiss,
        )
    }

    // 新手引导覆盖层（仅首次进入分屏）：捏合/平移/拖柄/换位手势说明
    if (showCoach) {
        dev.tgfeed.ui.components.CoachOverlay(
            page = dev.tgfeed.ui.components.CoachPage.Grid,
            onDismiss = onCoachDismiss,
        )
    }
}

/**
 * R149 ① 分屏双指捏合修复：自绘宽容版 detectTransformGestures。
 *
 * 为什么不用库版：库版把"任何 change 被消费"当整轮手势作废——而分屏格子的点按层
 * （detectTapGestures，Compose 1.7 起首指 down 固定 consume）在 Main pass 先于
 * 画布层拿到事件：第二个手指按在格子上时，这个 down 先被格子点按层消费，库版变换
 * 手势随后看到 isConsumed 即作废——分屏双指捏合从未生效（单指平移不受影响：平移
 * 期间没有新 down 事件）。口味星图可用，是因为其点按层与变换层在同一节点且变换层
 * 在内侧（Main pass 先于点按层拿到事件），不存在这层"子层吃 down"。
 *
 * R230 追加3（拖动不滚屏）改动：位移被消费不再=整轮作废，改为**跳过该事件继续等**。
 * 旧「作废」语义下，子层手势（格子长按拖拽/拖柄/音量胶囊）一旦消费位移，本手势
 * 永久退出本轮触摸；配合格子长按等待窗里的位移漂移，画布平移会在长按成立前抢跑
 * 滚屏——用户实锤「长按拖动时屏幕跟着滚」。新语义：
 *  - 消费位移的帧=子层手势在处理，本手势静默跳过（不累计不平移，滚屏与子层互斥）；
 *  - 子层放手后同指继续移动（未消费）自然回落本手势——平移从放手点续起；
 *  - 识别阈值/捏合/平移算法与库版逐行一致。
 * 另（首按门槛分档）：[pressOnCell] 判首按落在格子上时，平移启动门槛抬到 4×
 * touchSlop（捏合/旋转仍按原 slop）——长按等待窗（格子侧 3×slop 容忍）内画布不
 * 抢跑；空白处首按维持原 slop（平移跟手不变）。
 */
private suspend fun PointerInputScope.detectCanvasTransformGestures(
    panZoomLock: Boolean = false,
    pressOnCell: (Offset) -> Boolean = { false },
    onGesture: (centroid: Offset, pan: Offset, zoom: Float, rotation: Float) -> Unit,
) {
    awaitEachGesture {
        var rotation = 0f
        var zoom = 1f
        var pan = Offset.Zero
        var pastTouchSlop = false
        val touchSlop = viewConfiguration.touchSlop
        var lockedToPanZoom = false

        // 与库版一致：首指 down 允许已被子层消费（点按层必然消费它）
        val down = awaitFirstDown(requireUnconsumed = false)
        // R230 追加3：首按在格子上=长按拖拽候选——平移启动门槛抬高（4×slop），
        // 等待窗内不抢跑滚屏；空白处首按正常 slop
        val panSlop = if (pressOnCell(down.position)) touchSlop * 4f else touchSlop
        do {
            val event = awaitPointerEvent()
            // R230 追加3：位移被消费（子层拖拽在抢占事件流）= 跳过本帧事件、不退出
            // 手势；点按层对第二指 down / 抬指 up 的常规消费不含位移，不受影响
            val childDragging = event.changes.any { it.isConsumed && it.positionChanged() }
            if (!childDragging) {
                val zoomChange = event.calculateZoom()
                val rotationChange = event.calculateRotation()
                val panChange = event.calculatePan()

                if (!pastTouchSlop) {
                    zoom *= zoomChange
                    rotation += rotationChange
                    pan += panChange

                    val centroidSize = event.calculateCentroidSize(useCurrent = false)
                    val zoomMotion = abs(1 - zoom) * centroidSize
                    val rotationMotion = abs(rotation * PI.toFloat() * centroidSize / 180f)
                    val panMotion = pan.getDistance()

                    if (zoomMotion > touchSlop ||
                        rotationMotion > touchSlop ||
                        panMotion > panSlop
                    ) {
                        pastTouchSlop = true
                        lockedToPanZoom = panZoomLock && rotationMotion < touchSlop
                    }
                }

                if (pastTouchSlop) {
                    val centroid = event.calculateCentroid(useCurrent = false)
                    val effectiveRotation = if (lockedToPanZoom) 0f else rotationChange
                    if (effectiveRotation != 0f ||
                        zoomChange != 1f ||
                        panChange != Offset.Zero
                    ) {
                        onGesture(centroid, panChange, zoomChange, effectiveRotation)
                    }
                    event.changes.forEach {
                        if (it.positionChanged()) {
                            it.consume()
                        }
                    }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}
