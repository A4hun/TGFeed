package dev.tgfeed.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.feed.KeywordMutePolicy
import dev.tgfeed.ui.theme.TgFeedTokens

/**
 * R413 设置页「关键词屏蔽」卡片内容（宿主 SettingsCard 包裹）：
 * 输入框+添加、已屏蔽词 chips（× 删除）、「同时匹配频道名」开关（默认关）、清空。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeywordMuteSettingsContent(vm: FeedViewModel) {
    val context = LocalContext.current
    val words by vm.mutedKeywords.collectAsState()
    val matchChannel by vm.muteMatchChannelTitle.collectAsState()
    KeywordMuteSettingsContent(
        words = words,
        matchChannel = matchChannel,
        onAdd = { input ->
            vm.addMutedKeyword(input) { n ->
                Toast.makeText(context, "已屏蔽 ${n} 条", Toast.LENGTH_SHORT).show()
            }
        },
        onRemove = { vm.removeMutedKeyword(it) },
        onSetMatchChannel = { vm.setMuteMatchChannelTitle(it) },
        onClear = { vm.clearMutedKeywords() },
    )
}

/**
 * R422 无状态版（行为与 VM 版一致，UI 测试直接注入假词表）：
 * [onAdd] 返回错误文案（null=成功，清空输入框）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeywordMuteSettingsContent(
    words: Collection<String>,
    matchChannel: Boolean,
    onAdd: (String) -> String?,
    onRemove: (String) -> Unit,
    onSetMatchChannel: (Boolean) -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    fun submit() {
        val err = onAdd(input)
        if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show() else input = ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
        Text(
            "标题、正文或标签含屏蔽词的内容不再出现在推荐、关注流与搜索中（频道主页、收藏等自己打开的列表不过滤）",
            style = MaterialTheme.typography.bodySmall,
            color = TgFeedTokens.TextMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
            TextField(
                value = input,
                onValueChange = { input = it.take(KeywordMutePolicy.MAX_LEN + 10) },
                placeholder = { Text("输入要屏蔽的关键词", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedTextColor = TgFeedTokens.TextHigh,
                    unfocusedTextColor = TgFeedTokens.TextHigh,
                    cursorColor = TgFeedTokens.TgBlue,
                    focusedContainerColor = Color.White.copy(alpha = 0.14f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.14f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedPlaceholderColor = TgFeedTokens.TextLow,
                    unfocusedPlaceholderColor = TgFeedTokens.TextLow,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.weight(1f).testTag(UiTestTags.KW_MUTE_INPUT),
            )
            FilledTonalButton(
                onClick = { submit() },
                enabled = input.isNotBlank(),
                modifier = Modifier.testTag(UiTestTags.KW_MUTE_ADD),
            ) { Text("添加") }
        }
        if (words.isEmpty()) {
            Text("没有屏蔽任何关键词", style = MaterialTheme.typography.bodyMedium, color = TgFeedTokens.TextLow)
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
            ) {
                words.forEach { w ->
                    Row(
                        Modifier
                            .testTag(UiTestTags.KW_MUTE_CHIP)
                            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                            .background(Color.White.copy(alpha = 0.10f))
                            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(w, style = MaterialTheme.typography.bodyMedium, color = TgFeedTokens.TextHigh, maxLines = 1)
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "删除",
                            tint = TgFeedTokens.TextMedium,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(28.dp)
                                .clip(CircleShape)
                                .testTag(UiTestTags.KW_MUTE_CHIP_DELETE)
                                .clickable { onRemove(w) }
                                .padding(6.dp),
                        )
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
                .clickable { onSetMatchChannel(!matchChannel) }
                .padding(vertical = TgFeedTokens.GapSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = TgFeedTokens.GapMd)) {
                Text("同时匹配频道名", style = MaterialTheme.typography.bodyLarge, color = TgFeedTokens.TextHigh)
                Text(
                    "开启后频道名含屏蔽词的内容也会被屏蔽",
                    style = MaterialTheme.typography.bodySmall,
                    color = TgFeedTokens.TextMedium,
                )
            }
            Switch(checked = matchChannel, onCheckedChange = { onSetMatchChannel(it) }, colors = settingsSwitchColors)
        }
        if (words.isNotEmpty()) {
            TextButton(onClick = {
                onClear()
                Toast.makeText(context, "已清空屏蔽词", Toast.LENGTH_SHORT).show()
            }) { Text("清空全部屏蔽词", color = TgFeedTokens.TextMedium) }
        }
    }
}
