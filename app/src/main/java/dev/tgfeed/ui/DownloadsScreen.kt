package dev.tgfeed.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.feed.FeedViewModelFactory
import dev.tgfeed.core.store.DownloadRecord
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.ui.components.kit.AppDialog
import dev.tgfeed.ui.components.kit.AppEmptyState
import dev.tgfeed.ui.components.kit.AppTopBar
import dev.tgfeed.ui.components.kit.adaptiveContentWidth
import dev.tgfeed.ui.theme.TgFeedTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 下载管理页：已下载到系统相册的记录列表（按媒体类型分目录：视频 Movies / 图片 Pictures / 音频 Music，均挂 TGFeed 子目录）。
 * 从设置页"我的下载"进入（路由由容器层接，与设置页同为状态驱动、不在返回栈里）。
 *
 * - 每行：标题（caption 首行）/ 频道 · 大小 · 日期；点击 = onPlay(item.key) 回 Feed
 *   定位播放该条；右侧删除钮 = 删记录 + 删本地文件（vm.removeDownload）
 * - 多选模式（GAPS3 批量操作）：长按任意条目进入，行首出现勾选框，顶栏变为
 *   "已选 N 条" + 全选/取消全选 + 批量删除；逐条取消勾选至清空、顶栏关闭钮或
 *   系统返回键均可退出；批量删除走二次确认后 vm.removeDownloads 一次清完
 * - 顶栏右侧"全部删除"：先弹二次确认，确认后逐条走 removeDownload（每条独立删文件 + 落盘，量小可接受）
 * - 数据源：vm.downloadRecords（LocalStore downloads.json 持久化，下载完成时间倒序）
 */
@Composable
fun DownloadsScreen(
    gateway: TdGateway,
    onBack: () -> Unit,
    onPlay: (String) -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val context = LocalContext.current
    val vm: FeedViewModel = viewModel(factory = FeedViewModelFactory(gateway, app), key = "gw" + System.identityHashCode(gateway))
    val records by vm.downloadRecords.collectAsState()
    // "全部删除"二次确认弹窗开关（GAPS3：删本地文件 + 记录不可恢复，确认后才执行）
    var showClearAllConfirm by remember { mutableStateOf(false) }
    // "批量删除"二次确认弹窗开关（多选模式下顶栏"删除"触发）
    var showBatchConfirm by remember { mutableStateOf(false) }
    // GAPS3 多选集合：选中的 DownloadRecord.key；非空即处于多选模式（长按条目进入，清空即退出）
    var selection by remember { mutableStateOf(setOf<String>()) }
    val selectMode = selection.isNotEmpty()
    // 系统返回键：多选模式优先退出多选（清空选择），否则返回设置页
    // （与设置页同款状态驱动路由，不拦会退到桌面）
    BackHandler {
        if (selectMode) selection = emptySet() else onBack()
    }

    // 大屏：内容列按 adaptiveContentWidth 三档限宽居中（与设置页同一规格）；整页纯黑沉浸底
    Box(
        Modifier
            .fillMaxSize()
            .background(TgFeedTokens.InkBlack),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                // 自适应内容列宽必须在前：fillMaxSize 会先把约束钉成全宽，其后的限宽是死代码（P1-6 教训）
                .adaptiveContentWidth()
                .fillMaxSize(),
        ) {
            // 顶栏：kit AppTopBar（返回圆钮 + 17sp SemiBold 标题，内建 statusBarsPadding）。
            // 普通模式 = 返回 + "我的下载" + 全部删除（空列表时不给入口）；
            // 多选模式 = 返回钮（语义 = 退出多选）+ "已选 N 条" + 全选/取消全选 + 批量删除
            if (selectMode) {
                AppTopBar(
                    title = "已选 ${selection.size} 条",
                    onBack = { selection = emptySet() },
                    actions = {
                        TopBarTextAction(
                            text = if (selection.size == records.size) "取消全选" else "全选",
                            onClick = {
                                // 已全选时再点 = 取消全选（清空选择即退出多选）；否则一次选满
                                selection =
                                    if (selection.size == records.size) emptySet()
                                    else records.map { it.key }.toSet()
                            },
                        )
                        TopBarTextAction(
                            text = "删除",
                            color = TgFeedTokens.WarningRed,
                            onClick = {
                                // 不直接删：先弹二次确认（弹窗见下方）
                                showBatchConfirm = true
                            },
                        )
                    },
                )
            } else {
                AppTopBar(
                    title = "我的下载",
                    onBack = onBack,
                    actions = {
                        if (records.isNotEmpty()) {
                            TopBarTextAction(
                                text = "全部删除",
                                color = TgFeedTokens.WarningRed,
                                onClick = {
                                    // 不直接删：先弹二次确认（弹窗见下方）
                                    showClearAllConfirm = true
                                },
                            )
                        }
                    },
                )
            }

            if (records.isEmpty()) {
                // 空态：kit AppEmptyState 统一壳（大图标 + 主文案，居中占满剩余高度）
                AppEmptyState(
                    icon = Icons.Outlined.FileDownload,
                    title = "还没有下载记录",
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(records, key = { it.key }) { rec ->
                        DownloadRow(
                            rec = rec,
                            selectMode = selectMode,
                            selected = rec.key in selection,
                            onToggle = {
                                // 长按/多选下点击 = 切换勾选；取消最后一项勾选即自然退出多选
                                selection =
                                    if (rec.key in selection) selection - rec.key
                                    else selection + rec.key
                            },
                            onPlay = { onPlay(rec.key) },
                            onRemove = { vm.removeDownload(rec.key) },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }

        // "全部删除"二次确认弹窗（kit AppDialog 统一壳）：确认后逐条走 removeDownload（删文件 + 删记录 + 清 Done 态）
        if (showClearAllConfirm) {
            AppDialog(
                onDismissRequest = { showClearAllConfirm = false },
                title = "删除全部下载？",
                confirmText = "全部删除",
                onConfirm = {
                    showClearAllConfirm = false
                    // 逐条删：复用单条链路（每条独立删文件 + 落盘，量小可接受）
                    records.forEach { vm.removeDownload(it.key) }
                    Toast.makeText(context, "已删除全部下载", Toast.LENGTH_SHORT).show()
                },
                dismissText = "取消",
                content = {
                    Text(
                        "将删除全部 ${records.size} 条下载记录及已保存到相册的本地文件，删除后不可恢复。",
                        color = TgFeedTokens.TextMedium,
                    )
                },
            )
        }

        // "批量删除"二次确认弹窗（kit AppDialog 统一壳）：确认后走 vm.removeDownloads 一次清完并退出多选
        if (showBatchConfirm) {
            AppDialog(
                onDismissRequest = { showBatchConfirm = false },
                title = "删除所选下载？",
                confirmText = "删除",
                onConfirm = {
                    showBatchConfirm = false
                    val n = selection.size
                    // 批量链路：一次内存过滤 + 逐条删文件落盘（详见 removeDownloads 注释）
                    vm.removeDownloads(selection)
                    selection = emptySet() // 删完退出多选
                    Toast.makeText(context, "已删除 $n 条下载", Toast.LENGTH_SHORT).show()
                },
                dismissText = "取消",
                content = {
                    Text(
                        "将删除所选 ${selection.size} 条下载记录及已保存到相册的本地文件，删除后不可恢复。",
                        color = TgFeedTokens.TextMedium,
                    )
                },
            )
        }
    }
}

/**
 * 顶栏文字动作：labelLarge + RadiusSm 裁剪的圆角点击热区
 * （与观看历史页顶栏"清空"同一规格；替换裸 TextButton，去掉默认最小高度带来的顶栏错位）。
 */
@Composable
private fun TopBarTextAction(
    text: String,
    onClick: () -> Unit,
    color: Color = TgFeedTokens.TextMedium,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
            .clickable(onClick = onClick)
            .padding(horizontal = TgFeedTokens.GapMd, vertical = TgFeedTokens.GapSm),
    )
}

/**
 * 单行下载记录：标题 + "频道 · 大小 · 日期" 副行。
 * 普通模式：点击播放，右侧删除钮；多选模式：点击/长按切换勾选，行首出现勾选框，右侧删除钮隐藏。
 * 退出多选的路径：逐条取消勾选至清空 / 顶栏关闭钮 / 系统返回键
 * （列表行整行都是点击热区，"点空白退出"由这三者共同覆盖）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadRow(
    rec: DownloadRecord,
    selectMode: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                // 长按任意条目进入多选（并选中它）；多选下普通点击只做勾选切换，不触发播放
                onClick = { if (selectMode) onToggle() else onPlay() },
                onLongClick = onToggle,
            )
            // 内边距对齐 token：普通态行首 GapLg，多选态给勾选框让位只留 GapXs
            .padding(
                start = if (selectMode) TgFeedTokens.GapXs else TgFeedTokens.GapLg,
                end = TgFeedTokens.GapSm,
                top = TgFeedTokens.GapMd,
                bottom = TgFeedTokens.GapMd,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectMode) {
            // 勾选框仅作选中态展示与点击热区，勾选逻辑统一走 onToggle（与整行点击同口径）
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
        }
        Column(Modifier.weight(1f)) {
            Text(
                rec.title.ifBlank { rec.channelTitle },
                style = MaterialTheme.typography.bodyLarge,
                color = TgFeedTokens.TextHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${rec.channelTitle} · ${formatSize(rec.sizeBytes)} · ${formatDate(rec.ts)}",
                style = MaterialTheme.typography.bodySmall,
                color = TgFeedTokens.TextMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!selectMode) {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = TgFeedTokens.TextMedium,
                )
            }
        }
    }
}

/** 字节数人性化：B/KB/MB/GB 千进一档；未知（<=0）显式说明 */
private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "未知大小"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var u = 0
    while (v >= 1024.0 && u < units.lastIndex) {
        v /= 1024.0
        u++
    }
    return if (u == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", v, units[u])
}

/** 下载完成时刻格式化；缺省（<=0）显示空串 */
private fun formatDate(ts: Long): String =
    if (ts <= 0) "" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
