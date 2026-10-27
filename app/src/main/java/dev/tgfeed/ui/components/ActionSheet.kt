package dev.tgfeed.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BookmarkAdded
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Comment
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.NotInterested
import androidx.compose.material.icons.outlined.PictureInPicture
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.feed.FeedViewModelFactory
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.core.td.VideoVariant
import dev.tgfeed.ui.SheetMaxWidth
import dev.tgfeed.ui.components.kit.LocalBottomBarInset
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlin.math.abs

/**
 * "加入片单"外部处理器（CompositionLocal，由 FeedScreen 用 CompositionLocalProvider 提供）：
 * 功能面板（VideoActionSheet）渲染在 VideoPage 内部，VideoPage 的参数表本轮冻结不能加回调，
 * 面板与 FeedScreen 之间靠这个 CompositionLocal 穿透——提供时片单选择对话框由 FeedScreen 宿主
 * （能拿到精确的目标条目）；未提供时面板内建兜底（自解共享 VM，目标条目取当前停留页）。
 */
val LocalAddToPlaylist = compositionLocalOf<(() -> Unit)?> { null }

/** 倍速档位（对照抖音面板） */
private val SPEED_OPTIONS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

/** 定时关闭档位：分钟数，null = 不开启 */
private val SLEEP_OPTIONS = listOf<Pair<Int?, String>>(
    null to "不开启",
    15 to "15 分钟",
    30 to "30 分钟",
    60 to "60 分钟",
)

/**
 * 长按视频中间区域弹出的功能面板（ModalBottomSheet，深色圆角）：
 * 行1 推荐 / 稍后再看 / 不感兴趣·该频道 / 屏蔽频道 / 缓存视频 / 悬浮播放 / 加入分屏 / 转存 / 翻译 /
 * 分享（R201 收编：原「复制链接」+「分享卡片」两行并为单行，点按弹分享枢纽小菜单）/
 * [弹幕开关（R175 对调自右轨；仅视频宿主渲染，图/音宿主该格不渲染）] / 发弹幕 /
 * 查看上下文 / [画面调整（R227 收编：原「旋转」+「镜像翻转」两行并为单行，点按弹
 * 画面调整枢纽小菜单——向左/向右旋转/左右镜像/恢复默认，仅视频宿主渲染）] / 静音 /
 * 全屏 / 截图（动作多，横向滚动；后三个为 P0-1 右轨收编项，仅视频页提供回调时才渲染；
 * 弹幕开关与"发弹幕"相邻成组）；
 * 行2 倍速档位 chips（当前档位高亮）；行3 清屏播放；
 * 行4 定时关闭 chips；行5 自动连播 Switch；行6 循环播放 Switch（单条循环）。
 * 面板本身不做业务，全部通过回调上报；是否关面板由 VideoPage 在各回调里收口。
 * 按 [mediaType] 裁行：画面填充/清屏播放仅视频（0）渲染；倍速行图文（1）不渲染（无倍速语义）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class) // BOM 2024.09.02 中 ModalBottomSheet / FlowRow 均已转正；保留注解兼容 foundation<1.7，多余时仅是警告
@Composable
internal fun VideoActionSheet(
    speed: Float,
    autoAdvance: Boolean,
    sleepTimerMinutes: Int?,
    onDismiss: () -> Unit,
    onRecommend: () -> Unit,
    onWatchLater: () -> Unit,
    onNotInterested: () -> Unit,
    // 屏蔽频道（频道级硬屏蔽，与内容级"不感兴趣"并存）：点击先弹面板内建确认框（防误触），
    // 确认后调本回调。null = 宿主未接线（VideoPage 参数冻结期）→ 走面板内建兜底：
    // 自解与 FeedScreen 共享的 FeedViewModel，目标条目取当前停留页（同 AddToPlaylistSheet 兜底链路）
    onBlockChannel: (() -> Unit)? = null,
    onCache: () -> Unit,
    onPopout: () -> Unit,
    onAddToGrid: () -> Unit,
    // 转存到 TG 收藏夹 / 一键翻译标题（VideoPage 已预埋回调；默认值保持旧调用兼容）
    onForward: () -> Unit = {},
    onTranslate: () -> Unit = {},
    // 加入片单：通知型回调（在弹片单选择器之前触发）；VideoPage 本轮冻结不接它，
    // 实际链路走 LocalAddToPlaylist（FeedScreen 宿主选择对话框）或面板内建兜底
    onAddToPlaylist: () -> Unit = {},
    onSetSpeed: (Float) -> Unit,
    // 面板宿主的媒体类型（0=视频 1=图片 2=音频，口径同 VideoItem.mediaType）：
    // 视频专属行（画面填充/清屏播放）仅 mediaType==0 渲染；倍速行对图文（无倍速语义）不渲染，
    // 音频保留（倍速直连共享播放器真实生效）。默认 0 保持视频页旧调用不变
    mediaType: Int = 0,
    // 清晰度档位：当前视频的备选分辨率（数据层已按 height 降序去重；空/<2 档不渲染该行）、
    // 当前选中的目标高度（0=自动，跟随主档）、设定回调（0 或某档 height；全局偏好，FeedScreen 落 AppPrefs）
    variants: List<VideoVariant> = emptyList(),
    currentQualityHeight: Int = 0,
    onSetQuality: (Int) -> Unit = {},
    // 填充模式（当前值 + 切换回调）：填满=Crop 完整=FIT
    fillToScreen: Boolean = true,
    onSetFillMode: (Boolean) -> Unit = {},
    onClearScreen: () -> Unit,
    onSetSleepTimer: (Int?) -> Unit,
    onSetAutoAdvance: (Boolean) -> Unit,
    // 循环播放（单条循环）：当前值 + 切换回调；默认值保持旧调用兼容
    loopOne: Boolean = false,
    onToggleLoop: (Boolean) -> Unit = {},
    // R201 分享收编：原「复制链接」「分享卡片」两行（两宿主各直跳一条链路）收编为
    // 单行「分享」——点按弹 ShareHubSheet 枢纽小菜单三选一（复制链接/分享到其他
    // 应用/分享卡片，链体在宿主 FeedScreen 枢纽接线收口）。默认值保持旧调用兼容
    onShare: () -> Unit = {},
    // R175 弹幕开关（原右轨第二格原样迁来）：非空 = 视频页宿主，渲染"弹"字形开关格
    //（开启态红点角标，图标/角标/回调与右轨原样）；null = 图/音宿主未接线，本格不渲染
    //（R201 前该 null 分支渲染「分享卡片」，分享语义已收编进上面「分享」行）
    danmakuEnabled: Boolean = false,
    onToggleDanmaku: (() -> Unit)? = null,
    // 发弹幕到 TG 广场（广场开启时有意义）
    onSendDanmaku: () -> Unit = {},
    // R205 TL-9 弹幕时间旅行入口：弹幕入口旁的「弹幕回放」格（拉广场历史弹幕流
    // 渲染时间轴——纯回放视图）。null = 宿主未接线，本格不渲染
    onOpenDanmakuTimeline: (() -> Unit)? = null,
    // R204 DS-5 首发溯源：同指纹跨频道最早出现判定（「查看上下文」同排先例——
    // 溯源也属条目级考据动作）；null = 宿主未接线，本格不渲染
    onFirstSeen: (() -> Unit)? = null,
    // 查看上下文：切到该条所在频道 Feed 并定位到本条（FeedViewModel.openChannelAt 链路）；
    // 默认值保持旧调用兼容
    onViewContext: () -> Unit = {},
    // R202 TL-4 事件考古：以该条发布时刻为锚点开时光机 ±48h 全网横截面（与「查看
    // 上下文」/「首发溯源」同族——查看上下文=纵向频道内前后，首发溯源=单点考据，
    // 本格=横向看全网该事件前后的反应面）。默认值保持旧调用兼容
    onEventDig: () -> Unit = {},
    // R227 画面调整收编：原「旋转」+「镜像翻转」两格（各自直跳一个姿态动作）收编为
    // 单格「画面调整」——点按弹 FrameAdjustHubSheet 枢纽小菜单四选一（向左旋转/
    // 向右旋转/左右镜像/恢复默认；复合态记忆在 FrameAdjustStore 键级缓存，每条视频
    // 独立、跨会话保持）。frameAdjusted=非默认姿态时红点角标（弹幕开关/镜像行同工艺）。
    // null = 宿主未接线（图/音面板，画面姿态仅视频有语义），本格不渲染
    frameAdjusted: Boolean = false,
    onOpenFrameAdjust: (() -> Unit)? = null,
    // ---- P0-1 右轨收敛：静音/全屏/截图从右轨收进本面板（视频专属动作）----
    // null = 调用方未提供（图/音面板不传）→ 对应格子不渲染，不做静默假动作
    muted: Boolean = false,
    onToggleMute: (() -> Unit)? = null,
    onToggleFullscreen: (() -> Unit)? = null,
    onScreenshot: (() -> Unit)? = null,
    // R233 口味星图二期：在星图中查看——跳进星图并运镜飞向该条目所属恒星
    //（Galaxy 双形态入口；「查看上下文/看同期」同排的条目级动作）。null = 宿主未
    // 接线，本格不渲染（静音格同口径——不假动作）
    onOpenTasteMap: (() -> Unit)? = null,
    // R299 B1 听单入口（仅音频宿主接线渲染，design §2.1）：加入听单（入尾）/
    // 下一首播放（插队首）。null = 宿主未接线，本格不渲染（不假动作）
    onEnqueueListen: (() -> Unit)? = null,
    onPlayNextListen: (() -> Unit)? = null,
) {
    // 片单选择器显隐（内建兜底路径用；FeedScreen 经 LocalAddToPlaylist 接管时不会走到）
    var showPlaylistPicker by remember { mutableStateOf(false) }
    // "屏蔽频道"确认对话框显隐（防误触：点动作先弹确认，确认才执行）
    var showBlockConfirm by remember { mutableStateOf(false) }
    // R413 "屏蔽关键词"输入框显隐；输入由用户自填（刻意不从标题抽词预填）
    var showKeywordMute by remember { mutableStateOf(false) }
    // FeedScreen 提供的"加入片单"外部处理器；null = 未提供，走面板内建兜底
    val externalAddToPlaylist = LocalAddToPlaylist.current
    // P3-g：面板本地 Toast 用的 Context（图/音面板"悬浮播放"置灰项的点击说明）
    val context = LocalContext.current
    // 屏蔽频道内建兜底链路的共享 VM：仅宿主未传 onBlockChannel 时解析（如 VideoPage
    // 参数冻结期）；与 AddToPlaylistSheet 同一招——同 key 的 activity 作用域共享实例
    val blockFallbackVm: FeedViewModel? = if (onBlockChannel == null) {
        val app = context.applicationContext as? android.app.Application
        val gateway = (app as? dev.tgfeed.App)?.container?.gateway?.value
        if (app != null && gateway != null) {
            viewModel(factory = FeedViewModelFactory(gateway, app), key = "gw" + System.identityHashCode(gateway))
        } else {
            null
        }
    } else {
        null
    }
    // R387 加入队列内建兜底 VM：无宿主回调参数（VideoPage/FeedScreen 参数表冻结期
    // 纪律——行 1 新格零参数面新增），面板自解共享 FeedViewModel（blockFallbackVm
    // 同一招，同 key 取回 activity 作用域既有实例），目标=当前停留页条目
    val queueApp = context.applicationContext as? android.app.Application
    val queueGateway = (queueApp as? dev.tgfeed.App)?.container?.gateway?.value
    val queueFallbackVm: FeedViewModel? = if (queueApp != null && queueGateway != null) {
        viewModel(
            factory = FeedViewModelFactory(queueGateway, queueApp),
            key = "gw" + System.identityHashCode(queueGateway),
        )
    } else {
        null
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // R171：560dp 字面量收敛进 Responsive.SheetMaxWidth（弹层统一口径，零行为变化）
        sheetMaxWidth = SheetMaxWidth, // 平板上限宽居中（铺满巨屏的面板没法看）
        // 内容较高（6 行），跳过半展开态，直接全展开
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = TgFeedTokens.RadiusLg, topEnd = TgFeedTokens.RadiusLg),
        containerColor = TgFeedTokens.Surface,
        contentColor = TgFeedTokens.TextHigh,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // 矮屏（如横屏）下内容可滚动，保证 6 行都够得着
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                // 2026-10-17 弹窗被透明 dock 挡底修复：长按面板消费 dock inset
                .padding(bottom = LocalBottomBarInset.current + 16.dp),
        ) {
            // 行 1：推荐 / 稍后再看 / 不感兴趣·该频道 / 屏蔽频道 / 缓存视频 / 悬浮播放 / 加入分屏 / 转存收藏夹 / 翻译标题 / 分享 / [弹幕开关（仅视频宿主）]
            // 8 个动作 weight 均分挤不下，改横向滚动 + 固定格宽；
            // B3：右缘叠渐隐色带作为"可继续横滑"提示（原先末位动作被可视区裁半，看着像溢出），
            // 滚动末尾再补 16dp 呼吸位——滑到最右时最后一个动作也不贴可视区边
            val actionScroll = rememberScrollState()
            Box(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(actionScroll)
                        .padding(vertical = 6.dp),
                ) {
                    // R299 B1 听单两格（仅音频宿主接线才渲染——图/视频面板无语义不渲染）：
                    // 加入听单（入尾，攒着连续听）/ 下一首播放（插队首）。
                    // R306 置行首：V11 专席实锤音频面板两格排在横滑行第 9/10 位
                    //（19 格行初始可视区仅前 ~8 格），专席横滑前 dump 判定「缺格」
                    // 挂账——队列一等化的招牌动作必须首屏即见（音频面板与视频/图面板
                    // 行序本就因裁行不同，置首不破坏跨型一致性）
                    if (mediaType == 2 && onEnqueueListen != null) {
                        SheetAction(
                            Icons.Outlined.PlaylistAdd, "加入听单", Modifier.width(72.dp),
                            onClick = { onEnqueueListen?.invoke() },
                        )
                    }
                    if (mediaType == 2 && onPlayNextListen != null) {
                        SheetAction(
                            Icons.Outlined.PlaylistPlay, "下一首播放", Modifier.width(72.dp),
                            onClick = { onPlayNextListen?.invoke() },
                        )
                    }
                    SheetAction(Icons.Outlined.ThumbUp, "推荐", Modifier.width(72.dp), onRecommend)
                    SheetAction(Icons.Outlined.WatchLater, "稍后再看", Modifier.width(72.dp), onWatchLater)
                    SheetAction(Icons.Outlined.NotInterested, "不感兴趣", Modifier.width(72.dp), onNotInterested)
                    // 屏蔽频道：频道级硬屏蔽（整条频道从推荐/搜索/聚合消失），与内容级
                    // "不感兴趣"并存；点击不立即执行——先弹确认框（防误触，渲染在本面板尾部）。
                    // 动作行是横滑布局（固定 72dp 格宽），加一个动作不存在放不下的问题
                    SheetAction(
                        Icons.Outlined.Block, "屏蔽频道", Modifier.width(72.dp),
                        onClick = { showBlockConfirm = true },
                    )
                    // R413 屏蔽关键词：弹输入框，确认即生效（推荐/关注/搜索全链过滤，当前列表即时剔除）
                    SheetAction(
                        Icons.Outlined.VisibilityOff, "屏蔽关键词", Modifier.width(72.dp),
                        onClick = { showKeywordMute = true },
                    )
                    SheetAction(Icons.Outlined.FileDownload, "缓存视频", Modifier.width(72.dp), onCache)
                    // P3-g/B13：图/音面板（按 P0-1 约定 onToggleMute==null 即图/音宿主）没有视频可悬浮——
                    // "悬浮播放"置灰，点击本地弹说明、不关面板也不上报宿主：反馈收进面板本身，
                    // 不再依赖宿主记得接 Toast（宿主 FeedScreen 的 onPopout Toast 在该路径不再触发，不会双弹）
                    val popoutUnsupported = onToggleMute == null
                    SheetAction(
                        Icons.Outlined.PictureInPicture,
                        "悬浮播放",
                        Modifier.width(72.dp),
                        onClick = {
                            if (popoutUnsupported) {
                                android.widget.Toast.makeText(
                                    context, "图文内容暂不支持悬浮窗播放", android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                onPopout()
                            }
                        },
                        enabled = !popoutUnsupported,
                    )
                    SheetAction(Icons.Outlined.GridView, "加入分屏", Modifier.width(72.dp), onAddToGrid)
                    SheetAction(Icons.Outlined.BookmarkAdded, "转存收藏夹", Modifier.width(72.dp), onForward)
                    SheetAction(Icons.Outlined.Translate, "翻译标题", Modifier.width(72.dp), onTranslate)
                    // R201 分享收编：原「复制链接」格（视频/图音两宿主都有）+ 图/音宿主的
                    // 「分享卡片」格（R175 后视频宿主该槽已是弹幕开关）两行收编为单格
                    // 「分享」——点按弹枢纽小菜单三选一；IosShare 图标沿用分享卡片时代
                    // 的图标族（面板里的分享语义位）
                    SheetAction(Icons.Outlined.IosShare, "分享", Modifier.width(72.dp), onShare)
                    // R175 弹幕/分享对调（原分享卡片位）：
                    //  - 视频页宿主（onToggleDanmaku 非空）→ 弹幕开关格（原右轨第二格
                    //    原样迁来：弹字形图标 + 开启态红点角标 + onToggleDanmaku）。
                    //    与相邻"发弹幕"成组：开关管显示、发送管内容
                    //  - 图/音面板宿主（null）→ 本格不渲染（分享语义已收编进「分享」格）
                    val danmakuToggle = onToggleDanmaku
                    if (danmakuToggle != null) {
                        SheetAction(
                            Icons.Outlined.Comment, // textGlyph 生效时占位不用
                            label = if (danmakuEnabled) "弹幕已开" else "弹幕已关",
                            modifier = Modifier.width(72.dp),
                            onClick = danmakuToggle,
                            textGlyph = "弹",
                            badge = danmakuEnabled,
                        )
                    }
                    SheetAction(Icons.Outlined.Comment, "发弹幕", Modifier.width(72.dp), onSendDanmaku)
                    // R205 TL-9 弹幕回放（弹幕入口旁：开关管显示、发送管内容、回放管历史
                    // ——「发过的弹幕后来怎样了」的回看口）。宿主未接线不渲染
                    val danmakuTimelineAction = onOpenDanmakuTimeline
                    if (danmakuTimelineAction != null) {
                        SheetAction(Icons.Outlined.History, "弹幕回放", Modifier.width(72.dp), danmakuTimelineAction)
                    }
                    // 查看上下文：跳到该频道里本条的前后（剧集续刷场景）
                    SheetAction(Icons.Outlined.CenterFocusStrong, "查看上下文", Modifier.width(72.dp), onViewContext)
                    // R204 DS-5 首发溯源（条目级考据动作，「查看上下文」同排）：
                    // 同指纹跨频道最早出现判定——点按付费（全表扫由面板转圈承接）。
                    // 宿主未接线不渲染（图/音面板不传也不假动作）
                    val firstSeenAction = onFirstSeen
                    if (firstSeenAction != null) {
                        SheetAction(Icons.Outlined.Flag, "首发溯源", Modifier.width(72.dp), firstSeenAction)
                    }
                    // R202 TL-4 事件考古→R215 ④ 更名「看同期」（更直白，用户秒懂）：
                    // 该条发布前后 ±48h 的全网横截面（谁在跟、谁先发的——本地索引
                    // 毫秒级，零请求零配额）；交互零变化
                    SheetAction(Icons.Outlined.Schedule, "看同期", Modifier.width(72.dp), onEventDig)
                    // R233④ 在星图中查看：跳进口味星图并运镜飞向该条目所属恒星
                    //（双形态跳转——条目级「这颗星在哪」的动作；宿主未接线不渲染）
                    val tasteMapAction = onOpenTasteMap
                    if (tasteMapAction != null) {
                        SheetAction(Icons.Outlined.AutoAwesome, "在星图中查看", Modifier.width(72.dp), tasteMapAction)
                    }
                    // R227 画面调整收编（原 R174「旋转」格 + R218「镜像翻转」格两行
                    // 合一——旋转的 +90 步进绕圈与镜像的来回 toggle 升级成枢纽里的
                    // 显式方向选项 + 恢复默认）：点按关本面板、弹 FrameAdjustHubSheet
                    // 枢纽小菜单四选一（onOpenFrameAdjust 由宿主收口）；非默认姿态
                    //（旋转非 0 或已镜像）红点角标（弹幕开关同工艺）。宿主未接线
                    //（图/音面板）不渲染
                    val frameAdjustAction = onOpenFrameAdjust
                    if (frameAdjustAction != null) {
                        SheetAction(
                            Icons.Outlined.ScreenRotation,
                            "画面调整",
                            Modifier.width(72.dp),
                            frameAdjustAction,
                            badge = frameAdjusted,
                        )
                    }
                    // P0-1：从右轨收进面板的视频专属三件套——静音 / 全屏 / 截图。
                    // 回调为 null（图/音面板未提供）时不渲染，不做静默假动作
                    val muteAction = onToggleMute
                    if (muteAction != null) {
                        SheetAction(
                            if (muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                            if (muted) "取消静音" else "静音",
                            Modifier.width(72.dp),
                            muteAction,
                        )
                    }
                    val fullscreenAction = onToggleFullscreen
                    if (fullscreenAction != null) {
                        SheetAction(Icons.Outlined.Fullscreen, "全屏", Modifier.width(72.dp), fullscreenAction)
                    }
                    val screenshotAction = onScreenshot
                    if (screenshotAction != null) {
                        SheetAction(Icons.Outlined.Screenshot, "截图", Modifier.width(72.dp), screenshotAction)
                    }
                    // 加入片单：优先交给 FeedScreen（经 CompositionLocal，能拿到精确条目）；
                    // 未提供时弹面板内建的片单选择器兜底
                    // （enabled 尾随参数占了 lambda 末位，这里必须用命名参数 onClick =）
                    SheetAction(
                        Icons.Outlined.PlaylistAdd, "加入片单", Modifier.width(72.dp),
                        onClick = {
                            onAddToPlaylist()
                            val ext = externalAddToPlaylist
                            if (ext != null) {
                                // 外部宿主接管：先关本面板再弹选择对话框（与其他动作"触发即关"口径一致）
                                onDismiss()
                                ext()
                            } else {
                                // 内建兜底：选择器渲染在本面板尾部，本面板必须留在组合里，不能先关
                                showPlaylistPicker = true
                            }
                        },
                    )
                    // R387 加入队列（「稍后再看入单」的 feed 腿——r387_notes §3.2：刷到即
                    // 存队列按序播；与既有「稍后再看」两系统并存不互斥）：无宿主回调参数
                    //（参数表冻结期纪律），内建兜底 VM 自解共享实例，目标=当前停留页条目
                    //（长按面板只可能出现在可见页，实践中即 settled page=长按的那张卡——
                    // 屏蔽频道兜底同款口径）；幂等分流 Toast（同 key 已在单不重复入）
                    SheetAction(
                        Icons.Outlined.QueueMusic, "加入队列", Modifier.width(72.dp),
                        onClick = {
                            val vm = queueFallbackVm
                            val target = vm?.items?.value?.getOrNull(vm.currentIndex.value)
                            when {
                                vm == null || target == null -> android.widget.Toast.makeText(
                                    context, "当前没有可入单的条目", android.widget.Toast.LENGTH_SHORT,
                                ).show()
                                else -> {
                                    val added = vm.enqueueItemToPlayQueue(target)
                                    android.widget.Toast.makeText(
                                        context,
                                        if (added) "已加入播放队列" else "已在播放队列里",
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                            onDismiss()
                        },
                    )
                    // B3：滚动末尾呼吸位（配合外层 Column horizontal=20dp 内边距，内容两端均不贴屏幕边）
                    Spacer(Modifier.width(TgFeedTokens.GapLg))
                }
                ScrollEndFade(actionScroll)
            }
            SheetDivider()

            // 行 2：倍速档位 chips，当前档位高亮；点击设定并关面板（关闭由调用方收口）。
            // 图文条目无倍速语义（行内 chips 点了无任何效果）——只对视频/音频渲染
            if (mediaType != 1) {
            SheetTitle("倍速")
            // P2-d/B3：6 档 chips 总宽 ~350dp，360dp 竖屏初始屏必裁末档"3x"——横滑+渐隐
            // 仍会在初始态把 chip 裁到只剩个角；档位少而固定，改 FlowRow 折行全显示，彻底不裁边
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
            ) {
                SPEED_OPTIONS.forEach { option ->
                    SheetChip(
                        text = formatSpeed(option),
                        selected = abs(speed - option) < 0.01f,
                        onClick = { onSetSpeed(option) },
                    )
                }
            }
            SheetDivider()
            }

            // 行 2.2：清晰度档位 chips：自动 + 该视频各分辨率档（1080p/720p/480p…按 height 标注）。
            // 2026-10-17 改永远渲染（用户实锤「找不到清晰度切换」：旧行仅 ≥2 档才显示，而 TG
            // 不做服务端转码——频道传什么就什么，多数视频单档 → 行几乎永不出现=功能隐形）。
            // 单档时如实标注「TG 按上传画质提供」保可发现性+机制教育；多档行为不变。
            // 选中态：currentQualityHeight==0 高亮"自动"，否则高亮同高那一档
            run {
                SheetTitle("清晰度")
                // B3：同倍速行——可横滑 + 右缘渐隐提示 + 末尾呼吸位
                val qualityScroll = rememberScrollState()
                Box(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(qualityScroll),
                        horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                    ) {
                        SheetChip(
                            text = "自动",
                            selected = currentQualityHeight == 0,
                            onClick = { onSetQuality(0) },
                        )
                        variants.forEach { variant ->
                            SheetChip(
                                text = "${variant.height}p",
                                selected = currentQualityHeight == variant.height,
                                onClick = { onSetQuality(variant.height) },
                            )
                        }
                        if (variants.size < 2) {
                            // 单档视频：标注机制（TG 不转码，画质随上传；多档=频道自己传的变体）
                            Text(
                                "仅一档 · TG 按上传画质提供",
                                style = MaterialTheme.typography.labelSmall,
                                color = TgFeedTokens.TextLow,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            )
                        }
                        Spacer(Modifier.width(TgFeedTokens.GapSm))
                    }
                    ScrollEndFade(qualityScroll)
                }
                SheetDivider()
            }

            // 行 2.5：画面填充（填满裁切 不留黑边 / 完整显示 留黑边）。
            // 仅视频有填充语义（图/音面板未接线，chips 点了不高亮不生效）——非视频不渲染
            if (mediaType == 0) {
            SheetTitle("画面填充")
            Row(horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
                SheetChip(text = "填满", selected = fillToScreen, onClick = { onSetFillMode(true) })
                SheetChip(text = "完整", selected = !fillToScreen, onClick = { onSetFillMode(false) })
            }
            SheetDivider()
            }

            // 行 3：清屏播放（仅视频有清屏态；图/音点它只会关面板，不渲染）
            if (mediaType == 0) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
                    .clickable(onClick = onClearScreen)
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.VisibilityOff,
                    contentDescription = null,
                    tint = TgFeedTokens.TextHigh,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(TgFeedTokens.GapMd))
                Column {
                    Text("清屏播放", color = TgFeedTokens.TextHigh, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "隐藏所有控件，单击任意处恢复",
                        color = TgFeedTokens.TextLow,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            SheetDivider()
            }

            // 行 4：定时关闭 chips（选中态由调用方镜像传入）
            SheetTitle("定时关闭")
            // P2-d/B3：与倍速行同理改 FlowRow 折行——截图 24 中"60 分钟"整格被裁到贴右缘；
            // 4 档总宽 ~340dp 超 360dp 竖屏可用宽（320dp），折行全显示，竖屏/折叠屏通用
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
            ) {
                SLEEP_OPTIONS.forEach { (minutes, label) ->
                    SheetChip(
                        text = label,
                        selected = sleepTimerMinutes == minutes,
                        onClick = { onSetSleepTimer(minutes) },
                    )
                }
            }
            SheetDivider()

            // 行 5：自动连播 Switch（切换不关面板，便于立即看到状态）
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Repeat,
                    contentDescription = null,
                    tint = TgFeedTokens.TextHigh,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(TgFeedTokens.GapMd))
                Text(
                    "自动连播",
                    color = TgFeedTokens.TextHigh,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = autoAdvance,
                    onCheckedChange = onSetAutoAdvance,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TgFeedTokens.TextHigh,
                        checkedTrackColor = TgFeedTokens.TgBlue,
                    ),
                )
            }
            SheetDivider()

            // 行 6：循环播放 Switch（单条循环，RepeatOne 与"自动连播"的 Repeat 图标区分；
            // 状态由 Feed 级共享，切换不关面板，便于立即看到状态）
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.RepeatOne,
                    contentDescription = null,
                    tint = TgFeedTokens.TextHigh,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(TgFeedTokens.GapMd))
                Column(Modifier.weight(1f)) {
                    Text("循环播放", color = TgFeedTokens.TextHigh, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "单条播完自动重播，不再跳下一条",
                        color = TgFeedTokens.TextLow,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = loopOne,
                    onCheckedChange = onToggleLoop,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TgFeedTokens.TextHigh,
                        checkedTrackColor = TgFeedTokens.TgBlue,
                    ),
                )
            }
        }
    }

    // 内建兜底的片单选择器（仅当 FeedScreen 未提供 LocalAddToPlaylist 时才会被置位打开；
    // 渲染在本面板尾部 → 选择器期间本面板保持打开，选完/取消后回到本面板）
    if (showPlaylistPicker) {
        AddToPlaylistSheet(onDismiss = { showPlaylistPicker = false })
    }

    // R413 "屏蔽关键词"输入对话框：共享 VM（queueFallbackVm 同 key 取回 activity 作用域实例）
    if (showKeywordMute) {
        var kw by remember { mutableStateOf("") }
        val submitKw: () -> Unit = {
            val vm = queueFallbackVm
            if (vm == null) {
                android.widget.Toast.makeText(context, "当前无法屏蔽关键词", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                val err = vm.addMutedKeyword(kw) { n ->
                    android.widget.Toast.makeText(context, "已屏蔽 $n 条", android.widget.Toast.LENGTH_SHORT).show()
                }
                if (err != null) {
                    android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    showKeywordMute = false
                    onDismiss()
                }
            }
        }
        AlertDialog(
            onDismissRequest = { showKeywordMute = false },
            title = { Text("屏蔽关键词") },
            text = {
                Column {
                    Text(
                        "含该词的内容将不再出现在推荐、关注流与搜索中，可在设置→关键词屏蔽里管理",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = kw,
                        onValueChange = { kw = it.take(dev.tgfeed.core.feed.KeywordMutePolicy.MAX_LEN + 10) },
                        singleLine = true,
                        placeholder = { Text("输入关键词") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = submitKw, enabled = kw.isNotBlank()) { Text("屏蔽") }
            },
            dismissButton = {
                TextButton(onClick = { showKeywordMute = false }) { Text("取消") }
            },
        )
    }

    // "屏蔽频道"确认对话框（防误触）：渲染在本面板尾部，弹窗期间面板保持打开，取消即回面板。
    // 确认后两条路径：
    //  - 宿主已接线（onBlockChannel 非空，FeedScreen 图/音面板）：屏蔽+Toast+关面板全由宿主收口；
    //  - 未接线（VideoPage 视频面板参数冻结期）：面板内建兜底——自解共享 VM，目标取当前停留页条目
    if (showBlockConfirm) {
        AlertDialog(
            onDismissRequest = { showBlockConfirm = false },
            title = { Text("屏蔽该频道？") },
            text = { Text("屏蔽后该频道从推荐/搜索/聚合中消失，可在设置里解除") },
            confirmButton = {
                TextButton(onClick = {
                    showBlockConfirm = false
                    val ext = onBlockChannel
                    if (ext != null) {
                        // 宿主接线路径：目标条目精确（长按的那张卡），Toast 与关面板由宿主收口
                        ext()
                    } else {
                        // 内建兜底路径：目标 = 共享 VM 当前停留页条目
                        //（长按面板只可能出现在可见页，实践中即 settled page）
                        val vm = blockFallbackVm
                        val target = vm?.items?.value?.getOrNull(vm.currentIndex.value)
                        when {
                            vm == null || target == null -> android.widget.Toast.makeText(
                                context, "当前没有可屏蔽的频道", android.widget.Toast.LENGTH_SHORT,
                            ).show()
                            // toggleBlockChannel 是翻转语义——已在屏蔽表的不再调（再调=解除屏蔽）
                            target.chatId in vm.blockedChannels.value -> android.widget.Toast.makeText(
                                context, "该频道已在屏蔽列表中，可在设置里解除", android.widget.Toast.LENGTH_SHORT,
                            ).show()
                            else -> {
                                vm.toggleBlockChannel(target.chatId)
                                android.widget.Toast.makeText(
                                    context, "已屏蔽，可在设置→屏蔽的频道里解除", android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                        onDismiss()
                    }
                }) { Text("屏蔽") }
            },
            dismissButton = {
                TextButton(onClick = { showBlockConfirm = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 片单选择对话框（"加入片单"的目标选择；ModalBottomSheet，深色圆角与本文件面板同一语言）：
 * 列表 = 现有片单（名称 + 条数）+ "新建片单"行（展开为输入框，确定 = 新建并加入）；
 * 底部另附"管理我的片单"入口（跳播放列表管理页，路由经 AppContainer）。
 *
 * 两个宿主共用：
 *  - FeedScreen（经 [LocalAddToPlaylist] 接管，[item] 参数传入精确的目标条目）；
 *  - VideoActionSheet 内建兜底（[item] = null 时取共享 VM 当前停留页条目）。
 * 数据与动作都直挂共享 FeedViewModel（与 FeedScreen 同 key："gw"+identityHashCode(gateway)）。
 */
@OptIn(ExperimentalMaterial3Api::class) // ModalBottomSheet 同上面板：已转正，注解兼容旧版
@Composable
internal fun AddToPlaylistSheet(
    onDismiss: () -> Unit,
    item: VideoItem? = null,
) {
    val context = LocalContext.current
    val app = context.applicationContext as android.app.Application
    val container = (app as? dev.tgfeed.App)?.container
    val gateway = container?.gateway?.value
    if (gateway == null) {
        // 容器/网关未就绪（理论上只在 Feed 之外被误用）：立即关闭，不渲染内容
        androidx.compose.runtime.LaunchedEffect(Unit) { onDismiss() }
        return
    }
    // 与 FeedScreen 共享 activity 作用域的同一个 ViewModel（片单数据同源）
    val vm: FeedViewModel = viewModel(
        factory = FeedViewModelFactory(gateway, app),
        key = "gw" + System.identityHashCode(gateway),
    )
    val playlists by vm.playlists.collectAsState()
    // 目标条目：外部传入优先；兜底取当前停留页（长按面板只可能出现在可见页，实践中即 settled page）
    val target = item ?: vm.items.value.getOrNull(vm.currentIndex.value)
    // 新建片单输入行展开态 + 草稿
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetMaxWidth = SheetMaxWidth, // R171 收敛：Responsive.SheetMaxWidth（与本文件面板同一规格）
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = TgFeedTokens.RadiusLg, topEnd = TgFeedTokens.RadiusLg),
        containerColor = TgFeedTokens.Surface,
        contentColor = TgFeedTokens.TextHigh,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()) // 片单很多时内容可滚动
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                // 2026-10-17 弹窗被透明 dock 挡底修复：片单 sheet 同款 inset
                .padding(bottom = LocalBottomBarInset.current + 16.dp),
        ) {
            SheetTitle("加入片单")
            if (playlists.isEmpty() && !creating) {
                Text(
                    "还没有片单，先新建一个",
                    color = TgFeedTokens.TextLow,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            // 现有片单：名称 + 条数，点击即加入并关闭（加入动作内部幂等：同 key 已在片单则忽略）
            playlists.forEach { p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
                        .clickable {
                            val t = target
                            if (t == null) {
                                android.widget.Toast.makeText(
                                    context, "当前没有可加入的条目", android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                vm.addToPlaylist(p.id, t)
                                android.widget.Toast.makeText(
                                    context, "已加入「${p.name}」", android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                            onDismiss()
                        }
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.PlaylistAdd,
                        contentDescription = null,
                        tint = TgFeedTokens.TextHigh,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(TgFeedTokens.GapMd))
                    Text(
                        p.name,
                        color = TgFeedTokens.TextHigh,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${p.count} 条",
                        color = TgFeedTokens.TextLow,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            SheetDivider()

            // 新建片单：未展开是入口行；展开为输入框 + 确定（确定 = 新建并加入当前条目）
            if (creating) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = { Text("片单名称") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        val name = newName.trim()
                        if (name.isEmpty()) return@TextButton
                        // 建单即加入首条（VM 单协程内串行完成，见 createPlaylist 注释）；
                        // 目标条目为空时退化为纯建单
                        vm.createPlaylist(name, target)
                        android.widget.Toast.makeText(
                            context,
                            if (target != null) "已创建「$name」并加入" else "已创建「$name」",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                        onDismiss()
                    }) { Text("确定") }
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
                        .clickable { creating = true }
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = null,
                        tint = TgFeedTokens.TextHigh,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(TgFeedTokens.GapMd))
                    Text(
                        "新建片单",
                        color = TgFeedTokens.TextHigh,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            SheetDivider()

            // 播放列表管理页入口（整单连播 / 重命名 / 删除）
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
                    .clickable {
                        container?.openPlaylists()
                        onDismiss()
                    }
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.List,
                    contentDescription = null,
                    tint = TgFeedTokens.TextHigh,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(TgFeedTokens.GapMd))
                Text(
                    "管理我的片单",
                    color = TgFeedTokens.TextHigh,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

/**
 * 面板行 1 的动作格：圆形图标底托 + 小标签，等宽排列。
 * enabled=false 为置灰态（P3-g：图/音面板里的视频专属动作）——仍可点，点击由调用方弹说明。
 * R175 弹幕开关迁入配套：[textGlyph] 非空时以文字字形替代矢量图标（Material 无弹幕
 * 图标，与 ActionRail.RailIcon 的 textGlyph 同一手法）；[badge]=true 时底托右上角
 * 小红点角标（开启态语义，工艺同 RailIcon 徽章：AccentRed + OverlayBadgeBorder 描边）。
 */
@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    enabled: Boolean = true,
    textGlyph: String? = null,
    badge: Boolean = false,
) {
    // 置灰态统一降透明度（底托/图标/标签），视觉即"不可用"——R201 收编进
    // TgFeedTokens.DisabledContentAlpha（与 AppListItem 禁用行同一数字一处来源）
    val contentAlpha = if (enabled) 1f else TgFeedTokens.DisabledContentAlpha
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(TgFeedTokens.SheetActionBadge)
                .background(TgFeedTokens.SurfaceHigh.copy(alpha = contentAlpha), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (textGlyph != null) {
                // 文字字形图标：与矢量图标同底托居中，加粗对齐图标视觉重量（RailIcon 同款）
                Text(
                    textGlyph,
                    color = TgFeedTokens.TextHigh.copy(alpha = contentAlpha),
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                )
            } else {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = TgFeedTokens.TextHigh.copy(alpha = contentAlpha),
                    modifier = Modifier.size(22.dp),
                )
            }
            if (badge) {
                // 小红点角标（开启态，与右轨徽章同工艺）：深色描边防亮底融边
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-2).dp)
                        .size(8.dp)
                        .background(TgFeedTokens.AccentRed, CircleShape)
                        .border(1.dp, OverlayBadgeBorder, CircleShape),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            color = TgFeedTokens.TextMedium.copy(alpha = contentAlpha),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** 档位 chip：全圆角胶囊；选中白底黑字高亮，未选表面二级灰底 */
@Composable
private fun SheetChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text,
        color = if (selected) Color.Black else TgFeedTokens.TextHigh.copy(alpha = 0.85f),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (selected) Color.White else TgFeedTokens.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** 小节标题（倍速 / 定时关闭）：三级文字 + 拉开字距 */
@Composable
private fun SheetTitle(text: String) {
    Text(
        text,
        color = TgFeedTokens.TextLow,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

/** 行间隔线 */
@Composable
private fun SheetDivider() {
    HorizontalDivider(
        color = TgFeedTokens.TextHigh.copy(alpha = 0.08f),
        modifier = Modifier.padding(vertical = 14.dp),
    )
}

/**
 * 横向滚动行的右缘渐隐提示（B3）：
 * 内容超宽（maxValue>0）且未滑到底时，在右缘叠一条"透明 → 面板底色"的渐变色带，
 * 明确表达"右侧还有内容可滑"，避免末位图标/chip 被可视区边界裁半时看着像布局溢出。
 * 纯色 Box 不挂手势，点击/滑动全部穿透到底下的滚动行。
 */
@Composable
private fun BoxScope.ScrollEndFade(scroll: ScrollState) {
    if (scroll.maxValue > 0 && scroll.value < scroll.maxValue) {
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(TgFeedTokens.GapXl)
                .fillMaxHeight()
                .background(
                    Brush.horizontalGradient(
                        0f to TgFeedTokens.Surface.copy(alpha = 0f),
                        1f to TgFeedTokens.Surface,
                    ),
                ),
        )
    }
}
