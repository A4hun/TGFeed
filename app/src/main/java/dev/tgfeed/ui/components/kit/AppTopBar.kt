package dev.tgfeed.ui.components.kit

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tgfeed.ui.components.withOverlayShadow
import dev.tgfeed.ui.theme.TgFeedTokens

/**
 * 二级页统一顶栏：返回圆钮 + 标题 + 尾部动作槽。
 *
 * 什么时候用它：
 * - 所有从一级页下钻的二级页（设置详情、下载、频道主页、观看报告……），
 *   替换各处手写的 Row + IconButton + Text 拼搭；
 * - 页面顶部是沉浸内容（视频/网格）时同样适用——返回钮自带 OverlayBg 圆底。
 *
 * 什么时候不用：
 * - 一级页（Feed/Grid/发现）的顶部 chrome 走各自的 overlay 组件，不套这个壳；
 * - 需要大标题折叠效果的页面不用它（没有 CollapsingTopBar 能力）。
 *
 * 视觉规格（统一）：
 * - 内建 statusBarsPadding，调用处不要再加；
 * - 左侧 [AppIconButton] 返回钮（AutoMirrored ArrowBack，自动适配 RTL）；
 * - 标题 17sp SemiBold [TgFeedTokens.TextHigh]，超长省略；
 * - 尾部 [actions] 槽右对齐，放 0~2 个动作（建议也用 [AppIconButton]）。
 *
 * @param title 标题文案。
 * @param onBack 返回回调；传 null 则不渲染返回钮（少数根级页可用）。
 * @param modifier 外部修饰符。
 * @param overlayTitle 标题压在沉浸内容（封面/画面）之上时传 true——标题文字追加
 *   withOverlayShadow 描边保可读（R162 内容区零遮罩政策：可读性归描边，
 *   不许回潮顶部渐变遮罩；实底页面传 false 零变化）。
 * @param actions 尾部动作槽，RowScope 内排列。
 */
@Composable
internal fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    overlayTitle: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = TgFeedTokens.GapLg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            AppIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
            )
            Spacer(Modifier.width(TgFeedTokens.GapMd))
        }
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = TgFeedTokens.TextHigh,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            // 参数级 fontSize/fontWeight 优先于 style，这里只承载可选描边
            style = if (overlayTitle) TextStyle().withOverlayShadow() else TextStyle.Default,
        )
        actions()
    }
}
