package dev.tgfeed.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tgfeed.core.td.TestFolderSnapshot
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.ui.components.kit.AppDialog
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * R220 测试文件夹清理对话框（设置→诊断通道→「清理测试文件夹」入口弹起）。
 *
 * 背景：频道礼包功能早期测试在用户真实 TG 账号里留下了一批「R130」开头的测试
 * 文件夹（无查重+无测试闸年代的污染，R220 修根+立规）。本对话框是配套的
 * 「可回滚」通道：让用户**亲眼确认**要删什么，确认后才删。
 *
 * 交互与红线：
 *  - 打开即**只读扫描**（UpdateChatFolders + 逐夹 GetChatFolder），列出全部
 *    「R130」开头的文件夹（默认全选，可逐个取消勾选）；
 *  - 删除前**二次确认**——文案钉死红线：只删文件夹分组本身，已加入的频道
 *    **全部保留、不退订任何一个**（DeleteChatFolder 的 leaveChatIds 恒空）；
 *  - 带活跃邀请链接的夹会先撤链接再删夹（TG 拒删带活跃链接的夹）；
 *  - 扫描失败给「重新扫描」而不是误报「干净」（listTestFolders 的 null 语义）；
 *  - 删除完成自动复扫回填列表 + 结果摘要（成功/失败数）。
 */
@Composable
fun TestFolderCleanupDialog(
    gateway: TdLibGateway,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    /** null = 扫描中；非 null = 扫描结果（List 可为空 = 干净；null 元素不可能） */
    var folders by remember { mutableStateOf<List<TestFolderSnapshot>?>(null) }
    /** 扫描失败标记（与「扫描成功但零命中」严格区分，防误报干净） */
    var scanFailed by remember { mutableStateOf(false) }
    /** 勾选待删的 folderId 集（默认全选——测试夹全是该删的） */
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    /** 二次确认态（第一次「删除所选」→ 本确认框 → 真删） */
    var confirming by remember { mutableStateOf(false) }
    /** 删除进行中（按钮全禁、防重入） */
    var busy by remember { mutableStateOf(false) }
    /** 上一轮删除结果摘要（展示在列表上方，复扫后仍可见） */
    var resultSummary by remember { mutableStateOf<String?>(null) }

    /** 只读扫描（IO；结果回主线程状态） */
    fun rescan() {
        folders = null
        scanFailed = false
        scope.launch(Dispatchers.IO) {
            val got = gateway.listTestFolders()
            withContext(Dispatchers.Main) {
                if (got == null) {
                    scanFailed = true
                    folders = emptyList()
                } else {
                    folders = got
                    // 新一批默认全选（测试夹都是清理目标；用户可逐个取消）
                    selected = got.map { it.folderId }.toSet()
                }
            }
        }
    }

    // 打开即扫描
    LaunchedEffect(Unit) { rescan() }

    /** 执行删除（用户已过二次确认）：逐夹删（先撤链接再删夹）→ 复扫回填 */
    fun doDelete() {
        if (busy) return
        val targets = folders.orEmpty().filter { it.folderId in selected }
        if (targets.isEmpty()) {
            confirming = false
            return
        }
        busy = true
        confirming = false
        scope.launch(Dispatchers.IO) {
            var ok = 0
            var fail = 0
            targets.forEach { f ->
                if (gateway.deleteTestFolder(f.folderId)) ok++ else fail++
            }
            val fresh = gateway.listTestFolders()
            withContext(Dispatchers.Main) {
                busy = false
                resultSummary = "已删除 $ok 个" + if (fail > 0) "，失败 $fail 个（可重试）" else "，全部成功"
                if (fresh != null) {
                    folders = fresh
                    selected = fresh.map { it.folderId }.toSet()
                } else {
                    // 复扫失败：留在当前列表（已删的会显示为陈旧，重新打开即校准）
                    scanFailed = true
                }
            }
        }
    }

    val list = folders.orEmpty()
    val hasContent = !scanFailed && list.isNotEmpty()

    AppDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = "清理测试文件夹",
        confirmText = when {
            busy -> null
            scanFailed -> "重新扫描"
            !hasContent -> "知道了"
            else -> "删除所选（${selected.size}）"
        },
        onConfirm = {
            when {
                busy -> {}
                scanFailed -> rescan()
                !hasContent -> onDismiss()
                // 空勾选不进确认（防误触）
                selected.isNotEmpty() -> confirming = true
            }
        },
        dismissText = if (busy) null else "关闭",
        content = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                // 红线钉死文案（每个阶段都可见——这是用户做决定的依据）
                Text(
                    "这些是早期功能测试留下的「R130」开头文件夹。删除只移除文件夹分组，" +
                        "已加入的频道全部保留、不会退订任何一个。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TgFeedTokens.TextMedium,
                )
                resultSummary?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (it.contains("失败")) TgFeedTokens.AccentRed else TgFeedTokens.TgBlue,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                when {
                    busy -> Text(
                        "正在删除…（带链接的文件夹会先撤链接再删除）",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    folders == null -> Text(
                        "正在扫描你的 Telegram 文件夹…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    scanFailed -> Text(
                        "扫描失败：稍后再试（可能是网络或登录未就绪）",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    list.isEmpty() -> Text(
                        "未发现「R130」开头的测试文件夹——账号是干净的",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    else -> {
                        if (list.size > 1) {
                            Text(
                                if (selected.size == list.size) "取消全选" else "全选",
                                color = TgFeedTokens.TgBlue,
                                fontSize = 14.sp,
                                modifier = Modifier
                                    .padding(top = 10.dp)
                                    .clickable {
                                        selected = if (selected.size == list.size) emptySet() else list.map { it.folderId }.toSet()
                                    },
                            )
                        }
                        list.forEach { f ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selected = if (f.folderId in selected) selected - f.folderId else selected + f.folderId }
                                    .padding(vertical = 2.dp),
                            ) {
                                Checkbox(
                                    checked = f.folderId in selected,
                                    onCheckedChange = {
                                        selected = if (f.folderId in selected) selected - f.folderId else selected + f.folderId
                                    },
                                )
                                Column(Modifier.padding(start = 2.dp)) {
                                    Text(
                                        f.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TgFeedTokens.TextHigh,
                                        maxLines = 1,
                                    )
                                    Text(
                                        buildString {
                                            append(
                                                if (f.chatCount >= 0) "频道 ${f.chatCount} 个" else "频道数未知",
                                            )
                                            if (f.inviteLinkCount > 0) append(" · 链接 ${f.inviteLinkCount} 条")
                                            if (f.isShareable) append(" · 可分享合集")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TgFeedTokens.TextMedium,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    )

    // 二次确认（AppDialog 主钮语义=删除）：红线文案在此重申——这是「用户确认」闸门本体
    if (confirming) {
        AppDialog(
            onDismissRequest = { confirming = false },
            title = "确认删除 ${selected.size} 个文件夹？",
            content = {
                Text(
                    "只删除上面勾选的文件夹分组；已加入的频道会全部保留（不会退订）。" +
                        "带邀请链接的文件夹会先撤掉链接。此操作不可撤销。",
                )
            },
            confirmText = "删除",
            onConfirm = { doDelete() },
            dismissText = "再想想",
        )
    }
}
