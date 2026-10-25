package dev.tgfeed.core.tastemap

import dev.tgfeed.core.tastemap.TasteFlight.FlightCam
import dev.tgfeed.core.tastemap.TasteFlight.FxEngine
import dev.tgfeed.core.tastemap.TasteFlight.FitStar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/**
 * R206 自由深空飞行核心单测：相机变换（平移跟手/视差深浅/缩放俯冲/滚转及其命中
 * 逆变换/惯性衰减/焦点推进收敛）、投影（中心锚定/近远透视/近平面防护/雾）、
 * 创世纪（量程/星生序/无证据星最后诞生/光龄）、XY 鲁棒取景（分位框/离群点/
 * 名签并入/退化回退）、粒子引擎（爆散池回收/流光按边权分布/流星串循环与尾迹/
 * 彗星飞行与熄灭/星爆闪 TTL/确定性同轨迹）。
 */
class TasteFlightTest {

    private val cam0 = FlightCam(500f, 500f, 1f, 0f)
    private val out = FloatArray(3)
    private val out2 = FloatArray(3)

    // ==================== 相机与投影 ====================

    @Test
    fun `投影-相机轴上的星落在屏幕中心`() {
        TasteFlight.project(500f, 500f, 0f, cam0, 1650f, 540f, 1200f, out)
        assertEquals(540f, out[0], 0.01f)
        assertEquals(1200f, out[1], 0.01f)
        // z=0 恰在初始距离上：persp=1
        assertEquals(1f, out[2], 0.01f)
    }

    @Test
    fun `投影-近星persp大远星persp小`() {
        TasteFlight.project(500f, 500f, -160f, cam0, 1650f, 540f, 1200f, out)
        val nearPersp = out[2]
        TasteFlight.project(500f, 500f, 160f, cam0, 1650f, 540f, 1200f, out)
        val farPersp = out[2]
        assertTrue(nearPersp > 1f && farPersp < 1f)
        // 半径=radius×persp：近星标记大远星小（深空近大远小）
        assertTrue(nearPersp > farPersp)
    }

    @Test
    fun `投影-近平面防护`() {
        // dist 会随 zoom 缩小，但 z+dist 恒被 clamp 到 ≥200 → persp 有界不爆
        val deepCam = FlightCam(500f, 500f, TasteFlight.ZOOM_MAX, 0f)
        TasteFlight.project(500f, 500f, -10000f, deepCam, 1650f, 540f, 1200f, out)
        assertEquals(TasteFlight.D0 / TasteFlight.NEAR_PLANE, out[2], 0.01f)
    }

    @Test
    fun `平移-星野四向全跟手`() {
        // 手指右拖 100px：中深度星屏幕 x 应右移 ~100px（跟手）
        val cam = TasteFlight.panBy(cam0, 100f, 0f, 1f)
        TasteFlight.project(500f, 500f, 0f, cam, 1650f, 540f, 1200f, out)
        assertEquals(640f, out[0], 2f)
        // 手指下拖 100px：星屏幕 y 下移 ~100px
        val cam2 = TasteFlight.panBy(cam0, 0f, 100f, 1f)
        TasteFlight.project(500f, 500f, 0f, cam2, 1650f, 540f, 1200f, out)
        assertEquals(1300f, out[1], 2f)
        // R210 四向验收（上滑图上移/下滑图下移/左右同）：反方向拖拽星野反向跟
        val camL = TasteFlight.panBy(cam0, -100f, 0f, 1f)
        TasteFlight.project(500f, 500f, 0f, camL, 1650f, 540f, 1200f, out)
        assertEquals(440f, out[0], 2f)
        val camU = TasteFlight.panBy(cam0, 0f, -100f, 1f)
        TasteFlight.project(500f, 500f, 0f, camU, 1650f, 540f, 1200f, out)
        assertEquals(1100f, out[1], 2f)
    }

    @Test
    fun `平移-近星快远星慢（视差是深空感核心）`() {
        val cam = TasteFlight.panBy(cam0, 100f, 0f, 1f)
        TasteFlight.project(600f, 500f, -160f, cam, 1650f, 540f, 1200f, out)  // 近星
        TasteFlight.project(600f, 500f, 160f, cam, 1650f, 540f, 1200f, out2)  // 远星
        val nearShift = out[0] - 540f
        val farShift = out2[0] - 540f
        assertTrue(nearShift > farShift && farShift > 0f)
        assertTrue("近星位移应显著大于远星", nearShift > farShift * 1.15f)
    }

    @Test
    fun `缩放-表观倍率恰等于zoom（dolly×lens互补）`() {
        // z=0 星的屏幕比例系数 k=focal/dist 应随 zoom 线性放大
        for (zoom in floatArrayOf(0.5f, 2f, 5f)) {
            val cam = FlightCam(500f, 500f, zoom, 0f)
            val focal = TasteFlight.focalOf(1f, zoom)
            TasteFlight.project(650f, 500f, 0f, cam, focal, 540f, 1200f, out)
            TasteFlight.project(650f, 500f, 0f, cam0, 1650f, 540f, 1200f, out2)
            assertEquals(out2[0] - 540f, (out[0] - 540f) / zoom, 1.5f)
        }
    }

    @Test
    fun `缩放-俯冲时视差增强`() {
        // 放大 5 倍后平移同量：近远星位移差比 zoom=1 时更大（dolly 缩短 dist）
        val camNear = TasteFlight.panBy(FlightCam(500f, 500f, 5f, 0f), 100f, 0f, 1f)
        TasteFlight.project(600f, 500f, -160f, camNear, TasteFlight.focalOf(1f, 5f), 540f, 1200f, out)
        TasteFlight.project(600f, 500f, 160f, camNear, TasteFlight.focalOf(1f, 5f), 540f, 1200f, out2)
        val ratioZoomed = (out[0] - 540f) / (out2[0] - 540f)
        val cam1 = TasteFlight.panBy(cam0, 100f, 0f, 1f)
        TasteFlight.project(600f, 500f, -160f, cam1, 1650f, 540f, 1200f, out)
        TasteFlight.project(600f, 500f, 160f, cam1, 1650f, 540f, 1200f, out2)
        val ratioPlain = (out[0] - 540f) / (out2[0] - 540f)
        assertTrue("俯冲后近/远位移比应更大（${ratioZoomed} > $ratioPlain）", ratioZoomed > ratioPlain)
    }

    @Test
    fun `缩放-钳位到上下限`() {
        val up = TasteFlight.zoomBy(cam0, 1e6f)
        assertEquals(TasteFlight.ZOOM_MAX, up.zoom, 0f)
        val down = TasteFlight.zoomBy(cam0, 1e-9f)
        assertEquals(TasteFlight.ZOOM_MIN, down.zoom, 0f)
    }

    // ==================== R210 手势参照系（单位/pivot/方向回归锚） ====================

    @Test
    fun `手势-旋转度直传无57倍放大（R210根因锚）`() {
        // detectTransformGestures 的 rotation 是「度」（foundation 1.7.2 字节码实锤
        // ×180/π）——拧 10 度就滚 10 度；R206 把度当弧度再 toDegrees=×57.3 放大，
        // 捏合时整图绕屏心狂转+残留 roll 反转拖拽参照系（走查双实锤根干）
        val c = TasteFlight.gestureStep(cam0, 0f, 0f, 1f, 10f, 540f, 1200f, 1f, 540f, 1200f)
        assertEquals(10f, c.rollDeg, 0.01f)
    }

    @Test
    fun `手势-捏合零滚转泄漏`() {
        // 纯捏合（zoom≠1、rotation=0）：滚转分量必须为零——缩放绝不带旋转
        val c = TasteFlight.gestureStep(cam0, 3f, 2f, 1.05f, 0f, 900f, 1300f, 1f, 540f, 1200f)
        assertEquals(0f, c.rollDeg, 0f)
        assertTrue(c.zoom > 1f)
    }

    @Test
    fun `手势-拧转不影响缩放`() {
        // 纯拧转（rotation≠0、zoom=1）：zoom 不动，只有 roll 跟手
        val c = TasteFlight.gestureStep(cam0, 0f, 0f, 1f, 45f, 540f, 1200f, 1f, 540f, 1200f)
        assertEquals(1f, c.zoom, 0f)
        assertEquals(45f, c.rollDeg, 0.01f)
    }

    @Test
    fun `手势-拧转方向跟手（顺时针带屏右星向下）`() {
        // 顺时针拧 30 度（Compose rotation=+30 度）：屏幕右方的星被顺时针带向下方
        val c = TasteFlight.gestureStep(cam0, 0f, 0f, 1f, 30f, 540f, 1200f, 1f, 540f, 1200f)
        TasteFlight.project(650f, 500f, 0f, c, 1650f, 540f, 1200f, out)
        assertTrue("星应仍偏右且已被带向下方", out[0] > 540f && out[1] > 1200f)
    }

    @Test
    fun `缩放-质心锚定（z=0星钉在指尖）`() {
        // 捏合 3 倍、锚在质心屏位：质心处中深度星缩放前后屏位不动
        val starX = 660f; val starY = 400f
        TasteFlight.project(starX, starY, 0f, cam0, 1650f, 540f, 1200f, out)
        val pivotX = out[0]; val pivotY = out[1]
        val cam = TasteFlight.zoomAt(cam0, 3f, pivotX, pivotY, 1f, 540f, 1200f)
        TasteFlight.project(starX, starY, 0f, cam, TasteFlight.focalOf(1f, cam.zoom), 540f, 1200f, out)
        assertEquals(pivotX, out[0], 0.1f)
        assertEquals(pivotY, out[1], 0.1f)
        assertEquals(3f, cam.zoom, 0f)
    }

    @Test
    fun `缩放-滚转态质心锚仍成立`() {
        // 带滚转的相机捏合：pivot 先反旋再解算——滚转下锚照样精确
        val rolled = TasteFlight.rollBy(cam0, 37f)
        val starX = 620f; val starY = 470f
        TasteFlight.project(starX, starY, 0f, rolled, 1650f, 540f, 1200f, out)
        val pivotX = out[0]; val pivotY = out[1]
        val cam = TasteFlight.zoomAt(rolled, 0.5f, pivotX, pivotY, 1f, 540f, 1200f)
        TasteFlight.project(starX, starY, 0f, cam, TasteFlight.focalOf(1f, cam.zoom), 540f, 1200f, out)
        assertEquals(pivotX, out[0], 0.1f)
        assertEquals(pivotY, out[1], 0.1f)
        assertEquals(37f, cam.rollDeg, 0.01f)
    }

    @Test
    fun `缩放-钳位后质心锚仍成立`() {
        // 撞 ZOOM_MAX：按钳后实际 zoom 解算——锚不漂
        val starX = 700f; val starY = 300f
        TasteFlight.project(starX, starY, 0f, cam0, 1650f, 540f, 1200f, out)
        val pivotX = out[0]; val pivotY = out[1]
        val cam = TasteFlight.zoomAt(cam0, 1e6f, pivotX, pivotY, 1f, 540f, 1200f)
        assertEquals(TasteFlight.ZOOM_MAX, cam.zoom, 0f)
        TasteFlight.project(starX, starY, 0f, cam, TasteFlight.focalOf(1f, cam.zoom), 540f, 1200f, out)
        assertEquals(pivotX, out[0], 0.1f)
        assertEquals(pivotY, out[1], 0.1f)
    }

    @Test
    fun `缩放-zoomAt 恒等`() {
        // factor=1 或已钳死在边界：相机原样返回（含滚转保留）
        assertEquals(cam0, TasteFlight.zoomAt(cam0, 1f, 900f, 1300f, 1f, 540f, 1200f))
        val atMax = FlightCam(500f, 500f, TasteFlight.ZOOM_MAX, 0f)
        assertEquals(atMax, TasteFlight.zoomAt(atMax, 2f, 900f, 1300f, 1f, 540f, 1200f))
    }

    @Test
    fun `手势-pan加捏合组合指尖星全程钉住`() {
        // 一事件组合（质心 (700,1300)→(720,1310) 移动 + 1.06 倍捏合）：
        // 盯住旧质心的中深度星应精确落到新质心——内容跟手+锚定质心的合成绩
        val cx = 540f; val cy = 1200f
        val starX = 660f; val starY = 400f // 投影恰在 (700,1300) 的世界点
        TasteFlight.project(starX, starY, 0f, cam0, 1650f, cx, cy, out)
        assertEquals(700f, out[0], 0.01f)
        assertEquals(1300f, out[1], 0.01f)
        val c = TasteFlight.gestureStep(cam0, 20f, 10f, 1.06f, 0f, 720f, 1310f, 1f, cx, cy)
        TasteFlight.project(starX, starY, 0f, c, TasteFlight.focalOf(1f, c.zoom), cx, cy, out)
        assertEquals(720f, out[0], 0.1f)
        assertEquals(1310f, out[1], 0.1f)
    }

    @Test
    fun `惯性-速度方向与拖拽drift严格一致`() {
        // 松手惯性必须沿拖拽方向滑行：flingVelFromPan×(1/60) 的相机位移 == panBy 同位移
        val vel = FloatArray(2)
        TasteFlight.flingVelFromPan(cam0, 100f, 50f, 1f, vel)
        val drifted = TasteFlight.drift(cam0, vel[0], vel[1], 1f / 60f)
        val panned = TasteFlight.panBy(cam0, 100f, 50f, 1f)
        assertEquals(panned.x, drifted.x, 0.001f)
        assertEquals(panned.y, drifted.y, 0.001f)
        // 高 zoom 下同样成立（wpp 随 zoom 缩放的一致性）
        val camZ = FlightCam(500f, 500f, 4f, 0f)
        TasteFlight.flingVelFromPan(camZ, 80f, -60f, 1f, vel)
        val driftedZ = TasteFlight.drift(camZ, vel[0], vel[1], 1f / 60f)
        val pannedZ = TasteFlight.panBy(camZ, 80f, -60f, 1f)
        assertEquals(pannedZ.x, driftedZ.x, 0.001f)
        assertEquals(pannedZ.y, driftedZ.y, 0.001f)
    }

    @Test
    fun `滚转-绕屏心旋转且unroll互逆`() {
        val cam = TasteFlight.rollBy(cam0, 90f)
        assertEquals(90f, cam.rollDeg, 0f)
        // 屏幕右方（u>0,v=0）的星滚转后落到屏幕下方
        TasteFlight.project(650f, 500f, 0f, cam, 1650f, 540f, 1200f, out)
        assertTrue(out[0] < 540.5f && out[1] > 1200f)
        // 命中逆变换：把投影点转回平面坐标，应回到无滚转投影位
        TasteFlight.unroll(out[0], out[1], cam, 540f, 1200f, out2)
        TasteFlight.project(650f, 500f, 0f, cam0, 1650f, 540f, 1200f, out)
        assertEquals(out[0], out2[0], 0.01f)
        assertEquals(out[1], out2[1], 0.01f)
        // wrap：360 度归零
        val wrapped = TasteFlight.rollBy(FlightCam(0f, 0f, 1f, 350f), 20f)
        assertEquals(10f, wrapped.rollDeg, 0.001f)
    }

    @Test
    fun `惯性-衰减到停机阈`() {
        var v = 800f
        var ms = 0f
        while (abs(v) > TasteFlight.FLING_EPS && ms < 3000f) {
            v = TasteFlight.decayVel(v, 16.7f)
            ms += 16.7f
        }
        assertTrue("衰减应在 3s 内停机（实际 ${ms}ms）", ms < 3000f)
        assertTrue(abs(v) <= TasteFlight.FLING_EPS)
    }

    @Test
    fun `焦点推进-指数趋近收敛并报到位`() {
        var cam = cam0
        val tau = 0.18f
        val dt = 1 / 60f
        var steps = 0
        while (steps < 600 && !TasteFlight.focusArrived(cam, 700f, 300f, 3f)) {
            val k = 1f - kotlin.math.exp(-dt / tau)
            cam = TasteFlight.approach(cam, 700f, 300f, 3f, k)
            steps++
        }
        assertTrue("600 帧（10s）内应到位", TasteFlight.focusArrived(cam, 700f, 300f, 3f))
        assertEquals(700f, cam.x, 8f)
        assertEquals(300f, cam.y, 8f)
        assertEquals(3f, cam.zoom, 0.06f)
        // 滚转在推进中不被清掉（惯性运镜保留手感的滚转）
        assertEquals(0f, cam.rollDeg, 0f)
    }

    @Test
    fun `雾-persp越远雾越淡`() {
        assertEquals(1f, TasteFlight.tasteFog(1f), 0.001f)
        val far = TasteFlight.tasteFog(0.5f)
        assertTrue(far in 0.3f..0.4f)
        assertTrue(TasteFlight.tasteFog(0.25f) < far)
        // 近（persp>1）不反向增亮：clamp 到 1
        assertEquals(1f, TasteFlight.tasteFog(2f), 0.001f)
    }

    // ==================== 创世纪 ====================

    @Test
    fun `创世纪-量程忽略无证据且全无证据时无效`() {
        val span = TasteFlight.genesisSpan(listOf(0L, 1000L, 500L, 0L, 2000L))
        assertEquals(500L, span.minMs)
        assertEquals(2000L, span.maxMs)
        assertTrue(span.valid)
        assertFalse(TasteFlight.genesisSpan(listOf(0L, 0L, -1L)).valid)
    }

    @Test
    fun `创世纪-星生序按首见凝聚无证据星最后诞生`() {
        val span = TasteFlight.GenesisSpan(100L, 1100L)
        // 首见=600 的星：光标 <0.5 不可见，≥0.5 可见
        assertFalse(TasteFlight.bornVisible(600L, span, 0.49f))
        assertTrue(TasteFlight.bornVisible(600L, span, 0.5f))
        // 首见=量程 min 的星：光标 0（大爆炸起点）即诞生
        assertTrue(TasteFlight.bornVisible(100L, span, 0f))
        // 无证据（0）星：只在光标=1（宇宙凝固定格）诞生
        assertFalse(TasteFlight.bornVisible(0L, span, 0.999f))
        assertTrue(TasteFlight.bornVisible(0L, span, 1f))
        // 光龄：诞生前为负，诞生刻为 0，之后随光标推进增长
        assertTrue(TasteFlight.birthAgeMs(600L, span, 0.4f) < 0L)
        assertEquals(0L, TasteFlight.birthAgeMs(600L, span, 0.5f))
        assertTrue(TasteFlight.birthAgeMs(600L, span, 0.6f) > 0L)
    }

    @Test
    fun `创世纪-光标线性映射量程`() {
        val span = TasteFlight.GenesisSpan(1_000_000L, 2_000_000L)
        assertEquals(1_000_000L, TasteFlight.cursorMs(span, 0f))
        assertEquals(2_000_000L, TasteFlight.cursorMs(span, 1f))
        assertEquals(1_500_000L, TasteFlight.cursorMs(span, 0.5f))
        // 越界 clamp
        assertEquals(2_000_000L, TasteFlight.cursorMs(span, 1.5f))
    }

    // ==================== R314 宇宙延时摄影（光年口径读数+倍速档） ====================

    @Test
    fun `R314 光年读数-分档与单位上限自动升档`() {
        assertEquals("0 光秒", TasteFlight.lightTravelOf(0L))
        assertEquals("0 光秒", TasteFlight.lightTravelOf(-5L))
        assertEquals("1 光秒", TasteFlight.lightTravelOf(500L))
        assertEquals("30 光秒", TasteFlight.lightTravelOf(30_000L))
        assertEquals("1 光分", TasteFlight.lightTravelOf(60_000L))
        assertEquals("42 光分", TasteFlight.lightTravelOf(42L * 60_000L))
        // 59.98 分钟 → 升档「1 光时」（不产「60 光分」）
        assertEquals("1 光时", TasteFlight.lightTravelOf(3_599_000L))
        assertEquals("5 光时", TasteFlight.lightTravelOf(5L * 3_600_000L))
        // 23.6 小时 → 升档「1 光日」（不产「24 光时」）
        assertEquals("1 光日", TasteFlight.lightTravelOf(84_960_000L))
        assertEquals("153 光日", TasteFlight.lightTravelOf(153L * 86_400_000L))
        // 400 天 → 1.095 年 → 一位小数「1.1 光年」
        assertEquals("1.1 光年", TasteFlight.lightTravelOf(400L * 86_400_000L))
        // 2163 天 ≈ 5.921 年 →「5.9 光年」
        assertEquals("5.9 光年", TasteFlight.lightTravelOf(2163L * 86_400_000L))
    }

    @Test
    fun `R314 回望副读数-人间时长锚分档`() {
        assertEquals("此刻", TasteFlight.lookbackTextOf(0L))
        assertEquals("此刻", TasteFlight.lookbackTextOf(59_000L))
        assertEquals("1 分钟前", TasteFlight.lookbackTextOf(60_000L))
        assertEquals("3 小时前", TasteFlight.lookbackTextOf(3L * 3_600_000L))
        assertEquals("2 天前", TasteFlight.lookbackTextOf(2L * 86_400_000L))
        assertEquals("2.2 年前", TasteFlight.lookbackTextOf(800L * 86_400_000L))
    }

    @Test
    fun `R314 倍速档-三档循环单调加速`() {
        val steps = TasteFlight.TIMELAPSE_SPEED_STEPS
        assertEquals(3, steps.size)
        assertEquals(1f, steps[0], 0.001f)
        assertTrue("档速单调加速", steps[0] < steps[1] && steps[1] < steps[2])
        assertEquals("最高档=16 倍（12s 基底→0.75s 全程扫过）", 16f, steps[2], 0.001f)
    }

    // ==================== XY 取景框 ====================

    private fun fitStars(): List<FitStar> {
        // 主体云团 100 颗挤在 400..600；两颗离群星在 5000；一颗名签星在 650
        val list = ArrayList<FitStar>(103)
        repeat(100) { i ->
            list += FitStar(400f + (i % 10) * 20f, 400f + (i / 10) * 20f, 0f, 6f, true, false)
        }
        list += FitStar(5000f, 500f, 0f, 6f, true, false)   // 有证据但离群
        list += FitStar(500f, 5000f, 0f, 6f, true, false)
        list += FitStar(660f, 460f, 0f, 12f, false, true)   // 名签星（无构成证据但强制并入）
        return list
    }

    @Test
    fun `取景-鲁棒分位框排除离群点名签强制并入`() {
        val b = TasteFlight.flightBoundsXy(fitStars())
        // 离群点（5000）不该把框撑大：宽高应远小于 4600
        assertTrue("鲁棒框宽 ${b[2]} 应远小于全量", b[2] < 1000f && b[3] < 1000f)
        // 名签星（660±16）必须并入框内
        assertTrue(b[0] <= 644f && b[0] + b[2] >= 676f)
        // 全量框含离群点
        val full = TasteFlight.boundsFullXy(fitStars())
        assertTrue(full[2] > 4000f || full[3] > 4000f)
    }

    @Test
    fun `取景-证据星不足回退全量盒`() {
        val few = listOf(
            FitStar(100f, 100f, 0f, 5f, false, false),
            FitStar(200f, 100f, 0f, 5f, false, false),
        )
        assertEquals(TasteFlight.boundsFullXy(few).toList(), TasteFlight.flightBoundsXy(few).toList())
    }

    // ==================== 粒子引擎 ====================

    @Test
    fun `爆散-生成推进回收池不超限`() {
        val e = FxEngine(burstCap = 16)
        e.burst(100f, 100f, 14, 300f, 42)
        assertEquals(14, e.burstCount)
        repeat(120) { e.step(1 / 60f) }
        assertEquals("1.2s 后全灭", 0, e.burstCount)
        // 池上限：狂撒不越界
        repeat(20) { e.burst(0f, 0f, 14, 300f, it) }
        assertTrue(e.burstCount <= 16)
        // 位置确实飞出去了
        e.burst(0f, 0f, 1, 300f, 7)
        val x0 = e.bxSnapshot()[0]
        e.step(1 / 60f)
        assertTrue(abs(e.bxSnapshot()[0] - x0) > 0.01f)
    }

    @Test
    fun `流光-按边权分布且t回绕`() {
        val e = FxEngine(flowCap = 32)
        e.setLinks(floatArrayOf(1f, 0.02f), 2)
        assertEquals(minOf(32, 4), e.flowActive)
        // 重边拿走绝大多数粒子
        var heavy = 0
        for (k in 0 until e.flowActive) if (e.edgeSnapshot()[k] == 0) heavy++
        assertTrue("重边应占多数（$heavy/${e.flowActive}）", heavy >= e.flowActive - 1)
        // t 推进回绕不越界
        repeat(300) { e.step(0.1f) }
        for (k in 0 until e.flowActive) {
            val t = e.tSnapshot()[k]
            assertTrue(t in 0f..1f)
        }
    }

    @Test
    fun `流星-星到星飞行循环尾迹有界`() {
        val e = FxEngine()
        e.setRoute(floatArrayOf(100f, 300f, 500f), floatArrayOf(100f, 200f, 100f), floatArrayOf(0f, 0f, 0f), 3)
        assertTrue(e.meteorAlive)
        // 停泊 0.8s 后起飞
        repeat(60) { e.step(1 / 60f) } // 1s
        assertTrue(e.meteorTSnapshot() > 0f || e.meteorSegSnapshot() > 0)
        // 尾迹环上限=trailCap，长跑不越界
        repeat(6000) { e.step(1 / 60f) } // 100s：跑完整路线多轮
        assertTrue(e.trailSizeSnapshot() <= e.trailCapSnapshot())
        // 路线走完回 0 段循环（今晚的旅程反复上演）
        assertTrue(e.meteorAlive)
        // <2 点=诚实无流星
        e.setRoute(floatArrayOf(100f), floatArrayOf(100f), floatArrayOf(0f), 1)
        assertFalse(e.meteorAlive)
    }

    @Test
    fun `彗星-飞行熄灭与出界`() {
        val e = FxEngine()
        e.spawnComet(seed = 9, lifeSec = 0.2f)
        val y0 = e.cometYSnapshot()
        repeat(30) { e.step(1 / 60f) } // 0.5s > 寿命
        assertTrue("寿命尽熄灭（≤0 即死）", e.cometLifeSnapshot() <= 0f)
        // 出界自灭：贴边往界外飞
        e.spawnComet(seed = 3, lifeSec = 10f)
        repeat(300) { e.step(1 / 60f) }
        assertTrue("5s 内应出界自灭", e.cometLifeSnapshot() <= 0f)
    }

    @Test
    fun `星爆闪-TTL倒数归零移除`() {
        val e = FxEngine()
        e.flashStar(12345L, 0.3f)
        assertTrue(e.hasFlash(12345L))
        repeat(60) { e.step(1 / 60f) } // 1s
        assertFalse("TTL 归零后移除", e.hasFlash(12345L))
    }

    @Test
    fun `确定性-同种子同轨迹`() {
        fun drive(): FxEngine {
            val e = FxEngine()
            e.burst(50f, 60f, 10, 200f, 99)
            e.setLinks(floatArrayOf(0.5f, 0.5f), 2)
            e.setRoute(floatArrayOf(0f, 100f), floatArrayOf(0f, 100f), floatArrayOf(0f, 0f), 2)
            e.spawnComet(seed = 5)
            e.flashStar(77L)
            repeat(120) { e.step(1 / 60f) }
            return e
        }
        val a = drive()
        val b = drive()
        assertEquals(a.burstCount, b.burstCount)
        for (i in 0 until a.burstCount) assertEquals(a.bxSnapshot()[i], b.bxSnapshot()[i], 0.0001f)
        assertEquals(a.cometXSnapshot(), b.cometXSnapshot(), 0.0001f)
        assertEquals(a.meteorTSnapshot(), b.meteorTSnapshot(), 0.0001f)
        assertEquals(2f, a.timeSecSnapshot(), 0.001f)
    }

    @Test
    fun `时钟-统一呼吸相位源推进`() {
        val e = FxEngine()
        repeat(60) { e.step(1 / 60f) }
        assertEquals(1f, e.timeSecSnapshot(), 0.001f)
    }

    // ==================== R320 引力波波前账（FxEngine carrier） ====================

    @Test
    fun `波前账-演出级闪光门独占`() {
        // 彗星爆闪 2.5/舷窗欢迎 3.5/NOVA 复燃 4.5 恒不入门；超新星 7.0/宣判 9.0 入
        val e = FxEngine()
        e.flashStar(111L, 2.5f)
        e.flashStar(112L, 3.5f)
        e.flashStar(113L, 4.5f)
        assertEquals(0, e.waveCount)
        assertTrue(e.starFlashes.isNotEmpty()) // 爆闪本身照常（门只筛波前）
        e.flashStar(114L, GravityWaveFx.RIPPLE_FLASH_TTL_SEC)
        assertEquals(1, e.waveCount)
        assertEquals(114L, e.waveChatIds[0])
        assertEquals(GravityWaveFx.RIPPLE_AMP_PX, e.waveAmpPx[0], 1e-3f)
        e.flashStar(115L, GravityWaveFx.VERDICT_FLASH_TTL_SEC)
        assertEquals(2, e.waveCount)
        assertEquals(GravityWaveFx.VERDICT_AMP_PX, e.waveAmpPx[1], 1e-3f)
    }

    @Test
    fun `波前账-同星重置与池满挤最老`() {
        val e = FxEngine()
        e.flashStar(221L, 9.0f)
        repeat(5) { e.step(0.1f) } // 第一颗波 0.5s 龄（step 的 dt 钳 ≤0.1——分拍推）
        // 同星重波=重置该槽（不新开——宣判重演/跃迁叠加取新档）
        e.flashStar(221L, 7.0f)
        assertEquals(1, e.waveCount)
        assertEquals(GravityWaveFx.RIPPLE_AMP_PX, e.waveAmpPx[0], 1e-3f)
        assertEquals(0.5f, e.waveStartSec[0], 1e-3f) // 重置=以当前 timeSec 重新起算
        // 池满（≤2）：第三颗=最老让位
        e.flashStar(222L, 9.0f)
        e.flashStar(223L, 9.0f)
        assertEquals(2, e.waveCount)
        val ids = setOf(e.waveChatIds[0], e.waveChatIds[1])
        assertTrue("最老波（221）让位", 221L !in ids)
        assertTrue(222L in ids && 223L in ids)
    }

    @Test
    fun `波前账-到期回收`() {
        val e = FxEngine()
        e.flashStar(231L, 9.0f) // 宣判档寿命 3.8s
        assertEquals(1, e.waveCount)
        repeat(379) { e.step(0.01f) } // 3.79s
        assertEquals(1, e.waveCount)
        repeat(10) { e.step(0.01f) } // 3.89s > 寿命
        assertEquals(0, e.waveCount) // swap-remove 回收=回零成本常态
    }

    @Test
    fun `波前账-零幅度零星防御与直入口`() {
        val e = FxEngine()
        e.spawnGravityWave(0L, 6f, 3f)
        e.spawnGravityWave(241L, 0f, 3f)
        assertEquals(0, e.waveCount)
        // 直入口（非 flashStar 档位路径——R327 跃迁 diff 未来直消费预留）
        e.spawnGravityWave(242L, 4.5f, 2.8f)
        assertEquals(1, e.waveCount)
        assertEquals(4.5f, e.waveAmpPx[0], 1e-3f)
        assertEquals(2.8f, e.waveLifeSec[0], 1e-3f)
    }

    // ==================== R231 纵深飞行（dive 轴） ====================

    @Test
    fun `纵深-diveBy前进后退与dist_eff钳位`() {
        // 前进 1000：dist_eff 1650→650
        val fwd = TasteFlight.diveBy(cam0, 1000f)
        assertEquals(650f, TasteFlight.camDist(fwd), 0.01f)
        assertEquals(1000f, fwd.dive, 0.01f)
        // 后退 5000：dist_eff 6650 → 钳到后界
        val back = TasteFlight.diveBy(cam0, -5000f)
        assertEquals(TasteFlight.DIVE_DIST_BACK, TasteFlight.camDist(back), 0.01f)
        // 狂前进：dist_eff 钳到前界（**负值=可穿越云团**——被星包围的语义锚）
        val deep = TasteFlight.diveBy(cam0, 1e6f)
        assertEquals(TasteFlight.DIVE_DIST_FRONT, TasteFlight.camDist(deep), 0.01f)
        // dive=0 恒在界内：钳位无操作（旧语义零回归）
        assertEquals(cam0, TasteFlight.diveBy(cam0, 0f))
    }

    @Test
    fun `纵深-dive过零后原远带星变近星`() {
        // dive 2200 > distOf(1)=1650 → dist_eff=-550：相机已飞过云团中心
        val camDeep = cam0.copy(dive = 2200f)
        assertEquals(-550f, TasteFlight.camDist(camDeep), 0.01f)
        // z=+700（云团远带后方）：d=150 → 相机前方且 persp 大（近星=大标记）
        // R241：depthAhead 带天体 y（y=cam.y=500 时与旧 z 口径同判）
        assertTrue(TasteFlight.depthAhead(500f, 700f, camDeep))
        TasteFlight.project(500f, 500f, 700f, camDeep, 1650f, 540f, 1200f, out)
        assertTrue("穿越后原远带星应成近星（persp=${out[2]}）", out[2] > 1f)
        // z=-100（原近带）：在相机背后 → 剔除
        assertFalse(TasteFlight.depthAhead(500f, -100f, camDeep))
    }

    @Test
    fun `纵深-dive为0时depthAhead对全图恒真`() {
        // R231 前口径锚：无纵深飞行时剔除永不触发（渲染/命中零行为变化）
        // R241：签名带 y（y=cam.y 时与旧 z 口径逐位同判）
        val camMax = FlightCam(500f, 500f, TasteFlight.ZOOM_MAX, 0f)
        val camMin = FlightCam(500f, 500f, TasteFlight.ZOOM_MIN, 0f)
        for (z in floatArrayOf(-500f, -200f, 0f, 200f, 500f)) {
            assertTrue(TasteFlight.depthAhead(500f, z, cam0))
            assertTrue(TasteFlight.depthAhead(500f, z, camMax))
            assertTrue(TasteFlight.depthAhead(500f, z, camMin))
        }
    }

    @Test
    fun `纵深-FOV只依赖dive捏合恒一`() {
        // dive=0：任意 zoom 下 fovScale 恒等 1（「表观倍率=zoom」语义零回归）
        assertEquals(1f, TasteFlight.fovScale(0f), 1e-6f)
        for (zoom in floatArrayOf(0.1f, 1f, 5f, 10f)) {
            val cam = FlightCam(500f, 500f, zoom, 0f)
            assertEquals(TasteFlight.focalOf(1f, zoom), TasteFlight.focalEff(1f, cam), 0.01f)
        }
        // 俯冲：焦距缩短（广角=速度感），钳位下界
        val fwd = TasteFlight.fovScale(1400f)
        assertTrue("俯冲广角档（0.78..1）：$fwd", fwd in 0.78f..1f)
        assertTrue(TasteFlight.fovScale(1e6f) >= TasteFlight.FOV_SCALE_MIN)
        // 后拉：微缩焦，钳位上界
        val back = TasteFlight.fovScale(-2000f)
        assertTrue("后拉伸焦档（1..1.2）：$back", back in 1f..1.2f)
        assertTrue(TasteFlight.fovScale(-1e6f) <= TasteFlight.FOV_SCALE_MAX)
        // 单调：dive 越深焦距越短
        assertTrue(TasteFlight.fovScale(500f) < TasteFlight.fovScale(100f))
    }

    @Test
    fun `纵深-透视增强幂口径`() {
        // persp=1 恒等（zoom=1 平面语义零漂移）
        assertEquals(6f, TasteFlight.sizeOf(6f, 1f), 1e-4f)
        // 远星（persp<1）比旧口径更小、近星（persp>1）更大
        assertTrue(TasteFlight.sizeOf(6f, 0.7f) < 6f * 0.7f)
        assertTrue(TasteFlight.sizeOf(6f, 1.3f) > 6f * 1.3f)
        assertEquals(6f * 2f.pow(TasteFlight.PERSP_EXP), TasteFlight.sizeOf(6f, 2f), 1e-3f)
        // 与旧口径（×persp）同向单调（雾/微星合并阈值语义连续）
        assertTrue(TasteFlight.sizeOf(6f, 0.6f) < TasteFlight.sizeOf(6f, 0.9f))
        assertTrue(TasteFlight.sizeOf(6f, 0.9f) < TasteFlight.sizeOf(6f, 1.4f))
    }

    @Test
    fun `纵深-飞行速率换算`() {
        // R237 真机收口：一屏高上推 = 1.5×D0 世界单位（原 1.0——用户实锤感知迟滞）
        assertEquals(TasteFlight.FLIGHT_DIVE_PER_SCREEN_H * TasteFlight.D0, 1000f * TasteFlight.divePerPx(1000f), 0.01f)
        assertEquals(TasteFlight.FLIGHT_DIVE_PER_SCREEN_H * TasteFlight.D0 * 0.5f, 1000f * TasteFlight.divePerPx(2000f), 0.01f)
    }

    @Test
    fun `纵深-运镜tDive收敛与单指原语义`() {
        // 双击运镜（τ=0.9s）：x/y/zoom/dive 四轴收敛到位
        var cam = cam0
        val tau = 0.9f
        val dt = 1 / 60f
        var steps = 0
        while (steps < 3600 && !TasteFlight.focusArrived(cam, 700f, 300f, 2f, 1600f)) {
            val k = 1f - kotlin.math.exp(-dt / tau)
            cam = TasteFlight.approach(cam, 700f, 300f, 2f, k, 1600f)
            steps++
        }
        assertTrue("60s 内应到位（实际 ${steps} 帧）", TasteFlight.focusArrived(cam, 700f, 300f, 2f, 1600f))
        assertEquals(1600f, cam.dive, 30f)
        // tDive=null：纵深轴不动（单指点星原语义锚）
        var cam2 = cam0.copy(dive = 555f)
        repeat(50) { cam2 = TasteFlight.approach(cam2, 700f, 300f, 2f, 0.1f) }
        assertEquals(555f, cam2.dive, 0.01f)
        // tDive 旧调用口径（不传）：focusArrived 不检查纵深轴
        assertTrue(TasteFlight.focusArrived(cam2, cam2.x, cam2.y, cam2.zoom))
    }

    @Test
    fun `纵深-dive下惯性方向一致`() {
        // 深潜态（dist_eff 保正下限）：fling 与 pan 方向严格一致的 R210 锚不破
        val camD = cam0.copy(dive = 1200f)
        val vel = FloatArray(2)
        TasteFlight.flingVelFromPan(camD, 100f, 50f, 1f, vel)
        val drifted = TasteFlight.drift(camD, vel[0], vel[1], 1f / 60f)
        val panned = TasteFlight.panBy(camD, 100f, 50f, 1f)
        assertEquals(panned.x, drifted.x, 0.001f)
        assertEquals(panned.y, drifted.y, 0.001f)
    }

    // ==================== R241 俯仰（pitch 轴——0/±90/180 四向锚） ====================

    @Test
    fun `俯仰-pitch为0投影逐位恒等旧式`() {
        // 显式 pitchDeg=0 与旧构造：投影三输出全同（零回归锚——契约只加不改）
        val explicit = FlightCam(500f, 500f, 1f, 0f, 0f, 0f)
        val pts = listOf(Triple(500f, 500f, 0f), Triple(650f, 400f, 160f), Triple(430f, 620f, -160f))
        for ((x, y, z) in pts) {
            TasteFlight.project(x, y, z, cam0, 1650f, 540f, 1200f, out)
            TasteFlight.project(x, y, z, explicit, 1650f, 540f, 1200f, out2)
            assertEquals(out[0], out2[0], 1e-4f)
            assertEquals(out[1], out2[1], 1e-4f)
            assertEquals(out[2], out2[2], 1e-4f)
        }
    }

    @Test
    fun `俯仰-90度头顶正上方点落屏心`() {
        // 仰头 90°（pitch=+90=棚顶）：相机正上方 (y=cam.y+H, zc=0) 的星落屏心，
        // persp=深度 H（头顶星的距离语义）；脚下点在背后（低头才看得见）
        val cam = TasteFlight.pitchBy(cam0, 90f)
        val h = 700f
        val zAbove = -TasteFlight.camDist(cam0) // zc = z + dist = 0（正上方）
        TasteFlight.project(500f, cam0.y + h, zAbove, cam, 1650f, 540f, 1200f, out)
        assertEquals(540f, out[0], 0.01f)
        assertEquals(1200f, out[1], 0.01f)
        assertEquals(TasteFlight.D0 / h, out[2], 0.01f)
        // 头顶点在前方（旧 z 口径会错裁成背后——R241 修法核心锚）；脚下点剔除
        assertTrue(TasteFlight.depthAhead(cam0.y + h, zAbove, cam))
        assertFalse(TasteFlight.depthAhead(cam0.y - h, zAbove, cam))
    }

    @Test
    fun `俯仰-负90度脚下正下方点落屏心`() {
        // 低头 90°（pitch=−90=地面）：相机正下方 (y=cam.y−H, zc=0) 的星落屏心
        val cam = TasteFlight.pitchBy(cam0, -90f)
        val h = 700f
        val zAbove = -TasteFlight.camDist(cam0)
        TasteFlight.project(500f, cam0.y - h, zAbove, cam, 1650f, 540f, 1200f, out)
        assertEquals(540f, out[0], 0.01f)
        assertEquals(1200f, out[1], 0.01f)
        assertEquals(TasteFlight.D0 / h, out[2], 0.01f)
        assertTrue(TasteFlight.depthAhead(cam0.y - h, zAbove, cam))
        assertFalse(TasteFlight.depthAhead(cam0.y + h, zAbove, cam))
    }

    @Test
    fun `俯仰-180度身后点回望落屏心`() {
        // 回望 180°：原相机背后的点 (zc=−D) 落屏心（星图球面化的过顶锚）
        val cam = TasteFlight.pitchBy(cam0, 180f)
        val d = 800f
        TasteFlight.project(500f, 500f, -d - TasteFlight.camDist(cam0), cam, 1650f, 540f, 1200f, out)
        assertEquals(540f, out[0], 0.01f)
        assertEquals(1200f, out[1], 0.01f)
        assertEquals(TasteFlight.D0 / d, out[2], 0.01f)
        // 原正前方点 (zc=+D) 到了背后 → 剔除
        assertFalse(TasteFlight.depthAhead(500f, d - TasteFlight.camDist(cam0), cam))
    }

    @Test
    fun `俯仰-90度原正前星沉到屏幕下方`() {
        // 仰头时原「正前方」的墙沉到视野下方（v>0=屏下方）——用户「墙仰到棚顶」
        // 手势过程中的连续扫掠方向锚
        val cam = TasteFlight.pitchBy(cam0, 90f)
        TasteFlight.project(500f, 500f, 0f, cam, 1650f, 540f, 1200f, out)
        assertEquals(540f, out[0], 0.01f) // x 轴与俯仰无关
        assertTrue("原正前星应沉到屏下方（v=${out[1] - 1200f}）", out[1] > 1200f)
    }

    @Test
    fun `俯仰-pitchBy恒等与wrap`() {
        assertEquals(cam0, TasteFlight.pitchBy(cam0, 0f))
        // 350+20→10（wrap 0..360，长手势浮点不累积）
        assertEquals(10f, TasteFlight.pitchBy(FlightCam(0f, 0f, 1f, 0f, 0f, 350f), 20f).pitchDeg, 0.001f)
        // 全周 360°：wrap 后投影逐位复原（连续旋转=球面化语义）
        val full = TasteFlight.pitchBy(cam0, 360f)
        assertEquals(cam0.pitchDeg, full.pitchDeg, 0.001f)
        TasteFlight.project(650f, 400f, 160f, cam0, 1650f, 540f, 1200f, out)
        TasteFlight.project(650f, 400f, 160f, full, 1650f, 540f, 1200f, out2)
        assertEquals(out[0], out2[0], 0.01f)
        assertEquals(out[1], out2[1], 0.01f)
        // pitchBy 只动俯仰轴（x/y/zoom/roll/dive 全保持）
        val c = TasteFlight.pitchBy(cam0.copy(dive = 123f), 45f)
        assertEquals(123f, c.dive, 0f)
        assertEquals(500f, c.x, 0f)
        assertEquals(500f, c.y, 0f)
    }

    @Test
    fun `俯仰-速率换算`() {
        // 一满屏上滑 = 180°（半屏冲程把星图墙仰到棚顶）
        assertEquals(TasteFlight.PITCH_PER_SCREEN_H, 1000f * TasteFlight.pitchPerPx(1000f), 0.01f)
        assertEquals(TasteFlight.PITCH_PER_SCREEN_H * 0.5f, 1000f * TasteFlight.pitchPerPx(2000f), 0.01f)
    }

    @Test
    fun `俯仰-pan不感知pitch`() {
        // pan=世界 XY 平移（任务书「修复方向后不变」口径）：与 pitch 无关逐位同果
        val panned = TasteFlight.panBy(cam0, 100f, 60f, 1f)
        val pitched = cam0.copy(pitchDeg = 45f)
        val pannedP = TasteFlight.panBy(pitched, 100f, 60f, 1f)
        assertEquals(panned.x, pannedP.x, 0.001f)
        assertEquals(panned.y, pannedP.y, 0.001f)
    }

    @Test
    fun `俯仰-zoomAt俯仰态质心锚精确`() {
        // 俯仰 30°：z=0 星钉在 pivot——捏合 3 倍后屏位不动（R210 锚的俯仰扩展）
        val pitched = TasteFlight.pitchBy(cam0, 30f)
        val starX = 600f
        val starY = 450f
        TasteFlight.project(starX, starY, 0f, pitched, 1650f, 540f, 1200f, out)
        val pivotX = out[0]
        val pivotY = out[1]
        val cam = TasteFlight.zoomAt(pitched, 3f, pivotX, pivotY, 1f, 540f, 1200f)
        assertEquals(3f, cam.zoom, 0f)
        assertEquals(30f, cam.pitchDeg, 0.01f) // 缩放绝不引入俯仰分量
        TasteFlight.project(starX, starY, 0f, cam, TasteFlight.focalOf(1f, cam.zoom), 540f, 1200f, out)
        assertEquals(pivotX, out[0], 0.1f)
        assertEquals(pivotY, out[1], 0.1f)
    }

    @Test
    fun `俯仰-zoomAt掠射退化不炸`() {
        // pitch=90 + 屏心 pivot：射线与参考面平行（分母=0）→ 锚退化纯缩放
        // （不 NaN 不抛——沿 R231 深潜态回退先例）
        val pitched = TasteFlight.pitchBy(cam0, 90f)
        val cam = TasteFlight.zoomAt(pitched, 2f, 540f, 1200f, 1f, 540f, 1200f)
        assertEquals(2f, cam.zoom, 0f)
        assertEquals(pitched.x, cam.x, 0f)
        assertEquals(pitched.y, cam.y, 0f)
        assertEquals(90f, cam.pitchDeg, 0.01f)
        // 掠射后投影仍可用（无 NaN）
        TasteFlight.project(600f, 450f, 0f, cam, 1650f, 540f, 1200f, out)
        assertTrue(out[0].isFinite() && out[1].isFinite() && out[2].isFinite())
    }

    @Test
    fun `俯仰-运镜tPitch最短角差收敛`() {
        // 350°→0° 走 +10° 方向（不绕 350° 远路）；到位判定含俯仰容差
        var cam = FlightCam(500f, 500f, 1f, 0f, 0f, 350f)
        cam = TasteFlight.approach(cam, 500f, 500f, 1f, 0.5f, null, 0f)
        assertEquals(355f, cam.pitchDeg, 0.01f)
        val dt = 1 / 60f
        var steps = 0
        while (steps < 600 && !TasteFlight.focusArrived(cam, 500f, 500f, 1f, null, 0f)) {
            val k = 1f - kotlin.math.exp(-dt / 0.9f)
            cam = TasteFlight.approach(cam, 500f, 500f, 1f, k, null, 0f)
            steps++
        }
        assertTrue("10s 内俯仰应回平到位（实际 ${cam.pitchDeg}°）", TasteFlight.focusArrived(cam, 500f, 500f, 1f, null, 0f))
        // tPitch=null：俯仰保持不动（旧调用口径全兼容）
        var cam2 = cam0.copy(pitchDeg = 123.4f)
        repeat(50) { cam2 = TasteFlight.approach(cam2, 700f, 300f, 2f, 0.1f) }
        assertEquals(123.4f, cam2.pitchDeg, 0.01f)
        assertTrue(TasteFlight.focusArrived(cam2, cam2.x, cam2.y, cam2.zoom))
    }

    @Test
    fun `俯仰-gestureStep与diveBy不串俯仰`() {
        // pan/zoom/roll 手势与 dive 轴推进都绝不引入俯仰分量（轴解耦锚）
        val pitched = cam0.copy(pitchDeg = 45f, dive = 100f)
        val g = TasteFlight.gestureStep(pitched, 10f, 10f, 1.5f, 5f, 700f, 1300f, 1f, 540f, 1200f)
        assertEquals(45f, g.pitchDeg, 0f)
        assertEquals(100f, g.dive, 0f)
        val dived = TasteFlight.diveBy(pitched, 500f)
        assertEquals(45f, dived.pitchDeg, 0f)
        assertEquals(600f, dived.dive, 0f)
    }
}

/** 引擎内部数组/状态的只读快照口（测试专用访问面，主代码走渲染器直读） */
private fun FxEngine.bxSnapshot(): FloatArray = bx
private fun FxEngine.edgeSnapshot(): IntArray = flowEdge
private fun FxEngine.tSnapshot(): FloatArray = flowT
private fun FxEngine.meteorTSnapshot(): Float = meteorT
private fun FxEngine.meteorSegSnapshot(): Int = meteorSeg
private fun FxEngine.trailSizeSnapshot(): Int = trailSize
private fun FxEngine.trailCapSnapshot(): Int = trailCap
private fun FxEngine.cometYSnapshot(): Float = cometY
private fun FxEngine.cometLifeSnapshot(): Float = cometLife
private fun FxEngine.cometXSnapshot(): Float = cometX
private fun FxEngine.hasFlash(id: Long): Boolean = starFlashes.containsKey(id)
private fun FxEngine.timeSecSnapshot(): Float = timeSec
