package dev.tgfeed.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.tgfeed.ui.theme.TgFeedTokens

/**
 * R324 离线提示胶囊（离线降级矩阵的「呈现」半体——feed 顶部/频道页顶共用视觉件）：
 * OverlayBg 暗胶囊 + 白字描边（ResumeHintPill 同语言同规格——14dp 圆角、
 * labelMedium、横 14 纵 7 padding）。
 *
 * 文案一律来自 core/net/OfflineDegradePolicy（单一出处，纪律§一：同一文案族
 * 不许五处各写一份）；调用方离线判定为 null 时不组合本件（在线零视觉噪声）。
 */
@Composable
internal fun OfflineHintPill(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.labelMedium.withOverlayShadow(),
        modifier = modifier
            .background(TgFeedTokens.OverlayBg, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
