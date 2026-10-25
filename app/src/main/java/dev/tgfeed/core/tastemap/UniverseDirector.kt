package dev.tgfeed.core.tastemap

import java.io.File
import kotlin.math.floor
import kotlin.math.sin

/**
 * R282 活宇宙 2.0·宇宙导演器（**纯 Kotlin 零 Android 依赖**——JVM 单测可跑；
 * galaxy-roadmap R273「物理+叙事导演混合」的导演半边，R249 热化教训的字面落地：
 * 纯物理会发散——所以是「物理保形 + 事件导演」）。
 *
 * 导演的戏（四类宇宙事件的节奏与叙事）：
 *  - **星云旋臂缓慢流转**（nebular drift）：非物理爆炸，是**受控缓慢形变**——
 *    未探索雾团绕世界 Y 轴 ±3.2° 正弦摇曳 + ±3% 径向呼吸（各团错相错频），
 *    相位由导演器慢钟推进（[Config.driftPeriodSec] 基准一圈 × 活跃度微加速）。
 *    振荡不漂走（雾团永不远离其成员星域=语义不破）、**不喂回 N 体**（保形）；
 *  - **彗星事件**=爬虫发现新频道（R206 雏形的导演化接管）：爬虫脉冲只是信号，
 *    发不发、何时发、多亮（轨迹叙事=强度→寿命/参数组曲）由节奏闸裁决；
 *  - **新星诞生/超新星**=频道从死到活（prev7==0 ∧ recent7≥2）/爆发（分类器
 *    [CelestialKind.SUPERNOVA] 判据族内的演出级强度门 recent7≥8）：装配档案
 *    筛候选 → 开场 24s 起 6 分钟窗哈希散布排程（「宇宙陆续苏醒」不轰炸）→
 *    到期过闸演出；
 *  - **用户观看=流星轨迹**（R206 今晚星轨已有循环）：导演器统管节奏密度——
 *    [meteorPaceOf] 输出引擎流星档（并发事件多时星轨让速，虫洞 transit 暂停）。
 *
 * 导演器三原则（任务书原文口径）：
 *  1. **事件有节律不轰炸**——节奏闸五门（[gateOf] 纯函数，拒因枚举可单测）：
 *     全局最小间隔 / 每分钟全局上限 / 每分钟分类上限 / 同屏并发上限 /
 *     同星冷却。到期不过闸=挂起到死线再弃（拒因落探针账——可证伪的「不轰炸」）；
 *  2. **物理保形**——导演器不持物理引用、无一字写 N 体状态；事件全部是
 *     叠加视觉层指令（渲染无关 [Directive]，屏幕侧翻译成 FxEngine 调用）。
 *     验证口径：R249「N体」探针（界内/动能）与事件流同框对照；
 *  3. **可回放**——事件账本（[Entry] 行式文件，R235 TasteVoyageLog 同款原子写
 *     纪律）+ 确定性种子（同种子同轨迹）+ [replayEntries] 纯函数重演；
 *     账本热身（[warmUp]）让节奏闸跨进程重启记忆——重进 App 不被再炸一轮。
 *
 * 确定性：零 Random 零系统钟（nowMs 全入参）；同输入序列→同指令序列
 * （双状态对拍单测锚定）。
 *
 * 线程纪律（FxEngine 同款）：[State] 全部可变字段只在**主线程**读写；
 * 唯一例外=[State.driftTurns]（@Volatile Double——GL 渲染线程经
 * [driftTurnsOf] 只读，主线程 tick 单写，Double 非原子但漂移相位撕裂读
 * 最坏一帧微差=良性，与 GlFrameBuilder 直读引擎账同款先例）。
 */
object UniverseDirector {

    /** 事件账本文件名（ui 侧 File(filesDir/galaxy, 此名)——TasteVoyageLog 同目录） */
    const val EVENTS_FILE = "universe_events.txt"

    /** 账本环形上限（条）——小文件有界，超出挤掉最老 */
    const val JOURNAL_CAP = 384

    /**
     * 事件种类（账本 kind 序号=ordinal——只加不改序）。R320 GRAVITY_WAVE 排尾追加
     * （r315 S6/r317 挂账⑥ 兑现——旧三值序号不动=旧账本回放兼容）。
     */
    enum class EventKind(val label: String) {
        /** 彗星：爬虫发现新频道（轨迹叙事：强度→寿命/参数组曲） */
        COMET("彗星"),

        /** 新星诞生：频道从死到活（复活）/ 爬虫爬到在图新频道 */
        NOVA("新星"),

        /** 超新星：频道爆发（recent7≥8 且 前周死或≥3×前周——演出级稀有） */
        SUPERNOVA("超新星"),

        /**
         * R320 引力波：终态宣判事件的涟漪档（黑洞/中子星/夸克星宣判=质量剧变时刻
         * ——星点被波前推移回弹的时空涟漪演出）。触发源=场景候选（宣判星高优先）
         * +[enqueueGravityWave] 入队口（R327 诞生侧跃迁 diff 对接口——r325 C 章
         * 「跃迁演出走 Directive 参数位」：波前参数=本档 Directive 尾参）。
         * 幅度=超新星档 1.5×（质量更大的事件=更强的波——物理诚实，GravityWaveFx
         * 宣判档单源）。
         */
        GRAVITY_WAVE("引力波"),
    }

    /** 节奏闸拒因（探针账/单测锚定——「不轰炸」的可证伪口径） */
    enum class Reject {
        /** 距上一事件 < 全局最小间隔 */
        MIN_INTERVAL,

        /** 本类事件分钟窗满 */
        KIND_MINUTE_CAP,

        /** 全局分钟窗满 */
        GLOBAL_MINUTE_CAP,

        /** 同屏并发满（按 startMs+duration 活窗计） */
        CONCURRENT_CAP,

        /** 同星冷却期内 */
        STAR_COOLDOWN,

        /** 挂起到死线仍不过闸——弃演（排程事件不无限等） */
        DEADLINE_DROP,
    }

    /** 节奏/叙事配置（默认值=任务书「有节律不轰炸」的定标；单测可换小值） */
    class Config(
        /** 全局每分钟事件上限 */
        val globalPerMinute: Int = 5,
        /** 彗星每分钟上限 */
        val cometPerMinute: Int = 2,
        /** 新星每分钟上限 */
        val novaPerMinute: Int = 2,
        /** 超新星每分钟上限（最稀有） */
        val supernovaPerMinute: Int = 1,
        /** R320 引力波每分钟上限（宣判事件本就稀有——最稀有档） */
        val gravityWavePerMinute: Int = 1,
        /** 同屏并发事件上限（活窗=startMs+durationMs） */
        val maxConcurrent: Int = 3,
        /** 任意两事件最小间隔（ms） */
        val minIntervalMs: Long = 5_000L,
        /** 同星冷却（ms）——同一颗星的演出不连续轰炸 */
        val starCooldownMs: Long = 120_000L,
        /** 开场排程首发延迟（ms）——进图先看星野，宇宙陆续苏醒 */
        val startDelayMs: Long = 24_000L,
        /** 开场排程散布窗（ms）——候选事件在窗内哈希定刻 */
        val spreadWindowMs: Long = 360_000L,
        /** 排程事件挂起死线（ms）——到期不过闸最多再等这么久 */
        val pendingDeadlineMs: Long = 60_000L,
        /** 星云漂移基准周期（秒/圈——缓慢：4 分钟一圈） */
        val driftPeriodSec: Float = 240f,
        /** 星云摇曳角幅（rad——±3.2°：可读的流转，不晕的克制） */
        val driftSwayRad: Float = 0.055f,
        /** 星云径向呼吸幅（±3%） */
        val driftRadialAmp: Float = 0.03f,
    )

    /**
     * 事件指令（**渲染无关**——屏幕侧原样翻译成 FxEngine 调用：彗星=
     * spawnComet(seed, cometLifeSec)；新星/超新星/引力波=flashStar(chatId,
     * flashTtlSec)+投影位 burst(burstParticles/burstSpeedPx/burstLifeSec/
     * burstSizePx)）。[seed]=轨迹/粒子确定性复现键（账本重演同种子同轨迹）。
     *
     * R320 波前参数位（waveAmpPx/waveLifeSec——r325 C 章「跃迁演出走 Directive
     * 参数位」的对接位，GravityWaveFx 单源出值）：GRAVITY_WAVE 档=宣判档
     * （9.0s 演出级闪光经 FxEngine 波前门入账+本参数位供 R327 跃迁 diff 直接
     * 消费）；SUPERNOVA 档=涟漪档（演出第二视觉层——波前同随 flashStar TTL 门
     * 自动在场）。尾参带默认值=旧构造点零变化（S5 只加不改纪律）。
     */
    class Directive(
        val kind: EventKind,
        val chatId: Long,
        val seed: Int,
        val intensity01: Float,
        val startMs: Long,
        val durationMs: Long,
        val burstParticles: Int,
        val burstSpeedPx: Float,
        val burstLifeSec: Float,
        val burstSizePx: Float,
        val flashTtlSec: Float,
        val cometLifeSec: Float,
        /** R320 波前幅度（px——GravityWaveFx.rippleAmpPxOf/verdictAmpPxOf 单源出值） */
        val waveAmpPx: Float = 0f,
        /** R320 波前寿命（秒——GravityWaveFx.waveLifeSecOf 单源出值） */
        val waveLifeSec: Float = 0f,
    )

    /** 场景事件候选（装配档案筛出——[sceneCandidates] 的产出） */
    class SceneCandidate(val chatId: Long, val kind: EventKind, val intensity01: Float)

    /** 候选筛选输入签名（从 StarNode 映射的最小字段——core 不依赖 ui 类型） */
    class StarSig(val chatId: Long, val kind: CelestialKind, val recent7: Int, val prev7: Int)

    /** 账本条目（一行一件——`epochMs|kindOrdinal|chatId|seed|intensityMilli`） */
    class Entry(
        val epochMs: Long,
        val kind: EventKind,
        val chatId: Long,
        val seed: Int,
        val intensityMilli: Int,
    )

    /** 待发候选（内部排程件——小数组纪律，容量 ≤32） */
    internal class Pending(
        val kind: EventKind,
        val chatId: Long,
        val seed: Int,
        val intensity01: Float,
        val dueMs: Long,
        val deadlineMs: Long,
        /** true=开场排程档（换场重排清这档留爬虫档）；false=爬虫脉冲档 */
        val fromScene: Boolean,
        /** 已记过账的拒因（同候选同拒因只记一次——60fps 节拍不把账刷爆） */
        var rejectCounted: Reject? = null,
    )

    /**
     * 导演器状态（会话级——engine 同位 remember；**主线程单写读**，
     * [driftTurns] 例外见类头）。零库纯 Kotlin。
     */
    class State {
        /** 会话起点（首个 tick/排程锚定——探针/排程共用） */
        var sessionStartMs = 0L
            internal set

        /** 上一 tick 时刻（漂移相位积分锚） */
        var lastTickMs = 0L
            internal set

        /** 最近一次事件发火时刻（全局最小间隔闸；0=从未发火——epoch 毫秒恒正，
         *  绝不用 Long.MIN_VALUE 哨兵（nowMs-MIN_VALUE 溢出回绕=首闸全拒的实锤虫）） */
        var lastFireMs = 0L
            internal set

        // ---- 分钟窗环（全局 48 帽 + 分类 16 帽——环写覆盖最老） ----
        internal val fireMsRing = LongArray(48)
        internal var fireRingHead = 0
        internal var fireRingN = 0
        internal val kindFireMs = Array(EventKind.values().size) { LongArray(16) }
        internal val kindFireHead = IntArray(EventKind.values().size)
        internal val kindFireN = IntArray(EventKind.values().size)

        // ---- 并发活窗账（firedAt+duration；24 帽——闸上限远小于此） ----
        internal val actFireMs = LongArray(24)
        internal val actDurMs = LongArray(24)
        internal var actN = 0

        /** 同星冷却账（chatId → 最近发火时刻；事件量级有界） */
        internal val lastStarFireMs = HashMap<Long, Long>(64)

        /** 待发排程（≤32；到期→闸→指令/挂起/弃） */
        internal val pendings = ArrayList<Pending>(32)

        /** 会话已演账（chatId → 已演 kind 位集——同星同种一会话只演一次） */
        internal val firedKindBits = HashMap<Long, Int>(64)

        /**
         * 星云漂移相位（圈数，Double 保精度——Float 在长会话下量化抖动；
         * @Volatile=GL 帧线程只读/主线程 tick 单写，撕裂读良性见类头）
         */
        @Volatile
        var driftTurns = 0.0
            internal set

        /** 虫洞 transit 保持（屏幕侧写入——transit 期流星节奏归零让位） */
        var transitHold = false

        // ---- 探针账（屏幕侧分钟探针读——core 零 Log 纪律） ----
        /** 各档已发数 */
        val emitted = IntArray(EventKind.values().size)

        /** 各拒因累计 */
        val rejected = IntArray(Reject.values().size)

        /** 本场排程数（装配探针） */
        var scheduledScene = 0
            internal set
    }

    // ==================== 确定性哈希（同参同值——撒种即无状态） ====================

    /** 0..1 哈希（Long 折叠进 FxEngine 同款 Int 混合式——同数据同轨迹） */
    internal fun h01(a: Long, b: Int): Float {
        val ai = (a xor (a ushr 32)).toInt()
        return (
            ((ai * -0x61c8897 and 0x7FFFFFFF) xor (b * 0x1b873593 and 0x7FFFFFFF)) and 0xFFFFFF
            ).toFloat() / 16777216f
    }

    // ==================== 节奏闸（纯函数五门） ====================

    /** 分类分钟上限查表 */
    internal fun kindCapOf(cfg: Config, kind: EventKind): Int = when (kind) {
        EventKind.COMET -> cfg.cometPerMinute
        EventKind.NOVA -> cfg.novaPerMinute
        EventKind.SUPERNOVA -> cfg.supernovaPerMinute
        EventKind.GRAVITY_WAVE -> cfg.gravityWavePerMinute
    }

    /** 全局分钟窗内已发数（探针/闸共用） */
    fun minuteCount(state: State, nowMs: Long): Int {
        var n = 0
        val m = state.fireRingN
        for (i in 0 until m) {
            if (state.fireMsRing[i] > nowMs - 60_000L) n++
        }
        return n
    }

    internal fun kindMinuteCount(state: State, kind: EventKind, nowMs: Long): Int {
        val k = kind.ordinal
        var n = 0
        val m = state.kindFireN[k]
        val ring = state.kindFireMs[k]
        for (i in 0 until m) {
            if (ring[i] > nowMs - 60_000L) n++
        }
        return n
    }

    /** 并发活窗内事件数（流星节奏/并发闸共用） */
    fun concurrentCount(state: State, nowMs: Long): Int {
        var n = 0
        for (i in 0 until state.actN) {
            if (state.actFireMs[i] + state.actDurMs[i] > nowMs) n++
        }
        return n
    }

    /**
     * 节奏闸五门（顺序即优先级——首拒即返；null=放行）：
     * 全局最小间隔 → 全局分钟帽 → 分类分钟帽 → 并发帽 → 同星冷却。
     */
    internal fun gateOf(state: State, kind: EventKind, chatId: Long, nowMs: Long, cfg: Config): Reject? {
        if (nowMs - state.lastFireMs < cfg.minIntervalMs) return Reject.MIN_INTERVAL
        if (minuteCount(state, nowMs) >= cfg.globalPerMinute) return Reject.GLOBAL_MINUTE_CAP
        if (kindMinuteCount(state, kind, nowMs) >= kindCapOf(cfg, kind)) return Reject.KIND_MINUTE_CAP
        if (concurrentCount(state, nowMs) >= cfg.maxConcurrent) return Reject.CONCURRENT_CAP
        if (chatId != 0L) {
            val last = state.lastStarFireMs[chatId]
            if (last != null && nowMs - last < cfg.starCooldownMs) return Reject.STAR_COOLDOWN
        }
        return null
    }

    /** 发火记账（闸过后调用——环写覆盖最老；并发账先挤枯再压） */
    private fun recordFire(state: State, kind: EventKind, chatId: Long, nowMs: Long, durationMs: Long) {
        state.lastFireMs = nowMs
        // 全局环
        if (state.fireRingN < state.fireMsRing.size) {
            state.fireMsRing[state.fireRingN++] = nowMs
        } else {
            state.fireMsRing[state.fireRingHead] = nowMs
            state.fireRingHead = (state.fireRingHead + 1) % state.fireMsRing.size
        }
        // 分类环
        val k = kind.ordinal
        if (state.kindFireN[k] < state.kindFireMs[k].size) {
            state.kindFireMs[k][state.kindFireN[k]++] = nowMs
        } else {
            state.kindFireMs[k][state.kindFireHead[k]] = nowMs
            state.kindFireHead[k] = (state.kindFireHead[k] + 1) % state.kindFireMs[k].size
        }
        // 并发账：先挤枯（活窗已尽的槽位回收）
        var i = 0
        while (i < state.actN) {
            if (state.actFireMs[i] + state.actDurMs[i] <= nowMs) {
                val last = state.actN - 1
                state.actFireMs[i] = state.actFireMs[last]
                state.actDurMs[i] = state.actDurMs[last]
                state.actN = last
            } else {
                i++
            }
        }
        if (state.actN < state.actFireMs.size) {
            state.actFireMs[state.actN] = nowMs
            state.actDurMs[state.actN] = durationMs
            state.actN++
        }
        if (chatId != 0L) state.lastStarFireMs[chatId] = nowMs
        state.emitted[kind.ordinal]++
    }

    // ==================== 指令组曲（导演定参数——屏幕侧是哑执行器） ====================

    /** 种类+强度 → 指令参数（**单口**——tick/replay 共用，同种同参=可复现） */
    fun directiveOf(kind: EventKind, chatId: Long, seed: Int, intensity01: Float, startMs: Long): Directive {
        val i = intensity01.coerceIn(0f, 1f)
        return when (kind) {
            EventKind.COMET -> Directive(
                kind, chatId, seed, i, startMs,
                durationMs = ((2.2f + 1.4f * i) * 1000f).toLong(),
                burstParticles = 0, burstSpeedPx = 0f, burstLifeSec = 0f, burstSizePx = 0f,
                flashTtlSec = 0f, cometLifeSec = 2.2f + 1.4f * i,
            )
            EventKind.NOVA -> Directive(
                kind, chatId, seed, i, startMs,
                durationMs = 4_500L,
                burstParticles = (10f + 8f * i).toInt(), burstSpeedPx = 300f + 120f * i,
                burstLifeSec = 0.70f, burstSizePx = 2.8f,
                flashTtlSec = 4.5f, cometLifeSec = 0f,
            )
            EventKind.SUPERNOVA -> Directive(
                kind, chatId, seed, i, startMs,
                durationMs = 7_000L,
                burstParticles = (22f + 10f * i).toInt(), burstSpeedPx = 480f + 160f * i,
                burstLifeSec = 1.10f, burstSizePx = 3.6f,
                flashTtlSec = 7.0f, cometLifeSec = 0f,
                // R320 涟漪档：超新星演出的第二视觉层（波前随 flashStar TTL=7.0
                // 过演出门自动在场——「同 Directive」r315 E④ 字面）
                waveAmpPx = GravityWaveFx.rippleAmpPxOf(i),
                waveLifeSec = GravityWaveFx.waveLifeSecOf(i),
            )
            EventKind.GRAVITY_WAVE -> Directive(
                kind, chatId, seed, i, startMs,
                durationMs = 9_000L,
                burstParticles = (18f + 8f * i).toInt(), burstSpeedPx = 360f + 120f * i,
                burstLifeSec = 0.90f, burstSizePx = 3.0f,
                flashTtlSec = 9.0f, cometLifeSec = 0f,
                // R320 宣判档：质量更大的事件=更强的波（1.5×——GravityWaveFx 单源）；
                // flashTtl=9.0 过 FxEngine 演出级闪光门（≥8.5=宣判档幅度）→渲染端
                // 波前账入账（执行器既有 else 分支零改动的数据通路）
                waveAmpPx = GravityWaveFx.verdictAmpPxOf(i),
                waveLifeSec = GravityWaveFx.waveLifeSecOf(i),
            )
        }
    }

    // ==================== 信号入口 ====================

    /**
     * 爬虫脉冲（R206 彗星雏形的导演化接管——watcher 只报信号，节奏/轨迹叙事
     * 归导演器）：入队一颗彗星（即刻到期）+ 在图新频道的新星（≤3 颗，错拍
     * 700ms 尾随彗星=「彗星掠过处新星点亮」的叙事序）。已在队/已演同星跳过。
     */
    fun enqueueCrawl(state: State, nowMs: Long, cfg: Config, seed: Int, onMapNewChatIds: LongArray, count: Int) {
        val intensity = (0.35f + 0.15f * count).coerceAtMost(1f)
        if (state.pendings.size < 32) {
            state.pendings += Pending(
                EventKind.COMET, 0L, seed, intensity, nowMs,
                nowMs + cfg.pendingDeadlineMs, fromScene = false,
            )
        }
        val n = minOf(count, 3)
        for (idx in 0 until n) {
            val chatId = onMapNewChatIds[idx]
            if (chatId == 0L) continue
            // 爬虫脉冲语义=「爬到新内容」不是「诞生」——同星可再现（R206 爆闪同口径），
            // 轰炸防护=同星冷却闸（不走会话已演账——账留给状态型排程候选）
            var dup = false
            for (p in state.pendings) {
                if (p.chatId == chatId && p.kind == EventKind.NOVA) { dup = true; break }
            }
            if (dup || state.pendings.size >= 32) continue
            state.pendings += Pending(
                EventKind.NOVA, chatId,
                (chatId % 100_000L).toInt(), 0.60f,
                nowMs + 700L * (idx + 1), nowMs + 700L * (idx + 1) + cfg.pendingDeadlineMs,
                fromScene = false,
            )
        }
    }

    /**
     * 超新星演出强度（recent7→0.60~1.0——**单一出处**：场景候选与 R320 宣判波
     * 候选共用同式，强度公式不许两处各写一份）。
     */
    internal fun supernovaIntensityOf(recent7: Int): Float =
        (0.60f + recent7 / 42f).coerceIn(0.60f, 1f)

    /**
     * R320 引力波入队口（**R327 诞生侧跃迁 diff 的对接位**——r325 B①-2/C 章
     * 「跃迁清单与 GRAVITY_WAVE 批 3 同口排队」）：宣判/跃迁事件即刻错拍入队
     * （+700ms 尾随——[enqueueCrawl] 爬虫档同款错拍序），节奏闸五门照常把关
     * （不过闸挂起到死线再弃，拒因落账）。同星同种在队去重（防跃迁 diff 对同
     * 一颗星连发重演）。**本批只定义不依赖**：主树既有演出走 sceneCandidates
     * 排程档（fromScene=true 经开场散布窗），本口为运行时事件（宣判 diff 检出
     * /诞生跃迁）保留——R327 wiring 落地时消费，零双向依赖。
     */
    fun enqueueGravityWave(state: State, nowMs: Long, cfg: Config, chatId: Long, intensity01: Float) {
        if (chatId == 0L) return
        if (state.pendings.size >= 32) return
        for (p in state.pendings) {
            if (p.chatId == chatId && p.kind == EventKind.GRAVITY_WAVE) return
        }
        val due = nowMs + 700L
        state.pendings += Pending(
            EventKind.GRAVITY_WAVE, chatId,
            (chatId % 100_000L).toInt(), intensity01.coerceIn(0f, 1f),
            due, due + cfg.pendingDeadlineMs, fromScene = false,
        )
    }

    /**
     * 场景候选筛选（装配档案→演出候选；纯函数确定性）：
     *  - **R320 引力波演出（宣判波）**：终态宣判星（黑洞/中子星/夸克星=R317b
     *    宣判入谱的**坍缩族**）→GRAVITY_WAVE 档候选（质量剧变时刻的时空涟漪
     *    重演——黑洞宣判事件高优先）；强度=[supernovaIntensityOf] 同式对接
     *    （缺据 recent7<0=0.72 基档诚实缺省——宣判事实在场，活跃证据缺席）；
     *  - **超新星演出**：[CelestialKind.SUPERNOVA] 判据族（金矿 recent7/prev7 爆发
     *    判定=分类器单一判据源，不另立炉灶）内的**演出级强度门** recent7≥8
     *    （比入档更严——演出稀有才壮观）；超新星演出的涟漪=指令第二视觉层
     *    （波前随 flashStar TTL 自动在场——不经 GRAVITY_WAVE 候选双演）；
     *  - **新星演出（复活）**：prev7==0 ∧ recent7∈[2,+∞)——「从死到活」的叙事
     *    （复活且够上超新星演出门的归强档不双演）；
     *  - prev7<0=无金矿证据=**不演**（诚实缺据——没有证据不冒充复活）；
     *  - 特殊天体（脉冲星/星团/流星）不演；白洞不演（源非坍缩——只出不进
     *    不扰动时空）；坍缩族改走宣判波档（黑洞吸积盛宴不是爆发叙事——是涟漪）。
     * 排序：引力波（宣判高优先）→ 超新星 → recent7 降序 → chatId 升序破平；
     * 上限 [cap]。
     */
    fun sceneCandidates(stars: List<StarSig>, cap: Int = 24): List<SceneCandidate> {
        if (stars.isEmpty() || cap <= 0) return emptyList()
        val picked = ArrayList<SceneCandidate>(cap)
        val recentOf = HashMap<Long, Int>(cap * 2)
        for (s in stars) {
            // ---- R320 宣判波候选（坍缩族——黑洞/中子/夸克，排首高优先） ----
            if (s.kind == CelestialKind.BLACK_HOLE || s.kind == CelestialKind.NEUTRON_STAR ||
                s.kind == CelestialKind.QUARK_STAR
            ) {
                val gi = if (s.recent7 >= 0) supernovaIntensityOf(s.recent7) else 0.72f
                picked += SceneCandidate(s.chatId, EventKind.GRAVITY_WAVE, gi)
                recentOf[s.chatId] = s.recent7
                continue
            }
            when (s.kind) {
                CelestialKind.PULSAR, CelestialKind.CLUSTER,
                CelestialKind.METEOR,
                -> continue
                else -> {}
            }
            val supernova = s.kind == CelestialKind.SUPERNOVA && s.recent7 >= 8
            val nova = !supernova && s.prev7 == 0 && s.recent7 >= 2
            if (!supernova && !nova) continue
            val kind = if (supernova) EventKind.SUPERNOVA else EventKind.NOVA
            val intensity = if (supernova) {
                supernovaIntensityOf(s.recent7)
            } else {
                (0.42f + 0.08f * s.recent7).coerceIn(0.42f, 0.98f)
            }
            picked += SceneCandidate(s.chatId, kind, intensity)
            recentOf[s.chatId] = s.recent7
        }
        picked.sortWith(
            compareByDescending<SceneCandidate> { candidatePriorityOf(it.kind) }
                .thenByDescending { recentOf[it.chatId] ?: 0 }
                .thenBy { it.chatId },
        )
        return if (picked.size > cap) picked.subList(0, cap).toList() else picked
    }

    /** 候选优先序（宣判波 2 > 超新星 1 > 复活 0——排序键单一出处） */
    internal fun candidatePriorityOf(kind: EventKind): Int = when (kind) {
        EventKind.GRAVITY_WAVE -> 2
        EventKind.SUPERNOVA -> 1
        else -> 0
    }

    /**
     * 开场排程（场景装载后调用一次）：候选在 [Config.startDelayMs] 后起
     * [Config.spreadWindowMs] 窗内按 哈希(chatId,kind) 定刻（确定性散布=
     * 「宇宙陆续苏醒」不轰炸）。换场重排=清旧排程档（爬虫脉冲档保留）；
     * 本场已演的同星同种不再排（会话账）。
     */
    fun scheduleSceneEvents(state: State, nowMs: Long, cfg: Config, candidates: List<SceneCandidate>) {
        val it = state.pendings.iterator()
        while (it.hasNext()) {
            if (it.next().fromScene) it.remove()
        }
        if (state.sessionStartMs == 0L) state.sessionStartMs = nowMs
        for (c in candidates) {
            if (state.pendings.size >= 32) break
            if ((state.firedKindBits[c.chatId] ?: 0) and (1 shl c.kind.ordinal) != 0) continue
            val due = nowMs + cfg.startDelayMs +
                (h01(c.chatId, c.kind.ordinal * 31 + 5) * cfg.spreadWindowMs).toLong()
            state.pendings += Pending(
                c.kind, c.chatId, (c.chatId % 100_000L).toInt(), c.intensity01,
                due, due + cfg.pendingDeadlineMs, fromScene = true,
            )
        }
        state.scheduledScene = candidates.size
    }

    // ==================== 节拍（帧循环调用——主线程） ====================

    /**
     * 导演一拍：①漂移相位积分（dt 钳 ≤500ms——掉帧不跳变；活跃度微加速=
     * 最近一分钟发出数越多流转稍快，克制的 ±50%）；②到期排程过闸→指令
     * （不过闸挂起到死线，死线弃演落拒因账）。产出写入 [out]（调用方复用
     * 清单——帧路径零分配纪律；本函数只在真发火时 new Directive）。
     */
    fun tick(state: State, nowMs: Long, cfg: Config, out: MutableList<Directive>) {
        if (state.sessionStartMs == 0L) state.sessionStartMs = nowMs
        // 漂移相位（首拍只锚钟不推进）
        val last = state.lastTickMs
        state.lastTickMs = nowMs
        if (last > 0L && nowMs > last) {
            val dtMs = (nowMs - last).coerceAtMost(500L)
            val boost = 1.0 + 0.5 * (minuteCount(state, nowMs) / 6.0).coerceAtMost(1.0)
            state.driftTurns += dtMs.toDouble() / (cfg.driftPeriodSec.toDouble() * 1000.0) * boost
        }
        if (state.pendings.isEmpty()) return
        val it = state.pendings.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (nowMs < p.dueMs) continue
            val reject = gateOf(state, p.kind, p.chatId, nowMs, cfg)
            if (reject == null) {
                val d = directiveOf(p.kind, p.chatId, p.seed, p.intensity01, nowMs)
                recordFire(state, p.kind, p.chatId, nowMs, d.durationMs)
                state.firedKindBits[p.chatId] =
                    (state.firedKindBits[p.chatId] ?: 0) or (1 shl p.kind.ordinal)
                out += d
                it.remove()
            } else if (nowMs > p.deadlineMs) {
                state.rejected[Reject.DEADLINE_DROP.ordinal]++
                it.remove()
            } else {
                // 挂起等下拍；同候选同拒因只记一次（帧级节拍不刷爆探针账）
                if (p.rejectCounted != reject) {
                    p.rejectCounted = reject
                    state.rejected[reject.ordinal]++
                }
            }
        }
    }

    // ==================== 导演输出（节奏/漂移） ====================

    /**
     * 流星节奏档（今晚星轨的密度统管——FxEngine.meteorPaceScale 的消费口径）：
     * 虫洞 transit=0（穿越时星轨让位）；并发≥2=0.5 / 并发==1=0.75（事件演出
     * 期间星轨减速不抢戏）；无事件=1.0（原速）。
     */
    fun meteorPaceOf(state: State, nowMs: Long, cfg: Config): Float {
        if (state.transitHold) return 0f
        return when {
            concurrentCount(state, nowMs) >= 2 -> 0.5f
            concurrentCount(state, nowMs) == 1 -> 0.75f
            else -> 1.0f
        }
    }

    /** 漂移相位读口（圈数——GL 帧线程经此只读；@Volatile 字段访问） */
    fun driftTurnsOf(state: State): Double = state.driftTurns

    /**
     * 星云摇曳（受控缓慢形变的数学件——帧组装对每团雾心调用）：
     * 绕世界 Y 轴 ±[Config.driftSwayRad] 正弦摇曳（各团错频 ωᵢ=1+0.13i、
     * 错相 φᵢ=哈希）+ ±[Config.driftRadialAmp] 径向呼吸。**振荡不漂走**
     * （雾团永不远离成员星域=未探索语义不破）；零写入 N 体（保形）。
     * [out] 长 ≥3，写漂移后世界坐标。
     */
    fun applyDrift(cx: Float, cy: Float, cz: Float, slot: Int, phaseTurns: Double, cfg: Config, out: FloatArray) {
        val omega = 1.0 + 0.13 * slot
        val phi = h01(slot.toLong(), 7).toDouble() * (2.0 * Math.PI)
        val t1 = phaseTurns * omega
        val sway = sin(2.0 * Math.PI * (t1 - floor(t1)) + phi) * cfg.driftSwayRad
        val t2 = phaseTurns * 0.7 * omega
        val radial = 1f + (sin(2.0 * Math.PI * (t2 - floor(t2)) + phi * 1.7) * cfg.driftRadialAmp).toFloat()
        val cosS = kotlin.math.cos(sway)
        val sinS = sin(sway)
        out[0] = (cx * cosS + cz * sinS).toFloat() * radial
        out[1] = cy * radial
        out[2] = (-cx * sinS + cz * cosS).toFloat() * radial
    }

    // ==================== 账本（可回放 + 跨重启节奏记忆） ====================

    /**
     * 账本热身（开图装载后调用）：60s 窗内历史事件计入分钟闸、同星冷却窗
     * 内记账、全局最小间隔锚定——**跨进程重启节奏记忆**（重进 App 不会被
     * 开场排程再炸一轮的闸级保证）。
     */
    fun warmUp(state: State, entries: List<Entry>, nowMs: Long, cfg: Config) {
        if (state.sessionStartMs == 0L) state.sessionStartMs = nowMs
        if (state.lastTickMs == 0L) state.lastTickMs = nowMs
        for (e in entries) {
            if (e.epochMs > nowMs - 60_000L) {
                if (state.fireRingN < state.fireMsRing.size) {
                    state.fireMsRing[state.fireRingN++] = e.epochMs
                }
                val k = e.kind.ordinal
                if (state.kindFireN[k] < state.kindFireMs[k].size) {
                    state.kindFireMs[k][state.kindFireN[k]++] = e.epochMs
                }
            }
            if (e.chatId != 0L && e.epochMs > nowMs - cfg.starCooldownMs) {
                val prev = state.lastStarFireMs[e.chatId]
                if (prev == null || e.epochMs > prev) state.lastStarFireMs[e.chatId] = e.epochMs
            }
            if (e.epochMs > state.lastFireMs) state.lastFireMs = e.epochMs
        }
    }

    /** 账本追加入环（超出 [JOURNAL_CAP] 挤掉最老——小文件有界） */
    fun appendEntry(entries: MutableList<Entry>, e: Entry) {
        entries += e
        if (entries.size > JOURNAL_CAP) {
            entries.subList(0, entries.size - JOURNAL_CAP).clear()
        }
    }

    /** 账本序列化（行式 `epochMs|kindOrdinal|chatId|seed|intensityMilli`） */
    fun serializeJournal(entries: List<Entry>): String {
        val sb = StringBuilder(entries.size * 40)
        for (e in entries) {
            sb.append(e.epochMs).append('|')
                .append(e.kind.ordinal).append('|')
                .append(e.chatId).append('|')
                .append(e.seed).append('|')
                .append(e.intensityMilli).append('\n')
        }
        return sb.toString()
    }

    /** 账本解析（坏行静默跳过——诚实缺省，绝不抛） */
    fun parseJournal(text: String): List<Entry> {
        val out = ArrayList<Entry>(64)
        val kinds = EventKind.values()
        for (line in text.split('\n')) {
            if (line.isBlank()) continue
            val parts = line.split('|')
            if (parts.size != 5) continue
            val epochMs = parts[0].toLongOrNull() ?: continue
            val kindOrd = parts[1].toIntOrNull() ?: continue
            if (kindOrd !in kinds.indices) continue
            val chatId = parts[2].toLongOrNull() ?: continue
            val seed = parts[3].toIntOrNull() ?: continue
            val milli = parts[4].toIntOrNull() ?: continue
            out += Entry(epochMs, kinds[kindOrd], chatId, seed, milli)
        }
        return out
    }

    /** 读账本（文件缺失/损坏=空账诚实缺省，绝不抛——TasteVoyageLog 同款纪律） */
    fun loadJournal(file: File): List<Entry> {
        if (!file.isFile) return emptyList()
        return runCatching { parseJournal(file.readText()) }.getOrDefault(emptyList())
    }

    /** 原子写（临时文件+rename；失败静默——事件账丢一次可容忍，绝不抛） */
    fun saveJournal(file: File, entries: List<Entry>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(serializeJournal(entries))
            if (!tmp.renameTo(file)) {
                file.writeText(serializeJournal(entries))
                tmp.delete()
            }
        }
    }

    /**
     * 回放（可回放的纯函数件）：[windowMs] 窗内账本条目 → 按原相对序等距
     * [spacingMs] 重排程的指令串——**原种子/种类/强度**（确定性重演=同种子
     * 同轨迹）。UI 消费（「重播今日宇宙」）留后续件，本函数单测锚定口径。
     */
    fun replayEntries(
        entries: List<Entry>,
        nowMs: Long,
        windowMs: Long = 86_400_000L,
        spacingMs: Long = 1_500L,
    ): List<Directive> {
        val inWindow = entries.filter { it.epochMs >= nowMs - windowMs }.sortedBy { it.epochMs }
        val out = ArrayList<Directive>(inWindow.size)
        inWindow.forEachIndexed { idx, e ->
            out += directiveOf(e.kind, e.chatId, e.seed, e.intensityMilli / 1000f, nowMs + idx * spacingMs)
        }
        return out
    }
}
