package dev.tgfeed.core.tastemap

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * R303 多尺度涌现星图 P0——**涌现布局引擎·离线定形**（纯函数，零 Android 依赖，JVM
 * 单测锚定；design-multiscale-emergent §6.1~§6.4 终审稿落地，与 Canvas/GL 现渲染零耦合
 * ——本层只产「布局场输出」=每星新坐标表）。
 *
 * 引擎口径（§6.1 逐条）：
 *  1. 输入=频道复合刚体节点（质量/半径/λ）+稀疏引力对（≤[TasteEmergentForces.MAX_PAIRS]）
 *     +初值（调用方给——现役 V3 位置或其哈希角降档版，**不是随机**：定形=对今天的宇宙
 *     做局部松弛，涌现形状在用户认得的骨架上长出来）；
 *  2. 每轮：BH 树全对斥力（TasteNBody.accelBarnesHutRepel，O(N log N)）+基底向心
 *     （树根井心=原点）+稀疏弹簧直算+硬核近邻（确定性格网扫描）+可选暗能量（默认关）；
 *  3. 速度阻尼 [TasteEmergentForces.DAMPING] 积分、步长 ×T（退火 T:1→0 线性——先大调
 *     后细修）；单步位移帽 [TasteEmergentForces.DISP_CAP]·T（安全不等式⑤机械化）；
 *  4. 每 [TasteEmergentForces.GATE_EVERY] 轮查稳态硬闸（§6.3），过闸早停；
 *  5. 收尾：死星晕漂平衡位移（§3.4，可选）→ **位置级投影兜底**（逐对推开残余硬核
 *     违约——「最小间距违约数=0」是可证伪承诺，不是调参缓解）；
 *  6. 可选万步浸泡（[TasteEmergentForces.Params.soakSteps]——§6.3-6 长期不坍缩硬闸：
 *     云幅 R90 有界/质心不漂/近邻距分布稳态 KS 检验）。
 *
 * 力的符号口径：弹簧=几何约束直接作加速度（不进质量——布局场求形不求质点动力学，
 * 否则轻星被弹簧甩飞）；斥力/基底向心/暗能量=单位质量加速度（质量进公式=天文语义轴）。
 * 弱核力进弹簧项：s_eff=s·√(λa·λb)（死星停止拉拢别人，也不拽住别人——对称化是布局
 * 场的刻意选择，design §3.4 作用面①）。
 *
 * 保形边界（§6.5 条款一）：本引擎只在离线定形/局部温启运行；在线态四力绝不自由积分
 * ——热化从机制上删除，不是调参防。
 */
object TasteEmergentLayout {

    /**
     * 频道节点（复合刚体——系内 tag/内容不进力场，§3.3 尺度分离）。
     *
     * @property mass01 质量（ln1p(条数) 归一，沿用 TasteGalaxyPhysics 口径）
     * @property rho 体量半径（bodyRadius 口径 2.2~26）
     * @property lambda 弱核力衰变因子（TasteEmergentForces.decayLambda 产物）
     * @property rPref 观察者家弹簧径向偏好（§3.8；NaN=无家弹簧——快照缺观看证据的诚实档）
     */
    class Node(
        val chatId: Long,
        val mass01: Float,
        val rho: Float,
        val lambda: Float,
        val rPref: Float,
        val x: Float,
        val y: Float,
        val z: Float,
    )

    /**
     * 稀疏引力对（§3.1）。
     *
     * @property s 相关强度（TasteEmergentForces.strengthOf 产物，已含弱核力外的全源合成）
     * @property L 理想距（TasteEmergentForces.idealDist 产物——构造性满足安全不等式③）
     * @property strong 双星/强互链束缚态标记（强核力在全图力场的唯一现身：k_S=10·k_G，§3.3）
     */
    class Link(val a: Int, val b: Int, val s: Float, val L: Float, val strong: Boolean = false)

    /** 收敛探针行（每 probeEvery 轮一条——收敛曲线原料） */
    class Probe(
        val round: Int,
        val temp: Float,
        val maxForce: Float,
        val maxDisp: Float,
        val stress: Float,
        val violations: Int,
        val r90: Float,
        val cx: Float,
        val cy: Float,
        val cz: Float,
    )

    /** 稳态六闸体检结果（§6.3；soak 系三条只在浸泡段结算） */
    class Gates(
        val forceOk: Boolean,
        val dispOk: Boolean,
        val violationOk: Boolean,
        val stressDriftOk: Boolean,
        val healthOk: Boolean,
    ) {
        /** 前五闸（定形段）全过 */
        fun shapingPass(): Boolean = forceOk && dispOk && violationOk && stressDriftOk && healthOk
    }

    /** 浸泡结算（§6.3-6 三条+判废口径） */
    class SoakReport(
        val steps: Int,
        val r90Start: Float,
        val r90End: Float,
        /** 云幅相对变化（(末−初)/初） */
        val r90Drift: Float,
        /** 质心漂移（世界单位） */
        val centroidDrift: Float,
        /** r90 探针序列中单调收缩段占比（>0.7 且 r90 缩=判废征兆） */
        val shrinkFrac: Float,
        /** 近邻距分布 KS 统计量（首 1/3 窗 vs 末 1/3 窗） */
        val ksD: Float,
        /** KS 临界值（α=0.05） */
        val ksCrit: Float,
    ) {
        /** 长期不坍缩硬闸（§6.3-6）：云幅有界 ∧ 质心不漂 ∧ 间距分布稳态 */
        fun steady(): Boolean =
            abs(r90Drift) < TasteEmergentForces.SOAK_R90_DRIFT &&
                centroidDrift < TasteEmergentForces.SOAK_CENTROID_DRIFT &&
                ksD < ksCrit

        /** 判废证词（§6.4：纯引力配置必塌的断言锚）：云幅显著单调收缩 */
        fun collapsing(): Boolean = r90Drift < -2f * TasteEmergentForces.SOAK_R90_DRIFT && shrinkFrac > 0.7f
    }

    /** 定形产物（布局场输出=每星新坐标表+体检报告；渲染侧零耦合——谁消费谁查表） */
    class Result(
        val n: Int,
        /** 平衡位表（与输入节点同序，x/y/z 交错 3n） */
        val positions: FloatArray,
        val rounds: Int,
        /** 定形段早停收敛标记（false=跑满上限——§6.3 超时口径：取历史最优由调用方裁） */
        val converged: Boolean,
        val gates: Gates,
        val stressFinal: Float,
        val violationsFinal: Int,
        val r90: Float,
        val probes: List<Probe>,
        val soak: SoakReport?,
    )

    // ==================== 定形主循环 ====================

    /**
     * 离线定形（装配期一次性/局部温启共用同一核——温启=调用方只传受影响子图并把
     * 初值设为现位置，引擎不知情=口径单一）。
     *
     * 全确定性：格网扫描按星索引序、无任何哈希迭代序、无随机数——同数据同宇宙
     * （R 系铁律延续）。
     */
    fun shape(
        nodes: List<Node>,
        links: List<Link>,
        p: TasteEmergentForces.Params = TasteEmergentForces.Params(),
        probeEvery: Int = 10,
    ): Result {
        val n = nodes.size
        require(n > 0) { "空星表无定形" }
        val F = TasteEmergentForces
        val x = FloatArray(n) { nodes[it].x }
        val y = FloatArray(n) { nodes[it].y }
        val z = FloatArray(n) { nodes[it].z }
        val vx = FloatArray(n)
        val vy = FloatArray(n)
        val vz = FloatArray(n)
        val mass = FloatArray(n) { nodes[it].mass01 }
        val rho = FloatArray(n) { nodes[it].rho }
        val lambda = FloatArray(n) { nodes[it].lambda }
        var mTot = 0f
        for (i in 0 until n) mTot += mass[i]
        // ---- 死星晕漂=定形输入侧平衡位移（§3.4：彩排实锤——若收尾才施加，僵死弹簧被
        //      拉爆、应力指标失真；开局先迁墓地轨道，全云围绕它重新平衡=「平衡位移式」
        //      的时序正解；温启口径不变） ----
        // 墓地轨道带厚（0.94~1.06×带心，chatId 哈希确定性——单值薄壳=百星挤同球面，
        // 硬核违约常青/力闸永红的病根，彩排第九轮落法）
        val haloBand = FloatArray(n) {
            F.HALO_BAND_FRAC * F.R_GALAXY * (0.94f + 0.12f * TasteSpace.h01(nodes[it].chatId, 305))
        }
        if (p.applyHaloDrift) {
            for (i in 0 until n) {
                val r = sqrt(x[i] * x[i] + y[i] * y[i] + z[i] * z[i])
                if (r < 1e-4f) continue
                val k = F.haloShiftedRadius(r, lambda[i], haloBand[i]) / r
                x[i] *= k
                y[i] *= k
                z[i] *= k
            }
        }
        // ---- 径向锚目标统一口径（彩排第七轮落法）：rPrefEff = haloShifted(rPref 或初值
        //      半径, λ)——家弹簧与晕漂是**同一根径向弹簧的两个端态**（活星锚 rPref、
        //      死星锚墓地轨道带；否则死星无锚被 λ·向心缓慢拉回=质心慢漂的病根）。
        //      NaN 且活（λ≥0.99）=无锚自由星。 ----
        val rPrefEff = FloatArray(n) { i ->
            val rp = nodes[i].rPref
            when {
                !rp.isNaN() -> F.haloShiftedRadius(rp, lambda[i], haloBand[i])
                lambda[i] < 0.99f -> {
                    val r0 = sqrt(x[i] * x[i] + y[i] * y[i] + z[i] * z[i])
                    F.haloShiftedRadius(r0, lambda[i], haloBand[i])
                }
                else -> Float.NaN
            }
        }
        val fx = FloatArray(n)
        val fy = FloatArray(n)
        val fz = FloatArray(n)
        // 弹簧弱核力耦合预合成（s_eff=s·√(λa·λb)）
        val linkA = IntArray(links.size) { links[it].a }
        val linkB = IntArray(links.size) { links[it].b }
        val linkS = FloatArray(links.size) {
            links[it].s * sqrt(lambda[links[it].a] * lambda[links[it].b])
        }
        val linkL = FloatArray(links.size) { links[it].L }
        val linkK = FloatArray(links.size) { if (links[it].strong) p.kS else p.kG }
        val probes = ArrayList<Probe>(p.roundsMax / probeEvery + p.soakSteps / 100 + 8)
        var stressAtLastGate = -1f
        var converged = false
        var round = 0
        var lastMaxDisp = Float.MAX_VALUE
        var lastMaxForce = Float.MAX_VALUE
        var lastStress = Float.NaN
        var lastViolations = -1

        while (round < p.roundsMax) {
            val temp = 1f - round.toFloat() / p.roundsMax
            // ---- 力装配 ----
            TasteNBody.accelBarnesHutRepel(
                n, mass, x, y, z, p.kR, F.EPS_REPEL, F.R_CUTOFF, fx, fy, fz,
            )
            for (i in 0 until n) {
                val r = sqrt(x[i] * x[i] + y[i] * y[i] + z[i] * z[i])
                if (r > 1e-4f) {
                    val ux = x[i] / r
                    val uy = y[i] / r
                    val uz = z[i] / r
                    // 基底向心（井心=原点；×λ_i=弱核力作用面①对基底耦合的一致延伸——
                    // 死星停止被银河拉住，斥力自然把它迁向墓地轨道）+暗能量（默认关）
                    var aRad = -lambda[i] * F.baseGravityAccel(r, mTot, p.gLay) + F.darkEnergyAccel(r, p.kDE)
                    // 径向锚弹簧（§3.8 偏心+§3.4 晕漂统一端态；×λ_i=死星握力衰变）
                    val rp = rPrefEff[i]
                    if (!rp.isNaN()) aRad += -p.kHome * p.kG * (r - rp) * lambda[i]
                    fx[i] += aRad * ux
                    fy[i] += aRad * uy
                    fz[i] += aRad * uz
                }
            }
            // 稀疏弹簧（直算；几何约束直接作加速度）
            for (e in links.indices) {
                val a = linkA[e]
                val b = linkB[e]
                val dx = x[b] - x[a]
                val dy = y[b] - y[a]
                val dz = z[b] - z[a]
                val d = sqrt(dx * dx + dy * dy + dz * dz)
                if (d < 1e-4f) continue
                val f = linkK[e] * linkS[e] * (d - linkL[e]) / d
                val ax = f * dx
                val ay = f * dy
                val az = f * dz
                fx[a] += ax
                fy[a] += ay
                fz[a] += az
                fx[b] -= ax
                fy[b] -= ay
                fz[b] -= az
            }
            // 硬核（格网近邻；违约=0 的力层）
            applyHardCore(n, x, y, z, rho, p.kHard, fx, fy, fz)
            // ---- 阻尼退火积分（步长 ×T；位移帽机械化安全不等式⑤） ----
            val cap = F.DISP_CAP * temp
            var maxF = 0f
            var maxD = 0f
            for (i in 0 until n) {
                vx[i] = (vx[i] + fx[i] * temp) * p.damping
                vy[i] = (vy[i] + fy[i] * temp) * p.damping
                vz[i] = (vz[i] + fz[i] * temp) * p.damping
                var dx = vx[i] * temp
                var dy = vy[i] * temp
                var dz = vz[i] * temp
                val dl = sqrt(dx * dx + dy * dy + dz * dz)
                if (dl > cap && dl > 1e-6f) {
                    val k = cap / dl
                    dx *= k
                    dy *= k
                    dz *= k
                }
                x[i] += dx
                y[i] += dy
                z[i] += dz
                val fl = sqrt(fx[i] * fx[i] + fy[i] * fy[i] + fz[i] * fz[i])
                if (fl > maxF) maxF = fl
                if (dl > maxD) maxD = minOf(dl, cap)
            }
            lastMaxForce = maxF
            lastMaxDisp = maxD
            round++
            // ---- 探针与稳态闸 ----
            if (round % probeEvery == 0 || round == p.roundsMax) {
                val stress = edgeStress(n, x, y, z, links)
                val viol = countViolations(n, x, y, z, rho)
                lastStress = stress
                lastViolations = viol
                probes += makeProbe(round, temp, maxF, maxD, stress, viol, n, x, y, z)
            }
            if (round % F.GATE_EVERY == 0 && stressAtLastGate >= 0f && !lastStress.isNaN()) {
                val drift = abs(lastStress - stressAtLastGate) / stressAtLastGate.coerceAtLeast(1e-6f)
                val g = Gates(
                    forceOk = lastMaxForce < F.GATE_FORCE,
                    dispOk = lastMaxDisp < F.GATE_DISP,
                    violationOk = lastViolations == 0,
                    stressDriftOk = drift < F.GATE_STRESS_DRIFT,
                    healthOk = !layoutDegenerateNow(n, x, y, z),
                )
                if (g.shapingPass()) {
                    converged = true
                    break
                }
            }
            if (round % F.GATE_EVERY == 0 && !lastStress.isNaN()) stressAtLastGate = lastStress
        }

        // ---- 收尾：位置级投影兜底（违约清零——晕漂已在开局施加，此处不再位移） ----
        projectViolations(n, x, y, z, rho)
        var violFinal = countViolations(n, x, y, z, rho)
        var stressFinal = edgeStress(n, x, y, z, links, lambda)

        // ---- 万步浸泡（§6.3-6；定形后同核延跑，恒温微扰 T=0.1=持续热浴等价） ----
        val soak = if (p.soakSteps > 0) {
            soakRun(n, x, y, z, vx, vy, vz, mass, rho, lambda, rPrefEff,
                linkA, linkB, linkS, linkL, linkK, mTot, p, probes)
        } else null
        // 浸泡热浴会再生少量硬核违约（彩排第十一轮实锤：浸泡末窗 ~370 对）——
        // 输出前再扫一道投影兜底，保证**落表的平衡位违约=0**（硬条款管到交付物）
        if (p.soakSteps > 0) {
            projectViolations(n, x, y, z, rho)
            violFinal = countViolations(n, x, y, z, rho)
            stressFinal = edgeStress(n, x, y, z, links, lambda)
        }

        val r90 = radiusQuantile(n, x, y, z, 0.9f)
        val positions = FloatArray(3 * n)
        for (i in 0 until n) {
            positions[3 * i] = x[i]
            positions[3 * i + 1] = y[i]
            positions[3 * i + 2] = z[i]
        }
        return Result(
            n, positions,
            round, converged,
            Gates(
                forceOk = lastMaxForce < F.GATE_FORCE,
                dispOk = lastMaxDisp < F.GATE_DISP,
                violationOk = violFinal == 0,
                stressDriftOk = true, // 收尾后应力漂移不再适用——定形段闸已结算于 converged
                healthOk = !layoutDegenerateNow(n, x, y, z),
            ),
            stressFinal, violFinal, r90, probes, soak,
        )
    }

    // ==================== 硬核近邻（确定性格网——扫描序=星索引序，零哈希迭代序） ====================

    private const val GRID_CELL = 128f

    private fun gridKey(cx: Int, cy: Int, cz: Int): Int =
        (cx + 128) + (cy + 128) * 256 + (cz + 128) * 65536

    private fun buildGrid(n: Int, x: FloatArray, y: FloatArray, z: FloatArray): HashMap<Int, ArrayList<Int>> {
        val map = HashMap<Int, ArrayList<Int>>(n * 2)
        for (i in 0 until n) {
            val k = gridKey(
                (x[i] / GRID_CELL).toInt(), (y[i] / GRID_CELL).toInt(), (z[i] / GRID_CELL).toInt(),
            )
            map.getOrPut(k) { ArrayList(4) }.add(i)
        }
        return map
    }

    /** 硬核弹簧力装配（d<d_min 推开；对称成对，j>i 各结一次） */
    private fun applyHardCore(
        n: Int, x: FloatArray, y: FloatArray, z: FloatArray, rho: FloatArray, kHard: Float,
        fx: FloatArray, fy: FloatArray, fz: FloatArray,
    ) {
        val grid = buildGrid(n, x, y, z)
        for (i in 0 until n) {
            val cx = (x[i] / GRID_CELL).toInt()
            val cy = (y[i] / GRID_CELL).toInt()
            val cz = (z[i] / GRID_CELL).toInt()
            for (ox in -1..1) for (oy in -1..1) for (oz in -1..1) {
                val cell = grid[gridKey(cx + ox, cy + oy, cz + oz)] ?: continue
                for (j in cell) {
                    if (j <= i) continue
                    val dx = x[j] - x[i]
                    val dy = y[j] - y[i]
                    val dz = z[j] - z[i]
                    val d = sqrt(dx * dx + dy * dy + dz * dz)
                    val dMin = TasteEmergentForces.hardMinDist(rho[i], rho[j])
                    if (d >= dMin) continue
                    val a = TasteEmergentForces.hardCoreAccel(d, dMin, kHard)
                    if (d < 1e-4f) {
                        // 全同点退化：沿索引确定性方向推开（i 沿 −X、j 沿 +X）
                        fx[i] -= a
                        fx[j] += a
                        continue
                    }
                    val ux = dx / d
                    val uy = dy / d
                    val uz = dz / d
                    fx[i] -= a * ux
                    fy[i] -= a * uy
                    fz[i] -= a * uz
                    fx[j] += a * ux
                    fy[j] += a * uy
                    fz[j] += a * uz
                }
            }
        }
    }

    /** 最小间距违约数（硬条款体检；d<d_min·0.999 计违约——浮点沿留 0.1% 死区） */
    fun countViolations(n: Int, x: FloatArray, y: FloatArray, z: FloatArray, rho: FloatArray): Int {
        val grid = buildGrid(n, x, y, z)
        var count = 0
        for (i in 0 until n) {
            val cx = (x[i] / GRID_CELL).toInt()
            val cy = (y[i] / GRID_CELL).toInt()
            val cz = (z[i] / GRID_CELL).toInt()
            for (ox in -1..1) for (oy in -1..1) for (oz in -1..1) {
                val cell = grid[gridKey(cx + ox, cy + oy, cz + oz)] ?: continue
                for (j in cell) {
                    if (j <= i) continue
                    val dx = x[j] - x[i]
                    val dy = y[j] - y[i]
                    val dz = z[j] - z[i]
                    val d = sqrt(dx * dx + dy * dy + dz * dz)
                    if (d < TasteEmergentForces.hardMinDist(rho[i], rho[j]) * 0.999f) count++
                }
            }
        }
        return count
    }

    /**
     * 位置级投影兜底（§3.2 收尾条款）：逐对把残余违约推开（各退半差），
     * 重复扫到违约=0（收敛性：每轮推开严格增大违约对间距，400 轮硬顶防护）。
     */
    fun projectViolations(
        n: Int, x: FloatArray, y: FloatArray, z: FloatArray, rho: FloatArray, maxPasses: Int = 400,
    ): Int {
        var pass = 0
        while (pass < maxPasses) {
            val grid = buildGrid(n, x, y, z)
            var touched = false
            for (i in 0 until n) {
                val cx = (x[i] / GRID_CELL).toInt()
                val cy = (y[i] / GRID_CELL).toInt()
                val cz = (z[i] / GRID_CELL).toInt()
                for (ox in -1..1) for (oy in -1..1) for (oz in -1..1) {
                    val cell = grid[gridKey(cx + ox, cy + oy, cz + oz)] ?: continue
                    for (j in cell) {
                        if (j <= i) continue
                        var dx = x[j] - x[i]
                        var dy = y[j] - y[i]
                        var dz = z[j] - z[i]
                        var d = sqrt(dx * dx + dy * dy + dz * dz)
                        val dMin = TasteEmergentForces.hardMinDist(rho[i], rho[j])
                        if (d >= dMin * 0.999f) continue
                        touched = true
                        if (d < 1e-4f) {
                            dx = 1f; dy = 0f; dz = 0f; d = 1f // 全同点退化方向
                        }
                        val push = (dMin - d) / 2f + 0.5f
                        val ux = dx / d
                        val uy = dy / d
                        val uz = dz / d
                        x[i] -= ux * push
                        y[i] -= uy * push
                        z[i] -= uz * push
                        x[j] += ux * push
                        y[j] += uy * push
                        z[j] += uz * push
                    }
                }
            }
            pass++
            if (!touched) break
        }
        return countViolations(n, x, y, z, rho)
    }

    // ==================== 浸泡段（§6.3-6 长期不坍缩；判废单测同口径） ====================

    private fun soakRun(
        n: Int, x: FloatArray, y: FloatArray, z: FloatArray,
        vx: FloatArray, vy: FloatArray, vz: FloatArray,
        mass: FloatArray, rho: FloatArray, lambda: FloatArray, rPrefEff: FloatArray,
        linkA: IntArray, linkB: IntArray, linkS: FloatArray, linkL: FloatArray, linkK: FloatArray,
        mTot: Float, p: TasteEmergentForces.Params,
        probes: ArrayList<Probe>,
    ): SoakReport {
        val F = TasteEmergentForces
        val fx = FloatArray(n)
        val fy = FloatArray(n)
        val fz = FloatArray(n)
        val c0 = centroid(n, x, y, z)
        val r90Series = ArrayList<Float>(p.soakSteps / 100 + 2)
        val nnFirst = FloatArray(n)
        val nnLast = FloatArray(n)
        var nnPhase = 0 // 0=前窗未采 1=已采
        val soakTemp = 0.1f
        var step = 0
        while (step < p.soakSteps) {
            TasteNBody.accelBarnesHutRepel(n, mass, x, y, z, p.kR, F.EPS_REPEL, F.R_CUTOFF, fx, fy, fz)
            for (i in 0 until n) {
                val r = sqrt(x[i] * x[i] + y[i] * y[i] + z[i] * z[i])
                if (r > 1e-4f) {
                    var aRad = -lambda[i] * F.baseGravityAccel(r, mTot, p.gLay) + F.darkEnergyAccel(r, p.kDE)
                    // 径向锚弹簧在浸泡段同权（第五轮实锤：漏挂=重力单极慢坍缩；
                    // 第七轮：锚目标=haloShift 统一端态，死星锚墓地轨道不再慢沉）
                    val rp = rPrefEff[i]
                    if (!rp.isNaN()) aRad += -p.kHome * p.kG * (r - rp) * lambda[i]
                    fx[i] += aRad * x[i] / r
                    fy[i] += aRad * y[i] / r
                    fz[i] += aRad * z[i] / r
                }
            }
            for (e in linkA.indices) {
                val a = linkA[e]
                val b = linkB[e]
                val dx = x[b] - x[a]
                val dy = y[b] - y[a]
                val dz = z[b] - z[a]
                val d = sqrt(dx * dx + dy * dy + dz * dz)
                if (d < 1e-4f) continue
                val f = linkK[e] * linkS[e] * (d - linkL[e]) / d
                fx[a] += f * dx
                fy[a] += f * dy
                fz[a] += f * dz
                fx[b] -= f * dx
                fy[b] -= f * dy
                fz[b] -= f * dz
            }
            applyHardCore(n, x, y, z, rho, p.kHard, fx, fy, fz)
            val cap = F.DISP_CAP * soakTemp
            for (i in 0 until n) {
                vx[i] = (vx[i] + fx[i] * soakTemp) * p.damping
                vy[i] = (vy[i] + fy[i] * soakTemp) * p.damping
                vz[i] = (vz[i] + fz[i] * soakTemp) * p.damping
                var dx = vx[i] * soakTemp
                var dy = vy[i] * soakTemp
                var dz = vz[i] * soakTemp
                val dl = sqrt(dx * dx + dy * dy + dz * dz)
                if (dl > cap && dl > 1e-6f) {
                    val k = cap / dl
                    dx *= k
                    dy *= k
                    dz *= k
                }
                x[i] += dx
                y[i] += dy
                z[i] += dz
            }
            step++
            if (step % 100 == 0) {
                r90Series += radiusQuantile(n, x, y, z, 0.9f)
                probes += makeProbe(
                    -step, soakTemp, 0f, 0f,
                    edgeStress(n, x, y, z, linkA, linkB, linkS, linkL),
                    countViolations(n, x, y, z, rho), n, x, y, z,
                )
                // 近邻距采样：首 1/3 窗与末 1/3 窗（KS 两样本）
                if (nnPhase == 0 && step >= p.soakSteps / 3) {
                    nearestNeighborDist(n, x, y, z, nnFirst)
                    nnPhase = 1
                }
            }
        }
        nearestNeighborDist(n, x, y, z, nnLast)
        val c1 = centroid(n, x, y, z)
        val r90Start = r90Series.firstOrNull() ?: 0f
        val r90End = r90Series.lastOrNull() ?: r90Start
        var shrinkSeg = 0
        for (i in 1 until r90Series.size) if (r90Series[i] < r90Series[i - 1]) shrinkSeg++
        val shrinkFrac = if (r90Series.size > 1) shrinkSeg.toFloat() / (r90Series.size - 1) else 0f
        val ksD = ksStatistic(nnFirst, nnLast)
        val ksCrit = 1.36f * sqrt((n + n).toFloat() / (n * n).toFloat())
        return SoakReport(
            p.soakSteps, r90Start, r90End,
            if (r90Start > 1e-6f) (r90End - r90Start) / r90Start else 0f,
            sqrt((c1[0] - c0[0]) * (c1[0] - c0[0]) + (c1[1] - c0[1]) * (c1[1] - c0[1]) + (c1[2] - c0[2]) * (c1[2] - c0[2])),
            shrinkFrac, ksD, ksCrit,
        )
    }

    // ==================== 体检量（全部纯函数，复用 LayoutHealth 现役闸件） ====================

    private fun centroid(n: Int, x: FloatArray, y: FloatArray, z: FloatArray): FloatArray {
        var cx = 0f
        var cy = 0f
        var cz = 0f
        for (i in 0 until n) {
            cx += x[i]
            cy += y[i]
            cz += z[i]
        }
        return floatArrayOf(cx / n, cy / n, cz / n)
    }

    /** 云幅半径分位（|r| 排序取 q 分位——R90=云幅口径） */
    fun radiusQuantile(n: Int, x: FloatArray, y: FloatArray, z: FloatArray, q: Float): Float {
        val rs = FloatArray(n) { sqrt(x[it] * x[it] + y[it] * y[it] + z[it] * z[it]) }
        rs.sort()
        return rs[((n - 1) * q).toInt().coerceIn(0, n - 1)]
    }

    /** 每星最近邻距（KS 检验原料；O(N²) 体检口径——百步窗一次，非帧路径） */
    fun nearestNeighborDist(n: Int, x: FloatArray, y: FloatArray, z: FloatArray, out: FloatArray) {
        for (i in 0 until n) {
            var best = Float.MAX_VALUE
            for (j in 0 until n) {
                if (j == i) continue
                val dx = x[j] - x[i]
                val dy = y[j] - y[i]
                val dz = z[j] - z[i]
                val d2 = dx * dx + dy * dy + dz * dz
                if (d2 < best) best = d2
            }
            out[i] = if (best == Float.MAX_VALUE) 0f else sqrt(best)
        }
    }

    /** 两样本 KS 统计量（排序 CDF 最大差） */
    fun ksStatistic(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val sa = a.sorted()
        val sb = b.sorted()
        var i = 0
        var j = 0
        var d = 0f
        while (i < sa.size && j < sb.size) {
            if (sa[i] <= sb[j]) i++ else j++
            val cdf = abs(i.toFloat() / sa.size - j.toFloat() / sb.size)
            if (cdf > d) d = cdf
        }
        return d
    }

    /**
     * 边长应力（§3.7-2）：S=√(Σw(d−L)²/Σw·L²)。
     * @param lambda 非空时 w=s·√(λa·λb)（弱核力耦合口径——死星弹簧名存实亡，其形变
     *   不计入应力=与动力学生效的 s_eff 同源一致；null=裸 s 权）
     */
    fun edgeStress(
        n: Int, x: FloatArray, y: FloatArray, z: FloatArray, links: List<Link>,
        lambda: FloatArray? = null,
    ): Float =
        edgeStress(
            n, x, y, z,
            IntArray(links.size) { links[it].a },
            IntArray(links.size) { links[it].b },
            if (lambda == null) FloatArray(links.size) { links[it].s }
            else FloatArray(links.size) { links[it].s * sqrt(lambda[links[it].a] * lambda[links[it].b]) },
            FloatArray(links.size) { links[it].L },
        )

    private fun edgeStress(
        n: Int, x: FloatArray, y: FloatArray, z: FloatArray,
        la: IntArray, lb: IntArray, ls: FloatArray, ll: FloatArray,
    ): Float {
        if (la.isEmpty()) return 0f
        var num = 0.0
        var den = 0.0
        for (e in la.indices) {
            val a = la[e]
            val b = lb[e]
            val dx = x[b] - x[a]
            val dy = y[b] - y[a]
            val dz = z[b] - z[a]
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            val w = if (ls[e] > 0f) ls[e] else 1f
            val dev = (d - ll[e]) / ll[e]
            num += w.toDouble() * dev * dev
            den += w.toDouble()
        }
        return if (den <= 0.0) 0f else sqrt(num / den).toFloat()
    }

    /**
     * 邻域保持率（§3.7-1）：每星语义 top-k（links 内 s 降序）落在空间 top-k 近邻内
     * 的比例均值（k=[TasteEmergentForces.TOPK_NEIGHBOR]；无语义邻居的星不参与均值）。
     */
    fun neighborPreservation(n: Int, x: FloatArray, y: FloatArray, z: FloatArray, links: List<Link>): Float {
        val k = TasteEmergentForces.TOPK_NEIGHBOR
        val sem = Array(n) { ArrayList<Pair<Int, Float>>(k) }
        for (l in links) {
            sem[l.a] += l.b to l.s
            sem[l.b] += l.a to l.s
        }
        var sum = 0f
        var cnt = 0
        for (i in 0 until n) {
            if (sem[i].isEmpty()) continue
            cnt++
            val topSem = sem[i].sortedWith(compareByDescending<Pair<Int, Float>> { it.second }.thenBy { it.first })
                .take(k).map { it.first }.toHashSet()
            // 空间 top-k（O(N) 选）
            val d2 = FloatArray(n)
            for (j in 0 until n) {
                val dx = x[j] - x[i]
                val dy = y[j] - y[i]
                val dz = z[j] - z[i]
                d2[j] = if (j == i) Float.MAX_VALUE else dx * dx + dy * dy + dz * dz
            }
            val topSpa = d2.indices.sortedBy { d2[it] }.take(k).toHashSet()
            var hit = 0
            for (j in topSem) if (j in topSpa) hit++
            sum += hit.toFloat() / minOf(k, topSem.size)
        }
        return if (cnt == 0) 0f else sum / cnt
    }

    /**
     * 簇内聚度（§3.7-3）：簇内均距/簇间均距 + 置换检验（[permutations] 轮，LCG 确定性
     * 种子——同数据同结论）。返回 (比值, p 值)。标签=调用方给的口味簇号（快照缺 MDS
     * 簇源时可用主 tag 代理并诚实标注）。
     */
    fun clusterCohesion(
        n: Int, x: FloatArray, y: FloatArray, z: FloatArray, labels: IntArray,
        permutations: Int = 100, seed: Long = 0x303,
    ): Pair<Float, Float> {
        fun ratio(lab: IntArray): Float {
            var inSum = 0.0
            var inCnt = 0L
            var outSum = 0.0
            var outCnt = 0L
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    val dx = x[j] - x[i]
                    val dy = y[j] - y[i]
                    val dz = z[j] - z[i]
                    val d = sqrt(dx * dx + dy * dy + dz * dz).toDouble()
                    if (lab[i] == lab[j] && lab[i] >= 0) {
                        inSum += d
                        inCnt++
                    } else {
                        outSum += d
                        outCnt++
                    }
                }
            }
            if (inCnt == 0L || outCnt == 0L) return 1f
            return ((inSum / inCnt) / (outSum / outCnt)).toFloat()
        }
        val actual = ratio(labels)
        var s = seed
        fun rnd(): Int {
            s = s * 6364136223846793005L + 1442695040888963407L
            return ((s ushr 33) and 0x7FFFFFFFL).toInt()
        }
        var beats = 0
        val perm = labels.copyOf()
        repeat(permutations) {
            // Fisher-Yates（确定性 LCG）
            for (i in n - 1 downTo 1) {
                val j = rnd() % (i + 1)
                val t = perm[i]
                perm[i] = perm[j]
                perm[j] = t
            }
            if (ratio(perm) <= actual) beats++
        }
        return actual to (beats + 1).toFloat() / (permutations + 1)
    }

    /**
     * 屏上重合率（§3.9-3 用户实锤病的量化终判）：标准机位投影仿真（复用
     * TasteSpace.project 纯函数），「两星屏距 <[thresholdPx]px」对数占可见对数比。
     * 只统计双星均投在屏内（深度过近裁+屏框内）的对——分母=可见对数。
     */
    fun screenOverlapRatio(
        n: Int, x: FloatArray, y: FloatArray, z: FloatArray,
        cam: TasteSpace.SpaceCam, screenW: Float, screenH: Float,
        thresholdPx: Float = 8f,
    ): Float {
        val focal = TasteSpace.focalOf(screenW, screenH)
        val basis = TasteSpace.basis(cam.yawDeg, cam.pitchDeg, FloatArray(9))
        val sx = FloatArray(n)
        val sy = FloatArray(n)
        val vis = BooleanArray(n)
        val pt = FloatArray(4)
        for (i in 0 until n) {
            vis[i] = TasteSpace.project(x[i], y[i], z[i], cam, focal, screenW / 2f, screenH / 2f, pt, basis)
            if (vis[i]) {
                sx[i] = pt[0]
                sy[i] = pt[1]
                if (sx[i] < 0f || sx[i] > screenW || sy[i] < 0f || sy[i] > screenH) vis[i] = false
            }
        }
        var close = 0L
        var total = 0L
        val thr2 = thresholdPx * thresholdPx
        for (i in 0 until n) {
            if (!vis[i]) continue
            for (j in i + 1 until n) {
                if (!vis[j]) continue
                total++
                val dx = sx[j] - sx[i]
                val dy = sy[j] - sy[i]
                if (dx * dx + dy * dy < thr2) close++
            }
        }
        return if (total == 0L) 0f else close.toFloat() / total
    }

    /** R303 标准机位组（§3.9-3：初见位/银河位/中壳回望位——三机位取最差） */
    fun standardCams(): List<Pair<String, TasteSpace.SpaceCam>> {
        // 银河位：退至银心距 7000、俯角 25° 看全银河（§7.2 银河视角口径）——
        // R303c 起委托 TasteScaleSigma.galaxyViewPose（同一距离语义单口）
        return listOf(
            "初见位" to TasteSpace.SpaceCam.ORIGIN,
            "银河位" to TasteScaleSigma.galaxyViewPose(),
            "中壳回望位" to TasteSpace.SpaceCam(0f, 0f, 1500f, 180f, 0f),
        )
    }

    // ==================== 内部小件 ====================

    private fun makeProbe(
        round: Int, temp: Float, maxF: Float, maxD: Float, stress: Float, viol: Int,
        n: Int, x: FloatArray, y: FloatArray, z: FloatArray,
    ): Probe {
        val c = centroid(n, x, y, z)
        return Probe(round, temp, maxF, maxD, stress, viol, radiusQuantile(n, x, y, z, 0.9f), c[0], c[1], c[2])
    }

    /** LayoutHealth 闸对接（§6.3-5：布局场退化有旧闸接手）——θ=atan2(z,x)、格=x/z 两轴 */
    private fun layoutDegenerateNow(n: Int, x: FloatArray, y: FloatArray, z: FloatArray): Boolean {
        val thetas = FloatArray(n) { atan2(z[it], x[it]) }
        val tu = LayoutHealth.thetaUniqueCount(thetas, n)
        val cu = LayoutHealth.coordUniqueCount(x, z, n)
        return LayoutHealth.layoutDegenerate(tu, cu)
    }
}
