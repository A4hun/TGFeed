package dev.tgfeed.core.diag

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** R421: 诊断日志异步写线程，避免主线程同步 IO。 */
internal object DiagLogWriter {
    private const val MAX_PENDING = 2000

    private val pending = AtomicInteger(0)
    private val droppedCount = AtomicLong(0)

    val dropped: Long get() = droppedCount.get()

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "diaglog-writer").apply { isDaemon = true }
    }

    fun submit(task: () -> Unit) {
        if (pending.incrementAndGet() > MAX_PENDING) {
            pending.decrementAndGet()
            droppedCount.incrementAndGet()
            return
        }
        try {
            executor.execute {
                try {
                    task()
                } catch (_: Throwable) {
                } finally {
                    pending.decrementAndGet()
                }
            }
        } catch (_: Throwable) {
            pending.decrementAndGet()
            droppedCount.incrementAndGet()
        }
    }

    /** 崩溃前尽力冲刷：提交栅栏任务，最多等待 timeoutMs。 */
    fun drain(timeoutMs: Long = 300) {
        if (Thread.currentThread().name == "diaglog-writer") return
        val latch = CountDownLatch(1)
        try {
            executor.execute { latch.countDown() }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
        }
    }
}
