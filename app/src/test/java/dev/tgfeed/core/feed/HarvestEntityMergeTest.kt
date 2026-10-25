package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R183 宝藏二·收割实体三源合并（[mergeHarvestEntities]）单测：
 * 手动清单 → 用户勾选群 → 自动发现的优先级与去重语义（勾选即生效/知情可控口径）。
 */
class HarvestEntityMergeTest {

    private fun ent(id: Long, kind: Int = SearchEntity.KIND_GROUP, src: String = "name") =
        SearchEntity(id, kind, "群$id", src)

    @Test
    fun `三源并一_前源优先_chatId去重`() {
        val out = mergeHarvestEntities(
            manualIds = listOf(1L),
            userSelected = listOf(ent(2L, src = "user")),
            auto = listOf(ent(3L), ent(1L)), // 1 已在手动（沿用自动 kind），3 只在 auto
        )
        assertEquals(3, out.size)
        assertEquals("manual", out.first { it.chatId == 1L }.src) // 手动覆盖 src 归属
        assertEquals("user", out.first { it.chatId == 2L }.src)
        assertEquals("name", out.first { it.chatId == 3L }.src)
    }

    @Test
    fun `手动id命中自动清单沿用自动侧kind_bot私聊手填也按私聊收`() {
        val out = mergeHarvestEntities(
            manualIds = listOf(9L),
            userSelected = emptyList(),
            auto = listOf(SearchEntity(9L, SearchEntity.KIND_BOT, "@jisou", "botconfig")),
        )
        assertEquals(SearchEntity.KIND_BOT, out.single().kind) // 旧语义：kind 沿用自动侧
        assertEquals("manual", out.single().src) // src 归手动
    }

    @Test
    fun `用户勾选群与自动发现重叠时勾选实体胜出_零依赖自动发现开关`() {
        // 勾选语义独立于自动发现：就算 auto 为空（开关关），勾选群照常进清单
        val out = mergeHarvestEntities(
            manualIds = emptyList(),
            userSelected = listOf(ent(5L, src = "user")),
            auto = emptyList(),
        )
        assertEquals(listOf(5L), out.map { it.chatId })
        // 重叠：勾选（前源）胜出——去重不产生重复实体
        val overlap = mergeHarvestEntities(
            manualIds = emptyList(),
            userSelected = listOf(ent(5L, src = "user")),
            auto = listOf(ent(5L, src = "name")),
        )
        assertEquals(1, overlap.size)
        assertEquals("user", overlap.single().src)
    }

    @Test
    fun `全空输入返回空表`() {
        assertEquals(0, mergeHarvestEntities(emptyList(), emptyList(), emptyList()).size)
    }
}
