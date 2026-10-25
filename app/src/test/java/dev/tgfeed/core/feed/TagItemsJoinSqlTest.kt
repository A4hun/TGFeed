package dev.tgfeed.core.feed

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R269 tagItems JOIN 查询 SQL 形状回归锚（真机实锤回归：R236 修正刀 27dd5a8 误删
 * FROM 子句 → 「near "JOIN": syntax error」被 runCatching 吞成全设备 100% 空召回，
 * R222 时间轴/R234 传播带/R269 内容流三面同病）——形状断言锁死 FROM/JOIN 结构。
 */
class TagItemsJoinSqlTest {

    private val sql = tagItemsJoinSql("items.key,items.chatId")

    @Test
    fun `必须含 FROM items JOIN tag_items 双表结构（27dd5a8 误删 FROM 的回归锚）`() {
        assertTrue(sql.contains("FROM items JOIN tag_items"))
    }

    @Test
    fun `JOIN 条件=tag_items 倒排 rowid 回表`() {
        assertTrue(sql.contains("ON items.rowid = tag_items.rowid_ref"))
    }

    @Test
    fun `tag 谓词+时间倒序+分页占位三件套`() {
        assertTrue(sql.contains("WHERE tag_items.tag=?"))
        assertTrue(sql.contains("ORDER BY items.dateEpochSec DESC"))
        assertTrue(sql.contains("LIMIT ?"))
    }

    @Test
    fun `列清单由调用方透传（companion 私有派生清单的真实接线位）`() {
        assertTrue(sql.startsWith("SELECT items.key,items.chatId FROM"))
    }
}
