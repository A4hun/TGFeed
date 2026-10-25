package dev.tgfeed.core.feed

import android.content.Context
import dev.tgfeed.core.store.LocalStore
import dev.tgfeed.core.td.VideoItem
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * R126 全网重发折叠的单测（同片多源只出现一次）：
 *  - [contentFoldId]：硬判据 fileUniqueId 优先；无指纹时 caption+时长软判据兜底；
 *    caption 空白/时长缺失不参与折叠（宁漏勿错杀）；
 *  - [foldReposts]：FeedViewModel.loadMore 收尾的跨页折叠闸——命中已入列身份或
 *    页内先前幸存者的条目滤掉，先入列者保留，身份集跨页滚动复用；
 *  - [rankWeightedPage] ①段页内折叠经软判据生效，同片留频道权重高者。
 */
class RepostFoldTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: LocalStore

    @Before
    fun setUp() {
        // 假 Context：filesDir 指向 JUnit 临时目录，LocalStore 真实落盘、用完即焚
        val context = mockk<Context>()
        every { context.filesDir } returns tempFolder.root
        store = LocalStore(context)
        // 全新赛马追踪器（"后注册生效"顶掉其他用例残留；默认无"新发现频道"供给）
        ExposureTrackerHolder.register(store) { emptySet() }
    }

    @After
    fun tearDown() {
        // 保底名册是进程级状态：清空防泄漏到其他用例（空名册 = 露头段零开销直通）
        GuaranteedPoolHolder.update(emptySet())
    }

    // ---------------- 夹具 ----------------

    /** 构造一条测试条目：默认无指纹（走软判据路径），caption/时长按需指定 */
    private fun video(
        chatId: Long,
        seq: Int,
        caption: String = "",
        durationSec: Int = 30,
        fileUniqueId: String = "",
    ) = VideoItem(
        key = "k_${chatId}_$seq",
        chatId = chatId,
        messageId = seq.toLong(),
        channelTitle = "频道$chatId",
        caption = caption,
        durationSec = durationSec,
        width = 720,
        height = 1280,
        fileId = -1,
        sizeBytes = 1L,
        playUri = null,
        fileUniqueId = fileUniqueId,
    )

    // ---------------- ① contentFoldId：判据口径 ----------------

    @Test
    fun `硬判据 fileUniqueId 优先且不掺 caption`() {
        // 同一指纹不同 caption（转发时改了文案）必须仍判同片；不同指纹即不同片
        val a = video(1L, 1, caption = "文案甲", fileUniqueId = "uid-x")
        val b = video(2L, 1, caption = "完全不同的文案", fileUniqueId = "uid-x")
        val c = video(3L, 1, caption = "文案甲", fileUniqueId = "uid-y")
        assertEquals(contentFoldId(a), contentFoldId(b))
        assertNotEquals(contentFoldId(a), contentFoldId(c))
    }

    @Test
    fun `软判据 caption 归一化后等价`() {
        // 空白差异（首尾空格/连续空格/换行）与大小写差异不影响判同
        val a = video(1L, 1, caption = "搞笑 合集", durationSec = 42)
        val b = video(2L, 1, caption = "  搞笑\n合集  ", durationSec = 42)
        assertEquals(contentFoldId(a), contentFoldId(b))
    }

    @Test
    fun `软判据时长不同不判同片`() {
        val a = video(1L, 1, caption = "同一段文案", durationSec = 42)
        val b = video(2L, 1, caption = "同一段文案", durationSec = 43)
        assertNotEquals(contentFoldId(a), contentFoldId(b))
    }

    @Test
    fun `信息不足不参与折叠（宁漏勿错杀）`() {
        // 无指纹 + 空 caption → null；无指纹 + 时长缺失（图片等 durationSec=0）→ null
        assertNull(contentFoldId(video(1L, 1, caption = "", durationSec = 30)))
        assertNull(contentFoldId(video(2L, 1, caption = "   ", durationSec = 30)))
        assertNull(contentFoldId(video(3L, 1, caption = "有文案", durationSec = 0)))
    }

    // ---------------- ② foldReposts：跨页折叠闸 ----------------

    @Test
    fun `页内同片多源只留先入列者且计数正确`() {
        val a = video(1L, 1, fileUniqueId = "uid-same")
        val b = video(2L, 1, fileUniqueId = "uid-same") // 同片跨频道搬运
        val c = video(3L, 1, fileUniqueId = "uid-other")
        val known = HashSet<String>()
        val (survivors, dropped) = foldReposts(listOf(a, b, c), known)
        assertEquals(1, dropped)
        assertEquals(listOf(a.key, c.key), survivors.map { it.key }) // 先入列者保留
    }

    @Test
    fun `跨页命中已入列身份直接滤掉`() {
        // 第一页入列 uid-a；第二页又来同片（别的频道、别的 key）→ 滤掉
        val page1 = listOf(video(1L, 1, fileUniqueId = "uid-a"))
        val known = HashSet<String>()
        val (s1, d1) = foldReposts(page1, known)
        assertEquals(0, d1)
        assertEquals(1, s1.size)
        val page2 = listOf(
            video(2L, 1, fileUniqueId = "uid-a"), // 同片另一源：折叠
            video(3L, 1, fileUniqueId = "uid-b"), // 新片：保留
        )
        val (s2, d2) = foldReposts(page2, known)
        assertEquals(1, d2)
        assertEquals(listOf("k_3_1"), s2.map { it.key })
    }

    @Test
    fun `无指纹条目走软判据折叠`() {
        val a = video(1L, 1, caption = "爆款混剪 第三期", durationSec = 95)
        val b = video(2L, 1, caption = "爆款混剪 第三期", durationSec = 95)
        val known = HashSet<String>()
        val (survivors, dropped) = foldReposts(listOf(a, b), known)
        assertEquals(1, dropped)
        assertEquals(listOf(a.key), survivors.map { it.key })
    }

    @Test
    fun `无身份信息条目互不折叠`() {
        // 空 caption 无指纹的两条（软判据信息不足）：都保留，宁漏勿错杀
        val a = video(1L, 1, caption = "")
        val b = video(2L, 1, caption = "")
        val known = HashSet<String>()
        val (survivors, dropped) = foldReposts(listOf(a, b), known)
        assertEquals(0, dropped)
        assertEquals(2, survivors.size)
    }

    // ---------------- ③ rankWeightedPage：页内折叠接软判据 ----------------

    @Test
    fun `页内软判据折叠留频道权重高者`() {
        // 两条无指纹同片（caption+时长相同）分属不同频道：频道 2 权重高 → 留频道 2 的
        val dup1 = video(1L, 1, caption = "同一段混剪", durationSec = 60)
        val dup2 = video(2L, 1, caption = "同一段混剪", durationSec = 60)
        val other = video(3L, 1, caption = "另一条", durationSec = 61)
        val items = listOf(dup1, dup2, other)
        // 预记曝光使 tier=NORMAL，排除小流量池段位对断言的干扰
        ExposureTrackerHolder.current()!!.recordImpressions(items.map { it.key })
        val out = rankWeightedPage(items, mapOf(1L to 0.5, 2L to 1.5, 3L to 1.0), emptySet())
        assertEquals(2, out.size)
        assertTrue(out.none { it.key == dup1.key })
        assertTrue(out.any { it.key == dup2.key })
        assertTrue(out.any { it.key == other.key })
    }
}
