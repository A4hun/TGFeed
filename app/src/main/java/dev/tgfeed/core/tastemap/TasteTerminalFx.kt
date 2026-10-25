package dev.tgfeed.core.tastemap

import kotlin.math.cos
import kotlin.math.sin

/**
 * R317b 终态宣判渲染形状参数**单源**（core 纯件，零 Android 依赖，JVM 单测可跑）
 * ——Canvas（TasteFlightRenderer）与 GL（GlFrameBuilder）双端同式的机制保障
 * （TasteShipFx 同模式：渲染端只画不算，形状公式一处改两端生效——纪律§一类
 * 问题收敛）：
 *
 *  - **CompactStarFx**（[remnantFilaments]/[remnantArcRCoef]/[remnantArcSweep]/
 *    [remnantArcBaseDeg]）：中子星/夸克星=蓝白极亮致密核+**遗迹环**——R249
 *    超新星丝缕笔刷复用（3~4 段椭圆弧族），相位冻结缓漂（遗迹=死透的物质壳，
 *    vs 超新星的爆发扩散）；夸克星加第二道内环（密度连中子都压碎的读法）；
 *  - **R350 纤维点串**（一类一件——壳=球面版 [shellFiberPoint]/遗迹丝缕=环面版
 *    [torusFiberPoint]，r341 §3.3-A/B 的 2D 弧线 3D 升维共用族）：密度两档
 *    [remnantFiberPoints]+沿线侵蚀 [remnantFiberErode01]+遗迹环面方位/进动
 *    （[remnantRingTiltX]/[remnantRingTiltZ]/[remnantRingPrecessDeg]——漂移率
 *    [REMNANT_DRIFT_DEG_S] 与壳丝缕基相同族单源）；
 *  - **WhiteholeFx**（[whiteholeJetAngleDeg]/[whiteholeJetLen]/[whiteholeJetBreath]）：
 *    白核+**双向喷流**——固定哈希轴向+缓呼吸（只出不进的稳态外喷，与脉冲星
 *    旋转扫描束语义区分：白洞不扫、恒外喷）；
 *  - **黑洞进食档**（[accretionDiskK]/[isQuasarPhase01]）：accretion01
 *    （[TasteAstroPhysics.accretion01Of] 单口）→吸积盘亮度系数（静默暗盘/
 *    盛宴炽盘）+类星体相门（盛宴=喷流登场+光子环增亮——「正在坍缩=正在进食」
 *    的视觉翻译）；
 *  - **白洞互动账口径**（[whiteholeAccountOf]）：三账（赞/藏/弹幕）在场位→
 *    哨兵单口（在场=原计数/缺席=-1 缺据不判——r317b 挂账④案 A 的单一出处）。
 *
 * 确定性：全部公式零 Random 零系统钟（哈希=TasteSpace.h01 同式单口）——
 * 同星同形（R 系铁律）。
 */
object TasteTerminalFx {

    // ==================== CompactStarFx（中子星/夸克星） ====================

    /** 致密星核亮度下限（白矮星「死而不灭」同族档——致密残骸恒亮） */
    const val COMPACT_CORE_ALPHA_MIN = 0.92f

    /** 夸克星核额外增亮（比中子星更极端——密度压碎中子的读法） */
    const val QUARK_CORE_BOOST = 0.07f

    /** 遗迹环半径系数（r 倍数——爆发冲击波物质壳的现役半径，非扩散中） */
    const val REMNANT_RING_R = 1.9f

    /**
     * 3D 遗迹环窄带半宽（×环基准径 uRadius——r341 §3.3-B「内外径=REMNANT_RING_R
     * ×r±窄带」的窄带单源；RING_FS mode=2 内插消费）。
     */
    const val REMNANT_RING_BAND_HALF = 0.12f

    /** 夸克星内致密环半径系数（第二道环——中子星无此环） */
    const val QUARK_INNER_RING_R = 1.30f

    /**
     * R350 遗迹冻结缓漂率（°/s）：壳丝缕基相（[remnantArcBaseDeg]）与 3D 遗迹环
     * 进动角（[remnantRingPrecessDeg]）**同漂率族单源**（r341 §3.3-B「与超新星
     * 壳同漂率族」——遗迹=死透的物质壳近乎静止，只有漫长岁月的整体缓旋）。
     */
    const val REMNANT_DRIFT_DEG_S = 0.4f

    /**
     * R350 环面纤维离面波幅（×主环径——遗迹丝缕的管厚读法：面内径向散布由
     * [remnantArcRCoef]（0.80~1.12）承担、竖向起伏由此系数承担，环面纤维侧视
     * 不再压成一条线=真 3D 管）。
     */
    const val REMNANT_FIBER_WOBBLE = 0.12f

    /** 度→弧度（本件内部换算常量——勿在渲染端另写一份） */
    private const val DEG2RAD = 0.017453293f

    /** 遗迹丝缕弧段数（3~4 段——超新星丝缕同族；负 chatId 余数归一（Kotlin % 负域防 2 段）；确定性） */
    fun remnantFilaments(seed: Int): Int = 3 + ((seed % 2) + 2) % 2

    /** 遗迹丝缕弧半径系数（0.80~1.12——R249 超新星丝缕同参数族） */
    fun remnantArcRCoef(seed: Int, k: Int): Float = 0.80f + 0.32f * TasteSpace.h01(seed.toLong(), k * 31 + 201)

    /** 遗迹丝缕弧扫角（度，38~90） */
    fun remnantArcSweep(seed: Int, k: Int): Float = 38f + 52f * TasteSpace.h01(seed.toLong(), k * 31 + 203)

    /**
     * 遗迹丝缕基相角（度）：哈希基相+**极缓漂移** t×[REMNANT_DRIFT_DEG_S]
     * （超新星爆发环 t×3°/s 的冻结版——遗迹壳近乎静止，只有漫长岁月的整体缓旋；
     * R350 漂移率提单源常量与遗迹环进动角同族）。
     */
    fun remnantArcBaseDeg(seed: Int, k: Int, t: Float): Float =
        TasteSpace.h01(seed.toLong(), k * 31 + 205) * 360f + t * REMNANT_DRIFT_DEG_S

    // ==================== R350 纤维点串（球面/环面——壳/遗迹一类一件） ====================

    /**
     * 纤维点串密度（点/缕——r341 §3.3-A「8~12 点/缕，密度随 rPx 升两档」）：
     * 分界=[TasteLodPolicy.LOWMESH_MAX_DP]（全模线 4dp——政策件单口零新常量：
     * <4dp 疏档 8 点、≥4dp 密档 12 点；R355 阈值 dp 化——[lowMeshMaxPx]=帧字段
     * 透传（缺省 mdpi 档 density=1 恒等；渲染器传 [lodLowMeshMaxPx]））。
     * 预算：fil≤4×点串≤12=≤48 点/体。
     */
    fun remnantFiberPoints(rPx: Float, lowMeshMaxPx: Float = TasteLodPolicy.LOWMESH_MAX_DP): Int =
        if (rPx >= lowMeshMaxPx) 12 else 8

    /**
     * 纤维沿线侵蚀 01（0.15~1.0）：丝缕断续观感（r341 §3.3-A「点亮度=SHELL_ALPHA
     * ×A×沿线 fbm 侵蚀」——fbm 的确定性双频正弦和近似：零 Random 零系统钟，同星
     * 同形铁律；值域钳 0.15 保底=点串明暗起伏不断链）。侵蚀只做形状不做增益。
     */
    fun remnantFiberErode01(seed: Int, k: Int, u: Float): Float {
        val f1 = 1.5f + 2.5f * TasteSpace.h01(seed.toLong(), k * 31 + 221)
        val p1 = TasteSpace.h01(seed.toLong(), k * 31 + 223)
        val f2 = 4f + 5f * TasteSpace.h01(seed.toLong(), k * 31 + 225)
        val p2 = TasteSpace.h01(seed.toLong(), k * 31 + 227)
        val e = 0.5f + 0.27f * sin((u * f1 + p1) * 6.2831853f) +
            0.23f * sin((u * f2 + p2) * 6.2831853f)
        return 0.15f + 0.85f * e * e
    }

    /**
     * **球面纤维点串**（超新星遗迹壳——R350 3D 升维）：第 k 缕大圆弧上弧长参数
     * u∈[0,1] 的**单位球面点**（半径由调用方乘：壳径 r×SHELL_R×[remnantArcRCoef]）。
     * 起角=[remnantArcBaseDeg]（冻结缓漂直引）/扫角=[remnantArcSweep]（38~90°
     * 直引）既有通道直引；**大圆弧面方位 2 自由度（倾角+节点角）=3D 升维几何必需
     * 的同族新增哈希通道**（k*31+213/k*31+215——同 h01 同式同文件，零第二套源，
     * r350_notes §2.1 诚实记档）。out 槽 off..off+2 填 (x,y,z)（单位向量）。
     */
    fun shellFiberPoint(seed: Int, k: Int, u: Float, t: Float, out: FloatArray, off: Int) {
        val ad = (remnantArcBaseDeg(seed, k, t) + remnantArcSweep(seed, k) * u) * DEG2RAD
        val incl = TasteSpace.h01(seed.toLong(), k * 31 + 213) * 180f * DEG2RAD
        val node = TasteSpace.h01(seed.toLong(), k * 31 + 215) * 360f * DEG2RAD
        val ca = cos(ad)
        val sa = sin(ad)
        // 大圆：XZ 面基圆 (ca,0,sa) → 绕 X 倾 incl（面法线出屏面）→ 绕 Y 转 node
        val y1 = -sa * sin(incl)
        val z1 = sa * cos(incl)
        val cn = cos(node)
        val sn = sin(node)
        out[off] = ca * cn + z1 * sn
        out[off + 1] = y1
        out[off + 2] = -ca * sn + z1 * cn
    }

    /**
     * **环面纤维点串**（中子星/夸克星遗迹丝缕——R350 3D 升维）：第 k 缕在环面族
     * 的**局部平面坐标**（y=环法向；调用方再施与 mode=2 环带一致的
     * [remnantRingTiltX]/[remnantRingTiltZ]/[remnantRingPrecessDeg] 旋转变换——
     * 环带/丝缕同面族）。面内半径=majorR×[remnantArcRCoef]（0.80~1.12=管厚读法
     * 的径向散布）、离面波幅=majorR×[REMNANT_FIBER_WOBBLE]（竖向起伏——真 3D
     * 管侧视不压线）；起角/扫角 remnant 族直引。out 槽 off..off+2 填 (x,y,z)。
     */
    fun torusFiberPoint(seed: Int, k: Int, u: Float, t: Float, majorR: Float, out: FloatArray, off: Int) {
        val ad = (remnantArcBaseDeg(seed, k, t) + remnantArcSweep(seed, k) * u) * DEG2RAD
        val rr = majorR * remnantArcRCoef(seed, k)
        val wf = 1f + 2f * TasteSpace.h01(seed.toLong(), k * 31 + 217)
        val wp = TasteSpace.h01(seed.toLong(), k * 31 + 219)
        val w = sin((u * wf + wp) * 6.2831853f)
        out[off] = cos(ad) * rr
        out[off + 1] = majorR * REMNANT_FIBER_WOBBLE * w
        out[off + 2] = sin(ad) * rr
    }

    /** 3D 遗迹环主倾角（弧度——哈希 0~π；环带与环面纤维同面族共用） */
    fun remnantRingTiltX(seed: Int): Float = TasteSpace.h01(seed.toLong(), 207) * 3.14159265f

    /** 3D 遗迹环次倾角（弧度 ±0.3——轻扰防侧视完全压线） */
    fun remnantRingTiltZ(seed: Int): Float = (TasteSpace.h01(seed.toLong(), 209) - 0.5f) * 0.6f

    /**
     * 3D 遗迹环进动角（度）：哈希基相+t×[REMNANT_DRIFT_DEG_S]（与壳丝缕基相
     * 同漂率族——慢进动=环面绕世界 Y 的节点线缓旋；RING_STRIDE 预留槽 15 的
     * 取值单源）。返回度（渲染端换弧度——R350 契约跟改申报件）。
     */
    fun remnantRingPrecessDeg(seed: Int, t: Float): Float =
        TasteSpace.h01(seed.toLong(), 211) * 360f + t * REMNANT_DRIFT_DEG_S

    // ==================== WhiteholeFx（白洞） ====================

    /** 白核亮度下限（暖白高亮——源泉恒在供血） */
    const val WHITEHOLE_CORE_ALPHA_MIN = 0.90f

    /**
     * 喷流轴向（度，0~180——固定哈希轴：双向对称恒外喷；与脉冲星束的旋转扫掠
     * （t×120°/s）语义区分。**白洞/类星体相黑洞共用**（同一「固定轴外喷」原语族
     * ——双端 Canvas/GL 消费同一式）。
     */
    fun whiteholeJetAngleDeg(seed: Int): Float = TasteSpace.h01(seed.toLong(), 17) * 180f

    /** 白洞喷流长度（px：r×4.6+26——单侧长度，双侧对称） */
    fun whiteholeJetLen(r: Float): Float = r * 4.6f + 26f

    /** 喷流亮度呼吸（0.72~1.0，~0.14Hz 缓呼吸——物质外喷的呼吸感非闪灭） */
    fun whiteholeJetBreath(t: Float, seed: Int): Float =
        0.86f + 0.14f * sin(t * 0.9f + TasteSpace.h01(seed.toLong(), 23) * 6.2831853f)

    // ==================== 黑洞进食档（accretion01→盘亮度/类星体相） ====================

    /**
     * 吸积盘亮度系数（0.55~1.0，accretion01 单调不减）：静默黑洞=暗盘（余温），
     * 盛宴黑洞=炽盘（吸积物质摩擦发光——accretion01 是「进食速率」的归一）。
     * 双端盘环带/盘粒子 alpha 同乘此系数。
     */
    fun accretionDiskK(accretion01: Float): Float = 0.55f + 0.45f * accretion01.coerceIn(0f, 1f)

    /**
     * 类星体相判定（accretion01 域的盛宴门——[TasteAstroPhysics.isQuasarPhase]
     * recent7 域的等价单口：acc01×[TasteAstroPhysics.RECENT7_SATURATION] ≥
     * [TasteAstroPhysics.ACCRETION_FEAST_RECENT7]）。过门=喷流登场+光子环
     * 增亮（渲染侧消费；判据侧口径单一出处不裂）。
     */
    fun isQuasarPhase01(accretion01: Float): Boolean =
        accretion01 * TasteAstroPhysics.RECENT7_SATURATION >= TasteAstroPhysics.ACCRETION_FEAST_RECENT7

    /** 盛宴档光子环增亮（刚好逃逸的光更亮——吸积供能） */
    const val QUASAR_PHOTON_BOOST = 0.13f

    // ==================== 白洞互动账口径（r317b 挂账④案 A 单一出处） ====================

    /**
     * 互动账哨兵单口（赞/藏/弹幕三账共用）：[present]=账面在场（读取成功/快照
     * 在场）→原计数透传；缺席 → **-1 缺据**（[TasteAstroPhysics.judgeWhiteHoles]
     * 见 -1 即不判该频道——绝不冒充「零回馈」）。在场位判定见装配口
     * （like=likes.json runCatching isSuccess / fav=favorites.json 磁盘直读
     * isSuccess / danmaku=广场快照非空——r317b 笔记②章论证）。
     */
    fun whiteholeAccountOf(count: Int, present: Boolean): Int = if (present) count else -1
}
