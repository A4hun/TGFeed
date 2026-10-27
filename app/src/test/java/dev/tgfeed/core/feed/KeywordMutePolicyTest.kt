package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R413 关键词屏蔽纯函数单测（全合成词） */
class KeywordMutePolicyTest {

    private fun item(
        caption: String = "",
        channel: String = "频道甲",
        title: String? = null,
        tags: List<String>? = null,
    ) = VideoItem(
        key = "k1", chatId = 1L, messageId = 1L, channelTitle = channel, caption = caption,
        durationSec = 0, width = 0, height = 0, fileId = -1, sizeBytes = 0L, playUri = null,
        parsedTitle = title, itemTags = tags,
    )

    @Test
    fun emptySetNeverHits() {
        val m = KeywordMutePolicy.compile(emptySet(), matchChannelTitle = true)
        assertTrue(m.isEmpty)
        assertFalse(m.hits(item(caption = "推广 加群 任意文本")))
    }

    @Test
    fun plainSubstringHits() {
        val m = KeywordMutePolicy.compile(setOf("推广"), false)
        assertTrue(m.hits(item(caption = "今日推广活动")))
        assertFalse(m.hits(item(caption = "普通内容")))
    }

    @Test
    fun caseAndFullWidthFolded() {
        val m = KeywordMutePolicy.compile(setOf("ABC"), false)
        assertTrue(m.hits(item(caption = "xxabcxx")))
        assertTrue(m.hits(item(caption = "xxＡＢＣxx")))
        val m2 = KeywordMutePolicy.compile(setOf("ｖｉｐ１"), false)
        assertTrue(m2.hits(item(caption = "VIP1 专享")))
    }

    @Test
    fun multipleWordsAnyHits() {
        val m = KeywordMutePolicy.compile(setOf("广告词A", "加群"), false)
        assertTrue(m.hits(item(caption = "欢迎加群")))
        assertTrue(m.hits(item(caption = "含广告词a的内容")))
        assertFalse(m.hits(item(caption = "广告 词A 被拆开")))
    }

    @Test
    fun titleAndTagsChecked() {
        val m = KeywordMutePolicy.compile(setOf("推广"), false)
        assertTrue(m.hits(item(title = "推广标题")))
        assertTrue(m.hits(item(tags = listOf("其他", "推广"))))
    }

    @Test
    fun channelTitleSwitch() {
        val off = KeywordMutePolicy.compile(setOf("推广"), matchChannelTitle = false)
        val on = KeywordMutePolicy.compile(setOf("推广"), matchChannelTitle = true)
        val it = item(caption = "普通", channel = "推广频道")
        assertFalse(off.hits(it))
        assertTrue(on.hits(it))
    }

    @Test
    fun blankAndOverlongRejected() {
        assertNull(KeywordMutePolicy.normalize(""))
        assertNull(KeywordMutePolicy.normalize("   "))
        assertNull(KeywordMutePolicy.normalize("　"))
        assertNull(KeywordMutePolicy.normalize("a".repeat(31)))
        assertEquals("a".repeat(30), KeywordMutePolicy.normalize("a".repeat(30)))
        assertEquals("推 广", KeywordMutePolicy.normalize("  推   广 "))
        val m = KeywordMutePolicy.compile(setOf("  ", "a".repeat(40)), false)
        assertTrue(m.isEmpty)
    }

    @Test
    fun sanitizeDedupsByFold() {
        val s = KeywordMutePolicy.sanitize(listOf("Abc", "abc", "ＡＢＣ", "加群", " 加群 "))
        assertEquals(listOf("Abc", "加群"), s.toList())
        assertTrue(KeywordMutePolicy.containsEquivalent(s, "aBC"))
        assertFalse(KeywordMutePolicy.containsEquivalent(s, "推广"))
    }
}
