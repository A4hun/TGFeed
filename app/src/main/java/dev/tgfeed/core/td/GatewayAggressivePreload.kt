package dev.tgfeed.core.td

import android.os.SystemClock
import android.util.Log
import dev.tgfeed.core.net.AppForeground
import dev.tgfeed.core.net.NetworkMeteredBus
import dev.tgfeed.core.playback.PlaybackTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// ---------------- R195 WiFi 激进整载（后台把当前视频整文件下满 + 下一条预载） ----------------
//
// 任务口径：现行边下边播只拉播放位前方 16MB 窗口 → 带宽永远跑不满（用户对照第三方
// TG 客户端实锤）。本件让 WiFi（非计量）下起播稳定 5s 后，以**低优先级**把当前在播
// 条目整文件下满；当前条完成后按 PreloadScheduler 推送的队列继续整载下 1~2 条
// （架构师追加：「当前视频加载完还可以预热下面几条」）。
//
// 地基与红线：
//  - R166 窗口抢占闸**零改动复用**：泵跳变查询（seek 到未下载区）先
//    CancelDownloadFile 取消在途属主（=本整载器发的整载）再发段查询重锚——
//    「在途整下载载杀 seek」是当年 P0 的根因，本整载器必须**可抢占可重启**
//    （notePreempt 钩子 + 稳定窗重启实现）。整载追上泵前缘后泵的顺序块查询全部
//    落在整载窗口内=「连续→同锚合并」（R166 口径），TDLib 侧下载永不停车=带宽
//    跑满；慢网时整载追不上前缘会被泵反复抢占（行为自动退化近精瘦模式，泵优先
//    永远成立——安全方向）。
//  - R158 整载路径：DownloadFile(id, p, 0, 0, false)（锚 0 全文件，已下载 part
//    自动跳过）——与 prioritize/WiFi 预载同一条 background full-load 链。
//  - R166 整下兜底表（fullDlFallbackFiles）内的文件让位不碰（兜底 p32 拥有它）。
//  - R196 存储治理契约：发起整载登记 storageGovernor.registerFullLoad（幂等刷新
//    lastUse），保留上限淘汰/在飞排除统一归 StorageGovernor 执行；本件只登记，
//    不做自己的删除链。在飞整载的 temp 碎片由 R196 T0 清扫的 GetFile
//    isDownloadingActive 在线核验保护（无需改其活跃集）。
//
// 两档候选 + 全局唯一整载槽（串行，带宽不劈叉、当前优先）：
//  - A 档·当前在播（[AGGR_PRIORITY_CURRENT]）：候选=TgStreamServer 在播登记
//    （conns>0，覆盖分屏/悬浮窗/频道主页/音频卡一切播放面）；滑走/播完连接归零
//    超宽限取消（保住 R166「滑走后不再后台磨完」语义）。分屏多路在播按先来后到
//    串行整载。
//  - B 档·下一条预载（[AGGR_PRIORITY_NEXT]）：PreloadScheduler 翻页落定推送的
//    目标（前 [AGGRESSIVE_NEXT_MAX] 条）；滑走作废（新推送不含）+ 过期作废
//    （TTL，防离开 Feed 后陈队列继续烧）+ 前台闸（R158 P0-2 后台静默哲学）。
//    滑到预载条目=conns 开→升档 A 无感接管（该条已下完时 ensurePlayable 直回
//    本地路径零加载圈=验收点；未下完时 p8 重发同锚 0 合并续载）。
//  优先级阶梯（TDLib priority）：泵 32 > 下一条前缀 16（PreloadScheduler 旧行为
//  不动）> 当前整载 8 > 下一条整载 4 > WiFi 预载 1。
//
// 决策收敛纯函数 [aggressivePreloadAction]（行为单测 AggressivePreloadPolicyTest）；
// 稳定窗纯函数 [aggrStableForMs]。

/** R195 B 档目标条数上限（架构师口径「下一个 1~2 条」，取 2；PreloadScheduler 推送侧共用） */
internal const val AGGRESSIVE_NEXT_MAX = 2

/** A 档（当前在播）整载优先级：低于泵 32/前缀 16，高于 B 档与 WiFi 预载 1 */
internal const val AGGR_PRIORITY_CURRENT = 8

/** B 档（下一条预载）整载优先级：低于 A 档，高于 WiFi 预载 1 */
internal const val AGGR_PRIORITY_NEXT = 4

/** 起播稳定窗：距最近不稳定事件 ≥5s 才 START/RESTART（任务书口径「5s 无 seek」） */
internal const val AGGR_STABLE_MS = 5_000L

/** A 档停播宽限：供流归零 ≥5s 才取消（连接 churn 短缝不误杀） */
internal const val AGGR_STOP_GRACE_MS = 5_000L

/** issued 停滞判定：TDLib 侧连续不活跃 ≥10s 视为外杀（无钩子的取消路径兜底） */
internal const val AGGR_STALL_MS = 10_000L

/** B 档队列 TTL：≥10min 无刷新视为陈队列作废（防离开 Feed 后继续烧带宽） */
internal const val AGGR_NEXT_QUEUE_TTL_MS = 10L * 60_000L

/** A/B 档：当前在播（conns 驱动）/ 下一条预载（队列推送驱动） */
internal enum class AggrTier { CURRENT, NEXT }

/**
 * 每文件整载管理状态（manager 内部；字段并发读取走 @Volatile）
 */
internal class AggrFileState internal constructor(
    /** 当前档位（NEXT 条目开始供流时升档 CURRENT=无感接管） */
    @Volatile var tier: AggrTier,
) {
    /** B 档：仍在最新推送队列内（滑走/过期作废判定原料） */
    @Volatile var queued: Boolean = false

    /** 本档位整载在册（我们发起过且未判定死亡/完成） */
    @Volatile var issued: Boolean = false

    /** 最近一次发整载时刻（preemptedAfterIssue = lastPreemptMs > issuedAtMs） */
    @Volatile var issuedAtMs: Long = 0L

    /** 最近一次窗口抢占（R166 闸取消在途属主）时刻——seek 类不稳定事件 */
    @Volatile var lastPreemptMs: Long = 0L

    /** A 档供流归零起点（0=在播；>0=自该刻起无供流连接，宽限判定用） */
    @Volatile var unstreamedSinceMs: Long = 0L

    /** 「issued 但 TDLib 侧连续不活跃」起点（0=活跃/未发；停滞兜底用） */
    @Volatile var inactiveSinceMs: Long = 0L

    // ---- 观测采样（aggrRate/aggrDone 数据源） ----
    /** 本轮整载起点（耗时口径；重发刷新） */
    @Volatile var startClockMs: Long = 0L

    @Volatile var startBytes: Long = 0L

    @Volatile var totalBytes: Long = 0L

    @Volatile var lastRateMs: Long = 0L

    @Volatile var lastRateBytes: Long = 0L
}

/**
 * 决策输入快照（纯函数入参；loop 每拍对每个 tracked 文件拼装）
 */
internal data class AggrFileFacts(
    val tier: AggrTier,
    /** 设置开关（AppPrefs.aggressivePreload，每拍直读=翻转 ≤1s 生效） */
    val enabled: Boolean,
    /** 计量网络（NetworkMeteredBus；true=整载一律不发/即停） */
    val metered: Boolean,
    /** 当前有供流连接（A 档语义核心） */
    val streaming: Boolean,
    /** 供流断开持续时长（在播=0；A 档宽限判定） */
    val unstreamedForMs: Long,
    /** 距最近一次不稳定事件（新连接注册/窗口抢占）的时长（A 档稳定窗） */
    val stableForMs: Long,
    /** GetFile.isDownloadingCompleted（整文件完成） */
    val completed: Boolean,
    /** R166 整下兜底表内（让位不碰） */
    val inFallback: Boolean,
    val issued: Boolean,
    /** 发出后发生过窗口抢占（闸已把我们的整载取消；稳定后该重启） */
    val preemptedAfterIssue: Boolean,
    /** issued 但 TDLib 侧连续不活跃的时长（外杀兜底） */
    val stalledForMs: Long,
    /** B 档：仍在最新推送队列内 */
    val queued: Boolean,
    /** B 档：距队列推送的时长（TTL 过期判定） */
    val queuedForMs: Long,
    /** 唯一整载槽被其他文件占用（A 档需求优先：B 档让位） */
    val slotOccupiedByOther: Boolean,
    /** B 档前台闸（AppForeground；退后台不再对新条目发整载） */
    val appInForeground: Boolean,
)

/** 决策动作（loop 按动作执行发/取消/摘牌；trace 口径见各 case 注释） */
internal enum class AggrAction {
    NONE,

    /** 发起整载（A 档 p8 / B 档 p4；含 B 档槽空闲后的按序起跑） */
    START,

    /** 被抢占/停滞死后稳定窗到点，重发整载（同锚 0 合并，零打断） */
    RESTART,
    CANCEL_METERED,
    CANCEL_DISABLED,

    /** A 档供流归零超宽限（滑走/播完——保住「滑走后不再磨完」语义） */
    CANCEL_STOPPED,

    /** B 档整载槽让位给 A 档需求（已下字节保留，滑回来即续） */
    CANCEL_TAKEOVER,

    /** B 档滑走作废 / 队列过期作废 */
    CANCEL_INVALIDATED,

    /** 整文件完成（trace aggrDone + 摘牌） */
    DONE,

    /** 安静摘牌（兜底表内/已完成未发/滑走未发/陈队列未发） */
    DROP,
}

private val AGGR_START_LIKE = setOf(AggrAction.START, AggrAction.RESTART)

/**
 * R195 整载决策（纯函数，行为单测 AggressivePreloadPolicyTest）。
 * 判定顺序即优先级（前面 case 先裁决）：
 *  1. 完成：issued→DONE（trace+摘牌）；未发→DROP；
 *  2. R166 兜底表内→DROP（兜底 p32 拥有该文件；同锚被兜底整载合并，安静让位）；
 *  3. 开关关/计量→issued 即取消（秒级止损，切回自动重启）；未发→NONE；
 *  4. B 档作废三连：滑走（不在队）/槽被 A 档需求占/队列过期——issued 取消
 *     （各自 trace 口径），未发直接 DROP；
 *  5. A 档停播：供流归零超 [AGGR_STOP_GRACE_MS]→取消+摘牌（churn 短缝不算）；
 *  6. 发起/重启三闸：全局槽被占→等（占槽的 B 档会被「A 档想要槽」判定取消）；
 *     issued 且（被抢占过∧稳定≥[AGGR_STABLE_MS] ∨ 停滞≥[AGGR_STALL_MS]）→
 *     RESTART；issued→保持；A 档在播且稳定→START；B 档在队∧前台∧未在播→START。
 */
internal fun aggressivePreloadAction(f: AggrFileFacts): AggrAction = when {
    f.completed && f.issued -> AggrAction.DONE
    f.completed -> AggrAction.DROP
    f.inFallback -> AggrAction.DROP
    f.issued && !f.enabled -> AggrAction.CANCEL_DISABLED
    f.issued && f.metered -> AggrAction.CANCEL_METERED
    f.tier == AggrTier.NEXT && f.issued && !f.queued -> AggrAction.CANCEL_INVALIDATED
    f.tier == AggrTier.NEXT && f.issued && f.slotOccupiedByOther -> AggrAction.CANCEL_TAKEOVER
    f.tier == AggrTier.NEXT && f.issued && f.queuedForMs >= AGGR_NEXT_QUEUE_TTL_MS -> AggrAction.CANCEL_INVALIDATED
    f.tier == AggrTier.NEXT && !f.queued -> AggrAction.DROP
    f.tier == AggrTier.NEXT && !f.issued && f.queuedForMs >= AGGR_NEXT_QUEUE_TTL_MS -> AggrAction.DROP
    f.tier == AggrTier.CURRENT && f.issued && !f.streaming && f.unstreamedForMs >= AGGR_STOP_GRACE_MS -> AggrAction.CANCEL_STOPPED
    f.tier == AggrTier.CURRENT && !f.streaming && f.unstreamedForMs >= AGGR_STOP_GRACE_MS -> AggrAction.DROP
    !f.enabled || f.metered -> AggrAction.NONE
    f.slotOccupiedByOther -> AggrAction.NONE
    f.issued && (f.preemptedAfterIssue && f.stableForMs >= AGGR_STABLE_MS || f.stalledForMs >= AGGR_STALL_MS) -> AggrAction.RESTART
    f.issued -> AggrAction.NONE
    f.tier == AggrTier.CURRENT && f.streaming && f.stableForMs >= AGGR_STABLE_MS -> AggrAction.START
    f.tier == AggrTier.NEXT && f.queued && f.appInForeground && !f.streaming -> AggrAction.START
    else -> AggrAction.NONE
}

/**
 * R195 起播稳定窗（纯函数）：距「最近一次不稳定事件」的时长。不稳定事件 =
 * 该文件新供流连接注册（seek 重开/moov 尾探针/R164b 预览带扫动）∨ 窗口抢占。
 * B 档无连接无抢占（推送即翻页落定）恒判稳定——由推送时机保证，不走本函数。
 */
internal fun aggrStableForMs(nowMs: Long, lastConnMs: Long, lastPreemptMs: Long): Long =
    nowMs - maxOf(lastConnMs, lastPreemptMs)

/**
 * R195 整载槽占用判定（纯函数，行为单测 AggressivePreloadPolicyTest）：
 * 全局唯一整载槽「从本文件视角看是否被别人占着」——
 *  - 有其他文件在载（issued）且自己没在载 → 占（发起方等待/持有方让位）；
 *  - B 档（无论自己是否在载）：A 档出现 START/RESTART 意向 → 占（未发的不抢槽
 *    ——让位后的空槽只归 A 档，防 B1让位→B2同拍抢槽→下拍再让位的乒乓，真机
 *    实锤 4 轮把 A 档挡 16s、起播首块饿死 8s）；
 *  - 其余（A 档自持/无人在载/A 档无意向且 B 未发）→ 空。
 */
internal fun aggrSlotOccupiedFor(
    tier: AggrTier,
    issuedSelf: Boolean,
    issuedOther: Boolean,
    currentWantsSlot: Boolean,
): Boolean = when {
    issuedOther && !issuedSelf -> true
    tier == AggrTier.NEXT && currentWantsSlot -> true
    else -> false
}

/**
 * R195 激进整载器（网关侧 1Hz 决策循环）。构造轻量（纯表）；[startLoop] 由
 * streamServer lazy 首次构建时调用（整载候选来自在播连接与队列推送，流服务器
 * 未起时循环无事可做）；网关 close() 调 [stop]（循环随网关会话终止）。
 */
internal class AggressivePreloadManager(private val gw: TdLibGateway) {

    /** tracked：fileId → 状态（A 档由在播快照同步进表，B 档由队列推送进表） */
    private val tracked = ConcurrentHashMap<Int, AggrFileState>()

    /** 最新推送的下一条目标队列（PreloadScheduler.schedule 覆写） */
    @Volatile
    private var nextTargets: List<Int> = emptyList()

    @Volatile
    private var nextTargetsAtMs: Long = 0L

    private var loopJob: Job? = null

    // ---------------- 对外触点 ----------------

    /**
     * B 档目标推送（TdGateway.setAggressivePreloadNextTargets → 这里）。
     * 覆写语义：新推送不含的旧目标=滑走作废（下拍 CANCEL_INVALIDATED）。
     */
    fun setNextTargets(nextFileIds: List<Int>) {
        nextTargets = nextFileIds.filter { it > 0 }.distinct()
        nextTargetsAtMs = SystemClock.elapsedRealtime()
    }

    /**
     * R166 抢占闸钩子（downloadPart 跳变分支调用，闸的 CancelDownloadFile 已把
     * 在途整载取消——本钩子只记账+trace）：被取消的若是我们发的整载即打
     * aggrPreempt(reason=seek)，稳定窗到点后由循环 RESTART 重发。
     * 抢占闸本体（shouldPreemptDownloadWindow/取消顺序）零改动。
     */
    fun notePreempt(fileId: Int) {
        val st = tracked[fileId] ?: return
        st.lastPreemptMs = SystemClock.elapsedRealtime()
        if (st.issued) {
            PlaybackTrace.log(
                "aggrPreempt", "f$fileId",
                "原因=seek（抢占闸取消在途整载重锚，稳定窗后重启）",
            )
            Log.i(
                TAG,
                "R195 整载被抢占：fileId=$fileId（seek 跳变重锚，${AGGR_STABLE_MS / 1000}s 稳定后重启）",
            )
        }
    }

    /** 启动决策循环（幂等；IO 1Hz） */
    fun startLoop() {
        if (loopJob?.isActive == true) return
        loopJob = gw.scope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { tick() }
                    .onFailure { Log.w(TAG, "整载决策轮失败（下拍再试）: ${it.message}") }
                delay(AGGR_TICK_MS)
            }
        }
    }

    /** 停循环+清表（网关 close() 调用；在途整载交 TDLib 自然收尾） */
    fun stop() {
        runCatching { loopJob?.cancel() }
        loopJob = null
        tracked.clear()
    }

    // ---------------- 决策循环 ----------------

    private suspend fun tick() {
        val enabled = runCatching { gw.appPrefs.aggressivePreload }.getOrDefault(true)
        val metered = NetworkMeteredBus.metered
        val streaming = TgStreamServer.streamingFilesSnapshot()

        // 1) 候选同步：新面孔在播→入表；在播条目重置停播计时；NEXT 开始供流=升档
        //    （无感接管）；A 档离场开始宽限计时；B 档队列打标
        for ((id, s) in streaming) {
            val st = tracked.getOrPut(id) { AggrFileState(AggrTier.CURRENT) }
            st.tier = AggrTier.CURRENT
            st.unstreamedSinceMs = 0L
        }
        for ((id, st) in tracked) {
            if (st.tier == AggrTier.CURRENT && id !in streaming && st.unstreamedSinceMs == 0L) {
                st.unstreamedSinceMs = SystemClock.elapsedRealtime()
            }
        }
        val queue = nextTargets
        val queuedAt = nextTargetsAtMs
        for (id in queue) {
            tracked.getOrPut(id) { AggrFileState(AggrTier.NEXT) }.queued = true
        }
        for ((id, st) in tracked) {
            if (st.tier == AggrTier.NEXT) st.queued = id in queue
        }

        if (tracked.isEmpty()) return

        // 2) GetFile 批查（幂等本地查询，1Hz/条便宜）：完成态/停滞/速率观测原料
        val files = HashMap<Int, TdApi.File?>(tracked.size)
        for (id in tracked.keys) {
            files[id] = runCatching { gw.sendReq(TdApi.GetFile(id)) }.getOrNull()
        }

        // 3) 两拍决策：先算 A 档的「想要槽」意向（不计槽占用——占槽的 B 档据此
        //    让位：占槽者自己看不见「他者 issued」，让位信号必须从 A 档意向侧送入）；
        //    B 档让位后的空槽**只归 A 档**——未发的 NEXT 条目在本拍也不得抢槽
        //    （否则 B1 让位→同拍 B2 起跑→下拍再让位的乒乓，真机实锤 4 轮：A 档
        //    被挡 16s、带宽被反复重锚/握手烧掉、起播首块饿死 8s）。槽判定收敛
        //    纯函数 [aggrSlotOccupiedFor]。
        val currentWantsSlot = tracked.entries.any { (id, st) ->
            st.tier == AggrTier.CURRENT && aggressivePreloadAction(
                factsOf(id, st, files[id], streaming, queuedAt, enabled, metered, slotOccupiedByOther = false),
            ) in AGGR_START_LIKE
        }
        for ((id, st) in tracked) {
            val issuedOther = tracked.entries.any { (oid, os) -> oid != id && os.issued }
            val slotOccupied = aggrSlotOccupiedFor(
                tier = st.tier,
                issuedSelf = st.issued,
                issuedOther = issuedOther,
                currentWantsSlot = currentWantsSlot,
            )
            val action = aggressivePreloadAction(
                factsOf(id, st, files[id], streaming, queuedAt, enabled, metered, slotOccupied),
            )
            runCatching { apply(id, st, files[id], action) }
        }
        // 让位效应：占槽的 B 档本拍被 CANCEL_TAKEOVER（已下字节保留），A 档下拍起跑
    }

    /** 拼某文件的决策输入（槽位由调用方两拍给出） */
    private fun factsOf(
        id: Int,
        st: AggrFileState,
        f: TdApi.File?,
        streaming: Map<Int, StreamingFileState>,
        queuedAt: Long,
        enabled: Boolean,
        metered: Boolean,
        slotOccupiedByOther: Boolean,
    ): AggrFileFacts {
        val now = SystemClock.elapsedRealtime()
        val s = streaming[id]
        return AggrFileFacts(
            tier = st.tier,
            enabled = enabled,
            metered = metered,
            streaming = (s?.conns?.get() ?: 0) > 0,
            unstreamedForMs = if (st.unstreamedSinceMs > 0) now - st.unstreamedSinceMs else 0L,
            stableForMs = if (st.tier == AggrTier.CURRENT) {
                aggrStableForMs(now, s?.lastConnMs ?: 0L, st.lastPreemptMs)
            } else {
                Long.MAX_VALUE // B 档由推送时机（翻页落定）保证稳定
            },
            completed = f?.local?.isDownloadingCompleted == true,
            inFallback = gw.fullDlFallbackFiles.containsKey(id),
            issued = st.issued,
            preemptedAfterIssue = st.issued && st.lastPreemptMs > st.issuedAtMs,
            stalledForMs = if (st.inactiveSinceMs > 0) now - st.inactiveSinceMs else 0L,
            queued = st.queued,
            queuedForMs = if (queuedAt > 0) now - queuedAt else 0L,
            slotOccupiedByOther = slotOccupiedByOther,
            appInForeground = AppForeground.inForeground,
        )
    }

    /** 执行动作（发/取消/摘牌 + trace + R196 登记） */
    private suspend fun apply(id: Int, st: AggrFileState, f: TdApi.File?, action: AggrAction) {
        val now = SystemClock.elapsedRealtime()
        when (action) {
            AggrAction.START, AggrAction.RESTART -> {
                val prio = if (st.tier == AggrTier.CURRENT) AGGR_PRIORITY_CURRENT else AGGR_PRIORITY_NEXT
                if (issueFullLoad(id, prio)) {
                    st.issued = true
                    st.issuedAtMs = now
                    st.inactiveSinceMs = 0L
                    st.startClockMs = now
                    st.startBytes = f?.local?.downloadedSize ?: 0L
                    st.totalBytes = f?.let { if (it.expectedSize > 0) it.expectedSize else it.size } ?: 0L
                    st.lastRateMs = 0L
                    st.lastRateBytes = st.startBytes
                    val tierLabel = if (st.tier == AggrTier.CURRENT) "当前" else "下一条"
                    val mode = if (action == AggrAction.RESTART) "重启" else "启动"
                    PlaybackTrace.log(
                        "aggrStart", "f$id",
                        "档位=$tierLabel $mode p=$prio 大小=${fmtKb(st.totalBytes)} 已下=${fmtKb(st.startBytes)}",
                    )
                    Log.i(TAG, "R195 整载${mode}：fileId=$id 档位=$tierLabel p=$prio")
                    // R196 契约：登记（幂等刷新 lastUse——接管/重启续命同口）
                    runCatching { gw.storageGovernor.registerFullLoad(id) }
                }
                // 发失败（取锁超时/客户端异常）：不置 issued，下拍重试
            }
            AggrAction.CANCEL_METERED, AggrAction.CANCEL_DISABLED, AggrAction.CANCEL_STOPPED,
            AggrAction.CANCEL_TAKEOVER, AggrAction.CANCEL_INVALIDATED,
            -> {
                val reason = when (action) {
                    AggrAction.CANCEL_METERED -> "metered（计量网络止损）"
                    AggrAction.CANCEL_DISABLED -> "开关关"
                    AggrAction.CANCEL_STOPPED -> "停播（供流归零超宽限）"
                    AggrAction.CANCEL_TAKEOVER -> "让位（当前条需要整载槽）"
                    else -> if (st.queued) "作废（队列过期）" else "作废（滑走）"
                }
                val tag = if (action == AggrAction.CANCEL_INVALIDATED) "aggrInvalidate" else "aggrPreempt"
                PlaybackTrace.log(tag, "f$id", "原因=$reason 已下=${fmtKb(f?.local?.downloadedSize ?: 0L)}")
                Log.i(TAG, "R195 整载取消：fileId=$id $reason")
                cancelFullLoad(id)
                st.issued = false
                st.inactiveSinceMs = 0L
                if (action == AggrAction.CANCEL_INVALIDATED) {
                    // R196 契约：预热作废撤销登记（完成的全载留在表里等上限淘汰）
                    runCatching { gw.storageGovernor.unregisterFullLoad(id) }
                    tracked.remove(id)
                } else if (action == AggrAction.CANCEL_STOPPED) {
                    tracked.remove(id)
                }
                // metered/disabled/takeover 保留 tracked：条件恢复后自动重发
            }
            AggrAction.DONE -> {
                val elapsed = (now - st.startClockMs).coerceAtLeast(1L)
                val got = (f?.local?.downloadedSize ?: 0L) - st.startBytes
                val avgKb = got / 1024L * 1000L / elapsed
                PlaybackTrace.log(
                    "aggrDone", "f$id",
                    "整载完成 耗时=${elapsed / 1000}s 均速=${avgKb}KB/s 总量=${fmtKb(st.totalBytes)}",
                )
                Log.i(TAG, "R195 整载完成：fileId=$id 耗时${elapsed / 1000}s 均速${avgKb}KB/s")
                tracked.remove(id)
            }
            AggrAction.DROP -> tracked.remove(id)
            AggrAction.NONE -> {
                // 观测采样（issued 且未完成）：aggrRate 5s 周期 + 停滞计时原料
                if (st.issued && f != null && !f.local.isDownloadingCompleted) {
                    val dl = f.local.downloadedSize
                    if (f.local.isDownloadingActive) {
                        st.inactiveSinceMs = 0L
                    } else if (st.inactiveSinceMs == 0L) {
                        st.inactiveSinceMs = now
                    }
                    if (st.lastRateMs == 0L) {
                        st.lastRateMs = now
                        st.lastRateBytes = dl
                    } else if (now - st.lastRateMs >= AGGR_RATE_PERIOD_MS) {
                        val rate = (dl - st.lastRateBytes) / 1024L * 1000L / (now - st.lastRateMs)
                        val pct = if (st.totalBytes > 0) dl * 100 / st.totalBytes else 0L
                        PlaybackTrace.log("aggrRate", "f$id", "近窗${rate}KB/s 已下=${fmtKb(dl)} 进度=$pct%")
                        Log.i(TAG, "R195 整载速率：fileId=$id ${rate}KB/s 进度$pct%")
                        st.lastRateMs = now
                        st.lastRateBytes = dl
                    }
                } else {
                    st.inactiveSinceMs = 0L
                }
            }
        }
    }

    // ---------------- TDLib 触点 ----------------

    /**
     * 发整载（R158 路径 DownloadFile(p, 0, 0, async)）。锁纪律（R76 同文件互杀
     * 收编口径）：走 videoDlMutex 与泵同步查询串行，但取锁用 **tryLock 轮询 +3s
     * 上限**——两不误：①泵查询持锁可达 20s（PUMP_TIMEOUT_MS），决策循环不能被
     * 单文件挡死（超时=本拍放弃下拍再试）；②刻意不用「withTimeout{lock()}」——
     * kotlinx 的超时取消与获锁完成存在竞速窗，取消恰好落在获锁之后会漏持锁，
     * 同文件锁一旦泄漏泵的 withLock 永挂（踩 R166 红线），tryLock 非挂起无此窗。
     * 发失败静默（调用方靠返回值决定是否置 issued）。
     */
    private suspend fun issueFullLoad(id: Int, priority: Int): Boolean {
        val mutex = gw.videoDlMutex(id)
        val deadline = SystemClock.elapsedRealtime() + AGGR_LOCK_TIMEOUT_MS
        while (!mutex.tryLock()) {
            if (SystemClock.elapsedRealtime() >= deadline) return false
            delay(AGGR_LOCK_POLL_MS)
        }
        return try {
            runCatching { gw.sendReq(TdApi.DownloadFile(id, priority, 0L, 0L, false)) }.isSuccess
        } finally {
            runCatching { mutex.unlock() }
        }
    }

    /**
     * 取消整载（CancelDownloadFile(id, false)，已落盘字节保留）。**刻意不走锁**
     * （与发的锁纪律不对称，理由）：取消触发（计量翻转/开关关/停播/作废/让位）
     * 都是用户/网络级事件，要秒级止损——等泵查询的锁（可达 20s）会把计量保护
     * 拖成半分钟级。偶发恰逢泵同步查询在途：该查询被一并取消=泵按 500ms 退避
     * 自愈重试（pumpRetry trace 可见），代价一次重试；R166 红线的 seek 抢占
     * 不受影响——抢占闸在泵侧锁内自决策，本取消与其无锁序关系。
     */
    private suspend fun cancelFullLoad(id: Int) {
        runCatching { gw.sendReq(TdApi.CancelDownloadFile(id, false)) }
    }

    private companion object {
        const val TAG = "AggrPreload"

        /** 决策循环周期：1s（开关/计量翻转/稳定窗的生效时延上界） */
        const val AGGR_TICK_MS = 1_000L

        /** 发整载取锁上限：不挡决策循环（泵持锁可达 20s）；超时本拍放弃下拍再试 */
        const val AGGR_LOCK_TIMEOUT_MS = 3_000L

        /** 发整载取锁轮询步长（tryLock 非挂起，轮询间隔给泵留吞吐） */
        const val AGGR_LOCK_POLL_MS = 50L

        /** 速率观测周期（aggrRate 行） */
        const val AGGR_RATE_PERIOD_MS = 5_000L
    }
}

/** 字节数格式化（KB/MB 口径，trace 行宽可控；本文件族私有） */
internal fun fmtKb(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1fMB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "${bytes / 1024L}KB"
    else -> "${bytes}B"
}
