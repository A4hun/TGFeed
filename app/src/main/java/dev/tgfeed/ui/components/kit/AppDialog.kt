package dev.tgfeed.ui.components.kit

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.tgfeed.ui.theme.TgFeedTokens

/**
 * AlertDialog 统一壳：全 App 居中对话框的唯一入口。
 *
 * 什么时候用它：
 * - 需要强打断、必须明确选择的确认（删除、退出登录、放弃编辑）；
 * - 少量信息的模态展示（版本说明、错误详情）。
 *
 * 什么时候不用：
 * - 内容多、需要滚动或复杂交互，用 [AppSheet]（底部面板）；
 * - 轻提示（"已收藏"）用 Toast/Snackbar，不要弹窗打断。
 *
 * 视觉规格（统一）：
 * - 底 [TgFeedTokens.Surface]、圆角 [TgFeedTokens.RadiusLg]；
 * - 标题 17sp SemiBold [TgFeedTokens.TextHigh]，内容默认 TextMedium；
 * - 按钮行右下：主钮 AccentRed 文字钮（确定/删除），次钮 TextMedium 文字钮（取消）。
 *   主钮永远放右侧、次钮在左，不要在调用处调换。
 *
 * @param onDismissRequest 请求关闭（点遮罩、系统返回）；确认型对话框通常把"取消"
 *                         和遮罩关闭都接到同一个"放弃"语义上。
 * @param modifier 外部修饰符。
 * @param title 可选标题；null 则不渲染标题位。
 * @param confirmText 主按钮文案；null 则没有主按钮（纯展示框，记得给次钮）。
 * @param onConfirm 主按钮回调；null 时点击主按钮等价于关闭。
 * @param dismissText 次按钮文案（通常"取消"）；null 则不渲染次按钮。
 * @param onDismissClick 次按钮回调；null 时点击次按钮走 [onDismissRequest]。
 * @param content 内容槽，渲染在标题与按钮之间（一段 Text、一组选项、输入框均可）。
 */
@Composable
internal fun AppDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    confirmText: String? = null,
    onConfirm: (() -> Unit)? = null,
    dismissText: String? = null,
    onDismissClick: (() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        confirmButton = {
            if (confirmText != null) {
                TextButton(onClick = onConfirm ?: onDismissRequest) {
                    Text(
                        text = confirmText,
                        color = TgFeedTokens.AccentRed,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                Spacer(Modifier)
            }
        },
        dismissButton = if (dismissText != null) {
            {
                TextButton(onClick = onDismissClick ?: onDismissRequest) {
                    Text(text = dismissText, color = TgFeedTokens.TextMedium)
                }
            }
        } else null,
        title = if (title != null) {
            {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TgFeedTokens.TextHigh,
                )
            }
        } else null,
        text = content,
        containerColor = TgFeedTokens.Surface,
        shape = RoundedCornerShape(TgFeedTokens.RadiusLg),
        titleContentColor = TgFeedTokens.TextHigh,
        textContentColor = TgFeedTokens.TextMedium,
    )
}
