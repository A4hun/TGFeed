package dev.tgfeed.core.tastemap

import kotlin.math.min

/**
 * R320 日月食**几何判定单源**（core 纯件，零 Android 依赖，JVM 单测可跑——
 * r315 G 章「日月食」的字面落地；TasteTerminalFx 同模式）：Canvas
 * （TasteFlightRenderer）与 GL（GlFrameBuilder）双端同式的机制保障。
 *
 * **G 章铁律：零新数据源零新运动源**——本件是纯渲染期几何判定（轨道/雾漂移/
 * 事件全部既有），凌星与消光都是**既有运动的自然副产品**：
 *  - **凌星**（贴脸恒星系档）：tag 行星开普勒轨道转到恒星正前方（ν/E 求值=
 *    TasteTagPlanets 既有）→行星剪影+恒星微暗（真实凌星测光：深度=面积比
 *    (行星视半径/恒星视半径)²——行星:恒星 1:8.7~1:5（R347 口径）→深度
 *    1.3%~4.0%；**食深帽 [DEPTH_CAP01]=4%**=防灭星红线）。**轨道倾角反例**：
 *    系统轨道倾角（galaxyOrbitInclination ±34° 哈希）决定行星投影轨迹是否扫过
 *    恒星星面——高倾角系统的行星屏距恒 > 恒星视半径=**永远不凌星=诚实**
 *    （零强制造食：判定纯几何，数据说话）；
 *  - **星际消光**（全景档）：未探索雾团（UniverseDirector.applyDrift 漂移既有
 *    ——绕 Y 轴摇曳+径向呼吸）真实遮星（星深 > 雾深=星在雾后）→变暗
 *    ×0.8+色温 −300K（尘埃散射蓝光=变红变暗——真实消光物理；线性+域）。
 *
 * 确定性：全部公式零 Random 零系统钟（同帧同几何同判定——R 系铁律）。
 * 帧预算：凌星=贴脸档系统行星级微成本（域外早退）；消光=6 雾×星数距离平方
 * （透镜同款量级先例——R249 实测零成本）。R385 增复合体级消光分支（§3.3-2
 * ——团级语义零碰；复合体 ≤4×星数距离平方=雾账同构微成本）。
 */
object EclipseFx {

    // ==================== 凌星（transit——渲染期几何判定） ====================

    /** 食深帽（0.04=4%——防灭星红线：r315 G⑤「食深帽 ≤4%」字面） */
    const val DEPTH_CAP01 = 0.04f

    /**
     * 凌星掩星分数（0..1）：行星盘在恒星星面内的分数——渐入(0→1)/全合(1)/
     * 渐出(1→0)；域外（屏距 ≥ 恒星视半径+行星视半径）恒 0=零成本早退。
     * 剪影强度消费本值（剪影只在掩星>0 时画——金星凌日观感）。
     */
    fun transitOverlap01(planetRpx: Float, starRpx: Float, screenDist: Float): Float {
        if (planetRpx <= 0f || starRpx <= 0f) return 0f
        if (screenDist >= starRpx + planetRpx) return 0f // 域外早退（不凌）
        if (screenDist <= starRpx - planetRpx) return 1f // 全内合（行星整盘在星面内）
        // 部分掩：线性近似（渐入渐出的对称坡——精确面积积分对 4% 帽量级不必要）
        return ((starRpx + planetRpx - screenDist) / (2f * planetRpx)).coerceIn(0f, 1f)
    }

    /**
     * 凌星食深（0..[DEPTH_CAP01]）：恒星被行星遮掉的亮度分数=掩星分数×面积比
     * (行星视半径/恒星视半径)²（**真实凌星测光**——开普勒望远镜找系外行星的
     * 方法：行星 1:8.7~1:5（TasteTagPlanets.planetRadiusCoef 域 0.115~0.20——R247
     * 口径）→全合深度 1.3%~4.0%；r315 G「1.5% 星等变化=真实量级不戏剧化」）。
     * 恒星亮度乘子=1−深度（渲染 pass 消费——微暗不灭星）。
     */
    fun transitDepth01(planetRpx: Float, starRpx: Float, screenDist: Float): Float {
        val overlap = transitOverlap01(planetRpx, starRpx, screenDist)
        if (overlap <= 0f) return 0f
        val ratio = planetRpx / starRpx
        return min(DEPTH_CAP01, overlap * ratio * ratio)
    }

    /**
     * 凌星是否在场（剪影绘制门——行星屏位进恒星星面即画剪影+恒星微暗）。
     * 与 [transitDepth01] 同域（域外零成本早退双口径同一式）。
     */
    fun isTransiting(planetRpx: Float, starRpx: Float, screenDist: Float): Boolean =
        screenDist < starRpx + planetRpx && planetRpx > 0f && starRpx > 0f

    /**
     * **R340 凌星前置判定（深度分量——黑圈回归根治件）**：真凌星=行星在恒星
     * **与相机之间**（[planetDepth]<[starDepth]，相机系深度——TasteSpace.project
     * 的 out[2]）且屏面重叠（[isTransiting] 域）。用户 20:0x 实锤「行星变成围绕
     * 恒星的小黑圆圈」病根=旧判定纯屏距无深度：行星绕到恒星**背后**（掩星）也
     * 判凌星→暗盘+亮环画在恒星辉光上；中近景 starPx（几何视半径，钳
     * SWALLOW_MAX_R_FRAC=0.35×min(w,h)）远大于吞没渐隐后的可视星面→屏面重叠
     * 域覆盖整个行星系屏区→行星大面积被劫持进剪影档=「全员黑圈」。加前置门后：
     * 背后行星走正常受光路径（GL 深度测试被恒星球真遮挡）；前行+屏面重叠=
     * 金星凌日观感保真（R320 特性回归本义）。旧 [isTransiting] 保留（Canvas
     * 消费方 R345 退役前零改动；GL 侧消费方换本件）。
     */
    fun isTransitingFront(
        planetRpx: Float,
        starRpx: Float,
        screenDist: Float,
        planetDepth: Float,
        starDepth: Float,
    ): Boolean = isTransiting(planetRpx, starRpx, screenDist) && planetDepth < starDepth

    /**
     * **R340 凌星前置食深**（[isTransitingFront] 的预扫账口径——恒星微暗只吃
     * 真前行星的遮挡）：背后（掩星）行星恒 0——恒星不会被身后的行星遮暗
     * （物理诚实：食深=正面遮挡的测光）。参数语义同 [transitDepth01]+双深度。
     */
    fun transitDepthFront01(
        planetRpx: Float,
        starRpx: Float,
        screenDist: Float,
        planetDepth: Float,
        starDepth: Float,
    ): Float = if (planetDepth < starDepth) transitDepth01(planetRpx, starRpx, screenDist) else 0f

    // ==================== 星际消光（interstellar extinction——雾团漂移真实遮星） ====================

    /** 消光变暗帽（0.20——雾心正对时 ×0.8；r315 G「变暗 ×0.8」字面） */
    const val EXTINCTION_DIM_MAX01 = 0.20f

    /** 消光色温偏移帽（−300K——尘埃散射蓝光=变红；r315 G「色温 −300K」字面） */
    const val EXTINCTION_SHIFT_K = -300f

    /**
     * 消光重合度（0..1）：星屏位与雾团投影位的重合分数——雾心正对=1、雾缘=0
     * （线性渐变：雾是弥散介质非硬边盘）。域外（屏距 ≥ 雾视半径）恒 0。
     * **深度序判据在调用方**（星深 > 雾深=星在雾后才真实遮挡——「真实遮星」；
     * 本函数只出重合度几何，前后关系是物理前提）。
     */
    fun extinctionOverlap01(screenDist: Float, fogRpx: Float): Float {
        if (fogRpx <= 0f || screenDist >= fogRpx) return 0f
        return (1f - screenDist / fogRpx).coerceIn(0f, 1f)
    }

    /**
     * 消光变暗量（0..[EXTINCTION_DIM_MAX01]，重合度线性——r315 G「重合度→暗
     * 线性+域」字面）：星亮度乘子=1−本值（0.8 帽=雾心正对的满档）。
     */
    fun extinctionDim01(overlap01: Float): Float =
        (EXTINCTION_DIM_MAX01 * overlap01.coerceIn(0f, 1f))

    /**
     * 消光色温偏移（[EXTINCTION_SHIFT_K]..0，重合度线性）：**只作用于主序
     * STAR 连续色温支路（tempK>0）**——特殊 kind 身份色不被色温轴污染
     * （R311 惯例延续：消光不越权改身份色，只改亮度）。
     */
    fun extinctionShiftK(overlap01: Float): Float =
        EXTINCTION_SHIFT_K * overlap01.coerceIn(0f, 1f)

    // ==================== R385 复合体级消光（design-nebula §3.3-2——团级零碰新分支） ====================

    /**
     * 复合体消光透射下限候选（0.75=柱峰 ~25% 减光——design §4.3 预算表候选带，
     * **真机定稿+注释自认**）。量级自认：真实星际消光随柱密度可暗上星等数级
     * （透射远低于此），本值是「穿云前方星群可辨但明显减光」的克制档——梦感
     * 优先于天文写实，与团级变暗帽 0.2（×0.8）同判词族；帽上仍有丝缕形态与
     * 丝间留空三重防线（R252 三代病负锚同守）。真机复核口径：华为腿穿云前后
     * 对照（r385_notes §5），观感过暗=候选带下调、不可辨=上调——单点改此常量。
     */
    const val EXTINCTION_CPX_TRANSMISSION_MIN = 0.75f

    /**
     * 复合体级消光柱密度（0..1 近似——design §3.3-2「消光∝视线柱密度」的渲染期
     * 代理）：屏面重合度（[extinctionOverlap01] 团级几何单源复用——云心正对=1/
     * 云缘=0，线性弥散介质同式）×云在场度 [presence01]（视觉 alpha/帽=数据锚定
     * 的云浓度）。柱密度∝云内路径长×密度，屏面重合度=路径长的屏面代理——大云
     * 的近似足够（复合体半径 ≥800 世界单位的星系级结构，屏面几何主导）。
     * 深度序判据（星深>云深=云在星与相机之间）在调用方——与团级同口径。
     */
    fun extinctionCpxColumn01(overlap01: Float, presence01: Float): Float =
        overlap01.coerceIn(0f, 1f) * presence01.coerceIn(0f, 1f)

    /**
     * 复合体级消光变暗量（0..1−[EXTINCTION_CPX_TRANSMISSION_MIN]=0.25）：星亮度
     * 乘子=1−本值（透射下限 0.75——满柱 25% 减光；团级 0.2 帽之上的复合体级档，
     * 大云更厚的诚实量级）。消费端=星 pass1 baseA 乘子（同团级口）。
     */
    fun extinctionCpxDim01(column01: Float): Float =
        (1f - EXTINCTION_CPX_TRANSMISSION_MIN) * column01.coerceIn(0f, 1f)

    /**
     * 复合体级消光色温偏移（[EXTINCTION_SHIFT_K]..0 线性）：**与团级同一尘埃
     * 散射物理同一常量单源消费**（[EXTINCTION_SHIFT_K] 直引零第二份数字——改就
     * 一起改），仅域随复合体档放宽（满柱 −300K）。只作用于主序连续色温支路
     * （消费端 R311 惯例沿 buildStarBody 内守卫——身份色不被色温轴污染）。
     */
    fun extinctionCpxShiftK(column01: Float): Float =
        EXTINCTION_SHIFT_K * column01.coerceIn(0f, 1f)
}
