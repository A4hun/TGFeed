package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WK-3 事件研究器（R205）单测：参数模型 + 研究引擎全链 + 数据源端口 mock。
 *
 * 覆盖：
 *  - 窗过滤（时间界/无时间证据条目/频道集合）；
 *  - 时间轴分桶（等宽/空桶保留/短窗小时粒度标签）；
 *  - 共现簇：fileUniqueId 精确指纹、归一化 caption 模板指纹（AdPatternFilter
 *    同口径归一化——「第03集/第12集」同模板）、72h 断簇（同一内容隔一年=两个
 *    事件）、minClusterChannels 闸（单频道重发不算事件）、簇排序（频道数降序）；
 *  - 主流程 research（mock 源三态直通：EnginePending 不吞）；
 *  - 首发溯源（最早条目 isFirst + offsetHours=0，中继滞后小时数）。
 *
 * 数据纪律：测试数据全部合成（频道A/B/C 代称），无真实频道名/标题。
 */
class EventResearchTest {

    private fun item(
        chatId: Long,
        chatName: String,
        caption: String,
        epochSec: Long,
        fileUniqueId: String = "",
    ) = VideoItem(
        key = "tg:$chatId:$epochSec",
        chatId = chatId,
        messageId = epochSec,
        channelTitle = chatName,
        caption = caption,
        durationSec = 60,
        width = 1920,
        height = 1080,
        fileId = -1,
        sizeBytes = 0,
        playUri = null,
        dateEpochSec = epochSec.toInt(),
        fileUniqueId = fileUniqueId,
    )

    private val base = 1_700_000_000L // 2023-11-14 UTC 左右，固定基点防测试随日历漂移
    private val hour = 3600L

    // ---------------- filterWindow ----------------

    @Test
    fun `窗过滤_时间界含端点_窗外与无时间证据条目被滤`() {
        val q = EventResearchQuery(fromEpochSec = base, toEpochSec = base + 10 * hour)
        val items = listOf(
            item(1, "频道A", "窗内下界", base),
            item(1, "频道A", "窗内上界", base + 10 * hour),
            item(2, "频道B", "窗外早", base - hour),
            item(2, "频道B", "窗外晚", base + 11 * hour),
            item(3, "频道C", "无时间证据", 0),
        )
        val out = EventResearcher.filterWindow(items, q)
        assertEquals(2, out.size)
        assertTrue(out.all { it.dateEpochSec.toLong() in base..(base + 10 * hour) })
    }

    @Test
    fun `窗过滤_频道集合非空只留集合内频道`() {
        val q = EventResearchQuery(
            fromEpochSec = base, toEpochSec = base + hour,
            chatIds = setOf(2L),
        )
        val items = listOf(
            item(1, "频道A", "a", base),
            item(2, "频道B", "b", base),
        )
        val out = EventResearcher.filterWindow(items, q)
        assertEquals(1, out.size)
        assertEquals(2L, out.first().chatId)
    }

    // ---------------- bucketTimeline ----------------

    @Test
    fun `分桶_等宽满桶数_空桶保留_计数落位`() {
        val buckets = EventResearcher.bucketTimeline(
            items = listOf(
                item(1, "频道A", "a", base),
                item(1, "频道A", "b", base),
                item(2, "频道B", "c", base + 10 * hour),
            ),
            fromSec = base,
            toSec = base + 10 * hour,
            maxBuckets = 10,
        )
        assertEquals(10, buckets.size)
        assertEquals(2, buckets.first().count) // 第 1 桶 [base, base+1h)
        assertEquals(1, buckets.last().count) // 末桶吸收余量 [base+9h, base+10h]
        assertEquals(0, buckets[4].count) // 空桶保留（时间轴形状的一部分）
        assertTrue(buckets.all { it.label.isNotBlank() })
    }

    @Test
    fun `分桶_窗非法或桶数非法返回空`() {
        assertTrue(EventResearcher.bucketTimeline(emptyList(), base, base, 24).isEmpty())
        assertTrue(EventResearcher.bucketTimeline(emptyList(), base, base, 0).isEmpty())
    }

    // ---------------- buildClusters ----------------

    @Test
    fun `共现簇_fileUniqueId精确指纹_三频道同片成一簇_首发最早`() {
        val q = EventResearchQuery(fromEpochSec = 0, toEpochSec = Long.MAX_VALUE)
        val items = listOf(
            item(2, "频道B", "搬运", base + 30 * hour, fileUniqueId = "FU:X"),
            item(1, "频道A", "首发", base, fileUniqueId = "FU:X"),
            item(3, "频道C", "再搬运", base + 60 * hour, fileUniqueId = "FU:X"),
        )
        val clusters = EventResearcher.buildClusters(items, q)
        assertEquals(1, clusters.size)
        val c = clusters.first()
        assertTrue(c.exact)
        assertEquals(3, c.channelCount)
        assertEquals(base, c.firstEpochSec)
        assertEquals("首发", c.representativeTitle)
        // 传播路径按时间升序：首发在首，offset 0；中继滞后小时数递增
        assertEquals(listOf(1L, 2L, 3L), c.relays.map { it.item.chatId })
        assertTrue(c.relays.first().isFirst)
        assertFalse(c.relays[1].isFirst)
        assertEquals(0L, c.relays.first().offsetHours)
        assertEquals(30L, c.relays[1].offsetHours)
        assertEquals(60L, c.relays[2].offsetHours)
    }

    @Test
    fun `共现簇_空fileUniqueId走归一化模板指纹_集数变化同模板`() {
        val q = EventResearchQuery(fromEpochSec = 0, toEpochSec = Long.MAX_VALUE)
        val items = listOf(
            item(1, "频道A", "【蓝光修复】合集第03集中文字幕", base),
            item(2, "频道B", "【蓝光修复】合集第12集中文字幕", base + hour),
        )
        // 归一化后「第#集」同模板（数字折叠）且长度过 12 字下限 → 成簇（模板兜底路）
        val clusters = EventResearcher.buildClusters(items, q)
        assertEquals(1, clusters.size)
        assertFalse(clusters.first().exact) // 模板指纹（同文案兜底路）
    }

    @Test
    fun `共现簇_短话术不构成模板证据`() {
        val q = EventResearchQuery(fromEpochSec = 0, toEpochSec = Long.MAX_VALUE)
        val items = listOf(
            item(1, "频道A", "感谢关注", base),
            item(2, "频道B", "感谢关注", base + hour),
        )
        // 「感谢关注」归一化后 4 字 < MIN_TEMPLATE_FINGERPRINT_LEN：不参与簇
        assertTrue(EventResearcher.buildClusters(items, q).isEmpty())
    }

    @Test
    fun `共现簇_超窗断簇_同一内容隔一年是两个事件`() {
        val q = EventResearchQuery(fromEpochSec = 0, toEpochSec = Long.MAX_VALUE)
        val items = listOf(
            item(1, "频道A", "旧事件", base, fileUniqueId = "FU:Y"),
            item(2, "频道B", "旧事件转发", base + 24 * hour, fileUniqueId = "FU:Y"),
            item(3, "频道C", "一年后再传", base + 365 * 24 * hour, fileUniqueId = "FU:Y"),
        )
        // 72h 共现窗：A→B 一簇；C 距 B 一年，断为新段但单频道不成簇
        val clusters = EventResearcher.buildClusters(items, q)
        assertEquals(1, clusters.size)
        assertEquals(setOf(1L, 2L), clusters.first().relays.map { it.item.chatId }.toSet())
    }

    @Test
    fun `共现簇_最小频道数闸_单频道重发不算事件`() {
        val q = EventResearchQuery(fromEpochSec = 0, toEpochSec = Long.MAX_VALUE)
        val items = listOf(
            item(1, "频道A", "重发", base, fileUniqueId = "FU:Z"),
            item(1, "频道A", "重发2", base + hour, fileUniqueId = "FU:Z"),
        )
        assertTrue(EventResearcher.buildClusters(items, q).isEmpty())
    }

    @Test
    fun `共现簇_簇排序_频道数降序同规模首发时间降序`() {
        val q = EventResearchQuery(fromEpochSec = 0, toEpochSec = Long.MAX_VALUE)
        val big = listOf(
            item(1, "频道A", "大事件", base, fileUniqueId = "FU:B1"),
            item(2, "频道B", "大事件", base + hour, fileUniqueId = "FU:B1"),
            item(3, "频道C", "大事件", base + 2 * hour, fileUniqueId = "FU:B1"),
        )
        val small = listOf(
            item(1, "频道A", "小事件", base + 100 * hour, fileUniqueId = "FU:S1"),
            item(2, "频道B", "小事件", base + 101 * hour, fileUniqueId = "FU:S1"),
        )
        val clusters = EventResearcher.buildClusters(big + small, q)
        assertEquals(2, clusters.size)
        assertEquals(3, clusters.first().channelCount) // 大簇在前
        assertEquals("大事件", clusters.first().representativeTitle)
    }

    // ---------------- research（主流程，mock 源） ----------------

    /** 内存 mock 源（R202 接口公告前的单测替身；模拟「源返回全量，引擎负责窗过滤」） */
    private class MockSource(private val all: List<VideoItem>) : EventResearchSource {
        override suspend fun loadWindow(query: EventResearchQuery): SourceOutcome =
            SourceOutcome.Items(all, "mock 召回 ${all.size} 条")
    }

    @Test
    fun `research主流程_mock源_窗过滤加时间轴加簇`() = kotlinx.coroutines.runBlocking {
        val q = EventResearchQuery(
            fromEpochSec = base, toEpochSec = base + 72 * hour,
            keywords = "合集",
        )
        val all = listOf(
            item(1, "频道A", "【合集】首发", base, fileUniqueId = "FU:M"),
            item(2, "频道B", "【合集】转发", base + 48 * hour, fileUniqueId = "FU:M"),
            item(3, "频道C", "窗外的老帖", base - 24 * hour, fileUniqueId = "FU:M"),
        )
        val r = EventResearcher.research(q, MockSource(all))
        assertTrue(r is SourceOutcomeOrResult.Ready)
        val result = (r as SourceOutcomeOrResult.Ready).result
        // 窗过滤：窗外老帖被滤（哪怕同指纹——窗是第一道闸）
        assertEquals(2, result.items.size)
        assertEquals(1, result.clusters.size)
        assertTrue(result.items.zipWithNext().all { (a, b) ->
            a.dateEpochSec.toLong() <= b.dateEpochSec.toLong()
        }) // 时间升序
        assertEquals("mock 召回 3 条", result.engineNote)
    }

    @Test
    fun `research主流程_源EnginePending直通不吞`() = kotlinx.coroutines.runBlocking {
        val q = EventResearchQuery(fromEpochSec = base, toEpochSec = base + hour, keywords = "")
        val r = EventResearcher.research(q, object : EventResearchSource {
            override suspend fun loadWindow(query: EventResearchQuery) = SourceOutcome.EnginePending
        })
        assertTrue(r is SourceOutcomeOrResult.EnginePending)
    }

    @Test
    fun `research主流程_源Failed直通`() = kotlinx.coroutines.runBlocking {
        val q = EventResearchQuery(fromEpochSec = base, toEpochSec = base + hour, keywords = "x")
        val r = EventResearcher.research(q, object : EventResearchSource {
            override suspend fun loadWindow(query: EventResearchQuery) =
                SourceOutcome.Failed("索引未建")
        })
        assertTrue(r is SourceOutcomeOrResult.Failed)
        assertEquals("索引未建", (r as SourceOutcomeOrResult.Failed).reason)
    }

    // ---------------- 参数模型默认值 ----------------

    @Test
    fun `参数默认值_W4口径_共现窗72h_最小频道数2`() {
        val q = EventResearchQuery(fromEpochSec = 0, toEpochSec = 1)
        assertEquals(72, q.coOccurrenceWindowHours)
        assertEquals(2, q.minClusterChannels)
        assertEquals(EventResearchQuery.DEFAULT_CO_OCCURRENCE_WINDOW_HOURS, 72)
    }

    @Test
    fun `research引擎_空结果_簇与桶为空不异常`() = kotlinx.coroutines.runBlocking {
        val q = EventResearchQuery(fromEpochSec = base, toEpochSec = base + hour, keywords = "无命中")
        val r = EventResearcher.research(q, MockSource(emptyList()))
        assertTrue(r is SourceOutcomeOrResult.Ready)
        val result = (r as SourceOutcomeOrResult.Ready).result
        assertTrue(result.items.isEmpty())
        assertTrue(result.clusters.isEmpty())
        assertTrue(EventResearcher.bucketTimeline(result.items, q.fromEpochSec, q.toEpochSec).isEmpty().not())
    }

    // ---------------- R222 hashtag 时间轴（全史窗语义锁） ----------------

    /**
     * hashtag 时间轴的查询形态：keywords="#tag"（hashtag 精确词模式）、
     * from=[HASHTAG_TIMELINE_FROM_SEC]（=1，全史下界）、to=now。锁两件事：
     *  1. 缺省时间（t=0 老收藏）不进时间轴——时间轴上没有诚实位置；
     *  2. Ready 结果按时间升序（首=首次出现、尾=最新——UI 的「从头到尾」量程
     *     直接取 first/last，顺序契约在此锁死）。
     */
    @Test
    fun `hashtag全史窗_from为1_无时间证据不进轴_结果时间升序首尾即量程`() = kotlinx.coroutines.runBlocking {
        val q = EventResearchQuery(
            fromEpochSec = HASHTAG_TIMELINE_FROM_SEC,
            toEpochSec = base + 24 * hour,
            keywords = "#tag",
        )
        assertEquals(1L, HASHTAG_TIMELINE_FROM_SEC)
        val all = listOf(
            item(2, "频道B", "#tag 稍后", base + 2 * hour),
            item(3, "频道C", "老收藏缺省时间", 0),
            item(1, "频道A", "#tag 首发", base),
        )
        val r = EventResearcher.research(q, MockSource(all))
        assertTrue(r is SourceOutcomeOrResult.Ready)
        val items = (r as SourceOutcomeOrResult.Ready).result.items
        assertEquals(2, items.size) // t=0 被滤
        assertEquals(base, items.first().dateEpochSec.toLong()) // 升序：首次出现=头
        assertEquals(base + 2 * hour, items.last().dateEpochSec.toLong()) // 最新=尾
    }
}
