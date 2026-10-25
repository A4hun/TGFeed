package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R197 群选择器行装配（[buildHarvestGroupRows]）单测：用户裁决「手动选，不替他筛选」
 * 口径——关键词只作建议角标、列表不预过滤、频道例外、旧勾选项不消失、排序语义。
 */
class HarvestGroupPickerTest {

    private fun cand(id: Long, title: String, isChannel: Boolean = false, members: Int = 0) =
        HarvestGroupCandidate(id, title, isChannel, members)

    private fun ent(id: Long, src: String = "user", title: String = "群$id") =
        SearchEntity(id, SearchEntity.KIND_GROUP, title, src)

    @Test
    fun `全部已加入群进列表_关键词不筛_只是角标`() {
        // 用户裁决核心：没有任何建议命中的普通群照常在列（不预过滤）
        val rows = buildHarvestGroupRows(
            candidates = listOf(cand(1, "闲聊群"), cand(2, "家庭群"), cand(3, "同学群")),
            selected = emptyList(),
            detected = emptyList(),
        )
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.chatId })
        assertTrue(rows.none { it.suggested })
        assertTrue(rows.none { it.selected })
    }

    @Test
    fun `频道默认不列_建议或已勾的频道保留_旧勾项不消失`() {
        val rows = buildHarvestGroupRows(
            candidates = listOf(
                cand(1, "普通频道", isChannel = true),   // 频道：无建议无勾选 → 不列
                cand(2, "资源搜索频道", isChannel = true), // 频道：标题命中建议 → 保留
                cand(3, "已勾的频道", isChannel = true),   // 频道：已勾选 → 保留（旧卡勾过）
                cand(4, "普通群"),                          // 群：恒保留
            ),
            selected = listOf(ent(3)),
            detected = emptyList(),
            titleSuggests = { "搜索" in it },
        )
        assertEquals(listOf(3L, 2L, 4L), rows.map { it.chatId }) // 已勾(3)最前，建议(2)次之，普通群(4)其后
        assertFalse(rows.any { it.chatId == 1L })               // 无勾无建议的频道不列
    }

    @Test
    fun `建议集_自动发现缓存命中_∪_群名规则现算命中`() {
        val rows = buildHarvestGroupRows(
            candidates = listOf(
                cand(1, "超级资源群"),            // 现算命中（缓存没扫到的新群）
                cand(2, "缓存检测过的群"),         // 缓存命中（src=desc）
                cand(3, "普通群"),
            ),
            selected = emptyList(),
            // 缓存只检出了 2（desc 口径=群简介玩法说明）
            detected = listOf(ent(2, src = "desc")),
            titleSuggests = { "资源" in it },
        )
        assertTrue(rows.first { it.chatId == 1L }.suggested)
        assertEquals("name", rows.first { it.chatId == 1L }.suggestedSrc) // 现算命中归 name 口径
        assertTrue(rows.first { it.chatId == 2L }.suggested)
        assertEquals("desc", rows.first { it.chatId == 2L }.suggestedSrc) // 缓存 src 原样透传（写回溯源用）
        assertFalse(rows.first { it.chatId == 3L }.suggested)
    }

    @Test
    fun `排序_已勾最前_建议次之_其余按枚举序_同档稳定`() {
        val rows = buildHarvestGroupRows(
            candidates = listOf(
                cand(1, "群1"),            // 普通
                cand(2, "资源群2"),         // 建议
                cand(3, "群3"),            // 已勾
                cand(4, "资源群4"),         // 建议+已勾 → 已勾档（角标照显）
                cand(5, "群5"),            // 普通
            ),
            selected = listOf(ent(3), ent(4)),
            detected = emptyList(),
            titleSuggests = { "资源" in it },
        )
        assertEquals(listOf(3L, 4L, 2L, 1L, 5L), rows.map { it.chatId })
        // 建议+已勾同行共存：勾选档里角标照常（“已勾且像搜索群”是最强状态，不互斥）
        assertTrue(rows.first { it.chatId == 4L }.selected)
        assertTrue(rows.first { it.chatId == 4L }.suggested)
    }

    @Test
    fun `已勾选但枚举缺席_追加占位行_可取消_不产生幽灵勾选`() {
        val rows = buildHarvestGroupRows(
            candidates = listOf(cand(1, "群1")),
            selected = listOf(ent(1), ent(99, title = "退掉的群")),
            detected = emptyList(),
        )
        assertEquals(2, rows.size)
        val ghost = rows.first { it.chatId == 99L }
        assertTrue(ghost.selected)
        assertTrue(ghost.missingFromList)      // UI 标「不在群列表」
        assertEquals("退掉的群", ghost.title)  // 标题从勾选实体兜底
        assertFalse(rows.first { it.chatId == 1L }.missingFromList)
    }

    @Test
    fun `成员数与标题透传`() {
        val rows = buildHarvestGroupRows(
            candidates = listOf(cand(7, "大群", members = 234_567)),
            selected = emptyList(),
            detected = emptyList(),
        )
        val row = rows.single()
        assertEquals("大群", row.title)
        assertEquals(234_567, row.memberCount)
    }

    @Test
    fun `脏数据防御_chatId为0的勾选实体跳过_全空输入空表`() {
        // chatId=0 是 setUserGroupSelected 已拒写的非法值，读侧再防一层
        val rows = buildHarvestGroupRows(
            candidates = listOf(cand(0, "坏行")),
            selected = listOf(ent(0)),
            detected = listOf(ent(0, src = "name")),
        )
        assertTrue(rows.isEmpty())
        assertEquals(0, buildHarvestGroupRows(emptyList(), emptyList(), emptyList()).size)
    }
}
