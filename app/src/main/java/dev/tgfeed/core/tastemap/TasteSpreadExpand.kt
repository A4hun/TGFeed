package dev.tgfeed.core.tastemap

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * R237 聚类自适应展开纯函数（零 Android 依赖，单测可跑）——用户真机实锤③
 * 「大部分星系聚拢在一起」的修法（任务书口径：检测到聚类过紧时自动扩大间距，
 * pure function spreadFactor）：
 *
 *  - **根因**：MDS 余弦距离的口味云团天然紧核（R152c 已知「口味云团极紧」）——
 *    大部分星挤在一小团里、少数离群星拉出云团半径；**均匀缩放救不了**（整体
 *    放大=密度不变，取景框跟着放大=观感原样）；
 *  - **判定量=星体间距**（用户实锤原文的直接口径）：口味云团中位最近邻间距 ÷
 *    中位星体半径（[spacing01] 语义上 <1=星体互相压住、≥[SPACING_COMFY]=舒适
 *    舒展）——比径向分位浓度更贴「聚拢」的感知本质，且对离群点鲁棒；
 *  - **修法**：围绕云团质心的**径向次线性重映射** r' = R·(r/R)^k（k<1）——核内
 *    星径向展开、离群星原地不动、角度与簇结构**严格保序**（单调映射，簇仍是
 *    簇、邻居仍是邻居）——「密度」真解耦；k 由两个量联合自适应：
 *    ①间距缺口 [expandFactor]（要放大多少倍核内间距 F）；②浓度（中位半径/
 *    最大半径——核越集中在小半径里，同 k 的展开增益越大）；
 *  - **确定性**：同数据同 k 同布局（中位数/最近邻全为确定性计算，无随机成分）；
 *  - 帧零成本：装配期一次（O(n²) 最近邻在 Default 线程，600 星毫秒级），
 *    渲染/命中期零计算（坐标数组原地改写）。
 *
 * 消费方：layoutTasteMap 装配期（微扰 recenter 之后、三带纵深 spread 之前），
 * 作用于最终 x/y 数组——星/连线/命中/行星/弧/尘埃带/星轨/星云/海报全吃同一
 * 坐标单一来源（双口径 z 不动：[TasteDepthSpread] 的 z 重建照常在其后跑）。
 */
object TasteSpreadExpand {

    /** 舒展达标线：中位最近邻间距 ≥ 此值×中位星体半径 → 不触发展开 */
    const val SPACING_COMFY = 2.2f

    /** 极紧触底线：≤ 此值=星体互相压住（间距小于本体半径），展开拉满 */
    const val SPACING_TIGHT = 0.8f

    /** 间距缺口放大倍数上限（核内星最大径向舒展 ~2.6×——再大簇结构读感会散） */
    const val F_MAX = 2.6f

    /** 展开幂指数下界（k 钳位——均匀紧核盘径向映射的展开极限，诚实部分修） */
    const val K_MIN = 0.55f

    /**
     * 间距缺口 → 目标放大倍数 F∈[1, F_MAX]（1=不触发）：
     * [spacing01] ≥ [SPACING_COMFY] 恒 1；≤ [SPACING_TIGHT] 钳 [F_MAX]；中间线性。
     */
    fun expandFactor(spacing01: Float): Float {
        if (spacing01 >= SPACING_COMFY) return 1f
        if (spacing01 <= SPACING_TIGHT) return F_MAX
        val t = (spacing01 - SPACING_TIGHT) / (SPACING_COMFY - SPACING_TIGHT)
        return 1f + (F_MAX - 1f) * (1f - t)
    }

    /**
     * 径向幂指数 k∈[K_MIN, 1]（1=恒等）：让**中位半径星**的间距放大 [f] 倍
     * （r'=R·(rm/R)^k = f·rm ⇔ k = 1 + ln f / ln(rm/R)，rm/R<1 → k<1）。
     * 浓度联动：核越紧（rm/R 越小）同 f 的 k 越温和但核内增益越深（r 更小者
     * 放大更多——`r'=R·(r/R)^k` 对 r 单调、对小 r 增益更大）。退化防御：
     * rm≥R 或 rm≤0 → 恒等（径向映射无可展开的浓度差）。
     */
    fun radialK(f: Float, medR: Float, maxR: Float): Float {
        if (f <= 1f || medR <= 1e-4f || maxR <= medR) return 1f
        val lr = ln(medR / maxR) // < 0
        val k = 1f + ln(f) / lr
        return k.coerceIn(K_MIN, 1f)
    }

    /**
     * 装配期一步口（推荐入口）：间距测量（hasComp 口味云团的中位最近邻/中位
     * 星体半径）→ k → **原地径向展开**（全体星共用同一 k——保相对关系与簇
     * 结构；离群星 r≥R 原地不动）→ 返回实际 k（1=未触发）。
     *
     * @param hasComp 有构成证据的口味云团位（间距/浓度只量它——与取景/居中同口径）
     * @param bodyR 每星体量口径半径（中位分母——MapStarRaw.bodyRadius 直传）
     */
    fun apply(
        x: FloatArray,
        y: FloatArray,
        n: Int,
        hasComp: BooleanArray,
        bodyR: FloatArray,
        cx: Float,
        cy: Float,
    ): Float {
        if (n <= 0 || hasComp.size < n || bodyR.size < n) return 1f
        val idx = ArrayList<Int>(n)
        for (i in 0 until n) if (hasComp[i]) idx += i
        if (idx.size < 3) return 1f
        val m = idx.size
        // ① 中位/最大半径（围绕云团质心 (cx,cy)——调用方 recenter 后即世界中心）
        val radii = FloatArray(m)
        var maxR = 1e-4f
        for (j in 0 until m) {
            val i = idx[j]
            val r = sqrt((x[i] - cx) * (x[i] - cx) + (y[i] - cy) * (y[i] - cy))
            radii[j] = r
            if (r > maxR) maxR = r
        }
        val medR = medianOf(radii)
        // ② 中位最近邻间距（O(m²) 确定性——600 星毫秒级，Default 线程装配期一次）
        val nn = FloatArray(m)
        for (a in 0 until m) {
            val ia = idx[a]
            var best = Float.MAX_VALUE
            for (b in 0 until m) {
                if (a == b) continue
                val ib = idx[b]
                val dx = x[ia] - x[ib]
                val dy = y[ia] - y[ib]
                val d2 = dx * dx + dy * dy
                if (d2 < best) best = d2
            }
            nn[a] = sqrt(best)
        }
        val medNn = medianOf(nn)
        val bodies = FloatArray(m) { bodyR[idx[it]] }
        val medBody = medianOf(bodies).coerceAtLeast(1e-3f)
        // ③ 间距缺口 → F → k → 原地展开
        val f = expandFactor(medNn / medBody)
        if (f <= 1f) return 1f
        val k = radialK(f, medR, maxR)
        if (k >= 1f) return 1f
        return expandXy(x, y, n, cx, cy, k, maxR)
    }

    /**
     * 径向展开（**原地**改写 x/y）：围绕 (cx,cy) 的 r' = R·(r/R)^k（R=[radiusRef]，
     * 全体星共用同一 k 与 R——保序保证簇结构不变）。k=1 或 r≥R 恒等（离群星
     * 原地不动——离群也是数据，只展开核不收 outlier）。返回实际使用的 k。
     */
    fun expandXy(
        x: FloatArray,
        y: FloatArray,
        n: Int,
        cx: Float,
        cy: Float,
        k: Float,
        radiusRef: Float,
    ): Float {
        if (n <= 0 || k >= 1f || radiusRef <= 1e-4f) return 1f
        val rRef = radiusRef
        for (i in 0 until n) {
            val dx = x[i] - cx
            val dy = y[i] - cy
            val r = sqrt(dx * dx + dy * dy)
            if (r <= 1e-4f || r >= rRef) continue // 质心星/离群星原地
            val rr = r / rRef
            val newR = rRef * rr.pow(k)
            if (newR <= r) continue // 防御：k<1 下 rr∈(0,1) 恒 >r，此分支不可达
            val s = newR / r
            x[i] = cx + dx * s
            y[i] = cy + dy * s
        }
        return k
    }

    /** 中位数（偶数个取中间两均值；空/退化=0）——确定性，零随机 */
    internal fun medianOf(vals: FloatArray): Float {
        if (vals.isEmpty()) return 0f
        val sorted = vals.copyOf().also { it.sort() }
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }
}
