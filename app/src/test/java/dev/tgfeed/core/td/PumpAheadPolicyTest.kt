package dev.tgfeed.core.td

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R190 倍速感知泵前瞻纯函数行为单测：
 * 前瞻垫按墙钟设计（R145 的 16MB 垫按 1x 消耗算），长按 2x 期间消耗速率翻倍，
 * 同样 16MB 只换得一半墙钟垫——泵速低谷一穿就见底「加载一下」。
 * [pumpAheadBytesFor] 的使命=前瞻随消耗速率连续放大（封顶 2×），垫的墙钟时长
 * 回到 1x 设计点；常速/暂停/异常值逐字节保持 R145 基准。
 */
class PumpAheadPolicyTest {

    private val base = 16L * 1024 * 1024 // R145 常速基准 16MB

    @Test
    fun `常速与慢速保持基准_停车口径与R145逐字节一致`() {
        assertEquals(base, pumpAheadBytesFor(base, 1f))
        // 慢放/暂停语义（speed≤1）不缩前瞻：垫是「吃低谷」用的，慢速更该留着
        assertEquals(base, pumpAheadBytesFor(base, 0.5f))
        assertEquals(base, pumpAheadBytesFor(base, 0f))
    }

    @Test
    fun `倍速档随速度连续放大`() {
        assertEquals(base * 2, pumpAheadBytesFor(base, 2f)) // 长按 2x → 32MB
        assertEquals(base + base / 2, pumpAheadBytesFor(base, 1.5f)) // 面板 1.5x → 24MB
    }

    @Test
    fun `超档封顶2倍_磁盘缓存多占有限`() {
        // 假想 3x+：仍只拉双倍前瞻（存储水位闸兜底下的有限代价）
        assertEquals(base * 2, pumpAheadBytesFor(base, 3f))
        // 非有限值走防御回落基准（总线 report 已钳非有限值，此为泵侧第二道防线）
        assertEquals(base, pumpAheadBytesFor(base, Float.POSITIVE_INFINITY))
    }

    @Test
    fun `异常值回落基准_不放大不报错`() {
        assertEquals(base, pumpAheadBytesFor(base, Float.NaN))
        assertEquals(base, pumpAheadBytesFor(base, -1f))
    }
}
