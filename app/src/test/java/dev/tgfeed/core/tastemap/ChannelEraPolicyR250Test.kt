package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R250 节奏微图纯函数单测：24h 小时直方图聚合（缺槽补零/域外防御/同槽累加）+
 * 峰时标签（小样本不出伪结论/确定性破平/长度防御）。R248/R251 既有用例零改动
 * （本文件只锚定 R250 新增成员）。
 */
class ChannelEraPolicyR250Test {

    private fun hour(h: Int, c: Int) = ChannelEraPolicy.HourCount(h, c)

    @Test
    fun `直方图-缺槽补零`() {
        val hist = ChannelEraPolicy.hourHistogramOf(listOf(hour(22, 5)))
        assertEquals(ChannelEraPolicy.HOURS_PER_DAY, hist.size)
        assertEquals(5, hist[22])
        assertEquals(0, hist[0])
        assertEquals(5, hist.sum())
    }

    @Test
    fun `直方图-域外行防御丢弃`() {
        val hist = ChannelEraPolicy.hourHistogramOf(listOf(hour(-1, 3), hour(24, 7), hour(0, 2)))
        assertEquals(ChannelEraPolicy.HOURS_PER_DAY, hist.size)
        assertEquals(2, hist[0])
        assertEquals(2, hist.sum())
    }

    @Test
    fun `直方图-多行同槽累加`() {
        val hist = ChannelEraPolicy.hourHistogramOf(listOf(hour(9, 4), hour(9, 6), hour(10, 1)))
        assertEquals(10, hist[9])
        assertEquals(1, hist[10])
    }

    @Test
    fun `峰时-小样本不出`() {
        // 5 条 < RHYTHM_MIN_ITEMS(30)——小样本不冒充作息
        val hist = ChannelEraPolicy.hourHistogramOf(listOf(hour(22, 5)))
        assertNull(ChannelEraPolicy.peakHourLabelOf(hist))
    }

    @Test
    fun `峰时-峰槽标签`() {
        val rows = (0 until 24).map { hour(it, 2) } + hour(22, 40)
        assertEquals("22 时最活跃", ChannelEraPolicy.peakHourLabelOf(ChannelEraPolicy.hourHistogramOf(rows)))
    }

    @Test
    fun `峰时-平峰取最小钟点`() {
        val hist = ChannelEraPolicy.hourHistogramOf(listOf(hour(3, 30), hour(17, 30)))
        assertEquals("3 时最活跃", ChannelEraPolicy.peakHourLabelOf(hist))
    }

    @Test
    fun `峰时-非24长防御null`() {
        assertNull(ChannelEraPolicy.peakHourLabelOf(IntArray(3) { 100 }))
    }

    @Test
    fun `峰时-零量防御null`() {
        assertNull(ChannelEraPolicy.peakHourLabelOf(IntArray(ChannelEraPolicy.HOURS_PER_DAY)))
    }
}
