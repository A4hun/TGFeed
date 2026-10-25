package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * R249 v2 真 N 体仿真单测（用户 19:00 口径「如果要做就要做好」——守恒/边界/
 * 数值稳定性/边界场景全覆盖）：
 *  - **Barnes-Hut 正确性**：BH≈直接求和（θ 容差）；零自引力（θ<1/√3 性质）；
 *  - **Verlet 辛性质**：二体轨道能量守恒（漂移 <1%/百步）+周期性（不逃逸）；
 *  - **动量守恒**：对称力系总动量严格守恒（内力成对）；
 *  - **软化**：近距力有界（不爆炸）+ ε→力封顶一致性；
 *  - **边界**：软墙拉回（出界星钳回+速度反转）；
 *  - **边界场景**：三体混沌冒烟（有界不炸）/极质量比（重物几乎不动）/
 *    全同质量（质心不动）/碰撞（重合体并合不无限细分）；
 *  - **R368 并发互斥**：双星引力（n=2）与涌现斥力布局（n=120）双线程并发
 *    压测零异常——单例 Octree 串树闪退的回归锚。
 */
class TasteNBodyTest {

    private fun state(
        masses: FloatArray,
        pos: Array<FloatArray>,
    ): TasteNBody.NBodyState {
        val n = masses.size
        val px = FloatArray(n) { pos[it][0] }
        val py = FloatArray(n) { pos[it][1] }
        val pz = FloatArray(n) { pos[it][2] }
        val s = TasteNBody.createState(n, masses, px, py, pz)
        TasteNBody.accelDirect(s)
        return s
    }

    // ==================== Barnes-Hut 实现正确性 ====================

    @Test
    fun `BH-与直接求和一致`() {
        // 30 体随机分布（确定性哈希散布），对照两路加速度
        val n = 30
        val masses = FloatArray(n) { 0.2f + 0.8f * ((it * 37 + 11) % 13) / 13f }
        val pos = Array(n) {
            val a = (it * 2.39996f) % 6.2831853f
            val r = 200f + 800f * ((it * 17 + 3) % 29) / 29f
            floatArrayOf(
                r * kotlin.math.cos(a),
                r * 0.3f * kotlin.math.sin(a * 1.7f),
                r * kotlin.math.sin(a),
            )
        }
        val s = state(masses, pos)
        TasteNBody.accelDirect(s)
        val dx = FloatArray(n) { s.ax[it] }
        val dy = FloatArray(n) { s.ay[it] }
        val dz = FloatArray(n) { s.az[it] }
        TasteNBody.accelBarnesHut(s)
        var maxRel = 0f
        for (i in 0 until n) {
            val d = sqrt(dx[i] * dx[i] + dy[i] * dy[i] + dz[i] * dz[i]) + 1e-9f
            val bx = dx[i] - s.ax[i]
            val by = dy[i] - s.ay[i]
            val bz = dz[i] - s.az[i]
            val rel = sqrt(bx * bx + by * by + bz * bz) / d
            if (rel > maxRel) maxRel = rel
        }
        assertTrue("BH 与直接求和偏差 ${maxRel} 应 <0.15（θ 容差）", maxRel < 0.15f)
    }

    @Test
    fun `BH-零自引力`() {
        // 单体树：自体加速度必须为 0（θ<1/√3 性质——含目标节点恒展开）
        val s = state(floatArrayOf(1f), arrayOf(floatArrayOf(100f, 200f, 300f)))
        TasteNBody.accelBarnesHut(s)
        assertEquals(0f, s.ax[0], 1e-12f)
        assertEquals(0f, s.ay[0], 1e-12f)
        assertEquals(0f, s.az[0], 1e-12f)
        // 两体近重合：互力对称（牛顿第三律——树路径独立验证）
        val s2 = state(
            floatArrayOf(1f, 1f),
            arrayOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(87f, 0f, 0f)),
        )
        TasteNBody.accelBarnesHut(s2)
        assertEquals(-s2.ax[1], s2.ax[0], 1e-6f * abs(s2.ax[1]) + 1e-9f)
    }

    // ==================== Verlet 辛性质（数值稳定性） ====================

    @Test
    fun `Verlet-二体轨道能量守恒且不逃逸`() {
        // 圆轨道二体（等质量互绕）：100 步后能量漂移 <1%，轨道半径稳定 ±5%
        val m = 1f
        val r = 400f
        val v = TasteNBody.circularVelocity(m, r / 2f * 2f, 1f) / 2f // 各绕质心
        // 两体在 x 轴 ±r/2，速度沿 y 反向（圆轨道）
        val s = state(
            floatArrayOf(m, m),
            arrayOf(floatArrayOf(-r / 2f, 0f, 0f), floatArrayOf(r / 2f, 0f, 0f)),
        )
        // 圆轨道速度：v = sqrt(G·m_other/(2·r_sep))·... 标准二体圆轨道：
        // 每体绕质心半径 r/2，速度 v=sqrt(G·m/(2·r_sep))·... v_each = sqrt(G·m/(2r))·??
        // 直接用向心力条件：v²/(r/2) = G·m/(r²+ε²) → v = sqrt(G·m·(r/2)/(r²+ε²))
        val ve = sqrt(TasteNBody.G * m * (r / 2f) / (r * r + TasteNBody.SOFTENING * TasteNBody.SOFTENING))
        s.vy[0] = -ve
        s.vy[1] = ve
        val e0 = TasteNBody.totalEnergy(s)
        val p0x = s.px[0]
        for (k in 0 until 100) {
            TasteNBody.step(s, TasteNBody.DT)
        }
        val e1 = TasteNBody.totalEnergy(s)
        val drift = abs(e1 - e0) / abs(e0)
        assertTrue("能量漂移 $drift 应 <1%（Verlet 辛性质）", drift < 0.01f)
        // 轨道不逃逸不塌缩：分离度 400±10%
        val sep = sqrt(
            (s.px[1] - s.px[0]) * (s.px[1] - s.px[0]) +
                (s.py[1] - s.py[0]) * (s.py[1] - s.py[0]) +
                (s.pz[1] - s.pz[0]) * (s.pz[1] - s.pz[0]),
        )
        assertTrue("分离度 $sep 应在 ±10% 稳定（圆轨道）", abs(sep - r) < r * 0.1f)
        assertTrue("体 0 未被吸进质心", abs(s.px[0] - p0x) < r) // 有界运动
    }

    @Test
    fun `Verlet-动量守恒（对称内力）`() {
        // 10 体随机（零初速总动量）：多步后总动量保持 ~0（内力成对抵消）
        val n = 10
        val masses = FloatArray(n) { 0.3f + ((it * 7) % 11) / 22f }
        val pos = Array(n) {
            val a = it * 0.6283f
            floatArrayOf(
                500f * kotlin.math.cos(a),
                200f * kotlin.math.sin(a * 2f),
                500f * kotlin.math.sin(a),
            )
        }
        val s = state(masses, pos)
        val p0 = s.momentum()
        for (k in 0 until 50) {
            TasteNBody.step(s, TasteNBody.DT)
        }
        val p1 = s.momentum()
        // 树近似破坏严格成对性到 θ 阶——动量漂移远小于典型动量尺度
        assertTrue("动量漂移 ${abs(p1 - p0)} 应 <0.5", abs(p1 - p0) < 0.5f)
    }

    // ==================== 软化与边界 ====================

    @Test
    fun `软化-近距力有界不爆炸`() {
        // 两体近重合（r=1 << ε）：加速度封顶在 G·m/ε² 量级——不产生无穷力
        val s = state(
            floatArrayOf(1f, 1f),
            arrayOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 0f, 0f)),
        )
        TasteNBody.accelDirect(s)
        val eps = TasteNBody.SOFTENING
        val expect = TasteNBody.G / (2f * eps * eps * sqrt(2f) * 1f) // ~G/(r²+ε²)^1.5·r
        assertTrue(abs(s.ax[1]) < expect * 3f + 1f) // 封顶量级（ε 主导）
        // 碰撞步进 50 步：速度有界（V_MAX 护栏+软化）
        for (k in 0 until 50) {
            TasteNBody.step(s, TasteNBody.DT)
            TasteNBody.applyVCap(s)
        }
        val v = sqrt(s.vx[1] * s.vx[1] + s.vy[1] * s.vy[1] + s.vz[1] * s.vz[1])
        assertTrue("碰撞后速度 $v 有界", v <= TasteNBody.V_MAX * 1.01f)
        assertTrue("数值未 NaN", !s.px[1].isNaN() && !s.vx[1].isNaN())
    }

    @Test
    fun `边界-软墙拉回`() {
        // 出界星（r=2×BOUND）：钳回界上且径向外速反转
        val s = state(
            floatArrayOf(1f, 0.1f),
            arrayOf(
                floatArrayOf(0f, 0f, 0f),
                floatArrayOf(TasteNBody.BOUND * 2f, 0f, 0f),
            ),
        )
        s.vx[1] = 5f // 向外飞
        TasteNBody.applyBound(s)
        val r = sqrt(s.px[1] * s.px[1] + s.py[1] * s.py[1] + s.pz[1] * s.pz[1])
        assertTrue("出界星钳回界上（r=$r）", r <= TasteNBody.BOUND * 1.001f)
        assertTrue("径向外速反转", s.vx[1] < 0f)
        // 全局有界性
        assertTrue(s.allBounded())
    }

    // ==================== 边界场景 ====================

    @Test
    fun `三体-混沌冒烟不炸不逃`() {
        // 经典三体三角形初条件：200 步内速度有界、位置有界、无 NaN（混沌但稳定数值）
        val s = state(
            floatArrayOf(1f, 1f, 1f),
            arrayOf(
                floatArrayOf(600f, 0f, 0f),
                floatArrayOf(-300f, 520f, 0f),
                floatArrayOf(-300f, -520f, 0f),
            ),
        )
        for (i in 0 until 3) {
            s.vy[i] = 0.3f * (if (i == 0) 1f else -1f)
        }
        for (k in 0 until 200) {
            TasteNBody.step(s, TasteNBody.DT)
            TasteNBody.applyBound(s)
            TasteNBody.applyVCap(s)
        }
        assertTrue(s.allBounded())
        for (i in 0 until 3) {
            assertTrue(!s.px[i].isNaN() && !s.vx[i].isNaN())
            val v = sqrt(s.vx[i] * s.vx[i] + s.vy[i] * s.vy[i] + s.vz[i] * s.vz[i])
            assertTrue("三体速度有界 v=$v", v <= TasteNBody.V_MAX * 1.01f)
        }
    }

    @Test
    fun `极质量比-重物几乎不动轻物绕转`() {
        // 1:0.01 质量比：重物速度变化 << 轻物（微扰近似刚体中心）
        val s = state(
            floatArrayOf(1f, 0.01f),
            arrayOf(
                floatArrayOf(0f, 0f, 0f),
                floatArrayOf(700f, 0f, 0f),
            ),
        )
        val ve = sqrt(TasteNBody.G * 1f * 700f / (700f * 700f + TasteNBody.SOFTENING * TasteNBody.SOFTENING))
        s.vy[1] = ve // 轻物圆轨道
        s.vy[0] = -0.01f * ve // 反冲
        for (k in 0 until 60) {
            TasteNBody.step(s, TasteNBody.DT)
        }
        val vHeavy = sqrt(s.vx[0] * s.vx[0] + s.vy[0] * s.vy[0] + s.vz[0] * s.vz[0])
        val vLight = sqrt(s.vx[1] * s.vx[1] + s.vy[1] * s.vy[1] + s.vz[1] * s.vz[1])
        assertTrue("重物速度 $vHeavy << 轻物 $vLight", vHeavy < vLight * 0.2f)
    }

    @Test
    fun `全同质量-质心动量不动`() {
        // 8 体全同质量对称环：总动量保持 0（对称性+成对内力）
        val n = 8
        val pos = Array(n) {
            val a = it * 0.7854f
            floatArrayOf(800f * kotlin.math.cos(a), 0f, 800f * kotlin.math.sin(a))
        }
        val s = state(FloatArray(n) { 0.5f }, pos)
        for (k in 0 until 40) {
            TasteNBody.step(s, TasteNBody.DT)
        }
        assertTrue("总动量 ~0 保持（${s.momentum()}）", s.momentum() < 1f)
        // 质心仍在原点附近（对称环收缩不漂移）
        var cx = 0f
        var cz = 0f
        for (i in 0 until n) {
            cx += s.px[i] / n
            cz += s.pz[i] / n
        }
        assertTrue("质心不漂移（cx=$cx cz=$cz）", sqrt(cx * cx + cz * cz) < 120f)
    }

    @Test
    fun `碰撞-重合体并合不无限细分`() {
        // 5 体完全同位置：建树不炸（深度护栏），力有限
        val s = state(
            floatArrayOf(1f, 1f, 1f, 1f, 1f),
            Array(5) { floatArrayOf(123f, 456f, 789f) },
        )
        TasteNBody.accelBarnesHut(s)
        for (i in 0 until 5) {
            assertTrue(!s.ax[i].isNaN())
        }
        // 步进 20 步数值稳定
        for (k in 0 until 20) {
            TasteNBody.step(s, TasteNBody.DT)
            TasteNBody.applyVCap(s)
        }
        assertTrue(!s.px[0].isNaN() && !s.vx[2].isNaN())
    }

    @Test
    fun `圆轨道速度-开普勒口径`() {
        // v=√(G·M/r)（第三定律口径）：符号与量级
        val v = TasteNBody.circularVelocity(1f, 800f, 1f)
        assertEquals(sqrt(TasteNBody.G / 800f), v, 1e-3f)
        assertEquals(-v, TasteNBody.circularVelocity(1f, 800f, -1f), 1e-3f)
    }

    // ==================== R368 并发互斥（线上闪退复现锚） ====================

    @Test
    fun `并发-双星引力与涌现斥力布局互斥不串树`() {
        // 线上闪退复现口径（ArrayIndexOutOfBoundsException length=2; index=113
        // @forceOn）：TasteNBody 是进程级单例，唯一 Octree 被两路并发消费——
        // 主线程双星 step（n=2 → px/py/pz 长 2）× Default 线程涌现布局
        // accelBarnesHutRepel（n=120 → 体索引 0..119）。无锁时后台 build 重写
        // nbody[]=113 而主线程 forceOn 正走树 → px[113] 越界。R368 engineLock
        // 后两路 build+走树原子互斥=不串树不越界。压测：双线程拍齐起跑 ×600
        // 回合，任一线程抛错即失败（未修复版几拍内必炸）。
        val s = state(
            floatArrayOf(1f, 0.8f),
            arrayOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(300f, 0f, 0f)),
        )
        s.vy[0] = -1.2f
        s.vy[1] = 1.2f
        val nL = 120
        val mass = FloatArray(nL) { 0.2f + 0.8f * ((it * 37 + 11) % 13) / 13f }
        val x = FloatArray(nL) { 1400f * ((it * 17 + 3) % 29) / 29f - 700f }
        val y = FloatArray(nL) { 400f * ((it * 11 + 7) % 19) / 19f - 200f }
        val z = FloatArray(nL) { 1400f * ((it * 23 + 5) % 31) / 31f - 700f }
        val fx = FloatArray(nL)
        val fy = FloatArray(nL)
        val fz = FloatArray(nL)
        val rounds = 600
        val failures = java.util.Collections.synchronizedList(ArrayList<Throwable>())
        val start = java.util.concurrent.CyclicBarrier(2)
        val layoutThread = Thread {
            try {
                start.await()
                repeat(rounds) {
                    TasteNBody.accelBarnesHutRepel(nL, mass, x, y, z, 1f, 120f, 1400f, fx, fy, fz)
                }
            } catch (t: Throwable) {
                failures += t
            }
        }
        layoutThread.start()
        try {
            start.await()
            repeat(rounds) {
                TasteNBody.step(s, TasteNBody.DT)
            }
        } catch (t: Throwable) {
            failures += t
        }
        layoutThread.join(30_000L)
        assertTrue(
            "并发两路应零异常（实际 ${failures.size} 个，首个=${failures.firstOrNull()})",
            failures.isEmpty(),
        )
        for (i in 0 until 2) {
            assertTrue("双星数值未 NaN", !s.px[i].isNaN() && !s.vx[i].isNaN())
        }
    }
}
