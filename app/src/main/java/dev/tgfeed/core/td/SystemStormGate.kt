package dev.tgfeed.core.td

import android.os.SystemClock
import android.util.Log
import dev.tgfeed.core.prefs.AppPrefs
import kotlinx.coroutines.delay
import java.io.File

// ---------------- R208 开机风暴治理（P0，全局启动协调·单源） ----------------

/**
 * R208 系统风暴闸·纯策略（零 Android 依赖，单测锚定）。
 *
 * P0 事故根因：华为平板系统级异常重启（bootreason=abnormal）后 app 被拉起
 * （用户点开/adb——实测华为 ROM 无自动恢复路径）→ 深索引爬虫双节拍器照常
 * 起跑（开机 73s 跑 72.6s=100% 占空比+7.3G 虚存）→ 与系统开机窗（华为全家
 * 服务+其他应用恢复风暴）叠加 → watchdog 复位。**任何真实用户设备都可能复现**。
 *
 * 三道判定（与既有语义的关系：只加「时机闸」，R158 爬虫调度/R196 存储治理/
 * R170b 预热的执行逻辑一律不动，只延后起跑时机）：
 *  1. **开机静默期**：系统 uptime（[SystemClock.elapsedRealtime]）< N 分钟 →
 *     一切后台重活（爬虫/预热/存储治理首轮）持有。这是「系统级开机窗」信号，
 *     不是 app 冷启动信号——用户平时开 app（系统已运行数小时）零延迟。
 *     实测依据：华为平板每次开机（正常/异常重启皆然）系统级风暴 ≥5 分钟
 *     （abnormal 后实测 uptime 87s load 23 / 292s load 26）；
 *  2. **CPU 余量退避（主负载信号）**：/proc/stat 两采样差分的 CPU 忙闲分数
 *     超阈 → 退避（真 CPU+IO 饱和）。**为什么不用 /proc/loadavg 直判**：loadavg
 *     把 D 态任务计入——本事故机实测有 **27 个华为常驻诊断内核线程永久 D 态**
 *     （bbox/hwxcollie/hisee 等），load 地板=27、永不回落，按「核数×倍率」判
 *     会永远持有（或盲区）。CPU 忙闲分数对 D 态免疫（D 态不耗 CPU），零权限同
 *     loadavg；iowait 计入忙侧（IO 风暴也是本事故实测形态：无用户态 CPU 消费
 *     者、纯 D 态 load 40+）；
 *  3. **loadavg 退避（辅助信号，默认关）**：留作可配置项（倍率 0=关）——
 *     普通设备（无 D 态地板）可用；事故机默认关（地板 27 无法用）。
 */
internal object SystemStormPolicy {

    /** 开机静默期判定：uptime < [bootQuietMs] 即持有；0 = 关闭本闸 */
    fun bootQuietHeld(uptimeMs: Long, bootQuietMs: Long): Boolean =
        bootQuietMs > 0 && uptimeMs < bootQuietMs

    /**
     * CPU 余量退避判定：忙闲分数（0..1，含 iowait）> [busyThresholdPct]% 即持有。
     * [busyFrac]=null（首个采样无基线/读不到 /proc/stat）→ 不持有（防御：
     * 读不到不能把后台活锁死；开机静默期闸独立生效）。
     * [busyThresholdPct]=0 → 关闭本闸。
     */
    fun cpuBusyHeld(busyFrac: Double?, busyThresholdPct: Int): Boolean {
        if (busyThresholdPct <= 0 || busyFrac == null) return false
        return busyFrac * 100.0 > busyThresholdPct
    }

    /**
     * loadavg 退避判定（辅助信号）：1 分钟负载 > max(1, [cores]) × [loadPerCore]
     * 即持有。[load1]=null（读不到）→ 不持有；[loadPerCore]=0 → 关闭本闸
     * （默认即关：事故机 D 态地板 27 见类注释）。
     */
    fun loadHeld(load1: Double?, cores: Int, loadPerCore: Int): Boolean {
        if (loadPerCore <= 0 || load1 == null) return false
        return load1 > maxOf(1, cores) * loadPerCore
    }

    /** 综合放行：总开关关 = 恒放行（kill switch）；否则任一闸持有即不放行 */
    fun stormClear(
        uptimeMs: Long,
        bootQuietMs: Long,
        busyFrac: Double?,
        busyThresholdPct: Int,
        load1: Double?,
        cores: Int,
        loadPerCore: Int,
        enabled: Boolean,
    ): Boolean = !enabled || (
        !bootQuietHeld(uptimeMs, bootQuietMs) &&
            !cpuBusyHeld(busyFrac, busyThresholdPct) &&
            !loadHeld(load1, cores, loadPerCore)
        )

    /** 等待超限（饥饿兜底）：持有持续 ≥ [maxHoldMs] 即放行（防持续高载永久锁死）；0 = 永不超限 */
    fun holdExpired(waitedMs: Long, maxHoldMs: Long): Boolean =
        maxHoldMs > 0 && waitedMs >= maxHoldMs
}

/**
 * R208 系统风暴闸·运行时（Android 侧；策略纯函数见 [SystemStormPolicy]）。
 *
 * 全局单源：深索引双节拍器起跑（FeedIndexPart）、WarmupCoordinator 错峰闸、
 * StorageGovernor 首轮/水位闸（经 ColdStartQuietGate 追加）全部从本闸取
 * 「系统是否处于风暴窗」的裁决——一处配置全局生效（AppPrefs 五键，run-as
 * 可调，内部调优口径无设置页入口）。装机接线：App.onCreate →
 * AppContainer init → [attach]（未 attach 时按默认值工作，测试语境兜底）。
 */
internal object SystemStormGate {

    private const val TAG = "StormGate"

    /** 开机静默期默认：系统重启后 5 分钟内一切后台重活持有（建议域 3~5min 取上沿） */
    internal const val DEFAULT_BOOT_QUIET_MS = 5L * 60_000L

    /** CPU 忙闲阈值默认：85%（忙闲分数含 iowait；D 态地板机型的可靠信号） */
    internal const val DEFAULT_CPU_BUSY_PCT = 85

    /** loadavg 倍率默认：0=关（事故机 D 态地板 27，loadavg 不可判；普通设备可开） */
    internal const val DEFAULT_LOAD_PER_CORE = 0

    /** 持有等待上限默认：10 分钟（持续高载也放行，派工侧低速语义自会退避） */
    internal const val DEFAULT_MAX_HOLD_MS = 10L * 60_000L

    /** 复查轮询步长（awaitClear 用） */
    private const val POLL_MS = 5_000L

    /** /proc/stat 采样最小间隔（忙闲差分需要 ≥2s 才有统计意义） */
    private const val CPU_SAMPLE_MIN_GAP_MS = 2_000L

    /** 信号缓存 TTL：0.5s 派工拍不重复读盘（stat+loadavg 同拍读） */
    private const val SIGNAL_CACHE_TTL_MS = 5_000L

    @Volatile
    private var prefs: AppPrefs? = null

    // ---- /proc/stat 两采样差分（CPU 忙闲分数）----
    // 首次读只有基线无分数（busy=null=未知不持有）；≥2s 间隔的下一拍才有差分。
    @Volatile
    private var lastCpuSample: LongArray? = null

    @Volatile
    private var lastCpuSampleAtMs = 0L

    @Volatile
    private var cachedBusyFrac: Double? = null

    // ---- /proc/loadavg 直读（辅助信号）----
    @Volatile
    private var cachedLoad1: Double? = null

    @Volatile
    private var cachedSignalAtMs = 0L

    /** 装机接线（AppContainer init；幂等） */
    fun attach(prefs: AppPrefs) {
        this.prefs = prefs
    }

    /** 系统 uptime（自内核启动含深睡，毫秒；开机静默期的原料） */
    fun uptimeMs(): Long = SystemClock.elapsedRealtime()

    /** /proc/loadavg 1 分钟负载直读（零权限；失败 null） */
    fun loadAvg1(): Double? = runCatching {
        File("/proc/loadavg").readText().split(' ').firstOrNull()?.toDouble()
    }.getOrNull()

    /**
     * /proc/stat 聚合 cpu 行读数（user nice system idle iowait irq softirq steal…
     * 按字段位取前 8 项，jiffies）。两采样差分得忙闲分数（含 iowait 计忙侧）。
     */
    private fun readCpuLine(): LongArray? = runCatching {
        val parts = File("/proc/stat").readText().lineSequence().firstOrNull()
            ?.split(' ') ?: return null
        // 首字段 "cpu" 后跟数值列；跳过空列（kernel 写法 "cpu  123" 可能双空格）
        val nums = parts.drop(1).filter { it.isNotBlank() }.map { it.toLong() }
        if (nums.size < 5) null else LongArray(minOf(nums.size, 8)) { nums[it] }
    }.getOrNull()

    /**
     * 当前 CPU 忙闲分数（0..1，含 iowait）。距上次采样 ≥[CPU_SAMPLE_MIN_GAP_MS]
     * 才重算差分；不足则回最近一次算得值（首拍/读盘失败 null=未知）。
     * 线程安全：@Volatile 双字段非原子但最坏值抖一拍，判定语义无损。
     */
    fun cpuBusyFrac(): Double? {
        val now = SystemClock.elapsedRealtime()
        val sample = readCpuLine() ?: return cachedBusyFrac
        val prev = lastCpuSample
        val prevAt = lastCpuSampleAtMs
        lastCpuSample = sample
        lastCpuSampleAtMs = now
        if (prev == null || prev.size != sample.size || now - prevAt < CPU_SAMPLE_MIN_GAP_MS) {
            return cachedBusyFrac
        }
        var busy = 0L
        var total = 0L
        for (i in sample.indices) {
            val d = sample[i] - prev[i]
            if (d < 0) return cachedBusyFrac // 计数器回绕/异常：保旧值
            total += d
            // 下标 3=idle、4=iowait（iowait 计忙侧：IO 饱和也是风暴形态）
            if (i != 3) busy += d
        }
        if (total <= 0) return cachedBusyFrac
        val frac = busy.toDouble() / total
        cachedBusyFrac = frac
        return frac
    }

    /** 当前是否放行（风暴窗外）。每拍现值：stat/loadavg 走 TTL 缓存，prefs 直读。 */
    fun clearNow(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - cachedSignalAtMs >= SIGNAL_CACHE_TTL_MS) {
            cachedBusyFrac = cpuBusyFrac()
            cachedLoad1 = loadAvg1()
            cachedSignalAtMs = now
        }
        val p = prefs
        return SystemStormPolicy.stormClear(
            uptimeMs = now,
            bootQuietMs = (p?.stormBootQuietSec ?: (DEFAULT_BOOT_QUIET_MS / 1000).toInt()) * 1000L,
            busyFrac = cachedBusyFrac,
            busyThresholdPct = p?.stormCpuBusyPct ?: DEFAULT_CPU_BUSY_PCT,
            load1 = cachedLoad1,
            cores = Runtime.getRuntime().availableProcessors(),
            loadPerCore = p?.stormLoadPerCore ?: DEFAULT_LOAD_PER_CORE,
            enabled = p?.stormGateEnabled ?: true,
        )
    }

    /**
     * 挂起等待风暴窗过去（时机闸本体）：已放行直通零开销；持有中每 [POLL_MS]
     * 复查，进入/放行各打一行日志（tag 供多消费方区分）；等待超过
     * prefs.stormMaxHoldSec 兜底放行（持续高载不能永久锁死后台活——放行后
     * 派工侧 stormHeld 仍会继续拦真派工，双层设计）。
     */
    suspend fun awaitClear(tag: String) {
        if (clearNow()) return
        val startedAt = SystemClock.elapsedRealtime()
        val maxHoldMs = (prefs?.stormMaxHoldSec ?: (DEFAULT_MAX_HOLD_MS / 1000).toInt()) * 1000L
        Log.i(TAG, "R208 风暴闸等待：$tag（开机静默期/负载退避持有中，每 ${POLL_MS / 1000}s 复查）")
        while (true) {
            delay(POLL_MS)
            if (clearNow()) {
                Log.i(TAG, "R208 风暴闸放行：$tag（共等 ${(SystemClock.elapsedRealtime() - startedAt) / 1000}s）")
                return
            }
            if (SystemStormPolicy.holdExpired(SystemClock.elapsedRealtime() - startedAt, maxHoldMs)) {
                Log.w(
                    TAG,
                    "R208 风暴闸等待超限（${maxHoldMs / 1000}s）：$tag 兜底放行（负载持续偏高也起跑，派工侧低速语义自会退避）",
                )
                return
            }
        }
    }
}
