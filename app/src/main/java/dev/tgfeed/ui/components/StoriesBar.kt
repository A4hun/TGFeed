package dev.tgfeed.ui.components

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.tgfeed.core.td.ChannelStoriesBrief
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * 频道 Stories 快拍条（R144，ideas.md #5，design-redesign.md §3.7 预制规范）。
 *
 * 两个部件：
 *  - [StoriesBar]：Feed 顶栏下方的头像横滑带。没看完=红环（复用"刚冒出来的"红环语言，
 *    红环语义与"今日新内容"锁死）、看完=灰环；名称下带"剩 N 小时"过期紧迫感标签
 *    （stories 24h 限时是天然的打开钩子）。无 story 频道不进条（调用方 briefs 为空整条不渲染，
 *    不占位——多数中小频道没有 story，快拍条有内容才出现）。
 *  - [StoryViewer]：点开后的满屏竖卡沉浸流（§3.7"满屏竖卡沉浸流+顶部进度条段"）：
 *    上下滑切 story（频道内旧→新连播，频道尾自动接下一个频道），图片 5s 计时、
 *    视频播完自动进下一条，最后一条播完=整轮看完收起查看器。
 *    已看状态走网关会话级账（noteStoryWatched，不持久化、重启即忘）。
 *
 * 视觉全部落在 TgFeedTokens（红环/进度段=AccentRed 活跃进度语义，灰环=TextLow），
 * 文字带浮层阴影（withOverlayShadow）；进出场只用 AnimFast/Med/Slow 三档节律（挂载点控制）。
 */
// ---------------- 常量 ----------------

/** 快拍生命周期（TG 口径）：发布后 24h 到期 */
private const val STORIES_TTL_SEC = 24 * 3600

/** 图片快拍停留时长（连播节奏，与官方客户端同档） */
private const val STORY_PHOTO_DWELL_MS = 5_000L

/** 视频播放进度回报采样间隔 */
private const val STORY_PROGRESS_POLL_MS = 100L

/** 快拍条单项宽（头像 62dp + 两侧留白，横滑一屏约见 4~5 个，与"刚冒出来的"密度一致） */
private val StoryBarCellWidth = 74.dp

/** 快拍环外径（红环 1.5dp 略粗=与 DiscoverFreshMiniCard 的红环语言同口径） */
private val StoryRingSize = 62.dp
private val StoryRingWidthHot = 1.5.dp
private val StoryRingWidthGray = 1.dp

/** 字母头像柔和底色板（与 DiscoverAvatarPalette 同族调色板；按频道 id 哈希稳定取色） */
private val StoriesAvatarPalette = listOf(
    Color(0xFF5C6BC0), // 柔靛蓝
    Color(0xFF26A69A), // 柔青绿
    Color(0xFFAB47BC), // 柔紫
    Color(0xFFFFA726), // 柔橙
    Color(0xFF42A5F5), // 柔蓝
    Color(0xFF66BB6A), // 柔绿
    Color(0xFFEC407A), // 柔粉
)

private fun storiesAvatarColor(channelId: Long): Color =
    StoriesAvatarPalette[Math.floorMod(channelId, StoriesAvatarPalette.size)]

// ---------------- 快拍条（横滑带） ----------------

/**
 * Feed 顶栏下的 stories 圆环带。briefs 为空时调用方直接不挂载（空态不占位，需求钉死）。
 * titleOf 是零网络标题快照（channelTitleCache → 图谱，网关 peekChatTitle 通道）；
 * avatarLoader 与频道卡共用（内部带进程内缓存，Mock 返回 null 回落字母圆）。
 */
@Composable
internal fun StoriesBar(
    briefs: List<ChannelStoriesBrief>,
    watched: Set<String>,
    titleOf: (Long) -> String?,
    avatarLoader: (suspend (Long) -> ByteArray?)?,
    onOpen: (ChannelStoriesBrief) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .height(116.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(horizontal = 10.dp),
    ) {
        items(briefs, key = { it.chatId }) { brief ->
            val hasNew = brief.hasUnwatched(watched)
            val title = titleOf(brief.chatId) ?: "频道"
            Column(
                Modifier
                    .width(StoryBarCellWidth)
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onOpen(brief) }
                    .padding(vertical = TgFeedTokens.GapXs),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StoriesAvatar(
                    chatId = brief.chatId,
                    title = title,
                    avatarLoader = avatarLoader,
                    ringColor = if (hasNew) TgFeedTokens.AccentRed else TgFeedTokens.TextLow,
                    ringWidth = if (hasNew) StoryRingWidthHot else StoryRingWidthGray,
                )
                Spacer(Modifier.height(TgFeedTokens.GapXs))
                Text(
                    title,
                    color = TgFeedTokens.TextHigh,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 过期紧迫感：story 24h 限时，越临近到期越红（<6h）；到期兜底灰字
                val (expiryText, expiryColor) = storyExpiryLabel(brief.latestDateSec)
                Text(
                    expiryText,
                    color = expiryColor,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 快拍过期标签："剩 N 分钟/剩 N 小时"（数字时间统一中文体）；<6h 转红催促 */
private fun storyExpiryLabel(latestDateSec: Int): Pair<String, Color> {
    val remainSec = latestDateSec + STORIES_TTL_SEC - System.currentTimeMillis() / 1000L
    return when {
        remainSec <= 0 -> "已到期" to TgFeedTokens.TextLow
        remainSec < 3600 -> "剩 ${remainSec / 60} 分钟" to TgFeedTokens.AccentRed
        remainSec < 6 * 3600 -> "剩 ${remainSec / 3600} 小时" to TgFeedTokens.AccentRed
        else -> "剩 ${remainSec / 3600} 小时" to TgFeedTokens.TextLow
    }
}

/**
 * 圆环头像（快拍条与连播页头部共用）：真头像圆形裁切，缺头像回落字母圆
 * （id 稳定调色板）；ringColor 非 null 时外套环（红环=没看完，灰环=已看完）。
 */
@Composable
private fun StoriesAvatar(
    chatId: Long,
    title: String,
    avatarLoader: (suspend (Long) -> ByteArray?)?,
    size: Dp = StoryRingSize,
    ringColor: Color? = null,
    ringWidth: Dp = 1.dp,
) {
    val ring = if (ringColor != null) Modifier.border(ringWidth, ringColor, CircleShape) else Modifier
    Box(
        Modifier
            .size(size)
            .then(ring)
            .padding(TgFeedTokens.GapXs),
        contentAlignment = Alignment.Center,
    ) {
        val avatarImage = rememberAvatarBitmap(chatId, avatarLoader)
        if (avatarImage != null) {
            Image(
                bitmap = avatarImage,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(storiesAvatarColor(chatId)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title.firstOrNull()?.uppercase() ?: "?",
                    color = TgFeedTokens.TextHigh,
                    style = TextStyle(
                        fontSize = if (size >= StoryRingSize) 17.sp else 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
        }
    }
}

/** 频道头像异步加载（入组合拉一次，loader 内部带进程内缓存；R194④ 解码收编
 *  rememberChannelAvatarImage——IO 解码，与频道卡同族） */
@Composable
private fun rememberAvatarBitmap(
    chatId: Long,
    avatarLoader: (suspend (Long) -> ByteArray?)?,
): ImageBitmap? = rememberChannelAvatarImage(chatId, avatarLoader)

// ---------------- 沉浸连播查看器 ----------------

/** 连播页的一"格"：频道 + storyId（频道主序平铺，频道内旧→新——连播从最早的未看条起播） */
private data class StorySlot(
    val brief: ChannelStoriesBrief,
    val storyId: Int,
    /** 本条在频道内的序号（0 起；顶部进度条段与起播定位用） */
    val indexInChannel: Int,
)

/** 单条快拍加载结果（produceState 容器：null=加载中，Failed=失败） */
private sealed interface StoryLoad {
    data class Ok(val story: TdApi.Story) : StoryLoad
    data object Failed : StoryLoad
}

/**
 * 满屏沉浸连播：竖滑切 story（[VerticalPager] 平铺全部频道的活跃 stories，
 * 频道内旧→新，频道尾自动接下一个频道）；图片 5s 计时、视频播完自动进下一条，
 * 最后一条播完=整轮看完，收起查看器。左 30% 点按=上一条、右侧点按=下一条
 * （快拍通用手势，与竖滑并存）；返回键/关闭钮收起。
 * 已看状态经 [gateway.noteStoryWatched] 记会话账（不持久化，重启即忘）。
 */
@Composable
internal fun StoryViewer(
    gateway: TdLibGateway,
    briefs: List<ChannelStoriesBrief>,
    initialChatId: Long,
    watched: Set<String>,
    titleOf: (Long) -> String?,
    avatarLoader: (suspend (Long) -> ByteArray?)?,
    onDismiss: () -> Unit,
) {
    // 平铺格（打开时刻的快照——看的过程中红环转灰不重排连播列表）
    val slots = remember(briefs) {
        briefs.flatMap { b -> b.storyIds.mapIndexed { i, id -> StorySlot(b, id, i) } }
    }
    // 起播页：点中的频道内第一条没看完的；全看过则从最新一条开始
    val initialPage = remember(briefs, initialChatId) {
        val idx = briefs.indexOfFirst { it.chatId == initialChatId }
        if (idx < 0) 0 else {
            val base = briefs.subList(0, idx).sumOf { it.storyIds.size }
            val b = briefs[idx]
            val firstUnwatched = b.storyIds.indexOfFirst { b.isStoryUnwatched(it, watched) }
            base + (if (firstUnwatched >= 0) firstUnwatched else b.storyIds.lastIndex)
        }
    }
    // 防御：快照全空（条上点开必然非空，理论进不来）不崩不挂
    if (slots.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { slots.size })
    // 当前条的进度（0f..1f）：当前生效页的计时/播放器回报喂进来，顶部进度段渲染用
    var currentProgress by remember { mutableStateOf(0f) }
    // 当前条发布时间（详情加载完成时由当前页回报；头部"N 小时前"时间行用）
    var currentStoryDate by remember { mutableStateOf<Int?>(null) }
    // 静音（快拍默认有声，头部钮一键静音；TG 官方同默认）
    var muted by remember { mutableStateOf(false) }
    val latestDismiss = rememberUpdatedState(onDismiss)

    BackHandler { latestDismiss.value() }

    // 结算当前生效页：清零进度/时间行 + 记已看（会话账，快拍条红环实时转灰）
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .filter { it >= 0 }
            .collect { page ->
                currentProgress = 0f
                currentStoryDate = null
                slots.getOrNull(page)?.let { gateway.noteStoryWatched(it.brief.chatId, it.storyId) }
            }
    }

    fun advanceFrom(page: Int) {
        if (page < slots.lastIndex) {
            scope.launch { pagerState.animateScrollToPage(page + 1) }
        } else {
            latestDismiss.value()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(TgFeedTokens.InkBlack),
    ) {
        VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val slot = slots[page]
            StoryPage(
                gateway = gateway,
                slot = slot,
                isActive = page == pagerState.settledPage,
                muted = muted,
                onPrev = { if (page > 0) scope.launch { pagerState.animateScrollToPage(page - 1) } },
                onNext = { if (page == pagerState.currentPage) advanceFrom(page) },
                onAdvance = { if (page == pagerState.currentPage) advanceFrom(page) },
                onProgress = { fraction ->
                    if (page == pagerState.settledPage) currentProgress = fraction
                },
                onLoadedDate = { date ->
                    if (page == pagerState.settledPage) currentStoryDate = date
                },
            )
        }

        // 顶部 chrome 最后压上（进度段 + 频道信息行）
        val currentSlot = slots.getOrNull(pagerState.settledPage)
        if (currentSlot != null) {
            StoryViewerHeader(
                slot = currentSlot,
                progress = currentProgress,
                storyDateSec = currentStoryDate,
                muted = muted,
                titleOf = titleOf,
                avatarLoader = avatarLoader,
                onToggleMute = { muted = !muted },
                onClose = { latestDismiss.value() },
            )
        }
    }
}

/** 顶部 chrome：进度条段（当前频道一段一格）+ 头像/频道名/相对时间/静音/关闭 */
@Composable
private fun StoryViewerHeader(
    slot: StorySlot,
    progress: Float,
    storyDateSec: Int?,
    muted: Boolean,
    titleOf: (Long) -> String?,
    avatarLoader: (suspend (Long) -> ByteArray?)?,
    onToggleMute: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = TgFeedTokens.GapMd, vertical = TgFeedTokens.GapSm),
    ) {
        // 进度条段：已播=满白、在播=红条按进度、未播=灰轨道（§3.7"顶部进度条段"）
        Row(
            Modifier
                .fillMaxWidth()
                .height(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(slot.brief.storyIds.size) { i ->
                val fraction = when {
                    i < slot.indexInChannel -> 1f
                    i == slot.indexInChannel -> progress.coerceIn(0f, 1f)
                    else -> 0f
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(TgFeedTokens.TextLow.copy(alpha = 0.35f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(
                                if (i == slot.indexInChannel) TgFeedTokens.AccentRed
                                else TgFeedTokens.TextHigh,
                            ),
                    )
                }
            }
        }
        Spacer(Modifier.height(TgFeedTokens.GapSm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StoriesAvatar(
                chatId = slot.brief.chatId,
                title = titleOf(slot.brief.chatId) ?: "频道",
                avatarLoader = avatarLoader,
                size = 30.dp,
            )
            Spacer(Modifier.width(TgFeedTokens.GapSm))
            Text(
                titleOf(slot.brief.chatId) ?: "频道",
                color = TgFeedTokens.TextHigh,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            storyDateSec?.let { sec ->
                Spacer(Modifier.width(TgFeedTokens.GapSm))
                Text(
                    storyAgeLabel(sec),
                    color = TgFeedTokens.TextMedium,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onToggleMute) {
                Icon(
                    if (muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                    contentDescription = if (muted) "取消静音" else "静音",
                    tint = TgFeedTokens.TextHigh,
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "关闭快拍",
                    tint = TgFeedTokens.TextHigh,
                )
            }
        }
    }
}

/** story 相对时间（中文体）：刚刚 / N 分钟前 / N 小时前 / N 天前 */
private fun storyAgeLabel(dateSec: Int): String {
    val minutes = (System.currentTimeMillis() / 1000L - dateSec).coerceAtLeast(0L) / 60L
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 24 * 60 -> "${minutes / 60} 小时前"
        else -> "${minutes / (24 * 60)} 天前"
    }
}

// ---------------- 单条快拍页 ----------------

/**
 * 连播单页：加载详情（GetStory）→ 按内容分支渲染。
 *  - 图片：浏览档全屏（Crop 装帧）+ 5s 计时（图就绪才起表）到点自动进下一条；
 *  - 视频：流式起播（网关 loadStoryVideoUri），播完自动进下一条；minithumbnail 模糊压顶防黑闪；
 *  - 直播/不支持：一句话 + 跳过。
 * 左 30% 点按上一条、右侧点按下一条（快拍通用手势，与竖滑翻页并存）。
 * isActive=false（划走/邻页）时暂停计时与播放。
 */
@Composable
private fun StoryPage(
    gateway: TdLibGateway,
    slot: StorySlot,
    isActive: Boolean,
    muted: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onAdvance: () -> Unit,
    onProgress: (Float) -> Unit,
    onLoadedDate: (Int) -> Unit,
) {
    val latestOnAdvance = rememberUpdatedState(onAdvance)
    val latestOnProgress = rememberUpdatedState(onProgress)
    val latestOnLoadedDate = rememberUpdatedState(onLoadedDate)
    var retry by remember(slot.brief.chatId, slot.storyId) { mutableStateOf(0) }
    val detail: State<StoryLoad?> = produceState<StoryLoad?>(null, slot.brief.chatId, slot.storyId, retry) {
        value = gateway.loadStory(slot.brief.chatId, slot.storyId)
            ?.let { StoryLoad.Ok(it) } ?: StoryLoad.Failed
    }
    // 详情就绪：回报发布时间（头部时间行）
    LaunchedEffect(detail.value) {
        (detail.value as? StoryLoad.Ok)?.let { latestOnLoadedDate.value(it.story.date) }
    }
    // minithumbnail 模糊底衬（图片/视频都有：加载/起播窗口防黑屏，VideoSurfaceLayer 同手法）
    val miniCover: ImageBitmap? = remember(detail.value, retry) {
        (detail.value as? StoryLoad.Ok)?.story?.let { s ->
            extractStoryMini(s)?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (val d = detail.value) {
            null -> StoryLoadingContent()
            is StoryLoad.Failed -> StoryFailedContent(
                onRetry = { retry++ },
                onNext = onNext,
            )
            is StoryLoad.Ok -> when (val content = d.story.content) {
                is TdApi.StoryContentPhoto -> StoryPhotoContent(
                    gateway = gateway,
                    content = content,
                    miniCover = miniCover,
                    isActive = isActive,
                    onPrev = onPrev,
                    onNext = onNext,
                    onAdvance = { latestOnAdvance.value() },
                    onProgress = { latestOnProgress.value(it) },
                )
                is TdApi.StoryContentVideo -> StoryVideoContent(
                    gateway = gateway,
                    content = content,
                    miniCover = miniCover,
                    isActive = isActive,
                    muted = muted,
                    onPrev = onPrev,
                    onNext = onNext,
                    onAdvance = { latestOnAdvance.value() },
                    onProgress = { latestOnProgress.value(it) },
                )
                else -> StoryUnsupportedContent(
                    text = if (content is TdApi.StoryContentLive) "这是直播快拍，暂时放不了" else "这条快拍不支持展示",
                    onNext = onNext,
                )
            }
        }

        // caption 压底（所有内容形态共用；空 caption 不占位）。
        // R162 零遮罩：旧版这里垫 0.55 黑渐变衬底（R144），caption 文字本身已带
        // withOverlayShadow 描边（视频页同款基准），渐变删除后内容原色不被染色
        val captionText = (detail.value as? StoryLoad.Ok)?.story?.caption?.text
        if (!captionText.isNullOrBlank()) {
            Text(
                captionText,
                color = TgFeedTokens.TextHigh,
                style = MaterialTheme.typography.bodyMedium.withOverlayShadow(),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapLg)
                    .navigationBarsPadding(),
            )
        }
    }
}

/** 从 story 内容里取 minithumbnail 字节（photo/video 两形态都有） */
private fun extractStoryMini(story: TdApi.Story): ByteArray? = when (val c = story.content) {
    is TdApi.StoryContentPhoto -> c.photo.minithumbnail?.data
    is TdApi.StoryContentVideo -> c.video.minithumbnail?.data
    else -> null
}

/** 模糊底衬（minithumbnail 放大+高斯模糊铺满；低版本无模糊退化可用） */
@Composable
private fun MiniCoverBackdrop(miniCover: ImageBitmap?) {
    if (miniCover == null) return
    Image(
        bitmap = miniCover,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = 1.25f
                scaleY = 1.25f
            }
            .blur(30.dp),
    )
}

/** 加载态：转圈 + 文案（快拍是轻内容，加载窗口极短） */
@Composable
private fun BoxScope.StoryLoadingContent() {
    Column(
        Modifier.align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(40.dp),
            color = TgFeedTokens.TextHigh,
            strokeWidth = 3.dp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "正在加载快拍…",
            color = TgFeedTokens.TextHigh,
            style = MaterialTheme.typography.bodyMedium.withOverlayShadow(),
        )
    }
}

/** 失败态：原因 + 下一步动作（重试/跳过；§3.5 状态层文案规范） */
@Composable
private fun BoxScope.StoryFailedContent(onRetry: () -> Unit, onNext: () -> Unit) {
    Column(
        Modifier.align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "这条快拍加载失败",
            color = TgFeedTokens.TextHigh,
            style = MaterialTheme.typography.titleSmall.withOverlayShadow(),
        )
        Spacer(Modifier.height(TgFeedTokens.GapXs))
        Text(
            "网络抖动或快拍已过期",
            color = TgFeedTokens.TextMedium,
            style = MaterialTheme.typography.bodySmall.withOverlayShadow(),
        )
        Spacer(Modifier.height(TgFeedTokens.GapMd))
        Row {
            TextButton(onClick = onRetry) {
                Text("重试", color = TgFeedTokens.TgBlue)
            }
            TextButton(onClick = onNext) {
                Text("跳过", color = TgFeedTokens.TextMedium)
            }
        }
    }
}

/** 不支持态（直播快拍/未来新形态）：一句话 + 跳过 */
@Composable
private fun BoxScope.StoryUnsupportedContent(text: String, onNext: () -> Unit) {
    Column(
        Modifier.align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            color = TgFeedTokens.TextHigh,
            style = MaterialTheme.typography.titleSmall.withOverlayShadow(),
        )
        Spacer(Modifier.height(TgFeedTokens.GapMd))
        TextButton(onClick = onNext) {
            Text("跳过", color = TgFeedTokens.TgBlue)
        }
    }
}

// ---------------- 图片快拍 ----------------

@Composable
private fun BoxScope.StoryPhotoContent(
    gateway: TdLibGateway,
    content: TdApi.StoryContentPhoto,
    miniCover: ImageBitmap?,
    isActive: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onAdvance: () -> Unit,
    onProgress: (Float) -> Unit,
) {
    // 图片路径就绪前先铺 minithumbnail 模糊底衬（防白屏闪）
    MiniCoverBackdrop(miniCover)
    // 浏览档图片（网关同步下载，最长边采样解码防大图白扔内存）
    val path by produceState<String?>(null, content) {
        value = gateway.loadStoryPhotoPath(content)
    }
    val bitmap = remember(path) { path?.let { decodeStoryPhotoSampled(it) } }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "快拍图片",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        StoryLoadingContent()
    }
    // 图片 5s 计时：图就绪 + 当前生效页才起表（进度回报驱动顶部进度段，到点自动下一条）
    val latestOnAdvance = rememberUpdatedState(onAdvance)
    val latestOnProgress = rememberUpdatedState(onProgress)
    LaunchedEffect(isActive, bitmap) {
        if (!isActive || bitmap == null) return@LaunchedEffect
        val start = System.currentTimeMillis()
        while (currentCoroutineContextIsActive()) {
            val fraction = ((System.currentTimeMillis() - start).toFloat() / STORY_PHOTO_DWELL_MS)
                .coerceIn(0f, 1f)
            latestOnProgress.value(fraction)
            if (fraction >= 1f) {
                latestOnAdvance.value()
                break
            }
            delay(50)
        }
    }
    // 左右点按区（快拍通用手势：左 30% 上一条，右 70% 下一条；与竖滑翻页并存）
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (offset.x < size.width * 0.3f) onPrev() else onNext()
                }
            },
    )
}

/** 图片采样解码（最长边钳到 1440px：story 图片是竖版大图，直接 decode 会白扔几倍内存） */
private fun decodeStoryPhotoSampled(path: String): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (longest / (sample * 2) >= 1440) sample *= 2
    BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )?.asImageBitmap()
}.getOrNull()

// ---------------- 视频快拍 ----------------

@Composable
private fun BoxScope.StoryVideoContent(
    gateway: TdLibGateway,
    content: TdApi.StoryContentVideo,
    miniCover: ImageBitmap?,
    isActive: Boolean,
    muted: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onAdvance: () -> Unit,
    onProgress: (Float) -> Unit,
) {
    val context = LocalContext.current
    // 起播地址（网关流式链：本地完成回路径/未完成回流服务器）+ TDLib 口径时长
    val media by produceState<Pair<String, Double>?>(null, content) {
        value = gateway.loadStoryVideoUri(content)
    }
    // 单页独享播放器（story 是短竖片；页面销毁即释放，不进 Feed 的 PlayerPool）
    val player = remember(media?.first) {
        media?.first?.let { uri ->
            ExoPlayer.Builder(context)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true, // 来电/其他 App 发声自动闪避暂停
                )
                .build()
                .apply {
                    setMediaItem(MediaItem.fromUri(uri))
                    prepare()
                    playWhenReady = false
                }
        }
    }
    DisposableEffect(player) {
        onDispose { player?.release() }
    }
    // 首帧前 minithumbnail 模糊压顶（防黑闪）
    var firstFrame by remember(media?.first) { mutableStateOf(false) }
    DisposableEffect(player) {
        val p = player ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrame = true
            }
        }
        p.addListener(listener)
        onDispose { p.removeListener(listener) }
    }
    // 播完自动进下一条（只认当前生效页，防划走邻页的陈旧 ENDED 抢跑）
    val latestOnAdvance = rememberUpdatedState(onAdvance)
    val activeRef = rememberUpdatedState(isActive)
    DisposableEffect(player) {
        val p = player ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && activeRef.value) latestOnAdvance.value()
            }
        }
        p.addListener(listener)
        onDispose { p.removeListener(listener) }
    }
    // 生效页起播 / 失效页暂停（回看已播完的会归零重播）
    LaunchedEffect(player, isActive) {
        val p = player ?: return@LaunchedEffect
        if (isActive) {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            p.play()
        } else {
            p.pause()
        }
    }
    // 静音联动
    LaunchedEffect(player, muted) {
        player?.volume = if (muted) 0f else 1f
    }
    // 播放进度回报（驱动顶部进度段；100ms 采样）
    val latestOnProgress = rememberUpdatedState(onProgress)
    LaunchedEffect(player, isActive) {
        val p = player ?: return@LaunchedEffect
        val fallbackDurMs = ((media?.second ?: 0.0) * 1000L).toLong()
        while (currentCoroutineContextIsActive()) {
            if (isActive && (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_ENDED)) {
                val d = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: fallbackDurMs
                if (d > 0) latestOnProgress.value((p.currentPosition.toFloat() / d).coerceIn(0f, 1f))
            }
            delay(STORY_PROGRESS_POLL_MS)
        }
    }

    MiniCoverBackdrop(miniCover)
    if (player != null) {
        AndroidView(
            factory = { ctx ->
                // TextureView 版 PlayerView（player_cell，Feed 同款）：透明底不留黑边，
                // 装帧 ZOOM 裁满屏——story 视频是竖版满屏形态
                (android.view.LayoutInflater.from(ctx)
                    .inflate(dev.tgfeed.R.layout.player_cell, null) as PlayerView).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        StoryLoadingContent()
    }
    // 左右点按区（与图片页同款；压在播放层之上、chrome 之下）
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (offset.x < size.width * 0.3f) onPrev() else onNext()
                }
            },
    )
}

/** 协程存活判定（LaunchedEffect 循环的退出条件：取消即停，不用裸 isActive 名字防与参数混淆） */
private suspend fun currentCoroutineContextIsActive(): Boolean =
    kotlinx.coroutines.currentCoroutineContext().isActive
