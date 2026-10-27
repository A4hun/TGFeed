package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexPipelinePolicyTest {

    private val p = IndexPipelinePolicy

    @Test
    fun crawlAbortStallAfterNoProgress() {
        val start = 1_000_000L
        assertFalse(p.crawlShouldAbort(start + p.CRAWL_STALL_MS - 1, start, start, manual = false))
        assertTrue(p.crawlShouldAbort(start + p.CRAWL_STALL_MS, start, start, manual = false))
    }

    @Test
    fun crawlAbortProgressResetsStall() {
        val start = 0L
        val now = p.CRAWL_STALL_MS * 2
        assertFalse(p.crawlShouldAbort(now, start, now - 1_000L, manual = false))
    }

    @Test
    fun crawlAbortHardCapDiffersByLane() {
        val now = p.ROAM_CRAWL_HARD_CAP_MS
        assertTrue(p.crawlShouldAbort(now, 0L, now, manual = false))
        assertFalse(p.crawlShouldAbort(now, 0L, now, manual = true))
        assertTrue(p.crawlShouldAbort(p.MANUAL_CRAWL_HARD_CAP_MS, 0L, p.MANUAL_CRAWL_HARD_CAP_MS, manual = true))
    }

    @Test
    fun crawlAbortClockBackwardsNoFalseKill() {
        assertFalse(p.crawlShouldAbort(0L, 10_000_000L, 10_000_000L, manual = false))
    }

    @Test
    fun watchdogLoopStalled() {
        val v = p.watchdogVerdict(p.LOOP_STALL_MS, 0L, p.LOOP_STALL_MS, 0, 0, held = false)
        assertEquals(IndexPipelinePolicy.WatchdogVerdict.LOOP_STALLED, v)
    }

    @Test
    fun watchdogNoProgressWithBacklog() {
        val now = p.NO_PROGRESS_MS
        assertEquals(
            IndexPipelinePolicy.WatchdogVerdict.NO_PROGRESS,
            p.watchdogVerdict(now, now, 0L, backlog = 3, inFlight = 0, held = false),
        )
    }

    @Test
    fun watchdogHeldOrIdleIsOk() {
        val now = p.NO_PROGRESS_MS * 3
        assertEquals(IndexPipelinePolicy.WatchdogVerdict.OK, p.watchdogVerdict(now, now, 0L, 5, 2, held = true))
        assertEquals(IndexPipelinePolicy.WatchdogVerdict.OK, p.watchdogVerdict(now, now, 0L, 0, 0, held = false))
        assertEquals(IndexPipelinePolicy.WatchdogVerdict.OK, p.watchdogVerdict(now, now, now - 1_000L, 5, 2, held = false))
    }

    @Test
    fun restartDelayBackoffAndCap() {
        assertEquals(0L, p.restartDelayMs(0))
        assertEquals(5_000L, p.restartDelayMs(1))
        assertEquals(10_000L, p.restartDelayMs(2))
        assertEquals(300_000L, p.restartDelayMs(30))
    }

    @Test
    fun failuresFallBackAfterStableRun() {
        assertEquals(0, p.nextConsecutiveFailures(5, 0L, failed = false))
        assertEquals(4, p.nextConsecutiveFailures(3, 1_000L, failed = true))
        assertEquals(1, p.nextConsecutiveFailures(7, p.STABLE_RUN_MS, failed = true))
    }

    @Test
    fun manualRequeueOnlyWhenUnfinishedAndUnderCap() {
        assertTrue(p.manualShouldRequeue(timedOut = true, budgetExhausted = false, attemptsSoFar = 0))
        assertTrue(p.manualShouldRequeue(timedOut = false, budgetExhausted = true, attemptsSoFar = 0))
        assertFalse(p.manualShouldRequeue(timedOut = false, budgetExhausted = false, attemptsSoFar = 0))
        assertFalse(p.manualShouldRequeue(timedOut = true, budgetExhausted = false, attemptsSoFar = p.MAX_MANUAL_ATTEMPTS))
    }

    @Test
    fun manualRequeueOnAbortedCancellation() {
        // R429：取消/异常/看护超时（aborted）也续排——管线重建取消在飞爬取时
        // outcome 停在 FAILED 初始值，钉住频道不丢（续排进手动车道等下一轮管线）
        assertTrue(p.manualShouldRequeue(timedOut = false, budgetExhausted = false, attemptsSoFar = 0, aborted = true))
        // aborted 与超时/预算耗尽同受续排上限约束（防取消风暴里无限重排）
        assertFalse(p.manualShouldRequeue(timedOut = false, budgetExhausted = false, attemptsSoFar = p.MAX_MANUAL_ATTEMPTS, aborted = true))
        // 默认参数（旧调用点口径）：不传 aborted 行为不变
        assertFalse(p.manualShouldRequeue(timedOut = false, budgetExhausted = false, attemptsSoFar = 0))
    }

    @Test
    fun manualDispatchIgnoresTimingAndBudget() {
        // R429：手动车道豁免预算硬限（与优先车道 M1/M9 同口径）——预算干涸期照常派工
        assertTrue(p.manualDispatchAllowed(healthPaused = false))
        // 健康喊停仍是硬限（进程存活优先于任何爬取，R165 口径不变）
        assertFalse(p.manualDispatchAllowed(healthPaused = true))
    }

    @Test
    fun laneNewRequestJumpsToFront() {
        val lane = ManualCrawlLane()
        assertTrue(lane.request(1L))
        assertTrue(lane.request(2L))
        assertFalse(lane.request(1L))
        assertEquals(2, lane.size())
        assertEquals(1L, lane.takeNext { false })
        assertEquals(2L, lane.takeNext { false })
        assertNull(lane.takeNext { false })
    }

    @Test
    fun laneSkipsInFlightAndKeepsThem() {
        val lane = ManualCrawlLane()
        lane.request(2L)
        lane.request(1L)
        assertEquals(2L, lane.takeNext { it == 1L })
        assertTrue(lane.contains(1L))
        assertNull(lane.takeNext { it == 1L })
        assertEquals(1L, lane.takeNext { false })
    }

    @Test
    fun laneRequeueGoesToBackAndCounts() {
        val lane = ManualCrawlLane()
        lane.request(1L)
        lane.request(2L)
        assertEquals(2L, lane.takeNext { false })
        assertEquals(1, lane.requeue(2L))
        assertEquals(1L, lane.takeNext { false })
        assertEquals(2L, lane.takeNext { false })
        assertEquals(1, lane.attemptsOf(2L))
        lane.request(2L)
        assertEquals(0, lane.attemptsOf(2L))
    }

    @Test
    fun laneForgetReportsQueuedState() {
        val lane = ManualCrawlLane()
        lane.request(9L)
        assertTrue(lane.forget(9L))
        assertFalse(lane.forget(9L))
        assertEquals(0, lane.size())
    }
}
