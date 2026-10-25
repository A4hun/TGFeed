package dev.tgfeed.ui.components

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PhotoViewerPolicy]（R299 B2/B3 查看器/漫画模式政策纯函数）行为单测：
 * 进入阈值 / 条漫 3:1 判定 / 双击档位 / 下滑退出 / 平移夹域。
 */
class PhotoViewerPolicyTest {

    @Test
    fun `进入判定_收尾过阈才进查看器`() {
        // 清屏阈值 1.3 与查看器阈值 1.8 之间的缓冲带：想清屏的手势绝不误进查看器
        assertFalse(PhotoViewerPolicy.shouldEnterViewer(1.3f))
        assertFalse(PhotoViewerPolicy.shouldEnterViewer(1.79f))
        assertTrue(PhotoViewerPolicy.shouldEnterViewer(1.8f))
        assertTrue(PhotoViewerPolicy.shouldEnterViewer(4f))
    }

    @Test
    fun `条漫判定_3比1含边界_未知尺寸不误判`() {
        assertTrue(PhotoViewerPolicy.isLongStrip(100, 300))  // 恰 3:1 判中（含边界）
        assertTrue(PhotoViewerPolicy.isLongStrip(100, 301))
        assertFalse(PhotoViewerPolicy.isLongStrip(100, 299)) // 差一丝不判
        assertFalse(PhotoViewerPolicy.isLongStrip(300, 100)) // 横长不是条漫
        assertFalse(PhotoViewerPolicy.isLongStrip(0, 500))   // 未知宽不误判
        assertFalse(PhotoViewerPolicy.isLongStrip(500, 0))
        // 位图口径（宽/高 aspect 入参）：同阈值同结论
        assertTrue(PhotoViewerPolicy.isLongStrip(1f / 3f))
        assertFalse(PhotoViewerPolicy.isLongStrip(1f / 2.9f))
    }

    @Test
    fun `双击档位_1x进2点5x_放大态回1x`() {
        assertEquals(PhotoViewerPolicy.DOUBLE_TAP_SCALE, PhotoViewerPolicy.doubleTapTarget(1f))
        assertEquals(1f, PhotoViewerPolicy.doubleTapTarget(2.5f))
        assertEquals(1f, PhotoViewerPolicy.doubleTapTarget(1.5f))
    }

    @Test
    fun `下滑退出_仅1x态且过阈`() {
        val h = 2000f
        assertTrue(PhotoViewerPolicy.shouldDismiss(h * 0.23f, h, 1f))   // 过阈
        assertFalse(PhotoViewerPolicy.shouldDismiss(h * 0.21f, h, 1f))  // 未过阈
        assertFalse(PhotoViewerPolicy.shouldDismiss(h * 0.5f, h, 2f))   // 放大态竖向=平移语义
        assertFalse(PhotoViewerPolicy.shouldDismiss(100f, 0f, 1f))      // 高度未知不炸
    }

    @Test
    fun `平移夹域_1x锁死_放大态内容边缘不过界`() {
        // 1x：内容锁死居中（夹域恒 0）
        assertEquals(0f, PhotoViewerPolicy.panClampAxis(800f, 1000f, 1f))
        // 放大态：内容带 800 放大 2x=1600，窗口 1000 → 夹域 (1600-1000)/2=300
        assertEquals(300f, PhotoViewerPolicy.panClampAxis(800f, 1000f, 2f))
        // 缩小侧（scaled<window）：夹域 0 居中
        assertEquals(0f, PhotoViewerPolicy.panClampAxis(400f, 1000f, 2f))
        // clampPan 双轴合成
        val clamped = PhotoViewerPolicy.clampPan(Offset(500f, -500f), 800f, 800f, 1000f, 1000f, 2f)
        assertEquals(300f, clamped.x)
        assertEquals(-300f, clamped.y)
    }
}
