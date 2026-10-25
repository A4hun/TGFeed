package dev.tgfeed.ui.components.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.tgfeed.ui.theme.TgFeedTokens

/**
 * 胶囊 chip（描边胶囊）。
 *
 * 什么时候用它：
 * - 筛选/分类 chip 行（如 FilterSheet 的时长档、日期档单选）；
 * - 话题标签、搜索热词等"可点、可多选/单选、轻量"的入口。
 *
 * 什么时候不用：
 * - 需要承载主行动作（"保存/下载"级别）时，用 [dev.tgfeed.ui.components.kit] 里的
 *   实心主按钮（AppEmptyState 的主按钮样式）而不是 chip；
 * - 纯展示、不可点的标签，直接 Text + 背景即可，不必套可点语义。
 *
 * 视觉规格（全 App 统一，勿在调用处覆写）：
 * - 未选中：底 [TgFeedTokens.OverlayBg] + 0.5dp [TgFeedTokens.OverlayHairline] 发丝描边，
 *   文字 [TgFeedTokens.TextMedium]；
 * - 选中：底 [TgFeedTokens.AccentRed]、文字白，描边隐去（底色本身够跳）；
 * - 圆角 [TgFeedTokens.RadiusPill]，横向内边距 GapLg、纵向 GapSm；
 * - 可选前导 icon，尺寸 [TgFeedTokens.IconSm]（14dp，胶囊前导档）。
 *
 * @param text chip 文案，单行。
 * @param selected 是否选中；选中态切换交由调用方管理（本组件不存状态）。
 * @param onClick 点击回调，整个胶囊都是热区。
 * @param modifier 外部布局修饰符。
 * @param icon 可选前导图标（如时钟、日历），null 则纯文本。
 */
@Composable
internal fun AppPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(TgFeedTokens.RadiusPill)
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (selected) TgFeedTokens.AccentRed else TgFeedTokens.OverlayBg)
            .then(
                if (selected) Modifier
                else Modifier.border(0.5.dp, TgFeedTokens.OverlayHairline, shape)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapXs),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(TgFeedTokens.IconSm),
                tint = if (selected) TgFeedTokens.TextHigh else TgFeedTokens.TextMedium,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) TgFeedTokens.TextHigh else TgFeedTokens.TextMedium,
        )
    }
}
