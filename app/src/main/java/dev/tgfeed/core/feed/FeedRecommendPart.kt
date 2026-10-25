package dev.tgfeed.core.feed

import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import kotlin.math.ceil

/**
 * R298 真推荐引擎 · 召回装配层（design-recommendation.md §3 四路召回的 VM 侧落地）：
 *
 *  - **R2 tag 兴趣路（核心）**：兴趣 tag 轴 top-[INTEREST_TOP_TAGS] 逐页轮转
 *    （[INTEREST_TAGS_PER_PAGE] 个/页）→ DeepSearchIndex.tagItemsByRelevance
 *    （R236 倒排毫秒级 + **R313 词频闸时间分代**——宽 tag 候选集跨年代，
 *    老内容进得了兴趣候选集（R307b 实锤的近端截断根治）+ R288 BM25 相关度序）
 *    → 过采样 → 与正页同一根过滤链
 *    （pass 谓词 + 已看双轨 + key 判重）→ 预算条数（⌈拉页×[INTEREST_RECALL_RATIO]⌉
 *    钳 [[INTEREST_RECALL_MIN],[INTEREST_RECALL_MAX]]）。**跨频道捞「你爱的话题」
 *    的条目——G1「召回层零兴趣概念」的根治**。
 *  - **R3 互链图谱路**：C 轴 top 频道（p2ChannelWeights 现有出口）→ DiscoveryGraph
 *    .relatedByEdgeType（link/forward_src/co_watch 三路并集）→ 金矿快照验货
 *    （tier≥0 系统验货豁免，否则 recent7≥[GRAPH_GOLD_MIN_RECENT7]；广告话术/模板
 *    风暴/农场闸不过的一律不进）→ 深索引 channelItemsPage 近端直出 → 同一根过滤链。
 *    **工程修正（对施工图 §3 R3 行）**：施工图写「频道页拉取少量条目（有界配额）」
 *    走网络；落地改深索引本地直出——图谱边本就产自爬取期，邻居频道几乎全在已索引
 *    宇宙内，本地直出毫秒级、零网络、零配额风险（§九纪律）；未索引邻居不追网络
 *    （挂账：R3 网络补货增强，确有需求再走 GetChatHistory 有界配额）。
 *  - **R1 现状路/R4 探索路不在本文件**：R1=网关聚合拉取（不动），R4=既有探索位
 *    （placeWithDiversity 10%）+历史回溯注入+新面孔保底（不动）。
 *  - **候选池汇合去重**：注入条目进 loadMore 的 merged 与正页条目合流——
 *    known keys 判重 + pass 谓词 + rankWeightedPage ① dedupeByFile + 跨页
 *    foldReposts 收尾闸四重既有一道不少，零新判重件（类问题收敛）。
 *  - 生效面：仅聚合源（Aggregate/WeightedAggregate）；单频道源/本地清单源/搜索源
 *    不注入（语义明确列表不污染，与 sampleHistoryBackfill 同边界）。
 *  - 解释（推荐→星图单向）：注入条目 copy(recommendReason=…)，UI chips 行出
 *    「为什么推这条」；无命中轴的条目不注入——不编理由（红线）。
 *
 * 冷启动（设计 §6，零退化）：三轴全空 → topInterestTags 空 → 两路召回静默空表
 * （行为与现状逐字节一致）；订阅/收藏恢复后 [maybeSeedTagInterestPrior] 灌先验，
 * 零行为也有画像。
 */

// ---------------- R2：tag 兴趣召回路 ----------------

/**
 * 按兴趣 tag 轴从深索引召回一页兴趣条目（IO 线程调用；全程本地 SQL 零网络）。
 * [pageSize] = 本轮拉页条数（预算比例基数）；[seenIds] 与回溯注入同口径
 * （null=已看排除关闭）。索引未建/轴空/过滤后无货 = 空表直通。
 */
internal fun FeedViewModel.recallByInterest(
    tab: Int,
    excludeKeys: Set<String>,
    pass: (VideoItem) -> Boolean,
    seenIds: Pair<Set<String>, Set<String>>?,
    pageSize: Int,
): List<VideoItem> {
    if (!deepIndex.hasIndex()) return emptyList()
    val top = tagInterestLedger.topInterestTags(INTEREST_TOP_TAGS)
    if (top.isEmpty()) return emptyList()
    val want = (pageSize * INTEREST_RECALL_RATIO).toInt()
        .coerceIn(INTEREST_RECALL_MIN, INTEREST_RECALL_MAX)
    // 轮转：本页消费的 tag 窗口（页游标模 top.size 推进；账本更新后 size 变化自动取模收敛）
    val offset = interestTagRotation % top.size
    interestTagRotation = (interestTagRotation + INTEREST_TAGS_PER_PAGE) % top.size
    val perTagWant = ceil(want.toDouble() / INTEREST_TAGS_PER_PAGE).toInt().coerceAtLeast(1)
    // 兴趣 tag 数不足步长时窗口折返重复取同 tag——本页内 tag 去重 + 注入 key 去重双保险
    val picked = (0 until INTEREST_TAGS_PER_PAGE).map { top[(offset + it) % top.size] }.distinctBy { it.first }
    val out = ArrayList<VideoItem>(want)
    val injected = HashSet<String>()
    for ((foldKey, display) in picked) {
        if (out.size >= want) break
        val recalled = runCatching {
            deepIndex.tagItemsByRelevance(
                foldKey,
                perTagWant * INTEREST_RECALL_OVERSAMPLE,
            )
        }.getOrDefault(emptyList())
        var took = 0
        for (item in recalled) {
            if (out.size >= want || took >= perTagWant) break
            if (item.key in excludeKeys || item.key in injected) continue
            // R337：已看双轨判定收口 SeenDedupPolicy.isSeen（与正页/回溯/网关同一把尺）
            if (seenIds != null && SeenDedupPolicy.isSeen(item, seenIds.first, seenIds.second)) continue
            if (!pass(item)) continue
            // 命中解释：fold 键与账本案头 display 不一致时（同 fold 多原形）以账本为准
            out += item.copy(recommendReason = "常看 #$display")
            injected += item.key
            took++
        }
    }
    if (out.isNotEmpty()) {
        android.util.Log.i(
            "FeedViewModel",
            "R298 兴趣召回：本页注入 ${out.size} 条（预算 $want，轮转位 $offset/${top.size}）",
        )
    }
    return out
}

// ---------------- R3：互链图谱召回路 ----------------

/** R3 金矿验货（纯函数，单测锚）：tier≥0（系统验货徽章）豁免新帖闸；广告/风暴/农场一律拒 */
internal fun graphGoldPass(g: DiscoveryGoldmine.ChannelGold): Boolean {
    if (g.farm) return false
    if (g.adRate >= AdPatternFilter.AD_REJECT_RATE) return false
    if (g.stormRate >= AdPatternFilter.STORM_REJECT_RATE) return false
    return g.tier >= 0 || g.recent7 >= GRAPH_GOLD_MIN_RECENT7
}

/**
 * 按互链图谱从「你爱的频道指向的地方」召回一页同好条目（IO 线程；全本地零网络）。
 * 金矿快照不在场（未建/未刷）= 空表静默跳过（绝不从本路径触发重建——金矿重建归
 * 发现页/预热协调器，R223b「星图/推荐只读金矿快照」先例）。
 */
internal fun FeedViewModel.recallByGraph(
    tab: Int,
    excludeKeys: Set<String>,
    pass: (VideoItem) -> Boolean,
    seenIds: Pair<Set<String>, Set<String>>?,
    pageSize: Int,
): List<VideoItem> {
    if (!deepIndex.hasIndex()) return emptyList()
    val gw = gateway as? TdLibGateway ?: return emptyList()
    val goldById = discoveryGoldSnapshot?.byId() ?: return emptyList()
    // 引用方集 = C 轴 top 频道（双轨融合统一出口现成）
    val topChannels = p2ChannelWeights().entries
        .sortedByDescending { it.value }
        .take(GRAPH_SOURCE_TOP_CHANNELS)
        .mapTo(HashSet()) { it.key }
    if (topChannels.isEmpty()) return emptyList()
    val subs = _subscriptions.value.mapTo(HashSet()) { it.id }
    // 三路边并集（Σ count 降序由图谱返回；并集保序去重）
    val neighbors = LinkedHashSet<Long>()
    for (type in listOf(
        DiscoveryGraph.EDGE_LINK,
        DiscoveryGraph.EDGE_FORWARD_SRC,
        DiscoveryGraph.EDGE_CO_WATCH,
    )) {
        neighbors += runCatching {
            gw.discoveryGraph.relatedByEdgeType(topChannels, type, GRAPH_NEIGHBOR_LIMIT)
        }.getOrDefault(emptyList())
    }
    if (neighbors.isEmpty()) return emptyList()
    val want = (pageSize * GRAPH_RECALL_RATIO).toInt()
        .coerceIn(GRAPH_RECALL_MIN, GRAPH_RECALL_MAX)
    val out = ArrayList<VideoItem>(want)
    for (id in neighbors) {
        if (out.size >= want) break
        if (id in topChannels || id in subs) continue // 订阅内=C 轴已覆盖，R3 只找订阅外同好
        val gold = goldById[id] ?: continue // 未索引邻居：不追网络（挂账口径见文件头）
        if (!graphGoldPass(gold)) continue
        val items = runCatching {
            deepIndex.channelItemsPage(id, GRAPH_ITEMS_PER_NEIGHBOR, 0)
        }.getOrDefault(emptyList())
        for (item in items) {
            if (out.size >= want) break
            if (item.key in excludeKeys) continue
            // R337：已看双轨判定收口 SeenDedupPolicy.isSeen（与正页/回溯/网关同一把尺）
            if (seenIds != null && SeenDedupPolicy.isSeen(item, seenIds.first, seenIds.second)) continue
            if (!pass(item)) continue
            out += item.copy(recommendReason = "同好频道在看")
        }
    }
    if (out.isNotEmpty()) {
        android.util.Log.i(
            "FeedViewModel",
            "R298 图谱召回：本页注入 ${out.size} 条（预算 $want，邻居 ${neighbors.size} 个）",
        )
    }
    return out
}

// ---------------- 冷启动先验灌注（设计 §6：订阅即先验，零行为也有画像） ----------------

/**
 * 灌 tag 轴先验（IO 协程调用；VM 订阅/收藏恢复完成后触发一次，之后由内容哈希守卫
 * 幂等——原料集没变=零计算零写盘）：
 *  - 订阅频道的深索引 topTags（每频道 top 5，[TagInterestLedger.PRIOR_WEIGHT] 档）——
 *    「订阅是用户亲手写的兴趣声明」；
 *  - 收藏夹/稍后再看条目的 caption #标签（CaptionParser 现提，低档同额）；
 *  - 点赞条目的 tag（deepIndex.tagsOfKeys 反查，低档同额）；
 * 全部 fold 归一，先验分值低档（设计口径 0.3 档），观测段积累后自然盖过。
 * 深索引未建时仅 caption 来源可灌（订阅 topTags/点赞反查跳过）——不建库不开库。
 */
internal fun FeedViewModel.maybeSeedTagInterestPrior() {
    val subs = _subscriptions.value
    val favs = favoriteItems
    val later = watchLaterItems
    val likes = _likes.value
    if (subs.isEmpty() && favs.isEmpty() && later.isEmpty() && likes.isEmpty()) return
    // 内容哈希守卫：四路原料集任一变化才重灌（集合 hashCode 稳定同序无关）
    val contentKey = listOf(
        subs.map { it.id }.toSet().hashCode(),
        favs.map { it.key }.toSet().hashCode(),
        later.map { it.key }.toSet().hashCode(),
        likes.hashCode(),
    ).joinToString(":")

    val prior = HashMap<String, Pair<Double, String>>()
    fun put(foldKey: String, display: String, score: Double) {
        if (foldKey.isEmpty()) return
        val cur = prior[foldKey]
        // 多来源同 tag 小幅叠加上限 3×低档分（仍是低档：观测一笔就盖过）
        val next = ((cur?.first ?: 0.0) + score).coerceAtMost(TagInterestLedger.PRIOR_WEIGHT * 3)
        prior[foldKey] = next to (cur?.second ?: display)
    }
    val w = TagInterestLedger.PRIOR_WEIGHT
    if (deepIndex.hasIndex()) {
        for (s in subs) {
            val tags = runCatching { deepIndex.topTags(s.id, PRIOR_TAGS_PER_CHANNEL) }
                .getOrDefault(emptyList())
            for ((display, _) in tags) {
                put(dev.tgfeed.core.td.SearchTextKit.fold(display), display, w)
            }
        }
        // 点赞条目 tag 反查（key 主键点查毫秒级；未解析条目缺席不补）
        if (likes.isNotEmpty()) {
            val likedTags = runCatching { deepIndex.tagsOfKeys(likes) }.getOrDefault(emptyMap())
            for ((_, tags) in likedTags) {
                for (t in tags) put(dev.tgfeed.core.td.SearchTextKit.fold(t), t, w)
            }
        }
    }
    // 收藏/稍后再看 caption #标签（本地清单自带 caption，无需 DB）
    for (item in favs + later) {
        if (item.caption.isBlank()) continue
        for (t in CaptionParser.extractHashtags(item.caption)) {
            put(dev.tgfeed.core.td.SearchTextKit.fold(t), t, w)
        }
    }
    tagInterestLedger.seedPrior(prior, contentKey)
}

/** 冷启动先验：每个订阅频道灌 top N 个 tag（N 小防订阅大户的先验噪声） */
private const val PRIOR_TAGS_PER_CHANNEL = 5
