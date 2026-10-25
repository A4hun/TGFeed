package dev.tgfeed.core.diag

import android.content.ComponentCallbacks2
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * R165 进程健康监视器（ANR-watchdog 类防御）。
 *
 * 起因（16384 夜间实锤，见 anr-analysis.md）：深索引热循环把 native 内存推到
 * 03:53 mmap OOM，abort 链冻尸持锁 → 全进程 futex 网格 → 主线程整夜楔死；
 * ANR 时 Signal Catcher 自身 bad_alloc、debuggerd 附加失败——**Java 栈永远拿不到**。
 * 全仓此前零内存压力感知（grep onTrimMemory 零命中），进程只能被环境打死或僵死。
 *
 * 三件事：
 *  1. **主线程心跳取证**：主线程 5s 一跳；看门狗发现停跳 > [STALL_THRESHOLD_MS]
 *     时，在独立取证线程上打主线程 Java 栈（E 级）——下次同类事故不用再猜
 *     「主线程在等谁」。取栈可能卡 safepoint，放独立线程避免拖垮看门狗本体；
 *  2. **内存压力分级**（纯函数 [pressureLevelOf]，60s 采样 /proc/self/status VmRSS）：
 *     HIGH 只告警；SEVERE 时 [crawlPaused] 喊停深索引派工（在飞频道跑完收尾），
 *     把最大的后台分配源先掐掉，给主线程/播放器留活路；
 *  3. **onTrimMemory 接线**：系统回收压力（RUNNING_CRITICAL/COMPLETE 等）转成
 *     限时暂停窗（系统只报压力不报缓解，靠时间窗自愈 + 压力采样复核）。
 *
 * 只读旁路设计：不抛异常、不改业务状态、喊停只影响派工节奏（FeedIndexPart
 * 消费 [crawlPaused]）。心跳本身每 5s 一次 Handler post，开销可忽略。
 */
object AppHealthMonitor {

    private const val TAG = "AppHealth"

    // ---------------- 压力分级 ----------------

    /** 压力分级常量：OK / HIGH（告警）/ SEVERE（喊停爬取） */
    const val PRESSURE_OK = 0
    const val PRESSURE_HIGH = 1
    const val PRESSURE_SEVERE = 2

    /**
     * RSS 分级阈值（KB）。HIGH=1.8G：超过常见健康水位（Java 堆 180M + TDLib +
     * 播放器常态几百 M），值得留痕观察；SEVERE=2.6G：夜事故进程击穿前的量级，
     * 再往上是 commit 耗尽区——先掐后台大分配源。
     */
    const val RSS_HIGH_KB = 1_800_000L
    const val RSS_SEVERE_KB = 2_600_000L

    // ---------------- 心跳参数 ----------------

    /** 主线程心跳间隔（毫秒） */
    const val HEARTBEAT_INTERVAL_MS = 5_000L

    /** 停跳告警阈值（毫秒）：卡主 20s 必是事故级（GC/重组毫秒级不该摸到） */
    const val STALL_THRESHOLD_MS = 20_000L

    /** 看门狗巡检间隔（毫秒） */
    private const val CHECK_INTERVAL_MS = 5_000L

    /** 内存采样间隔（毫秒）——RSS 读 /proc/self/status，低频足够 */
    private const val MEMORY_SAMPLE_INTERVAL_MS = 60_000L

    /** 健康快照日志间隔（毫秒）：夜间取证用（30min 一行，不刷屏） */
    private const val SNAPSHOT_LOG_INTERVAL_MS = 30L * 60_000L

    /** onTrimMemory 暂停窗时长（毫秒）：系统压力后限时喊停，窗过靠 RSS 采样复核 */
    private const val TRIM_PAUSE_MS = 10L * 60_000L

    // ---------------- 运行态（@Volatile 跨线程） ----------------

    private val started = AtomicBoolean(false)

    /** 主线程最近一跳（SystemClock.uptimeMillis 口径，单调不受改墙钟影响） */
    @Volatile
    var lastMainBeatMs: Long = 0L
        private set

    /** 主线程当前是否停跳（停跳即事故态） */
    @Volatile
    var mainThreadStalled: Boolean = false
        private set

    /** 当前内存压力分级（PRESSURE_*） */
    @Volatile
    var pressureLevel: Int = PRESSURE_OK
        private set

    /** 最近一次 RSS 采样（KB；0=还没采过） */
    @Volatile
    var lastRssKb: Long = 0L
        private set

    /** onTrimMemory 暂停窗到期时刻（uptimeMillis；0=无窗） */
    @Volatile
    private var trimPauseUntilMs: Long = 0L

    /** 主线程引用（start 时捕获；取证用） */
    private var mainThread: Thread? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // ---------------- 生命周期 ----------------

    /** 装机（幂等）：App.onCreate 调一次；重复调用无副作用 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        val main = Looper.getMainLooper().thread
        mainThread = main
        lastMainBeatMs = SystemClock.uptimeMillis()
        // 心跳：主线程自跑 Runnable 链（主线程死则链断，lastMainBeatMs 停走——正是要测的）
        val beat = object : Runnable {
            override fun run() {
                lastMainBeatMs = SystemClock.uptimeMillis()
                mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
        mainHandler.post(beat)
        // 看门狗：IO 侧巡检（停跳判定 + 内存采样 + 快照日志）
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var nextMemSampleAtMs = 0L
            var nextSnapshotAtMs = 0L
            while (isActive) {
                val now = SystemClock.uptimeMillis()
                val stalled = isStalled(lastMainBeatMs, now, STALL_THRESHOLD_MS)
                if (stalled != mainThreadStalled) {
                    mainThreadStalled = stalled
                    if (stalled) dumpMainThreadStack(now) else {
                        Log.w(TAG, "主线程心跳恢复（停跳 ${now - lastMainBeatMs}ms 期间的心跳已补上）")
                    }
                }
                if (now >= nextMemSampleAtMs) {
                    nextMemSampleAtMs = now + MEMORY_SAMPLE_INTERVAL_MS
                    val rss = readVmRssKb()
                    if (rss > 0) lastRssKb = rss
                    val level = pressureLevelOf(rss)
                    if (level != pressureLevel) {
                        Log.w(TAG, "内存压力分级变化：$pressureLevel → $level（RSS ${rss / 1024}MB）")
                        pressureLevel = level
                    }
                }
                if (now >= nextSnapshotAtMs) {
                    nextSnapshotAtMs = now + SNAPSHOT_LOG_INTERVAL_MS
                    Log.i(
                        TAG,
                        "健康快照：RSS ${lastRssKb / 1024}MB 压力级 $pressureLevel " +
                            "主线程心跳 ${now - lastMainBeatMs}ms 前 爬取喊停=${crawlPaused()}",
                    )
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
        Log.i(TAG, "进程健康监视器启动（心跳 ${HEARTBEAT_INTERVAL_MS / 1000}s/停跳阈值 ${STALL_THRESHOLD_MS / 1000}s/RSS 采样 ${MEMORY_SAMPLE_INTERVAL_MS / 1000}s）")
    }

    /** onTrimMemory 接线（App 转发）：系统回收压力转限时喊停窗 */
    fun onTrimMemory(level: Int) {
        val pause = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            -> true
            else -> level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        }
        if (pause) {
            trimPauseUntilMs = SystemClock.uptimeMillis() + TRIM_PAUSE_MS
            Log.w(TAG, "系统回收压力 onTrimMemory(level=$level)：深索引派工限时喊停 ${TRIM_PAUSE_MS / 60_000L}min")
        }
    }

    /** 深索引派工是否喊停：SEVERE 压力 / 主线程停跳 / trim 暂停窗 任一命中 */
    fun crawlPaused(): Boolean =
        pressureLevel >= PRESSURE_SEVERE || mainThreadStalled ||
            SystemClock.uptimeMillis() < trimPauseUntilMs

    // ---------------- 纯函数（单测覆盖） ----------------

    /**
     * 内存压力分级（纯函数）：RSS（KB）→ PRESSURE_*。
     * 采样失败（0/负数）按 OK 处理（宁漏勿误——读不到不该喊停）。
     */
    fun pressureLevelOf(rssKb: Long): Int = when {
        rssKb <= 0L -> PRESSURE_OK
        rssKb >= RSS_SEVERE_KB -> PRESSURE_SEVERE
        rssKb >= RSS_HIGH_KB -> PRESSURE_HIGH
        else -> PRESSURE_OK
    }

    /** 主线程是否停跳（纯函数）：距上次心跳超过阈值即停跳（顺时针单调时钟口径） */
    fun isStalled(lastBeatMs: Long, nowMs: Long, thresholdMs: Long): Boolean =
        nowMs > lastBeatMs && nowMs - lastBeatMs > thresholdMs

    // ---------------- 内部 ----------------

    /**
     * 主线程栈取证：独立一次性线程上执行——[Thread.getStackTrace] 需要挂起目标线程，
     * 目标若已楔死在 native futex 上可能连挂起都做不到（甚至卡住调用方），
     * 绝不让取证拖垮看门狗本体。每段停跳只取证一次（状态翻转沿触发）。
     */
    private fun dumpMainThreadStack(nowMs: Long) {
        val thread = mainThread
        Thread {
            runCatching {
                val frames = thread?.stackTrace ?: emptyArray()
                val trace = frames.take(40).joinToString("\n") { "    at $it" }
                Log.e(
                    TAG,
                    "主线程心跳停跳 ${nowMs - lastMainBeatMs}ms（≥${STALL_THRESHOLD_MS}ms 阈值）！" +
                        "深索引派工已喊停。主线程 Java 栈（R165 取证，补「ANR 时 Java 栈不可得」缺口）：\n$trace",
                )
            }
        }.start()
    }

    /** 读 /proc/self/status 的 VmRSS（KB）；读不到返回 0（调用方按 OK 处理） */
    private fun readVmRssKb(): Long = runCatching {
        val text = File("/proc/self/status").readText()
        val m = Regex("VmRSS:\\s+(\\d+)\\s+kB").find(text) ?: return@runCatching 0L
        m.groupValues[1].toLongOrNull() ?: 0L
    }.getOrDefault(0L)
}
