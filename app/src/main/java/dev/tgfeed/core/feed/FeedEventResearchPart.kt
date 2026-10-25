package dev.tgfeed.core.feed

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import dev.tgfeed.core.td.VideoItem

// ---------------- WK-3 事件研究器（R205）：VM part ----------------
// 状态编排：研究表单（UI 层持有）→ runEventResearch(query) → 引擎+源 → 状态上屏。
// 数据源 v1 = FtsKeywordWindowSource（关键词 fts 路径）；R202 接口公告后换挂
// windowOnly 源（见 EventResearch.kt 头注释的接线计划）。频道二次筛选走
// refineEventResearchChannels（纯客户端过滤，零新查询）。
//
// 字段宿主：状态字段声明在 FeedViewModel 本体（deepIndex 段之后，见该文件
// 「事件研究器」段）；本文件只放实现（part 模式，与 FeedPlazaPart 同款）。

/** 事件研究器 UI 状态（Ready 里才有可渲染结果；其余态诚实直通上屏） */
sealed interface EventResearchUiState {
    /** 未跑过（首进页：显示表单+引导） */
    data object Idle : EventResearchUiState

    /** 查询中（源拉取+引擎分析） */
    data object Loading : EventResearchUiState

    /** 结果就绪（时间轴+内容流+共现簇） */
    data class Ready(val result: EventResearchResult) : EventResearchUiState

    /** 引擎未接线（R202 待接：纯时间窗查询不可用，v1 需关键词） */
    data object EnginePending : EventResearchUiState

    /** 失败（索引未建/查询异常；reason 中文可直接上屏） */
    data class Failed(val reason: String) : EventResearchUiState
}

/**
 * 跑一轮研究：Loading → 引擎（源三态直通：EnginePending/Failed 不吞，诚实上屏）。
 * 就绪时留底 [FeedViewModel.eventResearchLastQuery] / [eventResearchRawItems]
 * （二次筛选的原料——refine 不重新召回）。
 */
internal fun FeedViewModel.runEventResearchImpl(query: EventResearchQuery) {
    viewModelScope.launch(Dispatchers.IO) {
        _eventResearchState.value = EventResearchUiState.Loading
        val source: EventResearchSource = FtsKeywordWindowSource(deepIndex)
        when (val r = EventResearcher.research(query, source)) {
            is SourceOutcomeOrResult.Ready -> {
                eventResearchLastQuery = query
                eventResearchRawItems = r.result.items
                _eventResearchState.value = EventResearchUiState.Ready(r.result)
            }
            SourceOutcomeOrResult.EnginePending -> {
                _eventResearchState.value = EventResearchUiState.EnginePending
            }
            is SourceOutcomeOrResult.Failed -> {
                _eventResearchState.value = EventResearchUiState.Failed(r.reason)
            }
        }
    }
}

/**
 * 频道集合二次筛选（结果内 chips 点选）：对上一次召回的窗内条目重新过
 * [EventResearcher.filterWindow]+[buildClusters]（纯客户端，零新查询、零索引 IO）。
 * 没跑过研究（无原始召回）时静默忽略——筛选是 refine 语义，不是独立查询。
 */
internal fun FeedViewModel.refineEventResearchChannelsImpl(chatIds: Set<Long>) {
    val last = eventResearchLastQuery ?: return
    val raw = eventResearchRawItems
    if (raw.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
        val refinedQuery = last.copy(chatIds = chatIds)
        val windowed = EventResearcher.filterWindow(raw, refinedQuery)
        eventResearchLastQuery = refinedQuery
        _eventResearchState.value = EventResearchUiState.Ready(
            EventResearchResult(
                query = refinedQuery,
                items = windowed.sortedBy { it.dateEpochSec.toLong() },
                clusters = EventResearcher.buildClusters(windowed, refinedQuery),
                // 二次筛选不换召回口径：note 标注本地过滤（不冒充重新召回）
                engineNote = "频道筛选为结果内本地过滤（未重新召回）；" +
                    "首跑口径：关键词 fts 词位精确匹配",
            ),
        )
    }
}

/** 离开研究页即清（下次进入全新状态，不残留旧查询结果） */
internal fun FeedViewModel.clearEventResearchImpl() {
    _eventResearchState.value = EventResearchUiState.Idle
    eventResearchLastQuery = null
    eventResearchRawItems = emptyList<VideoItem>()
}
