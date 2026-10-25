package dev.tgfeed.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.ui.ShareHubLink
import dev.tgfeed.ui.ShareHubRow
import dev.tgfeed.ui.ShareHubRowState
import dev.tgfeed.ui.components.kit.AppListItem
import dev.tgfeed.ui.components.kit.AppSheetMotion
import dev.tgfeed.ui.components.kit.SheetAnchor
import dev.tgfeed.ui.components.kit.SheetStaggeredSection
import dev.tgfeed.ui.shareHubRowState
import dev.tgfeed.ui.theme.MotionTokens
import dev.tgfeed.ui.theme.TgFeedTokens

/**
 * R201 分享枢纽面板（ShareHubSheet）：主界面分享入口的统一小菜单。
 *
 * 背景：分享能力原来三个入口各直跳一个动作——右轨分享钮直跳系统分享（ACTION_SEND
 * 链接）、长按面板「复制链接」直跳剪贴板、长按面板「分享卡片」直跳图卡生成。R201
 * 收口：点分享 → 弹本菜单三选一，三能力链路本身零改动（链体都在宿主 FeedScreen，
 * 本面板只做入口整合与菜单态）。
 *
 * 三行（渲染序 = [ShareHubRow] 口径）：
 *  - 复制链接：链接解析到手才可点；
 *  - 分享到其他应用：同上（ACTION_SEND 系统面板，文本=公开链接）；
 *  - 分享卡片：恒可点（ShareCard.generate 对 link=null 本就跳链接行）。
 *
 * 菜单态由 [shareHubRowState] 纯函数裁决（单测守护）：面板打开即起 [resolveLink]
 * 解析（异步），解析中两行禁用+「正在获取公开链接…」；确定无公开链接两行禁用+
 * 按媒体类型的如实口径（「该视频没有公开链接」沿用）；分享卡片行不受牵连。
 *
 * 动效/视觉：AppSheetMotion 壳（BOTTOM 锚定——触发点在屏幕下方/右侧轨的就近锚定
 * 裁决；MotionTokens sheet spring + scrim 180/150ms），内容三段 stagger
 * （0/+40/+80ms，SheetStaggeredSection）。关窗契约由壳统一保证（先播完退场再
 * [onDismiss]）；点行 = 先收面板再执行动作（afterClosed 挂点），系统分享面板
 * 不会与本面板叠层登场。
 *
 * @param item 目标条目（菜单态文案按 item.mediaType 分词）
 * @param resolveLink 公开链接解析口（宿主的 getMessageLink 封装；null = 无公开链接）
 * @param onCopyLink 复制链接（参数=已解析链接；剪贴板+Toast 由宿主收口）
 * @param onSendToApps 分享到其他应用（参数=已解析链接；ACTION_SEND 由宿主收口）
 * @param onShareCard 分享卡片（参数=目标条目；图卡生成链在宿主内部自行取链接——
 *   卡链沿用面板时代行为：IO 线程解析一次，与菜单解析互不等待）
 * @param onDismiss 请求关窗（壳退场动画播完后回调）
 */
@Composable
internal fun ShareHubSheet(
    item: VideoItem,
    resolveLink: suspend (VideoItem) -> String?,
    onCopyLink: (String) -> Unit,
    onSendToApps: (String) -> Unit,
    onShareCard: (VideoItem) -> Unit,
    onDismiss: () -> Unit,
) {
    // 链接解析态：面板打开即解析（LaunchedEffect 主上下文，与既有复制链接链同款）；
    // 换条目（同一次会话不该发生，防御）按 key 重解析
    var link by remember(item.key) { mutableStateOf<ShareHubLink>(ShareHubLink.Resolving) }
    LaunchedEffect(item.key) {
        val url = resolveLink(item)
        link = if (url != null) ShareHubLink.Ready(url) else ShareHubLink.NoPublicLink
    }

    AppSheetMotion(
        onDismiss = onDismiss,
        title = "分享",
        anchor = SheetAnchor.BOTTOM,
    ) { sheet ->
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = TgFeedTokens.GapMd),
        ) {
            // 三行各一段 stagger（0/+40/+80ms，退场无 stagger——壳统一利落收起）；
            // 行骨架复用 kit 的 AppListItem（菜单项统一骨架：IconLg 前导 + 双行文字
            // + 整行热区；置灰态走其 enabled 参数——DisabledContentAlpha 统一口径）
            ShareHubRow.entries.forEachIndexed { idx, rowSpec ->
                SheetStaggeredSection(delayMs = idx * MotionTokens.SheetStaggerStepMs) {
                    val spec = hubRowSpec(rowSpec)
                    val state: ShareHubRowState = shareHubRowState(rowSpec, link, item.mediaType)
                    AppListItem(
                        title = spec.title,
                        // 覆盖文案（解析中/无链接的如实说明）优先于默认描述
                        subtitle = state.hint ?: spec.description,
                        leadingIcon = spec.icon,
                        enabled = state.enabled,
                        onClick = {
                            // 先收面板再执行（afterClosed）：菜单退场动画播完才拉起
                            // 系统分享/Toast/图卡链，两层面板不叠层登场
                            sheet.dismiss {
                                val url = (link as? ShareHubLink.Ready)?.url
                                when (rowSpec) {
                                    ShareHubRow.COPY_LINK -> if (url != null) onCopyLink(url)
                                    ShareHubRow.SEND_APPS -> if (url != null) onSendToApps(url)
                                    ShareHubRow.CARD -> onShareCard(item)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 菜单行的静态规格（图标/标题/默认描述）——文案与 [ShareHubRow] 一一对应，只此一处 */
private data class HubRowSpec(
    val icon: ImageVector,
    val title: String,
    val description: String,
)

private fun hubRowSpec(row: ShareHubRow): HubRowSpec = when (row) {
    // 图标沿用各能力的既有图标族：复制=ContentCopy（剪贴板语义）、系统分享=Share
    //（通用分享箭头）、分享卡片=IosShare（R175 之前面板「分享卡片」同款）
    ShareHubRow.COPY_LINK -> HubRowSpec(Icons.Outlined.ContentCopy, "复制链接", "把公开链接复制到剪贴板")
    ShareHubRow.SEND_APPS -> HubRowSpec(Icons.Outlined.Share, "分享到其他应用", "拉起系统分享面板")
    ShareHubRow.CARD -> HubRowSpec(Icons.Outlined.IosShare, "分享卡片", "生成封面图卡并存入相册")
}
