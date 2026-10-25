package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SyncMergePolicy] 合并裁决矩阵单测（R300 期一 MVP 裁决唯一出处的锚）：
 * per-key LWW+删除墓碑 / 平局保本机 / 时钟超前熔断 / 存量未知戳存在性并集（宁多勿丢）/
 * 单调游标取 max / 墓碑 TTL / 快照分片确定性。
 */
class SyncMergePolicyTest {

    private val now = 1_700_000_000_000L

    // ---------------- per-key LWW + 墓碑 ----------------

    @Test
    fun `远端加入较新_本机无_判在册`() {
        val m = SyncMergePolicy.mergeSetKey(
            localPresent = false, localAdded = 0L, localRemoved = 0L,
            remoteAdded = now - 1000, remoteClaimed = true, remoteRemoved = 0L, nowMs = now,
        )
        assertTrue(m.present)
        assertEquals(now - 1000, m.added)
    }

    @Test
    fun `远端墓碑较新_本机在册_判删除传播`() {
        val m = SyncMergePolicy.mergeSetKey(
            localPresent = true, localAdded = now - 10_000, localRemoved = 0L,
            remoteAdded = now - 10_000, remoteClaimed = true, remoteRemoved = now - 100, nowMs = now,
        )
        assertFalse(m.present)
        assertEquals(now - 100, m.removed)
    }

    @Test
    fun `本机加入较新_远端墓碑较旧_本机赢不删`() {
        // A 机取消收藏（t1）→ B 机重新收藏（t2>t1）→ 收敛为「在」（design §3.1 例二）
        val m = SyncMergePolicy.mergeSetKey(
            localPresent = true, localAdded = now - 100, localRemoved = 0L,
            remoteAdded = 0L, remoteClaimed = false, remoteRemoved = now - 10_000, nowMs = now,
        )
        assertTrue(m.present)
        assertEquals(now - 100, m.added)
    }

    @Test
    fun `平局保本机_两侧同戳不动本机`() {
        val m = SyncMergePolicy.mergeSetKey(
            localPresent = true, localAdded = now - 500, localRemoved = 0L,
            remoteAdded = now - 500, remoteClaimed = true, remoteRemoved = 0L, nowMs = now,
        )
        assertTrue(m.present)
        assertEquals(now - 500, m.added)
    }

    @Test
    fun `删除后远端重加较新_复活成立`() {
        // 本机墓碑 t1，远端加入 t2>t1 → 复活（ProgressSync V2「删除后重看=复活成立」同语义）
        val m = SyncMergePolicy.mergeSetKey(
            localPresent = false, localAdded = 0L, localRemoved = now - 10_000,
            remoteAdded = now - 100, remoteClaimed = true, remoteRemoved = 0L, nowMs = now,
        )
        assertTrue(m.present)
        assertEquals(now - 100, m.added)
    }

    // ---------------- 时钟异常熔断 ----------------

    @Test
    fun `远端戳超前墙钟超阈值_整组拒收`() {
        val future = now + SyncMergePolicy.MAX_CLOCK_SKEW_MS + 1
        val m = SyncMergePolicy.mergeSetKey(
            localPresent = true, localAdded = now - 100, localRemoved = 0L,
            remoteAdded = future, remoteClaimed = true, remoteRemoved = future, nowMs = now,
        )
        assertTrue(m.present) // 本机原样不动
        assertEquals(now - 100, m.added)
    }

    @Test
    fun `远端戳轻微超前_阈值内正常收编`() {
        val slightlyFuture = now + SyncMergePolicy.MAX_CLOCK_SKEW_MS - 1
        val m = SyncMergePolicy.mergeSetKey(
            localPresent = false, localAdded = 0L, localRemoved = 0L,
            remoteAdded = slightlyFuture, remoteClaimed = true, remoteRemoved = 0L, nowMs = now,
        )
        assertTrue(m.present)
        assertEquals(slightlyFuture, m.added)
    }

    // ---------------- 存量未知戳存在性并集（favTs 迁移兜底，宁多勿丢） ----------------

    @Test
    fun `本机在册未知戳_远端墓碑不误杀`() {
        // 存量老收藏（favTs=0）撞上远端删除墓碑：无法定序 → 保本机（宁多勿丢）
        val m = SyncMergePolicy.mergeSetKey(
            localPresent = true, localAdded = 0L, localRemoved = 0L,
            remoteAdded = 0L, remoteClaimed = false, remoteRemoved = now - 100, nowMs = now,
        )
        assertTrue(m.present)
    }

    @Test
    fun `本机在册未知戳_远端已知加入戳被收编`() {
        // 未知戳条目获得远端已知戳： uplift 后进入正轨 LWW
        val m = SyncMergePolicy.mergeSetKey(
            localPresent = true, localAdded = 0L, localRemoved = 0L,
            remoteAdded = now - 500, remoteClaimed = true, remoteRemoved = 0L, nowMs = now,
        )
        assertTrue(m.present)
        assertEquals(now - 500, m.added)
    }

    @Test
    fun `本机无_远端未知存在声明_并集判在册`() {
        // 远端老设备推上来的 ts=0 存在声明：本机没有 → 按「在」收编（宁多勿丢）
        val m = SyncMergePolicy.mergeSetKey(
            localPresent = false, localAdded = 0L, localRemoved = 0L,
            remoteAdded = 0L, remoteClaimed = true, remoteRemoved = 0L, nowMs = now,
        )
        assertTrue(m.present)
    }

    @Test
    fun `本机无_远端无声明无戳_不在册`() {
        val m = SyncMergePolicy.mergeSetKey(
            localPresent = false, localAdded = 0L, localRemoved = 0L,
            remoteAdded = 0L, remoteClaimed = false, remoteRemoved = 0L, nowMs = now,
        )
        assertFalse(m.present)
    }

    // ---------------- 单调游标 ----------------

    @Test
    fun `已读水位取max`() {
        assertEquals(200L, SyncMergePolicy.mergeCursor(100L, 200L))
        assertEquals(200L, SyncMergePolicy.mergeCursor(200L, 100L))
        assertEquals(0L, SyncMergePolicy.mergeCursor(0L, 0L))
    }

    // ---------------- 墓碑 TTL ----------------

    @Test
    fun `墓碑超期可剪_期内保留`() {
        val expired = now - SyncMergePolicy.TOMBSTONE_TTL_MS - 1
        assertTrue(SyncMergePolicy.tombstoneExpired(expired, now))
        assertFalse(SyncMergePolicy.tombstoneExpired(now - 1000, now))
        assertFalse(SyncMergePolicy.tombstoneExpired(0L, now)) // 无墓碑不剪
    }

    // ---------------- 快照分片（确定性） ----------------

    @Test
    fun `分片不切断单条_总量守恒`() {
        val lines = (1..50).map { "k$it:" + "x".repeat(50) }
        val shards = SyncMergePolicy.shardPayload(lines, maxChars = 300)
        assertTrue(shards.size > 1)
        assertEquals(lines, shards.flatten())
        // 除可能的「单条超限强占片」外，每片不超上限+1 行宽
        shards.forEach { shard ->
            val len = shard.sumOf { it.length + 1 }
            assertTrue(shard.size == 1 || len <= 300 + 60)
        }
    }

    @Test
    fun `分片确定性_同输入同切片`() {
        val lines = (1..100).map { "key$it:${it * 7}" }
        assertEquals(SyncMergePolicy.shardPayload(lines, 200), SyncMergePolicy.shardPayload(lines, 200))
    }

    @Test
    fun `空输入零片`() {
        assertTrue(SyncMergePolicy.shardPayload(emptyList()).isEmpty())
    }
}
