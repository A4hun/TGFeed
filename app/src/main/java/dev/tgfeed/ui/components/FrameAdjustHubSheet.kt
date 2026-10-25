package dev.tgfeed.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.Rotate90DegreesCcw
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import dev.tgfeed.ui.FrameAdjustRow
import dev.tgfeed.ui.frameAdjustRowEnabled
import dev.tgfeed.ui.frameAdjustRowSubtitle
import dev.tgfeed.ui.components.kit.AppListItem
import dev.tgfeed.ui.components.kit.AppSheetMotion
import dev.tgfeed.ui.components.kit.SheetAnchor
import dev.tgfeed.ui.components.kit.SheetStaggeredSection
import dev.tgfeed.ui.theme.MotionTokens
import dev.tgfeed.ui.theme.TgFeedTokens

/**
 * R227 画面调整枢纽面板（FrameAdjustHubSheet）：旋转 + 镜像的统一小菜单，
 * 复刻 R201 分享枢纽（ShareHubSheet）模式——AppSheetMotion（BOTTOM 锚定 +
 * MotionTokens sheet spring/scrim）+ 段落 stagger + AppListItem 行骨架 +
 * 点行=先收面板再执行（afterClosed，两层面板不叠层登场）。
 *
 * 背景：R218 镜像与 R174 手动旋转原是长按面板两个独立格（各自直跳一个动作）。
 * R227 收口：点「画面调整」→ 弹本菜单四选一（向左旋转 / 向右旋转 / 左右镜像 /
 * 恢复默认）——姿态动作聚拢后，旋转的「+90 步进绕圈」和镜像的「来回 toggle」
 * 升级成带方向的显式选项 + 一键归零（组合态可累积，没有归零口就是死胡同）。
 *
 * 与 ShareHubSheet 的一个架构差异：分享的三条能力链体在各宿主（FeedScreen 收口），
 * 而画面调整的"链体"就是 [FrameAdjustStore] 本身（store 即单一真源，无宿主接线
 * 差异）——本面板**自持读写**（stateOf 组合期订阅、动作直调 store），两宿主
 * （FeedScreen / GridScreen）只需传 itemKey + onDismiss，零能力接线，杜绝
 * 两处各写一份动作链。
 *
 * 菜单态由 [frameAdjustRowSubtitle]/[frameAdjustRowEnabled] 纯函数裁决
 * （单测守护）：每行副文案带当前态指示（当前角度/镜像态/组合态总述）；
 * 恢复默认行在已是默认姿态时如实禁用（不假装可点，R201 口径）。
 *
 * @param itemKey 目标条目 key（FrameAdjustStore 键级记忆；空 key 走默认态零动作）
 * @param onDismiss 请求关窗（壳退场动画播完后回调）
 */
@Composable
internal fun FrameAdjustHubSheet(
    itemKey: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // 组合期读快照表即订阅：面板在场期间外部（右轨旋转钮等）改姿态，行文案同步
    val state = FrameAdjustStore.stateOf(context, itemKey)

    AppSheetMotion(
        onDismiss = onDismiss,
        title = "画面调整",
        anchor = SheetAnchor.BOTTOM,
    ) { sheet ->
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = TgFeedTokens.GapMd),
        ) {
            // 四行各一段 stagger（0/+40/+80/+120ms——R201 三行 0/40/80 的同节律
            // 延到第四行；退场无 stagger，壳统一利落收起）；行骨架复用 kit 的
            // AppListItem（菜单项统一骨架：IconLg 前导 + 双行文字 + 整行热区）
            FrameAdjustRow.entries.forEachIndexed { idx, rowSpec ->
                SheetStaggeredSection(delayMs = idx * MotionTokens.SheetStaggerStepMs) {
                    val spec = hubRowSpec(rowSpec)
                    AppListItem(
                        title = spec.title,
                        // 副文案=当前态指示（当前角度/镜像态/组合态总述，纯函数裁决）
                        subtitle = frameAdjustRowSubtitle(rowSpec, state.rotationDeg, state.mirrored),
                        leadingIcon = spec.icon,
                        enabled = frameAdjustRowEnabled(rowSpec, state.rotationDeg, state.mirrored),
                        onClick = {
                            // 先收面板再执行（afterClosed）：退场动画播完才改姿态，
                            // scrim 退场与画面旋转/翻转不同帧登场（R201 同契约）
                            sheet.dismiss {
                                when (rowSpec) {
                                    FrameAdjustRow.ROTATE_LEFT ->
                                        FrameAdjustStore.rotate(context, itemKey, -90)
                                    FrameAdjustRow.ROTATE_RIGHT ->
                                        FrameAdjustStore.rotate(context, itemKey, +90)
                                    FrameAdjustRow.MIRROR ->
                                        FrameAdjustStore.toggleMirror(context, itemKey)
                                    FrameAdjustRow.RESET ->
                                        FrameAdjustStore.reset(context, itemKey)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 菜单行的静态规格（图标/标题）——文案与 [FrameAdjustRow] 一一对应，只此一处
 *（命名区别于 ShareHubSheet 的 HubRowSpec：同包顶层声明不可重名） */
private data class FrameAdjustRowSpec(
    val icon: ImageVector,
    val title: String,
)

private fun hubRowSpec(row: FrameAdjustRow): FrameAdjustRowSpec = when (row) {
    // 图标沿用姿态动作既有图标族：向右旋转=Rotate90DegreesCw（右轨旋转钮同款）、
    // 向左旋转=Rotate90DegreesCcw（镜像对称款）、左右镜像=Flip（R218 镜像行同族）、
    // 恢复默认=SettingsBackupRestore（回退语义）
    FrameAdjustRow.ROTATE_LEFT -> FrameAdjustRowSpec(Icons.Outlined.Rotate90DegreesCcw, "向左旋转")
    FrameAdjustRow.ROTATE_RIGHT -> FrameAdjustRowSpec(Icons.Outlined.Rotate90DegreesCw, "向右旋转")
    FrameAdjustRow.MIRROR -> FrameAdjustRowSpec(Icons.Outlined.Flip, "左右镜像")
    FrameAdjustRow.RESET -> FrameAdjustRowSpec(Icons.Outlined.SettingsBackupRestore, "恢复默认")
}
