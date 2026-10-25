package dev.tgfeed.core.tastemap

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * R303 多尺度涌现星图 P0——**在线活层纯函数件**（design-multiscale-emergent §6.5 条款二
 * 「活层有界且保形」+§6.4 不坍缩机制一「角动量（绕而不撞）」的落法；纯函数零 Android
 * 依赖，JVM 单测锚定）。
 *
 * 本件职责（与布局场的分野=「双场分离」第一纪律）：
 *  - **布局场**（TasteEmergentLayout）管「该在哪」：离线阻尼弛豫求收敛；
 *  - **演艺场活层**（本件）管「活着」：在线逐帧的位置微运动——**刚体旋转**（全星系
 *    同角速度绕银心自转，两两距离数学恒等式保持=涌现的语义结构永不被剪切）+
 *    **锚弹簧微扰**（每星绕平衡位的确定性有界漂移——星是活的，但永不离开家）。
 *
 * 为什么刚体旋转是「不坍缩」的机制一：角动量守恒由构造保证（旋转支持对抗向心收缩，
 * 与真实旋涡星系同原理）；而现役差速旋转曲线（内圈快外圈慢）会把涌现簇结构数小时
 * 拧碎（差速剪切语义磨损=预存挂账，本条款是它的根治——行为变更设计已注明待终审，
 * 本件只产纯函数核，接线属 P5）。
 *
 * 红线条款一保持：四力只在离线/温启运行；本件**零力积分**——刚体旋转是运动学变换，
 * 微扰是确定性相位函数，都不积力。
 */
object TasteEmergentLive {

    /** 银河刚体自转角速度（度/实分钟，§6.5 条款二：Ω≈0.5°/分钟——「银河在转」肉眼难察但可积） */
    const val OMEGA_DEG_PER_MIN = 0.5f

    /** 锚弹簧微扰振幅上限（世界单位，§6.5：≤40≈近邻距 6%——保形边界） */
    const val PERTURB_AMP = 40f

    /** 锚弹簧微扰时标（秒，§6.5：~30 分钟一个呼吸周期） */
    const val PERTURB_PERIOD_SEC = 1800f

    /**
     * 刚体旋转一步（绕「过质心的 Y 轴」转 [omegaDegPerMin]·dtMin——保距变换：
     * 任意两星距离旋转前后严格相等，是保形的数学恒等式锚）。
     *
     * @param cx/cz 质心（银心井位口径——P0=原点；有中心黑洞时调用方传井位）
     */
    fun rigidRotateY(
        n: Int, x: FloatArray, y: FloatArray, z: FloatArray,
        cx: Float, cz: Float, omegaDegPerMin: Float, dtMin: Float,
    ) {
        if (omegaDegPerMin == 0f || dtMin == 0f) return
        val ang = Math.toRadians((omegaDegPerMin * dtMin).toDouble()).toFloat()
        val c = cos(ang)
        val s = sin(ang)
        for (i in 0 until n) {
            val dx = x[i] - cx
            val dz = z[i] - cz
            x[i] = cx + dx * c + dz * s
            z[i] = cz - dx * s + dz * c
        }
    }

    /**
     * 锚弹簧微扰（确定性相位函数——零状态零积分）：星 i 在 tSec 时刻相对平衡位的
     * 偏移 = 振幅分位·哈希方向·sin(2π·t/T + 哈希相位)。全确定性（chatId 哈希——
     * 同数据同宇宙不破）；|偏移| ≤ [PERTURB_AMP] 恒成立（保形边界由构造保证）。
     *
     * @param out 长 ≥3，写 (dx,dy,dz)
     */
    fun anchorPerturb(chatId: Long, tSec: Float, out: FloatArray) {
        // 振幅分位 0.4~1.0×PERTURB_AMP（哈希——各星呼吸幅度各异但全部有界）
        val amp = PERTURB_AMP * (0.4f + 0.6f * TasteSpace.h01(chatId, 301))
        val phase = TasteSpace.h01(chatId, 302) * (2f * PI.toFloat())
        // 哈希方向（单位向量——方位/俯仰双哈希）
        val az = TasteSpace.h01(chatId, 303) * (2f * PI.toFloat())
        val el = (TasteSpace.h01(chatId, 304) - 0.5f) * PI.toFloat()
        val w = sin(2f * PI.toFloat() * (tSec / PERTURB_PERIOD_SEC) + phase) * amp
        val ce = cos(el)
        out[0] = w * ce * cos(az)
        out[1] = w * sin(el)
        out[2] = w * ce * sin(az)
    }

    /** 微扰位移模上界（恒等式锚：|Δ| ≤ [PERTURB_AMP]——单测断全时间轴） */
    fun perturbBound(): Float = PERTURB_AMP

    /**
     * 两两距离恒等式校验（测试锚——刚体旋转保形的直接证词）：
     * 返回旋转前后采样两两距离的最大绝对偏差（应 ≤ 浮点噪声 ~1e-2）。
     */
    fun maxPairDistDeviation(
        n: Int, x0: FloatArray, y0: FloatArray, z0: FloatArray,
        x1: FloatArray, y1: FloatArray, z1: FloatArray, samples: Int = 2000,
    ): Float {
        var worst = 0f
        var seed = 0x9E3779B97F4A7C15uL.toLong() // 黄金分割哈希（Kotlin 十六进制字面量不越 Long——无符号落笔再转）
        fun rnd(): Int {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 33) and 0x7FFFFFFFL).toInt()
        }
        repeat(samples) {
            val a = rnd() % n
            val b = rnd() % n
            if (a == b) return@repeat
            val d0 = dist(x0[a], y0[a], z0[a], x0[b], y0[b], z0[b])
            val d1 = dist(x1[a], y1[a], z1[a], x1[b], y1[b], z1[b])
            val dev = kotlin.math.abs(d1 - d0)
            if (dev > worst) worst = dev
        }
        return worst
    }

    private fun dist(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val dz = bz - az
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
