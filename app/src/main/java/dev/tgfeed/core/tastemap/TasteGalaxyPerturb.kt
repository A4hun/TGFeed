package dev.tgfeed.core.tastemap

import kotlin.math.max
import kotlin.math.sqrt

/**
 * R223b 互链弹簧微扰（口味星图一期·纯函数，零 Android 依赖单测可跑）：
 *
 * 设计文档 §2.2 推荐方案——「MDS 收敛坐标为初值，叠加固定 20 轮力导向松弛：
 * 互链/转发溯源边为弱弹簧（劲度小幅度 0.15，防把口味簇拉碎），星间最小间距斥力
 * 防重叠。**固定轮数+固定初值=确定性不破**」。
 *
 * - 只动 x/y：z 深度轴继续由 MDS 第三特征向量给出（视差/景深语义不动，设计
 *   §2.2 第 3 条）；
 * - 弹簧是**单向聚拢**（当前距 > [restLen] 才吸引；更近不推——紧密口味簇不被
 *   弹开，重叠防护是斥力的职责）——纯吸引+自然长度=相连频道「聚到邻域」而不
 *   塌缩到重叠（弱弹簧本义）；位移限幅（非力系数限幅）防单轮过冲；
 * - 斥力只在星对距离 < [minDist] 时介入（防重叠/防标签互吃），O(n²)×20 轮对
 *   ~720 节点 ≈ 千万次浮点运算，Default 线程毫秒级；
 * - 迭代序固定（边按入参序、星对按 i<j 升序）、纯浮点算术——同输入同输出，
 *   与 MDS 管线同一确定性纪律（同数据同布局绝不跳位）。
 *
 * 布局侧接线（TasteMapScreen.layoutTasteMap）：微扰在 MDS 归一到世界坐标之后、
 * 连线烘焙之前执行；收尾把口味云团质心平移回世界中心（纯平移不重标定，取景
 * 稳定）。设置开关「按互链关系聚拢」（默认开）关 = 跳过本段（纯口味几何）。
 */
object TasteGalaxyPerturb {

    /** 微扰轮数（设计 §2.2：固定 20 轮） */
    const val ROUNDS = 20

    /** 弹簧劲度（设计 §2.2：小幅度 0.15，防把口味簇拉碎） */
    const val STIFFNESS = 0.15f

    /** 边自然长度（世界单位）：相连频道聚到邻域而不塌缩到重叠 */
    const val REST_LEN = 150f

    /**
     * 星间最小间距（世界单位）：小于此值互斥防重叠。量级标定——600+ 星均匀铺满
     * CLOUD_R=330 云团时平均近邻距 ≈22，取 24 只修「贴脸重叠」不膨胀簇密度
     * （簇紧致=MDS 应力语义，斥力不得拉碎它）。
     */
    const val MIN_DIST = 24f

    /**
     * 斥力劲度（0.35，比弹簧硬）：重叠是读数硬伤（点吃点），弹簧软是防拉碎簇，
     * 两者目标不同所以劲度分档——限幅同 0.5 防单轮过冲。
     */
    const val REPEL_STIFFNESS = 0.35f

    /**
     * 互链弹簧微扰一步到位（原地更新 [x]/[y]，z 不动）：
     *
     * @param x/y 星世界坐标（行主序 n 个；MDS 归一产出，函数原地改写）
     * @param n 星数
     * @param edgesFlat 无向边表（平铺索引对 a0,b0,a1,b1…；均为 0..n-1）
     * @param edgeCount 边数（edgesFlat 长度的一半）
     * @param cloudMask 口味云团掩码（true=有构成证据；质心回中只统计云团——
     *   与 MDS 归一同一「云团居中」口径，未知极不被质心拖走）
     * @param rounds 松弛轮数（默认 [ROUNDS]）
     * @param stiffness 弹簧劲度（默认 [STIFFNESS]）
     * @param restLen 边自然长度（默认 [REST_LEN]）
     * @param minDist 最小间距（默认 [MIN_DIST]）
     * @return 实际位移统计（诊断日志用；均方根位移，纯读数无副作用）
     */
    fun perturbXy(
        x: FloatArray,
        y: FloatArray,
        n: Int,
        edgesFlat: IntArray,
        edgeCount: Int,
        cloudMask: BooleanArray,
        rounds: Int = ROUNDS,
        stiffness: Float = STIFFNESS,
        restLen: Float = REST_LEN,
        minDist: Float = MIN_DIST,
    ): Float {
        // 无星/无轮/无边可聚（演示模式纯观看图）→ 整段直通（微扰=互链聚拢，
        // 无边时无聚拢语义，零成本收场；质心回中由 [recenterCloud] 独立执行）
        if (n <= 1 || rounds <= 0 || edgeCount <= 0) return 0f
        val ax = FloatArray(n)
        val ay = FloatArray(n)
        val minD2 = minDist * minDist
        var rms = 0f
        var round = 0
        while (round < rounds) {
            java.util.Arrays.fill(ax, 0f)
            java.util.Arrays.fill(ay, 0f)
            // ---- 弹簧：**单向聚拢**（d>自然距才吸引，d≤自然距不推——紧密簇不被
            //      弹到自然距之外；重叠防护是斥力的职责）；每端位移=劲度×(d-rest)×
            //      半程，位移限幅 ±0.25×d 防单轮过冲 ----
            for (e in 0 until edgeCount) {
                val a = edgesFlat[e * 2]
                val b = edgesFlat[e * 2 + 1]
                if (a < 0 || a >= n || b < 0 || b >= n || a == b) continue
                val dx = x[b] - x[a]
                val dy = y[b] - y[a]
                val d = sqrt(dx * dx + dy * dy)
                if (d <= restLen || d < 1e-4f) continue
                val inv = 1f / d
                val mv = (stiffness * (d - restLen) * 0.5f).coerceAtMost(0.25f * d)
                val mx = mv * dx * inv
                val my = mv * dy * inv
                ax[a] += mx; ay[a] += my // a 向 b 挪
                ax[b] -= mx; ay[b] -= my // b 向 a 挪
            }
            // ---- 斥力：星对距离 < minDist 时推开（每端位移=劲度×(minDist-d)×半程，
            //      位移限幅 0.25×minDist；劲度 0.35 比弹簧硬——重叠是读数硬伤） ----
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    val dx = x[j] - x[i]
                    val dy = y[j] - y[i]
                    val d2 = dx * dx + dy * dy
                    if (d2 >= minD2) continue
                    val d = sqrt(max(d2, 1e-8f))
                    val inv = 1f / d
                    val step = (REPEL_STIFFNESS * (minDist - d) * 0.5f).coerceAtMost(0.25f * minDist)
                    val mx = step * dx * inv
                    val my = step * dy * inv
                    ax[i] -= mx; ay[i] -= my
                    ax[j] += mx; ay[j] += my
                }
            }
            // ---- R267 稳定闸（真机塌方根因实锤：中枢星**多边位移叠加无上限**——
            //      每边位移 0.075×d 单向过冲，k 边中枢每轮位移 k×0.075×d；k·0.075>2
            //      （度 ≥27 的 hub）即正反馈发散：过冲→d 更大→位移更大，20 轮把
            //      380 半径云团吹爆到 1e8 量级并漂离原点（真机探针 x幅 -3.3e8..1.1e7）
            //      → θ=atan2(-(y-500),(x-500)) 参照系全塌 → V3 球面竖线/偏心扇。
            //      修法=限幅从「每边」升到「**每星每轮总位移** ≤0.25×restLen」：
            //      正常收敛域（单边 ~17u ≪ 37.5u）永不触闸=旧行为零变化，hub 过冲
            //      正反馈断根（位移不再随 d 增长——发散的几何级数引擎被拆掉）） ----
            val maxStep = 0.25f * restLen
            val maxStep2 = maxStep * maxStep
            for (i in 0 until n) {
                val m2 = ax[i] * ax[i] + ay[i] * ay[i]
                if (m2 > maxStep2) {
                    val sc = maxStep / sqrt(m2)
                    ax[i] *= sc
                    ay[i] *= sc
                }
            }
            // ---- 应用（阻尼已并入劲度=全额应用本轮位移；纯浮点+固定迭代序=确定性） ----
            var moved = 0f
            for (i in 0 until n) {
                x[i] += ax[i]
                y[i] += ay[i]
                moved += ax[i] * ax[i] + ay[i] * ay[i]
            }
            rms = sqrt(moved / n)
            round++
        }
        return rms // 末轮位移 RMS（诊断读数）
    }

    /**
     * 口味云团质心平移回世界中心（纯平移，不缩放不旋转——MDS 归一的云团半径
     * 语义不动；未知极如实留在侧远方）。[cx]/[cy] 为目标中心（世界系 500,500）。
     * 返回平移量（诊断日志）。
     */
    fun recenterCloud(
        x: FloatArray,
        y: FloatArray,
        n: Int,
        cloudMask: BooleanArray,
        cx: Float,
        cy: Float,
    ): FloatArray {
        if (n <= 0) return floatArrayOf(0f, 0f)
        var mx = 0f
        var my = 0f
        var m = 0
        for (i in 0 until n) {
            if (i < cloudMask.size && !cloudMask[i]) continue
            mx += x[i]; my += y[i]; m++
        }
        if (m == 0) {
            for (i in 0 until n) { mx += x[i]; my += y[i] }
            m = n
        }
        val dx = cx - mx / m
        val dy = cy - my / m
        for (i in 0 until n) {
            x[i] += dx
            y[i] += dy
        }
        return floatArrayOf(dx, dy)
    }
}
