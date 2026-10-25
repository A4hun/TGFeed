package dev.tgfeed.core.feed

import androidx.lifecycle.viewModelScope
import dev.tgfeed.core.prefs.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 重算智能房间（IO 协程）：取最近 7 天 watch_events 做共现聚类，结果写回 [smartRooms] 缓存。
 * 合集弹窗打开时调用即可拿到最新房间；计算失败保留旧结果，不打扰当前 Feed。
 */
internal fun FeedViewModel.refreshSmartRoomsImpl() {
    viewModelScope.launch(Dispatchers.IO) {
        val rooms = runCatching { smartRoomsEngine.buildSmartRooms() }
            .getOrElse { return@launch } // 失败保留旧缓存
        _smartRooms.value = rooms
    }
}

/** 一键刷智能房间：房间即合集（ChannelCollection），直接复用合集聚合源 */
internal fun FeedViewModel.showSmartRoomImpl(room: ChannelCollection) = switchSource(CollectionFeedSource(gateway, room))

// ---------------- 盲盒模式（随机频道 · 随机位置开刷） ----------------

/**
 * 盲盒开刷：v3 起接算法池——优先从发现候选池（DiscoveryRanker 排名加权）抽，
 * 池空回落口味画像加权，最后才均匀随机；切成该频道源之前
 * 先随机跳过前 N 条（N = Random.nextInt(0, [BLIND_BOX_SKIP_BOUND])），
 * 让用户从频道中段随机位置开刷，而不是永远从最新一条开始。
 *
 * 实现要点：
 *  - 频道清单现拉（listChannels 走网关，IO 线程）；被屏蔽频道不参与抽取；
 *  - 跳过靠"多拉几页并丢弃"：ChannelFeedSource 的游标随 nextPage 前进，
 *    丢弃的条目不进 _items（跳页发生在 switchSource 之前，结果直接弃掉）；
 *  - 频道过短（翻到底都不够 N 条）就停在自然位置，不报错；
 *  - 整页空但游标仍在时最多连翻 [BLIND_BOX_MAX_ROUNDS] 页，防极端条件下死循环；
 *  - 切源回主线程执行，与 UI 侧其他 switchSource 调用保持同一线程语义。
 */
internal fun FeedViewModel.openBlindBoxImpl() {
    viewModelScope.launch(Dispatchers.IO) {
        // v3 接线：盲盒不再均匀瞎抽——优先从发现候选池抽（DiscoveryRanker 排过序、
        // 已硬过滤已加入/已屏蔽，名次加权：越靠前越容易被抽中但不钉榜首）；
        // 池空（没开过发现页）回落口味画像加权抽已加入频道；画像也空才均匀随机。
        val discovery = _discoveryCandidates.value.filter { it.id !in _blockedChannels.value }
        val pickedId: Long? = when {
            discovery.isNotEmpty() -> {
                val total = discovery.indices.sumOf { 1.0 / (it + 1) }
                var roll = kotlin.random.Random.nextDouble() * total
                var hit: Long? = null
                for ((i, c) in discovery.withIndex()) {
                    roll -= 1.0 / (i + 1)
                    if (roll <= 0.0) { hit = c.id; break }
                }
                hit ?: discovery.first().id
            }
            else -> {
                val channels = runCatching { gateway.listChannels() }.getOrDefault(emptyList())
                    .filter { it.id !in _blockedChannels.value }
                if (channels.isEmpty()) {
                    _error.value = "盲盒开不了：没有可刷的频道"
                    return@launch
                }
                val weights = p2ChannelWeights()
                val total = channels.sumOf { (weights[it.id] ?: EXPLORE_WEIGHT).coerceAtLeast(0.01) }
                var roll = kotlin.random.Random.nextDouble() * total
                var hit: Long? = null
                for (c in channels) {
                    roll -= (weights[c.id] ?: EXPLORE_WEIGHT).coerceAtLeast(0.01)
                    if (roll <= 0.0) { hit = c.id; break }
                }
                hit ?: channels.first().id
            }
        }
        if (pickedId == null) {
            _error.value = "盲盒开不了：没有可刷的频道"
            return@launch
        }
        // R183 自愈线索：盲盒优先抽发现候选池——正是深索引 chatId-only 候选的聚集地，
        // 带 brief 的 title/username 才能在 Chat not found 时解析建会话开刷
        val pickedBrief = discovery.firstOrNull { it.id == pickedId }
        val blindSource = ChannelFeedSource(
            gateway, pickedId,
            openTitle = pickedBrief?.title, openUsername = pickedBrief?.username,
        )

        // 随机跳过前 N 条：多拉页丢弃（每页 ≤20 条，N<20 时通常一页就够）
        var remaining = kotlin.random.Random.nextInt(0, BLIND_BOX_SKIP_BOUND)
        var rounds = 0
        while (remaining > 0 && rounds < BLIND_BOX_MAX_ROUNDS) {
            rounds++
            val page = runCatching { blindSource.nextPage() }.getOrNull() ?: break
            if (page.items.isEmpty() && page.nextCursor == null) break // 频道到底
            remaining -= page.items.size
        }

        // 回主线程切源：结算当前观看计时、清空列表、从盲盒位置起刷
        launch(Dispatchers.Main) { switchSource(blindSource) }
    }
}

// ---------------- 关键词订阅 ----------------

/**
 * 刷关键词订阅：读取 AppPrefs 的逗号分隔词表（兼容中文逗号），切到
 * [KeywordSubscriptionSource]——每个词走一次网关搜索（SearchMessages），
 * 去重合并、按时间倒序。词表为空也会切过去（空态页），语义对应设置页
 * "订阅后相关新内容进追更源"。
 */
internal fun FeedViewModel.showKeywordFeedImpl() {
    val keywords = runCatching { prefs.keywordSubscriptions }.getOrDefault("")
        .split(',', '，')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    switchSource(KeywordSubscriptionSource(gateway, keywords))
}

// ---------------- 盲盒模式常量（FeedViewModel 盲盒段配套，文件级私有） ----------------

/** 盲盒随机跳过条数上界（开区间）：N = Random.nextInt(0, BLIND_BOX_SKIP_BOUND)，即 0..19 条 */
private const val BLIND_BOX_SKIP_BOUND = 20

/** 盲盒跳页兜底：目标位置未到但连翻页数达上限即停（防整页滤空/网关异常时死循环） */
private const val BLIND_BOX_MAX_ROUNDS = 5
