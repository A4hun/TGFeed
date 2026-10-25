package dev.tgfeed.core.feed

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.exp

/**
 * R79 隐式负反馈账本（秒划负反馈强化 + LinUCB 条目级奖励归因）：
 *
 *  1. **秒划计数衰减账本**（业界"单次=暂时不想看、多次=不感兴趣"的计数升级口径）：
 *     每次秒划（watchSec < [SKIP_FAST_SEC]=2s）把时刻记入 频道账本 + 频道×内容类型账本
 *     两路时间戳流水（持久化 implicit_feedback.json，tmp+rename 原子写）；
 *     惩罚不看原始次数而看**有效计数** = Σ exp(-(now-ts)/[SKIP_DECAY_MS])（λ=24h），
 *     再按档位映射惩罚——单次秒划有效计数 ≈1，够不到任何档位（零额外惩罚，只走
 *     既有 -0.5 等效比例的基准口径）；两天内连划同频道 2 次才进 1 档，越密压得越狠：
 *       频道档：有效计数 ≥2 → -0.10；≥3.5 → -0.20；≥5 → -0.35
 *       类型档（同频道同内容类型）：≥2 → -0.08；≥4 → -0.15
 *     频道+类型合并最多 -0.5（合并地板 [COMBINED_FACTOR_FLOOR]，在 weightedShuffle
 *     消费点钳制——单侧各自地板 [FACTOR_FLOOR]），且只作排序乘区
 *     （weightedShuffle 乘子），不动
 *     窗口/占比/探索配额三条多样性硬约束——被压频道降权不消失，保底配额吃不掉。
 *
 *  2. **防过拟合闸（衰减 + 恢复双机制，用户口味会变）**：
 *      - 衰减：有效计数随时间指数消退（昨天的秒划今天只算 0.37 次），
 *        超 [SKIP_WINDOW_MS]=3 天的时间戳硬过期——上周秒划的频道本周自然翻身；
 *      - 恢复：该频道再来一次有效观看（≥2s）即抵扣最新 1 条秒划记录，
 *        完播（比例 ≥0.9）抵扣 2 条——重新爱看就能快速爬出负反馈坑；
 *      - 节奏画像缩放：浅尝辄止型用户的秒划噪声大（对什么都划得快），
 *        惩罚乘 [penaltyScale] 减半；深度观看型用户的秒划是真负反馈，满格生效。
 *
 *  3. **LinUCB 条目级奖励归因**（itemSettles，纯内存 LRU，与 LinUCB pending
 *     "不落盘、进程死即弃"同生命周期哲学）：FeedViewModel 每次观看结算把
 *     条目 key → (观看秒数/时长/是否回看/时刻) 入账；[itemReward] 供
 *     rankWeightedPage 的 LinUCB.settle 回调查询"该次探索选中后这条的实际结局"：
 *       秒划 → -0.4（探索失败，立即结算不等窗）；
 *       有效观看按观看比例分档 0.0/0.2/0.4/0.6（25%/60%/90% 边界）；图文
 *       （无真实时长）有效观看记 +0.3——音频有真实时长，与视频同走比例分档，
 *       不吃该档（旧注释把音频也写进 +0.3 档，名实不符，R110 修正口径）；
 *       回看再 +0.3（翻回来再看是超强正信号）；clamp 到 [-0.5, 1.0]。
 *     结算时刻早于探索选中时刻的旧账不算（防把上一会话的观看冤枉成本次探索结局）。
 *
 * 线程模型：公开方法全部锁内（@Synchronized 风格同 ExposureTracker）；
 * 调用方均在 IO 协程（观看结算/排序页输出），文件 IO 为 KB 级小文件且节流落盘。
 */
internal class ImplicitFeedback(private val stateFile: File?) {

    /** 条目级结算记录（LinUCB 延迟奖励归因用；纯内存，进程死即弃） */
    private class SettleRec(
        val watchSec: Int,
        val durationSec: Int,
        val replayed: Boolean,
        val ts: Long,
    )

    private val lock = Any()

    /** 频道秒划时刻流水（chatId → 时间戳升序，超 [SKIP_KEEP_PER_CHAN] 丢最旧） */
    private val chanSkips = HashMap<Long, ArrayList<Long>>()

    /** 频道×内容类型秒划时刻流水（"chatId:mediaType" → 时间戳升序） */
    private val typeSkips = HashMap<String, ArrayList<Long>>()

    /** 各频道上次已打日志的惩罚档位（日志只在档位跃迁时打，防每页刷屏） */
    private val lastLoggedTier = HashMap<Long, Int>()

    /** 条目级结算 LRU（key → 最近一次结算；超 [MAX_ITEM_SETTLES] 淘汰最老） */
    private val itemSettles = LinkedHashMap<String, SettleRec>()

    private var loaded = false
    private var sinceSave = 0
    private var lastSaveMs = 0L

    /**
     * 节奏画像惩罚系数（FeedViewModel 按 InterestProfile 节奏分型设置：
     * 浅尝辄止型 0.5 / 深度观看型 1.0 / 其余 0.8）。@Volatile：IO 线程写、排序线程读。
     */
    @Volatile
    var penaltyScale: Double = 0.8

    // ---------------- 记账入口（FeedViewModel 观看结算调用，IO 协程） ----------------

    /**
     * 一次观看结算统一入账：
     *  - 秒划（watchSec < [SKIP_FAST_SEC]）→ 频道 + 频道×类型两路秒划流水记时刻，
     *    档位跃迁时打结构化日志；
     *  - 有效观看（≥2s）→ 恢复抵扣（完播抵 2 条、普通抵 1 条最新秒划记录）；
     *  - 无论正负都写条目级结算（LinUCB 奖励归因底账）。
     */
    fun recordSettle(
        key: String,
        chatId: Long,
        mediaType: Int,
        watchSec: Int,
        durationSec: Int,
        replayed: Boolean,
    ) {
        if (chatId == 0L) return
        synchronized(lock) {
            ensureLoadedLocked()
            val now = System.currentTimeMillis()
            itemSettles[key] = SettleRec(watchSec, durationSec, replayed, now)
            while (itemSettles.size > MAX_ITEM_SETTLES) {
                val eldest = itemSettles.entries.first()
                itemSettles.remove(eldest.key)
            }
            if (watchSec < SKIP_FAST_SEC) {
                recordSkipLocked(chatId, mediaType, now)
            } else {
                recoverLocked(chatId, mediaType, watchSec, durationSec)
            }
            sinceSave++
            if (sinceSave >= SAVE_EVERY || now - lastSaveMs >= SAVE_INTERVAL_MS) saveLocked()
        }
    }

    // ---------------- LinUCB 奖励归因（rankWeightedPage 的 settle 回调调用） ----------------

    /**
     * 条目级奖励查询：该 key 在 [sinceTs]（探索选中时刻）之后有结算记录才作数
     * （旧账不归因到本次探索）；无记录返回 null（= 尚无信号，调用方按延迟奖励继续等）。
     * 奖励构成：秒划 -0.4 / 观看比例分档 0.0~0.6 / 图文（无真实时长）有效观看 +0.3
     * （音频有真实时长走比例分档，不在该档）/ 回看 +0.3。
     */
    fun itemReward(key: String, sinceTs: Long): Double? {
        synchronized(lock) {
            val s = itemSettles[key] ?: return null
            if (s.ts < sinceTs) return null // 旧账：探索选中之前的观看与本次无关
            if (s.watchSec < SKIP_FAST_SEC) return -0.4 // 秒划：探索失败，负奖励立即结算
            var r = when {
                s.durationSec <= 0 -> 0.3 // 图文/无真实时长：有效观看按中性偏正计
                else -> {
                    val ratio = (s.watchSec.toDouble() / s.durationSec).coerceIn(0.0, 1.0)
                    when {
                        ratio >= 0.9 -> 0.6  // 完播档
                        ratio >= 0.6 -> 0.4  // 看了大半
                        ratio >= 0.25 -> 0.2 // 看了一段
                        else -> 0.0          // 看了一眼：中性（罚权在秒划档，不双罚）
                    }
                }
            }
            if (s.replayed) r += 0.3 // 回看加成：翻回来再看是超强正信号
            return r.coerceIn(-0.5, 1.0)
        }
    }

    // ---------------- 排序乘区（rankWeightedPage/weightedShuffle 消费） ----------------

    /**
     * 排序页头快照（rankWeightedPage 每页取一次，weightedShuffle 全程查表，不逐条抢锁）：
     * chatId → DoubleArray(4) = [频道乘子, 视频类型乘子, 图文类型乘子, 音频类型乘子]，
     * 全部 ∈ (0, 1]，无任何惩罚的频道不进表（调用方缺省 1.0 直通）。
     */
    fun factorSnapshot(chatIds: Collection<Long>): Map<Long, DoubleArray> {
        synchronized(lock) {
            ensureLoadedLocked()
            val now = System.currentTimeMillis()
            val out = HashMap<Long, DoubleArray>()
            for (id in chatIds) {
                val tier = chanTierLocked(id, now)
                val chanF = if (tier == 0) 1.0
                else (1.0 + CHAN_PENALTY[tier] * penaltyScale).coerceIn(FACTOR_FLOOR, 1.0)
                val types = DoubleArray(3) { 1.0 }
                var anyType = false
                for (m in 0..2) {
                    val list = typeSkips[typeKey(id, m)] ?: continue
                    val n = effectiveCount(list, now)
                    val p = when {
                        n >= TYPE_T2_COUNT -> TYPE_T2_PENALTY
                        n >= TYPE_T1_COUNT -> TYPE_T1_PENALTY
                        else -> 0.0
                    } * penaltyScale
                    if (p != 0.0) {
                        types[m] = (1.0 + p).coerceIn(FACTOR_FLOOR, 1.0)
                        anyType = true
                    }
                }
                if (chanF < 1.0 || anyType) {
                    out[id] = doubleArrayOf(chanF, types[0], types[1], types[2])
                }
            }
            return out
        }
    }

    /** 兜底落盘（FeedViewModel.onCleared 调用；无脏数据零开销直通） */
    fun flush() {
        synchronized(lock) {
            if (loaded && sinceSave > 0) saveLocked()
        }
    }

    // ---------------- 内部实现（全部锁内调用） ----------------

    private fun typeKey(chatId: Long, mediaType: Int) = "$chatId:$mediaType"

    /** 有效计数：窗口内时间戳的指数衰减和（λ=[SKIP_DECAY_MS]，24h） */
    private fun effectiveCount(list: List<Long>, now: Long): Double {
        var sum = 0.0
        for (ts in list) {
            if (now - ts > SKIP_WINDOW_MS) continue
            sum += exp(-(now - ts).toDouble() / SKIP_DECAY_MS).coerceAtMost(1.0)
        }
        return sum
    }

    /** 频道惩罚档位：0 无 / 1 轻 / 2 中 / 3 重（有效计数 2.0 / 3.5 / 5.0 分界） */
    private fun chanTierLocked(chatId: Long, now: Long): Int {
        val list = chanSkips[chatId] ?: return 0
        val n = effectiveCount(list, now)
        return when {
            n >= CHAN_T3_COUNT -> 3
            n >= CHAN_T2_COUNT -> 2
            n >= CHAN_T1_COUNT -> 1
            else -> 0
        }
    }

    /** 记一笔秒划：两路流水各追加时刻（顺带硬过期清理），档位跃迁打结构化日志 */
    private fun recordSkipLocked(chatId: Long, mediaType: Int, now: Long) {
        val chan = chanSkips.getOrPut(chatId) { ArrayList() }
        pruneAndAppend(chan, now)
        val tkey = typeKey(chatId, mediaType)
        val type = typeSkips.getOrPut(tkey) { ArrayList() }
        pruneAndAppend(type, now)
        val eff = effectiveCount(chan, now)
        Log.d(
            TAG,
            "秒划记账: 频道尾号${tail(chatId)} 类型=$mediaType " +
                "有效计数=%.1f（原始 %d 条）".format(eff, chan.size),
        )
        val tier = chanTierLocked(chatId, now)
        val prev = lastLoggedTier[chatId] ?: 0
        if (tier != prev) {
            lastLoggedTier[chatId] = tier
            Log.i(
                TAG,
                "秒划负反馈档位跃迁: 频道尾号${tail(chatId)} 类型=$mediaType " +
                    "有效计数=%.1f 档位=$prev->$tier（惩罚 %.2f）".format(
                        eff, CHAN_PENALTY[tier] * penaltyScale,
                    ),
            )
        }
    }

    /**
     * 恢复抵扣（防过拟合闸的恢复半）：有效观看抵 1 条最新秒划、完播抵 2 条；
     * 频道与类型两路同抵。抵扣后档位回落打日志（"频道翻身"可见）。
     */
    private fun recoverLocked(chatId: Long, mediaType: Int, watchSec: Int, durationSec: Int) {
        val chan = chanSkips[chatId] ?: return
        if (chan.isEmpty()) return
        val completed = durationSec > 0 &&
            (watchSec.toDouble() / durationSec).coerceIn(0.0, 1.0) >= COMPLETE_RATIO
        val credits = if (completed) RECOVER_PER_COMPLETE else RECOVER_PER_WATCH
        repeat(credits.coerceAtMost(chan.size)) { chan.removeAt(chan.size - 1) }
        typeSkips[typeKey(chatId, mediaType)]?.let { type ->
            repeat(credits.coerceAtMost(type.size)) { type.removeAt(type.size - 1) }
            if (type.isEmpty()) typeSkips.remove(typeKey(chatId, mediaType))
        }
        if (chan.isEmpty()) chanSkips.remove(chatId)
        val now = System.currentTimeMillis()
        val tier = chanTierLocked(chatId, now)
        val prev = lastLoggedTier[chatId] ?: 0
        if (tier < prev) {
            lastLoggedTier[chatId] = tier
            Log.i(
                TAG,
                "秒划负反馈恢复: 频道尾号${tail(chatId)} 有效观看抵扣 $credits 条，" +
                    "档位=$prev->$tier（剩余有效计数 %.1f）".format(
                        chanSkips[chatId]?.let { effectiveCount(it, now) } ?: 0.0,
                    ),
            )
        }
    }

    /** 追加时刻并保持流水有界（超窗口硬过期清理 + 条数上限丢最旧） */
    private fun pruneAndAppend(list: ArrayList<Long>, now: Long) {
        list.removeAll { now - it > SKIP_WINDOW_MS }
        list.add(now)
        while (list.size > SKIP_KEEP_PER_CHAN) list.removeAt(0)
    }

    // ---------------- 持久化（implicit_feedback.json，tmp+rename 原子写） ----------------

    private fun saveLocked() {
        val f = stateFile ?: return
        sinceSave = 0
        lastSaveMs = System.currentTimeMillis()
        runCatching {
            val obj = JSONObject()
            obj.put("v", 1)
            val chanObj = JSONObject()
            for ((id, list) in chanSkips) {
                chanObj.put(id.toString(), JSONArray().apply { for (ts in list) put(ts) })
            }
            val typeObj = JSONObject()
            for ((k, list) in typeSkips) {
                typeObj.put(k, JSONArray().apply { for (ts in list) put(ts) })
            }
            obj.put("skips", chanObj)
            obj.put("typeSkips", typeObj)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(f)) {
                runCatching { f.writeText(obj.toString()) }
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "隐式反馈账本落盘失败（内存态不受影响）: ${it.message}") }
    }

    /** 懒加载（锁内首次触碰时读盘；超窗口时间戳直接丢，文件缺失/损坏按空账起步） */
    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val f = stateFile ?: return
        runCatching {
            if (!f.isFile) return@runCatching
            val now = System.currentTimeMillis()
            val obj = JSONObject(f.readText())
            val chanObj = obj.optJSONObject("skips")
            if (chanObj != null) {
                val keys = chanObj.keys()
                while (keys.hasNext()) {
                    val id = keys.next().toLongOrNull() ?: continue
                    val arr = chanObj.optJSONArray(id.toString()) ?: continue
                    val list = parseTsList(arr, now)
                    if (list.isNotEmpty()) chanSkips[id] = list
                }
            }
            val typeObj = obj.optJSONObject("typeSkips")
            if (typeObj != null) {
                val keys = typeObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val arr = typeObj.optJSONArray(k) ?: continue
                    val list = parseTsList(arr, now)
                    if (list.isNotEmpty()) typeSkips[k] = list
                }
            }
        }.onFailure { Log.w(TAG, "隐式反馈账本读取失败（按空账起步）: ${it.message}") }
    }

    /** 解析时间戳数组：超窗口硬过期 + 条数上限保尾 */
    private fun parseTsList(arr: JSONArray, now: Long): ArrayList<Long> {
        val list = ArrayList<Long>(arr.length())
        for (i in 0 until arr.length()) {
            val ts = arr.optLong(i, 0L)
            if (ts > 0L && now - ts <= SKIP_WINDOW_MS) list.add(ts)
        }
        while (list.size > SKIP_KEEP_PER_CHAN) list.removeAt(0)
        return list
    }

    /** 日志用频道尾号（不落完整 id，与既有日志口径一致） */
    private fun tail(chatId: Long) = chatId.toString().takeLast(6)

    companion object {
        private const val TAG = "ImplicitFeedback"

        /** 有效计数衰减常数 λ（毫秒）：24h——昨天的秒划今天只算 e^-1≈0.37 次 */
        private const val SKIP_DECAY_MS = 24L * 3600 * 1000

        /** 秒划记录硬窗口（毫秒）：3 天前的记录直接过期（衰减之外的硬闸，账本有界） */
        private const val SKIP_WINDOW_MS = 3L * 24 * 3600 * 1000

        /** 每路流水保留的最近时间戳条数（内存与落盘同保有界） */
        private const val SKIP_KEEP_PER_CHAN = 20

        /** 频道惩罚档位的有效计数分界：轻/中/重（单次秒划 ≈1，够不到任何档） */
        private const val CHAN_T1_COUNT = 2.0
        private const val CHAN_T2_COUNT = 3.5
        private const val CHAN_T3_COUNT = 5.0

        /** 频道惩罚表（按档位索引）：0 档无惩罚，越密压得越狠 */
        private val CHAN_PENALTY = doubleArrayOf(0.0, -0.10, -0.20, -0.35)

        /** 类型级惩罚分界与力度（同频道同内容类型反复秒划；独立于频道档的小幅惩罚） */
        private const val TYPE_T1_COUNT = 2.0
        private const val TYPE_T2_COUNT = 4.0
        private const val TYPE_T1_PENALTY = -0.08
        private const val TYPE_T2_PENALTY = -0.15

        /** 乘子地板：频道/类型单侧各自不击穿（降权不消失，保底配额吃不掉） */
        private const val FACTOR_FLOOR = 0.4

        /**
         * 合并乘子地板：频道 × 类型合并后不击穿 0.5（类注释"合并最多 -0.5"的落实，
         * 在 weightedShuffle 消费点钳制——本表只存单侧乘子，乘积在消费方形成）。
         * 此前只靠 [FACTOR_FLOOR] 单侧钳制，合并最差可达 0.16（-84%），
         * 与"降权不消失"的设计声称不符。
         */
        internal const val COMBINED_FACTOR_FLOOR = 0.5

        /** 完播判定（与 FeedViewModel.COMPLETE_RATIO 同口径镜像）：完播恢复抵扣加倍 */
        private const val COMPLETE_RATIO = 0.9

        /** 恢复抵扣条数：普通有效观看 / 完播 */
        private const val RECOVER_PER_WATCH = 1
        private const val RECOVER_PER_COMPLETE = 2

        /** 条目级结算 LRU 上限（LinUCB pending 上限 500 的裕量） */
        private const val MAX_ITEM_SETTLES = 600

        /** 落盘节流：每这么多次结算写一次 / 距上次至少这么久（任一满足即写） */
        private const val SAVE_EVERY = 5
        private const val SAVE_INTERVAL_MS = 60_000L
    }
}

/**
 * ImplicitFeedback 的进程级注册点（五个 Holder 的统一口径见 FeedSource.kt 中
 * ChannelFatigueHolder 类注释开头的"Holder 总约"，此处只记本件差异）：
 *
 *  - 注册语义：**后注册生效**（R109 M1 起）：新实例直接顶位。
 *    旧口径"首个注册生效"在 VM 重建后造成数据分裂——记账方（FeedViewModel）
 *    写自己持有的新账本，排序链（rankWeightedPage）却读着旧 VM 注册的死账本，
 *    两边各记各的。后注册生效保证记账方与排序链始终落在同一个活实例上；
 *    时刻流水持久化在 implicit_feedback.json，顶位只丢条目级结算的会话内存态
 *    （与 LinUCB pending 同生命周期哲学：进程/VM 重建即弃）。
 *  - 注册方/时机：FeedViewModel 构造时（VM 是记账方），先于首次 rankWeightedPage 取用。
 *  - 未注册降级：隐式降权乘区与条目级奖励归因整体跳过，排序链退化为无隐式反馈
 *    版本（单测即此路径）。
 */
internal object ImplicitFeedbackHolder {
    @Volatile
    private var ledger: ImplicitFeedback? = null

    /** 注册：新实例直接顶位（语义与理由见类注释） */
    fun register(instance: ImplicitFeedback): ImplicitFeedback {
        ledger = instance // @Volatile 单次写，天然线程安全
        return instance
    }

    fun current(): ImplicitFeedback? = ledger
}
