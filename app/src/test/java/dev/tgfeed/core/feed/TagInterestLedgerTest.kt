package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [TagInterestLedger]（R298 tag 轴 + 类型轴兴趣账本）与排序纯函数件的行为单测。
 * 时钟全部注入（nowMs 可变），衰减/快速学习/先验/落盘往返逐条锚定。
 */
class TagInterestLedgerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var now = 1_000_000L

    /** 每账本独立文件（同路径多实例会经落盘文件互相污染——首轮记录即触发首存） */
    private var ledgerSeq = 0

    private fun newLedger(): TagInterestLedger =
        TagInterestLedger(
            java.io.File(java.io.File(tempFolder.root, "store"), "tag_interest_${ledgerSeq++}.json"),
        ) { now }

    private fun item(
        caption: String = "",
        tags: List<String>? = null,
        mediaType: Int = 0,
        key: String = "k1",
    ) = VideoItem(
        key = key, chatId = 1L, messageId = 1L, channelTitle = "频道A", caption = caption,
        durationSec = 30, width = 720, height = 1280, fileId = -1, sizeBytes = 1L,
        playUri = null, mediaType = mediaType, itemTags = tags,
    )

    // ---------------- 记账与轴快照 ----------------

    @Test
    fun `有效观看记正分且轴快照归一化到1`() {
        val l = newLedger()
        l.recordSettle(listOf("美食", "旅行"), mediaType = 0, watchSec = 20, durationSec = 30)
        l.recordSettle(listOf("美食"), mediaType = 0, watchSec = 30, durationSec = 30)
        val axis = l.tagAxisSnapshot()
        assertEquals(1.0, axis["美食"]!!, 1e-9) // 最强 tag 归一为 1
        val travel = axis["旅行"]!!
        assertTrue("较弱 tag 归一后 ∈(0,1)：$travel", travel > 0.0 && travel < 1.0)
        assertTrue(l.hasAnySignal())
        assertEquals(2, l.validWatchCount())
    }

    @Test
    fun `秒划记负分且负轴保留符号`() {
        val l = newLedger()
        l.recordSettle(listOf("美食"), mediaType = 0, watchSec = 30, durationSec = 30) // 正
        l.recordSettle(listOf("广告"), mediaType = 0, watchSec = 1, durationSec = 30) // 秒划
        val axis = l.tagAxisSnapshot()
        assertTrue(axis["美食"]!! > 0.0)
        assertTrue("负轴必须保留符号（dinAttentionScore 跳负口径不适配 tag 负轴）", axis["广告"]!! < 0.0)
        // 召回侧只出正分
        val top = l.topInterestTags(10)
        assertEquals(1, top.size)
        assertEquals("美食", top[0].first)
    }

    @Test
    fun `快速学习期前50笔记账翻倍`() {
        val l = newLedger()
        l.recordSettle(listOf("美食"), mediaType = 0, watchSec = 10, durationSec = 100)
        val early = l.tagAxisSnapshot()["美食"]!!
        // 单独账本对照：非快速学习期同额记账（先灌满 50 笔无关 tag 再记）
        val l2 = newLedger()
        repeat(TagInterestLedger.QUICK_LEARN_LIMIT) {
            l2.recordSettle(listOf("背景$it"), mediaType = 0, watchSec = 10, durationSec = 100)
        }
        l2.recordSettle(listOf("美食"), mediaType = 0, watchSec = 10, durationSec = 100)
        val late = l2.tagAxisSnapshot()["美食"]!!
        assertEquals(TagInterestLedger.QUICK_LEARN_GAIN, early / late, 1e-9)
    }

    @Test
    fun `tag轴按半衰期14天衰减`() {
        val l = newLedger()
        l.recordSettle(listOf("美食"), mediaType = 0, watchSec = 20, durationSec = 30)
        val fresh = l.tagAxisSnapshot() // 归一化前绝对分拿不到——用两时点同键相对比较
        // 同账本再记一笔参照 tag，然后时间前进 14 天：老分应≈半衰
        val l2 = newLedger()
        l2.recordSettle(listOf("旧爱"), mediaType = 0, watchSec = 20, durationSec = 30)
        now += 14L * 24 * 3600 * 1000 // +14 天
        l2.recordSettle(listOf("新欢"), mediaType = 0, watchSec = 20, durationSec = 30)
        val axis = l2.tagAxisSnapshot()
        val oldScore = axis["旧爱"]!!
        // 新欢是最大值=1.0；旧爱 14 天前同额 → 归一后 ≈ 0.5
        assertEquals(0.5, oldScore, 0.02)
        assertTrue(fresh.isNotEmpty())
    }

    @Test
    fun `类型轴无证据直通1正证据上浮负证据下沉`() {
        val l = newLedger()
        l.recordSettle(emptyList(), mediaType = 0, watchSec = 30, durationSec = 30) // 视频正
        l.recordSettle(emptyList(), mediaType = 2, watchSec = 1, durationSec = 30)  // 音频秒划
        val f = l.typeFactorSnapshot()
        assertEquals(TagInterestLedger.TYPE_FACTOR_CAP, f[0], 1e-9) // 最强类型到顶
        assertTrue("负证据类型下沉且不破地板：${f[2]}", f[2] >= TagInterestLedger.TYPE_FACTOR_FLOOR && f[2] < 1.0)
        assertEquals("无证据类型中性直通", 1.0, f[1], 1e-9)
        // 全空账本全 1.0（冷启动零分支变化）
        val empty = newLedger().typeFactorSnapshot()
        assertTrue(empty.all { it == 1.0 })
    }

    // ---------------- 先验（冷启动） ----------------

    @Test
    fun `先验灌注幂等同键重灌零变化`() {
        val l = newLedger()
        val prior = mapOf("美食" to (TagInterestLedger.PRIOR_WEIGHT.toDouble() to "美食"))
        l.seedPrior(prior, "key1")
        val first = l.tagAxisSnapshot()
        l.seedPrior(prior, "key1") // 同键重灌
        assertEquals(first, l.tagAxisSnapshot())
        assertEquals(prior.keys, l.topInterestTags(10).map { it.first }.toSet())
    }

    @Test
    fun `先验整区替换不累积`() {
        val l = newLedger()
        l.seedPrior(mapOf("美食" to (0.3 to "美食")), "key1")
        l.seedPrior(mapOf("旅行" to (0.3 to "旅行")), "key2") // 来源集变了
        val top = l.topInterestTags(10).map { it.first }.toSet()
        assertEquals(setOf("旅行"), top) // 旧先验被替换，不是累加
    }

    @Test
    fun `观测盖过先验同键取大`() {
        val l = newLedger()
        l.seedPrior(mapOf("美食" to (TagInterestLedger.PRIOR_WEIGHT.toDouble() to "美食")), "k")
        // 观测段大额记账（快速学习 ×2：20s/10=2.0×2=4.0 ≫ 0.3 先验）
        l.recordSettle(listOf("美食"), mediaType = 0, watchSec = 20, durationSec = 30)
        val top = l.topInterestTags(10)
        assertEquals("美食", top[0].first)
        // 归一化轴里是观测值（=1.0 归一锚点）
        assertEquals(1.0, l.tagAxisSnapshot()["美食"]!!, 1e-9)
    }

    // ---------------- 纯函数件 ----------------

    @Test
    fun `foldTagsOf已解析优先空表不回退`() {
        val parsed = item(caption = "有 #标签 的 caption", tags = emptyList())
        assertTrue("itemTags 空表=已解析无标签（权威空，不回退 caption）", foldTagsOf(parsed).isEmpty())
        val unparsed = item(caption = "今晚吃 #美食 大餐", tags = null)
        assertEquals(listOf("美食"), foldTagsOf(unparsed))
        val withTags = item(tags = listOf("美食", "美食", "旅行")) // 原形去重口径
        assertEquals(2, foldTagsOf(withTags).size)
    }

    @Test
    fun `foldTagsOf归一与全角井号`() {
        val it = item(caption = "＃旅行日记 和 #美食", tags = null)
        val tags = foldTagsOf(it)
        assertTrue("美食" in tags)
        assertTrue(tags.any { t -> t.startsWith("旅行") })
    }

    @Test
    fun `tagMatchScore开方归一防堆词`() {
        val axis = mapOf("美食" to 1.0)
        val one = tagMatchScore(listOf("美食"), axis)
        val stacked = tagMatchScore(listOf("美食", "美食", "美食"), axis) // 堆词
        assertEquals(one, stacked, 1e-9) // 同键堆词被开方归一压平
        val diluted = tagMatchScore(listOf("美食", "无关", "无关2"), axis)
        assertTrue("命中同额但候选词更多 → 分被稀释：$diluted < $one", diluted < one)
        val neg = tagMatchScore(listOf("美食"), mapOf("美食" to -0.5))
        assertTrue(neg < 0.0) // 负轴产生负分
        assertEquals(0.0, tagMatchScore(listOf("不存在"), axis), 1e-9)
        assertEquals(0.0, tagMatchScore(emptyList(), axis), 1e-9)
    }

    @Test
    fun `interestMultiplier轴空直通类型系数`() {
        val types = doubleArrayOf(1.3, 1.0, 0.7)
        assertEquals(1.3, interestMultiplier(listOf("美食"), emptyMap(), types, 0), 1e-9)
        assertEquals(0.7, interestMultiplier(listOf("美食"), emptyMap(), types, 2), 1e-9)
    }

    @Test
    fun `interestMultiplier命中加减分与钳制`() {
        val types = doubleArrayOf(1.0, 1.0, 1.0)
        val axis = mapOf("美食" to 1.0)
        val up = interestMultiplier(listOf("美食"), axis, types, 0)
        assertEquals(1.0 + INTEREST_ALPHA, up, 1e-9)
        val down = interestMultiplier(listOf("美食"), mapOf("美食" to -1.0), types, 0)
        assertEquals(1.0 - INTEREST_ALPHA, down, 1e-9)
        // 强命中 × 强类型也不破帽
        val capped = interestMultiplier(
            listOf("美食", "旅行"), mapOf("美食" to 1.0, "旅行" to 1.0),
            doubleArrayOf(1.3, 1.0, 1.0), 0, alpha = 10.0,
        )
        assertTrue(capped <= INTEREST_MULT_CAP * TagInterestLedger.TYPE_FACTOR_CAP + 1e-9)
    }

    // ---------------- 落盘往返 ----------------

    @Test
    fun `落盘往返保住轴与先验`() {
        val file = java.io.File(java.io.File(tempFolder.root, "store"), "tag_interest.json")
        val l1 = TagInterestLedger(file) { now }
        l1.recordSettle(listOf("美食", "旅行"), mediaType = 0, watchSec = 20, durationSec = 30)
        l1.recordSettle(listOf("广告"), mediaType = 2, watchSec = 1, durationSec = 30)
        l1.seedPrior(mapOf("摄影" to (0.3 to "摄影")), "k1")
        l1.flush()
        assertTrue(file.isFile)
        val l2 = TagInterestLedger(file) { now }
        val axis = l2.tagAxisSnapshot()
        assertEquals(1.0, axis["美食"]!!, 1e-9)
        assertTrue(axis["广告"]!! < 0.0)
        assertTrue(axis["摄影"]!! > 0.0)
        assertTrue(l2.typeFactorSnapshot()[2] < 1.0)
        assertEquals(1, l2.validWatchCount())
    }
}
