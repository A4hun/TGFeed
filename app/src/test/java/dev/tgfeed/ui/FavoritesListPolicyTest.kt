package dev.tgfeed.ui

import dev.tgfeed.core.td.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R199 收藏列表纯策略单测：按频道分组（组序=组内最新收藏首现序、组内保序、
 * 改名取最新标题、空名回退）+ 计数徽章格式。
 * 前置条件锚定：输入列表一律按收藏时间倒序构造（新收藏在前，模拟数据层不变量）。
 */
class FavoritesListPolicyTest {

    /** 最小 VideoItem 构造（收藏墙只消费 key/chatId/channelTitle，其余占位） */
    private fun item(key: String, chatId: Long, title: String): VideoItem = VideoItem(
        key = key,
        chatId = chatId,
        messageId = 0L,
        channelTitle = title,
        caption = "",
        durationSec = 0,
        width = 0,
        height = 0,
        fileId = -1,
        sizeBytes = 0,
        playUri = null,
    )

    @Test
    fun `空列表返回空`() {
        assertEquals(emptyList<FavoritesListRow>(), groupFavoritesByChannel(emptyList()))
    }

    @Test
    fun `单频道无交错_一组_组内保持原序`() {
        val items = listOf(
            item("a", 1L, "频道A"),
            item("b", 1L, "频道A"),
            item("c", 1L, "频道A"),
        )
        val rows = groupFavoritesByChannel(items)
        // 1 个组头 + 3 条目，组内 a→b→c（收藏时间倒序原样）
        assertEquals(
            listOf(
                FavoritesListRow.Header(1L, "频道A", 3),
                FavoritesListRow.Entry(items[0]),
                FavoritesListRow.Entry(items[1]),
                FavoritesListRow.Entry(items[2]),
            ),
            rows,
        )
    }

    @Test
    fun `多频道交错_组序按最新收藏首现序`() {
        // 收藏时间倒序：先收的是频道B的z，随后频道A的x、再频道B的y、最后频道A的w（最新）
        val items = listOf(
            item("w", 1L, "频道A"),
            item("y", 2L, "频道B"),
            item("x", 1L, "频道A"),
            item("z", 2L, "频道B"),
        )
        val rows = groupFavoritesByChannel(items)
        // 首现序：频道A（w 最新）在前，频道B（y 第二新）在后；组内各自倒序
        assertEquals(
            listOf(
                FavoritesListRow.Header(1L, "频道A", 2),
                FavoritesListRow.Entry(items[0]), // w
                FavoritesListRow.Entry(items[2]), // x
                FavoritesListRow.Header(2L, "频道B", 2),
                FavoritesListRow.Entry(items[1]), // y
                FavoritesListRow.Entry(items[3]), // z
            ),
            rows,
        )
    }

    @Test
    fun `频道改名_组头取组内最新的标题`() {
        // 同一 chatId 前后两条标题不同：组头用最新一条（列表头部）的名字
        val items = listOf(
            item("new", 7L, "新名字"),
            item("old", 7L, "旧名字"),
        )
        val rows = groupFavoritesByChannel(items)
        val header = rows.filterIsInstance<FavoritesListRow.Header>().single()
        assertEquals("新名字", header.title)
        assertEquals(2, header.count)
    }

    @Test
    fun `频道名为空_组头回退未命名频道`() {
        val items = listOf(item("a", 9L, ""))
        val rows = groupFavoritesByChannel(items)
        assertEquals(
            listOf(FavoritesListRow.Header(9L, "未命名频道", 1), FavoritesListRow.Entry(items[0])),
            rows,
        )
    }

    @Test
    fun `不同频道同标题_按chatId分组不合并`() {
        // 两个不同频道恰好重名：组按 chatId 划分（不是按标题），各出各的组头
        val items = listOf(
            item("a", 1L, "同名"),
            item("b", 2L, "同名"),
        )
        val headers = groupFavoritesByChannel(items).filterIsInstance<FavoritesListRow.Header>()
        assertEquals(listOf(1L, 2L), headers.map { it.chatId })
        assertEquals(2, headers.size)
    }

    @Test
    fun `计数徽章_999以内原样`() {
        assertEquals("0", formatFavoritesCount(0))
        assertEquals("1", formatFavoritesCount(1))
        assertEquals("999", formatFavoritesCount(999))
    }

    @Test
    fun `计数徽章_超999封顶`() {
        assertEquals("999+", formatFavoritesCount(1000))
        assertEquals("999+", formatFavoritesCount(98765))
    }
}
