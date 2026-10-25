package dev.tgfeed.core.feed

import android.util.Log
import dev.tgfeed.core.td.GatewayState
import dev.tgfeed.core.td.SearchQuotaLedger
import dev.tgfeed.core.td.TdLibGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

// ---------------- R211 搜索群 bot 回复频道直荐（用户勾选群 → USER 车道快速验证 → 直荐注册表） ----------------

/**
 * R211 直荐注册表条目：用户勾选的搜索群里，群友搜过的词命中的资源频道
 * （bot 应答里带出来的——**已被搜索验证**：有这个内容、群在传）。
 *
 * 一条 = 一个「频道 × 证据」档案：
 *  - [keyword]：群友最近一次搜它的词（推荐语「你的搜索群里有人搜过「X」」的点名词，
 *    换词再搜时由 [mergeRecommendEntry] 刷新）；
 *  - [verifiedAt]：直荐管线首次验证时刻（存在+是频道；活跃度另记 [lastActivitySec]）；
 *  - [lastTs]：最近一次 bot 应答命中（毫秒）——时效性排序原料（近 24h/7d 优先）；
 *  - [username]：用户名链接答案的小写名（直链答案为空）——开频道自愈/分享链接原料。
 */
data class RecommendEntry(
    val chatId: Long,
    val title: String,
    val username: String,
    val keyword: String,
    val fromGroup: Long,
    val lastTs: Long,
    val verifiedAt: Long,
    val lastActivitySec: Long,
    val members: Int,
    val hits: Int,
)

/**
 * 注册表本地合并（R211 纯函数锚，单测同源）：
 *  - 新频道 = 全新条目（hits=1，verifiedAt=本次）；
 *  - 已注册 = 命中/时间/来源群刷新（hits 累加、lastTs 取新、词取新命中之词），
 *    标题/用户名/成员数/活跃度**只升不降**（旧值已知不覆盖——AnswerStore 线索升级同口径），
 *    verifiedAt 保持首验时刻（再被 bot 应答本身就是复验，不需要重烧验证）。
 */
internal fun mergeRecommendEntry(
    existing: RecommendEntry?,
    chatId: Long,
    title: String,
    username: String,
    keyword: String,
    fromGroup: Long,
    lastTs: Long,
    verifiedAt: Long,
    lastActivitySec: Long,
    members: Int,
): RecommendEntry {
    if (existing == null) {
        return RecommendEntry(
            chatId = chatId, title = title, username = username, keyword = keyword,
            fromGroup = fromGroup, lastTs = lastTs, verifiedAt = verifiedAt,
            lastActivitySec = lastActivitySec, members = members, hits = 1,
        )
    }
    return existing.copy(
        title = existing.title.ifBlank { title },
        username = existing.username.ifBlank { username },
        keyword = if (lastTs >= existing.lastTs) keyword else existing.keyword,
        fromGroup = if (lastTs >= existing.lastTs) fromGroup else existing.fromGroup,
        lastTs = maxOf(existing.lastTs, lastTs),
        lastActivitySec = maxOf(existing.lastActivitySec, lastActivitySec),
        members = if (existing.members > 0) existing.members else members,
        hits = existing.hits + 1,
    )
}

/**
 * 待验证选择（R211 纯函数锚，单测同源）：从用户勾选群的答案里挑本轮要验证的条目。
 *  - **直链（chatId 已知）在前**：零搜索成本（GetChatHistory 本地探活），不占
 *    USER 车道账本——先收免费的；
 *  - 用户名链接在后：每条要烧一发 SearchPublicChat（USER 车道）；
 *  - 每档内部按 bot 应答时间**新鲜度降序**（任务书时效性：近的先验）；
 *  - 覆盖判定（已注册=被 chatId 或 username 认领）与负缓存（[skips]）剔除。
 */
internal fun selectPendingVerifies(
    scoped: List<KeywordAnswer>,
    entriesByChatId: Map<Long, RecommendEntry>,
    entriesByUsername: Map<String, RecommendEntry>,
    skips: Set<String>,
    limit: Int,
): List<KeywordAnswer> {
    if (limit <= 0) return emptyList()
    val direct = ArrayList<KeywordAnswer>()
    val byName = ArrayList<KeywordAnswer>()
    for (ka in scoped) {
        val a = ka.answer
        if (a.chatId != 0L) {
            if (a.chatId in entriesByChatId) continue
            direct += ka
        } else {
            val name = a.username.trim().removePrefix("@").lowercase()
            if (name.isEmpty() || name in skips || name in entriesByUsername) continue
            byName += ka
        }
    }
    val byTs = compareByDescending<KeywordAnswer> { it.answer.lastTs }
    return (direct.sortedWith(byTs) + byName.sortedWith(byTs)).take(limit)
}

/**
 * R211 直荐注册表（answer_store.json 同款风格：单写入方自持文件 +
 * @Synchronized 内存缓存唯一事实源 + 临时文件 rename 原子落盘 + 变更阈值 flush）。
 * 只做存储与读口，网络与验证逻辑在 [SearchGroupRecommender]——纯存储件可独立单测。
 */
class SearchGroupRecommendStore(dir: File) {

    private val file = File(dir, FILE_NAME)

    /** @Volatile 内存缓存（写时同步重建引用）；null = 尚未加载（首次访问 lazy 读盘） */
    @Volatile
    private var stateCache: StoreState? = null

    private class StoreState(
        val entries: HashMap<Long, RecommendEntry> = HashMap(), // key = chatId
        val skips: HashMap<String, Long> = HashMap(), // key = 小写用户名，value = 跳过时刻
    )

    private var dirtyCount = 0

    // ---------------- 读口 ----------------

    /** 全部注册条目快照（新建 List，调用方改不动库内对象） */
    @Synchronized
    fun snapshot(): List<RecommendEntry> = loadLocked().entries.values.toList()

    /** 负缓存快照（小写用户名集） */
    @Synchronized
    fun skips(): Set<String> = loadLocked().skips.keys.toHashSet()

    /** 发现页读口：TTL 内的条目（读侧过滤不落盘——时间相对性不值得触发写） */
    fun entries(nowMs: Long = System.currentTimeMillis()): List<RecommendEntry> =
        snapshot().filter { nowMs - it.lastTs <= ENTRY_TTL_MS }

    /** 诊断快照（GroupHarvester.stats 拼接用） */
    @Synchronized
    fun stats(): String {
        val st = loadLocked()
        return "直荐=${st.entries.size} 负缓存=${st.skips.size}"
    }

    // ---------------- 写口（Recommender 专用） ----------------

    /** 注册/刷新一条（[mergeRecommendEntry] 单一定义合并语义）；返回是否产生**新**条目 */
    @Synchronized
    fun register(
        chatId: Long, title: String, username: String, keyword: String, fromGroup: Long,
        lastTs: Long, verifiedAt: Long, lastActivitySec: Long, members: Int,
    ): Boolean {
        if (chatId == 0L) return false
        val st = loadLocked()
        val merged = mergeRecommendEntry(
            st.entries[chatId], chatId, title, username, keyword, fromGroup,
            lastTs, verifiedAt, lastActivitySec, members,
        )
        val isNew = !st.entries.containsKey(chatId)
        st.entries[chatId] = merged
        // 容量收口：按 lastTs 最老驱逐（新鲜热乎的直荐优先活着）
        while (st.entries.size > MAX_ENTRIES) {
            val oldest = st.entries.entries.minByOrNull { it.value.lastTs }?.key ?: break
            st.entries.remove(oldest)
        }
        markDirtyLocked()
        return isNew
    }

    /** 负缓存记一名（非频道/用户名 400 稳定终态——不烧第二次验证） */
    @Synchronized
    fun markSkip(name: String, nowMs: Long) {
        val key = name.trim().removePrefix("@").lowercase()
        if (key.isEmpty()) return
        val st = loadLocked()
        st.skips[key] = nowMs
        // 负缓存过期：用户名可被收回/频道可改名换主，30 天后允许重试一次
        val it = st.skips.entries.iterator()
        while (it.hasNext()) {
            if (nowMs - it.next().value > SKIPS_TTL_MS) it.remove()
        }
        while (st.skips.size > MAX_SKIPS) {
            val oldest = st.skips.entries.minByOrNull { it.value }?.key ?: break
            st.skips.remove(oldest)
        }
        markDirtyLocked()
    }

    /** 立即落盘（stop() 兜底调用）；从未加载/变更时空转（绝不凭空建文件） */
    @Synchronized
    fun flush() {
        if (stateCache == null) return
        flushLocked()
    }

    // ---------------- 落盘 ----------------

    private fun loadLocked(): StoreState {
        stateCache?.let { return it }
        val st = StoreState()
        runCatching {
            if (!file.isFile) return@runCatching
            val root = JSONObject(file.readText())
            root.optJSONObject("entries")?.let { e ->
                for (k in e.keys()) {
                    runCatching {
                        val o = e.getJSONObject(k)
                        val chatId = o.optLong("chatId", k.toLongOrNull() ?: 0L)
                        if (chatId != 0L) {
                            st.entries[chatId] = RecommendEntry(
                                chatId = chatId,
                                title = o.optString("title", ""),
                                username = o.optString("username", ""),
                                keyword = o.optString("keyword", ""),
                                fromGroup = o.optLong("fromGroup"),
                                lastTs = o.optLong("lastTs"),
                                verifiedAt = o.optLong("verifiedAt"),
                                lastActivitySec = o.optLong("lastAct", 0L),
                                members = o.optInt("members", 0),
                                hits = o.optInt("hits", 1),
                            )
                        }
                    }
                }
            }
            root.optJSONObject("skips")?.let { s ->
                for (k in s.keys()) {
                    runCatching { st.skips[k.lowercase()] = s.getLong(k) }
                }
            }
        }.onFailure { Log.w(TAG, "$FILE_NAME 读取失败（按空表重建）: ${it.message}") }
        stateCache = st
        return st
    }

    private fun markDirtyLocked() {
        dirtyCount++
        if (dirtyCount >= FLUSH_AFTER_MUTATIONS) flushLocked()
    }

    /** 持锁落盘：全量序列化 + tmp+rename 原子写（AnswerStore 同口径） */
    private fun flushLocked() {
        val st = stateCache ?: return
        runCatching {
            val root = JSONObject()
            val entries = JSONObject()
            for ((k, e) in st.entries) {
                entries.put(
                    k.toString(),
                    JSONObject()
                        .put("chatId", e.chatId)
                        .put("title", e.title)
                        .put("username", e.username)
                        .put("keyword", e.keyword)
                        .put("fromGroup", e.fromGroup)
                        .put("lastTs", e.lastTs)
                        .put("verifiedAt", e.verifiedAt)
                        .put("lastAct", e.lastActivitySec)
                        .put("members", e.members)
                        .put("hits", e.hits),
                )
            }
            root.put("entries", entries)
            val skips = JSONObject()
            for ((k, ts) in st.skips) skips.put(k, ts)
            root.put("skips", skips)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText()) // rename 失败兜底（跨分区/占用）
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "$FILE_NAME 落盘失败（下轮重试）: ${it.message}") }
        dirtyCount = 0
    }

    companion object {
        private const val TAG = "SearchGroupRecommend"

        /** 落盘文件名（filesDir 根，与 answer_store.json 同级） */
        const val FILE_NAME = "search_group_recommend.json"

        /** 读侧 TTL（14 天）：两周内群里还传它才算「在传」；过期条目不再出直荐卡 */
        const val ENTRY_TTL_MS = 14L * 24 * 3600 * 1000

        /** 注册表容量硬顶（按 lastTs 最老驱逐） */
        const val MAX_ENTRIES = 200

        /** 负缓存留存（30 天后允许重验一次——用户名可收回/频道可换主） */
        const val SKIPS_TTL_MS = 30L * 24 * 3600 * 1000

        /** 负缓存容量硬顶 */
        const val MAX_SKIPS = 500

        /** 变更多少次自动落盘（与 AnswerStore 同值口径） */
        const val FLUSH_AFTER_MUTATIONS = 10

        /** 内部诊断：脏计数清零只由 flush 做（单测只走公开口） */
        internal fun dirtyCountOf(store: SearchGroupRecommendStore): Int = store.dirtyCount
    }
}

/**
 * R211 搜索群直荐管线（拉模型，常驻只读协程）：
 *
 * 用户勾选搜索群（显式用户行为——R183 宝藏二的知情可控口径）里 bot 应答带出的频道，
 * 用**USER 车道**（[SearchQuotaLedger.Lane.USER_ACTION]）快速验证后进直荐注册表，
 * 发现页合并出「你的搜索群里有人搜过「X」」直荐卡。
 *
 * 为什么是拉模型（扫答案库）而不是推模型（入库钩子）：答案有**两个**写入方
 * （收割 recordLinks / 秒回 GatewayLiveSearch.askSearchEntity 直写答案库），
 * 单一 choke point 是答案库本身——扫库天然两路全收，不漏秒回场景。
 *
 * 验证语义（任务书 b）：
 *  - 存在 + 是频道：`resolvePublicChatByUsername`（USER 车道；resolve-once 缓存命中
 *    零搜索零预算；熔断/封禁窗/车道预算拒=**未发送**——整轮收工连试都不试，R184 纪律）；
 *  - 有内容 + 活跃度：解析返回的 Chat.lastMessage 日期（零额外请求）；直链答案用
 *    GetChatHistory 1 条本地探针（会话未建=null→按答案证据注册，活跃度记 0）——
 *    SearchChatMessages 是深索引爬取族重原语，验活轮不借用（GetChat 等价且便宜）；
 *  - 非频道（@人提及/私聊）与 400 稳定终态 → 负缓存（不烧第二次）。
 *
 * 时效性（任务书 d）：待验证队列按 bot 应答新鲜度降序（近的先验）；注册条目
 * lastTs 供发现页公平轮转的 tie-break 加新鲜分。
 *
 * 车道纪律：全程零裸 `client.send(TdApi.Search*)`——唯一搜索族调用
 * resolvePublicChatByUsername 内部走 searchPublicChatGuardedEx 统一发送口
 * （熔断/账号账本/resolve-once 缓存/429 上报全在口内）；代理验证模式
 * （agent_verify_search_quota）下 USER 车道折算 CRAWLER 基本被拒——直荐
 * 自动降级为「等 EntityResolver 后台车道慢慢解析」，语义安全。
 */
class SearchGroupRecommender(
    filesDir: File,
    private val gateway: TdLibGateway,
    private val answerStore: AnswerStore,
    private val userGroups: () -> List<SearchEntity>,
) {

    val store = SearchGroupRecommendStore(filesDir)

    /** 验证协程作用域（SupervisorJob：单轮失败不扩散；stop() 整体取消） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var loopJob: Job? = null

    /** 单飞闸：主循环与 kick 并发时只跑一个 drainOnce（防同轮双解析） */
    private val draining = AtomicBoolean(false)

    /** 瞬态失败的临时缓 names（name → defer 到时刻，毫秒；进程内——重启重试无害） */
    private val transientDefer = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 新直荐条目监听器（FeedViewModel 自动补刷发现页的接入口；隔离派发） */
    fun interface NewEntryListener {
        fun onNewRecommendEntries(count: Int)
    }

    private val listeners = CopyOnWriteArrayList<NewEntryListener>()

    /** 注册监听器（幂等）；返回注销函数 */
    fun registerListener(listener: NewEntryListener): () -> Unit {
        listeners.addIfAbsent(listener)
        return { listeners.remove(listener) }
    }

    // ---------------- 生命周期 ----------------

    /**
     * 启动验证主循环（幂等；GroupHarvester.start() 内随收割器起）。
     * 节奏：有产出短歇（等账本滑窗回血），空转低频巡检（秒回直写答案库的
     * 新答案最迟一个空闲周期被拾取）；未授权原地等待。
     * R377 懒门（用户 2026-10-24 拍板）：背景验证轮启动 20s 后即烧 USER_ACTION
     * 车道（12/h 总账最高优先级——真实用户搜索的救命车道被抢，Phase A 新实锚），
     * 现收编进懒门——门关/窗过期空转巡检，门内（用户意图在场窗）照既有预算验证。
     * 探针=「直荐轮候门」。
     */
    fun start() {
        if (loopJob != null) return
        loopJob = scope.launch {
            while (isActive) {
                val ready = gateway.state.value is GatewayState.Ready
                val gate = gateway.groupHarvester.discoveryGate
                val gateOpen = ready && gate.sessionActive()
                if (ready && !gateOpen) gate.noteGateHold("直荐轮")
                var didWork = false
                if (gateOpen) {
                    didWork = drainGuarded()
                }
                delay(if (didWork) ROUND_DELAY_MS else IDLE_DELAY_MS)
            }
        }
        Log.i(TAG, "SearchGroupRecommender 已启动（用户勾选群答案 → USER 车道直荐验证，R377 门内会话）")
    }

    /** 停止主循环并兜底落盘（幂等；GroupHarvester.stop() 内调用） */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
        runCatching { store.flush() }
    }

    /** 收割轮结束/秒回答案落库后的提前唤醒（单飞闸防与主循环并发双跑） */
    fun kick() {
        scope.launch { drainGuarded() }
    }

    private suspend fun drainGuarded(): Boolean {
        if (!draining.compareAndSet(false, true)) return false
        try {
            return runCatching { drainOnce() > 0 }
                .onFailure { Log.w(TAG, "直荐验证轮次失败（下轮重试）: ${it.message}") }
                .getOrDefault(false)
        } finally {
            draining.set(false)
        }
    }

    // ---------------- 一轮验证 ----------------

    /**
     * 单轮：扫用户勾选群答案 → 已注册的本地幂等刷新（零网络）→ 待验证队列
     * （直链先/用户名后，新鲜度降序）逐条验证注册。返回本轮注册+刷新条数
     * （驱动主循环节奏：>0 短歇回血，=0 低频巡检）。
     */
    private suspend fun drainOnce(): Int {
        val groupIds = runCatching { userGroups() }.getOrDefault(emptyList())
            .mapTo(HashSet()) { it.chatId }
        if (groupIds.isEmpty()) return 0
        val scoped = runCatching { answerStore.allKeywordAnswers() }
            .getOrDefault(emptyList())
            .filter { it.answer.fromGroup in groupIds }
        if (scoped.isEmpty()) return 0
        val now = System.currentTimeMillis()
        // 1) 本地幂等刷新：已注册 chatId 的新答案（词/命中/时间——零网络零预算）
        var touched = refreshRegistered(scoped, now)
        // 2) 待验证（快照判定一次做完，网络在锁外逐条）
        val byChatId = HashMap<Long, RecommendEntry>()
        val byUsername = HashMap<String, RecommendEntry>()
        for (e in store.snapshot()) {
            byChatId[e.chatId] = e
            if (e.username.isNotBlank()) byUsername[e.username] = e
        }
        transientDefer.entries.removeAll { now >= it.value }
        val skips = store.skips()
        val work = selectPendingVerifies(scoped, byChatId, byUsername, skips, MAX_VERIFY_PER_ROUND)
            .filter { ka ->
                // 瞬态缓 names：defer 期内的用户名本轮不烧（直链不设缓）
                ka.answer.chatId != 0L ||
                    !transientDefer.containsKey(ka.answer.username.removePrefix("@").lowercase())
            }
        var newCount = 0
        for (ka in work) {
            val a = ka.answer
            if (a.chatId != 0L) {
                // 直链：本地 GetChatHistory 1 条探活（会话未建/受限=null→按答案证据注册）
                val probe = runCatching { gateway.fetchRawMessages(a.chatId, 0, 0, 1) }.getOrNull()
                val lastAct = (probe?.maxOfOrNull { it.date } ?: 0).toLong()
                val title = a.hintTitle.ifBlank {
                    runCatching { gateway.discoveryGraph.nodeTitle(a.chatId) }.getOrNull() ?: ""
                }
                if (store.register(
                        a.chatId, title, "", ka.keyword, a.fromGroup,
                        a.lastTs, now, lastAct, a.members,
                    )
                ) {
                    newCount++
                }
                touched++
                continue
            }
            val name = a.username.trim().removePrefix("@").lowercase()
            if (name.isEmpty()) continue
            // USER 车道快速验证（勾选搜索群=显式用户行为——任务书口径）
            val r = gateway.resolvePublicChatByUsername(name, SearchQuotaLedger.Lane.USER_ACTION)
            when {
                r.ok -> {
                    if (!r.isChannel) {
                        // @人提及/私聊/超级群：不是资源频道，负缓存防反复烧
                        store.markSkip(name, now)
                        Log.i(TAG, "直荐验证：@$name 是非频道（负缓存），不入直荐")
                        continue
                    }
                    if (store.register(
                            r.chatId, r.title, name, ka.keyword, a.fromGroup,
                            a.lastTs, now, r.lastActivitySec, a.members,
                        )
                    ) {
                        newCount++
                        // 用户名答案回填 chatId/标题/成员数——宝藏二旧口径
                        // （aggregateGroupSignals 只认 chatId≠0）同时点亮，双路收敛
                        runCatching { answerStore.backfillChatId(name, r.chatId) }
                        if (r.title.isNotBlank() || a.members > 0) {
                            runCatching { answerStore.backfillHintTitle(a.link, r.title, a.members) }
                        }
                        if (r.title.isNotBlank()) {
                            runCatching { gateway.discoveryGraph.recordNodeTitle(r.chatId, r.title) }
                        }
                        Log.i(
                            TAG,
                            "直荐注册：@$name → ${r.chatId}（词=「${ka.keyword}」活跃=${
                                if (r.lastActivitySec > 0) r.lastActivitySec else "未知"
                            }）",
                        )
                    }
                    touched++
                }
                r.permanent -> {
                    // 400 稳定终态（用户名不存在/不可见/被封）：负缓存
                    store.markSkip(name, now)
                }
                r.floodWait -> {
                    // 封禁窗/熔断/账号预算拒（未发送）——连试都不试，整轮收工
                    Log.w(
                        TAG,
                        "直荐验证：搜索账本/熔断拒发（剩 ${r.floodRetryAfterSec}s），本轮收工" +
                            "（已注册 ${newCount}）",
                    )
                    break
                }
                else -> {
                    // 瞬态（网络类）：缓一缓再试，不进负缓存（连败由 EntityResolver 侧死信管）
                    transientDefer[name] = now + TRANSIENT_DEFER_MS
                }
            }
        }
        runCatching { store.flush() }
        if (newCount > 0) {
            notifyNewEntries(newCount)
        }
        return touched
    }

    /** 已注册条目的本地刷新（新答案命中：词/时间/hits——零网络） */
    private fun refreshRegistered(scoped: List<KeywordAnswer>, now: Long): Int {
        val byChatId = HashMap<Long, RecommendEntry>()
        for (e in store.snapshot()) byChatId[e.chatId] = e
        var refreshed = 0
        for (ka in scoped) {
            val a = ka.answer
            // 用户名答案的 chatId 可能已被回填（上一轮直荐或 EntityResolver）：
            // 两种形态都能对上注册条目
            val id = a.chatId
            if (id == 0L) continue
            val known = byChatId[id] ?: continue
            if (a.lastTs <= known.lastTs) continue // 旧答案不刷（recordAnswer 本身已按命中升级）
            store.register(
                id,
                known.title.ifBlank { a.hintTitle },
                known.username, ka.keyword, a.fromGroup,
                a.lastTs, known.verifiedAt, known.lastActivitySec, a.members,
            )
            refreshed++
        }
        return refreshed
    }

    private fun notifyNewEntries(count: Int) {
        for (l in listeners) {
            runCatching { l.onNewRecommendEntries(count) }
                .onFailure { Log.w(TAG, "直荐监听器异常（已隔离）: ${it.message}") }
        }
    }

    private companion object {
        const val TAG = "SearchGroupRecommender"

        /** 有产出后的短歇（毫秒）：等账号账本滑窗回血（USER 车道总量本就 ~12/h 级） */
        const val ROUND_DELAY_MS = 3L * 60_000

        /** 空转巡检周期（毫秒）：秒回直写答案库的新答案最迟一个空闲周期被拾取 */
        const val IDLE_DELAY_MS = 10L * 60_000

        /** 单轮验证上限（直链+用户名合计）：USER 车道保守花销 + 探活成本封顶 */
        const val MAX_VERIFY_PER_ROUND = 3

        /** 瞬态失败缓验时长（毫秒） */
        const val TRANSIENT_DEFER_MS = 10L * 60_000
    }
}
