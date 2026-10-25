package dev.tgfeed.core.feed

import androidx.lifecycle.viewModelScope
import dev.tgfeed.core.store.LocalStore
import dev.tgfeed.core.store.WatchEvent
import dev.tgfeed.core.td.ChannelBrief
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

/** 触发一次观看报告重算（IO 线程聚合）；报告页 onEnter 时调用 */
internal fun FeedViewModel.refreshWatchReportImpl() {
    viewModelScope.launch(Dispatchers.IO) {
        _watchReport.value = runCatching { buildWatchReport() }.getOrDefault(WatchReport())
    }
}

/**
 * 从 watch_events 聚合观看报告（同步阻塞，**必须在 IO 线程调用**，UI 请走 [refreshWatchReport]）。
 *
 * 窗口口径（本地时区）：
 *  - "今日" = 今日 00:00 起至今；"本周" = 本周一 00:00 起至今（周一为一周起点）；
 *  - "总计" = 事件表现存全部——注意 watch_events 总量封顶 [LocalStore.MAX_WATCH_EVENTS] 条
 *    （300 条环形淘汰），重度用户的"总计/本周"实际只覆盖最近 300 次观看（UI 已就此给出说明文案）；
 *  - 三档范围各聚合一份 [WatchRangeStats]（见 [WatchReport.ranges]），UI 切换范围无需重算。
 *
 * 内容类型（mediaType）是近似推导而非精确字段：watch_events 只存
 * chatId/watchSec/durationSec/ts，没有 mediaType。推导分两层：
 *  1. 频道画像：用内存条目（当前 Feed + 收藏 + 稍后再看）对每个频道做 mediaType 多数表决；
 *  2. 无画像事件的兜底启发式：durationSec<=0 → 图文（图片无时长概念）；
 *     watchSec==durationSec 且 ≤30s → 图文（[recordMediaView] 对图文把 durationSec 记成
 *     watchSec，幻灯片一轮约 4s）；其余按视频计（音频脱离频道画像无法与视频区分）。
 * 因此类型分布是"偏好的近似画像"，随内存条目增多而更准。
 *
 * 派生维度口径：
 *  - 完播率：仅视频参与判定（图文 durationSec 记法无完播概念，音频按"收听"不计完播）；
 *    watchSec ≥ 95% durationSec 记一次完播，分母只算 watchSec>0 的视频事件（0 秒负反馈不进分母）；
 *  - 常看话题：内存条目的 caption 提取 #话题，按"频道 → 标签集"归集；范围内事件按频道命中
 *    标签并以 watchSec 加权（事件本身不存 caption，这是"常看频道的话题画像"，属近似）；
 *  - 近 7 天逐日趋势 / 连续观看天数 / 累计观看天数：事件按本地日界归桶，
 *    仅 watchSec>0 的事件计为"当日看过"（纯秒划负反馈不算活跃日）；
 *  - 花样层（观看人格 / 近 12 周热力图 / "最"时刻 / 成就徽章）：全部按"总计"口径
 *    从事件表现推（窗口 ≈ 最近 300 次观看），不随范围切换、不新增采集字段；
 *    人格规则表与阈值见函数内"花样层"注释。
 */
internal fun FeedViewModel.buildWatchReportImpl(): WatchReport {
    val events = runCatching { store.loadWatchEvents() }.getOrDefault(emptyList())
    if (events.isEmpty()) return WatchReport()

    // 本地时区"今日 00:00"与"本周一 00:00"（毫秒）；周一为一周起点
    val dayStartMs = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val weekStartMs = Calendar.getInstance().apply {
        timeInMillis = dayStartMs
        // DAY_OF_WEEK：周日=1、周一=2…；回退 (dow-周一+7)%7 天到本周一
        add(Calendar.DAY_OF_YEAR, -((get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7))
    }.timeInMillis

    // 频道标题表：订阅 / 频道列表 / 当前条目 / 收藏 / 稍后再看，先填不覆盖（空标题跳过）
    val titles = HashMap<Long, String>()
    fun putTitle(id: Long, t: String?) {
        if (!t.isNullOrBlank()) titles.putIfAbsent(id, t)
    }
    _subscriptions.value.forEach { putTitle(it.id, it.title) }
    _channels.value.forEach { putTitle(it.id, it.title) }
    _items.value.forEach { putTitle(it.chatId, it.channelTitle) }
    favoriteItems.forEach { putTitle(it.chatId, it.channelTitle) }
    watchLaterItems.forEach { putTitle(it.chatId, it.channelTitle) }

    // 频道 → 内容类型多数表决（chatId → [视频票, 图文票, 音频票]）
    val typeVotes = HashMap<Long, IntArray>()
    fun voteType(item: VideoItem) {
        val arr = typeVotes.getOrPut(item.chatId) { IntArray(3) }
        arr[item.mediaType.coerceIn(0, 2)]++
    }
    _items.value.forEach(::voteType)
    favoriteItems.forEach(::voteType)
    watchLaterItems.forEach(::voteType)
    val channelType = typeVotes.mapValues { (_, arr) ->
        arr.indices.maxByOrNull { arr[it] } ?: 0
    }

    // 单条事件的内容类型：频道画像优先，缺失时走启发式兜底（见方法头注释）
    fun mediaTypeOf(e: WatchEvent): Int {
        channelType[e.chatId]?.let { return it }
        if (e.durationSec <= 0) return 1 // 无时长概念 → 图文
        if (e.watchSec > 0 && e.watchSec == e.durationSec && e.durationSec <= 30) return 1 // 图文记法
        return 0 // 音频无画像时无法区分，按视频计
    }

    // 频道 → 话题标签集：内存条目 caption 提取 #话题（三处条目源与标题表同源）
    val channelTopics = HashMap<Long, Set<String>>()
    fun collectTopics(item: VideoItem) {
        if (item.caption.isBlank()) return
        val tags = extractHashtags(item.caption)
        if (tags.isEmpty()) return
        channelTopics[item.chatId] = (channelTopics[item.chatId] ?: emptySet()) + tags
    }
    _items.value.forEach(::collectTopics)
    favoriteItems.forEach(::collectTopics)
    watchLaterItems.forEach(::collectTopics)

    // 单范围聚合：fromMs（含）起至今的事件窗口；今日/本周/总计三档各调一次
    fun aggregate(fromMs: Long): WatchRangeStats {
        var totalSec = 0L
        var eventCount = 0
        var videoSec = 0L
        var photoSec = 0L
        var audioSec = 0L
        var completedCount = 0
        var completableCount = 0
        val perChannel = HashMap<Long, Long>()
        val topicSec = HashMap<String, Long>()
        val hours = LongArray(24) // 0..23 点各时段观看秒数
        val cal = Calendar.getInstance()
        for (e in events) {
            if (e.ts < fromMs) continue // 窗口外的事件不计
            eventCount++
            val ws = e.watchSec.coerceAtLeast(0).toLong()
            totalSec += ws
            if (ws > 0) {
                perChannel[e.chatId] = (perChannel[e.chatId] ?: 0L) + ws
                // 话题加权：命中频道画像的每个标签都吃到这次观看的秒数
                channelTopics[e.chatId]?.forEach { tag ->
                    topicSec[tag] = (topicSec[tag] ?: 0L) + ws
                }
            }
            val mt = mediaTypeOf(e)
            when (mt) {
                1 -> photoSec += ws
                2 -> audioSec += ws
                else -> videoSec += ws
            }
            // 完播判定：仅视频、有真实时长、有实际观看（口径见方法头注释）
            if (mt == 0 && e.durationSec > 0 && ws > 0) {
                completableCount++
                // ≥95% 记完播；整数运算判定（ws/dur ≥ 19/20），避开浮点
                if (ws * 20 >= e.durationSec.toLong() * 19) completedCount++
            }
            cal.timeInMillis = e.ts
            hours[cal.get(Calendar.HOUR_OF_DAY)] += ws
        }
        // Top 5 频道：按范围内观看秒数降序；只有 0 秒事件（纯负反馈）的频道不上榜
        val topChannels = perChannel.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { (id, sec) -> ChannelWatchStat(id, titles[id] ?: "未知频道", sec) }
        // 常看话题 Top 8：按加权观看秒数降序
        val topTopics = topicSec.entries
            .sortedByDescending { it.value }
            .take(8)
            .map { (tag, sec) -> TopicStat(tag, sec) }
        return WatchRangeStats(
            totalSec = totalSec,
            eventCount = eventCount,
            topChannels = topChannels,
            videoSec = videoSec,
            photoSec = photoSec,
            audioSec = audioSec,
            hourHistogram = hours.toList(),
            completedCount = completedCount,
            completableCount = completableCount,
            topTopics = topTopics,
        )
    }

    val todayStats = aggregate(dayStartMs)
    val weekStats = aggregate(weekStartMs)
    val allStats = aggregate(0L)

    // ---------- 日粒度派生：近 7 天趋势 / 连续与累计观看天数 ----------
    // 本地日界归桶（ts → 当日 00:00 毫秒）
    fun dayStartOf(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val perDay = HashMap<Long, Long>()
    for (e in events) {
        val ws = e.watchSec.coerceAtLeast(0).toLong()
        if (ws <= 0) continue // 纯负反馈不算"当日看过"
        val d = dayStartOf(e.ts)
        perDay[d] = (perDay[d] ?: 0L) + ws
    }
    // 近 7 天（含今日，日界升序，末位=今日）：Calendar 逐日回推，跨日界无精度问题
    val backCal = Calendar.getInstance()
    val dailyTrend = (6 downTo 0).map { back ->
        backCal.timeInMillis = dayStartMs
        backCal.add(Calendar.DAY_OF_YEAR, -back)
        val d = backCal.timeInMillis
        DayWatch(d, perDay[d] ?: 0L)
    }
    val activeDays = perDay.size // 事件表窗口内的累计观看天数
    // 连续观看天数：今日还没看不清零，从昨日回溯；中间断档即止
    var streakDays = 0
    var cursor = dayStartMs
    if (!perDay.containsKey(cursor)) {
        backCal.timeInMillis = cursor
        backCal.add(Calendar.DAY_OF_YEAR, -1)
        cursor = backCal.timeInMillis
    }
    while (perDay.containsKey(cursor)) {
        streakDays++
        backCal.timeInMillis = cursor
        backCal.add(Calendar.DAY_OF_YEAR, -1)
        cursor = backCal.timeInMillis
    }

    // ==================== 花样层（人格/热力图/最时刻/徽章，全局口径，不随范围切换） ====================
    // 原料全部从 watch_events 现推（窗口 = 事件表现存全部，与"总计"同口径），不新增采集字段。
    val funCal = Calendar.getInstance()

    // ---------- 公共原料：分时段/分星期/日粒度观看秒数、全频道时长表、互动底账等（同一趟循环顺手算，零新 IO） ----------
    // 时段桶秒数（窗口互斥或显式父子包含：deepNightSec ⊂ nightSec，wolfSec ⊂ deepNightSec；各人格/徽章各吃各的桶）：
    var nightSec = 0L        // 深夜 23:00–05:59
    var deepNightSec = 0L    // 凌晨 00:00–04:59
    var wolfSec = 0L         // 凌晨深处 02:00–03:59
    var morningSec = 0L      // 清晨 06:00–08:59
    var noonSec = 0L         // 午间 11:00–13:59
    var primeSec = 0L        // 黄金档 20:00–22:59
    var presleepSec = 0L     // 睡前 22:00–23:59
    var workdayDaySec = 0L   // 工作日白天：周一~周五 09:00–17:59
    var commuteMornSec = 0L  // 工作日早高峰：周一~周五 07:00–08:59
    var commuteEveSec = 0L   // 工作日晚高峰：周一~周五 17:00–18:59
    var weekdaySec = 0L      // 周一~周五全天
    var weekendSec = 0L      // 周六 + 周日全天
    var weekendNightSec = 0L // 周末深夜：周六/日的 23:00–05:59
    // 事件计数桶（与秒数桶同窗口；计数类人格/徽章用，只计 ws>0 的真实观看）
    var nightEvents = 0        // 深夜 23:00–05:59 条数
    var dawnMorningEvents = 0  // 起早窗 05:00–07:59 条数
    var noonEvents = 0         // 午间 11:00–13:59 条数
    var primeEvents = 0        // 黄金档 20:00–22:59 条数
    var zeroSecEvents = 0      // 秒划（watchSec<=0 的纯负反馈/翻页）
    var videoEvents = 0        // 可判类型=视频的事件（长短视频占比的分母）
    var shortVideoEvents = 0   // 其中时长 ≤60s 的短视频
    var longVideoEvents = 0    // 其中时长 ≥10 分钟的长视频
    var unsubscribedEvents = 0 // 来自未订阅频道的事件
    var unsubscribedSec = 0L   // 来自未订阅频道的观看秒数
    // "在 X 时段看过"存在性开关（时段族徽章用，条件文案即窗口）
    var hasDeepNightEvent = false   // 00:00–05:59
    var hasWolfEvent = false        // 02:00–03:59
    var hasDawnEvent = false        // 05:00–06:59
    var hasMorningEvent = false     // 06:00–08:59
    var hasNoonEvent = false        // 11:00–13:59
    var hasPrimeEvent = false       // 20:00–22:59
    var hasWorkdayDayEvent = false  // 工作日 09:00–17:59
    var hasCommuteMornEvent = false // 工作日 07:00–08:59
    var hasCommuteEveEvent = false  // 工作日 17:00–18:59
    val perChannelAll = HashMap<Long, Long>() // 全频道观看秒数（不限 Top5，人格/徽章用）
    val dayChannels = HashMap<Long, HashSet<Long>>()          // 每天看过的频道集合（单日广度用）
    val dayMask = HashMap<Long, Int>()                        // 每天观看小时位掩码（bit h = 当天 h 点看过；跨夜/凌晨天数用）
    val dayNightSec = HashMap<Long, Long>()                   // 每天深夜(23–6点)观看秒数
    val dayChannelSec = HashMap<Long, HashMap<Long, Long>>()  // 每天各频道观看秒数（蝉联用）
    val dayChannelCnt = HashMap<Long, HashMap<Long, Int>>()   // 每天各频道观看条数（追剧用）
    val perDayEvents = HashMap<Long, Int>()                   // 每天观看条数（单日条数极值用）
    var latestEvent: WatchEvent? = null // 最晚一次观看：时刻从中午 12:00 起算"熬夜深度"最大者
    var latestScore = -1
    var longestEvent: WatchEvent? = null  // 单场最久：watchSec 最大的一条有效观看
    var longestWatchSec = 0
    var ratioSum = 0.0   // 平均观看占比分子：Σ min(watchSec, durationSec)/durationSec
    var ratioSamples = 0 // 占比样本数（仅 durationSec>0 且有实际观看的事件）
    val subscribedIds = _subscriptions.value.mapTo(HashSet()) { it.id } // 订阅表（ChannelBrief.id = chatId；未订阅频道判定用）
    for (e in events) {
        val ws = e.watchSec.coerceAtLeast(0)
        funCal.timeInMillis = e.ts
        val hour = funCal.get(Calendar.HOUR_OF_DAY)
        val dow = funCal.get(Calendar.DAY_OF_WEEK)
        val isWeekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
        if (ws > 0) {
            val wsL = ws.toLong()
            val day = dayStartOf(e.ts)
            perChannelAll[e.chatId] = (perChannelAll[e.chatId] ?: 0L) + wsL
            // —— 时段秒数桶 / 计数桶 ——
            if (hour >= 23 || hour < 6) {
                nightSec += wsL
                nightEvents++
                dayNightSec[day] = (dayNightSec[day] ?: 0L) + wsL
                if (isWeekend) weekendNightSec += wsL
            }
            if (hour in 0..4) deepNightSec += wsL
            if (hour in 2..3) wolfSec += wsL
            if (hour in 6..8) morningSec += wsL
            if (hour in 5..7) dawnMorningEvents++
            if (hour in 11..13) { noonSec += wsL; noonEvents++ }
            if (hour in 20..22) { primeSec += wsL; primeEvents++ }
            if (hour in 22..23) presleepSec += wsL
            if (isWeekend) {
                weekendSec += wsL
            } else {
                weekdaySec += wsL
                if (hour in 9..17) workdayDaySec += wsL
                if (hour in 7..8) commuteMornSec += wsL
                if (hour in 17..18) commuteEveSec += wsL
            }
            // —— "在 X 时段看过"存在性开关 ——
            if (hour in 0..5) hasDeepNightEvent = true
            if (hour in 2..3) hasWolfEvent = true
            if (hour in 5..6) hasDawnEvent = true
            if (hour in 6..8) hasMorningEvent = true
            if (hour in 11..13) hasNoonEvent = true
            if (hour in 20..22) hasPrimeEvent = true
            if (!isWeekend && hour in 9..17) hasWorkdayDayEvent = true
            if (!isWeekend && hour in 7..8) hasCommuteMornEvent = true
            if (!isWeekend && hour in 17..18) hasCommuteEveEvent = true
            // —— 日粒度集合：当天频道集合 / 小时位掩码 / 分频道秒数与条数 / 当天条数 ——
            dayChannels.getOrPut(day) { HashSet() } += e.chatId
            dayMask[day] = (dayMask[day] ?: 0) or (1 shl hour)
            val chSec = dayChannelSec.getOrPut(day) { HashMap() }
            chSec[e.chatId] = (chSec[e.chatId] ?: 0L) + wsL
            val chCnt = dayChannelCnt.getOrPut(day) { HashMap() }
            chCnt[e.chatId] = (chCnt[e.chatId] ?: 0) + 1
            perDayEvents[day] = (perDayEvents[day] ?: 0) + 1
            // —— 未订阅频道（探索族依据）——
            if (e.chatId !in subscribedIds) {
                unsubscribedSec += wsL
                unsubscribedEvents++
            }
            // —— 视频长短构成（类型族依据；mediaTypeOf 与报告同一口径）——
            if (mediaTypeOf(e) == 0) {
                videoEvents++
                if (e.durationSec in 1..60) shortVideoEvents++
                if (e.durationSec >= 600) longVideoEvents++
            }
        } else {
            zeroSecEvents++ // 秒划/纯负反馈：不计观看，只计"划得快"
        }
        // 最晚时刻按事件时刻算（0 秒负反馈也是真实翻页动作）；从中午起算让凌晨排在深夜前
        val minuteOfDay = hour * 60 + funCal.get(Calendar.MINUTE)
        val lateScore = (minuteOfDay - 12 * 60 + 24 * 60) % (24 * 60)
        if (lateScore > latestScore) {
            latestScore = lateScore
            latestEvent = e
        }
        if (ws > longestWatchSec) {
            longestWatchSec = ws
            longestEvent = e
        }
        if (e.durationSec > 0 && ws > 0) {
            ratioSum += ws.coerceAtMost(e.durationSec).toDouble() / e.durationSec
            ratioSamples++
        }
    }
    val totalSecAll = allStats.totalSec
    val totalEventsAll = allStats.eventCount
    val distinctChannels = perChannelAll.size
    val topChannelSec = perChannelAll.values.maxOrNull() ?: 0L
    val top1Share = if (totalSecAll > 0) topChannelSec.toDouble() / totalSecAll else 0.0
    val avgRatio = if (ratioSamples > 0) ratioSum / ratioSamples else 1.0
    val maxDayEntry = perDay.maxByOrNull { it.value }
    val maxDaySec = maxDayEntry?.value ?: 0L
    val completionRateAll = allStats.completionRate
    // 占比速查：总量为 0 时一切占比按 0 计（占比类条件自带总量门槛，不会误判）
    fun shareOf(sec: Long): Double = if (totalSecAll > 0) sec.toDouble() / totalSecAll else 0.0
    // 节奏派生：正样本事件数（剔除秒划）、单次均值、频道探索速率、每频道条数
    val positiveEvents = (totalEventsAll - zeroSecEvents).coerceAtLeast(1) // ≥1 保护除法；门槛判断不受影响
    val avgWatchSec = totalSecAll / positiveEvents
    val channelExploreRate = distinctChannels.toDouble() / activeDays.coerceAtLeast(1)
    val avgEventsPerChannel = if (distinctChannels > 0) positiveEvents.toDouble() / distinctChannels else 0.0
    // 未订阅频道（探索族依据）：时长占比 / 事件占比 / 个数
    val unsubChannelCount = perChannelAll.keys.count { it !in subscribedIds }
    val unsubSecShare = shareOf(unsubscribedSec)
    val unsubEventShare = unsubscribedEvents.toDouble() / positiveEvents
    // 类型占比（"总计"口径，与"内容偏好"卡同一分母）
    val videoShare = shareOf(allStats.videoSec)
    val photoShare = shareOf(allStats.photoSec)
    val audioShare = shareOf(allStats.audioSec)
    val shortVideoShare = if (videoEvents > 0) shortVideoEvents.toDouble() / videoEvents else 0.0
    val longVideoShare = if (videoEvents > 0) longVideoEvents.toDouble() / videoEvents else 0.0
    // 小时覆盖度与单小时集中度（直方图为"总计"口径）
    val hoursAll = allStats.hourHistogram
    val hoursCovered = hoursAll.count { it > 0L }
    val topHour = hoursAll.indices.maxByOrNull { hoursAll[it] } ?: -1
    val topHourShare = if (topHour >= 0) shareOf(hoursAll[topHour]) else 0.0
    // 日粒度阈值天数（≥30 分钟 / ≥1 小时 / ≥2 小时的观看日数）与单日极值
    val daysOver30m = perDay.values.count { it >= 1800 }
    val daysOver1h = perDay.values.count { it >= 3600 }
    val daysOver2h = perDay.values.count { it >= 2 * 3600 }
    val maxDayEvents = perDayEvents.values.maxOrNull() ?: 0
    val maxDayChannels = dayChannels.values.maxOfOrNull { it.size } ?: 0
    val maxDaySameChannelCnt = dayChannelCnt.values.flatMap { it.values }.maxOrNull() ?: 0
    // 跨夜天数（同日 20–23 点与 0–4 点都有观看）与凌晨天数（0–5 点有观看）：小时位掩码判定
    val overnightDays = dayMask.values.count { m -> (m and 0x00F00000) != 0 && (m and 0x1F) != 0 }
    val deepNightDays = dayMask.values.count { (it and 0x3F) != 0 }
    val maxNightDaySec = dayNightSec.values.maxOrNull() ?: 0L
    // 周末单日最长（"周末狂欢"人格与"周末双时"徽章用）
    var maxWeekendDaySec = 0L
    for ((d, sec) in perDay) {
        funCal.timeInMillis = d
        val dw = funCal.get(Calendar.DAY_OF_WEEK)
        if ((dw == Calendar.SATURDAY || dw == Calendar.SUNDAY) && sec > maxWeekendDaySec) maxWeekendDaySec = sec
    }
    // 阈值连续天数：每天 ≥minSec 的连续段（回溯口径与 streakDays 相同：今天不够格则从昨天起算）
    fun streakOver(minSec: Long): Int {
        var n = 0
        var cur = dayStartMs
        if ((perDay[cur] ?: 0L) < minSec) {
            backCal.timeInMillis = cur
            backCal.add(Calendar.DAY_OF_YEAR, -1)
            cur = backCal.timeInMillis
        }
        while ((perDay[cur] ?: 0L) >= minSec) {
            n++
            backCal.timeInMillis = cur
            backCal.add(Calendar.DAY_OF_YEAR, -1)
            cur = backCal.timeInMillis
        }
        return n
    }
    val streakOver1h = streakOver(3600)
    // 最长断档：相邻活跃日之间隔的无观看天数最大值（毫秒差四舍五入成天，±1 小时夏令时抖动无碍）
    var maxGapDays = 0
    run {
        var prev = -1L
        for (d in perDay.keys.sorted()) {
            if (prev >= 0L) {
                val gap = (Math.round((d - prev) / 86400000.0) - 1).toInt()
                if (gap > maxGapDays) maxGapDays = gap
            }
            prev = d
        }
    }
    // 近 7 天有观看（dailyTrend 任一 >0，"回魂夜"人格与"诈尸回归"徽章的回归判据）
    val recentActive7 = dailyTrend.any { it.watchSec > 0 }
    // 互动底账：历史/收藏/稍后/下载/搜索（历史读 store 内存缓存，其余全是内存态，零新 IO）
    val historyCount = runCatching { store.loadHistory() }.getOrDefault(emptyList()).size
    val favCount = favoriteItems.size
    val favRate = if (historyCount > 0) favCount.toDouble() / historyCount else 0.0
    val laterCount = watchLaterItems.size
    val downloadCount = _downloadRecords.value.size
    val searchTermCount = _searchHistory.value.size
    // 最常看频道蝉联天数：同一频道连续为"当日观看第一"的最长连续天数（dayChannelSec 已在公共原料循环构建）
    val dayTopChannel = dayChannelSec.mapValues { (_, m) -> m.maxByOrNull { it.value }?.key ?: 0L }
    var bestRunDays = 0
    var bestRunChannel = 0L
    run {
        var curDays = 0
        var curChannel = 0L
        var prevDay = 0L
        for (d in dayTopChannel.keys.sorted()) {
            val top = dayTopChannel[d] ?: 0L
            // 连续 = 上一天 +1 天恰好是本日（Calendar 步进，跨夏令时也安全），且当日第一频道没变
            funCal.timeInMillis = prevDay
            funCal.add(Calendar.DAY_OF_YEAR, 1)
            val consecutive = curDays > 0 && funCal.timeInMillis == d && top == curChannel
            if (consecutive) {
                curDays++
            } else {
                curDays = 1
                curChannel = top
            }
            if (curDays > bestRunDays) {
                bestRunDays = curDays
                bestRunChannel = curChannel
            }
            prevDay = d
        }
    }
    // 类型共鸣原料：看过的频道按"标题关键词"归类（观看秒数加权；标题缺失的频道漏掉，属近似口径；
    // 一个词组内多关键词命中同一频道只记一次，不同词组可重复命中——词组间不是互斥分类）
    val genreSec = HashMap<String, Long>()
    for ((chatId, sec) in perChannelAll) {
        val t = titles[chatId]?.lowercase() ?: continue
        for (g in GenreKeywordGroups) {
            if (g.keywords.any { t.contains(it) }) {
                genreSec[g.id] = (genreSec[g.id] ?: 0L) + sec
            }
        }
    }
    fun genreSecOf(id: String): Long = genreSec[id] ?: 0L

    // ---------- 观看人格规则表（80 条规则 + 兜底"见习刷手"；命中按 score 显著度降序：第 1 = 主人格，2~3 = 副标签） ----------
    // 十族：时段(10) / 时长节奏(10) / 频道(8) / 类型(6) / 互动(7) / 探索(5) / 习惯(8) / 里程碑(6) / 特殊现象(7) / 类型共鸣(13)；
    // 族内每条规则的判定维度或阈值口径两两不同（每条行内注释写明依据与显著度算法）。
    // 占比类规则统一挂 enoughData（总观看 ≥ 10 分钟且事件 ≥ 5 条，小样本上占比全是噪声）；计数/天数类规则自带门槛。
    val personaHits = ArrayList<Pair<Double, WatchPersona>>()
    fun hitPersona(score: Double, id: String, emoji: String, title: String, tagline: String) {
        personaHits += score to WatchPersona(id, title, emoji, tagline)
    }
    val enoughData = totalSecAll >= 600 && totalEventsAll >= 5

    // —— 时段族（10）：看的"时间点"画像，依据各互斥/父子时段桶的时长占比（enoughData 门槛）——
    if (enoughData) {
        // 深夜 23–6 点占比 ≥ 30%（显著度 = 占比）
        if (shareOf(nightSec) >= 0.30) hitPersona(shareOf(nightSec), "night", "🦉", "夜猫子", "凌晨的推荐页没有别人，只有你和手机面面相觑")
        // 凌晨 0–5 点占比 ≥ 18%：比夜猫子更狠的核心熬夜段（nightSec 的子窗，阈值口径不同）
        if (shareOf(deepNightSec) >= 0.18) hitPersona(shareOf(deepNightSec), "sentinel", "🌃", "守夜人", "整座城都睡了，你还在给频道站最后一班岗")
        // 凌晨 2–4 点占比 ≥ 6% 且该窗口累计 ≥ 30 分钟：硬核熬夜证据
        if (shareOf(wolfSec) >= 0.06 && wolfSec >= 1800) {
            hitPersona((shareOf(wolfSec) * 3).coerceAtMost(1.0), "wolf", "🐺", "凌晨孤狼", "两三点钟的内容格外香，这事你只跟月亮说过，懂得都懂")
        }
        // 清晨 6–9 点占比 ≥ 25%
        if (shareOf(morningSec) >= 0.25) hitPersona(shareOf(morningSec), "early", "🐦", "早鸟党", "别人睁眼先关闹钟，你睁眼直接开刷")
        // 午间 11–14 点占比 ≥ 20%
        if (shareOf(noonSec) >= 0.20) hitPersona((shareOf(noonSec) * 1.5).coerceAtMost(1.0), "napper", "🍱", "午休党", "午饭配视频，电子榨菜管饱，下午才有力气装忙")
        // 黄金档 20–23 点占比 ≥ 40%：标准晚间档观众
        if (shareOf(primeSec) >= 0.40) hitPersona(shareOf(primeSec), "primetime", "📺", "黄金档", "八点档没有你，电视台都要连夜改排期")
        // 睡前 22–24 点占比 ≥ 30%
        if (shareOf(presleepSec) >= 0.30) hitPersona(shareOf(presleepSec), "presleep", "😴", "睡前党", "说好再看一条就睡，结果给夜晚开了个头条")
        // 工作日早高峰 7–9 点占比 ≥ 15%：通勤路上刷
        if (shareOf(commuteMornSec) >= 0.15) hitPersona((shareOf(commuteMornSec) * 2).coerceAtMost(1.0), "commuter", "🚇", "早峰乘客", "早高峰的地铁晃不晕你，你的眼里只有下一条")
        // 工作日 9–18 点占比 ≥ 40%：上班时间主力刷
        if (shareOf(workdayDaySec) >= 0.40) hitPersona(shareOf(workdayDaySec), "slacker", "🐟", "摸鱼宗师", "工位上的你神情专注，屏幕里的鱼越摸越大")
        // 反向：工作日 9–18 点占比 ≤ 5% 且累计 ≥ 2 小时（看得不少但从不在上班时间看）
        if (shareOf(workdayDaySec) <= 0.05 && totalSecAll >= 7200) {
            hitPersona((0.5 + (0.05 - shareOf(workdayDaySec)) * 8).coerceAtMost(1.0), "anticlock", "💼", "反卷标兵", "上班时间一条不刷，老板看了都想给你加薪")
        }
    }

    // —— 时长节奏族（10）：看的"剂量与耐力"画像，依据单日/单场极值、单次均值与观看占比 ——
    // 单日最长 ≥ 3 小时（显著度 = 单日最长/4h 封顶）
    if (maxDaySec >= 3 * 3600) {
        hitPersona(
            (maxDaySec / (4.0 * 3600)).coerceAtMost(1.0), "marathon", "🏃", "马拉松",
            "单日连看 ${"%.1f".format(maxDaySec / 3600.0)} 小时，泰裤辣！手指跑出了个人最好成绩",
        )
    }
    // 单日最长 ≥ 6 小时：马拉松的进阶铁人档
    if (maxDaySec >= 6 * 3600) {
        hitPersona(
            (maxDaySec / (8.0 * 3600)).coerceAtMost(1.0), "ironman", "🦾", "铁人观众",
            "单日 ${"%.1f".format(maxDaySec / 3600.0)} 小时不带歇，充电宝都得喊你一声哥",
        )
    }
    // 单日条数 ≥ 40：条数维度的高强度（与马拉松的时长维度互补）
    if (maxDayEvents >= 40) {
        hitPersona((maxDayEvents / 60.0).coerceAtMost(1.0), "binge", "🌀", "连刷狂人", "单日狂刷 $maxDayEvents 条，你就是信息流的显眼包")
    }
    // 单日同一频道 ≥ 15 条：追更式蹲守
    if (maxDaySameChannelCnt >= 15) {
        hitPersona((maxDaySameChannelCnt / 25.0).coerceAtMost(1.0), "chaser", "📼", "追剧模式", "一天在同一家蹲了 $maxDaySameChannelCnt 条，UP 主都被你蹲感动了")
    }
    // 平均单次 ≤ 8 秒且事件 ≥ 50：闪电浏览
    if (positiveEvents >= 50 && avgWatchSec <= 8) {
        hitPersona((1.0 - avgWatchSec / 8.0).coerceAtLeast(0.1), "sipper", "💧", "蜻蜓点水", "每条只看个开头，你的手指比内容先到下一站")
    }
    // 平均单次 ≥ 5 分钟且事件 ≥ 30：深看慢看（与蜻蜓点水同维度反向、阈值与门槛不同）
    if (positiveEvents >= 30 && avgWatchSec >= 300) {
        hitPersona((avgWatchSec / 600.0).coerceAtMost(1.0), "diver", "🤿", "深潜者", "平均一条看 ${avgWatchSec / 60} 分钟，你管这个叫刷短视频？")
    }
    // 单场最久 ≥ 1 小时：一口气长看（单场维度，区别于单日的马拉松）
    if (longestWatchSec >= 3600) {
        hitPersona((longestWatchSec / 5400.0).coerceAtMost(1.0), "gulper", "🍜", "一口闷", "单场 ${longestWatchSec / 60} 分钟不抬头，泡面凉了都顾不上")
    }
    // 平均观看占比 ≤ 25%（占比样本 ≥ 8）：快速划过
    if (ratioSamples >= 8 && avgRatio <= 0.25) {
        hitPersona(1.0 - avgRatio, "speeder", "⛸️", "速滑手", "三秒定生死，你的「下一个」说得比谁都快")
    }
    // 平均观看占比 ≥ 80%（占比样本 ≥ 10）：几乎看完（与速滑手同维度反向）
    if (ratioSamples >= 10 && avgRatio >= 0.80) {
        hitPersona(avgRatio, "savorer", "🍵", "细品师", "每条都看到结尾，快进键在你这属于摆设")
    }
    // 跨夜天数（同日 20–23 点与 0–4 点都有观看）≥ 2：日粒度组合行为
    if (overnightDays >= 2) {
        hitPersona((overnightDays / 5.0).coerceAtMost(1.0), "alnighter", "🌌", "通宵战神", "从夜里看到天亮，感觉身体被掏空但还想再看一条")
    }

    // —— 频道族（8）：看的"对象结构"画像，依据频道离散度 / TOP 集中度 / 蝉联 / 订阅关系 ——
    // TOP1 频道时长占比 ≥ 50%（至少 2 个频道）
    if (top1Share >= 0.50 && distinctChannels >= 2) {
        hitPersona(top1Share, "loyal", "🎯", "单推人", "频道千千万，你一半的时长给了同一家，锁死！")
    }
    // TOP1 占比 ≥ 80%：单推的极端形态
    if (top1Share >= 0.80 && distinctChannels >= 2) {
        hitPersona(top1Share, "diehard", "🔒", "唯粉", "八成时长给了同一家，别人是关注，你是驻扎")
    }
    // 看过 ≥ 15 个频道且 TOP1 ≤ 25%：广撒网
    if (distinctChannels >= 15 && top1Share <= 0.25) {
        hitPersona((distinctChannels / 30.0).coerceAtMost(1.0), "explorer", "🌊", "博览家", "什么都看一点，算法都摸不透你")
    }
    // 看过 ≥ 50 个频道：纯数量维度的广度
    if (distinctChannels >= 50) {
        hitPersona((distinctChannels / 80.0).coerceAtMost(1.0), "voyager", "🧭", "探险家", "逛过 $distinctChannels 个频道，电报的角落都被你踩出脚印了")
    }
    // ≥ 30 个频道且 TOP1 ≤ 10%：极端分散（与博览家的阈值口径不同）
    if (distinctChannels >= 30 && top1Share <= 0.10) {
        hitPersona((0.4 + (0.10 - top1Share) * 6).coerceAtMost(1.0), "casanova", "🎣", "海王", "$distinctChannels 个频道雨露均沾，没有一家能留住你超过一成")
    }
    // 同一频道连续霸榜 ≥ 7 天（蝉联维度）
    if (bestRunDays >= 7) {
        hitPersona(
            (bestRunDays / 10.0).coerceAtMost(1.0), "squatter", "📌", "钉子户",
            "「${titles[bestRunChannel] ?: "未知频道"}」连续 $bestRunDays 天霸榜，别的频道连门缝都挤不进来",
        )
    }
    // 单日看过 ≥ 8 个不同频道（单日广度）
    if (maxDayChannels >= 8) {
        hitPersona((maxDayChannels / 12.0).coerceAtMost(1.0), "hopper", "🧹", "扫街党", "单日逛了 $maxDayChannels 个频道，频道列表被你犁了一遍")
    }
    // 未订阅频道时长占比 ≥ 60%（看过 ≥ 10 个）：不订阅只看
    if (distinctChannels >= 10 && unsubSecShare >= 0.60) {
        hitPersona(unsubSecShare, "nomad", "🐪", "游牧民", "六成时长给了没订阅的频道，来了就走，片叶不沾身")
    }

    // —— 类型族（6）：内容形态偏好，依据视频/图文/音频时长占比与长短视频构成（口径同"内容偏好"卡） ——
    if (totalSecAll >= 600) {
        // 视频时长占比 ≥ 85%
        if (videoShare >= 0.85) hitPersona(videoShare, "video", "🎬", "视频党", "图文不看音频不听，你的眼睛只接受会动的")
        // 图文时长占比 ≥ 60%
        if (photoShare >= 0.60) hitPersona(photoShare, "photo", "🖼️", "图文党", "一图胜千言，你的信息流安静得像本画册")
        // 音频时长占比 ≥ 40%
        if (audioShare >= 0.40) hitPersona(audioShare, "audio", "🎧", "音频党", "眼睛可以休息，耳朵必须加班")
        // 三类各 ≥ 15% 且最高类 ≤ 60%：三栖均衡
        val maxTypeShare = maxOf(videoShare, photoShare, audioShare)
        if (videoShare >= 0.15 && photoShare >= 0.15 && audioShare >= 0.15 && maxTypeShare <= 0.60) {
            hitPersona(((0.60 - maxTypeShare) * 2).coerceAtMost(1.0), "balancer", "⚖️", "均衡大师", "视频图文音频三样通吃，营养搭配比食堂还均衡")
        }
    }
    // 短视频（≤60s）占视频事件 ≥ 70%（视频事件 ≥ 20 条）
    if (videoEvents >= 20 && shortVideoShare >= 0.70) {
        hitPersona(shortVideoShare, "shortie", "🥊", "短打王", "七成视频不超过一分钟，长一点的你都嫌啰嗦")
    }
    // 长视频（≥10 分钟）占视频事件 ≥ 40%（视频事件 ≥ 10 条）
    if (videoEvents >= 10 && longVideoShare >= 0.40) {
        hitPersona((longVideoShare * 1.5).coerceAtMost(1.0), "longie", "🎞️", "长片控", "专挑十分钟以上的看，短视频在你这排不上号")
    }

    // —— 互动族（7）：看完之后的动作画像，依据收藏/稍后/下载底账（收藏率分母为历史条数，近似口径） ——
    // 收藏 ≥ 5 且收藏率 ≥ 10%
    if (favCount >= 5 && favRate >= 0.10) {
        hitPersona((favRate * 2).coerceAtMost(1.0), "collector", "📦", "收藏家", "先码后看——虽然码了的基本不会再点开")
    }
    // 收藏 ≥ 50：纯数量囤货（与收藏家的"率"口径不同）
    if (favCount >= 50) {
        hitPersona((favCount / 100.0).coerceAtMost(1.0), "hamster", "🐹", "仓鼠", "收藏了 $favCount 条，囤粮速度连冬天都追不上")
    }
    // 稍后再看 ≥ 20：光囤不看
    if (laterCount >= 20) {
        hitPersona((laterCount / 40.0).coerceAtMost(1.0), "pigeon", "🕊️", "鸽王", "稍后再看攒了 $laterCount 条，「稍后」大概是下辈子")
    }
    // 下载 ≥ 10
    if (downloadCount >= 10) {
        hitPersona((downloadCount / 20.0).coerceAtMost(1.0), "saver", "⬇️", "下载党", "看中的必须落袋为安，UP 主听了都想说谢谢你")
    }
    // 下载 ≥ 50：备荒级
    if (downloadCount >= 50) {
        hitPersona((downloadCount / 80.0).coerceAtMost(1.0), "prepper", "🥫", "备荒者", "下载了 $downloadCount 条，断网三天你也饿不着")
    }
    // 观看 ≥ 100 条但零收藏/零下载/零稍后：纯看不互动
    if (totalEventsAll >= 100 && favCount == 0 && downloadCount == 0 && laterCount == 0) {
        hitPersona((totalEventsAll / 200.0).coerceAtMost(1.0), "lurker", "🫥", "潜水员", "看了上百条不收藏不下载，主打一个陪伴")
    }
    // 收藏率 ≥ 30% 且收藏 ≥ 20：高比例精藏（与收藏家的阈值口径不同）
    if (favCount >= 20 && favRate >= 0.30) {
        hitPersona(favRate.coerceAtMost(1.0), "curator", "🗂️", "策展人", "三条里就要收藏一条，你的收藏夹比正文精彩")
    }

    // —— 探索族（5）：找新内容的姿势，依据频道探索速率 / 未订阅占比 / 搜索联动 ——
    // 频道探索速率（看过频道数 / 活跃天数）≥ 0.8 且活跃 ≥ 7 天
    if (activeDays >= 7 && channelExploreRate >= 0.8) {
        hitPersona((channelExploreRate / 1.5).coerceAtMost(1.0), "pioneer", "🚩", "拓荒者", "平均每天解锁 ${"%.1f".format(channelExploreRate)} 个新频道，地图都不够你插旗的")
    }
    // 未订阅频道事件占比 ≥ 50% 且频道 ≥ 15：看的多是"野生的"（事件口径，区别于游牧民的时长口径）
    if (distinctChannels >= 15 && unsubEventShare >= 0.50) {
        hitPersona(unsubEventShare, "blindbox", "🎁", "盲盒玩家", "一半的观看来自没订阅的频道，拆开就是惊喜")
    }
    // 搜索 ≥ 5 词且未订阅频道 ≥ 10 个：主动搜 + 广泛逛（组合条件）
    if (searchTermCount >= 5 && unsubChannelCount >= 10) {
        hitPersona(
            ((searchTermCount / 20.0 + unsubChannelCount / 30.0) / 2).coerceAtMost(1.0),
            "radar", "📡", "雷达站", "边搜边逛，没订阅的频道也逃不过你的扫描",
        )
    }
    // 搜索 ≥ 8 词且看过 ≥ 25 个频道：搜完就去看的执行力（与雷达站的组合维度不同）
    if (searchTermCount >= 8 && distinctChannels >= 25) {
        hitPersona(
            ((searchTermCount / 20.0 + distinctChannels / 80.0) / 2).coerceAtMost(1.0),
            "trender", "🏹", "热词猎手", "搜完就去看，热词的尽头总有你的脚印",
        )
    }
    // 频道 ≥ 20 且平均每频道 ≤ 3 条：每家浅尝
    if (distinctChannels >= 20 && avgEventsPerChannel <= 3.0) {
        hitPersona(((3.0 - avgEventsPerChannel) / 3.0).coerceIn(0.2, 1.0), "sampler", "🥄", "试吃员", "每家尝两口就走，$distinctChannels 个频道没一个留得住你")
    }

    // —— 习惯族（8）：星期/连续/节律画像，依据周末占比、连续与活跃天数、时段集中度 ——
    if (enoughData) {
        // 周末占比 ≥ 60%
        if (shareOf(weekendSec) >= 0.60) hitPersona(shareOf(weekendSec), "weekend", "🛋️", "周末战士", "工作日养精蓄锐，周末一刷到底")
        // 反向：工作日占比 ≥ 85%（周末基本不看）
        if (shareOf(weekdaySec) >= 0.85) hitPersona(shareOf(weekdaySec), "weekdayer", "💻", "工作日党", "一到周末就消失，你的快乐是工位限定款")
        // 周末深夜占比 ≥ 20%（星期 × 时段组合）
        if (shareOf(weekendNightSec) >= 0.20) {
            hitPersona((shareOf(weekendNightSec) * 2).coerceAtMost(1.0), "partyowl", "🎉", "周末夜场", "周末的深夜场被你包圆，工作日只是热身")
        }
        // 工作日早 7–9 + 晚 17–19 双峰合计占比 ≥ 25%：通勤双高峰
        val doublePeakShare = shareOf(commuteMornSec + commuteEveSec)
        if (doublePeakShare >= 0.25) {
            hitPersona((doublePeakShare * 1.5).coerceAtMost(1.0), "doublepeak", "🚉", "双峰乘客", "早晚两班车上都有你，通勤路被你刷成了连续剧")
        }
        // 单小时段集中度：最高一小时占比 ≥ 35%（作息像闹钟）
        if (topHourShare >= 0.35) {
            hitPersona(topHourShare, "creature", "⏰", "习惯动物", "一天里最忙的是 $topHour 点档，你的生物钟比闹钟还准")
        }
    }
    // 连续 ≥ 14 天
    if (streakDays >= 14) {
        hitPersona((streakDays / 21.0).coerceAtMost(1.0), "punch", "📅", "打卡人", "连续 $streakDays 天报道，这一局没你真不行")
    }
    // 凌晨 0–5 点观看天数 ≥ 3：反复熬夜（天数口径，区别于守夜人的占比口径）
    if (deepNightDays >= 3) {
        hitPersona((deepNightDays / 6.0).coerceAtMost(1.0), "insomniac", "🛏️", "失眠会员", "$deepNightDays 个凌晨都有你的身影，床只是你的观众席")
    }
    // 周末单日最长 ≥ 3 小时：周末限定马拉松（与马拉松的星期口径不同）
    if (maxWeekendDaySec >= 3 * 3600) {
        hitPersona(
            (maxWeekendDaySec / (4.0 * 3600)).coerceAtMost(1.0), "weekendbinge", "🎊", "周末狂欢",
            "周末单日 ${"%.1f".format(maxWeekendDaySec / 3600.0)} 小时，工作日攒的力气全用在这了",
        )
    }

    // —— 里程碑族（6）：量级资历，依据累计条数 / 活跃与连续天数递进 ——
    if (totalEventsAll >= 10) {
        hitPersona((totalEventsAll / 50.0).coerceAtMost(1.0), "novice", "🌿", "初窥门径", "看了 $totalEventsAll 条，刷门正式向你敞开")
    }
    if (totalEventsAll >= 100) {
        hitPersona((totalEventsAll / 300.0).coerceAtMost(1.0), "adept", "🧠", "熟门熟路", "$totalEventsAll 条观看打底，推荐算法见你都喊老熟人")
    }
    if (totalEventsAll >= 500) {
        hitPersona((totalEventsAll / 1000.0).coerceAtMost(1.0), "senior", "🎓", "资深观众", "$totalEventsAll 条观看在身，你已经能预判算法的预判")
    }
    if (totalEventsAll >= 2000) {
        hitPersona((totalEventsAll / 4000.0).coerceAtMost(1.0), "legend", "👑", "传说", "$totalEventsAll 条观看，服务器都该给你立个牌坊")
    }
    if (activeDays >= 100) {
        hitPersona((activeDays / 200.0).coerceAtMost(1.0), "centurion", "🗓️", "百日老兵", "活跃 $activeDays 天，日历上全是你的出战记录")
    }
    if (streakDays >= 30) {
        hitPersona((streakDays / 45.0).coerceAtMost(1.0), "monthly", "🔥", "月度连击", "连续 $streakDays 天不断更，月老都没你这么粘人")
    }

    // —— 特殊现象族（7）：跨维度组合与反向现象，依据秒划占比、完播两端、断档回归、时段计数等 ——
    // 秒划占比 ≥ 50% 且事件 ≥ 50：一半内容活不过一秒
    if (totalEventsAll >= 50 && zeroSecEvents * 2 >= totalEventsAll) {
        hitPersona(zeroSecEvents.toDouble() / totalEventsAll, "flash", "⚡", "闪电侠", "一半的内容活不过一秒，你的指尖自带审判")
    }
    // 完播率 ≥ 90%（可判 ≥ 20）：几乎不落片尾
    if (allStats.completableCount >= 20 && (completionRateAll ?: 0f) >= 0.9f) {
        hitPersona((completionRateAll ?: 0f).toDouble(), "completist", "✅", "完播控", "完播率九成起步，中途退出在你这不存在")
    }
    // 反向：完播率 ≤ 10%（可判 ≥ 20）：几乎从不看完
    if (allStats.completableCount >= 20 && (completionRateAll ?: 1f) <= 0.1f) {
        hitPersona(1.0 - (completionRateAll ?: 1f).toDouble(), "quitter", "🏳️", "半途而废", "九成视频看不到结尾，片尾曲对你来说是传说")
    }
    // 完播率 ≥ 70%（可判 ≥ 5）
    if (allStats.completableCount >= 5 && completionRateAll != null && completionRateAll >= 0.7f) {
        hitPersona(completionRateAll.toDouble(), "zen", "🧘", "耐心怪", "完播是你对创作者最长情的告白")
    }
    // 最长断档 ≥ 14 天且近 7 天有观看：回归型
    if (maxGapDays >= 14 && recentActive7) {
        hitPersona((maxGapDays / 30.0).coerceAtMost(1.0), "ghost", "👻", "回魂夜", "消失 $maxGapDays 天后突然回归，算法差点给你办了追悼会")
    }
    // 深夜观看条数 ≥ 50：计数维度的熬夜（与时段族的占比口径不同）
    if (nightEvents >= 50) {
        hitPersona((nightEvents / 80.0).coerceAtMost(1.0), "oilburner", "🕯️", "挑灯夜读", "深夜看了 $nightEvents 条，夜越黑你越精神")
    }
    // 清晨 5–8 点观看条数 ≥ 30：计数维度的起早（与早鸟党的占比口径不同）
    if (dawnMorningEvents >= 30) {
        hitPersona((dawnMorningEvents / 50.0).coerceAtMost(1.0), "rooster", "🐓", "闻鸡起舞", "清晨看了 $dawnMorningEvents 条，鸡还没起你就开刷了")
    }

    // —— 类型共鸣族（13）：常看频道的"内容题材"画像；依据 = 看过的频道标题关键词归类（genreSec，观看秒数加权），
    //    某题材 ≥ 30 分钟且占总观看 ≥ 8% 命中（显著度 = 题材占比 ×2 封顶）——
    fun hitGenre(id: String, emoji: String, title: String, tagline: String) {
        val sec = genreSecOf(id)
        if (sec >= 1800 && shareOf(sec) >= 0.08) {
            hitPersona((shareOf(sec) * 2).coerceAtMost(1.0), id, emoji, title, tagline)
        }
    }
    hitGenre("anime", "🎏", "二次元住民", "DNA 动了！你的追番表比课表还满")
    hitGenre("drama", "🎭", "追剧狂魔", "别人等更靠催，你等更靠命——主打一个陪伴")
    hitGenre("movie", "🎦", "影院经理", "你这不是刷频道，是在自家客厅开了家影院")
    hitGenre("photo2", "🎨", "鉴赏家", "审美这块你是有点东西的，老司机懂得都懂")
    hitGenre("asmr", "👂", "耳边风", "别人熬夜靠咖啡，你入睡靠耳语，赛博神仙级待遇")
    hitGenre("software", "🛠️", "军火库管理员", "你的收藏夹不是收藏夹，是军火库，遥遥领先")
    hitGenre("game", "🕹️", "峡谷常住民", "人在工位心在峡谷，主打一个云上分")
    hitGenre("food", "🥢", "干饭人", "你的下饭视频比饭还香，电子榨菜十级学者")
    hitGenre("music", "🎵", "人肉歌单", "耳朵在你这全年无休，听到前奏就能报歌名")
    hitGenre("tech", "🤓", "参数党", "参数背得比身份证号还熟，发布会一场不落，遥遥领先")
    hitGenre("pet", "🐱", "云吸猫所长", "猫没养一只，猫片看了一万条，你人还怪好的嘞")
    hitGenre("funny", "😂", "快乐喷泉", "你的笑点是 5G 的，哈哈哈含量全场最高")
    hitGenre("study", "📖", "卷王", "别人刷视频放松，你刷视频上课，卷得悄无声息")

    personaHits.sortByDescending { it.first }
    // 兜底人格"见习刷手"：80 条规则全部不命中（含数据太薄）时接管
    val persona = personaHits.firstOrNull()?.second
        ?: WatchPersona("rookie", "见习刷手", "🐣", "观看数据还太薄，再刷几天，你的人格就藏不住了")
    val personaSubs = personaHits.drop(1).take(2).map { it.second }

    // ---------- 近 12 周热力图：本周一往前 11 周为第 1 格，共 84 格（日界升序；未来日 watchSec=-1 占位） ----------
    val heatCal = Calendar.getInstance().apply {
        timeInMillis = weekStartMs
        add(Calendar.DAY_OF_YEAR, -77) // 11 周 × 7 天
    }
    val heatmap = (0 until 84).map {
        val d = heatCal.timeInMillis
        heatCal.add(Calendar.DAY_OF_YEAR, 1)
        DayWatch(d, if (d > dayStartMs) -1L else perDay[d] ?: 0L)
    }

    // ---------- "最"时刻（3~4 条；单场 < 5 分钟、蝉联 < 2 天不上榜，不够排面的数据不出镜） ----------
    fun dayLabel(ts: Long): String {
        funCal.timeInMillis = ts
        return "${funCal.get(Calendar.MONTH) + 1}月${funCal.get(Calendar.DAY_OF_MONTH)}日"
    }
    fun fmtDur(sec: Long): String {
        val h = sec / 3600
        val m = sec % 3600 / 60
        return when {
            h > 0 -> "$h 小时 $m 分钟"
            m > 0 -> "$m 分钟"
            else -> "$sec 秒"
        }
    }
    val records = ArrayList<WatchRecordItem>()
    latestEvent?.let { e ->
        funCal.timeInMillis = e.ts
        val h = funCal.get(Calendar.HOUR_OF_DAY)
        // 梗味后缀：凌晨 0–4 点 = 通宵现场；5 点 = 天亮了；23 点 = 睡前仪式；其余不缀
        val flavor = when {
            h in 0..4 -> " · 通宵现场"
            h == 5 -> " · 天亮了"
            h == 23 -> " · 睡前仪式"
            else -> ""
        }
        records += WatchRecordItem(
            "最晚一次观看",
            "%02d:%02d".format(h, funCal.get(Calendar.MINUTE)),
            dayLabel(e.ts) + flavor,
        )
    }
    if (maxDayEntry != null && maxDaySec > 0) {
        records += WatchRecordItem("单日最长观看", fmtDur(maxDaySec), dayLabel(maxDayEntry.key))
    }
    longestEvent?.let { e ->
        // 单场 ≥ 5 分钟才值得上榜（短视频单条撑死几十秒，低于 5 分钟说出来没排面）
        if (longestWatchSec >= 300) {
            records += WatchRecordItem("单场最久", fmtDur(longestWatchSec.toLong()), titles[e.chatId] ?: "未知频道")
        }
    }
    // 频道蝉联记录（bestRunDays/bestRunChannel 已在花样层公共原料段算好，这里只负责上镜）
    if (bestRunDays >= 2) {
        records += WatchRecordItem("频道蝉联", "$bestRunDays 天", "「${titles[bestRunChannel] ?: "未知频道"}」连续霸榜")
    }

    // ---------- 成就徽章（200 枚 / 14 类；condition 为达成条件文案，UI 长按弹窗展示） ----------
    // 全部条件由上方公共原料计算（零新 IO），UI 只读结果；类目顺序 = 定义顺序（groupBy 保序），
    // 组内"达成的在前"在末尾统一稳定排序完成。里程碑递进、题材双档这类同族梯度不算重复条件。
    val badgeList = ArrayList<WatchBadge>(200)
    fun badge(id: String, name: String, emoji: String, category: String, condition: String, achieved: Boolean) {
        badgeList += WatchBadge(id, name, emoji, category, condition, achieved)
    }

    // —— 里程碑（14）：累计观看条数递进 + 首开 ——
    badge("ms1", "初来乍到", "👶", "里程碑", "完成第 1 次观看", totalEventsAll >= 1)
    badge("ms10", "十次观看", "🐾", "里程碑", "累计观看 10 次", totalEventsAll >= 10)
    badge("ms50", "五十次", "🦶", "里程碑", "累计观看 50 次", totalEventsAll >= 50)
    badge("ms100", "身经百战", "🗡", "里程碑", "累计观看 100 次", totalEventsAll >= 100)
    badge("ms200", "二百次", "🏏", "里程碑", "累计观看 200 次", totalEventsAll >= 200)
    badge("ms300", "三百次", "🪃", "里程碑", "累计观看 300 次", totalEventsAll >= 300)
    badge("ms500", "五百次", "🏸", "里程碑", "累计观看 500 次", totalEventsAll >= 500)
    badge("ms700", "七百次", "🥍", "里程碑", "累计观看 700 次", totalEventsAll >= 700)
    badge("ms1000", "千次斩", "🪂", "里程碑", "累计观看 1000 次", totalEventsAll >= 1000)
    badge("ms1500", "千五次", "🤺", "里程碑", "累计观看 1500 次", totalEventsAll >= 1500)
    badge("ms2000", "两千次", "🚵", "里程碑", "累计观看 2000 次", totalEventsAll >= 2000)
    badge("ms3000", "三千次", "🏂", "里程碑", "累计观看 3000 次", totalEventsAll >= 3000)
    badge("ms4000", "四千次", "🚁", "里程碑", "累计观看 4000 次", totalEventsAll >= 4000)
    badge("ms5000", "封顶怪", "🏰", "里程碑", "累计观看 5000 次（事件表上限）", totalEventsAll >= 5000)

    // —— 时长（20）：累计总时长递进 + 单日最长 + 单场最久 ——
    badge("t1h", "一时半刻", "⏱️", "时长", "累计观看 ≥ 1 小时", totalSecAll >= 3600)
    badge("t5h", "五小时", "🔋", "时长", "累计观看 ≥ 5 小时", totalSecAll >= 5 * 3600)
    badge("t10h", "十小时", "🕙", "时长", "累计观看 ≥ 10 小时", totalSecAll >= 10 * 3600)
    badge("t24h", "一天一夜", "🌍", "时长", "累计观看 ≥ 24 小时", totalSecAll >= 24 * 3600)
    badge("t50h", "半百小时", "🧭", "时长", "累计观看 ≥ 50 小时", totalSecAll >= 50 * 3600)
    badge("t72h", "三天三夜", "🎑", "时长", "累计观看 ≥ 72 小时", totalSecAll >= 72 * 3600)
    badge("t100h", "百小时", "💯", "时长", "累计观看 ≥ 100 小时", totalSecAll >= 100 * 3600)
    badge("t300h", "三百小时", "🏔️", "时长", "累计观看 ≥ 300 小时", totalSecAll >= 300 * 3600)
    badge("t500h", "五百小时", "🗿", "时长", "累计观看 ≥ 500 小时", totalSecAll >= 500 * 3600)
    badge("t1000h", "千小时", "🌋", "时长", "累计观看 ≥ 1000 小时", totalSecAll >= 1000 * 3600)
    badge("d30m", "单日小酌", "🥃", "时长", "单日观看 ≥ 30 分钟", maxDaySec >= 1800)
    badge("d1h", "单日一时", "☕", "时长", "单日观看 ≥ 1 小时", maxDaySec >= 3600)
    badge("d2h", "单日双时", "🍿", "时长", "单日观看 ≥ 2 小时", maxDaySec >= 2 * 3600)
    badge("d3h", "马拉松", "🏃", "时长", "单日观看 ≥ 3 小时", maxDaySec >= 3 * 3600)
    badge("d5h", "单日五时", "🧎", "时长", "单日观看 ≥ 5 小时", maxDaySec >= 5 * 3600)
    badge("d8h", "单日八时", "🕰️", "时长", "单日观看 ≥ 8 小时", maxDaySec >= 8 * 3600)
    badge("s10m", "入戏", "🎟️", "时长", "单场观看 ≥ 10 分钟", longestWatchSec >= 600)
    badge("s30m", "沉浸", "🛁", "时长", "单场观看 ≥ 30 分钟", longestWatchSec >= 1800)
    badge("s1h", "一场一时", "🎫", "时长", "单场观看 ≥ 1 小时", longestWatchSec >= 3600)
    badge("s3h", "史诗连播", "🏟️", "时长", "单场观看 ≥ 3 小时", longestWatchSec >= 3 * 3600)

    // —— 天数（14）：累计观看天数递进 + 阈值日（每天 ≥30 分钟 / ≥1 小时 / ≥2 小时）天数 ——
    badge("day1", "首日开张", "🎀", "天数", "累计 1 天有观看", activeDays >= 1)
    badge("day3", "三日客", "🧳", "天数", "累计 3 天有观看", activeDays >= 3)
    badge("day7", "一周常客", "🪪", "天数", "累计 7 天有观看", activeDays >= 7)
    badge("day14", "双周常客", "🧷", "天数", "累计 14 天有观看", activeDays >= 14)
    badge("day30", "满月", "🌝", "天数", "累计 30 天有观看", activeDays >= 30)
    badge("day60", "双月", "🪙", "天数", "累计 60 天有观看", activeDays >= 60)
    badge("day100", "百日", "🏮", "天数", "累计 100 天有观看", activeDays >= 100)
    badge("day200", "二百日", "🏯", "天数", "累计 200 天有观看", activeDays >= 200)
    badge("h30m7", "轻量一周", "🍃", "天数", "≥ 30 分钟的观看日累计 7 天", daysOver30m >= 7)
    badge("h30m30", "轻量一月", "🌾", "天数", "≥ 30 分钟的观看日累计 30 天", daysOver30m >= 30)
    badge("h1h3", "三日沉浸", "🫖", "天数", "≥ 1 小时的观看日累计 3 天", daysOver1h >= 3)
    badge("h1h7", "七日沉浸", "🍶", "天数", "≥ 1 小时的观看日累计 7 天", daysOver1h >= 7)
    badge("h1h30", "月度沉浸", "🏺", "天数", "≥ 1 小时的观看日累计 30 天", daysOver1h >= 30)
    badge("h2h3", "三日重度", "🧱", "天数", "≥ 2 小时的观看日累计 3 天", daysOver2h >= 3)

    // —— 连续（9）：连续观看天数递进 ——
    badge("st2", "连看萌新", "🐤", "连续", "连续 2 天都有观看", streakDays >= 2)
    badge("st3", "渐入佳境", "🍀", "连续", "连续 3 天都有观看", streakDays >= 3)
    badge("st7", "七日连看", "📿", "连续", "连续 7 天都有观看", streakDays >= 7)
    badge("st14", "双周连击", "⛓️", "连续", "连续 14 天都有观看", streakDays >= 14)
    badge("st21", "三七之约", "🎰", "连续", "连续 21 天都有观看", streakDays >= 21)
    badge("st30", "满月连击", "🌕", "连续", "连续 30 天都有观看", streakDays >= 30)
    badge("st45", "四十五连", "🧧", "连续", "连续 45 天都有观看", streakDays >= 45)
    badge("st60", "双月连击", "🛡️", "连续", "连续 60 天都有观看", streakDays >= 60)
    badge("st100", "百日连击", "🐲", "连续", "连续 100 天都有观看", streakDays >= 100)

    // —— 频道（15）：离散度递进 + 集中度认证 + 蝉联 + 单日广度 + 订阅关系 ——
    badge("ch1", "第一家", "🏪", "频道", "看过 1 个频道", distinctChannels >= 1)
    badge("ch5", "五家", "🏙️", "频道", "看过 5 个不同频道", distinctChannels >= 5)
    badge("ch10", "十家", "🏬", "频道", "看过 10 个不同频道", distinctChannels >= 10)
    badge("ch15", "十五家", "🗺️", "频道", "看过 15 个不同频道", distinctChannels >= 15)
    badge("ch20", "博览", "🧗", "频道", "看过 20 个以上不同频道", distinctChannels >= 20)
    badge("ch30", "三十家", "🎡", "频道", "看过 30 个不同频道", distinctChannels >= 30)
    badge("ch50", "半百频道", "🎢", "频道", "看过 50 个不同频道", distinctChannels >= 50)
    badge("ch100", "百频斩", "⚔️", "频道", "看过 100 个不同频道", distinctChannels >= 100)
    badge("diehardb", "唯粉认证", "🔐", "频道", "TOP1 频道时长占比 ≥ 80%（观看 ≥ 50 条）", positiveEvents >= 50 && top1Share >= 0.80)
    badge("poly", "博爱认证", "💞", "频道", "看过 ≥ 20 个频道且 TOP1 占比 ≤ 15%", distinctChannels >= 20 && top1Share <= 0.15)
    badge("run3", "三连霸", "🥉", "频道", "同一频道连续 3 天霸榜", bestRunDays >= 3)
    badge("run7", "七连霸", "🥈", "频道", "同一频道连续 7 天霸榜", bestRunDays >= 7)
    badge("run14", "十四连霸", "🥇", "频道", "同一频道连续 14 天霸榜", bestRunDays >= 14)
    badge("daych8", "一日八家", "🛒", "频道", "单日看过 ≥ 8 个不同频道", maxDayChannels >= 8)
    badge("unsub", "自由市场", "🪁", "频道", "未订阅频道时长占比 ≥ 50%（看过 ≥ 10 个频道）", distinctChannels >= 10 && unsubSecShare >= 0.50)

    // —— 完播（12）：完播条数递进 + 完播率梯度（含反向"开头达人"） ——
    badge("cp10", "完播十连", "🎥", "完播", "完播视频 10 条", allStats.completedCount >= 10)
    badge("cp20", "完播二十", "📽️", "完播", "完播视频 20 条", allStats.completedCount >= 20)
    badge("cp50", "完播半百", "🎞️", "完播", "完播视频 50 条", allStats.completedCount >= 50)
    badge("cp100", "完播百次", "🎗️", "完播", "完播视频 100 条", allStats.completedCount >= 100)
    badge("cp300", "完播三百", "🎖️", "完播", "完播视频 300 条", allStats.completedCount >= 300)
    badge("cp500", "完播五百", "🏆", "完播", "完播视频 500 条", allStats.completedCount >= 500)
    badge("cr70", "七成熟", "🥩", "完播", "视频完播率 ≥ 70%（至少 10 条可判定）", allStats.completableCount >= 10 && (completionRateAll ?: 0f) >= 0.7f)
    badge("cr80", "老饕", "🍽️", "完播", "视频完播率 ≥ 80%（至少 10 条可判定）", allStats.completableCount >= 10 && (completionRateAll ?: 0f) >= 0.8f)
    badge("cr90", "九成熟", "🧈", "完播", "视频完播率 ≥ 90%（至少 20 条可判定）", allStats.completableCount >= 20 && (completionRateAll ?: 0f) >= 0.9f)
    badge("cr95", "接近圆满", "🌖", "完播", "视频完播率 ≥ 95%（至少 20 条可判定）", allStats.completableCount >= 20 && (completionRateAll ?: 0f) >= 0.95f)
    badge("cr100", "完美主义", "💎", "完播", "可判定的视频全部看完（至少 5 条）", allStats.completableCount >= 5 && allStats.completedCount == allStats.completableCount)
    badge("cr10", "开头达人", "🚪", "完播", "视频完播率 ≤ 10%（至少 20 条可判定）", allStats.completableCount >= 20 && (completionRateAll ?: 1f) <= 0.1f)

    // —— 速度（12）：平均观看比例两端 + 平均单次两端 + 秒划 ——
    badge("ar50", "半数即弃", "✂️", "速度", "平均观看比例 ≤ 50%（至少 8 条样本）", ratioSamples >= 8 && avgRatio <= 0.5)
    badge("ar25", "速滑认证", "🛼", "速度", "平均观看比例 ≤ 25%（至少 8 条样本）", ratioSamples >= 8 && avgRatio <= 0.25)
    badge("ar10", "浅尝即止", "👅", "速度", "平均观看比例 ≤ 10%（至少 8 条样本）", ratioSamples >= 8 && avgRatio <= 0.10)
    badge("ar80", "细嚼慢咽", "🐢", "速度", "平均观看比例 ≥ 80%（至少 10 条样本）", ratioSamples >= 10 && avgRatio >= 0.80)
    badge("ar95", "片尾不放", "🐌", "速度", "平均观看比例 ≥ 95%（至少 10 条样本）", ratioSamples >= 10 && avgRatio >= 0.95)
    badge("aw10", "十秒快闪", "✨", "速度", "平均单次观看 ≤ 10 秒（至少 30 条）", positiveEvents >= 30 && avgWatchSec <= 10)
    badge("aw30", "半分钟", "🥡", "速度", "平均单次观看 ≤ 30 秒（至少 50 条）", positiveEvents >= 50 && avgWatchSec <= 30)
    badge("aw3m", "慢热", "🍲", "速度", "平均单次观看 ≥ 3 分钟（至少 20 条）", positiveEvents >= 20 && avgWatchSec >= 180)
    badge("aw10m", "十分耐心", "🧊", "速度", "平均单次观看 ≥ 10 分钟（至少 10 条）", positiveEvents >= 10 && avgWatchSec >= 600)
    badge("zs50", "秒划五十", "💨", "速度", "秒划（0 秒即划走）50 条", zeroSecEvents >= 50)
    badge("zs200", "秒划二百", "🌪️", "速度", "秒划 200 条", zeroSecEvents >= 200)
    badge("zr50", "划水过半", "🏄", "速度", "秒划占比 ≥ 50%（至少 50 条事件）", totalEventsAll >= 50 && zeroSecEvents * 2 >= totalEventsAll)

    // —— 收藏（12）：收藏数量递进 + 收藏率梯度 + 稍后再看 ——
    badge("fv1", "第一颗", "⭐", "收藏", "收藏 1 条", favCount >= 1)
    badge("fv5", "五颗", "🌟", "收藏", "收藏 5 条", favCount >= 5)
    badge("fv10", "十颗", "🌠", "收藏", "收藏 10 条", favCount >= 10)
    badge("fv50", "半百收藏", "🧺", "收藏", "收藏 50 条", favCount >= 50)
    badge("fv100", "百藏", "🗃️", "收藏", "收藏 100 条", favCount >= 100)
    badge("fv200", "二百藏", "🗄️", "收藏", "收藏 200 条", favCount >= 200)
    badge("fv500", "五百藏", "🐘", "收藏", "收藏 500 条", favCount >= 500)
    badge("fr10", "藏十取一", "📥", "收藏", "收藏数 ≥ 历史数的 10%（历史 ≥ 20 条）", historyCount >= 20 && favRate >= 0.10)
    badge("fr30", "藏三取一", "🧲", "收藏", "收藏数 ≥ 历史数的 30%（历史 ≥ 20 条）", historyCount >= 20 && favRate >= 0.30)
    badge("wl5", "稍后五条", "🔖", "收藏", "稍后再看攒 5 条", laterCount >= 5)
    badge("wl20", "稍后二十", "📑", "收藏", "稍后再看攒 20 条", laterCount >= 20)
    badge("wl50", "稍后五十", "🧾", "收藏", "稍后再看攒 50 条", laterCount >= 50)

    // —— 下载（9）：下载条数递进 ——
    badge("dl1", "第一载", "💾", "下载", "下载 1 条", downloadCount >= 1)
    badge("dl3", "三载", "💽", "下载", "下载 3 条", downloadCount >= 3)
    badge("dl5", "五载", "📀", "下载", "下载 5 条", downloadCount >= 5)
    badge("dl10", "十载", "🗜️", "下载", "下载 10 条", downloadCount >= 10)
    badge("dl20", "二十载", "🧰", "下载", "下载 20 条", downloadCount >= 20)
    badge("dl30", "三十载", "🎒", "下载", "下载 30 条", downloadCount >= 30)
    badge("dl50", "半百下载", "🚚", "下载", "下载 50 条", downloadCount >= 50)
    badge("dl100", "百载", "🚛", "下载", "下载 100 条", downloadCount >= 100)
    badge("dl200", "二百载", "🚢", "下载", "下载 200 条", downloadCount >= 200)

    // —— 搜索（7）：搜索历史词数递进（上限 20 词） ——
    badge("se1", "第一次搜", "🔎", "搜索", "留下 1 个搜索词", searchTermCount >= 1)
    badge("se3", "三个词", "🗝️", "搜索", "留下 3 个搜索词", searchTermCount >= 3)
    badge("se5", "五个词", "🧵", "搜索", "留下 5 个搜索词", searchTermCount >= 5)
    badge("se8", "八个词", "🪡", "搜索", "留下 8 个搜索词", searchTermCount >= 8)
    badge("se10", "十个词", "📇", "搜索", "留下 10 个搜索词", searchTermCount >= 10)
    badge("se15", "十五个词", "🗞️", "搜索", "留下 15 个搜索词", searchTermCount >= 15)
    badge("se20", "词库满格", "📚", "搜索", "搜索历史攒满 20 个词", searchTermCount >= 20)

    // —— 时段（20）：各时段"看过"存在性 + 覆盖度 + 占比 + 跨夜 + 常客计数 ——
    badge("hr_night", "深夜档", "🌚", "时段", "在 0:00–5:59 看过内容", hasDeepNightEvent)
    badge("hr_wolf", "凌晨两三点", "🦇", "时段", "在 2:00–3:59 看过内容", hasWolfEvent)
    badge("hr_dawn", "破晓档", "🌄", "时段", "在 5:00–6:59 看过内容", hasDawnEvent)
    badge("hr_morning", "清晨档", "🌤️", "时段", "在 6:00–8:59 看过内容", hasMorningEvent)
    badge("hr_rush1", "早高峰", "🚌", "时段", "工作日 7:00–8:59 看过内容", hasCommuteMornEvent)
    badge("hr_noon", "午间档", "🍚", "时段", "在 11:00–13:59 看过内容", hasNoonEvent)
    badge("hr_work", "上班档", "🖥️", "时段", "工作日 9:00–17:59 看过内容", hasWorkdayDayEvent)
    badge("hr_rush2", "晚高峰", "🚗", "时段", "工作日 17:00–18:59 看过内容", hasCommuteEveEvent)
    badge("hr_prime", "黄金档", "🎪", "时段", "在 20:00–22:59 看过内容", hasPrimeEvent)
    badge("cov12", "十二时辰", "🕐", "时段", "24 小时里 ≥ 12 个小时段有观看", hoursCovered >= 12)
    badge("cov24", "全天无休", "🌐", "时段", "一天 24 个小时段全看过", hoursCovered >= 24)
    badge("ns30", "深夜三成", "🌉", "时段", "深夜(23–6点)观看占比 ≥ 30%（累计 ≥ 10 分钟）", totalSecAll >= 600 && shareOf(nightSec) >= 0.30)
    badge("we60", "周末型", "🎈", "时段", "周末观看占比 ≥ 60%（累计 ≥ 10 分钟）", totalSecAll >= 600 && shareOf(weekendSec) >= 0.60)
    badge("we80", "周末纯享", "🎮", "时段", "周末观看占比 ≥ 80%（累计 ≥ 10 分钟）", totalSecAll >= 600 && shareOf(weekendSec) >= 0.80)
    badge("on1", "一次通宵", "🥱", "时段", "同一天 20–23 点和 0–4 点都有观看", overnightDays >= 1)
    badge("on3", "三度通宵", "☄️", "时段", "通宵（同日跨夜）≥ 3 天", overnightDays >= 3)
    badge("ne20", "深夜常客", "🌔", "时段", "深夜(23–6点)观看 ≥ 20 条", nightEvents >= 20)
    badge("nn20", "午间常客", "🥗", "时段", "午间(11–14点)观看 ≥ 20 条", noonEvents >= 20)
    badge("pe20", "黄金档常客", "🧨", "时段", "黄金档(20–23点)观看 ≥ 20 条", primeEvents >= 20)
    badge("wd40", "摸鱼达人", "🪝", "时段", "工作日 9–18 点观看占比 ≥ 40%（累计 ≥ 30 分钟）", totalSecAll >= 1800 && shareOf(workdayDaySec) >= 0.40)

    // —— 类型（15）：视频/图文/音频占比梯度 + 三栖均衡 + 短视频构成（口径同"内容偏好"卡） ——
    badge("vd50", "视频过半", "📹", "类型", "视频观看占比 ≥ 50%（累计 ≥ 10 分钟）", totalSecAll >= 600 && videoShare >= 0.50)
    badge("vd65", "视频主力", "🎬", "类型", "视频观看占比 ≥ 65%（累计 ≥ 10 分钟）", totalSecAll >= 600 && videoShare >= 0.65)
    badge("vd80", "视频为王", "🤴", "类型", "视频观看占比 ≥ 80%（累计 ≥ 10 分钟）", totalSecAll >= 600 && videoShare >= 0.80)
    badge("vd95", "纯血视频", "🎭", "类型", "视频观看占比 ≥ 95%（累计 ≥ 10 分钟）", totalSecAll >= 600 && videoShare >= 0.95)
    badge("ph30", "图文三成", "🖼", "类型", "图文观看占比 ≥ 30%（累计 ≥ 10 分钟）", totalSecAll >= 600 && photoShare >= 0.30)
    badge("ph50", "图文半壁", "📰", "类型", "图文观看占比 ≥ 50%（累计 ≥ 10 分钟）", totalSecAll >= 600 && photoShare >= 0.50)
    badge("ph60", "图文为主", "📄", "类型", "图文观看占比 ≥ 60%（累计 ≥ 10 分钟）", totalSecAll >= 600 && photoShare >= 0.60)
    badge("ph90", "纯血图文", "🧾", "类型", "图文观看占比 ≥ 90%（累计 ≥ 10 分钟）", totalSecAll >= 600 && photoShare >= 0.90)
    badge("au20", "音频二成", "🔈", "类型", "音频观看占比 ≥ 20%（累计 ≥ 10 分钟）", totalSecAll >= 600 && audioShare >= 0.20)
    badge("au35", "音频三成半", "🔉", "类型", "音频观看占比 ≥ 35%（累计 ≥ 10 分钟）", totalSecAll >= 600 && audioShare >= 0.35)
    badge("au50", "音频半壁", "🔊", "类型", "音频观看占比 ≥ 50%（累计 ≥ 10 分钟）", totalSecAll >= 600 && audioShare >= 0.50)
    badge("au80", "纯血音频", "📻", "类型", "音频观看占比 ≥ 80%（累计 ≥ 10 分钟）", totalSecAll >= 600 && audioShare >= 0.80)
    badge("tri", "三栖选手", "🐸", "类型", "视频/图文/音频占比各 ≥ 10%（累计 ≥ 10 分钟）", totalSecAll >= 600 && videoShare >= 0.10 && photoShare >= 0.10 && audioShare >= 0.10)
    badge("bal", "均衡饮食", "🥘", "类型", "视频/图文/音频占比各 ≥ 20%（累计 ≥ 10 分钟）", totalSecAll >= 600 && videoShare >= 0.20 && photoShare >= 0.20 && audioShare >= 0.20)
    badge("sv70", "短平快", "💫", "类型", "短视频(≤60s)占视频事件 ≥ 70%（视频事件 ≥ 20 条）", videoEvents >= 20 && shortVideoShare >= 0.70)

    // —— 共鸣（30）：常看频道的题材归类（按频道标题关键词识别，观看秒数加权），15 题材 × 双档 ——
    badge("g_anime1", "初入二次元", "🎏", "共鸣", "动漫类频道观看 ≥ 30 分钟（按频道标题识别）", genreSecOf("anime") >= 1800)
    badge("g_anime2", "老二次元", "🎌", "共鸣", "动漫类频道观看 ≥ 3 小时（按频道标题识别）", genreSecOf("anime") >= 3 * 3600)
    badge("g_drama1", "开始追剧", "🧃", "共鸣", "剧集类频道观看 ≥ 30 分钟（按频道标题识别）", genreSecOf("drama") >= 1800)
    badge("g_drama2", "追剧狂魔", "🎭", "共鸣", "剧集类频道观看 ≥ 3 小时（按频道标题识别）", genreSecOf("drama") >= 3 * 3600)
    badge("g_movie1", "初进影院", "🎦", "共鸣", "电影类频道观看 ≥ 30 分钟（按频道标题识别）", genreSecOf("movie") >= 1800)
    badge("g_movie2", "光影常客", "🎞️", "共鸣", "电影类频道观看 ≥ 3 小时（按频道标题识别）", genreSecOf("movie") >= 3 * 3600)
    badge("g_photo1", "开始鉴赏", "🎨", "共鸣", "写真/摄影类频道观看 ≥ 30 分钟（按频道标题识别）", genreSecOf("photo2") >= 1800)
    badge("g_photo2", "资深鉴赏家", "🖌️", "共鸣", "写真/摄影类频道观看 ≥ 3 小时（按频道标题识别）", genreSecOf("photo2") >= 3 * 3600)
    badge("g_asmr1", "耳边风", "🪶", "共鸣", "音声/助眠类频道观看 ≥ 30 分钟（按频道标题识别）", genreSecOf("asmr") >= 1800)
    badge("g_asmr2", "深度助眠", "😪", "共鸣", "音声/助眠类频道观看 ≥ 3 小时（按频道标题识别）", genreSecOf("asmr") >= 3 * 3600)
    badge("g_soft1", "捡到宝了", "🔧", "共鸣", "软件/资源类频道观看 ≥ 30 分钟（按频道标题识别）", genreSecOf("software") >= 1800)
    badge("g_soft2", "军火充沛", "🔩", "共鸣", "软件/资源类频道观看 ≥ 3 小时（按频道标题识别）", genreSecOf("software") >= 3 * 3600)
    badge("g_game1", "初入峡谷", "🎲", "共鸣", "游戏类频道观看 ≥ 30 分钟（按频道标题识别）", genreSecOf("game") >= 1800)
    badge("g_game2", "峡谷老兵", "🎮", "共鸣", "游戏类频道观看 ≥ 3 小时（按频道标题识别）", genreSecOf("game") >= 3 * 3600)
    badge("g_food1", "深夜放毒", "🍢", "共鸣", "美食类频道观看 ≥ 30 分钟（按频道标题识别）", genreSecOf("food") >= 1800)
    badge("g_food2", "榨菜自由", "🥡", "共鸣", "美食类频道观看 ≥ 3 小时（按频道标题识别）", genreSecOf("food") >= 3 * 3600)
    badge("g_music1", "单曲循环", "🔂", "共鸣", "音乐类频道观看 ≥ 30 分钟（按频道标题识别）", genreSecOf("music") >= 1800)
    badge("g_music2", "耳朵怀孕", "🎼", "共鸣", "音乐类频道观看 ≥ 3 小时（按频道标题识别）", genreSecOf("music") >= 3 * 3600)
    badge("g_tech1", "参数入门", "📱", "共鸣", "科技/数码类频道观看 ≥ 30 分钟（按频道标题识别）", genreSecOf("tech") >= 1800)
    badge("g_tech2", "赛博神仙", "🤖", "共鸣", "科技/数码类频道观看 ≥ 3 小时（按频道标题识别）", genreSecOf("tech") >= 3 * 3600)
    badge("g_pet1", "开始云吸", "🐈", "共鸣", "萌宠类频道观看 ≥ 30 分钟（按频道标题识别）", genreSecOf("pet") >= 1800)
    badge("g_pet2", "猫片管够", "🐈‍⬛", "共鸣", "萌宠类频道观看 ≥ 3 小时（按频道标题识别）", genreSecOf("pet") >= 3 * 3600)
    badge("g_funny1", "快乐源泉", "⛲", "共鸣", "搞笑类频道观看 ≥ 30 分钟（按频道标题识别）", genreSecOf("funny") >= 1800)
    badge("g_funny2", "哈哈哈十级", "🤣", "共鸣", "搞笑类频道观看 ≥ 3 小时（按频道标题识别）", genreSecOf("funny") >= 3 * 3600)
    badge("g_study1", "学了一点", "✏️", "共鸣", "学习/教程类频道观看 ≥ 30 分钟（按频道标题识别）", genreSecOf("study") >= 1800)
    badge("g_study2", "卷出天际", "📏", "共鸣", "学习/教程类频道观看 ≥ 3 小时（按频道标题识别）", genreSecOf("study") >= 3 * 3600)
    badge("g_fin1", "初入股市", "📈", "共鸣", "财经类频道观看 ≥ 30 分钟（按频道标题识别）", genreSecOf("finance") >= 1800)
    badge("g_fin2", "韭菜成精", "💹", "共鸣", "财经类频道观看 ≥ 3 小时（按频道标题识别）", genreSecOf("finance") >= 3 * 3600)
    badge("g_novel1", "书荒自救", "📕", "共鸣", "小说/网文类频道观看 ≥ 30 分钟（按频道标题识别）", genreSecOf("novel") >= 1800)
    badge("g_novel2", "网文老饕", "📗", "共鸣", "小说/网文类频道观看 ≥ 3 小时（按频道标题识别）", genreSecOf("novel") >= 3 * 3600)

    // —— 特殊（11）：跨维度组合 / 反向 / 回归型现象 ——
    badge("spch15", "单厨狂喜", "🍳", "特殊", "单日同一频道观看 ≥ 15 条", maxDaySameChannelCnt >= 15)
    badge("spch30", "单厨爆肝", "🍖", "特殊", "单日同一频道观看 ≥ 30 条", maxDaySameChannelCnt >= 30)
    badge("spev50", "火力全开", "🧯", "特殊", "单日观看 ≥ 50 条", maxDayEvents >= 50)
    badge("spev100", "一日百条", "🎇", "特殊", "单日观看 ≥ 100 条", maxDayEvents >= 100)
    badge("spghost", "诈尸回归", "🧟", "特殊", "断档 ≥ 14 天后又在近 7 天回归", maxGapDays >= 14 && recentActive7)
    badge("sptri", "三修居士", "☯️", "特殊", "收藏、下载、稍后再看全都用过", favCount >= 1 && downloadCount >= 1 && laterCount >= 1)
    badge("spnd1h", "深夜一时", "🌘", "特殊", "单日深夜(23–6点)观看 ≥ 1 小时", maxNightDaySec >= 3600)
    badge("spwe2h", "周末双时", "🥳", "特殊", "周末单日观看 ≥ 2 小时", maxWeekendDaySec >= 2 * 3600)
    badge("sph1h3", "连续沉浸", "🫧", "特殊", "连续 3 天每天观看 ≥ 1 小时", streakOver1h >= 3)
    badge("splurk", "纯看党", "👀", "特殊", "观看 ≥ 100 条且零收藏/零下载/零稍后", totalEventsAll >= 100 && favCount == 0 && downloadCount == 0 && laterCount == 0)
    badge("spdawn5", "三更灯火", "🪔", "特殊", "累计 5 天在 0:00–5:59 看过内容", deepNightDays >= 5)

    // 类目顺序 = 定义顺序（groupBy 底层 LinkedHashMap 保序）；组内"达成的在前"（稳定排序保住同状态内的定义顺序）
    val badges = badgeList
        .groupBy { it.category }
        .flatMap { (_, list) -> list.sortedByDescending { it.achieved } }

    return WatchReport(
        // —— 旧字段保留（周窗口口径），兼容既有引用 ——
        weekTotalSec = weekStats.totalSec,
        todayTotalSec = todayStats.totalSec,
        topChannels = weekStats.topChannels,
        videoSec = weekStats.videoSec,
        photoSec = weekStats.photoSec,
        audioSec = weekStats.audioSec,
        hourHistogram = weekStats.hourHistogram,
        weekEventCount = weekStats.eventCount,
        // —— 新增：三档范围聚合 / 近 7 天趋势 / 连续与累计观看天数 ——
        ranges = mapOf(
            WatchRange.TODAY to todayStats,
            WatchRange.WEEK to weekStats,
            WatchRange.ALL to allStats,
        ),
        dailyTrend = dailyTrend,
        streakDays = streakDays,
        activeDays = activeDays,
        // —— 花样层：人格 / 近 12 周热力图 / "最"时刻 / 成就徽章（全局口径） ——
        persona = persona,
        personaSubs = personaSubs,
        heatmap = heatmap,
        records = records,
        badges = badges,
    )
}

/** #话题 提取：# 后连续的字母/数字/下划线（\p{L} 覆盖中日韩文字），2..20 字，纯数字话题也收 */
private val HashtagRegex = Regex("#([\\p{L}\\p{N}_]{2,20})")

/**
 * 类型共鸣词组（观看报告花样层"类型共鸣"族用）：id + 频道标题关键词。
 * 匹配方式：看过的频道标题转小写后 contains 命中即把该频道观看秒数计入词组（词组间不互斥）。
 * 选词原则宁缺毋滥：只收指向性强、不易误伤的词（如"番"单字容易误伤"番茄"，不收）。
 */
private data class GenreKeywordGroup(val id: String, val keywords: List<String>)

/** 15 个题材词组：人格"类型共鸣"族吃前 13 个，徽章"共鸣"类 15 个全吃 */
private val GenreKeywordGroups = listOf(
GenreKeywordGroup("anime", listOf("动漫", "番剧", "新番", "二次元", "动画", "漫画", "acg", "anime", "cosplay", "宅舞")),
GenreKeywordGroup("drama", listOf("剧集", "连续剧", "追剧", "美剧", "韩剧", "日剧", "英剧", "电视剧", "网剧")),
GenreKeywordGroup("movie", listOf("电影", "影评", "影视", "大片", "院线")),
GenreKeywordGroup("photo2", listOf("写真", "摄影", "街拍", "模特", "约拍")),
GenreKeywordGroup("asmr", listOf("asmr", "音声", "助眠", "哄睡", "耳语")),
GenreKeywordGroup("software", listOf("软件", "破解", "绿色版", "资源分享", "黑科技", "工具箱", "安装包")),
GenreKeywordGroup("game", listOf("游戏", "电竞", "手游", "王者", "原神", "吃鸡", "开黑", "英雄联盟")),
GenreKeywordGroup("food", listOf("美食", "吃播", "菜谱", "探店", "深夜食堂", "做饭", "烘焙")),
GenreKeywordGroup("music", listOf("音乐", "歌曲", "歌单", "翻唱", "电音", "说唱")),
GenreKeywordGroup("tech", listOf("科技", "数码", "评测", "开箱", "手机", "电脑", "硬件", "极客")),
GenreKeywordGroup("pet", listOf("萌宠", "猫咪", "狗狗", "喵", "汪星人", "宠物", "仓鼠")),
GenreKeywordGroup("funny", listOf("搞笑", "沙雕", "段子", "爆笑", "鬼畜", "整活", "名场面")),
GenreKeywordGroup("study", listOf("学习", "教程", "课程", "英语", "考研", "编程", "教学", "干货")),
GenreKeywordGroup("finance", listOf("财经", "股票", "股市", "基金", "币圈", "比特币", "理财", "投资")),
GenreKeywordGroup("novel", listOf("小说", "网文", "推文", "书荒", "连载")),
)

/** 从 caption 提取 #话题 集合（去 # 前缀、去重）；无话题返回空集 */
private fun extractHashtags(caption: String): Set<String> =
HashtagRegex.findAll(caption).mapTo(LinkedHashSet()) { it.groupValues[1] }
