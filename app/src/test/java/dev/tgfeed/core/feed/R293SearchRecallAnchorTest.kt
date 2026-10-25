package dev.tgfeed.core.feed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R293 技术债清剿包一的接线锚（InterlinkSqlShapeTest/ChannelItemsByHourPageSqlTest
 * 源码扫描手法——DeepSearchIndex 本体无法 JVM 实例化，形状锚防三类漂移）：
 *  1. 任务②：searchHashtag 换轨 tag_items 后，任何人把 LIKE 全表扫接回主路/
 *     删掉覆盖闸兜底臂，这里当场红；
 *  2. 任务①：分代召回的 INDEXED BY 有界形状与词频闸在场（防「闸被绕回恒旧路」）；
 *  3. 任务③：meta 迁移裸 ALTER 全族清零（R286 挂账收口——ensureMetaColumn 内的
 *     `$column` 插值形态是唯一合法残余）。
 */
class R293SearchRecallAnchorTest {

    private val src: String by lazy {
        java.io.File(
            System.getProperty("user.dir"),
            "src/main/java/dev/tgfeed/core/feed/DeepSearchIndex.kt",
        ).readText()
    }

    private fun fnBody(name: String, span: Int = 5200): String {
        val i = src.indexOf(name)
        assertTrue("函数须存在：$name", i >= 0)
        return src.substring(i).substring(0, minOf(span, src.length - i))
    }

    @Test
    fun `任务② searchHashtag 换轨：倒排主路+覆盖闸兜底臂+同尺重排`() {
        val body = fnBody("private fun searchHashtag(")
        // R313：倒排主路改走 tagRecall 词频闸决策口（闸下仍 tagItemsRecall 单路旧形，
        // 闸上 tag_items 时间分代——挂账③超宽 tag 新 2000 偏置根治）
        assertTrue("倒排主路须在场（R313 词频闸决策口）", body.contains("tagRecall(d, tagFold"))
        assertTrue("覆盖闸兜底臂须在场", body.contains("tagCoverageLikelyIncompleteThrottled()"))
        assertTrue("兜底臂走词频闸召回", body.contains("likeRecall(d, tokens, isAnd = true)"))
        assertTrue("R288 同尺重排不动", body.contains("rescoreByRelevance("))
        assertTrue("双轨复核语义不动", body.contains("hashtagHits"))
    }

    @Test
    fun `任务② tagItems 公共 API 与换轨召回共用单一 SQL 源`() {
        val pub = fnBody("fun tagItems(tag: String", span = 1600)
        assertTrue("tagItems 委托 tagItemsRecall", pub.contains("tagItemsRecall(d, key, limit)"))
        val recall = fnBody("private fun tagItemsRecall(")
        assertTrue("SQL 唯一源 tagItemsJoinSql", recall.contains("tagItemsJoinSql(ITEM_COLS_FROM_ITEMS)"))
        assertFalse("召回本体不私写 JOIN SQL", recall.contains("JOIN tag_items ON"))
    }

    @Test
    fun `任务① 词频闸三门齐全（统计在场+时间索引就绪+规模超闸）`() {
        val body = fnBody("private fun likeRecall(")
        assertTrue(body.contains("rankStatsOrNull(d)"))
        assertTrue(body.contains("dateIndexReadyCached(d)"))
        assertTrue(body.contains("RecallSlicePolicy.shouldStratify"))
        assertTrue(body.contains("RecallSlicePolicy.estimateRecallSize"))
    }

    @Test
    fun `任务① 分代召回=INDEXED BY 有界形状+词位谓词括号化`() {
        val body = fnBody("private fun likeSlicedCandidates(")
        assertTrue("锁死访问路径", body.contains("INDEXED BY $"))
        assertTrue("代内日期区间", body.contains("dateEpochSec >= ? AND dateEpochSec < ?"))
        assertTrue("代内时间倒序有界", body.contains("ORDER BY dateEpochSec DESC LIMIT"))
        // 词位组括号化（OR 形态防 AND 优先级吞并日期谓词）
        assertTrue("词位谓词整组括号化", body.contains("WHERE ("))
    }

    @Test
    fun `任务③ meta 迁移裸 ALTER 全族清零（唯一合法形态=ensureMetaColumn 内插值）`() {
        val occurrences = src.split("ALTER TABLE meta ADD COLUMN ").drop(1)
        assertTrue("ensureMetaColumn 本体的 ALTER 须在场", occurrences.isNotEmpty())
        for (tail in occurrences) {
            assertTrue(
                "裸 ALTER 残余：ALTER TABLE meta ADD COLUMN ${tail.take(30)}…",
                tail.startsWith("\$column"),
            )
        }
    }

    @Test
    fun `任务① 消费口收敛（精确与模糊 LIKE 同走 likeRecall 决策口）`() {
        assertTrue(fnBody("private fun searchExactLike(").contains("likeRecall(d, tokens, isAnd = true)"))
        assertTrue(fnBody("private fun searchFuzzyLike(").contains("likeRecall(d, tokens, isAnd = false)"))
    }
}
