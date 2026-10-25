package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R204 WK-2 宇宙作息表单测（[UniverseRhythm] 纯格式化 + 快照往返）：
 * SQL rollup（全表 GROUP BY）= 设备验证面，JVM 不建库。
 */
class UniverseRhythmTest {

    private fun rhythm(
        hours: IntArray,
        weekdays: IntArray = IntArray(7),
        channels: Int = 203,
        items: Long = 4_126_000L,
    ) = UniverseRhythm.Rhythm(
        builtAtMs = 1_800_000_000L,
        tzOffsetSec = 8 * 3600,
        channels = channels,
        items = items,
        hours = hours,
        weekdays = weekdays,
        matrix = IntArray(24 * 7),
    )

    @Test
    fun `峰值小时与沉睡小时`() {
        val h = IntArray(24) { it * 10 }
        h[23] = 500 // 23 点是绝对峰
        h[4] = 1 // 凌晨 4 点几乎沉睡
        val r = rhythm(h)
        assertEquals(23, UniverseRhythm.peakHour(r))
        assertEquals(4, UniverseRhythm.quietHour(r))
        assertEquals(23, UniverseRhythm.peakHour(r))
    }

    @Test
    fun `全零矩阵不出伪结论`() {
        val r = rhythm(IntArray(24), channels = 0, items = 0)
        assertFalse(r.hasData())
        assertEquals(-1, UniverseRhythm.peakHour(r))
        assertEquals(-1, UniverseRhythm.quietHour(r))
        assertTrue(UniverseRhythm.hourBars(r).all { it == 0f })
    }

    @Test
    fun `峰值星期`() {
        val wd = IntArray(7) { 100 }
        wd[5] = 900 // 周五爆更
        assertEquals(5, UniverseRhythm.peakWeekday(rhythm(IntArray(24), wd)))
        assertEquals("五", UniverseRhythm.WEEKDAY_LABELS[UniverseRhythm.peakWeekday(rhythm(IntArray(24), wd))])
    }

    @Test
    fun `柱状归一化峰值为一`() {
        val h = IntArray(24)
        h[9] = 300
        h[10] = 150
        val bars = UniverseRhythm.hourBars(rhythm(h))
        assertEquals(1f, bars[9], 1e-6f)
        assertEquals(0.5f, bars[10], 1e-6f)
    }

    @Test
    fun `覆盖足注带万格式`() {
        assertEquals("基于本地深索引 203 个频道 · 412.6 万条", UniverseRhythm.coverageLine(rhythm(IntArray(24))))
        val small = rhythm(IntArray(24), channels = 3, items = 812)
        assertEquals("基于本地深索引 3 个频道 · 812 条", UniverseRhythm.coverageLine(small))
    }

    @Test
    fun `快照往返与陈旧判定`() {
        val f = File.createTempFile("universe_rhythm", ".json")
        val h = IntArray(24) { 7 * it }
        val wd = IntArray(7) { 3 * it }
        val matrix = IntArray(24 * 7) { it }
        val r = UniverseRhythm.Rhythm(1_234_567L, 28_800, 88, 999_999L, h, wd, matrix)
        UniverseRhythm.saveSnapshot(f, r)
        val back = UniverseRhythm.loadSnapshot(f)
        assertTrue(back != null)
        // IntArray 字段逐一比对（data class 对数组是引用等，快照往返须逐字段核）
        assertEquals(r.builtAtMs, back!!.builtAtMs)
        assertEquals(r.tzOffsetSec, back.tzOffsetSec)
        assertEquals(r.channels, back.channels)
        assertEquals(r.items, back.items)
        assertTrue(r.hours.contentEquals(back.hours))
        assertTrue(r.weekdays.contentEquals(back.weekdays))
        assertTrue(r.matrix.contentEquals(back.matrix))
        // builtAt 1_234_567 远超 TTL → 陈旧
        assertTrue(back.isStale(nowMs = System.currentTimeMillis()))
        assertFalse(back.isStale(nowMs = 1_234_567L + 1000L))
        f.delete()
    }

    @Test
    fun `坏快照按无快照处理`() {
        val f = File.createTempFile("universe_rhythm", ".json")
        f.writeText("not-json")
        assertNull(UniverseRhythm.loadSnapshot(f))
        f.delete()
    }
}
