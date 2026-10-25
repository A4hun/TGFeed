package dev.tgfeed.core.feed

import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.VideoItem

// ---------------- R397 分屏批量入口（统一机制件） ----------------
//
// 定位（用户 19:1x 立项「搜索结果批量加进分屏；分屏在很多场景都能利用起来，都要
// 覆盖」；R387/R391「一切皆 Feed」同思想移植）：**任意条目集 → 分屏，一刀统一机制**。
//
// 实勘判词（r397_notes §1）：分屏入格真身 = 容器片单 App.gridPlaylist（GridScreen
// 片单模式 customItems 非空优先于自持聚合源），单条路 = ActionSheet「加入分屏」→
// container.addToGrid（key 去重）；批量先例 = GridScreen「从片单添加」整单倒入。
// 本件把该形态收口成三个单源件：
//   1. [gridBatchSeedAppend]——批量种子纯函数（去重口径 = addToGrid 单条 key 判轨的
//      批量化，实增数可测）；
//   2. [fetchCollectionSeed]——channel 形态面（频道合集）换条目集的现拉路；
//   3. [GRID_SEED_FETCH_MAX]——现拉上限（画布记忆口径）。
// 容器口 App.addBatchToGrid（App.kt，本件消费方）之后，任何新列表面接入 = 一行：
// 钮回调传 List<VideoItem> → addBatchToGrid(来源, items) + 容器 showGrid()。
//
// 纪律：纯 Kotlin 零 Android 依赖（JVM 单测直测）；
// 分屏播放引擎零碰（GridScreen 本体零刀，种子路全在容器/调用方侧）。

/**
 * 批量入分屏种子纯函数：返回 incoming 中可追加进分屏片单的子列。
 *
 * 去重口径（沿 [App.addToGrid] 单条路的 item.key 判轨批量化——刻意不引入
 * fileUniqueId 第二判轨：单条路没有，批量路不偷偷加宽口径）：
 *  - 与 existing（分屏片单当前内容）撞 key 的剔除；
 *  - 批内同 key 只保首个（搜索混排/队列条目已按 key 去重的面天然不触发，此处兜底）。
 *
 * 顺序：保持 incoming 原序（调用方给的列表序即入格序=画布排位序）。
 * 无上限判词：画布流式排位条目数无硬闸（GRID_CANVAS_MAX_SLOTS 只管画布记忆落盘的
 * 前 N 槽位），本地在位列表全量入格零成本；有网络成本的路（fetchCollectionSeed）
 * 才设上限。空入返回空表。
 */
fun gridBatchSeedAppend(existing: List<VideoItem>, incoming: List<VideoItem>): List<VideoItem> {
    if (incoming.isEmpty()) return emptyList()
    val seen = HashSet<String>(existing.size + incoming.size)
    for (item in existing) seen.add(item.key)
    val out = ArrayList<VideoItem>(incoming.size)
    for (item in incoming) {
        if (seen.add(item.key)) out += item
    }
    return out
}

/** 合集现拉上限（R397）：对齐 GridScreen 画布记忆口径 GRID_CANVAS_MAX_SLOTS=48 */
const val GRID_SEED_FETCH_MAX = 48

/**
 * 合集 → 分屏种子现拉（channel 形态面换条目集的唯一路）：
 * 构造 [CollectionFeedSource] 拉**单轮** nextPage(GRID_SEED_FETCH_MAX)——每频道配额
 * 均分（至少 4 条）+ 源内 shuffle 天然混排，恰是分屏要的「多频道打散铺格」形态。
 *
 * 诚实口径：空结果（离线/坏档/全部频道失败）原样返回空表，不编造不重试——调用方
 * 空表如实提示（合集行钮面无数即因在位数不可预知，实数在拉后 Toast/探针落实）。
 * 单轮即止判词：一轮 ≈ min(48, 频道数×配额) 条已铺满画布记忆口径；想续看走分屏
 * 画布既有换片/预取链（池本体零碰的边界）。
 */
suspend fun fetchCollectionSeed(gateway: TdGateway, collection: ChannelCollection): List<VideoItem> {
    val page = CollectionFeedSource(gateway, collection).nextPage(GRID_SEED_FETCH_MAX)
    return page.items
}
