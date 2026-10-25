package dev.tgfeed.core.feed

import dev.tgfeed.core.td.FeedPage
import dev.tgfeed.core.td.FeedRequest
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.VideoItem

/**
 * 关键词订阅 Feed（"订阅一个词"）：nextPage 时对词表里每个关键词各走一次网关搜索
 * （真实网关 = TDLib SearchMessages 全局搜索，channelId=null；Mock = 本地 caption 过滤），
 * 跨词按 item.key 去重合并后按发布时间倒序返回——对应设置页"订阅后相关新内容进追更源"的入口源。
 *
 * 游标模型：与 CollectionFeedSource 同思路——各关键词的搜索游标互不兼容
 * （真实网关是 "g:" 前缀的不透明 offset，Mock 是 "o:N"），故游标状态放内存按词分开维护
 * （[termCursors]/[termEnded]），外层 FeedPage.nextCursor 只是"是否到底"的信号：
 * 全部词都到底 → null；否则返回占位串（内容无意义，翻页状态都在本类内部）。
 *
 * 单词失败（网络/限流）只跳过该词、保留其游标（下次 loadMore 自动重试），不影响其他词。
 * 多取截断：各词配额均分会多取一点，合并排序后截断到 limit（多出的条目随游标前进被丢弃，
 * 与 CollectionFeedSource 的取舍一致）。
 */
class KeywordSubscriptionSource(
    private val gateway: TdGateway,
    keywords: List<String>,
) : FeedSource {

    // 与搜索/聚合同为"跨频道"语义：无单一频道；searchQuery 不挂在 request 上
    // （request 只承载单关键词，多词并行搜索由 nextPage 内部分派）
    override val request: FeedRequest = FeedRequest()

    /** 有效词表：构造时去空白、去空串、去重，后续翻页不再变 */
    private val terms: List<String> = keywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** 各关键词的搜索游标：缺省 = 首页；值 = 网关返回的下一页游标（原样透传的不透明串） */
    private val termCursors = LinkedHashMap<String, String>()

    /** 已到底的关键词集合（nextCursor == null 时登记） */
    private val termEnded = HashSet<String>()

    override suspend fun nextPage(limit: Int): FeedPage {
        if (terms.isEmpty()) return FeedPage(emptyList(), null)
        // 每词本轮配额：均分一页，至少 4 条（与 CollectionFeedSource/网关聚合的策略一致）
        val perTerm = maxOf(4, limit / terms.size)
        // key 级去重合并：同一片子命中多个词只留一条（LinkedHashMap 保留先到者）
        val merged = LinkedHashMap<String, VideoItem>()
        for (term in terms) {
            if (term in termEnded) continue
            val page = runCatching {
                gateway.fetchVideos(FeedRequest(searchQuery = term), termCursors[term], perTerm)
            }.getOrNull() ?: continue // 单词失败只跳过该词，保留游标下轮重试
            page.items.forEach { merged.putIfAbsent(it.key, it) }
            val next = page.nextCursor
            if (next == null) termEnded += term else termCursors[term] = next
        }
        // 按发布时间倒序（dateEpochSec 缺失的老数据为 0，自然沉底）
        val items = merged.values.sortedByDescending { it.dateEpochSec }.take(limit)
        val done = terms.all { it in termEnded }
        return FeedPage(items, if (done) null else CURSOR_MORE)
    }

    override fun reset() {
        termCursors.clear()
        termEnded.clear()
    }

    private companion object {
        /** 外层游标占位串：仅表示"还有词没翻完"，真实翻页状态在 termCursors 内 */
        const val CURSOR_MORE = "more"
    }
}
