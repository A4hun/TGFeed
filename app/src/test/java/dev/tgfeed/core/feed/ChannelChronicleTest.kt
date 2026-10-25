package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R204 TL-5 频道编年史纯函数单测（[ChannelChronicle]）：月历直方图 → 年块时间轴
 * （年归属/峰日/覆盖外年块/热度档位/摘要文案/会话缓存 TTL）。
 * 网关月历拉取 = 设备验证面。
 */
class ChannelChronicleTest {

    private companion object {
        const val DAY = 86_400L
        // 2024-01-01T00:00:00Z = 1_704_067_200（测试日历锚点）
        const val Y2024 = 1_704_067_200L
    }

    @Test
    fun `年块按年折算_新到旧`() {
        // 2024 年 3 天 + 2025 年 1 天（now 取 2025 年中）
        val nowMs = (Y2024 + 500 * DAY) * 1000L // 2025-05-15 附近
        val days = listOf(
            (Y2024 + 10 * DAY).toInt() to 5,
            (Y2024 + 11 * DAY).toInt() to 3,
            (Y2024 + 12 * DAY).toInt() to 7,
            (Y2024 + 400 * DAY).toInt() to 9, // 2025-02-05
        )
        val c = ChannelChronicle.of(days, monthsCovered = 14, genomeMinDateSec = null, nowMs = nowMs)
        assertEquals(2, c.blocks.size)
        assertEquals(2025, c.blocks.first().year)
        assertEquals(2024, c.blocks.last().year)
        assertEquals(9, c.blocks.first().count)
        assertEquals(15, c.blocks.last().count)
        assertTrue(c.blocks.first().hasCalendar)
        assertEquals(2024, c.earliestYear)
    }

    @Test
    fun `基因下界补出覆盖外年块`() {
        // 月历只覆盖 2024+，但基因说 2019 年就有帖 → 2019~2023 是无热力的"早年"块
        val nowMs = (Y2024 + 200 * DAY) * 1000L
        val days = listOf((Y2024 + 100 * DAY).toInt() to 4)
        val genome2019 = Y2024 - 5 * 365 * DAY // ≈2019-01
        val c = ChannelChronicle.of(days, monthsCovered = 12, genomeMinDateSec = genome2019, nowMs = nowMs)
        assertEquals(6, c.blocks.size)
        val oldBlock = c.blocks.last()
        assertEquals(2019, oldBlock.year)
        assertTrue(!oldBlock.hasCalendar)
        assertEquals(0, oldBlock.count)
        assertEquals("2019 · 早年", ChannelChronicle.yearHeadline(oldBlock))
        // 摘要带覆盖口径
        assertEquals("从 2019 年到今天 · 详细档案近 12 个月", ChannelChronicle.summaryLine(c))
    }

    @Test
    fun `两源皆无是诚实空态`() {
        val c = ChannelChronicle.of(emptyList(), 0, null, nowMs = 1_800_000_000_000L)
        assertTrue(c.blocks.isEmpty())
        assertNull(c.earliestYear)
        assertNull(ChannelChronicle.summaryLine(c))
    }

    @Test
    fun `峰日为当年最热闹一天并列取最新`() {
        val nowMs = (Y2024 + 200 * DAY) * 1000L
        // 同为 7 条的两天：取更新的一天
        val d1 = (Y2024 + 10 * DAY).toInt()
        val d2 = (Y2024 + 60 * DAY).toInt()
        val days = listOf(d1 to 7, d2 to 7, (Y2024 + 30 * DAY).toInt() to 2)
        val c = ChannelChronicle.of(days, 12, null, nowMs = nowMs)
        assertEquals(d2 + 43_200, c.blocks.first().peakDaySec)
    }

    @Test
    fun `覆盖外年块峰日回落当年元旦午时`() {
        val nowMs = (Y2024 + 200 * DAY) * 1000L
        val c = ChannelChronicle.of(emptyList(), 0, Y2024 - 3 * 365 * DAY, nowMs = nowMs)
        val oldBlock = c.blocks.last() // 2021（约）
        assertTrue(!oldBlock.hasCalendar)
        // 峰日 = 该年 1 月 1 日午时（穿越目标仍可用）
        assertTrue(oldBlock.peakDaySec > 0)
    }

    @Test
    fun `热度档位按全局峰值归一`() {
        // 全局峰值 20：5 条→次低档，20 条→顶档，0→不画
        assertEquals(0f, ChannelChronicle.heatAlpha(0, 20), 1e-6f)
        assertTrue(ChannelChronicle.heatAlpha(1, 20) > 0f)
        assertTrue(ChannelChronicle.heatAlpha(2, 20) >= ChannelChronicle.heatAlpha(1, 20))
        assertTrue(ChannelChronicle.heatAlpha(20, 20) > ChannelChronicle.heatAlpha(10, 20))
        assertEquals(0f, ChannelChronicle.heatAlpha(5, 0), 1e-6f) // 峰值 0 防除零
    }

    @Test
    fun `年块数量封顶`() {
        // 基因说 1995 年建频道（30 年）→ 年块封顶 MAX_YEAR_BLOCKS
        val nowMs = (Y2024 + 200 * DAY) * 1000L
        val c = ChannelChronicle.of(emptyList(), 0, Y2024 - 30 * 365 * DAY, nowMs = nowMs)
        assertEquals(ChannelChronicle.MAX_YEAR_BLOCKS, c.blocks.size)
    }

    @Test
    fun `年首锚点_闰年与平年`() {
        // 2024-01-01T00:00:00Z = 1_704_067_200；2023-01-01 = 1_672_531_200；2000（闰年
        // 但 1/1 在 2/29 之前）= 946_684_800
        assertEquals(1_704_067_200L, ChannelChronicle.utcYearStartSec(2024))
        assertEquals(1_672_531_200L, ChannelChronicle.utcYearStartSec(2023))
        assertEquals(946_684_800L, ChannelChronicle.utcYearStartSec(2000))
    }

    @Test
    fun `会话缓存TTL语义`() {
        // ChannelChronicle.cache 是进程级单例——本测只验 put/cached 的 TTL 门（内容隔离靠 chatId）
        val id = System.nanoTime() // 防与其他用例互踩
        assertNull(ChannelChronicle.cached(id, nowMs = 1_000L))
        ChannelChronicle.put(id, 12, listOf(1_700_000_000 to 3), nowMs = 1_000L)
        assertTrue(ChannelChronicle.cached(id, nowMs = 1_000L + ChannelChronicle.CACHE_TTL_MS - 1) != null)
        assertTrue(ChannelChronicle.cached(id, nowMs = 1_000L + ChannelChronicle.CACHE_TTL_MS + 1) == null)
    }

    @Test
    fun `缓存行非空月数按年月去重`() {
        // 2024-01 两天 + 2024-02 一天 + 2025-01 一天 = 3 个非空月
        val cal = ChannelChronicle.CachedCalendar(
            atMs = 0L,
            monthsCap = 24,
            days = listOf(
                (Y2024 + 1 * DAY).toInt() to 1,
                (Y2024 + 2 * DAY).toInt() to 1,
                (Y2024 + 32 * DAY).toInt() to 1,
                (Y2024 + 366 * DAY).toInt() to 1,
            ),
        )
        assertEquals(3, cal.monthsCovered())
    }
}
