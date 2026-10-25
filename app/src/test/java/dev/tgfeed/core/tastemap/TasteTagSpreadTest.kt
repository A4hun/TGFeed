package dev.tgfeed.core.tastemap

import dev.tgfeed.core.td.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R234 尘埃带纯函数单测：聚合/首发判定/时间序/留重抽样/粒子参数映射/亮度分桶/
 * now 扩展有效光标/联动可见性/确定性/空态诚实。
 */
class TasteTagSpreadTest {

    private fun item(
        key: String,
        msgId: Long,
        dateSec: Int,
        chatId: Long,
        tags: List<String>?,
        caption: String = "",
    ) = VideoItem(
        key = key, chatId = chatId, messageId = msgId, channelTitle = "频道$chatId", caption = caption,
        durationSec = 0, width = 0, height = 0, fileId = -1, sizeBytes = 0, playUri = null,
        dateEpochSec = dateSec, itemTags = tags,
    )

    private fun sample(): List<VideoItem> = listOf(
        item("a", 10, 1000, chatId = 100, tags = listOf("旅行")),
        item("b", 9, 1200, chatId = 100, tags = listOf("旅行")),
        item("c", 8, 2000, chatId = 200, tags = listOf("旅行", "日常")),
        item("d", 7, 3000, chatId = 300, tags = listOf("旅行")),
        item("e", 6, 0, chatId = 400, tags = listOf("旅行")), // 无日期：不进时间叙事
        item("f", 5, 500, chatId = 500, tags = null, caption = "今天 #旅行 攻略"), // 未解析：caption 复核
        item("g", 4, 600, chatId = 500, tags = null, caption = "无标记内容"), // 复核不过：剔除
    )

    @Test
    fun `聚合-传播档案与首发判定`() {
        val m = TasteTagSpread.build("旅行", sample())
        // 频道 500=500s（caption 复核通过 g 剔除）、100=1000、200=2000、300=3000
        assertEquals(4, m.channels.size)
        assertEquals(500L, m.sourceChatId) // 最早出现=首发
        assertEquals(500L, m.t0Sec)
        assertEquals(3000L, m.t1Sec)
        assertEquals(7, m.recalled) // 召回口径（含被复核剔除前的总数）
        assertEquals(5, m.datedSec) // 有日期参与聚合（e 无日期、g 无标记——都不算）
        val byChat = m.channels.associateBy { it.chatId }
        assertEquals(1, byChat.getValue(500L).count)
        assertEquals(2, byChat.getValue(100L).count)
        assertEquals(1000L, byChat.getValue(100L).firstSec)
        assertEquals(1200L, byChat.getValue(100L).lastSec)
        assertFalse(m.sampled)
    }

    @Test
    fun `聚合-时间序与确定性`() {
        val a = TasteTagSpread.build("旅行", sample())
        val b = TasteTagSpread.build("旅行", sample())
        assertEquals(a.channels.map { it.chatId }, b.channels.map { it.chatId })
        // firstSec 升序=传播时间序
        assertTrue("firstSec 升序", a.channels.zipWithNext().all { (x, y) -> x.firstSec <= y.firstSec })
    }

    @Test
    fun `同步频道-chatId升序破平`() {
        val items = listOf(
            item("a", 1, 100, chatId = 900, tags = listOf("舞蹈")),
            item("b", 2, 100, chatId = 100, tags = listOf("舞蹈")),
        )
        val m = TasteTagSpread.build("舞蹈", items)
        assertEquals(listOf(100L, 900L), m.channels.map { it.chatId })
        assertEquals(100L, m.sourceChatId)
    }

    @Test
    fun `留重-频道超上限保首发按条数留重`() {
        // 35 个频道（>MAX_CHANNELS=32）：首发=最早；其余按 count 留重
        val items = buildList {
            add(item("src", 1, 100, chatId = 1, tags = listOf("热词")))
            for (c in 2..36) {
                val n = if (c == 35) 5 else 1 // 频道 35 条数最多（除首发外）
                for (i in 0 until n) {
                    add(item("k$c-$i", (1000 + c * 10 + i).toLong(), 200 + c, chatId = c.toLong(), tags = listOf("热词")))
                }
            }
        }
        val m = TasteTagSpread.build("热词", items)
        assertEquals(TasteTagSpread.MAX_CHANNELS, m.channels.size)
        assertTrue(m.sampled)
        assertEquals(1L, m.sourceChatId) // 首发必留
        val ids = m.channels.map { it.chatId }
        assertTrue("留重含条数最大者 35", 35L in ids)
        // 被挤掉的=条数 1 的 chatId 最大者（单条频道按 chatId 升序留重——尾部 4 个出局）
        val dropped = (2L..36L).filter { it !in ids }
        assertEquals(listOf(32L, 33L, 34L, 36L), dropped)
        // 留重后仍按 firstSec 升序（时间序读法）
        assertTrue(m.channels.zipWithNext().all { (x, y) -> x.firstSec <= y.firstSec })
    }

    @Test
    fun `粒子-等距抽样上限与首尾锚`() {
        val items = (0 until 60).map {
            item("p$it", it.toLong(), 1000 + it * 10, chatId = 77, tags = listOf("标签"))
        }
        val m = TasteTagSpread.build("标签", items)
        val ps = m.channels.first().particleSec
        assertEquals(TasteTagSpread.MAX_PARTICLES_PER_CHANNEL, ps.size)
        assertEquals(1000L, ps.first()) // 首锚
        assertEquals(1590L, ps.last()) // 尾锚（最晚时刻）
        assertTrue("粒子升序", ps.toList().zipWithNext().all { (a, b) -> a <= b })
        assertEquals(60, m.channels.first().count) // count=抽样前口径
    }

    @Test
    fun `粒子参数-时间位置映射与边界`() {
        val u0 = TasteTagSpread.particleU(1000L, 1000L, 2000L)
        val u1 = TasteTagSpread.particleU(2000L, 1000L, 2000L)
        val uMid = TasteTagSpread.particleU(1500L, 1000L, 2000L)
        assertEquals(TasteTagSpread.U_MIN, u0, 1e-4f)
        assertEquals(TasteTagSpread.U_MAX, u1, 1e-4f)
        assertEquals((TasteTagSpread.U_MIN + TasteTagSpread.U_MAX) / 2f, uMid, 1e-4f)
        // 跨度 0（同时段爆发）=恒中段（信号本身，不假散布）
        assertEquals((TasteTagSpread.U_MIN + TasteTagSpread.U_MAX) / 2f, TasteTagSpread.particleU(123L, 100L, 100L), 1e-4f)
        // 越界 clamp
        assertEquals(TasteTagSpread.U_MIN, TasteTagSpread.particleU(500L, 1000L, 2000L), 1e-4f)
        assertEquals(TasteTagSpread.U_MAX, TasteTagSpread.particleU(3000L, 1000L, 2000L), 1e-4f)
    }

    @Test
    fun `亮度-近新远旧三档分桶`() {
        val t0 = 0L
        val t1 = 300L
        assertEquals(0, TasteTagSpread.alphaBucketOf(10L, t0, t1)) // 旧
        assertEquals(1, TasteTagSpread.alphaBucketOf(150L, t0, t1))
        assertEquals(2, TasteTagSpread.alphaBucketOf(290L, t0, t1)) // 新=最亮
        // 单点传播=最亮档
        assertEquals(TasteTagSpread.ALPHA_BUCKETS - 1, TasteTagSpread.alphaBucketOf(5L, 100L, 100L))
    }

    @Test
    fun `有效光标-now扩展线性映射`() {
        val spanMin = 1_000_000L
        val now = 2_000_000L
        assertEquals(spanMin, TasteTagSpread.effCursorMs(spanMin, 0f, now))
        assertEquals(now, TasteTagSpread.effCursorMs(spanMin, 1f, now))
        assertEquals(1_500_000L, TasteTagSpread.effCursorMs(spanMin, 0.5f, now))
        // t01 越界 clamp
        assertEquals(now, TasteTagSpread.effCursorMs(spanMin, 1.5f, now))
        assertEquals(spanMin, TasteTagSpread.effCursorMs(spanMin, -1f, now))
        // spanMin ≥ now 的退化（未来量程）：range 钳 0
        assertEquals(now, TasteTagSpread.effCursorMs(now, 0.5f, now))
    }

    @Test
    fun `联动-带到达与粒子可见`() {
        // firstSec=100（→100_000ms）
        assertTrue(TasteTagSpread.bandArrived(100L, 100_000L))
        assertTrue(TasteTagSpread.bandArrived(100L, 200_000L))
        assertFalse(TasteTagSpread.bandArrived(100L, 99_999L))
        assertTrue(TasteTagSpread.particleVisible(50L, 50_000L))
        assertFalse(TasteTagSpread.particleVisible(50L, 49_999L))
    }

    @Test
    fun `空态-无成员与空输入`() {
        val m = TasteTagSpread.build("不存在", emptyList())
        assertTrue(m.channels.isEmpty())
        assertEquals(0L, m.sourceChatId)
        val m2 = TasteTagSpread.build("旅行", listOf(item("x", 1, 0, chatId = 1, tags = listOf("别的"))))
        assertTrue(m2.channels.isEmpty())
        assertEquals(1, m2.recalled) // 有召回但无成员：诚实记账
        assertEquals(0, m2.datedSec)
    }

    @Test
    fun `成员复核-itemTags精确与caption回退`() {
        assertTrue(TasteTagSpread.hasTag(item("a", 1, 1, 1, listOf("旅行")), "旅行"))
        assertFalse(TasteTagSpread.hasTag(item("b", 1, 1, 1, listOf("旅行")), "旅行团")) // 整词精确
        assertFalse(TasteTagSpread.hasTag(item("c", 1, 1, 1, emptyList()), "旅行")) // 权威空
        assertTrue(TasteTagSpread.hasTag(item("d", 1, 1, 1, null, caption = "看 #旅行 记"), "旅行"))
        assertFalse(TasteTagSpread.hasTag(item("e", 1, 1, 1, null, caption = "旅行日记无标记"), "旅行")) // 无 # 前缀不算
    }

    @Test
    fun `标签归一-井号前缀与折叠`() {
        val m = TasteTagSpread.build("＃旅行 ", sample())
        assertEquals(4, m.channels.size) // trimStart('#','＃')+lowercase 归一后同聚合
    }
}
