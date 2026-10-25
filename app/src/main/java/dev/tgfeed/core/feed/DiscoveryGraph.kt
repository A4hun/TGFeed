package dev.tgfeed.core.feed

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 资源嗅探图谱（DISCOVERY-GRAPH M1 前段）：频道间关系的有向图，本地持久化。
 *
 * 数据来源是 fetchFromChannel 每页消息的被动钩子（零额外网络请求）：
 *  - forwardInfo 溯源 → forward_src 边（搬运号的供出链）
 *  - caption/正文里的 t.me 互链 → link 边（t.me/c/ 形式直接得 chatId）
 *  - t.me/<name> 形式暂不解析（SearchPublicChat 太贵），记入 resolveQueue 待懒解析
 *
 * 落盘 `<storeDir>/discovery_graph.json`，格式：
 * ```json
 * {
 *   "nodes": {"-1001": {"title": "...", "firstSeen": 0, "lastSeen": 0, "memberCount": -1}},
 *   "edges": [{"from": -1001, "to": -1002, "type": "link|forward_src|forward_dst|comment|co_watch", "count": 3, "lastTs": 0}],
 *   "resolveQueue": [{"from": -1001, "name": "somechannel", "count": 2, "lastTs": 0}],
 *   "hubs": [-1001]
 * }
 * ```
 * （resolveQueue 为本实现相对文档 §7.3 协议的扩展字段：公开用户名待解析表；
 *  hubs 为 P1-b 扩展：forward_src 高 out-degree 搬运枢纽集合，sticky 打标、读侧降权；
 *  nodes.memberCount 为 P1-b 扩展：GetSupergroupFullInfo 懒加载供给，-1=未知。
 *  三者均为新增可选字段，老版本文件缺失时按空/未知处理，天然兼容。）
 *
 * 并发与落盘风格全套照 LocalStore：公开方法 @Synchronized；@Volatile 内存缓存，
 * 读路径命中后不碰磁盘；文件写"临时文件 + rename"原子落盘；单写入方语义，
 * 不做 mtime 校验。内存结构是唯一事实源，磁盘只是它的周期性快照：
 * 变更累计 [FLUSH_AFTER_MUTATIONS] 次自动落盘，进程退出前由网关 close() 调 [flush]。
 */
class DiscoveryGraph(storeDir: File) {

    // ---------------- 内存结构 ----------------

    /** 节点：title 可空（未补全标题时为空串），firstSeen/lastSeen 毫秒时间戳，
     *  memberCount 为 GetSupergroupFullInfo 懒加载供给的成员数（-1 = 未知，P1-b） */
    private class Node(var title: String, var firstSeen: Long, var lastSeen: Long, var memberCount: Int = -1)

    /** 边：命中累加 count、刷新 lastTs */
    private class Edge(var count: Int, var lastTs: Long)

    /** 待解析用户名：记录引用方 fromChatId，懒解析后可补写真实 link 边 */
    private class PendingName(var fromChatId: Long, var count: Int, var lastTs: Long)

    /**
     * P1-d 新边监听挂点（广场互链边共享的数据源）：仅在 [recordEdge] **新建**边
     * （非命中累加）时回调 (from, to, type)。@Volatile：注册方（FeedViewModel）
     * 与调用方（网关嗅探线程）不同线程。契约：监听必须轻量非阻塞，且不得回调
     * 本图谱的任何方法（recordEdge 持锁，重入会死锁）。
     */
    @Volatile
    var newEdgeListener: ((fromChatId: Long, toChatId: Long, type: String) -> Unit)? = null

    /** 一整张图的可变状态（仅在 @Synchronized 方法内访问） */
    private class GraphState(
        val nodes: HashMap<Long, Node> = HashMap(),
        // 边 key = "from|to|type"（from/to 是 Long 的十进制串，type 不含 '|'）
        val edges: HashMap<String, Edge> = HashMap(),
        // 待解析表 key = 小写用户名
        val resolveQueue: HashMap<String, PendingName> = HashMap(),
        // 搬运枢纽集合（sticky）：forward_src 出度 ≥ [HUB_OUT_DEGREE_THRESHOLD] 的引用方节点。
        // BRAINSTORM-GRAPH §1.3：搬运号是"路标"不是"目的地"，其票值在建边侧打标、
        // 读侧（topCandidates 预筛 + DiscoveryRanker W 表）统一降权
        val hubIds: LinkedHashSet<Long> = LinkedHashSet(),
    )

    /**
     * forward_src 出度底账（from → 指向的不同上游源数）：枢纽判定的计数依据。
     * 不持久化——load 时从边表扫描重建（O(边数) 一次），recordEdge 新建边时增量维护。
     * 仅在 @Synchronized 方法内访问。
     */
    private val fwdSrcOutDegree = HashMap<Long, Int>()

    private val dir = storeDir
    private val graphFile = File(dir, "discovery_graph.json")

    /** @Volatile 内存缓存（写时同步重建引用）；赋值即发布，读写均在 @Synchronized 内 */
    @Volatile
    private var stateCache: GraphState? = null

    /** 距上次落盘以来的变更次数（落盘节流计数器） */
    private var dirtyCount = 0

    // ---------------- 写入 ----------------

    /**
     * 记录一条有向边：命中已存在的 (from, to, type) 则 count+1 并刷 lastTs，
     * 否则新建。from == to 的自环直接丢弃（频道转自己旧帖/挂自己链接是噪声）。
     * 两端节点自动补建（title 留空，由 [recordNodeTitle] 后续补全）。
     */
    @Synchronized
    fun recordEdge(fromChatId: Long, toChatId: Long, type: String) {
        if (fromChatId == toChatId) return
        val now = System.currentTimeMillis()
        val st = loadLocked()
        val key = "$fromChatId|$toChatId|$type"
        val edge = st.edges[key]
        if (edge != null) {
            edge.count++
            edge.lastTs = now
        } else {
            st.edges[key] = Edge(1, now)
            // 枢纽打标（建边侧，BRAINSTORM-GRAPH §1.3）：forward_src 新边让引用方出度 +1，
            // 越 [HUB_OUT_DEGREE_THRESHOLD] 即把引用方记入 hubIds（sticky——一旦是搬运枢纽，
            // 历史边一并按枢纽降权，不逐边标记；读侧经 EdgeEvidence.hubFrom 消费）
            if (type == EDGE_FORWARD_SRC) {
                val deg = (fwdSrcOutDegree[fromChatId] ?: 0) + 1
                fwdSrcOutDegree[fromChatId] = deg
                if (deg >= HUB_OUT_DEGREE_THRESHOLD) st.hubIds += fromChatId
            }
            // P1-d：新边监听（广场互链边共享挂点）。监听契约见 newEdgeListener 注释——
            // 轻量非阻塞、不得回调本图谱（此处持锁）。命中累加不触发（共享的是"新发现"）
            runCatching { newEdgeListener?.invoke(fromChatId, toChatId, type) }
        }
        touchNodeLocked(st, fromChatId, now)
        touchNodeLocked(st, toChatId, now)
        markDirtyLocked()
    }

    /**
     * 记录一条 t.me/<name> 形式的待解析链接（公开用户名 → chatId 需 SearchPublicChat，
     * 太贵，懒解析；先攒引用方证据，解析成功后可补写 link 边）。
     */
    @Synchronized
    fun recordPendingName(fromChatId: Long, name: String) {
        val key = name.trim().removePrefix("@").lowercase()
        if (key.isEmpty()) return
        val now = System.currentTimeMillis()
        val st = loadLocked()
        val p = st.resolveQueue[key]
        if (p != null) {
            p.count++
            p.lastTs = now
            p.fromChatId = fromChatId // 引用方记最近一个即可（证据用途）
        } else {
            st.resolveQueue[key] = PendingName(fromChatId, 1, now)
        }
        touchNodeLocked(st, fromChatId, now)
        markDirtyLocked()
    }

    /** 补全节点标题（已知才调；title 有变化才算一次变更，避免刷屏触发落盘） */
    @Synchronized
    fun recordNodeTitle(chatId: Long, title: String) {
        if (title.isBlank()) return
        val st = loadLocked()
        val now = System.currentTimeMillis()
        val node = st.nodes[chatId]
        if (node == null) {
            st.nodes[chatId] = Node(title, now, now)
            markDirtyLocked()
        } else if (node.title != title) {
            node.title = title
            markDirtyLocked()
        }
    }

    /**
     * 补全节点成员数（P1-b：GetSupergroupFullInfo 懒加载供给，DiscoveryRanker 的 Q/A 数据源）。
     * memberCount ≤ 0（未知/隐藏成员）不记；数值有变化才算一次变更（同 recordNodeTitle 的防刷口径）。
     */
    @Synchronized
    fun recordNodeMemberCount(chatId: Long, memberCount: Int) {
        if (memberCount <= 0) return
        val st = loadLocked()
        val now = System.currentTimeMillis()
        val node = st.nodes[chatId]
        if (node == null) {
            st.nodes[chatId] = Node("", now, now, memberCount)
            markDirtyLocked()
        } else if (node.memberCount != memberCount) {
            node.memberCount = memberCount
            markDirtyLocked()
        }
    }

    // ---------------- 读取 ----------------

    /**
     * 候选打分（轻量预筛版）：按入边聚合评分，取前 [limit] 名并**返回证据明细**。
     *
     * 返回的每个 [GraphCandidate] 携带：节点标题、firstSeen、全部入边的
     * （引用方 from / 边类型 type / 命中次数 count / 最近命中 lastTs）——
     * 这是 DiscoveryRanker 打分（引用方权重 × 类型权重 × log1p(count)、新鲜度 τ）
     * 与发现页证据文案（「你的 N 个订阅频道引用过它」）的共同数据源。
     *
     * 排序仍用 M1 的轻量口径（Σ count × 边类型权重）只做**预筛截断**：
     * 真正的排序分由 DiscoveryRanker 用完整公式重算，这里的名次不进 UI。
     */
    @Synchronized
    fun topCandidates(limit: Int = 30): List<GraphCandidate> {
        val st = loadLocked()
        val score = HashMap<Long, Int>()
        val evidence = HashMap<Long, MutableList<EdgeEvidence>>()
        for ((key, e) in st.edges) {
            // key = "from|to|type"
            val from = key.substringBefore('|').toLongOrNull() ?: continue
            val to = key.substringAfter('|').substringBefore('|').toLongOrNull() ?: continue
            val type = key.substringAfterLast('|')
            // 枢纽降权（预筛层）：forward_src 引用方已打枢纽标 → 票值与 link 同档，
            // 防搬运号高 out-degree 把预筛 Top-N 刷成"它转过的所有号"（§1.3）
            val w = when (type) {
                EDGE_FORWARD_SRC -> if (from in st.hubIds) 1 else 3
                EDGE_FORWARD_DST -> 2
                else -> 1 // link/co_watch 及未知类型按 1 计（前向兼容未来新增边类型）
            }
            score.merge(to, e.count * w, Int::plus)
            evidence.getOrPut(to) { ArrayList() } +=
                EdgeEvidence(from, type, e.count, e.lastTs, hubFrom = from in st.hubIds)
        }
        return score.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { (id, _) ->
                val node = st.nodes[id]
                GraphCandidate(
                    chatId = id,
                    title = node?.title.orEmpty(),
                    firstSeen = node?.firstSeen ?: 0L,
                    memberCount = node?.memberCount ?: -1,
                    edges = evidence[id].orEmpty().sortedByDescending { it.count },
                )
            }
    }

    /**
     * 按边类型取候选（P2 金矿：group_search 收割边的专项预筛通道）。
     *
     * 为什么需要：[topCandidates] 是全边类型聚合 Top-N——收割刚起步时 group_search 边
     * 量少票轻，挤不进总榜就被预筛截断，发现页永远吃不到收割成果。这里给单一边类型
     * 一条独立通道：只统计该类型入边，Σ count 降序（同类型无需类型权重）。
     * 返回的 [GraphCandidate] 证据明细只含该类型的入边；其余字段语义与 [topCandidates] 全同。
     */
    @Synchronized
    fun topCandidatesByEdgeType(type: String, limit: Int): List<GraphCandidate> {
        val st = loadLocked()
        val score = HashMap<Long, Int>()
        val evidence = HashMap<Long, MutableList<EdgeEvidence>>()
        for ((key, e) in st.edges) {
            // key = "from|to|type"
            val edgeType = key.substringAfterLast('|')
            if (edgeType != type) continue
            val from = key.substringBefore('|').toLongOrNull() ?: continue
            val to = key.substringAfter('|').substringBefore('|').toLongOrNull() ?: continue
            score.merge(to, e.count, Int::plus)
            evidence.getOrPut(to) { ArrayList() } +=
                EdgeEvidence(from, edgeType, e.count, e.lastTs, hubFrom = from in st.hubIds)
        }
        return score.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { (id, _) ->
                val node = st.nodes[id]
                GraphCandidate(
                    chatId = id,
                    title = node?.title.orEmpty(),
                    firstSeen = node?.firstSeen ?: 0L,
                    memberCount = node?.memberCount ?: -1,
                    edges = evidence[id].orEmpty().sortedByDescending { it.count },
                )
            }
    }

    /**
     * 全图只读快照（R143 口味星图数据源）：节点（标题/成员数/最近活跃）+ 边（方向/类型/权重）
     * 的浅拷贝投影。与 [topCandidates] 的"预筛截断"不同，本口不排序不截断——
     * 星图侧要自己挑节点、合并无向边（挑多少颗星、连多长的线是展示决策，归 UI 层），
     * 这里只做数据搬运不做裁剪。单遍扫内存结构 O(N+E)，只读不落盘。
     */
    @Synchronized
    fun snapshot(): GraphSnapshot {
        val st = loadLocked()
        val nodes = ArrayList<GraphSnapshotNode>(st.nodes.size)
        for ((id, n) in st.nodes) {
            nodes += GraphSnapshotNode(id, n.title, n.memberCount, n.lastSeen)
        }
        val edges = ArrayList<GraphSnapshotEdge>(st.edges.size)
        for ((key, e) in st.edges) {
            // key = "from|to|type"
            val from = key.substringBefore('|').toLongOrNull() ?: continue
            val to = key.substringAfter('|').substringBefore('|').toLongOrNull() ?: continue
            edges += GraphSnapshotEdge(
                from, to, key.substringAfterLast('|'), e.count, e.lastTs,
                hubFrom = from in st.hubIds,
            )
        }
        return GraphSnapshot(nodes, edges)
    }

    /**
     * 引用方出度快照（只读，P1-c Adamic-Adar 共同邻居加分的图侧数据源）：
     * 对 [fromIds] 内每个节点统计它指向的**不同目标数**——同一对 (from,to) 跨边类型
     * 去重只算一度（link+forward_src 双关系不代表指向面更宽）。
     * 单遍扫边表 O(E)；不在 [fromIds] 的节点不进结果，无出边的节点缺席
     * （调用方按"出度未知"跳过该共同邻居，不按 0 处理——AA 分母取 ln(outDeg+1)，
     * 缺席即不加这一票，避免未知节点被当成"指向面最窄"而拿到最大权重）。
     */
    @Synchronized
    fun outDegreesOf(fromIds: Set<Long>): Map<Long, Int> {
        if (fromIds.isEmpty()) return emptyMap()
        val st = loadLocked()
        val targets = HashMap<Long, HashSet<Long>>()
        for (key in st.edges.keys) {
            // key = "from|to|type"
            val from = key.substringBefore('|').toLongOrNull() ?: continue
            if (from !in fromIds) continue
            val to = key.substringAfter('|').substringBefore('|').toLongOrNull() ?: continue
            targets.getOrPut(from) { HashSet() } += to
        }
        return targets.mapValues { it.value.size }
    }

    /**
     * 指定引用方集合的某类出边目标（R46 搜索联动插队的图谱证据口）：
     * "这些证据群（from）经 [type] 边指向过哪些频道（to）"——答案库命中答案的
     * 来源群 + group_search 边 = 同群群友搜索证据指向的关联频道。
     * 返回按 Σ count 降序的 toChatId 列表（截断到 [limit]）；无命中返回空表。
     * 单遍扫边表 O(E)，只读不落盘。
     */
    @Synchronized
    fun relatedByEdgeType(fromIds: Set<Long>, type: String, limit: Int): List<Long> {
        if (fromIds.isEmpty() || limit <= 0) return emptyList()
        val st = loadLocked()
        val score = HashMap<Long, Int>()
        for ((key, e) in st.edges) {
            // key = "from|to|type"
            if (key.substringAfterLast('|') != type) continue
            val from = key.substringBefore('|').toLongOrNull() ?: continue
            if (from !in fromIds) continue
            val to = key.substringAfter('|').substringBefore('|').toLongOrNull() ?: continue
            score.merge(to, e.count, Int::plus)
        }
        return score.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    /**
     * 近窗口新增边计数（P1-d 周榜切片数据源）：对 [toIds] 内每个节点统计
     * lastTs ≥ [sinceTs] 的入边**条数**（不按 count 累计值——"近 7 天新增"语义是
     * 窗口内活跃过的连接数；老边在窗口内被刷新 lastTs 同样算活跃，一并计入）。
     * 缺席 = 窗口内无活跃边（调用方按 0 处理）。单遍扫边表 O(E)，只读不落盘。
     */
    @Synchronized
    fun recentEdgeCounts(toIds: Set<Long>, sinceTs: Long): Map<Long, Int> {
        if (toIds.isEmpty()) return emptyMap()
        val st = loadLocked()
        val out = HashMap<Long, Int>()
        for ((key, e) in st.edges) {
            if (e.lastTs < sinceTs) continue
            // key = "from|to|type"
            val to = key.substringAfter('|').substringBefore('|').toLongOrNull() ?: continue
            if (to in toIds) out.merge(to, 1, Int::plus)
        }
        return out
    }

    // ---------------- 落盘 ----------------

    /** 立即把内存状态原子落盘（进程退出前由网关 close() 调用；平时靠阈值自动触发） */
    @Synchronized
    fun flush() {
        flushLocked(loadLocked())
        dirtyCount = 0
    }

    /** 懒加载：缓存命中直接返回，否则读盘重建（文件损坏/缺失误按空图处理，不崩溃） */
    private fun loadLocked(): GraphState {
        stateCache?.let { return it }
        val st = GraphState()
        runCatching {
            val text = if (graphFile.isFile) graphFile.readText() else null
            if (!text.isNullOrEmpty()) {
                val root = JSONObject(text)
                // 节点逐条防御：单条坏记录只跳过该条（LocalStore 同款口径）
                root.optJSONObject("nodes")?.let { nodesObj ->
                    for (k in nodesObj.keys()) {
                        runCatching {
                            val id = k.toLong()
                            val o = nodesObj.getJSONObject(k)
                            st.nodes[id] = Node(
                                o.optString("title", ""),
                                o.optLong("firstSeen", 0L),
                                o.optLong("lastSeen", 0L),
                                // P1-b 新增字段：老数据无 memberCount，缺省 -1（未知），天然兼容
                                o.optInt("memberCount", -1),
                            )
                        }
                    }
                }
                root.optJSONArray("edges")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        runCatching {
                            val o = arr.getJSONObject(i)
                            val from = o.getLong("from")
                            val to = o.getLong("to")
                            val type = o.getString("type")
                            if (from != to) {
                                st.edges["$from|$to|$type"] =
                                    Edge(o.optInt("count", 1), o.optLong("lastTs", 0L))
                            }
                        }
                    }
                }
                root.optJSONArray("resolveQueue")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        runCatching {
                            val o = arr.getJSONObject(i)
                            val name = o.getString("name").lowercase()
                            if (name.isNotEmpty()) {
                                st.resolveQueue[name] = PendingName(
                                    o.optLong("from", 0L),
                                    o.optInt("count", 1),
                                    o.optLong("lastTs", 0L),
                                )
                            }
                        }
                    }
                }
                root.optJSONArray("hubs")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val id = arr.optLong(i, 0L)
                        if (id != 0L) st.hubIds += id // 坏条目跳过
                    }
                }
            }
        }
        // forward_src 出度底账从边表重建（不持久化的派生结构）
        rebuildFwdSrcOutDegreeLocked(st)
        stateCache = st
        return st
    }

    /** 出度底账全量重建：只在磁盘加载路径调用一次（之后由 recordEdge 增量维护） */
    private fun rebuildFwdSrcOutDegreeLocked(st: GraphState) {
        fwdSrcOutDegree.clear()
        for (key in st.edges.keys) {
            // key = "from|to|type"，尾部类型段匹配 forward_src 才计数
            if (!key.endsWith("|$EDGE_FORWARD_SRC")) continue
            val from = key.substringBefore('|').toLongOrNull() ?: continue
            fwdSrcOutDegree.merge(from, 1, Int::plus)
        }
        // 老数据回填枢纽标：出度已越阈值但 hubIds 还没记上的（升级前攒下的边）补标
        for ((from, deg) in fwdSrcOutDegree) {
            if (deg >= HUB_OUT_DEGREE_THRESHOLD) st.hubIds += from
        }
    }

    private fun touchNodeLocked(st: GraphState, chatId: Long, now: Long) {
        val node = st.nodes[chatId]
        if (node == null) {
            st.nodes[chatId] = Node("", now, now)
        } else {
            node.lastSeen = now
        }
    }

    /** 变更计数 +1，累计到阈值自动落盘（写放大控制：一页消息最多触发一次磁盘写） */
    private fun markDirtyLocked() {
        dirtyCount++
        if (dirtyCount >= FLUSH_AFTER_MUTATIONS) {
            flushLocked(loadLocked())
            dirtyCount = 0
        }
    }

    /** 序列化 + 原子写。容量超限先按 lastTs/lastSeen 淘汰最旧（文档 §7.3 口径） */
    private fun flushLocked(st: GraphState) {
        runCatching {
            evictLocked(st)
            val root = JSONObject()
            val nodesObj = JSONObject()
            for ((id, n) in st.nodes) {
                nodesObj.put(id.toString(), JSONObject()
                    .put("title", n.title)
                    .put("firstSeen", n.firstSeen)
                    .put("lastSeen", n.lastSeen)
                    .put("memberCount", n.memberCount))
            }
            root.put("nodes", nodesObj)
            val edgesArr = JSONArray()
            for ((key, e) in st.edges) {
                val from = key.substringBefore('|').toLongOrNull() ?: continue
                val to = key.substringAfter('|').substringBefore('|').toLongOrNull() ?: continue
                val type = key.substringAfterLast('|')
                edgesArr.put(JSONObject()
                    .put("from", from)
                    .put("to", to)
                    .put("type", type)
                    .put("count", e.count)
                    .put("lastTs", e.lastTs))
            }
            root.put("edges", edgesArr)
            val queueArr = JSONArray()
            for ((name, p) in st.resolveQueue) {
                queueArr.put(JSONObject()
                    .put("from", p.fromChatId)
                    .put("name", name)
                    .put("count", p.count)
                    .put("lastTs", p.lastTs))
            }
            root.put("resolveQueue", queueArr)
            // 枢纽集合（sticky）：forward_src 高 out-degree 引用方名单，读侧降权用
            root.put("hubs", JSONArray().apply { st.hubIds.forEach { put(it) } })
            writeText(graphFile, root.toString())
        }
    }

    /** 容量淘汰：边超上限按 lastTs 留新；节点超上限按 lastSeen 留新 */
    private fun evictLocked(st: GraphState) {
        if (st.edges.size > MAX_EDGES) {
            val keep = st.edges.entries
                .sortedByDescending { it.value.lastTs }
                .take(MAX_EDGES)
            st.edges.clear()
            keep.forEach { st.edges[it.key] = it.value }
        }
        if (st.nodes.size > MAX_NODES) {
            val keep = st.nodes.entries
                .sortedByDescending { it.value.lastSeen }
                .take(MAX_NODES)
            st.nodes.clear()
            keep.forEach { st.nodes[it.key] = it.value }
        }
        // 枢纽表跟随节点淘汰收口：被淘汰节点的枢纽标一并清（sticky 但不成 orphan）
        if (st.hubIds.isNotEmpty()) st.hubIds.retainAll(st.nodes.keys)
        if (st.resolveQueue.size > MAX_RESOLVE_QUEUE) {
            val keep = st.resolveQueue.entries
                .sortedByDescending { it.value.lastTs }
                .take(MAX_RESOLVE_QUEUE)
            st.resolveQueue.clear()
            keep.forEach { st.resolveQueue[it.key] = it.value }
        }
    }

    /** 先写临时文件再 rename，保证落盘原子性（LocalStore 同款） */
    private fun writeText(file: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            // rename 失败（极少见）时退化为直接覆盖写
            runCatching { file.writeText(text) }
            tmp.delete()
        }
    }

    // ---------------- 发现页配套：待解析队列读取/出队 + 节点标题读取（懒解析协议） ----------------

    /**
     * 取待解析用户名前 [limit] 个（引用计数降序、同数按 lastTs 新先——被引用最多的名字最值得先解析）。
     * 返回 (小写用户名, 最近引用方 chatId) 列表；供网关懒解析通道
     * （TdLibGateway.resolvePendingChannels）消费，纯读不出队。
     */
    @Synchronized
    fun pendingResolveNames(limit: Int): List<Pair<String, Long>> {
        val st = loadLocked()
        return st.resolveQueue.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, PendingName>> { it.value.count }
                    .thenByDescending { it.value.lastTs },
            )
            .take(limit)
            .map { it.key to it.value.fromChatId }
    }

    /**
     * 把一个待解析用户名移出队列。解析成功补写真边后、或解析失败时都由调用方触发——
     * 失败也移除是刻意的：SearchPublicChat 对不存在/不可见名是稳定失败，重试无意义，
     * 留在队列里只会形成死信堆积、挤占 MAX_RESOLVE_QUEUE 上限里的真线索。
     */
    @Synchronized
    fun removePendingName(name: String) {
        val key = name.trim().removePrefix("@").lowercase()
        if (key.isEmpty()) return
        val st = loadLocked()
        if (st.resolveQueue.remove(key) != null) markDirtyLocked()
    }

    /** 读节点标题（发现页候选卡展示用）；未知节点/标题未补全（空串）返回 null */
    @Synchronized
    fun nodeTitle(chatId: Long): String? =
        loadLocked().nodes[chatId]?.title?.takeIf { it.isNotBlank() }

    /**
     * 节点入边证据合计（AdChannelClassifier 的 F1 规模特征数据源）：
     * 指向 [chatId] 的全部类型入边 count 累加——"该频道被全图观测到的引用/溯源强度"，
     * 作"频道总资源数很少"的本地可观测代理（真资源大频道会被反复搜出/互链/转发）。
     * 单遍扫边表 O(E)；未知节点返回 0。只读不落盘。
     */
    @Synchronized
    fun evidenceCountOf(chatId: Long): Int {
        val st = loadLocked()
        var sum = 0
        for ((key, e) in st.edges) {
            // key = "from|to|type"
            val to = key.substringAfter('|').substringBefore('|').toLongOrNull() ?: continue
            if (to == chatId) sum += e.count
        }
        return sum
    }

    /** 读节点成员数（AdChannelClassifier 的 F1 辅助特征）；未知/未补全返回 -1 */
    @Synchronized
    fun memberCountOf(chatId: Long): Int = loadLocked().nodes[chatId]?.memberCount ?: -1

    companion object {
        /** 边类型：互链（t.me 引用） */
        const val EDGE_LINK = "link"

        /** 边类型：转发溯源（forwardInfo.origin 指向上游源频道） */
        const val EDGE_FORWARD_SRC = "forward_src"

        /** 边类型：转发去向（GetMessagePublicForwards，M2 主动路径使用） */
        const val EDGE_FORWARD_DST = "forward_dst"

        /** 边类型：评论区出没（评论链接/@mention/MessageSenderChat，P1 使用，先占位进权重表） */
        const val EDGE_COMMENT = "comment"

        /** 边类型：搜索反哺（搜索命中频道沉淀，from=0 无引用方——弱证据） */
        const val EDGE_SEARCH_HIT = "search_hit"

        /**
         * 边类型：观看序列共现（P1-b，BRAINSTORM-GRAPH B4）——同会话相邻观看的两频道
         * 互建双向弱边（图谱是有向图，无向共现用两条反向边表达）；纯本地聚合零请求，
         * 边权由 DiscoveryRanker 的 TW 表给 0.3（低于一切主动互引边）
         */
        const val EDGE_CO_WATCH = "co_watch"

        /**
         * 搬运枢纽阈值：forward_src 出度（指向的不同上游源数）达到即把引用方记入 hubIds
         * （建边侧打标，BRAINSTORM-GRAPH §1.3：防搬运号的高 out-degree 霸榜）
         */
        private const val HUB_OUT_DEGREE_THRESHOLD = 15

        /** 落盘节流：累计多少次变更写一次盘（约一页消息的量级） */
        private const val FLUSH_AFTER_MUTATIONS = 25

        /** 节点上限（文档 §7.3：5000），超限按 lastSeen 淘汰最旧 */
        private const val MAX_NODES = 5000

        /** 边上限（文档 §7.3：20000），超限按 lastTs 淘汰最旧 */
        private const val MAX_EDGES = 20000

        /** 待解析用户名上限，超限按 lastTs 淘汰最旧 */
        private const val MAX_RESOLVE_QUEUE = 2000
    }

    /**
     * 一条入边的证据明细：谁（fromChatId）以什么关系（type）指向候选几次（count）、
     * 最近何时（lastTs）。fromChatId == 0 表示无引用方的弱证据边（如 search_hit）。
     * hubFrom = 引用方是搬运枢纽（forward_src 高 out-degree，建边侧打标产物），
     * ranker 据此对枢纽票值降权；老调用路径不填时缺省 false，前向兼容。
     */
    data class EdgeEvidence(
        val fromChatId: Long,
        val type: String,
        val count: Int,
        val lastTs: Long,
        val hubFrom: Boolean = false,
    )

    /**
     * 图谱候选（带证据明细）：DiscoveryRanker 打分与发现页证据文案的输入。
     * edges 按命中次数降序；title 未补全时为空串；
     * memberCount 为懒加载供给的成员数（-1 = 未知，ranker Q 取中性档）。
     */
    data class GraphCandidate(
        val chatId: Long,
        val title: String,
        val firstSeen: Long, // 节点首见毫秒时间戳（"新节点优先"探索加分用）
        val memberCount: Int = -1, // 节点成员数（GetSupergroupFullInfo 懒加载供给；-1 = 未知）
        val edges: List<EdgeEvidence>,
    )

    /** 快照节点（R143 口味星图）：title 未补全时为空串；memberCount -1 = 未知 */
    data class GraphSnapshotNode(
        val chatId: Long,
        val title: String,
        val memberCount: Int,
        val lastSeenMs: Long,
    )

    /** 快照边（R143 口味星图）：有向边原样投影（含 count 权重与枢纽标）；无向合并归 UI 层 */
    data class GraphSnapshotEdge(
        val fromChatId: Long,
        val toChatId: Long,
        val type: String,
        val count: Int,
        val lastTs: Long,
        val hubFrom: Boolean,
    )

    /** 全图快照（[snapshot] 的产物）：节点+边的只读列表，纯数据搬运不含展示决策 */
    data class GraphSnapshot(
        val nodes: List<GraphSnapshotNode>,
        val edges: List<GraphSnapshotEdge>,
    )
}
