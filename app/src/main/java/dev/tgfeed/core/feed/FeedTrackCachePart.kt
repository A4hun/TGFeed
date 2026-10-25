package dev.tgfeed.core.feed

import androidx.lifecycle.viewModelScope
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------- R189 轨道内存状态缓存（媒体 tab / 换列往返——横滑状态保持的切流域） ----------------
//
// 任务书实锤①的切流域对应物：用户「看内容→横滑切媒体 tab / 级联换列→切回」——旧实现
// 切走即 refresh（列表清空重拉），切回看到的不是离开时的内容与位置。本件给两条轨道加
// **会话内内存快照**（R170b 磁盘快照手法的内存版——不落盘、随 VM 生灭、条目不可变
// 即快照，与 R182 源栈条目同一冻结纪律）：
//
//  - **媒体 tab 轨道（R181 L1）**：同一源实例内的 tab 往返，键=(源实例, tab)。
//    复合键的意义：换列往返回槽的是**同一源实例**（列槽持有实例），tab 快照跨换列
//    往返仍有效（单键 tab 表会被对侧同 tab 覆盖——本刀定的复合键，r189_notes G③）。
//  - **换列轨道（R181 L2）**：推荐/关注各一单槽（r189_notes C 节对称化）。
//
// 红线口径（任务书第五条）：
//  - **R176 公平供给/判重**：快照只还原 _items 视图 + 判重键集（snapshotKeys），
//    源实例的供给账本/servedKeys/分层机零触碰；续拉 loadMore 的 known 判重照常生效。
//  - **R182 返回栈**：入栈/清栈/弹栈语义逐字保留（本件只给 showFollowing/showAggregate
//    的「裸切」环节加缓存命中旁路；弹栈恢复与两缓存共用同一恢复核心——机械体单源）。
//  - **惰性失效**：切源不 eager 清表（陈旧复合键永远扑空，容量自限）；全局条件漂移
//    （filter / excludeWatched）在命中判定处作废旧快照；显式 refresh 作废当前轨道
//    快照（双击刷新后往返不得回旧内容）；不感兴趣/永久屏蔽全表作废（屏蔽项不经
//    缓存还魂，R156 纪律）。

/** 媒体 tab 离场快照（同源实例内的 tab 往返用；R189 C2） */
internal data class MediaTabSnapshot(
    /** 离场时的列表（不可变实例即快照——VM 的 _items 更新链一律换新实例） */
    val items: List<VideoItem>,
    /** 离场时的页位（恢复后经 resumeScroll 事件滚回） */
    val index: Int,
    /** 离场时的媒体 tab 专用游标（续拉从这继续——不重扫） */
    val mediaTabCursor: String?,
    /** 离场时的媒体 tab 抽空标记 */
    val mediaTabExhausted: Boolean,
    /** 离场时的判重键集（loadMore known 判重的原料——恢复后续拉不重不漏） */
    val snapshotKeys: Set<String>,
    /** 离场时的筛选（命中判定比对——漂移即作废走 refresh） */
    val filter: FeedFilter,
    /** 离场时的已看过滤开关（同上比对） */
    val excludeWatched: Boolean,
)

/** 换列离场快照（推荐/关注各一单槽；R189 C3——比 R182 条目多游标族，比 tab 快照多源实例） */
internal data class ColumnTrackSnapshot(
    /** 列源实例（自带续拉游标——恢复后向下翻页自然续走） */
    val source: FeedSource,
    val items: List<VideoItem>,
    val index: Int,
    /** 离场时的媒体 tab（恢复连 tab 一起还原——band 与内容自洽） */
    val mediaTypeTab: Int,
    val mediaTabCursor: String?,
    val mediaTabExhausted: Boolean,
    val snapshotKeys: Set<String>,
    val filter: FeedFilter,
    val excludeWatched: Boolean,
)

/** 列轨道归属（core 侧私有口径——与 ui.MediaRailPolicy.columnOf 同一分类，供缓存寻址） */
internal enum class TrackColumnKind { RECOMMEND, FOLLOWING }

/** 源 → 列轨道归属（null=潜进源：往返归 R182 源栈管，列缓存不管） */
internal fun trackColumnKindOf(src: FeedSource?): TrackColumnKind? = when (src) {
    is FollowingFeedSource -> TrackColumnKind.FOLLOWING
    is AggregateFeedSource, is WeightedAggregateFeedSource -> TrackColumnKind.RECOMMEND
    else -> null
}

/** R189 轨道缓存纯策略（单测锚点：命中判定/容量逐出） */
internal object FeedTrackCachePolicy {

    /**
     * tab 快照表容量：理论满额 2列×4tab=8 + 潜进源余量；超出逐出最旧（insertion
     * order = LRU，putCapped 先删后放实现「重写即最新」）。
     */
    const val TAB_SNAPSHOT_CAP = 12

    /** 快照可用性：非空 + 全局条件（筛选/已看开关）未漂移 */
    fun tabSnapshotUsable(snap: MediaTabSnapshot, filter: FeedFilter, excludeWatched: Boolean): Boolean =
        snap.items.isNotEmpty() && snap.filter == filter && snap.excludeWatched == excludeWatched

    fun columnSnapshotUsable(snap: ColumnTrackSnapshot, filter: FeedFilter, excludeWatched: Boolean): Boolean =
        snap.items.isNotEmpty() && snap.filter == filter && snap.excludeWatched == excludeWatched

    /** LRU 写入：重写挪最新端，超容逐最旧 */
    fun <K, V> putCapped(map: LinkedHashMap<K, V>, key: K, value: V, cap: Int) {
        map.remove(key)
        map[key] = value
        while (map.size > cap) {
            val eldest = map.keys.firstOrNull() ?: break
            map.remove(eldest)
        }
    }
}

// ---------------- VM 扩展实现（状态搬运层，决策见 FeedTrackCachePolicy） ----------------

/**
 * 服务态原位恢复核心（R189 抽共享件——弹栈恢复 / 列缓存恢复 / 媒体tab恢复三消费方
 * 同一段机械体，R182 pop 的原机械体逐字收编于此；调用方自理入栈/清栈/标题时刻戳等
 * 各自语义）。恢复内容：源实例（可选）、列表、页位、类型 tab、游标族、判重键集、
 * 加载闸；reset 项（连播豁免/新面孔露脸账/回溯节拍器）随恢复归零——恢复的
 * 是冻结快照，这些会话累积器对它是陈旧值，与 R182 弹栈同口径。
 * R337：原「重温兜底（rewatchRelaxSeen）随恢复归零」行随兜底旗退役一并移除
 * （旗已不存在，见 SeenDedupPolicy 契约）。
 */
internal fun FeedViewModel.applyServedStateSnapshotImpl(
    newSource: FeedSource?,
    items: List<VideoItem>,
    index: Int,
    mediaTypeTabValue: Int,
    mediaTabCursorValue: String?,
    mediaTabExhaustedValue: Boolean,
    snapshotKeysValue: Set<String>,
    filterValue: FeedFilter?,
    title: String?,
) {
    settleWatchEvent(null) // 换列表前结算当前观看计时
    (source as? SubscriptionFeedSource)?.let { src ->
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                // R300：已读水位结账后有推进才标脏推云端（同 FeedSourcePart 口径）
                val before = store.loadSubscriptions().sumOf { it.lastSeenMessageId }
                src.commitSeenProgress()
                if (store.loadSubscriptions().sumOf { it.lastSeenMessageId } != before) {
                    multiSync?.markDirty(MultiSync.Domain.SUBS)
                    maybeAutoPushState()
                }
            }
        }
    }
    loadGen.incrementAndGet() // 作废可能在途的旧源拉取（同 switchSource/refresh 口径）
    val changedSource = newSource != null && newSource !== source
    if (changedSource) {
        // 抄录口径与 switchSourceNoStackImpl 一致：进入新态时刻的标题（调用方已设新
        // 标题；title 非空时以它为准——R182 弹栈的条目标题）
        titleAtLastSwitch = title ?: sourceTitle.value
        suppressSourceResetScroll = true // 切源回顶效应让路（FeedScreen 一次性消费）
        source = newSource!!
        if (newSource is ChannelFeedSource) noteChannelVisited(newSource.channelId)
    } else if (title != null) {
        titleAtLastSwitch = title
    }
    _feedSnapshotActive.value = false
    snapshotKeys = snapshotKeysValue
    mediaTabCursor = mediaTabCursorValue
    mediaTabExhausted = mediaTabExhaustedValue
    dryRoundStreak = 0
    clearAutoAdvanceMemory()
    backfillPages = 0
    synchronized(newFaceLock) { newFaceShownAt.clear() }
    newFaceRosterCache = null
    _error.value = null
    if (filterValue != null) _filter.value = filterValue
    _mediaTypeTab.value = mediaTypeTabValue
    _items.value = items
    _currentIndex.value = index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    _loading.value = false
    // 记忆播放：恢复即就位（列表已还原，无需重启恢复链）
    positionRestored = true
    pendingResumeKey = null
    if (title != null) sourceTitle.value = title
    // 页位回滚：resumeScroll 事件两发（即发多数命中；延时二发兜底「列表重组晚于
    // 收集器唤醒」的时序——R182 弹栈原机械体，见 FeedBackStackPolicy 注释）
    val key = items.getOrNull(_currentIndex.value)?.key
    if (key != null) {
        _resumeScroll.tryEmit(key)
        viewModelScope.launch(Dispatchers.Main) {
            delay(FeedBackStackPolicy.RESTORE_REEMIT_DELAY_MS)
            _resumeScroll.tryEmit(key)
        }
    }
}

/** 媒体 tab 快照落袋：当前（源实例, 当前tab）的离场快照。空列表（加载中）不入表。 */
internal fun FeedViewModel.saveMediaTabSnapshotImpl() {
    val itemsNow = _items.value
    if (itemsNow.isEmpty()) return // 加载中空态不入缓存（R182 空快照同款判定）
    FeedTrackCachePolicy.putCapped(
        mediaTabSnapshots,
        source to _mediaTypeTab.value,
        MediaTabSnapshot(
            items = itemsNow,
            index = _currentIndex.value,
            mediaTabCursor = mediaTabCursor,
            mediaTabExhausted = mediaTabExhausted,
            snapshotKeys = snapshotKeys,
            filter = _filter.value,
            excludeWatched = _excludeWatched.value,
        ),
        FeedTrackCachePolicy.TAB_SNAPSHOT_CAP,
    )
    android.util.Log.i(
        "FeedViewModel",
        "R189 媒体tab快照落袋（${source.javaClass.simpleName}·tab=${_mediaTypeTab.value}，" +
            "${itemsNow.size}条@${_currentIndex.value}）",
    )
}

/**
 * 媒体 tab 快照恢复：命中（复合键在表 + 条件未漂移）→ 原位恢复（内容/位置/游标族/
 * 判重键集），返回 true；未命中或已作废 → false（调用方走 reset 旧路 refresh 重拉）。
 */
internal fun FeedViewModel.restoreMediaTabSnapshotImpl(tab: Int): Boolean {
    val snap = mediaTabSnapshots[source to tab] ?: return false
    if (!FeedTrackCachePolicy.tabSnapshotUsable(snap, _filter.value, _excludeWatched.value)) {
        mediaTabSnapshots.remove(source to tab) // 全局条件漂移：旧快照作废
        return false
    }
    android.util.Log.i(
        "FeedViewModel",
        "R189 媒体tab快照命中（${source.javaClass.simpleName}·tab=$tab，" +
            "${snap.items.size}条@${snap.index}，零网络）",
    )
    applyServedStateSnapshotImpl(
        newSource = null, // 同源切 tab：源实例不动（切源回顶效应天然不触发）
        items = snap.items,
        index = snap.index,
        mediaTypeTabValue = tab,
        mediaTabCursorValue = snap.mediaTabCursor,
        mediaTabExhaustedValue = snap.mediaTabExhausted,
        snapshotKeysValue = snap.snapshotKeys,
        filterValue = null, // 一致性已在命中判定过，不动当前筛选
        title = null,
    )
    return true
}

/**
 * 列快照写入（两个写入点共用本函数）：
 * ① showFollowing/showAggregate 顶部——restore 命中路径不经 switchNoStack，离场列
 *    必须先入槽；② switchSourceNoStackImpl 顶部——从列源跳走去别的源（试吃/深链/
 *    收藏夹……），后续回列（顶栏 chip/级联/底栏回底）即原位恢复。
 * 空列表（加载中）不入槽；潜进源不归列缓存管（R182 源栈管它）。
 */
internal fun FeedViewModel.saveColumnTrackIfDepartingImpl() {
    val itemsNow = _items.value
    if (itemsNow.isEmpty()) return
    val snap = ColumnTrackSnapshot(
        source = source,
        items = itemsNow,
        index = _currentIndex.value,
        mediaTypeTab = _mediaTypeTab.value,
        mediaTabCursor = mediaTabCursor,
        mediaTabExhausted = mediaTabExhausted,
        snapshotKeys = snapshotKeys,
        filter = _filter.value,
        excludeWatched = _excludeWatched.value,
    )
    when (trackColumnKindOf(source)) {
        TrackColumnKind.RECOMMEND -> recommendColumnSnapshot = snap
        TrackColumnKind.FOLLOWING -> followingColumnSnapshot = snap
        null -> return
    }
    android.util.Log.i(
        "FeedViewModel",
        "R189 列快照落袋（${source.javaClass.simpleName}，${itemsNow.size}条@" +
            "${_currentIndex.value}·tab=${_mediaTypeTab.value}）",
    )
}

/**
 * 列快照恢复（showFollowing/showAggregate 的「裸切」旁路）：命中 → 源实例回位 +
 * 内容/位置/tab/游标族原位恢复（连 tab 一起还原——band 与内容自洽），返回 true；
 * 未命中/作废 → false（调用方走原构造+首拉路）。R170b 磁盘快照垫底只在 miss 路
 * 生效（命中路列表非空，垫底让位守卫也会短路——提前绕开一次磁盘读）。
 */
internal fun FeedViewModel.restoreColumnTrackImpl(target: TrackColumnKind): Boolean {
    val snap = when (target) {
        TrackColumnKind.RECOMMEND -> recommendColumnSnapshot
        TrackColumnKind.FOLLOWING -> followingColumnSnapshot
    } ?: return false
    if (!FeedTrackCachePolicy.columnSnapshotUsable(snap, _filter.value, _excludeWatched.value)) {
        when (target) {
            TrackColumnKind.RECOMMEND -> recommendColumnSnapshot = null
            TrackColumnKind.FOLLOWING -> followingColumnSnapshot = null
        }
        return false
    }
    android.util.Log.i(
        "FeedViewModel",
        "R189 列快照命中（${snap.source.javaClass.simpleName}，${snap.items.size}条@" +
            "${snap.index}·tab=${snap.mediaTypeTab}，零网络）",
    )
    applyServedStateSnapshotImpl(
        newSource = snap.source,
        items = snap.items,
        index = snap.index,
        mediaTypeTabValue = snap.mediaTypeTab,
        mediaTabCursorValue = snap.mediaTabCursor,
        mediaTabExhaustedValue = snap.mediaTabExhausted,
        snapshotKeysValue = snap.snapshotKeys,
        filterValue = null, // 一致性已在命中判定过
        title = null, // 调用方（顶栏 tab/轨道级联）已设「关注/推荐」标题
    )
    return true
}

/** 显式 refresh 作废当前轨道快照（当前 tab 条目 + 当前列槽）：刷新后往返不得回旧内容 */
internal fun FeedViewModel.evictCurrentTrackSnapshotsImpl() {
    mediaTabSnapshots.remove(source to _mediaTypeTab.value)
    when (trackColumnKindOf(source)) {
        TrackColumnKind.RECOMMEND -> recommendColumnSnapshot = null
        TrackColumnKind.FOLLOWING -> followingColumnSnapshot = null
        null -> {}
    }
}

/** 全表作废（内容指纹级变更用——R156 不感兴趣/永久屏蔽：屏蔽项不许经缓存还魂） */
internal fun FeedViewModel.evictTrackCacheImpl() {
    mediaTabSnapshots.clear()
    recommendColumnSnapshot = null
    followingColumnSnapshot = null
}
