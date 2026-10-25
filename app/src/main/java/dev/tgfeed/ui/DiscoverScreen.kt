package dev.tgfeed.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tgfeed.R
import dev.tgfeed.core.feed.DiscoveryFreshCard
import dev.tgfeed.core.feed.DiscoveryPreview
import dev.tgfeed.core.feed.DiscoverTimeBand
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.feed.FeedViewModelFactory
import dev.tgfeed.core.feed.RadarChannelCard
import dev.tgfeed.core.feed.ReverseExploreCard
import dev.tgfeed.core.feed.discoverTimeBandCounts
import dev.tgfeed.core.feed.rerankCandidatesByTimeWindow
import dev.tgfeed.core.td.ChannelBrief
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.ui.components.kit.adaptiveContentWidth
import dev.tgfeed.ui.components.DiscoverEntryRow
import dev.tgfeed.ui.components.DiscoverInfoStrip
import dev.tgfeed.ui.components.DiscoverLeadDot
import dev.tgfeed.ui.components.DiscoverPillBadge
import dev.tgfeed.ui.components.DiscoverSectionTitle
import dev.tgfeed.ui.components.HarvestGroupPickerSheet
import dev.tgfeed.ui.components.withOverlayShadow
import dev.tgfeed.ui.discover.LuckyBoxOverlay
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 候选卡片圆角：16dp（与设置页 SettingsCard 同值；那边是文件私有，这里自带一份） */
private val DiscoverCardRadius = 16.dp

/**
 * 发现页 v3（单页杂志流；替换 v2"推荐/周榜"双 tab 割裂版——用户实测"索然无味"的修复）：
 * R183 双宝藏架构重铸数据引擎（双宝藏全量 → 垃圾/广告过滤闸 → 公平轮转展示，
 * 用户终版设计——"守着金矿要饭"的修复）：候选 = 深索引金矿频道（宝藏一：丰度分/
 * 近期更新/存货，R163 公式同源）∪ 用户搜索群收获频道（宝藏二：答案库+群搜边热度），
 * 过 [AdPatternFilter] 过滤闸后按曝光账公平轮转（R176 思想）出榜——无口味个性化；
 * 每卡客观事实推荐语（「深索引验货（丰度 92 分）· 你的搜索群在传」）+ 佐证徽章。
 * 「试吃 / 收 / 弃」界面与四区段骨架/横滑/盲盒/接力全部保留（已验收 UI 不动）。
 *
 * 单页纵向流（标题栏之下全部在一个 LazyColumn 里一体滚动，自上而下）：
 *  1. 顶栏（v3 定稿·A2 精致大片风：AI 金矿横幅低透明度垫底、底部渐隐到纯黑）：
 *     圆形底托返回钮 + 大标题 + 骰子（手气不错·开盲盒，见 LuckyBoxOverlay）+ 刷新（换一批：
 *     VM 侧本轮排除当前已展示的 chatId——真换一批面孔，不是同池重排；小池子收口
 *     不排除、原地重排，见 triggerBatchRefresh），排除集只作用本轮、下轮自动复位；
 *     触发即上骨架屏（最短 [DISCOVER_SKELETON_MIN_MS] 毫秒，防"秒没"无感知）；
 *  2. R62 收录规模播报：顶部一行小字"今日新收录 N 个频道 · 累计 M 个"
 *     （VM 只读投影：24h 首见计数 + 图谱节点计数；无数据整行隐藏）；
 *     统计胶囊："今日收割 X 词 · 新发现 Y 频道 · 拦截 Z 广告"（为 0 的段不渲染，
 *     全 0/无数据整区隐藏）；
 *  3. 热词区（横滑 LazyRow，top3 名次亮阶 + ≥2 次 🔥 高亮，chip 可点跳搜索页
 *     并自动开搜；空整区隐藏）：R65 双词源——全网热搜词榜（每日收割，榜序优先）
 *     + 群友在搜 24h 聚合（去重补齐），榜缺席/过期回落纯群搜口径；
 *     热词区上方常驻「我的搜索群」入口行（R197：勾选搜索群改用户手动选——全部
 *     已加入群列表+建议角标，入口从设置页迁入发现模块属地；N=0 引导文案）；
 *  4. "刚冒出来的"横滑区（7 天内图谱首见频道小卡：头像 + 名称 + 相对时间，点了试吃；
 *     空整区隐藏）；
 *  5. 推荐候选区（R62 分类分区：按内容类目关键词粗分小节——写真/网盘/剧集/软件/学习/其它，
 *     每节标题 + 横滑卡，复用名片卡 v5 封面杂志风组件，封面换**真缩略图**——视频
 *     Video.thumbnail.file 走 TdLibGateway.loadCoverBytes 真实下载通道，与 Feed
 *     网格/全屏播放同一条链，VM 侧全局限流 4 路并发；不再用 minithumbnail 糊图全铺，
 *     真图缺失/失败回落频道稳定色对角渐变色块——不留灰底不铺糊图）；
 *     卡片信息位（R183）：基因鉴定条 → 佐证徽章胶囊行 → 主推荐语录条（客观事实）→
 *     证据文案行 → 预览小图行 → 胶囊形三按钮（试吃/收/弃）；
 *  6. 沉底"本周口碑 · 7天"区块：R183 语义换血——同批候选按近 7 天**发布条数**降序
 *     （深索引 items 时间戳第一手；旧口径"新增边计数"=二手共现噪音已废），计数 >0
 *     才进榜，直接作为页面最后一个 section，不再需要切换；数据与候选同批到达、
 *     作为列表尾项天然不挡上面内容（慢也只是晚出现）；
 * - 收下接力（P1-d）：收下成功后底部弹非模态小条（3 秒自动消失）"已收下 · 类似的还有 →"，
 *   点击把同臂 TOP3 置顶到列表顶部（纯 UI 置顶，不改打分）；
 * - 进页 LaunchedEffect 触发 vm.onDiscoverPageSettled()（R377 懒门授权源）：金矿
 *   快照（陈值先回/后台单飞重建）→ 搜索群信号聚合（本地读）→ 过滤闸 → 公平轮转
 *   → 权威校验，全程 IO 协程；W5 口味雷达由授权轮/恒许刷新顺带触发（每天最多
 *   巡逻一次，异步并入通道保留），窗内再开网络臂关零搜索族；
 * - Mock 网关无金矿/收割 → 候选恒空 → 空态引导文案（DISCOVERY-GRAPH §8
 *   Mock 降级口径）；收割数据为空时宝藏二段自然空转，照常走宝藏一候选。
 * - R207 视觉统一治理：本页 chrome 的色/形/字号一律走 docs/DISCOVER-DESIGN.md §10
 *   视觉语法（强调色 3 语义、徽章/入口行/区块标题/信息条共享件），结构零变化。
 * - R232 层级重排（用户实锤：追更被双 tab 挡住滑不到 + 热榜不该占一等位）：R209 的
 *   「发现|热榜」双 tab 与子 tab 横滑轨道整体撤销——页内横滑回归顶层 pager（发现
 *   左滑普通滑即达追更/右滑回首页，R177 消歧矩阵原语义；热词/候选卡 LazyRow 内滑
 *   照旧行内消费+页级 guard 吞残余）；热榜降级为内容区「全站热榜」入口行点开
 *   [PlazaBoardSheet]（二等公民）；外部热榜入口令 plazaTabRequestSeq 落点从「选
 *   热榜 tab」改「开热榜 sheet」（openDiscoverPlaza 统一唯一入口通道不变）。
 * - R215 时间维度融入式（取代 R213 三 tab 方案——用户否决「花里胡哨」）：时间不是
 *   用户「去」的地方，是内容「自带」的属性——①候选区顶部时段切换条「此刻|本周|
 *   全月|全年」四 chip（默认全年=现状零变化；切换=排序权重变化，timeWindowItems
 *   窗口聚合候选集交并→时间权重主导原位重排+类目行 Crossfade 转场）；③时间盲盒
 *   入口迁顶栏右上角时钟钮；深功能区收敛（事件研究器/宇宙作息表入口迁设置页
 *   高级区「时间维度工具」——代码不删只藏入口）。
 */
@Composable
fun DiscoverScreen(
    gateway: TdGateway,
    onBack: () -> Unit,
    onOpenChannel: (ChannelBrief) -> Unit,
    // 热词 chip 点击：带该词跳搜索页并自动开搜（宿主走 container.openSearch 既有通道；
    // 发现页不关闭，压在搜索页之下，搜索页返回即回本页）
    onSearchHotWord: (String) -> Unit,
    // R173 热榜条目点击进 Feed 定位播放（R232 起热榜住在 PlazaBoardSheet，播放出口
    // 语义不变——宿主走「锚定切源 + 轮询 + requestFeedJump」链路）；默认空实现，
    // 调用方不接线也可编译
    onPlazaPlay: (dev.tgfeed.core.feed.PlazaBoardEntry) -> Unit = {},
    // 热榜入口行/sheet 的广场开关读取（plazaEnabled 非流式，与 MineScreen 同款读取口径）
    prefs: dev.tgfeed.core.prefs.AppPrefs? = null,
    // R205 WK-3 事件研究器入口（深功能区常驻行，位于「我的搜索群」行下方）：
    // 打开独立研究器页（时间窗+关键词 → 内容流时间轴+共现传播路径）。
    // 默认空实现，调用方不接线也可编译
    onOpenEventResearch: () -> Unit = {},
    // R202 时间维度旗舰入口（架构师 IA 仲裁：时光机占发现页主位行，搜索群/研究器
    // 顺次排下）：打开时光机页——进页落在「此刻横截面」（DS-1 语义：发现=正在
    // 发生），页内拖滑块即全网时光机（TL-1）。默认空实现，调用方不接线也可编译
    onOpenTimeMachine: () -> Unit = {},
    // R204 TL-3 时间盲盒的播放出口：随机时刻+随机频道抽中的条目 → 宿主走
    // 「锚定切源 + 轮询 + requestFeedJump」链路回 Feed 定位起播（onPlazaPlay 同款）。
    // 默认空实现，调用方不接线也可编译
    onPlayTimeItem: (dev.tgfeed.core.td.VideoItem) -> Unit = {},
    // R209 外部热榜入口（我的页 TG 广场行等统一唯一入口的落点指令）：nonce 单调递增
    // （同值连发不被快照流去重吞——AppContainer.topLevelPageRequest 同款先例）；
    // R232 起递增=打开热榜 sheet（等本页落定 pageActive 才消费——滚页动画在途时
    // 先开会闪在别的页上；LaunchedEffect 消费后回调清槽——防二级页往返重放旧令，
    // 用户此后手动关掉 sheet 不被旧令重新拉回）。默认 0=无令
    plazaTabRequestSeq: Int = 0,
    // 热榜入口令消费回调（调用方接 AppContainer.consumeDiscoverPlazaTabRequest）；
    // 默认空实现，调用方不接线也可编译（令不清槽仅损失防重放面）
    onPlazaTabRequestConsumed: () -> Unit = {},
    // R177 顶层 pager 页位可见性：true = 本页是 pager 落定页（进页刷新只在此时触发）。
    // 默认 true 兼容独立使用；pager 挂载方传「page == pagerState.settledPage」——
    // 首页↔我的 0↔2 程序滚页会路过本页（中转组合），落定页门控挡掉中转期的刷新
    // 请求（refreshDiscovery 是网络+图谱的整链路，不许被过路动画触发）
    pageActive: Boolean = true,
) {
    // R232：发现页单页态（R209 的 plazaTab 双 tab 撤销——热榜降级 sheet，见下方
    // showPlazaSheet）
    val app = LocalContext.current.applicationContext as android.app.Application
    // 系统返回键 = 返回 Feed（状态驱动路由，不拦会退到桌面）
    // R189 保活门控：pager 三页常驻后本页 handler 与首页/我的页同台注册
    //（Compose 后注册者优先）——非落定页时禁用，返回语义归激活页自己的 handler
    BackHandler(enabled = pageActive, onBack = onBack)
    // 与 FeedScreen 共享 activity 作用域的同一个 ViewModel（同 key 约定：gw+identityHashCode）
    val vm: FeedViewModel = viewModel(factory = FeedViewModelFactory(gateway, app), key = "gw" + System.identityHashCode(gateway))
    val candidates by vm.discoveryCandidates.collectAsState()
    val sources by vm.discoverySources.collectAsState()
    val evidence by vm.discoveryEvidence.collectAsState()
    val previews by vm.discoveryPreviews.collectAsState()
    val loading by vm.discoveryLoading.collectAsState()
    // P1-d 周榜切片数据源：chatId → 近 7 天新增边计数（与候选同批刷新）
    val weeklyCounts by vm.discoveryWeeklyEdgeCounts.collectAsState()
    // P4-A 金矿：群友近 24h 搜索热词榜（"群友在搜"区）+ 图谱首见新发现 id 集（"刚冒出来的"角标）
    val hotWords by vm.discoveryHotWords.collectAsState()
    // R372 「群友正在搜」雷达频道卡：热词头部词→答案库返回频道（48h 窗词行的 Y 侧证据卡）
    val radarCards by vm.discoveryRadarCards.collectAsState()
    // R65 热词源：每日收割的全网热搜词榜（hot_words.json，GroupHarvester/HotWordBoard 自持，
    // freshWords 自带 3 天新鲜闸）并入热词区——榜词优先（榜序即热度序），缺席/过期
    // 回落现有 24h 群搜聚合口径。读盘走 IO 协程，首读后经 HotWordBoard 内存缓存零开销
    val boardHotWords by produceState(initialValue = emptyList<String>(), gateway) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                (gateway as? TdLibGateway)?.groupHarvester?.hotWordBoard?.freshWords().orEmpty()
            }.getOrDefault(emptyList())
        }
    }
    // 融合后的展示词表（榜词在前 + 群搜词去重补齐，截 [DISCOVER_HOT_WORD_ROW_LIMIT]）
    val mergedHotWords = remember(hotWords, boardHotWords) { mergeBoardHotWords(hotWords, boardHotWords) }
    val freshIds by vm.discoveryFreshIds.collectAsState()
    // 发现页升级：候选成员数（标题行"23.4万"段）+ 群搜来源词（推荐语）+ "刚冒出来的"横滑卡 + 顶部统计胶囊
    val memberCounts by vm.discoveryMemberCountView.collectAsState()
    val sourceWords by vm.discoverySourceWords.collectAsState()
    val freshCards by vm.discoveryFreshCards.collectAsState()
    val harvestStats by vm.discoveryHarvestStats.collectAsState()
    // R62 收录规模播报：今日新收录 N 个频道 · 累计 M 个（VM 侧只读图谱/首见时间戳投影）
    val intakeStats by vm.discoveryIntakeStats.collectAsState()
    // R133 反向探索卡（#17 每周一张"信息茧房打破器"）：与画像负相关的频道 + 基因行
    val reverseCard by vm.discoveryReverseCard.collectAsState()
    // R133 候选卡样本基因行（#19 频道基因鉴定）：chatId → "近期主打X · 平均 N 分钟"
    val genomeLines by vm.discoveryGenomeLines.collectAsState()
    // R183 双宝藏：客观事实主推荐语（推荐语录条）+ 佐证徽章（胶囊行——广场/群搜/上升
    // 计数降级为佐证；缺 key 不渲染）
    val recommendLines by vm.discoveryRecommendLines.collectAsState()
    val corroborations by vm.discoveryCorroborations.collectAsState()
    // R204 TL-3 时间盲盒：随机时刻+随机频道（R215 ③ 入口迁顶栏右上角时钟钮——
    // 深功能区入口行已撤，sheet/播放出口接线零变化）
    var showTimeBlindbox by remember { mutableStateOf(false) }
    // R215 ① 时段切换条：三档「本周|全月|全年」（R229「此刻」档移除——1h 窗的
    // 「正在更新」语义由追更 tab 频道动态卡流承接；默认全年=丰度优先现状零变化）；
    // 非默认档经 VM 查询口（timeWindowItems 窗口聚合候选集交并，索引毫秒级）取
    // 计数表，驱动候选原位重排——切换是排序权重变化，不是页面跳转
    var timeBand by remember { mutableStateOf(DiscoverTimeBand.YEAR) }
    var timeBandCounts by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    // v4"换一批"收口依据：本轮真实打分池大小（合并过滤后、排除与出榜截断前由 VM 定格）
    val poolSize by vm.discoveryPoolSize.collectAsState()
    // 进页触发一次刷新：R377 懒门公共口（首开/窗外再开=授权一轮：开门行+kickOnce
    // 预算内收割+网络臂开；窗内再开=本地聚合零网络）；换一批/骰子/双击仍走
    // refreshDiscovery 恒许路径不受门。
    // R133 反向探索卡同拍触发（内部自带周轮换与广场快照宽限，与候选刷新互不阻塞）。
    // R204 WK-2 作息表同拍触发（纯本地快照读毫秒级；过期后台单飞重建，不阻塞候选）。
    // R177：LaunchedEffect 键从 Unit 改 pageActive——顶层 pager 时代本页的「进页」
    // 语义 = 落定到本页（手势滑到/底栏点到/返回键回到），中转组合（0↔2 程序滚页
    // 路过）不触发；离开本页（落定到别页）即取消本轮 effect（VM 侧刷新不中断）
    LaunchedEffect(pageActive) {
        if (pageActive) {
            vm.onDiscoverPageSettled()
            vm.refreshReverseExploreCard()
            // R215 入口收敛：宇宙作息表触发随入口迁设置页高级区（此处不再刷）——
            // VM 数据口 refreshUniverseRhythm/universeRhythm 保留，SettingsScreen 消费
        }
    }

    // R215 ① 时段查询：档位或候选批变化即重取计数（IO+索引毫秒级；索引未就绪/
    // 空库=空表→零重排诚实降级，R183 三纪律继承）；全年档恒空表（零查询零变化）
    LaunchedEffect(timeBand, candidates) {
        timeBandCounts = if (timeBand.windowSec != null && candidates.isNotEmpty()) {
            vm.discoverTimeBandCounts(
                timeBand,
                candidates.mapTo(HashSet()) { it.id },
            )
        } else {
            emptyMap()
        }
    }

    // v3 刷新换一批：骨架屏标记——true 时候选区整体换骨架卡（"有感知"的核心：
    // 旧面孔先下场、新面孔未到之前绝不装作没变）
    var batchRefreshing by remember { mutableStateOf(false) }
    // ---------- R197 我的搜索群：手动选群（用户裁决「改成用户手动选」+「发现模块属地」） ----------
    // 入口常驻发现页（统计胶囊之下、热词区上方）；sheet 勾选翻格回调 tick →
    // 重读勾选计数刷新「N 个群」（loadUserSelectedGroups 纯读零网络）
    var showHarvestPicker by remember { mutableStateOf(false) }
    var harvestPickTick by remember { mutableStateOf(0) }
    val harvestGroupCount by produceState(initialValue = 0, gateway, harvestPickTick) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                (gateway as? TdLibGateway)?.groupHarvester?.loadUserSelectedGroups()?.size ?: 0
            }.getOrDefault(0)
        }
    }
    // P1-d 收下接力：刚收下的候选 id（非 null 时底部弹接力小条，3 秒自动消失）+ 其来源臂
    var relayAcceptedId by remember { mutableStateOf<Long?>(null) }
    var relayArm by remember { mutableStateOf<String?>(null) }
    // P1-d 接力置顶：同臂 TOP3 候选 id（纯 UI 置顶，不改打分）；刷新出新列表后失效 id 自然脱落
    var pinnedIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    // 接力小条 3 秒自动消失（每一次新收下重置计时）
    LaunchedEffect(relayAcceptedId) {
        if (relayAcceptedId != null) {
            delay(3_000)
            relayAcceptedId = null
        }
    }
    // ---------- v3 定稿「手气不错」开盲盒：骰子 = 从当前候选池随机抽一个频道开盒 ----------
    // 候选池已由 VM 过滤已加入/已屏蔽/软拒绝（出榜前 GetChatMember 权威校验），直接抽即可；
    // 抽中瞬间并行预取它最热条目的真封面（loadDiscoveryPreview 内部幂等 + 全局限流），
    // 摇动动画结束揭开时封面多半已就位，慢了则由揭示卡渐变色块兜底、不空等
    var luckyCandidate by remember { mutableStateOf<ChannelBrief?>(null) }
    var luckyRevealed by remember { mutableStateOf(false) }
    // 盲盒开着时系统返回键 = 关盲盒（后声明的 enabled BackHandler 优先于页面级返回）
    // R189 保活门控：非落定页时禁用（滑去别的页时盲盒开着也不许吃那页的返回键；
    // 滑回发现页自动恢复接管——盲盒开窗期间模态在页内，状态随页冻结原样）
    BackHandler(enabled = luckyCandidate != null && pageActive) {
        luckyCandidate = null
        luckyRevealed = false
    }
    // 当前展示序（R215 ①）：时段重排（时间权重主导——非全年档窗口计数多的候选
    // 前移，稳定排序平手保序；全年档=打分器原序零变化）→ 接力置顶（同臂 TOP3
    // 提前——纯 UI 重排不动打分器；用户显式动作优先于时段权重）
    val shown = remember(candidates, pinnedIds, timeBand, timeBandCounts) {
        val base = if (timeBand.windowSec == null) {
            candidates
        } else {
            rerankCandidatesByTimeWindow(candidates, timeBandCounts)
        }
        if (pinnedIds.isEmpty()) {
            base
        } else {
            val pinned = pinnedIds.mapNotNull { id -> base.firstOrNull { it.id == id } }
            pinned + base.filterNot { it.id in pinnedIds }
        }
    }
    // R62 分类分区（借鉴 soso 发现模块"分类浏览器"形态）：候选按内容类目粗分小组——
    // 关键词规则表见 [discoverCategoryOf]；组内保持打分输出序（含接力置顶序），
    // 小节展示顺序固定（见 DISCOVER_CATEGORY_ORDER），空类目整节不出现
    val categoryGroups = remember(shown) {
        val byCategory = LinkedHashMap<String, MutableList<ChannelBrief>>()
        for (c in shown) {
            byCategory.getOrPut(discoverCategoryOf(c.title, c.username)) { ArrayList() } += c
        }
        DISCOVER_CATEGORY_ORDER.mapNotNull { category ->
            byCategory[category]?.let { category to it }
        }
    }
    // 全局展示序索引（chatId → 名次）：分类分组后"首屏前 N 张走封面优先通道"的判定
    // 仍以整体排名为准（与旧单列语义一致），不因分组错序
    val shownOrderIndex = remember(shown) {
        shown.mapIndexed { index, c -> c.id to index }.toMap()
    }
    // 沉底"本周口碑"区块：同批候选按近 7 天发布条数降序取前 N（R183 语义换血：
    // 深索引 items 时间戳第一手——旧口径"近 7 天新增边计数"属二手共现噪音已废；
    // 条数 >0 才进榜）；数据与候选同批到达，作为列表尾项天然不挡上面内容——慢也只是晚出现
    val weeklyTop = remember(candidates, weeklyCounts) {
        candidates.mapNotNull { c ->
            val n = weeklyCounts[c.id] ?: 0
            if (n > 0) c to n else null
        }.sortedByDescending { it.second }.take(DISCOVER_WEEKLY_SECTION_MAX)
    }
    // 刷新换一批入口（v4 收口规则）：只要真实打分池比当前展示的多，就排除当前展示
    // 整批（VM 侧打分池剔除，排除集只作用本轮、下轮自动复位）——真换一批新面孔；
    // 池子见底（真实池 ≤ 展示数）才不传排除集、原地重排（每轮新种子下 Thompson
    // 仍会换序），骨架屏照给不丢感知。真实池深用 VM 发布的 poolSize——
    // 旧版按"发布数触顶 50"猜池深，小池子（池 30 张、展示 30 张）刷新等于原地洗牌，
    // 是"手动刷新不管用"的根因之一
    val triggerBatchRefresh: () -> Unit = {
        if (!loading && !batchRefreshing) {
            batchRefreshing = true
            val excludeIds = if (poolSize > shown.size) {
                shown.mapTo(HashSet()) { it.id }
            } else {
                emptySet()
            }
            vm.refreshDiscovery(excludeIds = excludeIds)
        }
    }
    // 骨架最短展示 [DISCOVER_SKELETON_MIN_MS]：VM 落闸（loading=false）后再停片刻，
    // 防刷新太快"骨架一闪而过"仍然无感知
    LaunchedEffect(loading, batchRefreshing) {
        if (batchRefreshing && !loading) {
            delay(DISCOVER_SKELETON_MIN_MS)
            batchRefreshing = false
        }
    }

    // R57 修复①（"打开发现页不是从头展示"）：列表状态显式 remember，弃用
    // rememberLazyListState（其内部走 rememberSaveable）——本 App 路由是状态驱动
    // when 链（MainActivity），离开页面即出组合，saveable 注册表会把上次滚动位置
    // 复活，再进页直接落在上次浏览到的中部；显式 remember 随页面出组合即归零，
    // 每次进页恒从顶部开始
    val listState = remember { LazyListState() }
    // R57 修复①补充（防"内容异步到货后视口漂移"）：LazyList 数据变化时锚定
    // "当前首项"（key 语义）——候选卡先上屏、顶部区段（统计胶囊/热词榜/刚冒出来
    // 的横滑）后到时，视口被锚在首张候选卡上，新区段全被顶到屏外，表现为
    // "从中间开始"。策略：用户首次手动拖拽前，顶部区段到货且视口已不在顶部，
    // 等本帧布局（含锚定重映射）落定后拉回 0 位；用户一旦滚过，永不抢视口
    var userScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) userScrolled = true
        }
    }

    // 频道真头像加载器：真实网关内部有进程内缓存；Mock/占位网关恒 null → 回落字母圆
    val avatarLoader: suspend (Long) -> ByteArray? = remember(gateway) {
        { id -> (gateway as? TdLibGateway)?.getChannelAvatar(id) }
    }

    // 统计胶囊分段（"今日收割 X 词 · 新发现 Y 频道 · 拦截 Z 广告"：每段中性引导点
    // ——R207 视觉语法：数据分段不是强调色现场，区分靠文案本身；为 0 的段不渲染，
    // 三项全 0（VM 发 null）整区隐藏，绝不显示 0）
    val statsSegments = harvestStats?.let { s ->
        buildList {
            if (s.harvestedWords > 0) add("今日收割 ${s.harvestedWords} 词")
            if (s.freshChannels > 0) add("新发现 ${s.freshChannels} 频道")
            if (s.blockedAds > 0) add("拦截 ${s.blockedAds} 广告")
        }.takeIf { it.isNotEmpty() }
    }
    // 顶部内容区是否有任何一段在展示（决定候选空态是整页居中还是跟在区段后的紧凑块）；
    // R133：反向探索卡同为顶部区段，一并计入；R372：雷达卡行同为顶部区段，一并计入
    val hasTopContent = intakeStats != null || statsSegments != null || mergedHotWords.isNotEmpty() ||
        radarCards.isNotEmpty() || freshCards.isNotEmpty() || reverseCard != null

    // R57 钉顶守卫：顶部区段的到货快照（有无/数量）任一变化即检查一次视口；
    // withFrameNanos 等本帧布局落定再拉回，确保钉顶是最后一次写
    // （R62：收录播报行同为顶部区段，一并纳入守卫键，防它后到时把视口顶下去；
    //  R133：反向探索卡后到（广场快照宽限）同样纳入；R372：雷达卡行同拍纳入）
    LaunchedEffect(intakeStats != null, statsSegments != null, mergedHotWords.size, radarCards.size, freshCards.size, reverseCard != null) {
        withFrameNanos { }
        if (!userScrolled &&
            (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0)
        ) {
            listState.scrollToItem(0)
        }
    }

    // ================= R232 层级重排（用户实锤：追更被双 tab 挡住滑不到 + 热榜
    // 不该占一等位）——R209 子 tab 轨道整体撤销 =================
    // 撤销面：mediaRailHost 轨道挂载/双 tab 行/DiscoverTabRailPolicy 查表链全部移除
    // ——页内横滑事件回归顶层 pager（R177 消歧矩阵原语义：发现页普通左滑直达追更，
    // 右滑回首页；热词/候选卡 LazyRow 内滑照旧行内消费+页级 guard 吞残余）。
    // 热榜降级新家：内容区「全站热榜」入口行（下方 LazyColumn）→ PlazaBoardSheet。
    var showPlazaSheet by remember { mutableStateOf(false) }
    // R209→R232 外部热榜入口消费（我的页「TG 广场」行等→openDiscoverPlaza：滚页
    // 请求+nonce 令）：令到达且**本页已落定**（pageActive）才开热榜 sheet——滚页
    // 动画在途时（settledPage 未到 1）先开 sheet 会闪在别的页上，等落定即开；消费
    // 后回调清槽（0=无令不动作；二级页往返重建组合也不重放旧令——消费即清语义
    // 与 R177 顶层滚页请求同一先例；pageActive 键保证等窗期间不丢令）
    LaunchedEffect(plazaTabRequestSeq, pageActive) {
        if (plazaTabRequestSeq > 0 && pageActive) {
            showPlazaSheet = true
            onPlazaTabRequestConsumed()
        }
    }

    // 平板/横屏：自适应内容限宽居中（共享 kit helper，与设置页/观看报告同一规格）
    Box(
        Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                // adaptiveContentWidth 必须在前：fillMaxSize 会先把约束钉成全宽，
                // 其后的 widthIn 是死代码（横屏限宽失效实锤，P1-6 七处同病的顺序纪律）
                .adaptiveContentWidth()
                .fillMaxSize(),
        ) {
            // ---------- 顶部标题区：纯色背景（用户反馈 AI 横幅背景图多余/显乱，改纯黑底） ----------
            Box(Modifier.fillMaxWidth().background(TgFeedTokens.InkBlack)) {
                // ---------- 标题行：圆形底托返回钮 + 大标题 + 时钟（时间盲盒）+ 骰子（手气不错）+ 刷新（换一批） ----------
                // R232：单页态——骰子/时钟/刷新恒显（R209 的「热榜 tab 藏发现钮」随双 tab 撤销；热榜 sheet 有自己的刷新钮）
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = TgFeedTokens.GapLg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
                ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(TgFeedTokens.SurfaceHigh)
                        .clickable(onClick = onBack), // 与设置页返回钮同一语言
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
                    "发现频道",
                    // 显式给色：本页根容器是裸 Box（非 Surface），style color 为 Unspecified 时
                    // 回落 LocalContentColor 默认值 Black → 黑底黑字标题不可见（走查实锤）
                    color = TgFeedTokens.TextHigh,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                // R215 ③ 时间盲盒入口迁顶栏右上角（原深功能区「时间盲盒」入口行撤销，
                // 交互零变化）：时钟图标与骰子（手气不错·Casino）区分——图标形 +
                // contentDescription 明示「时间盲盒」；中性 TextHigh（AccentRed 仍归
                // 手气不错一枚，R207 红线）；点击开 TimeBlindboxSheet，播放出口
                // onPlayTimeItem 接线不动
                IconButton(onClick = { showTimeBlindbox = true }) {
                    Icon(
                        Icons.Outlined.Schedule,
                        contentDescription = "时间盲盒·随机时刻",
                        tint = TgFeedTokens.TextHigh,
                    )
                }
                // v3 定稿·骰子 = 手气不错·开盲盒（原来是摆设：与刷新同通道换一批）。
                // 从当前候选池随机抽一个频道 → 全屏盲盒 overlay（摇动 → 揭示 → 直接试吃）；
                // A2 语言下它是页面唯一的霓虹红动作位——R207 视觉语法：AccentRed=可点主行动
                // 的唯一现场（盲盒揭示 CTA 在 LuckyBoxOverlay 同族），tint 撑起视觉锚点
                IconButton(
                    onClick = {
                        val pick = candidates.randomOrNull() ?: return@IconButton
                        luckyCandidate = pick
                        luckyRevealed = false
                        // 摇动动画期间并行预取它的封面（VM 内幂等 + 限流，重复点不炸）；
                        // 盲盒是用户盯着看的揭示卡，走优先通道与首屏前 3 张同档
                        vm.loadDiscoveryPreview(pick.id, priority = true)
                    },
                    enabled = candidates.isNotEmpty(), // 池空不开空盒（空态区有引导文案兜底）
                ) {
                    Icon(
                        // 实心骰子：描线版在小尺寸下辨识度差（用户反馈"看着不像骰子"）
                        Icons.Filled.Casino,
                        contentDescription = "手气不错·开盲盒",
                        tint = if (candidates.isEmpty()) TgFeedTokens.TextLow else TgFeedTokens.AccentRed,
                    )
                }
                IconButton(
                    onClick = triggerBatchRefresh,
                    enabled = !loading && !batchRefreshing, // 刷新中/骨架中禁用：与 VM 的防重入闸同语义
                ) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = "刷新候选",
                        tint = if (loading || batchRefreshing) TgFeedTokens.TextLow else TgFeedTokens.TextHigh,
                    )
                }
                } // 标题 Row 收口
            } // 标题区 Box 收口（纯黑底）

            when {
                // 首次冷加载（还什么内容都没有）→ 整页转圈
                loading && candidates.isEmpty() && !batchRefreshing -> Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // 页面级加载统一规格：40dp / 3dp / TextHigh
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        color = TgFeedTokens.TextHigh,
                        strokeWidth = 3.dp,
                    )
                }
                // v3 单页杂志流：统计胶囊 → 热词榜 → 刚冒出来的 → 候选卡 → 沉底本周口碑，
                // 全部进一个 LazyColumn 一体滚动（杂志流语义：页面即一条内容长河）
                else -> LazyColumn(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    // R57：显式状态（非 saveable），进页恒从顶部开始，见上方修复①
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
                    contentPadding = PaddingValues(bottom = TgFeedTokens.GapLg),
                ) {
                    // ---------- R133 反向探索卡（#17：每周一张"信息茧房打破器"，发现页顶部） ----------
                    // 与口味画像负相关（你从来不看的内容构成）+ 曝光账有正面记录的频道；
                    // VM 侧每周轮换落盘（store/reverse_explore.json），null 整卡隐藏。
                    // 点卡 = 与候选卡"试吃"同口径留痕后打开频道 Feed
                    reverseCard?.let { card ->
                        item(key = "reverse_explore") {
                            DiscoverReverseCard(
                                card = card,
                                avatarLoader = avatarLoader,
                                onOpen = {
                                    vm.tasteDiscoveryCandidate(card.brief.id)
                                    onOpenChannel(card.brief)
                                },
                            )
                        }
                    }

                    // ---------- R62 收录规模播报（顶部第一行小字；数据皆 0 时 VM 发 null 整行隐藏） ----------
                    // 借鉴 soso 发现模块的"今日新收录"播报：给发现页活力感；
                    // 数据来自 VM 只读投影（24h 首见计数 + 图谱节点计数），不为它改图谱
                    intakeStats?.let { stats ->
                        item(key = "intake_broadcast") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapXs),
                            ) {
                                // 引导小点：与统计胶囊分段同一中性引导族（R207）
                                DiscoverLeadDot()
                                Text(
                                    "今日新收录 ${stats.todayNew} 个频道 · 累计 ${stats.totalNodes} 个",
                                    color = TgFeedTokens.TextLow,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                )
                            }
                        }
                    }

                    // ---------- 顶部统计胶囊（今日收割/新发现/拦截广告；全 0 整区隐藏） ----------
                    if (statsSegments != null) {
                        item(key = "harvest_stats") {
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                                    .background(TgFeedTokens.SurfaceHigh)
                                    .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusPill))
                                    .padding(horizontal = TgFeedTokens.GapMd, vertical = TgFeedTokens.GapXs),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
                            ) {
                                statsSegments.forEach { text ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        DiscoverLeadDot()
                                        Spacer(Modifier.width(TgFeedTokens.GapXs))
                                        Text(
                                            text,
                                            color = TgFeedTokens.TextLow,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ---------- R202 全网时光机入口（时间维度旗舰·架构师仲裁主位行） ----------
                    // 与「我的搜索群」行等深功能区入口共用 [DiscoverEntryRow] 一个式
                    // （R207：图标中性、尾徽章中性——旗舰位语义由文案+图标形表达，
                    // 不再吃强调色）；进页=此刻横截面（发现页语义=正在发生），页内拖
                    // 滑块即穿越到任意时刻（TL-1）；视频长按面板「事件考古」格是
                    // 同屏第三入口（Event 模式）
                    item(key = "time_machine_entry") {
                        DiscoverEntryRow(
                            icon = Icons.Outlined.Schedule,
                            title = "全网时光机",
                            subtitle = "此刻全网在发什么 · 拖滑块穿越到任意时刻",
                            trailingBadge = "旗舰",
                            onClick = onOpenTimeMachine,
                        )
                    }

                    // ---------- R197 我的搜索群入口（用户裁决：发现模块属地，常驻行不随热词区隐藏） ----------
                    // 「N 个群」计数（0 时引导文案「选择你的搜索群，解锁群友在搜」——
                    // 群友在搜的数据源正是勾选的搜索群，0 群 = 该区无源可聚）；点击开选择器 sheet。
                    // R207：共用 [DiscoverEntryRow]，计数徽章中性一形（N>0「N 个群」/ 0 群引导「去选择」）
                    // R211 引导档：0 群时 emphasis（图标/副文案升一阶+尾徽章 TgBlue 主行动语义）——
                    // 搜索群直荐金矿未解锁的显眼引导（任务书③）。
                    item(key = "harvest_groups_entry") {
                        DiscoverEntryRow(
                            icon = Icons.Outlined.Groups,
                            title = "我的搜索群",
                            subtitle = if (harvestGroupCount == 0) "勾选后，群友搜到的频道会直接出现在这里" else null,
                            trailingBadge = if (harvestGroupCount > 0) "${harvestGroupCount} 个群" else "去选择",
                            onClick = { showHarvestPicker = true },
                            emphasis = harvestGroupCount == 0,
                        )
                    }

                    // ---------- R232 全站热榜入口（用户裁决「热榜是二等公民」：撤平级
                    // 双 tab，热榜改发现页内容区入口行——[PlazaBoardSheet] 的唯一页内
                    // 触发点；外部入口=我的页 TG 广场行经 openDiscoverPlaza 令开同一
                    // sheet） ----------
                    // 副文案带广场开关态（prefs 非流式读取，重组时刷新）：未开启=引导档
                    // emphasis+「去开启」徽章（R211 引导语义——开启动作在 sheet 空态内
                    // 就地开管理面板，一步）；已开启=中性热榜描述。慢链观感由 R232
                    // 慢链修承接（快照聚合即出榜+锚定并发反查原地升级）
                    item(key = "plaza_board_entry") {
                        val plazaOn = prefs?.plazaEnabled == true
                        DiscoverEntryRow(
                            icon = Icons.Outlined.Whatshot,
                            title = "全站热榜",
                            subtitle = if (plazaOn) "广场聚合的在看 / 点赞热度与频道礼包" else "开启 TG 广场后聚合全站热度（默认关闭）",
                            trailingBadge = if (plazaOn) null else "去开启",
                            onClick = { showPlazaSheet = true },
                            emphasis = !plazaOn,
                        )
                    }

                    // R215 ③+入口收敛（R213 砍掉项落地）：时间盲盒入口迁顶栏右上角
                    // 时钟钮（见标题行）；事件研究器/宇宙作息表入口迁设置页高级区
                    // 「时间维度工具」卡——**只做入口隐藏，代码不删**（EventResearchScreen
                    // /UniverseRhythmSheet/VM 数据口 onOpenEventResearch 接线全保留，
                    // 后续用户想用可加回）。发现页深功能区由此收敛为：时光机+搜索群+热榜
                    // 三行（R232 热榜入口行加入；用户嫌花里胡哨的「工具列表」感收敛）。

                    // ---------- P4-A 群友在搜 + R65 全网热搜词榜（热词源并入：榜词优先，
                    // 群搜聚合词去重补齐；榜缺席/过期自动回落纯群搜口径） ----------
                    // R372 窗口换血：群搜聚合窗 24h→48h（GoldmineHooks 留存窗满档——
                    // 旧 24h 定窗收割断流一天即整区全黑，48h 档下断流期存量数据仍可露）；
                    // 数据为空（刚装机/收割未跑出数据）整区隐藏；候选空时热词正好是
                    // "发现页还有什么可看"的兜底内容
                    if (mergedHotWords.isNotEmpty()) {
                        item(key = "hot_words") {
                            Column(Modifier.fillMaxWidth()) {
                                // 小节标题统一件 [DiscoverSectionTitle]（R207：中性引导条
                                // + 粗体白字——区块标题一级，不再红条）
                                // R65：热词榜在列时标题标明双词源（全网热搜 + 群友在搜）；
                                // R372：纯群搜档窗标签随窗诚实标注（48h=雷达窗常量）
                                DiscoverSectionTitle(
                                    if (boardHotWords.isNotEmpty()) "全网热搜 · 群友在搜" else "群友在搜 · 48h",
                                )
                                Spacer(Modifier.height(TgFeedTokens.GapSm))
                                // 横滑 LazyRow：词再多也不挤占候选列表纵向空间；
                                // 榜单化——top3 名次亮阶、窗内 ≥2 次叠 🔥 高亮（见 HotWordChip，
                                // R207：热度由 emoji+次数自带，chip 壳不染红不换边）；
                                // R65 榜词无窗计数（count=0）不显次数段，榜序即热度序
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
                                    itemsIndexed(mergedHotWords, key = { _, it -> it.first }) { index, (word, count) ->
                                        HotWordChip(rank = index + 1, word = word, count = count, onClick = {
                                            // 点热词 = 一次显式搜索提交：先记搜索历史（与 Feed 顶栏提交
                                            // 走 onRecordSearch 同口径），再带词跳搜索页自动开搜
                                            vm.recordSearch(word)
                                            onSearchHotWord(word)
                                        })
                                    }
                                }
                                // ---------- R372 雷达频道卡行（热词行之下、候选列表之上） ----------
                                // 「群友正在搜 XXX，返回了 YYY 频道」的 Y 侧：热词头部词→答案库
                                // 该词返回频道的证据卡（N 次命中/最近命中/成员数，纯本地零网络）；
                                // 卡空=词行仍在（词是兜底内容）；点击=试吃留痕后打开频道 Feed
                                // （与候选卡/"刚冒出来的"同口径）——chatId 直开零搜索零写
                                if (radarCards.isNotEmpty()) {
                                    Spacer(Modifier.height(TgFeedTokens.GapSm))
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd)) {
                                        items(radarCards, key = { it.chatId }) { card ->
                                            DiscoverRadarCard(
                                                card = card,
                                                avatarLoader = avatarLoader,
                                                onOpen = {
                                                    vm.tasteDiscoveryCandidate(card.chatId)
                                                    onOpenChannel(ChannelBrief(card.chatId, card.title, card.username))
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ---------- "刚冒出来的"横滑区（热词区之下、候选列表之上） ----------
                    // 7 天内图谱首见且 chatId/标题已解析的频道小卡；点了试吃（与候选卡"试吃"
                    // 同口径留痕后打开频道 Feed）；数据为空整区隐藏
                    if (freshCards.isNotEmpty()) {
                        item(key = "fresh_rail") {
                            Column(Modifier.fillMaxWidth()) {
                                DiscoverSectionTitle("刚冒出来的")
                                Spacer(Modifier.height(TgFeedTokens.GapSm))
                                // A2 密度：裸头像横列（去卡片壳），间距放宽到 GapMd
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd)) {
                                    items(freshCards, key = { it.brief.id }) { card ->
                                        DiscoverFreshMiniCard(
                                            card = card,
                                            avatarLoader = avatarLoader,
                                            onClick = {
                                                vm.tasteDiscoveryCandidate(card.brief.id)
                                                onOpenChannel(card.brief)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ---------- R215 ① 时段切换条（候选区顶部·极简横条：本周|全月|全年） ----------
                    // R229 起三档（「此刻」档移除并入追更 tab）；默认=全年（丰度优先=现状，
                    // 零变化）；切换=排序权重变化不是页面跳转——候选卡原位重排（类目横滑行
                    // Crossfade 转场）；视觉：chip 壳与热词 chip 同档（Pill+SurfaceHigh+
                    // 发丝+labelMedium——R207 独立一档），选中=AccentRed 字（任务书拍板
                    // 「与现有 tab 同语言」的选中档）
                    if (shown.isNotEmpty() || batchRefreshing) {
                        item(key = "time_band_chips") {
                            Row(horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
                                DiscoverTimeBand.values().forEach { band ->
                                    DiscoverTimeBandChip(
                                        label = band.label,
                                        selected = band == timeBand,
                                        onClick = { timeBand = band },
                                    )
                                }
                            }
                        }
                    }

                    // ---------- 候选区：刷新骨架 / 空态 / 卡片流 + 沉底本周口碑 ----------
                    when {
                        // v3 刷新换一批：旧面孔下场、骨架卡顶上（"有感知"的核心层）
                        batchRefreshing -> items(
                            count = DISCOVER_SKELETON_COUNT,
                            key = { "skeleton_$it" },
                        ) { DiscoverSkeletonCard() }
                        // 空态：图谱还没长出来 / Mock 模式 / 本轮排除后真空——不是死态，
                        // 雷达与图谱都在后台攒线索，给雷达纹样 + 体面文案 + 细 spinner
                        shown.isEmpty() -> item(key = "empty_state") {
                            DiscoverEmptyState(
                                // 顶部无其他区段时撑满整页居中（与旧版空态观感一致）；
                                // 有热词/新片等内容时收为紧凑块跟在区段之后
                                if (hasTopContent) {
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = TgFeedTokens.GapXl)
                                } else {
                                    Modifier.fillParentMaxSize()
                                },
                            )
                        }
                        // 候选卡片流（R62 分类分区：小节标题 + 横滑卡，复用候选卡组件；
                        // 组内保持打分输出序/接力置顶序，空类目整节不出现）
                        else -> {
                            categoryGroups.forEach { (category, group) ->
                                item(key = "category_header_$category") {
                                    DiscoverSectionTitle("$category · ${group.size}")
                                }
                                item(key = "category_row_$category") {
                                    // R215 ①：时段切换=原位重排的转场——类目横滑行包
                                    // Crossfade(targetState=timeBand)（tween=AnimMed，
                                    // MotionTokens 时长三档单一出处，内容域 tween 纪律；
                                    // 旧序行淡出新序行淡入，重排可感不跳）。（R232 起
                                    // 本页无轨道层，行内残余横滑由页级 guard 吞）
                                    Crossfade(
                                        targetState = timeBand,
                                        animationSpec = tween(TgFeedTokens.AnimMed),
                                        label = "discoverTimeBand",
                                    ) { _ ->
                                    // 横滑 LazyRow：卡片限宽 [DiscoverCategoryCardWidth]，
                                    // 一屏约见 1.2 张（露出下一张边缘暗示可横滑）
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd)) {
                                        items(group, key = { it.id }) { c ->
                                            Box(Modifier.width(DiscoverCategoryCardWidth)) {
                                                DiscoverCandidateCard(
                                                    channel = c,
                                                    sourceTag = sources[c.id],
                                                    // P4-A 新片雷达：图谱首见的新发现频道打"刚冒出来的"角标
                                                    fresh = c.id in freshIds,
                                                    // 成员数（标题行"23.4万"段，未知为 null 不显示）
                                                    memberCount = memberCounts[c.id],
                                                    // 群搜来源词（推荐语「群友搜『XX』找到」，非答案库来源为 null）
                                                    sourceWord = sourceWords[c.id],
                                                    // R183 客观事实主推荐语（双宝藏推荐语录条；优先于群搜来源词）
                                                    recommendLine = recommendLines[c.id],
                                                    // R183 佐证徽章（「N 群在搜」「本周 N 条」「系统验货 T1」…）
                                                    corroborationBadges = corroborations[c.id].orEmpty(),
                                                    // R133 样本基因行（#19："近期主打X · 平均 N 分钟"，预览页现算；缺 key 不渲染）
                                                    genomeLine = genomeLines[c.id],
                                                    evidenceLines = evidence[c.id].orEmpty(),
                                                    preview = previews[c.id],
                                                    avatarLoader = avatarLoader,
                                                    // v4 首屏优先：整体排名前 N 张卡的封面下载走优先通道
                                                    // （插队到限流队列头部，其余卡照旧排队）——首屏先出图；
                                                    // 分组后仍以全局名次判定，与旧单列语义一致
                                                    onLoadPreview = {
                                                        vm.loadDiscoveryPreview(
                                                            c.id,
                                                            priority = (shownOrderIndex[c.id] ?: Int.MAX_VALUE) < DISCOVER_PRIORITY_PREVIEW_CARDS,
                                                        )
                                                    },
                                                    // 试吃显式记录（P1-a #1：发现漏斗 曝光→试吃→收/弃 的中间环归因），
                                                    // 随后打开频道 Feed——不加订阅、不留其他痕迹（语义不变）
                                                    onTaste = { vm.tasteDiscoveryCandidate(c.id); onOpenChannel(c) },
                                                    onAccept = {
                                                        // P1-d 收下接力：先定格来源臂（收下后卡片即移除，
                                                        // 臂归属表底账仍留），再弹接力小条
                                                        relayArm = vm.discoveryArmOf(c.id)
                                                        relayAcceptedId = c.id
                                                        vm.acceptDiscoveryCandidate(c)
                                                    },
                                                    onDismiss = { vm.dismissDiscoveryCandidate(c.id) },
                                                )
                                            }
                                        }
                                    }
                                    } // Crossfade(timeBand) 收口（R215 ① 原位重排转场）
                                }
                            }
                            // ---------- 沉底"本周口碑 · 7天"（v3：原周榜内容作为页面最后
                            // 一个 section，不再需要切换；全 0 整区隐藏） ----------
                            if (weeklyTop.isNotEmpty()) {
                                item(key = "weekly_board") {
                                    DiscoverWeeklySection(
                                        entries = weeklyTop,
                                        avatarLoader = avatarLoader,
                                        onOpen = { brief ->
                                            // 与候选卡"试吃"同口径留痕后打开频道 Feed
                                            vm.tasteDiscoveryCandidate(brief.id)
                                            onOpenChannel(brief)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    // 底部让开系统导航条 + 底栏让位（R173 LocalBottomBarInset，一级页在场）
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

        // ---------- P1-d 收下接力小条（非模态 Snackbar 风格，3 秒自动消失） ----------
        // 点"→"把与被收候选同臂（无臂归因退化为同"种"同源角标）的 TOP3 置顶到列表顶部——
        // 纯 UI 置顶，不改打分；整条都是热区（"→"是动作暗示，不单独切热区）
        // R173：底栏在场时抬到栏上沿之上（LocalBottomBarInset 同拍让位）
        val relayId = relayAcceptedId
        if (relayId != null) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(
                        bottom = TgFeedTokens.GapLg +
                            dev.tgfeed.ui.components.kit.LocalBottomBarInset.current,
                    )
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                    .background(TgFeedTokens.OverlayBgStrong)
                    .clickable {
                        val arm = relayArm
                        val pool = if (arm != null) {
                            candidates.filter { vm.discoveryArmOf(it.id) == arm }
                        } else {
                            // 无臂归因兜底：同"种"（同源角标）候选——
                            // 角标表随刷新重建，被收者的条目此刻仍留底
                            val tag = sources[relayId]
                            if (tag != null) candidates.filter { sources[it.id] == tag } else emptyList()
                        }
                        pinnedIds = pool.take(3).map { it.id }
                        relayAcceptedId = null // 接力完成即收条
                    }
                    .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapMd),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "已收下 · 类似的还有 →",
                    color = TgFeedTokens.TextHigh,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }

        // ---------- v3 定稿「手气不错」盲盒全屏 overlay（压在含接力小条的一切内容之上） ----------
        // 摇动 1.6s（5 × AnimSlow）→ 揭示卡（封面 + 频道名 + 成员数 + "手气如何"）；
        // 封面取预览第一张真图（抽盒瞬间已并行预取），未就位则频道稳定色渐变兜底
        val lucky = luckyCandidate
        if (lucky != null) {
            val luckyCoverBytes = previews[lucky.id]?.thumbs?.firstOrNull { it != null }
            val luckyCover = remember(luckyCoverBytes) {
                luckyCoverBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }
            LuckyBoxOverlay(
                candidate = lucky,
                revealed = luckyRevealed,
                coverImage = luckyCover,
                coverFallbackColor = discoverAvatarColor(lucky.id),
                memberCountText = memberCounts[lucky.id]?.takeIf { it > 0 }?.let(::formatMemberCount),
                onShakeDone = { luckyRevealed = true },
                onTaste = {
                    // 直接试吃：与候选卡"试吃"同口径留痕后打开频道 Feed，盲盒收场
                    vm.tasteDiscoveryCandidate(lucky.id)
                    luckyCandidate = null
                    luckyRevealed = false
                    onOpenChannel(lucky)
                },
                onDismiss = {
                    luckyCandidate = null
                    luckyRevealed = false
                },
            )
        }

        // ---------- R197 我的搜索群选择器 sheet（发现页入口与设置页跳转行共用同款组件） ----------
        // ModalBottomSheet 自带系统返回关闭（后组合的 back handler 优先于页面级返回）；
        // 勾选翻格回调 tick → 上方入口行计数即时刷新
        if (showHarvestPicker) {
            HarvestGroupPickerSheet(
                gateway = gateway,
                onDismiss = { showHarvestPicker = false },
                onSelectionChanged = { harvestPickTick++ },
            )
        }

        // ---------- R204 WK-2 宇宙作息表面板：R215 入口迁设置页高级区，本页不再挂载 ----------
        //（sheet 本体 UniverseRhythmSheet.kt 与 VM 数据口保留，SettingsScreen「时间维度工具」消费）

        // ---------- R204 TL-3 时间盲盒面板（随机时刻+随机频道 → 该秒起播） ----------
        // R215 ③：触发点从深功能区入口行迁顶栏右上角时钟钮（就近锚定原则随触发点走，
        // 仍是底部 sheet——顶栏触发与发现页主位行同排先例（时光机入口）不同：
        // 盲盒是「摇一个随机时刻」的轻彩蛋，sheet 形态交互零变化）
        if (showTimeBlindbox) {
            dev.tgfeed.ui.TimeBlindboxSheet(
                gateway = gateway,
                onPlay = { item ->
                    showTimeBlindbox = false
                    onPlayTimeItem(item)
                },
                onDismiss = { showTimeBlindbox = false },
            )
        }

        // ---------- R232 全站热榜 sheet（热榜降级后的新家；触发点=上方入口行 /
        // 外部令（我的页 TG 广场行 → openDiscoverPlaza）） ----------
        // onPlay 包装：先收 sheet 再走宿主播放链——sheet 是 Dialog 独立窗口，发现页
        // 落到后台保活（R189 四页常驻）时开着会盖屏/吃返回；板内「去刷刷看」离场
        // 同理由 PlazaBoardSheet 的 onLeave 承接（先播收起动画再移除）。返回键/
        // scrim 下拖 = 用户自关。prefs 空兜底：只可能发生在未接线的调用方（不渲染）
        if (showPlazaSheet && prefs != null) {
            PlazaBoardSheet(
                gateway = gateway,
                prefs = prefs,
                onPlay = { entry ->
                    showPlazaSheet = false
                    onPlazaPlay(entry)
                },
                onDismiss = { showPlazaSheet = false },
            )
        }
    }
}

/**
 * 候选名片卡 v5（v3 杂志流配套·封面真图化）：
 * 预览里第一张**下载成功的真缩略图** 16:9 **全出血**铺顶当封面（卡片四周 0 内边距，
 * 圆角由卡壳统一裁；真图来自 TdLibGateway.loadCoverBytes 真实下载通道，
 * 不再用 minithumbnail 糊图全铺——"缩略图加载不出来/糊成一团"的修复），
 * 底部渐变遮罩上直接压标题组（头像 + 频道名）、左上角标胶囊（A2 深底描边小胶囊）
 * 与右上成员数芯片（A2 时长胶囊位）——图即封面；
 * 真图缺失/全部失败回落频道稳定色对角渐变色块（[DiscoverThumbFallback]），
 * 不留灰底、不铺糊图，杂志感不塌。
 * 封面之下依次为：R133 基因鉴定条（R207：统一信息条壳 DiscoverInfoStrip，「近期主打X · 平均 N 分钟」——
 * 把试吃成本降到读一行字）→ 群搜来源词推荐语录条（蓝引条 + 深底横条）→ 证据文案行
 * （最多 2 条）→ 其余预览小图行（失败位同样回落渐变色块）→ 胶囊形三按钮（试吃/收/弃）。
 * 卡片本身不可点——三个动作语义差异大，全部走显式按钮，防误触。
 *
 * @param memberCount 成员数（发现档案懒加载产物；null = 未知，封面右上芯片不渲染）
 * @param sourceWord 群搜来源词（答案库 chatId→词 映射；非 null 时显示推荐语「群友搜『XX』找到」）
 * @param recommendLine R183 客观事实主推荐语（双宝藏推荐语录条：「深索引验货（丰度 92 分）·
 *   你的搜索群在传」等；null = 无推荐语回落 sourceWord 条）
 * @param corroborationBadges R183 佐证徽章（「N 群在搜」「本周 N 条」「系统验货 T1」「全站 N 人推荐」
 *   「冷门宝藏」——终版修正：广场/群搜/上升计数降级为佐证胶囊，不进主排序；空不渲染整行）
 * @param genomeLine R133 样本基因行（预览页现算；null = 预览未回/样本不足，不渲染该条）
 * @param evidenceLines 证据文案（客观事实行，最多 2 条；空则不渲染该区块）
 * @param fresh P4-A 新片雷达：是否"图谱首见"的新发现频道（true 时来源角标旁加"刚冒出来的"）
 * @param preview 候选预览包（最近条目总数 + 逐条真缩略图字节）；null = 尚未加载，
 *   thumbs 元素 null = 该条无封面文件/下载失败（回落渐变色块）
 * @param onLoadPreview 卡片入组合时触发一次预览拉取（VM 内防重入 + 进程内缓存）
 */
@Composable
private fun DiscoverCandidateCard(
    channel: ChannelBrief,
    sourceTag: String?,
    fresh: Boolean,
    memberCount: Int?,
    sourceWord: String?,
    recommendLine: String?,
    corroborationBadges: List<String>,
    genomeLine: String?,
    evidenceLines: List<String>,
    preview: DiscoveryPreview?,
    avatarLoader: suspend (Long) -> ByteArray?,
    onLoadPreview: () -> Unit,
    onTaste: () -> Unit,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 真头像异步加载：行进组合时查/拉一次；loader 内部带进程内缓存，
    // LazyColumn 滚动出行再回来只是缓存命中，不会重复触发 TDLib 下载
    val avatarImage = rememberChannelAvatar(channel.id, avatarLoader)
    LaunchedEffect(channel.id) {
        onLoadPreview() // 预览拉取：行级懒加载（每候选只拉一页，VM 内幂等）
    }
    // 封面头图：预览里第一张下载成功的真缩略图（indexOfFirst 跳过失败位）；
    // 其余条目（含失败位 null）进小图行
    val heroIndex = remember(preview) { preview?.thumbs?.indexOfFirst { it != null } ?: -1 }
    val heroBytes = remember(preview, heroIndex) {
        if (heroIndex >= 0) preview?.thumbs?.get(heroIndex) else null
    }
    val heroImage = remember(heroBytes) {
        heroBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    val smallThumbs = remember(preview, heroIndex) {
        preview?.thumbs.orEmpty().filterIndexed { i, _ -> i != heroIndex }
    }
    // 卡壳形状：圆角 16dp + 0.5dp 发丝描边（卡片悬浮在黑场上的层次，与分屏格子同语言）
    val cardShape = RoundedCornerShape(DiscoverCardRadius)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(TgFeedTokens.SurfaceHigh)
            .border(0.5.dp, TgFeedTokens.OverlayHairline, cardShape),
    ) {
        // ---------- 封面：16:9 全出血（真图优先，无图回落渐变色块）+ 底部渐变遮罩，标题组/角标全部压图 ----------
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(TgFeedTokens.Surface),
        ) {
            if (heroImage != null) {
                Image(
                    bitmap = heroImage,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // 无图回落：频道稳定色对角渐变色块（预览未回/无封面/下载失败的统一兜底——
                // 不留灰底、不铺 minithumbnail 糊图；渐变之上仍压标题组，杂志感不塌）
                DiscoverThumbFallback(channel.id, Modifier.fillMaxSize())
            }
            // R162 零遮罩：旧版底部渐变遮罩（上 35% 起渐、底部浓墨 0.88，R36）删除——
            // 压图频道名改 withOverlayShadow 描边可读（视频页干净基准同款），封面原色不染
            // 角标胶囊组压图左上：onImage 变体用深浮层底，任意画面上都可读
            if (sourceTag != null || fresh) {
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(TgFeedTokens.GapSm),
                    horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapXs),
                ) {
                    if (sourceTag != null) DiscoverSourceBadge(sourceTag, onImage = true)
                    // P4-A 新片雷达角标：与来源角标正交（任何来源的候选都可能是"刚冒出来的"）；
                    // R207：统一走 [DiscoverPillBadge] 中性一形（深浮层底+发丝描边），文案表义
                    if (fresh) DiscoverPillBadge("刚冒出来的", onImage = true)
                }
            }
            // 成员数芯片压图右上（R207：统一 [DiscoverPillBadge] onImage 档——深底描边
            // 小胶囊，任意画面可读；成员数未知则不渲染——信息位移出标题组，封面标题组
            // 只留频道名，密度向 A2 靠齐）
            if (memberCount != null && memberCount > 0) {
                DiscoverPillBadge(
                    formatMemberCount(memberCount),
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(TgFeedTokens.GapSm),
                    onImage = true,
                )
            }
            // 标题组压图左下：头像（带发丝光环）+ 频道名
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(TgFeedTokens.GapMd),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DiscoverAvatarImage(
                    avatarImage = avatarImage,
                    channelId = channel.id,
                    title = channel.title,
                    size = 36.dp,
                    ringColor = TgFeedTokens.OverlayHairline,
                )
                Spacer(Modifier.width(TgFeedTokens.GapSm))
                // weight(1f)：压图标题组按"封面宽 − 头像 − 内边距"量宽，长标题省略号收尾不越界
                Column(Modifier.weight(1f)) {
                    Text(
                        channel.title,
                        color = TgFeedTokens.TextHigh,
                        // R162：压图频道名可读性走描边（渐变遮罩已删，视频页基准同款）
                        style = MaterialTheme.typography.titleMedium.withOverlayShadow(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // ---------- 封面之下的正文区（统一 GapLg 内边距） ----------
        Column(
            Modifier.padding(TgFeedTokens.GapLg),
            verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
        ) {
            // ---------- R133 基因鉴定条（#19：正文区第一信息位——"这个频道是干嘛的"
            // 读一行字就有数，试吃成本降到读一行；null 不渲染；R207：统一信息条壳） ----------
            if (genomeLine != null) DiscoverInfoStrip("🧬 $genomeLine")
            // ---------- R183 佐证徽章行（客观计数胶囊：「N 群在搜」「本周 N 条」「系统验货
            // T1」…——终版修正：广场/群搜/上升降级为佐证不进主排序；空不渲染整行；
            // R207：统一 [DiscoverPillBadge] 中性一形，⚠ 前缀走警示语义=WarningRed 字） ----------
            if (corroborationBadges.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapXs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    corroborationBadges.forEach { DiscoverPillBadge(it, warn = it.startsWith("⚠")) }
                }
            }
            // ---------- R183 主推荐语录条（客观事实——「深索引验货（丰度 92 分）· 你的
            // 搜索群在传」/「近 7 天更新 N 条」）+ 群搜来源词兜底条 + 证据文案行 ----------
            // 推荐语与来源词/证据同卡去重防复读（群搜词已并入推荐语文案）；
            // R207：统一 [DiscoverInfoStrip] 中性引条（信息类型由文案本身表达）
            if (recommendLine != null) {
                DiscoverInfoStrip(recommendLine)
            } else if (sourceWord != null) {
                DiscoverInfoStrip("群友搜「$sourceWord」找到")
            }
            val evidenceShown = remember(evidenceLines, recommendLine) {
                if (recommendLine != null) {
                    // 推荐语已点名的内容（丰度分/搜索群）不在证据行复读
                    evidenceLines.filterNot {
                        it.startsWith("索引验货：") || it.startsWith("你的 ") || it.startsWith("搜「")
                    }
                } else if (sourceWord != null) {
                    evidenceLines.filterNot { it.startsWith("搜「") }
                } else {
                    evidenceLines
                }
            }
            if (evidenceShown.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapXs)) {
                    evidenceShown.forEach { DiscoverEvidenceBullet(it) }
                }
            }

            // ---------- 预览小图行（封面吃掉第一张成功图，其余逐条展示；
            // 失败位 null 回落渐变色块——不摆灰底空壳；预览未回/无条目整条隐藏） ----------
            if (smallThumbs.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                ) {
                    smallThumbs.forEachIndexed { index, bytes ->
                        val thumb = remember(bytes) {
                            bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                        }
                        if (thumb != null) {
                            Image(
                                bitmap = thumb,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(width = 92.dp, height = 56.dp)
                                    .clip(RoundedCornerShape(TgFeedTokens.RadiusSm))
                                    .background(TgFeedTokens.Surface),
                            )
                        } else {
                            // 无图位：同款渐变色块（id+index 错开同卡内的色相，防一排同色块）
                            DiscoverThumbFallback(
                                channel.id + index,
                                Modifier
                                    .size(width = 92.dp, height = 56.dp)
                                    .clip(RoundedCornerShape(TgFeedTokens.RadiusSm)),
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "最近 ${preview?.totalCount ?: smallThumbs.size} 条",
                        color = TgFeedTokens.TextLow,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }

            // ---------- 三按钮：试吃 / 收 / 弃（胶囊形，与全 App 胶囊语言一致） ----------
            Row(horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
                // 试吃：打开该频道 Feed 先刷为敬（不加订阅、不留痕迹）
                FilledTonalButton(
                    onClick = onTaste,
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp),
                    shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
                    contentPadding = PaddingValues(horizontal = TgFeedTokens.GapSm),
                ) {
                    Text("试吃", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
                // 收：订阅该频道（进订阅追更集）——R207：TgBlue=提交型主按钮唯一现场
                // （Material primary，全 App 按钮语言，不属发现页 chrome 强调色盘点）
                Button(
                    onClick = onAccept,
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp),
                    shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
                    contentPadding = PaddingValues(horizontal = TgFeedTokens.GapSm),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TgFeedTokens.TgBlue,
                        contentColor = Color.White,
                    ),
                ) {
                    Text("收", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
                // 弃：软拒绝梯度——首次弃 30 天不再出现；复出后二次弃才永久拉黑
                // （R207：WarningRed=破坏性动作语义保留）
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp),
                    shape = RoundedCornerShape(TgFeedTokens.RadiusPill),
                    contentPadding = PaddingValues(horizontal = TgFeedTokens.GapSm),
                    colors = ButtonDefaults.textButtonColors(contentColor = TgFeedTokens.WarningRed),
                ) {
                    Text("弃", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
}

/**
 * 刷新骨架卡（v3"换一批"的感知层）：与候选卡同壳（16dp 圆角 + 发丝描边），
 * 16:9 封面位 + 一宽一窄文案条 + 三胶囊按钮位全部占位，白调微光呼吸脉动
 * （最短展示 [DISCOVER_SKELETON_MIN_MS]——刷新太快也停够时长，防"没变化"错觉；
 * 650ms 呼吸半程=连续占位脉动周期非转场——MotionTokensPolicyTest 豁免清单登记）。
 */
@Composable
private fun DiscoverSkeletonCard() {
    val transition = rememberInfiniteTransition(label = "discoverSkeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "discoverSkeletonAlpha",
    )
    // "骨头"色：白调微光，随脉动呼吸（token 派生，不造新色）
    val bone = TgFeedTokens.TextHigh.copy(alpha = alpha)
    val cardShape = RoundedCornerShape(DiscoverCardRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(TgFeedTokens.SurfaceHigh)
            .border(0.5.dp, TgFeedTokens.OverlayHairline, cardShape),
    ) {
        // 封面位（16:9，与候选卡封面同规格）
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(bone),
        )
        Column(
            Modifier.padding(TgFeedTokens.GapLg),
            verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
        ) {
            // 文案位：一宽一窄两条
            Box(
                Modifier
                    .fillMaxWidth(0.82f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                    .background(bone),
            )
            Box(
                Modifier
                    .fillMaxWidth(0.56f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                    .background(bone),
            )
            // 按钮位：三等分胶囊（与候选卡三按钮同位同高）
            Row(horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm)) {
                repeat(3) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                            .background(bone),
                    )
                }
            }
        }
    }
}

/**
 * 沉底"本周口碑 · 7天"区块（v3 单页杂志流：原"周榜" tab 内容改为页面最后一个
 * section，不再需要切换）：同批候选按近 7 天**发布条数**降序（R183：深索引第一手
 * 数据，条数 >0 才进榜，由调用方过滤好），行 = 名次号（R207：top3 亮阶 TextHigh/
 * 其余 TextLow 的中性阶差，与热词榜同一语言）+ 真头像 + 频道名 + 本周条数；点行 =
 * 试吃留痕 + 打开频道 Feed（与候选卡"试吃"同口径，由调用方接线）。
 */
@Composable
private fun DiscoverWeeklySection(
    entries: List<Pair<ChannelBrief, Int>>,
    avatarLoader: suspend (Long) -> ByteArray?,
    onOpen: (ChannelBrief) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        // A2 小节标题统一件 [DiscoverSectionTitle]（与热词/新片两区同规格）
        DiscoverSectionTitle("本周口碑 · 7天")
        Spacer(Modifier.height(TgFeedTokens.GapSm))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
                .background(TgFeedTokens.SurfaceHigh)
                .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusMd)),
        ) {
            entries.forEachIndexed { index, (brief, count) ->
                val avatarImage = rememberChannelAvatar(brief.id, avatarLoader)
                val rankColor = if (index < 3) TgFeedTokens.TextHigh else TgFeedTokens.TextLow
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(brief) }
                        .padding(horizontal = TgFeedTokens.GapMd, vertical = TgFeedTokens.GapSm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "#${index + 1}",
                        color = rankColor,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        modifier = Modifier.width(28.dp),
                    )
                    DiscoverAvatarImage(
                        avatarImage = avatarImage,
                        channelId = brief.id,
                        title = brief.title,
                        size = 32.dp,
                    )
                    Spacer(Modifier.width(TgFeedTokens.GapSm))
                    Text(
                        brief.title,
                        color = TgFeedTokens.TextHigh,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(TgFeedTokens.GapSm))
                    Text(
                        "本周 ${count} 条",
                        color = TgFeedTokens.TextLow,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
                // 行间发丝分隔（末行不画）
                if (index < entries.lastIndex) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = TgFeedTokens.GapMd)
                            .height(0.5.dp)
                            .background(TgFeedTokens.OverlayHairline),
                    )
                }
            }
        }
    }
}

/**
 * 无图回落块（候选卡封面/小图共用）：频道稳定色的对角渐变色块。
 * v3 修复口径：真缩略图未回/无封面/下载失败一律回落到这里——不留灰底、
 * 不铺 minithumbnail 糊图。
 */
@Composable
private fun DiscoverThumbFallback(seed: Long, modifier: Modifier = Modifier) {
    val base = discoverAvatarColor(seed)
    Box(
        modifier.background(
            Brush.linearGradient(
                listOf(base.copy(alpha = 0.85f), TgFeedTokens.InkBlack),
            ),
        ),
    )
}

/**
 * 候选空态（v3 收编为杂志流列表内项）：雷达纹样 + 体面文案 + 细 spinner——
 * 图谱还没长出来 / Mock 模式 / 本轮排除后真空都不是死态，雷达与图谱在后台攒线索。
 * 顶部无其他区段时调用方传 fillParentMaxSize 撑满整页居中；有内容时传紧凑块
 * 修饰符跟在区段之后。
 */
@Composable
private fun DiscoverEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 空态指示：简洁雷达图标（用户反馈 AI 插画多余，换 Material 描线雷达——
        // 与全 App 图标语言一致，不再铺成品插画）
        Icon(
            Icons.Outlined.Radar,
            contentDescription = null,
            tint = TgFeedTokens.TextLow,
            modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.height(TgFeedTokens.GapLg))
        Text(
            "金矿还在积累，攒够就来",
            color = TgFeedTokens.TextHigh,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(TgFeedTokens.GapMd))
        // 细 spinner（页面级转圈是 40dp/3dp，空态辅助指示降一档：20dp/2dp/TextLow）
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = TgFeedTokens.TextLow,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.height(TgFeedTokens.GapMd))
        Text(
            // R197 入口已迁本页：引导从「设置 → 我的搜索群」改指页内常驻入口行
            "深度索引正在后台挖掘全部频道的历史内容（几百万条就是发现页的候选矿）；\n" +
                "也可以点上方「我的搜索群」勾选你的资源搜索群——\n群友搜过的词、被安利的频道会直接变成候选。",
            color = TgFeedTokens.TextLow,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 来源角标（R207 视觉语法：统一 [DiscoverPillBadge] 中性一形——来源差异由文案表义，
 * 色相分类废除（旧病：电报蓝/抖音红/柔紫/星标黄/青绿/红六色轮播当分类器）；
 * 压图位 onImage=true 走深浮层底+发丝描边，任意画面可读）。
 * 标签映射：深索引→「深索引收录」/ 深索引+群搜→「双宝藏命中」/ 群搜→「群友都在找」/
 * 图谱→「来自图谱」/ 相似→「来自相似」/ 雷达→「雷达巡逻」/ 广场→「广场热推」/
 * 多源命中→如实展示组合标签。
 */
@Composable
private fun DiscoverSourceBadge(tag: String, onImage: Boolean = false) {
    val label = when (tag) {
        // R183 宝藏一：深索引收录（金矿档案候选）
        "深索引" -> "深索引收录"
        // R183 双置信：索引验货 + 搜索群在传（两路宝藏都背书）
        "深索引+群搜" -> "双宝藏命中"
        // R183 宝藏二：搜索群收获（群友需求侧信号）
        "群搜" -> "群友都在找"
        "图谱" -> "来自图谱"
        "相似" -> "来自相似"
        // W5 口味雷达：SearchPublicPosts 每日免费配额自动巡逻命中的新频道
        "雷达" -> "雷达巡逻"
        // P1-d 广场臂：全站推荐（fetchPlaza 快照 channelRecommends 供给）
        "广场" -> "广场热推"
        // 多源命中（"深索引+群搜+雷达" / "图谱+相似" / "雷达+深索引"…）：如实展示组合标签
        else -> tag
    }
    DiscoverPillBadge(label, onImage = onImage)
}

/**
 * P4-A 热词 chip（"群友在搜"区，横滑 LazyRow 项）：#名次 + 词 + 24h 次数。
 * 可点——点击带词跳搜索页并自动开搜（产品确认通过；跳转接线在 DiscoverScreen 的 onSearchHotWord 回调）。
 * R207 视觉语法（榜单化视觉，中性收敛）：
 *  - 胶囊壳：SurfaceHigh 深底 + 0.5dp 发丝描边（token 派生不造色）；
 *  - 名次走中性亮阶：top3 = TextHigh、其余 TextLow（领奖台红/黄/蓝色相废除——
 *    名次信息由 #数字本身表达）；
 *  - 24h 内被搜 ≥[DISCOVER_HOT_WORD_FIRE_MIN] 次的热词：叠 🔥 前缀——emoji 自带
 *    热度符号，chip 壳不染红不换边宽（旧病：红淡底+1dp 红边+红字三处同染）；
 *  - R65：count ≤ 0（全网热搜榜词，无 24h 群搜计数）不显"×N"次数段——榜序即热度序。
 */
@Composable
private fun HotWordChip(rank: Int, word: String, count: Int, onClick: () -> Unit) {
    val hot = count >= DISCOVER_HOT_WORD_FIRE_MIN
    val rankColor = if (rank <= 3) TgFeedTokens.TextHigh else TgFeedTokens.TextLow
    val chipShape = RoundedCornerShape(TgFeedTokens.RadiusPill)
    Box(
        Modifier
            .clip(chipShape)
            .background(TgFeedTokens.SurfaceHigh)
            .border(0.5.dp, TgFeedTokens.OverlayHairline, chipShape)
            .clickable(onClick = onClick)
            .padding(horizontal = TgFeedTokens.GapMd, vertical = TgFeedTokens.GapXs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "#$rank",
                color = rankColor,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
            Spacer(Modifier.width(TgFeedTokens.GapXs))
            Text(
                (if (hot) "🔥 " else "") + if (count > 0) "$word ×$count" else word,
                color = TgFeedTokens.TextMedium,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

// R207：DiscoverFreshBadge（抖音红淡底胶囊）已删除——收编进 DiscoverPillBadge 统一徽章形

/**
 * R215 ① 时段切换 chip（「此刻|本周|全月|全年」·候选区顶部横条）：
 * 壳与热词 chip 同档（Pill+SurfaceHigh+0.5dp 发丝+labelMedium+GapMd/GapXs——
 * R207「可点 tap 目标独立一档」同一形）；选中态=**AccentRed 字+加粗**
 * （任务书拍板：时段 chips 选中与可点主行动同一强调语言——「选中档」为 §10
 * 强调色语义位的 R215 增补档，见 docs/DISCOVER-DESIGN.md §10），未选中=TextMedium。
 */
@Composable
private fun DiscoverTimeBandChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val chipShape = RoundedCornerShape(TgFeedTokens.RadiusPill)
    Box(
        Modifier
            .clip(chipShape)
            .background(TgFeedTokens.SurfaceHigh)
            .border(0.5.dp, TgFeedTokens.OverlayHairline, chipShape)
            .clickable(onClick = onClick)
            .padding(horizontal = TgFeedTokens.GapMd, vertical = TgFeedTokens.GapXs),
    ) {
        Text(
            label,
            color = if (selected) TgFeedTokens.AccentRed else TgFeedTokens.TextMedium,
            style = if (selected) {
                MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
            } else {
                MaterialTheme.typography.labelMedium
            },
            maxLines = 1,
        )
    }
}

// R207：DiscoverSourceWordStrip / DiscoverRecommendStrip / DiscoverQuoteStrip
// （深底横条+蓝/红/绿三色引导竖条三胞胎）已删除——收编进 DiscoverInfoStrip 统一信息条壳

// R207：DiscoverCorroborationChip 已删除——收编进 DiscoverPillBadge（warn 参数保留
// R204 DS-3 ⚠ 警示口径：WarningRed 字 + 中性底，淡彩底 alpha 废除）

/** 证据文案行：中性引导点 + 一行小字（候选卡"为什么推它"区块的统一行格式；R207：点色相废除走引导族） */
@Composable
private fun DiscoverEvidenceBullet(line: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        DiscoverLeadDot()
        Spacer(Modifier.width(TgFeedTokens.GapSm))
        Text(
            line,
            color = TgFeedTokens.TextMedium,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * R133 反向探索卡（#17：发现页顶部"信息茧房打破器"，每周一张）：
 * 指南针图标 + "反向探索 · 每周一张"小节头（R207：中性 TextMedium——探索语义由
 * 图标形表达，不再吃星标黄）→ 发丝环头像 + 频道名 + 基因鉴定行 →
 * 一句反差文案（广场有推荐数时「你从来不看这类，但 N 个广场用户在推荐」，
 * 否则无数字版）。整卡即"试吃"热区（与候选卡同口径留痕后打开频道 Feed）。
 */
@Composable
private fun DiscoverReverseCard(
    card: ReverseExploreCard,
    avatarLoader: suspend (Long) -> ByteArray?,
    onOpen: () -> Unit,
) {
    val avatarImage = rememberChannelAvatar(card.brief.id, avatarLoader)
    val cardShape = RoundedCornerShape(DiscoverCardRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(TgFeedTokens.SurfaceHigh)
            .border(0.5.dp, TgFeedTokens.OverlayHairline, cardShape)
            .clickable(onClick = onOpen)
            .padding(TgFeedTokens.GapLg),
        verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapXs),
        ) {
            Icon(
                Icons.Outlined.Explore,
                contentDescription = null,
                tint = TgFeedTokens.TextMedium,
                modifier = Modifier.size(16.dp),
            )
            Text(
                "反向探索 · 每周一张",
                color = TgFeedTokens.TextMedium,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
        ) {
            DiscoverAvatarImage(
                avatarImage = avatarImage,
                channelId = card.brief.id,
                title = card.brief.title,
                size = 40.dp,
                // 发丝环：与候选卡头像同一视觉语言（R207：金环黄系废除走中性）
                ringColor = TgFeedTokens.OverlayHairline,
                ringWidth = 1.dp,
            )
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    card.brief.title,
                    color = TgFeedTokens.TextHigh,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    card.genomeLine,
                    color = TgFeedTokens.TextLow,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            if (card.recommendCount > 0) {
                "你从来不看这类，但 ${card.recommendCount} 个广场用户在推荐"
            } else {
                "你从来不看这类 · 本周的开眼推荐"
            },
            color = TgFeedTokens.TextMedium,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// R207：DiscoverGenomeStrip（青绿引条手抄壳）已删除——收编进 DiscoverInfoStrip
// （调用点 DiscoverInfoStrip("🧬 $genomeLine")，🧬 前缀保留）

/**
 * 发现页升级·"刚冒出来的"横滑小卡（v3 定稿·A2 精致大片风：裸头像横列，去卡片壳）：
 * 红环大头像 + 频道名 + 相对时间（刚刚/N 分钟前/N 小时前/N 天前）。
 * 新鲜度视觉（R207 中性阶差）：环恒为发丝弱环；1 小时内还在发酵的频道时间戳
 * 亮阶（TextMedium），超过 1 小时落 TextLow——热度由时间文案本身表达。
 * 整卡即"试吃"热区（点击 = 试吃留痕 + 打开频道 Feed，由调用方接线）。
 */
@Composable
private fun DiscoverFreshMiniCard(
    card: DiscoveryFreshCard,
    avatarLoader: suspend (Long) -> ByteArray?,
    onClick: () -> Unit,
) {
    val avatarImage = rememberChannelAvatar(card.brief.id, avatarLoader)
    // 1 小时窗口 = "刚刚冒出来"的发酵期（与 freshAgeLabel 的分钟档同窗口）
    val stillHot = System.currentTimeMillis() - card.lastTs < 3_600_000L
    Column(
        Modifier
            .width(DiscoverFreshCardWidth)
            .clip(RoundedCornerShape(TgFeedTokens.RadiusMd)) // 只裁 ripple 形状，不再画卡片壳
            .clickable(onClick = onClick)
            .padding(vertical = TgFeedTokens.GapXs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DiscoverAvatarImage(
            avatarImage = avatarImage,
            channelId = card.brief.id,
            title = card.brief.title,
            size = 52.dp,
            // R207：红环废除——新鲜度由时间文案与亮度阶差表达（环恒发丝中性）
            ringColor = TgFeedTokens.OverlayHairline,
        )
        Spacer(Modifier.height(TgFeedTokens.GapXs))
        Text(
            card.brief.title,
            color = TgFeedTokens.TextHigh,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            freshAgeLabel(card.lastTs),
            // R207：发酵期亮阶 TextMedium / 落温 TextLow（旧红字废除）
            color = if (stillHot) TgFeedTokens.TextMedium else TgFeedTokens.TextLow,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

/**
 * R372 雷达频道卡（「群友正在搜」雷达段横滑项）：「群友正在搜 XXX，返回了 YYY 频道」
 * 的频道侧证据卡——头像+频道名+成员数+「N 次命中 · 相对时间」+来源词行（搜「X」）。
 * 壳与 A2 语境一致（SurfaceHigh+发丝+RadiusMd，同"刚冒出来的"小卡的卡片壳档）；
 * 证据行时间沿用 [freshAgeLabel] 相对档文案件（语义=答案最近命中时刻，非首见年龄
 * ——同一相对时间件复用，不加第三份，R204 口径）；成员数未知（0）不渲染该段。
 * 整卡即"试吃"热区（点击=试吃留痕后打开频道 Feed，与候选卡/刚冒出来的同口径）。
 */
@Composable
private fun DiscoverRadarCard(
    card: RadarChannelCard,
    avatarLoader: suspend (Long) -> ByteArray?,
    onOpen: () -> Unit,
) {
    val avatarImage = rememberChannelAvatar(card.chatId, avatarLoader)
    Column(
        Modifier
            .width(DiscoverRadarCardWidth)
            .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
            .background(TgFeedTokens.SurfaceHigh)
            .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusMd))
            .clickable(onClick = onOpen)
            .padding(TgFeedTokens.GapMd),
        verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapXs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DiscoverAvatarImage(
                avatarImage = avatarImage,
                channelId = card.chatId,
                title = card.title,
                size = 36.dp,
            )
            Spacer(Modifier.width(TgFeedTokens.GapSm))
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    card.title,
                    color = TgFeedTokens.TextHigh,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "搜「${card.word}」",
                    color = TgFeedTokens.TextLow,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (card.members > 0) {
            Text(
                formatMemberCount(card.members),
                color = TgFeedTokens.TextLow,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
        Text(
            "${card.hits} 次命中 · ${freshAgeLabel(card.lastTs)}",
            color = TgFeedTokens.TextMedium,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 频道头像（候选卡/横滑小卡共用渲染件）：真头像为圆形裁切图，缺头像回落字母圆
 * （频道名首字大写，底色按 id 稳定取色）；ringColor 非 null 时外套细环（宽度 ringWidth，
 * 封面压图用发丝环勾轮廓；R207：红环新鲜语义已退中性，见 DiscoverFreshMiniCard）。
 */
@Composable
private fun DiscoverAvatarImage(
    avatarImage: ImageBitmap?,
    channelId: Long,
    title: String,
    size: Dp,
    ringColor: Color? = null,
    ringWidth: Dp = 1.dp,
) {
    val ring = if (ringColor != null) {
        Modifier.border(ringWidth, ringColor, CircleShape)
    } else {
        Modifier
    }
    if (avatarImage != null) {
        Image(
            bitmap = avatarImage,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .then(ring)
                .clip(CircleShape),
        )
    } else {
        // 回落：圆形字母头像（调色板与 FeedTopBarOverlay 私有版同口径，那边不可跨文件复用故自带一份）
        Box(
            Modifier
                .size(size)
                .then(ring)
                .clip(CircleShape)
                .background(discoverAvatarColor(channelId)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title.firstOrNull()?.uppercase() ?: "?",
                color = TgFeedTokens.TextHigh,
                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

/**
 * 频道头像异步加载（候选卡与"刚冒出来的"小卡共用）：入组合时查/拉一次，
 * loader 内部带进程内缓存，滚动出行再回来只是缓存命中，不会重复触发 TDLib 下载。
 */
@Composable
private fun rememberChannelAvatar(
    channelId: Long,
    avatarLoader: suspend (Long) -> ByteArray?,
): ImageBitmap? {
    var bytes by remember(channelId) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(channelId) {
        if (bytes == null) bytes = avatarLoader(channelId)
    }
    return remember(bytes) {
        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
}

/** "刚冒出来的"相对时间：最近再被搜出的时间戳 → 刚刚 / N 分钟前 / N 小时前 / N 天前 */
private fun freshAgeLabel(lastTs: Long): String {
    val minutes = (System.currentTimeMillis() - lastTs).coerceAtLeast(0L) / 60_000L
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes / 60 < 24 -> "${minutes / 60} 小时前"
        else -> "${minutes / 60 / 24} 天前"
    }
}

/** 成员数格式化：≥1万 压成"23.4万成员"（一位小数，整万不带小数点）；不足 1 万原样"N 成员"
 *  （R197 收敛：万位折算核心在 components/Formatters.formatMemberCount 一处定义，此处只拼后缀） */
private fun formatMemberCount(n: Int): String = "${dev.tgfeed.ui.components.formatMemberCount(n)} 成员"

// R207：本文件私有 DiscoverSectionTitle（霓虹红引条版）已删除——收编进
// components/DiscoverStyles.kt 的共享件（中性引条，规范见 docs/DISCOVER-DESIGN.md §10）

/** 热词 chip 火焰高亮门槛：24h 内被搜次数 ≥2 即热词（1 次是路过的长尾，不值得高亮） */
private const val DISCOVER_HOT_WORD_FIRE_MIN = 2

/** R65 热词区横滑行出词上限（榜词 ≤240 全铺太长；30 覆盖榜头部 + 群搜补齐，横滑不到底也不可惜） */
private const val DISCOVER_HOT_WORD_ROW_LIMIT = 30

/**
 * R65 热词融合（发现页热词区词源）：全网热搜词榜（每日收割，榜序即热度序）优先，
 * 群搜 24h 聚合词（word × 24h 次数）去重补齐；榜词已有 24h 计数的沿用计数
 * （可叠 🔥 高亮），无计数的带 count=0（chip 不显次数段）。榜缺席/过期
 * （HotWordBoard.freshWords 返回空）时原样回落群搜口径。
 */
private fun mergeBoardHotWords(local: List<Pair<String, Int>>, board: List<String>): List<Pair<String, Int>> {
    if (board.isEmpty()) return local.take(DISCOVER_HOT_WORD_ROW_LIMIT)
    val localCount = HashMap<String, Int>(local.size)
    for ((w, c) in local) localCount.putIfAbsent(w, c)
    val out = ArrayList<Pair<String, Int>>(board.size + local.size)
    val seen = HashSet<String>()
    for (w in board) {
        if (!seen.add(w)) continue
        out += w to (localCount[w] ?: 0)
    }
    for ((w, c) in local) {
        if (!seen.add(w)) continue
        out += w to c
    }
    return out.take(DISCOVER_HOT_WORD_ROW_LIMIT)
}

/** 刷新骨架卡张数（视口高约 2-3 张候选卡，3 张铺满首屏即"整页在换"的感知） */
private const val DISCOVER_SKELETON_COUNT = 3

/** 刷新骨架最短展示时长（毫秒）：VM 落闸后再停片刻，防刷新太快"骨架一闪而过"仍无感知（落在 400-800ms 感知窗内） */
private const val DISCOVER_SKELETON_MIN_MS = 600L

/** 沉底"本周口碑"区块出榜条数上限（计数 >0 的候选才进榜；横铺不超一页纵向增量） */
private const val DISCOVER_WEEKLY_SECTION_MAX = 10

/**
 * 首屏封面优先预取的候选卡张数（v4）：列表前 N 张卡的预览下载走 VM 优先通道
 * （跳过普通档二级闸，直接占全局闸预留车道），首屏先出图；其余卡照旧排队
 */
private const val DISCOVER_PRIORITY_PREVIEW_CARDS = 3

/** 标题区金矿横幅透明度（A2 定稿口径 15~25% 取中：压得住标题字、又看得出矿脉纹理） */
private const val DISCOVER_BANNER_ALPHA = 0.22f

/** "刚冒出来的"横滑小卡宽（A2 裸头像密度：头像 52dp + 两侧内边距，横滑一屏约见 4~5 张） */
private val DiscoverFreshCardWidth = 72.dp

/** R372 雷达频道卡宽（证据卡密度：头像 36dp + 标题/来源词两行 + 证据行，横滑一屏约见 2~3 张） */
private val DiscoverRadarCardWidth = 150.dp

/** 字母头像柔和底色板（与 FeedTopBarOverlay 私有版同调色板；按频道 id 哈希稳定取色） */
private val DiscoverAvatarPalette = listOf(
    Color(0xFF5C6BC0), // 柔靛蓝
    Color(0xFF26A69A), // 柔青绿
    Color(0xFFEF5350), // 柔红
    Color(0xFFAB47BC), // 柔紫
    Color(0xFFFFA726), // 柔橙
    Color(0xFF42A5F5), // 柔蓝
    Color(0xFF66BB6A), // 柔绿
    Color(0xFFEC407A), // 柔粉
)

// R207：DiscoverGroupSearchPurple（群搜角标柔紫，第 6 色相）已删除——来源角标
// 收敛中性一形后私有色无消费者；字母头像调色板（身份色）按 §10.1 不受强调色纪律约束

/** 按频道 id 稳定取一个头像底色（floorMod 避免 Long.MIN_VALUE 取负） */
private fun discoverAvatarColor(channelId: Long): Color =
    DiscoverAvatarPalette[Math.floorMod(channelId, DiscoverAvatarPalette.size)]

// ---------------- R62 分类分区（借鉴 soso 发现模块"分类浏览器"：候选按内容类目聚类小节） ----------------

/** 兜底类目名：全部关键词未命中的候选统一进"其它"（宁粗勿错） */
private const val DISCOVER_CATEGORY_OTHER = "其它"

/**
 * R62 类目关键词规则表（顺序即优先级：自上而下首个命中的类目即归属）。
 * 粗分口径——只看频道标题与用户名的关键词命中，不做语义理解；
 * 拿不准的宁落"其它"，不硬猜类目。
 */
private val DISCOVER_CATEGORY_RULES: List<Pair<String, List<String>>> = listOf(
    // 写真：图册/摄影向内容
    "写真" to listOf("写真", "图集", "摄影", "相册", "美图", "模特", "cosplay"),
    // 网盘：存储/分享向内容
    "网盘" to listOf("网盘", "云盘", "盘搜", "磁力", "阿里云", "夸克", "torrent", "drive"),
    // 剧集：影视/番剧向内容
    "剧集" to listOf("剧集", "追剧", "影视", "电影", "美剧", "韩剧", "日剧", "动漫", "番剧"),
    // 软件：工具/应用向内容
    "软件" to listOf("软件", "工具", "插件", "安卓", "破解", "windows", "macos"),
    // 学习：教程/课程向内容
    "学习" to listOf("学习", "教程", "课程", "编程", "英语", "考研", "读书", "资料", "考试"),
)

/** 类目小节展示顺序：与规则表同序，"其它"沉底 */
private val DISCOVER_CATEGORY_ORDER: List<String> =
    DISCOVER_CATEGORY_RULES.map { it.first } + DISCOVER_CATEGORY_OTHER

/**
 * R62 候选类目粗分（关键词规则制）：标题 + 用户名小写化拼接后按 [DISCOVER_CATEGORY_RULES]
 * 顺序匹配，首个命中的类目即归属；全部未命中进 [DISCOVER_CATEGORY_OTHER]。
 * 候选只有标题/用户名可用（ChannelBrief 无简介字段），故规则只看这两处。
 */
private fun discoverCategoryOf(title: String, username: String?): String {
    val text = (title + " " + username.orEmpty()).lowercase()
    for ((category, keywords) in DISCOVER_CATEGORY_RULES) {
        if (keywords.any { it in text }) return category
    }
    return DISCOVER_CATEGORY_OTHER
}

/** R62 分类分区横滑卡宽：一屏约见 1.2 张，露出下一张边缘暗示可横滑 */
private val DiscoverCategoryCardWidth = 300.dp
