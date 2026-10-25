package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ComicChapterPolicy]（R299 B3 话归组纯函数）行为单测：
 * albumId 归话 / 锚话定位 / 邻话择取方向（+1 下一话=更新=列表头方向）。
 */
class ComicChapterPolicyTest {

    private fun img(key: String, albumId: Long = 0L) = VideoItem(
        key = key,
        chatId = 100L,
        messageId = key.removePrefix("k").toLongOrNull() ?: 0L,
        channelTitle = "频道乙",
        caption = "",
        durationSec = 0,
        width = 800,
        height = 2400,
        fileId = -1,
        sizeBytes = 0L,
        playUri = null,
        mediaType = 1,
        albumId = albumId,
    )

    @Test
    fun `归话_连续同相册合一话_散图单条一话`() {
        // 输入=messageId 新→旧序（channelItemsPage 口径）
        val items = listOf(
            img("k9"),                       // 散图一话
            img("k8", 77), img("k7", 77), img("k6", 77), // 相册 77 一话（3 张）
            img("k5"), img("k4", 55), img("k3", 55),
        )
        val chapters = ComicChapterPolicy.chaptersOf(items)
        assertEquals(4, chapters.size)
        assertEquals(listOf("k9"), chapters[0].map { it.key })
        assertEquals(listOf("k8", "k7", "k6"), chapters[1].map { it.key })
        assertEquals(listOf("k5"), chapters[2].map { it.key })
        assertEquals(listOf("k4", "k3"), chapters[3].map { it.key })
    }

    @Test
    fun `邻话择取_下一话取更新_上一话取更旧`() {
        val chapters = ComicChapterPolicy.chaptersOf(
            listOf(img("k9"), img("k8", 77), img("k7", 77), img("k5"), img("k4", 55)),
        )
        val anchor = img("k6", 77) // 相册 77 的话（锚=话内任一成员，albumId 归并身份）
        // +1 下一话 = 更新（列表头方向）
        assertEquals(listOf("k9"), ComicChapterPolicy.neighborOf(chapters, anchor, +1)?.map { it.key })
        // -1 上一话 = 更旧
        assertEquals(listOf("k5"), ComicChapterPolicy.neighborOf(chapters, anchor, -1)?.map { it.key })
        // 头话没有下一话；尾话没有上一话
        assertNull(ComicChapterPolicy.neighborOf(chapters, img("k9"), +1))
        assertNull(ComicChapterPolicy.neighborOf(chapters, img("k4", 55), -1))
    }

    @Test
    fun `锚定位_散图按key_相册按albumId`() {
        val chapters = ComicChapterPolicy.chaptersOf(
            listOf(img("k9"), img("k8", 77), img("k7", 77)),
        )
        assertEquals(0, ComicChapterPolicy.chapterIndexOf(chapters, img("k9")))
        assertEquals(1, ComicChapterPolicy.chapterIndexOf(chapters, img("k6", 77))) // 话外成员同 albumId 仍归该话
        assertEquals(-1, ComicChapterPolicy.chapterIndexOf(chapters, img("k1", 99)))
    }
}
