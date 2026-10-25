package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * [TimeWindowPolicy]（R202 时间窗横截面纯函数）行为单测：
 * 横截面精炼管线（广告剔除/零日期防御/同片指纹去重保最早=首发/每频道封顶/
 * 轮转交错/limit 截断/倒序终序）、统计行、锚点格式化（固定时区保确定性）、
 * 年刻度、滑杆分数映射钳制、密度归一、默认锚点（最密日正午+回落+量程钳制）。
 */
class TimeWindowPolicyTest {

    private companion object {
        const val DAY = TimeWindowPolicy.DAY_SEC
        val TZ = TimeZone.getTimeZone("Asia/Shanghai") // 测试固定时区（生产=本机时区）

        /** 紧凑条目构造（dateEpochSec/fileUniqueId/chatId/caption 是本测关心的四元） */
        fun item(
            key: String,
            chatId: Long,
            date: Int,
            caption: String = "",
            fp: String = "",
        ) = VideoItem(
            key = key, chatId = chatId, messageId = 1L,
            channelTitle = "频道$chatId", caption = caption,
            durationSec = 60, width = 1920, height = 1080,
            fileId = 1, sizeBytes = 1L, playUri = null,
            dateEpochSec = date, fileUniqueId = fp,
        )
    }

    // ---------------- 窗宽 ----------------

    @Test
    fun `窗宽窗口数学`() {
        assertEquals(900L to 1100L, TimeWindowPolicy.windowOf(1000L, 100L))
        // 锚点=半宽（起点恰为 0）与锚点=6h、半宽=12h 的普通对称窗
        assertEquals(0L to 43_200L, TimeWindowPolicy.windowOf(6 * 3600L, 6 * 3600L))
        assertEquals(100L to 300L, TimeWindowPolicy.windowOf(200L, 100L))
    }

    @Test
    fun `三模式预设档不含零与负半宽`() {
        for (presets in listOf(
            TimeWindowPolicy.FREE_PRESETS, TimeWindowPolicy.NOW_PRESETS, TimeWindowPolicy.EVENT_PRESETS,
        )) {
            assertTrue(presets.isNotEmpty())
            presets.forEach { assertTrue("半宽必须为正", it.halfWidthSec > 0) }
        }
        // 事件考古默认 ±2 天（TL-4 口径）、此刻默认 ±30 分（DS-1 分钟级口径）、
        // 时光机默认 ±6 时（R200 TL-1 口径）
        assertEquals(2 * DAY, TimeWindowPolicy.EVENT_PRESETS[2].halfWidthSec)
        assertEquals(30 * 60L, TimeWindowPolicy.NOW_PRESETS[1].halfWidthSec)
        assertEquals(6 * 3600L, TimeWindowPolicy.FREE_PRESETS[1].halfWidthSec)
    }

    // ---------------- 横截面精炼 ----------------

    @Test
    fun `广告话术与零日期行剔除`() {
        val raw = listOf(
            item("k1", -100L, 300),
            item("k2", -100L, 200, caption = "商务合作加V看片"), // 命中广告话术族
            item("k3", -100L, 100, caption = "第四集上线"),       // 正常连载 caption 保留
            item("k4", -100L, 0),                                 // 零日期防御
        )
        val out = TimeWindowPolicy.refineCrossSection(raw)
        assertEquals(listOf("k1", "k3"), out.map { it.key }) // 倒序：k1(300) > k3(100)
    }

    @Test
    fun `同片指纹去重保最早=首发叙事`() {
        // 输入为 date 倒序：晚发(200)在前、早发(100)在后——精炼后只留最早（首发）
        val raw = listOf(
            item("late", -200L, 200, fp = "FU1"),
            item("early", -100L, 100, fp = "FU1"),
        )
        val out = TimeWindowPolicy.refineCrossSection(raw)
        assertEquals(listOf("early"), out.map { it.key })
        assertEquals(-100L, out[0].chatId)
    }

    @Test
    fun `等日期指纹保先见者`() {
        val raw = listOf(
            item("first", -100L, 100, fp = "FU1"),
            item("second", -200L, 100, fp = "FU1"),
        )
        val out = TimeWindowPolicy.refineCrossSection(raw)
        assertEquals(listOf("first"), out.map { it.key })
    }

    @Test
    fun `空指纹老条目不去重`() {
        val raw = listOf(
            item("a", -100L, 200, fp = ""),
            item("b", -200L, 100, fp = ""),
        )
        val out = TimeWindowPolicy.refineCrossSection(raw)
        assertEquals(2, out.size)
    }

    @Test
    fun `每频道封顶防怪兽垄断`() {
        val raw = (1..10).map { item("m$it", -999L, 100 + it) } // 一频道 10 条
        val out = TimeWindowPolicy.refineCrossSection(raw, perChannelCap = 3)
        assertEquals(3, out.size)
        assertEquals(listOf("m10", "m9", "m8"), out.map { it.key }) // 倒序取最新 3 条
    }

    @Test
    fun `频道轮转交错+封顶+倒序终序`() {
        // 频道A 5 条（新→旧 104..100）、频道B 2 条（200,150）；cap=3
        val raw = (100..104).reversed().map { item("a$it", -1L, it) } +
            listOf(item("b200", -2L, 200), item("b150", -2L, 150))
        val out = TimeWindowPolicy.refineCrossSection(raw, perChannelCap = 3)
        // A 封顶 3 条（104/103/102）、B 全取（200/150）；终序=date 倒序
        assertEquals(listOf("b200", "b150", "a104", "a103", "a102"), out.map { it.key })
    }

    @Test
    fun `limit 截断后仍倒序`() {
        val raw = (1..30).map { item("k$it", -1L, it) }
        val out = TimeWindowPolicy.refineCrossSection(raw, perChannelCap = 10, limit = 5)
        assertEquals(5, out.size)
        assertEquals(listOf("k30", "k29", "k28", "k27", "k26"), out.map { it.key })
    }

    @Test
    fun `空入参与非法参数空手返回`() {
        assertTrue(TimeWindowPolicy.refineCrossSection(emptyList()).isEmpty())
        assertTrue(TimeWindowPolicy.refineCrossSection(listOf(item("k", -1L, 1)), perChannelCap = 0).isEmpty())
        assertTrue(TimeWindowPolicy.refineCrossSection(listOf(item("k", -1L, 1)), limit = 0).isEmpty())
    }

    @Test
    fun `统计行同片折叠与广告剔除计数`() {
        val raw = listOf(
            item("ad", -1L, 300, caption = "招代理加盟上车费"),
            item("a", -1L, 200, fp = "FU1"),
            item("b", -2L, 100, fp = "FU1"),
            item("c", -3L, 90),
        )
        val refined = TimeWindowPolicy.refineCrossSection(raw)
        val stats = TimeWindowPolicy.statsOf(raw, refined)
        assertEquals(2, stats.total)
        assertEquals(2, stats.channels)
        assertEquals(1, stats.repostsFolded)
        assertEquals(1, stats.adsFolded)
    }

    // ---------------- 锚点格式化（固定时区保确定性） ----------------

    @Test
    fun `锚点时刻格式化`() {
        // 2023-08-19 20:05 +08:00（周六）——epoch 手算锚定防时区工具自身出错
        assertEquals("2023年8月19日 周六 20:05", TimeWindowPolicy.formatAnchorDateTime(1692446700L, TZ))
    }

    @Test
    fun `锚点日期格式化`() {
        assertEquals("2023年8月19日", TimeWindowPolicy.formatAnchorDate(1692446700L, TZ))
    }

    // ---------------- 滑杆几何 ----------------

    @Test
    fun `年刻度落在量程内每年一枚`() {
        val min = 1622476800L // 2021-06-01 +08:00 附近
        val max = 1696032000L // 2023-09-30 +08:00 附近
        val ticks = TimeWindowPolicy.yearTicks(min, max, TZ)
        assertEquals(listOf("2022", "2023"), ticks.map { it.label })
        assertTrue(ticks.all { it.major })
        // 首枚=2022-01-01 00:00 +08:00（手算 epoch 锚定，防时区工具自身出错）
        assertEquals(1640966400L, ticks[0].sec)
        assertTrue(ticks[0].sec > min && ticks[0].sec <= max)
    }

    @Test
    fun `分数与秒互转钳制`() {
        val min = 1_000_000L
        val max = 2_000_000L
        assertEquals(min, TimeWindowPolicy.anchorFromFraction(-0.5f, min, max))
        assertEquals(max, TimeWindowPolicy.anchorFromFraction(1.5f, min, max))
        val mid = TimeWindowPolicy.anchorFromFraction(0.5f, min, max)
        assertEquals(1_500_000L, mid)
        val f = TimeWindowPolicy.fractionFromAnchor(mid, min, max)
        assertTrue(f in 0.49f..0.51f)
        assertEquals(0f, TimeWindowPolicy.fractionFromAnchor(min - 1, min, max))
        assertEquals(0f, TimeWindowPolicy.fractionFromAnchor(5L, 5L, 5L)) // 退化量程
    }

    @Test
    fun `密度分桶归一化`() {
        // 量程 100 天 10 桶；day50+day52 落桶 5（10+5=15 条=max）、day10 落桶 1（1 条）
        val hist = listOf(50 to 10, 52 to 5, 10 to 1)
        val dens = TimeWindowPolicy.densities(hist, 0L, 100 * DAY, 10)
        assertEquals(10, dens.size)
        assertEquals(1.0f, dens[5], 1e-6f)
        assertEquals(1f / 15f, dens[1], 1e-6f)
        assertEquals(0f, dens[0], 1e-6f)
        // 空直方图全 0
        assertEquals(List(10) { 0f }, TimeWindowPolicy.densities(emptyList(), 0L, DAY, 10))
    }

    // ---------------- 默认锚点 ----------------

    @Test
    fun `默认锚点取近窗最密日正午`() {
        // now=300d、近窗=180d（[120d,300d]）：day5(99 条)在窗外不参评，98<295/298 在窗内
        val now = 300 * DAY
        val hist = listOf(5 to 99, 295 to 40, 298 to 41)
        val anchor = TimeWindowPolicy.defaultAnchorSec(hist, now, 0L, now)
        assertEquals(298 * DAY + DAY / 2, anchor)
    }

    @Test
    fun `直方图空或近窗无数据回落now减一天`() {
        val now = 300 * DAY
        assertEquals(now - DAY, TimeWindowPolicy.defaultAnchorSec(emptyList(), now, 0L, now))
        // 近窗内无数据（只有窗外老桶）也回落
        assertEquals(now - DAY, TimeWindowPolicy.defaultAnchorSec(listOf(5 to 99), now, 0L, now))
    }

    @Test
    fun `默认锚点钳制到量程`() {
        val now = 300 * DAY
        // 量程上界太近：now-24h 被钳到 max
        assertEquals(now - 10 * DAY, TimeWindowPolicy.defaultAnchorSec(emptyList(), now, 0L, now - 10 * DAY))
    }
}
