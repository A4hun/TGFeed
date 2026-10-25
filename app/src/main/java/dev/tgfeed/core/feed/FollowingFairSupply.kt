package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem
import kotlin.math.ceil

// ---------------- R176 关注流公平供给（纯函数部件） ----------------
//
// 用户三实锤（关注 tab）：刷到底 / 重复视频 / 频道集中。本文件把供给算法拆成
// 可大量单测的纯函数（轮转交错 / 判重闸 / 分层状态机 / 回收排序），编排逻辑
//（游标、并发拉取、余量缓冲）在 FollowingFeedSource（FeedSource.kt）。
//
// 设计全文见 r176_notes.md；对用户 round-robin 提案的采纳与升级：
//  - 轮转骨架 = 最少已供给者优先（无饿死的结构性保证）；
//  - 加权有界 = 高权重频道每 pass 至多第二条（decayedChannelWeights 时间衰减
//    口味画像只做倾斜不做通吃），叠加聚合流同款放置层硬约束；
//  - 推送位判重 = 已看底账（key+fileUniqueId 双轨）+ 会话内推送位记忆；
//  - 永不见底 = FRESH（未看深挖）→ RECYCLE（已看二轮回收）→ RECOMMEND
//    （推荐兜底，条目带 recommended 标记）→ 定期环回回收层探测新帖。

/** 已看回收间隔（毫秒）：距最近观看不足此间隔的已看条目不参与二轮回收（用户口径「间隔要长」） */
internal const val FOLLOWING_REWATCH_GAP_MS = 72L * 3600_000L

/** FRESH/RECYCLE 层连续颗粒无收的降层阈值（轮）：2 轮空转即降层，不沿已见内容烧请求 */
internal const val FOLLOWING_FRESH_DRY_ROUNDS = 2

/** 单次 nextPage 内供给轮上限（轮）：防单页内无限挖库；跨页由 loadMore 链式续拉接力 */
internal const val FOLLOWING_ROUNDS_PER_PAGE = 4

/** 加权轮转的第二条门槛：口味权重（0.3~2.0，EXPLORE 兜底 0.6）达到此值的频道每 pass 可出第二条 */
internal const val FOLLOWING_WEIGHT_BOOST_MIN = 1.2

/** RECOMMEND 层每出满 N 轮内容就环回 RECYCLE 探测一次新帖（回收层入场即游标归顶重走） */
internal const val FOLLOWING_RECOMMEND_PROBE_EVERY = 3

/** 两次回收层探测的最小间隔（毫秒）：环回别太频，长尾会话里 K 频道重走头部有请求成本 */
internal const val FOLLOWING_RECYCLE_PROBE_MIN_INTERVAL_MS = 60_000L

/** 会话推送位记忆容量上限：超限丢最旧一半（对齐 seen_ids.log 的 FIFO 淘汰口径） */
internal const val FOLLOWING_SERVED_KEYS_MAX = 20_000

/**
 * 供给层（永不见底的三层，见 r176_notes.md §C）：
 *  - [FRESH]   未看深挖：各频道游标向老翻页，严格闸 = 未看且本会话未推送；
 *  - [RECYCLE] 已看二轮回收：游标归顶重走，闸放宽 = 未看 ∨ 距最近观看 ≥ 间隔；
 *  - [RECOMMEND] 推荐兜底：网关聚合路（同推荐 tab 内容池），条目带「为你推荐」标记。
 */
internal enum class FollowingSupplyLayer {
    FRESH, RECYCLE, RECOMMEND
}

/**
 * 判重闸与回收排序共用的底账快照（一次 nextPage 内取一次，纯数据）：
 *  - seenKeys/seenUids：已看身份双轨（LocalStore.loadSeenIdentities，跨会话）；
 *  - watchKeyTs/watchUidTs：最近观看时刻（LocalStore.loadWatchTimestamps，
 *    观看历史 500 条窗口；不在窗口 = 很久以前，回收间隔闸放行）；
 *  - servedKeys：本会话推送位记忆（同一会话同一推送位不重推；用户翻回旧内容
 *    是列表内滚动，不算重复——判重只挡「推送位重复」）。
 */
internal class FollowingSeenLedger(
    val seenKeys: Set<String>,
    val seenUids: Set<String>,
    val watchKeyTs: Map<String, Long>,
    val watchUidTs: Map<String, Long>,
    val servedKeys: Set<String>,
)

/**
 * 推送位判重闸（各层供给条目的硬过滤）：
 *  - 任何层：本会话已推送过 → 挡（推送位不重复）；
 *  - 已看开关关闭（用户显式要求「已看也出来」）→ 只剩推送位判重，其余全放；
 *  - FRESH / RECOMMEND：已看双轨命中（key 或 fileUniqueId）→ 挡；
 *  - RECYCLE：未看放行；已看条目要过回收间隔（距最近观看 ≥ [FOLLOWING_REWATCH_GAP_MS]）。
 *    无观看时刻（markSeen-only 快甩经过页 / 已被 500 条历史窗口淘汰）= 视作很久
 *    以前，放行——真正拦「刚刷过的又来」的是 servedKeys 会话记忆。
 */
internal fun followingGatePasses(
    item: VideoItem,
    layer: FollowingSupplyLayer,
    ledger: FollowingSeenLedger,
    nowMs: Long,
    seenGateOn: Boolean,
): Boolean {
    if (item.key in ledger.servedKeys) return false
    if (!seenGateOn) return true
    val seenByKey = item.key in ledger.seenKeys
    val seenByUid = item.fileUniqueId.isNotEmpty() && item.fileUniqueId in ledger.seenUids
    if (layer == FollowingSupplyLayer.RECYCLE) {
        if (!seenByKey && !seenByUid) return true
        val ts = if (seenByKey) ledger.watchKeyTs[item.key] else ledger.watchUidTs[item.fileUniqueId]
        return ts == null || nowMs - ts >= FOLLOWING_REWATCH_GAP_MS
    }
    return !seenByKey && !seenByUid
}

/**
 * RECYCLE 批内排序（降权口径）：未看优先（已看垫后），已看内部按观看时刻升序
 * （越久远越靠前），未知时刻视作最久远。key 兜底稳定序。
 */
internal fun recycleOrder(items: List<VideoItem>, ledger: FollowingSeenLedger): List<VideoItem> {
    if (items.size < 2) return items
    return items.sortedWith(
        compareBy<VideoItem> {
            if (it.key in ledger.seenKeys ||
                (it.fileUniqueId.isNotEmpty() && it.fileUniqueId in ledger.seenUids)
            ) 1 else 0
        }.thenBy {
            val ts = if (it.key in ledger.seenKeys) {
                ledger.watchKeyTs[it.key]
            } else {
                ledger.watchUidTs[it.fileUniqueId]
            }
            ts ?: 0L
        }.thenBy { it.key }
    )
}

/**
 * 公平轮转交错（治「频道集中」的核心，纯函数）：
 *
 * 输入 = 每频道本批条目（批内序由调用方定：FRESH=时间新→旧，RECYCLE=回收序），
 * 输出 = 全量交错序（不截断——页长裁剪与余量缓冲由调用方处理）。
 *
 * 排序键（频道级）：
 *  1. 会话已供给条数升序——**最少已供给者优先**，这是无饿死的结构性保证
 *     （对齐 SampleRotationPool「最久未露头优先」的保底车道思想，但确定性更强）；
 *  2. 口味权重（decayedChannelWeights 时间衰减画像）降序——加权轮转；
 *  3. 频道头条发布时间降序——新帖频道先露头（时间衰减的另一半）；
 *  4. chatId 升序——稳定防抖动。
 *
 * 放置层硬约束（继承聚合流口径，常量同一定义，见 FeedSource.kt）：
 *  - 任意连续 [WINDOW] 条内同频道 ≤ [WINDOW_MAX]（连续 5 条同频道 ≤2）；
 *  - 单频道批内占比 ≤ [SHARE_CAP_RATIO]（≥[SHARE_CAP_MIN] 条）；
 *  - 加权补位：权重 ≥ [FOLLOWING_WEIGHT_BOOST_MIN] 的频道每 pass 至多再出 1 条
 *    （有界倾斜：高权重频道每 pass ≤2 条，占比硬上限仍然压顶——加权不许通吃）。
 *
 * 约束放松：活跃频道极少（1~2 个）时占比/窗口约束结构性无法满足，逐级放松
 * （单/双频道订阅的整段连排本来就是合法形态——旧实现亦如此）。
 */
internal fun interleaveFollowingRound(
    batches: Map<Long, List<VideoItem>>,
    servedCount: Map<Long, Int>,
    weights: Map<Long, Double>,
): List<VideoItem> {
    val active = LinkedHashMap<Long, List<VideoItem>>()
    for ((id, list) in batches) if (list.isNotEmpty()) active[id] = list
    if (active.isEmpty()) return emptyList()
    val total = active.values.sumOf { it.size }
    val order = active.keys.sortedWith(
        compareBy<Long> { servedCount[it] ?: 0 }
            .thenByDescending { weights[it] ?: EXPLORE_WEIGHT }
            .thenByDescending { active[it]!!.first().dateEpochSec }
            .thenBy { it }
    )
    val queues = order.map { ArrayDeque(active[it]!!) }
    val shareCap = maxOf(SHARE_CAP_MIN, ceil(total * SHARE_CAP_RATIO).toInt())
    val out = ArrayList<VideoItem>(total)
    val pageCounts = HashMap<Long, Int>()
    var relaxed = false

    fun windowOk(chatId: Long): Boolean {
        // 与聚合流 placeWithDiversity 同口径：待追加位的前 WINDOW-1 个里同频道
        // 已达 WINDOW_MAX 则本位让位（takeLast 对短前缀原样返回——尾窗不足 4 条时
        // 也拦三连，宽松处由下方的约束放松兜底）
        val recent = out.takeLast(WINDOW - 1)
        return recent.count { it.chatId == chatId } < WINDOW_MAX
    }

    fun tryEmit(i: Int): Boolean {
        val q = queues[i]
        val item = q.firstOrNull() ?: return false
        val c = order[i]
        if (!relaxed && (!windowOk(c) || (pageCounts[c] ?: 0) >= shareCap)) return false
        q.removeFirst()
        out += item
        pageCounts[c] = (pageCounts[c] ?: 0) + 1
        return true
    }

    while (out.size < total) {
        var progress = false
        // 主 pass：订单内每频道出 1 条
        for (i in order.indices) progress = tryEmit(i) || progress
        // 加权补位 pass：高权重频道第二条（有界倾斜）
        for (i in order.indices) {
            if ((weights[order[i]] ?: EXPLORE_WEIGHT) >= FOLLOWING_WEIGHT_BOOST_MIN) {
                progress = tryEmit(i) || progress
            }
        }
        if (!progress) {
            // 约束卡死（活跃频道太少）：逐级放松后强制出 1 条，保证产出不空转
            relaxed = true
            val firstAlive = order.indices.firstOrNull { queues[it].isNotEmpty() } ?: break
            tryEmit(firstAlive)
        }
    }
    return out
}

/**
 * 降层/环回判定（颗粒无收时；纯函数）：
 *  - FRESH：连续 [FOLLOWING_FRESH_DRY_ROUNDS] 轮颗粒无收，或全部频道已到底 → RECYCLE；
 *  - RECYCLE：连续空轮（回收间隔把已看全挡了）→ RECOMMEND；
 *  - RECOMMEND：连续空轮 → 环回 RECYCLE（入场即归顶重走——新帖探测；聚合路
 *    也枯竭的极端账号下形成 FRESH→RECYCLE→RECOMMEND 的完整环，供给层永不
 *    终结，「到底」不是终态）。出货期间的定期环回由 [shouldProbeRecycle] 节拍驱动。
 */
internal fun nextFollowingLayer(
    cur: FollowingSupplyLayer,
    dryStreak: Int,
    allChannelsExhausted: Boolean,
): FollowingSupplyLayer = when (cur) {
    FollowingSupplyLayer.FRESH ->
        if (dryStreak >= FOLLOWING_FRESH_DRY_ROUNDS || allChannelsExhausted) {
            FollowingSupplyLayer.RECYCLE
        } else {
            FollowingSupplyLayer.FRESH
        }
    FollowingSupplyLayer.RECYCLE ->
        if (dryStreak >= FOLLOWING_FRESH_DRY_ROUNDS) {
            FollowingSupplyLayer.RECOMMEND
        } else {
            FollowingSupplyLayer.RECYCLE
        }
    FollowingSupplyLayer.RECOMMEND ->
        if (dryStreak >= FOLLOWING_FRESH_DRY_ROUNDS) {
            FollowingSupplyLayer.RECYCLE
        } else {
            FollowingSupplyLayer.RECOMMEND
        }
}

/**
 * RECOMMEND 层的环回探测节拍（纯函数）：出满 [FOLLOWING_RECOMMEND_PROBE_EVERY] 轮
 * 且距上次探测 ≥ [FOLLOWING_RECYCLE_PROBE_MIN_INTERVAL_MS] 才值得再回回收层——
 * 回收入场即游标归顶重走，能捎带发现新帖（未看条目永远过闸），但 K 频道重走
 * 头部有请求成本，节拍别太频。
 */
internal fun shouldProbeRecycle(recommendRounds: Int, sinceProbeMs: Long): Boolean =
    recommendRounds >= FOLLOWING_RECOMMEND_PROBE_EVERY &&
        sinceProbeMs >= FOLLOWING_RECYCLE_PROBE_MIN_INTERVAL_MS
