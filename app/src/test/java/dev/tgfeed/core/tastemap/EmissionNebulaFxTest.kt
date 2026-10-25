package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R323 发射星云单测锚（r315 K⑤ 单测锚表全项——[EmissionNebulaFx] core 纯件）：
 * 判据门（占比≥50%）/金矿缺失恒普通/星胚粒数-候选数单调+帽 8/电离朝向（最近
 * 活跃星方向余弦域）/发射档 alpha 帽 0.10/联动口空间共在门（CYCLE_PULSE_*
 * 消费——R328 挂账兑现）/星胚偏移域与确定性。测试名零 JVM 非法字符（§七）。
 */
class EmissionNebulaFxTest {

    // ==================== 判据门（占比≥50%——金矿候选聚集=产房成立） ====================

    @Test
    fun `判据门-占比过半判发射档`() {
        assertTrue(EmissionNebulaFx.isEmission(4, 2))    // 恰 50%（≥门）
        assertTrue(EmissionNebulaFx.isEmission(3, 2))    // 66.7%
        assertTrue(EmissionNebulaFx.isEmission(10, 9))   // 90%
        assertFalse(EmissionNebulaFx.isEmission(4, 1))   // 25% 不足
        assertFalse(EmissionNebulaFx.isEmission(10, 4))  // 40% <50%
    }

    @Test
    fun `金矿缺失恒普通-零候选零成员防御`() {
        // 诚实红线（K①）：金矿快照缺失→候选全零→恒普通（结构上不可绕）
        assertFalse(EmissionNebulaFx.isEmission(3, 0))
        assertFalse(EmissionNebulaFx.isEmission(30, 0))
        assertFalse(EmissionNebulaFx.isEmission(10, -1)) // 防御：负候选不判
        assertFalse(EmissionNebulaFx.isEmission(0, 0))   // 防御：零成员不判
    }

    // ==================== 星胚粒数（候选数单调+帽 8） ====================

    @Test
    fun `星胚粒数-候选数单调帽8`() {
        assertEquals(0, EmissionNebulaFx.embryoCountOf(0))
        assertEquals(2, EmissionNebulaFx.embryoCountOf(2)) // 候选<3：粒数=候选数不冒充
        assertEquals(3, EmissionNebulaFx.embryoCountOf(3))
        assertEquals(8, EmissionNebulaFx.embryoCountOf(8))
        assertEquals(8, EmissionNebulaFx.embryoCountOf(9))  // 帽 8
        assertEquals(8, EmissionNebulaFx.embryoCountOf(99)) // 帽 8（大团不超）
        // 单调（0..8 域内逐位）
        for (c in 0 until 8) {
            assertTrue(EmissionNebulaFx.embryoCountOf(c) <= EmissionNebulaFx.embryoCountOf(c + 1))
        }
    }

    @Test
    fun `星胚基alpha与偏移-域与确定性`() {
        // 基 alpha 域 0.05~0.10（预告版原值族）
        for (k in 0 until 8) {
            val a = EmissionNebulaFx.embryoAlphaBase01(k, 12345)
            assertTrue(a >= EmissionNebulaFx.EMBRYO_ALPHA_MIN && a <= 0.10f)
        }
        // 偏移域：模 ≤0.62（雾盘内圈——「内部星胚」），确定性同种子同位
        val o1 = FloatArray(2)
        val o2 = FloatArray(2)
        for (k in 0 until 8) {
            EmissionNebulaFx.embryoOffset01(k, 777, o1)
            EmissionNebulaFx.embryoOffset01(k, 777, o2)
            assertEquals(o1[0], o2[0], 0f)
            assertEquals(o1[1], o2[1], 0f)
            val m = kotlin.math.sqrt(o1[0] * o1[0] + o1[1] * o1[1])
            assertTrue(m <= 0.62f)
            assertTrue(m >= 0.15f) // 散布下界（不挤雾心）
        }
    }

    // ==================== 亮档（alpha×1.6 帽 0.10+饱和度升） ====================

    @Test
    fun `发射档alpha-乘子原样与帽联动2x雾帽`() {
        // 乘子原样（R323 单源零改）：0.05×1.6=0.08
        assertEquals(0.08f, EmissionNebulaFx.emissionAlphaOf(0.05f), 1e-4f)
        // R358 帽联动锚：帽=2×FOG_ALPHA_MAX（R252 档 2:1 历史比单源化）
        assertEquals(TasteNebula.FOG_ALPHA_MAX * 2f, EmissionNebulaFx.EMISSION_ALPHA_CAP, 1e-6f)
        // 发射满档恒亮于普通满档（防「发射被帽钳到普通之下」语义倒挂——R358 联动判词）
        assertTrue(EmissionNebulaFx.emissionAlphaOf(TasteNebula.FOG_ALPHA_MAX) > TasteNebula.FOG_ALPHA_MAX)
        // 极端雾档防御：0.30×1.6=0.48→帽
        assertEquals(EmissionNebulaFx.EMISSION_ALPHA_CAP, EmissionNebulaFx.emissionAlphaOf(0.30f), 1e-4f)
        assertTrue(EmissionNebulaFx.emissionAlphaOf(0f) <= 0f)
    }

    @Test
    fun `亮档色-饱和度与明度升色相不动`() {
        val base = 0xFF3388AA.toInt() // 中性样色
        val bright = EmissionNebulaFx.brightColorOf(base)
        fun hsv(argb: Int): FloatArray {
            val r = ((argb shr 16) and 0xFF) / 255f
            val g = ((argb shr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            val max = maxOf(r, g, b); val min = minOf(r, g, b)
            val v = max
            val s = if (max <= 0f) 0f else (max - min) / max
            return floatArrayOf(TasteStarColor.hueOfArgb(argb), s, v)
        }
        val h0 = hsv(base); val h1 = hsv(bright)
        assertTrue(h1[1] > h0[1]) // 饱和度升（发射星云更浓）
        assertTrue(h1[2] > h0[2]) // 明度升（更亮）
        assertEquals(h0[0], h1[0], 1.0f) // 色相原样（类别投票语义不动——8bit 往返容差）
        // 全黑防御：原样透传（不为无色造亮）
        assertEquals(0xFF000000.toInt(), EmissionNebulaFx.brightColorOf(0xFF000000.toInt()))
    }

    // ==================== 电离朝向（余弦域——最近活跃星一侧更亮） ====================

    @Test
    fun `电离朝向-余弦域单调与边界`() {
        assertEquals(0f, EmissionNebulaFx.ionization01(-1f), 1e-4f)  // 背向=零增益
        assertEquals(0.5f, EmissionNebulaFx.ionization01(0f), 1e-4f) // 侧向=半增益
        assertEquals(1f, EmissionNebulaFx.ionization01(1f), 1e-4f)   // 朝向=满增益
        // 余弦域钳制（防御——正常域 [-1,1]）
        assertEquals(0f, EmissionNebulaFx.ionization01(-3f), 1e-4f)
        assertEquals(1f, EmissionNebulaFx.ionization01(3f), 1e-4f)
        // 单调
        assertTrue(EmissionNebulaFx.ionization01(0.3f) < EmissionNebulaFx.ionization01(0.6f))
    }

    @Test
    fun `电离侧辉-增益帽与合计不超发射帽`() {
        val ea = EmissionNebulaFx.emissionAlphaOf(0.05f) // 0.08
        val glow = EmissionNebulaFx.ionizationSideGlowAlpha(ea, 1f)
        assertTrue(glow > 0f && glow <= ea * EmissionNebulaFx.IONIZATION_GAIN_MAX + 1e-5f)
        // 星侧合计（雾体+侧辉）≤ EMISSION_ALPHA_CAP（K⑤ 亮度克制的总账）
        assertTrue(ea + glow <= EmissionNebulaFx.EMISSION_ALPHA_CAP + 1e-5f)
        assertEquals(0f, EmissionNebulaFx.ionizationSideGlowAlpha(ea, 0f), 1e-5f)
    }

    @Test
    fun `电离源判定-OB档光谱单口`() {
        // O/B=炽热活跃（spectralClassOf 单口消费——recent7 饱和口径）
        assertTrue(EmissionNebulaFx.isIonizingSource(14, 0L))  // 满档=O
        assertTrue(EmissionNebulaFx.isIonizingSource(12, 0L))  // B
        assertFalse(EmissionNebulaFx.isIonizingSource(10, 0L)) // A 以下非电离源
        assertFalse(EmissionNebulaFx.isIonizingSource(0, 0L))  // M
        // 无金矿档案回落：断更分级（<7 天=0.35→G）非 O/B
        assertFalse(EmissionNebulaFx.isIonizingSource(-1, 3L))
        // 双缺=诚实 G 型
        assertFalse(EmissionNebulaFx.isIonizingSource(-1, -1L))
    }

    // ==================== 循环联动口（R328 挂账兑现——CYCLE_PULSE_* 消费） ====================

    @Test
    fun `联动口-空间共在门外恒零`() {
        // 无活跃超新星（active=false）→恒 0（r325 C①-2 联动口门外零行为）
        assertEquals(0f, EmissionNebulaFx.cyclePulse01(0f, false, 10f, 7), 0f)
        assertEquals(0f, EmissionNebulaFx.cyclePulse01(0.5f, false, 10f, 7), 0f)
        // 窗缘外（x01≥1）→0 早退
        assertEquals(0f, EmissionNebulaFx.cyclePulse01(1f, true, 10f, 7), 0f)
        assertEquals(0f, EmissionNebulaFx.cyclePulse01(1.5f, true, 10f, 7), 0f)
    }

    @Test
    fun `联动口-窗内脉冲域与衰减单调`() {
        // 窗内：CYCLE_PULSE 呼吸（0.05~0.10）×平方衰减（≤1）→域 (0, 0.10]
        val at0 = EmissionNebulaFx.cyclePulse01(0f, true, 10f, 7)
        assertTrue(at0 > 0f && at0 <= 0.10f + 1e-5f)
        // 衰减单调：距源越远脉冲越弱
        val mid = EmissionNebulaFx.cyclePulse01(0.5f, true, 10f, 7)
        val near = EmissionNebulaFx.cyclePulse01(0.1f, true, 10f, 7)
        assertTrue(near > mid && mid > 0f)
        // 确定性：同参同值（零随机零系统钟——t 由调用方传）
        assertEquals(at0, EmissionNebulaFx.cyclePulse01(0f, true, 10f, 7), 0f)
    }
}
