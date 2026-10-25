package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R303 四力数据化核心单测（design-multiscale-emergent §3 终审稿逐式锚定——
 * 公式、常量、安全不等式五条、弱核力三作用面、暗能量旋钮默认关）。
 */
class TasteEmergentForcesTest {

    private val F = TasteEmergentForces

    // ==================== 引力：相关强度合成（§3.1） ====================

    @Test
    fun `相关强度-公式逐式锚定`() {
        // 全零证据：j=0、d_mds=p95 → 0.3·0 + 0.2·0 = 0
        assertEquals(0f, F.strengthOf(0f, 100f, 0f, 1f, 1f), 1e-4f)
        // 满互链+满 Jaccard+零距离 → 0.5+1.0+0.2 超 1 → clamp01=1
        assertEquals(1f, F.strengthOf(100f, 100f, 0.9f, 0f, 1f), 1e-4f)
        // 纯 Jaccard 0.30=满档锚 → 0.3
        assertEquals(0.3f, F.strengthOf(0f, 0f, 0.30f, 1f, 1f), 1e-3f)
        // 纯 MDS 零距离 → 0.2
        assertEquals(0.2f, F.strengthOf(0f, 0f, 0f, 0f, 1f), 1e-4f)
        // 互链源缺席（p95=0）→ 该项计 0 不除零（诚实降档）
        assertEquals(0f, F.strengthOf(5f, 0f, 0f, 1f, 1f), 1e-4f)
    }

    @Test
    fun `相关强度-Jaccard 单调`() {
        var prev = -1f
        var j = 0f
        while (j <= 0.9f) {
            val s = F.strengthOf(0f, 0f, j, 0.5f, 1f)
            assertTrue("j=$j 处 s=$s 应 ≥ $prev", s >= prev)
            prev = s
            j += 0.05f
        }
    }

    @Test
    fun `理想距-安全不等式3全档成立`() {
        // s 全档 [0,1]：L > d_min 恒真（构造性下界钳复验）
        var s = 0f
        while (s <= 1f) {
            val L = F.idealDist(10f, 20f, s)
            assertTrue("s=$s L=$L 应过硬核下界", L > F.hardMinDist(10f, 20f))
            s += 0.02f
        }
        // 量级锚（§3.1：最强对 ~450=ρa+ρb+40+400；最弱对 ~1690）
        assertEquals(10f + 20f + 40f + 1600f * 0.25f, F.idealDist(10f, 20f, 1f), 1e-3f)
        assertEquals(10f + 20f + 40f + 1600f, F.idealDist(10f, 20f, 0f), 1e-3f)
    }

    @Test
    fun `基底向心-量级标定锚`() {
        // §3.1：r=4000 处 ≈1.0 单位/步² @ M_tot≈210
        val a = F.baseGravityAccel(4000f, 210f)
        assertTrue("r=4000 基底向心=$a 应 ≈1.0", a > 0.9f && a < 1.1f)
        // r→0 退化防御：零除防护
        assertEquals(0f, F.baseGravityAccel(0f, 210f), 0f)
    }

    // ==================== 斥力：硬核（§3.2） ====================

    @Test
    fun `硬核-d_min口径与力方向`() {
        assertEquals(10f + 20f + 40f, F.hardMinDist(10f, 20f), 1e-4f)
        // d≥d_min 零力；d<d_min 线性推开
        assertEquals(0f, F.hardCoreAccel(70f, 70f, 20f), 0f)
        assertEquals(20f * 10f, F.hardCoreAccel(60f, 70f, 20f), 1e-3f)
    }

    // ==================== 弱核力（§3.4） ====================

    @Test
    fun `衰变-半衰期与下限`() {
        assertEquals(1f, F.decayLambda(0f), 1e-4f)
        assertEquals(0.5f, F.decayLambda(45f), 1e-3f) // τ=45 天半衰
        assertEquals(0.25f, F.decayLambda(90f), 1e-3f)
        assertEquals(F.LAMBDA_MIN, F.decayLambda(10000f), 1e-4f) // 下限 0.05
    }

    @Test
    fun `晕漂-活星不动死星迁晕`() {
        assertEquals(1000f, F.haloShiftedRadius(1000f, 1f), 1e-3f) // 活星原位
        // 死星 λ=0.05：r'=1000+0.95·(5400−1000)=5180 → 墓地轨道带
        assertEquals(5180f, F.haloShiftedRadius(1000f, 0.05f), 1f)
        // 已在晕外不继续外推（r>r_halo 时项反号=回拉——墓地轨道是吸引子）
        val r = F.haloShiftedRadius(5800f, 0.05f)
        assertTrue("晕外回拉：$r 应 < 5800", r < 5800f)
    }

    // ==================== 暗能量旋钮（§6.4 机制三） ====================

    @Test
    fun `暗能量-默认关与平方律`() {
        assertEquals(0f, F.darkEnergyAccel(4000f, F.K_DE_DEFAULT), 0f)
        assertEquals(0f, F.K_DE_DEFAULT, 0f) // 拍板项⑨：默认关
        // 平方律：r=6000 → kDE；r=3000 → 0.25·kDE
        assertEquals(2f, F.darkEnergyAccel(6000f, 2f), 1e-3f)
        assertEquals(0.5f, F.darkEnergyAccel(3000f, 2f), 1e-3f)
    }

    // ==================== 安全不等式（§3.6 参数合法域成文） ====================

    @Test
    fun `安全不等式-默认参数全绿`() {
        assertTrue(F.safetyReport(TasteEmergentForces.Params()).isEmpty())
    }

    @Test
    fun `安全不等式-违约逐条可检`() {
        // ①硬核 <20×kG
        assertTrue(F.safetyReport(TasteEmergentForces.Params(kHard = 1f)).any { it.contains("①") })
        // ②强核 <10×kG
        assertTrue(F.safetyReport(TasteEmergentForces.Params(kS = 1f)).any { it.contains("②") })
        // ④基底向心不足（防膨胀散架）
        assertTrue(F.safetyReport(TasteEmergentForces.Params(gLay = 1f)).any { it.contains("④") })
        // 暗能量负值非法
        assertTrue(F.safetyReport(TasteEmergentForces.Params(kDE = -1f)).isNotEmpty())
    }
}
