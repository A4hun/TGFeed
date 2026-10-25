package dev.tgfeed.core.onthisday

import dev.tgfeed.core.feed.TimeWindowPolicy
import dev.tgfeed.core.td.VideoItem
import java.time.LocalDate
import java.time.ZoneId

/**
 * R203 那年今日族·共享内容引擎（纯函数，TL-2 页面与 RT-2 推送共用）。
 *
 * 「N 年前的今天你的频道们在传什么」全链路：
 *   日期窗（当年今日 ±1 天）→ 候选集（时间窗查询，走 [OnThisDaySource] 接缝，
 *   R202 timeWindowItems 共享原语）→ 精选 N 条 → 用户向文案。
 *
 * **策展管线已收编（AGENT-DISCIPLINE §一 + R202 同类扫描点名）**：五步精炼
 * （有效行防御/广告剔除/同片去重保首发/每频道封顶+轮转交错/倒序终序）由
 * [TimeWindowPolicy.refineCrossSection] 统一承载（R202 阶段 B 落地，TL-1/TL-4/
 * DS-1/R203 四消费方同一引擎）——本件只做那年今日特有层：日期窗推算、年轮统计、
 * 推送择年、文案。**勿在此另写管线或镜像常量**（采样硬顶=TimeWindowPolicy
 * .QUERY_LIMIT_MAX 单一出处；秒常数=TimeWindowPolicy.DAY_SEC）。
 *
 * 设计口径（timeline-opportunities.md TL-2 条目）：
 *  - 窗=目标日期 ±1 天（共 3 天）——「那一天频道生态的横截面」（TG 频道更新节奏
 *    离散，单日太薄，±1 天才有「那几天在传什么」的浓度）；
 *  - 年轮频控：1..[MAX_YEARS] 年各一屏，别一次倾泻（R200 风险条）；
 *  - 文案用户向且脱敏：数字与年数，零频道名零标题（RT-2 推送红线，DailyDigest
 *    R63 同口径）。
 *
 * 全部 JVM 纯函数可单测；时间一律显式传参（today/zone），不偷读系统时钟。
 */

/** epoch 秒区间 [fromSec, toSecExcl)：左闭右开；与 timeWindowItems 闭区间换算见 Source */
data class EpochRange(val fromSec: Long, val toSecExcl: Long) {
    fun contains(sec: Long): Boolean = sec >= fromSec && sec < toSecExcl
}

/**
 * 目标日期的「那年今日窗」：日期 ±1 天（本机时区口径，DailyDigest todayStart 同源）。
 * 例：目标 2026-10-17（东八）→ [2026-10-16 00:00, 2026-10-19 00:00) 共 3 天。
 */
fun onThisDayWindow(date: LocalDate, zone: ZoneId): EpochRange {
    val dayStart = date.atStartOfDay(zone).toEpochSecond()
    return EpochRange(dayStart - TimeWindowPolicy.DAY_SEC, dayStart + 2 * TimeWindowPolicy.DAY_SEC)
}

/** 周年目标日：today 减 N 年。2 月 29 日的非闰年周年回落 2 月 28 日（java.time 语义，诚实口径） */
fun anniversaryDate(today: LocalDate, yearsAgo: Int): LocalDate = today.minusYears(yearsAgo.toLong())

/** 策展结果：精选条目 + 横截面统计（R202 共享统计行：条数/频道数/同片折叠/广告剔除） */
data class OnThisDayCuration(
    /** 精选条目（发布时间倒序，Feed 信息流语义） */
    val items: List<VideoItem>,
    /** 横截面统计（[TimeWindowPolicy.statsOf]；channels=文案用频道数） */
    val stats: TimeWindowPolicy.CrossSectionStats,
)

/**
 * 策展主函数：候选集 → 精选 N 条（五步管线委托 [TimeWindowPolicy.refineCrossSection]）。
 * 本层附加：窗成员防御过滤（查询侧已窗化，这里兜底窗外行/无时间戳老数据缺省 0）。
 * 每频道上限走那年今日口径 [MAX_PER_CHANNEL]（横截面广度优先，比时光机的 3 更紧）。
 */
fun curateOnThisDay(
    candidates: List<VideoItem>,
    window: EpochRange,
    limit: Int = CURATED_LIMIT,
): OnThisDayCuration {
    val windowed = candidates.filter { it.dateEpochSec > 0 && window.contains(it.dateEpochSec.toLong()) }
    val refined = TimeWindowPolicy.refineCrossSection(
        items = windowed,
        perChannelCap = MAX_PER_CHANNEL,
        limit = limit,
    )
    return OnThisDayCuration(
        items = refined,
        stats = TimeWindowPolicy.statsOf(windowed, refined),
    )
}

// ---------------- 年轮统计与推送择年 ----------------

/** 单个年轮的窗内统计（pills 徽标与推送判定共用的原料） */
data class OnThisDayYearStat(
    val yearsAgo: Int,
    /** 窗内条数（有界采样计数——上限 TimeWindowPolicy.QUERY_LIMIT_MAX，保守少报方向安全） */
    val itemCount: Int,
    /** 窗内频道数（横截面广度） */
    val channelCount: Int,
)

/**
 * 推送择年：周年日「有内容才推」的判定与择优。
 *  - 条数 <[MIN_PUSH_ITEMS] 的年轮不推（宁缺勿滥：一两条存货不成「在传」）；
 *  - 频道数（横截面广度）最多者优先——「N 个频道在传」的记忆浓度；
 *  - 平手取更久远年轮（回忆杀随距离加深）。
 */
fun pickYearForPush(stats: List<OnThisDayYearStat>): OnThisDayYearStat? =
    stats.asSequence()
        .filter { it.itemCount >= MIN_PUSH_ITEMS }
        .maxWithOrNull(compareBy({ it.channelCount }, { it.yearsAgo }))

// ---------------- 文案（用户向 + 脱敏） ----------------

/** 页面头部·周年版：「3 年前的今天，5 个频道在传这些」 */
fun anniversaryHeadline(yearsAgo: Int, channelCount: Int): String =
    "$yearsAgo 年前的今天，$channelCount 个频道在传这些"

/** 页面头部·任意日期版（日期选择器回看）：「2023 年 10 月 17 日，5 个频道在传这些」 */
fun dateHeadline(date: LocalDate, channelCount: Int): String =
    "${date.year} 年 ${date.monthValue} 月 ${date.dayOfMonth} 日，$channelCount 个频道在传这些"

/** RT-2 推送标题（渠道名「那年今日」；不含任何内容维度） */
const val PUSH_TITLE = "那年今日"

/** RT-2 推送正文：「3 年前的今天，你的频道传过 12 条内容，来看看」——只有年数与条数（脱敏红线） */
fun pushContentText(yearsAgo: Int, itemCount: Int): String =
    "$yearsAgo 年前的今天，你的频道传过 $itemCount 条内容，来看看"

// ---------------- R215 ② feed 那年今日卡（个人观看历史记忆） ----------------

/**
 * 「N 年前的今天你看过这些」的记忆载荷（feed 第 3~5 位注入的特殊卡数据）：
 * 与 [OnThisDayCuration]（订阅频道横截面）相对，本件是**个人观看历史**口径。
 */
data class OnThisDayMemory(
    /** 年轮（1..[MAX_YEARS]） */
    val yearsAgo: Int,
    /** 当年今日 ±1 天窗内看过的条目（观看时刻倒序=最近的记忆在前；全量进一次性播放流） */
    val items: List<VideoItem>,
)

/**
 * 从个人观看历史（history.json 的条目 + 观看时刻）提取那年今日记忆（纯函数）。
 *
 * - 年轮窗口与页面/推送同一 [onThisDayWindow]（目标日 ±1 天，本机时区口径）——
 *   「那天前后你在看什么」与「那天频道在传什么」同一日期语义；
 * - 择年：窗内条数最多者优先（记忆浓度）；平手取更久远年轮（回忆杀随距离加深，
 *   [pickYearForPush] 同口径）；
 * - 窗内 <[MEMORY_MIN_ITEMS] 条的年轮不成卡（宁缺勿滥：一两条撑不起一排缩略）；
 * - 全部年轮都无存货 → null（当天不出卡，次日再试——记忆数据历史久远，
 *   当天内自然稳定，幂等闸在调用方 prefs 层）。
 *
 * 注意：ts 是**观看时刻**毫秒（HistoryEntry.ts），不是条目发布时刻——
 * 「你看过这些」的语义锚是个人行为时间，不是内容发布时间。
 */
fun onThisDayHistoryMemory(
    history: List<Pair<VideoItem, Long>>,
    today: LocalDate,
    zone: ZoneId,
    maxYears: Int = MAX_YEARS,
): OnThisDayMemory? {
    if (history.isEmpty()) return null
    var best: OnThisDayMemory? = null
    for (y in 1..maxYears.coerceAtLeast(0)) {
        val w = onThisDayWindow(anniversaryDate(today, y), zone)
        val fromMs = w.fromSec * 1000L
        val toMs = w.toSecExcl * 1000L
        val items = history.asSequence()
            .filter { (_, ts) -> ts >= fromMs && ts < toMs }
            .sortedByDescending { it.second }
            .map { it.first }
            .toList()
        if (items.size >= MEMORY_MIN_ITEMS) {
            // 平手取更久远：y 递增遍历 + >= 替换（后到=更久远者胜）
            if (best == null || items.size >= best.items.size) {
                best = OnThisDayMemory(yearsAgo = y, items = items)
            }
        }
    }
    return best
}

// ---------------- 常量（那年今日特有；时间族共享常量一律引用 TimeWindowPolicy） ----------------

/** 年轮上限：1..5 年前各一屏（频控递进，别一次倾泻） */
const val MAX_YEARS = 5

/** 精选条数上限（页面一屏的「精选 N 条」） */
const val CURATED_LIMIT = 20

/**
 * 查询采样上限：取时间族公共硬顶（查询层取满候选池，精选截断交给
 * [curateOnThisDay] 的 [CURATED_LIMIT]——查询 limit=精选 limit 会把候选池过早
 * 掐到「窗内最新 N 条」，广告/同片/频道封顶折叠后所剩无几且广度失真）。
 */
const val CURATED_QUERY_LIMIT = TimeWindowPolicy.QUERY_LIMIT_MAX

/** 单频道条数上限：那年今日横截面广度闸（比时光机墙的 3 更紧——年轮要广度不要深度） */
const val MAX_PER_CHANNEL = 2

/** 推送最低存货条数：窗内条数低于此值不发通知（一两条不成「在传」） */
const val MIN_PUSH_ITEMS = 3

/** R215 ② feed 卡最低记忆条数：窗内低于此值不出卡（一两条撑不起一排缩略） */
const val MEMORY_MIN_ITEMS = 2
