package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * R223b 互链弹簧微扰单测：确定性（同输入同输出——「同数据同布局绝不跳位」纪律）/
 * 聚拢不塌缩（相连星对距离朝自然长度收敛但不归零、云团半径不塌）/最小间距斥力
 * （重叠星对被推开）/z 轴不动（深度语义保留）/质心回中（纯平移不缩放）/
 * 无边直通（演示模式零成本）。
 */
class TasteGalaxyPerturbTest {

    private fun dist(x: FloatArray, y: FloatArray, i: Int, j: Int): Float {
        val dx = x[i] - x[j]
        val dy = y[i] - y[j]
        return sqrt(dx * dx + dy * dy)
    }

    @Test
    fun `确定性-同输入两次结果逐位相等`() {
        fun build(): Pair<FloatArray, FloatArray> {
            val x = floatArrayOf(100f, 900f, 300f, 700f, 500f)
            val y = floatArrayOf(100f, 900f, 700f, 300f, 500f)
            val edges = intArrayOf(0, 1, 2, 3) // 两条互链边
            val mask = booleanArrayOf(true, true, true, true, true)
            TasteGalaxyPerturb.perturbXy(x, y, 5, edges, 2, mask)
            return x to y
        }
        val (x1, y1) = build()
        val (x2, y2) = build()
        assertTrue(x1.contentEquals(x2))
        assertTrue(y1.contentEquals(y2))
    }

    @Test
    fun `聚拢-相连星对距离向自然长度收敛`() {
        // 两颗相连星相距 800（远超自然长度 150）：微扰后显著拉近但不到重叠
        val x = floatArrayOf(100f, 900f)
        val y = floatArrayOf(500f, 500f)
        val before = dist(x, y, 0, 1)
        TasteGalaxyPerturb.perturbXy(x, y, 2, intArrayOf(0, 1), 1, booleanArrayOf(true, true))
        val after = dist(x, y, 0, 1)
        assertTrue("相连星对应显著聚拢 before=$before after=$after", after < before * 0.75f)
        assertTrue("聚拢不塌缩（距离 > 最小间距的一半）", after > TasteGalaxyPerturb.MIN_DIST * 0.5f)
    }

    @Test
    fun `不塌缩-云团不整体缩成一点`() {
        // 3 颗星两两相连成链，其中一对本来就贴近自然长度：整体尺度应基本保持
        val x = floatArrayOf(200f, 700f, 1200f)
        val y = floatArrayOf(500f, 500f, 500f)
        val mask = booleanArrayOf(true, true, true)
        val spanBefore = dist(x, y, 0, 2)
        TasteGalaxyPerturb.perturbXy(x, y, 3, intArrayOf(0, 1, 1, 2), 2, mask)
        val spanAfter = dist(x, y, 0, 2)
        // 弱弹簧只聚拢到自然长度邻域（2×150=300 下限量级），绝不塌缩到接近 0
        assertTrue("云团跨度应保留量级 before=$spanBefore after=$spanAfter", spanAfter > 250f)
    }

    @Test
    fun `斥力-重叠星对被推开到最小间距邻域`() {
        // 两颗重叠星（第三颗远处做桥让函数进入正常路径）：斥力应把重叠对推开
        val x = floatArrayOf(500f, 500.5f, 2000f)
        val y = floatArrayOf(500f, 500.5f, 500f)
        TasteGalaxyPerturb.perturbXy(x, y, 3, intArrayOf(0, 2, 1, 2), 2, booleanArrayOf(true, true, true), rounds = 16)
        val d = dist(x, y, 0, 1)
        assertTrue("重叠星对应被推开 d=$d", d > TasteGalaxyPerturb.MIN_DIST * 0.8f)
    }

    @Test
    fun `z轴不动-微扰只改写xy`() {
        // 本契约由签名保证（无 z 参数）；此处锁定接口形态：perturbXy 不触碰 z
        // 的编译期证据=函数签名只收 x/y 两个数组。运行时验证 x/y 确实被改：
        val x = floatArrayOf(100f, 900f)
        val y = floatArrayOf(100f, 900f)
        TasteGalaxyPerturb.perturbXy(x, y, 2, intArrayOf(0, 1), 1, booleanArrayOf(true, true))
        assertTrue(abs(x[0] - 100f) > 0.01f || abs(y[0] - 100f) > 0.01f)
    }

    @Test
    fun `质心回中-口味云团掩码决定中心且纯平移`() {
        // 4 星：2 颗云团星在 (100,100)/(200,200)，2 颗未知极在 (900,900)/(950,950)
        val x = floatArrayOf(100f, 200f, 900f, 950f)
        val y = floatArrayOf(100f, 200f, 900f, 950f)
        val mask = booleanArrayOf(true, true, false, false)
        val shift = TasteGalaxyPerturb.recenterCloud(x, y, 4, mask, cx = 500f, cy = 500f)
        // 云团质心 (150,150) → 平移 (350,350)：全部星（含未知极）同步平移
        assertEquals(350f, shift[0], 0.01f)
        assertEquals(350f, shift[1], 0.01f)
        // 相对位置不变（纯平移不缩放）
        assertEquals(100f, x[1] - x[0], 0.01f)
        assertEquals(50f, x[3] - x[2], 0.01f)
        // 云团质心已到世界中心
        assertEquals(500f, (x[0] + x[1]) / 2f, 0.01f)
    }

    @Test
    fun `无边直通-零成本收场`() {
        val x = floatArrayOf(100f, 900f)
        val y = floatArrayOf(100f, 900f)
        val rms = TasteGalaxyPerturb.perturbXy(x, y, 2, IntArray(0), 0, booleanArrayOf(true, true))
        assertEquals(0f, rms, 0f)
        assertEquals(100f, x[0], 0f) // 坐标一字不动
    }

    @Test
    fun `退化防御-越界边索引被跳过不崩`() {
        val x = floatArrayOf(100f, 900f)
        val y = floatArrayOf(100f, 900f)
        // a==b 与越界索引：跳过，不抛不崩
        TasteGalaxyPerturb.perturbXy(x, y, 2, intArrayOf(0, 0, -1, 5, 0, 1), 3, booleanArrayOf(true, true))
        assertEquals(0f, TasteGalaxyPerturb.perturbXy(x, y, 1, intArrayOf(0, 0), 1, booleanArrayOf(true)), 0f)
    }

    @Test
    fun `R267稳定闸-hub星多边叠加不爆炸`() {
        // 塌方根因复现件：1 颗中枢星（60 条放射边）+ 60 颗外围星散布——旧版
        // k×0.075×d 叠加过冲（60·0.075=4.5≫2）指数发散；稳定闸后全图坐标必须有界
        val n = 61
        val x = FloatArray(n) { 500f + 300f * kotlin.math.cos(it * 6.2831853f / 60f) }
        val y = FloatArray(n) { 500f + 300f * kotlin.math.sin(it * 6.2831853f / 60f) }
        x[0] = 500f; y[0] = 500f // hub 在中心
        val edges = IntArray(120)
        for (e in 0 until 60) {
            edges[e * 2] = 0
            edges[e * 2 + 1] = e + 1
        }
        TasteGalaxyPerturb.perturbXy(x, y, n, edges, 60, BooleanArray(n) { true })
        var maxR = 0f
        for (i in 0 until n) {
            val dx = x[i] - 500f
            val dy = y[i] - 500f
            maxR = maxOf(maxR, sqrt(dx * dx + dy * dy))
        }
        // 每星每轮 ≤0.25×150=37.5u × 20 轮 = 750u 硬上界（+初值 300）；爆炸旧版此处 1e6+
        assertTrue("hub 叠加位移必须有界（实测最大半径=$maxR）", maxR < 1200f)
    }

    @Test
    fun `R267稳定闸-单边收敛域不触闸（旧行为零变化）`() {
        // 单连边收敛速度对照：位移远低于闸值时逐位结果与闸前公式一致——
        // 两星 800 距相连，首轮每端应挪 0.15×(800−150)/2=48.75u（>37.5 闸会触——
        // 本例选 400 距：0.15×250/2=18.75u 不触闸，逐位锚定公式不变）
        val x = floatArrayOf(100f, 500f)
        val y = floatArrayOf(500f, 500f)
        TasteGalaxyPerturb.perturbXy(x, y, 2, intArrayOf(0, 1), 1, booleanArrayOf(true, true), rounds = 1)
        assertEquals(100f + 18.75f, x[0], 0.01f)
        assertEquals(500f - 18.75f, x[1], 0.01f)
    }

    @Test
    fun `大池冒烟-720节点20轮毫秒级量级`() {
        // 性能纪律冒烟：720 节点 + 800 边 + 20 轮 O(n²) 应在毫秒级完成（真值由
        // 布局日志在 Default 线程取证，这里只防数量级回归——断言 < 2s）
        val n = 720
        val x = FloatArray(n) { (it * 137) % 1000 + 50f }
        val y = FloatArray(n) { (it * 311) % 1000 + 50f }
        val edges = IntArray(1600)
        for (e in 0 until 800) {
            edges[e * 2] = (e * 7) % n
            edges[e * 2 + 1] = (e * 7 + 13) % n
        }
        val mask = BooleanArray(n) { true }
        val t0 = System.nanoTime()
        TasteGalaxyPerturb.perturbXy(x, y, n, edges, 800, mask)
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        assertTrue("720 节点 20 轮微扰应毫秒级（实测 ${ms}ms）", ms < 2000.0)
    }
}
