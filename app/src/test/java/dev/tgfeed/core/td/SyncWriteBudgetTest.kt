package dev.tgfeed.core.td

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [SyncWriteBudget] 全域云同步写预算闸单测（R300 期零②的锚）：
 * 30s 全局最小间隔 / FLOOD_WAIT 指数退避（取 max 不缩短）/ 成功清零 /
 * 批量槽（槽内免间隔、槽外互斥、静默窗连批量也拦）。
 */
class SyncWriteBudgetTest {

    @Before
    fun reset() = SyncWriteBudget.resetForTest()

    @Test
    fun `首写直通_30s内二写被拒_满30s放行`() {
        val t0 = 1_000_000L
        assertTrue(SyncWriteBudget.tryEnter(t0))
        assertFalse(SyncWriteBudget.tryEnter(t0 + 29_999L))
        assertTrue(SyncWriteBudget.tryEnter(t0 + 30_000L))
    }

    @Test
    fun `429退避_静默期连试都拒_窗满放行`() {
        val t0 = 1_000_000L
        assertTrue(SyncWriteBudget.tryEnter(t0))
        SyncWriteBudget.noteFlood(retryAfterSec = 120, nowMs = t0)
        assertFalse(SyncWriteBudget.tryEnter(t0 + 119_000L))
        assertTrue(SyncWriteBudget.blockedRemainingMs(t0 + 60_000L) > 0L)
        assertTrue(SyncWriteBudget.tryEnter(t0 + 120_000L))
    }

    @Test
    fun `429无retryAfter走指数退避_逐级翻倍`() {
        val t0 = 1_000_000L
        SyncWriteBudget.noteFlood(0, t0) // 第 1 级：30s
        assertTrue(SyncWriteBudget.blockedRemainingMs(t0) == 30_000L)
        SyncWriteBudget.noteFlood(0, t0 + 31_000L) // 第 2 级：60s
        assertTrue(SyncWriteBudget.blockedRemainingMs(t0 + 31_000L) == 60_000L)
        SyncWriteBudget.noteFlood(0, t0 + 91_000L) // 第 3 级：120s
        assertTrue(SyncWriteBudget.blockedRemainingMs(t0 + 91_000L) == 120_000L)
    }

    @Test
    fun `429退避取max_后到的小窗不冲短大窗`() {
        val t0 = 1_000_000L
        SyncWriteBudget.noteFlood(600, t0) // 服务端 600s 大窗
        val before = SyncWriteBudget.blockedRemainingMs(t0)
        SyncWriteBudget.noteFlood(1, t0 + 1000L) // 后到 1s 小窗不冲短
        assertTrue(SyncWriteBudget.blockedRemainingMs(t0 + 1000L) >= before - 1000L)
    }

    @Test
    fun `成功写清零退避计数_静默窗解除`() {
        val t0 = 1_000_000L
        SyncWriteBudget.noteFlood(300, t0)
        SyncWriteBudget.noteSuccess()
        assertEquals(0L, SyncWriteBudget.blockedRemainingMs(t0))
        // 计数清零：下次撞墙从第 1 级退避重算
        SyncWriteBudget.noteFlood(0, t0 + 1)
        assertEquals(30_000L, SyncWriteBudget.blockedRemainingMs(t0 + 1))
    }

    @Test
    fun `批量槽内多写免30s间隔_槽外仍受闸`() {
        val t0 = 1_000_000L
        assertTrue(SyncWriteBudget.beginBatch(t0))
        assertTrue(SyncWriteBudget.tryEnterBatch(t0 + 1_000L))
        assertTrue(SyncWriteBudget.tryEnterBatch(t0 + 2_000L)) // 槽内免间隔（分片连发）
        assertFalse(SyncWriteBudget.tryEnter(t0 + 3_000L))     // 槽外 30s 间隔仍在
        SyncWriteBudget.endBatch()
        assertFalse(SyncWriteBudget.tryEnterBatch(t0 + 4_000L)) // 槽外批写无效
        assertFalse(SyncWriteBudget.beginBatch(t0 + 30_000L))   // 距槽内最后写（t0+2s）未满 30s 仍拒
        assertTrue(SyncWriteBudget.beginBatch(t0 + 32_000L))    // 间隔满可开新批
        SyncWriteBudget.endBatch()
    }

    @Test
    fun `批量互斥_槽在飞不开二批`() {
        val t0 = 1_000_000L
        assertTrue(SyncWriteBudget.beginBatch(t0))
        assertFalse(SyncWriteBudget.beginBatch(t0 + 31_000L)) // 有批在飞，间隔满也拒
        SyncWriteBudget.endBatch()
        assertTrue(SyncWriteBudget.beginBatch(t0 + 31_000L))
        SyncWriteBudget.endBatch()
    }

    @Test
    fun `静默窗连批量槽也拦`() {
        val t0 = 1_000_000L
        assertTrue(SyncWriteBudget.beginBatch(t0))
        SyncWriteBudget.noteFlood(60, t0)
        assertFalse(SyncWriteBudget.tryEnterBatch(t0 + 1_000L)) // 槽内撞静默窗照拒
        SyncWriteBudget.endBatch()
        assertFalse(SyncWriteBudget.beginBatch(t0 + 30_000L))   // 静默期开批也拒
    }
}
