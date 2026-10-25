package dev.tgfeed.core.tastemap

import dev.tgfeed.ui.gl.GlTextureBaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * R319 宇宙底幕单源纯件单测（r315 A⑤/C⑤ + r319 设计⑥锚表）：
 *  - **温度档**：temp01Of 单调（光标早=宇宙热）/延时摄影关=0 恒定底色（诚实缺省）/
 *    kelvin 域（3200K→2000K 线性）/alphaOf 钳域（常态 0.02~0.04、剧场 0.02~0.12
 *    ——克制帽防抢戏）；
 *  - **底纹噪声**：noise01At 确定性（同种子同值）+值域 [0,1]+种子敏感；
 *  - **奥伯斯差集**：olbersCandidatesOf 池内排除/排序（金矿 items 降序+chatId
 *    升序破平）/帽 120 截断记账/空池全量/items=0 过滤；
 *  - **布点**：dotRadius01 半径域 [1.0,1.15]+确定性；dotDir 单位向量+确定性；
 *  - **zone 装配**：olbersZoneOf 同差集同布点（确定性）/rObs 缺省空带；
 *  - **彗星联动**：dotAlphaGlow 域（彗星不在场=原档；域内线性增亮；帽）；
 *  - **烘焙单源**：[GlTextureBaker.bakeCmb] 逐位确定性（同种子同纹理——Canvas/GL
 *    双侧消费同一 IntArray 的机制前提；跨层引用 ui.gl=单源配对校验特意为之）。
 */
class CmbBackdropFxTest {

    // ==================== CMB 温度档（A 章①——光标=温度唯一数据源） ====================

    @Test
    fun `温度档-延时摄影关为恒定底色诚实缺省`() {
        // 延时摄影关/量程无效=0 恒定底色（不编造温度——A⑤ 单测锚）
        for (t in listOf(-0.5f, 0f, 0.3f, 1f, 1.5f)) {
            assertEquals(0f, CmbBackdropFx.temp01Of(false, t), 0f)
        }
    }

    @Test
    fun `温度档-光标单调与钳域`() {
        // 光标 0=大爆炸端（最热=1）；1=现在端（最冷=0）；越界钳域
        assertEquals(1f, CmbBackdropFx.temp01Of(true, 0f), 1e-4f)
        assertEquals(0f, CmbBackdropFx.temp01Of(true, 1f), 1e-4f)
        assertEquals(1f, CmbBackdropFx.temp01Of(true, -0.5f), 1e-4f)
        assertEquals(0f, CmbBackdropFx.temp01Of(true, 1.5f), 1e-4f)
        // 单调：光标越早（小）温度越高（回望=看更热的时代）
        var prev = 2f
        var t = 0f
        while (t <= 1f) {
            val now = CmbBackdropFx.temp01Of(true, t)
            assertTrue("温度应随光标推进单调不增 t=$t", now <= prev + 1e-6f)
            prev = now
            t += 0.1f
        }
    }

    @Test
    fun `kelvin域-端点与单调`() {
        assertEquals(CmbBackdropFx.TEMP_K_NOW, CmbBackdropFx.kelvinOf(0f), 1e-3f)
        assertEquals(CmbBackdropFx.TEMP_K_BANG, CmbBackdropFx.kelvinOf(1f), 1e-3f)
        assertEquals(CmbBackdropFx.TEMP_K_NOW, CmbBackdropFx.kelvinOf(-1f), 1e-3f)
        assertEquals(CmbBackdropFx.TEMP_K_BANG, CmbBackdropFx.kelvinOf(2f), 1e-3f)
        // 回望越远（temp01 大）色温越低（暖黑→暗橙红——早期宇宙更热更亮的视感）
        var prev = 9999f
        var t = 0f
        while (t <= 1f) {
            val k = CmbBackdropFx.kelvinOf(t)
            assertTrue(k in CmbBackdropFx.TEMP_K_BANG..CmbBackdropFx.TEMP_K_NOW)
            assertTrue("kelvin 应随 temp01 单调不增 t=$t", k <= prev + 1e-3f)
            prev = k
            t += 0.1f
        }
    }

    @Test
    fun `alpha钳域-常态帽与剧场帽`() {
        for (i in 0..10) {
            val t = i / 10f
            val a = CmbBackdropFx.alphaOf(t, false)
            assertTrue("常态 alpha 越下限 t=$t", a >= CmbBackdropFx.ALPHA_FLOOR - 1e-6f)
            assertTrue("常态 alpha 越帽 t=$t", a <= CmbBackdropFx.ALPHA_NOW + 1e-6f)
            val c = CmbBackdropFx.alphaOf(t, true)
            assertTrue("剧场 alpha 越下限 t=$t", c >= CmbBackdropFx.ALPHA_FLOOR - 1e-6f)
            assertTrue("剧场 alpha 越帽 t=$t", c <= CmbBackdropFx.ALPHA_CINEMATIC + 1e-6f)
        }
        assertEquals(CmbBackdropFx.ALPHA_FLOOR, CmbBackdropFx.alphaOf(0f, false), 1e-5f)
        assertEquals(CmbBackdropFx.ALPHA_NOW, CmbBackdropFx.alphaOf(1f, false), 1e-5f)
        assertEquals(CmbBackdropFx.ALPHA_CINEMATIC, CmbBackdropFx.alphaOf(1f, true), 1e-5f)
    }

    @Test
    fun `着色桶-域与单调`() {
        var prev = -1
        for (i in 0..20) {
            val b = CmbBackdropFx.tintBucketOf(i / 20f)
            assertTrue(b in 0 until CmbBackdropFx.TINT_BUCKETS)
            assertTrue("桶应随温度单调不减", b >= prev)
            prev = b
        }
        assertEquals(0, CmbBackdropFx.tintBucketOf(-1f))
        assertEquals(CmbBackdropFx.TINT_BUCKETS - 1, CmbBackdropFx.tintBucketOf(2f))
    }

    // ==================== 底纹噪声（A 章②——确定性哈希非随机贴图） ====================

    @Test
    fun `噪声-确定性与值域与种子敏感`() {
        for (s in listOf(0, 7, -12345, 987654)) {
            for ((u, v) in listOf(0.13f to 0.77f, 0.5f to 0.5f, 0.999f to 0.001f)) {
                val a = CmbBackdropFx.noise01At(u, v, s)
                assertEquals("同种子应逐位一致 s=$s", a, CmbBackdropFx.noise01At(u, v, s), 0f)
                assertTrue("值域越界 s=$s u=$u", a in 0f..1f)
            }
        }
        // 种子敏感：换种子至少有一处纹理不同（「换库换底色」的前提）
        var diff = 0
        var i = 0
        while (i < 16 && diff == 0) {
            if (CmbBackdropFx.noise01At(i * 0.061f, 0.37f, 1) !=
                CmbBackdropFx.noise01At(i * 0.061f, 0.37f, 2)
            ) diff++
            i++
        }
        assertTrue("不同种子 16 采样零差异——种子敏感失效", diff > 0)
    }

    // ==================== 奥伯斯差集（C 章①——金矿成员−星池成员） ====================

    @Test
    fun `差集-池内排除与空池全量`() {
        val gold = listOf(5L to 10, 6L to 20, 7L to 15)
        // 池内成员排除（在图=已观测——差集天然=未观看未订阅未入图）
        assertEquals(listOf(6L, 7L), CmbBackdropFx.olbersCandidatesOf(gold, setOf(5L)).first)
        // 空池=金矿全量（items 降序）
        assertEquals(listOf(6L, 7L, 5L), CmbBackdropFx.olbersCandidatesOf(gold, emptySet()).first)
    }

    @Test
    fun `差集-items零过滤`() {
        // 索引零条目（probed=false 的空档案）不算「光在路上」
        assertEquals(0, CmbBackdropFx.olbersCandidatesOf(listOf(9L to 0), emptySet()).first.size)
    }

    @Test
    fun `差集-排序破平确定性`() {
        // items 相同→chatId 升序破平（确定性铁律）
        val gold = listOf(9L to 20, 3L to 20, 7L to 20)
        assertEquals(listOf(3L, 7L, 9L), CmbBackdropFx.olbersCandidatesOf(gold, emptySet()).first)
        // 同输入两跑逐位一致
        assertEquals(
            CmbBackdropFx.olbersCandidatesOf(gold, emptySet()),
            CmbBackdropFx.olbersCandidatesOf(gold, emptySet()),
        )
    }

    @Test
    fun `差集-帽与截断记账`() {
        val gold = (1..130).map { i -> (i * 10L + 1L) to (131 - i) } // items 降序=生成序
        val (kept, truncated) = CmbBackdropFx.olbersCandidatesOf(gold, emptySet())
        assertEquals(CmbBackdropFx.DOTS_MAX, kept.size)
        assertEquals(10, truncated)
        // 截断保序：kept 是 items top-120（金矿候选常有几百——诚实截断记账）
        // items 最大=131-1=130（i=1→chatId 11）；第 120 名 items=11（i=120→chatId 1201）
        assertEquals(11L, kept.first())
        assertEquals(1201L, kept.last())
    }

    // ==================== 布点（C 章②——球面哈希确定性） ====================

    @Test
    fun `布点-半径域与确定性`() {
        val d = FloatArray(3)
        for (id in listOf(1L, 42L, -999L, 123456789L)) {
            val r = CmbBackdropFx.dotRadius01(id)
            assertEquals("同 id 半径应确定性一致", r, CmbBackdropFx.dotRadius01(id), 0f)
            assertTrue("半径域越界 id=$id", r in 1f..CmbBackdropFx.SHELL_OUT_K)
        }
    }

    @Test
    fun `布点-方向单位向量与确定性`() {
        val d = FloatArray(3)
        val d2 = FloatArray(3)
        for (id in listOf(1L, 42L, -999L, 123456789L)) {
            CmbBackdropFx.dotDir(id, d)
            CmbBackdropFx.dotDir(id, d2)
            for (j in 0..2) assertEquals("同 id 方向应确定性一致", d[j], d2[j], 0f)
            val len = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
            assertEquals("方向应为单位向量 id=$id", 1f, len, 1e-4f)
        }
        // 不同 id 方向不同（球面哈希均匀布点的前提）
        CmbBackdropFx.dotDir(1L, d)
        CmbBackdropFx.dotDir(2L, d2)
        var same = true
        for (j in 0..2) if (d[j] != d2[j]) same = false
        assertTrue("不同 id 方向零差异——球面哈希失效", !same)
    }

    @Test
    fun `zone装配-同差集同布点与缺省`() {
        val ids = listOf(11L, 22L, 33L)
        val z1 = CmbBackdropFx.olbersZoneOf(100f, ids, 5, 0)
        val z2 = CmbBackdropFx.olbersZoneOf(100f, ids, 5, 0)
        assertEquals(z1.dots.size, z2.dots.size)
        for (i in z1.dots.indices) assertEquals("同差集同布点应逐位一致", z1.dots[i], z2.dots[i], 0f)
        // 记账直通
        assertEquals(5, z1.rawDiff)
        assertEquals(0, z1.truncated)
        // 世界位=R_obs×半径分数（薄壳层 1.0~1.15）
        for (i in ids.indices) {
            val m = sqrt(
                z1.dots[i * 3] * z1.dots[i * 3] +
                    z1.dots[i * 3 + 1] * z1.dots[i * 3 + 1] +
                    z1.dots[i * 3 + 2] * z1.dots[i * 3 + 2],
            )
            assertTrue("暗点半径越薄壳层 id=${ids[i]}", m in 100f..100f * CmbBackdropFx.SHELL_OUT_K)
        }
        // rObs 缺省=空带；候选空=空点
        assertEquals(0, CmbBackdropFx.olbersZoneOf(0f, ids, 5, 0).dots.size)
        assertEquals(0, CmbBackdropFx.olbersZoneOf(100f, emptyList(), 0, 0).dots.size)
    }

    // ==================== 彗星联动（C 章④——被动微亮） ====================

    @Test
    fun `彗星联动-域与帽`() {
        val base = CmbBackdropFx.DOT_ALPHA_BASE
        // 彗星不在场=原档（域外零成本）
        assertEquals(base, CmbBackdropFx.dotAlphaGlow(base, 0f, 0f), 0f)
        assertEquals(base, CmbBackdropFx.dotAlphaGlow(base, 0f, -1f), 0f)
        // 屏距在域外=原档
        assertEquals(base, CmbBackdropFx.dotAlphaGlow(base, CmbBackdropFx.COMET_GLOW_PX + 5f, 1f), 0f)
        // 贴头=最大增亮（基础档+增幅——帽=DOT_ALPHA_MAX+COMET_GLOW_BOOST 顶线）
        val atHead = CmbBackdropFx.dotAlphaGlow(base, 0f, 1f)
        assertEquals(CmbBackdropFx.DOT_ALPHA_BASE + CmbBackdropFx.COMET_GLOW_BOOST, atHead, 1e-4f)
        assertTrue(
            "增亮不得越帽（微微一亮非点亮）",
            atHead <= CmbBackdropFx.DOT_ALPHA_MAX + CmbBackdropFx.COMET_GLOW_BOOST + 1e-6f,
        )
        // 线性中间档在域内单调不减
        var prev = 0f
        var dist = CmbBackdropFx.COMET_GLOW_PX
        while (dist >= 0f) {
            val a = CmbBackdropFx.dotAlphaGlow(base, dist, 1f)
            assertTrue(a >= prev - 1e-6f)
            prev = a
            dist -= 12f
        }
    }

    // ==================== 烘焙单源（GlTextureBaker.bakeCmb——Canvas/GL 同一份） ====================

    @Test
    fun `烘焙-同种子逐位同纹理`() {
        val a = GlTextureBaker.bakeCmb(1234567L)
        val b = GlTextureBaker.bakeCmb(1234567L)
        assertEquals(CmbBackdropFx.TEX_SIZE * CmbBackdropFx.TEX_SIZE, a.size)
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals("烘焙应逐位确定 i=$i", a[i], b[i])
        // 白噪 alpha 图契约：RGB 全白（着色归绘制端），alpha 通道承载涨落
        for (i in a.indices) {
            assertEquals("RGB 应全白（着色绘制端做）", 0xFFFFFF, a[i] and 0xFFFFFF)
        }
    }

    @Test
    fun `烘焙-记忆化单条目`() {
        val s1 = GlTextureBaker.bakeCmbCached(777L)
        val s2 = GlTextureBaker.bakeCmbCached(777L)
        assertTrue("同种子应命中同一记忆条目（零重烘）", s1 === s2)
        val s3 = GlTextureBaker.bakeCmbCached(778L)
        assertTrue("换种子应换纹理", s3 !== s1)
    }
}
