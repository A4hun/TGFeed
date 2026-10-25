package dev.tgfeed.core.feed

// ---------------- R167 #话题搜索入口（FeedViewModel 扩展件，实现见本文件） ----------------

/**
 * R167 #话题搜索入口：caption #标签点击的语义是"全 TG 频道带该标签的内容"。
 *
 * 为什么不走 deepSearch/服务端搜索（R6 老接线根因）：
 *  - 服务端 SearchMessages(ChatListMain) 只搜**已加入聊天**——Feed 信息流大量内容
 *    来自深索引爬取的**未加入频道**，标签所在频道没加入 → 服务端零命中（用户
 *    实锤"什么都搜不到/当前源没有资源"）；
 *  - [DeepSearchFeedSource] 纯深索引单腿：已加入但爬取尚浅的频道内容也搜不到；
 *    本入口改用 [HashtagSearchFeedSource] 混合双腿（深索引 hashtag 精确词主路 +
 *    服务端已加入聊天补位，分页模型见该类注释）。
 *
 * 线纪律（R165 口径）：本函数主线程段零 IO——源构造零 IO（深索引腿 async 即起、
 * 服务端腿在 nextPage 内走），switchSource 是切源家族既有结构（内存复位 +
 * loadMore launch(IO)，锁图见 anr-analysis.md §4.1，切源打点已入）。
 *
 * 升档（R163 B4/机制③）：onDeepHits 回调在深索引腿完成时（IO 线程）直调共享内核
 * [promoteHitChannelsImpl]——**零额外全表扫**。R278 起普通深搜（deepSearchImpl）
 * 同款回调制（DeepSearchFeedSource.onHits 复用本次 search 结果），复查式升档
 * （search() 再跑一遍，LIKE 设备=第二个 45s）已整体拆除。
 */
internal fun FeedViewModel.hashtagSearchImpl(tag: String) {
    val t = tag.trim().trimStart('#', '＃')
    if (t.isEmpty()) return
    switchSource(
        HashtagSearchFeedSource(
            deepIndex,
            gateway,
            t,
            onDeepHits = { hits -> promoteHitChannelsImpl(hits, "#$t") },
        ),
    )
}

/**
 * R269 行星内容流（星图点行星/飞入门「看内容」行 → 该 tag 全宇宙内容直刷）：
 * [TagContentFeedSource] 纯本地源（tag_items 倒排毫秒级 + R267 LIKE 兜底——
 * 零 Search 族，星图语境红线），与上方混合双腿搜索（含服务端补位）分工并存。
 *
 * 线纪律同 hashtagSearchImpl（R165 口径）：本函数主线程段零 IO——源构造零 IO，
 * 召回在 nextPage 内（VM loadMore 的 IO 调度）；switchSource 是切源家族既有结构。
 */
internal fun FeedViewModel.playTagContentImpl(tag: String) {
    val t = tag.trim().trimStart('#', '＃')
    if (t.isEmpty()) return
    switchSource(TagContentFeedSource(deepIndex, t))
}
