package dev.tgfeed.core.feed

import android.content.Context
import dev.tgfeed.core.store.LocalStore
import dev.tgfeed.core.td.VideoItem
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [HistoryFeedSource]（R165 H1 闭坑后）的行为单测：
 * 内存半边（构造时快照）+ 磁盘半边（nextPage 现读）合并、key 撞车内存条目赢
 * （最新播放地址优先）、磁盘读延迟到 nextPage（构造后落的历史也出现在首页——
 * 旧实现构造时取用 disk 内容，此测试反证旧病）、一次性到底无游标。
 */
class HistoryFeedSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: LocalStore

    @Before
    fun setUp() {
        val context = mockk<Context>()
        every { context.filesDir } returns tempFolder.root
        store = LocalStore(context)
    }

    private fun item(key: String, playUri: String, epochSec: Int = 1_700_000_000) = VideoItem(
        key = key,
        chatId = 1L,
        messageId = key.removePrefix("c1m").toLongOrNull() ?: 0L,
        channelTitle = "频道1",
        caption = "内容$key",
        durationSec = 30,
        width = 720,
        height = 1280,
        fileId = 7,
        sizeBytes = 1024L,
        playUri = playUri,
        dateEpochSec = epochSec,
    )

    @Test
    fun `磁盘半边与内存半边合并_顺序跟历史账`() = runBlocking {
        // 落账顺序 k3→k1→k2（appendHistory 置顶语义 → 账本序 k2,k1,k3，观看时间倒序）
        store.appendHistory(item("k3", "disk"))
        store.appendHistory(item("k1", "mem"))
        store.appendHistory(item("k2", "disk"))
        val src = HistoryFeedSource(store, listOf(item("k1", "mem", 1), item("k2", "mem", 2)))
        val page = src.nextPage(20)
        // k1/k2 内存有 → 内存条目；k3 只在磁盘 → 磁盘条目；顺序 = 账本序（新→旧）
        assertEquals(listOf("k2", "k1", "k3"), page.items.map { it.key })
        assertNull(page.nextCursor) // 一次性给完
    }

    @Test
    fun `key撞车内存条目赢_带最新播放地址`() = runBlocking {
        store.appendHistory(item("k1", "stale-uri"))
        val src = HistoryFeedSource(store, listOf(item("k1", "fresh-uri")))
        val page = src.nextPage(20)
        assertEquals(1, page.items.size)
        assertEquals("fresh-uri", page.items[0].playUri)
    }

    @Test
    fun `构造后才落盘的历史也会出现_证明磁盘读延迟到nextPage`() = runBlocking {
        // R165 H1 的核心回归：磁盘半边 nextPage 现读——构造时刻 store 还是空的
        val src = HistoryFeedSource(store, emptyList())
        assertTrue(src.nextPage(20).items.isEmpty()) // 此时确实无历史
        store.appendHistory(item("late", "disk")) // 构造之后才产生历史
        val page = src.nextPage(20)
        assertEquals(listOf("late"), page.items.map { it.key }) // 依然读得到
    }

    @Test
    fun `无历史无内存返回空页`() = runBlocking {
        val src = HistoryFeedSource(store, emptyList())
        val page = src.nextPage(20)
        assertTrue(page.items.isEmpty())
        assertNull(page.nextCursor)
    }

    @Test
    fun `reset后重放_nextPage重读磁盘口径不变`() = runBlocking {
        store.appendHistory(item("k1", "disk"))
        val src = HistoryFeedSource(store, emptyList())
        val first = src.nextPage(20)
        src.reset() // 无游标状态：reset 后重放应原样再现
        val replay = src.nextPage(20)
        assertEquals(first.items.map { it.key }, replay.items.map { it.key })
    }
}
