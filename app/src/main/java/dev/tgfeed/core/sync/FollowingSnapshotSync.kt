package dev.tgfeed.core.sync

import dev.tgfeed.core.feed.FollowingFeedSource
import dev.tgfeed.core.feed.SeenDedupPolicy
import dev.tgfeed.core.store.LocalStore
import dev.tgfeed.core.td.MockTdGateway
import dev.tgfeed.core.td.TdGateway
import kotlinx.coroutines.sync.Mutex

/**
 * R420 关注流快照同步的不依赖 UI 最小入口（原 FeedViewModel.warmFollowingSnapshot 真身外提）：
 * 订阅频道并发拉首页（FollowingFeedSource，只取消息头+内联小缩略图，不发起媒体下载）
 * → 过已看过滤 → 落盘 following_snapshot.json。前台预热与后台 Worker 共用本入口与同一把锁。
 */
object FollowingSnapshotSync {

    /** 前台预热与后台 Worker 的进程内互斥锁（tryLock 语义：在途即跳过，不叠拉） */
    val lock = Mutex()

    /** @property ok false=可重试失败；channels 订阅频道数；fresh 落盘条数；newCount 相对旧快照的新条目数 */
    data class Outcome(val ok: Boolean, val channels: Int, val fresh: Int, val newCount: Int)

    suspend fun syncOnce(gateway: TdGateway, store: LocalStore, pageLimit: Int): Outcome {
        val subs = runCatching { store.loadSubscriptions() }.getOrDefault(emptyList())
        if (subs.isEmpty()) return Outcome(true, 0, 0, 0)
        val src = FollowingFeedSource(gateway, store)
        val page = runCatching { src.nextPage(pageLimit) }.getOrNull()
            ?: return Outcome(false, subs.size, 0, 0)
        if (page.items.isEmpty()) return Outcome(true, subs.size, 0, 0)
        val (seenK, seenU) = runCatching { store.loadSeenIdentities() }
            .getOrDefault(emptySet<String>() to emptySet())
        val fresh = page.items.filter { !SeenDedupPolicy.isSeen(it, seenK, seenU) }
        if (fresh.isEmpty()) return Outcome(true, subs.size, 0, 0)
        val oldKeys = runCatching { store.loadFollowingSnapshot()?.items?.map { it.key }?.toSet() }
            .getOrNull() ?: emptySet()
        val ok = runCatching {
            store.saveFollowingSnapshot(fresh, if (gateway is MockTdGateway) "demo" else "real")
        }.isSuccess
        return Outcome(ok, subs.size, fresh.size, BackgroundSyncPolicy.countNew(oldKeys, fresh.map { it.key }))
    }
}
