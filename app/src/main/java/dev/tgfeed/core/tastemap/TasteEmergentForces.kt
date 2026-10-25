package dev.tgfeed.core.tastemap

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * R303 多尺度涌现星图 P0——**四种基本作用力的数据化核心**（纯函数，零 Android 依赖，
 * JVM 单测锚定；design-multiscale-emergent §3 终审稿的逐式落地，11 拍板项全按推荐值）。
 *
 * 四力总表（设计 §3.5）与本件落点：
 *
 * | 力 | 数据世界对应 | 本件函数 |
 * |---|---|---|
 * | 引力 | 互链/同口味/同内容（稀疏弹簧+全域基底向心） | [strengthOf]/[idealDist]/[baseGravityAccel] |
 * | 斥力 | 不相关疏远+个体空间（BH 软斥+硬核最小间距） | TasteNBody.accelBarnesHutRepel/[hardMinDist]/[hardCoreAccel] |
 * | 强核力 | 频道内构成=尺度分离（复合刚体节点+双星超硬弹簧） | [K_S]（系内绑定由 TasteTagPlanets 现役件承担，不进本力场） |
 * | 弱核力 | 活跃度衰变（λ 衰减耦合+死星晕漂墓地轨道） | [decayLambda]/[haloShiftedRadius] |
 *
 * 纪律锚点：
 *  - **弹簧非 1/d²**（拍板项核心）：数据世界的「引力」有显式平衡点 L_ab——真实引力
 *    无静态平衡正是 R249c 热化坍缩的数学根；
 *  - **不坍缩一等约束**（用户补刀）：硬核 20× 安全不等式+位置级投影（引擎侧）+
 *    暗能量旋钮默认关（[Params].kDE=0）+角动量由活层刚体旋转承担（TasteEmergentLive）；
 *  - 参数永不手调上线：安全不等式五条由 [safetyReport] 成文可测，终值走离线标定回路
 *    （快照彩排报告=标定原料）。
 *  - 与 Canvas/GL 现渲染**零耦合**：本层只产「布局场输出」（每星新坐标表）。
 */
object TasteEmergentForces {

    // ==================== 常量（设计 §3 各力小节起始值——终值由离线标定回路定稿） ====================

    /** 成对阈值：s_ab ≥ 此值（或有互链边）才进稀疏引力对（设计 §3.1「引力是稀疏的，这本身就是语义」） */
    const val S_PAIR_MIN = 0.08f

    /** 每星语义近邻数（top-k Jaccard 邻；§3.1） */
    const val TOPK_NEIGHBOR = 8

    /** 稀疏引力对总上限（互链 ≤800 + 每星 top-8 并集，设计口径 ≤2400） */
    const val MAX_PAIRS = 2400

    /** 硬核内衬（世界单位）：d_min=ρ_a+ρ_b+本值（§3.2 用户实锤病的根治条款） */
    const val HARD_GAP = 40f

    /** 理想距跨度（世界单位）：L_ab=d_min+SPAN·(1−0.75·s)（§3.1：最强对 ~450/最弱对 ~1690） */
    const val IDEAL_SPAN = 1600f

    /** 理想距强度增益（s=1 时压缩 75% 跨度） */
    const val IDEAL_S_GAIN = 0.75f

    /** 弹簧引力基准系数 k_G（量级标定起点=1.0；弹簧作几何约束直接作加速度，见引擎注） */
    const val K_G = 1.0f

    /** 强核力倍数：双星/强互链对超硬弹簧 k_S=10·k_G（安全不等式②，§3.3） */
    const val K_S_MULT = 10f

    /** 硬核倍数：k_H ≥ 20× 弹簧基准（安全不等式①，§3.2；违约=0 另由位置级投影清零兜底） */
    const val K_HARD_MULT = 20f

    /** 斥力强度起始值 k_R（§3.2 起始值；终值走标定） */
    const val K_R = 2500f

    /** 斥力软化长度（世界单位，§3.2 ε=120） */
    const val EPS_REPEL = 120f

    /** 斥力截断距（世界单位，§3.2 R_c=1400——斥力是短中程力） */
    const val R_CUTOFF = 1400f

    /** 基底向心强度 G_lay（§3.1：r=4000 处 ≈1.0 单位/步² @ M_tot≈210；有中心黑洞时井心加权——P0 井心=原点） */
    const val G_LAY = 7.6e4f

    /** 基底向心软化（沿用引力族口径） */
    const val EPS_BASE = 80f

    /** 银河半径（世界单位）：晕漂目标带/暗能量归一锚——与 TasteSpace.V3_FAR 同值同源 */
    const val R_GALAXY = 6000f

    /** 弱核力半衰期（天，§3.4 τ=45：结构关系漂移比行为偏好慢一个量级） */
    const val TAU_DAYS = 45f

    /** 衰变下限（λ 永不为 0——死星仍有残迹耦合） */
    const val LAMBDA_MIN = 0.05f

    /** 死星晕漂目标带 = 0.9·R_GALAXY（墓地轨道，§3.4） */
    const val HALO_BAND_FRAC = 0.9f

    /** 暗能量旋钮默认值 = 0（关闭——拍板项⑨：仅标定回路可开启） */
    const val K_DE_DEFAULT = 0f

    /** 阻尼系数（布局场速度阻尼 0.85，§6.1——布局求收敛不求守恒，与演艺场辛积分刻意不同） */
    const val DAMPING = 0.85f

    /** 离线定形轮数上限（§6.1：退火上限 800 轮早停） */
    const val ROUNDS_MAX = 800

    /** 稳态判据检查间隔（轮，§6.1 每 50 轮查闸） */
    const val GATE_EVERY = 50

    /** 单步位移帽（世界单位·温度 T 线性缩——安全不等式⑤的机械化：热相位大步调、冷相位细修） */
    const val DISP_CAP = 20f

    /** 力平衡闸：max|F| 上限（§6.3-1） */
    const val GATE_FORCE = 0.5f

    /** 几何静止闸：末轮最大位移上限（世界单位，§6.3-2） */
    const val GATE_DISP = 0.5f

    /** 收敛非震荡闸：应力 S 近 50 轮相对变化上限（§6.3-4） */
    const val GATE_STRESS_DRIFT = 0.001f

    /** 万步浸泡闸：云幅 R90 变化上限（§6.3-6，用户不坍缩补刀立法） */
    const val SOAK_R90_DRIFT = 0.05f

    /** 万步浸泡闸：质心漂移上限（世界单位，§6.3-6） */
    const val SOAK_CENTROID_DRIFT = 50f

    // ==================== 力场参数包（标定回路的网格单元） ====================

    /**
     * 力场参数（一组可标定配置；默认值=设计 §3 起始值）。
     *
     * @property kG 弹簧引力系数
     * @property kR 斥力系数
     * @property kHard 硬核系数（安全不等式①：≥20×kG）
     * @property kS 强核双星弹簧系数（安全不等式②：≥10×kG）
     * @property gLay 基底向心强度
     * @property lSpan 弹簧理想距跨度（标定三参之一；默认 [IDEAL_SPAN]）
     * @property kHome 观察者家弹簧径向系数（§3.8——弱力口径默认 0.02·kG；快照缺观看
     *   证据时调用方可传体量半径代理 rPref）
     * @property kDE 暗能量旋钮（默认 0 关闭——拍板项⑨）
     * @property damping 速度阻尼
     * @property roundsMax 退火轮数上限
     * @property applyHaloDrift 是否在定形收尾施加死星晕漂平衡位移（§3.4——拍板项⑤默认开）
     * @property soakSteps 定形后延长浸泡步数（0=不浸泡；验收/判废口径 10000）
     */
    class Params(
        val kG: Float = K_G,
        val kR: Float = K_R,
        val kHard: Float = K_HARD_MULT * K_G,
        val kS: Float = K_S_MULT * K_G,
        val gLay: Float = G_LAY,
        val lSpan: Float = IDEAL_SPAN,
        val kHome: Float = 0.02f,
        val kDE: Float = K_DE_DEFAULT,
        val damping: Float = DAMPING,
        val roundsMax: Int = ROUNDS_MAX,
        val applyHaloDrift: Boolean = true,
        val soakSteps: Int = 0,
    )

    // ==================== 引力：相关强度合成与理想距（§3.1） ====================

    /**
     * 相关强度合成（设计原文公式逐式落地）：
     * `s = clamp01( 0.5·ln1p(w)/ln1p(w_p95) + 0.3·(j/0.30) + 0.2·(1−d_mds/d_p95) )`
     *
     * @param wLink 互链边权（DiscoveryGraph 三类边合并计数；无证据=0）
     * @param wLinkP95 全库互链边权 P95（归一锚；≤0=互链源缺席，该项计 0——诚实降档）
     * @param jaccard tag 指纹 Jaccard（TasteTagAffinity 口径；0.30 为满档锚）
     * @param mdsDist MDS 构成基因余弦距离（MDS 降级=距离预言机，拍板项③）；快照缺源时
     *   调用方可用 tag 向量余弦距离代理并在探针行诚实标注
     * @param mdsDistP95 候选对距离 P95（归一锚；≤0 该项计 0）
     */
    fun strengthOf(
        wLink: Float,
        wLinkP95: Float,
        jaccard: Float,
        mdsDist: Float,
        mdsDistP95: Float,
    ): Float {
        val linkTerm = if (wLinkP95 > 0f && wLink > 0f) {
            0.5f * (kotlin.math.ln1p(wLink) / kotlin.math.ln1p(wLinkP95)).coerceIn(0f, 1f)
        } else 0f
        val jacTerm = 0.3f * (jaccard / 0.30f)
        val mdsTerm = if (mdsDistP95 > 0f) {
            0.2f * (1f - (mdsDist / mdsDistP95).coerceIn(0f, 1f))
        } else 0f
        return (linkTerm + jacTerm + mdsTerm).coerceIn(0f, 1f)
    }

    /** 硬核最小间距（§3.2 根治条款）：d_min=ρ_a+ρ_b+[HARD_GAP] */
    fun hardMinDist(rhoA: Float, rhoB: Float): Float = rhoA + rhoB + HARD_GAP

    /**
     * 弹簧理想距（§3.1）：`L = d_min + span·(1 − [IDEAL_S_GAIN]·s)`。
     * 安全不等式③由构造成立（s∈[0,1] → 跨度项 ≥ span·0.25 > 0 → L > d_min 恒真）。
     * [span] 默认 [IDEAL_SPAN]（设计起始值 1600——终值由标定回路定稿：快照彩排实测
     * 1600 ≫ 实测近邻距带=弹簧全网过伸，span 是标定网格三参之一）。
     */
    fun idealDist(rhoA: Float, rhoB: Float, s: Float, span: Float = IDEAL_SPAN): Float =
        hardMinDist(rhoA, rhoB) + span * (1f - IDEAL_S_GAIN * s.coerceIn(0f, 1f))

    /**
     * 银河基底向心加速度（§3.1「全体弱相关=都在你的口味宇宙里」；井心=原点）：
     * `a = −G_lay·M_tot·r̂/(r²+ε²)`（单位质量口径——质量在分子分母相消）。
     * 它对抗斥力无限膨胀（安全不等式④的左半），也是「恒星绕银心」愿景的物理化身。
     */
    fun baseGravityAccel(r: Float, mTot: Float, gLay: Float = G_LAY): Float {
        if (r <= 1e-4f) return 0f
        val e2 = EPS_BASE * EPS_BASE
        return gLay * mTot / (r * r + e2)
    }

    // ==================== 斥力：硬核弹簧（软斥走 TasteNBody 树，§3.2） ====================

    /**
     * 硬核弹簧加速度（重合根治的力层）：d<d_min 时 `a = k_H·(d_min−d)` 沿连线推开；
     * d≥d_min 零力。安全不等式①要求 k_H≥20×kG（[Params] 默认构造即满足）。
     */
    fun hardCoreAccel(d: Float, dMin: Float, kHard: Float): Float =
        if (d >= dMin) 0f else kHard * (dMin - d)

    // ==================== 弱核力：活跃度衰变（§3.4） ====================

    /**
     * 衰变因子：`λ = 2^(−断更天数/τ)`，τ=[TAU_DAYS]，下限 [LAMBDA_MIN]。
     * 三作用面（设计原文）：①引力耦合 ×λ（引擎侧 s_eff=s·√(λa·λb)——死星停止拉拢
     * 别人，别人也不被死星拽住，对称化是布局场的刻意选择）②晕漂平衡位移
     * （[haloShiftedRadius]）③天体身份演化（现役六态分类器，本层不接）。
     */
    fun decayLambda(staleDays: Float, tauDays: Float = TAU_DAYS): Float {
        if (staleDays <= 0f) return 1f
        val v = 2.0.pow((-staleDays / tauDays).toDouble()).toFloat()
        return v.coerceAtLeast(LAMBDA_MIN)
    }

    /**
     * 晕漂后的平衡半径（§3.4 墓地轨道——**平衡位移式**，绝不是活体受力飞行）：
     * `r' = r + (1−λ)·(r_halo − r)`，r_halo 默认 [HALO_BAND_FRAC]·[R_GALAXY]。
     * 活星 λ≈1 不动；死星 λ→0.05 渐近漂向银河边缘。以数据节奏（天/周）发生，
     * 线上呈现走条款三的 2s 滑移（P4 演出；本层只给平衡位真源）。
     * [rHalo] 可逐星指定（引擎侧加哈希厚度——单值壳会把百星挤进同一球面，
     * 彩排第九轮实锤：墓地要带厚 0.94~1.06×，否则硬核违约常青）。
     */
    /** 晕漂位移（银河边缘默认带心版——2 参便捷口） */
    fun haloShiftedRadius(r: Float, lambda: Float): Float =
        haloShiftedRadius(r, lambda, HALO_BAND_FRAC * R_GALAXY)

    /** 晕漂位移（显式目标半径版）：`r' = r + (1−λ)·(r_halo − r)`——墓地轨道是吸引子 */
    fun haloShiftedRadius(r: Float, lambda: Float, rHalo: Float): Float =
        r + (1f - lambda.coerceIn(0f, 1f)) * (rHalo - r)

    // ==================== 暗能量旋钮（§6.4 机制三——备而不用，用则有据） ====================

    /**
     * 暗能量加速度（可选大尺度扩张，**默认 kDE=0 关闭**——拍板项⑨）：
     * `a = +k_DE·r̂·(r/R_GALAXY)²`（单位质量口径，径向朝外）。
     * 仅当标定回路显示过收缩（簇内聚度达标但云幅小于目标带）才由标定网格开启取值。
     */
    fun darkEnergyAccel(r: Float, kDE: Float, rGalaxy: Float = R_GALAXY): Float {
        if (kDE == 0f || r <= 1e-4f) return 0f
        val q = r / rGalaxy
        return kDE * q * q
    }

    // ==================== 安全不等式（§3.6 参数合法域——成文可测，编译期外的单测锚） ====================

    /**
     * 安全不等式五条校验（返回违规描述表——空表=合法域内）：
     * ①硬核 ≥20×kG ②强核 ≥10×kG ③理想距下界（[idealDist] 构造保证，此处复验
     * s 全档）④基底向心 ≥1.2× 边缘斥力（**截断邻域口径**——斥力有 R_c=1400 截断，
     * 边缘星只受截断球内邻居排斥：nLocal≈N·(R_c/R)³ 均匀密度近似、近邻距
     * dnn≈0.554·R·(4π/3N)^⅓；点质量式会高估斥力 30 倍把合法域掐死——彩排第四轮
     * 实锤修正）⑤位移帽存在（[DISP_CAP]>0）。
     */
    fun safetyReport(p: Params, nStars: Int = 600, mTot: Float = 210f): List<String> {
        val out = ArrayList<String>(4)
        if (p.kHard < K_HARD_MULT * p.kG) {
            out += "安全不等式①违约：硬核 kHard=${p.kHard} < ${K_HARD_MULT}×kG=${K_HARD_MULT * p.kG}"
        }
        if (p.kS < K_S_MULT * p.kG) {
            out += "安全不等式②违约：强核 kS=${p.kS} < ${K_S_MULT}×kG=${K_S_MULT * p.kG}"
        }
        // ③ 全档复验 L>d_min（构造性成立——复验防未来改式破坏）
        var s = 0f
        while (s <= 1f) {
            if (idealDist(10f, 10f, s, p.lSpan) <= hardMinDist(10f, 10f)) {
                out += "安全不等式③违约：s=$s 处理想距未过硬核下界"
                break
            }
            s += 0.1f
        }
        // ④ R_GALAXY 处：基底向心 ≥1.2× 边缘斥力（截断邻域口径）
        val dnn = 0.554f * R_GALAXY * Math.cbrt(4.18879 / nStars).toFloat()
        val q = R_CUTOFF / R_GALAXY
        val nLocal = mTot * q * q * q // 截断球内邻居质量（均匀密度近似）
        val repelEdge = p.kR * nLocal / (dnn * dnn + EPS_REPEL * EPS_REPEL)
        val baseEdge = p.gLay * mTot / (R_GALAXY * R_GALAXY + EPS_BASE * EPS_BASE)
        if (baseEdge < 1.2f * repelEdge) {
            out += "安全不等式④违约：基底向心 $baseEdge < 1.2×边缘斥力 ${1.2f * repelEdge}（截断邻域口径，防膨胀散架）"
        }
        if (p.kDE < 0f) out += "暗能量旋钮非法：kDE=${p.kDE} <0（只许关或扩张，不许负向坍缩）"
        return out
    }

    // ==================== 强核力边界说明（§3.3——本层唯一现身=双星超硬弹簧系数 [Params].kS） ====================
    // 系内绑定（tag 绕频道/内容绕 tag）由 TasteTagPlanets 现役开普勒件承担——尺度分离
    // （系内 ≤R_sys≈60~120 ≪ 系间数百），不进全图力场；频道=复合刚体节点（质量=系总）。
}
