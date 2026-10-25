package dev.tgfeed.core.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.tgfeed.core.prefs.AppPrefs
import dev.tgfeed.core.store.BlockedItem
import dev.tgfeed.core.store.LocalStore
import dev.tgfeed.core.td.FeedRequest
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.File

/** 自动翻页前登记：被弹走的 key + 计划落点 key（视频/图文/音频三条连播链共用） */
internal fun FeedViewModel.noteAutoEjectedImpl(ejectedKey: String, plannedTargetKey: String?) {
    synchronized(autoAdvanceLock) {
        autoEjectedKeys += ejectedKey
        autoAdvancePlannedKey = plannedTargetKey
    }
    android.util.Log.i(
        "TGFlow",
        "连播弹走: …${ejectedKey.takeLast(12)} → 计划落点 …${plannedTargetKey?.takeLast(12)}",
    )
}

/** 回看豁免查询（VideoPage ENDED 监听 / 图音卡自动跳页前调用）：命中即不许自动跳页 */
internal fun FeedViewModel.isAutoAdvanceExemptImpl(key: String): Boolean =
    synchronized(autoAdvanceLock) { key in replayExemptKeys }

/** onPage 落定消费：手动翻回被弹走过的页 → 转入回看豁免（自动连播落子不算翻回） */
internal fun FeedViewModel.consumeAutoEjectedOnSettle(landedKey: String) {
    synchronized(autoAdvanceLock) {
        val planned = autoAdvancePlannedKey
        autoAdvancePlannedKey = null
        if (planned != null && planned == landedKey) return // 自动连播落子，不消费豁免
        if (autoEjectedKeys.remove(landedKey)) {
            replayExemptKeys += landedKey
            android.util.Log.i("TGFlow", "翻回消费豁免: …${landedKey.takeLast(12)}（本条不再自动弹走）")
        }
    }
}

/** 切源/刷新清零连播豁免记忆（新内容上下文重新计） */
internal fun FeedViewModel.clearAutoAdvanceMemory() {
    synchronized(autoAdvanceLock) {
        autoEjectedKeys.clear()
        replayExemptKeys.clear()
        autoAdvancePlannedKey = null
    }
}

// ---------------- 长按面板动作 ----------------

/**
 * 切换频道屏蔽：屏蔽后当前 items 立即移除该频道条目；
 * 后续 loadMore 的新结果在 ViewModel 层统一过滤（加权混排源内部也过滤，双保险）。
 * 解除屏蔽不回补已移除的条目，等下一次刷新自然出现。
 */
internal fun FeedViewModel.toggleBlockChannelImpl(chatId: Long) {
    // R108：点击瞬间定格意图（屏蔽/解除）——落盘是异步合并，不能以落盘时的内存态反推意图
    val blocking = chatId !in _blockedChannels.value
    // R300 同步记账：屏蔽=加入戳，解除=墓碑（传播两端）
    if (blocking) multiSync?.noteAdded(MultiSync.Domain.BLK, chatId.toString())
    else multiSync?.noteRemoved(MultiSync.Domain.BLK, chatId.toString())
    maybeAutoPushState()
    // 乐观更新内存让 UI 立即响应；磁盘真值由串行通道合并后回校
    _blockedChannels.value =
        if (blocking) _blockedChannels.value + chatId else _blockedChannels.value - chatId
    if (blocking) {
        // P1-11：CAS 原子剔除——与 IO 线程 loadMore 的 _items.update 追加并发时互不丢更新
        _items.update { list -> list.filterNot { it.chatId == chatId } }
        // R111：屏蔽成功顺手向广场上报 [B] 频道避雷协议（修"避雷写侧死"——读侧
        // fetchPlaza 已解析 channelBlocks 进发现页 Q 软惩罚，此前写侧零调用致全站避雷空转）。
        // 频控/幂等全在 PlazaService.postChannelBlock 内置：同频道每进程只报一次 +
        // 全局 10s 限速（被限速丢弃会释放标记，后续屏蔽动作可补报）；解除屏蔽不回撤
        // （协议无撤销语义，读侧按发送者去重，重复上报不重复计数）
        plazaPostChannelBlock(chatId, PLAZA_BLOCK_REPORT_REASON)
    }
    viewModelScope.launch(Dispatchers.IO) {
        // R108：统一串行通道内先重读磁盘账本再按意图合并——冷启动恢复未完成前内存是空的，
        // 拿内存快照全量覆盖 blocked_channels.json 会把历史屏蔽列表清成 1 条（addSubscription 同款纪律）
        ledgerMutex.withLock {
            val disk = runCatching { store.loadBlockedChannels() }.getOrDefault(emptySet())
            val merged = if (blocking) disk + chatId else disk - chatId
            runCatching { store.saveBlockedChannels(merged) }
                .onFailure { _error.value = "屏蔽设置保存失败：${it.message}" }
            // 以磁盘合并终值回校内存（恢复协程若刚把老账搬进内存，这里对齐）
            _blockedChannels.value = merged
        }
    }
}

/**
 * 切换"不感兴趣"（视频粒度黑名单，区别于频道级屏蔽 [toggleBlockChannel]，两者并存）：
 * 加入黑名单时：
 *  1. 当前 items 立即剔除该条，集合持久化到 disliked.json；
 *  2. 后续 loadMore 统一过滤，WeightedAggregateFeedSource 内部也过滤一道（双保险）；
 *  3. 往 watch_events 追加一条 watchSec=0 的强负反馈事件——加权混排的频道权重
 *     取该频道最近事件的平均观看比例（0.3 + ratio×0.7），0 秒观看会自然把它拉向权重底 0.3；
 *  4. P1 #1/#4 当日即生效加强：互动加分立减 [BOOST_DISLIKE]（CES ×-4）+
 *     会话系数 ×[SESSION_DOWN_FACTOR]（内存态），撤销时两者对称回退。
 *     粒度说明：本条内容进 disliked 硬过滤（内容粒度负反馈，该话题不再出现），
 *     频道级只是降权不是封杀（"话题不想要但频道还想看"的诉求由内容粒度过滤满足）。
 * 再次触发同一条目则移出黑名单（幂等；不回补已移除的条目，等下次刷新自然出现，
 * 负反馈事件不撤销——量小且 300 条环形上限，噪声会自然被新事件冲淡）。
 *
 * 广告永久屏蔽（计数升级）：
 *  - 计数键取内容指纹 fileUniqueId，无指纹的条目（Mock/老数据）回落 item.key——
 *    同一广告换消息重发/被转发到别的频道时 item.key 变了指纹不变，只有按指纹计数
 *    "同一内容点 2 次"才累计得起来（用户反馈"同一广告点了不感兴趣还会再出现"的根因）；
 *  - 累计 ≥[PERMANENT_BLOCK_THRESHOLD] 次 → 指纹进 blocked_items.json 永久屏蔽表，
 *    并立即把当前列表里同指纹的全部条目（含跨频道转发的同片）一并剔除；
 *  - 防误杀：单次不感兴趣只进 disliked（可撤销），不永久；撤销时计数对称 -1，
 *    避免"很久以前点过一次"让未来一次误点直接顶到阈值；已进永久表的撤销不解封
 *    （解封走屏蔽管理 [unblockItem]）。
 */
internal fun FeedViewModel.toggleNotInterestedImpl(item: VideoItem) {
    val newSet = _dislikedKeys.value.let { if (item.key in it) it - item.key else it + item.key }
    val disliking = item.key in newSet
    _dislikedKeys.value = newSet
    // R300 同步记账：不感兴趣=加入戳，撤销=墓碑
    if (disliking) multiSync?.noteAdded(MultiSync.Domain.DIS, item.key)
    else multiSync?.noteRemoved(MultiSync.Domain.DIS, item.key)
    maybeAutoPushState()
    if (disliking) {
        // P1-11：CAS 原子剔除（同 toggleBlockChannel）
        _items.update { list -> list.filterNot { it.key == item.key } }
        // R189 轨道缓存全表作废：屏蔽/不感兴趣是内容指纹级变更——其他轨道（媒体 tab
        // 快照/列槽）的冻结列表里还躺着该条目（缓存恢复不过 fetch 硬过滤链），不
        // 作废会在切回时「还魂」（R156 纪律：屏蔽即从一切在场视图消失）
        evictTrackCacheImpl()
    }
    val counterKey = item.fileUniqueId.ifEmpty { item.key }
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { store.saveDisliked(newSet) }
            .onFailure { _error.value = "不感兴趣设置保存失败：${it.message}" }
        if (disliking) {
            // 强负反馈：0 秒观看事件，复用现有权重机制自然拉低该频道权重
            runCatching { store.appendWatchEvent(item.chatId, 0, item.durationSec) }
            // P1 #1/#4 负反馈当日即生效（CES ×-4 口径）：立减该频道互动加分 [BOOST_DISLIKE]
            // （削既有正光环，不等 7 天衰减），并记会话系数 ×0.5（noteSessionSignal 内部
            //  不感兴趣 1 次即降）——与上方 0 秒事件的 avgRatio 拉低共三条通道同时生效；
            // 内容粒度：本条 key 已进 disliked 硬过滤（loadMore/源内/置顶三重），
            // 频道级降权是"口味偏移"不是"封杀频道"，撤销时全部对称回退
            addInteractionBoost(item.chatId, BOOST_DISLIKE)
            noteSessionSignal(item.chatId, SESSION_SIGNAL_DISLIKE)
            interactionStats.recordNegative("dislike", item.chatId)
            // 累计"不感兴趣"次数，达到阈值升级为内容指纹永久屏蔽
            val count = runCatching { store.bumpDislikeCount(counterKey) }.getOrDefault(0)
            if (count >= PERMANENT_BLOCK_THRESHOLD &&
                _blockedItems.value.none { it.fileUniqueId.ifEmpty { it.key } == counterKey }
            ) {
                val entry = BlockedItem(
                    key = item.key,
                    fileUniqueId = item.fileUniqueId,
                    // R228：titleLine() 共享件——parsed_title（逐条解析标题）优先，
                    // NULL 回退 caption 首行（旧口径行为不变）
                    title = item.titleLine()
                        .ifBlank { item.channelTitle },
                    channelTitle = item.channelTitle,
                    ts = System.currentTimeMillis(),
                )
                val added = runCatching { store.addBlockedItem(entry) }.getOrDefault(false)
                if (added) {
                    // R300：新进永久屏蔽表标脏（加入戳在 BlockedItem.ts 上，无需台账）
                    multiSync?.markDirty(MultiSync.Domain.BITEM)
                    // store 内已去重置顶，直接现读回内存（命中缓存，无额外 IO）
                    _blockedItems.value = runCatching { store.loadBlockedItems() }
                        .getOrDefault(listOf(entry) + _blockedItems.value)
                    // 立即剔除当前列表里同指纹的全部条目（含本条）：
                    // 跨频道转发的同片此刻就在列表里的也一并清掉；CAS 与 loadMore 追加互不丢更新
                    val uid = item.fileUniqueId
                    _items.update { list ->
                        list.filterNot {
                            it.key == item.key || (uid.isNotEmpty() && it.fileUniqueId == uid)
                        }
                    }
                }
            }
        } else {
            // 撤销"不感兴趣"：计数对称 -1（到 0 清除）；已进永久屏蔽表的不受影响；
            // 互动加分与会话系数对称回退（与加 dislike 时的双通道负反馈镜像）
            runCatching { store.decayDislikeCount(counterKey) }
            addInteractionBoost(item.chatId, -BOOST_DISLIKE)
            clearSessionAdjust(item.chatId)
        }
    }
}

/**
 * 解除一条永久屏蔽（屏蔽管理入口）：按身份（fileUniqueId 或 item.key）从
 * blocked_items.json 移除并更新内存。不回补已剔除的条目（等下次刷新自然出现）；
 * dislike 计数保留——解封后再点 ≥1 次不感兴趣会重新累计并可能再次进表。
 */
internal fun FeedViewModel.unblockItemImpl(identity: String) {
    if (identity.isEmpty()) return
    // R300 同步记账：解除永久屏蔽记墓碑
    multiSync?.noteRemoved(MultiSync.Domain.BITEM, identity)
    maybeAutoPushState()
    viewModelScope.launch(Dispatchers.IO) {
        val removed = runCatching { store.removeBlockedItem(identity) }.getOrDefault(false)
        if (removed) {
            _blockedItems.value = _blockedItems.value.filterNot {
                it.fileUniqueId == identity || it.key == identity
            }
        }
    }
}

/** 切换稍后再看：完整条目落盘（模式同 toggleFavorite），新暂存置顶（加入时间倒序） */
internal fun FeedViewModel.toggleWatchLaterImpl(item: VideoItem) {
    // R108：点击瞬间定格意图（加/减）——落盘是异步合并，不能以落盘时的内存态反推意图
    val adding = item.key !in _watchLater.value
    // R300 favTs/记账：新暂存打加入戳；取消暂存记墓碑（同收藏域口径）
    val stamped = if (adding) item.copy(favTs = System.currentTimeMillis()) else item
    if (adding) multiSync?.markDirty(MultiSync.Domain.LATER) else multiSync?.noteRemoved(MultiSync.Domain.LATER, item.key)
    // 乐观更新内存让 UI 立即响应；磁盘真值由串行通道合并后回校
    if (adding) {
        // 新暂存：完整条目入库并置顶（列表即加入时间倒序）
        watchLaterItems = listOf(stamped) + watchLaterItems.filterNot { it.key == item.key }
        _watchLater.value = _watchLater.value + item.key
    } else {
        // 取消暂存：从列表移除
        watchLaterItems = watchLaterItems.filterNot { it.key == item.key }
        _watchLater.value = _watchLater.value - item.key
    }
    maybeAutoPushState()
    viewModelScope.launch(Dispatchers.IO) {
        // R108：统一串行通道内先重读磁盘账本再按意图合并——冷启动恢复未完成前内存是空的，
        // 拿内存快照全量覆盖 watch_later.json 会把历史暂存清成 1 条（addSubscription 同款纪律）；
        // 快速连点的多次落盘被同一把锁串行化，后到的写一定读到前次的合并终值（M1 不乱序）
        ledgerMutex.withLock {
            val disk = runCatching { store.loadWatchLater() }.getOrDefault(emptyList())
            val merged = if (adding) {
                listOf(stamped) + disk.filterNot { it.key == item.key }
            } else {
                disk.filterNot { it.key == item.key }
            }
            runCatching { store.saveWatchLater(merged) }
                .onFailure { _error.value = "稍后再看保存失败：${it.message}" }
            // 以磁盘合并终值回校内存（恢复协程若刚把老账搬进内存，这里对齐）
            watchLaterItems = merged
            _watchLater.value = merged.mapTo(LinkedHashSet<String>()) { it.key }
        }
    }
}

/**
 * 批量取消稍后再看（GAPS3 批量操作，供稍后再看管理类列表页多选移除用）：
 * 内存列表/集合一次性过滤 + 一次落盘；稍后再看不入互动权重，无需回退。
 * 未命中任何已暂存 key 时直接忽略（不碰磁盘）。
 */
internal fun FeedViewModel.removeWatchLaterBatchImpl(keys: Set<String>) {
    if (keys.isEmpty()) return
    val newItems = watchLaterItems.filterNot { it.key in keys }
    if (newItems.size == watchLaterItems.size) return // 本来就没有这些暂存
    // 乐观更新内存（同 toggleWatchLater 纪律），磁盘真值由串行通道合并后回校
    watchLaterItems = newItems
    _watchLater.value = _watchLater.value - keys
    // R300 同步记账：批量取消暂存逐 key 记墓碑
    keys.forEach { multiSync?.noteRemoved(MultiSync.Domain.LATER, it) }
    maybeAutoPushState()
    viewModelScope.launch(Dispatchers.IO) {
        // R108：统一串行通道内重读磁盘再移除——内存没有的暂存（恢复未完成/他处新加）必须保住
        ledgerMutex.withLock {
            val disk = runCatching { store.loadWatchLater() }.getOrDefault(emptyList())
            val merged = disk.filterNot { it.key in keys }
            runCatching { store.saveWatchLater(merged) }
                .onFailure { _error.value = "稍后再看保存失败：${it.message}" }
            watchLaterItems = merged
            _watchLater.value = merged.mapTo(LinkedHashSet<String>()) { it.key }
        }
    }
}

/** 设置自动连播开关（立即生效并写入 AppPrefs） */
internal fun FeedViewModel.setAutoAdvanceImpl(v: Boolean) {
    _autoAdvance.value = v
    runCatching { prefs.autoAdvance = v }
}

// ---------------- CRITICAL-2：收藏/稍后再看的重启复活 ----------------

/**
 * 重启复活：收藏/稍后再看持久化的是 TDLib 会话级 fileId，进程重启即失效——
 * 复活前这些条目 ensurePlayable 必失败（"视频下载中…"永久转圈）。
 * LocalStore 已持久化 fileUniqueId（File.remote.uniqueId，跨会话稳定）；
 * 本方法体检两个列表，对失效条目用 GetRemoteFile(remoteFileId=fileUniqueId)
 * 向 TDLib 换发本会话新 fileId，成功后更新内存并一次性落盘固化。
 * 幂等：同会话重复调用时 GetRemoteFile 返回同一 fileId，重复体检无害。
 * Mock 网关无远端文件概念（直链条目本就可播），整体跳过。
 * init 时调用（接在磁盘恢复之后，同一协程串行执行，无并发覆盖）。
 */
internal fun FeedViewModel.reviveItemsImpl() {
    viewModelScope.launch(Dispatchers.IO) {
        // R108：复活体检跨网络调用（GetRemoteFile 逐条换发），其"读盘→换发→写回"窗口
        // 内的互动合并结果会被体检旧快照回滚——必须进统一串行通道（init 恢复协程同锁）
        ledgerMutex.withLock { reviveStoredItems() }
    }
}

/**
 * [reviveItems] 的挂起实现（供 init 恢复协程内串行调用，避免与恢复写回并发交错）。
 * 体检规则：
 *  - playUri 为 http(s)/asset 直链（Mock/外链）：永久有效，跳过
 *  - playUri 为本地路径且文件仍在：有效，跳过（兜底分支——序列化时本地路径本不落盘）
 *  - fileUniqueId 为空（旧版本数据）：救不回，原样保留（播放时走 ensurePlayable 报错链）
 *  - 其余（fileId<0 无直链 / 本地路径失效 / playUri 为 null 的 TDLib 条目）：换发新 fileId
 * 数据以 store 现读为准（@Synchronized + 内存缓存，安全），不读本类字段——
 * 避免与恢复协程/互动写回交错时拿到半成品。
 */
internal suspend fun FeedViewModel.reviveStoredItems() {
    val real = gateway as? TdLibGateway ?: return
    var changed = false
    val favs = runCatching { store.loadFavorites() }.getOrDefault(emptyList())
    val newFavs = favs.map { reviveOne(it, real)?.also { changed = true } ?: it }
    if (changed) {
        runCatching { store.saveFavorites(newFavs) }
        favoriteItems = newFavs
        changed = false
    }
    val later = runCatching { store.loadWatchLater() }.getOrDefault(emptyList())
    val newLater = later.map { reviveOne(it, real)?.also { changed = true } ?: it }
    if (changed) {
        runCatching { store.saveWatchLater(newLater) }
        watchLaterItems = newLater
    }
    // key 集合不受 fileId 换发影响，_favorites/_watchLater 无需重建；
    // 收藏/稍后源（FavoritesFeedSource/WatchLaterFeedSource）每次刷取现读 store，自动拿到新 fileId
}

/**
 * 单条体检：需复活且复活成功返回换发新 fileId 的副本；无需复活或复活失败返回 null
 * （调用方保留原条目）。GetRemoteFile 是本地数据库优先的轻量查询，串行逐条开销可忽略。
 */
internal suspend fun FeedViewModel.reviveOne(item: VideoItem, real: TdLibGateway): VideoItem? {
    if (item.fileUniqueId.isEmpty()) return null // 旧数据无 uniqueId，救不回
    val uri = item.playUri
    if (uri != null) {
        if (uri.startsWith("http") || uri.startsWith("asset:///")) return null // 直链永久有效
        if (runCatching { File(uri).isFile }.getOrDefault(false)) return null // 本地文件仍在
        // 本地路径已失效 → 落入复活
    }
    // playUri 为 null 的 TDLib 条目 / fileId<0 无直链 / 本地路径失效：换发新 fileId。
    // 优先按消息重解析（GetMessage 拿回当前内容的新 fileId——GetRemoteFile 收的是
    // remote.id 不是 uniqueId，用它换发会放错视频，"货不对板"的根因）；
    // 消息没了（被删）才回落 uniqueId 路径
    val newId = real.reviveFileIdByMessage(item.chatId, item.messageId)
        ?: real.reviveFileId(item.fileUniqueId)
        ?: return null
    return if (newId == item.fileId) null else item.copy(fileId = newId)
}

/**
 * 设置定时关闭：minutes=null/0 取消；否则到点在 viewModelScope 发射 sleepNow。
 * 重复调用替换旧定时；倒计时挂在 viewModelScope 上，VM 销毁自动失效。
 */
internal fun FeedViewModel.setSleepTimerImpl(minutes: Int?) {
    sleepJob?.cancel()
    sleepJob = null
    val mins = minutes ?: return
    if (mins <= 0) return
    sleepJob = viewModelScope.launch {
        delay(mins * 60_000L)
        _sleepNow.tryEmit(Unit) // 缓冲 1 发，tryEmit 永不挂起
    }
}

/**
 * 清空观看历史（设置页"清空观看历史"按钮）：IO 线程删 history.json + 清 store 缓存。
 * 同时复位历史节流标记 [lastHistoryKey]：清空属于用户显式行为，
 * 之后翻页（含翻回当前条目）应重新记录历史，符合"从清空后重新计"的直觉。
 * 当前已加载的 items 不受影响（历史不参与列表过滤）。
 */
internal fun FeedViewModel.clearHistoryImpl() {
    lastHistoryKey = null
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { store.clearHistory() }
            .onFailure { _error.value = "清空观看历史失败：${it.message}" }
    }
}

/**
 * 删除单条观看历史（观看历史页"长按删除"）：经本实例 store 落盘，保证
 * historyCache 单写入方一致（页面自建 LocalStore 实例删除会被本实例后续
 * appendHistory 的旧缓存整体重写回盘，即"删除复活"）。
 * 若被删条目恰是节流标记 [lastHistoryKey]，一并复位：删除是用户显式行为，
 * 之后翻回该条目应重新记录历史（与 [clearHistory] 的复位语义一致）。
 */
internal fun FeedViewModel.removeHistoryImpl(key: String) {
    if (lastHistoryKey == key) lastHistoryKey = null
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { store.removeHistoryEntry(key) }
            .onFailure { _error.value = "删除观看记录失败：${it.message}" }
    }
}

/**
 * 清空"不感兴趣"黑名单（设置页按钮）：IO 线程删 disliked.json + 清 store 缓存；
 * 内存 [_dislikedKeys] 同步置空（立即生效：后续 loadMore 不再按旧名单过滤）。
 * 已被剔除出列表的条目不回补，等下次刷新自然回归——与 toggleNotInterested 的移除语义一致。
 */
internal fun FeedViewModel.clearDislikedImpl() {
    // R300 同步记账：清空=对当前全部 key 记墓碑（逐 key 删除传播，防对端旧值复活）
    _dislikedKeys.value.forEach { multiSync?.noteRemoved(MultiSync.Domain.DIS, it) }
    _dislikedKeys.value = emptySet()
    maybeAutoPushState()
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { store.clearDisliked() }
            .onFailure { _error.value = "清空不感兴趣名单失败：${it.message}" }
    }
}

/**
 * 记录一次搜索词：trim 后忽略空串；内存态同词去重、置顶并截断，IO 线程落盘。
 * 仅显式提交（IME 搜索键 / 历史 chip 点击）时调用；防抖实时搜索不写入历史。
 */
internal fun FeedViewModel.recordSearchImpl(term: String) {
    val t = term.trim()
    if (t.isEmpty()) return
    _searchHistory.value = (listOf(t) + _searchHistory.value.filterNot { it == t })
        .take(LocalStore.MAX_SEARCH_HISTORY)
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { store.saveSearchHistory(t) }
            .onFailure { _error.value = "搜索历史保存失败：${it.message}" }
    }
}

/** 设置已看过滤开关（立即生效并写入 AppPrefs；当前列表不回溯，从下一次 loadMore 起生效） */
internal fun FeedViewModel.setExcludeWatchedImpl(v: Boolean) {
    _excludeWatched.value = v
    runCatching { prefs.excludeWatched = v }
}

/**
 * 已看过滤是否对指定源生效：开关开 且 该源为聚合场景（Aggregate/WeightedAggregate）。
 * 判定依据 = 对该源做「loadMore 事后硬滤」是否成立：
 *  - R176 起关注流（FollowingFeedSource）**不再入册**：已看判重整体收编进源
 *   （FollowingFairSupply.followingGatePasses——已看底账双轨 + 会话推送位记忆 +
 *    回收间隔闸分层施闸）。旧口径在游标推进之后滤条目=被滤条目从供给永久消失，
 *    且全局关开关的「重温」副作用会把推荐 tab 一起拖下水；
 *  - 刻意按源类型判定而不是 `request.channelId == null && searchQuery == null`——
 *   后者同样命中收藏夹/稍后再看/历史等本地源（request 也是 FeedRequest()），
 *   会把本地源自己滤空；CollectionFeedSource 同为聚合语义但属于用户显式圈定的
 *   合集，仍暂不纳入（想重看合集内旧片时不被挡）。
 * EDGE-HIGH-9：loadMore 传入其捕获的源（而非重读可变的 source），
 * 保证整轮过滤语义与正在拉取的源一致。
 */
internal fun FeedViewModel.excludeWatchedActive(src: FeedSource): Boolean =
    _excludeWatched.value &&
        (src is AggregateFeedSource || src is WeightedAggregateFeedSource)

// ---------------- T0-3 追加区：屏蔽解除入口 ----------------

/**
 * 解除频道屏蔽（设置页"屏蔽的频道"管理卡的"解除"钮用）：
 * 从 blocked_channels 移除并立即持久化；不在表里时幂等空转。
 * 与 [toggleBlockChannel] 的翻转语义不同，本方法是定向移除，
 * 解除后不回补当前 items——等下一次刷新该频道内容自然出现。
 */
internal fun FeedViewModel.unblockChannelImpl(chatId: Long) {
    if (chatId !in _blockedChannels.value) return
    // R300 同步记账：解除屏蔽记墓碑
    multiSync?.noteRemoved(MultiSync.Domain.BLK, chatId.toString())
    maybeAutoPushState()
    // 乐观更新内存（定向移除语义），磁盘真值由串行通道合并后回校
    _blockedChannels.value = _blockedChannels.value - chatId
    viewModelScope.launch(Dispatchers.IO) {
        // R108：统一串行通道内重读磁盘再移除——冷启动空内存覆盖会连带抹掉其余屏蔽项
        ledgerMutex.withLock {
            val disk = runCatching { store.loadBlockedChannels() }.getOrDefault(emptySet())
            val merged = disk - chatId
            runCatching { store.saveBlockedChannels(merged) }
                .onFailure { _error.value = "解除屏蔽保存失败：${it.message}" }
            _blockedChannels.value = merged
        }
    }
}

/** 频道避雷上报的默认原因（[B] 协议载荷，写入侧截断 30 字；屏蔽动作无用户填写的原因，统一报此固定值） */
private const val PLAZA_BLOCK_REPORT_REASON = "用户屏蔽"

// ---------------- 互动动作 ----------------

internal fun FeedViewModel.toggleLikeImpl(item: VideoItem) {
    // R108：点击瞬间定格意图（加/减）——落盘是异步合并，不能以落盘时的内存态反推意图
    val adding = item.key !in _likes.value
    // 乐观更新内存让 UI 立即响应；磁盘真值由串行通道合并后回校
    _likes.value = if (adding) _likes.value + item.key else _likes.value - item.key
    // 互动信号入权重：点赞 +BOOST_LIKE（CES ×1 基准单位），取消点赞对称回退（防反复点刷分）
    addInteractionBoost(item.chatId, if (adding) BOOST_LIKE else -BOOST_LIKE)
    if (adding) {
        interactionStats.recordInteraction("like", item.chatId) // 指标闭环：互动率分子
        noteSessionSignal(item.chatId, SESSION_SIGNAL_STRONG_POS) // P1 #4：点赞计入连续强正
    }
    // 广场上报：点赞才发，取消赞不回写（广场是人数计数语义）
    if (adding) plazaPostLike(item)
    viewModelScope.launch(Dispatchers.IO) {
        // R108：统一串行通道内先重读磁盘账本再按意图合并——冷启动恢复未完成前内存是空的，
        // 拿内存快照全量覆盖 likes.json 会把历史点赞清成 1 条（addSubscription 同款纪律）
        ledgerMutex.withLock {
            val disk = runCatching { store.loadLikes() }.getOrDefault(emptySet())
            val merged = if (adding) disk + item.key else disk - item.key
            runCatching { store.saveLikes(merged) }
                .onFailure { _error.value = "点赞保存失败：${it.message}" }
            _likes.value = merged
        }
    }
}

internal fun FeedViewModel.toggleFavoriteImpl(item: VideoItem) {
    // R108：点击瞬间定格意图（加/减）——落盘是异步合并，不能以落盘时的内存态反推意图
    val adding = item.key !in _favorites.value
    // R300 favTs：新收藏打「加入时刻」戳（多设备同步 per-key LWW 依据；存量老收藏 ts=0=未知）
    val stamped = if (adding) item.copy(favTs = System.currentTimeMillis()) else item
    // R300 同步记账：取消收藏记墓碑（传播删除）；收藏标脏（加入戳已在条目 favTs 上）
    if (adding) multiSync?.markDirty(MultiSync.Domain.FAV) else multiSync?.noteRemoved(MultiSync.Domain.FAV, item.key)
    // 乐观更新内存让 UI 立即响应；磁盘真值由串行通道合并后回校
    if (adding) {
        // 新收藏：完整条目入库并置顶（列表即收藏时间倒序）
        favoriteItems = listOf(stamped) + favoriteItems.filterNot { it.key == item.key }
        _favorites.value = _favorites.value + item.key
    } else {
        // 取消收藏：从列表移除
        favoriteItems = favoriteItems.filterNot { it.key == item.key }
        _favorites.value = _favorites.value - item.key
    }
    // 互动信号入权重：收藏 +BOOST_FAVORITE（CES ×2），取消收藏对称回退
    addInteractionBoost(item.chatId, if (adding) BOOST_FAVORITE else -BOOST_FAVORITE)
    if (adding) {
        interactionStats.recordInteraction("favorite", item.chatId) // 指标闭环：互动率分子
        noteSessionSignal(item.chatId, SESSION_SIGNAL_STRONG_POS) // P1 #4：收藏计入连续强正
    }
    maybeAutoPushState() // R300：四域节流推（总闸/域闸/全域预算闸在链内）
    viewModelScope.launch(Dispatchers.IO) {
        // R108 修复核心（H2 冷启动恢复竞态丢收藏）：统一串行通道内先重读磁盘账本
        // 再按本次意图合并——恢复协程未把磁盘搬进内存前内存是空的，拿内存快照全量
        // 覆盖 favorites.json 会把历史收藏清成 1 条（addSubscription 同款纪律）；
        // 快速连点的多次落盘被同一把锁串行化，后到的写一定读到前次的合并终值（M1 不乱序）
        ledgerMutex.withLock {
            val disk = runCatching { store.loadFavorites() }.getOrDefault(emptyList())
            val merged = if (adding) {
                listOf(stamped) + disk.filterNot { it.key == item.key }
            } else {
                disk.filterNot { it.key == item.key }
            }
            runCatching { store.saveFavorites(merged) }
                .onFailure { _error.value = "收藏保存失败：${it.message}" }
            // 以磁盘合并终值回校内存（恢复协程若刚把老账搬进内存，这里对齐）
            favoriteItems = merged
            _favorites.value = merged.mapTo(LinkedHashSet<String>()) { it.key }
        }
    }
}

/**
 * 批量取消收藏（GAPS3 批量操作，供收藏管理类列表页多选移除用）：
 * 内存列表/集合一次性过滤 + 一次落盘；互动加分对每条命中项对称回退
 * （-BOOST_FAVORITE，与 toggleFavorite 取消分支同口径，防刷分语义一致）。
 * 未命中任何已收藏 key 时直接忽略（不碰磁盘）。
 */
internal fun FeedViewModel.removeFavoritesImpl(keys: Set<String>) {
    if (keys.isEmpty()) return
    val removed = favoriteItems.filter { it.key in keys }
    if (removed.isEmpty()) return // 本来就没有这些收藏
    // 乐观更新内存（同 toggleFavorite 纪律），磁盘真值由串行通道合并后回校
    favoriteItems = favoriteItems.filterNot { it.key in keys }
    _favorites.value = _favorites.value - keys
    removed.forEach { addInteractionBoost(it.chatId, -BOOST_FAVORITE) }
    // R300 同步记账：批量取消收藏逐 key 记墓碑（传播删除）
    keys.forEach { multiSync?.noteRemoved(MultiSync.Domain.FAV, it) }
    maybeAutoPushState()
    viewModelScope.launch(Dispatchers.IO) {
        // R108：统一串行通道内重读磁盘再移除——内存没有的收藏（恢复未完成/他处新加）必须保住
        ledgerMutex.withLock {
            val disk = runCatching { store.loadFavorites() }.getOrDefault(emptyList())
            val merged = disk.filterNot { it.key in keys }
            runCatching { store.saveFavorites(merged) }
                .onFailure { _error.value = "收藏保存失败：${it.message}" }
            favoriteItems = merged
            _favorites.value = merged.mapTo(LinkedHashSet<String>()) { it.key }
        }
    }
}
