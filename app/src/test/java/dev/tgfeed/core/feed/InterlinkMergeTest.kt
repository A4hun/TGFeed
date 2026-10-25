package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R283 InterlinkMerge（编年史⑥互联卷数据底座·纯逻辑件）单测：
 * 时间线去重/排序/截断、频道对聚合方向归位/首末日期/跨度、伙伴榜归组/排序/口径——
 * SQL 壳喂行的下游语义全锚。
 */
class InterlinkMergeTest {

    private fun dir(src: Long, sMsg: Long, sDate: Long, dst: Long, dMsg: Long, dDate: Long) =
        InterlinkEvidence(src, sMsg, sDate, dst, dMsg, dDate, mediaType = 0, coOccur = false)

    private fun co(a: Long, aMsg: Long, aDate: Long, b: Long, bMsg: Long, bDate: Long) =
        InterlinkEvidence(minOf(a, b), if (a <= b) aMsg else bMsg, if (a <= b) aDate else bDate,
            maxOf(a, b), if (a <= b) bMsg else aMsg, if (a <= b) bDate else aDate,
            mediaType = 0, coOccur = true)

    // ---------------- 时间线 ----------------

    @Test
    fun `时间线按互联开始时刻升序且去重`() {
        val rows = listOf(
            dir(-1, 10, 1000, -2, 20, 1200),
            dir(-1, 10, 1000, -2, 20, 1200), // 跨臂重复行
            dir(-1, 11, 800, -2, 21, 900), // 更早
            co(-1, 12, 1500, -2, 22, 1600),
        )
        val t = InterlinkMerge.timeline(rows, 64)
        assertEquals(3, t.size) // 重复行去重
        assertEquals(800L, t[0].pointDateSec) // 升序
        assertEquals(1000L, t[1].pointDateSec)
        assertTrue(t[2].coOccur)
    }

    @Test
    fun `时间线截断与空输入`() {
        val rows = (1..10).map { dir(-1, it.toLong(), it * 100L, -2, (it + 50).toLong(), it * 100L + 10) }
        assertEquals(3, InterlinkMerge.timeline(rows, 3).size)
        assertTrue(InterlinkMerge.timeline(emptyList(), 3).isEmpty())
        assertTrue(InterlinkMerge.timeline(rows, 0).isEmpty())
    }

    // ---------------- 频道对聚合 ----------------

    @Test
    fun `聚合方向归位与首末日期`() {
        val rows = listOf(
            dir(-1, 10, 1000, -2, 20, 1200), // -1 是源 → aToB
            dir(-1, 11, 2000, -2, 21, 2200), // aToB
            dir(-2, 30, 500, -1, 12, 700),   // bToA
            co(-1, 13, 3000, -2, 23, 3100),  // 共现
            dir(-9, 99, 100, -8, 98, 110),   // 无关对（防御：不计）
        )
        val s = InterlinkMerge.summarize(-1, -2, rows, truncated = false)
        assertEquals(2, s.aToB)
        assertEquals(1, s.bToA)
        assertEquals(1, s.coOccur)
        assertEquals(4, s.total)
        assertEquals(500L, s.firstDateSec) // bToA 行的 point=min(500,700)
        assertEquals(3000L, s.lastDateSec)
        assertEquals((3000L - 500L) / 86400L, s.spanDays.toLong())
        assertFalse(s.truncated)
    }

    @Test
    fun `聚合truncated透传与无证据零值`() {
        val s = InterlinkMerge.summarize(-1, -2, emptyList(), truncated = true)
        assertEquals(0, s.total)
        assertEquals(0L, s.firstDateSec)
        assertEquals(0L, s.lastDateSec)
        assertEquals(0, s.spanDays)
        assertTrue(s.truncated)
    }

    // ---------------- 伙伴榜 ----------------

    @Test
    fun `伙伴榜三类口径归组与排序`() {
        val rows = listOf(
            dir(-9, 1, 100, -1, 2, 200),  // -9 是源、self(-1) 收编 → theySourced
            dir(-9, 3, 300, -1, 4, 400),  // theySourced 再+1
            dir(-1, 5, 500, -7, 6, 600),  // self 是源、-7 收编 → weSourced
            co(-1, 7, 700, -5, 8, 800),   // 与 -5 共现
        )
        val ps = InterlinkMerge.partners(-1, rows, 24)
        assertEquals(3, ps.size)
        assertEquals(-9L, ps[0].chatId) // total=2 居首
        assertEquals(2, ps[0].theySourced)
        assertEquals(0, ps[0].weSourced)
        assertEquals(100L, ps[0].firstDateSec)
        assertEquals(300L, ps[0].lastDateSec)
        val w = ps.first { it.chatId == -7L }
        assertEquals(1, w.weSourced)
        val c = ps.first { it.chatId == -5L }
        assertEquals(1, c.coOccur)
    }

    @Test
    fun `伙伴榜自环与无关行剔除`() {
        val rows = listOf(
            dir(-1, 1, 100, -1, 2, 200), // 自环防御
            dir(-9, 1, 100, -8, 2, 200), // 与 self 无关
            dir(-9, 3, 300, -1, 4, 400),
        )
        val ps = InterlinkMerge.partners(-1, rows, 24)
        assertEquals(1, ps.size)
        assertEquals(-9L, ps[0].chatId)
    }

    @Test
    fun `伙伴榜截断与确定性（同数chatId升序）`() {
        val rows = listOf(
            dir(-3, 1, 100, -1, 2, 200),
            dir(-9, 1, 100, -1, 2, 200),
            dir(-5, 1, 100, -1, 2, 200),
        )
        val ps = InterlinkMerge.partners(-1, rows, 2)
        assertEquals(2, ps.size)
        assertEquals(-9L, ps[0].chatId) // 同 total=1 按 chatId 升序
        assertEquals(-5L, ps[1].chatId)
    }
}
