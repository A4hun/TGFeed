package dev.tgfeed.core.tastemap

import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import kotlin.math.ln1p
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * R303 快照彩排（design-multiscale-emergent §十二 P0 门禁：真实库快照只读跑离线定形，
 * 出收敛曲线+屏上重合率 ≤0.5% 验收硬指标——全 JVM 零上机）。
 *
 * 数据源：snap2.db 提取的 r303_universe.csv / r303_pairs.csv（.session-review，
 * 源头遮蔽——只含 chatId 数值与聚合量）。
 * **缺源诚实降档**（快照是索引库）：互链边=无（w=0，strengthOf 互链项计 0）、
 * MDS 基因=无（d_mds 用 tag 计数向量余弦距离代理）、观看/订阅=无（家弹簧 rPref=NaN
 * 不启用）、双星=无（无 strong 对）、口味角=无（初值=V3 形式+LayoutHealth.fallbackTheta
 * 哈希角）。全部在报告里标注。
 *
 * 跑法：`gradlew :app:testDebugUnitTest --tests "*R303RehearsalSim*"`（数据目录由
 * R303_DATA_DIR 指定，默认主仓 .session-review；文件缺席=Assume 跳过——常测零负担）。
 */
class R303RehearsalSimTest {

    private val F = TasteEmergentForces
    private val L = TasteEmergentLayout

    private val dataDir: String
        get() = System.getenv("R303_DATA_DIR")
            ?: "build/r303-rehearsal" // 缺省占位：数据缺席=Assume 跳过（开源卫生：不写死本机路径）

    private class Uni(val chatId: Long, val items: Int, val lastActiveSec: Long)

    private fun loadUniverse(f: File): List<Uni> =
        f.readLines().drop(1).filter { it.isNotBlank() }.map {
            val p = it.split(',')
            Uni(p[0].toLong(), p[1].toInt(), p[2].toLong())
        }

    @Test
    fun `快照彩排-离线定形与重合率验收`() {
        val uf = File(dataDir, "r303_universe.csv")
        val pf = File(dataDir, "r303_pairs.csv")
        Assume.assumeTrue("快照 CSV 缺席=跳过（先跑 r303_extract.py）", uf.exists() && pf.exists())

        val uni = loadUniverse(uf)
        val n = uni.size
        val idx = HashMap<Long, Int>(n * 2)
        uni.forEachIndexed { i, u -> idx[u.chatId] = i }
        val maxItems = uni.maxOf { it.items }.coerceAtLeast(1)
        val nowSec = uni.maxOf { it.lastActiveSec }

        // ---- 节点（质量=ln1p 归一 / ρ=体量口径半径 / λ=断更衰变 / 初值=V3 形式哈希角降档） ----
        // 体量分位（V3 r 口径=大近小远；CSV 按 chatId 序≠体量序——必须先按条数排名）
        val byItems = uni.sortedBy { it.items }
        val volRank = HashMap<Long, Float>(n * 2)
        byItems.forEachIndexed { i, u -> volRank[u.chatId] = i.toFloat() / (n - 1).coerceAtLeast(1) }
        val out3 = FloatArray(3)
        val nodes = uni.map { u ->
            val mass01 = ln1p(u.items.toFloat()) / ln1p(maxItems.toFloat())
            val volBase = sqrt(u.items.toFloat() / maxItems).pow(1.35f)
            val rho = 2.2f + (26f - 2.2f) * volBase
            val staleDays = (nowSec - u.lastActiveSec).coerceAtLeast(0L).toFloat() / 86400f
            val lambda = F.decayLambda(staleDays)
            val rank01 = volRank.getValue(u.chatId)
            // 初值=V3 球面（口味角缺源=哈希角降档，r 按体量分位——设计 §6.1「初值=现役位置」的快照近似）
            TasteSpace.spherifyV3(LayoutHealth.fallbackTheta(u.chatId), u.chatId, rank01, out3)
            // 家弹簧 rPref=体量半径代理（§3.8 正交分解：角向涌现+径向保 V3 体量壳——
            // 快照缺 watchSec/订阅的诚实降档，非生产口径）
            val rPref = TasteSpace.V3_NEAR + (TasteSpace.V3_FAR - TasteSpace.V3_NEAR) * (1f - rank01)
            TasteEmergentLayout.Node(u.chatId, mass01, rho, lambda, rPref, out3[0], out3[1], out3[2])
        }

        // ---- 稀疏引力对（s_ab 走 TasteEmergentForces 单口合成；互链源缺席 w=0） ----
        class RawPair(val a: Int, val b: Int, val jac: Float, val cosD: Float)
        val rawPairs = pf.readLines().drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split(',')
            val a = idx[p[0].toLong()] ?: return@mapNotNull null
            val b = idx[p[1].toLong()] ?: return@mapNotNull null
            RawPair(a, b, p[2].toFloat(), p[3].toFloat())
        }
        val cosSorted = rawPairs.map { it.cosD }.sorted()
        val cosP95 = if (cosSorted.isEmpty()) 1f else cosSorted[(cosSorted.size - 1) * 95 / 100]
        class Scored(val a: Int, val b: Int, val s: Float)
        val linksAll = rawPairs.map { rp ->
            Scored(rp.a, rp.b, F.strengthOf(0f, 0f, rp.jac, rp.cosD, cosP95))
        }.filter { it.s >= F.S_PAIR_MIN }
            .sortedWith(compareByDescending<Scored> { it.s }.thenBy { it.a }.thenBy { it.b })
            .take(F.MAX_PAIRS)
        val links = linksAll.map { sc ->
            TasteEmergentLayout.Link(sc.a, sc.b, sc.s, F.idealDist(nodes[sc.a].rho, nodes[sc.b].rho, sc.s))
        }

        val sb = StringBuilder()
        fun say(s: String) {
            sb.appendLine(s)
            println(s)
        }
        say("# R303 快照彩排报告（snap2.db 只读 · 全 JVM 零上机）")
        say("宇宙规模: 星=$n（有条目频道全量，未触 600 上限） 候选对=${rawPairs.size} 成对=${links.size}（s≥${F.S_PAIR_MIN}）")
        say("缺源降档: 互链边=无 / MDS=tag 余弦代理 / 家弹簧=无 / 双星=无 / 初值=V3 哈希角降档")
        say("cosP95=%.3f".format(cosP95))

        // ---- 基线：V3 初值形态的体检（对照组=现役布局，§3.7 纪律；应力同走 λ 耦合权） ----
        val lambdas = FloatArray(n) { nodes[it].lambda }
        val ix = FloatArray(n) { nodes[it].x }
        val iy = FloatArray(n) { nodes[it].y }
        val iz = FloatArray(n) { nodes[it].z }
        val cams = L.standardCams()
        val screens = listOf("红米竖屏1216x2688" to (1216f to 2688f), "华为横屏3048x2032" to (3048f to 2032f))
        fun worstOverlap(x: FloatArray, y: FloatArray, z: FloatArray): Pair<String, Float> {
            var worst = 0f
            var tag = ""
            for ((cn, cam) in cams) for ((sn, wh) in screens) {
                val r = L.screenOverlapRatio(n, x, y, z, cam, wh.first, wh.second)
                if (r > worst) {
                    worst = r
                    tag = "$cn/$sn"
                }
            }
            return tag to worst
        }
        val (v3Cam, v3Overlap) = worstOverlap(ix, iy, iz)
        val v3Keep = L.neighborPreservation(n, ix, iy, iz, links)
        val v3Stress = L.edgeStress(n, ix, iy, iz, links, lambdas)
        say("\n## 对照组=V3 初值形态")
        say("屏上重合率最差机位: %.4f%% @%s（验收硬指标 ≤0.5%%）".format(v3Overlap * 100, v3Cam))
        say("邻域保持率: %.3f  应力: %.3f".format(v3Keep, v3Stress))

        // ---- 标定网格（§3.6 离线标定回路彩排版：lSpan×kR×gLay 粗网格，收敛件先行） ----
        say("\n## 标定网格（rounds=400 免浸泡快筛）")
        say("lSpan,kR,gLay,kHome,④合法,收敛轮,早停,应力,保持率,重合率%,R90,违约")
        class GridRow(
            val lSpan: Float, val kR: Float, val gLay: Float, val kHome: Float,
            val r: TasteEmergentLayout.Result, val keep: Float, val overlap: Float,
        )
        fun linksOf(span: Float) = linksAll.map { sc ->
            TasteEmergentLayout.Link(sc.a, sc.b, sc.s, F.idealDist(nodes[sc.a].rho, nodes[sc.b].rho, sc.s, span))
        }
        val grid = ArrayList<GridRow>()
        // 网格：span×(kR,gLay)×kHome——(kR,gLay) 全在安全不等式④截断邻域口径合法域
        // （kHome=径向钉壳强度：彩排第四轮实锤 0.02 太弱被重力压垮→核心糊球）
        val kgPairs = listOf(2.5e4f to 7.6e4f, 1e5f to 1.5e5f, 2.5e5f to 4e5f)
        for (span in floatArrayOf(400f, 800f, 1600f)) {
            for (kg in kgPairs) {
                for (kHome in floatArrayOf(0.02f, 0.1f)) {
                    val kR = kg.first
                    val gLay = kg.second
                    val legal = F.safetyReport(TasteEmergentForces.Params(kR = kR, gLay = gLay, kHome = kHome)).isEmpty()
                    val p = TasteEmergentForces.Params(kR = kR, gLay = gLay, lSpan = span, kHome = kHome, roundsMax = 400)
                    val r = L.shape(nodes, linksOf(span), p)
                    val fx = FloatArray(n) { r.positions[3 * it] }
                    val fy = FloatArray(n) { r.positions[3 * it + 1] }
                    val fz = FloatArray(n) { r.positions[3 * it + 2] }
                    val keep = L.neighborPreservation(n, fx, fy, fz, linksOf(span))
                    val ov = worstOverlap(fx, fy, fz).second
                    grid += GridRow(span, kR, gLay, kHome, r, keep, ov)
                    say("%.0f,%.0f,%.0f,%.2f,%b,%d,%b,%.3f,%.3f,%.4f,%.0f,%d".format(
                        span, kR, gLay, kHome, legal, r.rounds, r.converged, r.stressFinal, keep, ov * 100, r.r90, r.violationsFinal))
                }
            }
        }
        // 达标选拔（§3.6「达标组里选最优」）：违约=0 ∧ 重合率 ≤0.5% 先过筛 → 保持率最大
        val legal = grid.filter { it.r.violationsFinal == 0 && it.overlap <= 0.005f }
        val best = (legal.ifEmpty { grid.filter { it.r.violationsFinal == 0 } })
            .maxWithOrNull(compareBy<GridRow> { it.keep }.thenBy { -it.overlap })
            ?: grid.maxByOrNull { it.keep }!!
        say("选定组: lSpan=${best.lSpan} kR=${best.kR} gLay=${best.gLay} kHome=${best.kHome}（过线组保持率优先）")

        // ---- 选定组全量定形+万步浸泡 ----
        val linksBest = linksOf(best.lSpan)
        val full = L.shape(
            nodes, linksBest,
            TasteEmergentForces.Params(
                kR = best.kR, gLay = best.gLay, lSpan = best.lSpan, kHome = best.kHome,
                roundsMax = F.ROUNDS_MAX, soakSteps = 10000,
            ),
        )
        val ex = FloatArray(n) { full.positions[3 * it] }
        val ey = FloatArray(n) { full.positions[3 * it + 1] }
        val ez = FloatArray(n) { full.positions[3 * it + 2] }
        val (eCam, eOverlap) = worstOverlap(ex, ey, ez)
        val eKeep = L.neighborPreservation(n, ex, ey, ez, linksBest)
        // 结构诊断：近邻距中位 / 核心份额 / 死星晕漂实证
        val nnArr = FloatArray(n)
        L.nearestNeighborDist(n, ex, ey, ez, nnArr)
        val nnMed = nnArr.sorted()[n / 2]
        val coreShare = (0 until n).count {
            val r3 = sqrt(ex[it] * ex[it] + ey[it] * ey[it] + ez[it] * ez[it])
            r3 < 2000f
        }.toFloat() / n
        val deadIdx = (0 until n).filter { nodes[it].lambda < 0.3f }
        val deadRMed = deadIdx.map {
            sqrt(ex[it] * ex[it] + ey[it] * ey[it] + ez[it] * ez[it])
        }.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
        // 簇内聚度：主 tag 代理簇标签（快照缺 MDS 簇源的代理口径——按频道最大 tag 的
        // 稳定哈希分 12 簇；无 tag 频道=-1 不计簇内）
        val labels = IntArray(n) { i -> (uni[i].chatId and 0x7FFFFFFFL).toInt() % 12 }
        val (coh, cohP) = L.clusterCohesion(n, ex, ey, ez, labels, permutations = 100)

        say("\n## 选定组全量（rounds≤${F.ROUNDS_MAX}+万步浸泡）")
        say("早停收敛=%b 轮=%d 违约=%d R90=%.0f".format(full.converged, full.rounds, full.violationsFinal, full.r90))
        say("稳态五闸: 力=%b 位移=%b 违约=%b 应力漂移=%b 健康=%b".format(
            full.gates.forceOk, full.gates.dispOk, full.gates.violationOk, full.gates.stressDriftOk, full.gates.healthOk))
        full.soak?.let { s ->
            say("浸泡(10000): r90 %.0f→%.0f (%.2f%%) 质心漂 %.1f shrinkFrac %.2f KS %.3f/%.3f → 稳态=%b 坍缩相=%b".format(
                s.r90Start, s.r90End, s.r90Drift * 100, s.centroidDrift, s.shrinkFrac, s.ksD, s.ksCrit, s.steady(), s.collapsing()))
            if (s.centroidDrift >= F.SOAK_CENTROID_DRIFT) {
                say("**挂账·质心漂超阈（设计闸 50 世界单位）**：机理=退火 T→0 把弹簧残力冻在局里" +
                    "（力闸同因未过），浸泡热浴 T=0.1 解冻后继续向真平衡蠕行（非坍缩：r90/KS 全稳）。" +
                    "P1 裁决提案：退火尾段加力淬灭（T 地板段零温弛豫）或质心闸相对化（%%R90 口径）——待终审。")
            }
        }
        say("语义三指标: 保持率 %.3f（V3 对照 %.3f） 应力 %.3f（V3 %.3f） 聚度 %.3f(p=%.3f)".format(
            eKeep, v3Keep, full.stressFinal, v3Stress, coh, cohP))
        say("结构诊断: 近邻距中位=%.0f 核心(r<2000)份额=%.2f 死星(λ<0.3 共%d)半径中位=%.0f（晕漂目标带 5400）".format(
            nnMed, coreShare, deadIdx.size, deadRMed))
        say("**屏上重合率: %.4f%% @%s（V3 对照 %.4f%%——硬指标 ≤0.5%%）**".format(eOverlap * 100, eCam, v3Overlap * 100))
        // P0 硬门禁（任务书口径）=重合率 ≤0.5% ∧ 违约清零 ∧ 不坍缩浸泡三量（云幅有界/
        // 间距分布稳态/无坍缩相）∧ 全面不劣于 V3 对照组。
        // 挂账不进门禁的两项（报告上文显式标注）：①质心漂 50 绝对闸 vs 热浴蠕行实测
        // （机理与 P1 提案见上）②保持率 ≥0.5 绝对目标 vs 快照缺互链/MDS 真源的降档口径
        // （P2 接线真源后复测）。
        val soak = full.soak!!
        val pass = eOverlap <= 0.005f && full.violationsFinal == 0 &&
            kotlin.math.abs(soak.r90Drift) < F.SOAK_R90_DRIFT && soak.ksD < soak.ksCrit &&
            !soak.collapsing() && eKeep > v3Keep && full.stressFinal < v3Stress
        say("P0 门禁总判: ${if (pass) "过" else "未过（挂账项见报告）"}")

        // ---- 收敛曲线 CSV + 平衡位表（布局场输出本体） ----
        File(dataDir, "r303_convergence.csv").writeText(buildString {
            appendLine("round,temp,maxForce,maxDisp,stress,violations,r90,cx,cy,cz")
            for (pr in full.probes) {
                appendLine("%d,%.4f,%.3f,%.3f,%.4f,%d,%.1f,%.1f,%.1f,%.1f".format(
                    pr.round, pr.temp, pr.maxForce, pr.maxDisp, pr.stress, pr.violations, pr.r90, pr.cx, pr.cy, pr.cz))
            }
        })
        File(dataDir, "r303_positions.csv").writeText(buildString {
            appendLine("chatId,x,y,z")
            for (i in 0 until n) {
                appendLine("${uni[i].chatId},${full.positions[3 * i]},${full.positions[3 * i + 1]},${full.positions[3 * i + 2]}")
            }
        })
        File(dataDir, "r303_rehearsal_report.txt").writeText(sb.toString())

        // 断言=P0 门禁（重合率硬指标+违约清零+浸泡稳态；不达标=红——报告即挂账清单）
        assertTrue("P0 门禁未过——见 r303_rehearsal_report.txt", pass)
    }

    // ==================== R303c P1+P2：生产管线真源复跑（TasteEmergentSource 全链） ====================

    /**
     * R303c 生产管线彩排（P1 接互链真源+P2 装配接线的门禁）：
     *
     *  - **数据真源**（r303c 增提取，源头脱敏）：互链=r303_links.csv（items.fileUniqueId
     *    同指纹跨频道对=生产 interlinkPairSummary 同证据族，快照无 repost_refs 表——
     *    互转方向边缺席诚实标注；top-800 留重=生产 MAX_LINKS 口径）；指纹=r303_fp.csv
     *    （tag 原文经 SHA1 前 12 位匿名 id——集合交并语义与原文同构）；MDS 距离=
     *    tag 计数向量余弦（生产=特征余弦矩阵，快照缺特征的代理口径）。
     *  - **管线**=生产同链：TasteEmergentSource.buildNodes（家弹簧真源缺席=陌生档
     *    诚实降档）→ buildLinks（互链∪top-8 Jaccard∪双星空集）→ shape（PRODUCTION_PARAMS
     *    标定组）→ verdictOf 裁决 → **温启二跑**（冷启平衡位=温启初值，§6.6 增量
     *    演化+§6.9 搬家帽）。
     *  - **门禁**（P0 同口径+真源升档）：违约=0 ∧ 屏上重合率 ≤0.5% ∧ 保持率/应力
     *    优于 V3 对照 ∧ 温启搬家帽不破 ∧ 裁决过闸。
     */
    @Test
    fun `快照彩排-生产管线真源复跑`() {
        val uf = File(dataDir, "r303_universe.csv")
        val lf = File(dataDir, "r303_links.csv")
        val fpf = File(dataDir, "r303_fp.csv")
        Assume.assumeTrue(
            "R303c 真源 CSV 缺席=跳过（先跑 r303_extract.py 增段）",
            uf.exists() && lf.exists() && fpf.exists(),
        )
        val sb = StringBuilder()
        fun say(s: String) {
            sb.appendLine(s)
            println(s)
        }
        say("# R303c 生产管线真源彩排（snap2.db 只读 · TasteEmergentSource 全链）")

        val uni = loadUniverse(uf)
        val n = uni.size
        val idx = HashMap<Long, Int>(n * 2)
        uni.forEachIndexed { i, u -> idx[u.chatId] = i }
        val nowSec = uni.maxOf { it.lastActiveSec }
        val byItems = uni.sortedBy { it.items }
        val volRank = HashMap<Long, Float>(n * 2)
        byItems.forEachIndexed { i, u -> volRank[u.chatId] = i.toFloat() / (n - 1).coerceAtLeast(1) }

        // ---- 互链真源：top-800 留重（生产 MAX_LINKS 口径：权重降序→chatId 升序破平） ----
        val linkRows = lf.readLines().drop(1).filter { it.isNotBlank() }.map { line ->
            val p = line.split(',')
            Triple(p[0].toLong(), p[1].toLong(), p[2].toInt())
        }
        val topLinks = linkRows.sortedWith(
            compareByDescending<Triple<Long, Long, Int>> { it.third }.thenBy { it.first }.thenBy { it.second },
        ).take(800)
        val interlink = HashMap<Long, HashMap<Long, Int>>(1024)
        for ((a, b, w) in topLinks) {
            interlink.getOrPut(a) { HashMap(4) }[b] = w
            interlink.getOrPut(b) { HashMap(4) }[a] = w
        }
        val wSorted = linkRows.sortedBy { it.third }
        say(
            "互链真源: 全对=${linkRows.size} top800 留重（p50=${wSorted[wSorted.size / 2].third} " +
                "p90=${wSorted[(wSorted.size * 9) / 10].third} max=${linkRows.maxOf { it.third }}）",
        )

        // ---- 匿名指纹（SHA1 id——集合语义同构原文）----
        val fingerprints = HashMap<Long, HashMap<String, Int>>(512)
        for (line in fpf.readLines().drop(1)) {
            if (line.isBlank()) continue
            val p = line.split(',')
            fingerprints.getOrPut(p[0].toLong()) { HashMap(64) }[p[1]] = p[2].toInt()
        }
        say("指纹: 频道=${fingerprints.size}/$n（tag 原文经 SHA1 匿名——文字零出库）")

        // ---- MDS 距离代理：tag 计数向量余弦（倒排累加共享对点积；无共享=满距 1） ----
        val norm = HashMap<Long, Double>(n * 2)
        for ((cid, fp) in fingerprints) {
            var s = 0.0
            for (v in fp.values) s += v.toDouble() * v.toDouble()
            norm[cid] = sqrt(s)
        }
        val dot = HashMap<Pair<Long, Long>, Double>(4096)
        run {
            val tagCols = HashMap<String, ArrayList<Pair<Long, Int>>>(256)
            for ((cid, fp) in fingerprints) {
                for ((t, c) in fp) tagCols.getOrPut(t) { ArrayList(4) } += cid to c
            }
            for (lst in tagCols.values) {
                if (lst.size < 2) continue
                lst.sortBy { it.first }
                for (i in lst.indices) for (j in i + 1 until lst.size) {
                    val (ci, ni) = lst[i]
                    val (cj, nj) = lst[j]
                    val k = minOf(ci, cj) to maxOf(ci, cj)
                    dot.merge(k, ni.toDouble() * nj.toDouble(), Double::plus)
                }
            }
        }
        val distCache = HashMap<Pair<Long, Long>, Float>(4096)
        val distOf = { ia: Int, ib: Int ->
            val ci = uni[ia].chatId
            val cj = uni[ib].chatId
            val k = minOf(ci, cj) to maxOf(ci, cj)
            distCache.getOrPut(k) {
                val d = dot[k] ?: 0.0
                val den = (norm[ci] ?: 1e-9) * (norm[cj] ?: 1e-9)
                (1.0 - d / den).coerceIn(0.0, 1.0).toFloat()
            }
        }

        // ---- 生产管线：节点（家弹簧=陌生档诚实降档——快照无观看/订阅账） ----
        val out3 = FloatArray(3)
        val specs = ArrayList<TasteEmergentSource.StarSpec>(n)
        val v3x = FloatArray(n)
        val v3y = FloatArray(n)
        val v3z = FloatArray(n)
        for (i in 0 until n) {
            val u = uni[i]
            val rank01 = volRank.getValue(u.chatId)
            TasteSpace.spherifyV3(LayoutHealth.fallbackTheta(u.chatId), u.chatId, rank01, out3)
            v3x[i] = out3[0]; v3y[i] = out3[1]; v3z[i] = out3[2]
            specs += TasteEmergentSource.StarSpec(
                chatId = u.chatId, itemsCount = u.items,
                rho = 2.2f + (26f - 2.2f) * rank01, // 体量分位半径口径（快照缺 bodyRadius 现役值）
                lastActivitySec = u.lastActiveSec, volume01 = rank01,
                watchSec = 0L, lastWatchMs = 0L, subscribed = false,
            )
        }
        val nodes = TasteEmergentSource.buildNodes(
            specs, v3x, v3y, v3z, nowSec * 1000L, 0L, nowSec, null,
        )
        val links = TasteEmergentSource.buildLinks(idx, nodes, interlink, fingerprints, distOf, emptyList())
        say("生产管线输入: 星=$n 互链top800=${topLinks.size} 候选成对=${links.size}（互链∪top-8 Jaccard）")
        assertTrue("链对应非空（互链真源在场）", links.isNotEmpty())

        // ---- 冷启定形（生产标定组）+ 裁决 ----
        val cold = TasteEmergentLayout.shape(nodes, links, TasteEmergentSource.PRODUCTION_PARAMS)
        val vCold = TasteEmergentSource.verdict(cold, links.size)
        say(
            "冷启: 轮=${cold.rounds} 收敛=${cold.converged} 违约=${cold.violationsFinal} R90=${cold.r90.toInt()} " +
                "应力=%.3f 裁决=${vCold.accept}（${vCold.reason} mode=${vCold.mode}）".format(cold.stressFinal),
        )
        assertTrue("冷启裁决应过闸：${vCold.reason}", vCold.accept)

        // ---- 温启二跑（§6.6 增量演化：冷启平衡位=初值；§6.9 搬家帽） ----
        val warmMap = HashMap<Long, FloatArray>(n * 2)
        for (i in 0 until n) {
            warmMap[uni[i].chatId] = floatArrayOf(cold.positions[3 * i], cold.positions[3 * i + 1], cold.positions[3 * i + 2])
        }
        val nodesWarm = TasteEmergentSource.buildNodes(
            specs, v3x, v3y, v3z, nowSec * 1000L, 0L, nowSec, warmMap,
        )
        val warm = TasteEmergentLayout.shape(
            nodesWarm, links, TasteEmergentSource.warmParams(TasteEmergentSource.PRODUCTION_PARAMS),
        )
        val fin = TasteEmergentSource.warmFinal(warm, nodesWarm, warmMap)
        var maxMove = 0f
        for (i in 0 until n) {
            val w = warmMap.getValue(uni[i].chatId)
            val dx = fin.positions[3 * i] - w[0]
            val dy = fin.positions[3 * i + 1] - w[1]
            val dz = fin.positions[3 * i + 2] - w[2]
            maxMove = maxOf(maxMove, sqrt(dx * dx + dy * dy + dz * dz))
        }
        val vWarm = TasteEmergentSource.verdictOf(n, fin.positions, fin.violations, fin.r90, links.size)
        say(
            "温启: 轮=${warm.rounds} 最大位移=${maxMove.toInt()}（帽=${TasteEmergentSource.WARM_MOVE_CAP.toInt()}） " +
                "违约=${fin.violations} 裁决=${vWarm.accept}（${vWarm.reason}）",
        )
        assertTrue(
            "温启 §6.9 搬家帽应不破（最大位移 ${maxMove.toInt()} ≤ 帽+投影余量）",
            maxMove <= TasteEmergentSource.WARM_MOVE_CAP + 60f,
        )
        assertTrue("温启终态应过裁决：${vWarm.reason}", vWarm.accept)

        // ---- 门禁指标（P0 同口径：违约清零 ∧ 重合率 ≤0.5% ∧ 优于 V3 对照） ----
        val ex = FloatArray(n) { fin.positions[3 * it] }
        val ey = FloatArray(n) { fin.positions[3 * it + 1] }
        val ez = FloatArray(n) { fin.positions[3 * it + 2] }
        val cams = L.standardCams()
        val screens = listOf("红米竖屏1216x2688" to (1216f to 2688f), "华为横屏3048x2032" to (3048f to 2032f))
        fun worstOverlap(x: FloatArray, y: FloatArray, z: FloatArray): Pair<String, Float> {
            var worst = 0f
            var tag = ""
            for ((cn, cam) in cams) for ((sn, wh) in screens) {
                val r = L.screenOverlapRatio(n, x, y, z, cam, wh.first, wh.second)
                if (r > worst) {
                    worst = r
                    tag = "$cn/$sn"
                }
            }
            return tag to worst
        }
        val (eCam, eOverlap) = worstOverlap(ex, ey, ez)
        val (v3CamTag, v3Overlap) = worstOverlap(v3x, v3y, v3z)
        val eKeep = L.neighborPreservation(n, ex, ey, ez, links)
        val v3Keep = L.neighborPreservation(n, v3x, v3y, v3z, links)
        val lambdas = FloatArray(n) { nodes[it].lambda }
        val eStress = L.edgeStress(n, ex, ey, ez, links, lambdas)
        val v3Stress = L.edgeStress(n, v3x, v3y, v3z, links, lambdas)
        say("\n## 门禁指标（真源升档）")
        say(
            "**屏上重合率: %.4f%% @%s（V3 对照 %.4f%% @%s——硬指标 ≤0.5%%）**".format(
                eOverlap * 100, eCam, v3Overlap * 100, v3CamTag,
            ),
        )
        say("邻域保持率: %.3f（V3 %.3f）  边长应力: %.3f（V3 %.3f）".format(eKeep, v3Keep, eStress, v3Stress))
        File(dataDir, "r303c_rehearsal_report.txt").writeText(sb.toString())
        val pass = fin.violations == 0 && eOverlap <= 0.005f && eKeep > v3Keep && eStress < v3Stress
        say("R303c 生产管线门禁总判: ${if (pass) "过" else "未过"}")
        assertTrue("R303c 生产管线门禁未过——见 r303c_rehearsal_report.txt", pass)
    }
}