package dev.tgfeed.core.feed

/**
 * R293 分代分层召回策略（纯函数件，零 IO，JVM 单测直测）——召回侧时间偏置的根治件。
 *
 * 病（R288 挂账①）：LIKE 召回 `ORDER BY dateEpochSec DESC LIMIT cap` 先召回再重排——
 * 命中数 ≫ cap 的超高频词，召回集被最近 cap 条占满，高相关老帖根本进不了召回集，
 * BM25 重排（[SearchRanker]）巧妇难为无米之炊。
 *
 * 修法（两阶段召回的收敛形态）：**词频闸 + 时间等分代**。命中估计超过
 * [GATE_FACTOR]×cap（截断率 ≥ 1−1/GATE_FACTOR，偏置足够深）才把全量程切成
 * 若干年代区间，每代独立 `LIMIT k` 召回（idx_items_date 索引倒序早停），
 * 并集后交 BM25 重排——老年代 Guaranteed 有代表进召回集。
 *
 * 成本红线（45s 级全表扫不劣化）证明：
 *  - 各代日期区间互不相交 → 访问量并集 ≤ 全表一遍（最坏=与现状同阶，永不更差）；
 *  - 触发分层的形态恒为高频词（闸在 [GATE_FACTOR]×cap 之上）→ 各代 LIMIT 早停，
 *    总访问量 ≈ Σmin(k/代内密度, 代内行数) ≪ N；
 *  - snap2.db（7.42M 行）实测：df=64k 结构性词位，旧路 2000 条只盖最近 36 天，
 *    分层并集 4322 条横跨 9 年（2017→2026），耗时同阶（详 r293_notes 验证节）。
 */
internal object RecallSlicePolicy {

    /**
     * 触发分层的估计命中倍数闸：估计命中 > [GATE_FACTOR]×cap 才分层。
     * 闸下（轻微截断，旧路仍覆盖 ≥1/GATE_FACTOR 命中）分层的额外扫描不划算——
     * 密度越低单路越早停不了、分层各代越接近穷扫全区间（最坏=一遍全表），
     * 把闸抬高就是把「最坏付费点」推向「偏置足够深才付费」。
     */
    const val GATE_FACTOR = 4

    /** 一个时间代：[fromSec, toSecExclusive) 闭开区间 + 本代召回上限 [limit] */
    data class Slice(val fromSec: Long, val toSecExclusive: Long, val limit: Int)

    /**
     * 召回规模估计（R288 df 采样估计的消费口）：
     *  - AND（精确/hashtag）：命中 ≤ min(df)（AND 单调收敛，最稀有词位定上界）；
     *  - OR（模糊+同义词扩展）：命中 ≤ min(Σdf, N)（并集不超全库）；
     *  - 词位空 = 0（调用方本就该短路）。
     */
    fun estimateRecallSize(isAnd: Boolean, dfs: List<Long>, totalDocs: Long): Long {
        if (dfs.isEmpty()) return 0L
        return if (isAnd) {
            dfs.min()
        } else {
            var sum = 0L
            for (d in dfs) {
                sum += d.coerceAtLeast(0L)
                if (sum >= totalDocs) return totalDocs
            }
            sum
        }
    }

    /** 分层闸：估计命中严格大于 [GATE_FACTOR]×cap（截断率 ≥75%）才值得分层 */
    fun shouldStratify(estimatedMatches: Long, cap: Int): Boolean =
        cap > 0 && estimatedMatches > GATE_FACTOR.toLong() * cap

    /**
     * 时间量程等分代（返回**新→旧**序；空表=退化量程信号，调用方回落旧单路）：
     *  - 覆盖完整：首代 from=0（兜 dateEpochSec=0 存量行——正常爬取恒>0，0 是迁移
     *    存量防御值），末代 to=maxDate+1，相邻代边界相接（无洞无叠）；
     *  - 每代 limit=⌈cap×oversample/bucketCount⌉——oversample 防「话题年代集中」
     *    形态单代截断缩量（全部命中挤在一代时，该代仍能给出 ⌈cap×oversample/B⌉ 条，
     *    而非被均分额度饿死）；
     *  - 退化拒切：maxDate≤0（无有效日期）或量程秒数 ≤ bucketCount（切了也没
     *    偏置可治）→ 空表。
     */
    fun slices(
        minDateSec: Long,
        maxDateSec: Long,
        bucketCount: Int,
        cap: Int,
        oversample: Int,
    ): List<Slice> {
        require(bucketCount >= 1) { "bucketCount must be >= 1" }
        require(cap >= 1) { "cap must be >= 1" }
        require(oversample >= 1) { "oversample must be >= 1" }
        if (maxDateSec <= 0L) return emptyList()
        val lo = minDateSec.coerceAtLeast(1L) // dateEpochSec=0 存量由首代 from=0 兜
        if (maxDateSec < lo) return emptyList()
        val span = maxDateSec - lo + 1
        if (span <= bucketCount.toLong()) return emptyList()
        val step = (span + bucketCount - 1) / bucketCount // 向上取整，末代兜底到 max+1
        val perSlice = ((cap.toLong() * oversample + bucketCount - 1) / bucketCount).toInt()
        val out = ArrayList<Slice>(bucketCount)
        var from = lo
        while (from <= maxDateSec) {
            val to = minOf(maxDateSec + 1, from + step)
            out += Slice(fromSec = from, toSecExclusive = to, limit = perSlice)
            from = to
        }
        // 首代下放到 0（兜 dateEpochSec=0 行；区间互交性不破：0 < lo ≤ 其余各代 from）
        val first = out.first()
        out[0] = first.copy(fromSec = 0L)
        out.reverse() // 新→旧：并集去重时新代先入（LinkedHashMap 保序，重排前的可读序）
        return out
    }
}
