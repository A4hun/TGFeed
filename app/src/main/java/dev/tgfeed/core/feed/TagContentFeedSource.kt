package dev.tgfeed.core.feed

import android.util.Log
import dev.tgfeed.core.td.FeedPage
import dev.tgfeed.core.td.FeedRequest
import dev.tgfeed.core.td.VideoItem

/**
 * R269 行星内容流（feed↔星图双向穿梭·第一跃）：星图点行星（tag）→ 该 tag 的
 * 全宇宙内容流直刷（视频/图文；任务书口径——不是先跳时间轴页，时间轴页保留为
 * 弹层二级入口）。
 *
 * 数据源（任务书口径）：tag_items 倒排（R236 v9 元素级倒排，PK 前缀扫+rowid 回表，
 * 毫秒级——LIKE 设备旧路 3-8 分钟的根治件）+ LIKE 兜底（R267 口径：倒排空 ∧
 * 覆盖回填在途 → 全表扫，慢但诚实——Loading 态挂着比空白强）。**纯本地零
 * Search 族**（星图语境 R233/R184 红线）——与 HashtagSearchFeedSource（R167
 * 混合双腿含服务端补位）分工：本流=本地索引里这个 tag 的全部；搜索播放=全网
 * 混合原路径（弹层里保留，整合不是替换）。
 *
 * 语义锚：行星内容流=该 tag 的**全宇宙**内容（tag_items 全局倒排），不是仅宿主
 * 频道的样本——行星绕恒星转因为该 tag 是该频道热词；点尘埃（该频道该 tag 的
 * 样本条目播放）语义不变。
 *
 * 分页模型：召回一次性物化（上限 SEARCH_HARD_CAP=2000）后按页切片（limit/页，
 * 游标到尽即 null）——两千条整批不一页糊给首帧；[reset] 重放**不重启召回**
 * （同词结果幂等，HashtagSearchFeedSource reset 不重启深扫同款取舍）。条目
 * playUri=null，播放走 ensurePlayable 按条现取链（R222b 口径——与深搜索结果
 * 进 Feed 完全同一条已上线链路）。
 *
 * 源标识沿用一次性搜索源约定（request.searchQuery="#tag"——FeedScreen 筛选复位
 * 联动靠它）；面包屑标题由宿主置「标签：#tag」前缀（FeedScreen 空态三件套
 * ——加载中文案/错误/HashtagEmptyCard——随之免费获得，LIKE 兜底的几十秒等待
 * 有诚实承接）。
 *
 * 线纪律（R165/R222b 口径）：构造零 IO（召回在 nextPage 内、VM loadMore 的 IO
 * 调度里跑），switchSource 主线程段零 IO。
 */
class TagContentFeedSource(
    index: DeepSearchIndex,
    tag: String,
) : FeedSource {

    override val request: FeedRequest = FeedRequest(searchQuery = "#$tag")

    /** 分页状态机（纯 JVM 件 [TagContentPager]，单测直测；召回闭包懒执行仅一次） */
    private val pager = TagContentPager {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val recall = recallTagContent(index, tag)
        Log.i(
            "TGFlow",
            "R269 行星内容流召回: #$tag 命中 ${recall.items.size} 条 " +
                "${android.os.SystemClock.elapsedRealtime() - t0}ms" +
                (if (recall.likeFallback) "（LIKE 兜底——覆盖回填在途，慢但诚实）" else "（tag_items 倒排毫秒级）"),
        )
        recall
    }

    override suspend fun nextPage(limit: Int): FeedPage = pager.nextPage(limit)

    override fun reset() = pager.reset()
}

/**
 * R269 tag 内容召回结果：[items]=发布时间倒序召回集；[likeFallback]=true 表示走了
 * R267 LIKE 全表扫兜底（调用方打诚实文案/日志用）。
 */
data class TagContentRecall(val items: List<VideoItem>, val likeFallback: Boolean)

/**
 * R269 tag 内容召回**单一口径**（类收敛件——R267 兜底原物是 FtsKeywordWindowSource
 * 的内联块，本函数把它收敛为共享件，研究器/时间轴与行星内容流同源一把尺子）：
 *
 *  1. tag_items 倒排（[DeepSearchIndex.tagItems]，毫秒级；# 前缀/全角＃/空白归一
 *     在 tagKeyOf 内，直传原词即可）；
 *  2. 召回空 ∧ 覆盖在途（[DeepSearchIndex.tagCoverageLikelyIncomplete]——两回填
 *     断点 done 位，O(1) 文件级）→ LIKE 全表扫兜底（[DeepSearchIndex.search] 的
 *     hashtag 精确词模式，慢但诚实）；覆盖收敛后空=真空，不白付全表扫。
 *
 * 异常口径：tagItems/search 内部均已 runCatching 收敛为空表+记日志——本函数不抛。
 * **请在 IO 线程调用**。
 */
fun recallTagContent(index: DeepSearchIndex, tag: String): TagContentRecall {
    val key = tag.trim().trimStart('#', '＃')
    if (key.isEmpty()) return TagContentRecall(emptyList(), likeFallback = false)
    val viaTags = index.tagItems(key)
    if (viaTags.isNotEmpty()) return TagContentRecall(viaTags, likeFallback = false)
    if (!index.tagCoverageLikelyIncomplete()) {
        return TagContentRecall(emptyList(), likeFallback = false)
    }
    Log.i(TAG, "R267/R269 tag 内容召回: tags 倒排空且覆盖在途→LIKE 全表扫兜底（慢但诚实）#$key")
    return TagContentRecall(index.search("#$key", false), likeFallback = true)
}

/**
 * R269 行星内容流分页机（**纯 JVM 件**——android.util.Log 只在注入的 recall 闭包里，
 * 单测注入假召回即整机可测）：
 *
 *  - **懒召回仅一次**：首个 nextPage 才执行 [recall]（IO 由 VM loadMore 调度），结果
 *    缓存复用——同词幂等，reset 重放不重复召回（45s 级 LIKE 兜底绝不白烧第二轮）；
 *  - **按页切片**：每页至多 limit 条（limit≤0 防御钳 1），游标=未发完的非空标记，
 *    发尽一页游标即 null（终页）；
 *  - **空召回=首页即终页**（空 items+null 游标——非聚合源空页不续拉，状态机不悬空）；
 *  - **reset 语义**：切片游标归零（重放同一份召回集），召回缓存保留。
 */
internal class TagContentPager(private val recall: () -> TagContentRecall) {

    private var all: List<VideoItem>? = null
    private var offset = 0

    /** 召回执行计数（单测锚：懒+仅一次——reset 后也不再召回） */
    internal var recallCount = 0
        private set

    fun nextPage(limit: Int): FeedPage {
        val list = all ?: recall().also { recallCount++ }.items.also { all = it }
        if (offset >= list.size) return FeedPage(emptyList(), null)
        val pageSize = limit.coerceAtLeast(1)
        val end = minOf(offset + pageSize, list.size)
        val page = list.subList(offset, end)
        offset = end
        return FeedPage(page, if (offset < list.size) CONT_CURSOR else null)
    }

    fun reset() {
        offset = 0
    }

    private companion object {
        /** 非终页游标（自造标记，仅本机状态自读；VM 只判 null/非 null） */
        const val CONT_CURSOR = "tagc:more"
    }
}

private const val TAG = "TagContentFeed"
