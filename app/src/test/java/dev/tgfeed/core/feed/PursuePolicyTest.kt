package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PursuePolicy]（R229 追更 tab 纯策略）行为单测：
 * - **最新在最下面**（用户终审核心不变量）：卡流按 newestSec 升序——index 0=最旧、
 *   last=最新；30s 折叠新卡落底；
 * - 窗过滤（闭区间边界/无时间戳剔除）；按频道聚合+key 去重；每频道新→旧截断；
 * - 滚动窗淘汰（旧卡从顶=最旧侧落马）+ 卡数封顶（顶侧裁）；
 * - 折叠新滑入标记（新卡/有新货的卡打 freshAtMs，其余保持）。
 */
class PursuePolicyTest {

    private companion object {
        const val NOW_SEC = 1_000_000L
        const val WINDOW = 24L * 3600L
        val CUTOFF = NOW_SEC - WINDOW

        fun item(chatId: Long, msgId: Long, atSec: Long, title: String = "频道$chatId") = VideoItem(
            key = "tg:$chatId:$msgId",
            chatId = chatId,
            messageId = msgId,
            channelTitle = title,
            caption = "",
            durationSec = 0,
            width = 0,
            height = 0,
            fileId = 0,
            sizeBytes = 0,
            playUri = null,
            dateEpochSec = atSec.toInt(),
        )
    }

    // ---------------- 窗过滤 ----------------

    @Test
    fun `窗过滤闭区间_边界条目保留_出窗剔除`() {
        assertTrue(pursueItemInWindow(item(1, 1, CUTOFF), NOW_SEC, WINDOW))
        assertFalse(pursueItemInWindow(item(1, 1, CUTOFF - 1), NOW_SEC, WINDOW))
    }

    @Test
    fun `无时间戳条目剔除_无法参与时间语义`() {
        assertFalse(pursueItemInWindow(item(1, 1, 0), NOW_SEC, WINDOW))
    }

    // ---------------- 聚合与卡序（最新在底） ----------------

    @Test
    fun `卡流升序_index0最旧_滚到底最新_用户终审不变量`() {
        val old = item(-101, 1, CUTOFF + 100)
        val mid = item(-102, 1, CUTOFF + 3_600)
        val newest = item(-103, 1, NOW_SEC - 10)
        val cards = buildPursueCards(listOf(newest, mid, old), NOW_SEC, WINDOW, 8)
        assertEquals(listOf(-101L, -102L, -103L), cards.map { it.chatId })
    }

    @Test
    fun `平手按chatId稳定_可测可复现`() {
        val a = item(-202, 1, NOW_SEC - 60)
        val b = item(-201, 1, NOW_SEC - 60)
        val cards = buildPursueCards(listOf(b, a), NOW_SEC, WINDOW, 8)
        assertEquals(listOf(-202L, -201L), cards.map { it.chatId })
    }

    @Test
    fun `按频道聚合_卡内条目新到旧_跨来源key去重`() {
        val pool = listOf(
            item(-101, 3, NOW_SEC - 100), // 频道A 最新
            item(-101, 1, NOW_SEC - 300), // 频道A 旧
            item(-101, 3, NOW_SEC - 100), // 同 key 重复（跨来源合并）→ 去重
            item(-102, 5, NOW_SEC - 50),  // 频道B
        )
        val cards = buildPursueCards(pool, NOW_SEC, WINDOW, 8)
        assertEquals(2, cards.size)
        val a = cards.first { it.chatId == -101L }
        assertEquals(listOf(3L, 1L), a.items.map { it.messageId })
        assertEquals(2, a.items.size) // 重复条目没入卡
    }

    @Test
    fun `每频道条目上限_新到旧截断`() {
        val pool = (1..10).map { item(-101, it.toLong(), NOW_SEC - it * 10) }
        val cards = buildPursueCards(pool, NOW_SEC, WINDOW, 3)
        assertEquals(listOf(1L, 2L, 3L), cards.single().items.map { it.messageId }) // 最新的 3 条
    }

    @Test
    fun `出窗与无时间戳条目不进卡`() {
        val pool = listOf(
            item(-101, 1, NOW_SEC - 10),
            item(-101, 2, CUTOFF - 1), // 出窗
            item(-101, 3, 0),          // 无时间戳
        )
        val cards = buildPursueCards(pool, NOW_SEC, WINDOW, 8)
        assertEquals(1, cards.single().items.size)
    }

    @Test
    fun `bootstrap 卡 freshAtMs 恒 0_首屏整批不滑入`() {
        val cards = buildPursueCards(listOf(item(-101, 1, NOW_SEC)), NOW_SEC, WINDOW, 8)
        assertEquals(0L, cards.single().freshAtMs)
    }

    // ---------------- 滚动窗淘汰与封顶 ----------------

    @Test
    fun `滚动窗淘汰_旧卡从顶侧落马_新卡保住`() {
        val old = item(-101, 1, CUTOFF + 10)
        val fresh = item(-102, 1, NOW_SEC - 10)
        val cards = buildPursueCards(listOf(old, fresh), NOW_SEC, WINDOW, 8)
        // 时间推进 11s：old 的 newestSec 落到 cutoff 之下
        val pruned = prunePursueCards(cards, NOW_SEC + 11, WINDOW, 60)
        assertEquals(listOf(-102L), pruned.map { it.chatId })
    }

    @Test
    fun `卡数封顶从顶裁_底部最新保住`() {
        val pool = (1..8).map { i -> item(-100L - i, 1, NOW_SEC - i * 100) }
        val cards = buildPursueCards(pool, NOW_SEC, WINDOW, 8)
        val pruned = prunePursueCards(cards, NOW_SEC, WINDOW, 3)
        // 最新的三张保住（顶=最旧侧裁掉——底部=最新方向不变）
        assertEquals(listOf(-103L, -102L, -101L), pruned.map { it.chatId })
    }

    // ---------------- 30s 折叠（merge） ----------------

    @Test
    fun `折叠新频道卡落底_新滑入标记`() {
        val base = buildPursueCards(listOf(item(-101, 1, NOW_SEC - 500)), NOW_SEC, WINDOW, 8)
        val merged = mergePursueCards(
            base,
            listOf(item(-102, 1, NOW_SEC - 10)),
            NOW_SEC, WINDOW, 8, 60,
            nowMs = 123_456L,
        )
        assertEquals(listOf(-101L, -102L), merged.map { it.chatId }) // 新卡在最底
        assertEquals(123_456L, merged.last().freshAtMs)              // 新卡=滑入
        assertEquals(0L, merged.first().freshAtMs)                   // 旧卡不重播
    }

    @Test
    fun `折叠给既有卡添新货_该卡重打滑入标记且落底`() {
        val base = buildPursueCards(
            listOf(item(-101, 1, NOW_SEC - 500), item(-102, 1, NOW_SEC - 400)),
            NOW_SEC, WINDOW, 8,
        )
        // 频道A 折叠进一条更新的 → A 的 newest 超过 B → A 落底 + 滑入标记
        val merged = mergePursueCards(
            base,
            listOf(item(-101, 2, NOW_SEC - 10)),
            NOW_SEC, WINDOW, 8, 60,
            nowMs = 999L,
        )
        assertEquals(listOf(-102L, -101L), merged.map { it.chatId })
        assertEquals(999L, merged.last().freshAtMs)
        assertEquals(0L, merged.first().freshAtMs) // B 无新货：保持 0（已组合卡不重播）
        assertEquals(2, merged.last().items.size) // A 卡内 2 条
    }

    @Test
    fun `折叠无变化_卡表内容不变_fresh 标记不刷新`() {
        val base = buildPursueCards(listOf(item(-101, 1, NOW_SEC - 100)), NOW_SEC, WINDOW, 8)
        val merged = mergePursueCards(base, emptyList(), NOW_SEC, WINDOW, 8, 60, nowMs = 777L)
        assertEquals(base, merged)
    }

    @Test
    fun `折叠重复条目_key 去重不虚增计数`() {
        val base = buildPursueCards(listOf(item(-101, 1, NOW_SEC - 100)), NOW_SEC, WINDOW, 8)
        val merged = mergePursueCards(base, listOf(item(-101, 1, NOW_SEC - 100)), NOW_SEC, WINDOW, 8, 60)
        assertEquals(1, merged.single().items.size)
    }

    @Test
    fun `折叠出窗新条目不入卡`() {
        val base = buildPursueCards(listOf(item(-101, 1, NOW_SEC - 100)), NOW_SEC, WINDOW, 8)
        val merged = mergePursueCards(base, listOf(item(-102, 1, CUTOFF - 5)), NOW_SEC, WINDOW, 8, 60)
        assertEquals(listOf(-101L), merged.map { it.chatId })
    }

    @Test
    fun `空池与空卡表折叠安全`() {
        assertTrue(buildPursueCards(emptyList<VideoItem>(), NOW_SEC, WINDOW, 8).isEmpty())
        assertTrue(mergePursueCards(emptyList(), listOf(item(-1, 1, NOW_SEC)), NOW_SEC, WINDOW, 8, 60).isNotEmpty())
    }

    // ---------------- R239 扁平完整流（时间窗内完整流，无每频道上限） ----------------

    @Test
    fun `扁平流合并升序_最新在底_用户不变量延续`() {
        val old = item(-101, 1, CUTOFF + 100)
        val mid = item(-102, 1, CUTOFF + 3_600)
        val newest = item(-103, 1, NOW_SEC - 10)
        val merged = pursueTimelineMerge(emptyList(), listOf(newest, mid, old), CUTOFF)
        assertEquals(3, merged.size)
        // 升序：index 0 最旧 → 末尾最新（「最新在最下面」拍板延续）
        assertTrue(merged.zipWithNext().all { (a, b) -> a.dateEpochSec <= b.dateEpochSec })
        assertEquals(CUTOFF + 100, merged[0].dateEpochSec.toLong())
        assertEquals(NOW_SEC - 10, merged[2].dateEpochSec.toLong())
    }

    @Test
    fun `扁平流无每频道上限_同频道全量入流_用户修正①`() {
        // 频道 -101 在窗内发 10 条 → 10 条都进流（旧卡流 8 条截断语义废止）
        val pool = (1..10).map { item(-101, it.toLong(), NOW_SEC - it * 10) }
        val merged = pursueTimelineMerge(emptyList(), pool, CUTOFF)
        assertEquals(10, merged.size)
    }

    @Test
    fun `扁平流去重来者覆盖_富字段版本胜`() {
        val stale = item(-101, 1, NOW_SEC - 10).let { it.copy(playUri = null) }
        val rich = item(-101, 1, NOW_SEC - 10).let { it.copy(playUri = "http://x/y.mp4") }
        val merged = pursueTimelineMerge(listOf(stale), listOf(rich), CUTOFF)
        assertEquals(1, merged.size)
        assertEquals("http://x/y.mp4", merged.single().playUri)
    }

    @Test
    fun `扁平流cutoff过滤_窗下与无时间戳剔除`() {
        val pool = listOf(
            item(-101, 1, NOW_SEC - 10),   // 窗内
            item(-101, 2, CUTOFF - 1),     // 窗下
            item(-101, 3, 0),              // 无时间戳
        )
        val merged = pursueTimelineMerge(emptyList(), pool, CUTOFF)
        assertEquals(1, merged.size)
        assertEquals(1L, merged.single().messageId)
    }

    @Test
    fun `历史地板口径_修剪不裁历史_普通态滚动窗淘汰`() {
        val fresh = item(-101, 1, NOW_SEC - 10)
        val histOld = item(-102, 1, CUTOFF - 100) // 历史层内容（窗下）
        val floor = histOld.dateEpochSec.toLong()
        // 历史在册：cutoff=地板（历史内容保留）
        val withHistory = pursueTimelineMerge(listOf(fresh, histOld), emptyList(), floor)
        assertEquals(2, withHistory.size)
        // 普通态（地板=0）：同一条被滚动窗淘汰
        val rolling = pursueTimelineMerge(listOf(fresh, histOld), emptyList(), CUTOFF)
        assertEquals(1, rolling.size)
        assertEquals(-101L, rolling.single().chatId)
    }

    @Test
    fun `pursueTimelineCutoffSec_地板取更旧_无历史走滚动窗`() {
        val rolling = NOW_SEC - WINDOW
        assertEquals(rolling, pursueTimelineCutoffSec(NOW_SEC, WINDOW, 0L))
        assertEquals(rolling, pursueTimelineCutoffSec(NOW_SEC, WINDOW, rolling + 5)) // 地板更新→滚动窗更旧
        assertEquals(CUTOFF - 500, pursueTimelineCutoffSec(NOW_SEC, WINDOW, CUTOFF - 500)) // 地板更旧→地板
    }

    @Test
    fun `硬顶从最旧侧裁_最新端保住`() {
        val pool = (1..100).map { item(-101L, it.toLong(), CUTOFF + it) }
        val merged = pursueTimelineMerge(emptyList(), pool, CUTOFF, hardCap = 30)
        assertEquals(30, merged.size)
        assertEquals(100L, merged.last().messageId) // 最新端在
        assertEquals(71L, merged.first().messageId) // 最旧侧被裁
    }

    @Test
    fun `新内容计数_只有晚于旧流最新端的新key计数`() {
        val old = listOf(item(-101, 1, NOW_SEC - 100))
        val merged = listOf(
            item(-101, 1, NOW_SEC - 100),          // 旧条目
            item(-102, 2, NOW_SEC - 50),           // 新 append（晚于旧最新）
            item(-103, 3, NOW_SEC - 10),           // 新 append
        )
        assertEquals(2, pursueTimelineAppendCount(old, merged))
        // 归并重排（无新增）不计数
        assertEquals(0, pursueTimelineAppendCount(merged, merged))
    }

    @Test
    fun `历史fallback窗_floor以下一步_越界null`() {
        assertEquals((CUTOFF - 86400) to (CUTOFF - 1), pursueHistoryFallbackWindow(CUTOFF, 86400L))
        assertNull(pursueHistoryFallbackWindow(0L, 86400L))
        assertNull(pursueHistoryFallbackWindow(1L, 86400L)) // 不足一步/越界
    }

    // ---------------- R240 卡片分批（微信/微博式卡流呈现） ----------------

    @Test
    fun `卡片分批_空流安全`() {
        assertTrue(pursueTimelineCards(emptyList()).isEmpty())
    }

    @Test
    fun `卡片分批_相邻同频道同批合卡_卡内升序`() {
        // 同频道 3 条相邻（10 分钟内连发）→ 一张卡；条目升序=与流同序
        val stream = listOf(
            item(-101, 1, NOW_SEC - 600),
            item(-101, 2, NOW_SEC - 540),
            item(-101, 3, NOW_SEC - 480),
        )
        val cards = pursueTimelineCards(stream)
        assertEquals(1, cards.size)
        assertEquals(listOf(1L, 2L, 3L), cards.single().items.map { it.messageId })
        assertEquals(NOW_SEC - 480, cards.single().newestSec) // 卡尾=最新
    }

    @Test
    fun `卡片分批_换频道新卡_卡序等于流序_最新在底不变量`() {
        val stream = listOf(
            item(-101, 1, NOW_SEC - 600),
            item(-102, 1, NOW_SEC - 500),
            item(-101, 2, NOW_SEC - 400),
        )
        val cards = pursueTimelineCards(stream)
        assertEquals(listOf(-101L, -102L, -101L), cards.map { it.chatId }) // 流序原样
    }

    @Test
    fun `卡片分批_同频道间隔断裂新卡_各自卡头时间诚实`() {
        // 同频道相邻但间隔 31 分钟（>30 分钟阈）→ 两张卡
        val stream = listOf(
            item(-101, 1, NOW_SEC - 3_100),
            item(-101, 2, NOW_SEC - 400),
        )
        val cards = pursueTimelineCards(stream)
        assertEquals(2, cards.size)
        assertEquals(1, cards[0].items.size)
        assertEquals(1, cards[1].items.size)
    }

    @Test
    fun `卡片分批_间隔恰等于阈仍合卡_闭区间口径`() {
        val stream = listOf(
            item(-101, 1, NOW_SEC - 1_800),
            item(-101, 2, NOW_SEC),
        )
        assertEquals(1, pursueTimelineCards(stream).size)
    }

    @Test
    fun `卡片缩略与溢出_最新3张_旧到新序_余数角标`() {
        // 同频道连发 5 条 → 一卡；缩略=最新 3 条（msg 3/4/5，升序），+N=2
        val stream = (1..5).map { item(-101, it.toLong(), NOW_SEC - (6 - it) * 60) }
        val card = pursueTimelineCards(stream).single()
        assertEquals(5, card.items.size) // 完整流不截断（数据面语义）
        assertEquals(listOf(3L, 4L, 5L), card.thumbs.map { it.messageId })
        assertEquals(2, card.overflowCount)
    }

    @Test
    fun `卡片缩略_不满3张全展示_无溢出`() {
        val stream = listOf(
            item(-101, 1, NOW_SEC - 600),
            item(-101, 2, NOW_SEC - 300),
        )
        val card = pursueTimelineCards(stream).single()
        assertEquals(2, card.thumbs.size)
        assertEquals(0, card.overflowCount)
    }

    @Test
    fun `卡片频道名取卡尾最新条目`() {
        val stream = listOf(
            item(-101, 1, NOW_SEC - 600, title = "旧名"),
            item(-101, 2, NOW_SEC - 60, title = "新名"),
        )
        assertEquals("新名", pursueTimelineCards(stream).single().channelTitle)
    }

    @Test
    fun `卡片分批_长连发不分裂_跨多批时间内的间插频道各自成卡`() {
        // A 连发 4 条（每条间隔 10 分钟）被 B 一条间插 → A1、B、A2 三卡（B 前后两段 A）
        val stream = listOf(
            item(-101, 1, NOW_SEC - 1_800),
            item(-101, 2, NOW_SEC - 1_200),
            item(-102, 1, NOW_SEC - 900),
            item(-101, 3, NOW_SEC - 600),
            item(-101, 4, NOW_SEC - 300),
        )
        val cards = pursueTimelineCards(stream)
        assertEquals(listOf(-101L, -102L, -101L), cards.map { it.chatId })
        assertEquals(listOf(1L, 2L), cards[0].items.map { it.messageId })
        assertEquals(listOf(3L, 4L), cards[2].items.map { it.messageId })
    }
}
