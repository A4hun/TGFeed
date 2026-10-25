package dev.tgfeed.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tgfeed.core.feed.ChannelCollection
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.feed.FeedViewModelFactory
import dev.tgfeed.core.td.ChannelBrief
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.ui.components.kit.AppDialog
import dev.tgfeed.ui.components.kit.AppSheetMotion
import dev.tgfeed.ui.components.kit.SheetAnchor
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 频道合集管理面板（R137 生产侧 AppSheet 化，design-redesign.md §2.1；R178 就近锚定
 * 迁 AppSheetMotion 左抽屉——源面板「换着看」chip 触发点在顶部域）：
 *  - 上半部分列出已有合集（名称 + 频道数 + 频道名摘要），单击 → onSelect（一键刷合集）；
 *    右侧显式「…」小图标 → 礼包分享面板（[PackShareSheet]，不再是唯一入口的长按；
 *    长按保留为快捷方式）；「…」面板里带独立分区的「删除合集」（仍走 AppDialog 二次确认）；
 *  - 智能房间区：单击刷房间，「…」/长按直达礼包分享（房间是本地聚类产物，没有删除语义）；
 *  - 下半部分"新建合集"：输入名称 + 频道多选（数据来自共享 FeedViewModel 的
 *    channels，为空时先 loadChannels），点"创建"调 vm.createCollection。
 *
 * 分享为礼包：把合集/房间的频道集生成 Telegram 原生合集邀请链接（t.me/addlist 形态，
 * 对用户只称"频道包"），附安利文案广播到广场（PlazaService.postChannelPack，服务内
 * 幂等+节奏控制），链接同时复制到剪贴板；生成中/成功/失败全部收在 [PackShareSheet]
 * 面板内（按钮内嵌转圈、结果卡、错误行），不再 AlertDialog 套娃、不再 Toast 承载结果。
 *
 * 视觉：全链路 TgFeedTokens 深色语言（旧版 M3 默认配色直出已收编）。
 *
 * 注意：面板内容继承调用处的 CompositionLocal，viewModel(factory = …) 拿到的
 * 是与 FeedScreen 同一个 Activity 作用域的共享 FeedViewModel 实例。
 */
@OptIn(ExperimentalFoundationApi::class) // combinedClickable（R178 抽屉化后不再需要 M3 实验注解）
@Composable
fun CollectionsDialog(
    gateway: TdGateway,
    onDismiss: () -> Unit,
    onSelect: (ChannelCollection) -> Unit,
) {
    val app = LocalContext.current.applicationContext as android.app.Application
    // 必须带网关 key：与 FeedScreen 共享同一 activity 作用域 VM，
    // 不带 key 会拿到默认 key 的另一个实例——点房间切源切到错误实例上（点了等于没点）
    val vm: FeedViewModel = viewModel(
        factory = FeedViewModelFactory(gateway, app),
        key = "gw" + System.identityHashCode(gateway),
    )
    val collections by vm.collections.collectAsState()
    val channels by vm.channels.collectAsState()

    // 新建合集的本地表单状态
    var newName by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    // 删除合集二次确认（GAPS3：先记下待删合集名，AppDialog 确认后才执行）
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    // 礼包分享面板目标（合集/智能房间共用）+ 是否可删（房间无删除语义）+ 生成状态
    var shareTarget by remember { mutableStateOf<ChannelCollection?>(null) }
    var shareDeletable by remember { mutableStateOf(false) }
    var shareBusy by remember { mutableStateOf(false) }
    var shareError by remember { mutableStateOf<String?>(null) }
    var shareResult by remember { mutableStateOf<PackShareResultUi?>(null) }
    val context = LocalContext.current
    val shareScope = rememberCoroutineScope()

    // 频道真头像加载器（礼包盒盖的头像堆叠用；网关侧带进程内缓存，Mock 回落字母圆）
    val avatarLoader: suspend (Long) -> ByteArray? = remember(gateway) {
        { chatId ->
            when (gateway) {
                is TdLibGateway -> gateway.getChannelAvatar(chatId)
                is dev.tgfeed.core.td.MockTdGateway -> gateway.getChannelAvatar(chatId)
                else -> null
            }
        }
    }

    /** 打开礼包分享面板（合集可删；智能房间是聚类产物无删除语义；每次进入重置生成状态） */
    fun openShare(c: ChannelCollection, deletable: Boolean) {
        shareTarget = c
        shareDeletable = deletable
        shareBusy = false
        shareError = null
        shareResult = null
    }

    /**
     * 执行分享（IO 协程生成邀请链接 → 广场广播 → 剪贴板复制）。
     * 结果全部落面板状态（结果卡/错误行），绝不用 Toast 承载。
     */
    fun doGenerate(c: ChannelCollection, pitch: String) {
        val td = gateway as? TdLibGateway
        if (td == null) {
            shareError = "演示模式不支持礼包分享，请用真实 Telegram 账号登录"
            return
        }
        shareBusy = true
        shareError = null
        shareResult = null
        shareScope.launch(Dispatchers.IO) {
            val link = td.createChannelPackInviteLink(c.name, c.channelIds)
            if (link == null) {
                withContext(Dispatchers.Main) {
                    shareBusy = false
                    shareError = "链接生成失败，稍后再试（也可能是可分享的频道包数量到上限了）"
                }
                return@launch
            }
            // 广场广播（服务内每合集一次 + 全局节奏闸；未开启/未赶上 = false，仅复制链接）
            val posted = vm.plazaService?.postChannelPack(c.name, c.channelIds, link.url, pitch) ?: false
            withContext(Dispatchers.Main) {
                runCatching {
                    val cm = context.getSystemService(
                        android.content.Context.CLIPBOARD_SERVICE,
                    ) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("频道礼包", link.url))
                }
                shareBusy = false
                shareResult = PackShareResultUi(link.url, posted)
            }
        }
    }

    // 频道列表为空时（如刚进页面还没拉过）先拉一次
    LaunchedEffect(channels.isEmpty()) {
        if (channels.isEmpty()) vm.loadChannels()
    }

    // chatId → 频道摘要，用于把合集里的 id 解析成可读标题
    val channelById = remember(channels) { channels.associateBy { it.id } }

    // R178 就近锚定迁移：源面板「换着看」chip 触发点在左上域 → 左抽屉形态
    // （AppSheetMotion；嵌套的 PackShareSheet/AppDialog 独立窗口 z 序天然压过主窗抽屉）
    AppSheetMotion(onDismiss = onDismiss, title = "频道合集", anchor = SheetAnchor.LEFT) { sheet ->
        Column(
            Modifier
                .fillMaxWidth()
                // 横屏可滚动：三段列表+表单合计 ~750dp，横屏窗体被裁（审查实锤）
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TgFeedTokens.GapLg),
        ) {
            // ---------------- 智能房间（算法自动聚类，打开时刷新） ----------------
            val smartRooms by vm.smartRooms.collectAsState()
            LaunchedEffect(Unit) { vm.refreshSmartRooms() }
            if (smartRooms.isNotEmpty()) {
                Text(
                    "智能房间（按观看习惯自动聚类）",
                    style = MaterialTheme.typography.labelMedium,
                    color = TgFeedTokens.TextLow,
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.heightIn(max = 120.dp)) {
                    items(smartRooms, key = { "smart_" + it.name }) { room ->
                        CollectionRow(
                            collection = room,
                            channelById = channelById,
                            onClick = {
                                // 先切源再收抽屉（pick 同款）；关窗动画壳内播完回调 onDismiss
                                vm.showSmartRoom(room)
                                sheet.dismiss()
                            },
                            // 房间是本地聚类产物（无删除语义）：… 与长按都直达礼包分享
                            onMore = { openShare(room, deletable = false) },
                            onLongClick = { openShare(room, deletable = false) },
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = TgFeedTokens.OverlayHairline)
            }

            // ---------------- 已有合集列表 ----------------
            if (collections.isEmpty()) {
                Text(
                    "还没有合集，在下方创建一个吧",
                    color = TgFeedTokens.TextMedium,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 200.dp)) {
                    items(collections, key = { it.name }) { c ->
                        CollectionRow(
                            collection = c,
                            channelById = channelById,
                            // 先切源再收抽屉（关窗动画壳内播完回调 onDismiss——
                            // 调用方 onSelect 不再自己置 false，落组合统一走壳）
                            onClick = {
                                onSelect(c)
                                sheet.dismiss()
                            },
                            // 显式「…」入口（R137）：点开礼包分享面板（长按保留为快捷方式）
                            onMore = { openShare(c, deletable = true) },
                            onLongClick = { openShare(c, deletable = true) },
                        )
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = TgFeedTokens.OverlayHairline)

            // ---------------- 新建合集 ----------------
            Text(
                "新建合集",
                style = MaterialTheme.typography.titleSmall,
                color = TgFeedTokens.TextHigh,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("合集名称，如：舞蹈合集", color = TgFeedTokens.TextLow) },
                singleLine = true,
                shape = RoundedCornerShape(TgFeedTokens.RadiusField),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TgFeedTokens.TextHigh,
                    unfocusedTextColor = TgFeedTokens.TextHigh,
                    cursorColor = TgFeedTokens.TgBlue,
                    focusedBorderColor = TgFeedTokens.TgBlue,
                    unfocusedBorderColor = TgFeedTokens.OverlayHairline,
                    focusedContainerColor = TgFeedTokens.SurfaceHigh,
                    unfocusedContainerColor = TgFeedTokens.SurfaceHigh,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            if (channels.isEmpty()) {
                Text(
                    "频道列表加载中…",
                    color = TgFeedTokens.TextMedium,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 180.dp)) {
                    items(channels, key = { it.id }) { ch ->
                        ChannelCheckRow(
                            channel = ch,
                            checked = ch.id in selectedIds,
                            onToggle = {
                                selectedIds =
                                    if (ch.id in selectedIds) selectedIds - ch.id else selectedIds + ch.id
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = {
                        vm.createCollection(newName, selectedIds.toList())
                        newName = "" // 清空表单，便于连续创建
                        selectedIds = emptySet()
                    },
                    // 名称非空且至少勾选一个频道才可创建
                    enabled = newName.isNotBlank() && selectedIds.isNotEmpty(),
                ) {
                    Text("创建", color = TgFeedTokens.TgBlue)
                }
            }
            Spacer(Modifier.height(TgFeedTokens.GapLg))
        }
    }

    // ---------------- 礼包分享面板（R137：… 或长按打开，一层装下预览+表单+结果） ----------------
    val share = shareTarget
    if (share != null) {
        val members = share.channelIds.map { channelById[it] ?: ChannelBrief(it, "频道 $it", null) }
        PackShareSheet(
            collection = share,
            members = members,
            avatarLoader = avatarLoader,
            busy = shareBusy,
            error = shareError,
            result = shareResult,
            onGenerate = { pitch -> doGenerate(share, pitch) },
            // 智能房间是本地聚类产物，无删除语义：危险区不渲染
            onDelete = if (shareDeletable) {
                {
                    shareTarget = null
                    pendingDelete = share.name
                }
            } else {
                null
            },
            onDismiss = { shareTarget = null },
        )
    }

    // 删除合集二次确认（破坏性操作走 AppDialog；确认后才真正删除）
    val delName = pendingDelete
    if (delName != null) {
        AppDialog(
            onDismissRequest = { pendingDelete = null },
            title = "删除合集？",
            confirmText = "删除",
            onConfirm = {
                vm.deleteCollection(delName)
                pendingDelete = null
            },
            dismissText = "取消",
        ) {
            Text("将删除合集「$delName」，删除后不可恢复（频道本身与频道内容不受影响）。")
        }
    }
}

/**
 * 合集条目行：名称 + "N 个频道" + 频道名摘要；单击选择、尾随「…」或长按进礼包分享面板。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollectionRow(
    collection: ChannelCollection,
    channelById: Map<Long, ChannelBrief>,
    onClick: () -> Unit,
    onMore: () -> Unit,
    onLongClick: () -> Unit,
) {
    // 把 id 解析成频道标题（未加载到的显示原始 id），取前 3 个做摘要
    val titles = collection.channelIds
        .map { channelById[it]?.title ?: "频道 $it" }
    val summary = titles.take(3).joinToString("、") +
        if (titles.size > 3) " 等" else ""
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                collection.name,
                style = MaterialTheme.typography.bodyLarge,
                color = TgFeedTokens.TextHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${collection.channelIds.size} 个频道：$summary",
                color = TgFeedTokens.TextMedium,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 显式「…」入口：礼包分享/删除面板（长按不再是唯一入口）
        IconButton(onClick = onMore) {
            Icon(
                Icons.Outlined.MoreHoriz,
                contentDescription = "分享或删除",
                tint = TgFeedTokens.TextMedium,
                modifier = Modifier.size(TgFeedTokens.IconLg),
            )
        }
    }
}

/** 频道多选行：checkbox + 频道名 */
@Composable
private fun ChannelCheckRow(
    channel: ChannelBrief,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = TgFeedTokens.AccentRed,
                checkmarkColor = TgFeedTokens.TextHigh,
                uncheckedColor = TgFeedTokens.TextLow,
            ),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            channel.title,
            style = MaterialTheme.typography.bodyMedium,
            color = TgFeedTokens.TextHigh,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
