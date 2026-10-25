package dev.tgfeed.core.feed

/**
 * 频道体检报告模型 + 健康分打分（R132，ideas.md #6：僵尸/变质频道一键断舍离）。
 *
 * 定位：给"我关注/订阅的频道"做一次全本地体检——判据全部来自已有数据
 * （深度索引的频道统计 + watch_events 互动记录 + 广告频道黑名单），
 * **零网络请求**；体检页是低频入口，每次进入现算即可。
 *
 * 健康分口径（0~100，越高越健康）：100 起步扣分，四个维度独立计罚、互不叠加封顶：
 *  1. 静默罚（最后发帖距今，越久越差）：索引无该频道数据不计罚（没有证据不臆测）；
 *  2. 低频罚（近 30 天发帖条数）：0 条/1~3 条分两档；
 *  3. 广告罚：命中广告黑名单（AdChannelClassifier 三特征判定）重罚；
 *  4. 互动衰减罚（你与它的互动）：最近一次观看距今 + "看过的还看吗"
 *     （历史观看秒数按 30 天半衰折算后的近期有效值占比）。
 *
 * 分层（差 → 好，枚举序即列表排序优先级）：
 *  - 变质：广告黑名单命中（频道性质变了，留着只剩骚扰）；
 *  - 僵尸：索引实证 > 90 天没更新（无索引数据不判僵尸，防误杀）；
 *  - 低频：近 30 天 0~3 条的稀疏更新，或你超过半年没看过它（频道活着、关系死了）；
 *  - 健康：其余。
 */
enum class ChannelHealthTier(val label: String, val emoji: String) {
    DECAYED("变质", "⚠️"),
    ZOMBIE("僵尸", "🧟"),
    LOW_FREQ("低频", "🌙"),
    HEALTHY("健康", "💚"),
}

/**
 * 单频道体检条目（[FeedViewModel.buildChannelHealth] 产出）。
 * [lastPostDays]/[posts30d]/[postsPerWeek] 为 null = 深度索引没有该频道的数据
 * （没爬过 ≠ 没更新，判罚与分层一律按"无证据"跳过）；[lastWatchDays] 为 null = 从未看过。
 */
data class ChannelHealthEntry(
    val chatId: Long,
    val title: String,              // 频道名（订阅/频道列表/内存条目多来源拼表，缺省"未知频道"）
    val score: Int,                 // 健康分 0..100
    val tier: ChannelHealthTier,    // 分层（排序键）
    val reasons: List<String>,      // 判定原因（至多 3 条，按重要性排序，UI 直接拼接展示）
    val lastPostDays: Long?,        // 最新条目距今天数；null = 索引未覆盖
    val posts30d: Int?,             // 近 30 天发帖条数；null = 索引未覆盖
    val postsPerWeek: Double?,      // 索引覆盖期整体发帖频率（条/周）；null = 索引未覆盖
    val adBlocked: Boolean,         // 命中广告黑名单
    val lastWatchDays: Long?,       // 最近一次观看距今天数；null = 从未看过
    val totalWatchSec: Long,        // 历史累计观看秒数（watch_events 全量）
    val decayedWatchSec: Long,      // 30 天半衰折算后的近期有效观看秒数（"看过的还看吗"）
)

/**
 * 体检报告聚合（全默认值 = 空报告）。[entries] 已按"分层差者优先 + 健康分升序 + 标题"
 * 排好序，UI 直接渲染；生成时刻 [generatedAtMs] 供"体检时间"角标展示。
 */
data class ChannelHealthReport(
    val generatedAtMs: Long = 0L,
    val entries: List<ChannelHealthEntry> = emptyList(),
) {
    /** 空报告（没有任何可体检的频道） */
    val isEmpty: Boolean get() = entries.isEmpty()

    /** 某分层条数（总览卡四格计数用） */
    fun countOf(tier: ChannelHealthTier): Int = entries.count { it.tier == tier }
}

// ---------------- 打分阈值（体检页专用，文件级私有防散落） ----------------

/** 僵尸判定：最新帖距今超过该天数（ideas.md #6 原口径"90 天没发"） */
private const val ZOMBIE_DAYS = 90L

/** 互动弃置判定：最近一次观看距今超过该天数（"你半年没看过它"） */
private const val ABANDON_WATCH_DAYS = 180L

/** 低频判定：近 30 天发帖 ≤ 该条数 */
private const val LOW_FREQ_30D_MAX = 3

/** 互动明显衰减判定：历史观看 ≥ 该秒数 且 近期有效占比 < [DECAYED_RATIO_CUT] */
private const val MEANINGFUL_WATCH_SEC = 600L

/** 近期有效观看占比低于该值记"明显衰减"（30 天半衰后残值 <15%） */
private const val DECAYED_RATIO_CUT = 0.15

/**
 * 单频道体检打分（纯函数，输入原始事实、输出条目）。
 * 分层与判罚口径见文件头注释；reasons 文案为 UI 展示口径（中文短句）。
 *
 * @param subscribed 构建时刻是否已订阅（只影响"订阅后从未看过"文案，不入条目字段——
 *   订阅态由 UI 层实时派生，取关/重订后行内状态即时变化，无需重算报告）
 */
internal fun buildChannelHealthEntry(
    chatId: Long,
    title: String,
    subscribed: Boolean,
    lastPostDays: Long?,
    posts30d: Int?,
    postsPerWeek: Double?,
    adBlocked: Boolean,
    lastWatchDays: Long?,
    totalWatchSec: Long,
    decayedWatchSec: Long,
): ChannelHealthEntry {
    // ---- 四维扣分（独立计罚，最后统一夹到 0..100） ----
    var penalty = 0
    // 1) 静默罚：最后发帖距今（无索引数据不计——没爬过不等于死了）
    if (lastPostDays != null) {
        penalty += when {
            lastPostDays > 180L -> 65
            lastPostDays > ZOMBIE_DAYS -> 45
            lastPostDays > 30L -> 25
            lastPostDays > 7L -> 8
            else -> 0
        }
    }
    // 2) 低频罚：近 30 天发帖条数（无索引数据不计）
    if (posts30d != null) {
        penalty += when {
            posts30d == 0 -> 12
            posts30d <= LOW_FREQ_30D_MAX -> 6
            else -> 0
        }
    }
    // 3) 广告罚：黑名单命中（变质频道，最重罚）
    if (adBlocked) penalty += 55
    // 4a) 互动弃置罚：最近一次观看距今（从未看过 = 只吃轻罚，频道本身可能没问题）
    penalty += when (lastWatchDays) {
        null -> 12
        in 0L..14L -> 0
        in 15L..60L -> 4
        in 61L..90L -> 10
        in 91L..ABANDON_WATCH_DAYS -> 18
        else -> 25
    }
    // 4b) 互动明显衰减罚：历史看得不少、近期残值占比却很低（"看过的还看吗"）
    if (totalWatchSec >= MEANINGFUL_WATCH_SEC &&
        decayedWatchSec.toDouble() / totalWatchSec < DECAYED_RATIO_CUT
    ) {
        penalty += 8
    }
    val score = (100 - penalty).coerceIn(0, 100)

    // ---- 分层（判定优先级：变质 > 僵尸 > 低频 > 健康） ----
    val tier = when {
        adBlocked -> ChannelHealthTier.DECAYED
        lastPostDays != null && lastPostDays > ZOMBIE_DAYS -> ChannelHealthTier.ZOMBIE
        posts30d != null && posts30d <= LOW_FREQ_30D_MAX -> ChannelHealthTier.LOW_FREQ
        lastWatchDays != null && lastWatchDays > ABANDON_WATCH_DAYS -> ChannelHealthTier.LOW_FREQ
        else -> ChannelHealthTier.HEALTHY
    }

    // ---- 判定原因（按重要性收集，截前 3 条） ----
    val reasons = ArrayList<String>(4)
    if (adBlocked) reasons += "广告频道（黑名单命中）"
    if (lastPostDays != null) {
        when {
            lastPostDays > ZOMBIE_DAYS -> reasons += "已 $lastPostDays 天没更新"
            lastPostDays > 30L -> reasons += "$lastPostDays 天没更新"
            else -> {} // 常规更新节奏，无需点名
        }
    } else {
        reasons += "暂无更新数据"
    }
    when {
        posts30d == null -> {} // 无数据不点评
        posts30d == 0 && lastPostDays != null && lastPostDays <= ZOMBIE_DAYS -> reasons += "近 30 天零更新"
        posts30d in 1..LOW_FREQ_30D_MAX -> reasons += "近 30 天仅 $posts30d 条"
        else -> {}
    }
    when (lastWatchDays) {
        null -> if (subscribed) reasons += "订阅后从未看过"
        in 0L..90L -> {} // 近三个月有互动，不点名
        in 91L..ABANDON_WATCH_DAYS -> reasons += "你 $lastWatchDays 天没看它"
        else -> reasons += "你半年没看过它"
    }
    if (totalWatchSec >= MEANINGFUL_WATCH_SEC &&
        decayedWatchSec.toDouble() / totalWatchSec < DECAYED_RATIO_CUT
    ) {
        reasons += "近期兴趣明显衰减"
    }

    return ChannelHealthEntry(
        chatId = chatId,
        title = title,
        score = score,
        tier = tier,
        reasons = reasons.take(3),
        lastPostDays = lastPostDays,
        posts30d = posts30d,
        postsPerWeek = postsPerWeek,
        adBlocked = adBlocked,
        lastWatchDays = lastWatchDays,
        totalWatchSec = totalWatchSec,
        decayedWatchSec = decayedWatchSec,
    )
}
