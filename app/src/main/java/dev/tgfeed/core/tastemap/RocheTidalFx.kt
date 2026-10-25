package dev.tgfeed.core.tastemap

import kotlin.math.min

/**
 * R321 洛希极限潮汐形变参数**单源**（core 纯件，零 Android 依赖，JVM 单测可跑）
 * ——r315 D 章（用户铁语义「重复撞车的频道千万不要搞成两恒星重合」）：**同源双星
 * 近距互相撕扯但永不合并**——两星进入洛希域（世界距 < [dRocheOf]）时各自被拉成
 * 朝向对方的椭长形（scaleX 朝向轴伸长/scaleY 垂直压扁）+潮汐尾（朝对方撕出的
 * 细弱物质流，止于两星中点前）。
 *
 * Canvas（TasteFlightRenderer）与 GL（GlFrameBuilder/GlShaders SPHERE_VS）双端
 * 同式的机制保障（TasteTerminalFx/TasteStarGlow 同模式——渲染端只画不落数）：
 *
 *  - **洛希域**（[dRocheOf]/[def01Of]）：d_roche=ρa+ρb+[TasteEmergentForces.HARD_GAP]
 *    ——**R303 布局场硬核保护带外沿的单一出处**（同一距离语义不许两处常量——纪律
 *    §一；本件只调 hardMinDist 不自写 ρa+ρb+40）。形变量 def01=1−d/d_roche 钳 0..1
 *    （d≥d_roche 恒 0=域外零成本早退）；d 由调用方取两星**当帧 N 体渲染位**世界距
 *    （绕转呼吸→近心点撕得狠/远心点松开=形变随绕转自然涨落，零新时序源）。
 *  - **形变帽 [DEFORM_CAP]=0.28**（「永不合体」铁语义的视觉层量化保障）：伸长量
 *    ≤0.28×半径——与物理层硬核 d_min 双保险。数学证明（单测锚逐域采样）：伸长后
 *    表面间隙 ≥HARD_GAP−0.28×(ρa+ρb) ≥40−0.28×52=25.4 世界单位恒正——**任何
 *    体量组合下两星永不接触**（重合率回归锚的保卫线，R303 P0 不得回退）。
 *  - **形变曲线**：scaleX=1+0.25×def（名义 1.0→1.25）/scaleY=1−0.12×def（0.88）
 *    ——帽内运行时钳（[scaleXOf]/[scaleYOf] 任何输入不越 1±[DEFORM_CAP]）。
 *  - **潮汐尾**：[tailDots]（3~5 微点）/[tailAlphaOf]（0.05~0.12）/[tailLenPx]
 *    （∝形变量，中点前截止 [TAIL_STOP]=0.46×屏距）——「撕扯但没并」的张力感。
 *  - **撞车档门**（[warningSharedW]）：factLine 潮汐预警行（r315-D③/r331 预清单）
 *    只对**同源分 top** 的宣判双星对出（缺据/非 top=行不出现——诚实红线）。
 *
 * 工程纪律：全函数纯浮点零分配、确定性（零 Random 零系统钟）。
 */
object RocheTidalFx {

    /** 朝向轴伸长斜率（scaleX=1+ELONG×def——r315-D② 名义域 1.0→1.25） */
    const val ELONG = 0.25f

    /** 垂直轴压扁斜率（scaleY=1−SQUASH×def——0.88） */
    const val SQUASH = 0.12f

    /**
     * 形变帽（0.28 半径——**「永不合体」铁语义的视觉层保障**）：任何输入下伸长量
     * 不越此值（与物理层 hardMinDist 双保险；数学证明见类头——0.28×(ρa+ρb)≪
     * HARD_GAP=40 恒正间隙）。
     */
    const val DEFORM_CAP = 0.28f

    /** 潮汐尾微点数下限（哈希 3~5——「细弱物质流」克制） */
    const val TAIL_DOTS_MIN = 3

    /** 潮汐尾微点数上限 */
    const val TAIL_DOTS_MAX = 5

    /** 潮汐尾 alpha 下限（满形变窗内 0.05~0.12） */
    const val TAIL_ALPHA_MIN = 0.05f

    /** 潮汐尾 alpha 上限 */
    const val TAIL_ALPHA_MAX = 0.12f

    /** 潮汐尾长度帽（两星屏距分数——**止于中点前**：0.46<0.5 恒不越中点） */
    const val TAIL_STOP = 0.46f

    /**
     * 洛希域半径（世界单位）——[TasteEmergentForces.hardMinDist] 单一出处直调
     * （R303 硬核保护带外沿 ρa+ρb+40；同一距离语义零第二常量）。
     */
    fun dRocheOf(rhoA: Float, rhoB: Float): Float =
        TasteEmergentForces.hardMinDist(rhoA, rhoB)

    /**
     * 形变量（0..1）：1−d/d_roche（进入域越深越狠）；d≥d_roche 恒 0（**域外零成本
     * 早退**——渲染端单比较跳过全部分支）；病态输入（d_roche≤0/d<0）恒 0。
     */
    fun def01Of(d: Float, dRoche: Float): Float {
        if (dRoche <= 0f || d < 0f || d >= dRoche) return 0f
        return (1f - d / dRoche).coerceIn(0f, 1f)
    }

    /** 朝向轴缩放（1+ELONG×def，运行时钳 ≤1+DEFORM_CAP——帽是护栏非名义值） */
    fun scaleXOf(def01: Float): Float =
        (1f + ELONG * def01.coerceIn(0f, 1f)).coerceAtMost(1f + DEFORM_CAP)

    /** 垂直轴缩放（1−SQUASH×def，运行时钳 ≥1−DEFORM_CAP） */
    fun scaleYOf(def01: Float): Float =
        (1f - SQUASH * def01.coerceIn(0f, 1f)).coerceAtLeast(1f - DEFORM_CAP)

    /**
     * 伸长后半径（ρ×scaleX——「永不合体」证明的消费式：两星伸长半径和与硬核带
     * 的间隙恒正，单测逐域采样锚）。
     */
    fun elongatedRadiusOf(rho: Float, def01: Float): Float = rho * scaleXOf(def01)

    /** 潮汐尾微点数（3~5——确定性哈希；seed=星 chatId 桶） */
    fun tailDots(seed: Int): Int =
        TAIL_DOTS_MIN + ((seed % (TAIL_DOTS_MAX - TAIL_DOTS_MIN + 1)) +
            (TAIL_DOTS_MAX - TAIL_DOTS_MIN + 1)) % (TAIL_DOTS_MAX - TAIL_DOTS_MIN + 1)

    /** 潮汐尾 alpha（0.05~0.12×def 线性——形变越深尾越亮，域外 0） */
    fun tailAlphaOf(def01: Float): Float =
        TAIL_ALPHA_MIN + (TAIL_ALPHA_MAX - TAIL_ALPHA_MIN) * def01.coerceIn(0f, 1f)

    /**
     * 潮汐尾长度（px）：∝形变量（TAIL_STOP×屏距封顶）——**止于两星中点前**
     * （0.46×dpx<0.5×dpx 恒不越中点=「撕扯但没并」）；域外 0。
     */
    fun tailLenPx(def01: Float, pairScreenD: Float): Float {
        val d = def01.coerceIn(0f, 1f)
        if (d <= 0f || pairScreenD <= 0f) return 0f
        return min(TAIL_STOP * d * pairScreenD, TAIL_STOP * pairScreenD)
    }

    /**
     * 撞车档门（factLine 潮汐预警行——r315-D③「重复撞车对（同源分 top）」）：
     * [pairShared]=本对同源证据数（BinaryStarPolicy.PairEvidence 的
     * sharedDirected+sharedCoOccur——同指纹内容证据现役字段直取）；
     * [topShared]=全宣判对同源分最大值。过门=返回本对 W（出「同源证据 W 条」行）；
     * 缺据（0）/非 top（同分并列除外——≥top 并列也算撞车档）恒 0=行不出现。
     */
    fun warningSharedW(pairShared: Int, topShared: Int): Int =
        if (pairShared > 0 && pairShared >= topShared) pairShared else 0
}
