package dev.tgfeed.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tgfeed.core.feed.DownloadStatus
import dev.tgfeed.ui.landscapeFeedChromeOrNull
import dev.tgfeed.ui.rememberWindowSize
import dev.tgfeed.ui.theme.TgFeedTokens
import dev.tgfeed.ui.uiScale
import kotlin.math.roundToInt

/**
 * 右侧操作栏（P0-1 收敛版）：点赞 / 分享 / 收藏 / 下载（四态）/ 旋转，共 5 格；
 * 加上 VideoPage 叠在列顶的"频道头像（带 +追角标）"即终稿的 6 元素右轨。
 * 静音 / 全屏 / 截图已从右轨移除，收进长按功能面板（VideoActionSheet）。
 * R175 弹幕/分享对调（用户拍板）：分享卡片从长按面板上右轨（原弹幕开关位），
 * 弹幕开关迁长按面板（原分享卡片位）——图标/回调原样搬，行为零变化。
 * R201 分享枢纽：右轨分享点按不再直跳单动作（2026-10-16 裁决的 ACTION_SEND 直跳），
 * 改弹 ShareHubSheet 小菜单三选一（复制链接/分享到其他应用/分享卡片）。
 * 视觉纪律（F-A/F-G）：未选中一律细描边 Outlined 白图标，选中才填充主题色。
 * 规格：图标 30dp × uiScale（RailIconSize × 档位系数）、行距 26dp（RailGap）。
 */
@Composable
internal fun ActionRail(
    liked: Boolean,
    favorited: Boolean,
    // R432 重组收敛：下载表 State 本体下传 + 条目 key，本组件内现查——
    // 原 downloadStatus 值参链（FeedScreen → VideoPage → 本栏）让下载 1Hz 进度
    // 更新把整页拖进重组；改 State 后订阅作用域收敛到本栏，进度只在
    // RailItem 行内刷新
    downloads: State<Map<String, DownloadStatus>>,
    downloadKey: String,
    // R175：分享（原长按面板按钮原样搬来）→ 2026-10-16 改直跳系统分享 → R201 整合：
    // 点按弹分享枢纽小菜单（ShareHubSheet 三选一：复制链接/分享到其他应用/分享卡片），
    // 与 FeedScreen.onOpenShareHub 同一回调链，链体在枢纽接线收口
    onShare: () -> Unit,
    // 倍速从右轨移除（长按面板里本来就有倍速行）；这个位置让给"画面旋转"（用户拍板）：
    // 点按顺时针 90° 循环，角度只对当前条目有效（划走归零；频道记忆设施保留未接线）；
    // 非 0° 时图标染蓝提示"当前是转过的"
    rotationDeg: Int,
    onRotate: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    // R138 点赞回写（design-redesign P0-③）：长按点赞钮查本条回写状态（VideoPage 弹状态面板）；
    // null = 宿主未接线（图/音面板等旧调用）→ 不挂长按，行为与旧版完全一致
    onLikeLongPress: (() -> Unit)? = null,
    // R138：本条 👍 已回写送达 → 点赞钮右下角小绿点（区别纯本地赞的可见闭环）
    likeWritebackDelivered: Boolean = false,
) {
    // 图标随档位系数外扩（手机基准 1x、平板放大）；行距固定 26dp 不随档（P0-1 规格）
    // R225 横屏可伸展档：图标/行距/触区按 LandscapeFeedChrome 政策档取值（A=40dp/26dp、
    // B=36dp/20dp，触区恒 48dp 铁律）；竖屏/矮横屏政策给 null → 走下方原链逐字节不变
    val windowSize = rememberWindowSize()
    val landscapeChrome = landscapeFeedChromeOrNull(
        windowSize,
        LocalConfiguration.current.screenHeightDp,
    )
    val iconSize = landscapeChrome?.railIconSize
        ?: TgFeedTokens.RailIconSize * windowSize.uiScale
    val railGap = landscapeChrome?.railGap ?: TgFeedTokens.RailGap
    val railTouchMin = landscapeChrome?.railTouchMin ?: 0.dp
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(railGap),
    ) {
        // 终稿裁决：动词文字标签全部删除，图标列回归纯图标；
        // 中文文案保留在 contentDescription（无障碍读出），界面不再渲染
        RailIcon(
            icon = if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            tint = if (liked) LikeRed else TgFeedTokens.TextHigh,
            label = "",
            onClick = onToggleLike,
            iconSize = iconSize,
            touchMin = railTouchMin,
            bounceOnClick = true,
            contentDescription = if (liked) "已点赞" else "点赞",
            // R138：长按查本条回写状态；已送达时右下角小绿点
            onLongClick = onLikeLongPress,
            deliveredMark = likeWritebackDelivered,
        )
        // 分享（R175 对调上右轨 + 2026-10-16 用户裁决 + R201 整合）：IosShare 图标，
        // 点按=弹分享枢纽小菜单三选一（复制链接/分享到其他应用/分享卡片，
        // ShareHubSheet）；弹幕开关在长按面板
        RailIcon(
            icon = Icons.Outlined.IosShare,
            tint = TgFeedTokens.TextHigh,
            label = "",
            onClick = onShare,
            iconSize = iconSize,
            touchMin = railTouchMin,
            contentDescription = "分享",
        )
        RailIcon(
            icon = if (favorited) Icons.Filled.Star else Icons.Outlined.StarBorder,
            tint = if (favorited) FavYellow else TgFeedTokens.TextHigh,
            label = "",
            onClick = onToggleFavorite,
            iconSize = iconSize,
            touchMin = railTouchMin,
            bounceOnClick = true,
            contentDescription = if (favorited) "已收藏" else "收藏",
        )
        // 下载按状态换图标：未下载 ↓ / 下载中转圈 / 已完成 ✓ / 失败 ⚠（点按重试）
        // R432：叶子查表点（本栏组合期单次读，1Hz 进度更新只重组本 RailItem）
        val downloadStatus = downloads.value[downloadKey]
        when (downloadStatus) {
            null -> RailIcon(
                Icons.Outlined.FileDownload, TgFeedTokens.TextHigh, "", onDownload,
                iconSize = iconSize,
                touchMin = railTouchMin,
                contentDescription = "下载",
            )
            // is 判断对 data object / data class 两种定义都兼容
            //（Running 正改造为带 progress 的 data class，此处不再用对象相等判断）
            is DownloadStatus.Running -> RailItem(label = "", touchMin = railTouchMin) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    color = TgFeedTokens.TextHigh,
                    strokeWidth = 2.5.dp,
                )
                // 进度已知（≥0）时在转圈下方显示百分比；
                // <0（Mock 等拿不到总大小的场景）只转圈不显示百分比
                if (downloadStatus.progress >= 0f) {
                    Spacer(Modifier.height(TgFeedTokens.GapXs))
                    Text(
                        "${(downloadStatus.progress * 100).roundToInt().coerceIn(0, 100)}%",
                        color = TgFeedTokens.TextHigh,
                        style = MaterialTheme.typography.labelSmall.withOverlayShadow(),
                    )
                }
            }
            is DownloadStatus.Done -> RailIcon(
                Icons.Outlined.CheckCircle, TgFeedTokens.SuccessGreen, "", onDownload,
                iconSize = iconSize,
                touchMin = railTouchMin,
                contentDescription = "已下载",
            )
            is DownloadStatus.Failed -> RailIcon(
                Icons.Outlined.Warning, TgFeedTokens.WarningRed, "", onDownload,
                iconSize = iconSize,
                touchMin = railTouchMin,
                contentDescription = "下载失败，点按重试",
            )
        }
        // 画面旋转（占用原倍速位）：点按顺时针转 90°，频道粒度记忆；
        // 非 0° 时图标染品牌蓝提示"已旋转"，恢复 0° 回到白描边
        RailIcon(
            icon = Icons.Outlined.Rotate90DegreesCw,
            tint = if (rotationDeg != 0) TgFeedTokens.TgBlue else TgFeedTokens.TextHigh,
            label = "",
            onClick = onRotate,
            iconSize = iconSize,
            touchMin = railTouchMin,
            contentDescription = if (rotationDeg != 0) "旋转画面（当前 ${rotationDeg}°）" else "旋转画面",
        )
    }
}

/** 操作栏一格：图标区 + 可选小标签；label 传空串时跳过标签（纯图标列，终稿裁决）。
 *  [onLongClick]（R138）非空时长按热区生效（combinedClickable），null 时保持原 clickable 零改动
 *  [touchMin]（R225）触区最小边长（横屏可伸展档 48dp 铁律）：0.dp = 竖屏/矮横屏
 *  原行为（触区=内容包裹，逐字节不变）；非 0 时本格触区盒放大到该边长、图标居中。 */
@OptIn(ExperimentalFoundationApi::class) // BOM 2024.09.02 的 combinedClickable 仍带实验注解
@Composable
private fun RailItem(
    label: String,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    touchMin: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // R225：触区盒内垂直居中（内容包裹时 Center 与 Top 布局等价，竖屏零变化）
        verticalArrangement = Arrangement.Center,
        modifier = (
            when {
                onClick != null && onLongClick != null ->
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                onClick != null -> Modifier.clickable(onClick = onClick)
                else -> Modifier
            }
            ).sizeIn(minWidth = touchMin, minHeight = touchMin),
    ) {
        content()
        if (label.isNotEmpty()) {
            Spacer(Modifier.height(TgFeedTokens.GapXs))
            Text(
                label,
                color = TgFeedTokens.TextMedium,
                style = MaterialTheme.typography.labelSmall.withOverlayShadow(),
            )
        }
    }
}

/**
 * 操作栏图标格。
 * @param label 图标下方文字标签；空串 = 纯图标（终稿裁决后的常态）
 * @param iconSize 图标尺寸（30dp × uiScale，由调用方按档位算好传入）
 * @param touchMin 触区最小边长（R225 横屏可伸展档 48dp 铁律；0.dp = 原内容包裹）
 * @param bounceOnClick 点击时图标先放大 1.35x 再弹簧回弹（点赞/收藏的微交互）
 * @param badge 右上角小红点角标（选中/有内容态语义，替代整图标变色）
 * @param contentDescription 无障碍读出文案（标签删空后由它兜底）
 * @param onLongClick 长按动作（R138 点赞钮：查本条回写状态）；null = 不挂长按
 * @param deliveredMark 右下角小绿点（R138：本条 👍 已回写送达的可见闭环标记）
 */
@Composable
private fun RailIcon(
    icon: ImageVector,
    tint: Color,
    label: String,
    onClick: () -> Unit,
    iconSize: Dp,
    touchMin: Dp = 0.dp,
    bounceOnClick: Boolean = false,
    badge: Boolean = false,
    contentDescription: String = label,
    // 文字字形图标（弹幕"弹"这类 Material 没有现成图标的语义）：非空时替代矢量图标渲染
    textGlyph: String? = null,
    onLongClick: (() -> Unit)? = null,
    deliveredMark: Boolean = false,
) {
    // 每次点击自增 pulse，驱动一次弹跳动画（连点会打断重播，天然防抖）
    // R178 收编：点按应答弹跳单一来源 = MotionTokens.LikeBounce（与 VideoPage 双击点赞
    // likeBounce 同一常数两处各写的类违规收敛；反馈应答弹簧域，见 token 注释）
    var pulse by remember { mutableStateOf(0) }
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pulse) {
        if (pulse > 0) {
            scale.snapTo(1.35f)
            scale.animateTo(
                1f,
                spring(
                    dampingRatio = dev.tgfeed.ui.theme.MotionTokens.LikeBounceDamping,
                    stiffness = dev.tgfeed.ui.theme.MotionTokens.LikeBounceStiffness,
                ),
            )
        }
    }
    RailItem(
        label = label,
        onClick = {
            if (bounceOnClick) pulse++
            onClick()
        },
        onLongClick = onLongClick,
        touchMin = touchMin,
    ) {
        Box {
            if (textGlyph != null) {
                // 文字字形图标：与矢量图标同尺寸框内居中，字重加粗对齐图标视觉重量
                Box(
                    Modifier
                        .size(iconSize)
                        .graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        textGlyph,
                        color = tint,
                        fontWeight = FontWeight.Bold,
                        fontSize = (iconSize.value * 0.62f).sp,
                    )
                }
            } else {
                Icon(
                    icon,
                    contentDescription = contentDescription,
                    tint = tint,
                    modifier = Modifier
                        .size(iconSize)
                        // 投影已按用户反馈整体去掉（亮画面上会显出可见的方块状光晕，观感脏）
                        .graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
                        },
                )
            }
            if (badge) {
                // 小红点角标（抖音式"有新内容/已开启"语义）：描一圈深色边，亮画面上不融边
                // （R162 收敛 OverlayBadgeBorder token，原 0.6 散装字面量）
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-2).dp)
                        .size(8.dp)
                        .background(TgFeedTokens.AccentRed, CircleShape)
                        .border(1.dp, OverlayBadgeBorder, CircleShape),
                )
            }
            // R138 回写送达标记：右下角小绿点（与左上小红点错位，语义=成功送达；
            // 描深边防亮画面融边，同 badge 工艺）
            if (deliveredMark) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 1.dp, y = 1.dp)
                        .size(5.dp)
                        .background(TgFeedTokens.SuccessGreen, CircleShape)
                        .border(1.dp, OverlayBadgeBorder, CircleShape),
                )
            }
        }
    }
}
