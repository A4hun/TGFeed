package dev.tgfeed.core.td

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R283 SniffLedger（嗅探去重账本）单测：区间合并/重叠去重/散点隔离/双 cap 淘汰/
 * 跨车道独立/异常坐标——互链图谱深化的幂等闸行为锚。
 */
class SniffLedgerTest {

    @Test
    fun `新区间覆盖与未覆盖`() {
        val l = SniffLedger()
        l.markRange(-100, 101, 150) // 页级闭区间
        assertFalse(l.isNew(-100, 101))
        assertFalse(l.isNew(-100, 125))
        assertFalse(l.isNew(-100, 150))
        assertTrue(l.isNew(-100, 100))
        assertTrue(l.isNew(-100, 151))
    }

    @Test
    fun `页区间乱序自适应（新先旧后传参）`() {
        val l = SniffLedger()
        l.markRange(-100, 150, 101) // fetchFromChannel 调用形态：first=最新 last=最旧
        assertFalse(l.isNew(-100, 101))
        assertFalse(l.isNew(-100, 150))
    }

    @Test
    fun `翻页重叠只嗅探增量`() {
        val l = SniffLedger()
        l.markRange(-100, 101, 150) // 第一页
        // 第二页与第一页半重叠（翻页回拉场景）
        assertTrue(l.isNew(-100, 51))
        assertFalse(l.isNew(-100, 101))
        l.markRange(-100, 51, 120)
        // 两段重叠合并成一段
        assertEquals(1, l.intervalCount(-100))
        assertFalse(l.isNew(-100, 60))
        assertFalse(l.isNew(-100, 150))
    }

    @Test
    fun `相邻区间合并（gap=1）`() {
        val l = SniffLedger()
        l.markRange(-100, 101, 150)
        l.markRange(-100, 151, 200) // 紧邻下一段（连续翻页常态）
        assertEquals(1, l.intervalCount(-100))
        assertFalse(l.isNew(-100, 175))
    }

    @Test
    fun `不相交区间各自保留`() {
        val l = SniffLedger()
        l.markRange(-100, 101, 150)
        l.markRange(-100, 201, 250) // 跳页（gap>1）
        assertEquals(2, l.intervalCount(-100))
        assertTrue(l.isNew(-100, 175)) // 洞中消息未嗅探——不得谎报覆盖
    }

    @Test
    fun `散点不并区间（评论线程语义）`() {
        val l = SniffLedger()
        l.markMessage(-200, 10)
        l.markMessage(-200, 30)
        assertFalse(l.isNew(-200, 10))
        assertFalse(l.isNew(-200, 30))
        // 散点之间的 id（邻线程消息未过手）不得被覆盖
        assertTrue(l.isNew(-200, 20))
        // 散点不形成区间
        assertEquals(0, l.intervalCount(-200))
        assertEquals(2, l.singleCount(-200))
    }

    @Test
    fun `区间吞并散点（账本卫生）`() {
        val l = SniffLedger()
        l.markMessage(-200, 10)
        l.markMessage(-200, 30)
        l.markRange(-200, 1, 50) // 整段页扫覆盖散点坐标
        assertEquals(0, l.singleCount(-200)) // 散点被区间吸收清除
        assertFalse(l.isNew(-200, 10))
        assertFalse(l.isNew(-200, 30))
    }

    @Test
    fun `散点落在已有区间内不重复记账`() {
        val l = SniffLedger()
        l.markRange(-200, 1, 100)
        l.markMessage(-200, 50)
        assertEquals(0, l.singleCount(-200)) // 区间已覆盖：单点不落账
    }

    @Test
    fun `区间上限淘汰最深段（近页高频重扫区保留）`() {
        val l = SniffLedger(maxIntervalsPerLane = 4)
        // 5 段不相交区间（id 越大越新）
        l.markRange(-100, 10, 19)
        l.markRange(-100, 30, 39)
        l.markRange(-100, 50, 59)
        l.markRange(-100, 70, 79)
        l.markRange(-100, 90, 99)
        assertEquals(4, l.intervalCount(-100))
        // 最深段（10..19）被淘汰 → 有界双计；近页段保留
        assertTrue(l.isNew(-100, 15))
        assertFalse(l.isNew(-100, 95))
        assertFalse(l.isNew(-100, 75))
    }

    @Test
    fun `散点上限FIFO淘汰最旧`() {
        val l = SniffLedger(maxSinglesPerLane = 3)
        l.markMessage(-200, 1)
        l.markMessage(-200, 2)
        l.markMessage(-200, 3)
        l.markMessage(-200, 4)
        assertEquals(3, l.singleCount(-200))
        assertTrue(l.isNew(-200, 1)) // 最旧被挤掉
        assertFalse(l.isNew(-200, 4))
    }

    @Test
    fun `车道LRU上限淘汰最久未碰`() {
        val l = SniffLedger(maxLanes = 2)
        l.markRange(-100, 1, 10)
        l.markRange(-200, 1, 10)
        // 访问 -100 让它变热
        assertFalse(l.isNew(-100, 5))
        l.markRange(-300, 1, 10) // 新车道挤掉最冷的 -200
        assertEquals(2, l.laneCount())
        assertTrue(l.isNew(-200, 5))
        assertFalse(l.isNew(-100, 5))
        assertFalse(l.isNew(-300, 5))
    }

    @Test
    fun `跨车道独立（频道与讨论组 id 空间互不干扰）`() {
        val l = SniffLedger()
        l.markRange(-100, 1, 100)
        assertTrue(l.isNew(-200, 50)) // 同 id 不同车道=未嗅探
        assertFalse(l.isNew(-100, 50))
    }

    @Test
    fun `异常坐标恒新不记账`() {
        val l = SniffLedger()
        assertTrue(l.isNew(-100, 0))
        assertTrue(l.isNew(-100, -5))
        l.markMessage(-100, 0)
        l.markRange(-100, -3, 0)
        assertEquals(0, l.intervalCount(-100))
        assertEquals(0, l.singleCount(-100))
        assertTrue(l.isNew(-100, 0)) // 0 永不视为已覆盖
    }

    @Test
    fun `重复标记幂等（区间数不膨胀）`() {
        val l = SniffLedger()
        repeat(10) { l.markRange(-100, 1, 50) }
        assertEquals(1, l.intervalCount(-100))
        repeat(10) { l.markMessage(-100, 77) }
        assertEquals(1, l.singleCount(-100))
    }
}
