package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem

// ---------------- R391 口味智能编排（#7）：今日单生成器（纯函数） ----------------
//
// 定位（r391_notes §3）：星图口味数据反向输出「今天给你排的单」——纯函数生成器：
// 输入=口味原料（频道权重/收藏/已看账），输出=一条 PlayQueue（今日台），探索
// 新内容与重温收藏按配比混编；**诚实口径：原料缺（双侧皆空）返回 null，
// UI 侧诚实空态引导，不编造**（任务书铁律）。
//
// 架构判词：
//  - 纯 Kotlin 零 Android 依赖（JVM 单测直测，buildPlayPlan 同纪律）；
//  - 时钟/随机全注入（[TodayTasteInput.nowMs]/[seed]）——同输入同输出，测试
//    确定性不赌机器时间；
//  - 探索侧原料=调用方（VM）取 p2ChannelWeights 统一口径（「本类一切当前口味
//    画像的统一出口」——与混排/配额/发现页同一份口味画像，零另起炉灶）；
//  - 已看账只用于**收藏分桶**（未看过的收藏优先=「收藏了还没看」最强重温信号），
//    探索侧不滤——队列=显式排入语境（一期判词「已看硬滤对队列源天然豁免」沿续，
//    重温语义=重看合法）；
//  - 产物 orderMode=MANUAL（推荐序即手排序——一期判词「条目序=手排序」直接
//    表达生成器的推荐顺序）。

/** 今日单配方常量（单源可调——任务书铁律：配比常量单源） */
object TodayQueueRecipe {
    /** 今日台名（生成钮识别/覆盖语义的键；用户改名后=普通台不再覆盖） */
    const val STATION_NAME = "今日台"

    /** 探索侧：排进今日单的频道台数上限 */
    const val EXPLORE_CHANNELS_MAX = 5

    /** 重温侧：排进今日单的收藏条数上限 */
    const val FAVORITE_ITEMS_MAX = 8

    /**
     * 探索门：频道权重低于此值不排（decayedChannelWeights 的过期回落档
     * EXPLORE_WEIGHT=0.3、互动打底同档——7 天没看过/只点过赞的过期口味不
     * 冒充「今日」口味；0.55=衰减平均观看比例约 0.36 的真实兴趣位，
     * r391_notes §1.2 判词）。
     */
    const val EXPLORE_WEIGHT_GATE = 0.55
}

/**
 * R402 今日台上插纯函数（V65-③ 真缺陷修——R401 验证波抓实：旧实现「原位替换
 * 后再无条件尾追」=同 id 双插 → QueueStationsScreen LazyColumn `key={it.id}`
 * 重复 key 主线程崩溃 + 毒态落盘**启动崩溃循环**（4 起 FATAL 实锚））。
 *
 * 修式判词：**滤除全部旧今日台后单次追加**——判据=`name==今日台`（识别键
 * 语义沿续：用户改名=普通台不再覆盖）∨`id==generated.id`（同 id 重入守门）；
 * 顺带**自愈毒态**（历史缺陷落下的重复今日台条全收敛为单条）。满员（新增路）
 * 先淘汰最久未动（今日台优先保=核心体验判词沿续）。纯 Kotlin JVM 可测。
 */
fun upsertTodayStation(
    stations: List<PlayQueue>,
    generated: PlayQueue,
): List<PlayQueue> {
    val replaceable: (PlayQueue) -> Boolean = {
        it.name == TodayQueueRecipe.STATION_NAME || it.id == generated.id
    }
    return if (stations.any(replaceable)) {
        stations.filterNot(replaceable) + generated
    } else {
        val base = if (stations.size >= PlayQueueModel.MAX_STATIONS) {
            stations.sortedByDescending { it.updatedAt }.dropLast(1)
        } else stations
        base + generated
    }
}

/**
 * 今日单生成的口味原料（调用方=VM 的 IO 协程现算快照，r391_notes §3.2）。
 *
 * @param channelWeights 频道权重表（p2ChannelWeights 统一口径——长期轨 7 天
 *   衰减+互动加分×短期轨融合×订阅/会话加成）
 * @param channelTitles 频道标题拼表（channels 覆盖订阅，L2609 先例；缺失条目
 *   生成时回落「频道 <id>」=SmartRooms 同口径）
 * @param favorites 收藏列表（收藏时间倒序——LocalStore loadFavorites 语义）
 * @param watchedKeys 已看条目 key 集（loadSeenIdentities 第一轨；收藏分桶用）
 * @param nowMs 时钟注入（updatedAt/id 生成；测试确定性）
 * @param seed 随机种子注入（entryId 生成；测试确定性——VM 侧=每次生成现取）
 */
data class TodayTasteInput(
    val channelWeights: Map<Long, Double> = emptyMap(),
    val channelTitles: Map<Long, String> = emptyMap(),
    val favorites: List<VideoItem> = emptyList(),
    val watchedKeys: Set<String> = emptySet(),
    val nowMs: Long = 0L,
    val seed: Long = 0L,
)

/**
 * 生成今日单（纯函数）：
 *
 *  - **探索侧**：权重表滤 [TodayQueueRecipe.EXPLORE_WEIGHT_GATE] 门 → 权重降序
 *    （并列 chatId 升序破平=确定性）→ 取 top [TodayQueueRecipe.EXPLORE_CHANNELS_MAX]
 *    → Channel 台级条目（title=拼表缺失回落「频道 <id>」）；
 *  - **重温侧**：收藏分两桶——未看过（!watchedKeys，桶内收藏时间倒序=最近
 *    收藏优先）在前、已看过（真重温，桶内收藏时间倒序）在后 → 取 top
 *    [TodayQueueRecipe.FAVORITE_ITEMS_MAX] → Item 单条条目；
 *  - **混编**：两侧交替穿插（台1、藏1、台2、藏2…）——顺序段走语义下台是长
 *    供给、收藏是短垫场，交替=「探索一段，回味一条」的节奏；一侧先尽=另一侧
 *    余量尾追（配比=两侧上限常量，缺侧自然收敛，不硬凑）；
 *  - **诚实空态**：双侧皆空 → **null**（原料缺=新用户/无历史，UI 侧引导，
 *    不编造空台不硬凑陌生频道）；单侧空 → 单侧全量（≤上限）。
 *
 * @return 今日台 PlayQueue（id=qs_today_ 前缀；断点 null=从头播；推荐序即
 *   entries 序）；原料双侧皆空返回 null
 */
fun buildTodayQueue(input: TodayTasteInput): PlayQueue? {
    // 探索侧：权重门 + 降序 + 破平确定性
    val channels = input.channelWeights.entries
        .filter { it.value >= TodayQueueRecipe.EXPLORE_WEIGHT_GATE }
        .sortedWith(compareByDescending<Map.Entry<Long, Double>> { it.value }.thenBy { it.key })
        .take(TodayQueueRecipe.EXPLORE_CHANNELS_MAX)
        .mapIndexed { i, (chatId, _) ->
            PlayQueueEntry.Channel(
                // 掺序号保证单内唯一（权重表 chatId 唯一但防御口径统一走序号）
                id = todayEntryId(input.seed, input.nowMs, i, chatId),
                chatId = chatId,
                title = input.channelTitles[chatId]?.takeIf { it.isNotBlank() } ?: "频道 $chatId",
            )
        }

    // 重温侧：收藏分桶（未看过优先——「收藏了还没看」最强信号）+ 桶内收藏序
    val favorites = input.favorites
        .sortedBy { if (it.key in input.watchedKeys) 1 else 0 } // 稳定排序：未看(0)在前已看(1)在后
        .take(TodayQueueRecipe.FAVORITE_ITEMS_MAX)
        .mapIndexed { i, item ->
            PlayQueueEntry.Item(
                // 掺序号+key 指纹：收藏可同频道多条（chatId 尾会撞），序号绝对唯一
                id = todayEntryId(input.seed, input.nowMs, i, item.chatId) + "_" + item.key.hashCode(),
                item = item,
            )
        }

    // 诚实空态：双侧皆空=null（不编造）
    if (channels.isEmpty() && favorites.isEmpty()) return null

    // 混编：交替穿插（探索一段、回味一条的节奏），一侧先尽=余量尾追
    val entries = ArrayList<PlayQueueEntry>(channels.size + favorites.size)
    var ci = 0
    var fi = 0
    while (ci < channels.size || fi < favorites.size) {
        if (ci < channels.size) entries += channels[ci++]
        if (fi < favorites.size) entries += favorites[fi++]
    }

    return PlayQueue(
        id = "qs_today_${input.seed}_${input.nowMs}",
        name = TodayQueueRecipe.STATION_NAME,
        orderMode = PlayQueueOrder.MANUAL, // 推荐序即手排序（一期判词直接表达）
        entries = entries,
        updatedAt = input.nowMs,
        resumeEntryId = null,
        resumeKey = null,
    )
}

/**
 * 今日单条目 id：eq_today_<seed>_<nowMs>_<序号>_<来源尾>（同输入同输出确定性；
 * 序号掺入保证单内绝对唯一——收藏可同频道多条（来源尾会撞），一期 newEntryId
 * 的「本机唯一」语义在纯函数里由 seed+nowMs+序号组合承担；跨次生成由 seed 区分）。
 */
private fun todayEntryId(seed: Long, nowMs: Long, index: Int, sourceId: Long): String =
    "eq_today_${seed}_${nowMs}_${index}_${sourceId.toString().takeLast(6)}"
