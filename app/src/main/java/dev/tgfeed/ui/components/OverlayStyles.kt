package dev.tgfeed.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.tgfeed.ui.theme.TgFeedTokens

/**
 * 浮层可读性 & 内容遮罩政策的唯一家（R162 统一治理，全文见仓库根 scrim-audit.md）：
 *
 * P1 内容区零遮罩红线：任何压在真实媒体内容（照片/视频/封面/模糊底衬）之上的
 *    半透明黑层/黑色渐变/染色层一律不画（用户五次同向裁决）；文字可读性统一走
 *    [withOverlayShadow]，图标走高对比本色（必要时 [overlayIconShadow]）；
 *    模糊底衬只模糊不压暗（R83/becc50e 口径）。
 * P2 合法 chrome 只准用共享 token，不许各页自造 alpha 数字：
 *    控件底 = TgFeedTokens.OverlayBg（×钮/暂停钮/投票钮/toast/状态胶囊）；
 *    信息 chip = [OverlayInfoChip]（本文件唯一实现）；
 *    进度/拖动气泡/弹幕 chip = [OverlayChipBg]；已看弱化 = [WatchedDimLayer]。
 * P3 无真实内容在下的占位/装饰渐变不属遮罩，照旧。
 * P4 守护：ScrimPolicyTest 扫源码，Color.Black.copy(alpha=…) 字面量只准出现在
 *    theme/Theme.kt 与本文件（token 家）；要新遮罩先立 token + scrim-audit.md 登记。
 */

/**
 * 浮层文字统一阴影：压在浅色/亮色视频画面上时保持可读。
 * 用法：`style = MaterialTheme.typography.bodyMedium.withOverlayShadow()`
 */
internal val OverlayTextShadow = Shadow(
    color = Color.Black.copy(alpha = 0.75f),
    offset = Offset(0f, 1f),
    blurRadius = 6f,
)

/** 给 TextStyle 追加浮层阴影（幂等：直接替换 shadow 字段） */
internal fun TextStyle.withOverlayShadow(): TextStyle = copy(shadow = OverlayTextShadow)

/**
 * 浮层图标的柔光投影：在图标底下垫一圈柔和圆形阴影（不裁剪），
 * 图标压在亮色画面上时仍有层次，模拟设计稿的"柔光"质感。
 * 调低版：原 6dp/0.8 在亮画面上会显出一个可见的方块状光晕（用户反馈），
 * 降到 2.5dp/0.45——保住可读性，消掉"图标带底托"的观感。
 */
internal fun Modifier.overlayIconShadow(): Modifier = shadow(
    elevation = 2.5.dp,
    shape = CircleShape,
    clip = false,
    ambientColor = Color.Black.copy(alpha = 0.45f),
    spotColor = Color.Black.copy(alpha = 0.45f),
)

/** 浮层信息胶囊底色（半透明黑）：时长/大小 chip、进度拖动气泡共用 */
internal val OverlayChipBg = Color.Black.copy(alpha = 0.45f)

/** 浮层信息胶囊形状（全圆角） */
internal val OverlayChipShape = RoundedCornerShape(percent = 50)

// ---------------- R162 信息 chip 唯一实现（终稿裁决 v2-b 样式收敛） ----------------

/** 信息 chip 底色：黑 α0.35（比实心深胶囊轻，暗/亮画面两边都可读——v2-b 裁决） */
internal val OverlayInfoChipBg = Color.Black.copy(alpha = 0.35f)

/** 信息 chip 发丝描边：白 α0.18 */
internal val OverlayInfoChipHairline = Color.White.copy(alpha = 0.18f)

/** 信息 chip 形状：8dp 圆角 */
internal val OverlayInfoChipShape = RoundedCornerShape(8.dp)

/**
 * 描边信息 chip（时长/热度/类型角标/广场角标）：黑 α0.35 底 + 0.5dp 白 α0.18 描边
 * + 8dp 圆角 + labelSmall。R162 前三处手抄同款样式（BottomInfoPanel InfoChip /
 * AudioCard 广场角标 / 频道页网格角标）收编到这里——一处改全局生效。
 * [contentPadding] 随场景微调（网格角标更紧凑），样式本体（底色/描边/圆角/字号）不许各处自造。
 */
@Composable
internal fun OverlayInfoChip(
    text: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
) {
    Text(
        text,
        color = TgFeedTokens.TextHigh.copy(alpha = 0.9f),
        style = MaterialTheme.typography.labelSmall, // 11sp
        modifier = modifier
            .background(OverlayInfoChipBg, OverlayInfoChipShape)
            .border(0.5.dp, OverlayInfoChipHairline, OverlayInfoChipShape)
            .padding(contentPadding),
    )
}

// ---------------- 状态语义层 token（非可读性遮罩） ----------------

/**
 * 已看弱化叠层（黑 α0.25）：分屏"看过"的状态语义层（GRID-REDESIGN §4.7，
 * 取代旧版 40% 黑纱 + "已看过"文字；chromeless LOD 下不画）。
 * 不是为文字可读性服务的遮罩，属状态表达；字面量收编成 token 防散装 alpha。
 */
internal val WatchedDimLayer = Color.Black.copy(alpha = 0.25f)

// ---------------- 描边/模态工艺 token ----------------

/**
 * 小圆点角标的深色描边（红点"有新内容"/绿点"已送达"勾一圈，防亮画面融边）。
 * 描边工艺而非遮罩（与遮罩政策同向：用轮廓不用底色压暗）。
 */
internal val OverlayBadgeBorder = Color.Black.copy(alpha = 0.6f)

/**
 * 模态/引导层全屏 dim（黑 α0.85，新手教学 CoachOverlay 用，语义同对话框 dim）。
 * UI 规范层例外（P1 红线只管"压在内容上的可读性/装饰遮罩"，模态聚焦不算）；
 * 立成 token 防各处自造深浅。
 */
internal val ModalDimLayer = Color.Black.copy(alpha = 0.85f)

/**
 * R299 B2 查看器底幕（纯黑画布）：查看器=模态看图态，黑底是「看图」语义本体
 *（内容之下的底，不是压在内容上的遮罩——P1 红线不适用；Telegram 查看器同款）。
 * 下滑退出的跟手渐隐由调用方 .copy(alpha=…) 乘系数（PhotoViewerPolicy.
 * dismissBackdropAlpha 单一出处）；本 token 收编底色字面量。
 */
internal val PhotoViewerBackdrop = Color.Black

/**
 * R301 运镜「沉入深空」底幕（错觉拼接 A 案 feed 侧出场叠加层 GalaxyWarpOverlay
 * 的背景）：封面缩成光点时现场整体沉入黑，α 上限 0.92 随运镜进度 [progress] 渐入
 * （留一丝现场感不死黑）。全屏运镜过场=模态语义（与 [ModalDimLayer] 同族的 UI
 * 规范层例外，非内容可读性遮罩——P1 红线不适用）；字面量收编成 token 函数防散装
 * alpha，scrim-audit.md B22 登记。
 */
internal fun galaxyWarpSinkLayer(progress: Float): Color =
    Color.Black.copy(alpha = 0.92f * progress.coerceIn(0f, 1f))

