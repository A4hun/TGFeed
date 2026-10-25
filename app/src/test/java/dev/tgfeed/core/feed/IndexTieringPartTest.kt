package dev.tgfeed.core.feed

import dev.tgfeed.core.store.WatchEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IndexTieringPart] R163 B4 的纯信号折算单测（VM 实现侧聚合源是 SQL/StateFlow，
 * 设备验证覆盖）：
 *  - [watchDemandFold]：观看事件 → 需求三元组（事件数/完播率/平均秒；完播分母
 *    只含有真实时长的结算——图文 durationSec=0 不计，防「图文恒完播」虚高）；
 *  - [richnessFold]：items 聚合 + 死链率 → 丰度信号（零条目=未探针；剧集规整率
 *    = caption 样本 parseEpisodeMark 命中比例）。
 */
class IndexTieringPartTest {

    private companion object {
        const val EPS = 1e-9
    }

    private fun ev(chatId: Long, watchSec: Int, durationSec: Int) =
        WatchEvent(chatId, watchSec, durationSec, ts = System.currentTimeMillis())

    // ---------------- watchDemandFold ----------------

    @Test
    fun `观看事件聚合_事件数完播率平均秒`() {
        // 行为：5 事件（4 有真实时长：3 完播 1 半途；1 图文无时长——进事件数与
        // 平均秒、不进完播分母，防「图文恒完播」虚高）→ (5, 3/4, 均值)
        val (n, cr, avg) = watchDemandFold(
            listOf(
                ev(1L, 60, 60),  // 完播（1.0 ≥ 0.9）
                ev(1L, 55, 60),  // 完播（0.917 ≥ 0.9）
                ev(1L, 20, 60),  // 半途（0.33）
                ev(1L, 30, 0),   // 图文：无真实时长——进平均秒不进完播分母
                ev(1L, 61, 60),  // 完播（1.017 超 1 也 ≥ 0.9 计完播）
            ),
        )
        assertEquals(5, n)
        assertEquals(3.0 / 4, cr, EPS) // 完播 3 / 有时长 4
        assertEquals((60 + 55 + 20 + 30 + 61) / 5.0, avg, EPS)
    }

    @Test
    fun `恰在完播线上的事件算完播_完播线是闭区间`() {
        // 行为：watchSec/duration 恰=0.9 → 完播（≥ 口径，与 InteractionStats 同值）
        val (_, cr, _) = watchDemandFold(listOf(ev(1L, 90, 100), ev(1L, 89, 100)))
        assertEquals(0.5, cr, EPS)
    }

    @Test
    fun `零事件返回全零_无证据不臆测`() {
        val (n, cr, avg) = watchDemandFold(emptyList())
        assertEquals(0, n)
        assertEquals(0.0, cr, EPS)
        assertEquals(0.0, avg, EPS)
    }

    // ---------------- richnessFold ----------------

    private fun stats(
        items: Int = 100,
        spanSec: Long = 35L * 86_400,
        videoShare: Double = 1.0,
        nonEmptyRate: Double = 1.0,
        avgLen: Double = 80.0,
        captions: List<String> = listOf("第1集 上", "第2集 下", "EP.3", "随便一条"),
    ) = DeepSearchIndex.ChannelIndexStats(items, spanSec, videoShare, nonEmptyRate, avgLen, captions)

    @Test
    fun `零条目或null为未探针_与测过且低分区分`() {
        // 行为：stats null / items=0 → probed=false（「没测过」——档位判定靠标志
        // 区分「测过且低」；死链率再高也不误判成探针失败）
        assertFalse(richnessFold(null, deadRate = 0.9).probed)
        assertFalse(richnessFold(stats(items = 0), deadRate = 0.9).probed)
    }

    @Test
    fun `丰度折算全映射_密度视频规整信息量死链`() {
        // 行为：字段逐项映射（spanSec→天；episodeHitRate=样本命中比例；死链率透传）
        val r = richnessFold(stats(), deadRate = 0.2)
        assertTrue(r.probed)
        assertEquals(100, r.sampleItems)
        assertEquals(35.0, r.sampleSpanDays, EPS)
        assertEquals(1.0, r.videoShare, EPS)
        assertEquals(1.0, r.captionNonEmptyRate, EPS)
        assertEquals(80.0, r.avgCaptionLen, EPS)
        assertEquals(0.2, r.deadRate, EPS)
    }

    @Test
    fun `剧集规整率等于caption样本解析命中比例`() {
        // 行为：3/4 命中（parseEpisodeMark 认「第N集/EP.N」类标记）→ 0.75；
        // 空样本 → 0（不除零）
        val r = richnessFold(stats(captions = listOf("第1集", "EP.2", "第3集", "无标记闲聊")), 0.0)
        assertEquals(0.75, r.episodeHitRate, EPS)
        val rEmpty = richnessFold(stats(captions = emptyList()), 0.0)
        assertEquals(0.0, rEmpty.episodeHitRate, EPS)
    }

    @Test
    fun `折算结果可直接进R评分_满分样本得满分`() {
        // 行为：折算 → richnessScore 闭环（密度 100/35天×7=20/周 恰满、video 满、
        // 规整满、caption 满信息量、零死链 → R=1.0）——B4 聚合链与 B1 评分卡接得上
        val r = richnessFold(
            stats(items = 100, spanSec = 35L * 86_400, videoShare = 1.0, nonEmptyRate = 1.0, avgLen = 80.0,
                captions = listOf("第1集", "第2集")),
            deadRate = 0.0,
        )
        assertEquals(1.0, IndexTierPolicy.richnessScore(r), EPS)
    }
}
