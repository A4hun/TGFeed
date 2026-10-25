package dev.tgfeed.core.store

import dev.tgfeed.core.td.VideoItem
import io.mockk.every
import io.mockk.mockk
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * R299 B1 听单持久化（LocalStore.loadListenQueue/saveListenQueue）行为单测：
 * 落盘往返保真（顺序+关键字段）/ 损坏文件容错空表 / 增量字段
 * （audioTitle/albumId/photoViewFileId）随 Codec 落盘。
 */
class ListenQueueStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: LocalStore

    @Before
    fun setUp() {
        val context = mockk<Context>()
        every { context.filesDir } returns tempFolder.root
        store = LocalStore(context)
    }

    private fun audio(key: String, msgId: Long) = VideoItem(
        key = key,
        chatId = 100L,
        messageId = msgId,
        channelTitle = "频道丙",
        caption = "歌名$key",
        durationSec = 200,
        width = 0,
        height = 0,
        fileId = -1,
        sizeBytes = 4096L,
        playUri = null,
        fileUniqueId = "uid_$key",
        mediaType = 2,
        audioTitle = "曲名$key",
        audioPerformer = "歌手丙",
        albumId = 0L,
    )

    @Test
    fun `听单落盘往返保真_顺序与增量字段齐全`() {
        val items = listOf(audio("a", 1), audio("b", 2), audio("c", 3))
        store.saveListenQueue(items)
        // 全新实例（缓存不共享）读回——真落盘验证
        val fresh = LocalStore(mockk<Context>().apply {
            every { filesDir } returns tempFolder.root
        })
        val back = fresh.loadListenQueue()
        assertEquals(listOf("a", "b", "c"), back.map { it.key })
        assertEquals(listOf("曲名a", "曲名b", "曲名c"), back.map { it.audioTitle })
        assertEquals("歌手丙", back.first().audioPerformer)
        assertEquals(2, back.first().mediaType)
        assertEquals(listOf("uid_a", "uid_b", "uid_c"), back.map { it.fileUniqueId })
    }

    @Test
    fun `听单图片条目的图集与浏览档凭据随单落盘`() {
        val photo = VideoItem(
            key = "p1", chatId = 100L, messageId = 9L, channelTitle = "频道丙",
            caption = "", durationSec = 0, width = 800, height = 2400,
            fileId = -1, sizeBytes = 1000L, playUri = null, mediaType = 1,
            albumId = 777L, photoViewFileId = 55,
        )
        store.saveListenQueue(listOf(photo))
        val fresh = LocalStore(mockk<Context>().apply {
            every { filesDir } returns tempFolder.root
        })
        val back = fresh.loadListenQueue().single()
        assertEquals(777L, back.albumId)
        assertEquals(55, back.photoViewFileId)
        assertEquals(800, back.width)
        assertEquals(2400, back.height)
    }

    @Test
    fun `无文件返回空表_覆盖写清空语义`() {
        assertTrue(store.loadListenQueue().isEmpty())
        store.saveListenQueue(listOf(audio("a", 1)))
        assertEquals(1, store.loadListenQueue().size)
        store.saveListenQueue(emptyList())
        assertTrue(store.loadListenQueue().isEmpty())
    }
}
