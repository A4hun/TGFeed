package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * R204 TL-3 时间盲盒纯策略单测（[TimeBlindboxPolicy]）：窗序列/随机锚点/抽样/
 * 同片去重保最早。R202 timeWindowItems 查询体 = 设备验证面（依赖索引就绪）。
 */
class TimeBlindboxPolicyTest {

    private fun item(
        key: String,
        chatId: Long,
        dateSec: Int,
        fileUniqueId: String = "",
    ) = VideoItem(
        key = key, chatId = chatId, messageId = dateSec * 100L, channelTitle = "频道$chatId",
        caption = "c$key", durationSec = 60, width = 0, height = 0, fileId = -1,
        sizeBytes = 0, playUri = null, dateEpochSec = dateSec, fileUniqueId = fileUniqueId,
    )

    @Test
    fun `窗序列首掷紧落空放宽`() {
        val halves = TimeBlindboxPolicy.windowHalfSecs()
        assertEquals(TimeBlindboxPolicy.MAX_ROLLS, halves.size)
        assertEquals(TimeBlindboxPolicy.FIRST_WINDOW_HALF_SEC, halves[0])
        // ×8 逐掷放宽：30 分钟 → 4 小时 → 32 小时
        assertEquals(30L * 60 * 8, halves[1])
        assertEquals(30L * 60 * 64, halves[2])
    }

    @Test
    fun `随机锚点在量程内_量程非法返回null`() {
        val rand = Random(42)
        repeat(50) {
            val a = TimeBlindboxPolicy.randomAnchorSec(1_000L, 2_000L, rand)!!
            assertTrue(a in 1_000L..2_000L)
        }
        assertNull(TimeBlindboxPolicy.randomAnchorSec(2_000L, 1_000L, rand))
        assertNull(TimeBlindboxPolicy.randomAnchorSec(1_000L, 1_000L, rand))
    }

    @Test
    fun `随机抽样覆盖全部候选`() {
        val rand = Random(7)
        val pool = listOf("a", "b", "c", "d")
        val picked = HashSet<String>()
        repeat(100) { TimeBlindboxPolicy.pickRandom(pool, rand)?.let { picked += it } }
        assertEquals(pool.toSet(), picked)
        assertNull(TimeBlindboxPolicy.pickRandom(emptyList<String>(), rand))
    }

    @Test
    fun `同片跨频道去重保最早`() {
        val out = TimeBlindboxPolicy.dedupeItems(
            listOf(
                item("k1", 101, 1_700_200, "F1"), // 晚发副本
                item("k2", 202, 1_700_100, "F1"), // 最早副本（首发）
                item("k3", 303, 1_700_150),       // 无指纹原样过
            ),
        )
        assertEquals(2, out.size) // F1 一组 + 无指纹一条
        assertEquals(202L, out[0].chatId) // 组内取最早（dateSec=1_700_100）
        assertEquals("k3", out[1].key) // 无指纹条目保持原序
    }

    @Test
    fun `组槽保持首见顺序`() {
        val out = TimeBlindboxPolicy.dedupeItems(
            listOf(
                item("kb", 202, 1_700_500, "F2"),
                item("ka", 101, 1_700_400, "F1"),
                item("kc", 303, 1_700_100, "F1"), // F1 更早副本
            ),
        )
        assertEquals(2, out.size)
        // 槽序=首见序：F2 先见（kb）→ 其槽的最早副本排第一；F1 槽取组内最早 kc
        assertEquals("kb", out[0].key)
        assertEquals("kc", out[1].key)
    }
}
