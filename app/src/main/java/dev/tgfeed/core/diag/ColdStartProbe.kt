package dev.tgfeed.core.diag

import android.os.SystemClock

/**
 * R52 冷启动分段计时探针。
 *
 * 各关键节点调用 [mark] 打一行日志（内存环形缓冲 + 落盘滚动文件 + logcat 三通道，
 * 走 [DiagLog] 既有链路）：格式 "+Nms 节点名"，N = 距探针首响的毫秒数。
 * 探针首响约定在 App.onCreate（进程创建后第一个可控节点），
 * 故 N ≈ 距进程创建的耗时，同一进程的各行直接相减即得分段耗时。
 *
 * 纯内存静态函数，自身零 IO（IO 全在 DiagLog 内），任意线程可调用。
 */
object ColdStartProbe {

    /** 探针首响时刻（elapsedRealtime 毫秒）；0 = 尚未首响 */
    @Volatile
    private var t0 = 0L

    /** 打一个节点标记：日志自带墙钟时间戳（DiagLog 侧），本行附相对进程创建的偏移 */
    @Synchronized
    fun mark(node: String) {
        if (t0 == 0L) t0 = SystemClock.elapsedRealtime()
        val delta = SystemClock.elapsedRealtime() - t0
        DiagLog.log("ColdStart", "+${delta}ms $node")
    }
}
