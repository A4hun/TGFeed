package dev.tgfeed.core.sync

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.tgfeed.core.net.AppForeground
import dev.tgfeed.core.net.NetworkMeteredBus
import dev.tgfeed.core.store.LocalStore
import dev.tgfeed.core.td.GatewayState
import dev.tgfeed.core.td.MockTdGateway
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * R420 后台定时同步 Worker：App 不在前台时把关注频道首页新内容拉到本地快照
 * （following_snapshot.json），打开即见最新。只读：仅拉消息头/内联小缩略图写本地缓存，
 * 零账号写操作、零搜索族接口、不发起媒体下载（计量网络天然只拉头）。
 * 探针 Tag「R420Sync」每次一行：skip 原因 / 频道数 / 新条目数 / 耗时，不含任何名字。
 */
class BackgroundSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val t0 = System.currentTimeMillis()
        var skip = BackgroundSyncPolicy.Skip.NONE
        var channels = 0
        var newCount = 0
        var fresh = 0
        val metered = runCatching { NetworkMeteredBus.metered }.getOrDefault(true)
        try {
            val app = applicationContext as? dev.tgfeed.App
            val container = app?.container
            val minutes = container?.prefs?.backgroundSyncMinutes ?: 0
            val pre = BackgroundSyncPolicy.preSkip(
                rawMinutes = minutes,
                inForeground = isProcessForeground(),
                realGateway = container?.hasApiCredentials() == true,
                authorized = true, // 授权在网关就绪后再判
                busy = FollowingSnapshotSync.lock.isLocked,
            )
            if (pre != BackgroundSyncPolicy.Skip.NONE || container == null) {
                skip = if (pre == BackgroundSyncPolicy.Skip.NONE) BackgroundSyncPolicy.Skip.ERROR else pre
                return Result.success()
            }
            val done = withTimeoutOrNull(BackgroundSyncPolicy.RUN_TIMEOUT_MS) {
                val gateway = container.ensureGateway()
                if (gateway is MockTdGateway) {
                    skip = BackgroundSyncPolicy.Skip.NO_REAL_GATEWAY; return@withTimeoutOrNull true
                }
                val st = withTimeoutOrNull(BackgroundSyncPolicy.AUTH_WAIT_MS) {
                    gateway.state.first { it !is GatewayState.Loading }
                }
                if (st !is GatewayState.Ready) {
                    skip = BackgroundSyncPolicy.Skip.NOT_AUTHORIZED; return@withTimeoutOrNull true
                }
                if (!FollowingSnapshotSync.lock.tryLock()) {
                    skip = BackgroundSyncPolicy.Skip.BUSY; return@withTimeoutOrNull true
                }
                try {
                    val r = FollowingSnapshotSync.syncOnce(gateway, LocalStore(applicationContext), PAGE_LIMIT)
                    channels = r.channels; fresh = r.fresh; newCount = r.newCount
                    if (!r.ok) skip = BackgroundSyncPolicy.Skip.ERROR
                } finally {
                    FollowingSnapshotSync.lock.unlock()
                }
                true
            }
            if (done == null) skip = BackgroundSyncPolicy.Skip.TIMEOUT
            return Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            skip = BackgroundSyncPolicy.Skip.TIMEOUT
            throw e
        } catch (e: Throwable) {
            skip = BackgroundSyncPolicy.Skip.ERROR
            return Result.success() // 周期任务不重试，下一周期自然再来
        } finally {
            Log.i(
                TAG,
                "run skip=${skip.name} channels=${channels} fresh=${fresh} new=${newCount} " +
                    "metered=${metered} media=false " +
                    "costMs=${System.currentTimeMillis() - t0}",
            )
        }
    }

    /**
     * 前台判定：进程 importance 可见及以上=前台（AppForeground 默认 true，后台冷启进程里
     * 不可信，故以系统 importance 为主、AppForeground 仅在进程可见时作补充）。
     */
    private fun isProcessForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        runCatching { ActivityManager.getMyMemoryState(info) }
        val visible = info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        return visible && AppForeground.inForeground
    }

    companion object {
        const val TAG = "R420Sync"
        private const val PAGE_LIMIT = 100 // 与前台关注流预热同口径（FOLLOWING_WARM_PAGE_LIMIT）
    }
}
