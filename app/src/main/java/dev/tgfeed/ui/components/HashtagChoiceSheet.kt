package dev.tgfeed.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.tgfeed.ui.components.kit.AppListItem
import dev.tgfeed.ui.components.kit.AppSheetMotion
import dev.tgfeed.ui.components.kit.SheetAnchor
import dev.tgfeed.ui.components.kit.SheetStaggeredSection
import dev.tgfeed.ui.theme.MotionTokens
import dev.tgfeed.ui.theme.TgFeedTokens

/**
 * R222 #话题双选弹层：caption #标签点击的入口整合菜单（两行）。
 *
 * 背景：点 #标签的语义原来只有一条路（混合双腿搜索直接开刷）。R222 把事件研究器
 * 挂到这个自然交互上——点标签先弹本菜单两选一：
 *  - 「时间轴探索」：新行为——开 hashtag 时间轴页（该标签从头到尾的时间分布，
 *    点轴上任意节点从那块开始播放）；
 *  - 「搜索播放」：旧行为原样（「标签：#tag」源 + 深索引/服务端混合搜索直接开刷，
 *    链体零改动——整合不是替换）。
 *
 * R269：新增可选首行「看内容·直接开刷」（[onPlayContent] 非空才渲染——星图飞进
 * 行星门宿主传入；该 tag 全宇宙内容纯本地直刷，tag_items 倒排毫秒级 + R267 LIKE
 * 兜底）。行序=[看内容]→时间轴探索→搜索播放→[看传播]：时间轴页由此降为本菜单的
 * 二级入口（任务书口径）；feed caption 弹层不传 = 两行逐字节不变（R222 已验链
 * 零触碰）。
 *
 * 视觉/动效：R201 分享枢纽同款语法（AppSheetMotion BOTTOM 锚定 + AppListItem
 * 双行文字整行热区 + 两段 stagger）；点行 = 先收面板再执行动作
 * （sheet.dismiss afterClosed 挂点，两层面板不叠层登场）。
 *
 * @param tag 标签词（不含 #；标题展示补 #）
 * @param onExploreTimeline 「时间轴探索」——外层容器路由开时间轴页
 * @param onSearchPlay 「搜索播放」——旧行为（切标签源直接开刷）
 * @param onShowSpread R234 三期「看传播」——在星图上渲染该关键字从首发频道
 *   扩散的尘埃带（星图宿主传入；null=本行不渲染——feed 弹层等既有调用方零变化，
 *   ActionSheet onOpenTasteMap 同口径的可选格模式）
 * @param onPlayContent R269「看内容·直接开刷」——该 tag 全宇宙内容流（纯本地
 *   tag_items 倒排+LIKE 兜底；星图飞入门宿主传入；null=本行不渲染）
 * @param onDismiss 请求关窗（壳退场动画播完后回调）
 */
@Composable
internal fun HashtagChoiceSheet(
    tag: String,
    onExploreTimeline: () -> Unit,
    onSearchPlay: () -> Unit,
    onDismiss: () -> Unit,
    onShowSpread: (() -> Unit)? = null,
    onPlayContent: (() -> Unit)? = null,
) {
    AppSheetMotion(
        onDismiss = onDismiss,
        title = "#$tag",
        anchor = SheetAnchor.BOTTOM,
    ) { sheet ->
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = TgFeedTokens.GapMd),
        ) {
            // 各行按段 stagger（0/+40/+80…；退场无 stagger——壳统一利落收起）。
            // 新行为排首行（本任务的产品方向：研究器挂上主界面自然交互）；
            // 旧行为次行原文案语义（「原路径」明示，用户知道这是老路）。
            // R269：可选首行「看内容·直接开刷」（行星内容流=该 tag 全宇宙内容纯本地
            // 直刷——任务书波次二主路径；行序位在时间轴探索之上=时间轴降二级入口）
            if (onPlayContent != null) {
                SheetStaggeredSection(delayMs = 0) {
                    AppListItem(
                        title = "看内容 · 直接开刷",
                        subtitle = "本地索引里这个标签的全部内容，视频图文直接刷（毫秒级）",
                        leadingIcon = Icons.Outlined.PlayCircle,
                        onClick = { sheet.dismiss { onPlayContent() } },
                    )
                }
            }
            SheetStaggeredSection(
                // 可选首行在场时本行顺移一段（星图宿主）；不在场=0 与原 R222 两行逐字节一致
                delayMs = if (onPlayContent != null) MotionTokens.SheetStaggerStepMs else 0,
            ) {
                AppListItem(
                    title = "时间轴探索",
                    subtitle = "这个标签从头到尾的时间分布，点任意节点即可从那块开始播放",
                    leadingIcon = Icons.Outlined.Timeline,
                    onClick = { sheet.dismiss { onExploreTimeline() } },
                )
            }
            SheetStaggeredSection(delayMs = MotionTokens.SheetStaggerStepMs) {
                AppListItem(
                    title = "搜索播放",
                    subtitle = "按标签搜索内容并直接开始播放（原路径）",
                    leadingIcon = Icons.Outlined.PlayArrow,
                    onClick = { sheet.dismiss { onSearchPlay() } },
                )
            }
            // R234① 三期第三选（星图宿主专属：feed 弹层不传=不渲染）：
            // 该关键字从首发频道向各频道扩散的传播可视化
            if (onShowSpread != null) {
                SheetStaggeredSection(delayMs = MotionTokens.SheetStaggerStepMs) {
                    AppListItem(
                        title = "看传播",
                        subtitle = "在星图上显示这个关键字从首发频道扩散的尘埃带，可拖时间轴回看传播史",
                        leadingIcon = Icons.Outlined.Explore,
                        onClick = { sheet.dismiss { onShowSpread() } },
                    )
                }
            }
        }
    }
}
