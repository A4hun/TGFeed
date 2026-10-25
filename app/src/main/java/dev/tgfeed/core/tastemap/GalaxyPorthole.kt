package dev.tgfeed.core.tastemap

import dev.tgfeed.core.feed.DiscoveryGraph
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * R301 星图舷窗/行星世界卡 共享纯函数件（design-dual-form.md §7 Q3 收口位——
 * 「舷窗邻域小装配（core/tastemap 新纯函数件——信息卡邻星/海报/舷窗三方消费同一
 * 邻域口径）」的落地）。
 *
 * 职责四件（全部纯 Kotlin 零 android 依赖——JVM 单测直测，R235/R248/R269 同款纪律）：
 *  1. **邻域小装配**：以某恒星为根的 1-2 跳邻域（DiscoveryGraph 快照边合并→权重表→
 *     确定性环形布局），O(邻域) 毫秒级，与全图装配零共享状态——星图从未开过也能出
 *     舷窗（设计 §2.2 关键裁决）。边合并口径（无向对求和、search_hit 弱证据剔除）与
 *     全图装配 layoutTasteMap 内联块同规则；本件是舷窗/世界卡邻星数据的**单一出处**
 *     （全图装配那段内联块的收敛改造挂账 r301_notes，不在本刀）。
 *  2. **快照指纹 key**：邻域内容签名（中心星+邻星 id/权重+tag）FNV-1a——舷窗位图
 *     磁盘缓存的失效锚（R254 指纹族思路：内容变才重渲，陈值无害）。
 *  3. **tag 时期弧**：(频道, tag) 条目的月度分桶（ChannelEraPolicy.monthlyOf 复用族）
 *     +峰月判词——舷窗时期行与世界卡时期弧同一尺子。
 *  4. **SQL 形状锚**：[tagEraSql] 纯函数（R269 tagItemsJoinSql 先例——27dd5a8 误删
 *     FROM 的教训：SQL 字符串必须有 JVM 可测的形状锚）。
 */
object GalaxyPorthole {

    // ==================== 邻域小装配 ====================

    /** 舷窗邻星上限（快照与 pills 共用密度口径——信息卡相邻恒星 take(5) 同族纪律） */
    const val MAX_NEIGHBORS = 10

    /** 邻星 pills 展示条数（横滚；快照节点数=MAX_NEIGHBORS） */
    const val PILLS_SHOW = 5

    /**
     * 图谱快照边的最小投影（core.tastemap 不反向依赖 core.feed 的 GraphSnapshotEdge
     * 之外的任何实现细节——装载侧逐字段映射）。
     */
    class RawEdge(val from: Long, val to: Long, val type: String, val count: Int)

    /**
     * 以 [centerChatId] 为根的 1 跳邻星权重表（合并边权降序、chatId 升序破平=确定性
     * 序）。口径与全图装配的连线合并同规则：无向对求和、search_hit 弱证据边不进、
     * 自环/0 号位剔除。
     */
    fun mergedNeighborWeights(centerChatId: Long, edges: List<RawEdge>): List<Pair<Long, Int>> {
        if (centerChatId == 0L) return emptyList()
        val acc = HashMap<Long, Int>()
        for (e in edges) {
            if (e.type == DiscoveryGraph.EDGE_SEARCH_HIT) continue
            val other = when (centerChatId) {
                e.from -> e.to
                e.to -> e.from
                else -> continue
            }
            if (other == 0L || other == centerChatId) continue
            acc.merge(other, e.count, Int::plus)
        }
        return acc.entries
            .sortedWith(compareByDescending<Map.Entry<Long, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }
    }

    /**
     * 2 跳边筛选（邻居互连——快照上的「关联弧」）：两端都在 [keep] 集合内且不含中心星
     * 的合并无向对（打包键=min shl 32 or max，负数 chatId 无打包碰撞——Long 位打包）。
     */
    fun secondHopPairs(edges: List<RawEdge>, keep: Set<Long>, centerChatId: Long): Set<Long> {
        if (keep.size < 2) return emptySet()
        val acc = HashMap<Long, Int>()
        for (e in edges) {
            if (e.type == DiscoveryGraph.EDGE_SEARCH_HIT) continue
            if (e.from == e.to || e.from == centerChatId || e.to == centerChatId) continue
            if (e.from !in keep || e.to !in keep) continue
            val a = minOf(e.from, e.to)
            val b = maxOf(e.from, e.to)
            acc.merge((a shl 32) or (b and 0xFFFF_FFFFL), e.count, Int::plus)
        }
        return acc.keys
    }

    // ==================== 舷窗邻域布局（确定性——同输入同图，ShareCard 海报系坐标） ====================

    /** 世界系边长镜像（ShareCard.TASTE_WORLD=1000 海报口径；core 内注释同步） */
    const val WORLD = 1000f

    /** 中心星锚点（世界中心——tasteProject 以 (500,500) 为局部系原点） */
    const val CENTER = 500f

    /** 邻星双环半径（世界单位）：内环 top 半数强关联、外环余者——近密远疏的读法 */
    const val RING_INNER = 230f
    const val RING_OUTER = 330f

    /** 快照取景余量（最外环+星半径外再多留——绘制端 scale 计算的单一出处） */
    const val FRAME_EXTENT = 395f

    /** 邻星视觉填充比（快照半边幅 × 本值 = 邻域图幅——绘制端/覆盖层共用口径） */
    const val SNAPSHOT_FIT = 0.80f

    /** 舷窗快照节点（z 恒 0=正俯视正交感；radius/colorArgb/alpha 直喂 ShareCard 绘制族） */
    class PortholeNode(
        val chatId: Long,
        val title: String,
        val x: Float,
        val y: Float,
        val radius: Float,
        val colorArgb: Int,
        val weight: Int,
        val isCenter: Boolean,
    )

    /** 舷窗快照边（端点=nodes 下标——绘制端零查表） */
    class PortholeEdge(val aIdx: Int, val bIdx: Int, val alpha: Float)

    /** 舷窗邻域布局成品（nodes[0] 恒为中心星） */
    class PortholeLayout(
        val centerChatId: Long,
        val nodes: List<PortholeNode>,
        val edges: List<PortholeEdge>,
    )

    /** 中心星体色（暖星金——TgFeedTokens.StarYellow 同族色值，core 不引 compose） */
    const val CENTER_COLOR = 0xFFFFD37E.toInt()

    /** 邻星体色（冷白微蓝——ShareCard 背景星点冷白族同语） */
    const val NEIGHBOR_COLOR = 0xFF9FB6E8.toInt()

    private const val GOLDEN_ANGLE = 2.3999632f // 黄金角（相邻邻星不叠向）

    /**
     * 邻域确定性环形布局：中心星锚世界中心；邻星按权重降序金角散布双环（偶位内环/
     * 奇位外环——强关联靠内），相位起点=中心星 chatId 哈希（同输入同图的不变式锚）；
     * 半径 ∝ sqrt(权重/最大权重)（面积隐喻：关联证据体量）；边=中心-邻星全连 +
     * [secondHop] 邻间互连（alpha 低于主边=次证据视觉降级）。
     */
    fun layoutNeighborhood(
        centerChatId: Long,
        centerTitle: String,
        neighbors: List<Pair<Long, Int>>, // (chatId, 合并边权) 任意序——本函数内自重排
        titles: Map<Long, String>,
        secondHop: Set<Long> = emptySet(),
    ): PortholeLayout {
        val ranked = neighbors
            .filter { it.first != 0L && it.first != centerChatId && it.second > 0 }
            .sortedWith(compareByDescending<Pair<Long, Int>> { it.second }.thenBy { it.first })
            .take(MAX_NEIGHBORS)
        val wMax = ranked.maxOfOrNull { it.second }?.toFloat() ?: 1f
        val jitter = ((centerChatId xor (centerChatId ushr 32)) % 6283L).toFloat() / 1000f
        val nodes = ArrayList<PortholeNode>(ranked.size + 1)
        nodes += PortholeNode(
            centerChatId, centerTitle, CENTER, CENTER, 26f, CENTER_COLOR, 0, true,
        )
        ranked.forEachIndexed { i, (id, w) ->
            val ang = jitter + i * GOLDEN_ANGLE
            val ring = if (i % 2 == 0) RING_INNER else RING_OUTER
            val r = 10f + 14f * sqrt(w.toFloat() / wMax)
            nodes += PortholeNode(
                id, titles[id].orEmpty(),
                CENTER + ring * cos(ang), CENTER + ring * sin(ang),
                r, NEIGHBOR_COLOR, w, false,
            )
        }
        val idxById = HashMap<Long, Int>(nodes.size * 2)
        nodes.forEachIndexed { i, n -> idxById[n.chatId] = i }
        val edges = ArrayList<PortholeEdge>(nodes.size * 2)
        for (i in 1 until nodes.size) {
            val w01 = nodes[i].weight.toFloat() / wMax
            edges += PortholeEdge(0, i, 0.28f + 0.5f * w01)
        }
        for (key in secondHop) {
            val a = key shr 32 // 高位还原（Long 算术右移——负数 chatId 符号天然保留）
            val b = (key shl 32).shr(32) // 低位还原（符号扩展低 32 位）
            val ia = idxById[a] ?: continue
            val ib = idxById[b] ?: continue
            edges += PortholeEdge(ia, ib, 0.16f)
        }
        return PortholeLayout(centerChatId, nodes, edges)
    }

    // ==================== 快照指纹 key（位图磁盘缓存失效锚——R254 指纹族思路） ====================

    /**
     * 舷窗快照内容指纹（FNV-1a 64）：中心星+tag+邻星 (id,权重) 序——邻域内容变才
     * 重渲（陈值无害：邻域图是静态快照语义，与金矿 6h TTL 同族口径）。
     */
    fun snapshotCacheKey(centerChatId: Long, tag: String?, neighbors: List<Pair<Long, Int>>): String {
        var h = -3750763034362895579L // FNV-1a 64 offset
        fun mix(v: Long) {
            var x = v
            for (i in 0 until 8) {
                h = h xor (x and 0xFF)
                h *= 1099511628211L
                x = x ushr 8
            }
        }
        mix(centerChatId)
        mix((tag?.hashCode() ?: 0).toLong())
        val ranked = neighbors
            .sortedWith(compareByDescending<Pair<Long, Int>> { it.second }.thenBy { it.first })
            .take(MAX_NEIGHBORS)
        for ((id, w) in ranked) {
            mix(id)
            mix(w.toLong())
        }
        return h.toString(16)
    }

    // ==================== tag 时期弧（舷窗时期行/世界卡时期弧同一尺子） ====================

    /** 时期弧月窗（近 N 个月——世界卡弧与舷窗行共用口径） */
    const val ERA_MONTHS = 12

    /** 时期弧条数上限（SQL LIMIT——频道级 tag 爆量防御；月度分桶的有界原料） */
    const val ERA_ROW_CAP = 5000

    /**
     * (频道, tag) 时期弧查询 SQL 形状锚（**机检纯函数**——R269 tagItemsJoinSql 先例：
     * 27dd5a8 误删 FROM 全设备空召回的教训=SQL 字符串必须有 JVM 可测形状锚）。
     * 形状要求：tag_items JOIN items 双表（tag_items 有 chatId 列=频道域谓词在倒排
     * 侧先收窄，rowid 回表只取 dateEpochSec）；三谓词（tag/chatId/日期有效）；时间
     * 升序（分桶确定性）；LIMIT 有界。命中 tag_items (tag) 前缀扫——与 tagItems
     * 召回同族成本（毫秒级），恒 ≤ 该 tag 全站条数 ∩ 频道域。
     */
    fun tagEraSql(): String =
        "SELECT i.dateEpochSec FROM tag_items t JOIN items i ON i.rowid = t.rowid_ref " +
            "WHERE t.tag=? AND t.chatId=? AND i.dateEpochSec>0 " +
            "ORDER BY i.dateEpochSec ASC LIMIT $ERA_ROW_CAP"

    /**
     * 发布秒表 → 月度柱（近 [ERA_MONTHS] 个月，升序）：日聚合后复用
     * [ChannelEraPolicy.monthlyOf]（编年史律动卷同一分桶件——舷窗/世界卡/信息卡
     * 三处月柱同一尺子）。空输入=空表（诚实缺省，调用方整段不渲染）。
     */
    fun tagMonthly(dateSecs: LongArray): List<ChannelEraPolicy.MonthCount> {
        if (dateSecs.isEmpty()) return emptyList()
        val dayMap = HashMap<Int, Int>(64)
        for (sec in dateSecs) {
            if (sec <= 0L) continue
            dayMap.merge((sec / 86_400L).toInt(), 1, Int::plus)
        }
        val days = dayMap.entries.sortedBy { it.key }.map { ChannelEraPolicy.DayCount(it.key, it.value) }
        return ChannelEraPolicy.monthlyOf(days, ERA_MONTHS)
    }

    /** 峰月判词（「2024年3月」——中性事实制，ChannelEraPolicy.halfYearLabel 同族文风） */
    fun peakMonthLabel(monthly: List<ChannelEraPolicy.MonthCount>): String? {
        val peak = monthly.maxByOrNull { it.count } ?: return null
        if (peak.count <= 0) return null
        return "${peak.year}年${peak.month1}月"
    }

    // ==================== 舷窗 tag 行星标记（Compose 覆盖层定位——与快照共用世界系） ====================

    /**
     * tag 行星标记在舷窗快照上的锚点（相对世界中心的偏移，世界单位）：相位=
     * TasteTagPlanets phase01 同源（**真相位非摆拍**——与星图内该行星同一确定性
     * 哈希）；轨道半径映射进邻域双环内缘（RING_INNER×0.55 ~ RING_INNER×0.95——
     * 行星在恒星与邻星环之间，不与邻星抢位）；y 压扁 0.62=TagOrbitCloud 同款
     * 轨道面语言（ORBIT_FLATTEN 同值注释同步）。
     */
    fun tagMarkerOffset(phase01: Float, orbitR01: Float): FloatArray {
        val r = RING_INNER * (0.55f + 0.40f * orbitR01.coerceIn(0f, 1f))
        val ang = (phase01.coerceIn(0f, 1f) * 2.0 * Math.PI).toFloat()
        return floatArrayOf(r * cos(ang), r * 0.62f * sin(ang))
    }
}
