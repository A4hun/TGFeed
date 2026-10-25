package dev.tgfeed.core.tastemap

import kotlin.math.sqrt

/**
 * R249 v2 真 N 体仿真引擎（**用户 19:00 修正的字面落地**：「如果要做就要做好，
 * 考虑好各种可能性，不要做一个半成品」——正经 Barnes-Hut+辛积分，不是简化版）：
 *
 *  - **求解器=Barnes-Hut 八叉树 O(N log N)**（节点池零分配；θ=[THETA]——
 *    θ<1/√3 的数学性质保证「含目标体的节点必被展开」=零自引力伪差）；
 *  - **积分=velocity Verlet（kick-drift-kick 辛积分）**——非欧拉；辛结构保证
 *    长期能量有界（二体轨道测试锚定）；
 *  - **力=牛顿引力+Plummer 软化**（F=G·m1·m2/(r²+ε²)——ε=[SOFTENING] 防近距
 *    奇点/防碰撞爆炸，「碰撞」=两体并合进同一叶节点不炸）；
 *  - **边界=软墙**（[applyBound]：出 [BOUND] 界的星钳回界上、径向速度反转衰减
 *    ——防飞出；非守恒项=护栏，守恒测试在内部域跑）；
 *  - **速度上限 [V_MAX]**（防数值飞升的护栏；同上不进守恒口径）；
 *  - **直接求和对照**（[accelDirect]——测试与 BH 实现互证：θ 容差内一致）；
 *  - **守恒诊断**（[totalMomentum]/[totalEnergy]——单测的守恒锚：对称力系
 *    动量严格守恒；二体系统能量漂移 <1%/百步）。
 *
 * 时间口径：调用方 5s 实拍一步 × [DT] 模拟秒（1:5 时间膨胀——星系演化肉眼
 * 可见但不狂奔）；渲染插值在 ui 侧（prev→cur 线性，60fps 平滑）。
 * 初条件（MDS 聚类保相关性——「引力=自然整理」）：吸引域星=绕主导井的圆轨道
 * 速度（真开普勒 IC）∪ 双星互转轨道 ∪ 野星微弥散——由 ui 物理场类构造。
 *
 * R368 线程口径：本 object=进程级单例（共享 [tree]/[fScratch]），两路并发消费
 * （主线程双星 Verlet 步进 × Default 线程涌现布局斥力）经 [engineLock] 互斥
 * ——单锁无环（锁内零外部调用零嵌套）；详见 [engineLock] 文档。
 */
object TasteNBody {

    /**
     * 引力常数（**16416 实测标定**：G=392 时全库质量场自由落时 ~9 分钟=系统热化
     * 坍缩实锤（速度中位 0.98→4.16/动能 22×）；G=60+旋转曲线 IC（[icGalacticCircular]
     * 按包络质量圆轨道）→ 中壳星 ~2.7 u/s、星系 ~1.6°/实分钟自转——「活而不散」）。
     */
    const val G = 60f

    /** Plummer 软化长度（世界单位）：近距力封顶 ∝ 1/ε²——防奇点防爆炸 */
    const val SOFTENING = 80f

    /** Barnes-Hut 开角 θ（<1/√3≈0.577——含目标体的节点恒被展开=零自引力） */
    const val THETA = 0.45f

    /** 有界区域半径（世界单位）：星系宇宙的软墙 */
    const val BOUND = 8500f

    /** 单步模拟时长（秒/步——1:5 时间膨胀 @5s 实拍） */
    const val DT = 1f

    /** 速度护栏上限（世界单位/秒——数值安全阀） */
    const val V_MAX = 30f

    // ==================== 状态 ====================

    /** N 体状态（位置/速度/加速度/插值帧——全部 FloatArray 零逐帧分配） */
    class NBodyState(
        val n: Int,
        val mass: FloatArray,
        /** 当前位置（步进后） */
        val px: FloatArray,
        val py: FloatArray,
        val pz: FloatArray,
        /** 上一步位置（渲染插值锚——步进前快照） */
        val prevPx: FloatArray,
        val prevPy: FloatArray,
        val prevPz: FloatArray,
        /** 速度 */
        val vx: FloatArray,
        val vy: FloatArray,
        val vz: FloatArray,
        /** 加速度（kick-drift-kick 复用） */
        val ax: FloatArray,
        val ay: FloatArray,
        val az: FloatArray,
    ) {
        /** 总动能（诊断） */
        fun kinetic(): Float {
            var e = 0f
            for (i in 0 until n) {
                val v2 = vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i]
                e += 0.5f * mass[i] * v2
            }
            return e
        }

        /** 总动量模（诊断——对称力系严格守恒锚） */
        fun momentum(): Float {
            var sx = 0f
            var sy = 0f
            var sz = 0f
            for (i in 0 until n) {
                sx += mass[i] * vx[i]
                sy += mass[i] * vy[i]
                sz += mass[i] * vz[i]
            }
            return sqrt(sx * sx + sy * sy + sz * sz)
        }

        /** 是否全在有界域内（边界测试锚） */
        fun allBounded(): Boolean {
            for (i in 0 until n) {
                val r = sqrt(px[i] * px[i] + py[i] * py[i] + pz[i] * pz[i])
                if (r > BOUND * 1.001f) return false
            }
            return true
        }
    }

    /** 新建状态（位置=初条件；速度全零由调用方填 IC；加速度先算一遍供首步 Verlet） */
    fun createState(
        n: Int,
        mass: FloatArray,
        px: FloatArray,
        py: FloatArray,
        pz: FloatArray,
    ): NBodyState {
        val s = NBodyState(
            n, mass,
            px.copyOf(), py.copyOf(), pz.copyOf(),
            px.copyOf(), py.copyOf(), pz.copyOf(),
            FloatArray(n), FloatArray(n), FloatArray(n),
            FloatArray(n), FloatArray(n), FloatArray(n),
        )
        return s
    }

    // ==================== Barnes-Hut 八叉树（节点池零分配） ====================

    /**
     * 八叉树（**正经 BH 实现**）：叶=≤1 体（重合体并合进质心不无限细分——碰撞
     * 防护）；内部节点=质心/质量聚合；[forceOn] 开角走树 θ=[THETA]。
     * 节点池上限 [MAX_NODES]（577 体 × 深度 ~8 → <6000 节点；护栏 16384）。
     */
    private const val MAX_NODES = 16384

    private class Octree {
        val ncx = FloatArray(MAX_NODES)
        val ncy = FloatArray(MAX_NODES)
        val ncz = FloatArray(MAX_NODES)
        val nhalf = FloatArray(MAX_NODES)
        val nmass = FloatArray(MAX_NODES)
        val ncomx = FloatArray(MAX_NODES)
        val ncomy = FloatArray(MAX_NODES)
        val ncomz = FloatArray(MAX_NODES)
        /** 叶节点体索引（-1=内部节点；-2=空叶） */
        val nbody = IntArray(MAX_NODES)
        /** 子节点索引×8（-1=无） */
        val nchild = IntArray(MAX_NODES * 8)
        /** 走树显式栈（字段复用——零分配；R368 上界重标：8 叉树深度帽 25 的 DFS
         *  栈上界=7·25+1=176（旧 64 是隐患位）——256 绰绰） */
        private val walkStack = IntArray(256)
        var count = 0

        fun newNode(cx: Float, cy: Float, cz: Float, half: Float): Int {
            val id = count
            count++
            ncx[id] = cx
            ncy[id] = cy
            ncz[id] = cz
            nhalf[id] = half
            nmass[id] = 0f
            ncomx[id] = 0f
            ncomy[id] = 0f
            ncomz[id] = 0f
            nbody[id] = -2
            for (k in 0 until 8) nchild[id * 8 + k] = -1
            return id
        }

        /** 建树（覆盖全体：根=包围盒）——每步重建（池复用零分配） */
        fun build(
            n: Int,
            mass: FloatArray,
            px: FloatArray,
            py: FloatArray,
            pz: FloatArray,
        ) {
            count = 0
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            var maxZ = -Float.MAX_VALUE
            for (i in 0 until n) {
                if (px[i] < minX) minX = px[i]
                if (px[i] > maxX) maxX = px[i]
                if (py[i] < minY) minY = py[i]
                if (py[i] > maxY) maxY = py[i]
                if (pz[i] < minZ) minZ = pz[i]
                if (pz[i] > maxZ) maxZ = pz[i]
            }
            val cx = (minX + maxX) / 2f
            val cy = (minY + maxY) / 2f
            val cz = (minZ + maxZ) / 2f
            var half = maxOf(maxX - minX, maxY - minY, maxZ - minZ) / 2f
            if (half < 1f) half = 1f
            val root = newNode(cx, cy, cz, half + 1f)
            for (i in 0 until n) insert(root, i, mass, px, py, pz, 0)
        }

        /** 插入（叶细分；同格重合体并合——碰撞防护：半宽 <0.5 或深 >24 不再细分） */
        private fun insert(
            node: Int,
            body: Int,
            mass: FloatArray,
            px: FloatArray,
            py: FloatArray,
            pz: FloatArray,
            depth: Int,
        ) {
            var cur = node
            var d = depth
            val m = mass[body]
            // 沿树下行至空叶（细分发生在下行中）
            while (true) {
                // 聚合质心
                val total = nmass[cur] + m
                ncomx[cur] = (ncomx[cur] * nmass[cur] + px[body] * m) / total
                ncomy[cur] = (ncomy[cur] * nmass[cur] + py[body] * m) / total
                ncomz[cur] = (ncomz[cur] * nmass[cur] + pz[body] * m) / total
                nmass[cur] = total
                if (nbody[cur] == -2) {
                    // 空叶：直接放
                    nbody[cur] = body
                    return
                }
                if (nbody[cur] >= 0) {
                    // 叶有体：重合防护或细分
                    val other = nbody[cur]
                    if (nhalf[cur] < 0.5f || d > 24 || count + 2 > MAX_NODES) {
                        // 同格/超深并合（保持 other 为代表体；质量已聚合——碰撞
                        // 不炸）。R368 节点池护栏并入同口径：大库（n·深度可超池容
                        // 16384）时退化为并合而非 newNode 越界闪退（池只出不回收）
                        return
                    }
                    // 细分：把 other 下放
                    nbody[cur] = -1
                    val k = octantOf(cur, other, px, py, pz)
                    val child = childNode(cur, k)
                    nchild[cur * 8 + k] = child
                    // 把 other 放进新子叶（直接聚合：子叶从零开始）
                    val mo = mass[other]
                    nmass[child] = mo
                    ncomx[child] = px[other]
                    ncomy[child] = py[other]
                    ncomz[child] = pz[other]
                    nbody[child] = other
                    // 继续插 body（cur 现在是内部节点）
                }
                // 内部节点：走子
                val k = octantOf(cur, body, px, py, pz)
                var c = nchild[cur * 8 + k]
                if (c < 0) {
                    if (count + 1 > MAX_NODES) return // R368 池护栏：并入当前聚合（同并合口径）
                    c = childNode(cur, k)
                    nchild[cur * 8 + k] = c
                }
                cur = c
                d++
            }
        }

        /** 体在节点的八分象限（0..7） */
        private fun octantOf(node: Int, body: Int, px: FloatArray, py: FloatArray, pz: FloatArray): Int {
            var k = 0
            if (px[body] > ncx[node]) k = k or 1
            if (py[body] > ncy[node]) k = k or 2
            if (pz[body] > ncz[node]) k = k or 4
            return k
        }

        /** 子节点创建（半宽减半） */
        private fun childNode(parent: Int, k: Int): Int {
            val h = nhalf[parent] / 2f
            val cx = ncx[parent] + if (k and 1 != 0) h else -h
            val cy = ncy[parent] + if (k and 2 != 0) h else -h
            val cz = ncz[parent] + if (k and 4 != 0) h else -h
            return newNode(cx, cy, cz, h)
        }

        /**
         * 作用在体 [i] 上的引力加速度（写 out[0..2]）。θ=[THETA]<1/√3 保证含 i
         * 的节点恒被展开（零自引力伪差）；空叶/含 i 的叶跳过。
         */
        fun forceOn(
            i: Int,
            n: Int,
            mass: FloatArray,
            px: FloatArray,
            py: FloatArray,
            pz: FloatArray,
            out: FloatArray,
        ) {
            var fx = 0f
            var fy = 0f
            var fz = 0f
            val xi = px[i]
            val yi = py[i]
            val zi = pz[i]
            // 显式栈遍历（字段复用零分配；深度 ≤ ~26）
            val stack = walkStack
            var sp = 0
            stack[sp++] = 0
            while (sp > 0) {
                val node = stack[--sp]
                val m = nmass[node]
                if (m <= 0f) continue
                val b = nbody[node]
                if (b >= 0) {
                    // 叶：单体（自体跳过；重合并合叶用聚合质量 nmass——代表位近似）
                    if (b == i) continue
                    val dx = px[b] - xi
                    val dy = py[b] - yi
                    val dz = pz[b] - zi
                    val r2 = dx * dx + dy * dy + dz * dz + SOFTENING * SOFTENING
                    val inv = G / (r2 * sqrt(r2))
                    val f = m * inv
                    fx += dx * f
                    fy += dy * f
                    fz += dz * f
                    continue
                }
                if (b == -2) continue // 空叶
                // 内部节点：开角判定
                val dx = ncomx[node] - xi
                val dy = ncomy[node] - yi
                val dz = ncomz[node] - zi
                val d = sqrt(dx * dx + dy * dy + dz * dz)
                val s = nhalf[node] * 2f
                if (d > 1e-4f && s / d < THETA) {
                    // 远场近似：点质量质心
                    val r2 = dx * dx + dy * dy + dz * dz + SOFTENING * SOFTENING
                    val inv = G / (r2 * sqrt(r2))
                    val f = m * inv
                    fx += dx * f
                    fy += dy * f
                    fz += dz * f
                } else {
                    // 展开：子节点入栈
                    for (k in 0 until 8) {
                        val c = nchild[node * 8 + k]
                        if (c >= 0) stack[sp++] = c
                    }
                }
            }
            out[0] = fx
            out[1] = fy
            out[2] = fz
        }

        /**
         * R303 斥力版走树（与 [forceOn] 同构，只加不改）：符号反转（指向远离源）+
         * 参数化软化/截断。节点级保守剔除：com 距 > cutoff+0.9·节点宽 → 整节点跳过
         * （节点内最远比 com 至多远 √3·half≈0.87·宽——剔除条件保守不丢界内对）；
         * 远场近似对仍按 com 截断判定（近截断沿的近似差由全域对照测试口径规避）。
         */
        fun forceOnRepel(
            i: Int,
            mass: FloatArray,
            px: FloatArray,
            py: FloatArray,
            pz: FloatArray,
            k: Float,
            soft2: Float,
            cut2: Float,
            out: FloatArray,
        ) {
            var fx = 0f
            var fy = 0f
            var fz = 0f
            val xi = px[i]
            val yi = py[i]
            val zi = pz[i]
            val cutoff = sqrt(cut2)
            val stack = walkStack
            var sp = 0
            stack[sp++] = 0
            while (sp > 0) {
                val node = stack[--sp]
                val m = nmass[node]
                if (m <= 0f) continue
                val b = nbody[node]
                if (b >= 0) {
                    if (b == i) continue
                    val dx = xi - px[b]
                    val dy = yi - py[b]
                    val dz = zi - pz[b]
                    val d2 = dx * dx + dy * dy + dz * dz
                    if (d2 > cut2) continue
                    val r2 = d2 + soft2
                    val f = k * m / (r2 * sqrt(r2))
                    fx += dx * f
                    fy += dy * f
                    fz += dz * f
                    continue
                }
                if (b == -2) continue
                val dx = xi - ncomx[node]
                val dy = yi - ncomy[node]
                val dz = zi - ncomz[node]
                val d2 = dx * dx + dy * dy + dz * dz
                val d = sqrt(d2)
                val s = nhalf[node] * 2f
                if (d > cutoff + 0.9f * s) continue // 保守剔除：整节点在截断外
                if (d > 1e-4f && s / d < THETA) {
                    if (d2 > cut2) continue // 远场对按 com 截断
                    val r2 = d2 + soft2
                    val f = k * m / (r2 * sqrt(r2))
                    fx += dx * f
                    fy += dy * f
                    fz += dz * f
                } else {
                    for (kk in 0 until 8) {
                        val c = nchild[node * 8 + kk]
                        if (c >= 0) stack[sp++] = c
                    }
                }
            }
            out[0] = fx
            out[1] = fy
            out[2] = fz
        }
    }

    private val tree = Octree()
    private val fScratch = FloatArray(3)

    /**
     * R368 引擎互斥锁（**线上闪退根因修复**）：本 object 是进程级单例，唯一的
     * [tree]/[fScratch] 被两路**并发**消费——主线程双星 Verlet 步进（TasteGalaxy
     * Physics 5s 拍，[NBodyState] 逐对 n=2——px/py/pz 长 2）× Default 线程涌现
     * 布局斥力（TasteMapScreen 装配 layoutTasteMap→TasteEmergentLayout，n=全库
     * 体索引 0..100+）。无锁时**共享一棵树被并发 build+走树**：后台 build 重写
     * nbody[]=113 而主线程 forceOn 正走树 → px[113] 越界闪退（实锤：
     * ArrayIndexOutOfBoundsException length=2; index=113 @forceOn——主线程栈
     * stepLocked→step→accelBarnesHut，5s 拍与装配重算同窗必现）。fScratch/
     * walkStack 串写同病（不炸也静默污染两路结果）。
     *
     * 修法=**build+全体走树原子化**（[accelBarnesHut]/[accelBarnesHutRepel] 整函数
     * 锁内——build 与走树分两次锁=树仍可被中间换血，必须一口锅端）。锁纪律=
     * 单锁无环：锁内零外部调用零嵌套（沿 TasteGalaxyPhysics renderLock 同款），
     * Java 监视器可重入（step→accelBarnesHut 无自锁）。成本口径：两侧临界区皆
     * µs 级（主线程 2 体树；布局每轮一趟 O(N log N)）——5s 拍与布局轮互不可感
     * 阻塞。各调用方自有状态（NBodyState/布局 fx 数组）线程封闭，不受此锁管辖。
     */
    private val engineLock = Any()

    /** BH 树求全体加速度（写 ax/ay/az——每步一次；R368 起锁内原子见 [engineLock]） */
    fun accelBarnesHut(s: NBodyState) {
        synchronized(engineLock) {
            tree.build(s.n, s.mass, s.px, s.py, s.pz)
            for (i in 0 until s.n) {
                tree.forceOn(i, s.n, s.mass, s.px, s.py, s.pz, fScratch)
                s.ax[i] = fScratch[0]
                s.ay[i] = fScratch[1]
                s.az[i] = fScratch[2]
            }
        }
    }

    /** 直接求和加速度（O(N²)——测试对照与守恒口径） */
    fun accelDirect(s: NBodyState) {
        for (i in 0 until s.n) {
            var fx = 0f
            var fy = 0f
            var fz = 0f
            for (j in 0 until s.n) {
                if (j == i) continue
                val dx = s.px[j] - s.px[i]
                val dy = s.py[j] - s.py[i]
                val dz = s.pz[j] - s.pz[i]
                val r2 = dx * dx + dy * dy + dz * dz + SOFTENING * SOFTENING
                val inv = G / (r2 * sqrt(r2))
                val f = s.mass[j] * inv
                fx += dx * f
                fy += dy * f
                fz += dz * f
            }
            s.ax[i] = fx
            s.ay[i] = fy
            s.az[i] = fz
        }
    }

    /**
     * 一步推进（**kick-drift-kick velocity Verlet**——辛积分）：
     * v += 0.5·a_old·dt → x += v·dt → 重算 a → v += 0.5·a_new·dt。
     * 前后调用 [applyBound]/[applyVCap]（护栏项——不进守恒口径）。
     */
    fun step(s: NBodyState, dt: Float) {
        val n = s.n
        // kick（旧加速度）
        for (i in 0 until n) {
            s.vx[i] += 0.5f * s.ax[i] * dt
            s.vy[i] += 0.5f * s.ay[i] * dt
            s.vz[i] += 0.5f * s.az[i] * dt
        }
        // drift
        for (i in 0 until n) {
            s.px[i] += s.vx[i] * dt
            s.py[i] += s.vy[i] * dt
            s.pz[i] += s.vz[i] * dt
        }
        // 新加速度
        accelBarnesHut(s)
        // kick（新加速度）
        for (i in 0 until n) {
            s.vx[i] += 0.5f * s.ax[i] * dt
            s.vy[i] += 0.5f * s.ay[i] * dt
            s.vz[i] += 0.5f * s.az[i] * dt
        }
    }

    /** 软墙边界（出界钳回+径向速度反转衰减 0.5——防飞出护栏） */
    fun applyBound(s: NBodyState) {
        for (i in 0 until s.n) {
            val r = sqrt(s.px[i] * s.px[i] + s.py[i] * s.py[i] + s.pz[i] * s.pz[i])
            if (r > BOUND) {
                val k = BOUND / r
                s.px[i] *= k
                s.py[i] *= k
                s.pz[i] *= k
                // 径向速度分量（外向）反转衰减
                val ux = s.px[i] / BOUND
                val uy = s.py[i] / BOUND
                val uz = s.pz[i] / BOUND
                val vr = s.vx[i] * ux + s.vy[i] * uy + s.vz[i] * uz
                if (vr > 0f) {
                    s.vx[i] -= 1.5f * vr * ux
                    s.vy[i] -= 1.5f * vr * uy
                    s.vz[i] -= 1.5f * vr * uz
                }
            }
        }
    }

    /** 速度护栏（|v| 上限——数值安全阀） */
    fun applyVCap(s: NBodyState) {
        for (i in 0 until s.n) {
            val v2 = s.vx[i] * s.vx[i] + s.vy[i] * s.vy[i] + s.vz[i] * s.vz[i]
            if (v2 > V_MAX * V_MAX) {
                val k = V_MAX / sqrt(v2)
                s.vx[i] *= k
                s.vy[i] *= k
                s.vz[i] *= k
            }
        }
    }

    /**
     * 总能量（动能+软化势能 O(N²)——测试诊断口径；势能=−Σ G·mi·mj/√(r²+ε²)
     * 对）。守恒测试用它（Verlet 辛性质=能量有界振荡）。
     */
    fun totalEnergy(s: NBodyState): Float {
        var ke = 0f
        for (i in 0 until s.n) {
            ke += 0.5f * s.mass[i] * (s.vx[i] * s.vx[i] + s.vy[i] * s.vy[i] + s.vz[i] * s.vz[i])
        }
        var pe = 0f
        for (i in 0 until s.n) {
            for (j in i + 1 until s.n) {
                val dx = s.px[j] - s.px[i]
                val dy = s.py[j] - s.py[i]
                val dz = s.pz[j] - s.pz[i]
                val r = sqrt(dx * dx + dy * dy + dz * dz + SOFTENING * SOFTENING)
                pe -= G * s.mass[i] * s.mass[j] / r
            }
        }
        return ke + pe
    }

    // ==================== R303 布局场斥力口（只加不改——布局场=斥力符号的 BH） ====================

    /**
     * R303 涌现布局场专用：**符号反转的 BH 力=斥力**（design-multiscale-emergent §3.2
     * 「树不挑力的符号，质心聚合不变式相同」的落法）。作用在体 i 上的斥力加速度
     * = Σ_j k·m_j·(r_i−r_j)/(|r_i−r_j|²+ε²)^1.5（指向远离 j）；截断 [cutoff]：
     * 超出截断距的对/节点跳过（斥力是短中程力——远者各安天命）。
     *
     * 与引力族的分野：布局场求收敛不求守恒（阻尼退火在 TasteEmergentLayout），
     * 本口只供力；守恒测试仍走引力族原口径（本口不进守恒口径）。
     *
     * @param k 斥力强度系数（k_R）
     * @param softening 软化长度（布局口径 120，与引力 SOFTENING=80 分档）
     * @param cutoff 截断距（设计 R_c=1400）；传 ≥1e9 即全域（测试对照口径）
     */
    fun accelBarnesHutRepel(
        n: Int,
        mass: FloatArray,
        px: FloatArray,
        py: FloatArray,
        pz: FloatArray,
        k: Float,
        softening: Float,
        cutoff: Float,
        outAx: FloatArray,
        outAy: FloatArray,
        outAz: FloatArray,
    ) {
        // R368：build+走树锁内原子（与主线程 accelBarnesHut 互斥——见 [engineLock]）
        synchronized(engineLock) {
            tree.build(n, mass, px, py, pz)
            val soft2 = softening * softening
            val cut2 = cutoff * cutoff
            for (i in 0 until n) {
                tree.forceOnRepel(i, mass, px, py, pz, k, soft2, cut2, fScratch)
                outAx[i] = fScratch[0]
                outAy[i] = fScratch[1]
                outAz[i] = fScratch[2]
            }
        }
    }

    /**
     * 斥力直接求和对照（O(N²)——与 [accelBarnesHutRepel] 互证，同 [accelDirect] 纪律）：
     * 截断为**逐对精确**口径（树口径=节点级保守剔除，近截断沿有近似差——对照测试
     * 用 cutoff≥1e9 的全域口径消这档差）。
     */
    fun accelDirectRepel(
        n: Int,
        mass: FloatArray,
        px: FloatArray,
        py: FloatArray,
        pz: FloatArray,
        k: Float,
        softening: Float,
        cutoff: Float,
        outAx: FloatArray,
        outAy: FloatArray,
        outAz: FloatArray,
    ) {
        val soft2 = softening * softening
        val cut2 = cutoff * cutoff
        for (i in 0 until n) {
            var fx = 0f
            var fy = 0f
            var fz = 0f
            for (j in 0 until n) {
                if (j == i) continue
                val dx = px[i] - px[j]
                val dy = py[i] - py[j]
                val dz = pz[i] - pz[j]
                val d2 = dx * dx + dy * dy + dz * dz
                if (d2 > cut2) continue
                val r2 = d2 + soft2
                val f = k * mass[j] / (r2 * sqrt(r2))
                fx += dx * f
                fy += dy * f
                fz += dz * f
            }
            outAx[i] = fx
            outAy[i] = fy
            outAz[i] = fz
        }
    }

    /**
     * 圆轨道初速度（IC 单口——绕质量 [mCenter] 中心天体的圆轨道切向速度）：
     * v=√(G·mCenter/r)；方向=位置向量×上向量的切向（[sign] 定顺逆）。
     */
    fun circularVelocity(mCenter: Float, r: Float, sign: Float): Float =
        sqrt(G * mCenter / r.coerceAtLeast(1f)) * sign

    /**
     * 星系旋转曲线速度（**IC 的全域分量**——真 N 体热化实锤后的修法：每星绕
     * 「星系中心+包络质量」的圆轨道速度 v=√(G·M_enc(r)/r)，全部同向=自转支持的
     * 星系（不坍缩不热化——旋转平衡；差速剪切=星系长期演化））。调用方传入该星
     * 半径处的包络质量（排序+前缀和算好）。
     *
     * R343（怠速治理）后**生产零消费**：全场缓旋按用户裁决退役（「屏保级花招」
     * ——ui 物理场 TasteGalaxyPhysics 已撤除旋转曲线 IC）；函数保留=历史口径
     * 存档（源锚锁其在物理场绝迹——退役干净闸）。
     */
    fun icGalacticCircular(mEnclosed: Float, r: Float): Float =
        sqrt(G * mEnclosed / r.coerceAtLeast(20f))

    /**
     * R343 渲染插值相位（**纯数学单源**——「低频步进拍 × draw 相位线性插值」机制
     * 的相位口；消费方=ui 物理场 TasteGalaxyPhysics.beginRenderFrame，单口收敛）：
     *
     *  - 相位 = (nowMs − lastStepMs)/tickMs 钳 [0,1]——负差归 0（步进未到零相位）；
     *    超拍钳 1（步进迟到时停在当拍位，迟到的拍由下一次步进补）；
     *  - **时钟域契约（本函数唯一戒律）**：[nowMs] 与 [lastStepMs] 必须同一时钟域
     *    （同钟相减才有 0..tick 的真时间差）。R343 实锤的历史坑：调用方传 epoch
     *    墙钟（System.currentTimeMillis ≈1.79e12）而步进侧存 uptime
     *    （SystemClock.uptimeMillis ≈1e9）——差恒 +1.79e12 → 相位恒钳 1.0 →
     *    插值死拍 → 星只在 5s 步进拍上跳格（用户「一卡一卡顺时针转」的根因，
     *    自 R249b 接线日起带病）。消费方因此自取步进侧同款 uptime 钟（源锚锁死
     *    两侧同符号 SystemClock.uptimeMillis——时钟域错配类复发闸）。
     */
    fun renderPhase01(nowMs: Long, lastStepMs: Long, tickMs: Long): Float {
        val dtMs = (nowMs - lastStepMs).coerceAtLeast(0L)
        return (dtMs.toFloat() / tickMs.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * R343 D2 双星孤立二体圆轨道速度（**软化力修正口径**——留种 IC 单口）：
     *
     * Plummer 软化（ε=[SOFTENING]=80）在双星对典型间距（d≈40~400）与 ε 同量级
     * 甚至更小——牛顿口径 v=√(G·mSum/d) 高估向心力 → 轨道外旋漂移（退役
     * 「干净」红线不容残漂）。软化两体圆轨道精确解：ω²=G·mSum/(d²+ε²)^{3/2}
     * → v_rel=ω·d=√(G·mSum·d²/(d²+ε²)^{3/2})（d≫ε 收敛牛顿口径=量纲自检）。
     * 消费方=ui 物理场双星对 IC（动量零拆分：v_a=+m_b/mSum·v_rel·t̂、
     * v_b=−m_a/mSum·v_rel·t̂ → 对质心静止永驻烘焙位）。
     */
    fun binaryCircularVelSoftened(mSum: Float, d: Float): Float {
        val d2 = d * d
        val s2 = SOFTENING * SOFTENING
        return sqrt(G * mSum * d2 / ((d2 + s2) * sqrt(d2 + s2)))
    }
}
