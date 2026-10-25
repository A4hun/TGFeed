package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem

// ---------------- R229 追更 tab（方案 B·频道动态卡流）纯策略 ----------------

/**
 * R229 追更 tab 纯策略（用户终审 r224_notes：方案 B 当选 + **最新在最下面**）。
 *
 * 产品语义（用户原话口径）：
 *  - 「频道A 刚发布了 2 条」——按频道聚合的动态卡流，先认脸再看内容；
 *  - **最新在最下面**——用户往下刷=新消息在下面排队等他看；越往上越旧
 *    （刷朋友圈的自然方向），不是常规 feed 的最新置顶；
 *  - 30 秒轮询折叠被动更新流，新卡从底部滑入。
 *
 * 本文件零 IO 零 Compose（单测锚 [PursuePolicyTest]）；网络腿见
 * core/td/GatewayPursuePart.kt（被动 UpdateNewMessage 流 + 每频道近期媒体），
 * VM 编排见 PursueViewModel.kt。
 *
 * 数据口径（与「方案 C 预留」的接口边界）：卡流=频道粒度聚合视图；后续版本
 * 若加时间分区视图（方案 C 保留项），消费同一份 items 池重新分桶即可——
 * 本策略的聚合输出是纯函数，分桶渲染器另立，不预设分支。
 */

/** 追更窗（秒）：最近 24 小时的频道动态（mockup 数据跨度 3s~8h 的自然放大；R215「此刻 1h」档语义并入本 tab 的宽窗版） */
internal const val PURSUE_WINDOW_SEC = 24L * 3600L

/**
 * R239 时间线窗（秒）：**3 天**（任务书口径「首次打开拉最近 3 天的全频道更新」；
 * R229 v1 卡流的 24h 窗只覆盖当天活跃频道——用户实锤「有的频道更了很多但没展示
 * 出来」，72h 窗+全频道覆盖才是「时间范围内符合条件的都应该展示」）。供
 * [PursueViewModel] 的扁平流窗/滚动修剪消费；旧卡流语义（[pursueItemInWindow]
 * 的 windowSec 参数）不受影响（调用方传参）。
 */
internal const val PURSUE_TIMELINE_WINDOW_SEC = 3L * 24L * 3600L

/** 每频道条目上限（「刚发布了 N 条」的 N 与缩略横排的货盘上限；超出按新→旧截断） */
internal const val PURSUE_ITEMS_PER_CHANNEL = 8

/**
 * R239 时间线每频道**拉取深度**（媒体目标条数/频道/次扫描）：fetchFromChannel
 * 的 limit 形参——maxRounds=2×50 条消息扫描内尽量收满；展示端无每频道上限
 * （用户修正①：时间窗内完整流，收到多少展示多少），本值只是网络预算的
 * 有界性保护（598 频道 × 12 条 ≤ 7176 条首轮供给）。
 */
internal const val PURSUE_FETCH_PER_CHANNEL = 12

/**
 * R239 历史轮每频道媒体目标（fetchFromChannel limit 形参，maxRounds=1×50 条
 * 消息扫描）：一轮全频道并发后全局时间归并的每频道供给上限。
 */
internal const val PURSUE_HISTORY_ROUND_ITEMS = 10

/** 卡数上限（滚动窗内最多展示的频道卡数；超出从顶=最旧侧裁掉） */
internal const val PURSUE_MAX_CARDS = 60

/**
 * R239 扁平流长度护栏：内存兜底（百屏级深度才触顶——按 4 列格 ≈3000 屏）；
 * 超限从**最旧侧**裁（用户主动回看的历史优先保留新端，滑得够深时最深端让位）。
 * 正常路径永不触达（窗 72h × 用户节奏的历史轮远低于此量级）。
 */
internal const val PURSUE_STREAM_HARD_CAP = 12_000

/**
 * R239 全频道扫描软性截止（毫秒）：bootstrap 正路的尾部频道跳过线——最近活跃
 * 序前缀已优先供给，尾部（长期沉寂频道）超时让路（下轮刷新自然补上）。
 */
internal const val PURSUE_SWEEP_DEADLINE_MS = 120_000L

/**
 * R239 索引 fallback 步长（秒）：历史网络轮空手时，深索引按 [floor-本值, floor)
 * 一窗向前找内容的探测步（3 天一窗与任务书「每 3 天为一窗」口径对齐）。
 */
internal const val PURSUE_INDEX_FALLBACK_STEP_SEC = 3L * 24L * 3600L

/** R239 索引 fallback 单次最大探测窗数（连续空窗防长空转） */
internal const val PURSUE_INDEX_FALLBACK_MAX_WINDOWS = 8

/**
 * R239 历史轮**近顶预取行距**（grid 行）：第一可见行进入距顶 N 行内即触发
 * 历史轮（预载更早内容在用户到顶前就位——流在顶部无缝向前生长，prepend
 * 由格 key 锚定滚动位零跳动）。硬顶触发（canScrollBackward=false）在
 * 1787+ 条的密度流上=200+ 屏才可达=事实不可达（真机实测），预取口径把
 * 「滑到顶=触发」的语义落成「接近顶=供给」——用户永远滑不到头（无上限回看）。
 */
internal const val PURSUE_HISTORY_PREFETCH_ROWS = 18

/** 轮询折叠周期（毫秒）：被动流积压每 30s 折叠一次成卡（任务书口径）。R239 起时间线 30s tick 复用同一节拍（职责改滚动窗修剪+新鲜期检查——用户修正③：实时推入，折叠不再延迟上屏），不另立私有镜像（纪律§一同数同源） */
internal const val PURSUE_TICK_MS = 30_000L

/** bootstrap 频道数上限：按会话清单序（主列表≈最近活跃序）取前 N 个「频道/群」会话拉近期媒体 */
internal const val PURSUE_BOOTSTRAP_CHANNELS = 24

/** bootstrap 新鲜期（毫秒）：窗内重进 tab 不重拉（被动流+滚动窗已覆盖）；超期（后台回来等缺口）重拉补账 */
internal const val PURSUE_BOOTSTRAP_FRESH_MS = 5L * 60_000L

/**
 * 追更频道动态卡：一个频道近窗内的全部动态。
 *
 * @property chatId 频道 id（负数=频道/群会话——被动流的入口过滤口径）
 * @property channelTitle 频道名（toVideoItem 懒解析产物；空串由 UI 兜底「频道尾号」）
 * @property items 卡内条目（**新→旧**序；窗内去重后截 [PURSUE_ITEMS_PER_CHANNEL]）
 * @property newestSec 最新一条的发布秒——卡在列表里的排序键
 * @property freshAtMs 折叠时刻（毫秒）：本卡是否「新滑入卡」的判定原料
 *   （bootstrap 全量=0 恒不滑入；30s 折叠新增/有新货的卡打当拍时刻，UI 首次组合时
 *   据 `freshAtMs>0` 播 slideInVertically；已组合的卡刷新不重播——LazyColumn key 保实例）
 */
internal data class PursueChannelCard(
    val chatId: Long,
    val channelTitle: String,
    val items: List<VideoItem>,
    val newestSec: Long,
    val freshAtMs: Long = 0L,
)

/**
 * 卡摘要文案：「刚发布了 N 条视频/图片/音频」（全卡同类型给精确词，混类型给
 * 「新内容」）——mockup B 案的正式版（单一出处，UI 直取）。
 */
internal fun pursueCardSummary(items: List<VideoItem>): String {
    val word = when {
        items.isEmpty() -> "新内容"
        items.all { it.mediaType == 0 } -> "视频"
        items.all { it.mediaType == 1 } -> "图片"
        items.all { it.mediaType == 2 } -> "音频"
        else -> "新内容"
    }
    return "刚发布了 ${items.size} 条$word"
}

/**
 * 条目是否落在追更窗内（dateEpochSec>0 且 ≥ now-windowSec；无时间戳的条目
 * 无法参与时间语义，诚实剔除）。
 */
internal fun pursueItemInWindow(item: VideoItem, nowSec: Long, windowSec: Long): Boolean =
    item.dateEpochSec > 0 && item.dateEpochSec >= nowSec - windowSec

/**
 * 条目池 → 频道卡流（方案 B 聚合本体）。
 *
 * - 窗过滤 + key 去重（跨来源合并不重复入卡）；
 * - 每频道内按发布时间**新→旧**截 [perChannelCap]（缩略横排的货盘）；
 * - 卡流按 newestSec **升序**排（index 0=最旧，滚到底=最新）——用户终审的
 *   「最新在最下面」核心不变量；平手按 chatId 升序稳定（可测可复现）。
 * - freshAtMs=0（bootstrap 全量不滑入；滑入标记由 [mergePursueCards] 折叠路径打）。
 */
internal fun buildPursueCards(
    items: Collection<VideoItem>,
    nowSec: Long,
    windowSec: Long,
    perChannelCap: Int,
): List<PursueChannelCard> {
    if (items.isEmpty()) return emptyList()
    val cutoff = nowSec - windowSec
    val byChat = LinkedHashMap<Long, MutableList<VideoItem>>()
    val seenKeys = HashSet<String>(items.size)
    for (it in items) {
        if (it.dateEpochSec <= 0 || it.dateEpochSec < cutoff) continue
        if (!seenKeys.add(it.key)) continue
        byChat.getOrPut(it.chatId) { ArrayList() } += it
    }
    val out = ArrayList<PursueChannelCard>(byChat.size)
    for ((chatId, list) in byChat) {
        val sorted = list.sortedByDescending { it.dateEpochSec }
        out += PursueChannelCard(
            chatId = chatId,
            channelTitle = sorted.first().channelTitle,
            items = sorted.take(perChannelCap),
            newestSec = sorted.first().dateEpochSec.toLong(),
        )
    }
    return out.sortedWith(compareBy({ it.newestSec }, { it.chatId }))
}

/**
 * 滚动窗裁剪：出窗卡（newestSec 落后窗口）淘汰 + 卡数超限从**顶=最旧**侧裁
 * （底部永远是最新——用户「往下刷=追新」的方向不变）。
 */
internal fun prunePursueCards(
    cards: List<PursueChannelCard>,
    nowSec: Long,
    windowSec: Long,
    maxCards: Int,
): List<PursueChannelCard> {
    val cutoff = nowSec - windowSec
    val kept = cards.filter { it.newestSec >= cutoff }
    return if (kept.size > maxCards) kept.drop(kept.size - maxCards) else kept
}

/**
 * 30s 折叠：现有卡 + 被动流新到条目 → 新卡表（tick 的唯一增量入口）。
 *
 * - 合并池 = 现有卡 items + incoming（build 内窗过滤+key 去重兜住过期与重复）；
 * - **新滑入标记**：折叠拍新增的卡（chatId 原先不在场）或有新货的卡（条目数变多）
 *   打 freshAtMs=nowMs；其余卡保持原 freshAtMs（已组合卡不重播滑入）；
 * - 收尾过 [prunePursueCards]（滚动窗淘汰旧卡、卡数封顶）。
 */
internal fun mergePursueCards(
    existing: List<PursueChannelCard>,
    incoming: List<VideoItem>,
    nowSec: Long,
    windowSec: Long,
    perChannelCap: Int,
    maxCards: Int,
    nowMs: Long = System.currentTimeMillis(),
): List<PursueChannelCard> {
    val prevCount = existing.associate { it.chatId to it.items.size }
    val pool = ArrayList<VideoItem>(existing.sumOf { it.items.size } + incoming.size)
    for (card in existing) pool += card.items
    pool += incoming
    val rebuilt = buildPursueCards(pool, nowSec, windowSec, perChannelCap)
    val prevFresh = existing.associate { it.chatId to it.freshAtMs }
    return prunePursueCards(
        rebuilt.map { card ->
            val addedItem = (prevCount[card.chatId] ?: 0) < card.items.size
            val fresh = if (card.chatId !in prevCount || addedItem) nowMs else (prevFresh[card.chatId] ?: 0L)
            if (fresh == card.freshAtMs) card else card.copy(freshAtMs = fresh)
        },
        nowSec,
        windowSec,
        maxCards,
    )
}

// ---------------- R239 时间线扁平完整流（用户修正①：时间窗内完整流，无每频道上限） ----------------
// 旧卡流族（上方）按 ARCHITECTURE 接口契约原样保留（消费方只剩单测）；时间线 v2
// 的数据模型改扁平：List<VideoItem> 按 dateEpochSec 升序（index 0=最旧、末尾=最新
// ——「最新在最下面」用户拍板不变量延续），本节为 VM 消费的纯函数族。

/**
 * 流的滚动修剪下界（秒）：普通态=now-windowSec（滚动窗滑出淘汰）；
 * **历史回看开启后**（historyFloorSec>0=用户已向前翻过）=两者取更旧——历史
 * 内容是用户主动请求的，不随滚动窗被裁（窗修剪只淘汰「没人翻过的旧内容」）。
 */
internal fun pursueTimelineCutoffSec(nowSec: Long, windowSec: Long, historyFloorSec: Long): Long {
    val rolling = nowSec - windowSec
    return if (historyFloorSec > 0L) minOf(rolling, historyFloorSec) else rolling
}

/**
 * 扁平流合并（时间线唯一的入流口径——bootstrap/快车道/实时推入/历史轮共用）：
 * - key 去重**来者覆盖**（同 key 的网络/live 版本字段比索引行更富——playUri/
 *   coverBytes/variants 都在活条目上，覆盖方向保证展示与播放吃最富版本）；
 * - cutoff 过滤（无时间戳条目诚实剔除——无法参与时间语义）；
 * - 升序排（dateEpochSec，平手按 key 稳定）；
 * - 超过 [hardCap] 从最旧侧裁（内存护栏）。
 */
internal fun pursueTimelineMerge(
    existing: List<VideoItem>,
    incoming: List<VideoItem>,
    cutoffSec: Long,
    hardCap: Int = PURSUE_STREAM_HARD_CAP,
): List<VideoItem> {
    if (existing.isEmpty() && incoming.isEmpty()) return emptyList()
    val byKey = LinkedHashMap<String, VideoItem>(existing.size + incoming.size)
    for (it in existing) byKey[it.key] = it
    for (it in incoming) byKey[it.key] = it
    val out = ArrayList<VideoItem>(byKey.size)
    for (it in byKey.values) {
        if (it.dateEpochSec > 0 && it.dateEpochSec >= cutoffSec) out += it
    }
    out.sortWith(compareBy({ it.dateEpochSec }, { it.key }))
    return if (out.size > hardCap) out.drop(out.size - hardCap) else out
}

/**
 * 实时推入的「新内容」计数（「N 条新内容」胶囊口径）：merged 中**晚于旧流最新
 * 一条**的新 key 数——滚动窗修剪/去重导致的位次重排不算新内容，只有真正
 * append 在用户已见最新端之后的内容才计数（用户不在底部时安静排队）。
 */
internal fun pursueTimelineAppendCount(old: List<VideoItem>, merged: List<VideoItem>): Int {
    if (merged.size <= old.size) return 0
    val oldKeys = old.mapTo(HashSet(old.size)) { it.key }
    val oldNewestSec = old.lastOrNull()?.dateEpochSec ?: 0
    return merged.count { it.key !in oldKeys && it.dateEpochSec > oldNewestSec }
}

/**
 * 历史索引 fallback 窗（用户修正②的 c 腿：网络轮空手时的本地供给）：
 * [floorSec] 以下 [stepSec] 一窗（from..to 含两端）；floor 不足一步/越界 → null
 * （调用方按诚实终态处理）。
 */
internal fun pursueHistoryFallbackWindow(floorSec: Long, stepSec: Long): Pair<Long, Long>? {
    if (floorSec <= 0L) return null
    val to = floorSec - 1L
    val from = floorSec - stepSec
    return if (to <= 0L || from <= 0L) null else from to to
}

// ---------------- R240 时间线卡片分批（微信/微博式卡流呈现） ----------------
// 本节为**呈现层**纯函数：扁平流（上方 R239 族）不变，本节只决定「怎么摆盘」——
// 同频道同批的相邻条目折成一卡（用户实锤「瀑布格看不出哪个是哪个频道的」的解法）。
// 数据链（VM/网关/索引）零消费零改动；旧卡流族（R229）/扁平流族（R239）一字不动。

/**
 * R240 卡片缩略上限（张）：卡内缩略横排最多展示的条目数，超出部分折叠为
 * 「+N」溢出角标（角标非交互——被折叠条目经邻域窗上滑可达，诚实可达不藏）。
 */
internal const val PURSUE_CARD_THUMB_MAX = 3

/**
 * R240 同批合卡的时间断裂阈（秒）：同频道**相邻**条目间隔 ≤ 此值视为同一批更新
 * （合一张卡——「频道连发一波」的读法）；间隔更大=新卡（各自卡头时间诚实分摊）。
 * 换频道恒新卡。30 分钟=频道连发一轮的常见跨度（分钟级连发全覆盖、跨小时不算同批）。
 */
internal const val PURSUE_CARD_BATCH_GAP_SEC = 30L * 60L

/**
 * R240 时间线卡片（呈现模型）：一个频道**同批**更新的展示单元。
 *
 * @property chatId 频道 id
 * @property channelTitle 频道名（取卡尾最新条目的懒解析产物；空串由 UI 兜底「频道尾号」）
 * @property items 卡内条目（**升序**=与扁平流同序；不截断——完整流语义在数据面保持，
 *   展示端只折叠缩略）
 * @property newestSec 卡尾（最新一条）时刻——卡头相对时间与卡在列表里位置的排序键
 */
internal data class PursueTimelineCard(
    val chatId: Long,
    val channelTitle: String,
    val items: List<VideoItem>,
    val newestSec: Long,
) {
    /** 卡内缩略横排展示集（最新 [PURSUE_CARD_THUMB_MAX] 条，旧→新序——横排从左到右按时间读） */
    val thumbs: List<VideoItem>
        get() = items.takeLast(PURSUE_CARD_THUMB_MAX)

    /** 溢出计数（「+N」角标=被折叠的更早条数；0=无溢出不打角标） */
    val overflowCount: Int
        get() = maxOf(0, items.size - PURSUE_CARD_THUMB_MAX)
}

/**
 * R240 扁平流 → 卡片流（纯分批：不删不改不重排条目——「时间窗内完整流」数据语义
 * 零改动，本函数只做呈现分组）。
 *
 * - 相邻同频道且间隔 ≤ [batchGapSec] → 合一张卡（「同频道同批」）；
 * - 换频道 / 间隔断裂 / 流首 → 新卡；
 * - 卡序=流序（升序——「最新在最下面」用户拍板不变量保持）；卡内条目升序；
 * - 入流口径约定升序（[pursueTimelineMerge] 保证，单测锚）；本函数不二次排序。
 */
internal fun pursueTimelineCards(
    stream: List<VideoItem>,
    batchGapSec: Long = PURSUE_CARD_BATCH_GAP_SEC,
): List<PursueTimelineCard> {
    if (stream.isEmpty()) return emptyList()
    val out = ArrayList<PursueTimelineCard>(stream.size) // 上界=每条各自成卡

    var batch: ArrayList<VideoItem>? = null
    for (entry in stream) {
        val cur = batch
        val startNew = cur == null ||
            entry.chatId != cur.last().chatId ||
            (entry.dateEpochSec.toLong() - cur.last().dateEpochSec.toLong()) > batchGapSec
        if (startNew) {
            cur?.let { done -> out += cardOf(done) }
            batch = arrayListOf(entry)
        } else {
            cur!!.add(entry)
        }
    }
    batch?.let { done -> out += cardOf(done) }
    return out
}

/** 卡装配：频道名/时刻取卡尾（最新条目——卡头「谁·刚发」的身份面） */
private fun cardOf(batch: List<VideoItem>): PursueTimelineCard = PursueTimelineCard(
    chatId = batch.last().chatId,
    channelTitle = batch.last().channelTitle,
    items = batch,
    newestSec = batch.last().dateEpochSec.toLong(),
)
