package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R321 洛希极限单测（r315 D⑤ 锚表+任务书单测锚全套）：形变帽域/合体回归锚
 * （永不重合数学证明的逐域采样）/d 与形变量单调/域外恒零/潮汐尾长度域+中点截止
 * /撞车档同源分门（top 才出预警行）。
 */
class RocheTidalFxTest {

    // ---------------- 形变曲线（帽 0.28 铁语义） ----------------

    @Test
    fun `形变帽-0_28半径域与运行时护栏`() {
        assertEquals(0.28f, RocheTidalFx.DEFORM_CAP, 0.0001f)
        // 名义域：def=1 → scaleX=1.25（r315-D② 字面）/scaleY=0.88
        assertEquals(1.25f, RocheTidalFx.scaleXOf(1f), 0.0001f)
        assertEquals(0.88f, RocheTidalFx.scaleYOf(1f), 0.0001f)
        // 域外（def=0）恒等：零形变=现状零改
        assertEquals(1f, RocheTidalFx.scaleXOf(0f), 0.0001f)
        assertEquals(1f, RocheTidalFx.scaleYOf(0f), 0.0001f)
        // 病态输入（超界 def）→ 域内钳（名义 1.25 上限；帽 0.28=护栏不变量：
        // 任何输入伸长量 ≤ELONG<DEFORM_CAP——永不重合证明取更保守的帽值）
        assertEquals(1.25f, RocheTidalFx.scaleXOf(9f), 0.0001f)
        assertEquals(0.88f, RocheTidalFx.scaleYOf(9f), 0.0001f)
        assertEquals(1f, RocheTidalFx.scaleXOf(-3f), 0.0001f)
        // 帽不变量：任何形变量下 1≤scaleX≤1+DEFORM_CAP ∧ 1−DEFORM_CAP≤scaleY≤1
        for (i in 0..20) {
            val d = i / 10f - 0.5f // 含负域（防御）
            val sx = RocheTidalFx.scaleXOf(d)
            val sy = RocheTidalFx.scaleYOf(d)
            assertTrue(sx <= 1f + RocheTidalFx.DEFORM_CAP + 1e-6f)
            assertTrue(sx >= 1f - 1e-6f)
            assertTrue(sy >= 1f - RocheTidalFx.DEFORM_CAP - 1e-6f)
            assertTrue(sy <= 1f + 1e-6f)
        }
        // 单调：形变量越大伸得越长/压得越扁
        var prevX = 0f
        var prevY = 2f
        for (i in 0..20) {
            val d = i / 20f
            val sx = RocheTidalFx.scaleXOf(d)
            val sy = RocheTidalFx.scaleYOf(d)
            assertTrue("scaleX 随 def 单调不减", sx >= prevX - 1e-6f)
            assertTrue("scaleY 随 def 单调不增", sy <= prevY + 1e-6f)
            prevX = sx
            prevY = sy
        }
    }

    @Test
    fun `合体回归锚-永不重合逐域采样（重合率保卫线）`() {
        // 「永不合体」铁语义的数学本体：伸长后两星表面间隙 = d_roche − 1×(1+伸长)
        // ×(ρa+ρb) ≥ HARD_GAP − DEFORM_CAP×(ρa+ρb) 恒正——体量半径域 [2.2, 26]
        // （TasteEmergentLayout rho 口径）逐边界+中点采样
        val rhos = floatArrayOf(2.2f, 5f, 13f, 26f)
        for (ra in rhos) for (rb in rhos) {
            val dRoche = RocheTidalFx.dRocheOf(ra, rb)
            // 洛希域=硬核带（单一出处——不两处写 ρa+ρb+40）
            assertEquals(
                "d_roche=hardMinDist 单源（ρa=$ra ρb=$rb）",
                TasteEmergentForces.hardMinDist(ra, rb), dRoche, 0.0001f,
            )
            // 顶格形变下两星伸长半径和
            val elongated = RocheTidalFx.elongatedRadiusOf(ra, 1f) +
                RocheTidalFx.elongatedRadiusOf(rb, 1f)
            val gap = dRoche - elongated
            assertTrue(
                "顶格形变表面间隙恒正（永不重合：ρa=$ra ρb=$rb 间隙=$gap）",
                gap > 0f,
            )
            // 帽的充分性：0.28×(ρa+ρb) ≪ 40（全域最大 14.56）
            assertTrue(
                RocheTidalFx.DEFORM_CAP * (ra + rb) < TasteEmergentForces.HARD_GAP,
            )
        }
    }

    @Test
    fun `形变量-d域内外单调与域外恒零`() {
        val dRoche = RocheTidalFx.dRocheOf(10f, 12f) // =22+40=62
        // 域外恒零（零成本早退口径）
        assertEquals(0f, RocheTidalFx.def01Of(dRoche, dRoche), 0.0001f)
        assertEquals(0f, RocheTidalFx.def01Of(dRoche * 3f, dRoche), 0.0001f)
        assertEquals(0f, RocheTidalFx.def01Of(-5f, dRoche), 0.0001f) // 病态负距防御
        assertEquals(0f, RocheTidalFx.def01Of(50f, 0f), 0.0001f) // 病态零域防御
        // 域内：贴面最狠（d→0 → 1）、域沿松开（d→d_roche → 0）；单调
        assertEquals(1f, RocheTidalFx.def01Of(0.001f, dRoche), 0.0001f)
        var prev = 2f
        for (i in 0..30) {
            val d = dRoche * i / 30f
            val v = RocheTidalFx.def01Of(d, dRoche)
            assertTrue("def 随 d 单调不增（d=$d）", v <= prev + 1e-6f)
            prev = v
        }
        assertEquals(0f, prev, 0.0001f)
        // 绕转呼吸语义：d 越近形变越深（近心点撕得狠/远心点松开——r315-D④）
        assertTrue(
            RocheTidalFx.def01Of(30f, dRoche) > RocheTidalFx.def01Of(50f, dRoche),
        )
    }

    // ---------------- 潮汐尾（撕扯但没并） ----------------

    @Test
    fun `潮汐尾-粒数域与alpha域`() {
        // 粒数 3~5（确定性哈希）
        for (seed in intArrayOf(0, 7, 12345, -999, 8675309)) {
            val n = RocheTidalFx.tailDots(seed)
            assertTrue("尾粒数域 3~5（seed=$seed 实=$n）", n in 3..5)
            assertEquals("同 seed 确定性", n, RocheTidalFx.tailDots(seed))
        }
        // alpha 0.05~0.12 线性 ∝ 形变
        assertEquals(0.05f, RocheTidalFx.tailAlphaOf(0f), 0.0001f)
        assertEquals(0.12f, RocheTidalFx.tailAlphaOf(1f), 0.0001f)
        assertEquals(0.12f, RocheTidalFx.tailAlphaOf(9f), 0.0001f) // 防御钳域
        assertTrue(RocheTidalFx.tailAlphaOf(0.5f) > RocheTidalFx.tailAlphaOf(0.2f))
    }

    @Test
    fun `潮汐尾-长度域与中点前截止`() {
        // 域外零
        assertEquals(0f, RocheTidalFx.tailLenPx(0f, 300f), 0.0001f)
        assertEquals(0f, RocheTidalFx.tailLenPx(0.8f, 0f), 0.0001f)
        // 长度 ∝ 形变量（同屏距单调）
        val l1 = RocheTidalFx.tailLenPx(1f, 300f)
        val l2 = RocheTidalFx.tailLenPx(0.5f, 300f)
        assertTrue(l1 > l2)
        // **止于两星中点前**：任何形变量下长度 < 0.5×屏距
        for (i in 0..10) {
            val d = i / 10f
            val len = RocheTidalFx.tailLenPx(d, 300f)
            assertTrue("尾长恒 < 中点（def=$d len=$len）", len < 0.5f * 300f)
            assertTrue(len <= RocheTidalFx.TAIL_STOP * 300f + 1e-4f)
        }
        // 顶格=TAIL_STOP×屏距（0.46——中点前的安全停点）
        assertEquals(RocheTidalFx.TAIL_STOP * 300f, l1, 0.0001f)
        assertTrue(RocheTidalFx.TAIL_STOP < 0.5f)
    }

    // ---------------- 撞车档门（factLine 潮汐预警行——同源分 top） ----------------

    @Test
    fun `撞车档门-同源分top才出预警行`() {
        // 缺据（0）恒不出——诚实红线
        assertEquals(0, RocheTidalFx.warningSharedW(0, 0))
        assertEquals(0, RocheTidalFx.warningSharedW(0, 12))
        // top 对出（W 透传——「同源证据 W 条」全本星）
        assertEquals(12, RocheTidalFx.warningSharedW(12, 12))
        assertEquals(5, RocheTidalFx.warningSharedW(5, 5))
        // 非 top 恒不出（撞车档=同源分 top 的对——克制位）
        assertEquals(0, RocheTidalFx.warningSharedW(4, 12))
        assertEquals(0, RocheTidalFx.warningSharedW(11, 12))
        // 并列 top（同分并列=都算撞车档——不低于 top 即过门）
        assertEquals(7, RocheTidalFx.warningSharedW(7, 7))
        assertEquals(3, RocheTidalFx.warningSharedW(3, 3))
    }
}
