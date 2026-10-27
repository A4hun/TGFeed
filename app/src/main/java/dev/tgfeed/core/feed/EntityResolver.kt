package dev.tgfeed.core.feed

import android.util.Log
import dev.tgfeed.core.td.FloodCircuitBreaker
import dev.tgfeed.core.td.GatewayState
import dev.tgfeed.core.td.TdLibGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * 用户名 → chatId 持久解析器（金矿断链修复）。
 *
 * 背景（模拟器实测定案）：金矿管道收割的链接几乎全是 t.me/<name> 用户名形式，
 * sniffLink 只把它们攒进 DiscoveryGraph.resolveQueue（懒解析表），而既有懒解析
 * 通道 resolvePendingChannels 只补 link 边、不回填 chatId、不持久化映射——
 * 导致 answer_store/goldmine fresh 的 chatId 恒为 0、group_search 边恒为 0 条，
 * 发现页"群搜"与"刚冒出来的"两个角标数据源永远为空。
 *
 * 本解析器把"用户名 → chatId"做成一条持久化管线：
 *  1. **队列来源**：
 *     - 收割新链接：GroupHarvester.recordLinks 每条用户名链接调 [enqueueHarvest]
 *       （边类型证据记 group_search、引用方=证据群 chatId，与图谱 resolveQueue 双写）；
 *     - 存量积压：每轮 [sweepFromGraph] 把 DiscoveryGraph.resolveQueue 全量并入
 *       自有 pending 表（边类型缺省 link；被 answer_store / goldmine fresh 引用的
 *       名字改判 group_search——这两个表的数据只可能来自收割管道）；
 *  2. **限流**：与收割主循环共享收割车道 FloodBudget（解析与收割同属被动只读管线，
 *     共用一个 flood 池，防风控口径不变）；每条解析前记账（R378 刀1：辅助车道
 *     tryAcquireAux，受主收割保底份额约束），耗尽即收工；
 *  3. **持久化**：entity_resolve.json（pending/resolved/dead 三表，@Synchronized
 *     公开方法 + @Volatile 内存缓存唯一事实源 + tmp+rename 原子落盘 + 变更阈值
 *     flush，与 AnswerStore/DiscoveryGraph 全同构），映射与死信重启不丢；
 *  4. **回填**：每解析出一条立即——answer_store 该用户名全部答案回填 chatId、
 *     goldmine fresh 表 "u:<name>" 条目迁移为 "c:<chatId>"（角标通道按 chatId 键，
 *     迁移后 freshChatIds() 立即可见）、图谱 resolveQueue 出队并按证据补写
 *     group_search/link 边（仅频道补边，与 resolvePendingChannels 同口径）+ 补全节点标题；
 *  5. **死信**：用户名不存在/不可见（TDLib 400）与广告黑名单频道进 dead 表永不重试；
 *     瞬态失败（网络异常）记 attempts，跨轮重试 [MAX_RESOLVE_ATTEMPTS] 次
 *     仍败才进死信——坏名字绝不反复烧预算；
 *     429 限流不计连败（R44：那是账号状态不是用户名的问题），且 SearchPublicChat
 *     熔断静默期每轮整轮跳过（不进解析、不烧 attempts），静默节奏由网关熔断表
 *     按服务端 retry-after 自持（持久化跨进程）；
 *  6. **优先级**：answer_store 与 goldmine fresh 引用的用户名（直接决定两个角标
 *     可见性）排第一梯队，其余按图谱引用计数降序的既有顺序排后；
 *  7. **黑名单**：AdChannelClassifier 已拉黑的用户名/chatId 不解析（纯浪费配额），
 *     直接进死信并清出图谱队列。
 *
 * 生命周期：由 GroupHarvester 构造持有，start()/stop() 挂在收割器同名方法上
 * （网关授权 Ready 后随收割器启动）——R377 懒门：门内会话恢复消化积压（门关/
 * 窗过期不烧 BACKGROUND 车道），不等下次收割的口径保留在门内。
 *
 * 与 FeedViewModel 触发的 resolvePendingChannels 并存：两边都消费图谱 resolveQueue，
 * 偶发同名重复解析的最坏后果是边 count 多加一次（recordEdge 幂等累加），收敛无害；
 * 本解析器出队即补边+回填，覆盖那边的全部正向效果。锁序固定单向
 * EntityResolver → DiscoveryGraph / AnswerStore / GoldmineHooks，无环。
 */
class EntityResolver(
    dir: File,
    private val gateway: TdLibGateway,
    private val graph: DiscoveryGraph,
    private val answerStore: AnswerStore,
    private val adClassifier: AdChannelClassifier,
    private val budget: FloodBudget,
) {

    /** 待解析证据：引用方 + 边类型 + attempts（graph resolveQueue 不存边类型与失败计数，本表补齐） */
    private class PendingEvidence(var fromChatId: Long, var edgeType: String, var ts: Long, var attempts: Int = 0)

    /** 已解析映射（持久化复用：该名字再被引用时零网络直接补边，见 sweep/enqueue 的即决路径） */
    private class ResolvedEntry(val chatId: Long, val title: String, val channel: Boolean, val ts: Long)

    /** 死信：reason ∈ not_found（400 不存在/不可见）/ blocked（广告黑名单）/ unstable（瞬态连败上限） */
    private class DeadEntry(val reason: String, val ts: Long)

    private class StoreState(
        val pending: LinkedHashMap<String, PendingEvidence> = LinkedHashMap(), // key = 小写用户名
        val resolved: HashMap<String, ResolvedEntry> = HashMap(), // key = 小写用户名
        val dead: HashMap<String, DeadEntry> = HashMap(), // key = 小写用户名
    )

    /** 单条解析的结果分类（驱动轮次节奏：预算耗尽/被限流即整轮收工） */
    private enum class Outcome { RESOLVED, DEAD, TRANSIENT, FLOOD, BUDGET_EXHAUSTED }

    private val file = File(dir, FILE_NAME)

    /** @Volatile 内存缓存（写时同步重建引用）；null = 尚未加载（首次访问 lazy 读盘） */
    @Volatile
    private var stateCache: StoreState? = null

    /** 距上次落盘以来的变更次数（flush 节流） */
    private var dirtyCount = 0

    /** 解析协程作用域（SupervisorJob：单轮失败不扩散；stop() 整体取消） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var loopJob: Job? = null

    // ---------------- 生命周期 ----------------

    /**
     * 启动解析主循环（幂等；GroupHarvester.start() 内调用，与收割开关解耦——
     * 就算暂停收割，门内存量积压也该继续消化）。循环体内每轮先查网关 Ready，
     * 未就绪原地等待；一切失败静默降级（记日志、下轮再来）。
     * R377 懒门（用户 2026-10-24 拍板：不打开发现页不在后台烧搜索族车道）：
     * 旧「应用启动即恢复消化积压」口径改为「门内会话消化」——门关（冷启→首开
     * 发现页）/窗过期空转巡检（BACKGROUND 车道不再启动即烧）；门>开关>解耦口径
     * （门开+收割关=解析照跑，存量积压消化语义保留在门内）。探针=「解析轮候门」。
     */
    fun start() {
        if (loopJob != null) return
        loopJob = scope.launch {
            while (isActive) {
                val ready = gateway.state.value is GatewayState.Ready
                val gate = gateway.groupHarvester.discoveryGate
                val gateOpen = ready && gate.sessionActive()
                if (ready && !gateOpen) gate.noteGateHold("解析轮")
                var didWork = false
                if (gateOpen) {
                    didWork = runCatching { drainOnce() > 0 }
                        .onFailure { Log.w(TAG, "解析轮次失败（下轮重试）: ${it.message}") }
                        .getOrDefault(false)
                }
                // 有活短歇（等滑窗回血），空转/未就绪/候门低频巡检（新收割入队最迟一个空闲周期被拾取）
                delay(if (didWork) ROUND_DELAY_MS else IDLE_DELAY_MS)
            }
        }
        Log.i(TAG, "EntityResolver 已启动（用户名→chatId 持久解析管线，与收割共享 flood 预算，R377 门内会话消化）")
    }

    /** 停止主循环并兜底落盘（幂等；GroupHarvester.stop() 内调用） */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
        runCatching { flush() }
    }

    /** 诊断快照（GroupHarvester.stats 拼接用）：待解析数 / 已解析映射数 / 死信数 */
    @Synchronized
    fun stats(): String {
        val st = loadLocked()
        return "解析待=${st.pending.size} 已解析=${st.resolved.size} 死信=${st.dead.size}"
    }

    /**
     * R211 已解析为**非频道**（私聊/超级群——@人提及、bot 自提及的解析产物）的
     * chatId 集（只读快照）：宝藏二信号聚合的过滤闸原料——这些 id 若被
     * [AnswerStore.backfillChatId] 回填进答案，聚合侧按非频道剔除，防「群友的
     * @人提及」混进发现候选池冒充频道（回填本身不区分频道与否，过滤归消费侧）。
     */
    @Synchronized
    fun resolvedNonChannelChatIds(): Set<Long> {
        val st = loadLocked()
        return st.resolved.values.filter { !it.channel }.mapTo(HashSet()) { it.chatId }
    }

    // ---------------- 收割入队（GroupHarvester.recordLinks 调用点） ----------------

    /**
     * 收割到的用户名链接入队（recordLinks 热路径，调用点在 recordGroupSearchLink 之后——
     * 顺序刻意：先让图谱 resolveQueue 攒引用证据，这里再判即决/入队/出队，已解析名字
     * 不会留下"先补边后又被 recordPendingName 加回队列"的重复证据）。
     *
     * 三分支：
     *  - 已解析：零网络即决——按收割证据（group_search 边）直接落地并出图谱队列；
     *  - 死信/黑名单：不排队不解析，顺手清出图谱队列（防 resolvePendingChannels 烧请求）；
     *  - 未解析：写入/刷新自有 pending 证据（from=证据群，type=group_search），等主循环解析。
     *
     * 全程纯本地（内存 + 阈值落盘），零网络零预算；直链（t.me/c/）与邀请链上游已过滤，
     * 这里 usernameFromLink 取不到名字直接返回。
     */
    fun enqueueHarvest(fromChatId: Long, link: String) {
        val name = normalizeName(AnswerStore.usernameFromLink(link)) ?: return
        if (adClassifier.isBlocked(0L, name)) {
            // 黑名单频道不解析（浪费配额）：进死信 + 清出图谱队列
            markDead(name, DEAD_BLOCKED)
            runCatching { graph.removePendingName(name) }
            return
        }
        var applyNow: ResolvedEntry? = null
        var wasDead = false
        synchronized(this) {
            val st = loadLocked()
            val r = st.resolved[name]
            if (r != null) {
                applyNow = r
            } else if (st.dead.containsKey(name)) {
                wasDead = true
            } else {
                val cur = st.pending[name]
                val now = System.currentTimeMillis()
                if (cur != null) {
                    // 收割证据优先于 sweep 的 link 缺省：刷新引用方与边类型
                    cur.fromChatId = fromChatId
                    cur.edgeType = GroupHarvester.EDGE_GROUP_SEARCH
                    cur.ts = now
                } else {
                    st.pending[name] = PendingEvidence(fromChatId, GroupHarvester.EDGE_GROUP_SEARCH, now)
                }
                markDirtyLocked()
            }
        }
        if (wasDead) {
            runCatching { graph.removePendingName(name) }
            return
        }
        applyNow?.let { applyResolved(name, it, fromChatId, GroupHarvester.EDGE_GROUP_SEARCH) }
    }

    // ---------------- 一轮消化 ----------------

    /**
     * 单轮消化：并入图谱积压 → 按优先级取一批 → 逐条限流解析。
     * 返回本轮成功解析条数（驱动主循环节奏：>0 短歇回血，=0 转低频巡检）。
     */
    private suspend fun drainOnce(): Int {
        // 角标优先级快照（一轮算一次）：answer_store 与 goldmine fresh 里 chatId 未知的用户名
        val hot = runCatching {
            answerStore.unresolvedUsernames() + GoldmineHooks.unresolvedFreshUsernames()
        }.getOrDefault(emptySet())
        sweepFromGraph(hot)
        // R44+R58 熔断闸（每轮解析前查闸）：SearchPublicChat 熔断静默中整轮跳过——不进解析、
        // 不烧预算、不烧 attempts。静默节奏由熔断表按服务端 retry-after 自持
        // （持久化跨进程），这里只看闸不自行记时；返回 0 转低频巡检，解封后自然复工。
        // R58 半开试探期：每轮只解析 1 条（试探性质）——试探成败由网关回报熔断表推进
        // 状态机（成功计数、429 重新熔断），闭合后才恢复正常节奏
        val family = FloodCircuitBreaker.FAMILY_SEARCH_PUBLIC_CHAT
        val breakerState = gateway.floodBreaker.stateOf(family)
        if (breakerState == FloodCircuitBreaker.BreakerState.OPEN) {
            Log.d(TAG, "SearchPublicChat 熔断静默中（剩 ${gateway.floodBreaker.remainingSec(family)}s），本轮跳过解析")
            return 0
        }
        val work = buildWorkList(hot).let {
            if (breakerState == FloodCircuitBreaker.BreakerState.HALF_OPEN) it.take(1) else it
        }
        if (work.isEmpty()) return 0
        if (breakerState == FloodCircuitBreaker.BreakerState.HALF_OPEN) {
            Log.i(TAG, "SearchPublicChat 半开试探期：本轮只解析 1 条（试探性质，闭合后恢复正常节奏）")
        }
        var resolvedCount = 0
        for (name in work) {
            // 黑名单快挡（sweep/enqueue 已挡；运行中新拉黑的双保险）
            if (adClassifier.isBlocked(0L, name)) {
                markDead(name, DEAD_BLOCKED)
                runCatching { graph.removePendingName(name) }
                continue
            }
            when (resolveOne(name)) {
                Outcome.BUDGET_EXHAUSTED -> {
                    Log.w(TAG, "解析预算耗尽，本轮收工（待=${work.size} 已解=$resolvedCount 剩=${budget.remaining()}）")
                    break
                }
                Outcome.FLOOD -> {
                    Log.w(TAG, "TDLib 限流（429），本轮收工（已解=$resolvedCount）")
                    break
                }
                // R378 刀2：瞬态失败与 FLOOD 同款整轮收工——网络劣化窗（16384 实况
                // loadCoverBytes 15s 超时连发同族）旧版 else->{} 继续循环=每名 1 令牌
                // 连烧穿整池的止血口；单名瞬态失败的下轮重试语义不变（attempts 照记，
                // 3 连败进死信不阻塞队列）
                Outcome.TRANSIENT -> {
                    Log.w(TAG, "解析瞬态失败，本轮收工等下轮（网络劣化态止血，已解=$resolvedCount）")
                    break
                }
                Outcome.RESOLVED -> resolvedCount++
                Outcome.DEAD -> {} // 死信终态：单名记账，轮次继续
            }
            delay(RESOLVE_INTERVAL_MS)
        }
        runCatching { flush() }
        if (resolvedCount > 0) Log.i(TAG, "本轮解析成功 $resolvedCount 条（${stats()}）")
        return resolvedCount
    }

    /**
     * 图谱 resolveQueue 积压并入自有 pending（每轮开头）：
     *  - 未知名：补 pending 证据（from=图谱最近引用方；hot 名字判 group_search——
     *    被答案库/fresh 引用只可能来自收割管道，其余按 link 缺省）；
     *  - hot 名字图谱队列缺席的（队列条目被容量淘汰，但答案库/fresh 还引用着）：
     *    未解析的并入 pending（from=0 占位，解析成功后由 AnswerStore.latestFromGroupOf
     *    补证据）；已解析的直接进 reapply 零网络补落地（覆盖"解析成功后 force-stop、
     *    金矿迁移没落盘"的现场，不等它再被收割进队列）；
     *  - 已解析名（重启前解出、之后又被引用入队）：零网络即决补边并出队；
     *  - 死信名：直接清出图谱队列（防 resolvePendingChannels 反复烧请求）。
     */
    private fun sweepFromGraph(hot: Set<String>) {
        val names = runCatching { graph.pendingResolveNames(GRAPH_QUEUE_SNAPSHOT_LIMIT) }
            .getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        val reapply = ArrayList<Triple<String, ResolvedEntry, Long>>() // (名字, 已解析映射, 图谱引用方)
        val deadInQueue = ArrayList<String>()
        synchronized(this) {
            val st = loadLocked()
            val inQueue = HashSet<String>(names.size * 2)
            for ((name, from) in names) {
                inQueue += name
                val r = st.resolved[name]
                when {
                    r != null -> reapply += Triple(name, r, from)
                    st.dead.containsKey(name) -> deadInQueue += name
                    !st.pending.containsKey(name) -> {
                        st.pending[name] = PendingEvidence(
                            from,
                            if (name in hot) GroupHarvester.EDGE_GROUP_SEARCH else DiscoveryGraph.EDGE_LINK,
                            now,
                        )
                        markDirtyLocked()
                    }
                }
            }
            // 图谱队列缺席的 hot 名字：答案/fresh 引用还在，角标不能等它再被收割到
            for (n in hot) {
                if (n in inQueue || st.pending.containsKey(n) || st.dead.containsKey(n)) continue
                val r = st.resolved[n]
                if (r != null) {
                    // 已解析 + 被 fresh/答案库引用 + 已出图谱队列（典型现场：首轮解析成功后
                    // 进程被 force-stop，金矿迁移没落盘）——零网络补落地（from=0 占位，
                    // 出锁后走 latestFromGroupOf 兜底链），不等它被重新收割进队列
                    reapply += Triple(n, r, 0L)
                    continue
                }
                st.pending[n] = PendingEvidence(0L, GroupHarvester.EDGE_GROUP_SEARCH, now)
                markDirtyLocked()
            }
        }
        for (n in deadInQueue) runCatching { graph.removePendingName(n) }
        for ((name, r, from) in reapply) {
            val edgeType = if (name in hot) GroupHarvester.EDGE_GROUP_SEARCH else DiscoveryGraph.EDGE_LINK
            // from=0 占位（图谱队列缺席的 hot 已解析名）：从答案库取最近引用群补证据
            var realFrom = from
            if (realFrom == 0L && edgeType == GroupHarvester.EDGE_GROUP_SEARCH) {
                realFrom = runCatching { answerStore.latestFromGroupOf(name) }.getOrDefault(0L)
            }
            applyResolved(name, r, realFrom, edgeType)
        }
    }

    /**
     * 优先级工作清单：第一梯队 = hot（answer_store/fresh 引用，直接决定两个角标可见性），
     * 第二梯队 = 纯积压；梯队内部按图谱引用计数降序（pendingResolveNames 既有顺序——
     * 被引用最多的名字最值得先解析），图谱队列缺席的（收割直入）按 ts 新先排在梯队尾。
     */
    private fun buildWorkList(hot: Set<String>): List<String> {
        val graphOrder = runCatching { graph.pendingResolveNames(GRAPH_QUEUE_SNAPSHOT_LIMIT) }
            .getOrDefault(emptyList())
        synchronized(this) {
            val st = loadLocked()
            if (st.pending.isEmpty()) return emptyList()
            val inGraph = graphOrder.map { it.first }.filter { st.pending.containsKey(it) }
            val inGraphSet = inGraph.toHashSet()
            val rest = st.pending.entries
                .filter { it.key !in inGraphSet }
                .sortedByDescending { it.value.ts }
                .map { it.key }
            val merged = inGraph + rest
            return (merged.filter { it in hot } + merged.filter { it !in hot })
                .take(RESOLVE_BATCH_PER_ROUND)
        }
    }

    /**
     * 解析一个用户名（预算记账 + TDLib SearchPublicChat + 落地/死信分类）。
     * 预算是与收割共享的令牌桶（R378 刀1+刀2 联动：记账口换辅助车道
     * [FloodBudget.tryAcquireAux]——L1 辅助上限与 L4 账本双保险；拒绝即本轮
     * 收工，零记账零强发）。
     */
    private suspend fun resolveOne(name: String): Outcome {
        if (!budget.tryAcquireAux()) return Outcome.BUDGET_EXHAUSTED
        val r = gateway.resolvePublicChatByUsername(name)
        if (r.ok) {
            if (adClassifier.isBlocked(r.chatId, name)) {
                // 解析后按 chatId 身份命中黑名单：不建边不回填，进死信
                markDead(name, DEAD_BLOCKED)
                runCatching { graph.removePendingName(name) }
                Log.i(TAG, "u:$name → ${r.chatId} 命中广告黑名单，进死信不落地")
                return Outcome.DEAD
            }
            val (entry, evidence) = synchronized(this) {
                val st = loadLocked()
                val e = ResolvedEntry(r.chatId, r.title, r.isChannel, System.currentTimeMillis())
                st.resolved[name] = e
                val ev = st.pending.remove(name)
                markDirtyLocked()
                e to ev
            }
            // 引用方证据兜底：pending 里 from=0（图谱队列条目被淘汰的 hot 名字）
            // 时从答案库取最近引用群；仍无引用方则只回填不补边（applyResolved 内部闸）
            val edgeType = evidence?.edgeType ?: DiscoveryGraph.EDGE_LINK
            var from = evidence?.fromChatId ?: 0L
            if (from == 0L && edgeType == GroupHarvester.EDGE_GROUP_SEARCH) {
                from = runCatching { answerStore.latestFromGroupOf(name) }.getOrDefault(0L)
            }
            applyResolved(name, entry, from, edgeType)
            Log.i(
                TAG,
                "解析成功 u:$name → ${r.chatId}（${if (r.isChannel) "频道" else "非频道"}，" +
                    "边=$edgeType from=$from）",
            )
            return Outcome.RESOLVED
        }
        if (r.permanent) {
            // 稳定终态（400 不存在/不可见/被封）：进死信，永不重试
            markDead(name, DEAD_NOT_FOUND)
            runCatching { graph.removePendingName(name) }
            return Outcome.DEAD
        }
        // R44：429 限流是账号状态不是该用户名的问题——不计 attempts 连败
        // （旧版封禁期把积压好名字逐个撞满 3 次进死信）；熔断表已按服务端 retry-after
        // 压住后续轮次，这里只负责本轮收工
        if (r.floodWait) {
            Log.d(TAG, "解析遇 429 限流 u:$name（不计连败，静默到期后自然重试）")
            return Outcome.FLOOD
        }
        // 瞬态失败（网络类）：attempts 跨进程累计，连败上限进死信防坏名字反复烧预算
        val (wentDead, attemptsNow) = synchronized(this) {
            val st = loadLocked()
            val p = st.pending[name]
            if (p == null) {
                false to 0
            } else {
                p.attempts++
                val dead = p.attempts >= MAX_RESOLVE_ATTEMPTS
                if (dead) {
                    st.pending.remove(name)
                    st.dead[name] = DeadEntry(DEAD_UNSTABLE, System.currentTimeMillis())
                }
                markDirtyLocked()
                dead to p.attempts
            }
        }
        if (wentDead) runCatching { graph.removePendingName(name) }
        // 瞬态失败是静默重试路径：留 D 级踪迹（名字+累计次数+是否进死信），排查不靠猜
        Log.d(
            TAG,
            "解析瞬态失败 u:$name（attempts=$attemptsNow/$MAX_RESOLVE_ATTEMPTS" +
                "${if (wentDead) " 连败上限进死信" else ""}）",
        )
        return Outcome.TRANSIENT
    }

    /**
     * 解析结果落地（三个回填触发点，全部本地幂等，可重入）：
     *  1. 图谱：resolveQueue 出队；频道且引用方有效才补边（from==0 是队列脏数据，
     *     与 resolvePendingChannels 同口径；超级群/私聊不建边——超级群不是 Feed 源），
     *     顺手 recordNodeTitle 补标题（发现页候选卡展示）；
     *  2. 答案库：该用户名全部答案回填 chatId（chatId 已为真的不动，二次调用空转）；
     *  3. 金矿：fresh 表 "u:<name>" 迁移为 "c:<chatId>"，freshChatIds() 角标立即可见。
     */
    private fun applyResolved(name: String, r: ResolvedEntry, fromChatId: Long, edgeType: String) {
        runCatching { graph.removePendingName(name) }
        if (r.channel && fromChatId != 0L && fromChatId != r.chatId) {
            runCatching { graph.recordEdge(fromChatId, r.chatId, edgeType) }
            if (r.title.isNotBlank()) runCatching { graph.recordNodeTitle(r.chatId, r.title) }
        }
        val filled = runCatching { answerStore.backfillChatId(name, r.chatId) }.getOrDefault(0)
        runCatching { GoldmineHooks.onEntityResolved(name, r.chatId) }
        Log.d(TAG, "落地 u:$name → ${r.chatId}：边=$edgeType from=$fromChatId 答案回填=$filled")
    }

    // ---------------- 死信 ----------------

    /** 进死信（持锁内可重入）：同时摘除 pending——死信不再消耗任何解析预算 */
    private fun markDead(name: String, reason: String) {
        synchronized(this) {
            val st = loadLocked()
            if (st.dead.containsKey(name)) {
                st.pending.remove(name)
                return
            }
            st.pending.remove(name)
            st.dead[name] = DeadEntry(reason, System.currentTimeMillis())
            markDirtyLocked()
        }
        Log.i(TAG, "u:$name 进死信（$reason）")
    }

    // ---------------- 落盘 ----------------

    @Synchronized
    private fun loadLocked(): StoreState {
        stateCache?.let { return it }
        val st = StoreState()
        runCatching {
            if (!file.isFile) return@runCatching
            val root = JSONObject(file.readText())
            root.optJSONObject("pending")?.let { p ->
                for (k in p.keys()) {
                    runCatching {
                        val o = p.getJSONObject(k)
                        val name = normalizeName(k) ?: return@runCatching
                        st.pending[name] = PendingEvidence(
                            o.optLong("from"),
                            o.optString("type", DiscoveryGraph.EDGE_LINK),
                            o.optLong("ts"),
                            o.optInt("attempts"),
                        )
                    }
                }
            }
            root.optJSONObject("resolved")?.let { rs ->
                for (k in rs.keys()) {
                    runCatching {
                        val o = rs.getJSONObject(k)
                        val name = normalizeName(k) ?: return@runCatching
                        val chatId = o.optLong("chatId")
                        if (chatId != 0L) {
                            st.resolved[name] = ResolvedEntry(
                                chatId, o.optString("title", ""),
                                o.optBoolean("channel"), o.optLong("ts"),
                            )
                        }
                    }
                }
            }
            root.optJSONObject("dead")?.let { d ->
                for (k in d.keys()) {
                    runCatching {
                        val o = d.getJSONObject(k)
                        val name = normalizeName(k) ?: return@runCatching
                        st.dead[name] = DeadEntry(o.optString("reason", DEAD_NOT_FOUND), o.optLong("ts"))
                    }
                }
            }
        }.onFailure { Log.w(TAG, "entity_resolve.json 读取失败（按空库重建）: ${it.message}") }
        stateCache = st
        return st
    }

    private fun markDirtyLocked() {
        dirtyCount++
        if (dirtyCount >= FLUSH_AFTER_MUTATIONS) flushLocked()
    }

    /** 立即落盘（stop() 兜底调用）；从未加载/变更时空转（绝不凭空建文件） */
    @Synchronized
    fun flush() {
        if (stateCache == null) return
        flushLocked()
    }

    /** 持锁落盘：容量收口 + 全量序列化 + tmp+rename 原子写（AnswerStore 同口径） */
    private fun flushLocked() {
        val st = stateCache ?: return
        runCatching {
            evictLocked(st)
            val root = JSONObject()
            val pending = JSONObject()
            for ((k, p) in st.pending) {
                pending.put(k, JSONObject()
                    .put("from", p.fromChatId)
                    .put("type", p.edgeType)
                    .put("ts", p.ts)
                    .put("attempts", p.attempts))
            }
            root.put("pending", pending)
            val resolved = JSONObject()
            for ((k, r) in st.resolved) {
                resolved.put(k, JSONObject()
                    .put("chatId", r.chatId)
                    .put("title", r.title)
                    .put("channel", r.channel)
                    .put("ts", r.ts))
            }
            root.put("resolved", resolved)
            val dead = JSONObject()
            for ((k, d) in st.dead) {
                dead.put(k, JSONObject().put("reason", d.reason).put("ts", d.ts))
            }
            root.put("dead", dead)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText()) // rename 失败兜底（跨分区/占用）：拷内容后删临时文件
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "entity_resolve.json 落盘失败（下轮重试）: ${it.message}") }
        dirtyCount = 0
    }

    /** 容量收口：三表各按 ts 最老驱逐（resolved 被驱逐的最坏后果是再被引用时重付一次解析） */
    private fun evictLocked(st: StoreState) {
        if (st.pending.size > MAX_PENDING) {
            val keep = st.pending.entries.sortedByDescending { it.value.ts }.take(MAX_PENDING)
            st.pending.clear()
            keep.forEach { st.pending[it.key] = it.value }
        }
        if (st.resolved.size > MAX_RESOLVED) {
            val keep = st.resolved.entries.sortedByDescending { it.value.ts }.take(MAX_RESOLVED)
            st.resolved.clear()
            keep.forEach { st.resolved[it.key] = it.value }
        }
        if (st.dead.size > MAX_DEAD) {
            val keep = st.dead.entries.sortedByDescending { it.value.ts }.take(MAX_DEAD)
            st.dead.clear()
            keep.forEach { st.dead[it.key] = it.value }
        }
    }

    companion object {
        private const val TAG = "EntityResolver"

        /** 落盘文件名（filesDir 根目录，与 answer_store.json / discovery_graph.json 同级） */
        private const val FILE_NAME = "entity_resolve.json"

        /** 死信原因：TDLib 400（用户名不存在/不可见/被封），稳定终态 */
        private const val DEAD_NOT_FOUND = "not_found"

        /** 死信原因：AdChannelClassifier 黑名单（不解析不落地，省配额） */
        private const val DEAD_BLOCKED = "blocked"

        /** 死信原因：瞬态失败连败达上限（坏名字防反复烧预算） */
        private const val DEAD_UNSTABLE = "unstable"

        /**
         * 单轮解析条数上限（R378 刀2 慢车道：25→5——4000 积压下 25 名/轮的令牌烧速
         * 在瞬态风暴态可吃满整池；5 名/轮+长间隔把解析降为后台慢消化。消化速度受
         * L4 BACKGROUND 车道（4/h 基数）+本慢车道双重封顶——账号安全设计，不是
         * 本批要消灭的对象；加速须走 L4 车道配额另立单）。
         */
        private const val RESOLVE_BATCH_PER_ROUND = 5

        /** 逐条解析间隔（毫秒）：平滑请求突发（预算管总量，间隔管瞬时密度——分工照旧） */
        private const val RESOLVE_INTERVAL_MS = 1_200L

        /**
         * 有解析成果的轮次后短歇（毫秒；R378 刀2 慢车道：60s→300s 长间隔——任务书
         * 「5 名/轮长间隔」定案，稳态令牌烧速≈授予 4+探针 ≤12/5min ≈ 16/h <辅助上限）
         */
        private const val ROUND_DELAY_MS = 300_000L

        /** 空转/未就绪巡检周期（毫秒）：新收割入队的名字最迟一个空闲周期被拾取 */
        private const val IDLE_DELAY_MS = 300_000L

        /** 瞬态失败跨轮重试上限（attempts 持久化，跨进程累计）：连败即进死信 */
        private const val MAX_RESOLVE_ATTEMPTS = 3

        /** 图谱队列快照条数（对齐 DiscoveryGraph.MAX_RESOLVE_QUEUE 全量覆盖） */
        private const val GRAPH_QUEUE_SNAPSHOT_LIMIT = 2_000

        /** 待解析表硬顶（图谱队列 2000 的两倍缓冲：收割直入但图谱缺席的残余），超出按 ts 最老驱逐 */
        private const val MAX_PENDING = 4_000

        /** 已解析映射硬顶（对齐图谱 MAX_NODES 量级），超出按 ts 最老驱逐 */
        private const val MAX_RESOLVED = 5_000

        /** 死信表硬顶，超出按 ts 最老驱逐（死信被驱逐的最坏后果是重试一次再死） */
        private const val MAX_DEAD = 2_000

        /** 变更多少次自动落盘（与 AnswerStore 同值口径：低频动作，阈值小、落盘勤） */
        private const val FLUSH_AFTER_MUTATIONS = 10

        /** 用户名归一（与 DiscoveryGraph.recordPendingName 同口径：去 @ 前缀 + 小写）；空名返回 null */
        internal fun normalizeName(raw: String): String? =
            raw.trim().removePrefix("@").lowercase().takeIf { it.isNotEmpty() }
    }
}
