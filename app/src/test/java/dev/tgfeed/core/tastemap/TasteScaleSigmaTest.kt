package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * R303c P3 σ 统一件单测（TasteScaleSigma——design §5.1/§5.2 三铁律的机械锚）：
 * ①主尺度坐标（σ 单调/端点钳/逆变换互逆）②窗口必交叠无真空带（σ 全程扫描
 * 至少一层全盛）③C0 连续（端点值精确相等）④双件同画（过渡段邻层双在场）⑤
 * 银河视角位姿（7000/25°/瞄准银心+与 standardCams 银河位同源）。
 */
class TasteScaleSigmaTest {

    private val S = TasteScaleSigma

    // ==================== 主尺度坐标（§5.1） ====================

    @Test
    fun `σ-单调与端点钳与互逆`() {
        // 停船锚=σ0（贴脸档）；每 +1 距离翻倍
        assertEquals(0f, S.sigmaOf(260f), 1e-4f)
        assertEquals(1f, S.sigmaOf(520f), 1e-4f)
        assertEquals(2f, S.sigmaOf(1040f), 1e-4f)
        // 单调（d 升 σ 升）
        var prev = Float.NEGATIVE_INFINITY
        var d = 50f
        while (d < 20000f) {
            val s = S.sigmaOf(d)
            assertTrue("d=$d σ=$s 应单调 ≥ $prev", s >= prev)
            prev = s
            d *= 1.13f
        }
        // 端点钳（d≤0=σ_MIN；超上界=σ_MAX）
        assertEquals(S.SIGMA_MIN, S.sigmaOf(100f), 1e-5f)
        assertEquals(S.SIGMA_MIN, S.sigmaOf(0f), 1e-5f)
        assertEquals(S.SIGMA_MAX, S.sigmaOf(1e9f), 1e-4f)
        // 逆变换互逆（σ→d→σ 精确回环；起点=σ_MIN 域内）
        var sg = S.SIGMA_MIN
        while (sg < 5.9f) {
            assertEquals(sg, S.sigmaOf(S.distOf(sg)), 1e-4f)
            sg += 0.37f
        }
        assertEquals(S.DOCK_DIST, S.distOf(0f), 1e-3f)
    }

    @Test
    fun `σ-五档尺度表锚定`() {
        // §4.1 五档：L0 尘埃 150~260（σ∈[−0.8,0]）；L1 井深 260~832=σ[0,1.68]；
        // L4 银河位 7000=σ≈4.75（银河视角口径）
        assertEquals(-0.79f, S.sigmaOf(150f), 0.02f)
        assertEquals(4.75f, S.sigmaOf(7000f), 0.02f)
        assertEquals(4.2f, S.sigmaOf(260f * 18.38f), 0.02f)
    }

    // ==================== 铁律①：窗口必交叠（无真空带） ====================

    @Test
    fun `铁律一-任何σ处至少一层全盛`() {
        // σ∈[−1,6] 逐 0.05 步长：无真空带（任何 σ 处至少一件全盛=窗口必交叠）
        var s = S.SIGMA_MIN
        while (s <= S.SIGMA_MAX) {
            assertTrue(
                "σ=%.2f 处无全盛层（真空带——§5.2 铁律①破）".format(s),
                S.anyLayerFullAt(s),
            )
            s += 0.05f
        }
    }

    // ==================== 铁律③：C0 连续（端点值精确相等） ====================

    @Test
    fun `铁律三-端点值精确相等`() {
        for (l in TasteScaleSigma.Layer.entries) {
            val a0 = S.alphaOf(l, l.fadeInIn)
            assertTrue("${l} 淡入起点 alpha=$a0 应=0", abs(a0) < 1e-6f)
            val a1 = S.alphaOf(l, l.fadeInOut)
            assertTrue("${l} 全盛起点 alpha=$a1 应=1", abs(a1 - 1f) < 1e-6f)
            val a2 = S.alphaOf(l, l.fadeOutIn)
            assertTrue("${l} 淡出起点 alpha=$a2 应=1（全盛尾）", abs(a2 - 1f) < 1e-6f)
            if (l.fadeOutOut.isFinite()) {
                val a3 = S.alphaOf(l, l.fadeOutOut)
                assertTrue("${l} 淡出终点 alpha=$a3 应=0", abs(a3) < 1e-6f)
            }
        }
        // 全盛窗中段恒 1
        assertEquals(1f, S.alphaOf(TasteScaleSigma.Layer.DUST, 0f), 0f)
        assertEquals(1f, S.alphaOf(TasteScaleSigma.Layer.PLANETS_FULL, 1.0f), 0f)
        assertEquals(1f, S.alphaOf(TasteScaleSigma.Layer.GALAXY_BG, 5.2f), 0f)
    }

    @Test
    fun `铁律三-alpha连续性-微步长无跳变`() {
        // 每层 α(σ) 沿 σ 0.01 步长连续（|Δα| 平滑——C0 的数值面）
        for (l in TasteScaleSigma.Layer.entries) {
            var prev = S.alphaOf(l, S.SIGMA_MIN)
            var s = S.SIGMA_MIN + 0.01f
            while (s <= S.SIGMA_MAX) {
                val a = S.alphaOf(l, s)
                assertTrue(
                    "${l} α 在 σ=%.2f 跳变 |Δ|=%.3f".format(s, abs(a - prev)),
                    abs(a - prev) < 0.06f,
                )
                prev = a
                s += 0.01f
            }
        }
    }

    // ==================== 铁律②：过渡段双件同画（cross-dissolve） ====================

    @Test
    fun `铁律二-过渡段双件同画`() {
        // cross-dissolve 判据=新层淡入窗内旧层仍显著在场（旧全盛+新浮现=双件
        // 同画；§5.2「过渡段双件同画」的字面口径——L1 淡入时 L0 尚全盛等五交接带）
        val handoffs = listOf(
            TasteScaleSigma.Layer.DUST to TasteScaleSigma.Layer.PLANETS_FULL,
            TasteScaleSigma.Layer.PLANETS_FULL to TasteScaleSigma.Layer.PLANETS_LITE,
            TasteScaleSigma.Layer.PLANETS_LITE to TasteScaleSigma.Layer.SYSTEM_GLYPH,
            TasteScaleSigma.Layer.SYSTEM_GLYPH to TasteScaleSigma.Layer.CLUSTER_LINKS,
            TasteScaleSigma.Layer.CLUSTER_LINKS to TasteScaleSigma.Layer.GALAXY_BG,
        )
        for ((oldL, newL) in handoffs) {
            // 新层淡入窗=交接带（此窗内新层 α∈(0,1) 浮现、旧层应仍 >0.5）
            val lo = newL.fadeInIn
            val hi = newL.fadeInOut
            assertTrue("${newL} 淡入窗宽 ≤0（表配置错）", hi > lo)
            var both = 0
            var s = lo + 0.01f
            while (s < hi) {
                val aOld = S.alphaOf(oldL, s)
                val aNew = S.alphaOf(newL, s)
                if (aOld > 0.5f && aNew > 0.01f && aNew < 1f) both++
                s += 0.02f
            }
            assertTrue(
                "${oldL}→${newL} 淡入窗内双件同画样本=$both 应 >0（cross-dissolve）",
                both > 0,
            )
        }
    }

    // ==================== 银河视角位姿（§7.2） ====================

    @Test
    fun `银河视角-银心距7000俯角25瞄准银心`() {
        val pose = S.galaxyViewPose()
        val dist = kotlin.math.sqrt(pose.px * pose.px + pose.py * pose.py + pose.pz * pose.pz)
        assertEquals(S.GALAXY_VIEW_DIST, dist, 0.5f)
        // 俯角 25°：y/sin 分量核对（朝向原点=俯视）
        assertEquals((S.GALAXY_VIEW_DIST * kotlin.math.sin(Math.toRadians(25.0))).toFloat(), pose.py, 0.5f)
        assertEquals((-S.GALAXY_VIEW_DIST * kotlin.math.cos(Math.toRadians(25.0))).toFloat(), pose.pz, 0.5f)
        // 瞄准银心：从位姿点看原点的 aim 角
        val aim = TasteSpace.aimAt(pose, 0f, 0f, 0f, FloatArray(2))
        assertEquals(aim[0], pose.yawDeg, 0.01f)
        assertEquals(aim[1], pose.pitchDeg, 0.01f)
        assertEquals(-25f, pose.pitchDeg, 0.01f) // 俯视银心=负俯角
    }

    @Test
    fun `银河视角-与标准机位组银河位同源`() {
        // 同一口径单一出处（TasteEmergentLayout.standardCams 银河位委托本件——
        // 同一距离语义不许两处常量）
        val pose = S.galaxyViewPose()
        val cams = TasteEmergentLayout.standardCams()
        val gal = cams.first { it.first == "银河位" }.second
        assertEquals(gal.px, pose.px, 0.01f)
        assertEquals(gal.py, pose.py, 0.01f)
        assertEquals(gal.pz, pose.pz, 0.01f)
        assertEquals(gal.yawDeg, pose.yawDeg, 0.01f)
        assertEquals(gal.pitchDeg, pose.pitchDeg, 0.01f)
    }

    @Test
    fun `σ-负值与防御`() {
        // d≤0 防御：钳 σ_MIN 不产 NaN
        assertEquals(S.SIGMA_MIN, S.sigmaOf(0f), 0f)
        assertEquals(S.SIGMA_MIN, S.sigmaOf(-100f), 0f)
        assertFalse(S.sigmaOf(100f).isNaN())
        assertFalse(S.distOf(S.SIGMA_MIN).isNaN())
    }
}
