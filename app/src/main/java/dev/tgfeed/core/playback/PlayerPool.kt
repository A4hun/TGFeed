package dev.tgfeed.core.playback

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.video.MediaCodecVideoDecoderException
import androidx.media3.exoplayer.video.VideoRendererEventListener
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoRenderer

/**
 * LRU 播放器池。
 * 默认容量 6：单 feed 竖滑只需 1~2 个实例，多留 4 路余量。
 * 多窗格（GridScreen）以 maxPlayers = 6 构建（曾是 9：多数设备硬解并发上限 4~6 路，
 * 9 路超限；且 9 格同时拉流会把 TgStreamServer 的 TDLib 同步分段下载打爆，
 * logcat 实证多格"解码失败"）：最多 6 个可见格同时持有播放器，
 * 超出时淘汰最久未使用的 busy 实例（彻底 release 后新建补位——不复用转手，
 * 防止旧持有方与新 key 共享同一播放器），配合网格"可见才 acquire"策略不会超额。
 * 注意：绑到 Player 上的 Listener 由使用方负责 remove，池回收时只负责 stop/clear。
 *
 * R158 idle 池延迟回收：闲置实例 stop 后的 LoadControl 缓冲池/软解帧缓存等
 * 内存只有彻底 release 才归还——静置 [IDLE_TRIM_DELAY_MS] 后把 idle 裁到
 * [IDLE_TRIM_KEEP] 台（见 [trimIdle]），长会话 native 堆余量止血。
 *
 * 后台音频镜像（onActivePlayerChanged / sharedActivePlayerListener）以单播 Feed 为目标
 * 场景；分屏多格/悬浮窗的多池并发 acquire 会互相顶"主"位（后到者得），锁屏元数据可能
 * 不是用户正看的那一格——已知限制，需要时再升级为按页面注册仲裁。
 */
class PlayerPool(
    private val context: Context,
    private val maxPlayers: Int = 6,
    // 多路并发场景（分屏 6 格 / 悬浮多窗）必须开小缓冲：默认 LoadControl 每路 1080p
    // 要吃十几 MB（缓冲+解码器输出帧），多路并发直接撑爆 192MB 堆引发 OOM 崩溃
    private val lowMemoryMode: Boolean = false,
) {
    private val idle = ArrayDeque<ExoPlayer>()
    // LinkedHashMap 维持访问序：acquire 命中时把条目摘出重挂尾部（R109 P-1 真 LRU），
    // 表头即"最久未用"，淘汰入口 busy.entries.first() 名实相副
    //（修复前只挂不刷，表头是"最早插入"，退化成 FIFO）
    private val busy = LinkedHashMap<String, ExoPlayer>()
    private var created = 0

    // ---------------- R158 idle 池延迟回收（P0-3 native 堆余量止血） ----------------

    /**
     * R158 idle 池回收调度器（主线程 Handler）：池的 acquire/release 全部由 Compose
     * 组合/销毁在主线程驱动，Handler 同线程执行无锁竞态；trimIdle 本身再加池同步锁
     * 双保险（防将来出现非主线程使用方）
     */
    private val idleTrimHandler = Handler(Looper.getMainLooper())

    /** 延迟回收任务（每次有播放器归 idle 都会重置计时——翻页活跃期池保持温热） */
    private val idleTrimRunnable = Runnable { trimIdle() }

    /** 排程/重置延迟回收：最后一次归 idle 起算静置 [IDLE_TRIM_DELAY_MS] 后执行 */
    private fun scheduleIdleTrim() {
        idleTrimHandler.removeCallbacks(idleTrimRunnable)
        idleTrimHandler.postDelayed(idleTrimRunnable, IDLE_TRIM_DELAY_MS)
    }

    /**
     * R158 idle 池回收：把闲置超 [IDLE_TRIM_DELAY_MS] 的池裁到 [IDLE_TRIM_KEEP] 台。
     *
     * 动机（R155 体检 §3 修后余量）：封面缓存修复（7fd744b，native -70%）后长会话
     * native 堆仍缓涨，嫌疑大头之一是播放器池 idle 实例的存活期——release 只做
     * stop/clearMediaItems，**不 release**：DefaultAllocator 已分配的 64KB 缓冲块池
     * （单播池 LoadControl 50s 缓冲垫口径）保留给复用、软解帧池/FFmpeg 上下文等
     * native 分配是否即时全归还不确定，6 实例常驻 = 数十 MB 级不确定存量。本回收在
     * 静置期把闲置实例彻底 release（created 额度同步归还，后续 acquire 原地重建，
     * 与既有 LRU 淘汰同一条销毁路径），busy 实例不受影响；保留 1 台供翻回上一条
     * 即时复播。归因复测（88 分钟档）在模拟器空闲后补做。
     */
    @Synchronized
    private fun trimIdle() {
        var trimmed = 0
        while (idle.size > IDLE_TRIM_KEEP) {
            destroy(idle.removeFirst())
            trimmed++
        }
        if (trimmed > 0) {
            android.util.Log.i("PlayerPool", "idle 池回收：彻底释放 $trimmed 台闲置播放器（余 ${idle.size} 台）")
        }
    }

    // R112 进度账本：key → 播放器被释放/淘汰瞬间的播放位置（毫秒）。
    // 翻页收敛时外层 LaunchedEffect(settledPage) 的 releaseAllExcept 先于失焦页的
    // 进度保存回调执行（父级 effect 先跑），失焦页再 peek 已查不到 busy 实例，
    // 进度静默丢失（翻走再翻回同一视频从 0 重播）；acquire 的 LRU 淘汰同病（V-5）。
    // 故一切 stop/release/淘汰出口先记账再动手，失焦保存经 peekPosition 回落账本取值，
    // 保证"先记账、后释放"。封顶 POSITION_LEDGER_MAX 条，超了丢最旧快照。
    private val lastPositions = LinkedHashMap<String, Long>()

    /**
     * 释放/淘汰前记账（R112）：把该 key 当前播放位置记入 [lastPositions]，
     * 供失焦页保存进度时回落读取。必须在 stop/clearMediaItems/release 之前调用
     * （stop 会把位置归零，之后再记等于白记）。仅在池同步锁内调用。
     */
    private fun snapshotPosition(key: String, player: ExoPlayer) {
        val pos = runCatching { player.currentPosition }.getOrNull() ?: return
        lastPositions.remove(key)
        lastPositions[key] = pos
        while (lastPositions.size > POSITION_LEDGER_MAX) {
            lastPositions.remove(lastPositions.entries.first().key)
        }
        android.util.Log.d("PlayerPool", "进度记账：…${key.takeLast(12)} → ${pos / 1000f}s")
    }

    /**
     * 当前"主"播放器变化回调（实例级，UI 侧可用；AudioBackgroundService 走伴生的
     * [sharedActivePlayerListener]，因为它拿不到 Compose remember 出来的池实例）。
     * 触发点：acquire / releaseAllExcept / release（恰好释放主位）/ releaseAll。
     * 注意：回调在池的同步锁内触发，监听实现里**不得回调池方法**（防重入死锁），
     * 需要异步处理的自行 post 到主线程。
     */
    var onActivePlayerChanged: ((ExoPlayer?) -> Unit)? = null

    // ---------------- R180 导航闸（App 内页面切换暂停，实例级——仅 Feed 池消费） ----------------

    /**
     * R180 导航闸状态（App 内导航播控，需求①）：true = feed 页不在台前，池内一切
     * 播放触发都要拦下。与进程级 [setBackgroundGate] 的区别：后台闸由 Activity
     * onStop 驱动（Feed/分屏/悬浮三池全管）；导航闸是**实例级**——只有
     * FeedScreen 的池会落（分屏 GridScreen/悬浮窗各自有独立池实例且本身就是台前页，
     * 不受本闸影响）。由 [setNavGate] 落/抬，FeedScreen 的导航闸 effect 驱动。
     *
     * 拦截口径（与后台闸同一手法）：池内每台播放器的 onPlayWhenReadyChanged 监听器
     * 在本闸落下期间把任何 playWhenReady=true 当场打回暂停——修实锤的「迟到开播」
     * 类病：进频道主页时还在缓冲的视频（playWhenReady=true、isPlaying=false，旧闸
     * 的 isPlaying 口径查不到它）缓冲完成后照样在主页之下开播出声（R180 实测实锤，
     * 且 autoAdvance 连锁让整条 feed 在覆盖层下逐条连播近 10 分钟）。
     */
    @Volatile
    var navGate: Boolean = false
        private set

    /**
     * R180 落/抬导航闸。
     *
     * 落闸（on=true）：①置 [navGate]（此后迟到的 playWhenReady=true 被逐台监听器打回，
     * 且被打回的 key 记入 [navIntercepted]——闸期被吃掉的自动起播意图，抬闸时恢复）；
     * ②一刀切暂停全部 busy 实例——**记账返回"落闸时有播放意图（playWhenReady=true）"
     * 的 key 清单**，供抬闸恢复（修旧覆盖层闸只暂停当前单 key 的漏音类病：池内可同时
     * 存在多路 busy——翻页在途旧页未收/邻页残留，BGGate 通道注释自述的同款教训）；
     * 判定用 playWhenReady 而非 isPlaying：缓冲中 isPlaying=false 但用户语义上是"在播"
     * （R158 后台闸同口径——旧频道主页闸正是栽在这个口径上：进主页时还在缓冲的视频
     * 不被暂停，缓冲完成即在主页之下开播出声，实测实锤）。
     * 抬闸（on=false）：清 [navGate]；resumeKeys + [navIntercepted] 合并恢复（记账=
     * 落闸前在播的；拦截=闸期被吃掉的自动起播——回页重建的 VideoLoad 起播意图不该被
     * 一帧陈旧的台前信号误杀）；已被释放/淘汰/换片的 key 自然扑空跳过，不回放旧声音。
     * 已落闸时重复落闸为幂等 no-op（返回空清单，恢复账本以调用方持有者为准）。
     */
    @Synchronized
    fun setNavGate(on: Boolean, resumeKeys: List<String> = emptyList()): List<String> {
        if (on) {
            if (navGate) return emptyList()
            navGate = true
            navIntercepted.clear()
            // R417：externals（池外自管播放器，分屏音频格）同入占有权闸——key 命名空间
            // 与 busy 不相交（grid:audio: 前缀），恢复侧 busy ?: externals 统一查
            val wasPlaying = busy.filterValues { it.playWhenReady }.keys.toList() +
                externals.filterValues { it.playWhenReady }.keys.toList()
            busy.values.forEach { it.pause() }
            externals.values.forEach { it.pause() }
            android.util.Log.i(
                "NavGate",
                "导航闸落下：一刀切暂停 ${busy.size} 路在册（播放意图 ${wasPlaying.size} 路记账）",
            )
            return wasPlaying
        }
        if (!navGate) return emptyList()
        navGate = false
        val allKeys = resumeKeys + navIntercepted
        navIntercepted.clear()
        var resumed = 0
        allKeys.distinct().forEach { k ->
            (busy[k] ?: externals[k])?.let {
                it.play()
                resumed++
            }
        }
        android.util.Log.i(
            "NavGate",
            "导航闸抬起：恢复 ${resumed}/${allKeys.size} 路（记账+拦截合并）",
        )
        return emptyList()
    }

    /**
     * R180 闸期拦截记录：导航闸落下期间被监听器打回的自动起播 key（只在闸期内累积，
     * 落闸/抬闸都清空）。恢复语义见 [setNavGate] 抬闸分支。
     */
    private val navIntercepted = mutableListOf<String>()

    /** 闸期拦截入账（监听器在播放器线程触发；池同步锁可重入，同线程安全） */
    private fun noteNavIntercepted(player: ExoPlayer) {
        synchronized(this) {
            if (!navGate) return
            busy.entries.firstOrNull { it.value === player }?.key?.let { k ->
                if (k !in navIntercepted) {
                    navIntercepted.add(k)
                    android.util.Log.i("NavGate", "闸拦截自动起播并记账：…${k.takeLast(12)}")
                }
            }
        }
    }

    /**
     * "下一条"信号：锁屏/耳机/车机按"下一首"时由 AudioBackgroundService 触发，
     * UI（FeedScreen 主代理）接线成翻页。setter 同步镜像到伴生 [sharedSkipNextHandler]，
     * 让拿不到池实例的系统组件（服务）也能触发；多池并存时后接线者生效
     * （目标场景是单播 Feed，分屏多格不接"下一条"）。
     */
    var onSkipNext: (() -> Unit)? = null
        set(value) {
            field = value
            sharedSkipNextHandler = value
        }

    /**
     * 主播放器上报：同引用去重（翻页时 acquire(新) 与 releaseAllExcept(新) 会连发两次同一实例，
     * acquire 命中缓存也可能重复上报），下游按引用相等直接忽略即可。
     */
    private fun notifyActivePlayer(player: ExoPlayer?) {
        if (player === lastActivePlayer) return
        lastActivePlayer = player
        onActivePlayerChanged?.invoke(player)
        sharedActivePlayerListener?.invoke(player)
    }

    // 音频焦点属性：媒体用途 + 影视内容类型。实例不可变，单播池与网格池的所有播放器共用一份。
    // setAudioAttributes(attrs, handleAudioFocus=true) 让 ExoPlayer 自动管理音频焦点：
    // 来电/其他 App 发声时自动闪避暂停（瞬态焦点丢失），焦点恢复后自动续播。
    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()

    @Synchronized
    fun acquire(key: String): ExoPlayer {
        // R112：本 key 由活播放器接管，账本旧快照作废——此后以实时位置为准
        lastPositions.remove(key)
        busy[key]?.let {
            // 命中即刷新访问序：摘出重挂到表尾，本 key 成为"最近使用"，
            // 淘汰指针（表头）才始终指向最久未用者（R109 P-1）
            busy.remove(key)
            busy[key] = it
            // 命中缓存同样上报（去重在 notifyActivePlayer 内）——服务可能刚启动、快照滞后
            notifyActivePlayer(it)
            return it
        }
        val player = idle.removeFirstOrNull()
            ?: if (created < maxPlayers) {
                buildPlayer()
            } else {
                // 淘汰最久未使用的 busy 实例：必须彻底 release（P1-5 修复）。
                // 原先只 stop/clearMediaItems 就把实例转手给新 key——旧持有方
                // （PlayerView/FloatingWindow）仍引用它，两个视图共享一个播放器，
                // 画面互抢、事件串台。release 腾出 created 额度后给新 key 新建实例；
                // 旧格 onDispose 的 release(旧key) 因 busy 已移除而落空成 no-op，安全
                val eldest = busy.entries.first()
                busy.remove(eldest.key)
                // R112：淘汰销毁前先记进度账（V-5：失焦保存晚于淘汰时靠账本兜底）
                snapshotPosition(eldest.key, eldest.value)
                destroy(eldest.value)
                buildPlayer()
            }
        busy[key] = player
        // 新 acquire 的 key 即当前"主"播放器（Feed 单播只有激活页会 acquire；
        // 分屏多格会按 acquire 顺序互相顶位，见类头注释的多池限制）
        notifyActivePlayer(player)
        return player
    }

    /** 新建一个池内播放器（created 计数在此自增；构建路径全局唯一，勿在别处另起 Builder） */
    private fun buildPlayer(): ExoPlayer {
        created++
        // R95 硬解回落软解：每台播放器独立的解码器选择器——某条视频硬解崩了只把
        // 本机切软解（见文件末尾 SoftwareFallbackMediaCodecSelector），不污染池内
        // 其他机器。工厂随之逐机新建：原共享单例省下的初始化开销本就可忽略
        // （构建次数 ≤ maxPlayers），而逐机工厂才放得下逐机软解标记
        val codecSelector = SoftwareFallbackMediaCodecSelector()
        // R125 FFmpeg 兜底：工厂换成追加了 FFmpeg 渲染器的子类（文件末尾
        // FfmpegFallbackRenderersFactory）。FFmpeg 渲染器排在 MediaCodec 之后，
        // 轨道选择同分先到的赢——平台能解的格式仍走 MediaCodec（零行为变化），
        // FFmpeg 只在平台全不支持时被自动顶位，或被下方监听器在硬软双崩后显式顶位
        val renderersFactory = FfmpegFallbackRenderersFactory(context).apply {
            // 解码器初始化失败先试候选列表里的下一位（如模拟器 HEVC 硬解起不来直换软解）
            setEnableDecoderFallback(true)
            setMediaCodecSelector(codecSelector)
        }
        // R125：逐机独立轨道选择器。FFmpeg 顶位靠"禁用本机的 MediaCodec 视频渲染器"
        // 实现（setRendererDisabled 按下标生效），必须每台播放器各持一个选择器——
        // 一个实例进 FFmpeg 档不能传染池内其他机器
        val trackSelector = DefaultTrackSelector(context)
        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            // 音频焦点接管：handleAudioFocus=true，来电/其他 App 发声自动闪避暂停。
            // 单播池（lowMemoryMode=false）与网格池共用此构建路径，同时生效
            .setAudioAttributes(audioAttributes, true)
            .apply {
                // 熄屏唤醒锁：WAKE_MODE_LOCAL 持 PARTIAL_WAKE_LOCK，熄屏后 CPU 不睡，
                // 后台音频/长缓冲不断流。该锁需 Manifest 声明 android.permission.WAKE_LOCK，
                // 未声明时 WakeLock.acquire 会抛 SecurityException——故按权限防御性开启，
                // 清单补上权限后无需改代码即生效
                if (context.checkSelfPermission(android.Manifest.permission.WAKE_LOCK)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    setWakeMode(C.WAKE_MODE_LOCAL)
                }
                if (lowMemoryMode) {
                    // LoadControl 保持每播放器独立构建（内部 allocator 不跨播放器共享，
                    // 避免缓冲核算口径变化——OOM 修复成果勿回退）；构建次数最多 maxPlayers 次，开销可忽略
                    setLoadControl(
                        DefaultLoadControl.Builder()
                            .setBufferDurationsMs(1000, 5_000, 500, 1000)
                            .setTargetBufferBytes(4 * 1024 * 1024)
                            .setPrioritizeTimeOverSizeThresholds(true)
                            .build(),
                    )
                } else {
                    // R145 高 RTT 边下边播缓冲垫：单播池显式设 LoadControl——起播水位维持
                    // 默认（2500ms，不为垫层牺牲秒开），重缓冲恢复水位 5s→8s：泵速低谷
                    //（TDLib 重定位/并发挤占）拖穿缓冲时，回播要攒更厚的垫再续，避免
                    // "恢复→又见底→再 BUFFERING"的抖振（华为平板基线实锤近窗速率有
                    // 165~344KB/s 塌陷段）。min/max 缓冲保持 media3 默认 50s 不动。
                    // R190 换 [BoostAwareLoadControl]（同参构建，仅倍速档加策略）：
                    // 字节码实证 media3 1.4.1 的 maxBufferUs 停止上限是媒体时间口径
                    // ——50s 媒体垫在 2x 下只值 25s 墙钟（1x 设计点的一半），是长按
                    // 2x 周期性「加载一下」的播放器侧半壁根因。倍速档时间目标×速度
                    //（封顶 2×），1x 路径零变化（内存纪律不回退）
                    setLoadControl(
                        BoostAwareLoadControl(),
                    )
                }
            }
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_OFF
                // 后台闸硬执行（修"后台行为=暂停 不生效"）：onStop 的一次性 pause 挡不住
                // 闸落之后才到的播放触发——迟到 playUri 的装填 effect、翻页动画落定、
                // 清晰度热切换源都会在 onStop 之后置 playWhenReady=true（实测 HOME 暂停后
                // 0.37s 又有新 AudioTrack started）。这里在每台播放器上挂监听器，
                // 闸落期间任何 playWhenReady=true 当场打回暂停，补上一次性 pauser 够不到的缝。
                // pause() 会把 playWhenReady 置回 false 重进本回调，此时条件不成立，无自旋。
                // R180 导航闸并入同一拦截位：App 内切页/进覆盖层（feed 页不在台前）期间，
                // 迟到的播放触发同样打回——实测实锤：进频道主页时还在缓冲的视频
                // （playWhenReady=true、isPlaying=false）缓冲完成后在主页之下开播出声，
                // 且 autoAdvance 连锁让整条 feed 在覆盖层下逐条连播。
                addListener(object : Player.Listener {
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        // pipExempt：系统画中画期间后台闸不打回播放——PiP 小窗用户可见，
                        // 属继续播放场景；闸本身照落（状态一致），只是对播放触发放行
                        // （导航闸无 PiP 豁免：本池实例归 Feed 页所有，PiP 播放走悬浮窗独立池）
                        if (playWhenReady && ((backgroundGate && !pipExempt) || navGate)) {
                            if (navGate) noteNavIntercepted(this@apply)
                            android.util.Log.i(
                                "BGGate",
                                "闸拦截播放触发，打回暂停（后台=$backgroundGate 导航=$navGate）",
                            )
                            pause()
                        }
                    }

                    // R190 倍速总线写入口：速度一变（长按 2x 进出/面板换档）就全池重算
                    // 最大速度——PlaybackSpeedBus 是泵前瞻倍速感知的唯一数据源。
                    // 邻页复播回写 1f 不会误清总线（取 max：加速中的主位播放器仍在册）。
                    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                        recomputeSpeedBus()
                    }
                })
                // R190 倍速丝滑度观测插桩（零行为变化，纯取证）：
                //  - dropFrames：视频渲染器丢帧计数（≥10 帧才记，例行 1~2 帧是节奏
                //    抖动）——「解码卡」分诊数据源（2x 解码吞吐翻倍，FFmpeg 软名单
                //    尤其要盯）；
                //  - boostUnderrun：倍速中进 BUFFERING——「等流卡」分诊数据源。
                //    两计数对 = 真机判定「长按 2x 卡顿是解码跟不上还是泵供不上」。
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onDroppedVideoFrames(
                        eventTime: AnalyticsListener.EventTime,
                        droppedFrames: Int,
                        elapsedMs: Long,
                    ) {
                        if (droppedFrames >= DROP_TRACE_MIN) {
                            PlaybackTrace.log(
                                "dropFrames",
                                "",
                                "掉${droppedFrames}帧/${elapsedMs}ms speed=${PlaybackSpeedBus.speed}",
                            )
                        }
                    }

                    override fun onPlaybackStateChanged(
                        eventTime: AnalyticsListener.EventTime,
                        state: Int,
                    ) {
                        if (state == Player.STATE_BUFFERING && PlaybackSpeedBus.isBoosted) {
                            PlaybackTrace.log(
                                "boostUnderrun",
                                "",
                                "倍速中进缓冲 水位=${runCatching { this@apply.bufferedPosition }.getOrDefault(-1L)}ms",
                            )
                        }
                    }
                })
                // 音量均衡：内容 key 转接给本机音频链上的归一器（挂点与语义见
                // FfmpegFallbackRenderersFactory.attachTo；buildAudioSink 在 .build() 内
                // 同步回调，工厂逐机新建，attachTo 取回的必是本机实例）
                renderersFactory.attachTo(this)
                // 视频解码三级回落（R95 两级 + R125 第三级 FFmpeg）：
                //   一级：硬解（默认，省电；平台 MediaCodec 顺序）
                //   二级：平台软解（R95——修平板实测大头 code=4003 MediaCodecVideoDecoderException：
                //        高通 c2.qti.avc.decoder 等硬解器解不了某些编码）检出视频解码器失败 →
                //        地址记入软解名单 → 本机切软解并原地重 prepare——时间线与播放位置保留，
                //        重投时渲染器重新初始化解码器、再次咨询选择器，同一实例即可换软解，
                //        无需重建播放器（UI 持有的引用不失效）；READY 后 UI 失败态自愈
                //   三级：FFmpeg 软解（R125——实测高通 HEVC 机型 OMX.qcom.hevc 与 c2.android.hevc
                //        双崩，平台链全灭）禁用本机 MediaCodec 视频渲染器 → 尾部的 FFmpeg
                //        视频渲染器在重投时顶位 → 原地重 prepare（同一实例，引用不失效）
                //   FFmpeg 也解不了（真坏片/真不支持）则放行给 UI 分诊，防死循环
                var ffmpegVideoMode = false
                fun applyFfmpegVideoMode() {
                    val mcIndex = renderersFactory.mediaCodecVideoIndex
                    if (mcIndex < 0 || !renderersFactory.ffmpegAvailable) return
                    trackSelector.setParameters(
                        trackSelector.parameters.buildUpon()
                            .setRendererDisabled(mcIndex, ffmpegVideoMode)
                            .build(),
                    )
                }
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        if (!isVideoDecoderFailure(error)) return
                        val uri = currentMediaItem?.localConfiguration?.uri?.toString()
                        if (!codecSelector.preferSoftware) {
                            // 一级崩 → 二级：地址记入平台软解名单，本机切软解重试
                            if (uri != null) softwareDecodeUris.add(uri)
                            codecSelector.preferSoftware = true
                            android.util.Log.w(
                                "PlayerPool",
                                "视频硬解失败（code=${error.errorCode}），回落平台软解重试：" +
                                    "…${uri?.takeLast(40)}",
                            )
                            prepare()
                            return
                        }
                        if (!ffmpegVideoMode && renderersFactory.ffmpegAvailable) {
                            // 二级崩 → 三级：地址记入 FFmpeg 名单，禁用 MediaCodec 视频渲染器重试
                            ffmpegVideoMode = true
                            if (uri != null) ffmpegDecodeUris.add(uri)
                            applyFfmpegVideoMode()
                            android.util.Log.w(
                                "PlayerPool",
                                "平台软解仍失败（code=${error.errorCode}），回落 FFmpeg 软解重试：" +
                                    "…${uri?.takeLast(40)}",
                            )
                            prepare()
                            return
                        }
                        // 三级也失败（真坏片/格式超范围）→ 放行给 UI 分诊
                    }

                    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                        // 装填新片（PLAYLIST_CHANGED 先于解码器初始化同步回调）：
                        // 命中各级名单的地址直接按已知档位起手，不再逐级撞崩；未命中复位
                        // 硬解优先——池内实例复用转手，上一条的回落标记不能误伤新片
                        //（只让崩过的那条回落，其余照旧硬解省电）
                        if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
                        val uri = currentMediaItem?.localConfiguration?.uri?.toString()
                        codecSelector.preferSoftware =
                            uri != null && softwareDecodeUris.contains(uri)
                        // FFmpeg 档只对"硬解+平台软解双崩过"的地址开（名单同 R95 纪律）；
                        // 其余复位硬解优先——（R125 事故复盘：验证期临时钩曾把
                        // wantFfmpeg 写成 uri!=null 强制全量 FFmpeg，断网未撤随 WIP 入库，
                        // 造成全线"起播画面冻结只有声音"，根因即此，已修）
                        val wantFfmpeg = uri != null && ffmpegDecodeUris.contains(uri)
                        if (wantFfmpeg != ffmpegVideoMode) {
                            ffmpegVideoMode = wantFfmpeg
                            applyFfmpegVideoMode()
                        }
                    }
                })
                registeredPlayers.add(this)
            }
    }

    /** 彻底销毁一台池内播放器：摘后台闸登记表 + release + 归还 created 额度（唯一销毁出口） */
    private fun destroy(p: ExoPlayer) {
        registeredPlayers.remove(p)
        p.release()
        created--
    }

    @Synchronized
    fun release(key: String) {
        val p = busy.remove(key) ?: return
        snapshotPosition(key, p) // R112：先记账再释放（stop 会把位置归零）
        p.stop()
        p.clearMediaItems()
        if (idle.size < maxPlayers) {
            idle.addLast(p)
            scheduleIdleTrim() // R158：静置超时后彻底回收（见 trimIdle）
        } else {
            destroy(p)
        }
        recomputeSpeedBus() // R190：摘除后全池倍速回落（防总线残留过期 2x）
        // 释放的恰好是主位（如未翻页直接离开 Feed）：主位清空，服务侧退回占位底座
        if (p === lastActivePlayer) notifyActivePlayer(null)
    }

    /** 暂停但不回收（页面划出视野但仍在 pager 缓存范围内时用） */
    @Synchronized
    fun pause(key: String) {
        busy[key]?.pause()
    }

    /**
     * 只查询不创建：取某 key 当前持有的 busy 播放器（无则 null）。
     * 供"保存播放进度"这类只读场景使用——Compose 组合里的 player 引用在失焦时
     * 已被求值为 null（acquire 条件含 isActive），旧闭包拿不到活的播放器实例，
     * 必须按 key 到池里现查（记忆播放修复）。
     */
    @Synchronized
    fun peek(key: String): ExoPlayer? = busy[key]

    /**
     * 查某 key 的"最新可知"播放位置（毫秒，R112）：busy 里有活实例读实时位置；
     * 已被释放/淘汰的回落 [lastPositions] 的释放前记账。
     * 专供"保存播放进度"这类场景——翻页收敛（releaseAllExcept）与 acquire 淘汰
     * 都可能先于失焦页的保存回调执行，纯 [peek] 扑空会让进度静默丢失。
     * 只查询不创建；无任何记录返回 null。
     */
    @Synchronized
    fun peekPosition(key: String): Long? = busy[key]?.currentPosition ?: lastPositions[key]

    /**
     * R190 全池倍速重算 → [PlaybackSpeedBus]（泵前瞻倍速感知的数据源）。
     * 触发点：任一播放器 onPlaybackParametersChanged（长按 2x 进出/面板换档）+
     * 一切摘除路径（release/releaseAllExcept/releaseAll——加速中的播放器被摘除后
     * 总线即时回落，不残留过期 2x 让后续泵白拉双倍前瞻）。
     * 池同步锁内调用（监听器触发时加锁，release 路径重入同线程），读 busy 无竞态。
     */
    @Synchronized
    private fun recomputeSpeedBus() {
        val max = busy.values.maxOfOrNull { p ->
            runCatching { p.playbackParameters.speed }.getOrDefault(1f)
        } ?: 1f
        PlaybackSpeedBus.report(max)
    }

    // ---------------- R380 池外自管播放器登记（分屏音频格收编进全停广播域） ----------------

    /**
     * R380 池外自管播放器登记表（key → 播放器实例）。分屏音频格（GridAudioCell）的
     * ExoPlayer 自建自释放、不占池容（音频解码与视频硬解路数互不相干，入池反而
     * 顶占 MAX_GRID_PLAYERS 名额）——旧口径下它们游离于 [pauseAll] 广播域之外：
     * 顶栏「全部暂停」只拉停池内 busy、后台暂停通道（MainActivity.onStop →
     * container.pauseAllPlayback → 本池 pauseAll）同样够不到，自管音频格照播不止
     * （用户 11:3x 实锤「分屏播放模块的全部暂停全部播放功能不好使」的池外半壁根因）。
     * 收编刀法（最小刀）：格子组合期 attachExternal 登记、销毁/换片期 detachExternal
     * 注销（登记生命周期与格子组合同进退），[pauseAll] 广播时连 externals 一起拉停，
     * 「全部播放」恢复面按 key peekExternal 取实例续播。与 companion 的
     * attachExternalBackgroundGate（进程级、永不注销）分职：那把刀给跨页面存活的
     * 共享音频播放器，这把给生命周期随格子组合的自管播放器。
     */
    private val externals = LinkedHashMap<String, ExoPlayer>()

    /** 池外自管播放器登记（格子组合期调用；同 key 重登记=后者顶位，换片重建场景） */
    @Synchronized
    fun attachExternal(key: String, player: ExoPlayer) {
        externals.remove(key)?.let { old -> externalGateListeners.remove(key)?.let { old.removeListener(it) } }
        externals[key] = player
        // R417：池外自管播放器同受占有权闸迟到拦截（与池内监听器同手法）
        val l = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady && navGate) {
                    synchronized(this@PlayerPool) {
                        if (key !in navIntercepted) navIntercepted.add(key)
                    }
                    player.pause()
                }
            }
        }
        player.addListener(l)
        externalGateListeners[key] = l
        if (navGate && player.playWhenReady) player.pause()
    }

    private val externalGateListeners = HashMap<String, Player.Listener>()

    /** 池外自管播放器注销（格子销毁/换片释放前调用；key 不在册为幂等 no-op） */
    @Synchronized
    fun detachExternal(key: String) {
        val p = externals.remove(key)
        externalGateListeners.remove(key)?.let { l -> p?.removeListener(l) }
    }

    /** 只查询不创建：取某 key 登记的池外自管播放器（无则 null；「全部播放」恢复面用） */
    @Synchronized
    fun peekExternal(key: String): ExoPlayer? = externals[key]

    /**
     * 暂停全部 busy 实例（不回收；分屏退后台的"暂停全部格子"用）。
     * 比逐 key pause 彻底：格子划出可见区、DisposableEffect 尚未归还的瞬态 key 也覆盖到。
     * R380 起连池外自管登记（[externals]，分屏音频格）一起拉停——全停/后台暂停
     * 广播域对自管播放器同等生效（修「全部暂停后池外音频格照播」）。
     */
    @Synchronized
    fun pauseAll() {
        busy.values.forEach { it.pause() }
        externals.values.forEach { it.pause() }
    }

    @Synchronized
    fun releaseAllExcept(keepKey: String?) {
        val it = busy.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key != keepKey) {
                // R112：先记账再释放——失焦页的进度保存回调晚于本方法执行时，
                // 经 peekPosition 回落账本仍能拿到正确位置（修翻走再翻回从 0 重播）
                snapshotPosition(e.key, e.value)
                e.value.stop()
                e.value.clearMediaItems()
                if (idle.size < maxPlayers) {
                    idle.addLast(e.value)
                    scheduleIdleTrim() // R158：静置超时后彻底回收（见 trimIdle）
                } else {
                    destroy(e.value)
                }
                it.remove()
            }
        }
        // 翻页收敛后 keepKey 即主位；keepKey 已被淘汰/为 null 则主位清空
        notifyActivePlayer(keepKey?.let { busy[it] })
        recomputeSpeedBus() // R190：摘除后全池倍速重算（防总线残留过期 2x）
    }

    @Synchronized
    fun releaseAll() {
        // busy 实例也必须彻底 release：旧实现只 stop/clear 后从 busy 摘除丢弃，
        // 实例既不 release 也不回 idle——解码器/渲染器资源随页面销毁泄漏，
        // 且后台闸登记表里的死引用越攒越多
        busy.entries.forEach { (k, p) ->
            snapshotPosition(k, p) // R112：先记账再销毁
            p.stop(); p.clearMediaItems(); destroy(p)
        }
        busy.clear()
        idle.forEach { destroy(it) }
        idle.clear()
        // 池生命周期终结：撤销挂着的延迟回收排程（防 Handler 持引用拖延实例回收）
        idleTrimHandler.removeCallbacks(idleTrimRunnable)
        notifyActivePlayer(null)
        recomputeSpeedBus() // R190：池清空总线回落 1f
    }

    companion object {
        /** R112 进度账本上界（条）：正常翻页账面只有个位数，极端长跑防无限膨胀 */
        private const val POSITION_LEDGER_MAX = 32

        /**
         * R158 idle 池回收静置期（毫秒）：最后一次播放器归 idle 起算。翻页活跃期
         * release 事件不断重置计时，池保持温热不受影响；静置超时才裁剪
         */
        private const val IDLE_TRIM_DELAY_MS = 60_000L

        /** R158 idle 池回收保留台数：留 1 台供翻回上一条即时复播（其余彻底 release） */
        private const val IDLE_TRIM_KEEP = 1

        /**
         * R190 dropFrames trace 门槛（帧）：渲染器例行 1~2 帧丢帧是节奏抖动（30fps
         * 下每秒一帧常态），低于此不记防刷屏；≥10 帧/窗才是解码吞吐吃紧的信号
         */
        private const val DROP_TRACE_MIN = 10

        /**
         * R190 单播池媒体时间水位（毫秒）：R145 既有口径 50s（min=max 同值），
         * 提为常量供 [BoostAwareLoadControl] 构建参数与倍速档时间目标共用——
         * 同一个数字不许在构建处与策略处各写一份（纪律§一）
         */
        internal const val FEED_BUFFER_MS = 50_000

        /**
         * 后台闸（"后台行为=暂停"的硬执行件，MainActivity.onStop/onStart 驱动）。
         * 落下期间：① 每台池内播放器的监听器把任何 playWhenReady=true 当场打回暂停；
         * ② 落闸瞬间一刀切暂停全部在册播放器（Feed/分屏/悬浮窗三个池全覆盖）。
         * 进程级内存态，无需持久化——进程重启后闸天然是抬起的。
         */
        @Volatile
        var backgroundGate: Boolean = false
            private set

        /**
         * PiP 豁免标记（MainActivity 进出系统画中画时置位）。
         * true 期间后台闸失效：① 各播放器监听器的"打回暂停"放行；
         * ② 落闸瞬间的一刀切暂停跳过。PiP 小窗用户可见，是继续播放场景，
         * 不能按后台暂停纪律掐视频。关掉小窗时 MainActivity 先清本标记再落闸/停播。
         */
        @Volatile
        var pipExempt: Boolean = false
            private set

        /** 置/清 PiP 豁免（进出系统画中画由 MainActivity 驱动；进程级内存态，无需持久化） */
        fun setPipExempt(on: Boolean) {
            pipExempt = on
        }

        /**
         * R180 池外播放器接入后台闸（共享音频播放器上提 AppContainer 进程级后新增的口）：
         * FeedScreen 可能已 dispose（用户在别的页面背景听音频），其后 onStop 时
         * currentPlayerPauser 已注销、够不到本实例——挂上与池内播放器同款的两件：
         * ① 迟到播放拦截监听器（闸落期间 playWhenReady=true 打回）；② 落闸一刀切
         * 登记表 [registeredPlayers]。进程级持有不 destroy，无需摘除口。
         * 导航闸不管池外实例（音频豁免/暂停由 FeedScreen 闸 effect 直调，见 NavPlaybackPolicy 接线）。
         */
        fun attachExternalBackgroundGate(player: ExoPlayer) {
            registeredPlayers.add(player)
            player.addListener(object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (playWhenReady && backgroundGate && !pipExempt) {
                        android.util.Log.i("BGGate", "后台闸拦截播放触发（池外音频），打回暂停")
                        player.pause()
                    }
                }
            })
        }

        /**
         * 在册播放器登记表（所有池实例共用；busy/idle 都在册，destroy 时摘除）。
         * 仅用于落闸瞬间的一刀切暂停；强引用但与实例同生命周期（destroy 必摘除），不泄漏。
         */
        private val registeredPlayers =
            java.util.Collections.synchronizedSet(mutableSetOf<ExoPlayer>())

        /**
         * 本会话内确认硬解崩过的视频地址集（R95 软解名单）：进程级内存态，
         * Feed/分屏/悬浮窗各池共享——同一地址换池再播也直接软解优先。
         * 只增不删、随进程消亡；只装真崩过的极少数地址，无膨胀之虞。
         */
        private val softwareDecodeUris =
            java.util.Collections.synchronizedSet(mutableSetOf<String>())

        /**
         * 本会话内确认"硬解+平台软解双崩"的视频地址集（R125 FFmpeg 名单）：进程级内存态，
         * Feed/分屏/悬浮窗各池共享——同一地址换池再播直接 FFmpeg 起手，不再连撞两级
         * MediaCodec。与 [softwareDecodeUris] 同纪律：只增不删、随进程消亡。
         */
        private val ffmpegDecodeUris =
            java.util.Collections.synchronizedSet(mutableSetOf<String>())

        /**
         * 是否"视频解码器失败"（R95 回落软解的触发口径）：
         * 错误码是解码失败（4003）或解码器初始化失败（4001），且因果链上挂着
         * 视频解码器异常（MediaCodecVideoDecoderException）。音频解码失败不触发
         * （实测大头是视频硬解 4003；音频链路另行分诊，不在此回落）。
         */
        private fun isVideoDecoderFailure(error: PlaybackException): Boolean {
            if (error.errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED &&
                error.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
            ) {
                return false
            }
            var cause: Throwable? = error.cause
            while (cause != null) {
                if (cause is MediaCodecVideoDecoderException) return true
                cause = cause.cause
            }
            // 兜底：异常类型被包装丢失时，按"渲染器错误 + 渲染器名含 Video"辨认
            val exo = error as? ExoPlaybackException ?: return false
            return exo.type == ExoPlaybackException.TYPE_RENDERER &&
                exo.rendererName?.contains("Video") == true
        }

        /** 落闸/抬闸。落闸即静默：在册播放器全部暂停（闸持续生效，防迟到触发重新出声）。
         *  PiP 豁免期间落闸只记状态不砍播放（小窗用户可见，见 [pipExempt]） */
        fun setBackgroundGate(on: Boolean) {
            if (backgroundGate == on) return
            backgroundGate = on
            android.util.Log.i("BGGate", if (on) "后台闸落下：全量暂停在册播放器" else "后台闸抬起")
            if (on && !pipExempt) {
                synchronized(registeredPlayers) {
                    registeredPlayers.forEach { it.pause() }
                }
            }
        }

        /**
         * 最近一次上报的"主"播放器快照。AudioBackgroundService 可能起得比池晚，
         * 启动时先按它接管一次，不必等下一次切换。
         */
        @Volatile
        var lastActivePlayer: ExoPlayer? = null
            private set

        /**
         * 主播放器变化的服务侧订阅口（所有池实例共用；同一时刻只有 AudioBackgroundService 会挂）。
         * 与实例级 [onActivePlayerChanged] 并行广播，互不影响。
         */
        @Volatile
        var sharedActivePlayerListener: ((ExoPlayer?) -> Unit)? = null

        /**
         * "下一条"的服务侧触发口：AudioBackgroundService 调它，实际执行的是
         * UI 接线到池实例 [onSkipNext] 的 lambda（由该 setter 镜像过来）。
         * R299 B1：共享音频播放器建造时由 AppContainer 先落听单兜底
         * （FeedScreen 未接线/dispose 期间锁屏「下一首」=听单连播）。
         */
        @Volatile
        var sharedSkipNextHandler: (() -> Unit)? = null

        /**
         * R299 B1「上一首」的服务侧触发口（与 [sharedSkipNextHandler] 同通道镜像）：
         * 听单非空=重播刚播完的条目；听单空=FeedScreen 接线期回落翻页；
         * 全未接线=按了无反应（静默吞掉，同下一首既有口径）。
         */
        @Volatile
        var sharedSkipPrevHandler: (() -> Unit)? = null

        /**
         * 池外播放器接入"主播放器"通知链（音频卡的共享音频播放器用——它归 FeedScreen
         * 持有、不经池 acquire，但锁屏/耳机控制要走同一条镜像通道）。
         * 与实例侧 notifyActivePlayer 同一出口同一语义：同引用去重、后报者顶位；
         * AudioBackgroundService 无需区分池内池外。调用方请在主线程触发。
         */
        fun notifyExternalActive(player: ExoPlayer?) {
            if (player === lastActivePlayer) return
            lastActivePlayer = player
            sharedActivePlayerListener?.invoke(player)
        }
    }
}

/**
 * R190 倍速档加载决策（纯函数，行为单测 PlaybackBoostPolicyTest）：
 * 长按 2x（及面板 1.5/2x 档）期间媒体消耗速率翻倍，而 media3 1.4.1
 * DefaultLoadControl 的停止上限 maxBufferUs 是**媒体时间**口径——50s 媒体垫
 * 在 2x 下只值 25s 墙钟（1x 设计点的一半），周期性穿底=「加载一下」的
 * 播放器侧半壁根因（字节目标视频 125MB 永不先咬，javap 实证，勿再误记）。
 * 倍速档把时间目标按速度放大（封顶 [BOOST_TARGET_MAX_MULTIPLE]×）：2x 下
 * 100s 媒体垫 = 50s 墙钟，恰好回到 1x 的墙钟设计点。
 *
 * 返回 null = 非倍速档：交回 DefaultLoadControl 既有裁决（1x 行为零变化，
 * 内存纪律：50s 目标照旧，倍速只是短时抬升、松手回落）。
 */
internal fun shouldContinueLoadingBoostAware(
    playbackSpeed: Float,
    bufferedDurationUs: Long,
    maxBufferUs: Long,
): Boolean? {
    if (!PlaybackSpeedBus.isBoostSpeed(playbackSpeed)) return null
    val boostedTargetUs = (maxBufferUs.toDouble() * playbackSpeed)
        .coerceAtMost(maxBufferUs.toDouble() * BOOST_TARGET_MAX_MULTIPLE)
        .toLong()
    return bufferedDurationUs < boostedTargetUs
}

/** R190 倍速档时间目标放大上限（×）：防未来 >2x 档位把内存垫拉爆（100s 媒体封顶） */
internal const val BOOST_TARGET_MAX_MULTIPLE = 2.0

/**
 * R190 倍速感知 LoadControl（单播池专用；构建参数与 R145 既有口径一字不差）。
 *
 * 为什么不用 Builder：DefaultLoadControl 的 Builder 只能产出基类，速度感知必须
 * 子类化；protected 构造器直传（alloc 分段大小/back buffer 等未列参数按
 * media3 默认值，与旧 Builder 链逐项等价）。
 *
 * 为什么重缓冲后起播门槛不动：shouldStartPlayback 的 8s×speed 语义本来正确
 * （倍速下要攒 16s 媒体才够 8s 墙钟，防「恢复→又见底」抖振），不碰。
 */
private class BoostAwareLoadControl : DefaultLoadControl(
    DefaultAllocator(/* trackedBuffers = */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
    PlayerPool.FEED_BUFFER_MS, PlayerPool.FEED_BUFFER_MS, /* forPlayback = */ 2_500,
    /* afterRebuffer = */ 8_000,
    DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES,
    /* prioritizeTimeOverSizeThresholds = */ false,
    DefaultLoadControl.DEFAULT_BACK_BUFFER_DURATION_MS,
    /* retainBackBufferFromKeyframe = */ false,
) {
    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val boostDecision = shouldContinueLoadingBoostAware(
            playbackSpeed = parameters.playbackSpeed,
            bufferedDurationUs = parameters.bufferedDurationUs,
            maxBufferUs = PlayerPool.FEED_BUFFER_MS * 1000L,
        )
        return boostDecision ?: super.shouldContinueLoading(parameters)
    }
}

/**
 * 软硬解自适应解码器选择器（R95）：默认完全等同 [MediaCodecSelector.DEFAULT]
 * （硬解优先，省电）。本机有视频硬解崩过后由池置位 [preferSoftware]，此后本机
 * 初始化解码器时同 MIME 的软解器排到最前；硬解器保留在后兜底——软解不支持
 * 该格式时仍能落回硬解，不会因回落直接播不了。
 * 只对视频 MIME（"video/"前缀）重排，音频链路保持默认顺序。
 * media3 1.4.1 的 MediaCodecSelector 只有 DEFAULT（javap 实证，无内置软解优先件），
 * 故软解优先排序在此自行实现（MediaCodecInfo.hardwareAccelerated 辨软硬）。
 */
private class SoftwareFallbackMediaCodecSelector : MediaCodecSelector {
    /** 软解优先开关：由池在检出视频硬解失败后置位；装填未命中软解名单的新片时复位 */
    @Volatile
    var preferSoftware: Boolean = false

    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunneling: Boolean,
    ): List<MediaCodecInfo> {
        val all = MediaCodecSelector.DEFAULT.getDecoderInfos(
            mimeType,
            requiresSecureDecoder,
            requiresTunneling,
        )
        if (!preferSoftware || !mimeType.startsWith("video/")) return all
        val software = all.filter { !it.hardwareAccelerated }
        if (software.isEmpty()) return all
        android.util.Log.i(
            "PlayerPool",
            "软解优先选取解码器：$mimeType → ${software.first().name}（候选${all.size}个）",
        )
        return software + all.filter { it.hardwareAccelerated }
    }
}

/**
 * FFmpeg 软解兜底渲染器工厂（R125）：直承 DefaultRenderersFactory，往渲染器数组尾部
 * 追加 FFmpeg 软解渲染器；音量均衡挂点（buildAudioSink/attachTo）复刻
 * NormalizingRenderersFactory 的实现——它是 final 不可继承，且归一器挂点不能丢，
 * 故同语义重写于此（两处如演进需同步；语义注释见 LoudnessNormalizer.kt 同名成员）。
 *
 * 追加在尾部的语义：ExoPlayer 轨道选择对"同分"格式取数组中靠前者——MediaCodec 支持的
 * 格式照常走平台链（零行为变化）；只有平台全不支持的格式（如无 AV1 解码器的设备播
 * AV1）FFmpeg 才被自动选中。平台支持但硬软双崩的格式，由 PlayerPool 的监听器用
 * DefaultTrackSelector.setRendererDisabled 禁用 MediaCodec 视频渲染器后顶位（第三级）。
 *
 * FFmpeg 库不可用（native 加载失败/ABI 未打包）时一个渲染器都不加，
 * 行为与接入前完全一致；FfmpegLibrary.isAvailable 只做 System.loadLibrary 探测，
 * 无 media3 版本门禁（字节码实证）。
 *
 * 构建期交接棒：渲染器下标与归一器都在 build*Renderers/buildAudioSink 回调里记录
 * （ExoPlayer.Builder.build 内同步回调，构建返回后 attachTo 取回，无竞态），
 * 供 PlayerPool 禁用/恢复 MediaCodec 视频渲染器与转接内容 key 时定位。
 */
private class FfmpegFallbackRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    /** MediaCodec 视频渲染器在数组中的下标（视频渲染器最先构建且默认不开扩展位，恒为 0；未构建为 -1） */
    var mediaCodecVideoIndex: Int = -1
        private set

    /** FFmpeg 视频渲染器下标；<0 表示 FFmpeg 不可用未入列（此时三级回落逻辑自动停用） */
    var ffmpegVideoIndex: Int = -1
        private set

    /** FFmpeg native 库可用且视频渲染器已入列 */
    val ffmpegAvailable: Boolean get() = ffmpegVideoIndex >= 0

    /** 最近一次 buildAudioSink 挂上的归一器（构建期交接棒，同 NormalizingRenderersFactory 语义） */
    private var lastNormalizer: LoudnessNormalizer? = null

    /** 音量均衡挂点（复刻 NormalizingRenderersFactory.buildAudioSink，逐机独立归一器） */
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

    /** 内容 key 转接给本机归一器（复刻 NormalizingRenderersFactory.attachTo，语义见其注释） */
    fun attachTo(player: ExoPlayer) {
        val normalizer = lastNormalizer ?: return
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                normalizer.beginContent(mediaItem?.mediaId?.takeIf { it.isNotEmpty() })
            }
        })
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        super.buildVideoRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            eventHandler, eventListener, allowedVideoJoiningTimeMs, out,
        )
        // super 只加了一台 MediaCodec 视频渲染器（扩展渲染器模式默认关），即当前末位；
        // 记下供"双崩后禁用它"定位
        mediaCodecVideoIndex = out.size - 1
        // TEMP-R125-TEST：定位强制档黑屏用，随临时钩一并撤除
        android.util.Log.w(
            "PlayerPool",
            "TEMP-TEST ffmpegLibAvailable=${FfmpegLibrary.isAvailable()} mcIndex=$mediaCodecVideoIndex",
        )
        if (!FfmpegLibrary.isAvailable()) return
        out.add(
            FfmpegVideoRenderer(
                allowedVideoJoiningTimeMs, eventHandler, eventListener,
                MAX_DROPPED_FRAMES_TO_NOTIFY,
            ),
        )
        ffmpegVideoIndex = out.size - 1
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            audioSink, eventHandler, eventListener, out,
        )
        // 音频同思路：排尾部被动兜底（AC3/DTS 等平台无解码器的格式自动顶位，不做强制
        // 换档）。复用本机构建的同一条 AudioSink——音量归一器挂在它上面，FFmpeg 音频
        // 路径不丢均衡
        if (!FfmpegLibrary.isAvailable()) return
        out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
    }

    companion object {
        /** 丢帧通知阈值：与 DefaultRenderersFactory 构建 MediaCodec 渲染器时的默认一致 */
        private const val MAX_DROPPED_FRAMES_TO_NOTIFY = 50
    }
}
