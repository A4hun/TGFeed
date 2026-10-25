package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FollowingFairSupply]（R176 关注流公平供给纯函数）的行为单测：
 *  - 轮转交错：无饿死（最少供给者优先）/高频不霸屏（窗口+占比硬约束）/
 *    加权有界/单频道约束放松/稳定序；
 *  - 判重闸：FRESH 双轨硬挡 / RECYCLE 间隔闸 / 开关关闭只剩推送位判重；
 *  - 回收序：未看优先垫后已看、越久远越靠前；
 *  - 分层状态机：空轮降层/全到底降层/推荐层环回节拍。
 */
class FollowingFairSupplyTest {

    private fun item(
        chatId: Long,
        msgId: Long,
        epochSec: Int = 1_700_000_000 + msgId.toInt(),
        key: String = "c${chatId}m$msgId",
        uid: String = "",
    ) = VideoItem(
        key = key,
        chatId = chatId,
        messageId = msgId,
        channelTitle = "频道$chatId",
        caption = "内容$msgId",
        durationSec = 30,
        width = 720,
        height = 1280,
        fileId = 7,
        sizeBytes = 1024L,
        playUri = "http://x/$chatId/$msgId",
        dateEpochSec = epochSec,
        fileUniqueId = uid,
    )

    private fun ledger(
        seenKeys: Set<String> = emptySet(),
        seenUids: Set<String> = emptySet(),
        watchKeyTs: Map<String, Long> = emptyMap(),
        watchUidTs: Map<String, Long> = emptyMap(),
        servedKeys: Set<String> = emptySet(),
    ) = FollowingSeenLedger(seenKeys, seenUids, watchKeyTs, watchUidTs, servedKeys)

    // ---------------- interleaveFollowingRound ----------------

    @Test
    fun `公平轮转_首轮每频道各出一条`() {
        // 5 频道各 5 条：交错输出的前 5 条 = 5 个不同频道（round-robin 骨架）
        val batches = (1L..5L).associateWith { ch -> (1L..5L).map { item(ch, it) } }
        val out = interleaveFollowingRound(batches, emptyMap(), emptyMap())
        assertEquals(25, out.size)
        assertEquals(5, out.take(5).map { it.chatId }.distinct().size)
    }

    @Test
    fun `公平轮转_无饿死_最少已供给者优先`() {
        // 频道 9 会话已供给 10 条、频道 1 供给 0 条：本轮队首 = 频道 1
        val batches = mapOf(
            1L to listOf(item(1L, 1L)),
            5L to listOf(item(5L, 1L)),
            9L to listOf(item(9L, 1L)),
        )
        val served = mapOf(1L to 0, 5L to 5, 9L to 10)
        val out = interleaveFollowingRound(batches, served, emptyMap())
        assertEquals(1L, out.first().chatId)
        assertEquals(listOf(1L, 5L, 9L), out.map { it.chatId })
    }

    @Test
    fun `高频频道不霸屏_窗口与占比双约束`() {
        // A 40 条 + B/C/D 各 10 条：占比上限 ceil(70×0.25)=18；
        // 滑动窗口任意连续 5 条同频道 ≤2
        val batches = mapOf(
            1L to (1L..40L).map { item(1L, it) },
            2L to (1L..10L).map { item(2L, it) },
            3L to (1L..10L).map { item(3L, it) },
            4L to (1L..10L).map { item(4L, it) },
        )
        val out = interleaveFollowingRound(batches, emptyMap(), emptyMap())
        assertEquals(70, out.size)
        // 放松段只在别的频道全部出完之后才启动：占比约束的守区 =
        // 「还有别的频道没出完」的整个前缀
        var relaxStart = out.size
        for (i in out.indices) {
            val tail = out.drop(i)
            if (tail.map { it.chatId }.distinct().size <= 1) {
                relaxStart = i
                break
            }
        }
        val guarded = out.take(relaxStart)
        assertTrue("占比约束守区不能为空", guarded.isNotEmpty())
        // 占比上限按全批总量口径（interleave 的 shareCap 就是这么算的）
        val cap = maxOf(SHARE_CAP_MIN, kotlin.math.ceil(70 * SHARE_CAP_RATIO).toInt())
        val aInGuarded = guarded.count { it.chatId == 1L }
        assertTrue("高频频道在守区内 $aInGuarded 条 > 上限 $cap", aInGuarded <= cap)
        // 滑动窗口（守区内）：任意连续 5 条同频道 ≤2
        for (i in 0..guarded.size - WINDOW) {
            val win = guarded.subList(i, i + WINDOW)
            win.groupBy { it.chatId }.values.forEach {
                assertTrue("窗口约束被违反：${win.map { it.chatId }}", it.size <= WINDOW_MAX)
            }
        }
    }

    @Test
    fun `加权有界_高权重频道每pass至多第二条`() {
        // A 权重 2.0、B/C 权重 0.3：A 最多连续 2 条（pass 内主位+补位），随后必让位
        val batches = mapOf(
            1L to (1L..20L).map { item(1L, it) },
            2L to (1L..20L).map { item(2L, it) },
            3L to (1L..20L).map { item(3L, it) },
        )
        val out = interleaveFollowingRound(batches, emptyMap(), mapOf(1L to 2.0, 2L to 0.3, 3L to 0.3))
        assertEquals(60, out.size)
        // 任意连续 3 条不得全为同频道（加权倾斜的硬上界）
        for (i in 0..out.size - 3) {
            val win = out.subList(i, i + 3)
            assertTrue("加权倾斜越界：连续 3 条同频道", win.map { it.chatId }.distinct().size > 1)
        }
        // A 出货总量有界：不应超过均值的 2 倍（60/3=20 均值 → A ≤ 27）
        val aCount = out.count { it.chatId == 1L }
        assertTrue("高权重频道出货 $aCount 条越界", aCount <= 27)
    }

    @Test
    fun `单频道_约束放松仍全量供给`() {
        // 只订一个频道：占比/窗口约束结构性无法满足——放松后照常出全部货
        val batches = mapOf(1L to (1L..10L).map { item(1L, it) })
        val out = interleaveFollowingRound(batches, emptyMap(), emptyMap())
        assertEquals(10, out.size)
        assertEquals((1L..10L).map { "c1m$it" }, out.map { it.key })
    }

    @Test
    fun `双频道_交错不整段连排`() {
        val batches = mapOf(
            1L to (1L..10L).map { item(1L, it) },
            2L to (1L..10L).map { item(2L, it) },
        )
        val out = interleaveFollowingRound(batches, emptyMap(), emptyMap())
        // 两个频道轮流出（权重同档无补位）：开头 4 条两频道各 2 条
        val head = out.take(4)
        assertEquals(2, head.count { it.chatId == 1L })
        assertEquals(2, head.count { it.chatId == 2L })
    }

    @Test
    fun `空批返回空表`() {
        assertTrue(interleaveFollowingRound(emptyMap(), emptyMap(), emptyMap()).isEmpty())
        assertTrue(
            interleaveFollowingRound(mapOf(1L to emptyList()), emptyMap(), emptyMap()).isEmpty()
        )
    }

    @Test
    fun `并列时_权重高者先_再按头条新鲜度`() {
        // 供给计数全 0：权重 1.5 的频道先于 0.5；权重并列时新帖频道先
        val batches = mapOf(
            1L to listOf(item(1L, 1L, epochSec = 100)),
            2L to listOf(item(2L, 1L, epochSec = 200)),
            3L to listOf(item(3L, 1L, epochSec = 300)),
        )
        val out = interleaveFollowingRound(batches, emptyMap(), mapOf(1L to 1.5, 2L to 0.5, 3L to 1.5))
        // 权重：1(1.5) 与 3(1.5) 并列 → 头条新鲜度 300 > 100 → 3 在 1 前；2(0.5) 最后
        assertEquals(listOf(3L, 1L, 2L), out.map { it.chatId })
    }

    // ---------------- followingGatePasses ----------------

    @Test
    fun `FRESH层_已看双轨硬挡_未看放行`() {
        val now = 1_000_000_000_000L
        val seen = ledger(
            seenKeys = setOf("k1"),
            seenUids = setOf("uid9"),
        )
        val i1 = item(1L, 1L, key = "k1", uid = "u1") // key 命中
        val i2 = item(2L, 1L, key = "k2", uid = "uid9") // 指纹命中（换台同片）
        val i3 = item(3L, 1L, key = "k3", uid = "u3") // 未看
        assertFalse(followingGatePasses(i1, FollowingSupplyLayer.FRESH, seen, now, true))
        assertFalse(followingGatePasses(i2, FollowingSupplyLayer.FRESH, seen, now, true))
        assertTrue(followingGatePasses(i3, FollowingSupplyLayer.FRESH, seen, now, true))
    }

    @Test
    fun `RECYCLE层_间隔闸_久远放行近期挡`() {
        val now = 1_000_000_000_000L
        val old = now - FOLLOWING_REWATCH_GAP_MS - 1
        val recent = now - 1_000L
        val seen = ledger(
            seenKeys = setOf("old", "recent", "unknown"),
            watchKeyTs = mapOf("old" to old, "recent" to recent),
        )
        val iOld = item(1L, 1L, key = "old")
        val iRecent = item(2L, 1L, key = "recent")
        val iUnknown = item(3L, 1L, key = "unknown") // markSeen-only（无观看时刻）
        val iUnseen = item(4L, 1L, key = "fresh")
        assertTrue(followingGatePasses(iOld, FollowingSupplyLayer.RECYCLE, seen, now, true))
        assertFalse(followingGatePasses(iRecent, FollowingSupplyLayer.RECYCLE, seen, now, true))
        assertTrue(followingGatePasses(iUnknown, FollowingSupplyLayer.RECYCLE, seen, now, true))
        assertTrue(followingGatePasses(iUnseen, FollowingSupplyLayer.RECYCLE, seen, now, true))
    }

    @Test
    fun `RECYCLE层_指纹轨的观看时刻同口径`() {
        val now = 1_000_000_000_000L
        val old = now - FOLLOWING_REWATCH_GAP_MS - 1
        val recent = now - 1_000L
        val seen = ledger(
            seenKeys = emptySet(),
            seenUids = setOf("uidOld", "uidRecent"),
            watchUidTs = mapOf("uidOld" to old, "uidRecent" to recent),
        )
        val iOld = item(1L, 1L, key = "a", uid = "uidOld")
        val iRecent = item(2L, 1L, key = "b", uid = "uidRecent")
        assertTrue(followingGatePasses(iOld, FollowingSupplyLayer.RECYCLE, seen, now, true))
        assertFalse(followingGatePasses(iRecent, FollowingSupplyLayer.RECYCLE, seen, now, true))
        // RECOMMEND 层与 FRESH 同口径：指纹已见（无论多久）都挡
        assertFalse(followingGatePasses(iOld, FollowingSupplyLayer.RECOMMEND, seen, now, true))
    }

    @Test
    fun `推送位判重_任何层任何开关都挡`() {
        val now = 1_000_000_000_000L
        val served = ledger(servedKeys = setOf("pushed"))
        val i = item(1L, 1L, key = "pushed")
        for (layer in FollowingSupplyLayer.entries) {
            assertFalse(followingGatePasses(i, layer, served, now, true))
            assertFalse(followingGatePasses(i, layer, served, now, false))
        }
    }

    @Test
    fun `开关关闭_仅剩推送位判重`() {
        val now = 1_000_000_000_000L
        val seen = ledger(seenKeys = setOf("seen"), watchKeyTs = mapOf("seen" to now))
        val iSeen = item(1L, 1L, key = "seen")
        assertTrue(followingGatePasses(iSeen, FollowingSupplyLayer.FRESH, seen, now, false))
        assertTrue(followingGatePasses(iSeen, FollowingSupplyLayer.RECYCLE, seen, now, false))
    }

    // ---------------- recycleOrder ----------------

    @Test
    fun `回收序_未看优先_已看垫后_越久远越靠前`() {
        val now = 1_000_000_000_000L
        val ledgerNow = ledger(
            seenKeys = setOf("old", "mid", "new"),
            watchKeyTs = mapOf(
                "old" to now - 100_000L,
                "mid" to now - 50_000L,
                "new" to now - 1_000L,
            ),
        )
        val items = listOf(
            item(1L, 1L, key = "new"),
            item(1L, 2L, key = "fresh1"),
            item(1L, 3L, key = "mid"),
            item(1L, 4L, key = "old"),
            item(1L, 5L, key = "fresh2"),
        )
        val out = recycleOrder(items, ledgerNow)
        // 未看（fresh1/fresh2）在前（原序），已看按观看时刻升序垫后
        assertEquals(listOf("fresh1", "fresh2", "old", "mid", "new"), out.map { it.key })
    }

    // ---------------- 分层状态机 ----------------

    @Test
    fun `FRESH空轮达阈值降层_未达阈值保持`() {
        assertEquals(
            FollowingSupplyLayer.RECYCLE,
            nextFollowingLayer(FollowingSupplyLayer.FRESH, FOLLOWING_FRESH_DRY_ROUNDS, false),
        )
        assertEquals(
            FollowingSupplyLayer.FRESH,
            nextFollowingLayer(FollowingSupplyLayer.FRESH, FOLLOWING_FRESH_DRY_ROUNDS - 1, false),
        )
    }

    @Test
    fun `FRESH全频道到底_即刻降层`() {
        assertEquals(
            FollowingSupplyLayer.RECYCLE,
            nextFollowingLayer(FollowingSupplyLayer.FRESH, 0, true),
        )
    }

    @Test
    fun `RECYCLE空轮达阈值转推荐`() {
        assertEquals(
            FollowingSupplyLayer.RECOMMEND,
            nextFollowingLayer(FollowingSupplyLayer.RECYCLE, FOLLOWING_FRESH_DRY_ROUNDS, false),
        )
        assertEquals(
            FollowingSupplyLayer.RECYCLE,
            nextFollowingLayer(FollowingSupplyLayer.RECYCLE, 0, true),
        )
    }

    @Test
    fun `RECOMMEND空轮达阈值环回回收层`() {
        // 聚合路也枯竭的极端账号：环回回收层（归顶重走=新帖探测），
        // FRESH→RECYCLE→RECOMMEND 形成完整环，供给层永不终结
        assertEquals(
            FollowingSupplyLayer.RECYCLE,
            nextFollowingLayer(FollowingSupplyLayer.RECOMMEND, FOLLOWING_FRESH_DRY_ROUNDS, true),
        )
        assertEquals(
            FollowingSupplyLayer.RECOMMEND,
            nextFollowingLayer(FollowingSupplyLayer.RECOMMEND, FOLLOWING_FRESH_DRY_ROUNDS - 1, true),
        )
    }

    @Test
    fun `推荐层环回节拍_轮次与间隔双闸`() {
        // 轮次未满：不探
        assertFalse(shouldProbeRecycle(FOLLOWING_RECOMMEND_PROBE_EVERY - 1, Long.MAX_VALUE))
        // 轮次满但间隔不够：不探
        assertFalse(
            shouldProbeRecycle(
                FOLLOWING_RECOMMEND_PROBE_EVERY,
                FOLLOWING_RECYCLE_PROBE_MIN_INTERVAL_MS - 1,
            )
        )
        // 双闸都过：探
        assertTrue(
            shouldProbeRecycle(
                FOLLOWING_RECOMMEND_PROBE_EVERY,
                FOLLOWING_RECYCLE_PROBE_MIN_INTERVAL_MS,
            )
        )
    }
}
