package dev.tgfeed.ui.components.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tgfeed.ui.theme.TgFeedTokens

/**
 * 列表行：前导图标 + 主标题（+副标题）+ 尾部槽，设置项/菜单项的统一骨架。
 *
 * 什么时候用它：
 * - 设置页条目（"通知""存储""关于"）、面板里的菜单行、频道信息行；
 * - 尾部可以是箭头、Switch、数值文本或任意小组合（走 [trailing] 槽）。
 *
 * 什么时候不用：
 * - 信息流卡片（视频卡、图片卡）有自己的封面+元信息版式，不是列表行；
 * - 需要多行正文、内嵌按钮的富内容行，自行组合 Column。
 *
 * 视觉规格（统一）：
 * - 行高 ≥ 56dp，横向留白 [TgFeedTokens.GapLg]；
 * - 前导图标可选，[TgFeedTokens.IconLg]、[TgFeedTokens.TextMedium]；
 * - 主标题 bodyLarge [TgFeedTokens.TextHigh] 单行省略，
 *   副标题 bodySmall [TgFeedTokens.TextLow] 单行省略；
 * - [onClick] 非空时整行可点（整行都是热区，不要只在尾部放小按钮）；
 * - [enabled]=false 时整行 [TgFeedTokens.DisabledContentAlpha] 置灰且不可点——
 *   如实禁用（状态说明写在副标题里，让用户知道为什么不能点，R201 分享枢纽
 *   「该视频没有公开链接」行的口径来源）。
 *
 * @param title 主标题。
 * @param modifier 外部修饰符。
 * @param subtitle 可选副标题（状态、摘要）。
 * @param leadingIcon 可选前导图标；没有就不占位置，标题顶到行首。
 * @param onClick 整行点击回调；null 则行不可点（纯展示行）。
 * @param enabled 行可用态；false = 置灰（DisabledContentAlpha）+ 不挂点击，
 *                与 onClick=null 的「纯展示行」区分开（禁用是有内容的不可用态）。
 *                默认 true 保持既有调用零变化。
 * @param trailing 尾部槽（箭头/开关/数值）；注意槽内不要再消费点击，
 *                 除非该控件本身独立可点（如 Switch）。
 */
@Composable
internal fun AppListItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            // 置灰态整行统一降透明（R201 收编 token：与 ActionSheet 置灰格同一数字一处来源）
            .alpha(if (enabled) 1f else TgFeedTokens.DisabledContentAlpha)
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(TgFeedTokens.IconLg),
                tint = TgFeedTokens.TextMedium,
            )
            Spacer(Modifier.width(TgFeedTokens.GapLg))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = TgFeedTokens.TextHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(TgFeedTokens.GapXs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TgFeedTokens.TextLow,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(TgFeedTokens.GapMd))
            Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
    }
}
