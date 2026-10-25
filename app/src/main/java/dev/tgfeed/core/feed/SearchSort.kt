package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem

/**
 * R371 搜索结果排序维度（用户 2026-10-24 10:4x 实锤「搜索这块应该支持各种维度的排序，
 * 业界该有的能力咱们都得具备」）——搜索页混排结果的多维度排序**单源件**。
 *
 * 判词与设计（R371 拍板）：
 *  - **默认档=相关度=现状混排原样保留**（本地在前+服务端 searchScore 降序；负锚：默认档
 *    不改变既有顺序——本档实现上直接原样返回不排序，谁也不许在默认档偷加稳定键）；
 *  - 四档=业界标配落地：相关度/最新（dateEpochSec 发布时间降序，0=老收藏未知沉底）/
 *    频道热度（来源频道成员数降序，[memberCounts] 由 VM 本地查表供给——发现图谱+
 *    发现档案内存表，**零网络**；未知（不在表）沉底=诚实降级，不伪装热度）/
 *    时长（durationSec 降序，任务书「内容量」维度的条目级落地——VideoItem 无频道级聚合
 *    字段，条目级体量即时长；图片=0 沉底）；
 *  - **纯客户端**：只对已混排去重后的结果列表排序，零新网络请求（频道热度查表在
 *    VM 侧 IO 线程完成，见 FeedViewModel.channelMemberCountsForSort）；
 *  - 稳定排序：sortedByDescending 系稳定排序，并列项保持混排原序（本地命中优先保留）；
 *  - 档位跨会话记忆：AppPrefs.searchSortOrder（字符串名，缺省 RELEVANCE），与
 *    fuzzySearch 同模式（UI 只读写这一处，禁止旁路第二入口）。
 *
 * 接线：SearchScreen 的 mergedResults（本地+服务端混排去重后）在上屏前套用本函数；
 * 排序 chips 只改档位不重复实现排序逻辑（单源纪律）。
 */
enum class SearchSortOrder(val label: String) {
    /** 相关度（默认）：现状混排——本地命中在前、服务端按 searchScore 降序，不重排 */
    RELEVANCE("相关度"),

    /** 最新：发布时间 dateEpochSec 降序；0=未知（老收藏）沉底 */
    NEWEST("最新"),

    /** 频道热度：来源频道成员数降序（本地图谱查表，未知沉底） */
    CHANNEL_HEAT("频道热度"),

    /** 时长：条目时长降序（视频/音频秒数；图片=0 沉底） */
    DURATION("时长"),

    ;

    companion object {
        /** 持久化名反解（AppPrefs.searchSortOrder）；未知/空值回落默认档 RELEVANCE */
        fun fromName(name: String?): SearchSortOrder =
            values().firstOrNull { it.name == name } ?: RELEVANCE
    }
}

/**
 * 对混排去重后的搜索结果套用排序档（单源排序器）。
 *
 * @param items 混排结果（second=true 为本地命中，排序只动顺序不动成员与本地标记）
 * @param order 排序档；[SearchSortOrder.RELEVANCE] 直接原样返回（默认档负锚）
 * @param memberCounts chatId → 频道成员数（CHANNEL_HEAT 档数据源；其他档不读）
 */
fun applySearchSortOrder(
    items: List<Pair<VideoItem, Boolean>>,
    order: SearchSortOrder,
    memberCounts: Map<Long, Int> = emptyMap(),
): List<Pair<VideoItem, Boolean>> = when (order) {
    // 默认档=现状：不排序（本地在前+服务端序原样上屏——R371 负锚，禁止加键重排）
    SearchSortOrder.RELEVANCE -> items
    // 最新：发布时间降序；dateEpochSec=0（未知/老收藏）沉底，并列保混排原序
    SearchSortOrder.NEWEST -> items.sortedByDescending { it.first.dateEpochSec }
    // 频道热度：成员数降序；未知（≤0/不在表）沉底——诚实降级不伪装热度
    SearchSortOrder.CHANNEL_HEAT ->
        items.sortedByDescending { (memberCounts[it.first.chatId] ?: 0).coerceAtLeast(0) }
    // 时长：条目秒数降序；图片（=0）沉底
    SearchSortOrder.DURATION -> items.sortedByDescending { it.first.durationSec }
}
