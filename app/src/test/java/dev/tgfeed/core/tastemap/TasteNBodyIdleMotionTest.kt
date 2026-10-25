package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * R343 怠速治理纯数学锚（core 单源件的 JVM 承重——UI 物理场无 JVM 直测面
 * （Android Log/SystemClock 依赖），行为数学半体在此锚定+源锚锁接线）：
 *
 *  - **渲染插值相位**（[TasteNBody.renderPhase01]）：「低频步进拍 × draw 相位
 *    线性插值」机制的相位口——拍内线性爬坡/钳位/负差归零；
 *  - **时钟域契约**（R343 根因锚）：相位只认同钟相减——epoch 墙钟 vs uptime
 *    的历史错配形（恒钳 1.0=插值死拍=5s 跳格）作为契约演示锚固化，防复发；
 *  - **双星孤立二体**（D2 留种）：软化圆速度上界/量纲收敛/二体百拍零残漂
 *    （退役干净）+ **60fps 跨拍插值连续性**（用户验收②的 JVM 化——相邻帧
 *    位移/角度增量≈常量；**死拍对照**（phase 恒 1=原 bug 形）必须被同一
 *    断言抓死——锚抓得住原病）。
 */
class TasteNBodyIdleMotionTest {

    // ==================== D3 渲染插值相位 ====================

    @Test
    fun `渲染相位-拍内线性爬坡与端点钳位`() {
        val tick = 5000L
        val stepAt = 10_000L
        // 零相位（步进刚落）→ 中点 → 满相位
        assertEquals(0f, TasteNBody.renderPhase01(stepAt, stepAt, tick), 0f)
        assertEquals(0.5f, TasteNBody.renderPhase01(stepAt + 2500L, stepAt, tick), 0f)
        assertEquals(1f, TasteNBody.renderPhase01(stepAt + tick, stepAt, tick), 0f)
        // 超拍（步进迟到）钳 1——迟到的拍由下一次步进补
        assertEquals(1f, TasteNBody.renderPhase01(stepAt + 2 * tick, stepAt, tick), 0f)
        // 负差（时钟回拨/相位取在步进前）归 0
        assertEquals(0f, TasteNBody.renderPhase01(stepAt - 100L, stepAt, tick), 0f)
    }

    @Test
    fun `渲染相位-拍内单调不减`() {
        val tick = 5000L
        val stepAt = 1_000L
        var prev = -1f
        for (k in 0..20) {
            val p = TasteNBody.renderPhase01(stepAt + k * (tick / 20), stepAt, tick)
            assertTrue("相位必须单调不减（k=$k p=$p prev=$prev）", p >= prev)
            prev = p
        }
    }

    @Test
    fun `渲染相位-时钟域契约演示锚`() {
        // R343 根因实锤的数学形：epoch 墙钟（System.currentTimeMillis 量级）与
        // uptime（SystemClock.uptimeMillis 量级）互减恒 +1.79e12 → 相位恒钳
        // 1.0=插值死拍=星只在 5s 步进拍上跳格。契约=两侧必须同钟——本锚固化
        // 该错配形，供源锚（两侧同符号 SystemClock.uptimeMillis）与回归测试
        // 双轨防复发。
        val uptimeNow = 500_000_000L // ≈5.8 天开机时长（uptime 量级）
        val epochNow = 1_790_000_000_000L // 2026-10 epoch 墙钟量级
        val stepAtUptime = uptimeNow - 2500L // 步进 2.5s 前（uptime 域）
        assertEquals(
            "epoch−uptime 错配必须复现恒钳 1（死拍形）",
            1f,
            TasteNBody.renderPhase01(epochNow, stepAtUptime, 5000L),
            0f,
        )
        // 同钟（uptime 域）则得真相位
        assertEquals(
            "同域 uptime 相减必须得真相位",
            0.5f,
            TasteNBody.renderPhase01(uptimeNow, stepAtUptime, 5000L),
            0f,
        )
    }

    // ==================== D2 双星孤立二体（留种数学） ====================

    /** 双星对测试场景（镜像 ui 物理场 build 的对 IC 数学——等质量对称对） */
    private fun symmetricPair(): TasteNBody.NBodyState {
        val masses = floatArrayOf(0.8f, 0.8f)
        val px = floatArrayOf(-60f, 60f)
        val py = floatArrayOf(0f, 0f)
        val pz = floatArrayOf(0f, 0f)
        val s = TasteNBody.createState(2, masses, px, py, pz)
        val d = 120f
        val mSum = masses[0] + masses[1]
        // 切向 t̂=normalize(u×Y)，u=(1,0,0) → t̂=(0,0,−1)（build 原式 tx=uz, tz=−ux）
        val vRel = TasteNBody.binaryCircularVelSoftened(mSum, d)
        val va = masses[1] / mSum * vRel
        val vb = masses[0] / mSum * vRel
        s.vx[0] = 0f; s.vy[0] = 0f; s.vz[0] = -va
        s.vx[1] = 0f; s.vy[1] = 0f; s.vz[1] = vb
        TasteNBody.accelBarnesHut(s) // 首步 Verlet 需要初始加速度
        return s
    }

    @Test
    fun `双星圆速度-软化修正上界量纲收敛与质量单调`() {
        // 软化圆速度恒 ≤ 牛顿口径（软化削向心力）
        for (d in floatArrayOf(40f, 80f, 120f, 300f, 1000f)) {
            val mSum = 1.6f
            val vSoft = TasteNBody.binaryCircularVelSoftened(mSum, d)
            val vNewton = sqrt(TasteNBody.G * mSum / d)
            assertTrue("软化圆速度必须 ≤ 牛顿口径（d=$d）", vSoft <= vNewton)
        }
        // d≫ε 收敛牛顿口径（量纲自检）
        val vFar = TasteNBody.binaryCircularVelSoftened(1.6f, 8000f)
        val vNewtonFar = sqrt(TasteNBody.G * 1.6f / 8000f)
        assertEquals("d≫ε 必须收敛牛顿口径", 1f, vFar / vNewtonFar, 0.01f)
        // 质量单调（v ∝ √mSum）
        assertTrue(
            "圆速度必须随质量和单调增",
            TasteNBody.binaryCircularVelSoftened(1.6f, 120f) >
                TasteNBody.binaryCircularVelSoftened(0.8f, 120f),
        )
    }

    @Test
    fun `双星孤立二体-软化圆轨道百拍零残漂`() {
        // 退役「干净」的数学锚：软化修正 IC+Verlet 百拍——分离距离漂移 <2%
        // （轨道闭合零残漂）+ 动量守恒（对称内力）——对质心永驻的构造前提。
        val s = symmetricPair()
        val d0 = 120f
        var maxDrift = 0f
        repeat(100) {
            TasteNBody.step(s, TasteNBody.DT)
            TasteNBody.applyBound(s)
            TasteNBody.applyVCap(s)
            val dx = s.px[1] - s.px[0]
            val dy = s.py[1] - s.py[0]
            val dz = s.pz[1] - s.pz[0]
            val dNow = sqrt(dx * dx + dy * dy + dz * dz)
            maxDrift = maxOf(maxDrift, abs(dNow - d0) / d0)
        }
        assertTrue("二体软化圆轨道百拍分离漂移实锤：${"%.4f".format(maxDrift)}", maxDrift < 0.02f)
        assertTrue("二体总动量必须守恒（对称内力）", s.momentum() < 1e-4f)
    }

    @Test
    fun `双星轨道-60fps 跨拍插值位移与角增量连续无台阶`() {
        // 用户验收②的 JVM 化：3 个 5s 拍 × 60fps 采样成员 0 的渲染位
        // （renderPhase01 驱动 prev→cur lerp）——相邻帧位移/绕质心角增量
        // ≈常量（无 5s 台阶）；**死拍对照**（phase 恒 1=R343 原病形）必须被
        // 同一断言抓死（中位归零=运动不在场）。
        val s = symmetricPair()
        val tick = 5000L
        val frameMs = 1000f / 60f
        val comX = 0f // 等质量对称对：质心=原点（永驻锚）
        val comZ = 0f

        fun samplePositions(deadClock: Boolean): ArrayList<FloatArray> {
            val positions = ArrayList<FloatArray>()
            repeat(3) {
                val prev = floatArrayOf(s.px[0], s.py[0], s.pz[0])
                TasteNBody.step(s, TasteNBody.DT)
                TasteNBody.applyBound(s)
                TasteNBody.applyVCap(s)
                val cur = floatArrayOf(s.px[0], s.py[0], s.pz[0])
                var tMs = 0f
                while (tMs < tick) {
                    val phase = if (deadClock) 1f else TasteNBody.renderPhase01(tMs.toLong(), 0L, tick)
                    val inv = 1f - phase
                    positions += floatArrayOf(
                        inv * prev[0] + phase * cur[0],
                        inv * prev[1] + phase * cur[1],
                        inv * prev[2] + phase * cur[2],
                    )
                    tMs += frameMs
                }
            }
            return positions
        }

        // —— 活插值（修复后形态）——
        val positions = samplePositions(deadClock = false)
        val disp = (1 until positions.size).map { i ->
            val a = positions[i - 1]
            val b = positions[i]
            sqrt((b[0] - a[0]) * (b[0] - a[0]) + (b[2] - a[2]) * (b[2] - a[2]))
        }
        val sorted = disp.sorted()
        val median = sorted[sorted.size / 2]
        val max = sorted.last()
        assertTrue("活插值帧位移中位必须非零（运动在场）", median > 1e-4f)
        assertTrue(
            "活插值帧位移台阶实锤：max=${"%.5f".format(max)} median=${"%.5f".format(median)}",
            max <= median * 1.3f,
        )
        // 绕质心角增量 ≈ 常量（任务书「旋转角连续性」锚——真运动分量的丝滑；
        // 角差按 ±π 环绕解包裹——atan2 支割线（±π）上的真小增量不得读成 2π 跳）
        val angles = positions.map { p -> kotlin.math.atan2(p[2] - comZ, p[0] - comX) }
        val tau = 2.0 * kotlin.math.PI
        val dAng = (1 until angles.size).map { i ->
            val raw = (angles[i] - angles[i - 1]).toDouble()
            val wrapped = ((raw + kotlin.math.PI) % tau + tau) % tau - kotlin.math.PI
            abs(wrapped).toFloat()
        }
        val dAngSorted = dAng.sorted()
        val dAngMedian = dAngSorted[dAngSorted.size / 2]
        val dAngMax = dAngSorted.last()
        assertTrue("活插值角增量中位必须非零", dAngMedian > 1e-7f)
        assertTrue(
            "活插值角增量台阶实锤：max=${"%.3e".format(dAngMax)} median=${"%.3e".format(dAngMedian)}",
            dAngMax <= dAngMedian * 1.5f,
        )

        // —— 死拍对照（R343 原病形：phase 恒 1 → 只在拍边界跳）——
        val s2 = symmetricPair()
        val deadPositions = ArrayList<FloatArray>()
        repeat(3) {
            val prev = floatArrayOf(s2.px[0], s2.py[0], s2.pz[0])
            TasteNBody.step(s2, TasteNBody.DT)
            TasteNBody.applyBound(s2)
            TasteNBody.applyVCap(s2)
            val cur = floatArrayOf(s2.px[0], s2.py[0], s2.pz[0])
            var tMs = 0f
            while (tMs < tick) {
                // phase≡1：历史 bug 形（帧组装 epoch 钟与 uptime 步进钟错配恒钳 1）
                val phase = 1f
                val inv = 1f - phase
                deadPositions += floatArrayOf(
                    inv * prev[0] + phase * cur[0],
                    inv * prev[1] + phase * cur[1],
                    inv * prev[2] + phase * cur[2],
                )
                tMs += frameMs
            }
        }
        val deadDisp = (1 until deadPositions.size).map { i ->
            val a = deadPositions[i - 1]
            val b = deadPositions[i]
            sqrt((b[0] - a[0]) * (b[0] - a[0]) + (b[2] - a[2]) * (b[2] - a[2]))
        }
        val deadSorted = deadDisp.sorted()
        val deadMedian = deadSorted[deadSorted.size / 2]
        val deadMax = deadSorted.last()
        // 死拍形：拍内位移全零（中位=0）+拍边界大跳（max≈整拍位移）——
        // 同一「中位非零+带宽」断言必抓死（锚抓得住原病的证明）
        assertTrue("死拍对照中位必须归零（拍内恒停）", deadMedian < 1e-4f)
        assertTrue("死拍对照拍边界必须大跳（max=${"%.5f".format(deadMax)}）", deadMax > deadMedian * 1.3f)
    }
}
