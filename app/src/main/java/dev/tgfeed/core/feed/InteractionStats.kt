package dev.tgfeed.core.feed

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 互动统计 / 评估闭环（P1 #3，修 ALGO-AUDIT §2.8「无评估闭环」与 §5 名不副实表
 * 「指标体系零实现」）：自采自算近 7 天推荐 Feed 的五项核心指标——
 * **曝光数 / 完播率 / 平均观看时长 / 互动率 / 负反馈率**。
 *
 * 为什么自立门户（边界约束：LocalStore/AppPrefs 本轮冻结）：
 * watch_events 没有"互动"语义（点赞/收藏/下载不在其中），likes/favorites 又无时间戳，
 * 两边都凑不出"近 7 天互动率/负反馈率"——故本类自带一份轻量事件流水
 * （内存环形缓冲 + 独立 JSON 落盘），事件口径与权重侧严格对齐：
 *  - 曝光（impression）：视频页条目成为主位（settleWatchEvent 起计）；
 *    图文/音频以"有效观看"（recordMediaView 过 1s 噪声闸）近似一次曝光——
 *    图文流没有独立的"入屏"回调，这是冻结边界内能拿到的最诚实口径；
 *  - 观看（watch）：翻页/离场结算，watchSec 为 P0-2 心跳供给的真实播放秒数；
 *  - 正互动：like / favorite / download（share / comment 常量与 kind 预留，
 *    UI 接线后自动入统，无需改本类）；P1.5 新增 replay（重看）/ unmute（主动开声音）/
 *    fullscreen（主动全屏）三个细分，复用本入口；
 *  - 负反馈：dislike（不感兴趣）/ skip（快划，watchSec < SKIP_FAST_SEC，与权重侧 skip 口径同源）。
 *
 * P1.5 #2 追加 SEEK 事件（拖拽位置信号）：用户主动跳转的"落点秒数/总时长"，
 * 落点借 Ev.watchSec 字段承载（durationSec 仍为原始条时长），聚合出
 * 落点均值/头尾占比——"拖到后段 = 内容有高潮"、"老拖开头 = 前奏太长"的分布型证据；
 * 只进指标不进权重（分布信号先看分布再定策略）。Kind 枚举只许尾部追加
 * （落盘存 ordinal，中间插入会让老文件语义错位）。
 *
 * 指标口径（computeStats，窗口 [STATS_WINDOW_MS]=7 天）：
 *  - 完播率 = 比例 ≥ [COMPLETE_RATIO] 的观看事件 / 有真实时长的观看事件
 *    （图文等无时长事件不进分母，防"图文恒完播"虚高——ALGO-AUDIT §2.2 已点名该风险）；
 *  - 平均观看时长 = 全部观看事件 watchSec 均值（含快划 0 秒，诚实反映"刷得多快"）；
 *  - 互动率 = 正互动数 / 曝光数；负反馈率 = 负反馈数 / 曝光数。
 *
 * 落盘纪律（与 LocalStore/TasteRadar 同款）：
 *  - tmp + rename 原子写；写盘节流（距上次 ≥ [PERSIST_THROTTLE_MS] 且有脏数据才写）；
 *  - 指标重算节流（≥ [COMPUTE_THROTTLE_MS]；启动时由 FeedViewModel 强制算一次打日志）；
 *  - 指标可见性：Log.i 一行输出（调参入口），最新快照同步写进 stateFile 的
 *    "lastStats" 字段（adb pull 可查）；超过 [PRUNE_AGE_MS] 的老事件在重算时剪除。
 *
 * 消费边界（R122 明确标注）：**本类指标仅诊断用，不回哺排序**。曝光/完播率/互动率/
 * 负反馈率/拖拽落点五项指标的消费点只有 Log.i 日志与 lastStats 落盘快照，
 * 不接任何排序/权重参数——这是有意为之的开环：指标是"推荐系统健康度"的观察面，
 * 直接接回排序会引入"指标噪声自激"（排序影响指标、指标再调排序的反馈震荡）。
 * 若未来要把某项指标接回排序，须先评估该指标的口径稳定性与反馈回路风险，
 * 并删除本段声明。类名"评估闭环"的"环"到日志/落盘为止，请勿据此推断存在自动调参。
 *
 * 线程模型：record* 可能来自主线程（互动回调）或 IO 线程（观看结算），内部一把锁
 * 保护环形缓冲；文件读写全部甩到自带 IO 协程作用域，绝不卡调用方线程。
 */
class InteractionStats(
    /** 落盘位置（{"events":[...],"lastStats":{...}}）；null = 仅内存统计（重启清零） */
    private val stateFile: File?,
) {

    /** 近 7 天聚合指标快照（computeStats 产出；各 rate 为 0~1 小数） */
    data class FeedStats(
        val windowDays: Int,        // 统计窗口（天）
        val impressions: Int,       // 曝光数
        val watchEvents: Int,       // 观看结算事件数
        val completionRate: Double, // 完播率（比例≥0.9 / 有真实时长事件）
        val avgWatchSec: Double,    // 平均观看时长（秒，含快划 0s）
        val interactions: Int,      // 正互动数（赞/藏/下载/分享/评论）
        val interactionRate: Double,// 互动率 = 正互动 / 曝光
        val negatives: Int,         // 负反馈数（dislike + skip）
        val negativeRate: Double,   // 负反馈率 = 负反馈 / 曝光
        // ---- P1.5 #2 拖拽落点分布（SEEK 事件聚合；窗口内无 SEEK 事件时四项全 0） ----
        val seekEvents: Int = 0,       // 主动跳转（拖拽/点按进度条/±10s 家族）事件数
        val avgSeekRatio: Double = 0.0,   // 平均落点比例（0~1；偏高=奔后段，偏低=回看开头）
        val seekHeadRatio: Double = 0.0,  // 落点 <[SEEK_HEAD_MAX] 的占比（"老拖开头"≈前奏太长）
        val seekTailRatio: Double = 0.0,  // 落点 ≥[SEEK_TAIL_MIN] 的占比（"拖到后段"≈内容有高潮）
    )

    /**
     * 事件大类（落盘存 ordinal 省字节）。
     * 追加纪律：新大类只许加在尾部——ordinal 是落盘格式的一部分，中间插入会让老文件错位
     * （SEEK 即 P1.5 尾部追加；旧版 App 读到未知 ordinal 由 ensureLoadedLocked 跳过，向后兼容）。
     */
    private enum class Kind { IMPRESSION, WATCH, INTERACTION, NEGATIVE, SEEK }

    /** 单条统计事件（字段压到最少 + JSON 短 key，控制环形缓冲与文件体积） */
    private data class Ev(
        val kind: Kind,
        val sub: String,      // 互动/负反馈细分：like/favorite/download/share/comment | dislike/skip
        val chatId: Long,
        val watchSec: Int,
        val durationSec: Int, // 原始条时长；图文等无真实时长传 0（不进完播率分母）
        val ts: Long,
    )

    private val lock = Any()
    private val events = ArrayDeque<Ev>()
    private var loaded = false   // 懒加载标记：首次使用才读盘（常规路径首用在 VM init 的 IO 协程）
    private var dirty = false
    private var lastPersistMs = 0L
    private var lastComputeMs = 0L
    private var lastStats: FeedStats? = null

    /** 文件读写专用 IO 作用域（与 VM 同寿；SupervisorJob 防单次写盘失败拖垮后续） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---------------- 记录入口（调用方任意线程；仅内存操作 + 节流触发落盘/重算） ----------------

    /** 记一次曝光：条目成为主位 / 图文音频有效观看 */
    fun recordImpression(chatId: Long) =
        record(Ev(Kind.IMPRESSION, "", chatId, 0, 0, System.currentTimeMillis()))

    /** 记一次观看结算：真实播放秒数（心跳口径）与原始条时长（无真实时长传 0） */
    fun recordWatch(chatId: Long, watchSec: Int, durationSec: Int) =
        record(Ev(Kind.WATCH, "", chatId, watchSec, durationSec, System.currentTimeMillis()))

    /** 记一次正互动：like / favorite / download（share / comment 预留） */
    fun recordInteraction(sub: String, chatId: Long) =
        record(Ev(Kind.INTERACTION, sub, chatId, 0, 0, System.currentTimeMillis()))

    /** 记一次负反馈：dislike（不感兴趣）/ skip（快划 <2s）/ deadmedia（死媒体终态，R163 B5） */
    fun recordNegative(sub: String, chatId: Long) =
        record(Ev(Kind.NEGATIVE, sub, chatId, 0, 0, System.currentTimeMillis()))

    /**
     * R163 B5 死媒体终态记账：播放失败落到不可重试终态（fatalReasonOf 判「视频格式
     * 不支持或已损坏/资源已失效」类，VideoPlayerListener fatal 分支调用）记一笔。
     * 与 dislike/skip 同走 NEGATIVE 流水（sub 区分）；per-chatId 聚合供降权与丰度分
     * 负分量消费（[deadChannelStats] / IndexTierPolicy.RichnessSignals.deadRate）。
     */
    fun recordDeadMedia(chatId: Long) =
        record(Ev(Kind.NEGATIVE, DEAD_MEDIA_SUB, chatId, 0, 0, System.currentTimeMillis()))

    /**
     * 记一次主动跳转（P1.5 #2 拖拽位置信号）：targetSec = 跳转落点秒数（借 watchSec 字段承载），
     * durationSec = 原始条时长（<=0 的无法算比例，computeStats 聚合时丢弃）。
     * 只进指标不进权重——分布型信号（高潮点/前奏长）先观察分布再定权重策略。
     *
     * R204 TL-7：[key] = 条目 key（落进 sub 自由字符串字段——落盘格式零迁移，旧文件
     * SEEK 行 sub="" 自动兼容），供「本机单视频回放热力」key 级聚合
     * （[seekLandingsByKey]）；缺省空串=不参与 key 级聚合（chatId 级口径不受影响）。
     */
    fun recordSeek(chatId: Long, targetSec: Int, durationSec: Int, key: String = "") =
        record(Ev(Kind.SEEK, key, chatId, targetSec, durationSec, System.currentTimeMillis()))

    /**
     * 统一记录入口：内存环形缓冲 append（超 [MAX_EVENTS] 丢最老），并按节流阀
     * 触发异步落盘与指标重算。全部操作只碰内存，纳秒级返回。
     */
    private fun record(e: Ev) {
        var persist = false
        var compute = false
        synchronized(lock) {
            ensureLoadedLocked()
            events.addLast(e)
            while (events.size > MAX_EVENTS) events.removeFirst()
            dirty = true
            val now = e.ts
            if (now - lastPersistMs >= PERSIST_THROTTLE_MS) {
                lastPersistMs = now
                persist = true
            }
            if (now - lastComputeMs >= COMPUTE_THROTTLE_MS) compute = true
        }
        if (compute) computeStats()
        if (persist) persistAsync()
    }

    // ---------------- 指标计算（评估闭环出口） ----------------

    /**
     * 计算近 [STATS_WINDOW_MS]（7 天）指标：快照 Log.i 输出一行（调参可见），
     * 并标记脏数据让最新快照随下一次落盘写进 stateFile.lastStats。
     * force=false 时受 [COMPUTE_THROTTLE_MS] 节流（距上次不足则直接返回缓存快照）；
     * force=true 无条件重算（VM 启动时调用一次，保证启动日志里必有最新指标）。
     * 可在任意线程调用（锁内计算；万级事件聚合为微秒级开销）。
     */
    fun computeStats(nowMs: Long = System.currentTimeMillis(), force: Boolean = false): FeedStats? {
        val stats: FeedStats
        synchronized(lock) {
            if (!force && nowMs - lastComputeMs < COMPUTE_THROTTLE_MS) return lastStats
            lastComputeMs = nowMs
            ensureLoadedLocked()
            // 剪掉超出留存期的老事件（窗口 7 天 + 1 天余量），防长跑用户文件无限胀
            val cutoff = nowMs - PRUNE_AGE_MS
            while (events.isNotEmpty() && events.first().ts < cutoff) {
                events.removeFirst()
                dirty = true
            }
            val winStart = nowMs - STATS_WINDOW_MS
            var impressions = 0
            var watchCount = 0
            var watchSecSum = 0L
            var withDuration = 0
            var completions = 0
            var interactions = 0
            var negatives = 0
            // P1.5 #2 拖拽落点分布累加器（仅统计有真实时长的 SEEK 事件）
            var seekCount = 0
            var seekRatioSum = 0.0
            var seekHead = 0
            var seekTail = 0
            for (e in events) {
                if (e.ts < winStart) continue
                when (e.kind) {
                    Kind.IMPRESSION -> impressions++
                    Kind.WATCH -> {
                        watchCount++
                        watchSecSum += e.watchSec
                        if (e.durationSec > 0) {
                            withDuration++
                            val ratio = e.watchSec.toDouble() / e.durationSec
                            if (ratio >= COMPLETE_RATIO) completions++
                        }
                    }
                    Kind.INTERACTION -> interactions++
                    Kind.NEGATIVE -> negatives++
                    // P1.5 #2：SEEK 事件的 watchSec 字段是"跳转落点秒数"（见 recordSeek）
                    Kind.SEEK -> if (e.durationSec > 0) {
                        seekCount++
                        val r = (e.watchSec.toDouble() / e.durationSec).coerceIn(0.0, 1.0)
                        seekRatioSum += r
                        if (r < SEEK_HEAD_MAX) seekHead++   // 落点在开头 ≈ 前奏太长/回看
                        if (r >= SEEK_TAIL_MIN) seekTail++  // 落点在后段 ≈ 奔高潮
                    }
                }
            }
            stats = FeedStats(
                windowDays = (STATS_WINDOW_MS / (24L * 3600 * 1000)).toInt(),
                impressions = impressions,
                watchEvents = watchCount,
                completionRate = if (withDuration > 0) completions.toDouble() / withDuration else 0.0,
                avgWatchSec = if (watchCount > 0) watchSecSum.toDouble() / watchCount else 0.0,
                interactions = interactions,
                interactionRate = if (impressions > 0) interactions.toDouble() / impressions else 0.0,
                negatives = negatives,
                negativeRate = if (impressions > 0) negatives.toDouble() / impressions else 0.0,
                // P1.5 #2 拖拽落点分布（无 SEEK 事件时全 0，不污染既有指标）
                seekEvents = seekCount,
                avgSeekRatio = if (seekCount > 0) seekRatioSum / seekCount else 0.0,
                seekHeadRatio = if (seekCount > 0) seekHead.toDouble() / seekCount else 0.0,
                seekTailRatio = if (seekCount > 0) seekTail.toDouble() / seekCount else 0.0,
            )
            lastStats = stats
            dirty = true // 快照随下次落盘写入 lastStats 字段
        }
        Log.i(
            TAG,
            "computeStats(近${stats.windowDays}天): 曝光=${stats.impressions} " +
                "观看结算=${stats.watchEvents} 完播率=${"%.1f".format(stats.completionRate * 100)}% " +
                "平均观看=${"%.1f".format(stats.avgWatchSec)}s " +
                "互动率=${"%.2f".format(stats.interactionRate * 100)}%(${stats.interactions}) " +
                "负反馈率=${"%.2f".format(stats.negativeRate * 100)}%(${stats.negatives}) " +
                // P1.5 #2：拖拽落点分布（0 次跳转时该项恒 0，仅供调参观察）
                "拖拽=${stats.seekEvents}次 均落点=${"%.0f".format(stats.avgSeekRatio * 100)}% " +
                "头/尾=${"%.0f".format(stats.seekHeadRatio * 100)}%/" +
                "${"%.0f".format(stats.seekTailRatio * 100)}%",
        )
        return stats
    }

    /** 立即落盘（VM onCleared 兜底用；常规路径由节流自动触发，无需调） */
    fun flush() = persistAsync(force = true)

    // ---------------- R163 B5：死媒体 per-chatId 聚合（降权数据源） ----------------

    /**
     * 单频道死链统计（近 [STATS_WINDOW_MS] 窗口）：[deadEvents]=deadmedia 终态数、
     * [impressions]=曝光数（死链率分母）；[rate]=死链率（无曝光=0，防除零）。
     */
    data class DeadChannelStat(val deadEvents: Int, val impressions: Int) {
        val rate: Double get() = if (impressions > 0) deadEvents.toDouble() / impressions else 0.0
    }

    /**
     * 近 7 天窗口的 per-chatId 死链统计（R163 B5 死媒体降权与丰度分负分量的数据源，
     * 消费方：DeadMediaPolicy / IndexTierPolicy.RichnessSignals.deadRate）。
     * 锁内一遍聚合（万级事件微秒级，与 computeStats 同开销量级），任意线程可调用；
     * 只数 sub=[DEAD_MEDIA_SUB] 的 NEGATIVE 事件与 IMPRESSION 事件——dislike/skip
     * 不掺入（死链率与负反馈率是两个口径）。
     */
    fun deadChannelStats(nowMs: Long = System.currentTimeMillis()): Map<Long, DeadChannelStat> {
        synchronized(lock) {
            ensureLoadedLocked()
            val winStart = nowMs - STATS_WINDOW_MS
            var dead = HashMap<Long, Int>()
            var imps = HashMap<Long, Int>()
            for (e in events) {
                if (e.ts < winStart) continue
                when {
                    e.kind == Kind.IMPRESSION -> imps[e.chatId] = (imps[e.chatId] ?: 0) + 1
                    e.kind == Kind.NEGATIVE && e.sub == DEAD_MEDIA_SUB ->
                        dead[e.chatId] = (dead[e.chatId] ?: 0) + 1
                    else -> Unit
                }
            }
            if (dead.isEmpty()) return emptyMap()
            val out = HashMap<Long, DeadChannelStat>(dead.size)
            for ((id, d) in dead) out[id] = DeadChannelStat(d, imps[id] ?: 0)
            return out
        }
    }

    /**
     * 单频道正/负互动计数（R163 B4 需求信号聚合口，近 [STATS_WINDOW_MS] 窗）：
     * positive = INTERACTION 事件（like/favorite/download），negative = NEGATIVE
     * 事件**剔除 deadmedia**（dislike + skip——死链是供给侧信号已进丰度分 R 的
     * 负分量，需求侧不重复计负）。与 [deadChannelStats] 同款一遍聚合，任意线程可调。
     */
    fun channelInteractionCounts(nowMs: Long = System.currentTimeMillis()): Map<Long, ChannelInteractionStat> {
        synchronized(lock) {
            ensureLoadedLocked()
            val winStart = nowMs - STATS_WINDOW_MS
            var out: HashMap<Long, IntArray>? = null
            for (e in events) {
                if (e.ts < winStart) continue
                when (e.kind) {
                    Kind.INTERACTION -> {
                        val acc = out ?: HashMap<Long, IntArray>().also { out = it }
                        val st = acc.getOrPut(e.chatId) { IntArray(2) }
                        st[0]++
                    }
                    Kind.NEGATIVE -> if (e.sub != DEAD_MEDIA_SUB) {
                        val acc = out ?: HashMap<Long, IntArray>().also { out = it }
                        val st = acc.getOrPut(e.chatId) { IntArray(2) }
                        st[1]++
                    }
                    else -> Unit
                }
            }
            val acc = out ?: return emptyMap()
            val result = HashMap<Long, ChannelInteractionStat>(acc.size)
            for ((id, st) in acc) result[id] = ChannelInteractionStat(st[0], st[1])
            return result
        }
    }

    /** 单频道正/负互动计数（[channelInteractionCounts] 的聚合视图，R163 B4） */
    data class ChannelInteractionStat(val positive: Int, val negative: Int)

    // ---------------- R204 TL-7 拖拽落点 key 级 / 频道级聚合（回放热力数据源） ----------------

    /**
     * 本机单视频拖拽落点列表（R204 TL-7 进度条热力）：近 [STATS_WINDOW_MS] 窗口内
     * sub=[key]（recordSeek 的条目 key）且 durationSec>0 的 SEEK 事件，落点比例 0..1。
     * 与 [deadChannelStats] 同款锁内一遍聚合，任意线程可调用；窗口内无事件返回空表
     * （UI 侧按 [SeekHeatPolicy.MIN_SAMPLES] 样本闸决定不渲染）。
     */
    fun seekLandingsByKey(key: String, nowMs: Long = System.currentTimeMillis()): List<SeekHeatPolicy.Landing> {
        if (key.isBlank()) return emptyList()
        synchronized(lock) {
            ensureLoadedLocked()
            val winStart = nowMs - STATS_WINDOW_MS
            val out = ArrayList<SeekHeatPolicy.Landing>(16)
            for (e in events) {
                if (e.ts < winStart) continue
                if (e.kind == Kind.SEEK && e.durationSec > 0 && e.sub == key) {
                    out += SeekHeatPolicy.Landing(e.watchSec.toDouble() / e.durationSec)
                }
            }
            return out
        }
    }

    /**
     * 单频道拖拽落点列表（R204 TL-7 频道级「精彩段落」洞察）：近 [STATS_WINDOW_MS] 窗口内
     * 该 chatId 全部有真实时长的 SEEK 事件（key 级信息聚合时丢弃——频道口径只要分布）。
     * 锁内一遍聚合，任意线程可调用。
     */
    fun seekLandingsByChannel(chatId: Long, nowMs: Long = System.currentTimeMillis()): List<SeekHeatPolicy.Landing> {
        synchronized(lock) {
            ensureLoadedLocked()
            val winStart = nowMs - STATS_WINDOW_MS
            val out = ArrayList<SeekHeatPolicy.Landing>(32)
            for (e in events) {
                if (e.ts < winStart) continue
                if (e.kind == Kind.SEEK && e.durationSec > 0 && e.chatId == chatId) {
                    out += SeekHeatPolicy.Landing(e.watchSec.toDouble() / e.durationSec)
                }
            }
            return out
        }
    }

    // ---------------- 落盘（tmp + rename 原子写；IO 协程异步） ----------------

    /** 异步落盘：锁内取快照（事件拷贝 + lastStats + 清脏标记），锁外写文件 */
    private fun persistAsync(force: Boolean = false) {
        val f = stateFile ?: return
        val snapshot: Pair<List<Ev>, FeedStats?>
        synchronized(lock) {
            if (!dirty && !force) return
            dirty = false
            snapshot = events.toList() to lastStats
        }
        scope.launch {
            runCatching {
                val root = JSONObject()
                val arr = JSONArray()
                for (e in snapshot.first) {
                    arr.put(
                        JSONObject()
                            .put("k", e.kind.ordinal)
                            .put("s", e.sub)
                            .put("c", e.chatId)
                            .put("w", e.watchSec)
                            .put("d", e.durationSec)
                            .put("t", e.ts),
                    )
                }
                root.put("events", arr)
                snapshot.second?.let { s ->
                    root.put(
                        "lastStats",
                        JSONObject()
                            .put("windowDays", s.windowDays)
                            .put("impressions", s.impressions)
                            .put("watchEvents", s.watchEvents)
                            .put("completionRate", s.completionRate)
                            .put("avgWatchSec", s.avgWatchSec)
                            .put("interactions", s.interactions)
                            .put("interactionRate", s.interactionRate)
                            .put("negatives", s.negatives)
                            .put("negativeRate", s.negativeRate)
                            // P1.5 #2 拖拽落点分布（新增字段，旧版读取方自动忽略未知 key）
                            .put("seekEvents", s.seekEvents)
                            .put("avgSeekRatio", s.avgSeekRatio)
                            .put("seekHeadRatio", s.seekHeadRatio)
                            .put("seekTailRatio", s.seekTailRatio),
                    )
                }
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(root.toString())
                if (!tmp.renameTo(f)) {
                    // rename 失败（Windows 目标占用等）：直接写 + 清 tmp，与 LocalStore 同款兜底
                    runCatching { f.writeText(root.toString()) }
                    tmp.delete()
                }
            }.onFailure { Log.w(TAG, "统计落盘失败（内存态不受影响）: ${it.message}") }
        }
    }

    /** 懒加载：锁内首次使用时读盘；文件缺失/损坏按空表处理，不崩溃 */
    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val f = stateFile ?: return
        runCatching {
            if (!f.isFile) return@runCatching
            val arr = JSONObject(f.readText()).optJSONArray("events") ?: return@runCatching
            val kinds = Kind.entries
            val cutoff = System.currentTimeMillis() - PRUNE_AGE_MS
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val ts = o.optLong("t", 0L)
                if (ts <= 0L || ts < cutoff) continue // 无时间戳/超留存期的老数据直接丢
                val kind = kinds.getOrNull(o.optInt("k", -1)) ?: continue
                events.addLast(
                    Ev(
                        kind = kind,
                        sub = o.optString("s", ""),
                        chatId = o.optLong("c", 0L),
                        watchSec = o.optInt("w", 0),
                        durationSec = o.optInt("d", 0),
                        ts = ts,
                    ),
                )
            }
            while (events.size > MAX_EVENTS) events.removeFirst()
        }.onFailure { Log.w(TAG, "统计文件读取失败（按空表起步）: ${it.message}") }
    }

    companion object {
        private const val TAG = "InteractionStats"

        /** R163 B5：死媒体终态的负反馈 sub 标识（dislike/skip 之外的新成员，
         *  sub 是自由字符串——落盘格式零迁移；记账口 [recordDeadMedia] 与聚合口
         *  [deadChannelStats] 共用此唯一常量，防手抄漂移） */
        const val DEAD_MEDIA_SUB = "deadmedia"

        /** 统计窗口：近 7 天（与 decayedChannelWeights 的衰减半衰期同量级，口径对齐） */
        private const val STATS_WINDOW_MS = 7L * 24 * 3600 * 1000

        /** 事件留存期：窗口 + 1 天余量，超时在重算/加载时剪除（防文件无限胀） */
        private const val PRUNE_AGE_MS = 8L * 24 * 3600 * 1000

        /** 内存/落盘事件环形上限（约 2~3 天重度使用量；每条 JSON ~70B，体积可控） */
        private const val MAX_EVENTS = 2000

        /** 落盘节流：距上次写盘不足此间隔且有新事件时只在内存记账 */
        private const val PERSIST_THROTTLE_MS = 20_000L

        /** 指标重算节流：观看/互动是高频事件，每次记录都重算纯属浪费 */
        private const val COMPUTE_THROTTLE_MS = 10L * 60 * 1000

        /** 完播判定：观看比例 ≥ 此值视为完播（与 FeedViewModel.COMPLETE_RATIO 同口径） */
        private const val COMPLETE_RATIO = 0.9

        /** P1.5 #2 落点分桶边界：<20% 记"拖开头"（前奏太长信号），≥70% 记"拖后段"（奔高潮信号） */
        private const val SEEK_HEAD_MAX = 0.2
        private const val SEEK_TAIL_MIN = 0.7
    }
}
