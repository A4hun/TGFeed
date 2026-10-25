package dev.tgfeed.core.feed

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * R170b 可复用预热协调器（用户设计原则升级：**扫描不该暴露给用户**——后台静默
 * 预热，前台路径永不等待预热）。
 *
 * 统一收口所有「低优先级后台预热」的协调语义（发现一个修一类：关注流快照预热、
 * tier 快照刷新、后续音频/图文 tab 的内容预热都走这里）：
 *  - **错峰**：任务统一等冷启动让路闸（[gate]）放行才起跑——R158 实锤爬虫风暴
 *    拖慢首拉的教训：预热优先级低于一切前台路径，绝不与冷启动首拉抢 TDLib/IO；
 *  - **优先级**：数字大者先跑（用户可感知的预热 > 纯后台保鲜）；
 *  - **去重**：同 key 只入队一次（进程生命周期内；重复 submit 静默忽略）；
 *  - **串行执行**：任务逐个跑（不并发互抢），按优先级出队；
 *  - **静默重试**：任务块返回 false / 抛异常 = 失败，指数退避重试至
 *    [Task.maxRetries] 次后放弃——全程无 UI、无 toast、无错误态；
 *  - **生命周期**：随传入的 [scope]（VM 的 viewModelScope）生灭，进程内有效。
 *
 * [gate] 与 [retryBackoffMs] 可注入（单测无需 Android 时钟/真退避）。
 */
internal class WarmupCoordinator(
    private val scope: CoroutineScope,
    private val gate: suspend () -> Unit = {
        dev.tgfeed.core.td.ColdStartQuietGate.awaitQuiet(WARMUP_START_GRACE_MS)
    },
    private val retryBackoffMs: Long = WARMUP_RETRY_BACKOFF_MS,
) {
    /** 一条预热任务：block 返回 true = 成功（不再重试）；false/异常 = 失败可重试 */
    internal class Task(
        val key: String,
        val priority: Int,
        val maxRetries: Int,
        val block: suspend () -> Boolean,
    )

    private val lock = Any()
    private val pending = ArrayList<Task>()
    private val claimed = HashSet<String>()
    private var worker: Job? = null

    /**
     * 提交预热任务（幂等：key 已入队/已跑过则忽略）。首个提交起跑 worker；
     * worker 先过 [gate]（冷启动让路）再逐任务执行。调用线程任意（UI/IO 均可）。
     */
    fun submit(key: String, priority: Int = 0, maxRetries: Int = 2, block: suspend () -> Boolean) {
        synchronized(lock) {
            if (key in claimed) return
            claimed += key
            pending += Task(key, priority, maxRetries, block)
            // 稳定优先级序：同优先级保持提交序（ArrayList.sortByDescending 稳定）
            pending.sortByDescending { it.priority }
            if (worker == null) {
                worker = scope.launch(Dispatchers.IO) { runLoop() }
            }
        }
    }

    private suspend fun runLoop() {
        // 错峰闸：主 Feed 首拉落定 + 宽限后才起跑（R158 教训：不与冷启动抢）
        runCatching { gate() }
        while (true) {
            val task = synchronized(lock) { pending.removeFirstOrNull() } ?: break
            var attempt = 0
            while (attempt <= task.maxRetries) {
                val ok = runCatching { task.block() }.getOrDefault(false)
                if (ok) break
                attempt++
                if (attempt <= task.maxRetries) {
                    delay(retryBackoffMs * attempt) // 线性退避：1x、2x、3x…
                }
            }
        }
    }
}
