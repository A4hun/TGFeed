package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R311 色彩引擎锚（TasteStarColor 纯件）：
 * 黑体谱连续性/单调性/钳域、三轴色温方向（活跃↑热/年龄↑红/体量↑热/微抖防撞色）、
 * 构成微调、行星色三票+恒星光染色、造父变星判据/周期-光度/快升慢降波形、
 * 环带颗粒确定性/聚块场连续性。
 */
class TasteStarColorTest {

    // ==================== 黑体谱 ====================

    @Test
    fun `黑体谱-连续值不撞色-相邻色温不同色`() {
        var prev = 0
        var diffCount = 0
        for (k in 2400..11800 step 50) {
            val c = TasteStarColor.blackbodyRgb(k.toFloat())
            if (k > 2400 && c != prev) diffCount++
            prev = c
        }
        // 188 个采样点：连续谱下大面积相邻不同（黑体近似式在 R/B 满档平台段有
        // 量化平台——真防撞由 ±90K 微抖兜底，此处锚「主导区间连续」）
        assertTrue("连续谱相邻色应大面积不同（实测 $diffCount/187）", diffCount > 120)
    }

    @Test
    fun `黑体谱-温度越高越蓝`() {
        val cold = TasteStarColor.blackbodyRgb(2600f)
        val hot = TasteStarColor.blackbodyRgb(11000f)
        // 冷星：红显著大于蓝；热星：蓝显著上升
        assertTrue("2600K 应红大于蓝", (cold shr 16 and 0xFF) > (cold and 0xFF) + 80)
        assertTrue("11000K 蓝通道应高于 2600K", (hot and 0xFF) > (cold and 0xFF) + 100)
    }

    @Test
    fun `黑体谱-太阳黄白带 5800K 合理`() {
        val c = TasteStarColor.blackbodyRgb(5800f)
        val r = c shr 16 and 0xFF
        val g = c shr 8 and 0xFF
        val b = c and 0xFF
        assertTrue("5800K R 应近满", r >= 240)
        assertTrue("5800K G 应在黄白带", g in 200..255)
        assertTrue("5800K B 应低于 R（黄白非蓝白）", b < r)
    }

    @Test
    fun `黑体谱-钳域`() {
        assertEquals(TasteStarColor.blackbodyRgb(2400f), TasteStarColor.blackbodyRgb(2400f))
        // 域外输入不崩（内部钳 1000..40000）
        TasteStarColor.blackbodyRgb(10f)
        TasteStarColor.blackbodyRgb(999999f)
    }

    // ==================== 三轴色温 ====================

    @Test
    fun `色温-活跃轴单调`() {
        val tCold = TasteStarColor.starKelvinOf(0.0f, 100f, 0.5f, 42L)
        val tHot = TasteStarColor.starKelvinOf(1.0f, 100f, 0.5f, 42L)
        assertTrue("活跃↑应更热", tHot > tCold + 4000f)
    }

    @Test
    fun `色温-年龄轴越老越红`() {
        val tYoung = TasteStarColor.starKelvinOf(0.5f, 10f, 0.5f, 42L)
        val tOld = TasteStarColor.starKelvinOf(0.5f, 1095f, 0.5f, 42L)
        assertTrue("老星应更冷（红）", tOld < tYoung - 1500f)
    }

    @Test
    fun `色温-体量轴大热冷小`() {
        val tSmall = TasteStarColor.starKelvinOf(0.5f, 100f, 0.0f, 42L)
        val tBig = TasteStarColor.starKelvinOf(0.5f, 100f, 1.0f, 42L)
        assertTrue("大频道应更热", tBig > tSmall + 2000f)
    }

    @Test
    fun `色温-同参数不同chatId微抖不撞色`() {
        val a = TasteStarColor.starKelvinOf(0.5f, 100f, 0.5f, 111L)
        val b = TasteStarColor.starKelvinOf(0.5f, 100f, 0.5f, 222L)
        assertNotEquals("同参不同 id 色温应微分", a, b)
        assertTrue("微抖幅度应在 ±90K 档", kotlin.math.abs(a - b) < 180f)
    }

    @Test
    fun `色温-钳域`() {
        val t0 = TasteStarColor.starKelvinOf(0f, 99999f, 0f, 1L)
        val t1 = TasteStarColor.starKelvinOf(1f, 0f, 1f, 9L)
        assertTrue(t0 >= TasteStarColor.TEMP_MIN_K && t1 <= TasteStarColor.TEMP_MAX_K)
    }

    @Test
    fun `色温-无年龄证据中立`() {
        val tNoAge = TasteStarColor.starKelvinOf(0.5f, -1f, 0.5f, 42L)
        val tMidAge = TasteStarColor.starKelvinOf(0.5f, 547f, 0.5f, 42L)
        assertTrue("无证据不应更老", tNoAge > tMidAge)
    }

    // ==================== 构成微调 ====================

    @Test
    fun `恒星色-构成微调但不夺主`() {
        val k = 5800f
        val base = TasteStarColor.blackbodyRgb(k)
        val video = TasteStarColor.compositionArgb(100, 0, 0)
        val tinted = TasteStarColor.starColorOfK(k, video)
        // 微调后色相应偏构成色方向但仍以黑体为主（RGB 距离占比验证）
        assertNotEquals(base, tinted)
        val distBase = colorDist(tinted, base)
        val distVideo = colorDist(tinted, video)
        assertTrue("微调后应离黑体近、离构成色远", distBase < distVideo)
    }

    @Test
    fun `恒星色-无构成证据纯黑体走柔光档`() {
        val k = 6000f
        // 无构成=不染色，但出口过柔光档（S/V 钳——纯饱和黑体色不直出）
        val c = TasteStarColor.starColorOfK(k, 0)
        val expected = TasteStarColor.softGrade(TasteStarColor.blackbodyRgb(k))
        assertEquals(expected, c)
        // 柔光档域锚
        val hsv = FloatArray(3)
        val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
        val mx = maxOf(r, g, b).toFloat(); val mn = minOf(r, g, b).toFloat()
        hsv[2] = mx / 255f; hsv[1] = if (mx <= 0f) 0f else (mx - mn) / mx
        assertTrue(hsv[1] in 0.15f..0.59f)
        assertTrue(hsv[2] in 0.65f..0.93f)
    }

    @Test
    fun `构成色-零票哨兵`() {
        assertEquals(0, TasteStarColor.compositionArgb(0, 0, 0))
        assertNotEquals(0, TasteStarColor.compositionArgb(1, 0, 0))
    }

    // ==================== 行星色 ====================

    @Test
    fun `行星色-三票轴向`() {
        val star = TasteStarColor.blackbodyRgb(5800f)
        val video = TasteStarColor.planetColorOf(9, 1, 0, star, 100)
        val photo = TasteStarColor.planetColorOf(0, 9, 1, star, 100)
        val audio = TasteStarColor.planetColorOf(1, 0, 9, star, 100)
        val hv = TasteStarColor.hueOfArgb(video)
        val hp = TasteStarColor.hueOfArgb(photo)
        val ha = TasteStarColor.hueOfArgb(audio)
        assertTrue("视频行星应暖（橙带）", hv in 350f..360f || hv in 0f..90f)
        assertTrue("图片行星应冷（蓝带）", hp in 150f..250f)
        assertTrue("音频行星应紫（紫带）", ha in 250f..320f)
    }

    @Test
    fun `行星色-零票回退恒星光`() {
        val star = TasteStarColor.blackbodyRgb(4000f)
        val c = TasteStarColor.planetColorOf(0, 0, 0, star, 100)
        // 回退色应接近恒星色相（±抖动+染色域内）
        val dh = hueDist(TasteStarColor.hueOfArgb(c), TasteStarColor.hueOfArgb(star))
        assertTrue("零票行星应贴恒星色相", dh < 40f)
    }

    @Test
    fun `行星色-确定性`() {
        val star = TasteStarColor.blackbodyRgb(5800f)
        assertEquals(
            TasteStarColor.planetColorOf(3, 2, 1, star, 777),
            TasteStarColor.planetColorOf(3, 2, 1, star, 777),
        )
    }

    // ==================== 造父变星 ====================

    @Test
    fun `变星-判据门槛`() {
        // 规则更新频道：1000 条、跨度 500 天（均距 0.5 天）、近两周 6/5 稳定
        val c = TasteStarColor.cepheidOf(1000, 1000000L, 1000000L + 500L * 86400L, 6, 5, 42L)
        assertTrue(c != null)
        // 条数不足
        assertNull(TasteStarColor.cepheidOf(10, 1000000L, 1000000L + 500L * 86400L, 6, 5, 42L))
        // 跨度不足
        assertNull(TasteStarColor.cepheidOf(1000, 1000000L, 1000000L + 10L * 86400L, 6, 5, 42L))
        // 均距过慢（45 天外=停更语义）
        assertNull(TasteStarColor.cepheidOf(30, 1000000L, 1000000L + 3000L * 86400L, 1, 1, 42L))
        // 节奏突变（近7天=0：停更语义非搏动）
        assertNull(TasteStarColor.cepheidOf(1000, 1000000L, 1000000L + 500L * 86400L, 0, 5, 42L))
        assertNull(TasteStarColor.cepheidOf(1000, 1000000L, 1000000L + 500L * 86400L, 5, 0, 42L))
        // 无金矿节奏证据
        assertNull(TasteStarColor.cepheidOf(1000, 1000000L, 1000000L + 500L * 86400L, -1, -1, 42L))
        // 首见无证据
        assertNull(TasteStarColor.cepheidOf(1000, 0L, 1000000L, 6, 5, 42L))
    }

    @Test
    fun `变星-周期光度关系-周期长振幅大`() {
        // 短周期（均距 0.6 天）vs 长周期（均距 20 天）
        val fast = TasteStarColor.cepheidOf(2000, 1000000L, 1000000L + 1200L * 86400L, 9, 9, 42L)!!
        val slow = TasteStarColor.cepheidOf(60, 1000000L, 1000000L + 1200L * 86400L, 1, 1, 42L)!!
        assertTrue("周期长=可视周期长", slow.periodSec > fast.periodSec)
        assertTrue("周期长=振幅大（周期-光度关系）", slow.amplitude > fast.amplitude)
        assertTrue(slow.amplitude <= 0.24f && fast.amplitude >= 0.10f)
    }

    @Test
    fun `变星-波形快升慢降`() {
        // 满相前半应已到峰（快升），后半缓降
        val peakPhase = TasteStarColor.CEPHEID_RISE_FRAC
        val atPeak = TasteStarColor.cepheidShape(peakPhase - 0.001f)
        val midRise = TasteStarColor.cepheidShape(peakPhase * 0.5f)
        val midFall = TasteStarColor.cepheidShape(peakPhase + (1f - peakPhase) * 0.5f)
        assertTrue("升相中点应已过半峰（快升——(0.5)^0.65≈0.637>线性 0.5）", midRise > 0.60f)
        assertTrue("降相中点应仍高于升相中点起点感（慢降）", midFall > 0.35f)
        assertTrue("峰值近 1", atPeak > 0.97f)
        // 周期闭环：u=0 与 u→1 连续（慢降到 0）
        assertTrue(TasteStarColor.cepheidShape(0f) < 0.05f)
        assertTrue(TasteStarColor.cepheidShape(0.999f) < 0.05f)
    }

    @Test
    fun `变星-亮度因子域`() {
        val c = TasteStarColor.Cepheid(10f, 0.20f, 0.3f)
        var min = 2f
        var max = 0f
        var t = 0f
        while (t < 10f) {
            val l = TasteStarColor.cepheidLuma(t, c)
            if (l < min) min = l
            if (l > max) max = l
            t += 0.05f
        }
        assertTrue("亮度带应≈1±amp", min > 0.79f && max < 1.21f)
        assertTrue("null 恒 1", TasteStarColor.cepheidLuma(5f, null) == 1f)
        assertTrue("非变星恒 1", TasteStarColor.cepheidLuma(5f, TasteStarColor.Cepheid(0f, 0.2f, 0f)) == 1f)
    }

    // ==================== 环带颗粒 ====================

    @Test
    fun `环带-颗粒确定性与域`() {
        for (k in 0 until TasteStarColor.HABITABLE_GRAINS) {
            val ang = TasteStarColor.habitableGrainAngle(1234, k)
            assertTrue(ang >= 0f && ang < 6.30f)
            val a = TasteStarColor.habitableGrainAlpha(1234, ang)
            assertTrue(a >= TasteStarColor.HABITABLE_ALPHA_MIN && a <= TasteStarColor.HABITABLE_ALPHA_MAX)
            val coef = TasteStarColor.habitableGrainCoef(1234, k)
            assertTrue(kotlin.math.abs(coef - 1f) <= TasteStarColor.HABITABLE_RADIAL_JITTER + 1e-6f)
            val sz = TasteStarColor.habitableGrainSize(1234, k)
            assertTrue(sz in 0.6f..1.6f)
        }
        // 确定性
        assertEquals(
            TasteStarColor.habitableGrainAngle(1234, 7),
            TasteStarColor.habitableGrainAngle(1234, 7),
        )
    }

    @Test
    fun `环带-聚块场连续性（相邻角 alpha 不跳=成弧观感）`() {
        // 聚块=角位连续场：密布采样相邻点的 alpha 差应有界（非逐粒白噪声）
        var maxStep = 0f
        var prev = 0f
        for (i in 0..360) {
            val ang = i / 360f * 6.2831853f
            val a = TasteStarColor.habitableGrainAlpha(99, ang)
            if (i > 0) maxStep = maxOf(maxStep, kotlin.math.abs(a - prev))
            prev = a
        }
        assertTrue("相邻角 alpha 跳变应有界（连续场非白噪声），实测 $maxStep", maxStep < 0.10f)
    }

    // ==================== 工具 ====================

    private fun colorDist(a: Int, b: Int): Float {
        val dr = ((a shr 16 and 0xFF) - (b shr 16 and 0xFF)).toFloat()
        val dg = ((a shr 8 and 0xFF) - (b shr 8 and 0xFF)).toFloat()
        val db = ((a and 0xFF) - (b and 0xFF)).toFloat()
        return kotlin.math.sqrt(dr * dr + dg * dg + db * db)
    }

    private fun hueDist(a: Float, b: Float): Float {
        var d = kotlin.math.abs(a - b) % 360f
        if (d > 180f) d = 360f - d
        return d
    }
}
