package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * R303 涌现布局引擎单测（design-multiscale-emergent §6 终审稿锚定）：
 *  - BH 斥力与直接求和互证（同 TasteNBodyTest 的 BH-vs-direct 纪律）；
 *  - 弹簧平衡点（**弹簧非 1/d²**——显式平衡点是工程落法：两星从远/近双向收敛 L）；
 *  - 硬核违约=0（重合根治的可证伪承诺——位置级投影兜底清零）；
 *  - 合成簇全量定形：语义三指标之邻域保持率/边长应力过闸；
 *  - **万步浸泡不坍缩**（§6.3-6，用户补刀立法）；
 *  - **纯引力判废单测**（§6.4：k_R=0∧k_H=0 配置断言必塌——禁态钉死在测试里）；
 *  - 全确定性（同数据同宇宙=逐位一致）；暗能量旋钮开→云幅扩张；家弹簧径向偏心。
 */
class TasteEmergentLayoutTest {

    private val F = TasteEmergentForces
    private val L = TasteEmergentLayout

    /** 确定性伪随机（LCG——同种子同序列） */
    private class Rnd(seed: Long) {
        private var s = seed
        fun next(): Float {
            s = s * 6364136223846793005L + 1442695040888963407L
            return ((s ushr 33) and 0xFFFFFFL).toFloat() / 16777216f
        }
    }

    private fun dist(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val dz = bz - az
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    // ==================== BH 斥力互证（§3.2 树复用正确性） ====================

    @Test
    fun `BH斥力-与直接求和一致`() {
        val n = 30
        val rnd = Rnd(42)
        val mass = FloatArray(n) { 0.2f + 0.8f * rnd.next() }
        val px = FloatArray(n) { (rnd.next() - 0.5f) * 4000f }
        val py = FloatArray(n) { (rnd.next() - 0.5f) * 4000f }
        val pz = FloatArray(n) { (rnd.next() - 0.5f) * 4000f }
        val bx = FloatArray(n)
        val by = FloatArray(n)
        val bz = FloatArray(n)
        val dx = FloatArray(n)
        val dy = FloatArray(n)
        val dz = FloatArray(n)
        // 全域口径（cutoff 1e9）消树/直两路截断沿近似差
        TasteNBody.accelBarnesHutRepel(n, mass, px, py, pz, 2500f, 120f, 1e9f, bx, by, bz)
        TasteNBody.accelDirectRepel(n, mass, px, py, pz, 2500f, 120f, 1e9f, dx, dy, dz)
        var num = 0f
        var den = 0f
        for (i in 0 until n) {
            val ex = dx[i] - bx[i]
            val ey = dy[i] - by[i]
            val ez = dz[i] - bz[i]
            num += sqrt(ex * ex + ey * ey + ez * ez)
            den += sqrt(dx[i] * dx[i] + dy[i] * dy[i] + dz[i] * dz[i])
        }
        assertTrue("BH-直相对总差 ${num / den} 应 <2%", num / den < 0.02f)
    }

    @Test
    fun `斥力截断-截断距外零力`() {
        val n = 2
        val mass = floatArrayOf(1f, 1f)
        val px = floatArrayOf(0f, 2000f) // d=2000 > R_c=1400
        val py = floatArrayOf(0f, 0f)
        val pz = floatArrayOf(0f, 0f)
        val ox = FloatArray(n)
        val oy = FloatArray(n)
        val oz = FloatArray(n)
        TasteNBody.accelDirectRepel(n, mass, px, py, pz, 2500f, 120f, 1400f, ox, oy, oz)
        assertEquals(0f, ox[0], 0f)
        // 截断距内有力且指向远离（+X 方向的星把 0 号往 −X 推）
        TasteNBody.accelDirectRepel(n, mass, floatArrayOf(0f, 1000f), py, pz, 2500f, 120f, 1400f, ox, oy, oz)
        assertTrue("斥力应指向远离源：ax=${ox[0]} 应 <0", ox[0] < 0f)
    }

    // ==================== 弹簧平衡点（§3.1 显式平衡点=工程落法） ====================

    @Test
    fun `弹簧-双向收敛理想距`() {
        val rho = 10f
        val s = 0.8f
        val L0 = F.idealDist(rho, rho, s)
        for (startD in floatArrayOf(3000f, 200f)) {
            val nodes = listOf(
                TasteEmergentLayout.Node(11L, 0.5f, rho, 1f, Float.NaN, 0f, 0f, 0f),
                TasteEmergentLayout.Node(22L, 0.5f, rho, 1f, Float.NaN, startD, 0f, 0f),
            )
            val links = listOf(TasteEmergentLayout.Link(0, 1, s, L0))
            // 孤立弹簧对：关斥力/基底/晕漂，只留弹簧
            val r = L.shape(
                nodes, links,
                TasteEmergentForces.Params(kR = 0f, gLay = 0f, applyHaloDrift = false, roundsMax = 600),
            )
            val d = dist(r.positions[0], r.positions[1], r.positions[2],
                r.positions[3], r.positions[4], r.positions[5])
            assertTrue("初距 $startD → 平衡 $d 应 ≈L=$L0（±5%）", d > L0 * 0.95f && d < L0 * 1.05f)
        }
    }

    // ==================== 硬核违约=0（§3.2 根治承诺） ====================

    @Test
    fun `硬核-重叠星对必推开`() {
        val nodes = listOf(
            TasteEmergentLayout.Node(1L, 0.5f, 20f, 1f, Float.NaN, 0f, 0f, 0f),
            TasteEmergentLayout.Node(2L, 0.5f, 20f, 1f, Float.NaN, 10f, 0f, 0f), // d=10 ≪ d_min=80
        )
        val r = L.shape(
            nodes, emptyList(),
            TasteEmergentForces.Params(kR = 0f, gLay = 0f, applyHaloDrift = false, roundsMax = 200),
        )
        assertEquals(0, r.violationsFinal)
        val d = dist(r.positions[0], r.positions[1], r.positions[2],
            r.positions[3], r.positions[4], r.positions[5])
        assertTrue("d=$d 应 ≥d_min=80", d >= 80f * 0.999f)
    }

    // ==================== 合成簇全量定形（语义三指标之二） ====================

    /** 四簇合成宇宙（簇内互连 s=0.9 弹簧——连最近的 4 个簇友；初值=簇心+抖动） */
    private fun synthClusterUniverse(perCluster: Int = 30): Pair<List<TasteEmergentLayout.Node>, List<TasteEmergentLayout.Link>> {
        val centers = arrayOf(
            floatArrayOf(3500f, 200f, 3500f), floatArrayOf(-3500f, -300f, 3500f),
            floatArrayOf(3500f, 100f, -3500f), floatArrayOf(-3500f, -100f, -3500f),
        )
        val rnd = Rnd(777)
        val nodes = ArrayList<TasteEmergentLayout.Node>()
        for (c in centers.indices) {
            for (k in 0 until perCluster) {
                nodes += TasteEmergentLayout.Node(
                    (c * 1000 + k + 1).toLong(), 0.5f, 10f, 1f, Float.NaN,
                    centers[c][0] + (rnd.next() - 0.5f) * 1200f,
                    centers[c][1] + (rnd.next() - 0.5f) * 1200f,
                    centers[c][2] + (rnd.next() - 0.5f) * 1200f,
                )
            }
        }
        // 簇内稀疏弹簧：每星连初值下最近的 4 个簇友（s=0.9 强语义簇）
        val links = ArrayList<TasteEmergentLayout.Link>()
        for (c in centers.indices) {
            val base = c * perCluster
            for (k in 0 until perCluster) {
                val a = base + k
                val near = (0 until perCluster)
                    .filter { it != k }
                    .sortedBy {
                        val b = base + it
                        dist(nodes[a].x, nodes[a].y, nodes[a].z, nodes[b].x, nodes[b].y, nodes[b].z)
                    }
                    .take(4)
                for (it in near) {
                    val b = base + it
                    if (a < b || links.none { l -> l.a == b && l.b == a }) {
                        // span=800（标定区档——1600 起始值对簇内 nn 尺度过伸，见笔记「标定回路的第一课」）
                        links += TasteEmergentLayout.Link(a, b, 0.9f, F.idealDist(10f, 10f, 0.9f, 800f))
                    }
                }
            }
        }
        return nodes to links
    }

    @Test
    fun `合成簇-收敛且语义保持`() {
        val (nodes, links) = synthClusterUniverse()
        // 标定域参数（彩排网格实测过闸区：斥力抬到 2.5e4 档——设计起始值 2.5e3 在
        // gLay=7.6e4 下纯压缩区，详见 r303 彩排报告网格表）
        val r = L.shape(
            nodes, links,
            TasteEmergentForces.Params(kR = 2.5e4f, gLay = 4e4f, applyHaloDrift = false),
        )
        assertTrue("健康闸应过", r.gates.healthOk)
        assertEquals("硬核违约应=0", 0, r.violationsFinal)
        val stress = r.stressFinal
        assertTrue("应力 S=$stress 应 <0.35（合成簇口径）", stress < 0.35f)
        val keep = L.neighborPreservation(r.n, FloatArray(r.n) { r.positions[3 * it] },
            FloatArray(r.n) { r.positions[3 * it + 1] }, FloatArray(r.n) { r.positions[3 * it + 2] }, links)
        assertTrue("邻域保持率=$keep 应 ≥0.5（§3.7-1）", keep >= 0.5f)
    }

    @Test
    fun `万步浸泡-不坍缩稳态`() {
        val (nodes, links) = synthClusterUniverse(perCluster = 15) // N=60 控测试时长
        val r = L.shape(
            nodes, links,
            TasteEmergentForces.Params(kR = 2.5e4f, gLay = 4e4f, applyHaloDrift = false, soakSteps = 10000),
        )
        val soak = r.soak!!
        assertTrue(
            "长期不坍缩硬闸：r90Drift=${soak.r90Drift} 质心漂=${soak.centroidDrift} " +
                "KS=${soak.ksD}/${soak.ksCrit} shrinkFrac=${soak.shrinkFrac}",
            soak.steady(),
        )
        assertFalse("健康配置不得呈坍缩相", soak.collapsing())
    }

    @Test
    fun `浸泡-家弹簧径向钉壳不失守`() {
        // 彩排第五轮实锤回归锚：soak 段漏挂家弹簧=重力单极慢坍缩（r90 −5%/质心漂 776）。
        // 钉壳星环（rPref=3000）浸泡 3000 步：半径中位不得漂离 ±15%，且稳态闸过。
        val n = 40
        val rnd = Rnd(555)
        val nodes = (0 until n).map { i ->
            val th = rnd.next() * 6.2831853f
            val ph = (rnd.next() - 0.5f) * 3.14159265f
            val r = 3000f
            TasteEmergentLayout.Node(
                (i + 1).toLong(), 0.5f, 10f, 1f, 3000f,
                r * kotlin.math.cos(ph) * kotlin.math.cos(th),
                r * kotlin.math.sin(ph),
                r * kotlin.math.cos(ph) * kotlin.math.sin(th),
            )
        }
        val r = TasteEmergentLayout.shape(
            nodes, emptyList(),
            TasteEmergentForces.Params(kHome = 0.1f, roundsMax = 400, soakSteps = 3000, applyHaloDrift = false),
        )
        val rs = FloatArray(n) {
            sqrt(r.positions[3 * it] * r.positions[3 * it] + r.positions[3 * it + 1] * r.positions[3 * it + 1] +
                r.positions[3 * it + 2] * r.positions[3 * it + 2])
        }.sorted()
        val rMed = rs[n / 2]
        assertTrue("钉壳半径中位 $rMed 应守 3000±15%", rMed > 2550f && rMed < 3450f)
        assertTrue("浸泡稳态闸应过：${r.soak}", r.soak!!.steady())
    }

    // ==================== 纯引力判废（§6.4 反向钉死——禁态永留测试） ====================

    @Test
    fun `判废-纯引力配置必塌`() {
        val (nodes, links0) = synthClusterUniverse(perCluster = 15)
        // 判废配置=设计 §6.4 原文口径的全拆版：k_R=0 ∧ k_H=0 ∧ 弹簧 L 下界钳拆除
        // （L=0=纯吸引无平衡点——真实引力 1/d² 无静态平衡的复刻，R249c 的数学根）
        val links = links0.map { TasteEmergentLayout.Link(it.a, it.b, it.s, 0f, it.strong) }
        val r = L.shape(
            nodes, links,
            TasteEmergentForces.Params(
                kR = 0f, kHard = 0f, applyHaloDrift = false, soakSteps = 10000,
            ),
        )
        val soak = r.soak!!
        // 必塌证词（阻尼弛豫+位移帽下 10k 步窗内「方向性收缩+重合发生」即必塌——
        // r90 长程落差属标定回路万倍窗口径）：①浸泡段单调收缩段 >0.7 ②r90 收缩方向
        // ③收尾投影清零的违约在浸泡末窗重新出现（星体重合=用户补刀「渐渐重合」直读）
        val r90First = r.probes.first().r90
        val lastViol = r.probes.last().violations
        assertTrue(
            "纯引力配置必塌（用户补刀立法）：r90 $r90First→${r.r90} " +
                "shrinkFrac=${soak.shrinkFrac} 末窗违约=$lastViol",
            soak.shrinkFrac > 0.7f && soak.r90Drift < 0f && lastViol > 0,
        )
    }

    // ==================== 确定性 / 旋钮 / 偏心 ====================

    @Test
    fun `确定性-同数据同宇宙逐位一致`() {
        val (nodes, links) = synthClusterUniverse(perCluster = 10)
        val p = TasteEmergentForces.Params(applyHaloDrift = false, roundsMax = 300)
        val a = L.shape(nodes, links, p)
        val b = L.shape(nodes, links, p)
        assertTrue(a.positions.contentEquals(b.positions))
    }

    @Test
    fun `暗能量旋钮-开则云幅扩张`() {
        val (nodes, links) = synthClusterUniverse(perCluster = 10)
        val off = L.shape(nodes, links, TasteEmergentForces.Params(applyHaloDrift = false, roundsMax = 300))
        val on = L.shape(
            nodes, links,
            TasteEmergentForces.Params(applyHaloDrift = false, roundsMax = 300, kDE = 4f),
        )
        assertTrue("kDE=4 云幅 ${on.r90} 应 > 关闭 ${off.r90}", on.r90 > off.r90)
    }

    @Test
    fun `家弹簧-径向偏心正交于涌现`() {
        // 孤星挂家弹簧（rPref=500）：径向被拉回近带，斥力/基底照常
        val nodes = listOf(
            TasteEmergentLayout.Node(1L, 0.5f, 10f, 1f, 500f, 4000f, 0f, 0f),
            TasteEmergentLayout.Node(2L, 0.5f, 10f, 1f, Float.NaN, 4100f, 50f, 0f),
        )
        val r = L.shape(
            nodes, emptyList(),
            TasteEmergentForces.Params(applyHaloDrift = false, roundsMax = 600),
        )
        val r1 = sqrt(r.positions[0] * r.positions[0] + r.positions[1] * r.positions[1] + r.positions[2] * r.positions[2])
        assertTrue("家弹簧星半径 $r1 应被拉向近带（<2000）", r1 < 2000f)
    }
}
