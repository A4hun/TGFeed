package dev.tgfeed.core.feed

import dev.tgfeed.core.td.TdLibGateway

// ---------------- R372 「群友正在搜」雷达（纯被动呈现层，发现页） ----------------

/**
 * R372 雷达频道卡模型：「群友正在搜 XXX，返回了 YYY 频道」的 Y 侧只读投影。
 *
 * 数据全部来自本地收割沉淀（[AnswerStore] 答案库的该热词结果行），零网络零新解析：
 *  - [chatId]/[title]/[username]/[members]：答案行既有字段（hintTitle=bot 应答按钮
 *    配对出的频道名线索，members=应答行尾/网页预览解析产物，0=未知不展示）；
 *  - [hits]：该（词→频道）答案被群友搜出的累计次数（「N 次命中」证据行原料）；
 *  - [lastTs]：最近一次命中时刻（「最近命中」证据行原料，相对时间文案沿用
 *    DiscoverScreen 既有的 freshAgeLabel 相对档文案件——不加第三份相对时间件）；
 *  - [word]：来源热词展示原文（词→频道配对的用户原话口径锚）。
 */
data class RadarChannelCard(
    val chatId: Long,
    val title: String,
    val username: String,
    val members: Int,
    val hits: Int,
    val lastTs: Long,
    val word: String,
)

/**
 * R372 雷达频道卡聚合器（纯本地读，零网络零预算零新解析口——纯被动红线）：
 * 热词榜头部词 → [AnswerStore.query] 该词答案（hits/lastTs 降序）→ 每词取头部
 * 一张过闸卡（词→频道配对的「返回了 YYY」侧）。
 *
 * 诚实门三闸（不过闸不推，宁缺毋滥）：
 *  1. [RadarChannelCard.chatId]==0 的用户名链接（未解析）不推——chatId 直开链路
 *     不可达（解析归 EntityResolver 既有管线，本批零新增解析口）；
 *  2. [RadarChannelCard.title] 空白（无 hintTitle 线索）不推——裸 id 无名卡不冒充；
 *  3. 已加入频道不推（[joinedNow] 弱集合=GetChats 前缀∪订阅集，与搜索页答案区
 *     「投诉2」同口径：指向已加入频道的答案没有增量意义；宁松勿紧的既有语义）。
 * 另过 R211 非频道闸（[EntityResolver.resolvedNonChannelChatIds]——@人提及解析
 * 产物不冒充频道卡），与宝藏二信号聚合同口径。
 *
 * 一词一卡（query 已按 hits/lastTs 降序，头部即该词最热答案）；chatId 去重
 * （首词优先，后来的词只剩已上榜频道时自然贡献为零）；上限
 * [DISCOVERY_RADAR_CARD_LIMIT]。热词行空=调用侧整段隐藏（诚实空态）。
 */
internal fun FeedViewModel.buildRadarCards(
    real: TdLibGateway?,
    hotWords: List<Pair<String, Int>>,
    joinedNow: Set<Long>,
): List<RadarChannelCard> {
    if (real == null || hotWords.isEmpty()) return emptyList()
    val store = runCatching { real.groupHarvester.answerStore }.getOrNull() ?: return emptyList()
    val nonChannelIds = runCatching {
        real.groupHarvester.entityResolver.resolvedNonChannelChatIds()
    }.getOrDefault(emptySet())
    val out = ArrayList<RadarChannelCard>(DISCOVERY_RADAR_CARD_LIMIT)
    val seenChatIds = HashSet<Long>(DISCOVERY_RADAR_CARD_LIMIT * 2)
    for ((word, _) in hotWords) {
        if (out.size >= DISCOVERY_RADAR_CARD_LIMIT) break
        val answers = runCatching { store.query(word) }.getOrDefault(emptyList())
        for (a in answers) {
            // 诚实门三闸 + 非频道闸 + chatId 去重（单源实现于本函数，UI 只读结果）
            if (a.chatId == 0L) continue
            if (a.chatId in nonChannelIds) continue
            if (a.hintTitle.isBlank()) continue
            if (a.chatId in joinedNow) continue
            if (!seenChatIds.add(a.chatId)) continue
            out += RadarChannelCard(
                chatId = a.chatId,
                title = a.hintTitle,
                username = a.username,
                members = a.members,
                hits = a.hits,
                lastTs = a.lastTs,
                word = word,
            )
            break // 一词一卡：该词最热答案已上榜，下一词继续
        }
    }
    return out
}

// ---------------- R372 雷达常量（FeedViewModel/DiscoverScreen 配套，文件级私有） ----------------

/**
 * R372 「群友正在搜」热词行统计窗：48h=GoldmineHooks 热词留存窗（HOT_RETENTION_MS）
 * 的满档——旧 24h 定窗只用到留存一半，收割断流一天即整区全黑（16384 实锚：24h 窗
 * 空而 48h 留存内有 377 个活跃词在库睡觉）。标题窗标签随窗诚实标注（「近 2 天」）。
 * 注意：顶部统计胶囊「今日收割 X 词」仍走 [DISCOVERY_HOT_WORD_WINDOW_MS]（24h
 * 今日口径）——统计窗与雷达窗分轨，各说各话。
 */
internal const val DISCOVERY_RADAR_WORD_WINDOW_MS = 48L * 3600 * 1000

/** R372 雷达频道卡出卡上限（横滑卡行；一词一卡=与热词行头部词数同量级） */
internal const val DISCOVERY_RADAR_CARD_LIMIT = 12
