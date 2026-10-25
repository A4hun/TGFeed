package dev.tgfeed.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tgfeed.BuildConfig
import dev.tgfeed.core.feed.FeedFilter
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.feed.FeedViewModelFactory
import dev.tgfeed.core.feed.PlazaBoardEntry
import dev.tgfeed.core.plaza.PlazaPack
import dev.tgfeed.core.plaza.PlazaService
import dev.tgfeed.core.prefs.AppPrefs
import dev.tgfeed.core.td.ChannelPackJoin
import dev.tgfeed.core.td.MockTdGateway
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.ui.components.GiftPackOpenSheet
import dev.tgfeed.ui.components.PackClaimedUi
import dev.tgfeed.ui.components.PackPeekUi
import dev.tgfeed.ui.components.PlazaGiftPackCard
import dev.tgfeed.ui.components.kit.AppEmptyState
import dev.tgfeed.ui.components.kit.AppIconButton
import dev.tgfeed.ui.components.kit.AppLoading
import dev.tgfeed.ui.components.kit.AppTopBar
import dev.tgfeed.ui.components.kit.LocalBottomBarInset
import dev.tgfeed.ui.components.kit.adaptiveContentWidth
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 热榜卡片圆角：16dp（与发现页/设置页卡片同一语言） */
private val PlazaBoardCardRadius = 16.dp

/**
 * 全站热榜页（P1-d 广场新形态）：PlazaService.plazaTrending() 的 key 列表解析成
 * 内容卡（封面 + 标题 + 热度分 + N 在看 / N 赞），点击进 Feed 定位播放
 * （卡片点击经 onPlay 回调由 MainActivity 走「锚定切源 + 轮询 + requestFeedJump」链路）。
 *
 * R232 层级重排：本件从发现页平级双 tab 降级为**发现页入口行点开的
 * [PlazaBoardSheet] 内容**（用户裁决「热榜是二等公民」）——直接宿主是 sheet，
 * 返回/关闭由 sheet 承担；发现页宿主只接 [onPlay]/[onLeave]（离场先关 sheet）。
 *
 * R130 起新增「频道礼包」区（PlazaSnapshot.channelPacks 协议读侧聚合）：
 * R137 礼物盒化（design-redesign.md §2.1 消费侧）——礼包卡升级为「礼物盒物种」：
 * 盒盖封面（频道头像堆叠 + 频道数徽标）+ 礼包名/安利文案 +「N 人分享过」+
 * 主按钮「拆开看看」；拆开 = AppSheet 先看成员再「全部收下」（免加入解析同一次
 * 复用），收下成功卡片翻面成「已收下 ✓」并给「去刷刷看」直达；失败态一律
 * 面板内错误行 + 重试，不再 Toast 裸抛。收下走 TDLib AddChatFolderByInviteLink
 * 免离开整包加入成自己的 TG 合集，频道随后自然进本 App 的频道枚举与 Feed——
 * 自带增长引擎的收口位。
 *
 * - 顶排：右对齐刷新钮（强制重拉广场快照再解析）；标题/返回由宿主承担（R232 起
 *   宿主=[PlazaBoardSheet]：发现页入口行点开，返回/拖拽收起=关闭 sheet）；
 * - 数据：与 FeedScreen 共享 activity 作用域的同一个 FeedViewModel（同 key 约定），
 *   vm.refreshPlazaBoard() 把 trending key 按频道分组拉页反查成内容卡
 *  （R232 慢链修：锚定单发+并发 5 路+两段发布——每频道恰 1 次 GetChatHistory
 *   且零评论级联（R229 追更 bootstrap 同款 cascadeBudget=0 先例），反查墙钟从
 *   串行 10 轮压到≈1 批；封面用消息自带 minithumbnail，零额外下载）；
 * - 空态分级：演示模式（Mock 无广场）→ 演示说明；未开启广场 → **就地开
 *   PlazaSheet 管理面板**（R209 路由收敛：原「去 我的→TG广场 开启」两跳迷路
 *   撤销——开启动作跟热榜的家在同一层，一步直达；面板逻辑与设置页"广场"卡
 *   同一套 prefs 唯一事实源）；已开启无数据 → "热榜还是空的"（广场还没攒够
 *   在看/点赞信号）；
 * - 热度口径：赞×3 + 在看×1（PlazaService.plazaTrending 同一加权，不另起口径）。
 * - R209 刷新触发补位：解析 effect 键从 Unit 改 plazaOn——就地开面板把广场
 *   从关到开后，收起面板的重组带着新开关态重触发一次 refreshPlazaBoard
 *  （面板内开启成功 → 关面板 → 榜单自动开拉，不需要重开热榜 sheet）。
 */
@Composable
fun PlazaBoardContent(
    gateway: TdGateway,
    prefs: AppPrefs,
    onPlay: (PlazaBoardEntry) -> Unit,
    // R232 离场钩子：板内动作把宿主路由带离发现页时（礼物盒「去刷刷看」）先回调
    // 关闭宿主 sheet——sheet 是 Dialog 独立窗口，宿主页离场后开着会盖屏/吃返回。
    // 默认空实现，调用方不接线也可编译
    onLeave: () -> Unit = {},
) {
    // R232 宿主=发现页入口行点开的 PlazaBoardSheet；返回/关闭由 sheet 承担
    val app = LocalContext.current.applicationContext as android.app.Application
    // 与 FeedScreen/DiscoverScreen 共享 activity 作用域的同一个 ViewModel（同 key 约定）
    val vm: FeedViewModel = viewModel(
        factory = FeedViewModelFactory(gateway, app),
        key = "gw" + System.identityHashCode(gateway),
    )
    val entries by vm.plazaBoard.collectAsState()
    val loading by vm.plazaBoardLoading.collectAsState()
    val demoMode = gateway is MockTdGateway
    val plazaOn = prefs.plazaEnabled // prefs 非流式：与 MineScreen 同款读取口径（进页重组时刷新）
    val scope = rememberCoroutineScope()

    // 频道礼包（R130）：随 refreshPlazaBoard / 60s 轮询更新的同一 PlazaSnapshot 读侧聚合
    val plazaSnap by vm.plaza.collectAsState()
    val packs = plazaSnap?.channelPacks ?: emptyList()

    // ---------------- R137 礼物盒化：预览缓存 / 已收下记忆 / 拆开面板状态 ----------------
    // url → 免加入解析结果（封面徽标/头像堆叠与拆开面板共用同一次解析）
    val packPeeks = remember { mutableStateMapOf<String, PackPeekUi>() }
    // url → 已收下结果（会话级：翻面/重进面板复用；重进页面后链接侧幂等兜底）
    val claimedPacks = remember { mutableStateMapOf<String, PackClaimedUi>() }
    // 免加入解析去重闸（非状态：只在协程内读写，重组安全）
    val peekDone = remember { mutableSetOf<String>() }
    var openPack by remember { mutableStateOf<PlazaPack?>(null) }
    var joining by remember { mutableStateOf(false) }
    var joinResult by remember { mutableStateOf<ChannelPackJoin?>(null) }
    // R209 广场管理面板开关（未开启广场的空态「开启 TG 广场」就地打开——
    // 面板从 MineScreen 整体迁入，逻辑零改动：prefs 唯一事实源）
    var showPlazaSheet by remember { mutableStateOf(false) }

    // 频道真头像加载器（拆开面板/封面头像堆叠共用；网关侧带进程内缓存，Mock 回落字母圆）
    val avatarLoader: suspend (Long) -> ByteArray? = remember(gateway) {
        { chatId ->
            when (gateway) {
                is TdLibGateway -> gateway.getChannelAvatar(chatId)
                is MockTdGateway -> gateway.getChannelAvatar(chatId)
                else -> null
            }
        }
    }

    /** 免加入解析一次（首见/重试共用）：IO 协程，结果落 packPeeks */
    fun fetchPeek(url: String) {
        val td = gateway as? TdLibGateway ?: return
        scope.launch {
            packPeeks[url] = PackPeekUi.Loading
            val pv = withContext(Dispatchers.IO) { td.peekChannelPack(url) }
            packPeeks[url] = if (pv != null) PackPeekUi.Ready(pv) else PackPeekUi.Failed
        }
    }

    /** 整包收下：结果写面板状态 + 已收下记忆（卡片翻面）；失败留在面板错误行 */
    fun doJoin(pack: PlazaPack) {
        val td = gateway as? TdLibGateway
        if (td == null) {
            joinResult = ChannelPackJoin.Failed("演示模式不支持收礼包，请用真实 Telegram 账号登录")
            return
        }
        joining = true
        joinResult = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { td.joinChannelPackInvite(pack.url) }
            joining = false
            joinResult = r
            if (r is ChannelPackJoin.Joined) {
                claimedPacks[pack.url] = PackClaimedUi(r.folderName, r.chatCount)
            }
        }
    }

    // 进页解析一次（VM 内部防重入 + 快照兜底拉取）。
    // R209 键改 plazaOn（原 Unit）：就地开管理面板把广场从关到开后，收起面板的
    // 重组带着新开关态重触发一次 refresh（开启 → 关面板 → 榜单自动开拉）；
    // 进 tab 首次照常拉一次，同值重组不重跑（键未变）
    LaunchedEffect(plazaOn) {
        if (plazaOn && !demoMode) vm.refreshPlazaBoard()
    }

    // 礼包首见即预解析：封面的频道数/头像堆叠要用（CheckChatFolderInviteLink 只读，免加入）
    LaunchedEffect(packs, gateway) {
        for (p in packs) {
            if (peekDone.add(p.url)) fetchPeek(p.url)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 顶排：右对齐刷新钮（标题由发现页 tab 行承担；广场关/演示模式不显示）
            if (plazaOn && !demoMode) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = TgFeedTokens.GapSm),
                    horizontalArrangement = Arrangement.End,
                ) {
                    AppIconButton(
                        icon = Icons.Outlined.Refresh,
                        contentDescription = "刷新热榜",
                        onClick = { vm.refreshPlazaBoard(forceFetch = true) },
                    )
                }
            }
            when {
                // 演示模式：Mock 网关无广场服务
                demoMode -> AppEmptyState(
                    icon = Icons.Outlined.Public,
                    title = "演示模式无广场",
                    subtitle = "用真实 TG 账号登录后，开启 TG 广场即可看到全站热榜",
                    modifier = Modifier.weight(1f),
                )
                // 未开启广场：就地开管理面板（R209 路由收敛——原「跳我的页找
                // TG 广场」两跳迷路撤销；面板与设置页广场卡同一套 prefs 事实源）
                !plazaOn -> AppEmptyState(
                    icon = Icons.Outlined.Public,
                    title = "广场未开启",
                    subtitle = "开启 TG 广场后，这里会聚合全站用户的「在看 / 点赞」热度",
                    actionText = "开启 TG 广场",
                    onAction = { showPlazaSheet = true },
                    modifier = Modifier.weight(1f),
                )
                // 首次解析（还没有任何条目/礼包）→ 整页转圈
                loading && entries.isEmpty() && packs.isEmpty() -> AppLoading(
                    text = "正在拉取全站热榜…",
                    modifier = Modifier.weight(1f),
                )
                // 已开启但窗口内无信号也无礼包
                entries.isEmpty() && packs.isEmpty() -> AppEmptyState(
                    icon = Icons.Outlined.Whatshot,
                    title = "热榜还是空的",
                    subtitle = "广场最近还没有攒够「在看 / 点赞」信号；去 Feed 多刷刷，或稍后再来",
                    modifier = Modifier.weight(1f),
                )
                // 频道礼包区 + 热榜内容卡流（任一非空即出列表）
                else -> LazyColumn(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
                    contentPadding = PaddingValues(
                        top = TgFeedTokens.GapMd,
                        bottom = TgFeedTokens.GapLg,
                    ),
                ) {
                    // —— 频道礼包区（R130：有礼包才出现；无礼包时视觉与旧版零差别）——
                    if (packs.isNotEmpty()) {
                        item(key = "packs_header") {
                            Text(
                                "频道礼包",
                                color = TgFeedTokens.TextLow,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        itemsIndexed(packs, key = { _, p -> "pack_" + p.url }) { _, pack ->
                            PlazaGiftPackCard(
                                pack = pack,
                                peek = packPeeks[pack.url] ?: PackPeekUi.Loading,
                                claimed = claimedPacks[pack.url],
                                avatarLoader = avatarLoader,
                                onOpen = {
                                    openPack = pack
                                    // 重进面板：已收下过的礼包直接呈现结果卡（含去刷刷看）
                                    joinResult = claimedPacks[pack.url]?.let {
                                        ChannelPackJoin.Joined(it.folderName, it.chatCount)
                                    }
                                },
                            )
                        }
                        // 礼包与热榜并存时加分节标题，视觉分区
                        if (entries.isNotEmpty()) {
                            item(key = "trending_header") {
                                Text(
                                    "热榜",
                                    color = TgFeedTokens.TextLow,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    }
                    itemsIndexed(entries, key = { _, e -> e.key }) { index, entry ->
                        PlazaBoardCard(
                            rank = index + 1,
                            entry = entry,
                            onClick = { onPlay(entry) },
                        )
                    }
                    // 底部让开系统导航条 + 底栏让位（R173 LocalBottomBarInset，热榜在一级页）
                    item {
                        Spacer(
                            Modifier
                                .navigationBarsPadding()
                                .padding(bottom = dev.tgfeed.ui.components.kit.LocalBottomBarInset.current),
                        )
                    }
                }
            }
        }

        // 拆开面板（AppSheet）：先看成员再收；成功/失败状态都在面板内闭环
        val opening = openPack
        if (opening != null) {
            GiftPackOpenSheet(
                pack = opening,
                peek = packPeeks[opening.url] ?: PackPeekUi.Loading,
                joining = joining,
                result = joinResult,
                avatarLoader = avatarLoader,
                onJoin = { doJoin(opening) },
                onRetryPeek = { fetchPeek(opening.url) },
                onGoBrowse = {
                    // 去刷刷看（直达）：复位类型 tab/筛选（防本地过滤吃掉新频道）→
                    // 聚合源 → 先关宿主热榜 sheet（R232：Dialog 窗口不随路由自动走，
                    // 离场前必须显式关）→ 剥掉发现页/我的直接亮 Feed——容器路由经
                    // LocalContext 直取；返回键先回发现页（discoverReturnPending）
                    vm.resetMediaTypeTab(-1)
                    vm.setFilter(FeedFilter())
                    vm.showAggregate()
                    openPack = null
                    onLeave()
                    val c = (app as? dev.tgfeed.App)?.container
                    c?.closeDiscover()
                    c?.closeMine()
                    // closeDiscover 会清 discoverReturnPending，必须在它之后再置位
                    if (c != null) c.discoverReturnPending.value = true
                    c?.showFeed()
                },
                onDismiss = { openPack = null },
            )
        }

        // R209 广场管理面板（从 MineScreen 整体迁入——未开启广场空态「开启 TG 广场」
        // 就地打开；ModalBottomSheet 独立窗口渲染，指针事件不进主窗口，与热榜 sheet
        // 的手势天然隔离——R177 C4 弹层隔离口径同款）
        if (showPlazaSheet) {
            PlazaSheet(
                gateway = gateway,
                prefs = prefs,
                onDismiss = { showPlazaSheet = false },
            )
        }
    }
}

// ================= R232 热榜 sheet 宿主（发现页入口行点开——热榜降级为二等公民） =================

/**
 * 全站热榜 sheet：发现页「全站热榜」入口行点开（R232 层级重排——用户裁决
 * 「热榜和发现不该同级」：撤 R209 平级双 tab，热榜=发现页内容区入口+sheet）。
 *
 * - 形态：M3 ModalBottomSheet（[PlazaSheet] 管理面板同语言：skipPartiallyExpanded +
 *   sheetMaxWidth + Surface 底 + 顶圆角）+ 内容 0.85 屏高（余 15% 透出发现页——
 *   「sheet 盖在发现页上」的降级语义可感）；
 * - [LocalBottomBarInset] 就地归零：sheet 是 Dialog 独立窗口，[PlazaBoardContent]
 *   板内底衬 spacer 的底栏让位（发现页 dock 用的）不需要——只留系统导航条让位；
 * - 手势/返回隔离：Dialog 独立窗口天然隔离（R177 C4 口径）——sheet 开着时主
 *   窗口（顶层 pager 横滑）收不到事件，不存在「sheet 开着还能滑页」；
 * - 程序化离场都先收 sheet：onPlay 播放（宿主 DiscoverScreen 包装先关）+
 *   onLeave（板内礼物盒「去刷刷看」跳 Feed，[PlazaBoardContent] 回调）；返回键/
 *   scrim 下拖 = 用户自关。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlazaBoardSheet(
    gateway: TdGateway,
    prefs: AppPrefs,
    onPlay: (PlazaBoardEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // 关窗：先播收起动画，动画结束后再真正移除（与 PlazaSheet/SourcePickerSheet 同一手法）
    val dismiss: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }
    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = sheetState,
        // R171 弹层统一限宽（Responsive.SheetMaxWidth，PlazaSheet 同规格）
        sheetMaxWidth = SheetMaxWidth,
        containerColor = TgFeedTokens.Surface,
        shape = RoundedCornerShape(
            topStart = TgFeedTokens.RadiusLg,
            topEnd = TgFeedTokens.RadiusLg,
        ),
    ) {
        CompositionLocalProvider(LocalBottomBarInset provides 0.dp) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f),
            ) {
                PlazaBoardContent(
                    gateway = gateway,
                    prefs = prefs,
                    onPlay = onPlay,
                    onLeave = dismiss,
                )
            }
        }
    }
}

/**
 * 热榜内容卡：名次 + 封面（96×54，minithumbnail 模糊占位，无封面回落 Whatshot 图标块）
 * + 标题/频道名 + 热度分 · N 在看 · N 赞。整卡可点 → onPlay 进 Feed 定位播放。
 */
@Composable
private fun PlazaBoardCard(
    rank: Int,
    entry: PlazaBoardEntry,
    onClick: () -> Unit,
) {
    // 封面解码随字节记忆，防重组重复解码
    val cover = remember(entry.coverBytes) {
        entry.coverBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PlazaBoardCardRadius))
            .background(TgFeedTokens.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(TgFeedTokens.GapMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
    ) {
        // 名次（前三甲用抖音红提亮，其余三级灰）
        Text(
            "$rank",
            color = if (rank <= 3) TgFeedTokens.AccentRed else TgFeedTokens.TextLow,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(24.dp),
        )
        // 封面：消息自带 minithumbnail（零额外下载）；无封面回落图标块
        Box(
            Modifier
                .size(width = 96.dp, height = 54.dp)
                .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
                .background(TgFeedTokens.Surface),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = entry.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Outlined.Whatshot,
                    contentDescription = null,
                    tint = TgFeedTokens.TextLow,
                    modifier = Modifier.size(TgFeedTokens.IconLg),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                color = TgFeedTokens.TextHigh,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.channelTitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    entry.channelTitle,
                    color = TgFeedTokens.TextLow,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(TgFeedTokens.GapXs))
            // 热度分 · N 在看 · N 赞（口径：赞×3 + 在看×1）
            Row(horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd)) {
                Text(
                    "热度 ${entry.heat}",
                    color = TgFeedTokens.AccentRed,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    "${entry.watching} 在看",
                    color = TgFeedTokens.SuccessGreen,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    "${entry.likes} 赞",
                    color = TgFeedTokens.StarYellow,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

// ================= R209 广场管理面板（从 MineScreen 整体迁入，逻辑零改动） =================
// 迁移动机（用户实锤②）：热榜空态的「开启广场」引导原跳我的页找 TG 广场行（两跳
// +语义错位——管理面板跟热榜的家分居两页）；面板归位热榜 tab 空态就地开后，
// MineScreen 的广场管理入口同步改跳转（openDiscoverPlaza 统一唯一入口）。
/**
 * 广场管理面板：深色 #121212、顶圆角 18dp、跳过半展开。
 * 内容与设置页"广场"卡同一套：总开关（开启走 PlazaService.ensurePlaza 找/建/加入）、
 * 隐私说明、频道用户名输入（即时保存）、"我是广场创建者"（广场建好后隐藏）、加入中提示。
 * R361 开源收口（R369 官方名换轨沿用）：release 构建下频道名输入框隐藏（只读行
 * 「官方广场 @tgfeed_stl」——注释引述名随 R369 换轨，实际渲染走 prefs 常量非
 * 硬编码）、「我是广场创建者」开关整隐（创建=作者 debug 构建专属）；备用广场输入框
 * 两形态都保留（主广场被炸时的逃生口，只找不建语义不变）——与设置页广场卡同一
 * prefs 事实源同步闸。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlazaSheet(
    gateway: TdGateway,
    prefs: AppPrefs,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current

    // 关窗：先播收起动画，动画结束后再真正移除面板（与 SourcePickerSheet 同一手法）
    val dismiss: () -> Unit = {
        keyboard?.hide()
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    // 广场状态镜像 prefs；plazaBusy 防"加入中"重复点击
    var plazaEnabled by remember { mutableStateOf(prefs.plazaEnabled) }
    var plazaChannel by remember { mutableStateOf(prefs.plazaChannel) }
    var plazaChannelBackup by remember { mutableStateOf(prefs.plazaChannelBackup) } // 备用广场，可空
    var plazaOwner by remember { mutableStateOf(prefs.plazaOwner) }
    var plazaBusy by remember { mutableStateOf(false) }
    // 广场服务：仅真实 TDLib 网关可用（Mock 演示模式无广场）；跟随 gateway 实例重建
    val plazaService = remember(gateway) {
        (gateway as? TdLibGateway)?.let { PlazaService(it, prefs) }
    }

    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = sheetState,
        // R171 弹层统一限宽（Responsive.SheetMaxWidth，平板巨屏铺满整宽没法看）：
        // 旧状态吃 material3 默认 640dp，与全 App 手工面板 560 规格不一致，此归一
        sheetMaxWidth = SheetMaxWidth,
        containerColor = TgFeedTokens.Surface,
        shape = RoundedCornerShape(
            topStart = TgFeedTokens.RadiusLg,
            topEnd = TgFeedTokens.RadiusLg,
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = TgFeedTokens.GapXl)
                // R226 sheet 族漏网补齐（88fcb74 同款钥匙，与 ActionSheet 同构）：
                // 广场管理面板从热榜空态就地开——发现页 dock 可见域，内容消费
                // inset 抬到栏上沿 + 手势条护栏（此前连 navigationBarsPadding 都缺）
                .navigationBarsPadding()
                .padding(bottom = LocalBottomBarInset.current + 16.dp),
            verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
        ) {
            Text(
                "TG 广场",
                color = TgFeedTokens.TextHigh,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "去中心化全站动态：一个公开 TG 超级群组 = 广场，开启后「在看 / 点赞 / 弹幕」以本人 TG 账号写入，其他用户读同一个群即看到全站动态（零服务器）",
                style = MaterialTheme.typography.bodySmall,
                color = TgFeedTokens.TextMedium,
            )
            if (plazaService == null) {
                Text(
                    "演示模式（Mock）下不可用——用真实 TG 账号登录后可开启",
                    style = MaterialTheme.typography.bodySmall,
                    color = TgFeedTokens.TextMedium,
                )
            } else {
                PlazaSwitchRow(
                    title = "TG 广场（全站动态互通）",
                    subtitle = "开启后你的「在看 / 点赞 / 弹幕」会以你的 TG 账号公开发送到广场频道，其他用户可见",
                    checked = plazaEnabled,
                    onCheckedChange = { on ->
                        if (!on) {
                            plazaEnabled = false
                            prefs.plazaEnabled = false
                            Toast.makeText(context, "广场已关闭（已发送的消息仍保留在频道中）", Toast.LENGTH_SHORT).show()
                        } else if (!plazaBusy) {
                            plazaBusy = true
                            scope.launch {
                                // R361：用户手动开广场=用户动作车道（USER_ACTION 最高优先，
                                // 不被后台收割烧尽的 BACKGROUND 小时份额挤死）
                                val chatId = plazaService.ensurePlaza(
                                    dev.tgfeed.core.td.SearchQuotaLedger.Lane.USER_ACTION,
                                )
                                plazaBusy = false
                                if (chatId != null) {
                                    plazaEnabled = true
                                    prefs.plazaEnabled = true
                                    Toast.makeText(
                                        context,
                                        "已加入广场 @${prefs.plazaChannel}，全站动态互通已开启",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        // R361 开源收口文案闸：release 无「我是广场创建者」开关
                                        // （广场创建=作者 debug 构建专属），指路文案分两版
                                        if (BuildConfig.DEBUG) {
                                            "加入失败：未找到广场频道 @${prefs.plazaChannel}（若是第一个使用者，请开启下方「我是广场创建者」后重试），或网络异常"
                                        } else {
                                            "加入失败：未找到官方广场 @${prefs.plazaChannel}——官方广场可能暂不可用，请稍后再试（或检查网络）"
                                        },
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        }
                    },
                )
                Text(
                    "隐私说明：动态以你的 TG 账号公开发送，频道内所有人（含非本 App 用户）可见发送者身份；关闭后停止发送，但已发出的消息仍保留在频道中（可在 Telegram 客户端手动删除）",
                    style = MaterialTheme.typography.bodySmall,
                    color = TgFeedTokens.TextMedium,
                )
                // R361 开源收口：release 锁死官方广场名（全站互通前提）——
                // 输入框隐藏，只读行展示官方名；debug（作者通道）保留自定义输入
                if (BuildConfig.DEBUG) {
                    PlazaPillField(
                        label = "广场频道用户名",
                        value = plazaChannel,
                        onValueChange = { input ->
                            plazaChannel = input
                            prefs.plazaChannel = input // 即时保存；setter 内 trim + 去 @，空白读取侧回落默认
                        },
                        hint = "不含 @，留空默认 ${AppPrefs.PLAZA_CHANNEL_OFFICIAL}",
                    )
                    Text(
                        "全站用户需填同一个频道名才能互通；修改后请重新开关一次以加入新频道",
                        style = MaterialTheme.typography.bodySmall,
                        color = TgFeedTokens.TextMedium,
                    )
                } else {
                    Text(
                        "官方广场 @${AppPrefs.PLAZA_CHANNEL_OFFICIAL}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TgFeedTokens.TextHigh,
                    )
                    Text(
                        "全站用户在同一个官方广场互通（正式版锁定官方名，不可修改）",
                        style = MaterialTheme.typography.bodySmall,
                        color = TgFeedTokens.TextMedium,
                    )
                }
                PlazaPillField(
                    label = "备用广场用户名（可空）",
                    value = plazaChannelBackup,
                    onValueChange = { input ->
                        plazaChannelBackup = input
                        prefs.plazaChannelBackup = input // 即时保存；setter 内 trim + 去 @，空白 = 不启用
                    },
                    hint = "不含 @，主广场不可用时自动回落",
                )
                // 广场建好后藏起"我是广场创建者"（建群是一次性的事，建完这个开关再没用了）。
                // R361：release 构建整开关隐藏（广场创建=作者 debug 构建专属，正式版只找不建）
                if (BuildConfig.DEBUG && !prefs.plazaCreated) {
                    PlazaSwitchRow(
                        title = "我是广场创建者",
                        subtitle = "找不到广场频道时，以我的账号创建它（仅第一个使用者需要开启）",
                        checked = plazaOwner,
                        onCheckedChange = {
                            plazaOwner = it
                            prefs.plazaOwner = it
                        },
                    )
                }
                if (plazaBusy) {
                    Text(
                        "正在加入广场…",
                        style = MaterialTheme.typography.bodySmall,
                        color = TgFeedTokens.TextMedium,
                    )
                }
            }
            // 底部让开系统导航条
            Spacer(
                Modifier
                    .navigationBarsPadding()
                    .height(TgFeedTokens.GapMd),
            )
        }
    }
}

/** 开关配色：与设置页 settingsSwitchColors 同款（未选中态轨道提亮，贴在深色底上可见） */
@Composable
private fun plazaSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = TgFeedTokens.TgBlue,
    uncheckedThumbColor = TgFeedTokens.TextMedium,
    uncheckedTrackColor = Color.White.copy(alpha = 0.08f),
    uncheckedBorderColor = Color.White.copy(alpha = 0.16f),
)

/** 两行式开关行：标题 bodyLarge + 说明 bodySmall 灰字，右侧 M3 Switch；整行可点切换（同设置页） */
@Composable
private fun PlazaSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = TgFeedTokens.GapSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(end = TgFeedTokens.GapMd),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = TgFeedTokens.TextHigh)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TgFeedTokens.TextMedium)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = plazaSwitchColors())
    }
}

/** 小标签 + 全圆角输入框：与设置页 LabeledPillField 同一语言 */
@Composable
private fun PlazaPillField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapXs)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = TgFeedTokens.TextLow,
            modifier = Modifier.padding(start = TgFeedTokens.GapXs),
        )
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(hint, maxLines = 1) },
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
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
