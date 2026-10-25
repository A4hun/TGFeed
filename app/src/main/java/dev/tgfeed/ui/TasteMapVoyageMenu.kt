package dev.tgfeed.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.tgfeed.ui.components.kit.avoidBottomBar
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlin.math.cos
import kotlin.math.sin

/**
 * R356 启航径向菜单（右下/探索条收纳件——用户 08:1x「航行日志/导出海报/飞往
 * 恒星/飞往随机恒星/延时摄影都收纳一下，或者以另外的方式呈现——目前这种
 * 设计太low了」）：
 *  - 右下**单主钮**「启航」（48dp RocketLaunch 圆钮）点开 quarter-arc 径向扇：
 *    动作件沿 r=112dp 弧自主钮左上方展开，逐件错峰 45ms 缩放渐显（径向绽放）；
 *    点空白/主钮再点/动作执行=收起；
 *  - 图标名两字上限+图标沿用旧钮（恒星 NearMe/随机 Shuffle/延时 AutoAwesome/
 *    日志 MilitaryTech/海报 Share）=视觉延续；busy=导出中禁用变暗；
 *    enabled=false=诚实空态（延时无星生序证据不出门）；
 *  - 纯展示组件：[TasteVoyageActionR356] 数据类 lambda 接线，无 ctx 依赖
 *    （接线全在舞台件=预算闸安全位，主方法零接线）。
 *
 * 几何（168dp 容器盒，主钮居盒右下角）：动作件 i 中心=主钮中心 + r·(−cosθ_i,
 * −sinθ_i)，θ 自 90°（正上）按 18° 步进向左展开——dp 口径偏移（密度无关，
 * 各档 DPI 同布局）；图形层 scale/alpha=入场进度。
 */
internal data class TasteVoyageActionR356(
    /** 图标名（≤2 字——R356WiringSourceTest 文案红线锚逐个断言） */
    val label: String,
    val icon: ImageVector,
    /** 诚实空态门（false=变暗不可点——如延时无星生序证据） */
    val enabled: Boolean = true,
    /** 忙态（导出中=变暗不可点） */
    val busy: Boolean = false,
    val action: () -> Unit,
)

/** R356 启航径向菜单（BoxScope 扩展——舞台件 Box 内挂载） */
@Composable
internal fun BoxScope.TasteMapVoyageHubR356(
    open: Boolean,
    actions: List<TasteVoyageActionR356>,
    onTrigger: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 点空白收起层（**舞台级兄弟层**——matchParentSize 匹配调用侧舞台盒=全屏；
    // 先绘=主钮/动作件后绘在上层不误伤；嵌在 168dp 盒内只盖盒面=点图不收的坑）
    if (open) {
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        )
    }
    Box(
        Modifier
            .align(Alignment.BottomEnd)
            .navigationBarsPadding()
            .avoidBottomBar() // R226 统一避让钥匙（旧导出组同款）
            .padding(end = 20.dp, bottom = 20.dp)
            .size(HUB_BOX),
    ) {
        // 主钮「启航」（盒右下角——旧导出组原位语义：收纳不挪窝）
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(48.dp)
                .clip(CircleShape)
                .background(TgFeedTokens.OverlayBgStrong)
                .border(0.5.dp, TgFeedTokens.OverlayHairline, CircleShape)
                .clickable(onClick = onTrigger),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.RocketLaunch,
                contentDescription = if (open) "收起启航" else "启航",
                tint = if (open) TgFeedTokens.AccentRed else TgFeedTokens.TextMedium,
                modifier = Modifier.size(TgFeedTokens.IconXl),
            )
        }
        if (open) {
            actions.forEachIndexed { i, act ->
                // 入场进度（错峰绽放：i×45ms 延迟后 150ms 缩放到位；收起=整体
                // 摘除即收——径向扇的呼吸感只给开场）
                val enter = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(i * 45L)
                    // AnimFast=150 档（MotionTokensPolicy 红线：tween 时长走 token）
                    enter.animateTo(1f, tween(TgFeedTokens.AnimFast, easing = FastOutSlowInEasing))
                }
                val thetaRad = Math.toRadians(ARC_START_DEG - ARC_STEP_DEG * i)
                val dx = RADIUS_DP * cos(thetaRad)
                val dy = RADIUS_DP * sin(thetaRad)
                Column(
                    Modifier
                        .offset(
                            x = (HUB_BOX.value - 24f - dx).dp,
                            y = (HUB_BOX.value - 30f - dy).dp,
                        )
                        .graphicsLayer {
                            alpha = enter.value
                            scaleX = enter.value
                            scaleY = enter.value
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(TgFeedTokens.OverlayBgStrong)
                            .border(0.5.dp, TgFeedTokens.OverlayHairline, CircleShape)
                            .clickable(enabled = act.enabled && !act.busy) {
                                act.action()
                                onDismiss()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            act.icon,
                            contentDescription = act.label,
                            tint = if (act.enabled && !act.busy) TgFeedTokens.AccentRed else TgFeedTokens.TextLow,
                            modifier = Modifier.size(TgFeedTokens.IconLg),
                        )
                    }
                    Text(
                        act.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (act.enabled && !act.busy) TgFeedTokens.TextMedium else TgFeedTokens.TextLow,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** R356 径向扇几何私有档（dp——容器/半径/起始角/步进；不入 TgFeedTokens：星图收纳件私有） */
private val HUB_BOX = 168.dp
private val RADIUS_DP = 112f
private const val ARC_START_DEG = 90.0
private const val ARC_STEP_DEG = 18.0
