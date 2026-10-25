package dev.tgfeed.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R217 ⑤ 宽高比感知 span 单测（masonry v1）：
 *  - [aspectSpan] 三档判定（横 2×1 / 竖 1×2 / 方 1×1）+ 无尺寸回落 + 阈值边界；
 *  - [flowLayout] 混排回归：竖格 1×2 与方格 1×1 同行拼装（砌墙错落的核心断言）、
 *    槽位矩形互不重叠、总行数收敛。
 *  - 政策锚：[MIN_SCALE]/[MAX_SCALE] 放开域（⑥ 的 0.1~10）防手滑漂移回窄域。
 */
class GridAspectSpanTest {

    // ---------------- ① 三档判定 ----------------

    @Test
    fun `横版_16比9给2x1`() {
        assertEquals(2 to 1, aspectSpan(1920, 1080))
    }

    @Test
    fun `竖版_9比16给1x2`() {
        assertEquals(1 to 2, aspectSpan(1080, 1920))
    }

    @Test
    fun `竖长_9比20仍给1x2不超档`() {
        assertEquals(1 to 2, aspectSpan(720, 1600))
    }

    @Test
    fun `方形_1比1给1x1`() {
        assertEquals(1 to 1, aspectSpan(1080, 1080))
    }

    @Test
    fun `无尺寸_零与负回落1x1`() {
        assertEquals(1 to 1, aspectSpan(0, 0))
        assertEquals(1 to 1, aspectSpan(0, 1920))
        assertEquals(1 to 1, aspectSpan(1920, 0))
        assertEquals(1 to 1, aspectSpan(-10, 100))
    }

    // ---------------- ② 阈值边界（ASPECT_SPAN_FACTOR=1.3） ----------------

    @Test
    fun `边界_横比刚好130给方形`() {
        assertEquals(1 to 1, aspectSpan(130, 100))
    }

    @Test
    fun `边界_横比131给2x1`() {
        assertEquals(2 to 1, aspectSpan(131, 100))
    }

    @Test
    fun `边界_竖比刚好130给方形`() {
        assertEquals(1 to 1, aspectSpan(100, 130))
    }

    @Test
    fun `边界_竖比131给1x2`() {
        assertEquals(1 to 2, aspectSpan(100, 131))
    }

    @Test
    fun `政策锚_阈值与旧横版首派口径同值`() {
        assertEquals(1.3f, ASPECT_SPAN_FACTOR)
    }

    // ---------------- ③ flowLayout 混排（砌墙核心断言） ----------------

    @Test
    fun `混排_竖格与方格同行错落且互不重叠`() {
        // 序列：竖(1×2) 方 方 方（4 列画布）：第 1 行应被 竖格右半+两个方格 填满
        val spans = mapOf(
            "tall" to (1 to 2),
            "sq1" to (1 to 1),
            "sq2" to (1 to 1),
            "sq3" to (1 to 1),
        )
        val (layout, totalRows) = flowLayout(listOf("tall", "sq1", "sq2", "sq3"), spans, CANVAS_COLS)
        // 竖格占两行：其第二行让位给后续方格 = 砌墙效果（总行数 2，不是 3）
        assertEquals(2, totalRows)
        // 互不重叠（单元级位图重放校验）
        val occupied = HashSet<Pair<Int, Int>>()
        for (rect in layout.values) {
            for (dy in 0 until rect.h) {
                for (dx in 0 until rect.w) {
                    val cell = (rect.x + dx) to (rect.y + dy)
                    assertTrue("格位重叠：$cell（$rect）", occupied.add(cell))
                }
            }
        }
        // 4 列 × 2 行 = 8 单元 = 竖2 + 方3 = 5？不——竖格 2 + 三方格 3 = 5 单元，
        // 剩 3 单元空置（画布右下留白属正常流式排位），只断言占用数与总行
        assertEquals(5, occupied.size)
    }

    @Test
    fun `混排_横格2x1占双列不越画布右缘`() {
        val (layout, _) = flowLayout(listOf("wide", "a", "b"), mapOf("wide" to (2 to 1)), CANVAS_COLS)
        val wide = layout.getValue("wide")
        assertEquals(2, wide.w)
        assertTrue(wide.x + wide.w <= CANVAS_COLS)
    }

    // ---------------- ④ ⑥ 缩放域政策锚 ----------------

    @Test
    fun `政策锚_缩放域放开至0点1到10`() {
        assertEquals(0.1f, MIN_SCALE)
        assertEquals(10f, MAX_SCALE)
    }
}
