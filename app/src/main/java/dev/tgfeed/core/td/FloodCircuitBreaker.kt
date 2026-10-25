package dev.tgfeed.core.td

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 429 熔断表（R44 限流收敛治理 + R58 半开态）：按 API 方法族（如 [FAMILY_SEARCH_PUBLIC_CHAT]）
 * 记录"静默到何时"（silencedUntil，epoch 毫秒时刻）。任何泳道撞到该方法族的 429 后，
 * 全 app 该方法族立即静默到服务端 retry-after 结束——各泳道不再轮番撞墙。
 *
 * R58 半开状态机（治"静默到期瞬间所有泳道同时开闸"的自我冲击：EntityResolver 数千条积压、
 * resolveAnswerTitles、resolvePendingChannels 一起放量极易立刻再被封）：
 * ```
 * 闭合(CLOSED) ──429 上报──▶ 熔断(OPEN，silencedUntil=now+retryAfter)
 * 熔断(OPEN) ──静默到期（过闸/落盘时懒发现，或启动读盘发现）──▶ 半开(HALF_OPEN)
 * 半开(HALF_OPEN) ──试探成功累计 [PROBE_SUCCESS_TO_CLOSE] 发──▶ 闭合(CLOSED，清除半开字段)
 * 半开(HALF_OPEN) ──试探期再撞 429──▶ 熔断(OPEN，retry-after 照旧吃，试探计数清零)
 * ```
 * 半开放行规则：每方法族按"距上发试探 ≥[PROBE_INTERVAL_MS]"逐发放行（全局单发间隔闸），
 * 间隔未到的过闸请求一律拒发；试探请求的成功/429 由过闸点回报本表推进状态机
 *（非 429 的瞬态失败不清零也不推进，留给下一发试探）。
 *
 * R379 静默限流防线（第三层·最后一道机械防线）：TG 隐性限流（silent flood）**不发
 * 429 只挂起**——R44 只吃 429 的口径下静默期间本表全程不开闸，app 继续把请求烧进
 * 死车道（每发 12s 超时=账号持续承压=用户封号焦虑本体）。本批三件叠加（R58 状态机
 * 原样复用零另起）：
 *  1. **静默打击上报 [silentStrike]**：withTdTimeout 超时分支喂打击信号（与 [report429]
 *     并行不替代）。判别线：单次超时=网络毛刺不开闸，同族 [SILENT_STRIKE_WINDOW_MS]
 *     滑窗内 ≥[SILENT_STRIKE_THRESHOLD] 次超时才开闸（防误伤）；
 *  2. **退避梯**（无 retry-after 的替代）：开闸时长按该族连续开闸次数
 *     （[SilentState.consecutiveOpens]，静默与 429 开闸都计）升级 30min→2h→8h→24h
 *     封顶（[silentBackoffSec]）；半开试探攒满 [PROBE_SUCCESS_TO_CLOSE] 发成功闭合时
 *     清零归一档；
 *  3. **静默前置闸 [silentGuard]**（withTdTimeout 发送前消费，与 [gate] 分工）：OPEN
 *     拒发零请求零承压；半开=只放行不推进（间隔闸/成功计数归统一发送口——嵌套调用
 *     不二次吃间隔、不双扣闭合计数）；搜索族 scope 另过配速闸（全域最小间隔
 *     [SEARCH_SEND_MIN_INTERVAL_MS] + 绝对小时帽 [SEARCH_SCOPE_HOURLY_CAP]，双族
 *     共享账号桶合并计，USER_ACTION 不豁免——优先=排序优先非容量豁免）。
 *
 * 持久化（本类的核心价值）：flood_breaker.json 落 filesDir 根目录，构造时读盘、
 * 上报时落盘（tmp+rename 原子写，与 AnswerStore/EntityResolver 全同构）。
 * 封号级限流（retry after 上万秒）横跨多次进程生命周期，而既有 FloodBudget 滑窗预算
 * 全是进程内存态、重启清零——重启即重置的记性是假防守，本表让静默与半开进度跨进程生效。
 *
 * 同文件顺带持久化答案区标题解析的 400 死信用户名（answerTitleDead 段）：
 * 稳定终态（用户名失效/不可见）重试只是白烧请求，重启不该重烧。
 *
 * 文件格式（halfOpen 段为 R58 新增；silent/pacing 段为 R379 新增；旧格式缺段，
 * 读盘按缺省兼容）：
 * ```json
 * {"families": {"SearchPublicChat": 1730000000000},
 *  "halfOpen": {"SearchPublicChat": {"halfOpenSince": 1730000100000,
 *               "lastProbeAt": 1730000102000, "probeRemaining": 2}},
 *  "silent": {"SearchPublicChat": {"consecutiveOpens": 2, "strikes": [1730000000000]}},
 *  "pacing": {"SearchAccountBucket": {"stamps": [1730000000000, 1730000001000]}},
 *  "answerTitleDead": ["name1", "name2"]}
 * ```
 */
class FloodCircuitBreaker(
    private val dir: File,
    /** 时钟注入（R379：滑窗/退避梯/配速窗的确定性单测；默认墙钟） */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    init {
        // R379 进程级挂载：withTdTimeout（GatewaySendReq 顶层单源）拿不到网关实例引用——
        // 构造即自挂（网关重建=新实例覆盖自挂，旧表随旧网关退役；演示模式不构造本表=
        // hub 为 null，静默防线空转照发，不拦 Mock 车道）
        processHub = this
    }

    /** 半开窗口进度（持久化）：与 families 条目互斥——在 families 里=熔断中，在这里=半开试探中 */
    private class HalfOpen(
        /** 进入半开的时刻（epoch 毫秒，诊断用） */
        val halfOpenSince: Long,
        /** 上一发试探的放行时刻（epoch 毫秒，0=尚未放过）；间隔闸：距它 ≥[PROBE_INTERVAL_MS] 才再放行 */
        var lastProbeAt: Long,
        /** 距闭合还差的成功试探发数：成功一发 -1，归零闭合；试探期撞 429 整段废弃重新熔断 */
        var probeRemaining: Int,
    )

    /** R379 静默防线状态（持久化 silent 段，按族）：打击滑窗 + 退避梯档位 */
    private class SilentState(
        /** 同族超时打击时间戳（epoch 毫秒，[SILENT_STRIKE_WINDOW_MS] 滑窗内计数） */
        val strikes: ArrayList<Long> = ArrayList(),
        /** 连续开闸次数（静默与 429 开闸都累计；成功闭合清零归一档）——退避梯档位 */
        var consecutiveOpens: Int = 0,
    )

    /** R379 搜索族配速状态（持久化 pacing 段，按 scope——双搜索族共享一个账号桶） */
    private class PaceState(
        /** 近 1h 真发送时间戳（绝对小时帽窗口 + 最小间隔判据） */
        val stamps: ArrayList<Long> = ArrayList(),
    )

    private class State(
        /** 方法族 → 静默截止时刻（epoch 毫秒）；过期条目在过闸/落盘时转半开 */
        val families: HashMap<String, Long> = HashMap(),
        /** 方法族 → 半开窗口进度（R58）；读盘兼容旧格式：缺该段 = 无半开 */
        val halfOpen: HashMap<String, HalfOpen> = HashMap(),
        /** 方法族 → R379 静默防线状态；读盘兼容旧格式：缺段 = 无静默史 */
        val silent: HashMap<String, SilentState> = HashMap(),
        /** R379 搜索族配速 scope → 发送时间戳（绝对帽+最小间隔共用一份账） */
        val pacing: HashMap<String, PaceState> = HashMap(),
        /** 答案区标题解析 400 死信用户名（小写，插入序保序供容量收口） */
        val answerTitleDead: LinkedHashSet<String> = LinkedHashSet(),
    )

    /** 过闸判定（R58 三态）：PASS=闭合正常放行；PROBE=半开试探放行（成败必须回报本表）；REJECT=熔断中/半开间隔未到，拒发 */
    enum class GateDecision { PASS, PROBE, REJECT }

    /** 熔断状态三态（节奏控制查询用，纯读不改状态）：CLOSED=闭合；HALF_OPEN=半开试探期；OPEN=熔断静默中 */
    enum class BreakerState { CLOSED, HALF_OPEN, OPEN }

    private val file = File(dir, FILE_NAME)

    /** @Volatile 内存缓存（唯一事实源）；null = 尚未加载（首次访问时 lazy 读盘） */
    @Volatile
    private var stateCache: State? = null

    /** 距上次落盘以来的死信变更次数（flush 节流；熔断上报是即写，不走阈值） */
    private var dirtyCount = 0

    // ---------------- 熔断闸 ----------------

    /** 该方法族当前是否处于 429 静默期（纯读，过期条目留给读盘/落盘时清理） */
    fun isSilenced(family: String): Boolean =
        (synchronized(this) { loadLocked().families[family] } ?: 0L) > clock()

    /** 该方法族剩余静默秒数（不在静默期 = 0；半开期也是 0——半开不属于静默） */
    fun remainingSec(family: String): Long {
        val remain = (synchronized(this) { loadLocked().families[family] } ?: 0L) - clock()
        return if (remain > 0) remain / 1000 else 0L
    }

    // ---------------- R58 半开状态机 ----------------

    /**
     * 过闸判定（R58 核心入口，替代"isSilenced 二元"）：
     *  - 熔断静默中 → [GateDecision.REJECT]（一发不放）；
     *  - 半开试探期 → 距上发试探 ≥[PROBE_INTERVAL_MS] 才放行一发 [GateDecision.PROBE]
     *   （试探请求的成败必须由过闸点回报：成功 [reportSuccess]、429 [report429]），
     *    间隔未到 REJECT；
     *  - 闭合 → [GateDecision.PASS]。
     * 静默到期在此懒发现转半开（首个过闸请求即成为第一发试探，零空转）。
     */
    @Synchronized
    fun gate(family: String): GateDecision {
        val st = loadLocked()
        val now = clock()
        transitionExpiredLocked(st, now)
        if ((st.families[family] ?: 0L) > now) return GateDecision.REJECT
        val ho = st.halfOpen[family] ?: return GateDecision.PASS
        if (now - ho.lastProbeAt < PROBE_INTERVAL_MS) return GateDecision.REJECT
        ho.lastProbeAt = now
        flushLocked() // 试探是低频关键事件：放行时刻落盘，进程暴死也不重置间隔闸
        Log.i(TAG, "半开试探放行：$family（距闭合还差 ${ho.probeRemaining} 发成功）")
        return GateDecision.PROBE
    }

    /**
     * 试探成功回报：半开计数推进一发，攒满 [PROBE_SUCCESS_TO_CLOSE] 发即闭合（全开）。
     * 非半开期调用空转（调用方只在 gate 放行 PROBE 后才需要回报）。
     */
    @Synchronized
    fun reportSuccess(family: String) {
        val st = loadLocked()
        val ho = st.halfOpen[family] ?: return
        ho.probeRemaining--
        if (ho.probeRemaining <= 0) {
            st.halfOpen.remove(family)
            // R379：成功闭合清零归一档——退避梯重置（下次再撞从 30min 档起，
            // 不带着历史档位惩罚已恢复的族）
            st.silent[family]?.let { it.consecutiveOpens = 0 }
            flushLocked()
            Log.i(TAG, "半开闭合：$family 试探全部成功，泳道恢复正常（全开）——R379 退避梯清零归一档")
        } else {
            flushLocked()
            Log.i(TAG, "半开试探成功：$family（再成功 ${ho.probeRemaining} 发即闭合）")
        }
    }

    /**
     * 熔断状态三态查询（节奏控制用，纯读不触发状态迁移）：
     * 已过期但尚未被 gate/落盘懒发现的静默条目也按半开报（语义一致：下一发过闸即试探）。
     */
    fun stateOf(family: String): BreakerState {
        val now = clock()
        val st = synchronized(this) { loadLocked() }
        val until = st.families[family] ?: 0L
        return when {
            until > now -> BreakerState.OPEN
            until > 0L || st.halfOpen.containsKey(family) -> BreakerState.HALF_OPEN
            else -> BreakerState.CLOSED
        }
    }

    /**
     * 到期静默条目转半开（持锁调用；gate/落盘/读盘三处共用，保证"到期必进半开"
     * 不流失——即使到期后无人过闸，下次落盘也把它转成半开写出去）。
     */
    private fun transitionExpiredLocked(st: State, now: Long) {
        val it = st.families.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value > now) continue
            it.remove()
            if (st.halfOpen.containsKey(e.key)) continue // 半开已存在（脏数据防御）：不重置试探进度
            st.halfOpen[e.key] = HalfOpen(halfOpenSince = now, lastProbeAt = 0L, probeRemaining = PROBE_SUCCESS_TO_CLOSE)
            Log.i(
                TAG,
                "半开进入：${e.key} 静默期结束，限 $PROBE_SUCCESS_TO_CLOSE 发试探" +
                    "（间隔 ≥${PROBE_INTERVAL_MS / 1000}s），全部成功才闭合",
            )
        }
    }

    /**
     * 上报一次 429：静默期取 max 不缩短已有静默（多条泳道并发撞墙时，
     * 后到的小 retry-after 不得把先到的大封禁冲短）。服务端没给 retry-after
     * 时按 [DEFAULT_RETRY_AFTER_SEC] 兜底静默（429 不报等待时长也不能照打）。
     * R58：半开试探期撞 429 = 试探失败，半开进度整段废弃、重新熔断（retry-after 照旧吃）。
     * 熔断状态立即落盘（低频关键事件，不等阈值）。
     */
    @Synchronized
    fun report429(family: String, retryAfterSec: Long) {
        val waitSec = if (retryAfterSec > 0) retryAfterSec else DEFAULT_RETRY_AFTER_SEC
        val st = loadLocked()
        val now = clock()
        val cur = st.families[family] ?: 0L
        if (cur > now + waitSec * 1000L) {
            Log.d(TAG, "429 上报：$family 已有更长静默（剩 ${(cur - now) / 1000}s），不缩短")
            return
        }
        st.families[family] = now + waitSec * 1000L
        // R379 退避梯档位记账：429 开闸也累计连续开闸次数（「连续开闸」字面口径+同族
        // 热度跨成因累计更保守）；本行只记档位，429 等待时长仍 100% 服务端 retry-after
        //（429 路径既有语义零改负锚不受影响）
        st.silent.getOrPut(family) { SilentState() }.consecutiveOpens += 1
        val wasHalfOpen = st.halfOpen.remove(family) != null
        flushLocked()
        if (wasHalfOpen) {
            Log.w(TAG, "半开试探撞 429：$family 重新熔断 ${waitSec}s（试探计数清零，retry-after 照旧吃）")
        } else {
            Log.w(TAG, "429 熔断：$family 全 app 静默 ${waitSec}s（进程重启仍有效）")
        }
    }

    // ---------------- R379 静默限流防线（第三层·最后一道机械防线） ----------------

    /**
     * 静默前置闸（[withTdTimeout] 发送前消费，与 [gate] 分工——gate 是统一发送口的
     * R58 状态机入口，本口是超时包装的拒发层）：
     *  - 熔断 OPEN → [GateDecision.REJECT]（零请求零承压）：拒发行「限流静默中
     *    family=… 余 H:mm」在本口内落（探针：拒发各一行）；
     *  - 半开试探期 → [GateDecision.PROBE]：**只放行不推进**——R58 的试探间隔闸与
     *    成功计数归统一发送口（[gate]/[reportSuccess] 持有；嵌套调用不二次吃间隔、
     *    不双扣闭合计数）。试探成败回报三源分工：成功=统一发送口既有回报、429=
     *    [report429]、超时=[silentStrike]（超时包装的超时分支喂）——零重叠；
     *  - 闭合 → [GateDecision.PASS]；
     *  - 搜索族另过配速闸（[pacePassLocked]：全域最小间隔+绝对小时帽，双族共享
     *    账号桶合并计），拒发各落诚实一行；放行即记账（每发恰一次——真发送口唯一
     *    what 前缀映射保证，见 GatewaySendReq 的 silentFloodFamilyOf）。
     * 本口只拒不发+配速记账，429 既有路径语义零改。
     */
    @Synchronized
    fun silentGuard(family: String): GateDecision {
        val st = loadLocked()
        val now = clock()
        transitionExpiredLocked(st, now)
        val until = st.families[family] ?: 0L
        if (until > now) {
            val remainSec = (until - now) / 1000L
            Log.w(
                TAG,
                "限流静默中 family=$family 余 ${formatHhMm(remainSec)}（拒发零请求零承压——静默限流不发 429，熔断表替账号挡请求）",
            )
            return GateDecision.REJECT
        }
        val scope = searchPaceScopeOf(family)
        if (scope != null && !pacePassLocked(st, scope, now)) return GateDecision.REJECT
        return if (st.halfOpen.containsKey(family)) GateDecision.PROBE else GateDecision.PASS
    }

    /**
     * 静默打击上报（[withTdTimeout] 超时分支消费，与 [report429] 并行不替代）。
     * 判别线（防误伤）：单次超时=网络毛刺不开闸；同族 [SILENT_STRIKE_WINDOW_MS]
     * 滑窗内 ≥[SILENT_STRIKE_THRESHOLD] 次超时=静默限流在场，开闸走退避梯
     * [silentBackoffSec]（无 retry-after 的替代——静默限流不发自报时长）。
     * 静默取 max 不缩短已有静默（与 [report429] 同口径）；滑窗/档位落盘（跨进程：
     * 重启前 2 次打击+重启后 1 次=3 次=开闸——R44「重启即重置的记性是假防守」判词）。
     * @return 本次生效的静默秒数（0=滑窗未满未开闸）
     */
    @Synchronized
    fun silentStrike(family: String): Long {
        val st = loadLocked()
        val now = clock()
        val s = st.silent.getOrPut(family) { SilentState() }
        s.strikes.removeAll { it <= now - SILENT_STRIKE_WINDOW_MS }
        s.strikes.add(now)
        val count = s.strikes.size
        if (!silentThresholdMet(s.strikes, now)) {
            Log.d(
                TAG,
                "静默打击计数：$family 滑窗内第 ${count}/${SILENT_STRIKE_THRESHOLD} 次超时" +
                    "（单次=网络毛刺口径，不足开闸）",
            )
            flushLocked()
            return 0L
        }
        s.consecutiveOpens += 1
        val waitSec = silentBackoffSec(s.consecutiveOpens)
        val until = now + waitSec * 1000L
        val cur = st.families[family] ?: 0L
        if (cur >= until) {
            Log.d(TAG, "静默开闸：$family 已有更长静默（剩 ${(cur - now) / 1000}s），不缩短（与 429 同口径）")
            flushLocked()
            return (cur - now) / 1000L
        }
        st.families[family] = until
        st.halfOpen.remove(family) // 静默开闸废弃半开进度（与 429 重新熔断同口径）
        flushLocked()
        Log.w(
            TAG,
            "静默限流开闸：$family 连续第 ${s.consecutiveOpens} 次开闸，退避 ${formatHhMm(waitSec)}" +
                "（滑窗内 ${count} 次超时实证；静默限流无 retry-after——退避梯替代，到期半开单发试探）",
        )
        return waitSec
    }

    /**
     * 搜索族配速闸（持锁）：[SEARCH_SEND_MIN_INTERVAL_MS] 全域最小间隔（任意两发
     * ≥1s——杜绝 15 发/13ms 突发形态，R372 10:59 实锚）+ [SEARCH_SCOPE_HOURLY_CAP]
     * 绝对小时帽（双族共享账号桶合并计，USER_ACTION 不豁免）。拒发各落一行（探针：
     * 拒发各一行·脱敏纯计数）；放行即记账落盘（跨进程整形）。
     * 注记（挂账）：本闸在统一发送口的账本记账**之后**（GatewaySendReq 写域边界）——
     * 配速拒发时该发已烧 1 账本令牌（罕见态：亚秒双发/帽满；R378 域记账顺序正治）。
     */
    private fun pacePassLocked(st: State, scope: String, now: Long): Boolean {
        val pace = st.pacing.getOrPut(scope) { PaceState() }
        pace.stamps.removeAll { it <= now - SEARCH_PACE_WINDOW_MS }
        if (pace.stamps.size >= SEARCH_SCOPE_HOURLY_CAP) {
            Log.w(
                TAG,
                "搜索族绝对小时帽拒发：近 1h 已发 ${pace.stamps.size} 发 ≥$SEARCH_SCOPE_HOURLY_CAP" +
                    "（scope=$scope——最后一道机械防线，USER_ACTION 不豁免）",
            )
            return false
        }
        val lastAt = pace.stamps.maxOrNull() ?: 0L
        if (lastAt > 0L && now - lastAt < SEARCH_SEND_MIN_INTERVAL_MS) {
            Log.w(
                TAG,
                "搜索族最小间隔闸拒发：距上发 ${now - lastAt}ms < ${SEARCH_SEND_MIN_INTERVAL_MS}ms" +
                    "（scope=$scope，杜绝突发形态；本发不发按既有失败语义回落）",
            )
            return false
        }
        pace.stamps.add(now)
        flushLocked()
        return true
    }

    // ---------------- 答案区标题死信 ----------------

    /** 答案区标题解析死信判定（400 稳定终态的用户名，重启不重烧） */
    fun isAnswerTitleDead(username: String): Boolean =
        synchronized(this) { loadLocked().answerTitleDead.contains(username) }

    /** 记入答案区标题死信（幂等）；达变更阈值自动落盘，进程退出前由网关 close() 兜底 flush */
    fun markAnswerTitleDead(username: String) {
        val name = username.trim().lowercase()
        if (name.isEmpty()) return
        synchronized(this) {
            val st = loadLocked()
            if (!st.answerTitleDead.add(name)) return
            // 容量收口：超硬顶按插入序掐最老（被驱逐的最坏后果是重试一次再死）
            while (st.answerTitleDead.size > MAX_ANSWER_TITLE_DEAD) {
                st.answerTitleDead.remove(st.answerTitleDead.first())
            }
            dirtyCount++
            if (dirtyCount >= FLUSH_AFTER_MUTATIONS) flushLocked()
        }
    }

    /** 死信累计条数（诊断日志用） */
    fun answerTitleDeadCount(): Int = synchronized(this) { loadLocked().answerTitleDead.size }

    // ---------------- 落盘 ----------------

    @Synchronized
    private fun loadLocked(): State {
        stateCache?.let { return it }
        val st = State()
        runCatching {
            if (!file.isFile) return@runCatching
            val root = JSONObject(file.readText())
            val now = clock()
            root.optJSONObject("families")?.let { f ->
                for (k in f.keys()) {
                    val until = f.optLong(k)
                    // R58：过期静默不再直接丢弃，转半开限量试探（防到期瞬间各泳道同时开闸）；
                    // 迁移在读出后统一做（transitionExpiredLocked）
                    if (until > 0L) st.families[k] = until
                }
            }
            // R58 半开段（旧格式无此段 = 无半开，天然兼容）；与未过期熔断条目冲突的按熔断为准丢弃
            root.optJSONObject("halfOpen")?.let { h ->
                for (k in h.keys()) {
                    val o = h.optJSONObject(k) ?: continue
                    if ((st.families[k] ?: 0L) > now) continue // 与未过期熔断冲突（脏数据）：以熔断为准
                    val remaining = o.optInt("probeRemaining", PROBE_SUCCESS_TO_CLOSE)
                    if (remaining <= 0) continue // 脏数据（本该归零即闭合却落了盘）：不恢复，按闭合处理
                    st.halfOpen[k] = HalfOpen(
                        halfOpenSince = o.optLong("halfOpenSince", now),
                        lastProbeAt = o.optLong("lastProbeAt", 0L),
                        probeRemaining = remaining,
                    )
                }
            }
            // R379 silent 段（旧格式无此段 = 无静默史，天然兼容）
            root.optJSONObject("silent")?.let { s ->
                for (k in s.keys()) {
                    val o = s.optJSONObject(k) ?: continue
                    val sil = SilentState()
                    sil.consecutiveOpens = o.optInt("consecutiveOpens", 0)
                    o.optJSONArray("strikes")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val ts = arr.optLong(i, 0L)
                            if (ts > 0L) sil.strikes.add(ts)
                        }
                    }
                    if (sil.consecutiveOpens > 0 || sil.strikes.isNotEmpty()) st.silent[k] = sil
                }
            }
            // R379 pacing 段（旧格式无此段 = 无配速史，天然兼容）
            root.optJSONObject("pacing")?.let { p ->
                for (k in p.keys()) {
                    val o = p.optJSONObject(k) ?: continue
                    val pace = PaceState()
                    o.optJSONArray("stamps")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val ts = arr.optLong(i, 0L)
                            if (ts > 0L) pace.stamps.add(ts)
                        }
                    }
                    if (pace.stamps.isNotEmpty()) st.pacing[k] = pace
                }
            }
            root.optJSONArray("answerTitleDead")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val n = arr.optString(i).trim().lowercase()
                    if (n.isNotEmpty()) st.answerTitleDead += n
                }
            }
        }.onFailure { Log.w(TAG, "flood_breaker.json 读取失败（按空表重建）: ${it.message}") }
        stateCache = st
        val now = clock()
        transitionExpiredLocked(st, now) // 到期静默转半开（含"上次熔断在进程外到期"的现场）
        if (st.families.isNotEmpty()) {
            // 重启恢复可见性：logcat 一眼确认静默跨进程生效（验证要点）
            Log.i(
                TAG,
                "熔断表已从磁盘恢复：${st.families.entries.joinToString { "${it.key} 剩 ${(it.value - now) / 1000}s" }}",
            )
        }
        if (st.halfOpen.isNotEmpty()) {
            // 半开恢复可见性：重启续上试探进度（probeRemaining 不重置，间隔闸从 0 起算立即可放第一发）
            Log.i(
                TAG,
                "半开表已从磁盘恢复：${st.halfOpen.entries.joinToString { "${it.key} 待成功 ${it.value.probeRemaining} 发" }}",
            )
        }
        return st
    }

    /** 立即落盘（进程退出前由网关 close() 兜底调用）；从未加载/变更时空转（绝不凭空建文件） */
    @Synchronized
    fun flush() {
        if (stateCache == null) return
        flushLocked()
    }

    /** 持锁落盘：到期静默转半开（不直接丢弃）+ 全量序列化 + tmp+rename 原子写（AnswerStore 同口径） */
    private fun flushLocked() {
        val st = stateCache ?: return
        runCatching {
            val now = clock()
            transitionExpiredLocked(st, now) // R58：到期静默转半开一起落盘（到期必进半开不流失）
            val root = JSONObject()
            val families = JSONObject()
            for ((k, v) in st.families) families.put(k, v)
            root.put("families", families)
            val half = JSONObject()
            for ((k, v) in st.halfOpen) {
                half.put(
                    k,
                    JSONObject()
                        .put("halfOpenSince", v.halfOpenSince)
                        .put("lastProbeAt", v.lastProbeAt)
                        .put("probeRemaining", v.probeRemaining),
                )
            }
            root.put("halfOpen", half)
            // R379：静默防线（滑窗打击+退避梯档位）与搜索族配速（发送时间戳）落盘——
            // 打击计数/档位/整形窗全部跨进程
            val silent = JSONObject()
            for ((k, v) in st.silent) {
                val arr = JSONArray()
                v.strikes.forEach { arr.put(it) }
                silent.put(k, JSONObject().put("consecutiveOpens", v.consecutiveOpens).put("strikes", arr))
            }
            root.put("silent", silent)
            val pacing = JSONObject()
            for ((k, v) in st.pacing) {
                val arr = JSONArray()
                v.stamps.forEach { arr.put(it) }
                pacing.put(k, JSONObject().put("stamps", arr))
            }
            root.put("pacing", pacing)
            val dead = JSONArray()
            st.answerTitleDead.forEach { dead.put(it) }
            root.put("answerTitleDead", dead)
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText()) // rename 失败兜底（跨分区/占用）：拷内容后删临时文件
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "flood_breaker.json 落盘失败（下轮重试）: ${it.message}") }
        dirtyCount = 0
    }

    companion object {
        private const val TAG = "FloodCircuitBreaker"

        /** R379 进程级挂载点（@Volatile，构造即自挂）：withTdTimeout 过闸前置/静默打击上报的通道 */
        @Volatile
        private var processHub: FloodCircuitBreaker? = null

        /** 当前进程挂载的熔断表（null=未构造，演示模式等——静默防线空转照发不拦 Mock 车道） */
        @JvmStatic
        fun hub(): FloodCircuitBreaker? = processHub

        /** 落盘文件名（filesDir 根目录，与 answer_store.json / entity_resolve.json 同级） */
        private const val FILE_NAME = "flood_breaker.json"

        /** 方法族名：SearchPublicChat（公开用户名解析泳道——审计实证的封号级限流泳道） */
        const val FAMILY_SEARCH_PUBLIC_CHAT = "SearchPublicChat"

        /**
         * 方法族名：SearchChatsOnServer（R184 服务端搜索泳道）。事故实证 SearchPublicChat
         * 被账号级 429 封死期 SearchChatsOnServer 候选归零——两族至少共享账号桶的邻位，
         * 离线无法证伪「不同桶」，保守按同桶处理：任一族撞 429 双族同静默
         * （reportAccountSearch429 统一入口），宁可少搜不可续封（TG 洪水惩罚累进）。
         */
        const val FAMILY_SEARCH_CHATS_ON_SERVER = "SearchChatsOnServer"

        /** 方法族名：GetChatHistory（R46：深度索引旧路/头部刷新/加深/采样锚点所在的
         *  历史拉取泳道——撞 429 后全 app 该族静默，熔断期爬取整轮跳过不再轮番撞墙） */
        const val FAMILY_GET_CHAT_HISTORY = "GetChatHistory"

        /** 服务端没给 retry-after 时的兜底静默时长（秒）：429 不报等待时长也不能照打 */
        private const val DEFAULT_RETRY_AFTER_SEC = 300L

        /** 半开闭合所需的成功试探发数（R58）：少而可信——全部成功才认定服务端真的放行了 */
        private const val PROBE_SUCCESS_TO_CLOSE = 3

        /** 半开试探最小放行间隔（毫秒）：每方法族全局单发间隔闸，防试探期并发泳道瞬时打满 */
        private const val PROBE_INTERVAL_MS = 2_000L

        /** 答案区标题死信硬顶，超出按插入序掐最老 */
        private const val MAX_ANSWER_TITLE_DEAD = 5_000

        /** 死信变更多少次自动落盘（与 AnswerStore 同值口径：低频动作，阈值小、落盘勤） */
        private const val FLUSH_AFTER_MUTATIONS = 10

        // ---------------- R379 静默限流防线常量（单源） ----------------

        /** 静默打击滑窗（毫秒）：同族超时 ≥[SILENT_STRIKE_THRESHOLD] 次 within 10min 才开闸
         *  （判别线：单次超时=网络毛刺不开闸——防误伤；跨进程持久，重启前 2 次+重启后
         *  1 次=3 次同样开闸） */
        const val SILENT_STRIKE_WINDOW_MS = 10 * 60_000L

        /** 静默打击开闸阈值（滑窗内超时次数） */
        const val SILENT_STRIKE_THRESHOLD = 3

        /** 搜索族全域最小发送间隔（毫秒）：任意两发 ≥1s——杜绝 15 发/13ms 突发形态
         *  （R372 10:59 实锚的整形闸；人因节奏秒级不受影响，USER_ACTION 一样过闸） */
        const val SEARCH_SEND_MIN_INTERVAL_MS = 1_000L

        /** 搜索族绝对小时帽（次/小时，双族共享账号桶合并计）：现量级实勘=账号桶安全量级
         *  12（SearchQuotaLedger BASE_TOTAL_PER_HOUR 同值锚）；独立于账本——账本重构/
         *  旁路新口都不破帽（最后一道机械防线）；USER_ACTION 不豁免（优先=排序优先
         *  非容量豁免）；§九零绕过（收紧不是放宽：帽 ≤ 账本基数，等值不放宽） */
        const val SEARCH_SCOPE_HOURLY_CAP = 12

        /** 配速滑窗（毫秒）：绝对小时帽的计数窗（1h） */
        private const val SEARCH_PACE_WINDOW_MS = 3_600_000L

        /** 搜索族配速 scope 键：SearchPublicChat+SearchChatsOnServer 合并计（账号桶同桶口径） */
        private const val SEARCH_PACE_SCOPE = "SearchAccountBucket"

        /** 搜索族 → 配速 scope（null=非搜索族不配速）；新搜索族真发送口入账时同步扩本表 */
        private fun searchPaceScopeOf(family: String): String? =
            if (family == FAMILY_SEARCH_PUBLIC_CHAT || family == FAMILY_SEARCH_CHATS_ON_SERVER) SEARCH_PACE_SCOPE else null

        /** 静默退避梯（纯函数，无 retry-after 的替代）：连续开闸次数 → 静默秒数
         *  （1→30min、2→2h、3→8h、≥4→24h 封顶；成功闭合清零归一档） */
        fun silentBackoffSec(consecutiveOpens: Int): Long = when {
            consecutiveOpens <= 1 -> 30 * 60L
            consecutiveOpens == 2 -> 2 * 3_600L
            consecutiveOpens == 3 -> 8 * 3_600L
            else -> 24 * 3_600L
        }

        /** 静默开闸阈值判定（纯函数）：滑窗内（[SILENT_STRIKE_WINDOW_MS]）超时次数 ≥[SILENT_STRIKE_THRESHOLD] */
        fun silentThresholdMet(strikeTimestamps: List<Long>, nowMs: Long): Boolean =
            strikeTimestamps.count { it > nowMs - SILENT_STRIKE_WINDOW_MS } >= SILENT_STRIKE_THRESHOLD

        /** 时长格式（纯函数）：秒 → 「H:mm」分钟粒度向上取整（静默日志/诚实降级文案
         *  共口；45s → 0:01 不落「余 0:00」误导） */
        fun formatHhMm(sec: Long): String {
            val minutes = (sec.coerceAtLeast(0L) + 59) / 60
            return "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"
        }
    }
}
