package dev.tgfeed.core.feed

import android.content.Context
import dev.tgfeed.core.store.LocalStore
import dev.tgfeed.core.td.VideoItem
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [rankWeightedPage]（FeedSource.kt 文件级排序链）的行为单测：
 * 文件级去重 → 赛马分层 → 加权重排 → 滑动窗口打散 → 头部降权 → 探索注入 → 保底露头。
 *
 * 进程级挂接件的处理口径（都是"未注册即退化"设计，单测友好）：
 *  - ExposureTrackerHolder：每个用例注册一个挂在临时目录 LocalStore 上的全新实例
 *    （"后注册生效"语义顶掉上个用例的残留），用例间互不污染；
 *  - LinUCBHolder / ImplicitFeedbackHolder / ChannelFatigueHolder：全程不注册 →
 *    探索选臂回退"主序首条"、隐式降权与频道疲劳直通，符合被测函数的单测退化设计；
 *  - GuaranteedPoolHolder：保底露头用例显式 update 名册，用例后清空防泄漏。
 */
class RankWeightedPageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: LocalStore

    @Before
    fun setUp() {
        // 假 Context：filesDir 指向 JUnit 临时目录，LocalStore 真实落盘、用完即焚
        val context = mockk<Context>()
        every { context.filesDir } returns tempFolder.root
        store = LocalStore(context)
        // 全新赛马追踪器（默认无"新发现频道"供给）
        ExposureTrackerHolder.register(store) { emptySet() }
    }

    @After
    fun tearDown() {
        // 保底名册是进程级状态：清空防泄漏到后续用例（空名册 = 露头段零开销直通）
        GuaranteedPoolHolder.update(emptySet())
    }

    // ---------------- 夹具 ----------------

    /** 构造一条测试条目：dateEpochSec=0 → 新鲜度系数恒 0.80，所有条目同档无偏 */
    private fun video(chatId: Long, seq: Int, durationSec: Int = 30) = VideoItem(
        key = "k_${chatId}_$seq",
        chatId = chatId,
        messageId = seq.toLong(),
        channelTitle = "频道$chatId",
        caption = "",
        durationSec = durationSec,
        width = 720,
        height = 1280,
        fileId = -1,
        sizeBytes = 1L,
        playUri = null,
        fileUniqueId = "u_${chatId}_$seq",
    )

    /** 预记一次曝光：全部条目 tier=NORMAL——小流量池（NEW 段位）整段为空，
     *  排序只剩"加权主序 + 放置层约束"，排除 NEW 段位随机拆分对断言的干扰 */
    private fun markAllNormal(items: List<VideoItem>) {
        ExposureTrackerHolder.current()!!.recordImpressions(items.map { it.key })
    }

    /** 统计输出页每个 5 滑窗内同频道最大出现次数（窗口硬约束的观测量） */
    private fun maxSameChannelInAnyWindow(out: List<VideoItem>, window: Int = 5): Int {
        var worst = 0
        for (s in 0..out.size - window) {
            val counts = HashMap<Long, Int>()
            for (i in s until s + window) {
                val c = (counts[out[i].chatId] ?: 0) + 1
                counts[out[i].chatId] = c
                if (c > worst) worst = c
            }
        }
        return worst
    }

    // ---------------- ① 多样性硬约束：滑动窗口 ----------------

    @Test
    fun `滑动窗口硬约束在非降级区恒成立`() {
        // 行为：任意连续 5 条内同频道 ≤2（WINDOW=5 / WINDOW_MAX=2）。
        //
        // 断言区间的理论依据（不是统计拟合）：放置层唯一可能违反窗口的路径是
        // "硬放队首"，而硬放只在所有剩余条目的频道全被窗口封死时触发——
        // 尾部 4 条最多封死 2 个频道，故触发硬放时剩余条目 ≤ 2×每频道条数。
        // 8 频道 × 8 条 = 64 条：前 64-2×8=48 个位置结构性不可能出现硬放，
        // 完全落在该区间的窗口（起点 ≤43）必须恒满足 ≤2。跑 200 轮随机主序加固。
        val chatIds = 1L..8L
        val perChannel = 8
        val n = chatIds.count() * perChannel // 64
        val cleanPrefix = n - 2 * perChannel // 硬放不可达区间长度 = 48
        val weights = chatIds.associateWith { 1.0 }
        repeat(200) {
            val items = chatIds.flatMap { c -> (1..perChannel).map { s -> video(c, s) } }
            val out = rankWeightedPage(items, weights, emptySet())
            assertEquals(n, out.size)
            assertEquals(items.map { it.key }.toSet(), out.map { it.key }.toSet())
            val head = out.subList(0, cleanPrefix)
            assertEquals(
                "非降级区存在连续 5 条内同频道超过 2 条的窗口",
                2, maxSameChannelInAnyWindow(head),
            )
        }
    }

    @Test
    fun `极端单频道页宁违反窗口约束也不丢条目`() {
        // 行为（设计内降级）：整页同频道时窗口约束在数学上不可满足，
        // 放置层硬放队首保底——条目一条不丢（"宁违反约束不丢条目"的显式语义）
        val items = (1..10).map { video(7L, it) }
        val out = rankWeightedPage(items, mapOf(7L to 1.0), emptySet())
        assertEquals(10, out.size)
        assertEquals(items.map { it.key }.toSet(), out.map { it.key }.toSet())
    }

    // ---------------- ② 多样性硬约束：头部降权（页内占比 ≤25%，溢出沉底） ----------------

    @Test
    fun `单频道页内占比硬上限为四分之一且溢出条目沉到页尾`() {
        // 页面构成：头部频道 A 40 条 + 60 个单条频道各 1 条，共 100 条。
        // shareCap = max(4, ceil(100×25%)) = 25。单条频道结构性保证：
        // 占位阶段窗口约束对它们恒可满足（每个只有 1 条），主序在 A 触顶前永不降级——
        // 因此前 85 条恰好 = 25 条 A + 全部 60 条单条频道，余下 15 条 A 沉到页尾
        // （"排到下一页"语义），且占位区每个窗口都过了 windowOk 检查。
        val hotA = 100L
        val items = (1..40).map { video(hotA, it) } +
            (1000L..1059L).map { c -> video(c, 1) }
        markAllNormal(items)
        val weights = (listOf(hotA) + (1000L..1059L)).associateWith { 1.5 }

        val out = rankWeightedPage(items, weights, emptySet())

        assertEquals(100, out.size)
        assertEquals(items.map { it.key }.toSet(), out.map { it.key }.toSet())
        val head = out.subList(0, 85)
        val tail = out.subList(85, 100)
        assertEquals("占位区 A 条数应恰好等于占比上限 25", 25, head.count { it.chatId == hotA })
        assertTrue("占比溢出的 A 条目应全部沉到页尾", tail.all { it.chatId == hotA })
        assertEquals("占位区窗口约束不应被打破", 2, maxSameChannelInAnyWindow(head))
    }

    // ---------------- ③ 探索注入：零画像/低权重冷门频道保底位 ----------------

    @Test
    fun `探索配额位稳定落在冷门频道上`() {
        // 页面构成：8 个高热频道 × 10 条（权重 100，远超冷门）+ 4 个冷门频道 × 5 条
        // （权重 0.3 ≤ 探索档 0.6），共 100 条 → 探索配额 = 100/10 = 10 格，
        // 落在第 10/20/…/100 个位置（0 基下标 9,19,…,99）。
        // 高热权重拉到 100 让冷门在主序里几乎必然沉底（不会被正常流程提前抽干），
        // 因此每个探索格都必须抓到冷门条目——验证"防马太效应"的保底位真的保底。
        val hotIds = 1L..8L
        val coldIds = 10L..13L
        val items = hotIds.flatMap { c -> (1..10).map { s -> video(c, s) } } +
            coldIds.flatMap { c -> (1..5).map { s -> video(c, s) } }
        markAllNormal(items)
        val weights = hotIds.associateWith { 100.0 } + coldIds.associateWith { 0.3 }

        val out = rankWeightedPage(items, weights, emptySet())

        assertEquals(100, out.size)
        assertEquals(items.map { it.key }.toSet(), out.map { it.key }.toSet())
        for (pos in 9 until 100 step 10) {
            assertTrue(
                "第 ${pos + 1} 个位置是探索格，应放冷门频道（实际 chatId=${out[pos].chatId}）",
                out[pos].chatId in coldIds,
            )
        }
    }

    // ---------------- ④ 探索注入：新发现频道半区（新面孔配额） ----------------

    @Test
    fun `探索配额的新面孔半区稳定落在图谱新频道上`() {
        // 页面构成：9 个高热频道 × 10 条（权重 100）+ 4 个"近 7 天 firstSeen 的新频道"
        // 各 5 条（权重 1.0：高于探索档 0.6——不是冷门，只能靠"新面孔"半区上位）。
        // 探索配额一半给新频道、一半给冷门，冷门池为空时交叉兜底仍给新频道——
        // 全部探索格都应被新频道条目占据。
        // R150 夹具加固（随机种子翻车实测 1/10）：旧夹具 2 个新频道恰 10 条对 10 个
        // 探索格零余量，且主序末段若聚出 2 条同频道新条目，最后一个探索格的幸存候选
        // 全被滑窗约束挡住 → 兜底回落主序（热频道占探索格）→ 断言随机翻车；
        // 对齐冷门用例的 4 频道多样性后同窗口最多封锁 2 个频道、必有候选存活，
        // 断言对任意 weightedShuffle 抽样序稳定成立
        val newIds = setOf(500L, 501L, 502L, 503L)
        // 本用例需要"新发现频道"供给：后注册生效，顶掉 setUp 里的空供给
        ExposureTrackerHolder.register(store) { newIds }
        val hotIds = 1L..9L
        val items = hotIds.flatMap { c -> (1..10).map { s -> video(c, s) } } +
            newIds.flatMap { c -> (1..5).map { s -> video(c, s) } }
        markAllNormal(items)
        val weights = hotIds.associateWith { 100.0 } + newIds.associateWith { 1.0 }

        val out = rankWeightedPage(items, weights, emptySet())

        assertEquals(110, out.size)
        assertEquals(items.map { it.key }.toSet(), out.map { it.key }.toSet())
        val n = out.size
        for (pos in 9 until n step 10) {
            assertTrue(
                "第 ${pos + 1} 个位置是探索格，应放新面孔频道（实际 chatId=${out[pos].chatId}）",
                out[pos].chatId in newIds,
            )
        }
    }

    // ---------------- ⑤ R82 保底露头：保底频道换入头部窗口 ----------------

    @Test
    fun `保底频道条目按配额换入头部窗口且不增删条目`() {
        // 页面构成：保底频道 G 5 条（权重 0.3 且标记已看 ×0.2，主序里几乎必然沉底）
        // + 19 个高热频道 × 5 条（权重 1.5），共 100 条。
        // 行为：注入保底名册后（露头配额 3/轮），排序结果头部窗口（前 20 条）内
        // 保底条目被补足到 3 条——修"保底进池但沉底，前几屏永远刷不到"；
        // 换位是纯换位，条目不增不减。
        val g = 9_000_001L
        val hotIds = 1L..19L
        val gItems = (1..5).map { video(g, it) }
        val hotItems = hotIds.flatMap { c -> (1..5).map { s -> video(c, s) } }
        val items = hotItems + gItems
        markAllNormal(items)
        val weights = hotIds.associateWith { 1.5 } + mapOf(g to 0.3)
        val seenKeys = gItems.mapTo(HashSet()) { it.key }

        GuaranteedPoolHolder.update(setOf(g)) // 注入本轮保底名册（露头配额重置为 3）
        val out = rankWeightedPage(items, weights, seenKeys)

        assertEquals(100, out.size)
        assertEquals(items.map { it.key }.toSet(), out.map { it.key }.toSet())
        val headCount = out.subList(0, 20).count { it.chatId == g }
        assertTrue("头部窗口（前 20 条）保底条目应补足到配额 3 条（实际 $headCount）", headCount >= 3)

        // 名册重新注入 = 新一轮：露头配额重置，再次排序仍能把保底条目补进头部窗口
        GuaranteedPoolHolder.update(setOf(g))
        val out2 = rankWeightedPage(items, weights, seenKeys)
        val headCount2 = out2.subList(0, 20).count { it.chatId == g }
        assertTrue("新一轮配额重置后头部窗口仍应补足 3 条（实际 $headCount2）", headCount2 >= 3)
    }

    @Test
    fun `保底名册为空时露头段直通`() {
        // 行为：名册空集（未跑聚合轮/Mock 网关）时露头段整体跳过——
        // 排序正常完成、条目不丢不重（本用例同时也是其他用例名册泄漏的反向保险）。
        GuaranteedPoolHolder.update(emptySet())
        val items = (1L..5L).flatMap { c -> (1..4).map { s -> video(c, s) } }
        val out = rankWeightedPage(items, (1L..5L).associateWith { 1.0 }, emptySet())
        assertEquals(20, out.size)
        assertEquals(items.map { it.key }.toSet(), out.map { it.key }.toSet())
    }

    // ---------------- ⑥ 文件级去重（排序链第一段） ----------------

    @Test
    fun `同内容指纹跨频道只留权重高者的一条`() {
        // 行为：dedupeByFile——同 fileUniqueId 的条目只留频道权重高者；
        // 空指纹条目不参与去重原样保留。
        val dup1 = video(1L, 1).copy(fileUniqueId = "same-uid")
        val dup2 = video(2L, 1).copy(fileUniqueId = "same-uid")
        val noUid = video(3L, 1).copy(fileUniqueId = "")
        val items = listOf(dup1, dup2, noUid)
        markAllNormal(items)
        // 频道 2 权重更高 → 保留 dup2 的位置
        val out = rankWeightedPage(items, mapOf(1L to 0.5, 2L to 1.5, 3L to 1.0), emptySet())
        assertEquals(2, out.size)
        assertTrue(out.none { it.key == dup1.key })
        assertTrue(out.any { it.key == dup2.key })
        assertTrue(out.any { it.key == noUid.key })
    }
}
