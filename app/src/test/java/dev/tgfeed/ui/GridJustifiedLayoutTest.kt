package dev.tgfeed.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R415 按行装箱（justified rows）纯函数单测：
 * 宽高比钳制、单格、整行填满且同行等高、宽度按比例分配、尾行不拉伸、横屏扁格换算、无重叠。
 */
class GridJustifiedLayoutTest {

    private val eps = 1e-3f

    private fun rows(layout: Map<String, CellRect>): Map<Float, List<CellRect>> =
        layout.values.groupBy { it.y }

    @Test
    fun `钳制_超宽超竖与无尺寸回落`() {
        assertEquals(JUSTIFY_MAX_ASPECT, justifyAspect(4000, 500), eps)
        assertEquals(JUSTIFY_MIN_ASPECT, justifyAspect(300, 3000), eps)
        assertEquals(JUSTIFY_DEFAULT_ASPECT, justifyAspect(0, 1080), eps)
        assertEquals(JUSTIFY_DEFAULT_ASPECT, justifyAspect(1920, -1), eps)
        assertEquals(16f / 9f, justifyAspect(1920, 1080), eps)
    }

    @Test
    fun `钳制常量区间合理`() {
        assertTrue(JUSTIFY_MIN_ASPECT in 0.3f..0.7f)
        assertTrue(JUSTIFY_MAX_ASPECT in 2f..3f)
    }

    @Test
    fun `空列表_零行`() {
        val (layout, total) = justifiedLayout(emptyList(), emptyMap(), CANVAS_COLS)
        assertTrue(layout.isEmpty())
        assertEquals(0, total)
    }

    @Test
    fun `单格_尾行不拉伸按目标行高左对齐`() {
        val (layout, total) = justifiedLayout(listOf("a"), mapOf("a" to 1f), CANVAS_COLS)
        val a = layout.getValue("a")
        assertEquals(0f, a.x, eps)
        assertEquals(JUSTIFY_TARGET_ROW_H, a.h, eps)
        assertEquals(JUSTIFY_TARGET_ROW_H, a.w, eps)
        assertTrue(a.w < CANVAS_COLS)
        assertEquals(1, total)
    }

    @Test
    fun `满行_同行等高且恰好填满画布宽`() {
        val ids = (0 until 9).map { "v$it" }
        val asp = ids.withIndex().associate { (i, id) -> id to listOf(1.78f, 0.56f, 1f)[i % 3] }
        val (layout, _) = justifiedLayout(ids, asp, CANVAS_COLS)
        val rs = rows(layout).values.toList()
        // 除最后一行外每行填满
        for (r in rs.dropLast(1)) {
            val h = r.first().h
            r.forEach { assertEquals(h, it.h, eps) }
            assertEquals(CANVAS_COLS.toFloat(), r.sumOf { it.w.toDouble() }.toFloat(), 1e-2f)
        }
    }

    @Test
    fun `同行宽度按宽高比分配`() {
        val ids = listOf("w", "t", "s", "w2", "t2")
        val asp = mapOf("w" to 2f, "t" to 0.5f, "s" to 1f, "w2" to 2f, "t2" to 0.5f)
        val (layout, _) = justifiedLayout(ids, asp, CANVAS_COLS)
        val w = layout.getValue("w")
        val t = layout.getValue("t")
        if (w.y == t.y) assertEquals(4f, w.w / t.w, 1e-2f)
        for (id in ids) {
            val c = layout.getValue(id)
            assertEquals(asp.getValue(id), c.w / c.h, 1e-2f)
        }
    }

    @Test
    fun `极端比例_超宽内容钳制后独占不越界`() {
        val (layout, _) = justifiedLayout(listOf("x", "y"), mapOf("x" to 10f, "y" to 10f), CANVAS_COLS)
        for (c in layout.values) {
            assertEquals(JUSTIFY_MAX_ASPECT, c.w / c.h, 1e-2f)
            assertTrue(c.x + c.w <= CANVAS_COLS + eps)
        }
    }

    @Test
    fun `尾行_未凑满保持目标行高不放大`() {
        val ids = (0 until 5).map { "s$it" }
        val (layout, _) = justifiedLayout(ids, ids.associateWith { 1f }, CANVAS_COLS)
        // 4 个方格凑满一行（行高 1），第 5 个落尾行不拉伸
        val last = layout.getValue("s4")
        assertEquals(JUSTIFY_TARGET_ROW_H, last.h, eps)
        assertEquals(JUSTIFY_TARGET_ROW_H, last.w, eps)
        assertEquals(0f, last.x, eps)
    }

    @Test
    fun `横屏扁格_单元宽高比换算后像素比例不变`() {
        val cellAspect = 16f / 10f
        val ids = (0 until 6).map { "h$it" }
        val (layout, _) = justifiedLayout(ids, ids.associateWith { 16f / 9f }, CANVAS_COLS, cellAspect)
        for (c in layout.values) {
            // 像素宽高比 = (w × 单元宽) ÷ (h × 单元高) = w÷h × cellAspect
            assertEquals(16f / 9f, c.w / c.h * cellAspect, 1e-2f)
        }
    }

    @Test
    fun `无重叠_行纵向连续总行数向上取整`() {
        val ids = (0 until 20).map { "m$it" }
        val asp = ids.withIndex().associate { (i, id) -> id to listOf(0.5f, 2.4f, 1.33f, 0.75f)[i % 4] }
        val (layout, total) = justifiedLayout(ids, asp, CANVAS_COLS)
        val sorted = layout.values.sortedWith(compareBy({ it.y }, { it.x }))
        for (i in 1 until sorted.size) {
            val p = sorted[i - 1]
            val c = sorted[i]
            if (c.y == p.y) assertTrue(c.x >= p.x + p.w - eps) else assertTrue(c.y >= p.y + p.h - eps)
        }
        val bottom = layout.values.maxOf { it.y + it.h }
        assertEquals(kotlin.math.ceil(bottom - eps).toInt(), total)
    }
}
