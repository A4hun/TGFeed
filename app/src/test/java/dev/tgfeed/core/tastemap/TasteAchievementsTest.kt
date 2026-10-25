package dev.tgfeed.core.tastemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R235 成就体系纯函数单测：五阶判据矩阵/勋章进度/等阶晋升边界/下一阶差距/
 * 六态口径/R331 终态奇观册（勋章外口径）/ShipStyle 等阶映射/标签与徽章色单一出处。
 */
class TasteAchievementsTest {

    private fun stats(
        channels: Int = 0,
        watchSec: Long = 0L,
        favs: Int = 0,
        tags: Int = 0,
        shares: Int = 0,
        kinds: Set<CelestialKind> = emptySet(),
        discoveredStars: Int = 0,
    ) = TasteAchievements.AchievementStats(channels, watchSec, favs, tags, shares, kinds, discoveredStars)

    private val allKinds = TasteAchievements.SIX_KINDS

    @Test
    fun `等阶-青铜默认`() {
        val r = TasteAchievements.evaluate(stats())
        assertEquals(TasteAchievements.Rank.BRONZE, r.rank)
        assertEquals(TasteAchievements.Rank.SILVER, r.nextRank)
        // 零数据=下一阶差距完整给出
        assertNotNull(r.nextGapLine)
        assertTrue(r.nextGapLine!!.contains("发现频道"))
        assertEquals(15, r.medals.size) // 白银2+黄金3+钻石3+传奇3+R248探索里程碑4
    }

    @Test
    fun `R248-探索里程碑进度与不参与晋升`() {
        // 9 颗：全部未达
        val low = TasteAchievements.evaluate(stats(discoveredStars = 9))
        val m10 = low.medals.first { it.key == "explore_m10" }
        assertFalse(m10.met)
        assertEquals(9L, m10.current)
        assertEquals(10L, m10.target)
        // 10 颗：初级达成（闭边界），其余未达
        val mid = TasteAchievements.evaluate(stats(discoveredStars = 10))
        assertTrue(mid.medals.first { it.key == "explore_m10" }.met)
        assertFalse(mid.medals.first { it.key == "explore_m50" }.met)
        // 300 颗点亮**不升阶**（零观看/收藏/分享——仍青铜：里程碑不参与等阶晋升）
        val many = TasteAchievements.evaluate(stats(discoveredStars = 300))
        assertEquals(TasteAchievements.Rank.BRONZE, many.rank)
        assertTrue(many.medals.first { it.key == "explore_m300" }.met)
        // 缺省参数兼容：旧调用（无 discoveredStars）照常
        val legacy = TasteAchievements.evaluate(TasteAchievements.AchievementStats(0, 0L, 0, 0, 0, emptySet()))
        assertEquals(0L, legacy.medals.first { it.key == "explore_m10" }.current)
    }

    @Test
    fun `等阶-白银边界`() {
        // 49 频道+9.9h：还差一档
        val low = TasteAchievements.evaluate(
            stats(channels = 49, watchSec = 9 * 3600L + 3599L),
        )
        assertEquals(TasteAchievements.Rank.BRONZE, low.rank)
        // 50+10h：正好晋升（闭边界=达成）
        val ok = TasteAchievements.evaluate(
            stats(channels = 50, watchSec = 10 * 3600L),
        )
        assertEquals(TasteAchievements.Rank.SILVER, ok.rank)
    }

    @Test
    fun `等阶-黄金需三勋章全达成`() {
        val partial = TasteAchievements.evaluate(
            stats(channels = 100, watchSec = 50 * 3600L, favs = 29),
        )
        assertEquals(TasteAchievements.Rank.SILVER, partial.rank) // 差一条收藏
        val full = TasteAchievements.evaluate(
            stats(channels = 100, watchSec = 50 * 3600L, favs = 30),
        )
        assertEquals(TasteAchievements.Rank.GOLD, full.rank)
    }

    @Test
    fun `等阶-钻石与传奇`() {
        val diamond = TasteAchievements.evaluate(
            stats(channels = 200, tags = 100, shares = 10),
        )
        assertEquals(TasteAchievements.Rank.DIAMOND, diamond.rank)
        // 传奇还差观看+六态
        assertNotNull(diamond.nextGapLine)
        assertTrue(diamond.nextGapLine!!.contains("观看时长"))
        val legend = TasteAchievements.evaluate(
            stats(channels = 300, watchSec = 200 * 3600L, kinds = allKinds),
        )
        assertEquals(TasteAchievements.Rank.LEGEND, legend.rank)
        assertNull(legend.nextRank)
        assertNull(legend.nextGapLine) // 已到顶：无差距行
    }

    @Test
    fun `等阶-六态口径五恒星加脉冲星`() {
        assertEquals(6, TasteAchievements.SIX_KINDS.size)
        // 五恒星+脉冲星=六类（CLUSTER/METEOR 不进六态——KDoc 口径）
        assertTrue(TasteAchievements.SIX_KINDS.contains(CelestialKind.PULSAR))
        assertFalse(TasteAchievements.SIX_KINDS.contains(CelestialKind.METEOR))
        // 差一类不晋升（传奇以下全满足——只缺六态）
        val fiveOfSix = allKinds.drop(1).toSet()
        val r = TasteAchievements.evaluate(
            stats(channels = 300, watchSec = 200 * 3600L, favs = 30, tags = 100, shares = 10, kinds = fiveOfSix),
        )
        assertEquals(TasteAchievements.Rank.DIAMOND, r.rank)
        assertTrue(r.nextGapLine!!.contains("形态"))
    }

    @Test
    fun `R331-终态奇观册四席与勋章外口径`() {
        // 四席=终态宣判天体全收（黑洞+三新 kind 同册——同类同修不漏一）
        assertEquals(4, TasteAchievements.WONDER_KINDS.size)
        assertTrue(TasteAchievements.WONDER_KINDS.containsAll(
            listOf(
                CelestialKind.BLACK_HOLE,
                CelestialKind.NEUTRON_STAR,
                CelestialKind.QUARK_STAR,
                CelestialKind.WHITE_HOLE,
            ),
        ))
        // 稀有度序（rarityRankOf 头部四档同序：夸克>黑洞>中子>白洞）
        assertEquals(CelestialKind.QUARK_STAR, TasteAchievements.WONDER_KINDS[0])
        assertEquals(CelestialKind.BLACK_HOLE, TasteAchievements.WONDER_KINDS[1])
        assertEquals(CelestialKind.NEUTRON_STAR, TasteAchievements.WONDER_KINDS[2])
        assertEquals(CelestialKind.WHITE_HOLE, TasteAchievements.WONDER_KINDS[3])
        // 勋章外口径：与 SIX_KINDS 零交集（不进等阶判据——R315-L③ 黑洞不入
        // 等阶先例延续：见齐天体形态勋章仍只数六席）
        assertTrue(TasteAchievements.WONDER_KINDS.none { it in TasteAchievements.SIX_KINDS })
        // evaluate 零改锚：奇观全见不影响等阶（kinds 传四奇观=六态零见仍青铜）
        val wondersOnly = TasteAchievements.evaluate(
            stats(kinds = TasteAchievements.WONDER_KINDS.toSet()),
        )
        assertEquals(TasteAchievements.Rank.BRONZE, wondersOnly.rank)
        assertEquals(15, wondersOnly.medals.size) // 勋章表零扩（奇观册不新增勋章位）
    }

    @Test
    fun `勋章-进度归一与达成态`() {
        val r = TasteAchievements.evaluate(stats(channels = 25, watchSec = 5 * 3600L, favs = 15))
        val silverChannels = r.medals.first { it.key == "silver_channels" }
        assertEquals(25L, silverChannels.current)
        assertEquals(50L, silverChannels.target)
        assertFalse(silverChannels.met)
        assertEquals(0.5f, silverChannels.progress01, 1e-4f)
        val goldFavs = r.medals.first { it.key == "gold_favs" }
        assertEquals(15L, goldFavs.current)
        assertTrue(goldFavs.unit == "条")
    }

    @Test
    fun `勋章-观看小时换算`() {
        val r = TasteAchievements.evaluate(stats(watchSec = 3599L))
        val watch = r.medals.first { it.key == "silver_watch" }
        assertEquals(0L, watch.current) // 59分59秒=0 小时（整除口径诚实）
    }

    @Test
    fun `飞船-等阶映射酷度单调`() {
        val ranks = TasteAchievements.Rank.values()
        // 等阶序=酷度序（MICRO_TRAIL→RAINBOW_STORM 枚举序即档位序）
        for (i in ranks.indices) {
            assertEquals(TasteAchievements.ShipStyle.values()[i], TasteAchievements.shipStyleOf(ranks[i]))
        }
    }

    @Test
    fun `标签与徽章色-单一出处稳定`() {
        assertEquals("青铜探索者", TasteAchievements.labelOf(TasteAchievements.Rank.BRONZE))
        assertEquals("传奇星海旅人", TasteAchievements.labelOf(TasteAchievements.Rank.LEGEND))
        // 五阶五色互异（徽章视觉档）
        val colors = TasteAchievements.Rank.values().map { TasteAchievements.colorOf(it) }
        assertEquals(5, colors.toSet().size)
        assertEquals("基础飞船 · 白色微尾", TasteAchievements.shipDescOf(TasteAchievements.ShipStyle.MICRO_TRAIL))
        // R348 C1：彩虹退役后传奇档描述=星尘风暴（文案单出出口径——视觉改了文案不许说谎）
        assertEquals("传说级飞船 · 星尘风暴", TasteAchievements.shipDescOf(TasteAchievements.ShipStyle.RAINBOW_STORM))
    }
}
