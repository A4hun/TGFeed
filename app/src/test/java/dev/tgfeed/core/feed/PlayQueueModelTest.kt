package dev.tgfeed.core.feed

import dev.tgfeed.core.td.ChannelBrief
import dev.tgfeed.core.td.FeedPage
import dev.tgfeed.core.td.FeedRequest
import dev.tgfeed.core.td.GatewayState
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R387 播放队列（节目单）core 纯件行为单测（ListenQueueModel/HistoryFeedSource
 * 的 JVM 直测族）：
 *  - 纯模型 PlayQueueModel：入单幂等/上限截尾/移除断点失联清账/拖拽换序/置顶/
 *    跳过/档位/清空/断点记账变更位门；
 *  - buildPlayPlan 装配纯函数：MANUAL 手排原序+skipped 构建期排除/HEAT 成员数
 *    稳定排序未知沉底/RANDOM 同 seed 同序换 seed 重洗/TIME 段序直通；
 *  - PlayQueueFeedSource：MANUAL 顺序段走（耗尽接下段段间无缝+页缓冲防丢+
 *    到底 null 游标）/TIME 轮式（轮内时间序降序+单条段首轮即尽+单频道失败跳过
 *    保留游标）/reset 重放/entryIdOf 反查/顺序段错误上抛（诚实口径）。
 *
 * 假网关模拟 TDLib 口径（FollowingFeedSourceTest 同款母版）：单频道游标=该页
 * 最老 messageId（null=从最新开取）；failing 中的频道抛错（可变——失败跳过
 * 测试中途恢复用）。
 */
class PlayQueueModelTest {

    // ---------------- 纯模型 ----------------

    @Test
    fun `入台幂等_同chatId第二次返回false不重复`() {
        val m = PlayQueueModel()
        assertTrue(m.enqueueChannel(-1001L, "频道A"))
        assertFalse("同 chatId 已在单=幂等忽略", m.enqueueChannel(-1001L, "频道A2"))
        assertEquals(1, m.queue.entries.size)
        assertEquals("频道A", (m.queue.entries[0] as PlayQueueEntry.Channel).title)
    }

    @Test
    fun `入条幂等_同key第二次返回false不重复`() {
        val m = PlayQueueModel()
        assertTrue(m.enqueueItem(item("k1", -1001L)))
        assertFalse("同 key 已在单=幂等忽略", m.enqueueItem(item("k1", -1001L)))
        assertEquals(1, m.queue.entries.size)
    }

    @Test
    fun `上限200截尾_入超限防御`() {
        val m = PlayQueueModel()
        repeat(PlayQueueModel.MAX_ENTRIES + 10) { i ->
            m.enqueueChannel(-(1000L + i), "频道$i")
        }
        assertEquals("超限截尾到 MAX_ENTRIES", PlayQueueModel.MAX_ENTRIES, m.queue.entries.size)
    }

    @Test
    fun `移除条目_断点指向被移除条目同步清账`() {
        val m = PlayQueueModel()
        m.enqueueChannel(-1001L, "频道A")
        m.enqueueChannel(-1002L, "频道B")
        val aId = m.queue.entries[0].id
        m.setResume(aId, "k1")
        assertNotNull(m.queue.resumeEntryId)
        assertTrue(m.remove(aId))
        assertNull("断点随条目消亡一并清账", m.queue.resumeEntryId)
        assertNull(m.queue.resumeKey)
        assertFalse("本来就没有=移除返回 false（省一次落盘）", m.remove("不存在"))
    }

    @Test
    fun `移除条目_断点指向存活条目不受波及`() {
        val m = PlayQueueModel()
        m.enqueueChannel(-1001L, "频道A")
        m.enqueueChannel(-1002L, "频道B")
        val bId = m.queue.entries[1].id
        m.setResume(bId, "k1")
        assertTrue(m.remove(m.queue.entries[0].id))
        assertEquals("断点指向存活条目=原样保留", bId, m.queue.resumeEntryId)
    }

    @Test
    fun `拖拽换序_越界与原地返回false`() {
        val m = PlayQueueModel()
        m.enqueueChannel(-1001L, "A")
        m.enqueueChannel(-1002L, "B")
        m.enqueueChannel(-1003L, "C")
        assertFalse("原地=无变更", m.move(1, 1))
        assertFalse("越界=拒绝", m.move(0, 3))
        assertFalse("负下标=拒绝", m.move(-1, 0))
        assertTrue(m.move(2, 0))
        assertEquals(
            listOf(-1003L, -1001L, -1002L),
            m.queue.entries.map { (it as PlayQueueEntry.Channel).chatId },
        )
    }

    @Test
    fun `置顶_已在顶返回false`() {
        val m = PlayQueueModel()
        m.enqueueChannel(-1001L, "A")
        m.enqueueChannel(-1002L, "B")
        assertFalse("已在顶=省一次落盘", m.moveToTop(m.queue.entries[0].id))
        assertTrue(m.moveToTop(m.queue.entries[1].id))
        assertEquals(-1002L, (m.queue.entries[0] as PlayQueueEntry.Channel).chatId)
    }

    @Test
    fun `跳过开关翻转可恢复_清空清账`() {
        val m = PlayQueueModel()
        m.enqueueChannel(-1001L, "A")
        val id = m.queue.entries[0].id
        assertTrue(m.toggleSkip(id))
        assertTrue(m.queue.entries[0].skipped)
        assertTrue(m.toggleSkip(id))
        assertFalse("跳过=播放期排除可恢复（不是删除）", m.queue.entries[0].skipped)
        m.setResume(id, "k")
        assertTrue(m.clear())
        assertTrue("清空=断点随条目消亡", m.queue.entries.isEmpty() && m.queue.resumeEntryId == null)
        assertFalse("本来就空=clear 返回 false", m.clear())
    }

    @Test
    fun `断点记账变更位门_同条不重写`() {
        val m = PlayQueueModel()
        m.enqueueChannel(-1001L, "A")
        val id = m.queue.entries[0].id
        assertTrue("首记=变更", m.setResume(id, "k1"))
        assertFalse("同条同 key 重记=不变更（节流落盘门）", m.setResume(id, "k1"))
        assertTrue("换条=变更", m.setResume(id, "k2"))
        assertFalse("无断点=resetResume 返回 false", PlayQueueModel().resetResume())
        assertTrue(m.resetResume())
        assertNull(m.queue.resumeEntryId)
    }

    @Test
    fun `档位反解_未知回落MANUAL`() {
        assertEquals("大小写敏感：小写 time 不匹配档名=未知回落", PlayQueueOrder.MANUAL, PlayQueueOrder.fromName("time"))
        assertEquals(PlayQueueOrder.MANUAL, PlayQueueOrder.fromName(null))
        assertEquals(PlayQueueOrder.MANUAL, PlayQueueOrder.fromName("不存在的档"))
        assertEquals(PlayQueueOrder.TIME, PlayQueueOrder.fromName("TIME"))
    }

    // ---------------- buildPlayPlan 装配纯函数 ----------------

    @Test
    fun `MANUAL手排原序_skipped构建期排除`() {
        val m = PlayQueueModel()
        m.enqueueChannel(-1001L, "A")
        m.enqueueItem(item("s1", -1001L))
        m.enqueueChannel(-1002L, "B")
        m.toggleSkip(m.queue.entries[0].id) // A 跳过
        val plan = buildPlayPlan(m.queue)
        assertEquals("跳过条目不进装配流", 2, plan.size)
        assertTrue(plan[0] is PlaySegment.Single)
        assertTrue(plan[1] is PlaySegment.Channel)
        assertEquals(-1002L, (plan[1] as PlaySegment.Channel).chatId)
    }

    @Test
    fun `HEAT段键成员数降序_未知负一沉底_稳定并列保原序`() {
        val m = PlayQueueModel()
        m.enqueueChannel(-1001L, "A") // 成员 500
        m.enqueueChannel(-1002L, "B") // 未知
        m.enqueueChannel(-1003L, "C") // 成员 100
        m.enqueueChannel(-1004L, "D") // 成员 500（与 A 并列，稳定排序保原序）
        m.setOrder(PlayQueueOrder.HEAT)
        val plan = buildPlayPlan(m.queue, memberCounts = mapOf(-1001L to 500, -1003L to 100, -1004L to 500))
        assertEquals(
            listOf(-1001L, -1004L, -1003L, -1002L),
            plan.map { (it as PlaySegment.Channel).chatId },
        )
    }

    @Test
    fun `HEAT单条段键取item的chatId`() {
        val m = PlayQueueModel()
        m.enqueueChannel(-1001L, "A") // 未知 → -1 沉底
        m.enqueueItem(item("s1", -2001L)) // 成员 900 → 单条段按来源频道取表
        m.setOrder(PlayQueueOrder.HEAT)
        val plan = buildPlayPlan(m.queue, memberCounts = mapOf(-2001L to 900))
        assertEquals(
            "单条段键=item.chatId（其来源频道成员数）",
            listOf("s1", -1001L),
            plan.map { if (it is PlaySegment.Single) it.item.key else (it as PlaySegment.Channel).chatId },
        )
    }

    @Test
    fun `RANDOM同seed同序_换seed重洗_成员零丢失`() {
        val m = PlayQueueModel()
        repeat(6) { i -> m.enqueueChannel(-(1000L + i), "频道$i") }
        m.setOrder(PlayQueueOrder.RANDOM)
        val plan1 = buildPlayPlan(m.queue, seed = 42L)
        val plan2 = buildPlayPlan(m.queue, seed = 42L)
        assertEquals("同 seed=同序（重放语义）", plan1, plan2)
        val identity = m.queue.entries.map { it.id }
        // 会洗判据=10 个种子至少一个偏离手排原序（单一 seed 与原序相同的概率 1/720，
        // 十种子全同 ≈ 1e-17——工程确定性断言，不赌单对 seed）
        val anyShuffled = (0L..9L).any { s ->
            buildPlayPlan(m.queue, seed = s).map { it.entryId } != identity
        }
        assertTrue("RANDOM 档须真的会洗（至少一个种子偏离手排原序）", anyShuffled)
        // 成员零丢失：任一洗牌结果与手排名单同集
        val keys1 = plan1.map { it.entryId }.toSet()
        assertEquals("洗牌=只动序不动成员", identity.toSet(), keys1)
    }

    @Test
    fun `TIME段序直通_不预排`() {
        val m = PlayQueueModel()
        m.enqueueChannel(-1001L, "A")
        m.enqueueChannel(-1002L, "B")
        m.enqueueItem(item("s1", -1001L))
        m.setOrder(PlayQueueOrder.TIME)
        val plan = buildPlayPlan(m.queue)
        assertEquals(
            "TIME=段序直通（轮式交错在源层），entries 原序即 plan 序",
            m.queue.entries.map { it.id },
            plan.map { it.entryId },
        )
    }

    // ---------------- PlayQueueFeedSource 源行为（假网关） ----------------

    /**
     * 假网关（FollowingFeedSourceTest 母版）：channels[chatId] = 该频道全部条目
     * （messageId 大=新）；游标=该页最老 messageId（排他向旧翻）；failing 中的
     * 频道抛错（可变——失败跳过测试中途恢复用）。
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

    private fun item(key: String, chatId: Long, msgId: Long = key.hashCode().toLong() and 0xFFFF) =
        VideoItem(
            key = key,
            chatId = chatId,
            messageId = msgId,
            channelTitle = "频道$chatId",
            caption = "内容$key",
            durationSec = 30,
            width = 720,
            height = 1280,
            fileId = 7,
            sizeBytes = 1024L,
            playUri = "http://example.com/$key",
            dateEpochSec = 1_700_000_000 + msgId.toInt(), // messageId 大=时间新
        )

    /** 造 [chatId]=msg 序号集的频道条目表（ids 顺序即供给顺序——新→旧传 downTo） */
    private fun channelItems(chatId: Long, ids: Iterable<Long>): List<VideoItem> =
        ids.map { item("c${chatId}m$it", chatId, it) }

    @Test
    fun `MANUAL顺序段走_耗尽接下段页界无缝拼接`() = runBlocking {
        val gw = FakeGateway(
            mapOf(
                -1001L to channelItems(-1001L, 5L downTo 1L), // A: 5 条
                -1002L to channelItems(-1002L, 5L downTo 1L), // B: 5 条
            ),
        )
        val m = PlayQueueModel()
        m.enqueueChannel(-1001L, "A")
        m.enqueueItem(item("s1", -1001L, 99L))
        m.enqueueChannel(-1002L, "B")
        val src = PlayQueueFeedSource(gw, buildPlayPlan(m.queue), PlayQueueOrder.MANUAL)

        // 第一页：A 头 4 条
        val p1 = src.nextPage(4)
        assertEquals(listOf("c-1001m5", "c-1001m4", "c-1001m3", "c-1001m2"), p1.items.map { it.key })
        assertNotNull("未到底=非 null 游标", p1.nextCursor)

        // 第二页：A 尾 + 单条 + B 头——段耗尽即接下段，页界跨段拼接零缝隙
        val p2 = src.nextPage(4)
        assertEquals(listOf("c-1001m1", "s1", "c-1002m5", "c-1002m4"), p2.items.map { it.key })
        assertNotNull(p2.nextCursor)

        // 第三页：B 剩 3 条到底
        val p3 = src.nextPage(4)
        assertEquals(listOf("c-1002m3", "c-1002m2", "c-1002m1"), p3.items.map { it.key })
        assertNull("全段耗尽且缓冲空=到底（末页可带条目）", p3.nextCursor)
        // 到底后再拉=空页空游标
        val p4 = src.nextPage(4)
        assertTrue(p4.items.isEmpty())
        assertNull(p4.nextCursor)
    }

    @Test
    fun `页缓冲防丢_轮式盈余下页零网络续供`() = runBlocking {
        val gw = FakeGateway(
            mapOf(-1001L to channelItems(-1001L, 5L downTo 1L)),
        )
        val m = PlayQueueModel()
        m.enqueueChannel(-1001L, "A")
        // TIME 轮式：limit=2 → perChannel=max(4,2)=4 → 取 4 条 take 2、盈余 2 进缓冲
        val src = PlayQueueFeedSource(gw, buildPlayPlan(m.queue), PlayQueueOrder.TIME)
        val p1 = src.nextPage(2)
        assertEquals(2, p1.items.size)
        val callsAfter1 = gw.fetchCalls
        val p2 = src.nextPage(2)
        assertEquals(2, p2.items.size)
        assertEquals(
            "盈余从页缓冲续供（零网络）",
            callsAfter1,
            gw.fetchCalls,
        )
        assertEquals(
            "顺序不断流：p1+p2 = 频道头 4 条（新→旧）",
            listOf("c-1001m5", "c-1001m4", "c-1001m3", "c-1001m2"),
            p1.items.map { it.key } + p2.items.map { it.key },
        )
    }

    @Test
    fun `TIME轮内时间序降序_未知时间沉底_单条段首轮即尽`() = runBlocking {
        val old = VideoItem(
            key = "old", chatId = -1001L, messageId = 90L, channelTitle = "A",
            caption = "老条目", durationSec = 30, width = 720, height = 1280,
            fileId = 7, sizeBytes = 1024L, playUri = "http://example.com/old",
            dateEpochSec = 0, // 未知时间 → 轮内沉底
        )
        val gw = FakeGateway(
            mapOf(
                -1001L to channelItems(-1001L, 2L downTo 1L),
                -1002L to channelItems(-1002L, 2L downTo 1L),
            ),
        )
        val m = PlayQueueModel()
        m.enqueueChannel(-1001L, "A")
        m.enqueueChannel(-1002L, "B")
        val before = gw.fetchCalls // 假网关计数从 0 开始
        assertEquals(0, before)
        m.enqueueItem(old)
        val src = PlayQueueFeedSource(gw, buildPlayPlan(m.queue), PlayQueueOrder.TIME)
        // 一轮：A 2 条 + B 2 条 + 单条 1 条（dateEpochSec=0 沉底）→ 轮内时间降序
        val p1 = src.nextPage(8)
        assertEquals(
            listOf("c-1001m2", "c-1002m2", "c-1001m1", "c-1002m1", "old"),
            p1.items.map { it.key },
        )
        // 单条段首轮即尽：后续轮只剩 A/B
        val p2 = src.nextPage(8)
        assertTrue("第二轮不再出单条段", p2.items.none { it.key == "old" })
    }

    @Test
    fun `TIME单频道失败跳过_段游标保留恢复后从顶重试`() = runBlocking {
        val gw = FakeGateway(
            mapOf(
                -1001L to channelItems(-1001L, 2L downTo 1L),
                -1002L to channelItems(-1002L, 2L downTo 1L),
            ),
            failing = setOf(-1001L), // A 整轮失败
        )
        val m = PlayQueueModel()
        m.enqueueChannel(-1001L, "A")
        m.enqueueChannel(-1002L, "B")
        val src = PlayQueueFeedSource(gw, buildPlayPlan(m.queue), PlayQueueOrder.TIME)
        val p1 = src.nextPage(4)
        assertEquals("死一台不挡全队：B 照常供给", listOf("c-1002m2", "c-1002m1"), p1.items.map { it.key })
        // A 恢复：下轮自动重试且游标未推进（从 A 的最新一条开取）
        gw.failing = emptySet()
        val p2 = src.nextPage(4)
        // B 已耗尽（上一轮取完 2 条到底）：本轮只剩活跃段 A，2 条完整在场
        assertEquals(
            "失败保留游标=恢复后从顶重试（A 头两条完整）",
            listOf("c-1001m2", "c-1001m1"),
            p2.items.map { it.key },
        )
    }

    @Test
    fun `MANUAL频道段取数错误上抛_诚实口径`() = runBlocking {
        val gw = FakeGateway(
            mapOf(-1001L to channelItems(-1001L, 2L downTo 1L)),
            failing = setOf(-1001L),
        )
        val m = PlayQueueModel()
        m.enqueueChannel(-1001L, "A")
        val src = PlayQueueFeedSource(gw, buildPlayPlan(m.queue), PlayQueueOrder.MANUAL)
        val err = runCatching { src.nextPage(4) }.exceptionOrNull()
        assertTrue(
            "顺序段走=错误上抛（队列停在该段由用户 skip/重试处置，不静默吞）",
            err is RuntimeException,
        )
    }

    @Test
    fun `reset重放_段游标全复位同序`() = runBlocking {
        val gw = FakeGateway(
            mapOf(
                -1001L to channelItems(-1001L, 3L downTo 1L),
                -1002L to channelItems(-1002L, 3L downTo 1L),
            ),
        )
        val m = PlayQueueModel()
        m.enqueueChannel(-1001L, "A")
        m.enqueueChannel(-1002L, "B")
        val src = PlayQueueFeedSource(gw, buildPlayPlan(m.queue), PlayQueueOrder.MANUAL)
        val first = src.nextPage(4)
        val second = src.nextPage(4)
        assertTrue("两页拉完（首页 A 整段+B 头，次页 B 余量）", first.items.isNotEmpty() && second.items.isNotEmpty())
        src.reset()
        val replay1 = src.nextPage(4)
        assertEquals("reset=段游标全复位重放（首页原样再现）", first.items.map { it.key }, replay1.items.map { it.key })
        val replay2 = src.nextPage(4)
        assertEquals(second.items.map { it.key }, replay2.items.map { it.key })
    }

    @Test
    fun `entryIdOf反查_单条精确命中优先_频道按chatId_失联null`() {
        val gw = FakeGateway(mapOf(-1001L to emptyList()))
        val m = PlayQueueModel()
        m.enqueueChannel(-1001L, "A")
        m.enqueueItem(item("s1", -1001L)) // 单条与频道段同 chatId：单条优先
        val src = PlayQueueFeedSource(gw, buildPlayPlan(m.queue), PlayQueueOrder.MANUAL)
        val singleEntryId = m.queue.entries[1].id
        val channelEntryId = m.queue.entries[0].id
        assertEquals(
            "单条段 key 精确命中优先（显式入单语境）",
            singleEntryId,
            src.entryIdOf(item("s1", -1001L)),
        )
        assertEquals(
            "频道段按 chatId 命中（items 流的频道条目）",
            channelEntryId,
            src.entryIdOf(item("c-1001m9", -1001L, 9L)),
        )
        assertNull("失联（不在任何段）=null（调用方跳过记账）", src.entryIdOf(item("x", -9999L)))
    }

    @Test
    fun `request聚合语义_空单到底空页`() = runBlocking {
        val gw = FakeGateway(mapOf(-1001L to emptyList()))
        val src = PlayQueueFeedSource(gw, emptyList(), PlayQueueOrder.MANUAL)
        assertNull("空 request=聚合语义（无单一频道无搜索词）", src.request.channelId)
        assertNull(src.request.searchQuery)
        val p = src.nextPage(20)
        assertTrue("空单=首拉即到底（空页+null 游标）", p.items.isEmpty())
        assertNull(p.nextCursor)
    }
}
