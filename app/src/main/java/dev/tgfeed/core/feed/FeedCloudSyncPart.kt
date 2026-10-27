package dev.tgfeed.core.feed

import androidx.lifecycle.viewModelScope
import dev.tgfeed.core.store.BlockedItem
import dev.tgfeed.core.td.ChannelBrief
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------- R300 多设备状态云端同步（四域）VM 接线区 ----------------
// 引擎=MultiSync（core/feed/MultiSync.kt）；协议=SyncPayload V3；裁决=SyncMergePolicy 唯一出处。
// 纪律：一切网络失败静默；key 不落日志；store 合并落盘全走 ledgerMutex（R108 串行通道）。

/**
 * 状态同步动作后的自动推送（四域共用主链路）：30s 节流（与进度域 maybeAutoPushProgress
 * 同口径）；引擎内另有域级 60s 节流+全域写预算闸。总闸 cloudSyncEnabled 关着零网络。
 * 被全域预算闸/域级节流拒掉的脏域留标记，本函数排一次延迟补推（35s 后），
 * 再推不掉等周期环（15min）兜底——不烧重试风暴。
 */
internal fun FeedViewModel.maybeAutoPushState() {
    if (!prefs.cloudSyncEnabled) return
    val sync = multiSync ?: return
    val now = System.currentTimeMillis()
    if (now - lastStateAutoPushAtMs < AUTO_PUSH_THROTTLE_MS) {
        scheduleStatePushRetry()
        return
    }
    lastStateAutoPushAtMs = now
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { sync.pushDirty() }
        if (sync.hasPendingDirty()) scheduleStatePushRetry()
    }
}

/** 延迟补推单飞闸（节流窗内被拒的脏域 35s 后补一枪；重复调用幂等） */
private fun FeedViewModel.scheduleStatePushRetry() {
    if (statePushRetryJob?.isActive == true) return
    statePushRetryJob = viewModelScope.launch(Dispatchers.IO) {
        delay(35_000L)
        lastStateAutoPushAtMs = 0L
        maybeAutoPushState()
    }
}

/**
 * 拉取云端四域并合入本地（启动拉/开关开拉/手动拉/事件驱动拉共用）：
 * 合并落盘由引擎在 ledgerMutex 内完成并经 onApplied 回校内存。
 * 返回合入项数；任何失败静默为 0（同步是锦上添花，绝不影响主流程）。
 */
internal suspend fun FeedViewModel.pullStateIntoMemory(): Int {
    if (!prefs.cloudSyncEnabled) return 0
    val sync = multiSync ?: return 0
    return runCatching { sync.pullAll() }.getOrDefault(0)
}

/**
 * 同步合入后的内存回校（MultiSync.onApplied 回调，在 ledgerMutex 内被调——轻量快速）：
 * store 落盘已完成，这里把受影响域的内存态重读对齐（与 toggle 动作的回校同口径）。
 */
internal fun FeedViewModel.reloadSyncedStateFromStore(changed: Set<MultiSync.Domain>) {
    for (d in changed) {
        when (d) {
            MultiSync.Domain.FAV -> {
                val items = runCatching { store.loadFavorites() }.getOrNull() ?: continue
                favoriteItems = items
                _favorites.value = items.mapTo(LinkedHashSet()) { it.key }
            }
            MultiSync.Domain.LATER -> {
                val items = runCatching { store.loadWatchLater() }.getOrNull() ?: continue
                watchLaterItems = items
                _watchLater.value = items.mapTo(LinkedHashSet()) { it.key }
            }
            MultiSync.Domain.BLK -> {
                _blockedChannels.value = runCatching { store.loadBlockedChannels() }.getOrNull() ?: continue
            }
            MultiSync.Domain.DIS -> {
                _dislikedKeys.value = runCatching { store.loadDisliked() }.getOrNull() ?: continue
            }
            MultiSync.Domain.KW -> {
                _mutedKeywords.value = runCatching { store.loadMutedKeywords() }.getOrNull() ?: continue
            }
            MultiSync.Domain.BITEM -> {
                _blockedItems.value = runCatching { store.loadBlockedItems() }.getOrNull() ?: continue
            }
            MultiSync.Domain.SUBS -> {
                _subscriptions.value = runCatching { store.loadSubscriptions() }.getOrNull()
                    ?.map { ChannelBrief(it.chatId, it.title, null) } ?: continue
            }
        }
    }
}

/**
 * 期零③可回滚口（诊断通道「清理云同步消息」确认后调用）：
 * 删除收藏夹里全部同步消息（四域 V3 + 进度域 V2）；本机数据不受影响，
 * 各端下次推送时 find-or-create 自然重建。结果经 [onResult] 回 Toast 文案。
 */
internal fun FeedViewModel.clearCloudSyncMessagesImpl(onResult: (String) -> Unit) {
    val sync = multiSync
    if (sync == null) {
        onResult("演示模式无云端同步可清")
        return
    }
    viewModelScope.launch(Dispatchers.IO) {
        val n = sync.clearAllRemoteMessages()
        onResult(
            when {
                n > 0 -> "已清理 $n 条云同步消息（本机数据未动，后续会自动重建同步消息）"
                n == 0 -> "收藏夹里没有云同步消息"
                else -> "清理失败（网络/限流），稍后再试"
            }
        )
    }
}
