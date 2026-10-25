package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.core.td.filterSeenIdentities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R337 已看查重统一口 [SeenDedupPolicy] 单测（用户 19:12 实锤「刷过了就不要
 * 重复推」——图片/音频 tab 已刷条目回灌修复的语义锚）：
 *
 *  - [SeenDedupPolicy.isSeen]：双轨判定正反例（key 精确命中 / fileUniqueId
 *    指纹命中=跨频道转发同片 / 无指纹条目只走 key 轨 / 双集皆空零开销直通）；
 *  - [SeenDedupPolicy.notServedAndUnseen]：供给口查重组合式——「已刷 key 不进
 *    供给 / 未刷照常进 / 会话内已供给不重复」（loadMore 正页 kept 过滤的判定体）；
 *  - [SeenDedupPolicy.hardExcludeOn]：严格契约——已看排除唯一输入=用户开关，
 *    不存在任何供给状态/饥饿计数参数能翻转（旧 R94/R216 rewatchRelaxSeen
 *    静默解除路已退役）；
 *  - 三链一致性锚：网关侧 [filterSeenIdentities]（fetchAggregatedMediaAll/
 *    AllFast = 视频/图片/音频 tab 共用直拉路的源头过滤）与策略件 [isSeen]
 *    逐条判定等价——两侧锁死同一把尺，任何一侧漂移即刻红。
 */
class SeenDedupPolicyTest {

    // ---------------- 夹具 ----------------

    private fun video(
        key: String,
        chatId: Long = 1L,
        fileUniqueId: String = "",
        mediaType: Int = 1,
    ) = VideoItem(
        key = key,
        chatId = chatId,
        messageId = key.hashCode().toLong(),
        channelTitle = "频道$chatId",
        caption = "",
        durationSec = 30,
        width = 720,
        height = 1280,
        fileId = 7,
        sizeBytes = 1024L,
        playUri = null,
        fileUniqueId = fileUniqueId,
        mediaType = mediaType,
        dateEpochSec = 1_700_000_000,
    )

    // ---------------- isSeen：双轨判定正反例 ----------------

    @Test
    fun `isSeen key 精确命中`() {
        // 已刷条目（key 在底账）：无论哪条链哪层供给口都必须判已看
        assertTrue(SeenDedupPolicy.isSeen(video("k1"), setOf("k1"), emptySet()))
    }

    @Test
    fun `isSeen 指纹命中跨频道转发同片`() {
        // 同片被搬运到别的频道：key 不同、fileUniqueId 相同——key 轨管不住
        // 「换台又见同片」，指纹轨必须兜住（图/音 tab 重复推送的主形态之一）
        assertTrue(
            SeenDedupPolicy.isSeen(
                video("kA", chatId = 2, fileUniqueId = "uidX"),
                emptySet(),
                setOf("uidX"),
            ),
        )
    }

    @Test
    fun `isSeen 无指纹条目只走 key 轨不误伤`() {
        // fileUniqueId 为空的条目不能因空指纹命中任何指纹集（宁漏勿错杀）
        assertFalse(SeenDedupPolicy.isSeen(video("k1"), emptySet(), setOf("uidX")))
        assertTrue(SeenDedupPolicy.isSeen(video("k1"), setOf("k1"), setOf("uidX")))
    }

    @Test
    fun `isSeen 未刷条目与双集皆空直通`() {
        // 未刷照常：key/指纹都不在底账 → false（供给照常放行）
        assertFalse(
            SeenDedupPolicy.isSeen(
                video("k2", fileUniqueId = "uidY"),
                setOf("k1"),
                setOf("uidX"),
            ),
        )
        // 双集皆空（开关关/底账空）恒 false：零开销直通
        assertFalse(SeenDedupPolicy.isSeen(video("k1"), emptySet(), emptySet()))
    }

    // ---------------- notServedAndUnseen：供给口查重组合式 ----------------

    @Test
    fun `供给口已刷不进未刷照常会话内不重复`() {
        // 模拟 loadMore 正页 kept 过滤的判定体（FeedViewModel loadMore）：
        // known=当前列表（会话已供给），seen=已看底账双轨
        val known = setOf("served1") // 会话内已供给（在列表）
        val seenK = setOf("swipedOld") // 底账已刷（本会话或历史刷过）
        val seenU = setOf("uidRe")
        val page = listOf(
            video("served1"), // 会话已供给 → 不重复供给
            video("swipedOld"), // 已刷 key → 不进供给
            video("fresh", fileUniqueId = "uidRe"), // 已刷指纹（跨频道同片）→ 不进供给
            video("clean", chatId = 9, fileUniqueId = "uidNew", mediaType = 2), // 未刷 → 照常进
        )
        val kept = page.filter { SeenDedupPolicy.notServedAndUnseen(it, known, seenK, seenU) }
        assertEquals(1, kept.size)
        assertEquals("clean", kept.first().key)
    }

    @Test
    fun `供给口单频道场景已看集空退化为纯会话判重`() {
        // 单频道/本地源场景：已看集为空（excludeWatchedActive=false）时
        // notServedAndUnseen 只做「key 不在当前列表」——与旧行为逐字节等价
        val known = setOf("served1")
        val page = listOf(video("served1"), video("next1"))
        val kept = page.filter { SeenDedupPolicy.notServedAndUnseen(it, known, emptySet(), emptySet()) }
        assertEquals(1, kept.size)
        assertEquals("next1", kept.first().key)
    }

    // ---------------- hardExcludeOn：严格契约 ----------------

    @Test
    fun `已看排除唯一输入是用户开关`() {
        // 开关开=剔；开关关=不剔。签名单参即设计：不存在「供给饥饿/兜底态」
        // 输入能翻转本判定——R94/R216 触底自愈的 rewatchRelaxSeen 静默解除路
        // （图/音 tab 已刷回灌的根因）已退役，此锚钉死不复活。
        assertTrue(SeenDedupPolicy.hardExcludeOn(watchSwitchOn = true))
        assertFalse(SeenDedupPolicy.hardExcludeOn(watchSwitchOn = false))
    }

    // ---------------- 三链一致性锚：网关孪生与策略件锁死 ----------------

    @Test
    fun `网关源头过滤与策略件逐条等价`() {
        // 视频/图片/音频 tab 共用 fetchAggregatedMediaAll/AllFast 网关直拉路
        // （源头过滤 filterSeenIdentities）；VM 侧 pass/召回/回溯走策略件。
        // 两侧对同一批条目必须给出逐条一致的判定+一致的命中计数+保序输出——
        // 任何一侧改动漂移即刻红（三链一致性锚）。
        val items = listOf(
            video("v1", chatId = 1, fileUniqueId = "uid1", mediaType = 0),
            video("p1", chatId = 2, fileUniqueId = "uidR", mediaType = 1),
            video("a1", chatId = 3, fileUniqueId = "uid3", mediaType = 2),
            video("p2", chatId = 4, fileUniqueId = "", mediaType = 1),
            video("a2", chatId = 5, fileUniqueId = "uid5", mediaType = 2),
        )
        val seenK = setOf("v1", "p2")
        val seenU = setOf("uidR", "uid9")
        val (out, hits) = filterSeenIdentities(items, seenK, seenU)
        // 逐条等价：网关剔除位 = isSeen 判已看位
        items.forEach { item ->
            assertEquals(
                "网关与策略件判定漂移：${item.key}",
                SeenDedupPolicy.isSeen(item, seenK, seenU),
                !out.contains(item),
            )
        }
        // 命中计数一致 + 幸存者保序
        assertEquals(items.count { SeenDedupPolicy.isSeen(it, seenK, seenU) }, hits)
        assertEquals(items.filter { !SeenDedupPolicy.isSeen(it, seenK, seenU) }, out)
    }

    @Test
    fun `网关双集皆空时零开销直通两侧同义`() {
        // 双集皆空（开关关）= 不过滤：网关整体直通（原引用返回）、策略件恒 false
        val items = listOf(video("k1"), video("k2", fileUniqueId = "u"))
        val (out, hits) = filterSeenIdentities(items, emptySet(), emptySet())
        assertEquals(2, out.size)
        assertEquals(0, hits)
        items.forEach { assertFalse(SeenDedupPolicy.isSeen(it, emptySet(), emptySet())) }
    }
}
