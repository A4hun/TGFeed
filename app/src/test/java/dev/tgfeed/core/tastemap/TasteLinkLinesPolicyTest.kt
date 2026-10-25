package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R314 TasteLinkLinesPolicy 单测（互链连线「默认不划线+焦点引力弧」政策锚）：
 * 全景网开关裁决/焦点弧三条件/alpha 与线宽细弱档钳——Canvas（drawEdges）与 GL
 * （buildEdges）双侧消费同一出处的数值锚。
 */
class TasteLinkLinesPolicyTest {

    @Test
    fun `全景连线网-开关唯一裁决`() {
        assertTrue(TasteLinkLinesPolicy.drawMesh(linkLines = true))
        assertFalse(TasteLinkLinesPolicy.drawMesh(linkLines = false))
    }

    @Test
    fun `焦点引力弧-关态且有焦点且非延时摄影才画`() {
        // 三条件全真才画
        assertTrue(TasteLinkLinesPolicy.drawFocusArcs(linkLines = false, focusId = 61L, genesisOn = false))
        // 开关开（旧网状档）=不画弧（直线网承担焦点加亮）
        assertFalse(TasteLinkLinesPolicy.drawFocusArcs(linkLines = true, focusId = 61L, genesisOn = false))
        // 无焦点星=不画
        assertFalse(TasteLinkLinesPolicy.drawFocusArcs(linkLines = false, focusId = 0L, genesisOn = false))
        // 延时摄影期=不画（同关联弧「宇宙尚未凝成」门径）
        assertFalse(TasteLinkLinesPolicy.drawFocusArcs(linkLines = false, focusId = 61L, genesisOn = true))
    }

    @Test
    fun `焦点引力弧-alpha 细弱档钳（远低于旧焦点直线 0_25~0_9）`() {
        // 弱边抬到下限
        assertEquals(0.10f, TasteLinkLinesPolicy.focusArcAlpha(0.01f), 0.001f)
        // 中档边=微抬
        assertEquals(0.22f, TasteLinkLinesPolicy.focusArcAlpha(0.2f), 0.001f)
        // 重边钳上限
        assertEquals(0.34f, TasteLinkLinesPolicy.focusArcAlpha(0.9f), 0.001f)
        // 全档恒在细弱界内
        for (a in listOf(0f, 0.05f, 0.3f, 0.6f, 1f)) {
            val v = TasteLinkLinesPolicy.focusArcAlpha(a)
            assertTrue(v >= TasteLinkLinesPolicy.FOCUS_ARC_ALPHA_MIN)
            assertTrue(v <= TasteLinkLinesPolicy.FOCUS_ARC_ALPHA_MAX)
        }
    }

    @Test
    fun `焦点引力弧-线宽细弱档钳`() {
        assertEquals(0.8f, TasteLinkLinesPolicy.focusArcStroke(0.1f), 0.001f)
        assertEquals(1.2f, TasteLinkLinesPolicy.focusArcStroke(1.2f), 0.001f)
        assertEquals(1.6f, TasteLinkLinesPolicy.focusArcStroke(9f), 0.001f)
    }

    @Test
    fun `焦点引力弧-段数与 R233 共振弧同口径（20 段）`() {
        assertEquals(20, TasteLinkLinesPolicy.FOCUS_ARC_SEGMENTS)
    }
}
