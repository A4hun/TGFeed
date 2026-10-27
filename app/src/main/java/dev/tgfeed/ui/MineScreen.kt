package dev.tgfeed.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.feed.FeedViewModelFactory
import dev.tgfeed.core.prefs.AppPrefs
import dev.tgfeed.core.td.MockTdGateway
import dev.tgfeed.core.td.MyProfile
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.ui.components.kit.adaptiveContentWidth
import dev.tgfeed.ui.theme.TgFeedTokens

/** 分组卡片圆角：16dp，与设置页 SettingsCard 同一语言 */
private val MineCardRadius = 16.dp

/**
 * 「我的」页（信息架构收敛；R173 底栏时代为底栏右端真头像座位的落地页）。
 * - 纯黑底（InkBlack），平板/横屏限宽 480dp 居中，与设置页同尺度；
 * - 顶部：OverlayBg 圆形底托返回钮 + 标题"我的"；
 * - 账号卡：真实本人资料（TdLibGateway.getMyProfile：GetMe + 头像小图 DownloadFile）——
 *   圆形头像（无头像回落首字母圆）+ 显示名（firstName+lastName）+
 *   副标题 @username（无则 +手机号，再回落连接状态）；演示模式维持占位文案；
 * - 入口列表（图标 + 名称 + 右箭头，行高 ≥56dp）：设置 / 我的下载 / 播放列表 /
 *   观看历史 / 那年今日（R203 增） / 观看报告 / 频道体检 / 口味星图 / TG 广场
 *   （R173 §2.4 起行数 8，R203 增"那年今日"行成 9——各子页走
 *   AppContainer 现有 openXxx 状态路由（由调用方传入）；
 * - TG 广场（R209 路由收敛，用户实锤②）：行改跳转=发现页+选中热榜 tab
 *   （openDiscoverPlaza 统一唯一入口；副标题仍读 prefs 反映广场开关态）——
 *   广场管理面板迁热榜 tab 空态就地开（PlazaBoardScreen），本页不再有管理入口；
 *   管理的设置页「广场」卡（高级折叠区）照旧保留。
 */
@Composable
fun MineScreen(
    gateway: TdGateway,
    prefs: AppPrefs,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenPlaylists: () -> Unit = {},
    // R387 播放队列（节目单）页入口（播放列表旁——同为"我攒的单"语义组）；默认空实现
    onOpenPlayQueue: () -> Unit = {},
    // 观看历史页入口（放在观看报告旁边）；默认空实现，调用方不接线也可编译
    onOpenWatchHistory: () -> Unit = {},
    // R199 我的收藏页入口（放在播放列表旁——同为"我攒的内容"语义组）；默认空实现
    onOpenFavorites: () -> Unit = {},
    // R203 那年今日页入口（放在观看历史旁——同为"时间回看"语义组）；默认空实现
    onOpenOnThisDay: () -> Unit = {},
    onOpenWatchReport: () -> Unit = {},
    // 频道体检页入口（R132：僵尸/变质频道一键断舍离；放在观看报告旁边）；默认空实现
    onOpenChannelHealth: () -> Unit = {},
    // 口味星图页入口（R143：互链图谱+观看行为渲染的星系图，可导出海报；放在频道体检旁边）；
    // 默认空实现，调用方不接线也可编译
    onOpenTasteMap: () -> Unit = {},
    // R209 热榜统一唯一入口（用户实锤②「入口在布局下方——产品语义不对」的归位）：
    // 我的页「TG 广场」行改跳转——发现页+选中热榜 tab（调用方接
    // AppContainer.openDiscoverPlaza）；默认空实现，调用方不接线也可编译
    onOpenPlazaBoard: () -> Unit = {},
    // R189 顶层 pager 保活门控：true = 本页是 pager 落定页（页级 BackHandler 只在
    // 此时启用——三页常驻组合后非激活页不许吃返回键）。默认 true 兼容独立使用
    pageActive: Boolean = true,
    // R189 底栏双击「我的」=刷新的戳（递增即重拉本人资料；调用方先清网关缓存再递增。
    // 门控同 profile：非落定页时戳变动不触发——不可见不拉流）
    refreshTick: Int = 0,
) {
    // 系统返回键 = 回 Feed（我的页是状态驱动路由，不在返回栈里，不拦会退到桌面）
    // R189 保活门控：pager 三页常驻后本页 handler 与首页/发现页同台注册
    //（Compose 后注册者优先）——非落定页时禁用，返回语义归激活页自己的 handler
    BackHandler(enabled = pageActive, onBack = onBack)

    // 连接状态副标题（账号卡兜底用）：已连接 / 连接中 / 同步中…
    val connectionState by gateway.connectionState.collectAsState()
    val demoMode = gateway is MockTdGateway

    // R199 收藏计数徽章数据源：与 FeedScreen 同 key 的共享 VM（WatchHistoryScreen
    // 同款先例）——favorites 键集是活流，视频页星标/收藏页取消即时反映到徽章。
    // 冷启动零增量：保活页序 0（FeedScreen）先组合已建同 key 实例，此处取回既有
    // 实例，无新 init 协程点火
    val app = LocalContext.current.applicationContext as android.app.Application
    val feedVm: FeedViewModel = viewModel(
        factory = FeedViewModelFactory(gateway, app),
        key = "gw" + System.identityHashCode(gateway),
    )
    val favoriteKeys by feedVm.favorites.collectAsState()

    // 本人资料（账号卡真实数据）：进页拉一次（网关侧有进程内缓存，重复进页不重复请求）；
    // 演示模式（Mock 无 TDLib）不拉，维持占位文案；加载中与拉取失败都回落占位（myProfile 保持 null）
    // R189 保活门控（架构师转交线索①）：三页常驻后我的页冷启动即组合——profile 首拉
    // 是真实网络调用（GetMe+头像小图），挂 pageActive 推迟到首次落定本页（底栏头像
    // 座位冷启动同拉已填缓存，首次进页多为缓存命中）；保活承诺=冷启动零增量。
    // refreshTick 变化=双击「我的」的重拉请求（缓存已被调用方清掉，真重拉）
    var myProfile by remember { mutableStateOf<MyProfile?>(null) }
    LaunchedEffect(pageActive, refreshTick) {
        if (!pageActive) return@LaunchedEffect
        val real = gateway as? TdLibGateway ?: return@LaunchedEffect
        myProfile = real.getMyProfile()
    }
    // 头像字节 → Bitmap：解码结果随字节记忆，避免重组重复解码
    val avatarBitmap = remember(myProfile?.avatarBytes) {
        myProfile?.avatarBytes?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }
    // 首字母（无头像时的字母圆）：显示名优先，用户名兜底
    val myInitial = (myProfile?.name ?: myProfile?.username)
        ?.firstOrNull { !it.isWhitespace() }
        ?.uppercase()
    // 账号卡文案：显示名（firstName+lastName，网关已拼好）→ 无名回落 @用户名；
    // 副标题 @username 优先，无用户名回落 +手机号，都没有回落连接状态（优雅降级：只显示能拿到的）
    val myName = myProfile?.name ?: myProfile?.username?.let { "@$it" }
    val mySubtitle = myProfile?.let { p ->
        p.username?.let { "@$it" } ?: p.phone?.let { "+$it" }
    }

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
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                // 底部避开手势条，最后一张卡片不贴边；R173 底栏在场时再让出栏高
                // （LocalBottomBarInset 随底栏显隐同曲线动画）
                .navigationBarsPadding()
                .padding(bottom = dev.tgfeed.ui.components.kit.LocalBottomBarInset.current),
        ) {
            // ---------- 顶部：OverlayBg 圆底返回钮 + 标题 ----------
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
                        .background(TgFeedTokens.OverlayBg)
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
                Text(
                    "我的",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TgFeedTokens.TextHigh,
                )
            }

            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapLg),
            ) {
                // ---------- 账号卡：圆形头像 + 名称/状态 ----------
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(MineCardRadius))
                        .background(TgFeedTokens.SurfaceHigh)
                        .padding(TgFeedTokens.GapLg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapLg),
                ) {
                    // 头像：真实头像字节解码 + 圆形裁剪铺满；无头像用首字母圆（TgBlue 底白字）；
                    // 加载中/拉取失败/演示模式回落人像图标占位圆
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(
                                // 字母圆要彩色底才显眼；头像图铺满，底色无所谓
                                if (avatarBitmap == null && myInitial != null) {
                                    TgFeedTokens.TgBlue
                                } else {
                                    TgFeedTokens.Surface
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            avatarBitmap != null -> Image(
                                bitmap = avatarBitmap,
                                contentDescription = "头像",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop,
                            )
                            myInitial != null -> Text(
                                myInitial,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                            )
                            else -> Icon(
                                Icons.Outlined.Person,
                                contentDescription = null,
                                tint = TgFeedTokens.TextMedium,
                                modifier = Modifier.size(30.dp),
                            )
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (demoMode) "演示账号" else myName ?: "Telegram 用户",
                            style = MaterialTheme.typography.titleMedium,
                            color = TgFeedTokens.TextHigh,
                        )
                        Text(
                            if (demoMode) "演示模式 · 本地演示数据" else mySubtitle ?: connectionState,
                            style = MaterialTheme.typography.bodySmall,
                            color = TgFeedTokens.TextMedium,
                        )
                    }
                }

                // ---------- 入口列表：设置/下载/片单/报告/发现/广场 ----------
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(MineCardRadius))
                        .background(TgFeedTokens.SurfaceHigh)
                        .padding(TgFeedTokens.GapLg),
                    verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapXs),
                ) {
                    MineEntryRow(
                        icon = Icons.Outlined.Settings,
                        title = "设置",
                        subtitle = "连接、播放、存储与更多",
                        onClick = onOpenSettings,
                    )
                    MineEntryRow(
                        icon = Icons.Outlined.FileDownload,
                        title = "我的下载",
                        subtitle = "管理已保存到相册的内容",
                        onClick = onOpenDownloads,
                    )
                    MineEntryRow(
                        icon = Icons.Outlined.PlaylistPlay,
                        title = "播放列表",
                        subtitle = "自建的片单，整单连播",
                        onClick = onOpenPlaylists,
                    )
                    // R387 播放队列→R391 播放台：多套节目单按序连播+口味今日单
                    MineEntryRow(
                        icon = Icons.Outlined.QueueMusic,
                        title = "播放台",
                        subtitle = "多套节目单，按序连播",
                        onClick = onOpenPlayQueue,
                    )
                    // R199 我的收藏（收藏查看模块入口）：收藏封面墙——点开回味、
                    // 按频道分组、角标取消；计数徽章=当前收藏数（活流，999+ 封顶）
                    MineEntryRow(
                        icon = Icons.Outlined.Star,
                        title = "我的收藏",
                        subtitle = "收藏过的内容，点开就能回味",
                        badge = formatFavoritesCount(favoriteKeys.size),
                        onClick = onOpenFavorites,
                    )
                    // 观看历史（观看报告旁边）：按观看时刻分组的历史清单，
                    // 点条目回 Feed 定位续播，长按删单条，顶栏可清空
                    MineEntryRow(
                        icon = Icons.Outlined.PlayCircle,
                        title = "观看历史",
                        subtitle = "看过的内容，点回去接着播",
                        onClick = onOpenWatchHistory,
                    )
                    // R203 那年今日（TL-2）：N 年前的今天你的频道们在传什么——年轮
                    // 策展页 + 任意日期回看；订阅频道深索引横截面（R202 时间索引），
                    // 索引整理中/无存货页内诚实空态
                    MineEntryRow(
                        icon = Icons.Outlined.Replay,
                        title = "那年今日",
                        subtitle = "N 年前的今天，你的频道们在传这些",
                        onClick = onOpenOnThisDay,
                    )
                    MineEntryRow(
                        icon = Icons.Outlined.History,
                        title = "观看报告",
                        subtitle = "本周观看时长、偏好分布与最常看的频道",
                        onClick = onOpenWatchReport,
                    )
                    // 频道体检（R132，R139 报告化）：健康分报告（总分环+结论先行），
                    // 变质/僵尸频道即点即关 + 底部撤销——全本地判据零网络；
                    // 副题与页内动词统一"取关"（设计文档 P1-④ 文案同源）
                    MineEntryRow(
                        icon = Icons.Outlined.HealthAndSafety,
                        title = "频道体检",
                        subtitle = "僵尸/变质频道排查，该取关的一键取关",
                        onClick = onOpenChannelHealth,
                    )
                    // 口味星图（R143）：互链图谱+观看行为渲染的星系图——星=频道（大小=观看时长，
                    // 色系=内容构成）、连线=互链/共现；可点星试吃、可导出 Wrapped 风海报
                    MineEntryRow(
                        icon = Icons.Outlined.AutoAwesome,
                        title = "口味星图",
                        subtitle = "你的频道宇宙星系图，可导出海报",
                        onClick = onOpenTasteMap,
                    )
                    // R209 热榜路由收敛（用户实锤②；R232 落点更新）：本行改跳转——
                    // 「TG 广场」的内容面=发现页热榜 sheet（R232 起热榜降级为发现页
                    // 入口行+PlazaBoardSheet），点击跳发现页+开热榜 sheet
                    // （openDiscoverPlaza 统一唯一入口，与底栏点按同一条滚页通道）；
                    // 副标题仍读 prefs 反映广场开关态（未开启时 sheet 空态就地开管理
                    // 面板——面板在 PlazaBoardScreen）
                    MineEntryRow(
                        icon = Icons.Outlined.Public,
                        title = "TG 广场",
                        subtitle = if (prefs.plazaEnabled) {
                            "已开启 · @${prefs.plazaChannel}"
                        } else {
                            "全站在看 / 点赞 / 弹幕互通（默认关闭）"
                        },
                        onClick = onOpenPlazaBoard,
                    )
                }
            }
        }
    }
}

// ==================== 以下为本页私有组件 ====================

/**
 * 入口行：36dp 圆形图标底托 + 标题/副标题两行 + 可选计数徽章 + 右箭头，整行可点，
 * 行高 ≥56dp。与设置页 SettingsActionRow 同一视觉语言。
 * [badge]（R199）：右箭头左侧的小计数胶囊（如「我的收藏 12」）——null 不渲染。
 */
@Composable
private fun MineEntryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    badge: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
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
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = TgFeedTokens.TextMedium,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = TgFeedTokens.TextHigh)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TgFeedTokens.TextMedium)
        }
        // R199 计数徽章：底色与图标底托同语言（白 8%），文字次级灰不抢主文案
        if (badge != null) {
            Text(
                badge,
                style = MaterialTheme.typography.labelMedium,
                color = TgFeedTokens.TextMedium,
                modifier = Modifier
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(horizontal = TgFeedTokens.GapSm, vertical = 2.dp),
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = TgFeedTokens.TextLow,
            modifier = Modifier.size(20.dp),
        )
    }
}
