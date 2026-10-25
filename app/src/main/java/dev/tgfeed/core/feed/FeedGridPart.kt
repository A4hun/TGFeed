package dev.tgfeed.core.feed

import androidx.lifecycle.viewModelScope
import dev.tgfeed.core.prefs.AppPrefs
import dev.tgfeed.core.store.LocalStore
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// ---------------- 分屏会话托管（"分屏每次打开都是同一批"修复） ----------------
// 背景：分屏自持源/类型 tab 直拉游标本写在 GridScreen 的 remember 里，每次进分屏
// 都新建（cursor=null 从头拉第一页）；叠加画布快照恢复（上次的内容原样复活）与
// "分屏播放不上报 vm.onPage"（seen_ids.log 从不记分屏看过的，已看剔除零效力），
// 三件套叠加 = "每次打开分屏都是那些视频"。
// 修复口径：分屏的拉取状态提升到本 VM（activity 作用域，与 GridScreen 共享同一实例）——
// 游标跨"进出分屏"存活，再进分屏接着上次位置拉新；分屏已展示内容记会话内存双轨
// （防同屏重供；进程重启清零，重启后的首两页与快照重合部分由 GridScreen 侧 known
// 去重 + 补试翻页吸收）。
// R94 补记：会话双轨只管"已展示"（防同屏重供）；格内"实际播放 ≥2s"的条目经
// [markGridWatched] 落 seen_ids.log 持久底账（量小：需真在播，远小于展示量）——
// 修"分屏看过的重启后又被推"。
// R150 补记（B：分屏"显示即看过"）：会话双轨之外，画布可见格的当前内容经
// [markGridDisplayedSeen] 同步落 seen_ids.log 持久底账（显示即记、无播放阈值）——
// 用户口径：分屏小格看了一眼没点 = 看过不感兴趣，退分屏/重启后主 Feed 也不再推；
// 旧版"显示不落盘"正是主 Feed 全局刷新后重复推荐的最大漏账面（主侧过滤只读盘）。

/**
 * 取分屏自持源（无则建）。[excludeSeenProvider] 闭包读设置开关（AppPrefs 实时读盘，
 * 跨组合存活安全）；extraSeenProvider 固定接本 VM 的分屏会话已看双轨。
 */
internal fun FeedViewModel.gridFeedSourceImpl(
    store: LocalStore,
    excludeSeenProvider: () -> Boolean,
): WeightedAggregateFeedSource {
    gridSourceRef?.let { return it }
    return WeightedAggregateFeedSource(
        gateway, store,
        excludeSeenProvider = excludeSeenProvider,
        extraSeenProvider = { gridSeenIdentities() },
    ).also { gridSourceRef = it }
}

/** 登记一批分屏已展示条目（换片链换上的新片/轮播换掉的旧片都经此入册） */
internal fun FeedViewModel.noteGridDisplayedImpl(items: List<VideoItem>) {
    if (items.isEmpty()) return
    gridSeenKeys = gridSeenKeys + items.map { it.key }
    gridSeenUids = gridSeenUids +
        items.mapNotNull { it.fileUniqueId.takeIf { u -> u.isNotEmpty() } }
}

/** 读分屏会话已看双轨（gridLoadMore 直拉路径过滤与源内 extraSeenProvider 共用） */
internal fun FeedViewModel.gridSeenIdentitiesImpl(): Pair<Set<String>, Set<String>> = gridSeenKeys to gridSeenUids

// ---------------- R94 已看底账补登（修"已看过的又被推"的三条漏记路径） ----------------

/**
 * 分屏实际观看落账（GridScreen 口径：格内 isPlaying 真实播放累计 ≥2s 才上报——
 * 只记"真播过"，自动轮播渲染即换的不算）。与"分屏播放不上报 vm.onPage"的解耦
 * 口径不变：只登记已看身份（seen_ids.log 双轨底账），不写观看历史/不动 currentIndex。
 * 修"分屏看过的内容进程重启后又被推"（此前只记会话内存双轨，重启清零）。
 */
internal fun FeedViewModel.markGridWatchedImpl(item: VideoItem) {
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { store.markSeen(item) }
    }
}

/**
 * R150 分屏"显示即看过"落账（B，用户 emphatic 需求：分屏画布上显示过但没点播
 * 的视频 = 用户看见了不感兴趣 = 不该再被推荐）：
 *  - 语义：格子可见即记（无播放阈值——分屏小格本来就看了一眼），与
 *    [markGridWatchedImpl]（格内真实播放 ≥2s 才落账）的"真播过"口径互补，
 *    与用户主动点播（主 Feed onPage → appendHistory）的正常已看记账区分；
 *  - 实现：会话内存双轨（[noteGridDisplayedImpl]，防同屏重供）+ seen_ids.log
 *    持久底账批量落盘（VM 的 store 单实例，同 key 幂等零写盘）——退分屏/重启后
 *    主 Feed 与分屏的已看过滤都读这本账，"画布上看了一眼没点它"的内容不再回流；
 *  - 量级安全：轮播 20s/格 + 换片链事件驱动（500ms 轮询指纹比对去重），典型
 *    每分钟个位数新条目，远低于 seen_ids.log 100k 容量的摊销水位；
 *    旧版"自动轮播量太大会冲垮底账"的顾虑在批量幂等写下不成立；
 *  - 主侧已看过滤随之收口：主 Feed 的 pass 谓词此前只读磁盘底账，分屏显示
 *    （含快照恢复、"换一拨"、轮播换上的格子）全不落账——这正是用户实测
 *    "全局刷新后推荐里还是有重复的已看视频"的最大漏账面。
 */
internal fun FeedViewModel.markGridDisplayedSeenImpl(items: List<VideoItem>) {
    if (items.isEmpty()) return
    // 会话双轨照旧登记（GridScreen 自持源/直拉翻页的防重供口径不变）
    noteGridDisplayedImpl(items)
    // 持久底账批量落盘（IO 线程；store.markSeen 同 key 幂等，重复显示零写盘）
    viewModelScope.launch(Dispatchers.IO) {
        items.forEach { runCatching { store.markSeen(it) } }
    }
}

/**
 * 主 pager 快甩（fling）经过页补登：一次甩多页时中间页上过屏但从未 settle
 * （onPage 只记落定页），按"上过屏即看过"补登已看身份——只写 seen_ids.log
 * 底账，不进观看历史、不计观看时长（与 markGridWatched 同一条目级口径）。
 */
internal fun FeedViewModel.markSeenBatchImpl(items: List<VideoItem>) {
    if (items.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
        items.forEach { runCatching { store.markSeen(it) } }
    }
}

/** 读某 tab 的分屏直拉游标；tab 不匹配（含从未拉过）返回 null = 从头拉 */
internal fun FeedViewModel.gridMediaCursorForImpl(tab: Int): String? =
    gridMediaCursorPair?.takeIf { it.first == tab }?.second

/** 写某 tab 的分屏直拉游标（覆盖式；nextCursor=null = 该 tab 到底） */
internal fun FeedViewModel.updateGridMediaCursorImpl(tab: Int, next: String?) {
    gridMediaCursorPair = tab to next
}
