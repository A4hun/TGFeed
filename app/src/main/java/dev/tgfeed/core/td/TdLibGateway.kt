package dev.tgfeed.core.td

import android.content.Context
import android.util.Log
import dev.tgfeed.core.feed.AdBlacklist
import dev.tgfeed.core.feed.AdChannelClassifier
import dev.tgfeed.core.feed.AnswerStore
import dev.tgfeed.core.feed.DiscoveryGraph
import dev.tgfeed.core.feed.FloodBudget
import dev.tgfeed.core.feed.GoldmineHooks
import dev.tgfeed.core.feed.GroupAnswer
import dev.tgfeed.core.feed.HintTitle
import dev.tgfeed.core.feed.GroupHarvester
import dev.tgfeed.core.feed.HarvestGroupCandidate
import dev.tgfeed.core.feed.SearchEntity
import dev.tgfeed.core.prefs.AppPrefs
import io.github.tdlibandroid.ktx.TdClient
import io.github.tdlibandroid.ktx.TdException
import io.github.tdlibandroid.ktx.updatesOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi
import java.util.concurrent.TimeoutException
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 真实 TDLib 数据层。
 *
 * 播放策略：已下载完成的条目直接播 TDLib 本地缓存路径；未完成的经 TgStreamServer
 * 回环流（http://127.0.0.1:<port>/v/<fileId>，Range 泵+水位校验+零洞闸）边下边播。
 * 配合 PreloadScheduler 预取下两条，短视频场景下滑动延迟可接受。
 */

// ---------------- 频道礼包（合集 addlist 一键分享，R130）：对外结果模型 ----------------

/** 频道礼包生成结果（TdLibGateway.createChannelPackInviteLink 返回值）：addlist 链接 + 实际进包频道 */
class ChannelPackInvite(
    val url: String,
    val name: String,
    val chatIds: List<Long>,
)

/** 频道礼包整包收下结果（TdLibGateway.joinChannelPackInvite 返回值） */
sealed interface ChannelPackJoin {
    /** folderName = 服务端侧合集名；chatCount = 本次新收下的频道数（0 = 之前已全收） */
    data class Joined(val folderName: String, val chatCount: Int) : ChannelPackJoin

    data class Failed(val message: String) : ChannelPackJoin
}

/**
 * 频道礼包免加入预览（R137 消费侧「拆开看看」）：CheckChatFolderInviteLink 只读解析出的
 * 合集名 + 成员频道摘要。addedIds = 已经在你 TG 频道列表里的成员（UI 侧标「已在你的列表」）。
 */
class ChannelPackPreview(
    val folderName: String,
    val channels: List<ChannelBrief>,
    val addedIds: Set<Long>,
)

// ---------------- 频道礼包查重（R220：建夹前防重复污染） ----------------

/**
 * R220 查重输入：既有文件夹快照（网关从 UpdateChatFolders + 逐夹 GetChatFolder 汇出）。
 * memberIds = pinned+included 并集（礼包夹无排除无规则，这两组即全部成员）。
 */
internal class FolderDupCandidate(
    val folderId: Int,
    val title: String,
    val memberIds: Set<Long>,
    val isShareable: Boolean,
    val hasMyInviteLinks: Boolean,
)

/**
 * R220 查重决策（纯函数，无 IO，可单测）：目标礼包（title + 频道集合）撞上既有夹时
 * 选哪条复用路。优先级（同频道集合内从上到下）：
 *  1. [ReuseExistingLink] 夹里已有我的邀请链接 → 直接返回现有链接（零新增写）；
 *  2. [ExportOnExisting] 夹已是可分享合集 → 在它身上导出链接（不建新夹）；
 *  3. [AdoptOrphan] 同名普通夹 = 上次生成中途失败的遗孤 → 收编转可分享再导出（不建新夹）；
 *  4. [CreateNew] 其余（含「同频道集合但用户自建普通夹」——防误伤私夹不收编）→ 建新夹。
 */
internal sealed interface FolderReuseDecision {
    data class ReuseExistingLink(val folderId: Int) : FolderReuseDecision
    data class ExportOnExisting(val folderId: Int) : FolderReuseDecision
    data class AdoptOrphan(val folderId: Int) : FolderReuseDecision
    data object CreateNew : FolderReuseDecision
}

/** [FolderReuseDecision] 的决策实现（纯函数；分支依据见接口注释） */
internal fun decideFolderReuse(
    candidates: List<FolderDupCandidate>,
    title: String,
    ids: Set<Long>,
): FolderReuseDecision {
    val setMatched = candidates.filter { it.memberIds == ids }
    setMatched.firstOrNull { it.hasMyInviteLinks }?.let { return FolderReuseDecision.ReuseExistingLink(it.folderId) }
    setMatched.firstOrNull { it.isShareable }?.let { return FolderReuseDecision.ExportOnExisting(it.folderId) }
    setMatched.firstOrNull { it.title == title }?.let { return FolderReuseDecision.AdoptOrphan(it.folderId) }
    return FolderReuseDecision.CreateNew
}

/** R220 测试污染文件夹快照（清理工具列表用）：只带结构化字段，频道标题不进来（输出脱敏铁律） */
class TestFolderSnapshot(
    val folderId: Int,
    /** 夹名（R130 开头的代理测试命名；展示给用户确认用） */
    val name: String,
    /** 夹内频道数（pinned+included 去重；-1 = GetChatFolder 失败读不到） */
    val chatCount: Int,
    val isShareable: Boolean,
    /** 夹上挂着的我的邀请链接数（>0 的夹删除前要先删链接，TG 拒删带活跃链接的夹） */
    val inviteLinkCount: Int,
)

// ---------------- 时间机器·频道考古（R134，ideas.md #7）：对外结果模型 ----------------

/**
 * 考古一铲的落点与货（TdLibGateway.archaeoJumpToTime / archaeoRandomDig 返回值）。
 *
 * 老频道 5 年库存只刷得到最近 2 周——考古把长尾盘活：按时间档位二分跳跃，或随机
 * 盲盒一铲；落点永远是真实存量消息，[items] 是从落点向更旧方向翻出的一页媒体
 * （"逛考古层"的原料）。
 */
class ArchaeoDig(
    /** 落点锚点消息 id（真实存量消息；items 为空时也有效——该层就是没媒体） */
    val anchorMessageId: Long,
    /** 落点消息发布时间（Unix 秒；观测口径见网关"考古一铲"日志） */
    val anchorDateSec: Int,
    /** 跳跃层数 = 本铲发出的 GetChatHistory 探测总次数（摸界 + 二分/随机试探；取页不计） */
    val hops: Int,
    /** 本铲目标时刻（Unix 秒）；随机一铲没有目标，填落点时刻仅作观测对齐 */
    val targetEpochSec: Int,
    /** true = 随机盲盒一铲；false = 时间档位跳跃 */
    val random: Boolean,
    /** 落点向更旧翻出的一页媒体（fetchChannelPageAnchored 同口径：锚点入窗、本地过滤） */
    val items: List<VideoItem>,
)

/** 考古二分跳跃层数上限（需求钉死"跳 5 层以内"；摸上界的 1 次探测另计、不占层数） */
private const val ARCHAEO_MAX_HOPS = 5

/**
 * 频道消息 id 网格因子（2^20）：TDLib 频道 Message.id = 帖子序号 × 2^20
 * （MainActivity 深链换算 5/5 样本实证；"爬虫游标每页恰好步进 50×2^20"同佐证）——
 * 帖子序号才是均匀线性的搜索单位，二分在它上面做。
 */
private const val ARCHAEO_ID_GRID = 1_048_576L

/**
 * 考古探测超时：30s，与 R46 月级低频请求同口径放宽——实测爬虫高峰期（深度索引
 * 采样排队数百频道、TDLib 请求串行排队）单次探测可超 10s（R5/R45 的教训只防
 * "无超时挂死"，超时过紧则用户点按的考古铲会被后台爬车误杀）。摸界+二分共
 * ≤6 次探测，最坏排队数分钟是病态上限；正常代理下探测毫秒级返回。
 */
private const val ARCHAEO_PROBE_TIMEOUT_MS = 30_000L

/** 二分提前收敛容差（秒）：探测消息时间距目标 6 小时内即视为落点，不再烧层数 */
private const val ARCHAEO_TOLERANCE_SEC = 6 * 3600

/** 随机一铲落空（随机位低于频道最老存量帖，头部删帖空档）时的重掷上限 */
private const val ARCHAEO_RANDOM_REROLLS = 3

/** 考古层一页媒体条数（与频道主页网格一页 30 同口径） */
private const val ARCHAEO_PAGE_LIMIT = 30

/** 考古探测命中：探测位之下最新一条存量消息的 id 与发布时间（Unix 秒）；文件内私有 */
private class ArchaeoHit(val id: Long, val date: Int)

// ---------------- 频道 Stories 快拍条（R144，ideas.md #5）：对外结果模型 ----------------

/**
 * 快拍条渲染单元：一个频道当前活跃的 stories 摘要（[TdLibGateway.chatStoriesBriefs] 投影）。
 *
 * stories 是频道主"正式发帖之外"的轻内容（预告/花絮/日常），24h 限时到期；
 * TDLib 1.8.67 对象模型完整（GetChatActiveStories / Story / StoryContent 系，javap 实证），
 * 官方客户端入口藏得深，快拍条给它一等公民位置就是差异化。
 * 注意：频道发 story 需要 boost 等级，多数中小频道没有——无活跃快拍的频道
 * 不进本投影（快拍条空态不占位，需求钉死）。
 */
class ChannelStoriesBrief(
    val chatId: Long,
    /** 该频道当前活跃的 story id 列表（旧→新升序；连播从最早的未看条起播） */
    val storyIds: List<Int>,
    /** 服务端已看水位（官方客户端看过的最大 story id）：≤ 水位的不算新 */
    val maxReadStoryId: Int,
    /** TDLib 快拍列表排序键（UpdateChatActiveStories 里的 order 原样透传，越大越靠前） */
    val order: Long,
    /** 最新一条 story 的发布时间（Unix 秒）：order 缺失时的排序兜底 + 过期紧迫感标签用 */
    val latestDateSec: Int,
) {
    /**
     * 这条 story 算"没看完"吗：id 高于服务端已看水位、且不在本会话已看账里
     * （账本键格式 "chatId:storyId"，见 [TdLibGateway.storyWatched]）。
     * 任一条未看完 = 快拍环亮红（"刚冒出来的"红环语言，design-redesign §3.7）。
     */
    fun isStoryUnwatched(storyId: Int, watched: Set<String>): Boolean =
        storyId > maxReadStoryId && "$chatId:$storyId" !in watched

    /** 本频道的 stories 是否还有没看完的（快拍环红/灰的判据） */
    fun hasUnwatched(watched: Set<String>): Boolean = storyIds.any { isStoryUnwatched(it, watched) }
}

/** 快拍条全量快照刷新的会话级 TTL：story 24h 生命周期里 10 分钟一档足够（增量变化走更新流补） */
private const val CHAT_STORIES_REFRESH_TTL_MS = 10 * 60 * 1000L

/** 快拍条全量快照刷新的频道级并发闸（与封面闸 coverDownloadSem 同思路：防几百频道并发挤爆 TDLib 请求队列） */
private const val CHAT_STORIES_REFRESH_CONCURRENCY = 8

/** 快拍快照单频道查询超时：正常毫秒级；TDLib 拥塞排队时按超时弃权（该频道本轮无快拍，更新流会补） */
private const val CHAT_STORIES_PER_CALL_TIMEOUT_MS = 5_000L

class TdLibGateway(
    context: Context,
    internal val scope: CoroutineScope,
    apiId: Int,
    apiHash: String,
    internal val proxyEnabled: Boolean = false,
    internal val proxyHost: String = "127.0.0.1",
    internal val proxyPort: Int = 7897,
    // 视频缓存水位上限（字节）；0 = 不启用自动水位清理。由 AppPrefs.cacheLimitGb 换算后注入
    internal val cacheLimitBytes: Long = 0,
) : TdGateway {

    internal val client: TdClient
    internal val _state = MutableStateFlow<GatewayState>(GatewayState.Loading)
    override val state: StateFlow<GatewayState> = _state.asStateFlow()

    internal val _connectionState = MutableStateFlow("连接中")
    override val connectionState: StateFlow<String> = _connectionState.asStateFlow()

    /**
     * R244 代理决策提示（见 TdGateway.proxyHint）：init 代理探测段写入，
     * 登录页直接可见——治「代理探测失败静默直连」。
     */
    internal val _proxyHint = MutableStateFlow<String?>(null)
    override val proxyHint: StateFlow<String?> = _proxyHint.asStateFlow()

    /**
     * R244 代理自动检测落盘回调（App.kt 装配时注入，写 prefs 的 proxyHost/Port/Enabled）：
     * 首启探测到本地代理软件端口 → AddProxy 的同时把结果写回配置，下次启动直达、
     * 设置页同步显示。null 或注入晚于探测完成时只影响落盘（本次会话代理照常生效，
     * 下次启动重新探测），不影响连接。
     */
    @Volatile
    var proxyPersistHook: ((host: String, port: Int) -> Unit)? = null

    /** R244 登录 auth 系请求的旁路网络监视 job（连点时取消旧监视，见 startAuthNetWatch） */
    internal var authNetWatchJob: kotlinx.coroutines.Job? = null

    /** 把 TDLib 的英文错误翻成用户能懂的中文 */
    internal fun friendlyError(e: Throwable, fallback: String): String {
        val msg = (e.message ?: "").lowercase()
        return when {
            "phone_number_invalid" in msg -> "手机号格式不对（要含区号，如 +86…）"
            "another authorization" in msg -> "验证码已在发送中（上次请求已生效）"
            "flood" in msg -> "操作太频繁，稍等片刻再试"
            "phone_code_invalid" in msg -> "验证码不对，重新核对"
            "phone_code_expired" in msg -> "验证码过期了，重新发一条"
            "password" in msg && "invalid" in msg -> "两步验证密码不对"
            "timeout" in msg || "timed out" in msg -> "网络超时——检查代理/VPN 是否在线"
            "resolve" in msg || "unreachable" in msg || "network" in msg -> "连不上网络——检查代理/VPN"
            else -> "$fallback（${e.message ?: "未知错误"}）"
        }
    }

    /**
     * 频道标题进程内缓存：chatId → 标题。
     * P1-1：必须 ConcurrentHashMap——fetchAggregated 以 Semaphore(5) 并发跑
     * fetchFromChannel → toVideoItem → channelTitle，普通 HashMap 并发 put 触发
     * resize 时可能结构损坏，后续 get 死循环（IO 线程挂死，主线程表现为 ANR）。
     */
    internal val channelTitleCache = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /**
     * 形态A 跨页相册共享 caption 缓存：chatId → (mediaAlbumId → 共享 caption)。
     * GetChatHistory 按 50 条/页切，同一相册被页边界劈开时带 caption 的成员落在邻页，
     * 本页空 caption 成员从本缓存补全（修"合集成员播放页只剩频道名"的分页断档）。
     * 外层 ConcurrentHashMap（fetchAggregated 聚合并发按 chatId 分桶，不会撞桶）；
     * 内层 LinkedHashMap 的全部读写必须 synchronized 它自身（同频道可被 Feed/搜索/媒体 tab
     * 并发触达），超 [ALBUM_CAPTION_CACHE_MAX] 按插入序掐最老。
     */
    internal val albumCaptionStore = java.util.concurrent.ConcurrentHashMap<Long, LinkedHashMap<Long, String>>()

    /**
     * 形态B 跨页批注状态：chatId → 上次翻页结束时的活跃批注（文字帖 id + 正文 + 已关联条数）。
     * "先发一批媒体、再发一条文字说明"的批被页边界劈开时（文字帖在上一页、
     * 空 caption 媒体落在本页头部），续页从这里取出接续关联；buildCaptionOverrides 内做
     * 消息 id 邻接校验，刷新从头拉/换频道时首条消息比文字帖新，天然不命中。
     */
    internal val tailNoteStore = java.util.concurrent.ConcurrentHashMap<Long, TailNote>()

    /**
     * R257b 标题回退链·链③转发原帖 caption 缓存：原帖坐标 "chatId:messageId" → 条目。
     * 空串 text=负缓存（查询失败/原帖无 caption 同记账，TTL 内同原帖不重查）；
     * 容量治理在 resolveForwardCaptions 内（清过期→仍超 FORWARD_CAPTION_CACHE_MAX 全清重建，
     * 与 albumCaptionStore/markExpandedOnce 同族口径——缓存重建便宜）。
     */
    internal val forwardCaptionStore = java.util.concurrent.ConcurrentHashMap<String, ForwardCapEntry>()

    /** 形态B 跨页接续的页尾批注状态（[tailNoteStore] 的值 / [buildCaptionOverrides] 的返回值） */
    internal data class TailNote(val textMsgId: Long, val text: String, val used: Int)

    /**
     * 资源嗅探图谱（DISCOVERY-GRAPH M1 前段）：fetchFromChannel 每页消息的被动钩子
     * 往这里写边（forward_src 溯源 / link 互链），零额外网络请求。
     * 落盘 filesDir/discovery_graph.json；候选评分/「发现频道」页在后续迭代接入读侧。
     * 图谱为纯本地数据，不上报、不进广场。
     */
    val discoveryGraph: DiscoveryGraph = DiscoveryGraph(context.filesDir)

    /**
     * R283 消息级嗅探去重账本（互链图谱深化的幂等闸）：全嗅探路径（feed 页/评论
     * 线程/链③原帖/本地回填）统一过闸——同一 (chatId, messageId) 只贡献一次边权，
     * 防回填周期重扫/翻页重叠把边 count 从「去重消息数」刷成「观测次数」（双星判定
     * 权重污染源）。进程内不落盘，口径与容量治理见 [SniffLedger]。
     */
    internal val sniffLedger = SniffLedger()

    /**
     * v2 拉取配额倾斜的权重来源：返回 chatId → 频道权重（时间衰减版，
     * 见 core/feed/FeedSource.kt 的 decayedChannelWeights）。由 FeedViewModel 启动时注入；
     * 未注入或返回空表时，聚合拉取退化为 v1 的每频道均匀配额。
     * 网关不直接持有 LocalStore（保持 store 单实例缓存不失效、单写入方语义），
     * 故用 provider 回调解耦。调用时机：fetchAggregated 每轮开头一次（IO 线程）。
     */
    var channelWeightProvider: (() -> Map<Long, Double>)? = null

    /**
     * 频道轮换采样（修"每次刷都是同一批频道"）："最近露脸频道"记忆供给。
     * 由 FeedViewModel 注入（进程内存态，记最近 2 轮刷新的进池频道）；
     * fetchAggregated 新轮（cursor==null）抽样前调用一次，命中的频道本轮大幅降权
     * （×[ROTATION_PENALTY]，非露脸候选够一池时直接排除，不够才降权回补）——
     * 高权重频道只是轮休，隔轮即归，不是永别。未注入时无轮换惩罚（退化旧行为）。
     * 与 [channelWeightProvider] 同款 provider 解耦：网关不碰 LocalStore/会话态。
     */
    @Volatile
    var recentPoolProvider: (() -> Set<Long>)? = null

    /**
     * 本轮进池频道回报：fetchAggregated 新轮抽样完成后调用一次（轮内翻页不报），
     * 由 FeedViewModel 记入"最近露脸"记忆（[recentPoolProvider] 的数据来源）。
     */
    @Volatile
    var poolReporter: ((Set<Long>) -> Unit)? = null

    /**
     * R75-A 保底配额队列的数据源：chatId → 上次进池时刻（epoch ms，无记录 = 从未进池）。
     * 由 FeedViewModel 注入（ChannelExposureLedger 的跨会话持久账）；未注入/空表时
     * 保底车道按"账面全空 = 全员从未进池"自然运转（列表原序就是最久未进池序）。
     * 调用时机：sampleRotationPool 每轮抽池一次（IO 线程）。
     */
    @Volatile
    var poolRecencyProvider: (() -> Map<Long, Long>)? = null

    /**
     * R75-C 进页条数回报：fetchAggregated 每页拉取完成后调用一次，
     * 参数 = 本页各频道实际贡献进页管道的条数（chatId → 条数；拉取/入池口径，非 UI 曝光）。
     * 只有主聚合 Feed（无搜索词）回报，搜索回退的临时池不污染曝光账。
     */
    @Volatile
    var poolItemReporter: ((Map<Long, Int>) -> Unit)? = null

    /**
     * R92 保底选池内容分层数据源：入参 mediaType（0 视频/1 图文/2 音频），
     * 返回 (chatId→深度索引内该类型条数, 索引已爬频道集)；null = 索引未建/不可用
     *（保底选池回退"最久未进窗"单口径）。由 FeedViewModel 注入（只读 deepindex
     * search.db，内存缓存）。用途：保底坑位分层——有货 > 未知 > 确认无货，
     * 修"保底坑位被无视频文字频道白占"（实测 117 个进窗频道 35 个历史零产出）。
     * 调用时机：fetchAggregatedMediaAll 刷新轮（cursor==null）选保底坑位时一次（IO 线程）。
     */
    @Volatile
    var poolContentProvider: ((Int) -> Pair<Map<Long, Int>, Set<Long>>?)? = null

    /**
     * R92 保底名册显式回报（仅 fetchAggregatedMediaAll 刷新轮）：保底坑位经内容分层后，
     * VM 侧按 (lastPooled, chatId) 复算的口径与网关实际选取不再一致——名册改由网关
     * 显式上报，与 [poolReporter] 同轮同步调用且先于它；fetchAggregated 路径不回报
     *（VM 侧维持原复算口径）。
     */
    @Volatile
    var guaranteedRosterReporter: ((Set<Long>) -> Unit)? = null

    /**
     * R150 已看身份双轨数据源（网关直拉产出源头过滤用）：返回 (已看 key 集,
     * 已看 fileUniqueId 集)；null 或两集皆空 = 不过滤。
     * 由 FeedViewModel 注入（LocalStore.loadSeenIdentities 命中内存缓存，纯内存读
     * 无磁盘 IO——源头过滤不引入网络/重 IO，快车道延迟零增量）。
     * 消费方：fetchAggregatedMediaAll / fetchAggregatedMediaAllFast（聚合媒体 tab
     * 正路与快车道）——产出阶段即剔除已看条目，全局刷新后不再整窗重拉同批已看内容；
     * 单频道直拉（fetchAggregatedMedia）刻意不消费（翻频道历史能力保留，分屏/频道页
     * 自行过滤）。未注入（单测/未接线）时两路径行为与旧版一致，已看剔除仍由
     * VM 侧 loadMore 的 pass 谓词兜底（双保险口径不变）。
     */
    @Volatile
    var seenIdentityProvider: (() -> Pair<Set<String>, Set<String>>)? = null

    /**
     * 当前轮换轮的进池频道：fetchAggregated 新轮（cursor==null）重抽一池，
     * 轮内翻页复用本池，保证同一轮内游标与配额口径稳定（换池会让 cursor 语义漂移）。
     * 进程内存态：重启后首轮 cursor==null 自然重抽，无需持久化。
     */
    @Volatile
    internal var currentPool: List<Long> = emptyList()

    /**
     * 类型 tab（fetchAggregatedMediaAll）环形窗口的刷新起点：mediaType → 下次刷新起点下标。
     * 每次刷新（cursor==null）从该起点开扫并把记录推进一个窗口宽（[MEDIA_TAB_CHANNEL_WINDOW]，
     * 对频道总数取模环形绕回）——修"每次刷新看到的都是列表顺序前 40 个频道"。
     * 轮内翻页仍走游标自带的 @rotation 段（语义不变）。
     * R92 起落盘持久化（filesDir/media_window_start.json）：旧口径纯内存、冷启动归零，
     * 实测头部频道每次冷启动重复进窗（入池账 rounds=5 全集中在清单头部）、
     * 尾部归档/文件夹频道（527 名之后）永远轮不到；落盘后环形车道跨会话续扫，
     * "确定性遍历全表"从单会话扩展到跨会话。
     */
    internal val mediaTabWindowStart = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    /** R92：环形起点落盘文件目录（构造参数 context 仅属性初始化期可用，这里接住） */
    internal val appFilesDir = context.filesDir

    /** 环形起点落盘是否已加载（懒加载：首次刷新才读盘，双检锁防并发重复读） */
    @Volatile
    internal var mediaWindowStartLoaded = false

    /** R92：读盘恢复环形起点（文件缺失/损坏静默跳过 = 从 0 起步，同旧口径） */
    internal fun loadMediaWindowStarts() {
        if (mediaWindowStartLoaded) return
        synchronized(mediaTabWindowStart) {
            if (mediaWindowStartLoaded) return
            mediaWindowStartLoaded = true
            runCatching {
                val f = java.io.File(appFilesDir, "media_window_start.json")
                val text = f.takeIf { it.isFile }?.readText() ?: return@runCatching
                val j = org.json.JSONObject(text)
                val keys = j.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val mt = k.toIntOrNull() ?: continue
                    val v = j.optInt(k, -1)
                    if (v >= 0) mediaTabWindowStart[mt] = v
                }
                Log.d(TAG, "媒体窗环形起点已恢复：${mediaTabWindowStart.toMap()}")
            }
        }
    }

    /** R92：环形起点落盘（tmp+rename 原子替换；度量性状态，失败静默下轮再写） */
    internal fun persistMediaWindowStarts() {
        runCatching {
            val j = org.json.JSONObject()
            mediaTabWindowStart.forEach { (k, v) -> j.put(k.toString(), v) }
            val f = java.io.File(appFilesDir, "media_window_start.json")
            val tmp = java.io.File(appFilesDir, f.name + ".tmp")
            tmp.writeText(j.toString())
            if (!tmp.renameTo(f)) {
                runCatching { f.writeText(j.toString()) }
                tmp.delete()
            }
        }
    }

    /** 类型 tab 上次刷新的窗口频道集（mediaType → ids）：轮换观测日志"新进池/轮休"计数用 */
    internal val mediaTabLastWindow = java.util.concurrent.ConcurrentHashMap<Int, Set<Long>>()

    /**
     * R75-B 轮内余量缓冲（修"拉 ~100-150 条 take(20) 丢弃 80%"）：
     * 每页各频道按配额合拉 ~100 条、游标已按实际拉取量前进，旧实现 take(limit) 后
     * 其余条目随游标前进而被永久丢弃（低频频道稀有帖子的大部分死在展示前）。
     * 现改为截下部分暂存本缓冲，轮内翻页先吃余量、不够再补拉——拉多少用多少。
     *
     * 口径与边界：
     *  - [aggregateLeftoverTag] = 产出该批余量时返回给调用方的游标串：只有同一游标链
     *    回来才认领（游标按 chatId 键控，余量正是"旧游标→新游标之间已拉未展示"的货）；
     *  - 刷新（cursor==null）/ 游标链对不上（别的聚合源交叉翻页）→ 余量作废现拉，
     *    行为回退旧版，无正确性风险；搜索回退（query != null）不碰本缓冲；
     *  - 进程内存态：重启后 cursor==null 自然重抽，无需持久化；单轮上限 ~150 条
     *    （Σ配额 − 页长），内存可忽略。
     */
    internal val aggregateLeftoverLock = Any()
    internal var aggregateLeftovers: List<VideoItem> = emptyList()
    internal var aggregateLeftoverTag: String? = null

    /**
     * R94 媒体直拉路径的余量缓冲（fetchAggregatedMediaAll / fetchAggregatedMedia 多频道
     * 调用配套，仿 R75-B aggregateLeftovers 先例）：媒体窗口为保频道覆盖每频道拉 50 条、
     * 一窗合拉至多 ~2000 条，而页长只有 100（分屏直拉甚至只有 24）——R92 配额采样只解决
     * "哪 100 条进页"，截下的部分此前随游标前进被永久丢弃（频道历史以约 20 倍于消费的
     * 速度空烧，是"刷到底"的第一推手）。现截下部分带游标标签暂存，同一游标链回来
     * 先吃余量、不够再补拉——拉多少用多少，游标语义不动。
     *
     * 口径与边界：
     *  - key = "车道:mediaType"（"all"=fetchAggregatedMediaAll，"media"=fetchAggregatedMedia
     *    多频道调用）——两条车道的游标格式不同（前者带 @rotation 段），同 mediaType 也
     *    不共享缓冲，防 Feed 与分屏交替翻页时互相作废对方的余量；
     *  - [mediaLeftoverTags] = 产出该批余量时返回给调用方的游标串，同一游标链才认领；
     *    刷新轮（cursor==null）/游标链对不上 → 余量作废现拉，行为回退旧版，无正确性风险；
     *  - 单车道容量封顶 [MEDIA_LEFTOVER_MAX]：余量条目带 minithumbnail 字节，
     *    不设上限时三类型车道合计最差可占数 MB 内存；
     *  - 进程内存态不落盘：重启后 cursor==null 重扫自然召回（未看条目不在已看底账，
     *    从顶重扫会再次命中它们）——这正是媒体游标不落盘的原因之一（落盘会把
     *    随进程死掉的余量永久埋掉）。
     */
    internal val mediaLeftoverLock = Any()
    internal val mediaLeftovers = HashMap<String, List<VideoItem>>()
    internal val mediaLeftoverTags = HashMap<String, String>()

    /**
     * v3 网关级开关：是否级联拉取评论区的资源视频（默认开）。
     * 关闭后 fetchFromChannel 不再调 GetMessageThreadHistory，
     * 彻底免除级联带来的 flood wait 风险。由设置页直接赋值。
     */
    override var includeCommentResources: Boolean = true

    companion object {
        /**
         * t.me 链接形态判定（答案区解析分流的根修）：**消息级链接**
         * （t.me/<name>/<msgId>、t.me/c/<cid>/<msgId>）才能走 GetMessageLinkInfo 出可播条目；
         * 频道主页/邀请类裸链接（t.me/<name>、t.me/+xxx）恒被 TDLib 拒
         * （"Wrong message link URL"，实测 jisou 秒回 28 条频道链接 8 次尝试全败、
         * 白烧请求还染死信表）——它们的正确呈现是带真标题的可点频道行
         * （SearchPublicChat 换 chatId+标题），不是内容卡。
         */
        fun isMessageLevelLink(link: String): Boolean {
            val path = link.substringAfter("t.me/", "").substringBefore('?').trim('/')
            if (path.isEmpty()) return false
            val segs = path.split('/')
            return when {
                segs[0] == "c" -> segs.size >= 3 // t.me/c/<cid>/<msgId>
                segs[0].startsWith("+") || segs[0].equals("joinchat", ignoreCase = true) -> false // 邀请链接
                else -> segs.size >= 2 && segs[1].toLongOrNull() != null // t.me/<name>/<msgId>
            }
        }

        /**
         * R244 代理决策纯函数（单一真源，单测覆盖）：
         * [configuredReachable] = 配置的代理端口 socket 可达；[localProbePort] = 本机
         * 常见代理端口探测命中的端口（null = 无）。返回最终代理 (host, port, 自动检测?)，
         * null = 直连。口径：
         *  - 配置代理开且可达 → 照旧用配置（设置页填的局域网电脑代理等场景）；
         *  - 其余情形（配置关 / 配置不可达 / host 空）→ 本地探测命中即自动接入
         *    127.0.0.1:探测端口（系统级代理 App 的本地监听口；TDLib 原生库不读系统
         *    HTTP 代理，不显式接入就等于直连被墙——新设备登录 P0 实锤②根因）；
         *  - 全无 → null（直连：模拟器 adb reverse 之外，真机此路大概率不通，
         *    由 [AUTH_NET_WATCH_MS] 旁路监视兜底给用户明确错误）。
         */
        internal fun decideProxy(
            configuredEnabled: Boolean,
            configuredHost: String,
            configuredPort: Int,
            configuredReachable: Boolean,
            localProbePort: Int?,
        ): Triple<String, Int, Boolean>? {
            if (configuredEnabled && configuredHost.isNotBlank() && configuredReachable) {
                return Triple(configuredHost, configuredPort, false)
            }
            if (localProbePort != null && localProbePort > 0) {
                return Triple("127.0.0.1", localProbePort, true)
            }
            return null
        }

        /** 答案区 eager 解析单批上限（"群友刚答的/群友搜到过"两区共用：每区每词/轮最多前 8 条进解析窗）。
         *  public：FeedViewModel 侧取同一个窗口值，避免两处常量漂移 */
        const val ANSWER_INLINE_RESOLVE_MAX = 8
    }

    // ---------------- 扫码登录状态字段（R46 启动竞态修复：声明必须在 init 块之前） ----------------
    // init 块里的授权状态收集协程一点火就可能立刻收到 UpdateAuthorizationState 并写
    // _qrLoginLink；字段若按原位置声明在类体后段，更新先到一步就是 NPE 崩进程
    // （模拟器快速重启实锤 FATAL）。完整语义注释不变，此处仅声明位置前移。

    /**
     * 扫码登录链接流：非 null = TDLib 正处"等待对方设备确认"态，值形如
     * tg://login?token=...，UI 渲染成二维码即可（渲染器见 ui/QrCode.kt，自研零依赖）。
     * token 过期时 TDLib 自动推新链接，本流会跟着更新，UI 无需轮询/手动刷新。
     * 对方设备确认后状态机自动变 Ready（更新流既有口径，本类零新增处理）。
     */
    internal val _qrLoginLink = MutableStateFlow<String?>(null)
    val qrLoginLink: StateFlow<String?> = _qrLoginLink.asStateFlow()

    /** 内部标记：TDLib 状态机当前是否处于 WaitOtherDeviceConfirmation（决定要不要重发请求） */
    @Volatile
    internal var qrAwaitingScan = false

    /** 发起幂等闸：与 submitPhone 的 phoneSubmitInFlight 同款，防连点撞 "another authorization query" */
    @Volatile
    internal var qrStartInFlight = false

    /** R97 治理循环句柄：close() 时取消。声明必须在 init 块之前——init 里赋值后，
     *  若声明在类体后段，其初始化器（=null）会后到一步把句柄冲掉（R46 同款陷阱） */
    internal var storageGovernJob: kotlinx.coroutines.Job? = null

    // ---------------- R144 快拍条状态字段（声明必须在 init 块之前，R46 同款陷阱：
    // init 里的 UpdateChatActiveStories 收集协程一点火就可能收到更新并写本字段） ----------------

    /** 频道活跃快拍存储：chatId → ChatActiveStories（story 空的频道不入表=快拍条空态不占位） */
    internal val _chatActiveStories = MutableStateFlow<Map<Long, TdApi.ChatActiveStories>>(emptyMap())

    /**
     * 会话级已看账（R144 需求 3：本地记已看、不持久化、重启即忘）：
     * 键格式 "chatId:storyId"。StateFlow 而非普通集合——连播页每看一条翻一次状态，
     * 快拍环由红转灰靠重组即见（与 [ChannelStoriesBrief.isStoryUnwatched] 的判定配套）。
     * 有意不调 OpenStory/CloseStory 往服务端写已看（写操作红线：本地已看是纯客户端语义）。
     */
    internal val _storyWatched = MutableStateFlow<Set<String>>(emptySet())

    /** 快拍条全量快照刷新时间戳（TTL 闸 [CHAT_STORIES_REFRESH_TTL_MS] 用） */
    @Volatile
    internal var chatStoriesRefreshAtMs = 0L

    /** 全量快照刷新的并发互斥（防两个调用方同时过 TTL 检查双跑一轮） */
    internal val chatStoriesRefreshMutex = Mutex()

    init {
        // R97 存储治理·启动前闸门（此刻 TDLib 尚未开库，文件操作安全；全程只动 files/tdlib/ 内
        // TDLib 自有缓存，files/deepindex/ 与 files/ 根下业务 json 一律不碰）：
        // 1) 消息库（db.sqlite 三件套）超预算则整体重置——TDLib 1.8.67 没有消息库体积控制
        //    API，sqlite 也只有重建文件才能真正缩容；消息/会话缓存可从服务器重建，
        //    代价是重置后首次启动全量重同步一次。
        // 2) temp 死残留清扫（上轮会话的下载残骸，24h 未动才删）。
        if (cacheLimitBytes > 0) {
            runCatching { resetMessageDatabaseIfOverBudget(context.filesDir) }
                .onFailure { Log.w(TAG, "存储治理·消息库重置检查失败: ${it.message}") }
        }
        runCatching { sweepTempDirs(context.filesDir, TEMP_STALE_PREINIT_MS) }
            .onFailure { Log.w(TAG, "存储治理·启动前 temp 清扫失败: ${it.message}") }
        // R52 冷启动打点：拆分 native 库加载与客户端构造/初始化两段耗时
        dev.tgfeed.core.diag.ColdStartProbe.mark("TDLib native 库加载开始")
        System.loadLibrary("tdjni")
        dev.tgfeed.core.diag.ColdStartProbe.mark("TDLib native 库加载完成")
        client = TdClient(
            filesDir = context.filesDir.absolutePath + "/tdlib",
            verbosityLevel = 1,
            apiId = apiId,
            apiHash = apiHash,
        )
        client.init()
        dev.tgfeed.core.diag.ColdStartProbe.mark("TDLib 客户端构造+init 完成")
        // R97 存储治理·原生文件 GC 开关：本 AAR（TDLib 1.8.67）的 libtdjni.so 实证内置
        // FileGcWorker（td/telegram/files/FileGcParameters.cpp），由以下本地选项驱动，
        // SetOption 即时生效、无需授权；开启后 TDLib 按周期自动把文件缓存总量压回
        // storage_max_files_size 以内（最久未访问优先，immunity 保护期内的不删）。
        // 这让"缓存上限"从启动时跑一次的人工清理，变成 TDLib 自己常驻执行的真限额。
        if (cacheLimitBytes > 0) {
            scope.launch(Dispatchers.IO) {
                val gcOptions = listOf(
                    "use_storage_optimizer" to TdApi.OptionValueBoolean(true),
                    // 该选项值域上限是 Int32.MAX（实测 3G 被拒："outside of the valid range
                    // [0, 2147483647]"）——超上限压到上限，原生 GC 略偏紧不影响正确性
                    //（人工水位两道闸仍按 cacheLimitBytes 精确执行）
                    "storage_max_files_size" to TdApi.OptionValueInteger(minOf(cacheLimitBytes, Int.MAX_VALUE.toLong())),
                    // 30 天未访问的文件即便总量未超限也允许清掉（防死缓存永久占位）
                    "storage_max_time_from_last_access" to TdApi.OptionValueInteger(30L * 24 * 3600),
                    // 免疫期 1h：覆盖在播+预载窗口即可。TDLib 默认 24h 实测会让
                    // "当天重度刷机"在限额之上白攒一天（模拟器实锤 2.3G 超限时清出 0）
                    "storage_immunity_delay" to TdApi.OptionValueInteger(3600),
                )
                for ((name, value) in gcOptions) {
                    runCatching { client.send(TdApi.SetOption(name, value)) }
                        .onSuccess { Log.i(TAG, "存储治理·原生GC选项已设置 $name") }
                        .onFailure { Log.w(TAG, "存储治理·原生GC选项设置失败 $name: ${it.message}") }
                }
            }
        }
        // 代理自动探测：能连上才启用，连不上直连（手机上有 VPN/代理 App 时流量走系统层，
        // 无需在 App 里配置；模拟器 127.0.0.1:7897 经 adb reverse 指向宿主机 Clash）。
        // 注意：本预编译库的 TDLib 版本较新，AddProxy 接收 Proxy 对象而非 (host, port)
        //
        // R244 新设备登录修复（P0，用户三连实锤②）：
        // 旧逻辑只探测配置的代理（默认 127.0.0.1:7897 = 模拟器 adb reverse 场景），
        // 真机上该地址必失败 → **静默直连** → 直连被墙 → 登录全程无响应。新逻辑：
        //  a) 配置代理可达 → 照旧 AddProxy；
        //  b) 配置不可达 / proxyEnabled=false（含历史遗留 false 值，实锤：用户机
        //     proxy_enabled=false + 模拟器默认地址）/ host 空 → 探测本地常见 SOCKS5
        //     端口（Clash/v2rayNG 系统代理 App 的本地监听口；TDLib 原生库不读
        //     Android 系统 HTTP 代理，不显式接入=直连）。命中 → AddProxy + 落盘
        //     （proxyPersistHook 写 prefs：下次启动直达、设置页同步显示）；
        //  c) 全部失败 → 直连，并把结果写 proxyHint 流让登录页可见（不再静默）。
        //     决策口径单一真源 = decideProxy 纯函数（companion，单测覆盖）。
        scope.launch(Dispatchers.IO) {
            val configuredReachable = if (proxyEnabled && proxyHost.isNotBlank()) {
                runCatching {
                    java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress(proxyHost, proxyPort), CONFIGURED_PROXY_PROBE_TIMEOUT_MS)
                    }
                    true
                }.getOrDefault(false)
            } else {
                false
            }
            val localProbePort = LOCAL_SOCKS_PROBE_PORTS.firstOrNull { port ->
                runCatching {
                    java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress("127.0.0.1", port), LOCAL_SOCKS_PROBE_TIMEOUT_MS)
                    }
                    true
                }.getOrDefault(false)
            }
            val decision = decideProxy(proxyEnabled, proxyHost, proxyPort, configuredReachable, localProbePort)
            if (decision != null) {
                val (host, port, autoDetected) = decision
                runCatching {
                    client.send(
                        TdApi.AddProxy(
                            TdApi.Proxy(host, port, TdApi.ProxyTypeSocks5(null, null)),
                            true,
                            "user-clash",
                        )
                    )
                }
                Log.d(TAG, "代理已启用 $host:$port${if (autoDetected) "（本地自动检测）" else ""}")
                if (autoDetected) {
                    _proxyHint.value = "已自动启用本地代理 $host:$port（检测到手机代理软件）"
                    // 落盘失败不影响本次会话（代理已生效），下次启动重探即可
                    runCatching { proxyPersistHook?.invoke(host, port) }
                        .onFailure { Log.w(TAG, "代理自动检测落盘失败（不影响本次）: ${it.message}") }
                }
            } else if (proxyEnabled && proxyHost.isNotBlank()) {
                // 配置开着却不可达且本地无代理可接 → 直连，但让用户看见（治静默）
                _proxyHint.value = "代理 $proxyHost:$proxyPort 不可达，直连中——连不上请检查 VPN/代理"
                Log.w(TAG, "代理 $proxyHost:$proxyPort 不可达且本地未发现代理端口，本次直连（系统级 VPN 会自动接管）")
            }
            // proxyEnabled=false 且本地无代理 → 不提示（用户显式关 + 系统 VPN 接管场景，
            // 不惊扰；真连不上由 AUTH_NET_WATCH_MS 旁路监视兜底给明确错误）
        }
        // 敏感内容放开：在本 AAR 的 libtdjni.so（TDLib 1.8.67）中实证存在
        // "ignore_sensitive_content_restrictions" 选项字符串，SetOption/OptionValueBoolean
        // 签名已 javap 确认。能否修改取决于服务端下发的 can_ignore_sensitive_content_restrictions
        // 配置——账号/地区不满足时会抛 400 "can't be changed by the user"，失败仅记日志不崩溃。
        scope.launch(Dispatchers.IO) {
            runCatching {
                client.send(TdApi.SetOption("ignore_sensitive_content_restrictions", TdApi.OptionValueBoolean(true)))
            }.onSuccess {
                Log.i(TAG, "敏感内容选项 ignore_sensitive_content_restrictions 已开启")
            }.onFailure {
                Log.w(TAG, "敏感内容选项设置失败（可能该账号/地区被服务端禁止）: ${it.message}")
            }
        }
        // R46 启动竞态修复：扫码登录三字段（_qrLoginLink/qrAwaitingScan/qrStartInFlight）
        // 的类体声明已前移到 init 块之前——本收集协程一点火就可能立刻收到
        // UpdateAuthorizationState 并写 _qrLoginLink，字段若在类体更靠后声明，
        // 更新先到一步就是 NPE 崩进程（模拟器快速重启实锤）。
        scope.launch(Dispatchers.IO) {
            client.updatesOf<TdApi.UpdateAuthorizationState>().collect { upd ->
                val authState = upd.authorizationState
                _state.value = when (authState) {
                    // ktx 的 init() 在全新库/库损坏时不会下发参数（平板实测踩坑：
                    // "Initialization parameters are needed"），标准姿势是自己接这个状态
                    is TdApi.AuthorizationStateWaitTdlibParameters -> {
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                client.send(
                                    TdApi.SetTdlibParameters(
                                        false,
                                        context.filesDir.absolutePath + "/tdlib",
                                        context.filesDir.absolutePath + "/tdlib",
                                        ByteArray(0),
                                        true, true, true, true,
                                        apiId, apiHash,
                                        "zh-CN",
                                        android.os.Build.MODEL ?: "Android",
                                        android.os.Build.VERSION.RELEASE ?: "",
                                        "0.1.0",
                                    )
                                )
                            }.onFailure { Log.e(TAG, "SetTdlibParameters 失败: ${it.message}") }
                        }
                        GatewayState.Loading
                    }
                    is TdApi.AuthorizationStateWaitPhoneNumber -> GatewayState.NeedPhone
                    // 扫码登录（javap 实证本 AAR：AuthorizationStateWaitOtherDeviceConfirmation
                    // 有 public String link 字段，形如 tg://login?token=...）：link 交给 UI 渲染
                    // 二维码。token 过期时 TDLib 自动推新 link（本分支反复触发），UI 无需轮询刷新。
                    // _state 仍保持 NeedPhone——GatewayState 接口不在本任务改动清单，扫码态完全由
                    // qrLoginLink 非空表达；对方设备确认后 TDLib 自动推 Ready，走现有登录链路口径
                    is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                        _qrLoginLink.value = authState.link
                        qrAwaitingScan = true
                        GatewayState.NeedPhone
                    }
                    is TdApi.AuthorizationStateWaitCode -> GatewayState.NeedCode
                    is TdApi.AuthorizationStateWaitPassword -> GatewayState.NeedPassword
                    is TdApi.AuthorizationStateReady -> GatewayState.Ready
                    is TdApi.AuthorizationStateClosed -> GatewayState.Error("会话已关闭，请重启应用")
                    else -> GatewayState.Loading
                }
                // 离开扫码等待态（转手机号/验证码/密码/Ready/关闭）即清掉链接与标记，
                // 防 UI 拿着过期 token 的二维码继续等
                if (authState !is TdApi.AuthorizationStateWaitOtherDeviceConfirmation) {
                    qrAwaitingScan = false
                    _qrLoginLink.value = null
                }
            }
        }
        // 分组（文件夹）列表：攒 UpdateChatFolders（频道枚举用它补全分组里的频道；
        // R220 起存全量 ChatFolderInfo——查重/清理工具要名字与可分享态，见 _chatFolderInfos）
        scope.launch(Dispatchers.IO) {
            client.updatesOf<TdApi.UpdateChatFolders>().collect { upd ->
                _chatFolderInfos.value = upd.chatFolders.filterNotNull().toList()
                _chatFoldersSynced.value = true
            }
        }
        // R144 快拍条：订阅频道活跃快拍变更（新 story 上线 / 到期 / 官方客户端已看水位
        // 变化都会推 UpdateChatActiveStories），被动喂快拍存储——会话加载时 TDLib 自行
        // 同步各频道活跃快拍，这些更新流进来快拍条就活了；全量快照兜底由
        // refreshChatActiveStories 在首次进 Feed 时补一次（TTL 10 分钟）。
        // 空表更新（story 全部到期/删除）要把频道键摘掉，保住"无 story 不占位"。
        scope.launch(Dispatchers.IO) {
            client.updatesOf<TdApi.UpdateChatActiveStories>().collect { upd ->
                val s = upd.activeStories
                if (s.stories.isEmpty()) {
                    _chatActiveStories.value = _chatActiveStories.value - s.chatId
                } else {
                    _chatActiveStories.value = _chatActiveStories.value + (s.chatId to s)
                }
            }
        }
        // 连接状态实时可见（用户要能看见"连没连上 TG"）
        scope.launch(Dispatchers.IO) {
            client.updatesOf<TdApi.UpdateConnectionState>().collect { upd ->
                _connectionState.value = when (upd.state) {
                    is TdApi.ConnectionStateReady -> "已连接"
                    is TdApi.ConnectionStateUpdating -> "同步中"
                    is TdApi.ConnectionStateConnectingToProxy -> "连接代理中"
                    is TdApi.ConnectionStateWaitingForNetwork -> "等待网络"
                    else -> "连接中"
                }
            }
        }
        // R196 存储治理调度收编：治理循环启动挪到类体尾部 init 块②（storageGovernor
        // 的 lazy 引用了类体后段声明的 fullDlFallbackFiles/appPrefs——首个 init 块触达
        // 会踩「字段未初始化」顺序陷阱，R46 同款，见下方块注释）。
        // 资源搜索群收割（P1 只读监控）：授权 Ready 后启动收割主循环。
        // 全程零发送（GetChatHistory/GetUser/SearchPublicChat 只读三件套）；
        // 开关/群清单/节奏/预算全走 AppPrefs（见 group_harvest_* / flood_harvest_per_hour），
        // 一切失败静默降级，绝不影响主流程
        scope.launch(Dispatchers.IO) {
            runCatching {
                awaitAuthorized()
                // R52 冷启动降噪：收割循环延 20s 再起步——授权后的头一段 TDLib 请求
                // 通道全部让给首页首拉（收割是常驻背景事，晚 20s 无感）
                kotlinx.coroutines.delay(20_000)
                groupHarvester.start()
            }.onFailure { Log.w(TAG, "groupHarvester 启动失败（本轮不收割）: ${it.message}") }
        }
    }

    // ---------------- 登录 ----------------

    /**
     * R244 登录 auth 系请求的旁路网络监视（治实锤③「静默失败」）：
     * TDLib 在网络不通时对 auth 系请求**不报错只挂起**（请求在 TDLib 内部等网络），
     * 发请求的协程连同后续代码（含一切 UI 反馈）被无限拖住——用户侧只剩一个永远
     * 「发送中…」的按钮。监视必须**先于 send 启动**（send 挂起时后面的代码轮不到执行）。
     * [initial] = 发请求时刻的 state 快照：只认「之后的变化」（NeedCode/NeedPassword/
     * Ready/Error 任一）——防上次遗留的 Error 态让监视秒停（重试场景），也防真错误
     * （如「手机号格式不对」TDLib 秒回的 Error）被网络错误文案覆盖。窗内无变化且
     * TG 链路未就绪 → 写 [AUTH_NET_ERROR_TEXT]（用户实锤③要求的明确提示）。
     * 连点时取消旧监视（[authNetWatchJob]）。
     */
    internal fun startAuthNetWatch(initial: GatewayState) {
        authNetWatchJob?.cancel()
        authNetWatchJob = scope.launch(Dispatchers.IO) {
            val ok = kotlinx.coroutines.withTimeoutOrNull(AUTH_NET_WATCH_MS) {
                _state.first {
                    it !== initial && (it is GatewayState.NeedCode || it is GatewayState.NeedPassword ||
                        it is GatewayState.Ready || it is GatewayState.Error)
                }
            }
            if (ok == null && connectionState.value != "已连接") {
                _state.value = GatewayState.Error(AUTH_NET_ERROR_TEXT)
            }
        }
    }

    /** 发号幂等：进行中的调用未完成时忽略后续调用（双击/连点会撞 Telegram 的
     *  "another authorization query has started"）；撞到了说明第一次已把验证码发出去了，
     *  直接按"已发送"进入验证码页 */
    @Volatile
    internal var phoneSubmitInFlight = false

    override suspend fun submitPhone(phone: String) {
        if (phoneSubmitInFlight) return
        phoneSubmitInFlight = true
        try {
            // R244：先起网络监视再发请求——网络不通时 send 挂起且不报错（见
            // startAuthNetWatch 注释），用户侧「无任何反馈」（实锤③根因）
            startAuthNetWatch(_state.value)
            // 注意：扫码等待态（WaitOtherDeviceConfirmation）下本调用同样合法——TDLib 会
            // 隐式取消扫码查询并直接转入短信验证码流程（tdlib/td#1645 "Skipping QR code
            // authentication" 的官方答复口径），所以"取消扫码再登手机号"无需任何前置调用
            runCatching { client.send(TdApi.SetAuthenticationPhoneNumber(phone.trim(), null)) }
                .onFailure { e ->
                    val msg = e.message ?: ""
                    when {
                        msg.contains("another authorization", ignoreCase = true) -> {
                            // 第一次调用已经在发验证码了，直接进验证码页
                            _state.value = GatewayState.NeedCode
                        }
                        // 兜底逃生门：个别 TDLib 版本不允许扫码等待态直接收手机号
                        //（400 Unexpected setAuthenticationPhoneNumber）——协议层无解，
                        // 按任务口径走 close+重建网关（recreateHook 由 App.kt 装配时注入）
                        qrAwaitingScan &&
                            msg.contains("unexpected setauthenticationphonenumber", ignoreCase = true) -> {
                            Log.w(TAG, "扫码态直接转手机号被本版 TDLib 拒绝，触发 close+重建兜底")
                            _qrLoginLink.value = null
                            qrAwaitingScan = false
                            // 重建会构造新 TdClient（估 50~300ms），不能挡 UI 协程，挪 IO
                            scope.launch(Dispatchers.IO) { recreateHook?.invoke() }
                        }
                        else -> _state.value = GatewayState.Error(friendlyError(e, "发送手机号失败"))
                    }
                }
        } finally {
            phoneSubmitInFlight = false
        }
    }

    override suspend fun submitCode(code: String) {
        // R244：同 submitPhone——先起网络监视（send 在网络不通时挂起不报错）
        startAuthNetWatch(_state.value)
        runCatching { client.send(TdApi.CheckAuthenticationCode(code.trim())) }
            .onFailure { _state.value = GatewayState.Error(friendlyError(it, "验证码校验失败")) }
    }

    override suspend fun submitPassword(password: String) {
        // R244：同 submitPhone——先起网络监视（send 在网络不通时挂起不报错）
        startAuthNetWatch(_state.value)
        runCatching { client.send(TdApi.CheckAuthenticationPassword(password)) }
            .onFailure { _state.value = GatewayState.Error(friendlyError(it, "两步验证失败")) }
    }

    // ---------------- 扫码登录（TG 协议 auth.exportLoginToken 系能力） ----------------
    // 注：_qrLoginLink/qrAwaitingScan/qrStartInFlight 三字段声明已前移到 init 块之前
    // （R46 启动竞态修复：授权状态收集协程启动即可能写 _qrLoginLink，声明位置必须更早）。

    /**
     * 兜底重建回调（由 App.kt 装配时注入）：扫码等待态下 submitPhone 若被本版 TDLib
     * 拒绝（协议层没有原生"取消扫码"接口），唯一可靠的回手机号路径是 close+重建客户端。
     * 正常版本（≥1.7 起支持扫码态直接收手机号）永远不会触达。
     */
    @Volatile
    var recreateHook: (() -> Unit)? = null

    /**
     * 发起扫码登录。
     * javap 实证本 AAR（TDLib 1.8.67）签名：
     *   TdApi.RequestQrCodeAuthentication(long[] otherUserIds) -> TdApi.Ok
     * otherUserIds 传空数组——该参数语义是"本客户端上已登录的其他账号 id"，全新登录场景为空。
     * 协议约束：只能在 WaitPhoneNumber 态发起；已处于扫码等待态/进行中时幂等空转，
     * 二维码由授权更新流持续供给（含 token 过期自动续期）。
     */
    suspend fun startQrLogin() {
        if (qrAwaitingScan || qrStartInFlight) return
        if (_state.value !is GatewayState.NeedPhone) return
        qrStartInFlight = true
        try {
            // R244：先起旁路网络监视再发请求——网络不通时 RequestQrCodeAuthentication
            // 同样挂起不报错（TDLib 内部等网络），UI 只会无限「正在获取二维码…」
            // （实锤①「扫码登录不见了」的用户可感知面：QR 永远出不来）。窗内无链接
            // 且未进等待态 → 明确报网络错误，用户不再干等。
            authNetWatchJob?.cancel()
            authNetWatchJob = scope.launch(Dispatchers.IO) {
                val link = kotlinx.coroutines.withTimeoutOrNull(AUTH_NET_WATCH_MS) {
                    _qrLoginLink.first { it != null }
                }
                if (link == null && !qrAwaitingScan && _state.value is GatewayState.NeedPhone) {
                    _state.value = GatewayState.Error(AUTH_NET_ERROR_TEXT)
                }
            }
            runCatching { client.send(TdApi.RequestQrCodeAuthentication(LongArray(0))) }
                .onFailure { e ->
                    val msg = e.message ?: ""
                    // "another authorization" = 已有授权查询在跑（大概率就是上一次扫码请求），
                    // 等更新流喂链接即可，不当错误处理
                    if (!msg.contains("another authorization", ignoreCase = true)) {
                        Log.w(TAG, "扫码登录发起失败: $msg")
                        _state.value = GatewayState.Error(friendlyError(e, "扫码登录发起失败"))
                    }
                }
        } finally {
            qrStartInFlight = false
        }
    }

    /**
     * 取消扫码登录（回手机号路径）。
     * TDLib 没有原生"取消扫码"函数（javap 扫本 AAR 类清单实证：无 CancelXxx/Revoke 系），
     * 正确姿势是"提交手机号时隐式取消"（见 submitPhone 注释），后台查询随 token 过期
     * （1~2 分钟）自然失效，无泄漏。
     * 注意刻意不清 _qrLoginLink：链接在 token 过期前仍然有效，用户取消后立刻重新点进
     * 扫码页要能秒出二维码——清掉就得干等 TDLib 下一轮 token 轮换推送（最长约 1 分钟）。
     * UI 的"取消"语义由 AuthScreen 本地切 stage 表达，与本流解耦。
     */
    suspend fun cancelQrLogin() {
        Log.d(TAG, "cancelQrLogin: 协议层无取消接口，本地等待 token 自然过期")
    }

    // ---------------- 频道 ----------------

    /**
     * 等待授权完成。注意：不用 ktx 的 awaitReady()——它等的是 Ready 事件，已授权会话会错过事件导致永久挂起。
     *
     * P1-4 超时兜底（30s）：SetTdlibParameters 失败/库损坏/网络卡死时 _state 会永留
     * Loading，所有 fetchVideos/ensurePlayable/listChannels 随之永久挂起，VM 的
     * _loading 闸一并卡死——除杀进程外无恢复路径。超时后：
     *  1. 仅当仍处于 Loading 时把 _state 改写为 Error（给 UI"重试/重配"出口）；
     *     NeedPhone/NeedCode/NeedPassword 是用户交互中的正常态，绝不覆盖；
     *  2. 向上抛错，让调用方走自己的 runCatching 正常收尾（不永久挂起）。
     * 若之后 TDLib 真的到达 Ready，更新流会自然把 _state 覆盖回 Ready，Error 是瞬态的。
     */
    internal suspend fun awaitAuthorized() {
        try {
            kotlinx.coroutines.withTimeout(AUTH_TIMEOUT_MS) {
                _state.first { it is GatewayState.Ready }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            if (_state.value is GatewayState.Loading) {
                _state.value = GatewayState.Error("授权超时，请检查网络/代理")
            }
            throw IllegalStateException("授权超时，请检查网络/代理", e)
        }
    }

    /**
     * 分组（文件夹）全量信息缓存（R220 升级）：从 UpdateChatFolders 更新流攒
     * ChatFolderInfo 全量（名字/可分享态/是否有我的邀请链接）。旧口径只攒 id——
     * R220 起频道礼包查重、测试文件夹清理工具都要名字与可分享态，id 单列不够用；
     * [chatFolderIds] 改为从这里派生，id 口径的既有读点零改动。
     * 授权前也可能到（TDLib 启动即推一次当前夹列表，真零夹用户也推空表）。
     */
    private val _chatFolderInfos = MutableStateFlow<List<TdApi.ChatFolderInfo>>(emptyList())

    /** [UpdateChatFolders][TdApi.UpdateChatFolders] 是否至少到过一次（区分「还没到」与「真零夹」） */
    private val _chatFoldersSynced = MutableStateFlow(false)

    /** 当前文件夹信息快照（只读视图；R220 查重/清理工具的列表源） */
    internal val chatFolderInfos: List<TdApi.ChatFolderInfo> get() = _chatFolderInfos.value

    /** 派生自 [_chatFolderInfos]（旧口径兼容：频道枚举用它补全分组里的频道） */
    internal val chatFolderIds: List<Int> get() = _chatFolderInfos.value.map { it.id }

    /**
     * 等文件夹列表首个快照到达（授权后 TDLib 即推；10s 兜底防永等）。
     * R220 查重与清理工具在动手前调它——拿不到就按当前（可能空的）快照继续。
     */
    private suspend fun awaitChatFolderSync() {
        if (_chatFoldersSynced.value) return
        kotlinx.coroutines.withTimeoutOrNull(10_000) { _chatFoldersSynced.first { it } }
            ?: Log.w(TAG, "文件夹列表更新 10s 未到（登录未就绪或网络慢），按当前快照继续")
    }

    /**
     * R88 频道枚举去上限：把指定会话列表全量加载进本地——LoadChats 循环直到
     * TDLib 报 404（全量加载完成），这是覆盖长尾的正路：GetChats 只按活跃序从头取
     * 前 N 个，数百个频道挤在同一列表时，排后面的最不活跃部分永远枚举不到。
     * 主列表/归档/每个文件夹互相独立，每个列表都要各自跑到 404。
     * 返回 false = 中途失败（网络/限流等），调用方按已加载部分继续，不阻塞整表。
     * R169：单发过 [sendReq] 10s 超时（F1 家族）——native 冻死时旧口径单发永挂、
     * R88 的"循环到 404"依赖每次 send 返回，整环一起挂死；超时按异常走下方
     * Throwable 分支（按已加载部分继续），协程真取消仍上抛不受影响。
     */
    internal suspend fun loadAllChatsInList(chatList: TdApi.ChatList): Boolean {
        var rounds = 0
        while (true) {
            try {
                sendReq(TdApi.LoadChats(chatList, CHAT_ENUM_PAGE))
                if (++rounds >= 50) {
                    // 防御：正常几轮内必到 404，50 轮（万级会话）仍没到说明异常，按已加载部分继续
                    Log.w(TAG, "频道枚举：LoadChats 50 轮未到 404，按已加载部分继续")
                    return false
                }
            } catch (e: TdException) {
                if (e.code == 404) return true // 该列表已全量加载
                Log.w(TAG, "频道枚举：LoadChats 失败 code=${e.code}，按已加载部分继续")
                return false
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // 协程取消必须上抛
            } catch (e: Throwable) {
                Log.w(TAG, "频道枚举：LoadChats 异常 ${e.javaClass.simpleName}，按已加载部分继续")
                return false
            }
        }
    }

    /** H1 listChannels 进程内缓存（含归档/分组合成后的最终结果）；@Volatile 保证跨线程可见 */
    internal class ChannelListSnapshot(val atMs: Long, val channels: List<ChannelBrief>)

    @Volatile
    internal var channelListSnapshot: ChannelListSnapshot? = null

    override suspend fun listChannels(): List<ChannelBrief> {
        // H1：TTL 内直接命中缓存——聚合模式每翻一页都调本方法，不能让
        // "LoadChats 全量 + GetChats×(2+文件夹) + 逐 chat GetChat"（数百次 JNI 往返）每页重跑。
        // 代价：新关注/取关的频道最迟 5 分钟后才反映到聚合 Feed（可接受，刷 Feed 本就是增量混排）
        channelListSnapshot
            ?.takeIf { System.currentTimeMillis() - it.atMs < CHANNEL_LIST_TTL_MS }
            ?.let { return it.channels }
        awaitAuthorized()
        // 主列表 + 归档 + 全部分组：用户反馈"频道不全"——归档（ChatListArchive）和
        // 文件夹（ChatListFolder）里的频道之前漏了，只抓了主列表。
        // R88 去上限：每列表先 LoadChats 循环到 404 全量加载，再 GetChats 一次全取，
        // 长尾频道（按活跃序排在后面的）不再漏；整个枚举放 IO 调度器（调用方可能在主线程起协程）
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            loadAllChatsInList(TdApi.ChatListMain())
            val mainIds = client.send(TdApi.GetChats(TdApi.ChatListMain(), CHAT_ENUM_ALL_LIMIT)).chatIds.toList()
            val archIds = runCatching {
                loadAllChatsInList(TdApi.ChatListArchive())
                client.send(TdApi.GetChats(TdApi.ChatListArchive(), CHAT_ENUM_ALL_LIMIT)).chatIds.toList()
            }.getOrDefault(emptyList())
            val folderIds = chatFolderIds.flatMap { folderId ->
                runCatching {
                    loadAllChatsInList(TdApi.ChatListFolder(folderId))
                    client.send(TdApi.GetChats(TdApi.ChatListFolder(folderId), CHAT_ENUM_ALL_LIMIT)).chatIds.toList()
                }.getOrDefault(emptyList())
            }
            val chatIdSet = LinkedHashSet<Long>()
            chatIdSet += mainIds
            chatIdSet += archIds
            chatIdSet += folderIds
            Log.d(TAG, "频道枚举分列表：主=${mainIds.size} 归档=${archIds.size} 文件夹=${folderIds.size} 合计=${chatIdSet.size}（listChannels）")
            val result = chatIdSet.toList().mapNotNull { id ->
                val chat = runCatching { client.send(TdApi.GetChat(id)) }.getOrNull() ?: return@mapNotNull null
                val type = chat.type
                if (type is TdApi.ChatTypeSupergroup && type.isChannel) {
                    channelTitleCache[id] = chat.title
                    ChannelBrief(id, chat.title, null)
                } else {
                    null
                }
            }
            Log.d(TAG, "频道枚举：共 ${result.size} 个频道（listChannels 全量，含归档/文件夹）")
            channelListSnapshot = ChannelListSnapshot(System.currentTimeMillis(), result)
            result
        }
    }

    internal suspend fun channelTitle(chatId: Long): String {
        channelTitleCache[chatId]?.let { return it }
        // 平台类型防御 null；失败缓存空串（与原 getOrPut 语义一致：失败不重试）
        // R212 补 10s 超时（R45 家族）：本方法在 toVideoItem 逐条目路径上——
        // 频道主页/搜索/聚合的每一页都会走到；原生管线拥塞时裸 send 可挂数分钟
        // （实锤：频道主页首屏 loading 卡真、logcat 零后续请求），失败落空串缓存不重试
        val title = runCatching {
            kotlinx.coroutines.withTimeout(10_000) { client.send(TdApi.GetChat(chatId)).title }
        }.getOrNull() ?: ""
        // 并发下同频道可能重复 GetChat（无害，幂等查询）；putIfAbsent 保证写回原子、先到为准
        return channelTitleCache.putIfAbsent(chatId, title) ?: title
    }

    /**
     * 频道标题对外懒解析（设置页"屏蔽的频道"列表等 chatId → 标题 场景专用）：
     * 先走 [peekChatTitle] 零网络快照（channelTitleCache → 图谱 nodeTitle），
     * 未命中再补一次幂等 GetChat 并双回填（缓存 + 图谱），下次零网络可读。
     * 与私有 [channelTitle] 的区别：本方法供 UI 层直接调用，自带 [awaitAuthorized]
     * 授权等待（30s 超时兜底）与空串防御（缓存里的失败空串不当作终态，允许重试）；
     * 任何一步失败返回 null，由调用方回落占位文案（如"未知频道"）。
     */
    suspend fun chatTitleOf(chatId: Long): String? {
        peekChatTitle(chatId)?.let { return it }
        // 懒解析：GetChat 幂等补查（未加入的会话 TDLib 查不到会 400 → null 回落）
        val title = runCatching {
            awaitAuthorized()
            client.send(TdApi.GetChat(chatId)).title
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        channelTitleCache.putIfAbsent(chatId, title)
        runCatching { discoveryGraph.recordNodeTitle(chatId, title) }
        return title
    }

    /**
     * 零网络标题快照（搜索页答案区标题行专用："识别中…"回落前的最后兜底）：
     * 只读进程内 channelTitleCache（listChannels/拉取管道顺手喂的）+ 图谱 nodeTitle，
     * 绝不发请求——答案行渲染是高频同步路径，不能为标题烧 GetChat。
     */
    fun peekChatTitle(chatId: Long): String? {
        channelTitleCache[chatId]?.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching { discoveryGraph.nodeTitle(chatId) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    // ---------------- 相似频道推荐 ----------------

    /**
     * 相似频道推荐：GetChatSimilarChats 拿到与目标频道相似的 chatId 列表，
     * 再逐个 GetChat 取标题组成 ChannelBrief。推荐属于锦上添花：
     * 整体失败或任一 GetChat 失败都只是少推荐几项，返回 emptyList 不向上抛错。
     *
     * 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
     * GetChatSimilarChats(long chatId) -> Chats{totalCount: int, chatIds: long[]}
     *   —— 注意鉴别：1.8.67 没有 ChatSimilarChats 类型（javap 报找不到类），
     *      返回的就是普通 Chats，与本文件 listChannels() 的 GetChats 同一返回类型
     * GetChat(long chatId) -> Chat{title: String, type: ChatType}
     */
    suspend fun getSimilarChats(chatId: Long): List<ChannelBrief> = runCatching {
        awaitAuthorized()
        val chats = client.send(TdApi.GetChatSimilarChats(chatId))
        Log.d(TAG, "getSimilarChats: chatId=$chatId total=${chats.totalCount} ids=${chats.chatIds.size}")
        chats.chatIds.toList().mapNotNull { id ->
            val chat = runCatching { client.send(TdApi.GetChat(id)) }.getOrNull()
                ?: return@mapNotNull null
            channelTitleCache[id] = chat.title
            ChannelBrief(id, chat.title, null)
        }
    }.onFailure {
        Log.w(TAG, "getSimilarChats 失败 chatId=$chatId: ${it.message}")
    }.getOrDefault(emptyList())

    // ---------------- 频道头像 ----------------

    /**
     * 频道头像进程内缓存：chatId → 图片字节。
     * 头像不常变，进程内缓存即可；用 ConcurrentHashMap 是因为本方法会被
     * UI 层多行并发调用（LazyColumn 每行一个 LaunchedEffect）。
     */
    internal val channelAvatarCache = java.util.concurrent.ConcurrentHashMap<Long, ByteArray>()

    /**
     * 取频道真头像（小图）的字节；无头像或任何一步失败返回 null（调用方回落字母头像）。
     *
     * 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
     * GetChat(long chatId) -> Chat{photo: ChatPhotoInfo}
     *   —— 注意鉴别：Chat.photo 的类型是 ChatPhotoInfo（不是 ChatPhoto；
     *      ChatPhoto 是带 sizes 数组的大图集类型，small 字段在 ChatPhotoInfo 上）
     * ChatPhotoInfo{small: File, big: File}
     * File{id: int, local: LocalFile}；LocalFile{path: String, isDownloadingCompleted: boolean}
     * DownloadFile(int fileId, int priority, long offset, long limit, boolean synchronous) -> File
     *
     * 流程：GetChat 拿 photo.small → 已下载完成（local.isDownloadingCompleted）直接读文件字节；
     * 未下载用 DownloadFile(fileId, 16, 0, 0, synchronous=true) 同步拉（头像小图秒回）后再读。
     */
    suspend fun getChannelAvatar(chatId: Long): ByteArray? = runCatching {
        channelAvatarCache[chatId]?.let { return@runCatching it }
        awaitAuthorized()
        // W7：补 15s 超时 + 封面全局并发闸（原无超时无闸，慢代理下会无限挂起、
        // 且与封面下载互抢带宽）
        withTimeout(MSG_COVER_TIMEOUT_MS) {
            coverDownloadSem.withPermit {
                val small = client.send(TdApi.GetChat(chatId)).photo?.small
                    ?: return@withPermit null
                var file = small
                if (!file.local.isDownloadingCompleted) {
                    file = client.send(TdApi.DownloadFile(file.id, 16, 0L, 0L, true))
                    if (!file.local.isDownloadingCompleted) return@withPermit null
                }
                val path = file.local.path
                // 复用 toVideoItem 的教训：TDLib 偶发把目录路径标记为已完成（EISDIR），必须是真实文件
                if (path.isEmpty() || !java.io.File(path).isFile) return@withPermit null
                // R194④ 读盘挪 IO（R170 封面族同刀）：调用方是组合作用域的
                // LaunchedEffect（主调度器）——旧 readBytes 直读在抽屉 spring 窗内
                // 逐行上主线程 IO，与头像解码同族的帧预算窃贼
                val bytes = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    java.io.File(path).readBytes()
                }
                if (bytes.isEmpty()) return@withPermit null
                channelAvatarCache[chatId] = bytes
                bytes
            }
        }
    }.onFailure {
        Log.w(TAG, "getChannelAvatar 失败 chatId=$chatId: ${it.message}")
    }.getOrNull()

    // ---------------- Feed ----------------

    override suspend fun fetchVideos(request: FeedRequest, cursor: String?, limit: Int): FeedPage {
        Log.d(TAG, "fetchVideos: request=$request cursor=$cursor")
        awaitAuthorized()
        val query = request.searchQuery
        return when {
            // 带关键词：走 TDLib 服务端搜索（失败自动回退本地过滤，见各实现）
            !query.isNullOrBlank() && request.channelId != null ->
                searchInChannel(request.channelId, query, cursor, limit)
            !query.isNullOrBlank() ->
                searchGlobal(query, cursor, limit)
            request.channelId != null ->
                fetchFromChannel(request.channelId, cursor?.toLongOrNull() ?: 0L, limit, null)
            else ->
                fetchAggregated(cursor, limit, null)
        }
    }

    // ---- Feed 拉取公共 API 委托（实现见 GatewayFeedFetch.kt） ----
    suspend fun getMessageLink(item: VideoItem): String? = getMessageLinkImpl(item)
    suspend fun peekAlbumCaption(chatId: Long, albumId: Long, anchorMessageId: Long): String? = peekAlbumCaptionImpl(chatId, albumId, anchorMessageId)

    // ---- 下载泵/播放/存储治理公共 API 委托（实现见 GatewayDownload.kt） ----
    override suspend fun ensurePlayable(item: VideoItem, priority: Int): String = ensurePlayableImpl(item, priority)
    suspend fun ensurePlayableVariant(item: VideoItem, fileId: Int): String = ensurePlayableVariantImpl(item, fileId)
    suspend fun forceRedownloadVideo(fileId: Int): Boolean = forceRedownloadVideoImpl(fileId)
    override suspend fun prioritize(fileId: Int, priority: Int, offset: Long, limit: Long) = prioritizeImpl(fileId, priority, offset, limit)
    suspend fun downloadProgress(fileId: Int): Float = downloadProgressImpl(fileId)
    suspend fun reviveFileId(fileUniqueId: String): Int? = reviveFileIdImpl(fileUniqueId)
    fun downloadProgressFlow(fileId: Int): Flow<Float> = downloadProgressFlowImpl(fileId)
    suspend fun optimizeStorage(): String = optimizeStorageImpl()
    suspend fun forwardToSaved(item: VideoItem): Result<String> = forwardToSavedImpl(item)
    suspend fun translateCaption(item: VideoItem, targetLang: String = "zh"): Result<String> = translateCaptionImpl(item, targetLang)

    /**
     * R76：同 fileId 的 DownloadFile 全链互斥表（视频文件专用）。
     * TDLib 对同一文件的并发 DownloadFile 会互杀（"Canceled by another
     * downloadFile request"，R72 实测）：TgStreamServer 内部的 fileMutex 只串行
     * 了流服务器各连接之间的泵查询，护不住网关层的裸调用——ensurePlayable
     * 后台整载 / ensurePlayableVariant / PreloadScheduler 预热 /
     * downloadPartialCover 抽帧前缀，任何一路与泵的在途同步分块并发都会把泵
     * 杀掉。实测自激回路：「下载中」页的看门狗每 6s 记一次失败、退避后
     * ensurePlayable 重发整载，把泵刚发出的分块查询杀掉，水位永远 0、
     * 进度永远 0%（logcat 实锤：泵查询未竟 Canceled by another downloadFile
     * request 与等数据超时 pos=0 水位=0 循环往复）。
     * 收编后同文件的一切 DownloadFile 串行：整载类调用 synchronous=false
     * 持锁一瞬即发；泵的同步分块持锁到查询完成（换水位真实推进）。
     * 锁序：泵路径 信号量→流服务器连接锁→本表；网关路径只持本表，
     * 无反向嵌套，无死锁。锁对象按 fileId 惰性登记、会话级驻留（几十字节/条）。
     */
    internal val videoDlMutexes = java.util.concurrent.ConcurrentHashMap<Int, Mutex>()
    /** 边下边播服务器：按需分段拉取 TDLib 文件喂给 ExoPlayer */
    internal val streamServer by lazy {
        TgStreamServer(
            scope = scope,
            getFile = { id -> client.send(TdApi.GetFile(id)) },
            downloadPart = { id, off, len ->
                videoDlMutex(id).withLock {
                    // R166 窗口抢占闸（快播式插队核心）：TDLib 同文件同时只有一个下载
                    // 窗口锚点——同锚合并（优先级取高者）、异锚竞态互杀（R72 实证
                    // 「moov 尾探针与正播交错两边窗口全灭」）。旧实现跳变查询（seek 到
                    // 未下载区/尾探针）与在途整载裸竞速，输掉=查询被杀/悬挂 → 水位不前
                    // → 503/截断 → 播放器重试循环（用户实锤「必须整个视频下载完才能
                    // 快进」）。判定收敛为纯函数 [shouldPreemptDownloadWindow]（行为
                    // 单测见 StreamWindowPolicyTest）：连续区间不取消（与在途下载同锚
                    // 合并共享带宽），跳变才先 CancelDownloadFile 取消属主（只停查询，
                    // 已落盘字节保留）再发本段查询，窗口立即重锚到本段——seek 插队由此保证
                    runCatching { client.send(TdApi.GetFile(id)) }.onSuccess { g ->
                        val l = g.local
                        if (shouldPreemptDownloadWindow(
                                off, l.downloadOffset, l.downloadOffset + l.downloadedPrefixSize,
                                l.isDownloadingCompleted, fullDlFallbackFiles.containsKey(id),
                            )
                        ) {
                            runCatching { client.send(TdApi.CancelDownloadFile(id, false)) }
                            dev.tgfeed.core.playback.PlaybackTrace.log(
                                "windowPreempt", "f$id",
                                "off=$off 原窗=[${l.downloadOffset},+${l.downloadedPrefixSize}) 已取消属主重锚",
                            )
                            Log.d(
                                TAG,
                                "R166 窗口抢占：fileId=$id off=$off " +
                                    "原窗=[${l.downloadOffset},+${l.downloadedPrefixSize}) 已取消属主",
                            )
                            // R195 整载被抢占通知（只记账+trace，闸本体零改动）：被取消
                            // 的在途属主若是激进整载器发的整载，稳定窗到点后由其决策
                            // 循环重发（可抢占可重启=R166 红线）
                            aggressivePreloadManager.notePreempt(id)
                        }
                    }
                    val f = client.send(TdApi.DownloadFile(id, 32, off, len, true))
                    // R81 落盘窗口校验（修「播着好好的突然 3001 Invalid NAL」）：
                    // TDLib 同步 DownloadFile 在下载失败/被顶掉时也会携当前状态正常返回
                    //（不是异常），旧实现把「无异常」当「已落盘」推进水位 → 服务循环把
                    // 预分配空洞的 0 字节发给播放器（解析终态，不可重试）。
                    // 必须用 LocalFile 已下载窗口核对目标区间：
                    //  - 整载完成 → 全文件可读，直接认领到区间尾；
                    //  - 新口径窗口 [downloadOffset, +downloadedPrefixSize) 覆盖 → 认领；
                    //  - 旧口径前缀 [0, downloadedPrefixSize) 覆盖 → 认领；
                    // 返回验证过的连续覆盖终点（部分覆盖只认到验证边界）；
                    // 窗口完全不含本区间 → null（泵按失败退避重试，水位不动）
                    val verifiedTo = when {
                        f.local.isDownloadingCompleted -> {
                            // R166 兜底摘牌：整载完成即恢复正常分段（后续跳变恢复抢占闸）
                            fullDlFallbackFiles.remove(id)
                            off + len
                        }
                        else -> {
                            // 只认「窗口起点不晚于本区间起点」的连续覆盖段：
                            // downloadOffset=0 时即旧前缀口径 [0, prefixSize)；
                            // downloadOffset>0 时窗口是本下载前端的 [dOff, dOff+prefix)。
                            // 刻意不做「off+len ≤ prefixSize」的单独放行——多前端并发时
                            // 快照里的窗口可能指向别的区间（如 moov 尾探针），按绝对长度
                            // 误纳会假通过出空洞（那才是本任务要根治的 3001 路径）
                            val winStart = f.local.downloadOffset
                            val winEnd = winStart + f.local.downloadedPrefixSize
                            if (winStart <= off && off < winEnd) minOf(winEnd, off + len) else null
                        }
                    }
                    if (verifiedTo == null) {
                        // 校验未过必留证：窗口快照是事后复盘假水位的唯一现场
                        dev.tgfeed.core.playback.PlaybackTrace.log(
                            "dlVerifyMiss", "f$id",
                            "off=$off len=$len 窗=[${f.local.downloadOffset},+${f.local.downloadedPrefixSize}) " +
                                "downloaded=${f.local.downloadedSize} active=${f.local.isDownloadingActive} " +
                                "completed=${f.local.isDownloadingCompleted}",
                        )
                    }
                    verifiedTo
                }
            },
            // R166 整下最终兜底（显式上报）：泵连续失败达阈值回调此处——p32 整载 +
            // 登记 fullDlFallbackFiles（downloadPart 抢占闸见牌让位）+ trace 留证。
            // 幂等：已登记不重发。
            fullDownload = { id ->
                if (id > 0 && !fullDlFallbackFiles.containsKey(id)) {
                    fullDlFallbackFiles[id] = android.os.SystemClock.elapsedRealtime()
                    dev.tgfeed.core.playback.PlaybackTrace.log("整下兜底", "f$id", "泵分段连续失败，转整载兜底")
                    Log.w(TAG, "R166 整下兜底：fileId=$id 分段拉取持续失败，转整体下载（最终兜底已上报）")
                    runCatching {
                        videoDlMutex(id).withLock {
                            client.send(TdApi.DownloadFile(id, 32, 0L, 0L, false))
                        }
                    }
                }
            },
            // R166 查牌接线：泵起跑前直查兜底登记，新连接直接进轮询模式
            isFullDownloadFallback = { id -> fullDlFallbackFiles.containsKey(id) },
        ).also {
            // R195 激进整载循环随流服务器首次构建启动（整载候选=在播连接+队列
            // 推送，流服务器未起时无事可做；循环幂等）
            aggressivePreloadManager.startLoop()
        }
    }

    /**
     * R166 整下兜底登记表：fileId → 兜底发起时刻（elapsedRealtime ms）。
     * 消费方三处：① downloadPart 窗口抢占闸见牌让位（不取消兜底整载）；
     * ② TgStreamServer 泵起跑前查牌（新连接直接进兜底轮询模式，免再撞 6 次失败）；
     * ③ downloadPart 验证整载完成时摘牌恢复正常分段。会话级，量小不清理。
     * R196 追加消费方 ④：StorageGovernor 活跃 fileId 集合（temp 清扫遇在飞兜底让路）。
     */
    internal val fullDlFallbackFiles = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /**
     * R195 WiFi 激进整载器（1Hz 决策循环，实现见 GatewayAggressivePreload.kt）：
     * A 档当前在播（conns 驱动）+ B 档下一条预载（PreloadScheduler 推送驱动）
     * + 全局唯一整载槽；与泵窗口共存（优先级阶梯 32>16>8>4>1）、可抢占可重启
     * （R166 红线，抢占闸钩子 notePreempt）。消费触点三处：
     *  - downloadPart 抢占分支 notePreempt（闸取消在途属主时记账）；
     *  - streamServer lazy 构建处 startLoop（循环随流服务器启用）；
     *  - [setAggressivePreloadNextTargets]（PreloadScheduler 翻页落定推送 B 档目标）。
     * close() 调 stop() 终止循环。构造轻量（纯表），非 lazy 直接构造。
     */
    internal val aggressivePreloadManager = AggressivePreloadManager(this)

    /** R195 B 档目标推送口（接口默认空实现，Mock 无感；见 TdGateway 注释） */
    override fun setAggressivePreloadNextTargets(nextFileIds: List<Int>) {
        aggressivePreloadManager.setNextTargets(nextFileIds)
    }

    /**
     * R196 存储治理中枢（懒构造，init 尾部 start）：调度（探针/定期/压力）、分级清理
     * （temp 碎片 → 全载保留上限 → 水位两道闸）、R195 全载登记表、清理日志全在这。
     * 网关只注入 TDLib 触点（GetFile/DeleteFile/Fast 快账/水位闸/观测）与活跃集。
     * 设置页「存储管理」卡与 R195 预热链都从这里拿接口（FeedStoragePart / registerFullLoad）。
     * 生命周期同本网关实例：close() 取消 start() 返回的 job；换发网关 = 换新 governor。
     */
    internal val storageGovernor by lazy {
        dev.tgfeed.core.storage.StorageGovernor(
            scope = scope,
            filesDir = appFilesDir,
            prefs = appPrefs,
            getFile = { id -> runCatching { client.send(TdApi.GetFile(id)) }.getOrNull() },
            deleteFileTd = { id ->
                runCatching { client.send(TdApi.DeleteFile(id)); true }.getOrDefault(false)
            },
            storageFastStats = { runCatching { client.send(TdApi.GetStorageStatisticsFast()) }.getOrNull() },
            enforceWatermark = { enforceStorageWatermark() },
            observeMessageDb = { observeMessageDatabase(appFilesDir) },
            activeFileIds = { fullDlFallbackFiles.keys + TgStreamServer.streamingFileIds() },
            isStreamingActive = { TgStreamServer.anyFileStreaming() },
            watermarkEnabled = cacheLimitBytes > 0,
        )
    }

    /**
     * 真实条目：已完成的直接返回本地路径；未完成的一律回流服务器边下边播
     * （R166 起不再后台整载——泵=唯一下载驱动，见 ensurePlayableImpl 注释）。
     */

    /** caption 译文缓存：key = VideoItem.key（含 chatId，避免跨频道 messageId 撞号串译文）。
     *  P1-2：ConcurrentHashMap——翻译由 UI 点击驱动、可并发触发，普通 HashMap 无保护 */
    internal val captionTranslationCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    // ---- 搜索机器人/热词榜公共 API 委托（实现见 GatewayBotSearch.kt） ----
    suspend fun searchViaBot(botUsername: String, query: String): List<VideoItem> = searchViaBotImpl(botUsername, query)
    fun extractLinkTitleHints(m: TdApi.Message): Map<String, HintTitle> = extractLinkTitleHintsImpl(m)
    fun extractMessageTmeLinks(msg: TdApi.Message): List<String> = extractMessageTmeLinksImpl(msg)
    fun extractMessageMentionLinks(msg: TdApi.Message): List<String> = extractMessageMentionLinksImpl(msg)
    fun extractMessageAnswerLinksFiltered(msg: TdApi.Message): Triple<List<String>, Int, Int> = extractMessageAnswerLinksFilteredImpl(msg)
    fun isSelfReferentialSearchLink(link: String, responderUsername: String?): Boolean = isSelfReferentialSearchLinkImpl(link, responderUsername)
    fun knownSearchBotNames(): List<String> = knownSearchBotNamesImpl()
    suspend fun harvestHotWordBoard(onAttempt: () -> Unit = {}): List<String>? = harvestHotWordBoardImpl(onAttempt)

    /** 读取应用配置（备用机器人列表等）。AppPrefs 是 SharedPreferences 轻量包装，
     *  lazy 单例即可；用 applicationContext 避免意外持有 Activity */
    internal val appPrefs by lazy { AppPrefs(context.applicationContext) }

    // R196 init 块②：存储治理循环启动（StorageGovernor：探针 5min 一拍 + 定期整轮
    // 45min±抖动 + 压力触发 temp>500MB/缓存>8GB + 分级清理 T0 temp/T1 全载/T2 水位
    // + R195 全载登记表 + 清理日志），取代旧「授权后小时循环」——旧链路实锤漏收当日
    // 残骸（用户机 3GB 现场）。T0/T1 不依赖 cacheLimitBytes；T2 水位闸仅在限额>0 时
    // 启用（0=用户关自动水位，语义保留）。首轮经 R145 冷启动让路闸错峰（start 内部
    // 等 QuietGate）。**本块刻意放在类体声明全部就绪之后**——storageGovernor 的 lazy
    // 引用本行之前声明的 fullDlFallbackFiles/appPrefs，首个 init 块触达会踩字段未
    // 初始化顺序陷阱（R46 同款）。
    init {
        storageGovernJob = storageGovernor.start()
    }

    /**
     * 搜索机器人结果进程内缓存：key = "主机器人:关键词"（均 trim + 小写），value = 写入时间戳 to 结果。
     * TTL 10 分钟（BOT_CACHE_TTL_MS），命中直接返回、不再问机器人。
     * 只缓存非空结果——空结果多半是机器人临时不可用/超时，缓存会挡住用户立即重试。
     * ConcurrentHashMap：searchViaBot 可能被 UI 多入口并发触发。
     */
    internal val searchBotCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<VideoItem>>>()

    // ---------------- 资源搜索群收割支撑（GroupHarvester P0/P1，全程只读） ----------------

    /**
     * 429 熔断表（R44 限流收敛治理）：SearchPublicChat 泳道被 429 后，全 app 该方法族
     * 立即静默到服务端 retry-after 结束；JSON 落盘 filesDir，进程重启后静默仍然有效
     * （封号级限流横跨多次进程生命周期，FloodBudget 内存态重启清零是假防守）。
     * 挂网关统一持有：8 个裸调用点过闸、EntityResolver 轮首查闸、resolveAnswerTitles
     * 入口查闸、答案区标题 400 死信持久化，全部共用这一张表。
     */
    val floodBreaker = FloodCircuitBreaker(context.filesDir)

    /**
     * R184 账号级搜索配额账本（SearchPublicChat + SearchChatsOnServer 全族共享总账）：
     * 三车道（用户动作/后台/爬虫验证）×极保守总量 12/h×累进衰减（历史封禁越多配额越低）
     * ×429 封禁窗跨进程持久（封禁期内连试都不试——连试会续封/升级）。
     * 发送口统一见 [searchPublicChatGuardedEx] / [searchChatsOnServerGuarded]；
     * 429 上报统一入口 [reportAccountSearch429]（双族熔断表+账本一次写齐）。
     */
    val searchQuotaLedger = SearchQuotaLedger(context.filesDir)

    /**
     * R184 resolve-once 缓存（username→chatId 持久化，filesDir/resolve_once.json）：
     * SearchPublicChat 副作用=在 TDLib 本地建会话——缓存命中后 GetChat 直通，
     * **永不重解析**（治验证轮 force-stop 循环把进程内缓存清零后反复重烧的事故形态）。
     * 接入位=发送口本身，全部调用方自动继承；GetChat 失败即失效移除走真解析。
     */
    internal val resolveOnceCache = ResolveOnceCache(context.filesDir)

    /**
     * R184 搜索族 429 统一上报：双方法族（SearchPublicChat + SearchChatsOnServer）
     * 熔断表 + 账号账本一次写齐——事故实证两族至少是账号桶邻位（SearchPublicChat 被
     * 封死期 SearchChatsOnServer 候选归零），保守同桶同静默（宁可少搜不可续封）。
     * 半开试探状态机（R58）仍由熔断表按族自持；封禁窗与累进记忆由账本自持。
     */
    internal fun reportAccountSearch429(retryAfterSec: Long) {
        floodBreaker.report429(FloodCircuitBreaker.FAMILY_SEARCH_PUBLIC_CHAT, retryAfterSec)
        floodBreaker.report429(FloodCircuitBreaker.FAMILY_SEARCH_CHATS_ON_SERVER, retryAfterSec)
        searchQuotaLedger.report429(retryAfterSec)
    }

    /**
     * 账号级搜索封禁窗判定（分类/降级用，纯读）：账本封禁窗或任一搜索族熔断静默中
     * = true——此时任何搜索调用点连试都不许试（防续封），UI 层据此给「限流」口径
     * 而不是「频道不存在」误报。
     */
    internal fun accountSearchBanned(): Boolean =
        searchQuotaLedger.remainingBanSec() > 0 ||
            floodBreaker.isSilenced(FloodCircuitBreaker.FAMILY_SEARCH_PUBLIC_CHAT) ||
            floodBreaker.isSilenced(FloodCircuitBreaker.FAMILY_SEARCH_CHATS_ON_SERVER)

    /**
     * R184 验证车道折算：代理验证模式（[AppPrefs.agentVerifySearchQuota]，run-as 写
     * shared_prefs + force-stop 生效）开启时，一切搜索请求按 CRAWLER 车道计费——
     * 最低优先级、份额最小、最先被拒。测试机/模拟器上跑的全是用户主力账号，
     * 保护账号优先于验证完整性（AGENT-DISCIPLINE §九）。
     */
    internal fun effectiveSearchLane(requested: SearchQuotaLedger.Lane): SearchQuotaLedger.Lane =
        if (appPrefs.agentVerifySearchQuota) SearchQuotaLedger.Lane.CRAWLER else requested

    /**
     * flood 预算·查询车道（P0 #1）：searchViaBot 每个候选机器人的完整查询会话记 1 次
     * （发关键词 + 轮询收割 + 翻页打包计费）。滑动窗口 1 小时；
     * 额度上限在 queryBotOnce 入口从 AppPrefs.floodQueryPerHour 逐次刷新（设置改动免重启）。
     * 耗尽时查询静默降级为空结果——防风控优先于单次搜索成功率。
     */
    val floodBudgetQuery = FloodBudget(3_600_000L, appPrefs.floodQueryPerHour)

    /**
     * flood 预算·搜索联动秒回车道（用户主动动作专用，与收割车道 GroupHarvester.harvestBudget
     * **彻底解耦**）：askSearchEntity 每个实体的"发送+监听"整轮记 1 次，滑动窗口 1 小时，
     * 额度定格 [LIVE_ASK_BUDGET_PER_HOUR]（依据见该常量注释）。
     * 为什么独立成车道：后台收割+解析器与秒回共用收割预算时，用户主动发起的秒问会被
     * 后台爬取饿死（实测三实体全被"收割预算耗尽"跳过）；用户显式动作优先级必须高于
     * 后台机会型爬取。耗尽时该实体静默跳过——一词一轮冷却是更前面的主闸，这里是保险丝。
     * 进程内存态，重启清零（与 FloodBudget 全家族同口径）。
     */
    internal val liveAskBudget = FloodBudget(3_600_000L, LIVE_ASK_BUDGET_PER_HOUR)

    /**
     * 收割器实例（lazy：close() 不会为"停止"而白构造一个从未启动的收割器；
     * start() 由 init 里授权 Ready 后的协程触发）。
     * 构造零 IO（AnswerStore 首访问才读盘）；传入 filesDir 落 answer_store.json。
     */
    internal val groupHarvesterLazy = lazy { GroupHarvester(this, appPrefs, context.filesDir) }

    /** 资源搜索群收割器（设置页/诊断入口读统计、未来答案库搜索路径的访问点） */
    val groupHarvester: GroupHarvester get() = groupHarvesterLazy.value

    /** bot 用户名 → userId 进程内缓存（只缓存成功；失败下轮重试，防瞬时网络问题钉死） */
    internal val botUserIdCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** userId → UserTypeBot 实证结果缓存（正负都缓存：人不会变 bot，bot 不会变人） */
    internal val botUserTypeCache = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()

    /**
     * 收割专用·原始消息拉取（只读）：GetChatHistory 薄封装，**不做任何媒体过滤**——
     * 与 fetchFromChannel 的定位差异：那边出 VideoItem（纯文本消息被丢弃），
     * 这边保全文本/按钮/replyTo 供 GroupHarvester 做"关键词→bot 应答"配对。
     * 返回 null = 拉取失败（调用方不得推进游标）；空表 = 该区间没有消息。
     * 签名同 fetchFromChannel 已实证：GetChatHistory(long, long, int, int, boolean) -> Messages。
     *
     * R372 超时兜底：裹 withTdTimeout（20s 拉历史放宽档）——此前裸 send 是 R366 三期
     * （LiveSearch/Discovery/ChannelDirectory 三文件）的漏网位，TDLib 隐性限流（请求
     * 挂起无响应）时收割轮卡死在首个实体（16384 实锚：10:34 轮 24 分钟零实体完成行），
     * 答案库/金矿热词随之断流。超时按既有失败语义 null 回落（本轮放弃不动游标下轮重试）。
     */
    suspend fun fetchRawMessages(chatId: Long, fromMessageId: Long, offset: Int, limit: Int): List<TdApi.Message>? {
        awaitAuthorized()
        return runCatching {
            withTdTimeout(
                PLAZA_TD_HISTORY_TIMEOUT_MS,
                what = "fetchRawMessages/GetChatHistory chatId=$chatId from=$fromMessageId",
            ) {
                client.send(TdApi.GetChatHistory(chatId, fromMessageId, offset, limit, false))
            }?.messages?.filterNotNull()
        }.onFailure {
            Log.w(TAG, "fetchRawMessages 失败 chatId=$chatId from=$fromMessageId: ${it.message}")
        }.getOrNull()
    }

    /**
     * 本地缓存白嫖通道（R46 方案 b）：`GetChatHistory(onlyLocal=true)`——只读 TDLib
     * 本地消息库中已沉淀的区间（用户 Feed 浏览/搜索拉过的页），**严格零网络、
     * 零 flood 暴露**；本地没有覆盖的区间返回空/截断，绝不回退联网拉取。
     *
     * 供 DeepSearchIndex"只采不推进游标"模式：采到多少算多少（INSERT OR IGNORE 幂等），
     * 本地空洞不污染游标/完成态（由调用方保证不写 meta）。
     *
     * 刻意裁剪（保零网络口径）：
     *  - 不做评论级联、不做邻居窗口 caption 展开（那些路径会发 GetMessageThreadHistory/
     *    GetChatHistory 联网请求）；
     *  - 标题走 [peekChatTitle] 零网络快照（toVideoItem 默认的 channelTitle() 缓存
     *    miss 时会补 GetChat——对本通道那就是一次网络请求，必须掐掉）；
     *  - R283 口径修正：资源嗅探建边**接入**（旧口径「本地消息等正式爬取路过时再顺手
     *    建」被 R168「互链数据薄」证伪——媒体导向爬虫对文字+链接型频道/群判
     *    exhausted 后正式爬取不再路过，本地回填成唯一过手通道）。嗅探=纯字段读取
     *    零网络（消息本就在手），文字消息全量过闸不过滤——搜索群 bot 应答的
     *    t.me 线索面由此收口；SniffLedger 幂等闸防派工周期重扫刷边权。
     *
     * 返回 null = 失败（调用方按"本轮不采"处理）；FeedPage.nextCursor = 本页最老
     * 消息 id（本地还有更老内容时），null = 本地覆盖到此为止。
     * 签名同 fetchFromChannel 注释已实证：GetChatHistory(long, long, int, int, boolean)。
     */
    suspend fun fetchLocalHistoryForIndex(chatId: Long, fromMessageId: Long, limit: Int): FeedPage? {
        awaitAuthorized()
        return runCatching {
            val history = withTimeout(5_000) {
                client.send(TdApi.GetChatHistory(chatId, fromMessageId, 0, limit, true))
            }
            val msgs = history.messages.filterNotNull()
            if (msgs.isEmpty()) return@runCatching FeedPage(emptyList(), null)
            // R283 互链嗅探（零网络纯字段）：文字+媒体全量过闸——文字消息不过
            // toVideoItem 但携带的 t.me 链接/forwardInfo 同样是图谱边证据
            msgs.forEach { m -> runCatching { if (sniffLedger.isNew(chatId, m.id)) sniffMessage(chatId, m) } }
            runCatching { sniffLedger.markRange(chatId, msgs.first().id, msgs.last().id) }
            // 零网络标题快照（miss 给空串——标题不值一次联网请求，正式爬取会补真值）
            val title = peekChatTitle(chatId) ?: ""
            val items = msgs.mapNotNull { m ->
                runCatching { toVideoItem(chatId, m, null, titleOverride = title) }.getOrNull()
            }
            FeedPage(items, msgs.last().id.toString())
        }.onFailure {
            Log.w(TAG, "fetchLocalHistoryForIndex 失败 chatId=$chatId from=$fromMessageId: ${it.message}")
        }.getOrNull()
    }

    /**
     * SearchPublicChat 过闸发送（R44 熔断 + R58 半开三态）：发送前过熔断闸——
     * 熔断静默中一发不打直接 null；半开试探期按闸限量放行（间隔未到的拒发同样 null）；
     * 闭合正常发送。试探放行（PROBE）的成败必须回报熔断表：成功 reportSuccess 推进闭合计数，
     * 撞 429（以 code==429 为准——库的 isFloodWait 按老格式 "FLOOD_WAIT_X" 匹配，
     * 会漏新版 "Too Many Requests: retry after N"）解析 retry-after 上报熔断表重新熔断，
     * 全 app 该方法族即刻静默到解封。其余失败同样 null 收场（调用方各自失败语义不变）。
     */

    // ---- 发现页支撑/本人资料公共 API 委托（实现见 GatewayDiscovery.kt） ----
    suspend fun resolveBotUserId(username: String, lane: SearchQuotaLedger.Lane = SearchQuotaLedger.Lane.CRAWLER): Long? = resolveBotUserIdImpl(username, lane)
    /** R378 刀3 白名单缓存只读口：botUserIdCache 命中直出（零网络零记账零 suspend；实现见 GatewayDiscovery.kt） */
    fun peekBotUserId(username: String): Long? = peekBotUserIdImpl(username)
    suspend fun isBotUser(userId: Long): Boolean = isBotUserImpl(userId)
    suspend fun listDiscoveryProfiles(): List<DiscoveryProfile> = listDiscoveryProfilesImpl()
    /** R197 群选择器：用户全部已加入群（含成员数，零 Search 族调用；实现见 GatewayDiscovery.kt） */
    suspend fun listHarvestGroupCandidates(): List<HarvestGroupCandidate> = listHarvestGroupCandidatesImpl()
    suspend fun fetchSupergroupDescription(supergroupId: Long): String? = fetchSupergroupDescriptionImpl(supergroupId)
    suspend fun probeUserBotProfile(userId: Long): Pair<Boolean, String>? = probeUserBotProfileImpl(userId)
    suspend fun resolveBotChatId(username: String, lane: SearchQuotaLedger.Lane = SearchQuotaLedger.Lane.CRAWLER): Long? = resolveBotChatIdImpl(username, lane)
    suspend fun resolvePublicChatByUsername(username: String, lane: SearchQuotaLedger.Lane = SearchQuotaLedger.Lane.BACKGROUND): PublicChatLookup = resolvePublicChatByUsernameImpl(username, lane)
    suspend fun discoverSimilarChannels(seedChatIds: List<Long>, depth: Int = 2): List<ChannelBrief> = discoverSimilarChannelsImpl(seedChatIds, depth)
    suspend fun resolvePendingChannels(limit: Int = 20) = resolvePendingChannelsImpl(limit)

    /** R184 限流降级入队后台重试（实现见 GatewayDiscovery；ChannelFeedSource 自愈撞限流时调用） */
    fun enqueueBackgroundResolve(username: String?) = enqueueBackgroundResolveImpl(username)
    suspend fun getMyProfile(): MyProfile? = getMyProfileImpl()
    /** R189 底栏双击「我的」=刷新：清 profile 进程内缓存（实现见 GatewayDiscovery.kt） */
    fun refreshMyProfile() = refreshMyProfileImpl()
    suspend fun fetchSupergroupProfile(chatId: Long): SupergroupProfile? = fetchSupergroupProfileImpl(chatId)
    suspend fun harvestChatFolderInvite(fromChatId: Long, inviteLink: String) = harvestChatFolderInviteImpl(fromChatId, inviteLink)
    suspend fun isJoinedChat(chatId: Long): Boolean = isJoinedChatImpl(chatId)

    /**
     * R183 加入态三值判定（发现页主流程用——UNKNOWN=chat 本地无记录=从未打开=从未加入，
     * 放行；保守布尔版 [isJoinedChat] 保留给 R57 新鲜区等既有调用方，语义见
     * GatewayDiscovery.kt 的 JoinStatus KDoc）
     */
    suspend fun joinStatusOf(chatId: Long): JoinStatus = joinStatusOfImpl(chatId)

    /**
     * 评论纯文本缓存：key = VideoItem.key（含 chatId，防跨频道 messageId 撞号）。
     * 只缓存成功结果（含"确认无评论"的空表）；网络类失败不缓存，下次调用可重试。
     * 弹幕是锦上添花，不追求实时性，进程内缓存即可。
     */
    internal val commentsCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    // ---- 资源群答案/秒问联动公共 API 委托（实现见 GatewayLiveSearch.kt） ----
    fun recordGroupSearchLink(fromChatId: Long, link: String) = recordGroupSearchLinkImpl(fromChatId, link)
    fun queryGroupAnswers(keyword: String): List<GroupAnswer> = queryGroupAnswersImpl(keyword)
    suspend fun searchViaGroup(query: String): List<VideoItem> = searchViaGroupImpl(query)
    suspend fun resolveLinkVideoItem(link: String): VideoItem? = resolveLinkVideoItemImpl(link)
    suspend fun liveAskEntities(): List<SearchEntity> = liveAskEntitiesImpl()
    suspend fun askSearchEntity( entity: SearchEntity, query: String, onBatch: suspend (SearchEntity, List<GroupAnswer>, List<String>) -> Unit, ): Boolean = askSearchEntityImpl(entity, query, onBatch)
    suspend fun resolveLiveAnswerItems(links: List<String>): List<Pair<String, VideoItem>>? = resolveLiveAnswerItemsImpl(links)
    suspend fun resolveGroupAnswerItems(links: List<String>): List<Pair<String, VideoItem>>? = resolveGroupAnswerItemsImpl(links)
    suspend fun harvestAnswerTitleHints(chatIds: List<Long>, perChatLimit: Int = 50): Map<String, HintTitle> = harvestAnswerTitleHintsImpl(chatIds, perChatLimit)
    suspend fun resolveAnswerTitles(usernames: List<String>, live: Boolean): TitleResolveOutcome? = resolveAnswerTitlesImpl(usernames, live)
    suspend fun resolveTitlesViaWeb(usernames: List<String>): Map<String, WebPreviewResolver.WebChannelInfo> = resolveTitlesViaWebImpl(usernames)
    suspend fun getComments(item: VideoItem, limit: Int = 50): List<String> = getCommentsImpl(item, limit)
    suspend fun getCommentsRich(item: VideoItem, limit: Int = 50): List<DanmakuItem> = getCommentsRichImpl(item, limit)

    // ---- 搜索相关实体自动发现支撑（多源收割升级：网关只搬原始事实，分类规则在 GroupHarvester 侧） ----

    /**
     * 发现扫描的单会话类型化档案（[listDiscoveryProfiles] 元素）。
     * 与 ChannelBrief 的差异：带 chat 类型/超级群 id/私聊 userId——
     * "什么算搜索相关实体"的关键词判定是收割侧策略，网关只提供分类所需的原始字段。
     */
    class DiscoveryProfile(
        val chatId: Long,
        val title: String,
        val chatKind: Int,       // CHAT_SUPERGROUP / CHAT_BASICGROUP / CHAT_PRIVATE / CHAT_OTHER
        val isChannel: Boolean,  // chatKind=CHAT_SUPERGROUP 时区分频道(true)/群组(false)
        val supergroupId: Long,  // chatKind=CHAT_SUPERGROUP 时有效，其余 0
        val userId: Long,        // chatKind=CHAT_PRIVATE 时有效，其余 0
    ) {
        companion object {
            const val CHAT_SUPERGROUP = 0
            const val CHAT_BASICGROUP = 1
            const val CHAT_PRIVATE = 2
            const val CHAT_OTHER = 3 // 秘密聊天等，发现侧直接忽略
        }
    }

    @Volatile
    internal var discoveryProfilesSnapshot: DiscoveryProfilesSnapshot? = null

    /** R197 群选择器全量群枚举快照（TTL [HARVEST_PICKER_TTL_MS]，见 GatewayDiscovery.kt） */
    @Volatile
    internal var harvestGroupsSnapshot: HarvestGroupsSnapshot? = null

    /**
     * 网页泳道标题解析器（t.me/s/<用户名> 官方公开预览页，无需登录，og:title 即频道名）：
     * 限流按 IP 不按账号，与 SearchPublicChat 的账号桶完全隔离——账号级 429 封死期
     * （实测 retry after 上万秒）这是答案区标题的唯一实时补充泳道。
     * 代理配置与 AddProxy 同源（appPrefs.proxyEnabled/proxyHost/proxyPort），未启用时直连。
     */
    internal val webPreviewResolver by lazy {
        WebPreviewResolver(
            proxyHost = if (appPrefs.proxyEnabled) appPrefs.proxyHost else null,
            proxyPort = appPrefs.proxyPort,
        )
    }

    /** 消息库预算：跟随缓存限额，保底 [DB_BUDGET_MIN_BYTES]。cacheLimitBytes=0（不启用）时不会走到。
     *  注意必须做成 get() 每次现算：本属性声明在类体后段而启动闸门在 init 块里同步调用，
     *  普通属性初始化顺序在 init 之后，会读到未初始化的 0（Kotlin 属性初始化顺序陷阱） */
    internal val dbBudgetBytes: Long get() = maxOf(cacheLimitBytes, DB_BUDGET_MIN_BYTES)

    // ---- 广场公共 API 委托（实现见 GatewayPlaza.kt） ----
    // R361：lane 参数（默认 BACKGROUND）——用户手动开广场（设置页/管理面板开关手势）
    // 传 USER_ACTION：用户动作走最高优先车道，不被后台收割烧尽的 BACKGROUND 份额挤死
    suspend fun plazaFindChat(username: String, lane: SearchQuotaLedger.Lane = SearchQuotaLedger.Lane.BACKGROUND): PlazaFindResult = plazaFindChatImpl(username, lane)
    suspend fun plazaCheckChat(chatId: Long): Boolean = plazaCheckChatImpl(chatId)
    suspend fun plazaCreateChat(username: String): Long? = plazaCreateChatImpl(username)
    suspend fun plazaJoin(chatId: Long) = plazaJoinImpl(chatId)
    suspend fun renameChatTitle(chatId: Long, title: String): Boolean = renameChatTitleImpl(chatId, title)
    suspend fun plazaSendText(chatId: Long, text: String): Boolean = plazaSendTextImpl(chatId, text)
    suspend fun plazaFetchRecentTexts(chatId: Long, limit: Int = 100): List<Triple<String, Int, String>> = plazaFetchRecentTextsImpl(chatId, limit)

    /** R205 TL-9：广场历史分页（弹幕时间旅行的拉取原语；有界页数由调用方收口） */
    suspend fun plazaFetchHistoryPage(chatId: Long, fromMessageId: Long, limit: Int = 100): List<dev.tgfeed.core.plaza.PlazaHistoryRow> =
        plazaFetchHistoryPageImpl(chatId, fromMessageId, limit)

    // ---- 跨设备续播同步公共 API 委托（实现见 GatewaySync.kt） ----
    suspend fun getSavedMessagesChatId(): Long? = getSavedMessagesChatIdImpl()
    suspend fun findSyncMessage(chatId: Long, marker: String): Long? = findSyncMessageImpl(chatId, marker)
    suspend fun sendSyncText(chatId: Long, text: String): Long? = sendSyncTextImpl(chatId, text)
    suspend fun editSyncText(chatId: Long, messageId: Long, text: String): Boolean = editSyncTextImpl(chatId, messageId, text)
    suspend fun readSyncText(chatId: Long, messageId: Long): String? = readSyncTextImpl(chatId, messageId)
    // ---- R300 多设备多域同步（V3）新增委托（实现见 GatewaySync.kt；写点全过 §十闸+全域写预算闸） ----
    suspend fun findSyncMessages(chatId: Long, markerPrefix: String): List<Pair<Long, String>> = findSyncMessagesImpl(chatId, markerPrefix)
    suspend fun deleteSyncMessages(chatId: Long, messageIds: List<Long>): Boolean = deleteSyncMessagesImpl(chatId, messageIds)
    fun startSyncUpdateWatcher(onSyncEdited: () -> Unit) = startSyncUpdateWatcherImpl(onSyncEdited)
    internal fun syncWriteBeginBatch(): Boolean = SyncWriteBudget.beginBatch()
    internal fun syncWriteEndBatch() = SyncWriteBudget.endBatch()
    internal suspend fun sendSyncTextBatched(chatId: Long, text: String): Long? = sendSyncTextBatchedImpl(chatId, text)
    internal suspend fun editSyncTextBatched(chatId: Long, messageId: Long, text: String): Boolean = editSyncTextBatchedImpl(chatId, messageId, text)
    internal suspend fun deleteSyncMessagesBatched(chatId: Long, messageIds: List<Long>): Boolean = deleteSyncMessagesBatchedImpl(chatId, messageIds)

    /** R300 同步消息云端编辑监听单飞闸（一个网关实例只起一条收集协程；实现见 GatewaySync.kt） */
    @Volatile
    internal var syncUpdateWatcherStarted: Boolean = false

    // ---- Inline bot 盲盒流公共 API 委托（实现见 GatewayInline.kt，R135 新区） ----
    // 与本文件既有分区惯例一致：网关类只挂薄委托，实现与 TDLib 签名实证注释都在
    // GatewayInline.kt。降级编排（换 bot/节流/验证墙报告）不在这里——见
    // core/feed/InlineBotFeedSource.kt
    internal suspend fun getInlineQueryResults(
        botUserId: Long,
        query: String,
        offset: String?,
    ): InlineBotQueryPage? = getInlineQueryResultsImpl(botUserId, query, offset)

    internal suspend fun sendInlineResultToSaved(
        queryId: Long,
        resultId: String,
        titleOverride: String,
    ): VideoItem? = sendInlineResultToSavedImpl(queryId, resultId, titleOverride)

    /** inline bot 用户名 → userId（防熔断正路+旁路+负缓存，见 GatewayInline.kt） */
    internal suspend fun resolveInlineBotUserId(bot: String): Long? = resolveInlineBotUserIdImpl(bot)

    /** Saved Messages 会话 id 进程内缓存：本人 user id 不变，GetMe 一次即可 */
    @Volatile
    internal var savedMessagesChatId: Long? = null

    // ---------------- 点赞回写原频道 Reaction（R128） ----------------

    /** 本会话已回写过 👍 的消息账（"chatId:messageId"）：同一条只回写一次（任务书口径） */
    private val likeReactionSent = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 点赞回写（R128）：给原频道该消息补一个 👍 Reaction，让点赞回到内容生态。
     *
     * 总开关：AppPrefs.likeReactionWriteback（默认关，设置页"点赞回写"卡片显式开启；
     * 默认关的理由见该 prefs 成员注释）。每次调用现查 prefs——SharedPreferences
     * 读是内存哈希，代价可忽略，换来设置翻转即时生效、跨重启不漂移（单一事实源）。
     *
     * 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
     *  - 本 AAR 无 SetMessageReaction（单数）类（javap 报找不到类）；语义等价且更贴合的是
     *    AddMessageReaction(long chatId, long messageId, ReactionType reactionType,
     *    boolean isBig, boolean updateRecentReactions) -> Ok
     *    ——增量加一个 Reaction，服务端幂等（本账号已选过同表情再调为空操作）。
     *  - 不用 SetMessageReactions（复数，ReactionType[] 全量替换）：会把本账号在该消息上
     *    已选的其他 Reaction 冲掉——"点赞回写"只做加法。
     *  - ReactionTypeEmoji(String emoji) 构造存在；👍 属默认可用表情集，无需预查可用表。
     *
     * 口径：
     *  - 幂等：同一 (chatId, messageId) 本会话只发一次（[likeReactionSent] 记账）；
     *    进程重启后重发也无碍——服务端幂等兜底。
     *  - 取消点赞不回撤：回写记的是"点赞发生过"，不是点赞状态的镜像。
     *  - 失败静默：频道关 Reaction/无权限/消息已删/限流等任何失败只记日志不向上抛——
     *    本地点赞与飘心早已完成，回写只是锦上添花。
     *  - isBig=false（不触发大动画）、updateRecentReactions=false（不把 👍 顶进
     *    本账号官方客户端的"最近 Reaction"栏——回写不应扰动用户的 TG 使用痕迹）。
     *
     * @return true = 请求已被 TDLib 受理（Ok）；false = 开关关/本会话已回过/失败（均静默）
     */
    suspend fun sendLikeReaction(chatId: Long, messageId: Long): Boolean {
        if (!appPrefs.likeReactionWriteback) return false
        if (!likeReactionSent.add("$chatId:$messageId")) return false
        return runCatching {
            awaitAuthorized()
            client.send(
                TdApi.AddMessageReaction(chatId, messageId, TdApi.ReactionTypeEmoji("👍"), false, false),
            )
            Log.i(TAG, "点赞回写已受理 chatId=$chatId msgId=$messageId")
            // 回查确认：GetMessage 对已缓存消息是本地库读，代价极低；delay 给
            // UpdateMessageReaction 更新落库留窗。确认失败同样静默（纯观测日志）。
            scope.launch(Dispatchers.IO) {
                runCatching {
                    delay(2_000)
                    val m = client.send(TdApi.GetMessage(chatId, messageId))
                    val mine = m.interactionInfo?.reactions?.reactions
                        ?.firstOrNull { (it.type as? TdApi.ReactionTypeEmoji)?.emoji == "👍" }
                    Log.d(
                        TAG,
                        "点赞回写确认 chatId=$chatId msgId=$messageId 👍总数=${mine?.totalCount ?: 0} 含我=${mine?.isChosen == true}",
                    )
                }
            }
            true
        }.getOrElse {
            Log.w(TAG, "点赞回写失败（静默，不影响本地点赞）chatId=$chatId msgId=$messageId: ${it.message}")
            false
        }
    }

    // ---------------- 频道礼包（合集 addlist 一键分享，R130） ----------------

    /** 能力诊断只跑一次的会话标记（见 createChannelPackInviteLink 开头） */
    @Volatile
    internal var packCapabilityProbed = false

    /**
     * R220 账号写保护闸（TEST_MODE）：[AppPrefs.agentNoAccountWrite] 开启时，一切
     * 「对用户真实 TG 账号落写」的礼包路径（建夹/转可分享/导出链接/整包收下）一律在
     * 发送前拦截。背景：测试机/模拟器跑的全是**用户主力账号**（R130 测试污染实锤，
     * AGENT-DISCIPLINE §十）——代理跑真机验证前开闸，验证完关（run-as 写
     * shared_prefs + force-stop 重启生效，与 R184 agentVerifySearchQuota 同工具链）。
     * 纯读（枚举/查重/预览）不受影响；清理工具 [deleteTestFolder] 是用户显式确认的
     * 修复动作，不走本闸。
     */
    internal fun accountWriteBlocked(): Boolean = appPrefs.agentNoAccountWrite

    /**
     * 生成频道礼包链接（R130 生产侧）：把一组频道打包成 TG 原生合集邀请链接
     * （t.me/addlist/<slug>，别人点开即可"整包收下"成自己的 TG 文件夹）。
     *
     * 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
     *  - CreateChatFolderInviteLink(int chatFolderId, String name, long[] chatIds)
     *    -> ChatFolderInviteLink{inviteLink: String, name: String, chatIds: long[]}
     *    ——注意与早期 1.8.6 双参形态（name + chatIds）不同：本版带 chatFolderId 首参；
     *  - CreateChatFolder(ChatFolder) -> ChatFolderInfo{id, name, icon, colorId, isShareable, hasMyInviteLinks}
     *    ChatFolder 构造 15 参（name/icon/colorId/isShareable + 三组 chatIds + 八个规则开关）。
     *
     * 两级路线（模拟器实证收敛，见方法体各行日志口径）：
     *  - 路线一（主路）：CreateChatFolder 建一个「普通」文件夹（含目标频道）→
     *    EditChatFolder 转成可分享合集 → CreateChatFolderInviteLink 导出邀请链接。
     *    与官方客户端"分享文件夹"同一链路（官方也是先把文件夹转可分享再出链接；
     *    不转直接导出实证报 FILTER_ID_INVALID，免夹 folderId=0 实证报 Chat folder not found）。
     *    副作用：用户 TG 账号里会多一个同名文件夹（官方分享合集本来就是这个行为；
     *    带活跃邀请链接的文件夹在 TG 里无法直接删，需先删链接——不在本任务范围）；
     *  - 路线二（兜底）：直建 isShareable=true 的可分享合集再导出（本版实证
     *    "Can't create shareable folder" 被拒，留作其他账号/版本的主路保底）。
     *
     * 节制口径：一次生成本身是一次写操作，由 UI 显式触发；失败静默返回 null（调用方
     * 自行提示），不向上抛错。
     *
     * R220 加固（测试污染实锤后的两道闸）：
     *  - **账号写保护**：[accountWriteBlocked] 开启（测试模式）→ 入口即拦，绝不建夹；
     *  - **查重**：建夹前先找同频道集合的既有夹（[findReusablePackFolder]）——
     *    已有链接直接返回、已是可分享合集在其上导出、同名遗孤收编复用；
     *    只有全无命中才走建新夹主路。防「一次测试=用户账号多一个垃圾夹」。
     */
    suspend fun createChannelPackInviteLink(title: String, chatIds: List<Long>): ChannelPackInvite? {
        val ids = chatIds.distinct()
        if (ids.isEmpty() || title.isBlank()) return null
        runCatching { awaitAuthorized() }.onFailure {
            Log.w(TAG, "频道礼包生成失败（未授权/授权等待失败）合集=$title: ${it.message}")
            return null
        }
        // R220 账号写保护闸：测试模式下绝不碰真实账号（防 R130 式污染复发）
        if (accountWriteBlocked()) {
            Log.w(TAG, "频道礼包生成被账号写保护拦截（R220 测试模式，未建夹）合集=$title")
            return null
        }
        // 一次性账号能力诊断（会话内只跑一次）：Premium 态 + chatlist 限额 + 既有文件夹可分享态
        if (!packCapabilityProbed) {
            packCapabilityProbed = true
            runCatching {
                val me = client.send(TdApi.GetMe())
                suspend fun opt(name: String): Any = runCatching {
                    when (val v = client.send(TdApi.GetOption(name))) {
                        is TdApi.OptionValueInteger -> v.value
                        is TdApi.OptionValueString -> v.value
                        is TdApi.OptionValueBoolean -> v.value
                        else -> "?"
                    }
                }.getOrDefault("?")
                val folderStates = chatFolderIds.map { id ->
                    "$id:" + runCatching {
                        (client.send(TdApi.GetChatFolder(id)) as TdApi.ChatFolder).isShareable
                    }.getOrNull()
                }
                Log.i(
                    TAG,
                    "频道礼包能力诊断：isPremium=${me.isPremium} " +
                        "可分享合集上限=${opt("chatlist_invites_limit_default")}/${opt("chatlist_invites_limit_premium")} " +
                        "可加入合集上限=${opt("chatlists_joined_limit_default")}/${opt("chatlists_joined_limit_premium")} " +
                        "已有文件夹=${folderStates.joinToString()}",
                )
            }
        }
        // R220 查重：建夹前先找同频道集合的既有夹——命中即复用（返回已有链接/在已有
        // 夹上导出/收编遗孤），绝不 CreateChatFolder 重复建。防 R130 式测试污染
        // （账号里积一堆同名同集合的垃圾夹）。失败静默回落建新夹主路。
        runCatching { findReusablePackFolder(title, ids) }.getOrNull()?.let { reuse ->
            Log.i(TAG, "频道礼包查重命中：复用已有夹（R220，未建新夹）合集=$title 频道数=${reuse.chatIds.size}")
            return reuse
        }
        // 路线一（已弃用）：chatFolderId=0 免夹直链——模拟器实证报 "Chat folder not found"
        // （本版 folderId 无 0 语义，必须指向真实存在的可分享合集），不再白烧请求。
        // 路线二（主路）：建普通文件夹 → EditChatFolder 转可分享合集 → 导出邀请链接
        // （官方客户端"分享文件夹"同款链路；不转直接导出实证报 FILTER_ID_INVALID）
        val regular = runCatching {
            val folder = client.send(
                TdApi.CreateChatFolder(
                    TdApi.ChatFolder(
                        TdApi.ChatFolderName(TdApi.FormattedText(title, emptyArray()), false),
                        null, // icon 交由服务端按名称猜默认图标
                        0,
                        false, // 普通文件夹：下一步转可分享
                        LongArray(0),
                        ids.toLongArray(),
                        LongArray(0),
                        // 八个规则开关全关（礼包是显式频道清单，不带规则）
                        false, false, false, false, false, false, false, false,
                    )
                )
            )
            Log.i(TAG, "频道礼包建夹 folderId=${folder.id} 合集=$title，转可分享…")
            // 转可分享合集：不转直接导出会报 FILTER_ID_INVALID（合集邀请只能出自可分享合集）。
            // 注意：本版 TDLib 对该编辑返回 "Can't convert a shareable folder to a non-shareable"
            //（客户端侧拦截改标志），但实证服务端已受理转换（updateDialogFilter 落了
            // dialogFilterChatlist）——错误只记日志不阻断，紧接着的导出依赖服务端转换生效
            runCatching {
                client.send(
                    TdApi.EditChatFolder(
                        folder.id,
                        TdApi.ChatFolder(
                            TdApi.ChatFolderName(TdApi.FormattedText(title, emptyArray()), false),
                            null,
                            0,
                            true, // 转成可分享合集
                            LongArray(0),
                            ids.toLongArray(),
                            LongArray(0),
                            false, false, false, false, false, false, false, false,
                        ),
                    )
                )
            }.onFailure { Log.i(TAG, "频道礼包转可分享回执（诊断）：${it.message}") }
            // 导出邀请链接：服务端转换是异步落定的（实证快则 ~100ms、慢则 ~450ms），
            // 首发撞 FILTER_ID_INVALID 属转换未落定——小步重试 ×3 兜住时序抖动
            var link: TdApi.ChatFolderInviteLink? = null
            var lastErr: Throwable? = null
            for (attempt in 1..3) {
                val got = runCatching {
                    client.send(TdApi.CreateChatFolderInviteLink(folder.id, title, ids.toLongArray()))
                }.onFailure { lastErr = it; Log.i(TAG, "频道礼包导出第${attempt}次被拒（诊断）：${it.message}") }.getOrNull()
                if (got != null && !got.inviteLink.isNullOrBlank()) {
                    link = got
                    break
                }
                delay(600)
            }
            val final = link ?: error("导出邀请链接失败: ${lastErr?.message}")
            Log.i(
                TAG,
                "频道礼包链接已生成（普通夹+建链路线 folderId=${folder.id}）：${final.inviteLink} 频道数=${final.chatIds?.size ?: ids.size} 合集=$title",
            )
            ChannelPackInvite(final.inviteLink, title, final.chatIds?.toList() ?: ids)
        }.onFailure { Log.i(TAG, "频道礼包建夹/建链路线被拒（诊断）：${it.message}") }.getOrNull()
        if (regular != null) return regular
        // 路线二（兜底）：直建可分享合集再导出（可分享合集的服务端形态是 dialogFilterChatlist：
        // 无规则无排除、聊天集在 pinned+included；参数矩阵逐形态实测，任一成即收）
        data class Variant(val tag: String, val icon: TdApi.ChatFolderIcon?, val colorId: Int, val pinned: List<Long>)
        val variants = listOf(
            Variant("icon+同集pin", TdApi.ChatFolderIcon("Auto"), 0, ids),
            Variant("icon", TdApi.ChatFolderIcon("Auto"), 0, emptyList()),
            Variant("icon+color-1", TdApi.ChatFolderIcon("Auto"), -1, emptyList()),
            Variant("color-1", null, -1, emptyList()),
        )
        for (v in variants) {
            val made = runCatching {
                val folder = client.send(
                    TdApi.CreateChatFolder(
                        TdApi.ChatFolder(
                            TdApi.ChatFolderName(TdApi.FormattedText(title, emptyArray()), false),
                            v.icon,
                            v.colorId,
                            true, // 可分享合集：纯频道清单，不带任何规则开关
                            v.pinned.toLongArray(),
                            ids.toLongArray(),
                            LongArray(0),
                            false, false, false, false, false, false, false, false,
                        )
                    )
                )
                val link = client.send(TdApi.CreateChatFolderInviteLink(folder.id, title, ids.toLongArray()))
                Log.i(
                    TAG,
                    "频道礼包链接已生成（直建可分享合集·${v.tag} folderId=${folder.id}）：${link.inviteLink} 频道数=${link.chatIds?.size ?: ids.size} 合集=$title",
                )
                ChannelPackInvite(link.inviteLink, title, link.chatIds?.toList() ?: ids)
            }.onFailure { Log.i(TAG, "频道礼包直建可分享合集被拒（诊断·${v.tag}）：${it.message}") }.getOrNull()
            if (made != null) return made
        }
        Log.w(TAG, "频道礼包链接生成失败（建夹路线全部）合集=$title")
        return null
    }

    /**
     * R220 查重实现：枚举既有夹（UpdateChatFolders 信息流 + 逐夹 GetChatFolder 汇成
     * [FolderDupCandidate]），[decideFolderReuse] 纯函数决策后按分支执行：
     *  - ReuseExistingLink：GetChatFolderInviteLinks 挑同频道集合的既有链接直接返回
     *    （零新增写——查重命中的最优解）；链接列表取不到/为空则回落「在其上导出」；
     *  - ExportOnExisting：夹已是可分享合集，直接在它身上导出（不建新夹）；
     *  - AdoptOrphan：上次生成中途失败留下的同名普通夹，先 Edit 转可分享再导出
     *    （与主路同款：Edit 回执错误不阻断，服务端已受理转换）；
     *  - CreateNew：无可复用 → 返回 null（调用方走建新夹主路）。
     * 导出统一带主路同款小步重试 ×3（服务端转换落定时序抖动兜底）。
     * 本路任一步失败都静默返回 null 回落主路——查重只许省写，不许挡路。
     */
    private suspend fun findReusablePackFolder(
        title: String,
        ids: List<Long>,
    ): ChannelPackInvite? {
        awaitChatFolderSync()
        val candidates = chatFolderInfos.mapNotNull { info ->
            runCatching {
                val f = client.send(TdApi.GetChatFolder(info.id)) as? TdApi.ChatFolder
                    ?: return@mapNotNull null
                FolderDupCandidate(
                    folderId = info.id,
                    title = info.name?.text?.text ?: "",
                    memberIds = (f.pinnedChatIds.toList() + f.includedChatIds.toList()).toSet(),
                    isShareable = info.isShareable,
                    hasMyInviteLinks = info.hasMyInviteLinks,
                )
            }.getOrNull()
        }
        var exportFolderId: Int? = null
        var needConvert = false
        when (val decision = decideFolderReuse(candidates, title, ids.toSet())) {
            is FolderReuseDecision.ReuseExistingLink -> {
                val links = runCatching {
                    client.send(TdApi.GetChatFolderInviteLinks(decision.folderId)).inviteLinks
                }.getOrDefault(emptyArray())
                // 链接即礼包：同集合的既有链接直接返回，零新增写（名字用链接自己的，
                // 展示诚实——那是这个合集在服务端的真名）
                links.firstOrNull { !it.inviteLink.isNullOrBlank() }?.let { link ->
                    return ChannelPackInvite(link.inviteLink, link.name ?: title, link.chatIds?.toList() ?: ids)
                }
                // 标志位过期（链接已被删）：该夹仍可分享，落到「在其上导出」
                exportFolderId = decision.folderId
            }
            is FolderReuseDecision.ExportOnExisting -> exportFolderId = decision.folderId
            is FolderReuseDecision.AdoptOrphan -> {
                exportFolderId = decision.folderId
                needConvert = true
            }
            FolderReuseDecision.CreateNew -> return null
        }
        val folderId = exportFolderId ?: return null
        // 遗孤收编：普通夹转可分享（与主路同款回执口径——错误只记日志不阻断）
        if (needConvert) {
            runCatching {
                client.send(
                    TdApi.EditChatFolder(
                        folderId,
                        TdApi.ChatFolder(
                            TdApi.ChatFolderName(TdApi.FormattedText(title, emptyArray()), false),
                            null,
                            0,
                            true,
                            LongArray(0),
                            ids.toLongArray(),
                            LongArray(0),
                            false, false, false, false, false, false, false, false,
                        ),
                    )
                )
            }.onFailure { Log.i(TAG, "频道礼包查重·遗孤转可分享回执（诊断）：${it.message}") }
        }
        // 在既有夹上导出（服务端转换落定时序同主路：小步重试 ×3 兜抖动）
        for (attempt in 1..3) {
            val got = runCatching {
                client.send(TdApi.CreateChatFolderInviteLink(folderId, title, ids.toLongArray()))
            }.onFailure { Log.i(TAG, "频道礼包查重·复用导出第${attempt}次被拒（诊断）：${it.message}") }
                .getOrNull()
            if (got != null && !got.inviteLink.isNullOrBlank()) {
                return ChannelPackInvite(got.inviteLink, title, got.chatIds?.toList() ?: ids)
            }
            delay(600)
        }
        Log.w(TAG, "频道礼包查重命中但复用导出失败 folderId=$folderId，回落建新夹主路")
        return null
    }

    // ---------------- R220 测试文件夹清理（诊断通道工具，app 内） ----------------

    /** 测试文件夹名前缀：R130 时代代理测试建夹的统一命名前缀（清理工具的匹配口径） */
    internal val testFolderPrefix = "R130"

    /**
     * R220 清理工具·列表（只读）：名字以 [testFolderPrefix] 开头的文件夹快照。
     * 列表源 = UpdateChatFolders 信息流（本版 TDLib 无 LoadChatFolders，授权后
     * TDLib 自推一次当前夹列表）+ 逐夹 GetChatFolder 补频道数。
     * 返回 null = 扫描失败（未授权/文件夹列表同步超时——UI 给「重试」而不是误报
     * 「干净」）；空表 = 扫描成功且无命中。日志只落条数不落夹名/频道名（输出脱敏铁律）。
     */
    suspend fun listTestFolders(): List<TestFolderSnapshot>? {
        runCatching { awaitAuthorized() }.onFailure {
            Log.w(TAG, "清理工具·列夹失败（未授权）：${it.message}")
            return null
        }
        return runCatching {
            awaitChatFolderSync()
            if (!_chatFoldersSynced.value) return@runCatching null
            chatFolderInfos
                .filter { info -> (info.name?.text?.text ?: "").startsWith(testFolderPrefix) }
                .mapNotNull { info ->
                    val f = runCatching { client.send(TdApi.GetChatFolder(info.id)) }.getOrNull()
                    TestFolderSnapshot(
                        folderId = info.id,
                        name = info.name?.text?.text ?: "",
                        chatCount = f?.let { (it.pinnedChatIds.toList() + it.includedChatIds.toList()).distinct().size } ?: -1,
                        isShareable = info.isShareable,
                        inviteLinkCount = if (info.hasMyInviteLinks) {
                            runCatching { client.send(TdApi.GetChatFolderInviteLinks(info.id)).inviteLinks?.size ?: 0 }
                                .getOrDefault(0)
                        } else 0,
                    )
                }
        }.onFailure {
            Log.w(TAG, "清理工具·列夹失败：${it.message}")
            null
        }.getOrNull()?.also {
            Log.i(TAG, "清理工具·扫描：全夹 ${chatFolderInfos.size} 个，命中「$testFolderPrefix」前缀 ${it.size} 个")
        }
    }

    /**
     * R220 清理工具·删除单个文件夹（**必须用户确认后调用**，UI 层负责确认闸）：
     *  1. 带活跃邀请链接的夹 TG 拒删（实测+官方行为）——先 GetChatFolderInviteLinks
     *     逐条 DeleteChatFolderInviteLink（链接失效只记日志，不阻断删夹）；
     *  2. DeleteChatFolder(folderId, **leaveChatIds 恒空**)——只删分组本身，
     *     **绝不退出/退订任何已加入的频道**（任务红线；leaveChatIds 是「删夹时要一并
     *     退出哪些聊天」的参数，传空 = 频道全部原地保留）。
     * 不走 [accountWriteBlocked] 闸：这是用户显式确认的修复动作，不是测试流量。
     */
    suspend fun deleteTestFolder(folderId: Int): Boolean {
        runCatching { awaitAuthorized() }.onFailure {
            Log.w(TAG, "清理工具·删夹失败（未授权）folderId=$folderId: ${it.message}")
            return false
        }
        return runCatching {
            val links = runCatching {
                client.send(TdApi.GetChatFolderInviteLinks(folderId)).inviteLinks
            }.getOrDefault(emptyArray())
            links.forEach { link ->
                runCatching { client.send(TdApi.DeleteChatFolderInviteLink(folderId, link.inviteLink)) }
                    .onFailure { Log.w(TAG, "清理工具·删链接失败 folderId=$folderId: ${it.message}") }
            }
            client.send(TdApi.DeleteChatFolder(folderId, LongArray(0)))
            Log.i(TAG, "清理工具·已删测试文件夹 folderId=$folderId（先清链接 ${links.size} 条；未退任何频道）")
            true
        }.onFailure {
            Log.w(TAG, "清理工具·删测试文件夹失败 folderId=$folderId: ${it.message}")
            false
        }.getOrDefault(false)
    }

    /**
     * 频道礼包整包收下（R130 消费侧）：先 CheckChatFolderInviteLink 免加入解析
     * （拿合集名 + missingChatIds），再 AddChatFolderByInviteLink 把缺失频道
     * 一次性加入并落成本人 TG 账号里的合集文件夹——与本 App 刷 Feed 的频道枚举
     * 天然兼容（listChannels 本就扫主列表/归档/全部文件夹，收下后频道自动进 Feed）。
     *
     * 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
     *  - CheckChatFolderInviteLink(String inviteLink) -> ChatFolderInviteLinkInfo
     *    {chatFolderInfo: ChatFolderInfo, missingChatIds: long[], addedChatIds: long[]}
     *  - AddChatFolderByInviteLink(String inviteLink, long[] chatIds) -> Ok
     *    （chatIds = 要加入的频道，传解析出的 missingChatIds 全量）
     *
     * 失败语义：链接失效/超过可加合集数上限/网络错误 → [ChannelPackJoin.Failed]
     * （friendlyError 翻译）；missing 为空 = 之前已全收下（Joined，chatCount=0）。
     */
    suspend fun joinChannelPackInvite(inviteLink: String): ChannelPackJoin {
        runCatching { awaitAuthorized() }.onFailure {
            return ChannelPackJoin.Failed(friendlyError(it, "尚未就绪，稍后再试"))
        }
        // R220 账号写保护闸：整包收下 = 在用户账号里加合集文件夹，测试模式下拦截
        if (accountWriteBlocked()) {
            Log.w(TAG, "频道礼包整包收下被账号写保护拦截（R220 测试模式）link=$inviteLink")
            return ChannelPackJoin.Failed("账号写保护开启中（测试模式），整包收下已拦下")
        }
        return runCatching {
            val info = client.send(TdApi.CheckChatFolderInviteLink(inviteLink))
            val folderName = info.chatFolderInfo?.name?.text?.text?.takeIf { it.isNotBlank() } ?: "频道礼包"
            val missing = info.missingChatIds?.toList().orEmpty()
            if (missing.isEmpty()) {
                Log.d(TAG, "频道礼包整包收下：频道此前已全部收下 合集=$folderName link=$inviteLink")
                return@runCatching ChannelPackJoin.Joined(folderName, 0)
            }
            client.send(TdApi.AddChatFolderByInviteLink(inviteLink, missing.toLongArray()))
            Log.i(TAG, "频道礼包整包收下完成：合集=$folderName 新收=${missing.size} link=$inviteLink")
            ChannelPackJoin.Joined(folderName, missing.size)
        }.getOrElse {
            Log.w(TAG, "频道礼包整包收下失败 link=$inviteLink: ${it.message}")
            ChannelPackJoin.Failed(friendlyError(it, "整包收下失败"))
        }
    }

    /**
     * 频道礼包免加入预览（R137 消费侧「先看再收」）：CheckChatFolderInviteLink 只读解析
     * （合集名 + missing/added 全量频道），逐频道 GetChat 补标题顺手喂缓存。
     * 与 [joinChannelPackInvite] 的区别：**绝不加合集、不落任何会话状态**——礼包卡封面
     * 的「N 个频道」徽标与头像堆叠、拆开面板的成员清单都从这里来（同一次解析两处复用）。
     * 任何失败返回 null，调用方回落占位（礼包链接失效/网络抖动时卡片仍可整体渲染）。
     */
    suspend fun peekChannelPack(inviteLink: String): ChannelPackPreview? {
        runCatching { awaitAuthorized() }.onFailure { return null }
        return runCatching {
            val info = client.send(TdApi.CheckChatFolderInviteLink(inviteLink))
            val folderName = info.chatFolderInfo?.name?.text?.text?.takeIf { it.isNotBlank() } ?: "频道礼包"
            val added = info.addedChatIds?.toList().orEmpty()
            // missing+added 合并去重（与 harvestChatFolderInviteImpl 同口径），missing 在前
            val ids = (info.missingChatIds?.toList().orEmpty() + added).distinct()
            val channels = ids.map { id ->
                val title = runCatching { client.send(TdApi.GetChat(id)).title }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: peekChatTitle(id)
                    ?: "未知频道"
                channelTitleCache.putIfAbsent(id, title)
                ChannelBrief(id, title, null)
            }
            Log.d(TAG, "频道礼包预览：合集=$folderName 成员=${channels.size} 已加入=${added.size}")
            ChannelPackPreview(folderName, channels, added.toSet())
        }.onFailure {
            Log.w(TAG, "频道礼包预览失败 link=$inviteLink: ${it.message}")
        }.getOrNull()
    }

    // ---------------- 发现页：相似频道递归 + resolveQueue 懒解析（DISCOVERY-GRAPH M1 后段） ----------------

    /** 相似频道递归的会话级查询计数闸：整个网关实例生命周期内累计，不随调用复位 */
    internal val similarDiscoverCalls = java.util.concurrent.atomic.AtomicInteger(0)

    /** 关闭 TDLib 客户端（切网关时调用，防旧客户端泄漏）。幂等。 */
    fun close() {
        // R97：治理周期循环随网关一起停（否则旧循环挂着已关闭的客户端每小时空转报错）
        runCatching { storageGovernJob?.cancel() }
        // R195：激进整载循环随网关一起停（否则旧循环挂着已关闭的客户端 1Hz 空转报错）；
        // 在途整载交 TDLib 自然收尾（已落盘字节保留，新网关会话按需重判）
        runCatching { aggressivePreloadManager.stop() }
        // 收割器停止 + 答案库兜底落盘（isInitialized：不为"停止"白构造从未启动的收割器）
        runCatching { if (groupHarvesterLazy.isInitialized()) groupHarvesterLazy.value.stop() }
        // 429 熔断表兜底落盘（R44：死信变更走阈值 flush，这里保住退出前的尾巴）
        runCatching { floodBreaker.flush() }
        // R184 搜索账本/resolve-once 缓存兜底落盘（同口径：平时阈值自动 flush，这里保尾巴）
        runCatching { searchQuotaLedger.flush() }
        runCatching { resolveOnceCache.flush() }
        // 资源嗅探图谱兜底落盘（平时靠变更阈值自动 flush，这里保住退出前的尾巴）
        runCatching { discoveryGraph.flush() }
        runCatching { client.close() }
    }

    /**
     * 高清封面缓存：coverFileId → 图片字节（进程内）。
     * R169（P0-1 / F7）：从无界 ConcurrentHashMap 换成有界 LRU（[BoundedLruCache]）——
     * 旧口径只进不出（全仓无 remove/无上限），长会话刷数百条=数百张封面字节常驻，
     * 正是 R165 灾变链（native 耗尽→TDLib 冻死→裸 send 永挂）的燃料；R155 修了
     * CoverStore/CoverExtractor 双表，这口是漏网的同族最后一口大锅。
     * 现为 [COVER_BYTES_CACHE_MAX] 条访问序 LRU + 入表前超 [COVER_BYTES_MAX_DIM]px
     * 降采样重编码（照抄 CoverExtractor 正解）。operator get/set 与原 Map 方括号
     * 语法兼容，GatewayCover 的 8 处读写点零改动、未来写入点自动受闸。
     */
    internal val coverBytesCache = BoundedLruCache<Int, ByteArray>(COVER_BYTES_CACHE_MAX, ::downscaleCoverBytes)

    // ---- 封面加载公共 API 委托（实现见 GatewayCover.kt） ----
    suspend fun loadCoverBytes(item: VideoItem): ByteArray? = loadCoverBytesImpl(item)
    suspend fun loadPhotoViewPath(item: VideoItem): String? = loadPhotoViewPathImpl(item)
    suspend fun loadAudioCoverBytes(item: VideoItem): ByteArray? = loadAudioCoverBytesImpl(item)
    suspend fun reviveFileIdByMessage(chatId: Long, messageId: Long): Int? = reviveFileIdByMessageImpl(chatId, messageId)
    suspend fun resolveCoverFrameFileId(item: VideoItem): Int? = resolveCoverFrameFileIdImpl(item)
    suspend fun downloadPartialCover(fileId: Int): String? = downloadPartialCoverImpl(fileId)
    suspend fun loadCoverBytesByMessage(item: VideoItem): ByteArray? = loadCoverBytesByMessageImpl(item)

    internal val coverFileMinted = java.util.concurrent.ConcurrentHashMap<Int, MintedCover>()

    /**
     * 封面类下载全局并发闸（W7）：loadCoverBytes/loadAudioCoverBytes/getChannelAvatar
     * 与消息反解封面的 DownloadFile 共用 5 路——慢代理下一屏 20+ 格各自同步下载会把
     * 带宽打爆、成片 15s 超时跌成频道头像兜底（"搜索封面大量重复"实锤之二）；
     * 收闸后排队下载，且不再与视频播放抢带宽
     */
    internal val coverDownloadSem = Semaphore(5)

    // ---- 类型 tab 聚合拉取公共 API 委托（实现见 GatewayMediaTab.kt） ----
    suspend fun fetchAggregatedMedia( chatIds: List<Long>, mediaType: Int, cursor: String?, limit: Int, expandCaptions: Boolean = true, ): FeedPage = fetchAggregatedMediaImpl(chatIds, mediaType, cursor, limit, expandCaptions)
    suspend fun fetchAggregatedMediaAll( mediaType: Int, cursor: String?, limit: Int, ): FeedPage = fetchAggregatedMediaAllImpl(mediaType, cursor, limit)

    // ---- 频道目录/深链解析公共 API 委托（实现见 GatewayChannelDirectory.kt） ----
    suspend fun listSearchableChats(): List<ChannelBrief> = listSearchableChatsImpl()
    /** R216 媒体 tab 路径专用：免 GetChat 标题清扫的 id 枚举（见 listSearchableChatIdsImpl 注释） */
    internal suspend fun listSearchableChatIds(): List<Long> = listSearchableChatIdsImpl()
    suspend fun resolveChannelBrief(username: String): ChannelBrief? = resolveChannelBriefImpl(username)
    suspend fun resolvePublicChannel(username: String): PublicChannelResolve = resolvePublicChannelImpl(username)
    /**
     * R183 开频道自愈 + R184 三态化：本地无会话的频道按 GetChat→resolve-once 缓存→
     * username/标题解析建会话（只认 chatId 严格相等；调用侧加超时）。限流
     * （封禁窗/熔断/账号预算/429）返回 [ChannelOpenHeal.RateLimited]——调用方
     * 诚实上抛「搜索暂时被限流」并入队后台重试，不再与「真打不开」混为一谈。
     */
    suspend fun ensureChannelOpenable(chatId: Long, title: String, username: String?): ChannelOpenHeal =
        ensureChannelOpenableImpl(chatId, title, username)
    suspend fun resolveJoinedChannel(chatId: Long): ChannelBrief? = resolveJoinedChannelImpl(chatId)
    suspend fun resolveDeepLinkChat(chatId: Long, rawLink: String): DeepLinkChatResolve = resolveDeepLinkChatImpl(chatId, rawLink)
    suspend fun resolveInviteLink(inviteLink: String): InviteLinkResolve = resolveInviteLinkImpl(inviteLink)

    @Volatile
    internal var searchableChatsSnapshot: SearchableChatsSnapshot? = null

    /** R216 媒体 tab 路径 id-only 枚举快照（免 GetChat 清扫，见 listSearchableChatIdsImpl） */
    @Volatile
    internal var searchableChatIdsSnapshot: SearchableChatIdsSnapshot? = null

    // ---- 频道内搜索/统计公共 API 委托（实现见 GatewayChannelSearch.kt） ----

    /**
     * 最近一次 SearchPublicPosts 响应的配额快照：
     * Triple(remainingFreeQueryCount 今日剩余免费次数, dailyFreeQueryCount 每日免费总额,
     *        areLimitsExceeded 是否已超限)。
     * 供 TasteRadar 的配额闸"见 0/超限即停"与 UI 配额透明展示（红线：配额透明）。
     * @Volatile：巡逻协程写、任意线程读；null = 本会话还没做过公开帖子搜索。
     * （setter 放宽为 internal：写入点在 GatewayChannelSearch.kt 的扩展函数里）
     */
    @Volatile
    var publicPostQuota: Triple<Int, Int, Boolean>? = null
        internal set
    suspend fun fetchChannelPageAnchored(chatId: Long, anchorMessageId: Long, limit: Int): FeedPage = fetchChannelPageAnchoredImpl(chatId, anchorMessageId, limit)
    suspend fun expandAlbumMembers( chatId: Long, albumId: Long, anchorMessageId: Long, ): List<VideoItem> = expandAlbumMembersImpl(chatId, albumId, anchorMessageId)
    /**
     * R238 评论媒体流公共口（实现见 GatewayFeedFetch.fetchCommentVideos）：
     * 评论区媒体金矿——用户实锤"37 条评论全是媒体资源"。级联路（fetchFromChannel）
     * 内部同源复用；评论流入口（BottomInfoPanel 角标点按→FeedViewModel.openCommentStream）
     * 是用户显式动作，limit 放宽到 50。任何失败（flood/关评论/无线程）返回空表不抛。
     */
    suspend fun fetchCommentVideos(chatId: Long, messageId: Long, chTitle: String, limit: Int = COMMENT_CASCADE_REPLY_LIMIT): List<VideoItem> =
        fetchCommentVideosImpl(chatId, messageId, chTitle, limit)
    suspend fun searchChannelAll(chatId: Long, query: String, cursor: String? = null, limit: Int = 50): FeedPage = searchChannelAllImpl(chatId, query, cursor, limit)
    suspend fun searchChannelComments(chatId: Long, query: String, limit: Int = 10): List<VideoItem> = searchChannelCommentsImpl(chatId, query, limit)
    suspend fun getChatActivityDays(
        chatId: Long,
        filter: TdApi.SearchMessagesFilter = TdApi.SearchMessagesFilterPhotoAndVideo(),
        maxMonths: Int = CALENDAR_MAX_MONTHS,
    ): List<Pair<Int, Int>> = getChatActivityDaysImpl(chatId, filter, maxMonths)
    suspend fun getMessageIdByDate(chatId: Long, epochSec: Int): Long? = getMessageIdByDateImpl(chatId, epochSec)
    suspend fun probeMediaTotalCount(chatId: Long): Int? = probeMediaTotalCountImpl(chatId)
    suspend fun probeChannelStats(chatId: Long): ChannelStatTriple? = probeChannelStatsImpl(chatId)
    suspend fun searchPublicPosts(query: String, offset: String = "", limit: Int = 20): Pair<List<VideoItem>, String?> = searchPublicPostsImpl(query, offset, limit)

    // ---------------- 时间机器·频道考古（R134，ideas.md #7）：GetChatHistory 日期二分历史跳跃 ----------------

    /**
     * 时间机器·按时间档位跳跃（考古主 API）：给 chatId + 目标时间点，收敛到"不晚于
     * 目标的最后一条消息"，并从落点向更旧翻出一页媒体。
     *
     * 为什么绕开 GetChatMessageCalendar：实测本构建挂死（GatewayChannelSearch 的日历
     * 注释有实证），按 ideas.md #7 的降级方案改用 GetChatHistory(fromMessageId) 二分：
     *  1. 摸上界：from=0 探最新一条；目标不早于它（频道比目标年轻）→ 零层直接落最新；
     *  2. 二分：频道消息 id = 帖子序号 × 2^20（[ARCHAEO_ID_GRID]，均匀网格），在帖子
     *     序号空间二分。探测原语 [archaeoProbe]：fromMessageId 在频道里是纯位置界
     *     （实证：低于第 1 帖的 id 拉空页、不报错），返回"该位之下最新一条"的 (id, date)。
     *     date 晚于目标 → 上界收紧到它；否则它成为"不晚于目标"的最大候选，下界收紧；
     *  3. 插值加速：上下界都有时间证据后，探测位按目标时刻在两界时间轴上的比例取点
     *     （发帖速率均匀的频道一步近点省层数）；比例退化/越界自动回纯中点二分；
     *  4. 收口：二分最多 [ARCHAEO_MAX_HOPS] 层（需求：跳 5 层以内）；探测时间距目标
     *     ≤ [ARCHAEO_TOLERANCE_SEC] 提前收；区间收敛到相邻两帖即停。落点 = 已见
     *     "不晚于目标"的最大候选；全程没见过（目标早于频道出生）→ 从频道底摸最老
     *     存量帖落底（诚实呈现"频道那时还没出生"）；
     *  5. 取页：[fetchChannelPageAnchored]（与"查看上下文"同一条链：锚点入窗、向更旧
     *     翻、本地媒体过滤），落点附近一页媒体即"逛考古层"的原料；取页失败静默空页
     *     （落点信息仍有效，UI 按"这一层没挖到媒体"呈现）。
     *
     * 观测（需求钉死）：logcat（tag [TAG]）"考古一铲"汇总行——跳跃层数、目标/落点
     * 时间戳（原始 Unix 秒 + 可读时间）、误差天数、该层媒体数；二分逐跳 debug 行。
     * 失败（未授权/超时/空频道/网络异常）返回 null，调用方按"这一铲没挖到"呈现。
     */
    suspend fun archaeoJumpToTime(chatId: Long, targetEpochSec: Int): ArchaeoDig? {
        return try {
            awaitAuthorized()
            // 摸上界：from=0 恒回最新一条（GetChatHistory 语义：0 = 从最后一条开始）
            val newest = archaeoProbe(chatId, 0L) ?: return null
            if (newest.date <= targetEpochSec) {
                return digOf(chatId, newest, targetEpochSec, hops = 1, random = false,
                    note = "目标不早于频道最新一条，零层直接落最新")
            }
            // ── 日期二分（帖子序号空间）──
            // 不变量：Q（不晚于目标的最后一条帖子序号）∈ [lo, hi)；best = 已见"不晚于
            // 目标"的最大候选消息（Q 的实锤下界证据）
            var lo = 1L
            var hi = newest.id / ARCHAEO_ID_GRID // 最新一条晚于目标 → Q < 它
            var hiDate = newest.date // 上界位置的时间证据（插值用）
            var best: ArchaeoHit? = null
            var hops = 0
            while (hops < ARCHAEO_MAX_HOPS && hi - lo > 1L) {
                val span = hi - lo
                // 插值探测位：按目标时刻在两界时间轴上的比例取点；无下界证据（目标可能
                // 早于频道出生）/比例退化时回纯中点二分
                val biased = if (best != null && hiDate > best.date) {
                    lo + span * (targetEpochSec - best.date) / (hiDate - best.date)
                } else -1L
                // 收进开区间 (lo, hi)，至少离两端各 1 帖防原地踏步
                val post = (if (biased in lo until hi) biased else lo + span / 2L)
                    .coerceIn(lo + 1L, hi - 1L)
                val m = archaeoProbe(chatId, post * ARCHAEO_ID_GRID)
                hops++
                Log.d(TAG, "考古二分 hop=$hops chatId=$chatId 探测帖位=$post 命中id=${m?.id ?: 0} 命中时间=${m?.date ?: 0}")
                when {
                    // 该位之下无存量帖（头部删帖空档）：Q 若存在必 ≥ post
                    m == null -> lo = post
                    // 它晚于目标（date 随 id 单调不减）→ Q 在它之下
                    m.date > targetEpochSec -> {
                        hi = m.id / ARCHAEO_ID_GRID
                        hiDate = m.date
                    }
                    else -> {
                        // 它不晚于目标 → Q ≥ 它；刷新"不晚于目标"的最大候选
                        val p = m.id / ARCHAEO_ID_GRID
                        if (p >= lo) {
                            lo = p
                            best = m
                        }
                        // 时间容差内提前收：落点=本条，省下层数
                        if (targetEpochSec - m.date <= ARCHAEO_TOLERANCE_SEC) break
                    }
                }
            }
            val landing = best ?: archaeoOldestStock(chatId) ?: newest
            digOf(chatId, landing, targetEpochSec, hops = hops + 1, random = false,
                note = if (best == null) "目标早于已见全部日期，落最老存量层" else "二分收敛")
        } catch (e: Exception) {
            Log.w(TAG, "archaeoJumpToTime 失败 chatId=$chatId target=$targetEpochSec: ${e.message}")
            null
        }
    }

    /**
     * 时间机器·随机一铲：频道全部历史里随机 messageId 锚点取一页（盲盒）。
     *
     * 锚点 = 随机帖子探测位之下最新一条存量消息——帖子探测位在序号空间均匀取点，
     * 落点近似均匀铺满全历史（"从没刷到过的年代"才是盲盒快感的来源）。
     * 随机位低于频道最老存量帖（头部删帖空档）时重掷，封顶 [ARCHAEO_RANDOM_REROLLS] 次；
     * 重掷仍空（畸形频道）退化落最新一条。观测与失败语义同 [archaeoJumpToTime]。
     */
    suspend fun archaeoRandomDig(chatId: Long): ArchaeoDig? {
        return try {
            awaitAuthorized()
            val newest = archaeoProbe(chatId, 0L) ?: return null
            val newestPost = newest.id / ARCHAEO_ID_GRID
            var hops = 1
            var landing: ArchaeoHit? = null
            var rolls = 0
            // 随机探测位 ∈ [2, newestPost+1]：帖子序号 1..newestPost 各等概率成为
            // "落位之下最新帖"（2 = 第 1 帖，newestPost+1 = 最新帖）
            while (landing == null && rolls <= ARCHAEO_RANDOM_REROLLS) {
                val pos = Random.nextLong(2L, newestPost + 2L)
                val m = archaeoProbe(chatId, pos * ARCHAEO_ID_GRID)
                hops++
                if (m != null) {
                    landing = m
                    Log.d(TAG, "考古随机 chatId=$chatId 探测帖位=$pos 落点id=${m.id} 落点时间=${m.date}")
                } else {
                    rolls++
                }
            }
            val hit = landing ?: newest
            digOf(chatId, hit, targetEpochSec = hit.date, hops = hops, random = true,
                note = "随机探测 ${rolls + 1} 次${if (rolls > 0) "（前 $rolls 次落在头部空档）" else ""}")
        } catch (e: Exception) {
            Log.w(TAG, "archaeoRandomDig 失败 chatId=$chatId: ${e.message}")
            null
        }
    }

    /**
     * 考古探测原语：GetChatHistory(chatId, fromMessageId, 0, 5, false) 取"fromMessageId
     * 之下最新一条"的 (id, date)。from=0 = 频道最新一条；from 是纯位置界——不存在的
     * id / 删帖空档位不报错（实证：低于第 1 帖的 id 拉空页），响应消息按新→旧排列，
     * 首条即落点。limit 取 5 与追更轮询 H2 同虑（TDLib 自选返回条数，小窗比单条稳）。
     * 空窗（该位之下无存量帖）返回 null。
     */
    private suspend fun archaeoProbe(chatId: Long, fromMessageId: Long): ArchaeoHit? {
        val history = withTimeout(ARCHAEO_PROBE_TIMEOUT_MS) {
            client.send(TdApi.GetChatHistory(chatId, fromMessageId, 0, 5, false))
        }
        val m = history.messages?.filterNotNull()?.firstOrNull() ?: return null
        return ArchaeoHit(m.id, m.date)
    }

    /**
     * 从频道底摸最老存量帖：第 1~3 帖探测位各试一次（覆盖头部删帖空档）；
     * 全空（畸形频道）返回 null，调用方退化落最新一条。
     */
    private suspend fun archaeoOldestStock(chatId: Long): ArchaeoHit? {
        for (p in 2L..4L) {
            archaeoProbe(chatId, p * ARCHAEO_ID_GRID)?.let { return it }
        }
        return null
    }

    /**
     * 落点收口：取考古层一页媒体（锚点入窗向更旧翻，"逛考古层"的原料）+ 打观测
     * 汇总日志（需求钉死：跳跃层数 / 最终落点时间戳）。取页失败静默空页不抛。
     */
    private suspend fun digOf(
        chatId: Long,
        landing: ArchaeoHit,
        targetEpochSec: Int,
        hops: Int,
        random: Boolean,
        note: String,
    ): ArchaeoDig {
        val items = runCatching { fetchChannelPageAnchored(chatId, landing.id, ARCHAEO_PAGE_LIMIT).items }
            .onFailure { Log.w(TAG, "考古层取页失败 chatId=$chatId anchor=${landing.id}: ${it.message}") }
            .getOrDefault(emptyList())
        val dig = ArchaeoDig(landing.id, landing.date, hops, targetEpochSec, random, items)
        val driftDays = (landing.date - targetEpochSec) / 86400
        Log.i(
            TAG,
            "考古一铲 chatId=$chatId 模式=${if (random) "随机盲盒" else "档位跳跃"} " +
                "目标=$targetEpochSec(${archaeoTimeText(targetEpochSec)}) 跳跃层数=$hops " +
                "落点msgId=${landing.id} 落点时间戳=${landing.date}(${archaeoTimeText(landing.date)}) " +
                "误差=${driftDays}天 该层媒体=${items.size} $note",
        )
        return dig
    }

    /** 考古日志时间文本（设备时区 yyyy-MM-dd HH:mm；原始 Unix 秒始终在同一行在场） */
    private fun archaeoTimeText(epochSec: Int): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date(epochSec * 1000L))

    /**
     * 展开去重缓存（SEARCH-PERF §2）：key = "a:chatId:albumId"（相册）/ "t:chatId:msgId"（批注帖），
     * value = 上次展开时间戳。TTL [SEARCH_EXPAND_DEDUP_TTL_MS] 内同对象不重复展开——
     * 翻页重搜已覆盖区间、同词重复搜索时同一相册/文字帖不再重复发 GetChatHistory。
     * ConcurrentHashMap：searchChannelAll 被扇出协程并发调用。
     */
    internal val searchExpandCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // ---------------- R49 频道主页统计探测：服务端真实口径（各类型总量 + 成员数） ----------------

    /** 频道统计探测的进程内缓存：chatId → (结果, 探测时间戳)。
     *  1 小时 TTL——同一频道主页反复进出不重复探测（统计数字本身变化慢） */
    internal val channelStatsCache = java.util.concurrent.ConcurrentHashMap<Long, Pair<ChannelStatTriple, Long>>()

    // ---------------- 频道评论（弹幕数据源·"问出处"上下文增强版） ----------------

    /** getCommentsRich 结果进程内缓存：key = VideoItem.key（与 commentsCache 同口径同语义） */
    internal val commentsRichCache = java.util.concurrent.ConcurrentHashMap<String, List<DanmakuItem>>()

    /** 消息封面内存缓存：LinkedHashMap 访问序 LRU，上限 [MSG_COVER_MEM_MAX] 条；全部读写经 synchronized 保护 */
    internal val msgCoverMemCache = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
            size > MSG_COVER_MEM_MAX
    }

    /** 同 key 并发去重锁表：条目加载完即移除，防无限增长 */
    internal val msgCoverMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    /** 消息封面磁盘缓存目录（cacheDir：系统空间紧张可自动清，封面本来就可重建） */
    internal val msgCoverDiskDir: java.io.File by lazy {
        java.io.File(context.applicationContext.cacheDir, "msg_covers").apply { mkdirs() }
    }

    // ---------------- 本人资料（MineScreen 账号卡） ----------------

    /** 本人资料进程内缓存：账号资料会话内几乎不变，进"我的"页不重复 GetMe/DownloadFile */
    @Volatile
    internal var myProfileCache: MyProfile? = null

    // ---------------- 频道 Stories 快拍条（R144，ideas.md #5）：读侧 API ----------------
    // 全部只读（不发 OpenStory/CloseStory 写已看到服务端——已看是本地会话语义，见 _storyWatched 注释）

    /** 快拍条/连播页的活跃快拍只读投影（增量变化经 UpdateChatActiveStories 实时喂） */
    val chatActiveStories: StateFlow<Map<Long, TdApi.ChatActiveStories>> = _chatActiveStories.asStateFlow()

    /** 会话级已看账只读投影（连播页每看完一条翻一次） */
    val storyWatched: StateFlow<Set<String>> = _storyWatched.asStateFlow()

    /**
     * 看完一条快拍（连播页当前条结算时调用）：会话级、幂等、不落盘。
     * 快拍环由红转灰的本地判据就是这笔账（见 [ChannelStoriesBrief.hasUnwatched]）。
     */
    fun noteStoryWatched(chatId: Long, storyId: Int) {
        val key = "$chatId:$storyId"
        if (key in _storyWatched.value) return
        _storyWatched.value = _storyWatched.value + key
        Log.d(TAG, "快拍已看入账 $key（会话级，共 ${_storyWatched.value.size} 条）")
    }

    /**
     * 全量刷新已加入频道的活跃快拍（快拍条首次进 Feed 触发；TTL 10 分钟内直接命中不再跑）。
     *
     * 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
     * GetChatActiveStories(long chatId) -> ChatActiveStories{chatId: long, list: StoryList,
     *   order: long, canBeArchived: boolean, maxReadStoryId: int, stories: StoryInfo[]}
     *   StoryInfo{storyId: int, date: int, isForCloseFriends: boolean, isLive: boolean}
     * —— 注意鉴别：本 AAR 没有 GetChatStoryList / getChatStoryList（1.8.10 时代的全表
     *    拉取口已删，javap 全 jar 类清单实证）。全表快照的正路 = 逐频道 GetChatActiveStories
     *    （TDLib 本地库优先的查询，会话加载时快拍已自动同步，几百频道毫秒级）+
     *    UpdateChatActiveStories 增量维护（init 块已订阅）。
     * 频道级失败静默跳过（快拍是锦上添花，绝不阻塞 Feed 主链路）；并发
     * [CHAT_STORIES_REFRESH_CONCURRENCY] 闸防挤爆 TDLib 请求队列。
     * 返回当前有活跃快拍的频道数（观测口径，logcat 同步打点）。
     */
    suspend fun refreshChatActiveStories(channels: List<dev.tgfeed.core.td.ChannelBrief>): Int {
        chatStoriesRefreshMutex.withLock {
            if (System.currentTimeMillis() - chatStoriesRefreshAtMs < CHAT_STORIES_REFRESH_TTL_MS) {
                return _chatActiveStories.value.size
            }
            if (channels.isEmpty()) return 0
            runCatching {
                awaitAuthorized()
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val sem = Semaphore(CHAT_STORIES_REFRESH_CONCURRENCY)
                    // 先攒本轮结果再原子换表：逐频道边拉边写会让 StateFlow 抖一串中间态
                    val fetched = java.util.concurrent.ConcurrentHashMap<Long, TdApi.ChatActiveStories>()
                    kotlinx.coroutines.coroutineScope {
                        channels.forEach { ch ->
                            launch {
                                // 单频道 5s 超时：快拍查询正常毫秒级返回，但 TDLib 请求队列
                                // 拥塞时（首拉/媒体窗在跑）会排队数十秒——不设超时会把整轮
                                // 快照（以及后续轮次卡在互斥闸上的调用）一起拖死（模拟器
                                // 596 频道账号实测实锤：无超时版本整轮挂死无日志）
                                val s = runCatching {
                                    sem.withPermit {
                                        withTimeout(CHAT_STORIES_PER_CALL_TIMEOUT_MS) {
                                            client.send(TdApi.GetChatActiveStories(ch.id))
                                        }
                                    }
                                }.getOrNull() ?: return@launch
                                if (s.stories.isNotEmpty()) fetched[ch.id] = s
                            }
                        }
                    }
                    // 输入频道全部重判（story 已空/查询失败即摘键，防旧数据钉在条上）；
                    // 不在本次输入里的频道（更新流先进来的）保留
                    val inputIds = channels.mapTo(HashSet()) { it.id }
                    _chatActiveStories.value =
                        (_chatActiveStories.value.filterKeys { it !in inputIds }) + fetched
                }
            }.onFailure {
                Log.w(TAG, "快拍条全量刷新失败（保留旧快照继续展示）: ${it.message}")
                return _chatActiveStories.value.size
            }
            chatStoriesRefreshAtMs = System.currentTimeMillis()
            val n = _chatActiveStories.value.size
            Log.i(TAG, "快拍条全量刷新：${channels.size} 频道中 $n 个有活跃快拍（refreshChatActiveStories）")
            return n
        }
    }

    /**
     * 快拍条渲染单元快照：按 TDLib order 降序（与官方快拍列表同序；order 同值兜底按最新
     * 发布时间降序）。标题不在此投影（UI 侧走 [peekChatTitle] 零网络快照）。
     */
    fun chatStoriesBriefs(): List<ChannelStoriesBrief> =
        _chatActiveStories.value.values
            .filter { it.stories.isNotEmpty() }
            .map { s ->
                ChannelStoriesBrief(
                    chatId = s.chatId,
                    storyIds = s.stories.map { it.storyId }.sorted(),
                    maxReadStoryId = s.maxReadStoryId,
                    order = s.order,
                    latestDateSec = s.stories.maxOf { it.date },
                )
            }
            .sortedWith(
                compareByDescending<ChannelStoriesBrief> { it.order }
                    .thenByDescending { it.latestDateSec },
            )

    /**
     * 取单条快拍详情（沉浸连播页用：caption / 内容 / 时长都在 Story 里）。
     * 签名已 javap 实证：GetStory(long storyPosterChatId, int storyId, boolean onlyLocal) -> Story
     * Story{id: int, posterChatId: long, date: int, content: StoryContent, caption: FormattedText, …}
     * 失败/超时返回 null（连播页按条目级错误态呈现，不崩链）。
     */
    suspend fun loadStory(storyPosterChatId: Long, storyId: Int): TdApi.Story? = runCatching {
        awaitAuthorized()
        withTimeout(MSG_COVER_TIMEOUT_MS) {
            client.send(TdApi.GetStory(storyPosterChatId, storyId, false))
        }
    }.onFailure {
        Log.w(TAG, "loadStory 失败 chatId=$storyPosterChatId storyId=$storyId: ${it.message}")
    }.getOrNull()

    /**
     * 快拍图片本地路径（StoryContentPhoto → Photo.sizes 浏览档同步下载）：
     * 复用 [pickPhotoViewSize]（≈1280px 档，全屏浏览的清晰度/体积平衡点）与
     * loadPhotoViewPath 同口径——priority=32（正在看的不排队尾）+ 15s 超时 + EISDIR 防御。
     * 路径原样返回（decodeFile 直开文件，免疫 '#'/'?' 截断坑）。
     */
    suspend fun loadStoryPhotoPath(content: TdApi.StoryContent): String? {
        val photo = (content as? TdApi.StoryContentPhoto)?.photo ?: return null
        val size = pickPhotoViewSize(photo.sizes) ?: return null
        return runCatching {
            awaitAuthorized()
            // GetStory 带回的 File.local 已声明下载完成且是真实文件时直出路径
            // （含 EISDIR 防御），省一次 GetFile 往返；未完成才走查询+同步下载
            var f = size.photo
            if (!f.local.isDownloadingCompleted || f.local.path.isEmpty() ||
                !java.io.File(f.local.path).isFile
            ) {
                f = client.send(TdApi.GetFile(size.photo.id))
                if (!f.local.isDownloadingCompleted || f.local.path.isEmpty()) {
                    f = withTimeout(MSG_COVER_TIMEOUT_MS) {
                        client.send(TdApi.DownloadFile(size.photo.id, 32, 0L, 0L, true))
                    }
                }
            }
            f.local.path.takeIf { it.isNotEmpty() && java.io.File(it).isFile }
        }.onFailure {
            Log.w(TAG, "loadStoryPhotoPath 失败: ${it.message}")
        }.getOrNull()
    }

    /**
     * 快拍视频起播地址（StoryContentVideo → StoryVideo.video）：
     * 与 ensurePlayable 同一条下载链——DownloadFile 已完成回本地路径（过
     * [playableUriOrStream] 收口防 '#'/'?'），未完成回流服务器边下边播（story 是
     * ≤60s 短竖片，流式首帧快）。javap 实证 StoryVideo{duration: double, width: int,
     * height: int, isAnimation: boolean, video: File}；低画质备选档 alternativeVideo
     * 本版不选（story 分辨率本就压过）。
     */
    suspend fun loadStoryVideoUri(content: TdApi.StoryContent): Pair<String, Double>? {
        val sv = (content as? TdApi.StoryContentVideo)?.video ?: return null
        return runCatching {
            awaitAuthorized()
            // GetStory 带回的 File.local 已声明完成且是真实文件时直出本地路径
            // （过 playableUriOrStream 收口），不发 DownloadFile；未完成才起下载链
            val already = sv.video.local.isDownloadingCompleted &&
                sv.video.local.path.isNotEmpty() &&
                runCatching { java.io.File(sv.video.local.path).isFile }.getOrDefault(false)
            val f = if (already) {
                sv.video
            } else {
                // R166 整下载收编：story 未完成不再发整载（≤60s 短片分段按需拉起帧更快），
                // 仅 GetFile 取新鲜状态（幂等本地查询，无下载副作用）
                runCatching { client.send(TdApi.GetFile(sv.video.id)) }.getOrNull() ?: sv.video
            }
            val p = f.local.path
            val uri = if (f.local.isDownloadingCompleted && p.isNotEmpty() &&
                runCatching { java.io.File(p).isFile }.getOrDefault(false)
            ) {
                playableUriOrStream(p, f.id)
            } else {
                streamServer.url(f.id)
            }
            uri to sv.duration
        }.onFailure {
            Log.w(TAG, "loadStoryVideoUri 失败: ${it.message}")
        }.getOrNull()
    }


}
