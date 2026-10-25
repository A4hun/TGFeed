package dev.tgfeed.core.feed

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// ---------------- R222 #关键字 → 事件研究器时间轴（VM part） ----------------
//
// 状态编排：HashtagTimelineScreen 打开即 runHashtagTimeline(tag) → 引擎+源 →
// 状态上屏。**引擎与数据源全部复用 R205**（EventResearcher.research +
// FtsKeywordWindowSource——keywords 传 "#tag" 时 DeepSearchIndex.search 自动路由
// hashtag 精确词模式），本 part 只做 hashtag 专用的状态宿主与缓存语义。
//
// 与 eventResearch 状态（发现页研究器入口）**刻意分离**：两组字段各自独立，
// 互不覆盖对方的召回缓存（研究器 refine 用的 eventResearchRawItems 不会被
// hashtag 时间轴洗掉；反之亦然）。两入口路由互斥，但 VM 是同一个长生命周期
// 实例（"gw"+identityHashCode 共享约定），共享状态会串台。

/** hashtag 时间轴的查询窗起点：epoch 1 秒（filterWindow 要求 t>0；全史=不设下界） */
internal const val HASHTAG_TIMELINE_FROM_SEC = 1L

/**
 * hashtag 时间轴状态宿主（VM 扩展实现；状态字段声明在 FeedViewModel 本体
 * 「hashtag 时间轴」段，见该文件）。
 */
internal fun FeedViewModel.runHashtagTimelineImpl(tag: String) {
    val t = tag.trim().trimStart('#', '＃')
    if (t.isEmpty()) return
    // 缓存命中：同 tag 且已就绪/在飞——返回键回本页复用上一轮结果（LIKE 设备
    // 45s 级深扫不重烧）；Failed 不拦（重进=重试语义）
    if (hashtagTimelineTag == t) {
        when (_hashtagTimelineState.value) {
            is EventResearchUiState.Ready, EventResearchUiState.Loading -> return
            else -> Unit
        }
    }
    hashtagTimelineTag = t
    viewModelScope.launch(Dispatchers.IO) {
        _hashtagTimelineState.value = EventResearchUiState.Loading
        // 全史窗：from=1 → now（「从头到尾」由召回结果的实际时间跨度决定，
        // UI 层按 [min,max] 重分桶——不预设窗宽）
        val query = EventResearchQuery(
            fromEpochSec = HASHTAG_TIMELINE_FROM_SEC,
            toEpochSec = System.currentTimeMillis() / 1000L,
            keywords = "#$t",
        )
        val source: EventResearchSource = FtsKeywordWindowSource(deepIndex)
        when (val r = EventResearcher.research(query, source)) {
            is SourceOutcomeOrResult.Ready ->
                _hashtagTimelineState.value = EventResearchUiState.Ready(r.result)
            SourceOutcomeOrResult.EnginePending ->
                _hashtagTimelineState.value = EventResearchUiState.EnginePending
            is SourceOutcomeOrResult.Failed ->
                _hashtagTimelineState.value = EventResearchUiState.Failed(r.reason)
        }
    }
}

/** 清空 hashtag 时间轴状态（换 tag 重查的兜底口；Ready 缓存由 run 内的命中闸保护） */
internal fun FeedViewModel.clearHashtagTimelineImpl() {
    _hashtagTimelineState.value = EventResearchUiState.Idle
    hashtagTimelineTag = null
}
