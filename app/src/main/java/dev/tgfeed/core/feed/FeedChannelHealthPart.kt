package dev.tgfeed.core.feed

import androidx.lifecycle.viewModelScope
import dev.tgfeed.core.td.ChannelBrief
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ---------------- 频道体检（R132：僵尸/变质频道一键断舍离；R318：聚合降档） ----------------

/**
 * 触发一次体检重算（IO 协程走查聚合）；体检页 onEnter 时调用。
 * R318 起走查分片逐片回报进度（[FeedViewModel.channelHealthProgress]），可
 * [FeedViewModel.cancelChannelHealth] 取消（取消=保旧报告直通）。重算期间旧报告
 * 继续可见（低频入口不整页转圈，R132 保留设计），refreshing 位明示当前数据是
 * 上一轮的（防旧原因被当成现行结论）。
 */
internal fun FeedViewModel.refreshChannelHealthImpl() {
    channelHealthJob?.cancel() // 重进页/手动重触发：旧轮作废（保旧报告，零叠加走查）
    _channelHealthRefreshing.value = true
    _channelHealthProgress.value = 0
    val t0 = System.currentTimeMillis()
    channelHealthJob = viewModelScope.launch(Dispatchers.IO) {
        try {
            val report = runCatching {
                buildChannelHealthImpl(
                    onProgress = { _channelHealthProgress.value = it },
                    isCancelled = { !isActive },
                )
            }.getOrNull()
            // null=走查被取消：保旧报告直通（不覆盖、不报错）
            if (report != null) {
                _channelHealth.value = report
                android.util.Log.d(
                    "FeedViewModel",
                    "R318 体检走查完成：频道 ${report.entries.size} 个，耗时 ${System.currentTimeMillis() - t0} ms",
                )
            } else {
                android.util.Log.d(
                    "FeedViewModel",
                    "R318 体检走查取消：进度 ${_channelHealthProgress.value ?: 0}%（保旧报告）",
                )
            }
        } finally {
            _channelHealthRefreshing.value = false
            _channelHealthProgress.value = null
        }
    }
}

/** R318：取消在途体检走查（协程取消+片间取消哨兵=亚秒级响应；进度位归闲） */
internal fun FeedViewModel.cancelChannelHealthImpl() {
    channelHealthJob?.cancel()
}

/**
 * 全本地聚合频道体检报告（同步阻塞，**必须在 IO 线程调用**，UI 请走 [refreshChannelHealth]）。
 *
 * 数据源（三处全部现查，零网络、零缓存依赖）：
 *  - 深度索引库（deepindex/search.db 只读连接）：全频道统计快照 + 近 30 天窗口发帖数
 *    （R318 起两证同流——[aggregateChatStatsChunked] date 索引覆盖分片走查一次产出，
 *    5.8M 行真库旧形状分钟级→走查秒级；旧「两条全表 GROUP BY 回表」形状保留为兜底链，
 *    详见 IndexStatsWalk.kt）；
 *  - watch_events（LocalStore 内存缓存）：每频道累计观看秒数、30 天半衰折算的近期有效值、
 *    最近一次观看时刻（互动衰减口径）；
 *  - 广告频道黑名单（AdChannelClassifier.isBlockedRuntime 静态快照）。
 *
 * 覆盖集合取并集（订阅 ∪ 已加入频道 ∪ 索引统计 ∪ 观看记录），与频道卡元数据同口径；
 * 标题拼表顺序：订阅 → 已加入频道 → 内存条目（先填不覆盖）。
 * 排序：分层差者优先（变质→僵尸→低频→健康）→ 健康分升序 → 标题，UI 直接渲染。
 *
 * @param onProgress 走查进度（0..100，整数百分比、片粒度回报）
 * @param isCancelled 取消检查（片间命中→返回 null=取消；取消≠失败——失败产空证据面报告）
 * @return 报告；null=走查被取消（调用方保旧值直通）
 */
internal fun FeedViewModel.buildChannelHealthImpl(
    onProgress: (percent: Int) -> Unit = {},
    isCancelled: () -> Boolean = { false },
): ChannelHealthReport? {
    val nowMs = System.currentTimeMillis()
    val nowSec = nowMs / 1000L

    // 1) 索引统计快照 + 近 30 天窗口发帖数：一次分片走查产出（date 索引覆盖；
    //    索引缺席/中途失败→旧形状兜底；打不开库等硬失败→空 stats+null 窗口=
    //    无证据不计罚，与旧 Q1 失败/Q2 失败口径一致）。null=取消（保旧报告）
    val agg = try {
        aggregateChatStatsChunked(
            dbFile = tierDbFile(),
            todayStartSec = localMidnightSec(),
            windowSinceSec = nowSec - RECENT_WINDOW_DAYS * 24 * 3600L,
            onProgress = onProgress,
            isCancelled = isCancelled,
        )
    } catch (e: Throwable) {
        ChatStatsAggregate(emptyMap(), null)
    } ?: return null
    val stats = agg.stats
    // null = 窗口查询失败（无证据不臆测），空表 = 查询成功但窗口内零发帖（同旧口径）
    val recent30d = agg.recentCounts

    // 2) 观看互动聚合：每频道 累计秒数 / 30 天半衰近期有效值 / 最近观看时刻
    val events = runCatching { store.loadWatchEvents() }.getOrDefault(emptyList())
    var totalWatch = HashMap<Long, Long>()
    var decayedWatch = HashMap<Long, Double>()
    var lastWatchMs = HashMap<Long, Long>()
    for (e in events) {
        if (e.chatId == 0L) continue
        totalWatch[e.chatId] = (totalWatch[e.chatId] ?: 0L) + e.watchSec.coerceAtLeast(0)
        // 30 天半衰：越近的观看权重越接近 1，一个月前的只剩一半（"看过的还看吗"）
        val ageDays = ((nowMs - e.ts).coerceAtLeast(0L)) / 86_400_000.0
        decayedWatch[e.chatId] = (decayedWatch[e.chatId] ?: 0.0) +
            e.watchSec.coerceAtLeast(0) * Math.pow(0.5, ageDays / 30.0)
        if (e.ts > (lastWatchMs[e.chatId] ?: 0L)) lastWatchMs[e.chatId] = e.ts
    }

    // 3) 身份并集 + 标题拼表（订阅标题优先：体检主对象是订阅频道，标题必须最可靠）
    val titles = HashMap<Long, String>()
    fun putTitle(id: Long, t: String?) {
        if (!t.isNullOrBlank()) titles.putIfAbsent(id, t)
    }
    val subs = runCatching { store.loadSubscriptions() }.getOrDefault(emptyList())
    subs.forEach { putTitle(it.chatId, it.title) }
    _channels.value.forEach { putTitle(it.id, it.title) }
    _items.value.forEach { putTitle(it.chatId, it.channelTitle) }
    favoriteItems.forEach { putTitle(it.chatId, it.channelTitle) }
    watchLaterItems.forEach { putTitle(it.chatId, it.channelTitle) }

    val subIds = HashSet<Long>(subs.size)
    subs.forEach { subIds.add(it.chatId) }
    // 已加入频道的用户名（广告黑名单 u<name> 身份双查用；拿不到就只查 chatId）
    val usernames = HashMap<Long, String>()
    _channels.value.forEach { if (!it.username.isNullOrBlank()) usernames[it.id] = it.username }

    val ids = HashSet<Long>()
    ids.addAll(subIds)
    _channels.value.forEach { ids.add(it.id) }
    ids.addAll(stats.keys)
    ids.addAll(lastWatchMs.keys)

    // 4) 逐频道打分（纯函数见 ChannelHealth.kt）。
    //    近 30 天发帖数口径：窗口查询成功且索引爬过该频道 = 同库实证（不在窗口表 = 0 条）；
    //    其余（查询失败/索引未爬）一律 null = 无证据不计罚。
    val entries = ArrayList<ChannelHealthEntry>(ids.size)
    for (id in ids) {
        val st = stats[id]
        val lastWatchDays = lastWatchMs[id]?.let { (nowMs - it) / 86_400_000L }
        val total = totalWatch[id] ?: 0L
        val decayed = decayedWatch[id] ?: 0.0
        val posts30d = if (recent30d != null && st != null) recent30d[id] ?: 0 else null
        entries += buildChannelHealthEntry(
            chatId = id,
            title = titles[id] ?: "未知频道",
            subscribed = id in subIds,
            lastPostDays = st?.let { (nowSec - it.maxDateSec).coerceAtLeast(0L) / 86_400L },
            posts30d = posts30d,
            postsPerWeek = st?.let { postsPerWeek(it) },
            adBlocked = AdChannelClassifier.isBlockedRuntime(id, usernames[id]),
            lastWatchDays = lastWatchDays,
            totalWatchSec = total,
            decayedWatchSec = decayed.toLong(),
        )
    }
    entries.sortWith(
        compareBy({ it.tier }, { it.score }, { it.title }),
    )
    return ChannelHealthReport(generatedAtMs = nowMs, entries = entries)
}

// ---------------- 体检页配套：批量订阅增删（单次读改写，防循环调用竞态） ----------------

/**
 * 批量取关（体检页"一键断舍离"用）：一次读改写整张订阅表。
 * 不逐个循环调 removeSubscription——每个调用各起一个 IO 协程独立"读-合并-写"，
 * 并发下会互相丢更新；本实现与 [removeSubscriptionImpl] 同一条订阅移除链
 * （store.saveSubscriptions 落盘 + _subscriptions 内存态更新），只是合并成单事务。
 */
internal fun FeedViewModel.removeSubscriptionsBatchImpl(chatIds: Set<Long>) {
    if (chatIds.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
        val current = runCatching { store.loadSubscriptions() }.getOrDefault(emptyList())
        val newList = current.filterNot { it.chatId in chatIds }
        if (newList.size == current.size) return@launch // 本来就没订阅
        runCatching { store.saveSubscriptions(newList) }
            .onFailure { _error.value = "订阅保存失败：${it.message}" }
        _subscriptions.value = newList.map { ChannelBrief(it.chatId, it.title, null) }
        // R300 同步记账：批量取关逐频道记墓碑
        chatIds.forEach { multiSync?.noteRemoved(MultiSync.Domain.SUBS, it.toString()) }
        maybeAutoPushState()
    }
}

/**
 * 批量重订（体检页取关后的"撤销"用）：一次读改写整张订阅表，
 * 与 [addSubscriptionImpl] 同链（新订阅 lastSeen=0，首次刷追更只取最新一页）。
 */
internal fun FeedViewModel.addSubscriptionsBatchImpl(briefs: List<ChannelBrief>) {
    if (briefs.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
        val current = runCatching { store.loadSubscriptions() }.getOrDefault(emptyList())
        val known = current.mapTo(HashSet()) { it.chatId }
        val adds = briefs.filter { it.id !in known && it.id != 0L }
        if (adds.isEmpty()) return@launch
        // 重订恢复原订阅标题优先，条目里带回来的新标题兜底
        val oldTitles = current.associateBy({ it.chatId }, { it.title })
        val newList = current + adds.map {
            dev.tgfeed.core.store.Subscription(it.id, oldTitles[it.id] ?: it.title, 0L)
        }
        runCatching { store.saveSubscriptions(newList) }
            .onFailure { _error.value = "订阅保存失败：${it.message}" }
        _subscriptions.value = newList.map { ChannelBrief(it.chatId, it.title, null) }
        // R300 同步记账：批量重订逐频道记加入戳
        adds.forEach { multiSync?.noteAdded(MultiSync.Domain.SUBS, it.id.toString()) }
        maybeAutoPushState()
    }
}

/** 近期窗口长度（天）：体检"近期发帖频率"的观察窗（走查窗口口径与旧 Q2 同界） */
internal const val RECENT_WINDOW_DAYS = 30L
