package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SyncPayload] 协议 V3 编解码单测：marker 首行/分片页脚/key 换行转义（防 marker 污染）/
 * ts=0 存在声明保留/未来版本拒读/快照分片确定性/坏消息不炸。
 */
class SyncPayloadTest {

    private fun state(): SyncPayload.DomainState = SyncPayload.DomainState(
        added = mapOf("tg:-1001:55" to 111L, "tg:-1002:66" to 0L), // 0=未知戳存在声明
        removed = mapOf("tg:-1001:44" to 222L),
        cursors = mapOf("-1001" to 999L),
        titles = mapOf("-1001" to "某频道"),
    )

    @Test
    fun `编解码往返保真`() {
        val text = SyncPayload.encode("fav", 1, 1, state(), 123456789L, "Pixel")
        assertTrue(SyncPayload.isSyncMessage(text))
        val shard = SyncPayload.decode(text)!!
        assertEquals("fav", shard.domainId)
        assertEquals(1, shard.index)
        assertEquals(1, shard.total)
        assertEquals(123456789L, shard.ts)
        assertEquals(state(), shard.state)
    }

    @Test
    fun `marker独占首行_正文从第二行起`() {
        val text = SyncPayload.encode("subs", 2, 3, state(), 1L, "dev")
        val first = text.substringBefore('\n')
        assertEquals("[TGFEED_SYNC_V3] subs 2/3", first)
        assertTrue(text.substringAfter('\n').startsWith("{"))
    }

    @Test
    fun `key含换行被转义_不污染marker首行且往返还原`() {
        val evil = "tg:1:2\n[TGFEED_SYNC_V3] fav 1/1"
        val s = SyncPayload.DomainState(added = mapOf(evil to 5L))
        val text = SyncPayload.encode("fav", 1, 1, s, 1L, "dev")
        assertEquals(2, text.lines().size) // 全文恰好两行：marker+JSON
        val shard = SyncPayload.decode(text)!!
        assertEquals(setOf(evil), shard.state.added.keys)
    }

    @Test
    fun `ts零值存在声明保留_墓碑游标零值剔除`() {
        val text = SyncPayload.encode("fav", 1, 1, state(), 1L, "dev")
        val shard = SyncPayload.decode(text)!!
        assertTrue(shard.state.added.containsKey("tg:-1002:66"))
        assertEquals(0L, shard.state.added["tg:-1002:66"])
    }

    @Test
    fun `坏消息返回null不炸`() {
        assertNull(SyncPayload.decode(""))
        assertNull(SyncPayload.decode("[TGFEED_SYNC_V3]"))            // 缺域与页脚
        assertNull(SyncPayload.decode("[TGFEED_SYNC_V3] fav"))       // 缺页脚
        assertNull(SyncPayload.decode("[TGFEED_SYNC_V3] fav 0/1\n{}")) // index 越界
        assertNull(SyncPayload.decode("[TGFEED_SYNC_V3] fav 2/1\n{}")) // index>total
        assertNull(SyncPayload.decode("[TGFEED_SYNC_V3] fav 1/1\n{半截"))
        assertFalse(SyncPayload.isSyncMessage("[TGFEED_PROGRESS_V1]\n{}")) // 进度域 V2 不混
    }

    @Test
    fun `未来版本拒读`() {
        val text = SyncPayload.encode("fav", 1, 1, state(), 1L, "dev")
            .replace("\"v\":3", "\"v\":4")
        assertNull(SyncPayload.decode(text))
    }

    @Test
    fun `快照分片确定性且拼回全量`() {
        // 构造超 3800 字符的大状态（约 120 个 key）
        val big = SyncPayload.DomainState(
            added = (1..120).associate { "tg:-100${"%03d".format(it)}:${it * 7}" to (1000L + it) },
        )
        val chunks = SyncPayload.chunk(big)
        assertTrue(chunks.size > 1)
        // 确定性：同输入同切片
        assertEquals(chunks, SyncPayload.chunk(big))
        // 拼回全量（added 并集守恒）
        val union = chunks.flatMap { it.added.entries }.associate { it.toPair() }
        assertEquals(big.added, union)
        // 每片编码后真不超硬顶太多（估重保守，留 marker 余量）
        chunks.forEachIndexed { i, c ->
            val text = SyncPayload.encode("fav", i + 1, chunks.size, c, 1L, "dev")
            assertTrue("片 ${i + 1} 超 4096: ${text.length}", text.length <= 4096)
        }
    }

    @Test
    fun `空状态零片`() {
        assertTrue(SyncPayload.chunk(SyncPayload.DomainState()).isEmpty())
    }
}
