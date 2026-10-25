package dev.tgfeed.ui.components.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.tgfeed.ui.theme.TgFeedTokens

/**
 * 浮层图标按钮：OverlayBg 圆底 + 居中单图标。
 *
 * 什么时候用它：
 * - 浮在视频/图片/深色底上的动作钮：返回、关闭 ×、更多 ⋯、静音等；
 * - 顶栏、面板头部等需要"小圆钮"风格的位置（[AppTopBar] 的返回钮即是它）。
 *
 * 什么时候不用：
 * - 列表行内的次要动作（尾巴箭头、开关）用 [AppListItem] 的尾部槽；
 * - 需要文字说明的动作不要用纯图标钮（可达性差），改用带文字的按钮。
 *
 * 视觉规格（统一，勿在调用处改）：
 * - 触控热区 44dp（≥ [TgFeedTokens.TouchMin]，圆底本身即热区）；
 * - 底 [TgFeedTokens.OverlayBg] 正圆；
 * - 图标默认 [TgFeedTokens.IconLg]（22dp），默认色白 90%（浮层图标档）。
 *
 * @param icon 图标。
 * @param contentDescription 无障碍描述；纯图标按钮必须给（如"返回""关闭"）。
 * @param onClick 点击回调。
 * @param modifier 外部布局修饰符。
 * @param tint 图标色，默认白 90%；仅特殊语义（如警示）才覆盖。
 * @param iconSize 图标尺寸，默认 [TgFeedTokens.IconLg]；关闭 × 等小 glyph 可传 IconSm。
 */
@Composable
internal fun AppIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = TgFeedTokens.TextHigh.copy(alpha = 0.90f),
    iconSize: Dp = TgFeedTokens.IconLg,
) {
    Box(
        modifier = modifier
            .size(44.dp) // 终稿热区 44dp，高于 TouchMin 40dp 底线
            .clip(CircleShape)
            .background(TgFeedTokens.OverlayBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = tint,
        )
    }
}
