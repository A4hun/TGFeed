package dev.tgfeed.core.td

/**
 * R283 消息级嗅探去重账本（互链图谱深化的幂等闸）。
 *
 * 病灶（缺口⑤）：DiscoveryGraph.recordEdge 命中即 count++，同一批消息被多条抓取路径
 * 重复过手（feed 翻页重叠 / 评论级联重拉 / 链③原帖 TTL 过期重查 / **本地回填每派工
 * 周期重扫**——IndexCrawlGate 注释实锤 0 产频道整夜空转重扫）会反复累加，边权从
 * 「去重消息数」虚涨成「观测次数」——回填路径接嗅探后不加闸 = 每小时级刷爆边权，
 * 双星判定（合并边权门）直接被污染。
 *
 * 设计：per-车道（chatId：频道/讨论组/原帖源频道各自独立 id 空间）记「已嗅探」消息
 * 坐标，两种形态——
 *  - **区间**：页级抓取（GetChatHistory/GetMessageThreadHistory 页、回填页）整段
 *    [旧id, 新id] 闭区间记入，重叠/相邻自动合并（连续翻页天然并成 1~3 段）；
 *  - **单点**：线程级/原帖级散点（评论线程回复在讨论组 id 空间内与其他线程交错、
 *    链③原帖单条坐标）——记单点集，**绝不并入区间**（并入会把未过手的邻线程消息
 *    区间段谎报成已嗅探 = 漏边，F6 弱证据冒充权威同族）。
 *
 * 容量治理（F7 无界资源族红线，宁有界双计勿漏收）：
 *  - 车道上限 [maxLanes]：LinkedHashMap access-order LRU 淘汰最久未碰车道；
 *  - 车道内区间上限 [maxIntervalsPerLane]：超限时淘汰**最深**（start 最小）区间——
 *    重扫高频区在近页（刷新/回填都扫新消息），深史区间重访率低；
 *  - 车道内单点上限 [maxSinglesPerLane]：FIFO 淘汰最旧单点。
 * 淘汰的代价是**有界双计**（重扫重记），绝不制造漏收。
 *
 * 纯 Kotlin 零 Android 依赖（JVM 单测直锚）；进程内账本不落盘——重启后首轮回填
 * 重扫双计一次（天级低频，与既有「feed 翻页重叠双计」同量级，挂账申报）。
 * 全部方法 @Synchronized（嗅探跑在网关 IO 协程多线程）。
 */
internal class SniffLedger(
    private val maxLanes: Int = MAX_LANES,
    private val maxIntervalsPerLane: Int = MAX_INTERVALS_PER_LANE,
    private val maxSinglesPerLane: Int = MAX_SINGLES_PER_LANE,
) {

    private class Lane {
        /** 已嗅探区间集：[start, end] 闭区间，按 start 升序、互不重迭（合并不变式由 [mergeInto] 维护） */
        val intervals = ArrayList<LongArray>()

        /** 已嗅探散点（线程回复/原帖坐标）；懒建——多数车道只有区间没有散点 */
        var singles: LinkedHashSet<Long>? = null
    }

    /** access-order LinkedHashMap = LRU 车道表 */
    private val lanes = LinkedHashMap<Long, Lane>(128, 0.75f, true)

    /** 该消息是否尚未嗅探（true = 应执行嗅探）。messageId<=0 的异常坐标恒视为新（不记不查，保持现状行为） */
    @Synchronized
    fun isNew(chatId: Long, messageId: Long): Boolean {
        if (messageId <= 0L) return true
        val lane = lanes[chatId] ?: return true
        lane.singles?.contains(messageId)?.let { if (it) return false }
        return !coveredByInterval(lane, messageId)
    }

    /** 记一条已嗅探散点（评论线程回复/链③原帖专用；页级抓取走 [markRange]） */
    @Synchronized
    fun markMessage(chatId: Long, messageId: Long) {
        if (messageId <= 0L) return
        val lane = laneOf(chatId)
        if (coveredByInterval(lane, messageId)) return // 区间已覆盖：无需记散点
        val s = lane.singles ?: LinkedHashSet<Long>(16).also { lane.singles = it }
        if (!s.add(messageId)) return // 已记
        while (s.size > maxSinglesPerLane) {
            val eldest = s.firstOrNull() ?: break
            s.remove(eldest)
        }
    }

    /**
     * 记一段已嗅探闭区间（页级抓取：firstId..lastId 乱序自适应——调用方传页首/页尾即可）。
     * 重叠/相邻（gap<=1）自动合并；合并后吞掉的散点顺手清出单点集（账本卫生）。
     */
    @Synchronized
    fun markRange(chatId: Long, firstId: Long, lastId: Long) {
        if (firstId <= 0L || lastId <= 0L) return
        var start = minOf(firstId, lastId)
        var end = maxOf(firstId, lastId)
        val lane = laneOf(chatId)
        mergeInto(lane, start, end)
        // 区间吞掉的散点清除（防单点集无意义膨胀）
        lane.singles?.let { s ->
            if (s.isNotEmpty()) {
                s.removeAll { id -> coveredByInterval(lane, id) }
                if (s.isEmpty()) lane.singles = null
            }
        }
        // 区间上限：淘汰最深（start 最小=最老历史——重访率最低；近页高频重扫区保留）
        while (lane.intervals.size > maxIntervalsPerLane) {
            lane.intervals.removeAt(0)
        }
    }

    /** 诊断/单测：车道数 */
    @Synchronized
    fun laneCount(): Int = lanes.size

    /** 诊断/单测：指定车道区间数（车道不存在返回 0） */
    @Synchronized
    fun intervalCount(chatId: Long): Int = lanes[chatId]?.intervals?.size ?: 0

    /** 诊断/单测：指定车道散点数（车道/散点集不存在返回 0） */
    @Synchronized
    fun singleCount(chatId: Long): Int = lanes[chatId]?.singles?.size ?: 0

    // ---------------- 内部 ----------------

    private fun laneOf(chatId: Long): Lane {
        val lane = lanes.getOrPut(chatId) { Lane() }
        // 车道上限：LRU 淘汰最久未碰（access-order 迭代首位=最老）
        while (lanes.size > maxLanes) {
            val eldest = lanes.entries.firstOrNull()?.key ?: break
            if (eldest == chatId) break // 防御：刚建的车道绝不自删
            lanes.remove(eldest)
        }
        return lane
    }

    /** [start, end] 并入区间集（调用方保证 start<=end）：重叠/相邻合并，保持按 start 升序互不重迭 */
    private fun mergeInto(lane: Lane, start: Long, end: Long) {
        var s = start
        var e = end
        val list = lane.intervals
        var i = 0
        var insertAt = list.size
        while (i < list.size) {
            val iv = list[i]
            if (iv[1] < s - 1) { i++; continue } // 在左侧且不邻接
            if (iv[0] > e + 1) { insertAt = i; break } // 在右侧且不邻接：插入点
            // 重叠/相邻：吸收
            s = minOf(s, iv[0])
            e = maxOf(e, iv[1])
            list.removeAt(i)
            // 不递增 i：同位再看下一个
        }
        list.add(minOf(insertAt, list.size), longArrayOf(s, e))
    }

    private fun coveredByInterval(lane: Lane, messageId: Long): Boolean {
        for (iv in lane.intervals) {
            if (messageId < iv[0]) return false // 升序：后面的区间只会更大
            if (messageId <= iv[1]) return true
        }
        return false
    }

    companion object {
        /** 车道数上限（订阅+图谱触及的频道/讨论组量级；LRU 淘汰） */
        const val MAX_LANES = 1024

        /** 车道内区间上限（连续翻页天然合并成 1~3 段；跳页扫描才累积——超限淘汰最深段） */
        const val MAX_INTERVALS_PER_LANE = 64

        /** 车道内散点上限（线程回复/原帖坐标；FIFO 淘汰最旧） */
        const val MAX_SINGLES_PER_LANE = 256
    }
}
