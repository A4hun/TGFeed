package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R293 分代分层召回策略（[RecallSlicePolicy]）纯函数锚——任务①召回侧时间偏置
 * 根治件的判定真值：词频闸/规模估计/量程切代的边界与不变量全在这里锁死。
 */
class RecallSlicePolicyTest {

    // ---------------- estimateRecallSize ----------------

    @Test
    fun `AND 取最稀有词位（AND 单调收敛上界）`() {
        assertEquals(120L, RecallSlicePolicy.estimateRecallSize(true, listOf(5000L, 120L, 900L), 1_000_000L))
        assertEquals(0L, RecallSlicePolicy.estimateRecallSize(true, listOf(0L, 5000L), 1_000_000L))
    }

    @Test
    fun `OR 求和且封顶总文档数`() {
        assertEquals(300L, RecallSlicePolicy.estimateRecallSize(false, listOf(100L, 200L), 1_000_000L))
        // 同义词扩展把 df 和吹过 N 时封顶 N（并集不超全库）
        assertEquals(1_000L, RecallSlicePolicy.estimateRecallSize(false, listOf(900L, 900L), 1_000L))
    }

    @Test
    fun `空词位=0（调用方本就该短路）`() {
        assertEquals(0L, RecallSlicePolicy.estimateRecallSize(true, emptyList(), 1_000_000L))
        assertEquals(0L, RecallSlicePolicy.estimateRecallSize(false, emptyList(), 1_000_000L))
    }

    // ---------------- shouldStratify ----------------

    @Test
    fun `闸=严格大于 GATE_FACTOR 倍 cap`() {
        val cap = 2000
        val gate = RecallSlicePolicy.GATE_FACTOR.toLong() * cap
        assertFalse(RecallSlicePolicy.shouldStratify(gate, cap))       // 恰好打满不分层
        assertFalse(RecallSlicePolicy.shouldStratify(gate - 1, cap))
        assertTrue(RecallSlicePolicy.shouldStratify(gate + 1, cap))    // 截断率超 75% 才分层
        assertFalse(RecallSlicePolicy.shouldStratify(100_000L, 0))     // 非法 cap 永不分层
    }

    // ---------------- slices ----------------

    @Test
    fun `正常量程：覆盖完整无洞无叠+新到旧序+每代额度`() {
        val minD = 1_000L
        val maxD = 8_999L // span=8000，B=8 → step=1000 整除
        val out = RecallSlicePolicy.slices(minD, maxD, bucketCount = 8, cap = 2000, oversample = 2)
        assertEquals(8, out.size)
        // 每代 limit = ⌈cap×oversample/B⌉ = 500
        assertTrue(out.all { it.limit == 500 })
        // 新→旧序：首元素是量程最新代
        assertEquals(maxD + 1, out.first().toSecExclusive)
        // 末元素 from=0（兜 dateEpochSec=0 存量行）
        assertEquals(0L, out.last().fromSec)
        // 相邻代边界相接（降序下：本代 from = 上一代 to）
        for (i in 0 until out.size - 1) {
            assertEquals("代 $i 与 ${i + 1} 边界须相接", out[i].fromSec, out[i + 1].toSecExclusive)
        }
        // 升序重构后覆盖 [0, max+1) 无洞
        val asc = out.reversed()
        assertEquals(0L, asc.first().fromSec)
        assertEquals(maxD + 1, asc.last().toSecExclusive)
    }

    @Test
    fun `非整除量程：末代兜底到 max+1 且代数不超帽`() {
        val out = RecallSlicePolicy.slices(1L, 100L, bucketCount = 8, cap = 2000, oversample = 2)
        assertTrue(out.size in 1..8)
        assertEquals(101L, out.first().toSecExclusive)
        assertEquals(0L, out.last().fromSec)
        val asc = out.reversed()
        for (i in 0 until asc.size - 1) {
            assertEquals(asc[i].toSecExclusive, asc[i + 1].fromSec)
        }
    }

    @Test
    fun `退化量程拒切=空表信号走旧单路`() {
        assertTrue(RecallSlicePolicy.slices(0L, 0L, 8, 2000, 2).isEmpty())        // 无有效日期
        assertTrue(RecallSlicePolicy.slices(5L, 3L, 8, 2000, 2).isEmpty())        // 倒置
        assertTrue(RecallSlicePolicy.slices(100L, 104L, 8, 2000, 2).isEmpty())    // 量程比代还窄
        assertTrue(RecallSlicePolicy.slices(-5L, 100L, 8, 2000, 2).isNotEmpty())  // min<1 收编为 1 正常切
    }

    @Test
    fun `非法参数硬拒（require）`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            RecallSlicePolicy.slices(1L, 1000L, 0, 2000, 2)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            RecallSlicePolicy.slices(1L, 1000L, 8, 0, 2)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            RecallSlicePolicy.slices(1L, 1000L, 8, 2000, 0)
        }
    }

    @Test
    fun `首代 from=0 与其余代互斥（dateEpochSec=0 行只落首代）`() {
        val out = RecallSlicePolicy.slices(1L, 8_000L, 8, 2000, 2)
        val asc = out.reversed()
        assertEquals(0L, asc.first().fromSec)
        // 第二代起的 from 都 ≥ 1（与首代 [0, x) 不叠）
        assertTrue(asc.drop(1).all { it.fromSec >= asc.first().toSecExclusive })
    }
}
