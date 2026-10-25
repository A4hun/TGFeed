package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ManualDeepIndexPart]（R191 手动钉住深索引）纯函数单测：
 *  1. 钉住集合序列化内核（parseChatIdCsv/chatIdCsvOf——AppPrefs.deepIndexPinnedChatIds
 *     的存取路径）：坏值不崩、往返稳定、去重；
 *  2. 行状态机（频道行「深度索引」按钮台账的转移函数）：钉住请求/开爬/页进度/
 *     完成✓/取消钉——左抽屉行内 spinner/进度/打勾/回收的判定口径。
 * 档位策略（钉住恒 T0/不降档/取消钉复位）的矩阵在 IndexTierPolicyTest（档位模型
 * 唯一权威处的同套测试）；VM 实现层（请求/取消/钩子接线）为设备验证面。
 */
class ManualDeepIndexPartTest {

    // ---------------- 钉住集合序列化 ----------------

    @Test
    fun `chatIdCsv解析_正常往返与去重`() {
        // 行为：逗号分隔 chatId 往返闭环；重复项去重（集合语义）
        val ids = setOf(-1001234567890L, 42L, 0L)
        val csv = chatIdCsvOf(ids)
        assertEquals(ids, parseChatIdCsv(csv))
        // 序列化稳定序（升序）——同集合写盘字节幂等
        assertEquals(chatIdCsvOf(setOf(42L, 7L)), chatIdCsvOf(setOf(7L, 42L)))
    }

    @Test
    fun `chatIdCsv解析_坏值丢弃不崩_空串即空表`() {
        // 行为：坏值（非数字/空白）丢弃；空串/纯空白=空表（读取侧按空表重建）
        assertEquals(setOf(12L, 34L), parseChatIdCsv(" 12 , abc ,34,, "))
        assertEquals(emptySet<Long>(), parseChatIdCsv(""))
        assertEquals(emptySet<Long>(), parseChatIdCsv(" , , "))
        assertEquals(setOf(-5L), parseChatIdCsv("-5"))
    }

    // ---------------- 行状态机 ----------------

    @Test
    fun `钉住请求_无台账即建_有台账清戳重爬`() {
        // 行为：首次点按（无条目）→ pinned+building；上一轮已完成（✓ 戳）时再钉 →
        // 清掉旧完成戳开新一轮（✓ 不残留误导「这轮已完成」）
        val fresh = manualRowOnPinRequested(null)
        assertTrue(fresh.pinned)
        assertTrue(fresh.building)
        assertEquals(0L, fresh.doneAtMs)
        val again = manualRowOnPinRequested(ManualIndexRowState(pinned = true, building = false, indexedCount = 300, doneAtMs = 123L))
        assertTrue(again.pinned)
        assertTrue(again.building)
        assertEquals(0L, again.doneAtMs)
    }

    @Test
    fun `取消钉_在爬保留进度_空闲即回收`() {
        // 行为：在爬轮次照跑完（行内进度继续显示）→ pinned=false 但 building 保留；
        // 空闲态取消钉 → null=移除台账（行按钮回未钉态）
        val building = ManualIndexRowState(pinned = true, building = true, indexedCount = 120)
        val kept = manualRowOnUnpinned(building)
        assertNotNull(kept)
        assertFalse(kept!!.pinned)
        assertTrue(kept.building) // 本轮照跑完
        assertEquals(120, kept.indexedCount)
        val idle = ManualIndexRowState(pinned = true, building = false, indexedCount = 300, doneAtMs = 9L)
        assertNull(manualRowOnUnpinned(idle))
    }

    @Test
    fun `开爬清完成戳_页进度累计_完成打勾`() {
        // 行为：开爬（新一轮）清 ✓ 戳；onProgress 为累计口径直写；完成=building=false
        // + 终值计数 + ✓ 戳（manualRowShowCheck 判定为真）
        var s = manualRowOnStart(ManualIndexRowState(pinned = true, building = false, indexedCount = 100, doneAtMs = 55L))
        assertTrue(s.building)
        assertEquals(0L, s.doneAtMs)
        s = manualRowOnProgress(s, 200)
        assertTrue(s.building)
        assertEquals(200, s.indexedCount)
        s = manualRowOnDone(s, 300, nowMs = 777L)
        assertFalse(s.building)
        assertEquals(300, s.indexedCount)
        assertEquals(777L, s.doneAtMs)
        assertTrue(manualRowShowCheck(s))
    }

    @Test
    fun `完成态打勾判定_三条件缺一不可`() {
        // 行为：✓ 闪示=钉住 ∧ 不在爬 ∧ 有完成戳——非钉住（取消钉后收尾）或在爬都
        // 不显示 ✓；完成后戳被定时器清零（doneAtMs=0）回落钉住填充态
        assertFalse(manualRowShowCheck(ManualIndexRowState(pinned = false, building = false, doneAtMs = 9L)))
        assertFalse(manualRowShowCheck(ManualIndexRowState(pinned = true, building = true, doneAtMs = 9L)))
        assertFalse(manualRowShowCheck(ManualIndexRowState(pinned = true, building = false, doneAtMs = 0L)))
        assertTrue(manualRowShowCheck(ManualIndexRowState(pinned = true, building = false, doneAtMs = 9L)))
    }

    @Test
    fun `页进度负值防御_钳到零`() {
        // 行为：onProgress 累计口径防御负值（网关异常不会把行内计数打成负数）
        val s = manualRowOnProgress(ManualIndexRowState(pinned = true, building = true), -3)
        assertEquals(0, s.indexedCount)
    }
}
