package dev.tgfeed.core.feed

import java.util.concurrent.ConcurrentHashMap

/**
 * R204 TL-5 频道编年史（timeline-opportunities TL-5）：月历直方图 → 年历热力墙时间轴的
 * **纯折算层**（JVM 可单测）。数据两源：
 *  - 服务端月历 [getChatActivityDays]（日→媒体数，每非空月 2 请求，封顶 24 个月）
 *    ——年块热力与条数的**第一手**；
 *  - 本地基因 loadIndexGenome（最早帖时间）——**年表下界**（月历覆盖外的更老年代，
 *    年块照出（可穿越）但无热力，如实标「覆盖外」）。
 *
 * 口径：
 *  - 日 epoch 是**UTC 日**（网关月历口径，跨设备一致）；年归属按 UTC 年取整——
 *    与本地时区年在年初/年末交界处至多差一天帖子的归属，编年史语义容差内；
 *  - 热度色按**跨年全局峰值**归一（按年各自归一会把安静年染得跟高产年一样热——
 *    误导性口径，不用）；
 *  - 会话级内存缓存（[CACHE_TTL_MS] 6h）：月历请求是 Get 族但有 flood 面——
 *    同频道反复开关编年史不重拉（W3「缓存月历史」纪律）。
 */
object ChannelChronicle {

    /** 热力色档（5 档：0=当日无帖不画；4 档 alpha 与峰值比例挂钩） */
    private val HEAT_ALPHAS = floatArrayOf(0.22f, 0.42f, 0.62f, 0.85f)

    /** 会话缓存 TTL：与 ChannelGold 快照同节拍（小时级稳定事实，陈度无害） */
    const val CACHE_TTL_MS = 6L * 3600_000

    /** 年块数量上限（防畸形超老频道把抽屉撑成无尽列表；更老年代合入末块「更早」） */
    const val MAX_YEAR_BLOCKS = 12

    /**
     * 一个年份块（时间轴节点）：[hasCalendar]=该年在月历覆盖内（有热力墙）；
     * [dayCells]=该年 (UTC 日 epoch, 条数) 升序；[peakDaySec]=当年最热闹一天
     * （穿越落点：该日午时）；[count]=当年总条数（覆盖外=0）。
     */
    data class YearBlock(
        val year: Int,
        val yearsAgo: Int,
        val hasCalendar: Boolean,
        val count: Int,
        val dayCells: List<Pair<Int, Int>>,
        val peakDaySec: Int,
    )

    /** 折算产物：年块（新→旧）+ 覆盖月数 + 全局峰值（热度归一分母）+ 年表下界年 */
    data class Chronicle(
        val blocks: List<YearBlock>,
        val monthsCovered: Int,
        val globalMaxPerDay: Int,
        /** 年表最早年（月历与基因的下确界；null=两源皆无——诚实空态） */
        val earliestYear: Int?,
    )

    /**
     * 年块折算（纯函数）。
     * @param days 月历直方图（UTC 日 epoch → 条数，任意序）
     * @param monthsCovered 月历拉到的非空月数（覆盖口径文案用）
     * @param genomeMinDateSec 本地基因最早帖时间（null=未索引/未知）
     * @param nowMs 当前时刻
     */
    fun of(
        days: List<Pair<Int, Int>>,
        monthsCovered: Int,
        genomeMinDateSec: Long?,
        nowMs: Long = System.currentTimeMillis(),
    ): Chronicle {
        val nowYear = epochYear(nowMs / 1000L)
        val calMinDay = days.minOfOrNull { it.first }
        val calMinYear = calMinDay?.let { epochYear(it.toLong()) }
        val genomeYear = genomeMinDateSec?.takeIf { it > 0L }?.let { epochYear(it) }
        val earliestYear = listOfNotNull(calMinYear, genomeYear).minOrNull()
        val globalMax = days.maxOfOrNull { it.second } ?: 0
        if (earliestYear == null) return Chronicle(emptyList(), monthsCovered, globalMax, null)
        val byYear = HashMap<Int, MutableList<Pair<Int, Int>>>()
        for ((day, n) in days) byYear.getOrPut(epochYear(day.toLong())) { ArrayList() } += day to n
        val blocks = ArrayList<YearBlock>(nowYear - earliestYear + 1)
        var year = nowYear
        while (year >= earliestYear && blocks.size < MAX_YEAR_BLOCKS) {
            val cells = byYear[year]?.sortedBy { it.first } ?: emptyList()
            // 峰日：条数最多的一天（并列取最新——同热度下最新的更近语境）；无数据回落当年 1 月 1 日
            val peak = cells.maxWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
            val peakDaySec = ((peak?.first?.toLong() ?: utcYearStartSec(year)) + 43_200L).toInt()
            blocks += YearBlock(
                year = year,
                yearsAgo = nowYear - year,
                hasCalendar = cells.isNotEmpty(),
                count = cells.sumOf { it.second },
                dayCells = cells,
                peakDaySec = peakDaySec,
            )
            year--
        }
        return Chronicle(blocks, monthsCovered, globalMax, earliestYear)
    }

    /** UTC 年（epoch 秒 → 年；日历对齐 86_400 整除，无需 Calendar 对象） */
    private fun epochYear(epochSec: Long): Int {
        val rough = (epochSec / 31_557_600L + 1970L).toInt()
        // 31_557_600=平均年长，粗算后用年起点校正（闰年边界 ±1 天）
        val start = utcYearStartSec(rough)
        return if (epochSec < start) rough - 1 else rough
    }

    /** 某 UTC 年 1 月 1 日 0 点（epoch 秒；民用历闰年规则）。
     *  R204 TL-5 起 internal：年历热力墙的列对齐锚（周列从该年首个周日起算） */
    internal fun utcYearStartSec(year: Int): Long {
        var days = 0L
        for (y in 1970 until year) days += if (isLeap(y)) 366 else 365
        return days * 86_400L
    }

    private fun isLeap(y: Int): Boolean = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0

    /**
     * 日条数 → 热力色 alpha（0=不画）。档位按全局峰值比例分 4 档（GitHub 墙同思路：
     * 视觉档位而非连续渐变——一眼分清「有/旺/爆」三态即可，精确度交给数字标注）。
     */
    fun heatAlpha(count: Int, globalMax: Int): Float {
        if (count <= 0 || globalMax <= 0) return 0f
        return when {
            count >= globalMax -> HEAT_ALPHAS[3]
            count >= globalMax * 3 / 4 -> HEAT_ALPHAS[3]
            count >= globalMax / 2 -> HEAT_ALPHAS[2]
            count >= globalMax / 5 -> HEAT_ALPHAS[1]
            else -> HEAT_ALPHAS[0]
        }
    }

    /** 年块标题行文案：「2024 · 1,234 条」/ 覆盖外年块「2021 · 早年（详细档案未覆盖）」 */
    fun yearHeadline(b: YearBlock): String =
        if (b.hasCalendar) "${b.year} · ${DiscoveryGoldmine.fmtCount(b.count)} 条"
        else "${b.year} · 早年"

    /**
     * 开场摘要行：「从 2019 年到今天 · 详细档案近 24 个月」/ 月历缺席但基因在：
     * 「从 2019 年到今天」；两源皆无 → null（面板回落通用考古文案）。
     */
    fun summaryLine(c: Chronicle): String? {
        val earliest = c.earliestYear ?: return null
        val base = "从 $earliest 年到今天"
        return if (c.monthsCovered > 0) "$base · 详细档案近 ${c.monthsCovered} 个月" else base
    }

    // ---------------- 会话级月历缓存（每频道一次拉取，6h TTL） ----------------

    /** 缓存行：直方图 + 拉取时申请的月份上限 */
    data class CachedCalendar(val atMs: Long, val monthsCap: Int, val days: List<Pair<Int, Int>>) {
        /** 非空月数（(UTC 年, 月) 去重——与网关按月回溯循环同口径） */
        fun monthsCovered(): Int =
            days.map { (d, _) -> utcMonthIndex(d.toLong()) }.distinct().size.coerceAtMost(monthsCap)
    }

    /** UTC 月序号（year*12+month，0 起）：日历对齐（GregorianCalendar UTC），防平均月长漂移错计 */
    private fun utcMonthIndex(dayEpochSec: Long): Int {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = dayEpochSec * 1000L
        return cal.get(java.util.Calendar.YEAR) * 12 + cal.get(java.util.Calendar.MONTH)
    }

    private val cache = ConcurrentHashMap<Long, CachedCalendar>()

    fun cached(chatId: Long, nowMs: Long = System.currentTimeMillis()): CachedCalendar? =
        cache[chatId]?.takeIf { nowMs - it.atMs < CACHE_TTL_MS }

    /** 不看 TTL 直取缓存行（拉取失败时的陈值先回用；null=从没拉到过） */
    fun peek(chatId: Long): CachedCalendar? = cache[chatId]

    fun put(chatId: Long, monthsCap: Int, days: List<Pair<Int, Int>>, nowMs: Long = System.currentTimeMillis()) {
        cache[chatId] = CachedCalendar(nowMs, monthsCap, days)
    }
}
