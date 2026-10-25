package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R317 发布直方图点查件 SQL 形状回归锚（[tagItemsJoinSql] 同款 R269 机检锚——防
 * 27dd5a8 类 FROM/谓词误删漂移）：频道域点查（WHERE chatId=?）+ 日粒度直方图
 * （GROUP BY dateEpochSec/86400——monthHistogram 既有口径的频道版）+ 双臂形状锁。
 */
class ChatHistogramSqlShapeTest {

    private val sql = chatHistogramDaySql()
    private val refsSql = chatHistogramDayRefsSql("repost_refs")

    // R323 两窗构成档（chatPublishMediaHistogram——自行漂移候选的 S3 家族消费）
    private val mediaSql = chatHistogramMediaDaySql()
    private val mediaRefsSql = chatHistogramMediaDayRefsSql("repost_refs")

    @Test
    fun `items 臂-必须含 FROM items 与频道域点查谓词（有界点查非全库扫描的形状锁）`() {
        assertTrue(sql.contains("FROM items"))
        assertTrue(sql.contains("WHERE chatId=?"))
        // 无 ORDER BY/LIMIT 聚合形状（GROUP BY 输出即全集——聚合非分页召回）
        assertTrue(!sql.contains("LIMIT"))
    }

    @Test
    fun `items 臂-日粒度直方图口径（dateEpochSec 按日聚合=monthHistogram 既有口径频道版）`() {
        assertTrue(sql.contains("GROUP BY day"))
        assertTrue(sql.contains("dateEpochSec/86400 AS day"))
        assertTrue(sql.contains("COUNT(*)"))
        // 0 值日期行不入证（诚实证据面）
        assertTrue(sql.contains("dateEpochSec > 0"))
    }

    @Test
    fun `items 臂-锁频道域索引（snap2 EXPLAIN 实锤自然计划误选 idx_items_album 的回归锚）`() {
        // R313 tagItemsSlicedJoinSql 同款纪律：INDEXED BY 锁驱动侧（chatId=索引前导列，
        // WHERE 可下压；索引重建 pending 窗=SQLITE_ERROR 被 runCatching 吞成缺据 null）
        assertTrue(sql.contains("INDEXED BY idx_items_chat_msg"))
    }

    @Test
    fun `refs 臂-别名表镜像同形状（v11 双臂口径——表名调用方传 companion 常量）`() {
        assertTrue(refsSql.startsWith("SELECT dateEpochSec/86400 AS day, COUNT(*) FROM repost_refs"))
        assertTrue(refsSql.contains("WHERE chatId=? AND dateEpochSec > 0 GROUP BY day"))
        assertTrue(refsSql.contains("INDEXED BY idx_refs_chat_msg"))
        // 两臂除表名与索引名外逐字一致（形状锁同一机检锚管——防两臂漂移出不同语义）
        assertEquals(
            sql.replaceFirst("FROM items", "FROM T").replaceFirst("idx_items_chat_msg", "IDX"),
            refsSql.replaceFirst("FROM repost_refs", "FROM T").replaceFirst("idx_refs_chat_msg", "IDX"),
        )
    }

    // ==================== R323 两窗构成档（chatPublishMediaHistogram 形状锁） ====================

    @Test
    fun `media 臂-构成分桶口径（日粒度+mediaType 双列聚合——自行两窗三票原料）`() {
        assertTrue(mediaSql.contains("FROM items"))
        assertTrue(mediaSql.contains("WHERE chatId=? AND dateEpochSec > 0"))
        assertTrue(mediaSql.contains("dateEpochSec/86400 AS day"))
        assertTrue(mediaSql.contains("GROUP BY day, mediaType"))
        assertTrue(mediaSql.contains("COUNT(*)"))
        assertTrue(!mediaSql.contains("LIMIT")) // 聚合形状（输出即全集）
    }

    @Test
    fun `media 臂-锁频道域索引同族（有界点查非全库扫描——与 day 档同锁）`() {
        assertTrue(mediaSql.contains("INDEXED BY idx_items_chat_msg"))
    }

    @Test
    fun `media refs 臂-别名表镜像同形状（两臂除表名索引名外逐字一致）`() {
        assertTrue(mediaRefsSql.startsWith("SELECT dateEpochSec/86400 AS day, mediaType, COUNT(*) FROM repost_refs"))
        assertTrue(mediaRefsSql.contains("INDEXED BY idx_refs_chat_msg"))
        assertEquals(
            mediaSql.replaceFirst("FROM items", "FROM T").replaceFirst("idx_items_chat_msg", "IDX"),
            mediaRefsSql.replaceFirst("FROM repost_refs", "FROM T").replaceFirst("idx_refs_chat_msg", "IDX"),
        )
    }
}
