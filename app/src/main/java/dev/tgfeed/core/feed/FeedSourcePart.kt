package dev.tgfeed.core.feed

import androidx.lifecycle.viewModelScope
import dev.tgfeed.core.td.MockTdGateway
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// ---------------- 相似频道推荐（SimilarChannelsDialog 数据源） ----------------

/**
 * 拉取与 [channelId] 相似的频道（IO 线程）。网关扩展方法不进 TdGateway 接口
 * （项目惯例，同 optimizeStorage/FeedActions），按实现类型分派；
 * 未知实现或失败均回落为空列表，不报错（推荐属锦上添花）。
 */
internal fun FeedViewModel.loadSimilarImpl(channelId: Long) {
    _similarLoading.value = true
    _similarChannels.value = emptyList()
    viewModelScope.launch(Dispatchers.IO) {
        val list = runCatching {
            when (gateway) {
                is TdLibGateway -> gateway.getSimilarChats(channelId)
                is MockTdGateway -> gateway.getSimilarChats(channelId)
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
        _similarChannels.value = list
        _similarLoading.value = false
    }
}

/** 关闭"找相似"弹窗时清空结果与加载态 */
internal fun FeedViewModel.clearSimilarImpl() {
    _similarChannels.value = emptyList()
    _similarLoading.value = false
}

/**
 * R189 用户导航路（媒体条 band 点击 / R181 轨道 StepTab）：切走先把当前（源实例×
 * 当前 tab）离场快照入表，目标 tab 有可用快照（非空+全局条件未漂移）→ **原位恢复**
 * （内容/位置/游标族/判重键集，零网络——任务书「切媒体 tab 再切回内容与位置保留」）；
 * 无快照 → 走 [resetMediaTypeTabImpl] 旧路（refresh 重拉）。旧 tab 的播放由列表换血
 * 自然停播（VideoPage dispose → 池 release，R112 先记账后释放）。
 */
internal fun FeedViewModel.setMediaTypeTabImpl(t: Int) {
    if (t == _mediaTypeTab.value) return
    saveMediaTabSnapshotImpl()
    if (!restoreMediaTabSnapshotImpl(t)) resetMediaTypeTabImpl(t)
}

/**
 * R189 程序复位路（与用户导航路分流）：试吃/热榜/搜索/深链/星图/历史/下载/频道主页
 * 格/广场板等 10 处调用点的 `resetMediaTypeTab(-1)` 是「防过滤吃掉跳转目标」的程序
 * 复位（R109 #7），不是用户导航——**不走快照/恢复**（恢复旧内容会把跳转目标的落列
 * 表时序搅乱：复位点后紧跟 switchSource，恢复的旧列表会被 R182 入栈快照误当成「被
 * 离开态」）。语义=旧行为逐字保留：置值+游标重置+refresh。
 */
internal fun FeedViewModel.resetMediaTypeTabImpl(t: Int) {
    if (t == _mediaTypeTab.value) return
    _mediaTypeTab.value = t
    mediaTabCursor = null // 类型 tab 专用游标重置（服务端过滤拉取从头开始）
    refresh()
}

/**
 * 应用新的筛选条件：与原条件相同则忽略；否则更新并 refresh() 重刷当前源
 * （reset 游标 + 清空列表 + 重新拉第一页，新条件从第一页起生效）。
 * 不持久化：筛选属会话态，重启回到"全部"。
 */
internal fun FeedViewModel.setFilterImpl(f: FeedFilter) {
    if (f == _filter.value) return
    _filter.value = f
    refresh()
}

internal fun FeedViewModel.switchSourceImpl(newSource: FeedSource) {
    // R182 切源入栈（用户原则「跳转触发→返回回上一级」）：先快照被离开态（先于
    // 下方任何复位——离场态原样冻结），再走裸切。例外见 showAggregateImpl（回底清栈）。
    pushSourceBackStackImpl()
    switchSourceNoStackImpl(newSource)
}

/**
 * 裸切源（不入栈）：switchSourceImpl 的原机械体；popSourceBack 的空快照重拉
 * 路径与 showAggregate（回底清栈）共用。标题抄录（titleAtLastSwitch）在本层——
 * 任何进入新源的路径都须记录「进入时设定的标题」，否则 showAggregate/弹栈重拉后
 * 下一跳的入栈快照会抄到陈旧标题。
 */
internal fun FeedViewModel.switchSourceNoStackImpl(newSource: FeedSource) {
    // R189 列快照写入点②：从列源（推荐/关注）跳走去别的源（试吃/深链/收藏夹……）——
    // 离场列状态入单槽，后续回列（顶栏 tab/级联/底栏回底）原位恢复。写入点①在
    // showFollowing/showAggregate 顶部（restore 命中路径不经本函数）。潜进源不归
    // 列缓存管（R182 源栈管它）；空列表（加载中）不入槽。
    saveColumnTrackIfDepartingImpl()
    // 抄录进入新态时的标题（切源时刻现值=FeedScreen 刚设定的新标题；下一跳入栈
    // 时被离开态的标题取本值——口径见 SourceBackStackEntry.title）
    titleAtLastSwitch = sourceTitle.value
    // R165 切源打点：本函数主线程段此前零日志——用户主力机「单次点击 #标签切源即冻结、
    // 点击本身零日志」现场没法鉴别「点击落了没」（主线程已楔死 vs 切源后拉取期死亡）。
    // 此行落地即分叉：日志出现→死亡在切源之后的拉取/native 侧；日志缺失→点击前主线程已楔死。
    android.util.Log.i(
        "FeedViewModel",
        "切源：${source.javaClass.simpleName} → ${newSource.javaClass.simpleName}（loadGen=${loadGen.get()}）",
    )
    settleWatchEvent(null) // 切源前结算当前观看计时（列表即将清空）
    // 追更源离场结账：把"本轮下发且已真看"的频道基线推进落账（IO 协程，不阻塞切源；
    // 源内 refresh 重拉路径由 SubscriptionFeedSource.nextPage 开头自结，无需在此挂钩）
    (source as? SubscriptionFeedSource)?.let { src ->
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                // R300：已读水位（lastSeenMessageId）是同步域游标——结账后有推进才标脏推云端
                val before = store.loadSubscriptions().sumOf { it.lastSeenMessageId }
                src.commitSeenProgress()
                if (store.loadSubscriptions().sumOf { it.lastSeenMessageId } != before) {
                    multiSync?.markDirty(MultiSync.Domain.SUBS)
                    maybeAutoPushState()
                }
            }
        }
    }
    loadGen.incrementAndGet() // 作废可能在途的旧源拉取：其结果/收尾见代次检查，不再生效
    source = newSource
    // R66：刷某频道即记该频道查看时刻（lastVisitTs），追更红点判定随之重估
    if (newSource is ChannelFeedSource) noteChannelVisited(newSource.channelId)
    _feedSnapshotActive.value = false // R52：切源后列表与快照无关，标记归位
    snapshotKeys = emptySet()
    mediaTabCursor = null // 类型 tab 专用游标重置
    mediaTabExhausted = false // 媒体 tab 抽空标记随源作废（新源重新全量连拉）
    dryRoundStreak = 0 // 队列保底链式续拉的连续空轮计数随源清零（R337：唯一停链闸，重温兜底旗已退役）
    clearAutoAdvanceMemory() // 连播"往回翻回看"豁免记忆随源清零
    backfillPages = 0 // 历史回溯注入节拍器随源重置（与列表同生命周期）
    // R98 新面孔露脸账与名册缓存随源清零（与列表同生命周期）
    synchronized(newFaceLock) { newFaceShownAt.clear() }
    newFaceRosterCache = null
    _items.value = emptyList()
    _currentIndex.value = 0 // 换源回到第一条；从分屏/设置返回时靠它恢复位置
    // 记忆播放：切源即放弃重启恢复（旧源的位置不该恢复到新源列表）
    positionRestored = true
    pendingResumeKey = null
    // 放行新源首拉：旧轮可能把 _loading 卡在 true 且其收尾已无权放闸，
    // 不清闸新源 loadMore 会被吞掉 → 永远卡空态（EDGE-HIGH-9）
    _loading.value = false
    loadMore()
}

// ---------------- 内容源快捷切换（供 UI 底栏/抽屉调用） ----------------

/** 刷收藏夹 */
internal fun FeedViewModel.showFavoritesImpl() = switchSource(FavoritesFeedSource(store))

/** 刷稍后再看 */
internal fun FeedViewModel.showWatchLaterImpl() = switchSource(WatchLaterFeedSource(store))

/**
 * 刷观看历史（内存条目 + 磁盘持久化的完整历史条目共同补全）。
 * G4 修复：history.json 改存完整 VideoItem 后，重启内存为空时"已看历史"源依然有内容。
 * R165 H1：内存半边在切源清列表前先捕获（纯内存零 IO）；磁盘半边（loadHistoryItems）
 * 延迟到 HistoryFeedSource.nextPage 在 IO 线程现读——主线程不再直连 store 监视器/读盘。
 */
internal fun FeedViewModel.showHistoryImpl() = switchSource(HistoryFeedSource(store, _items.value))

/**
 * 历史源/本地搜索共用的补全清单：当前内存条目优先（带最新播放地址），
 * 磁盘还原的历史条目补位。调用方须在 IO 语境（nextPage / localSearchInstant 的
 * withContext(IO)）——store 读盘与监视器等待不落主线程（R165 口径）。
 */
internal fun FeedViewModel.historyReplayItems(): List<VideoItem> {
    val persisted = runCatching { store.loadHistoryItems() }.getOrDefault(emptyList())
    if (persisted.isEmpty()) return _items.value
    val memoryKeys = _items.value.mapTo(HashSet()) { it.key }
    return _items.value + persisted.filterNot { it.key in memoryKeys }
}

/** 回到聚合全部频道（加权混排 v2：时间衰减权重 + 互动加分 + 文件级去重 + 同频道打散；原 AggregateFeedSource 类保留可用）
 *
 * R182：本函数=「回推荐流/回底」的显式用户意图（顶栏 HOME 座位重置潜进源/顶栏
 * 推荐 chip/源面板推荐）——**清源栈**再裸切：历史归零，返回语义直接落 R177 顶层
 * （回底后按返回不应再翻回已明确离开的旧源）。
 * R189 C3：清栈后先试推荐列单槽快照（任务书「换列再切回内容与位置保留」）——
 * 命中=源实例回位+内容/页位/tab/游标族原位恢复（零网络）；未命中=原构造+首拉。 */
internal fun FeedViewModel.showAggregateImpl() {
    // R189 列快照写入点①（restore 命中路径不经 switchNoStack——顶部先存离场列）
    saveColumnTrackIfDepartingImpl()
    clearSourceBackStackImpl()
    if (!restoreColumnTrackImpl(TrackColumnKind.RECOMMEND)) {
        switchSourceNoStackImpl(
            WeightedAggregateFeedSource(
                // R66：加分提供者叠加稀缺上浮折算（与初始源同口径）
                gateway, store, { scarcityAdjustedBoost() },
                excludeSeenProvider = { SeenDedupPolicy.hardExcludeOn(_excludeWatched.value) }, // 与初始源同口径：已看硬剔除接开关（R337 判定收口——唯一输入=用户开关，兜底解除路已退役）
            ),
        )
    }
}

/** 刷订阅追更：只看订阅频道里 lastSeen 之后的新视频 */
internal fun FeedViewModel.showSubscriptionsImpl() = switchSource(SubscriptionFeedSource(gateway, store))

/**
 * 刷关注流（R154，顶栏"关注"tab）：只看订阅频道的完整内容流
 *（与追更的"lastSeen 增量提醒"不同——这里是订阅频道的内容主场，
 *  从最新起可翻页深挖历史，不看已读基线）。
 *
 * R170b 快照垫底（用户设计原则：扫描不暴露给用户）：切到「关注」tab 时先把上次
 * 的关注流首页快照（预热任务/真实浏览滚动落盘）摆进列表——秒出可刷内容；网络
 * 首拉经 loadMore 既有去重链在后面接续（追加不替换，播放零打断）。首拉/预热
 * 先到货（列表非空）则快照静默让位；快照条目先过已看底账（R94 口径，整页皆
 * 已看=等同无快照，交给首拉）。冷启动 R52 快照同款手法移植到关注 tab。
 * R189 C3：R182 入栈语义逐字保留（push 照旧），「裸切」环节先试关注列单槽快照
 * （任务书「换列再切回内容与位置保留」对称方向）——命中=源实例回位+内容/页位/
 * tab/游标族原位恢复（零网络）；未命中=原构造+首拉+R170b 磁盘垫底。
 */
internal fun FeedViewModel.showFollowingImpl() {
    // R189 列快照写入点①（restore 命中路径不经 switchNoStack——顶部先存离场列）
    saveColumnTrackIfDepartingImpl()
    pushSourceBackStackImpl()
    if (restoreColumnTrackImpl(TrackColumnKind.FOLLOWING)) return
    switchSourceNoStackImpl(
        FollowingFeedSource(
            gateway, store,
            // R176：已看过滤收编进源（分层供给的判重闸原料）——接全局开关，
            // 与推荐流同口径；源内按层施闸（FRESH 严/RECYCLE 宽+间隔），
            // loadMore 侧不再对本源事后硬滤（拉了又滤=丢条目）
            excludeSeenProvider = { _excludeWatched.value },
        ),
    )
    // 快照垫底走 IO 协程（switchSource 在主线程，读盘不落主线程）；与首拉赛跑
    // 由「列表非空即让位」守卫裁决（毫秒级读盘几乎必胜网络首拉）
    viewModelScope.launch(Dispatchers.IO) {
        val snap = runCatching { store.loadFollowingSnapshot() }.getOrNull() ?: return@launch
        val wantMode = if (gateway is MockTdGateway) "demo" else "real"
        if (snap.mode != wantMode) return@launch
        // 首拉先到货：快照让位，照常追新（R52 同款守卫）
        if (_items.value.isNotEmpty()) return@launch
        val (seenK, seenU) = runCatching { store.loadSeenIdentities() }
            .getOrDefault(emptySet<String>() to emptySet())
        val snapFresh = snap.items.filter { !SeenDedupPolicy.isSeen(it, seenK, seenU) }
        if (snapFresh.isEmpty()) return@launch
        _items.value = snapFresh
        snapshotKeys = snapFresh.mapTo(HashSet()) { it.key }
        _feedSnapshotActive.value = true
        // R324 离线降级：关注流快照时点入流（与主快照共用 _snapshotSavedAtMs——
        // banner 时点口径=max(快照时点, 本会话到货戳)，两个垫底路径都供数）
        _snapshotSavedAtMs.value = snap.savedAtMs
        android.util.Log.i(
            "FeedViewModel",
            "R170b 关注流快照垫底（${snapFresh.size} 条，网络刷新接续中）",
        )
    }
}

/**
 * R170b 关注流首页快照预热任务真身（WarmupCoordinator 经 init 提交，错峰窗执行；
 * block 本身是挂起函数，nextPage 直接调用无需阻塞）：
 * 用**独立实例**跑一轮关注流首页（并发拉取，cursor=null 从各订阅频道最新开取），
 * 结果过已看过滤后落盘 following_snapshot.json——用户下次切「关注」tab 秒出内容。
 * 刻意不复用 VM 的 source（那是用户当前源，职责是前台浏览；预热实例一次性用后即弃）。
 * @return true=成功（含"无订阅/全到底/全已看"这类非失败终态）；false=可重试失败
 */
internal suspend fun FeedViewModel.warmFollowingSnapshot(): Boolean {
    val subs = runCatching { store.loadSubscriptions() }.getOrDefault(emptyList())
    if (subs.isEmpty()) return true // 无订阅：预热无事可做（空态引导是正路），不算失败
    val src = FollowingFeedSource(gateway, store)
    val page = runCatching { src.nextPage(FOLLOWING_WARM_PAGE_LIMIT) }.getOrNull() ?: return false
    if (page.items.isEmpty()) return true // 全到底/空轮：非失败
    val (seenK, seenU) = runCatching { store.loadSeenIdentities() }
        .getOrDefault(emptySet<String>() to emptySet())
    val fresh = page.items.filter { !SeenDedupPolicy.isSeen(it, seenK, seenU) }
    if (fresh.isEmpty()) return true // 全已看：快照无意义，等真实浏览轮落盘
    runCatching {
        store.saveFollowingSnapshot(fresh, if (gateway is MockTdGateway) "demo" else "real")
    }.onFailure { return false }
    android.util.Log.i(
        "FeedViewModel",
        "R170b 关注流快照预热完成（${fresh.size} 条落盘）",
    )
    return true
}

/** 一键刷合集：切到该合集限定的聚合源（合集内各频道轮流取页混排） */
internal fun FeedViewModel.showCollectionImpl(c: ChannelCollection) = switchSource(CollectionFeedSource(gateway, c))

/** 新建合集（同名覆盖，名称为主键），立即持久化；空名或空频道列表直接忽略 */
internal fun FeedViewModel.createCollectionImpl(name: String, channelIds: List<Long>) {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || channelIds.isEmpty()) return
    val entry = ChannelCollection(trimmed, channelIds.distinct())
    val newList = _collections.value.filterNot { it.name == trimmed } + entry
    _collections.value = newList
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { store.saveCollections(newList) }
            .onFailure { _error.value = "合集保存失败：${it.message}" }
    }
}

/** 按名称删除合集，立即持久化；正在刷该合集时当前列表不受影响（下次切源自然失效） */
internal fun FeedViewModel.deleteCollectionImpl(name: String) {
    val newList = _collections.value.filterNot { it.name == name }
    if (newList.size == _collections.value.size) return // 本来就没有
    _collections.value = newList
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { store.saveCollections(newList) }
            .onFailure { _error.value = "合集删除失败：${it.message}" }
    }
}
