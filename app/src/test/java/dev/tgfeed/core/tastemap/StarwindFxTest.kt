package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R321 星风单测（r315 F⑤ 锚表+任务书单测锚全套）：星风门双门（recent7≥14 ∧
 * degree>0——**与 TasteStarColor.activity01Of 同源单口锚**）/强度域/尘埃不对称
 * 系数域（±0.35 帽）/粒子上限帽（8 槽并发）/流速率域/确定性。
 * R376：泡域锚（半径/alpha 剖面）随星风泡退役删除（用户小米截图像素实锚 36/689
 * 颗 6.0~8.5×r 灰白环+半径比代码指纹逐位吻合——退役判词见 StarwindFx KDoc；
 * 锚随行为显式退役非静默解除）；星风语义锚全保活（门/强度/流/尘埃）。
 */
class StarwindFxTest {

    // ---------------- 星风门（双门+activity01Of 同源单口） ----------------

    @Test
    fun `星风门-双门与缺据无风`() {
        // 双门：recent7≥14 ∧ degree>0
        assertTrue(StarwindFx.isWindy(14, 3))
        assertTrue(StarwindFx.isWindy(30, 1))
        assertFalse("13 条 < 门=微风不可见", StarwindFx.isWindy(13, 3))
        assertFalse("degree=0=无外流通道=无风", StarwindFx.isWindy(20, 0))
        // 缺据（recent7<0 无金矿证据）=无风（诚实红线）
        assertFalse(StarwindFx.isWindy(-1, 5))
        assertFalse(StarwindFx.isWindy(-1, 0))
    }

    @Test
    fun `星风门-与activity01Of同源单口锚（不许两处写活跃门常量）`() {
        // 门=activity01Of 饱和（≥1f ⟺ recent7≥14）——单一出处纪律§一的机检锚：
        // 任何 recent7 上两口径判定必须逐位一致（若 activity01Of 口径漂移，本锚红）
        for (r7 in intArrayOf(-1, 0, 1, 7, 13, 14, 15, 20, 56, 200)) {
            val saturated = TasteStarColor.activity01Of(r7) >= 1f
            val gate = StarwindFx.isWindy(r7, 1)
            assertEquals(
                "星风门与 activity01Of 饱和口径同源（recent7=$r7）",
                saturated,
                gate,
            )
        }
        // 门值本体：14 条/周=满档（O/B 光谱型档——r315-F① 字面）
        assertEquals(1f, TasteStarColor.activity01Of(14), 0.0001f)
    }

    // ---------------- 强度（星风流/尘埃不对称的消费单源） ----------------

    @Test
    fun `强度-门后梯度域与门外恒零`() {
        assertEquals(0f, StarwindFx.strength01Of(14), 0.0001f) // 门上=0
        assertEquals(0f, StarwindFx.strength01Of(13), 0.0001f) // 门外
        assertEquals(0f, StarwindFx.strength01Of(-1), 0.0001f) // 缺据
        assertEquals(1f, StarwindFx.strength01Of(StarwindFx.W_RECENT7_SAT), 0.0001f)
        assertEquals(1f, StarwindFx.strength01Of(999), 0.0001f) // 饱和钳域
        assertTrue(StarwindFx.strength01Of(20) > 0f)
        assertTrue(StarwindFx.strength01Of(30) > StarwindFx.strength01Of(20))
    }

    // R376 泡域锚退役位：旧「泡域-半径与alpha随强度单调且钳帽」例随星风泡绘制
    // 本体删除（BUBBLE_R_*/BUBBLE_A_*/bubbleRadiusCoef/bubbleAlphaOf 四件同批退役
    // ——退役判词与像素实锚见 StarwindFx 类 KDoc「R376 星风泡退役」段）。

    // ---------------- 尘埃不对称（±0.35 帽） ----------------

    @Test
    fun `尘埃不对称-系数域帽与keep域`() {
        // 系数域：[0, 0.35]（±0.35 帽防撕碎轨道可读性——r315-F⑤ 字面）
        assertEquals(0f, StarwindFx.dustAsym01(0f), 0.0001f)
        assertEquals(StarwindFx.DUST_ASYM_MAX, StarwindFx.dustAsym01(1f), 0.0001f)
        assertEquals(StarwindFx.DUST_ASYM_MAX, StarwindFx.dustAsym01(9f), 0.0001f)
        assertEquals(0.35f, StarwindFx.DUST_ASYM_MAX, 0.0001f)
        // 无风恒 1=现状零改（asym=0 → keep 恒 1）
        assertEquals(1f, StarwindFx.dustKeep01(3.14159f, 0f), 0.0001f)
        // 背风密（Δ=0：cos=1 → keep=1——尘埃被吹到下风侧聚积）
        assertEquals(1f, StarwindFx.dustKeep01(0f, 0.35f), 0.0001f)
        // 迎风稀（Δ=π：cos=−1 → keep=1−asym——迎风侧被吹开）
        assertEquals(0.65f, StarwindFx.dustKeep01(3.14159265f, 0.35f), 0.001f)
        // 域下界：keep ≥ 1−0.35=0.65（任何相位/任何合法 asym——帽的运行时保证）
        for (i in 0..12) {
            val delta = i * 0.5236f
            for (j in 0..7) {
                val asym = j * 0.05f
                val keep = StarwindFx.dustKeep01(delta, asym)
                assertTrue("keep ≥ 1−帽（Δ=$delta asym=$asym keep=$keep）", keep >= 0.65f - 1e-4f)
                assertTrue(keep <= 1f + 1e-6f)
            }
        }
        // 防御：asym 超界入参钳帽
        assertTrue(StarwindFx.dustKeep01(3.14159265f, 5f) >= 0.65f - 1e-4f)
    }

    // ---------------- 星风流（速率域/槽帽/纯 t 确定性） ----------------

    @Test
    fun `流速率-域与强度单调`() {
        assertEquals(2f, StarwindFx.streamRateOf(0f), 0.0001f)
        assertEquals(4f, StarwindFx.streamRateOf(1f), 0.0001f)
        assertEquals(4f, StarwindFx.streamRateOf(9f), 0.0001f)
        assertTrue(StarwindFx.streamRateOf(0.5f) > StarwindFx.streamRateOf(0.2f))
        // 并发帽：8 槽（帧预算护栏——r315-F④「粒子上限 8 并发帽」）
        assertEquals(8, StarwindFx.STREAM_SLOTS)
        assertTrue(StarwindFx.STREAM_SLOTS <= 8)
    }

    @Test
    fun `流粒子-纯t函数确定性与活性窗`() {
        // 同 (t, seed, k) 两跑逐位一致（零常驻状态——渲染无状态复现锚）
        for (k in 0 until StarwindFx.STREAM_SLOTS) {
            val p1 = StarwindFx.streamPhase01(12.34f, 3f, 77, k)
            val p2 = StarwindFx.streamPhase01(12.34f, 3f, 77, k)
            assertEquals("槽 $k 确定性", p1, p2, 0.0001f)
            assertTrue("相位域 0..1（槽 $k 实=$p1）", p1 in 0f..1f)
        }
        // 时间推进=相位推进（纯 t 函数——非新时序源的字面验证）
        val t0 = StarwindFx.streamPhase01(10f, 2f, 77, 3)
        val t1 = StarwindFx.streamPhase01(11f, 2f, 77, 3)
        assertTrue(t0 != t1)
        // 活性窗：窗内 1→0 线性衰减/窗外恒 0（零绘制零成本早退）
        assertEquals(1f, StarwindFx.streamAlive01(0f), 0.0001f)
        assertEquals(0f, StarwindFx.streamAlive01(1f), 0.0001f)
        assertEquals(0f, StarwindFx.streamAlive01(0.9f), 0.0001f)
        val mid = StarwindFx.streamAlive01(0.25f)
        assertTrue(mid > 0f && mid < 1f)
        assertTrue(StarwindFx.streamAlive01(0.1f) > StarwindFx.streamAlive01(0.4f))
    }

    @Test
    fun `风轴-确定性哈希域`() {
        for (seed in intArrayOf(0, 1, 7, 123, -456, 99991)) {
            val a = StarwindFx.windAxisRad(seed)
            assertEquals("同 seed 确定性", a, StarwindFx.windAxisRad(seed), 0.0001f)
            assertTrue("风轴域 0..2π（seed=$seed 实=$a）", a in 0f..6.2831854f)
        }
    }
}
