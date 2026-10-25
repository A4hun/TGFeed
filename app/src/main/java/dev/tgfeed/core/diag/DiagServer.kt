package dev.tgfeed.core.diag

import android.content.Context
import android.os.Build
import dev.tgfeed.BuildConfig
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * R51 局域网只读诊断端点：常驻小型 HTTP 服务，开发者随时
 * `curl http://<设备IP>:<port>/diag` 拉走最近诊断日志，用户零操作。
 *
 * 设计要点：
 *  - 独立于 TgStreamServer（那个只绑 127.0.0.1 且随播放按需启动；本服务绑 0.0.0.0 常驻）；
 *  - 裸 ServerSocket 自实现（项目无 NanoHTTPD 依赖，按任务约定不引新依赖）；
 *  - 只读：仅响应 `GET /diag`（text/plain 诊断快照），其余路径一律 404，绝不写任何状态；
 *  - 端口从 [BASE_PORT] 起，冲突时 +1 重试，最多 [MAX_PORT_ATTEMPTS] 次；
 *  - 有界线程池 + accept 失败退避，单连接 10s 超时，诊断服务自身不能成为稳定性风险。
 *
 * R146 安全收口：端点只允许 debug 构建启动——start() 有 BuildConfig.DEBUG 硬闸，
 * release 无论从哪条调用链（容器接线/设置开关）进来都绑不出端口（端口不开、无监听）。
 * crash.log / playback trace 的落盘采集在 DiagLog / PlaybackTrace，不经过本类，照旧保留。
 */
class DiagServer(private val context: Context) {

    @Volatile
    private var serverSocket: ServerSocket? = null

    /** 实际监听端口（0 = 未启动） */
    @Volatile
    var port: Int = 0
        private set

    /** 连接处理线程池：诊断请求秒回，2 个工作线程足够，拒绝时直接丢连接 */
    private val pool = ThreadPoolExecutor(
        0, MAX_THREADS, 30L, TimeUnit.SECONDS, SynchronousQueue(),
    ) { r -> Thread(r, "diag-http").apply { isDaemon = true } }

    /** 是否正在监听 */
    val running: Boolean get() = serverSocket != null

    /** 对外展示用完整地址（无局域网 IP 时给占位说明） */
    fun diagUrl(): String {
        val p = port
        if (p <= 0) return "（未启动）"
        val ip = lanAddress() ?: return "（未获取到局域网地址，端口 $p）"
        return "http://$ip:$p/diag"
    }

    @Synchronized
    fun start() {
        // R146 安全收口硬闸：诊断端点仅 debug 构建启用——release 一律拒绝启动，
        // 端口不开、无监听。BuildConfig.DEBUG 在 release 编译期即 false，
        // R8 会把整个绑定/监听链路当死代码消除，正式包里连监听代码都不剩。
        if (!BuildConfig.DEBUG) {
            DiagLog.log(TAG, "诊断端点已按 R146 安全收口跳过启动（仅 debug 构建启用）")
            return
        }
        if (serverSocket != null) return
        val ss = bindWithRetry() ?: run {
            DiagLog.log(TAG, "诊断端点启动失败：${BASE_PORT}起连续 $MAX_PORT_ATTEMPTS 个端口均被占用")
            return
        }
        serverSocket = ss
        port = ss.localPort
        DiagLog.log(TAG, "诊断端点已启动: ${diagUrl()}（局域网只读）")
        pool.execute { acceptLoop(ss) }
    }

    @Synchronized
    fun stop() {
        val ss = serverSocket ?: return
        serverSocket = null
        port = 0
        runCatching { ss.close() }
        DiagLog.log(TAG, "诊断端点已停止")
    }

    /** 端口冲突时 +1 重试；显式绑 IPv4 通配（0.0.0.0），只绑 IPv6 会让局域网 IPv4 访问不到 */
    private fun bindWithRetry(): ServerSocket? {
        for (i in 0 until MAX_PORT_ATTEMPTS) {
            val p = BASE_PORT + i
            val ss = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", p), BACKLOG)
                }
            }.getOrNull()
            if (ss != null) return ss
        }
        return null
    }

    /** accept 失败退避重试；socket 被关/被替换（stop）时退出循环 */
    private fun acceptLoop(ss: ServerSocket) {
        var failures = 0
        while (true) {
            val socket = try {
                ss.accept()
            } catch (e: Exception) {
                if (ss.isClosed || serverSocket !== ss) return
                failures++
                try {
                    Thread.sleep((200L * failures).coerceAtMost(3_000L))
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            failures = 0
            runCatching { pool.execute { handle(socket) } }
                .onFailure { runCatching { socket.close() } }
        }
    }

    /** 只读路由：仅 GET /diag 出快照，其余一律 404；不读请求体、不写任何状态 */
    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val input = socket.getInputStream()
            val out = socket.getOutputStream()
            val reader = input.bufferedReader(Charsets.ISO_8859_1)
            val requestLine = reader.readLine() ?: return
            // 请求头读完即止（不消费 body；诊断端点不接收任何提交）
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            // 路由判断保持最简：前缀命中即出快照（/diag、/diag?x=1 均算），其余一律 404
            if (requestLine.startsWith("GET /diag")) {
                val body = buildReport().toByteArray(Charsets.UTF_8)
                val header = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: text/plain; charset=utf-8\r\n")
                    append("Content-Length: ${body.size}\r\n")
                    append("Cache-Control: no-store\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(header.toByteArray(Charsets.US_ASCII))
                out.write(body)
                out.flush()
            } else {
                out.write(
                    "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII),
                )
                out.flush()
            }
        } catch (_: Exception) {
            // 客户端断线/畸形请求：静默丢连接（诊断通道不值得告警）
        } finally {
            runCatching { socket.close() }
        }
    }

    /** 诊断快照正文：设备信息 + 熔断表 + 崩溃日志 + 自采集滚动日志 + logcat 现场抓取 */
    private fun buildReport(): String {
        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        sb.append("===== tgfeed 诊断快照 =====\n")
        sb.append("时间: ").append(now).append('\n')
        sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" / Android ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("应用: ").append(appVersionLine()).append('\n')
        sb.append("诊断端点: ").append(diagUrl()).append('\n')

        sb.append("\n===== 熔断表 flood_breaker.json =====\n")
        sb.append(readBreakerFile()).ifBlank { "（无熔断记录或尚未落盘）" }
        sb.append('\n')

        // R292 封面 OCR 试点进度（deepindex 下三件状态文件原文直出——
        // 全聚合计数口径，识别文本绝不出现；§七扩令合规）
        sb.append("\n===== R292 封面 OCR 试点（cover_ocr_*，全聚合计数）=====\n")
        sb.append(readOcrStateFiles())
        sb.append('\n')

        sb.append("\n===== 崩溃日志 crash.log =====\n")
        sb.append(DiagLog.crashLog().ifBlank { "（无崩溃记录）" })
        sb.append('\n')

        sb.append("\n===== 自采集滚动日志（内存环形缓冲 ").append(DiagLog.ringSnapshot().size)
            .append(" 条 + rolling.log 尾部）=====\n")
        val ringText = DiagLog.ringSnapshot().joinToString("\n")
        val rollingTail = DiagLog.rollingTail()
        when {
            ringText.isNotBlank() -> sb.append(ringText)
            rollingTail.isNotBlank() -> sb.append(rollingTail)
            else -> sb.append("（暂无自采集事件）")
        }
        sb.append('\n')

        // R80：播放链路结构化事件（PlaybackTrace 内存环 + playback_trace.log 尾部）
        sb.append("\n===== 播放链路事件（PlaybackTrace 内存 ").append(
            dev.tgfeed.core.playback.PlaybackTrace.ringSnapshot().size,
        ).append(" 条 + playback_trace.log 尾部）=====\n")
        val traceRing = dev.tgfeed.core.playback.PlaybackTrace.ringSnapshot().joinToString("\n")
        val traceTail = dev.tgfeed.core.playback.PlaybackTrace.fileTail()
        when {
            traceRing.isNotBlank() -> sb.append(traceRing)
            traceTail.isNotBlank() -> sb.append(traceTail)
            else -> sb.append("（暂无播放链路事件）")
        }
        sb.append('\n')

        sb.append("\n===== 系统 logcat 现场抓取（最近 500 条）=====\n")
        sb.append(DiagLog.logcatSnapshot())
        sb.append('\n')
        return sb.toString()
    }

    private fun appVersionLine(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (versionCode=${info.versionCode})"
    }.getOrElse { "（版本信息读取失败）" }

    /** 熔断表原文（filesDir/flood_breaker.json，与 FloodCircuitBreaker 落盘位置同口径） */
    private fun readBreakerFile(): String = runCatching {
        val f = java.io.File(context.filesDir, "flood_breaker.json")
        if (f.isFile) f.readText().take(MAX_BREAKER_CHARS) else ""
    }.getOrElse { "（熔断表读取失败: ${it.message}）" }

    /**
     * R292 封面 OCR 状态三件套（filesDir/deepindex/cover_ocr_*）原文直出。
     * 这三个文件由 DeepSearchIndex 侧保证只含计数/游标/长度分桶（编解码件
     * CoverOcrState.kt 结构上就塞不进识别文本），直出即合规。
     */
    private fun readOcrStateFiles(): String = runCatching {
        val dir = java.io.File(context.filesDir, "deepindex")
        val names = listOf(
            "cover_ocr_backfill_v1.json",
            "cover_ocr_apply_v1.json",
            "cover_ocr_pilot_report.json",
        )
        names.joinToString("\n") { n ->
            val f = java.io.File(dir, n)
            "$n: " + if (f.isFile) f.readText().take(4_096) else "（未落盘）"
        }
    }.getOrElse { "（OCR 状态读取失败: ${it.message}）" }

    companion object {
        private const val TAG = "DiagServer"

        /** 基准端口：避开 TgStreamServer 的动态临时端口区间（它走系统分配），固定 18963 */
        const val BASE_PORT = 18963

        /** 端口冲突重试次数（18963 ~ 18963+7） */
        private const val MAX_PORT_ATTEMPTS = 8

        private const val BACKLOG = 8
        private const val MAX_THREADS = 3
        private const val SOCKET_TIMEOUT_MS = 10_000

        /** 熔断表快照截断上限（正常只有几 KB，截断防异常膨胀拖累响应体） */
        private const val MAX_BREAKER_CHARS = 64 * 1024

        /**
         * 取本机局域网 IPv4 地址：枚举网络接口，优先 wlan/eth 前缀的有线与无线网卡，
         * 跳过回环与未启用接口；均无时返回 null（调用方自行降级展示）
         */
        fun lanAddress(): String? {
            return runCatching {
                val all = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
                val candidates = all.filter { it.isUp && !it.isLoopback }
                fun firstV4(ifaces: List<NetworkInterface>): String? =
                    ifaces.asSequence()
                        .flatMap { it.inetAddresses.toList().asSequence() }
                        .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                        ?.hostAddress
                // 先挑名字像物理网卡的，挑不到再全体兜底
                firstV4(candidates.filter {
                    val n = it.name.lowercase(Locale.US)
                    n.startsWith("wlan") || n.startsWith("eth") || n.startsWith("ap")
                }) ?: firstV4(candidates)
            }.getOrNull()
        }
    }
}
