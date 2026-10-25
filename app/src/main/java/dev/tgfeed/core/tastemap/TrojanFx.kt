package dev.tgfeed.core.tastemap

import kotlin.math.sqrt

/**
 * R321 拉格朗日点特洛伊簇参数**单源**（core 纯件，零 Android 依赖，JVM 单测可跑）
 * ——r315 I 章：双星连线等边三角形第三顶点（L4/L5）处的微亮点群=两频道**共同
 * 上游**内容（「A 转自 X 且 B 也转自 X」的共源尘埃）驻在引力平衡点的数据可视化。
 *
 * Canvas（TasteFlightRenderer）与 GL（GlFrameBuilder）双端同式（TasteTerminalFx
 * 同模式）：**每帧从两星当帧位置导出**（N 体渲染位——R249b 双星互转同款口径，
 * 簇随绕转刚性跟随零独立时序源）；焦点件（选中/锁定双星时才渲染——全景零成本）。
 *
 * - **L4/L5 几何**（[lPointOf]）：等边三角形第三顶点=中点±√3/2×|AB|×面内垂线
 *  （垂线=世界 up(0,1,0) 投影到 ⊥AB 平面——退化（AB∥up）回落 (0,0,1)；确定性
 *   纯函数）。|CA|=|CB|=|AB| 恒成立（单测几何锚——「尘埃簇相对两星位置恒等边
 *   三角形」的逐帧断言原料）。
 * - **共源判定**（[sharedUpstreamWeight]）：DiscoveryGraph `forward_src` 边上游
 *   交集——X∈up(A)∩up(B) 上 Σ min(count(A→X), count(B→X))（两侧都搬运过的量=
 *   交集语义最诚实）。**无共源=零渲染**（诚实红线——拉格朗日点不是每对双星都有
 *   货的驿站，有货没货数据说话）。
 * - **簇粒数**（[particleCountOf]）：3~9 粒（0=共源缺据零渲染）；**亮度**
 *   （[brightness01Of]）∝共源内容量归一（ln1p 口径——BinaryStarPolicy 维度分
 *   同哲学，重尾压平）。
 * - **粒子抖动**（[particleOf]）：L 点面内（u=⊥AB 垂线轴/v=AB 轴）±[CLUSTER_SPREAD]
 *   ×|AB| 确定性哈希散布——双端同式（TasteStarColor.h01 公共单源——与渲染端
 *   galaxyH01 同式）。
 *
 * 工程纪律：全函数纯浮点（out 数组零分配）、确定性（零 Random 零系统钟）。
 */
object TrojanFx {

    /** 簇粒数下限（3~9 微亮点——r315-I②「一小撮」克制） */
    const val CLUSTER_MIN = 3

    /** 簇粒数上限（帽 9——r315-I⑤ 单测锚） */
    const val CLUSTER_MAX = 9

    /** 粒子面内散布（|AB| 分数——L 点周围的尘埃云尺度） */
    const val CLUSTER_SPREAD = 0.09f

    /** 共源量饱和口径（W≥12 条共源证据=满亮度——ln1p 归一分母） */
    const val W_SATURATION = 12

    /**
     * L4/L5 顶点（世界坐标）——等边三角形第三顶点：中点±√3/2×|AB|×n̂（n̂=⊥AB
     * 面内垂线）。写 [out]（长 ≥9）：[0..2]=L 点位/[3..5]=u 轴（n̂，粒子抖动轴 1）
     * /[6..8]=v 轴（AB 单位向，抖动轴 2）。[sign]=+1（L4）/−1（L5）。
     * 病态输入（|AB|<1e-4）恒返 A 位（退化安全）。
     */
    fun lPointOf(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        sign: Float,
        out: FloatArray,
    ) {
        val mx = (ax + bx) * 0.5f
        val my = (ay + by) * 0.5f
        val mz = (az + bz) * 0.5f
        val dx = bx - ax
        val dy = by - ay
        val dz = bz - az
        val sep = sqrt(dx * dx + dy * dy + dz * dz)
        if (sep < 1e-4f) {
            out[0] = ax; out[1] = ay; out[2] = az
            out[3] = 0f; out[4] = 1f; out[5] = 0f
            out[6] = 0f; out[7] = 0f; out[8] = 1f
            return
        }
        // 面内垂线：世界 up(0,1,0)×AB=(dz, 0, −dx)（⊥AB 的面内向）；|·|≈0（AB∥up）
        // 回落 z 轴（AB 主沿 y 时 z 轴分量必非零——退化安全确定性）
        var nx = dz
        var ny = 0f
        var nz = -dx
        var nl = sqrt(nx * nx + ny * ny + nz * nz)
        if (nl < 1e-3f * sep) {
            nx = 0f; ny = 0f; nz = 1f
            nl = 1f
        }
        nx /= nl; ny /= nl; nz /= nl
        val h = sqrt(3f) * 0.5f * sep
        out[0] = mx + nx * h * sign
        out[1] = my + ny * h * sign
        out[2] = mz + nz * h * sign
        out[3] = nx; out[4] = ny; out[5] = nz
        out[6] = dx / sep; out[7] = dy / sep; out[8] = dz / sep
    }

    /**
     * 共源上游权重（装配 IO 期——DiscoveryGraph forward_src 边上游交集）：
     * [upA]/[upB]=A/B 的 forward_src 上游账（上游 chatId→边计数）。X∈交集上
     * Σ min(countA, countB)（两侧都搬运过的量）；空交/空账=0（**无共源=零尘埃**
     * 不冒充驿站）。
     */
    fun sharedUpstreamWeight(upA: Map<Long, Int>, upB: Map<Long, Int>): Int {
        if (upA.isEmpty() || upB.isEmpty()) return 0
        var w = 0
        for ((x, ca) in upA) {
            val cb = upB[x] ?: continue
            w += minOf(ca, cb)
        }
        return w
    }

    /**
     * 簇粒数（[CLUSTER_MIN]~[CLUSTER_MAX]，∝共源量归一单调）；W≤0（无共源）恒 0
     * =零渲染（域外零成本——r315-I⑤ 单测锚）。
     */
    fun particleCountOf(trojanW: Int): Int {
        if (trojanW <= 0) return 0
        val b01 = brightness01Of(trojanW)
        return (CLUSTER_MIN + ((CLUSTER_MAX - CLUSTER_MIN) * b01).toInt())
            .coerceIn(CLUSTER_MIN, CLUSTER_MAX)
    }

    /** 亮度归一（0..1，ln1p 口径——亮度∝共源内容量归一；W≤0 恒 0） */
    fun brightness01Of(trojanW: Int): Float {
        if (trojanW <= 0) return 0f
        val w = trojanW.toFloat()
        return (kotlin.math.ln1p(w) / kotlin.math.ln1p(W_SATURATION.toFloat()))
            .coerceIn(0f, 1f)
    }

    /**
     * 簇粒子世界位（L 点+面内确定性抖动）：[out]（长 ≥3）=L+u×jx×spread+v×jy×
     * spread（spread=[CLUSTER_SPREAD]×[sep]；jx/jy=h01 哈希 ±0.5——双端同式）。
     */
    fun particleOf(
        lx: Float, ly: Float, lz: Float,
        ux: Float, uy: Float, uz: Float,
        vx: Float, vy: Float, vz: Float,
        sep: Float,
        seed: Int,
        k: Int,
        out: FloatArray,
    ) {
        val spread = CLUSTER_SPREAD * sep
        val jx = (TasteStarColor.h01(seed, k * 31 + 601) - 0.5f) * 2f
        val jy = (TasteStarColor.h01(seed, k * 31 + 607) - 0.5f) * 2f
        out[0] = lx + ux * jx * spread + vx * jy * spread
        out[1] = ly + uy * jx * spread + vy * jy * spread
        out[2] = lz + uz * jx * spread + vz * jy * spread
    }
}
