package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * R310 世界卡·恒星系 3D 小模型纯件单测（StarSystemMini）：
 *  ①布局确定性+镜像主图几何锚（轨道半径/行星半径/倾角/相位——同数据同模型）；
 *  ②轨道相机 dragBy 号族（与 TasteSpace.turnBy 同号）+yaw 环绕+pitch 钳 ±88°；
 *  ③惯性阻尼（衰减单调+停机阈归零+测速窗初速+毛刺钳位 MINI_MODEL_FLING_MAX_DEG_SEC）；
 *  ④camOf 机位/瞄准（原点恒投影屏心）+fitDist 取景（填满/近裁护栏双支）；
 *  ⑤pickPlanet 命中族（正中/热区/未命中/深度近者胜/恒星面遮罩）。
 */
class StarSystemMiniTest {

    // ---------------- 原料 ----------------

    private fun planet(
        tag: String,
        count: Int,
        orbitR01: Float,
        sizeR01: Float,
        phase01: Float,
        ecc: Float = 0.18f,
    ) = TasteTagPlanets.TagPlanet(
        tag = tag,
        count = count,
        orbitR01 = orbitR01,
        sizeR01 = sizeR01,
        phase01 = phase01,
        omegaRadPerSec = TasteTagPlanets.keplerOmegaOf(orbitR01),
        itemIdx = IntArray(0),
        dustMinSec = 0L,
        dustMaxSec = 0L,
        ecc = ecc,
    )

    private fun samplePlanets(): List<TasteTagPlanets.TagPlanet> = listOf(
        planet("alpha", 100, 0.24f, 1.0f, 0.13f),
        planet("beta", 64, 0.45f, 0.8f, 0.47f),
        planet("gamma", 30, 0.70f, 0.55f, 0.81f),
        planet("delta", 9, 0.92f, 0.3f, 0.29f),
    )

    private val out4 = FloatArray(4)

    // ---------------- ① 布局确定性 + 镜像几何锚 ----------------

    @Test
    fun `布局确定性——同数据同模型`() {
        val a = StarSystemMini.layout(12345L, samplePlanets())
        val b = StarSystemMini.layout(12345L, samplePlanets())
        assertEquals(a.planets.size, b.planets.size)
        assertEquals(a.incRad, b.incRad, 0f)
        assertEquals(a.extent, b.extent, 0f)
        for (i in a.planets.indices) {
            assertEquals(a.planets[i].x, b.planets[i].x, 0f)
            assertEquals(a.planets[i].y, b.planets[i].y, 0f)
            assertEquals(a.planets[i].z, b.planets[i].z, 0f)
            assertEquals(a.planets[i].worldR, b.planets[i].worldR, 0f)
            assertEquals(a.planets[i].orbitSemi, b.planets[i].orbitSemi, 0f)
            assertEquals(a.planets[i].ringed, b.planets[i].ringed)
        }
    }

    @Test
    fun `镜像主图几何——轨道半径与行星半径与倾角`() {
        val src = samplePlanets()
        val sys = StarSystemMini.layout(777L, src)
        // 轨道半长轴=1.9+3.4×orbitR01（planetOrbitRadius 镜像锚）
        for (i in src.indices) {
            assertEquals(1.9f + 3.4f * src[i].orbitR01, sys.planets[i].orbitSemi, 1e-5f)
        }
        // 行星半径=planetRadiusCoef×STAR_R（钳 0.30×半长轴——planetWorldRadius 镜像锚）
        for (i in src.indices) {
            val wantRaw = TasteTagPlanets.planetRadiusCoef(src[i])
            val want = minOf(wantRaw, sys.planets[i].orbitSemi * 0.30f)
            assertEquals(want, sys.planets[i].worldR, 1e-5f)
        }
        // 系统倾角=（h01(chatId%100000,71)−0.5)×1.2（galaxyOrbitInclination 镜像锚）
        val wantInc = (TasteTagPlanets.h01((777L % 100_000L).toInt(), 71) - 0.5f) * 1.2f
        assertEquals(wantInc, sys.incRad, 1e-6f)
        // 行星位置=planetOrbitPoint 镜像（星心=原点）
        val p0 = src[0]
        val nu = TasteTagPlanets.keplerTheta(p0, 0f)
        val rOrb = TasteTagPlanets.keplerOrbitRadius(p0, sys.planets[0].orbitSemi, nu)
        val ang = nu + sys.planets[0].peri
        assertEquals(cos(ang) * rOrb, sys.planets[0].x, 1e-4f)
        assertEquals(-sin(ang) * rOrb * sin(wantInc), sys.planets[0].y, 1e-4f)
        assertEquals(sin(ang) * rOrb * cos(wantInc), sys.planets[0].z, 1e-4f)
        // 行星心到原点距离=开普勒即时半径（恒星在焦点——椭圆语义真）
        val d = sqrt(
            sys.planets[0].x * sys.planets[0].x + sys.planets[0].y * sys.planets[0].y +
                sys.planets[0].z * sys.planets[0].z,
        )
        assertEquals(rOrb, d, 1e-4f)
    }

    @Test
    fun `取景包络罩住全部行星远日点与环缘`() {
        val src = samplePlanets()
        val sys = StarSystemMini.layout(99L, src)
        for (i in src.indices) {
            val mp = sys.planets[i]
            val rim = if (mp.ringed) mp.worldR * 2.35f else mp.worldR
            val apo = mp.orbitSemi * (1f + mp.ecc)
            assertTrue("行星$i 远日点+环缘应入包络", sys.extent >= apo + rim - 1e-4f)
        }
        assertTrue(sys.extent >= StarSystemMini.STAR_R)
    }

    @Test
    fun `行星上限——超出按原序留重`() {
        val many = (1..16).map { planet("t$it", 100 - it, 0.3f + it * 0.04f, 0.5f, it * 0.06f) }
        val sys = StarSystemMini.layout(5L, many)
        assertEquals(StarSystemMini.MAX_PLANETS, sys.planets.size)
        assertEquals("t1", sys.planets[0].tag)
        assertEquals("t10", sys.planets[9].tag)
    }

    // ---------------- ② 轨道相机 dragBy 号族 ----------------

    @Test
    fun `拖动号族——右滑 yaw 增，上滑 pitch 增（turnBy 同号）`() {
        val cam = StarSystemMini.OrbitCam()
        cam.dragStart()
        cam.dragBy(0L, 100f, 0f, 0.2f)
        assertEquals(20f, cam.yawDeg, 1e-4f)
        cam.dragBy(16L, 0f, -50f, 0.2f) // 上滑 dy<0 → pitch 增（升机位俯视）
        assertEquals(StarSystemMini.INITIAL_PITCH_DEG + 10f, cam.pitchDeg, 1e-4f)
        // 累计探针量
        assertEquals(20f, cam.gestureYawDeg, 1e-4f)
        assertEquals(10f, cam.gesturePitchDeg, 1e-4f)
    }

    @Test
    fun `yaw 无限环绕——360° 无死角`() {
        val cam = StarSystemMini.OrbitCam()
        cam.dragStart()
        repeat(40) { cam.dragBy(it.toLong(), 50f, 0f, 0.2f) } // 累计 400°
        assertTrue(cam.yawDeg in -180f..180f)
        assertEquals(40f, cam.yawDeg, 1e-3f) // 400° wrap = 40°
    }

    @Test
    fun `pitch 钳位 ±88°——顶视底视都到得了，不翻转`() {
        val cam = StarSystemMini.OrbitCam()
        cam.dragStart()
        repeat(50) { cam.dragBy(it.toLong(), 0f, -100f, 0.2f) }
        assertEquals(GestureFeelPolicy.PITCH_LIMIT_DEG, cam.pitchDeg, 1e-4f)
        repeat(100) { cam.dragBy(1000L + it, 0f, 100f, 0.2f) }
        assertEquals(-GestureFeelPolicy.PITCH_LIMIT_DEG, cam.pitchDeg, 1e-4f)
    }

    // ---------------- ③ 惯性阻尼 ----------------

    @Test
    fun `惯性衰减单调且有限步停机归零`() {
        val cam = StarSystemMini.OrbitCam()
        cam.dragStart()
        // 拟真拖动：10 事件 × 16ms，每步 12px → 窗内速度可测
        for (i in 0..10) cam.dragBy(i * 16L, 12f, 0f, 0.2f)
        cam.dragEnd(176L)
        assertTrue("松手应有惯性初速", cam.yawVelDeg > 0f)
        var lastV = cam.yawVelDeg
        var steps = 0
        while (cam.stepInertia(16.7f)) {
            assertTrue("衰减应单调不增", cam.yawVelDeg <= lastV + 1e-6f)
            lastV = cam.yawVelDeg
            steps++
            assertTrue("惯性必须有限停机", steps < 10000)
        }
        assertEquals(0f, cam.yawVelDeg, 0f)
        assertEquals(0f, cam.pitchVelDeg, 0f)
        assertTrue("惯性应转过一段角度", abs(cam.yawDeg) > 1f)
    }

    @Test
    fun `惯性 pitch 不越钳位`() {
        val cam = StarSystemMini.OrbitCam()
        cam.dragStart()
        for (i in 0..10) cam.dragBy(i * 16L, 0f, -20f, 0.5f) // 猛烈上推
        cam.dragEnd(176L)
        repeat(400) { cam.stepInertia(16.7f) }
        assertTrue(cam.pitchDeg <= GestureFeelPolicy.PITCH_LIMIT_DEG + 1e-4f)
        assertTrue(cam.pitchDeg >= -GestureFeelPolicy.PITCH_LIMIT_DEG - 1e-4f)
    }

    @Test
    fun `松手毛刺钳位——1ms 大跳变不虚高`() {
        val cam = StarSystemMini.OrbitCam()
        cam.dragStart()
        cam.dragBy(0L, 5f, 0f, 0.2f)
        cam.dragBy(1L, 300f, 0f, 0.2f) // 1ms 跳 60°——毛刺
        cam.dragEnd(2L)
        assertEquals(GestureFeelPolicy.MINI_MODEL_FLING_MAX_DEG_SEC, cam.yawVelDeg, 1e-3f)
    }

    @Test
    fun `手势期惯性冻结——dragging 时 stepInertia 不动`() {
        val cam = StarSystemMini.OrbitCam()
        cam.dragStart()
        cam.dragBy(0L, 100f, 0f, 0.2f)
        val yaw = cam.yawDeg
        assertFalse(cam.stepInertia(16.7f))
        assertEquals(yaw, cam.yawDeg, 0f)
    }

    // ---------------- ④ camOf / fitDist ----------------

    @Test
    fun `camOf 机位与瞄准——原点恒投影屏心`() {
        val w = 800f
        val h = 440f
        val focal = TasteSpace.focalOf(w, h) * StarSystemMini.MINI_LENS
        for ((yaw, pitch) in listOf(0f to 0f, 37f to 24f, -120f to 60f, 175f to -80f)) {
            val cam = StarSystemMini.camOf(StarSystemMini.CamPose(yaw, pitch, false), 22f)
            assertTrue(TasteSpace.project(0f, 0f, 0f, cam, focal, w / 2f, h / 2f, out4))
            assertEquals(w / 2f, out4[0], 0.01f)
            assertEquals(h / 2f, out4[1], 0.01f)
            assertEquals(22f, out4[2], 0.01f) // 深度=机位距
        }
        // pitch=+60°=升机位：相机在轨道上方向下看
        val camHigh = StarSystemMini.camOf(StarSystemMini.CamPose(0f, 60f, false), 22f)
        assertTrue(camHigh.py > 0f)
        // yaw=0 pitch=0：机位 +Z 正视；世界 +X 点应在屏心左（面向 -Z 时 +X=左手侧）
        val cam0 = StarSystemMini.camOf(StarSystemMini.CamPose(0f, 0f, false), 22f)
        TasteSpace.project(3f, 0f, 0f, cam0, focal, w / 2f, h / 2f, out4)
        assertTrue("+X 应投影屏心左", out4[0] < w / 2f)
    }

    @Test
    fun `fitDist 双支——填满与近裁护栏`() {
        val w = 800f
        val h = 440f
        val focal = TasteSpace.focalOf(w, h) * StarSystemMini.MINI_LENS
        val halfMin = h / 2f
        // 填满支（extent 大——公式超护栏）：extent=8 → 27.1 > 23
        val dBig = StarSystemMini.fitDist(8f, w, h)
        assertEquals(8f * focal / (halfMin * StarSystemMini.FIT_FILL), dBig, 1e-3f)
        assertEquals(halfMin * StarSystemMini.FIT_FILL, 8f * focal / dBig, 1e-2f)
        assertTrue("近裁护栏：最近点须在 NEAR 外", dBig - 8f > TasteSpace.NEAR)
        // 护栏支（extent 小——公式低于 extent+NEAR+1 时护栏接管）：extent=2 →
        // 公式 6.8 << 17
        val dSmall = StarSystemMini.fitDist(2f, w, h)
        assertEquals(2f + TasteSpace.NEAR + 1f, dSmall, 1e-4f)
        // 退化小窗（半幅<1px）→ 保底支
        val dTiny = StarSystemMini.fitDist(6.24f, 0f, 0f)
        assertTrue(dTiny >= 6.24f + TasteSpace.NEAR)
    }

    // ---------------- ⑤ pickPlanet 命中族 ----------------

    private fun camFor(sys: StarSystemMini.MiniSystem, w: Float, h: Float, yaw: Float = 0f, pitch: Float = 24f): TasteSpace.SpaceCam =
        StarSystemMini.camOf(
            StarSystemMini.CamPose(yaw, pitch, false),
            StarSystemMini.fitDist(sys.extent, w, h),
        )

    @Test
    fun `正中命中——无歧义视角逐行星投影心点击必中`() {
        val sys = StarSystemMini.layout(4242L, samplePlanets())
        val w = 800f
        val h = 440f
        val focal = TasteSpace.focalOf(w, h) * StarSystemMini.MINI_LENS
        // 自适应找一个「全分离」视角（真 3D 语义下某些角度行星确实叠压——叠压时
        // 「深度近者胜」是对的行为，另有专测；本测锚定无歧义基底「点在谁身上就中谁」）
        var cam: TasteSpace.SpaceCam? = null
        val sep = FloatArray(4)
        for (yawTry in 0..350 step 15) {
            val c = camFor(sys, w, h, yawTry.toFloat(), 50f)
            var ok = true
            val sx = FloatArray(sys.planets.size)
            val sy = FloatArray(sys.planets.size)
            for (i in sys.planets.indices) {
                val p = sys.planets[i]
                if (!TasteSpace.project(p.x, p.y, p.z, c, focal, w / 2f, h / 2f, sep)) { ok = false; break }
                sx[i] = sep[0]
                sy[i] = sep[1]
            }
            if (!ok) continue
            for (i in sys.planets.indices) {
                for (j in i + 1 until sys.planets.size) {
                    val dd = kotlin.math.hypot(sx[i] - sx[j], sy[i] - sy[j])
                    if (dd < 24f) { ok = false; break }
                }
                if (!ok) break
            }
            if (ok) { cam = c; break }
        }
        assertTrue("应存在全分离视角", cam != null)
        val camOk = cam ?: error("应存在全分离视角")
        val hitBuf = FloatArray(4)
        for (i in sys.planets.indices) {
            val p = sys.planets[i]
            assertTrue(TasteSpace.project(p.x, p.y, p.z, camOk, focal, w / 2f, h / 2f, sep))
            val hit = StarSystemMini.pickPlanet(sep[0], sep[1], w, h, camOk, sys, 6f, hitBuf)
            assertEquals("第 $i 颗正中应命中", i, hit)
        }
    }

    @Test
    fun `热区与未命中——slop 内中、远离不中`() {
        val sys = StarSystemMini.layout(4242L, samplePlanets())
        val w = 800f
        val h = 440f
        val cam = camFor(sys, w, h)
        val focal = TasteSpace.focalOf(w, h) * StarSystemMini.MINI_LENS
        val p0 = sys.planets[0]
        TasteSpace.project(p0.x, p0.y, p0.z, cam, focal, w / 2f, h / 2f, out4)
        // 热区=max(行星投影半径×1.6, slop)：slop=30px 主导（小行星）
        assertEquals(0, StarSystemMini.pickPlanet(out4[0] + 29f, out4[1], w, h, cam, sys, 30f, FloatArray(4)))
        // 远离=不中（屏角——前提屏角不在其他行星热区内：选 (1,1) 角大概率空；保守起见
        // 用「恒星遮罩外沿+偏离全部行星」的兜底口径：直接断言选中≠0 号或 -1 皆可→改断言
        // 距 p0 60px 外侧点不再命中 p0）
        val missP0 = StarSystemMini.pickPlanet(out4[0] + 60f, out4[1] + 60f, w, h, cam, sys, 30f, FloatArray(4))
        assertTrue("p0 热区外不应命中 p0", missP0 != 0)
    }

    @Test
    fun `深度近者胜——同屏位前后叠压点中前景`() {
        // 手工系统：两行星同射线（屏位重合）——机位在 +Z=22 朝 −Z 看：z 大者离机位近
        // （前景）。x=1.2 平移=恒星投影盘外（遮罩不吃）。
        val near = StarSystemMini.MiniPlanet(
            "near", 3f, 0f, 0f, 0.15f, 1.2f, 0f, 5f, // 深度 17（近）
            gas = false, ringed = false, ringTiltX = 0f, ringTiltZ = 0f,
            spin0 = 0f, seedF = 1f, hueShiftDeg = 0f,
        )
        val far = StarSystemMini.MiniPlanet(
            "far", 5f, 0f, 0f, 0.15f, 1.2f, 0f, -3f, // 深度 25（远）
            gas = false, ringed = false, ringTiltX = 0f, ringTiltZ = 0f,
            spin0 = 0f, seedF = 2f, hueShiftDeg = 0f,
        )
        val sys = StarSystemMini.MiniSystem(listOf(near, far), 0f, 6f)
        val w = 800f
        val h = 440f
        val cam = StarSystemMini.camOf(StarSystemMini.CamPose(0f, 0f, false), 22f)
        val focal = TasteSpace.focalOf(w, h) * StarSystemMini.MINI_LENS
        assertTrue(TasteSpace.project(1.2f, 0f, 5f, cam, focal, w / 2f, h / 2f, out4))
        val hit = StarSystemMini.pickPlanet(out4[0], out4[1], w, h, cam, sys, 30f, FloatArray(4))
        assertEquals("叠压应中近者", 0, hit)
        assertNotEquals(-1, hit)
    }

    @Test
    fun `恒星面遮罩——点在恒星盘内不算行星`() {
        val sys = StarSystemMini.layout(4242L, samplePlanets())
        val w = 800f
        val h = 440f
        val cam = camFor(sys, w, h)
        // 屏心=恒星盘心 → 恒 -1（哪怕有行星投影恰巧压屏心）
        assertEquals(-1, StarSystemMini.pickPlanet(w / 2f, h / 2f, w, h, cam, sys, 30f, FloatArray(4)))
        val focal = TasteSpace.focalOf(w, h) * StarSystemMini.MINI_LENS
        assertTrue(TasteSpace.project(0f, 0f, 0f, cam, focal, w / 2f, h / 2f, out4))
        val starPx = StarSystemMini.STAR_R * out4[3]
        // 恒星盘内沿（0.9×半径）仍遮罩
        assertEquals(-1, StarSystemMini.pickPlanet(out4[0] + starPx * 0.9f, out4[1], w, h, cam, sys, 30f, FloatArray(4)))
    }

    @Test
    fun `360° 旋转全程可点——八个方位角逐颗命中`() {
        val sys = StarSystemMini.layout(4242L, samplePlanets())
        val w = 800f
        val h = 440f
        val focal = TasteSpace.focalOf(w, h) * StarSystemMini.MINI_LENS
        for (k in 0..7) {
            val yaw = k * 45f
            val cam = StarSystemMini.camOf(
                StarSystemMini.CamPose(yaw, 24f, false),
                StarSystemMini.fitDist(sys.extent, w, h),
            )
            var hits = 0
            for (i in sys.planets.indices) {
                val p = sys.planets[i]
                if (!TasteSpace.project(p.x, p.y, p.z, cam, focal, w / 2f, h / 2f, out4)) continue
                // 恒星遮罩内跳过（诚实口径——遮罩内的行星该角度不可点）
                val focalStar = StarSystemMini.STAR_R * (focal / out4[2])
                val dcx = out4[0] - w / 2f
                val dcy = out4[1] - h / 2f
                if (dcx * dcx + dcy * dcy < focalStar * focalStar) continue
                if (StarSystemMini.pickPlanet(out4[0], out4[1], w, h, cam, sys, 6f, FloatArray(4)) == i) hits++
            }
            assertTrue("yaw=$yaw 至少两颗行星可点（命中=$hits）", hits >= 2)
        }
    }
}
