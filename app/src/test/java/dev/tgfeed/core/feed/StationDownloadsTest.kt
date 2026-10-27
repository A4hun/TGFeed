package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R398 #9 离线联动（整单下载）core 纯件行为例（StationDownloads JVM 直测
 * ——QueueSemanticJumpTest/PlayChainTest 同纪律；r398_notes §2.1 定案的
 * 逐例兑现）。
 */
class StationDownloadsTest {

    private fun item(
        key: String,
        playUri: String? = null,
        skipped: Boolean = false,
    ) = VideoItem(
        key = key,
        chatId = -10L,
        messageId = key.hashCode().toLong(),
        channelTitle = "频道",
        caption = "内容$key",
        durationSec = 30,
        width = 720,
        height = 1280,
        fileId = 7,
        sizeBytes = 1024L,
        playUri = playUri,
    )

    private fun entry(key: String, playUri: String? = null, skipped: Boolean = false) =
        PlayQueueEntry.Item(id = "eq_$key", item = item(key, playUri), skipped = skipped)

    private fun station(vararg entries: PlayQueueEntry) =
        PlayQueue(id = "qs_s", name = "台", entries = entries.toList(), updatedAt = 100L)

    // ---------------- stationDownloadItems（下载整单口径） ----------------

    @Test
    fun `台内条目集_非跳过单条条目按序提取`() {
        val e1 = entry("a")
        val e2 = entry("b")
        val out = stationDownloadItems(station(e1, e2))
        assertEquals("单条条目按条目序提取（顺序即下载发起序）", listOf("a", "b"), out.map { it.key })
    }

    @Test
    fun `频道条目与跳过条目不入集`() {
        val ch = PlayQueueEntry.Channel(id = "eq_c", chatId = -20L, title = "台")
        val skipped = entry("s", skipped = true)
        val kept = entry("a")
        val out = stationDownloadItems(station(ch, skipped, kept))
        assertEquals("频道归开台链/跳过=播放期排除（R397 台卡 seedCount 同口径）", listOf("a"), out.map { it.key })
    }

    @Test
    fun `纯频道台与空台_条目集为空`() {
        assertTrue(stationDownloadItems(station(PlayQueueEntry.Channel("eq_c", -20L, "台"))).isEmpty())
        assertTrue(stationDownloadItems(station()).isEmpty())
    }

    // ---------------- stationDownloadCount（徽记口径） ----------------

    @Test
    fun `徽记计数_命中台账的条目数与总数`() {
        val s = station(entry("a"), entry("b"), entry("c"), entry("sk", skipped = true))
        val count = stationDownloadCount(s, setOf("a", "c"))
        assertEquals("M=非跳过单条条目数（跳过不计数）", 3, count.total)
        assertEquals("N=其中 key∈台账数", 2, count.downloaded)
    }

    @Test
    fun `徽记计数_频道条目不进分母`() {
        val s = station(
            PlayQueueEntry.Channel("eq_c", -20L, "台"),
            entry("a"),
        )
        val count = stationDownloadCount(s, emptySet())
        assertEquals("M=1（频道不计数=徽记与下载钮同口径才不骗）", 1, count.total)
        assertEquals("N=0", 0, count.downloaded)
    }

    @Test
    fun `纯频道台_M为零徽记缺席`() {
        val s = station(PlayQueueEntry.Channel("eq_c", -20L, "台"))
        val count = stationDownloadCount(s, setOf("x"))
        assertEquals("M=0=诚实缺席（UI 侧不显示徽记）", 0, count.total)
        assertEquals("N=0", 0, count.downloaded)
    }

    // ---------------- applyDownloadedSegments（离线按单播接线） ----------------

    @Test
    fun `命中段换playUri为本地地址_下载副本离线最强凭据`() {
        val seg = PlaySegment.Single("eq_a", item("a"))
        val out = applyDownloadedSegments(listOf(seg), mapOf("a" to "/storage/emulated/0/Movies/TGFeed/x.mp4"))
        assertEquals("playUri=传入本地绝对路径（toVideoItem 同语义直作 playUri）",
            "/storage/emulated/0/Movies/TGFeed/x.mp4", (out[0] as PlaySegment.Single).item.playUri)
        assertEquals("entryId 保留（断点记账反查不受影响）", "eq_a", out[0].entryId)
    }

    @Test
    fun `未命中段原样_同实例零拷贝`() {
        val seg = PlaySegment.Single("eq_a", item("a"))
        val chSeg = PlaySegment.Channel("eq_c", -20L, "台")
        val plan = listOf(seg, chSeg)
        val out = applyDownloadedSegments(plan, mapOf("zzz" to "/x.mp4"))
        assertSame("未命中段原样返回（同一实例）", seg, out[0])
        assertSame("频道段原样返回", chSeg, out[1])
    }

    @Test
    fun `本地地址表空_整plan原样`() {
        val plan = listOf(PlaySegment.Single("eq_a", item("a")))
        assertSame("表空=一期开单行为零变化（同一实例）", plan, applyDownloadedSegments(plan, emptyMap()))
        val emptyPlan: List<PlaySegment> = emptyList()
        assertSame("plan 空=原样（入参实例直返）", emptyPlan, applyDownloadedSegments(emptyPlan, mapOf("a" to "/x")))
    }

    @Test
    fun `覆盖既有playUri_下载副本优于缓存地址判词`() {
        // 既有 http 外链/流地址（在线可用）与下载文件（离线最强）并存时取下载文件
        val seg = PlaySegment.Single("eq_a", item("a", playUri = "http://example.com/a"))
        val out = applyDownloadedSegments(listOf(seg), mapOf("a" to "/data/local/tmp/a.mp4"))
        assertEquals("下载副本覆盖既有地址（离线最强凭据判词）",
            "/data/local/tmp/a.mp4", (out[0] as PlaySegment.Single).item.playUri)
    }

    @Test
    fun `多次命中各自换_非命中不动`() {
        val plan = listOf(
            PlaySegment.Single("eq_a", item("a")),
            PlaySegment.Single("eq_b", item("b")),
            PlaySegment.Single("eq_c", item("c")),
        )
        val out = applyDownloadedSegments(plan, mapOf("a" to "/xa", "c" to "/xc"))
        assertEquals("a 段换", "/xa", (out[0] as PlaySegment.Single).item.playUri)
        assertNull("b 段不动（playUri 原样 null）", (out[1] as PlaySegment.Single).item.playUri)
        assertEquals("c 段换", "/xc", (out[2] as PlaySegment.Single).item.playUri)
    }
}
