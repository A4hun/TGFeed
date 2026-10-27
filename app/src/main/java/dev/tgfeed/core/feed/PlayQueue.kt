package dev.tgfeed.core.feed

import dev.tgfeed.core.td.FeedPage
import dev.tgfeed.core.td.FeedRequest
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.VideoItem
import kotlin.random.Random

// ---------------- R387 播放编排一期（节目单）core 纯件 ----------------
//
// 定位（r387_notes §0/§1.2「一切皆 Feed」判词）：发现层与播放引擎之间的
// 「节目单」整层缺席——用户只能被动顺着时间流刷，不能主动「我要这样看」。
// 本件 = 一期四件的数据模型 + 装配纯函数 + 队列 FeedSource：
//   1. 频道播放队列（A/B/C 台排成单，按序播完自动下一条）；
//   2. 顺序规则四档（MANUAL 手排 / TIME 跨台时间线 / HEAT 热度 / RANDOM 随机）；
//   3. 稍后再看入单（刷到/星图看到 → 存队列 → 按序播，feed 长按面板+星图星卡两入口）；
//   4. 队列编辑（拖拽换序/跳过/置顶 + 队列级断点续播）。
//
// 架构判词：队列 = 一个新 FeedSource——FeedScreen pager 逐页翻 + autoAdvance
// 自动切下一条 + loadMore 尾页续拉对「按序播完自动下一条」在列表层已内建，
// 队列只需把「多个频道的条目流」装配成同一条 items 流；autoAdvance/播放池/
// 预载调度/后台闸全部零改自动生效（r387_notes §3.4 红线：播放链零碰）。
//
// 纯 Kotlin 零 Android 依赖（JVM 单测直测，ListenQueueModel 同纪律）；
// 持久化 codec 在 LocalStore（play_queue.json，笔3），VM 编排在
// FeedPlayQueuePart（笔4），UI 在 QueueScreen/两入口（笔5/笔6）。

/**
 * 队列顺序四档（R371 搜索四档 SearchSortOrder 的单源件模式：enum+label+
 * fromName 反解未知回落默认档；档位是队列自身属性随 play_queue.json 落盘，
 * 刻意不进 AppPrefs——与搜索档「全局偏好」语义不同，少一个全局键）。
 *
 * 四档语义（装配层 [buildPlayPlan] 对条目流生效，r387_notes §2.2 定案）：
 *  - MANUAL（默认）：分段顺序播——单内条目按用户手排顺序；频道条目内
 *    最新→最旧（ChannelFeedSource 既有游标语义）；条目播完自动接下一条目
 *    （懒分页无缝续）=「按序播完自动下一条」的落点；
 *  - TIME：跨台时间线——轮式装配（每轮各活跃段取一小页，轮内按发布时间
 *    降序合并；轮间近似全局时间序，诚实注记=SubscriptionFeedSource 混排
 *    同款近似口径，不做全量预取）；
 *  - HEAT：全段按热度键稳定排序——段键 = 来源频道成员数查表（零网络，
 *    R371 channelMemberCountsForSort 同源口径；未知 = -1 沉底诚实降级），
 *    段内仍时间序；
 *  - RANDOM：开单时对段序洗牌一次（[seed] 会话级不落盘——每次开单重洗），
 *    段内时间序。
 */
enum class PlayQueueOrder(val label: String) {
    /** 手动排序（默认）：分段顺序播，单内条目按用户手排顺序 */
    MANUAL("手动排序"),

    /** 时间序：跨台时间线（最新→最旧，轮式近似） */
    TIME("时间序"),

    /** 热度：段序按来源频道成员数降序（本地图谱查表，未知沉底） */
    HEAT("热度"),

    /** 随机：开单时对段序洗牌一次（会话级种子，每次开单重洗） */
    RANDOM("随机"),
    ;

    companion object {
        /** 持久化名反解（play_queue.json 的 orderMode 字段）；未知/空值回落默认档 MANUAL */
        fun fromName(name: String?): PlayQueueOrder =
            values().firstOrNull { it.name == name } ?: MANUAL
    }
}

/** 队列单内一条（sealed：频道台级条目 / 单条条目，r387_notes §2.1 定案） */
sealed interface PlayQueueEntry {
    /** 本机唯一条目 id（eq_<时间戳>_<随机段>，生成见 PlayQueueModel）；拖拽/跳过/置顶/断点都按它定位 */
    val id: String

    /** 跳过开关：播放期排除（构建期不进装配流），可恢复——与移除语义分离 */
    val skipped: Boolean

    /** 频道条目：整台按序播。只存 chatId+title（条目轻，取数现拉）；title 兼作开频道自愈线索 */
    data class Channel(
        override val id: String,
        val chatId: Long,
        val title: String,
        override val skipped: Boolean = false,
    ) : PlayQueueEntry

    /** 单条条目：稍后再看入的单条。存完整 VideoItem（片单/稍后再看同口径，离线可播） */
    data class Item(
        override val id: String,
        val item: VideoItem,
        override val skipped: Boolean = false,
    ) : PlayQueueEntry
}

/**
 * 播放队列实体（二期起 = 播放台：多套队列实体化，一期预留 name 接口位启用，
 * r391_notes §2.1；一期=单队列「节目单」形态，v2 档迁移见 LocalStore codec）。
 *
 * 持久化：play_queue.json v2（LocalStore codec，stations 列表档）；断点双字段
 * （[resumeEntryId]+[resumeKey]）由 onPage 记账钩子写入（一期链），
 * 开单时按 resumeKey 跳回（跳转落空诚实回顶）。
 */
data class PlayQueue(
    /**
     * 台身份（R391 多队列主键）：qs_<时间戳>_<随机段>；"" = 哨兵语义
     * （冷启动空单/无活跃台——模型内合法，codec 读侧空 id=坏台跳过，
     * VM 哨兵台不入台列表）。生成方 = VM 侧 createPlayStation/
     * generateTodayStation；v1 迁移台固定 "legacy"（幂等重迁移不漂移）。
     */
    val id: String = "",
    /** 台名（用户可改；两台同名合法——本地身份=id 不靠名） */
    val name: String = DEFAULT_NAME,
    /** 顺序档（队列自身属性随实体落盘跨会话记忆） */
    val orderMode: PlayQueueOrder = PlayQueueOrder.MANUAL,
    /** 单内条目（含 skipped=true 的跳过条目——跳过是播放排除不是删除；列表序=手排序） */
    val entries: List<PlayQueueEntry> = emptyList(),
    /** 最近一次内容变更时刻（enqueues/remove/move/skip/setOrder；断点记账不推进） */
    val updatedAt: Long = 0L,
    /** 队列级断点：播到的条目 id（null=无断点，从头播） */
    val resumeEntryId: String? = null,
    /** 队列级断点：播到的 item key（跳回定位用；与 resumeEntryId 成对消亡） */
    val resumeKey: String? = null,
    /**
     * 接棒台(R392 #5 自动连播链,三态):null=不接(播完即停,一期语义保留)/
     * [CHAIN_NEXT]=同列表下一台(哨兵)/其他=指定台 id。
     * 判词:「电台连续节目」心智——用户配一次,开台听一天;台播完自动衔接。
     * copy 天然保留;legacy/默认 null=不接——一期/二期装机行为零变化;
     * 持久化=play_queue.json v2 增量字段 chainTo(旧档无键→null=不接,向后
     * 兼容);消费=VM 层段尽位(FeedPlayChainPart,一期开台链复用);解析=
     * 纯函数 [resolvePlayChain](PlayChain.kt)。
     */
    val chainTo: String? = null,
) {
    companion object {
        /** 队列缺省名（一期恒定；二期新台缺省名同款，用户可改） */
        const val DEFAULT_NAME = "播放队列"

        /** v1 单队列迁移台的固定 id（LocalStore v2 读侧幂等迁移：重读不漂移） */
        const val LEGACY_ID = "legacy"

        /**
         * 「同列表下一台」哨兵(R392 三态的中位):台 id 域为 qs_<时间戳>_<随机段>/
         * legacy/qs_today_* 前缀族,与字面 "next" 永不碰撞——哨兵语义单源。
         */
        const val CHAIN_NEXT = "next"
    }
}

/**
 * 队列纯模型（模型纪律抄 ListenQueueModel：纯 Kotlin 零 Android 依赖、
 * 全部方法非线程安全由调用方（VM，主线程串行）约定、变更返回 Boolean
 * 变更位供调用方省一次落盘——remove/playNext 同款「本来就没有=false」语义）。
 *
 * 与 ListenQueue 的分野（r387_notes §1.3 判词）：那是音频单播放器的 up-next
 * 队列（ENDED 仲裁在播放器层）；本件是 feed 视频流队列——推进权在 items
 * 列表 + autoAdvance，纯 VM 域即可，不抄 controller 骨架只抄模型纪律。
 * VM 侧落盘编排见 FeedPlayQueuePart（笔4）。
 */
class PlayQueueModel {

    /** 队列本体。对外只读视图，变更只能走本类方法。 */
    var queue: PlayQueue = PlayQueue()
        private set

    /** 整队列替换（冷启动从磁盘恢复的唯一入口；超 [MAX_ENTRIES] 防御截尾） */
    fun replaceAll(restored: PlayQueue) {
        queue = restored.copy(entries = restored.entries.take(MAX_ENTRIES))
    }

    /** 加入频道台级条目（入尾）；同 chatId 已在单 = 幂等忽略（返回 false 供 UI Toast 分流） */
    fun enqueueChannel(chatId: Long, title: String): Boolean {
        if (queue.entries.any { it is PlayQueueEntry.Channel && it.chatId == chatId }) return false
        queue = queue.copy(
            entries = (queue.entries + PlayQueueEntry.Channel(newEntryId(), chatId, title))
                .take(MAX_ENTRIES),
            updatedAt = System.currentTimeMillis(),
        )
        return true
    }

    /** 加入单条条目（入尾；稍后再看入单动作的模型面）；同 key 已在单 = 幂等忽略 */
    fun enqueueItem(item: VideoItem): Boolean {
        if (queue.entries.any { it is PlayQueueEntry.Item && it.item.key == item.key }) return false
        queue = queue.copy(
            entries = (queue.entries + PlayQueueEntry.Item(newEntryId(), item)).take(MAX_ENTRIES),
            updatedAt = System.currentTimeMillis(),
        )
        return true
    }

    /** 移除一条（按条目 id）；本来就没有返回 false（省一次落盘） */
    fun remove(entryId: String): Boolean {
        val list = queue.entries.filterNot { it.id == entryId }
        if (list.size == queue.entries.size) return false
        // 断点指向被移除条目 → 同步清账（诚实：条目已不在，断点失去落点；
        // 开单跳转对失联 key 本就有「落空诚实回顶」兜底，这里双保险）
        val resumeValid = list.any { it.id == queue.resumeEntryId }
        queue = queue.copy(
            entries = list,
            updatedAt = System.currentTimeMillis(),
            resumeEntryId = if (resumeValid) queue.resumeEntryId else null,
            resumeKey = if (resumeValid) queue.resumeKey else null,
        )
        return true
    }

    /** 拖拽换序（UI 列表下标即 entries 下标——跳过条目也占位可见）；越界/原地返回 false */
    fun move(from: Int, to: Int): Boolean {
        if (from == to) return false
        if (from !in queue.entries.indices || to !in queue.entries.indices) return false
        val list = queue.entries.toMutableList()
        list.add(to, list.removeAt(from))
        queue = queue.copy(entries = list, updatedAt = System.currentTimeMillis())
        return true
    }

    /** 置顶一条（按条目 id）；已在顶返回 false（省一次落盘） */
    fun moveToTop(entryId: String): Boolean {
        val idx = queue.entries.indexOfFirst { it.id == entryId }
        if (idx <= 0) return false
        return move(idx, 0)
    }

    /** 跳过开关（翻转运放排除，可恢复）；找不到条目返回 false */
    fun toggleSkip(entryId: String): Boolean {
        val idx = queue.entries.indexOfFirst { it.id == entryId }
        if (idx < 0) return false
        val list = queue.entries.toMutableList()
        list[idx] = when (val e = list[idx]) {
            is PlayQueueEntry.Channel -> e.copy(skipped = !e.skipped)
            is PlayQueueEntry.Item -> e.copy(skipped = !e.skipped)
        }
        queue = queue.copy(entries = list, updatedAt = System.currentTimeMillis())
        return true
    }

    /** 切顺序档；同档返回 false（chips 重点同档省一次落盘） */
    fun setOrder(mode: PlayQueueOrder): Boolean {
        if (queue.orderMode == mode) return false
        queue = queue.copy(orderMode = mode, updatedAt = System.currentTimeMillis())
        return true
    }

    /** 清空队列（断点随条目消亡一并清账——r387_notes §3.3 判词）；本来就空返回 false */
    fun clear(): Boolean {
        if (queue.entries.isEmpty() && queue.resumeEntryId == null) return false
        queue = queue.copy(
            entries = emptyList(),
            updatedAt = System.currentTimeMillis(),
            resumeEntryId = null,
            resumeKey = null,
        )
        return true
    }

    /**
     * 断点记账（onPage 钩子写入；id 与 key 成对）；不推进 updatedAt（断点=播放账
     * 非内容账，节流落盘不虚增内容时间）。返回变更位（同条不重写——调用方据此
     * 省一次落盘，remove 的「本来就没有=false」同语义）。
     */
    fun setResume(entryId: String, key: String): Boolean {
        if (queue.resumeEntryId == entryId && queue.resumeKey == key) return false
        queue = queue.copy(resumeEntryId = entryId, resumeKey = key)
        return true
    }

    /** 从头播清账（队列页「从头播」动作）；无断点返回 false */
    fun resetResume(): Boolean {
        if (queue.resumeEntryId == null && queue.resumeKey == null) return false
        queue = queue.copy(resumeEntryId = null, resumeKey = null)
        return true
    }

    /** 条目 id 生成：eq_<时间戳>_<随机段>（本机唯一；playlist id 同款口径） */
    private fun newEntryId(): String =
        "eq_${System.currentTimeMillis()}_${(1000..9999).random()}"

    companion object {
        /** 单内条目上限：超限截尾（ListenQueue MAX_SIZE=500 同族精神；队列条目为台级+单条混合，更少） */
        const val MAX_ENTRIES = 200

        /**
         * 播放台数上限（R391 多队列防滥用：存档体积+台列表可用性；
         * 今日台覆盖不增数——生成走替换不走追加）。
         */
        const val MAX_STATIONS = 12
    }
}

// ---------------- 装配纯函数（顺序档对条目流的生效层） ----------------

/** 装配段（一个队列条目在播放流里的供给单位；Channel=整台懒分页，Single=一条即尽） */
sealed interface PlaySegment {
    /** 来源条目 id（断点记账反查用，见 PlayQueueFeedSource.entryIdOf） */
    val entryId: String

    /** 频道段：取数引擎 = ChannelFeedSource（R183 自愈/R184 限流链全继承） */
    data class Channel(override val entryId: String, val chatId: Long, val title: String) :
        PlaySegment

    /** 单条段：一次性 served 语义（serve 一条即耗尽） */
    data class Single(override val entryId: String, val item: VideoItem) : PlaySegment
}

/**
 * 开单装配（纯函数）：以队列当前条目装配播放段列表——**快照语义**
 * （播放中编辑队列不改在播流，下次开单生效；openPlaylist 同款判词）。
 * skipped 条目构建期排除（=「跳过条目开单不出现」的落点）。
 *
 * 档位生效（r387_notes §2.2 四档定案）：
 *  - MANUAL：手排原序直通（频道段内仍由游标保证最新→最旧）；
 *  - TIME：段序直通——轮式交错在 [PlayQueueFeedSource] 源层逐轮进行，
 *    段的先后不参与时间线（单条段首轮即尽、频道段逐轮供页）；
 *  - HEAT：段按热度键稳定排序（sortedByDescending 保并列段原序）；
 *    段键 = 来源频道成员数（频道段=自身 chatId，单条段=item.chatId），
 *    [memberCounts] 本地图谱查表零网络（R371 channelMemberCountsForSort
 *    同源口径）；未知（不在表）= -1 沉底诚实降级不伪装热度；
 *  - RANDOM：段序洗牌一次（[seed] 会话级种子——同一次开单重放同序，
 *    重开单换种子=重洗；段内仍时间序）。
 *
 * @param queue 开单时的队列快照
 * @param memberCounts chatId → 频道成员数（HEAT 档数据源；其他档不读）
 * @param seed RANDOM 档洗牌种子（VM 侧会话级生成，不落盘）
 */
fun buildPlayPlan(
    queue: PlayQueue,
    memberCounts: Map<Long, Int> = emptyMap(),
    seed: Long = 0L,
): List<PlaySegment> {
    val segments = queue.entries
        .filterNot { it.skipped }
        .map { e ->
            when (e) {
                is PlayQueueEntry.Channel -> PlaySegment.Channel(e.id, e.chatId, e.title)
                is PlayQueueEntry.Item -> PlaySegment.Single(e.id, e.item)
            }
        }
    return when (queue.orderMode) {
        // 手排原序；TIME 的跨台时间线由源层轮式装配（段序不参与）
        PlayQueueOrder.MANUAL, PlayQueueOrder.TIME -> segments
        // 热度档：段键稳定降序；未知 -1 沉底（诚实降级）
        PlayQueueOrder.HEAT -> segments.sortedByDescending { segment ->
            memberCounts[segmentChatId(segment)] ?: -1
        }
        // 随机档：开单洗一次（seed 会话级）；reset 重放同序（快照语义）
        PlayQueueOrder.RANDOM -> segments.shuffled(Random(seed))
    }
}

/** 段的来源频道键（HEAT 档取表键：频道段=自身 chatId，单条段=条目 chatId） */
private fun segmentChatId(segment: PlaySegment): Long = when (segment) {
    is PlaySegment.Channel -> segment.chatId
    is PlaySegment.Single -> segment.item.chatId
}

// ---------------- 队列 FeedSource（「按序播完自动下一条」的供给面） ----------------

/**
 * 播放队列源：把队列装配段（[buildPlayPlan] 产物）拼成同一条 items 流，
 * FeedScreen pager/autoAdvance/loadMore/预载/播放池零改自动生效。
 *
 *  - request = 聚合语义（无单一频道无搜索词，CollectionFeedSource 同口径）；
 *    loadMore 过滤链按**源类型**判别的组件（useMediaFetch 服务端媒体过滤/
 *    已看硬滤/历史回溯注入/新面孔保底/兴趣与图谱召回）对非聚合源天然豁免
 *    = 队列序保全（r387_notes §1.2 前任结论，实勘复核成立）；VM 侧仍需
 *    两处点名豁免（笔4 最小刀）：重发折叠（显式清单语义）与 P1.5/P2
 *    页内后处理（有界前移会破队列序）。
 *  - **快照语义**：plan 是开单时刻的装配（播放中编辑队列/切档不改在播流，
 *    下次开单生效）；
 *  - MANUAL/HEAT/RANDOM = 顺序段走：当前段取数 → 耗尽接下一段 → 段间无缝
 *    （页界允许跨段拼接，页内不足 limit 不强凑）；页缓冲防超 limit 丢条
 *    （段页多出的条目进缓冲下页续供，不学 CollectionFeedSource 的丢弃口径
 *    ——队列是显式节目单，一条都不丢）；频道段取数错误**上抛**
 *    （诚实：队列卡死该段由用户 skip/重试处置，与单频道源同一错误口径）；
 *  - TIME = 轮式：每轮各活跃段取一小页（perChannel = max(4, limit/活跃段数)，
 *    CollectionFeedSource 同口径）→ 轮内按 dateEpochSec 降序（0 沉底=R371
 *    口径；稳定排序保并列段原序）→ 返回；单条段首轮即尽；单频道失败跳过
 *    保留游标下轮重试（CollectionFeedSource 同口径）；**轮间近似全局时间序**
 *    （诚实注记：不做全量预取，SubscriptionFeedSource 混排同款近似）；
 *  - reset() = 段游标全复位（重放语义；ChannelFeedSource.reset 保 healed
 *    闸——已建会话的频道重放不重烧自愈链）；
 *  - [entryIdOf] = 条目 key → 来源段条目 id 的反查（断点记账用）。
 *
 * 线程约定：FeedSource 家族纪律——VM loadMore 的 _loading 闸+代次闸串行调用，
 * 本类零同步（GatewayFeedSource/CollectionFeedSource 同款）。
 */
class PlayQueueFeedSource(
    private val gateway: TdGateway,
    plan: List<PlaySegment>,
    /** 顺序档（快照：开单时刻队列档位；TIME 走轮式装配，其余顺序段走） */
    val order: PlayQueueOrder,
    /** RANDOM 档会话级种子（随 plan 快照固化；对外只读供 VM 开单探针/日志记账） */
    val seed: Long = 0L,
) : FeedSource {

    // 与 CollectionFeedSource 同为聚合语义：无单一频道、无搜索词
    override val request: FeedRequest = FeedRequest()

    /** 段运行态（频道段懒建源、耗尽标记；单条段 served 即 done） */
    private class SegmentState(val segment: PlaySegment) {
        var channelSource: ChannelFeedSource? = null
        var done = false
    }

    private val states: List<SegmentState> = plan.map { SegmentState(it) }

    /** 页缓冲（段页超出本次 limit 的盈余——下页零网络续供，防丢条） */
    private val buffer = ArrayDeque<VideoItem>()

    /** 顺序段走：当前打开的段下标（频道段跨页持游标）；-1 = 无打开段 */
    private var openIndex = -1

    /** 全段耗尽且缓冲空（到底；末页可带条目——家族纪律） */
    private var exhausted = false

    override suspend fun nextPage(limit: Int): FeedPage {
        if (exhausted) return FeedPage(emptyList(), null)
        return if (order == PlayQueueOrder.TIME) timePage(limit) else sequentialPage(limit)
    }

    override fun reset() {
        for (st in states) {
            st.done = false
            // 段游标全复位；ChannelFeedSource.reset 只复位游标不动 healed 闸
            //（已建会话的频道重放不再烧自愈链，R183 healed=终身免再试口径）
            st.channelSource?.reset()
        }
        buffer.clear()
        openIndex = -1
        exhausted = false
    }

    /**
     * 条目 key → 来源段条目 id 反查（断点记账：onPage 落定条目 → 记进队列实体的
     * resumeEntryId）。单条段 key 精确命中优先（显式入单语境优先）；频道段按
     * chatId 命中（enqueueChannel 同 chatId 幂等 = 队列内 chatId 唯一）。
     * 注：签名收整条 [VideoItem]（比 r387_notes 草案的 itemKey 单参多带 chatId
     * ——频道段反查需要；调用方 onPage 本就持有整条）。
     * 反查失联（key 不在任何段，如队列已编辑换代）返回 null，调用方跳过记账。
     */
    /**
     * 供给尽只读访问器(R392 段尽位判据的一半):全段耗尽∧页缓冲空。
     * **段走本体零改判词**:只读既有内部 exhausted 账,nextPage/sequentialPage/
     * timePage/reset 行为零碰;置位时点=最后一次 nextPage 收页(pageOf 收口),
     * 此后 nextPage 恒回(空页,null)。消费方=VM 层段尽消费(FeedPlayChainPart
     * ——「队列段尽=供给尽∧消费尽」两闸串联的后半闸由 VM 判)。
     */
    val supplyExhausted: Boolean
        get() = exhausted

    fun entryIdOf(item: VideoItem): String? {
        for (st in states) {
            val seg = st.segment
            if (seg is PlaySegment.Single && seg.item.key == item.key) return seg.entryId
        }
        for (st in states) {
            val seg = st.segment
            if (seg is PlaySegment.Channel && seg.chatId == item.chatId) return seg.entryId
        }
        return null
    }

    // ---------------- 顺序段走（MANUAL/HEAT/RANDOM 共用：段序已由 buildPlayPlan 定） ----------------

    private suspend fun sequentialPage(limit: Int): FeedPage {
        val out = ArrayList<VideoItem>(limit.coerceAtMost(64))
        // 先消费页缓冲（上轮盈余，零网络）
        while (buffer.isNotEmpty() && out.size < limit) out.add(buffer.removeFirst())
        while (out.size < limit) {
            if (openIndex < 0) {
                // 开下一段：单条段即开即尽；频道段懒建源持游标
                val next = nextOpenableIndex()
                if (next < 0) break
                openIndex = next
                val seg = states[next].segment
                if (seg is PlaySegment.Single) {
                    offer(seg.item, out, limit)
                    states[next].done = true
                    openIndex = -1
                    continue
                }
            }
            // 频道段取数：错误上抛（诚实——限流/断连文案直显，队列停在该段待用户处置）
            val st = states[openIndex]
            val need = limit - out.size
            val page = sourceOf(st).nextPage(need)
            for (item in page.items) offer(item, out, limit)
            if (page.nextCursor == null) {
                // 段耗尽 → 接下一段（段间无缝：本页内继续拼下段开头）
                st.done = true
                openIndex = -1
            } else {
                // 段未耗尽：本页即还（单次取数口径同 GatewayFeedSource——一次 nextPage
                // 一发取数；段页偏短不强凑不重发，下轮 loadMore 续本段）
                break
            }
        }
        return pageOf(out)
    }

    /** 下一个未耗尽段下标（顺序段走）；全尽返回 -1 */
    private fun nextOpenableIndex(): Int {
        for (i in states.indices) if (!states[i].done) return i
        return -1
    }

    // ---------------- TIME 轮式（跨台时间线，CollectionFeedSource 段口径） ----------------

    private suspend fun timePage(limit: Int): FeedPage {
        val out = ArrayList<VideoItem>(limit.coerceAtMost(64))
        while (buffer.isNotEmpty() && out.size < limit) out.add(buffer.removeFirst())
        if (out.size < limit) {
            for (item in collectRound(limit)) offer(item, out, limit)
        }
        return pageOf(out)
    }

    /**
     * 一轮：各活跃段取 perChannel = max(4, limit/活跃段数)（配额均分至少 4 条，
     * 对齐 CollectionFeedSource/网关层聚合策略）→ 轮内按 dateEpochSec 降序合并。
     * 单频道失败跳过保留游标下轮重试（CollectionFeedSource 同口径——TIME 档
     * 是轮式混排，死一台不挡全队）；一轮一页（配额 oversample 天然 ≥ limit，
     * 盈余进缓冲）。轮间近似全局时间序（诚实注记：未耗尽段下一轮再供，
     * 不做全量预取对齐）。
     */
    private suspend fun collectRound(limit: Int): List<VideoItem> {
        val active = states.filter { !it.done }
        if (active.isEmpty()) return emptyList()
        val per = maxOf(4, limit / active.size)
        val round = ArrayList<VideoItem>(per * active.size)
        for (st in active) {
            when (val seg = st.segment) {
                is PlaySegment.Single -> {
                    round += seg.item
                    st.done = true // 单条段首轮即尽
                }
                is PlaySegment.Channel -> {
                    val page = try {
                        sourceOf(st).nextPage(per)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e // 协程取消不吞（ChannelFeedSource/家族纪律）
                    } catch (_: Exception) {
                        null // 失败跳过：段游标未推进，下轮自动重试
                    }
                    if (page != null) {
                        round += page.items
                        if (page.nextCursor == null) st.done = true
                    }
                }
            }
        }
        // 轮内时间序：最新→最旧；dateEpochSec=0（未知）沉底（R371 NEWEST 口径）；
        // 稳定排序保并列（同时刻）段取序
        return round.sortedByDescending { it.dateEpochSec }
    }

    // ---------------- 共用小件 ----------------

    /** 段源懒建（频道段）；已建直用（reset 后复用同实例=游标复位 healed 保留） */
    private fun sourceOf(st: SegmentState): ChannelFeedSource {
        st.channelSource?.let { return it }
        val seg = st.segment as PlaySegment.Channel
        val src = ChannelFeedSource(gateway, seg.chatId, openTitle = seg.title)
        st.channelSource = src
        return src
    }

    /** 入页收口：页内空位先填，盈余进缓冲（防超 limit 丢条——队列一条不丢） */
    private fun offer(item: VideoItem, out: ArrayList<VideoItem>, limit: Int) {
        if (out.size < limit) out.add(item) else buffer.addLast(item)
    }

    /** 收页收口：全段耗尽且缓冲空 = 到底（null 游标；末页可带条目） */
    private fun pageOf(items: List<VideoItem>): FeedPage {
        if (states.all { it.done } && buffer.isEmpty()) exhausted = true
        return FeedPage(items, if (exhausted) null else ALIVE_CURSOR)
    }

    private companion object {
        /**
         * 活着标记（不透明游标）：源内状态（段游标/页缓冲/耗尽账）自持，
         * 外层只辨 null（到底）与否——FollowingFeedSource ALIVE_CURSOR 同款
         * 口径（外层不解析该值）。
         */
        const val ALIVE_CURSOR = "r387:alive"
    }
}
