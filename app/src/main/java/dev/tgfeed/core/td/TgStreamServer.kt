package dev.tgfeed.core.td

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.drinkless.tdlib.TdApi
import dev.tgfeed.core.playback.PlaybackTrace
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket

/**
 * 应用内嵌的本地流媒体服务器，实现 Telegram 视频的边下边播。
 *
 * 原理：ExoPlayer 请求 http://127.0.0.1:<port>/v/<fileId>（支持 Range），
 * 服务器按需调用 TDLib DownloadFile(offset, limit, synchronous=true) 同步拉取
 * 播放器当前需要的数据，从 TDLib 本地缓存文件读出并写给播放器。
 * 播放器向后 seek 时 ExoPlayer 会发带 Range 的新请求，天然支持拖拽。
 *
 * R72 提速（第二版，泵+轮询架构）：
 * 旧实现「下一块（等 TDLib 同步下完 512KB）→ 读盘 → 写 socket → 再下一块」
 * 全程串行，写 socket 与播放器消化缓冲期间 TDLib 完全空转，吞吐被钉死在
 * 512KB/单块全程耗时（高 RTT 代理实测仅几百 KB/s）。曾试 8 块并发预拉窗，
 * 实测推翻：TDLib 对同一文件的并发 DownloadFile 会以
 * "Canceled by another downloadFile request" 互杀（mp4 尾部 moov 探针连接与
 * 正播连接交错时两边窗口全灭）。现架构：
 *  - 每连接一个顺序下载泵：任一时刻最多一个在途大块查询（首块 512KB 压首帧，
 *    之后 2MB 摊薄高 RTT 代理的单请求开销），完成即续，TDLib 侧永不空转；
 *  - 同文件跨连接经 [fileMutexes] 互斥：探针/正播交错不再互杀，探针最多等
 *    一个在途块，正播因 8MB 前瞻覆盖通常无感；
 *  - 服务循环只认泵的落盘水位 coveredTo（泵查询成功=TDLib 保证范围真实下载），
 *    水位没过目标块才读盘——直接轮询读盘会拿到 TDLib 预分配空洞的 0 字节
 *   （实测首块 1ms"就绪"→ ExoPlayer UnrecognizedInputFormatException）；
 *  - 首块用 512KB 小块压首帧延迟，之后 4MB 大块摊薄高 RTT 代理的握手开销，
 *    吞吐不再被「512KB 一问一答」钉死。
 *
 * R81 内容完整性硬保证（根治平板实测「播着好好的突然 3001 Invalid NAL length」）：
 * 旧水位模型把「TDLib 同步查询无异常返回」当成「该区间已真实落盘」，三个洞都会
 * 让损坏字节进了播放器（3001 是解析终态，R78 分诊不可重试，比慢更糟糕）：
 *  ① 假水位：TDLib 同步 DownloadFile 在下载失败/被更高优先级查询顶掉时也会
 *     携当前文件状态正常返回（不是异常），旧泵照样推进 coveredTo——服务循环随后
 *     把 TDLib 预分配空洞的一坨 0 字节当内容发出；
 *  ② 路径漂移：连接开头取的 localPath 被整载完成改名/删除后，temp 名会被新下载
 *     复用成别的文件，旧实现只在 open 失败时重取路径，open「成功」开到错文件
 *     照读照发（R72 已实证 temp 名复用，但只堵了失败分支）；
 *  ③ 读盘零校验：水位一过就信盘，盘上内容与水位之间没有任何一致性闸。
 * 现在的硬保证（任一闸不过 → 头前 503 让播放器走重试链 / 头后截断，绝不发坏字节）：
 *  - 泵推进水位前必须用 TDLib 返回的 LocalFile 已下载窗口核对目标区间
 *   （整载完成=全文件可读；否则可读窗口=[downloadOffset, +downloadedPrefixSize)，
 *    兼容旧口径 [0, downloadedPrefixSize)），验证不过只推进到已验证边界、
 *    毫无进展按失败退避重试——对齐短板/瞬态失败都会自愈，水位永不虚报；
 *  - 文件句柄每次开启都现取 GetFile 新鲜路径（不再跨块缓存路径），开启后做
 *    inode 钉（fstat 对 stat，防开启瞬间名字被复用）+ 文件长度须覆盖目标读区；
 *  - 写 socket 前逐缓冲扫零洞：水位宣称已落盘的区域读出整缓冲 0 字节 = 洞实证，
 *    当场拒发（真实视频流不存在 64KB 纯零区——盒头/NAL 长度前缀皆非零）。
 *  - 全链路 PlaybackTrace 观测：泵窗口校验结果、句柄开启校验、零洞拦截、
 *    每连接发出字节数/终态水位。
 *
 * R124 根治分屏多路并发下密集 3001 Invalid NAL（R81 各闸全程静默、块块验证通过
 * 仍出 3001 的残留路径）：根因不是内容——盘文件事后 ffmpeg 全解码零错误、
 * 服务器读盘逐块指纹与盘一致、双读核对无 torn read——而是 R76 的 URG 僵尸探测
 *（水位等待期每秒一次 sendUrgentData）向数据流注入带外标记字节，该平板
 *（MIUI）接收路径上带外字节会伤及正常字节流，播放器 extractor 读到的流
 * 位置错位 → Invalid NAL。分屏 6 路并发使水位等待频繁（探测密集），故密集爆发；
 * 单 Feed 等待稀少，故偶发。修法：探测换成零写入的读式探测（见 [clientGone]），
 * 僵尸收割语义不变。A/B 实证：同机同分屏同并发，仅换探测方式，
 * 3001 由 27 分钟 30+ 次降为 14+ 分钟 0 次。
 *
 * 只监听 127.0.0.1，仅本进程可访问。
 */
class TgStreamServer(
    private val scope: CoroutineScope,
    private val getFile: suspend (Int) -> TdApi.File,
    /**
     * 分段下载查询（TDLib DownloadFile 同步版，网关侧已按 fileId 串行化）。
     * 返回值语义（R81）：验证过的连续覆盖终点（开区间，必然 > offset 且 ≤ offset+limit）；
     * null = 目标区间未能确认落盘（TDLib 正常返回但已下载窗口不含该区间），
     * 调用方必须按失败退避重试，绝不可推进水位。
     */
    private val downloadPart: suspend (fileId: Int, offset: Long, limit: Long) -> Long?,
    /**
     * R166 整下最终兜底（显式上报）：泵分段查询连续失败达阈值时回调，网关侧发起一次
     * 整载并登记（downloadPart 的窗口抢占闸见牌让位）。返回后本泵转 GetFile 轮询推
     * 水位，等整载扫描覆盖本连接区间。幂等性由网关侧登记表保证。
     */
    private val fullDownload: suspend (fileId: Int) -> Unit = {},
    /** R166 查牌：该 fileId 是否已进整下兜底（泵起跑前直查，免新连接再撞一轮失败） */
    private val isFullDownloadFallback: (fileId: Int) -> Boolean = { false },
) {
    @Volatile
    private var serverSocket: ServerSocket? = null

    /**
     * P1-3 有界线程池：最多 [MAX_THREADS] 个工作线程（SynchronousQueue + AbortPolicy，
     * 超出即拒绝——accept 循环捕获后回 503 丢连接，而不是无界堆线程）。
     * 旧实现 newCachedThreadPool 无界：TDLib 卡死时每路 ExoPlayer 连接永久占一个线程
     * （+64KB 缓冲 + RandomAccessFile fd），线程/fd/内存三头耗尽直到 OOM。
     * 注意：accept 循环自身常驻占用 1 个线程，实际可供流式连接并发的为 MAX_THREADS-1。
     */
    private val pool = ThreadPoolExecutor(
        0, MAX_THREADS, 60L, TimeUnit.SECONDS,
        SynchronousQueue(),
    ) { r -> Thread(r, "tg-stream").apply { isDaemon = true } }

    @Volatile
    var port: Int = 0
        private set

    @Synchronized
    fun start() {
        if (serverSocket != null) return
        val ss = bindLoopback()
        serverSocket = ss
        port = ss.localPort
        Log.d(TAG, "流媒体服务器启动: 127.0.0.1:$port")
        pool.execute { acceptLoop(ss) }
    }

    /** 必须显式绑 IPv4 回环：getLoopbackAddress() 在 Android 上常返回 ::1，
     *  只监听 IPv6 会导致 ExoPlayer 连 127.0.0.1 被拒绝（踩过坑） */
    private fun bindLoopback(): ServerSocket =
        ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))

    /**
     * P1-3 accept 循环：失败不再一次性 break 永久死亡。
     *  - 瞬时错误（典型 EMFILE：fd 被打满）→ 线性退避后 continue 重试；
     *  - 连续失败达 [MAX_ACCEPT_FAILURES] 次 → 旧 socket 大概率已坏，
     *    经 [rebuildServerSocket] 换绑新端口重建，服务自愈；
     *  - 仅当 socket 已被关闭/被替换（不会有新连接到来）时才退出，
     *    退出前置空 serverSocket，让下次 url()→start() 能重启。
     */
    private fun acceptLoop(initial: ServerSocket) {
        var ss = initial
        var failures = 0
        while (true) {
            val socket = try {
                ss.accept()
            } catch (e: Exception) {
                if (ss.isClosed || serverSocket !== ss) {
                    // 已关闭且没有新循环接管：置空允许重启；已被替换则旧循环直接让位
                    if (ss.isClosed && serverSocket === ss) {
                        synchronized(this) { if (serverSocket === ss) serverSocket = null }
                    }
                    Log.w(TAG, "accept 循环退出（socket 已关闭/已替换）")
                    return
                }
                failures++
                val backoffMs = (200L * failures).coerceAtMost(5_000L)
                Log.w(TAG, "accept 失败（第 $failures 次）: ${e.message}，${backoffMs}ms 后重试")
                if (failures >= MAX_ACCEPT_FAILURES) {
                    val rebuilt = rebuildServerSocket(ss) ?: return // 已被别的循环替换，让位
                    if (rebuilt !== ss) {
                        ss = rebuilt
                        failures = 0
                    }
                    // rebuilt === ss：重建失败（端口耗尽等），保留旧 socket 继续退避重试
                }
                try {
                    Thread.sleep(backoffMs)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            failures = 0 // 成功接一路即复位退避
            try {
                pool.execute { handle(socket) }
            } catch (e: RejectedExecutionException) {
                // 有界池已满（全部线程在忙）：回 503 丢弃该连接，播放器走自己的网络重试链
                Log.w(TAG, "流连接被拒（线程池满 $MAX_THREADS），回 503")
                runCatching { respondError(socket.getOutputStream(), 503) }
                runCatching { socket.close() }
            }
        }
    }

    /** 重建 serverSocket（accept 连续失败、旧 socket 可能已坏时自愈）；仅当在册实例仍是 [old] 才换绑 */
    @Synchronized
    private fun rebuildServerSocket(old: ServerSocket): ServerSocket? {
        if (serverSocket !== old) return null // 已被别处替换，调用方（旧 accept 循环）应退出
        val ss = runCatching { bindLoopback() }
            .onFailure { Log.w(TAG, "serverSocket 重建失败: ${it.message}") }
            .getOrNull() ?: return old // 重建失败：保留旧 socket，调用方继续退避重试
        runCatching { old.close() }
        serverSocket = ss
        port = ss.localPort
        Log.i(TAG, "流媒体服务器已重建: 127.0.0.1:$port")
        return ss
    }

    fun url(fileId: Int): String {
        start()
        return "http://127.0.0.1:$port/v/$fileId"
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            // 回环小写免 Nagle 攒包：响应头/尾块等小包立即发出，压首帧与收尾延迟
            runCatching { socket.tcpNoDelay = true }
            val input = socket.getInputStream()
            val out = socket.getOutputStream()
            val reader = input.bufferedReader(Charsets.ISO_8859_1)
            // R124 诊断：每连接一个序号，同文件多连接（嗅探/探针/正播）的 trace 可区分
            val connSeq = connSeqGen.incrementAndGet()

            // 请求行: GET /v/<fileId> HTTP/1.1
            val requestLine = reader.readLine() ?: return
            val fileId = Regex("""^GET /v/(\d+)""").find(requestLine)
                ?.groupValues?.get(1)?.toIntOrNull() ?: run {
                PlaybackTrace.log("conn404", detail = "请求行解析失败")
                respondError(out, 404); socket.close(); return
            }

            // 请求头：找 Range
            var rangeStart = -1L
            var rangeEnd = -1L
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    val m = Regex("""bytes=(\d*)-(\d*)""").find(line)
                    if (m != null) {
                        rangeStart = m.groupValues[1].toLongOrNull() ?: 0L
                        rangeEnd = m.groupValues[2].toLongOrNull() ?: -1L
                    }
                }
            }

            // P1-3：GetFile 同样加超时——旧实现无超时，TDLib 卡死（VPN 掉线/死 client）时
            // 连接线程永久阻塞，信号量许可逐个泄漏，3 个卡死请求后全 App 拉流瘫痪
            val file = runBlocking {
                kotlinx.coroutines.withTimeout(GET_FILE_TIMEOUT_MS) { getFile(fileId) }
            }
            val total = file.size.takeIf { it > 0 } ?: file.expectedSize
            if (total <= 0) {
                // R80：大小未知是瞬态（TDLib 远端元数据未就绪），不是"资源失效"——
                // 416 会被 R78 分诊当 4xx 稳定终态误杀；回 503 让播放器走重试链
                PlaybackTrace.log("conn503", "f$fileId", "大小未知 size=${file.size} expected=${file.expectedSize}")
                respondError(out, 503); socket.close(); return
            }

            val start = if (rangeStart >= 0) rangeStart else 0L
            val end = when {
                rangeEnd >= 0 -> minOf(rangeEnd, total - 1)
                else -> total - 1
            }
            if (start > end) {
                PlaybackTrace.log("conn416", "f$fileId", "start=$start end=$end total=$total")
                respondError(out, 416); socket.close(); return
            }
            val length = end - start + 1
            PlaybackTrace.log("conn", "f$fileId#$connSeq", "range=$start..$end/$total")

            val headers = buildString {
                if (rangeStart >= 0) {
                    append("HTTP/1.1 206 Partial Content\r\n")
                    append("Content-Range: bytes $start-$end/$total\r\n")
                } else {
                    append("HTTP/1.1 200 OK\r\n")
                }
                append("Content-Type: video/mp4\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $length\r\n")
                append("Connection: close\r\n\r\n")
            }
            // ⚠️ 响应头先不写：等首个分段真实落盘读到数据后再发。
            // 旧实现先发 200 再拉流，TDLib 拉取失败/数据未落盘时播放器收到
            // "假 200 + 空 body"，ExoPlayer 嗅探失败报 UnrecognizedInputFormatException
            //（用户看到"解码失败"）。现在首块就绪前失败 → 503，走 ExoPlayer 网络重试链。
            // R166 例外：带 Range 的连接（seek 重开/moov 尾探针）头立即发——ExoPlayer 的
            // DefaultHttpDataSource 有 8s 读超时，旧口径等首块（seek 到未下载区要现拉，
            // 高 RTT 代理下 512KB 可达数秒）=把超时枪口顶在 seek 延迟上，头一发不出就
            // SocketTimeoutException → Loader 重试白烧。206 是对播放器明确 Range 请求的
            // 诚实应答；本段失败仍按原语义截断（已发字节都真实），播放器报网络错误可重试。
            // 无 Range 的初次 GET 维持「首块就绪才发头」（R81 防假 200 嗅探失败，勿回退）。
            var raf: RandomAccessFile? = null
            var headersSent = false
            if (rangeStart >= 0) {
                runCatching {
                    out.write(headers.toByteArray(Charsets.US_ASCII))
                    out.flush()
                }
                headersSent = true
            }

            // R166 在播登记（WiFi 预载让位闸的数据源）：本连接开始供流即计数 +1，
            // finally 里 -1；网关侧预载循环见到「该文件正在被播放」跳过/换下一条——
            // 预载整载与泵同文件只有一个下载窗口锚点，抢窗口=抢 seek 能力
            markStreamingActive(fileId)

            // 分块供流（R72 泵+轮询；并发窗口已被实测推翻，勿回退——见类头注释）。
            // 既有性能要点（勿回退）：
            //  - RandomAccessFile 按连接复用：同一 socket 只 open 一次，finally 里 close；
            //    每 512KB 都开关文件会造成文件系统抖动（syscall + fd churn）
            //  - 64KB 读缓冲按连接复用，不在块循环里反复分配
            // R72 速率观测：首块耗时 + 每 5s 一记服务字节/均速/近窗速率（修复前后对比口径）
            val connStartMs = SystemClock.elapsedRealtime()
            var lastRateLogMs = connStartMs
            var lastRateLogBytes = 0L
            try {
                var pos = start
                val buf = ByteArray(64 * 1024)
                runBlocking {
                    // 消费位置对泵可见：泵始终保持其前方 PUMP_AHEAD_BYTES 的已请求覆盖
                    val consumerPos = AtomicLong(start)
                    // 已落盘覆盖水位（泵查询成功才推进）：服务循环读盘前必须先等水位越过
                    // 目标块——直接读盘会把 TDLib 预分配空洞的 0 字节当数据发给播放器
                    //（实测：首块 1ms "就绪"→ ExoPlayer UnrecognizedInputFormatException）
                    val coveredTo = AtomicLong(start)
                    // 顺序下载泵：本连接任一时刻最多一个在途 DownloadFile（TDLib 同文件
                    // 并发查询互杀，见类头）；同文件跨连接经 fileMutex 互斥，全局经
                    // downloadSemaphore 限流。失败不推进覆盖位，小幅退避后原位重试。
                    // 首块用 512KB 小块压首帧延迟，之后 4MB 大块摊薄 RTT
                    val pump = launch(Dispatchers.IO) {
                        PlaybackTrace.log("pumpStart", "f$fileId", "from=$start")
                        var covered = start
                        var firstBlock = true
                        var blockSeq = 0
                        // R166 整下兜底状态：起新连接时该文件已在兜底表（之前某条连接的泵已
                        // 判定分段持续不可得）→ 直接进轮询模式，免再撞一轮失败；本连接自己
                        // 撞满失败阈值（PUMP_FALLBACK_FAILS）也会转入。
                        var fallbackEngaged = isFullDownloadFallback(fileId)
                        if (fallbackEngaged) {
                            PlaybackTrace.log("pumpFallback", "f$fileId", "起跑即兜底模式 from=$start")
                        }
                        // R166 连续失败计数（查询异常/落盘校验未过都算；任何成功清零）
                        var consecFails = 0
                        while (true) {
                            // R166 兜底模式：不再发段查询（与兜底整载互杀毫无意义——网关侧
                            // 抢占闸也已见牌让位），改 GetFile 轮询推水位：整载从 0 顺序扫描，
                            // 扫过本连接区间即放行，完成则直接放行到尾。期间服务循环的
                            // 等水位/客户端断开探测/超时语义照常生效。
                            if (fallbackEngaged) {
                                val g = runCatching {
                                    kotlinx.coroutines.withTimeout(GET_FILE_TIMEOUT_MS) { getFile(fileId) }
                                }.getOrNull()
                                val l = g?.local
                                when {
                                    l == null -> delay(500) // 查询失败：退避重试
                                    l.isDownloadingCompleted -> {
                                        covered = end + 1
                                        coveredTo.set(covered)
                                        PlaybackTrace.log("pumpFallbackDone", "f$fileId", "整载完成 水位=$covered")
                                    }
                                    l.downloadOffset <= start -> {
                                        // 整载锚在本连接区间之前（正常口径=0）：扫描前缘
                                        // 推进到哪里就认领到哪里（只认连续扫过段）
                                        val adv = (l.downloadOffset + l.downloadedPrefixSize)
                                            .coerceAtMost(end + 1)
                                        if (adv > covered) {
                                            covered = adv
                                            coveredTo.set(covered)
                                        }
                                        delay(300)
                                    }
                                    else -> delay(300) // 锚点异常：等网关侧兜底整载真正起跑
                                }
                                if (covered > end) break
                                continue
                            }
                            val need = consumerPos.get()
                            // R190 倍速感知前瞻：前瞻垫随播放消耗速率放大（2x=32MB），
                            // 速度源=PlaybackSpeedBus（PlayerPool 全池倍速重算写入）。
                            // 常速（含暂停）回落 16MB 基准——停车口径与旧版逐字节一致
                            val ahead = pumpAheadBytesFor(
                                PUMP_AHEAD_BYTES,
                                dev.tgfeed.core.playback.PlaybackSpeedBus.speed,
                            )
                            if (covered > end || covered >= need + ahead) {
                                delay(150) // 覆盖已够：停车待命（取消经 delay 传播）
                                continue
                            }
                            val off = maxOf(covered, need).coerceAtMost(end)
                            // R145 尾探针小首块：起点在文件末尾 1MB 内的连接按 moov 探针对待，
                            // 首块 256KB（旧一律 512KB）——探针只要几十~几百 KB 就能开读，
                            // 高 RTT 代理下首块耗时直接减半；后续块照常 2MB 大块
                            val firstCap = if (start > total - TAIL_PROBE_ZONE_BYTES) {
                                TAIL_PROBE_CHUNK_BYTES
                            } else {
                                CHUNK_SIZE
                            }
                            val blockCap = if (firstBlock) firstCap else PUMP_BLOCK_BYTES
                            val len = minOf(blockCap, end - off + 1)
                            var failure: String? = null
                            var cancelled = false
                            val res = downloadSemaphore.withPermitIO {
                                fileMutex(fileId).withLock {
                                    runCatching {
                                        kotlinx.coroutines.withTimeout(PUMP_TIMEOUT_MS) { downloadPart(fileId, off, len) }
                                    }.onFailure {
                                        failure = "${it.javaClass.simpleName}: ${it.message}"
                                        // R80：withTimeout 抛的 TimeoutCancellationException 也是
                                        // CancellationException 的子类，旧实现把它误判成
                                        // "连接收尾取消"→ 泵悄悄自杀、不记失败、不退避重试，
                                        // 水位永远停摆（实测慢网/卡死时 水位=0 死循环：
                                        // 新连接→泵 20s 超时自杀→10s 等数据超时→500→重连…）。
                                        // 真取消只有泵协程自身被 cancel（连接收尾）；
                                        // 查询超时属于失败，要退避重试而不是退场
                                        cancelled = it is kotlinx.coroutines.CancellationException &&
                                            it !is kotlinx.coroutines.TimeoutCancellationException
                                    }
                                }
                            }
                            when {
                                cancelled -> {
                                    // 连接收尾/seek 的客户端取消属正常退场：抛出让泵协程终止，
                                    // 不记失败、不退避（TDLib 侧在途查询任其完成入缓存）
                                    PlaybackTrace.log("pumpStop", "f$fileId", "取消退场 covered=$covered")
                                    throw kotlinx.coroutines.CancellationException("泵收尾")
                                }
                                res.isFailure -> {
                                    Log.d(TAG, "泵查询未竟 fileId=$fileId off=$off len=$len: $failure（退避重试）")
                                    PlaybackTrace.log("pumpRetry", "f$fileId", "off=$off len=$len: $failure")
                                    consecFails++ // R166 兜底计数
                                    delay(500)
                                }
                                else -> {
                                    // R81 落盘窗口校验：downloadPart 返回的是「经 TDLib 已下载
                                    // 窗口核对过的连续覆盖终点」，null = 该区间未能确认落盘
                                    //（同步查询失败/被顶掉也会正常返回，旧实现把这种假成功
                                    // 也推进水位 → 服务循环发出预分配空洞的 0 字节 → 3001）。
                                    // 验证不过按失败退避重试；只前进了部分也只认领验证过的边界
                                    val verifiedTo = res.getOrNull()
                                    if (verifiedTo == null || verifiedTo <= covered) {
                                        noteIntegrityGate(fileId) // R86 闸命中登记
                                        Log.d(
                                            TAG,
                                            "泵落盘校验未过 fileId=$fileId off=$off len=$len " +
                                                "verifiedTo=$verifiedTo（退避重试，水位不动）",
                                        )
                                        PlaybackTrace.log(
                                            "pumpVerifyFail", "f$fileId",
                                            "off=$off len=$len verifiedTo=$verifiedTo 水位=$covered",
                                        )
                                        consecFails++ // R166 兜底计数
                                        delay(500)
                                    } else {
                                        covered = verifiedTo.coerceAtMost(end + 1)
                                        coveredTo.set(covered)
                                        blockSeq++
                                        consecFails = 0 // R166：任何成功清零
                                        if (covered >= start + CHUNK_SIZE) firstBlock = false
                                        // 校验观测采样：首两块与每 8 块一记（全量记会刷屏环形缓冲）
                                        if (blockSeq <= 2 || blockSeq % 8 == 0) {
                                            PlaybackTrace.log(
                                                "pumpBlock", "f$fileId",
                                                "off=$off len=$len 验证通过→水位=$covered",
                                            )
                                        }
                                    }
                                }
                            }
                            // R166 整下最终兜底：连续失败达阈值 = 分段拉取持续不可得
                            // （TDLib 拒绝重锚/该区间网络死路）——经回调转整载（网关侧
                            // 显式上报+登记），本泵转入轮询推水位模式。兜底是最终手段：
                            // 正常网络下永不应触达；触达必有 trace（整下兜底）留证。
                            if (!fallbackEngaged && consecFails >= PUMP_FALLBACK_FAILS) {
                                fallbackEngaged = true
                                PlaybackTrace.log(
                                    "pumpFallback", "f$fileId",
                                    "连续失败${consecFails}次转整下兜底 水位=$covered",
                                )
                                runCatching { fullDownload(fileId) }
                            }
                        }
                    }
                    try {
                        var rafOpenFails = 0
                        // R76 客户端断开探测计时：等水位/短读轮询只等不写，感知不到对端 FIN。
                        // 滑走页面的 ExoPlayer 断开 socket 后，僵尸连接占着线程/泵/全局下载
                        // 许可直到 10s 超时——快速连切时 7 路连接池被僵尸占满（新连接 503）、
                        // 4 个下载许可被僵尸泵排光（当前页首块永远不就绪，实测「下载中 0%」
                        // 卡死一分多钟）。每秒探测一次，确认对端已走就当退场
                        //（finally 停泵、释放许可与线程）。
                        // R124：探测方式由 sendUrgentData 换成零写入的读式探测
                        //（根因与 A/B 实证见 [clientGone] 注释）——URG 探测在分屏多路的
                        // 水位等待期每秒一发，正是密集 3001 Invalid NAL 的来源
                        var lastGoneProbeMs = 0L
                        while (pos <= end) {
                            val chunkLen = minOf(CHUNK_SIZE, end - pos + 1)
                            consumerPos.set(pos)
                            // 等泵的落盘水位越过本块（coveredTo 语义=泵查询成功=TDLib 保证
                            // 该范围已真实下载，读盘不可能拿到预分配空洞的 0 字节）；
                            // 本地文件句柄可能尚未被 TDLib 创建，同样在轮询里等
                            val deadlineMs = SystemClock.elapsedRealtime() + CHUNK_TIMEOUT_MS
                            while (coveredTo.get() < pos + chunkLen &&
                                SystemClock.elapsedRealtime() < deadlineMs
                            ) {
                                delay(60)
                                val nowProbe = SystemClock.elapsedRealtime()
                                if (nowProbe - lastGoneProbeMs >= 1_000L) {
                                    lastGoneProbeMs = nowProbe
                                    if (clientGone(input, socket)) {
                                        Log.d(TAG, "客户端已断开（读式探测），连接提前退场 fileId=$fileId pos=$pos")
                                        PlaybackTrace.log("connGone", "f$fileId#$connSeq", "pos=$pos")
                                        return@runBlocking
                                    }
                                }
                            }
                            if (coveredTo.get() < pos + chunkLen) {
                                Log.w(TAG, "等数据超时 fileId=$fileId pos=$pos 水位=${coveredTo.get()}")
                                PlaybackTrace.log(
                                    "waterTimeout", "f$fileId",
                                    "pos=$pos 水位=${coveredTo.get()} 头已发=$headersSent",
                                )
                                if (!headersSent) {
                                    // R81：数据不就绪一律 503（R78 分诊属可重试瞬态），
                                    // 绝不降级发坏字节（解析终态不可重试）
                                    respondError(out, 503)
                                    return@runBlocking
                                }
                                break // 中途超时：截断，播放器报网络错误可重试
                            }
                            if (raf == null) {
                                // R81 句柄开启闸：现取 GetFile 新鲜路径 + inode 钉 + 长度覆盖校验，
                                // 打开「成功」也不再盲信（temp 名会被新下载复用成别的文件——
                                // 旧实现只堵打开失败，打开成功开到错文件照读照发 = 3001）
                                raf = openVerifiedRaf(fileId, pos + chunkLen)
                                if (raf == null) {
                                    // 水位已过但句柄未就绪/校验未过：最多再试 3 轮，防路径永久失效空转
                                    if (++rafOpenFails >= 3) {
                                        Log.w(TAG, "文件句柄三次未就绪 fileId=$fileId pos=$pos")
                                        PlaybackTrace.log(
                                            "regionReject", "f$fileId",
                                            "pos=$pos 句柄三次未就绪 头已发=$headersSent",
                                        )
                                        if (!headersSent) {
                                            respondError(out, 503)
                                            return@runBlocking
                                        }
                                        break
                                    }
                                    continue
                                }
                                rafOpenFails = 0
                            }
                            raf!!.seek(pos)
                            var firstRead = raf!!.read(buf, 0, minOf(buf.size.toLong(), chunkLen).toInt())
                            if (firstRead <= 0) {
                                // 水位称已落盘但读不到（路径漂移等异常）：关旧句柄按新鲜路径重开一次
                                Log.w(TAG, "水位内读空 fileId=$fileId pos=$pos，重取路径重试")
                                runCatching { raf!!.close() }
                                raf = openVerifiedRaf(fileId, pos + chunkLen)
                                if (raf == null) {
                                    if (!headersSent) {
                                        respondError(out, 503)
                                        return@runBlocking
                                    }
                                    break
                                }
                                raf!!.seek(pos)
                                firstRead = raf!!.read(buf, 0, minOf(buf.size.toLong(), chunkLen).toInt())
                                if (firstRead <= 0) {
                                    if (!headersSent) {
                                        respondError(out, 503)
                                        return@runBlocking
                                    }
                                    break
                                }
                            }
                            // R81 零洞闸：水位宣称已落盘的区域读出整缓冲 0 字节 = 预分配空洞实证，
                            // 当场拒发（真实视频流不存在 64KB 纯零区——盒头/NAL 长度前缀皆非零）。
                            // 头前 503 走播放器重试链；头后截断（已发出的字节都是真实的）
                            if (allZero(buf, firstRead)) {
                                noteIntegrityGate(fileId) // R86 闸命中登记
                                Log.w(TAG, "零洞拦截 fileId=$fileId pos=$pos n=$firstRead 水位=${coveredTo.get()}")
                                PlaybackTrace.log(
                                    "holeGuard", "f$fileId",
                                    "pos=$pos n=$firstRead 水位=${coveredTo.get()} 头已发=$headersSent",
                                )
                                if (!headersSent) {
                                    respondError(out, 503)
                                    return@runBlocking
                                }
                                break
                            }

                            if (pos == start) {
                                val firstMs = SystemClock.elapsedRealtime() - connStartMs
                                Log.i(TAG, "首块就绪 fileId=$fileId 耗时${firstMs}ms")
                                PlaybackTrace.log("firstBlock", "f$fileId", "耗时${firstMs}ms")
                            }
                            // 首块就绪：此刻才发响应头（200/206 背后一定有真实数据）
                            if (!headersSent) {
                                out.write(headers.toByteArray(Charsets.US_ASCII))
                                out.flush()
                                headersSent = true
                            }

                            // 写出本块（水位已越过本块且经落盘校验；短读兜底轮询到截止线）
                            var remaining = chunkLen
                            out.write(buf, 0, firstRead)
                            pos += firstRead
                            remaining -= firstRead
                            var midTimeout = false
                            var holeHit = false
                            while (remaining > 0) {
                                val want = minOf(buf.size.toLong(), remaining).toInt()
                                var n = raf!!.read(buf, 0, want)
                                while (n <= 0 && !midTimeout) {
                                    if (SystemClock.elapsedRealtime() >= deadlineMs) {
                                        midTimeout = true
                                    } else {
                                        delay(60)
                                        // R76：同上的客户端断开探测（短读轮询同样只等不写）
                                        val nowProbe2 = SystemClock.elapsedRealtime()
                                        if (nowProbe2 - lastGoneProbeMs >= 1_000L) {
                                            lastGoneProbeMs = nowProbe2
                                            if (clientGone(input, socket)) {
                                                Log.d(TAG, "客户端已断开（读式探测），连接提前退场 fileId=$fileId pos=$pos")
                                                PlaybackTrace.log("connGone", "f$fileId#$connSeq", "pos=$pos 短读轮询")
                                                return@runBlocking
                                            }
                                        }
                                        raf!!.seek(pos)
                                        n = raf!!.read(buf, 0, want)
                                    }
                                }
                                if (n <= 0) break // midTimeout：本块写不满，截断收尾
                                // R81 零洞闸（块内后续缓冲同样要过）
                                if (allZero(buf, n)) {
                                    noteIntegrityGate(fileId) // R86 闸命中登记
                                    Log.w(TAG, "零洞拦截 fileId=$fileId pos=$pos n=$n 水位=${coveredTo.get()}")
                                    PlaybackTrace.log(
                                        "holeGuard", "f$fileId",
                                        "pos=$pos n=$n 水位=${coveredTo.get()} 头已发=true",
                                    )
                                    holeHit = true
                                    break
                                }
                                out.write(buf, 0, n)
                                pos += n
                                remaining -= n
                            }
                            out.flush()
                            if (midTimeout || holeHit) break

                            // R72 速率日志：每 5s 一记（服务字节=播放器实际消费速率）
                            val nowMs = SystemClock.elapsedRealtime()
                            if (nowMs - lastRateLogMs >= 5_000) {
                                val servedKb = (pos - start) / 1024
                                val totalMs = nowMs - connStartMs
                                val winMs = nowMs - lastRateLogMs
                                val winKb = (pos - start - lastRateLogBytes) / 1024
                                Log.i(
                                    TAG,
                                    "流速率 fileId=$fileId 已服务${servedKb}KB 总耗时${totalMs}ms " +
                                        "均速${servedKb * 1000 / (totalMs + 1)}KB/s " +
                                        "近窗${winKb * 1000 / winMs}KB/s",
                                )
                                lastRateLogMs = nowMs
                                lastRateLogBytes = pos - start
                            }
                        }
                    } finally {
                        // 收尾停泵（正常到尾/503 早退/seek 断连都覆盖；泵内 delay/withTimeout
                        // 都是取消点，TDLib 侧在途查询任其完成，本地缓存不浪费）
                        pump.cancel()
                        // R81 连接终态观测：发出了多少字节/终态水位（完整性事件复盘口径）
                        PlaybackTrace.log(
                            "connDone", "f$fileId#$connSeq",
                            "发出=${(pos - start) / 1024}KB 水位=${coveredTo.get()} 头已发=$headersSent",
                        )
                    }
                }
            } catch (e: Exception) {
                // 意外异常且头没发出去 → 给 500（ExoPlayer 抛 HttpDataSourceException，可重试）。
                // 注意数据不就绪类失败在上面各闸已一律回 503（语义更准：服务暂不可供，请重试）；
                // 播放器主动断开（暂停/滑走/seek）属正常
                if (!headersSent) {
                    Log.w(TAG, "流失败 fileId=$fileId: ${e.javaClass.simpleName}: ${e.message}")
                    PlaybackTrace.log("serveFail", "f$fileId", "${e.javaClass.simpleName}: ${e.message}")
                    runCatching { respondError(out, 500) }
                } else {
                    Log.d(TAG, "流中断 fileId=$fileId: ${e.javaClass.simpleName}")
                    PlaybackTrace.log("serveAbort", "f$fileId", e.javaClass.simpleName)
                }
            } finally {
                // R166 在播登记摘牌：本连接结束（正常到尾/断开/出错）即释放计数
                runCatching { unmarkStreamingActive(fileId) }
                runCatching { raf?.close() }
                runCatching { socket.close() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "请求处理失败: ${e.message}")
            runCatching { socket.close() }
        }
    }

    /**
     * R81 目标文件句柄开启闸（水位已过、准备读盘前调用）：
     *  1. 现取 GetFile 新鲜路径——不跨块缓存路径：TDLib 整载完成会把 temp 改名/删除，
     *     temp 名还会被新下载复用成别的文件（R72 实证），缓存路径打开「成功」也可能
     *     是错的文件，旧实现只堵打开失败分支；
     *  2. inode 钉：fstat(句柄) 对 stat(路径) 比对 dev+ino——钉住「开的确实是这个
     *     路径此刻指向的文件」，防开启瞬间名字被换绑（syscall 不可用的 ROM 放行，
     *     其余闸仍在岗）；
     *  3. 长度闸：文件长度必须覆盖目标读区终点——要读的区域连长度都不够，
     *     必是错文件/预分配空洞。
     * 任一闸不过返回 null（调用方按数据不就绪处理：头前 503 / 头后截断重试）。
     */
    private suspend fun openVerifiedRaf(fileId: Int, needEndExclusive: Long): RandomAccessFile? {
        val path = runCatching {
            kotlinx.coroutines.withTimeout(GET_FILE_TIMEOUT_MS) { getFile(fileId) }.local.path
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val raf = runCatching { RandomAccessFile(path, "r") }.getOrNull() ?: return null
        val okLen = runCatching { raf.length() >= needEndExclusive }.getOrDefault(false)
        val okInode = runCatching {
            val byFd = android.system.Os.fstat(raf.fd)
            val byPath = android.system.Os.stat(path)
            byFd.st_dev == byPath.st_dev && byFd.st_ino == byPath.st_ino
        }.getOrDefault(true) // syscall 异常不堵路：路径新鲜度+长度闸+零洞闸仍在岗
        if (!okLen || !okInode) {
            runCatching { raf.close() }
            PlaybackTrace.log(
                "regionReject", "f$fileId",
                "开闸未过 长度够=$okLen inode一致=$okInode 需覆盖到=$needEndExclusive …${path.takeLast(20)}",
            )
            return null
        }
        return raf
    }

    /**
     * R124 零写入的客户端断开探测（替换 R76 的 sendUrgentData URG 探测）：
     * 临时把读超时压到 1ms 试读请求流——读超时 = 对端活着只是没数据发我们；
     * 返回 -1 = 对端已 FIN；异常 = RST/已断。GET 请求无请求体、Connection: close
     * 不流水线复用，试读不会吞掉任何业务字节（头部解析早已完成）。
     *
     * 换探测方式的原因（R124 根因）：R76 的 URG 探测在水位等待期每秒一发
     * sendUrgentData，向数据流注入带外标记字节。桌面 JVM 对照实验里接收端
     * 会正确抠除带外字节（流不受损），但平板（MIUI 内核接收路径）实测受损：
     * 分屏多路并发时水位等待频繁 → 探测密集 → 播放器收到的字节流被打乱，
     * extractor 报 3001 Invalid NAL（R81 各闸全程静默、盘文件 ffmpeg 验证完好、
     * 逐块指纹与盘一致——服务器侧零错误，坏字节只可能在写出之后产生）。
     * A/B 实证：仅把 URG 探测换成本读式探测，同机同分屏同并发强度
     *（297 次水位等待/21 连接），3001 由「27 分钟 30+ 次」降为 0。
     */
    private fun clientGone(input: java.io.InputStream, socket: Socket): Boolean {
        return runCatching {
            val oldTimeout = socket.soTimeout
            try {
                socket.soTimeout = 1
                when {
                    input.read() < 0 -> true // 对端已关闭（FIN）
                    else -> false
                }
            } catch (e: java.net.SocketTimeoutException) {
                false // 读超时：对端活着
            } catch (e: Exception) {
                true // 连接异常：按已断开处理
            } finally {
                runCatching { socket.soTimeout = oldTimeout }
            }
        }.getOrDefault(true)
    }

    private fun respondError(out: java.io.OutputStream, code: Int) {
        val msg = when (code) {
            404 -> "Not Found"
            416 -> "Range Not Satisfiable"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        runCatching {
            out.write("HTTP/1.1 $code $msg\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
        }
    }

    companion object {
        private const val TAG = "TgStreamServer"
        private const val CHUNK_SIZE = 512L * 1024L

        /**
         * R72 泵单块查询大小：2MB/次。取舍：块越大 RTT 摊薄越好；但同文件互斥下
         * moov 尾探针连接最多等一个在途块，块越大探针等待越久（实测 4MB 块在
         * ~800KB/s 链路上探针首块拖到 8.8s，2MB 减半到可接受），2MB 是平衡点
         */
        private const val PUMP_BLOCK_BYTES = 2L * 1024 * 1024

        /**
         * R145 泵前瞻：消费位置前方保持的已请求覆盖量，8MB→16MB。
         * 依据：高 RTT 代理下泵速有低谷（华为平板基线近窗 165~344KB/s 的塌陷段，
         * 原因之一是 TDLib 侧重定位/其他请求挤占），前瞻就是播放器吃低谷的缓冲垫
         * ——8MB @ 4.8Mbps 仅约 13 秒垫，10 秒级的塌陷就见底回 BUFFERING；
         * 16MB 翻倍垫层，代价只是 TDLib 磁盘缓存多占（有存储水位闸管着，不伤内存）。
         * R190：此值为**常速基准**——倍速档由 [pumpAheadBytesFor] 按播放速度放大
         * （2x=32MB），泵循环每次发块前现读（见调用处注释）。
         */
        private const val PUMP_AHEAD_BYTES = 16L * 1024 * 1024

        /**
         * R145 尾探针识别区：连接起点落在文件末尾 1MB 内视为 moov 尾探针。
         * mp4 的 moov 盒多在文件尾（TG 上传原片常见），ExoPlayer 起播前要开第二条
         * Range 连接去尾段取 moov——它实际只需要几十~几百 KB，旧口径首块照样拉
         * 512KB，高 RTT 代理下白等一倍时间
         */
        private const val TAIL_PROBE_ZONE_BYTES = 1024L * 1024

        /** R145 尾探针首块大小：256KB（典型 moov 盒量级，够探针直接开读） */
        private const val TAIL_PROBE_CHUNK_BYTES = 256L * 1024

        /** 泵单块查询超时（毫秒）：2MB 块的宽松上限，卡死时退避重试而非悬挂 */
        private const val PUMP_TIMEOUT_MS = 20_000L

        /**
         * R166 整下兜底触发阈值（连续失败次数）：查询异常与落盘校验未过都计数，任何
         * 成功清零。取 6（每失败退避 500ms，≈3s+ 持续失败才兜底）——避开单次网络抖动
         * /重锚瞬态，只有分段拉取真的持续不可得才转整载（最终兜底，显式上报）
         */
        private const val PUMP_FALLBACK_FAILS = 6

        /** 服务循环等单位数据落的总超时（毫秒）：超时 → 头前 503 / 头后截断（UX 上界不变） */
        private const val CHUNK_TIMEOUT_MS = 10_000L

        /**
         * 下载查询全局并发闸（R72 由 3 调 4）：泵每连接只占 1 个在途查询，
         * 4 许可覆盖分屏多格各一路泵，削平对 TDLib 的瞬时压力。
         */
        private val downloadSemaphore = Semaphore(4)

        /**
         * 同文件 DownloadFile 互斥锁表（R72）：TDLib 对同一 fileId 的并发下载查询
         * 会互杀（"Canceled by another downloadFile request"，moov 尾探针与正播交错
         * 实测全灭），同文件查询一律串行。锁表按 fileId 惰性登记、会话级驻留
         * （单条锁对象几十字节，播放过的文件数量有限，不清理）。
         */
        private val fileMutexes = java.util.concurrent.ConcurrentHashMap<Int, Mutex>()

        /** 取某文件的互斥锁（不存在则登记） */
        private fun fileMutex(fileId: Int): Mutex = fileMutexes.getOrPut(fileId) { Mutex() }

        /**
         * R86 完整性闸命中登记：fileId → 最近一次「泵落盘校验未过 / 零洞拦截」的
         * 毫秒时刻（elapsedRealtime）。供播放层在内容损坏类错误（3001 等）发生时
         * 复盘闸的动静：该文件近期有命中 = 闸在拦但仍有漏网字节进播放器；
         * 完全无命中 = 闸全程静默（坏字节过了所有闸，校验口径存在盲区）。
         * 会话级驻留（每文件一个 Long，量小不清理）。
         */
        private val integrityGateHits = java.util.concurrent.ConcurrentHashMap<Int, Long>()

        /** 登记一次完整性闸命中（泵落盘校验未过 / 零洞拦截处调用） */
        internal fun noteIntegrityGate(fileId: Int) {
            integrityGateHits[fileId] = SystemClock.elapsedRealtime()
        }

        /** 该文件最近一次完整性闸命中时刻（elapsedRealtime ms）；从未命中返回 null */
        internal fun lastIntegrityGateMs(fileId: Int): Long? = integrityGateHits[fileId]

        /**
         * R166 在播登记表：fileId → 状态（供流连接数 + 最近一次连接注册时刻）。
         * WiFi 预载让位闸的数据源——预载整载与泵同文件在 TDLib 侧只有一个下载窗口
         * 锚点，预载盯着正在播的文件磨就是抢 seek 能力（用户实锤「必须整个视频
         * 下载完才能快进」的窗口竞争源之一）。handle() 供流开始 +1 / finally -1；
         * 同文件多连接（尾探针+正播）自然累加。会话级驻留（量小不清理）。
         * R195：值升级为「计数+lastConnMs」——新连接注册即打时间戳（mark 侧），
         * 整载器（AggressivePreloadManager）用「距最近一次新连接注册 ≥5s」当
         * 起播稳定闸（seek 重开/moov 尾探针/R164b 预览带扫动=不稳定事件全落在
         * 新连接注册上）；unmark（连接关闭）刻意不打戳——暂停时连接长驻、
         * churn 短缝不算不稳定。
         */
        private val streamingConns =
            java.util.concurrent.ConcurrentHashMap<Int, StreamingFileState>()

        /** 供流开始时登记一条在播连接（handle() 调用） */
        private fun markStreamingActive(fileId: Int) {
            runCatching {
                val s = streamingConns.getOrPut(fileId) { StreamingFileState() }
                s.conns.incrementAndGet()
                s.lastConnMs = SystemClock.elapsedRealtime()
            }
        }

        /** 连接结束时释放一条在播计数（handle() finally 调用；归零即从表内摘除） */
        private fun unmarkStreamingActive(fileId: Int) {
            val s = streamingConns[fileId] ?: return
            val left = s.conns.decrementAndGet()
            if (left <= 0) streamingConns.remove(fileId)
        }

        /** 该文件当前是否有供流连接在服务（WiFi 预载让位闸查询口） */
        internal fun isFileStreaming(fileId: Int): Boolean =
            (streamingConns[fileId]?.conns?.get() ?: 0) > 0

        /**
         * R195 在播快照（整载器 1Hz 消费）：当前有供流连接的文件 →
         * （连接数，最近一次连接注册时刻）。map 是浅拷贝，条目对象读取并发安全
         * （已摘除条目的末值读取无害）。
         */
        internal fun streamingFilesSnapshot(): Map<Int, StreamingFileState> =
            streamingConns.entries.associate { it.key to it.value }

        /** R196 当前供流中的 fileId 全集（存储治理·temp 清扫的活跃排除查询口） */
        internal fun streamingFileIds(): Set<Int> = streamingConns.keys.toSet()

        /** R196 是否有任一文件正在供流（治理重活错峰判定：正播时 GetStorageStatistics
         *  全量扫描与泵的窗口查询挤资源，等空闲再跑） */
        internal fun anyFileStreaming(): Boolean = streamingConns.isNotEmpty()

        /** P1-3 连接处理线程池上限（含常驻 accept 线程 1 个，流式连接实际并发 7 路） */
        private const val MAX_THREADS = 8

        /** P1-3 accept 连续失败达到此次数即重建 serverSocket（瞬时错误在此之前靠退避吸收） */
        private const val MAX_ACCEPT_FAILURES = 5

        /** P1-3 GetFile 的统一超时（毫秒）：TDLib 卡死时快速失败，防线程悬挂 */
        private const val GET_FILE_TIMEOUT_MS = 10_000L

        /** R124 诊断：连接序号发生器（同文件多连接的 trace 可区分） */
        private val connSeqGen = AtomicInteger(0)
    }
}

/** Semaphore 的协程化 withPermit（本文件局部工具）：取许可→执行→必释放 */
private suspend inline fun <T> Semaphore.withPermitIO(crossinline block: suspend () -> T): T {
    acquire()
    try {
        return block()
    } finally {
        release()
    }
}

/** R81 零洞判定：缓冲前 n 字节是否全 0（水位覆盖区内读出全零 = TDLib 预分配空洞实证） */
private fun allZero(b: ByteArray, n: Int): Boolean {
    for (i in 0 until n) if (b[i].toInt() != 0) return false
    return true
}

/**
 * R166 窗口抢占闸判定（纯函数；TdLibGateway.downloadPart 段查询前调用，行为单测
 * StreamWindowPolicyTest）：TDLib 同文件同时只有一个下载窗口锚点（[winStart, winEnd)
 * = [downloadOffset, +downloadedPrefixSize)），本段查询要不要先取消在途属主——
 *  - 文件已完成：全文件可读，无需抢占；
 *  - 已进整下兜底：兜底整载优先（分段持续失败才进的兜底，别再互杀）；
 *  - 连续（off ∈ [winStart, winEnd]）：顺序泵块/同锚请求，与在途下载同锚合并
 *    共享带宽，不取消（省一轮取消/重握手）；
 *  - 跳变（off 在窗口外）：seek 到未下载区/moov 尾探针——必须先取消属主再发
 *    本段查询，保证窗口立即重锚（快播式 seek 插队核心）。
 */
internal fun shouldPreemptDownloadWindow(
    off: Long,
    winStart: Long,
    winEnd: Long,
    completed: Boolean,
    inFallback: Boolean,
): Boolean = !completed && !inFallback && !(winStart <= off && off <= winEnd)

/**
 * R190 倍速感知泵前瞻（纯函数，行为单测 PumpAheadPolicyTest）：
 * 前瞻垫本意是「按**墙钟**吃泵速低谷」（R145 的 16MB 垫设计点按 1x 消耗算），
 * 长按 2x 期间媒体消耗速率翻倍，同样 16MB 只换得一半墙钟垫——低谷一穿就见底
 * 「加载一下」（用户实锤）。本函数让前瞻随消耗速率连续放大：
 *  - 1x/暂停/异常值 → 基准不变（16MB，停车口径与 R145 逐字节一致）；
 *  - 1.5x → 24MB、2x → 32MB（垫的墙钟时长回到 1x 设计点）；
 *  - 封顶 2×：App 速度上限 2x（长按 2x/面板 1.5-2x 档），超档（未来 3x+）也只
 *    拉双倍前瞻——磁盘缓存多占是有存储水位闸兜底的有限代价，不随速度无界。
 * 块大小/优先级不动：块 2MB 是 R145 实证的探针等待平衡点（4MB 块把 moov 尾
 * 探针首块拖到 8.8s，倍速期 seek 重开连接仍会遇探针），泵查询优先级恒 32
 * = TDLib 满档本就无提速空间。
 */
internal fun pumpAheadBytesFor(baseBytes: Long, speed: Float): Long {
    val s = if (speed.isFinite() && speed > 1f) speed.coerceAtMost(2f) else 1f
    return (baseBytes * s).toLong()
}

/**
 * R195 在播登记条目（companion streamingConns 的值类型）：
 *  - [conns]：当前供流连接数（R166 原语义，WiFi 预载让位闸/存储治理活跃集消费）；
 *  - [lastConnMs]：最近一次连接**注册**时刻（elapsedRealtime）——R195 整载器的
 *    起播稳定闸数据源（新连接=seek 重开/尾探针/预览带扫动=不稳定事件）；
 *    刻意只在 mark 侧打戳：连接关闭（churn 短缝/暂停长驻）不算不稳定。
 * 读取方（mark/unmark/snapshot 消费者）并发安全：conns 原子、lastConnMs volatile。
 */
internal class StreamingFileState internal constructor() {
    internal val conns = AtomicInteger(0)

    @Volatile
    internal var lastConnMs: Long = 0L
}
