package dev.tgfeed.core.sync

/**
 * R420 后台定时同步的纯判定（无 Android 依赖，单测直测）。
 * 间隔归一、是否跳过、计量网络是否允许下媒体——Worker 只消费结论。
 */
object BackgroundSyncPolicy {

    /** 设置页可选间隔（分钟）；0=关闭 */
    val OPTIONS: List<Int> = listOf(0, 15, 30, 60)

    const val DEFAULT_MINUTES = 30

    /** 单次执行总超时（超时干净退出） */
    const val RUN_TIMEOUT_MS = 8L * 60_000L

    /** 等网关授权就绪的上限（含在总超时内） */
    const val AUTH_WAIT_MS = 60_000L

    /** 跳过原因枚举（探针输出用；NONE=执行） */
    enum class Skip { NONE, DISABLED, FOREGROUND, NO_REAL_GATEWAY, NOT_AUTHORIZED, BUSY, TIMEOUT, ERROR }

    /** 存储值归一：0 及负数=关闭；15/30/60 原样；其余非法值回默认 30 */
    fun normalizeMinutes(raw: Int): Int = when {
        raw <= 0 -> 0
        raw in OPTIONS -> raw
        else -> DEFAULT_MINUTES
    }

    /** 是否需要调度周期任务（关闭=取消） */
    fun shouldSchedule(raw: Int): Boolean = normalizeMinutes(raw) > 0

    /**
     * 执行前跳过判定（优先级：关闭 > 前台 > 无真实网关 > 未授权 > 互斥在途）。
     * 前台时直接跳过：前台已有自己的刷新。
     */
    fun preSkip(
        rawMinutes: Int,
        inForeground: Boolean,
        realGateway: Boolean,
        authorized: Boolean,
        busy: Boolean,
    ): Skip = when {
        normalizeMinutes(rawMinutes) == 0 -> Skip.DISABLED
        inForeground -> Skip.FOREGROUND
        !realGateway -> Skip.NO_REAL_GATEWAY
        !authorized -> Skip.NOT_AUTHORIZED
        busy -> Skip.BUSY
        else -> Skip.NONE
    }

    /** 计量网络只拉消息头不下媒体 */
    fun allowMediaDownload(metered: Boolean): Boolean = !metered

    /** 新条目数：本轮落盘条目中不在旧快照里的 key 数 */
    fun countNew(oldKeys: Set<String>, freshKeys: List<String>): Int = freshKeys.count { it !in oldKeys }
}
