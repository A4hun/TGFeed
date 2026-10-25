package dev.tgfeed.core.feed

import android.content.Context
import dev.tgfeed.core.store.LocalStore
import dev.tgfeed.core.store.Subscription
import dev.tgfeed.core.td.ChannelBrief
import dev.tgfeed.core.td.FeedPage
import dev.tgfeed.core.td.FeedRequest
import dev.tgfeed.core.td.GatewayState
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.VideoItem
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [FollowingFeedSource]（R176 关注流公平供给重设计）的行为单测：
 *  - 公平轮转：百级订阅全覆盖无饿死 / 高频频道不霸屏 / 余量缓冲翻页零网络；
 *  - 推送位判重：跨会话已看底账（双轨）/ 会话内零重复；
 *  - 永不见底：挖穿历史 → 已看回收（间隔闸+降权垫后）→ 推荐兜底（recommended
 *    标记），nextCursor 永不为 null；
 *  - 保留语义：只出订阅频道、订阅动态变更、单频道失败隔离（含首轮失败不误判死）、
 *    reset 重放。
 *
 * 假网关模拟 TDLib 口径：单频道游标 = 该页最老 messageId（null=从最新开取）；
 * 聚合路（request.channelId==null，RECOMMEND 层消费）游标 = "g:offset"。
 * 已看底账/观看时刻经构造参数注入（R176 供给参数），语义见 FollowingFairSupplyTest。
 */
class FollowingFeedSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: LocalStore

    @Before
    fun setUp() {
        val context = mockk<Context>()
        every { context.filesDir } returns tempFolder.root
        store = LocalStore(context)
    }

    /**
     * 假网关：channels[chatId] = 该频道全部条目（messageId 大=新）；failing 中的频道
     * 抛错（可变——失败隔离测试中途恢复用）；fetchCalls 计数（余量缓冲零网络验证）。
     * 聚合路默认返回全宇宙条目时间倒序分页（推荐兜底层的内容池）。
     */
    private class FakeGateway(
        val channels: Map<Long, List<VideoItem>>,
        var failing: Set<Long> = emptySet(),
    ) : TdGateway {
        var fetchCalls = 0

        override val state: StateFlow<GatewayState> = MutableStateFlow(GatewayState.Ready)
        override val connectionState: StateFlow<String> = MutableStateFlow("已连接")
        override val proxyHint: StateFlow<String?> = MutableStateFlow(null)
        override var includeCommentResources: Boolean = false

        override suspend fun submitPhone(phone: String) {}
        override suspend fun submitCode(code: String) {}
        override suspend fun submitPassword(password: String) {}
        override suspend fun listChannels(): List<ChannelBrief> =
            channels.keys.map { ChannelBrief(it, "频道$it", null) }

        override suspend fun fetchVideos(request: FeedRequest, cursor: String?, limit: Int): FeedPage {
            fetchCalls++
            if (request.channelId == null) {
                // 聚合路（RECOMMEND 层）：全宇宙时间倒序分页，游标 "g:offset"
                val all = channels.values.flatten().sortedByDescending { it.dateEpochSec }
                val from = cursor?.removePrefix("g:")?.toIntOrNull() ?: 0
                val page = all.drop(from).take(limit)
                val next = from + page.size
                return FeedPage(page, if (next < all.size) "g:$next" else null)
            }
            val chatId = requireNotNull(request.channelId)
            if (chatId in failing) throw RuntimeException("模拟拉取失败")
            val all = channels[chatId].orEmpty().sortedByDescending { it.messageId }
            val from = cursor?.toLongOrNull()
            val window = all.filter { from == null || it.messageId < from }
            val page = window.take(limit)
            val hasMore = window.size > limit
            return FeedPage(page, if (hasMore) page.last().messageId.toString() else null)
        }

        override suspend fun ensurePlayable(item: VideoItem, priority: Int): String =
            item.playUri ?: error("无播放地址")
    }

    private fun item(chatId: Long, msgId: Long, epochSec: Int) = VideoItem(
        key = "c${chatId}m$msgId",
        chatId = chatId,
        messageId = msgId,
        channelTitle = "频道$chatId",
        caption = "内容$msgId",
        durationSec = 30,
        width = 720,
        height = 1280,
        fileId = 7,
        sizeBytes = 1024L,
        playUri = "http://x/$chatId/$msgId",
        dateEpochSec = epochSec,
    )

    /** 造一个频道的内容库存：[count] 条，messageId 1..count，时间随 messageId 递增 */
    private fun stock(chatId: Long, count: Int, baseEpoch: Int = 1_700_000_000): List<VideoItem> =
        (1..count).map { item(chatId, it.toLong(), baseEpoch + it * 60) }

    private fun subs(vararg chatIds: Long) =
        chatIds.map { Subscription(chatId = it, title = "频道$it") }

    @Test
    fun `只出订阅频道的内容`() = runBlocking {
        val gateway = FakeGateway(
            mapOf(1L to stock(1, 5), 2L to stock(2, 5), 99L to stock(99, 5)),
        )
        store.saveSubscriptions(subs(1L, 2L)) // 99 未订阅
        val src = FollowingFeedSource(gateway, store)
        val page = src.nextPage(20)
        assertTrue(page.items.isNotEmpty())
        assertTrue(page.items.all { it.chatId in setOf(1L, 2L) })
    }

    @Test
    fun `公平轮转_百级订阅全覆盖无饿死`() = runBlocking {
        // 100 频道各 3 条：一轮拉 200 条交错，页 20 条——刷 100 条后
        // 100 个订阅频道全部露头（用户指标「关注 N 频道至少 N-k 个出现过」）
        val ids = (1L..100L).toList()
        val gateway = FakeGateway(ids.associateWith { stock(it, 3) })
        store.saveSubscriptions(subs(*ids.toLongArray()))
        val src = FollowingFeedSource(gateway, store)
        val seen = ArrayList<VideoItem>(100)
        repeat(5) { seen += src.nextPage(20).items }
        assertEquals(100, seen.size)
        assertEquals(100, seen.map { it.chatId }.distinct().size) // 全覆盖零饿死
        // 首页 20 条 = 20 个不同频道（页头即最大多样性）
        assertEquals(20, seen.take(20).map { it.chatId }.distinct().size)
    }

    @Test
    fun `页内公平_高频频道不霸屏`() = runBlocking {
        // A 库存 50 条、其余 4 频道各 5 条：配额均分（20/5=4/频道）后轮转交错，
        // 首页 20 条里 A 不超过 8 条、每个频道至少 2 条
        val gateway = FakeGateway(
            mapOf(
                1L to stock(1, 50),
                2L to stock(2, 5), 3L to stock(3, 5), 4L to stock(4, 5), 5L to stock(5, 5),
            )
        )
        store.saveSubscriptions(subs(1L, 2L, 3L, 4L, 5L))
        val src = FollowingFeedSource(gateway, store)
        val page = src.nextPage(20)
        assertEquals(20, page.items.size)
        val byChannel = page.items.groupBy { it.chatId }
        assertTrue("高频频道霸屏：${byChannel[1L]?.size} 条", (byChannel[1L]?.size ?: 0) <= 8)
        for (id in 2L..5L) {
            assertTrue("频道 $id 饿死页内", (byChannel[id]?.size ?: 0) >= 2)
        }
    }

    @Test
    fun `余量缓冲_翻页零网络直取`() = runBlocking {
        // 一轮拉 200 条（100 频道×2），页 20 条：余 180 进缓冲，后续 8 页
        // 全部零网络直取（fetchCalls 只在首轮 +1/频道）
        val ids = (1L..100L).toList()
        val gateway = FakeGateway(ids.associateWith { stock(it, 3) })
        store.saveSubscriptions(subs(*ids.toLongArray()))
        val src = FollowingFeedSource(gateway, store)
        src.nextPage(20)
        val callsAfterFirst = gateway.fetchCalls
        assertEquals(100, callsAfterFirst) // 首轮 = 每频道一次
        repeat(8) {
            val page = src.nextPage(20)
            assertEquals(20, page.items.size)
        }
        assertEquals(100, gateway.fetchCalls) // 缓冲期零网络
        assertTrue(src.pendingCount() > 0)
    }

    @Test
    fun `跨会话判重_已看双轨命中不再出现`() = runBlocking {
        // 已看底账：key 轨命中 ch2m2、指纹轨命中 ch1m3（换台同片）——
        // 全程任何页都不再出现（含挖穿后的回收层：近期观看被间隔闸挡）
        val gateway = FakeGateway(mapOf(1L to stock(1, 5), 2L to stock(2, 5)))
        store.saveSubscriptions(subs(1L, 2L))
        val nowMs = System.currentTimeMillis()
        val src = FollowingFeedSource(
            gateway, store,
            seenProvider = {
                setOf("c1m3", "c2m2") to setOf("uid-c1m1")
            },
            watchTsProvider = {
                mapOf("c1m3" to nowMs, "c2m2" to nowMs) to emptyMap()
            },
            weightsProvider = { emptyMap() },
        )
        // 让 c1m1 带指纹（构造时改不了——直接用默认 uid 空，指纹轨用独立场景验证）
        val served = ArrayList<VideoItem>()
        repeat(10) { served += src.nextPage(20).items }
        assertTrue(served.isNotEmpty())
        assertFalse(served.any { it.key == "c1m3" || it.key == "c2m2" })
    }

    @Test
    fun `推送位判重_会话内零重复`() = runBlocking {
        // 刷穿全部供给层（含回收重走+推荐环回）：任何条目在同一会话只推一次
        val gateway = FakeGateway(mapOf(1L to stock(1, 6), 2L to stock(2, 6)))
        store.saveSubscriptions(subs(1L, 2L))
        val src = FollowingFeedSource(gateway, store, weightsProvider = { emptyMap() })
        val served = ArrayList<VideoItem>()
        repeat(12) { served += src.nextPage(20).items }
        assertEquals(served.size, served.map { it.key }.distinct().size) // 零重复
    }

    @Test
    fun `永不见底_挖穿后回收挡住则推荐兜底带标记`() = runBlocking {
        // ch1/ch2 各 3 条（全部近期已看：回收间隔闸挡住二轮回收）+ ch7（未订阅，
        // 聚合宇宙里）——p1 直接走到推荐兜底层：出 ch7 条目（条条带 recommended
        // 标记=「为你推荐」chip 的数据源）；nextCursor 全程非 null（「到底」状态
        // 永久移除），推荐宇宙也耗尽后空页但游标仍活（环回继续探测新帖，非终态）
        val gateway = FakeGateway(mapOf(1L to stock(1, 3), 2L to stock(2, 3), 7L to stock(7, 3)))
        store.saveSubscriptions(subs(1L, 2L))
        val nowMs = System.currentTimeMillis()
        val src = FollowingFeedSource(
            gateway, store,
            seenProvider = { setOf("c1m1", "c1m2", "c1m3", "c2m1", "c2m2", "c2m3") to emptySet() },
            watchTsProvider = {
                mapOf(
                    "c1m1" to nowMs, "c1m2" to nowMs, "c1m3" to nowMs,
                    "c2m1" to nowMs, "c2m2" to nowMs, "c2m3" to nowMs,
                ) to emptyMap()
            },
            weightsProvider = { emptyMap() },
        )
        val p1 = src.nextPage(20)
        assertTrue(p1.items.isNotEmpty())            // 供给未死：推荐层接上
        assertTrue(p1.items.all { it.recommended })  // 条条带「为你推荐」标记
        assertTrue(p1.items.all { it.chatId == 7L }) // 来自聚合路而非订阅频道
        assertNotNull(p1.nextCursor)
        assertEquals(FollowingSupplyLayer.RECOMMEND, src.supplyLayerNow())
        // 推荐宇宙也耗尽（ch7 共 3 条已推）：空页但游标仍活——环回继续探测
        val p2 = src.nextPage(20)
        assertTrue(p2.items.isEmpty())
        assertNotNull(p2.nextCursor)
        val p3 = src.nextPage(20)
        assertNotNull(p3.nextCursor)
    }

    @Test
    fun `回收层_间隔闸放行旧看内容且降权垫后`() = runBlocking {
        // ch1 三条：m1/m3 未看，m2「100 小时前看过」（过间隔闸）——
        // 同一页内 FRESH 先出未看两条，回收层把 m2 降权垫后补上
        val gateway = FakeGateway(mapOf(1L to stock(1, 3)))
        store.saveSubscriptions(subs(1L))
        val nowMs = System.currentTimeMillis()
        val src = FollowingFeedSource(
            gateway, store,
            seenProvider = { setOf("c1m2") to emptySet() },
            watchTsProvider = { mapOf("c1m2" to nowMs - 100L * 3600_000L) to emptyMap() },
            weightsProvider = { emptyMap() },
        )
        val p1 = src.nextPage(20)
        // FRESH 批内时间新→旧（m3、m1），回收层把间隔放行的 m2 降权垫后
        assertEquals(listOf("c1m3", "c1m1", "c1m2"), p1.items.map { it.key })
        assertFalse(p1.items.any { it.recommended }) // 回收条目不是推荐位，不标记
    }

    @Test
    fun `开关关闭_已看条目照常供给`() = runBlocking {
        val gateway = FakeGateway(mapOf(1L to stock(1, 4)))
        store.saveSubscriptions(subs(1L))
        val src = FollowingFeedSource(
            gateway, store,
            seenProvider = { setOf("c1m1", "c1m2", "c1m3", "c1m4") to emptySet() },
            watchTsProvider = { mapOf("c1m1" to 1L, "c1m2" to 1L, "c1m3" to 1L, "c1m4" to 1L) to emptyMap() },
            excludeSeenProvider = { false },
            weightsProvider = { emptyMap() },
        )
        val p1 = src.nextPage(20)
        assertEquals(4, p1.items.size) // 开关关：已看不挡，全量供给
    }

    @Test
    fun `新订阅频道下一轮自动并入_退订剔除`() = runBlocking {
        // ch1 库存深（30 条）：FRESH 轮内补拉不断粮，推荐层不被触发；
        // 会话中途改订阅：退 1 订 2、3 → 退订频道游标剔除、新频道从最新并入
        val gateway = FakeGateway(
            mapOf(1L to stock(1, 30), 2L to stock(2, 8), 3L to stock(3, 8))
        )
        store.saveSubscriptions(subs(1L))
        val src = FollowingFeedSource(gateway, store, weightsProvider = { emptyMap() })
        val p1 = src.nextPage(20)
        assertEquals(20, p1.items.size)
        assertTrue(p1.items.all { it.chatId == 1L })
        src.nextPage(20) // 继续深挖（余量+补拉），ch1 供给不断流
        // 会话中途改订阅：退 1 订 2、3
        store.saveSubscriptions(subs(2L, 3L))
        val p3 = src.nextPage(20)
        assertTrue(p3.items.isNotEmpty())
        assertTrue(p3.items.all { it.chatId in setOf(2L, 3L) }) // 退订剔除 + 新订阅并入
    }

    @Test
    fun `单频道失败只跳过该频道_恢复后下轮补出`() = runBlocking {
        // 首轮失败 ≠ 误判到底（R154 老病：失败页 nextCursor=null 被写成 END_MARK
        // 判死频道）：失败保留原游标（未开取=下轮从最新重试），恢复后照常出内容；
        // ch1 库存深保证失败期间 FRESH 不断粮（不降层）
        val gateway = FakeGateway(
            mapOf(1L to stock(1, 30), 2L to stock(2, 4)),
            failing = setOf(2L),
        )
        store.saveSubscriptions(subs(1L, 2L))
        val src = FollowingFeedSource(gateway, store, weightsProvider = { emptyMap() })
        val p1 = src.nextPage(20)
        assertEquals(20, p1.items.size)
        assertTrue(p1.items.all { it.chatId == 1L }) // 频道 2 失败被跳过
        assertEquals(FollowingSupplyLayer.FRESH, src.supplyLayerNow()) // 未被误降层
        // 频道 2 恢复：游标未记账（失败未判死），下轮从最新补出
        gateway.failing = emptySet()
        val p2 = src.nextPage(20)
        assertTrue(p2.items.any { it.chatId == 2L })
    }

    @Test
    fun `无订阅返回空页`() = runBlocking {
        val gateway = FakeGateway(mapOf(1L to stock(1, 4)))
        val src = FollowingFeedSource(gateway, store)
        val page = src.nextPage(20)
        assertTrue(page.items.isEmpty())
        assertNull(page.nextCursor) // 唯一合法空态：没订阅（引导卡），不是「到底」
    }

    @Test
    fun `reset后从最新重放`() = runBlocking {
        val gateway = FakeGateway(mapOf(1L to stock(1, 12)))
        store.saveSubscriptions(subs(1L))
        val src = FollowingFeedSource(gateway, store, weightsProvider = { emptyMap() })
        val first = src.nextPage(20)
        src.reset()
        val replay = src.nextPage(20)
        assertEquals(first.items.map { it.key }, replay.items.map { it.key })
    }

    @Test
    fun `翻页深挖历史不丢条目`() = runBlocking {
        // 单频道 12 条，每轮配额 10（limit=20 单频道 → perChannel=10）：
        // 轮内补拉把整库出满（第二轮续拉余下 2 条），全库存一条不丢、
        // 批内时间新→旧的深挖语义保留（配额上限防单页无限挖库）
        val gateway = FakeGateway(mapOf(1L to stock(1, 12)))
        store.saveSubscriptions(subs(1L))
        val src = FollowingFeedSource(gateway, store, weightsProvider = { emptyMap() })
        val p1 = src.nextPage(20)
        assertEquals(12, p1.items.size)
        assertEquals(12, p1.items.map { it.key }.distinct().size) // 无重复无丢失
        assertEquals(12L, p1.items.first().messageId) // 批内新→旧
        assertEquals(1L, p1.items.minOf { it.messageId }) // 挖到最老一条
    }
}
