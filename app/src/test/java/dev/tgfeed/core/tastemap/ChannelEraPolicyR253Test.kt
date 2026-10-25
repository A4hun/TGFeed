package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R253 热力条点选纯函数单测：时段范围标签（本地钟点两位补零/24 时收口/域外防御）+
 * 点选读数标签（N=直方图口径时段总数/0 条·域外·错长=null 诚实缺省）。
 * R248/R250/R251 既有用例零改动（本文件只锚定 R253 新增成员）。
 */
class ChannelEraPolicyR253Test {

    @Test
    fun `时段标签-常规两位补零`() {
        assertEquals("22:00~23:00", ChannelEraPolicy.hourRangeLabelOf(22))
        assertEquals("00:00~01:00", ChannelEraPolicy.hourRangeLabelOf(0))
        assertEquals("09:00~10:00", ChannelEraPolicy.hourRangeLabelOf(9))
    }

    @Test
    fun `时段标签-23时收口24`() {
        assertEquals("23:00~24:00", ChannelEraPolicy.hourRangeLabelOf(23))
    }

    @Test
    fun `时段标签-域外防御null`() {
        assertNull(ChannelEraPolicy.hourRangeLabelOf(-1))
        assertNull(ChannelEraPolicy.hourRangeLabelOf(24))
        assertNull(ChannelEraPolicy.hourRangeLabelOf(Int.MIN_VALUE))
    }

    @Test
    fun `读数标签-直方图口径N条`() {
        val hist = IntArray(ChannelEraPolicy.HOURS_PER_DAY)
        hist[22] = 87
        assertEquals("22:00~23:00 · 87 条", ChannelEraPolicy.hourFilterLabelOf(hist, 22))
    }

    @Test
    fun `读数标签-0条时段null`() {
        // 该时段无发布=无可过滤，标签不出（诚实缺省）
        val hist = IntArray(ChannelEraPolicy.HOURS_PER_DAY)
        hist[22] = 5
        assertNull(ChannelEraPolicy.hourFilterLabelOf(hist, 21))
    }

    @Test
    fun `读数标签-域外与错长直方图null`() {
        val hist = IntArray(ChannelEraPolicy.HOURS_PER_DAY)
        hist[0] = 3
        assertNull(ChannelEraPolicy.hourFilterLabelOf(hist, -1))
        assertNull(ChannelEraPolicy.hourFilterLabelOf(hist, 24))
        assertNull(ChannelEraPolicy.hourFilterLabelOf(IntArray(3) { 10 }, 1))
    }

    @Test
    fun `读数标签-满槽边界`() {
        val hist = IntArray(ChannelEraPolicy.HOURS_PER_DAY) { 1 }
        assertEquals("23:00~24:00 · 1 条", ChannelEraPolicy.hourFilterLabelOf(hist, 23))
    }
}
