package dev.tgfeed.core.feed

import dev.tgfeed.core.td.ChannelBrief
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DiscoverTimeBandPolicy]（R215 ① 时段切换条纯策略）行为单测：
 * 三档窗口定义（本周 7d/全月 30d/全年 null=现状零变化——R229「此刻」档移除后
 * 的口径）、枚举序=chip 序、重排（计数主导/稳定平手/无计数殿后保序/空计数零
 * 变化/超集计数丢弃非候选）。
 */
class DiscoverTimeBandPolicyTest {

    private companion object {
        fun channel(id: Long) = ChannelBrief(id, "频道$id", null)
    }

    // ---------------- 档位定义 ----------------

    @Test
    fun `三档窗口秒长`() {
        assertEquals(7L * TimeWindowPolicy.DAY_SEC, DiscoverTimeBand.WEEK.windowSec)
        assertEquals(30L * TimeWindowPolicy.DAY_SEC, DiscoverTimeBand.MONTH.windowSec)
        assertNull(DiscoverTimeBand.YEAR.windowSec)
    }

    @Test
    fun `枚举序即 chip 序（本周|全月|全年）`() {
        assertEquals(
            listOf("本周", "全月", "全年"),
            DiscoverTimeBand.values().map { it.label },
        )
    }

    @Test
    fun `R229 此刻档已移除_不再有 1h 窗档位`() {
        // 用户终审：「正在更新」语义由追更 tab 承接——本枚举不得再有 NOW/1h 档
        assertTrue(DiscoverTimeBand.entries.none { it.label == "此刻" || it.windowSec == 3_600L })
    }

    @Test
    fun `全年档零查询零重排的档位前提`() {
        // 全年是默认档=丰度优先现状：windowSec=null 即调用方跳过查询的判据
        assertTrue(DiscoverTimeBand.YEAR.windowSec == null)
    }

    // ---------------- 重排 ----------------

    @Test
    fun `计数主导前移无计数殿后`() {
        val candidates = listOf(channel(1), channel(2), channel(3), channel(4))
        val counts = mapOf(3L to 7, 1L to 2) // 频道3 最活跃、频道1 有货、2/4 窗内无样本
        val out = rerankCandidatesByTimeWindow(candidates, counts)
        assertEquals(listOf(3L, 1L, 2L, 4L), out.map { it.id }) // 2/4 保原序殿后
    }

    @Test
    fun `平手稳定不乱序`() {
        val candidates = listOf(channel(10), channel(20), channel(30), channel(40))
        val counts = mapOf(20L to 5, 30L to 5, 40L to 5) // 三者平手，10 无计数
        val out = rerankCandidatesByTimeWindow(candidates, counts)
        // 平手组保持原相对序（20→30→40），无计数殿后
        assertEquals(listOf(20L, 30L, 40L, 10L), out.map { it.id })
    }

    @Test
    fun `空计数零变化（索引未就绪诚实降级）`() {
        val candidates = listOf(channel(1), channel(2), channel(3))
        assertEquals(candidates, rerankCandidatesByTimeWindow(candidates, emptyMap()))
    }

    @Test
    fun `计数含非候选频道自然丢弃`() {
        val candidates = listOf(channel(1), channel(2))
        val counts = mapOf(99L to 100, 2L to 1) // 99 无卡片承载（金矿候选交并的集外侧）
        val out = rerankCandidatesByTimeWindow(candidates, counts)
        assertEquals(listOf(2L, 1L), out.map { it.id })
    }

    @Test
    fun `全零计数等于原序`() {
        val candidates = listOf(channel(5), channel(6))
        val counts = mapOf(5L to 0, 6L to 0)
        assertEquals(listOf(5L, 6L), rerankCandidatesByTimeWindow(candidates, counts).map { it.id })
    }
}
