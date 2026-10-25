package dev.tgfeed.core.tastemap

import dev.tgfeed.ui.galaxyOrbitInclination
import dev.tgfeed.ui.planetOrbitPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * R320 日月食单测（r315 G⑤ 锚表）：凌星判定几何（掩星分数/面积比深度/渐入渐出
 * 单调）/食深帽 ≤4%/轨道倾角反例确定性（有的系统永远不凌星=诚实——渲染管线同
 * 式几何的纯函数级复证）/消光系数域（重合度线性+dim 帽 0.2+shiftK 帽 −300K）。
 */
class EclipseFxTest {

    // ---------------- 凌星判定（transit——渲染期几何判定纯函数） ----------------

    @Test
    fun `凌星掩星-域外零全内合一渐入线性`() {
        // 行星视半径 12px / 恒星视半径 100px
        assertEquals(0f, EclipseFx.transitOverlap01(12f, 100f, 112f), 0.001f) // 域外（相切外）
        assertEquals(0f, EclipseFx.transitOverlap01(12f, 100f, 130f), 0.001f)
        assertEquals(1f, EclipseFx.transitOverlap01(12f, 100f, 88f), 0.001f) // 全内合
        assertEquals(1f, EclipseFx.transitOverlap01(12f, 100f, 0f), 0.001f) // 正中
        // 渐入：112→88 单调上升（线性坡）
        val mid = EclipseFx.transitOverlap01(12f, 100f, 100f)
        assertEquals(0.5f, mid, 0.01f)
        assertTrue(EclipseFx.transitOverlap01(12f, 100f, 96f) > mid)
        assertTrue(EclipseFx.transitOverlap01(12f, 100f, 104f) < mid)
        // 防御：零半径域
        assertEquals(0f, EclipseFx.transitOverlap01(0f, 100f, 0f), 0.001f)
        assertEquals(0f, EclipseFx.transitOverlap01(12f, 0f, 0f), 0.001f)
    }

    @Test
    fun `凌星深度-面积比真实测光量级`() {
        // 全内合深度=(rP/starR)²——行星:恒星 1:8.7（R247 系数域下界 0.115）→1.3%
        assertEquals(0.0132f, EclipseFx.transitDepth01(11.5f, 100f, 0f), 0.0005f)
        // 1:5（系数域上限 0.20）→4.0%=食深帽
        assertEquals(0.04f, EclipseFx.transitDepth01(20f, 100f, 0f), 0.0005f)
        // 域外零
        assertEquals(0f, EclipseFx.transitDepth01(20f, 100f, 150f), 0.0001f)
    }

    @Test
    fun `食深帽-4Pct红线防灭星`() {
        // 超帽半径比（防御性输入——行星:恒星 1:2）→ 钳 4%
        assertEquals(0.04f, EclipseFx.transitDepth01(50f, 100f, 0f), 0.0001f)
        assertEquals(EclipseFx.DEPTH_CAP01, 0.04f, 0.0001f)
        // 任意屏距域内不越帽
        for (d in intArrayOf(0, 20, 40, 60, 80, 100, 130)) {
            assertTrue(
                "深度恒 ≤4%（d=$d）",
                EclipseFx.transitDepth01(50f, 100f, d.toFloat()) <= EclipseFx.DEPTH_CAP01 + 1e-6f,
            )
        }
    }

    @Test
    fun `凌星渐入渐出-深度随屏距单调下降`() {
        var prev = Float.MAX_VALUE
        for (d in 0..140 step 10) {
            val v = EclipseFx.transitDepth01(20f, 100f, d.toFloat())
            assertTrue("深度随屏距单调不增（d=$d）", v <= prev + 1e-6f)
            prev = v
        }
        assertEquals(0f, prev, 0.0001f) // 终值归零（140=域外）
    }

    @Test
    fun `凌星在场门-同域判定剪影强度单源`() {
        // isTransiting 与 depth 同域（域外双口径同一式）
        assertTrue(EclipseFx.isTransiting(20f, 100f, 110f))
        assertFalse(EclipseFx.isTransiting(20f, 100f, 121f))
        assertTrue(EclipseFx.isTransiting(20f, 100f, 0f))
        assertFalse(EclipseFx.isTransiting(0f, 100f, 0f))
        assertFalse(EclipseFx.isTransiting(20f, 0f, 0f))
    }

    /**
     * 轨道倾角反例（r315 G① 红线：**有的系统永远不凌星=诚实**——零强制造食）。
     * 几何复证渲染管线同式：行星轨道点（ui planetOrbitPoint——渲染/判定单口）+
     * TasteSpace.project 投影，一整个公转周期采样最小行星-恒星屏距：
     * 高倾角（±34° 域上沿）系统的投影轨迹恒在恒星星面外——凌星深度全周期恒 0；
     * 低倾角系统必然扫过星面（深度 >0 出现）。
     */
    @Test
    fun `倾角反例-高倾角系统全周期不凌星且确定性`() {
        val w = 1080f
        val h = 600f
        val cx = w / 2f
        val cy = h / 2f
        val focal = TasteSpace.focalOf(w, h)
        fun minDistAndDepth(incRad: Float, starBodyR: Float, orbitR: Float): Pair<Float, Float> {
            // 相机在星正前 900 世界单位（贴脸档量级的井深比域）；星在世界原点
            val cam = TasteSpace.SpaceCam(0f, 0f, -900f, 0f, 0f)
            val proj = FloatArray(4)
            val orb = FloatArray(3)
            var minDist = Float.MAX_VALUE
            var maxDepth = 0f
            // 恒星视半径=投影口径（sizeK=focal/depth）
            val starProj = TasteSpace.project(0f, 0f, 0f, cam, focal, cx, cy, proj, null)
            assertTrue(starProj)
            val starPx = starBodyR * proj[3]
            val planetRpx = starPx * 0.20f // 行星:恒星 1:5（系数域上限——最容易凌的档）
            val p = TasteTagPlanets.TagPlanet(
                tag = "测", count = 10, orbitR01 = 0f, sizeR01 = 1f, phase01 = 0.25f,
                omegaRadPerSec = TasteTagPlanets.keplerOmegaOf(0.3f),
                itemIdx = IntArray(0), dustMinSec = 0L, dustMaxSec = 0L,
                ecc = 0.14f,
            )
            val period = 6.2831853f / p.omegaRadPerSec
            val n = 720
            for (k in 0..n) {
                val t = period * k / n
                val nu = TasteTagPlanets.keplerTheta(p, t)
                val rOrb = TasteTagPlanets.keplerOrbitRadius(p, orbitR, nu)
                planetOrbitPoint(0f, 0f, 0f, rOrb, nu + 1.2f, incRad, orb)
                val ok = TasteSpace.project(orb[0], orb[1], orb[2], cam, focal, cx, cy, proj, null)
                if (!ok) continue
                val d = hypot(proj[0] - cx, proj[1] - cy)
                if (d < minDist) minDist = d
                val depth = EclipseFx.transitDepth01(planetRpx, starPx, d)
                if (depth > maxDepth) maxDepth = depth
            }
            return minDist to maxDepth
        }
        // 星体量视半径 40 世界单位。反例几何：**外圈行星（a=2.9×恒星半径，
        // R247 系数域 orbitR01≈0.3 档）+最大倾角 ±34°**——投影最小屏距下界
        // = 近日点 2.49×恒星半径 × sin(34°)≈1.39×恒星视半径 > 星面+行星盘
        // （1.2×视半径）→ **永远不凌星**（几何保证的诚实反例——轨道倾角与轨道
        // 半径的既有量共同决定，不造食）
        val starBodyR = 40f
        val (hiDist, hiDepth) = minDistAndDepth(0.5934f, starBodyR, starBodyR * 2.9f)
        assertTrue("高倾角外圈最小屏距 > 0（在屏内可判域）", hiDist > 0f)
        assertEquals("高倾角外圈系统全周期零凌星深度", 0f, hiDepth, 0.0001f)
        // 确定性：同几何两次求值逐位一致
        val (d2, dep2) = minDistAndDepth(0.5934f, starBodyR, starBodyR * 2.9f)
        assertEquals(hiDist, d2, 0.0001f)
        assertEquals(hiDepth, dep2, 0.0001f)
        // 低倾角（≈0）内圈（a=1.9×）：行星投影轨迹穿过星面 → 深度 >0 出现
        // （同几何必然凌星——正反两例同式同管线）
        val (loDist, loDepth) = minDistAndDepth(0.02f, starBodyR, starBodyR * 1.9f)
        assertTrue("低倾角内圈最小屏距为小值（实=${"%.1f".format(loDist)}px）", loDist < 400f)
        assertTrue("低倾角必然出现凌星深度（实=${loDepth}）", loDepth > 0f)
    }

    // ---------------- R340 凌星前置判定（深度分量——黑圈回归根治） ----------------

    /**
     * R340 前置判定域锚：**背后行星（掩星——相机系深度大于恒星）永不凌星**（全
     * 屏距采样）；真前（深度更小）保留旧 [EclipseFx.isTransiting] 判定域（域外
     * 早退/内合双口径同一式不变）；深度相等=侧向伴行=非凌星（凌星=行星在恒星
     * 与相机之间）。旧 isTransiting 域零变化（Canvas 消费方 R345 退役前安全）。
     */
    @Test
    fun `凌星前置判定-背后行星永不凌星前行星保域`() {
        // 背后（掩星）：全屏距采样恒 false——纯屏距旧判定的黑圈病根被深度门根治
        for (d in intArrayOf(0, 20, 50, 80, 100, 110, 130)) {
            assertFalse(
                "背后行星（掩星）永不凌星（d=$d）",
                EclipseFx.isTransitingFront(20f, 100f, d.toFloat(), planetDepth = 950f, starDepth = 900f),
            )
            // 预扫食深同口径：背后行星不遮恒星
            assertEquals(
                "背后行星食深恒 0（d=$d）",
                0f,
                EclipseFx.transitDepthFront01(20f, 100f, d.toFloat(), planetDepth = 950f, starDepth = 900f),
                0.0001f,
            )
        }
        // 真前（凌星本义）：判定域与旧 isTransiting 逐位一致（前门零收窄）
        for (d in intArrayOf(0, 20, 50, 80, 100, 110, 121)) {
            assertEquals(
                "前行星判定=旧域（d=$d）",
                EclipseFx.isTransiting(20f, 100f, d.toFloat()),
                EclipseFx.isTransitingFront(20f, 100f, d.toFloat(), planetDepth = 850f, starDepth = 900f),
            )
            assertEquals(
                "前行星食深=旧深度（d=$d）",
                EclipseFx.transitDepth01(20f, 100f, d.toFloat()),
                EclipseFx.transitDepthFront01(20f, 100f, d.toFloat(), planetDepth = 850f, starDepth = 900f),
                0.0001f,
            )
        }
        // 侧向伴行（深度相等）：非凌星（不在恒星与相机之间）
        assertFalse(EclipseFx.isTransitingFront(20f, 100f, 0f, planetDepth = 900f, starDepth = 900f))
        // 防御：半径零域前置分量不翻案
        assertFalse(EclipseFx.isTransitingFront(0f, 100f, 0f, planetDepth = 1f, starDepth = 900f))
        assertFalse(EclipseFx.isTransitingFront(20f, 0f, 0f, planetDepth = 1f, starDepth = 900f))
    }

    /**
     * **R340 回归锚（管线级——用户 20:0x「行星变成围绕恒星的小黑圆圈」的病根
     * 复现+根治断言）**：渲染管线同式（planetOrbitPoint+TasteSpace.project），
     * 内圈行星+高倾角在**轨道远端**（恒星背后、投影近星心）——旧纯屏距判定
     * **判凌星**（=黑圈病：暗盘+亮环画在恒星上），前置判定判**非凌星**（走
     * 受光路径，GL 深度测试被恒星球真遮挡）；同几何轨道近端（恒星与相机之间）
     * 两口径**都判凌星**（金星凌日观感保真——R320 特性回归本义）。
     */
    @Test
    fun `R340回归锚-背后掩星不凌星真前凌星保真`() {
        val w = 1080f
        val h = 600f
        val cx = w / 2f
        val cy = h / 2f
        val focal = TasteSpace.focalOf(w, h)
        val cam = TasteSpace.SpaceCam(0f, 0f, -900f, 0f, 0f)
        val proj = FloatArray(4)
        val orb = FloatArray(3)
        val starBodyR = 40f
        // 恒星屏心+视半径+相机系深度（渲染管线同式——buildPlanetSystem 的判定原料）
        assertTrue(TasteSpace.project(0f, 0f, 0f, cam, focal, cx, cy, proj, null))
        val starSx = proj[0]
        val starSy = proj[1]
        val starDepth = proj[2]
        val starPx = starBodyR * proj[3]
        val planetRpx = starPx * 0.20f // 行星:恒星 1:5（系数域上限——最容易凌的档）
        fun judgeAtOrbitEnd(farSide: Boolean): Pair<Boolean, Boolean> {
            // 内圈轨道（a=1.9×恒星半径）+高倾角 ±34° 域：θ=90°（远端/背后）或
            // θ=270°（近端/恒星与相机之间）——投影均近星心（sin 分量小）
            val inc = 0.5934f
            val rOrb = starBodyR * 1.9f
            val theta = if (farSide) 1.5707964f else 4.712389f
            val st = kotlin.math.sin(theta)
            val ct = kotlin.math.cos(theta)
            orb[0] = 0f + ct * rOrb
            orb[1] = 0f - st * rOrb * kotlin.math.sin(inc)
            orb[2] = 0f + st * rOrb * kotlin.math.cos(inc)
            assertTrue(TasteSpace.project(orb[0], orb[1], orb[2], cam, focal, cx, cy, proj, null))
            val d = hypot(proj[0] - starSx, proj[1] - starSy)
            val oldWay = EclipseFx.isTransiting(planetRpx, starPx, d)
            val newWay = EclipseFx.isTransitingFront(planetRpx, starPx, d, proj[2], starDepth)
            return oldWay to newWay
        }
        // 远端（背后掩星）：旧判定 true=病根复现（纯屏距无深度）；前置判定 false=根治
        val (farOld, farNew) = judgeAtOrbitEnd(farSide = true)
        assertTrue("回归条件在案：旧纯屏距判定把背后行星判成凌星（实=$farOld）", farOld)
        assertFalse("R340 根治：背后掩星行星不再进剪影档", farNew)
        // 近端（真前）：两口径一致=真凌星保真
        val (nearOld, nearNew) = judgeAtOrbitEnd(farSide = false)
        assertTrue("真前行星凌星在场（旧口径）", nearOld)
        assertTrue("真前行星凌星保真（前置口径）", nearNew)
    }

    // ---------------- 星际消光（extinction——雾团漂移真实遮星） ----------------

    @Test
    fun `消光重合度-雾心一雾缘零域外早退`() {
        assertEquals(1f, EclipseFx.extinctionOverlap01(0f, 100f), 0.001f) // 雾心正对
        assertEquals(0.5f, EclipseFx.extinctionOverlap01(50f, 100f), 0.001f)
        assertEquals(0f, EclipseFx.extinctionOverlap01(100f, 100f), 0.001f) // 雾缘
        assertEquals(0f, EclipseFx.extinctionOverlap01(150f, 100f), 0.001f) // 域外
        assertEquals(0f, EclipseFx.extinctionOverlap01(50f, 0f), 0.001f) // 防御
    }

    @Test
    fun `消光变暗-线性且0_8帽域`() {
        // dim ∈[0, 0.2]——雾心正对 ×0.8 帽（r315 G 字面）；重合度线性
        assertEquals(0.20f, EclipseFx.extinctionDim01(1f), 0.0001f)
        assertEquals(0.10f, EclipseFx.extinctionDim01(0.5f), 0.0001f)
        assertEquals(0f, EclipseFx.extinctionDim01(0f), 0.0001f)
        assertEquals(0.20f, EclipseFx.extinctionDim01(9f), 0.0001f) // 超界入参防御钳域
        for (ov in floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f)) {
            assertTrue(EclipseFx.extinctionDim01(ov) <= 0.20f + 1e-6f)
        }
    }

    @Test
    fun `消光色温-负300K帽线性`() {
        assertEquals(-300f, EclipseFx.extinctionShiftK(1f), 0.001f)
        assertEquals(-150f, EclipseFx.extinctionShiftK(0.5f), 0.001f)
        assertEquals(0f, EclipseFx.extinctionShiftK(0f), 0.001f)
        assertEquals(-300f, EclipseFx.extinctionShiftK(7f), 0.001f) // 防御钳域
        for (ov in floatArrayOf(0.1f, 0.35f, 0.9f)) {
            assertTrue(EclipseFx.extinctionShiftK(ov) <= 0f)
            assertTrue(EclipseFx.extinctionShiftK(ov) >= -300f - 1e-3f)
        }
    }
}
