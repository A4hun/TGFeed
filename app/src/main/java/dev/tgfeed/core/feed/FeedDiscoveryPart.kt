package dev.tgfeed.core.feed

import androidx.lifecycle.viewModelScope
import dev.tgfeed.core.td.ChannelBrief
import dev.tgfeed.core.td.FeedRequest
import dev.tgfeed.core.td.TdLibGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit

// ---------------- PK 擂台投票入画像（分屏对打） ----------------

/**
 * PK 擂台投票喂算法：赢家频道 +0.4 / 输家频道 -0.2。
 * 复用互动加分表 [addInteractionBoost] 的内部机制——内存表即时生效、
 * IO 线程异步落盘、单频道累计 clamp 到 [0, INTERACTION_BOOST_CAP]，
 * 封顶/封底逻辑完全沿用，不新开通道。
 * chatId = 0（本地文件等无频道归属的条目）跳过不写表；赢输同频道时只记赢家。
 */
internal fun FeedViewModel.recordPkVoteImpl(winnerChatId: Long, loserChatId: Long) {
    if (winnerChatId != 0L) addInteractionBoost(winnerChatId, 0.4)
    if (loserChatId != 0L && loserChatId != winnerChatId) addInteractionBoost(loserChatId, -0.2)
}

// ---------------- 发现页（R183 双宝藏架构：深索引金矿 ∪ 用户搜索群 → 过滤闸 → 公平轮转） ----------------

/**
 * 刷新发现页候选（IO 协程；进页时由 DiscoverScreen 的 LaunchedEffect 触发）。
 *
 * **R183 双宝藏架构（用户终版设计：双宝藏全量 → 垃圾/广告过滤闸 → 公平轮转展示）**：
 *  1. **宝藏一·深索引金矿**（[ensureDiscoveryGoldSnapshot]）：604 频道/几百万条 items
 *    折算的客观档案（丰度分=R163 公式同源、近 7 天更新、死链/广告信号）——
 *    数据地基从「监听陌生群」换成「挖自己的深索引」；快照陈值先回+过期后台单飞重建
 *    （R170 预热协调器纪律，重查询零阻塞）；
 *  2. **宝藏二·用户搜索群信号**（[aggregateGroupSignals]）：用户自己的已加入搜索群
 *    收割产物（答案库 hits + group_search 边 count）→「你的搜索群在传」热度；
 *    群里收获的频道与金矿档案合并成**双置信**候选（推荐语「深索引验货 · 你的搜索
 *    群在传」）；索引没档案的群搜新面孔按「待深索引验货」入池（群热度背书）；
 *  3. **过滤闸**（[AdPatternFilter]——唯一的门，无口味个性化）：广告话术密度/
 *    同频道模板风暴/跨频道 spam 农场指纹/双零拦截（群零安利∧索引零丰度），
 *    外加 AdBlacklist 标题词表与 AdChannelClassifier 运行时黑名单兜底；
 *  4. **公平轮转**（[DiscoveryGoldmine.fairRotationSelect] 泛型版，R176「最少曝光
 *    优先」思想）：合格频道全量进池按曝光账升序轮转，人人有出场机会不被头部霸屏；
 *    tie-break 全客观（丰度/近期更新/存货），不做口味打分排序个性化；
 *  5. **语义修正**（R154 时代遗留）：已加入过滤集不再并入 deepIndex.indexedChannelIds
 *    ——R163 后深索引爬全宇宙（含未加入频道），「索引过 ≠ 已加入」；终点权威校验
 *    isJoinedChat 保留（网络缓存命中零请求）；
 *  6. 硬过滤（订阅/屏蔽/软拒绝 TTL）与「换一批」排除集+空池回落（v3/v4 语义）、
 *    广告黑名单兜底、占位标题小预算懒解析、曝光记账、臂归属记账（收/弃归因）、
 *    GetSupergroupFullInfo 懒加载全部原样保留；
 *  7. 发布：候选（公平轮转序）+ 来源角标（深索引/群搜/双置信）+ 客观推荐语
 *    （[DiscoveryGoldmine.recommendLineOf]——「索引丰度 92 分」「你的 3 个搜索群在传」
 *    「近 7 天日更」，不写口味）+ 佐证徽章（[DiscoveryGoldmine.corroborationChipsOf]
 *    ——广场/群搜/上升计数降级为胶囊，不进主排序）+ 证据行 + 周榜（近 7 天发布条数
 *    ——深索引第一手，替代旧图边二手计数）；
 *  8. 顶部区段（热词榜/统计胶囊/新发现横滑/收录播报）与曝光/试吃/收/弃链路全部
 *    原样保留（UI 已验收骨架不动）。
 *
 * 已砍（终版设计裁决）：相似扇出 getSimilarChats 15 查询/轮（官方相似=服务器侧
 * 共现，与被批评的二手噪音同族）；DiscoveryRanker 主排序（Thompson 臂个性化采样
 * 与曝光疲劳——曝光疲劳语义由公平轮转的曝光账升序天然承载）；图谱 topCandidates
 * 主输入（互链共现=二手噪音，group_search 边降级为宝藏二信号聚合）。
 *
 * Mock 网关：无金矿快照（hasIndex 假）、无收割产物 → 候选恒空 → 空态引导
 * （DISCOVERY-GRAPH §8 Mock 降级口径，雷达巡逻并入通道照旧）。
 *
 * @param excludeIds 本轮排除集（v3 刷新"换一批"语义）：手动刷新/骰子把当前
 *     已展示的 chatId 集传进来，轮转池直接剔除——换出的是真·新面孔；排除集只随
 *     本次调用生效、不落任何状态，下一次刷新（不含参调用）自然复位；剔除后候选
 *     不足允许列表变短/真空（UI 空态承接）。进页首刷传空集（默认）。
 * @param allowNetworkArm R377 懒门网络臂开关（默认 true=恒许，显式用户动作路径
 *     下拉刷新/换一批/骰子/双击不受门）：受门两臂=runTasteRadar（SearchPublicPosts
 *     日配额）+resolvePendingChannels（SearchPublicChat 族）；false=纯本地聚合
 *     （门关/窗内再开/R211 直荐补刷/金矿首建补刷的背景刷新形态——网络工作恒挂
 *     用户意图时刻+门内常驻循环两处，不再有第三条背景暗道）。listChannels/
 *     joinStatusOf（GetChat 族本地缓存优先）/previews（GetFile 族页面可见驱动）
 *     不属于 Search 族账号桶消耗者，零碰。
 */
internal fun FeedViewModel.refreshDiscoveryImpl(excludeIds: Set<Long> = emptySet(), allowNetworkArm: Boolean = true) {
    // W5 口味雷达：进发现页顺带触发每日巡逻（内部"每天最多一次"节流，非今日首次
    // 调用立即空转，零成本；命中频道经 FeedRadarPart 既有通道异步并入——客观信号
    // 佐证源，不参与本池主排序）。终版设计砍口味个性化主排序，巡逻本身保留
    // （既有验收行为不动，网络配额每日一次）。R377 懒门：Search 族账号桶消耗者，
    // 受 allowNetworkArm 门控（背景刷新形态不巡逻）。
    if (allowNetworkArm) runTasteRadar()
    if (_discoveryLoading.value) return // 防双击/快速进出页并发刷新
    _discoveryLoading.value = true
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val real = gateway as? TdLibGateway
            // 1) 图谱待解析队列（宝藏二群搜用户名链接的懒解析——预算制既有通道，保留；
            //    R377 懒门：SearchPublicChat 族消耗者，受 allowNetworkArm 门控）
            if (allowNetworkArm && real != null) runCatching { real.resolvePendingChannels() }
            val now = System.currentTimeMillis()
            // 2) 宝藏一：金矿快照（陈值先回/过期后台单飞重建/首次同步构建）
            val goldById = ensureDiscoveryGoldSnapshot()?.byId() ?: emptyMap()
            // 3) 状态账：软拒绝 TTL 过滤集 + 曝光账（公平轮转的 servedCount 原料）
            val candStates = runCatching { store.loadDiscoveryCandidateStates() }.getOrDefault(emptyMap())
            val softRejected = candStates.values
                .filter { it.dismissCount > 0 && now - it.lastDismissTs < DISCOVERY_SOFT_REJECT_TTL_MS }
                .mapTo(HashSet()) { it.chatId }
            val impressions = buildMap {
                for ((id, s) in candStates) put(id, s.impressions)
            }
            // 4) 宝藏二：搜索群信号聚合（答案库带词投影 + group_search 边 + R211 直荐
            //    注册表，纯本地读零网络）——R211 词源换血：来源词直接取答案库关键词
            //    （群友搜过的词），不再依赖用户自己的搜索历史精确命中（旧口径下
            //    「群友搜『X』找到」永不出现——X 必须是用户自己搜过的词）。
            //    空收割数据整段空转。
            val bundle = aggregateGroupSignalsEx(real)
            val groupSignals = bundle.signals
            val sourceWords = bundle.sourceWords
            _discoverySourceWords.value = sourceWords
            // 5) 过滤集合（R183 语义修正：索引 ≠ 已加入——deepIndex.indexedChannelIds
            //    不再并入排除集；GetChats 前缀 ∪ 订阅集是弱集合，终点由 isJoinedChat
            //    权威校验兜底）
            val subscribedNow = _subscriptions.value.mapTo(HashSet()) { it.id }
            val blockedNow = _blockedChannels.value
            val joinedNow = runCatching { gateway.listChannels() }.getOrDefault(emptyList())
                .mapTo(HashSet()) { it.id }
                .apply { addAll(subscribedNow) }
            var joinedDropped = 0
            fun filteredOut(id: Long) = id in subscribedNow || id in blockedNow || id in softRejected
            fun fallbackTitle(id: Long): String =
                _subscriptions.value.firstOrNull { it.id == id }?.title
                    ?: _channels.value.firstOrNull { it.id == id }?.title
                    ?: ""
            // 6) 双宝藏候选池 + 过滤闸（唯一的门——无口味筛选无分值个性化）
            val plazaSnap = _plaza.value
            val pool = ArrayList<DiscoveryGoldCandidate>(goldById.size + groupSignals.size)
            var gateDropped = 0
            val seen = HashSet<Long>(goldById.size + groupSignals.size)
            // 6a) 宝藏一全量（未关注金矿频道；宝藏二信号命中即合并成双置信）
            for ((id, g) in goldById) {
                if (id in joinedNow) { joinedDropped++; continue } // 已加入不出榜
                if (filteredOut(id)) continue
                val sig = groupSignals[id]
                val verdict = DiscoveryGoldmine.verdictOf(g, sig?.endorse ?: 0)
                if (!verdict.pass) {
                    gateDropped++
                    continue
                }
                seen += id
                pool += DiscoveryGoldCandidate(
                    chatId = id,
                    title = g.title.ifBlank { fallbackTitle(id) },
                    gold = g,
                    signal = sig,
                    plazaRecommends = plazaSnap?.channelRecommends?.get(id) ?: 0,
                    plazaBlocks = plazaSnap?.channelBlocks?.get(id)?.first ?: 0,
                    username = sig?.username ?: "",
                )
            }
            // 6b) 宝藏二纯新面孔（索引还没档案——「待深索引验货」形态；群信号非零才在
            //     groupSignals 里，双零拦截天然不命中）
            for ((id, sig) in groupSignals) {
                if (id in seen) continue // 已并入 6a
                if (id in joinedNow) { joinedDropped++; continue }
                if (filteredOut(id)) continue
                seen += id
                pool += DiscoveryGoldCandidate(
                    chatId = id,
                    title = sig.hintTitle.ifBlank { fallbackTitle(id) },
                    gold = null,
                    signal = sig,
                    plazaRecommends = plazaSnap?.channelRecommends?.get(id) ?: 0,
                    plazaBlocks = plazaSnap?.channelBlocks?.get(id)?.first ?: 0,
                    username = sig.username,
                )
            }
            if (joinedDropped > 0) {
                android.util.Log.d("FeedViewModel", "refreshDiscovery: 已加入频道过滤 $joinedDropped 个")
            }
            if (gateDropped > 0) {
                android.util.Log.d("FeedViewModel", "refreshDiscovery: 过滤闸拦截 $gateDropped 个（广告/双零）")
            }
            // 7) 广告黑名单兜底（标题词表 + 收割侧分类器运行时黑名单——宝藏二无档案
            //    候选没走收割分类闸的补一道；占位标题天然不命中词表）
            val adDropped = pool.filter { AdBlacklist.isSpam(it.title) }.map { it.chatId }
            val adClassDropped = pool.filter { AdChannelClassifier.isBlockedRuntime(it.chatId, null) }
                .map { it.chatId }
            (adDropped + adClassDropped).distinct().let { dropped ->
                if (dropped.isNotEmpty()) {
                    pool.removeAll { it.chatId in dropped }
                    android.util.Log.d("FeedViewModel", "refreshDiscovery: 广告黑名单兜底剔除 ${dropped.size} 个")
                }
            }
            // 7.5) 池级权威"已加入"校验（R183 真机修正：选后再剔 = 50 个出榜名额被
            //      静默已加入频道占用——实测金矿候选 45/50 是「已加入但不在 GetChats
            //      活跃前缀」的静默频道（判定本身正确，但页面只剩 5 张卡）；前移到
            //      选前过滤，名额全部留给真候选。GetChat/GetSupergroup 均本地读 +
            //      JOINED 结论进程缓存——池级逐点成本毫秒级；UNKNOWN（本地无记录=
            //      从未打开=从未加入）放行，9) 的终点校验保留作纵深防御）
            if (real != null) {
                val poolJoined = ArrayList<Long>()
                for (c in pool) {
                    if (real.joinStatusOf(c.chatId) == dev.tgfeed.core.td.JoinStatus.JOINED) {
                        poolJoined += c.chatId
                    }
                }
                if (poolJoined.isNotEmpty()) {
                    pool.removeAll { it.chatId in poolJoined }
                    android.util.Log.d(
                        "FeedViewModel",
                        "refreshDiscovery: 池级权威校验剔除已加入 ${poolJoined.size} 个（选前过滤，名额不浪费）",
                    )
                }
            }
            // 8) 池深定格（排除与出榜截断前——v4"换一批"收口依据）+ 排除集剔除与备份
            //    （v3 空池回落：排除后全灭 → 还原排除集原地重排一次，防"点刷新跌进
            //    空态"；池深口径偏松的兜底语义原样保留）
            _discoveryPoolSize.value = pool.size
            var excludedBackup: List<DiscoveryGoldCandidate> = emptyList()
            if (excludeIds.isNotEmpty()) {
                val before = pool.size
                excludedBackup = excludeIds.mapNotNull { id -> pool.firstOrNull { it.chatId == id } }
                pool.removeAll { it.chatId in excludeIds }
                val excluded = before - pool.size
                if (excluded > 0) {
                    android.util.Log.d("FeedViewModel", "refreshDiscovery: 本轮排除已展示候选 $excluded 个")
                }
            }
            // 9) 公平轮转 + 权威"已加入"终点校验（封局部函数供空池回落复用）
            suspend fun selectAndVerify(): List<DiscoveryGoldCandidate> {
                val selected = DiscoveryGoldmine.fairRotationSelect(
                    pool = pool,
                    idOf = { it.chatId },
                    // 客观 tie-break：金矿丰度优先；待验货新面孔走 R211 群候选纯函数
                    // （[DiscoveryGoldmine.groupCandidateQuality] 单一定义：直荐验证
                    // +0.10 / 近 24h +0.05 / 近 7d +0.02，封顶 0.5 < 0.55 丰度线——
                    // 群热度是强佐证但无验货，排序上让位给丰度过线（≥0.55）的金矿频道；
                    // 陈旧未验证从旧一刀切 0.5 降 0.35——「新鲜热乎的」与「验过活的」
                    // 在同 endorse 层里先出，任务书时效性口径）
                    qualityOf = { c ->
                        c.gold?.richness
                            ?: DiscoveryGoldmine.groupCandidateQuality(
                                endorse = c.signal?.endorse ?: 0,
                                verified = c.signal?.verified ?: false,
                                signalAgeMs = now - (c.signal?.lastTs ?: 0L),
                            )
                    },
                    // R211 群候选活跃度（[DiscoveryGoldmine.groupCandidateActivity]）：
                    // 新鲜度分档（24h=10/7d=5）压过 endorse 计数
                    activityOf = { c ->
                        c.gold?.recent7
                            ?: DiscoveryGoldmine.groupCandidateActivity(
                                endorse = c.signal?.endorse ?: 0,
                                signalAgeMs = now - (c.signal?.lastTs ?: 0L),
                            )
                    },
                    stockOf = { it.gold?.items ?: 0 },
                    impressions = impressions,
                    excludeIds = excludeIds,
                    limit = DISCOVERY_CANDIDATE_LIMIT,
                )
                if (real == null || selected.isEmpty()) return selected
                // R183 三值口径：JOINED 剔除；UNKNOWN（chat 本地无记录=从未打开=从未加入
                // ——深索引候选常态；真机实锤 R64 保守口径曾把 50/50 全误杀）与
                // NOT_JOINED 都放行。既有 R57 新鲜区仍走保守版 isJoinedChat（口径分轨，
                // 语义零变化）。UNKNOWN 单独计数——金矿「从未打开频道」出榜占比的诊断口径。
                val unknownKept = java.util.concurrent.atomic.AtomicInteger(0)
                val kept = coroutineScope {
                    selected.chunked(DISCOVERY_JOIN_CHECK_CONCURRENCY).flatMap { batch ->
                        batch.map { c ->
                            async(Dispatchers.IO) {
                                when (real.joinStatusOf(c.chatId)) {
                                    dev.tgfeed.core.td.JoinStatus.JOINED -> null
                                    dev.tgfeed.core.td.JoinStatus.UNKNOWN -> c.also { unknownKept.incrementAndGet() }
                                    dev.tgfeed.core.td.JoinStatus.NOT_JOINED -> c
                                }
                            }
                        }.map { it.await() }
                    }.filterNotNull()
                }
                val authDropped = selected.size - kept.size
                if (authDropped > 0 || unknownKept.get() > 0) {
                    android.util.Log.d(
                        "FeedViewModel",
                        "refreshDiscovery: 权威校验剔除已加入 $authDropped 个；本地未知(=从未加入,放行) ${unknownKept.get()} 个",
                    )
                }
                return kept
            }
            var verified = selectAndVerify().toMutableList()
            // 空池回落（"点刷新变空态"的修复，原样保留）：还原排除集原地重排一次
            if (verified.isEmpty() && excludedBackup.isNotEmpty()) {
                android.util.Log.d(
                    "FeedViewModel",
                    "refreshDiscovery: 排除后候选全灭，还原排除集原地重排兜底（还原 ${excludedBackup.size} 个）",
                )
                excludedBackup.forEach { c -> if (pool.none { it.chatId == c.chatId }) pool += c }
                excludedBackup = emptyList() // 只回落一次，防死循环
                verified = selectAndVerify().toMutableList()
            }
            // 10) 标题关：占位标题（"频道 <id>"——宝藏二 hintTitle 缺失的裸 ID 候选）小预算
            //     懒解析（fetchVideos 一页取 channelTitle；金矿候选标题自带免网络）。
            //     解析成功回写图谱 recordNodeTitle（落盘，跨重启自愈）——旧口径原样。
            var titleResolveBudget = DISCOVERY_TITLE_RESOLVE_MAX
            val titleResolved = HashMap<Long, String>()
            val titleDropped = ArrayList<Long>()
            for (c in verified) {
                val t = c.title
                if (t.isNotBlank() && !t.startsWith("频道 ")) continue // 已有真标题
                val resolved = discoveryTitleCache[c.chatId] ?: run {
                    if (titleResolveBudget <= 0) return@run null
                    titleResolveBudget--
                    runCatching {
                        gateway.fetchVideos(FeedRequest(channelId = c.chatId), null, 1)
                            .items.firstOrNull()?.channelTitle?.takeIf { it.isNotBlank() }
                    }.getOrNull()
                }
                if (resolved == null) {
                    titleDropped += c.chatId
                } else {
                    titleResolved[c.chatId] = resolved
                    discoveryTitleCache[c.chatId] = resolved
                    if (real != null) runCatching { real.discoveryGraph.recordNodeTitle(c.chatId, resolved) }
                }
            }
            titleDropped.forEach { id -> verified.removeAll { it.chatId == id } }
            titleResolved.forEach { (id, title) ->
                verified.firstOrNull { it.chatId == id }?.let { idx ->
                    verified[verified.indexOf(idx)] = idx.copy(title = title)
                }
            }
            if (titleResolved.isNotEmpty() || titleDropped.isNotEmpty()) {
                android.util.Log.d(
                    "FeedViewModel",
                    "refreshDiscovery: 裸ID标题解析 成功=${titleResolved.size} 换掉=${titleDropped.size} 剩余预算=$titleResolveBudget",
                )
            }
            // 11) 发布：候选（公平轮转序）+ 角标 + 证据 + 推荐语 + 佐证徽章 + 周榜
            val memberKnown: (Long) -> Int? = { id ->
                discoveryMemberCounts[id]?.takeIf { it > 0 }
                    ?: groupSignals[id]?.members?.takeIf { it > 0 }
            }
            _discoveryCandidates.value = verified.map { it.brief }
            _discoverySources.value = verified.associate { c ->
                c.chatId to when {
                    c.gold != null && c.signal != null -> "深索引+群搜" // 双置信
                    c.gold != null -> "深索引"
                    // R211 直荐（任务书 c：标注来源「你的搜索群」——用户勾选群+验证过）
                    c.signal?.verified == true -> "你的搜索群"
                    else -> "群搜"
                }
            }
            _discoveryEvidence.value = verified.associate { c ->
                val lines = ArrayList<String>(2)
                val sig = c.signal
                if (sig != null && sig.groupCount > 0) {
                    lines += "你的 ${sig.groupCount} 个搜索群在搜它"
                }
                // R211 直荐证据行：USER 车道验过「存在+是频道」——时效性档位已进
                // 排序（groupCandidateQuality/Activity），这里只给事实结论不写相对时间
                // （相对时间文案已有 freshAgeLabel/StoriesBar 两份私有件，不再加第三份）
                if (sig?.verified == true && lines.size < 2) {
                    lines += "已验证频道存活 · 你的搜索群在传"
                }
                val g = c.gold
                if (g != null && g.probed) {
                    val pct = (g.richness * 100).toInt().coerceIn(0, 100)
                    lines += "索引验货：丰度 $pct 分 · ${DiscoveryGoldmine.fmtCount(g.items)} 条存货"
                }
                if (lines.size < 2 && g != null && g.recent7 > 0) {
                    lines += "近 7 天更新 ${g.recent7} 条"
                }
                if (lines.size < 2 && c.plazaRecommends > 0) {
                    lines += "全站 ${c.plazaRecommends} 人推荐过它"
                }
                if (lines.size < 2 && c.plazaBlocks >= 2) {
                    lines += "⚠ 全站 ${c.plazaBlocks} 人标记了避雷"
                }
                c.chatId to lines.take(2)
            }
            _discoveryRecommendLines.value = verified.associate { c ->
                c.chatId to DiscoveryGoldmine.recommendLineOf(
                    g = c.gold,
                    groupEndorse = c.signal?.endorse ?: 0,
                    groupSearchWord = sourceWords[c.chatId],
                    memberCount = memberKnown(c.chatId),
                    userGroupVerified = c.signal?.verified ?: false,
                )
            }
            _discoveryCorroborations.value = verified.associate { c ->
                // R204 DS-3 断更警示胶囊置顶（防订阅尸体语义优先于正向佐证），
                // 沿 corroborationChipsOf 的 ≤3 枚截断口径
                val dormancyChip = c.gold?.let { ChannelDormancy.chipOf(it) }
                val base = DiscoveryGoldmine.corroborationChipsOf(
                    g = c.gold,
                    groupCount = c.signal?.groupCount ?: 0,
                    groupEndorse = c.signal?.endorse ?: 0,
                    plazaRecommends = c.plazaRecommends,
                    memberCount = memberKnown(c.chatId),
                )
                c.chatId to (if (dormancyChip != null) listOf(dormancyChip) + base else base)
                    .take(DiscoveryGoldmine.CORROBORATION_MAX)
            }
            // 成员数视图（enrich 懒加载缓存 + 答案库/图谱线索；缺 key = 未知不渲染）
            _discoveryMemberCountView.value = buildMap {
                for ((id, mc) in discoveryMemberCounts) if (mc > 0) put(id, mc)
                for (c in verified) memberKnown(c.chatId)?.let { put(c.chatId, it) }
            }
            // 周榜（R183 语义换血）：近 7 天发布条数（深索引第一手），计数 >0 才进榜
            _discoveryWeeklyEdgeCounts.value = verified.mapNotNull { c ->
                c.gold?.takeIf { it.recent7 > 0 }?.let { g -> c.chatId to g.recent7 }
            }.toMap()
            // 来源臂归属表（收/弃时向 discovery_arms.json 记账——臂学习已退出主排序，
            // 底账继续积累：deep/search/dual 三臂，将来任何消费方可用）
            verified.forEach { c ->
                discoveryArmById[c.chatId] = when {
                    c.gold != null && c.signal != null -> "dual"
                    c.gold != null -> "deep"
                    else -> "search"
                }
            }
            // 12) 顶部区段（P4-A 热词榜 + 新发现 + 统计胶囊 + 收录播报——旧代码原样保留）
            val freshInfos = runCatching { GoldmineHooks.freshChannels(now) }.getOrDefault(emptyList())
            val hotAll = runCatching {
                GoldmineHooks.hotKeywords(DISCOVERY_HOT_WORD_WINDOW_MS, DISCOVERY_HOT_WORD_STATS_LIMIT)
            }.getOrDefault(emptyList())
            // R372 雷达热词窗（48h=GoldmineHooks 留存窗满档）：词行与雷达卡共用一次读
            // （纯内存聚合零网络）；hotAll（24h）仍归统计胶囊「今日收割 X 词」——
            // 统计窗与雷达窗分轨，各说各话（旧 24h 定窗=断流一天整区全黑的病根②修点）
            val radarHot = runCatching {
                GoldmineHooks.hotKeywords(DISCOVERY_RADAR_WORD_WINDOW_MS, DISCOVERY_HOT_WORD_STATS_LIMIT)
            }.getOrDefault(emptyList())
            _discoveryHotWords.value = radarHot.take(DISCOVERY_HOT_WORD_LIMIT)
            // R372 雷达频道卡：「群友正在搜 XXX，返回了 YYY 频道」的 Y 侧（热词头部词
            // →答案库返回频道，纯本地读；诚实门三闸+非频道闸在 buildRadarCards 单源）
            _discoveryRadarCards.value = buildRadarCards(real, radarHot, joinedNow)
            _discoveryFreshIds.value = freshInfos.mapTo(HashSet()) { it.chatId }
            val blockedAds = runCatching {
                real?.groupHarvester?.adClassifier?.stats()?.second ?: 0
            }.getOrDefault(0)
            val harvestStats = DiscoveryHarvestStats(
                harvestedWords = hotAll.sumOf { it.second },
                freshChannels = freshInfos.count { now - it.firstTs < DISCOVERY_HOT_WORD_WINDOW_MS },
                blockedAds = blockedAds,
            )
            _discoveryHarvestStats.value =
                if (harvestStats.harvestedWords + harvestStats.freshChannels + harvestStats.blockedAds > 0) {
                    harvestStats
                } else {
                    null
                }
            val intakeTotal = real?.let { g ->
                runCatching { g.discoveryGraph.topCandidates(Int.MAX_VALUE).size }.getOrDefault(0)
            } ?: 0
            _discoveryIntakeStats.value =
                if (harvestStats.freshChannels + intakeTotal > 0) {
                    DiscoveryIntakeStats(todayNew = harvestStats.freshChannels, totalNodes = intakeTotal)
                } else {
                    null
                }
            _discoveryFreshCards.value = buildFreshRailCards(real, freshInfos, joinedNow)
            // 13) 曝光计数落表（进榜即记一次曝光——公平轮转曝光账的数据源；被权威校验
            //     剔除的不记）+ 档案懒加载（成员数喂冷门判定与卡片展示）
            runCatching { store.recordDiscoveryImpressions(verified.map { it.chatId }) }
            enrichDiscoveryProfiles(verified.map { it.chatId })
        } finally {
            _discoveryLoading.value = false
        }
    }
}

/**
 * 发现页"试吃"：显式记录漏斗中间环（DISCOVER-UPGRADE P1-a #1：曝光→试吃→收/弃 全链路可归因）。
 * 只刷候选状态表的 lastTasteTs（不动曝光/软拒绝字段），随后由 UI 打开频道 Feed——
 * 不加订阅、不进 blocked，语义与原来一致，只是从此留下归因证据。
 */
internal fun FeedViewModel.tasteDiscoveryCandidateImpl(chatId: Long) {
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { store.recordDiscoveryTaste(chatId, System.currentTimeMillis()) }
    }
}

/**
 * 发现页"收"：订阅该频道并即时从候选列表移除（订阅写盘走 [addSubscription] 既有通道）。
 * P1-a #2：收下不再清空候选状态——改记 acceptedTs 成交戳，归因证据
 * （曝光次数/试吃戳/过往弃记录）全量保留；P1-a #3：顺手给来源臂记一次命中（α+1）。
 */
internal fun FeedViewModel.acceptDiscoveryCandidateImpl(c: ChannelBrief) {
    _discoveryCandidates.value = _discoveryCandidates.value.filterNot { it.id == c.id }
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { store.recordDiscoveryAccept(c.id, System.currentTimeMillis()) }
        runCatching { store.bumpDiscoveryArmHit(discoveryArmById[c.id] ?: "other") }
    }
    // P1-d：收下即向广场上报频道推荐（[R] 协议；服务内每频道一次 + 10s 限速，
    // 未开启广场/Mock 网关自动空转）——用户的"收"是全站热推臂的真实票源
    plazaPostChannelRecommend(c.id)
    addSubscription(c)
}

/**
 * 发现页"弃"：软拒绝梯度（DISCOVER-DESIGN §8.2，替换旧的"一次弃 = 永久拉黑"）：
 *  - 轻：首次弃 → 候选状态表记 dismissCount=1 + dismissedTs，30 天 TTL 内不再出现
 *   （refreshDiscovery 硬过滤），误伤可逆——30 天后若仍被图谱/相似推荐可复出；
 *  - 中：复出后再次被弃（dismissCount ≥ 1 的二次弃）→ 升级进 blocked_channels
 *    永久屏蔽（现状语义，二次确认才动真格）；
 * 已在屏蔽表里的 id 不再 toggle——[toggleBlockChannel] 是翻转语义，重复调用会变成解除屏蔽。
 */
internal fun FeedViewModel.dismissDiscoveryCandidateImpl(chatId: Long) {
    _discoveryCandidates.value = _discoveryCandidates.value.filterNot { it.id == chatId }
    viewModelScope.launch(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val prev = runCatching { store.loadDiscoveryCandidateStates()[chatId] }.getOrNull()
        val next = runCatching { store.recordDiscoveryDismiss(chatId, now) }.getOrNull() ?: return@launch
        // P1-a #3：来源臂记一次未命中（β+1；臂底账继续积累——R183 起臂学习已退出主排序）
        runCatching { store.bumpDiscoveryArmMiss(discoveryArmById[chatId] ?: "other") }
        if ((prev?.dismissCount ?: 0) >= 1) {
            // 二次弃 → 永久拉黑（中级）
            android.util.Log.d("FeedViewModel", "发现页二次弃，升级永久屏蔽: chatId=$chatId")
            if (chatId !in _blockedChannels.value) toggleBlockChannel(chatId)
        } else {
            android.util.Log.d("FeedViewModel", "发现页软拒绝（30 天）: chatId=$chatId dismiss=${next.dismissCount}")
        }
    }
}

/**
 * 主导来源类型 → 来源臂名（discovery_arms.json 的键口径，DISCOVER-UPGRADE P1-a #3）。
 * 臂粒度 = 边类型/候选源；映射本体在 DiscoveryRanker.armNameOf（雷达异步并入路径
 * 与收/弃记账共用此口径）；R183 双宝藏池的臂名（deep/search/dual）在
 * refreshDiscoveryImpl 内直接给定（见发布段）。
 */
internal fun FeedViewModel.discoveryArmName(dominantType: String): String = DiscoveryRanker.armNameOf(dominantType)

/**
 * 出榜候选的 SupergroupFullInfo 懒加载（P1-b #8）：候选出榜后才调，
 * 每候选每会话至多一次（[discoveryFullInfoRequested] 防重入）、每刷新最多
 * [DISCOVERY_FULLINFO_MAX_PER_REFRESH] 个（预算闸）。串行逐个取（不并发抢带宽），
 * memberCount 进 [discoveryMemberCounts]（冷门宝藏判定与卡片成员芯片的数据源；
 * 图谱节点落盘由网关侧顺手完成）；linkedChatId/communityId 本轮只记日志。
 * 任何失败静默（丰富是增量收益，绝不影响已出榜列表）。
 */
internal fun FeedViewModel.enrichDiscoveryProfiles(chatIds: List<Long>) {
    val real = gateway as? TdLibGateway ?: return
    val pending = chatIds.filter { discoveryFullInfoRequested.add(it) }
        .take(DISCOVERY_FULLINFO_MAX_PER_REFRESH)
    if (pending.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
        for (id in pending) {
            val profile = runCatching { real.fetchSupergroupProfile(id) }.getOrNull() ?: continue
            if (profile.memberCount > 0) {
                discoveryMemberCounts[id] = profile.memberCount
                // 发现页升级：成员数补抓到位即增量合并进视图流（卡片标题行即时补上，不等下轮刷新）
                _discoveryMemberCountView.update { it + (id to profile.memberCount) }
            }
            if (profile.linkedChatId != 0L || profile.communityId != 0L) {
                android.util.Log.d(
                    "FeedViewModel",
                    "发现档案: chatId=$id 成员=${profile.memberCount} 讨论群=${profile.linkedChatId} community=${profile.communityId}",
                )
            }
        }
    }
}

// ---------------- 发现页升级·"刚冒出来的"横滑区 ----------------

/**
 * 把 GoldmineHooks 新发现快照映射成横滑小卡（IO 协程内调用，refreshDiscovery 尾段）：
 *  - chatId 未解析的条目上游已滤掉；这里过**标题关**——图谱节点标题/进程内缓存命中
 *    直接用，都没有的小预算懒解析（[DISCOVERY_FRESH_TITLE_RESOLVE_MAX] 次/刷新，
 *    与 4.6 裸 ID 候选同一 fetchVideos 通道口径），解析成功回写图谱落盘；
 *  - 标题不可得/已加入（joinedNow）的条目本轮跳过（宁缺毋丑）；
 *  - 出卡上限 [DISCOVERY_FRESH_RAIL_MAX]，快照本身已按最近再被搜出降序。
 * Mock 网关 freshInfos 恒空 → 直接空表，整区隐藏。
 *
 * R57 修复②（"发现页与首页首屏重复"根因之一）：本区旧实现只过 joinedNow 弱集合
 * （listChannels 前缀 ∪ 订阅集 ∪ 深索引已爬集）——前缀之外/深索引未爬到的已加入
 * 频道会漏进"刚冒出来的"，而其内容正是首页聚合首屏的来源，两屏因此撞脸。
 * 这里补与候选卡 5.5 同一道权威"已加入"终点校验（isJoinedChat：本地身份直读
 * 为主、缓存命中零请求；已加入/不确定一律剔除）。
 */
internal suspend fun FeedViewModel.buildFreshRailCards(
    real: TdLibGateway?,
    freshInfos: List<GoldmineHooks.FreshChannelInfo>,
    joinedNow: Set<Long>,
): List<DiscoveryFreshCard> {
    if (freshInfos.isEmpty()) return emptyList()
    var budget = DISCOVERY_FRESH_TITLE_RESOLVE_MAX
    val out = ArrayList<DiscoveryFreshCard>(minOf(freshInfos.size, DISCOVERY_FRESH_RAIL_MAX))
    for (info in freshInfos) {
        if (out.size >= DISCOVERY_FRESH_RAIL_MAX) break
        val id = info.chatId
        if (id in joinedNow) continue // 已加入不是"发现"
        // R57：权威终点校验兜底弱集合漏网（与候选卡 5.5 同闸；读失败按"不确定"剔除）
        if (real != null && runCatching { real.isJoinedChat(id) }.getOrDefault(true)) continue
        var title = real?.let { runCatching { it.discoveryGraph.nodeTitle(id) }.getOrNull() }
            ?: discoveryTitleCache[id]
        if (title.isNullOrBlank() && budget > 0) {
            budget--
            val fetched = runCatching {
                gateway.fetchVideos(FeedRequest(channelId = id), null, 1)
                    .items.firstOrNull()?.channelTitle?.takeIf { it.isNotBlank() }
            }.getOrNull()
            if (fetched != null) {
                title = fetched
                discoveryTitleCache[id] = fetched
                // 回写图谱节点标题（落盘持久化：下次刷新零请求，与 4.6 同口径）
                if (real != null) runCatching { real.discoveryGraph.recordNodeTitle(id, fetched) }
            }
        }
        val finalTitle = title?.takeIf { it.isNotBlank() } ?: continue // 标题不可得本轮跳过
        out += DiscoveryFreshCard(ChannelBrief(id, finalTitle, null), info.firstTs, info.lastTs)
    }
    return out
}

/**
 * 拉一张候选卡的预览（IO 协程；卡片入组合时由 UI 触发，幂等防重入）：
 * 先拉一页最近条目，再逐条下载真缩略图（全局限流闸内执行）；
 * 无 coverFileId/下载失败的条目留 null（UI 渐变色块承接，绝不回落糊图）。
 *
 * @param priority v4 首屏优先档：true = 可见前 3 张候选卡/盲盒抽中卡，
 *     下载跳过普通档二级闸、直接占全局闸的预留车道（插队到队列头部效果）；
 *     false = 普通档照旧两闸排队。总并发恒 ≤ 全局闸，限流纪律不变
 */
internal fun FeedViewModel.loadDiscoveryPreviewImpl(chatId: Long, priority: Boolean = false) {
    if (_discoveryPreviews.value.containsKey(chatId)) return
    if (!discoveryPreviewRequested.add(chatId)) return
    if (priority) {
        android.util.Log.d("FeedViewModel", "loadDiscoveryPreview: 优先通道 chatId=$chatId")
    }
    viewModelScope.launch(Dispatchers.IO) {
        val page = runCatching {
            gateway.fetchVideos(FeedRequest(channelId = chatId), null, DISCOVERY_PREVIEW_COUNT)
        }.getOrNull()
        val items = page?.items.orEmpty().take(DISCOVERY_PREVIEW_COUNT)
        // R133 候选卡样本基因行（#19）：预览页本来就带回了条目（类型/时长零额外成本），
        // 顺手聚一行"近期主打X · 平均 N 分钟"发布给候选卡
        val sampleLine = runCatching { buildSampleGenomeLine(items) }.getOrNull()
        if (!sampleLine.isNullOrEmpty()) {
            _discoveryGenomeLines.update { it + (chatId to sampleLine) }
        }
        val lib = gateway as? TdLibGateway
        val thumbs = coroutineScope {
            items.map { item ->
                async(Dispatchers.IO) {
                    if (lib == null || item.coverFileId < 0) return@async null
                    // 两档排队：优先卡只占全局闸（预留车道恒有），普通卡先过普通档
                    // 二级闸再占全局闸——总并发恒 ≤ 全局闸，首屏卡先出图
                    if (priority) {
                        discoveryPreviewDownloadSem.withPermit {
                            runCatching { lib.loadCoverBytes(item) }.getOrNull()
                        }
                    } else {
                        discoveryPreviewDownloadLowSem.withPermit {
                            discoveryPreviewDownloadSem.withPermit {
                                runCatching { lib.loadCoverBytes(item) }.getOrNull()
                            }
                        }
                    }
                }
            }.map { it.await() }
        }
        _discoveryPreviews.update {
            it + (chatId to DiscoveryPreview(totalCount = items.size, thumbs = thumbs))
        }
    }
}

// ---------------- 发现页常量（FeedViewModel 发现段配套，文件级私有） ----------------

/** 发现页：合并去重后候选展示上限（公平轮转取前 N；双宝藏全量池按曝光账轮转） */
private const val DISCOVERY_CANDIDATE_LIMIT = 50

/** 发现页：软拒绝 TTL（毫秒）——30 天内不再出现；复出后二次弃才永久拉黑（DISCOVER-DESIGN §8.2） */
internal const val DISCOVERY_SOFT_REJECT_TTL_MS = 30L * 24 * 3600 * 1000

/** 发现页名片卡：预览最近 N 条（1 张 16:9 头图铺顶 + 其余小图行；行级懒加载 + 进程内缓存） */
private const val DISCOVERY_PREVIEW_COUNT = 4

/** 发现页名片卡：真缩略图下载并发上限（全 VM 共享闸；候选卡集体入屏时防下载洪峰抢播放/Feed 带宽） */
internal const val DISCOVERY_PREVIEW_DOWNLOAD_CONCURRENCY = 4

/**
 * 发现页名片卡：优先档预留下载车道数（v4 首屏优先）——普通档二级闸容量 =
 * [DISCOVERY_PREVIEW_DOWNLOAD_CONCURRENCY] − 本值，优先卡（可见前 3 张/盲盒抽中）
 * 恒有预留车道可插队；总并发不变，限流纪律不破
 */
internal const val DISCOVERY_PREVIEW_PRIORITY_LANES = 1

/**
 * 发现页：裸 ID 占位标题懒解析的每次刷新预算（fetchVideos 一页取 channelTitle）。
 * 宝藏二 hintTitle 缺失的候选只有 chatId 没有标题；10 次/刷新防 flood，解析成功
 * 回写图谱落盘后后续刷新零请求，解析不到/超预算的候选本轮换掉不出榜。
 */
private const val DISCOVERY_TITLE_RESOLVE_MAX = 10

/** 发现页：权威"已加入"终点校验的并发档（出榜上限 50 即验证上限；缓存命中零请求） */
internal const val DISCOVERY_JOIN_CHECK_CONCURRENCY = 5

/** 发现页：观看序列共现的会话窗（毫秒）——相邻观看间隔超过 30 分钟即视为两个独立上下文，不建边（P1-b #7；FeedWatchPart 建边侧消费） */
internal const val CO_WATCH_WINDOW_MS = 30L * 60_000L

/** 发现页：GetSupergroupFullInfo 懒加载的每次刷新预算（每候选 2 次请求；另每候选每会话至多一次，P1-b #8） */
private const val DISCOVERY_FULLINFO_MAX_PER_REFRESH = 8

// ---------------- P2 金矿常量（群搜来源词——R211 词源换血：答案库关键词直出，旧搜索历史查询口径删除） ----------------

// ---------------- P4-A 金矿常量（热词榜 + 新片雷达，refreshDiscovery/DiscoverScreen 配套） ----------------

/** 发现页"群友在搜"热词榜：统计窗口（近 24h 出现次数降序；留存窗在 GoldmineHooks 侧为 48h） */
private const val DISCOVERY_HOT_WORD_WINDOW_MS = 24L * 3600 * 1000

/** 发现页"群友在搜"热词榜：出榜条数上限（横滑 LazyRow 展示，取头部 12 条） */
private const val DISCOVERY_HOT_WORD_LIMIT = 12

/** 发现页统计胶囊：热词事件全量求和的拉取上限（覆盖 24h 窗内活跃头部词；榜单仍只取前 [DISCOVERY_HOT_WORD_LIMIT]） */
private const val DISCOVERY_HOT_WORD_STATS_LIMIT = 200

/** 发现页升级·"刚冒出来的"横滑区：出卡上限（横滑小卡，超出截断；快照按最近再被搜出降序） */
private const val DISCOVERY_FRESH_RAIL_MAX = 12

/** 发现页升级·"刚冒出来的"横滑区：标题懒解析每次刷新预算（与 4.6 裸 ID 候选同一 fetchVideos 通道口径） */
private const val DISCOVERY_FRESH_TITLE_RESOLVE_MAX = 5
