package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R339 恒星光两形态引擎锚（TasteStarGlow 纯件——r339 §二 参数族六组锚+r333
 * §2.6 保留族；R349 形态线重锚随行——crossfade 族/tierOf/变星半径项档全部
 * 委托 [TasteLodPolicy] 单源（r341 §2.3 收编协议：零第二套），锚翻正为
 * 「恒等+域界」双口径）：
 * ①桶位确定性+域 ②桶色族（点体位图烘焙取色单源——端桶色带/饱和护栏） ③**径向
 * 单调非增哨**（r333 §2.6 ③ 保留——「圆环」反例哨：空心 stroke 环会在峰值外
 * 违反=红） ④紧剖面形状域（大饱和核+陡降+快尾——「刺眼的点」） ⑤**宽晕退役
 * 反锚**（死亡值断言+r 大于 2r 处 alpha 恒 0 sweep+盘光结构帽——用户 20:0x 拍板
 * 「圆盘晕去掉」的量化护栏） ⑥**星芒形状锚**（top 门/四芒六芒/芒长∝亮度/α 快
 * 衰减/细度/眩光极紧 ≤2px） ⑦**crossfade 连续性锚**（R349 重锚+R355 dp 化窗
 * 1_2~1_6dp：边界/单调/能量守恒/核点膨胀半径连续/星芒让位/政策件委托恒等+
 * 阈值参透传跨密度恒等） ⑧尺寸门三态互斥完备
 * ⑨shimmer 域 ⑩变星半径项档（球门 1_6dp——R355 参透传） ⑪
 * 遗迹壳静态哨（物质壳——盘光帽域外件） ⑫预算帽（位图过渡账+星芒微成本账）
 * ⑬发现盛放。
 * （渲染端接线位——死亡层屏上退役依赖 drawHaloLayer 早退、星芒绘制/crossfade
 * 接线归 R339 二阶段落码：由 V38 重写单+源码反锚覆盖，本文件只锚纯件口径。）
 */
@Suppress("DEPRECATION") // 宽晕退役死亡值断言=对 Deprecated 参数的有意引用（死亡锚本体）
class TasteStarGlowTest {

    // ==================== ① 桶位确定性+域 ====================

    @Test
    fun `桶位-域界与边界桶钳`() {
        assertEquals(0, TasteStarGlow.bucketOfK(2400f))
        assertEquals(0, TasteStarGlow.bucketOfK(3599f))
        assertEquals(1, TasteStarGlow.bucketOfK(3600f))
        assertEquals(7, TasteStarGlow.bucketOfK(11800f))
        assertEquals(7, TasteStarGlow.bucketOfK(99999f)) // 域外钳上界
        assertEquals(0, TasteStarGlow.bucketOfK(-5f)) // 域外钳下界
        assertEquals(8, TasteStarGlow.K_BUCKETS)
    }

    @Test
    fun `桶位-K 单调分桶与全桶覆盖`() {
        var prev = -1
        val seen = mutableSetOf<Int>()
        for (k in 2400..11800 step 100) {
            val b = TasteStarGlow.bucketOfK(k.toFloat())
            assertTrue("桶号域 $b", b in 0 until TasteStarGlow.K_BUCKETS)
            assertTrue("桶号应随 K 非降", b >= prev)
            seen.add(b)
            prev = b
        }
        assertEquals((0 until TasteStarGlow.K_BUCKETS).toSet(), seen)
        // 桶中点温度回投同桶（确定性——烘焙与绘制同桶）
        for (b in 0 until TasteStarGlow.K_BUCKETS) {
            assertEquals(b, TasteStarGlow.bucketOfK(TasteStarGlow.anchorKelvinOf(b)))
        }
    }

    // ==================== ② 桶色族（点体位图取色单源） ====================

    @Test
    fun `兜底桶-旧三档映射与身份隔离`() {
        assertEquals(TasteStarGlow.BUCKET_WARM, TasteStarGlow.legacyBucketOf(TasteStarGlow.TIER_WARM))
        // 爆发蓝白档=cool 兜底桶（r333 §2.3-1 口径）
        assertEquals(TasteStarGlow.BUCKET_COOL, TasteStarGlow.legacyBucketOf(TasteStarGlow.TIER_COOL))
        assertEquals(TasteStarGlow.BUCKET_WHITE, TasteStarGlow.legacyBucketOf(TasteStarGlow.TIER_WHITE))
        // 兜底桶 id 与 K 桶零重叠（身份隔离——特殊 kind 不进色温轴）
        for (b in listOf(TasteStarGlow.BUCKET_WARM, TasteStarGlow.BUCKET_COOL, TasteStarGlow.BUCKET_WHITE)) {
            assertTrue("兜底桶 $b 应在 K 桶域外", b >= TasteStarGlow.K_BUCKETS)
        }
        assertEquals(11, TasteStarGlow.BUCKET_COUNT)
    }

    @Test
    fun `桶色-三段色在域与锚温度域`() {
        for (b in 0 until TasteStarGlow.BUCKET_COUNT) {
            val k = TasteStarGlow.anchorKelvinOf(b)
            assertTrue("桶 $b 锚温度 $k 应在黑体谱域", k in 2400f..11800f)
            assertEquals(0xFF, TasteStarGlow.coreColorOfBucket(b) ushr 24)
            assertEquals(0xFF, TasteStarGlow.haloColorOfBucket(b) ushr 24)
            assertEquals(0xFF, TasteStarGlow.tailColorOfBucket(b) ushr 24)
        }
    }

    @Test
    fun `桶色-旧档兜底色族守卫`() {
        val warmH = TasteStarColor.hueOfArgb(TasteStarGlow.haloColorOfBucket(TasteStarGlow.BUCKET_WARM))
        val coolH = TasteStarColor.hueOfArgb(TasteStarGlow.haloColorOfBucket(TasteStarGlow.BUCKET_COOL))
        assertTrue("暖兜底桶应在暖橙带 实测 $warmH", warmH in 15f..60f)
        assertTrue("冷兜底桶应在蓝白带 实测 $coolH", coolH in 180f..250f)
    }

    @Test
    fun `桶色-晕段吃星色_端桶色带与饱和护栏`() {
        // 点体色保真（V38-② 色保真腿的单源预锁）：「灰」=饱和度崩塌——晕段 S
        // 恒 ≥0.15（softGrade 下限），白热核段 S 恒 ≤0.12（设计口径：真实摄影里
        // 核趋白——核段色相无意义不进色相断言，屏上取证位=超新星蓝白档与红
        // 巨星邻域暖橙档两个饱和端）；两端桶色带=取证位的单源预锁
        for (b in 0 until TasteStarGlow.BUCKET_COUNT) {
            val haloS = satOf(TasteStarGlow.haloColorOfBucket(b))
            val coreS = satOf(TasteStarGlow.coreColorOfBucket(b))
            assertTrue("桶 $b 晕段饱和度 $haloS 应不塌（≥0.15）", haloS >= 0.15f)
            assertTrue("桶 $b 白热核段饱和度 $coreS 应在钳内（≤0.13）", coreS <= 0.13f)
            // V38 ②量化：晕段饱和度 ≥核段 0_6 倍（一票否决项的单源预锁）
            assertTrue("桶 $b 晕段饱和度应 ≥核段 0_6 倍", haloS >= coreS * 0.6f)
        }
        // 端桶色带（取证位锚）：暖端橙带/冷端蓝白带——K 桶与旧档兜底同族
        val warmH = TasteStarColor.hueOfArgb(TasteStarGlow.haloColorOfBucket(0))
        val coolH = TasteStarColor.hueOfArgb(TasteStarGlow.haloColorOfBucket(7))
        val legacyWarmH = TasteStarColor.hueOfArgb(TasteStarGlow.haloColorOfBucket(TasteStarGlow.BUCKET_WARM))
        val legacyCoolH = TasteStarColor.hueOfArgb(TasteStarGlow.haloColorOfBucket(TasteStarGlow.BUCKET_COOL))
        assertTrue("暖端桶应在暖橙带 实测 $warmH", warmH in 15f..60f)
        assertTrue("冷端桶应在蓝白带 实测 $coolH", coolH in 180f..250f)
        assertTrue("暖兜底桶应在暖橙带 实测 $legacyWarmH", legacyWarmH in 15f..60f)
        assertTrue("冷兜底桶应在蓝白带 实测 $legacyCoolH", legacyCoolH in 180f..250f)
    }

    private fun satOf(argb: Int): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val max = maxOf(r, g, b).toFloat()
        val min = minOf(r, g, b).toFloat()
        return if (max <= 0f) 0f else (max - min) / max
    }

    // ==================== ③ 径向单调非增哨（「圆环」反例哨——R339 保留） ====================

    @Test
    fun `PSF-径向单调非增哨`() {
        var prev = 1f
        for (i in 0..400) {
            val d = i / 400f
            val a = TasteStarGlow.psfAlphaOf(d)
            assertTrue("d=$d 处 alpha=$a 高于前值 $prev（空心环=反例红）", a <= prev + 1e-6f)
            prev = a
        }
        assertEquals(1f, TasteStarGlow.psfAlphaOf(0f), 1e-6f)
        assertEquals(0f, TasteStarGlow.psfAlphaOf(1f), 1e-6f)
        assertEquals(0f, TasteStarGlow.psfAlphaOf(0.87f), 1e-4f) // 快尾 0_86 截止外
        // 域外钳
        assertEquals(1f, TasteStarGlow.psfAlphaOf(-1f), 1e-6f)
        assertEquals(0f, TasteStarGlow.psfAlphaOf(2f), 1e-6f)
    }

    @Test
    fun `PSF-剖面形状域_大饱和核-陡降-快尾`() {
        assertEquals(1f, TasteStarGlow.psfAlphaOf(0.26f), 1e-6f) // 核平台满档（0_26 饱和核）
        assertTrue("离平台即入陡降", TasteStarGlow.psfAlphaOf(0.27f) < 1f)
        assertTrue("陡降段快速跌落", TasteStarGlow.psfAlphaOf(0.35f) < 0.75f)
        val mid = TasteStarGlow.psfAlphaOf(0.55f)
        assertTrue("陡降段终点应约 0_30 实测 $mid", mid in 0.26f..0.34f)
        assertTrue("快尾段继续降", TasteStarGlow.psfAlphaOf(0.70f) < mid)
        assertTrue("快尾段截止前仍大于零", TasteStarGlow.psfAlphaOf(0.85f) > 0f)
        // 紧刺曲线的「快尾」硬截止：0_86 归零（R334 长尾 0_98→R339 0_86——尾短 0_12）
        assertEquals(0f, TasteStarGlow.psfAlphaOf(0.86f), 1e-6f)
        assertEquals(0f, TasteStarGlow.psfAlphaOf(0.90f), 1e-6f)
    }

    @Test
    fun `PSF-像素口径 alpha 通道与剖面一致`() {
        assertEquals(255, (TasteStarGlow.psfArgbOf(3, 0f) ushr 24) and 0xFF)
        assertEquals(0, TasteStarGlow.psfArgbOf(3, 1f))
        assertEquals(0, TasteStarGlow.psfArgbOf(3, 0.87f))
        var prevA = 255
        for (i in 0..100) {
            val a = (TasteStarGlow.psfArgbOf(5, i / 100f) ushr 24) and 0xFF
            assertTrue("像素 alpha 应随径向非增", a <= prevA)
            prevA = a
        }
        // 色非零（同桶三段色烘焙到位——非灰位图）
        for (b in 0 until TasteStarGlow.BUCKET_COUNT) {
            val c = TasteStarGlow.psfArgbOf(b, 0.5f)
            assertTrue("桶 $b 像素色应非零", (c and 0xFFFFFF) != 0)
        }
    }

    // ==================== ④ 宽晕退役反锚（用户 20:0x 拍板「圆盘晕去掉」的量化护栏） ====================

    @Test
    fun `宽晕退役-死亡值断言`() {
        // 三死亡层 alpha 恒 0（L2 内晕 2_2r/远星微晕 1_6r/球缘光渗 1_35r——复活即红）
        assertEquals("L2 内晕死亡值", 0f, TasteStarGlow.L2_A, 1e-6f)
        assertEquals("远星微晕死亡值", 0f, TasteStarGlow.FAR_A, 1e-6f)
        assertEquals("球缘光渗死亡值", 0f, TasteStarGlow.CLOSEUP_A, 1e-6f)
        // 死亡公式：任何星亮/盛放组合下球缘光渗恒 0
        assertEquals(0f, TasteStarGlow.closeupHaloAlpha(1f, 1.4f), 1e-6f)
        assertEquals(0f, TasteStarGlow.closeupHaloAlpha(3f, 3f), 1e-6f)
        assertEquals(0f, TasteStarGlow.closeupHaloAlpha(0.5f, 1f), 1e-6f)
        // 物理删除族不再有 API（编译期保证——L3_R/L3_A/outerHaloAlpha/OUTER_ALPHA_MAX/
        // TOTAL_R_MAX 已删；本断言块=死亡值面的运行时哨）
    }

    @Test
    fun `宽晕退役-反锚_r 大于 2r 处 alpha 恒 0`() {
        // 锚函数 lightAlphaOf=全盘光层族按活值入算的包络：d>2r 恒 0（径向 sweep）
        for (r in listOf(2.2f, 3.5f, 5f, 12f, 18f, 24f, 27f, 30f, 45f, 300f)) {
            // 非空转哨：星心必须真有光（防全零函数假绿）
            assertTrue("r=$r 星心盘光非零（非空转）", TasteStarGlow.lightAlphaOf(r, 0f) > 0.5f)
            var d = 2f * r + 0.05f
            while (d < 6f * r) {
                assertEquals("r=$r d=$d（大于2r）盘光应恒 0", 0f, TasteStarGlow.lightAlphaOf(r, d), 1e-9f)
                d += r / 8f
            }
        }
        // 边界内对照：点体在自盘内有光（1r 内核区）
        assertTrue(TasteStarGlow.lightAlphaOf(12f, 3f) > 0.5f)
        // 死亡层复活即破锚的机理面：锚函数对死亡层取活值（本断言族在复活时红）
    }

    @Test
    fun `宽晕退役-盘光结构帽`() {
        assertEquals(2f, TasteStarGlow.LIGHT_EXTENT_MAX_R, 1e-6f) // 「r大于2r」帽本体
        assertTrue("L1 点体半径系数应在帽内", TasteStarGlow.L1_R <= TasteStarGlow.LIGHT_EXTENT_MAX_R)
        assertEquals("L1 点体=星本体盘（1_0r 非外晕）", 1.0f, TasteStarGlow.L1_R, 1e-6f)
        assertTrue("眩光极紧帽（≤2px 字面）", TasteStarGlow.GLARE_R_PX <= 2f)
        // 点体 alpha 域（星本体亮度档——非晕层）
        assertTrue(TasteStarGlow.L1_A in 0.30f..1f)
    }

    // ==================== ⑤ 星芒形状锚（top ~5 最亮=衍射星芒——远观档） ====================

    @Test
    fun `星芒-门与芒数`() {
        assertEquals("top 门=最亮少数（~5 字面）", 5, TasteStarGlow.SPIKE_MAX_STARS)
        assertTrue(TasteStarGlow.SPIKE_MAX_STARS in 3..8) // 「最亮少数」域：一撮非一群
        assertEquals("四芒细十字主档", 4, TasteStarGlow.SPIKE_COUNT)
        assertEquals("六芒富档（四芒或六芒双口径）", 6, TasteStarGlow.SPIKE_COUNT_RICH)
        assertTrue(TasteStarGlow.SPIKE_COUNT_RICH > TasteStarGlow.SPIKE_COUNT)
        assertTrue(TasteStarGlow.SPIKE_COUNT_RICH <= 8)
    }

    @Test
    fun `星芒-芒长∝亮度线性域`() {
        val min = TasteStarGlow.SPIKE_LEN_MIN_PX
        val max = TasteStarGlow.SPIKE_LEN_MAX_PX
        assertTrue("芒长域应为正（10~26px 首版档）", min in 6f..14f && max in 20f..32f && max > min)
        assertEquals(min, TasteStarGlow.spikeLenPx(0f), 1e-6f)
        assertEquals(max, TasteStarGlow.spikeLenPx(1f), 1e-6f)
        assertEquals("线性中点（∝亮度的字面锚）", (min + max) / 2f, TasteStarGlow.spikeLenPx(0.5f), 1e-4f)
        // 线性增量：亮度等差=芒长等差（浮点容差 1e-4——常数线性公式的舍入位）
        val d1 = TasteStarGlow.spikeLenPx(0.3f) - TasteStarGlow.spikeLenPx(0.2f)
        val d2 = TasteStarGlow.spikeLenPx(0.8f) - TasteStarGlow.spikeLenPx(0.7f)
        assertEquals(d1, d2, 1e-4f)
        // 域外钳
        assertEquals(min, TasteStarGlow.spikeLenPx(-1f), 1e-6f)
        assertEquals(max, TasteStarGlow.spikeLenPx(9f), 1e-6f)
        // 单调：亮度升芒长增
        var prev = 0f
        for (i in 0..20) {
            val len = TasteStarGlow.spikeLenPx(i / 20f)
            assertTrue(len >= prev - 1e-6f)
            prev = len
        }
    }

    @Test
    fun `星芒-α 快衰减剖面`() {
        assertEquals(TasteStarGlow.SPIKE_A, TasteStarGlow.spikeAlpha01(0f), 1e-6f)
        assertEquals(0f, TasteStarGlow.spikeAlpha01(1f), 1e-6f)
        assertEquals(0f, TasteStarGlow.spikeAlpha01(1.5f), 1e-6f) // 域外钳
        // 「快衰减」量化：半芒即近灭（0_75×0_5³=0_094——「刺」不是「棒」）
        assertTrue(
            "半芒 alpha=${TasteStarGlow.spikeAlpha01(0.5f)} 应≤0_11",
            TasteStarGlow.spikeAlpha01(0.5f) <= 0.11f,
        )
        assertTrue("衰减幂应≥2_5（快衰减域锚）", TasteStarGlow.SPIKE_FALLOFF_POWER >= 2.5f)
        assertTrue(TasteStarGlow.SPIKE_A in 0.5f..0.95f) // 芒根亮度的域
        // 单调非增
        var prev = TasteStarGlow.spikeAlpha01(0f)
        for (i in 1..100) {
            val a = TasteStarGlow.spikeAlpha01(i / 100f)
            assertTrue("u=${i / 100f} 芒 alpha=$a 高于前值 $prev", a <= prev + 1e-6f)
            prev = a
        }
    }

    @Test
    fun `星芒-细度与眩光极紧`() {
        // 细光刺：恒宽 1_2px（不随 r 膨胀成「光柱」）
        assertTrue(
            "芒宽=${TasteStarGlow.SPIKE_WIDTH_PX} 应在细档（0_8~1_5px）",
            TasteStarGlow.SPIKE_WIDTH_PX in 0.8f..1.5f,
        )
        // 眩光：≤2px 极紧（内 1px 平台满档→2px 硬归零）
        assertEquals(2f, TasteStarGlow.GLARE_R_PX, 1e-6f)
        assertEquals(TasteStarGlow.GLARE_A, TasteStarGlow.glareAlphaOf(0f), 1e-6f)
        assertEquals(TasteStarGlow.GLARE_A, TasteStarGlow.glareAlphaOf(1f), 1e-6f) // 平台内满档
        assertTrue(TasteStarGlow.glareAlphaOf(1.5f) < TasteStarGlow.GLARE_A)
        assertTrue(TasteStarGlow.glareAlphaOf(1.5f) > 0f)
        assertEquals(0f, TasteStarGlow.glareAlphaOf(2f), 1e-6f)
        assertEquals(0f, TasteStarGlow.glareAlphaOf(2.5f), 1e-6f)
        assertEquals(TasteStarGlow.GLARE_A, TasteStarGlow.glareAlphaOf(-1f), 1e-6f) // 负距钳中心=满档
        assertTrue(TasteStarGlow.GLARE_A >= 0.9f) // 白热饱和核亮度档
        // 眩光径向单调非增（径向单调哨在眩光剖面上的同款锚）
        var prev = TasteStarGlow.glareAlphaOf(0f)
        for (i in 1..40) {
            val a = TasteStarGlow.glareAlphaOf(i / 20f)
            assertTrue("d=${i / 20f}px 眩光 alpha=$a 高于前值 $prev", a <= prev + 1e-6f)
            prev = a
        }
    }

    // ==================== ⑥ crossfade 连续性锚（R349 重锚+R355 dp 化——政策件委托单源） ====================

    @Test
    fun `crossfade-边界值与单调（R355 窗 1_2 到 1_6 mdpi 档）`() {
        // 窗外恒 0/1（点档/3D 档两形态零污染）；缺省参=mdpi 恒等档（density=1
        // 时 dp≡px——R355 JVM 基准）
        assertEquals(0f, TasteStarGlow.crossfade01(0f), 1e-6f)
        assertEquals(0f, TasteStarGlow.crossfade01(1.1f), 1e-6f)
        assertEquals(0f, TasteStarGlow.crossfade01(1.2f), 1e-6f) // 窗起点=0（点形态满档）
        assertTrue(TasteStarGlow.crossfade01(1.21f) > 0f)
        assertTrue(TasteStarGlow.crossfade01(1.59f) < 1f)
        assertEquals(1f, TasteStarGlow.crossfade01(1.6f), 1e-6f) // 窗终点=1（3D 形态满档）
        assertEquals(1f, TasteStarGlow.crossfade01(30f), 1e-6f)
        assertEquals(1f, TasteStarGlow.crossfade01(300f), 1e-6f)
        assertEquals(0f, TasteStarGlow.crossfade01(-5f), 1e-6f) // 域外钳=点形态（0）
        // smoothstep 对称中点=0_5（带中点 1_4）
        assertEquals(0.5f, TasteStarGlow.crossfade01(1.4f), 1e-4f)
        // 单调非降（连续过渡无回摆）
        var prev = 0f
        for (i in 0..600) {
            val k = TasteStarGlow.crossfade01(i / 10f)
            assertTrue("r=${i / 10f} crossfade=$k 低于前值 $prev（回摆=红）", k >= prev - 1e-6f)
            prev = k
        }
        assertEquals(1f, prev, 1e-6f)
        // R349 零第二套委托锚：全函数域与政策件恒等（收编协议——r341 §2.3；
        // R355：阈值参透传恒等——帧字段口径同源）
        for (i in 0..600) {
            val r = i / 10f
            assertEquals("r=$r crossfade01 应恒等政策件 blend01", TasteLodPolicy.blend01(r), TasteStarGlow.crossfade01(r), 1e-9f)
        }
        // R355 阈值参透传（400dpi 档：窗 [3,4)px——同 dp 位置 k 恒等）
        assertEquals(
            TasteStarGlow.crossfade01(1.4f),
            TasteStarGlow.crossfade01(1.4f * 2.5f, 1.6f * 2.5f, 1.2f * 2.5f),
            1e-5f,
        )
    }

    @Test
    fun `crossfade-形态能量守恒（点淡出球淡入亮度连续）`() {
        for (i in 0..240) {
            val r = i / 4f
            val point = TasteStarGlow.pointFormAlpha(r)
            val ball = TasteStarGlow.ballFormAlpha(r)
            assertTrue("r=$r 点形态因子域", point in 0f..1f)
            assertTrue("r=$r 球形态因子域", ball in 0f..1f)
            assertEquals("r=$r 双形态因子和应恒 1（能量守恒——无亮峰无凹陷）", 1f, point + ball, 1e-6f)
        }
        // 两端满档互斥（resolved 无芒无闪的因子口径——r341 §5.2）
        assertEquals(1f, TasteStarGlow.pointFormAlpha(1.1f), 1e-6f)
        assertEquals(0f, TasteStarGlow.ballFormAlpha(1.1f), 1e-6f)
        assertEquals(0f, TasteStarGlow.pointFormAlpha(1.6f), 1e-6f)
        assertEquals(1f, TasteStarGlow.ballFormAlpha(1.6f), 1e-6f)
        // pointFadeK 同义单源锚（r341 §对表协议：pointFadeK≡pointFormAlpha 已单源）
        for (i in 0..200) {
            val r = i / 50f
            assertEquals("r=$r pointFormAlpha≡pointFadeK", TasteLodPolicy.pointFadeK(r), TasteStarGlow.pointFormAlpha(r), 1e-9f)
        }
    }

    @Test
    fun `crossfade-核点膨胀半径连续（核点膨胀成球）`() {
        // 点形态核=紧刺核平台 0_26（与 PSF 核平台同值——点核即饱和核）
        assertEquals(0.26f, TasteStarGlow.coreSwellCoef(1.1f), 1e-6f)
        assertEquals(0.26f, TasteStarGlow.coreSwellCoef(1.2f), 1e-6f)
        // 窗起点连续（smoothstep 零斜率起步——无跳变；R355 窗宽 0_4dp 压缩，
        // 步进随缩同比例 0_05→0_004px=窗宽 1%——C¹ 起步斜率零锚）
        assertTrue("|核膨胀窗起点跳变| 应 <0_01", kotlin.math.abs(TasteStarGlow.coreSwellCoef(1.204f) - 0.26f) < 0.01f)
        // 球满档核=整盘 1_0——「半径连续过渡」锚：核半径 r×1_0 与 SPHERE 球半径 r 衔接
        assertEquals(1f, TasteStarGlow.coreSwellCoef(1.6f), 1e-6f)
        assertTrue(TasteStarGlow.coreSwellCoef(1.59f) < 1f)
        assertTrue(TasteStarGlow.coreSwellCoef(1.59f) > 0.26f)
        // 单调非降（膨胀不回缩）
        var prev = 0f
        for (i in 0..600) {
            val c = TasteStarGlow.coreSwellCoef(i / 10f)
            assertTrue("r=${i / 10f} 核膨胀系数 $c 低于前值 $prev（回缩=红）", c >= prev - 1e-6f)
            prev = c
        }
    }

    @Test
    fun `crossfade-星芒随点形态让位（远观档专属件）`() {
        // 星芒消费 pointFadeK（≡pointFormAlpha）：点档恒 1=在场；3D 档 0=退场
        // （芒不骑在球上——resolved 无芒）
        assertEquals(1f, TasteStarGlow.pointFormAlpha(1.1f), 1e-6f)
        assertEquals(1f, TasteStarGlow.pointFormAlpha(1.2f), 1e-6f) // 窗起点=点满档（k=0 连续）
        assertTrue(TasteStarGlow.pointFormAlpha(1.4f) < 1f) // 带内渐隐
        assertEquals(0f, TasteStarGlow.pointFormAlpha(1.6f), 1e-6f)
        assertEquals(0f, TasteStarGlow.pointFormAlpha(20f), 1e-6f)
    }

    // ==================== ⑦ 尺寸门（R349 三态互斥完备——政策件 formTierOf 委托） ====================

    @Test
    fun `尺寸门-三态互斥完备含边界（点_带_3D）`() {
        for (r in listOf(-1f, 0f, 1f, 1.19f)) assertEquals(0, TasteStarGlow.tierOf(r))
        for (r in listOf(1.2f, 1.4f, 1.59f)) assertEquals(1, TasteStarGlow.tierOf(r))
        for (r in listOf(1.6f, 8f, 30f, 300f)) assertEquals(2, TasteStarGlow.tierOf(r))
        // 互斥完备：任意 r 恰落一档（扫全域无空洞无重叠）+档间严格递进
        var last = -1
        for (i in 0..600) {
            val tier = TasteStarGlow.tierOf(i / 10f)
            assertTrue(tier in 0..2)
            if (tier != last) {
                assertEquals("档间应严格递进（无重叠）", last + 1, tier)
                last = tier
            }
        }
        assertEquals(2, last)
        // 委托恒等锚（政策件单源——tierOf 只是 R339 兼容读出口径）
        for (i in 0..600) {
            val r = i / 10f
            assertEquals("r=$r tierOf 应恒等政策件 formTierOf", TasteLodPolicy.formTierOf(r), TasteStarGlow.tierOf(r))
        }
    }

    @Test
    fun `尺寸门-档域界锚（政策件字面——微星线与 3D 线单源）`() {
        // R349 域界随常量退役收口政策件（MICRO_TIER_MAX_PX 2.2→POINT_CEIL 1.2dp；
        // CLOSEUP_TIER_MIN_PX 30→SOLID_FLOOR 1.6dp——「同值同改」镜像族绝迹；
        // R355 dp 化——帧字段口径单源）
        assertEquals(1.2f, TasteLodPolicy.POINT_CEIL_DP, 1e-6f)
        assertEquals(1.6f, TasteLodPolicy.SOLID_FLOOR_DP, 1e-6f)
        assertEquals(4.0f, TasteLodPolicy.LOWMESH_MAX_DP, 1e-6f)
    }

    // ==================== ⑧ shimmer 域（±8% 纯 alpha——零半径项） ====================

    @Test
    fun `shimmer-幅度域锚与零半径耦合守卫`() {
        assertEquals(0.08f, TasteStarGlow.SHIMMER_AMP, 1e-6f)
        assertTrue(1f - TasteStarGlow.SHIMMER_AMP > 0f) // tw 下沿 0_92 为正
        // 纯亮度铁律的结构守卫：唯一的半径调制系数定义在球档且点档恒 0
        // （R349 球门/R355 dp 化 1_6dp——点档 r=1_5 恒零）
        assertEquals(0f, TasteStarGlow.cepheidRadiusCoef(1.5f))
    }

    // ==================== ⑨ 变星半径项档（R349：r 小于 SOLID_FLOOR 恒 0_球档 0_10；R355 球门 1_6dp） ====================

    @Test
    fun `变星-半径项档_r 小于 1_6 恒 0_球档 0_10`() {
        for (r in listOf(-1f, 0f, 1.1f, 1.59f)) {
            assertEquals("r=$r 点档应零半径项", 0f, TasteStarGlow.cepheidRadiusCoef(r), 1e-6f)
        }
        for (r in listOf(1.6f, 2.5f, 8f, 30f)) {
            assertEquals("r=$r 球档系数", 0.10f, TasteStarGlow.cepheidRadiusCoef(r), 1e-6f)
        }
        // R355 球门参透传（400dpi 档：1_4dp×2_5=3_5px < 4px 球门=零半径项）
        assertEquals(0f, TasteStarGlow.cepheidRadiusCoef(1.4f * 2.5f, 1.6f * 2.5f), 1e-6f)
        assertEquals(0.10f, TasteStarGlow.cepheidRadiusCoef(2f * 2.5f, 1.6f * 2.5f), 1e-6f)
    }

    @Test
    fun `变星-点档纯亮度调制（半径因子恒 1）`() {
        // amp 判据域 0_10~0_24（TasteStarColor 产出）× 波形极值下：r<球门 半径因子恒 1
        // （径向搏动只在看得见球时成立——球门随形态线换轨 R355 dp 化）
        for (amp in listOf(0.10f, 0.24f)) {
            for (r in listOf(0.8f, 1.2f, 1.5f)) {
                val radiusFactor = 1f + TasteStarGlow.cepheidRadiusCoef(r) * amp * 1f
                assertEquals("r=$r amp=$amp 半径因子", 1f, radiusFactor, 1e-6f)
            }
        }
    }

    // ==================== ⑩ 遗迹壳静态哨（物质壳——盘光帽域外件） ====================

    @Test
    fun `遗迹壳-静态哨_半径恒定与漂移率不超 0_4 度每秒`() {
        // 半径零 t 依赖（半径随 t 增=「缓慢扩散」反例=红——r333 根因第三环的哨）。
        // R339 注记：遗迹壳=物质壳（被抛出的遗骸）非盘光——不受 LIGHT_EXTENT_MAX_R
        // 盘光帽约束（类 KDoc 边界：防误伤 R317b 静态壳资产）
        for (seed in listOf(1, 42, 999, 12345)) {
            val ringR = 10f * TasteStarGlow.SUPERNOVA_SHELL_R
            for (k in 0 until 4) {
                val r1 = ringR * TasteTerminalFx.remnantArcRCoef(seed, k)
                val r2 = ringR * TasteTerminalFx.remnantArcRCoef(seed, k)
                assertEquals(r1, r2, 1e-6f)
                assertTrue("弧半径系数域", r1 >= ringR * 0.79f && r1 <= ringR * 1.13f)
            }
        }
        // 相位漂移率=0.4°/s（冻结缓漂——非旧超新星 ±3°/s 爆发旋转）
        for (seed in listOf(7, 100, 2024)) {
            for (k in 0 until 3) {
                val d0 = TasteTerminalFx.remnantArcBaseDeg(seed, k, 0f)
                val d1 = TasteTerminalFx.remnantArcBaseDeg(seed, k, 10f)
                assertEquals("seed=$seed k=$k 十秒漂移", 4f, d1 - d0, 1e-3f)
            }
        }
        // 壳 alpha 恒域（不做衰减包络）
        assertEquals(0.30f, TasteStarGlow.SHELL_ALPHA, 1e-6f)
        // 壳径在 pass1 剔除外延（SUPERNOVA extent=r×3_0）内——不冒屏缘
        assertTrue(TasteStarGlow.SUPERNOVA_SHELL_R * 1.12f <= 3.0f)
    }

    // ==================== ⑪ 预算帽（位图过渡账+星芒微成本账） ====================

    @Test
    fun `预算帽-位图过渡账与星芒微成本账`() {
        // 位图过渡账（R334 遗产：点体 L1+R321 星风泡共用——二阶段随位图三档绘制退役）
        assertEquals(64, TasteStarGlow.HALO_MAX_STARS)
        assertEquals(2, TasteStarGlow.BAKE_PER_FRAME_MAX)
        assertEquals(12, TasteStarGlow.HALO_CACHE_MAX)
        assertEquals(64, TasteStarGlow.PSF_SIZE_PX)
        assertEquals(192 * 1024, TasteStarGlow.HALO_CACHE_MAX * TasteStarGlow.PSF_SIZE_PX * TasteStarGlow.PSF_SIZE_PX * 4)
        assertTrue("缓存帽应覆盖全部桶域", TasteStarGlow.HALO_CACHE_MAX >= TasteStarGlow.BUCKET_COUNT)
        // 星芒微成本账（新预算：top 门 5 星×六芒富档上限——「全景比 64 张位图还省」
        // 的字面锚：星芒线段预算结构性低于位图 drawBitmap 预算）
        assertTrue(
            "星芒线段预算（${TasteStarGlow.SPIKE_MAX_STARS}×${TasteStarGlow.SPIKE_COUNT_RICH}）应低于位图帽 64",
            TasteStarGlow.SPIKE_MAX_STARS * TasteStarGlow.SPIKE_COUNT_RICH < TasteStarGlow.HALO_MAX_STARS,
        )
    }

    // ==================== ⑫ 发现盛放（亮度语义——二阶段挂账重语义） ====================

    @Test
    fun `发现盛放-峰值回落窗与单调回落`() {
        assertEquals(TasteStarGlow.REVEAL_HALO_K, TasteStarGlow.revealHaloK(0f), 1e-6f)
        assertEquals(1f, TasteStarGlow.revealHaloK(TasteStarGlow.REVEAL_HALO_SEC), 1e-6f)
        assertEquals(1f, TasteStarGlow.revealHaloK(10f), 1e-6f)
        assertEquals(1f, TasteStarGlow.revealHaloK(-1f), 1e-6f)
        var prev = TasteStarGlow.revealHaloK(0f)
        for (i in 1..17) {
            val k = TasteStarGlow.revealHaloK(i * 0.05f)
            assertTrue("盛放因子应单调回落", k <= prev + 1e-6f)
            prev = k
        }
        assertTrue("盛放峰值域 1_2 到 1_6", TasteStarGlow.REVEAL_HALO_K in 1.2f..1.6f)
        assertTrue(TasteStarGlow.REVEAL_HALO_SEC in 0.5f..1.2f) // 850ms 档
    }
}
