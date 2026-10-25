package dev.tgfeed.core.feed

import dev.tgfeed.core.td.TD_MSG_ID_SEQ_SHIFT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R285 存量链式回填规划（planChainHealing）单测：
 *  - TDLib messageId 2^20 间距（[TD_MSG_ID_SEQ_SHIFT]，判定核唯一持有）——
 *    相邻判定在序号域；
 *  - 链②组检测与成员上下文（组大小/messageId 升序位次）；
 *  - 链④映射：散媒体带文行=批注源替身 / 带文相册成员=透明 / 纯标签行=不当源 /
 *    ping 行重置；②>④ 互斥（链②可救组不吃链④）。
 * 输入行一律 messageId DESC（新→旧，生产侧查询序）。
 */
class ChainHealingPlanTest {

    private fun seq(s: Long) = s shl TD_MSG_ID_SEQ_SHIFT

    private fun row(rowid: Long, seqNo: Long, albumId: Long = 0L, caption: String = "") =
        ChainPlanRow(rowid, seq(seqNo), albumId, caption)

    @Test
    fun `链②_组内有caption则空成员列入治疗`() {
        val plan = planChainHealing(
            listOf(
                row(1, 103, albumId = 7L),                 // 空成员（新→旧序最前）
                row(2, 102, albumId = 7L),                 // 空成员
                row(3, 101, albumId = 7L, caption = "共享文案 #合集"),
            ),
        )
        assertEquals(2, plan.size)
        val h = plan.first { it.rowid == 1L }
        assertTrue(h.viaChain2)
        assertEquals(3, h.memberCount)
        assertEquals(2, h.memberIndex) // messageId 升序位次：101,102,103 → rowid1 在 2 位
        val h2 = plan.first { it.rowid == 2L }
        assertEquals(1, h2.memberIndex)
    }

    @Test
    fun `链②_全空组不治疗`() {
        val plan = planChainHealing(
            listOf(row(1, 102, albumId = 7L), row(2, 101, albumId = 7L)),
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `链④_散媒体带文行批注紧随空媒体`() {
        val plan = planChainHealing(
            listOf(
                row(1, 105, caption = "本批剧照 12 张"), // 批注源（更新）
                row(2, 104),                             // 空散媒体
                row(3, 103),                             // 空散媒体
            ),
        )
        assertEquals(2, plan.size)
        val h = plan.first { it.rowid == 2L }
        assertFalse(h.viaChain2)
        assertEquals("本批剧照 12 张", h.note)
        assertEquals(1, h.memberCount)
        assertEquals(0, h.memberIndex)
    }

    @Test
    fun `链④_2的20次方间距内序号相邻才算相邻`() {
        // messageId 差一个 2^20 = 序号相邻 → 批注成立；差两个序号 = 断链
        val plan = planChainHealing(
            listOf(
                row(1, 105, caption = "批注正文"),
                row(2, 104),  // 序号相邻 → 救
                row(3, 102),  // 序号差 2 → 断链不救
            ),
        )
        assertEquals(listOf(2L), plan.map { it.rowid })
    }

    @Test
    fun `链④_全空相册组被邻近批注救回且带组上下文`() {
        val plan = planChainHealing(
            listOf(
                row(1, 105, caption = "整组说明"),
                row(2, 104, albumId = 9L),
                row(3, 103, albumId = 9L),
                row(4, 102, albumId = 9L),
            ),
        )
        assertEquals(3, plan.size)
        val h = plan.first { it.rowid == 3L }
        assertFalse(h.viaChain2)
        assertEquals(3, h.memberCount)
        assertEquals(1, h.memberIndex) // 组内升序 102,103,104 → rowid3 在 1 位
    }

    @Test
    fun `链④_纯标签行不当批注源`() {
        val plan = planChainHealing(
            listOf(
                row(1, 105, caption = "#美图 #合集"), // 纯标签行=透明媒体
                row(2, 104),
            ),
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `链④_ping形态行重置批注态`() {
        // ping 散媒体（无字母数字 caption）砸断既有批注链——与网关 ping 守卫同口径
        val plan = planChainHealing(
            listOf(
                row(1, 106, caption = "批注正文"),
                row(2, 105, caption = "🔔"), // ping 形态散媒体：重置
                row(3, 104),
            ),
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `链②优先于链④_互斥`() {
        // 组内有 caption 的相册空成员：链②治疗，不吃上方散媒体的批注（隔断）
        val plan = planChainHealing(
            listOf(
                row(1, 105, caption = "上方批注"),
                row(2, 104, albumId = 7L),
                row(3, 103, albumId = 7L, caption = "组内共享"),
            ),
        )
        assertEquals(1, plan.size)
        assertTrue(plan[0].viaChain2)
        assertEquals(2L, plan[0].rowid)
    }

    @Test
    fun `链④到②接管_全空相册超上限成员转链②收口`() {
        // 16416 实证形态缝：全空 8 人相册+统一批注——链④按上限批 6 名，余 2 名
        // 由「④批一员→②接管全组」转链②治疗（相册=完整内容单元，防半愈劈开）
        val rows = buildList {
            add(row(100, 200, caption = "整组统一批注"))
            for (i in 0 until 8) add(row(i.toLong(), 199L - i, albumId = 9L))
        }
        val plan = planChainHealing(rows)
        assertEquals(8, plan.size)
        assertEquals(6, plan.count { !it.viaChain2 })
        assertEquals(2, plan.count { it.viaChain2 })
        // 转链②的是序号最旧的 2 名成员
        val ext = plan.filter { it.viaChain2 }.map { it.rowid }.sorted()
        assertEquals(listOf(6L, 7L), ext)
    }

    @Test
    fun `链④_带文相册成员不外溢批注`() {
        // 相册带文成员只服务本组链②；更旧的别组空媒体不得吃它的 caption
        val plan = planChainHealing(
            listOf(
                row(1, 105, albumId = 7L, caption = "剧名 第1期"),
                row(2, 104, albumId = 7L),
                row(3, 103), // 别组散媒体：不得被"剧名 第1期"批注
            ),
        )
        assertEquals(listOf(2L), plan.map { it.rowid })
        assertTrue(plan[0].viaChain2)
    }

    @Test
    fun `链④_上限6条`() {
        val rows = buildList {
            add(row(100, 200, caption = "批注正文"))
            for (i in 0 until 8) add(row(i.toLong(), 199L - i))
        }
        val plan = planChainHealing(rows)
        assertEquals(6, plan.size)
    }

    @Test
    fun `幂等前提_空输入零产出`() {
        assertTrue(planChainHealing(emptyList()).isEmpty())
        // 全部非空 caption：无任何治疗
        assertTrue(
            planChainHealing(
                listOf(row(1, 102, caption = "有文"), row(2, 101, caption = "也有")),
            ).isEmpty(),
        )
    }
}
