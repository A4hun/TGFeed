package dev.tgfeed.core.feed

import dev.tgfeed.core.td.ChannelBrief
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 发现候选打分器（DISCOVER-DESIGN §2 打分公式 + §3 三桶配额的 P0 落地）。
 *
 * **语义边界（防回退）**：发现 = 探索"**未加入**"的新频道。已加入频道集合取三集合并
 * （gateway.listChannels ∪ 订阅集 ∪ 深度索引已爬频道集——GetChats 只回已加载前缀非全量，
 * 索引集是尽力而为的最严口径），由 FeedViewModel.refreshDiscovery 在候选合并后、
 * 打分前硬过滤——本打分器假定输入全部是未加入候选，不做、也不应做"已加入降权"
 * 之类的温和处理（给已加入频道排序是混排 FeedSource 的职责，不是发现的）。
 *
 * 对候选 c，入边集合 E(c) = {(src, type, count, lastTs)}：
 * ```
 * 关系强度 R(c) = Σ_{e∈E(c)} W(src_e)·tw(type_e)·log1p(count_e)
 *   W(src) = 引用方口味权重（decayedChannelWeights 混排画像复用，一处维护三处消费）；
 *            src 无画像取 [EXPLORE_WEIGHT]=0.6；src=0（search_hit 等无引用方边）取 0.3
 *   tw     = forward_dst 2.0 | forward_src 1.5 | group_search 1.2 | comment 1.2 | link 1.0
 *            | search_hit 0.8 | co_watch 0.3
 *           （co_watch P1-b 观看序列共现：无向弱边，仅作既有候选的佐证加分；
 *            group_search P2 金矿：资源搜索群 bot 应答指向的需求侧边，强意图证据）
 *   枢纽降权（P1-b，BRAINSTORM-GRAPH §1.3 建边侧打标）：EdgeEvidence.hubFrom=true 的
 *            forward_src 边 tw ×[HUB_PENALTY]（搬运号是"路标"不是"目的地"，防霸榜）
 *   log1p  = 计数对数衰减，防单频道刷屏灌票
 *   AA 加项（P1-c Adamic-Adar 共同邻居）：R += [AA_GAIN]·Σ 1/ln(outDeg(z)+1)，
 *            z = 订阅集内指向 c 的引用方（按 from 去重），outDeg(z) = z 指向的不同
 *            目标数（DiscoveryGraph.outDegreesOf 供给）；指向面越广的订阅票越稀
 *           （搬运号什么都转 ≈ 公共邻居无信息量），小系数并进 R，不盖过 W·tw·log1p 主项
 * 新鲜度 F(c) = 0.5 + 0.5·exp(-(now - maxLastTs)/τ)，τ = 14 天；
 *              无图边候选（纯相似/雷达）按"刚发现"取满格 1.0
 * 活跃度 A(c) = 0.3 + 0.7·clamp(posts14d/8, 0, 1)
 *              P0 无日历数据退化为恒 [ACTIVITY_DEFAULT]=0.65
 *             （成员数已知时用 Q 代替——P0 暂无成员数供给通道，此分支保留给 P1）
 * 内容匹配 T(c) = 1 + 2·tasteOverlap + 5·jaccard
 *   tasteOverlap = 候选标题命中口味词表的比例（0..1，复用 TasteRadar 词表与 tokenize）
 *   jaccard      = 与订阅集的指纹重叠（P2 指纹库供给，P0 恒 0）
 * 质量 Q(c) = clamp(memberCount/10000, 0.2, 1.0)；成员数未知（P0 常态）取中性档 0.5
 * 相似加成 S(c) = 共指次数 ≥ 2 时 +0.5
 *
 * score(c) = R·F·(0.6+0.4A)·T·(0.8+0.2Q) + S
 * score'   = score · 0.85^impressions    （曝光疲劳：连续曝光未转化逐次降权）
 * score''  = score' + [FRESH_FLOAT_BONUS]（v4 新货上浮：7 天内首见 / 新货集合命中的
 *            候选吃固定加分——加性有界，把新货抬进前列但不钉死榜首：上浮不霸榜）
 * ```
 *
 * 无边候选（纯相似/纯雷达）的关系强度用"虚拟边"兜底，保证可排序、不致全员零分：
 *  - 相似：R_v = 指向它的订阅种子平均权重 × 1.0 × log1p(共指次数)
 *   （官方相似推荐相当于种子频道的背书——种子本就是高权重已订阅频道）；
 *  - 雷达：R_v = [EXPLORE_WEIGHT] × 1.0 × log1p(1)（口味词巡逻命中，无引用方背书）。
 *
 * 候选下限 [MIN_SCORE]=1.0 只约束**纯图谱**候选——压掉"只被一个陌生频道提过一次"的
 * 互推噪声（§2.2 口径）；带相似/雷达/广场/群搜来源的候选豁免（上游已被共指过滤/口味配额闸/
 * 广场信任层/收割侧广告黑名单+归属过滤筛过）。
 *
 * P1-d 广场臂（DISCOVER-UPGRADE 广场全局信号接入）：fetchPlaza 快照的 channelRecommends
 * 作为新来源 [SOURCE_PLAZA] 进候选池——全站推荐人数经 [TW_PLAZA]·log1p 计进 R
 * （有图边时叠加佐证，无边时充当虚拟边兜底）；纯广场候选主导类型归 [DOMINANT_PLAZA]，
 * Thompson 臂归因与收/弃记账共用 "plaza" 键；快照 channelBlocks 做软惩罚——
 * 被 ≥[PLAZA_BLOCK_MIN] 人标记避雷的候选 Q 降到地板 [QUALITY_FLOOR]（不硬过滤，
 * 过滤权仍在本地屏蔽表）。
 * 数据稀缺档（P1-c，BRAINSTORM-ALGO §1.5 反探索修正）：过线候选 < limit 时下探至
 * [MIN_SCORE_SCARCE]=0.8 重滤——否则候选越稀缺、桶越被利用分占满，与冷启动该多探索
 * 的方向恰好相反。
 *
 * 输出层配额（P1-c，BRAINSTORM-ALGO §2.3/§2.4：替换 P0 静态三桶 60/25/15 与硬上限）：
 *  - 利用桶 60%：score 降序打底，MMR 桶内重排选取（对味优先，转化基本盘不动）；
 *  - 探索域 40%（P0 探索 25% + 野卡 15% 合并）：Thompson 采样按来源臂分配——
 *    臂 = 主导来源粒度（link/fwd_src/fwd_dst/comment/search_hit/co_watch/similar/radar，
 *    无法归因的残余归 "other"）；每轮 rank 各臂采 θ ~ Beta([BETA_PRIOR]+收, [BETA_PRIOR]+弃)
 *   （臂统计由构造参数 armStats 注入，存原始计数、均匀先验在本类叠加；表缺失/损坏恒有
 *    Beta(1,1) 兜底——冷启动天然最大探索，无需任何"冷启动分支"）；
 *    预算按 θ 比例最大余数法取整分配，臂内按 score+新节点加分（firstSeen < 7 天
 *    +[NEW_NODE_BONUS]）排序后 MMR 选取；配额超臂内候选数的余额按 θ 降序让给其他臂
 *   （沿用"某桶候选不足配额自然让出"口径）；"other" 臂出榜落野卡标签——野卡桶语义
 *    降级为归因兜底桶（Bandit 底账失效时均匀先验仍等价均匀分配，日种子随机不再使用）；
 *  - MMR 多样性（λ=[MMR_LAMBDA]，替换 P0"每源 40% 硬上限 + 探索桶类型 cap"的断崖
 *    截断，§1.6）：迭代选取 argmax_c λ·rel(c) − (1−λ)·max_{s∈已选} sim(c,s)，
 *    rel = 桶排序分池内 min-max 归一，sim = max(标题 token Jaccard,
 *    同主导类型 [MMR_SIM_SAME_TYPE], 相似种子重叠 [MMR_SIM_SEED_OVERLAP])。
 *
 * 证据文案（§4.2）随打分一并产出（最多 [EVIDENCE_MAX_LINES] 条，多源命中自然叠加）：
 * 「你的 N 个订阅频道引用过它」/「你的 N 个订阅频道转发过它的内容」/
 * 「群友都在找这个」（P2 金矿：group_search 边）/
 * 「搜「X」的人都去了这些频道」（P2 金矿：答案库命中用户搜索词）/
 * 「和「X」等 N 个频道相似」/「雷达巡逻命中的新频道」/「你的搜索命中过它」。
 *
 * 纯函数式实现：全部输入构造时注入（Thompson 采样由注入种子驱动，同输入同输出），
 * 无 IO、无副作用、无可变共享状态，可单测。
 */
class DiscoveryRanker(
    private val nowMs: Long,
    /** 引用方权重表（decayedChannelWeights 产物：chatId → 时间衰减口味权重） */
    private val sourceWeights: Map<Long, Double>,
    /** 口味词表（TasteRadar 的 caption 内容词 + 提取的口味词，一词两用） */
    private val tasteWords: Set<String>,
    /** 已订阅集合（证据文案"你的 N 个订阅频道"的归属判定） */
    private val subscribedIds: Set<Long>,
    /** chatId → 标题（证据文案点名种子频道用；未知返回 null） */
    private val titleOf: (Long) -> String?,
    /** 日种子（按日变化：nowMs / 86400000；P0 野卡桶随机用，P1-c 起仅作 [sampleSeed] 缺省值） */
    private val daySeed: Long,
    /** 分词器（TasteRadar.tokenize 注入，与口味词提取同口径） */
    private val tokenize: (String) -> List<String>,
    /** 来源臂统计（P1-c Thompson 底账：臂名 → 收/弃计数；空表 = 全臂均匀先验） */
    private val armStats: Map<String, ArmStat> = emptyMap(),
    /** Thompson 采样种子（每轮刷新变化；缺省沿用 daySeed，同一天同一池分配稳定） */
    private val sampleSeed: Long = daySeed,
    /** 订阅集引用方出度表（P1-c Adamic-Adar：chatId → 指向的不同目标数；空表 = 无 AA 加项） */
    private val sourceOutDegrees: Map<Long, Int> = emptyMap(),
    /**
     * 新货集合（v4 上浮通道）：金矿 fresh 表 7 天 TTL 内的 chatId——含 EntityResolver
     * 把 "u:<name>" 用户名条目解析升级为 "c:<chatId>" 的群搜候选（解析成功即入表可见）。
     * 命中的候选在打分尾段吃 [FRESH_FLOAT_BONUS] 固定加分；图谱 7 天首见节点
     * 由 [Input.firstSeen] 自行判定，不依赖本表
     */
    private val freshIds: Set<Long> = emptySet(),
) {

    /**
     * 打分输入：一个候选频道及其全部已知证据。
     * data class 便于多源合并时 copy 叠加（图谱+相似+雷达命中同一候选）。
     */
    data class Input(
        val brief: ChannelBrief,
        /** 命中来源集合：[SOURCE_GRAPH] / [SOURCE_SIMILAR] / [SOURCE_RADAR] */
        val sources: Set<String>,
        /** 图谱入边明细（无图边候选为空表） */
        val edges: List<DiscoveryGraph.EdgeEvidence> = emptyList(),
        /** 图谱节点首见毫秒时间戳（探索桶"新节点优先"加分用）；未知 = 0 */
        val firstSeen: Long = 0L,
        /** 成员数（P1 供给质量分；P0 恒 null → Q 取中性档） */
        val memberCount: Int? = null,
        /** 指向它的订阅种子频道 id（相似证据文案点名用） */
        val similarSeedIds: Set<Long> = emptySet(),
        /** 共指次数：被多少个已查询节点（种子 + 二层展开节点）同时指向 */
        val similarCoRef: Int = 0,
        /** 历史进榜曝光次数（候选状态表；曝光疲劳 ×0.85ⁿ 的 n） */
        val impressions: Int = 0,
        /** 全站推荐人数（P1-d 广场臂：fetchPlaza 快照 channelRecommends 供给，log1p 计分；0 = 无广场信号） */
        val plazaRecommends: Int = 0,
        /** 全站避雷标记人数（P1-d：快照 channelBlocks 供给；≥[PLAZA_BLOCK_MIN] 人标记时 Q 降到地板） */
        val plazaBlocks: Int = 0,
        /** 答案库命中关键词（P2 金矿：用户自己搜过、搜索群群友也搜出结果的词；证据文案点名用，按命中相关度排序） */
        val answerKeywords: List<String> = emptyList(),
        /** 答案库累计命中次数（P2：无 group_search 图边时充当虚拟边强度；有边时不叠加，防同一次收割观测双计） */
        val answerHits: Int = 0,
    )

    /** 打分输出：最终分 + 分桶 + 来源角标 + 证据文案 + 主导来源类型 */
    data class Ranked(
        val brief: ChannelBrief,
        /** 曝光疲劳后的最终分 */
        val score: Double,
        /** 分桶：[BUCKET_EXPLOIT] / [BUCKET_EXPLORE] / [BUCKET_WILDCARD] */
        val bucket: Int,
        /** 来源角标（沿用现有口径）："图谱" / "相似" / "雷达" / "图谱+相似" 等组合 */
        val sourceTag: String,
        /** 证据文案行（最多 [EVIDENCE_MAX_LINES] 条） */
        val evidence: List<String>,
        /** 主导来源类型（图边取贡献最大的边类型；P1-c 起用于 Thompson 臂归因 [armNameOf]） */
        val dominantType: String,
    )

    /**
     * 来源臂统计（P1-c Thompson 采样底账）：hits=累计"收"数，misses=累计"弃"数。
     * 与 LocalStore.DiscoveryArmStat 同口径（存原始计数，均匀先验由采样侧叠加）——
     * 在本类重定义是保持 ranker 纯函数定位，不反向依赖 store 层模型。
     */
    data class ArmStat(val hits: Double, val misses: Double)

    /**
     * 打分主入口：minScore 过滤（数据稀缺时下探 [MIN_SCORE_SCARCE] 档）→ 打分 →
     * 利用桶 60%（score 降序 + MMR）→ 探索域 40%（Thompson 采样按臂分配，臂内 MMR），
     * 探索域内按臂 θ 采样值降序拼接。桶配额按 [limit] 等比缩放（50 张时 = 30/20）；
     * 某臂候选不足时配额余额按 θ 降序让给其他臂。
     */
    fun rank(inputs: List<Input>, limit: Int = DEFAULT_LIMIT): List<Ranked> {
        if (inputs.isEmpty() || limit <= 0) return emptyList()
        // 广告频道黑名单硬剔除（P2 伪装资源频道防守，AdChannelClassifier 收割侧打标）：
        // 先于一切打分与豁免——黑名单频道分数再高也不进发现页，chatId/用户名双身份任中即剔。
        // 静态快照查询零注入（ranker 纯函数定位不变）；快照为空（收割未跑过）时无剔除。
        val clean = inputs.filter { !AdChannelClassifier.isBlockedRuntime(it.brief.id, it.brief.username) }
        // 过滤 + 打分：纯图谱候选受 minScore 约束（压互推噪声），相似/雷达来源豁免；
        // 数据稀缺档（§1.5 反探索修正）：过线候选填不满桶时下探到 [MIN_SCORE_SCARCE] 重滤
        val all = clean.map { it to scoreOf(it) }
        val exempt: (Input) -> Boolean = { inp -> inp.sources.any { it != SOURCE_GRAPH } }
        var scored = all.filter { (inp, sc) -> sc >= MIN_SCORE || exempt(inp) }
        if (scored.size < limit) {
            scored = all.filter { (inp, sc) -> sc >= MIN_SCORE_SCARCE || exempt(inp) }
        }
        if (scored.isEmpty()) return emptyList()

        val exploitN = limit * BUCKET_EXPLOIT_PERCENT / 100
        // 探索域 = P0 探索桶+野卡桶合并预算（40%），Thompson 采样按来源臂分配
        val exploreBudget = (limit - exploitN).coerceAtLeast(0)
        val out = ArrayList<Ranked>(limit.coerceAtMost(scored.size))

        /** 标题 token 集缓存（MMR Jaccard 用；分词失败按空集，免重复分词） */
        val tokenCache = HashMap<Long, Set<String>>()

        fun tokensOf(inp: Input): Set<String> = tokenCache.getOrPut(inp.brief.id) {
            if (inp.brief.title.isBlank()) emptySet()
            else runCatching { tokenize(inp.brief.title) }.getOrDefault(emptyList()).toSet()
        }

        /** 探索域臂内排序分：score + 新货加分（与 [isFresh] 同口径：7 天首见 / 新货集合） */
        fun exploreScore(p: Pair<Input, Double>): Double =
            p.second + if (isFresh(p.first)) NEW_NODE_BONUS else 0.0

        // 1) 利用桶 60%：score 降序打底，MMR 桶内重排选取
        val pool = scored.sortedByDescending { it.second }
        val exploitPicked = mmrSelect(pool, exploitN, { it.second }, { inp -> tokensOf(inp) })
        val pickedIds = HashSet<Long>()
        for ((inp, sc) in exploitPicked) {
            out += Ranked(inp.brief, sc, BUCKET_EXPLOIT, sourceTagOf(inp), evidenceOf(inp), dominant(inp))
            pickedIds += inp.brief.id
        }
        // 2) 探索域 40%：Thompson 采样按臂分配（臂 = 主导来源粒度，[armNameOf] 口径）
        val remaining = pool.filter { it.first.brief.id !in pickedIds }
        if (exploreBudget > 0 && remaining.isNotEmpty()) {
            val rand = Random(sampleSeed)
            val byArm = remaining.groupBy { armNameOf(dominant(it.first)) }
            // 每臂采 θ ~ Beta(先验+收, 先验+弃)：数据少的臂后验方差大、θ 经常冲到前列
            // （天然探索），数据多了 θ 收敛到真实转化率（自然利用）；臂表缺失时均匀先验兜底
            val theta = byArm.keys.associateWith { arm ->
                val st = armStats[arm]
                sampleBeta(BETA_PRIOR + (st?.hits ?: 0.0), BETA_PRIOR + (st?.misses ?: 0.0), rand)
            }
            val quotas = allocateArms(theta, exploreBudget, byArm.mapValues { it.value.size })
            // 按 θ 降序拼接（本轮采样表现好的臂，其候选排在探索域前面）
            for ((arm, quota) in quotas.entries.sortedByDescending { theta[it.key] ?: 0.0 }) {
                if (quota <= 0) continue
                val armPool = (byArm[arm] ?: continue).sortedByDescending { exploreScore(it) }
                val picked = mmrSelect(armPool, quota, { p -> exploreScore(p) }, { inp -> tokensOf(inp) })
                // "other" 臂（无法归因的残余候选）落野卡标签：野卡桶语义降级为归因兜底桶
                val bucket = if (arm == ARM_OTHER) BUCKET_WILDCARD else BUCKET_EXPLORE
                for ((inp, sc) in picked) {
                    out += Ranked(inp.brief, sc, bucket, sourceTagOf(inp), evidenceOf(inp), dominant(inp))
                }
            }
        }
        return out
    }

    /** 主导来源类型：图边取对 R 贡献最大的边类型；无边候选取来源（相似/雷达/广场/答案库），兜底 "other" */
    private fun dominant(inp: Input): String =
        inp.edges.maxByOrNull { edgeContribution(it) * ln1p(it.count.coerceAtLeast(1).toDouble()) }?.type
            ?: when {
                SOURCE_SIMILAR in inp.sources -> SOURCE_SIMILAR
                SOURCE_RADAR in inp.sources -> SOURCE_RADAR
                // P1-d：纯广场候选（无图边、无相似/雷达命中）归 plaza 主导——
                // Thompson 臂归因（armNameOf → "plaza"）与收/弃记账共用此口径
                SOURCE_PLAZA in inp.sources || inp.plazaRecommends > 0 -> DOMINANT_PLAZA
                // P2 金矿：纯答案库候选（边尚未落图谱/已被淘汰）归 group_search 主导，
                // 与 group_search 边候选同臂——Thompson 配额与收/弃记账共用一条金矿臂
                inp.answerKeywords.isNotEmpty() -> GroupHarvester.EDGE_GROUP_SEARCH
                else -> "other"
            }

    /**
     * MMR 桶内重排（P1-c，BRAINSTORM-ALGO §2.4）：替换"每源 40% 硬上限/探索桶类型 cap"
     * 的断崖式截断，多样性从硬约束变平滑惩罚。
     * 迭代选取 argmax_c [MMR_LAMBDA]·rel(c) − (1−λ)·max_{s∈已选} sim(c,s)：
     *  - rel = [base] 分在本池内 min-max 归一（与 sim 同量纲，λ 的权衡语义才成立；
     *    池内同分时 rel 全 1，退化为纯多样性选取，入选序保持入池序稳定）；
     *  - sim(c,s) = max(标题 token Jaccard, 同主导类型 [MMR_SIM_SAME_TYPE],
     *    相似种子重叠 [MMR_SIM_SEED_OVERLAP])，恒落 [0,1]。
     * O(n²)，50 张卡微秒级。
     */
    private fun mmrSelect(
        pool: List<Pair<Input, Double>>,
        quota: Int,
        base: (Pair<Input, Double>) -> Double,
        tokensOf: (Input) -> Set<String>,
    ): List<Pair<Input, Double>> {
        if (quota <= 0 || pool.isEmpty()) return emptyList()
        val baseVals = pool.map { base(it) }
        val lo = baseVals.minOrNull() ?: 0.0
        val hi = baseVals.maxOrNull() ?: 0.0
        val span = hi - lo
        val rel = HashMap<Input, Double>(pool.size)
        pool.forEachIndexed { i, (inp, _) -> rel[inp] = if (span > 0.0) (baseVals[i] - lo) / span else 1.0 }

        fun sim(a: Input, b: Input): Double {
            var s = if (dominant(a) == dominant(b)) MMR_SIM_SAME_TYPE else 0.0
            val ta = tokensOf(a)
            val tb = tokensOf(b)
            if (ta.isNotEmpty() && tb.isNotEmpty()) {
                val inter = ta.count { it in tb }
                if (inter > 0) {
                    val j = inter.toDouble() / (ta.size + tb.size - inter)
                    if (j > s) s = j
                }
            }
            if (s < MMR_SIM_SEED_OVERLAP && a.similarSeedIds.isNotEmpty() && b.similarSeedIds.isNotEmpty() &&
                a.similarSeedIds.any { it in b.similarSeedIds }
            ) s = MMR_SIM_SEED_OVERLAP
            return s
        }

        val remaining = pool.toMutableList()
        val picked = ArrayList<Pair<Input, Double>>(quota)
        while (picked.size < quota && remaining.isNotEmpty()) {
            var bestIdx = 0
            var bestVal = Double.NEGATIVE_INFINITY
            for (i in remaining.indices) {
                val cand = remaining[i]
                var maxSim = 0.0
                for (s in picked) {
                    val v = sim(cand.first, s.first)
                    if (v > maxSim) maxSim = v
                }
                val mmr = MMR_LAMBDA * (rel[cand.first] ?: 0.0) - (1.0 - MMR_LAMBDA) * maxSim
                if (mmr > bestVal) {
                    bestVal = mmr
                    bestIdx = i
                }
            }
            picked += remaining.removeAt(bestIdx)
        }
        return picked
    }

    /**
     * 探索预算的臂间分配（P1-c Thompson 配套）：按 θ 比例最大余数法取整；
     * 配额超出臂内候选数的余额回收后，按 θ 降序逐张让给还有候选的臂
     * （沿用旧口径"候选不足配额自然让出"），直到预算用尽或全员耗尽。
     */
    private fun allocateArms(theta: Map<String, Double>, budget: Int, avail: Map<String, Int>): Map<String, Int> {
        if (budget <= 0 || theta.isEmpty()) return emptyMap()
        val total = theta.values.sum()
        // θ 全零的极端数值情形退化为均分（Beta 采样理论上不产出全零，防御兜底）
        val share = theta.mapValues { if (total > 0.0) it.value / total else 1.0 / theta.size }
        // LinkedHashMap 保序：θ 并列时拼接顺序稳定（同输入同输出，可单测口径）
        val out = LinkedHashMap<String, Int>()
        var assigned = 0
        for ((arm, s) in share) {
            val q = (s * budget).toInt()
            out[arm] = q
            assigned += q
        }
        // 最大余数法补齐取整零头（余数大者优先；预算 < 臂数时只有部分臂拿到 1 张）
        var left = budget - assigned
        for ((arm, _) in share.entries.sortedByDescending { it.value * budget - (it.value * budget).toInt() }) {
            if (left <= 0) break
            out[arm] = (out[arm] ?: 0) + 1
            left--
        }
        // 容量收口：配额 > 臂内候选数的余额回收进 left
        for ((arm, q) in out) {
            val cap = avail[arm] ?: 0
            if (q > cap) {
                left += q - cap
                out[arm] = cap
            }
        }
        // 余额按 θ 降序逐张再分配，直到预算用尽或各臂候选耗尽
        val order = theta.entries.sortedByDescending { it.value }.map { it.key }
        while (left > 0) {
            var progressed = false
            for (arm in order) {
                if (left <= 0) break
                if ((out[arm] ?: 0) < (avail[arm] ?: 0)) {
                    out[arm] = (out[arm] ?: 0) + 1
                    left--
                    progressed = true
                }
            }
            if (!progressed) break
        }
        return out
    }

    // ---------------- Thompson 采样（P1-c，BRAINSTORM-ALGO §2.3：Beta-Bernoulli Bandit） ----------------

    /** Beta(a,b) 采样：x~Gamma(a)、y~Gamma(b)，θ=x/(x+y)；双零守卫返回 0.5（极端数值兜底） */
    private fun sampleBeta(a: Double, b: Double, rand: Random): Double {
        val x = sampleGamma(a, rand)
        val y = sampleGamma(b, rand)
        val s = x + y
        return if (s > 0.0) x / s else 0.5
    }

    /**
     * Gamma(k,1) 采样（Marsaglia-Tsang，不引依赖）：
     * k ≥ 1 走标准接受-拒绝；k < 1 走 Boost 技巧 Gamma(k) = Gamma(k+1)·U^(1/k)。
     * 本项目 shape 恒 ≥ [BETA_PRIOR]=1（先验保证），boost 分支仅为健壮性保留。
     */
    private fun sampleGamma(shape: Double, rand: Random): Double {
        if (shape <= 0.0) return 0.0
        if (shape < 1.0) {
            val u = rand.nextDouble().coerceAtLeast(1e-9)
            return sampleGamma(shape + 1.0, rand) * u.pow(1.0 / shape)
        }
        val d = shape - 1.0 / 3.0
        val c = 1.0 / sqrt(9.0 * d)
        while (true) {
            var z: Double
            var v: Double
            do {
                z = nextGaussian(rand)
                v = 1.0 + c * z
            } while (v <= 0.0)
            v = v * v * v
            val u = rand.nextDouble()
            // 快速接受分支 + 精确对数接受分支（Marsaglia-Tsang 原文两个判据）
            if (u < 1.0 - 0.0331 * z * z * z * z) return d * v
            if (ln(u) < 0.5 * z * z + d * (1.0 - v + ln(v))) return d * v
        }
    }

    /** 标准正态采样（Box-Muller；kotlin.random 无 nextGaussian，自实现不引依赖） */
    private fun nextGaussian(rand: Random): Double {
        val u1 = rand.nextDouble().coerceAtLeast(1e-9)
        val u2 = rand.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }

    // ---------------- 打分因子（§2.1 公式逐项） ----------------

    private fun scoreOf(inp: Input): Double {
        // R 关系强度：真图边聚合；无边候选走虚拟边兜底（相似=种子背书 / 雷达=探索权重）
        var r = 0.0
        for (e in inp.edges) {
            val w = when {
                e.fromChatId == 0L -> WEAK_SOURCE_WEIGHT // search_hit 等无引用方边：弱证据
                else -> sourceWeights[e.fromChatId] ?: EXPLORE_WEIGHT // 无画像引用方默认票值
            }
            r += w * edgeContribution(e) * ln1p(e.count.coerceAtLeast(1).toDouble())
        }
        // Adamic-Adar 共同邻居加项（P1-c）：订阅集内指向本候选的引用方 z（按 from 去重）
        // 各贡献 1/ln(outDeg(z)+1)——z 指向面越广票越稀（公共邻居无信息量）；
        // [AA_GAIN] 小系数并进 R，不盖过 W·tw·log1p 主项；无边候选天然无共同邻居
        if (sourceOutDegrees.isNotEmpty()) {
            var aa = 0.0
            for (src in inp.edges.map { it.fromChatId }.distinct()) {
                if (src == 0L || src !in subscribedIds) continue
                val deg = sourceOutDegrees[src] ?: continue
                aa += 1.0 / ln(deg + 1.0)
            }
            if (aa > 0.0) r += AA_GAIN * aa
        }
        if (inp.edges.isEmpty()) {
            r += when {
                inp.similarCoRef > 0 || inp.similarSeedIds.isNotEmpty() -> {
                    val seedW = inp.similarSeedIds.mapNotNull { sourceWeights[it] }
                        .takeIf { it.isNotEmpty() }?.average() ?: EXPLORE_WEIGHT
                    seedW * TW_VIRTUAL * ln1p(inp.similarCoRef.coerceAtLeast(1).toDouble())
                }
                SOURCE_RADAR in inp.sources -> EXPLORE_WEIGHT * TW_VIRTUAL * ln1p(1.0)
                else -> 0.0
            }
        }
        // P1-d 广场信号：全站推荐人数计 log1p 加分（plaza 臂的分数腿）。
        // 与图边叠加——有图边时是佐证加分；无边时充当虚拟边兜底，保证纯广场候选可排序；
        // 票值取 EXPLORE_WEIGHT（他方推荐 = 无本地画像背书的中性票，与雷达兜底同档）
        if (inp.plazaRecommends > 0) {
            r += EXPLORE_WEIGHT * TW_PLAZA * ln1p(inp.plazaRecommends.toDouble())
        }
        // P2 金矿答案库信号：用户搜过的词在搜索群里被 bot 回答过，命中次数计 log1p 加分。
        // 与 group_search 图边互斥——两者是收割 recordLinks 一次观测的双写产物
        // （边进图谱、答案进答案库），同存时图边已表达该证据，再加分即双计；
        // 无边（图谱淘汰/边未落盘）时充当虚拟边兜底，保证纯答案库候选可排序。
        // 票值取 EXPLORE_WEIGHT（需求侧他方证据，无本地画像背书，与广场同档口径）
        if (inp.answerHits > 0 && inp.edges.none { it.type == GroupHarvester.EDGE_GROUP_SEARCH }) {
            r += EXPLORE_WEIGHT * TW_GROUP_SEARCH * ln1p(inp.answerHits.toDouble())
        }
        // F 新鲜度：maxLastTs 越新越接近 1；无图边（纯相似/雷达）按刚发现取满格
        val maxLastTs = inp.edges.maxOfOrNull { it.lastTs } ?: 0L
        val f = if (maxLastTs <= 0L) {
            1.0
        } else {
            0.5 + 0.5 * exp(-(nowMs - maxLastTs).coerceAtLeast(0).toDouble() / FRESH_TAU_MS)
        }
        // Q 质量 / A 活跃度（P0 退化：无日历数据恒 0.65；成员数已知时 A 用 Q 代替）
        // P1-d 广场避雷惩罚：被 ≥[PLAZA_BLOCK_MIN] 人标记的候选 Q 降到地板 0.2——
        // 软惩罚而非硬过滤（避雷是他方信号，留误标记复出空间；过滤权仍在本地屏蔽表）
        val q0 = qualityOf(inp.memberCount)
        val q = if (inp.plazaBlocks >= PLAZA_BLOCK_MIN) minOf(q0, QUALITY_FLOOR) else q0
        val a = if (inp.memberCount != null) q else ACTIVITY_DEFAULT
        // T 内容匹配：标题口味词命中（jaccard 指纹重叠 P2 供给，P0 恒 0）
        val t = 1.0 + TASTE_OVERLAP_GAIN * tasteOverlap(inp.brief.title)
        // S 相似共指加成
        val s = if (inp.similarCoRef >= CO_REF_MIN) SIMILAR_CO_REF_BONUS else 0.0
        val score = r * f * (0.6 + 0.4 * a) * t * (0.8 + 0.2 * q) + s
        // 曝光疲劳：连续进榜未转化逐次降权
        val fatigued = score * FATIGUE_DECAY.pow(inp.impressions.coerceAtLeast(0))
        // 新货上浮（v4，"每次打开发现页都有近期新面孔"）：加性固定加分而非乘性——
        // 分值有界，够把新货从中腰部抬进前列，但压不动高分老面孔（上浮不霸榜）；
        // 新货刚出榜 impressions≈0，与老候选的曝光疲劳 ×0.85ⁿ 天然形成对冲
        return if (isFresh(inp)) fatigued + FRESH_FLOAT_BONUS else fatigued
    }

    /**
     * 新货判定（打分散装与探索域臂内排序共用同一口径）：
     * 7 天内图谱首见（[Input.firstSeen]），或在新货集合里（金矿 fresh 表——
     * 含 EntityResolver 新解析升级的群搜候选，见构造参数 [freshIds]）
     */
    private fun isFresh(inp: Input): Boolean =
        inp.brief.id in freshIds ||
            (inp.firstSeen > 0L && nowMs - inp.firstSeen < NEW_NODE_WINDOW_MS)

    /** Q 质量分：clamp(memberCount/10000, 0.2, 1.0)；成员数未知取中性档（不惩罚未知候选） */
    private fun qualityOf(memberCount: Int?): Double =
        if (memberCount == null) QUALITY_UNKNOWN
        else (memberCount / QUALITY_MEMBER_BASE).coerceIn(QUALITY_FLOOR, 1.0)

    /** tasteOverlap：候选标题分词命中口味词表的比例（0..1）；空标题/空词表返回 0 */
    private fun tasteOverlap(title: String): Double {
        if (title.isBlank() || tasteWords.isEmpty()) return 0.0
        val tokens = runCatching { tokenize(title) }.getOrDefault(emptyList()).distinct()
        if (tokens.isEmpty()) return 0.0
        val hits = tokens.count { it in tasteWords }
        return (hits.toDouble() / tokens.size).coerceIn(0.0, 1.0)
    }

    /** 边类型权重表（§2.2）：未知类型按 link 档 1.0（前向兼容未来新增边类型） */
    private fun edgeTypeWeight(type: String): Double = when (type) {
        DiscoveryGraph.EDGE_FORWARD_DST -> TW_FORWARD_DST
        DiscoveryGraph.EDGE_FORWARD_SRC -> TW_FORWARD_SRC
        DiscoveryGraph.EDGE_COMMENT -> TW_COMMENT
        DiscoveryGraph.EDGE_SEARCH_HIT -> TW_SEARCH_HIT
        DiscoveryGraph.EDGE_CO_WATCH -> TW_CO_WATCH
        // P2 金矿：group_search 显式进表（收割期一直按未知类型吃 link 档 1.0，
        // 进表后按需求侧边定档 1.2——群友真实搜索意图强于普通互链，弱于转发溯源）
        GroupHarvester.EDGE_GROUP_SEARCH -> TW_GROUP_SEARCH
        else -> TW_LINK
    }

    /**
     * 单条边的有效类型权重（打分/主导类型判定共用）：
     * forward_src 且引用方打了枢纽标（EdgeEvidence.hubFrom，DiscoveryGraph 建边侧打标）
     * → tw ×[HUB_PENALTY]。搬运枢纽号"什么都转"，其票不压会让 PageRank/打分偏向
     * 路标而非目的地（BRAINSTORM-GRAPH §1.3）。
     */
    private fun edgeContribution(e: DiscoveryGraph.EdgeEvidence): Double =
        edgeTypeWeight(e.type) *
            if (e.hubFrom && e.type == DiscoveryGraph.EDGE_FORWARD_SRC) HUB_PENALTY else 1.0

    // ---------------- 证据文案（§4.2：把"为什么推它"说清楚） ----------------

    private fun evidenceOf(inp: Input): List<String> {
        val lines = ArrayList<String>(3)
        // 互链引用（只数已订阅引用方——"你的"语义；未订阅引用方归兜底文案）
        val linkRefs = inp.edges
            .filter { it.type == DiscoveryGraph.EDGE_LINK && it.fromChatId in subscribedIds }
            .map { it.fromChatId }.distinct()
        if (linkRefs.isNotEmpty()) lines += "你的 ${linkRefs.size} 个订阅频道引用过它"
        // 转发溯源
        val fwdRefs = inp.edges
            .filter { it.type == DiscoveryGraph.EDGE_FORWARD_SRC && it.fromChatId in subscribedIds }
            .map { it.fromChatId }.distinct()
        if (fwdRefs.isNotEmpty()) lines += "你的 ${fwdRefs.size} 个订阅频道转发过它的内容"
        // P2 金矿：group_search 边（资源搜索群 bot 应答指向）——需求侧证据，
        // 文案刻意区别于"相似推荐"系：强调"群友在找"而非"内容像"
        if (inp.edges.any { it.type == GroupHarvester.EDGE_GROUP_SEARCH }) lines += "群友都在找这个"
        // P2 金矿答案库：用户自己搜过的词在群里有答案（点名词增强可感相关性）
        if (inp.answerKeywords.isNotEmpty()) lines += "搜「${inp.answerKeywords.first()}」的人都去了这些频道"
        // 搜索反哺（from=0 的弱证据边）
        if (inp.edges.any { it.type == DiscoveryGraph.EDGE_SEARCH_HIT }) lines += "你的搜索命中过它"
        // 相似（点名订阅种子；二层候选的指向节点不是订阅，落通用文案）
        if (inp.similarSeedIds.isNotEmpty()) {
            val names = inp.similarSeedIds.mapNotNull { titleOf(it) }.filter { it.isNotBlank() }
            lines += when {
                names.size > 1 -> "和「${names.first()}」等 ${names.size} 个频道相似"
                names.size == 1 -> "和「${names.first()}」相似"
                else -> "和你的订阅频道相似"
            }
        } else if (SOURCE_SIMILAR in inp.sources) {
            lines += "和你的订阅频道相似"
        }
        // 雷达
        if (SOURCE_RADAR in inp.sources) lines += "雷达巡逻命中的新频道"
        // P1-d 广场信号：全站推荐（plaza 臂名片的招牌文案）与避雷警告
        //（信任层在 PlazaService 读取侧已过滤刷屏/不合规行，这里如实展示聚合结果）
        if (inp.plazaRecommends > 0) lines += "全站 ${inp.plazaRecommends} 人推荐过它"
        if (inp.plazaBlocks >= PLAZA_BLOCK_MIN) lines += "⚠ 全站 ${inp.plazaBlocks} 人标记了避雷"
        // 兜底一：有图边但引用方都未订阅（聚合 Feed 刷到的圈外提及）
        if (lines.isEmpty() && inp.edges.isNotEmpty()) {
            val n = inp.edges
                .filter { it.type == DiscoveryGraph.EDGE_LINK || it.type == DiscoveryGraph.EDGE_FORWARD_SRC }
                .map { it.fromChatId }.filter { it != 0L }.distinct().size
            lines += if (n > 0) "被 $n 个你刷到的频道提及" else "图谱嗅探发现的新频道"
        }
        // 兜底二：任何证据都缺失（防御，正常不会走到）
        if (lines.isEmpty()) lines += "为你发现的新频道"
        return lines.take(EVIDENCE_MAX_LINES)
    }

    /** 来源角标：固定顺序拼接（图谱+相似+雷达+广场+群搜），与现有 UI 角标口径一致 */
    private fun sourceTagOf(inp: Input): String =
        listOf(SOURCE_GRAPH, SOURCE_SIMILAR, SOURCE_RADAR, SOURCE_PLAZA, SOURCE_GROUP_SEARCH)
            .filter { it in inp.sources }
            .joinToString("+")

    companion object {
        // ---- 来源标记（与发现页角标文案同源，勿改字面值——UI 靠它映射配色） ----
        const val SOURCE_GRAPH = "图谱"
        const val SOURCE_SIMILAR = "相似"
        const val SOURCE_RADAR = "雷达"

        /** P1-d 新来源：广场（fetchPlaza 快照 channelRecommends 全站推荐；角标"广场"） */
        const val SOURCE_PLAZA = "广场"

        /** P2 金矿新来源：资源搜索群收割（group_search 边 + 答案库直出；角标"群搜"） */
        const val SOURCE_GROUP_SEARCH = "群搜"

        // ---- 分桶 id（P1-c：野卡桶语义降级为"other" 归因兜底桶，见 rank() 探索域段） ----
        const val BUCKET_EXPLOIT = 0
        const val BUCKET_EXPLORE = 1
        const val BUCKET_WILDCARD = 2

        /**
         * 主导来源类型 → 来源臂名（discovery_arms.json 键口径，DISCOVER-UPGRADE P1-a #3）。
         * Thompson 配额分配（本类 rank）与收/弃记账（FeedViewModel 侧）共用此映射，
         * 保证"配额的臂"与"记账的臂"同口径；"official" 臂为 GetRecommendedChats 预留
         * （本轮无候选产出，键名先行约定）。
         */
        fun armNameOf(dominantType: String): String = when (dominantType) {
            DiscoveryGraph.EDGE_LINK -> "link"
            DiscoveryGraph.EDGE_FORWARD_SRC -> "fwd_src"
            DiscoveryGraph.EDGE_FORWARD_DST -> "fwd_dst"
            DiscoveryGraph.EDGE_COMMENT -> "comment"
            DiscoveryGraph.EDGE_SEARCH_HIT -> "search_hit"
            DiscoveryGraph.EDGE_CO_WATCH -> "co_watch"
            // P2 金矿：group_search 边候选与纯答案库候选（dominant 归同名字符串）共用一条臂
            GroupHarvester.EDGE_GROUP_SEARCH -> "group_search"
            SOURCE_SIMILAR -> "similar"
            SOURCE_RADAR -> "radar"
            // P1-d：广场臂（纯广场候选的收/弃记账与 Thompson 配额共用此键）
            DOMINANT_PLAZA -> "plaza"
            else -> ARM_OTHER
        }

        /** 默认输出上限（与 FeedViewModel.DISCOVERY_CANDIDATE_LIMIT 同值：50） */
        const val DEFAULT_LIMIT = 50

        // ---- §2.2 参数表：边类型权重 ----
        private const val TW_FORWARD_DST = 2.0  // 双滤（用户兴趣 × 公开热度）故最高
        private const val TW_FORWARD_SRC = 1.5
        private const val TW_COMMENT = 1.2
        private const val TW_LINK = 1.0
        private const val TW_SEARCH_HIT = 0.8   // 有意图但无引用方背书
        private const val TW_CO_WATCH = 0.3     // 观看序列共现：用户行为弱边，只作佐证不独立出榜

        /** P2 金矿：group_search 边权重（需求侧边：群友真实搜索意图，强于互链弱于转发溯源） */
        private const val TW_GROUP_SEARCH = 1.2

        /** 搬运枢纽惩罚系数：forward_src 枢纽边 tw ×0.4（BRAINSTORM-GRAPH §1.3，防搬运号霸榜） */
        private const val HUB_PENALTY = 0.4

        /** 虚拟边权重（相似/雷达兜底，文档未列——取 link 同档中性值） */
        private const val TW_VIRTUAL = 1.0

        /** 无画像引用方的默认票值（与 FeedSource.EXPLORE_WEIGHT 同口径） */
        private const val EXPLORE_WEIGHT = 0.6

        /** src=0（search_hit 等无引用方边）的弱证据票值 */
        private const val WEAK_SOURCE_WEIGHT = 0.3

        /** 新鲜度半衰 τ = 14 天（与 GRAPH §7.2 一致） */
        private const val FRESH_TAU_MS = 14L * 24 * 3600 * 1000

        /** 活跃度退化值（P0 无 GetChatMessageCalendar 数据） */
        private const val ACTIVITY_DEFAULT = 0.65

        /** 质量分：成员数未知时的中性档（因子 0.9，不惩罚未知候选） */
        private const val QUALITY_UNKNOWN = 0.5

        /** 质量分地板/归一基准（10,000 成员封顶 1.0） */
        private const val QUALITY_FLOOR = 0.2
        private const val QUALITY_MEMBER_BASE = 10_000.0

        /** T(c) 的口味命中增益系数（1 + 2·overlap） */
        private const val TASTE_OVERLAP_GAIN = 2.0

        /** 共指加成：≥2 共指 +0.5（相似递归二层天然供给） */
        private const val CO_REF_MIN = 2
        private const val SIMILAR_CO_REF_BONUS = 0.5

        /** 曝光疲劳：每进榜一次 ×0.85 */
        private const val FATIGUE_DECAY = 0.85

        /**
         * 候选下限：低于此不进列表（只约束纯图谱候选——单条低权 link 边 ≈0.6~1.0，
         * 恰好压掉"只被一个陌生频道提过一次"的噪声；相似/雷达来源豁免）
         */
        private const val MIN_SCORE = 1.0

        // ---- 输出层配额（P1-c：Thompson + MMR，替换 P0 静态三桶 60/25/15 与硬上限） ----

        /** 利用桶占比 60%（固定按分数，转化基本盘不动；探索域 = 剩余 40% 按臂分配） */
        private const val BUCKET_EXPLOIT_PERCENT = 60

        /** Thompson 均匀先验 α₀=β₀=1：θ ~ Beta(α₀+收, β₀+弃)——冷启动天然最大探索 */
        private const val BETA_PRIOR = 1.0

        /** 无法归因残余候选的臂名（出榜落野卡标签，语义 = 归因兜底桶） */
        private const val ARM_OTHER = "other"

        /** P1-d 广场臂主导类型字面值（无边纯广场候选的 dominant；armNameOf 映射到 "plaza" 臂） */
        private const val DOMINANT_PLAZA = "plaza"

        /** P1-d 广场虚拟边权重（全站推荐的分数腿系数；取 link 同档中性值） */
        private const val TW_PLAZA = 1.0

        /** P1-d 广场避雷惩罚门槛：被 ≥2 人标记的候选 Q 降到地板 0.2（单人标记可能是误伤，不降） */
        private const val PLAZA_BLOCK_MIN = 2

        /** MMR 相关度/多样性权衡 λ=0.7（BRAINSTORM-ALGO §2.4 口径） */
        private const val MMR_LAMBDA = 0.7

        /** MMR 相似度档：同主导类型惩罚 */
        private const val MMR_SIM_SAME_TYPE = 0.6

        /** MMR 相似度档：相似种子集合重叠 */
        private const val MMR_SIM_SEED_OVERLAP = 0.8

        /** Adamic-Adar 加项系数（小系数并进 R：Σ 1/ln(outDeg(z)+1)，不盖过主项） */
        private const val AA_GAIN = 0.3

        /** minScore 数据稀缺档：过线候选 < 桶容量时下探到此档重滤（§1.5 反探索修正） */
        private const val MIN_SCORE_SCARCE = 0.8

        /** 探索域臂内排序"新节点优先"：firstSeen 在 7 天内 +0.5 */
        private const val NEW_NODE_WINDOW_MS = 7L * 24 * 3600 * 1000
        private const val NEW_NODE_BONUS = 0.5

        /**
         * 新货上浮加分（v4）：打分散装尾段的加性固定值，对全体新货候选（7 天首见 /
         * 新货集合）生效——量级对齐一条中强图边的贡献（≈0.6·2.0·log1p(2)），
         * 能把新货抬进利用桶前列抢首屏，但压不动多分老面孔（上浮不霸榜）
         */
        private const val FRESH_FLOAT_BONUS = 2.0

        /** 证据文案最多列几条（多源命中叠加，卡片只放两行） */
        private const val EVIDENCE_MAX_LINES = 2
    }
}
