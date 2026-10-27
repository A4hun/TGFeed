package dev.tgfeed.uitest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tgfeed.ui.CellRect
import dev.tgfeed.ui.UiTestTags
import dev.tgfeed.ui.aspectSpan
import dev.tgfeed.ui.flowLayout
import dev.tgfeed.ui.justifiedLayout
import dev.tgfeed.ui.justifyAspect
import dev.tgfeed.uitest.UiTestKit.pxBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * R415 分屏按行装箱回归闸：按 GridScreen 同口径（justifiedLayout 开 / flowLayout 关）
 * 把格位换算成真实像素渲染，断言同行等高、整行铺满、尾行不拉伸、关闭回等分。
 */
@RunWith(AndroidJUnit4::class)
class GridJustifiedRowsTest {
    @get:Rule
    val rule = createComposeRule()

    private val cols = 3
    private val cellPx = 300f

    /** 不同宽高比的假视频（横、竖、方、超宽、超竖） */
    private val sizes = listOf(1920 to 1080, 720 to 1280, 1000 to 1000, 2400 to 800, 600 to 1600, 1280 to 720)
    private val ids = sizes.indices.map { "v${it}" }

    @Composable
    private fun Canvas(rects: Map<String, CellRect>) {
        val density = LocalDensity.current
        Box(Modifier.testTag(UiTestTags.GRID_CANVAS).size(with(density) { (cellPx * cols).toDp() }, 4000.dp)) {
            rects.forEach { (id, r) ->
                Box(
                    Modifier
                        .offset(with(density) { (r.x * cellPx).toDp() }, with(density) { (r.y * cellPx).toDp() })
                        .size(with(density) { (r.w * cellPx).toDp() }, with(density) { (r.h * cellPx).toDp() })
                        .testTag(UiTestTags.gridCell(id)),
                )
            }
        }
    }

    private fun justified(n: Int): Map<String, CellRect> {
        val sub = ids.take(n)
        val aspects = sub.associateWith { id -> sizes[ids.indexOf(id)].let { justifyAspect(it.first, it.second) } }
        return justifiedLayout(sub, aspects, cols).first
    }

    /** 像素级：渲染 6 格（含尾行），量真实节点边界 */
    @Test
    fun justifiedRowsEqualHeightAndFillWidth() {
        val rects = justified(6)
        rule.setContent { Canvas(rects) }
        assertEquals(6, rects.size)
        val canvasLeft = rule.pxBounds(UiTestTags.GRID_CANVAS).left
        val fullW = cellPx * cols
        val rows = rects.entries.groupBy { it.value.y }.toSortedMap()
        assertTrue("至少两行", rows.size >= 2)
        val lastY = rows.lastKey()
        for ((y, cells) in rows) {
            val b = cells.map { rule.pxBounds(UiTestTags.gridCell(it.key)) }
            val h0 = b.first().height
            b.forEach { assertTrue("同行等高", abs(it.height - h0) <= 1.5f) }
            val left = b.minOf { it.left } - canvasLeft
            val right = b.maxOf { it.right } - canvasLeft
            assertTrue("行首贴左", abs(left) <= 1.5f)
            if (y != lastY) {
                assertTrue("整行铺满", abs(right - fullW) <= 1.5f)
            } else if (abs(right - fullW) > 1.5f) {
                assertTrue("尾行不拉伸", abs(cells.first().value.h - 1f) <= 0.01f)
                assertTrue("尾行不越界", right <= fullW + 1.5f)
            }
        }
    }

    /** 几何级：3~6 格逐一核对（同行等高/非尾行铺满/尾行不拉伸） */
    @Test
    fun justifiedRowsGeometryForThreeToSix() {
        for (n in 3..6) {
            val rows = justified(n).values.groupBy { it.y }.toSortedMap()
            val lastY = rows.lastKey()
            rows.forEach { (y, cells) ->
                cells.forEach { assertEquals(cells.first().h, it.h, 0.001f) }
                val width = cells.sumOf { it.w.toDouble() }
                if (y != lastY) {
                    assertEquals(cols.toDouble(), width, 0.01)
                } else {
                    assertTrue("尾行 n=${n}", abs(width - cols) <= 0.01 || abs(cells.first().h - 1f) <= 0.01f)
                }
            }
        }
    }
    @Test
    fun switchOffFallsBackToEqualCells() {
        val n = 5
        val sub = ids.take(n)
        // 关闭装箱：GridScreen 走 flowLayout(用户 span 表)，默认 span 1×1 = 等分
        val rects = flowLayout(sub, emptyMap(), cols).first
        rule.setContent { Canvas(rects) }
        val b = sub.map { rule.pxBounds(UiTestTags.gridCell(it)) }
        b.forEach {
            assertTrue("等分宽", abs(it.width - cellPx) <= 1.5f)
            assertTrue("等分高", abs(it.height - cellPx) <= 1.5f)
        }
        // 对照：aspectSpan 仍可用（旧 span 推断存在且为正）
        val sp = aspectSpan(1920, 1080)
        assertTrue(sp.first >= 1 && sp.second >= 1)
    }
}
