package dev.tgfeed.core.feed

import dev.tgfeed.core.td.FeedPage
import dev.tgfeed.core.td.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R212 频道主页分页决策单测：失败≠到底、网络优先/索引兜底、到底判定口径。
 * FeedPage/VideoItem 只是决策输入的载体，用最小桩构造（key/messageId 足够）。
 */
class ChannelHomePagingTest {

    private fun item(id: Long) = VideoItem(
        key = "tg:-1001001001:$id", chatId = -1001001001L, messageId = id,
        channelTitle = "", caption = "", durationSec = 0, width = 0, height = 0,
        fileId = -1, sizeBytes = 0L, playUri = null, coverBytes = null,
        dateEpochSec = 0, fileUniqueId = "", mediaType = 0,
    )

    @Test
    fun `网络页超时或异常_失败轮_不判到底不并网络_请求索引兜底`() {
        val d = ChannelHomePaging.decide(null)
        assertFalse(d.mergeNet)
        assertFalse(d.netDone)
        assertTrue(d.fetchIndex)
    }

    @Test
    fun `网络页有条目_正常轮_只并网络_索引偏移不动`() {
        val d = ChannelHomePaging.decide(FeedPage(listOf(item(1)), "42"))
        assertTrue(d.mergeNet)
        assertFalse(d.fetchIndex)
        assertFalse(d.netDone)
    }

    @Test
    fun `网络页有条目且游标空_正常轮_网络到底`() {
        val d = ChannelHomePaging.decide(FeedPage(listOf(item(1)), null))
        assertTrue(d.mergeNet)
        assertTrue(d.netDone)
        assertFalse(d.fetchIndex)
    }

    @Test
    fun `网络空页游标还在_过路轮_索引兜底_不判到底`() {
        val d = ChannelHomePaging.decide(FeedPage(emptyList(), "42"))
        assertFalse(d.mergeNet)
        assertTrue(d.fetchIndex)
        assertFalse(d.netDone)
    }

    @Test
    fun `网络空页游标空_到底轮_索引兜底_网络到底`() {
        val d = ChannelHomePaging.decide(FeedPage(emptyList(), null))
        assertFalse(d.mergeNet)
        assertTrue(d.fetchIndex)
        assertTrue(d.netDone)
    }

    @Test
    fun `常量口径_旧版页面尺寸不变_索引页有界`() {
        assertEquals(30, ChannelHomePaging.PAGE_LIMIT)
        assertEquals(60, ChannelHomePaging.INDEX_PAGE)
        assertTrue(ChannelHomePaging.NET_PAGE_TIMEOUT_MS >= 10_000L)
    }
}
