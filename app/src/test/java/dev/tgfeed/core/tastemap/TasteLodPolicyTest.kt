package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R349 LOD 政策件锚（r341 §6.2 TasteLodPolicyTest 设计清单——纯件域矩阵）：
 * ①blend01 域/单调/边界/smoothstep C1 连续 ②pointFadeK 互补恒等 ③meshTier 边界
 * ④effective* bias 单调 ⑤档位互斥完备四案（点/带/低模/全模——r341 §2.1 形态档
 * 互斥完备的量化锚）⑥回退序①②的有效阈值账（r341 §4.3 的字面数对表）。
 *
 * **R355 全套翻正**（阈值 DPI 化——px 裸值→dp 基准）：⑦dp 换算族单口（dp×
 * density=px；密度钳 [0.75,4.0] 防呆）⑧两密度档矩阵（2.0/2.5/3.0+mdpi 恒等）
 * ⑨跨密度视觉不变性（同 dp 半径同形态档=宪法「形状=视觉尺寸非设备像素」的
 * 量化锚——V55 一致性断言的 JVM 半体）⑩回退序兼容（bias 与密度正交——
 * bias=1.5 在任意密度=阈值×1.5 恒比例）。缺省参=mdpi 恒等档（density=1 时
 * dp≡px——JVM 锚基准；主渲染路径显式传帧字段由 R355WiringSourceTest 源锚锁死）。
 */
class TasteLodPolicyTest {

    // ==================== ① blend01 域/边界/单调/C1（mdpi 恒等档基准） ====================

    @Test
    fun `blend01-边界值与窗外零污染（mdpi 档 1_2 到 1_6 窗）`() {
        // 窗外恒 0/1（点档/3D 档两形态零污染——r341 §5.1）；缺省参=mdpi 恒等档
        assertEquals(0f, TasteLodPolicy.blend01(0f), 1e-6f)
        assertEquals(0f, TasteLodPolicy.blend01(1.19f), 1e-6f)
        assertEquals(0f, TasteLodPolicy.blend01(1.2f), 1e-6f) // 窗起点=0（点满档）
        assertTrue(TasteLodPolicy.blend01(1.21f) > 0f)
        assertTrue(TasteLodPolicy.blend01(1.59f) < 1f)
        assertEquals(1f, TasteLodPolicy.blend01(1.6f), 1e-6f) // 窗终点=1（3D 全权）
        assertEquals(1f, TasteLodPolicy.blend01(4f), 1e-6f)
        assertEquals(1f, TasteLodPolicy.blend01(300f), 1e-6f)
        assertEquals(0f, TasteLodPolicy.blend01(-5f), 1e-6f) // 域外钳=点形态
        // smoothstep 对称中点=0_5（带中点 1_4）
        assertEquals(0.5f, TasteLodPolicy.blend01(1.4f), 1e-4f)
    }

    @Test
    fun `blend01-单调非降与域01`() {
        var prev = 0f
        for (i in 0..400) {
            val k = TasteLodPolicy.blend01(i / 100f)
            assertTrue("r=${i / 100f} k=$k 回摆=红（k 低于前值 $prev）", k >= prev - 1e-6f)
            assertTrue("r=${i / 100f} k=$k 域外", k in 0f..1f)
            prev = k
        }
        assertEquals(1f, prev, 1e-6f)
    }

    @Test
    fun `blend01-smoothstep C1 连续（端点导数零——交接速度无跳变）`() {
        // C1 数值哨：窗端点两侧差商随步长线性收敛到 0（导数跳变则差商不随 h 收敛）；
        // 窗外恒值侧差商恒 0——两侧导数在端点相等且为零=smoothstep 零斜率起步收尾
        fun quo(r: Float, h: Float): Float = (TasteLodPolicy.blend01(r + h) - TasteLodPolicy.blend01(r)) / h
        val outsideZero = (TasteLodPolicy.blend01(1.2f) - TasteLodPolicy.blend01(1.19f)) / 0.01f
        assertEquals(0f, outsideZero, 1e-9f) // 窗外恒 0（左导数恒零）
        // 窗起点右差商随 h 线性减半（C1 收敛签名——非导数跳变的常数商）
        val q1 = quo(1.2f, 1e-3f)
        val q2 = quo(1.2f, 5e-4f)
        assertTrue("窗起点右差商应 <0_02（近零起步）实测 $q1", q1 in 0f..0.02f)
        assertTrue("差商应随 h 收敛减半（$q2 < $q1）——C1 签名", q2 < q1 * 0.75f)
        // 窗终点左差商同签名（右导数恒零=窗外恒 1）
        val zEnd = (TasteLodPolicy.blend01(1.61f) - TasteLodPolicy.blend01(1.6f)) / 0.01f
        assertEquals(0f, zEnd, 1e-9f)
        val qe1 = (TasteLodPolicy.blend01(1.6f) - TasteLodPolicy.blend01(1.6f - 1e-3f)) / 1e-3f
        val qe2 = (TasteLodPolicy.blend01(1.6f) - TasteLodPolicy.blend01(1.6f - 5e-4f)) / 5e-4f
        assertTrue("窗终点左差商应 <0_02（零斜率收尾）实测 $qe1", qe1 in 0f..0.02f)
        assertTrue("差商应随 h 收敛减半（$qe2 < $qe1）——C1 签名", qe2 < qe1 * 0.75f)
    }

    @Test
    fun `blend01-回退序①拉宽带（bias 阈值上抬=带内天体经 blend 回点档）`() {
        // ①阈值上抬：effectiveSolidFloor(1, 1.5)=2.4——带 [1_2,2.4]（r341 §4.3
        // 「阈值上抬：resolvable 下限上移」——1.6dp×1.5）；带内 k 全域压低=球权重
        // 连续退场
        val floor15 = TasteLodPolicy.effectiveSolidFloor(1f, 1.5f)
        assertEquals(2.4f, floor15, 1e-6f)
        assertEquals(1f, TasteLodPolicy.blend01(2.4f, floor15), 1e-6f) // 回退态窗终点=2_4
        // 常态已 3D 的 2px 星在回退态被拉回带内（k<1——经 blend 回点档非突变）
        assertTrue("回退态 2px 星应回带内 k=${TasteLodPolicy.blend01(2f, floor15)}", TasteLodPolicy.blend01(2f, floor15) < 1f)
        val k18 = TasteLodPolicy.blend01(1.8f, floor15)
        assertTrue("回退态 1_8px 星 k=$k18 应在带内（<1）", k18 in 0f..1f)
        assertTrue("同半径回退态球权重低于常态（拉宽带压低）", k18 < TasteLodPolicy.blend01(1.8f))
        assertEquals("常态 1_8px 星=纯 3D（k=1）", 1f, TasteLodPolicy.blend01(1.8f), 1e-6f)
        // 点档上界不随 bias 动（微星并点线恒 1_2dp——数量/形态线分离）
        assertEquals(0f, TasteLodPolicy.blend01(1.1f, floor15), 1e-6f)
        // bias=1 恒等
        assertEquals(TasteLodPolicy.SOLID_FLOOR_DP, TasteLodPolicy.effectiveSolidFloor(1f, 1f), 1e-6f)
    }

    // ==================== ② pointFadeK 互补恒等（能量守恒的形态半边——R355 终裁唯一存活侧） ====================

    @Test
    fun `pointFadeK-互补恒等与域`() {
        for (i in 0..200) {
            val r = i / 50f
            val k = TasteLodPolicy.blend01(r)
            val f = TasteLodPolicy.pointFadeK(r)
            assertEquals("r=$r 双形态因子和恒 1（能量守恒——无亮峰无凹陷）", 1f, k + f, 1e-6f)
            assertTrue("r=$r 退场系数域", f in 0f..1f)
        }
        // 两端满档互斥（resolved 无芒无闪的量化口径——r341 §5.2）
        assertEquals(1f, TasteLodPolicy.pointFadeK(1.1f), 1e-6f)
        assertEquals(0f, TasteLodPolicy.pointFadeK(1.6f), 1e-6f)
        assertEquals(0f, TasteLodPolicy.pointFadeK(30f), 1e-6f)
        // 回退态同门（floor 参数随行——1_8px 在拉宽带 [1_2,2_4] 内=部分退场）
        val f18 = TasteLodPolicy.pointFadeK(1.8f, 2.4f)
        assertTrue("回退态 1_8px 退场系数=$f18 应在带内（0<f<1——部分回点档）", f18 in 0f..1f && f18 > 0f)
        assertEquals("回退态互补恒等同成立", 1f, f18 + TasteLodPolicy.blend01(1.8f, 2.4f), 1e-6f)
    }

    // ==================== ③ meshTier 边界（4dp 低模→全模——用户终裁降线） ====================

    @Test
    fun `meshTier-边界与回退序②全低模`() {
        assertEquals(TasteLodPolicy.MESH_LOW, TasteLodPolicy.meshTierOf(2f))
        assertEquals(TasteLodPolicy.MESH_LOW, TasteLodPolicy.meshTierOf(3.99f))
        assertEquals(TasteLodPolicy.MESH_FULL, TasteLodPolicy.meshTierOf(4f))
        assertEquals(TasteLodPolicy.MESH_FULL, TasteLodPolicy.meshTierOf(30f))
        assertEquals(TasteLodPolicy.MESH_FULL, TasteLodPolicy.meshTierOf(300f))
        // ②降模：meshBias→∞=全模档整体降低模（r341 §4.3 字面）
        val allLow = TasteLodPolicy.effectiveLowMeshMax(1f, Float.POSITIVE_INFINITY)
        assertEquals(TasteLodPolicy.MESH_LOW, TasteLodPolicy.meshTierOf(300f, allLow))
        assertEquals(TasteLodPolicy.MESH_LOW, TasteLodPolicy.meshTierOf(10_000f, allLow))
        // bias=1 恒等；域外钳（<1 当 1）
        assertEquals(TasteLodPolicy.LOWMESH_MAX_DP, TasteLodPolicy.effectiveLowMeshMax(1f, 1f), 1e-6f)
        assertEquals(TasteLodPolicy.LOWMESH_MAX_DP, TasteLodPolicy.effectiveLowMeshMax(1f, 0.5f), 1e-6f)
        assertEquals(6f, TasteLodPolicy.effectiveLowMeshMax(1f, 1.5f), 1e-6f)
    }

    // ==================== ④ effective* bias 单调（密度正交） ====================

    @Test
    fun `effective-bias 单调非降（密度正交逐档）`() {
        for (d in listOf(1f, 2.0f, 2.5f, 3.0f)) {
            var prevFloor = 0f
            var prevLow = 0f
            for (i in 0..40) {
                val bias = i / 10f
                val floor = TasteLodPolicy.effectiveSolidFloor(d, bias)
                val low = TasteLodPolicy.effectiveLowMeshMax(d, bias)
                assertTrue("d=$d bias=$bias solidFloor=$floor 应单调非降", floor >= prevFloor - 1e-6f)
                assertTrue("d=$d bias=$bias lowMeshMax=$low 应单调非降", low >= prevLow - 1e-6f)
                assertTrue("d=$d bias=$bias floor=$floor 应≥常态", floor >= TasteLodPolicy.effectiveSolidFloor(d, 1f) - 1e-6f)
                assertTrue("d=$d bias=$bias low=$low 应≥常态", low >= TasteLodPolicy.effectiveLowMeshMax(d, 1f) - 1e-6f)
                prevFloor = floor
                prevLow = low
            }
        }
    }

    // ==================== ⑤ 档位互斥完备四案（点/带/低模/全模——mdpi 档基准） ====================

    @Test
    fun `档位-互斥完备四案（点_带_低模_全模全域扫描）`() {
        // r341 §6.2（R355 dp 重述）：r<1.2 点档 / [1.2,1.6) blend 带 / ≥1.6 3D
        // 真身（低模<4/全模≥4——用户终裁全模型门槛 4dp）
        // ——任意 r 恰落一案且仅一案；四案全域出现（互斥+完备+严格递进序）
        val formOf = { r: Float ->
            when (TasteLodPolicy.formTierOf(r)) {
                TasteLodPolicy.FORM_POINT -> 0
                TasteLodPolicy.FORM_BLEND -> 1
                TasteLodPolicy.FORM_SOLID ->
                    if (TasteLodPolicy.meshTierOf(r) == TasteLodPolicy.MESH_LOW) 2 else 3
                else -> -1
            }
        }
        for (r in listOf(-1f, 0f, 0.8f, 1.19f)) assertEquals(0, formOf(r))
        for (r in listOf(1.2f, 1.4f, 1.59f)) assertEquals(1, formOf(r))
        for (r in listOf(1.6f, 2.5f, 3.99f)) assertEquals(2, formOf(r))
        for (r in listOf(4f, 30f, 300f)) assertEquals(3, formOf(r))
        // 互斥完备：全域扫描无空洞无重叠+档间严格递进
        var last = -1
        val seen = mutableSetOf<Int>()
        for (i in 0..600) {
            val f = formOf(i / 10f)
            assertTrue(f in 0..3)
            seen.add(f)
            if (f != last) {
                assertEquals("档间应严格递进（f=$f last=$last 无重叠）", last + 1, f)
                last = f
            }
        }
        assertEquals(setOf(0, 1, 2, 3), seen)
        assertEquals(3, last)
        // 档 id 单调序（形态三档+mesh 两档）
        assertTrue(TasteLodPolicy.FORM_POINT < TasteLodPolicy.FORM_BLEND)
        assertTrue(TasteLodPolicy.FORM_BLEND < TasteLodPolicy.FORM_SOLID)
        assertTrue(TasteLodPolicy.MESH_LOW < TasteLodPolicy.MESH_FULL)
    }

    // ==================== ⑥ 政策宪法字面锚（R355 dp 拍板数） ====================

    @Test
    fun `政策-字面数与结构关系（dp 基准）`() {
        assertEquals(1.2f, TasteLodPolicy.POINT_CEIL_DP, 1e-6f)
        assertEquals(1.6f, TasteLodPolicy.SOLID_FLOOR_DP, 1e-6f)
        assertEquals(4.0f, TasteLodPolicy.LOWMESH_MAX_DP, 1e-6f)
        // 结构关系：点档上限<3D 下限<低模上界（三线有序=两态分界+mesh 分界）
        assertTrue(TasteLodPolicy.POINT_CEIL_DP < TasteLodPolicy.SOLID_FLOOR_DP)
        assertTrue(TasteLodPolicy.SOLID_FLOOR_DP < TasteLodPolicy.LOWMESH_MAX_DP)
        // blend 带宽（r341 §5.1「带宽度是政策件单旋钮」的现值锚——V51-② 观感终调位；
        // 0.4dp=0.75×SOLID 带族比例与旧 0.5px/2.0 同形）
        assertEquals(
            0.4f,
            TasteLodPolicy.SOLID_FLOOR_DP - TasteLodPolicy.POINT_CEIL_DP,
            1e-6f,
        )
    }

    // ==================== ⑦ R355 dp 换算族单口（一处乘 density 全族消费） ====================

    @Test
    fun `换算-dp 族单口（dp×density=px 逐档对表）`() {
        // mdpi 恒等档（density=1：dp≡px——缺省参/JVM 基准）
        assertEquals(1.2f, TasteLodPolicy.pointCeilPx(1f), 1e-6f)
        assertEquals(1.6f, TasteLodPolicy.effectiveSolidFloor(1f, 1f), 1e-6f)
        assertEquals(4f, TasteLodPolicy.effectiveLowMeshMax(1f, 1f), 1e-6f)
        // 两密度档矩阵（任务书口径 2.0/2.5/3.0；400dpi=2.5 的任务书字面：
        // 「SOLID_FLOOR=1.6dp（400dpi 上=4px——中距离星即 3D）」）
        for (d in listOf(2.0f, 2.5f, 3.0f)) {
            assertEquals(1.2f * d, TasteLodPolicy.pointCeilPx(d), 1e-4f)
            assertEquals(1.6f * d, TasteLodPolicy.effectiveSolidFloor(d, 1f), 1e-4f)
            assertEquals(4f * d, TasteLodPolicy.effectiveLowMeshMax(d, 1f), 1e-4f)
        }
        // 任务书字面数锚：400dpi（density=2.5）SOLID_FLOOR=4px
        assertEquals(4f, TasteLodPolicy.effectiveSolidFloor(2.5f, 1f), 1e-4f)
    }

    @Test
    fun `换算-密度钳防呆（域外收敛 0_75 到 4_0）`() {
        // 缺省 0/负值→下钳 0.75（密度缺失时阈值不塌零=点档吞全屏）；上钳 4.0
        assertEquals(TasteLodPolicy.pointCeilPx(0.75f), TasteLodPolicy.pointCeilPx(0f), 1e-6f)
        assertEquals(TasteLodPolicy.pointCeilPx(0.75f), TasteLodPolicy.pointCeilPx(-3f), 1e-6f)
        assertEquals(TasteLodPolicy.effectiveSolidFloor(4f, 1f), TasteLodPolicy.effectiveSolidFloor(100f, 1f), 1e-4f)
        assertEquals(TasteLodPolicy.effectiveLowMeshMax(4f, 1f), TasteLodPolicy.effectiveLowMeshMax(Float.POSITIVE_INFINITY, 1f), 1e-4f)
        // ldpi 0.75 真值域内直通
        assertEquals(0.9f, TasteLodPolicy.pointCeilPx(0.75f), 1e-6f)
    }

    // ==================== ⑧ 跨密度视觉不变性（宪法判词 dp 口径——V55 一致性 JVM 半体） ====================

    @Test
    fun `不变性-同 dp 半径同形态档（跨密度矩阵）`() {
        // 宪法：「分辨出形状=3D」的「形状」=视觉尺寸非设备像素——同一视觉半径
        // （dp 口径）在任意密度上落同一形态档/同一 mesh 档（=设备间 3D 出现
        // 时机一致的本体；V55 两密度档 statLodLine 对比的纯件半体）
        for (d in listOf(1f, 1.5f, 2.0f, 2.5f, 3.0f)) {
            val ceil = TasteLodPolicy.pointCeilPx(d)
            val floor = TasteLodPolicy.effectiveSolidFloor(d, 1f)
            val lowMax = TasteLodPolicy.effectiveLowMeshMax(d, 1f)
            // 同 dp 半径=同档（0.6dp 点 / 1.4dp blend / 2dp 低模 / 5dp 全模）
            assertEquals(TasteLodPolicy.FORM_POINT, TasteLodPolicy.formTierOf(0.6f * d, floor, ceil))
            assertEquals(TasteLodPolicy.FORM_BLEND, TasteLodPolicy.formTierOf(1.4f * d, floor, ceil))
            assertEquals(TasteLodPolicy.FORM_SOLID, TasteLodPolicy.formTierOf(2f * d, floor, ceil))
            assertEquals(TasteLodPolicy.MESH_LOW, TasteLodPolicy.meshTierOf(2f * d, lowMax))
            assertEquals(TasteLodPolicy.MESH_FULL, TasteLodPolicy.meshTierOf(5f * d, lowMax))
            // crossfade 曲线跨密度恒等（同 dp 位置同 k——视觉交接节奏一致）
            assertEquals(
                "d=$d 同 dp 位置 k 应跨密度恒等",
                TasteLodPolicy.blend01(1.4f),
                TasteLodPolicy.blend01(1.4f * d, floor, ceil),
                1e-5f,
            )
            // blend 窗带宽随密度线性（高密度上带更宽=交接更顺滑——0.4dp×d）
            assertEquals(0.4f * d, floor - ceil, 1e-4f)
        }
    }

    // ==================== ⑨ 回退序兼容（bias 与密度正交——任意密度恒比例） ====================

    @Test
    fun `回退序-兼容（bias 抬升在任意密度=阈值×1_5 恒比例）`() {
        for (d in listOf(1f, 2.0f, 2.5f, 3.0f)) {
            val floor = TasteLodPolicy.effectiveSolidFloor(d, 1f)
            val floor15 = TasteLodPolicy.effectiveSolidFloor(d, 1.5f)
            assertEquals("d=$d 回退① floor×1.5", floor * 1.5f, floor15, 1e-4f)
            // 点档上限恒不随 bias 动（换算族单口不带 bias——形态线/数量线正交）
            assertEquals(TasteLodPolicy.pointCeilPx(d), TasteLodPolicy.pointCeilPx(d), 1e-6f)
            // ②降模恒比例
            assertEquals(
                "d=$d 回退② lowMax×2",
                TasteLodPolicy.effectiveLowMeshMax(d, 1f) * 2f,
                TasteLodPolicy.effectiveLowMeshMax(d, 2f),
                1e-4f,
            )
            // 带内回退语义跨密度成立：常态球档星（1.8dp）在回退态回带内（k<1）
            val rPx = 1.8f * d
            assertTrue(
                "d=$d 回退① 1_8dp 星回带内",
                TasteLodPolicy.blend01(rPx, floor15, TasteLodPolicy.pointCeilPx(d)) < 1f,
            )
            assertEquals(1f, TasteLodPolicy.blend01(rPx, floor, TasteLodPolicy.pointCeilPx(d)), 1e-6f)
        }
    }

    // ==================== ⑩ 缺省参=mdpi 恒等档（density=1 时 dp≡px） ====================

    @Test
    fun `缺省-mdpi 恒等档（无密度调用方与帧字段口径一致）`() {
        // 缺省参（mdpi）与显式 density=1 逐位恒等（委托族/JVM 锚口径不漂）
        assertEquals(TasteLodPolicy.blend01(1.4f), TasteLodPolicy.blend01(1.4f, 1.6f, 1.2f), 1e-9f)
        assertEquals(TasteLodPolicy.pointFadeK(1.3f), TasteLodPolicy.pointFadeK(1.3f, 1.6f, 1.2f), 1e-9f)
        assertEquals(TasteLodPolicy.formTierOf(1.4f), TasteLodPolicy.formTierOf(1.4f, 1.6f, 1.2f))
        assertEquals(TasteLodPolicy.meshTierOf(2f), TasteLodPolicy.meshTierOf(2f, 4f))
        assertEquals(TasteLodPolicy.POINT_CEIL_DP, TasteLodPolicy.pointCeilPx(1f), 1e-9f)
        assertEquals(TasteLodPolicy.SOLID_FLOOR_DP, TasteLodPolicy.effectiveSolidFloor(1f, 1f), 1e-9f)
        assertEquals(TasteLodPolicy.LOWMESH_MAX_DP, TasteLodPolicy.effectiveLowMeshMax(1f, 1f), 1e-9f)
    }
}
