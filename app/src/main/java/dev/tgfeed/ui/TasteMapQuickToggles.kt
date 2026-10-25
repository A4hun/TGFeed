package dev.tgfeed.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.tgfeed.ui.theme.TgFeedTokens

/**
 * R356 星图快捷切换图标组（口味星图设置内迁件——用户 08:1x 拍板「不应该放在
 * 设置里，应该放在口味星图里边，做成图标的小样式」）：
 *  - 照相机 app 快捷档形态：顶栏右上**单触发钮**（40dp Tune 圆钮，开态强调色）
 *    点开下拉图标组——一行 36dp 图标钮 + 下方一行极简回读（点按回读「星座 开」；
 *    长按显示「涌现 · 重进生效」式生效口径——布局类不给口径=翻转后画面不变
 *    像坏了，诚实红线）；
 *  - 图标态=开/关视觉直读：开=AccentRed+微红底、关=TextMedium+白微底（旧星座
 *    星标 tint 翻转的既有视觉语言+底色通道，零新视觉件）；点空白收起；
 *  - 文案红线（D）：图标名 ≤2 字、回读/口径恒一行零废话——无 help 段、无
 *    副标题、无解释段（宪法「克制」）；
 *  - 纯展示组件：[TasteQuickToggleR356] 数据类全 lambda 接线，无 ctx 依赖
 *    （GalaxyLogicCtx 是 private class 不跨文件——接线全在舞台件=预算闸
 *    安全位，主方法零接线）。语义接线（键/默认/写法逐句同款=迁移零变化）
 *    见 TasteMapScreen 舞台件调用点。
 */
internal data class TasteQuickToggleR356(
    /** 图标名（≤2 字——R356WiringSourceTest 文案红线锚逐个断言） */
    val label: String,
    /** 长按极简一行说明（「涌现 · 重进生效」——恒一行） */
    val hint: String,
    val icon: ImageVector,
    /** 当前开关态（视觉直读源：开=强调色） */
    val on: Boolean,
    /** 点按=翻转（写键+运行时同步在接线侧） */
    val onToggle: () -> Unit,
)

/** R356 星图快捷切换组（BoxScope 扩展——舞台件 Box 内挂载） */
@Composable
internal fun BoxScope.TasteMapQuickTogglesR356(
    open: Boolean,
    toggles: List<TasteQuickToggleR356>,
    /** 极简一行回读/口径（空串=不显示；点按与长按共用此行——零第二文案位） */
    note: String,
    onTrigger: () -> Unit,
    onDismiss: () -> Unit,
    /** 长按口径回传（接线侧写入 note） */
    onHint: (String) -> Unit,
) {
    // 触发钮：顶栏右上唯一件（R356 右上合并——旧星座/延时两钮退役后的必要件）
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .padding(end = 20.dp, top = 8.dp)
            .size(40.dp)
            .clip(CircleShape)
            .background(TgFeedTokens.OverlayBgStrong)
            .border(0.5.dp, TgFeedTokens.OverlayHairline, CircleShape)
            .clickable(onClick = onTrigger),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Tune,
            contentDescription = if (open) "收起星图开关" else "星图开关",
            tint = if (open) TgFeedTokens.AccentRed else TgFeedTokens.TextMedium,
            modifier = Modifier.size(TgFeedTokens.IconLg),
        )
    }
    if (open) {
        // 点空白收起层（开期占用命中=下拉模态；面板/触发钮后绘在上层不误伤）
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        )
        // 下拉面板：触发钮正下方一行图标组+一行回读（面板自带容器底/发丝边）
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = 20.dp, top = 56.dp)
                .clip(RoundedCornerShape(TgFeedTokens.RadiusLg))
                .background(TgFeedTokens.OverlayBgStrong)
                .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusLg))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd)) {
                toggles.forEach { t ->
                    // 单节点单 pointerInput（纪律§四：同节点双手势件互杀——tap/
                    // long-press 同一 detectTapGestures 承载；键=label 稳定，
                    // 翻转走状态对象=闭包旧引用行为等价）
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (t.on) TgFeedTokens.AccentRed.copy(alpha = 0.16f)
                                else Color.White.copy(alpha = 0.06f),
                            )
                            .pointerInput(t.label) {
                                detectTapGestures(
                                    onTap = { t.onToggle() },
                                    onLongPress = { onHint(t.hint) },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            t.icon,
                            contentDescription = if (t.on) "${t.label}开" else "${t.label}关",
                            tint = if (t.on) TgFeedTokens.AccentRed else TgFeedTokens.TextMedium,
                            modifier = Modifier.size(TgFeedTokens.IconMd),
                        )
                    }
                }
            }
            if (note.isNotEmpty()) {
                Text(
                    note,
                    style = MaterialTheme.typography.labelMedium,
                    color = TgFeedTokens.TextMedium,
                    maxLines = 1,
                )
            }
        }
    }
}
