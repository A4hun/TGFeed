package dev.tgfeed.ui.components

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import dev.tgfeed.ui.components.kit.LocalBottomBarInset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.tgfeed.core.feed.DownloadStatus
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.ui.landscapeFeedChromeOrNull
import dev.tgfeed.ui.rememberWindowSize
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.launch

/**
 * 视频页的右轨与底部信息区（R131 从 VideoPage.kt 按职责拆出，纯搬运零逻辑改动）。
 * 收纳：右侧操作栏列（频道头像入口 + ActionRail，含点赞弹簧弹跳）、
 * 横屏全屏退出钮、底部信息区列（合集导航行 + BottomInfoPanel）。
 * 显隐守卫（清屏/全屏）仍在 VideoPage.kt（组合时机零改动）；
 * 均为 [BoxScope] 扩展，Modifier.align 依赖调用方页 Box 作用域（保持原解析）。
 */

/**
 * 6) 右侧操作栏（P0-1 收敛后 6 格）。
 *    列顶 = 频道头像（带 +追角标，进主页入口）；其下 ActionRail =
 *    赞 / 分享 / 收藏 / 下载 / 旋转。
 *    静音 / 全屏 / 截图已收进长按功能面板（VideoActionSheet），不再占右轨；
 *    R175 弹幕/分享对调：分享卡片上右轨（原弹幕开关位），弹幕开关迁长按面板。
 * 横屏频道头像：经历「左上角（用户嫌怪）→移除（底部信息区承担）」两轮后，
 * 2026-10-16 用户明确要求恢复右轨位（点赞上方）——竖屏横屏统一右轨第一格，
 * 底部信息区头像保留（横屏双入口并存，用户偏好胜过去重）。
 */
@Composable
internal fun BoxScope.VideoRightRail(
    orientation: Int,
    uiScale: Float,
    chatId: Long,
    channelTitle: String,
    avatarLoader: (suspend (Long) -> ByteArray?)?,
    subscribed: Boolean,
    onOpenChannelHome: () -> Unit,
    onSubscribe: () -> Unit,
    liked: Boolean,
    favorited: Boolean,
    // R432 重组收敛：下载表 State 本体透传（ActionRail 内现查 downloadKey）
    downloads: State<Map<String, DownloadStatus>>,
    downloadKey: String,
    // R175 对调上右轨 → R201 分享枢纽：点按弹 ShareHubSheet 小菜单三选一
    //（复制链接/分享到其他应用/分享卡片；宿主 FeedScreen.onOpenShareHub 收口）
    onShare: () -> Unit,
    rotationDeg: Int,
    onRotate: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownload: () -> Unit,
    likeBounce: Animatable<Float, AnimationVector1D>,
    // R138 点赞回写：长按点赞钮查本条回写状态 / 已送达小绿点（null、false = 旧调用不变）
    onLikeLongPress: (() -> Unit)? = null,
    likeWritebackDelivered: Boolean = false,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .navigationBarsPadding()
            // 横屏底 padding 需平衡两端：
            // R50 起与热区显式挂钩：热区上沿距屏底 10+48=58dp（R194⑥ 贴拢后；
            // ProgressLine 线下垫 24→10dp 同拍）；
            // R173 底栏让位后进度细线随 LocalBottomBarInset 抬 44dp（横屏 dock 高），
            // 热区上沿变 102dp → 右轨底距同步 58+44=102 才不进热区（R173 挂账①兑现，
            // 不乘档位系数——热区本身不随档位缩放）。
            // 列顶侧：1849e84 恢复横屏右轨头像后列高 = 头像+间距+6 格，16416 横屏
            // 实测列顶仍落顶栏下沿之下、两头都不压（验证矩阵见 r178_notes）。
            // 竖屏 96dp：R194⑥ 前是 110dp（旧热区 116 顶沿 + 平衡余量），热区收窄
            // 同拍 -14dp（走查无回归）
            .padding(
                end = 10.dp * uiScale,
                bottom = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 102.dp else 96.dp * uiScale,
            ),
    ) {
        // 频道主页入口头像（CHANNEL-HOME-FINAL P0）：右栏第一格（带 +追/✓ 角标）。
        // 横屏曾经历「挪左上角（用户嫌怪）→移除（底部信息区承担）」两轮，2026-10-16
        // 用户明确要求恢复横屏右轨位（点赞钮上方）——竖屏横屏统一右轨第一格。
        // R225 横屏可伸展 A 档：头像按政策放大（56dp，用户实锤「头像太小」）；
        // 竖屏/矮横屏 null → 默认 48dp 现状零变化。
        val landscapeChrome = landscapeFeedChromeOrNull(
            rememberWindowSize(),
            LocalConfiguration.current.screenHeightDp,
        )
        RailChannelAvatar(
            chatId = chatId,
            title = channelTitle,
            avatarLoader = avatarLoader,
            subscribed = subscribed,
            onOpenHome = onOpenChannelHome,
            onSubscribe = onSubscribe,
            avatarSize = landscapeChrome?.railAvatarSize ?: 48.dp,
        )
        Spacer(Modifier.height(TgFeedTokens.RailGap))
        ActionRail(
            liked = liked,
            favorited = favorited,
            downloads = downloads,
            downloadKey = downloadKey,
            // R201 分享枢纽：点按弹小菜单三选一（链体在 FeedScreen 枢纽接线）；
            // 弹幕开关在长按面板
            onShare = onShare,
            // 倍速右轨位让给画面旋转（倍速在长按面板里）：cycleRotation 与面板共用同一闭包
            rotationDeg = rotationDeg,
            onRotate = onRotate,
            onToggleLike = onToggleLike,
            onToggleFavorite = onToggleFavorite,
            onDownload = onDownload,
            // R138：长按点赞钮 → 回写状态查询；已送达 → 点赞钮右下角小绿点
            onLikeLongPress = onLikeLongPress,
            likeWritebackDelivered = likeWritebackDelivered,
            // 双击点赞时整列以列顶（≈点赞心所在的第一格）为锚点弹簧弹跳一下。
            // 点赞图标在 ActionRail 内部，从外部能挂的只有这个 modifier——
            // 锚点取列顶，观感集中在点赞钮上
            modifier = Modifier.graphicsLayer {
                scaleX = likeBounce.value
                scaleY = likeBounce.value
                transformOrigin = TransformOrigin(0.5f, 0f)
            },
        )
    }
}

/**
 * 6.5) 全屏态退出按钮（清屏时也隐藏：单击先恢复浮层，再点退出；系统返回键始终可退）
 */
@Composable
internal fun BoxScope.FullscreenExitButton(
    uiScale: Float,
    onExit: () -> Unit,
) {
    Icon(
        Icons.Filled.FullscreenExit,
        contentDescription = "退出全屏",
        tint = Color.White,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .statusBarsPadding()
            .padding(top = 16.dp, end = 16.dp)
            // R162 收敛共享 token（值同 0.45，一处改全局生效）
            .background(OverlayChipBg, RoundedCornerShape(22.dp))
            .clickable(onClick = onExit)
            .padding(8.dp)
            .size(28.dp * uiScale),
    )
}

/**
 * 7) 底部信息区（右侧给操作栏留位）。
 */
@Composable
internal fun BoxScope.VideoBottomInfo(
    item: VideoItem,
    uiScale: Float,
    albumGroupKeys: List<String>,
    feedVm: FeedViewModel,
    onOpenChannel: () -> Unit,
    onSearchTag: (String) -> Unit,
    translatedCaption: String?,
    onTranslate: (VideoItem) -> Unit,
    avatarLoader: (suspend (Long) -> ByteArray?)?,
    subscribed: Boolean,
    onSubscribe: () -> Unit,
    plazaWatching: Int,
    plazaLikes: Int,
    onOpenLink: (String) -> Unit,
    onOpenMention: (String) -> Unit,
    // R269 「在星图中定位」：R234「你在这里」宇宙坐标行改可点（透传 BottomInfoPanel；
    // null = 行纯展示不可点——R234 原貌）
    onLocateInGalaxy: (() -> Unit)? = null,
) {
    // R225 横屏可伸缩档：信息区限宽（复用 R171 SheetMaxWidth 560dp 可读宽口径）——
    // 用户实锤横屏左右失衡的左半边根因：fillMaxWidth 让文字横贯整列（列宽
    // 683~1402dp）。竖屏/矮横屏政策给 null → 不挂 widthIn 节点，逐字节现状。
    // 右轨让位（end 84dp）与锚定（BottomStart）不动：限宽后锚左、右侧给 daylight，
    // 窄列（≤683dp）时 560dp 封顶不生效、行为同现状。
    val infoCap = landscapeFeedChromeOrNull(
        rememberWindowSize(),
        LocalConfiguration.current.screenHeightDp,
    )?.infoMaxWidth
    Column(
        verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
        modifier = Modifier
            .align(Alignment.BottomStart)
            // R225 限宽必须放在 fillMaxWidth 之前：fill 节点按「外层约束的 maxWidth」
            // 上报自身宽度、无视内层链的收窄结果（16384 实测 fillMaxWidth().widthIn()
            // 顺序时限宽被覆盖——列 683dp 信息区仍横贯整列）；widthIn 在前先把
            // 约束的 max 收到 560dp，fillMaxWidth 落位在收窄后的 max 上 ✓
            .then(if (infoCap != null) Modifier.widthIn(max = infoCap) else Modifier)
            .fillMaxWidth()
            .navigationBarsPadding()
            // 边距乘档位系数；end 是给操作栏的让位，随操作栏外扩同步放大。
            // R194⑥ 底部贴拢：32→18dp·档位（用户实测「信息区还在高位、与 dock 之间
            // 空地大，要整体往下贴」）——线在 inset+10dp、信息区底 ≈inset+18dp，
            // 与细线留 ~8dp 呼吸（时间数字在线内右下角，错层共存不动）；
            // R173 底栏让位：信息区随底栏显隐同拍抬落（清屏时 inset 归零回原位）
            .padding(start = 14.dp * uiScale, end = 84.dp * uiScale, bottom = 18.dp * uiScale + LocalBottomBarInset.current),
    ) {
        // 合集（相册）标识行：albumId≠0 的多媒体消息成员显示"🖼 合集 序号/总数"，
        // 同组 ≥2 名成员时给"上一张/下一张"在组内切换（跳转走
        // FeedViewModel.jumpToAlbumSibling → resumeScroll → FeedScreen pager 滚动，
        // 本页与 FeedScreen 之间零新增接口）；组里只有一名可见成员时退化为纯标识
        if (item.albumId != 0L) {
            AlbumNavRow(
                groupKeys = albumGroupKeys,
                currentKey = item.key,
                onJump = { delta -> feedVm.jumpToAlbumSibling(item, delta) },
            )
        }
        // R238 评论数（相册组聚合）：讨论线程属于整个相册帖，replyCount 只挂在
        // 载体成员上（常是携带 caption 的首成员=图片）——组内取 max 让任何成员
        // 都显示「N 条评论」。非相册条目用自身 commentCount（toVideoItem 直读
        // interactionInfo.replyInfo.replyCount）。DB 直出早期帧=0 不显示（网络页
        // 覆盖后出现，诚实缺省）。
        val commentCount = if (item.albumId != 0L) {
            feedVm.albumGroupOf(item).maxOfOrNull { it.commentCount } ?: 0
        } else {
            item.commentCount
        }
        // R238 评论媒体流入口（点按拉线程媒体回复插入当前条目后）：
        // 结果文案 Toast（VM 契约同 FeedActions.forwardToSaved）
        val streamContext = LocalContext.current
        val streamScope = rememberCoroutineScope()
        val openComments: (() -> Unit)? = if (commentCount > 0) {
            {
                streamScope.launch {
                    val msg = feedVm.openCommentStream(item)
                    android.widget.Toast.makeText(
                        streamContext, msg, android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        } else {
            null
        }
        BottomInfoPanel(
            item = item,
            onOpenChannel = onOpenChannel,
            onSearchTag = onSearchTag,
            translatedCaption = translatedCaption,
            onTranslate = { onTranslate(item) },
            avatarLoader = avatarLoader,
            subscribed = subscribed,
            onSubscribe = onSubscribe,
            plazaWatching = plazaWatching,
            plazaLikes = plazaLikes,
            commentCount = commentCount,
            onOpenComments = openComments,
            onOpenLink = onOpenLink,
            onOpenMention = onOpenMention,
            onLocateInGalaxy = onLocateInGalaxy,
            // 定位/边距已上移到外层 Column，这里只保留撑满宽度
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 合集（相册）标识 + 组内导航行：
 * "🖼 合集 序号/总数"胶囊（组成员未知/仅一名时退化为纯"🖼 合集"标识），
 * 同组 ≥2 名成员时按 Feed 列表顺序给"上一张/下一张"（onJump(-1/+1)，
 * 边界侧按钮不渲染）。样式与 BottomInfoPanel 的 InfoChip 同族（黑底描边小胶囊）。
 */
@Composable
private fun AlbumNavRow(
    groupKeys: List<String>,
    currentKey: String,
    onJump: (Int) -> Unit,
) {
    val idx = groupKeys.indexOf(currentKey)
    val total = groupKeys.size
    Row(horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
        AlbumNavChip(
            text = if (idx >= 0 && total > 1) "🖼 合集 ${idx + 1}/$total" else "🖼 合集",
            onClick = null, // 标识位不可点
        )
        if (idx > 0) {
            AlbumNavChip(text = "上一张", onClick = { onJump(-1) })
        }
        if (idx >= 0 && idx < total - 1) {
            AlbumNavChip(text = "下一张", onClick = { onJump(1) })
        }
    }
}

/** 合集行小胶囊（R162 收敛 OverlayInfoChip 唯一实现）；onClick=null = 纯标识不可点 */
@Composable
private fun AlbumNavChip(text: String, onClick: (() -> Unit)?) {
    OverlayInfoChip(
        text = text,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}
