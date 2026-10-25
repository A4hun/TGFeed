package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IndexCompactPolicy]（R294 P3 物理压实策略纯函数件）的行为单测：
 * 压实闸矩阵、拷贝计划（显式保 rowid）、文件命名/作用域红线、对账 SQL 形状、
 * **列清单与 CREATE_*_SQL 正则对拍反射锚**（防两处手写漂移=压实写错列集）。
 *
 * 关键行为：**裸 VACUUM 成文红线的唯一合法替代**——显式保 rowid 重建换装
 * （storage-merge-design §6.3：items 非 IPK 表，VACUUM 重排 rowid 团灭
 * tag_items/refs.canonRowid/fts 对齐）。
 */
class IndexCompactPolicyTest {

    // ---------------- 压实闸矩阵 ----------------

    @Test
    fun `压实闸_自由页双条件矩阵`() {
        // 行为：自由字节 ≥64MB 且占比 ≥10% 才值得压（小碎片靠 freelist 自然复用）
        val mb = 1024L * 1024
        assertTrue(IndexCompactPolicy.shouldCompact(64 * mb, 600 * mb)) // 双条件恰好达标
        assertTrue(IndexCompactPolicy.shouldCompact(600 * mb, 6000 * mb)) // 10% 整
        assertFalse(IndexCompactPolicy.shouldCompact(63 * mb, 600 * mb)) // 字节不足
        assertFalse(IndexCompactPolicy.shouldCompact(64 * mb, 1000 * mb)) // 占比 6.4% 不足
        assertFalse(IndexCompactPolicy.shouldCompact(0L, 0L)) // 空库直通
        assertFalse(IndexCompactPolicy.shouldCompact(100 * mb, 0L)) // 总量 0 防御
    }

    @Test
    fun `磁盘闸_余量一倍一红线`() {
        // 行为：可用 ≥ 净数据 ×1.1 才开压（暂存库与主库瞬态双占）
        assertTrue(IndexCompactPolicy.enoughDisk(1100L, 1000L))
        assertFalse(IndexCompactPolicy.enoughDisk(1099L, 1000L))
        assertFalse(IndexCompactPolicy.enoughDisk(0L, 1L))
        assertTrue(IndexCompactPolicy.enoughDisk(0L, 0L)) // 空库零需求直通
    }

    @Test
    fun `闸常量锚_64MB与一成与一倍一`() {
        assertEquals(64L * 1024 * 1024, IndexCompactPolicy.MIN_COMPACT_FREE_BYTES)
        assertEquals(0.10, IndexCompactPolicy.MIN_COMPACT_FREE_RATIO, 1e-9)
        assertEquals(1.1, IndexCompactPolicy.DISK_MARGIN, 1e-9)
    }

    // ---------------- 拷贝计划 ----------------

    @Test
    fun `拷贝计划_表序与形态门`() {
        // 全形态（LIKE+refs+ocr）：六表全在，items 带头（大头先行）且带 tokens
        val full = IndexCompactPolicy.copyPlan(sourceHasTokens = true, hasReposts = true, hasItemOcr = true)
        assertEquals(listOf("items", "meta", "tags", "tag_items", "repost_refs", "item_ocr"), full.map { it.table })
        assertTrue(full[0].columns.contains("tokens"))
        assertEquals(19, full[0].columns.size) // 18 基础列 + tokens
        // FTS5 形态：items 无 tokens 列
        val fts5 = IndexCompactPolicy.copyPlan(sourceHasTokens = false, hasReposts = true, hasItemOcr = true)
        assertFalse(fts5[0].columns.contains("tokens"))
        assertEquals(18, fts5[0].columns.size)
        // 老库直通：v11 前无 refs、v14 前无 item_ocr
        val old = IndexCompactPolicy.copyPlan(sourceHasTokens = true, hasReposts = false, hasItemOcr = false)
        assertEquals(listOf("items", "meta", "tags", "tag_items"), old.map { it.table })
    }

    @Test
    fun `拷贝SQL_rowid恒首位显式保`() {
        // 行为：rowid 显式随行（非 IPK 表压实红线——裸 VACUUM 重排团灭对齐链）
        val sql = IndexCompactPolicy.copySql(IndexCompactPolicy.TableCopyPlan("tag_items", listOf("tag", "rowid_ref", "chatId")))
        assertEquals(
            "INSERT INTO tag_items(rowid,tag,rowid_ref,chatId) SELECT rowid,tag,rowid_ref,chatId FROM src.tag_items",
            sql,
        )
        // 源 schema 可换（彩排/换装共用一口径）
        val bare = IndexCompactPolicy.copySql(IndexCompactPolicy.TableCopyPlan("meta", listOf("chatId")), sourceSchema = "main")
        assertTrue(bare.endsWith("FROM main.meta"))
    }

    // ---------------- 文件命名与作用域红线 ----------------

    @Test
    fun `压实作用域锚_用户数据绝不波及`() {
        // 行为：只放 search.db 三件套+compact-tmp 暂存+precompact 备份（各含 -wal/-shm）；
        // 收藏/历史/设置/成品缓存/登录态/自愈备份件恒 false（红线锚）
        for (ok in listOf(
            "search.db", "search.db-wal", "search.db-shm",
            "deepindex/search.db",
            "search.db.compact-tmp", "search.db.compact-tmp-wal", "search.db.compact-tmp-shm",
            "search.db.precompact", "search.db.precompact-wal", "search.db.precompact-shm",
        )) {
            assertTrue("应在压实域内：$ok", IndexCompactPolicy.isCompactScopePath(ok))
        }
        for (no in listOf(
            "prefs/settings.json", "store/localstore.bin", "galaxy/taste_map_cache_v2.bin",
            "tdlib/db.sqlite", "index_heal_guard.json",
            "search.db.corrupt-20261021-131500", // 自愈备份归 R289 域，压实绝不碰
            "search.db2", "search.db.bak", "",
        )) {
            assertFalse("绝不在压实域内：$no", IndexCompactPolicy.isCompactScopePath(no))
        }
    }

    @Test
    fun `文件命名锚_暂存与备份恒定名`() {
        // 行为：名改动=清理/换装路径全线漂移，锚死
        assertEquals("search.db.compact-tmp", IndexCompactPolicy.COMPACT_STAGING_NAME)
        assertEquals("search.db.precompact", IndexCompactPolicy.PRECOMPACT_BACKUP_NAME)
    }

    // ---------------- 对账 SQL 形状锚 ----------------

    @Test
    fun `对账SQL形状_行集指纹三元组与悬空点查`() {
        val chk = IndexCompactPolicy.tableChecksumSql("items", "src.")
        assertEquals("SELECT COUNT(*), COALESCE(SUM(rowid),0), COALESCE(MAX(rowid),0) FROM src.items", chk)
        val dang = IndexCompactPolicy.danglingCountSql("tag_items", "rowid_ref")
        // 形状：LEFT JOIN 主键点查（非笛卡尔）+ 双表同 schema 前缀
        assertTrue(dang.startsWith("SELECT COUNT(*) FROM tag_items LEFT JOIN items"))
        assertTrue(dang.contains("ON items.rowid = tag_items.rowid_ref"))
        assertTrue(dang.endsWith("WHERE items.rowid IS NULL"))
        val dangSrc = IndexCompactPolicy.danglingCountSql("repost_refs", "canonRowid", "src.")
        assertTrue(dangSrc.contains("FROM src.repost_refs LEFT JOIN src.items"))
        assertTrue(dangSrc.contains("WHERE src.items.rowid IS NULL"))
    }

    // ---------------- 反射锚：列清单与 CREATE_*_SQL 对拍（防两处手写漂移） ----------------

    /** CREATE TABLE DDL 列名解析（测试件）：顶层逗号切段（括号深度跟踪），跳约束段取首词 */
    private fun parseCreateTableColumns(sql: String): List<String> {
        val body = sql.substringAfter('(').substringBeforeLast(')')
        val segments = ArrayList<String>()
        var depth = 0
        var cur = StringBuilder()
        for (ch in body) {
            when (ch) {
                '(' -> { depth++; cur.append(ch) }
                ')' -> { depth--; cur.append(ch) }
                ',' -> if (depth == 0) { segments += cur.toString(); cur = StringBuilder() } else cur.append(ch)
                else -> cur.append(ch)
            }
        }
        segments += cur.toString()
        return segments.mapNotNull { seg ->
            val first = seg.trim().substringBefore(' ').trim()
            if (first.uppercase(java.util.Locale.US) in listOf("PRIMARY", "UNIQUE", "FOREIGN", "CHECK", "CONSTRAINT")) {
                null
            } else {
                first
            }
        }
    }

    private fun createSqlOf(field: String): String =
        DeepSearchIndex::class.java.getDeclaredField(field).apply { isAccessible = true }.get(null) as String

    @Test
    fun `列清单反射锚_与建表DDL逐字段一致`() {
        // 行为：压实拷贝列清单（IndexCompactPolicy）== 建表 DDL 列序（DeepSearchIndex
        // CREATE_*_SQL）——两处手写漂移=压实写错列集=静默数据错位，本锚强制同步
        val pairs = listOf(
            Triple("CREATE_ITEMS_SQL", IndexCompactPolicy.ITEMS_COLUMNS, "items"),
            Triple("CREATE_META_SQL", IndexCompactPolicy.META_COLUMNS, "meta"),
            Triple("CREATE_TAGS_SQL", IndexCompactPolicy.TAGS_COLUMNS, "tags"),
            Triple("CREATE_TAG_ITEMS_SQL", IndexCompactPolicy.TAG_ITEMS_COLUMNS, "tag_items"),
            Triple("CREATE_REPOSTS_SQL", IndexCompactPolicy.REPOST_REFS_COLUMNS, "repost_refs"),
            Triple("CREATE_ITEM_OCR_SQL", IndexCompactPolicy.ITEM_OCR_COLUMNS, "item_ocr"),
        )
        for ((field, expected, table) in pairs) {
            val ddl = createSqlOf(field)
            val ddlCols = parseCreateTableColumns(ddl)
            assertEquals("$field 列序与压实列清单一致", ddlCols, expected)
            assertTrue(
                "$field 表名与压实计划一致（$table）",
                ddl.contains("IF NOT EXISTS $table("),
            )
        }
        // 拷贝计划的表名全集=DDL 锚的表名全集（字面量漂移同锚锁死）
        assertEquals(
            listOf("items", "meta", "tags", "tag_items", "repost_refs", "item_ocr"),
            IndexCompactPolicy.copyPlan(sourceHasTokens = true, hasReposts = true, hasItemOcr = true).map { it.table },
        )
        // items 是 18 列非 IPK 表（rowid 不在列清单=拷贝 SQL 显式补首位的锚）
        assertEquals(18, IndexCompactPolicy.ITEMS_COLUMNS.size)
        assertFalse("items 列清单不含 rowid（copySql 恒补首位）", IndexCompactPolicy.ITEMS_COLUMNS.contains("rowid"))
        // item_ocr 的 rowid 是真实列（INTEGER PRIMARY KEY）——必须在清单内随行拷
        assertEquals("rowid", IndexCompactPolicy.ITEM_OCR_COLUMNS.first())
    }
}
