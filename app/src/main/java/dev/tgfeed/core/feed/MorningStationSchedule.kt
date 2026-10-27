package dev.tgfeed.core.feed

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

// ---------------- R392 #6① 晨间定时:闸点时间纯函数 ----------------
//
// 定位(r392b_notes §3.1):晨间定时(每日 HH:mm→生成今日单→开台)的调度层
// 时间函数——时钟/时区全注入(buildTodayQueue 同纪律,测试确定性不赌机器
// 时间);消费方=FeedPlayChainPart 晨间循环(VM 域 DailyDigest 判词:进程内
// 协程闸点循环)。判词定案(r392b_notes §1.4):WorkManager 零依赖(项目
// 「不加新依赖」边界)/AlarmManager 零先例(不引新系统权限面)——DailyDigest
// (R63)骨架平移,零新后台常驻。
//
// 已知局限(DailyDigest 同款如实注记):app 不开/进程被杀→当日闸点不触发,
// 下次启动补查兜底——补查仅迟到窗口([LATE_WINDOW_MS])内补开,窗外=晨间
// 已过明日再开(诚实:下午补开晨间电台=打扰非服务)。

/**
 * 晨间定时的闸点时间函数(纯函数,单源常量)。
 * 闸点语义:每日 [morningStationMinuteOfDay](分钟数 0..1439,AppPrefs 键)
 * ——「每日 HH:mm」的存储形态;显示层自行拆 HH:mm。
 */
object MorningStationSchedule {

    /** 迟到窗口:过闸后该窗内仍补开(启动补查/到点醒晚共用);窗外=晨间已过跳过 */
    const val LATE_WINDOW_MS = 4L * 60 * 60_000L

    /** 今天闸点(epoch ms,注入时区)——「到点」判定的基准 */
    fun gateTodayMs(minuteOfDay: Int, zone: ZoneId): Long {
        val mod = (minuteOfDay.coerceIn(0, 1439)) * 60L
        return LocalDate.now(zone)
            .atTime(LocalTime.ofSecondOfDay(mod))
            .atZone(zone).toInstant().toEpochMilli()
    }

    /**
     * 距下一个闸点的毫秒数(DailyDigest msUntilNextGate 同款):今天闸点未过取
     * 今天,已过取明天(LocalDate.plusDays 走日历=夏令时边界正确);兜底最小
     * 1 分钟防时区/时钟边界算出 0 或负数导致忙等。
     */
    fun msUntilNextGate(minuteOfDay: Int, nowMs: Long, zone: ZoneId): Long {
        val mod = (minuteOfDay.coerceIn(0, 1439)) * 60L
        val today = LocalDate.now(zone)
        val gateToday = today
            .atTime(LocalTime.ofSecondOfDay(mod))
            .atZone(zone).toInstant().toEpochMilli()
        val target = if (gateToday > nowMs) gateToday
        else today.plusDays(1)
            .atTime(LocalTime.ofSecondOfDay(mod))
            .atZone(zone).toInstant().toEpochMilli()
        return (target - nowMs).coerceAtLeast(60_000L)
    }

    /** 迟到窗口判定:now 距闸点过窗未超 [LATE_WINDOW_MS](负=未到点不在窗内) */
    fun withinLateWindow(gateMs: Long, nowMs: Long): Boolean {
        val delta = nowMs - gateMs
        return delta in 0..LATE_WINDOW_MS
    }

    /** now 所在日的本地日期串(yyyy-MM-dd,幂等日期闸的口径——daily digest 同形) */
    fun todayString(nowMs: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toString()

    /** now 所在日的本地 00:00(epoch ms)——「当日已有今日台」新鲜度判定基准 */
    fun todayStartMs(nowMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()
}
