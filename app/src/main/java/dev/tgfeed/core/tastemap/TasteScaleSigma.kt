package dev.tgfeed.core.tastemap

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin

/**
 * R303c P3——**σ 主尺度坐标统一件**（design-multiscale-emergent §5.1/§5.2 终审稿，
 * 拍板项⑥乘法缩放的配套；纯函数零 Android 依赖，JVM 单测锚定）。
 *
 * 定义唯一尺度坐标 **σ = log2(d / 260)**（260=现役停船锚 CRUISE_STOP_MAX；σ=0 即
 * 贴脸档；每 +1 距离翻倍）。**一切层的显隐都是 σ 上的连续函数**——离散档位只是
 * σ 曲线的采样读数，从机制上消灭「跳变」这个概念（§5.1 原文）。
 *
 * 三铁律（§5.2，单测钉死）：
 *  1. **窗口必交叠**——任何 σ 处至少一件全盛（无真空带：L0~L4 六层窗口表扫过
 *     σ∈[−1, 6] 逐 0.05 步长断言）；
 *  2. **过渡段双件同画**——层淡出窗与邻层淡入窗交叠（cross-dissolve：旧件淡出+
 *     新件淡入，双画开销有界）；
 *  3. **C0 连续**——smoothstep 端点值精确相等（淡入起点 alpha=0/全盛起点=1/
 *     淡出终点=0，单测逐端点断言）。
 *
 * **渲染换轨挂账（诚实范围管理）**：本程落 σ 件+窗口表+单测锚（LOD 咨询件——
 * 井深比 LOD 换轨 σ 需专程一轮+真机档，不冒进动渲染防 60fps 回退）；银河视角
 * 运镜（[galaxyViewPose]）已真接线。
 */
object TasteScaleSigma {

    /** 停船锚距离（世界单位，σ=0）：与 GestureFeelPolicy.CRUISE_STOP_MAX 同值
     *  ——现役停船语义的采样原点（同源常量收口注释互指，不许第二处数字） */
    const val DOCK_DIST = 260f

    /** σ 采样下界（=窗口表最低淡入起点 −0.9：L0 尘埃档之下即表界——无真空带
     *  的扫描下沿铁律配套） */
    const val SIGMA_MIN = -0.9f

    /** σ 采样上界（L4 银河位 σ=log2(7000/260)≈4.75，留余量到 6） */
    const val SIGMA_MAX = 6f

    // ==================== 主尺度坐标（§5.1 一条连续轴管所有层） ====================

    /** σ = log2(d / DOCK_DIST)（d≤0 防御=σ_MIN 钳；d=DOCK_DIST 精确 σ=0=贴脸档） */
    fun sigmaOf(d: Float): Float =
        if (d <= 0f) SIGMA_MIN else (log2(d / DOCK_DIST)).coerceIn(SIGMA_MIN, SIGMA_MAX)

    /** 逆变换：d = DOCK_DIST·2^σ（运镜/手势的目标距离解算） */
    fun distOf(sigma: Float): Float = DOCK_DIST * 2f.pow(sigma.coerceIn(SIGMA_MIN, SIGMA_MAX))

    // ==================== 层窗口表（§5.2 五档尺度 + 银河视角） ====================

    /**
     * LOD 层（§5.2 窗口表落地+**铁律①优先微调**：层淡出起点=继层淡入完成点
     * （设计表原值 DUST 0.2/PLANETS_FULL 1.8/LITE 2.8/GLYPH 4.4 起淡会在交叠区
     * 留下「无全盛层」真空带——铁律①「任何 σ 至少一件全盛」是硬约束，表内
     * 过渡带笔误以铁律为准，微调记录在案）；L0~L4 与 §4.1 五档尺度表同源。
     * 每层三窗（σ 区间）：淡入 [fadeInIn, fadeInOut] / 全盛 [fadeInOut, fadeOutIn] /
     * 淡出 [fadeOutIn, fadeOutOut]。
     */
    enum class Layer(
        val fadeInIn: Float,
        val fadeInOut: Float,
        val fadeOutIn: Float,
        val fadeOutOut: Float,
    ) {
        /** L0 内容表面：小行星多面体/尘埃带（σ≤0.6 全盛；淡入窗沉到表界之下
         *  ——贴脸方向无更内层可交接，尘埃带在 σ 表界即满场：铁律① floor 保障） */
        DUST(-1.5f, -0.9f, 0.6f, 1.2f),

        /** L1 行星际：行星全编（top15+轨道线） */
        PLANETS_FULL(0f, 0.6f, 2.0f, 2.6f),

        /** L2 恒星系（过渡简编）：行星 top5 */
        PLANETS_LITE(1.4f, 2.0f, 3.0f, 3.6f),

        /** L2/L3 恒星系符号（系=一个带系辉光点） */
        SYSTEM_GLYPH(2.4f, 3.0f, 3.8f, 5.0f),

        /** L3 星团浓核/互链光丝（常显不淡——淡出端 Inf=无淡出） */
        CLUSTER_LINKS(3.2f, 3.8f, 6f, Float.POSITIVE_INFINITY),

        /** L4 银河背景（旋臂图/银心亮核；常显） */
        GALAXY_BG(4.0f, 4.6f, 6f, Float.POSITIVE_INFINITY),
    }

    /** smoothstep（C0 连续：端点值精确 0/1——铁律③的机械保证） */
    private fun smooth01(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /**
     * 层在场透明度（σ 的连续函数，§5.1「一切层的显隐都是 σ 上的连续函数」）：
     * 淡入窗 0→1 / 全盛窗恒 1 / 淡出窗 1→0 / 窗外恒 0。常显层（淡出端=Inf）在
     * 全盛起点后恒 1（σ 恒 ≤[SIGMA_MAX]，Inf 端永不触达=永不淡出）。
     */
    fun alphaOf(layer: Layer, sigma: Float): Float = when {
        sigma <= layer.fadeInIn -> 0f
        sigma < layer.fadeInOut -> smooth01((sigma - layer.fadeInIn) / (layer.fadeInOut - layer.fadeInIn))
        sigma <= layer.fadeOutIn -> 1f
        sigma >= layer.fadeOutOut -> 0f
        else -> 1f - smooth01((sigma - layer.fadeOutIn) / (layer.fadeOutOut - layer.fadeOutIn))
    }

    /**
     * 无真空带断言（铁律①的机检口——单测与渲染换轨期共用）：
     * σ∈[fadeInOut, fadeOutIn]（全盛窗）内至少一层的层在 [sigma, sigma+step] 扫过。
     */
    fun anyLayerFullAt(sigma: Float): Boolean {
        for (l in Layer.entries) {
            if (sigma >= l.fadeInOut && sigma <= l.fadeOutIn) return true
        }
        return false
    }

    // ==================== 银河视角运镜（§7.2 L4 的仪式性入口） ====================

    /** 银河视角银心距（世界单位，§7.2「退到银心距 7000」；=R_GALAXY+1000 观礼位） */
    const val GALAXY_VIEW_DIST = 7000f

    /** 银河视角俯角（度，§7.2「俯角 25° 看全银河」） */
    const val GALAXY_VIEW_PITCH_DEG = 25f

    /** 银河视角运镜时间常数（秒——与 CRUISE_TAU_SEC 同族电影感长运镜） */
    const val GALAXY_VIEW_TAU_SEC = GestureFeelPolicy.CRUISE_TAU_SEC

    /**
     * 银河视角位姿（银心距 7000/俯角 25°/瞄准银心=世界原点——L4 仪式性入口的
     * 目标 pose；消费方=TasteMapScreen 双击空白触发，经 focusFlight 位姿模式缓动
     * 到位）。与 TasteEmergentLayout.standardCams 银河位同一口径（本口=单一出处，
     * standardCams 委托此处——同一距离语义不许两处常量）。
     */
    fun galaxyViewPose(): TasteSpace.SpaceCam {
        val pitch = GALAXY_VIEW_PITCH_DEG * PI.toFloat() / 180f
        val gx = 0f
        val gy = GALAXY_VIEW_DIST * sin(pitch)
        val gz = -GALAXY_VIEW_DIST * cos(pitch)
        val aim = TasteSpace.aimAt(TasteSpace.SpaceCam(gx, gy, gz, 0f, 0f), 0f, 0f, 0f, floatArrayOf(0f, 0f))
        return TasteSpace.SpaceCam(gx, gy, gz, aim[0], aim[1])
    }
}
