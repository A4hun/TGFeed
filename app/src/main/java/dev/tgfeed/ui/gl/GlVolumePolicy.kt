package dev.tgfeed.ui.gl

/**
 * R383 P3 raymarch 体积档政策件（design-nebula §5.2 降级链+§六 预算——**纯 Kotlin
 * 零 android 依赖**，JVM 单测锚定；GlEnginePolicy/TasteLodPolicy 同族模式）：
 * 「贴脸档 raymarch 的档位与降级链」的唯一出处（AGENT-DISCIPLINE §一：同样的
 * 判定不许在渲染器/装配层各写一份）。
 *
 * - **步数分档**（§5.2「40~64 步光线步进（步数分档）」）：[STEPS_HIGH]=64（质量
 *   档）/[STEPS_LOW]=40（省税档）；
 * - **降级链序**（§5.2「步数/分辨率降档→仍不过=退出 raymarch 档回远景 billboard」
 *   ——观感降级、语义与门连续=诚实降级 GlEnginePolicy 同口径）：
 *   tier 3=64 步半分辨率（满配——起始档自 R392 改为 [TIER_START]=quarter）→ 2=40 步半分辨率 → 1=40 步 quarter 分辨率 →
 *   **0=退出**（=P2 行为原样：billboard nearFade 渐出窗仍在、体积零趟——门语义
 *   不撤只是不给体积内饰，语义与门连续的结构兑现）；
 * - **帧率门口径**（§六「慢帧 ≤1%」+R376 LOD 同构滞回）：5s 统计窗慢帧>2%
 *   连续 2 窗降一级；连续 2 窗 <1% 升一级（**退出档升回须 3 窗**——退出粘滞
 *   防拍动：退出↔重入循环的帧率震荡对用户观感伤害大于滞留）；
 * - **门控**（§六「同屏至多 1 团贴脸窗内才开 raymarch」）：多团在窗=取
 *   dist/R 最小者单团（消费在渲染器触发门）。
 *
 * 消费端：TasteGlRenderer（触发门+状态机持态——R376 lodLevel 同构：渲染器持
 * 态、政策件出纯函数）；GlPostFx.renderVolume（[GlVolumeCall] uniform 束）。
 */
internal object GlVolumePolicy {

    /** 步数分档·质量档（§5.2「40~64」上限——半分辨率下的满质量步进） */
    const val STEPS_HIGH = 64

    /** 步数分档·省税档（§5.2 下限——降档第一刀砍步数） */
    const val STEPS_LOW = 40

    /** 退出档（体积零趟回 P2 billboard 行为——降级链终点） */
    const val TIER_EXIT = 0

    /** quarter 分辨率档（40 步×1/4 幅——降档第二刀砍分辨率） */
    const val TIER_QUARTER = 1

    /** 半分辨率 40 步档（降档第一级） */
    const val TIER_HALF_LOW = 2

    /** 半分辨率 64 步档（满质量天花板——升档链终点；R392 起不再兼任起始档） */
    const val TIER_HALF_HIGH = 3

    /**
     * 会话起始档（**R392 判词④低开高走**：起步即省税档 quarter×40——既有健康窗
     * 升档链自证后自动爬回满配（强机约 20s 后满档），弱机不再先挨幻灯片。
     * 「诚实降级」语义不变、方向反转：先证明跑得动，再给质量）
     */
    const val TIER_START = TIER_QUARTER

    /** 单次降档（0 地板=退出链终点；越界防御钳位） */
    fun demote(tier: Int): Int = (tier - 1).coerceAtLeast(TIER_EXIT)

    /** 单次升档（满质量天花板；越界防御钳位） */
    fun promote(tier: Int): Int = (tier + 1).coerceAtMost(TIER_HALF_HIGH)

    /** 档→步数（64 步只属半分辨率质量档；quarter 档恒 40 步省税） */
    fun stepsOf(tier: Int): Int = if (tier >= TIER_HALF_HIGH) STEPS_HIGH else STEPS_LOW

    /** 档→FBO 分辨率除数（半=2/quarter=4；退出档不消费——渲染器零趟） */
    fun resDivisorOf(tier: Int): Int = if (tier == TIER_QUARTER) 4 else 2

    /** 升档所需连续健康窗数（退出档 3 窗粘滞防拍动，其余 2 窗=R376 滞回同构） */
    fun healthyWinsToPromote(tier: Int): Int = if (tier <= TIER_EXIT) 3 else 2

    /** 档位人读名（探针行——logcat 取证件） */
    fun tierLabel(tier: Int): String = when (tier) {
        TIER_EXIT -> "退出回billboard"
        TIER_QUARTER -> "quarter×40步"
        TIER_HALF_LOW -> "半分辨率×40步"
        else -> "半分辨率×64步"
    }
}

/**
 * R383 体积趟 uniform 束（**帧零分配**——渲染器持单例逐帧改字段，GlPostFx 只读；
 * 纯 Kotlin 无 android 依赖）。相机三基+tan 半角+VP=光线重建与深度裁剪的几何件
 * （GlCameraMatrix 同约定：NDC y 向上、focal 像素口径）；[alphaCap]=已含
 * crossfade 权与 ramp 的等效帽终值（能量守恒消费侧）。
 */
internal class GlVolumeCall {
    /** 本帧体积趟是否发射（窗内 ∧ (tier>0 ∨ ramp 余量)） */
    var active = false

    // ---- 复合体（触发门选中团——单团门控） ----
    var cpxCx = 0f
    var cpxCy = 0f
    var cpxCz = 0f
    var cpxRadius = 1f
    var seed = 0f
    var haMix01 = 0.5f
    var alphaCap = 0.12f

    /** Hα 玫红（core 色板单源烘焙 rgb） */
    val colorHa = FloatArray(3)

    /** OIII 青（core 色板单源烘焙 rgb） */
    val colorOiii = FloatArray(3)

    // ---- 相机几何（光线重建） ----
    var camX = 0f
    var camY = 0f
    var camZ = 0f
    val camRight = FloatArray(3)
    val camUp = FloatArray(3)
    val camFwd = FloatArray(3)

    /** tan 半角（(w/2)/focal, (h/2)/focal——NDC→相机系光线方向比） */
    var tanX = 1f
    var tanY = 1f

    /** VP 矩阵（深度裁剪重建——列主序 16） */
    val vp = FloatArray(16)

    // ---- 档位与时间 ----
    var timeSec = 0f
    var steps = GlVolumePolicy.STEPS_LOW
    var resDiv = 2
}
