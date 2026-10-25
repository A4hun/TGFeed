package dev.tgfeed.core.playback

import dev.tgfeed.core.td.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ListenQueueModel]（R299 B1 听单纯模型）的行为单测：
 * up-next 语义（入尾/插队首/出列即播完）/ 同 key 幂等 / 500 截尾 /
 * lastPlayed 上一首栈 / replaceAll 恢复口径。
 */
class ListenQueueModelTest {

    private fun audio(key: String) = VideoItem(
        key = key,
        chatId = 100L,
        messageId = key.removePrefix("k").toLongOrNull() ?: 0L,
        channelTitle = "频道甲",
        caption = "",
        durationSec = 180,
        width = 0,
        height = 0,
        fileId = -1,
        sizeBytes = 0L,
        playUri = null,
        mediaType = 2,
    )

    @Test
    fun `加入听单入尾_重复加入幂等`() {
        val m = ListenQueueModel()
        assertTrue(m.enqueue(audio("k1")))
        assertTrue(m.enqueue(audio("k2")))
        assertFalse(m.enqueue(audio("k1"))) // 已在单=幂等忽略
        assertEquals(listOf("k1", "k2"), m.items.map { it.key })
    }

    @Test
    fun `插队首_已在单提到队首不重复占位`() {
        val m = ListenQueueModel()
        m.enqueue(audio("k1"))
        m.enqueue(audio("k2"))
        m.enqueue(audio("k3"))
        m.playNext(audio("k4"))
        assertEquals(listOf("k4", "k1", "k2", "k3"), m.items.map { it.key })
        m.playNext(audio("k2")) // 已在单=提首
        assertEquals(listOf("k2", "k4", "k1", "k3"), m.items.map { it.key })
    }

    @Test
    fun `出列开播_队首移除并记lastPlayed_队空归null`() {
        val m = ListenQueueModel()
        m.enqueue(audio("k1"))
        m.enqueue(audio("k2"))
        assertEquals("k1", m.popHead()?.key)
        assertEquals("k1", m.lastPlayed?.key)
        assertEquals(listOf("k2"), m.items.map { it.key })
        assertEquals("k2", m.popHead()?.key)
        assertNull(m.popHead()) // 队空回落（调用方走 Feed 连播现状）
        assertEquals("k2", m.lastPlayed?.key) // 空出列不污染 lastPlayed
    }

    @Test
    fun `上一首重播不动队列本体`() {
        val m = ListenQueueModel()
        m.enqueue(audio("k1"))
        m.enqueue(audio("k2"))
        m.popHead() // 播完 k1
        assertEquals("k1", m.replayLast()?.key)
        assertEquals(listOf("k2"), m.items.map { it.key }) // 重播不插队
        // 清空听单不动 lastPlayed（仍可重播刚播完的那条）
        m.clear()
        assertEquals("k1", m.replayLast()?.key)
    }

    @Test
    fun `移除按key_无此条不落盘语义false`() {
        val m = ListenQueueModel()
        m.enqueue(audio("k1"))
        assertFalse(m.remove("k9"))
        assertTrue(m.remove("k1"))
        assertTrue(m.items.isEmpty())
    }

    @Test
    fun `上限500截尾`() {
        val m = ListenQueueModel()
        repeat(600) { m.enqueue(audio("k$it")) }
        assertEquals(ListenQueueModel.MAX_SIZE, m.items.size)
        assertEquals("k0", m.items.first().key) // 截尾保头部（先攒的先听）
        m.playNext(audio("kx"))
        assertEquals(ListenQueueModel.MAX_SIZE, m.items.size)
        assertEquals("kx", m.items.first().key) // 插队首恒保
    }

    @Test
    fun `replaceAll恢复亦截尾`() {
        val m = ListenQueueModel()
        m.replaceAll((0 until 600).map { audio("k$it") })
        assertEquals(ListenQueueModel.MAX_SIZE, m.items.size)
    }
}
