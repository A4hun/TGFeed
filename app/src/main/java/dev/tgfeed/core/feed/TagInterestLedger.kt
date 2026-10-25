package dev.tgfeed.core.feed

import android.util.Log
import dev.tgfeed.core.td.SearchTextKit
import dev.tgfeed.core.td.VideoItem
import org.json.JSONObject
import java.io.File
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * R298 真推荐引擎 · tag 轴 + 内容类型轴兴趣账本（design-recommendation.md §2.1 的
 * T/M 两轴落地——G3「tag/类型两个兴趣轴没有采集」的根治件）：
 *
 *  1. **T tag 轴**：fold 归一键 → {衰减和 s, 末次记账时刻 t, 首见原形 display}。
 *     观看结算时把该条目的 tag（item_tags 已解析优先，否则 caption #标签现提，
 *     同一把尺子 [CaptionParser.extractHashtags] + [SearchTextKit.fold]）按观看结局
 *     记账：有效观看 δ=(watchSec/10)∈[0.2,3.0]（taste_words 同口径同量级——
 *     「看得越久越算数」）；秒划（<[SKIP_FAST_SEC]）δ=-1.0——**tag 级负轴是
 *     本设计的新增表达面**（「不爱看这个话题」第一次可被表达；频道/类型级负反馈
 *     既有 ImplicitFeedback 原样生效，不重复造）。
 *     衰减合并是精确指数记账（O(1)/tag）：s = s·e^(−Δt/λ) + δ，读侧再衰减到 now；
 *     半衰 14 天（λ=[TAG_DECAY_MS]，对齐 InterestProfile 马尔可夫遗忘口径）。
 *     冷启动快速学习：累计有效观看 <[QUICK_LEARN_LIMIT] 笔时 δ×[QUICK_LEARN_GAIN]
 *     （稀疏信号期单次观看更算数，对齐设计 §6「前 50 次有效观看 ×2 增益」）。
 *
 *  2. **M 类型轴**：mediaType∈{0视频,1图文,2音频} → 同构衰减账本；读侧折算偏好系数
 *     [typeFactorSnapshot] ∈ [[TYPE_FACTOR_FLOOR],[TYPE_FACTOR_CAP]]（缺数据=1.0 直通）。
 *     半衰 7 天（行为节奏比结构偏好漂移快，对齐 InterestProfile RHYTHM_HALFLIFE）。
 *
 *  3. **先验段（prior，冷启动）**：订阅频道 topTags 账本 + 收藏/稍后再看/点赞条目的
 *     tag 灌入低档分（[PRIOR_WEIGHT] 档）——「订阅是用户亲手写的兴趣声明」，
 *     零行为也有画像。先验独立成区（不与观测段混算）：内容哈希守卫幂等重建
 *     （订阅/收藏集没变=零写），重灌=整区替换非累加；先验不衰减（ standing
 *     声明），观测段积累后自然盖过（量级 0.3 vs 观测 δ 累计）。
 *
 * 持久化：store/tag_interest.json，tmp+rename 原子写、节流落盘（[SAVE_EVERY] 笔或
 * [SAVE_INTERVAL_MS]）、懒加载闸（ensureLoadedLocked）——InterestProfile/ImplicitFeedback
 * 同款自立门户纪律（LocalStore 冻结边界内不加字段）。
 * 线程模型：全部可变状态锁内读写；调用方均在 IO 协程（观看结算/排序页头/召回装配）。
 */
internal class TagInterestLedger(
    private val stateFile: File?,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    /** tag 轴账本行（fold 键 → 衰减和/末次时刻/首见原形） */
    private class TagRow(var s: Double, var t: Long, var display: String)

    /** 类型轴账本行（多一个记账笔数 n——区分「无证据=中性 1.0」与「负证据」） */
    private class TypeRow(var s: Double, var t: Long, var n: Int)

    private val lock = Any()

    /** tag 轴观测段（fold → TagRow） */
    private val tags = HashMap<String, TagRow>()

    /** 类型轴观测段（mediaType 0..2 → TypeRow） */
    private val types = HashMap<Int, TypeRow>()

    /** 先验段（fold → (分值, 原形)）；整区替换式重灌，不衰减 */
    private val prior = HashMap<String, Pair<Double, String>>()

    /** 先验内容哈希（重建守卫：订阅/收藏集没变=零写零换算） */
    private var priorKey = ""

    /** 累计有效观看笔数（快速学习期判定；封顶 [QUICK_LEARN_LIMIT] 后停计） */
    private var validRecorded = 0

    private var loaded = false
    private var sinceSave = 0
    private var lastSaveMs = 0L

    // ---------------- 记账入口（FeedWatchPart 观看结算调用，IO 协程） ----------------

    /**
     * 一次观看结算入账：[tags] = 条目 fold 后 tag 集（调用方经 [foldTagsOf] 取得，
     * 空集=该条目无 tag 可记，类型轴照记）；[mediaType] 越界钳进 0..2。
     * 秒划同时扣 tag 轴与类型轴；有效观看同额加分。返回本笔记账的 tag 数（观测日志用）。
     */
    fun recordSettle(tags: Collection<String>, mediaType: Int, watchSec: Int, durationSec: Int): Int {
        val now = nowMs()
        synchronized(lock) {
            ensureLoadedLocked()
            val skip = watchSec < SKIP_FAST_SEC
            val gain = if (skip) {
                TAG_SKIP_DELTA
            } else {
                var g = (watchSec / 10.0).coerceIn(0.2, 3.0)
                if (validRecorded < QUICK_LEARN_LIMIT) g *= QUICK_LEARN_GAIN
                g
            }
            for (tag in tags) {
                val row = this.tags.getOrPut(tag) { TagRow(0.0, now, tag) }
                row.s = decayed(row.s, row.t, now, TAG_DECAY_MS) + gain
                row.t = now
            }
            val mt = mediaType.coerceIn(0, 2)
            val trow = types.getOrPut(mt) { TypeRow(0.0, now, 0) }
            trow.s = decayed(trow.s, trow.t, now, TYPE_DECAY_MS) + gain
            trow.t = now
            trow.n += 1
            if (!skip && validRecorded < QUICK_LEARN_LIMIT) validRecorded++
            sinceSave++
            if (sinceSave >= SAVE_EVERY || now - lastSaveMs >= SAVE_INTERVAL_MS) saveLocked()
            return tags.size
        }
    }

    // ---------------- 先验灌注（冷启动；VM 订阅/收藏恢复后 IO 协程调用） ----------------

    /**
     * 灌先验（幂等）：[priorTags] = fold 键 → (低档分, 原形)；[contentKey] = 内容哈希
     * （订阅 id 集+收藏/稍后/点赞 key 集的稳定摘要）。contentKey 与账面一致 = 原料没变，
     * 直通零写；不同则整区替换（先验是声明不是流水，替换即最新真相）。
     */
    fun seedPrior(priorTags: Map<String, Pair<Double, String>>, contentKey: String) {
        synchronized(lock) {
            ensureLoadedLocked()
            if (priorTags.isEmpty() && prior.isEmpty()) return
            if (contentKey == priorKey && priorTags.size == prior.size) return
            prior.clear()
            prior.putAll(priorTags)
            priorKey = contentKey
            Log.i(TAG, "R298 tag 轴先验灌注：${prior.size} 个 tag（来源集哈希变化，整区替换）")
            saveLocked()
        }
    }

    // ---------------- 排序/召回消费口（rankWeightedPage 页头 / 召回装配，IO 线程） ----------------

    /**
     * tag 轴归一化快照（排序分用）：观测段衰减到 now 后与先验段合流（同键取大者——
     * 先验是下限保底不是叠加，观测一旦超过先验档即接管），再按正分最大值归一：
     * 正分 ∈ (0,1]，负分按负分最大幅值归一到 [-1,0)——**负轴保留符号**，
     * 「连续秒划的话题」在排序分里第一次能产生负向乘区。
     * 全无信号返回空表（调用方零开销直通=冷启动现状）。
     */
    fun tagAxisSnapshot(): Map<String, Double> {
        synchronized(lock) {
            ensureLoadedLocked()
            if (tags.isEmpty() && prior.isEmpty()) return emptyMap()
            val now = nowMs()
            val merged = HashMap<String, Double>(tags.size + prior.size)
            for ((k, row) in tags) {
                val v = decayed(row.s, row.t, now, TAG_DECAY_MS)
                if (v != 0.0) merged[k] = v
            }
            for ((k, p) in prior) {
                val cur = merged[k]
                if (cur == null || p.first > cur) merged[k] = p.first
            }
            val maxPos = merged.values.filter { it > 0.0 }.maxOrNull() ?: 0.0
            val maxNeg = merged.values.filter { it < 0.0 }.minOrNull() ?: 0.0 // 最负值
            val out = HashMap<String, Double>(merged.size)
            for ((k, v) in merged) {
                out[k] = when {
                    v > 0.0 && maxPos > 0.0 -> v / maxPos
                    v < 0.0 && maxNeg < 0.0 -> -(v / maxNeg) // 负/负=正号 → 再取负，幅值 ∈ (0,1]
                    else -> 0.0
                }
            }
            return out
        }
    }

    /**
     * 召回用 top tag（原始衰减分降序，未归一——召回要的是「最爱的话题」绝对水位）：
     * 只出正分 tag（负轴话题不召回），原形随附（展示/解释用）。先验参与（冷启动期
     * 先验即召回种子）。上限 [topN]。
     */
    fun topInterestTags(topN: Int): List<Pair<String, String>> {
        synchronized(lock) {
            ensureLoadedLocked()
            val now = nowMs()
            val merged = HashMap<String, Pair<Double, String>>(tags.size + prior.size)
            for ((k, row) in tags) {
                val v = decayed(row.s, row.t, now, TAG_DECAY_MS)
                if (v > 0.0) merged[k] = v to row.display
            }
            for ((k, p) in prior) {
                if (p.first <= 0.0) continue
                val cur = merged[k]
                if (cur == null || p.first > cur.first) merged[k] = p.first to p.second
            }
            return merged.entries.sortedByDescending { it.value.first }
                .take(topN)
                .map { it.key to it.value.second }
        }
    }

    /**
     * 类型轴偏好系数快照（排序乘区用）：DoubleArray(3) ∈ [0.7,1.3]；
     * 无任何类型证据 = 全 1.0（冷启动/纯单类型观看零行为变化）。
     * 正分映射 [1.0, 1.3]（相对最强类型按比例）；负分映射 [0.7, 1.0)；
     * 有证据但当前衰减归零/无证据 = 1.0 中性。
     */
    fun typeFactorSnapshot(): DoubleArray {
        synchronized(lock) {
            ensureLoadedLocked()
            val now = nowMs()
            val scores = DoubleArray(3) { 0.0 }
            val hasEvidence = BooleanArray(3)
            for ((mt, row) in types) {
                val idx = mt.coerceIn(0, 2)
                scores[idx] = decayed(row.s, row.t, now, TYPE_DECAY_MS)
                hasEvidence[idx] = row.n > 0
            }
            val maxPos = scores.filterIndexed { i, _ -> hasEvidence[i] }.filter { it > 0.0 }.maxOrNull() ?: 0.0
            val maxNeg = scores.filterIndexed { i, _ -> hasEvidence[i] }.filter { it < 0.0 }.minOrNull() ?: 0.0
            val out = DoubleArray(3) { 1.0 }
            for (i in 0..2) {
                if (!hasEvidence[i]) continue
                val v = scores[i]
                out[i] = when {
                    v > 0.0 && maxPos > 0.0 ->
                        1.0 + (TYPE_FACTOR_CAP - 1.0) * (v / maxPos)
                    v < 0.0 && maxNeg < 0.0 ->
                        (1.0 + (1.0 - TYPE_FACTOR_FLOOR) * (v / -maxNeg))
                            .coerceAtLeast(TYPE_FACTOR_FLOOR) // v/maxNeg∈(0,1] → 1-0.3·x
                    else -> 1.0
                }
            }
            return out
        }
    }

    /** 画像是否有任何信号（先验或观测）——排序链据此决定兴趣乘区是否整体跳过 */
    fun hasAnySignal(): Boolean {
        synchronized(lock) {
            ensureLoadedLocked()
            return tags.isNotEmpty() || prior.isNotEmpty()
        }
    }

    /** 观测段累计有效观看笔数（快速学习期判定/诊断口径） */
    fun validWatchCount(): Int = synchronized(lock) { ensureLoadedLocked(); validRecorded }

    /** 兜底落盘（FeedViewModel.onCleared 调用；无脏数据零开销直通） */
    fun flush() {
        synchronized(lock) {
            if (loaded && sinceSave > 0) saveLocked()
        }
    }

    // ---------------- 持久化（tag_interest.json，tmp+rename 原子写） ----------------

    private fun saveLocked() {
        val f = stateFile ?: return
        sinceSave = 0
        lastSaveMs = nowMs()
        runCatching {
            trimLocked()
            val obj = JSONObject()
            obj.put("v", 1)
            obj.put("updatedTs", nowMs())
            obj.put("valid", validRecorded)
            obj.put("priorKey", priorKey)
            val tagObj = JSONObject()
            for ((k, row) in tags) {
                tagObj.put(k, JSONObject().put("s", row.s).put("t", row.t).put("d", row.display))
            }
            obj.put("tags", tagObj)
            val typeObj = JSONObject()
            for ((mt, row) in types) {
                typeObj.put(mt.toString(), JSONObject().put("s", row.s).put("t", row.t).put("n", row.n))
            }
            obj.put("types", typeObj)
            val priorObj = JSONObject()
            for ((k, p) in prior) priorObj.put(k, JSONObject().put("s", p.first).put("d", p.second))
            obj.put("prior", priorObj)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(f)) {
                runCatching { f.writeText(obj.toString()) }
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "tag 兴趣账本落盘失败（内存态不受影响）: ${it.message}") }
    }

    /** 懒加载（锁内首次触碰读盘；文件缺失/损坏=空账起步，不落原文之外任何旧口径） */
    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val f = stateFile ?: return
        runCatching {
            if (!f.isFile) return@runCatching
            val obj = JSONObject(f.readText())
            validRecorded = obj.optInt("valid", 0).coerceIn(0, QUICK_LEARN_LIMIT)
            priorKey = obj.optString("priorKey", "")
            obj.optJSONObject("tags")?.let { tagObj ->
                val keys = tagObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val o = tagObj.optJSONObject(k) ?: continue
                    val s = o.optDouble("s", 0.0)
                    val t = o.optLong("t", 0L)
                    if (s == 0.0 || t <= 0L) continue
                    tags[k] = TagRow(s, t, o.optString("d", k))
                }
            }
            obj.optJSONObject("types")?.let { typeObj ->
                val keys = typeObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val mt = k.toIntOrNull()?.coerceIn(0, 2) ?: continue
                    val o = typeObj.optJSONObject(k) ?: continue
                    types[mt] = TypeRow(
                        o.optDouble("s", 0.0),
                        o.optLong("t", 0L),
                        o.optInt("n", 0).coerceAtLeast(0),
                    )
                }
            }
            obj.optJSONObject("prior")?.let { priorObj ->
                val keys = priorObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val o = priorObj.optJSONObject(k) ?: continue
                    val s = o.optDouble("s", 0.0)
                    if (s <= 0.0) continue
                    prior[k] = s to o.optString("d", k)
                }
            }
        }.onFailure { Log.w(TAG, "tag 兴趣账本读取失败（按空账起步）: ${it.message}") }
    }

    /** 落盘前剪枝（锁内）：观测段按 |s| 取前 [MAX_TAGS]，长尾噪声不进文件 */
    private fun trimLocked() {
        if (tags.size <= MAX_TAGS) return
        val top = tags.entries.sortedByDescending { kotlin.math.abs(it.value.s) }.take(MAX_TAGS)
        tags.clear()
        for ((k, row) in top) tags[k] = row
    }

    companion object {
        private const val TAG = "TagInterestLedger"

        /** tag 轴衰减常数 λ（毫秒）：半衰 14 天（λ=14d/ln2，对齐马尔可夫遗忘口径） */
        internal val TAG_DECAY_MS = 14L * 24 * 3600 * 1000 / Math.log(2.0)

        /** 类型轴衰减常数 λ（毫秒）：半衰 7 天（对齐 InterestProfile 节奏画像口径） */
        internal val TYPE_DECAY_MS = 7L * 24 * 3600 * 1000 / Math.log(2.0)

        /** 秒划的 tag/类型轴负记账量（「不爱看这个话题」的首次可表达） */
        internal const val TAG_SKIP_DELTA = -1.0

        /** 快速学习期：累计有效观看 < 此数时单次记账 ×[QUICK_LEARN_GAIN]（设计 §6 稀疏信号期） */
        internal const val QUICK_LEARN_LIMIT = 50

        /** 快速学习增益倍数 */
        internal const val QUICK_LEARN_GAIN = 2.0

        /** 类型系数区间：软乘区，降权不消失/偏好不霸屏 */
        internal const val TYPE_FACTOR_FLOOR = 0.7
        internal const val TYPE_FACTOR_CAP = 1.3

        /** 先验档分值：订阅/收藏先验的单元分（低档起步，观测段积累后自然盖过） */
        internal const val PRIOR_WEIGHT = 0.3

        /** 观测段账本条数上限（落盘剪枝；5.5 万去重 tag 宇宙里用户真爱看的远在千级以内） */
        private const val MAX_TAGS = 600

        /** 落盘节流：每这么多次结算写一次 / 距上次至少这么久（任一满足即写） */
        private const val SAVE_EVERY = 5
        private const val SAVE_INTERVAL_MS = 60_000L

        /** 精确指数衰减合并（纯函数）：把 [sum]@[lastTs] 衰减到 [now]（未来时间戳封顶 1 防放大） */
        internal fun decayed(sum: Double, lastTs: Long, now: Long, lambdaMs: Double): Double {
            if (lastTs <= 0L || now <= lastTs) return sum
            return sum * exp(-(now - lastTs).toDouble() / lambdaMs).coerceAtMost(1.0)
        }
    }
}

// ---------------- R298 排序侧纯函数件（JVM 可测，零 IO 零 Android） ----------------

/**
 * 条目的 fold 后 tag 集（采集与打分同一把尺子）：
 * item_tags 已解析（非 null）→ 权威口径直接 fold（空表=已解析无标签，不回退——
 * R228 权威空语义）；null（网络直取/存量未回填）→ caption #标签现提
 * （[CaptionParser.extractHashtags] 原形 → fold 归一）。
 */
internal fun foldTagsOf(item: VideoItem): List<String> {
    val raw = item.itemTags
    val tags = if (raw != null) raw else CaptionParser.extractHashtags(item.caption)
    if (tags.isEmpty()) return emptyList()
    val out = ArrayList<String>(tags.size)
    val seen = HashSet<String>(tags.size)
    for (t in tags) {
        val f = SearchTextKit.fold(t.trim().trimStart('#', '＃'))
        if (f.isNotEmpty() && seen.add(f)) out += f
    }
    return out
}

/**
 * tag 匹配分（dinAttentionScore 同族数学形式：Σ axis[t]/√n 开方归一防堆词灌水）——
 * **差异口径**：负分参与求和（tag 负轴是 R298 新增表达面，dinAttentionScore 的
 * 跳负口径只服务词画像，两轴语义不同不共用实现）。命中数 0 / 轴空 = 0。
 */
internal fun tagMatchScore(foldedTags: Collection<String>, axis: Map<String, Double>): Double {
    if (foldedTags.isEmpty() || axis.isEmpty()) return 0.0
    // 输入去重（堆词灌水的本意就是同一 tag 重复出现不该加分；foldTagsOf 出产已 distinct，
    // 这里兜底直传集合的调用方）
    val distinct = if (foldedTags is Set<String>) foldedTags else foldedTags.toSet()
    var sum = 0.0
    var matched = 0
    for (t in distinct) {
        val w = axis[t] ?: continue
        sum += w
        matched++
    }
    if (matched == 0) return 0.0
    return sum / sqrt(distinct.size.toDouble().coerceAtLeast(1.0))
}

/**
 * 兴趣乘区（weightedShuffle 条目权重乘子）：(1 + α·tagMatch) 钳进
 * [[INTEREST_MULT_FLOOR],[INTEREST_MULT_CAP]]，再乘类型轴系数。
 * α 低档起步（设计选项甲：0.3 → 按回放/线上指标爬坡）。
 */
internal fun interestMultiplier(
    foldedTags: Collection<String>,
    axis: Map<String, Double>,
    typeFactors: DoubleArray,
    mediaType: Int,
    alpha: Double = INTEREST_ALPHA,
): Double {
    val tagPart = if (axis.isEmpty()) {
        1.0
    } else {
        (1.0 + alpha * tagMatchScore(foldedTags, axis))
            .coerceIn(INTEREST_MULT_FLOOR, INTEREST_MULT_CAP)
    }
    val typePart = typeFactors.getOrElse(mediaType.coerceIn(0, 2)) { 1.0 }
    return tagPart * typePart
}

/** 兴趣乘区下限/上限（tag 轴最强命中 ±α 量级内再钳一道，防极端堆词） */
internal const val INTEREST_MULT_FLOOR = 0.6
internal const val INTEREST_MULT_CAP = 1.6

/** T 轴在排序分中的权重（设计 §4.2 选项甲：低档 0.3 起步，按指标爬坡） */
internal const val INTEREST_ALPHA = 0.3

/**
 * TagInterestLedger 的进程级注册点（第六个 Holder；五个 Holder 的统一口径见
 * FeedSource.kt 中 ChannelFatigueHolder 类注释开头的「Holder 总约」，此处只记本件差异）：
 *  - 注册语义：**后注册生效**（ImplicitFeedback 同款）——VM 是记账方，VM 重建后
 *    新实例直接顶位，记账方与排序链始终落在同一个活实例上；
 *  - 注册方/时机：FeedViewModel 构造时，先于首次 rankWeightedPage 取用；
 *  - 未注册降级：兴趣乘区整体跳过（乘子表空），排序链逐字节退回现状（冷启动
 *    零退化的实现闸）。
 */
internal object TagInterestHolder {
    @Volatile
    private var ledger: TagInterestLedger? = null

    /** 注册：新实例直接顶位（语义与理由见类注释） */
    fun register(instance: TagInterestLedger): TagInterestLedger {
        ledger = instance
        return instance
    }

    fun current(): TagInterestLedger? = ledger
}
