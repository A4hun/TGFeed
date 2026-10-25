package dev.tgfeed.core.feed

import dev.tgfeed.core.td.FeedPage

/**
 * R212 频道主页分页决策（纯函数，零依赖）：网络页结果 → 本轮收尾动作。
 *
 * 背景（用户走查实锤「上滑到底不触发继续加载」的根因族）：
 *  1. 旧版 loadMore 把 nextPage 的**任何异常**吞成「空页 + null 游标」→ `exhausted=true`
 *     一次性网络抖动（限流/超时/Chat not found 自愈失败）就**永久断更**；
 *  2. 旧版 nextPage 无界等待：网关链路里 channelTitle/fetchCommentVideos 的裸
 *     client.send 无超时，原生管线拥塞时可挂数分钟——`loading` 卡真 → 触发器
 *     的加载闸永久早退（实机实锤：状态行「正在加载…」常驻、logcat 零请求）；
 *  3. 触发器 `LaunchedEffect(shouldLoadMore)` 只在翻转时发射：一轮失败后
 *     shouldLoadMore 保持 true 不再翻转 → 永不重试（「到底」与「失败」都死）。
 *
 * 本文件只做**决策**（网络页 → 并谁/要不要索引兜底/算不算到货）；状态写回与
 * 索引取页由 UI 侧调用方执行（见 ChannelHomeScreen.loadMore）。
 */
object ChannelHomePaging {

    /** 网络页拉取上限（旧版口径不变） */
    const val PAGE_LIMIT = 30

    /** 网络页有界等待：超时=本轮失败（失败≠到底，触发器可重试）；比内层 10s×多轮宽裕、比拥塞挂死短得多 */
    const val NET_PAGE_TIMEOUT_MS = 20_000L

    /** 深索引分页粒度：首屏种子与补页同宽（有界查询，回表行数恒 ≤ 本值） */
    const val INDEX_PAGE = 60

    /** 一轮收尾的决策产物（字段语义见 [decide]） */
    data class Decision(
        /** true = 网络页有条目，按旧口径并入（网络字段全，按 key 覆盖升级索引薄条目） */
        val mergeNet: Boolean,
        /** true = 网络这轮没到货（超时/异常/空页），需要索引补页兜底（余量由调用方判断） */
        val fetchIndex: Boolean,
        /** 网络源报告到底（nextCursor == null）——真「到底了」还要索引侧同步取尽才成立 */
        val netDone: Boolean,
    )

    /**
     * 网络优先、索引兜底的翻页决策：
     *  - [net] == null（超时/异常）→ 失败轮：不推进到底、请求索引兜底、不算到货；
     *  - 有条目 → 正常轮：并网络页（索引偏移不动——网络是权威供给）；
     *  - 空页 + 游标在 → 过路轮（服务端偶发少给）：请求索引兜底但不判到底；
     *  - 空页 + 游标 null → 网络到底：请求索引兜底（还有就继续供给），netDone=true。
     *
     * 「到货」（pageArrivals 再装填键）由调用方按**实际并入条目数**记次——纯失败轮
     * 不记次（防失败风暴），并入新货即记（触发器在 shouldLoadMore 仍真时自动续拉，
     * 「可一直滑」的填坑行为）。
     */
    fun decide(net: FeedPage?): Decision = when {
        net == null -> Decision(mergeNet = false, fetchIndex = true, netDone = false)
        net.items.isNotEmpty() -> Decision(mergeNet = true, fetchIndex = false, netDone = net.nextCursor == null)
        else -> Decision(mergeNet = false, fetchIndex = true, netDone = net.nextCursor == null)
    }
}
