package dev.tgfeed.core.feed

// ---------------- R398 播放编排四期 #10（队列语义跳转）core 纯件 ----------------
//
// 「跳到讲…」的语义面（r398_notes §3.1 定案）：电台心智——用户开着队列按序
// 听，听到一半想直接跳到「讲某话题的那条」，不逐条手动快进。
//
// 纯 Kotlin 零 Android 依赖（JVM 单测直测，PlayChain/TodayQueueGen 同纪律）：
//  - [findNextSemanticMatch]：caption 本地包含匹配（大小写不敏感）零网络——
//    匹配面全在条目本地字段，无任何拉取（#10 的「零网络」承诺由签名兑现：
//    纯函数无 gateway 参数）；
//  - [dropPlanPrefixBefore]：跳播段前缀剪除（深位保落判词——匹配段前移首页，
//    一期断点 6s 跳回链复用落点）。
//
// 编排接线（VM 层）见 FeedQueueJumpPart.kt（笔4）；UI 搜索条见 QueueScreen
// （「跳到讲…」搜索条/跳播钮/无果 Toast）。

/**
 * 队列语义跳转匹配（纯函数，r398_notes §3.1）。
 *
 * 语义判词（逐条在案）：
 *  - **caption 本地包含匹配大小写不敏感**：`Item 条目的 caption` 整段
 *    contains(query, ignoreCase)——caption=条目自带文本（落盘口径，
 *    LocalStore toJson 恒保留），本地可得零网络；
 *  - **query 两端 trim，trim 后空 = null**（无操作诚实——空搜索不匹配任何
 *    条目，含空白字符的 query 按剥后词匹配）；
 *  - **跳过条目不参与**（skipped=true=播放期排除，buildPlayPlan 构建期
 *    不进装配流——跳到它=开单不出现，诚实不匹配）；
 *  - **Channel 条目永不匹配**（频道段条目要现拉，本地只有 chatId+title
 *    两个字段零内容——零网络承诺下频道条目的「讲什么」无从判定，语义
 *    跳转诚实缺席归开台链；title 匹配=台名匹配非内容匹配，不做）；
 *  - **不回绕**（扫描 [fromIndex, size) 单向——尾部之后无果即 null，不
 *    循环不偷跑头部；「跳到讲X」找不到=诚实无果，UI Toast 如实）；
 *  - **多重命中取首个**（扫描序=条目序=手排序，所见即所得）。
 *
 * @param entries 队列条目（活跃台 entries 原序——UI 列表所见序）
 * @param query 搜索词（「跳到讲…」搜索条输入；trim 后空=null）
 * @param fromIndex 起扫下标（VM 侧=断点后位：resumeEntryId 命中取其下标+1，
 *   无断点/失联取 0——「跳到」从当前播位向后找；负数收敛 0，越界=空扫 null）
 * @return 命中的单条条目（跳播落点=entryId+item.key 双凭据）；null=无果诚实
 */
fun findNextSemanticMatch(
    entries: List<PlayQueueEntry>,
    query: String,
    fromIndex: Int,
): PlayQueueEntry.Item? {
    val needle = query.trim()
    if (needle.isEmpty()) return null
    var i = fromIndex.coerceAtLeast(0)
    while (i < entries.size) {
        val e = entries[i]
        if (e is PlayQueueEntry.Item && !e.skipped &&
            e.item.caption.contains(needle, ignoreCase = true)
        ) {
            return e
        }
        i++
    }
    return null
}

/**
 * 跳播段前缀剪除（纯函数，r398_notes §3.2 深位保落判词）。
 *
 * 一期断点跳回=等 key 进**已载页**（6s 有界，openPlayQueueImpl）——深位
 * （未拉页）落空诚实回顶；语义跳转到深位条目时把匹配段**之前的段剪除**，
 * 匹配段前移首页→跳回链原样复用落点（「复用既有跳转链」字面兑现）。
 *
 * 语义判词：
 *  - entryId=null → 原样返回（**一期开单行为逐字零变**——默认路径零改）；
 *  - 命中段前的段（含其条目）剪除=跳播=跳过这些段本轮不再播（电台「快进
 *    到讲X的那段」心智；条目实体不删——QueueScreen 所见条目原样，仅本轮
 *    在播流剪除，快照语义一期判词同款）；
 *  - TIME 轮式同接线：剪除后匹配段在首轮轮转必在场（单条段首轮即尽），
 *    跳回链同生效；
 *  - 失联（entryId 不在任何段——队列编辑换代防御）→ 原样返回（诚实降级
 *    全量开单，跳回落空诚实回顶兜底）。
 *
 * @param plan buildPlayPlan 产物（开单快照段列表）
 * @param entryId 跳播目标段条目 id（findNextSemanticMatch 命中条目的 id）；
 *   null=普通开单不剪
 */
fun dropPlanPrefixBefore(plan: List<PlaySegment>, entryId: String?): List<PlaySegment> {
    if (entryId == null) return plan
    val idx = plan.indexOfFirst { it.entryId == entryId }
    if (idx <= 0) return plan // 失联/已在首位=原样（首位无前缀可剪）
    return plan.drop(idx)
}
