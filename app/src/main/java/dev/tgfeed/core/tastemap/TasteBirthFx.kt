package dev.tgfeed.core.tastemap

import kotlin.math.sin

/**
 * R326 诞生侧宣判渲染形状参数**单源**（core 纯件，零 Android 依赖，JVM 单测可跑）
 * ——R317b [TasteTerminalFx] 的诞生侧镜像（Canvas（TasteFlightRenderer）与 GL
 * （GlFrameBuilder）双端同式的机制保障：渲染端只画不算，形状公式一处改两端生效
 * ——纪律§一类问题收敛）：
 *
 *  - **聚合拍**（吸积盘螺旋内落——未探索星近距档焦点件）：PROTOSTAR 现役「吸积壳+
 *    3 静态尘埃粒」升维——尘埃粒**螺旋内落**（确定性哈希相位+半径随时间收缩循环=
 *    「物质正落向核心」的方向感）+核心进度亮度（[TasteAstroPhysics.probeProgress01]
 *    单口）+壳环呼吸随内落节奏。焦点件门 [ACCRETION_FOCUS_MIN_PX]（r≥3px——与
 *    PROTOSTAR 尘埃粒同门同预算；全景档=现状暗点零改——远看未探索星仍是雾中暗点，
 *    飞近才见聚合=发现感语义保护）；每颗 ≤4 粒+1 壳环+核心亮度系数（零新绘制
 *    调用族——≤PROTOSTAR 现役笔刷量级）；
 *  - **点燃拍**（年轻星点亮变体——会话内实时零缓存依赖）：点亮链（REVEAL_FX 增亮
 *    1.2s 既有）之上年轻星加**点燃闪光**（核心白闪 300ms+扩散环一道——REVEAL_FX
 *    同量级一次性窗）；老星点亮零加=现状打字机（判别门=kind 与 birth 注记，运行
 *    时判——批 C 接线）；
 *  - **行星成型贴脸拍**：成型态尘埃环凝缩增强（appearAlpha 出场坡道放缓+尘埃散布
 *    向行星轨道收缩——[TasteTagPlanets] 既有几何零改，仅参数经本件成型档调制）；
 *  - **循环联动口**（超新星遗迹环×雾星胚点脉冲——物质循环闭环的演出层接缝）：
 *    脉冲幅度 0.05~0.10 alpha+衰减窗包络（批 5 星胚点未落时**空挂零行为**——联动
 *    口参数单源已备，接线归批 5）。
 *
 * **回退姿势（r325 批间依赖图——参数帽=总闸等效位，施工批必须保留该帽）**：每组
 * 参数有归零/归一常量=行为归现状——[INFALL_RATE_CPS]=0→全族冻结（粒=哈希相位
 * 静态散布=现役 3 静态尘埃粒行为族）；[IGNITION_FLASH_BOOST]/[IGNITION_RING_ALPHA]
 * =0→零闪零环（现状点亮）；[FORMING_RAMP_MULT]=1.0/[FORMING_DUST_CONTRACT]=0→
 * 现状坡道散布；[CYCLE_PULSE_SPAN]=0→恒基值零脉冲。
 *
 * 确定性：全部公式零 Random 零系统钟（t 由调用方传；哈希=[TasteSpace.h01] 同式
 * 单口）——同星同形（R 系铁律）。
 */
object TasteBirthFx {

    // ==================== 聚合拍（吸积盘螺旋内落——焦点件） ====================

    /**
     * 聚合焦点件门（px，投影半径）——r≥3px 才画内落粒（与 PROTOSTAR 现役尘埃粒
     * 同门同预算——r325 帧预算三论证：焦点门后屏上同时可见数恒个位数）。批 C
     * 渲染器接线时其 3f 字面量换引本常量=单一出处收口。
     */
    const val ACCRETION_FOCUS_MIN_PX = 3f

    /**
     * 内落速率基（循环/秒）——单粒约 16s 从壳缘落到核心一循；**归零=零内落=全族
     * 冻结**（粒冻结哈希相位与基角=现役 3 静态尘埃粒行为族——聚合拍总闸回退位）。
     */
    const val INFALL_RATE_CPS = 1f / 16f

    /** 内落异速域下限（×基速率）——粒间快慢差=非均匀盘面 */
    private const val INFALL_RATE_MULT_MIN = 0.8f

    /** 内落异速域上限 */
    private const val INFALL_RATE_MULT_MAX = 1.2f

    /**
     * 内落扫角（rad/循环）——单粒一落约绕核 1.25 圈（螺旋疏密度）。与速率**共源**
     * （角=基角+旋向×扫角×内落时钟）：速率归零=扫角同步冻结=完全回退，不留「半径
     * 冻结但角度还在转」的半冻结态。
     */
    const val INFALL_SWEEP_RAD = 6.2831853f * 1.25f

    /** 内落粒数（3~4——PROTOSTAR 现役 3 静态尘埃粒的升维：动画不增粒数下限=帧预算同门同族；负 seed 余数归一防漏） */
    fun accretionParticles(seed: Int): Int = 3 + ((seed % 2) + 2) % 2

    /** 内落循环相位（0..1 确定性哈希——粒间错拍=盘面散布感） */
    fun accretionPhase01(seed: Int, k: Int): Float = TasteSpace.h01(seed.toLong(), k * 31 + 5)

    /** 内落速率（循环/秒——哈希异速 0.8~1.2×基速率；基速率归零则全体归零=冻结回退） */
    fun accretionRateCps(seed: Int, k: Int): Float =
        INFALL_RATE_CPS * (INFALL_RATE_MULT_MIN +
            (INFALL_RATE_MULT_MAX - INFALL_RATE_MULT_MIN) * TasteSpace.h01(seed.toLong(), k * 31 + 7))

    /**
     * 内落半径进度（0..1，**随时间收缩循环**）：1=壳缘（新物质出现）→0=核心（吸收）
     * ——半径=1−fract(t×速率+相位)，wrap 即重生=「物质正落向核心」的方向感。
     * 速率归零时恒为哈希相位的补（静态散布）。
     */
    fun accretionInfallRadius01(t: Float, seed: Int, k: Int): Float {
        var ph = (t * accretionRateCps(seed, k) + accretionPhase01(seed, k)) % 1f
        if (ph < 0f) ph += 1f
        return 1f - ph
    }

    /** 盘面旋向（+1/−1——**同星同向**=盘面读法：真实吸积盘统一旋向，粒间异速非异向） */
    fun accretionSpinDir(seed: Int): Int = if (TasteSpace.h01(seed.toLong(), 13) >= 0.5f) 1 else -1

    /**
     * 螺旋角（rad）：哈希基角+同向进动（进动时钟与内落半径共源——一落一扫的相位
     * 锁定螺旋；粒重生在壳缘（包络零亮）时角度跳变不可见）。
     */
    fun accretionSpiralAngleRad(t: Float, seed: Int, k: Int): Float =
        TasteSpace.h01(seed.toLong(), k * 31 + 11) * 6.2831853f +
            accretionSpinDir(seed) * INFALL_SWEEP_RAD *
            (t * accretionRateCps(seed, k) + accretionPhase01(seed, k))

    /**
     * 内落粒包络（0..1）：壳缘亮入→飞行满亮→核心吸收淡出（sin 半波——物质出现在
     * 盘缘、消失在核心=「正被吃进去」的读法；渲染端乘尘埃基 alpha）。
     */
    fun accretionParticleAlpha01(radius01: Float): Float =
        sin(3.14159265f * radius01.coerceIn(0f, 1f))

    /** 聚合核心亮度基（r325 原式 0.7——零进度也有微光：被探测中=有活动） */
    const val ACCRETION_CORE_K_BASE = 0.7f

    /** 聚合核心进度亮度跨度（r325 原式 0.3——归零=恒基亮=回退位） */
    const val ACCRETION_CORE_K_SPAN = 0.3f

    /**
     * 聚合核心亮度系数：0.7+0.3×进度（已聚合量÷升档线的进度亮度——
     * [TasteTerminalFx.accretionDiskK] 的诞生侧对位：终态侧进食速率升盘亮，诞生
     * 侧聚合进度升核亮）。[probeProgress01] 单口直供。
     */
    fun accretionCoreK(probeProgress01: Float): Float =
        (ACCRETION_CORE_K_BASE + ACCRETION_CORE_K_SPAN * probeProgress01.coerceIn(0f, 1f))
            .coerceIn(0f, 1f)

    /** 壳环呼吸幅度（0.15——0.85~1.0 缓呼吸；归零=恒 1.0 静态壳=现状吸积壳——回退位） */
    private const val ACCRETION_SHELL_BREATH_AMP = 0.15f

    /**
     * 壳环呼吸系数（0.85~1.0）：频率=内落基频×2（一落一吸的节奏耦合——壳随内落
     * 呼吸非闪灭；速率基归零=频率归零=呼吸冻结）。
     */
    fun accretionShellBreath(t: Float, seed: Int): Float =
        1f - ACCRETION_SHELL_BREATH_AMP * (0.5f + 0.5f *
            sin(t * 6.2831853f * INFALL_RATE_CPS * 2f + TasteSpace.h01(seed.toLong(), 19) * 6.2831853f))

    // ==================== 点燃拍（年轻星点亮变体——一次性窗） ====================

    /** 点燃窗（毫秒）——核心白闪+扩散环的一次性时长（REVEAL_FX 1.2s 增亮窗内的短促一拍） */
    const val IGNITION_MS = 300L

    /** 点燃窗（秒——[IGNITION_MS] 单一出处换算；Long÷Float 混算不入 const 故 val） */
    private val IGNITION_SEC = IGNITION_MS / 1000f

    /** 白闪峰值增亮（核心 alpha 增量；**归零=零闪=现状点亮**——回退位） */
    const val IGNITION_FLASH_BOOST = 0.8f

    /** 扩散环峰值透明度（**归零=零环=现状**——回退位） */
    const val IGNITION_RING_ALPHA = 0.55f

    /**
     * 点燃白闪包络（核心 alpha 增亮 0..[IGNITION_FLASH_BOOST]）：t=0 峰值线性衰减，
     * 窗外恒 0（一次性——点亮时刻短促一拍，窗后交回 REVEAL_FX 既有增亮；负 elapsed
     * =未点燃恒 0）。
     */
    fun ignitionFlash01(elapsedSec: Float): Float {
        if (elapsedSec < 0f) return 0f
        val k = 1f - elapsedSec / IGNITION_SEC
        return if (k <= 0f) 0f else IGNITION_FLASH_BOOST * k
    }

    /**
     * 点燃扩散环半径进度（0..1——核心→壳缘一道环；窗外恒 0=零绘制早退）。渲染端：
     * 环半径=核半径+（壳半径−核半径）×本进度。
     */
    fun ignitionRingR01(elapsedSec: Float): Float =
        if (elapsedSec < 0f || elapsedSec >= IGNITION_SEC) 0f else elapsedSec / IGNITION_SEC

    /** 点燃扩散环透明度（[IGNITION_RING_ALPHA]×(1−进度)——随扩散衰减；窗外恒 0） */
    fun ignitionRingAlpha01(elapsedSec: Float): Float =
        if (elapsedSec < 0f || elapsedSec >= IGNITION_SEC) 0f
        else IGNITION_RING_ALPHA * (1f - elapsedSec / IGNITION_SEC)

    // ==================== 行星成型贴脸拍（尘埃环凝缩增强） ====================

    /**
     * 成型坡道放缓倍率（×[TasteTagPlanets.APPEAR_RAMP] 坡道窗——出场更慢=「正在
     * 聚成」读法；**归一=1.0 现状坡道**——回退位）。批 C 接线时 [TasteTagPlanets]
     * 增带坡道参的重载（API 只加不改，现状调用面零破坏）后经 [formingRampWidth]
     * 单口消费——本批参数单源已备。
     */
    const val FORMING_RAMP_MULT = 1.8f

    /** 成型尘埃收缩比（0..1——尘埃散布向行星轨道收缩的凝缩度；**归零=现状散布**——回退位） */
    const val FORMING_DUST_CONTRACT = 0.55f

    /** 成型坡道窗（渲染端把放大后的窗喂给出场坡道计算=坡道放缓不另写公式） */
    fun formingRampWidth(baseRamp: Float): Float = baseRamp * FORMING_RAMP_MULT

    /**
     * 成型尘埃半径系数（[TasteTagPlanets.dustRadiusCoef] 的凝缩调制——**内部复用
     * 既有单口不另写公式**，纪律§一）：散布偏离量×(1−[FORMING_DUST_CONTRACT])=
     * 尘埃环收窄贴向行星轨道（「吸积盘边角料正聚成行星」的字面读法；收缩比归零=
     * 原样透传）。
     */
    fun formingDustRadiusCoef(seed: Int, idx: Int): Float =
        1f + (TasteTagPlanets.dustRadiusCoef(seed, idx) - 1f) * (1f - FORMING_DUST_CONTRACT)

    // ==================== 循环联动口（超新星遗迹×雾星胚点脉冲——批 5 件未落时空挂零行为） ====================

    /** 脉冲基值（alpha 下限——r315 K 星胚点预告版原值族） */
    const val CYCLE_PULSE_MIN = 0.05f

    /** 脉冲幅度（0.05~0.10 alpha——r315 K 原值；**归零=恒基值零脉冲**——回退位） */
    const val CYCLE_PULSE_SPAN = 0.05f

    /** 脉冲频率（Hz——慢培育节奏 ~6.7s 一拍：「死亡抛出的物质正在孕育新星」非闪灭） */
    const val CYCLE_PULSE_HZ = 0.15f

    /**
     * 循环脉冲 alpha（0.05~0.10 确定性呼吸——supernova 演出×星胚点表的联动幅度；
     * 渲染端乘星胚点基 alpha）。零随机：相位=哈希基相。
     */
    fun cyclePulseAlpha(t: Float, seed: Int): Float =
        CYCLE_PULSE_MIN + CYCLE_PULSE_SPAN * (0.5f + 0.5f *
            sin(t * 6.2831853f * CYCLE_PULSE_HZ + TasteSpace.h01(seed.toLong(), 29) * 6.2831853f))

    /**
     * 脉冲衰减包络（0..1，平方衰减=近处强远处快衰）：[x01]=归一距离（0=脉冲源心，
     * 1=窗缘外——窗宽由渲染侧世界单位定，r315 K 最近邻查询口径）**或**归一事件龄
     * （空间/时间两用同一包络口）。≥1 恒 0（窗外零成本早退）。
     */
    fun cyclePulseDecay01(x01: Float): Float {
        val x = x01.coerceIn(0f, 1f)
        return 1f - x * x
    }
}
