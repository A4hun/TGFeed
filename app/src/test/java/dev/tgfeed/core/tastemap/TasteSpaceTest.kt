package dev.tgfeed.core.tastemap

import dev.tgfeed.core.tastemap.TasteSpace.SpaceCam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * R242 沉浸式宇宙相机核心单测（任务书四向锚定：方向/投影/移动/点选）+
 * 球面化分布（三带壳层/全天球覆盖/确定性）+ 运镜收敛 + 距离衰减 + 光流。
 */
class TasteSpaceTest {

    private val cam0 = SpaceCam.ORIGIN
    private val out = FloatArray(4)
    private val out2 = FloatArray(4)
    private val dir = FloatArray(3)
    private val basis = FloatArray(9)

    // ==================== 方向 ====================

    @Test
    fun `方向-yaw0朝Z_yaw90朝X_负90朝负X`() {
        TasteSpace.lookDirection(0f, 0f, dir)
        assertEquals(0f, dir[0], 1e-4f); assertEquals(0f, dir[1], 1e-4f); assertEquals(1f, dir[2], 1e-4f)
        TasteSpace.lookDirection(90f, 0f, dir)
        assertEquals(1f, dir[0], 1e-4f); assertEquals(0f, dir[1], 1e-4f); assertEquals(0f, dir[2], 1e-4f)
        TasteSpace.lookDirection(-90f, 0f, dir)
        assertEquals(-1f, dir[0], 1e-4f); assertEquals(0f, dir[1], 1e-4f); assertEquals(0f, dir[2], 1e-4f)
    }

    @Test
    fun `方向-抬头朝Y低头朝负Y`() {
        TasteSpace.lookDirection(0f, 90f, dir)
        assertEquals(0f, dir[0], 1e-4f); assertEquals(1f, dir[1], 1e-4f); assertEquals(0f, dir[2], 1e-4f)
        TasteSpace.lookDirection(0f, -90f, dir)
        assertEquals(0f, dir[0], 1e-4f); assertEquals(-1f, dir[1], 1e-4f); assertEquals(0f, dir[2], 1e-4f)
    }

    @Test
    fun `方向-任意角都是单位向量`() {
        for (a in intArrayOf(0, 17, 45, 90, 133, 180, 225, 270, 359, -45, -170)) {
            for (p in intArrayOf(-88, -30, 0, 30, 88)) {
                TasteSpace.lookDirection(a.toFloat(), p.toFloat(), dir)
                assertEquals(1f, sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]), 1e-3f)
            }
        }
    }

    @Test
    fun `方向-正交基两两垂直且与视线一致`() {
        for (a in intArrayOf(-160, -45, 0, 30, 90, 179)) {
            for (p in intArrayOf(-60, 0, 45)) {
                TasteSpace.basis(a.toFloat(), p.toFloat(), basis)
                TasteSpace.lookDirection(a.toFloat(), p.toFloat(), dir)
                // f 与 lookDirection 一致
                assertEquals(dir[0], basis[0], 1e-4f)
                assertEquals(dir[1], basis[1], 1e-4f)
                assertEquals(dir[2], basis[2], 1e-4f)
                fun dot(i: Int, j: Int) = basis[i] * basis[j] + basis[i + 1] * basis[j + 1] + basis[i + 2] * basis[j + 2]
                assertEquals(0f, dot(0, 3), 1e-4f) // f·r
                assertEquals(0f, dot(0, 6), 1e-4f) // f·u
                assertEquals(0f, dot(3, 6), 1e-4f) // r·u
                assertEquals(1f, sqrt(basis[0] * basis[0] + basis[1] * basis[1] + basis[2] * basis[2]), 1e-3f)
                assertEquals(1f, sqrt(basis[3] * basis[3] + basis[4] * basis[4] + basis[5] * basis[5]), 1e-3f)
                assertEquals(1f, sqrt(basis[6] * basis[6] + basis[7] * basis[7] + basis[8] * basis[8]), 1e-3f)
            }
        }
    }

    @Test
    fun `方向-上向量在抬头时向前倾倒（抬头看天顶时上向变水平）`() {
        TasteSpace.basis(0f, 88f, basis)
        assertEquals(0f, basis[7], 0.05f) // u_y = cos(88°) ≈ 0
        TasteSpace.basis(0f, 0f, basis)
        assertEquals(1f, basis[7], 1e-4f) // 平视时上向=+Y
    }

    // ==================== 投影 ====================

    @Test
    fun `投影-视线上的星落在屏幕中心且深度精确`() {
        TasteSpace.lookDirection(0f, 0f, dir)
        val x = cam0.px + dir[0] * 800f
        val y = cam0.py + dir[1] * 800f
        val z = cam0.pz + dir[2] * 800f
        assertTrue(TasteSpace.project(x, y, z, cam0, 1000f, 540f, 1200f, out))
        assertEquals(540f, out[0], 0.01f)
        assertEquals(1200f, out[1], 0.01f)
        assertEquals(800f, out[2], 0.01f)
    }

    @Test
    fun `投影-右移星屏幕右移_上移星屏幕上移（透视除法方向）`() {
        // 星在 (右 100, 上 50, 深 800)：sx=cx+focal·100/800, sy=cy−focal·50/800
        assertTrue(TasteSpace.project(100f, 50f, 800f, cam0, 1000f, 540f, 1200f, out))
        assertEquals(540f + 125f, out[0], 0.5f)
        assertEquals(1200f - 62.5f, out[1], 0.5f)
    }

    @Test
    fun `投影-近大远小（3D 感核心）`() {
        assertTrue(TasteSpace.project(100f, 0f, 400f, cam0, 1000f, 540f, 1200f, out))
        val nearK = out[3]
        assertTrue(TasteSpace.project(100f, 0f, 3200f, cam0, 1000f, 540f, 1200f, out))
        val farK = out[3]
        assertEquals(1000f / 400f, nearK, 0.5f)
        assertEquals(1000f / 3200f, farK, 0.5f)
        assertTrue("近星屏像素/世界单位应显著更大", nearK > farK * 6f)
    }

    @Test
    fun `投影-相机背后的点被剔除`() {
        assertFalse(TasteSpace.project(0f, 0f, -100f, cam0, 1000f, 540f, 1200f, out))
        assertFalse(TasteSpace.project(0f, 0f, 5f, cam0, 1000f, 540f, 1200f, out)) // 近裁面内
        // 背后偏移的点同样剔除（深度=dot(d,f) 为负）
        assertFalse(TasteSpace.project(500f, 500f, -50f, cam0, 1000f, 540f, 1200f, out))
    }

    @Test
    fun `投影-转身后星野跟手（右滑=向右转头→星左移）`() {
        // 星在正前方偏右；右滑（dx>0）=向右转头 → 星屏位左移（内容跟手）
        TasteSpace.project(300f, 0f, 1000f, cam0, 1000f, 540f, 1200f, out)
        val sx0 = out[0]
        val camTurned = TasteSpace.turnBy(cam0, 120f, 0f, 0.1f)
        TasteSpace.project(300f, 0f, 1000f, camTurned, 1000f, 540f, 1200f, out)
        assertTrue("右滑后星应左移（sx0=$sx0 → ${out[0]}）", out[0] < sx0)
        // 左滑（dx<0）=向左转头 → 星右移
        val camTurnedL = TasteSpace.turnBy(cam0, -120f, 0f, 0.1f)
        TasteSpace.project(300f, 0f, 1000f, camTurnedL, 1000f, 540f, 1200f, out)
        assertTrue("左滑后星应右移", out[0] > sx0)
    }

    @Test
    fun `投影-上滑抬头后星野下移跟手`() {
        // 星在正上方偏前；上滑（dy<0）=抬头 → 星屏位下移（内容跟手）
        TasteSpace.project(0f, 300f, 1000f, cam0, 1000f, 540f, 1200f, out)
        val sy0 = out[1]
        val camUp = TasteSpace.turnBy(cam0, 0f, -120f, 0.1f)
        TasteSpace.project(0f, 300f, 1000f, camUp, 1000f, 540f, 1200f, out)
        assertTrue("上滑后星应下移（sy0=$sy0 → ${out[1]}）", out[1] > sy0)
    }

    @Test
    fun `投影-环顾四周（pitch 转到脚下看身后的星）`() {
        // 星在相机正后方（-Z）；低头 180° 不可达（钳 ±88°），但转身 180°+低头可见
        val cam = SpaceCam(0f, 0f, 0f, 180f, 0f)
        assertTrue(TasteSpace.project(0f, 0f, -1000f, cam, 1000f, 540f, 1200f, out))
        assertEquals(540f, out[0], 0.5f)
        // 抬头也能看头顶（+Y 1000 单位）：pitch=88 近乎垂直
        val camUp = SpaceCam(0f, 0f, 0f, 0f, 88f)
        assertTrue(TasteSpace.project(0f, 1000f, 100f, camUp, 1000f, 540f, 1200f, out))
    }

    // ==================== 移动（飞行） ====================

    @Test
    fun `移动-flyForward沿视线前进且朝向不变`() {
        val cam = TasteSpace.flyForward(cam0, 500f)
        assertEquals(0f, cam.px, 0.01f)
        assertEquals(0f, cam.py, 0.01f)
        assertEquals(500f, cam.pz, 0.01f)
        assertEquals(0f, cam.yawDeg, 0.01f)
        assertEquals(0f, cam.pitchDeg, 0.01f)
        // 朝斜向飞：位置沿视线方向移动
        val cam2 = TasteSpace.flyForward(SpaceCam(0f, 0f, 0f, 90f, 45f), 100f)
        TasteSpace.lookDirection(90f, 45f, dir)
        assertEquals(dir[0] * 100f, cam2.px, 0.01f)
        assertEquals(dir[1] * 100f, cam2.py, 0.01f)
        assertEquals(dir[2] * 100f, cam2.pz, 0.01f)
    }

    @Test
    fun `移动-前飞后前方星越来越近越来越大（穿越星域）`() {
        var cam = cam0
        var lastDepth = Float.MAX_VALUE
        var lastK = -1f
        repeat(30) {
            cam = TasteSpace.flyForward(cam, 60f)
            assertTrue(TasteSpace.project(0f, 0f, 2000f, cam, 1000f, 540f, 1200f, out))
            assertTrue("深度应单调递减", out[2] < lastDepth)
            assertTrue("屏像素密度应单调递增", out[3] > lastK)
            lastDepth = out[2]
            lastK = out[3]
        }
        // 飞过头（深度过零）→ 剔除（星星从身边掠过到身后）
        var past = cam
        repeat(40) { past = TasteSpace.flyForward(past, 100f) }
        assertFalse(TasteSpace.project(0f, 0f, 2000f, past, 1000f, 540f, 1200f, out))
    }

    @Test
    fun `移动-捏合飞行张开前进捏合后退且限幅`() {
        val spread = TasteSpace.flyBy(cam0, 1.2f)
        assertTrue(spread.pz > 0f) // 张开=前进
        val pinch = TasteSpace.flyBy(cam0, 0.8f)
        assertTrue(pinch.pz < 0f) // 捏合=后退
        // 单事件限幅：极端倍率也不超过 FLY_EVENT_CAP（R268b 收口政策件）
        val huge = TasteSpace.flyBy(cam0, 40f)
        assertEquals(GestureFeelPolicy.FLY_EVENT_CAP, huge.pz, 0.01f)
        val tiny = TasteSpace.flyBy(cam0, 0.01f)
        assertEquals(-GestureFeelPolicy.FLY_EVENT_CAP, tiny.pz, 0.01f)
    }

    @Test
    fun `移动-乘法捏合近地细控高空跨数量级`() {
        // R303c P3 §7.1：近地档（dRef<ZONE）与旧 flyBy 逐位一致（贴脸细控零变化）
        val near = TasteSpace.flyByAlt(cam0, 1.2f, 260f)
        val nearOld = TasteSpace.flyBy(cam0, 1.2f)
        assertEquals(nearOld.pz, near.pz, 0f)
        assertEquals(nearOld.px, near.px, 0f)
        // 高空档：单事件 factor 2.0 属毛刺档 → 事件帽钳 PINCH_MULT_EVENT_CAP
        // （防瞬移设计行为；「每满手势距离×2」由多事件累积达成——政策件单测锚）
        val far = TasteSpace.flyByAlt(cam0, 2f, 6000f)
        assertEquals(GestureFeelPolicy.PINCH_MULT_EVENT_CAP, far.pz, 0.01f)
        assertEquals(cam0.yawDeg, far.yawDeg, 0f)
        assertEquals(cam0.pitchDeg, far.pitchDeg, 0f)
        // 高空正常事件：factor 1.1@6000 → 前进 600（线性档只有 72——高空灵敏 8×）
        val ev = TasteSpace.flyByAlt(cam0, 1.1f, 6000f)
        assertEquals(600f, ev.pz, 0.01f)
        // 捏合后退（高空档负向，事件帽对称）
        val back = TasteSpace.flyByAlt(cam0, 0.5f, 6000f)
        assertEquals(-GestureFeelPolicy.PINCH_MULT_EVENT_CAP, back.pz, 0.01f)
    }

    @Test
    fun `移动-惯性滑行角速度与飞行速度双通道`() {
        // 角速度转头
        val cam = TasteSpace.drift(cam0, 90f, 0f, 0f, 1f)
        assertEquals(90f, cam.yawDeg, 0.01f)
        // 飞行速度沿视线
        val cam2 = TasteSpace.drift(cam0, 0f, 0f, 800f, 0.5f)
        assertEquals(400f, cam2.pz, 0.01f)
        // 双通道叠加：先转再沿**新视线**飞（转头与飞行同帧的合成语义）
        val cam3 = TasteSpace.drift(cam0, 30f, 10f, 1000f, 1f)
        assertEquals(30f, cam3.yawDeg, 0.01f)
        assertEquals(10f, cam3.pitchDeg, 0.01f)
        TasteSpace.lookDirection(30f, 10f, dir)
        assertEquals(dir[0] * 1000f, cam3.px, 0.1f)
        assertEquals(dir[1] * 1000f, cam3.py, 0.1f)
        assertEquals(dir[2] * 1000f, cam3.pz, 0.1f)
    }

    @Test
    fun `移动-pitch钳位防万向锁`() {
        val up = TasteSpace.turnBy(cam0, 0f, -100000f, 0.1f)
        assertEquals(GestureFeelPolicy.PITCH_LIMIT_DEG, up.pitchDeg, 0.01f) // R268b 收口政策件
        val down = TasteSpace.turnBy(cam0, 0f, 100000f, 0.1f)
        assertEquals(-GestureFeelPolicy.PITCH_LIMIT_DEG, down.pitchDeg, 0.01f)
    }

    // ==================== 点选（screenRay） ====================

    @Test
    fun `点选-屏心射线即视线方向`() {
        TasteSpace.screenRay(540f, 1200f, cam0, 1000f, 540f, 1200f, dir)
        TasteSpace.lookDirection(0f, 0f, out2)
        assertEquals(out2[0], dir[0], 1e-3f)
        assertEquals(out2[1], dir[1], 1e-3f)
        assertEquals(out2[2], dir[2], 1e-3f)
    }

    @Test
    fun `点选-射线恒为单位向量`() {
        for (sx in floatArrayOf(0f, 200f, 540f, 1080f)) {
            for (sy in floatArrayOf(0f, 400f, 1200f, 2400f)) {
                TasteSpace.screenRay(sx, sy, SpaceCam(10f, -20f, 30f, 37f, -12f), 1000f, 540f, 1200f, dir)
                assertEquals(1f, sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]), 1e-3f)
            }
        }
    }

    @Test
    fun `点选-射线与投影互逆（投影点必在自己的射线上）`() {
        val cam = SpaceCam(50f, -30f, 200f, 33f, -8f)
        val pts = arrayOf(
            floatArrayOf(300f, 200f, 1200f),
            floatArrayOf(-400f, 150f, 900f),
            floatArrayOf(100f, -600f, 2500f),
        )
        for (p in pts) {
            assertTrue(TasteSpace.project(p[0], p[1], p[2], cam, 1000f, 540f, 1200f, out))
            TasteSpace.screenRay(out[0], out[1], cam, 1000f, 540f, 1200f, dir)
            // p−cam 在射线上：投影长度=|p−cam|（夹角 0）
            val dx = p[0] - cam.px
            val dy = p[1] - cam.py
            val dz = p[2] - cam.pz
            val len = sqrt(dx * dx + dy * dy + dz * dz)
            val dot = dx * dir[0] + dy * dir[1] + dz * dir[2]
            assertEquals(len, dot, 0.5f) // 夹角≈0：点在射线上
        }
    }

    @Test
    fun `点选-射线选星命中半径世界口径`() {
        // 星在深 1000、屏位 (cx+125, cy)；点它旁边 5 世界单位处也能命中（slop 换算）
        val cam = cam0
        val star = floatArrayOf(100f, 0f, 800f) // 深 800、右 100
        assertTrue(TasteSpace.project(star[0], star[1], star[2], cam, 1000f, 540f, 1200f, out))
        val sx = out[0]
        val sy = out[1]
        TasteSpace.screenRay(sx, sy, cam, 1000f, 540f, 1200f, dir)
        // 星到射线垂距=0；偏 20px 的射线垂距 ≈ 20px×深度/focal
        TasteSpace.screenRay(sx + 20f, sy, cam, 1000f, 540f, 1200f, dir)
        val dx = star[0]
        val dy = star[1]
        val dz = star[2]
        val t = dx * dir[0] + dy * dir[1] + dz * dir[2]
        val perp2 = dx * dx + dy * dy + dz * dz - t * t
        val expectPerp = 20f * 800f / 1000f
        assertEquals(expectPerp, sqrt(perp2), 1.5f)
    }

    // ==================== 球面化分布 ====================

    @Test
    fun `分布-三带映射三壳层（任务书近中远口径）`() {
        val o = FloatArray(3)
        // 近带（flightZ≈−0.64×420，带心）→ 近壳 [90,480]
        TasteSpace.spherify(830f, 620f, -0.64f * 420f, 11L, o)
        var r = sqrt(o[0] * o[0] + o[1] * o[1] + o[2] * o[2])
        assertTrue("近带星应在近壳（r=$r）", r >= TasteSpace.SHELL_NEAR_MIN && r <= TasteSpace.SHELL_NEAR_MAX)
        // 中带 → 中壳
        TasteSpace.spherify(830f, 620f, 0f, 22L, o)
        r = sqrt(o[0] * o[0] + o[1] * o[1] + o[2] * o[2])
        assertTrue("中带星应在中壳（r=$r）", r >= TasteSpace.SHELL_MID_MIN && r <= TasteSpace.SHELL_MID_MAX)
        // 远带 → 远壳
        TasteSpace.spherify(830f, 620f, 0.64f * 420f, 33L, o)
        r = sqrt(o[0] * o[0] + o[1] * o[1] + o[2] * o[2])
        assertTrue("远带星应在远壳（r=$r）", r >= TasteSpace.SHELL_FAR_MIN && r <= TasteSpace.SHELL_FAR_MAX)
    }

    @Test
    fun `分布-全天球覆盖（上下左右前后都有星）`() {
        val o = FloatArray(3)
        val octant = IntArray(8)
        val quadrantY = IntArray(2)
        // 600 颗确定性星（口味位/带内位均匀铺开——带内位覆盖带全宽，非带心）
        for (i in 0 until 600) {
            val tx = 100f + (i % 30) * 28f
            val ty = 100f + (i / 30 % 20) * 40f
            val bandZ = ((i / 30 / 20 + i % 19) % 37 - 18) / 18f * 0.85f * 420f
            TasteSpace.spherify(tx, ty, bandZ, i * 31L + 7L, o)
            val idx = (if (o[0] > 0) 1 else 0) or (if (o[1] > 0) 2 else 0) or (if (o[2] > 0) 4 else 0)
            octant[idx]++
            if (o[1] > 0) quadrantY[1]++ else quadrantY[0]++
            // 高度角界内（±75°+混合余量）
            val r = sqrt(o[0] * o[0] + o[1] * o[1] + o[2] * o[2])
            assertTrue(abs(o[1] / r) < 0.97f)
        }
        for (k in 0 until 8) assertTrue("卦限 $k 无星", octant[k] > 0)
        assertTrue("上下半球都有星", quadrantY[0] > 100 && quadrantY[1] > 100)
    }

    @Test
    fun `分布-确定性（同数据同宇宙）`() {
        val a = FloatArray(3)
        val b = FloatArray(3)
        for (i in 0 until 50) {
            TasteSpace.spherify(200f + i * 13f, 700f - i * 7f, (i - 25) * 12f, i * 101L + 3L, a)
            TasteSpace.spherify(200f + i * 13f, 700f - i * 7f, (i - 25) * 12f, i * 101L + 3L, b)
            assertEquals(a[0], b[0], 0f)
            assertEquals(a[1], b[1], 0f)
            assertEquals(a[2], b[2], 0f)
        }
    }

    @Test
    fun `分布-口味簇保持角邻接（同簇星方位角相近）`() {
        val a = FloatArray(3)
        val b = FloatArray(3)
        // 两颗同簇星（口味位相邻 30 单位、同中带）→ 方位角差 < 12°
        TasteSpace.spherify(800f, 800f, 0f, 7L, a)
        TasteSpace.spherify(826f, 812f, 0f, 9L, b)
        val angA = Math.toDegrees(Math.atan2(-a[2].toDouble(), a[0].toDouble())) // 空间系水平角
        val angB = Math.toDegrees(Math.atan2(-b[2].toDouble(), b[0].toDouble()))
        var d = abs(angA - angB)
        if (d > 180) d = 360 - d
        assertTrue("同簇星方位角应相近（差=$d°）", d < 12.0)
    }

    // ==================== 运镜（approach） ====================

    @Test
    fun `运镜-双击收敛到停船点且看着星`() {
        var cam = SpaceCam(2000f, -500f, -3000f, 40f, -10f)
        val star = floatArrayOf(-300f, 200f, 1500f)
        val stop = 200f
        var arrived = false
        repeat(600) {
            cam = TasteSpace.approach(cam, star[0], star[1], star[2], stop, 0.09f)
            if (TasteSpace.approachArrived(cam, star[0], star[1], star[2], stop)) {
                arrived = true
                return@repeat
            }
        }
        assertTrue("600 步内应到达", arrived)
        // 位置=停船位（星前 stop 单位）
        val d = TasteSpace.distTo(cam, star[0], star[1], star[2])
        assertEquals(stop, d, 12f)
        // 朝向=看向星
        val aim = FloatArray(2)
        TasteSpace.aimAt(cam, star[0], star[1], star[2], aim)
        assertEquals(0f, TasteSpace.angDiff(cam.yawDeg, aim[0]), 2.5f)
        assertEquals(0f, TasteSpace.angDiff(cam.pitchDeg, aim[1]), 2.5f)
    }

    @Test
    fun `运镜-已在停船距离内只转头不逼近`() {
        val star = floatArrayOf(0f, 0f, 150f)
        var cam = SpaceCam(0f, 0f, 0f, 90f, 0f)
        repeat(50) { cam = TasteSpace.approach(cam, star[0], star[1], star[2], 200f, 0.2f) }
        // 距离保持 150（不再推进）
        assertEquals(150f, TasteSpace.distTo(cam, star[0], star[1], star[2]), 6f)
        // 朝向收敛到星
        val aim = FloatArray(2)
        TasteSpace.aimAt(cam, star[0], star[1], star[2], aim)
        assertEquals(0f, TasteSpace.angDiff(cam.yawDeg, aim[0]), 2f)
    }

    // ==================== 距离衰减 / 光流 / 角度工具 ====================

    @Test
    fun `衰减-fog单调递减且有下限`() {
        assertTrue(TasteSpace.fogOf(100f) > TasteSpace.fogOf(1000f))
        assertTrue(TasteSpace.fogOf(1000f) > TasteSpace.fogOf(4000f))
        assertTrue(TasteSpace.fogOf(8000f) > 0.1f) // 远方光点不消失（R247 立体感衰减加深后的下限口径）
        assertEquals(1f, TasteSpace.fogOf(0f), 1e-3f)
    }

    @Test
    fun `光流-前飞径向外涌_转头星野反向`() {
        val flow = FloatArray(3)
        val prev = SpaceCam(0f, 0f, 0f, 0f, 0f)
        // 前飞：径向率为正（屏心离心距×率=向外速度）
        val fwd = SpaceCam(0f, 0f, 110f, 0f, 0f)
        TasteSpace.opticalFlow(prev, fwd, 1000f, 0.1f, flow)
        assertTrue(flow[2] > 0f)
        // 右转：星左移（flowU<0）；抬头：星下移（flowV>0）
        val turned = SpaceCam(0f, 0f, 0f, 9f, 0f)
        TasteSpace.opticalFlow(prev, turned, 1000f, 0.1f, flow)
        assertTrue(flow[0] < 0f)
        val up = SpaceCam(0f, 0f, 0f, 0f, 9f)
        TasteSpace.opticalFlow(prev, up, 1000f, 0.1f, flow)
        assertTrue(flow[1] > 0f)
    }

    @Test
    fun `角度-wrap与最短角差`() {
        assertEquals(0f, TasteSpace.wrapDeg(360f), 1e-4f)
        assertEquals(-179f, TasteSpace.wrapDeg(181f), 1e-3f)
        assertEquals(179f, TasteSpace.wrapDeg(-181f), 1e-3f)
        assertEquals(20f, TasteSpace.angDiff(350f, 10f), 1e-3f) // 跨零最短路径
        assertEquals(-20f, TasteSpace.angDiff(10f, 350f), 1e-3f)
    }

    @Test
    fun `天幕-方向均匀覆盖全天球`() {
        val d = FloatArray(3)
        val octant = IntArray(8)
        for (i in 0 until TasteSpace.SKY_STARS) {
            TasteSpace.skyDirection(i, d)
            val len = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
            assertEquals(1f, len, 1e-3f)
            val idx = (if (d[0] > 0) 1 else 0) or (if (d[1] > 0) 2 else 0) or (if (d[2] > 0) 4 else 0)
            octant[idx]++
        }
        for (k in 0 until 8) assertTrue("天幕卦限 $k 空", octant[k] > 30)
    }
}
