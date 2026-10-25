package dev.tgfeed.core.playback

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * R80 播放链路结构化事件埋点（纯诊断通道，绝不进业务判定）：
 * 把播放链路关键事件记成结构化行（时间戳/条目 key 尾号/事件类型/状态快照），
 * 覆盖：ensurePlayable 发起与结果、流服务器泵起停与水位、ExoPlayer 状态迁移、
 * 看门狗判定、失败错误码与 fatalReasonOf 分类、切页取消。
 *
 * 设计要点：
 *  - 内存环形缓冲 [MAX_MEMORY] 条：热路径只进内存（一次 StringBuilder 拼装 +
 *    环形写入），调用线程零 IO；落盘走单线程队列异步追加，队列堆积有界化
 *    （超 [MAX_PENDING_WRITES] 直接丢弃，诊断通道绝不拖累播放主链）；
 *  - 落盘滚动文件 filesDir/diag/playback_trace.log（超 [MAX_FILE_BYTES] 滚动为 .1，
 *    只留一档历史），进程重启后仍可复盘；
 *  - key 只留尾号（行宽可控，长 key 不刷屏）；
 *  - 全部方法线程安全、异常自吞；DiagServer /diag 快照尾部带本缓冲。
 */
object PlaybackTrace {
    /** 内存环形缓冲容量（条） */
    private const val MAX_MEMORY = 300

    /** 落盘滚动文件上限（字节）：超过即在下次写入前滚动为 playback_trace.log.1 */
    private const val MAX_FILE_BYTES = 1024L * 1024L

    /** 落盘队列积压上限：超了直接丢（诊断数据丢了就丢了，播放链路不能等 IO） */
    private const val MAX_PENDING_WRITES = 512

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** 内存环形缓冲：synchronized 保护，读快照时整体拷贝 */
    private val ring = ArrayDeque<String>(MAX_MEMORY)

    @Volatile
    private var traceFile: File? = null

    /** 落盘单线程（守护）：所有文件 IO 都排队到这里，调用线程不沾盘 */
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "playback-trace").apply { isDaemon = true }
    }

    @Volatile
    private var pendingWrites = 0

    /** 进程启动时装机（App.onCreate，紧随 DiagLog.install）：确定落盘文件。幂等 */
    @Synchronized
    fun install(context: Context) {
        if (traceFile != null) return
        val dir = File(context.filesDir, "diag").apply { runCatching { mkdirs() } }
        traceFile = File(dir, "playback_trace.log")
        log("trace装机", detail = "dir=${dir.absolutePath}")
    }

    /**
     * 写一条播放链路事件。
     * @param event 事件类型短词（如 ensureOk/pumpStart/waterTimeout/state/err/dog/retry/gone）
     * @param key   条目 key（只留尾号）或 fileId 标记（如 "f1234"）
     * @param detail 状态快照（错误码/水位/耗时等），可空
     */
    fun log(event: String, key: String = "", detail: String = "") {
        runCatching {
            val sb = StringBuilder(96)
            sb.append(timeFormat.format(Date()))
            if (key.isNotEmpty()) sb.append(' ').append('…').append(key.takeLast(12))
            sb.append(' ').append(event)
            if (detail.isNotEmpty()) sb.append(' ').append(detail)
            val line = sb.toString()
            synchronized(ring) {
                while (ring.size >= MAX_MEMORY) ring.removeFirst()
                ring.addLast(line)
            }
            val f = traceFile ?: return
            // 积压超上限直接丢：埋点是旁路观测，绝不能让播放链路为落盘排队
            if (pendingWrites >= MAX_PENDING_WRITES) return
            pendingWrites++
            writer.execute {
                try {
                    if (f.length() > MAX_FILE_BYTES) {
                        val bak = File(f.parentFile, f.name + ".1")
                        runCatching { bak.delete() }
                        runCatching { if (!f.renameTo(bak)) f.delete() }
                    }
                    f.appendText(line + "\n")
                } catch (_: Exception) {
                    // 落盘失败静默：诊断通道不告警
                } finally {
                    pendingWrites--
                }
            }
        }
    }

    /** 内存环形缓冲当前快照（最旧在前） */
    fun ringSnapshot(): List<String> = synchronized(ring) { ring.toList() }

    /** 落盘滚动文件尾部内容（最多回读 [maxBytes]，防端点响应体过大） */
    fun fileTail(maxBytes: Long = 192L * 1024L): String {
        val f = traceFile ?: return ""
        return runCatching {
            if (!f.isFile) return@runCatching ""
            val len = f.length()
            if (len <= 0L) return@runCatching ""
            java.io.RandomAccessFile(f, "r").use { raf ->
                val start = if (len > maxBytes) len - maxBytes else 0L
                raf.seek(start)
                val buf = ByteArray(((len - start).coerceAtMost(maxBytes)).toInt())
                var off = 0
                while (off < buf.size) {
                    val n = raf.read(buf, off, buf.size - off)
                    if (n < 0) break
                    off += n
                }
                String(buf, 0, off, Charsets.UTF_8)
            }
        }.getOrElse { "（读取 playback_trace.log 失败: ${it.message}）" }
    }
}
