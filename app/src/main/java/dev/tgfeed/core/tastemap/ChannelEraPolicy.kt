package dev.tgfeed.core.tastemap

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * R248 编年史纯函数一期（R246 设计 §3.2 「ChannelEraPolicy 纯函数五件」的一期三件
 * ——判词/内容演化/发布节奏；**用户已确认编年史融入星图信息卡，不做独立页**）。
 *
 * 设计北极星（design-chronicle §1.3）：**每一屏是否在帮用户回答「这个频道值不值得
 * 订阅/追更」**——判词行 10 秒读完频道一生（结论先行），证据卷可展开。
 * 用户修正落实（r246_notes）：编年史≠人物传记——**段命名中性事实制**（「2023 上
 * 半年 · 高产 · 转向短视频」），成长期/衰落期一类传记词全部禁用。
 *
 * 一期卷集（任务书口径：A/B/C 卷=判词/内容演化/发布节奏；巅峰作品/资源里程碑/
 * 沉寂复活/互联档案数据薄**先隐藏**——代表作传播证据等 W6 forwardCount 入库）：
 *  - **判词拼装** [digest]：`{取向} · {节奏型} · 高峰在{峰段} · {近期趋势}`——字段
 *    缺席即跳段（无字段不出段，R246 诚实纪律）；
 *  - **内容演化** [evolutionOf]：半年桶时代行（三色构成）+[transitionsOf] 主导类型
 *    翻转点（占比摆幅 ≥[TRANSITION_SWING_PCT] 才算转型——不冒充）；
 *  - **发布节奏** [rhythmOf]：日更/周报/爆发/季节四型+样本地板（覆盖 <[RHYTHM_MIN_
 *    COVERAGE_DAYS] 天或 <[RHYTHM_MIN_ITEMS] 条不判型——小样本不出伪结论，基因鉴定
 *    GENOME_MIN_ITEMS 先例同款）。
 *
 * 输入全部来自本地深索引频道域聚合（调用侧 SQL，毫秒级）——零网络零 Search 族
 * （R184 红线）；**纯函数零 Android 依赖**（GregorianCalendar+UTC=确定性，JVM 单测
 * 可跑）。落位说明：R246 设计稿定 core/feed/，本代理=Galaxy 区按 ARCHITECTURE 地盘
 * 规则落 core/tastemap（Galaxy 自由区）；纯函数零依赖，Feed 侧编年史施工时可直接
 * 只读消费。
 */
object ChannelEraPolicy {

    // ==================== 量纲与判据（单一出处，单测锚定） ====================

    /** 半年桶长（秒）：365.25 天/2——SQL 侧 GROUP BY 同式（改就一起改） */
    const val HALF_YEAR_SEC = 15_778_800L

    /** 节奏判型样本地板：覆盖天数下限（<90 天不判型——诚实纪律） */
    const val RHYTHM_MIN_COVERAGE_DAYS = 90

    /** 节奏判型样本地板：总条数下限 */
    const val RHYTHM_MIN_ITEMS = 30

    /** 日更型判据：活跃天占比（≥60% 的日子有帖） */
    const val DAILY_RATE = 0.60f

    /** 周报型判据：相邻发帖日中位间隔（天）上界（≤16=周~双周节奏） */
    const val WEEKLY_MEDIAN_GAP_DAYS = 16

    /** 季节型判据：月-历年聚集——发帖量 top4 月份份额下限 */
    const val SEASONAL_TOP4_SHARE = 0.55f

    /** 季节型判据：覆盖天数下限（跨 ≥1.5 年才谈季节周期） */
    const val SEASONAL_MIN_COVERAGE_DAYS = 540

    /** 转型判据：主导类型占比摆幅（百分点）——低于此不算转型（不冒充） */
    const val TRANSITION_SWING_PCT = 30

    /** 近期趋势窗（天）：近 90 天 vs 前 90 天 */
    const val TREND_WINDOW_DAYS = 90

    /** 近期趋势判据：提速/放缓倍率 */
    const val TREND_UP = 1.5f
    const val TREND_DOWN = 1f / 1.5f

    /** 演化卷时代行上限（超出把最旧段合并成「更早」——防老频道 20+ 行淹没） */
    const val EVOLUTION_MAX_SEGS = 8

    /** 月度柱条回看月数上限 */
    const val MONTHLY_MAX = 24

    /** 周内峰判据：top 星期份额下限（均匀 1/7≈0.143 的 1.7 倍） */
    const val WEEKDAY_PEAK_SHARE = 0.24f

    // ==================== 输入模型 ====================

    /**
     * 半年桶（SQL 聚合行：`dateEpochSec/HALF_YEAR_SEC` 分组，[startSec]=桶起点秒，
     * mediaType 0=视频/1=图片/其余=音频——DeepSearchIndex 基因口径同源）。
     */
    class EraBucket(val startSec: Long, val total: Int, val video: Int, val photo: Int, val audio: Int)

    /** 日直方图行（`dateEpochSec/86400` 分组；epochDay=Unix 天序） */
    class DayCount(val epochDay: Int, val count: Int)

    /** 一期档案输入（全本地索引；两表升序） */
    class EraInput(val buckets: List<EraBucket>, val days: List<DayCount>)

    // ==================== 输出模型 ====================

    /** 节奏四型+样本不足（用户语言：日报/周报/爆发/季节——R246 §3.2.3） */
    enum class Rhythm(val label: String) {
        DAILY("日更型"),
        WEEKLY("周报型"),
        BURSTY("爆发型"),
        SEASONAL("季节型"),
        THIN("样本不足"),
    }

    /** 演化卷时代行（≤[EVOLUTION_MAX_SEGS] 段；isPeak=峰段标记） */
    class EvoSeg(val label: String, val video: Int, val photo: Int, val audio: Int, val total: Int, val isPeak: Boolean)

    /** 律动卷月度柱（month1=1..12） */
    class MonthCount(val year: Int, val month1: Int, val count: Int)

    /** 一期档案摘要（判词先行+两卷证据；verdict=null=数据太薄不出判词——UI 回落基因行） */
    class EraDigest(
        val verdict: String?,
        val orientation: String?,
        val rhythm: Rhythm,
        val peakLabel: String?,
        val trendLabel: String?,
        val evolution: List<EvoSeg>,
        val transitions: List<String>,
        val monthly: List<MonthCount>,
        val weekdayLabel: String?,
        val totalCount: Int,
        val coverageDays: Int,
    )

    // ==================== 节奏判型（卷②核心） ====================

    /**
     * 发布节奏判型（纯函数，判据全本对象常量=单一出处）：
     *  - 覆盖 <90 天 ∨ 总量 <30 条 → [Rhythm.THIN]（样本不足不出伪结论）；
     *  - 活跃天/覆盖 ≥[DAILY_RATE] → [Rhythm.DAILY]（日更型）；
     *  - 相邻发帖日中位间隔 ≤[WEEKLY_MEDIAN_GAP_DAYS] → [Rhythm.WEEKLY]（周~双周更）；
     *  - 跨 ≥[SEASONAL_MIN_COVERAGE_DAYS] 天且月-历年 top4 份额 ≥[SEASONAL_TOP4_SHARE]
     *    → [Rhythm.SEASONAL]（季节型：发帖聚在固定的几个月份）；
     *  - 其余 → [Rhythm.BURSTY]（爆发型=方差离群兜底：长静默+短爆发）。
     * 检查序=日更→周报→季节→爆发（前序命中即返——日更频道不会误判季节）。
     */
    fun rhythmOf(days: List<DayCount>): Rhythm {
        if (days.size < 2) return Rhythm.THIN
        val first = days.first().epochDay
        val last = days.last().epochDay
        val coverage = last - first
        val total = days.sumOf { it.count }
        if (coverage < RHYTHM_MIN_COVERAGE_DAYS || total < RHYTHM_MIN_ITEMS) return Rhythm.THIN
        val rate = days.size.toFloat() / (coverage + 1)
        if (rate >= DAILY_RATE) return Rhythm.DAILY
        val gaps = IntArray(days.size - 1) { i -> days[i + 1].epochDay - days[i].epochDay }
        gaps.sort()
        val medianGap = gaps[gaps.size / 2]
        if (medianGap in 1..WEEKLY_MEDIAN_GAP_DAYS) return Rhythm.WEEKLY
        if (coverage >= SEASONAL_MIN_COVERAGE_DAYS && monthOfYearTop4Share(days) >= SEASONAL_TOP4_SHARE) {
            return Rhythm.SEASONAL
        }
        return Rhythm.BURSTY
    }

    /** 月-历年聚集：全部发帖按日历月份（1..12 归并各年）聚合，top4 月份份额 */
    internal fun monthOfYearTop4Share(days: List<DayCount>): Float {
        if (days.isEmpty()) return 0f
        val cal = utcCalendar()
        val byMonth = IntArray(12)
        var total = 0
        for (d in days) {
            byMonth[monthOfYearOf(d.epochDay, cal)] += d.count
            total += d.count
        }
        if (total == 0) return 0f
        var acc = 0
        val desc = byMonth.sortedArrayDescending()
        for (i in 0 until 4) acc += desc[i]
        return acc.toFloat() / total
    }

    // ==================== 内容演化（卷①核心） ====================

    /**
     * 演化卷时代行：半年桶 → 时代段（≤[EVOLUTION_MAX_SEGS]：超出=最旧合并「更早」
     * +最新 7 段）；峰段（总量最大桶）标记 isPeak。
     */
    fun evolutionOf(buckets: List<EraBucket>): List<EvoSeg> {
        if (buckets.isEmpty()) return emptyList()
        val peakIdx = buckets.indices.maxByOrNull { buckets[it].total } ?: return emptyList()
        val idxBase: Int
        var mergedHead: EraBucket? = null
        if (buckets.size > EVOLUTION_MAX_SEGS) {
            idxBase = buckets.size - (EVOLUTION_MAX_SEGS - 1)
            val head = buckets.subList(0, idxBase)
            mergedHead = EraBucket(
                head.first().startSec,
                head.sumOf { it.total }, head.sumOf { it.video }, head.sumOf { it.photo }, head.sumOf { it.audio },
            )
        } else {
            idxBase = 0
        }
        val out = ArrayList<EvoSeg>(EVOLUTION_MAX_SEGS)
        var peakDone = false
        mergedHead?.let {
            // 合并头段的峰标记：峰桶被并入头段时峰随头段（峰在更早时代也是重要信息）
            out.add(EvoSeg("更早", it.video, it.photo, it.audio, it.total, peakIdx < idxBase))
            if (peakIdx < idxBase) peakDone = true
        }
        for (i in idxBase until buckets.size) {
            val b = buckets[i]
            val isPeak = !peakDone && i == peakIdx
            if (isPeak) peakDone = true
            out.add(EvoSeg(halfYearLabel(b.startSec), b.video, b.photo, b.audio, b.total, isPeak))
        }
        return out
    }

    /**
     * 转型点提取：相邻时代段主导类型翻转且占比摆幅 ≥[TRANSITION_SWING_PCT] 百分点
     * ——「2024上半年起转向视频（图文 45%→18%）」句式（中性事实制，无传记词）。
     * 最多 2 句（最新两个转型点——老频道 20+ 转型不淹没）。
     */
    fun transitionsOf(buckets: List<EraBucket>): List<String> {
        if (buckets.size < 2) return emptyList()
        val out = ArrayList<String>(2)
        for (i in 1 until buckets.size) {
            val prev = buckets[i - 1]
            val cur = buckets[i]
            val pDom = dominantOf(prev)
            val cDom = dominantOf(cur)
            if (pDom == cDom) continue
            val pPct = pctOf(prev, pDom)
            val cPct = pctOf(cur, cDom)
            // 摆幅：新主导从旧水平的抬升量（新主导 ≥ 旧主导+30 pct 才叫转型）
            val curOldDomPct = pctOf(cur, pDom)
            if (cPct - curOldDomPct >= TRANSITION_SWING_PCT) {
                out.add(
                    "${halfYearLabel(cur.startSec)}起转向${typeLabelOf(cDom)}" +
                        "（${typeLabelOf(pDom)} $pPct%→$curOldDomPct%）",
                )
            }
        }
        return out.takeLast(2)
    }

    // ==================== 判词与摘要（卷A核心） ====================

    /**
     * 一期档案摘要（判词拼装+两卷证据；纯函数——同输入同输出）：
     * 判词=`{取向}·{节奏型}·高峰在{峰段}·{趋势}` 各段缺席即跳；全薄=null（UI 回落）。
     */
    fun digest(input: EraInput): EraDigest {
        val buckets = input.buckets
        val days = input.days
        val total = buckets.sumOf { it.total }.coerceAtLeast(days.sumOf { it.count })
        val coverage = if (days.size >= 2) days.last().epochDay - days.first().epochDay else 0
        val rhythm = rhythmOf(days)
        val orientation = orientationOf(buckets)
        val peakLabel = buckets.indices.maxByOrNull { buckets[it].total }
            ?.let { halfYearLabel(buckets[it].startSec) }
        val trend = trendLabelOf(days)
        val weekday = weekdayLabelOf(days)
        val verdictParts = buildList {
            orientation?.let { add(it) }
            if (rhythm != Rhythm.THIN) add(rhythm.label)
            peakLabel?.let { if (total >= RHYTHM_MIN_ITEMS) add("高峰在$it") }
            trend?.let { add(it) }
        }
        val verdict = if (verdictParts.isEmpty()) null else verdictParts.joinToString(" · ")
        return EraDigest(
            verdict = verdict,
            orientation = orientation,
            rhythm = rhythm,
            peakLabel = peakLabel,
            trendLabel = trend,
            evolution = evolutionOf(buckets),
            transitions = transitionsOf(buckets),
            monthly = monthlyOf(days, MONTHLY_MAX),
            weekdayLabel = weekday,
            totalCount = total,
            coverageDays = coverage,
        )
    }

    /** 内容取向（判词首段）：单一类型 ≥50% →「视觉向/图文向/听觉向」；否则「混合向」 */
    internal fun orientationOf(buckets: List<EraBucket>): String? {
        val v = buckets.sumOf { it.video }
        val p = buckets.sumOf { it.photo }
        val a = buckets.sumOf { it.audio }
        val t = v + p + a
        if (t <= 0) return null
        return when {
            v * 2 >= t -> "视觉向"
            p * 2 >= t -> "图文向"
            a * 2 >= t -> "听觉向"
            else -> "混合向"
        }
    }

    /** 近期趋势（近 [TREND_WINDOW_DAYS] 天 vs 前 90 天，相对最后发帖日）：提速/放缓 */
    internal fun trendLabelOf(days: List<DayCount>): String? {
        if (days.size < 2) return null
        val lastDay = days.last().epochDay
        var recent = 0
        var prior = 0
        for (d in days) {
            when {
                d.epochDay > lastDay - TREND_WINDOW_DAYS -> recent += d.count
                d.epochDay > lastDay - 2 * TREND_WINDOW_DAYS -> prior += d.count
            }
        }
        if (recent + prior < RHYTHM_MIN_ITEMS) return null
        return when {
            prior == 0 && recent > 0 -> "近期提速"
            prior == 0 -> null
            recent.toFloat() / prior >= TREND_UP -> "近期提速"
            recent.toFloat() / prior <= TREND_DOWN -> "近期放缓"
            else -> null
        }
    }

    /** 周内惯例（律动卷副行）：top 星期份额 ≥[WEEKDAY_PEAK_SHARE] 才出「惯例周X更新」 */
    internal fun weekdayLabelOf(days: List<DayCount>): String? {
        if (days.isEmpty()) return null
        val cal = utcCalendar()
        val byWeekday = IntArray(7)
        var total = 0
        for (d in days) {
            byWeekday[weekdayOf(d.epochDay, cal)] += d.count
            total += d.count
        }
        if (total <= 0) return null
        var best = 0
        for (i in 1 until 7) if (byWeekday[i] > byWeekday[best]) best = i
        val share = byWeekday[best].toFloat() / total
        if (share < WEEKDAY_PEAK_SHARE) return null
        return "惯例${WEEKDAY_NAMES[best]}更新"
    }

    /** 月度柱条（日直方图→日历月聚合，最新 [max] 个月升序） */
    internal fun monthlyOf(days: List<DayCount>, max: Int): List<MonthCount> {
        if (days.isEmpty()) return emptyList()
        val cal = utcCalendar()
        val acc = LinkedHashMap<Long, MonthCount>()
        for (d in days) {
            cal.clear()
            cal.timeInMillis = d.epochDay * 86_400_000L
            val y = cal.get(Calendar.YEAR)
            val m = cal.get(Calendar.MONTH)
            val key = y * 12L + m
            val cur = acc[key]
            if (cur == null) acc[key] = MonthCount(y, m + 1, d.count) else acc[key] = MonthCount(y, m + 1, cur.count + d.count)
        }
        return acc.values.toList().takeLast(max)
    }

    // ==================== 日期换算（UTC=确定性，JVM/Android 同结果） ====================

    /** 半年桶标签：「2024上半年」/「2024下半年」（中性事实制——无时代传记词） */
    fun halfYearLabel(startSec: Long): String {
        val cal = utcCalendar()
        cal.clear()
        cal.timeInMillis = startSec * 1000L
        val y = cal.get(Calendar.YEAR)
        return if (cal.get(Calendar.MONTH) < 6) "${y}上半年" else "${y}下半年"
    }

    private fun utcCalendar(): GregorianCalendar = GregorianCalendar(TimeZone.getTimeZone("UTC"))

    /** epochDay → 日历月份（0..11，UTC） */
    internal fun monthOfYearOf(epochDay: Int, cal: GregorianCalendar): Int {
        cal.clear()
        cal.timeInMillis = epochDay * 86_400_000L
        return cal.get(Calendar.MONTH)
    }

    /** epochDay → 星期（0=周日..6=周六，UTC——1970-01-01=周四锚定） */
    internal fun weekdayOf(epochDay: Int, cal: GregorianCalendar): Int = ((epochDay + 4) % 7 + 7) % 7

    /** 星期用户语言（0=周日） */
    private val WEEKDAY_NAMES = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    // ==================== 类型小工具 ====================

    /** 主导类型（0=视频/1=图片/2=音频；总量 0=-1） */
    private fun dominantOf(b: EraBucket): Int = when {
        b.total <= 0 -> -1
        b.video >= b.photo && b.video >= b.audio -> 0
        b.photo >= b.audio -> 1
        else -> 2
    }

    /** 类型占比（百分整数；分母 0 → 0） */
    private fun pctOf(b: EraBucket, type: Int): Int {
        if (b.total <= 0) return 0
        val v = when (type) {
            0 -> b.video
            1 -> b.photo
            else -> b.audio
        }
        return v * 100 / b.total
    }

    /** 类型用户语言 */
    private fun typeLabelOf(type: Int): String = when (type) {
        0 -> "视频"
        1 -> "图文"
        else -> "音频"
    }

    // ==================== R251 四卷新增（③巅峰作品④资源里程碑⑤沉寂复活⑥互联——只加不改） ====================

    /** ⑤ 断更标记门槛（任务书口径：相邻发帖日间隔 >7 天=断更标记） */
    const val GAP_MARK_DAYS = 7

    /** ⑤ 沉寂独立行显示档（天）：7~30 天轻停更只归并计数，不出独立行 */
    const val DORMANT_ENTRY_MIN_DAYS = 30

    /** ⑤ 沉寂独立行条数上限（防老频道断更行淹没） */
    const val DORMANT_MAX_ENTRIES = 3

    /** ③ 剧集链出代表作的链长下限（<5 集不冒充「代表作」） */
    const val CHAIN_MIN_EPISODES = 5

    /** ③ 剧集链代表作上限（③④卷共用：开篇里程碑同源同限） */
    const val CHAIN_PEAK_MAX = 2

    /** ③ 峰作品全卷上限（每时代 ≤1 条、全卷 ≤5——R246 §6.2.4 防淹没口径） */
    const val PEAK_WORKS_MAX = 5

    /** ③ 时长离群判据：最短时长下限（秒——短平快频道不出伪「镇馆」） */
    const val DURATION_OUTLIER_MIN_SEC = 600

    /** ③ 时长离群判据：频道中位时长倍率 */
    const val DURATION_OUTLIER_X = 3f

    /** ④ 里程碑步进（第 N 条；频道 COUNT 门内才查——数组只读约定，调用方不改） */
    val MILESTONE_STEPS = intArrayOf(100, 500, 1000, 5000, 10000, 50000)

    /** ④ 里程碑时间轴条数上限 */
    const val MILESTONE_MAX = 8

    /** ⑥ 互联行上限（与相邻恒星 pills top5 同族，多一格给长名） */
    const val INTERLINK_MAX = 6

    // ==================== R251 输入模型 ====================

    /**
     * ③④ 卷条目行原料（频道域索引行的最小投影——调用侧 SQL 只查需要的列，
     * [title]=标题行（parsedTitle/caption 首行由调用侧折好）；[dateSec]=0=无日期证据）。
     */
    class EraItemRow(
        val messageId: Long,
        val dateSec: Long,
        val mediaType: Int,
        val durationSec: Int,
        val albumId: Long,
        val title: String,
    )

    /**
     * ③ 峰作品（代表作——**本地代理证据**：峰日合集/剧集链/时长离群；W6 forwardCount
     * 入库前不冒充传播数据，依据行必须写明证据类型——R246 §3.4 钥匙缺口挂账同款）。
     */
    class PeakWork(
        val dateSec: Long,
        val messageId: Long,
        val mediaType: Int,
        val era: String,
        val headline: String,
        val reason: String,
    )

    /** ④ 里程碑时间轴行（label=「创刊」/「第 N 条」/「系列开篇」；sub=对应内容行） */
    class Milestone(
        val dateSec: Long,
        val messageId: Long,
        val mediaType: Int,
        val label: String,
        val sub: String?,
    )

    /**
     * ⑤ 沉寂段：标记=相邻发帖日间隔 >[GAP_MARK_DAYS]；行=days ≥[DORMANT_ENTRY_MIN_DAYS]。
     * [lastPostDay]/[returnDay] 为 epochDay；未回归段 [returned]=false（returnDay=now）。
     */
    class Hiatus(
        val lastPostDay: Int,
        val returnDay: Int,
        val days: Int,
        val returned: Boolean,
    )

    /** ③④ 剧集链证据（episodeChains 的链事实投影：链长+首集事实） */
    class ChainLead(
        val episodes: Int,
        val firstDateSec: Long,
        val firstMessageId: Long,
        val firstTitle: String,
    )

    // ==================== ⑤ 沉寂与复活（纯函数核心） ====================

    /**
     * 断更标记提取（升序日直方图，纯函数）：
     *  - 相邻发帖日间隔 >[GAP_MARK_DAYS] → 一段断更（沉寂前最后帖日→回归日）；
     *  - 末段悬空（最后帖日距 [nowEpochDay] >7 天）→ 至今未归段；
     *  - [lastKnownActivityDay]（金矿最近活动日，0=未知）兜索引滞后：末段起点取
     *    直方图末日与它的较大者——索引没爬到的新帖不误判「已沉寂」（诚实口径）。
     */
    fun hiatusesOf(days: List<DayCount>, nowEpochDay: Int, lastKnownActivityDay: Int = 0): List<Hiatus> {
        if (days.isEmpty()) return emptyList()
        val out = ArrayList<Hiatus>(days.size / 8 + 1)
        for (i in 1 until days.size) {
            val gap = days[i].epochDay - days[i - 1].epochDay
            if (gap > GAP_MARK_DAYS) {
                out += Hiatus(days[i - 1].epochDay, days[i].epochDay, gap, true)
            }
        }
        // 末段（当前态）：金矿最近活动日兜底索引滞后
        val lastDay = maxOf(days.last().epochDay, lastKnownActivityDay)
        val trailing = nowEpochDay - lastDay
        if (trailing > GAP_MARK_DAYS) {
            out += Hiatus(lastDay, nowEpochDay, trailing, false)
        }
        return out
    }

    /** ⑤ 沉寂独立行：≥[DORMANT_ENTRY_MIN_DAYS] 天、时长降序取 [DORMANT_MAX_ENTRIES]（未归段在列） */
    fun dormantEntriesOf(hiatuses: List<Hiatus>): List<Hiatus> =
        hiatuses.filter { it.days >= DORMANT_ENTRY_MIN_DAYS }
            .sortedByDescending { it.days }
            .take(DORMANT_MAX_ENTRIES)

    /** ⑤ 摘要行（卷头）：「断更 3 次 · 最长 156 天」（标记口径计数）；无标记=null */
    fun hiatusSummaryOf(hiatuses: List<Hiatus>): String? {
        if (hiatuses.isEmpty()) return null
        val longest = hiatuses.maxOf { it.days }
        val unreturned = hiatuses.count { !it.returned }
        return buildString {
            append("断更 ${hiatuses.size} 次 · 最长 ${longest} 天")
            if (unreturned > 0) append(" · 当前未归")
        }
    }

    /**
     * ⑤ 行文案：「2025-03~2025-08 沉寂 156 天 → 2025-08-15 回归」；同月沉寂省区间
     * （「2025-03 沉寂 45 天」）；未归段尾「→ 至今未归」（任务书示例句式）。
     */
    fun hiatusLineOf(h: Hiatus): String {
        val cal = utcCalendar()
        val from = monthLabelOf(h.lastPostDay, cal)
        val to = monthLabelOf(h.returnDay, cal)
        val span = if (from != to) "$from~$to" else from
        val tail = if (h.returned) "${dateLabelOf(h.returnDay, cal)} 回归" else "至今未归"
        return "$span 沉寂 ${h.days} 天 → $tail"
    }

    // ==================== ③ 巅峰作品（本地代理证据，纯函数核心） ====================

    /**
     * 峰作品选择（三证据分层，去重保先；时间升序 ≤[PEAK_WORKS_MAX]）：
     *  1. 峰日合集（日直方图 globalMax 日）：最大相册组 ≥3 张取组首条，否则该日首条
     *     ——依据「峰日 N 条（全史最密一天）」；
     *  2. 剧集链首集（链长 ≥[CHAIN_MIN_EPISODES] 的 top[CHAIN_PEAK_MAX] 条链）
     *     ——依据「N 集连播链的首集」；
     *  3. 时长离群（频道内最长 ≥max([DURATION_OUTLIER_MIN_SEC], 中位×
     *     [DURATION_OUTLIER_X])）——依据「时长 X · 频道中位 Y 的 N 倍」。
     * 同一条命中多证据只保留首个（证据强度序）；依据行写明本地证据类型（不冒充传播数据）。
     */
    fun peakWorksOf(
        medianDurationSec: Int,
        peakDayCount: Int,
        peakDayRows: List<EraItemRow>,
        durationTop: EraItemRow?,
        chainLeads: List<ChainLead>,
    ): List<PeakWork> {
        val out = ArrayList<PeakWork>(6)
        val used = HashSet<Long>(6)
        // 证据一：峰日合集（相册组大小=「最密一天里最重的一摞」）
        if (peakDayRows.isNotEmpty() && peakDayCount > 0) {
            val byAlbum = HashMap<Long, MutableList<EraItemRow>>()
            for (r in peakDayRows) {
                if (r.albumId != 0L) byAlbum.getOrPut(r.albumId) { ArrayList(8) }.add(r)
            }
            val album = byAlbum.values.maxByOrNull { it.size }
            val lead = if (album != null && album.size >= 3) {
                album.minByOrNull { it.messageId } // 相册发送序= messageId 升序，组首条
            } else {
                peakDayRows.minByOrNull { it.messageId } // 峰日首条
            }
            if (lead != null && used.add(lead.messageId)) {
                val albumNote = if (album != null && album.size >= 3) " · 相册 ${album.size} 张" else ""
                out += peak(lead, "峰日合集", "峰日 $peakDayCount 条（全史最密一天）$albumNote · 本地证据")
            }
        }
        // 证据二：剧集链首集（追剧机器的链=「最多人追着看」的本地代理）
        for (c in chainLeads.sortedByDescending { it.episodes }.take(CHAIN_PEAK_MAX)) {
            if (c.episodes < CHAIN_MIN_EPISODES) break // 降序数组：后面都更短
            if (!used.add(c.firstMessageId)) continue
            out += peak(
                EraItemRow(c.firstMessageId, c.firstDateSec, 0, 0, 0L, c.firstTitle),
                "剧集链 ${c.episodes} 集",
                "频道最长连播链 · 首集（本地样本证据）",
            )
        }
        // 证据三：时长离群（「镇馆」长片）
        val top = durationTop
        if (top != null && top.durationSec > 0 && medianDurationSec > 0) {
            val threshold = maxOf(DURATION_OUTLIER_MIN_SEC, (medianDurationSec * DURATION_OUTLIER_X).toInt())
            if (top.durationSec >= threshold && used.add(top.messageId)) {
                val x = top.durationSec.toFloat() / medianDurationSec
                out += peak(
                    top,
                    "镇馆长片",
                    "时长 ${durationLabelOf(top.durationSec)} · 频道中位 ${durationLabelOf(medianDurationSec)} 的 ${"%.1f".format(x)} 倍（本地证据）",
                )
            }
        }
        return out.sortedBy { it.dateSec }.take(PEAK_WORKS_MAX)
    }

    private fun peak(r: EraItemRow, headline: String, reason: String): PeakWork =
        PeakWork(r.dateSec, r.messageId, r.mediaType, halfYearLabel(r.dateSec), headline, reason)

    /** ③ 时长用户语言：「4.2 小时」/「42 分钟」/「48 秒」 */
    fun durationLabelOf(sec: Int): String = when {
        sec >= 3600 -> "%.1f 小时".format(sec / 3600f)
        sec >= 60 -> "${sec / 60} 分钟"
        else -> "$sec 秒"
    }

    // ==================== ④ 资源里程碑（纯函数核心） ====================

    /**
     * 里程碑时间轴（时间升序 ≤[MILESTONE_MAX]）：
     *  - 创刊（[first]=messageId 升序第 1 条）；
     *  - 第 N 条（[nth] 只含 ≤[totalItems] 的步进已查行；「频道规模节点」）；
     *  - 系列开篇（链长 ≥[CHAIN_MIN_EPISODES] 的 top[CHAIN_PEAK_MAX] 条链首——
     *    「专题系列开篇」）。
     */
    fun milestonesOf(
        first: EraItemRow?,
        totalItems: Int,
        nth: List<Pair<Int, EraItemRow?>>,
        chainLeads: List<ChainLead>,
    ): List<Milestone> {
        val out = ArrayList<Milestone>(MILESTONE_MAX + 2)
        if (first != null) {
            out += Milestone(first.dateSec, first.messageId, first.mediaType, "创刊", "频道第一条${titleNoteOf(first)}")
        }
        for ((n, row) in nth) {
            if (row == null || n > totalItems) continue
            out += Milestone(row.dateSec, row.messageId, row.mediaType, "第 $n 条", row.title.ifBlank { null })
        }
        for (c in chainLeads.sortedByDescending { it.episodes }.take(CHAIN_PEAK_MAX)) {
            if (c.episodes < CHAIN_MIN_EPISODES) break
            out += Milestone(
                c.firstDateSec, c.firstMessageId, 0, "系列开篇",
                "${c.firstTitle.ifBlank { "剧集链" }} · ${c.episodes} 集连播",
            )
        }
        return out.sortedWith(compareBy({ it.dateSec }, { it.messageId })).take(MILESTONE_MAX)
    }

    private fun titleNoteOf(r: EraItemRow): String = if (r.title.isBlank()) "" else " · ${r.title}"

    // ==================== ⑥ 互联档案（文案单口——分类账由装配侧算） ====================

    /**
     * 互联行文案（主类计数前置、只列非零类）：「互转 23 次 · 互链 4 · 共现 12」；
     * 全零返回空串（调用方跳行）。语义：互转=转发边（forward_src/dst）、互链=t.me
     * 引用（link/comment）、共现=同会话观看序列（co_watch）——R246 互联卷的既有
     * 图谱口径，传播计数（W6）入库前不冒充。
     */
    fun interlinkLineOf(forward: Int, link: Int, coWatch: Int): String {
        val cats = listOf("互转" to forward, "互链" to link, "共现" to coWatch)
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
        if (cats.isEmpty()) return ""
        return cats.mapIndexed { i, (label, n) ->
            if (i == 0) "$label $n 次" else "$label $n"
        }.joinToString(" · ")
    }

    // ==================== R251 日期换算（UTC=确定性，与既有 halfYearLabel 同族） ====================

    /** epochDay → 「2025-03」（月标签——沉寂行区间用） */
    internal fun monthLabelOf(epochDay: Int, cal: GregorianCalendar): String {
        cal.clear()
        cal.timeInMillis = epochDay * 86_400_000L
        return "%04d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
    }

    /** epochDay → 「2025-08-15」（日期标签——回归日用） */
    internal fun dateLabelOf(epochDay: Int, cal: GregorianCalendar): String {
        cal.clear()
        cal.timeInMillis = epochDay * 86_400_000L
        return "%04d-%02d-%02d".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
    }

    /** 秒 → 「2021-05-30」（里程碑时间轴日期；0=空串——无日期证据不出日期） */
    fun dateLabelOf(sec: Long): String {
        if (sec <= 0L) return ""
        val cal = utcCalendar()
        cal.clear()
        cal.timeInMillis = sec * 1000L
        return "%04d-%02d-%02d".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
    }

    // ==================== R250 信息卡节奏微图新增（只加不改——共享件边界与 R251 四卷同款纪律） ====================

    /** 一天槽位数（24h 小时直方图定长 24——单一定义处） */
    const val HOURS_PER_DAY = 24

    /**
     * 24h 小时直方图行（SQL 聚合：`(dateEpochSec+tzOffsetSec)/3600%24` 分组——
     * **本地时区口径**：用户读「22 时最活跃」必须对本地钟点才有意义；tz 偏移秒由
     * 调用侧现算传入 bind 参数（历史帖跨 DST 的偏移差异为可容忍近似，不冒充）。
     */
    class HourCount(val hour0to23: Int, val count: Int)

    /** 24 槽小时直方图（小时缺槽=0；hour 域外行防御丢弃——脏数据不进图） */
    fun hourHistogramOf(rows: List<HourCount>): IntArray {
        val out = IntArray(HOURS_PER_DAY)
        for (r in rows) {
            val h = r.hour0to23
            if (h in 0 until HOURS_PER_DAY) out[h] += r.count
        }
        return out
    }

    /**
     * 峰时标签（本地钟点口径）：总量 <[RHYTHM_MIN_ITEMS] 不出（小样本不出伪结论
     * ——节奏判型同一纪律）；平峰取最小钟点（确定性破平）。例：「22 时最活跃」。
     */
    fun peakHourLabelOf(hours: IntArray): String? {
        if (hours.size != HOURS_PER_DAY) return null
        var total = 0
        for (c in hours) total += c
        if (total < RHYTHM_MIN_ITEMS) return null
        var best = 0
        for (h in 1 until HOURS_PER_DAY) if (hours[h] > hours[best]) best = h
        if (hours[best] <= 0) return null
        return "$best 时最活跃"
    }

    // ==================== R253 信息卡热力条点选（只加不改——共享件边界同款纪律） ====================

    /**
     * 时段范围标签（本地钟点口径，与 [peakHourLabelOf]/直方图同时区）：「22:00~23:00」
     * （23 时档收口 24:00）。域外=null（防御——脏数据不进标签）。
     */
    fun hourRangeLabelOf(hour0to23: Int): String? =
        if (hour0to23 in 0 until HOURS_PER_DAY) {
            "%02d:00~%02d:00".format(hour0to23, hour0to23 + 1)
        } else {
            null
        }

    /**
     * 热力条点选读数标签：「22:00~23:00 · 87 条」——N=该时段发布总数（直方图口径，
     * 全史账——非预览窗内计数）。0 条/域外/错长直方图=null（无可过滤即不出标签，
     * 诚实缺省同 [peakHourLabelOf] 哲学——调用侧点选门同款 hours[h]>0）。
     */
    fun hourFilterLabelOf(hours: IntArray, hour0to23: Int): String? {
        if (hours.size != HOURS_PER_DAY || hour0to23 !in 0 until HOURS_PER_DAY) return null
        val c = hours[hour0to23]
        if (c <= 0) return null
        return "${hourRangeLabelOf(hour0to23) ?: return null} · $c 条"
    }
}
