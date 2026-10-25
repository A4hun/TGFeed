package dev.tgfeed.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R324 离线降级文案/决策锚（OfflineDegradePolicy——纯件，零真实频道）：
 * 七面降级矩阵的「呈现」半体行为锚——在线恒 null（零视觉噪声）/离线按时点
 * 诚实标注（零编造：无时点不带标签）/分钟-小时-昨天-天分档边界/五面文案字面。
 */
class OfflineDegradePolicyTest {

    private val now = 1_700_000_000_000L // 固定基准（确定性）

    // ==================== 时点标签（stalenessLabel） ====================

    @Test
    fun `时点-无时点与未来时点均不带标签（零编造）`() {
        assertNull(OfflineDegradePolicy.stalenessLabel(0L, now))
        assertNull(OfflineDegradePolicy.stalenessLabel(-1L, now))
        assertNull(OfflineDegradePolicy.stalenessLabel(now + 60_000L, now)) // 时钟漂移=未来
    }

    @Test
    fun `时点-分档边界逐位（刚刚-分钟-小时-昨天-天）`() {
        assertEquals("刚刚", OfflineDegradePolicy.stalenessLabel(now - 30_000L, now))
        assertEquals("1 分钟前", OfflineDegradePolicy.stalenessLabel(now - 60_000L, now))
        assertEquals("59 分钟前", OfflineDegradePolicy.stalenessLabel(now - 59 * 60_000L, now))
        assertEquals("1 小时前", OfflineDegradePolicy.stalenessLabel(now - 60 * 60_000L, now))
        assertEquals("23 小时前", OfflineDegradePolicy.stalenessLabel(now - 23 * 60 * 60_000L, now))
        assertEquals("昨天", OfflineDegradePolicy.stalenessLabel(now - 24 * 60 * 60_000L, now))
        assertEquals("昨天", OfflineDegradePolicy.stalenessLabel(now - 47 * 60 * 60_000L, now))
        assertEquals("2 天前", OfflineDegradePolicy.stalenessLabel(now - 48 * 60 * 60_000L, now))
        assertEquals("30 天前", OfflineDegradePolicy.stalenessLabel(now - 30L * 24 * 60 * 60_000L, now))
    }

    // ==================== feed 面（banner+空态） ====================

    @Test
    fun `feed-在线恒null（零视觉噪声）`() {
        assertNull(
            OfflineDegradePolicy.feedOfflineBannerLine(
                online = true, itemsEmpty = false, dataAtMs = now - 3 * 60 * 60_000L, nowMs = now,
            ),
        )
        assertNull(OfflineDegradePolicy.feedOfflineEmptyHint(online = true))
    }

    @Test
    fun `feed-离线有内容带时点（N小时前的快照）`() {
        assertEquals(
            "离线中 · 展示的是 3 小时前的快照",
            OfflineDegradePolicy.feedOfflineBannerLine(
                online = false, itemsEmpty = false, dataAtMs = now - 3 * 60 * 60_000L, nowMs = now,
            ),
        )
    }

    @Test
    fun `feed-离线有内容无时点（诚实不带时点不编造）`() {
        assertEquals(
            "离线中 · 列表为离线前拉到的内容",
            OfflineDegradePolicy.feedOfflineBannerLine(
                online = false, itemsEmpty = false, dataAtMs = 0L, nowMs = now,
            ),
        )
    }

    @Test
    fun `feed-离线列表空banner不出（空态副文案接管）`() {
        assertNull(
            OfflineDegradePolicy.feedOfflineBannerLine(
                online = false, itemsEmpty = true, dataAtMs = now - 60_000L, nowMs = now,
            ),
        )
        assertEquals(
            "离线中，暂无缓存内容——联网后自动恢复",
            OfflineDegradePolicy.feedOfflineEmptyHint(online = false),
        )
    }

    // ==================== 频道页 / 深搜 / 播放器 / 多设备同步 面 ====================

    @Test
    fun `频道页-离线banner在线null`() {
        assertEquals("离线中 · 展示的是本地缓存内容", OfflineDegradePolicy.channelHomeOfflineLine(online = false))
        assertNull(OfflineDegradePolicy.channelHomeOfflineLine(online = true))
    }

    @Test
    fun `深搜-等待后缀与图例注（在线空串-null）`() {
        assertEquals("（离线中）", OfflineDegradePolicy.searchOfflineSuffix(online = false))
        assertEquals("", OfflineDegradePolicy.searchOfflineSuffix(online = true))
        assertEquals(
            "离线中 · 频道搜索暂不可用，仅显示本地结果",
            OfflineDegradePolicy.searchLegendOfflineNote(online = false),
        )
        assertNull(OfflineDegradePolicy.searchLegendOfflineNote(online = true))
    }

    @Test
    fun `播放器-离线终态文案在线null（可点重试语义由浮层offline参承担）`() {
        val text = OfflineDegradePolicy.playerOfflineFatalText(offline = true)
        assertNotNull(text)
        assertEquals("离线中，此内容未缓存——联网后点按重试，或先换一条看", text)
        assertNull(OfflineDegradePolicy.playerOfflineFatalText(offline = false))
    }

    @Test
    fun `多设备同步-离线行在线null`() {
        assertEquals("离线中 · 同步已暂停，联网后自动恢复", OfflineDegradePolicy.syncOfflineLine(online = false))
        assertNull(OfflineDegradePolicy.syncOfflineLine(online = true))
    }

    // ==================== 矩阵形状锚（七面全档位枚举） ====================

    @Test
    fun `矩阵形状-七面档位全枚举（可用-降级-不可用）`() {
        // 在线列：全 null/空串（离线降级零入侵）
        assertNull(OfflineDegradePolicy.feedOfflineBannerLine(true, false, now, now))
        assertNull(OfflineDegradePolicy.feedOfflineEmptyHint(true))
        assertNull(OfflineDegradePolicy.channelHomeOfflineLine(true))
        assertEquals("", OfflineDegradePolicy.searchOfflineSuffix(true))
        assertNull(OfflineDegradePolicy.searchLegendOfflineNote(true))
        assertNull(OfflineDegradePolicy.playerOfflineFatalText(false))
        assertNull(OfflineDegradePolicy.syncOfflineLine(true))
        // 离线列：feed/频道页降级（有提示行）、深搜可用（本地+注）、播放器/同步
        // 不可用（诚实终态/暂停行）——七面全有明确离线行为，无一面静默编造
        assertNotNull(OfflineDegradePolicy.feedOfflineBannerLine(false, false, now, now))
        assertNotNull(OfflineDegradePolicy.channelHomeOfflineLine(false))
        assertNotNull(OfflineDegradePolicy.searchLegendOfflineNote(false))
        assertNotNull(OfflineDegradePolicy.playerOfflineFatalText(true))
        assertNotNull(OfflineDegradePolicy.syncOfflineLine(false))
    }
}
