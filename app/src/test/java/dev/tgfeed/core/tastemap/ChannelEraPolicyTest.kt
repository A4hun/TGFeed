package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R248 编年史纯函数单测（R246 §3.2 一期三件）：节奏判型四型+样本地板/判词拼装
 * 缺段跳过/演化时代行合并/转型点摆幅门槛/月度柱/周内惯例/UTC 标签确定性。
 */
class ChannelEraPolicyTest {

    private fun day(epochDay: Int, count: Int = 1) = ChannelEraPolicy.DayCount(epochDay, count)

    private fun bucket(startSec: Long, total: Int, v: Int, p: Int, a: Int = total - v - p) =
        ChannelEraPolicy.EraBucket(startSec, total, v, p, a.coerceAtLeast(0))

    // ==================== 节奏判型 ====================

    @Test
    fun `节奏-样本不足`() {
        // 覆盖 30 天（<90）——不判型
        val thin = (0 until 30).map { day(it) }
        assertEquals(ChannelEraPolicy.Rhythm.THIN, ChannelEraPolicy.rhythmOf(thin))
        // 覆盖够但总量 <30 条
        val thin2 = (0 until 100).map { day(it * 3, 1) }.take(10)
        assertEquals(ChannelEraPolicy.Rhythm.THIN, ChannelEraPolicy.rhythmOf(thin2))
    }

    @Test
    fun `节奏-日更型`() {
        // 200 天里 180 天有帖（90% ≥ 60%）
        val days = (0 until 200).map { day(it) }.filterIndexed { i, _ -> i % 10 != 0 }
        assertEquals(ChannelEraPolicy.Rhythm.DAILY, ChannelEraPolicy.rhythmOf(days))
    }

    @Test
    fun `节奏-周报型`() {
        // 每 7 天一帖×60 周：覆盖 420 天、活跃率 14%——中位间隔 7 ≤16
        val days = (0 until 60).map { day(it * 7, 3) }
        assertEquals(ChannelEraPolicy.Rhythm.WEEKLY, ChannelEraPolicy.rhythmOf(days))
    }

    @Test
    fun `节奏-季节型`() {
        // 3 年：每年只 12月/1月/2月/3月 各发 10 条（top4 月份份额 100%、覆盖 ≥540 天）
        val cal = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC"))
        val days = ArrayList<ChannelEraPolicy.DayCount>()
        for (year in 2021..2023) {
            for (month in listOf(11, 0, 1, 2)) { // 12月/1月/2月/3月
                cal.clear()
                cal.set(year, month, 15)
                val d = (cal.timeInMillis / 86_400_000L).toInt()
                days += day(d, 10)
            }
        }
        days.sortBy { it.epochDay }
        assertEquals(ChannelEraPolicy.Rhythm.SEASONAL, ChannelEraPolicy.rhythmOf(days))
    }

    @Test
    fun `节奏-爆发型兜底`() {
        // 覆盖 2 年、中位间隔 >16、月份均匀（月-年 top4 <55%）——方差离群兜底
        val days = ArrayList<ChannelEraPolicy.DayCount>()
        var d = 0
        for (i in 0 until 40) {
            days += day(d, i % 4 + 1) // 间隔 30 天
            d += 30
        }
        assertEquals(ChannelEraPolicy.Rhythm.BURSTY, ChannelEraPolicy.rhythmOf(days))
    }

    // ==================== 判词与摘要 ====================

    @Test
    fun `判词-满字段`() {
        // 视频 80%：视觉向；日更；峰段与趋势在场
        val buckets = listOf(
            bucket(1_500_000_000L, 100, 20, 70, 10),
            bucket(1_600_000_000L, 400, 360, 30, 10),
        )
        val days = (0 until 200).map { day(it) }
        val dg = ChannelEraPolicy.digest(ChannelEraPolicy.EraInput(buckets, days))
        assertNotNull(dg.verdict)
        assertTrue(dg.verdict!!.contains("视觉向"))
        assertTrue(dg.verdict!!.contains("日更型"))
        assertTrue(dg.verdict!!.contains("高峰在"))
        assertEquals(2, dg.evolution.size)
        assertTrue(dg.evolution[1].isPeak)
    }

    @Test
    fun `判词-空输入不出伪结论`() {
        val dg = ChannelEraPolicy.digest(ChannelEraPolicy.EraInput(emptyList(), emptyList()))
        assertNull(dg.verdict)
        assertEquals(ChannelEraPolicy.Rhythm.THIN, dg.rhythm)
        assertEquals(0, dg.evolution.size)
        assertNull(dg.orientation)
    }

    @Test
    fun `判词-取向三分支`() {
        val d = (0 until 100).map { day(it) }
        assertEquals("图文向", ChannelEraPolicy.orientationOf(listOf(bucket(0L, 100, 10, 80, 10))))
        assertEquals("听觉向", ChannelEraPolicy.orientationOf(listOf(bucket(0L, 100, 10, 10, 80))))
        assertEquals("混合向", ChannelEraPolicy.orientationOf(listOf(bucket(0L, 100, 40, 35, 25))))
        assertNull(ChannelEraPolicy.orientationOf(listOf(bucket(0L, 0, 0, 0, 0))))
    }

    @Test
    fun `趋势-提速与放缓`() {
        // 近 90 天 100 条 vs 前 90 天 20 条（5 倍）——提速
        val up = (0 until 180).map { day(it, if (it >= 90) 1 else 0) }.filter { it.count > 0 }
        assertEquals("近期提速", ChannelEraPolicy.trendLabelOf(up))
        // 前 90 天密（172 条）+ 近 90 天疏（9 条）——放缓（lastDay 在近窗内）
        val down = (0 until 90).map { day(it, 2) } + listOf(day(175, 1))
        assertEquals("近期放缓", ChannelEraPolicy.trendLabelOf(down))
        // 持平不出段
        val flat = (0 until 180).map { day(it, 1) }
        assertNull(ChannelEraPolicy.trendLabelOf(flat))
    }

    // ==================== 演化与转型 ====================

    @Test
    fun `演化-超限合并更早段`() {
        // 12 桶 > 8 → 「更早」合并头 5 桶 + 最新 7 段
        val buckets = (0 until 12).map { bucket(it * ChannelEraPolicy.HALF_YEAR_SEC, 10, 5, 5) }
        val segs = ChannelEraPolicy.evolutionOf(buckets)
        assertEquals(8, segs.size)
        assertEquals("更早", segs[0].label)
        assertEquals(50, segs[0].total)
    }

    @Test
    fun `转型-摆幅过门槛才出句`() {
        // 前段图文 70%，后段视频 75%（图文跌到 20%——摆幅 55 ≥30）→ 转型句
        val buckets = listOf(
            bucket(1_500_000_000L, 100, 25, 70, 5),
            bucket(1_600_000_000L, 100, 75, 20, 5),
        )
        val ts = ChannelEraPolicy.transitionsOf(buckets)
        assertEquals(1, ts.size)
        assertTrue(ts[0].contains("转向视频"))
        assertTrue(ts[0].contains("图文 70%→20%"))
        // 主导类型变化但摆幅不足（视频 45% vs 图文 40%——新主导仅抬 15 <30）→ 不出句
        val soft = listOf(
            bucket(1_500_000_000L, 100, 45, 40, 15),
            bucket(1_600_000_000L, 100, 40, 45, 15),
        )
        assertEquals(0, ChannelEraPolicy.transitionsOf(soft).size)
    }

    @Test
    fun `演化-峰段标记`() {
        val buckets = listOf(
            bucket(1_500_000_000L, 100, 50, 50),
            bucket(1_600_000_000L, 300, 150, 150),
            bucket(1_700_000_000L, 200, 100, 100),
        )
        val segs = ChannelEraPolicy.evolutionOf(buckets)
        assertEquals(3, segs.size)
        assertTrue(segs[1].isPeak)
        assertFalse(segs[0].isPeak)
        assertFalse(segs[2].isPeak)
    }

    // ==================== 月度柱与周内惯例 ====================

    @Test
    fun `月度-聚合与截尾`() {
        val cal = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC"))
        val days = ArrayList<ChannelEraPolicy.DayCount>()
        cal.clear()
        cal.set(2023, 0, 5)
        var d = (cal.timeInMillis / 86_400_000L).toInt()
        // 2023-01 起每 10 天一帖×90 站 → 触达 ~31 个月，截尾 24（2023-08 起）
        repeat(90) {
            days += day(d, 1)
            d += 10
        }
        val monthly = ChannelEraPolicy.monthlyOf(days, ChannelEraPolicy.MONTHLY_MAX)
        assertEquals(24, monthly.size)
        assertEquals(2023, monthly.first().year)
        assertEquals(7, monthly.first().month1) // 触达 30 个月 → 截尾窗自 2023-07 起
    }

    @Test
    fun `周内-惯例周五`() {
        // 全部发在周五（weekday=5：epochDay ≡ 1 mod 7 时 (d+4)%7=5）
        val days = (0 until 60).filter { (it + 4) % 7 == 5 }.map { day(it, 2) }
        assertEquals("惯例周五更新", ChannelEraPolicy.weekdayLabelOf(days))
        // 均匀分布不出惯例
        val flat = (0 until 91).map { day(it, 1) }
        assertNull(ChannelEraPolicy.weekdayLabelOf(flat))
    }

    // ==================== 标签确定性 ====================

    @Test
    fun `标签-半年桶UTC确定`() {
        // 2024-01-02（UTC）落在「2024上半年」桶起点附近——标签只看年+月半
        val cal = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(2024, java.util.Calendar.JANUARY, 2)
        val sec = cal.timeInMillis / 1000L
        assertEquals("2024上半年", ChannelEraPolicy.halfYearLabel(sec))
        cal.clear()
        cal.set(2024, java.util.Calendar.JULY, 2)
        assertEquals("2024下半年", ChannelEraPolicy.halfYearLabel(cal.timeInMillis / 1000L))
    }
}
