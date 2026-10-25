package dev.tgfeed.core.td

import kotlin.math.ln

// ---------------- R288 搜索相关度排序（BM25 轻量变体，纯函数件，JVM 可测） ----------------

/**
 * R288 搜索相关度算分件（BM25 轻量变体）——无 FTS5 环境的 Kotlin 侧相关度排序
 * （设备 framework SQLite 无 FTS5 模块，r264_notes 实锤；本件把 bm25 从引擎能力
 * 降为纯函数，LIKE/倒排召回集合上统一重排）。
 *
 * ## 模型
 * `score(d,q) = Σ_t idf(t)·tf(t,d)·(k1+1)/(tf(t,d)+k1·(1−b+b·dl/avgdl)) + 短语加成`
 *  - k1=[K1]/b=[B]（BM25 经典默认值）；
 *  - idf(t)=ln(1+(N−df+0.5)/(df+0.5))（BM25+ 平滑变体：df=0 不炸不负——样本外
 *    稀有词取最大 idf，方向正确：越稀有区分度越高）；
 *  - tf=词位在文档 fold 截断文本内的重叠出现次数（CJK bigram 口径，与
 *    [SearchTextKit.tokenize] 建表词位同源）；纯 ASCII 词加 [a-z0-9] 边界守卫——
 *    与 tokens 列「整词成位」语义对齐（"aa" 不计入 "aaaa"，与 LIKE '% aa %'
 *    定界召回同口径）；
 *  - dl=文档词位数解析式估算（[estimateDocTokens]——与 tokenize(forIndex=true)
 *    多重集大小逐字节相等，单测属性锚锁定）；
 *  - 短语加成：文本含完整查询串（≥2 字）+[PHRASE_BOOST]——searchRelevance
 *    「完整短语=最强信号」同族直觉，量级刻意小于多词位 BM25 合分的区分跨度。
 *
 * ## 排序键（唯一出处，防多处各写一份）
 * [rank]：相关度降序 → dateEpochSec 降序（时间兜底/并列）→ key 升序（确定性
 * 兜底——同分同序可复现，快照对账/单测断言的前提）。
 *
 * ## 纯度
 * 零 IO 零框架依赖：集合统计（N/avgdl/df）由调用方（DeepSearchIndex 采样估计）
 * 以 [Corpus]/[Term] 注入；本件不算不猜。同输入恒同输出（确定性）。
 */
internal object SearchRanker {

    /** BM25 tf 饱和参数（经典默认 1.2） */
    const val K1 = 1.2

    /** BM25 文档长度归一参数（经典默认 0.75） */
    const val B = 0.75

    /** 完整短语命中加成（fold 后整串连续出现——searchRelevance 的 +2.0 同值同源直觉） */
    const val PHRASE_BOOST = 2.0

    /** 集合统计注入件：[totalDocs]=语料文档总数（N）；[avgDocLen]=平均文档词位数（avgdl，>0） */
    class Corpus(val totalDocs: Long, val avgDocLen: Double)

    /** 查询词（[token]=fold 后词位，纯字母数字；[idf]=文档频率权重，调用方按语料统计算好） */
    class Term(val token: String, val idf: Double)

    /**
     * BM25+ 平滑 idf：ln(1+(N−df+0.5)/(df+0.5))。
     * df 钳 [0,N]（防御：采样放大估计可能微超）；df=0 → ln(2N+1) 最大值（稀有=高权重）；
     * df=N → ln(1+0.5/(N+0.5)) ≈ 最小正值（人人有份的词几乎无区分度，但不为负）。
     * N≤0（空语料）→ 0（无区分度信息，调用方据此可整体跳过排序）。
     */
    fun idf(totalDocs: Long, docFreq: Long): Double {
        if (totalDocs <= 0L) return 0.0
        val df = docFreq.coerceIn(0L, totalDocs).toDouble()
        val n = totalDocs.toDouble()
        return ln(1.0 + (n - df + 0.5) / (df + 0.5))
    }

    /**
     * 单词位 BM25 贡献：idf·tf·(k1+1)/(tf+k1·(1−b+b·dl/avgdl))。
     * tf≤0 → 0（缺位词不贡献——OR 召回下覆盖率由求和自然承载）；
     * avgDocLen≤0 防御 → 长度归一退化为 1（不奖不罚）。
     */
    fun bm25Term(tf: Int, termIdf: Double, docLen: Int, avgDocLen: Double): Double {
        if (tf <= 0) return 0.0
        val lenNorm = if (avgDocLen > 0.0 && docLen > 0) {
            1.0 - B + B * docLen.toDouble() / avgDocLen
        } else {
            1.0
        }
        return termIdf * (tf.toDouble() * (K1 + 1.0)) / (tf.toDouble() + K1 * lenNorm)
    }

    /**
     * 词位在文本内的出现次数（tf 原料）。
     *  - CJK 词位（含任何非 ASCII 字符）：重叠子串计数（"aaaa" 中 "aa"=3——bigram
     *    词位在 CJK 连续段内的真实生成次数）；
     *  - 纯 ASCII 词位：两侧 [a-z0-9] 边界守卫计数（与 tokenize 整词成位/
     *    LIKE '% t %' 定界同口径——"aa" 在 "aaaa" 中计 0，"abc" 在 "xabc" 中计 0）。
     * 空词/空文本 → 0。
     */
    fun countTerm(textFold: String, term: String): Int {
        if (term.isEmpty() || textFold.isEmpty()) return 0
        val ascii = term.all { it.code < 128 }
        var count = 0
        var from = 0
        while (true) {
            val i = textFold.indexOf(term, from)
            if (i < 0) return count
            if (!ascii || (isLatinBoundary(textFold, i - 1) && isLatinBoundary(textFold, i + term.length))) {
                count++
            }
            from = i + 1 // 重叠口径：CJK bigram 的真实计数语义
        }
    }

    /** ASCII 词位边界：越界=边界；界内字符是 ASCII 字母数字=非边界（词身延续） */
    private fun isLatinBoundary(text: String, idx: Int): Boolean {
        if (idx < 0 || idx >= text.length) return true
        val c = text[idx]
        return !(c.code < 128 && (c in 'a'..'z' || c in '0'..'9'))
    }

    /**
     * 文档词位数解析式估算（dl 原料）：**与 [SearchTextKit.tokenize](forIndex=true)
     * 多重集大小逐字节相等**（单测属性锚锁定）——ASCII 段计 [a-z0-9]+ 词组数，
     * CJK 段长 L 计 L 单字位 + (L−1) bigram 位 = 2L−1。
     * 注意：建表侧 tokenize 后还会 toSet 去重（真实索引词位数 ≤ 本估值）——
     * dl/avgdl 两侧同用本估值（DeepSearchIndex 采样同口径），自洽不漂移。
     */
    fun estimateDocTokens(textFold: String): Int {
        var n = 0
        var i = 0
        while (i < textFold.length) {
            val ascii = textFold[i].code < 128
            var j = i + 1
            while (j < textFold.length && (textFold[j].code < 128) == ascii) j++
            if (ascii) {
                var inWord = false
                for (k in i until j) {
                    val c = textFold[k]
                    val w = c in 'a'..'z' || c in '0'..'9'
                    if (w && !inWord) n++
                    inWord = w
                }
            } else {
                val runLen = j - i
                n += 2 * runLen - 1 // L 单字位 + (L−1) bigram 位
            }
            i = j
        }
        return n
    }

    /**
     * 单文档相关度分：[terms] 各词位 BM25 贡献求和 + 完整短语加成
     * （[phrase]≥2 字且连续出现时 +[PHRASE_BOOST]；phrase 为空/单字不加成——
     * 单字查询的 tf/idf 已承载全部区分度，加成就成了常数平移）。
     * 全词位缺位（tf 全 0）且无短语命中 → 0（调用方可据此过滤/直通）。
     */
    fun scoreDoc(
        terms: List<Term>,
        textFold: String,
        docLenTokens: Int,
        corpus: Corpus,
        phrase: String = "",
    ): Double {
        var s = 0.0
        for (t in terms) {
            s += bm25Term(countTerm(textFold, t.token), t.idf, docLenTokens, corpus.avgDocLen)
        }
        if (s > 0.0 && phrase.length >= 2 && textFold.contains(phrase)) s += PHRASE_BOOST
        return s
    }

    /**
     * 相关度重排（**排序键唯一出处**）：相关度降序 → 时间（秒）降序 → key 升序。
     * 分数每条只算一次（[scoreOf] 允许是重函数）；规模 ≤1 直通（单文档退化：
     * 无比较对象，保调用方原序——单元素/空集零分配）。
     * 排序是稳定全序（三键合成严格弱序+key 唯一兜底），同输入恒同序。
     */
    fun <T> rank(
        items: List<T>,
        scoreOf: (T) -> Double,
        dateSecOf: (T) -> Long,
        keyOf: (T) -> String,
    ): List<T> {
        if (items.size <= 1) return items
        val scored = items.map { it to scoreOf(it) }
        return scored.sortedWith(
            compareByDescending<Pair<T, Double>> { it.second }
                .thenByDescending { dateSecOf(it.first) }
                .thenBy { keyOf(it.first) },
        ).map { it.first }
    }
}
