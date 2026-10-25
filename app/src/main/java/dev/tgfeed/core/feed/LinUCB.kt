package dev.tgfeed.core.feed

import android.util.Log
import dev.tgfeed.core.store.LocalStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * LinUCB 上下文老虎机（ALGO P2-B，docs/ALGO-NEXTGEN-RL.md §1.4.1/§1.4.2）：
 * 探索位（每 10 格 1 位，见 FeedSource.placeWithDiversity）的候选选择从"主序首条"
 * 升级为置信上界选臂——现行 ε-greedy 配额制的严格升级版，探索量随不确定性自适应
 * （不确定的频道多探、稳定的少探），比固定配额的纯随机更省样本。
 *
 * 模型形态（disjoint LinUCB，Li et al. WWW 2010）：
 *  - 臂 = 频道（chatId）。条目粒度臂在单设备上永远喂不饱（条目 churn 快、单臂样本≈1），
 *    频道粒度与 decayedChannelWeights 的画像粒度一致：几十臂 × d=8 特征，几百样本即稳；
 *  - 上下文 x = 条目特征向量（调用方装配，维度 [DIM]，顺序即语义勿错位）：
 *    [0]偏置1 / [1]频道权重归一化(w/2) / [2]新鲜度档位 / [3]是否已看 /
 *    [4]是否图片 / [5]是否音频 / [6]是否长视频 / [7]曝光疲劳 ln(1+曝光)/ln(11)；
 *  - 每臂维护 A = λI + Σxxᵀ（[DIM]×[DIM]，行主序）与 b = Σr·x；
 *    选臂分 = θ·x + α·√(xᵀA⁻¹x)，θ = A⁻¹b（d=8 的 Gauss-Jordan 求逆代价可忽略）；
 *  - 奖励 r（R79 起全信号口径，合成在 FeedSource 的 settle 回调里）：
 *    互动 1.0 / 近完读 0.6（ExposureTracker.positiveReward，与赛马"达标"同口径）
 *    与条目级观看结局（ImplicitFeedback.itemReward：秒划 -0.4 负反馈、观看比例
 *    0.2/0.4/0.6 分档、回看 +0.3 加成、图文音频有效观看 +0.3）取高合成；
 *    秒划负奖励立即结算不等窗，超结算窗仍无信号按 0.0（延迟结算见 [settle]）。
 *    负奖励让 bandit 的 θ 学到"这个臂的探索失败过"，与排序侧隐式降权
 *    （ImplicitFeedback 乘区）构成双通道负反馈：一个管探索位选谁，一个管主序先后。
 *
 * 与赛马（ExposureTracker）正交：赛马管"新内容冷启动晋级/出局"（打分乘区 + 小流量池
 * 段位），本类只管"探索位该把流量给谁"。探索池的构成（新频道半区/冷门半区/窗口与
 * 占比双硬约束）保持不变，LinUCB 只在池内多个候选之间做选择。
 *
 * 防震荡（单用户事件量比服务端少 6 个数量级，调研 §4.4 的教训是"防震荡比什么都重要"）：
 *  1. α 随数据量收缩：α(t) = α0 / √(1 + t/[ALPHA_HALF_LIFE])，t 为累计奖励结算数，
 *     下限 [ALPHA_MIN]——前期多探索，后期收敛但不锁死；
 *  2. 选臂用 softmax(UCB/τ) + [UNIFORM_MIX] 均匀混合的随机策略而非硬 argmax——
 *     避免 bandit 状态自激锁死，同时让每个候选的选择概率（propensity）有定义且
 *     ≥ [UNIFORM_MIX]/n（IPS 评估的下界保证）；
 *  3. 奖励档位数克制（R79 口径：-0.4/0/0.2/0.3/0.4/0.6/0.9/1.0 以内的合成值，
 *     clamp [-0.5, 1.0]），不追细粒度（页级曝光口径本身噪声大）；
 *     负奖励幅度 < 正奖励（一次秒划杀不死一个臂，多次才压下去——与秒划账本的
 *     "单次=暂时不想看、多次=不感兴趣"计数升级同哲学）。
 *
 * propensity 记录（调研 §1.4.2，反事实评估底座）：每次探索位选择把
 * "时刻/条目/臂/池/propensity/当时 α" 追加到 linucb_propensity.log（append-only；
 * 超 [PROP_MAX_BYTES] 截断保尾，截断写走 tmp+rename 原子替换）。以后做 IPS 离线
 * 评估或换策略时，旧策略的选中概率直接可用。
 *
 * 持久化：臂参数（A/b/pulls/totalPulls）落盘 <storeDir>/linucb_arms.json
 * （tmp+rename 原子写；防抖：每 [SAVE_EVERY] 次奖励结算或距上次 ≥[SAVE_INTERVAL_MS]
 * 才写）。目录由 [LinUCBHolder] 注册时经 LocalStore.storageDir 正式访问口取得
 * （R123 起；此前反射读私有字段的桥已拆除）；dir 为 null 时退化为纯内存模式——
 * 选臂功能不变，重启后臂参数丢失、α 从高档重新起步。
 * 待结算 pending 队列刻意不落盘：进程死亡时未结算的探索曝光直接丢弃（不更新臂），
 * 比冤枉成奖励 0 更诚实。
 *
 * 线程：全部公开方法 @Synchronized（与 ExposureTracker 同风格）；
 * 文件 IO 都是 KB 级小文件且在页频以下，排序路径可接受。
 */
internal class LinUCB(private val dir: File?) {

    /** 探索位候选（调用方装配好特征向量；[features] 必须长度 [DIM] 且归调用方所有） */
    internal class Candidate(
        val key: String,
        val chatId: Long,
        val durationSec: Int,
        val features: DoubleArray,
    )

    /** 一条待结算的探索曝光（选中时登记，互动/近完读/超时三种结局，见 [settle]） */
    private class Pending(
        val chatId: Long,
        val durationSec: Int,
        val features: DoubleArray,
        val propensity: Double, // 选中概率（目前只记日志；留字段供将来按 IPS 加权结算）
        val ts: Long,
    )

    /** 臂（频道）参数：A = λI + Σxxᵀ（行主序 d×d），b = Σr·x */
    private class Arm {
        val a = DoubleArray(DIM * DIM)
        val b = DoubleArray(DIM)
        var pulls = 0
        var lastTs = 0L

        init {
            for (i in 0 until DIM) a[i * DIM + i] = RIDGE_LAMBDA
        }
    }

    private val arms = HashMap<Long, Arm>()
    private var totalPulls = 0          // 累计奖励结算数（α 收缩的 t）
    private var loaded = false          // 臂参数是否已从磁盘懒加载
    private var dirtySince = 0          // 距上次落盘的奖励更新数
    private var lastSaveMs = 0L
    private val pending = LinkedHashMap<String, Pending>() // key → 待结算（插入序，超上限淘汰最老）
    private val logBuf = ArrayList<String>()               // propensity 日志内存缓冲（随落盘冲刷）

    // ---------------- 选臂 ----------------

    /**
     * 从候选中选一条，返回其在 [candidates] 中的下标；[poolTag] 是探索池标记
     * （"new"=新频道半区 / "cold"=冷门半区 / "mix"=交叉兜底），记进 propensity 日志
     * 供分池离线评估。候选 ≥2 时才真正选臂并登记 pending/propensity；
     * 唯一候选直接返回 0（propensity 恒 1，无反事实价值，不污染日志）。
     */
    @Synchronized
    fun select(candidates: List<Candidate>, poolTag: String, nowMs: Long): Int {
        ensureLoaded()
        val n = candidates.size
        if (n == 0) return -1
        if (n == 1) return 0
        val alpha = currentAlpha()
        // 每个涉及臂只算一次 (θ, A⁻¹)：同频道多候选共享，d=8 求逆本身代价也可忽略
        val cache = HashMap<Long, Pair<DoubleArray, DoubleArray>>()
        val ucb = DoubleArray(n)
        var maxUcb = Double.NEGATIVE_INFINITY
        for (i in 0 until n) {
            val c = candidates[i]
            val (theta, invA) = cache.getOrPut(c.chatId) { thetaAndInv(arms[c.chatId]) }
            val x = c.features
            var exploit = 0.0
            for (p in 0 until DIM) exploit += theta[p] * x[p]
            // 探索项 xᵀA⁻¹x（跳过零特征分量，稀疏特征下半数乘法可省）
            var quad = 0.0
            for (p in 0 until DIM) {
                val xp = x[p]
                if (xp == 0.0) continue
                val row = p * DIM
                var s = 0.0
                for (q in 0 until DIM) s += invA[row + q] * x[q]
                quad += xp * s
            }
            val u = exploit + alpha * sqrt(quad.coerceAtLeast(0.0))
            ucb[i] = u
            if (u > maxUcb) maxUcb = u
        }
        // softmax(UCB/τ) + 均匀混合 → 随机选臂；propensity = 混合后的真实选中概率
        val probs = DoubleArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            val p = exp((ucb[i] - maxUcb) / SOFTMAX_TAU)
            probs[i] = p
            sum += p
        }
        var r = Random.nextDouble()
        var pick = n - 1 // 浮点兜底
        var chosenP = (1.0 - UNIFORM_MIX) * (probs[n - 1] / sum) + UNIFORM_MIX / n
        for (i in 0 until n) {
            val pi = (1.0 - UNIFORM_MIX) * (probs[i] / sum) + UNIFORM_MIX / n
            r -= pi
            if (r <= 0.0) {
                pick = i
                chosenP = pi
                break
            }
        }
        val c = candidates[pick]
        pending[c.key] = Pending(c.chatId, c.durationSec, c.features, chosenP, nowMs)
        while (pending.size > MAX_PENDING) {
            // LinkedHashMap 插入序：淘汰最老的未结算（丢弃不更新，理由见类注释）
            val eldest = pending.entries.first()
            pending.remove(eldest.key)
        }
        logBuf += "$nowMs\t${c.key}\t${c.chatId}\t$poolTag\t$chosenP\t$alpha\n"
        if (logBuf.size >= LOG_FLUSH_LINES) dir?.let { flushLog(it) }
        return pick
    }

    // ---------------- 奖励结算 ----------------

    /**
     * 结算待奖励的探索曝光（每次排序页输出前调用一次）：
     * [rewardOf](key, durationSec, selectedTs) 返回非 null → 立即按该值更新臂
     * （R79 起含负奖励：秒划 -0.4 是确定结局，不等窗；观看比例/回看分档正奖励）；
     * 返回 null = 尚无确定结局 → 继续等（延迟奖励，不冤枉）；
     * 选中超过 [REWARD_HORIZON_MS] 仍无信号 → 按 0 更新（用户有充分机会却没看）。
     */
    @Synchronized
    fun settle(rewardOf: (String, Int, Long) -> Double?, nowMs: Long) {
        if (pending.isEmpty()) return
        ensureLoaded()
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val r = rewardOf(e.key, e.value.durationSec, e.value.ts)
            when {
                r != null -> {
                    applyReward(e.value, r)
                    it.remove()
                }
                nowMs - e.value.ts >= REWARD_HORIZON_MS -> {
                    applyReward(e.value, 0.0)
                    it.remove()
                }
            }
        }
        maybePersist(nowMs)
    }

    /** LinUCB 标准更新：A += xxᵀ，b += r·x；同时驱动 α 收缩与落盘防抖计数 */
    private fun applyReward(p: Pending, r: Double) {
        if (arms.size >= MAX_ARMS && p.chatId !in arms) {
            // 臂表上限：淘汰最久未更新的臂（订阅频道数有限，这里主要防探索期新频道膨胀）
            val lru = arms.minByOrNull { it.value.lastTs }?.key
            if (lru != null) arms.remove(lru)
        }
        val arm = arms.getOrPut(p.chatId) { Arm() }
        // R79 奖励结算日志：负奖励（秒划）与强正信号（互动/完播/回看）打 i 级，
        // 弱正与 0 结算打 d 级——结构性可观又不刷屏（探索位 ~1/10 条才进 pending）
        val kind = when {
            r < 0.0 -> "负反馈"
            r >= 0.6 -> "强正信号"
            r > 0.0 -> "弱正信号"
            else -> "中性/超时"
        }
        if (r < 0.0 || r >= 0.6) {
            Log.i(TAG, "探索奖励结算: 频道尾号${p.chatId.toString().takeLast(6)} 奖励=%.2f（$kind）累计结算=${arm.pulls + 1}".format(r))
        } else {
            Log.d(TAG, "探索奖励结算: 频道尾号${p.chatId.toString().takeLast(6)} 奖励=%.2f（$kind）累计结算=${arm.pulls + 1}".format(r))
        }
        for (i in 0 until DIM) {
            val xi = p.features[i]
            if (xi == 0.0) continue
            arm.b[i] += r * xi
            val row = i * DIM
            for (j in 0 until DIM) {
                val xj = p.features[j]
                if (xj != 0.0) arm.a[row + j] += xi * xj
            }
        }
        arm.pulls++
        arm.lastTs = System.currentTimeMillis()
        totalPulls++
        dirtySince++
    }

    // ---------------- α 收缩（防震荡核心） ----------------

    /** α(t) = α0 / √(1 + t/T)：t 次奖励结算后探索强度单调收缩，下限 [ALPHA_MIN] 不锁死 */
    private fun currentAlpha(): Double =
        (ALPHA0 / sqrt(1.0 + totalPulls.toDouble() / ALPHA_HALF_LIFE)).coerceAtLeast(ALPHA_MIN)

    // ---------------- 臂参数数学（θ = A⁻¹b，A⁻¹ 用 Gauss-Jordan 部分主元求逆） ----------------

    /** 未见过的臂按 A=(λI) 处理：θ=0、A⁻¹=(1/λ)I → 探索项最大（不确定的多探） */
    private fun thetaAndInv(arm: Arm?): Pair<DoubleArray, DoubleArray> {
        if (arm == null) {
            val inv = DoubleArray(DIM * DIM)
            for (i in 0 until DIM) inv[i * DIM + i] = 1.0 / RIDGE_LAMBDA
            return DoubleArray(DIM) to inv
        }
        val inv = invert(arm.a)
        val theta = DoubleArray(DIM)
        for (i in 0 until DIM) {
            var s = 0.0
            val row = i * DIM
            for (j in 0 until DIM) s += inv[row + j] * arm.b[j]
            theta[i] = s
        }
        return theta to inv
    }

    /** d×d 行主序矩阵求逆（Gauss-Jordan + 部分主元）；奇异兜底回 (1/λ)I（数值上不应发生） */
    private fun invert(a: DoubleArray): DoubleArray {
        val n = DIM
        val m = DoubleArray(n * n) { a[it] }
        val inv = DoubleArray(n * n)
        for (i in 0 until n) inv[i * n + i] = 1.0
        for (col in 0 until n) {
            var piv = col
            var best = kotlin.math.abs(m[col * n + col])
            for (r in col + 1 until n) {
                val v = kotlin.math.abs(m[r * n + col])
                if (v > best) {
                    best = v
                    piv = r
                }
            }
            if (best < 1e-12) {
                val fallback = DoubleArray(n * n)
                for (i in 0 until n) fallback[i * n + i] = 1.0 / RIDGE_LAMBDA
                return fallback
            }
            if (piv != col) {
                for (j in 0 until n) {
                    val tm = m[col * n + j]; m[col * n + j] = m[piv * n + j]; m[piv * n + j] = tm
                    val ti = inv[col * n + j]; inv[col * n + j] = inv[piv * n + j]; inv[piv * n + j] = ti
                }
            }
            val d0 = m[col * n + col]
            for (j in 0 until n) {
                m[col * n + j] /= d0
                inv[col * n + j] /= d0
            }
            for (r in 0 until n) {
                if (r == col) continue
                val f = m[r * n + col]
                if (f == 0.0) continue
                for (j in 0 until n) {
                    m[r * n + j] -= f * m[col * n + j]
                    inv[r * n + j] -= f * inv[col * n + j]
                }
            }
        }
        return inv
    }

    // ---------------- 持久化（linucb_arms.json + linucb_propensity.log，tmp+rename） ----------------

    /** 防抖落盘：每 [SAVE_EVERY] 次奖励结算或距上次 ≥[SAVE_INTERVAL_MS] 才写（页频以下） */
    private fun maybePersist(nowMs: Long) {
        val d = dir ?: return
        if (dirtySince == 0) return
        if (dirtySince < SAVE_EVERY && nowMs - lastSaveMs < SAVE_INTERVAL_MS) return
        runCatching {
            writeAtomic(File(d, ARMS_FILE), snapshotJson())
            flushLog(d)
        }
        dirtySince = 0
        lastSaveMs = nowMs
    }

    /** 臂参数快照：{"v":1,"totalPulls":N,"arms":{"chatId":{"a":[…],"b":[…],"n":pulls,"t":lastTs}}} */
    private fun snapshotJson(): String {
        val obj = JSONObject()
        obj.put("v", 1)
        obj.put("totalPulls", totalPulls)
        val armsObj = JSONObject()
        val entries = if (arms.size <= MAX_ARMS) {
            arms.entries.toList()
        } else {
            arms.entries.sortedByDescending { it.value.lastTs }.take(MAX_ARMS)
        }
        for ((id, arm) in entries) {
            armsObj.put(id.toString(), JSONObject().apply {
                put("a", JSONArray().apply { for (v in arm.a) put(v) })
                put("b", JSONArray().apply { for (v in arm.b) put(v) })
                put("n", arm.pulls)
                put("t", arm.lastTs)
            })
        }
        obj.put("arms", armsObj)
        return obj.toString()
    }

    /** 懒加载臂参数（首次 select/settle 时；文件缺失/损坏按全新带处理） */
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val d = dir ?: return
        val text = runCatching {
            File(d, ARMS_FILE).takeIf { it.isFile }?.readText()
        }.getOrNull() ?: return
        runCatching {
            val obj = JSONObject(text)
            totalPulls = obj.optInt("totalPulls", 0)
            val armsObj = obj.optJSONObject("arms") ?: return@runCatching
            for (k in armsObj.keys()) {
                val chatId = k.toLongOrNull() ?: continue
                val o = armsObj.optJSONObject(k) ?: continue
                val aArr = o.optJSONArray("a")
                val bArr = o.optJSONArray("b")
                if (aArr == null || bArr == null) continue
                if (aArr.length() != DIM * DIM || bArr.length() != DIM) continue // 维度不符的旧版数据跳过
                val arm = Arm()
                for (i in 0 until DIM * DIM) arm.a[i] = aArr.optDouble(i, 0.0)
                for (i in 0 until DIM) arm.b[i] = bArr.optDouble(i, 0.0)
                arm.pulls = o.optInt("n", 0)
                arm.lastTs = o.optLong("t", 0L)
                arms[chatId] = arm
            }
        }
    }

    /** propensity 日志冲刷：超 [PROP_MAX_BYTES] 先截断保尾（tmp+rename），再追加缓冲行 */
    private fun flushLog(d: File) {
        if (logBuf.isEmpty()) return
        runCatching {
            d.mkdirs()
            val f = File(d, PROP_FILE)
            if (f.isFile && f.length() > PROP_MAX_BYTES) {
                // 截断保尾：只留最近 [PROP_KEEP_BYTES] 字节（按字符近似，日志行基本 ASCII）
                val tail = f.readText().takeLast(PROP_KEEP_BYTES)
                writeAtomic(f, tail)
            }
            f.appendText(logBuf.joinToString(""))
            logBuf.clear()
        }
    }

    /** tmp+rename 原子覆盖写（与 LocalStore.writeText 同口径；rename 失败退化直接覆盖写） */
    private fun writeAtomic(file: File, text: String) {
        val d = file.parentFile ?: return
        d.mkdirs()
        val tmp = File(d, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            runCatching { file.writeText(text) }
            tmp.delete()
        }
    }

    companion object {
        private const val TAG = "LinUCB"

        /** 上下文特征维度（顺序语义见类注释；改动需同步 FeedSource 的 featuresOf 装配处） */
        const val DIM = 8

        /** 岭正则 λ：新臂 A 的初始对角（先验强度；越小新臂探索项越大） */
        private const val RIDGE_LAMBDA = 1.0

        /** 初始探索强度（t=0 时的 α） */
        private const val ALPHA0 = 1.2

        /** α 半衰样本数：约这么多次奖励结算后 α 降到 α0/√2，√(1+t/T) 持续收缩 */
        private const val ALPHA_HALF_LIFE = 80.0

        /** α 下限：永远保留最小探索，防 bandit 收敛后锁死（单用户兴趣会漂移） */
        private const val ALPHA_MIN = 0.2

        /** softmax 温度：UCB 分差折算成选择概率的刻度（UCB 量级 ~[0,3]，0.5 区分度适中） */
        private const val SOFTMAX_TAU = 0.5

        /** 均匀混合比例：每个候选保底概率 MIX/n——propensity 下界 + 防策略自激锁死 */
        private const val UNIFORM_MIX = 0.05

        /** 奖励结算窗：选中超过此时长仍无正信号按奖励 0 结算（页级曝光口径，给足观看机会） */
        private const val REWARD_HORIZON_MS = 20L * 60 * 1000

        /** 待结算队列上限（20 分钟窗口内 ~10 探索/页 × 翻页速率，正常远低于此） */
        private const val MAX_PENDING = 500

        /** 臂数上限：超出按 lastTs 淘汰最久未更新（防探索期新频道把表撑大） */
        private const val MAX_ARMS = 300

        /** 落盘防抖：每这么多次奖励结算写一次 */
        private const val SAVE_EVERY = 8

        /** 落盘防抖：距上次写入至少这么久（与 SAVE_EVERY 任一满足即写） */
        private const val SAVE_INTERVAL_MS = 60_000L

        /** 臂参数文件名（store 目录下） */
        private const val ARMS_FILE = "linucb_arms.json"

        /** propensity 日志文件名（store 目录下，append-only） */
        private const val PROP_FILE = "linucb_propensity.log"

        /** 日志文件超此字节数触发截断保尾 */
        private const val PROP_MAX_BYTES = 256 * 1024

        /** 截断后保留的最近字节数 */
        private const val PROP_KEEP_BYTES = 128 * 1024

        /** 内存日志缓冲行数上限（到量即冲刷，不等落盘防抖） */
        private const val LOG_FLUSH_LINES = 100
    }
}

/**
 * LinUCB 的进程级注册点（五个 Holder 的统一口径见 FeedSource.kt 中
 * ChannelFatigueHolder 类注释开头的"Holder 总约"，此处只记本件差异）：
 *
 *  - 注册语义：**首注册生效**（幂等）——多个 WeightedAggregateFeedSource 实例共享
 *    同一 bandit（臂参数文件口径唯一）；VM 重建后沿用旧实例，待结算 pending 奖励的
 *    会话连续性得以保留（进程死亡才丢弃，见 LinUCB 类注释"pending 不落盘"口径）。
 *  - 注册方/时机：WeightedAggregateFeedSource 构造时（FeedViewModel 默认源恒构造它），
 *    先于首次 rankWeightedPage 取用。
 *  - 未注册降级：探索位回退"主序首条"旧行为，整条排序链退化为无 bandit 版本
 *    （单测即此路径）。
 */
internal object LinUCBHolder {
    @Volatile
    private var bandit: LinUCB? = null

    /** 注册（幂等，首个注册生效——多个 WeightedAggregateFeedSource 实例共享同一文件口径） */
    fun register(store: LocalStore): LinUCB {
        bandit?.let { return it }
        return synchronized(this) {
            bandit ?: LinUCB(resolveStoreDir(store)).also { bandit = it }
        }
    }

    fun current(): LinUCB? = bandit

    /**
     * 取 store 目录（R123 起走 LocalStore.storageDir 正式访问口；此前的反射桥
     * 在 release 构建 R8 改名私有字段后会静默解析失败、退化为纯内存模式，已拆除）。
     */
    private fun resolveStoreDir(store: LocalStore): File? = store.storageDir
}
