package dev.tgfeed.core.feed

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * R170b [WarmupCoordinator] 单测（纯 JVM：gate/backoff 全注入，无 Android 依赖）：
 * 错峰闸先于任务、优先级出队、key 去重、失败重试到成功、重试耗尽放弃、
 * 任务串行执行（不互抢）。
 */
class WarmupCoordinatorTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** 轮询等待条件成立（带总超时防死等） */
    private suspend fun awaitCond(timeoutMs: Long = 3_000, cond: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!cond()) delay(10)
        }
    }

    @Test
    fun `闸先于任务执行`() = runBlocking {
        val order = mutableListOf<String>()
        val coord = WarmupCoordinator(scope, gate = { order += "gate" }, retryBackoffMs = 10)
        coord.submit("t") { order += "task"; true }
        awaitCond { order.size >= 2 }
        assertEquals(listOf("gate", "task"), order)
    }

    @Test
    fun `优先级严格出队_闸放行后`() = runBlocking {
        val order = mutableListOf<String>()
        val gateOpen = java.util.concurrent.atomic.AtomicBoolean(false)
        val coord = WarmupCoordinator(
            scope,
            gate = { awaitCond { gateOpen.get() } },
            retryBackoffMs = 10,
        )
        // worker 卡在闸上：三个任务全部入队后再放行，出队序必由优先级决定
        coord.submit("low", priority = 1) { order += "low"; true }
        coord.submit("mid", priority = 5) { order += "mid"; true }
        coord.submit("high", priority = 10) { order += "high"; true }
        delay(100) // 确保 worker 已起跑并卡在闸上（否则 submit 串行时首个可能直接跑）
        gateOpen.set(true)
        awaitCond { order.size >= 3 }
        assertEquals(listOf("high", "mid", "low"), order)
    }

    @Test
    fun `同 key 去重只跑一次`() = runBlocking {
        val runs = AtomicInteger(0)
        val coord = WarmupCoordinator(scope, gate = {}, retryBackoffMs = 10)
        coord.submit("dup") { runs.incrementAndGet(); true }
        coord.submit("dup") { runs.incrementAndGet(); true }
        awaitCond { runs.get() >= 1 }
        delay(100)
        assertEquals(1, runs.get())
    }

    @Test
    fun `失败重试到成功`() = runBlocking {
        val attempts = AtomicInteger(0)
        val coord = WarmupCoordinator(scope, gate = {}, retryBackoffMs = 10)
        val ok = mutableListOf<Boolean>()
        coord.submit("retry", maxRetries = 3) {
            attempts.incrementAndGet()
            attempts.get() >= 3 // 前两次失败，第三次成功
        }
        awaitCond { attempts.get() >= 3 }
        delay(100)
        assertEquals(3, attempts.get())
    }

    @Test
    fun `重试耗尽后放弃不再跑`() = runBlocking {
        val attempts = AtomicInteger(0)
        val coord = WarmupCoordinator(scope, gate = {}, retryBackoffMs = 10)
        coord.submit("giveup", maxRetries = 2) { attempts.incrementAndGet(); false }
        // 初次 + 2 次重试 = 3 次
        awaitCond { attempts.get() >= 3 }
        delay(150)
        assertEquals(3, attempts.get())
    }

    @Test
    fun `任务抛异常按失败重试`() = runBlocking {
        val attempts = AtomicInteger(0)
        val coord = WarmupCoordinator(scope, gate = {}, retryBackoffMs = 10)
        coord.submit("boom", maxRetries = 1) {
            if (attempts.incrementAndGet() == 1) error("模拟预热失败") else true
        }
        awaitCond { attempts.get() >= 2 }
        delay(100)
        assertEquals(2, attempts.get())
    }

    @Test
    fun `任务串行执行不并发`() = runBlocking {
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val done = AtomicInteger(0)
        val coord = WarmupCoordinator(scope, gate = {}, retryBackoffMs = 10)
        repeat(4) { i ->
            coord.submit("task$i") {
                val now = concurrent.incrementAndGet()
                maxConcurrent.updateAndGet { maxOf(it, now) }
                delay(50) // 模拟预热耗时
                concurrent.decrementAndGet()
                done.incrementAndGet()
                true
            }
        }
        awaitCond(timeoutMs = 5_000) { done.get() >= 4 }
        assertEquals(1, maxConcurrent.get()) // 串行：任一时刻至多 1 个任务在跑
    }
}
