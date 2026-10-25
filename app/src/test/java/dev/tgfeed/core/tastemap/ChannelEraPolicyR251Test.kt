package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * R251 编年史四卷新增纯函数单测（只加不改件——不动 R248 一期用例）：
 * ⑤ 沉寂与复活（7 天断更标记/30 天独立行/末段未归/金矿兜底/行文案）、
 * ③ 巅峰作品（峰日合集/链首/时长离群三证据+去重+上限）、
 * ④ 资源里程碑（创刊+第 N 条门+系列开篇+排序上限）、⑥ 互联行文案。
 */
class ChannelEraPolicyR251Test {

    private fun day(epochDay: Int, count: Int = 1) = ChannelEraPolicy.DayCount(epochDay, count)

    private fun row(
        messageId: Long,
        dateSec: Long = 1_700_000_000L,
        mediaType: Int = 0,
        durationSec: Int = 0,
        albumId: Long = 0L,
        title: String = "",
    ) = ChannelEraPolicy.EraItemRow(messageId, dateSec, mediaType, durationSec, albumId, title)

    private fun utc(): GregorianCalendar = GregorianCalendar(TimeZone.getTimeZone("UTC"))

    /** UTC 日期 → epochDay（测试锚定用） */
    private fun dOf(year: Int, month0: Int, dayOfMonth: Int): Int {
        val cal = utc()
        cal.clear()
        cal.set(year, month0, dayOfMonth)
        return (cal.timeInMillis / 86_400_000L).toInt()
    }

    // ==================== ⑤ 沉寂与复活 ====================

    @Test
    fun `沉寂-7天门槛与标记`() {
        // 间隔恰 7 天=不算断更；>7 天=标记一段
        val days = listOf(day(100), day(107), day(120))
        val hs = ChannelEraPolicy.hiatusesOf(days, nowEpochDay = 120)
        assertEquals(1, hs.size)
        assertEquals(107, hs[0].lastPostDay)
        assertEquals(120, hs[0].returnDay)
        assertEquals(13, hs[0].days)
        assertTrue(hs[0].returned)
    }

    @Test
    fun `沉寂-末段未归与金矿兜底`() {
        // 直方图末日 250、金矿最近活动 258（索引滞后 8 天）——末段起点取金矿日：
        // 距 now=262 只 4 天 ≤7——索引没爬到的新帖不误判「已沉寂」
        val days = listOf(day(245), day(250))
        val hs = ChannelEraPolicy.hiatusesOf(days, nowEpochDay = 262, lastKnownActivityDay = 258)
        assertEquals(0, hs.size)
        // 金矿缺省（0=未知）：末段起点=直方图末日 250 → 悬空 12 天=至今未归段
        val hs2 = ChannelEraPolicy.hiatusesOf(days, nowEpochDay = 262, lastKnownActivityDay = 0)
        assertEquals(1, hs2.size)
        assertEquals(250, hs2[0].lastPostDay)
        assertEquals(12, hs2[0].days)
        assertTrue(!hs2[0].returned) // 末段悬空=至今未归
        // 金矿更旧（200<250）不回退起点
        val hs3 = ChannelEraPolicy.hiatusesOf(days, nowEpochDay = 262, lastKnownActivityDay = 200)
        assertEquals(1, hs3.size)
        assertEquals(250, hs3[0].lastPostDay)
    }

    @Test
    fun `沉寂-当前活跃不出未归段`() {
        val days = listOf(day(100), day(299))
        val hs = ChannelEraPolicy.hiatusesOf(days, nowEpochDay = 300)
        // 中间 gap=199 标记一段；末段 1 天 ≤7 不出
        assertEquals(1, hs.size)
        assertTrue(hs[0].returned)
    }

    @Test
    fun `沉寂-独立行30天档与上限`() {
        val hs = listOf(
            ChannelEraPolicy.Hiatus(10, 60, 50, true),
            ChannelEraPolicy.Hiatus(100, 130, 30, true),
            ChannelEraPolicy.Hiatus(200, 205, 5, true),   // 5 天=轻停更不出行
            ChannelEraPolicy.Hiatus(300, 700, 400, true),
            ChannelEraPolicy.Hiatus(800, 900, 100, true),
            ChannelEraPolicy.Hiatus(950, 990, 40, true),
        )
        val entries = ChannelEraPolicy.dormantEntriesOf(hs)
        assertEquals(3, entries.size) // 400/100/50——30 天档过筛后取最长 3 段
        assertEquals(400, entries[0].days)
        assertEquals(100, entries[1].days)
        assertEquals(50, entries[2].days)
    }

    @Test
    fun `沉寂-摘要行`() {
        val hs = listOf(
            ChannelEraPolicy.Hiatus(10, 60, 50, true),
            ChannelEraPolicy.Hiatus(300, 700, 400, true),
            ChannelEraPolicy.Hiatus(800, 900, 100, false),
        )
        assertEquals("断更 3 次 · 最长 400 天 · 当前未归", ChannelEraPolicy.hiatusSummaryOf(hs))
        assertNull(ChannelEraPolicy.hiatusSummaryOf(emptyList()))
    }

    @Test
    fun `沉寂-行文案跨月与同月`() {
        // 2025-03-15 → 2025-08-18 = 156 天（任务书示例句式）
        val from = dOf(2025, Calendar.MARCH, 15)
        val to = dOf(2025, Calendar.AUGUST, 18)
        val h = ChannelEraPolicy.Hiatus(from, to, to - from, true)
        assertEquals("2025-03~2025-08 沉寂 156 天 → 2025-08-18 回归", ChannelEraPolicy.hiatusLineOf(h))
        // 同月沉寂省区间；未归段尾
        val s = dOf(2025, Calendar.MARCH, 1)
        val e = dOf(2025, Calendar.MARCH, 31)
        assertEquals("2025-03 沉寂 30 天 → 2025-03-31 回归", ChannelEraPolicy.hiatusLineOf(ChannelEraPolicy.Hiatus(s, e, 30, true)))
        assertEquals("2025-03 沉寂 30 天 → 至今未归", ChannelEraPolicy.hiatusLineOf(ChannelEraPolicy.Hiatus(s, e, 30, false)))
    }

    // ==================== ③ 巅峰作品 ====================

    @Test
    fun `峰作-峰日相册证据`() {
        // 峰日 3 条成相册（albumId=9）+2 条散帖：组首=最小 messageId
        val peakDayRows = listOf(
            row(21, 1_700_000_100L, albumId = 9L),
            row(19, 1_700_000_100L, albumId = 9L),
            row(20, 1_700_000_100L, albumId = 9L),
            row(30, 1_700_000_100L),
            row(31, 1_700_000_100L),
        )
        val works = ChannelEraPolicy.peakWorksOf(
            medianDurationSec = 300,
            peakDayCount = 27,
            peakDayRows = peakDayRows,
            durationTop = null,
            chainLeads = emptyList(),
        )
        assertEquals(1, works.size)
        assertEquals(19L, works[0].messageId)
        assertTrue(works[0].headline.contains("峰日合集"))
        assertTrue(works[0].reason.contains("峰日 27 条"))
        assertTrue(works[0].reason.contains("相册 3 张"))
        // era 标签=半年桶
        assertTrue(works[0].era.endsWith("上半年") || works[0].era.endsWith("下半年"))
    }

    @Test
    fun `峰作-链首与时长离群与去重`() {
        // 时长离群：中位 600s，最长 3600s ≥max(600,1800)
        val top = row(99, 1_700_010_000L, durationSec = 3600)
        val works = ChannelEraPolicy.peakWorksOf(
            medianDurationSec = 600,
            peakDayCount = 0,
            peakDayRows = emptyList(),
            durationTop = top,
            chainLeads = listOf(
                ChannelEraPolicy.ChainLead(episodes = 12, firstDateSec = 1_700_005_000L, firstMessageId = 50L, firstTitle = "某剧"),
                ChannelEraPolicy.ChainLead(episodes = 3, firstDateSec = 1_700_006_000L, firstMessageId = 60L, firstTitle = "短链"),
            ),
        )
        // 链长 3 <5 不出；离群 3600s 出；共 1+1=2 条，时间升序（链首先于离群）
        assertEquals(2, works.size)
        assertEquals(50L, works[0].messageId)
        assertTrue(works[0].headline.contains("12 集"))
        assertTrue(works[0].reason.contains("本地样本证据"))
        assertEquals(99L, works[1].messageId)
        assertTrue(works[1].reason.contains("中位"))
        // 去重：离群条=链首条（同 messageId）时只留链首
        val dup = ChannelEraPolicy.peakWorksOf(
            medianDurationSec = 600,
            peakDayCount = 0,
            peakDayRows = emptyList(),
            durationTop = row(50, 1_700_005_000L, durationSec = 3600),
            chainLeads = listOf(ChannelEraPolicy.ChainLead(12, 1_700_005_000L, 50L, "某剧")),
        )
        assertEquals(1, dup.size)
        assertEquals(50L, dup[0].messageId)
    }

    @Test
    fun `峰作-时长离群门槛双门`() {
        // 中位 100s×3=300 <600 下限——短平快频道不出伪「镇馆」
        val works = ChannelEraPolicy.peakWorksOf(
            medianDurationSec = 100,
            peakDayCount = 0,
            peakDayRows = emptyList(),
            durationTop = row(1, 1_700_000_000L, durationSec = 400),
            chainLeads = emptyList(),
        )
        assertEquals(0, works.size)
        // 中位 0（无时长证据）不出
        assertEquals(
            0,
            ChannelEraPolicy.peakWorksOf(0, 0, emptyList(), row(1, 1_700_000_000L, durationSec = 99999), emptyList()).size,
        )
    }

    @Test
    fun `峰作-全卷上限`() {
        val leads = (0 until 8).map {
            ChannelEraPolicy.ChainLead(episodes = 10 + it, firstDateSec = 1_700_000_000L + it * 100L, firstMessageId = 100L + it, firstTitle = "链$it")
        }
        val works = ChannelEraPolicy.peakWorksOf(0, 0, emptyList(), null, leads)
        // 链证据上限 2 条——全卷 ≤5 自然满足
        assertEquals(2, works.size)
        assertEquals(100L + 7, works[1].messageId) // 时间升序：最晚的链在后
    }

    @Test
    fun `峰作-时长用户语言`() {
        assertEquals("4.2 小时", ChannelEraPolicy.durationLabelOf(15_120))
        assertEquals("42 分钟", ChannelEraPolicy.durationLabelOf(42 * 60))
        assertEquals("48 秒", ChannelEraPolicy.durationLabelOf(48))
    }

    // ==================== ④ 资源里程碑 ====================

    @Test
    fun `里程碑-创刊第N条与开篇`() {
        val first = row(1, 1_600_000_000L, title = "开张")
        val nth = listOf(
            100 to row(150, 1_650_000_000L, title = "第100条"),
            1000 to row(900, 1_690_000_000L, title = "第1000条"),
        )
        val ms = ChannelEraPolicy.milestonesOf(
            first = first,
            totalItems = 1000,
            nth = nth,
            chainLeads = listOf(ChannelEraPolicy.ChainLead(20, 1_700_000_000L, 5000L, "某系列")),
        )
        // 创刊→第100→第1000→系列开篇：时间升序
        assertEquals(4, ms.size)
        assertEquals("创刊", ms[0].label)
        assertEquals("第 100 条", ms[1].label)
        assertEquals("第 1000 条", ms[2].label)
        assertEquals("系列开篇", ms[3].label)
        assertTrue(ms[3].sub!!.contains("20 集连播"))
        assertEquals(5000L, ms[3].messageId)
    }

    @Test
    fun `里程碑-总门拦超步与空行跳过`() {
        // totalItems=300：第 500/1000 条超门不出；第 100 条查无行（null）跳过
        val ms = ChannelEraPolicy.milestonesOf(
            first = row(1, 1_600_000_000L),
            totalItems = 300,
            nth = listOf(100 to null, 500 to row(999, 1_700_000_000L), 1000 to row(1000, 1_700_000_000L)),
            chainLeads = emptyList(),
        )
        assertEquals(1, ms.size)
        assertEquals("创刊", ms[0].label)
    }

    @Test
    fun `里程碑-上限8`() {
        // 6 步进+创刊+2 条链开篇=9 → 封顶 8
        val nth = ChannelEraPolicy.MILESTONE_STEPS.map { n -> n to row(n.toLong(), 1_700_000_000L + n) }
        val leads = listOf(
            ChannelEraPolicy.ChainLead(20, 1_600_500_000L, 5000L, "系列A"),
            ChannelEraPolicy.ChainLead(12, 1_600_600_000L, 6000L, "系列B"),
        )
        val ms = ChannelEraPolicy.milestonesOf(row(1, 1_600_000_000L), 100_000, nth, leads)
        assertEquals(ChannelEraPolicy.MILESTONE_MAX, ms.size)
    }

    // ==================== ⑥ 互联行文案 ====================

    @Test
    fun `互联-主类前置只列非零`() {
        assertEquals("互转 23 次 · 共现 12 · 互链 4", ChannelEraPolicy.interlinkLineOf(23, 4, 12))
        assertEquals("共现 45 次", ChannelEraPolicy.interlinkLineOf(0, 0, 45))
        assertEquals("互链 3 次 · 共现 1", ChannelEraPolicy.interlinkLineOf(0, 3, 1))
        assertEquals("", ChannelEraPolicy.interlinkLineOf(0, 0, 0))
    }

    // ==================== 日期标签确定性 ====================

    @Test
    fun `日期-秒与天标签UTC确定`() {
        val cal = utc()
        cal.clear()
        cal.set(2021, Calendar.MAY, 30)
        assertEquals("2021-05-30", ChannelEraPolicy.dateLabelOf(cal.timeInMillis / 1000L))
        assertEquals("", ChannelEraPolicy.dateLabelOf(0L))
    }
}
