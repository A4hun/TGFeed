package dev.tgfeed.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.tgfeed.ui.components.kit.avoidBottomBar
import dev.tgfeed.ui.theme.TgFeedTokens

/**
 * 新手引导覆盖层的页面枚举：
 *  - [Feed]：单播沉浸页手势（单击/双击/长按/双指捏合=放大·清屏）
 *  - [Grid]：分屏自由画布手势（捏合/平移/拖柄/换位）
 */
enum class CoachPage { Feed, Grid }

/**
 * 新手引导覆盖层：模态 dim（R162 收敛 ModalDimLayer token）罩住全屏，用手绘风
 * （虚线描边气泡 + 轻微歪斜）把本页手势标注在它实际生效的屏幕区域上；
 * 底部一颗全圆角"知道了"按钮。点击任意处同样 dismiss（按钮只是更显眼的出口）。
 *
 * 纯展示组件：不持有状态，显隐由调用方控制（如 `if (showCoach) CoachOverlay(...)`）。
 */
@Composable
fun CoachOverlay(page: CoachPage, onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(ModalDimLayer)
            // 点击任意处 dismiss："知道了"按钮自带 clickable，会先消费并同样回调 onDismiss
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onDismiss() })
            },
    ) {
        // 页标题
        Text(
            if (page == CoachPage.Feed) "单播手势" else "画布手势",
            color = TgFeedTokens.TextHigh,
            style = MaterialTheme.typography.titleMedium.withOverlayShadow(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 56.dp),
        )

        when (page) {
            CoachPage.Feed -> FeedCoachMarks()
            CoachPage.Grid -> GridCoachMarks()
        }

        // 知道了：全圆角主按钮（胶囊语言，与全局 chip/搜索框一致）
        // R226：Feed 首启引导时 dock 在场——CTA 消费 inset 抬到栏上沿（40dp 旧值
        // 只避开手势条，下半截埋进透明 dock 抢点）；Grid 模式 inset 恒 0 照常贴底
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .avoidBottomBar()
                .padding(horizontal = 48.dp, vertical = 40.dp)
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                .background(TgFeedTokens.AccentRed)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "知道了",
                color = TgFeedTokens.TextHigh,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/**
 * Feed 单播页手势标注：标注位置即手势生效的屏幕区域——
 * 中央单击暂停/继续、中央偏上双击点赞、左右两侧长按 2x 快进、中下长按出功能面板。
 * R174 +1 枚：双指捏合 = 放大画面 · 渐入清屏（提案 §3.1 实锤「捏合缩放零教学」
 * 的发现性漏洞补齐；模态引导与首次触发气泡双轨教同一手势）。
 */
@Composable
private fun BoxScope.FeedCoachMarks() {
    // 双击 = 点赞（中央偏上）
    CoachMark(
        icon = Icons.Filled.Favorite,
        gesture = "双击",
        action = "点赞",
        rotate = -2f,
        modifier = Modifier
            .align(Alignment.Center)
            .offset(y = (-120).dp),
    )
    // 单击 = 暂停 / 继续（正中央）
    CoachMark(
        icon = Icons.Filled.PlayArrow,
        gesture = "单击",
        action = "暂停 / 继续",
        rotate = 1.5f,
        modifier = Modifier.align(Alignment.Center),
    )
    // 长按中间 = 功能面板（中下）
    CoachMark(
        gesture = "长按中间",
        action = "功能面板",
        rotate = -1.5f,
        modifier = Modifier
            .align(Alignment.Center)
            .offset(y = 120.dp),
    )
    // R174 双指捏合 = 自由缩放（R174b 起可大可小）；外放渐入清屏（中下二段；
    // 捏合是全域手势，位置示意在画面中部列队）
    CoachMark(
        gesture = "双指捏合",
        action = "自由缩放 · 放大渐入清屏",
        rotate = 1.5f,
        modifier = Modifier
            .align(Alignment.Center)
            .offset(y = 230.dp),
    )
    // 长按左侧 = 2x 快进
    CoachMark(
        gesture = "长按左侧",
        action = "2x 快进",
        rotate = 2f,
        modifier = Modifier
            .align(Alignment.CenterStart)
            .padding(start = 20.dp),
    )
    // 长按右侧 = 2x 快进
    CoachMark(
        gesture = "长按右侧",
        action = "2x 快进",
        rotate = -2f,
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .padding(end = 20.dp),
    )
}

/**
 * R174 捏合清屏首次教学气泡（提案 §3.2）：顶部中央胶囊「双指放大已清屏 · 轻点
 * 任意处退出」，2.5s 持续后淡出（时长由调用方持有：VideoPage/PhotoCard 的
 * pinchTeachBubble 状态 + delay(2500)）。
 * 挂现有引导体系（不发明新机制）：AppPrefs 一次性 flag 把门（pinchCleanGuideShown，
 * 设置页「重新显示手势操作引导」复位覆盖）；视觉走 R162 token 家——OverlayBg
 * 胶囊底 + withOverlayShadow 描边，与 BoostStatusPill/SeekHintPill 同槽位同语言。
 */
@Composable
internal fun BoxScope.PinchCleanCoachBubble(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(top = 56.dp),
        // R178 收编三档 + 纪律二修正：退场 300>进场 150 违反「退场永远 ≤ 进场」——
        // 两端统一 AnimFast（150/150），气泡消失更利落（用户对消失没有耐心）
        enter = fadeIn(tween(TgFeedTokens.AnimFast)),
        exit = fadeOut(tween(TgFeedTokens.AnimFast)),
    ) {
        Text(
            "双指放大已清屏 · 轻点任意处退出",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium.withOverlayShadow(),
            modifier = Modifier
                .background(TgFeedTokens.OverlayBg, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

/** Grid 分屏画布手势标注：2×2 排布在画布中央（手势作用于整块画布，无单点区域语义） */
@Composable
private fun BoxScope.GridCoachMarks() {
    Column(
        modifier = Modifier.align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            CoachMark(gesture = "捏合", action = "缩放画布", rotate = -2f)
            CoachMark(gesture = "拖动空白", action = "平移画布", rotate = 1.5f)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            CoachMark(
                icon = Icons.Filled.Fullscreen,
                gesture = "拖右下角柄",
                action = "调整格子大小",
                rotate = 2f,
            )
            CoachMark(gesture = "长按格子", action = "拖动换位", rotate = -1.5f)
        }
    }
}

/**
 * 手绘风标注气泡：白色虚线圆角描边 + 轻微旋转（[rotate] 模拟手绘的不规整感），
 * 内部 = 可选图标 + 手势名（粗）+ 结果说明（细）。
 * drawBehind 纯绘制不插手事件，点击会穿透到覆盖层根节点触发 dismiss。
 */
@Composable
private fun CoachMark(
    gesture: String,
    action: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    rotate: Float = 0f,
) {
    Column(
        modifier = modifier
            .graphicsLayer { rotationZ = rotate }
            .drawBehind {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.85f),
                    cornerRadius = CornerRadius(18.dp.toPx(), 18.dp.toPx()),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f)),
                    ),
                )
            }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = TgFeedTokens.TextHigh,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(
            gesture,
            color = TgFeedTokens.TextHigh,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            action,
            color = TgFeedTokens.TextMedium,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}
