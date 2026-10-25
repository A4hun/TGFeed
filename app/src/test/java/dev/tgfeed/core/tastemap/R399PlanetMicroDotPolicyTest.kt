package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R399 行星缩放消失根治·count 轴微点政策纯函数锚（判词引 r399_notes §2——
 * 「优雅降级而非消失」：行星「未浮现」态=并入母星光晕的微点，永不整吞）。
 *
 * 锚四族：
 *  - **域内镜像族**：ratio<APPEAR 时 microDotAlpha01 ≡ 1−appearAlpha（球淡出
 *    的镜像淡入=R349 blend 带双态叠加同族语言——count 轴交接零跳变的公式本体）；
 *  - **远档 top-K 帽族**：ratio≥APPEAR 时 index<LOD_MID_PLANETS 恒 1（远档微点
 *    =中距 top 集同拍=点预算纪律）；深位一个坡道窗退场到 0；
 *  - **门边界连续族**：ratio→APPEAR±ε 左右极限相等（域内深位微点 1 ↔ 远档深位
 *    退场窗起点 1——旧系统级吞门退役后的交接无缝）；
 *  - **微点地板族**：[TasteLodPolicy.microDotPx] 裸像素下限 2.2 兜底+1.2dp 线
 *    （mdpi 2.2 / xxhdpi 3.3 / 边界密度档）。
 */
class R399PlanetMicroDotPolicyTest {

    private val ramp = TasteTagPlanets.APPEAR_RAMP
    private val appear = TasteTagPlanets.APPEAR_RATIO
    private val full = TasteTagPlanets.FULL_RATIO

    // ==================== ① 域内镜像族（公式本体=1−appearAlpha） ====================

    @Test
    fun `域内镜像-全指数全 ratio 段与 appearAlpha 逐位互补`() {
        // total=15 扫 index×ratio 网格：域内（FULL..APPEAR）每处 == 1−appearAlpha
        for (ratio in listOf(full, 3.6f, 4.0f, 4.5f, 5.0f, 5.5f, 5.9f)) {
            for (i in 0 until 15) {
                val expect = 1f - TasteTagPlanets.appearAlpha(ratio, i, 15, ramp)
                assertEquals(
                    "ratio=$ratio index=$i 应为 appearAlpha 镜像",
                    expect, TasteTagPlanets.microDotAlpha01(ratio, i, 15, ramp), 0f,
                )
            }
        }
    }

    @Test
    fun `近距满档-全行星微点零让位球身`() {
        // ratio=2.0（深进 FULL 档）：15 颗全 a=1（微点 0=近距观感零变化锚）
        for (i in 0 until 15) {
            assertEquals("近距 index=$i 微点应零", 0f, TasteTagPlanets.microDotAlpha01(2.0f, i, 15, ramp), 0f)
        }
    }

    @Test
    fun `成型坡道参-放缓窗同拍镜像`() {
        // ramp=1.44（formingRampWidth 档）：镜像式不变（与 appearAlpha 同参同拍）
        val ratio = 5.0f
        for (i in 0 until 10) {
            val expect = 1f - TasteTagPlanets.appearAlpha(ratio, i, 10, 1.44f)
            assertEquals(expect, TasteTagPlanets.microDotAlpha01(ratio, i, 10, 1.44f), 0f)
        }
    }

    // ==================== ② 远档 top-K 帽族（点预算纪律） ====================

    @Test
    fun `远档topK-浅位满额深位窗外全零`() {
        // ratio=7.0（APPEAR+ramp 之外）：index<6 恒 1；index≥6 恒 0（帽外不入场）
        for (i in 0 until 15) {
            val expect = if (i < TasteTagPlanets.LOD_MID_PLANETS) 1f else 0f
            assertEquals("远档 index=$i", expect, TasteTagPlanets.microDotAlpha01(7.0f, i, 15, ramp), 0f)
        }
    }

    @Test
    fun `远档深位退场窗-一个坡道内 1 到 0`() {
        // ratio=6.4（窗中点 (6.8−6.4)/0.8=0.5）；深位共享同一退场坡（帽的连续收口）
        assertEquals(0.5f, TasteTagPlanets.microDotAlpha01(6.4f, 6, 15, ramp), 1e-4f)
        assertEquals(0.5f, TasteTagPlanets.microDotAlpha01(6.4f, 14, 15, ramp), 1e-4f)
        // 窗终点=0
        assertEquals(0f, TasteTagPlanets.microDotAlpha01(6.8f, 6, 15, ramp), 1e-4f)
        // 成型放缓窗=同一式拉宽（1.44 档窗终点 7.44）
        assertEquals(0.5f, TasteTagPlanets.microDotAlpha01(6.72f, 8, 15, 1.44f), 1e-4f)
        assertEquals(0f, TasteTagPlanets.microDotAlpha01(7.44f, 8, 15, 1.44f), 1e-4f)
    }

    // ==================== ③ 门边界连续族（吞门退役交接无缝） ====================

    @Test
    fun `门边界连续-左右极限逐位相等`() {
        // 域内 6.0−ε（深位 a=0 → 镜像 1）↔ 域外 6.0+ε（深位退场窗起点 1）；
        // 浅位（index<K）域内 1−a(→0)=1 ↔ 域外恒 1——两类行星交接都零跳变
        val eps = 1e-4f
        for (i in 0 until 15) {
            val left = TasteTagPlanets.microDotAlpha01(appear - eps, i, 15, ramp)
            val right = TasteTagPlanets.microDotAlpha01(appear + eps, i, 15, ramp)
            assertEquals("门边界 index=$i 左右极限应等", left, right, 1e-3f)
        }
    }

    @Test
    fun `空系统与退化参-零防御档`() {
        assertEquals(0f, TasteTagPlanets.microDotAlpha01(5.0f, 0, 0, ramp), 0f)
        // ramp→0 的阶跃极限（正常调用域不达——防御不 NaN；深位=即退场 0）
        assertEquals(0f, TasteTagPlanets.microDotAlpha01(7.0f, 6, 15, 0f), 0f)
        assertEquals(1f, TasteTagPlanets.microDotAlpha01(7.0f, 0, 15, 0f), 0f)
    }

    // ==================== ④ 微点地板族（TasteLodPolicy.microDotPx） ====================

    @Test
    fun `微点地板-mdpi 裸像素下限兜底`() {
        // density=1：1.2dp 线=1.2px < 2.2px 下限 → 下限赢（低密度屏不塌缩成不可见）
        assertEquals(2.2f, TasteLodPolicy.microDotPx(1f), 0f)
        // 域外低密度钳 0.75：0.9px < 2.2 → 仍下限
        assertEquals(2.2f, TasteLodPolicy.microDotPx(0.5f), 0f)
    }

    @Test
    fun `微点地板-高密度 dp 线接管`() {
        // density=2.75（480dpi 档）：1.2dp=3.3px > 2.2 → dp 线赢
        assertEquals(3.3f, TasteLodPolicy.microDotPx(2.75f), 1e-3f)
        // density 钳上界 4.0：4.8px
        assertEquals(4.8f, TasteLodPolicy.microDotPx(9f), 1e-3f)
        // 交界密度 2.2/1.2≈1.8333：两腿相等
        assertEquals(2.2f, TasteLodPolicy.microDotPx(2.2f / 1.2f), 1e-3f)
    }
}
