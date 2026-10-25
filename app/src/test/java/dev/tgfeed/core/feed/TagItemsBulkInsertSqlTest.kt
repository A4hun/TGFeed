package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R260 实锤坏 SQL 的回归锚（DeepSearchIndex.tagItemsBulkInsertSql 纯函数）：
 * R255 W11 曾把 N 元组拼进同一对括号（`VALUES(?,?,?,?,?,?)`）——SQLite 报
 * 「N×3 values for 3 columns」，≥2 标签的批静默整页丢失且一致性对账双缺不可见。
 * 本组锚定正确形状：括号组数 = 元组数、每元组自带括号、占位符总数 = 3×元组数。
 */
class TagItemsBulkInsertSqlTest {

    @Test
    fun `单元组_单括号组`() {
        val sql = tagItemsBulkInsertSql(1)
        assertEquals(
            "INSERT OR IGNORE INTO tag_items(tag, rowid_ref, chatId) VALUES (?,?,?)",
            sql,
        )
    }

    @Test
    fun `多元组_每元组自带括号`() {
        val sql = tagItemsBulkInsertSql(3)
        assertTrue(sql.endsWith("VALUES (?,?,?),(?,?,?),(?,?,?)"))
        // 括号组数 = 元组数（旧坏 SQL 正是 1 组括号塞 9 个占位符）
        assertEquals(3, Regex("\\(\\?,\\?,\\?\\)").findAll(sql).count())
    }

    @Test
    fun `占位符总数等于三倍元组数`() {
        for (n in listOf(1, 2, 7, 300)) {
            val sql = tagItemsBulkInsertSql(n)
            val placeholders = sql.count { it == '?' }
            assertEquals(3 * n, placeholders)
        }
    }

    @Test
    fun `封顶元组不越 999 占位符线`() {
        // SQLITE_MAX_VARIABLE_NUMBER=999：300 元组×3=900 ≤999（R255 W11 口径保持）
        val sql = tagItemsBulkInsertSql(300)
        assertTrue(sql.count { it == '?' } <= 999)
    }

    @Test
    fun `拒绝零元组`() {
        try {
            tagItemsBulkInsertSql(0)
            org.junit.Assert.fail("0 元组必须拒")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `无嵌套括号坏形状`() {
        // 旧坏形状的特征：VALUES( 后紧跟裸占位符串（元组不自带括号）
        val sql = tagItemsBulkInsertSql(2)
        assertFalse(sql.contains("VALUES(?,?"))
        assertFalse(sql.contains("VALUES(?,?,?"))
    }
}
