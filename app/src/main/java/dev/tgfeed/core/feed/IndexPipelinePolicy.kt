package dev.tgfeed.core.feed

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * R419 深索引管线稳定性与手动车道（纯函数 + 线程安全小件，JVM 可单测）。
 *
 * 背景：用户反馈「指定频道优先建索引不生效」「打开软件时间长索引就不干活」。
 *  - 手动车道 [ManualCrawlLane]：用户钉住频道的专属队列，独立派工槽
 *    [IndexPipelinePolicy.MANUAL_LANE_SLOTS]（不占巡游并发，不受时机闸、探索配额、
 *    风暴闸约束；只受健康喊停与爬取预算硬限）；
 *  - 单频道爬取看护 [IndexPipelinePolicy.crawlShouldAbort]：停滞或超硬上限即取消，
 *    在飞槽位必然释放（TDLib 卡死不再永久占槽）；
 *  - 管线看门狗 [IndexPipelinePolicy.watchdogVerdict]：主循环心跳停跳或有积压却长时间
 *    零进展 → 重建管线；
 *  - 重启退避 [IndexPipelinePolicy.restartDelayMs] 指数退避，稳定运行后回落。
 */
object IndexPipelinePolicy {

    /** 手动车道额外派工槽（叠加在巡游并发之上——真插队，不等巡游槽空出） */
    const val MANUAL_LANE_SLOTS = 2

    /** 手动频道单次因超时或预算耗尽而续排的最大次数（防无限重排） */
    const val MAX_MANUAL_ATTEMPTS = 5

    /** 单频道爬取停滞判定：这么久没有任何页进度即视为卡死 */
    const val CRAWL_STALL_MS = 6L * 60_000L

    /** 巡游爬取单次硬上限（游标按页落盘，下次续爬不丢进度） */
    const val ROAM_CRAWL_HARD_CAP_MS = 45L * 60_000L

    /** 手动爬取单次硬上限（全量深挖允许更久，仍有停滞判定兜底） */
    const val MANUAL_CRAWL_HARD_CAP_MS = 4L * 60L * 60_000L

    /** 看门狗：主循环心跳超过此时长未跳 = 循环卡死或已静默退出 */
    const val LOOP_STALL_MS = 3L * 60_000L

    /** 看门狗：有积压且未被闸持有，却这么久零派工零完成零页进度 = 管线不干活 */
    const val NO_PROGRESS_MS = 20L * 60_000L

    /** 管线连续稳定运行这么久后，失败计数回落（退避归位） */
    const val STABLE_RUN_MS = 10L * 60_000L

    private const val RESTART_BASE_MS = 5_000L
    private const val RESTART_MAX_MS = 5L * 60_000L

    /** 单频道爬取是否应取消：停滞或超硬上限（时钟回拨按 0 处理，不误杀） */
    fun crawlShouldAbort(nowMs: Long, startMs: Long, lastProgressMs: Long, manual: Boolean): Boolean {
        val stalled = (nowMs - maxOf(startMs, lastProgressMs)).coerceAtLeast(0L) >= CRAWL_STALL_MS
        val cap = if (manual) MANUAL_CRAWL_HARD_CAP_MS else ROAM_CRAWL_HARD_CAP_MS
        val overCap = (nowMs - startMs).coerceAtLeast(0L) >= cap
        return stalled || overCap
    }

    enum class WatchdogVerdict { OK, LOOP_STALLED, NO_PROGRESS }

    /**
     * 看门狗判定：
     *  - 主循环心跳停跳 ≥ [LOOP_STALL_MS] → LOOP_STALLED；
     *  - 有活（积压或在飞）、未被闸持有、且 ≥ [NO_PROGRESS_MS] 零进展 → NO_PROGRESS；
     *  - 其余 OK（闸持有期间零进展是正常的，不误判）。
     */
    fun watchdogVerdict(
        nowMs: Long,
        lastLoopTickMs: Long,
        lastProgressMs: Long,
        backlog: Int,
        inFlight: Int,
        held: Boolean,
    ): WatchdogVerdict {
        if ((nowMs - lastLoopTickMs).coerceAtLeast(0L) >= LOOP_STALL_MS) return WatchdogVerdict.LOOP_STALLED
        val hasWork = backlog > 0 || inFlight > 0
        if (hasWork && !held && (nowMs - lastProgressMs).coerceAtLeast(0L) >= NO_PROGRESS_MS) {
            return WatchdogVerdict.NO_PROGRESS
        }
        return WatchdogVerdict.OK
    }

    /** 重启前等待：0 次失败不等；之后 5s 起倍增，封顶 5min */
    fun restartDelayMs(consecutiveFailures: Int): Long {
        if (consecutiveFailures <= 0) return 0L
        val shift = (consecutiveFailures - 1).coerceAtMost(10)
        return minOf(RESTART_BASE_MS shl shift, RESTART_MAX_MS)
    }

    /** 失败计数推进：正常结束归零；异常结束时若已稳定运行够久则回落为 1，否则累加 */
    fun nextConsecutiveFailures(prev: Int, ranMs: Long, failed: Boolean): Int = when {
        !failed -> 0
        ranMs >= STABLE_RUN_MS -> 1
        else -> prev.coerceAtLeast(0) + 1
    }

    /** 手动频道本轮结束后是否续排：超时或预算耗尽（没真跑完）且未超续排上限。
     *  R429：取消/异常/看护超时（[aborted]）也续排——管线重建取消在飞爬取时
     *  outcome 停在 FAILED 初始值即丢，钉住频道会卡死在 building（手动车道是
     *  进程级长活状态，续排后重建的下一轮管线照常取件续爬） */
    fun manualShouldRequeue(timedOut: Boolean, budgetExhausted: Boolean, attemptsSoFar: Int, aborted: Boolean = false): Boolean =
        (timedOut || budgetExhausted || aborted) && attemptsSoFar < MAX_MANUAL_ATTEMPTS

    /** 手动车道是否可派工：只受健康喊停（R429：豁免预算硬限——与优先车道 M1/M9 同口径，
     *  见 FeedIndexPart 优先车道「预算不拦优先车道（频次低、内部自会记账）」先例；
     *  用户钉住是显式意图，预算干涸期照常派工，否则钉住行整窗期恒 building） */
    fun manualDispatchAllowed(healthPaused: Boolean): Boolean = !healthPaused
}

/**
 * R419 手动车道队列：去重 FIFO，用户新请求插到最前；取件跳过在飞频道（留在队里，
 * 在飞那份——可能是钉住前以低档位启动的浅爬——结束后再按 T0 重跑）。
 */
class ManualCrawlLane {
    private val lock = Any()
    private val order = ArrayDeque<Long>()
    private val attempts = HashMap<Long, Int>()

    /** 用户请求：插到最前（已在队则前移），续排计数清零。返回是否新加入 */
    fun request(id: Long): Boolean = synchronized(lock) {
        val existed = order.remove(id)
        order.addFirst(id)
        attempts.remove(id)
        !existed
    }

    /** 续排（超时或预算耗尽）：放到队尾（让其他手动频道先走），返回累计续排次数 */
    fun requeue(id: Long): Int = synchronized(lock) {
        if (id !in order) order.addLast(id)
        val n = (attempts[id] ?: 0) + 1
        attempts[id] = n
        n
    }

    fun attemptsOf(id: Long): Int = synchronized(lock) { attempts[id] ?: 0 }

    /** 取下一个可派工频道（[skip] 为真的留在原位） */
    fun takeNext(skip: (Long) -> Boolean): Long? = synchronized(lock) {
        val it = order.iterator()
        while (it.hasNext()) {
            val id = it.next()
            if (!skip(id)) {
                it.remove()
                return@synchronized id
            }
        }
        null
    }

    /** 移出队列（续排计数保留，由 [forget] 清） */
    fun remove(id: Long): Boolean = synchronized(lock) { order.remove(id) }

    /** 彻底遗忘（取消钉或终结）；返回遗忘前是否仍在排队（未起跑） */
    fun forget(id: Long): Boolean = synchronized(lock) {
        attempts.remove(id)
        order.remove(id)
    }

    fun contains(id: Long): Boolean = synchronized(lock) { id in order }

    fun size(): Int = synchronized(lock) { order.size }
}

/** 单频道爬取结果（看护层口径） */
enum class CrawlOutcome { DONE, FAILED, TIMED_OUT }

/**
 * R419 管线运行态与纯计数（VM 级，跨管线重建存活）。只存数字，不存任何内容。
 */
class IndexPipelineState {
    val manualLane = ManualCrawlLane()
    val manualInFlight = AtomicInteger(0)

    // 管线每拍刷新的积压量表
    val backlogRoam = AtomicInteger(0)
    val backlogExplore = AtomicInteger(0)
    val backlogTtl = AtomicInteger(0)
    val inFlight = AtomicInteger(0)

    // 累计计数
    val dispatchedTotal = AtomicLong(0)
    val completedTotal = AtomicLong(0)
    val timeoutsTotal = AtomicLong(0)
    val loopErrors = AtomicLong(0)
    val pipelineErrors = AtomicLong(0)
    val watchdogRestarts = AtomicLong(0)
    val pipelineStarts = AtomicLong(0)

    @Volatile var lastLoopTickMs: Long = 0L
    @Volatile var lastProgressMs: Long = 0L
    @Volatile var held: Boolean = false

    fun noteProgress(nowMs: Long = System.currentTimeMillis()) {
        lastProgressMs = nowMs
    }
}
