package dev.tgfeed.core.feed

import dev.tgfeed.core.feed.IndexMergePolicy.ItemSide
import dev.tgfeed.core.feed.IndexMergePolicy.MergeAction
import dev.tgfeed.core.feed.IndexMergePolicy.MetaSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R287 多设备索引合并·裁决纯函数回归锚（IndexMergePolicy）。
 * 覆盖：分类矩阵 / 字段合并非空胜矩阵 / item_tags 并集 / 游标并集覆盖裁决 /
 * meta 触知型标记 / 内容一致性闸（R12 跨设备变体）。
 */
class IndexMergePolicyTest {

    private fun itemSide(
        caption: String = "", channelTitle: String = "", mimeType: String = "video/mp4",
        albumId: Long = 0L, coverFileId: Int = -1, fileId: Int = 0, durationSec: Int = 0,
        sizeBytes: Long = 0L, width: Int = 0, height: Int = 0, dateEpochSec: Long = 0L,
        parsedTitle: String? = null, itemTags: List<String>? = null,
    ) = ItemSide(
        caption, channelTitle, mimeType, albumId, coverFileId, fileId, durationSec,
        sizeBytes, width, height, dateEpochSec, parsedTitle, itemTags,
    )

    private fun metaSide(
        cursor: Long = 0L, exhausted: Boolean = false, imported: Boolean = false,
        mediaPass: Int = -1, needDeepen: Boolean = false, sweepCursor: Long = 0L,
        lastHeadRefreshTs: Long = 0L, sampled: Boolean = false, tier: Int = -1,
        dead: Boolean = false, ttlSec: Int? = 0, lastMsgSec: Long? = null,
    ) = MetaSide(
        cursor, exhausted, imported, mediaPass, needDeepen, sweepCursor,
        lastHeadRefreshTs, sampled, tier, dead, ttlSec, lastMsgSec,
    )

    // ---------------- 分类裁决 ----------------

    @Test
    fun `分类矩阵——key 命中优先于指纹`() {
        assertEquals(MergeAction.MERGE_ITEM, IndexMergePolicy.classify(true, false, 9, 0))
        assertEquals(MergeAction.MERGE_ITEM, IndexMergePolicy.classify(true, true, 0, 0))
        assertEquals(MergeAction.MERGE_REF, IndexMergePolicy.classify(false, true, 9, 0))
        assertEquals(MergeAction.BECOME_REF, IndexMergePolicy.classify(false, false, 9, 0))
        assertEquals(MergeAction.BECOME_REF, IndexMergePolicy.classify(false, false, 0, 7))
        assertEquals(MergeAction.INSERT_NEW, IndexMergePolicy.classify(false, false, 0, 0))
    }

    // ---------------- items 字段合并（链式非空胜） ----------------

    @Test
    fun `caption 空则对端胜且标记词位重算`() {
        val m = IndexMergePolicy.mergeItemFields(
            itemSide(caption = "", channelTitle = "本机频道"),
            itemSide(caption = "对端文案", channelTitle = "本机频道"),
        )
        assertEquals("对端文案", m.caption)
        assertTrue(m.captionSideChanged)
        assertTrue(m.changed)
    }

    @Test
    fun `caption 本机非空对端空则保本机且零动作面不标`() {
        val m = IndexMergePolicy.mergeItemFields(
            itemSide(caption = "本机文案", channelTitle = "t"),
            itemSide(caption = "", channelTitle = "t"),
        )
        assertEquals("本机文案", m.caption)
        assertFalse(m.captionSideChanged)
        assertFalse(m.changed)
    }

    @Test
    fun `caption 双非空异值取长者等长保本机`() {
        val longer = IndexMergePolicy.mergeItemFields(
            itemSide(caption = "短", channelTitle = "t"),
            itemSide(caption = "更长的对端文案", channelTitle = "t"),
        )
        assertEquals("更长的对端文案", longer.caption)
        assertTrue(longer.captionSideChanged)
        val tie = IndexMergePolicy.mergeItemFields(
            itemSide(caption = "甲乙", channelTitle = "t"),
            itemSide(caption = "丙丁", channelTitle = "t"),
        )
        assertEquals("甲乙", tie.caption)
        assertFalse(tie.changed)
    }

    @Test
    fun `数值字段零等于未知对端非零胜双非零保本机`() {
        val m = IndexMergePolicy.mergeItemFields(
            itemSide(caption = "c", albumId = 0L, coverFileId = -1, fileId = 0, sizeBytes = 0L, width = 0, dateEpochSec = 0L),
            itemSide(caption = "c", albumId = 777, coverFileId = 5, fileId = 9, sizeBytes = 12345, width = 640, dateEpochSec = 1700000000L),
        )
        assertEquals(777L, m.albumId)
        assertEquals(5, m.coverFileId)
        assertEquals(9, m.fileId)
        assertEquals(12345L, m.sizeBytes)
        assertEquals(640, m.width)
        assertEquals(1700000000L, m.dateEpochSec)
        assertTrue(m.changed)
        // 双非零异值保本机
        val keep = IndexMergePolicy.mergeItemFields(
            itemSide(caption = "c", sizeBytes = 100, dateEpochSec = 1700000000L),
            itemSide(caption = "c", sizeBytes = 200, dateEpochSec = 1700000001L),
        )
        assertEquals(100L, keep.sizeBytes)
        assertEquals(1700000000L, keep.dateEpochSec)
        assertFalse(keep.changed)
    }

    @Test
    fun `parsed_title 非空胜与 item_tags 并集`() {
        // 本机未解析 ← 对端已解析：随迁
        val adopt = IndexMergePolicy.mergeItemFields(
            itemSide(caption = "c", parsedTitle = null, itemTags = null),
            itemSide(caption = "c", parsedTitle = "标题", itemTags = listOf("a", "b")),
        )
        assertEquals("标题", adopt.parsedTitle)
        assertEquals(listOf("a", "b"), adopt.itemTags)
        assertTrue(adopt.tagsGrew)
        // 双侧已解析 → 并集（本机序先，对端补尾去重）
        val union = IndexMergePolicy.mergeItemFields(
            itemSide(caption = "c", parsedTitle = "本机标题", itemTags = listOf("a", "c")),
            itemSide(caption = "c", parsedTitle = "对端标题", itemTags = listOf("a", "b")),
        )
        assertEquals("本机标题", union.parsedTitle)
        assertEquals(listOf("a", "c", "b"), union.itemTags)
        assertTrue(union.tagsGrew)
        // 本机已解析对端未解析 → 保本机不标增长
        val keep = IndexMergePolicy.mergeItemFields(
            itemSide(caption = "c", itemTags = listOf("a")),
            itemSide(caption = "c", itemTags = null),
        )
        assertEquals(listOf("a"), keep.itemTags)
        assertFalse(keep.tagsGrew)
        // 双侧 null → null（未解析语义保留，不伪造已解析标记）
        assertNull(IndexMergePolicy.unionItemTags(null, null))
    }

    @Test
    fun `全同字段零动作`() {
        val same = itemSide(
            caption = "c", channelTitle = "t", albumId = 3, coverFileId = 2, fileId = 4,
            durationSec = 61, sizeBytes = 500, width = 640, height = 480,
            dateEpochSec = 1700000000L, parsedTitle = "p", itemTags = listOf("x"),
        )
        val m = IndexMergePolicy.mergeItemFields(same, itemSide(
            caption = "c", channelTitle = "t", albumId = 3, coverFileId = 2, fileId = 4,
            durationSec = 61, sizeBytes = 500, width = 640, height = 480,
            dateEpochSec = 1700000000L, parsedTitle = "p", itemTags = listOf("x"),
        ))
        assertFalse(m.changed)
        assertFalse(m.captionSideChanged)
        assertFalse(m.tagsGrew)
    }

    // ---------------- R12 跨设备内容一致性闸 ----------------

    @Test
    fun `内容一致性闸——类型必等大小零放行双非零必等`() {
        assertTrue(IndexMergePolicy.contentCompatible(0, 100, 0, 100))
        assertTrue(IndexMergePolicy.contentCompatible(0, 0, 0, 100)) // 一侧未知非冲突
        assertFalse(IndexMergePolicy.contentCompatible(0, 100, 1, 100)) // 类型分歧
        assertFalse(IndexMergePolicy.contentCompatible(0, 100, 0, 200)) // 大小真分歧
    }

    // ---------------- 游标并集覆盖裁决 ----------------

    @Test
    fun `游标一侧未开始取另一侧`() {
        assertEquals(1500L, IndexMergePolicy.mergeCursorUnion(0, 1500, 0, 2000, eitherExhausted = false))
        assertEquals(3000L, IndexMergePolicy.mergeCursorUnion(3000, 0, 9000, 0, eitherExhausted = false))
    }

    @Test
    fun `游标交叠区间取更深者`() {
        // A=[顶9000..3000]，B=[顶9500..1500]：交叠（3000≤9500 且 1500≤9000）→ min
        assertEquals(
            1500L,
            IndexMergePolicy.mergeCursorUnion(3000, 1500, localTopMsgId = 9000, incomingTopMsgId = 9500, eitherExhausted = false),
        )
    }

    @Test
    fun `游标脱节区间保守取大`() {
        // A=[顶9000..3000]，B=[顶12000..11000]：B 未探到 A 顶（11000>9000）→ 脱节 → max
        assertEquals(
            11000L,
            IndexMergePolicy.mergeCursorUnion(3000, 11000, localTopMsgId = 9000, incomingTopMsgId = 12000, eitherExhausted = false),
        )
    }

    @Test
    fun `游标任一侧到底取小免疫脱节`() {
        assertEquals(
            3000L,
            IndexMergePolicy.mergeCursorUnion(3000, 11000, localTopMsgId = 9000, incomingTopMsgId = 12000, eitherExhausted = true),
        )
    }

    // ---------------- meta 台账合并 ----------------

    @Test
    fun `meta 合并核心矩阵——配额协同`() {
        // A 爬到底（较深游标+完成态+刚刷过头），B 浅爬半途
        val m = IndexMergePolicy.mergeMeta(
            metaSide(cursor = 800, exhausted = true, mediaPass = 3, lastHeadRefreshTs = 1000, sweepCursor = 50),
            metaSide(cursor = 5000, exhausted = false, mediaPass = 1, lastHeadRefreshTs = 500, sweepCursor = 5000),
            localTopMsgId = 9000, incomingTopMsgId = 9000,
        )
        assertEquals(800L, m.cursor) // 到底免疫取小
        assertEquals(50L, m.sweepCursor)
        assertTrue(m.exhausted) // OR：爬过的不重爬
        assertEquals(3, m.mediaPass) // max 相位
        assertEquals(1000L, m.lastHeadRefreshTs) // max：两地不重复烧头刷
    }

    @Test
    fun `meta 新行档随迁与 tier 本机优先`() {
        // 本机行在：tier 恒保本机（B4 政策产物不随包走）
        val m = IndexMergePolicy.mergeMeta(
            metaSide(cursor = 100, tier = 2),
            metaSide(cursor = 200, tier = 0),
            localTopMsgId = 500, incomingTopMsgId = 500,
        )
        assertEquals(2, m.tier)
    }

    @Test
    fun `meta 触知型标记——死档与待加深`() {
        // 双碰：dead=AND（一侧爬出内容即非死档）
        assertFalse(
            IndexMergePolicy.mergeMeta(
                metaSide(cursor = 10, dead = true),
                metaSide(cursor = 20, dead = false),
                100, 100,
            ).dead,
        )
        assertTrue(
            IndexMergePolicy.mergeMeta(
                metaSide(cursor = 10, dead = true),
                metaSide(cursor = 20, dead = true),
                100, 100,
            ).dead,
        )
        // 单碰：取碰过侧（对端全零未碰 → 死档标记随迁）
        assertTrue(
            IndexMergePolicy.mergeMeta(
                metaSide(), // 未碰过
                metaSide(cursor = 20, dead = true),
                0, 100,
            ).dead,
        )
        // needDeepen：双碰 AND（一侧已加深即清）
        assertFalse(
            IndexMergePolicy.mergeMeta(
                metaSide(cursor = 10, needDeepen = true),
                metaSide(cursor = 20, needDeepen = false),
                100, 100,
            ).needDeepen,
        )
        // 单碰：碰过侧的待加深标记随迁
        assertTrue(
            IndexMergePolicy.mergeMeta(
                metaSide(),
                metaSide(cursor = 20, needDeepen = true),
                0, 100,
            ).needDeepen,
        )
    }

    @Test
    fun `meta 其余字段——sampled与imported并集 ttlSec保守取大`() {
        val m = IndexMergePolicy.mergeMeta(
            metaSide(cursor = 10, sampled = true, imported = false, ttlSec = 0),
            metaSide(cursor = 20, sampled = false, imported = true, ttlSec = 86400),
            100, 100,
        )
        assertTrue(m.sampled)
        assertTrue(m.imported)
        assertEquals(86400, m.ttlSec)
        // 源库无 ttlSec 列（null）→ 保本机
        val noTtl = IndexMergePolicy.mergeMeta(
            metaSide(cursor = 10, ttlSec = 3600),
            metaSide(cursor = 20, ttlSec = null),
            100, 100,
        )
        assertEquals(3600, noTtl.ttlSec)
    }

    @Test
    fun `lastMsgSec 最新胜与 v12 互导列缺席直通`() {
        // R286 v13 新列（架构师转达）：max=最新胜
        val m = IndexMergePolicy.mergeMeta(
            metaSide(cursor = 10, lastMsgSec = 1700000000L),
            metaSide(cursor = 20, lastMsgSec = 1700005000L),
            100, 100,
        )
        assertEquals(1700005000L, m.lastMsgSec)
        // 单侧缺列（v12 包导入 v13 本机）：取有值侧
        val oneSide = IndexMergePolicy.mergeMeta(
            metaSide(cursor = 10, lastMsgSec = null),
            metaSide(cursor = 20, lastMsgSec = 1700005000L),
            100, 100,
        )
        assertEquals(1700005000L, oneSide.lastMsgSec)
        // 双侧缺列（v12 互导）：null 直通（执行侧不写该列）
        assertNull(
            IndexMergePolicy.mergeMeta(metaSide(cursor = 10), metaSide(cursor = 20), 100, 100).lastMsgSec,
        )
    }

    @Test
    fun `触知判定口径`() {
        assertFalse(IndexMergePolicy.touched(metaSide()))
        assertTrue(IndexMergePolicy.touched(metaSide(cursor = 1)))
        assertTrue(IndexMergePolicy.touched(metaSide(mediaPass = 0)))
        assertTrue(IndexMergePolicy.touched(metaSide(exhausted = true)))
        assertTrue(IndexMergePolicy.touched(metaSide(sampled = true)))
    }

    @Test
    fun `refs 指纹点查 SQL 形状锚——列名是 fuid 不是 fileUniqueId`() {
        // R287 真机实锤（16416 SQLITE_ERROR）：repost_refs 的指纹列=fuid；
        // const val 编译期内联，本锚零 Android 类加载
        val sql = IndexImportMerge.REFS_FUID_LOOKUP_SQL
        assertTrue(sql.contains("repost_refs"))
        assertTrue(sql.contains("fuid=?"))
        assertFalse(sql.contains("fileUniqueId"))
    }
}
