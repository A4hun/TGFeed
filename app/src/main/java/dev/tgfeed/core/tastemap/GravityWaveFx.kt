package dev.tgfeed.core.tastemap

import kotlin.math.exp
import kotlin.math.hypot

/**
 * R320 引力波涟漪**波前数学单源**（core 纯件，零 Android 依赖，JVM 单测可跑——
 * r315 E 章「时空涟漪」的字面落地；TasteTerminalFx/CmbBackdropFx 同模式）：
 * Canvas（TasteFlightRenderer）与 GL（GlFrameBuilder）双端同式的机制保障
 * （渲染端只画不算，公式一处改两端生效——纪律§一类问题收敛）。
 *
 * **波前模型**（r315 E② 原文口径）：
 *  - 事件星屏幕投影位为心，半径 r(t)=c_wave×(t−t0)（[C_WAVE_PX_SEC]≈420px/s）；
 *  - 环带宽 ~[BAND_PX]（屏距 ∈ [r−30,r+30] 的星被扫到——早退域=|屏距−r|>3σ）；
 *  - 径向位移 = A×exp(−(屏距−r)²/2σ²)×e^(−t/τ)（**A≤[AMP_PX_MAX]=6px 帽**=「不违和
 *    克制」关；τ=[REBOUND_TAU_SEC]=1.2s——波随传播衰减=被扫过的星推移后自然回弹
 *    「一次性脉冲非持续位移」；高斯剖面对称=星在前沿抵达→推移→离开的全过程）；
 *  - 位移方向=离波心径向（波前推着星向外让位，前沿过后星回位——引力波探测器
 *    「测星点位置抖动」的 LIGO 原理观感）。
 *
 * **档位两档**（触发源=导演器既有事件，r315 E④ 触发链）：
 *  - **涟漪档**（超新星演出第二视觉层——「同 Directive」字面）：经 FxEngine
 *    flashStar TTL 门（[RIPPLE_FLASH_TTL_SEC]=超新星组曲 7.0s）入账，
 *    幅度 [RIPPLE_AMP_PX]=4.0px；
 *  - **宣判档**（终态宣判事件——质量更大的事件=更强的波）：GRAVITY_WAVE 指令
 *    （[VERDICT_FLASH_TTL_SEC]=9.0s）→幅度 [VERDICT_AMP_PX]=6.0px（=1.5× 涟漪档
 *    ——物理诚实）。NOVA 复燃（4.5s）/舷窗欢迎（3.5s）/彗星爆闪（2.5s）恒不入门
 *    （[FLASH_GATE_SEC]=5.0——复燃不是质量剧变）。
 *
 * **与 R327 的跃迁入队口对接**（r325 C 章「Directive 参数位承载新星点燃涟漪」）：
 * 导演器 Directive 的 waveAmpPx/waveLifeSec 尾参由本件出值（[rippleAmpPxOf]/
 * [verdictAmpPxOf]/[waveLifeSecOf]）——诞生侧跃迁 diff 入队走 GRAVITY_WAVE 档即
 * 自动获得同族不同强度档的涟漪面。
 *
 * 确定性：全部公式零 Random 零系统钟——同帧同参同位移（R 系铁律）。
 */
object GravityWaveFx {

    // ==================== 波前传播参数（r315 E② 原值族） ====================

    /** 波前传播速度（px/s≈420——r315 E 原值；2~4s 掠过全屏量级的定标） */
    const val C_WAVE_PX_SEC = 420f

    /** 环带宽（px，±30——被扫到的星的屏距带；早退域=带宽 1.5 倍） */
    const val BAND_PX = 30f

    /** 高斯剖面 σ（px）——环带宽的一半（带宽 ±30=±1.5σ 的剖面宽度） */
    const val PROFILE_SIGMA_PX = 15f

    /** 弹簧回弹衰减 τ（秒——波随传播的整体衰减；1.2s=两倍 τ 内衰减 86%） */
    const val REBOUND_TAU_SEC = 1.2f

    /** 位移帽（px——A≤6 设计红线：肉眼可见的克制，不抢戏不违和） */
    const val AMP_PX_MAX = 6f

    /** 活跃波前并发帽（节奏闸管并发——r315 E②「活跃波前 ≤2」） */
    const val WAVE_CAP = 2

    /** 波前寿命下限（秒——2.6~4.0s 掠过后自然消散） */
    const val LIFE_MIN_SEC = 2.6f

    /** 波前寿命跨度（秒——寿命=下限+跨度×强度档） */
    const val LIFE_SPAN_SEC = 1.4f

    // ==================== 档位（flashStar TTL 门——演出级闪光独占） ====================

    /** 演出级闪光门（秒）：≥此值的 flashStar 才入波前账（超新星 7.0/宣判 9.0 入；
     *  NOVA 4.5/舷窗欢迎 3.5/彗星爆闪 2.5 恒不入——复燃不是质量剧变=物理诚实） */
    const val FLASH_GATE_SEC = 5.0f

    /** 超新星指令闪光 TTL（秒——导演器组曲既有值，档位映射的镜像位：改就一起改） */
    const val RIPPLE_FLASH_TTL_SEC = 7.0f

    /** GRAVITY_WAVE（宣判档）指令闪光 TTL（秒——本批 directiveOf 定档） */
    const val VERDICT_FLASH_TTL_SEC = 9.0f

    /** 涟漪档幅度（px——超新星演出的第二视觉层） */
    const val RIPPLE_AMP_PX = 4.0f

    /** 宣判档幅度倍率（1.5×——质量更大的事件=更强的波，r315 E④ 原值） */
    const val VERDICT_AMP_MUL = 1.5f

    /** 宣判档幅度（px=RIPPLE_AMP_PX×1.5=帽值——黑洞宣判事件的涟漪面） */
    const val VERDICT_AMP_PX = RIPPLE_AMP_PX * VERDICT_AMP_MUL

    /**
     * flashStar TTL → 波前入账判定（演出级闪光门）：true=该闪光代表一次质量剧变
     * 事件（超新星/GRAVITY_WAVE 档），渲染端应起一圈波前。
     */
    fun isPerformFlashTtl(ttlSec: Float): Boolean = ttlSec >= FLASH_GATE_SEC

    /**
     * flashStar TTL → 波前幅度档（px）：≥[VERDICT_FLASH_TTL_SEC] 判宣判档
     * （黑洞宣判=6.0px），否则涟漪档（超新星演出=4.0px）；未过门=0（不入账）。
     * TTL 域锚：超新星组曲 7.0∈[门,宣判阈)、GRAVITY_WAVE 组曲 9.0≥宣判阈。
     */
    fun ampPxOfFlashTtl(ttlSec: Float): Float {
        if (!isPerformFlashTtl(ttlSec)) return 0f
        return if (ttlSec >= VERDICT_FLASH_TTL_SEC) VERDICT_AMP_PX else RIPPLE_AMP_PX
    }

    /** flashStar TTL → 波前寿命（秒）：宣判档 3.8（更强的波传更久）/涟漪档 3.0 */
    fun lifeSecOfFlashTtl(ttlSec: Float): Float =
        if (ttlSec >= VERDICT_FLASH_TTL_SEC) LIFE_MIN_SEC + 1.2f else LIFE_MIN_SEC + 0.4f

    // ==================== 导演器档位参数位（Directive 波前尾参出值——R327 对接口） ====================

    /** 涟漪档幅度（px，intensity01 缩放 0.55~1.0×——波前速度/幅度随强度缩放） */
    fun rippleAmpPxOf(intensity01: Float): Float =
        (RIPPLE_AMP_PX * (0.55f + 0.45f * intensity01.coerceIn(0f, 1f))).coerceAtMost(AMP_PX_MAX)

    /** 宣判档幅度（px，intensity01 缩放 0.55~1.0×，帽 [AMP_PX_MAX]——物理诚实+克制） */
    fun verdictAmpPxOf(intensity01: Float): Float =
        (VERDICT_AMP_PX * (0.55f + 0.45f * intensity01.coerceIn(0f, 1f))).coerceAtMost(AMP_PX_MAX)

    /** 波前寿命（秒=2.6+1.4×intensity01——越强的波掠得越远） */
    fun waveLifeSecOf(intensity01: Float): Float =
        LIFE_MIN_SEC + LIFE_SPAN_SEC * intensity01.coerceIn(0f, 1f)

    // ==================== 波前位移场（渲染期逐星/逐网格顶点求值——双端同式） ====================

    /**
     * 波前当前半径（px）：r(t)=c_wave×ageSec——**ageSec 钳 [0, 寿命]**（防陈波
     * 位移场发散；寿命到=账本侧回收+本函数域钳双保险）。半径随龄**单调不减**。
     */
    fun waveRadiusPx(ageSec: Float, lifeSec: Float): Float =
        (C_WAVE_PX_SEC * ageSec.coerceIn(0f, lifeSec.coerceAtLeast(0f)))

    /**
     * 波前位移（写 [out] 长 ≥2：dx/dy 屏坐标偏移；返回 false=零位移）：
     *  - 早退域：|屏距−波前半径| > 带宽 1.5 倍（±45px=±3σ）→ 零位移（绝大多数星
     *    一次距离平方+比较即出——透镜同款零成本先例）；
     *  - 高斯剖面：exp(−(屏距−r)²/2σ²)（对称脉冲=星被推离后随前沿离开回位）；
     *  - 时间衰减：×exp(−ageSec/τ)（波随传播整体衰减=推移量弹簧回弹到零）；
     *  - 位移方向=离波心径向单位向量 ×幅度（帽 [AMP_PX_MAX] 终钳）。
     * 波心=事件星当帧屏幕投影位（调用方预扫得出——「以事件星屏幕投影位为心」）；
     * 波心不可用（近裁后/出屏）=调用方直接跳过本函数（诚实缺省）。
     */
    fun displaceAt(
        px: Float,
        py: Float,
        cx: Float,
        cy: Float,
        waveR: Float,
        ageSec: Float,
        ampPx: Float,
        out: FloatArray,
    ): Boolean {
        out[0] = 0f
        out[1] = 0f
        if (ampPx <= 0f || waveR <= 0f) return false
        val dx = px - cx
        val dy = py - cy
        val dist = hypot(dx, dy)
        if (dist < 1e-4f) return false // 波心星自身（位移方向退化——原地震荡交给爆闪层）
        val off = dist - waveR
        if (off > BAND_PX * 1.5f || off < -BAND_PX * 1.5f) return false // 域外早退
        val profile = exp(-(off * off) / (2f * PROFILE_SIGMA_PX * PROFILE_SIGMA_PX))
        if (profile < 1e-3f) return false
        val decay = exp(-ageSec.coerceAtLeast(0f) / REBOUND_TAU_SEC)
        val amp = (ampPx * profile * decay).coerceAtMost(AMP_PX_MAX)
        if (amp < 0.05f) return false
        val inv = amp / dist
        out[0] = dx * inv
        out[1] = dy * inv
        return true
    }

    /**
     * 波心预扫出屏侧判（渲染帧首对 ≤[WAVE_CAP] 个波源星投影一次）：投影失败/在屏外
     * 超出带宽余量 → 该帧波位移零输出（波源星=事件星「以事件星屏幕投影位为心」——
     * 事件星不在视野内时波心无定义=诚实缺省，星点静止直到它回到屏内）。
     * [margin]=带宽 1.5 倍（波心在屏缘外一点点时屏内星仍可能被扫到）。
     */
    fun originOnScreen(sx: Float, sy: Float, w: Float, h: Float): Boolean {
        val m = BAND_PX * 1.5f
        return sx > -m && sx < w + m && sy > -m && sy < h + m
    }
}
