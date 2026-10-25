package dev.tgfeed.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotInterested
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tgfeed.BuildConfig
import dev.tgfeed.R
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.backup.BackupManager
import dev.tgfeed.core.feed.DemandSubscription
import dev.tgfeed.core.feed.DeadMediaPolicy
import dev.tgfeed.core.feed.FeedViewModelFactory
import dev.tgfeed.core.feed.IndexCrawlGate
import dev.tgfeed.core.feed.IndexTierPolicy
import dev.tgfeed.core.feed.universeRhythmNow
import dev.tgfeed.core.plaza.PlazaService
import dev.tgfeed.core.prefs.AppPrefs
import dev.tgfeed.core.storage.fmtGb
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.ui.components.HarvestGroupPickerSheet
import dev.tgfeed.ui.components.kit.AppDialog
import dev.tgfeed.ui.components.kit.adaptiveContentWidth
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlin.system.exitProcess

/** 分组卡片圆角：16dp（tokens 里暂无"卡片"专用档，值与 RadiusField 相同但语义不同，故私有定义） */
private val SettingsCardRadius = 16.dp

/** "屏蔽的频道"列表折叠阈值：超过该条数默认折叠成一行摘要，点开才展开全列表 */
private const val BLOCKED_COLLAPSE_THRESHOLD = 8

/**
 * 高级折叠区卡片清单（R198 渐进披露）。默认视图只留常用卡，这七组低频配置收进
 * 「高级设置」折叠区（R215 增「时间维度工具」：发现页撤下的事件研究器/宇宙作息表
 * 入口宿主）；清单与折叠区内实际卡片一一对应，增删卡必须同步本表——
 * 头部计数行「高级 · N 项」与 SettingsIaPolicyTest（数量相等断言）都以此为准。
 */
private val AdvancedCardTitles = listOf(
    "内容源策略", "需求订阅", "广场", "跨设备续播", "深度搜索", "时间维度工具", "诊断通道",
)

/**
 * 高级折叠区头部计数行的可见卡数（R361 开源收口：「诊断通道」卡 release 构建整卡隐藏
 * ——BuildConfig.DEBUG 一闸，见下方诊断通道段；计数行必须说真话，release 少一张卡）。
 * 清单本体 AdvancedCardTitles 不动（源锚 SettingsIaPolicyTest 按源码卡片调用点核对，
 * DEBUG 闸包裹不影响源锚计数），运行时计数用本值。
 */
private val AdvancedVisibleCount = AdvancedCardTitles.size - (if (BuildConfig.DEBUG) 0 else 1)

/**
 * 设置页（R198 信息架构治理：目标分组 + 渐进披露 + 平民化文案）。
 * - 默认视图按用户目标只留 5 张常用卡：播放 / 内容类型 / 省流与存储 / 发现新内容 /
 *   账号与数据；低频配置（内容源策略/需求订阅/广场/跨设备续播/深度搜索/诊断通道）
 *   收进「高级设置」折叠区（AnimatedVisibility 默认收起，头部「高级 · N 项」计数）；
 * - 数据迁移 / 屏蔽的频道 / 危险区留在底部（管理区惯例不动）；
 * - 开关行两行式 + 行尾可选「？」说明图标（SettingsHelpHint，AppDialog 气泡）：
 *   副文案只留两行人话，机理/参数/取舍/默认值全进气泡；
 * - 每组设置收进一张 16dp 圆角卡片（SurfaceHigh 底 + 卡片内顶部小字组标题），卡片间 16dp；
 * - 输入框全圆角（CircleShape）+ 白 α0.14 底 + 指示线透明，与顶栏搜索同一语言；
 * - 顶部：圆形底托返回钮 + 大标题"设置"。
 * 从 Feed 右上角齿轮进入，返回回到 Feed。
 * 本轮为纯 IA/文案/折叠层改动：开关逻辑与 prefs 键零变化（SettingsIaPolicyTest 锁结构）。
 */
@Composable
fun SettingsScreen(
    gateway: TdGateway,
    prefs: AppPrefs,
    autoAdvance: Boolean,
    onApplyConfig: (apiId: Int, apiHash: String, proxyEnabled: Boolean, proxyHost: String, proxyPort: Int) -> Unit,
    onSetAutoAdvance: (Boolean) -> Unit,
    onBack: () -> Unit,
    // 打开"我的下载"管理页（路由由容器层接）；默认空实现保证旧调用点不用改
    onOpenDownloads: () -> Unit = {},
    // 打开"观看报告"页（路由由容器层接）；默认空实现保证旧调用点不用改
    onOpenWatchReport: () -> Unit = {},
    // 打开"关于"页（路由由容器层接）；默认空实现保证旧调用点不用改
    onOpenAbout: () -> Unit = {},
    // 生效中的凭证（含编译期 BuildConfig 兜底），用于字段预填——只读 prefs 会把凭证显示成空白
    effectiveApiId: Int = 0,
    effectiveApiHash: String = "",
) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val context = LocalContext.current
    // 容器（播放列表等路由入口用）
    val appContainer = (app as dev.tgfeed.App).container
    // 系统返回键 = 返回 Feed（设置页是状态驱动路由，不在返回栈里，不拦会退到桌面）
    BackHandler(onBack = onBack)
    // 与 FeedScreen 共享 activity 作用域的同一个 ViewModel（拿清理存储的能力）
    val vm: FeedViewModel = viewModel(factory = FeedViewModelFactory(gateway, app), key = "gw" + System.identityHashCode(gateway))
    // R196 存储管理：进设置页即量一次概览 + 清理日志（IO 盘测，StateFlow 回填；
    // 手动「立即清理」完成后 FeedStoragePart 会再触发一轮刷新）
    LaunchedEffect(vm) { vm.refreshStorageOverview() }

    // 凭证一律不回显（用户手填过的也不回显——填过的值依然生效，只是不显示；
    // 想换新的直接填新值覆盖即可。防"平板借人/截图外泄把凭证带走"）
    var apiId by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    var proxyEnabled by remember { mutableStateOf(prefs.proxyEnabled) }
    var proxyHost by remember { mutableStateOf(prefs.proxyHost) }
    var proxyPort by remember { mutableStateOf(prefs.proxyPort.toString()) }
    var cleaning by remember { mutableStateOf(false) }
    var audioBg by remember { mutableStateOf(prefs.audioBackground) }
    // R180 音视频共存开关镜像 prefs；翻转经容器即时生效（共享音频播放器的系统焦点
    // 参与策略切换），无需重启/重建播放器
    var allowSimul by remember { mutableStateOf(prefs.allowAudioVideoSimultaneous) }
    // 音量均衡开关镜像 prefs；即时落盘，播放器音频链逐缓冲读新值（翻转即时生效，无需重启）
    var volumeNormalize by remember { mutableStateOf(prefs.volumeNormalize) }
    var danmakuOn by remember { mutableStateOf(prefs.danmakuEnabled) }
    var progressSyncEnabled by remember { mutableStateOf(prefs.progressSyncEnabled) }
    // R300 多设备状态同步（收藏/稍后/追更订阅+已读水位/屏蔽三件套）总闸镜像 prefs
    var cloudSyncEnabled by remember { mutableStateOf(prefs.cloudSyncEnabled) }
    // R324 离线降级（多设备同步面·不可用档）：NetworkWatch 单源——云同步卡离线
    // 提示行 + 「立即同步」禁用态的数据源（推拉全走网络，离线点了必败=不假装在努力）
    val networkOnline by dev.tgfeed.core.net.NetworkWatch.online.collectAsState()
    var commentCascade by remember { mutableStateOf(gateway.includeCommentResources) }
    var excludeWatched by remember { mutableStateOf(prefs.excludeWatched) }
    var indexing by remember { mutableStateOf(false) }
    var indexProgress by remember { mutableStateOf("") }
    var backgroundAction by remember { mutableStateOf(prefs.backgroundAction) }
    val scope = rememberCoroutineScope()
    // 内容类型开关：本地镜像 prefs，切换即时落盘（Feed 过滤由主代理按这三个值接线）
    var showVideos by remember { mutableStateOf(prefs.showVideos) }
    var showPhotos by remember { mutableStateOf(prefs.showPhotos) }
    var showAudio by remember { mutableStateOf(prefs.showAudio) }
    // 缓存上限输入框镜像 prefs；非法输入只在本地停留，不落盘
    var cacheLimitGb by remember { mutableStateOf(prefs.cacheLimitGb.toString()) }
    // 搜索机器人用户名输入框镜像 prefs；即时保存（setter 内 trim + 去 @，空白读取侧回落默认）
    // 机器人列表 = 主 + 备用合并显示（逗号分隔）
    var searchBot by remember {
        mutableStateOf(
            (listOf(prefs.searchBotUsername) + prefs.searchBotFallbacks.split(','))
                .map { it.trim() }.filter { it.isNotEmpty() }.joinToString(","),
        )
    }
    // 关键词订阅输入框镜像 prefs；逗号分隔多个词，即时保存（刷词表入口在 Feed 侧，见 showKeywordFeed）
    var keywordSubs by remember { mutableStateOf(prefs.keywordSubscriptions) }
    // R148 订阅更新通知合并开关：旧"追更通知"+"到货通知"两个分开关收口为一个；
    // 显示值 = 两键按位或（任一开过就显示开，宽容迁移），翻转时两键同写
    // （追更侧 VM 内存态同步 setNotifyNewContent，到货侧 prefs 即时生效），旧状态不一致在首次翻转时对齐
    var notifyNewContent by remember {
        mutableStateOf(prefs.notifyNewContent || prefs.demandNotifyEnabled)
    }
    // TG 广场：开关/频道名/创建者标记镜像 prefs；plazaBusy 防"加入中"重复点击
    var plazaEnabled by remember { mutableStateOf(prefs.plazaEnabled) }
    var plazaChannel by remember { mutableStateOf(prefs.plazaChannel) }
    var plazaOwner by remember { mutableStateOf(prefs.plazaOwner) }
    var plazaBusy by remember { mutableStateOf(false) }
    // R128 点赞回写开关镜像 prefs；即时落盘，网关侧每次调用现查（翻转即时生效，无需重启）
    var likeWriteback by remember { mutableStateOf(prefs.likeReactionWriteback) }
    // 危险区二次确认弹窗开关（GAPS3：破坏性操作零确认 → 先弹 AlertDialog 说清后果，确认后才执行）
    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmClearDisliked by remember { mutableStateOf(false) }
    // 深度索引"清空重建"二次确认弹窗开关（破坏性操作：先弹窗说清后果，确认后才执行）
    var confirmRebuildIndex by remember { mutableStateOf(false) }
    // R294 P3 压实：确认弹窗开关+预估文案（点钮时现场预估自由页/库总量）
    var confirmCompactIndex by remember { mutableStateOf(false) }
    var compactEstimateText by remember { mutableStateOf("") }
    // ---------------- 数据迁移（数据包导出/导入）状态 ----------------
    // 导/入互斥门闩（索引库可达数百 MB，一次只跑一个任务）+ 进行中文案
    var backupBusy by remember { mutableStateOf(false) }
    var backupProgress by remember { mutableStateOf("") }
    // 导入前的待确认目标（SAF 选定的 zip）：先弹"覆盖确认"再真正导入，绝不选中即动手
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    // 导入完成摘要：非空时弹"重启生效"对话框（ prefs/索引/各缓存都是启动时加载，必须冷启动）
    var importDoneSummary by remember { mutableStateOf<String?>(null) }
    // R287：合并导入接单摘要（非空时弹免重启提示；合并本体后台批迁+断点续传）
    var importMergeSummary by remember { mutableStateOf<String?>(null) }
    // ---------------- R197 我的搜索群：跳转行状态（发现模块属地，此处只留入口） ----------------
    // sheet 与发现页共用同款组件；勾选翻格回调 tick → 重读勾选计数（loadUserSelectedGroups
    // 纯读零网络）；sheet 挂载在文件末尾弹窗区（与 AlertDialog 同位，不进滚动列）
    var showHarvestPicker by remember { mutableStateOf(false) }
    // R215 时间维度工具：宇宙作息表面板的快照载荷（null=关着；行点击时快照优先填充）
    var rhythmSheet by remember {
        mutableStateOf<dev.tgfeed.core.feed.UniverseRhythm.Rhythm?>(null)
    }
    // R220 测试文件夹清理：诊断通道卡入口弹起的对话框（真实网关才有；挂文件末尾弹窗区）
    var showTestFolderCleaner by remember { mutableStateOf(false) }
    // R300 清理云同步消息：诊断通道卡入口弹起的二次确认框（同上挂载位）
    var showCloudSyncCleaner by remember { mutableStateOf(false) }
    var harvestPickTick by remember { mutableStateOf(0) }
    val harvestGroupCount by produceState(initialValue = 0, gateway, harvestPickTick) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                (gateway as? TdLibGateway)?.groupHarvester?.loadUserSelectedGroups()?.size ?: 0
            }.getOrDefault(0)
        }
    }

    // SAF 导出：系统文件选择器新建 zip（默认落在"下载"目录；全版本免存储权限）
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null && !backupBusy) {
            backupBusy = true
            backupProgress = "正在导出…"
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        // 进度回调跑在 IO 线程：直接写 Compose snapshot 状态（跨线程写安全）
                        BackupManager.exportBackup(context, uri) { msg -> backupProgress = msg }
                    }
                }
                backupBusy = false
                backupProgress = ""
                result.onSuccess { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                    .onFailure {
                        Toast.makeText(context, "导出失败：${it.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    // SAF 导入：系统文件选择器打开 zip；选中后只记入 pendingImportUri 弹覆盖确认，确认后才动手
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) pendingImportUri = uri }
    // 广场服务：仅真实 TDLib 网关可用（Mock 演示模式无广场）；跟随 gateway 实例重建
    val plazaService = remember(gateway) {
        (gateway as? TdLibGateway)?.let { PlazaService(it, prefs) }
    }

    // B12：页面滚动状态用 rememberSaveable + ScrollState.Saver 持有——
    // 设置页是状态驱动路由，进子页（下载/播放列表/观看报告）时本页被移出组合，
    // 普通 remember 的 ScrollState 会随组合销毁丢失滚动位置，返回时被重置回顶部；
    // rememberSaveable 走 SaveableStateRegistry，重建后按 Saver 恢复滚动偏移
    val settingsScrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }

    // 大屏：内容列按 adaptiveContentWidth 三档限宽居中（手机竖屏铺满/平板放宽/超宽横屏 480dp）
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(
        Modifier
            // 自适应内容列宽必须在前：fillMaxSize 会先把约束钉成全宽，其后的限宽是死代码（P1-6 教训）
            .adaptiveContentWidth()
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(settingsScrollState)
            .padding(horizontal = 20.dp)
            // 底部避开手势条，最后一张卡片不贴边
            .navigationBarsPadding(),
    ) {
        // ---------- 顶部：圆形底托返回钮 + 大标题 ----------
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = TgFeedTokens.GapLg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(TgFeedTokens.SurfaceHigh)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = TgFeedTokens.TextHigh,
                    modifier = Modifier.size(20.dp),
                )
            }
            // ST-D：页面标题"设置"——17sp SemiBold + TextHigh；
            // 必须显式给颜色：不指定时吃 LocalContentColor 默认值，深色背景上呈深色字几乎不可见
            Text(
                "设置",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = TgFeedTokens.TextHigh,
            )
        }

        // ---------- 分组卡片流：卡片间 16dp ----------
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapLg),
        ) {
            // ---------------- 播放（R198 目标组①：播放体验） ----------------
            SettingsCard("播放") {
                SettingsSwitchRow(
                    title = "自动连播（默认开）",
                    subtitle = "这一条播完后自动接着播下一条",
                    checked = autoAdvance,
                    onCheckedChange = onSetAutoAdvance,
                    help = "做什么：当前内容播完不用动手，自动切下一条。\n默认：开。\n生效：即时。",
                )
                SettingsSwitchRow(
                    title = "音量均衡（默认开）",
                    subtitle = "自动把不同内容的响度拉到一致，不用频繁调音量",
                    checked = volumeNormalize,
                    onCheckedChange = {
                        volumeNormalize = it
                        prefs.volumeNormalize = it
                    },
                    help = "做什么：每条内容自动对一次响度，偏大声/偏小声的拉到同一水平，" +
                        "不用频繁按音量键。\n默认：开。\n生效：即时（正在播的这条从下一段缓冲起变化）。",
                )
                SettingsSwitchRow(
                    title = "后台音频播放（默认关）",
                    subtitle = "切到别的应用或锁屏时，声音继续播",
                    checked = audioBg,
                    onCheckedChange = {
                        audioBg = it
                        prefs.audioBackground = it
                    },
                    help = "做什么：切后台/锁屏后当前内容继续出声（把视频当播客听）。\n默认：关。\n" +
                        "生效：即时。\n已知取舍：开启后有一条常驻通知（系统对后台播放的硬性要求），" +
                        "略微增加耗电。",
                )
                // R180 需求②：音视频共存开关（默认关=互斥）。两态语义收进问号气泡，
                // 副文案只留一行人话（R198 文案分工）。
                SettingsSwitchRow(
                    title = "音频与视频同时播放（默认关）",
                    subtitle = "关：二者互斥，切页都暂停；开：可同时出声，切页后音频继续播",
                    checked = allowSimul,
                    onCheckedChange = {
                        allowSimul = it
                        // 经容器：落盘 + 即时切换共享音频播放器的焦点策略
                        appContainer.setAllowAudioVideoSimultaneous(it)
                    },
                    help = "做什么：控制音频和视频能不能同时出声。\n默认：关（互斥——起音频自动停" +
                        "视频、起视频自动停音频，切换页面时都暂停）。\n开启后：可同时出声；切到其他" +
                        "页面音频继续播（背景听），视频照常暂停。\n生效：即时。\n已知取舍：开启后音频" +
                        "不再随来电等系统焦点自动暂停。",
                )
                SettingsSwitchRow(
                    title = "弹幕显示（默认开）",
                    subtitle = "有评论的内容会把评论以弹幕形式滚过画面",
                    checked = danmakuOn,
                    onCheckedChange = {
                        danmakuOn = it
                        prefs.danmakuEnabled = it
                    },
                    help = "做什么：把内容的评论变成飘过画面的弹幕。\n默认：开。",
                )
                // 后台行为：切后台时 继续播放 / 暂停 / 自动悬浮窗（单选 chips，用全局面板统一的蓝底选中语言）
                Column(verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
                    // B7：分组小标题显式指定 TextMedium（白 70%）——不指定颜色时是深色字压深色卡片，几乎看不见
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "切换至后台时（默认继续播放）",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TgFeedTokens.TextMedium,
                        )
                        SettingsHelpHint(
                            title = "切换至后台时",
                            help = "做什么：切到别的应用或锁屏时，播放器怎么办。\n默认：继续播放" +
                                "（需开启「后台音频播放」承载，未开启时自动按暂停兜底省电）。\n" +
                                "三档：继续播放 / 暂停 / 自动弹悬浮窗。\n生效：即时。",
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
                        listOf(0 to "继续播放", 1 to "暂停", 2 to "自动悬浮窗").forEach { (v, label) ->
                            val selected = backgroundAction == v
                            // 选中态 150ms 过渡（全局节律 AnimFast），不再硬切换色
                            // R178：注释说 AnimFast、代码却写 150 裸字面量——补上引用
                            val chipBg by animateColorAsState(
                                if (selected) TgFeedTokens.TgBlue.copy(alpha = 0.18f)
                                else Color.White.copy(alpha = 0.08f),
                                tween(TgFeedTokens.AnimFast),
                                label = "chipBg",
                            )
                            val chipText by animateColorAsState(
                                if (selected) TgFeedTokens.TgBlue else TgFeedTokens.TextHigh,
                                tween(TgFeedTokens.AnimFast),
                                label = "chipText",
                            )
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                                    .background(chipBg)
                                    .then(
                                        // 选中态补 0.5dp 蓝发丝描边（GRID-REDESIGN 9.2 面板 chip 统一语言）
                                        if (selected) Modifier.border(
                                            0.5.dp,
                                            TgFeedTokens.TgBlue.copy(alpha = 0.5f),
                                            RoundedCornerShape(TgFeedTokens.RadiusPill),
                                        ) else Modifier,
                                    )
                                    .clickable {
                                        backgroundAction = v
                                        prefs.backgroundAction = v
                                    }
                                    .padding(
                                        horizontal = TgFeedTokens.GapMd,
                                        vertical = TgFeedTokens.GapSm,
                                    ),
                            ) {
                                Text(
                                    label,
                                    color = chipText,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                    // R158 行为兜底提示：0 档"继续播放"的合规承载是后台音频 FGS；
                    // 未开启时切后台自动暂停（省电），用户在设置页就能看到口径
                    if (backgroundAction == 0 && !audioBg) {
                        Text(
                            "后台音频播放未开启时，切后台将自动暂停播放以省电（可开启「后台音频播放」保持续播）",
                            style = MaterialTheme.typography.bodySmall,
                            color = TgFeedTokens.TextMedium,
                        )
                    }
                }
                // 重置手势引导标记：回 Feed/分屏时重新显示一次
                SettingsActionRow(
                    icon = Icons.Outlined.TouchApp,
                    title = "重新显示手势操作引导",
                    subtitle = "返回 Feed / 分屏后将再次展示一次新手引导",
                ) {
                    prefs.firstRunFeed = true
                    prefs.firstRunGrid = true
                    // R174 捏合清屏教学气泡同门复位（提案 §3.2：该行覆盖一次性 flag）
                    prefs.pinchCleanGuideShown = false
                    Toast.makeText(context, "已重置，返回后即显示手势引导", Toast.LENGTH_SHORT).show()
                }
                // 播放列表：自建片单整单连播（R198 自「存储管理」卡迁入——播放语境归家）
                SettingsActionRow(
                    icon = Icons.Outlined.PlaylistPlay,
                    title = "播放列表",
                    subtitle = "自建的片单，整单连播",
                    trailing = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = TgFeedTokens.TextLow,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = { appContainer.openPlaylists() },
                )
            }

            // ---------------- 内容类型（R198 目标组②：想看什么） ----------------
            SettingsCard("内容类型") {
                Text(
                    "不想刷到的类型可以关掉（三种默认全开）；关闭后刷到的内容里不再出现该类型",
                    style = MaterialTheme.typography.bodySmall,
                    color = TgFeedTokens.TextMedium,
                )
                SettingsSwitchRow(
                    title = "视频",
                    subtitle = null,
                    checked = showVideos,
                    onCheckedChange = {
                        showVideos = it
                        prefs.showVideos = it
                    },
                )
                SettingsSwitchRow(
                    title = "图片",
                    subtitle = null,
                    checked = showPhotos,
                    onCheckedChange = {
                        showPhotos = it
                        prefs.showPhotos = it
                    },
                )
                SettingsSwitchRow(
                    title = "音频",
                    subtitle = null,
                    checked = showAudio,
                    onCheckedChange = {
                        showAudio = it
                        prefs.showAudio = it
                    },
                )
                // 三个全关时给一句警示：Feed 会空，用户大概率是误操作
                if (!showVideos && !showPhotos && !showAudio) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = TgFeedTokens.WarningRed,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            "三种类型均已关闭，内容流将为空",
                            style = MaterialTheme.typography.bodySmall,
                            color = TgFeedTokens.WarningRed,
                        )
                    }
                }
            }

            // ---------------- 省流与存储（R198 目标组③：流量与空间——原「存储管理」升级） ----------------
            // 「省流模式」（R195 整载，架构师改名令）与「WiFi 自动缓存追更」两个下载行为
            // 开关归家于此；存储概览/缓存上限/清理/我的下载自原存储管理卡保留。
            SettingsCard("省流与存储") {
                Text(
                    "看过的内容会留在手机里，回看更快；这里管它占多少空间、什么时候清",
                    style = MaterialTheme.typography.bodySmall,
                    color = TgFeedTokens.TextMedium,
                )
                // R195 WiFi 整载（R198 架构师改名令：用户拍板「WiFi 激进预载」→「省流模式」；
                // AppPrefs 键 aggressivePreload 不动——持久化兼容）。两态语义：
                // 开（默认）=WiFi 下观看时后台把当前视频整文件下满（带宽跑满、拖动/回看零等待），
                // 当前条下完后顺手预载下 1~2 条；关或计量网络=自动回落精瘦窗口模式
                // （只拉播放位前方小缓冲窗，省流量）。翻转 ≤1s 生效（网关侧 1Hz 决策循环每拍直读）。
                var aggressivePreload by remember { mutableStateOf(prefs.aggressivePreload) }
                SettingsSwitchRow(
                    title = "省流模式（默认开）",
                    subtitle = "连 WiFi 时自动下完整条视频——重看秒开、出门不耗流量",
                    checked = aggressivePreload,
                    onCheckedChange = {
                        aggressivePreload = it
                        prefs.aggressivePreload = it
                    },
                    help = "做什么：看视频时后台顺手把整条下完——拖进度条、回看秒开，不用等缓存；" +
                        "当前条下完还会预备接下来的 1~2 条。\n默认：开。\n生效：约 1 秒内，不用重启。\n" +
                        "机理：仅 WiFi 下整条下载；用移动数据时自动改精简缓冲（只拉正在看的一小段），" +
                        "不耗流量。\n已知取舍：WiFi 带宽会被占满（同网络有人在打游戏/开会时可酌情关闭）。",
                )
                // WiFi 自动缓存追更（R198 自「订阅与追更」卡迁入——下载/存储语境归家）：
                // 连上 WiFi 时自动把订阅频道的新视频缓存进应用内私有缓存（离线可刷）
                var wifiPrefetch by remember { mutableStateOf(prefs.wifiPrefetch) }
                SettingsSwitchRow(
                    title = "WiFi 自动缓存追更（默认开）",
                    subtitle = "连上 WiFi 自动把订阅频道的新视频下到应用内，出门没网也能刷",
                    checked = wifiPrefetch,
                    onCheckedChange = {
                        wifiPrefetch = it
                        prefs.wifiPrefetch = it
                        vm.setWifiPrefetch(it)
                    },
                    help = "做什么：连上 WiFi 后自动把订阅频道 24 小时内的新视频下载到应用内" +
                        "（不占相册、不进下载列表），出门没网也能刷追更。\n默认：开。\n" +
                        "生效：连上 WiFi 后自动开始（每轮至多 20 条、串行低优先级，不抢带宽）。\n" +
                        "已知取舍：占用存储（受下方缓存上限约束）。",
                )
                // 总占用概览（盘测分项：深索引 / 视频缓存 / 临时文件 / 消息库；null=量取中）
                // 注：delegate 属性不能 smart cast，取 .value 到本地 val 再判空
                val storageOverviewState = vm.storageOverview.collectAsState()
                val storageOverviewVal = storageOverviewState.value
                if (storageOverviewVal != null) {
                    Text(
                        "总占用 ${fmtGb(storageOverviewVal.totalBytes)} GB",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TgFeedTokens.TextHigh,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "深索引 ${fmtGb(storageOverviewVal.deepIndexBytes)} GB · 视频缓存 ${fmtGb(storageOverviewVal.tdlibCacheBytes)} GB · " +
                            "临时文件 ${fmtGb(storageOverviewVal.tempBytes)} GB · 消息库 ${fmtGb(storageOverviewVal.messageDbBytes)} GB",
                        style = MaterialTheme.typography.bodySmall,
                        color = TgFeedTokens.TextMedium,
                    )
                } else {
                    Text("总占用 量取中…", style = MaterialTheme.typography.bodySmall, color = TgFeedTokens.TextLow)
                }
                // 缓存水位上限：数字输入 + 即时保存；授权后超限自动清最旧视频文件
                LabeledPillField(
                    label = "缓存上限（GB）",
                    value = cacheLimitGb,
                    onValueChange = { input ->
                        cacheLimitGb = input
                        input.trim().toIntOrNull()?.let { prefs.cacheLimitGb = it }
                    },
                    hint = "默认 3",
                    keyboardType = KeyboardType.Number,
                    help = "做什么：已缓存视频总量超过这个数（GB）时，自动删最旧的。\n默认：3。\n" +
                        "生效：重启应用后。\n填 0 = 不自动清理（只手动清）。",
                )
                Text(
                    "视频缓存超限即清理最旧文件，重启生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = TgFeedTokens.TextMedium,
                )
                FilledTonalButton(
                    onClick = {
                        cleaning = true
                        vm.cleanupStorage { msg ->
                            cleaning = false
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    },
                    enabled = !cleaning,
                    shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
                    // 同深度索引按钮：卡片上需白 α0.10 才能与卡片底分层
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.White.copy(alpha = 0.10f),
                        contentColor = TgFeedTokens.TextHigh,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) {
                    Icon(
                        Icons.Outlined.CleaningServices,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        if (cleaning) "清理中…" else "立即清理",
                        modifier = Modifier.padding(start = TgFeedTokens.GapSm),
                    )
                }
                // 清理日志行（R196 任务书 2c：上次清理时间/释放量；governor 每轮落 prefs）
                val cleanLogVal = vm.storageCleanLog.collectAsState().value
                if (cleanLogVal.isNotEmpty()) {
                    Text(
                        cleanLogVal,
                        style = MaterialTheme.typography.bodySmall,
                        color = TgFeedTokens.TextMedium,
                    )
                }
                // 我的下载：已存相册的记录管理页（列表 / 播放 / 删除），导航行样式 + 尾部箭头
                SettingsActionRow(
                    icon = Icons.Outlined.FileDownload,
                    title = "我的下载",
                    subtitle = "管理已保存到相册的内容",
                    trailing = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = TgFeedTokens.TextLow,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = onOpenDownloads,
                )
            }

            // ---------------- 发现新内容（R198 目标组④：原「订阅与追更」改名归位） ----------------
            // R197 协调：原「我的搜索群」卡已由 R197 迁往发现页（设置页只留一行跳转，
            // 打开与发现页同款的选择器 sheet）——该跳转行按「已迁发现页」口径收编进本卡。
            SettingsCard("发现新内容") {
                // 关键词订阅：逗号分隔多个词，即时保存；Feed 侧 showKeywordFeed 切到关键词订阅源
                LabeledPillField(
                    label = "关键词订阅",
                    value = keywordSubs,
                    onValueChange = { input ->
                        keywordSubs = input
                        prefs.keywordSubscriptions = input
                    },
                    hint = "多个词用逗号分隔，如：教程,评测",
                    help = "做什么：填几个你关心的词（逗号分隔），带这些词的新内容会进「追更」源，" +
                        "不会淹没在主页推荐里。\n保存：输入即存。\n提醒：配合下方「订阅更新通知」" +
                        "可收到到货提醒；想被喊到的冷门词还可以在高级区「需求订阅」里登记。",
                )
                Text(
                    "订阅后相关新内容进追更源",
                    style = MaterialTheme.typography.bodySmall,
                    color = TgFeedTokens.TextMedium,
                )
                // R148 订阅更新通知（合并开关）：订阅频道有新帖（追更）+ 订阅关键词到货
                // （需求订阅）合并为同一渠道一条汇总通知（"N 个频道有更新 · M 条关键词到货"），
                // 点开直达内容/Feed；翻转同时写两把旧钥匙（notify_new_content + demand_notify_enabled）
                // 并同步 VM 内存态，两侧调度链下一轮即生效
                SettingsSwitchRow(
                    title = "订阅更新通知（默认开）",
                    subtitle = "订阅的频道有更新、订阅的词有到货时，发一条汇总通知，点通知直达内容",
                    checked = notifyNewContent,
                    onCheckedChange = {
                        notifyNewContent = it
                        prefs.notifyNewContent = it
                        prefs.demandNotifyEnabled = it
                        vm.setNotifyNewContent(it) // 同步 VM 内存态，追更轮询下一轮即生效
                    },
                    help = "做什么：订阅频道有新帖、订阅的关键词到货时，发一条汇总系统通知" +
                        "（「N 个频道有更新 · M 条关键词到货」），点开直达内容。\n默认：开。\n" +
                        "频率：频道更新每 15 分钟检查一次；关键词到货即时提醒。\n生效：翻转后下一轮" +
                        "检查起（追更侧即时同步内存态）。\n已知取舍：需要系统通知权限（没授权时" +
                        "到系统设置里开启）。",
                )
                // R356 口味星图设置整体内迁（用户 08:1x 拍板：设置不该放星图
                // 的东西——五行退役：互链聚拢/互链连线/涌现布局/星座/宇宙底幕；
                // AppPrefs 键与默认值零变化，入口=星图内「星图开关」图标快捷组，
                // 写键/vm 同步语句随迁 TasteMapScreen 舞台件=迁移语义零变化）。
                // 此处只留一行指针防老用户迷路（区块收拢无空洞——卡内其余行原位）：
                Text(
                    "口味星图的开关已收进星图内（右上角图标）",
                    style = MaterialTheme.typography.bodySmall,
                    color = TgFeedTokens.TextLow,
                )
                // R345：星图渲染引擎三态选择行退役（Canvas 老引擎已删——GL 恒为唯一
                // 引擎，选择无语义；旧 render_engine 键读侧归一化见 ui/gl/GlEnginePolicy
                // R345 收口注释+TasteMapScreen 解析日志一行）

                // R197 我的搜索群：发现模块属地，设置页只留一行跳转（打开与发现页同款
                // 选择器 sheet；勾选后群友搜过的词会变成发现页候选的活数据源）
                SettingsActionRow(
                    icon = Icons.Outlined.Groups,
                    title = "我的搜索群",
                    subtitle =
                        if (harvestGroupCount > 0) "已勾选 ${harvestGroupCount} 个群 · 换批/加群去发现页入口管理"
                        else "还没勾选搜索群——勾选后群友搜过的词会变成发现页候选",
                    trailing = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = TgFeedTokens.TextLow,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = { showHarvestPicker = true },
                )
                // R198 渐进披露指针：需求订阅/广场/深度搜索等发现向低频配置在高级折叠区
                Text(
                    "更多发现相关设置（需求订阅、广场、深度搜索等）收在下方「高级设置」",
                    style = MaterialTheme.typography.bodySmall,
                    color = TgFeedTokens.TextLow,
                )
            }

            // ---------------- 账号与数据（R198 目标组⑤：原「连接」卡升级 + 点赞回写/观看报告归家） ----------------
            // API 凭证对用户完全隐藏——那是开发者关心的东西（编译期内置，界面不出现）
            SettingsCard("账号与数据") {
                SettingsSwitchRow(
                    title = "网络代理（默认开）",
                    subtitle = "配置的代理连不上时自动检测本机代理端口；系统 VPN 用户可关闭",
                    checked = proxyEnabled,
                    onCheckedChange = { proxyEnabled = it },
                    help = "做什么：经本机代理（默认 127.0.0.1:7897）连接 Telegram，" +
                        "让消息收发走代理网络。\n默认：开。\n生效：点「保存并重连」重连后。\n" +
                        "已知取舍（R244）：代理连不上时自动探测本机常见代理端口" +
                        "（Clash/v2rayNG 等，命中即接入并记住）；全都找不到才直连。" +
                        "设备已开系统级 VPN 时无需本代理，可关闭省电。",
                )
                if (proxyEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
                        LabeledPillField(
                            label = "主机",
                            value = proxyHost,
                            onValueChange = { proxyHost = it },
                            hint = "127.0.0.1",
                            modifier = Modifier.weight(2f),
                        )
                        LabeledPillField(
                            label = "端口",
                            value = proxyPort,
                            onValueChange = { proxyPort = it },
                            hint = "7897",
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Button(
                    onClick = {
                        // 凭证区已对用户隐藏：填了才覆盖，留空 = 保留现有凭证不动
                        val id = apiId.trim().toIntOrNull() ?: 0
                        val port = proxyPort.trim().toIntOrNull() ?: 7897
                        onApplyConfig(id, apiHash.trim(), proxyEnabled, proxyHost.trim(), port)
                    },
                    // 凭证隐藏后按钮恒可点（代理设置随时可改；凭证覆盖是可选附带）
                    enabled = true,
                    shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) { Text("保存并重连", fontWeight = FontWeight.Bold) }
                // ---------------- 点赞回写（R128；R198 自独立卡并入——账号公开行为语境归家） ----------------
                // 双击点赞顺手给原频道该消息补一个 👍 Reaction，让点赞回到内容生态。
                // 写操作对频道可见（频道主/管理员能看 Reaction 名单），故默认关、显式知情开启
                SettingsSwitchRow(
                    title = "点赞同步到原频道（默认关）",
                    subtitle = "双击点赞时，用你的 TG 账号给原频道那条消息补一个 👍",
                    checked = likeWriteback,
                    onCheckedChange = {
                        likeWriteback = it
                        prefs.likeReactionWriteback = it
                    },
                    help = "做什么：双击点赞时，以你的 Telegram 账号给原频道那条消息补一个 👍，" +
                        "让点赞回到内容生态（频道主能看到支持）。\n默认：关（回写是公开行为，" +
                        "须你显式开启）。\n生效：即时。\n隐私：点赞名单频道主/管理员可见；" +
                        "取消点赞不撤回已补的 👍（可在 Telegram 客户端手动撤销）。",
                )
                Text(
                    "隐私说明：Reaction 以你的 TG 账号公开留在原频道消息上，频道主/管理员可见；关闭后停止回写，已留的 Reaction 可在 Telegram 客户端手动撤销",
                    style = MaterialTheme.typography.bodySmall,
                    color = TgFeedTokens.TextMedium,
                )
                // 观看报告：本周观看时长 / 内容偏好 / 常看频道 / 时段分布（R198 自「存储管理」迁入）
                SettingsActionRow(
                    icon = Icons.Outlined.History,
                    title = "观看报告",
                    subtitle = "本周观看时长、偏好分布与最常看的频道",
                    trailing = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = TgFeedTokens.TextLow,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = onOpenWatchReport,
                )
            }

            // ---------------- 高级折叠区开始（SettingsIaPolicyTest 锚点，勿删） ----------------
            // R198 渐进披露：低频配置收进折叠区，默认收起、一行计数提示「高级 · N 项」。
            // 动效口径（MotionTokens）：进场 fadeIn(AnimMed)+expandVertically(AnimSlow)，
            // 退场 fadeOut(AnimFast)+shrinkVertically(AnimMed)（退场 ≤ 进场）；箭头旋转 AnimMed。
            // 折叠收起时区内卡片移出组合——索引状态/诊断地址等展示型轮询随停（功能本体
            // 在 VM/网关侧照常运行，展开即恢复刷新），属折叠层语义的预期行为。
            var advancedExpanded by rememberSaveable { mutableStateOf(false) }
            val advancedChevron by animateFloatAsState(
                targetValue = if (advancedExpanded) 180f else 0f,
                animationSpec = tween(TgFeedTokens.AnimMed),
                label = "advancedChevron",
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(SettingsCardRadius))
                    .background(TgFeedTokens.SurfaceHigh)
                    .clickable { advancedExpanded = !advancedExpanded }
                    .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapMd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
            ) {
                Text(
                    "高级设置",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = TgFeedTokens.TextHigh,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "高级 · ${AdvancedVisibleCount} 项",
                    style = MaterialTheme.typography.bodySmall,
                    color = TgFeedTokens.TextLow,
                )
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = if (advancedExpanded) "收起高级设置" else "展开高级设置",
                    tint = TgFeedTokens.TextLow,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(advancedChevron),
                )
            }
            AnimatedVisibility(
                visible = advancedExpanded,
                enter = fadeIn(tween(TgFeedTokens.AnimMed)) +
                    expandVertically(tween(TgFeedTokens.AnimSlow)),
                exit = fadeOut(tween(TgFeedTokens.AnimFast)) +
                    shrinkVertically(tween(TgFeedTokens.AnimMed)),
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapLg),
                ) {
                // ---------------- 内容源策略（R198 收进高级区） ----------------
                SettingsCard("内容源策略") {
                    SettingsSwitchRow(
                        title = "抓取评论区资源",
                        subtitle = "部分频道的资源发布在帖子评论区，开启后自动级联抓取",
                        checked = commentCascade,
                        onCheckedChange = {
                            commentCascade = it
                            gateway.includeCommentResources = it
                        },
                        help = "做什么：有些频道把资源链接发在帖子评论区，开启后抓内容时会顺带" +
                            "把评论区里的资源一起抓回来。\n默认：开。\n生效：下一轮抓取起。",
                    )
                    SettingsSwitchRow(
                        title = "已看内容不再出现",
                        subtitle = "看过的内容从推荐/关注/追更里剔除；收藏、历史、搜索不受影响",
                        checked = excludeWatched,
                        onCheckedChange = {
                            excludeWatched = it
                            prefs.excludeWatched = it
                            vm.setExcludeWatched(it)
                        },
                        help = "做什么：看过的内容从推荐流、关注流、追更置顶与老内容回溯里剔除——" +
                            "同一条视频被转发到多个频道也能认出来（按内容指纹判重）。\n默认：开。\n" +
                            "关闭后：看过的内容只是排后一些，仍可能出现。\n不受影响：收藏夹、历史、" +
                            "搜索、合集等列表。",
                    )
                }

                // ---------------- 需求订阅（金矿 P4-B：搜不到的东西出现了就喊我；R198 收进高级区） ----------------
                // 搜索页"订阅这个词"的订阅词全在这里可加可管可删；收割管道（GoldmineHooks →
                // DemandSubscription）每轮入库的新链接命中订阅词即并入订阅更新汇总通知
                // （R148：与追更共用"订阅更新通知"开关与渠道，本卡不再有独立通知开关），
                // 点通知深链直达内容。
                // R148 可达性修复：订阅入口原来只有搜索页**空态**一条路——重用户（关注大量
                // 频道，搜索几乎从不落空）永远到不了空态，于是"明明关注那么多频道，需求订阅
                // 却显示暂无订阅"；现在搜索页两态都露入口（SearchScreen），本卡再加直接加词框，
                // 双路保底。
                SettingsCard("需求订阅") {
                    Text(
                        "搜索页可一键订阅当前词，这里也可直接添加；资源群收割到新链接命中订阅词时并入「订阅更新通知」（开关在「发现新内容」卡），点通知直达内容",
                        style = MaterialTheme.typography.bodySmall,
                        color = TgFeedTokens.TextMedium,
                    )
                    // 订阅词列表数据源（添加/删除后即时重读刷新）
                    var demandSubs by remember { mutableStateOf(DemandSubscription.list()) }
                    // 直接加词框（R148 可达性修复的一部分）：输入 + 添加按钮，走
                    // DemandSubscription.add 同一条归一/去重/硬顶链路；失败原因 Toast 直说
                    var newDemandWord by remember { mutableStateOf("") }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                    ) {
                        LabeledPillField(
                            label = "添加订阅词",
                            value = newDemandWord,
                            onValueChange = { newDemandWord = it },
                            hint = "想搜但常搜不到的词，如：绝版教材",
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "添加",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (newDemandWord.isBlank()) TgFeedTokens.TextLow else TgFeedTokens.TgBlue,
                            modifier = Modifier
                                .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                                .clickable(enabled = newDemandWord.isNotBlank()) {
                                    val word = newDemandWord.trim()
                                    if (DemandSubscription.add(word)) {
                                        newDemandWord = ""
                                        demandSubs = DemandSubscription.list()
                                        Toast.makeText(context, "已订阅「$word」，资源到货会通知你", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "订阅失败：词非法或订阅数已满", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    if (demandSubs.isEmpty()) {
                        Text(
                            "暂无订阅（搜索页或上方输入框都可添加）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TgFeedTokens.TextLow,
                        )
                    } else {
                        demandSubs.forEach { word ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
                                    .padding(vertical = TgFeedTokens.GapSm),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
                            ) {
                                Box(
                                    Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.08f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Outlined.Notifications,
                                        contentDescription = null,
                                        tint = TgFeedTokens.TextMedium,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Text(
                                    word,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = TgFeedTokens.TextHigh,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "删除",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = TgFeedTokens.WarningRed,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                                        .clickable {
                                            DemandSubscription.remove(word)
                                            demandSubs = DemandSubscription.list()
                                            Toast.makeText(context, "已删除订阅「$word」", Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }

                // ---------------- TG 广场（R198 收进高级区） ----------------
                // 去中心化全站动态：一个公开 TG 超级群组 = 广场，开启后「在看/点赞/弹幕」
                // 以本人 TG 账号写入，其他用户读同一个群即看到全站动态（零服务器）
                SettingsCard("广场") {
                    if (plazaService == null) {
                        Text(
                            "演示模式（Mock）下不可用——用真实 TG 账号登录后可开启",
                            style = MaterialTheme.typography.bodySmall,
                            color = TgFeedTokens.TextMedium,
                        )
                    } else {
                        SettingsSwitchRow(
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
                            help = "做什么：把你的「在看 / 点赞 / 弹幕」用你的 TG 账号发到一个公共频道，" +
                                "其他用户读同一个频道就能看到全站动态（无需任何服务器）。\n默认：关。\n" +
                                "生效：开启时加入广场频道（需网络）。\n隐私：公开发送，频道内所有人" +
                                "（含非本 App 用户）可见发送者身份；关闭后已发的消息仍留在频道中" +
                                "（可在 Telegram 客户端手动删除）。",
                        )
                        Text(
                            "隐私说明：动态以你的 TG 账号公开发送，频道内所有人（含非本 App 用户）可见发送者身份；关闭后停止发送，但已发出的消息仍保留在频道中（可在 Telegram 客户端手动删除）",
                            style = MaterialTheme.typography.bodySmall,
                            color = TgFeedTokens.TextMedium,
                        )
                        // R361 开源收口：release 锁死官方广场名（全站互通前提）——
                        // 输入框隐藏，只读行展示官方名；debug（作者通道）保留自定义输入
                        if (BuildConfig.DEBUG) {
                            LabeledPillField(
                                label = "广场频道用户名",
                                value = plazaChannel,
                                onValueChange = { input ->
                                    plazaChannel = input
                                    prefs.plazaChannel = input // 即时保存；setter 内 trim + 去 @，空白读取侧回落默认
                                },
                                hint = "不含 @，留空默认 ${AppPrefs.PLAZA_CHANNEL_OFFICIAL}",
                                help = "做什么：全站用户填同一个频道名才能互相看到动态。\n默认：${AppPrefs.PLAZA_CHANNEL_OFFICIAL}。\n" +
                                    "修改后请把广场开关关开一次以加入新频道。",
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
                        // 广场建好后藏起"我是广场创建者"（建群是一次性的事，建完这个开关再没用了）。
                        // R361：release 构建整开关隐藏（广场创建=作者 debug 构建专属，正式版只找不建）
                        if (BuildConfig.DEBUG && !prefs.plazaCreated) {
                            SettingsSwitchRow(
                                title = "我是广场创建者",
                                subtitle = "找不到广场频道时，以我的账号创建它（仅第一个使用者需要开启）",
                                checked = plazaOwner,
                                onCheckedChange = {
                                    plazaOwner = it
                                    prefs.plazaOwner = it
                                },
                                help = "做什么：广场频道不存在时，用你的账号创建它。\n默认：关。\n" +
                                    "仅第一个使用者需要开启；建好后此开关自动隐藏。",
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
                }

                // ---------------- 跨设备续播（R198 收进高级区） ----------------
                // 观看位置经 Telegram 收藏夹（Saved Messages）云端同步：手机刷一半平板接着放。
                // 数据是明文 JSON 存在你自己的收藏夹里（仅本人可见）。R198 勘误：旧注释写
                // 「开关默认关」，AppPrefs 实为默认开（progress_sync_enabled=true）——注释与
                // 文案一并纠正。
                SettingsCard("跨设备续播") {
                    SettingsSwitchRow(
                        title = "观看进度云端同步（默认开）",
                        subtitle = "观看位置存到 Telegram 收藏夹，换设备接着看（明文存在你自己的收藏夹，仅本人可见）",
                        checked = progressSyncEnabled,
                        onCheckedChange = {
                            progressSyncEnabled = it
                            prefs.progressSyncEnabled = it
                        },
                        help = "做什么：观看位置存到你自己的 Telegram 收藏夹（仅本人可见），" +
                            "手机刷一半平板接着放。\n默认：开。\n生效：即时（平时自动节流推送，" +
                            "也可点「立即同步」手动推拉）。\n隐私：数据是明文 JSON，只发往你自己的" +
                            "收藏夹，不对任何第三方可见。",
                    )
                    // R300：四域状态同步（与进度域同通道同隐私口径；载荷只搬 key+时刻不搬条目本体）
                    SettingsSwitchRow(
                        title = "收藏/稍后/订阅/屏蔽云端同步（默认开）",
                        subtitle = "收藏、稍后再看、追更订阅与已读进度、屏蔽名单在同账号设备间自动对齐；同步数据只存你自己的 Telegram 收藏夹（仅本人可见）",
                        checked = cloudSyncEnabled,
                        onCheckedChange = {
                            cloudSyncEnabled = it
                            prefs.cloudSyncEnabled = it
                        },
                        help = "做什么：收藏、稍后再看、追更订阅（含看到哪条的水位）、屏蔽名单" +
                            "自动同步到同账号的其他设备，换设备不重复刷旧内容、屏蔽处处生效。\n" +
                            "默认：开。\n生效：即时（动作后节流推送；其他在线设备秒级收到，" +
                            "离线设备下次启动/每 15 分钟轮询拉齐）。\n" +
                            "隐私：载荷只有条目编号和时刻（不含标题正文），明文 JSON 只发往你自己的" +
                            "收藏夹，不对任何第三方可见。",
                    )
                    // 立即同步：先推本机再拉云端合入
                    // R324 离线降级：离线时禁用（推拉全走网络，离线点击必败——禁用
                    // 动作的按钮态=诚实口径「不假装在努力」）；联网即时恢复可点
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
                            .clickable(enabled = progressSyncEnabled && networkOnline) {
                                scope.launch {
                                    vm.syncProgressPush()
                                    vm.syncProgressPull { msg ->
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "立即同步（先推本机再拉云端）",
                            color = if (progressSyncEnabled && networkOnline) {
                                TgFeedTokens.TgBlue
                            } else {
                                TgFeedTokens.TextLow
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    // R324 离线行（OfflineDegradePolicy 单源文案）：离线时给同步暂停
                    // 的诚实解释——多设备同步面在离线矩阵里是「不可用」档（自动推拉
                    // 静默停摆是既有设计，本行只补可见性，不改任何同步逻辑）
                    dev.tgfeed.core.net.OfflineDegradePolicy.syncOfflineLine(networkOnline)?.let { line ->
                        Text(
                            line,
                            color = TgFeedTokens.TextLow,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                        )
                    }
                }

                // ---------------- 深度搜索（R198 收进高级区） ----------------
                SettingsCard("深度搜索") {
                    // 搜索机器人：第三方全网搜索（机器人遍历公开频道自建索引，私聊发关键词回资源链接）；
                    // Feed 搜索为空态时的"机器人搜索"按钮经它搜全网，不限于已订阅/已索引频道
                    LabeledPillField(
                        label = "搜索机器人（可多个，逗号分隔）",
                        value = searchBot,
                        onValueChange = { input ->
                            searchBot = input
                            // 第一个=主机器人，其余=备用（自动轮换）；都不用用户维护
                            val parts = input.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
                            prefs.searchBotUsername = parts.firstOrNull() ?: ""
                            prefs.searchBotFallbacks = parts.drop(1).joinToString(",")
                        },
                        hint = "不含 @，如 jisou,soso（第一个挂了自动换下一个）",
                        help = "做什么：第三方全网搜索机器人——把关键词私聊发给它，它回公开频道里" +
                            "搜到的资源链接；搜索为空时可用它搜全网（不限于已订阅/已索引频道）。\n" +
                            "多个用逗号分隔：第一个是主力，挂了自动换下一个，不用你管。",
                    )
                    Text(
                        "第三方全网搜索机器人：搜索为空时可用它搜全网公开资源；多个自动轮换，不用管",
                        style = MaterialTheme.typography.bodySmall,
                        color = TgFeedTokens.TextMedium,
                    )
                    Text(
                        "服务端搜索对历史内容的索引较浅。建立本地全量索引后，可检索频道的全部历史内容（首次建立耗时较长，进度将实时显示）",
                        style = MaterialTheme.typography.bodySmall,
                        color = TgFeedTokens.TextMedium,
                    )
                    // 自动建立索引（默认开）：打开 App 就在后台自动建/续全部频道的索引，
                    // 后续更新也自动——用户完全不关心索引这件事。关掉才需要手动点下面的按钮。
                    var autoIndex by remember { mutableStateOf(prefs.autoDeepIndex) }
                    SettingsSwitchRow(
                        title = "自动建立索引",
                        subtitle = "打开 App 就在后台自动建/续全部频道+群组的索引（含后续更新）；索引只存文字元数据（不含媒体文件），占用随频道规模增长，实际占用见下方状态区",
                        checked = autoIndex,
                        onCheckedChange = {
                            autoIndex = it
                            prefs.autoDeepIndex = it
                        },
                        help = "做什么：打开 App 就在后台自动给全部频道建/续本地索引，建好后能搜到" +
                            "频道的全部历史内容（服务端搜索只覆盖近期浅层）。\n默认：开。\n" +
                            "存储：只存文字元数据（不含视频文件），占用随频道数增长（见下方状态行）。\n" +
                            "生效：下次启动起；首次建立耗时较长，进度实时显示。",
                    )
                    // ---------------- R163 智能索引（默认全关；「改了什么」弹窗给用户终审用） ----------------
                    var tieringOn by remember { mutableStateOf(prefs.indexTieringEnabled) }
                    var timingOn by remember { mutableStateOf(prefs.smartCrawlTiming) }
                    var exploreOn by remember { mutableStateOf(prefs.indexExploreQuota) }
                    var demoteOn by remember { mutableStateOf(prefs.deadMediaDemotion) }
                    // 修订令：索引库存储预算（GB，0/空=关=只看磁盘水位线）
                    var evictBudgetGb by remember { mutableStateOf(prefs.evictStorageBudgetGb.toString()) }
                    var showTieringChanges by remember { mutableStateOf(false) }
                    SettingsSwitchRow(
                        title = "智能索引分级",
                        subtitle = "关注的频道建最全（全部历史），常看或内容扎实的频道建深索引，新频道先小样探测、内容够好自动升级，低价值频道不占存储",
                        checked = tieringOn,
                        onCheckedChange = {
                            tieringOn = it
                            prefs.indexTieringEnabled = it
                            // 翻转即时生效：调度侧每拍读 @Volatile 运行时态（无需重启）
                            vm.indexTiering = IndexTierPolicy.TieringConfig(
                                enabled = it,
                                evictIndexBudgetBytes = evictBudgetGb.trim().toIntOrNull()
                                    ?.coerceAtLeast(0)?.toLong()?.let { gb -> gb * (1L shl 30) } ?: 0L,
                            )
                        },
                        help = "做什么：按价值给频道分四档建索引——关注的全建、常看/内容扎实的深建、" +
                            "新频道先探约 100 条（够好自动升级）、低价值不建（不占存储）。\n默认：关" +
                            "（不开时行为与以前完全一致）。\n生效：下一轮调度起。\n详见下方" +
                            "「了解智能索引改了什么」。",
                    )
                    // 索引库存储预算：数字输入 + 即时保存（0/空=关）；超预算清最低价值频道
                    LabeledPillField(
                        label = "索引库预算（GB）",
                        value = evictBudgetGb,
                        onValueChange = { input ->
                            evictBudgetGb = input
                            val gb = input.trim().toIntOrNull()?.coerceIn(0, 500) ?: 0
                            prefs.evictStorageBudgetGb = gb
                            vm.indexTiering = vm.indexTiering.copy(
                                evictIndexBudgetBytes = gb.toLong() * (1L shl 30),
                            )
                        },
                        hint = "0=不限（只看磁盘剩余空间）",
                        keyboardType = KeyboardType.Number,
                        help = "做什么：索引库体积超过这个数（GB）就清理最低价值频道（关注和" +
                            "高价值频道绝不清理）。\n默认：0（不限，只在磁盘剩余空间不足约 2GB 时清理）。",
                    )
                    Text(
                        "索引库体积超过预算时清理最低价值频道（关注的和高价值频道绝不清理）；不填则只在磁盘剩余空间不足（约 2GB）时清理",
                        style = MaterialTheme.typography.bodySmall,
                        color = TgFeedTokens.TextMedium,
                    )
                    SettingsSwitchRow(
                        title = "智能爬取时机",
                        subtitle = "后台爬取只在充电且设备闲置且不耗流量时进行（计费网络绝不爬）；搜索深挖与刚关注频道的补全不受影响",
                        checked = timingOn,
                        onCheckedChange = {
                            timingOn = it
                            prefs.smartCrawlTiming = it
                            vm.crawlTiming = IndexCrawlGate.CrawlTimingConfig(
                                smartTiming = it,
                                exploreQuotaPct = if (exploreOn) IndexCrawlGate.EXPLORE_QUOTA_PCT_DEFAULT else 0,
                            )
                        },
                        help = "做什么：后台爬取只在好时机进行——充电 + 设备闲置 + 非计费网络" +
                            "（用流量的网络绝不爬，防止耗流量）。\n默认：关（退后台即可爬）。\n" +
                            "不受限：你在等的爬取（搜索深挖、刚关注频道的补全）。\n连续一天没有好" +
                            "时机时逐级放宽，索引不会永不更新。",
                    )
                    SettingsSwitchRow(
                        title = "探索新频道",
                        subtitle = "分级开启后，约三成后台爬取预算留给未关注频道的小样探测（先看约一百条内容够不够好），防止错过没关注到的好频道",
                        checked = exploreOn,
                        onCheckedChange = {
                            exploreOn = it
                            prefs.indexExploreQuota = it
                            vm.crawlTiming = vm.crawlTiming.copy(
                                exploreQuotaPct = if (it) IndexCrawlGate.EXPLORE_QUOTA_PCT_DEFAULT else 0,
                            )
                        },
                        help = "做什么：把约三成后台爬取预算留给没关注过的频道——先各探约 100 条，" +
                            "防止错过没关注到的好频道。\n默认：开（但只在「智能索引分级」开启后才" +
                            "参与调度，分级关时无任何效果）。",
                    )
                    SettingsSwitchRow(
                        title = "坏片降权",
                        subtitle = "视频打不开会一直记账；开启后近一周坏片率超三成的频道在推荐中降权一半",
                        checked = demoteOn,
                        onCheckedChange = {
                            demoteOn = it
                            prefs.deadMediaDemotion = it
                            vm.deadMediaDemotion = DeadMediaPolicy.Config(demoteEnabled = it)
                        },
                        help = "做什么：视频打不开会记账；近一周坏片率超三成且样本足够的频道，" +
                            "在推荐里降权一半（坏链太多的频道少推）。\n默认：关（排序行为与引入前" +
                            "完全一致；记账始终进行，只是不改排序）。",
                    )
                    // 「改了什么」说明（用户语言）：默认值终审依据——开关默认全关=行为与以前完全一致
                    TextButton(
                        onClick = { showTieringChanges = true },
                        shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    ) { Text("了解智能索引改了什么", color = TgFeedTokens.TgBlue) }
                    if (showTieringChanges) {
                        AlertDialog(
                            onDismissRequest = { showTieringChanges = false },
                            title = { Text("智能索引改了什么") },
                            text = {
                                Text(
                                    "默认状态：智能索引分级 / 智能爬取时机 / 坏片降权默认关；「探索新频道」默认开（但只在分级开启后才参与调度）。不开任何开关，应用行为与以前完全一致（索引、爬取、推荐都不变）。\n\n" +
                                        "【智能索引分级】开启后，频道分四档建索引：\n" +
                                        "· 关注的频道：全部历史都建，最新内容每小时刷新\n" +
                                        "· 常看或内容扎实的频道：深索引（上限约 2 万条），6 小时刷新\n" +
                                        "· 新频道：先取约 100 条小样，按内容密度、视频占比、剧集规整度、坏片率综合评分，够好自动升级深索引——不需要先关注它\n" +
                                        "· 低价值频道：不建数据，搜到时再按需处理\n" +
                                        "存储兜底：不设总条数上限——你的索引有多大，取决于你关注和常看了多少好频道。只在两种真实情况发生时才清理：磁盘剩余空间不足（约 2GB，系统级兜底），或你自己在上面填了索引库预算。清理从最低价值频道开始整频道清理（关注的和高档频道绝不清理，清理的频道可随时重新探测）。\n\n" +
                                        "【智能爬取时机】开启后，后台爬取只在好时机进行：充电 + 设备闲置（后台且没在播放）+ 非计费网络。你在等的爬取不受限（搜索命中的深挖、刚关注频道的补全）。连续一天没有好时机会逐级放宽条件，索引不会永不更新。\n\n" +
                                        "【探索新频道】开启后，约三成爬取预算留给未关注频道的小样探测，保证没关注过的好频道也能被搜到。\n\n" +
                                        "【坏片降权】开启后，近一周坏片率超三成且样本足够的频道，推荐权重减半。",
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = { showTieringChanges = false }) { Text("知道了") }
                            },
                        )
                    }
                    // 索引状态可见（不再是黑盒）：状态徽标 + 已建频道/总会话/总条目/库大小明细
                    val indexStatus by vm.indexStatus.collectAsState()
                    // R294 P3 压实进度流（完成报告流在弹窗区收集——作用域同弹窗）
                    val compactProgress by vm.indexCompactProgress.collectAsState()
                    // 设置页打开期间每 5s 轻刷一次（DB 统计 + 文件大小均为毫秒级），
                    // 保证卡片上的数字 ≤10s 内有一次真实刷新，不显示陈旧状态
                    LaunchedEffect(Unit) {
                        while (true) {
                            vm.refreshDeepIndexStatus()
                            delay(5_000L)
                        }
                    }
                    // 状态徽标：建设中=蓝 / 已就绪=绿 / 未建立=灰（查询中也算灰）
                    val (badgeText, badgeColor) = when {
                        !indexStatus.loaded -> "查询中…" to TgFeedTokens.TextLow
                        indexStatus.building -> "建设中" to TgFeedTokens.TgBlue
                        indexStatus.indexedChannels > 0 -> "已就绪" to TgFeedTokens.SuccessGreen
                        else -> "未建立" to TgFeedTokens.TextLow
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = TgFeedTokens.GapXs),
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(badgeColor),
                        )
                        Text(
                            badgeText,
                            style = MaterialTheme.typography.bodySmall,
                            color = badgeColor,
                            modifier = Modifier.padding(start = TgFeedTokens.GapSm),
                        )
                    }
                    // 明细行：已索引 x/y 频道 · z 条内容 · 占用大小（自动开着且在爬时附当前轮次）
                    if (indexStatus.loaded) {
                        val detail = buildString {
                            append("已索引 ${indexStatus.indexedChannels}")
                            if (indexStatus.totalChats > 0) append("/${indexStatus.totalChats}")
                            append(" 个频道 · ${indexStatus.totalItems} 条内容")
                            // R266 v11 D6 诚实副标：总数含已收编转发（转发不再占独立存储，
                            // 但仍是该频道的发布事实——计数口径如实告知）
                            if (indexStatus.repostItems > 0) append("（含转发 ${indexStatus.repostItems} 条）")
                            // R46 口径诚实：按月采样覆盖（非全量）的频道数单列，不全量冒称
                            if (indexStatus.sampledChannels > 0) append("（含按月采样 ${indexStatus.sampledChannels} 个）")
                            if (indexStatus.dbBytes > 0) append(" · 占用 ${formatIndexSize(indexStatus.dbBytes)}")
                            if (indexStatus.building && autoIndex && indexStatus.round > 0) {
                                append("（第 ${indexStatus.round} 轮）")
                            }
                        }
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = TgFeedTokens.TextMedium,
                        )
                    }
                    // 手动按钮只在自动建关着时显示——自动开着还显示手动按钮是矛盾设计
                    if (!autoIndex) {
                    FilledTonalButton(
                        onClick = {
                            if (!indexing) {
                                indexing = true
                                indexProgress = "准备中…"
                                scope.launch {
                                    val channels = runCatching { gateway.listChannels() }.getOrDefault(emptyList())
                                    var done = 0
                                    // H3：串行建索引——同一协程里逐个 await 完成（buildDeepIndexAwait 是 suspend）。
                                    // 旧写法 forEach + buildDeepIndex（内部 launch）是 fire-and-forget，
                                    // 会瞬间点燃全部频道的并发爬取，flood wait 风险高、进度文案也是假走完
                                    for (ch in channels) {
                                        indexProgress = "正在索引：${ch.title}（$done/${channels.size}）"
                                        vm.buildDeepIndexAwait(ch.id) { n -> indexProgress = "${ch.title}：已索引 $n 条" }
                                        done++
                                    }
                                    indexing = false
                                    indexProgress = "索引完成（${channels.size} 个频道）"
                                    Toast.makeText(context, "深度索引建立完成，搜索即可命中全量内容", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        enabled = !indexing,
                        shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
                        // 卡片本身已是 SurfaceHigh，tonal 默认色会糊成一片；改用白 α0.10 提亮一档
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color.White.copy(alpha = 0.10f),
                            contentColor = TgFeedTokens.TextHigh,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                    ) {
                        Icon(
                            Icons.Outlined.ManageSearch,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            if (indexing) "索引中…" else "建立/更新全部频道深度索引",
                            modifier = Modifier.padding(start = TgFeedTokens.GapSm),
                        )
                    }
                    }
                    if (indexProgress.isNotEmpty()) {
                        Text(indexProgress, style = MaterialTheme.typography.bodySmall, color = TgFeedTokens.TextMedium)
                    }
                    // 清空重建：警示红描边文字钮（非主按钮），二次确认后才执行（弹窗见文件末尾）
                    TextButton(
                        onClick = { confirmRebuildIndex = true },
                        shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .border(
                                1.dp,
                                TgFeedTokens.WarningRed.copy(alpha = 0.6f),
                                RoundedCornerShape(TgFeedTokens.RadiusPill),
                            ),
                    ) {
                        Text("清空并重建索引", color = TgFeedTokens.WarningRed)
                    }
                    // R294 P3 物理压实：显式保 rowid 重建换装回收死行/孤儿页空间
                    // （裸 VACUUM 是成文红线，rowid 重排会团灭倒排对齐——此入口是唯一
                    // 合法压实路径）。先现场预估弹确认窗（弹窗见文件末尾），压实期间
                    // 索引写入暂停、搜索仍可用（进度行在下方）
                    TextButton(
                        onClick = {
                            scope.launch {
                                val est = vm.estimateIndexCompaction()
                                compactEstimateText = if (est == null) {
                                    "索引库尚未建立，无需压实。"
                                } else {
                                    val (free, total, autoVacFull) = est
                                    val worth = dev.tgfeed.core.feed.IndexCompactPolicy.shouldCompact(free, total)
                                    "当前索引库 ${formatIndexSize(total)}，其中自由页约 ${formatIndexSize(free)}。" +
                                        if (worth) {
                                            ""
                                        } else if (autoVacFull) {
                                            // R294 彩排实锤：设备库默认 auto_vacuum=FULL——整页删除
                                            // 随提交自动回收，自由页≈0 是常态而非「不用压」；页内密度
                                            // 空隙与孤儿页仍只有压实能收
                                            "\n\n本机已在自动回收整页空间（自由页≈0 属正常）；若刚经历" +
                                                "大量合并/清理，页内空隙仍在，压实可重整密度（收益以实测为准）。"
                                        } else {
                                            "\n\n未达建议阈值（自由页 ≥64MB 且占库比 ≥10%）——现在压实收益太薄，可以等等。"
                                        }
                                }
                                confirmCompactIndex = true
                            }
                        },
                        enabled = compactProgress == null,
                        shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                    ) {
                        Text("压实索引库（回收空间）", color = TgFeedTokens.TextMedium)
                    }
                    if (compactProgress != null) {
                        Text(
                            compactProgress ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = TgFeedTokens.TgBlue,
                        )
                    }
                }

                // ---------------- 时间维度工具（R215 入口收敛：自发现页深功能区迁入） ----------------
                // R213 砍掉项的「入口隐藏不删码」落点：事件研究器/宇宙作息表从发现页
                // 主界面撤下（用户嫌花里胡哨），进阶用户仍可从高级区进入——
                // EventResearchScreen/UniverseRhythmSheet/VM 数据口全保留未删，
                // 后续用户想用可一键加回发现页。
                SettingsCard("时间维度工具") {
                    SettingsActionRow(
                        icon = Icons.Outlined.Timeline,
                        title = "事件研究器",
                        subtitle = "时间窗 × 关键词 → 事件传播路径",
                        trailing = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = TgFeedTokens.TextLow,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        onClick = { appContainer.openEventResearch() },
                    )
                    SettingsActionRow(
                        icon = Icons.Outlined.Schedule,
                        title = "宇宙作息表",
                        subtitle = "TG 世界几点醒 · 全网频道发布节律",
                        trailing = {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = TgFeedTokens.TextLow,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        onClick = {
                            // 快照优先毫秒级（R170 陈值先回）；首次无快照触发刷新并
                            // 短等流回填（空库也会发空态快照=诚实文案，见 refreshImpl）
                            scope.launch {
                                val rhythm = vm.universeRhythmNow() ?: run {
                                    vm.refreshUniverseRhythm()
                                    kotlinx.coroutines.withTimeoutOrNull(1_500) {
                                        vm.universeRhythm.first { it != null }
                                    }
                                }
                                if (rhythm != null) {
                                    rhythmSheet = rhythm
                                } else {
                                    android.widget.Toast.makeText(
                                        context, "时间档案还在整理，稍后再来", android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                    )
                }

                // ---------------- 诊断通道（R198 收进高级区） ----------------
                // R361 开源收口：release 构建整卡隐藏（用户口径「诊断通道正式版隐藏」）——
                // 端点本体 R146 已收口（DiagServer.start 的 BuildConfig.DEBUG 硬闸，release
                // 零端口零监听）；本闸收 UI 面（含卡内 R220 清理测试文件夹/R300 清理云同步
                // 消息两对话框入口随卡同隐）。源锚计数不受影响（卡片调用点仍在源码中），
                // 运行时头部计数由 AdvancedVisibleCount 诚实减一。
                if (BuildConfig.DEBUG) {
                SettingsCard("诊断通道") {
                    var diagOn by remember { mutableStateOf(prefs.diagServerEnabled) }
                    var diagUrl by remember { mutableStateOf(appContainer.diagServer.diagUrl()) }
                    // 开着期间每 3s 轻刷一次展示地址：局域网 IP 可能随网络切换变化，
                    // 端口冲突重试也会让最终端口晚于首帧确定；纯字符串拼装，开销可忽略
                    LaunchedEffect(diagOn) {
                        while (diagOn) {
                            diagUrl = appContainer.diagServer.diagUrl()
                            delay(3_000L)
                        }
                    }
                    SettingsSwitchRow(
                        title = "诊断通道",
                        subtitle = "开启后同一局域网的电脑可随时用浏览器/命令行只读拉取本机诊断日志，无需任何操作",
                        checked = diagOn,
                        onCheckedChange = {
                            diagOn = it
                            appContainer.setDiagServerEnabled(it)
                            diagUrl = appContainer.diagServer.diagUrl()
                        },
                        help = "做什么：开着时，同一 WiFi 下的电脑可以用浏览器随时查看本机的" +
                            "设备信息、崩溃日志与最近运行日志（排障用）。\n默认：开。\n" +
                            "隐私：只读接口，不接收任何写入；关闭后端口立即释放。",
                    )
                    if (diagOn) {
                        Text(
                            diagUrl,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TgFeedTokens.TgBlue,
                        )
                    }
                    Text(
                        "只读接口：仅返回设备信息、崩溃日志与最近运行日志，不接收任何写入；关闭后端口立即释放",
                        style = MaterialTheme.typography.bodySmall,
                        color = TgFeedTokens.TextMedium,
                    )
                    // R220 测试文件夹清理入口：只对真实网关开放（演示模式没有真账号可清）。
                    // 弹窗内自带「列表→勾选→二次确认→删除」闸门，删除前用户必过确认
                    if (gateway is TdLibGateway) {
                        SettingsActionRow(
                            icon = Icons.Outlined.CleaningServices,
                            title = "清理测试文件夹",
                            subtitle = "移除早期功能测试遗留在账号里的「R130」开头文件夹（只删分组，不影响已加入的频道）",
                        ) {
                            showTestFolderCleaner = true
                        }
                        // R300 期零③可回滚口：清掉收藏夹里的云同步消息（本机数据不动，
                        // 各端下次推送自动重建；DangerConfirmDialog 二次确认后才执行）
                        SettingsActionRow(
                            icon = Icons.Outlined.CleaningServices,
                            title = "清理云同步消息",
                            subtitle = "删除 Telegram 收藏夹里的同步数据消息（观看进度/收藏/订阅/屏蔽的云端副本；本机数据不受影响，之后会自动重建）",
                        ) {
                            showCloudSyncCleaner = true
                        }
                    }
                }
                }
                }
            }
            // ---------------- 高级折叠区结束 ----------------

            // ---------------- 数据迁移（debug/release 签名不同无法覆盖安装：换包搬家通道） ----------------
            SettingsCard("数据迁移") {
                Text(
                    "换安装包（如调试包换正式包）时用：导出数据包 → 装新包 → 导入数据包。" +
                        "导出为压缩包（体积约为原数据的 1/5，导入后还原完整大小）。" +
                        "收藏、历史、订阅、设置、深度索引都会随迁；登录会话不随包迁移，导入后扫码重新登录即可",
                    style = MaterialTheme.typography.bodySmall,
                    color = TgFeedTokens.TextMedium,
                )
                FilledTonalButton(
                    onClick = { exportLauncher.launch(BackupManager.suggestedFileName()) },
                    enabled = !backupBusy,
                    shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
                    // 卡片本身已是 SurfaceHigh，tonal 默认色会糊成一片；改用白 α0.10 提亮一档（同深度索引按钮）
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.White.copy(alpha = 0.10f),
                        contentColor = TgFeedTokens.TextHigh,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) {
                    Icon(
                        Icons.Outlined.FileUpload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        if (backupBusy) "处理中…" else "导出数据包",
                        modifier = Modifier.padding(start = TgFeedTokens.GapSm),
                    )
                }
                FilledTonalButton(
                    onClick = {
                        // zip 在各文件管理器里的 MIME 登记不一，多带几个常见取值防选不到文件
                        importLauncher.launch(
                            arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
                        )
                    },
                    enabled = !backupBusy,
                    shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.White.copy(alpha = 0.10f),
                        contentColor = TgFeedTokens.TextHigh,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) {
                    Icon(
                        Icons.Outlined.FileDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        "导入数据包",
                        modifier = Modifier.padding(start = TgFeedTokens.GapSm),
                    )
                }
                if (backupProgress.isNotEmpty()) {
                    Text(backupProgress, style = MaterialTheme.typography.bodySmall, color = TgFeedTokens.TextMedium)
                }
                // R287：合并导入进度常驻行（后台批迁进行中显示；完成/中断自动消失——免重启语义的可观测面）
                val importMergeLine by vm.importMergeProgress.collectAsState()
                if (importMergeLine != null) {
                    Text(importMergeLine!!, style = MaterialTheme.typography.bodySmall, color = TgFeedTokens.TgBlue)
                }
            }

            // ---------------- T0-3：屏蔽的频道（AUDIT-GAPS3 #3：不可逆 + 无管理入口） ----------------
            // 发现页"弃"会把频道永久打进 blocked_channels，此前全 App 没有任何后悔药。
            // 本卡列出全部已屏蔽频道，每条一个"解除"钮（vm.unblockChannel 定向移除 + 持久化）
            val blockedChannels by vm.blockedChannels.collectAsState()
            val channelList by vm.channels.collectAsState()
            // 收纳：屏蔽数超过阈值时默认折叠成一行摘要（防列表无限拉长设置页）；
            // 折叠态 remember 记（设置页重组/子页往返保持用户选择）
            var blockedExpanded by remember { mutableStateOf(false) }
            // 标题懒解析结果（chatId → 频道标题）：被屏蔽的频道多半不在已加入列表里，
            // 走网关 chatTitleOf 链补查（channelTitleCache → 图谱 nodeTitle → 幂等 GetChat），
            // 解析回来写进这张本地快照触发重组；解析不到的显示"未知频道(id 后 6 位)"，不再摆裸 id
            var blockedTitles by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
            LaunchedEffect(blockedChannels, blockedExpanded) {
                // 折叠态不解析：列表收着时标题不可见，不为它白烧 GetChat；展开才补查
                if (blockedChannels.size > BLOCKED_COLLAPSE_THRESHOLD && !blockedExpanded) {
                    return@LaunchedEffect
                }
                val real = gateway as? TdLibGateway ?: return@LaunchedEffect // Mock 演示模式无解析链
                val missing = blockedChannels.filter { cid ->
                    blockedTitles[cid].isNullOrBlank() &&
                        channelList.firstOrNull { it.id == cid }?.title.isNullOrBlank()
                }
                if (missing.isNotEmpty()) {
                    // 串行逐个解析：GetChat 幂等查询，串行足够快且不对 TDLib 形成并发冲击
                    val resolved = LinkedHashMap<Long, String>(missing.size)
                    for (cid in missing) {
                        real.chatTitleOf(cid)?.let { resolved[cid] = it }
                    }
                    if (resolved.isNotEmpty()) blockedTitles = blockedTitles + resolved
                }
            }
            SettingsCard("屏蔽的频道") {
                Text(
                    "屏蔽后该频道从推荐、搜索、聚合中消失；在这里可以随时解除",
                    style = MaterialTheme.typography.bodySmall,
                    color = TgFeedTokens.TextMedium,
                )
                val blockedList = blockedChannels.toList()
                if (blockedList.isEmpty()) {
                    Text(
                        "没有屏蔽任何频道",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TgFeedTokens.TextLow,
                    )
                } else if (blockedList.size > BLOCKED_COLLAPSE_THRESHOLD && !blockedExpanded) {
                    // 折叠摘要行：整行可点展开，尾部下箭头提示可展开
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
                            .clickable { blockedExpanded = true }
                            .padding(vertical = TgFeedTokens.GapSm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
                    ) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.NotInterested,
                                contentDescription = null,
                                tint = TgFeedTokens.TextMedium,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Text(
                            "已屏蔽 ${blockedList.size} 个频道，点开展开",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TgFeedTokens.TextHigh,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Filled.ExpandMore,
                            contentDescription = "展开",
                            tint = TgFeedTokens.TextLow,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    blockedList.forEach { cid ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
                                .padding(vertical = TgFeedTokens.GapSm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
                        ) {
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.08f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Outlined.NotInterested,
                                    contentDescription = null,
                                    tint = TgFeedTokens.TextMedium,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Text(
                                // 频道名解析链：已加入列表真名 → 网关懒解析快照
                                // （channelTitleCache/图谱/GetChat）→ 未知频道(id 后 6 位)
                                channelList.firstOrNull { it.id == cid }?.title
                                    ?.ifBlank { null }
                                    ?: blockedTitles[cid]?.ifBlank { null }
                                    ?: "未知频道(${cid.toString().takeLast(6)})",
                                style = MaterialTheme.typography.bodyLarge,
                                color = TgFeedTokens.TextHigh,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "解除",
                                style = MaterialTheme.typography.labelLarge,
                                color = TgFeedTokens.TgBlue,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                                    .clickable {
                                        vm.unblockChannel(cid)
                                        Toast.makeText(context, "已解除屏蔽", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                    // 展开后的收起行（仅超阈值列表需要；少于此数本来就没有折叠态）
                    if (blockedList.size > BLOCKED_COLLAPSE_THRESHOLD) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
                                .clickable { blockedExpanded = false }
                                .padding(vertical = TgFeedTokens.GapSm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
                        ) {
                            Icon(
                                Icons.Filled.ExpandLess,
                                contentDescription = "收起",
                                tint = TgFeedTokens.TextLow,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                "收起",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TgFeedTokens.TextLow,
                            )
                        }
                    }
                }
            }

            // ---------------- 关于（版本信息 / 开源许可 / 项目主页；业界惯例置底） ----------------
            // 版本行直达关于页：版本号与构建变体实读 BuildConfig（版本名+内部版本号+变体如实），
            // 点行进入关于页看版本详情、检查更新、开源许可与免责要点
            SettingsCard("关于") {
                SettingsActionRow(
                    icon = Icons.Outlined.Info,
                    title = "${stringResource(R.string.app_name)} v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})",
                    subtitle = "构建变体 ${BuildConfig.BUILD_TYPE} · 版本详情、开源许可与项目主页",
                    trailing = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = TgFeedTokens.TextLow,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = onOpenAbout,
                )
            }

            // ---------------- 危险区：本地数据管理（不可恢复操作） ----------------
            SettingsCard("危险区", danger = true) {
                Text(
                    "以下操作仅清除本机记录，不影响 Telegram 账号数据；清空后不可恢复",
                    style = MaterialTheme.typography.bodySmall,
                    color = TgFeedTokens.TextMedium,
                )
                SettingsActionRow(
                    icon = Icons.Outlined.History,
                    title = "清空观看历史",
                    subtitle = "本机的观看记录与已看标记",
                    danger = true,
                ) {
                    // 不直接清空：先弹二次确认（见文件末尾弹窗）
                    confirmClearHistory = true
                }
                SettingsActionRow(
                    icon = Icons.Outlined.NotInterested,
                    title = "清空不感兴趣名单",
                    subtitle = "被标记的内容将重新进入推荐流",
                    danger = true,
                ) {
                    // 不直接清空：先弹二次确认（见文件末尾弹窗）
                    confirmClearDisliked = true
                }
            }
        }

        // 末尾留白：最后一张卡片与屏幕底缘保持距离
        Spacer(Modifier.height(TgFeedTokens.GapXl))
    }

    // ---------------- 危险区二次确认弹窗（确认后才真正执行清空） ----------------
    // ---------------- R197 我的搜索群选择器 sheet（与发现页入口同款组件） ----------------
    if (showHarvestPicker) {
        HarvestGroupPickerSheet(
            gateway = gateway,
            onDismiss = { showHarvestPicker = false },
            onSelectionChanged = { harvestPickTick++ },
        )
    }
    // ---------------- R215 时间维度工具：宇宙作息表面板（自发现页迁入的宿主） ----------------
    // 数据=共享 VM 的本地 rollup 快照（毫秒级陈值先回），sheet 本体 UniverseRhythmSheet
    // 零改动复用；触发点=高级区行 → 底部 sheet（R173 就近锚定）
    rhythmSheet?.let { rhythm ->
        dev.tgfeed.ui.UniverseRhythmSheet(
            rhythm = rhythm,
            onDismiss = { rhythmSheet = null },
        )
    }
    // ---------------- R220 测试文件夹清理（诊断通道配套的「可回滚」通道） ----------------
    if (showTestFolderCleaner) {
        val td = gateway as? TdLibGateway
        if (td != null) {
            dev.tgfeed.ui.components.TestFolderCleanupDialog(
                gateway = td,
                onDismiss = { showTestFolderCleaner = false },
            )
        }
    }
    // ---------------- R300 清理云同步消息（期零③可回滚口；用户显式确认后才删） ----------------
    if (showCloudSyncCleaner) {
        DangerConfirmDialog(
            title = "清理云同步消息？",
            message = "将删除你 Telegram 收藏夹里的全部同步数据消息（观看进度/收藏/订阅/屏蔽的云端副本）。" +
                "本机数据不受影响；其他设备上的这些云端副本会一并消失，之后各设备推送时会自动重建。",
            confirmLabel = "清理",
            onConfirm = {
                vm.clearCloudSyncMessages { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = { showCloudSyncCleaner = false },
        )
    }
    if (confirmClearHistory) {
        DangerConfirmDialog(
            title = "清空观看历史？",
            message = "将删除本机全部观看记录与已看标记，清空后不可恢复（不影响 Telegram 账号数据）。",
            confirmLabel = "清空",
            onConfirm = {
                vm.clearHistory()
                Toast.makeText(context, "观看历史已清空", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { confirmClearHistory = false },
        )
    }
    if (confirmClearDisliked) {
        DangerConfirmDialog(
            title = "清空不感兴趣名单？",
            message = "被标记为不感兴趣的内容将重新进入推荐流，清空后不可恢复。",
            confirmLabel = "清空",
            onConfirm = {
                vm.clearDisliked()
                Toast.makeText(context, "不感兴趣名单已清空", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { confirmClearDisliked = false },
        )
    }
    // 深度索引"清空重建"二次确认弹窗：文案说清后果（重建期间搜索结果会减少）；
    // 防误触升级为长按确认（索引是长时间爬出来的心血，点一下绝不许触发）
    if (confirmRebuildIndex) {
        DangerConfirmDialog(
            title = "清空并重建索引？",
            message = "将删除全部本地索引并重新建立，期间搜索结果会减少。重建在后台自动进行，不影响正常浏览。\n\n确认方式：按住下方按钮 1.5 秒不放。",
            confirmLabel = "按住清空重建",
            onConfirm = {
                vm.clearAndRebuildDeepIndex()
                Toast.makeText(context, "已清空索引，正在后台重建", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { confirmRebuildIndex = false },
            holdToConfirmMs = 1500,
        )
    }
    // R294 P3 压实确认弹窗：预估+后果明示（写入暂停/FTS5 机型词位重灌窗），长按确认防误触
    if (confirmCompactIndex) {
        DangerConfirmDialog(
            title = "压实索引库？",
            message = compactEstimateText +
                "\n\n压实=把索引库逐行拷贝重建（行号原样保留，内容零变化）后换装，" +
                "回收合并/清理留下的死行空间。压实期间索引写入暂停、搜索仍可用；" +
                "FTS5 机型换装后词位在后台重灌，重灌完成前老内容搜索可能短暂不全。\n\n" +
                "确认方式：按住下方按钮 1.5 秒不放。",
            confirmLabel = "按住开始压实",
            onConfirm = {
                vm.compactDeepIndex()
                Toast.makeText(context, "压实已开始（后台进行，期间索引写入暂停）", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { confirmCompactIndex = false },
            holdToConfirmMs = 1500,
        )
    }
    // R294 压实完成报告 Toast（一次性消费即复位；收集位与弹窗同作用域）
    val compactDone by vm.indexCompactDone.collectAsState()
    LaunchedEffect(compactDone) {
        compactDone?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.clearIndexCompactDone()
        }
    }
    // 数据迁移·导入三选确认（R287）：合并导入（多设备同账号汇总，默认推荐——本机数据不动，
    // 对端索引并入+爬取状态随迁，免重启）/覆盖恢复（整包回滚，换包搬家场景，需重启）/取消
    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text("导入数据包") },
            text = {
                Text(
                    "合并导入（推荐）：只把包内的深度索引并入本机（相同内容自动去重，" +
                        "对方已爬完的频道本机不再重复爬取），本机的收藏、历史与设置全部不动；" +
                        "合并在后台进行，不影响正常使用，无需重启。\n\n" +
                        "覆盖恢复：数据包内容整体覆盖本机现有数据（收藏、观看历史、订阅、设置、" +
                        "深度索引等），不可撤销，完成后需要重启应用。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingImportUri = null
                    backupBusy = true
                    backupProgress = "正在解包索引…"
                    scope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                BackupManager.importBackupMergeIndex(context, uri) { msg -> backupProgress = msg }
                            }
                        }
                        backupBusy = false
                        backupProgress = ""
                        result.onSuccess { summary ->
                            val kicked = vm.requestImportMerge()
                            importMergeSummary = if (kicked) {
                                "$summary\n\n合并已在后台自动进行（大包需要一段时间），中断会自动续跑，无需重启应用。"
                            } else {
                                "$summary\n\n合并器将在下次启动时自动开跑，无需重启应用。"
                            }
                        }.onFailure {
                            Toast.makeText(context, "导入失败：${it.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("合并导入（推荐）", color = TgFeedTokens.TgBlue) }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
                    TextButton(onClick = {
                        pendingImportUri = null
                        backupBusy = true
                        backupProgress = "正在导入…"
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    BackupManager.importBackup(context, uri) { msg -> backupProgress = msg }
                                }
                            }
                            backupBusy = false
                            backupProgress = ""
                            result.onSuccess { importDoneSummary = it }
                                .onFailure {
                                    Toast.makeText(context, "导入失败：${it.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                                }
                        }
                    }) { Text("覆盖恢复", color = TgFeedTokens.WarningRed) }
                    TextButton(onClick = { pendingImportUri = null }) { Text("取消") }
                }
            },
        )
    }
    // 数据迁移·合并导入已接单：免重启提示（合并本体在后台批迁，进度可查 logcat R287 行）
    importMergeSummary?.let { summary ->
        AlertDialog(
            onDismissRequest = { importMergeSummary = null },
            title = { Text("合并已就绪") },
            text = { Text(summary) },
            confirmButton = {
                TextButton(onClick = { importMergeSummary = null }) { Text("好", color = TgFeedTokens.TgBlue) }
            },
        )
    }
    // 数据迁移·导入完成：提示重启生效。登录会话（filesDir/tdlib）不随包迁移——重启后扫码重登即可
    importDoneSummary?.let { summary ->
        AlertDialog(
            onDismissRequest = { importDoneSummary = null },
            title = { Text("导入完成") },
            text = { Text("$summary\n\n重启应用后生效；登录会话不随数据包迁移，重启后扫码重新登录即可。") },
            confirmButton = {
                TextButton(onClick = {
                    // 杀进程 = 下次点开即冷启动。比"重建 VM/网关"干净：SharedPreferences 内存副本、
                    // LocalStore 各缓存、DeepSearchIndex 打开的句柄全部随之作废，无残留旧态
                    android.os.Process.killProcess(android.os.Process.myPid())
                    exitProcess(0)
                }) { Text("立即退出", color = TgFeedTokens.TgBlue) }
            },
            dismissButton = {
                TextButton(onClick = { importDoneSummary = null }) { Text("稍后手动重启") }
            },
        )
    }
    }
}

// ==================== 以下为设置页私有组件 ====================

/** 开关配色：M3 默认未选中态轨道是 surfaceVariant（=SurfaceHigh），贴在卡片底上几乎隐形，需显式提亮 */
private val settingsSwitchColors: SwitchColors
    @Composable
    get() = SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = TgFeedTokens.TgBlue,
        uncheckedThumbColor = TgFeedTokens.TextMedium,
        uncheckedTrackColor = Color.White.copy(alpha = 0.08f),
        uncheckedBorderColor = Color.White.copy(alpha = 0.16f),
    )

/** 分组卡片：16dp 圆角 + SurfaceHigh 底，组标题在卡片内顶部小字标签；danger=true 时标题转红并带警示图标 */
@Composable
private fun SettingsCard(
    title: String,
    danger: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SettingsCardRadius))
            .background(TgFeedTokens.SurfaceHigh)
            .padding(TgFeedTokens.GapLg),
        verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (danger) {
                Icon(
                    Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = TgFeedTokens.WarningRed,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = if (danger) TgFeedTokens.WarningRed else TgFeedTokens.TextLow,
            )
        }
        content()
    }
}

/** 两行式开关行：标题 bodyLarge + 说明 bodySmall 灰字，右侧 M3 Switch；整行可点切换。
 *  R198：help 非空时文本列与开关之间渲染 [SettingsHelpHint]「？」说明图标——副文案只留
 *  两行人话，机理/参数/取舍/默认值全进气泡（架构师令：业界标准问号收容） */
@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    help: String? = null,
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
        if (help != null) {
            SettingsHelpHint(title = title, help = help)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = settingsSwitchColors)
    }
}

/**
 * 行尾「？」说明图标（R198 架构师令：每项功能说明用小问号图标收容——业界标准）。
 * - 20dp HelpOutline 图标 + TextLow 色（不抢行尾开关的视觉焦点）；
 * - 触区 40dp（TgFeedTokens.TouchMin 达标）；点按弹 AppDialog（kit 居中弹层唯一入口，
 *   纯展示框形态：无主钮、次钮「知道了」）；
 * - 气泡内容四要素：做什么 / 默认 / 生效 / 已知取舍——机理与参数全收这里，
 *   行内副文案只留两行人话（全页复用的共享件）。
 */
@Composable
private fun SettingsHelpHint(title: String, help: String) {
    var show by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(TgFeedTokens.TouchMin)
            .clickable { show = true },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.HelpOutline,
            contentDescription = "$title 说明",
            tint = TgFeedTokens.TextLow,
            modifier = Modifier.size(20.dp),
        )
    }
    if (show) {
        AppDialog(
            onDismissRequest = { show = false },
            title = title,
            dismissText = "知道了",
        ) {
            Text(help, style = MaterialTheme.typography.bodyMedium, color = TgFeedTokens.TextMedium)
        }
    }
}

/**
 * 小标签 + 全圆角输入框：与顶栏搜索同一语言
 * （CircleShape + 白 α0.14 底 + 指示线透明；标签放框外上方，避免胶囊形裁切浮动标签）。
 * R198：help 非空时标签行右侧渲染 [SettingsHelpHint]「？」说明图标（同开关行口径）。
 */
@Composable
private fun LabeledPillField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    help: String? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapXs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = TgFeedTokens.TextLow,
                modifier = Modifier.padding(start = TgFeedTokens.GapXs),
            )
            if (help != null) {
                SettingsHelpHint(title = label, help = help)
            }
        }
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 图标徽章行：左侧 36dp 圆形图标底托 + 标题/说明两行，整行可点；danger=true 时图标与标题转红 */
@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    danger: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
            .clickable(onClick = onClick)
            .padding(vertical = TgFeedTokens.GapSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (danger) TgFeedTokens.WarningRed.copy(alpha = 0.12f)
                    else Color.White.copy(alpha = 0.08f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (danger) TgFeedTokens.WarningRed else TgFeedTokens.TextMedium,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (danger) TgFeedTokens.WarningRed else TgFeedTokens.TextHigh,
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TgFeedTokens.TextMedium)
        }
        trailing?.invoke()
    }
}

/** 索引库大小格式化：≥1MB 显示一位小数 MB，不足 1MB 显示 KB（用户友好口径） */
private fun formatIndexSize(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1.0) "%.1f MB".format(mb) else "${bytes / 1024} KB"
}

/** 危险操作二次确认弹窗：文案必须说清后果（不可恢复），确认钮用警示红。
 *  holdToConfirmMs > 0 时确认钮升级为长按确认（进度走完才触发）——用于清空索引这类高代价操作防误触 */
@Composable
private fun DangerConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    holdToConfirmMs: Long = 0,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            if (holdToConfirmMs <= 0) {
                TextButton(onClick = {
                    onDismiss()
                    onConfirm()
                }) {
                    Text(confirmLabel, color = TgFeedTokens.WarningRed)
                }
            } else {
                HoldToConfirmButton(label = confirmLabel, holdMs = holdToConfirmMs) {
                    onDismiss()
                    onConfirm()
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 长按确认按钮：按住 holdMs 毫秒、红色进度底纹从左扫到右才触发；中途松手进度回退归零 */
@Composable
private fun HoldToConfirmButton(label: String, holdMs: Long, onConfirm: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var holdDone by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
            .border(1.dp, TgFeedTokens.WarningRed, RoundedCornerShape(TgFeedTokens.RadiusPill))
            .pointerInput(holdMs) {
                detectTapGestures(
                    onPress = {
                        holdDone = false
                        val job = scope.launch {
                            progress.animateTo(1f, tween(holdMs.toInt()))
                            holdDone = true
                        }
                        tryAwaitRelease()
                        if (holdDone) {
                            onConfirm()
                        } else {
                            // 中途松手：取消进度并回退归零（R178 收编 AnimMed——状态过渡三档）
                            job.cancel()
                            scope.launch { progress.animateTo(0f, tween(TgFeedTokens.AnimMed)) }
                        }
                    },
                )
            },
    ) {
        // 进度底纹（从左往右扫的红色底）
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress.value)
                    .background(TgFeedTokens.WarningRed.copy(alpha = 0.25f)),
            )
        }
        Text(
            if (progress.value > 0f) "继续按住…" else label,
            color = TgFeedTokens.WarningRed,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
