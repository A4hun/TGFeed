package dev.tgfeed.ui.components.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.tgfeed.ui.theme.TgFeedTokens

/**
 * 空态 / 加载失败占位：大图标 + 主文案 + 可选副文案 + 可选主按钮。
 *
 * 什么时候用它：
 * - 列表/网格"真的没有数据"（空收藏、空下载、空搜索结果）；
 * - 加载失败需要给出解释和"重试"出口。
 *
 * 什么时候不用：
 * - "正在加载、还没回来"用 [AppLoading]——两者必须分清，
 *   空态转圈或加载中显示"暂无内容"都是事故；
 * - 页面局部小块为空（如某一行没副标题）不需要整屏占位，省略即可。
 *
 * 视觉规格（统一）：
 * - 整体居中填满父容器（默认 fillMaxSize，可用 modifier 改）；
 * - 图标 48dp（由 [TgFeedTokens.IconXl] 中央反馈档放大），色 [TgFeedTokens.TextLow]；
 * - 主文案 bodyLarge [TgFeedTokens.TextMedium]，副文案 bodySmall [TgFeedTokens.TextLow]；
 * - 主按钮：AccentRed 底白字胶囊（[TgFeedTokens.RadiusPill]），仅在给了
 *   [actionText] + [onAction] 时出现。
 *
 * @param icon 中央图标（如收件箱、云朵叹号、搜索放大镜）。
 * @param title 主文案，一句话说清状态（"还没有收藏任何视频"）。
 * @param modifier 外部修饰符，默认占满父容器居中。
 * @param subtitle 可选副文案，补充原因或引导（"刷到喜欢的就点右下角星标"）。
 * @param actionText 可选主按钮文案（"重试""去逛逛"）。
 * @param onAction 主按钮回调；与 [actionText] 必须同时给或同时不给。
 */
@Composable
internal fun AppEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(TgFeedTokens.GapXl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = TgFeedTokens.TextLow,
        )
        Spacer(Modifier.height(TgFeedTokens.GapLg))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = TgFeedTokens.TextMedium,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(TgFeedTokens.GapSm))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TgFeedTokens.TextLow,
                textAlign = TextAlign.Center,
            )
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(TgFeedTokens.GapXl))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                    .background(TgFeedTokens.AccentRed)
                    .clickable(onClick = onAction)
                    .padding(horizontal = TgFeedTokens.GapXl, vertical = TgFeedTokens.GapMd),
            ) {
                Text(
                    text = actionText,
                    style = MaterialTheme.typography.labelLarge,
                    color = TgFeedTokens.TextHigh,
                )
            }
        }
    }
}

/**
 * 加载中占位：转圈 + 下方说明文案。
 *
 * 什么时候用它：
 * - 数据请求中、首屏渲染中等"马上有内容"的时刻；
 * - 必须给 [text]（"正在加载…""正在登录…"），让用户分清"在加载"还是"没数据"。
 *
 * 什么时候不用：
 * - 加载结果为空或失败，立即换成 [AppEmptyState]，不要永远转下去；
 * - 极短促的局部刷新（下拉刷新指示器）用各自组件自带的指示器。
 *
 * 视觉规格：指示器 40dp、3dp 线宽，色 [TgFeedTokens.TextHigh]、轨道白 20%；
 * 文案 bodyMedium [TgFeedTokens.TextMedium]，居中。
 *
 * @param text 加载说明文案，必填。
 * @param modifier 外部修饰符，默认占满父容器居中。
 */
@Composable
internal fun AppLoading(
    text: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(TgFeedTokens.GapXl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(40.dp),
            color = TgFeedTokens.TextHigh,
            trackColor = TgFeedTokens.TextHigh.copy(alpha = 0.20f),
            strokeWidth = 3.dp,
        )
        Spacer(Modifier.height(TgFeedTokens.GapMd))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = TgFeedTokens.TextMedium,
            textAlign = TextAlign.Center,
        )
    }
}
