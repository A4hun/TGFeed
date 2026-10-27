package dev.tgfeed.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.tgfeed.core.feed.PlayQueue
import dev.tgfeed.core.feed.PlayQueueEntry
import dev.tgfeed.core.feed.StationDownloadCount
import dev.tgfeed.core.feed.TodayQueueRecipe
import dev.tgfeed.core.feed.stationDownloadCount
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.ui.components.kit.AppDialog
import dev.tgfeed.ui.components.kit.AppEmptyState
import dev.tgfeed.ui.components.kit.AppIconButton
import dev.tgfeed.ui.components.kit.AppPill
import dev.tgfeed.ui.components.kit.AppTopBar
import dev.tgfeed.ui.components.kit.adaptiveContentWidth
import dev.tgfeed.ui.theme.TgFeedTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 播放台列表页（R391 #3 播放台/节目单模板：多套队列实体化的管理面，
 * PlaylistsScreen 骨架母版 + QueueScreen 一期单台编辑面组合）。
 *
 * - 台卡：台名 + 「N 条 · 顺序档 · 更新于 时间」+ 断点徽记（「播到一半」——
 *   resumeKey 在场即显示，断点链=一期判词零改）；**点击台卡 = 一键开台**
 *   （vm.openPlayStation 切活跃台→一期开单链，feed 顶栏题=台名，回本页上一页）；
 * - 行尾编辑钮：切活跃台（vm.editPlayStation）→ 进 QueueScreen 编辑该台
 *   （一期编辑面：拖拽/跳过/置顶/档位 chips/加频道/入单桥全复用）；
 * - 长按台卡：管理对话框（重命名 / 删除，删除=红色警示钮）；
 * - 顶栏「+」：新建台（输入名称，MAX_STATIONS 上限 Toast）；
 * - 空态：AppEmptyState（新建一台排单，或长按视频加入队列——入单自动开
 *   默认台，一期体感连续）。
 *
 * 今日台（R391 #7 口味智能编排）：顶栏下方生成行（AutoAwesome 钮）——一键
 * 生成+直接开台（原料快照→纯函数混编→覆盖/新增今日台→一期开单链）；
 * 原料缺=nonce 空态 Toast 诚实引导（不编造）。台列表序=updatedAt 降序
 * （「最近动的在前」），今日台生成时刻即排最前——自然置顶零特判。
 *
 * 与 FeedScreen 共享 activity 作用域同一个 ViewModel（队列数据同源，必带网关 key）。
 *
 * R397 批量入分屏（六入口之五·播放台台卡行尾，私有线件）：台卡行尾「分屏播放
 * N 条」钮——把台内**点名单条**（非跳过 Item 条目，存完整 VideoItem 离线可播）
 * 整批种进分屏片单并直接开分屏（统一机制 App.addBatchToGrid）。频道条目不入格
 * 判词：台内 Channel 条目=「整台按序连播」语义，一期开台链（点击台卡）已承载；
 * N=0（纯频道台）钮不出现=诚实缺席（今日台探索段走开台链零损失）。
 *
 * R398 #9 离线联动（私有线件）：台卡 subtitle「已下载 N/M」徽记（N=命中下载
 * 台账数，M=非跳过单条条目数——下载完成/删除实时反映；M=0 不显示=诚实缺席）
 * +行尾「下载整单」钮（FileDownload 图标，下满=AccentRed）——断续幂等串行批
 * 走既有单条下载链；离线按单播=开单时 Single 段命中台账换本地 playUri
 * （FeedStationDownloadPart 挂点，出门前一键路上按单播判词）。判词：出门前
 * 一键，路上按单播。
 */
@Composable
fun QueueStationsScreen(
    gateway: TdGateway,
    onBack: () -> Unit,
    /** 进单台编辑页（QueueScreen）：宿主路由侧 openPlayQueue（编辑对象=活跃台） */
    onOpenStationEditor: () -> Unit,
    // R397 批量入分屏：宿主（MainActivity）收口——种进容器片单 + 剥落本页 + 开分屏
    onBatchToGrid: (List<VideoItem>) -> Unit = {},
    /**
     * R414 开台后落点（宿主收口）：切源后必须剥落台列表+我的页回首页 Feed 播放。
     * 旧件开台只调 [onBack]=只关台列表——用户实锤「点播放台跳到我的页」（我的页
     * 是本页宿主，开台后应进 Feed 连播）。缺省=onBack 保旧调用方兼容。
     */
    onStationStarted: (() -> Unit)? = null,
) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val context = LocalContext.current
    // 系统返回键 = 返回上一页（状态驱动路由；从我的页进来即回我的页）
    BackHandler(onBack = onBack)
    // 与 FeedScreen 共享 activity 作用域的同一个 ViewModel（QueueScreen 同款 key 口径）
    val vm: FeedViewModel = viewModel(
        factory = FeedViewModelFactory(gateway, app),
        key = "gw" + System.identityHashCode(gateway),
    )
    val stations by vm.playQueueStations.collectAsState()
    // R398 #9 下载台账键集（徽记 N/M 派生数据——下载完成/删除实时反映，
    // StateFlow 重组自动活；零新状态=DownloadRecord 既有账派生）
    val downloadRecords by vm.downloadRecords.collectAsState()
    val downloadedKeys = remember(downloadRecords) { downloadRecords.mapTo(HashSet()) { it.key } }
    // 今日台生成空态 nonce（原料缺——每次+1 触发引导 Toast；nonce 单调递增防快照流去重吞）
    val todayEmptyHint by vm.todayQueueEmptyHint.collectAsState()
    // 今日台在场判定（生成钮文案分流的键：name=今日台=识别口径，用户改名后=普通台）
    val hasTodayStation = stations.any { it.name == TodayQueueRecipe.STATION_NAME }

    // 空态引导（诚实口径：原料缺=新用户/无历史，不编造——#7 铁律）
    LaunchedEffect(todayEmptyHint) {
        if (todayEmptyHint > 0) {
            Toast.makeText(
                context,
                "口味数据还不够：先去刷几天视频、收藏几条，再来开今日台",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    // 新建台对话框显隐 + 名称草稿
    var showCreate by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    // 长按弹出的管理对话框目标（非空即显示）；重命名/删除对话框复用该目标
    var manageTarget by remember { mutableStateOf<PlayQueue?>(null) }
    var renameTarget by remember { mutableStateOf<PlayQueue?>(null) }
    var renameName by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<PlayQueue?>(null) }
    // 接棒选择器目标(R392 #5:台卡行尾「接棒」钮点开,非空即显示)
    var chainTarget by remember { mutableStateOf<PlayQueue?>(null) }
    // 晨间定时对话框(R392 #6①:播放台列表常驻入口)+入口行展示态
    // (AppPrefs SP 读纳秒级;保存后本地刷新)
    val morningPrefs = remember { dev.tgfeed.core.prefs.AppPrefs(context) }
    var morningOn by remember { mutableStateOf(morningPrefs.morningStationEnabled) }
    var morningMinute by remember { mutableStateOf(morningPrefs.morningStationMinuteOfDay) }
    var showMorningDialog by remember { mutableStateOf(false) }

    /** 一键开台：空台只提示不切源（一期 playQueue 同款守卫口径） */
    fun openStation(station: PlayQueue) {
        if (station.entries.none { !it.skipped }) {
            Toast.makeText(context, "这台还是空的，先编辑排单", Toast.LENGTH_SHORT).show()
            return
        }
        vm.openPlayStation(station.id)
        (onStationStarted ?: onBack)()
    }

    // 大屏：内容列按 adaptiveContentWidth 三档限宽居中（与设置/下载/片单页同一规格）；整页纯黑沉浸底
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
            // 顶栏：返回圆钮 + 标题 + 新建台圆钮
            AppTopBar(
                title = "播放台",
                onBack = onBack,
                actions = {
                    AppIconButton(
                        icon = Icons.Filled.Add,
                        contentDescription = "新建播放台",
                        onClick = {
                            createName = ""
                            showCreate = true
                        },
                    )
                },
            )

            // 今日台生成行（R391 #7 口味智能编排入口：一键生成+直接开台——
            // 原料快照（p2ChannelWeights 统一口径+收藏+已看账）→ 纯函数混编
            // （探索台×收藏重温交替）→ 覆盖/新增今日台 → 一期开单链开台。
            // 已存在今日台时文案=「重新生成」（覆盖语义：新单旧断点失效；
            // 续播今日台=点下方台卡，不走生成钮）
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { vm.generateTodayStation() }) {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = TgFeedTokens.AccentRed,
                    )
                    Spacer(Modifier.width(TgFeedTokens.GapXs))
                    Text(
                        if (hasTodayStation) "重新生成今日单" else "生成今日单",
                        color = TgFeedTokens.AccentRed,
                    )
                }
            }

            // 晨间定时入口行(R392 #6①,设置口=播放台列表):每日 HH:mm 自动生成
            // 今日单并开台播单——「电台连续节目」的晨间形态(配一次,每天到点
            // 自己开台);进程内协程闸点循环(DailyDigest 判词,零新后台常驻)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { showMorningDialog = true }) {
                    Icon(
                        Icons.Outlined.Alarm,
                        contentDescription = null,
                        tint = if (morningOn) TgFeedTokens.AccentRed else TgFeedTokens.TextMedium,
                    )
                    Spacer(Modifier.width(TgFeedTokens.GapXs))
                    Text(
                        if (morningOn) {
                            "晨间 ${morningMinute / 60}:${"%02d".format(morningMinute % 60)} 自动开今日台"
                        } else {
                            "晨间定时未开启"
                        },
                        color = if (morningOn) TgFeedTokens.AccentRed else TgFeedTokens.TextMedium,
                    )
                }
            }

            if (stations.isEmpty()) {
                // 空态：kit AppEmptyState 统一壳（大图标 + 主文案 + 引导副文案）
                AppEmptyState(
                    icon = Icons.Outlined.QueueMusic,
                    title = "还没有播放台",
                    subtitle = "新建一台排单、长按视频加入队列，或生成口味今日单",
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = TgFeedTokens.GapLg),
                ) {
                    items(stations, key = { it.id }) { station ->
                        StationCard(
                            station = station,
                            chainLabel = stationChainLabel(station, stations),
                            onOpen = { openStation(station) },
                            onEdit = {
                                if (vm.editPlayStation(station.id)) {
                                    onOpenStationEditor()
                                }
                            },
                            onChain = { chainTarget = station },
                            onLongClick = { manageTarget = station },
                            // R397 批量入分屏：N=非跳过单条条目数实数（频道条目归
                            // 一期开台链，判词见文件头 KDoc）
                            seedCount = station.entries.count { it is PlayQueueEntry.Item && !it.skipped },
                            onSeed = {
                                onBatchToGrid(
                                    station.entries
                                        .filterIsInstance<PlayQueueEntry.Item>()
                                        .filter { !it.skipped }
                                        .map { it.item },
                                )
                            },
                            // R398 #9 离线联动：徽记数据（N=命中台账数/M=非跳过单条
                            // 条目数，纯函数口径）+「下载整单」口（断续幂等串行批）
                            downloadCount = stationDownloadCount(station, downloadedKeys),
                            onDownload = {
                                val started = vm.downloadPlayStation(station.id)
                                Toast.makeText(
                                    context,
                                    if (started > 0) {
                                        "开始下载 $started 条（进度看台卡已下载计数）"
                                    } else {
                                        "都已下载（或正在下载中）"
                                    },
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
                        Spacer(Modifier.height(TgFeedTokens.GapMd))
                    }
                }
            }
        }
    }

    // 新建台对话框（kit AppDialog 统一壳；输入框留在内容槽——PlaylistsScreen 同款）
    if (showCreate) {
        AppDialog(
            onDismissRequest = { showCreate = false },
            title = "新建播放台",
            confirmText = "创建",
            onConfirm = {
                val name = createName.trim()
                // 空名不创建、不关窗（PlaylistsScreen 同款口径）
                if (name.isEmpty()) return@AppDialog
                if (!vm.createPlayStation(name)) {
                    Toast.makeText(context, "播放台已达上限，先清理旧台", Toast.LENGTH_SHORT).show()
                    showCreate = false
                    return@AppDialog
                }
                showCreate = false
            },
            dismissText = "取消",
            content = {
                OutlinedTextField(
                    value = createName,
                    onValueChange = { createName = it },
                    placeholder = { Text("播放台名称", color = TgFeedTokens.TextLow) },
                    singleLine = true,
                )
            },
        )
    }

    // 长按管理对话框（kit AppDialog 统一壳）：主钮 = 重命名，次钮 = 取消；
    // 「删除」收进内容区（红色警示钮——PlaylistsScreen 同款布局；删除=二次确认）
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
                        "${manage.entries.size} 条 · ${manage.orderMode.label} · " +
                            "更新于 ${formatStationDate(manage.updatedAt)}",
                        color = TgFeedTokens.TextMedium,
                    )
                    Spacer(Modifier.height(TgFeedTokens.GapLg))
                    Text(
                        "删除播放台",
                        style = MaterialTheme.typography.labelLarge,
                        color = TgFeedTokens.WarningRed,
                        modifier = Modifier
                            .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
                            .clickable { manageTarget = null; deleteTarget = manage }
                            .padding(vertical = TgFeedTokens.GapSm),
                    )
                }
            },
        )
    }

    // 删除二次确认（删除语义=用户显式确认后才执行；断点随实体消亡=一期 clear 同口径）
    val delete = deleteTarget
    if (delete != null) {
        AppDialog(
            onDismissRequest = { deleteTarget = null },
            title = "删除播放台",
            confirmText = "删除",
            onConfirm = {
                vm.deletePlayStation(delete.id)
                deleteTarget = null
                Toast.makeText(context, "已删除「${delete.name}」", Toast.LENGTH_SHORT).show()
            },
            dismissText = "取消",
            content = {
                Text(
                    "「${delete.name}」的 ${delete.entries.size} 条与断点将一并移除",
                    color = TgFeedTokens.TextMedium,
                )
            },
        )
    }

    // 重命名对话框（kit AppDialog 统一壳）
    val rename = renameTarget
    if (rename != null) {
        AppDialog(
            onDismissRequest = { renameTarget = null },
            title = "重命名播放台",
            confirmText = "确定",
            onConfirm = {
                val name = renameName.trim()
                // 空名不生效、不关窗（PlaylistsScreen 同款口径）
                if (name.isEmpty()) return@AppDialog
                vm.renamePlayStation(rename.id, name)
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

    // 接棒选择器（R392 #5，AppDialog 统一壳）：三段——不接棒/下一台/指定台
    // （列表显示序=CHAIN_NEXT 解析序，所见即所得）；选择即存即关窗。
    // 「睡眠衔接」说明行如实告知 #6② 语义（定时关闭计时中，节目单播完
    // 立即停播并清计时——睡眠旗优先于接棒）
    val chain = chainTarget
    if (chain != null) {
        AppDialog(
            onDismissRequest = { chainTarget = null },
            title = "接棒 · ${chain.name}",
            confirmText = "完成",
            onConfirm = { chainTarget = null },
            content = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ChainOptionRow(
                        label = "不接棒（播完即停）",
                        selected = chain.chainTo == null,
                    ) {
                        vm.setPlayStationChain(chain.id, null)
                        chainTarget = null
                    }
                    ChainOptionRow(
                        label = "播完后接下一台",
                        selected = chain.chainTo == PlayQueue.CHAIN_NEXT,
                    ) {
                        vm.setPlayStationChain(chain.id, PlayQueue.CHAIN_NEXT)
                        chainTarget = null
                    }
                    for (other in stations.filter { it.id != chain.id }) {
                        ChainOptionRow(
                            label = "接「${other.name}」（${other.entries.size} 条）",
                            selected = chain.chainTo == other.id,
                        ) {
                            vm.setPlayStationChain(chain.id, other.id)
                            chainTarget = null
                        }
                    }
                    if (stations.size <= 1) {
                        Text(
                            "只有这一台：先再建一台，才能配下一台/指定台",
                            style = MaterialTheme.typography.bodySmall,
                            color = TgFeedTokens.TextLow,
                        )
                    }
                    Spacer(Modifier.height(TgFeedTokens.GapMd))
                    Text(
                        "本台节目单播完时自动开接棒台续播（接棒台有断点则从断点续播）；" +
                            "若定时关闭正在计时，节目单播完会先停播并清除计时（睡眠优先）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TgFeedTokens.TextLow,
                    )
                }
            },
        )
    }

    // 晨间定时对话框（R392 #6①）：开关 + 时/分 chips（FlowRow 折行）+ 睡眠
    // 衔接与已知局限说明（诚实口径）；保存=落 prefs+重挂 VM 循环即时生效
    if (showMorningDialog) {
        MorningAlarmDialog(
            initialEnabled = morningOn,
            initialMinuteOfDay = morningMinute,
            onSave = { enabled, minute ->
                vm.setMorningStationAlarm(enabled, minute)
                morningOn = enabled
                morningMinute = minute
            },
            onDismiss = { showMorningDialog = false },
        )
    }
}

/**
 * 播放台卡：台名 + 「N 条 · 顺序档 · 更新于 时间」+ 断点徽记 + 接棒徽记 +
 * 行尾分屏播放钮（R397，seedCount>0 才在）/下载整单钮（R398，total>0 才在）
 * /接棒钮/编辑钮。点击整卡 = 一键开台（切源播单）；长按 = 管理对话框
 * （重命名/删除）。R392 起 chainLabel 非空=行尾接棒钮 AccentRed 高亮
 * （配置可见性：接不接棒一眼可辨）；R398 起 downloadCount 下满
 * （downloaded==total）=下载钮 AccentRed 高亮（下没下满一眼可辨）。
 */
@OptIn(ExperimentalFoundationApi::class) // combinedClickable：长按管理的标准手势（PlaylistCard 同款）
@Composable
private fun StationCard(
    station: PlayQueue,
    chainLabel: String?,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onChain: () -> Unit,
    onLongClick: () -> Unit,
    // R397 批量入分屏：seedCount=台内非跳过单条条目数（0=纯频道台，钮不出现）
    seedCount: Int,
    onSeed: () -> Unit,
    // R398 #9 离线联动：已下载 N/M（M=0 纯频道台，钮与徽记都不出现=诚实缺席，
    // 频道条目归一期开台链判词同 R397）+「下载整单」口（断续幂等串行批）
    downloadCount: StationDownloadCount,
    onDownload: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // 卡片规格对齐 token：SurfaceHigh 底 + RadiusLg 圆角（PlaylistCard 同款）
            .clip(RoundedCornerShape(TgFeedTokens.RadiusLg))
            .background(TgFeedTokens.SurfaceHigh)
            .combinedClickable(onClick = onOpen, onLongClick = onLongClick)
            .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapLg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.QueueMusic,
            contentDescription = "播放台",
            tint = TgFeedTokens.TextMedium,
        )
        Spacer(Modifier.width(TgFeedTokens.GapMd))
        Column(Modifier.weight(1f)) {
            Text(
                station.name,
                style = MaterialTheme.typography.bodyLarge,
                color = TgFeedTokens.TextHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(TgFeedTokens.GapXs))
            // 断点徽记：resumeKey 在场=「播到一半」（一期断点链零改，视觉直读）
            // 接棒徽记（R392 #5）：接棒配置字面兑现可见（不接=不显示）
            val subtitle = buildString {
                append(station.entries.size).append(" 条 · ").append(station.orderMode.label)
                append(" · 更新于 ").append(formatStationDate(station.updatedAt))
                if (station.resumeKey != null) append(" · 播到一半")
                if (chainLabel != null) append(" · ").append(chainLabel)
                // R398 #9 下载徽记：M>0 才显示（断点/接棒徽记同串；下载完成/
                // 删除实时反映——downloadRecords StateFlow 重组）
                if (downloadCount.total > 0) {
                    append(" · 已下载 ").append(downloadCount.downloaded)
                        .append('/').append(downloadCount.total)
                }
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TgFeedTokens.TextMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // R397 批量入分屏（台卡行尾）：台内点名单条整批倒进分屏——N=非跳过 Item
        // 条数实数；0=纯频道台不显示（诚实缺席，频道段走点击台卡开台链）。紧凑
        // TextButton 收窄内衬（行尾已有接棒/编辑两圆钮，防名字列被挤没）
        if (seedCount > 0) {
            TextButton(
                onClick = onSeed,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Icon(
                    Icons.Outlined.GridView,
                    contentDescription = "分屏播放",
                    tint = TgFeedTokens.AccentRed,
                    modifier = Modifier.size(TgFeedTokens.IconMd),
                )
                Spacer(Modifier.width(TgFeedTokens.GapXs))
                Text(
                    "分屏播放 $seedCount 条",
                    color = TgFeedTokens.AccentRed,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        // R398 #9 下载整单钮（台卡行尾，分屏钮后接棒钮前）：台内点名单条整批
        // 下载（断续幂等——已下载/在途跳过只补缺；串行批）；M=0 纯频道台
        // 不显示（诚实缺席，频道段走点击台卡开台链=下载面判词同 R397）；
        // downloaded==total 下满=AccentRed 高亮（接棒钮配置态同款语言）。
        // 窄屏四连钮（分屏文本钮+下载/接棒/编辑三圆钮）名字列挤压风险如实
        // 记：名字列 weight 弹性+ellipsis 既有兜底，徽记信息主走 subtitle
        if (downloadCount.total > 0) {
            IconButton(onClick = onDownload) {
                Icon(
                    Icons.Outlined.FileDownload,
                    contentDescription = "下载整单",
                    tint = if (downloadCount.downloaded == downloadCount.total) {
                        TgFeedTokens.AccentRed
                    } else {
                        TgFeedTokens.TextMedium
                    },
                )
            }
        }
        // 行尾接棒钮（R392 #5）：段尽接棒设置口（选择器：无/下一台/指定台）
        IconButton(onClick = onChain) {
            Icon(
                Icons.Outlined.FastForward,
                contentDescription = "接棒设置",
                tint = if (chainLabel != null) TgFeedTokens.AccentRed else TgFeedTokens.TextMedium,
            )
        }
        // 行尾编辑钮：切活跃台 + 进一期编辑面（QueueScreen）
        IconButton(onClick = onEdit) {
            Icon(
                Icons.Outlined.Edit,
                contentDescription = "编辑播放台",
                tint = TgFeedTokens.TextMedium,
            )
        }
    }
}

/**
 * 接棒徽记文案（R392 #5 台卡 subtitle 用）：null=不接（不显示）；
 * CHAIN_NEXT=「接棒下一台」；指定台失联=「接棒 (台已删)」诚实降级。
 */
private fun stationChainLabel(station: PlayQueue, all: List<PlayQueue>): String? =
    when (station.chainTo) {
        null -> null
        PlayQueue.CHAIN_NEXT -> "接棒下一台"
        else -> "接棒 ${all.firstOrNull { it.id == station.chainTo }?.name ?: "(台已删)"}"
    }

/** 接棒选择器行（选中=AccentRed + 尾标 ✓；点击即选即关窗由调用方回调负责） */
@Composable
private fun ChainOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
            .clickable(onClick = onClick)
            .padding(vertical = TgFeedTokens.GapSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) TgFeedTokens.AccentRed else TgFeedTokens.TextHigh,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text("✓", color = TgFeedTokens.AccentRed, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * 晨间定时对话框（R392 #6①）：开关行 + 时/分 chips（FlowRow 折行，分钟粒度
 * 10 分钟档=电台晨间语义够用）+ 保存/取消 + 说明区（到点行为/已知局限/睡眠
 * 衔接，诚实口径）。保存回调=vm.setMorningStationAlarm（落 prefs+重挂循环
 * 即时生效）。
 */
@OptIn(ExperimentalLayoutApi::class) // FlowRow 折行（FilterSheet/ActionSheet 同款用法）
@Composable
private fun MorningAlarmDialog(
    initialEnabled: Boolean,
    initialMinuteOfDay: Int,
    onSave: (Boolean, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var enabled by remember { mutableStateOf(initialEnabled) }
    var minuteOfDay by remember { mutableStateOf(initialMinuteOfDay) }
    AppDialog(
        onDismissRequest = onDismiss,
        title = "晨间定时",
        confirmText = "保存",
        onConfirm = {
            onSave(enabled, minuteOfDay)
            onDismiss()
        },
        dismissText = "取消",
        content = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // 开关行（点行整体切换——对话框内轻开关，TextButton 文案分流）
                TextButton(onClick = { enabled = !enabled }) {
                    Text(
                        if (enabled) "已开启 · 点按关闭" else "未开启 · 点按开启",
                        color = if (enabled) TgFeedTokens.AccentRed else TgFeedTokens.TextMedium,
                    )
                }
                if (enabled) {
                    Spacer(Modifier.height(TgFeedTokens.GapSm))
                    Text("每天几点", style = MaterialTheme.typography.labelLarge, color = TgFeedTokens.TextMedium)
                    FlowRow(
                        Modifier.fillMaxWidth().padding(vertical = TgFeedTokens.GapXs),
                    ) {
                        for (h in 0..23) {
                            AppPill(
                                text = h.toString(),
                                selected = minuteOfDay / 60 == h,
                                onClick = { minuteOfDay = h * 60 + minuteOfDay % 60 },
                            )
                        }
                    }
                    Text("分钟", style = MaterialTheme.typography.labelLarge, color = TgFeedTokens.TextMedium)
                    FlowRow(
                        Modifier.fillMaxWidth().padding(vertical = TgFeedTokens.GapXs),
                    ) {
                        for (m in 0..50 step 10) {
                            AppPill(
                                text = "%02d".format(m),
                                selected = minuteOfDay % 60 == m,
                                onClick = { minuteOfDay = minuteOfDay / 60 * 60 + m },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(TgFeedTokens.GapMd))
                Text(
                    "到点自动生成口味今日单并开台播单（原料缺则诚实跳过不编造）；" +
                        "应用当天没打开则当天不触发，下次打开在 4 小时迟到窗内补开。" +
                        "另：定时关闭计时中，节目单播完会立即停播并清除计时（睡眠播完即停）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TgFeedTokens.TextLow,
                )
            }
        },
    )
}

/** 播放台最近动过时刻格式化；缺省（<=0，如新建空台）显示「未知」 */
private fun formatStationDate(ts: Long): String =
    if (ts <= 0) "未知" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
