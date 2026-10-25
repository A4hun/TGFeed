package dev.tgfeed.core.feed

import dev.tgfeed.core.td.ChannelBrief
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/**
 * R183 发现页金矿引擎（终版架构「双宝藏全量 → 垃圾/广告过滤闸 → 公平轮转」的宝藏一）。
 *
 * **数据地基从「监听陌生群」换成「挖自己的深索引」**：深索引几百万条 items 是
 * 已验过货的第一手内容库——本引擎把每频道折算成一份客观档案 [ChannelGold]
 * （丰度分/近期更新/死链/广告信号），进发现页的只有过了 [AdPatternFilter] 过滤闸
 * 的合格频道；排序=公平轮转（R176 最少曝光优先思想，无口味个性化）。
 *
 * 性能纪律（LIKE 全表扫教训 + R170 预热协调器）：
 *  - 构建 = rollup（GROUP BY 走覆盖索引零回表）+ 每频道 [HEAD_SAMPLE_ROWS] 条头部
 *    有界采样（索引序倒序，回表行数与频道总量无关）；近 7 天窗/模板/话术全部
 *    Kotlin 侧对这批有界行计算，**零 dateEpochSec 全表扫**；
 *  - 重查询**后台单飞 + 快照缓存**：[saveSnapshot]/[loadSnapshot] 落 `files 根`的
 *    discovery_gold_snapshot.json（**绝不放 deepindex 目录**——R170 实锤那是门房
 *    辖区，删一切非数字命名 .json）；临时文件 + rename 原子落盘（LocalStore 同款）；
 *    [SNAPSHOT_TTL_MS] 内陈值先回（读快照毫秒级），过期才后台重建。
 *
 * 纯函数集合（JVM 可单测）+ 快照 IO 两件；不持可变状态、不反向依赖 VM 层。
 */
object DiscoveryGoldmine {

    /** 快照文件名（files 根；所有权=发现页金矿引擎，门房辖区之外） */
    const val SNAPSHOT_FILE = "discovery_gold_snapshot.json"

    /** 快照格式版本（老版本/损坏文件按空快照重建，不崩） */
    private const val SNAPSHOT_VERSION = 1

    /** 每频道头部采样行数：覆盖活跃频道近月产出，死频道全量覆盖（丰度密度分母即真实跨度） */
    const val HEAD_SAMPLE_ROWS = 256

    /** 快照 TTL（6 小时）：档案是小时级稳定事实，陈度无害；过期由调用方后台重建 */
    const val SNAPSHOT_TTL_MS = 6L * 3600_000

    // ---------------- 频道档案（快照行） ----------------

    /**
     * 一个索引频道的客观档案（全部第一手索引数据折算，零口味个性化字段）。
     * 丰度口径=**近期**（head 窗）——「值得看」关心频道现在有没有好内容：
     * 三年前更了 4 万条然后弃更的死频道全历史 R 高但近期密度分自然塌掉；
     * 全历史验货信号走 [tier]（R163 升档档位=系统验货徽章）互补。
     */
    data class ChannelGold(
        val chatId: Long,
        /** 频道标题（head 最新行的 channelTitle——候选标题免网络） */
        val title: String,
        /** 索引全量条数（rollup）——「N 条存货」 */
        val items: Int,
        /** 丰度分 R（0..1；IndexTierPolicy.richnessScore 同公式，head 窗口径） */
        val richness: Double,
        /** 是否有样本（false = 索引零条目，R 无意义） */
        val probed: Boolean,
        /** head 窗视频占比（0..1） */
        val videoShare: Double,
        /** 近 7 天发布条数（head 窗内统计） */
        val recent7: Int,
        /** 再前 7 天发布条数（增速对照：recent7 > prev7 = 提速） */
        val prev7: Int,
        /** 最近活动（epoch 秒；head 最新行日期） */
        val lastActivitySec: Long,
        /** R163 档位 persisted 值（-1=LEGACY；1=T1=系统验货徽章） */
        val tier: Int,
        /** 近 7 天死链率（InteractionStats deadmedia，0=无观看证据不误伤） */
        val deadRate: Double,
        /** 广告话术密度（AdPatternFilter 信号一） */
        val adRate: Double,
        /** 模板风暴率（AdPatternFilter 信号二） */
        val stormRate: Double,
        /** spam 农场指纹命中（AdPatternFilter 信号三） */
        val farm: Boolean,
    )

    /** 快照：构建时刻 + 全部有货频道档案（未过滤——订阅/屏蔽/软拒绝过滤归刷新时动态做） */
    data class Snapshot(val builtAtMs: Long, val channels: List<ChannelGold>) {
        fun byId(): Map<Long, ChannelGold> = channels.associateBy { it.chatId }
        fun isStale(nowMs: Long = System.currentTimeMillis()): Boolean =
            builtAtMs <= 0 || nowMs - builtAtMs > SNAPSHOT_TTL_MS
    }

    // ---------------- 信号折算（head 行 → RichnessSignals/Richness） ----------------

    /**
     * head 采样行 → R163 丰度信号（**公式复用** [IndexTierPolicy.richnessScore]，仅
     * 输入源从 channelIndexStats 的全频道聚合换成 head 窗——权重/系数/死链负分量
     * 一处定义零镜像）。剧集规整率样本= head 窗非空 caption（≤256 条全用）。
     */
    fun richnessSignalsOf(head: List<DeepSearchIndex.ChannelHeadRow>, deadRate: Double): IndexTierPolicy.RichnessSignals {
        if (head.isEmpty()) return IndexTierPolicy.RichnessSignals(probed = false)
        val captions = head.map { it.caption }.filter { it.isNotBlank() }
        val spanSec = (head.first().dateEpochSec - head.last().dateEpochSec).coerceAtLeast(0L)
        val episodeHitRate = if (captions.isEmpty()) 0.0 else {
            captions.count { parseEpisodeMark(it) != null }.toDouble() / captions.size
        }
        val avgCaptionLen = if (captions.isEmpty()) 0.0 else {
            captions.sumOf { it.length }.toDouble() / captions.size
        }
        return IndexTierPolicy.RichnessSignals(
            probed = true,
            sampleItems = head.size,
            sampleSpanDays = spanSec / 86_400.0,
            videoShare = head.count { it.mediaType == 0 }.toDouble() / head.size,
            episodeHitRate = episodeHitRate,
            captionNonEmptyRate = captions.size.toDouble() / head.size,
            avgCaptionLen = avgCaptionLen,
            deadRate = deadRate,
        )
    }

    /**
     * 快照构建（IO 协程调用；唯一重查询入口，调用方负责单飞防重入）：
     * rollup（覆盖索引聚合）→ 逐频道 head 采样折算 → 跨频道 spam 农场比对。
     * [deadRates] = InteractionStats.deadChannelStats()（调用方聚合传入，纯函数不反向依赖）。
     * 索引未建/零频道返回空快照（builtAtMs=now——空库也是事实，别让每次刷新都重建）。
     */
    fun buildSnapshot(
        deepIndex: DeepSearchIndex,
        deadRates: Map<Long, Double>,
        nowMs: Long = System.currentTimeMillis(),
    ): Snapshot {
        val rollup = runCatching { deepIndex.channelCountRollup() }.getOrDefault(emptyMap())
        val tiers = runCatching { deepIndex.tiers() }.getOrDefault(emptyMap())
        val nowSec = nowMs / 1000L
        val captionsByChannel = HashMap<Long, List<String>>(rollup.size)
        val channels = ArrayList<ChannelGold>(rollup.size)
        // R183 真机性能修正：按 chatId 升序遍历（= idx_items_chat_msg 索引序，连续页
        // 局部性好；HashMap 散序遍历 = 随机跳页的冷缓存抖动，实测首建 172s 的放大器）
        for ((chatId, itemCount) in rollup.entries.sortedBy { it.key }) {
            val head = runCatching { deepIndex.channelHeadRows(chatId, HEAD_SAMPLE_ROWS) }
                .getOrDefault(emptyList())
            if (head.isEmpty()) continue // rollup 与 head 不一致（并发爬取写入）：本轮跳过
            val captions = head.map { it.caption }.filter { it.isNotBlank() }
            captionsByChannel[chatId] = captions
            val deadRate = deadRates[chatId] ?: 0.0
            val signals = richnessSignalsOf(head, deadRate)
            channels += ChannelGold(
                chatId = chatId,
                title = head.first().channelTitle,
                items = itemCount,
                richness = IndexTierPolicy.richnessScore(signals),
                probed = signals.probed,
                videoShare = signals.videoShare,
                recent7 = head.count { it.dateEpochSec >= nowSec - 7L * 86_400 },
                prev7 = head.count { it.dateEpochSec >= nowSec - 14L * 86_400 && it.dateEpochSec < nowSec - 7L * 86_400 },
                lastActivitySec = head.first().dateEpochSec,
                tier = tiers[chatId]?.persisted ?: -1,
                deadRate = deadRate,
                adRate = AdPatternFilter.adRateOf(captions),
                stormRate = AdPatternFilter.stormRateOf(captions),
                farm = false, // 农场标记在下方跨频道比对统一回填
            )
        }
        val farmFlags = AdPatternFilter.farmChannelsOf(captionsByChannel)
        if (farmFlags.isNotEmpty()) {
            val out = ArrayList<ChannelGold>(channels.size)
            for (g in channels) out += g.copy(farm = g.chatId in farmFlags)
            return Snapshot(nowMs, out)
        }
        return Snapshot(nowMs, channels)
    }

    // ---------------- 过滤闸（AdPatternFilter 总判定的档案版封装） ----------------

    /** 过滤闸判定（[AdPatternFilter.classify] 的档案重载：宝藏一群热度=0，由调用方在外层并） */
    fun verdictOf(g: ChannelGold, groupEndorse: Int): AdPatternFilter.Verdict =
        AdPatternFilter.classify(
            probed = g.probed,
            richness = g.richness,
            adRate = g.adRate,
            stormRate = g.stormRate,
            farmFlag = g.farm,
            groupEndorse = groupEndorse,
        )

    // ---------------- 公平轮转（R176 思想：最少曝光优先，无个性化排序） ----------------

    /**
     * 公平轮转选择（R176 interleaveRound「最少已供给者优先」思想在发现页的落地）：
     * **曝光账升序**（impressions 缺席=0=从未露面者优先——人人有出场机会不被头部
     * 霸屏）；并列 tie-break（同为首次露面时的确定性序）：
     * 质量降序（[qualityOf]——客观丰度或群热度，非口味）→ 活跃降序（[activityOf]）→
     * 存货降序（[stockOf]）→ id 升序稳定。
     * [excludeIds] =「换一批」本轮排除集（排除的不占配额、不记曝光）；池子不足时
     * 允许列表变短（UI 空态承接）。
     *
     * 泛型本体：候选包装层（金矿档案 / 金矿+群信号合并体）与 [ChannelGold] 专用
     * 重载共用这**一份**实现（单测与生产同源，零镜像）。
     */
    fun <T> fairRotationSelect(
        pool: List<T>,
        idOf: (T) -> Long,
        qualityOf: (T) -> Double,
        activityOf: (T) -> Int,
        stockOf: (T) -> Int,
        impressions: Map<Long, Int>,
        excludeIds: Set<Long>,
        limit: Int,
    ): List<T> {
        if (pool.isEmpty() || limit <= 0) return emptyList()
        return pool.asSequence()
            .filter { idOf(it) !in excludeIds }
            .sortedWith(
                compareBy<T> { impressions[idOf(it)] ?: 0 }
                    .thenByDescending { qualityOf(it) }
                    .thenByDescending { activityOf(it) }
                    .thenByDescending { stockOf(it) }
                    .thenBy { idOf(it) },
            )
            .take(limit)
            .toList()
    }

    /** [fairRotationSelect] 的金矿档案重载：质量=丰度、活跃=近 7 天、存货=索引条数 */
    fun fairRotationSelect(
        pool: List<ChannelGold>,
        impressions: Map<Long, Int>,
        excludeIds: Set<Long>,
        limit: Int,
    ): List<ChannelGold> = fairRotationSelect(
        pool = pool,
        idOf = { it.chatId },
        qualityOf = { it.richness },
        activityOf = { it.recent7 },
        stockOf = { it.items },
        impressions = impressions,
        excludeIds = excludeIds,
        limit = limit,
    )

    // ---------------- R211 群候选（无索引档案）的公平轮转 tie-break 纯函数 ----------------

    /**
     * 群热度折算分母（endorse/20 再封顶 [GROUP_QUALITY_CEIL]）——与旧
     * refreshDiscoveryImpl 内联口径同值迁入（一处定义，零镜像）。
     */
    const val DISCOVERY_GROUP_QUALITY_CAP = 20.0

    /** 群候选 quality 封顶（0.5 < R163 丰度升档线 0.55——验货过的金矿频道恒先于纯群热度） */
    const val GROUP_QUALITY_CEIL = 0.5

    /** 新鲜度·第一档：bot 应答在 24h 内（任务书时效性「新鲜热乎的」） */
    const val GROUP_FRESH_24H_MS = 24L * 3600_000

    /** 新鲜度·第二档：bot 应答在 7 天内 */
    const val GROUP_FRESH_7D_MS = 7L * 24 * 3600_000

    /**
     * R211 群候选质量（无金矿档案时的公平轮转 quality tie-break，单一定义）：
     *  - 基础封顶 0.35（陈旧未验证的群传候选——语义：「值得看=深索引验货优先」，
     *    群热度再高也不越丰度过线的金矿频道；比旧口径 0.5 一刀切更低一档）；
     *  - **直荐验证 +0.10**（USER 车道验过存在+是频道）；
     *  - **新鲜度 +0.05（≤24h）/ +0.02（≤7d）**（bot 应答近的先出场——任务书 d）；
     *  - 恒封顶 [GROUP_QUALITY_CEIL]=0.5（< 0.55 丰度升档线不变）。
     * endorse 本体仍按 /20 折算（多群多命中慢慢涨，封顶管天花板）。
     */
    fun groupCandidateQuality(endorse: Int, verified: Boolean, signalAgeMs: Long): Double {
        val cap = 0.35 +
            (if (verified) 0.10 else 0.0) +
            (if (signalAgeMs <= GROUP_FRESH_24H_MS) 0.05 else if (signalAgeMs <= GROUP_FRESH_7D_MS) 0.02 else 0.0)
        return minOf(endorse / DISCOVERY_GROUP_QUALITY_CAP, minOf(cap, GROUP_QUALITY_CEIL))
    }

    /**
     * R211 群候选活跃度（公平轮转 activity tie-break，单一定义）：新鲜度分档
     * （24h=10 / 7d=5）压过 endorse 计数——同 endorse 层里**bot 应答近的先出**；
     * 陈旧的回落 endorse（多群在传仍是活跃信号）。
     */
    fun groupCandidateActivity(endorse: Int, signalAgeMs: Long): Int = when {
        signalAgeMs <= GROUP_FRESH_24H_MS -> 10
        signalAgeMs <= GROUP_FRESH_7D_MS -> 5
        else -> endorse
    }

    // ---------------- 客观推荐语（终版口径：只写事实，不猜口味） ----------------

    /**
     * 候选主推荐语（人话客观事实——替代旧「语焉不详的来源词」）。
     * 优先级：双置信（索引验货+搜索群在传）> **R211 直荐（用户勾选群验证过+带词）**
     * > 群传待验 > 近 7 天上升 > 丰度存货 > 兜底。
     *
     * @param groupEndorse 宝藏二热度（答案库 hits + group_search 边 count 合计；0=无群信号）
     * @param groupSearchWord 群搜来源词（答案库代表词；有词时群传文案点名「群友搜『X』」）
     * @param memberCount 成员数（enrich 懒加载；null/≤0=未知，冷门句不出现）
     * @param userGroupVerified R211 直荐口径：该频道的群传证据来自**用户勾选群**且经
     *   USER 车道验证（存在+是频道）——用任务书原话「你的搜索群里有人搜过「X」」，
     *   不带「待深索引验货」后缀（已验过活，丰度验货交给双置信卡）
     */
    fun recommendLineOf(
        g: ChannelGold?,
        groupEndorse: Int,
        groupSearchWord: String?,
        memberCount: Int?,
        userGroupVerified: Boolean = false,
    ): String {
        val pct = if (g != null && g.probed) (g.richness * 100).roundToInt().coerceIn(0, 100) else 0
        val word = groupSearchWord?.takeIf { it.isNotBlank() }
        return when {
            // 双置信：两路宝藏都为它背书——最高推荐位
            g != null && g.probed && groupEndorse > 0 ->
                "深索引验货（丰度 $pct 分）· 你的搜索群在传"
            // R211 直荐：用户勾选群里群友搜过+已验证——任务书原话口径
            groupEndorse > 0 && word != null && userGroupVerified ->
                "你的搜索群里有人搜过「$word」"
            // 纯宝藏二（索引还没验过货——刚从群里冒出来的新频道）
            groupEndorse > 0 && word != null -> "群友搜「$word」找到 · 待深索引验货"
            groupEndorse > 0 -> "你的搜索群在传 · 待深索引验货"
            // 上升（客观增速，第一手索引时间戳）
            g != null && g.recent7 >= RISING_LINE_MIN ->
                "近 7 天更新 ${g.recent7} 条" + if (g.probed && pct > 0) " · 丰度 $pct 分" else ""
            // 冷门宝藏（丰度高但人少——客观事实，不设个性化专区）
            g != null && g.probed && g.richness >= IndexTierPolicy.RICHNESS_UP_SCORE &&
                memberCount != null && memberCount in 1 until COLD_GEM_MEMBER_MAX ->
                "冷门但存货扎实：${fmtCount(g.items)} 条 · 仅 ${fmtMember(memberCount)}"
            // 常规（丰度+存货）
            g != null && g.probed && pct > 0 ->
                "索引丰度 $pct 分 · ${fmtCount(g.items)} 条存货"
            g != null -> "索引收录 ${fmtCount(g.items)} 条"
            else -> "为你发现的新频道"
        }
    }

    /**
     * 佐证徽章（胶囊行文案，最多 [CORROBORATION_MAX] 枚）：广场/群搜/上升/T1 验货/
     * 冷门——全部客观计数，不做主排序（终版修正：降级为佐证）。
     */
    fun corroborationChipsOf(
        g: ChannelGold?,
        groupCount: Int,
        groupEndorse: Int,
        plazaRecommends: Int,
        memberCount: Int?,
    ): List<String> {
        val out = ArrayList<String>(4)
        if (groupCount > 0) out += "$groupCount 群在搜"
        if (g != null && g.recent7 > 0) out += "本周 ${g.recent7} 条"
        if (g != null && g.tier == IndexTierPolicy.IndexTier.T1.persisted) out += "系统验货 T1"
        if (plazaRecommends > 0) out += "全站 $plazaRecommends 人推荐"
        if (memberCount != null && memberCount in 1 until COLD_GEM_MEMBER_MAX && g != null &&
            g.probed && g.richness >= IndexTierPolicy.RICHNESS_UP_SCORE
        ) {
            out += "冷门宝藏"
        }
        return out.take(CORROBORATION_MAX)
    }

    /** 上升句的最低门槛（7 天 ≥7 条 ≈ 日更——低于此不配「正在上升」句式） */
    const val RISING_LINE_MIN = 7

    /** 冷门宝藏成员上限（<1 万 = 小众频道；未知成员数不猜） */
    const val COLD_GEM_MEMBER_MAX = 10_000

    /** 佐证徽章每卡最多枚数（胶囊行宽度保护） */
    const val CORROBORATION_MAX = 3

    /** 存货数格式化：≥1 万压成「3.2 万」 */
    internal fun fmtCount(n: Int): String =
        if (n < 10_000) "$n" else {
            val whole = n / 10_000
            val dec = n % 10_000 / 1_000
            if (dec == 0) "${whole}万" else "$whole.${dec}万"
        }

    /** 成员数格式化（与 DiscoverScreen.formatMemberCount 同口径；卡片文案共用） */
    internal fun fmtMember(n: Int): String = fmtCount(n) + "成员"

    // ---------------- 快照落盘/读盘（LocalStore 同款：临时文件 + rename 原子写） ----------------

    /** 快照原子落盘（IO 协程；失败静默——陈值快照还在，下次刷新重建再试） */
    fun saveSnapshot(file: File, snap: Snapshot) {
        runCatching {
            val root = JSONObject()
                .put("v", SNAPSHOT_VERSION)
                .put("builtAtMs", snap.builtAtMs)
            val arr = JSONArray()
            for (g in snap.channels) {
                arr.put(
                    JSONObject()
                        .put("id", g.chatId)
                        .put("title", g.title)
                        .put("items", g.items)
                        .put("richness", g.richness)
                        .put("probed", g.probed)
                        .put("videoShare", g.videoShare)
                        .put("recent7", g.recent7)
                        .put("prev7", g.prev7)
                        .put("lastAct", g.lastActivitySec)
                        .put("tier", g.tier)
                        .put("deadRate", g.deadRate)
                        .put("adRate", g.adRate)
                        .put("storm", g.stormRate)
                        .put("farm", g.farm),
                )
            }
            root.put("channels", arr)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText()) // rename 失败兜底（跨分区/占用）
                tmp.delete()
            }
        }
    }

    /** 快照读盘（IO 协程；文件缺失/损坏/版本不符返回 null——调用方按无快照重建） */
    fun loadSnapshot(file: File): Snapshot? {
        return runCatching {
            if (!file.isFile) return@runCatching null
            val root = JSONObject(file.readText())
            if (root.optInt("v", -1) != SNAPSHOT_VERSION) return@runCatching null
            val builtAt = root.optLong("builtAtMs", 0L)
            val arr = root.optJSONArray("channels") ?: return@runCatching null
            val out = ArrayList<ChannelGold>(arr.length())
            for (i in 0 until arr.length()) {
                // 单条坏档案只跳过该条（LocalStore 同款防御口径）
                runCatching {
                    val o = arr.getJSONObject(i)
                    val id = o.optLong("id", 0L)
                    if (id == 0L) return@runCatching
                    out += ChannelGold(
                        chatId = id,
                        title = o.optString("title", ""),
                        items = o.optInt("items", 0),
                        richness = o.optDouble("richness", 0.0),
                        probed = o.optBoolean("probed", false),
                        videoShare = o.optDouble("videoShare", 0.0),
                        recent7 = o.optInt("recent7", 0),
                        prev7 = o.optInt("prev7", 0),
                        lastActivitySec = o.optLong("lastAct", 0L),
                        tier = o.optInt("tier", -1),
                        deadRate = o.optDouble("deadRate", 0.0),
                        adRate = o.optDouble("adRate", 0.0),
                        stormRate = o.optDouble("storm", 0.0),
                        farm = o.optBoolean("farm", false),
                    )
                }
            }
            Snapshot(builtAt, out)
        }.getOrNull()
    }

    /** 便捷：ChannelGold → ChannelBrief（发布候选列表用；username 索引没有，null） */
    fun briefOf(g: ChannelGold): ChannelBrief = ChannelBrief(g.chatId, g.title, null)
}
