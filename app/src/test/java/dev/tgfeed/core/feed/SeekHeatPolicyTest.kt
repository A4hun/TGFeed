package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R204 TL-7 拖拽落点热力纯函数单测（[SeekHeatPolicy] + InteractionStats key 级聚合 +
 * [FirstSeenLedger] 账本往返——同文件合并：都是回放热力/溯源族的纯层）。
 */
class SeekHeatPolicyTest {

    private fun landings(vararg ratios: Double) = ratios.map { SeekHeatPolicy.Landing(it) }

    // ---------------- binsOf ----------------

    @Test
    fun `分桶归一化_峰值桶为1`() {
        val bins = SeekHeatPolicy.binsOf(landings(0.0, 0.0, 0.5, 0.99))
        assertEquals(SeekHeatPolicy.BINS, bins.size)
        // 归一后：最大桶（0.0 双落）=1，其余按峰值比例
        assertEquals(1f, bins[0], 1e-6f)
        assertEquals(0.5f, bins[29], 1e-6f) // 0.5 → 第 29 桶（0.5×59 四舍五入前截断）
        assertEquals(0f, bins[30], 1e-6f)
        assertEquals(1f, bins.max(), 1e-6f)
    }

    @Test
    fun `越界落点夹取不崩`() {
        val bins = SeekHeatPolicy.binsOf(landings(-0.5, 1.7))
        val sum = bins.sum()
        assertEquals(2f, sum, 1e-6f)
    }

    @Test
    fun `空表全零桶`() {
        val bins = SeekHeatPolicy.binsOf(emptyList())
        assertTrue(bins.all { it == 0f })
    }

    // ---------------- insightOf ----------------

    @Test
    fun `样本不足不出洞察`() {
        assertNull(SeekHeatPolicy.insightOf(landings(0.5, 0.6)))
        assertNotNull(SeekHeatPolicy.insightOf(landings(0.5, 0.6, 0.7)))
    }

    @Test
    fun `峰段聚合与占比`() {
        // 8 个落点里 5 个聚在 0.62~0.75（峰段），2 个在开头，1 个在尾部
        val i = SeekHeatPolicy.insightOf(
            landings(0.02, 0.05, 0.62, 0.64, 0.66, 0.70, 0.72, 0.95),
        )!!
        assertEquals(8, i.samples)
        assertEquals(0.25, i.headRatio, 1e-9)
        // 尾部落点 = 0.70/0.72/0.95 三处 ≥0.7 → 3/8
        assertEquals(0.375, i.tailRatio, 1e-9)
        assertNotNull(i.peakStart)
        assertNotNull(i.peakEnd)
        assertTrue(i.peakStart!! > 0.5 && i.peakEnd!! < 0.85)
        val head = SeekHeatPolicy.insightHeadline(i)
        assertTrue(head.startsWith("你常跳到 "))
        assertTrue(head.contains("精彩段落"))
    }

    @Test
    fun `拖开头主导的频道文案`() {
        val i = SeekHeatPolicy.insightOf(landings(0.01, 0.02, 0.05, 0.08, 0.15, 0.5))!!
        assertTrue(SeekHeatPolicy.insightHeadline(i).contains("开头"))
    }

    @Test
    fun `分布平态回落平均落点文案`() {
        // 中段铺开的平态（头尾占比都不越阈）→ 平均落点文案
        val i = SeekHeatPolicy.insightOf(landings(0.2, 0.35, 0.5, 0.62, 0.75))!!
        assertTrue(SeekHeatPolicy.insightHeadline(i).contains("平均"))
        assertEquals("近 7 天 · 5 次拖拽 · 平均落点 48%", SeekHeatPolicy.insightSubline(i, 7))
    }

    // ---------------- InteractionStats key 级 / 频道级聚合（null stateFile = 纯内存） ----------------

    @Test
    fun `InteractionStats_seek落点按key聚合`() {
        val stats = InteractionStats(stateFile = null)
        // key 级：同 key 三次（够样本闸），别的 key 一次
        stats.recordSeek(11L, 60, 100, key = "kA")
        stats.recordSeek(11L, 70, 100, key = "kA")
        stats.recordSeek(11L, 62, 100, key = "kA")
        stats.recordSeek(22L, 10, 100, key = "kB")
        stats.recordSeek(11L, 30, 100, key = "") // 老口径（无 key）不进 key 级
        val kA = stats.seekLandingsByKey("kA")
        assertEquals(3, kA.size)
        assertTrue(kA.all { it.ratio in 0.6..0.7 })
        assertEquals(1, stats.seekLandingsByKey("kB").size)
        assertEquals(0, stats.seekLandingsByKey("").size)
        // 频道级：chatId=11 全部 4 条（含无 key 的一条与 kB 不算——kB 是频道 22）
        assertEquals(4, stats.seekLandingsByChannel(11L).size)
        assertEquals(1, stats.seekLandingsByChannel(22L).size)
    }

    @Test
    fun `无时长seek不进聚合`() {
        val stats = InteractionStats(stateFile = null)
        stats.recordSeek(11L, 60, 0, key = "kA") // durationSec=0 → 无法算比例
        assertEquals(0, stats.seekLandingsByKey("kA").size)
    }
}

/**
 * R204 DS-5 首发溯源账本单测（[FirstSeenLedger] 磁盘往返 + LRU 淘汰 + 文案）；
 * SQL 查询体（全表扫）= 设备验证面，JVM 不建库。
 */
class FirstSeenLedgerTest {

    @Test
    fun `账本往返与覆盖文案`() {
        val f = File.createTempFile("first_seen", ".json")
        val v = FirstSeenLedger.Verdict(
            fileUniqueId = "FU1",
            copies = listOf(
                FirstSeenLedger.Copy(101L, 10L, 1_700_000_000L, "频道A"),
                FirstSeenLedger.Copy(202L, 20L, 1_700_100_000L, "频道B"),
                FirstSeenLedger.Copy(303L, 30L, 1_700_200_000L, "频道C"),
            ),
            channels = 5,
            currentIsFirst = false,
            atMs = 1_800_000_000L,
        )
        FirstSeenLedger.save(f, v)
        val back = FirstSeenLedger.load(f)["FU1"]
        assertNotNull(back)
        assertEquals(v.copies, back!!.copies)
        assertEquals(v.channels, back.channels)
        assertEquals(v.currentIsFirst, back.currentIsFirst)
        assertEquals("在 3 个频道出现过 · 3 条副本", FirstSeenLedger.copiesLine(back))
        f.delete()
    }

    @Test
    fun `同指纹重查覆盖旧账`() {
        val f = File.createTempFile("first_seen", ".json")
        val mk: (Long) -> FirstSeenLedger.Verdict = { at ->
            FirstSeenLedger.Verdict(
                "FU1", listOf(FirstSeenLedger.Copy(1L, 1L, 100L, "c")), 3, true, at,
            )
        }
        FirstSeenLedger.save(f, mk(100L))
        FirstSeenLedger.save(f, mk(200L))
        val back = FirstSeenLedger.load(f)
        assertEquals(1, back.size)
        assertEquals(200L, back["FU1"]!!.atMs)
        f.delete()
    }

    @Test
    fun `LRU超限淘汰最旧`() {
        val f = File.createTempFile("first_seen", ".json")
        val budget = FirstSeenLedger.MAX_ENTRIES + 10
        for (i in 0 until budget) {
            FirstSeenLedger.save(
                f,
                FirstSeenLedger.Verdict(
                    "FU$i", listOf(FirstSeenLedger.Copy(i.toLong(), 1L, 1L, "c$i")),
                    3, false, atMs = i.toLong(),
                ),
            )
        }
        val back = FirstSeenLedger.load(f)
        assertEquals(FirstSeenLedger.MAX_ENTRIES, back.size)
        // 最旧的 10 条被淘汰（atMs 0..9 不在），最新（budget-1）在
        assertTrue(!back.containsKey("FU0"))
        assertTrue(!back.containsKey("FU9"))
        assertTrue(back.containsKey("FU${budget - 1}"))
        f.delete()
    }

    @Test
    fun `坏文件按空账本处理`() {
        val f = File.createTempFile("first_seen", ".json")
        f.writeText("{not json")
        assertEquals(0, FirstSeenLedger.load(f).size)
        f.delete()
    }
}
