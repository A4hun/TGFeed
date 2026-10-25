package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * R351 雾团 fBm 絮化单测（[FogFlocFx]——r344 审计 B 级最后一项的 core 单源；
 * 数值半体承重：GL 渲染端只画不算，絮化公式全过堂 JVM 直测）：
 *  - **fBm 值噪声**：确定性（同参同值——零 Random 零系统钟铁律）+值域
 *    [0,1)+多 octave 结构在场（≠单阶 vnoise）；
 *  - **blob 族**：数 2~3（含负种子余数归一）/半径系数只缩不胀/错位模长
 *    帽（弥散连体）+方向散布（真错位）/alpha 配比包**和恒 1.35**（量级
 *    零增益红线——絮化只做形状不做增益）；
 *  - **层族常量原值锚**：R281 双层原值（0.55+0.80 配比/0.90+1.10 半径基/
 *    ±0.35 深错位）直引零调——宪法诚实量级对照（r344 §五保留判词：雾的
 *    弥散语义一个不许伤）。
 */
class FogFlocFxTest {

    private val seeds = intArrayOf(0, 1, 7, 123, 99991, -48271, 11139760)

    // ==================== fBm 值噪声 ====================

    @Test
    fun `fBm 值噪声-确定性与值域（3 octave 等权和恒 1）`() {
        for (seed in seeds) {
            for (x in floatArrayOf(-2.3f, 0f, 0.7f, 2.1f, 5.5f, 13.7f)) {
                val v = FogFlocFx.fbm01(seed, x)
                assertTrue("fbm 值域 0 到 1：seed=$seed x=$x v=$v", v in 0f..1f)
                assertEquals(
                    "确定性同参同值（零 Random 铁律）：seed=$seed x=$x",
                    v,
                    FogFlocFx.fbm01(seed, x),
                    0f,
                )
            }
        }
        // 多 octave 结构在场：fbm ≠ 单阶 vnoise（高阶项非摆设——扰动是多八度噪声）
        var octGap = 0f
        for (seed in seeds) {
            for (x in floatArrayOf(0.3f, 1.7f, 4.2f, 8.9f)) {
                octGap = maxOf(
                    octGap,
                    abs(FogFlocFx.fbm01(seed, x) - FogFlocFx.vnoise01(seed, x)),
                )
            }
        }
        assertTrue("fbm 应含高阶 octave（≠单阶 vnoise）——gap=$octGap", octGap > 1e-4f)
        // 格点插值连续锚：相邻半格采样差不跳变（值噪声 smoothstep 内插）
        for (seed in seeds) {
            val a = FogFlocFx.vnoise01(seed, 3.45f)
            val b = FogFlocFx.vnoise01(seed, 3.55f)
            assertTrue("格点内插应连续：seed=$seed a=$a b=$b", abs(a - b) < 0.25f)
        }
    }

    // ==================== 雾絮种子（团位哈希折叠 Int——同雾同絮入口） ====================

    @Test
    fun `雾絮种子-团位哈希折叠 Int（同雾同絮 不同团不同絮）`() {
        for (seed in seeds) {
            val folded = FogFlocFx.flocSeedOf(seed.toFloat())
            // 确定性：同团位同折叠（零 Random 零系统钟——同雾同絮铁律入口）
            assertEquals("折叠确定性：seed=$seed", folded, FogFlocFx.flocSeedOf(seed.toFloat()))
            // 负折叠防御：blobCountOf 负余数归一在折叠负值上不炸（域测试已扫
            // -1000..1000，此处锚实折叠值再过一遍）
            assertTrue("blob 数域（负折叠种子同域）", FogFlocFx.blobCountOf(folded) in 2..3)
        }
        // 团位分辨力：不同团位折叠应大体错开（8191 折叠放大团位微差）
        assertTrue(
            "相邻团位折叠应错开（哈希族分辨力）",
            FogFlocFx.flocSeedOf(700.1f) != FogFlocFx.flocSeedOf(700.2f),
        )
    }

    // ==================== blob 数（2~3） ====================

    @Test
    fun `blob 数-域 2 到 3 与双档均达（含负种子余数归一）`() {
        var seen2 = false
        var seen3 = false
        for (seed in -1000..1000) {
            val n = FogFlocFx.blobCountOf(seed)
            assertTrue("blob 数域 2~3：seed=$seed n=$n", n in 2..3)
            if (n == 2) seen2 = true
            if (n == 3) seen3 = true
            assertEquals("确定性同参同值：seed=$seed", n, FogFlocFx.blobCountOf(seed))
        }
        assertTrue("2 档应可达（层族下限保义）", seen2)
        assertTrue("3 档应可达（絮化主档）", seen3)
        assertEquals("常量域下限", 2, FogFlocFx.BLOB_COUNT_MIN)
        assertEquals("常量域上限", 3, FogFlocFx.BLOB_COUNT_MAX)
    }

    // ==================== blob 半径系数（只缩不胀） ====================

    @Test
    fun `blob 半径系数-域 0_58 到 1 只缩不胀（fBm 扰动雾半径）`() {
        var varied = false
        for (seed in seeds) {
            for (k in 0 until FogFlocFx.BLOB_COUNT_MAX) {
                val r = FogFlocFx.blobRadius01(seed, k)
                assertTrue(
                    "半径系数域 0_58 到 1（外廓零外扩红线）：seed=$seed k=$k r=$r",
                    r in FogFlocFx.RADIUS_MIN..1f,
                )
                assertEquals("确定性：seed=$seed k=$k", r, FogFlocFx.blobRadius01(seed, k), 0f)
                if (k > 0 && abs(r - FogFlocFx.blobRadius01(seed, k - 1)) > 1e-4f) varied = true
            }
        }
        assertTrue("blob 间半径应有大小差（絮块错落非同尺复制）", varied)
    }

    // ==================== blob alpha 配比包（量级零增益） ====================

    @Test
    fun `blob alpha 配比包-和恒 1_35 零增益（fBm 扰动密度剖面）`() {
        val out = FloatArray(FogFlocFx.BLOB_COUNT_MAX)
        var perturbed = false
        for (seed in seeds) {
            for (n in 2..3) {
                FogFlocFx.blobAlphaPack01(seed, out, n)
                var sum = 0f
                for (k in 0 until n) {
                    assertTrue("配比应恒正：seed=$seed n=$n k=$k a=${out[k]}", out[k] > 0f)
                    sum += out[k]
                }
                assertEquals(
                    "配比和恒 ALPHA_PACK_SUM（量级零增益红线——絮化只做形状不做增益）：seed=$seed n=$n",
                    FogFlocFx.ALPHA_PACK_SUM,
                    sum,
                    1e-4f,
                )
                if (abs(out[0] - out[1]) > 1e-4f) perturbed = true
            }
        }
        assertTrue("配比应有 blob 间差（fBm 密度剖面扰动非均分）", perturbed)
        // 确定性：同雾同絮（两跑同值——同星同形铁律）
        FogFlocFx.blobAlphaPack01(4242, out, 3)
        val a0 = out.copyOf()
        FogFlocFx.blobAlphaPack01(4242, out, 3)
        assertTrue("两跑同值", a0.contentEquals(out))
    }

    // ==================== blob 错位（世界系真错位） ====================

    @Test
    fun `blob 错位-模长域与方向散布（弥散连体帽 0_45）`() {
        val out = FloatArray(3)
        val again = FloatArray(3)
        val prev = FloatArray(3)
        var misaligned = false
        for (seed in seeds) {
            for (k in 0 until FogFlocFx.BLOB_COUNT_MAX) {
                FogFlocFx.blobOffset01(seed, k, out)
                val mag = sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2])
                assertTrue(
                    "错位模长域（块间恒重叠=弥散连体不散架）：seed=$seed k=$k mag=$mag",
                    mag in FogFlocFx.OFFSET_MIN..FogFlocFx.OFFSET_MAX + 1e-4f,
                )
                FogFlocFx.blobOffset01(seed, k, again)
                assertTrue("确定性（同雾同错位）：seed=$seed k=$k", out.contentEquals(again))
                if (k > 0) {
                    FogFlocFx.blobOffset01(seed, k - 1, prev)
                    if (prev[0] != out[0] || prev[1] != out[1] || prev[2] != out[2]) {
                        misaligned = true
                    }
                }
            }
        }
        assertTrue("blob 间应有方向散布（真错位非同向复制——絮状观感来源）", misaligned)
    }

    // ==================== 层族常量原值锚（宪法诚实量级对照） ====================

    @Test
    fun `层族常量-R281 双层原值直引零调（配比 半径基 深错位）`() {
        assertEquals("前层配比原值", 0.55f, FogFlocFx.FRONT_ALPHA, 0f)
        assertEquals("后层配比合计原值", 0.80f, FogFlocFx.BACK_ALPHA_TOTAL, 0f)
        assertEquals("配比恒和原值 1_35（量级零增益锚）", 1.35f, FogFlocFx.ALPHA_PACK_SUM, 0f)
        assertEquals("前层半径基原值", 0.90f, FogFlocFx.FRONT_R, 0f)
        assertEquals("后层半径基原值", 1.10f, FogFlocFx.BACK_R, 0f)
        assertEquals("前后层深错位原值 ±0_35（双层视差层间距）", 0.35f, FogFlocFx.LAYER_DEPTH_OFF, 0f)
    }
}
