package dev.tgfeed.core.feed

/**
 * R283 编年史⑥互联卷数据底座·纯逻辑件：同指纹（fileUniqueId）跨频道内容行的
 * 合并/方向判定/去重/时间线组装/伙伴榜聚合。
 *
 * SQL 壳（DeepSearchIndex 三只读口：interlinkSharedTimeline/interlinkPairSummary/
 * interlinkPartnersOf）负责频道域有界取行，本件收口语义——单测零 SQLite 直锚。
 *
 * 语义口径（诚实申报）：
 *  - 「源头侧」=本地索引 canonical 锚（v11 收编语义：同指纹首次入库发布），
 *     refs 行=后收编/转发方——方向边只表达「本地证据下的发布先后」，不冒充
 *    全 TG 的首发判定（FirstSeenLedger 同款覆盖诚实口径）；
 *  - 共现边=两侧皆 refs（同收编第三方内容），无方向；
 *  - 时间点日期=两侧发布日期的较小者（互联关系「开始」的时刻）。
 */

/** 一条互联内容证据（频道对共享同指纹内容的一次观测） */
data class InterlinkEvidence(
    /** 源头侧频道（canonical 锚）；共现边=较小 chatId（无方向，仅归一） */
    val sourceChatId: Long,
    val sourceMessageId: Long,
    val sourceDateSec: Long,
    /** 收编/转发侧频道；共现边=较大 chatId（归一） */
    val repostChatId: Long,
    val repostMessageId: Long,
    val repostDateSec: Long,
    val mediaType: Int,
    /** true = 双方皆 refs 收编（共现无方向）；false = 方向边（source→repost） */
    val coOccur: Boolean,
) {
    /** 时间点日期（互联关系开始的时刻=两侧发布日期较小者） */
    val pointDateSec: Long get() = minOf(sourceDateSec, repostDateSec)

    /** 去重键（同两侧同两消息只算一条证据） */
    val dedupeKey: String get() = "$sourceChatId:$sourceMessageId:$repostChatId:$repostMessageId"
}

/** 频道对互联聚合（双星判定时间分布维度喂料/⑥卷摘要行原料） */
data class InterlinkPairSummary(
    /** a 为源、b 收编的条数（a→b 互转） */
    val aToB: Int,
    /** b 为源、a 收编的条数（b→a 互转） */
    val bToA: Int,
    /** 共现条数（双方皆收编第三方） */
    val coOccur: Int,
    /** 最早互联日期（Unix 秒；0=无证据） */
    val firstDateSec: Long,
    /** 最近互联日期（Unix 秒；0=无证据） */
    val lastDateSec: Long,
    /** 取数被行数 cap 截断（巨型频道诚实降级标记） */
    val truncated: Boolean,
) {
    /** 内容级互联总条数 */
    val total: Int get() = aToB + bToA + coOccur

    /** 互联跨度天数（无证据=0） */
    val spanDays: Int get() = if (lastDateSec <= firstDateSec) 0 else ((lastDateSec - firstDateSec) / 86400L).toInt()
}

/** 频道级内容伙伴（「频道 A 的互联史」卷面行原料） */
data class InterlinkPartner(
    val chatId: Long,
    /** 对方是源、本频道收编的条数（本频道 downstream 消费对方内容） */
    val theySourced: Int,
    /** 本频道是源、对方收编的条数（对方 downstream 消费本频道内容） */
    val weSourced: Int,
    /** 共现条数（双方同收编第三方内容） */
    val coOccur: Int,
    val firstDateSec: Long,
    val lastDateSec: Long,
) {
    val total: Int get() = theySourced + weSourced + coOccur
}

/** 纯逻辑收口（无状态单例） */
object InterlinkMerge {

    /**
     * 时间线组装：去重（dedupeKey）→ 按 pointDateSec 升序（同刻按 source/repost
     * messageId 升序稳定）→ 截断 [limit]。输入行为各臂 SQL 壳的原始收集（可含
     * 跨臂重复：残双 canonical 臂与方向臂理论上互斥，防御性去重不假设）。
     */
    fun timeline(rows: List<InterlinkEvidence>, limit: Int): List<InterlinkEvidence> {
        if (rows.isEmpty() || limit <= 0) return emptyList()
        return rows.distinctBy { it.dedupeKey }
            .sortedWith(
                compareBy<InterlinkEvidence> { it.pointDateSec }
                    .thenBy { it.sourceMessageId }
                    .thenBy { it.repostMessageId },
            )
            .take(limit)
    }

    /**
     * 频道对聚合：方向计数按查询口径 (a, b) 归位（coOccur 边无方向不计入 aToB/bToA）。
     * 输入须已限 (a,b) 对（SQL 壳保证）；[truncated] 透传取数侧截断标记。
     */
    fun summarize(a: Long, b: Long, rows: List<InterlinkEvidence>, truncated: Boolean): InterlinkPairSummary {
        var aToB = 0
        var bToA = 0
        var co = 0
        var first = Long.MAX_VALUE
        var last = Long.MIN_VALUE
        for (r in rows.distinctBy { it.dedupeKey }) {
            when {
                r.coOccur -> co++
                r.sourceChatId == a && r.repostChatId == b -> aToB++
                r.sourceChatId == b && r.repostChatId == a -> bToA++
                else -> continue // 防御：不属于本对的行不计
            }
            if (r.pointDateSec > 0) {
                first = minOf(first, r.pointDateSec)
                last = maxOf(last, r.pointDateSec)
            }
        }
        return InterlinkPairSummary(
            aToB = aToB,
            bToA = bToA,
            coOccur = co,
            firstDateSec = if (first == Long.MAX_VALUE) 0L else first,
            lastDateSec = if (last == Long.MIN_VALUE) 0L else last,
            truncated = truncated,
        )
    }

    /**
     * 伙伴榜聚合：把相对 [self] 的全部证据行按对方频道归组累计三类计数与首末日期，
     * 按 total 降序（同数按 chatId 升序=确定性）截断 [limit]。
     */
    fun partners(self: Long, rows: List<InterlinkEvidence>, limit: Int): List<InterlinkPartner> {
        if (rows.isEmpty() || limit <= 0) return emptyList()
        class Acc(var they: Int = 0, var we: Int = 0, var co: Int = 0, var first: Long = Long.MAX_VALUE, var last: Long = Long.MIN_VALUE)
        val acc = HashMap<Long, Acc>()
        for (r in rows.distinctBy { it.dedupeKey }) {
            val other = when {
                r.coOccur -> if (r.sourceChatId == self) r.repostChatId else r.sourceChatId
                r.sourceChatId == self -> r.repostChatId
                r.repostChatId == self -> r.sourceChatId
                else -> continue // 防御：与 self 无关的行不进
            }
            if (other == self) continue
            val a = acc.getOrPut(other) { Acc() }
            when {
                r.coOccur -> a.co++
                r.repostChatId == self -> a.they++ // 对方是源、本频道收编
                else -> a.we++ // 本频道是源、对方收编
            }
            if (r.pointDateSec > 0) {
                a.first = minOf(a.first, r.pointDateSec)
                a.last = maxOf(a.last, r.pointDateSec)
            }
        }
        return acc.entries
            .map { (id, a) ->
                InterlinkPartner(
                    chatId = id,
                    theySourced = a.they,
                    weSourced = a.we,
                    coOccur = a.co,
                    firstDateSec = if (a.first == Long.MAX_VALUE) 0L else a.first,
                    lastDateSec = if (a.last == Long.MIN_VALUE) 0L else a.last,
                )
            }
            .sortedWith(compareByDescending<InterlinkPartner> { it.total }.thenBy { it.chatId })
            .take(limit)
    }
}
