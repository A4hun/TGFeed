package dev.tgfeed.core.feed

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * R211 搜索群 bot 回复频道直荐纯函数单测：
 *  - [mergeRecommendEntry]：注册表合并语义（新条目/刷新只升不降/词随新命中刷新）；
 *  - [selectPendingVerifies]：待验证选择（直链在前零搜索成本/新鲜度降序/覆盖与负缓存剔除）；
 *  - [SearchGroupRecommendStore]：注册表落盘往返/负缓存 TTL/读侧 TTL/容量驱逐；
 *  - [AnswerStore.allKeywordAnswers]：带词投影（词与答案不丢位）；
 *  - [pickFallbackQuestion]：回看配对（窗口/本人/白名单 bot/应答者自身/非文本全排除，取最新）；
 *  - [DiscoveryGoldmine.groupCandidateQuality] / [groupCandidateActivity]：时效性 tie-break 档位；
 *  - [DiscoveryGoldmine.recommendLineOf]：直荐短语（任务书原话口径）。
 * 网络轮次（drainOnce/USER 车道验证）=设备验证面（真实网关+账本）。
 */
class SearchGroupRecommendTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun kwAns(
        link: String,
        keyword: String = "爱情公寓",
        fromGroup: Long = GRP1,
        lastTs: Long = 1_000L,
        chatId: Long = 0L,
        username: String = "",
        hintTitle: String = "",
        members: Int = 0,
    ) = KeywordAnswer(
        keyword,
        GroupAnswer(
            link = link, chatId = chatId, username = username, fromGroup = fromGroup,
            lastTs = lastTs, hits = 1, hintTitle = hintTitle, members = members,
        ),
    )

    // ---------------- mergeRecommendEntry ----------------

    @Test
    fun `新条目_全字段落位_hits为1`() {
        val e = mergeRecommendEntry(
            null, chatId = -100123, title = "频道甲", username = "abc",
            keyword = "爱情公寓", fromGroup = GRP1, lastTs = 5_000L,
            verifiedAt = 9_000L, lastActivitySec = 100L, members = 12,
        )
        assertEquals(-100123L, e.chatId)
        assertEquals("频道甲", e.title)
        assertEquals("爱情公寓", e.keyword)
        assertEquals(5_000L, e.lastTs)
        assertEquals(9_000L, e.verifiedAt)
        assertEquals(100L, e.lastActivitySec)
        assertEquals(12, e.members)
        assertEquals(1, e.hits)
    }

    @Test
    fun `刷新_新命中换词加命中_时间取新_已知线索只升不降`() {
        val first = mergeRecommendEntry(
            null, -100123, "频道甲", "abc", "爱情公寓", GRP1,
            5_000L, 9_000L, 100L, 12,
        )
        val refreshed = mergeRecommendEntry(
            first, -100123, "", "", "第4集", GRP2,
            8_000L, 9_000L, 0L, 0,
        )
        // 新命中之词/来源群刷新、时间取新、hits 累加
        assertEquals("第4集", refreshed.keyword)
        assertEquals(GRP2, refreshed.fromGroup)
        assertEquals(8_000L, refreshed.lastTs)
        assertEquals(2, refreshed.hits)
        // 已知线索不降级：标题/用户名/活跃度/成员数保持
        assertEquals("频道甲", refreshed.title)
        assertEquals("abc", refreshed.username)
        assertEquals(100L, refreshed.lastActivitySec)
        assertEquals(12, refreshed.members)
        // 首验时刻不动（再被应答本身就是复验）
        assertEquals(9_000L, refreshed.verifiedAt)
    }

    // ---------------- selectPendingVerifies ----------------

    @Test
    fun `直链在前_用户名在后_各档内部新鲜度降序`() {
        val scoped = listOf(
            kwAns("https://t.me/old", lastTs = 100L, username = "old"),       // 用户名·旧
            kwAns("https://t.me/c/9/1", chatId = -1009L, lastTs = 200L),      // 直链·较新
            kwAns("https://t.me/new", lastTs = 900L, username = "new"),       // 用户名·新
            kwAns("https://t.me/c/8/1", chatId = -1008L, lastTs = 300L),      // 直链·最新
        )
        val out = selectPendingVerifies(scoped, emptyMap(), emptyMap(), emptySet(), limit = 10)
        assertEquals(
            listOf(-1008L, -1009L, 0L, 0L),
            out.map { it.answer.chatId },
        )
        assertEquals(listOf(900L, 100L), out.drop(2).map { it.answer.lastTs })
    }

    @Test
    fun `覆盖判定与负缓存剔除_chatId与用户名双口径`() {
        val scoped = listOf(
            kwAns("https://t.me/c/1/1", chatId = -1001L, lastTs = 500L),   // 已注册（chatId 认领）
            kwAns("https://t.me/known", username = "known", lastTs = 400L), // 已注册（username 认领）
            kwAns("https://t.me/skipme", username = "skipme", lastTs = 300L), // 负缓存
            kwAns("https://t.me/fresh", username = "fresh", lastTs = 200L),   // 应选
        )
        val byChatId = mapOf(-1001L to mergeRecommendEntry(null, -1001L, "", "", "w", 0L, 0L, 0L, 0L, 0))
        val byUsername = mapOf("known" to mergeRecommendEntry(null, -1002L, "", "known", "w", 0L, 0L, 0L, 0L, 0))
        val out = selectPendingVerifies(scoped, byChatId, byUsername, setOf("skipme"), limit = 10)
        assertEquals(listOf("https://t.me/fresh"), out.map { it.answer.link })
    }

    @Test
    fun `limit封顶_超过截断`() {
        val scoped = (1..5).map { kwAns("https://t.me/n$it", username = "n$it", lastTs = it * 10L) }
        val out = selectPendingVerifies(scoped, emptyMap(), emptyMap(), emptySet(), limit = 2)
        assertEquals(2, out.size)
        assertEquals(listOf(50L, 40L), out.map { it.answer.lastTs })
    }

    // ---------------- SearchGroupRecommendStore ----------------

    @Test
    fun `注册落盘往返_重启实例不丢`() {
        val dir: File = tempFolder.newFolder()
        val s1 = SearchGroupRecommendStore(dir)
        assertTrue(s1.register(-100123, "频道甲", "abc", "爱情公寓", GRP1, 5_000L, 9_000L, 100L, 12))
        // 第二次注册同 chatId=刷新不是新条目
        assertTrue(!s1.register(-100123, "", "", "第4集", GRP2, 8_000L, 9_000L, 0L, 0))
        s1.flush()
        val s2 = SearchGroupRecommendStore(dir)
        val e = s2.snapshot().single()
        assertEquals(-100123L, e.chatId)
        assertEquals("频道甲", e.title)
        assertEquals("abc", e.username)
        assertEquals("第4集", e.keyword)
        assertEquals(2, e.hits)
        assertEquals(8_000L, e.lastTs)
    }

    @Test
    fun `负缓存落盘往返_30天内记得`() {
        val dir: File = tempFolder.newFolder()
        val s1 = SearchGroupRecommendStore(dir)
        s1.markSkip("NotChannel", 1_000L)
        s1.flush()
        val s2 = SearchGroupRecommendStore(dir)
        assertTrue("notchannel" in s2.skips())
    }

    @Test
    fun `读侧TTL_过期条目不出_不触发落盘语义`() {
        val dir: File = tempFolder.newFolder()
        val s = SearchGroupRecommendStore(dir)
        // now 距甲 10 秒（TTL 内）、距乙约 23 天（超 14 天 TTL）
        val now = 2_000_000_000_000L
        s.register(-1001, "甲", "", "w", 0L, lastTs = now - 10_000L, 0L, 0L, 0)
        s.register(-1002, "乙", "", "w", 0L, lastTs = 100L, 0L, 0L, 0)
        val out = s.entries(nowMs = now)
        assertEquals(listOf(-1001L), out.map { it.chatId })
    }

    @Test
    fun `容量驱逐_超上限按lastTs最老踢`() {
        val dir: File = tempFolder.newFolder()
        val s = SearchGroupRecommendStore(dir)
        for (i in 1..SearchGroupRecommendStore.MAX_ENTRIES + 5) {
            s.register(-100L - i, "t$i", "", "w", 0L, lastTs = i * 10L, 0L, 0L, 0)
        }
        assertEquals(SearchGroupRecommendStore.MAX_ENTRIES, s.snapshot().size)
        // 最老的 5 个（lastTs 最小）被踢
        assertTrue(s.snapshot().none { it.title == "t1" || it.title == "t5" })
        assertTrue(s.snapshot().any { it.title == "t${SearchGroupRecommendStore.MAX_ENTRIES + 5}" })
    }

    // ---------------- AnswerStore.allKeywordAnswers ----------------

    @Test
    fun `带词投影_词与答案不丢位_含命中计数`() {
        val dir: File = tempFolder.newFolder()
        val store = AnswerStore(dir)
        store.recordAnswer("爱情公寓", GRP1, "https://t.me/abc")
        store.recordAnswer("爱情公寓", GRP1, "https://t.me/abc") // 同词同链：hits 累加
        store.recordAnswer("第4集", GRP2, "https://t.me/c/77/1")
        val out = store.allKeywordAnswers()
        assertEquals(2, out.size) // 每词一条投影（同词同链命中累加在 answer.hits 上）
        val byWord = out.groupBy { it.keyword }
        assertEquals(1, byWord["爱情公寓"]!!.size)
        assertEquals(2, byWord["爱情公寓"]!![0].answer.hits)
        assertEquals("https://t.me/c/77/1", byWord["第4集"]!![0].answer.link)
        assertEquals(-10077L, byWord["第4集"]!![0].answer.chatId) // 直链 chatId 折算
    }

    // ---------------- pickFallbackQuestion ----------------

    private fun msg(
        id: Long,
        date: Int,
        text: String,
        senderId: Long = 7L,
        outgoing: Boolean = false,
    ): TdApi.Message = TdApi.Message().apply {
        this.id = id
        this.date = date
        this.isOutgoing = outgoing
        this.senderId = TdApi.MessageSenderUser(senderId)
        // TDLib 1.8.67：MessageText 三参（text + linkPreview + linkPreviewOptions），预览传 null
        this.content = TdApi.MessageText(TdApi.FormattedText(text, null), null, null)
    }

    @Test
    fun `回看配对_取窗口内最新人类文本`() {
        val reply = msg(id = 99, date = 1_000, text = "结果 https://t.me/x", senderId = 55L)
        val cands = listOf(
            msg(10, 950, "闲聊甲", senderId = 7L),
            msg(20, 980, "爱情公寓第4集", senderId = 8L), // 窗口内最新人类提问
            msg(30, 999, "bot 菜单", senderId = 9L),      // 更近但被白名单排除
        )
        val out = pickFallbackQuestion(1_000, 99, 55L, cands, whitelistIds = setOf(9L))
        assertEquals("爱情公寓第4集", out)
    }

    @Test
    fun `回看配对_同秒提问也算窗口内_bot秒回常态`() {
        val reply = msg(id = 99, date = 1_000, text = "结果", senderId = 55L)
        val q = msg(id = 20, date = 1_000, text = "爱情公寓第4集", senderId = 8L) // 同一秒提问
        assertEquals("爱情公寓第4集", pickFallbackQuestion(1_000, 99, 55L, listOf(q), emptySet()))
    }

    @Test
    fun `回看配对_窗口外本人应答者自身与非文本全排除`() {
        val reply = msg(id = 99, date = 1_000, text = "r", senderId = 55L)
        val old = msg(10, 800, "90 秒前太老", senderId = 7L) // 超 FALLBACK_PAIR_WINDOW_SEC(90)
        val mine = msg(20, 990, "我发的", senderId = 7L, outgoing = true)
        val self = msg(21, 991, "应答者补发", senderId = 55L)
        val media = TdApi.Message().apply {
            id = 22; date = 992; isOutgoing = false
            senderId = TdApi.MessageSenderUser(7L)
            content = TdApi.MessagePhoto()
        }
        assertNull(pickFallbackQuestion(1_000, 99, 55L, listOf(old, mine, self, media), emptySet()))
    }

    // ---------------- DiscoveryGoldmine 群候选纯函数 ----------------

    @Test
    fun `群候选质量_验证与新鲜度升档_恒不过丰度线`() {
        val stale = DiscoveryGoldmine.groupCandidateQuality(endorse = 100, verified = false, signalAgeMs = 30L * 24 * 3600_000)
        val verified = DiscoveryGoldmine.groupCandidateQuality(endorse = 100, verified = true, signalAgeMs = 30L * 24 * 3600_000)
        val fresh24 = DiscoveryGoldmine.groupCandidateQuality(endorse = 100, verified = true, signalAgeMs = 3600_000)
        val fresh7 = DiscoveryGoldmine.groupCandidateQuality(endorse = 100, verified = true, signalAgeMs = 3L * 24 * 3600_000)
        assertEquals(0.35, stale, 1e-9)
        assertEquals(0.45, verified, 1e-9)
        assertEquals(0.50, fresh24, 1e-9) // 0.35+0.10+0.05 顶到封顶
        assertEquals(0.47, fresh7, 1e-9)
        // 恒封顶 0.5 < 0.55 丰度升档线
        assertTrue(DiscoveryGoldmine.groupCandidateQuality(1000, true, 0L) <= 0.5)
        // endorse 折算：低热度按 /20 原值（封顶不放大）
        assertEquals(0.10, DiscoveryGoldmine.groupCandidateQuality(2, true, 3600_000), 1e-9)
    }

    @Test
    fun `群候选活跃度_新鲜度分档压过计数`() {
        assertEquals(10, DiscoveryGoldmine.groupCandidateActivity(endorse = 99, signalAgeMs = 3600_000))
        assertEquals(5, DiscoveryGoldmine.groupCandidateActivity(endorse = 99, signalAgeMs = 3L * 24 * 3600_000))
        assertEquals(99, DiscoveryGoldmine.groupCandidateActivity(endorse = 99, signalAgeMs = 30L * 24 * 3600_000))
    }

    @Test
    fun `直荐短语_任务书原话_不带待验货后缀`() {
        val line = DiscoveryGoldmine.recommendLineOf(
            g = null, groupEndorse = 3, groupSearchWord = "爱情公寓",
            memberCount = null, userGroupVerified = true,
        )
        assertEquals("你的搜索群里有人搜过「爱情公寓」", line)
    }

    @Test
    fun `未验证群传_旧短语不变_双置信仍最高位`() {
        assertEquals(
            "群友搜「资源」找到 · 待深索引验货",
            DiscoveryGoldmine.recommendLineOf(null, 3, "资源", null, userGroupVerified = false),
        )
        val g = DiscoveryGoldmineTestHelper.gold(1, richness = 0.92)
        assertEquals(
            "深索引验货（丰度 92 分）· 你的搜索群在传",
            DiscoveryGoldmine.recommendLineOf(g, 5, "资源", null, userGroupVerified = true),
        )
    }
}

/** 测试助手（同包内复用 ChannelGold 构造，避免复制 22 字段） */
internal object DiscoveryGoldmineTestHelper {
    fun gold(chatId: Long, richness: Double) = DiscoveryGoldmine.ChannelGold(
        chatId = chatId, title = "频道$chatId", items = 1000, richness = richness,
        probed = true, videoShare = 0.8, recent7 = 0, prev7 = 0,
        lastActivitySec = 1_000_000_000L, tier = 1, deadRate = 0.0,
        adRate = 0.0, stormRate = 0.0, farm = false,
    )
}

/** 测试用常量群 id（命名可读） */
private const val GRP1 = -100111L
private const val GRP2 = -100222L
