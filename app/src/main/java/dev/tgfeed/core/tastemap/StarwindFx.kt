package dev.tgfeed.core.tastemap

import kotlin.math.cos

/**
 * R321 星风参数**单源**（core 纯件，零 Android 依赖，JVM 单测可跑）——r315 F 章
 * （二轮概念：活跃频道吹出影响力）：**高活跃恒星向外吹物质流**——贴脸档=星风流
 * （沿自转轴两侧白色微点流）+行星尘埃带**背风密/迎风稀**不对称（真实星风-尘埃
 * 相互作用）。
 *
 * **R376 星风泡退役**（远观档外圈淡环壳 2026-10-24 正治）：用户小米截图像素
 * 实锚 36/689 颗宽场星带 6.0~8.5×r 对称淡环（旧 BUBBLE_R 系数逐位吻合）、环带
 * RGB(16,16,16)=「灰色半透明圆圈」原话字面命中、16384 探针『在场泡=24颗』——
 * 低 α 环进不了 bloom 阈值=平的灰圈（R333 判词机理），R339 宽晕退役后此件以
 * 「物质壳」白名单幸存至第五代否决。泡族四件（bubbleRadiusCoef/bubbleAlphaOf
 * 函数与 BUBBLE_R/BUBBLE_A 前缀四常量）随绘制本体退役；**星风语义不灭**：门/
 * 强度/贴脸流/尘埃不对称/whyChain 科普行全保留。
 *
 * 数据轴（r315-F①）：recent7（发布节奏）=风的强度；degree（无向连接数=互链/共现边）
 * =外流通道数——两字段全现役。**诚实红线**：无金矿证据（recent7<0）=无风（缺据
 * 不出）；星风只挂高活跃档。
 *
 * - **星风门**（[isWindy]）：`recent7≥14 ∧ degree>0` 双门——**14 从
 *   [TasteStarColor.activity01Of] 饱和口径导出**（activity01Of(recent7)≥1 ⟺
 *   recent7≥14——单一出处，**不许两处写活跃门常量**——纪律§一；渲染门/whyChain
 *   星风行同一口）。缺据（recent7<0）恒 false。
 * - **强度**（[strength01Of]）：门后梯度（recent7−14）/（[W_RECENT7_SAT]−14）钳
 *   0..1（56=8 条/天疾风档）——门上 0/56+ 满（星风流速率/尘埃不对称消费单源）。
 * - **星风流**（[streamRateOf]/[STREAM_SPREAD_PX]/[STREAM_SLOTS]）：焦点件——沿
 *   恒星自转轴（[windAxisRad] 确定性哈希轴——白洞喷流轴同族手法）**两侧** 2~4
 *   微粒/s 白色微点，飘 ~80px 渐散；粒子=纯 t 函数（[streamPhase01] 槽相位——
 *   零常驻状态零分配）；8 槽并发帽。
 * - **尘埃不对称**（[dustAsym01]/[dustKeep01]）：渲染期相位密度偏移一项——迎风侧
 *   （相位差=π）keep=1−asym 稀/背风侧（相位差=0）keep=1 密；asym ≤[DUST_ASYM_MAX]
 *   （±0.35 帽防撕碎轨道可读性——r315-F⑤ 单测锚）。
 *
 * 工程纪律：全函数纯浮点零分配、确定性（零 Random 零系统钟）。
 */
object StarwindFx {

    /** 星风疾风饱和档（条/周——8 条/天：强度归一上限） */
    const val W_RECENT7_SAT = 56

    /** 星风流微点飘距（px——渐散距离；r315-F②「飘 ~80px」字面） */
    const val STREAM_SPREAD_PX = 80f

    /** 星风流并发槽帽（8——帧预算护栏；r315-F④「粒子上限 8 并发帽」） */
    const val STREAM_SLOTS = 8

    /** 星风流速率下限（微粒/s） */
    const val STREAM_RATE_MIN = 2f

    /** 星风流速率上限（微粒/s） */
    const val STREAM_RATE_MAX = 4f

    /** 星风流速率（2~4 微粒/s——∝强度单调） */
    fun streamRateOf(strength01: Float): Float =
        STREAM_RATE_MIN + (STREAM_RATE_MAX - STREAM_RATE_MIN) * strength01.coerceIn(0f, 1f)

    /** 星风流微点尺寸（px——白点微光） */
    const val STREAM_DOT_PX = 1.3f

    /**
     * 尘埃不对称帽（0.35——**±0.35 帽防撕碎轨道可读性**；r315-F⑤ 单测锚字面）。
     */
    const val DUST_ASYM_MAX = 0.35f

    /**
     * 星风门（双门：recent7≥14 ∧ degree>0——**14 从 [TasteStarColor.activity01Of]
     * 饱和口径导出**：activity01Of(recent7)≥1f ⟺ recent7≥14，单一出处零第二常量；
     * 渲染门/whyChain 星风行/探针同一口）。缺据（recent7<0→activity01=0）恒 false
     * =无风不出（诚实红线）。
     */
    fun isWindy(recent7: Int, degree: Int): Boolean =
        TasteStarColor.activity01Of(recent7) >= 1f && degree > 0

    /**
     * 星风强度（门后梯度 0..1：(recent7−14)/(56−14) 钳——门上恒 0，疾风档满 1；
     * recent7<14（门外）恒 0）。
     */
    fun strength01Of(recent7: Int): Float {
        if (recent7 <= 14) return 0f
        return ((recent7 - 14f) / (W_RECENT7_SAT - 14f)).coerceIn(0f, 1f)
    }

    // R376 星风泡族退役位：bubbleRadiusCoef/bubbleAlphaOf（BUBBLE_R_MIN=6.0/
    // BUBBLE_R_MAX=8.5/BUBBLE_A_MIN=0.04/BUBBLE_A_MAX=0.09 四常量随删）——退役
    // 判词见类 KDoc「R376 星风泡退役」段（用户小米像素实锚 36/689 颗 6.0~8.5×r
    // 灰白环+半径比代码指纹逐位吻合+在场 24 颗探针互证）。

    /**
     * 恒星自转轴投影角（rad——确定性哈希轴；星风流两侧吹出方向+尘埃不对称的迎风
     * 轴同一出处。seed=星 chatId 桶——白洞喷流轴 whiteholeJetAngleDeg 同族手法）。
     */
    fun windAxisRad(seed: Int): Float =
        TasteStarColor.h01(seed, 901) * 6.2831853f

    /**
     * 星风流槽相位（0..1——粒子=纯 t 函数零常驻状态：槽 k 的循环相位
     * frac(t×rate/[STREAM_SLOTS]+k/8+哈希错相)；[ratePerSec]=[streamRateOf] 强度档
     * （发粒速率 2~4/s=8 槽×每槽 cycle 1/rate——强度大风吹得快）；[streamAlive01]
     * 判活性）。
     */
    fun streamPhase01(t: Float, ratePerSec: Float, seed: Int, k: Int): Float {
        var p = t * ratePerSec / STREAM_SLOTS + k.toFloat() / STREAM_SLOTS +
            TasteStarColor.h01(seed, k * 31 + 911)
        p -= p.toInt()
        return p
    }

    /**
     * 星风流槽活性（粒子漂移窗内=1→0 线性衰减；窗外恒 0=零绘制零成本早退）。
     * 活性窗=槽周期 ×[STREAM_LIVE_FRAC]（漂移 ~[STREAM_SPREAD_PX] 渐散的时长占比）。
     */
    const val STREAM_LIVE_FRAC = 0.55f

    fun streamAlive01(phase01: Float): Float {
        val live = STREAM_LIVE_FRAC
        if (phase01 >= live) return 0f
        return (1f - phase01 / live).coerceIn(0f, 1f)
    }

    /** 尘埃不对称系数（0~[DUST_ASYM_MAX]——∝星风强度单调；无风恒 0） */
    fun dustAsym01(strength01: Float): Float =
        DUST_ASYM_MAX * strength01.coerceIn(0f, 1f)

    /**
     * 尘埃粒保留概率（密度偏移——渲染期逐粒确定性判死活）：背风侧（[phaseDelta]=0
     * 风把尘埃吹到的下风侧）keep=1（密）/迎风侧（=π 迎着风头）keep=1−asym（稀）；
     * 域 [1−asym, 1]⊂[0.65, 1]（**±0.35 帽内**——轨道迹线可读性不撕碎）。
     * asym=0 恒 1=现状零改。
     */
    fun dustKeep01(phaseDelta: Float, asym: Float): Float {
        val a = asym.coerceIn(0f, DUST_ASYM_MAX)
        if (a <= 0f) return 1f
        val windward = 0.5f - 0.5f * cos(phaseDelta) // 迎风权重（Δ=π→1 稀/Δ=0→0 密）
        return 1f - a * windward
    }
}
