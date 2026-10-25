package dev.tgfeed.core.tastemap

import kotlin.math.ln1p

/**
 * R283 双星判定政策件（R249「双星系统」判定的维度化升级·单一出处）。
 *
 * 旧判定（TasteMapScreen 7.6 内联块）：合并边权 ≥8 硬门——单一计数不分辨证据质地：
 * 同会话共现（co_watch 弱边）刷量可过门，互转+互链俱全但计数薄的真对反被挡
 * （R168/R246「互链数据薄」实锤的系统性误伤）。
 *
 * 维度化证据（PairEvidence）：
 *  - 互转次数（forward_src/dst 合并·最强主动证据）/互链次数（link+comment 引用族）
 *    ——装配期互联分类账同款扫描零成本直供；
 *  - 共现次数（co_watch·弱证据：只加分不过门）；
 *  - 内容同源（编年史⑥数据底座 interlinkPairSummary：同指纹发布/收编——方向条
 *    [sharedDirected] 计入强证据，共现条 [sharedCoOccur] 只加分）；
 *  - 时间分布（跨度 spanDays/最近 recentDays：持续且活跃的对加成；缺据=中性零惩罚
 *    ——诚实降级，不冒充有时间证据）。
 *
 * 判定=**强证据门**（forward+link+sharedDirected ≥ [MIN_STRONG_EVIDENCE]——共现类
 * 弱证单独不过门）∧ **维度分门**（Σ w·ln1p(count) ×（1+时间档加成）≥ [MIN_SCORE]）。
 *
 * 量纲校准（MIN_SCORE=4.35 与旧「合并边权≥8」的对照表，单测锚定）：
 *  - 8 条纯互转=6.59 / 8 条纯互链=4.39≥4.35（旧门组合水位保留——存量真对不退化）；
 *  - 8 条纯共现=1.10 且强证据门拦（旧门可过的假双星挡掉——升级语义，刻意）；
 *  - 互转2+互链2=5.49 / 互转3+互链3=6.93（证据维度齐全的中量对可过门——「喂更厚
 *    的证据」的刻意放水方向；top 截断+每星单对保证稀有性）；
 *  - 互转1+互链1=3.47（门下——单线索不成系统）。
 *
 * 纯函数零 Android 依赖（JVM 单测直锚）；选择结果确定性（分降序→a→b 升序）。
 */
object BinaryStarPolicy {

    /** 互转量纲权重（最强主动证据） */
    const val W_FORWARD = 3.0f

    /** 互链量纲权重（link+comment 引用族） */
    const val W_LINK = 2.0f

    /** 共现量纲权重（co_watch 弱边——只加分不过门） */
    const val W_COWATCH = 0.5f

    /** 内容同源方向条量纲权重（同指纹发布/收编=互转语义族的内容级证据） */
    const val W_SHARED_DIRECTED = 2.5f

    /** 内容同源共现条量纲权重（同收编第三方——弱于方向条） */
    const val W_SHARED_COOCCUR = 0.8f

    /** 强证据门：互转+互链+内容方向条的合并计数下限（共现类弱证不计） */
    const val MIN_STRONG_EVIDENCE = 2

    /** 维度分门（校准见 KDoc 对照表） */
    const val MIN_SCORE = 4.35f

    /** 时间档：跨度下限（持续互联 ≥30 天才吃加成——一次性互转不吃） */
    const val SPAN_BONUS_DAYS = 30

    /** 时间档：近期活跃窗（最近互联 ≤30 天=关系还活着） */
    const val RECENT_ACTIVE_DAYS = 30

    /** 时间档加成：持续且活跃 */
    const val TIME_BONUS_ACTIVE = 0.15f

    /** 时间档加成：仅持续（沉寂老关系半档） */
    const val TIME_BONUS_SPAN_ONLY = 0.075f

    /** 装配期候选对增强（interlinkPairSummary 只读查询）的对数上限 */
    const val ENRICH_TOP_K = 24

    /** 装配期候选对增强的总时间预算（毫秒——超预算剩余对保持缺据降级） */
    const val ENRICH_TIME_BUDGET_MS = 1200L

    /** 双星对证据（图谱三维计数 + 内容底座四维增强；span/recent -1=缺据） */
    data class PairEvidence(
        val a: Long,
        val b: Long,
        val forward: Int = 0,
        val link: Int = 0,
        val coWatch: Int = 0,
        val sharedDirected: Int = 0,
        val sharedCoOccur: Int = 0,
        val spanDays: Int = -1,
        val recentDays: Int = -1,
    ) {
        /** 图谱边合并计数（信息卡「N 条关联」展示口径——与互联分类账同语义） */
        val mergedWeight: Int get() = forward + link + coWatch

        /** 强证据计数（共现类弱证不进） */
        val strongEvidence: Int get() = forward + link + sharedDirected
    }

    /** 判定结果（入选对：两端 chatId+维度分+展示口径合并权重+时间档加成系数） */
    data class PairVerdict(
        val a: Long,
        val b: Long,
        val score: Float,
        val mergedWeight: Int,
        val timeBonus: Float,
    )

    /** 时间档加成系数（1+加成=乘数；缺据/短跨度=0 中性） */
    fun timeBonusOf(e: PairEvidence): Float = when {
        e.spanDays < SPAN_BONUS_DAYS -> 0f
        e.recentDays in 0..RECENT_ACTIVE_DAYS -> TIME_BONUS_ACTIVE
        else -> TIME_BONUS_SPAN_ONLY
    }

    /** 维度分（含时间档加成） */
    fun scoreOf(e: PairEvidence): Float {
        val base = W_FORWARD * ln1p(e.forward.toFloat()) +
            W_LINK * ln1p(e.link.toFloat()) +
            W_COWATCH * ln1p(e.coWatch.toFloat()) +
            W_SHARED_DIRECTED * ln1p(e.sharedDirected.toFloat()) +
            W_SHARED_COOCCUR * ln1p(e.sharedCoOccur.toFloat())
        return base * (1f + timeBonusOf(e))
    }

    /** 单对过门判定（强证据门 ∧ 维度分门） */
    fun passes(e: PairEvidence, minScore: Float = MIN_SCORE): Boolean =
        e.strongEvidence >= MIN_STRONG_EVIDENCE && scoreOf(e) >= minScore

    /**
     * 双星对选择（布局期单一口）：维度分降序（同分 a→b 升序=确定性）逐对过门
     * （强证据门+分门+[eligible] 资格谓词=实体天体剔除），每星只入一对，
     * top [maxPairs] 截断。返回选中对的判定结果（含展示口径 mergedWeight）。
     */
    fun selectBinaryPairs(
        candidates: List<PairEvidence>,
        maxPairs: Int,
        eligible: (Long) -> Boolean = { true },
        minScore: Float = MIN_SCORE,
    ): List<PairVerdict> {
        if (maxPairs <= 0 || candidates.isEmpty()) return emptyList()
        val out = ArrayList<PairVerdict>(maxPairs)
        val used = HashSet<Long>(maxPairs * 2)
        val sorted = candidates.sortedWith(
            compareByDescending<PairEvidence> { scoreOf(it) }.thenBy { it.a }.thenBy { it.b },
        )
        for (e in sorted) {
            if (out.size >= maxPairs) break
            if (e.strongEvidence < MIN_STRONG_EVIDENCE) continue
            val score = scoreOf(e)
            if (score < minScore) continue
            if (!eligible(e.a) || !eligible(e.b)) continue
            if (e.a in used || e.b in used) continue
            used += e.a
            used += e.b
            out += PairVerdict(e.a, e.b, score, e.mergedWeight, timeBonusOf(e))
        }
        return out
    }
}
