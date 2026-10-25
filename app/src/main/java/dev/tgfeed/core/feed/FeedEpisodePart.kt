package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem

// ---------------- R142 追剧模式：VideoPage 播完的剧集链跳集（识别纯函数见 EpisodeChain.kt） ----------------

/**
 * 剧集链连播跳集：当前条目在链上有下一集时跳过去（同频道），返回 true；
 * 不在链上/链尾/目标已不在当前列表/目标本会话已自动播过（防环）返回 false
 * （调用方回落 Feed 顺序连播）。
 *
 * 三个落点动作（与 FeedScreen 顺序连播同一套机制，零 UI 侧改动）：
 *  1) [noteAutoEjectedImpl] 记账"被弹走的条＋计划落点"——用户手动翻回时转回看豁免
 *     （onPage 落定消费，与本文件无耦合）；
 *  2) [_currentIndex] 先定位索引（与 jumpToAlbumSibling/tryRestorePosition 同款）；
 *  3) [_resumeScroll] 发滚动事件——FeedScreen 既有收集器 scrollToPage 到目标页，
 *     落定后 onPage 正常结算观看/历史/预载。
 *
 * 刻意只在 VideoPage 的 ENDED 链调用（VideoPlayerListener）：图文/音频卡各有一条
 * 自己的翻页链（FeedScreen 侧，本轮冻结），不在此越界接线。
 */
internal fun FeedViewModel.advanceEpisodeChainImpl(item: VideoItem): Boolean {
    val items = _items.value
    val hop = episodeHop(items, item.key) ?: return false
    // 防环守卫：跳集目标已在本会话被自动播过（连播记账命中）→ 不回头重播，
    // 回落 Feed 顺序连播——正叙发布的剧在 Feed 里是逆序排的（第 12 集在列表
    // 最前），链尾的顺序翻页落点正是链内上一集：不挡会形成
    // "链尾→顺序翻页落回链成员→跳回链尾"的环（12→11→12→11…原地打转）。
    // 手动翻回过的条目会被回看豁免链从记账集移出（翻回消费豁免），不受本守卫影响。
    val targetAlreadyPlayed = synchronized(autoAdvanceLock) { hop.to.key in autoEjectedKeys }
    if (targetAlreadyPlayed) return false
    val targetIdx = items.indexOfFirst { it.key == hop.to.key }
    if (targetIdx < 0) return false
    noteAutoEjectedImpl(item.key, hop.to.key)
    _currentIndex.value = targetIdx
    _resumeScroll.tryEmit(hop.to.key)
    android.util.Log.i(
        "TGFlow",
        "剧集连播：第 ${hop.fromLabel}→${hop.toLabel} 集 " +
            "…${item.key.takeLast(12)} → …${hop.to.key.takeLast(12)}" +
            "（${item.channelTitle}，链 ${hop.chainSize} 集）",
    )
    return true
}
