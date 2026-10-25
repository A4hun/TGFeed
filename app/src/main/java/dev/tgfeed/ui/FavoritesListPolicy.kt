package dev.tgfeed.ui

import dev.tgfeed.core.td.VideoItem

/**
 * R199 收藏查看模块的列表纯策略（单测锚点；UI 只做状态搬运，决策全在本件）。
 *
 * 数据层不变量（本件全部函数的前置条件，零改动红线）：`favoriteItems` /
 * LocalStore 落盘顺序即**收藏时间倒序**（新收藏置顶，toggleFavorite 数据层维护），
 * 条目本身不带收藏时刻字段——「排序=收藏时间倒序（默认视图）」由该不变量直接
 * 满足（默认视图=列表原样平铺），本件只负责次级视图「按频道分组」的派生。
 */

/** 收藏墙平铺后的列表元素：分组视图的组头 或 条目格（两种视图共用的行模型） */
sealed interface FavoritesListRow {
    /** 按频道分组视图的组头：频道名 + 组内条数（组序=组内最新收藏在前） */
    data class Header(val chatId: Long, val title: String, val count: Int) : FavoritesListRow

    /** 收藏条目格 */
    data class Entry(val item: VideoItem) : FavoritesListRow
}

/**
 * 按频道分组（次级视图）：对收藏时间倒序的列表按 chatId 归组。
 * - **组序** = 每组最新收藏（组内首条）在原列表中的先后（首现序）——最近收的
 *   频道排最前，与默认视图的「最新在前」直觉一致；
 * - **组内序** = 原序（该频道内收藏时间倒序）；
 * - **组头标题** = 组内首条（最新）的 channelTitle——频道改名后以最新名字为准；
 *   空名回退「未命名频道」；
 * - 输出 = Header/Entry 平铺列表，LazyVerticalGrid 直接消费（Header 占整行）。
 */
fun groupFavoritesByChannel(items: List<VideoItem>): List<FavoritesListRow> {
    if (items.isEmpty()) return emptyList()
    // 首现序归组：LinkedHashMap 保插入序（=最新收藏在前的组序）
    val groups = LinkedHashMap<Long, MutableList<VideoItem>>()
    for (item in items) {
        groups.getOrPut(item.chatId) { mutableListOf() }.add(item)
    }
    val out = ArrayList<FavoritesListRow>(items.size + groups.size)
    for ((chatId, group) in groups) {
        val title = group.first().channelTitle.ifBlank { "未命名频道" }
        out += FavoritesListRow.Header(chatId, title, group.size)
        group.forEach { out += FavoritesListRow.Entry(it) }
    }
    return out
}

/**
 * 我的页「我的收藏」行计数徽章格式：≤999 原样数字；≥1000 收敛为「999+」
 * （徽章是行内小件，宽度过大会挤压行文案；收藏量级到千位时精确数对用户无增量信息）。
 */
fun formatFavoritesCount(n: Int): String =
    if (n > 999) "999+" else n.toString()
