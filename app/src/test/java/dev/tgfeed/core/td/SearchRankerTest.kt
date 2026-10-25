package dev.tgfeed.core.td

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SearchRanker]（R288 BM25 轻量变体）纯函数单测：
 * 算分确定性/排序正确性（相关度优先·时间兜底·key 收尾）/空集/单文档退化/
 * idf 单调性/tf 饱和/长度归一/词位计数口径（tokenize 同源属性锚）。
 */
class SearchRankerTest {

    // ---------------- idf：BM25+ 平滑变体 ----------------

    @Test
    fun `idf 随 df 单调递减且 df0 取最大`() {
        val n = 1_000_000L
        val max = SearchRanker.idf(n, 0) // 样本外稀有词 = 最大区分度
        val rare = SearchRanker.idf(n, 100)
        val common = SearchRanker.idf(n, 100_000)
        val ubiquitous = SearchRanker.idf(n, n)
        assertTrue(max > rare)
        assertTrue(rare > common)
        assertTrue(common > ubiquitous)
        assertTrue(ubiquitous > 0.0) // BM25+ 平滑：不为负（人人有份的词趋 0 不反转）
    }

    @Test
    fun `idf 边界防御`() {
        assertEquals(0.0, SearchRanker.idf(0, 0), 1e-9) // 空语料：无区分度信息
        assertEquals(0.0, SearchRanker.idf(-5, 3), 1e-9)
        // df > N（采样放大估计可能微超）钳到 N：不炸不反转
        assertEquals(SearchRanker.idf(100, 100), SearchRanker.idf(100, 10_000), 1e-9)
    }

    // ---------------- bm25Term：tf 饱和 + 长度归一 ----------------

    @Test
    fun `tf 饱和_亚线性增长`() {
        val idf = 5.0
        val s1 = SearchRanker.bm25Term(1, idf, docLen = 50, avgDocLen = 50.0)
        val s4 = SearchRanker.bm25Term(4, idf, docLen = 50, avgDocLen = 50.0)
        val s16 = SearchRanker.bm25Term(16, idf, docLen = 50, avgDocLen = 50.0)
        assertTrue(s1 > 0.0)
        assertTrue(s4 > s1) // 多次出现更相关
        assertTrue(s16 > s4)
        assertTrue(s4 < 4.0 * s1) // 但亚线性（k1 饱和）
        assertTrue(s16 < 16.0 * s1)
    }

    @Test
    fun `长度归一_同 tf 长文档罚分`() {
        val idf = 5.0
        val shortDoc = SearchRanker.bm25Term(1, idf, docLen = 20, avgDocLen = 50.0)
        val avgDoc = SearchRanker.bm25Term(1, idf, docLen = 50, avgDocLen = 50.0)
        val longDoc = SearchRanker.bm25Term(1, idf, docLen = 500, avgDocLen = 50.0)
        assertTrue(shortDoc > avgDoc) // 短文档同 tf 更聚焦
        assertTrue(avgDoc > longDoc) // 长清单体顺带提及被罚
    }

    @Test
    fun `bm25Term 退化防御`() {
        assertEquals(0.0, SearchRanker.bm25Term(0, 5.0, 50, 50.0), 1e-9) // 缺位词不贡献
        assertEquals(0.0, SearchRanker.bm25Term(-1, 5.0, 50, 50.0), 1e-9)
        // avgdl<=0：长度归一退化为 1（不奖不罚），分数仍为正
        assertTrue(SearchRanker.bm25Term(1, 5.0, 50, 0.0) > 0.0)
    }

    // ---------------- countTerm：tf 计数口径 ----------------

    @Test
    fun `CJK 词位重叠计数`() {
        assertEquals(3, SearchRanker.countTerm("电电电电", "电电")) // 重叠 bigram 口径
        assertEquals(4, SearchRanker.countTerm("电电电电", "电"))
        assertEquals(2, SearchRanker.countTerm("电影频道放电影", "电影"))
        assertEquals(0, SearchRanker.countTerm("今日合集", "电影"))
    }

    @Test
    fun `ASCII 词位边界守卫_整词语义`() {
        assertEquals(2, SearchRanker.countTerm("abc xabc abc", "abc")) // xabc 词身延续不计
        assertEquals(0, SearchRanker.countTerm("aaaa", "aa")) // 整词口径：子串不计
        assertEquals(2, SearchRanker.countTerm("1080p 1080p", "1080p"))
        assertEquals(1, SearchRanker.countTerm("ab_cd", "ab")) // 下划线=边界（tokenize [a-z0-9]+ 同口径）
        assertEquals(0, SearchRanker.countTerm("", "abc"))
        assertEquals(0, SearchRanker.countTerm("abc", ""))
    }

    // ---------------- estimateDocTokens：与 tokenize(forIndex=true) 多重集大小同源 ----------------

    @Test
    fun `词位数估算与 tokenize 建表多重集逐字节相等`() {
        val samples = listOf(
            "",
            "a",
            "电",
            "hello world 电影合集",
            "1080p合集",
            "宫崎骏合集",
            "hello，世界1080p",
            "ab_cd ef-gh",
            "电电电电",
            "a1 b2 電影 合集 1080P".lowercase(),
        )
        for (s in samples) {
            assertEquals(
                "样本=「$s」",
                SearchTextKit.tokenize(s, forIndex = true).size,
                SearchRanker.estimateDocTokens(s),
            )
        }
    }

    // ---------------- scoreDoc：合分行为 ----------------

    private val corpus = SearchRanker.Corpus(totalDocs = 1_000_000L, avgDocLen = 50.0)

    @Test
    fun `聚焦短文档压过长清单体`() {
        val terms = listOf(SearchRanker.Term("电影", SearchRanker.idf(corpus.totalDocs, 10_000)))
        val focused = "电影 电影 电影 必看" // 短 + 高 tf
        val linkDump = "电影 " + "片单链接邀请码每日更新防走失备用入口导航 ".repeat(20) // 长 + tf=1
        val sFocused = SearchRanker.scoreDoc(
            terms, focused, SearchRanker.estimateDocTokens(focused), corpus, phrase = "电影",
        )
        val sDump = SearchRanker.scoreDoc(
            terms, linkDump, SearchRanker.estimateDocTokens(linkDump), corpus, phrase = "电影",
        )
        assertTrue("聚焦分 $sFocused 应 > 清单体分 $sDump", sFocused > sDump)
    }

    @Test
    fun `稀有词命中压过常见词命中`() {
        val rare = listOf(SearchRanker.Term("宫崎", SearchRanker.idf(corpus.totalDocs, 100)))
        val common = listOf(SearchRanker.Term("电影", SearchRanker.idf(corpus.totalDocs, 500_000)))
        val doc = "宫崎 电影 各出现一次的短文"
        val dl = SearchRanker.estimateDocTokens(doc)
        val sRare = SearchRanker.scoreDoc(rare, doc, dl, corpus)
        val sCommon = SearchRanker.scoreDoc(common, doc, dl, corpus)
        assertTrue(sRare > sCommon) // idf 是区分度主承载
    }

    @Test
    fun `完整短语命中加成`() {
        val terms = listOf(
            SearchRanker.Term("宫崎", SearchRanker.idf(corpus.totalDocs, 1000)),
            SearchRanker.Term("崎骏", SearchRanker.idf(corpus.totalDocs, 1000)),
        )
        val phraseHit = "宫崎骏合集"
        val scattered = "宫崎 与 骏 隔开的文本" // bigram 崎骏 不连续出现
        val dl1 = SearchRanker.estimateDocTokens(phraseHit)
        val dl2 = SearchRanker.estimateDocTokens(scattered)
        val sHit = SearchRanker.scoreDoc(terms, phraseHit, dl1, corpus, phrase = "宫崎骏")
        val sScattered = SearchRanker.scoreDoc(terms, scattered, dl2, corpus, phrase = "宫崎骏")
        assertTrue(sHit > sScattered) // 词位命中数更多 + 短语加成双信号
    }

    @Test
    fun `缺位与空词位归零`() {
        val terms = listOf(SearchRanker.Term("电影", 5.0))
        assertEquals(0.0, SearchRanker.scoreDoc(terms, "完全不含查询词的文本", 10, corpus), 1e-9)
        assertEquals(0.0, SearchRanker.scoreDoc(emptyList(), "任何文本", 10, corpus, "任何"), 1e-9)
    }

    @Test
    fun `单文档退化_norm 约 1 不炸`() {
        val doc = "只有这一个文档 电影"
        val dl = SearchRanker.estimateDocTokens(doc)
        val solo = SearchRanker.Corpus(totalDocs = 1, avgDocLen = dl.toDouble())
        val s = SearchRanker.scoreDoc(
            listOf(SearchRanker.Term("电影", SearchRanker.idf(1, 0))), doc, dl, solo, "电影",
        )
        assertTrue(s.isFinite() && s > 0.0)
    }

    // ---------------- rank：排序键（相关度优先·时间兜底·key 收尾） ----------------

    private data class Doc(val key: String, val dateSec: Long, val s: Double)

    private fun rankDocs(docs: List<Doc>): List<Doc> =
        SearchRanker.rank(docs, { it.s }, { it.dateSec }, { it.key })

    @Test
    fun `相关度优先于时间`() {
        val old = Doc("a", dateSec = 100, s = 9.0) // 老但高相关
        val new = Doc("b", dateSec = 999, s = 1.0) // 新但低相关
        assertEquals(listOf("a", "b"), rankDocs(listOf(new, old)).map { it.key })
    }

    @Test
    fun `同分时间倒序兜底`() {
        val old = Doc("a", dateSec = 100, s = 5.0)
        val new = Doc("b", dateSec = 999, s = 5.0)
        assertEquals(listOf("b", "a"), rankDocs(listOf(old, new)).map { it.key })
    }

    @Test
    fun `同分同时 key 升序收尾_确定性`() {
        val docs = listOf(Doc("b", 100, 5.0), Doc("a", 100, 5.0), Doc("c", 100, 5.0))
        val first = rankDocs(docs).map { it.key }
        assertEquals(listOf("a", "b", "c"), first)
        assertEquals(first, rankDocs(docs.shuffled()).map { it.key }) // 同输入恒同序（洗牌入序无关）
    }

    @Test
    fun `空集与单元素直通`() {
        assertTrue(rankDocs(emptyList()).isEmpty())
        val single = listOf(Doc("x", 1, 0.0))
        assertSame(single, rankDocs(single)) // 单文档退化：零分配原样直通
    }

    @Test
    fun `scoreOf 每条只算一次`() {
        var calls = 0
        val docs = (1..50).map { Doc("k$it", it.toLong(), it.toDouble()) }
        SearchRanker.rank(docs, { calls++; it.s }, { it.dateSec }, { it.key })
        assertEquals(docs.size, calls) // 打分允许是重函数（fold+计数扫描）——不许重复算
    }

    @Test
    fun `零分文档不插到高分前`() {
        val noHit = Doc("z", dateSec = 9999, s = 0.0) // 最新但零相关
        val hit = Doc("a", dateSec = 1, s = 3.0)
        assertEquals(listOf("a", "z"), rankDocs(listOf(noHit, hit)).map { it.key })
    }
}
