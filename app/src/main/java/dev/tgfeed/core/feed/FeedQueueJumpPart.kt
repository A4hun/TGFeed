package dev.tgfeed.core.feed

// ---------------- R398 播放编排四期 #10（队列语义跳转）VM 编排 ----------------
//
// 「跳到讲…」的跳播链（r398_notes §3.2 定案）：**复用既有跳转链**字面兑现——
//   纯函数匹配（QueueSemanticJump.findNextSemanticMatch，零网络）→
//   setResume(matched.id, matched.item.key)（断点=跳播落点，一期语义：
//   跳到=从这继续听）→ openPlayQueueImpl(planFromEntryId=matched.id)
//   （一期开单链+段前缀剪除=匹配段前移首页，**断点 6s 跳回链原样落点**——
//   _items.first{含 key}+迟到守卫全继承，r398_notes §1.3 判词）。
//
// UI 搜索条见 QueueScreen（「跳到讲…」搜索条/跳播钮/无果 Toast）。
// 红线自查：一期 openPlayQueueImpl 语义零改（planFromEntryId=null 默认路径
// 逐字零变）；播放链 UI 四件零碰；R398 件零进 tgfeed-oss。

/**
 * 语义跳转（QueueScreen「跳到讲…」跳播钮）：本地 caption 匹配+跳播开单。
 *
 * 步骤（r398_notes §3.2）：
 *  1. **fromIndex=断点后位**：活跃台 resumeEntryId 命中→其下标+1（当前播位
 *     之后找），无断点/失联→0（从头找）；
 *  2. [findNextSemanticMatch] 纯函数匹配（零网络——即时返回；**null=无果
 *     返回 false**，UI Toast 如实：没找到讲「query」的条目）；
 *  3. 命中→`setResume(matched.id, matched.item.key)`（**断点=跳播落点**，
 *     一期语义：跳到=从这继续听；统一收尾落盘）+探针行（key 尾 12 位计数
 *     口径零内容）；
 *  4. `openPlayQueueImpl(fromStart=false, planFromEntryId=matched.id)`——
 *     段前缀剪除=匹配段前移首页，断点 6s 跳回链复用落点（**复用既有跳转链**；
 *     重开单=快照语义一期判词同款）。
 *
 * @return true=已发起跳播开单（UI 关页回宿主）；false=无果（UI 无果 Toast）
 */
internal fun FeedViewModel.jumpToQueueSemanticImpl(query: String): Boolean {
    val entries = playQueueModel.queue.entries
    // 断点后位：resume 失联(下标-1)经 +1 收敛 0=从头找；断点在末位=size=空扫诚实无果
    val fromIndex = playQueueModel.queue.resumeEntryId
        ?.let { rid -> entries.indexOfFirst { it.id == rid } + 1 }
        ?.coerceAtLeast(0)
        ?: 0
    val matched = findNextSemanticMatch(entries, query, fromIndex)
    if (matched == null) {
        android.util.Log.i(
            "R398",
            "语义跳转:无果 自位=$fromIndex 条目=${entries.size}（计数口径,零内容）",
        )
        return false
    }
    if (playQueueModel.setResume(matched.id, matched.item.key)) afterPlayQueueMutation()
    android.util.Log.i(
        "R398",
        "语义跳转:命中 key尾=${matched.item.key.takeLast(12)} 自位=$fromIndex（计数口径,零内容）",
    )
    // 复用一期开单链（自含 IO 协程+主线程切源+6s 有界跳回+迟到守卫）：
    // 段前缀剪除=匹配段前移首页，断点跳回链原样落点
    openPlayQueueImpl(fromStart = false, planFromEntryId = matched.id)
    return true
}
