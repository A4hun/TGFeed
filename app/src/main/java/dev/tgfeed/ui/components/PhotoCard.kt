package dev.tgfeed.ui.components

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import dev.tgfeed.ui.components.kit.LocalBottomBarInset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.ui.FitBottomAnchor
import dev.tgfeed.ui.PinchCleanEvent
import dev.tgfeed.ui.landscapeFeedChromeOrNull
import dev.tgfeed.ui.rememberWindowSize
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Feed 图片页卡（mediaType=1 的条目）：
 * - 图片全屏展示：模糊底衬（Crop 铺满 + 1.25x + 30dp 高斯模糊，照 VideoPage 封面模式）
 *   + 主图 ContentScale.Fit 居中完整显示；图未就绪时封面/转圈占位。
 *   R338：横屏可伸展档（landscapeChrome≠null）下卡内模糊底衬退役——留边纯深
 *   （卡根黑底直透，与 R336 外层纯深底衬自然衔接）；竖屏/矮横屏现状不变
 * - 默认幻灯片：isActive 时 4 秒倒计时自动跳下一条（onAutoNext），
 *   底部细进度条实时显示倒计时进度；单击画面 = 暂停/继续倒计时（暂停时中央常驻 ⏸）；
 *   双击 = 点赞飘心（复用 LikeHeartsLayer，未赞才触发 onToggleLike）；
 *   长按 = 弹功能面板（onShowPanel，ActionSheet 由 FeedScreen 宿主，与视频卡同一块面板）
 * - R174 捏合清屏（提案 §3.4 图文平等）：双指捏合缩放 1-4x 与 chrome（右轨/底部
 *   信息区/顶栏经 onOverlayStateChange）透明度连续线性映射，≥1.3 清屏态生效；
 *   三退通道（单击/双击/返回键）与 VideoPage 同一套共享宿主/手势件/状态机
 * - 底部信息区与视频页共用 BottomInfoPanel（P0-1：原 PhotoInfoPanel 分叉已合并，
 *   B9b/B9c/PH-C 修复一并并入 BottomInfoPanel）；不消费位移事件，竖滑翻页不受影响
 *
 * 图集模式（[siblings] 非空，同一条相册消息被拆成的兄弟图片条目）：
 * - 画面区改 HorizontalPager：第 1 张 = [item] 自身，其后按 [siblings] 顺序排列，
 *   横滑在图集内翻页（竖滑仍归外层 VerticalPager 翻 Feed，不冲突）
 * - 顶部分段条指示（Stories 式，docs/IMAGE-AUDIO-DESIGN.md §4.3）：每段一张图，
 *   高 2dp、间距 3dp、圆角 1dp；轨 白25%、已播段 白90%，当前段随倒计时生长，
 *   一眼读出"第几张/共几张"
 * - 幻灯片自动翻页改为图集内推进：到点翻下一张，只有停在最后一张时才上抛
 *   [onAutoNext] 跳 Feed 下一条；手动横滑后从落点张重新走表
 * - 单击暂停/继续、双击点赞语义与单图一致（手势层放页内，不挡 Pager 横滑）
 *
 * 图片解码（W1+W2）：真实 TDLib 条目经 TdLibGateway.loadPhotoViewPath 同步下载
 * 浏览档（≈1280px，photoViewFileId）拿本地路径，IO 线程 BitmapFactory.decodeFile
 * 采样解码；未就绪/失败退化为 coverBytes（minithumbnail，偏小偏糊，仅作占位级画质）。
 * 最大档原图 fileId 只供下载保存/分享。
 * 幻灯片与图就绪联动（W4）：当前张主图未解码成功时 4s 计时器停表，图好才走。
 *
 * @param onAutoNext 倒计时到点回调（外层切到下一条；图集模式仅最后一张到点才触发）
 * @param siblings 同相册（同 albumId）的其余图片条目；空 = 单图，保持原单图行为
 * @param gateway 真实 TDLib 条目 playUri 为空时用来 ensurePlayable；Mock 自带 playUri 可不传
 * @param slideDurationMs 每张幻灯片停留时长，默认 4 秒
 */
@Composable
internal fun PhotoCard(
    item: VideoItem,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onAutoNext: () -> Unit = {},
    liked: Boolean = false,
    onToggleLike: () -> Unit = {},
    // 操作栏：收藏/下载（图片没有声音和倍速，操作栏只挂这三个）
    favorited: Boolean = false,
    // R432 重组收敛：下载表 State 本体下传（卡片内按 item.key 现查），下载 1Hz
    // 进度不再经值参链把整页拖进重组
    downloads: androidx.compose.runtime.State<Map<String, dev.tgfeed.core.feed.DownloadStatus>>? = null,
    onToggleFavorite: () -> Unit = {},
    onDownload: () -> Unit = {},
    onOpenChannel: () -> Unit = {},
    onSearchTag: (String) -> Unit = {},
    // 新增可选参数（透传给 BottomInfoPanel）：频道真头像加载器；null = 回落首字字母圆
    avatarLoader: (suspend (Long) -> ByteArray?)? = null,
    // 新增可选参数（透传给 BottomInfoPanel）：当前频道是否已订阅（信息区"追"快捷钮态）
    subscribed: Boolean = false,
    // 新增可选参数（透传给 BottomInfoPanel）：点击"追"快捷订阅当前频道
    onSubscribe: () -> Unit = {},
    // 新增可选参数（透传给 BottomInfoPanel）：点击 caption 里的 http(s)/t.me 链接
    onOpenLink: (String) -> Unit = {},
    // 新增可选参数（透传给 BottomInfoPanel）：点击 caption 里的 @用户名（不含 @）
    onOpenMention: (String) -> Unit = {},
    // 长按画面弹功能面板（ActionSheet 由 FeedScreen 宿主，与视频卡同一块面板）
    onShowPanel: () -> Unit = {},
    // 一键翻译标题（透传给 BottomInfoPanel 的"译"chip；译文为 null = 未翻译）
    translatedCaption: String? = null,
    onTranslate: (VideoItem) -> Unit = {},
    // TG 广场角标：全站在看数 / 点赞数（透传给 BottomInfoPanel，0/0 = 不显示）
    plazaWatching: Int = 0,
    plazaLikes: Int = 0,
    gateway: TdGateway? = null,
    slideDurationMs: Long = 4_000L,
    siblings: List<VideoItem> = emptyList(),
    // 清屏/全屏状态上报（R174 提案 §3.4 图文同语义）：hidden = cleanMode；
    // chromeAlpha 为连续渐隐值（1=全显，捏合跟手）——外层顶栏以 graphicsLayer α 承接
    onOverlayStateChange: (hidden: Boolean, chromeAlpha: Float) -> Unit = { _, _ -> },
    // 上报口保留：当前显示图如实上报外层（liveFrame 恒 false——无帧流；图集只报
    // 当前显示页）。设计史注记：R194①② 图文卡同镜像口径（架构师终案④）曾把此
    // 上报收养为外层全屏模糊底衬（同图双绘）——R336 起外层按媒体类型档恒拒图片
    // 项上报（2026-10-22 用户拍板 A 方案：图片页底衬退役=纯深色背景+清晰图单层，
    // 华为平板两层皮实锤）；口零动，为未来复活留链路（收养门
    // BackdropPolicy.shouldAdoptBackdropForMedia + FeedScreen 清场观察者）
    onBackdropChange: (ImageBitmap?, liveFrame: Boolean) -> Unit = { _, _ -> },
    // R194② 轨道切换冻结窗代次（解冻 +1 → 当前页重报一次，与视频卡同一收口）
    backdropReportGen: Int = 0,
    // R194⑥ 顶栏实测底缘 px（悬停式顶锚预留基准，外层测得；0=居中兜底）
    topChromeBottomPx: Float = 0f,
    // R238 评论媒体流入口：点按「💬 N 条评论」角标（评论数由 item+siblings 相册组
    // 聚合——图集卡天然持有全组名单，与视频页 feedVm.albumGroupOf 同口径）
    onOpenComments: (VideoItem) -> Unit = {},
    // R269 「在星图中定位」：R234「你在这里」宇宙坐标行改可点（透传 BottomInfoPanel；
    // 宿主 FeedScreen 接线 R233 openTasteMapFly 链；null = 行纯展示不可点）
    onLocateInGalaxy: (() -> Unit)? = null,
    // ---- R299 B2 查看器第二态 / B3 漫画模式（宿主 FeedScreen 接线；默认值=旧调用零变化） ----
    // 保存原图（查看器操作排；宿主接既有下载链 vm.download——按当前显示页条目）
    onSavePhoto: (VideoItem) -> Unit = {},
    // 条漫断点续读（progress 链复用，key="comic:"+item.key；px 语义）
    comicProgressOf: (VideoItem) -> Long = { 0L },
    onSaveComicProgress: (VideoItem, Long) -> Unit = { _, _ -> },
    // 上一话/下一话（dir=+1 下一话/更新，-1 上一话/更早；返回新章图集页，null=没有更多话）；
    // null=宿主未接深索引，查看器不渲染章导航
    chapterNavigator: (suspend (pages: List<VideoItem>, dir: Int) -> List<VideoItem>?)? = null,
) {
    // R432 重组收敛：叶子查表点（卡片组合期单次读；1Hz 进度只重组下方下载格）
    val downloadStatus = downloads?.value?.get(item.key)
    // 图集页列表：第 1 张恒为条目自身，其余按 siblings 顺序（防御性去重自身 key）
    val albumPages = remember(item.key, siblings) {
        if (siblings.isEmpty()) listOf(item) else listOf(item) + siblings.filter { it.key != item.key }
    }
    val isAlbum = albumPages.size > 1
    val pagerState = rememberPagerState(pageCount = { albumPages.size })
    // 当前所在张（单图恒为 0）；读 pagerState.currentPage 即订阅翻页快照
    val currentAlbumPage = if (isAlbum) pagerState.currentPage else 0
    val scope = rememberCoroutineScope()

    // ── R174 捏合清屏（提案 §3.4 图文平等）：与 VideoPage 同款共享宿主/手势件，
    //    零私有拷贝（纪律§一）——双指自由缩放 0.25-4x（R174b 去地板）+ chrome 渐退
    //    同语义 ──
    val pinch = rememberPinchCleanHost(item.key)
    // R225 横屏可伸展档（与视频页同一政策）：信息区限宽 + 右轨触区/图标放大；
    // 竖屏/矮横屏 null → 全部走下方既有字面量，逐字节现状。
    // R338 起本门再收三件（图片横屏适配对齐视频——用户 19:2x 实锤）：①卡内模糊
    // 底衬横屏档退役（留边纯深，与 R336 外层纯深底衬自然衔接——卡根黑底直透）；
    // ②图集顶部分段条限宽（infoMaxWidth 可读宽口径，Stories 段不随列宽放宽拉成
    // 细面条）；③列宽随图放宽在 FeedScreen 侧接（feedPhotoShapeAspect 同一
    // contentAwareFeedMaxWidth 政策单口）——本门仍是唯一横屏判定口，零第二政策件
    val landscapeChrome = landscapeFeedChromeOrNull(
        rememberWindowSize(),
        LocalConfiguration.current.screenHeightDp,
    )
    var zoomScale by pinch.zoomScale
    var zoomOffset by pinch.zoomOffset
    // ---- R299 B2 查看器第二态态开关（ pinch「继续推」收尾≥1.8x / 右轨「放大」钮进入） ----
    var viewerOpen by remember(item.key) { mutableStateOf(false) }
    var viewerInitialScale by remember(item.key) { mutableFloatStateOf(1f) }
    // 进入查看器：卡级捏合复位（查看器接管视觉与手势，卡的缩放态不带进去）
    val enterViewer: (Float) -> Unit = { withScale ->
        viewerInitialScale = withScale
        pinch.cancelZoomReset()
        zoomScale = 1f
        zoomOffset = Offset.Zero
        pinch.dispatch(PinchCleanEvent.FullReset)
        viewerOpen = true
    }
    val cardContext = LocalContext.current
    val appPrefs = remember { dev.tgfeed.core.prefs.AppPrefs(cardContext.applicationContext) }
    // 首次教学气泡（§3.2 同视频口径：AppPrefs 全局一次性把门，2.5s 淡出）
    var pinchTeachBubble by remember { mutableStateOf(false) }
    LaunchedEffect(item.key, pinch.teachFired) {
        if (pinch.teachFired && !appPrefs.pinchCleanGuideShown) {
            appPrefs.pinchCleanGuideShown = true
            pinchTeachBubble = true
            delay(2500)
            pinchTeachBubble = false
        }
    }
    // 滑走复位（§3.2 滑走复位行）：下一条是干净的新开始
    LaunchedEffect(isActive) {
        if (!isActive) pinch.resetForPageLeft()
    }
    // 清屏态上报外层（顶栏连续渐隐；isActive 入键=落定即时重报，防陈旧残留）
    val onOverlayStateChangeNow = rememberUpdatedState(onOverlayStateChange)
    LaunchedEffect(item.key, isActive) {
        snapshotFlow { pinch.chromeAlpha.value to pinch.cleanMode }
            .collect { (alpha, hidden) -> onOverlayStateChangeNow.value(hidden, alpha) }
    }
    // R299 B2：查看器开=外层 chrome 全隐（顶栏+底栏经 hideTopBar/feedChromeHidden
    // 链全退——查看器模态语义）；关=按卡级捏合实况重报
    LaunchedEffect(viewerOpen) {
        if (viewerOpen) {
            onOverlayStateChangeNow.value(true, 0f)
        } else {
            onOverlayStateChangeNow.value(pinch.cleanMode, pinch.chromeAlpha.value)
        }
    }
    // §3.2 退通道③兜底：清屏态系统返回键 = 先恢复 chrome（后组合者优先，
    // 压过 MainActivity 级双击退出）
    BackHandler(enabled = pinch.cleanMode) {
        pinch.dispatch(PinchCleanEvent.ExitCleanKeepZoom(zoomScale))
    }

    // 幻灯片倒计时进度 0..1 与暂停态（换条目自动归零复活）
    var slideProgress by remember(item.key) { mutableStateOf(0f) }
    var slidePaused by remember(item.key) { mutableStateOf(false) }
    // W4：图集内各页"主图就绪"登记（就绪页的 key 集合）。4s 幻灯片只在当前张
    // 主图解码成功（或加载已定局）后才走表——治"图没出来就自动翻下一张"：
    // 原实现是纯计时器，与图就绪零联动，慢代理下马赛克连播
    var readyPages by remember(item.key) { mutableStateOf(setOf<String>()) }
    // 防御：条目刚切换时 pagerState.currentPage 可能还停在新图集之外的旧页码
    //（回滚到第 1 张由下面的 LaunchedEffect(item.key) 负责），这里必须安全取页
    val currentPageReady = albumPages.getOrNull(currentAlbumPage)
        ?.let { it.key in readyPages } == true
    // 双击点赞飘心列表
    var hearts by remember(item.key) { mutableStateOf(listOf<HeartBurst>()) }

    // 条目切换时回到图集第一张（分页窗口复用组合时防旧页码越界/跳张）
    LaunchedEffect(item.key) {
        if (pagerState.currentPage != 0) pagerState.scrollToPage(0)
    }

    // 图集内翻页（手动横滑或自动到点）后：当前段进度归零，从落点张重新走表。
    // 刻意放在计时器 effect 之前声明——同帧重启时归零协程先起跑
    LaunchedEffect(currentAlbumPage) {
        slideProgress = 0f
    }

    // 幻灯片倒计时：仅激活且未暂停时走表；暂停/恢复随 effect 取消重建，从当前进度续走。
    // 到点：图集内还有下一张 → 推进 Pager（独立协程起动画，本 effect 随翻页重启时
    // 不打断滚动动画）；已是最后一张（或单图）→ 上抛 onAutoNext（只抛一次：抛完进度
    // 停在 1f，循环自然结束）
    val currentOnAutoNext by rememberUpdatedState(onAutoNext)
    // W4：currentPageReady 进键——当前张主图未就绪期间计时器整体停表（进度不累计），
    // 图就绪后才从当前进度续走；到点必是真图已上屏，结构性消除"空翻"
    LaunchedEffect(item.key, isActive, slidePaused, currentAlbumPage, currentPageReady, viewerOpen) {
        // R299 B2：查看器开着时幻灯片计时器停表（查看器是自己的阅读节奏）
        if (!isActive || slidePaused || !currentPageReady || viewerOpen) return@LaunchedEffect
        val stepMs = 50L
        while (slideProgress < 1f) {
            delay(stepMs)
            slideProgress =
                (slideProgress + stepMs.toFloat() / slideDurationMs.toFloat()).coerceAtMost(1f)
        }
        if (slideProgress >= 1f) {
            if (isAlbum && currentAlbumPage < albumPages.size - 1) {
                scope.launch { pagerState.animateScrollToPage(currentAlbumPage + 1) }
            } else {
                currentOnAutoNext()
            }
        }
    }

    // 单击 = 暂停/继续倒计时（rememberUpdatedState 防 pointerInput 抓旧闭包）。
    // R174：清屏态单击 = 先恢复 chrome（§3.2 退通道①；倒计时暂停语义让位一次）
    val currentOnTap = rememberUpdatedState<() -> Unit>(newValue = {
        when {
            pinch.cleanMode -> pinch.dispatch(PinchCleanEvent.ExitCleanKeepZoom(zoomScale))
            else -> slidePaused = !slidePaused
        }
    })
    // 双击 = 点赞飘心（未赞才赞，已赞只飘心），落点即心起点。
    // R174：缩放态（R174b 起 ≠1x，含 <1x）双击 = 复位缩放 + chrome 全恢复
    //（§3.2 退通道②，弹簧+220ms 并行）
    val currentOnDoubleTap = rememberUpdatedState<(androidx.compose.ui.geometry.Offset) -> Unit>(
        newValue = { offset ->
            if (zoomScale != 1f) {
                pinch.dispatch(PinchCleanEvent.FullReset)
                pinch.launchZoomResetSpring()
            } else {
                if (!liked) onToggleLike()
                hearts = hearts + HeartBurst(
                    id = Random.nextLong(),
                    x = offset.x,
                    y = offset.y,
                    rotation = Random.nextInt(-24, 25).toFloat(),
                )
            }
        },
    )
    // 长按 = 弹功能面板（rememberUpdatedState 防 pointerInput 抓旧闭包）
    val currentOnShowPanel = rememberUpdatedState(newValue = onShowPanel)

    // 卡片容器实测尺寸（R188）：FIT 主图下锚平移量的判定基准（横/竖容器口径）。
    // 与 VideoPage 的 containerSizePx 同语义——图集各页共用卡片根尺寸
    var containerSizePx by remember { mutableStateOf(IntSize.Zero) }

    // R194③ 自由平移内容带（近似口径）：条目元数据宽高比——精确位图比在
    // PhotoImageArea 解码后才知，夹域只需近似边界（元数据缺尺寸回 null=旧图层
    // 口径，零回归兜底）；图集取当前显示页的条目。真身 rememberUpdatedState：
    // pointerInput 块实例不随重组刷新（键未变时闭包捕获的本地 val 会陈旧）
    val pinchAspectInput by rememberUpdatedState(
        run {
            val m = if (isAlbum) albumPages.getOrNull(pagerState.currentPage) ?: item else item
            if (m.width > 0 && m.height > 0) m.width.toFloat() / m.height else null
        },
    )
    // 内容静止位=R194⑥ 悬停顶锚近似（与 PhotoImageArea 内位图精确口径同函数
    // 同预留；夹域围绕基点）
    val pinchPanBase by rememberUpdatedState(
        run {
            val d = LocalDensity.current
            val bottomReserve = with(d) {
                WindowInsets.navigationBars.getBottom(this).toFloat() +
                    LocalBottomBarInset.current.toPx() + 12.dp.toPx()
            }
            Offset(
                0f,
                FitBottomAnchor.fitHoverAnchorShiftPx(
                    containerSizePx.width, containerSizePx.height, pinchAspectInput ?: 0f,
                    topChromeBottomPx + with(d) { 12.dp.toPx() }, bottomReserve,
                ),
            )
        },
    )

    Box(
        modifier
            .fillMaxSize()
            // 容器实测尺寸回写（R188 下锚平移基准；见上方声明处注释）
            .onSizeChanged { containerSizePx = it }
            .background(Color.Black)
            // 0) R174 捏合缩放/清屏手势（共享件，与 VideoPage 同构）：挂卡片根
            //    修饰链（祖先节点=事件必然流经；独立兄弟 Box 会收不到事件——
            //    VideoPage 真机实锤的死层教训，见 PinchZoomTracking.kt 头注释）。
            //    多指 Initial pass 先消费：图集横滑翻图/外层竖滑翻 Feed 不冲突
            //    （提案 §3.4 消歧矩阵）；缩放变化帧上抛机器（chrome α 联动），
            //    平移帧不触发（防 restore 后两指平移误清屏）
            //    R194③：内容带/基点现读（与 VideoPage 同语义——可见图片可拖到
            //    卡内任意角落，放大态部分出屏走保守界）
            .pinchZoomTracking(
                key = item.key,
                zoomScale = pinch.zoomScale,
                zoomOffset = pinch.zoomOffset,
                contentAspect = { pinchAspectInput },
                panBase = { pinchPanBase },
                onZoomChange = { old, new ->
                    pinch.dispatch(PinchCleanEvent.ZoomChanged(old, new))
                },
                onGestureStart = { pinch.cancelZoomReset() },
                // R299 B2：查看器开着=卡级捏合全放行（查看器是下方最顶兄弟层 modal
                // 全消费，本层不收任何事件——同一手势两主互杀面消除）
                enabled = { !viewerOpen },
                // R299 B2 查看器进入判定：pinch 收尾 ≥1.8x（清屏阈值 1.3 之上
                // 「继续推」的落点，design §3.1）→ 带收尾缩放值进查看器态
                onGestureEnd = { endScale ->
                    if (PhotoViewerPolicy.shouldEnterViewer(endScale)) enterViewer(endScale)
                },
            ),
    ) {
        // 1) 画面区：图集 → HorizontalPager（页内自带手势层，横滑翻页）；
        //    单图 → 原样单张 + 卡级手势层（现状不变）
        if (isAlbum) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val pageItem = albumPages[page]
                Box(Modifier.fillMaxSize()) {
                    // W4：逐页登记主图就绪态，计时器只认 currentPage 那张；
                    // R194①② 镜像上报只认当前显示页（±1 预排版页不上报）
                    PhotoImageArea(
                        pageItem, isActive, gateway,
                        onReadyChange = { ready ->
                            readyPages =
                                if (ready) readyPages + pageItem.key else readyPages - pageItem.key
                        },
                        onBackdropChange = if (page == pagerState.currentPage) onBackdropChange else null,
                        backdropReportGen = backdropReportGen,
                        zoomScale = zoomScale,
                        zoomOffset = zoomOffset,
                        containerSizePx = containerSizePx,
                        // R338：横屏可伸展档下卡内模糊底衬退役（竖屏/矮横屏 false=现状）
                        suppressBackdrop = landscapeChrome != null,
                    )
                    // 页内手势层：单击暂停/继续、双击点赞飘心；不消费位移，
                    // Pager 横滑与外层 VerticalPager 竖滑都不受影响
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(pageItem.key) {
                                detectTapGestures(
                                    onTap = { currentOnTap.value() },
                                    onDoubleTap = { offset -> currentOnDoubleTap.value(offset) },
                                    // 长按弹功能面板；不消费位移，Pager 横滑/竖滑翻页不受影响
                                    onLongPress = { currentOnShowPanel.value() },
                                )
                            },
                    )
                }
            }
        } else {
            // W4：单图模式同样登记就绪态（集合里只会含 item.key 一个元素）；
            // R194①② 镜像上报（单图恒当前页）
            PhotoImageArea(
                item, isActive, gateway,
                onReadyChange = { ready ->
                    readyPages = if (ready) readyPages + item.key else readyPages - item.key
                },
                onBackdropChange = onBackdropChange,
                backdropReportGen = backdropReportGen,
                zoomScale = zoomScale,
                zoomOffset = zoomOffset,
                containerSizePx = containerSizePx,
                // R338：横屏可伸展档下卡内模糊底衬退役（竖屏/矮横屏 false=现状）
                suppressBackdrop = landscapeChrome != null,
            )
            // 手势层：单击暂停/继续倒计时，双击点赞飘心；不消费位移，竖滑翻页不冲突
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(item.key) {
                        detectTapGestures(
                            onTap = { currentOnTap.value() },
                            onDoubleTap = { offset -> currentOnDoubleTap.value(offset) },
                            // 长按弹功能面板；不消费位移，竖滑翻页不受影响
                            onLongPress = { currentOnShowPanel.value() },
                        )
                    },
            )
        }

        // 1.6) 顶部可读性（B9a 起靠渐变遮罩，R162 零遮罩政策删除）：
        //      图文 tab 顶栏的标题/图标/tab 文字压在图片上，可读性统一由顶栏组件
        //      文字自带的 withOverlayShadow 描边承担（视频页同场景已验证的干净基准），
        //      不再在图卡内局部补染色渐变

        // 1.5) 顶部分段条（Stories 式，仅图集）：每段 = 一张图，
        //      已播段实心 白90%、当前段随倒计时生长、未播段 白25% 轨
        //      R338 横屏可伸展档：分段条限宽（infoMaxWidth=560 可读宽口径——
        //      列宽随图放宽后 Stories 段不横贯 1100dp+ 拉成细面条；TopCenter
        //      锚既有=限宽后仍居中）。widthIn 必须在 fillMaxWidth 之前（V6 同款
        //      顺序教训——fill 节点按外层 maxWidth 上报宽度，内层收窄被覆盖）；
        //      竖屏/矮横屏 null → 不挂节点逐字节现状
        if (isAlbum) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .then(
                        if (landscapeChrome != null) {
                            Modifier.widthIn(max = landscapeChrome.infoMaxWidth)
                        } else {
                            Modifier
                        },
                    )
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 14.dp, end = 14.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                albumPages.forEachIndexed { index, _ ->
                    val fill = when {
                        index < currentAlbumPage -> 1f
                        index == currentAlbumPage -> slideProgress
                        else -> 0f
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(Color.White.copy(alpha = 0.25f)),
                    ) {
                        if (fill > 0f) {
                            Box(
                                Modifier
                                    .fillMaxWidth(fill)
                                    .height(2.dp)
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(Color.White.copy(alpha = 0.9f)),
                            )
                        }
                    }
                }
            }
        }

        // 2) 底部可读性（B9b 起靠 300dp 渐变遮罩，R162 零遮罩政策删除）：
        //    信息区压在图上，BottomInfoPanel 的频道名/caption/展开钮/chip 文字
        //    全部已带 withOverlayShadow 描边（视频页同款干净基准），
        //    图片原色不被染色；海报 billing block 与面板文字互叠靠描边层级解决

        // 4) 暂停反馈：中央常驻 ⏸（再点一下继续倒计时）
        if (slidePaused) {
            Icon(
                Icons.Filled.Pause,
                contentDescription = "幻灯片已暂停",
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(96.dp),
            )
        }

        // 5) 双击飘心层
        LikeHeartsLayer(
            hearts = hearts,
            onHeartDone = { id -> hearts = hearts.filterNot { it.id == id } },
        )

        // 5.6) R174 捏合清屏首次教学气泡（与视频页同件同口径；顶部分段条/底部
        //      倒计时细线属进度信息，§3.3 留守清单——不进渐隐组）
        PinchCleanCoachBubble(visible = pinchTeachBubble)

        // 5.5) 右侧操作栏（图片条目与视频同款列）：点赞 / 收藏 / 下载。
        //      R174 清屏连续渐隐（§3.4 同语义：cleanMode 门控升级为 chrome α 渐隐）
        //      R225 横屏可伸展档：触区盒 40→48dp（铁律）、图标 30→政策档、行距同步；
        //      竖屏/矮横屏 null → 40dp/30dp/26dp 现状零变化
        if (!pinch.cleanMode) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = pinch.chromeAlpha.value }) {
        androidx.compose.foundation.layout.Column(
            Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 10.dp, bottom = 110.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(landscapeChrome?.railGap ?: TgFeedTokens.RailGap),
        ) {
            // 横屏可伸展档的钮盒/图标尺寸（触区盒=48dp 铁律锚；现状=40dp/30dp）
            val photoRailBox = landscapeChrome?.railTouchMin ?: 40.dp
            val photoRailIcon = landscapeChrome?.railIconSize ?: TgFeedTokens.RailIconSize
            // R299 B2：「放大」钮直达查看器（design §3.1 双入口之二；pinch 继续推是之一）
            Box(
                Modifier
                    .size(photoRailBox)
                    .clip(RoundedCornerShape(50))
                    .clickable { enterViewer(1f) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.ZoomIn,
                    contentDescription = "放大查看",
                    tint = TgFeedTokens.TextHigh,
                    modifier = Modifier.size(photoRailIcon),
                )
            }
            // 点赞
            Box(
                Modifier
                    .size(photoRailBox)
                    .clip(RoundedCornerShape(50))
                    .clickable { onToggleLike() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (liked) Icons.Filled.Favorite
                    else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (liked) "已点赞" else "点赞",
                    tint = if (liked) Color(0xFFFE2C55) else TgFeedTokens.TextHigh,
                    modifier = Modifier.size(photoRailIcon),
                )
            }
            // 收藏
            Box(
                Modifier
                    .size(photoRailBox)
                    .clip(RoundedCornerShape(50))
                    .clickable { onToggleFavorite() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (favorited) Icons.Filled.Star
                    else Icons.Outlined.StarBorder,
                    contentDescription = if (favorited) "已收藏" else "收藏",
                    tint = if (favorited) Color(0xFFFBBC05) else TgFeedTokens.TextHigh,
                    modifier = Modifier.size(photoRailIcon),
                )
            }
            // 下载
            Box(
                Modifier
                    .size(photoRailBox)
                    .clip(RoundedCornerShape(50))
                    .clickable(enabled = downloadStatus !is dev.tgfeed.core.feed.DownloadStatus.Running) { onDownload() },
                contentAlignment = Alignment.Center,
            ) {
                when (downloadStatus) {
                    is dev.tgfeed.core.feed.DownloadStatus.Running ->
                        // 内联小占位保持 22dp/2dp；补 trackColor——M3 1.3.0 spinner
                        // 动画首帧（比例 0）退化成小圆点，轨道色兜底显示完整圆环
                        CircularProgressIndicator(
                            Modifier.size(22.dp),
                            color = TgFeedTokens.TextHigh,
                            strokeWidth = 2.dp,
                            trackColor = TgFeedTokens.TextHigh.copy(alpha = 0.2f),
                        )
                    is dev.tgfeed.core.feed.DownloadStatus.Done ->
                        Icon(Icons.Outlined.CheckCircle, "已下载", tint = TgFeedTokens.SuccessGreen, modifier = Modifier.size(photoRailIcon))
                    is dev.tgfeed.core.feed.DownloadStatus.Failed ->
                        Icon(Icons.Outlined.Warning, "下载失败", tint = TgFeedTokens.WarningRed, modifier = Modifier.size(photoRailIcon))
                    else ->
                        Icon(Icons.Outlined.FileDownload, "下载", tint = TgFeedTokens.TextHigh, modifier = Modifier.size(photoRailIcon))
                }
            }
            } // 右轨 Column 收尾
            } // R174 chrome α 渐隐包裹 Box 收尾（右轨）
        }

        // 6) 底部信息区：P0-1 起图文/视频共用 BottomInfoPanel（原 PhotoInfoPanel 分叉
        //    已合并回去——"译"chip 在 chips 行末尾不压简介、图文不显示时长 chip、
        //    频道名 weight+Ellipsis 单行省略，这些修复都在 BottomInfoPanel 里）；
        //    图集共享同一条相册消息，标题/caption 一律取条目自身。
        //    R174 清屏连续渐隐（§3.4 同语义）
        if (!pinch.cleanMode) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = pinch.chromeAlpha.value }) {
        BottomInfoPanel(
            item = item,
            onOpenChannel = onOpenChannel,
            onSearchTag = onSearchTag,
            // 翻译 chip 与译文展示（与视频页同一口径；chip 在 chips 行末尾）
            translatedCaption = translatedCaption,
            onTranslate = { onTranslate(item) },
            avatarLoader = avatarLoader,
            subscribed = subscribed,
            onSubscribe = onSubscribe,
            // 广场角标："👥 N 在看 · ❤️ M"胶囊（与视频页同一口径，0/0 不显示）
            plazaWatching = plazaWatching,
            plazaLikes = plazaLikes,
            // R238 评论数（相册组聚合：item+siblings——载体成员独占 replyCount，其余
            // 成员 0）+ 评论流入口；与视频页 VideoBottomInfo 同口径（图集卡本地持有
            // 全组名单，无需 feedVm）
            commentCount = albumPages.maxOfOrNull { it.commentCount } ?: 0,
            onOpenComments = if ((albumPages.maxOfOrNull { it.commentCount } ?: 0) > 0) {
                { onOpenComments(item) }
            } else {
                null
            },
            onOpenLink = onOpenLink,
            onOpenMention = onOpenMention,
            onLocateInGalaxy = onLocateInGalaxy,
            modifier = Modifier
                .align(Alignment.BottomStart)
                // R225 横屏可伸缩档：与视频页同一限宽政策（560dp 可读宽）——竖屏/
                // 矮横屏 null → 不挂节点逐字节现状。widthIn 必须在 fillMaxWidth 之前
                //（fill 节点按外层 maxWidth 上报自身宽度，内层收窄会被覆盖——
                // 16384 实测教训，同 VideoBottomInfo）
                .then(
                    if (landscapeChrome != null) {
                        Modifier.widthIn(max = landscapeChrome.infoMaxWidth)
                    } else {
                        Modifier
                    },
                )
                .fillMaxWidth()
                .navigationBarsPadding()
                // R173 底栏让位：信息区贴底栏上沿（清屏时随 inset 归零）
                .padding(start = 14.dp, end = 84.dp, bottom = 18.dp + LocalBottomBarInset.current),
        )
            } // R174 chrome α 渐隐包裹 Box 收尾（信息区）
        }

        // 7) 幻灯片倒计时细进度条（最底部，让到导航栏之上；暂停时提亮）：
        //    图集模式下表达"当前这张"的停留进度，与顶部分段条同一视觉语言
        val alpha = if (slidePaused) 0.95f else 0.7f
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = LocalBottomBarInset.current) // R173 底栏让位：倒计时细线同拍跟随
                .height(2.dp)
                .background(Color.White.copy(alpha = 0.18f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(slideProgress)
                    .height(2.dp)
                    .background(Color.White.copy(alpha = alpha)),
            )
        }

        // 8) R174b 自由缩放三件套之三：悬浮复位标识（与 VideoPage 同件同语义：
        //    TopStart 避右轨/倒计时细线/教学气泡；不进渐隐组——清屏里就是复位
        //    出口；点按 = FullReset + 0.82/500 弹簧，复位后随判据翻假淡出）
        PinchResetChip(
            visible = pinch.isTransformed,
            onReset = {
                pinch.dispatch(PinchCleanEvent.FullReset)
                pinch.launchZoomResetSpring()
            },
        )

        // 9) R299 B2 查看器第二态（最顶兄弟层=modal 全消费；卡级捏合经 enabled 闸
        //    已让位，幻灯片停表，外层 chrome 全隐）。下滑甩回/返回键/关闭钮=退出
        //    回 Feed 原位（viewerOpen 翻假，卡状态原样恢复）
        if (viewerOpen) {
            PhotoViewerOverlay(
                pages0 = albumPages,
                initialPage = currentAlbumPage.coerceIn(0, albumPages.size - 1),
                initialScale = viewerInitialScale,
                gateway = gateway,
                onSave = onSavePhoto,
                onDismiss = { viewerOpen = false },
                comicProgressOf = comicProgressOf,
                onSaveComicProgress = onSaveComicProgress,
                chapterNavigator = chapterNavigator,
            )
        }
    }
}

/**
 * 单张图片的画面内容：模糊底衬（Crop 铺满 + 1.25x + blur 30dp）+ Fit 主图，
 * 未就绪时封面/转圈占位。IO 线程解码（file:// 前缀先剥掉，退化 coverBytes minithumbnail）。
 *
 * 加载链（W1+W2+W3，修"永远马赛克"）：真实 TDLib 图片条目不再走 ensurePlayable——
 * 文件未下完时它返回 http://127.0.0.1 流服务器地址，而 decodeFile 只认本地路径，
 * 必败且此后无任何重试/完成通知 → 永久停在 minithumbnail 马赛克。改走
 * TdLibGateway.loadPhotoViewPath：同步下载"浏览档"（photoViewFileId，≈1280px，
 * 0.2~0.8MB，priority=32，15s 超时）拿真实本地路径再解码。
 * 最大档原图 fileId 只留给下载保存/分享链（本链不碰）。
 *
 * @param active 卡片激活才触发下载；Pager 已组合的非当前页
 *   也会带着 active=true 预取（图集横滑到下一张时基本已就绪）
 * @param onReadyChange W4：向宿主汇报"主图加载是否已定局"——真实文件解码成功，
 *   或加载失败/无加载通道（失败不该让幻灯片永停）；仅 minithumbnail 占位期间报 false。
 *   宿主的 4s 幻灯片计时器据此暂停走表（图好才走，治"图没出来就自动翻下一张"）
 * @param containerSizePx R188：卡片容器实测尺寸（下锚平移的判定基准，宿主根
 *   Box onSizeChanged 回写；图集各页共用）
 * @param suppressBackdrop R338 横屏可伸展档下卡内模糊底衬退役（留边纯深，与 R336
 *   外层纯深底衬自然衔接——卡根 Color.Black 直透，竖图居中两侧/横图上下留边全纯深，
 *   「深底+单层清晰图」的横屏兑现）；默认 false=竖屏/矮横屏现状（模糊底衬填 Fit
 *   留边是竖屏主消费形态的既有观感，逐字节不变）
 */
@Composable
private fun PhotoImageArea(
    item: VideoItem,
    active: Boolean,
    gateway: TdGateway?,
    onReadyChange: (Boolean) -> Unit = {},
    // 镜像上报：位图就绪/代次翻转时把当前显示图交给外层（null=非当前显示页，不报）。
    // R336 起该上报在收养侧按媒体类型被拒（图片档底衬退役，A 方案——口径见卡头
    // onBackdropChange 注记）；口与代次重报零动，图集翻页重报同口径拒收
    onBackdropChange: ((ImageBitmap?, liveFrame: Boolean) -> Unit)? = null,
    backdropReportGen: Int = 0,
    // R194⑥ 顶栏实测底缘（悬停式顶锚预留基准，卡根透传）
    topChromeBottomPx: Float = 0f,
    // R174 捏合缩放（卡级状态透传）：主图与模糊底衬同 graphicsLayer 变换
    zoomScale: Float = 1f,
    zoomOffset: Offset = Offset.Zero,
    containerSizePx: IntSize = IntSize.Zero,
    // R338：横屏可伸展档（宿主 landscapeChrome 门透传）下模糊底衬不绘制
    suppressBackdrop: Boolean = false,
) {
    var playUri by remember(item.key) { mutableStateOf(item.playUri) }
    // 加载是否已定局：无需加载（自带 playUri / 无网关）或加载尝试已结束（成败都算——
    // 失败停在 minithumbnail 占位，但 W4 计时器不能因此永停）
    var loadSettled by remember(item.key) { mutableStateOf(item.playUri != null || gateway == null) }

    // H5：解码目标尺寸 = 屏宽 2 倍（Fit 主图最多撑满屏，2 倍留给缩放/高分屏余量）。
    // 不采样的全尺寸解码（2560×2560 ARGB_8888 ≈ 26MB/张）×图集 Pager 当前±1 三页同存，
    // 在 256MB heap 低端机上是真实 OOM 路径
    val photoContext = LocalContext.current
    val decodeTargetPx = remember { photoContext.resources.displayMetrics.widthPixels * 2 }

    // 真实 TDLib 条目首次激活时同步下载浏览档，拿本地文件路径
    //（W2：不再接收 ensurePlayable 的 http 流地址——那是 decodeFile 必败的根因）
    LaunchedEffect(active, item.key) {
        if (active && playUri == null && gateway != null) {
            val lib = gateway as? dev.tgfeed.core.td.TdLibGateway
            playUri = if (lib != null) {
                runCatching { lib.loadPhotoViewPath(item) }.getOrNull()
            } else {
                // 非 TDLib 网关（Mock 等）：保持原 ensurePlayable 链
                runCatching { gateway.ensurePlayable(item) }.getOrNull()
            }
            loadSettled = true
        }
    }

    // 图片解码：IO 线程，避免大图阻塞主线程；fromFile 标记区分真图与 minithumbnail 占位（W4 用）
    val decode: PhotoDecode? by produceState<PhotoDecode?>(initialValue = null, item.key, playUri) {
        value = withContext(Dispatchers.IO) {
            val uri = playUri
            val decoded = uri?.takeIf { it.isNotBlank() }?.let { path ->
                runCatching {
                    // R299：解码政策收敛共享件 decodePhotoSampled（纪律§一零私有拷贝）
                    decodePhotoSampled(path.removePrefix("file://"), decodeTargetPx)?.asImageBitmap()
                }.getOrNull()
            }
            if (decoded != null) {
                PhotoDecode(decoded, fromFile = true)
            } else {
                PhotoDecode(
                    bitmap = item.coverBytes?.let { bytes ->
                        runCatching {
                            // minithumbnail 本身就几十~几百字节的小图，无需采样
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
                        }.getOrNull()
                    },
                    fromFile = false,
                )
            }
        }
    }

    // W4：就绪 = 真实文件已解码成功，或加载已定局（失败/无通道不再空等）；
    // 未就绪期间宿主幻灯片计时器停表。rememberUpdatedState 防 effect 抓旧闭包
    val currentOnReadyChange = rememberUpdatedState(onReadyChange)
    LaunchedEffect(item.key, decode?.fromFile, loadSettled) {
        currentOnReadyChange.value(decode?.fromFile == true || loadSettled)
    }

    val bitmap = decode?.bitmap
    // 位图就绪即如实上报（liveFrame 恒 false）；代次键（解冻 +1）让当前页重报一次
    //（与视频卡同收口）。R336 起外层收养门按媒体类型恒拒图片项上报（图片档底衬
    // 退役 A 方案——口径见卡头注记）：上报照发、收养侧拒收，图片项在场改由外层
    // 清场观察者淡出底衬；口零动，未来复活直接接回
    val currentOnBackdropChange = rememberUpdatedState(onBackdropChange)
    LaunchedEffect(bitmap, backdropReportGen) {
        currentOnBackdropChange.value?.invoke(bitmap, false)
    }
    if (bitmap != null) {
        // R194⑥ 悬停式顶锚（架构师 17:10 布局终案，与视频层同一纯函数同一预留口径：
        // 顶缘贴 tab 小间距、底缘悬停 dock 上方 ~12dp、富余进中；比未知/竖容器 → 0 居中）
        val photoMediaDensity = LocalDensity.current
        val photoBottomReservePx = with(photoMediaDensity) {
            WindowInsets.navigationBars.getBottom(this).toFloat() +
                LocalBottomBarInset.current.toPx() + 12.dp.toPx()
        }
        val photoTopReservePx = topChromeBottomPx + with(photoMediaDensity) { 12.dp.toPx() }
        val bottomShiftPx = FitBottomAnchor.fitHoverAnchorShiftPx(
            containerSizePx.width, containerSizePx.height,
            if (bitmap.width > 0 && bitmap.height > 0) {
                bitmap.width.toFloat() / bitmap.height
            } else {
                0f
            },
            photoTopReservePx, photoBottomReservePx,
        )
        // R188c 探针（R174 验证范式，与 VideoPage 同 tag）：图片下锚落值——
        // 键挂 shift 值（容器/位图变化即落线），logcat 直证图片卡接线
        LaunchedEffect(item.key, bottomShiftPx) {
            android.util.Log.i(
                "R188c",
                "图片下锚 shift=%.1f 容器=%dx%d 图比=%.3f".format(
                    bottomShiftPx,
                    containerSizePx.width, containerSizePx.height,
                    if (bitmap.width > 0 && bitmap.height > 0) {
                        bitmap.width.toFloat() / bitmap.height
                    } else {
                        0f
                    },
                ),
            )
        }
        // 模糊底衬：遮住 Fit 留下的两侧/上下黑边（blur 仅 Android 12+ 生效，低版本退化无模糊）。
        // R174：≥1x 随主图同缩放（zoom 下底衬与画面同拍扩张，边缘不露黑）。
        // R174b：<1x 缩小态底衬**不随缩**——保持 1.25x 满幅填底（「周围透底衬」
        // 语义：缩小的主图浮在同图模糊底上）；此态平移由夹域钳在窗口内，
        // 固定满幅底衬恒全覆盖，不露黑页底。
        // R338：横屏可伸展档（suppressBackdrop=true）底衬整层退役——留边透卡根
        // Color.Black 纯深（与 R336 外层纯深连成整片「深底+单层清晰图」；缩放态
        // <1x 浮在深底上=清屏同观感家族）；竖屏/矮横屏 false 走原路径逐字节现状
        if (!suppressBackdrop) {
            val backdropScale = if (zoomScale >= 1f) 1.25f * zoomScale else 1.25f
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = backdropScale
                        scaleY = backdropScale
                        translationX = if (zoomScale >= 1f) zoomOffset.x else 0f
                        translationY = if (zoomScale >= 1f) zoomOffset.y else 0f
                    }
                    .blur(30.dp),
                contentScale = ContentScale.Crop,
            )
        }
        // 主图：完整居中显示（FitCenter 语义）；R174 捏合缩放层变换（与视频画面同口径）
        // R188：居中位再下移 bottomShiftPx（贴底锚定；与视频层 translationY 同式
        // = 捏合平移 + 下锚平移，两变换叠加兼容，draw-phase 改值不触发布局）
        Image(
            bitmap = bitmap,
            contentDescription = item.caption.ifBlank { null },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoomScale
                    scaleY = zoomScale
                    translationX = zoomOffset.x
                    translationY = zoomOffset.y + bottomShiftPx
                },
            contentScale = ContentScale.Fit,
        )
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // 统一 spinner 规格：40dp/3dp/TextHigh + trackColor
            //（M3 1.3.0 动画首帧退化成小圆点，轨道色兜底成完整圆环）
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                color = TgFeedTokens.TextHigh,
                strokeWidth = 3.dp,
                trackColor = TgFeedTokens.TextHigh.copy(alpha = 0.2f),
            )
        }
    }
}

/**
 * W4：图片解码结果——bitmap 可能来自真实文件（浏览档/原图本地路径）或
 * minithumbnail 占位；fromFile=true 才算"主图就绪"（幻灯片计时器的走表闸门）
 */
private class PhotoDecode(val bitmap: ImageBitmap?, val fromFile: Boolean)

