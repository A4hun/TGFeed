package dev.tgfeed.core.feed

/**
 * R197 群选择器行装配（用户裁决「改成用户手动选，不要替他筛选」的纯函数核心）：
 * 关键词检测降级为**提示**（「建议」角标），列表展示用户**全部已加入群**（不预过滤），
 * 勾选/取消经 GroupHarvester 既有 [GroupHarvester.setUserGroupSelected] 写口（生效链路零改动）。
 *
 * 语义细则（用户裁决 + 历史勾选项不消失的兼容口径）：
 *  - **不预过滤**：每个群候选都进列表——建议（关键词规则命中）只是角标，不是门票；
 *  - **频道例外**：频道（isChannel=true）不是用户语义里的「群」，默认不列；但**建议命中的
 *    频道**（旧自动发现规则本就含频道名命中）与**已勾选的频道**（旧设置卡勾过的）保留
 *    显示——旧可勾项在新列表里绝不无声消失；
 *  - **已勾选但枚举缺席**（退群/会话读不到）：追加占位行（成员数 0、missingFromList=true，
 *    UI 标「不在群列表」）——用户能看见并取消勾选，不产生幽灵勾选；
 *  - **排序**：已勾选（用户确认过的，一屏即见）→ 建议（关键词命中，次显眼）→ 其余
 *    （按枚举序）；同档内保持输入序。
 */
data class HarvestGroupCandidate(
    /** 会话 chatId（GetChatHistory 收割入参同口径） */
    val chatId: Long,
    /** 群标题（GetChat.title） */
    val title: String,
    /** ChatTypeSupergroup && isChannel（true=频道 false=群组；基础群恒 false） */
    val isChannel: Boolean,
    /** 成员数（GetSupergroup/GetBasicGroup.memberCount；读不到 0=未知，UI 不渲染该段） */
    val memberCount: Int,
)

/** 选择器展示行（[buildHarvestGroupRows] 输出）：候选档 + 建议/勾选两个标注位 */
data class HarvestGroupRow(
    val chatId: Long,
    val title: String,
    val isChannel: Boolean,
    val memberCount: Int,
    /** 建议角标：关键词规则（自动发现缓存 ∪ 群名现算）命中——只是提示，不筛列表 */
    val suggested: Boolean,
    /** 用户已勾选纳入收割（prefs 勾选集） */
    val selected: Boolean,
    /**
     * 建议归因 src（写回勾选时透传给 setUserGroupSelected 做诊断溯源）：
     * 自动发现缓存命中的沿用其 src（name=群名关键词/desc=群简介玩法说明）；
     * 群名现算命中给 "name"；非建议行 ""（勾选时调用方落 "user"）。
     */
    val suggestedSrc: String,
    /** 已勾选但本轮群枚举缺席（退群/会话异常）——UI 行尾标「不在群列表」，仍可取消勾选 */
    val missingFromList: Boolean,
)

/**
 * 装配选择器行列表（单测锚点：选择器状态纯函数，零 Android 依赖）。
 *
 * @param candidates 网关全量群枚举（LoadChats 全量加载 + GetChat 分类，含频道）
 * @param selected GroupHarvester 勾选集（prefs JSON 读侧，纯读）
 * @param detected 自动发现缓存里检测出的群组类实体（src=name/desc——关键词规则结论，
 *   只作建议角标输入，**不参与行过滤**）
 * @param titleSuggests 群名关键词现算判定（GroupHarvester.looksLikeSearchGroupByTitle
 *   同套规则；默认恒 false 便于测试与降级调用）
 */
internal fun buildHarvestGroupRows(
    candidates: List<HarvestGroupCandidate>,
    selected: List<SearchEntity>,
    detected: List<SearchEntity>,
    titleSuggests: (String) -> Boolean = { false },
): List<HarvestGroupRow> {
    val selectedById = LinkedHashMap<Long, SearchEntity>()
    for (e in selected) if (e.chatId != 0L) selectedById[e.chatId] = e
    // 建议集 = 自动发现缓存命中 ∪ 群名规则现算命中（新加入的群不等 1h 重扫即可见角标）
    val suggestedIds = HashSet<Long>()
    val suggestedSrcById = HashMap<Long, String>()
    for (e in detected) {
        if (e.chatId == 0L) continue
        suggestedIds += e.chatId
        suggestedSrcById[e.chatId] = e.src
    }
    for (c in candidates) {
        if (c.chatId in suggestedIds || !titleSuggests(c.title)) continue
        suggestedIds += c.chatId
        suggestedSrcById[c.chatId] = "name" // 现算命中即群名规则（name 口径）
    }
    // 行装配：频道只有建议/已勾才保留（用户语义「群」+ 旧勾选项不消失）；
    // chatId=0 是坏行（setUserGroupSelected 同口径拒写），防御性跳过
    data class Slot(val row: HarvestGroupRow, val rank: Int)
    val out = ArrayList<Slot>(candidates.size + selectedById.size)
    for (c in candidates) {
        if (c.chatId == 0L) continue
        val sel = c.chatId in selectedById
        val sug = c.chatId in suggestedIds
        if (c.isChannel && !sel && !sug) continue
        val rank = when {
            sel -> 0
            sug -> 1
            else -> 2
        }
        out += Slot(
            HarvestGroupRow(
                chatId = c.chatId,
                title = c.title,
                isChannel = c.isChannel,
                memberCount = c.memberCount,
                suggested = sug,
                selected = sel,
                suggestedSrc = suggestedSrcById[c.chatId] ?: "",
                missingFromList = false,
            ),
            rank,
        )
    }
    // 已勾选但枚举缺席：追加占位行（rank 0 与其他已勾同档；防幽灵勾选）
    for (e in selectedById.values) {
        if (out.any { it.row.chatId == e.chatId }) continue
        out += Slot(
            HarvestGroupRow(
                chatId = e.chatId,
                title = e.title,
                isChannel = false,
                memberCount = 0,
                suggested = e.chatId in suggestedIds,
                selected = true,
                suggestedSrc = suggestedSrcById[e.chatId] ?: "",
                missingFromList = true,
            ),
            0,
        )
    }
    // 档内稳定排序（Kotlin sortedBy 是稳定排序：同档保持输入序）
    return out.sortedBy { it.rank }.map { it.row }
}
