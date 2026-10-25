package dev.tgfeed.ui

import dev.tgfeed.core.tastemap.CelestialKind
import dev.tgfeed.core.tastemap.TasteNBody
import dev.tgfeed.core.tastemap.TasteSpace
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * R343 怠速治理后的星系物理场（**场冻结+双星留种+插值复活**——用户 20:3x 裁决
 * 「全场缓旋=屏保级花招，骗小学生」的退役口径；r249 v2 的全星系 N 体仿真
 * 换轨，r249 时代承诺的「60fps 平滑」由 D3 真正兑现）：
 *
 *  - **场冻结（D1 退役刀）**：field 星（非双星成员）位置恒=烘焙位 base——零
 *    初速度、零步进位移、零引力沉降（R249c 旋转曲线 IC 撤除，「全场零旋转」
 *    构造性成立而非钳位补丁）。井位/命中/运镜/轮询全走 [renderX] → 自动
 *    静态化（「认熟的星座不搬」从 N 体长时漂移挂账变成结构性成立）。
 *    *否决备选：IC 清零+全 N 体续跑=field 星 G=60 自由沉降（~1h 塌缩热化
 *    ——3776aa3 的病反向复发）=「残留运动不干净」。*
 *  - **双星留种（D2 保留刀）**：每对**孤立二体 mini N 体**（[TasteNBody
 *    .createState] n=2 复用 Verlet/软化/护栏——真引力真轨道不是假摆动；对间
 *    与全场引力清零=对质心永驻烘焙位零残漂）。IC=[TasteNBody
 *    .binaryCircularVelSoftened]（软化力修正圆速度——牛顿口径在 d≈ε 档外旋
 *    漂移）；切向 t̂=连线×Y 平面切向、哈希符号、d<40/mSum 守卫=R249b 对 IC
 *    原口径沿用；步进 5s 拍不变（[TICK_MS]/[TasteNBody.DT] 零改动）。
 *  - **渲染插值复活（D3 丝滑刀）**：[beginRenderFrame] 相位钟自取
 *    SystemClock.uptimeMillis（与 [lastStepMs] 同域——R343 实锤的历史坑：
 *    调用方传 epoch 墙钟与 uptime 互减恒 1.79e12 → 相位恒钳 1.0=插值死拍
 *    =星只在 5s 拍上跳格，用户「一卡一卡顺时针转」的根因）；renderX 全表
 *    lerp（field prev=cur=base → lerp 恒 base——统一代码零分支）。相位数学
 *    单源 [TasteNBody.renderPhase01]。
 *  - **位置单一来源**（契约不变）：渲染 pass1/井位/行星中心/tapStar（点=所见）
 *    /轮询全走 [renderX]/[posInto]；互链边/关联弧端点走 [dispIntoChat] 跟随
 *    （field 恒零位移=边网静止；对成员随轨道）。
 *
 * 引力井表（黑洞强井 1.0 ∪ 巨频道弱井 0.35，≤[MAX_WELLS]）锚定井星位（field
 * 井恒=烘焙位；对成员井随轨道——「星动=井也动」契约保留）。
 *
 * R277 线程模型（GL 渲染线程化——**单锁无环**，换轨不变）：[beginRenderFrame]/
 * [renderX] 读在 GL 渲染线程、step 仍在主线程 5s 拍——[step]/
 * [beginRenderFrame]/[posInto]/[posOfChat]/[dispIntoChat] 五口收口 [renderLock]
 * （synchronized）。临界区=全表乘加/拷贝微秒级，5s 拍与 60fps 读互不阻塞可感；
 * Canvas 路径同走锁=无竞争 ns 级开销。锁图：单锁、锁内无外部调用无嵌套=无
 * 死锁环。
 */
internal class TasteGalaxyPhysics private constructor(
    /** 星数（与渲染星表同序——remember(scene) 同 key 构建） */
    val n: Int,
    private val chatIdArr: LongArray,
    private val idxOfChat: HashMap<Long, Int>,
    private val baseX: FloatArray,
    private val baseY: FloatArray,
    private val baseZ: FloatArray,
    /** 当拍位（R343 D1：field 恒=base；双星成员=对轨道当拍位） */
    private val curX: FloatArray,
    private val curY: FloatArray,
    private val curZ: FloatArray,
    /** 上拍位（渲染插值锚——步进前快照） */
    private val prevX: FloatArray,
    private val prevY: FloatArray,
    private val prevZ: FloatArray,
    /** 渲染插值位（每帧 [beginRenderFrame] 刷新——渲染/命中/轮询单一来源） */
    val renderX: FloatArray,
    val renderY: FloatArray,
    val renderZ: FloatArray,
    /** R343 D2 双星孤立二体（每对 n=2 mini N 体；null=该对不合格冻结为 field） */
    private val pairStates: Array<TasteNBody.NBodyState?>,
    private val pairIdxA: IntArray,
    private val pairIdxB: IntArray,
    /** 引力井星索引（渲染透镜查表用） */
    val wellIdx: IntArray,
    val wellMass01: FloatArray,
    val wellSwirl: BooleanArray,
    val wellCount: Int,
) {
    companion object {
        /** 物理步进周期（ms）：5~10s 低频一步（用户口径；双星轨道节拍沿用） */
        const val TICK_MS = 5000L

        /** 引力井上限（渲染透镜查表成本护栏：≤6 井 × 600 星） */
        const val MAX_WELLS = 6

        /** 巨井（P90+ 频道）体量分位门 */
        private const val GIANT_WELL_RANK = 0.90f

        /**
         * 烘焙（remember(scene) 侧调用；星表与渲染器同序）：
         * [binaryChatIds]=双星对展平（a,b,a,b…——chatId 口径）。成对后各自
         * 首算一遍加速度（Verlet 首步需要）。
         */
        fun build(stars: List<TasteFxStar>, binaryChatIds: LongArray): TasteGalaxyPhysics? {
            if (stars.isEmpty()) return null
            val n = stars.size
            val baseX = FloatArray(n)
            val baseY = FloatArray(n)
            val baseZ = FloatArray(n)
            val mass01 = FloatArray(n)
            val chatIdArr = LongArray(n)
            var maxVol = 0
            for (i in stars.indices) {
                val s = stars[i]
                baseX[i] = s.x
                baseY[i] = s.y
                baseZ[i] = s.z
                if (s.vol > maxVol) maxVol = s.vol
                chatIdArr[i] = s.chatId
            }
            // 质量=条数对数归一（mass01——与透镜井强度同量纲；黑洞≈1.0）
            for (i in 0 until n) {
                val ln = if (maxVol > 0) kotlin.math.ln1p(stars[i].vol.toFloat()) / kotlin.math.ln1p(maxVol.toFloat()) else 0f
                mass01[i] = if (stars[i].kind == CelestialKind.BLACK_HOLE) ln.coerceAtLeast(0.85f) else ln
            }
            // ---- 引力井表（黑洞强井 ∪ P90 巨频道弱井，按质量降序 top ≤6） ----
            val wellList = ArrayList<Int>(MAX_WELLS + 2)
            for (i in 0 until n) {
                if (stars[i].kind == CelestialKind.BLACK_HOLE) wellList += i
            }
            val giantCut = giantsRankCut(mass01, GIANT_WELL_RANK)
            for (i in 0 until n) {
                if (stars[i].kind == CelestialKind.BLACK_HOLE) continue
                if (stars[i].kind == CelestialKind.PULSAR || stars[i].kind == CelestialKind.CLUSTER ||
                    stars[i].kind == CelestialKind.METEOR
                ) {
                    continue
                }
                if (mass01[i] >= giantCut) wellList += i
            }
            wellList.sortByDescending { mass01[it] }
            val wellCount = wellList.size.coerceAtMost(MAX_WELLS)
            val wellIdx = IntArray(wellCount)
            val wellMass01 = FloatArray(wellCount)
            val wellSwirl = BooleanArray(wellCount)
            for (w in 0 until wellCount) {
                val si = wellList[w]
                wellIdx[w] = si
                wellMass01[w] = if (stars[si].kind == CelestialKind.BLACK_HOLE) 1.0f else 0.35f
                wellSwirl[w] = stars[si].kind == CelestialKind.BLACK_HOLE
            }
            // ---- 双星对解析（每星至多一对——binMate 去重，R249b 原口径） ----
            val idxOf = HashMap<Long, Int>(n * 2)
            for (i in 0 until n) idxOf[chatIdArr[i]] = i
            val binMate = IntArray(n) { -1 }
            var k = 0
            val pairs = ArrayList<IntArray>(binaryChatIds.size / 2)
            while (k + 1 < binaryChatIds.size) {
                val a = idxOf[binaryChatIds[k]]
                val b = idxOf[binaryChatIds[k + 1]]
                if (a != null && b != null && a != b && binMate[a] < 0 && binMate[b] < 0) {
                    binMate[a] = b
                    binMate[b] = a
                    pairs += intArrayOf(a, b)
                }
                k += 2
            }
            // ---- R343 D2 双星留种：每对孤立二体 mini N 体（真引力真轨道） ----
            // （R249b 对 IC 换轨：切向 t̂=连线×Y 平面切向、哈希符号、d<40/
            //  mSum≤1e-4 守卫全部沿用——不合格对冻结为 field 静态；对间与全场
            //  引力清零=对质心永驻烘焙位零残漂；IC 速度换软化力修正圆速度单口）
            val pairIdxA = IntArray(pairs.size)
            val pairIdxB = IntArray(pairs.size)
            val pairStates = arrayOfNulls<TasteNBody.NBodyState>(pairs.size)
            var livePairs = 0
            for (k2 in pairs.indices) {
                val a = pairs[k2][0]
                val b = pairs[k2][1]
                pairIdxA[k2] = a
                pairIdxB[k2] = b
                val dx = baseX[b] - baseX[a]
                val dy = baseY[b] - baseY[a]
                val dz = baseZ[b] - baseZ[a]
                val d = sqrt(dx * dx + dy * dy + dz * dz)
                val mSum = mass01[a] + mass01[b]
                if (d < 40f || mSum <= 1e-4f) continue // 守卫沿用（不合格对=冻结）
                // 切向 t̂=normalize(u×Y)（u=连线单位向）——R249b 对 IC 原口径
                val ux = dx / d
                val uy = dy / d
                val uz = dz / d
                var tx = uz
                var ty = 0f
                var tz = -ux
                val tl = sqrt(tx * tx + ty * ty + tz * tz)
                if (tl < 1e-3f) {
                    tx = 1f; ty = 0f; tz = 0f
                } else {
                    tx /= tl; ty /= tl; tz /= tl
                }
                // 确定性顺逆（哈希）——R249b 对 IC 原口径
                val sign = if (TasteSpace.h01(chatIdArr[a], 71) > 0.5f) 1f else -1f
                // 软化力修正圆速度（单口——牛顿口径在 d≈ε 档外旋漂移=不干净）
                val vRel = TasteNBody.binaryCircularVelSoftened(mSum, d)
                val s = TasteNBody.createState(
                    2, floatArrayOf(mass01[a], mass01[b]),
                    floatArrayOf(baseX[a], baseX[b]),
                    floatArrayOf(baseY[a], baseY[b]),
                    floatArrayOf(baseZ[a], baseZ[b]),
                )
                // 动量零拆分（v_a=+m_b/mSum·v_rel·t̂、v_b=−m_a/mSum·…）→ 质心静止
                val va = mass01[b] / mSum * vRel * sign
                val vb = mass01[a] / mSum * vRel * sign
                s.vx[0] = va * tx
                s.vy[0] = va * ty
                s.vz[0] = va * tz
                s.vx[1] = -vb * tx
                s.vy[1] = -vb * ty
                s.vz[1] = -vb * tz
                TasteNBody.accelBarnesHut(s) // 首步 Verlet 需要初始加速度
                pairStates[k2] = s
                livePairs++
            }
            // （R249 v2 的全星系 N 体（旋转曲线 IC+全库引力）按用户 20:3x 裁决
            //  退役——field 星零速度零步进；旋转曲线 IC 单口（core 存档）生产
            //  绝迹，源锚锁）
            val p = TasteGalaxyPhysics(
                n, chatIdArr, idxOf,
                baseX, baseY, baseZ,
                baseX.copyOf(), baseY.copyOf(), baseZ.copyOf(),
                baseX.copyOf(), baseY.copyOf(), baseZ.copyOf(),
                baseX.copyOf(), baseY.copyOf(), baseZ.copyOf(),
                pairStates, pairIdxA, pairIdxB,
                wellIdx, wellMass01, wellSwirl, wellCount,
            )
            p.lastStepMs = android.os.SystemClock.uptimeMillis()
            p.binaryPairCount = livePairs
            android.util.Log.i(
                "TasteMapX",
                "R343 场冻结: 星=$n 双星=$livePairs 对（全场缓旋退役——R249 旋转曲线 IC 撤除；" +
                    "双星=孤立二体软化圆轨道 5s 拍+插值）",
            )
            return p
        }

        /** 巨井分位门（mass01 的 P90——对数口径与体量分位同序） */
        private fun giantsRankCut(mass01: FloatArray, rank: Float): Float {
            if (mass01.isEmpty()) return Float.MAX_VALUE
            val sorted = mass01.copyOf().also { it.sort() }
            val idx = ((sorted.size - 1) * (1f - rank)).toInt().coerceIn(0, sorted.size - 1)
            return sorted[idx]
        }
    }

    /** 双星对数（探针——活着互转的对数，不含守卫冻结对） */
    var binaryPairCount: Int = 0
        private set

    /** 最近步进时刻（uptime ms——插值相位锚） */
    private var lastStepMs: Long = 0L

    /** 步进计数（探针节拍） */
    private var steps = 0

    /**
     * R277 渲染锁（GL 线程化唯一同步件——见类头「R277 线程模型」）：单锁无环；
     * 锁内只做数组乘加/拷贝，零外部调用零嵌套。
     */
    private val renderLock = Any()

    /**
     * 物理一步（5s 一拍；主线程——R277 起与 GL 渲染线程经 [renderLock] 互斥）：
     * prev←cur 快照 → **双星孤立二体 Verlet 步进**（field 星零步进=场冻结）
     * → 对成员回写当拍位 → 探针（12 步=1 分钟一条）。
     */
    fun step(nowSec: Float) {
        synchronized(renderLock) {
            stepLocked(nowSec)
        }
    }

    private fun stepLocked(nowSec: Float) {
        steps++
        // prev ← cur 快照（全表——field 恒 base=快照恒等零成本）
        System.arraycopy(curX, 0, prevX, 0, n)
        System.arraycopy(curY, 0, prevY, 0, n)
        System.arraycopy(curZ, 0, prevZ, 0, n)
        // 双星孤立二体步进（真引力真轨道；对间与全场引力=零=质心永驻）
        for (k in pairStates.indices) {
            val s = pairStates[k] ?: continue
            TasteNBody.step(s, TasteNBody.DT)
            TasteNBody.applyBound(s)
            TasteNBody.applyVCap(s)
            val ia = pairIdxA[k]
            val ib = pairIdxB[k]
            curX[ia] = s.px[0]
            curY[ia] = s.py[0]
            curZ[ia] = s.pz[0]
            curX[ib] = s.px[1]
            curY[ib] = s.py[1]
            curZ[ib] = s.pz[1]
        }
        lastStepMs = android.os.SystemClock.uptimeMillis()
        if (steps % 12 == 1) {
            // 退役干净度探针：场冻结实况+对轨道分离漂移（应≈0=软化圆轨道闭合）
            var maxSepDriftPct = 0f
            var vSum = 0f
            var vN = 0
            for (k in pairStates.indices) {
                val s = pairStates[k] ?: continue
                val ia = pairIdxA[k]
                val ib = pairIdxB[k]
                val dx = curX[ib] - curX[ia]
                val dy = curY[ib] - curY[ia]
                val dz = curZ[ib] - curZ[ia]
                val dNow = sqrt(dx * dx + dy * dy + dz * dz)
                val bdx = baseX[ib] - baseX[ia]
                val bdy = baseY[ib] - baseY[ia]
                val bdz = baseZ[ib] - baseZ[ia]
                val d0 = sqrt(bdx * bdx + bdy * bdy + bdz * bdz)
                if (d0 > 1f) {
                    maxSepDriftPct = maxOf(maxSepDriftPct, abs(dNow - d0) / d0 * 100f)
                }
                vSum += sqrt(s.vx[0] * s.vx[0] + s.vy[0] * s.vy[0] + s.vz[0] * s.vz[0])
                vSum += sqrt(s.vx[1] * s.vx[1] + s.vy[1] * s.vy[1] + s.vz[1] * s.vz[1])
                vN += 2
            }
            android.util.Log.i(
                "TasteMapX",
                "R343 怠速: 步=$steps 星=$n（场冻结=${n - 2 * binaryPairCount}）双星=${binaryPairCount} 对 " +
                    "对速均=${"%.2f".format(if (vN > 0) vSum / vN else 0f)} " +
                    "对距漂移max=${"%.2f".format(maxSepDriftPct)}%（退役干净度探针）",
            )
        }
    }

    /**
     * 渲染帧插值（draw 相位首调用——R277 起 GL 渲染线程经 [renderLock] 与 step
     * 互斥）：prev→cur 按相位线性（5s 窗口内的物理步进被抹平成 60fps 平滑；
     * field prev=cur=base → lerp 恒 base——场冻结零分支统一代码）。
     *
     * R343 时钟域修复：相位钟**自取 [android.os.SystemClock.uptimeMillis]**（与
     * [stepLocked]/build 落的 [lastStepMs] 同域）——不再用调用方传入的 [nowMs]
     * 算相位（历史坑：帧组装传 epoch 墙钟 System.currentTimeMillis，与 uptime
     * 互减恒 +1.79e12 → 相位恒钳 1.0=插值死拍 → 星只在 5s 步进拍上跳格——
     * 用户「一卡一卡顺时针转」的根因，自 R249b 接线日起带病）。参数保留兼容
     * Canvas×2+GL×4 六个调用位零改动（nowMs 的 epoch 语义归 reveal 老化等
     * 其他消费者，不归本口）；相位数学单源 [TasteNBody.renderPhase01]。
     */
    fun beginRenderFrame(nowMs: Long) {
        synchronized(renderLock) {
            val phase = TasteNBody.renderPhase01(
                android.os.SystemClock.uptimeMillis(), lastStepMs, TICK_MS,
            )
            val inv = 1f - phase
            for (i in 0 until n) {
                renderX[i] = inv * prevX[i] + phase * curX[i]
                renderY[i] = inv * prevY[i] + phase * curY[i]
                renderZ[i] = inv * prevZ[i] + phase * curZ[i]
            }
        }
    }

    /** 当前（插值）位按索引写 [out]（长 ≥3；R277 锁内读——GL 渲染线程互斥） */
    fun posInto(i: Int, out: FloatArray) {
        synchronized(renderLock) {
            out[0] = renderX[i]
            out[1] = renderY[i]
            out[2] = renderZ[i]
        }
    }

    /** 当前（插值）位按 chatId 写 [out]（长 ≥3）——轮询/命中用 */
    fun posOfChat(chatId: Long, out: FloatArray): Boolean {
        val i = idxOfChat[chatId] ?: return false
        posInto(i, out)
        return true
    }

    /** 位移（当前插值位−烘焙位）按 chatId 写 [out]（长 ≥3；R277 锁内读）——边/弧端点跟随用 */
    fun dispIntoChat(chatId: Long, out: FloatArray): Boolean {
        val i = idxOfChat[chatId] ?: return false
        synchronized(renderLock) {
            out[0] = renderX[i] - baseX[i]
            out[1] = renderY[i] - baseY[i]
            out[2] = renderZ[i] - baseZ[i]
        }
        return true
    }
}
