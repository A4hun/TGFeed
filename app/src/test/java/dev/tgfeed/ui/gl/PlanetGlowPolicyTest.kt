package dev.tgfeed.ui.gl

import dev.tgfeed.core.tastemap.TasteTagPlanets
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R309 行星微光可发现性政策锚（用户实锤「行星全黑融进背景看不见」的防回归机检）：
 *  - 口径件常数区间锚：夜面/缘光下限必须**高于旧死黑区**且**远低于 bloom 阈 0.70**
 *    （可发现但不许亮过恒星——恒星吃 bloom，行星全程不触阈）；
 *  - GL shader 字符串形状锚（tagItemsJoinSql 先例）：常数经编译期内插进 PLANET_FS/
 *    ROCK_FS——口径件与 shader 脱节即红（防「改了常数没进 shader」的静默漂移）；
 *  - Canvas 侧同族：位图烘焙读同一组 TasteTagPlanets 常数（代码审+真机截图双侧验）。
 */
class PlanetGlowPolicyTest {

    @Test
    fun `区间-夜面下限高于旧死黑区且远低于bloom阈`() {
        // 旧 GL 值 0.06=死黑实锤区；新下限必须显著高于它
        assertTrue(TasteTagPlanets.PLANET_NIGHT_FLOOR > 0.10f)
        // bloom 阈 0.70（GlPostFx.bloomThreshold）——夜面链峰值（FLOOR×TINT_B≈0.18
        // +缘光 RIM_FLOOR）必须留足安全边际，行星永不入 bloom
        assertTrue(TasteTagPlanets.PLANET_NIGHT_FLOOR * TasteTagPlanets.PLANET_NIGHT_TINT_B < 0.30f)
        assertTrue(TasteTagPlanets.PLANET_RIM_FLOOR < 0.55f) // 受光侧缘光 0.55 原值不动
        assertTrue(TasteTagPlanets.PLANET_NIGHT_FLOOR + TasteTagPlanets.PLANET_RIM_FLOOR < 0.55f)
    }

    @Test
    fun `区间-冷蓝偏移与岩石下限`() {
        // 星光色温=蓝移：B 通道增益必须大于 R 通道
        assertTrue(TasteTagPlanets.PLANET_NIGHT_TINT_B > TasteTagPlanets.PLANET_NIGHT_TINT_R)
        assertTrue(TasteTagPlanets.PLANET_NIGHT_TINT_R in 0.5f..1.0f)
        // 岩石（小行星/尘埃岩）夜面下限：高于旧 0.20、低于 bloom 阈
        assertTrue(TasteTagPlanets.ROCK_NIGHT_FLOOR > 0.20f)
        assertTrue(TasteTagPlanets.ROCK_NIGHT_FLOOR < 0.40f)
    }

    @Test
    fun `形状锚-PLANET_FS吃进夜面常数`() {
        // 常数编译期内插（0.16f→"0.16"、tint vec3、缘光 mix(0.3, 0.55, day)）
        assertTrue(GlShaders.PLANET_FS.contains("alb * 0.16 * vec3(0.74, 0.84, 1.12)"))
        assertTrue(GlShaders.PLANET_FS.contains("mix(0.3, 0.55, day)"))
        // 旧死黑式已退役（alb * 0.06 不得回归）
        assertTrue(!GlShaders.PLANET_FS.contains("alb * 0.06"))
    }

    @Test
    fun `形状锚-ROCK_FS吃进岩石夜面常数`() {
        assertTrue(GlShaders.ROCK_FS.contains("(0.26 + 0.92 * dif)"))
        assertTrue(!GlShaders.ROCK_FS.contains("(0.20 + 0.92 * dif)"))
    }
}
