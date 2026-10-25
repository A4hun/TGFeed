package dev.tgfeed.core.feed

import dev.tgfeed.core.td.SearchTextKit
import dev.tgfeed.core.td.VideoItem

// ---------------- WK-3 事件研究器（R205）：参数模型 + 研究引擎 + 数据源端口 ----------------
//
// 定位（timeline-opportunities.md WK-3）：时间维度的高级研究 UI——给定时间窗 +
// 关键词/频道集合 → 内容流时间轴 + 共现分析（哪些频道同传某内容 = 事件传播路径，
// 谁先发 = 首发溯源）。受众是研究党/考据党，作为高级工具从发现页深功能区进。
//
// 数据架构（本轮 R205 的关键约束：R202 共享原语未公告、并发错峰）：
//  - 本文件定义【端口】[EventResearchSource]：时间窗查询的抽象口。R202 接口
//    公告后，新增 windowOnly 的生产实现（接 idx_items_date 时间窗原语）即是
//    一处 adapter 替换，UI/引擎/单测零改动；
//  - v1 生产实现 [FtsKeywordWindowSource]：关键词路径走既有
//    [DeepSearchIndex.search]（只读公开 API，本文件不改 DeepSearchIndex 一行），
//    时间窗/频道集在 Kotlin 侧过滤——timeline-opportunities TL-4①「fts 现成版」
//    同款口径；纯时间窗（无关键词）返回 [SourceOutcome.EnginePending] 诚实态；
//  - 研究引擎 [EventResearcher] 全部纯函数：单测驱动全链（窗过滤/分桶/共现簇/
//    首发溯源/72h 断簇），不依赖任何 Android 类型。
//
// 口径红线（继承自 R183/R200）：
//  - 绝不做 dateEpochSec 全表扫（R183 明令违禁）——v1 fts 路径先按词位召回再
//    Kotlin 侧窗过滤，召回集有 SEARCH_HARD_CAP 有界；窗口全量查询等 R202 索引；
//  - hasIndex() 前置（R183 防误建库纪律）：索引未建不触发 openDb 建库扫描；
//  - 输出脱敏只约束书面输出（笔记/报告/提交信息）——UI 展示用户本地数据原文
//    不受影响（纪律§七：审查只打输出文本，界面内数据是用户自己的）。

/**
 * 研究查询参数（研究器表单 → 引擎输入的唯一契约）。
 *
 * @param fromEpochSec 时间窗起点（Unix 秒，含）
 * @param toEpochSec 时间窗终点（Unix 秒，含）
 * @param keywords 关键词（原文，空格/逗号分隔多词；v1 必填——纯时间窗查询等
 *   R202 索引原语接线后开放，见 [SourceOutcome.EnginePending]）
 * @param chatIds 频道集合过滤（空 = 全部已索引频道；结果内二次过滤也走它）
 * @param coOccurrenceWindowHours 共现时间窗（小时）：同一指纹的两条记录时间差
 *   超窗即断簇（W4/TL-4 口径默认 72h——事件传播的自然周期）
 * @param minClusterChannels 成簇的最小频道数（默认 2：≥2 频道同传才算「事件」；
 *   单频道重发不构成传播路径）
 */
data class EventResearchQuery(
    val fromEpochSec: Long,
    val toEpochSec: Long,
    val keywords: String = "",
    val chatIds: Set<Long> = emptySet(),
    val coOccurrenceWindowHours: Int = DEFAULT_CO_OCCURRENCE_WINDOW_HOURS,
    val minClusterChannels: Int = DEFAULT_MIN_CLUSTER_CHANNELS,
) {
    companion object {
        /** 共现窗默认 72h（timeline-opportunities TL-4/W4：fileUniqueId 跨频道 72h 窗共现） */
        const val DEFAULT_CO_OCCURRENCE_WINDOW_HOURS = 72

        /** 成簇最小频道数默认 2（≥2 频道同传 = 事件；1 = 普通发布） */
        const val DEFAULT_MIN_CLUSTER_CHANNELS = 2
    }
}

/** 时间轴分桶（内容流时间轴的渲染单元：等宽桶 + 计数 + 日期标签） */
data class TimelineBucket(
    /** 桶起点（Unix 秒，含） */
    val fromSec: Long,
    /** 桶终点（Unix 秒，不含） */
    val toSec: Long,
    /** 桶内条目数 */
    val count: Int,
    /** 桶标签（yyyy-MM-dd 或 yyyy-MM-dd HH:mm，短窗自动细粒度——见 bucketTimeline） */
    val label: String,
)

/** 一次传播路径的中继站：簇内一条记录（按时间升序排好序） */
data class EventRelay(
    /** 中继条目（频道/标题/时间全在内） */
    val item: VideoItem,
    /** 距首发的小时数（四舍五入；首发恒 0） */
    val offsetHours: Long,
    /** true = 簇内最早一条（首发溯源：最有关注价值的节点） */
    val isFirst: Boolean,
)

/** 一个事件簇：同一内容（指纹相同）跨频道共现 + 传播路径 */
data class EventCluster(
    /** 簇指纹（fileUniqueId 精确指纹 或 归一化 caption 模板指纹） */
    val fingerprint: String,
    /** 指纹类型：精确（同片转发）/ 模板（同文案重发，改水印换指纹的兜底路） */
    val exact: Boolean,
    /** 簇代表标题（首发条目 caption 首行；空 caption 回落频道名） */
    val representativeTitle: String,
    /** 首发时间（Unix 秒） */
    val firstEpochSec: Long,
    /** 传播路径（时间升序：首发→各频道中继，offsetHours 递增） */
    val relays: List<EventRelay>,
) {
    /** 参与频道数（去重——同频道多条转发只算一个传播节点） */
    val channelCount: Int get() = relays.map { it.item.chatId }.distinct().size
}

/**
 * 研究结果（引擎输出，UI 直接渲染）。
 *
 * @param items 窗内条目（时间升序——内容流时间轴的数据源）
 * @param clusters 事件簇（按簇规模降序、同规模按首发时间降序：大事件在前）
 * @param engineNote 引擎口径说明（诚实口径：召回路径、覆盖率限制——如
 *   「关键词召回路径：深索引 fts 词位匹配」/「采样频道的历史区可能偏稀」）
 */
data class EventResearchResult(
    val query: EventResearchQuery,
    val items: List<VideoItem>,
    val clusters: List<EventCluster>,
    val engineNote: String,
)

/**
 * 数据源端口：时间窗查询的抽象（R202 接线点）。
 *
 * v1 实现见 [FtsKeywordWindowSource]（关键词 fts 召回 + Kotlin 侧窗过滤）；
 * R202 公告共享时间窗原语（idx_items_date 索引查询）后，新增 windowOnly 实现
 * 挂到这里即可，上层零改动。单测用内存 mock 实现（见 EventResearchTest）。
 */
interface EventResearchSource {
    /** 拉时间窗内条目（关键词/频道集合过滤由实现承担或引擎兜底——见 filterWindow） */
    suspend fun loadWindow(query: EventResearchQuery): SourceOutcome
}

/** [EventResearchSource.loadWindow] 的三态结果（引擎不吞状态：诚实上屏） */
sealed interface SourceOutcome {
    /**
     * 正常返回（可以是空集——空窗是合法事实，由 UI 空态承接，不是错误）。
     * [note] = 该次召回的口径说明（引擎口径诚实上屏的原料）。
     */
    data class Items(val items: List<VideoItem>, val note: String) : SourceOutcome

    /**
     * 引擎未接线（R205 语义）：纯时间窗全量查询等待 R202 共享原语落地——
     * v1 只有关键词路径可用，关键词为空时诚实返回本态而非装作无结果。
     */
    data object EnginePending : SourceOutcome

    /** 失败（索引未建/查询异常），reason 供 UI 引导（中文、可直接上屏） */
    data class Failed(val reason: String) : SourceOutcome
}

// ---------------- 研究引擎（纯函数，无 Android 依赖） ----------------

/**
 * 事件研究引擎：窗过滤 → 时间轴分桶 → 共现簇分析。
 * 全部纯函数；唯一 KDoc 出口给 VM part（FeedEventResearchPart）与单测共用，
 * UI 不直接调（VM 把 [EventResearchResult] 状态化后上屏）。
 */
object EventResearcher {

    /**
     * 窗过滤（含频道集合）：dateEpochSec ∈ [from, to] 且（chatIds 空 = 不限 /
     * 非空 = 只留集合内频道）。dateEpochSec<=0（老收藏缺省 0）视为无时间证据，
     * 不进研究流（时间轴上没有诚实位置）。
     */
    fun filterWindow(items: List<VideoItem>, query: EventResearchQuery): List<VideoItem> =
        items.filter { item ->
            val t = item.dateEpochSec.toLong()
            t > 0L && t >= query.fromEpochSec && t <= query.toEpochSec &&
                (query.chatIds.isEmpty() || item.chatId in query.chatIds)
        }

    /**
     * 时间轴分桶：把 [fromSec, toSec] 等宽切 [maxBuckets] 桶（不足整除时末桶吸收
     * 余量）。桶粒度自适应：
     *  - 窗 ≥ 90 天 → 按月标签（yyyy-MM）；窗 ≥ 2 天 → 按日（yyyy-MM-dd）；
     *  - 更短 → 按小时（MM-dd HH 时）——短窗研究（如 72h 事件窗）能看清逐时节奏。
     * 输出恒为 [maxBuckets] 桶（含空桶：时间轴要的是「哪里密哪里空」的形状，
     * 空桶是形状的一部分，不是噪声）。
     */
    fun bucketTimeline(
        items: List<VideoItem>,
        fromSec: Long,
        toSec: Long,
        maxBuckets: Int = 24,
    ): List<TimelineBucket> {
        if (maxBuckets <= 0 || toSec <= fromSec) return emptyList()
        val width = (toSec - fromSec + maxBuckets - 1) / maxBuckets
        val counts = IntArray(maxBuckets)
        for (item in items) {
            val t = item.dateEpochSec.toLong()
            if (t < fromSec || t > toSec) continue
            val idx = ((t - fromSec) / width).toInt().coerceIn(0, maxBuckets - 1)
            counts[idx]++
        }
        val spanSec = toSec - fromSec
        val fmt = java.text.SimpleDateFormat(
            when {
                spanSec >= 90L * 86400 -> "yyyy-MM"
                spanSec >= 2L * 86400 -> "yyyy-MM-dd"
                else -> "MM-dd HH 时"
            },
            java.util.Locale.CHINA,
        )
        return (0 until maxBuckets).map { i ->
            val f = fromSec + i * width
            val t = if (i == maxBuckets - 1) toSec + 1 else f + width
            TimelineBucket(
                fromSec = f,
                toSec = t,
                count = counts[i],
                label = fmt.format(java.util.Date(f * 1000L)),
            )
        }
    }

    /**
     * 共现簇分析（事件传播路径）：
     *  1. 指纹：fileUniqueId 非空 → 精确指纹（同片转发，唯一可靠证据）；空 →
     *     [AdPatternFilter.normalizeCaption] 归一化 caption（小写+数字折叠+去空白，
     *     「第03集/第12集」同模板——单一实现不镜像）且长度 ≥ [MIN_TEMPLATE_FINGERPRINT_LEN]
     *     才参与（短话术「感谢关注」跨频道不构成事件证据，farm 判定同款下限思想）；
     *  2. 同指纹按时间升序排：相邻时间差 > [query.coOccurrenceWindowHours] 断簇
     *     （同一内容隔一年再传 = 两个事件，不是一条传播路径）；
     *  3. 断出的段内去重频道数 ≥ [query.minClusterChannels] 才成簇；
     *  4. 簇排序：频道数降序 → 首发时间降序（大事件在前，同期在前）。
     *  注意：单一来源（单频道）的模板指纹重复发布会被 minClusterChannels 闸拦下
     *  （不算事件）；纯 fileUniqueId 的同频道重发同理由闸拦截。
     */
    fun buildClusters(items: List<VideoItem>, query: EventResearchQuery): List<EventCluster> {
        data class Tagged(val item: VideoItem, val fp: String, val exact: Boolean)
        val tagged = ArrayList<Tagged>(items.size)
        for (item in items) {
            val fu = item.fileUniqueId
            if (fu.isNotEmpty()) {
                tagged += Tagged(item, "u:$fu", true)
                continue
            }
            val norm = AdPatternFilter.normalizeCaption(item.caption)
            if (norm.length >= MIN_TEMPLATE_FINGERPRINT_LEN) {
                tagged += Tagged(item, "t:$norm", false)
            }
        }
        val windowSec = query.coOccurrenceWindowHours * 3600L
        val clusters = ArrayList<EventCluster>()
        tagged.groupBy { it.fp }.forEach { (_, group) ->
            val sorted = group.sortedBy { it.item.dateEpochSec.toLong() }
            var start = 0
            for (i in 1..sorted.size) {
                val breakChain = i == sorted.size ||
                    (sorted[i].item.dateEpochSec.toLong() - sorted[i - 1].item.dateEpochSec.toLong()) > windowSec
                if (breakChain) {
                    val segment = sorted.subList(start, i)
                    start = i
                    val channels = segment.map { it.item.chatId }.distinct()
                    if (channels.size < query.minClusterChannels) continue
                    val first = segment.first().item
                    val firstSec = first.dateEpochSec.toLong()
                    clusters += EventCluster(
                        fingerprint = segment.first().fp,
                        exact = segment.first().exact,
                        representativeTitle = first.caption.lineSequence()
                            .firstOrNull { it.isNotBlank() }?.trim()?.take(60)
                            ?: first.channelTitle,
                        firstEpochSec = firstSec,
                        relays = segment.map { (item, _, _) ->
                            EventRelay(
                                item = item,
                                offsetHours = (item.dateEpochSec.toLong() - firstSec) / 3600L,
                                isFirst = item === first,
                            )
                        },
                    )
                }
            }
        }
        return clusters.sortedWith(
            compareByDescending<EventCluster> { it.channelCount }.thenByDescending { it.firstEpochSec },
        )
    }

    /**
     * 主流程（VM part 调用）：源拉取 → 窗过滤（引擎兜底——源返回未过滤集也能保证
     * 口径一致）→ 时间轴 + 共现簇。源三态直通上层（EnginePending/Failed 不吞）。
     */
    suspend fun research(query: EventResearchQuery, source: EventResearchSource): SourceOutcomeOrResult {
        return when (val outcome = source.loadWindow(query)) {
            is SourceOutcome.EnginePending -> SourceOutcomeOrResult.EnginePending
            is SourceOutcome.Failed -> SourceOutcomeOrResult.Failed(outcome.reason)
            is SourceOutcome.Items -> {
                val windowed = filterWindow(outcome.items, query)
                SourceOutcomeOrResult.Ready(
                    EventResearchResult(
                        query = query,
                        items = windowed.sortedBy { it.dateEpochSec.toLong() },
                        clusters = buildClusters(windowed, query),
                        engineNote = outcome.note,
                    ),
                )
            }
        }
    }

    /** 模板指纹最短长度（归一化后）：短于它不构成「同内容」证据（farm 下限同思想） */
    const val MIN_TEMPLATE_FINGERPRINT_LEN = 12
}

/** research 的直通三态（对齐 [SourceOutcome]，Ready 里才是可渲染结果） */
sealed interface SourceOutcomeOrResult {
    data class Ready(val result: EventResearchResult) : SourceOutcomeOrResult
    data object EnginePending : SourceOutcomeOrResult
    data class Failed(val reason: String) : SourceOutcomeOrResult
}

/**
 * v1 生产数据源（关键词路径）：[DeepSearchIndex.search] fts 召回 + Kotlin 侧窗过滤。
 *
 * R236：#话题关键词改走 [DeepSearchIndex.tagItems]（v9 tags 表倒排，毫秒级——
 * LIKE 设备旧路 3-8 分钟；分路尺子=SearchTextKit.isHashtagQuery 与 search() 内部
 * 路由同源）。非 # 关键词照旧 fts 词位召回。
 *
 * - 关键词为空 → [SourceOutcome.EnginePending]（纯时间窗查询等 R202——诚实态，
 *   绝不静默拿「无结果」冒充「窗口里没内容」）；
 * - 索引未建（hasIndex()=false）→ [SourceOutcome.Failed]（R183 防误建库纪律：
 *   绝不为研究器触发 openDb 建库/迁移扫描；引导文案指路设置页深索引）；
 * - 召回后窗过滤/频道集合过滤由引擎 [EventResearcher.filterWindow] 兜底（本类
 *   只做召回，过滤口径单一定义在引擎）；
 * - fuzzy 固定 false：研究场景要精确词位 AND（模糊召回会稀释共现簇的证据强度）。
 *
 * R202 接线后（下轮）：本类保留为关键词路径，新增 windowOnly 源（挂
 * [EventResearchSource]）承担「无关键词全窗横截面」——届时 keywords 为空不再
 * 返回 EnginePending 而走新源。
 */
class FtsKeywordWindowSource(
    private val deepIndex: DeepSearchIndex,
) : EventResearchSource {

    override suspend fun loadWindow(query: EventResearchQuery): SourceOutcome {
        val kw = query.keywords.trim()
        if (kw.isEmpty()) return SourceOutcome.EnginePending
        if (!deepIndex.hasIndex()) {
            return SourceOutcome.Failed("深索引还没建立——先在设置里开启深度索引积累数据")
        }
        // R236：#话题查询走 tags 表倒排（tagItems 毫秒级——LIKE 设备旧路 3-8 分钟的
        // 根治件；分路口径=SearchTextKit.isHashtagQuery 与 search() 内部路由同一把
        // 尺子，「#tag 合集」类带空白查询不误入 hashtag 语义）。非 # 查询照旧词位
        // 精确匹配。覆盖口径：item_tags 已解析行（R228 回填推进中暂缺的老条目
        // 随回填收敛；超长 caption 截断/混合相册空 caption 成员两类旧缺口顺带根治）。
        val isHashtag = SearchTextKit.isHashtagQuery(kw)
        // R267：tag_items 覆盖不足兜底（真机实锤：导入存量库两回填在途 → tagItems 召回 0
        // → 时间轴页空白误报「本地索引里还没有」）。倒排空 ∧ 覆盖在途（断点 done=false）
        // → LIKE 兜底（searchHashtag 同口，慢但诚实——Loading 态挂着，比空白强）；
        // 覆盖收敛（done=true）后空=真空，不白付全表扫。
        // R269：召回口径收敛共享件 [recallTagContent]（tag_items 倒排+LIKE 兜底同一把
        // 尺子，行星内容流同源；tagItems/search 内部 runCatching 已收敛空表——原两处分
        // 支 runCatching→Failed 为不可达防御位，行为逐位保持：召回集/兜底标志/note 不变）。
        var hashtagLikeFallback = false
        val found = if (isHashtag) {
            val recall = recallTagContent(deepIndex, kw)
            hashtagLikeFallback = recall.likeFallback
            recall.items
        } else {
            runCatching { deepIndex.search(kw, false) }
                .getOrElse {
                    android.util.Log.w(TAG, "事件研究器 fts 召回失败: ${it.message}")
                    return SourceOutcome.Failed("索引查询失败：${it.message ?: "未知错误"}")
                }
        }
        return SourceOutcome.Items(
            items = found,
            note = if (isHashtag) {
                if (hashtagLikeFallback) {
                    "关键词召回路径：tags 覆盖回填在途，走词位 LIKE 兜底（召回 ${found.size} 条，" +
                        "全史时间轴按实际跨度分桶；回填收敛后自动切回毫秒级倒排——诚实口径）"
                } else {
                    "关键词召回路径：深索引 tags 表倒排（item_tags 元素级精确，召回 ${found.size} 条，" +
                        "全史时间轴按实际跨度分桶；怪兽频道月采样区历史可能偏稀——诚实口径）"
                }
            } else {
                "关键词召回路径：深索引词位精确匹配（召回 ${found.size} 条，" +
                    "时间窗/频道集合为本地过滤；怪兽频道月采样区历史可能偏稀——诚实口径）"
            },
        )
    }

    private companion object {
        const val TAG = "EventResearch"
    }
}
