package dev.tgfeed.core.feed

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** R419 纯计数探针 Tag（只输出数字，绝不输出频道名或内容） */
internal const val R419_TAG = "R419Index"

/** 看护层轮询间隔 */
private const val CRAWL_GUARD_POLL_MS = 15_000L

/** 取消后等待爬取协程退出的宽限（不可取消的阻塞段超出宽限即放弃等待，槽位照样释放） */
private const val CRAWL_CANCEL_GRACE_MS = 30_000L

/** 看门狗与探针节拍 */
private const val WATCHDOG_TICK_MS = 60_000L

/** 自动索引关闭时监督者的复查间隔（用户在设置里再开即恢复，不必重启 app） */
private const val DISABLED_RECHECK_MS = 60_000L

/**
 * R419 管线监督者：跑管线 → 异常/看门狗判死 → 退避重建；设置关掉 → 轮询等再开。
 * 看门狗与每分钟探针是监督者的子协程，与管线生死无关（管线死了探针照报）。
 */
internal suspend fun FeedViewModel.superviseIndexPipelineImpl() {
    val st = indexPipeline
    var failures = 0
    kotlinx.coroutines.coroutineScope {
        launch { indexProbeLoopImpl() }
        while (isActive) {
            if (!dev.tgfeed.core.prefs.AppPrefs(app).autoDeepIndex) {
                runCatching { refreshIndexStatus() } // 没开也显示现状
                delay(DISABLED_RECHECK_MS)
                continue
            }
            val startedAt = System.currentTimeMillis()
            var failed = false
            val crashed = java.util.concurrent.atomic.AtomicBoolean(false)
            val pipeline = launch(Dispatchers.IO) {
                try {
                    runIndexPipelineOnceImpl()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    // 管线异常就地收口（不上抛连坐监督者），记数后退避重建
                    crashed.set(true)
                    android.util.Log.w(R419_TAG, "pipeline_err=${t.javaClass.simpleName}")
                }
            }
            val dog = launch {
                while (isActive) {
                    delay(WATCHDOG_TICK_MS)
                    val now = System.currentTimeMillis()
                    val backlog = st.backlogRoam.get() + st.backlogExplore.get() + st.backlogTtl.get() +
                        st.manualLane.size() + priorityQueuedSet.size
                    val verdict = IndexPipelinePolicy.watchdogVerdict(
                        now, st.lastLoopTickMs, st.lastProgressMs, backlog, st.inFlight.get(), st.held,
                    )
                    if (verdict != IndexPipelinePolicy.WatchdogVerdict.OK) {
                        st.watchdogRestarts.incrementAndGet()
                        android.util.Log.w(R419_TAG, "watchdog=${verdict.name} restarts=${st.watchdogRestarts.get()}")
                        pipeline.cancel(CancellationException("R419 watchdog ${verdict.name}"))
                        break
                    }
                }
            }
            try {
                pipeline.join()
                // join 不抛管线内部异常——这里用 isCancelled 区分看门狗/异常收场与正常关停
                failed = pipeline.isCancelled || crashed.get()
            } finally {
                dog.cancel()
            }
            if (!isActive) break
            val ranMs = System.currentTimeMillis() - startedAt
            failures = IndexPipelinePolicy.nextConsecutiveFailures(failures, ranMs, failed)
            if (failed) {
                st.pipelineErrors.incrementAndGet()
                val wait = IndexPipelinePolicy.restartDelayMs(failures)
                android.util.Log.w(R419_TAG, "pipeline_restart failures=$failures wait_ms=$wait")
                delay(wait)
            }
        }
    }
}

/**
 * R419 每分钟纯计数探针：各车道积压、在飞、近一分钟完成、手动车道长度、看门狗重启次数。
 */
private suspend fun FeedViewModel.indexProbeLoopImpl() {
    val st = indexPipeline
    var lastCompleted = st.completedTotal.get()
    var lastDispatched = st.dispatchedTotal.get()
    while (true) {
        delay(WATCHDOG_TICK_MS)
        val c = st.completedTotal.get()
        val d = st.dispatchedTotal.get()
        android.util.Log.i(
            R419_TAG,
            "roam=${st.backlogRoam.get()} explore=${st.backlogExplore.get()} ttl=${st.backlogTtl.get()} " +
                "prio=${priorityQueuedSet.size} manualQ=${st.manualLane.size()} manualFly=${st.manualInFlight.get()} " +
                "inFlight=${st.inFlight.get()} done1m=${c - lastCompleted} disp1m=${d - lastDispatched} " +
                "timeouts=${st.timeoutsTotal.get()} loopErr=${st.loopErrors.get()} pipeErr=${st.pipelineErrors.get()} " +
                "wdRestart=${st.watchdogRestarts.get()} starts=${st.pipelineStarts.get()} held=${if (st.held) 1 else 0} " +
                "alive=${if (indexSchedulerAlive) 1 else 0}",
        )
        lastCompleted = c
        lastDispatched = d
    }
}

/**
 * R419 看护层单频道爬取：子协程跑 indexChannel，看护者每 [CRAWL_GUARD_POLL_MS] 判停滞/
 * 超硬上限（[IndexPipelinePolicy.crawlShouldAbort]）→ 取消。返回结果口径供手动车道续排。
 * 任何路径（成功/异常/取消/超时）都会返回，调用方 finally 释放在飞槽位。
 */
internal suspend fun FeedViewModel.guardedIndexChannelImpl(
    chatId: Long,
    manual: Boolean,
    scope: CoroutineScope,
): CrawlOutcome {
    val startMs = System.currentTimeMillis()
    val lastPageMs = java.util.concurrent.atomic.AtomicLong(startMs)
    val failed = java.util.concurrent.atomic.AtomicBoolean(false)
    val job = scope.launch(Dispatchers.IO) {
        try {
            deepIndex.indexChannel(gateway, chatId, tiering = indexTiering) { n ->
                val now = System.currentTimeMillis()
                lastPageMs.set(now)
                indexPipeline.noteProgress(now)
                onManualIndexRowProgressImpl(chatId, n)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            failed.set(true)
            if (manual) _error.value = "深度索引构建失败：${t.message}"
        }
    }
    var timedOut = false
    while (job.isActive) {
        val finished = withTimeoutOrNull(CRAWL_GUARD_POLL_MS) { job.join() } != null
        if (finished) break
        if (IndexPipelinePolicy.crawlShouldAbort(System.currentTimeMillis(), startMs, lastPageMs.get(), manual)) {
            timedOut = true
            indexPipeline.timeoutsTotal.incrementAndGet()
            android.util.Log.w(R419_TAG, "crawl_timeout manual=${if (manual) 1 else 0} id4=${chatId.toString().takeLast(4)}")
            job.cancel(CancellationException("R419 crawl guard"))
            withTimeoutOrNull(CRAWL_CANCEL_GRACE_MS) { job.join() }
            break
        }
    }
    return when {
        timedOut -> CrawlOutcome.TIMED_OUT
        failed.get() -> CrawlOutcome.FAILED
        else -> CrawlOutcome.DONE
    }
}
