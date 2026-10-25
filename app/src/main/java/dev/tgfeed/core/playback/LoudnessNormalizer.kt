package dev.tgfeed.core.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import dev.tgfeed.core.prefs.AppPrefs
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 音量均衡器（B 站同款"响度归一"）：挂在 ExoPlayer 音频链上的自定义 AudioProcessor，
 * 让不同内容的播放响度自动拉到同一水平，治"上一条震耳、下一条听不清"。
 *
 * 工作原理：
 *  1. 测量：新内容起播后进入约 3.2 秒的"学习窗"（8 窗 × 400ms），逐窗算 RMS 分贝；
 *     **静音窗（< -60dB）剔除**后再取中位数作本条响度估计——v1 不过滤静音窗，
 *     安静前奏把中位数拉偏，是"忽大忽小/过提升后硬钳制失真"的根因之一；
 *  2. 归一：增益dB = 目标dB - 中位实测dB，夹在 [-12dB, +9.5dB]（≈0.25x~3.0x）；
 *     目标定标 -14dBFS RMS（手机外放惯例，B 站感知约 -14~-13 LUFS；v1 的 -16 偏轻）；
 *  3. 防削波：不再用"单样本峰值"封顶（v1 的 0.95/峰值 会把安静内容的提升全部吃掉，
 *     是"整体太轻"的根因之一）——改为"学习期最响窗的 RMS 加完增益不得超过 -3dBFS"
 *     的持续电平封顶，瞬时峰值偶尔触硬钳制兜底（基本不可闻）；
 *  4. 平滑：增益绝不突变，按约 0.15s 时间常数逐样本指数趋近目标值；
 *     学成后目标增益固定（不随缓冲逐帧调），无漂移；
 *  5. 记忆：每条目学一次即把增益按内容 key 落盘（[LoudnessGainStore]），回看直接套用。
 *
 * 挂点：media3 1.4.1 的 ExoPlayer.Builder 没有 setAudioSink/setAudioProcessors
 * （javap 实证），唯一稳定注入口是 DefaultRenderersFactory.buildAudioSink（受保护、
 * ExoPlayer 构建时同步回调，javap -c 实证 createRenderers 以 invokevirtual 调它）。
 * 见本文件底部 [NormalizingRenderersFactory]：PlayerPool 与 FeedScreen 共享音频播放器
 * 共用。每台播放器一条链一个实例：分屏/悬浮窗多路并发各自独立归一。
 *
 * 线程模型：queueInput 跑在 ExoPlayer 音频处理线程，严禁磁盘 IO；
 * beginContent 由媒体切换回调（主线程）驱动。学习期累计量与 beginContent 的重置之间
 * 用 learnLock 互斥（临界区只做加法），模式/目标增益字段用 @Volatile 直通。
 *
 * 已知边界：
 *  - 只处理 16bit PCM（DefaultAudioSink 用户处理器位的标准格式，管线缓冲区已是
 *    LITTLE_ENDIAN——javap 实证 DefaultAudioSink.queueInput 有 checkArgument(order==LE)，
 *    故直接 getShort/putShort 无需再设字节序）；非 PCM16 链路 onConfigure 返回 NOT_SET
 *    整体旁路，不影响播放；
 *  - 归一作用在媒体音量之前，与静音开关（player.volume）互不干扰；
 *  - AudioCard 自管模式播放器（sharedPlayer=null，搜索/详情跳入的旧路径）在
 *    AudioCard.kt 内直建、不在本轮允许改动清单内，暂不归一；
 *  - 项目默认不开音频 offload，直通（passthrough）场景天然不归一。
 */
class LoudnessNormalizer(context: Context) : BaseAudioProcessor() {

    private val prefs = AppPrefs(context.applicationContext)
    private val gains = LoudnessGainStore.get(context)

    /** 当前内容 key（主线程写入、音频线程读）；null = 未知内容，照常学习但不记忆 */
    @Volatile
    private var contentKey: String? = null

    /** true = 学习测量中；false = 套用固定增益（已学完或命中记忆，此后目标增益不再变） */
    @Volatile
    private var learning = true

    /** 目标增益：学习完成/记忆命中时改写一次，音频线程逐样本趋近它（防爆音的关键） */
    @Volatile
    private var targetGain = 1f

    // ---- 以下字段仅音频线程访问 ----
    private var appliedGain = 1f   // 当前实际增益（向 targetGain 平滑趋近）
    private var smoothK = 0f       // 逐样本平滑系数（按采样率换算的 0.15s 时间常数）
    private var windowBudget = 0   // 一个测量窗的交织样本数（400ms × 采样率 × 声道数）
    private var logFirstBuffer = false // 每条内容只打一次"数据流通"日志

    // ---- 学习期累计量：音频线程写、主线程（beginContent）重置，一律在 learnLock 内访问 ----
    private val learnLock = Any()
    private var winSumSq = 0.0
    private var winSamples = 0
    private var learnWindowsLeft = LEARN_WINDOWS
    private var windowDbs = ArrayList<Float>()

    /**
     * 内容切换（NormalizingRenderersFactory.attachTo 的监听在主线程调用）。
     * 命中记忆直接套用已学增益（从当前增益平滑爬升，无跳变）；未命中进入学习窗。
     */
    fun beginContent(key: String?) {
        contentKey = key
        // 查记忆放锁外：首次访问有一次小文件 IO（后续走内存缓存）
        val remembered = key?.let { gains.get(it) }
        synchronized(learnLock) {
            winSumSq = 0.0
            winSamples = 0
            windowDbs = ArrayList()
            learnWindowsLeft = LEARN_WINDOWS
        }
        logFirstBuffer = true
        if (remembered != null) {
            targetGain = remembered
            learning = false
            Log.d(TAG, "内容切换 key=${key?.take(24)} 命中记忆 gain=${"%.2f".format(remembered)}")
        } else {
            targetGain = 1f
            learning = true
            Log.d(TAG, "内容切换 key=${key?.take(24)} 无记忆，进入 ${LEARN_WINDOWS} 窗学习期")
        }
    }

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        // 非 16bit PCM 不处理：返回 NOT_SET 让 AudioSink 把本处理器整体旁路（不影响播放）
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            Log.d(TAG, "onConfigure 非 PCM16（encoding=${inputAudioFormat.encoding}），整机旁路")
            return AudioProcessor.AudioFormat.NOT_SET
        }
        // 平滑系数：一阶指数趋近，时间常数 0.15s——增益变化人耳听不出阶梯
        smoothK = (1.0 - exp(-1.0 / (inputAudioFormat.sampleRate * SMOOTH_TAU_SEC))).toFloat()
        windowBudget =
            (inputAudioFormat.sampleRate * inputAudioFormat.channelCount * WINDOW_SEC).toInt()
        Log.d(
            TAG,
            "onConfigure 挂链成功 sr=${inputAudioFormat.sampleRate} ch=${inputAudioFormat.channelCount}" +
                " windowBudget=$windowBudget",
        )
        return inputAudioFormat
    }

    // 恒 active：开关切换走 queueInput 内的透传分支，运行时翻转即时生效，
    // 不依赖 AudioSink 重新 configure（isActive 只在 configure/flush 时被重查）
    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: ByteBuffer) {
        val total = inputBuffer.remaining()
        if (total == 0) return
        // 开关关闭：纯透传（一趟拷贝，开销可忽略）
        if (!prefs.volumeNormalize) {
            replaceOutputBuffer(total).put(inputBuffer).flip()
            return
        }
        if (logFirstBuffer) {
            logFirstBuffer = false
            Log.d(TAG, "数据流通：首个缓冲区 ${total}B（key=${contentKey?.take(24)}）")
        }
        val out = replaceOutputBuffer(total)
        var remaining = total
        var g = appliedGain
        val k = smoothK
        val tgt = targetGain
        // windowBudget<=0 说明 onConfigure 还没跑过（理论上不会发生，防御），此期间不学习
        val canLearn = learning && windowBudget > 0
        while (remaining >= 2) {
            val s = inputBuffer.short.toInt()
            if (canLearn) {
                // 学习窗内累计 RMS（锁内只做加法，临界区极小）
                synchronized(learnLock) {
                    winSumSq += s.toDouble() * s
                    winSamples++
                }
            }
            // 逐样本向目标增益趋近：增益永远连续变化，切增益/切条目都不爆音
            g += (tgt - g) * k
            out.putShort((s * g).roundToInt().coerceIn(-32768, 32767).toShort())
            remaining -= 2
            // 窗满结算（读完 winSamples 不加锁无碍：仅本线程写）
            if (canLearn && winSamples >= windowBudget) finishWindow()
        }
        // PCM16 按定义不会剩奇数字节；防御性忽略
        out.flip()
        appliedGain = g
    }

    /** 一个测量窗（400ms）结满：算窗分贝入列；学满 [LEARN_WINDOWS] 窗后定稿增益 */
    private fun finishWindow() {
        val db: Float
        val left: Int
        synchronized(learnLock) {
            if (winSamples == 0) return
            val meanSq = winSumSq / winSamples
            db = if (meanSq <= 0.0) -120f else (10.0 * log10(meanSq / FULL_SCALE_SQ)).toFloat()
            winSumSq = 0.0
            winSamples = 0
            windowDbs.add(db)
            learnWindowsLeft--
            left = learnWindowsLeft
        }
        Log.d(
            TAG,
            "学习窗 #${LEARN_WINDOWS - left} rms=${"%.1f".format(db)}dB（key=${contentKey?.take(24)}）",
        )
        if (left <= 0) finalizeLearn()
    }

    /** 学习窗结束：静音窗剔除后的中位分贝 → 目标增益（含上下限与持续电平封顶），记忆落盘 */
    private fun finalizeLearn() {
        val dbs = synchronized(learnLock) { ArrayList(windowDbs) }
        // 静音窗（< -60dB）剔除：安静前奏/片头静场不拉偏响度估计
        val valid = dbs.filter { it > SILENCE_WINDOW_DB }.sorted()
        if (valid.isEmpty()) {
            // 整条近静音：不增益（提了也是放大底噪），也不记忆（万一是抓取问题，下回重学）
            targetGain = 1f
            learning = false
            Log.d(TAG, "学习完成：全窗近静音，增益保持 1.0（key=${contentKey?.take(24)}）")
            return
        }
        val medianDb = valid[valid.size / 2]
        val loudDb = valid.last() // 学习期最响窗
        // RMS 归一：增益dB = 目标 - 实测中位，夹 [-12dB, +9.5dB]
        var gainDb = (TARGET_DB - medianDb).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        // 持续电平封顶：最响窗 RMS 加完增益不得越过 -3dBFS（防长期硬钳制失真；
        // 瞬时峰值允许偶尔触硬钳制，基本不可闻）
        val capDb = HEADROOM_DB - loudDb
        val cappedByLoud = gainDb > capDb
        if (cappedByLoud) gainDb = capDb
        val gain = 10f.pow(gainDb / 20f)
        targetGain = gain
        learning = false
        Log.d(
            TAG,
            "学习完成 key=${contentKey?.take(24)} 中位=${"%.1f".format(medianDb)}dB" +
                " 最响窗=${"%.1f".format(loudDb)}dB 增益=${"%.1f".format(gainDb)}dB" +
                "（${"%.2f".format(gain)}x${if (cappedByLoud) "，受最响窗封顶" else ""}）",
        )
        // key 为 null（未知内容）只生效不记忆；put 内部异步落盘，不占音频线程
        contentKey?.let { gains.put(it, gain) }
    }

    // onFlush 不清学习状态：seek/暂停续播后接着学，无需从头再来
    override fun onReset() {
        appliedGain = 1f
        targetGain = 1f
        learning = true
        contentKey = null
        synchronized(learnLock) {
            winSumSq = 0.0
            winSamples = 0
            windowDbs = ArrayList()
            learnWindowsLeft = LEARN_WINDOWS
        }
    }

    companion object {
        private const val TAG = "Loudness"

        /** 目标响度：-14dBFS RMS（手机外放惯例；B 站感知目标约 -14~-13 LUFS 的 RMS 近似） */
        private const val TARGET_DB = -14f

        /** 增益上下限（dB）：过响最多压 -12dB，过安静最多提 +9.5dB（防底噪放大） */
        private const val MIN_GAIN_DB = -12f
        private const val MAX_GAIN_DB = 9.5f

        /** 低于该响度的测量窗视为静音窗，从统计中剔除（防安静前奏拉偏中位数） */
        private const val SILENCE_WINDOW_DB = -60f

        /** 持续电平余量：学习期最响窗 RMS 加完增益不得超过 -3dBFS */
        private const val HEADROOM_DB = -3f

        /** 单个测量窗时长（秒）：400ms 滑窗 */
        private const val WINDOW_SEC = 0.4

        /** 学习窗数：8 窗 ≈ 3.2 秒采样期 */
        private const val LEARN_WINDOWS = 8

        /** 增益平滑时间常数（秒） */
        private const val SMOOTH_TAU_SEC = 0.15

        /** 16bit 满幅平方（RMS 归一基准） */
        private const val FULL_SCALE_SQ = 32768.0 * 32768.0
    }
}

/**
 * 音量均衡挂点工厂（PlayerPool 与 FeedScreen 共享音频播放器共用）。
 * media3 1.4.1 的 ExoPlayer.Builder 没有 setAudioSink/setAudioProcessors，
 * 唯一稳定的音频链注入口是受保护的 buildAudioSink——ExoPlayer 构建时同步回调一次
 * （javap -c 实证 createRenderers 以 invokevirtual 调它，覆写必生效），
 * 在此给每条音频链挂一个独立的 LoudnessNormalizer（每台播放器一条链一个实例，
 * 分屏/悬浮窗多路并发各自归一，互不串扰）。
 * enableFloatOutput/enableAudioTrackPlaybackParams 原样透传，与 DefaultRenderersFactory
 * 默认实现保持一致（setAudioProcessors 是"追加用户处理器"，默认处理链不受影响）。
 *
 * 用法：`val f = NormalizingRenderersFactory(context)` →
 * `ExoPlayer.Builder(context).setRenderersFactory(f).build().also { f.attachTo(it) }`
 */
class NormalizingRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    /**
     * 最近一次 buildAudioSink 挂上的归一器（"构建期交接棒"：播放器 .build() 返回后
     * 由 [attachTo] 同步取回接内容 key；buildAudioSink 在 .build() 内同步回调，无竞态）。
     */
    var lastNormalizer: LoudnessNormalizer? = null
        private set

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val normalizer = LoudnessNormalizer(context)
        lastNormalizer = normalizer
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(normalizer))
            .build()
    }

    /**
     * 把内容 key 转接给本工厂最近挂上的归一器（媒体切换 → beginContent）。
     * mediaId 即内容 key：VideoPage/AudioCard 装填时已显式 setMediaId(item.key)；
     * GridScreen/FloatingWindow 走 MediaItem.fromUri，mediaId 退化为流地址，
     * 同样可记忆（按地址粒度）。监听器随播放器 release 整体回收，无需摘除。
     * 必须在 .build() 返回后调用（buildAudioSink 此时已回调过）。
     */
    fun attachTo(player: ExoPlayer) {
        val normalizer = lastNormalizer ?: return
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                normalizer.beginContent(mediaItem?.mediaId?.takeIf { it.isNotEmpty() })
            }
        })
    }
}

/**
 * 音量均衡增益记忆表（LocalStore 边界冻结，自立门户小文件，与 TasteRadar 同款纪律）：
 * filesDir/store/loudness_gains.json，JSONObject：内容 key → 已学增益。
 * 内存缓存 + tmp+rename 原子落盘；写入统一甩给共享单线程守护执行器
 * （调用方是音频处理线程，严禁磁盘 IO）。上限 [MAX_ENTRIES] 条，
 * 超出淘汰最早写入的条目（插入序 LinkedHashMap 近似 LRU）。
 * store/ 整目录会被 BackupManager 数据包快照自动覆盖，无需额外登记。
 */
class LoudnessGainStore private constructor(context: Context) {

    private val file = File(File(context.filesDir, "store"), "loudness_gains.json")

    @Volatile
    private var cache: LinkedHashMap<String, Float>? = null

    @Synchronized
    fun get(key: String): Float? {
        ensureLoaded()
        return cache?.get(key)
    }

    fun put(key: String, gain: Float) {
        val snapshot: String
        synchronized(this) {
            ensureLoaded()
            val map = cache ?: return
            map[key] = gain
            while (map.size > MAX_ENTRIES) {
                val eldest = map.entries.firstOrNull() ?: break
                map.remove(eldest.key)
            }
            snapshot = serialize(map)
        }
        // 异步落盘：序列化在锁内完成（保一致性），磁盘 IO 甩出音频线程
        ioExecutor.execute { writeTextAtomic(file, snapshot) }
    }

    /** 首次访问懒加载（小文件一次小 IO，与 LocalStore 读缓存同纪律）；坏文件防御为空表 */
    private fun ensureLoaded() {
        if (cache != null) return
        val map = LinkedHashMap<String, Float>()
        runCatching {
            if (file.isFile) {
                val obj = org.json.JSONObject(file.readText())
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = obj.getDouble(k).toFloat()
                }
            }
        }
        cache = map
    }

    private fun serialize(map: LinkedHashMap<String, Float>): String {
        val obj = org.json.JSONObject()
        map.forEach { (k, v) -> obj.put(k, v.toDouble()) }
        return obj.toString()
    }

    /** tmp + rename 原子落盘（LocalStore 同款）；rename 失败直写兜底并清 tmp */
    private fun writeTextAtomic(target: File, text: String) {
        runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                runCatching { target.writeText(text) }
                tmp.delete()
            }
        }
    }

    companion object {
        /** 记忆条数上限：覆盖最近刷过的几百条内容，防文件无限膨胀 */
        private const val MAX_ENTRIES = 500

        /** 全局共享的落盘执行器：所有归一器实例（多池多路）共用一条守护 IO 线程 */
        private val ioExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "loudness-gain-io").apply { isDaemon = true }
        }

        @Volatile
        private var instance: LoudnessGainStore? = null

        fun get(context: Context): LoudnessGainStore =
            instance ?: synchronized(this) {
                instance ?: LoudnessGainStore(context.applicationContext).also { instance = it }
            }
    }
}
