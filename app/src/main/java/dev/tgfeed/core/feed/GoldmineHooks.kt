package dev.tgfeed.core.feed

import android.util.Log
import dev.tgfeed.core.td.SearchTextKit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 金矿 P4-A 统一钩子（热词榜 + 新片雷达，object 单例、数据自持）。
 *
 * 多代理边界协议：GroupHarvester 入库点（recordLinks 首行）只有一行
 * [onHarvested] 调用，热词计数器与最近新发现表全部自持于本对象；
 * 落盘 goldmine.json（filesDir 根目录，与 answer_store.json / discovery_graph.json 同级；
 * @Synchronized + @Volatile 内存缓存唯一事实源 + tmp+rename 原子写 + 变更阈值 flush，
 * 与 AnswerStore/DiscoveryGraph 全同构）。由 FeedViewModel init 时 [attach] 挂接
 * （仅真实网关挂接；Mock 网关/未挂接全程静默空转，绝不抛异常影响收割主链）。
 *
 * 两件资产：
 *  1. **热词榜**：群友搜索关键词出现事件流（fold 归一 → 时间戳序列），读侧按近 24h
 *     窗口聚合计数降序出榜——发现页"群友在搜"区数据源（需求维度热榜）；
 *  2. **新片雷达**：入库链接目标在**图谱建边前**比对图谱已有节点（钩子在
 *     recordGroupSearchLink 之前触发，"首见"判定依赖这个未入库现场），图谱从未见过的
 *     记入最近新发现表（TTL [FRESH_TTL_MS]）——发现页候选卡"刚冒出来的"角标数据源。
 */
object GoldmineHooks {

    /** 热词条目：展示原文（首见形态，仅展示用）+ 出现时间戳序列（升序，读/写两侧按留存窗驱逐） */
    private class KwHot(val display: String, val ts: ArrayDeque<Long> = ArrayDeque())

    /**
     * 新发现条目：chatId 已知（t.me/c/ 直链）记 chatId；用户名链接 chatId 未知记 0，
     * 表 key 用 "u:<小写用户名>" 区分（角标通道只能带 chatId 已知的条目，见 [freshChatIds]）。
     */
    private class FreshEntry(var chatId: Long, var username: String, var firstTs: Long, var lastTs: Long, var hits: Int)

    private class HookState(
        val hot: LinkedHashMap<String, KwHot> = LinkedHashMap(), // key = fold 后的关键词
        val fresh: LinkedHashMap<String, FreshEntry> = LinkedHashMap(), // key = "c:<chatId>" / "u:<username>"
    )

    /** 落盘目录（attach 注入；null = 未挂接，全程空转） */
    @Volatile
    private var dir: File? = null

    /** 图谱只读引用（attach 注入；首见判定用，本对象绝不调用图谱写方法） */
    @Volatile
    private var graph: DiscoveryGraph? = null

    /** @Volatile 内存缓存（写时同步重建引用）；null = 尚未加载（首次访问 lazy 读盘） */
    @Volatile
    private var stateCache: HookState? = null

    /** 距上次落盘以来的变更次数（flush 节流） */
    private var dirtyCount = 0

    /** 图谱待解析用户名快照（"名字见过"证据；[PENDING_NAMES_TTL_MS] 节流重建，防逐链接 O(队列) 扫描） */
    @Volatile
    private var pendingNames: Set<String> = emptySet()

    @Volatile
    private var pendingNamesAt = 0L

    /**
     * R378 刀4 liveAsk 自词登记表：fold 后的词 → 最近一次 liveAsk 发词时刻（毫秒）。
     * 纯内存零落盘（重启清零=重启后回声窗口已过的语义可接受）；锁=本对象 monitor
     * （与 [onHarvested] 写路径互斥，锁序单向无环）。
     */
    private val liveAskSent = LinkedHashMap<String, Long>()

    /**
     * 挂接（FeedViewModel init 调用；幂等，后到覆盖）。
     * 只持只读引用：图谱一切读方法都是 @Synchronized 纯读，本对象不回写图谱。
     */
    fun attach(filesDir: File, discoveryGraph: DiscoveryGraph) {
        dir = filesDir
        graph = discoveryGraph
    }

    // ---------------- 写：收割钩子 ----------------

    /**
     * 收割入库钩子（GroupHarvester.recordLinks 首行唯一调用点）：
     *  - [keyword] 非空 = 一次"群友搜了这个词"事件（应答配对成功才配得出），记一个时间戳
     *    （按应答消息计次，**不按链接数放大**）；关键词上游已过 sanitizeKeyword
     *    （长度/链接/广告/指令前缀过滤），这里只做 fold 归一；
     *  - [links] 逐条过与 recordLinks 同口径的轻过滤（广告参数链/邀请链丢弃——
     *    AdChannelClassifier 实例归 GroupHarvester 私有不可复用，被它独家拦截的广告频道
     *    可能漏进新发现表，属已知残余风险），然后做图谱首见判定：已在最近新发现表 →
     *    续命 lastTs 并累计 hits；图谱已见过 → 跳过；否则记 first-seen。
     * 全程纯本地（内存 + 节流落盘），零网络零 flood 预算；未挂接（dir==null）直接空转。
     */
    fun onHarvested(keyword: String?, links: List<String>) {
        // 外挂监听器派发（P4-B 需求订阅等后续金矿特性的接入口）先行：
        // 本对象自持资产在 Mock/未挂接时随 dir==null 空转，但外挂监听器
        // （挂接点在 AppPrefs.init，进程启动早期即就位）不受本对象 attach
        // 状态影响，不能被这条早退吃掉
        dispatchToListeners(keyword, links)
        if (dir == null) return
        val now = System.currentTimeMillis()
        val folded = keyword?.let { SearchTextKit.fold(it).trim() }?.takeIf { it.isNotEmpty() }
        synchronized(this) {
            val st = loadLocked()
            var changed = false
            // 1) 热词事件：一次应答配对 = 词出现一次
            // R378 刀4 liveAsk 自词排除（R372 挂账清偿·计数口一刀切双腿同治）：fold
            // 词在 [LIVE_ASK_SELF_SUPPRESS_MS] 窗内=本机 liveAsk 发过（腿A 用户点击
            // 热词 chip 每词+1 无界放大+腿B bot 私聊回声 ≤1/周期有界同向）→跳过
            // 热词事件；链接侧（新发现登记/外挂监听器派发/答案回灌）语义照旧——
            // 只有热词计数被排除，真资源照收
            if (folded != null && keyword != null && !isLiveAskSelfLocked(folded, now)) {
                val e = st.hot.getOrPut(folded) { KwHot(keyword.trim()) }
                evictKwLocked(e, now)
                e.ts.addLast(now)
                while (e.ts.size > MAX_TS_PER_KW) e.ts.removeFirst()
                // 关键词表容量收口：按最近出现最老驱逐（LRU）
                while (st.hot.size > MAX_HOT_KEYWORDS) {
                    val oldest = st.hot.entries.minByOrNull { it.value.ts.lastOrNull() ?: 0L }?.key ?: break
                    st.hot.remove(oldest)
                }
                changed = true
            }
            // 2) 新发现登记（图谱首见判定）
            for (link in links) {
                if (AdBlacklist.isSpamLink(link)) continue
                val path = link.removePrefix("https://t.me/")
                if (path.startsWith("+") || path.startsWith("joinchat", ignoreCase = true)) continue
                val chatId = AnswerStore.chatIdFromLink(link)
                val username = AnswerStore.usernameFromLink(link).lowercase()
                val key = when {
                    chatId != 0L -> "c:$chatId"
                    username.isNotEmpty() -> "u:$username"
                    else -> continue
                }
                val cur = st.fresh[key]
                if (cur != null) {
                    // 表内已有 = 首见早已登记：续命 + 累计（TTL 以 lastTs 计——窗口内被反复搜出，
                    // 说明"刚冒出来"仍在发酵，角标多挂几天）
                    cur.lastTs = now
                    cur.hits++
                    changed = true
                    continue
                }
                if (seenInGraph(chatId, username, now)) continue
                st.fresh[key] = FreshEntry(chatId, username, now, now, 1)
                // 容量收口：按 firstTs 最老驱逐
                while (st.fresh.size > MAX_FRESH_ENTRIES) {
                    val oldest = st.fresh.entries.minByOrNull { it.value.firstTs }?.key ?: break
                    st.fresh.remove(oldest)
                }
                changed = true
                Log.i(TAG, "新发现登记: $key（图谱首见，来源词=${folded ?: "无"}）")
            }
            // 顺手清过期新发现（写路径驱逐；读路径只过滤不删，见 freshChatIds）
            val it = st.fresh.entries.iterator()
            while (it.hasNext()) {
                if (now - it.next().value.lastTs > FRESH_TTL_MS) {
                    it.remove()
                    changed = true
                }
            }
            if (changed) markDirtyLocked()
        }
    }

    /**
     * 图谱首见判定（在钩子持锁内调用；graph 方法全是 @Synchronized 纯读，
     * 锁序固定 GoldmineHooks→DiscoveryGraph 单向无环）：
     *  - chatId 已知：入边证据合计 >0 或节点标题已补全 = 见过；
     *  - 用户名链接：小写名在图谱待解析队列快照里 = 见过（快照节流重建）。
     * 已知盲区：用户名早前已被懒解析成真边（出队）的，这里认不出"见过"——
     * 新发现 TTL 过期后可能误复活一次，可接受（交付风险点备查）。
     */
    private fun seenInGraph(chatId: Long, username: String, now: Long): Boolean {
        val g = graph ?: return false // 无图谱可读时按"没见过"记（宁多记不漏记）
        if (chatId != 0L) {
            return g.evidenceCountOf(chatId) > 0 || g.nodeTitle(chatId) != null
        }
        if (username.isNotEmpty()) {
            if (now - pendingNamesAt > PENDING_NAMES_TTL_MS) {
                pendingNames = runCatching {
                    g.pendingResolveNames(MAX_RESOLVE_QUEUE_SNAPSHOT).mapTo(HashSet()) { it.first }
                }.getOrDefault(pendingNames)
                pendingNamesAt = now
            }
            return username in pendingNames
        }
        return false
    }

    // ---------------- 写：liveAsk 自词登记（R378 刀4） ----------------

    /**
     * liveAsk 发词登记（askSearchEntityImpl 在词真发出后调用——sentOk=true 后一行）：
     * fold 归一与 [onHarvested] 热词计数段同键同源（[SearchTextKit.fold]），
     * 幂等刷新时刻。计数段窗内排除自词=R372 liveAsk 自增强环断环（发现页热词
     * chip 点击→openSearch 自动开搜→发词→轮末计数→词更热→更易被点）。
     * **搜索页 liveAsk 显式提交链（R371 域）零碰**——本口只登记不发送不计数。
     */
    fun noteLiveAskSent(word: String) {
        val folded = SearchTextKit.fold(word).trim().takeIf { it.isNotEmpty() } ?: return
        val now = System.currentTimeMillis()
        synchronized(this) {
            // 窗外旧词懒清场（登记表只服务窗口判定）+容量硬顶插入序最老先逐
            liveAskSent.entries.removeAll { now - it.value > LIVE_ASK_SELF_SUPPRESS_MS }
            liveAskSent[folded] = now
            while (liveAskSent.size > MAX_LIVE_ASK_SENT) {
                liveAskSent.remove(liveAskSent.keys.first())
            }
        }
    }

    /** 自词窗内判定（持本对象锁内调用——onHarvested 计数段前置） */
    private fun isLiveAskSelfLocked(folded: String, now: Long): Boolean =
        liveAskSent[folded]?.let { now - it <= LIVE_ASK_SELF_SUPPRESS_MS } == true

    // ---------------- 读：发现页数据源 ----------------

    /**
     * 热词榜（发现页"群友在搜"数据源）：近 [windowMs] 出现次数降序的 (展示词, 次数) 列表。
     * 纯内存聚合；读路径只统计不驱逐（时间相对性淘汰不值得触发落盘，写路径已管留存）。
     */
    @Synchronized
    fun hotKeywords(windowMs: Long, limit: Int): List<Pair<String, Int>> {
        if (dir == null) return emptyList()
        val now = System.currentTimeMillis()
        return loadLocked().hot.values
            .map { e -> e.display to e.ts.count { now - it < windowMs } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
    }

    /**
     * 最近新发现的 chatId 集（发现页候选卡"刚冒出来的"角标数据源）：
     * TTL 内且 chatId 已知的条目。用户名链接条目 chatId 未知、候选按 chatId 键，
     * 进不了角标通道——由 EntityResolver 解析出 chatId 后经 [onEntityResolved]
     * 迁移成 "c:<chatId>" 条目升级进通道。
     */
    @Synchronized
    fun freshChatIds(now: Long): Set<Long> {
        if (dir == null) return emptySet()
        return loadLocked().fresh.values
            .filter { it.chatId != 0L && now - it.lastTs <= FRESH_TTL_MS }
            .mapTo(HashSet()) { it.chatId }
    }

    /**
     * 新发现快照条目（发现页"刚冒出来的"横滑区/顶部统计胶囊数据源）：
     * chatId 已解析条目的首见/最近时间戳只读投影。
     */
    class FreshChannelInfo(val chatId: Long, val firstTs: Long, val lastTs: Long)

    /**
     * 最近新发现快照（TTL 内且 chatId 已解析，按 lastTs 降序）：
     * [freshChatIds] 的带时间戳版本——横滑小卡要"刚刚/几小时前"文案（lastTs）、
     * 统计胶囊要"今日新发现"计数（firstTs），故暴露条目级只读投影。
     * chatId 未解析（"u:<name>" 形态）的条目天然不进本快照，与角标通道同口径。
     */
    @Synchronized
    fun freshChannels(now: Long): List<FreshChannelInfo> {
        if (dir == null) return emptyList()
        return loadLocked().fresh.values
            .filter { it.chatId != 0L && now - it.lastTs <= FRESH_TTL_MS }
            .sortedByDescending { it.lastTs }
            .map { FreshChannelInfo(it.chatId, it.firstTs, it.lastTs) }
    }

    // ---------------- 解析回填（EntityResolver 专用） ----------------

    /**
     * 新发现表里 chatId 未知的 "u:<name>" 条目的小写用户名集合
     * （EntityResolver 的优先级源：这些名字直接决定"刚冒出来的"角标可见性，先解析）。
     */
    @Synchronized
    fun unresolvedFreshUsernames(): Set<String> {
        if (dir == null) return emptySet()
        return loadLocked().fresh.entries
            .filter { it.value.chatId == 0L && it.key.startsWith("u:") }
            .mapTo(HashSet()) { it.key.removePrefix("u:") }
    }

    /**
     * 用户名已解析出 chatId：把 fresh 表 "u:<name>" 条目迁移为 "c:<chatId>"
     * （保留 firstTs/lastTs/hits——"刚冒出来"的发酵时长与热度不因身份升级而清零）；
     * 同 chatId 的直链条目已存在时合并（firstTs 取最早、lastTs 取最新、hits 累加）。
     * 幂等：u 条目不存在（未登记过或已迁移）时直接返回，不触发落盘。
     */
    @Synchronized
    fun onEntityResolved(username: String, chatId: Long) {
        if (dir == null) return
        val name = username.trim().removePrefix("@").lowercase()
        if (name.isEmpty() || chatId == 0L) return
        val st = loadLocked()
        val entry = st.fresh.remove("u:$name") ?: return
        val existing = st.fresh["c:$chatId"]
        if (existing != null) {
            existing.firstTs = minOf(existing.firstTs, entry.firstTs)
            existing.lastTs = maxOf(existing.lastTs, entry.lastTs)
            existing.hits += entry.hits
        } else {
            st.fresh["c:$chatId"] =
                FreshEntry(chatId, entry.username, entry.firstTs, entry.lastTs, entry.hits)
        }
        // 迁移是稀有且直接决定角标可见性的事件：立即落盘，不等变更阈值——
        // 实测阈值 flush 未到就 force-stop 会丢掉迁移（重启后 fresh 仍是 u: 形态）
        flushLocked()
        Log.i(TAG, "新发现身份升级: u:$name → c:$chatId（角标通道可见）")
    }

    // ---------------- 落盘 ----------------

    /** 立即落盘（FeedViewModel.onCleared 兜底调用；常规路径靠变更阈值自动写） */
    @Synchronized
    fun flush() {
        if (stateCache == null) return // 从未加载/变更，不落盘（绝不凭空建文件）
        flushLocked()
    }

    /** 懒加载：缓存命中直接返回；文件损坏/缺失误按空表重建（不崩溃） */
    private fun loadLocked(): HookState {
        stateCache?.let { return it }
        val d = dir ?: return HookState() // 未挂接：空态不缓存，attach 后首次写再读盘
        val st = HookState()
        runCatching {
            val file = File(d, FILE_NAME)
            if (!file.isFile) return@runCatching
            val root = JSONObject(file.readText())
            root.optJSONObject("hot")?.let { h ->
                for (k in h.keys()) {
                    runCatching {
                        val o = h.getJSONObject(k)
                        val e = KwHot(o.optString("kw", k))
                        o.optJSONArray("ts")?.let { arr ->
                            for (i in 0 until arr.length()) {
                                val t = arr.optLong(i, 0L)
                                if (t > 0L) e.ts.addLast(t)
                            }
                        }
                        if (e.ts.isNotEmpty()) st.hot[k] = e
                    }
                }
            }
            root.optJSONArray("fresh")?.let { arr ->
                for (i in 0 until arr.length()) {
                    runCatching {
                        val o = arr.getJSONObject(i)
                        val key = o.optString("key")
                        if (key.isNotEmpty()) {
                            st.fresh[key] = FreshEntry(
                                chatId = o.optLong("chatId"),
                                username = o.optString("username", ""),
                                firstTs = o.optLong("firstTs"),
                                lastTs = o.optLong("lastTs"),
                                hits = o.optInt("hits", 1),
                            )
                        }
                    }
                }
            }
        }.onFailure { Log.w(TAG, "goldmine.json 读取失败（按空库重建）: ${it.message}") }
        stateCache = st
        return st
    }

    /** 驱逐词条目里超出留存窗的旧时间戳（持锁内调用） */
    private fun evictKwLocked(e: KwHot, now: Long) {
        while (e.ts.isNotEmpty() && now - e.ts.first() >= HOT_RETENTION_MS) e.ts.removeFirst()
    }

    private fun markDirtyLocked() {
        dirtyCount++
        if (dirtyCount >= FLUSH_AFTER_MUTATIONS) flushLocked()
    }

    /** 持锁落盘：全量序列化 + tmp+rename 原子写（与 AnswerStore 同口径） */
    private fun flushLocked() {
        val st = stateCache ?: return
        val d = dir ?: return
        runCatching {
            val root = JSONObject()
            val hot = JSONObject()
            for ((k, e) in st.hot) {
                hot.put(k, JSONObject()
                    .put("kw", e.display)
                    .put("ts", JSONArray().apply { e.ts.forEach { put(it) } }))
            }
            root.put("hot", hot)
            val fresh = JSONArray()
            for ((key, e) in st.fresh) {
                fresh.put(JSONObject()
                    .put("key", key)
                    .put("chatId", e.chatId)
                    .put("username", e.username)
                    .put("firstTs", e.firstTs)
                    .put("lastTs", e.lastTs)
                    .put("hits", e.hits))
            }
            root.put("fresh", fresh)
            val file = File(d, FILE_NAME)
            val tmp = File(d, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText()) // rename 失败兜底（跨分区/占用）：拷内容后删临时文件
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "goldmine.json 落盘失败（下轮重试）: ${it.message}") }
        dirtyCount = 0
    }

    // ---------------- 外挂监听器派发（P4-B+ 后续金矿特性接入口；P4-A 自持资产不走这里） ----------------

    /**
     * 收割事件外挂监听器。注意：派发的是 recordLinks **过滤前**的原始链接
     * （P4-A 图谱首见判定需要未入库现场），监听器须按需自行轻过滤
     * （广告参数链/邀请链丢弃）；只允许纯本地内存计算/发系统通知，
     * 禁止网络与重 IO——运行在收割协程（Dispatchers.IO）上，堵住会拖慢整轮收割。
     */
    fun interface HarvestListener {
        fun onHarvested(keyword: String?, links: List<String>)
    }

    /** 外挂监听器表：CopyOnWriteArrayList（个位数、读多写少，收割线程派发零加锁；注册幂等） */
    private val extraListeners = java.util.concurrent.CopyOnWriteArrayList<HarvestListener>()

    /** 注册外挂监听器（幂等）；返回注销函数（常驻特性生命周期=进程，可不调用） */
    fun registerListener(listener: HarvestListener): () -> Unit {
        extraListeners.addIfAbsent(listener)
        return { extraListeners.remove(listener) }
    }

    /** 逐个隔离派发：单监听器异常不影响其他监听器与收割主流程 */
    private fun dispatchToListeners(keyword: String?, links: List<String>) {
        if (links.isEmpty() || extraListeners.isEmpty()) return
        for (l in extraListeners) {
            runCatching { l.onHarvested(keyword, links) }
                .onFailure { Log.w(TAG, "金矿钩子监听器异常（已隔离，不影响收割）: ${it.message}") }
        }
    }

    private const val TAG = "GoldmineHooks"

    /** 落盘文件名（filesDir 根目录，与 answer_store.json 同级） */
    private const val FILE_NAME = "goldmine.json"

    /** 热词时间戳留存窗（48h = 24h 统计窗的两倍，读侧窗口边缘不丢数据） */
    private const val HOT_RETENTION_MS = 48L * 3600 * 1000

    /** 单词时间戳序列上限（防爆词无限膨胀；超出丢最旧） */
    private const val MAX_TS_PER_KW = 500

    /** 热词表容量硬顶（LRU 按最近出现驱逐；与 AnswerStore 关键词表同量级口径） */
    private const val MAX_HOT_KEYWORDS = 1_000

    /** 新发现表 TTL（7 天）：超过未再被搜出的按"不再新鲜"除名，角标自然消失 */
    private const val FRESH_TTL_MS = 7L * 24 * 3600 * 1000

    /** 新发现表容量硬顶（按 firstTs 最老驱逐） */
    private const val MAX_FRESH_ENTRIES = 200

    /** 变更多少次自动落盘（收割是低频动作，阈值小、落盘勤，与 AnswerStore 同值口径） */
    private const val FLUSH_AFTER_MUTATIONS = 10

    /** 待解析用户名快照条数（覆盖图谱 MAX_RESOLVE_QUEUE=2000 全量） */
    private const val MAX_RESOLVE_QUEUE_SNAPSHOT = 2_000

    /** 待解析用户名快照重建节流（毫秒）：首见判定逐链接调用，快照 1 分钟内复用 */
    private const val PENDING_NAMES_TTL_MS = 60_000L

    /**
     * R378 刀4 liveAsk 自词排除窗（30min=2×默认收割轮询 15min——覆盖腿B 回声
     * 最短路径：发词→下一轮收割把 bot 应答配回本人消息 ≤轮询间隔；更长轮询设置
     * 下回声逃逸 ≤1 事件/周期有界无环）。腿A（每次点击+1）恒在窗内必排除。
     */
    private const val LIVE_ASK_SELF_SUPPRESS_MS = 30L * 60 * 1000

    /** R378 刀4 自词登记表容量硬顶（窗外懒驱逐之上再保险，防极端长窗会话膨胀） */
    private const val MAX_LIVE_ASK_SENT = 200
}
