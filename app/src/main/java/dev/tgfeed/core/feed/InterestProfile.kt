package dev.tgfeed.core.feed

import android.util.Log
import dev.tgfeed.core.store.WatchEvent
import org.json.JSONObject
import java.io.File
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * P2 新生代算法第一批 · 兴趣画像引擎（调研依据：docs/ALGO-NEXTGEN-SEQ.md §1/§2、
 * docs/ALGO-NEXTGEN-RL.md §2.4.1 双轨画像与 §5 优先级表）：
 *
 *  1. **双轨兴趣画像**（多时间尺度叠加）：
 *     - 长期轨 = 现有 decayedChannelWeights（watch_events 全量、λ=7 天衰减 + 互动加分，
 *       FeedSource.kt 既有实现，本类不重复造轮子）；
 *     - 短期轨 = 本类维护的"本次会话内观看事件"快衰减画像（λ=[SHORT_DECAY_MS]=30 分钟），
 *       公式与长期轨同构（0.3 + 衰减加权平均观看比例 × 0.7、skip 倒扣 -0.5）——
 *       "今天突然想看 XX"立刻在权重里抬头，不用等 7 天窗口慢慢稀释；
 *     - 融合：W = α·W_long + (1−α)·W_short（纯函数 [fuseTrackWeights]），α 随会话
 *       有效观看数从 [ALPHA_LONG_MAX]=0.85 渐降到 [ALPHA_LONG_MIN]=0.5——会话越短越听
 *       长期轨（防冷启动抖动），证据攒够才平分话语权（RL 文档 §2.4.1 原文口径）。
 *
 *  2. **马尔可夫口味转移**（一阶，SASRec 的零训练平替，SEQ 文档 §1.4.2）：
 *     统计观看序列的 P(下一条口味 | 当前口味)——词级（caption 打分词 → 打分词）与
 *     频道级（频道 → 频道）两路计数，拉普拉斯平滑后给"接得住当前口味"的候选加分。
 *     数据源口径：watch_events 只存 chatId/watchSec/durationSec，不存 caption——
 *     词级转移只能自本功能上线后在观看结算时逐次累积；频道级转移在模型文件缺失时
 *     （= 升级后首跑的一次性事件）用既有 watch_events 的频道序列自举，
 *     相邻事件间隔超 [MARKOV_BOOTSTRAP_WINDOW_MS]（30 分钟会话窗）不计
 *     （与 recordCoWatchEdge 的会话窗同口径）。
 *
 *  3. **DIN 目标注意力的纯函数出口**（[dinAttentionScore]，见文件下方）：
 *     候选词集 × 词权重画像的相关度分，Σ weight(w) 不做 softmax 归一化
 *     （DIN 原文口径：保留兴趣强度绝对值），仅按候选词数开方归一防长 caption 堆词灌水。
 *
 * 持久化：马尔可夫转移计数 + R79 观看节奏画像落盘 stateFile（FeedViewModel 传入
 * store/interest_profile.json），tmp+rename 原子写（LocalStore 同款纪律；LocalStore
 * 冻结故自立门户，与 taste_radar.json 同目录便于管理）。短期轨纯内存、随进程清零——
 * 它是"今天的手感"不是长期画像。
 * 兴趣漂移处理：加载时按模型文件年龄每 [MARKOV_HALFLIFE_DAYS]=14 天把马尔可夫计数
 * 减半一次（指数遗忘的离散近似），节奏画像按每 [RHYTHM_HALFLIFE_DAYS]=7 天减半
 * （行为节奏比结构性偏好漂移更快），防上季度的口味永久生效；落盘前剪枝
 * （行内 top [MARKOV_ROW_MAX_TARGETS]、源行 top [MARKOV_MAX_WORD_SOURCES]/
 * [MARKOV_MAX_CHAN_SOURCES]），文件大小有界。
 *
 * 线程模型：全部可变状态锁内读写（短期轨 shortLock / 马尔可夫 markovLock）；
 * 调用方均在 IO 协程（settleWatchEvent / recordMediaView / loadMore / 网关配额回调），
 * 无主线程热路径。加载采用懒加载闸（ensureLoadedLocked）：preload 与首次记账/打分
 * 谁先谁后都安全。
 */
internal class InterestProfile(
    private val stateFile: File?,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    // ---------------- 双轨 · 短期轨（本次会话，纯内存，快衰减） ----------------

    private val shortLock = Any()

    /** 短期轨事件流水（仅本次会话；超 [SHORT_MAX_EVENTS] 丢最旧） */
    private val shortEvents = ArrayList<WatchEvent>()

    /** 本次会话有效观看数（watchSec ≥ [SKIP_FAST_SEC]）：α 融合的证据量计 */
    private var shortValidCount = 0

    /**
     * 短期轨记一笔观看事件（快划也记——秒划是短期轨的负反馈证据，skip 等效 -0.5
     * 的口径与长期轨 decayedChannelWeights 对齐；只进内存，不落盘、重启清零）。
     */
    fun recordWatch(chatId: Long, watchSec: Int, durationSec: Int) {
        if (chatId == 0L) return
        synchronized(shortLock) {
            shortEvents += WatchEvent(chatId, watchSec, durationSec, nowMs())
            if (shortEvents.size > SHORT_MAX_EVENTS) {
                shortEvents.subList(0, shortEvents.size - SHORT_MAX_EVENTS).clear()
            }
            if (watchSec >= SKIP_FAST_SEC) shortValidCount++
        }
    }

    /**
     * 短期轨权重表：与长期轨同构（0.3 + 衰减加权平均观看比例 × 0.7，skip 倒扣
     * [SHORT_SKIP_PENALTY]），只是衰减常数从 7 天换成 [SHORT_DECAY_MS]（30 分钟）——
     * "刚才看的"压倒"今早已经看的"。无会话事件返回空表（融合层零开销直通长期轨）。
     */
    fun shortWeights(): Map<Long, Double> {
        synchronized(shortLock) {
            if (shortEvents.isEmpty()) return emptyMap()
            val now = nowMs()
            val byChannel = shortEvents.groupBy { it.chatId }
            val out = HashMap<Long, Double>(byChannel.size)
            for ((id, list) in byChannel) {
                var num = 0.0
                var den = 0.0
                for (e in list) {
                    // 未来时间戳（改系统时间等）decay 会 >1，封顶 1 防止放大（同长期轨口径）
                    val decay = exp(-(now - e.ts).toDouble() / SHORT_DECAY_MS).coerceAtMost(1.0)
                    den += decay
                    num += if (e.watchSec < SKIP_FAST_SEC) {
                        decay * -SHORT_SKIP_PENALTY
                    } else {
                        val ratio = if (e.durationSec <= 0) 0.0
                        else (e.watchSec.toDouble() / e.durationSec).coerceIn(0.0, 1.0)
                        decay * ratio
                    }
                }
                val avg = if (den > 0.0) (num / den).coerceIn(0.0, 1.0) else 0.0
                out[id] = 0.3 + avg * 0.7
            }
            return out
        }
    }

    /**
     * 双轨融合的 α（长期轨话语权）：会话有效观看 0 次 → [ALPHA_LONG_MAX]=0.85
     * （几乎全听长期轨，防冷启动抖动）；攒够 [ALPHA_RAMP_EVENTS]=10 次 →
     * [ALPHA_LONG_MIN]=0.5（双轨平分）。其间线性渐降。
     */
    fun fusionAlpha(): Double {
        val n = synchronized(shortLock) { shortValidCount }
        val t = (n.toDouble() / ALPHA_RAMP_EVENTS).coerceIn(0.0, 1.0)
        return ALPHA_LONG_MAX - (ALPHA_LONG_MAX - ALPHA_LONG_MIN) * t
    }

    // ---------------- R79 观看节奏画像（跨会话沉淀，持久化） ----------------
    // 看多久（观看比例）/ 是否回看 / 是否连刷同频道三路节奏信号，衰减累加成通用画像，
    // 分型"深度观看型/浅尝辄止型/连刷型/均衡型"——类似强化学习的奖励统计口径，
    // 供消费侧调节负反馈强度与连刷加成（FeedViewModel 经 [rhythmProfile]/
    // [bingeBoostTarget] 取用）。只统计有效观看（≥[SKIP_FAST_SEC]s）——秒划是
    // 负反馈信号（ImplicitFeedback 口径），不是"节奏"。
    // 漂移处理：加载时按节奏年龄每 [RHYTHM_HALFLIFE_DAYS]=7 天全量减半一次
    // （指数遗忘离散近似）——上个月的节奏不绑架这个月的用户。

    /** 节奏衰减累加器（全部双精度，承载减半遗忘；锁内读写，与马尔可夫同锁同文件） */
    private var rhythmN = 0.0        // 有效观看样本数（衰减）
    private var rhythmRatioN = 0.0   // 其中有真实时长的样本数（观看比例分母；图文不计）
    private var rhythmRatioSum = 0.0 // 观看比例和（衰减）
    private var rhythmReplayN = 0.0  // 回看次数（衰减）
    private var rhythmPairN = 0.0    // 相邻有效观看对数（30 分钟会话窗内）
    private var rhythmSameChanN = 0.0// 其中同频道对数（连刷）

    /** 会话内连刷状态（纯内存，重启清零）：当前连刷频道与连刷条数 */
    private var streakChat = 0L
    private var streakN = 0

    /** 上一笔有效观看（频道/时刻）：连刷对判定的上下文（纯内存，跨进程不补记） */
    private var rhythmLastChat = 0L
    private var rhythmLastTs = 0L

    /**
     * 记一笔节奏样本（FeedViewModel 在有效观看结算时调用；秒划不进——它是负反馈不是节奏）。
     * 返回当前节奏分型（调用方据此调负反馈系数/连刷加成）。
     */
    fun recordWatchRhythm(chatId: Long, watchSec: Int, durationSec: Int, replayed: Boolean): RhythmStyle {
        synchronized(markovLock) {
            ensureLoadedLocked()
            if (chatId == 0L || watchSec < SKIP_FAST_SEC) return classifyRhythmLocked()
            val now = nowMs()
            rhythmN += 1
            if (durationSec > 0) {
                // 只看真实时长条目的观看比例——图文无时长概念，计入会把均值吹向 1.0
                rhythmRatioN += 1
                rhythmRatioSum += (watchSec.toDouble() / durationSec).coerceIn(0.0, 1.0)
            }
            if (replayed) rhythmReplayN += 1
            // 连刷对：相邻有效观看在 30 分钟会话窗内才成对（与马尔可夫转移同口径）
            if (rhythmLastChat != 0L && now - rhythmLastTs <= MARKOV_BOOTSTRAP_WINDOW_MS) {
                rhythmPairN += 1
                if (rhythmLastChat == chatId) rhythmSameChanN += 1
            }
            if (chatId == streakChat) streakN++ else { streakChat = chatId; streakN = 1 }
            rhythmLastChat = chatId
            rhythmLastTs = now
            // 节奏与马尔可夫共用落盘节流（同一份文件，记一笔即脏一笔）
            dirtySinceSave++
            if (dirtySinceSave >= MARKOV_SAVE_EVERY) saveLocked()
            return classifyRhythmLocked()
        }
    }

    /** 节奏画像快照（消费侧/日志用；样本不足时风格 UNKNOWN，各率为 0） */
    fun rhythmProfile(): RhythmProfile {
        synchronized(markovLock) {
            ensureLoadedLocked()
            val avgRatio = if (rhythmRatioN > 0.0) rhythmRatioSum / rhythmRatioN else 0.0
            val replayRate = if (rhythmN > 0.0) rhythmReplayN / rhythmN else 0.0
            val bingeRate = if (rhythmPairN > 0.0) rhythmSameChanN / rhythmPairN else 0.0
            return RhythmProfile(
                samples = rhythmN.toInt(),
                avgRatio = avgRatio,
                replayRate = replayRate,
                bingeRate = bingeRate,
                style = classifyRhythmLocked(),
                streakChat = streakChat,
                streakN = streakN,
            )
        }
    }

    /**
     * 连刷加成目标：连刷型用户且当前同频道连刷 ≥[RHYTHM_BINGE_STREAK] 条时返回该频道
     * （消费侧给小幅加分；硬多样性约束不动，连刷加成只是"多排一点"不是"霸屏"）。
     */
    fun bingeBoostTarget(): RhythmStreak? {
        synchronized(markovLock) {
            ensureLoadedLocked()
            if (classifyRhythmLocked() != RhythmStyle.BINGE) return null
            if (streakChat == 0L || streakN < RHYTHM_BINGE_STREAK) return null
            return RhythmStreak(streakChat, streakN)
        }
    }

    /**
     * 节奏分型（锁内）：样本 <[RHYTHM_MIN_SAMPLES] 不定型（小样本不可信）；
     * 连刷率优先判（行为模式强于深度偏好），其次深度/浅尝，其余均衡。
     */
    private fun classifyRhythmLocked(): RhythmStyle {
        if (rhythmN < RHYTHM_MIN_SAMPLES) return RhythmStyle.UNKNOWN
        val bingeRate = if (rhythmPairN > 0.0) rhythmSameChanN / rhythmPairN else 0.0
        val avgRatio = if (rhythmRatioN > 0.0) rhythmRatioSum / rhythmRatioN else 0.0
        return when {
            bingeRate >= RHYTHM_BINGE_MIN -> RhythmStyle.BINGE
            avgRatio >= RHYTHM_DEEP_RATIO -> RhythmStyle.DEEP
            avgRatio <= RHYTHM_SKIM_RATIO -> RhythmStyle.SKIM
            else -> RhythmStyle.BALANCED
        }
    }

    // ---------------- 马尔可夫口味转移（一阶；词级 + 频道级，持久化） ----------------

    private val markovLock = Any()

    /** 词级转移计数：fromWord → (toWord → 次数) */
    private val wordTrans = HashMap<String, HashMap<String, Int>>()

    /** 频道级转移计数：fromChatId → (toChatId → 次数) */
    private val chanTrans = HashMap<Long, HashMap<Long, Int>>()

    /**
     * 词/频道词汇表（拉普拉斯平滑分母的 V；随记账与加载递增）。
     * 落盘剪枝后词汇表仍是超集（不随剪枝收缩）——V 略大使未见过目标的平滑概率略低，
     * 方向保守（不误加分），可接受。
     */
    private val wordVocab = HashSet<String>()
    private val chanVocab = HashSet<Long>()

    /** 距上次落盘新增的转移条数（攒够 [MARKOV_SAVE_EVERY] 落一次盘） */
    private var dirtySinceSave = 0

    /** 模型文件是否已加载（懒加载闸：record/score 首次触碰时自动加载，与 preload 谁先谁后都安全） */
    private var loaded = false

    /** 加载时发现模型文件不存在 → 待自举标记（preload 消费；升级后首跑的一次性事件） */
    private var bootstrapPending = false

    /**
     * 预载（FeedViewModel init 的 IO 协程调用一次）：加载模型文件；文件不存在
     * （升级后首跑）则用既有 watch_events 自举频道级转移——事件表时间倒序，翻转后
     * 取相邻"有效观看"（watchSec ≥ [SKIP_FAST_SEC]，与运行时记账同口径）对，
     * 间隔超 [MARKOV_BOOTSTRAP_WINDOW_MS]（30 分钟会话窗）视为两个上下文不计。
     * 词级转移无历史可推（watch_events 不存 caption），从本功能上线后逐次累积。
     * 文件已存在时绝不自举——历史转移已在过去的会话里逐笔记过账，自举会双计。
     */
    fun preload(events: List<WatchEvent>) {
        synchronized(markovLock) {
            ensureLoadedLocked()
            if (!bootstrapPending) return
            bootstrapPending = false
            if (events.size < 2) return
            // 时间正序的有效观看序列（事件表持久化为时间倒序，先翻转）
            val seq = events.asReversed().filter { it.watchSec >= SKIP_FAST_SEC && it.chatId != 0L }
            var bootstrapped = 0
            for (i in 1 until seq.size) {
                val from = seq[i - 1]
                val to = seq[i]
                if (from.ts <= 0L || to.ts <= 0L) continue // 无时间戳的脏数据不参与
                if (to.ts < from.ts || to.ts - from.ts > MARKOV_BOOTSTRAP_WINDOW_MS) continue
                bumpChanLocked(from.chatId, to.chatId)
                bootstrapped++
            }
            if (bootstrapped > 0) {
                saveLocked() // 自举成果立即落盘（防首跑崩溃丢自举）
                Log.i(TAG, "马尔可夫频道转移自举完成：$bootstrapped 对（来自既有 watch_events）")
            }
        }
    }

    /**
     * 记一笔观看转移（相邻两次有效观看：from=上一次，to=本次；由 FeedViewModel 在
     * 结算时调用）。词级只在两侧词集都非空时记账；频道级总是记账（同频道连看也记——
     * 连刷同一频道是真实的口味驻留信号）。攒够 [MARKOV_SAVE_EVERY] 条自动落盘一次
     * （tmp+rename 原子写）。
     */
    fun recordTransition(fromWords: Set<String>, fromChat: Long, toWords: Set<String>, toChat: Long) {
        synchronized(markovLock) {
            ensureLoadedLocked()
            var dirty = 0
            if (fromWords.isNotEmpty() && toWords.isNotEmpty()) {
                for (a in fromWords) {
                    for (b in toWords) {
                        bumpWordLocked(a, b)
                        dirty++
                    }
                }
            }
            if (fromChat != 0L && toChat != 0L) {
                bumpChanLocked(fromChat, toChat)
                dirty++
            }
            if (dirty == 0) return
            // 落盘节流按"有效观看次数"计而非边条数：一次观看的词对可达上百条边，
            // 按边计会每次结算都落盘（小文件 I/O 过频）
            dirtySinceSave++
            if (dirtySinceSave >= MARKOV_SAVE_EVERY) saveLocked()
        }
    }

    /**
     * 马尔可夫转移分：候选（toWords/toChat）接住当前口味上下文（fromWords/fromChat）
     * 的概率，∈ [0, [MARKOV_WORD_WEIGHT]+[MARKOV_CHANNEL_WEIGHT]]。
     * 词级取 P(toW|fromW) 全对组合的**最大值**——最强单链决定候选的"衔接度"，
     * 避免多词平均稀释强信号；频道级取 P(toChat|fromChat)。两路按
     * [MARKOV_WORD_WEIGHT]/[MARKOV_CHANNEL_WEIGHT] 合成，拉普拉斯平滑
     * （κ=[MARKOV_SMOOTH]，V=词汇表规模）。无模型数据/无上下文返回 0（调用方按无加分处理）。
     */
    fun transitionScore(fromWords: Set<String>, fromChat: Long, toWords: Set<String>, toChat: Long): Double {
        synchronized(markovLock) {
            ensureLoadedLocked()
            if (wordTrans.isEmpty() && chanTrans.isEmpty()) return 0.0
            var bestWord = 0.0
            if (fromWords.isNotEmpty() && toWords.isNotEmpty()) {
                val v = wordVocab.size.coerceAtLeast(10) // 词汇量下限：防模型极早期平滑概率虚高
                for (a in fromWords) {
                    val row = wordTrans[a] ?: continue
                    val total = row.values.sum()
                    for (b in toWords) {
                        val p = ((row[b] ?: 0) + MARKOV_SMOOTH) / (total + MARKOV_SMOOTH * v)
                        if (p > bestWord) bestWord = p
                    }
                }
            }
            var chanP = 0.0
            if (fromChat != 0L && toChat != 0L) {
                val row = chanTrans[fromChat]
                if (row != null) {
                    val vc = chanVocab.size.coerceAtLeast(2)
                    val total = row.values.sum()
                    chanP = ((row[toChat] ?: 0) + MARKOV_SMOOTH) / (total + MARKOV_SMOOTH * vc)
                }
            }
            return MARKOV_WORD_WEIGHT * bestWord + MARKOV_CHANNEL_WEIGHT * chanP
        }
    }

    /** 兜底落盘（FeedViewModel.onCleared 调用；无脏数据时零开销直通） */
    fun flush() {
        synchronized(markovLock) {
            if (loaded && dirtySinceSave > 0) saveLocked()
        }
    }

    // ---------------- 马尔可夫模型内部实现（全部锁内调用） ----------------

    private fun bumpWordLocked(from: String, to: String) {
        val row = wordTrans.getOrPut(from) { HashMap() }
        row[to] = (row[to] ?: 0) + 1
        wordVocab += from
        wordVocab += to
    }

    private fun bumpChanLocked(from: Long, to: Long) {
        val row = chanTrans.getOrPut(from) { HashMap() }
        row[to] = (row[to] ?: 0) + 1
        chanVocab += from
        chanVocab += to
    }

    /**
     * 加载模型文件（幂等，锁内调用）。文件不存在/读失败 → 标记待自举（preload 消费）；
     * 文件存在但解析失败 → 清空按空模型起步（下一次落盘自然覆盖坏文件）。
     * 加载后按模型年龄做离散遗忘：每 [MARKOV_HALFLIFE_DAYS] 天全部计数减半一次。
     */
    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val f = stateFile ?: return
        val text = runCatching { if (f.isFile) f.readText() else null }.getOrNull()
        if (text == null) {
            // 文件不存在（升级后首跑）或读失败：preload 用既有 watch_events 自举频道级转移
            bootstrapPending = true
            return
        }
        runCatching {
            val obj = JSONObject(text)
            val updatedTs = obj.optLong("updatedTs", 0L)
            val wt = obj.optJSONObject("wordTrans")
            if (wt != null) {
                val fromKeys = wt.keys()
                while (fromKeys.hasNext()) {
                    val a = fromKeys.next()
                    val rowObj = wt.optJSONObject(a) ?: continue
                    val row = HashMap<String, Int>()
                    val toKeys = rowObj.keys()
                    while (toKeys.hasNext()) {
                        val b = toKeys.next()
                        val c = rowObj.optInt(b, 0)
                        if (c > 0) {
                            row[b] = c
                            wordVocab += b
                        }
                    }
                    if (row.isNotEmpty()) {
                        wordTrans[a] = row
                        wordVocab += a
                    }
                }
            }
            val ct = obj.optJSONObject("chanTrans")
            if (ct != null) {
                val fromKeys = ct.keys()
                while (fromKeys.hasNext()) {
                    val aKey = fromKeys.next()
                    val a = aKey.toLongOrNull() ?: continue // 坏 key 跳过
                    val rowObj = ct.optJSONObject(aKey) ?: continue
                    val row = HashMap<Long, Int>()
                    val toKeys = rowObj.keys()
                    while (toKeys.hasNext()) {
                        val bKey = toKeys.next()
                        val b = bKey.toLongOrNull() ?: continue
                        val c = rowObj.optInt(bKey, 0)
                        if (c > 0) {
                            row[b] = c
                            chanVocab += b
                        }
                    }
                    if (row.isNotEmpty()) {
                        chanTrans[a] = row
                        chanVocab += a
                    }
                }
            }
            // R79 观看节奏画像：六路衰减累加器（缺失字段按 0 起步，旧版文件自然兼容）
            obj.optJSONObject("rhythm")?.let { r ->
                rhythmN = r.optDouble("n", 0.0).coerceAtLeast(0.0)
                rhythmRatioN = r.optDouble("ratioN", 0.0).coerceAtLeast(0.0)
                rhythmRatioSum = r.optDouble("ratioSum", 0.0).coerceAtLeast(0.0)
                rhythmReplayN = r.optDouble("replayN", 0.0).coerceAtLeast(0.0)
                rhythmPairN = r.optDouble("pairN", 0.0).coerceAtLeast(0.0)
                rhythmSameChanN = r.optDouble("sameChanN", 0.0).coerceAtLeast(0.0)
            }
            // 兴趣漂移的离散遗忘：按模型文件年龄每 14 天计数减半一次
            //（比 7 天权重衰减更慢的时间尺度——转移是结构性偏好，不该和权重同速过期）
            if (updatedTs > 0L) {
                val days = (nowMs() - updatedTs) / 86_400_000L
                val halvings = (days / MARKOV_HALFLIFE_DAYS).toInt().coerceIn(0, 8)
                repeat(halvings) {
                    halveMapLocked(wordTrans)
                    halveMapLocked(chanTrans)
                }
                // 节奏画像漂移更快（行为节奏 ≠ 结构性偏好）：每 7 天减半一次
                val rhythmHalvings = (days / RHYTHM_HALFLIFE_DAYS).toInt().coerceIn(0, 8)
                repeat(rhythmHalvings) {
                    rhythmN /= 2; rhythmRatioN /= 2; rhythmRatioSum /= 2
                    rhythmReplayN /= 2; rhythmPairN /= 2; rhythmSameChanN /= 2
                }
            }
        }.onFailure {
            Log.w(TAG, "马尔可夫模型解析失败，按空模型起步: ${it.message}")
            wordTrans.clear()
            chanTrans.clear()
        }
    }

    /** 全表计数减半（加载侧兴趣漂移遗忘用）；减到 0 的边/行清除 */
    private fun <K> halveMapLocked(map: HashMap<K, HashMap<K, Int>>) {
        val rowIt = map.entries.iterator()
        while (rowIt.hasNext()) {
            val row = rowIt.next().value
            val edgeIt = row.entries.iterator()
            while (edgeIt.hasNext()) {
                val e = edgeIt.next()
                val v = e.value / 2
                if (v <= 0) edgeIt.remove() else e.setValue(v)
            }
            if (row.isEmpty()) rowIt.remove()
        }
    }

    /**
     * 落盘（锁内调用）：tmp+rename 原子写（LocalStore 同款纪律）。
     * 写前剪枝：每行只留计数前 [MARKOV_ROW_MAX_TARGETS] 个目标，源行按行总计数截断到
     * [MARKOV_MAX_WORD_SOURCES]/[MARKOV_MAX_CHAN_SOURCES]——文件大小有界，
     * 低频长尾边本来就是噪声。失败仅记日志（内存模型不受影响）。
     */
    private fun saveLocked() {
        dirtySinceSave = 0
        val f = stateFile ?: return
        runCatching {
            // 内存表同口径剪枝（不只剪落盘输出）：长会话里词表无限增长的内存收口
            trimMapLocked(wordTrans, MARKOV_MAX_WORD_SOURCES)
            trimMapLocked(chanTrans, MARKOV_MAX_CHAN_SOURCES)
            val obj = JSONObject()
            obj.put("v", 1)
            obj.put("updatedTs", nowMs())
            obj.put("wordTrans", transToJson(wordTrans, MARKOV_MAX_WORD_SOURCES))
            obj.put("chanTrans", transToJson(chanTrans, MARKOV_MAX_CHAN_SOURCES))
            // R79 观看节奏画像（双精度累加器原样落盘，加载侧按年龄减半遗忘）
            obj.put(
                "rhythm",
                JSONObject()
                    .put("n", rhythmN)
                    .put("ratioN", rhythmRatioN)
                    .put("ratioSum", rhythmRatioSum)
                    .put("replayN", rhythmReplayN)
                    .put("pairN", rhythmPairN)
                    .put("sameChanN", rhythmSameChanN),
            )
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(f)) {
                // rename 失败（极少见）时退化为直接覆盖写（LocalStore 同款兜底）
                runCatching { f.writeText(obj.toString()) }
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "马尔可夫模型落盘失败（内存模型不受影响）: ${it.message}") }
    }

    /**
     * 转移表剪枝（锁内调用，落盘前）：行内只留计数前 [MARKOV_ROW_MAX_TARGETS] 个目标，
     * 源行按行总计数截断到 maxSources 行——内存与落盘文件同保有界，
     * 低频长尾边本来就是噪声，剪了不亏。
     */
    private fun <K> trimMapLocked(map: HashMap<K, HashMap<K, Int>>, maxSources: Int) {
        if (map.size > maxSources) {
            // 源行超限：整表重建为"行总计数前 maxSources 行"
            val top = map.entries.sortedByDescending { e -> e.value.values.sum() }.take(maxSources)
            map.clear()
            for ((k, row) in top) map[k] = row
        }
        // 行内目标剪枝（无论源行是否超限都执行）
        for ((_, row) in map) {
            if (row.size <= MARKOV_ROW_MAX_TARGETS) continue
            val targets = row.entries.sortedByDescending { e -> e.value }.take(MARKOV_ROW_MAX_TARGETS)
            row.clear()
            for ((k, v) in targets) row[k] = v
        }
    }

    /** 转移表 → JSON：行内取计数前 [MARKOV_ROW_MAX_TARGETS] 个目标，源行按行总计数取前 maxSources 行 */
    private fun <K> transToJson(map: HashMap<K, HashMap<K, Int>>, maxSources: Int): JSONObject {
        val rows = map.entries.sortedByDescending { e -> e.value.values.sum() }.take(maxSources)
        val out = JSONObject()
        for ((from, row) in rows) {
            val rowJson = JSONObject()
            val targets = row.entries.sortedByDescending { e -> e.value }.take(MARKOV_ROW_MAX_TARGETS)
            for ((to, count) in targets) rowJson.put(to.toString(), count)
            out.put(from.toString(), rowJson)
        }
        return out
    }

    companion object {
        private const val TAG = "InterestProfile"

        /** 短期轨衰减常数 λ（毫秒）：30 分钟——快衰减，"刚才看的"压倒"今早已经看的" */
        private const val SHORT_DECAY_MS = 30L * 60_000L

        /** 短期轨事件流水上限（仅本次会话内存态；超出丢最旧） */
        private const val SHORT_MAX_EVENTS = 500

        /** 短期轨 skip 负反馈强度：等效观看比例 -0.5（与长期轨 SKIP_EXTRA_PENALTY 同口径镜像） */
        private const val SHORT_SKIP_PENALTY = 0.5

        /** α 融合的长期轨话语权区间：证据 0 时 0.85（几乎全听长期轨），攒够证据降到 0.5（双轨平分） */
        private const val ALPHA_LONG_MAX = 0.85
        private const val ALPHA_LONG_MIN = 0.5

        /** α 从 MAX 线性降到 MIN 所需的会话有效观看次数（证据爬坡长度） */
        private const val ALPHA_RAMP_EVENTS = 10

        /** 马尔可夫拉普拉斯平滑系数 κ */
        private const val MARKOV_SMOOTH = 1.0

        /** 转移分合成权重：词级 / 频道级（词级是内容口味本体，占大头） */
        private const val MARKOV_WORD_WEIGHT = 0.7
        private const val MARKOV_CHANNEL_WEIGHT = 0.3

        /** 每积累多少次有效观看（转移记账）落一次盘（节流；FeedViewModel.onCleared 另有兜底 flush） */
        private const val MARKOV_SAVE_EVERY = 10

        /** 落盘剪枝：每个源行只留计数前 N 个目标 */
        private const val MARKOV_ROW_MAX_TARGETS = 20

        /** 落盘剪枝：词级/频道级源行数上限（按行总计数取高者） */
        private const val MARKOV_MAX_WORD_SOURCES = 200
        private const val MARKOV_MAX_CHAN_SOURCES = 100

        /** 自举/记账的会话窗（毫秒）：相邻观看间隔超过 30 分钟视为两个上下文不建转移（与 recordCoWatchEdge 同口径） */
        private const val MARKOV_BOOTSTRAP_WINDOW_MS = 30L * 60_000L

        /** 兴趣漂移遗忘半衰期（天）：加载时按模型文件年龄每 N 天计数减半一次 */
        private const val MARKOV_HALFLIFE_DAYS = 14L

        // ---- R79 观看节奏画像分型参数 ----

        /** 节奏分型最小样本数（衰减后有效观看数）：小样本不可信，不定型（UNKNOWN） */
        private const val RHYTHM_MIN_SAMPLES = 20.0

        /** 连刷率阈值：相邻有效观看中同频道对占比 ≥ 此值判连刷型（超 1/3 即显著） */
        private const val RHYTHM_BINGE_MIN = 0.35

        /** 深度观看型阈值：平均观看比例 ≥ 此值 */
        private const val RHYTHM_DEEP_RATIO = 0.55

        /** 浅尝辄止型阈值：平均观看比例 ≤ 此值 */
        private const val RHYTHM_SKIM_RATIO = 0.25

        /** 节奏画像遗忘半衰期（天）：行为节奏比马尔可夫结构性偏好漂移更快 */
        private const val RHYTHM_HALFLIFE_DAYS = 7L

        /** 连刷加成的会话内连刷条数下限：连刷型用户同频道连看 ≥N 条才给小幅加成 */
        private const val RHYTHM_BINGE_STREAK = 3
    }
}

/** R79 观看节奏分型：深度观看型 / 浅尝辄止型 / 连刷型 / 均衡型 / 样本不足未定 */
internal enum class RhythmStyle { UNKNOWN, DEEP, SKIM, BINGE, BALANCED }

/** R79 观看节奏画像快照（各 rate/ratio 为 0~1；style 样本不足时 UNKNOWN） */
internal data class RhythmProfile(
    val samples: Int,
    val avgRatio: Double,
    val replayRate: Double,
    val bingeRate: Double,
    val style: RhythmStyle,
    val streakChat: Long, // 当前会话内连刷频道（无连刷为 0）
    val streakN: Int,     // 当前会话内连刷条数
)

/** R79 连刷加成目标（bingeBoostTarget 返回值）：频道 + 当前连刷条数 */
internal data class RhythmStreak(val chatId: Long, val streak: Int)

/**
 * DIN 目标注意力打分（纯函数，SEQ 文档 §2.4.1 的零训练落地）：
 * 候选词集 × 词权重画像的相关度分 interest(C) = Σ_{w ∈ tokens(C)} weight(w)。
 * 不做 softmax 归一化（DIN 原文口径：保留兴趣强度绝对值——命中强口味词的候选
 * 得分天然高于命中弱词的）；仅按候选词数开方归一，防长 caption 堆词线性灌水
 * （词多命中多仍然更强，只是强度亚线性）。
 */
internal fun dinAttentionScore(candidateWords: Collection<String>, wordWeights: Map<String, Double>): Double {
    if (candidateWords.isEmpty() || wordWeights.isEmpty()) return 0.0
    var sum = 0.0
    var matched = 0
    for (w in candidateWords) {
        val weight = wordWeights[w] ?: continue
        if (weight <= 0.0) continue
        sum += weight
        matched++
    }
    if (matched == 0) return 0.0
    return sum / sqrt(candidateWords.size.toDouble().coerceAtLeast(1.0))
}

/**
 * 双轨融合（纯函数）：W = α·W_long + (1−α)·W_short。
 * 短期轨只覆盖本会话看过的频道；不在长期轨的频道按 [EXPLORE_WEIGHT] 探索档打底
 * （与 decayedChannelWeights 的兜底口径一致）。短期轨为空时原样返回长期轨
 * （零开销直通——未产生任何会话观看时行为与融合前完全一致）。
 */
internal fun fuseTrackWeights(
    longTrack: Map<Long, Double>,
    shortTrack: Map<Long, Double>,
    alpha: Double,
): Map<Long, Double> {
    if (shortTrack.isEmpty()) return longTrack
    val a = alpha.coerceIn(0.0, 1.0)
    val out = HashMap<Long, Double>(longTrack.size + shortTrack.size)
    out.putAll(longTrack)
    for ((id, sw) in shortTrack) {
        val lw = out[id] ?: EXPLORE_WEIGHT
        out[id] = a * lw + (1.0 - a) * sw
    }
    return out
}
