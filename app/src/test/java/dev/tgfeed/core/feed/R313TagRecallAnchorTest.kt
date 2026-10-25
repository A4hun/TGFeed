package dev.tgfeed.core.feed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R313 R2 兴趣召回时间分代的接线锚（R293SearchRecallAnchorTest 同款源码扫描手法——
 * DeepSearchIndex 本体无法 JVM 实例化，形状锚防漂移）：
 *  1. 分代 SQL 形状（CROSS JOIN 锁 tag 侧驱动+代内日期区间+代内倒序有界）；
 *  2. tagRecall 词频闸三门（时间索引+精确行数+4×闸）与异常回落旧形；
 *  3. 消费口收敛（tagItemsByRelevance/searchHashtag 倒排主路同走 tagRecall）；
 *  4. tagItems 时间序契约原样（R222 时间轴/R234 消费方零影响——不分代）；
 *  5. 全库量程件唯一出处（like 与 tag 双路共用 itemsDateRange）。
 */
class R313TagRecallAnchorTest {

    private val src: String by lazy {
        java.io.File(
            System.getProperty("user.dir"),
            "src/main/java/dev/tgfeed/core/feed/DeepSearchIndex.kt",
        ).readText()
    }

    private val recommendSrc: String by lazy {
        java.io.File(
            System.getProperty("user.dir"),
            "src/main/java/dev/tgfeed/core/feed/FeedRecommendPart.kt",
        ).readText()
    }

    private fun fnBody(name: String, span: Int = 5200): String {
        val i = src.indexOf(name)
        assertTrue("函数须存在：$name", i >= 0)
        return src.substring(i).substring(0, minOf(span, src.length - i))
    }

    @Test
    fun `分代 SQL 形状：tag 侧驱动锁死+代内日期区间+代内倒序有界`() {
        val sql = tagItemsSlicedJoinSql("items.rowid")
        assertTrue(
            "CROSS JOIN 锁 tag 侧驱动（禁优化器换序）",
            sql.contains("FROM tag_items CROSS JOIN items ON items.rowid = tag_items.rowid_ref"),
        )
        assertTrue("tag 谓词", sql.contains("WHERE tag_items.tag=?"))
        assertTrue("代内日期区间", sql.contains("items.dateEpochSec >= ? AND items.dateEpochSec < ?"))
        assertTrue("代内时间倒序有界", sql.contains("ORDER BY items.dateEpochSec DESC LIMIT ?"))
    }

    @Test
    fun `词频闸三门齐全+异常回落单路旧形`() {
        val body = fnBody("private fun tagRecall(")
        assertTrue("时间索引门", body.contains("dateIndexReadyCached(d)"))
        assertTrue("tag 行数精确计数", body.contains("tagRowCount(d, key)"))
        assertTrue("同一 GATE_FACTOR 闸", body.contains("RecallSlicePolicy.shouldStratify"))
        assertTrue("闸上分代", body.contains("tagItemsSlicedRecall(d, key, limit)"))
        assertTrue("闸下/异常回落单路旧形", body.contains("tagItemsRecall(d, key, limit)"))
    }

    @Test
    fun `分代落形复用 RecallSlicePolicy 与全库量程件（绝不 items 侧 INDEXED BY）`() {
        val body = fnBody("private fun tagItemsSlicedRecall(")
        assertTrue("全库量程件", body.contains("itemsDateRange(d)"))
        assertTrue("R293 纯件分代", body.contains("RecallSlicePolicy.slices("))
        assertTrue(body.contains("RECALL_SLICE_BUCKETS"))
        assertTrue(body.contains("RECALL_SLICE_OVERSAMPLE"))
        assertTrue("SQL 唯一源", body.contains("tagItemsSlicedJoinSql(ITEM_COLS_FROM_ITEMS)"))
        assertFalse(
            "稀疏 tag 的 items 侧 INDEXED BY=全表点查灾难（驱动侧已锁 tag_items）",
            body.contains("INDEXED BY"),
        )
    }

    @Test
    fun `消费口收敛：R2 兴趣路与 hashtag 倒排主路同走 tagRecall`() {
        assertTrue(
            "tagItemsByRelevance 走词频闸",
            fnBody("fun tagItemsByRelevance(").contains("tagRecall(d, key, limit)"),
        )
        assertTrue(
            "searchHashtag 倒排主路走词频闸（R293 挂账③清账）",
            fnBody("private fun searchHashtag(").contains("tagRecall(d, tagFold, SEARCH_HARD_CAP)"),
        )
    }

    @Test
    fun `tagItems 时间序契约原样（不分代不接词频闸）`() {
        val body = fnBody("fun tagItems(tag: String", span = 1600)
        assertTrue("时间序直通单路旧形", body.contains("tagItemsRecall(d, key, limit)"))
        assertFalse("时间序契约不接分代（R222/R234 消费方零影响）", body.contains("tagRecall("))
    }

    @Test
    fun `R2 装配层调用点签名不变（召回内部换轨，装配零改动）`() {
        assertTrue(recommendSrc.contains("deepIndex.tagItemsByRelevance("))
    }

    @Test
    fun `全库量程件唯一出处（like 与 tag 双路共用）`() {
        assertTrue(fnBody("private fun likeSlicedCandidates(").contains("itemsDateRange(d)"))
        assertTrue(fnBody("private fun tagItemsSlicedRecall(").contains("itemsDateRange(d)"))
    }
}
