package dev.tgfeed.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R227 画面调整策略纯函数单测：换轴镜像（方向 bug 修复核心）/ 旋转步进归一 /
 * 菜单行文案与可用性 / 当前态描述。全矩阵枚举 0/90/180/270 × 镜像开合。
 */
class FrameAdjustPolicyTest {

    // ---------------- 镜像符号跟旋转态换轴（方向 bug 修复核心） ----------------

    @Test
    fun `偶数档镜像挂X轴屏幕左右翻`() {
        // 行为：0°/180° 时局部 X 轴 = 屏幕横轴（180° 是其反向，仍是横向）——
        // scaleX=-1、scaleY=+1（R218 原口径无旋转时本就正确；180° 同理挂 X 轴：
        // 镜像点 (-a,b) 旋 180 后 (a,-b) vs 未镜像 (-a,-b)——屏幕横向互换=左右翻）
        assertEquals(-1f, frameMirrorScaleX(0, true))
        assertEquals(-1f, frameMirrorScaleX(180, true))
        assertEquals(1f, frameMirrorScaleX(90, true))
        assertEquals(1f, frameMirrorScaleX(270, true))
        assertEquals(1f, frameMirrorScaleX(0, false))
    }

    @Test
    fun `奇数档镜像换轴到Y等效屏幕左右翻`() {
        // 行为：90°/270° 时局部 X 轴已被旋到屏幕竖向——R218 的 scaleX=-1 落到屏幕
        // 是上下翻（bug 根因）；修复=符号换到 scaleY（局部 Y 被旋到屏幕横轴）
        assertEquals(1f, frameMirrorScaleX(90, true))
        assertEquals(1f, frameMirrorScaleX(270, true))
        assertEquals(-1f, frameMirrorScaleY(90, true))
        assertEquals(-1f, frameMirrorScaleY(270, true))
        assertEquals(1f, frameMirrorScaleY(0, true))
        assertEquals(1f, frameMirrorScaleY(180, true))
        assertEquals(1f, frameMirrorScaleY(90, false))
    }

    @Test
    fun `全矩阵恒等一翻恰一轴`() {
        // 不变量：镜像开时 X/Y 符号恰有一个 -1（镜像闭时全 +1）；任何角度任何镜像态
        // 都不产生双 -1（那是 180° 旋转，不是镜像）或零 -1（镜像失效）
        for (deg in listOf(0, 90, 180, 270)) {
            for (m in listOf(true, false)) {
                val x = frameMirrorScaleX(deg, m)
                val y = frameMirrorScaleY(deg, m)
                if (m) {
                    assertTrue("deg=$deg 镜像开必恰一轴为 -1", (x == -1f) xor (y == -1f))
                } else {
                    assertEquals("deg=$deg 镜像闭全 +1", 1f, x)
                    assertEquals(1f, y)
                }
            }
        }
    }

    @Test
    fun `未归一角度先归一再换轴`() {
        // 行为：360/-90/450 等外部脏值先归一（0/270/90）再裁决——右轨 +90 步进链路
        // 历史上产生过 360 与负角，换轴判据不得被未归一值骗过
        assertEquals(-1f, frameMirrorScaleX(360, true))   // 360 ≡ 0 → X 轴
        assertEquals(-1f, frameMirrorScaleY(-90, true))   // -90 ≡ 270 → Y 轴
        assertEquals(-1f, frameMirrorScaleY(450, true))   // 450 ≡ 90 → Y 轴
    }

    // ---------------- 旋转步进 ----------------

    @Test
    fun `旋转步进归一并循环`() {
        assertEquals(90, rotateFrame(0, 90))
        assertEquals(180, rotateFrame(90, 90))
        assertEquals(0, rotateFrame(270, 90))
        assertEquals(90, rotateFrame(360, 90))   // 360+90=450 → 归一 90
        assertEquals(270, rotateFrame(0, -90))  // 向左从 0° 起 = 270°（逆时针语义）
        assertEquals(0, rotateFrame(90, -90))
        assertEquals(90, rotateFrame(180, -90)) // 180-90=90（顺归一）
        assertEquals(180, rotateFrame(180, -360)) // 整圈归零
    }

    // ---------------- 当前态与行文案 ----------------

    @Test
    fun `当前态描述四分支`() {
        assertEquals("未调整", frameAdjustStateText(0, false))
        assertEquals("已镜像", frameAdjustStateText(0, true))
        assertEquals("旋转 90°", frameAdjustStateText(90, false))
        assertEquals("旋转 180° · 已镜像", frameAdjustStateText(180, true))
    }

    @Test
    fun `恢复默认行仅非默认态可用`() {
        // 行为：默认姿态下恢复默认行禁用（如实禁用不假装可点，R201 口径）；
        // 旋转非 0 / 已镜像任一成立即可用
        assertFalse(frameAdjustRowEnabled(FrameAdjustRow.RESET, 0, false))
        assertTrue(frameAdjustRowEnabled(FrameAdjustRow.RESET, 90, false))
        assertTrue(frameAdjustRowEnabled(FrameAdjustRow.RESET, 0, true))
        assertTrue(frameAdjustRowEnabled(FrameAdjustRow.RESET, 270, true))
        // 三个动作行恒可用（姿态动作不存在不可用态）
        for (row in listOf(FrameAdjustRow.ROTATE_LEFT, FrameAdjustRow.ROTATE_RIGHT, FrameAdjustRow.MIRROR)) {
            assertTrue(frameAdjustRowEnabled(row, 0, false))
            assertTrue(frameAdjustRowEnabled(row, 270, true))
        }
    }

    @Test
    fun `镜像行文案随态翻转`() {
        assertEquals("画面左右翻转", frameAdjustRowSubtitle(FrameAdjustRow.MIRROR, 0, false))
        assertEquals("已镜像，点按恢复", frameAdjustRowSubtitle(FrameAdjustRow.MIRROR, 90, true))
    }

    @Test
    fun `恢复默认行副文案带当前态指示`() {
        assertEquals("已是默认姿态", frameAdjustRowSubtitle(FrameAdjustRow.RESET, 0, false))
        assertEquals("当前：旋转 90°", frameAdjustRowSubtitle(FrameAdjustRow.RESET, 90, false))
        assertEquals("当前：旋转 270° · 已镜像", frameAdjustRowSubtitle(FrameAdjustRow.RESET, 270, true))
    }

    @Test
    fun `非默认判据覆盖两维`() {
        assertFalse(isFrameAdjusted(0, false))
        assertTrue(isFrameAdjusted(0, true))
        assertTrue(isFrameAdjusted(90, false))
        assertFalse(isFrameAdjusted(360, false)) // 归一后为 0
        assertTrue(isFrameAdjusted(-90, false))  // 归一后为 270
    }
}
