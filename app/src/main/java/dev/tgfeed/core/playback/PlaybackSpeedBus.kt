package dev.tgfeed.core.playback

/**
 * R190 播放倍速进程级总线（单写多读共享件）。
 *
 * 动机：长按 2x 时媒体消耗速率翻倍，而泵前瞻/播放器水位此前是速度盲的固定值——
 * 用户实锤「进度条明明有缓冲，长按 2x 却要加载一下」的根因半壁。泵侧
 * （TgStreamServer）需要知道「当前在播速度」来放大前瞻垫，但流服务器构造在
 * 网关层、够不到任何 UI 持有的播放器——本总线是唯一桥。
 *
 * 语义：**池内 busy 播放器当前速度的最大值**（多路分屏各 1x 时=1f；长按 2x 的
 * 那台顶到 2f）。写方唯一：PlayerPool（onPlaybackParametersChanged 全池监听 +
 * release 侧重算），读方：泵前瞻。浮点取值不追求锁精确——前瞻垫差半档只影响
 * 磁盘缓存多占几十 MB 量级（有存储水位闸兜底），不值得上锁。
 *
 * 池外播放器（音频卡共享播放器/FeedScreen 面板音频倍速）不写本总线：音频码率
 * 数量级低于视频，泵追 2x 音频消耗物理上富余（挂账见 r190_notes 阶段2）。
 */
object PlaybackSpeedBus {

    /** 当前池内最高播放速度（默认 1f；2f = 长按快进中）。@Volatile 单写多读。 */
    @Volatile
    var speed: Float = 1f
        private set

    /** 倍速中快捷判读（读总线值；判任意速度用 [isBoostSpeed]） */
    val isBoosted: Boolean
        get() = isBoostSpeed(speed)

    /**
     * 倍速档判定（共享口径，单点供 bus 与 LoadControl 策略复用）：speed 明显高于
     * 1x 才算「加速播放中」。取 `> [BOOST_THRESHOLD]` 而非 `> 1f`：浮点 panel 值/
     * 重置链可能产生 1.0000001 级噪声，不该触发水位抬升；真正 ≥1.5x 的面板档
     * 与 2x 长按档都在阈值之上。NaN 与非有限值天然判 false（不放大不误报）。
     */
    fun isBoostSpeed(speed: Float): Boolean = speed > BOOST_THRESHOLD

    /** PlayerPool 重算写入口（全池 busy 最大速度；池空回落 1f）。 */
    fun report(maxSpeedAcrossBusyPlayers: Float) {
        val v = if (maxSpeedAcrossBusyPlayers.isFinite()) maxSpeedAcrossBusyPlayers else 1f
        speed = v.coerceIn(1f, 8f)
    }

    /** 倍速档阈值（see [isBoostSpeed]）：App 面板档 1/1.5/2 与长按 2x 的公共判据 */
    const val BOOST_THRESHOLD = 1.01f
}
