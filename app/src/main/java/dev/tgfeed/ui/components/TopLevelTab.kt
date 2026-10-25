package dev.tgfeed.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tgfeed.ui.rememberDoubleTapRule
import dev.tgfeed.ui.theme.TgFeedTokens

/**
 * R157 顶栏平级 tab（「关注」「推荐」共用；R173 起发现页同件复用，
 * design-proposal §2.4：全 App 形成「一级页都有自己的轻量顶 tab」统一语言）。
 *
 * 17sp、在播高亮加粗、不在播降透明度——所有一级页的轻量顶 tab 经同一函数渲染，
 * 从代码上保证视觉权重一致（修"推荐挂下拉箭头+大面板，关注只是纯 tab 被比下去"
 * 的失衡）。
 *
 * R189 双击刷新规则（单击语义收窄）：**单击已激活 = 无操作**（R157「在播时单击=
 * 刷新一批」废止——用户拍板刷新触发收窄为双击）；**双击已激活 = [onRefresh]**（刷新
 * 该流，默认空实现=双击无操作）；单击未激活 = onClick 纯切换（立即执行不迟滞）。
 * 判定走 [rememberDoubleTapRule] 共享窗口状态机（单一实现，媒体条/底栏同款）。
 *
 * R209 dragWeight 跟手参数随 R232 撤（唯一消费方=发现页「发现|热榜」双 tab 的
 * 横滑轨道，轨道整体撤销）——本件回到 R157/R189 形态。
 *
 * 首页（视频画面上）与发现页（纯黑底）共用同一渲染：withOverlayShadow 在黑底上
 * 无副作用，两处观感天然一致——这就是"同一渲染件"的含义，不许各页再抄一份。
 *
 * R245（D1 溢出加固）：新增 [modifier] 参（默认值=旧行为，调用方零改动）——
 * HomeTabsRow 以 weight(1f) 等分槽位传入时，本件成为「恒可见 tab」：槽内文案
 * 居中+单行省略（窄槽不消失只收窄），杜绝自然宽 tab 行在窄窗口下尾 tab 滑入
 * 侧区图标之下的「被覆盖」失效态（r245 实锤①的类修面）。
 */
@Composable
internal fun TopLevelTab(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val handleTap = rememberDoubleTapRule(
        active = active,
        onSwitch = onClick,
        onRefresh = onRefresh,
    )
    Text(
        label,
        // 不在播 α0.6 / 在播 α1.0（R157 原版口径）
        color = TgFeedTokens.TextHigh.copy(alpha = if (active) 1f else 0.6f),
        style = TextStyle(
            fontSize = 17.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            // R245：槽位化后文案在槽内居中（weight 等分槽的视觉重心=槽心）
            textAlign = TextAlign.Center,
        ).withOverlayShadow(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
            .clickable(onClick = handleTap)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}
