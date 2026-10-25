package dev.tgfeed.core.tastemap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * R317b 终态宣判渲染形状参数单测（[TasteTerminalFx]——Canvas/GL 双端同式的
 * 单源件；r315 单测锚总表「H 行 accretion+QuasarFx：归一+缺据零值/静默档
 * 现状回归锚」的 wiring 兑现+三新笔刷形状锚）：
 *  - **CompactStarFx**：遗迹丝缕弧段数/半径系数/扫角值域+确定性；相位冻结缓漂
 *    （t×0.4°/s——遗迹=死透的物质壳 vs 超新星爆发扩散）；
 *  - **WhiteholeFx**：喷流轴向域+确定性+长度单调+呼吸域；
 *  - **黑洞进食档**：[TasteTerminalFx.accretionDiskK] 归一单调+边界；盛宴门
 *    [TasteTerminalFx.isQuasarPhase01] 与判据族 [TasteAstroPhysics.isQuasarPhase]
 *    recent7 域**等价锚**（口径单一出处不裂——渲染侧与判据侧同门）；
 *  - **白洞互动账口径**（r317b 挂账④案 A 单一出处）：在场=透传/缺席=-1 缺据。
 */
class TasteTerminalFxTest {

    // ==================== CompactStarFx（遗迹环形状锚） ====================

    @Test
    fun `遗迹丝缕-段数与参数值域`() {
        for (seed in intArrayOf(0, 7, 123, 99991, -48271)) {
            val fil = TasteTerminalFx.remnantFilaments(seed)
            assertTrue("段数 3~4：seed=$seed fil=$fil", fil in 3..4)
            for (k in 0 until fil) {
                val rr = TasteTerminalFx.remnantArcRCoef(seed, k)
                assertTrue("弧半径系数 0.80~1.12：seed=$seed k=$k rr=$rr", rr in 0.80f..1.12f)
                val sweep = TasteTerminalFx.remnantArcSweep(seed, k)
                assertTrue("扫角 38°~90°：seed=$seed k=$k sweep=$sweep", sweep in 38f..90f)
            }
        }
    }

    @Test
    fun `遗迹丝缕-确定性同参两跑一致`() {
        assertEquals(
            TasteTerminalFx.remnantArcRCoef(4242, 1),
            TasteTerminalFx.remnantArcRCoef(4242, 1),
            0f,
        )
        assertEquals(
            TasteTerminalFx.remnantArcSweep(4242, 2),
            TasteTerminalFx.remnantArcSweep(4242, 2),
            0f,
        )
        // 同星同形：seed 相同弧参相同（不同 k 相位错开——丝缕结构）
        assertTrue(
            TasteTerminalFx.remnantArcRCoef(4242, 0) != TasteTerminalFx.remnantArcRCoef(4242, 1) ||
                TasteTerminalFx.remnantArcSweep(4242, 0) != TasteTerminalFx.remnantArcSweep(4242, 1),
        )
    }

    @Test
    fun `遗迹丝缕-相位冻结缓漂`() {
        // 遗迹=死透的物质壳：t×0.4°/s 极缓整体缓旋（超新星爆发环 ±3°/s 的冻结版）
        val seed = 777
        for (k in 0 until TasteTerminalFx.remnantFilaments(seed)) {
            val b0 = TasteTerminalFx.remnantArcBaseDeg(seed, k, 0f)
            val b10 = TasteTerminalFx.remnantArcBaseDeg(seed, k, 10f)
            assertEquals("10s 只漂 4°（0.4°/s）：k=$k", 4f, b10 - b0, 1e-3f)
        }
        // 哈希基相：同 seed 不同 k 基相不同（丝缕的散布感）
        assertTrue(
            TasteTerminalFx.remnantArcBaseDeg(777, 0, 0f) != TasteTerminalFx.remnantArcBaseDeg(777, 1, 0f),
        )
    }

    // ==================== WhiteholeFx（喷流形状锚） ====================

    @Test
    fun `白洞喷流-轴向域与确定性`() {
        for (seed in intArrayOf(3, 88, 54321, -999)) {
            val ang = TasteTerminalFx.whiteholeJetAngleDeg(seed)
            assertTrue("轴向 0~180（双向对称域）：seed=$seed ang=$ang", ang in 0f..180f)
            assertEquals("同星同轴（确定性）", ang, TasteTerminalFx.whiteholeJetAngleDeg(seed), 0f)
        }
        // 喷流呼吸域 0.72~1.0（缓呼吸非闪灭——恒外喷语义）
        for (t in floatArrayOf(0f, 1.7f, 13.3f, 100f)) {
            val breath = TasteTerminalFx.whiteholeJetBreath(t, 42)
            assertTrue("呼吸 0.72~1.0：t=$t breath=$breath", breath in 0.72f..1.0f)
        }
    }

    @Test
    fun `白洞喷流-长度随半径单调`() {
        val l1 = TasteTerminalFx.whiteholeJetLen(2f)
        val l2 = TasteTerminalFx.whiteholeJetLen(5f)
        val l3 = TasteTerminalFx.whiteholeJetLen(12f)
        assertTrue("大星喷流更长（单调）：$l1 < $l2 < $l3", l1 < l2 && l2 < l3)
        assertEquals("r×4.6+26 公式锚", 49f, TasteTerminalFx.whiteholeJetLen(5f), 1e-3f)
    }

    // ==================== 黑洞进食档（accretion01→盘亮度/类星体相门） ====================

    @Test
    fun `进食档-盘亮度系数归一单调与边界`() {
        // 静默黑洞（acc01=0）=0.55 暗盘；盛宴（acc01=1）=1.0 炽盘
        assertEquals(0.55f, TasteTerminalFx.accretionDiskK(0f), 1e-4f)
        assertEquals(1.00f, TasteTerminalFx.accretionDiskK(1f), 1e-4f)
        // 单调不减（进食速率→亮度）
        var prev = -1f
        for (i in 0..10) {
            val k = TasteTerminalFx.accretionDiskK(i / 10f)
            assertTrue("单调：$prev ≤ $k", k >= prev)
            prev = k
        }
        // 越界钳域（防御：非法 acc01 不放大）
        assertEquals(0.55f, TasteTerminalFx.accretionDiskK(-5f), 1e-4f)
        assertEquals(1.00f, TasteTerminalFx.accretionDiskK(9f), 1e-4f)
    }

    @Test
    fun `进食档-类星体相门与判据族等价`() {
        // 与 [TasteAstroPhysics.isQuasarPhase]（recent7 域）逐位等价——口径单一
        // 出处不裂：渲染侧盛宴视觉与判据侧「正在坍缩」分支同门同判
        for (recent7 in 0..40) {
            val acc01 = TasteAstroPhysics.accretion01Of(recent7)
            assertEquals(
                "盛宴门等价：recent7=$recent7",
                TasteAstroPhysics.isQuasarPhase(recent7),
                TasteTerminalFx.isQuasarPhase01(acc01),
            )
        }
        // 门值锚：8/14 ≈ 0.571 过门、7/14 不过（ACCRETION_FEAST_RECENT7=8）
        assertTrue(TasteTerminalFx.isQuasarPhase01(TasteAstroPhysics.accretion01Of(8)))
        assertTrue(!TasteTerminalFx.isQuasarPhase01(TasteAstroPhysics.accretion01Of(7)))
        // 缺据（-1=无金矿证据）=0 静默档——不冒充类星体相（诚实红线）
        assertEquals(0f, TasteAstroPhysics.accretion01Of(-1), 0f)
        assertTrue(!TasteTerminalFx.isQuasarPhase01(TasteAstroPhysics.accretion01Of(-1)))
    }

    // ==================== R350 纤维点串（球面/环面——壳/遗迹一类一件） ====================

    @Test
    fun `纤维点串-密度两档与侵蚀值域`() {
        // 密度两档：r341 §3.3-A「8~12 点/缕」——分界=LOWMESH_MAX_DP（政策件单口；
        // R355 dp 化缺省 mdpi 档 4dp——4px 前疏档 8 点/4px 起密档 12 点；
        // 渲染器帧字段透传口径由 GlFrameBuilderTest 消费锚覆盖）
        assertEquals(8, TasteTerminalFx.remnantFiberPoints(2f))
        assertEquals(8, TasteTerminalFx.remnantFiberPoints(3.99f))
        assertEquals(12, TasteTerminalFx.remnantFiberPoints(4f))
        assertEquals(12, TasteTerminalFx.remnantFiberPoints(40f))
        // 阈值参透传（400dpi 档=10px 分界——同 dp 半径同密度档=跨密度一致性半体）
        assertEquals(8, TasteTerminalFx.remnantFiberPoints(1.5f * 2.5f, 4f * 2.5f))
        assertEquals(12, TasteTerminalFx.remnantFiberPoints(5f * 2.5f, 4f * 2.5f))
        // 侵蚀值域 [0.15,1]（点串明暗起伏不断链——断续观感非增益）
        for (seed in intArrayOf(0, 71, 4242, 99991, -48271)) {
            for (k in 0 until 4) {
                for (j in 0..11) {
                    val u = j / 11f
                    val e = TasteTerminalFx.remnantFiberErode01(seed, k, u)
                    assertTrue("侵蚀域 [0.15,1]：seed=$seed k=$k u=$u e=$e", e in 0.15f..1f)
                }
            }
        }
    }

    @Test
    fun `纤维点串-侵蚀确定性与沿线起伏`() {
        // 同星同形（确定性铁律——零 Random 零系统钟）
        assertEquals(
            TasteTerminalFx.remnantFiberErode01(71, 1, 0.3f),
            TasteTerminalFx.remnantFiberErode01(71, 1, 0.3f),
            0f,
        )
        // 沿线起伏：同缕 u 域上侵蚀值有明暗变化（丝缕断续的形状半体）
        var spread = false
        val first = TasteTerminalFx.remnantFiberErode01(71, 1, 0f)
        for (j in 1..11) {
            if (abs(TasteTerminalFx.remnantFiberErode01(71, 1, j / 11f) - first) > 0.08f) spread = true
        }
        assertTrue("沿 u 有明暗起伏（断续观感）", spread)
    }

    @Test
    fun `球面纤维点-单位模长与确定性漂移`() {
        val out = FloatArray(3)
        for (seed in intArrayOf(71, 777, -31337)) {
            for (k in 0 until TasteTerminalFx.remnantFilaments(seed)) {
                for (j in 0..11) {
                    TasteTerminalFx.shellFiberPoint(seed, k, j / 11f, 0f, out, 0)
                    val m = sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2])
                    assertTrue("单位球面点（|p|=1±0.001）：seed=$seed k=$k j=$j m=$m", abs(m - 1f) < 1e-3f)
                }
            }
        }
        // 确定性同参两跑一致
        val a = FloatArray(3)
        TasteTerminalFx.shellFiberPoint(4242, 2, 0.5f, 3.3f, out, 0)
        TasteTerminalFx.shellFiberPoint(4242, 2, 0.5f, 3.3f, a, 0)
        assertArrayEquals(a, out, 1e-6f)
        // 冻结缓漂随 t：u 采样位=壳面真视差位的连续平移（非屏面恒定椭圆）
        TasteTerminalFx.shellFiberPoint(777, 0, 0.5f, 0f, out, 0)
        TasteTerminalFx.shellFiberPoint(777, 0, 0.5f, 10f, a, 0)
        assertTrue("t 平移后采样位移动（基相漂移语义）", a[0] != out[0] || a[1] != out[1] || a[2] != out[2])
    }

    @Test
    fun `环面纤维点-径向散布与离面起伏`() {
        val out = FloatArray(3)
        var sawVertical = false
        var sawRadiusSpread = false
        for (seed in intArrayOf(71, 777, 4242)) {
            // 面内半径散布=**缕间**（每缕 rr=majorR×remnantArcRCoef 恒定、缕间
            // 0.80~1.12 系数散开=管厚读法的径向半体）
            var rMin = Float.MAX_VALUE
            var rMax = -Float.MAX_VALUE
            for (k in 0 until TasteTerminalFx.remnantFilaments(seed)) {
                for (j in 0..11) {
                    TasteTerminalFx.torusFiberPoint(seed, k, j / 11f, 0f, 3.3f, out, 0)
                    val rIn = sqrt(out[0] * out[0] + out[2] * out[2])
                    rMin = min(rMin, rIn)
                    rMax = max(rMax, rIn)
                    // 离面波幅域：|y| ≤ majorR×WOBBLE
                    assertTrue(
                        "离面波幅帽：seed=$seed k=$k y=${out[1]}",
                        abs(out[1]) <= 3.3f * TasteTerminalFx.REMNANT_FIBER_WOBBLE + 1e-4f,
                    )
                    if (abs(out[1]) > 0.01f) sawVertical = true
                }
            }
            // 缕间面内半径散布（[0.80,1.12]×majorR 族）
            if (rMax - rMin > 0.05f) sawRadiusSpread = true
        }
        assertTrue("离面起伏在场（真 3D 管——侧视不压线）", sawVertical)
        assertTrue("缕间径向散布在场（管厚读法）", sawRadiusSpread)
    }

    @Test
    fun `遗迹环面方位与进动角-域与同族漂移率`() {
        for (seed in intArrayOf(0, 7, 54321, -999)) {
            val tx = TasteTerminalFx.remnantRingTiltX(seed)
            val tz = TasteTerminalFx.remnantRingTiltZ(seed)
            assertTrue("主倾域 [0,π]：seed=$seed tx=$tx", tx in 0f..3.1415927f)
            assertTrue("次倾域 ±0.3：seed=$seed tz=$tz", tz in -0.3f..0.3f)
            // 确定性
            assertEquals(tx, TasteTerminalFx.remnantRingTiltX(seed), 0f)
            assertEquals(tz, TasteTerminalFx.remnantRingTiltZ(seed), 0f)
            // 进动角漂移率=与壳丝缕基相同族（REMNANT_DRIFT_DEG_S 单源——r341 §3.3-B）
            val p0 = TasteTerminalFx.remnantRingPrecessDeg(seed, 0f)
            val p10 = TasteTerminalFx.remnantRingPrecessDeg(seed, 10f)
            assertEquals(
                "10s 只漂 4°（0.4°/s 同族单源）：seed=$seed",
                10f * TasteTerminalFx.REMNANT_DRIFT_DEG_S,
                p10 - p0,
                1e-3f,
            )
        }
        // 基相冻结缓漂测试与 REMNANT_DRIFT_DEG_S 单源恒等（旧锚域 4°/10s 随迁）
        assertEquals(0.4f, TasteTerminalFx.REMNANT_DRIFT_DEG_S, 0f)
    }

    // ==================== 白洞互动账口径（挂账④案 A 单一出处） ====================

    @Test
    fun `白洞口径-在场透传缺席负一`() {
        // 在场=原计数透传（含真零：账面在读到 0=真零回馈——判据族语义）
        assertEquals(0, TasteTerminalFx.whiteholeAccountOf(0, present = true))
        assertEquals(7, TasteTerminalFx.whiteholeAccountOf(7, present = true))
        // 缺席=-1 缺据哨兵（judgeWhiteHoles 见 -1 即不判——绝不冒充零回馈）
        assertEquals(-1, TasteTerminalFx.whiteholeAccountOf(0, present = false))
        assertEquals(-1, TasteTerminalFx.whiteholeAccountOf(9, present = false))
        // 口径消费面：-1 哨兵在判据族侧=缺据不判（core 既有锚复证）
        val wh = TasteAstroPhysics.judgeWhiteHoles(
            listOf(
                TasteAstroPhysics.WhiteholeEvidence(
                    chatId = 1L, watchSec = 5L * 3600L, watchedCount = 40, goldTier = -1,
                    likeCount = TasteTerminalFx.whiteholeAccountOf(3, present = false), // 缺据
                    favCount = 0, danmakuCount = 0, entityKind = null,
                ),
            ),
        )
        assertTrue("互动账缺据（-1）=白洞不判（案 A 口径闭环）", wh.isEmpty())
    }
}
