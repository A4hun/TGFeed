package dev.tgfeed.core.feed

import dev.tgfeed.core.td.FeedPage
import dev.tgfeed.core.td.FeedRequest
import dev.tgfeed.core.td.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R182 源历史栈纯策略单测（返回语义矩阵 L1 锚点）：
 * - 入栈/出栈 LIFO 序、栈深上限（溢出丢最旧）；
 * - 空快照条目的恢复路径裁决（整源重拉 vs 原位恢复）；
 * - L1 源栈 ∪ L2 路由 pending 的统一消费序（时刻戳择新：最新跳转先回）；
 * - BackNavClock 单调性（「先入栈后置 pending」的路由跳转序契约）。
 */
class FeedBackStackPolicyTest {

    /** 测试用空源（纯 JVM，零网关依赖） */
    private class FakeSource(val name: String) : FeedSource {
        override val request = FeedRequest()
        override suspend fun nextPage(limit: Int) = FeedPage(emptyList(), null)
        override fun reset() {}
        override fun toString() = name
    }

    private fun item(key: String) = VideoItem(
        key = key, chatId = 1, messageId = 1, channelTitle = "c", caption = "",
        durationSec = 0, width = 0, height = 0, fileId = -1, sizeBytes = 0, playUri = null,
    )

    private fun entry(
        name: String,
        items: List<VideoItem> = listOf(item("a")),
        stamp: Long = BackNavClock.next(),
    ) = SourceBackStackEntry(
        source = FakeSource(name), title = name, items = items,
        index = if (items.isEmpty()) 0 else items.size / 2,
        mediaTypeTab = -1, filter = FeedFilter(), stamp = stamp,
    )

    @Before
    fun resetClock() = BackNavClock.resetForTest()

    // ---------------- 入栈/出栈序 ----------------

    @Test
    fun `入栈出栈 LIFO 序`() {
        val stack = mutableListOf<SourceBackStackEntry>()
        FeedBackStackPolicy.push(stack, entry("agg"))
        FeedBackStackPolicy.push(stack, entry("channelA"))
        FeedBackStackPolicy.push(stack, entry("hashtag"))
        assertEquals(listOf("agg", "channelA", "hashtag"), stack.map { it.title })
        assertEquals("hashtag", FeedBackStackPolicy.pop(stack)?.title)
        assertEquals("channelA", FeedBackStackPolicy.pop(stack)?.title)
        assertEquals("agg", FeedBackStackPolicy.pop(stack)?.title)
        assertNull(FeedBackStackPolicy.pop(stack)) // 栈底：返回语义落 L2/L3
    }

    @Test
    fun `栈深上限溢出丢最旧`() {
        val stack = mutableListOf<SourceBackStackEntry>()
        repeat(15) { FeedBackStackPolicy.push(stack, entry("s$it")) }
        assertEquals(FeedBackStackPolicy.STACK_CAP, stack.size)
        // 最旧 5 个被丢：栈顶仍是 s14，逐弹到 s5
        assertEquals("s14", FeedBackStackPolicy.pop(stack)?.title)
        repeat(FeedBackStackPolicy.STACK_CAP - 2) { FeedBackStackPolicy.pop(stack) }
        assertEquals("s5", FeedBackStackPolicy.pop(stack)?.title)
        assertNull(FeedBackStackPolicy.pop(stack))
    }

    @Test
    fun `空栈 pop 返回 null 不抛`() {
        assertNull(FeedBackStackPolicy.pop(mutableListOf()))
    }

    // ---------------- 空快照恢复路径 ----------------

    @Test
    fun `空快照条目走整源重拉`() {
        assertTrue(FeedBackStackPolicy.restoreNeedsReload(entry("loading", items = emptyList())))
        assertFalse(FeedBackStackPolicy.restoreNeedsReload(entry("agg", items = listOf(item("a")))))
    }

    // ---------------- L1 ∪ L2 统一消费序（时刻戳择新） ----------------

    @Test
    fun `路由 pending 晚于入栈则先回子页`() {
        // 试吃链：入源栈（推荐@t1）→ 置发现 pending（t2）——第一次返回应先回发现页
        val pushStamp = BackNavClock.next()
        val pendingStamp = BackNavClock.next()
        val pick = FeedBackStackPolicy.pickBackConsumption(
            listOf(
                BackConsumption.PopSource(pushStamp),
                BackConsumption.Route(RoutePendingKind.DISCOVER, pendingStamp),
            ),
        )
        assertTrue(pick is BackConsumption.Route && pick.kind == RoutePendingKind.DISCOVER && pick.stamp == pendingStamp)
    }

    @Test
    fun `feed 内后续跳转晚于路由 pending 则先弹源栈`() {
        // 试吃频道里点 #标签：pending@t2 → 入栈@t3——第一次返回应弹源栈（回试吃频道）
        val pendingStamp = BackNavClock.next()
        val pushStamp = BackNavClock.next()
        val pick = FeedBackStackPolicy.pickBackConsumption(
            listOf(
                BackConsumption.PopSource(pushStamp),
                BackConsumption.Route(RoutePendingKind.DISCOVER, pendingStamp),
            ),
        )
        assertTrue(pick is BackConsumption.PopSource && pick.stamp == pushStamp)
    }

    @Test
    fun `多个路由 pending 取最新置位者`() {
        val search = BackNavClock.next()
        val channelHome = BackNavClock.next()
        val watchHistory = BackNavClock.next()
        val pick = FeedBackStackPolicy.pickBackConsumption(
            listOf(
                BackConsumption.Route(RoutePendingKind.SEARCH, search),
                BackConsumption.Route(RoutePendingKind.CHANNEL_HOME, channelHome),
                BackConsumption.Route(RoutePendingKind.WATCH_HISTORY, watchHistory),
            ),
        )
        assertTrue(pick is BackConsumption.Route && pick.kind == RoutePendingKind.WATCH_HISTORY)
    }

    @Test
    fun `候选为空返回 null（栈底落 L3 双击退出）`() {
        assertNull(FeedBackStackPolicy.pickBackConsumption(emptyList()))
    }

    @Test
    fun `源栈独存时弹源栈`() {
        // 深链/纯 feed 内跳转（无 pending）：只弹源栈
        val pick = FeedBackStackPolicy.pickBackConsumption(
            listOf(BackConsumption.PopSource(BackNavClock.next())),
        )
        assertTrue(pick is BackConsumption.PopSource)
    }

    // ---------------- 时刻钟契约 ----------------

    @Test
    fun `BackNavClock 严格单调递增`() {
        val a = BackNavClock.next()
        val b = BackNavClock.next()
        val c = BackNavClock.next()
        assertTrue(a < b && b < c)
    }
}
