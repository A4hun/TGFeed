package dev.tgfeed.ui.components.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tgfeed.ui.SheetMaxWidth
import dev.tgfeed.ui.theme.TgFeedTokens
import dev.tgfeed.ui.components.kit.LocalBottomBarInset

/**
 * ModalBottomSheet 统一壳：全 App 底部面板的唯一入口。
 *
 * 什么时候用它：
 * - 任何"从底部升起的模态面板"：筛选、更多操作、详情、片单选择等
 *   （替换 ActionSheet / FilterSheet / SearchPreviewSheet 各自手糊的外壳）；
 * - 需要拖拽手势关闭 + 系统返回关闭的内容。
 *
 * 什么时候不用：
 * - 需要强打断用户、必须二选一的确认，用 [AppDialog]（居中对话框）；
 * - 非模态的常驻面板（如播放控制条），自己写 Surface，不要套 sheet。
 *
 * 视觉规格（统一，勿在调用处覆写）：
 * - 底 [TgFeedTokens.Surface]，顶部圆角 [TgFeedTokens.RadiusLg]；
 * - 顶部居中拖拽条（32×4dp 圆角白条）；
 * - 标题位：17sp SemiBold [TgFeedTokens.TextHigh]，右侧关闭 X（[AppIconButton] 小号）；
 * - 内建 navigationBarsPadding，内容不会被手势条吃掉。
 *
 * @param onDismiss 请求关闭（拖拽到底、点遮罩、点 X、系统返回都会触发）；
 *                  调用方据此把控制显示的 state 置 false。
 * @param modifier 外部修饰符。
 * @param title 可选标题；null 则不渲染标题行（如纯操作格面板）。
 * @param showClose 有标题时是否显示右侧关闭 X，默认显示。
 * @param sheetState 外部可传（如需 skipPartiallyExpanded），默认全展开。
 * @param content 面板内容，ColumnScope 槽；自己负责内部滚动与横向留白。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    showClose: Boolean = true,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        // R171 弹层统一限宽（平板巨屏铺满整宽没法看）：全 App 面板唯一口径
        // Responsive.SheetMaxWidth——与 ActionSheet/FilterSheet 等既有手工面板同规格收敛，
        // 旧状态吃 material3 默认 640dp 与手工 560 不一致（字节码实证），此补齐归一
        sheetMaxWidth = SheetMaxWidth,
        containerColor = TgFeedTokens.Surface,
        shape = RoundedCornerShape(
            topStart = TgFeedTokens.RadiusLg,
            topEnd = TgFeedTokens.RadiusLg,
        ),
        dragHandle = { AppSheetHandle() },
    ) {
        if (title != null) {
            AppSheetTitleRow(
                title = title,
                onClose = onDismiss,
                showClose = showClose,
            )
            Spacer(Modifier.height(TgFeedTokens.GapSm))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                // 2026-10-17 用户实锤弹窗被透明 dock 挡底：sheet 族消费 LocalBottomBarInset
                //（dock 可见时抬起 44/56dp——与 chrome 层同款 inset 纪律）
                .padding(bottom = LocalBottomBarInset.current),
            content = content,
        )
    }
}

/**
 * AppSheet 家统一拖拽条（R173 从 AppSheet 抽出共享）：居中小白条 32×4dp 圆角。
 * [AppSheet]（ModalBottomSheet 壳）与 [AppSheetMotion]（动效壳）共用同一实现，
 * 拖拽条样式只此一处，勿在各面板再自绘。
 */
@Composable
internal fun AppSheetHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(vertical = TgFeedTokens.GapSm)
            .size(width = 32.dp, height = 4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(TgFeedTokens.TextLow),
    )
}

/**
 * AppSheet 家统一标题行（R173 从 AppSheet 抽出共享）：17sp SemiBold 标题 + 右侧
 * 关闭 X（AppIconButton 小号）。[AppSheetTitleRow] 两个壳共用，标题行样式只此一处。
 */
@Composable
internal fun AppSheetTitleRow(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    showClose: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TgFeedTokens.GapLg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = TgFeedTokens.TextHigh,
            modifier = Modifier.weight(1f),
        )
        if (showClose) {
            AppIconButton(
                icon = Icons.Filled.Close,
                contentDescription = "关闭",
                onClick = onClose,
                iconSize = TgFeedTokens.IconMd,
            )
        }
    }
}
