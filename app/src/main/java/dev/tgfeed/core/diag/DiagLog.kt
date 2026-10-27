package dev.tgfeed.core.diag

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * R51 诊断日志采集器：给"局域网只读诊断端点"（DiagServer）供数。
 *
 * 四层数据源：
 *  1. 内存环形缓冲（[MAX_MEMORY] 条）：本模块及愿意旁路调用的代码经 [log] 写入，
 *     进程死亡即失，但胜在零 IO、随时可取；
 *  2. 落盘滚动文件（filesDir/diag/rolling.log，超 [MAX_FILE_BYTES] 滚动另存 .1）：
 *     与环形缓冲同一条写入链，进程重启后历史仍在；
 *  3. 崩溃捕获（[install] 包装默认 UncaughtExceptionHandler，链式回调原 handler）：
 *     未捕获异常全文写 filesDir/diag/crash.log，下一次拉取端点时可见；
 *  4. 系统 logcat 现场抓取（[logcatSnapshot]）：端点被拉取时现场执行
 *     `logcat -d -t N`——同 UID 读自家进程日志不需要 READ_LOGS 权限；
 *     实测不可读时调用方退化为只用前三层，本方法返回带说明的空串而非抛异常。
 *
 * 全部方法线程安全、异常自吞（诊断通道绝不能拖累业务主链路）。
 */
object DiagLog {
    private const val TAG = "DiagLog"

    /** 内存环形缓冲容量（条） */
    private const val MAX_MEMORY = 500

    /** 落盘滚动文件上限（字节）：超过即在下次写入前滚动为 rolling.log.1 */
    private const val MAX_FILE_BYTES = 2L * 1024L * 1024L

    /** logcat 现场抓取行数与超时：抓太多/等太久都会拖慢端点响应 */
    private const val LOGCAT_LINES = 500
    private const val LOGCAT_WAIT_MS = 5_000L

    /** crash.log 封顶（字节）：崩溃正文单次不过几 KB，封顶防异常风暴写爆磁盘 */
    private const val MAX_CRASH_BYTES = 512L * 1024L

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** 内存环形缓冲：synchronized 保护，读快照时整体拷贝 */
    private val ring = ArrayDeque<String>(MAX_MEMORY)

    @Volatile
    private var diagDir: File? = null

    /** 已链入的原崩溃处理器（链式回调用；只装一次，重复 install 幂等） */
    @Volatile
    private var installed = false

    /**
     * 进程启动时装机（App.onCreate）：确定落盘目录 + 包装默认崩溃处理器。
     * 幂等；原 handler 存起来，本 handler 写完 crash.log 后回调它（系统默认处理=弹崩溃框/杀进程）。
     */
    @Synchronized
    fun install(context: Context) {
        diagDir = File(context.filesDir, "diag").apply { runCatching { mkdirs() } }
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { writeCrash(thread, error) }
            // 链式回调原 handler：不吞掉系统/平台的崩溃收尾逻辑
            previous?.uncaughtException(thread, error)
        }
        log(TAG, "诊断日志采集已装机，目录=${diagDir?.absolutePath}")
    }

    /** 旁路写一条关键事件：进内存环形缓冲 + 追加落盘滚动文件 + 镜像到系统 logcat */
    fun log(tag: String, msg: String) {
        val line = "${timeFormat.format(Date())} [$tag] $msg"
        synchronized(ring) {
            while (ring.size >= MAX_MEMORY) ring.removeFirst()
            ring.addLast(line)
        }
        runCatching { appendRolling(line) }
        // 镜像进系统 logcat：logcatSnapshot 抓的就是它，双通道互为备份
        Log.i(tag, msg)
    }

    /** 内存环形缓冲当前快照（最旧在前） */
    fun ringSnapshot(): List<String> = synchronized(ring) { ring.toList() }

    /** 落盘滚动文件尾部内容（最多回读 [maxBytes]，防端点响应体过大） */
    fun rollingTail(maxBytes: Long = 256L * 1024L): String {
        val f = rollingFile() ?: return ""
        return tailOf(f, maxBytes)
    }

    /** 崩溃日志内容（无则空串） */
    fun crashLog(): String {
        val f = diagDir?.let { File(it, "crash.log") } ?: return ""
        return tailOf(f, MAX_CRASH_BYTES)
    }

    /**
     * 端点拉取时现场抓系统 logcat（同 UID 只含自家进程日志，无需 READ_LOGS）。
     * 失败/超时/为空时返回带原因的单行说明，绝不抛异常——端点据此退化为只用自采集层。
     */
    fun logcatSnapshot(lines: Int = LOGCAT_LINES): String {
        return runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", lines.toString()))
            // 流必须并发读空再 waitFor：不读的话子进程写满管道缓冲会死锁。
            // 结果经单元素数组从守护线程递回（join 先行发生，可见性有保证）。
            val holder = arrayOfNulls<String>(1)
            val readerThread = Thread {
                holder[0] = runCatching {
                    proc.inputStream.bufferedReader().use { it.readText() }
                }.getOrNull()
            }.apply { isDaemon = true }
            readerThread.start()
            // join 而非 waitFor：读完后进程自然退出，二者等价但少一次竞态
            readerThread.join(LOGCAT_WAIT_MS)
            when {
                readerThread.isAlive -> {
                    proc.destroy()
                    "（logcat 抓取超时 ${LOGCAT_WAIT_MS}ms，已放弃）"
                }
                holder[0].isNullOrBlank() -> "（logcat 抓取无输出或本环境不允许读取，已退化为自采集日志）"
                else -> holder[0]!!
            }
        }.getOrElse { "（logcat 抓取失败: ${it.javaClass.simpleName}: ${it.message}，已退化为自采集日志）" }
    }

    // ---------------- 内部实现 ----------------

    private fun rollingFile(): File? {
        val dir = diagDir ?: return null
        return File(dir, "rolling.log")
    }

    @Synchronized
    private fun appendRolling(line: String) = DiagLogWriter.submit { appendRollingSync(line) }
    private fun appendRollingSync(line: String) {
        val f = rollingFile() ?: return
        // 超上限先滚动：旧 .1 丢弃，当前整体改名 .1（只留一档历史，诊断够用）
        if (f.length() > MAX_FILE_BYTES) {
            val bak = File(f.parentFile, f.name + ".1")
            runCatching { bak.delete() }
            runCatching { if (!f.renameTo(bak)) f.delete() }
        }
        f.appendText(line + "\n")
    }

    @Synchronized
    private fun writeCrash(thread: Thread, error: Throwable) {
        DiagLogWriter.drain()
        val dir = diagDir ?: return
        val f = File(dir, "crash.log")
        if (f.length() > MAX_CRASH_BYTES) runCatching { f.delete() }
        val sb = StringBuilder()
        sb.append("===== ${timeFormat.format(Date())} 线程=${thread.name} =====\n")
        sb.append(Log.getStackTraceString(error))
        sb.append('\n')
        f.appendText(sb.toString())
    }

    /** 读文件尾部最多 [maxBytes] 字节（按 UTF-8 解码，残缺的头部多字节字符由解码器容错） */
    private fun tailOf(f: File, maxBytes: Long): String {
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
        }.getOrElse { "（读取 ${f.name} 失败: ${it.message}）" }
    }
}
