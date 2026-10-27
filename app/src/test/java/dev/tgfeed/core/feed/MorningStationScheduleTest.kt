package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * R392 #6① 晨间闸点时间函数行为例(MorningStationSchedule 纯函数 JVM 直测
 * ——时钟/时区注入=确定性,r392b_notes §3.1;DailyDigest msUntilNextGate
 * 同款判词的独立可测化)。
 */
class MorningStationScheduleTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    // ---------------- msUntilNextGate(到点调度) ----------------

    @Test
    fun `今天闸点未过_取今天闸点`() {
        // now=今天 03:00,闸 07:30 → 距闸 4.5h(不是明天)
        val now = LocalDate.now(zone).atTime(3, 0).atZone(zone).toInstant().toEpochMilli()
        val delay = MorningStationSchedule.msUntilNextGate(450, now, zone)
        assertEquals("未过闸=今天闸点", 4L * 60 + 30, delay / 60_000L)
    }

    @Test
    fun `今天闸点已过_取明天闸点`() {
        // now=今天 10:00,闸 07:30 → 明天 07:30,距 21.5h
        val now = LocalDate.now(zone).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val delay = MorningStationSchedule.msUntilNextGate(450, now, zone)
        assertEquals("过闸=明天闸点(LocalDate plusDays 走日历)", 21L * 60 + 30, delay / 60_000L)
    }

    @Test
    fun `兜底下限_永不小于1分钟防忙等`() {
        val gate = MorningStationSchedule.gateTodayMs(450, zone)
        // now=闸点-500ms(时钟边界/早醒):今天闸点仍>now → delay≈500ms → 兜底 1 分钟
        val delay = MorningStationSchedule.msUntilNextGate(450, gate - 500, zone)
        assertEquals("边界值兜底 60s(DailyDigest 同款防忙等)", 60_000L, delay)
    }

    // ---------------- withinLateWindow(迟到窗) ----------------

    @Test
    fun `迟到窗_过闸1小时在窗内`() {
        val gate = 1_000_000L
        assertTrue("窗内", MorningStationSchedule.withinLateWindow(gate, gate + 3_600_000L))
    }

    @Test
    fun `迟到窗_未到点不在窗内_超窗不在窗内`() {
        val gate = 1_000_000L
        assertFalse("未到点(负 delta)", MorningStationSchedule.withinLateWindow(gate, gate - 60_000L))
        assertFalse("过闸超 4h 窗", MorningStationSchedule.withinLateWindow(gate, gate + MorningStationSchedule.LATE_WINDOW_MS + 1))
        assertTrue("窗沿恰在内(闭区间)", MorningStationSchedule.withinLateWindow(gate, gate + MorningStationSchedule.LATE_WINDOW_MS))
    }

    // ---------------- gateTodayMs(闸点构造) ----------------

    @Test
    fun `闸点构造_分钟数折算本地HHmm`() {
        val gate = MorningStationSchedule.gateTodayMs(450, zone)
        val zdt = Instant.ofEpochMilli(gate).atZone(zone)
        assertEquals("450 分位=07:30", LocalTime.of(7, 30), zdt.toLocalTime())
        assertEquals("闸点=今天", LocalDate.now(zone), zdt.toLocalDate())
    }

    @Test
    fun `闸点构造_越界分钟数coerce到1439`() {
        val gate = MorningStationSchedule.gateTodayMs(2000, zone)
        assertEquals("越界 coerce 23:59", LocalTime.of(23, 59), Instant.ofEpochMilli(gate).atZone(zone).toLocalTime())
    }

    // ---------------- todayString / todayStartMs(幂等日期闸口径) ----------------

    @Test
    fun `今日串与今日起点_时区感知`() {
        // 固定瞬间 2026-10-25T20:00:00Z:上海=10-26 04:00,UTC=10-25 20:00——同瞬
        // 间不同时区不同「今天」(幂等日期闸的时区口径)
        val now = Instant.parse("2026-10-25T20:00:00Z").toEpochMilli()
        assertEquals("上海日界", "2026-10-26", MorningStationSchedule.todayString(now, zone))
        assertEquals("UTC 日界", "2026-10-25", MorningStationSchedule.todayString(now, ZoneId.of("UTC")))
        // 上海今日 00:00 = 2026-10-25T16:00:00Z(前一日 UTC 下午)
        assertEquals(
            "上海今日起点",
            Instant.parse("2026-10-25T16:00:00Z").toEpochMilli(),
            MorningStationSchedule.todayStartMs(now, zone),
        )
    }
}
