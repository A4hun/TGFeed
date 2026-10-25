package dev.tgfeed.core.td

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轮换采样 [TdLibGateway.sampleRotationPool]（GatewayFeedFetch.kt，fetchAggregated 新轮抽池）
 * 的行为单测：保底车道（最久未进池必被轮到）+ 加权车道（指数赛跑不放回抽样）。
 *
 * 被测函数是 TdLibGateway 的扩展函数，只触达三个 provider 回调
 * （channelWeightProvider / recentPoolProvider / poolRecencyProvider），
 * 用 mockk 假接收体 + 桩住三个 provider 即可纯 JVM 测试（不加载 native 库）。
 */
class SampleRotationPoolTest {

    /** 造一个桩好三个 provider 的假网关接收体 */
    private fun gateway(
        weights: Map<Long, Double> = emptyMap(),
        recent: Set<Long> = emptySet(),
        recency: Map<Long, Long> = emptyMap(),
    ): TdLibGateway {
        val gw = mockk<TdLibGateway>()
        every { gw.channelWeightProvider } returns { weights }
        every { gw.recentPoolProvider } returns { recent }
        every { gw.poolRecencyProvider } returns { recency }
        return gw
    }

    @Test
    fun `频道数不超过池容量时全量直取不轮换`() {
        // 行为：allIds.size <= MAX_AGGREGATE_CHANNELS(24) 时原样返回（轮换无意义）
        val gw = gateway()
        val ids = (1L..24L).toList()
        assertEquals(ids, gw.sampleRotationPool(ids))
        val few = (1L..10L).toList()
        assertEquals(few, gw.sampleRotationPool(few))
    }

    @Test
    fun `保底车道按最久未进池升序取队首`() {
        // 行为：100 频道里 51..100 从未进池（时刻 0），1..50 最近进过——
        // 保底 8 坑必须给 51..58（从未进池组内按 chatId 升序，与列表归属无关）
        val recency = (1L..50L).associateWith { 1_000_000L } // 51..100 无记录 = 从未进池
        val gw = gateway(recency = recency)
        val pool = gw.sampleRotationPool((1L..100L).toList())
        assertEquals(MAX_AGGREGATE_CHANNELS, pool.size)
        assertEquals(pool.size, pool.distinct().size) // 池内无重复
        assertTrue("保底坑位应给最久未进池的 51..58", pool.containsAll((51L..58L).toList()))
    }

    @Test
    fun `最近露脸频道在非露脸候选够一池时整轮排除`() {
        // 行为：50 频道中 1..26 最近露脸，非露脸 27..50 恰好 24 个 = 一池——
        // 露脸频道本轮完全排除（不是降权是轮休），池子恰好等于 27..50
        val gw = gateway(recent = (1L..26L).toSet())
        val pool = gw.sampleRotationPool((1L..50L).toList())
        assertEquals((27L..50L).toSet(), pool.toSet())
    }

    @Test
    fun `非露脸候选不足一池时露脸频道降权回补`() {
        // 行为：50 频道中 1..40 露脸，非露脸只剩 10 个（41..50）不够一池——
        // 全量参选、露脸频道权重 ×0.1。账面把非露脸频道标成"从未进池"，
        // 保底 8 坑恒落在 41..48（隔离保底车道干扰），只观测加权车道的降权效果：
        // 300 轮后，仅剩的 2 个非露脸加权候选（49/50）出池次数应数倍于任一露脸频道。
        val recent = (1L..40L).toSet()
        val recency = (1L..40L).associateWith { 1_000_000L } // 41..50 从未进池 → 保底 41..48
        val gw = gateway(recent = recent, recency = recency)
        val allIds = (1L..50L).toList()
        val counts = HashMap<Long, Int>()
        repeat(300) {
            gw.sampleRotationPool(allIds).forEach { id -> counts[id] = (counts[id] ?: 0) + 1 }
        }
        // 加权车道每轮 16 坑 × 300 轮 = 4800 次抽取：非露脸 49/50 权重 0.6，
        // 露脸 40 个各 0.06——指数赛跑下 49/50 几乎每轮必进（期望各 ≈300 次），
        // 露脸频道期望各 ≈105 次。断言宽 bound：非露脸合计 ≥500、露脸单频道 ≤300
        val freshCount = (counts[49L] ?: 0) + (counts[50L] ?: 0)
        val maxRecent = recent.maxOf { counts[it] ?: 0 }
        assertTrue("非露脸频道出池次数（$freshCount）应 ≫ 露脸频道（最多 $maxRecent）",
            freshCount >= 500 && maxRecent <= 300)
    }

    @Test
    fun `保底车道驱动多轮后覆盖全量频道`() {
        // 行为：模拟真实轮换——每轮把进池频道写回进池账面，100 频道 × 保底 8 坑/轮，
        // 13 轮（8×13=104 ≥ 100）后每个频道都至少进池一次——
        // "零画像长尾纯抽奖 4.4%/轮"被保底车道结构性消灭（覆盖全量）。
        val allIds = (1L..100L).toList()
        val recency = HashMap<Long, Long>()
        val covered = HashSet<Long>()
        var gw = gateway(recency = recency)
        repeat(13) { round ->
            val pool = gw.sampleRotationPool(allIds)
            covered += pool
            pool.forEach { recency[it] = round + 1L }
            gw = gateway(recency = recency) // provider 闭包读的是入参表，重建注入新账面
        }
        assertEquals("13 轮后保底车道应覆盖全部 100 个频道", allIds.toSet(), covered)
    }

    @Test
    fun `加权车道同权重下不偏头不饿死`() {
        // 行为：50 频道同权重（空权重表 = 全员探索档 0.6）、账面全空——
        // 保底车道恒取 chatId 最小的 1..8（确定性扫表，账面不更新时的既定行为），
        // 加权车道 16 坑在其余 42 个频道间均匀分布：500 轮后每个频道期望 190 次，
        // 断言落在 [100, 300] 宽带上（期望 ±50% 以外才算马太效应），且无一频道零出池。
        val gw = gateway()
        val allIds = (1L..50L).toList()
        val counts = HashMap<Long, Int>()
        repeat(500) {
            gw.sampleRotationPool(allIds).forEach { id -> counts[id] = (counts[id] ?: 0) + 1 }
        }
        for (id in 9L..50L) {
            val c = counts[id] ?: 0
            assertTrue("频道 $id 出池 $c 次，应落在均匀期望宽带 [100,300] 内", c in 100..300)
        }
        // 保底车道的既定确定性行为：账面不更新时恒为最小 8 个 chatId
        for (id in 1L..8L) {
            assertEquals(500, counts[id] ?: 0)
        }
    }
}
