package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R318 统计走查件回归锚（[ChatHistogramSqlShapeTest] 同款 R269 机检锚模式）：
 *  - SQL 形状锚：走查臂（覆盖索引锁+分片谓词+组级聚合列）与旧形状兜底（R318 前
 *    源码字面——降级路径行为不变的锚）；
 *  - 分片计划锚：并集恰覆盖全域、互不重叠、末片闭收 MAX、零值日期行入首片；
 *  - 聚合器锚：行级口径与旧两条 GROUP BY 逐字对齐、双臂合并算术、组级并入与
 *    行级喂入等价（混合路径正确性）、窗口缺席语义；
 *  - 进度/取消语义锚：整数百分比变化才回报（防洪泛）、片间取消哨兵、空片表直通。
 */
class IndexStatsWalkTest {

    // ---------------- SQL 形状锚 ----------------

    @Test
    fun `走查臂 items-SQL 形状（覆盖索引锁+分片谓词+组级聚合列）`() {
        val sql = chatStatsWalkSql()
        assertTrue(
            "组级聚合六列（chatId/COUNT/today/MIN/MAX/窗计数）",
            sql.startsWith(
                "SELECT chatId, COUNT(*), SUM(dateEpochSec >= ?), MIN(dateEpochSec), MAX(dateEpochSec), SUM(dateEpochSec >= ?)",
            ),
        )
        assertTrue("INDEXED BY 锁 idx_items_date（防自然计划误选 album 回表——彩排实锤病根）", sql.contains("INDEXED BY idx_items_date"))
        assertTrue("分片区间谓词（可下压索引前导列）", sql.contains("WHERE dateEpochSec >= ? AND dateEpochSec < ?"))
        assertTrue("按频道分组", sql.contains("GROUP BY chatId"))
        assertTrue("聚合形状无分页", !sql.contains("LIMIT"))
    }

    @Test
    fun `走查臂 refs-别名表镜像同形状（表名与索引名外逐字一致）`() {
        val refs = chatStatsWalkRefsSql("repost_refs")
        assertTrue(refs.contains("FROM repost_refs INDEXED BY idx_refs_date"))
        assertEquals(
            chatStatsWalkSql().replaceFirst("FROM items", "FROM T").replaceFirst("idx_items_date", "IDX"),
            refs.replaceFirst("FROM repost_refs", "FROM T").replaceFirst("idx_refs_date", "IDX"),
        )
    }

    @Test
    fun `兜底 SQL-stats 臂=R318 前源码字面（降级路径行为不变锚）`() {
        assertEquals(
            "SELECT chatId, COUNT(*), SUM(CASE WHEN dateEpochSec >= ? THEN 1 ELSE 0 END), " +
                "MIN(dateEpochSec), MAX(dateEpochSec) FROM items GROUP BY chatId",
            chatStatsLegacyGroupSql(),
        )
    }

    @Test
    fun `兜底 SQL-窗口臂=R318 前源码字面`() {
        assertEquals(
            "SELECT chatId, COUNT(*) FROM items WHERE dateEpochSec >= ? GROUP BY chatId",
            chatStatsLegacyWindowSql(),
        )
    }

    @Test
    fun `兜底 SQL-refs 双臂镜像同形状`() {
        assertEquals(
            chatStatsLegacyGroupSql().replaceFirst("FROM items", "FROM repost_refs"),
            chatStatsLegacyGroupRefsSql("repost_refs"),
        )
        assertEquals(
            chatStatsLegacyWindowSql().replaceFirst("FROM items", "FROM repost_refs"),
            chatStatsLegacyWindowRefsSql("repost_refs"),
        )
    }

    // ---------------- 分片计划锚 ----------------

    @Test
    fun `分片计划-并集恰覆盖全域且互不重叠`() {
        val bounds = chatStatsChunkBounds(100L, 1100L, 10)
        assertEquals(10, bounds.size)
        for (i in bounds.indices) {
            assertTrue("片非空（宽域等宽切）", bounds[i][1] > bounds[i][0])
            if (i < bounds.size - 1) assertEquals("相邻片首尾相接", bounds[i + 1][0], bounds[i][1])
        }
        assertEquals("首片左界=域左界", 100L, bounds.first()[0])
        assertEquals("末片右界闭收 MAX+1（MAX 行是合法数据必须入片）", 1101L, bounds.last()[1])
        var union = 0L
        for (b in bounds) union += b[1] - b[0]
        assertEquals("并集宽度=全域+1（闭收）", 1001L, union)
    }

    @Test
    fun `分片计划-零值日期行入首片（MIN=0 覆盖性口径，旧 COUNT 同计数）`() {
        val bounds = chatStatsChunkBounds(0L, 1000L, 4)
        assertEquals(0L, bounds.first()[0])
    }

    @Test
    fun `分片计划-单点域与逆域与非法片数`() {
        val single = chatStatsChunkBounds(5L, 5L, 1)
        assertEquals(1, single.size)
        assertEquals(5L, single[0][0])
        assertEquals(6L, single[0][1])
        assertTrue("逆域（max<min）=空表", chatStatsChunkBounds(10L, 5L, 8).isEmpty())
        try {
            chatStatsChunkBounds(0L, 10L, 0)
            fail("chunks=0 应拒绝")
        } catch (expected: IllegalArgumentException) {
        }
    }

    // ---------------- 聚合器锚 ----------------

    @Test
    fun `聚合器-行级口径与旧 GROUP BY 逐字对齐`() {
        val acc = ChatStatsAccumulator(todayStartSec = 1000L, windowSinceSec = 900L)
        // 频道 7：0 值日期行（计数不窗不今）/窗口内非今日/今日内
        acc.add(7, 0)
        acc.add(7, 950)
        acc.add(7, 1500)
        // 频道 8：今日边界含（>=）与远古行
        acc.add(8, 1000)
        acc.add(8, 200)
        val snap = acc.snapshot()
        assertEquals(2, acc.chatCount())
        val s7 = snap.stats[7]!!
        assertEquals(3, s7.count)
        assertEquals(1, s7.todayCount)
        assertEquals(0, s7.minDateSec)
        assertEquals(1500, s7.maxDateSec)
        assertEquals(2, snap.recentCounts!![7])
        val s8 = snap.stats[8]!!
        assertEquals(2, s8.count)
        assertEquals(1, s8.todayCount)
        assertEquals(200, s8.minDateSec)
        assertEquals(1000, s8.maxDateSec)
        assertEquals(1, snap.recentCounts!![8]) // 窗口 ≥900：仅 1000 一行入窗（200 不入）
    }

    @Test
    fun `聚合器-双臂合并算术（refs 续喂=旧 Kotlin 侧合并：COUNT 加 MIN 小 MAX 大）`() {
        val acc = ChatStatsAccumulator(1000L, 900L)
        acc.add(1, 100) // items 臂
        acc.add(1, 500)
        acc.add(1, 1200) // refs 臂行（同一聚合器续喂即行级合并）
        val snap = acc.snapshot()
        assertEquals(3, snap.stats[1]!!.count)
        assertEquals(100, snap.stats[1]!!.minDateSec)
        assertEquals(1200, snap.stats[1]!!.maxDateSec)
        assertEquals(1, snap.recentCounts!![1]) // 窗口 ≥900：仅 1200 一行入窗（100/500 不入）
    }

    @Test
    fun `聚合器-组级并入与行级喂入等价（混合路径正确性锚）`() {
        val rowAcc = ChatStatsAccumulator(1000L, 900L)
        rowAcc.add(2, 100)
        rowAcc.add(2, 950)
        rowAcc.add(2, 1400)
        val groupAcc = ChatStatsAccumulator(1000L, 900L)
        groupAcc.mergeGroup(2, 3, 1, 100, 1400, 2) // 窗计数=行级 950+1400 两行入窗
        assertEquals(rowAcc.snapshot().stats[2], groupAcc.snapshot().stats[2])
        assertEquals(rowAcc.snapshot().recentCounts, groupAcc.snapshot().recentCounts)
    }

    @Test
    fun `聚合器-组级窗口缺席不并入窗口面（refs 窗臂失败=旧容错口径）`() {
        val acc = ChatStatsAccumulator(1000L, 900L)
        acc.mergeGroup(3, 2, 0, 100, 200, null)
        assertEquals(2, acc.snapshot().stats[3]!!.count)
        assertNull(acc.snapshot().recentCounts!![3])
    }

    // ---------------- 进度/取消语义锚 ----------------

    @Test
    fun `进度语义-整数百分比逐片回报`() {
        val progress = mutableListOf<Int>()
        var fed = 0
        runChatStatsChunks(
            chatStatsChunkBounds(0, 400, 4),
            feed = { _, _ -> fed++ },
            onProgress = { progress.add(it) },
            isCancelled = { false },
        )
        assertEquals(4, fed)
        assertEquals(listOf(25, 50, 75, 100), progress)
    }

    @Test
    fun `进度语义-512 片节流至整数档（防高频回调洪泛）`() {
        var calls = 0
        val seen = mutableSetOf<Int>()
        runChatStatsChunks(
            chatStatsChunkBounds(0, 100000, 512),
            feed = { _, _ -> },
            onProgress = { calls++; seen.add(it) },
            isCancelled = { false },
        )
        assertEquals("整数档 0..100 恰 101 档（无重复回报）", 101, calls)
        assertEquals((0..100).toSet(), seen)
    }

    @Test
    fun `取消语义-片间哨兵命中抛取消且已完成的片不重喂`() {
        var fed = 0
        try {
            runChatStatsChunks(
                chatStatsChunkBounds(0, 400, 4),
                feed = { _, _ -> fed++ },
                onProgress = {},
                isCancelled = { fed >= 2 }, // 第 3 片片间检查命中
            )
            fail("应抛 ChatStatsWalkCancelled")
        } catch (expected: ChatStatsWalkCancelled) {
            assertEquals(2, fed)
        }
    }

    @Test
    fun `取消语义-空片表零回调零抛出`() {
        var fed = 0
        var prog = 0
        runChatStatsChunks(
            emptyList(),
            feed = { _, _ -> fed++ },
            onProgress = { prog++ },
            isCancelled = { false },
        )
        assertEquals(0, fed)
        assertEquals(0, prog)
    }
}
