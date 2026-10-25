package dev.tgfeed.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tgfeed.core.feed.ChannelHealthEntry
import dev.tgfeed.core.feed.ChannelHealthTier
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.feed.FeedViewModelFactory
import dev.tgfeed.core.td.ChannelBrief
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.ui.components.kit.AppDialog
import dev.tgfeed.ui.components.kit.AppEmptyState
import dev.tgfeed.ui.components.kit.AppLoading
import dev.tgfeed.ui.components.kit.AppPill
import dev.tgfeed.ui.components.kit.AppTopBar
import dev.tgfeed.ui.components.kit.adaptiveContentWidth
import dev.tgfeed.ui.components.kit.avoidBottomBar
import dev.tgfeed.ui.theme.TgFeedTokens
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * 频道体检报告页（R132 引入，R139 按设计文档 P1-④ 从"列表页"改造为"报告页"）。
 * 从"我的"页进入（路由由容器层接，状态驱动、不在返回栈里）。
 *
 * 判据全本地零网络：深度索引统计（最后发帖/近 30 天频率）+ watch_events 互动衰减
 * + 广告频道黑名单；打分与分层口径见 core/feed/ChannelHealth.kt（逻辑不动，本页只改呈现）。
 *
 * 三段式报告结构（结论先行）：
 *  1. 结论头卡：86dp 健康分环（总分 0-100，等级色绿/黄/红，400ms countUp）
 *    + 一句话结论（"N 个频道 · M 个建议取关"）+ 体检时间 + "重新体检"次按钮；
 *    免责声明收进"体检说明"折叠（点 ⓘ 展开），首屏不占位；
 *  2. 建议行动卡（变质+僵尸且订阅中，默认展开）：每行 = 头像 + 频道名 + 结论色原因
 *    + 右侧"取关"胶囊——即点即关（走现有批量订阅移除链，仅动订阅表），
 *    行划出（fade+位移）；顶部"全部取关（N）"次按钮走 AppDialog 二次确认；
 *  3. 明细折叠卡：筛选胶囊（全部/待取关/低频/仅订阅，纯 UI 态不重算）移到明细区头部；
 *    低频/健康频道默认收起，点行展开看证据（原因 + 数据摘要行）。
 *
 * 反馈与撤销：取关反馈聚合到底部非模态浮条（"已取关 N 个频道 · 撤销"，5 秒自动消失），
 * 点撤销批量重订；单条取关即点即关不打断，批量取关保留确认框。
 *
 * 保留的旧设计（设计文档"不改清单"）：重算在途细条（R132——旧报告继续可见，
 * 明示当前数据是上一轮的）；撤销的非模态性（错在位置不在模式，故只挪到底部）。
 *
 * R318 聚合降档：重算细条与首跑加载态升级为"进度百分比 + 取消胶囊"（date 索引覆盖
 * 分片走查，5.8M 行真库分钟级→秒级；取消=保旧报告直通，见 IndexStatsWalk.kt）。
 *
 * 订阅态实时性：行的订阅态从 vm.subscriptions（StateFlow）实时派生而非报告快照——
 * 取关/撤销后行内状态即时翻转，无需重算报告。
 */
@Composable
fun ChannelHealthScreen(
    gateway: TdGateway,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as android.app.Application
    // 系统返回键 = 返回我的页（状态驱动路由，不拦会退到桌面）
    BackHandler(onBack = onBack)
    // 与 FeedScreen 共享 activity 作用域的同一个 ViewModel（索引/事件/订阅同源）
    val vm: FeedViewModel = viewModel(
        factory = FeedViewModelFactory(gateway, app),
        key = "gw" + System.identityHashCode(gateway),
    )
    val report by vm.channelHealth.collectAsState()
    val refreshing by vm.channelHealthRefreshing.collectAsState()
    // R318：走查进度（0..100；null=闲）——首跑加载态与重算在途条共用（分片逐片回报）
    val progress by vm.channelHealthProgress.collectAsState()
    val subscriptions by vm.subscriptions.collectAsState()
    // 进入页面触发一次体检重算（IO 协程分片走查，结果写回 channelHealth；可取消保旧报告）
    LaunchedEffect(Unit) { vm.refreshChannelHealth() }

    // ---- 纯 UI 态 ----
    var filter by remember { mutableStateOf(HealthFilter.ALL) }
    // 本页刚取关的频道（id → 标题）：底部撤销浮条数据源（聚合反馈，不一行一个提示）
    var unfollowed by remember { mutableStateOf(listOf<Pair<Long, String>>()) }
    // "全部取关"批量确认框（破坏性操作二次确认；单条即点即关不打断）
    var showConfirm by remember { mutableStateOf(false) }

    // 订阅态实时派生（取关/撤销后行内即时变化）
    val subIds = remember(subscriptions) { subscriptions.mapTo(HashSet()) { it.id } }

    val rep = report
    // ---- 三段式数据派生（报告快照 + 订阅态实时拼装，不重算） ----
    val riskEntries = remember(rep) {
        rep?.entries.orEmpty().filter {
            it.tier == ChannelHealthTier.DECAYED || it.tier == ChannelHealthTier.ZOMBIE
        }
    }
    // 可执行取关的（订阅中的变质/僵尸频道）：结论计数、行动卡与"全部取关"的对象
    val actionEntries = remember(riskEntries, subIds) {
        riskEntries.filter { it.chatId in subIds }
    }
    val lowEntries = remember(rep) { rep?.entries.orEmpty().filter { it.tier == ChannelHealthTier.LOW_FREQ } }
    val healthyEntries = remember(rep) { rep?.entries.orEmpty().filter { it.tier == ChannelHealthTier.HEALTHY } }
    // "仅订阅"筛选行（与旧版语义一致：订阅中的全部频道，按报告序差者优先）
    val subEntries = remember(rep, subIds) { rep?.entries.orEmpty().filter { it.chatId in subIds } }
    // 总分 = 全部频道健康分均值（0-100 取整）
    val avgScore = remember(rep) {
        rep?.entries?.takeIf { it.isNotEmpty() }?.let { it.map { e -> e.score }.average().roundToInt() } ?: 0
    }

    // ---- 取关动作（单条与批量同一条订阅移除链，仅动订阅表） ----
    val onUnfollow: (ChannelHealthEntry) -> Unit = { entry ->
        vm.removeSubscriptionsBatch(setOf(entry.chatId))
        // 聚合去重：同一频道重复点击只记一次（浮条计数才准）
        if (unfollowed.none { it.first == entry.chatId }) {
            unfollowed = unfollowed + (entry.chatId to entry.title)
        }
    }
    val onUndo: () -> Unit = {
        // 走批量重订链（单次读改写）：恢复到取关前的订阅表
        vm.addSubscriptionsBatch(
            unfollowed.map { (id, title) -> ChannelBrief(id, title, null) },
        )
        unfollowed = emptyList()
    }
    // 撤销浮条 5 秒自动消失（取关本身保留；重订入口不止这一处）
    if (unfollowed.isNotEmpty()) {
        LaunchedEffect(unfollowed) {
            delay(UnfollowUndoMs)
            unfollowed = emptyList()
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 二级页统一顶栏（kit AppTopBar：返回圆钮 + 17sp SemiBold 标题）
        AppTopBar(title = "频道体检", onBack = onBack)
        // 大屏：内容列按 adaptiveContentWidth 三档限宽居中（与观看报告页同一规格）
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    // 自适应内容列宽必须在前：fillMaxSize 会先把约束钉成全宽，其后的限宽是死代码（P1-6 教训）
                    .adaptiveContentWidth()
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapLg),
            ) {
                when {
                    // 加载态：首次进入体检尚未算完（R318 走查有进度+可取消——真库秒级非分钟级）
                    rep == null -> Column(
                        Modifier.fillMaxWidth().padding(top = 96.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
                    ) {
                        AppLoading(
                            text = if (progress != null) "正在体检… $progress%" else "正在体检…",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // 取消胶囊：取消保旧值直通（此处无旧报告=回到空态文案，不算半途数据）
                        if (progress != null) HealthCancelButton(onCancel = { vm.cancelChannelHealth() })
                    }
                    // 空态：订阅/索引/观看全都没有可体检的频道
                    rep.isEmpty -> AppEmptyState(
                        icon = Icons.Outlined.HealthAndSafety,
                        title = "还没有可体检的频道",
                        modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
                        subtitle = "订阅几个频道、刷一阵子之后再来看看",
                    )
                    else -> {
                        // ---------- 重算在途细条（R132 保留设计：旧报告继续可见，明示数据新旧） ----------
                        //（R318 走查分片回报进度；旧报告先顶着，不整页转圈；在途可取消）
                        if (refreshing) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                                modifier = Modifier.padding(start = TgFeedTokens.GapXs),
                            ) {
                                Text(
                                    if (progress != null) "正在重新体检… $progress%（先看上一轮结果）"
                                    else "正在重新体检…（先看上一轮结果）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TgFeedTokens.TextLow,
                                    modifier = Modifier.weight(1f),
                                )
                                HealthCancelButton(onCancel = { vm.cancelChannelHealth() })
                            }
                        }

                        // ---------- 第一段：结论头卡（总分环 + 一句话结论） ----------
                        HealthReportHeadCard(
                            score = avgScore,
                            total = rep.entries.size,
                            actionCount = actionEntries.size,
                            generatedAtMs = rep.generatedAtMs,
                            refreshing = refreshing,
                            onRefresh = { vm.refreshChannelHealth() },
                        )

                        // ---------- 第二段：建议行动卡（变质+僵尸 · 即点即关） ----------
                        HealthActionCard(
                            riskEntries = riskEntries,
                            actionEntries = actionEntries,
                            subIds = subIds,
                            onUnfollow = onUnfollow,
                            onUnfollowAll = { showConfirm = true },
                        )

                        // ---------- 第三段：明细折叠卡（低频/健康默认收起） ----------
                        HealthDetailCard(
                            filter = filter,
                            onFilter = { filter = it },
                            riskEntries = riskEntries,
                            lowEntries = lowEntries,
                            healthyEntries = healthyEntries,
                            subEntries = subEntries,
                            total = rep.entries.size,
                            subCount = subIds.size,
                            subIds = subIds,
                        )

                        // 底部留白：给底部撤销浮条让位（浮条 5 秒自动消失，非持久遮挡）
                        Spacer(Modifier.height(72.dp))
                    }
                }
            }

            // ---------------- 底部撤销浮条（非模态 Snackbar：聚合反馈 + 撤销 + 5s 自动消失） ----------------
            // 撤销条位置从页顶挪到底部：取关动作发生在行内，反馈跟手；整条都是撤销热区。
            // 注意用全限定名：本 Box 嵌在外层 Column 里，直接写 AnimatedVisibility 会被
            // ColumnScope 的同名扩展抢占解析（"cannot be called in this context"）
            androidx.compose.animation.AnimatedVisibility(
                visible = unfollowed.isNotEmpty(),
                enter = slideInVertically(tween(TgFeedTokens.AnimMed)) { it } +
                    fadeIn(tween(TgFeedTokens.AnimMed)),
                exit = slideOutVertically(tween(TgFeedTokens.AnimMed)) { it } +
                    fadeOut(tween(TgFeedTokens.AnimMed)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .adaptiveContentWidth()
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .avoidBottomBar(), // R226 底部锚定浮条统一避让钥匙（本页为二级页 inset 恒 0，防未来 dock 回场回归）
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = TgFeedTokens.GapLg)
                        .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                        .background(TgFeedTokens.OverlayBgStrong)
                        .border(0.5.dp, TgFeedTokens.OverlayHairline, RoundedCornerShape(TgFeedTokens.RadiusPill))
                        .clickable(onClick = onUndo)
                        .padding(horizontal = TgFeedTokens.GapLg, vertical = TgFeedTokens.GapMd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Undo,
                        contentDescription = null,
                        tint = TgFeedTokens.TgBlue,
                        modifier = Modifier.size(TgFeedTokens.IconMd),
                    )
                    Text(
                        "已取关 ${unfollowed.size} 个频道",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TgFeedTokens.TextHigh,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "撤销",
                        style = MaterialTheme.typography.labelLarge,
                        color = TgFeedTokens.TgBlue,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }

    // ---------------- "全部取关"确认框：破坏性批量操作二次确认 ----------------
    if (showConfirm) {
        AppDialog(
            onDismissRequest = { showConfirm = false },
            title = "确认取关 ${actionEntries.size} 个频道？",
            confirmText = "取关",
            onConfirm = {
                val doomed = actionEntries.map { it.chatId to it.title }
                if (doomed.isNotEmpty()) {
                    // 批量订阅移除链（单次读改写；与单条取关同一条链，仅动订阅表）
                    vm.removeSubscriptionsBatch(actionEntries.mapTo(HashSet()) { it.chatId })
                    unfollowed = doomed.filterNot { d ->
                        unfollowed.any { it.first == d.first }
                    } + unfollowed
                }
                showConfirm = false
            },
            dismissText = "再想想",
            content = {
                Text(
                    "取关后这些频道不再追更、不再进订阅聚合；已加入的频道不会被移出 Telegram。" +
                        "刚取关的 5 秒内可在底部一键撤销重订。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TgFeedTokens.TextMedium,
                )
            },
        )
    }
}

// ==================== 以下为本页私有组件 ====================

/** 筛选档（纯 UI 态：报告已全量算好，切换只过滤不重算） */
private enum class HealthFilter { ALL, RISK, LOW, SUBS }

/** 本页卡片圆角：与观看报告页报告卡同一语言（16dp） */
private val HealthCardRadius = 16.dp

/** 结论头卡健康分环直径（设计文档 P1-④ 规格：86dp） */
private val HealthRingSize = 86.dp

/** 健康分环描边宽度 */
private val HealthRingStroke = 8.dp

/** 分数环与数字 countUp 时长：R178 收编 MotionTokens.CountUpMs（§5.2 表「数字 countUp 400ms」行，值同源） */

/** 行进入 stagger 步长（首屏行动清单逐行错落进场） */
private const val RowStaggerMs = 40

/** 整卡退出比行退出晚半拍：让最后一行的划出动效先走完，卡片再收 */
private const val ActionCardExitDelayMs = 260

/** 撤销浮条驻留时长（5 秒自动消失） */
private const val UnfollowUndoMs = 5000L

/**
 * 分层语义色（任务书映射，复用 TgFeedTokens）：
 * 变质/僵尸 = 警示（行动档统一警示红）· 低频 = 中性灰 · 健康 = 正色绿。
 * 徽章语言统一为"色点 + 文字"，不再用 emoji 混搭。
 */
private fun tierColor(tier: ChannelHealthTier): Color = when (tier) {
    ChannelHealthTier.DECAYED -> TgFeedTokens.WarningRed
    ChannelHealthTier.ZOMBIE -> TgFeedTokens.WarningRed
    ChannelHealthTier.LOW_FREQ -> TgFeedTokens.TextMedium
    ChannelHealthTier.HEALTHY -> TgFeedTokens.SuccessGreen
}

/** 总分等级色：≥80 正色绿 · 60~79 星黄 · <60 警示红（环与数字同色） */
private fun scoreLevelColor(score: Int): Color = when {
    score >= 80 -> TgFeedTokens.SuccessGreen
    score >= 60 -> TgFeedTokens.StarYellow
    else -> TgFeedTokens.WarningRed
}

/**
 * 第一段 · 结论头卡：健康分环（86dp 环形进度 + countUp）+ 一句话结论
 * + 体检时间 + "重新体检"次按钮 + 折叠的"体检说明"（免责声明不占首屏）。
 */
@Composable
private fun HealthReportHeadCard(
    score: Int,
    total: Int,
    actionCount: Int,
    generatedAtMs: Long,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    var showScope by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(HealthCardRadius))
            .background(TgFeedTokens.SurfaceHigh)
            .padding(TgFeedTokens.GapLg),
        verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapLg),
        ) {
            ScoreRing(score = score, levelColor = scoreLevelColor(score))
            Column(Modifier.weight(1f)) {
                // 一句话结论（结论先行：先给判断，证据沉到后面两段）
                Text(
                    when {
                        actionCount > 0 -> "$total 个频道 · $actionCount 个建议取关"
                        else -> "$total 个频道 · 没有要取关的"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = TgFeedTokens.TextHigh,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "体检时间 ${SimpleDateFormat("M月d日 HH:mm").format(Date(generatedAtMs))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextLow,
                )
                Spacer(Modifier.height(TgFeedTokens.GapXs))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
                ) {
                    // 重新体检：描边幽灵胶囊（次按钮；重算在途时置灰防重复触发）
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                            .background(TgFeedTokens.OverlayBg)
                            .border(
                                0.5.dp,
                                TgFeedTokens.OverlayHairline,
                                RoundedCornerShape(TgFeedTokens.RadiusPill),
                            )
                            .alpha(if (refreshing) 0.5f else 1f)
                            .clickable(enabled = !refreshing, onClick = onRefresh)
                            .padding(horizontal = TgFeedTokens.GapMd, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapXs),
                    ) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = null,
                            tint = TgFeedTokens.TextMedium,
                            modifier = Modifier.size(TgFeedTokens.IconSm),
                        )
                        Text(
                            "重新体检",
                            style = MaterialTheme.typography.labelMedium,
                            color = TgFeedTokens.TextMedium,
                        )
                    }
                    // 体检说明入口（ⓘ）：免责声明收进折叠，首屏不占位
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable { showScope = !showScope },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = if (showScope) "收起体检说明" else "展开体检说明",
                            tint = if (showScope) TgFeedTokens.TgBlue else TgFeedTokens.TextLow,
                            modifier = Modifier.size(TgFeedTokens.IconMd),
                        )
                    }
                }
            }
        }
        // 体检说明：打分依据与取关边界（旧版首屏免责小字瘦身收编到这里）
        AnimatedVisibility(
            visible = showScope,
            enter = expandVertically(tween(TgFeedTokens.AnimMed)) + fadeIn(tween(TgFeedTokens.AnimMed)),
            exit = shrinkVertically(tween(TgFeedTokens.AnimMed)) + fadeOut(tween(TgFeedTokens.AnimMed)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
                    .background(TgFeedTokens.Surface)
                    .padding(TgFeedTokens.GapMd),
                verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
            ) {
                Text(
                    "打分只看本地数据：更新节奏、你的互动、广告黑名单，全程零网络。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TgFeedTokens.TextMedium,
                )
                Text(
                    "取关只动 App 内订阅表：不再追更、不进聚合；Telegram 里已加入的频道不受影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TgFeedTokens.TextMedium,
                )
                Text(
                    "标「未订阅」的频道只是刷到过、没订阅过，不在取关范围。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TgFeedTokens.TextMedium,
                )
            }
        }
    }
}

/**
 * 健康分环：86dp 圆形进度 + 环心数字（countUp 400ms 同步揭示）。
 * 弧色 = 总分等级色（绿/黄/红）沿弧做同色系渐变；底轨为弱白。
 */
@Composable
private fun ScoreRing(score: Int, levelColor: Color) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(score) { progress.animateTo(1f, tween(dev.tgfeed.ui.theme.MotionTokens.CountUpMs)) }
    Box(Modifier.size(HealthRingSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = HealthRingStroke.toPx()
            val diameter = size.minDimension - stroke
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            // 从 12 点方向起画：rotate(-90) 后弧的 0 度即屏幕正上方
            rotate(-90f) {
                drawArc(
                    color = Color.White.copy(alpha = 0.10f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                val sweep = 360f * (score / 100f) * progress.value
                if (sweep > 0.5f) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            0f to levelColor.copy(alpha = 0.55f),
                            (sweep / 360f) to levelColor,
                        ),
                        startAngle = 0f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${(score * progress.value).roundToInt()}",
                style = MaterialTheme.typography.headlineSmall,
                color = TgFeedTokens.TextHigh,
            )
            Text("健康分", style = MaterialTheme.typography.labelSmall, color = TgFeedTokens.TextLow)
        }
    }
}

/**
 * 第二段 · 建议行动卡：变质+僵尸频道（订阅中的才可执行）。
 * 行 = 头像 + 频道名 + 结论色原因 + 右侧"取关"胶囊（即点即关，走现有批量订阅移除链）。
 * [riskEntries] 是报告快照里的全部变质/僵尸频道（集合稳定，行随订阅态进出）；
 * [actionEntries] 是其中订阅中的子集（头卡计数、"全部取关"与整卡显隐的依据）。
 * 行动效：进入 stagger；取关后行划出（fade+位移），整卡在清空后延迟半拍收起。
 */
@Composable
private fun HealthActionCard(
    riskEntries: List<ChannelHealthEntry>,
    actionEntries: List<ChannelHealthEntry>,
    subIds: Set<Long>,
    onUnfollow: (ChannelHealthEntry) -> Unit,
    onUnfollowAll: () -> Unit,
) {
    AnimatedVisibility(
        visible = actionEntries.isNotEmpty(),
        enter = fadeIn(tween(TgFeedTokens.AnimMed)) + expandVertically(tween(TgFeedTokens.AnimMed)),
        exit = fadeOut(tween(TgFeedTokens.AnimMed, delayMillis = ActionCardExitDelayMs)) +
            shrinkVertically(tween(TgFeedTokens.AnimMed, delayMillis = ActionCardExitDelayMs)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(HealthCardRadius))
                .background(TgFeedTokens.SurfaceHigh)
                .padding(TgFeedTokens.GapLg),
            verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapXs),
        ) {
            // 卡头：段标题 + 档位计数 + "全部取关"次按钮（批量场景保留，走确认框）
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "建议取关",
                        style = MaterialTheme.typography.titleSmall,
                        color = TgFeedTokens.TextHigh,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        actionBreakdown(actionEntries),
                        style = MaterialTheme.typography.labelSmall,
                        color = TgFeedTokens.TextLow,
                    )
                }
                if (actionEntries.size > 1) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                            .background(TgFeedTokens.WarningRed.copy(alpha = 0.16f))
                            .border(
                                0.5.dp,
                                TgFeedTokens.WarningRed.copy(alpha = 0.40f),
                                RoundedCornerShape(TgFeedTokens.RadiusPill),
                            )
                            .clickable(onClick = onUnfollowAll)
                            .padding(horizontal = TgFeedTokens.GapMd, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "全部取关（${actionEntries.size}）",
                            style = MaterialTheme.typography.labelMedium,
                            color = TgFeedTokens.WarningRed,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            // 行清单：迭代稳定的全量风险集合，行的可见性由实时订阅态驱动（撤销后即时回位）
            riskEntries.forEachIndexed { index, entry ->
                key(entry.chatId) {
                    val subscribed = entry.chatId in subIds
                    val rowState = remember(entry.chatId) { MutableTransitionState(false) }
                    LaunchedEffect(entry.chatId, subscribed) { rowState.targetState = subscribed }
                    AnimatedVisibility(
                        visibleState = rowState,
                        enter = fadeIn(tween(TgFeedTokens.AnimMed, delayMillis = minOf(index, 8) * RowStaggerMs)) +
                            slideInVertically(
                                tween(TgFeedTokens.AnimMed, delayMillis = minOf(index, 8) * RowStaggerMs),
                            ) { it / 6 } +
                            expandVertically(tween(TgFeedTokens.AnimMed, delayMillis = minOf(index, 8) * RowStaggerMs)),
                        exit = fadeOut(tween(TgFeedTokens.AnimMed)) +
                            slideOutHorizontally(tween(TgFeedTokens.AnimMed)) { it / 2 } +
                            shrinkVertically(tween(TgFeedTokens.AnimMed)),
                    ) {
                        HealthActionRow(entry = entry, onUnfollow = { onUnfollow(entry) })
                    }
                }
            }
        }
    }
}

/** 行动卡档位计数文案："变质 X · 僵尸 Y"（空档不点名） */
private fun actionBreakdown(entries: List<ChannelHealthEntry>): String {
    val decayed = entries.count { it.tier == ChannelHealthTier.DECAYED }
    val zombie = entries.count { it.tier == ChannelHealthTier.ZOMBIE }
    return buildList {
        if (decayed > 0) add("变质 $decayed")
        if (zombie > 0) add("僵尸 $zombie")
    }.joinToString(" · ")
}

/** R318 取消胶囊：体检走查在途时取消重算（描边幽灵胶囊，与"重新体检"次按钮同按钮语言；
 *  取消=保旧报告直通，走查片间哨兵亚秒级响应） */
@Composable
private fun HealthCancelButton(onCancel: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
            .background(TgFeedTokens.OverlayBg)
            .border(
                0.5.dp,
                TgFeedTokens.OverlayHairline,
                RoundedCornerShape(TgFeedTokens.RadiusPill),
            )
            .clickable(onClick = onCancel)
            .padding(horizontal = TgFeedTokens.GapMd, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "取消体检",
            style = MaterialTheme.typography.labelMedium,
            color = TgFeedTokens.TextMedium,
        )
    }
}

/** 行动行：头像 + 频道名 + 健康分 + 结论色原因（一句话说清"为什么该取关"）+ 取关胶囊 */
@Composable
private fun HealthActionRow(
    entry: ChannelHealthEntry,
    onUnfollow: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .padding(vertical = TgFeedTokens.GapSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
    ) {
        ChannelAvatar(chatId = entry.chatId, title = entry.title, size = 36.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TgFeedTokens.TextHigh,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${entry.score} 分",
                    style = MaterialTheme.typography.labelSmall,
                    color = TgFeedTokens.TextLow,
                )
            }
            Spacer(Modifier.height(2.dp))
            if (entry.reasons.isNotEmpty()) {
                Text(
                    entry.reasons.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = TgFeedTokens.WarningRed,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 取关胶囊：即点即关不打断（可撤销），走与批量同一条订阅移除链
        Row(
            Modifier
                .clip(RoundedCornerShape(TgFeedTokens.RadiusPill))
                .background(TgFeedTokens.WarningRed.copy(alpha = 0.14f))
                .clickable(onClick = onUnfollow)
                .padding(horizontal = TgFeedTokens.GapLg, vertical = 5.dp),
        ) {
            Text(
                "取关",
                style = MaterialTheme.typography.labelMedium,
                color = TgFeedTokens.WarningRed,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * 第三段 · 明细折叠卡：筛选胶囊移到明细区头部；低频/健康默认收起（体检的行动价值
 * 只在前两档）。"待取关/仅订阅"为平铺证据视图（含数据摘要行），"全部/低频"为
 * 可展开分组。展开/收起动效 fade + 高度展开。
 */
@Composable
private fun HealthDetailCard(
    filter: HealthFilter,
    onFilter: (HealthFilter) -> Unit,
    riskEntries: List<ChannelHealthEntry>,
    lowEntries: List<ChannelHealthEntry>,
    healthyEntries: List<ChannelHealthEntry>,
    subEntries: List<ChannelHealthEntry>,
    total: Int,
    subCount: Int,
    subIds: Set<Long>,
) {
    var lowExpanded by remember { mutableStateOf(false) }
    var healthyExpanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(HealthCardRadius))
            .background(TgFeedTokens.SurfaceHigh)
            .padding(TgFeedTokens.GapLg),
        verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapXs),
    ) {
        // 筛选胶囊（保留四档纯 UI 态；窄屏单行横滑）
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
        ) {
            // 注意：AppPill 末位参数是 icon（非函数类型），onClick 必须显式具名，不能尾随 lambda
            AppPill(
                text = "全部 $total",
                selected = filter == HealthFilter.ALL,
                onClick = { onFilter(HealthFilter.ALL) },
            )
            AppPill(
                text = "待取关 ${riskEntries.size}",
                selected = filter == HealthFilter.RISK,
                onClick = { onFilter(HealthFilter.RISK) },
            )
            AppPill(
                text = "低频 ${lowEntries.size}",
                selected = filter == HealthFilter.LOW,
                // 点低频档顺带展开对应分组（减少一次点击）
                onClick = {
                    onFilter(HealthFilter.LOW)
                    lowExpanded = true
                },
            )
            AppPill(
                text = "仅订阅 $subCount",
                selected = filter == HealthFilter.SUBS,
                onClick = { onFilter(HealthFilter.SUBS) },
            )
        }
        when (filter) {
            HealthFilter.ALL, HealthFilter.LOW -> {
                if (lowEntries.isEmpty() && (filter == HealthFilter.LOW || healthyEntries.isEmpty())) {
                    HealthLensEmpty()
                } else {
                    // 低频分组（"还有 N 个低频频道"）
                    HealthGroupHeader(
                        text = "还有 ${lowEntries.size} 个低频频道",
                        dotColor = tierColor(ChannelHealthTier.LOW_FREQ),
                        expanded = lowExpanded,
                        onToggle = { lowExpanded = !lowExpanded },
                    )
                    AnimatedVisibility(
                        visible = lowExpanded,
                        enter = expandVertically(tween(TgFeedTokens.AnimMed)) + fadeIn(tween(TgFeedTokens.AnimMed)),
                        exit = shrinkVertically(tween(TgFeedTokens.AnimMed)) + fadeOut(tween(TgFeedTokens.AnimMed)),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapXs)) {
                            lowEntries.forEach { HealthDetailRow(entry = it, subscribed = it.chatId in subIds) }
                        }
                    }
                    // 健康分组（仅"全部"档出现）
                    if (filter == HealthFilter.ALL && healthyEntries.isNotEmpty()) {
                        HealthGroupHeader(
                            text = "${healthyEntries.size} 个健康频道",
                            dotColor = tierColor(ChannelHealthTier.HEALTHY),
                            expanded = healthyExpanded,
                            onToggle = { healthyExpanded = !healthyExpanded },
                        )
                        AnimatedVisibility(
                            visible = healthyExpanded,
                            enter = expandVertically(tween(TgFeedTokens.AnimMed)) + fadeIn(tween(TgFeedTokens.AnimMed)),
                            exit = shrinkVertically(tween(TgFeedTokens.AnimMed)) + fadeOut(tween(TgFeedTokens.AnimMed)),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(TgFeedTokens.GapXs)) {
                                healthyEntries.forEach { HealthDetailRow(entry = it, subscribed = it.chatId in subIds) }
                            }
                        }
                    }
                }
            }
            HealthFilter.RISK -> {
                if (riskEntries.isEmpty()) HealthLensEmpty() else {
                    riskEntries.forEach { HealthDetailRow(entry = it, subscribed = it.chatId in subIds) }
                }
            }
            HealthFilter.SUBS -> {
                if (subEntries.isEmpty()) HealthLensEmpty() else {
                    subEntries.forEach { HealthDetailRow(entry = it, subscribed = it.chatId in subIds) }
                }
            }
        }
    }
}

/** 折叠分组头：色点 + 标题 + 展开箭头（点击切换；箭头随展开旋转） */
@Composable
private fun HealthGroupHeader(
    text: String,
    dotColor: Color,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val arrow by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(TgFeedTokens.AnimFast),
        label = "groupArrow",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TgFeedTokens.RadiusMd))
            .clickable(onClick = onToggle)
            .padding(vertical = TgFeedTokens.GapSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapSm),
    ) {
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = TgFeedTokens.TextHigh,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "收起" else "展开",
            tint = TgFeedTokens.TextLow,
            modifier = Modifier.size(TgFeedTokens.IconLg).rotate(arrow),
        )
    }
}

/** 明细行（证据视图）：头像 + 频道名 + 健康分（分层色）+ 判定原因 + 数据摘要行 */
@Composable
private fun HealthDetailRow(entry: ChannelHealthEntry, subscribed: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(vertical = TgFeedTokens.GapXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TgFeedTokens.GapMd),
    ) {
        ChannelAvatar(chatId = entry.chatId, title = entry.title, size = 28.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (subscribed) TgFeedTokens.TextHigh else TgFeedTokens.TextMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${entry.score} 分",
                    style = MaterialTheme.typography.labelSmall,
                    color = tierColor(entry.tier),
                )
            }
            Spacer(Modifier.height(2.dp))
            if (entry.reasons.isNotEmpty()) {
                Text(
                    entry.reasons.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = TgFeedTokens.TextMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            // 数据摘要（更新节奏 + 互动节奏，与判定原因互证）只做一行
            Text(
                healthMetaLine(entry),
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextLow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 纯算法关注的频道（没进订阅表）标"未订阅"，不在取关范围
        if (!subscribed) {
            Text(
                "未订阅",
                style = MaterialTheme.typography.labelSmall,
                color = TgFeedTokens.TextLow,
            )
        }
    }
}

/** 筛选落空提示：该档没有频道（如没有低频频道） */
@Composable
private fun HealthLensEmpty() {
    Text(
        "这个筛选下没有频道",
        style = MaterialTheme.typography.bodyMedium,
        color = TgFeedTokens.TextLow,
        modifier = Modifier.padding(vertical = TgFeedTokens.GapLg),
    )
}

/** 频道头像：无头像字节的字母圆（按频道 id 稳定取底色，与发现页头像调色板同口径） */
@Composable
private fun ChannelAvatar(chatId: Long, title: String, size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(healthAvatarColor(chatId)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title.firstOrNull()?.uppercase() ?: "?",
            color = TgFeedTokens.TextHigh,
            fontSize = (size.value * 0.38f).sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 字母头像柔和底色板（与发现页私有版同调色板；那边私有不可跨文件引用，镜像一份） */
private val HealthAvatarPalette = listOf(
    Color(0xFF5C6BC0), // 柔靛蓝
    Color(0xFF26A69A), // 柔青绿
    Color(0xFFEF5350), // 柔红
    Color(0xFFAB47BC), // 柔紫
    Color(0xFFFFA726), // 柔橙
    Color(0xFF42A5F5), // 柔蓝
    Color(0xFF66BB6A), // 柔绿
)

/** 按频道 id 稳定取一个头像底色（floorMod 避免 Long.MIN_VALUE 取负） */
private fun healthAvatarColor(channelId: Long): Color =
    HealthAvatarPalette[Math.floorMod(channelId, HealthAvatarPalette.size)]

/** 数据摘要行文案：最近更新 X 天前 · 近 30 天 N 条 · 上次观看（无证据项显示占位符） */
private fun healthMetaLine(e: ChannelHealthEntry): String {
    val update = e.lastPostDays?.let { "最近更新 $it 天前" } ?: "更新数据无"
    val recent = e.posts30d?.let { "近30天 $it 条" } ?: "近30天未知"
    val watch = e.lastWatchDays?.let { "上次看 $it 天前" } ?: "从未看过"
    return "$update · $recent · $watch"
}
