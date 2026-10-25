package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R235 飞船航迹引擎单测：流场矢量（平移反向+俯冲涨缩）/速度归一/尾长速度联动
 * （dive 速率）/等阶尾长系数/粒子门控与速率/波纹边沿触发+滞回/状态机确定性
 * /容量护栏。
 */
class TasteShipFxTest {

    private val style = TasteAchievements.ShipStyle.GOLD_PARTICLES

    @Test
    fun `流场-平移反向`() {
        // 相机右移（x+），z=0 星层左流（u<0）；相机上移（y+ 世界），星层 v>0（屏 y 向下）
        val out = FloatArray(3)
        val prev = TasteFlight.FlightCam(500f, 500f, 1f, 0f)
        val cur = TasteFlight.FlightCam(560f, 540f, 1f, 0f)
        TasteShipFx.flowOf(prev, cur, 1f, out)
        assertTrue(out[0] < 0f) // 星左移
        assertTrue(out[1] > 0f) // 世界 y+ = 屏 v+
        assertEquals(1f, out[2], 1e-4f) // 无缩放：涨缩比恒 1
    }

    @Test
    fun `流场-俯冲径向涨缩`() {
        val out = FloatArray(3)
        val prev = TasteFlight.FlightCam(500f, 500f, 1f, 0f, dive = 0f)
        val cur = TasteFlight.FlightCam(500f, 500f, 1f, 0f, dive = 200f) // 前飞 200
        TasteShipFx.flowOf(prev, cur, 1f, out)
        // dist_eff 收缩 → k 增大 → s>1（星野向外涌）
        assertTrue(out[2] > 1f)
        assertEquals(0f, out[0], 1e-4f)
        assertEquals(0f, out[1], 1e-4f)
    }

    @Test
    fun `速度-dive 速率联动`() {
        // dive 500 世界单位 / 0.25s = 2000 u/s——「速度越快」由 dive 贡献
        val prev = TasteFlight.FlightCam(500f, 500f, 1f, 0f, 0f)
        val cur = TasteFlight.FlightCam(500f, 500f, 1f, 0f, 500f)
        assertEquals(2000f, TasteShipFx.speedOf(prev, cur, 0.25f), 1f)
        // 平移同样计速
        val pan = TasteShipFx.speedOf(
            TasteFlight.FlightCam(500f, 500f, 1f, 0f),
            TasteFlight.FlightCam(600f, 500f, 1f, 0f), 0.5f,
        )
        assertEquals(200f, pan, 1f)
        // dt=0 防御
        assertEquals(0f, TasteShipFx.speedOf(prev, cur, 0f), 1e-6f)
    }

    @Test
    fun `速度-归一钳位`() {
        assertEquals(0f, TasteShipFx.speed01Of(0f), 1e-6f)
        assertEquals(1f, TasteShipFx.speed01Of(99_999f), 1e-6f)
        assertEquals(0.5f, TasteShipFx.speed01Of(TasteShipFx.SPEED_CAP / 2f), 1e-4f)
    }

    @Test
    fun `尾长-速度联动与静止隐形`() {
        val minSide = 1000f
        // 静止（speed01≤FADE_FLOOR）：零尾迹
        assertEquals(0f, TasteShipFx.streakLenOf(style, 0f, minSide), 1e-6f)
        // 满速>半速（同船）：
        val full = TasteShipFx.streakLenOf(style, 1f, minSide)
        val half = TasteShipFx.streakLenOf(style, 0.5f, minSide)
        assertTrue(full > half)
        assertTrue(full > 0f)
        // 等阶越酷尾越长（同速度）
        val bronze = TasteShipFx.streakLenOf(TasteAchievements.ShipStyle.MICRO_TRAIL, 1f, minSide)
        val legend = TasteShipFx.streakLenOf(TasteAchievements.ShipStyle.RAINBOW_STORM, 1f, minSide)
        assertTrue(legend > bronze)
    }

    @Test
    fun `透明度-静止零尾迹与等阶档`() {
        assertEquals(0f, TasteShipFx.alphaOf(TasteAchievements.ShipStyle.RAINBOW_STORM, 0f), 1e-6f)
        assertEquals(0f, TasteShipFx.alphaOf(TasteAchievements.ShipStyle.RAINBOW_STORM, 0.03f), 1e-6f)
        assertTrue(TasteShipFx.alphaOf(style, 1f) > TasteShipFx.alphaOf(TasteAchievements.ShipStyle.MICRO_TRAIL, 1f))
    }

    @Test
    fun `粒子-门控与速率`() {
        // 青铜/白银无粒子；黄金/传奇有
        assertFalse(TasteShipFx.hasParticlesOf(TasteAchievements.ShipStyle.MICRO_TRAIL))
        assertFalse(TasteShipFx.hasParticlesOf(TasteAchievements.ShipStyle.BLUE_PULSE))
        assertTrue(TasteShipFx.hasParticlesOf(TasteAchievements.ShipStyle.GOLD_PARTICLES))
        assertTrue(TasteShipFx.hasParticlesOf(TasteAchievements.ShipStyle.RAINBOW_STORM))
        // 静止零速率；速率 ∝ speed01²
        assertEquals(0f, TasteShipFx.particleRateOf(style, 0f), 1e-6f)
        assertEquals(0f, TasteShipFx.particleRateOf(style, 0.05f), 1e-6f)
        val half = TasteShipFx.particleRateOf(style, 0.5f)
        val full = TasteShipFx.particleRateOf(style, 1f)
        assertEquals(full / 4f, half, 0.5f)
        // 青铜永不产粒子
        assertEquals(0f, TasteShipFx.particleRateOf(TasteAchievements.ShipStyle.MICRO_TRAIL, 1f), 1e-6f)
    }

    @Test
    fun `波纹-边沿触发与滞回`() {
        val e = TasteShipFx.ShipTrailEngine()
        val cam = TasteFlight.FlightCam(500f, 500f, 1f, 0f, 0f)
        // 首帧基准
        e.step(cam, 1f, 0.016f, TasteAchievements.ShipStyle.WARP_RIPPLE, 1080f)
        assertEquals(0, e.rCount)
        // 猛冲过阈（一帧位移足够大→speed01 过 0.55）
        val far = cam.copy(x = 500f + 800f)
        e.step(far, 1f, 0.016f, TasteAchievements.ShipStyle.WARP_RIPPLE, 1080f)
        assertEquals(1, e.rCount) // 边沿触发恰好一环
        // 保持高速：不再触发（armed=false）
        val far2 = far.copy(x = far.x + 800f)
        e.step(far2, 1f, 0.016f, TasteAchievements.ShipStyle.WARP_RIPPLE, 1080f)
        assertEquals(1, e.rCount)
        // 静止若干帧：平滑速度衰减过 0.40 → 再武装
        for (i in 0 until 12) {
            e.step(far2, 1f, 0.033f, TasteAchievements.ShipStyle.WARP_RIPPLE, 1080f)
        }
        assertTrue(e.speed01 < TasteShipFx.RIPPLE_REARM_01)
        // 再冲：平滑速度爬坡（0.35→0.58→…）过阈=第二环（指数平滑=触发要两三帧加速——
        // 与「运镜加速」的手感一致）
        var x = far2.x
        for (i in 0 until 3) {
            x += 800f
            e.step(TasteFlight.FlightCam(x, 500f, 1f, 0f), 1f, 0.016f, TasteAchievements.ShipStyle.WARP_RIPPLE, 1080f)
        }
        assertEquals(2, e.rCount)
    }

    @Test
    fun `波纹-无波纹等阶不触发`() {
        val e = TasteShipFx.ShipTrailEngine()
        val cam = TasteFlight.FlightCam(500f, 500f, 1f, 0f, 0f)
        e.step(cam, 1f, 0.016f, TasteAchievements.ShipStyle.GOLD_PARTICLES, 1080f)
        e.step(cam.copy(x = 1300f), 1f, 0.016f, TasteAchievements.ShipStyle.GOLD_PARTICLES, 1080f)
        assertEquals(0, e.rCount)
    }

    @Test
    fun `状态机-速度平滑与确定性`() {
        fun run(): TasteShipFx.ShipTrailEngine {
            val e = TasteShipFx.ShipTrailEngine()
            var x = 500f
            e.step(TasteFlight.FlightCam(x, 500f, 1f, 0f), 1f, 0.016f, style, 1080f)
            for (i in 0 until 20) {
                x += 40f
                e.step(TasteFlight.FlightCam(x, 500f, 1f, 0f), 1f, 0.016f, style, 1080f)
            }
            return e
        }
        val a = run()
        val b = run()
        assertEquals(a.speed01, b.speed01, 1e-6f)
        assertEquals(a.pCount, b.pCount)
        // 同 seed 同粒子（确定性：屏心相对坐标逐一相等）
        for (i in 0 until a.pCount) {
            assertEquals(a.pDx[i], b.pDx[i], 1e-5f)
            assertEquals(a.pSeed[i], b.pSeed[i])
        }
        assertTrue(a.speed01 > 0f)
    }

    @Test
    fun `状态机-静止收敛零尾迹`() {
        val e = TasteShipFx.ShipTrailEngine()
        val cam = TasteFlight.FlightCam(500f, 500f, 1f, 0f)
        e.step(cam, 1f, 0.016f, style, 1080f)
        // 先冲起来
        e.step(cam.copy(x = 900f), 1f, 0.016f, style, 1080f)
        val peak = e.speed01
        assertTrue(peak > 0f)
        // 静止 3s：平滑速度衰减到 0（尾迹消失）
        for (i in 0 until 200) {
            e.step(cam, 1f, 0.016f, style, 1080f)
        }
        assertEquals(0f, e.speed01, 1e-3f)
    }

    @Test
    fun `状态机-粒子容量护栏`() {
        val e = TasteShipFx.ShipTrailEngine()
        val cam = TasteFlight.FlightCam(500f, 500f, 1f, 0f)
        e.step(cam, 1f, 0.016f, TasteAchievements.ShipStyle.RAINBOW_STORM, 1080f)
        // 持续满速飞 10s：活粒子数永不超容量
        var x = 500f
        for (i in 0 until 600) {
            x += 60f
            e.step(TasteFlight.FlightCam(x, 500f, 1f, 0f), 1f, 0.0167f, TasteAchievements.ShipStyle.RAINBOW_STORM, 1080f)
            assertTrue(e.pCount <= TasteShipFx.PARTICLE_CAP)
        }
        assertTrue(e.pCount > 0)
        // 粒子寿命有界（全死光后可清零）
        for (i in 0 until 200) {
            e.step(TasteFlight.FlightCam(x, 500f, 1f, 0f), 1f, 0.0167f, TasteAchievements.ShipStyle.RAINBOW_STORM, 1080f)
        }
        assertEquals(0, e.pCount)
    }

    // ==================== R242 空间相机口径（stepSpace） ====================

    @Test
    fun `空间状态机-前飞速度径向外涌`() {
        val e = TasteShipFx.ShipTrailEngine()
        val cam0 = TasteSpace.SpaceCam(0f, 0f, 0f, 0f, 0f)
        e.stepSpace(cam0, 1000f, 0.016f, style, 1080f) // 基准帧
        // 前飞 400 单位/帧（≈24000/s 超归一上限）→ speed01 拉满 + 径向率 > 0
        e.stepSpace(TasteSpace.SpaceCam(0f, 0f, 400f, 0f, 0f), 1000f, 0.016f, style, 1080f)
        assertTrue(e.speed01 > 0.5f)
        assertTrue("前飞应径向外涌（rate=${e.flowRateS}）", e.flowRateS > 0f)
        // 静止收敛：不动 3s → 零尾迹
        for (i in 0 until 200) {
            e.stepSpace(TasteSpace.SpaceCam(0f, 0f, 400f, 0f, 0f), 1000f, 0.016f, style, 1080f)
        }
        assertEquals(0f, e.speed01, 1e-3f)
    }

    @Test
    fun `空间状态机-转头平移流与零速度`() {
        val e = TasteShipFx.ShipTrailEngine()
        val cam0 = TasteSpace.SpaceCam(0f, 0f, 0f, 0f, 0f)
        e.stepSpace(cam0, 1000f, 0.016f, style, 1080f)
        // 原地右转 30°/帧：星野左移（flowVelU<0）且速度=0（没飞就不算飞——诚实口径）
        e.stepSpace(TasteSpace.SpaceCam(0f, 0f, 0f, 30f, 0f), 1000f, 0.016f, style, 1080f)
        assertTrue("右转星应左移（U=${e.flowVelU}）", e.flowVelU < 0f)
        assertEquals(0f, e.speed01, 1e-3f)
    }
}
