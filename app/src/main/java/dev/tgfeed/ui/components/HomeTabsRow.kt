package dev.tgfeed.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** R245：三 tab 等分槽行宽帽（dp）——宽屏下 tab 带不铺满整中区（抖音式居中带读法），窄屏让位收缩 */
private val HomeTabsRowMaxWidth = 420.dp

/**
 * R239 首页三 tab 行（**关注 | 时间线 | 推荐**——时间线居中一等公民）：
 * 首页顶栏的唯一事实源渲染件，两个消费方共用——
 *  1. [FeedTopBarOverlay]（feed 态顶栏，视频画面上的浮层）；
 *  2. PursueScreen 页头（时间线 overlay 态）。
 * 同一 [TopLevelTab] 渲染（R157 平级双 tab 的三 tab 扩展——视觉权重一致、
 * 同一 rememberDoubleTapRule 双击刷新语义：单击未激活=纯切换、单击已激活=
 * 无操作、双击已激活=刷新该流）。「时间线」双击=时间线重拉
 * （timelineRefreshRequest nonce），「关注/推荐」双击=feed 刷新（R189 口径）。
 *
 * 纪律（AGENT-DISCIPLINE §一收敛）：三 tab 的排布/文案/双击语义只许在这一处
 * 定义——两个宿主各写一份会漂移（R157 双 tab 失衡教训的预防针）。
 *
 * R245（D1 溢出加固，实锤①类修）：行由自然宽改为 **fillMaxWidth+widthIn(420 帽)+
 * 三 tab weight(1f) 等分槽**——旧形态下窄窗口（分屏/小窗/大字号）三 tab 自然宽
 * 超出中区 Box(weight(1f)) 时，尾 tab「推荐」滑入右侧搜索/分屏图标之下（Row 后序
 * 兄弟绘制在上=被覆盖）=「tab 不见了」的失效态；等分槽后任何宽度三 tab 恒在场
 * （极窄时槽内省略号保位），宽屏帽 420dp 保持居中带读法不铺满。两宿主同件生效。
 *
 * @param followingActive 「关注」流在播（FeedScreen 侧=vm.source is Following）
 * @param timelineActive 时间线 overlay 在场（AppContainer.homeTimelineOn）
 * @param aggregateActive 「推荐」流在播（聚合两实现都算）
 * @param onSelectFollowing 点「关注」：时间线态=收层+切关注流；feed 态=切关注流
 * @param onSelectTimeline 点「时间线」：开时间线 overlay（AppContainer.openPursue）
 * @param onSelectAggregate 点「推荐」：对称切推荐流
 * @param onRefreshFeed 双击已激活的「关注/推荐」=刷新 feed（R189）
 * @param onRefreshTimeline 双击已激活的「时间线」=时间线重拉（R239 nonce）
 */
@Composable
internal fun HomeTabsRow(
    followingActive: Boolean,
    timelineActive: Boolean,
    aggregateActive: Boolean,
    onSelectFollowing: () -> Unit,
    onSelectTimeline: () -> Unit,
    onSelectAggregate: () -> Unit,
    onRefreshFeed: () -> Unit,
    onRefreshTimeline: () -> Unit,
) {
    Row(
        // R245：widthIn 在外/fillMaxWidth 在内——先钳约束再填充（反序时 fillMaxWidth
        // 会把布局尺寸钉在整中区宽、内层 widthIn 钳不住=帽失效，16416 实测教训）
        Modifier
            .widthIn(max = HomeTabsRowMaxWidth)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 「关注」tab：订阅频道的完整内容场（等分槽——R245 恒可见加固）
        TopLevelTab(
            "关注", active = followingActive, onClick = onSelectFollowing, onRefresh = onRefreshFeed,
            modifier = Modifier.weight(1f),
        )
        // 「时间线」tab：R239 一等公民居中——全频道媒体完整时间流（最新在底）
        TopLevelTab(
            "时间线", active = timelineActive, onClick = onSelectTimeline,
            onRefresh = onRefreshTimeline,
            modifier = Modifier.weight(1f),
        )
        // 「推荐」tab：聚合推荐流（与关注完全对称的纯 tab）
        TopLevelTab(
            "推荐", active = aggregateActive, onClick = onSelectAggregate, onRefresh = onRefreshFeed,
            modifier = Modifier.weight(1f),
        )
    }
}
