package dev.tgfeed.core.feed

import dev.tgfeed.core.store.LocalStore

/**
 * 智能主题房间：用观看行为把频道自动聚类成"房间"（无需用户手动建合集）。
 *
 * 算法——"观看时间段共现性"粗聚类：
 *  1. 取 watch_events 中最近 [ROOM_WINDOW_MS]（7 天）内的事件按频道分组；
 *     窗口外的旧行为不代表当前作息，直接丢弃（与频道权重的 7 天衰减口径一致）；
 *  2. 共现判定：对频道 a 的每条事件，若 b 存在一条事件与其时间差 ≤ [COOC_WINDOW_MS]
 *     （24 小时，"同一时段被连续观看"），记 a 侧一次共现；b 侧对称再计一次。
 *     两侧共现次数都 ≥ [COOC_MIN]（3 次）时 a、b 之间连一条边——
 *     要求双向都够 3 次，单侧短期刷量不构成房间；
 *  3. 房间 = 连边图的连通分量（并查集）；只保留 ≥2 个频道的分量——
 *     没有任何共现边的孤频道不成房间；
 *  4. 房间名 = 房内权重最高频道的标题 + "等 N 个频道"（N = 房间频道总数）；
 *     权重复用 [decayedChannelWeights]（时间衰减版，与混排/拉取配额同一份口味画像）；
 *  5. 房间按"房内最高权重"降序返回，最重要的房间排最前。
 *
 * 复用 [ChannelCollection] 作为房间模型：FeedViewModel.showSmartRoom 可直接拿
 * CollectionFeedSource 刷房间，无需新增 FeedSource。
 *
 * 依赖注入取舍：标题与被屏蔽频道经 provider 惰性取值（VM 侧现拼 channels/subscriptions），
 * 引擎不持有 Flow/VM 引用，保持纯计算、可单测。
 *
 * 性能：watch_events 上限 300 条、频道数远小于此，两两比较是微秒级；
 * 但内部仍是同步计算，请在 IO 线程调用 [buildSmartRooms]。
 */
class SmartRooms(
    private val store: LocalStore,
    private val titleProvider: () -> Map<Long, String>,
    private val blockedProvider: () -> Set<Long> = { emptySet() },
    // 互动加分（点赞/收藏/下载/PK）提供者：与混排/配额/发现页种子同一份口味画像
    private val boostProvider: () -> Map<Long, Double> = { emptyMap() },
) {
    /**
     * 现算全部房间（同步；读走 LocalStore 内存缓存，开销可忽略）。
     * 数据不足（窗口内事件 <2 个频道）或没有任何共现边时返回空列表——UI 隐藏"智能房间"区即可。
     */
    fun buildSmartRooms(): List<ChannelCollection> {
        val nowMs = System.currentTimeMillis()
        val blocked = blockedProvider()

        // 1. 7 天窗口内的事件按频道分组（ts<=0 的脏数据、被屏蔽频道不参与）
        val byChannel = store.loadWatchEvents()
            .asSequence()
            .filter { it.ts > 0L && nowMs - it.ts <= ROOM_WINDOW_MS && it.chatId !in blocked }
            .groupBy { it.chatId }
            .mapValues { (_, list) -> list.map { it.ts }.sorted() } // 升序，供二分
            .filterValues { it.isNotEmpty() }
        if (byChannel.size < 2) return emptyList()

        // 2. 两两判定共现边 + 并查集连边
        val ids = byChannel.keys.toList()
        val parent = HashMap<Long, Long>(ids.size * 2)
        ids.forEach { parent[it] = it }
        fun find(x: Long): Long {
            var r = x
            while (parent.getValue(r) != r) r = parent.getValue(r)
            var c = x // 路径压缩
            while (parent.getValue(c) != c) {
                val n = parent.getValue(c)
                parent[c] = r
                c = n
            }
            return r
        }
        fun union(a: Long, b: Long) {
            parent[find(a)] = find(b)
        }
        for (i in ids.indices) {
            for (j in i + 1 until ids.size) {
                val tsA = byChannel.getValue(ids[i])
                val tsB = byChannel.getValue(ids[j])
                // 双向各计一次：a 侧有多少条事件在 b 的 ±24h 内"有伴"，b 侧同理
                val coA = countCooccurring(tsA, tsB)
                if (coA < COOC_MIN) continue // a 侧都不够，b 侧不用算了
                val coB = countCooccurring(tsB, tsA)
                if (minOf(coA, coB) >= COOC_MIN) union(ids[i], ids[j])
            }
        }

        // 3. 连通分量 → 房间（≥2 个频道；孤频道不成房间）
        val groups = ids.groupBy { find(it) }.values.filter { it.size >= 2 }
        if (groups.isEmpty()) return emptyList()

        // 4. 权重与标题：权重复用混排同一份口径（含互动加分）；标题缺失时回退 "频道 <id>"
        val weights = decayedChannelWeights(store.loadWatchEvents(), nowMs, boostProvider())
        val titles = titleProvider()
        fun titleOf(id: Long): String = titles[id]?.takeIf { it.isNotBlank() } ?: "频道 $id"

        // 5. 命名 + 排序：房内按权重降序（第一个即权重最高者），房间按房内最高权重降序
        return groups
            .map { members -> members.sortedByDescending { weights[it] ?: EXPLORE_WEIGHT } }
            .sortedByDescending { room -> weights[room.first()] ?: EXPLORE_WEIGHT }
            .map { room ->
                ChannelCollection(
                    name = "${titleOf(room.first())} 等 ${room.size} 个频道",
                    channelIds = room,
                )
            }
    }

    /**
     * 统计 xs 中有多少条时间戳在 ys 的 ±[COOC_WINDOW_MS] 内"有伴"。
     * 两表均已升序：对每个 x 二分 ys 找插入点，只需检查左右两个最近邻居
     * （窗口内是否含任一 y 由最近邻居决定）。
     */
    private fun countCooccurring(xs: List<Long>, ys: List<Long>): Int {
        var n = 0
        for (x in xs) {
            val p = ys.binarySearch(x).let { if (it >= 0) it else -it - 1 } // 命中位或插入点
            val left = if (p - 1 >= 0) ys[p - 1] else null  // 最大的 <x
            val right = if (p < ys.size) ys[p] else null    // 最小的 ≥x
            val hit = (right != null && right - x <= COOC_WINDOW_MS) ||
                (left != null && x - left <= COOC_WINDOW_MS)
            if (hit) n++
        }
        return n
    }

    private companion object {
        /** 房间统计窗口：只看最近 7 天的观看事件（与权重衰减 λ 同口径） */
        const val ROOM_WINDOW_MS = 7L * 24 * 3600 * 1000

        /** 共现窗口：两条事件时间差 ≤ 24 小时视为"同一时段观看" */
        const val COOC_WINDOW_MS = 24L * 3600 * 1000

        /** 成边阈值：双向共现次数都 ≥ 3 次才连边（单侧短期刷量不构成房间） */
        const val COOC_MIN = 3
    }
}
