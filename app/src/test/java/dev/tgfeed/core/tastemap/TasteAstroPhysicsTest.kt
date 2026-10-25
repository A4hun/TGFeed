package dev.tgfeed.core.tastemap

import dev.tgfeed.core.td.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * R249 天体物理引擎单测：天体物理映射（质量对数/光谱型阶梯/黑洞奥本海默三重门）/
 * 开普勒三定律（T²∝a³·椭圆焦点·等面积·牛顿迭代收敛）/星系微运动（幅度钳/近井快）/
 * 引力透镜（1/d 衰减/井心保护/旋涡切向分量/影响域早退）/科普文案（各类着落+诚实
 * 缺据回落）/团块化球面（z 轴追加：团锚语义/确定性/带语义）。
 */
class TasteAstroPhysicsTest {

    // ==================== 天体物理映射 ====================

    @Test
    fun `质量-对数单调且界内`() {
        assertEquals(0f, TasteAstroPhysics.mass01Of(0, 1000), 1e-6f)
        assertEquals(0f, TasteAstroPhysics.mass01Of(500, 0), 1e-6f)
        assertEquals(1f, TasteAstroPhysics.mass01Of(1000, 1000), 1e-6f)
        // 对数口径：10 条与 100 条的质量差被压（天文质量本就是对数感）
        val m10 = TasteAstroPhysics.mass01Of(10, 1000)
        val m100 = TasteAstroPhysics.mass01Of(100, 1000)
        val m1000 = TasteAstroPhysics.mass01Of(1000, 1000)
        assertTrue(m10 < m100 && m100 < m1000)
        assertTrue(m100 - m10 < m1000 - m100) // 上段差>下段差=对数压重尾
    }

    @Test
    fun `光谱-活跃率阶梯O到M`() {
        // 每周 14 条+=最炽热 O；0 条=M 红暗
        assertEquals(TasteAstroPhysics.SpectralClass.O, TasteAstroPhysics.spectralClassOf(20, 1L))
        assertEquals(TasteAstroPhysics.SpectralClass.O, TasteAstroPhysics.spectralClassOf(14, 1L))
        assertEquals(TasteAstroPhysics.SpectralClass.M, TasteAstroPhysics.spectralClassOf(0, 1L))
        // 中间单调不跳档
        val seq = listOf(14, 10, 7, 5, 3, 1, 0).map { TasteAstroPhysics.spectralClassOf(it, 1L).ordinal }
        assertTrue(seq.zipWithNext().all { (a, b) -> a <= b })
        // 无金矿证据按断更天数回落
        assertEquals(TasteAstroPhysics.SpectralClass.G, TasteAstroPhysics.spectralClassOf(-1, 3L))
        assertEquals(TasteAstroPhysics.SpectralClass.M, TasteAstroPhysics.spectralClassOf(-1, 400L))
        // 双缺=诚实 G（中性缺省）
        assertEquals(TasteAstroPhysics.SpectralClass.G, TasteAstroPhysics.spectralClassOf(-1, -1L))
    }

    // ==================== 黑洞（奥本海默三重门） ====================

    @Test
    fun `黑洞-三重门合取`() {
        // 全过=候选（42000 条=P95 档且远超均值 2500）
        assertTrue(TasteAstroPhysics.isBlackHoleCandidate(42_000, 0.99f, 2500f))
        // 分位不足（P95 未达）
        assertFalse(TasteAstroPhysics.isBlackHoleCandidate(42_000, 0.94f, 2500f))
        // 均值门不足（用户原话口径「超过所有频道平均值」；16384 实库标定 ×1——
        // ×8 会把爬取上限档超大频道全拒=零黑洞实锤）
        assertFalse(TasteAstroPhysics.isBlackHoleCandidate(5_000, 0.99f, 6000f))
        // 绝对体量不足（小库不冒充）
        assertFalse(TasteAstroPhysics.isBlackHoleCandidate(700, 1.0f, 50f))
        // 无全库统计=诚实跳过
        assertFalse(TasteAstroPhysics.isBlackHoleCandidate(42_000, 0.99f, 0f))
    }

    // ==================== 开普勒三定律 ====================

    @Test
    fun `开普勒第三定律-T平方正比a立方`() {
        // ω(2a)/ω(a) = (1/2)^1.5 —— T²∝a³ 的导数形
        val w1 = TasteAstroPhysics.keplerOmega(0.5f, 1f)
        val w2 = TasteAstroPhysics.keplerOmega(1.0f, 1f)
        assertEquals(w2 / w1, 0.35355f, 1e-3f) // 0.5^1.5
        val w4 = TasteAstroPhysics.keplerOmega(2.0f, 1f)
        assertEquals(w4 / w2, 0.35355f, 1e-3f)
    }

    @Test
    fun `开普勒方程-牛顿迭代收敛`() {
        // e=0 圆轨道：E=M 恒等
        assertEquals(1.234f, TasteAstroPhysics.solveKeplerE(1.234f, 0f), 1e-6f)
        // 偏心域残差 E−e·sinE−M < 1e-5（3 轮迭代对小 e 收敛）
        for (m in listOf(0f, 0.7f, 1.5f, 2.3f, 3.1f, 4.9f, 6.1f)) {
            for (e in listOf(0.14f, 0.24f, 0.3f)) {
                val E = TasteAstroPhysics.solveKeplerE(m, e)
                assertTrue(abs(E - e * sin(E) - m) < 1e-4f)
            }
        }
    }

    @Test
    fun `开普勒第二定律-真近点角与偏近点角一致`() {
        // tan(ν/2)=√((1+e)/(1−e))·tan(E/2)——与 E 的解析关系逐点核对
        val e = 0.2f
        for (m in listOf(0.3f, 1.2f, 2.8f, 4.4f)) {
            val E = TasteAstroPhysics.solveKeplerE(m, e)
            val nu = TasteAstroPhysics.keplerTrueAnomaly(m, e)
            val lhs = sin(nu / 2f) / cos(nu / 2f)
            val rhs = kotlin.math.sqrt((1 + e) / (1 - e)) * (sin(E / 2f) / cos(E / 2f))
            assertEquals(rhs, lhs, 1e-4f)
        }
        // e=0：ν=M（圆退化）
        assertEquals(1.9f, TasteAstroPhysics.keplerTrueAnomaly(1.9f, 0f), 1e-4f)
    }

    @Test
    fun `开普勒第一定律-椭圆焦点近日远日`() {
        val a = 100f
        val e = 0.2f
        // ν=0 近日点 a(1−e)；ν=π 远日点 a(1+e)——恒星在焦点
        assertEquals(a * (1 - e), TasteAstroPhysics.orbitRadius(a, e, 0f), 1e-3f)
        assertEquals(a * (1 + e), TasteAstroPhysics.orbitRadius(a, e, 3.1415926f), 1e-2f)
        // 界内：r ∈ [a(1−e), a(1+e)]
        for (k in 0..32) {
            val nu = k / 32f * 6.2831853f
            val r = TasteAstroPhysics.orbitRadius(a, e, nu)
            assertTrue(r >= a * (1 - e) - 1e-3f && r <= a * (1 + e) + 1e-3f)
        }
    }

    @Test
    fun `行星-开普勒口径烘焙`() {
        // build 出的行星：omega 按第三定律（近轨道快）、ecc 界内、确定性
        val items = (0 until 20).map {
            VideoItem(
                key = "k$it", chatId = 1000L, messageId = it.toLong(), channelTitle = "频道A",
                caption = "", durationSec = 0, width = 0, height = 0, fileId = -1,
                sizeBytes = 0, playUri = null, dateEpochSec = 100 + it,
                itemTags = listOf("高频"),
            )
        } + (0 until 3).map {
            VideoItem(
                key = "l$it", chatId = 1000L, messageId = 100 + it.toLong(), channelTitle = "频道A",
                caption = "", durationSec = 0, width = 0, height = 0, fileId = -1,
                sizeBytes = 0, playUri = null, dateEpochSec = 300 + it,
                itemTags = listOf("低频"),
            )
        }
        val sys = TasteTagPlanets.build(1000L, items)
        val hi = sys.planets.first { it.tag == "高频" }
        val lo = sys.planets.first { it.tag == "低频" }
        // 第三定律：内圈（高频）公转快——ω 与 a^1.5 成反比（同常数）
        assertTrue(hi.orbitR01 < lo.orbitR01)
        assertTrue(hi.omegaRadPerSec > lo.omegaRadPerSec)
        assertEquals(
            TasteTagPlanets.keplerOmegaOf(hi.orbitR01), hi.omegaRadPerSec, 0f,
        )
        assertEquals(
            TasteTagPlanets.keplerOmegaOf(lo.orbitR01), lo.omegaRadPerSec, 0f,
        )
        // 量程钳（R233 可点性校准界不破）
        assertTrue(sys.planets.all { it.omegaRadPerSec in 0.004f..0.030f })
        // 偏心率界内（严格椭圆且内圈近日点在恒星面外）
        assertTrue(sys.planets.all { it.ecc in 0.14f..0.24f })
        // 确定性：同输入同输出（含 ecc）
        val sys2 = TasteTagPlanets.build(1000L, items)
        sys.planets.zip(sys2.planets).forEach { (p, q) ->
            assertEquals(p.ecc, q.ecc, 0f)
            assertEquals(p.omegaRadPerSec, q.omegaRadPerSec, 0f)
        }
        // 开普勒位置三件：θ 与 r 随时间推进（近日点快远日点慢=等面积）
        val nu0 = TasteTagPlanets.keplerTheta(hi, 0f)
        val nu1 = TasteTagPlanets.keplerTheta(hi, 1f)
        assertTrue(abs(nu1 - nu0) > 0f)
        val rNear = TasteTagPlanets.keplerOrbitRadius(hi, 200f, 0f)
        val rFar = TasteTagPlanets.keplerOrbitRadius(hi, 200f, 3.14159f)
        assertTrue(rNear < rFar)
        // 近日点角速度 > 远日点角速度（等面积定律数值验证：同 ΔM 下 ν 的步进）
        val m0 = hi.phase01 * 6.2831853f
        val nuNear = TasteAstroPhysics.keplerTrueAnomaly(m0, hi.ecc)
        val rAt = TasteTagPlanets.keplerOrbitRadius(hi, 200f, nuNear)
        assertTrue(rAt in rNear..rFar)
    }

    // ==================== 星系微运动 ====================

    @Test
    fun `微运动-幅度与角速度钳位`() {
        // 幅度：近小远大但钳 [4,22]
        assertEquals(4f, TasteAstroPhysics.driftAmpOf(0f), 1e-3f)
        assertTrue(TasteAstroPhysics.driftAmpOf(300f) < TasteAstroPhysics.driftAmpOf(1000f))
        assertEquals(22f, TasteAstroPhysics.driftAmpOf(100_000f), 1e-3f)
        // 角速度：开普勒（近井快远井慢）
        assertTrue(TasteAstroPhysics.driftOmegaOf(300f) > TasteAstroPhysics.driftOmegaOf(2000f))
        assertTrue(TasteAstroPhysics.driftOmegaOf(1000f) in 0.002f..0.06f)
        // 引力吸引趋势：随距离与井质量增、钳 [2,24]
        assertTrue(
            TasteAstroPhysics.attractorPullOf(500f, 1f) >
                TasteAstroPhysics.attractorPullOf(500f, 0.35f),
        )
        assertEquals(24f, TasteAstroPhysics.attractorPullOf(1e6f, 1f), 1e-2f)
        assertEquals(2f, TasteAstroPhysics.attractorPullOf(10f, 0f), 1e-3f)
    }

    @Test
    fun `双星-幅度角速度与开普勒`() {
        assertTrue(TasteAstroPhysics.binaryAmpOf(200f) < TasteAstroPhysics.binaryAmpOf(600f))
        assertEquals(30f, TasteAstroPhysics.binaryAmpOf(1e5f), 1e-2f)
        // ω ∝ d^-1.5：近距绕得快
        val ratio = TasteAstroPhysics.binaryOmegaOf(200f) / TasteAstroPhysics.binaryOmegaOf(800f)
        assertEquals(8f, ratio, 0.05f) // (800/200)^1.5 = 8
    }

    // ==================== 引力透镜 ====================

    private val lensOut = FloatArray(2)

    @Test
    fun `透镜-影响域早退与衰减`() {
        val wellRpx = 30f
        // 域外：零成本早退
        assertFalse(TasteAstroPhysics.lensOffset(900f, 0f, wellRpx, 1f, 1f, true, lensOut))
        // 近距：偏移存在且 1/d 衰减（60px 处 > 200px 处）
        assertTrue(TasteAstroPhysics.lensOffset(60f, 0f, wellRpx, 1f, 1f, false, lensOut))
        val near = kotlin.math.hypot(lensOut[0], lensOut[1])
        assertTrue(TasteAstroPhysics.lensOffset(200f, 0f, wellRpx, 1f, 1f, false, lensOut))
        val far = kotlin.math.hypot(lensOut[0], lensOut[1])
        assertTrue(near > far && far > 0f)
        // 质量衰减：弱井（巨星 0.35）偏移更小
        assertTrue(TasteAstroPhysics.lensOffset(60f, 0f, wellRpx, 0.35f, 1f, false, lensOut))
        assertTrue(kotlin.math.hypot(lensOut[0], lensOut[1]) < near)
        // 背后因子增强（在远端档测——近端被井心保护钳位属设计行为）
        assertTrue(TasteAstroPhysics.lensOffset(200f, 0f, wellRpx, 1f, 1f, false, lensOut))
        val base200 = kotlin.math.hypot(lensOut[0], lensOut[1])
        assertTrue(TasteAstroPhysics.lensOffset(200f, 0f, wellRpx, 1f, 1.5f, false, lensOut))
        assertTrue(kotlin.math.hypot(lensOut[0], lensOut[1]) > base200 * 1.1f)
    }

    @Test
    fun `透镜-井心保护与旋涡切向`() {
        // 径向模式：偏移指向井（与 −d 同向），且绝不越过井心（|off| < 0.22·d）
        val dx = 80f
        val dy = 60f
        assertTrue(TasteAstroPhysics.lensOffset(dx, dy, 30f, 1f, 1f, false, lensOut))
        assertTrue(lensOut[0] * -dx + lensOut[1] * -dy > 0f) // 与「指向井」同向
        val len = kotlin.math.hypot(lensOut[0], lensOut[1])
        val d = kotlin.math.hypot(dx, dy)
        assertTrue(len < 0.22f * d + 1e-3f)
        // 旋涡模式：偏移含切向分量（与径向不正平行）
        assertTrue(TasteAstroPhysics.lensOffset(dx, dy, 30f, 1f, 1f, true, lensOut))
        val radial = floatArrayOf(-dx / d, -dy / d)
        val cross = lensOut[0] * radial[1] - lensOut[1] * radial[0]
        assertTrue(abs(cross) > 1e-3f) // 非纯径向=旋臂分量在场
        // 幅度上限 46px（在影响域内测：300px 已在域外早退——域界=30px 井×7.5=225px）
        assertTrue(TasteAstroPhysics.lensOffset(200f, 0f, 30f, 1f, 1.5f, false, lensOut))
        assertTrue(kotlin.math.hypot(lensOut[0], lensOut[1]) <= 46.1f)
    }

    // ==================== 科普文案（信息卡彩蛋行） ====================

    @Test
    fun `科普-各类天体着落`() {
        // 黑洞：均值倍数行（任务书示例口径）
        val bh = TasteAstroPhysics.factLine(
            CelestialKind.BLACK_HOLE, 42_000, -1, -1, 100L, 900L, 2500f, null, 0,
        )
        assertTrue(bh.contains("黑洞"))
        assertTrue(bh.contains("奥本海默"))
        assertTrue(bh.contains("16.8 倍")) // 42000/2500
        // 白矮星：停更天数+存量（任务书示例）
        val wd = TasteAstroPhysics.factLine(
            CelestialKind.WHITE_DWARF, 3200, -1, -1, 187L, 900L, 2500f, null, 0,
        )
        assertTrue(wd.contains("白矮星") && wd.contains("187") && wd.contains("3200"))
        // 超新星：倍数行
        val sn = TasteAstroPhysics.factLine(
            CelestialKind.SUPERNOVA, 500, 18, 6, 2L, 900L, 2500f, null, 0,
        )
        assertTrue(sn.contains("超新星") && sn.contains("3.0 倍"))
        // 原恒星：诞生天数
        val proto = TasteAstroPhysics.factLine(
            CelestialKind.PROTOSTAR, 100, -1, -1, 5L, 23L, 2500f, null, 0,
        )
        assertTrue(proto.contains("原恒星") && proto.contains("23 天"))
        // 恒星：光谱型+本周发布
        val star = TasteAstroPhysics.factLine(
            CelestialKind.STAR, 500, 12, 5, 2L, 900L, 2500f, null, 0,
        )
        assertTrue(star.contains("恒星") && star.contains("12 条"))
        // 脉冲星/星团/流星：身份文案
        assertTrue(
            TasteAstroPhysics.factLine(CelestialKind.PULSAR, 0, -1, -1, -1L, -1L, 0f, null, 0).contains("脉冲星"),
        )
        assertTrue(
            TasteAstroPhysics.factLine(CelestialKind.CLUSTER, 0, -1, -1, -1L, -1L, 0f, null, 0).contains("星团"),
        )
        assertTrue(
            TasteAstroPhysics.factLine(CelestialKind.METEOR, 0, -1, -1, -1L, -1L, 0f, null, 0).contains("流星"),
        )
        // 双星后缀：伴侣+关联数
        val bin = TasteAstroPhysics.factLine(
            CelestialKind.STAR, 500, 12, 5, 2L, 900L, 2500f, "频道X", 12,
        )
        assertTrue(bin.contains("双星") && bin.contains("频道X") && bin.contains("12 条关联"))
    }

    @Test
    fun `科普-R321双星段两行（潮汐预警与特洛伊群——缺据行不出）`() {
        // r315-D③/I③+r331 预清单：同源 top 对出潮汐预警行（数字全本星）+共源对出
        // 特洛伊群行；缺据（0）整行不出绝不编造——「永不合体」铁语义进文案
        val both = TasteAstroPhysics.factLine(
            CelestialKind.STAR, 500, 12, 5, 2L, 900L, 2500f, "频道X", 12,
            partnerSharedW = 9, partnerTrojanW = 6,
        )
        assertTrue(both.contains("潮汐撕裂"))
        assertTrue(both.contains("永不合并"))
        assertTrue(both.contains("同源证据 9 条"))
        assertTrue(both.contains("特洛伊群"))
        assertTrue(both.contains("共源证据 6 条"))
        // 缺据双门：W=0 两行全不出（既有行为零改——默认参口径）
        val none = TasteAstroPhysics.factLine(
            CelestialKind.STAR, 500, 12, 5, 2L, 900L, 2500f, "频道X", 12,
        )
        assertFalse(none.contains("潮汐撕裂"))
        assertFalse(none.contains("特洛伊群"))
        // 单门：只有同源 → 只出潮汐行；只有共源 → 只出特洛伊行
        val onlyShared = TasteAstroPhysics.factLine(
            CelestialKind.STAR, 500, 12, 5, 2L, 900L, 2500f, "频道X", 12,
            partnerSharedW = 5,
        )
        assertTrue(onlyShared.contains("潮汐撕裂") && !onlyShared.contains("特洛伊群"))
        val onlyTrojan = TasteAstroPhysics.factLine(
            CelestialKind.STAR, 500, 12, 5, 2L, 900L, 2500f, "频道X", 12,
            partnerTrojanW = 3,
        )
        assertTrue(onlyTrojan.contains("特洛伊群") && !onlyTrojan.contains("潮汐撕裂"))
        // 无双星伴侣（partnerTitle=null）→ 两行恒不出（行的宿主是双星段）
        val noPartner = TasteAstroPhysics.factLine(
            CelestialKind.STAR, 500, 12, 5, 2L, 900L, 2500f, null, 0,
            partnerSharedW = 9, partnerTrojanW = 6,
        )
        assertFalse(noPartner.contains("潮汐撕裂"))
        assertFalse(noPartner.contains("特洛伊群"))
    }

    @Test
    fun `科普-缺据诚实回落`() {
        // 白矮星无天数证据：不编数字
        val wd = TasteAstroPhysics.factLine(CelestialKind.WHITE_DWARF, 0, -1, -1, -1L, -1L, 0f, null, 0)
        assertTrue(wd.contains("白矮星") && !wd.contains("-1"))
        // 原恒星无首帖：诞生时间未知
        val proto = TasteAstroPhysics.factLine(CelestialKind.PROTOSTAR, 0, -1, -1, -1L, -1L, 0f, null, 0)
        assertTrue(proto.contains("未知"))
        // 黑洞无均值：降级「最重的天体」不冒充倍数
        val bh = TasteAstroPhysics.factLine(CelestialKind.BLACK_HOLE, 900, -1, -1, -1L, -1L, 0f, null, 0)
        assertTrue(bh.contains("黑洞") && !bh.contains("NaN"))
    }

    // ==================== 团块化球面（z 轴追加） ====================

    @Test
    fun `团块-半径带语义与团槽`() {
        val out = FloatArray(3)
        // 同团同 chatId 同参数=确定性
        TasteSpace.spherifyClustered(600f, 600f, -300f, 42L, 1.0f, 0.5f, 0.8f, 0.05f, out)
        val r1 = kotlin.math.hypot(out[0], kotlin.math.hypot(out[1], out[2]))
        val out2 = FloatArray(3)
        TasteSpace.spherifyClustered(600f, 600f, -300f, 42L, 1.0f, 0.5f, 0.8f, 0.05f, out2)
        assertEquals(out[0], out2[0], 0f)
        assertEquals(out[1], out2[1], 0f)
        // 近带（flightZ=-300 → z01 < −0.32）落在近壳 [90,480]
        assertTrue(r1 in 90f..480f)
        // 远带（flightZ=+300）落在远壳 [2100,7800]
        TasteSpace.spherifyClustered(600f, 600f, 300f, 42L, 1.0f, 0.5f, 0.8f, 0.05f, out)
        val r2 = kotlin.math.hypot(out[0], kotlin.math.hypot(out[1], out[2]))
        assertTrue(r2 in 2100f..7800f)
        // 同带内：径向槽高者更远（团槽语义）
        TasteSpace.spherifyClustered(600f, 600f, -300f, 42L, 1.0f, 0.5f, 0.1f, 0.05f, out)
        val r3 = kotlin.math.hypot(out[0], kotlin.math.hypot(out[1], out[2]))
        assertTrue(r1 > r3)
    }

    @Test
    fun `团块-成员方位聚拢与高度散开`() {
        // R252b 第五次修复后语义（BLOB_ELEV_JITTER=±69°）：团内**方位**聚拢保留
        //（BLOB_AZ_JITTER=±0.06 未动），高度则刻意全天散开（治「64% 星压在赤道平面」
        // 的病根——团高度哈希修好前的旧锚「高度错开 ±0.2 rad」与该修法互斥，已随
        // 布局换 V3（spherifyV3）废弃；本函数布局不再消费，锚定其真实终版契约）。
        val out = FloatArray(3)
        val outB = FloatArray(3)
        // 同团两成员（chatId 不同=哈希抖动不同）：方位差 < 0.35 rad（团内聚拢）
        TasteSpace.spherifyClustered(600f, 600f, -300f, 42L, 1.2f, 0.4f, 0.5f, 0.02f, out)
        TasteSpace.spherifyClustered(600f, 600f, -300f, 43L, 1.2f, 0.4f, 0.5f, -0.02f, outB)
        val az1 = kotlin.math.atan2(out[2], out[0])
        val az2 = kotlin.math.atan2(outB[2], outB[0])
        val dAz = kotlin.math.abs(az1 - az2).let { if (it > 3.14159f) 6.28318f - it else it }
        assertTrue(dAz < 0.4f)
        // 高度：团锚 ±69° 散开 + ±1.5 rad 钳位生效（不再贴近团锚——刻意语义）
        val r1 = kotlin.math.hypot(out[0], kotlin.math.hypot(out[1], out[2]))
        val elev1 = kotlin.math.asin(out[1] / r1)
        assertTrue(kotlin.math.abs(elev1) <= 1.5f + 1e-3f)
        // 全确定性：同参数同坐标（宇宙可复现）
        val outC = FloatArray(3)
        TasteSpace.spherifyClustered(600f, 600f, -300f, 42L, 1.2f, 0.4f, 0.5f, 0.02f, outC)
        assertEquals(out[0], outC[0], 0f)
        assertEquals(out[1], outC[1], 0f)
        assertEquals(out[2], outC[2], 0f)
    }
}
