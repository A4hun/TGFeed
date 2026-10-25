package dev.tgfeed.core.tastemap

import kotlin.math.sqrt

/**
 * R231 纵深分布重建（纯函数，零 Android 依赖单测可跑）——用户实锤①「所有天体
 * 在一个平面上（缩到最小=扁盘），要向量空间那种上下左右全都有星、被星包围
 * 的感觉」的修法：
 *
 *  - **病灶**：MDS 第三维特征值 λ3 远小于 λ1/λ2（口味相似度本就二维主导），
 *    z 归一后压成薄层；相机沿 +z 直视无 pitch 轴，深度差只体现在 ±30% persp
 *    ——静态观感=圆盘；
 *  - **修法**：**XY 聚类结构一概不动**（口味嵌入的聚类关系不破坏），z 单独
 *    重建为**三带纵深分布**（近/中/远星带——飞进去有「穿过星层」的感觉）；
 *  - **z 的数据来源**（三路混序，全确定性）：
 *    1. MDS 第三维 rank（55%）——口味纵深序（如有；λ3 塌缩时 rank 退化为
 *       chatId 破平局序=确定性兜底，不冒充结构）；
 *    2. 活跃度 rank 反相（25%）——**常看的星离你近**：飞入近带先遇熟星
 *       （活跃度=lastWatch ∨ 内容 lastActivity，双源取强；无证据=最远）；
 *    3. chatId 确定性哈希（20%）——带内散布去量化条纹；
 *  - **椭球渐缩**：|z| 乘 (0.35+0.65·√(1−(rXY/TAPER_R)²))——XY 边缘星向中
 *    平面收，云团截面成椭球不是方盒（离群星仍离群，只是不再四角戳出）；
 *  - 排序一律 chatId 破平局——同数据同布局绝不跳位（与 MDS 同一确定性纪律）。
 *
 * 与布局管线的分工：layoutTasteMap 在 MDS 归一 + 互链微扰（只动 x/y）之后
 * 调用本函数补 z（flightZ 口径——**海报链路继续消费 MDS 原 z**，两套口径见
 * TasteMapScreen 的 StarNode KDoc）。
 */
object TasteDepthSpread {

    /** 纵深半幅（世界单位）：|z| 上界≈此值×0.95（三带外缘；XY 云团半径 CLOUD_R 同量级略深=有厚度不是扁盘） */
    const val Z_HALF = 420f

    /** 三带带心（z01 归一系 ±1 内：近带 −0.64 / 中带 0 / 远带 +0.64） */
    private const val BAND_PITCH = 0.64f

    /** 带半宽（z01 单位）：三带各占 0.54，带隙 0.10×2=「穿过星层」的层感 */
    private const val BAND_HALF = 0.27f

    /** 带内微抖动（z01 单位 ±0.03）：层不糊也不成纸片 */
    private const val JITTER = 0.03f

    /** 椭球渐缩半径（×CLOUD_R=445）：XY 超出此半径的星纵深向中平面收 */
    private const val TAPER_FACTOR = 1.35f

    /** MDS z3 rank 权重（口味纵深序） */
    private const val W_MDS = 0.55f

    /** 活跃度 rank 反相权重（常看的星偏近带） */
    private const val W_ACT = 0.25f

    /** chatId 哈希权重（带内散布） */
    private const val W_HASH = 0.20f

    /** 确定性 0..1 哈希（与 FxEngine.h01 同式——同 chatId 同散布，单测同口径锚定） */
    internal fun h01(a: Long, b: Int): Float =
        (((a * -0x61c8897L and 0x7FFFFFFFL) xor (b * 0x1b873593L and 0x7FFFFFFFL)) and 0xFFFFFFL).toFloat() / 16777216f

    /**
     * 三带纵深展开（**只写 [outZ]**，x/y 一概不动）：
     *
     * @param n 星数
     * @param zMds MDS 第三维（世界尺度；只取**序**不取值——λ3 塌缩不影响带内
     *   散布的均匀性）
     * @param chatIds 频道 id（rank 破平局 + 哈希散布）
     * @param x/y 世界坐标（椭球渐缩用；不被修改）
     * @param actMs 活跃度信号（毫秒；0=无证据——观看时刻 ∨ 内容活跃时刻，调用
     *   方双源取强后传入）
     * @param outZ 输出纵深（App 渲染口径 flightZ；调用方数组复用）
     * @return 三带计数（诊断日志：近/中/远——[近, 中, 远]）
     */
    fun spread(
        n: Int,
        zMds: FloatArray,
        chatIds: LongArray,
        x: FloatArray,
        y: FloatArray,
        actMs: LongArray,
        outZ: FloatArray,
    ): IntArray {
        if (n <= 0) return intArrayOf(0, 0, 0)
        // ---- 三路 rank（索引排序，chatId 破平局=确定性）----
        val mdsRank = rank01(n, zMds, chatIds)
        val actRank = rank01(n, actMs, chatIds)
        // ---- 混合分 → rank 重映射：混合分=三路加权均值（向中心收缩成单带——
        //      实测带计数 7/76/7 的病），重取 rank 保证 [0,1] 均匀覆盖=三带必然
        //      三等分；**序仍由混合分决定**（MDS 纵深序+活跃度偏近+哈希去并列）
        //      =口味+活跃双语义保留 ----
        val blend = FloatArray(n) {
            W_MDS * mdsRank[it] + W_ACT * (1f - actRank[it]) + W_HASH * h01(chatIds[it], 3)
        }
        val u = rank01(n, blend, chatIds)
        // ---- u → 三带映射 → z01 → 世界 z（椭球渐缩） ----
        val bands = intArrayOf(0, 0, 0)
        val taperR = TAPER_FACTOR * 330f // CLOUD_R 镜像（core 不依赖 UI 常量，同值同注释）
        for (i in 0 until n) {
            val band = (u[i] * 3f).toInt().coerceIn(0, 2)
            val inBand = (u[i] * 3f - band).coerceIn(0f, 1f)
            var z01 = (band - 1f) * BAND_PITCH + (inBand - 0.5f) * 2f * BAND_HALF
            z01 += (h01(chatIds[i], 7) - 0.5f) * 2f * JITTER
            // 椭球渐缩：XY 半径超界纵深向中平面收（0.35 下限=外缘星不糊进中带）
            val rxy = sqrt((x[i] - 500f) * (x[i] - 500f) + (y[i] - 500f) * (y[i] - 500f))
            val t = if (rxy >= taperR) 0f else sqrt(1f - (rxy / taperR) * (rxy / taperR))
            outZ[i] = z01 * Z_HALF * (0.35f + 0.65f * t)
            bands[band]++
        }
        return bands
    }

    /** Float 值序 rank → 0..1（升序：最小=0 最大=1；同值按 chatId 升序破平局；n=1 全 0.5） */
    private fun rank01(n: Int, values: FloatArray, chatIds: LongArray): FloatArray {
        val order = (0 until n).sortedWith(compareBy({ values[it] }, { chatIds[it] }))
        val out = FloatArray(n)
        for (pos in 0 until n) out[order[pos]] = if (n > 1) pos.toFloat() / (n - 1) else 0.5f
        return out
    }

    /** Long 值序 rank → 0..1（活跃度毫秒口径；同式复用） */
    private fun rank01(n: Int, values: LongArray, chatIds: LongArray): FloatArray {
        val order = (0 until n).sortedWith(compareBy({ values[it] }, { chatIds[it] }))
        val out = FloatArray(n)
        for (pos in 0 until n) out[order[pos]] = if (n > 1) pos.toFloat() / (n - 1) else 0.5f
        return out
    }
}
