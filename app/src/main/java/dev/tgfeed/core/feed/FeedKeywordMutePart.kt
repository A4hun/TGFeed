package dev.tgfeed.core.feed

import androidx.lifecycle.viewModelScope
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

// ---------------- R413 关键词屏蔽 VM 接线区 ----------------
// 判定唯一出处 = KeywordMutePolicy（纯函数）；本文件只管状态/持久化/同步记账/即时剔除。
// 日志纪律：只打计数，绝不打词与标题（§七）。

/** 当前预编译匹配器（词表/开关未变时复用缓存；空词表直出 EMPTY 零开销） */
internal fun FeedViewModel.keywordMatcher(): KeywordMutePolicy.Matcher {
    val words = _mutedKeywords.value
    if (words.isEmpty()) return KeywordMutePolicy.EMPTY
    val chan = _muteMatchChannelTitle.value
    val sig = words to chan
    keywordMatcherCache?.let { (k, m) -> if (k == sig) return m }
    val m = KeywordMutePolicy.compile(words, chan)
    keywordMatcherCache = sig to m
    return m
}

/**
 * 关键词屏蔽对该源是否生效：用户显式圈定的源豁免——单频道（频道主页/查看上下文，
 * 用户主动进某频道看）、收藏、稍后再看、历史、播放队列（用户自己排的清单）。
 * 推荐/聚合/关注流/搜索/合集/tag/时间窗等算法或检索供给一律生效。
 */
internal fun keywordMuteActive(src: FeedSource): Boolean =
    src !is ChannelFeedSource && src !is FavoritesFeedSource && src !is WatchLaterFeedSource &&
        src !is HistoryFeedSource && src !is PlayQueueFeedSource

/**
 * 内容可见性共享谓词（R413 类问题收口）：被屏蔽频道 / 不感兴趣单条 / 关键词屏蔽 三项
 * 现读内存态一次判定。主 Feed pass、追更置顶、关注流快照、搜索结果等现场共用。
 * 永久指纹段（SeenDedupPolicy）各现场仍自带——集合构建成本高，按现场原样预构建。
 */
internal class VisibilityGate(
    private val blocked: Set<Long>,
    private val disliked: Set<String>,
    val keyword: KeywordMutePolicy.Matcher,
) {
    fun passes(it: VideoItem): Boolean =
        it.chatId !in blocked && it.key !in disliked && !keyword.hits(it)
}

internal fun FeedViewModel.visibilityGate(): VisibilityGate =
    VisibilityGate(_blockedChannels.value, _dislikedKeys.value, keywordMatcher())

/** 探针：一次过滤一行纯计数（零词零标题） */
internal fun logKeywordMuteProbe(site: String, matcher: KeywordMutePolicy.Matcher, hits: Int) {
    if (matcher.isEmpty) return
    android.util.Log.i("FeedViewModel", "R413 关键词屏蔽: 词数=${matcher.size} 本页命中=${hits} 现场=${site}")
}

/**
 * 添加屏蔽词：规范化+fold 去重后入表、持久化、同步记账；当前列表 CAS 即时剔除命中条目，
 * 轨道缓存全表作废（R189 口径——冻结快照里的命中条目不许切回还魂）。
 * 返回 null=成功（剔除条数经 [onRemoved] 回调，主线程同步调用）；否则为拒绝原因人话。
 */
internal fun FeedViewModel.addMutedKeywordImpl(raw: String, onRemoved: (Int) -> Unit): String? {
    val w = KeywordMutePolicy.normalize(raw)
        ?: return if (raw.isBlank()) "关键词不能为空" else "关键词最长 ${KeywordMutePolicy.MAX_LEN} 字"
    val cur = _mutedKeywords.value
    if (KeywordMutePolicy.containsEquivalent(cur, w)) return "该关键词已在屏蔽列表中"
    if (cur.size >= KeywordMutePolicy.MAX_WORDS) return "屏蔽词已达上限 ${KeywordMutePolicy.MAX_WORDS} 个"
    _mutedKeywords.value = LinkedHashSet(cur).apply { add(w) }
    multiSync?.noteAdded(MultiSync.Domain.KW, w)
    maybeAutoPushState()
    val matcher = keywordMatcher()
    var removed = 0
    // 当前在看的是豁免源（单频道/收藏等）时不缩当前表；轨道缓存照样作废
    if (keywordMuteActive(source)) {
        _items.update { list ->
            val kept = list.filterNot { matcher.hits(it) }
            removed = list.size - kept.size
            kept
        }
    }
    evictTrackCacheImpl()
    logKeywordMuteProbe("新增即时剔除", matcher, removed)
    onRemoved(removed)
    persistMutedKeywords { disk -> KeywordMutePolicy.sanitize(disk + w) }
    return null
}

/** 移除屏蔽词（定向；不回补已剔除条目，下次刷新自然回归——同 unblockChannel 语义） */
internal fun FeedViewModel.removeMutedKeywordImpl(word: String) {
    if (word !in _mutedKeywords.value) return
    multiSync?.noteRemoved(MultiSync.Domain.KW, word)
    maybeAutoPushState()
    _mutedKeywords.value = _mutedKeywords.value - word
    persistMutedKeywords { disk -> disk - word }
}

/** 清空全部屏蔽词（逐词墓碑传播，防对端旧值复活——clearDisliked 同款） */
internal fun FeedViewModel.clearMutedKeywordsImpl() {
    val cur = _mutedKeywords.value
    if (cur.isEmpty()) return
    cur.forEach { multiSync?.noteRemoved(MultiSync.Domain.KW, it) }
    maybeAutoPushState()
    _mutedKeywords.value = emptySet()
    persistMutedKeywords { disk -> disk - cur }
}

/** 频道名匹配开关：开启时当前列表即时剔除新命中条目 */
internal fun FeedViewModel.setMuteMatchChannelTitleImpl(v: Boolean) {
    _muteMatchChannelTitle.value = v
    runCatching { prefs.muteMatchChannelTitle = v }
    if (v && _mutedKeywords.value.isNotEmpty() && keywordMuteActive(source)) {
        val matcher = keywordMatcher()
        var removed = 0
        _items.update { list ->
            val kept = list.filterNot { matcher.hits(it) }
            removed = list.size - kept.size
            kept
        }
        evictTrackCacheImpl()
        logKeywordMuteProbe("开关即时剔除", matcher, removed)
    }
}

/** R108 串行通道：重读磁盘 → 按意图合并 → 写回 → 回校内存 */
private fun FeedViewModel.persistMutedKeywords(merge: (Set<String>) -> Set<String>) {
    viewModelScope.launch(Dispatchers.IO) {
        ledgerMutex.withLock {
            val disk = runCatching { store.loadMutedKeywords() }.getOrDefault(emptySet())
            val merged = merge(disk)
            runCatching { store.saveMutedKeywords(merged) }
                .onFailure { _error.value = "屏蔽词保存失败：${it.message}" }
            _mutedKeywords.value = merged
        }
    }
}
