package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R204 DS-3 断更/僵尸标记纯函数单测（[ChannelDormancy]）：
 * 档位判定（30 天/90 天硬档 + 低频缓冲防误伤）+ 文案 + 发现卡胶囊（⚠ 前缀家族）。
 */
class ChannelDormancyTest {

    private companion object {
        const val NOW = 1_800_000_000L // 固定"现在"（epoch 秒）
        const val DAY = 86_400L
    }

    /** 测试用 ChannelGold 最小构造（lastActivitySec 按 daysAgo 折算，其余走默认） */
    private fun gold(daysAgo: Long) = DiscoveryGoldmine.ChannelGold(
        chatId = 1L,
        title = "频道1",
        items = 1000,
        richness = 0.6,
        probed = true,
        videoShare = 0.8,
        recent7 = 0,
        prev7 = 0,
        lastActivitySec = NOW - daysAgo * DAY,
        tier = -1,
        deadRate = 0.0,
        adRate = 0.0,
        stormRate = 0.0,
        farm = false,
    )

    @Test
    fun `活跃频道无档`() {
        assertNull(ChannelDormancy.of(NOW - 5 * DAY, NOW))
        assertNull(ChannelDormancy.of(NOW - 29 * DAY, NOW))
    }

    @Test
    fun `未知活性不臆测`() {
        assertNull(ChannelDormancy.of(0L, NOW))
        assertNull(ChannelDormancy.of(-1L, NOW))
    }

    @Test
    fun `30天硬档`() {
        val d = ChannelDormancy.of(NOW - 47 * DAY, NOW)
        assertNotNull(d)
        assertEquals(ChannelDormancy.Tier.STALE, d!!.tier)
        assertEquals(47, d.days)
        assertEquals("47 天未更", ChannelDormancy.label(d))
    }

    @Test
    fun `90天疑似停更`() {
        val d = ChannelDormancy.of(NOW - 132 * DAY, NOW)
        assertEquals(ChannelDormancy.Tier.SUSPECTED_DEAD, d!!.tier)
        assertEquals("疑似停更 132 天", ChannelDormancy.label(d))
    }

    @Test
    fun `低频缓冲挡住月更频道`() {
        // 历史节奏 0.2 条/周（期望间隔 35 天）：35 天/80 天没更都在 3 倍节奏内（<105 天）→ 不出档
        assertNull(ChannelDormancy.of(NOW - 35 * DAY, NOW, postsPerWeek = 0.2))
        assertNull(ChannelDormancy.of(NOW - 80 * DAY, NOW, postsPerWeek = 0.2))
        // 拖过 3 倍节奏（120 天 > 105 天）→ 缓冲失效出档（≥90 天 → 疑似停更档）
        val d = ChannelDormancy.of(NOW - 120 * DAY, NOW, postsPerWeek = 0.2)
        assertEquals(ChannelDormancy.Tier.SUSPECTED_DEAD, d!!.tier)
    }

    @Test
    fun `低频缓冲也护季更频道的90天空白`() {
        // 季更频道（0.08 条/周，期望间隔 87.5 天）：150 天 < 3×87.5 → 缓冲期内不出档
        assertNull(ChannelDormancy.of(NOW - 150 * DAY, NOW, postsPerWeek = 0.08))
        // 日更频道（7 条/周）100 天空白 = 63 倍节奏 → 疑似停更照出
        val d = ChannelDormancy.of(NOW - 100 * DAY, NOW, postsPerWeek = 7.0)
        assertEquals(ChannelDormancy.Tier.SUSPECTED_DEAD, d!!.tier)
    }

    @Test
    fun `频率未知只走硬档`() {
        // 35 天 + 频率未知（发现卡 gold 无频率供给）→ 出 30 天档（事实陈述档）
        assertNotNull(ChannelDormancy.of(NOW - 35 * DAY, NOW, postsPerWeek = null))
    }

    @Test
    fun `发现卡胶囊带警示前缀`() {
        assertEquals("⚠ 47 天未更", ChannelDormancy.chipOf(gold(daysAgo = 47), NOW))
        assertNull(ChannelDormancy.chipOf(gold(daysAgo = 3), NOW))
    }

    @Test
    fun `主页徽章行文案`() {
        assertEquals("63 天未更", ChannelDormancy.homeLineOf(NOW - 63 * DAY, NOW, null))
        assertNull(ChannelDormancy.homeLineOf(NOW - 10 * DAY, NOW, null))
    }
}
