package dev.tgfeed.core.store

import android.content.Context
import dev.tgfeed.core.td.VideoItem
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * R170b 关注流首页快照落盘往返单测（[LocalStore.saveFollowingSnapshot] /
 * [LocalStore.loadFollowingSnapshot]）：
 * 保存→读回条目/模式一致、条数截断到上限、空列表不写（不覆盖好快照）、
 * 无文件返回 null、coverB64 往返（minithumbnail 占位）。
 */
class FollowingSnapshotStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: LocalStore

    @Before
    fun setUp() {
        val context = mockk<Context>()
        every { context.filesDir } returns tempFolder.root
        store = LocalStore(context)
    }

    private fun item(chatId: Long, msgId: Long) = VideoItem(
        key = "c${chatId}m$msgId",
        chatId = chatId,
        messageId = msgId,
        channelTitle = "频道$chatId",
        caption = "内容$msgId",
        durationSec = 30,
        width = 720,
        height = 1280,
        fileId = 7,
        sizeBytes = 1024L,
        playUri = "http://x/$chatId/$msgId",
        dateEpochSec = 1_700_000_000 + msgId.toInt(),
    )

    @Test
    fun `保存读回条目与模式一致`() {
        val items = listOf(item(1, 10), item(2, 20), item(3, 30))
        store.saveFollowingSnapshot(items, "real")
        val snap = store.loadFollowingSnapshot()
        assertNotNull(snap)
        assertEquals("real", snap!!.mode)
        assertEquals(items.map { it.key }, snap.items.map { it.key })
        assertEquals(items.map { it.dateEpochSec }, snap.items.map { it.dateEpochSec })
    }

    @Test
    fun `coverB64 往返还原占位字节`() {
        val withCover = item(1, 10).let { it.copy(coverBytes = byteArrayOf(1, 2, 3, 4)) }
        store.saveFollowingSnapshot(listOf(withCover), "real")
        val snap = store.loadFollowingSnapshot()
        assertNotNull(snap)
        assertTrue(snap!!.items[0].coverBytes.contentEquals(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `条数截断到快照上限`() {
        val items = (1L..50L).map { item(it, it) }
        store.saveFollowingSnapshot(items, "real")
        val snap = store.loadFollowingSnapshot()
        assertNotNull(snap)
        assertTrue(snap!!.items.size <= 20)
        assertEquals(items.take(20).map { it.key }, snap.items.map { it.key }) // 保头去尾
    }

    @Test
    fun `空列表不写_不覆盖好快照`() {
        store.saveFollowingSnapshot(listOf(item(1, 1)), "real")
        store.saveFollowingSnapshot(emptyList(), "real") // 空写应被忽略
        val snap = store.loadFollowingSnapshot()
        assertNotNull(snap)
        assertEquals(1, snap!!.items.size)
    }

    @Test
    fun `无文件返回 null`() {
        assertNull(store.loadFollowingSnapshot())
    }
}
