package dev.tgfeed.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.ui.PinchCleanPolicy
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * H5 采样解码（R299 收敛：原 PhotoCard 私有 decodeSampled 上提为组件包共享件——
 * 纪律§一同一解码政策零私有拷贝）：先 inJustDecodeBounds 只量尺寸，再把
 * inSampleSize 翻倍到「短边仍 ≥ targetPx 的最大 2 的幂」。量尺寸失败退回原始
 * decodeFile（保持旧行为）。
 */
internal fun decodePhotoSampled(path: String, targetPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || targetPx <= 0) {
        return BitmapFactory.decodeFile(path)
    }
    var sample = 1
    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) {
        sample *= 2
    }
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/**
 * R299 B2 图片查看器第二态（design-audio-photo.md §3.1 终审稿）——
 * PhotoCard pinch「继续推」（收尾 ≥ [PhotoViewerPolicy.ENTER_SCALE]）或右轨「放大」
 * 钮进入的真看图模式，Telegram 查看器手势四件套：
 *  - 双指无级缩放 1~5x + 平移（内容边缘夹域，[PhotoViewerPolicy.clampPan]）；
 *  - 双击 2.5x↔1x（锚点在双击位）；单击=藏 chrome（纯净全屏=壁纸级，§3.3 并入）；
 *  - 横滑=图集内切图（1x 态让位内层 HorizontalPager；放大态横滑=平移）；
 *  - 下滑甩回=退出回 Feed 原位（1x 态，条漫页须滚到顶；位移跟手+背景渐隐）。
 *
 * R299 B3 漫画模式（§3.2）：本章首图 3:1 长条 → 整章竖滚阅读（不切图），
 * 顶排出「上一话/下一话」（[chapterNavigator] 由宿主接深索引毫秒级邻话查询），
 * 滚动位置经 [onSaveComicProgress]/[comicProgressOf] 断点续读（progress 链复用）。
 *
 * 手势挂载纪律（§四三禁一许）：查看器是卡根 Box 的**最顶兄弟层**（modal 全消费），
 * 卡级 pinchZoomTracking 由宿主加 enabled 闸关闭，外层 VerticalPager 竖滑由本层
 * 消费阻断——双指/单指位移仲裁全部在本层 Initial pass 收口，无兄弟层互杀面。
 *
 * @param pages0 本章图集页（第 1 张=条目自身，与 PhotoCard.albumPages 同序）
 * @param initialScale 进场缩放（pinch 收尾值；右轨钮直达=1f）
 * @param onSave 保存原图（宿主接既有下载链：Pictures/TGFeed；系统壁纸不开新权限通道）
 * @param chapterNavigator 邻话查询（dir=+1 下一话/更新，-1 上一话/更早；
 *   返回新章图集页列表，null=该方向没有更多话）；null=非漫画场不渲染章导航
 */
@Composable
internal fun PhotoViewerOverlay(
    pages0: List<VideoItem>,
    initialPage: Int,
    initialScale: Float,
    gateway: TdGateway?,
    onSave: (VideoItem) -> Unit,
    onDismiss: () -> Unit,
    comicProgressOf: (VideoItem) -> Long = { 0L },
    onSaveComicProgress: (VideoItem, Long) -> Unit = { _, _ -> },
    chapterNavigator: (suspend (pages: List<VideoItem>, dir: Int) -> List<VideoItem>?)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 当前章图集页（章导航整组换装）
    var pages by remember { mutableStateOf(pages0) }
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { pages.size })
    // 单页变换态（切页/切章归零复位——「换一张=干净开始」）
    var scale by remember { mutableFloatStateOf(initialScale.coerceIn(1f, PhotoViewerPolicy.MAX_SCALE)) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    // 下滑退出跟手位移（仅 1x 态）
    var dismissDragY by remember { mutableFloatStateOf(0f) }
    var chromeVisible by remember { mutableStateOf(true) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    // 章漫画态：本章首图 3:1 长条 → 整章竖滚（元数据先判，位图解码后细化在页内）
    val chapterComic = pages.firstOrNull()?.let { PhotoViewerPolicy.isLongStrip(it.width, it.height) } == true
    // 条漫页「滚动在顶」读位（仲裁层每事件现读；当前页 LaunchedEffect 周期写入）
    val comicScrollAtTop = remember { mutableStateOf(true) }

    android.util.Log.i(
        "R299V",
        "viewer enter pages=${pages.size} init=$initialPage scale=%.2f comic=$chapterComic".format(initialScale),
    )

    // 当前页内容宽高比（位图解码后为准，元数据兜底；平移夹域现读）
    var currentAspect by remember { mutableFloatStateOf(0f) }
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    // 切页/切章：变换与下滑位移复位（旧页的缩放不带去新页）
    LaunchedEffect(pagerState.settledPage, pages) {
        scale = 1f
        panOffset = Offset.Zero
        dismissDragY = 0f
        android.util.Log.d("R299V", "viewer page → ${pagerState.settledPage}/${pages.size}")
    }

    // 返回键=退出查看器（查看器在卡内，优先于一切外层返回语义）
    BackHandler { currentOnDismiss() }

    // 双击缩放（锚点在双击位：内容坐标不动点），动画走 spring
    fun doubleTapZoom(at: Offset) {
        val target = PhotoViewerPolicy.doubleTapTarget(scale)
        val old = scale
        val c = Offset(viewSize.width / 2f, viewSize.height / 2f)
        // 内容坐标不动点公式：off' = t − c − (t − c − off)/s·s′
        val contentPoint = (at - c - panOffset) / old
        val newOffset = at - c - contentPoint * target
        val aspect = currentAspect
        val (cw, ch) = if (aspect > 0f) {
            PinchCleanPolicy.fitContentSpan(aspect, viewSize.width.toFloat(), viewSize.height.toFloat())
        } else {
            viewSize.width.toFloat() to viewSize.height.toFloat()
        }
        scale = target
        panOffset = PhotoViewerPolicy.clampPan(
            newOffset, cw, ch, viewSize.width.toFloat(), viewSize.height.toFloat(), target,
        )
        android.util.Log.d("R299V", "viewer dtap zoom → %.2f".format(target))
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            // 底幕=PhotoViewerBackdrop token（模态看图语义本体；scrim-audit B21 登记），
            // 下滑跟手渐隐系数由政策件单源出
            .background(PhotoViewerBackdrop.copy(alpha = PhotoViewerPolicy.dismissBackdropAlpha(dismissDragY, viewSize.height.toFloat())))
            // 位移仲裁层（Initial pass：先于内层 Pager/竖滚/tap 的 Main pass）：
            // 双指=缩放+平移全消费；单指=放大态平移 / 1x 态下甩退出（条漫页须在顶）；
            // 1x 横滑与条漫竖滚不消费（让位内层）
            .pointerInput(pages) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var twoFinger = false
                    var singlePan = false
                    var dismissDrag = false
                    var released = false // 本手势已判明让位（横滑/竖滚），不再仲裁
                    var acc = Offset.Zero
                    val panSlopPx = 10.dp.toPx()
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2 && !dismissDrag && !released) twoFinger = true
                        if (!twoFinger && !singlePan && !dismissDrag && !released && pressed.size == 1) {
                            acc += event.changes
                                .filter { !it.changedToDown() }
                                .map { it.positionChange() }
                                .fold(Offset.Zero) { a, b -> a + b }
                            if (acc.getDistance() > panSlopPx) {
                                when {
                                    scale > 1f + 0.01f -> singlePan = true
                                    // 1x 竖向下甩=退出（条漫页滚动未顶=让位竖滚；
                                    // 非条漫页无竖滚层，下甩恒归退出手势）
                                    kotlin.math.abs(acc.y) > kotlin.math.abs(acc.x) && acc.y > 0f &&
                                        (!chapterComic || comicScrollAtTop.value) -> dismissDrag = true
                                    else -> released = true
                                }
                            }
                        }
                        if (twoFinger || singlePan) {
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val newScale = (scale * zoom).coerceIn(1f, PhotoViewerPolicy.MAX_SCALE)
                            val aspect = currentAspect
                            val (cw, ch) = if (aspect > 0f) {
                                PinchCleanPolicy.fitContentSpan(
                                    aspect, size.width.toFloat(), size.height.toFloat(),
                                )
                            } else {
                                size.width.toFloat() to size.height.toFloat()
                            }
                            panOffset = PhotoViewerPolicy.clampPan(
                                panOffset + pan, cw, ch,
                                size.width.toFloat(), size.height.toFloat(), newScale,
                            )
                            scale = newScale
                        } else if (dismissDrag) {
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                            val dy = event.changes.map { it.positionChange().y }.sum()
                            dismissDragY = (dismissDragY + dy).coerceAtLeast(0f)
                        }
                        if (pressed.isEmpty()) break
                    }
                    // 收尾：下滑过阈=退出；未过阈弹回（spring 由 animateFloatAsState 承接——
                    // 这里直接归零由 LaunchedEffect 动画化太重，跟手段已过，snap 回 0 即可）
                    if (dismissDrag) {
                        if (PhotoViewerPolicy.shouldDismiss(dismissDragY, size.height.toFloat(), scale)) {
                            android.util.Log.i("R299V", "viewer exit: drag dismiss y=${dismissDragY.toInt()}")
                            currentOnDismiss()
                        } else {
                            dismissDragY = 0f
                        }
                    }
                }
            }
            // tap 层（Main pass）：单击=藏/显 chrome；双击=2.5x↔1x
            .pointerInput(pages) {
                detectTapGestures(
                    onTap = { chromeVisible = !chromeVisible },
                    onDoubleTap = { offset -> doubleTapZoom(offset) },
                )
            },
    ) {
        // 图集 Pager（1x 态横滑切图；放大态禁用让位平移）
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = scale <= 1.01f && dismissDragY <= 0f,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val pageItem = pages[page]
            ViewerImagePage(
                item = pageItem,
                gateway = gateway,
                comicMode = chapterComic,
                scale = if (page == pagerState.settledPage) scale else 1f,
                panOffset = if (page == pagerState.settledPage) panOffset else Offset.Zero,
                dismissDragY = dismissDragY,
                onAspectKnown = { a -> if (page == pagerState.settledPage) currentAspect = a },
                comicScrollAtTopRef = comicScrollAtTop,
                comicInitialScrollPx = comicProgressOf(pageItem),
                onComicScrollSave = { px -> onSaveComicProgress(pageItem, px) },
            )
        }

        // chrome 顶排：关闭 / 页码 / 上一话·下一话（漫画场）/ 保存原图
        if (chromeVisible) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    // chrome 条底=OverlayInfoChipBg 同源 token（黑0.35 散装字面量
                    // 不自造——scrim-audit B21 登记口径）
                    .background(OverlayInfoChipBg)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭查看器",
                    tint = TgFeedTokens.TextHigh,
                    modifier = Modifier
                        .size(TgFeedTokens.TouchMin)
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            android.util.Log.i("R299V", "viewer exit: close button")
                            currentOnDismiss()
                        }
                        .padding(10.dp),
                )
                Text(
                    "${pagerState.settledPage + 1}/${pages.size}",
                    color = TgFeedTokens.TextHigh,
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.weight(1f))
                // 上一话/下一话（漫画场：chapterNavigator 接线且本章为条漫章）
                if (chapterNavigator != null && chapterComic) {
                    Text(
                        "上一话",
                        color = TgFeedTokens.TextHigh,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                scope.launch {
                                    val np = chapterNavigator.invoke(pages, -1)
                                    if (np != null) {
                                        android.util.Log.i("R299V", "viewer chapter prev → ${np.size} 页")
                                        pages = np
                                        pagerState.scrollToPage(0)
                                    } else {
                                        android.widget.Toast.makeText(
                                            context, "没有上一话了", android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "下一话",
                        color = TgFeedTokens.TextHigh,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                scope.launch {
                                    val np = chapterNavigator.invoke(pages, +1)
                                    if (np != null) {
                                        android.util.Log.i("R299V", "viewer chapter next → ${np.size} 页")
                                        pages = np
                                        pagerState.scrollToPage(0)
                                    } else {
                                        android.widget.Toast.makeText(
                                            context, "没有下一话了", android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Icon(
                    Icons.Filled.FileDownload,
                    contentDescription = "保存原图",
                    tint = TgFeedTokens.TextHigh,
                    modifier = Modifier
                        .size(TgFeedTokens.TouchMin)
                        .clip(RoundedCornerShape(50))
                        .clickable { pages.getOrNull(pagerState.settledPage)?.let(onSave) }
                        .padding(10.dp),
                )
            }
        }
    }
}

/** 查看器单页画面：普通页=FIT 整图（变换=缩放+平移+下滑跟手位移）；
 * 条漫章=FillWidth 竖滚（双击/捏合放大后平移，竖滚在 1x 态生效）。
 * 加载链与 PhotoCard 同政策（TdLibGateway.loadPhotoViewPath 浏览档 +
 * [decodePhotoSampled] 采样到屏宽 2 倍，H5 OOM 纪律同口径）。
 */
@Composable
private fun ViewerImagePage(
    item: VideoItem,
    gateway: TdGateway?,
    comicMode: Boolean,
    scale: Float,
    panOffset: Offset,
    dismissDragY: Float,
    onAspectKnown: (Float) -> Unit,
    comicScrollAtTopRef: androidx.compose.runtime.MutableState<Boolean>,
    comicInitialScrollPx: Long,
    onComicScrollSave: (Long) -> Unit,
) {
    val pageContext = LocalContext.current
    val decodeTargetPx = remember { pageContext.resources.displayMetrics.widthPixels * 2 }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, item.key) {
        value = withContext(Dispatchers.IO) {
            val path = item.playUri ?: (gateway as? TdLibGateway)?.let { lib ->
                runCatching { lib.loadPhotoViewPath(item) }.getOrNull()
            }
            path?.removePrefix("file://")?.let { p ->
                runCatching { decodePhotoSampled(p, decodeTargetPx) }.getOrNull()
            }?.asImageBitmap()
        }
    }
    val bmp = bitmap
    LaunchedEffect(bmp) {
        if (bmp != null && bmp.height > 0) onAspectKnown(bmp.width.toFloat() / bmp.height.toFloat())
    }

    if (bmp == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                color = TgFeedTokens.TextHigh,
                strokeWidth = 3.dp,
                trackColor = TgFeedTokens.TextHigh.copy(alpha = 0.2f),
            )
        }
        return
    }

    if (comicMode) {
        // 条漫页：FillWidth 竖滚（1x 态；放大态竖滚禁用让位平移——夹域按整图高算，
        // 平移可走完全图，等价于放大态的滚动）+ 断点续读（进场恢复/离开与周期落盘）
        val scrollState = remember(item.key) { androidx.compose.foundation.ScrollState(0) }
        // 断点恢复：等位图就绪内容有高度后再 scrollTo（ScrollState 初值会被
        // 「内容未测量 maxValue=0」夹掉）
        var restored by remember(item.key) { mutableStateOf(false) }
        LaunchedEffect(item.key, bmp) {
            if (!restored && comicInitialScrollPx > 0) {
                kotlinx.coroutines.delay(50) // 让一帧布局（maxValue 就位）
                scrollState.scrollTo(comicInitialScrollPx.toInt())
            }
            restored = true
        }
        // 「在顶」读位上报（下滑退出仲裁用）+ 周期落盘（1.5s 一拍，进程暴毙保底）
        LaunchedEffect(item.key) {
            var lastSaved = scrollState.value
            while (true) {
                comicScrollAtTopRef.value = scrollState.value <= 2
                delay(1500)
                if (scrollState.value != lastSaved) {
                    lastSaved = scrollState.value
                    onComicScrollSave(lastSaved.toLong())
                }
            }
        }
        DisposableEffect(item.key) {
            onDispose { onComicScrollSave(scrollState.value.toLong()) }
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = panOffset.x
                    translationY = panOffset.y + dismissDragY
                }
                .verticalScroll(scrollState, enabled = scale <= 1.01f),
        ) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        }
    } else {
        Image(
            bitmap = bmp,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = panOffset.x
                    translationY = panOffset.y + dismissDragY
                },
            contentScale = ContentScale.Fit,
        )
    }
}
