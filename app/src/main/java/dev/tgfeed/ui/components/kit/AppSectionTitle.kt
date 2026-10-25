package dev.tgfeed.ui.components.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.tgfeed.ui.theme.TgFeedTokens

/**
 * A2 小节标题（发现页 v3 定稿语言）：霓虹红引导竖条 + 粗体白字。
 *
 * 用途：二级页/面板的"小节分隔标题"（搜索历史、猜你想搜、资源群秒回……），
 * 替代各处裸 Text(labelMedium, TextLow) 的弱提示式小标题。
 *
 * 视觉规格（全 App 统一，勿在调用处覆写）：
 * - 竖条：3dp 宽 × 14dp 高、RadiusPill 圆头、AccentRed 霓虹红；
 * - 标题：titleMedium（16sp SemiBold）TextHigh，与竖条间距 GapSm。
 *
 * 注意：DiscoverScreen 内现存一枚同款私有实现（DiscoverSectionTitle），
 * 该文件归另一专项所有暂不可动；后续由其迁到本组件，别再复制第三份。
 */
@Composable
internal fun AppSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        // 霓虹红引导竖条：小节视觉锚点（A2 点缀色，红色只做引导不做文字色）
        Box(
            Modifier
                .width(3.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                .background(TgFeedTokens.AccentRed),
        )
        Spacer(Modifier.width(TgFeedTokens.GapSm))
        Text(
            text,
            color = TgFeedTokens.TextHigh,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
        )
    }
}
