package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DeadMediaPolicy]（R163 B5 死链降权纯函数）的行为单测：
 * 纪律①默认关（记账照常、降权零生效）、阈值严格大于、最小样本双闸（防小样本误杀）、
 * 系数表只含命中频道、乘性/加性两条折算路径的算术。
 */
class DeadMediaPolicyTest {

    private companion object {
        const val EPS = 1e-9
    }

    private fun stat(dead: Int, impressions: Int) = InteractionStats.DeadChannelStat(dead, impressions)

    @Test
    fun `默认配置降权关_再差的统计也不降`() {
        // 行为：纪律①——Config() 默认 demoteEnabled=false，排序口径与现状完全一致；
        // 记账（recordDeadMedia）不受此开关影响，照常积累数据
        val cfg = DeadMediaPolicy.Config() // 默认关
        val terrible = stat(dead = 9, impressions = 10) // 死链率 90%
        assertFalse(DeadMediaPolicy.isDemoted(terrible, cfg))
        assertTrue(DeadMediaPolicy.demotionFactors(mapOf(1L to terrible), cfg).isEmpty())
    }

    @Test
    fun `死链率阈值严格大于_等于不降`() {
        // 行为：>30% 才降（0.30 恰等于不降——边界保守，防恰在线上的抖动）
        val cfg = DeadMediaPolicy.Config(demoteEnabled = true)
        assertTrue(DeadMediaPolicy.isDemoted(stat(4, 10), cfg))  // 0.4 > 0.3
        assertFalse(DeadMediaPolicy.isDemoted(stat(3, 10), cfg)) // 0.3 == 0.3 不降
        assertFalse(DeadMediaPolicy.isDemoted(stat(2, 10), cfg)) // 0.2 < 0.3
    }

    @Test
    fun `最小样本双闸_曝光不足或死链仅一次都不降`() {
        // 行为：曝光 <5（分母不可信）或 deadmedia <2（单次失败可能是偶发）都免降——
        // 「没有证据不臆测」；两者都过才看率
        val cfg = DeadMediaPolicy.Config(demoteEnabled = true)
        assertFalse(DeadMediaPolicy.isDemoted(stat(dead = 3, impressions = 4), cfg)) // 曝光不足，率 0.75 也不降
        assertFalse(DeadMediaPolicy.isDemoted(stat(dead = 1, impressions = 10), cfg)) // 死链仅 1 次
        assertTrue(DeadMediaPolicy.isDemoted(stat(dead = 2, impressions = 5), cfg)) // 0.4 全过
    }

    @Test
    fun `无曝光时死链率零_防除零`() {
        // 行为：曝光 0（异常/冷启动）rate=0.0 不抛不降
        val s = stat(dead = 3, impressions = 0)
        assertEquals(0.0, s.rate, EPS)
        assertFalse(DeadMediaPolicy.isDemoted(s, DeadMediaPolicy.Config(demoteEnabled = true)))
    }

    @Test
    fun `系数表只含命中频道_未命中的不占位`() {
        // 行为：demotionFactors 只给被降频道（消费方按 1.0 缺省——零开销直通语义）
        val cfg = DeadMediaPolicy.Config(demoteEnabled = true)
        val stats = mapOf(
            1L to stat(4, 10),  // 0.4 → 降
            2L to stat(1, 10),  // 死链 1 次 → 不降
            3L to stat(2, 5),   // 0.4 → 降
        )
        val factors = DeadMediaPolicy.demotionFactors(stats, cfg)
        assertEquals(mapOf(1L to cfg.penaltyFactor, 3L to cfg.penaltyFactor), factors)
    }

    @Test
    fun `乘性降权只压命中频道_不在权重表的频道不动`() {
        // 行为：applyPenalty 对权重表内的命中频道 ×factor；不在权重表的频道（无权重
        // 可降）跳过；系数表空 = 原表直通（同一引用，零分配）
        val factors = mapOf(1L to 0.5, 99L to 0.5)
        val weights = mapOf(1L to 1.0, 2L to 0.8, 3L to 0.4)
        val out = DeadMediaPolicy.applyPenalty(weights, factors)
        assertEquals(0.5, out.getValue(1L), EPS) // 命中：×0.5
        assertEquals(0.8, out.getValue(2L), EPS) // 未命中：不动
        assertEquals(0.4, out.getValue(3L), EPS)
        assertFalse(out.containsKey(99L))        // 不在权重表：不凭空造条目
        // 空系数表直通
        assertTrue(DeadMediaPolicy.applyPenalty(weights, emptyMap()) === weights)
    }

    @Test
    fun `加性折算路径算术_等于源内乘性语义`() {
        // 行为：foldFactorIntoBoost(boost, base, f) = boost + base×(f−1)——
        // 源内 final = base + boost'，经此折算 final ≈ base×f + boost（会话系数同款折算法）
        // boost=0.3 base=1.0 f=0.5 → 0.3 + 1.0×(−0.5) = −0.2（源内 clamp(0.3,2.0) 兜底，
        // 既有近似口径——低权重频道压不到理论值，可接受）
        assertEquals(-0.2, DeadMediaPolicy.foldFactorIntoBoost(0.3, 1.0, 0.5), EPS)
        // f=1.0（未降）恒等不动
        assertEquals(0.7, DeadMediaPolicy.foldFactorIntoBoost(0.7, 1.2, 1.0), EPS)
    }
}
