package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R269 行星内容流分页机（TagContentPager）单测：
 * 懒召回仅一次 / 按页切片顺序保持 / 终页游标 null / reset 重放不重复召回 /
 * 空召回即终页 / limit≤0 防御钳 1。
 */
class TagContentFeedSourceTest {

    private fun item(i: Int) = VideoItem(
        key = "k$i",
        chatId = 100L + i,
        messageId = i.toLong(),
        channelTitle = "频道代称$i",
        caption = "内容$i",
        durationSec = 10,
        width = 720,
        height = 1280,
        fileId = -1,
        sizeBytes = 1024L,
        playUri = null,
        dateEpochSec = 1_700_000_000 - i, // 时间倒序（召回口径）
    )

    private fun pagerOf(count: Int, fallback: Boolean = false): Pair<TagContentPager, () -> Unit> {
        val items = (0 until count).map { item(it) }
        val pager = TagContentPager { TagContentRecall(items, likeFallback = fallback) }
        return pager to {}
    }

    @Test
    fun `懒召回——构造不召回 首次取页才召回且仅一次`() {
        val (pager, _) = pagerOf(3)
        assertEquals(0, pager.recallCount)
        pager.nextPage(2)
        assertEquals(1, pager.recallCount)
        pager.nextPage(2)
        pager.nextPage(2)
        assertEquals(1, pager.recallCount) // 后续翻页不重复召回
    }

    @Test
    fun `按页切片——顺序保持 终页游标 null 终页后空页`() {
        val (pager, _) = pagerOf(5)
        val p1 = pager.nextPage(2)
        assertEquals(listOf("k0", "k1"), p1.items.map { it.key })
        assertNotNull(p1.nextCursor)
        val p2 = pager.nextPage(2)
        assertEquals(listOf("k2", "k3"), p2.items.map { it.key })
        assertNotNull(p2.nextCursor)
        val p3 = pager.nextPage(2)
        assertEquals(listOf("k4"), p3.items.map { it.key })
        assertNull(p3.nextCursor) // 发尽即终页
        val p4 = pager.nextPage(2)
        assertTrue(p4.items.isEmpty())
        assertNull(p4.nextCursor) // 终页后恒空页（不悬空游标）
    }

    @Test
    fun `reset 重放同一批——游标归零 召回不重启`() {
        val (pager, _) = pagerOf(4)
        pager.nextPage(2)
        pager.nextPage(2)
        assertEquals(1, pager.recallCount)
        pager.reset()
        val p1 = pager.nextPage(2)
        assertEquals(listOf("k0", "k1"), p1.items.map { it.key })
        assertEquals(1, pager.recallCount) // 重放不白烧第二轮召回（45s LIKE 兜底保护）
    }

    @Test
    fun `空召回——首页即终页`() {
        val (pager, _) = pagerOf(0)
        val page = pager.nextPage(20)
        assertTrue(page.items.isEmpty())
        assertNull(page.nextCursor)
        assertEquals(1, pager.recallCount)
    }

    @Test
    fun `limit 防御——0 与负数钳为一页一条`() {
        val (pager, _) = pagerOf(2)
        val p1 = pager.nextPage(0)
        assertEquals(1, p1.items.size)
        assertNotNull(p1.nextCursor)
        val p2 = pager.nextPage(-5)
        assertEquals(1, p2.items.size)
        assertNull(p2.nextCursor)
    }

    @Test
    fun `召回语义——likeFallback 标志透传（R267 诚实口径）`() {
        // pager 透传召回结果不吞标志：直接断言数据类语义（宿主日志/文案靠它）
        val recalled = TagContentRecall(listOf(item(0)), likeFallback = true)
        assertTrue(recalled.likeFallback)
        assertEquals(1, recalled.items.size)
    }
}
