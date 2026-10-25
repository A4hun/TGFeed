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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.feed.FeedViewModelFactory
import dev.tgfeed.core.feed.Playlist
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.ui.components.kit.AppDialog
import dev.tgfeed.ui.components.kit.AppEmptyState
import dev.tgfeed.ui.components.kit.AppIconButton
import dev.tgfeed.ui.components.kit.AppTopBar
import dev.tgfeed.ui.components.kit.adaptiveContentWidth
import dev.tgfeed.ui.theme.TgFeedTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 播放列表管理页：用户的全部片单（LocalStore playlists.json 持久化，创建先后排序）。
 *
 * 入口：功能面板"加入片单"选择器底部的"管理我的片单"
 * （设置页/顶栏胶囊本轮冻结不能加入口，路由与设置/下载页同为容器状态驱动、不在返回栈里）。
 *
 * - 卡片：名称 + "N 条 · 更新于 时间"；点击 = 整单连播（vm.openPlaylist 切一次性源，回 Feed 连播）
 * - 长按卡片：管理对话框（重命名 / 删除）
 * - 顶栏右侧"+"：新建片单（输入名称）
 * - 数据源：vm.playlists（FeedViewModel 由 PlaylistMeta 投影：id/名称/条数/最新更新时间）
 */
@Composable
fun PlaylistsScreen(
    gateway: TdGateway,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val context = LocalContext.current
    // 系统返回键 = 返回上一页（状态驱动路由，不拦会退到桌面；从 Feed 进来即回 Feed）
    BackHandler(onBack = onBack)
    // 与 FeedScreen 共享 activity 作用域的同一个 ViewModel（片单数据同源）
    val vm: FeedViewModel = viewModel(
        factory = FeedViewModelFactory(gateway, app),
        key = "gw" + System.identityHashCode(gateway),
    )
    val playlists by vm.playlists.collectAsState()

    // 新建片单对话框显隐 + 名称草稿
    var showCreate by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    // 长按弹出的管理对话框目标（非空即显示）；重命名对话框复用该目标 + 名称草稿
    var manageTarget by remember { mutableStateOf<Playlist?>(null) }
    var renameTarget by remember { mutableStateOf<Playlist?>(null) }
    var renameName by remember { mutableStateOf("") }

    /** 整单连播：空片单只提示不切源；否则切一次性源并回本页上一页（Feed）连播 */
    fun playAll(p: Playlist) {
        if (p.count == 0) {
            Toast.makeText(context, "片单为空，先在视频里加入内容", Toast.LENGTH_SHORT).show()
            return
        }
        vm.openPlaylist(p.id)
        onBack()
    }

    // 大屏：内容列按 adaptiveContentWidth 三档限宽居中（与设置/下载页同一规格）；整页纯黑沉浸底
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
            // 顶栏：kit AppTopBar（返回圆钮 + 标题，内建 statusBarsPadding）+ 右侧新建圆钮
            AppTopBar(
                title = "播放列表",
                onBack = onBack,
                actions = {
                    AppIconButton(
                        icon = Icons.Filled.Add,
                        contentDescription = "新建片单",
                        onClick = {
                            createName = ""
                            showCreate = true
                        },
                    )
                },
            )

            if (playlists.isEmpty()) {
                // 空态：kit AppEmptyState 统一壳（大图标 + 主文案 + 引导副文案）
                AppEmptyState(
                    icon = Icons.Outlined.PlaylistPlay,
                    title = "还没有播放列表",
                    subtitle = "在视频里长按 → 加入片单",
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = TgFeedTokens.GapLg),
                ) {
                    items(playlists, key = { it.id }) { p ->
                        PlaylistCard(
                            playlist = p,
                            onPlay = { playAll(p) },
                            onLongClick = { manageTarget = p },
                        )
                        Spacer(Modifier.height(TgFeedTokens.GapMd))
                    }
                }
            }
        }
    }

    // 新建片单对话框（kit AppDialog 统一壳；输入框留在内容槽）
    if (showCreate) {
        AppDialog(
            onDismissRequest = { showCreate = false },
            title = "新建片单",
            confirmText = "创建",
            onConfirm = {
                val name = createName.trim()
                // 空名不创建、不关窗（沿用原逻辑）
                if (name.isEmpty()) return@AppDialog
                vm.createPlaylist(name)
                showCreate = false
            },
            dismissText = "取消",
            content = {
                OutlinedTextField(
                    value = createName,
                    onValueChange = { createName = it },
                    placeholder = { Text("片单名称", color = TgFeedTokens.TextLow) },
                    singleLine = true,
                )
            },
        )
    }

    // 长按管理对话框（kit AppDialog 统一壳）：主钮 = 重命名，次钮 = 取消；
    // AppDialog 只有双按钮位，"删除"收进内容区（红色警示文案钮，行为不变：立即删除 + 提示）
    val manage = manageTarget
    if (manage != null) {
        AppDialog(
            onDismissRequest = { manageTarget = null },
            title = manage.name,
            confirmText = "重命名",
            onConfirm = {
                renameName = manage.name
                renameTarget = manage
                manageTarget = null
            },
            dismissText = "取消",
            content = {
                Column {
                    Text(
                        "${manage.count} 条 · 更新于 ${formatPlaylistDate(manage.updatedAt)}",
                        color = TgFeedTokens.TextMedium,
                    )
                    Spacer(Modifier.height(TgFeedTokens.GapLg))
                    Text(
                        "删除片单",
                        style = MaterialTheme.typography.labelLarge,
                        color = TgFeedTokens.WarningRed,
                        modifier = Modifier
                            .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
                            .clickable {
                                vm.deletePlaylist(manage.id)
                                Toast.makeText(context, "已删除「${manage.name}」", Toast.LENGTH_SHORT).show()
                                manageTarget = null
                            }
                            .padding(vertical = TgFeedTokens.GapSm),
                    )
                }
            },
        )
    }

    // 重命名对话框（kit AppDialog 统一壳）
    val rename = renameTarget
    if (rename != null) {
        AppDialog(
            onDismissRequest = { renameTarget = null },
            title = "重命名片单",
            confirmText = "确定",
            onConfirm = {
                val name = renameName.trim()
                // 空名不生效、不关窗（沿用原逻辑）
                if (name.isEmpty()) return@AppDialog
                vm.renamePlaylist(rename.id, name)
                renameTarget = null
            },
            dismissText = "取消",
            content = {
                OutlinedTextField(
                    value = renameName,
                    onValueChange = { renameName = it },
                    singleLine = true,
                )
            },
        )
    }
}

/** 片单卡片：名称 + "N 条 · 更新于 时间"；点击整单连播，长按进管理对话框 */
@OptIn(ExperimentalFoundationApi::class) // combinedClickable：长按删除/重命名的标准手势
@Composable
private fun PlaylistCard(
    playlist: Playlist,
    onPlay: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // 卡片规格对齐 token：SurfaceHigh 底 + RadiusLg 圆角 + GapLg 内边距
            .clip(RoundedCornerShape(TgFeedTokens.RadiusLg))
            .background(TgFeedTokens.SurfaceHigh)
            .combinedClickable(onClick = onPlay, onLongClick = onLongClick)
            .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapLg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                playlist.name,
                style = MaterialTheme.typography.bodyLarge,
                color = TgFeedTokens.TextHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(TgFeedTokens.GapXs))
            Text(
                "${playlist.count} 条 · 更新于 ${formatPlaylistDate(playlist.updatedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = TgFeedTokens.TextMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 整单连播暗示：尾部播放图标（点击语义同卡片本体）
        Icon(
            Icons.Outlined.PlayArrow,
            contentDescription = "整单连播",
            tint = TgFeedTokens.TextMedium,
        )
    }
}

/** 片单最新更新时间格式化；缺省（<=0）显示"未知" */
private fun formatPlaylistDate(ts: Long): String =
    if (ts <= 0) "未知" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
