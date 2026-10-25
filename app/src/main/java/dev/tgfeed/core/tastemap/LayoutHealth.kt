package dev.tgfeed.core.tastemap

/**
 * R267 布局健康闸（星图手机端塌方修复的核心件——P0：模拟器全绿但用户真机全崩的
 * 验收缺口）。
 *
 * 塌方机理（真机实锤 2026-10-20）：V3 球面方位角 θ=atan2(-(y-500), x-500)（MDS 口味
 * 坐标）——MDS 原料退化（批迁锁窗期基因扫描空/旧缓存喂单值构成）→ 云团塌到中心或
 * 一维（λ2=λ3≈0）→ **θ 唯一值≤2**（全同点 atan2(0,0)=0；一维嵌入 θ∈{0,π}）→
 * spherifyV3 的 z=r·cosφ·sinθ≈0 ∀ → 全天体共面 → 初始相机视线在该面内 → 投影成
 * **屏幕中央一条竖线**（φ/r 正常分散=线上高低/大小差异，与用户截图形态逐点吻合）。
 *
 * 本件职责（全部纯函数零 Android 依赖，JVM 单测锚定）：
 *  1. **检测**：θ 量化唯一桶数 / xy 量化唯一格数 / 特征矩阵方差维数——三个健康量；
 *  2. **判定**：[layoutDegenerate]（产物退化）与 [sourceDegenerate]（原料退化）；
 *  3. **回退**：[fallbackTheta]——纯 chatId 哈希方位角（破零保球面展开；口味方向
 *     语义本轮牺牲——任务书原文口径「保证不成一条线，宁可牺牲口味方向语义也不能塌」）；
 *  4. **缓存门**：[genomeFp]/[genomeDrifted]——基因指纹（频道数+总条数对数桶）漂移
 *     判定（成品缓存换装门原料——不再只看频道数 ±5%）。
 */
object LayoutHealth {

    /** θ 量化桶数（全周 2π 均分） */
    const val THETA_BUCKETS = 64

    /** 退化判定：θ 唯一桶数下限（≤2=竖线塌缩实锤：全同 atan2(0,0)=0 / 一维 {0,π}） */
    const val THETA_UNIQUE_MIN = 3

    /** xy 坐标量化格边长（世界单位——CLOUD_R=380 云团尺度下 16u 格足够分辨塌缩） */
    const val COORD_CELL = 16f

    /** 退化判定：xy 唯一格数下限（≤2=坐标塌缩实锤） */
    const val COORD_UNIQUE_MIN = 3

    /** 数据源退化判定：特征矩阵有方差维数下限（<2=MDS 原料单值，嵌入必塌） */
    const val FEAT_VAR_DIMS_MIN = 2

    /**
     * 数据源退化判定启用的最小星数——小库（演示/新装）特征天然单薄（未知极哈希散布
     * 本就健康），不对它启用重试风暴。
     */
    const val SOURCE_CHECK_MIN_STARS = 32

    /** 特征方差判定 epsilon（特征是 0..1 归一值；小于此视为无方差维） */
    private const val FEAT_VAR_EPS = 1e-4f

    /** θ 唯一桶数（弧度 [-π,π]→[THETA_BUCKETS] 桶；NaN 防御进 0 桶——NaN 也是退化证据） */
    fun thetaUniqueCount(thetas: FloatArray, n: Int = thetas.size): Int {
        val seen = HashSet<Int>(THETA_BUCKETS)
        val lim = minOf(n, thetas.size)
        for (i in 0 until lim) {
            val t = thetas[i]
            val b = if (t.isNaN() || t.isInfinite()) {
                0
            } else {
                (((t + Math.PI.toFloat()) / (2f * Math.PI.toFloat())) * THETA_BUCKETS)
                    .toInt().coerceIn(0, THETA_BUCKETS - 1)
            }
            seen += b
        }
        return seen.size
    }

    /** xy 量化唯一格数（[COORD_CELL] 世界单位格；NaN 防御进 (0,0) 格） */
    fun coordUniqueCount(x: FloatArray, y: FloatArray, n: Int = x.size): Int {
        val seen = HashSet<Long>(64)
        val lim = minOf(n, minOf(x.size, y.size))
        for (i in 0 until lim) {
            val cx = if (x[i].isNaN() || x[i].isInfinite()) 0L else Math.floor((x[i] / COORD_CELL).toDouble()).toLong()
            val cy = if (y[i].isNaN() || y[i].isInfinite()) 0L else Math.floor((y[i] / COORD_CELL).toDouble()).toLong()
            seen += cx * 0x85EBCA6BL + cy * 0xC2B2AE35L
        }
        return seen.size
    }

    /**
     * 特征矩阵有方差维数（行主序 n×dims，0..1 归一特征；该维 max−min>[FEAT_VAR_EPS]
     * 即有方差）。MDS 原料健康度：全单值=余弦距离恒 0→B 矩阵恒 0→幂迭代退化→塌缩。
     */
    fun featureVarianceDims(feat: FloatArray, n: Int, dims: Int): Int {
        if (n <= 0) return 0
        val mn = FloatArray(dims) { Float.MAX_VALUE }
        val mx = FloatArray(dims) { -Float.MAX_VALUE }
        val lim = minOf(n, feat.size / dims)
        for (i in 0 until lim) {
            val base = i * dims
            for (d in 0 until dims) {
                val v = feat[base + d]
                if (v < mn[d]) mn[d] = v
                if (v > mx[d]) mx[d] = v
            }
        }
        var count = 0
        for (d in 0 until dims) {
            if (mx[d] - mn[d] > FEAT_VAR_EPS) count++
        }
        return count
    }

    /** 产物退化综合判定：θ 或 xy 任一塌缩即退化 */
    fun layoutDegenerate(thetaUnique: Int, coordUnique: Int): Boolean =
        thetaUnique < THETA_UNIQUE_MIN || coordUnique < COORD_UNIQUE_MIN

    /** 原料退化判定：星数够（小库不启用）且特征方差维不足 */
    fun sourceDegenerate(featVarDims: Int, n: Int): Boolean =
        n >= SOURCE_CHECK_MIN_STARS && featVarDims < FEAT_VAR_DIMS_MIN

    /**
     * 退化回退方位角：纯 chatId 哈希 [-π,π)（TasteSpace.h01 同族确定性——同数据同
     * 宇宙不破；φ/r 本就是哈希/体量独立分配，θ 哈希化后球面仍均匀展开，只牺牲
     * 「相似频道同方向」的口味语义——任务书裁决：不成一条线 > 方向语义）。
     */
    fun fallbackTheta(chatId: Long): Float =
        TasteSpace.h01(chatId, 267) * (2f * Math.PI.toFloat()) - Math.PI.toFloat()

    // ---------------- 基因指纹（R267 成品缓存换装门原料） ----------------

    /** 总条数对数桶（翻倍才换桶——爬虫小幅增量不漂移，导入换库/迁移收编才漂移） */
    fun logBucketOf(v: Long): Int =
        if (v <= 0L) 0 else 63 - java.lang.Long.numberOfLeadingZeros(v)

    /**
     * 基因指纹打包：高 56 位=基因组频道数，低 8 位=总条数对数桶。
     * 不是内容哈希——基因计数随每次爬虫 ingest 变动，内容哈希会恒定漂移把
     * 「门内沿用（星图零跳动）」打死；对数桶=翻倍级变化才漂移（与频道数 ±5%
     * 门同族的粗粒容忍口径）。
     */
    fun genomeFp(genomeCount: Int, totalItems: Long): Long =
        (genomeCount.toLong() shl 8) or (logBucketOf(totalItems).toLong() and 0xFFL)

    /**
     * 基因指纹漂移门：频道数 ±5% 外 或 总条数对数桶不同（翻倍级）→ 漂移（换装）。
     * fp=0=无旧账（首开/旧版缓存无此字段）不拦——由频道数门独自裁决。
     */
    fun genomeDrifted(fp0: Long, fp1: Long): Boolean {
        if (fp0 == 0L || fp1 == 0L || fp0 == fp1) return false
        val c0 = fp0 shr 8
        val c1 = fp1 shr 8
        if (c0 > 0 && kotlin.math.abs(c1 - c0) * 20L > c0) return true
        return (fp0 and 0xFFL) != (fp1 and 0xFFL)
    }
}
