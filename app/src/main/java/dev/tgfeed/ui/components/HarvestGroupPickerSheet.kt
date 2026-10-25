package dev.tgfeed.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tgfeed.core.feed.HarvestGroupRow
import dev.tgfeed.core.feed.buildHarvestGroupRows
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.ui.components.kit.AppSheet
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * R197 群选择器 sheet（发现页入口 + 设置页跳转行共用**同款**——一处定义两处挂载，
 * 用户裁决「这功能本质是发现模块的」后设置页只留跳转不再有第二份实现）。
 *
 * 数据装配（全链纯读 + 既有写口，零 Search 族调用）：
 *  - 全量已加入群：网关 [TdLibGateway.listHarvestGroupCandidates]（LoadChats 全量
 *    + 成员数，TTL 5min 快照）；
 *  - 建议角标：关键词规则**只作提示不筛列表**（用户裁决「手动选，不要替他筛选」）
 *    = 自动发现缓存（src=name/desc）∪ 群名规则现算（GroupHarvester
 *    .looksLikeSearchGroupByTitle 同套规则零镜像）；
 *  - 勾选/取消：[dev.tgfeed.core.feed.GroupHarvester.setUserGroupSelected]（R183 既有
 *    API 原样复用——勾选生效链路 ≤15min 收割轮零改动）。
 *
 * 交互细节：
 *  - 勾选翻转**就地翻**不重排（扫长列表时行不跳动；「已勾选优先」排序只在开 sheet
 *    装配时生效一次）；
 *  - 「不在群列表」的幽灵勾选项只能取消不能勾回（退了的群勾回去收割只会静默空转）；
 *  - 头像走 rememberChannelAvatarImage 共享件（IO 解码 + 字母圆兜底）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class) // AppSheet（SheetState 默认参，与全 App 调用处同款）
@Composable
internal fun HarvestGroupPickerSheet(
    gateway: TdGateway,
    onDismiss: () -> Unit,
    /** 勾选集发生变化（翻一格就回调一次）——入口行的「N 个群」计数据此刷新 */
    onSelectionChanged: () -> Unit = {},
) {
    val gw = gateway as? TdLibGateway
    val harvester = gw?.groupHarvester
    // null = 装配中；非 null 空表 = 枚举成功但一个群都没有
    var rows by remember { mutableStateOf<List<HarvestGroupRow>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var loadTick by remember { mutableIntStateOf(0) } // 失败重试计数
    val scope = rememberCoroutineScope()
    LaunchedEffect(gateway, loadTick) {
        if (gw == null || harvester == null) return@LaunchedEffect
        loadFailed = false
        rows = null
        // 全量枚举（LoadChats 循环 + 数百 GetChat/GetSupergroup JNI 往返）走 IO；
        // buildHarvestGroupRows 是纯函数，IO 线程装配后一次性回主线程
        val built = withContext(Dispatchers.IO) {
            runCatching {
                val candidates = gw.listHarvestGroupCandidates()
                val selected = harvester.loadUserSelectedGroups()
                val detected = harvester.knownUserSearchGroups()
                    .filter { it.src == "name" || it.src == "desc" } // 关键词规则结论（只作角标）
                buildHarvestGroupRows(candidates, selected, detected, harvester::looksLikeSearchGroupByTitle)
            }.getOrNull()
        }
        if (built == null) loadFailed = true else rows = built
    }

    AppSheet(onDismiss = onDismiss, title = "我的搜索群") {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = TgFeedTokens.GapLg),
        ) {
            Text(
                "列表是你全部已加入的群，「建议」角标只是关键词提示——勾选哪些纳入收割由你决定" +
                    "（只读监控，绝不发消息）：群友搜过的词、被安利的频道会变成发现页候选。",
                style = MaterialTheme.typography.bodySmall,
                color = TgFeedTokens.TextMedium,
            )
            Spacer(Modifier.height(TgFeedTokens.GapSm))
            val current = rows
            when {
                gw == null || harvester == null ->
                    SheetInfoLine("当前网络模式不可用（演示模式无群数据）")
                // 装配中：LoadChats 全量枚举是秒级动作，给 spinner + 文案（不是死态）
                current == null && !loadFailed ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = TgFeedTokens.GapLg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = TgFeedTokens.TextLow,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(TgFeedTokens.GapMd))
                        Text(
                            "正在读取你的群列表…",
                            style = MaterialTheme.typography.bodySmall,
                            color = TgFeedTokens.TextLow,
                        )
                    }
                // 读取失败：重试按钮原地重触发（loadTick 驱动 LaunchedEffect 重跑）
                current == null -> Row(
                    Modifier.fillMaxWidth().padding(vertical = TgFeedTokens.GapSm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "群列表读取失败（网络/登录态），稍后再试",
                        style = MaterialTheme.typography.bodySmall,
                        color = TgFeedTokens.TextLow,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { loadTick++ }) { Text("重试") }
                }
                current.isEmpty() -> SheetInfoLine("还没读到任何群——先在 Telegram 加入资源搜索群，再来这里勾选")
                else -> {
                    val list = current
                    val selectedCount = list.count { it.selected }
                    val suggestedCount = list.count { it.suggested && !it.selected }
                    Text(
                        buildString {
                            append("已勾选 $selectedCount 个群")
                            if (suggestedCount > 0) append(" · 还有 $suggestedCount 个建议待你看")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selectedCount == 0) TgFeedTokens.TextLow else TgFeedTokens.TgBlue,
                    )
                    Spacer(Modifier.height(TgFeedTokens.GapXs))
                    // 列表区限高内滚（GiftPackOpenSheet 同族口径）：几百群也只占 sheet 一屏高
                    LazyColumn(Modifier.heightIn(max = 460.dp)) {
                        items(list, key = { it.chatId }) { row ->
                            HarvestGroupPickerRow(
                                row = row,
                                // 显式 suspend 函数类型：lambda 值不会自动转 suspend 语义
                                avatarLoader = remember(gw) {
                                    val loader: suspend (Long) -> ByteArray? = { id -> gw?.getChannelAvatar(id) }
                                    loader
                                },
                                onToggle = { want ->
                                    // 幽灵勾选项（不在群列表）只许取消不许勾回：退了的群勾回
                                    // 收割侧只会静默空转烧预算
                                    if (row.missingFromList && want) return@HarvestGroupPickerRow
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            runCatching {
                                                harvester?.setUserGroupSelected(
                                                    row.chatId,
                                                    row.title,
                                                    row.suggestedSrc.ifBlank { "user" },
                                                    want,
                                                )
                                            }
                                        }
                                        // 就地翻标志（不重排：扫长列表时行不跳）；下次开 sheet
                                        // 由纯函数重新按「已勾→建议→其余」排序
                                        rows = list.map {
                                            if (it.chatId == row.chatId) it.copy(selected = want) else it
                                        }
                                        onSelectionChanged()
                                    }
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(TgFeedTokens.GapSm))
            Text(
                "勾选即生效（≤15 分钟开始收割）；取消后已收数据留存，再勾上从断点续收。",
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextLow,
            )
            Spacer(Modifier.height(TgFeedTokens.GapSm))
        }
    }
}

@Composable
private fun SheetInfoLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = TgFeedTokens.TextLow,
        modifier = Modifier.padding(vertical = TgFeedTokens.GapMd),
    )
}

/**
 * 选择器行：头像（字母圆兜底）+ 群名 + 「建议」角标 + 成员数小字 + 右侧 Checkbox
 * （整行可点；Checkbox 配色与 CollectionsDialog.ChannelCheckRow 同语言——
 * 控件配色家族全 App 收敛挂账见 r197_notes 同类扫描）。
 */
@Composable
private fun HarvestGroupPickerRow(
    row: HarvestGroupRow,
    avatarLoader: (suspend (Long) -> ByteArray?)?,
    onToggle: (Boolean) -> Unit,
) {
    val enabled = !(row.missingFromList && !row.selected) // 幽灵行只能取消不能勾回（上层已拦，这里降透明度）
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
            .clickable(enabled = enabled) { onToggle(!row.selected) }
            .padding(vertical = TgFeedTokens.GapXs)
            .alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val avatar = rememberChannelAvatarImage(row.chatId, avatarLoader)
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(TgFeedTokens.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (avatar != null) {
                Image(
                    avatar,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    // 字母圆兜底：首字符（与 DiscoverAvatarImage 兜底同口径）
                    row.title.trim().firstOrNull()?.uppercase() ?: "群",
                    style = MaterialTheme.typography.titleMedium,
                    color = TgFeedTokens.TextHigh,
                )
            }
        }
        Spacer(Modifier.width(TgFeedTokens.GapMd))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.title.ifBlank { "群 ${row.chatId}" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = TgFeedTokens.TextHigh,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.suggested) {
                    Spacer(Modifier.width(TgFeedTokens.GapSm))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                            .background(TgFeedTokens.TgBlue.copy(alpha = 0.16f))
                            .padding(horizontal = TgFeedTokens.GapSm, vertical = 2.dp),
                    ) {
                        Text(
                            "建议",
                            color = TgFeedTokens.TgBlue,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                }
                if (row.missingFromList) {
                    Spacer(Modifier.width(TgFeedTokens.GapSm))
                    Text(
                        "不在群列表",
                        color = TgFeedTokens.TextLow,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
            val subtitle = buildString {
                if (row.isChannel) append("频道")
                if (row.memberCount > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("${formatMemberCount(row.memberCount)} 成员")
                }
            }
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TgFeedTokens.TextMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Checkbox(
            checked = row.selected,
            onCheckedChange = { onToggle(it) },
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = TgFeedTokens.AccentRed,
                checkmarkColor = TgFeedTokens.TextHigh,
                uncheckedColor = TgFeedTokens.TextLow,
            ),
        )
    }
}
