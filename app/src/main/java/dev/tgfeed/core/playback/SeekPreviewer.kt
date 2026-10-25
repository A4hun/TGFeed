package dev.tgfeed.core.playback

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.LinkedHashMap
import java.util.TreeMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 进度条拖拽帧预览（R73 缩略图带 / storyboard，抖音同款实时观感）：
 *
 * 主路 = 后台预抽缩略图带：播放地址就绪即起一条 IO 协程，用 MediaMetadataRetriever
 * 按均匀间隔预抽一批小图（时长 ≤60s 每 1.5s 一张；长视频均分 40 张；总数封顶 50；
 * 尺寸压到短边 240px），从头往后随抽随进有序表（TreeMap：锚点时刻 → 缩略图）。
 * 拖动时只做最近邻查表（peekStrip，纯内存零等待），抽帧不再发生在拖动路径上。
 *
 * 兜底链 = R243 拖拽帧单飞通道：带未就绪（起播头几分钟/长片锚点波前未推进到）
 * 时，拖拽目标帧经 [requestDragFrame] 异步现场抽。**关键设计：请求方（Compose 侧
 * LaunchedEffect）只做轻量派发，真正的 IO 由引擎侧单飞协程承担且不随 effect
 * 重启而取消**——旧实现（R67 现场抽帧挂在 effect 里）在长视频慢拖下 effect 每
 * 6ms 重启一次，IO 永远做不完就被取消=帧永远到不了气泡（用户实锤「拖进度条
 * 缩略图预览没了」的根因）。latest-wins：请求代次递增，旧请求的抽帧结果作废；
 * 交付回调只收最新请求的帧。拖拽会话期 [stripPaused] 让带预抽暂停（抽帧 IO 不与
 * 兜底抢锁、下载泵带宽优先给拖拽目标段），会话收尾（拖拽结束或 2s 无新请求）
 * 自动恢复。
 *
 * 线程纪律（锁图，纪律§八）：
 * - reqLock：请求记账（代次/目标/回调/会话槽），纳秒级内存操作——主线程
 *   （requestDragFrame/cancelDragFrame/close 记账段）与 worker 共用，**恒不跨
 *   native 调用持有**；
 * - dragLock：拖拽通道 retriever 一切操作（open/抽帧/release），**仅 IO 侧协程
 *   持有**——setDataSource 可挂在流服务器上 ~10s（R76 实锤语义），主线程永不到
 *   此锁上排队（ANR 红线）；
 * - retrieverLock：带侧 retriever 同上语义（open/抽帧/release）；
 * - stripLock：缩略图带表与预抽状态，纳秒级表操作，主线程查表安全；
 * - 三锁 + reqLock 四锁**互不嵌套**（worker 侧 reqLock 与 dragLock 先后获取、
 *   中间释放，无环路）；close() 带侧异步释放保留历史唯一嵌套方向
 *   （retrieverLock→stripLock，R76 既有，无环）。
 * - requestDragFrame/cancelDragFrame/peekStrip/startPrefetch/close 任意线程可调；
 *   交付回调在 IO 线程触发（Compose 快照写线程安全，重组照常调度）。
 *
 * 内存纪律：带 50 张短边 240px + 拖拽 LRU 20 张最长边 320px ≈ 8~20MB（竖屏片上界），
 * 切视频/失焦/退出播放 close() 清空（只清引用不 recycle：位图可能正被 UI 绘制，
 * 交给 GC 回收防绘崩）。
 */
class SeekPreviewer {
    private val tag = "SeekPreviewer"

    // ── 带侧 retriever ──
    private val retrieverLock = Any()
    private var retriever: MediaMetadataRetriever? = null
    private var usable = false
    // 当前 retriever 已打开的数据源：重复 open 同一路径才复用（清晰度热切换换源后必须重开）
    private var openedSource: String? = null

    // ── R73 缩略图带 ──
    // 带锁：保护 stripJob/stripSource/stripGen/stripTotal/stripIntervalMs/strip 表。
    // 主线程拖动查表（peekStrip）也抢它，故持锁只许做纯内存表操作
    private val stripLock = Any()
    // 预抽协程作用域：跟随本实例存活（remember(item.key) 同寿），close 只取消任务不毁作用域——
    // 失焦再激活要靠同一作用域重新起抽
    private val stripScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var stripJob: Job? = null
    // 本条带对应的数据源：防清晰度热切换/换页后旧带的帧串到新源上
    private var stripSource: String? = null
    // 代次：每次 startPrefetch/close 递增；预抽循环入表前校代，旧任务残留的帧绝不进新带
    private var stripGen = 0
    // 已抽缩略图带：锚点时刻 ms → 短边 240px 缩略图（有序，最近邻查表用）
    private val strip = TreeMap<Long, Bitmap>()
    // 计划总张数与锚点间隔（查表容差 = 间隔一半 + 250ms 余量）
    private var stripTotal = 0
    private var stripIntervalMs = 0L

    // ── R243 拖拽帧单飞通道 ──
    // 请求记账锁：代次/目标/回调/会话槽——纳秒级，主线程可碰（见类头锁图）
    private val reqLock = Any()
    // 请求代次：requestDragFrame/cancelDragFrame/close 递增；交付只认最新代
    private var dragGen = 0
    // 最新请求（target<0 = 无待处理请求）
    private var dragTargetMs = -1L
    private var dragSource: String? = null
    private var dragDeliver: ((Boolean, Bitmap?) -> Unit)? = null
    // 拖拽会话协程（单飞：常驻一条，有请求就处理最新，无请求 2s 后收）
    private var dragJob: Job? = null
    // 拖拽会话期暂停带预抽（带宽让位）：@Volatile 供预抽循环免锁快读
    @Volatile
    private var stripPaused = false
    // 拖拽通道 retriever 锁：仅 IO 侧持有（open/抽帧/release；setDataSource 可挂 ~10s，
    // 主线程永不到此锁排队——ANR 红线）
    private val dragLock = Any()
    private var dragRetriever: MediaMetadataRetriever? = null
    private var dragUsable = false
    private var dragOpenedSource: String? = null
    // 拖拽源最近一次 open 失败标记（8s 内不再重试同源：防死源连环挂起放大）
    private var dragFailedSource: String? = null
    private var dragFailedAtMs = 0L

    /**
     * 打开视频源（带侧）。支持两种：
     * - 本地文件路径（TDLib 已落盘的缓存文件）
     * - 本机回环 http 流（TDLib 本地媒体服务器 127.0.0.1/localhost，支持 range 随机读）；
     *   远端 http 一律拒绝（慢且不可控），打不开返回 false 由 UI 降级为纯时间气泡。
     * 重复调用幂等：已打开同一路径直接复用。
     */
    fun open(path: String?): Boolean = synchronized(retrieverLock) { openLocked(path) }

    private fun openLocked(path: String?): Boolean {
        if (path.isNullOrBlank()) {
            usable = false
            return false
        }
        if (!isPreviewableSource(path)) {
            usable = false
            return false
        }
        val source: String = when {
            path.startsWith("http") -> path // 回环流直接用 URL 喂 retriever（TDLib 本地服务器支持 range 定位）
            else -> {
                val f = File(path.removePrefix("file://"))
                if (!f.isFile) {
                    usable = false
                    return false
                }
                f.absolutePath
            }
        }
        if (retriever != null && usable && openedSource == source) return true
        releaseRetrieverLocked()
        usable = runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(source)
                retriever = r
                openedSource = source
                true
            } catch (e: Throwable) {
                // setDataSource 失败必须当场 release：直接丢弃的话 retriever 只能等 GC
                // finalize，其 native 收尾会等内部 HTTP 读线程，可能阻塞超 10s 被
                // FinalizerWatchdog 判超时直接杀进程（实测 FATAL 实锤）
                runCatching { r.release() }
                throw e
            }
        }.getOrElse {
            Log.w(tag, "打开失败，降级无预览: ${it.message}")
            retriever = null
            false
        }
        return usable
    }

    /**
     * 启动后台预抽缩略图带（幂等：同源已在抽/已抽完直接返回；换源则取消旧任务清带重抽）。
     * 任意线程可调；真正的抽帧在内部 IO 协程里。durationHintMs 仅作时长提示
     * （容器元数据读不到时兜底），传 0 亦可。
     */
    fun startPrefetch(path: String?, durationHintMs: Long) {
        if (path.isNullOrBlank()) return
        synchronized(stripLock) {
            if (stripSource == path && stripJob != null) return // 同一条带已在抽/已抽完
            stripJob?.cancel()
            strip.clear()
            stripGen++
            stripSource = path
            stripTotal = 0
            stripIntervalMs = 0L
            val gen = stripGen
            stripJob = stripScope.launch { prefetchLoop(path, durationHintMs, gen) }
        }
    }

    /**
     * 预抽循环：开源 → 读时长 → 均匀布点 → 从头往后逐张抽，随抽随入带。
     * 布点规则：≤60s 每 1.5s 一张；长视频均分 40 张；总数封顶 50。
     * 锚点居中（interval/2 + i*interval）：任意时刻到最近锚点误差 ≤ 间隔一半，
     * 且最末锚点不贴到片尾（片尾精确帧 getFrameAtTime 易失败）。
     * R243 拖拽让路：拖拽会话期（stripPaused）暂停抽帧——抽帧 IO 不与拖拽兜底
     * 抢锁，下载泵带宽优先给拖拽目标段；同一锚点让完重试（不跳点）。
     */
    private suspend fun prefetchLoop(path: String, durationHintMs: Long, gen: Int) {
        if (!open(path)) {
            Log.i(tag, "缩略图带：源打不开，跳过预抽（拖动走现场抽帧兜底）")
            markPrefetchRetryable(gen)
            return
        }
        // 时长优先取容器元数据（比条目元数据准），提示值兜底；都拿不到没法均匀布点
        val metaMs = synchronized(retrieverLock) {
            runCatching {
                retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            }.getOrNull()
        }
        val durMs = (metaMs ?: 0L).takeIf { it > 0 } ?: durationHintMs
        if (durMs <= 0L) {
            Log.i(tag, "缩略图带：时长未知，跳过预抽")
            markPrefetchRetryable(gen)
            return
        }
        val count = (if (durMs <= 60_000L) (durMs + 1499L) / 1500L else 40L)
            .toInt().coerceIn(1, 50)
        val intervalMs = durMs / count
        synchronized(stripLock) {
            if (gen != stripGen) return
            stripTotal = count
            stripIntervalMs = intervalMs
        }
        Log.i(tag, "缩略图带：开始预抽 时长${durMs / 1000}s 共${count}张 间隔${intervalMs}ms")
        val startAt = System.currentTimeMillis()
        var done = 0
        var i = 0
        while (i < count) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive() // 取消/换源即停
            // R243 拖拽让路：会话期不抽（同锚点稍后重试），代次变了才退场
            if (stripPaused) {
                delay(150)
                synchronized(stripLock) { if (gen != stripGen) return }
                continue
            }
            synchronized(stripLock) { if (gen != stripGen) return }
            val tMs = intervalMs / 2 + i * intervalMs
            // 单帧抽（retrieverLock 串行）：OPTION_CLOSEST_SYNC 抽最近关键帧；
            // 失败跳过该锚点（拖动到该时段走单飞通道兜底）
            val raw = synchronized(retrieverLock) {
                val r = retriever
                if (r == null || !usable) {
                    null
                } else {
                    runCatching {
                        r.getFrameAtTime(tMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }.getOrNull()
                }
            }
            if (raw == null) {
                i++
                continue
            }
            val scaled = scaleToShortEdge(raw, 240)
            if (scaled !== raw) raw.recycle()
            synchronized(stripLock) {
                if (gen != stripGen) return // 入表前再校代：旧任务的帧不进新带
                strip[tMs] = scaled
            }
            done++
            i++
            Log.i(tag, "缩略图带：已抽 $done/$count")
        }
        Log.i(tag, "缩略图带：预抽完成 $done/$count 耗时${System.currentTimeMillis() - startAt}ms")
    }

    /**
     * R110：预抽未达成（源瞬态打不开/时长暂未知）时清掉带源标记——
     * 旧实现失败后 stripSource/stripJob 残留，startPrefetch 的幂等闸
     * （同源且 stripJob 非空即跳过）把同源请求永久挡在门外，一次瞬态失败
     * 本条视频整段无缩略图带。清标记后下一次 startPrefetch（缓冲翻转/
     * 失焦再激活都会重发）即可重试。代次校验：已有更新的带在抽时不动。
     */
    private fun markPrefetchRetryable(gen: Int) {
        synchronized(stripLock) {
            if (gen != stripGen) return
            stripJob = null
            stripSource = null
        }
    }

    /**
     * 拖动查表（R73 主路）：返回离 positionMs 最近的已抽缩略图，纯内存最近邻查询
     * （TreeMap floor/ceiling），主线程可直接调——零磁盘零解码，预览实时跟手。
     * 返回 null 的情形（调用方走拖拽帧单飞通道）：
     * - 带为空 / 波前未推进到该时刻（最近锚点距离超容差 = 间隔一半 + 250ms）
     * - source 非空且与带源不匹配（清晰度热切换/换页后防串帧）
     */
    fun peekStrip(positionMs: Long, source: String? = null): Bitmap? {
        synchronized(stripLock) {
            if (strip.isEmpty()) return null
            if (source != null && stripSource != null && source != stripSource) return null
            val floor = strip.floorEntry(positionMs)
            val ceil = strip.ceilingEntry(positionMs)
            val best = when {
                floor == null -> ceil
                ceil == null -> floor
                positionMs - floor.key <= ceil.key - positionMs -> floor
                else -> ceil
            } ?: return null
            val tolerance = stripIntervalMs / 2 + 250L
            return if (kotlin.math.abs(best.key - positionMs) <= tolerance) best.value else null
        }
    }

    // ── R243 拖拽帧单飞通道 ─────────────────────────────────────────────

    /**
     * 拖拽帧请求（带未就绪时的兜底主入口）。调用方只在 Compose effect 里做轻量派发，
     * 真正的 open+抽帧在引擎侧单飞协程里且**不随调用方 effect 重启而取消**：
     * - 源形态不可预览（远端 http/本地文件不存在）→ 立即回调 (false, null)，
     *   UI 降级纯时间气泡；
     * - 可预览 → 立即回调 (true, null)（卡片模式+转圈占位即刻出现），随后异步
     *   open+抽帧，出帧回调 (true, frame)；open 失败回调 (false, null)。
     * latest-wins：新请求递增代次，旧请求的迟到结果不再交付。
     * 拖拽会话期带预抽自动暂停（见 [prefetchLoop]），会话收尾自动恢复。
     * 交付回调在 IO 线程触发（快照状态写线程安全）。主线程调用安全：只做纳秒级
     * 记账（reqLock），不碰 retriever 锁（worker 可能持它挂在 native 调用上）。
     */
    fun requestDragFrame(positionMs: Long, source: String?, deliver: (Boolean, Bitmap?) -> Unit) {
        if (source.isNullOrBlank() || !isPreviewableSource(source)) {
            deliver(false, null)
            return
        }
        deliver(true, null) // 卡片模式+转圈占位先出场（抽帧通道确认可用）
        synchronized(reqLock) {
            dragGen++
            dragTargetMs = positionMs
            dragSource = source
            dragDeliver = deliver
            if (dragJob?.isActive != true) {
                dragJob = stripScope.launch { dragSessionLoop() }
            }
        }
        stripPaused = true
    }

    /** 拖拽收尾：作废在途请求（迟到结果不交付）并解除带预抽暂停。任意线程可调。 */
    fun cancelDragFrame() {
        synchronized(reqLock) {
            dragGen++
            dragTargetMs = -1L
            dragSource = null
            dragDeliver = null
        }
        stripPaused = false
    }

    /** 单飞会话的当前请求快照（reqLock 内组装） */
    private class DragReq(
        val gen: Int,
        val targetMs: Long,
        val source: String,
        val deliver: (Boolean, Bitmap?) -> Unit,
    )

    /**
     * 拖拽会话单飞循环：处理最新请求 → 交付 → 等新请求；2s 无新请求收会话
     * （带预抽恢复）。出帧失败（目标段数据未到位——流式下载前缘未推进到）短退避
     * 重试同目标：数据随下载推进到位后帧自然出；重试上限后交 (true, null)（转圈
     * 占位维持，诚实呈现「帧在路上」）。
     * 锁纪律：reqLock 只做纳秒级记账（读取请求/校代）；dragLock 包住 native
     * 操作（open/抽帧）——两锁先后获取互不嵌套，reqLock 恒不跨 native 持有。
     */
    private suspend fun dragSessionLoop() {
        try {
            while (true) {
                val req = synchronized(reqLock) {
                    val t = dragTargetMs
                    if (t < 0L) null else DragReq(dragGen, t, dragSource!!, dragDeliver!!)
                } ?: return
                var attempts = 0
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val opened = synchronized(dragLock) { openDragLocked(req.source) }
                    if (!opened) {
                        if (synchronized(reqLock) { dragGen } == req.gen) {
                            req.deliver(false, null)
                        }
                        break
                    }
                    val frame = synchronized(dragLock) { dragFrameLocked(req.targetMs) }
                    val stillLatest = synchronized(reqLock) { dragGen } == req.gen
                    if (frame != null) {
                        if (stillLatest) {
                            req.deliver(true, frame)
                            Log.i(
                                tag,
                                "拖拽帧交付 target=${req.targetMs}ms（重试${attempts}次/单飞通道）",
                            )
                        }
                        break
                    }
                    // 帧未得：目标段数据大概率仍在路上——短退避重试同目标
                    if (!stillLatest) break
                    if (++attempts > DRAG_FRAME_RETRY_MAX) {
                        req.deliver(true, null) // 诚实维持转圈占位（通道开着、帧未到）
                        Log.i(tag, "拖拽帧重试${attempts}次未得 target=${req.targetMs}ms（维持占位）")
                        break
                    }
                    delay(DRAG_FRAME_RETRY_DELAY_MS)
                }
                // 等下一个请求；[DRAG_SESSION_IDLE_MS] 无新请求收会话
                var newRequest = false
                withTimeoutOrNull(DRAG_SESSION_IDLE_MS) {
                    while (synchronized(reqLock) { dragGen } == req.gen) delay(50)
                    newRequest = true
                }
                if (!newRequest) return
            }
        } finally {
            stripPaused = false
            synchronized(reqLock) {
                // 会话自然结束才清槽（close 抢先清槽时此处已是 null）
                if (dragJob?.isActive == false) dragJob = null
            }
        }
    }

    /** 拖拽通道开源（dragLock 内调用）：幂等复用；死源 8s 冷却防连环挂起放大。 */
    private fun openDragLocked(path: String): Boolean {
        val failed = dragFailedSource
        if (failed == path && SystemClock.elapsedRealtime() - dragFailedAtMs < DRAG_SOURCE_FAIL_COOLDOWN_MS) {
            return false
        }
        if (dragRetriever != null && dragUsable && dragOpenedSource == path) return true
        releaseDragRetrieverLocked()
        val source = normalizeSource(path) ?: run {
            dragFailedSource = path
            dragFailedAtMs = SystemClock.elapsedRealtime()
            return false
        }
        dragUsable = runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(source)
                dragRetriever = r
                dragOpenedSource = path
                true
            } catch (e: Throwable) {
                // 同 openLocked：失败当场 release，防 FinalizerWatchdog 超时杀进程
                runCatching { r.release() }
                throw e
            }
        }.getOrElse {
            Log.w(tag, "拖拽通道打开失败: ${it.message}")
            dragRetriever = null
            dragFailedSource = path
            dragFailedAtMs = SystemClock.elapsedRealtime()
            false
        }
        return dragUsable
    }

    /**
     * 拖拽通道现场抽帧（dragLock 内调用）：250ms 量化做缓存键（拖动微抖不重复抽），
     * OPTION_CLOSEST_SYNC 最近关键帧，最长边 ≤320px（气泡卡片展示够用）。
     * 未打开/抽帧失败返回 null（调用方短退避重试——数据未到位属常态）。
     */
    private fun dragFrameLocked(positionMs: Long): Bitmap? {
        val r = dragRetriever ?: return null
        if (!dragUsable) return null
        val keySec = (positionMs / 250L).toInt()
        dragCache[keySec]?.let { return it }
        val bmp = runCatching {
            r.getFrameAtTime(positionMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }.getOrNull() ?: return null
        val scaled = scaleDown(bmp, 320)
        if (scaled !== bmp) bmp.recycle()
        dragCache[keySec] = scaled
        return scaled
    }

    // 拖拽通道兜底 LRU：量化时刻（250ms）→ 最长边 320px 缩略图，容量 20
    private val dragCache = object : LinkedHashMap<Int, Bitmap>(20, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?): Boolean =
            size > 20
    }

    /** 释放拖拽通道 retriever 并清兜底 LRU（必须在 dragLock 内调用） */
    private fun releaseDragRetrieverLocked() {
        runCatching { dragRetriever?.release() }
        dragRetriever = null
        dragUsable = false
        dragOpenedSource = null
        dragCache.clear()
    }

    /**
     * 全量收尾：取消预抽协程与拖拽会话、清缩略图带、释放两路 retriever、清兜底 LRU。
     * 切视频/失焦/页面销毁时调用；重复调用安全。位图只清引用不 recycle——
     * 可能正被 UI 绘制，交给 GC 回收（recycle 会绘崩）。
     *
     * R76 修复（"下载中快速切页"ANR 实锤）：retriever 的释放必须异步。
     * 旧实现 close() 在主线程同步抢 retrieverLock——预抽协程可能正持锁卡在
     * MediaMetadataRetriever.setDataSource(回环流)（native binder 调用不可中断，
     * 下载中首块未就绪时 TgStreamServer 刻意不发响应头，一挂就是十几秒），
     * 主线程等锁 25s 直接 ANR（trace 实锤：main Blocked at close ←
     * worker 持锁卡在 setDataSource）。现拆两段：strip 表清理保持同步
     * （stripLock 持锁全是纳秒级内存操作，主线程安全）；retriever 释放派到
     * IO 协程排队，挂死的 setDataSource 返回后锁一空出即释放，主线程零等待。
     * 代次校验：close 后若又重新 startPrefetch（失焦再激活），stripGen 已推进，
     * 迟到的释放作废——防止把刚复用/重开的 retriever 误清。
     * R243 拖拽通道同款纪律：记账（reqLock 纳秒级）主线程同步做，retriever
     * 释放等会话协程收尾后在 IO 侧持 dragLock 做。
     */
    fun close() {
        val gen = synchronized(stripLock) {
            stripGen++
            stripJob?.cancel()
            stripJob = null
            stripSource = null
            stripTotal = 0
            stripIntervalMs = 0L
            strip.clear()
            stripGen
        }
        // R243：拖拽会话记账收口（作废请求+解除带暂停），会话槽摘除
        val releaseJob = synchronized(reqLock) {
            dragGen++
            dragTargetMs = -1L
            dragSource = null
            dragDeliver = null
            val j = dragJob
            dragJob = null
            j
        }
        stripPaused = false
        stripScope.launch {
            // 等会话协程自然收尾（在途 native 调用返回后按作废代次退出），
            // 再独占 dragLock 释放拖拽通道 retriever——全程 IO，主线程无涉
            releaseJob?.join()
            synchronized(dragLock) { releaseDragRetrieverLocked() }
        }
        stripScope.launch {
            // 挂死的 setDataSource 持锁期间在此排队等待，与主线程无涉。
            // 代次校验放在锁内：等锁期间页面可能已复活（又 startPrefetch），
            // 迟到的释放必须作废——否则会把刚复用/重开的 retriever 误清。
            // 锁序安全：此处是唯一的 retrieverLock→stripLock 嵌套方向，
            // 其余路径两锁从不嵌套，无环路
            synchronized(retrieverLock) {
                if (synchronized(stripLock) { gen != stripGen }) return@launch
                releaseRetrieverLocked()
            }
        }
    }

    /** 释放 retriever 并清兜底 LRU（必须在 retrieverLock 内调用） */
    private fun releaseRetrieverLocked() {
        runCatching { retriever?.release() }
        retriever = null
        usable = false
        openedSource = null
    }

    /** 最长边压到 maxEdge（拖拽兜底现场抽帧用，320px 够气泡展示） */
    private fun scaleDown(src: Bitmap, maxEdge: Int): Bitmap {
        val w = src.width
        val h = src.height
        val longSide = maxOf(w, h)
        if (longSide <= maxEdge) return src
        val scale = maxEdge.toFloat() / longSide
        val dw = (w * scale).toInt().coerceAtLeast(1)
        val dh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, dw, dh, true)
    }

    /** 短边压到 shortEdge（缩略图带用：240px 够预览窗，内存可控） */
    private fun scaleToShortEdge(src: Bitmap, shortEdge: Int): Bitmap {
        val w = src.width
        val h = src.height
        val shortSide = minOf(w, h)
        if (shortSide <= shortEdge) return src
        val scale = shortEdge.toFloat() / shortSide
        val dw = (w * scale).toInt().coerceAtLeast(1)
        val dh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, dw, dh, true)
    }

    companion object {
        /** 拖拽帧重试上限（同目标）：数据未到位的诚实等待窗（每次重试含一次真抽帧尝试） */
        const val DRAG_FRAME_RETRY_MAX = 6

        /** 拖拽帧重试间隔（毫秒）：短退避——下载前缘推进到位即出帧 */
        const val DRAG_FRAME_RETRY_DELAY_MS = 400L

        /** 拖拽会话空闲收尾（毫秒）：无新请求即收会话、恢复带预抽 */
        const val DRAG_SESSION_IDLE_MS = 2_000L

        /** 拖拽源 open 失败冷却（毫秒）：死源短期内不再连环挂起重试 */
        const val DRAG_SOURCE_FAIL_COOLDOWN_MS = 8_000L
    }
}

/**
 * 源形态预览资格判定（纯函数）：本机回环流 / 本地文件 / file:// 前缀可预览；
 * 远端 http 一律不可（慢且不可控）。[SeekPreviewer.open] 与拖拽单飞通道共用，
 * 单一真源防两路口径漂移。
 */
internal fun isPreviewableSource(path: String): Boolean {
    if (path.startsWith("http")) {
        return path.startsWith("http://127.0.0.1") || path.startsWith("http://localhost") ||
            path.startsWith("https://127.0.0.1") || path.startsWith("https://localhost")
    }
    return File(path.removePrefix("file://")).isFile
}

/** 源规范化：回环流原样（URL 喂 retriever）；本地路径剥 file:// 前缀取绝对路径 */
private fun normalizeSource(path: String): String? {
    if (path.startsWith("http")) return path
    val f = File(path.removePrefix("file://"))
    if (!f.isFile) return null
    return f.absolutePath
}
