package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R283 BinaryStarPolicy（双星判定维度化政策件）单测：
 * 量纲校准表（旧「合并边权≥8」对照）/强证据门（共现独不过门）/时间档加成/
 * 缺据中性/选择确定性/每星单对/资格谓词——政策语义全锚。
 */
class BinaryStarPolicyTest {

    private fun ev(
        a: Long = -1, b: Long = -2,
        forward: Int = 0, link: Int = 0, coWatch: Int = 0,
        sharedDirected: Int = 0, sharedCoOccur: Int = 0,
        spanDays: Int = -1, recentDays: Int = -1,
    ) = BinaryStarPolicy.PairEvidence(a, b, forward, link, coWatch, sharedDirected, sharedCoOccur, spanDays, recentDays)

    // ---------------- 量纲校准（旧门对照表） ----------------

    @Test
    fun `旧门水位保留（8条纯互转或纯互链照旧过门）`() {
        assertTrue(BinaryStarPolicy.passes(ev(forward = 8)))
        assertTrue(BinaryStarPolicy.passes(ev(link = 8)))
        assertTrue(BinaryStarPolicy.passes(ev(forward = 4, link = 3, coWatch = 1)))
    }

    @Test
    fun `旧门挡下的单薄对照旧挡下（7条纯互链=门下）`() {
        assertFalse(BinaryStarPolicy.passes(ev(link = 7)))
        assertFalse(BinaryStarPolicy.passes(ev(forward = 1, link = 1))) // 3.47 单线索不成系统
    }

    @Test
    fun `共现弱证单独不过门（旧门可过的假双星挡掉）`() {
        assertFalse(BinaryStarPolicy.passes(ev(coWatch = 8))) // 强证据门拦
        assertFalse(BinaryStarPolicy.passes(ev(coWatch = 100))) // 刷量也不过
    }

    @Test
    fun `维度齐全中量对可过门（升级的刻意放水方向）`() {
        assertTrue(BinaryStarPolicy.passes(ev(forward = 2, link = 2))) // 5.49
        assertTrue(BinaryStarPolicy.passes(ev(forward = 3, link = 3))) // 6.93
    }

    @Test
    fun `内容同源方向条计入强证据与维度分`() {
        // 图谱边薄（互链数据薄场景）但内容级互转 5 条：新证据维独撑过门
        assertFalse(BinaryStarPolicy.passes(ev(sharedDirected = 4))) // 4.02 门下
        assertTrue(BinaryStarPolicy.passes(ev(sharedDirected = 5))) // 4.48 过门
        // 共现内容条只加分不过门
        assertFalse(BinaryStarPolicy.passes(ev(sharedCoOccur = 30)))
    }

    // ---------------- 时间分布档 ----------------

    @Test
    fun `时间档加成（持续且活跃大于仅持续大于无）`() {
        val base = ev(link = 8)
        val spanOnly = ev(link = 8, spanDays = 45)
        val active = ev(link = 8, spanDays = 45, recentDays = 5)
        assertEquals(0f, BinaryStarPolicy.timeBonusOf(base), 1e-4f)
        assertEquals(BinaryStarPolicy.TIME_BONUS_SPAN_ONLY, BinaryStarPolicy.timeBonusOf(spanOnly), 1e-4f)
        assertEquals(BinaryStarPolicy.TIME_BONUS_ACTIVE, BinaryStarPolicy.timeBonusOf(active), 1e-4f)
        assertTrue(BinaryStarPolicy.scoreOf(active) > BinaryStarPolicy.scoreOf(spanOnly))
        assertTrue(BinaryStarPolicy.scoreOf(spanOnly) > BinaryStarPolicy.scoreOf(base))
    }

    @Test
    fun `短跨度不吃加成（一次性互转）`() {
        assertEquals(0f, BinaryStarPolicy.timeBonusOf(ev(link = 8, spanDays = 29, recentDays = 1)), 1e-4f)
    }

    @Test
    fun `缺据中性（span或recent缺失不惩罚）`() {
        assertEquals(0f, BinaryStarPolicy.timeBonusOf(ev(link = 8, spanDays = -1, recentDays = 3)), 1e-4f)
        assertEquals(0f, BinaryStarPolicy.timeBonusOf(ev(link = 8, spanDays = -1, recentDays = -1)), 1e-4f)
    }

    // ---------------- 选择（确定性/每星单对/资格/截断） ----------------

    @Test
    fun `选择确定性（分降序同分chatId升序）`() {
        val c = listOf(
            ev(a = -5, b = -6, forward = 8), // 6.59
            ev(a = -1, b = -2, forward = 8), // 同分
            ev(a = -3, b = -4, link = 8), // 4.39
        )
        val v = BinaryStarPolicy.selectBinaryPairs(c, 6)
        assertEquals(3, v.size)
        assertEquals(-5L, v[0].a) // 同分按 a 升序（负值小者先）
        assertEquals(-1L, v[1].a)
        assertEquals(-3L, v[2].a)
    }

    @Test
    fun `每星只入一对`() {
        val c = listOf(
            ev(a = -1, b = -2, forward = 8),
            ev(a = -1, b = -3, forward = 7), // -1 已入对 → 跳过
            ev(a = -3, b = -4, forward = 6),
        )
        val v = BinaryStarPolicy.selectBinaryPairs(c, 6)
        assertEquals(2, v.size)
        assertTrue(v.none { it.a == -1L && it.b == -3L })
    }

    @Test
    fun `资格谓词剔除（实体天体不入双星）与top截断`() {
        val c = (1..10).map { ev(a = -(it * 2L), b = -(it * 2L + 1), forward = 8) }
        val v = BinaryStarPolicy.selectBinaryPairs(c, 3, eligible = { it != -2L })
        assertEquals(3, v.size)
        assertTrue(v.none { it.a == -2L || it.b == -2L })
    }

    @Test
    fun `展示口径mergedWeight保持图谱合并计数`() {
        val v = BinaryStarPolicy.selectBinaryPairs(listOf(ev(a = -1, b = -2, forward = 4, link = 3, coWatch = 1)), 6)
        assertEquals(1, v.size)
        assertEquals(8, v[0].mergedWeight) // 信息卡「N 条关联」口径不受维度化影响
    }
}
