package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * R183 双宝藏引擎纯函数单测（[DiscoveryGoldmine] + [AdPatternFilter]）：
 *  - head 行 → [IndexTierPolicy.RichnessSignals] 折算（同公式同口径的输入适配层）；
 *  - 公平轮转（R176「最少曝光优先」：曝光账升序 + 客观 tie-break + 换一批排除）；
 *  - 客观推荐语/佐证徽章（终版口径：只写事实不猜口味）；
 *  - 过滤闸四信号（广告话术密度/模板风暴/spam 农场/双零拦截）；
 *  - 快照落盘读盘往返（临时文件 + rename 原子写的读侧契约）。
 * SQL/索引层（channelCountRollup/channelHeadRows/buildSnapshot 全链）=设备验证面。
 */
class DiscoveryGoldmineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private companion object {
        const val EPS = 1e-9
        val DAY_SEC = 86_400L
    }

    private fun headRow(
        daysAgo: Long,
        mediaType: Int = 0,
        caption: String = "第1集 精彩内容",
    ) = DeepSearchIndex.ChannelHeadRow(
        dateEpochSec = 1_000_000_000L + 30L * DAY_SEC - daysAgo * DAY_SEC,
        mediaType = mediaType,
        caption = caption,
        channelTitle = "测试频道",
    )

    private fun gold(
        chatId: Long,
        items: Int = 1000,
        richness: Double = 0.6,
        probed: Boolean = true,
        recent7: Int = 0,
        prev7: Int = 0,
        tier: Int = 1,
        adRate: Double = 0.0,
        stormRate: Double = 0.0,
        farm: Boolean = false,
    ) = DiscoveryGoldmine.ChannelGold(
        chatId = chatId,
        title = "频道$chatId",
        items = items,
        richness = richness,
        probed = probed,
        videoShare = 0.8,
        recent7 = recent7,
        prev7 = prev7,
        lastActivitySec = 1_000_000_000L,
        tier = tier,
        deadRate = 0.0,
        adRate = adRate,
        stormRate = stormRate,
        farm = farm,
    )

    // ---------------- richnessSignalsOf（head 行 → R163 丰度信号） ----------------

    @Test
    fun `空head为未探针_与测过且低区分`() {
        val s = DiscoveryGoldmine.richnessSignalsOf(emptyList(), deadRate = 0.0)
        assertFalse(s.probed)
        assertEquals(0.0, IndexTierPolicy.richnessScore(s), EPS)
    }

    @Test
    fun `head折算_跨度视频占比caption率规整率全对`() {
        // 4 行跨 3 天：2 视频 + 2 图片；caption 3 非空（2 条「第N集」命中规整、1 条闲聊不命中）
        val head = listOf(
            headRow(0, mediaType = 0, caption = "第1集 开局"),
            headRow(1, mediaType = 0, caption = "第2集 发展"),
            headRow(2, mediaType = 1, caption = "宣传图"),
            headRow(3, mediaType = 1, caption = ""),
        )
        val s = DiscoveryGoldmine.richnessSignalsOf(head, deadRate = 0.1)
        assertTrue(s.probed)
        assertEquals(4, s.sampleItems)
        assertEquals(3 * DAY_SEC / 86_400.0, s.sampleSpanDays, EPS)
        assertEquals(0.5, s.videoShare, EPS)
        assertEquals(0.75, s.captionNonEmptyRate, EPS)
        assertEquals(2.0 / 3, s.episodeHitRate, EPS)
        // 平均 caption 长 = (6+6+3)/3（非空口径，空串不拖低信息量——与 R163 同约定）
        assertEquals((6 + 6 + 3) / 3.0, s.avgCaptionLen, EPS)
        assertEquals(0.1, s.deadRate, EPS)
    }

    @Test
    fun `head折算_直接喂R163公式_密度项惩罚死频道`() {
        // 64 行铺 945 天（低频道）vs 64 行铺 6 天（活跃）：其余分量相同，
        // 差距全部来自密度分（perWeek 0.5 vs 封顶 20）——近期口径对弃更频道的惩罚
        val dead = (0 until 64).map { headRow(it * 15L) }
        val alive = (0 until 64).map { headRow(it / 10L) }
        val rDead = IndexTierPolicy.richnessScore(DiscoveryGoldmine.richnessSignalsOf(dead, 0.0))
        val rAlive = IndexTierPolicy.richnessScore(DiscoveryGoldmine.richnessSignalsOf(alive, 0.0))
        assertTrue("死频道密度分应显著低于活跃频道 ($rDead vs $rAlive)", rDead < rAlive)
        assertEquals(0.3, rAlive - rDead, 0.05) // 差值≈密度分差（0.3·(1−0.024)）
    }

    // ---------------- fairRotationSelect（公平轮转，R176 思想） ----------------

    @Test
    fun `最少曝光优先_从未露面的排最前`() {
        val pool = listOf(gold(1, items = 10), gold(2, items = 20), gold(3, items = 30))
        val impressions = mapOf(1L to 5, 2L to 0, 3L to 2)
        val out = DiscoveryGoldmine.fairRotationSelect(pool, impressions, emptySet(), 3)
        assertEquals(listOf(2L, 3L, 1L), out.map { it.chatId })
    }

    @Test
    fun `同曝光并列_丰度降序再近期更新降序_chatId稳定`() {
        // 全员零曝光（首轮）：丰度 0.9 > 0.6 > 0.3；同丰度比 recent7；再同则 chatId 升序
        val pool = listOf(
            gold(10, richness = 0.6, recent7 = 0),
            gold(11, richness = 0.9),
            gold(12, richness = 0.6, recent7 = 5),
            gold(13, richness = 0.6, recent7 = 5),
        )
        val out = DiscoveryGoldmine.fairRotationSelect(pool, emptyMap(), emptySet(), 4)
        assertEquals(listOf(11L, 12L, 13L, 10L), out.map { it.chatId })
    }

    @Test
    fun `换一批排除集不占配额_排除后不足允许变短`() {
        val pool = listOf(gold(1), gold(2), gold(3))
        val out = DiscoveryGoldmine.fairRotationSelect(pool, emptyMap(), setOf(1L, 2L), 3)
        assertEquals(listOf(3L), out.map { it.chatId })
        // 池子见底（全被排除）：空表由 UI 空态承接
        assertTrue(
            DiscoveryGoldmine.fairRotationSelect(pool, emptyMap(), setOf(1L, 2L, 3L), 3).isEmpty(),
        )
    }

    @Test
    fun `limit截断与零守卫`() {
        val pool = listOf(gold(1), gold(2), gold(3))
        assertEquals(2, DiscoveryGoldmine.fairRotationSelect(pool, emptyMap(), emptySet(), 2).size)
        assertTrue(DiscoveryGoldmine.fairRotationSelect(emptyList(), emptyMap(), emptySet(), 5).isEmpty())
        assertTrue(DiscoveryGoldmine.fairRotationSelect(pool, emptyMap(), emptySet(), 0).isEmpty())
    }

    @Test
    fun `轮转连续两轮全覆盖_头部不霸屏`() {
        // 第一轮取前 2（tie-break 丰度序），曝光账 +1 后第二轮换出未露面的——
        // 人人有出场机会（公平轮转的结构性保证）
        val pool = listOf(gold(1), gold(2), gold(3), gold(4))
        val round1 = DiscoveryGoldmine.fairRotationSelect(pool, emptyMap(), emptySet(), 2)
        val impressions = round1.associate { it.chatId to 1 }
        val round2 = DiscoveryGoldmine.fairRotationSelect(pool, impressions, emptySet(), 2)
        assertEquals(setOf(1L, 2L), round1.map { it.chatId }.toSet())
        assertEquals(setOf(3L, 4L), round2.map { it.chatId }.toSet())
    }

    // ---------------- recommendLineOf / corroborationChipsOf（客观事实口径） ----------------

    @Test
    fun `双置信_索引验货加搜索群在传`() {
        val line = DiscoveryGoldmine.recommendLineOf(gold(1, richness = 0.92), groupEndorse = 5, groupSearchWord = null, memberCount = null)
        assertEquals("深索引验货（丰度 92 分）· 你的搜索群在传", line)
    }

    @Test
    fun `群搜未验_带词点名_无词通用`() {
        val g = null // 索引没档案（群里刚冒出来的新频道）
        assertEquals(
            "群友搜「资源」找到 · 待深索引验货",
            DiscoveryGoldmine.recommendLineOf(g, groupEndorse = 3, groupSearchWord = "资源", memberCount = null),
        )
        assertEquals(
            "你的搜索群在传 · 待深索引验货",
            DiscoveryGoldmine.recommendLineOf(g, groupEndorse = 3, groupSearchWord = null, memberCount = null),
        )
    }

    @Test
    fun `上升句_日更门槛与丰度补语`() {
        val g = gold(1, richness = 0.7, recent7 = 7, prev7 = 2)
        assertEquals("近 7 天更新 7 条 · 丰度 70 分", DiscoveryGoldmine.recommendLineOf(g, 0, null, null))
        // 低于门槛（<7 条）不配「正在上升」句式 → 落丰度存货句
        val g2 = gold(2, richness = 0.7, recent7 = 6)
        assertEquals("索引丰度 70 分 · 1000 条存货", DiscoveryGoldmine.recommendLineOf(g2, 0, null, null))
    }

    @Test
    fun `冷门宝藏句_丰度过线且成员少才出现`() {
        val g = gold(1, richness = 0.8, items = 3_200)
        assertEquals(
            "冷门但存货扎实：3200 条 · 仅 8000成员",
            DiscoveryGoldmine.recommendLineOf(g, 0, null, memberCount = 8000),
        )
        // 成员未知（null）不猜「冷门」；丰度不过线也不出现
        assertEquals("索引丰度 80 分 · 3200 条存货", DiscoveryGoldmine.recommendLineOf(g, 0, null, null))
        assertEquals(
            "索引丰度 40 分 · 3200 条存货",
            DiscoveryGoldmine.recommendLineOf(gold(2, richness = 0.4, items = 3200), 0, null, memberCount = 100),
        )
    }

    @Test
    fun `大数格式化_万级压缩`() {
        assertEquals("9800", DiscoveryGoldmine.fmtCount(9_800))
        assertEquals("3.2万", DiscoveryGoldmine.fmtCount(32_000))
        assertEquals("1万", DiscoveryGoldmine.fmtCount(10_000))
        assertEquals("3200成员", DiscoveryGoldmine.fmtMember(3_200))
    }

    @Test
    fun `佐证徽章_计数与封顶`() {
        val g = gold(1, richness = 0.8, recent7 = 20, tier = 1)
        val chips = DiscoveryGoldmine.corroborationChipsOf(
            g, groupCount = 2, groupEndorse = 5, plazaRecommends = 12, memberCount = 100,
        )
        // 全信号在场：N 群在搜 / 本周 N 条 / 系统验货 T1 / 全站 N 人推荐 / 冷门宝藏 → 封顶 3
        assertEquals(DiscoveryGoldmine.CORROBORATION_MAX, chips.size)
        assertEquals("2 群在搜", chips[0])
        assertEquals("本周 20 条", chips[1])
        assertEquals("系统验货 T1", chips[2])
        // 零信号 → 空表（UI 整行隐藏）
        assertTrue(
            DiscoveryGoldmine.corroborationChipsOf(
                gold(2, recent7 = 0, tier = -1), 0, 0, 0, null,
            ).isEmpty(),
        )
    }

    // ---------------- AdPatternFilter（过滤闸四信号） ----------------

    @Test
    fun `caption规范化_数字折叠为井号`() {
        assertEquals("第#集开局", AdPatternFilter.normalizeCaption("第3集 开局"))
        assertEquals("s#e#合集", AdPatternFilter.normalizeCaption("S01E12 合集"))
    }

    @Test
    fun `广告话术密度_命中占比与短文本不判`() {
        val captions = listOf("第1集 正剧", "加V看全集 私聊客服", "承接推广 位子有限", "第4集 结局")
        assertEquals(0.5, AdPatternFilter.adRateOf(captions), EPS)
        assertEquals(0.0, AdPatternFilter.adRateOf(emptyList()), EPS)
        // 短于 4 字的碎片不构成广告证据（链接残留）
        assertFalse(AdPatternFilter.isAdCaption("加V"))
        assertTrue(AdPatternFilter.isAdCaption("加V看全集"))
    }

    @Test
    fun `模板风暴率_单一模板占比_样本不足与短模板不判`() {
        // 同模板要够长（规范化后 ≥8 字）才构成证据：「第#集」这类 3 字短模板不计
        val storm = (1..9).map { "超清资源每日更新第$it 集精彩片段抢先看" }
        assertEquals(1.0, AdPatternFilter.stormRateOf(storm), EPS)
        assertEquals(0.0, AdPatternFilter.stormRateOf((1..9).map { "第$it 集" }), EPS)
        // 样本 <8 条不判（返回 0）
        assertEquals(0.0, AdPatternFilter.stormRateOf(storm.take(7)), EPS)
        // 正常频道：同模板占比 3/8 = 0.375 < 拒线
        val mixed = storm.take(3) + listOf(
            "完全不同的一条闲聊内容甲", "完全不同的二条闲聊内容乙", "完全不同的三条闲聊内容丙",
            "完全不同的四条闲聊内容丁", "完全不同的五条闲聊内容戊",
        )
        assertEquals(0.375, AdPatternFilter.stormRateOf(mixed), EPS)
        assertTrue(AdPatternFilter.stormRateOf(mixed) < AdPatternFilter.STORM_REJECT_RATE)
    }

    @Test
    fun `候选brief透传username_空白转null`() {
        // R183 开频道自愈线索链：答案库 username → 信号 → 候选 → brief——
        // ChannelFeedSource 撞 Chat not found 时按 username 精确解析建会话的原料
        val sig = DiscoveryGroupSignal(groupCount = 1, endorse = 2, lastTs = 0, hintTitle = "线索", members = 0, username = "abc")
        val withName = DiscoveryGoldCandidate(1L, "标题甲", null, sig, 0, 0, username = sig.username)
        assertEquals("abc", withName.brief.username)
        assertEquals("标题甲", withName.brief.title)
        // 无信号/空白 username（直链答案常态）→ null（不误导解析路）
        val blank = DiscoveryGoldCandidate(2L, "标题乙", null, null, 0, 0, username = "")
        assertNull(blank.brief.username)
    }

    @Test
    fun `跨频道spam农场_同指纹份额过线成farm_两频道与短指纹不算`() {
        // 份额制（R183 真机修正）：共享指纹行占非空样本 ≥50% 才标（样本须 ≥8）
        val longAd = "全网最全资源站每日更新上万部大片防失联请记住新地址"
        val byChannel = mapOf(
            // 6/9 共享（≥0.5）→ 标
            1L to listOf(longAd, longAd, longAd, longAd, longAd, longAd) +
                (1..3).map { "频道一正常原创内容第${it}条" },
            // 9/9 共享 → 标
            2L to (1..9).map { longAd },
            // 恰在份额线上 5/10 → 标（≥ 闭区间）
            3L to (1..5).map { longAd } + (1..5).map { "频道三原创第${it}条闲聊内容" },
            // 同话术只有 2 频道共现 → 指纹不算共享 → 不标
            4L to (1..8).map { "只有两个频道共现的话术很长很长很长很长${it}号变体" },
            5L to (1..8).map { "只有两个频道共现的话术很长很长很长很长${it}号变体" },
        )
        val farm = AdPatternFilter.farmChannelsOf(byChannel)
        assertEquals(setOf(1L, 2L, 3L), farm)
    }

    @Test
    fun `份额制修正_转发过一次共享话术的好频道不误杀`() {
        // R183 真机实锤修正：初版把「转发过一次极搜推广（86 频道同文）」的好频道整杀
        // （192/453 被标）——份额制：共享指纹占样本主体才是农场
        val promo = "TG必备的搜索引擎极搜帮你精准找到想要的群组频道音乐视频"
        val prefixes = mapOf(1L to "甲", 2L to "乙", 3L to "丙")
        val ordinals = "子丑寅卯辰巳午未"
        val byChannel = buildMap {
            // 三个频道各转发推广一次 + 各自 8 条真原创（去数字后互不相同；份额 1/9）→ 不标
            for (id in 1L..3L) {
                put(id, (0 until 8).map { "${prefixes[id]}频道原创${ordinals[it]}条目" } + promo)
            }
            // 纯农场：主体内容就是共享话术重发（份额 7/8 ≥ 0.5）→ 标
            put(9L, (1..7).map { promo } + "偶有一条闲聊内容而已")
        }
        val farm = AdPatternFilter.farmChannelsOf(byChannel)
        assertEquals(setOf(9L), farm)
    }

    @Test
    fun `过滤闸classify_四信号分支与放行`() {
        // 广告密度过线 → 拒
        assertFalse(
            AdPatternFilter.classify(true, 0.8, 0.6, 0.0, false, 0).pass,
        )
        // 模板风暴过线 → 拒
        val storm = AdPatternFilter.classify(true, 0.8, 0.0, 0.7, false, 0)
        assertFalse(storm.pass)
        assertTrue(storm.reason.contains("刷屏"))
        // 农场指纹 → 拒
        assertFalse(AdPatternFilter.classify(true, 0.8, 0.0, 0.0, true, 0).pass)
        // 双零拦截：群零安利 + 未探针 → 拒；群零安利 + 丰度低 → 拒
        assertFalse(AdPatternFilter.classify(false, 0.0, 0.0, 0.0, false, 0).pass)
        assertFalse(AdPatternFilter.classify(true, 0.2, 0.0, 0.0, false, 0).pass)
        // 群内有安利：索引零丰度也放行（宝藏二背书——「双零」只在两路都零时拦）
        assertTrue(AdPatternFilter.classify(false, 0.0, 0.0, 0.0, false, 3).pass)
        // 合格频道：丰度过线零群安利也放行（宝藏一单路背书）
        assertTrue(AdPatternFilter.classify(true, 0.6, 0.0, 0.0, false, 0).pass)
        // 临界语义：丰度「低于」降档线才拦（0.24 < 0.25 拒；恰=0.25 不在拦集）
        assertFalse(AdPatternFilter.classify(true, 0.24, 0.0, 0.0, false, 0).pass)
        assertTrue(AdPatternFilter.classify(true, 0.25, 0.0, 0.0, false, 0).pass)
    }

    @Test
    fun `verdictOf_档案重载直通classify`() {
        val g = gold(1, richness = 0.7)
        assertTrue(DiscoveryGoldmine.verdictOf(g, groupEndorse = 0).pass)
        val bad = gold(2, richness = 0.1, probed = true)
        assertFalse(DiscoveryGoldmine.verdictOf(bad, groupEndorse = 0).pass)
    }

    // ---------------- 快照落盘/读盘（原子写读侧契约） ----------------

    @Test
    fun `快照往返_字段逐项复原`() {
        val snap = DiscoveryGoldmine.Snapshot(
            builtAtMs = 1_234_567L,
            channels = listOf(
                gold(1, items = 4_0314, richness = 0.92, recent7 = 21, prev7 = 9, tier = 1),
                gold(2, richness = 0.1, probed = false, adRate = 0.5, stormRate = 0.7, farm = true),
            ),
        )
        val file = tempFolder.newFile("discovery_gold_snapshot.json")
        DiscoveryGoldmine.saveSnapshot(file, snap)
        val loaded = DiscoveryGoldmine.loadSnapshot(file)
        assertNotNull(loaded)
        assertEquals(snap.builtAtMs, loaded!!.builtAtMs)
        assertEquals(snap.channels, loaded.channels)
        assertEquals(40_314, loaded.byId()[1L]!!.items)
        assertEquals(21, loaded.byId()[1L]!!.recent7)
        assertEquals(false, loaded.byId()[2L]!!.probed)
        assertEquals(true, loaded.byId()[2L]!!.farm)
    }

    @Test
    fun `快照损坏或版本不符返回null_不崩`() {
        val file = tempFolder.newFile("bad.json")
        file.writeText("not a json {")
        assertNull(DiscoveryGoldmine.loadSnapshot(file))
        // 版本不符：按空重建（不崩不误读）
        file.writeText("""{"v":999,"builtAtMs":1,"channels":[]}""")
        assertNull(DiscoveryGoldmine.loadSnapshot(file))
        // 文件不存在
        assertNull(DiscoveryGoldmine.loadSnapshot(File(tempFolder.root, "absent.json")))
    }

    @Test
    fun `快照TTL_过期判定`() {
        val now = System.currentTimeMillis()
        assertFalse(DiscoveryGoldmine.Snapshot(now, emptyList()).isStale(now))
        assertTrue(DiscoveryGoldmine.Snapshot(now - DiscoveryGoldmine.SNAPSHOT_TTL_MS - 1, emptyList()).isStale(now))
        assertTrue(DiscoveryGoldmine.Snapshot(0, emptyList()).isStale(now))
    }
}
