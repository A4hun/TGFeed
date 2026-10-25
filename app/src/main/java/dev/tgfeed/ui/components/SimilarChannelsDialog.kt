package dev.tgfeed.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tgfeed.core.td.ChannelBrief
import dev.tgfeed.ui.theme.TgFeedTokens

/**
 * 相似频道推荐弹窗：竖排列表展示与目标频道相似的频道。
 *  - loading=true：居中转圈（数据由 FeedViewModel.loadSimilar 拉取中）；
 *  - 空列表：提示"暂无相似频道推荐"（TDLib 未返回或查询失败，均属正常情况）；
 *  - 点击某行 → onOpen（调用方负责切到该频道 Feed 并关闭弹窗）。
 *
 * 纯展示组件：自身不持有 ViewModel，数据/事件全部由参数注入，
 * 与 CollectionsDialog 的 AlertDialog 风格保持一致。
 */
@Composable
fun SimilarChannelsDialog(
    channels: List<ChannelBrief>,
    loading: Boolean,
    onOpen: (ChannelBrief) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("相似频道推荐") },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp),
            ) {
                when {
                    // 弹窗内加载按页面级规格：40dp / 3dp / TextHigh
                    //（默认 primary 色 + 4dp 描边在动画禁用退化态会缩成 4dp 蓝点）
                    loading -> CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(40.dp),
                        color = TgFeedTokens.TextHigh,
                        strokeWidth = 3.dp,
                    )
                    channels.isEmpty() -> Text(
                        "暂无相似频道推荐",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(channels, key = { it.id }) { c ->
                            SimilarChannelRow(channel = c, onClick = { onOpen(c) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** 相似频道条目行：AutoAwesome 小图标 + 频道名（超长省略），整行可点 */
@Composable
private fun SimilarChannelRow(
    channel: ChannelBrief,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            channel.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
