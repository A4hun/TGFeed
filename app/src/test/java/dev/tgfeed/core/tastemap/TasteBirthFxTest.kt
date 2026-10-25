package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R326 诞生侧宣判渲染形状参数单测（[TasteBirthFx]——TasteTerminalFxTest 8 例的诞生
 * 侧镜像；r325 C⑤ 单测锚：粒子帽/内落速率域/进度亮度单调/点燃闪光值域/凝缩坡道
 * 域/脉冲衰减窗/确定性）：
 *  - **聚合拍**：粒数 3~4 域+确定性；内落半径收缩循环（域+周期回归+同 seed 确定性）；
 *    螺旋角共源（速率冻结角冻结=完全回退的机检面）；粒子包络域；进度亮度
 *    [TasteAstroPhysics.probeProgress01] 单调对位（判据单口与 Fx 单口同门不裂——
 *    isQuasarPhase01 等价锚的诞生侧同款）；壳环呼吸域；
 *  - **点燃拍**：白闪包络（峰值→窗外零）+扩散环（单调+窗外零）——一次性窗语义；
 *  - **行星成型**：坡道宽度倍率锚+凝缩系数比现役散布更贴轨道（复用
 *    [TasteTagPlanets.dustRadiusCoef] 单口——同 seed 同偏离收缩）；
 *  - **循环联动口**：脉冲幅度 0.05~0.10 域+确定性；衰减包络（源心满/窗缘零/
 *    平方锚/越界防御）。
 */
class TasteBirthFxTest {

    // ==================== 聚合拍（螺旋内落形状锚） ====================

    @Test
    fun `聚合拍-粒数域与确定性`() {
        for (seed in intArrayOf(0, 7, 123, 99991, -48271)) {
            val n = TasteBirthFx.accretionParticles(seed)
            assertTrue("粒数 3~4（PROTOSTAR 现役 3 静态粒升维不增下限）：seed=$seed n=$n", n in 3..4)
            assertEquals("同星同粒数（确定性）", n, TasteBirthFx.accretionParticles(seed))
        }
        // 焦点件门锚：与 PROTOSTAR 现役尘埃粒同门（r≥3px——批 C 渲染器字面量换引本常量）
        assertEquals(3f, TasteBirthFx.ACCRETION_FOCUS_MIN_PX, 0f)
    }

    @Test
    fun `聚合拍-内落速率域与相位域`() {
        val base = TasteBirthFx.INFALL_RATE_CPS
        for (seed in intArrayOf(3, 88, 54321, -999)) {
            for (k in 0 until 4) {
                val rate = TasteBirthFx.accretionRateCps(seed, k)
                assertTrue("速率 0.8~1.2×基速率：seed=$seed k=$k rate=$rate", rate in base * 0.8f..base * 1.2f)
                val ph = TasteBirthFx.accretionPhase01(seed, k)
                assertTrue("相位域 0..1：seed=$seed k=$k ph=$ph", ph in 0f..1f)
                assertEquals("确定性", rate, TasteBirthFx.accretionRateCps(seed, k), 0f)
            }
        }
        // 速率基=1/16（单粒约 16s 一落——节奏锚）
        assertEquals(1f / 16f, TasteBirthFx.INFALL_RATE_CPS, 1e-6f)
    }

    @Test
    fun `聚合拍-内落半径收缩循环与域`() {
        for (seed in intArrayOf(11, 4242, -77)) {
            for (k in 0 until 3) {
                val rate = TasteBirthFx.accretionRateCps(seed, k)
                // 域 0..1（1=壳缘 0=核心）
                for (t in floatArrayOf(0f, 0.7f, 8.3f, 100f)) {
                    val r = TasteBirthFx.accretionInfallRadius01(t, seed, k)
                    assertTrue("半径域 0..1：t=$t r=$r", r in 0f..1f)
                }
                // 周期回归：一循（1/rate）后半径复位（收缩循环——wrap 即重生）
                val r0 = TasteBirthFx.accretionInfallRadius01(3f, seed, k)
                val r1 = TasteBirthFx.accretionInfallRadius01(3f + 1f / rate, seed, k)
                assertEquals("周期回归：seed=$seed k=$k", r0, r1, 1e-3f)
                // 确定性：同参两跑一致
                assertEquals(
                    TasteBirthFx.accretionInfallRadius01(5f, seed, k),
                    TasteBirthFx.accretionInfallRadius01(5f, seed, k),
                    0f,
                )
            }
        }
        // 收缩方向感：步进 0.01 周期=半径退 0.01（线性收缩段——相位不贴 wrap 界时严格成立）
        var asserted = 0
        for (seed in intArrayOf(4242, 11, 77, 999)) {
            for (k in 0 until 3) {
                val ph = TasteBirthFx.accretionPhase01(seed, k)
                if (ph > 0.9f) continue // 贴 wrap 界的粒跳过（wrap 段由周期回归例覆盖）
                val rate = TasteBirthFx.accretionRateCps(seed, k)
                val rA = TasteBirthFx.accretionInfallRadius01(0f, seed, k)
                val rB = TasteBirthFx.accretionInfallRadius01(0.01f / rate, seed, k)
                assertEquals("步进 0.01 周期=半径退 0.01：seed=$seed k=$k", 0.01f, rA - rB, 2e-3f)
                asserted++
            }
        }
        assertTrue("至少一粒在线性收缩段受检（否则本例空转）", asserted > 0)
    }

    @Test
    fun `聚合拍-螺旋角与旋向同星同向`() {
        for (seed in intArrayOf(5, 77, 999)) {
            val dir = TasteBirthFx.accretionSpinDir(seed)
            assertTrue("旋向 ±1：seed=$seed dir=$dir", dir == 1 || dir == -1)
            assertEquals("同星同旋向（盘面读法——粒间异速非异向）", dir, TasteBirthFx.accretionSpinDir(seed))
            // 同星各粒角速度同号（共源扫角×内落时钟——速率归零=角同步冻结不留半冻结态）
            for (k in 0 until 3) {
                val a0 = TasteBirthFx.accretionSpiralAngleRad(0f, seed, k)
                val a1 = TasteBirthFx.accretionSpiralAngleRad(10f, seed, k)
                assertTrue("角随时间按旋向推进：seed=$seed k=$k", (a1 - a0) * dir > 0f)
            }
            // 确定性
            assertEquals(
                TasteBirthFx.accretionSpiralAngleRad(3.3f, seed, 1),
                TasteBirthFx.accretionSpiralAngleRad(3.3f, seed, 1),
                0f,
            )
        }
    }

    @Test
    fun `聚合拍-粒子包络域（壳缘亮入核心吸收淡出）`() {
        assertEquals(0f, TasteBirthFx.accretionParticleAlpha01(0f), 1e-4f) // 核心=吸收零亮
        assertEquals(0f, TasteBirthFx.accretionParticleAlpha01(1f), 1e-4f) // 壳缘=新生零亮
        assertEquals(1f, TasteBirthFx.accretionParticleAlpha01(0.5f), 1e-4f) // 飞行中=满亮
        // 越界钳域（防御）
        assertEquals(0f, TasteBirthFx.accretionParticleAlpha01(-3f), 1e-4f)
        assertEquals(0f, TasteBirthFx.accretionParticleAlpha01(9f), 1e-4f)
    }

    @Test
    fun `聚合拍-核心进度亮度单调与域（r325原式基亮加跨度乘进度）`() {
        assertEquals(0.7f, TasteBirthFx.accretionCoreK(0f), 1e-4f) // 零进度=基亮（被探测中=有活动）
        assertEquals(0.85f, TasteBirthFx.accretionCoreK(0.5f), 1e-4f)
        assertEquals(1f, TasteBirthFx.accretionCoreK(1f), 1e-4f) // 升档线=满亮
        var prev = -1f
        for (i in 0..10) { // 单调不减（聚合进度→核亮）
            val k = TasteBirthFx.accretionCoreK(i / 10f)
            assertTrue("单调：$prev ≤ $k", k >= prev)
            prev = k
        }
        // 越界钳域（防御）+与判据单口同门（probeProgress01 喂入——口径单一出处不裂）
        assertEquals(0.7f, TasteBirthFx.accretionCoreK(TasteAstroPhysics.probeProgress01(-5)), 1e-4f)
        assertEquals(1f, TasteBirthFx.accretionCoreK(TasteAstroPhysics.probeProgress01(9999)), 1e-4f)
    }

    @Test
    fun `聚合拍-壳环呼吸域（缓呼吸非闪灭）`() {
        for (t in floatArrayOf(0f, 1.7f, 13.3f, 100f, 999f)) {
            val b = TasteBirthFx.accretionShellBreath(t, 42)
            assertTrue("呼吸 0.85~1.0（非闪灭）：t=$t b=$b", b in 0.85f..1.0f)
        }
        // 确定性
        assertEquals(
            TasteBirthFx.accretionShellBreath(9.9f, 17),
            TasteBirthFx.accretionShellBreath(9.9f, 17),
            0f,
        )
    }

    // ==================== 点燃拍（一次性窗 300ms） ====================

    @Test
    fun `点燃拍-白闪包络峰值衰减与窗外零`() {
        assertEquals(300L, TasteBirthFx.IGNITION_MS) // REVEAL_FX 同量级窗锚
        assertEquals(0.8f, TasteBirthFx.IGNITION_FLASH_BOOST, 1e-4f) // 峰值增亮锚
        // t=0 峰值→线性衰减→窗外恒 0（一次性——窗后交回 REVEAL_FX）
        assertEquals(TasteBirthFx.IGNITION_FLASH_BOOST, TasteBirthFx.ignitionFlash01(0f), 1e-4f)
        assertEquals(0.4f, TasteBirthFx.ignitionFlash01(0.15f), 1e-3f) // 半窗=半峰
        assertEquals(0f, TasteBirthFx.ignitionFlash01(0.3f), 1e-4f) // 窗末=零
        assertEquals(0f, TasteBirthFx.ignitionFlash01(5f), 1e-4f) // 窗外恒 0
        assertEquals(0f, TasteBirthFx.ignitionFlash01(-1f), 1e-4f) // 未点燃防御=零
    }

    @Test
    fun `点燃拍-扩散环单调衰减与窗外零`() {
        // 半径进度：0→1 窗内单调；窗外恒 0（零绘制早退）
        val r0 = TasteBirthFx.ignitionRingR01(0f)
        val rMid = TasteBirthFx.ignitionRingR01(0.15f)
        val rEnd = TasteBirthFx.ignitionRingR01(0.29f)
        assertTrue("环半径窗内单调扩：$r0 < $rMid < $rEnd", r0 < rMid && rMid < rEnd)
        assertEquals(1f, rEnd, 0.1f)
        assertEquals(0f, TasteBirthFx.ignitionRingR01(0.3f), 1e-4f)
        assertEquals(0f, TasteBirthFx.ignitionRingR01(9f), 1e-4f)
        // 透明度：峰值随扩散衰减到窗末零；窗外恒 0
        assertEquals(TasteBirthFx.IGNITION_RING_ALPHA, TasteBirthFx.ignitionRingAlpha01(0f), 1e-4f)
        assertTrue(TasteBirthFx.ignitionRingAlpha01(0.15f) < TasteBirthFx.IGNITION_RING_ALPHA)
        assertEquals(0f, TasteBirthFx.ignitionRingAlpha01(0.3f), 1e-4f)
        assertEquals(0f, TasteBirthFx.ignitionRingAlpha01(9f), 1e-4f)
    }

    // ==================== 行星成型贴脸拍（凝缩参数锚） ====================

    @Test
    fun `成型拍-坡道放缓倍率与凝缩域`() {
        // 坡道窗 ×1.8（放缓——「正在聚成」读法；归一=1.0 现状坡道=回退位）
        assertEquals(1.8f, TasteBirthFx.FORMING_RAMP_MULT, 1e-4f)
        assertEquals(0.8f * 1.8f, TasteBirthFx.formingRampWidth(TasteTagPlanets.APPEAR_RAMP), 1e-4f)
        // 凝缩系数：复用 TasteTagPlanets.dustRadiusCoef 单口——同 seed 同偏离×(1−收缩比)
        assertEquals(0.55f, TasteBirthFx.FORMING_DUST_CONTRACT, 1e-4f) // 收缩比锚（归零=现状散布=回退位）
        for (seed in intArrayOf(1, 42, 777, -321)) {
            for (idx in 0 until 6) {
                val orig = TasteTagPlanets.dustRadiusCoef(seed, idx)
                val formed = TasteBirthFx.formingDustRadiusCoef(seed, idx)
                // 凝缩=偏离量收缩（比现役散布更贴行星轨道）
                assertTrue(
                    "凝缩更贴轨道：seed=$seed idx=$idx orig=$orig formed=$formed",
                    kotlin.math.abs(formed - 1f) <= kotlin.math.abs(orig - 1f) + 1e-6f,
                )
                // 域：收缩后散布 0.94~1.10 的子域（不出原域）
                assertTrue("凝缩域在原散布域内：formed=$formed", formed in 0.94f..1.10f)
                assertEquals("同 seed 确定性", formed, TasteBirthFx.formingDustRadiusCoef(seed, idx), 0f)
            }
        }
    }

    // ==================== 循环联动口（脉冲+衰减窗——批 5 空挂零行为） ====================

    @Test
    fun `循环脉冲-幅度域与确定性`() {
        // r315 K 原值族：0.05~0.10 alpha（基值+幅度常量锚）
        assertEquals(0.05f, TasteBirthFx.CYCLE_PULSE_MIN, 1e-4f)
        assertEquals(0.05f, TasteBirthFx.CYCLE_PULSE_SPAN, 1e-4f)
        for (t in floatArrayOf(0f, 2.2f, 13.3f, 100f)) {
            for (seed in intArrayOf(1, 88, 54321)) {
                val a = TasteBirthFx.cyclePulseAlpha(t, seed)
                assertTrue("脉冲 0.05~0.10（非闪灭慢呼吸）：t=$t seed=$seed a=$a", a in 0.05f..0.10f)
            }
        }
        // 确定性（同参两跑一致）
        assertEquals(
            TasteBirthFx.cyclePulseAlpha(7.7f, 42),
            TasteBirthFx.cyclePulseAlpha(7.7f, 42),
            0f,
        )
    }

    @Test
    fun `循环脉冲-衰减窗包络（平方衰减两用口）`() {
        // 源心满亮/窗缘零/平方锚（0.5 处=0.75——近处强远处快衰）
        assertEquals(1f, TasteBirthFx.cyclePulseDecay01(0f), 1e-4f)
        assertEquals(0.75f, TasteBirthFx.cyclePulseDecay01(0.5f), 1e-4f)
        assertEquals(0f, TasteBirthFx.cyclePulseDecay01(1f), 1e-4f)
        // 窗外零成本早退+负值防御（钳域）
        assertEquals(0f, TasteBirthFx.cyclePulseDecay01(2.5f), 1e-4f)
        assertEquals(1f, TasteBirthFx.cyclePulseDecay01(-0.5f), 1e-4f)
        // 窗内单调不增
        var prev = 1f
        for (i in 1..10) {
            val d = TasteBirthFx.cyclePulseDecay01(i / 10f)
            assertTrue("衰减单调：$prev ≥ $d", d <= prev)
            prev = d
        }
    }
}
