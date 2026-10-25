package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem
import java.util.Calendar
import java.util.TimeZone

/**
 * R202 时间窗横截面策略（TL-1 全网时光机 / TL-4 事件考古 / DS-1 此刻横截面共享，
 * 纯函数集合，JVM 可单测——消费侧三模式只差初始锚点/窗宽/文案，引擎同一套）。
 *
 * 职责四块：
 *  - **窗宽预设**：三模式各自的 ± 档位（Free=时光机漫游 / Now=此刻新鲜度 /
 *    Event=事件考古 ±48h 默认——W4/TL-4 口径）；
 *  - **横截面精炼** [refineCrossSection]：dateEpochSec>0 防御 + [AdPatternFilter]
 *    .isAdCaption 条目级广告话术剔除（R200 TL-1「防广告频道污染横截面」的条目级
 *    实现；频道级 farm 闸=金矿快照口径，挂账见 r202_notes）+ fileUniqueId 去重
 *    保**最早**（同片多频道只显首发——TL-1「事件溯源」叙事 + W4 同口径）+
 *    每频道封顶 + 频道轮转交错（防怪兽频道垄断墙——R203 OnThisDay 策展同族
 *    设计，收敛机会见 r202_notes 同类扫描）；
 *  - **滑杆几何**：fraction↔秒映射（钳制）+ 年刻度生成 + 直方图密度归一；
 *  - **默认锚点** [defaultAnchorSec]：近 180 天直方图最密日（首开必有货的诚实
 *    保证——空墙开局=产品事故），回落 now-24h。
 *
 * 时间格式化全部本机时区（DailyDigest todayStart 同口径；测试注入固定时区保
 * 确定性）。输出文案零频道名零标题（脱敏只约束代理书面输出——App 内 UI 给用户
 * 看真实频道名是产品行为，不受此限）。
 */
object TimeWindowPolicy {

    const val HOUR_SEC = 3600L
    const val DAY_SEC = 86_400L

    /**
     * 时间窗单次采样硬顶（有界红线：R183 全表扫禁令的工程化钳制——任何调用方
     * 的 limit 都会被 [DeepSearchIndex.timeWindowItems] 压到该值）。
     * **公共唯一出处**（R203 OnThisDay 挂账「R202 companion 不可外引」的解法——
     * 需要对齐上限的时间族消费方引用这里，勿再开私有镜像）。
     */
    const val QUERY_LIMIT_MAX = 600

    /** 窗宽预设档（label 直显 chips，halfWidthSec=半窗宽秒） */
    data class WindowPreset(val label: String, val halfWidthSec: Long)

    /** 时光机漫游档（R200 TL-1：默认 ±6h 可调） */
    val FREE_PRESETS = listOf(
        WindowPreset("±1时", HOUR_SEC),
        WindowPreset("±6时", 6 * HOUR_SEC),
        WindowPreset("±1天", DAY_SEC),
        WindowPreset("±3天", 3 * DAY_SEC),
    )

    /** 此刻横截档（DS-1「正在发生」分钟级新鲜度——索引头部 1~6h 节流的诚实量级） */
    val NOW_PRESETS = listOf(
        WindowPreset("±10分", 10 * 60),
        WindowPreset("±30分", 30 * 60),
        WindowPreset("±1时", HOUR_SEC),
    )

    /** 事件考古档（TL-4：默认 ±2天，含 W4 的 72h 上探档） */
    val EVENT_PRESETS = listOf(
        WindowPreset("±6时", 6 * HOUR_SEC),
        WindowPreset("±1天", DAY_SEC),
        WindowPreset("±2天", 2 * DAY_SEC),
        WindowPreset("±3天", 3 * DAY_SEC),
    )

    /** 闭区间窗口（BETWEEN 口径）：(anchor-half, anchor+half) */
    fun windowOf(anchorSec: Long, halfWidthSec: Long): Pair<Long, Long> =
        (anchorSec - halfWidthSec) to (anchorSec + halfWidthSec)

    // ---------------- 横截面精炼 ----------------

    /** 精炼统计（头部诚实行：N 条 · M 频道 · 同片折叠 x · 广告剔除 y） */
    data class CrossSectionStats(
        val total: Int,
        val channels: Int,
        val repostsFolded: Int,
        val adsFolded: Int,
    )

    /**
     * 横截面精炼管线（墙与播放源共用同一输出——墙显什么，滑进去刷的就是什么）：
     *  1. dateEpochSec>0 防御（0=异常行不进横截面）+ [AdPatternFilter.isAdCaption]
     *     广告话术剔除；
     *  2. fileUniqueId 非空去重保最早（**首发语义**：同片多频道只留最早发布者；
     *     指纹为空的老条目原样保留——无证据不臆杀）；
     *  3. 每频道封顶 [perChannelCap] + 频道轮转交错取条（频道按组内最新排，
     *     每轮每频道至多取 1 条——「全网横截面」的均衡感，怪兽频道不垄断墙）；
     *  4. [limit] 截断；终序 = dateEpochSec 倒序（轮转是选取序，墙按时间线排）。
     *
     * 输入应为 dateEpochSec 倒序（[DeepSearchIndex.timeWindowItems] 原生序，
     * key 主键天然无重复）。
     */
    fun refineCrossSection(
        items: List<VideoItem>,
        perChannelCap: Int = 3,
        limit: Int = Int.MAX_VALUE,
    ): List<VideoItem> {
        if (items.isEmpty() || perChannelCap <= 0 || limit <= 0) return emptyList()
        // ①②：有效行+广告剔除+指纹去重保最早（两趟：先定每指纹最早行，再保它；
        // 等日期保先见者；key 主键天然无重复免查；指纹为空原样保留）
        val earliestByFp = HashMap<String, VideoItem>()
        for (it in items) {
            if (it.dateEpochSec <= 0) continue
            if (AdPatternFilter.isAdCaption(it.caption)) continue
            val fp = it.fileUniqueId
            if (fp.isEmpty()) continue
            val cur = earliestByFp[fp]
            if (cur == null || it.dateEpochSec < cur.dateEpochSec) earliestByFp[fp] = it
        }
        val kept = items.filter { it.dateEpochSec > 0 && !AdPatternFilter.isAdCaption(it.caption) && run {
            val fp = it.fileUniqueId
            fp.isEmpty() || earliestByFp[fp] === it
        } }
        // ③：频道分组 → 组内倒序 → 组序=组内最新在前 → 轮转+封顶+limit
        // （组内显式排序：对输入序鲁棒——即便调用方传了非倒序，轮转语义不漂移）
        val channelGroups = LinkedHashMap<Long, MutableList<VideoItem>>()
        for (it in kept) channelGroups.getOrPut(it.chatId) { ArrayList() }.add(it)
        val sortedGroups = channelGroups.values
            .map { group -> group.sortedByDescending { g -> g.dateEpochSec } }
            .sortedByDescending { group -> group.first().dateEpochSec }
        val taken = ArrayList<VideoItem>(limit.coerceAtMost(kept.size))
        val perChannel = HashMap<Long, Int>()
        var round = 0
        while (taken.size < limit) {
            var tookInRound = false
            for (group in sortedGroups) {
                if (taken.size >= limit) break
                if (round >= group.size) continue
                val chatId = group[0].chatId
                val cnt = perChannel.getOrDefault(chatId, 0)
                if (cnt >= perChannelCap) continue
                taken += group[round]
                perChannel[chatId] = cnt + 1
                tookInRound = true
            }
            if (!tookInRound) break // 候选耗尽或全部被频道封顶挡死
            round++
        }
        // ④终序：date 倒序
        taken.sortByDescending { it.dateEpochSec }
        return taken
    }

    /** [refineCrossSection] 的统计行（原始查询结果 → 诚实折叠数） */
    fun statsOf(raw: List<VideoItem>, refined: List<VideoItem>): CrossSectionStats {
        var ads = 0
        val fpCounts = HashMap<String, Int>()
        for (it in raw) {
            if (it.dateEpochSec <= 0) continue
            if (AdPatternFilter.isAdCaption(it.caption)) ads++
            else if (it.fileUniqueId.isNotEmpty()) fpCounts.merge(it.fileUniqueId, 1, Int::plus)
        }
        val reposts = fpCounts.values.sumOf { n -> maxOf(0, n - 1) }
        return CrossSectionStats(
            total = refined.size,
            channels = refined.mapTo(HashSet()) { it.chatId }.size,
            repostsFolded = reposts,
            adsFolded = ads,
        )
    }

    // ---------------- 锚点格式化（本机时区；测试可注入） ----------------

    private val WEEKDAYS = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    /** "2023年8月19日 周六 20:05"（滑杆大标签/头部锚点行） */
    fun formatAnchorDateTime(sec: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = sec * 1000
        val wd = WEEKDAYS[cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY]
        return "%d年%d月%d日 %s %02d:%02d".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
            wd,
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
        )
    }

    /** "2023年8月19日"（无时刻版，年刻度/低密度位用） */
    fun formatAnchorDate(sec: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = sec * 1000
        return "%d年%d月%d日".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
    }

    // ---------------- 滑杆几何 ----------------

    /** 滑杆刻度：年份边界处主刻度（label="2023"） */
    data class TimelineTick(val sec: Long, val label: String, val major: Boolean)

    /** 量程内每年 1 月 1 日 00:00（给定 [zone]）一枚刻度 */
    fun yearTicks(minSec: Long, maxSec: Long, zone: TimeZone = TimeZone.getDefault()): List<TimelineTick> {
        if (minSec >= maxSec) return emptyList()
        val out = ArrayList<TimelineTick>(8)
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = minSec * 1000
        cal.set(Calendar.MONTH, Calendar.JANUARY)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.YEAR, 1) // 首枚：量程起点后的第一个年界
        while (cal.timeInMillis / 1000 <= maxSec) {
            out += TimelineTick(
                sec = cal.timeInMillis / 1000,
                label = cal.get(Calendar.YEAR).toString(),
                major = true,
            )
            cal.add(Calendar.YEAR, 1)
        }
        return out
    }

    /** 比例 → 秒（钳制到量程） */
    fun anchorFromFraction(fraction: Float, minSec: Long, maxSec: Long): Long {
        val f = fraction.coerceIn(0f, 1f)
        return (minSec + ((maxSec - minSec) * f).toLong()).coerceIn(minSec, maxSec)
    }

    /** 秒 → 比例（钳制） */
    fun fractionFromAnchor(anchorSec: Long, minSec: Long, maxSec: Long): Float {
        if (maxSec <= minSec) return 0f
        return ((anchorSec - minSec).toFloat() / (maxSec - minSec)).coerceIn(0f, 1f)
    }

    /**
     * 直方图 → 滑杆密度分桶（刻度糖：哪里有货哪里亮）。
     * [histogram]=（dayIndex,count）升序（[DeepSearchIndex.monthHistogram] 原生）；
     * 返回 [buckets] 段归一化密度（0..1，max 段=1；空直方图=全 0）。
     */
    fun densities(
        histogram: List<Pair<Int, Int>>,
        minSec: Long,
        maxSec: Long,
        buckets: Int,
    ): List<Float> {
        if (buckets <= 0 || minSec >= maxSec) return emptyList()
        val sums = LongArray(buckets)
        val span = (maxSec - minSec).toDouble()
        for ((dayIndex, count) in histogram) {
            if (count <= 0) continue
            val sec = dayIndex * DAY_SEC
            if (sec < minSec || sec > maxSec) continue
            val idx = (((sec - minSec).toDouble() / span) * buckets).toInt().coerceIn(0, buckets - 1)
            sums[idx] += count
        }
        val max = sums.maxOrNull() ?: 0L
        if (max <= 0L) return List(buckets) { 0f }
        return sums.map { (it.toDouble() / max).toFloat() }
    }

    // ---------------- 默认锚点 ----------------

    /** 默认锚点的「近期」窗口（近 180 天——怪兽频道月采样下仍至少数桶在窗内） */
    const val RECENT_WINDOW_SEC = 180L * DAY_SEC

    /** 无近期数据时的回落年龄（now-24h：头部刷新节流内的常见命中区） */
    const val FALLBACK_AGE_SEC = DAY_SEC

    /**
     * 默认锚点：近 [RECENT_WINDOW_SEC] 直方图最密日的正午（首开有货保证）；
     * 直方图空/全无近期数据回落 [FALLBACK_AGE_SEC] 前的整点。钳到量程内。
     */
    fun defaultAnchorSec(
        histogram: List<Pair<Int, Int>>,
        nowSec: Long,
        minSec: Long,
        maxSec: Long,
    ): Long {
        val windowStart = nowSec - RECENT_WINDOW_SEC
        var bestDay: Int? = null
        var bestCount = 0
        for ((dayIndex, count) in histogram) {
            val sec = dayIndex * DAY_SEC
            if (sec < windowStart || sec > nowSec || sec < minSec || sec > maxSec) continue
            if (count > bestCount) {
                bestCount = count
                bestDay = dayIndex
            }
        }
        val anchor = when (bestDay) {
            null -> nowSec - FALLBACK_AGE_SEC
            else -> bestDay * DAY_SEC + DAY_SEC / 2 // 该日正午：±6h 窗吃满当日
        }
        return anchor.coerceIn(minSec, maxSec)
    }
}
