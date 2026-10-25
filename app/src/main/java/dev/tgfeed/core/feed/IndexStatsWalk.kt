package dev.tgfeed.core.feed

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.Calendar

// ---------------- R318 频道体检聚合降档：date 索引覆盖分片走查 ----------------
//
// 病灶（R290d V22 实锤 2026-10-22）：频道体检两条全表 GROUP BY（loadIndexChannelStats +
// loadRecentPostCounts）在 5.8M 行真库 >2min 无响应；源码注释设计假设「十几万条目数秒」
// =40 倍规模失配。PC snap2 彩排（r318_rehearsal.py）实锤根因：自然计划误选
// idx_items_album 走 GROUP BY chatId 序（与 R317 chatPublishHistogram 同族病），
// dateEpochSec 不在该索引 → 7.4M 行逐行回表，单臂 PC 实测 172s。
//
// 修法=降档查询：旧「GROUP BY chatId 回表聚合」→「idx_items_date(dateEpochSec,chatId)
// 覆盖索引 date 域分片走查 + Kotlin 侧聚合」：
//  - 覆盖性：chatId+dateEpochSec 均为 idx_items_date 索引列，零回表（R290b「无 WHERE+
//    非覆盖 INDEXED BY」雷形不踩——分片 WHERE 还能下压到索引前导列）；
//  - 分片：date 域切 N 片逐片查，片间回调进度+查取消（长操作进度可见可取消红线）；
//  - 30d 窗计数并入同一次走查（一次喂 (chatId, dateSec) 流同时产出 stats 与窗计数，
//    口径与旧两条 GROUP BY 逐字对齐：COUNT 相加/MIN 取小/MAX 取大/窗口 >= 边界）；
//  - refs 臂同形（idx_refs_date）；表缺席（v10 升级窗）直通跳过（现状同口径）；
//  - 兜底：idx_items_date 缺席（后台建索引 pending/重建窗）或走查中途失败（索引被
//    R289 自愈 DROP 等）→ 旧 GROUP BY 形状原样兜底（慢但正确，与旧行为一致）；
//    取消≠失败——取消直通 null 不兜底（否则取消后还会再跑一遍慢路）。

/**
 * 走查臂 SQL 形状（items）：R269 机检锚单一出处（[chatHistogramDaySql] 同款纪律）。
 * 出参六列=组级聚合（chatId/COUNT/today 数/MIN/MAX/窗计数）——**SQLite 侧聚合**：
 * 7.4M 行在 C 层流过临时 B 树（569 组），游标只出 ≤组数行（流式行遍历的 25 倍降流量，
 * PC 彩排实锤：侧算 21.4s vs SQLite 聚合 12.2s）；WHERE 分片区间下压索引前导列；
 * INDEXED BY idx_items_date 锁驱动侧（防自然计划误选 idx_items_album 回表——彩排
 * 实锤病根：四臂全走 album 索引逐行回表，单臂 172s）。
 * 参数序：今日零点秒、窗口起点秒、片左界（含）、片右界（不含）。
 */
internal fun chatStatsWalkSql(): String =
    "SELECT chatId, COUNT(*), SUM(dateEpochSec >= ?), MIN(dateEpochSec), MAX(dateEpochSec), " +
        "SUM(dateEpochSec >= ?) FROM items INDEXED BY idx_items_date " +
        "WHERE dateEpochSec >= ? AND dateEpochSec < ? GROUP BY chatId"

/** 走查臂 SQL 形状（repost_refs）：[chatStatsWalkSql] 的别名表镜像（形状锁同一机检锚管，
 *  与 chatHistogramDayRefsSql 同款纪律；[refsTable] 由调用方传 REFS_TABLE_NAME） */
internal fun chatStatsWalkRefsSql(refsTable: String): String =
    "SELECT chatId, COUNT(*), SUM(dateEpochSec >= ?), MIN(dateEpochSec), MAX(dateEpochSec), " +
        "SUM(dateEpochSec >= ?) FROM $refsTable INDEXED BY idx_refs_date " +
        "WHERE dateEpochSec >= ? AND dateEpochSec < ? GROUP BY chatId"

/** 旧形状兜底 SQL（items 臂，与 R318 前源码逐字同形——降级路径行为不变的形状锚） */
internal fun chatStatsLegacyGroupSql(): String =
    "SELECT chatId, COUNT(*), SUM(CASE WHEN dateEpochSec >= ? THEN 1 ELSE 0 END), " +
        "MIN(dateEpochSec), MAX(dateEpochSec) FROM items GROUP BY chatId"

/** 旧形状兜底 SQL（refs 臂）：[chatStatsLegacyGroupSql] 的别名表镜像 */
internal fun chatStatsLegacyGroupRefsSql(refsTable: String): String =
    "SELECT chatId, COUNT(*), SUM(CASE WHEN dateEpochSec >= ? THEN 1 ELSE 0 END), " +
        "MIN(dateEpochSec), MAX(dateEpochSec) FROM $refsTable GROUP BY chatId"

/** 旧形状兜底 SQL（30d 窗 items 臂，与 R318 前源码逐字同形） */
internal fun chatStatsLegacyWindowSql(): String =
    "SELECT chatId, COUNT(*) FROM items WHERE dateEpochSec >= ? GROUP BY chatId"

/** 旧形状兜底 SQL（30d 窗 refs 臂）：[chatStatsLegacyWindowSql] 的别名表镜像 */
internal fun chatStatsLegacyWindowRefsSql(refsTable: String): String =
    "SELECT chatId, COUNT(*) FROM $refsTable WHERE dateEpochSec >= ? GROUP BY chatId"

/** 走查分片数（每臂）：512 片≈每片万级行——片粒度即进度/取消粒度（亚秒级），
 *  分片税=逐片查询重入（索引区间 seek，彩排实测可忽略）；分片只改粒度不改总行数，
 *  小库大库同一常数（空片=空区间 seek，微秒级直通）。 */
internal const val CHAT_STATS_WALK_CHUNKS = 512

// 索引/表名镜像：DeepSearchIndex companion 私有引不到（DB_NAME 字面量镜像先例同款，
// FeedChannelHealthPart 旧码注释即此口径）。名实漂移兜底=INDEXED BY 找不到索引→
// SQLITE_ERROR→兜底链旧形状，安全降级不崩；单测锚锁 SQL 形状防手改漂移。
private const val ITEMS_DATE_INDEX_NAME = "idx_items_date"
private const val REFS_TABLE_NAME = "repost_refs"
private const val REFS_DATE_INDEX_NAME = "idx_refs_date"

/** 走查取消哨兵：片间取消检查命中时抛出；调用方译成「保旧值直通」而非兜底重跑 */
internal class ChatStatsWalkCancelled internal constructor() : RuntimeException("体检走查已取消")

/**
 * 聚合结果（走查与兜底共用产出）：[stats] 与旧 loadIndexChannelStats 同口径
 * （chatId → 条数/今日条数/最早/最新发布秒；refs 臂合并=COUNT 相加、MIN 取小、
 * MAX 取大——v11 D6 含转发口径不变）；[recentCounts] 与旧 loadRecentPostCounts
 * 同口径（30d 窗口各频道条数），null=窗口证据缺失（与旧 Q2 失败口径一致：
 * 无证据不臆测，不按 0 条计罚）。
 */
internal data class ChatStatsAggregate(
    val stats: Map<Long, IndexChannelStat>,
    val recentCounts: Map<Long, Int>?,
)

/**
 * 走查聚合器（纯函数件，JVM 单测锚）：喂 (chatId, dateSec) 行流，一次产出
 * stats 快照与 30d 窗计数。旧两条 GROUP BY 的口径在这里逐字复刻：
 *  - count：两臂行数相加（含 0 值日期行——旧 COUNT(*) 全计数）；
 *  - todayCount：dateSec >= [todayStartSec] 的行（旧 SUM(CASE WHEN 形状)）；
 *  - min/max：跨臂极值（旧 MIN 取小/MAX 取大合并）；
 *  - 窗计数：dateSec >= [windowSinceSec] 的行（旧 WHERE dateEpochSec >= 形状）。
 */
internal class ChatStatsAccumulator(
    private val todayStartSec: Long,
    private val windowSinceSec: Long,
) {
    private val count = HashMap<Long, Int>()
    private val today = HashMap<Long, Int>()
    private val minSec = HashMap<Long, Long>()
    private val maxSec = HashMap<Long, Long>()
    private val window = HashMap<Long, Int>()

    /** 喂一行（items 或 refs 臂通用——合并语义即旧两臂 Kotlin 侧合并的算术本尊） */
    fun add(chatId: Long, dateSec: Long) {
        count[chatId] = (count[chatId] ?: 0) + 1
        if (dateSec >= todayStartSec) today[chatId] = (today[chatId] ?: 0) + 1
        if (dateSec >= windowSinceSec) window[chatId] = (window[chatId] ?: 0) + 1
        val mn = minSec[chatId]
        if (mn == null || dateSec < mn) minSec[chatId] = dateSec
        val mx = maxSec[chatId]
        if (mx == null || dateSec > mx) maxSec[chatId] = dateSec
    }

    /**
     * 组级并入（混合路径专用：items 臂快路走查 + refs 臂旧形状兜底时，把旧 GROUP BY
     * 的一组结果并入行级聚合器——与逐行喂入等价的组级算术）。[windowCount] 缺席
     * =该臂窗口证据缺失（同旧 refs 窗臂失败被吞口径），窗口面不并入。
     */
    fun mergeGroup(chatId: Long, groupCount: Int, todayCount: Int, minDateSec: Long, maxDateSec: Long, windowCount: Int?) {
        count[chatId] = (count[chatId] ?: 0) + groupCount
        today[chatId] = (today[chatId] ?: 0) + todayCount
        if (windowCount != null) window[chatId] = (window[chatId] ?: 0) + windowCount
        val mn = minSec[chatId]
        if (mn == null || minDateSec < mn) minSec[chatId] = minDateSec
        val mx = maxSec[chatId]
        if (mx == null || maxDateSec > mx) maxSec[chatId] = maxDateSec
    }

    /** 快照（走查完成后一次性取；stats 与窗计数同一行流派生=两证据面天然同源） */
    fun snapshot(): ChatStatsAggregate {
        val out = HashMap<Long, IndexChannelStat>(count.size)
        for ((id, c) in count) {
            out[id] = IndexChannelStat(c, today[id] ?: 0, minSec[id] ?: 0L, maxSec[id] ?: 0L)
        }
        return ChatStatsAggregate(stats = out, recentCounts = HashMap(window))
    }

    /** 已聚合组数（诊断/单测对拍用） */
    fun chatCount(): Int = count.size
}

/**
 * date 域分片计划（纯函数，JVM 单测锚）：[dateMinSec]~[dateMaxSec] 等宽切 [chunks] 片，
 * 左闭右开 [lo, hi)；末片右端 +1 闭收 [dateMaxSec]（MAX 行是合法数据必须入片）。
 * 并集恰覆盖 [dateMinSec, dateMaxSec] 全域、互不重叠（走查不重不漏的数学保证）。
 */
internal fun chatStatsChunkBounds(dateMinSec: Long, dateMaxSec: Long, chunks: Int): List<LongArray> {
    if (chunks <= 0) throw IllegalArgumentException("chunks 必须为正：$chunks")
    if (dateMaxSec < dateMinSec) return emptyList()
    val span = dateMaxSec - dateMinSec
    val out = ArrayList<LongArray>(chunks)
    for (i in 0 until chunks) {
        val lo = dateMinSec + span * i / chunks
        val hi = if (i == chunks - 1) dateMaxSec + 1 else dateMinSec + span * (i + 1) / chunks
        out.add(longArrayOf(lo, hi))
    }
    return out
}

/**
 * 分片驱动循环（纯函数骨架，JVM 单测锚——进度/取消语义的真身）：逐片调 [feed]，
 * 片间查取消（命中抛 [ChatStatsWalkCancelled]）、回报进度（整数百分比变化才发，
 * 防高频回调洪泛）。取消检查在片间而非片内：单片=索引区间扫描亚秒级，
 * 取消响应粒度足够且不打断 SQLite 语句本尊。
 */
internal inline fun runChatStatsChunks(
    chunkList: List<LongArray>,
    crossinline feed: (lo: Long, hi: Long) -> Unit,
    crossinline onProgress: (percent: Int) -> Unit,
    crossinline isCancelled: () -> Boolean,
) {
    val total = chunkList.size
    var lastPct = -1
    for ((i, bounds) in chunkList.withIndex()) {
        if (isCancelled()) throw ChatStatsWalkCancelled()
        feed(bounds[0], bounds[1])
        val pct = if (total == 0) 100 else (i + 1) * 100 / total
        if (pct != lastPct) {
            lastPct = pct
            onProgress(pct)
        }
    }
}

/**
 * R318 统计聚合统一口（体检/追更红点两路共用）：
 * date 索引在场 → 分片覆盖走查（进度可见可取消）；缺席/走查失败 → 旧 GROUP BY
 * 形状兜底（行为与 R318 前逐字一致，慢但正确）；库文件不存在 → 空聚合（同旧口径）。
 *
 * @return 聚合结果；**null=走查被取消**（调用方保旧值直通——取消不触发兜底重跑）。
 * 必须在 IO 线程调用（走查=长读操作，旧口同纪律）。WAL 只读连接与爬取写并发安全。
 */
internal fun aggregateChatStatsChunked(
    dbFile: File,
    todayStartSec: Long,
    windowSinceSec: Long,
    onProgress: (percent: Int) -> Unit = {},
    isCancelled: () -> Boolean = { false },
): ChatStatsAggregate? {
    if (!dbFile.isFile) return ChatStatsAggregate(emptyMap(), emptyMap())
    val d = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    try {
        if (hasChatStatsIndex(d, ITEMS_DATE_INDEX_NAME)) {
            try {
                return walkChatStatsArms(d, todayStartSec, windowSinceSec, onProgress, isCancelled)
            } catch (e: ChatStatsWalkCancelled) {
                return null // 取消直通：不兜底（取消后重跑慢路=违背取消语义）
            } catch (e: Throwable) {
                // 走查失败（索引中途被 R289 自愈 DROP 等）→ 旧形状兜底重算（慢但正确）
            }
        }
        return legacyChatStatsAggregate(d, todayStartSec, windowSinceSec)
    } finally {
        runCatching { d.close() }
    }
}

/** 索引在场探测（sqlite_master 点查，毫秒级；R289 重建窗/R202 建库 pending=缺席） */
private fun hasChatStatsIndex(d: SQLiteDatabase, indexName: String): Boolean =
    d.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='index' AND name=?",
        arrayOf(indexName),
    ).use { it.moveToFirst() }

/** repost_refs 表在场探测（v10 升级窗=缺席直通跳过——与旧 refs 臂容错同口径） */
private fun hasChatStatsRepostsTable(d: SQLiteDatabase): Boolean =
    d.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
        arrayOf(REFS_TABLE_NAME),
    ).use { it.moveToFirst() }

/**
 * 走查真身（两臂）：items 臂 date 域分片流式扫描；refs 臂同形（表在而 idx_refs_date
 * 缺席的混合路径：items 快路 + refs 旧形状组级并入——保住 items 臂的提速不被罕见的
 * refs 索引缺席拖回分钟级）。进度域=两臂分片合计（items 先 refs 后）；取消哨兵片间抛出。
 */
private fun walkChatStatsArms(
    d: SQLiteDatabase,
    todayStartSec: Long,
    windowSinceSec: Long,
    onProgress: (percent: Int) -> Unit,
    isCancelled: () -> Boolean,
): ChatStatsAggregate {
    val acc = ChatStatsAccumulator(todayStartSec, windowSinceSec)
    val refsTable = REFS_TABLE_NAME
    val refsTablePresent = hasChatStatsRepostsTable(d)
    val refsWalkable = refsTablePresent && hasChatStatsIndex(d, REFS_DATE_INDEX_NAME)
    // 进度分母=两臂分片总数（refs 臂不占域时 items 独占全域——进度不悬空）
    val totalChunks = CHAT_STATS_WALK_CHUNKS * (if (refsWalkable) 2 else 1)

    // items 臂（空库=边沿点查无行，臂贡献空，同旧口径）
    val boundsI = chatStatsDateBounds(d, "items", ITEMS_DATE_INDEX_NAME)
    if (boundsI != null) {
        val chunks = chatStatsChunkBounds(boundsI.first, boundsI.second, CHAT_STATS_WALK_CHUNKS)
        // 臂内 pct(0..100) → 全域 pct：items 臂占前半（refs 不占域时占全域）
        val armShare = CHAT_STATS_WALK_CHUNKS * 100 / totalChunks
        runChatStatsChunks(
            chunks,
            feed = { lo, hi ->
                d.rawQuery(
                    chatStatsWalkSql(),
                    arrayOf(todayStartSec.toString(), windowSinceSec.toString(), lo.toString(), hi.toString()),
                ).use { c ->
                    while (c.moveToNext()) {
                        acc.mergeGroup(c.getLong(0), c.getInt(1), c.getInt(2), c.getLong(3), c.getLong(4), c.getInt(5))
                    }
                }
            },
            onProgress = { pct -> onProgress(pct * armShare / 100) },
            isCancelled = isCancelled,
        )
    }

    if (refsWalkable) {
        val boundsR = chatStatsDateBounds(d, refsTable, REFS_DATE_INDEX_NAME)
        if (boundsR != null) {
            val chunks = chatStatsChunkBounds(boundsR.first, boundsR.second, CHAT_STATS_WALK_CHUNKS)
            // refs 臂占后半（全域 50..100；items 空库时前半空转，末尾 100 兜底收口）
            runChatStatsChunks(
                chunks,
                feed = { lo, hi ->
                    d.rawQuery(
                        chatStatsWalkRefsSql(refsTable),
                        arrayOf(todayStartSec.toString(), windowSinceSec.toString(), lo.toString(), hi.toString()),
                    ).use { c ->
                        while (c.moveToNext()) {
                            acc.mergeGroup(c.getLong(0), c.getInt(1), c.getInt(2), c.getLong(3), c.getLong(4), c.getInt(5))
                        }
                    }
                },
                onProgress = { pct -> onProgress(50 + pct / 2) },
                isCancelled = isCancelled,
            )
        }
    } else if (refsTablePresent) {
        // 混合路径：refs 表在而 idx_refs_date 缺席（R289 DROP refs 索引等罕见窗）——
        // refs 臂走旧形状组级并入，items 臂的提速保住
        legacyRefsInto(d, refsTable, acc, todayStartSec, windowSinceSec)
    }
    onProgress(100)
    return acc.snapshot()
}

/**
 * date 上下界（两沿各一条 O(1) 语句）：走「ORDER BY 索引前导列 + LIMIT 1」形状
 * （不依赖 SQLite MIN/MAX 聚合优化——合并 MIN+MAX 单语句会退化全索引扫描）。
 * 空表返回 null。dateEpochSec NOT NULL DEFAULT 0：MIN=0 时零值日期行入片
 * （旧 COUNT(*) 同计数，走查覆盖性依赖此口径）。
 */
private fun chatStatsDateBounds(
    d: SQLiteDatabase,
    table: String,
    indexName: String,
): Pair<Long, Long>? {
    val lo = d.rawQuery(
        "SELECT dateEpochSec FROM $table INDEXED BY $indexName ORDER BY dateEpochSec LIMIT 1",
        null,
    ).use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return null
    val hi = d.rawQuery(
        "SELECT dateEpochSec FROM $table INDEXED BY $indexName ORDER BY dateEpochSec DESC LIMIT 1",
        null,
    ).use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return null
    return lo to hi
}

/** 旧形状兜底（四臂 SQL 与 R318 前源码逐字同形；失败口径同旧：stats 臂→空表、窗臂→null） */
private fun legacyChatStatsAggregate(
    d: SQLiteDatabase,
    todayStartSec: Long,
    windowSinceSec: Long,
): ChatStatsAggregate {
    val stats = HashMap<Long, IndexChannelStat>()
    // Q1 items 臂（失败=空表：无证据不计罚，同旧 getOrDefault 形状）
    val q1 = runCatching { legacyGroupArm(d, chatStatsLegacyGroupSql(), todayStartSec) }
        .getOrDefault(emptyMap())
    stats.putAll(q1)
    // Q1 refs 臂（表缺席/失败=独立容错直通，同旧 runCatching 吞掉）
    runCatching {
        if (hasChatStatsRepostsTable(d)) {
            val q1r = legacyGroupArm(d, chatStatsLegacyGroupRefsSql(REFS_TABLE_NAME), todayStartSec)
            for ((id, st) in q1r) {
                val old = stats[id]
                stats[id] = if (old == null) st else IndexChannelStat(
                    old.count + st.count,
                    old.todayCount + st.todayCount,
                    minOf(old.minDateSec, st.minDateSec),
                    maxOf(old.maxDateSec, st.maxDateSec),
                )
            }
        }
    }
    // Q2 窗口臂（items 失败=整个窗口证据缺失 null，同旧外层 getOrNull；refs 失败吞掉=items-only，同旧）
    val window = runCatching {
        val w = HashMap<Long, Int>()
        legacyWindowInto(d, chatStatsLegacyWindowSql(), windowSinceSec, w)
        w
    }.getOrNull()
    if (window != null) {
        runCatching {
            if (hasChatStatsRepostsTable(d)) {
                legacyWindowInto(
                    d,
                    chatStatsLegacyWindowRefsSql(REFS_TABLE_NAME),
                    windowSinceSec,
                    window,
                )
            }
        }
    }
    return ChatStatsAggregate(stats, window)
}

/** 旧 GROUP BY stats 臂执行（SQL 文本=旧源码字面，兜底行为锚） */
private fun legacyGroupArm(
    d: SQLiteDatabase,
    sql: String,
    todayStartSec: Long,
): Map<Long, IndexChannelStat> {
    val out = HashMap<Long, IndexChannelStat>()
    d.rawQuery(sql, arrayOf(todayStartSec.toString())).use { c ->
        while (c.moveToNext()) {
            out[c.getLong(0)] = IndexChannelStat(c.getInt(1), c.getInt(2), c.getLong(3), c.getLong(4))
        }
    }
    return out
}

/** 旧窗口臂执行并入目标表（SQL 文本=旧源码字面） */
private fun legacyWindowInto(
    d: SQLiteDatabase,
    sql: String,
    sinceSec: Long,
    into: HashMap<Long, Int>,
) {
    d.rawQuery(sql, arrayOf(sinceSec.toString())).use { c ->
        while (c.moveToNext()) into.merge(c.getLong(0), c.getInt(1), Int::plus)
    }
}

/** 混合路径：refs 臂旧形状（Q1 组级 + Q2 窗口）并入走查聚合器（refs 窗臂失败=窗口面不并入，同旧） */
private fun legacyRefsInto(
    d: SQLiteDatabase,
    refsTable: String,
    acc: ChatStatsAccumulator,
    todayStartSec: Long,
    windowSinceSec: Long,
) {
    runCatching {
        val q1r = legacyGroupArm(d, chatStatsLegacyGroupRefsSql(refsTable), todayStartSec)
        val q2r = runCatching {
            val w = HashMap<Long, Int>()
            legacyWindowInto(d, chatStatsLegacyWindowRefsSql(refsTable), windowSinceSec, w)
            w
        }.getOrNull()
        for ((id, st) in q1r) acc.mergeGroup(id, st.count, st.todayCount, st.minDateSec, st.maxDateSec, q2r?.get(id))
    }
}

/**
 * 本地今日零点（秒）：「今天更了 N 条」鲜活信号的口径边界（旧 loadIndexChannelStats
 * 内联 Calendar 的唯一出处收敛——体检/红点两路同源同界）。
 */
internal fun localMidnightSec(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis / 1000L
