package dev.tgfeed.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.tgfeed.ui.theme.TgFeedTokens

// ---------------- 频道主页入口（右栏头像，CHANNEL-HOME-FINAL P0） ----------------
// （R131 从 VideoPage.kt 按职责拆出，纯搬运零逻辑改动）

/** 头像回落字母圆的稳定配色（按 chatId 取色，与 BottomInfoPanel 同思路） */
private val RailAvatarPalette = listOf(
    Color(0xFFE17055), Color(0xFF6C5CE7), Color(0xFF00B894),
    Color(0xFF0984E3), Color(0xFFFD79A8), Color(0xFFE1A200),
)

private fun railAvatarColor(chatId: Long): Color =
    RailAvatarPalette[Math.floorMod(chatId, RailAvatarPalette.size)]

/**
 * 右栏频道头像（频道主页入口）：48dp 圆头像 + 1dp 白描边 + 与 RailIcon 同规格投影；
 * 右下角半叠 16dp 订阅徽标：未追 = TgBlue "＋"，已追 = 绿 "✓"。
 * 热区分离：点头像本体 → 进频道主页；点徽标 → 快捷追更；
 * 已追态点 ✓ 不直接退追（防误触），退追进频道主页管理。
 * 头像加载与 BottomInfoPanel 同口径：loader 内部带进程内缓存，重组/翻页回来只是缓存命中。
 */
@Composable
internal fun RailChannelAvatar(
    chatId: Long,
    title: String,
    avatarLoader: (suspend (Long) -> ByteArray?)?,
    subscribed: Boolean,
    onOpenHome: () -> Unit,
    onSubscribe: () -> Unit,
    // R225 横屏可伸展 A 档头像直径（默认 48dp = 竖屏/矮横屏现状）
    avatarSize: Dp = 48.dp,
) {
    // R194④：头像解码收编共享件（rememberChannelAvatarImage——IO 解码）：
    // 旧写法 remember(bytes){decodeByteArray} 在组合期主线程解码，翻页到货
    // 窗内逐个吃帧预算（R170 家族）
    val avatarImage = rememberChannelAvatarImage(chatId, avatarLoader)
    Box {
        // 头像本体（点击进频道主页）
        val avatarModifier = Modifier
            .size(avatarSize)
            .overlayIconShadow()
            .clip(CircleShape)
            .border(1.dp, Color.White, CircleShape)
            .clickable(onClick = onOpenHome)
        if (avatarImage != null) {
            Image(
                bitmap = avatarImage,
                contentDescription = "频道主页：$title",
                contentScale = ContentScale.Crop,
                modifier = avatarModifier,
            )
        } else {
            // 回落：圆形字母头像（频道名首字大写）
            Box(
                avatarModifier.background(railAvatarColor(chatId)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title.firstOrNull()?.uppercase() ?: "?",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        // 订阅徽标：16dp 半叠右下角；独立热区——未追点它快捷追更，
        // 已追点 ✓ 不退追（防误触；enabled=false 吃掉点击，不会误穿到头像进主页）
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 2.dp, y = 2.dp)
                .size(16.dp)
                .clip(CircleShape)
                .background(if (subscribed) TgFeedTokens.SuccessGreen else TgFeedTokens.TgBlue)
                // R162 收敛 OverlayBadgeBorder token（原 0.6 散装字面量）
                .border(1.dp, OverlayBadgeBorder, CircleShape)
                .clickable(enabled = !subscribed, onClick = onSubscribe),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (subscribed) Icons.Filled.Check else Icons.Filled.Add,
                contentDescription = if (subscribed) "已追更" else "追更该频道",
                tint = Color.White,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}
