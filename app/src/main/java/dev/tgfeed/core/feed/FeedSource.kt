package dev.tgfeed.core.feed

import android.util.Log
import dev.tgfeed.core.store.LocalStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import dev.tgfeed.core.store.Subscription
import dev.tgfeed.core.store.WatchEvent
import dev.tgfeed.core.td.FeedPage
import dev.tgfeed.core.td.FeedRequest
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random

// ---------------- v2 频道权重（时间衰减版，共享给混排与拉取配额） ----------------

/** 排序链结构化日志 TAG（R79 隐式负反馈页摘要等） */
private const val TAG = "FeedSource"

/** 权重衰减常数 λ = 7 天（毫秒）：每条事件按 exp(-(now-ts)/λ) 加权 */
private const val DECAY_WINDOW_MS = 7L * 24 * 3600 * 1000

/** 参与权重计算的每频道最近事件条数 */
private const val WEIGHT_SAMPLE = 50

/**
 * 秒划走阈值（秒）：watchSec 低于此值视为 skip（秒划）事件。
 * R110 起为全模块唯一真源：ImplicitFeedback 账本、InterestProfile 节奏画像、
 * TasteRadar 口味证据、FeedViewModel 的 skip 记账/质量分档全部引用本常量
 * （此前四处镜像 + 三处硬编码字面量，改一处漏其余即口径分裂）。
 */
internal const val SKIP_FAST_SEC = 2

/** skip 事件的额外负反馈强度：等效观看比例记为 -SKIP_EXTRA_PENALTY（比"看了 0 秒"更狠） */
private const val SKIP_EXTRA_PENALTY = 0.5

/** 无有效数据频道的探索权重（7 天无事件 / 无事件的频道由调用方缺省到此值） */
internal const val EXPLORE_WEIGHT = 0.6

/** 频道权重下限：基础公式 0.3 + ratio×0.7 的自然下限（互动加分恒非负，不会击穿） */
private const val MIN_CHANNEL_WEIGHT = 0.3

/** 频道权重上限：基础权重最高 1.0 + 互动加分封顶 +1.0 = 2.0 */
private const val MAX_CHANNEL_WEIGHT = 2.0

/**
 * v2 频道权重表：weight = clamp(0.3 + 衰减加权平均观看比例 × 0.7 + 互动加分, 0.3, 2.0)。
 *
 * 与 v1 的差别：
 *  - 每条事件按 exp(-(now-ts)/[DECAY_WINDOW_MS]) 衰减加权，越新的观看行为越能代表当前口味；
 *  - skip 事件（watchSec < [SKIP_FAST_SEC]，秒划走）按观看比例 0 计，并额外倒扣
 *    [SKIP_EXTRA_PENALTY]——即分子贡献 decay × (-0.5)，比"看了 0 秒"的中性 0 更狠；
 *    （v2 落地取舍：LocalStore 冻结不能加 skipped 字段，skip 由 watchSec < [SKIP_FAST_SEC] 在读侧推导，
 *    老数据自动获得相同语义，无需迁移）
 *  - 频道最近一条事件距今超过 7 天（λ 之外）视为权重过期，回落探索权重 [EXPLORE_WEIGHT]；
 *  - 互动信号入权重（v3）：[interactionBoost] 是 FeedViewModel 侧累计并持久化的
 *    channelId → 加分表（点赞 +0.15 / 收藏 +0.3 / 下载 +0.5，单频道封顶 +1.0，取消互动对称回退），
 *    在基础权重（含过期回落的 [EXPLORE_WEIGHT]）之上叠加；只有互动信号、暂无观看事件的频道
 *    也按 [EXPLORE_WEIGHT] 打底再叠加，避免"只点赞没看过"的频道拿不到正反馈；
 *  - 平均观看比例 clamp 到 [0,1]，最终权重 clamp 到 [[MIN_CHANNEL_WEIGHT], [MAX_CHANNEL_WEIGHT]]。
 *
 * 落地取舍：LocalStore/AppPrefs 本轮冻结（不能给 watch_events 加 kind 字段、不能加 prefs 属性），
 * 互动信号不写 watch_events（写进去也无法与真完播区分），改由 FeedViewModel 维护加分表并
 * 自建独立 SharedPreferences 落盘，经本参数注入——权重函数仍是纯函数，可测试性不变。
 *
 * 供 WeightedAggregateFeedSource 混排打分与 FeedViewModel 注入 TdLibGateway 的
 * 拉取配额共用，保证"看到的排序"与"拉取的量"用同一份口味画像。
 */
internal fun decayedChannelWeights(
    events: List<WatchEvent>,
    nowMs: Long,
    interactionBoost: Map<Long, Double> = emptyMap(),
): Map<Long, Double> {
    if (events.isEmpty() && interactionBoost.isEmpty()) return emptyMap()
    val base = if (events.isEmpty()) {
        emptyMap()
    } else {
        events.groupBy { it.chatId }.mapValues { (_, list) ->
        val sample = list.take(WEIGHT_SAMPLE) // watch_events 时间倒序，取最近 N 条
        val latestTs = sample.maxOf { it.ts }
        if (latestTs <= 0L || nowMs - latestTs > DECAY_WINDOW_MS) {
            EXPLORE_WEIGHT // 7 天无事件：权重过期，回落探索档
        } else {
            var num = 0.0
            var den = 0.0
            for (e in sample) {
                if (e.ts <= 0L) continue // 无时间戳的脏数据不参与
                // 未来时间戳（改系统时间等）decay 会 >1，封顶 1 防止放大
                val decay = exp(-(nowMs - e.ts).toDouble() / DECAY_WINDOW_MS).coerceAtMost(1.0)
                den += decay
                num += if (e.watchSec < SKIP_FAST_SEC) {
                    decay * -SKIP_EXTRA_PENALTY // skip：0 秒起步再倒扣一半
                } else {
                    val ratio = if (e.durationSec <= 0) 0.0
                    else (e.watchSec.toDouble() / e.durationSec).coerceIn(0.0, 1.0)
                    decay * ratio
                }
            }
            val avg = if (den > 0.0) (num / den).coerceIn(0.0, 1.0) else 0.0
            0.3 + avg * 0.7
        }
    }
    }
    if (interactionBoost.isEmpty()) return base
    // 叠加互动加分：基础权重 + boost，clamp 到 [0.3, 2.0]；
    // boost 表里独有的频道（暂无观看事件）按探索权重打底再叠加
    val out = HashMap<Long, Double>(base.size + interactionBoost.size)
    for ((id, w) in base) {
        out[id] = (w + (interactionBoost[id] ?: 0.0)).coerceIn(MIN_CHANNEL_WEIGHT, MAX_CHANNEL_WEIGHT)
    }
    for ((id, b) in interactionBoost) {
        if (id !in base) out[id] = (EXPLORE_WEIGHT + b).coerceIn(MIN_CHANNEL_WEIGHT, MAX_CHANNEL_WEIGHT)
    }
    return out
}

/**
 * 一切皆 Feed：刷频道、精准搜索、收藏夹、订阅追更……
 * 都只是 FeedSource 的不同实现，UI 与播放层不关心内容来源。
 * 后续新玩法（SavedFeed、CollectionFeed 等）在此扩展。
 */
interface FeedSource {
    val request: FeedRequest
    suspend fun nextPage(limit: Int = 20): FeedPage
    fun reset()
}

open class GatewayFeedSource(
    private val gateway: TdGateway,
    override val request: FeedRequest,
) : FeedSource {
    private var cursor: String? = null
    private var exhausted = false

    override suspend fun nextPage(limit: Int): FeedPage {
        if (exhausted) return FeedPage(emptyList(), null)
        val page = gateway.fetchVideos(request, cursor, limit)
        cursor = page.nextCursor
        if (cursor == null) exhausted = true
        return page
    }

    override fun reset() {
        cursor = null
        exhausted = false
    }
}

/**
 * 刷单个频道（R183 开频道自愈版 + R184 限流三态化）。
 *
 * 背景（阶段C 真机实锤 9/9）：R183 发现页候选是 chatId-only——深索引走
 * SearchChatMessages 爬取**不建 TDLib 本地会话**（GatewayDiscovery 既有口径），
 * 而 GetChatHistory 读历史需要本地会话记录（公开频道需 SearchPublicChat 打开过），
 * 于是「索引有货、卡片推荐语全对」的频道点开却「Chat not found」死页。
 *
 * 自愈：构造时带 [openTitle]/[openUsername] 线索（发现页候选/频道 brief 天然持有），
 * nextPage 撞 Chat not found 时按线索解析建会话（[TdLibGateway.ensureChannelOpenable]
 * 三态：GetChat 快路→resolve-once 缓存→username/标题搜索，**只认 chatId 严格相等**
 * 防仿冒频道）成功后重试本页一次；无线索/非真网关/已自愈过 = 行为与旧版完全一致。
 *
 * R184 限流降级路径（[ChannelOpenHeal.RateLimited]）：
 *  - 抛 [SearchRateLimitedException]（消息即用户口径「搜索暂时被限流，稍后自动重试」，
 *    VM _error 面诚实展示——不再伪装成「Chat not found」/「频道不存在」）；
 *  - username 有线索时**入队后台重试**（[TdLibGateway.enqueueBackgroundResolve]）：
 *    名字进图谱 resolveQueue，EntityResolver 常驻管线与进发现页的懒解析会在解封后
 *    消化——SearchPublicChat 副作用=建会话+建 resolve-once 缓存，用户下次点击
 *    GetChat 直通零搜索；
 *  - healed 闸不置位（限流期重试自愈零成本：闸拒在发送前，不烧账号）。
 */
class ChannelFeedSource(
    private val gateway: TdGateway,
    val channelId: Long,
    private val openTitle: String? = null,
    private val openUsername: String? = null,
) : FeedSource {
    private val base = GatewayFeedSource(gateway, FeedRequest(channelId = channelId))
    override val request: FeedRequest get() = base.request
    private var healed = false

    override suspend fun nextPage(limit: Int): FeedPage {
        try {
            return base.nextPage(limit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val gw = gateway as? TdLibGateway ?: throw e
            val hasHint = !openTitle.isNullOrBlank() || !openUsername.isNullOrBlank()
            val chatNotFound = e.message?.contains("Chat not found", ignoreCase = true) == true
            if (healed || !hasHint || !chatNotFound) throw e
            val heal = runCatching {
                kotlinx.coroutines.withTimeout(CHANNEL_OPEN_HEAL_TIMEOUT_MS) {
                    gw.ensureChannelOpenable(channelId, openTitle.orEmpty(), openUsername)
                }
            }.getOrNull()
            when (heal) {
                is dev.tgfeed.core.td.ChannelOpenHeal.Success -> {
                    healed = true // 自愈成功：本源终身免再试（会话已建）
                    return base.nextPage(limit)
                }
                is dev.tgfeed.core.td.ChannelOpenHeal.RateLimited -> {
                    // 限流：诚实上抛 + 入队后台重试；healed 不置位（解禁后零成本重试自愈）
                    gw.enqueueBackgroundResolve(openUsername)
                    throw SearchRateLimitedException(
                        "搜索暂时被限流，稍后自动重试" +
                            if (heal.retryAfterSec > 0) "（限流约剩 ${humanBanRemain(heal.retryAfterSec)}）" else "",
                    )
                }
                else -> {
                    // NotFound / 超时(null)：线索确无法建会话——原异常上抛走既有错误态
                    healed = true // 防同源无限重试同一线索（旧语义保留）
                    throw e
                }
            }
        }
    }

    override fun reset() = base.reset()
}

/** R184 搜索限流异常：消息即 UI 直显口径（「加载失败：搜索暂时被限流，稍后自动重试」） */
class SearchRateLimitedException(message: String) : Exception(message)

/** 秒数 → 人话时长（小时/分钟），限流提示用 */
internal fun humanBanRemain(sec: Int): String = when {
    sec >= 3600 -> "${sec / 3600} 小时"
    sec >= 60 -> "${sec / 60} 分钟"
    else -> "${sec}秒"
}

/** 开频道自愈解析的兜底上限（1-3 次网络往返够用；超时=按失败上抛走既有错误态） */
internal const val CHANNEL_OPEN_HEAL_TIMEOUT_MS = 10_000L

/** 精准搜索播放：关键词命中即变成一个可刷的 Feed */
class SearchFeedSource(gateway: TdGateway, query: String, channelId: Long? = null) :
    FeedSource by GatewayFeedSource(gateway, FeedRequest(channelId = channelId, searchQuery = query))

/** 聚合全部频道 */
class AggregateFeedSource(gateway: TdGateway) :
    FeedSource by GatewayFeedSource(gateway, FeedRequest())

/**
 * 加权混排聚合 Feed（v2）：底层游标与并发聚合拉取能力完全复用 GatewayFeedSource
 * （内部持有，gateway.fetchVideos 的聚合并发在网关层完成，v2 起每频道配额按权重倾斜），
 * 每页拉回来后在本地做可见性过滤，然后交给 [rankWeightedPage] 的 P1 排序链：
 * 文件级去重 → 赛马分层 → 加权（含新鲜度）→ 滑动窗口打散 → 头部降权 → 探索注入
 * → 保底露头（R82），与媒体 tab 的页内排序完全同一条链（口径见 rankWeightedPage 注释）。
 *
 * 打分规则：
 *  - 频道权重 = [decayedChannelWeights]（时间衰减版，skip 事件额外负反馈，
 *    叠加 [boostProvider] 提供的互动加分表）；
 *    无任何数据的频道固定 [EXPLORE_WEIGHT]（探索倾斜，让没刷过的频道也有冒头机会）
 *  - 已看：[excludeSeenProvider] 开时**硬剔除**（双轨：item.key 精确命中 +
 *    fileUniqueId 内容指纹命中——同一视频被转发到多个频道时 key 不同、指纹相同，
 *    只按 key 判重会漏掉"换台又见同片"）；关时回落旧行为，仅 ×[SEEN_PENALTY] 软降权
 *  - 长视频（durationSec > [LONG_VIDEO_SEC]）×[LONG_VIDEO_PENALTY]
 *  - 新鲜度分档衰减（[freshnessFactor]，7/30/90 天档位）
 *  - 赛马分层乘区（晋级 ×1.4 / 连败出局 ×0.7，ExposureTracker 口径）
 *  - R79 隐式负反馈乘区：秒划计数衰减账本（ImplicitFeedback）按频道/频道×类型
 *    降权（单次秒划不动、多次升级、有效观看恢复、时间衰减，降权不消失）
 *  - 放置层硬约束：任意连续 5 条同频道 ≤2、单频道页内占比 ≤25%、~10% 探索位
 *    （P2-B 起探索位池内候选由 LinUCB 选臂：上下文=频道权重/新鲜度/已看/内容类型/
 *    曝光疲劳，奖励=曝光后互动/近完读，α 随数据量收缩防震荡，propensity 落盘
 *    linucb_propensity.log 作反事实评估底座；实现见 LinUCB.kt）
 *
 * 排序用"按权重不放回加权随机抽样"打底，保留随机性防止刷感死板；硬约束在放置层收敛。
 *
 * [excludeSeenProvider] 默认 { true }（产品口径：已看内容默认不再出现）：
 * FeedViewModel 两处构造与分屏自持源（绕过 FeedViewModel.loadMore 的过滤链，
 * 此前已看内容在分屏直通）都显式接"已看内容不再出现"设置开关（关掉恢复软降权旧行为）；
 * 默认值只兜未来遗漏接线的新构造点（已看判重底账 loadSeenIdentities
 * 命中内存缓存，空历史时零开销直通）。
 *
 * [extraSeenProvider]：调用方补充的会话级已看双轨（key + fileUniqueId），
 * 与磁盘底账并集剔除。分屏专用——分屏自动轮播/换片量太大，展示记录不写
 * seen_ids.log（会冲垮 Feed 侧判重底账），改由 GridScreen 经 FeedViewModel
 * 以会话内存集补充（不落盘、进程重启清零）；默认空集 = 零开销直通。
 */
class WeightedAggregateFeedSource(
    gateway: TdGateway,
    private val store: LocalStore,
    private val boostProvider: () -> Map<Long, Double> = { emptyMap() },
    private val excludeSeenProvider: () -> Boolean = { true },
    private val extraSeenProvider: () -> Pair<Set<String>, Set<String>> = {
        emptySet<String>() to emptySet()
    },
) : FeedSource {
    override val request: FeedRequest = FeedRequest()

    /** 游标状态全部交给内部的 GatewayFeedSource 管理 */
    private val inner = GatewayFeedSource(gateway, FeedRequest())

    init {
        // ALGO P1：注册赛马曝光追踪器（进程级单例，供签名冻结的 rankWeightedPage 取用；
        // FeedViewModel 默认源恒构造本类，视频 tab 的网关直拉路径也因此拿到 tracker）。
        // 探索"新频道"半区读 DiscoveryGraph 近 7 天 firstSeen 的新节点（图谱候选口径），
        // Mock/测试网关无图谱 → 空集，探索位全部回落给零画像冷门频道
        ExposureTrackerHolder.register(store) {
            val g = (gateway as? TdLibGateway)?.discoveryGraph ?: return@register emptySet()
            val nowMs = System.currentTimeMillis()
            g.topCandidates(NEW_CHANNEL_SCAN_LIMIT)
                .filter { it.firstSeen > 0L && nowMs - it.firstSeen < NEW_CHANNEL_WINDOW_MS }
                .mapTo(HashSet()) { it.chatId }
        }
        // ALGO P2-B：注册 LinUCB 探索选臂器（进程级单例，供签名冻结的 rankWeightedPage 取用）。
        // 与赛马正交：赛马管"新内容冷启动晋级/出局"，LinUCB 管"探索位该把流量给谁"。
        // store 目录经 LocalStore.storageDir 正式访问口取得（R123 起，反射桥已拆）
        LinUCBHolder.register(store)
        // 频道级曝光疲劳（进程级单例，与 ExposureTrackerHolder 同款挂接）：会话内存态，
        // 不落盘、重启清零；rankWeightedPage 排序分乘 max(0.3, 0.9^n)（n=该频道会话曝光数）
        ChannelFatigueHolder.register()
    }

    override suspend fun nextPage(limit: Int): FeedPage {
        val page = inner.nextPage(limit)
        if (page.items.isEmpty()) return page

        // 1. 过滤被屏蔽频道 + "不感兴趣"单条 + 永久屏蔽内容指纹（ViewModel 层还会再过滤一次，
        //    双保险；整页都被过滤时返回空 items + 原游标，下次 loadMore 继续往后翻）
        val blocked = store.loadBlockedChannels()
        val disliked = store.loadDisliked()
        // 永久屏蔽（≥2 次不感兴趣的内容指纹）：按 fileUniqueId 滤——同一视频被转发到
        // 别的频道（消息 id/key 不同）也屏蔽；无指纹的老数据/Mock 条目回落按 key 滤
        val permBlocked = store.loadBlockedItems()
        val permBlockedKeys = permBlocked.mapTo(HashSet()) { it.key }
        val permBlockedUids = permBlocked.mapNotNullTo(HashSet()) { b ->
            b.fileUniqueId.takeIf { it.isNotEmpty() }
        }
        val visible = page.items.filter {
            it.chatId !in blocked && it.key !in disliked &&
                !SeenDedupPolicy.isSeen(it, permBlockedKeys, permBlockedUids)
        }
        if (visible.isEmpty()) return FeedPage(emptyList(), page.nextCursor)

        // 1.5 已看硬剔除（"已看内容不再出现"，[excludeSeenProvider] 开时）：
        // 双轨判重——item.key 精确命中 或 内容指纹 fileUniqueId 命中（跨频道转发同片
        // 也算看过）都算已看；底账是 LocalStore.loadSeenIdentities（seen_ids.log，
        // 容量 100000，不受观看历史 500 条上限淘汰影响）。关时跳过本段，已看条目进入
        // 排序链吃 SEEN_PENALTY 软降权（旧行为，给想重温的人留门——R337：唯一解除
        // 条件=用户开关，判定收口 SeenDedupPolicy）。
        // 注意整页滤空时返回空 items + 原游标，与上方可见性过滤同口径（调用方续拉下一页）
        val unseen = if (excludeSeenProvider()) {
            val (seenKeys0, seenUids0) = store.loadSeenIdentities()
            // 会话级已看并入（分屏已展示双轨；默认空集零开销）
            val (exK, exU) = extraSeenProvider()
            if (seenKeys0.isEmpty() && seenUids0.isEmpty() && exK.isEmpty() && exU.isEmpty()) {
                visible
            } else {
                // 双账并查（零合并分配）：任一账命中即已看
                visible.filter {
                    !SeenDedupPolicy.isSeen(it, seenKeys0, seenUids0) &&
                        !SeenDedupPolicy.isSeen(it, exK, exU)
                }
            }
        } else {
            visible
        }
        if (unseen.isEmpty()) return FeedPage(emptyList(), page.nextCursor)

        // 2. 计算频道权重（v2 时间衰减版 + 互动加分，与拉取配额同一份画像）+ 已看集合
        val channelWeights = decayedChannelWeights(
            store.loadWatchEvents(), System.currentTimeMillis(), boostProvider(),
        )
        // SEEN_PENALTY 软降权的 key 集：已看硬剔除开时池内已无已看（传空集即可），
        // 关时用判重底账 ∪ 会话级已看的 key 集（覆盖比 500 条历史更全），
        // 维持"降权但不消失"旧语义
        val seenKeys = if (excludeSeenProvider()) {
            emptySet()
        } else {
            store.loadSeenIdentities().first + extraSeenProvider().first
        }

        // 3. P1 起混排排序链与媒体 tab 完全同一条：rankWeightedPage（文件级去重 →
        //    赛马分层 → 加权（含新鲜度）→ 滑动窗口打散 → 头部降权 → 探索注入），
        //    实现与参数口径见文件下方
        return FeedPage(rankWeightedPage(unseen, channelWeights, seenKeys), page.nextCursor)
    }

    override fun reset() = inner.reset()
}

// ---------------- v2/P1 条目级排序机器（「全部」tab 混排与媒体 tab 页内排序共用） ----------------
// 以下函数与常量原先是 WeightedAggregateFeedSource 的私有成员；P0-1 为了给"绕过 FeedSource
// 的网关直拉页"（fetchAggregatedMediaAll，默认视频 tab 路径）接上同一套排序，提取为文件级
// 函数供 rankWeightedPage 复用；P1 把单条链扩展为六段：
//   去重 → 赛马分层 → 加权（含新鲜度）→ 滑动窗口打散 → 头部降权 → 探索注入。

/** 与上一条同频道时的惩罚系数（weightedShuffle 软约束） */
private const val SAME_CHANNEL_PENALTY = 0.3

/** 已看条目的惩罚系数 */
private const val SEEN_PENALTY = 0.2

/** 长视频阈值（秒）：超过则权重打折 */
private const val LONG_VIDEO_SEC = 20 * 60

/** 长视频惩罚系数 */
private const val LONG_VIDEO_PENALTY = 0.5

// ---- ALGO P1：新鲜度衰减（半衰式档位，乘区作用在 weightedShuffle 的条目权重上） ----
// dateEpochSec 距今 ≤7 天 ×1.0；7–30 天 ×0.85；30–90 天 ×0.70；>90 天 ×0.60；
// dateEpochSec=0 的老数据（老收藏/Mock）无法判定年龄，按 ×0.80 中性处理（介于 30–90 档
// 与 7–30 档之间，既不拔高也不埋掉）；未来时间戳（改系统时间等）按 ≤7 天 ×1.0 处理。
private const val FRESH_T1_MS = 7L * 24 * 3600 * 1000   // 新鲜档一：7 天
private const val FRESH_T2_MS = 30L * 24 * 3600 * 1000  // 新鲜档二：30 天
private const val FRESH_T3_MS = 90L * 24 * 3600 * 1000  // 新鲜档三：90 天
private const val FRESH_W1 = 1.0   // ≤7 天
private const val FRESH_W2 = 0.85  // 7–30 天
private const val FRESH_W3 = 0.70  // 30–90 天
private const val FRESH_W4 = 0.60  // >90 天
private const val FRESH_UNKNOWN = 0.80 // dateEpochSec=0 的老数据中性档

// ---- ALGO P1：流量池赛马（小流量池位置与乘区；分层判定在 ExposureTracker） ----
/** 小流量池中段位区间：页内 [35%, 75%] 的位置优先安放零曝光新内容（不占头部、不沉底） */
private const val NEW_BAND_START = 0.35
private const val NEW_BAND_END = 0.75

/** 小流量池单页上座上限：新内容最多占页大小的 1/4（至少 2 条），防全新页把中段挤满 */
private const val NEW_BAND_MAX_RATIO = 0.25

// ---- ALGO P1：同频道滑动窗口硬约束（替代旧的"相邻不连/不三连"后处理） ----
// R176 起 internal：关注流公平轮转（FollowingFairSupply.interleaveFollowingRound）
// 继承同一组放置层硬约束——数字单一定义，一处改全局生效（类问题收敛纪律）。
/** 窗口宽度：任意连续 [WINDOW] 条内同频道 ≤ [WINDOW_MAX] */
internal const val WINDOW = 5
internal const val WINDOW_MAX = 2

// ---- ALGO P1：头部降权（单频道页内占比硬上限，超出条目排到页尾——
//      即"下一屏/下一轮拉取之后才轮到"，等价于调研口径的"排到下一页"） ----
/** 单频道页内占比上限 25%（快手基尼系数思路：重排层硬约束防头部通吃） */
internal const val SHARE_CAP_RATIO = 0.25

/** 占比下限条数：小页（<16 条）按比例算太紧，至少允许 4 条同频道 */
internal const val SHARE_CAP_MIN = 4

// ---- ALGO P1：探索配额（防马太效应：轮盘赌之外的保底位） ----
/** 每 [EXPLORE_EVERY] 个位置保底 1 格探索位（≈10% 页内位置） */
private const val EXPLORE_EVERY = 10

// ---- ALGO P2-B：LinUCB 探索选臂（实现见 LinUCB.kt，特征装配在 placeWithDiversity 内） ----
/** 曝光疲劳特征的归一化分母 ln(11)：页级曝光 ≥10 次时疲劳特征封顶 1.0 */
private val LN_11 = ln(11.0)

// ---- 频道级曝光疲劳（修"活跃频道连续霸屏"：条目有赛马 0.85ⁿ 口径的疲劳，频道此前没有） ----
/** 频道疲劳底数：会话内该频道每多曝光 1 条，后续页该频道条目排序权重 ×0.9 */
private const val CHANNEL_FATIGUE_BASE = 0.9

/** 频道疲劳地板：衰减系数不低于 0.3——同一频道不能连续霸屏，但永远有机会冒头 */
private const val CHANNEL_FATIGUE_FLOOR = 0.3

// ---- ALGO P1：探索"新频道"半区的图谱读取口径（DiscoveryGraph，不引新依赖） ----
/** 新频道窗口：firstSeen 在 7 天内的图谱节点视为"新发现的新频道" */
private const val NEW_CHANNEL_WINDOW_MS = 7L * 24 * 3600 * 1000

/** 图谱候选扫描上限：topCandidates 的截断名次数（足够覆盖近期新节点） */
private const val NEW_CHANNEL_SCAN_LIMIT = 100

// ---- R82 保底频道露头（修"保底进池但沉底"：采样层 R75 保底车道把长尾频道抽进了池， ----
//      但排序层按权重排，低权重保底频道进池后也沉底，用户前几屏永远刷不到——进池≠露头） ----
/** 网关保底车道坑位口径镜像（TdLibGateway 私有常量，VM 复算本轮保底名册用同一份口径） */
internal const val GUARANTEED_POOL_SLOTS = 8

/** 网关聚合池容量口径镜像（池子不满 = 全量直取无轮换，VM 据此判定本轮有无保底车道） */
internal const val AGGREGATE_POOL_CAPACITY = 24

/** 头部露头窗口：排序页前 20 条 ≈ 用户前 1-2 屏（保底露头只在这个窗口内计较） */
private const val GUARANTEED_HEAD_WINDOW = 20

/** 位次窗口：前 10 条。头部窗口内的保底条目若全压在此线之外，换位提到前段 */
private const val GUARANTEED_FRONT_WINDOW = 10

/** 每轮保底频道在头部窗口的露头配额（条）：露头够了就不再前置，防整轮刷成生面孔 */
private const val GUARANTEED_SURFACE_QUOTA = 3

/** 露头加权：保底频道条目在加权抽样的排序分乘区（温和上浮，只作乘区不动放置层硬约束） */
private const val GUARANTEED_WEIGHT_BOOST = 1.6

/**
 * 媒体 tab 页内加权排序（P0-1 接线、P1 扩展，供 FeedViewModel.loadMore 调用）。
 *
 * 背景：视频/图文/音频单 tab 的聚合拉取走 TdLibGateway.fetchAggregatedMediaAll，
 * 其产出是纯 shuffled() 随机序（网关公共方法，口径不能就地改）；v2 排序机器此前只在
 * 「全部」tab 的 WeightedAggregateFeedSource.nextPage 里生效，默认路径上是死代码。
 * 本函数把排序机器搬给"网关直拉页"用，P1 起为六段一条链（两路径完全同一口径）：
 *
 *   ① 文件级去重（R126 重发折叠）：[dedupeByFile]，同内容身份只留一条（权重高者胜）——
 *      硬判据 fileUniqueId（TG 服务端级文件身份证，跨频道搬运同指纹），
 *      无指纹时 caption+时长软判据兜底（判据口径见 [contentFoldId]）；
 *   ② 赛马分层：[ExposureTracker] 按 item.key 的曝光/互动/近完读把条目分
 *      NEW/GRADUATED/STALLED/NORMAL 四档——零曝光新内容进"小流量池"（页内中段位），
 *      达标（有互动或近完读）晋级 ×[ExposureTracker.PROMOTE_BOOST]，连败出局
 *      ×[ExposureTracker.STALLED_PENALTY]（tracker 未注册时本段整体跳过）；
 *   ③ 加权随机重排：[weightedShuffle]，频道权重 × 新鲜度档位 × 赛马乘区 ×
 *      R79 隐式负反馈乘区（秒划计数衰减 → 频道/频道×类型降权，只作乘区不动
 *      放置层硬约束）× R298 兴趣乘区（tag 轴 (1+α·tagMatch) × 类型轴偏好系数，
 *      页头快照一次算成 key→乘子表，冷启动空表直通）× 同频道/已看/长视频惩罚，
 *      轮盘赌不放回抽样——**随机性保留为同分段内的抖动（防刷感死板），
 *      兴趣分首次决定抽样期望**（design-recommendation.md §4.2 选项甲）；
 *   ④ 滑动窗口打散：[placeWithDiversity]，任意连续 [WINDOW] 条内同频道 ≤ [WINDOW_MAX]
 *      （硬约束，替代旧的"相邻不连"后处理）；
 *   ⑤ 头部降权：同处放置层，单频道页内占比硬上限 [SHARE_CAP_RATIO]（≥[SHARE_CAP_MIN] 条），
 *      超出条目沉到页尾（等价"排到下一页"）；
 *   ⑥ 探索注入：每 [EXPLORE_EVERY] 格保底 1 个探索位（≈10%），一半给零画像/低权重
 *      冷门频道，一半给"近 7 天 firstSeen 的图谱新节点"（新发现的新频道），防马太效应；
 *      P2-B 起池内候选由 [LinUCB] 选臂（上下文=频道权重/新鲜度/已看/内容类型/曝光疲劳，
 *      奖励=曝光后互动/近完读，softmax+均匀混合输出 propensity 记账），bandit 未注册
 *      时回退"主序首条"旧行为。
 *   ⑦ 保底露头（R82）：本轮采样层保底车道的频道（[GuaranteedPoolHolder] 名册）
 *      在加权抽样吃 [GUARANTEED_WEIGHT_BOOST] 温和上浮，放置后再按每轮
 *      [GUARANTEED_SURFACE_QUOTA] 条配额换入头部窗口（前 [GUARANTEED_HEAD_WINDOW] 条，
 *      换位镜像滑动窗口约束）——修"保底进池但沉底"，名册空集时本段零开销直通。
 *
 * 打分口径：[channelWeights] 由调用方用 [decayedChannelWeights]（时间衰减权重 +
 * 互动加分表）算好传入，[seenKeys] 为已看条目 key 集（SEEN_PENALTY 软降权用；
 * 调用方已硬过滤已看时传空集即可）。
 *
 * 注意排序是页内的：每页（~100 条）内部"常看/高互动频道靠前"，跨页先后仍由
 * 网关窗口轮转的拉取次序决定——网关决定候选集，这里决定用户先看到谁。
 * 副作用：输出页被记为一次曝光（ExposureTracker.recordImpressions，赛马输入数据）。
 */
internal fun rankWeightedPage(
    items: List<VideoItem>,
    channelWeights: Map<Long, Double>,
    seenKeys: Set<String>,
): List<VideoItem> {
    if (items.size < 2) return items
    // ① 文件级去重（R126：硬判据 fileUniqueId + 无指纹时 caption+时长软判据兜底）
    val deduped = dedupeByFile(items, channelWeights)
    val pageFolded = items.size - deduped.size
    if (pageFolded > 0) {
        Log.d(TAG, "页内折叠重复 $pageFolded 条（同片多源，留权重高者）")
    }
    if (deduped.size < 2) return deduped

    // ② 赛马分层：四档判定 + 晋级/出局乘区（tracker 未注册时全部按 NORMAL，链退化为无赛马）
    val tracker = ExposureTrackerHolder.current()
    val nowMs = System.currentTimeMillis()
    // P2-B：LinUCB 探索选臂器（未注册时为 null，探索位回退"主序首条"旧行为）。
    // 排本页之前先结算上一周期探索位的延迟奖励，让最新学习结果立刻作用于本页选臂。
    // R79 全信号奖励合成：既有正信号（互动 1.0/近完读 0.6，与赛马达标同口径）与
    // 条目级观看结局（ImplicitFeedback.itemReward：秒划 -0.4、观看比例 0.2/0.4/0.6
    // 分档、回看 +0.3）取高；秒划负奖励立即结算不等窗；无任何记录返回 null 继续等窗
    val bandit = LinUCBHolder.current()
    if (tracker != null) {
        bandit?.settle({ k, d, ts ->
            val pos = tracker.positiveReward(k, d)
            val imp = ImplicitFeedbackHolder.current()?.itemReward(k, ts)
            when {
                imp == null -> pos.takeIf { it > 0.0 } // 无结算记录：维持旧口径（有正信号即结）
                imp < 0.0 -> imp                       // 秒划：确定的负结局，立即结算
                else -> maxOf(pos, imp)                // 观看结局与既有正信号取高（回看加成含在 imp）
            }
        }, nowMs)
    }
    val tierByKey = HashMap<String, ExposureTracker.Tier>(deduped.size)
    val raceBoost = HashMap<String, Double>()
    if (tracker != null) {
        for (it in deduped) {
            val tier = tracker.tierOf(it)
            tierByKey[it.key] = tier
            when (tier) {
                ExposureTracker.Tier.GRADUATED -> raceBoost[it.key] = ExposureTracker.PROMOTE_BOOST
                ExposureTracker.Tier.STALLED -> raceBoost[it.key] = ExposureTracker.STALLED_PENALTY
                else -> Unit // NEW/NORMAL 不加不减：NEW 的位置由放置层小流量池段位保证
            }
        }
    }

    // ③ 加权随机重排（频道权重 × 新鲜度 × 赛马乘区 × 频道疲劳 × 隐式负反馈 × 软惩罚）。
    // 频道级曝光疲劳：会话内该频道已曝光 n 条 → 本页该频道条目权重 ×max(0.3, 0.9ⁿ)，
    // 同一频道不能连续霸屏但永远有机会冒头；tracker 未注册（单测等）时空表直通
    val fatigue = ChannelFatigueHolder.current()
    val fatigueFactors = fatigue?.factorMap(deduped.map { it.chatId }.distinct()).orEmpty()
    // R79 隐式负反馈乘区：秒划计数衰减账本 → 频道/频道×类型降权乘子快照（页头取一次）。
    // 只作排序乘区——窗口/占比/探索配额三条多样性硬约束在放置层原样生效，
    // 被降权频道沉后但不消失，R75 保底配额吃不掉；账本未注册（单测等）时空表直通
    val implicitFactors = ImplicitFeedbackHolder.current()
        ?.factorSnapshot(deduped.map { it.chatId }.distinct()).orEmpty()
    if (implicitFactors.isNotEmpty()) {
        val strongest = implicitFactors.values.minOf { fa -> fa[0] }
        Log.d(
            TAG,
            "隐式负反馈降权: 本页 ${implicitFactors.size} 个频道受影响（最强 ×%.2f）".format(strongest),
        )
    }
    // R298 兴趣乘区（真推荐引擎排序主信号升格，design-recommendation.md §4.1 选项甲）：
    // 条目乘子 = (1 + α·tagMatch)（T 轴：候选 tag 集 × 兴趣 tag 轴快照，负轴参与）
    //          × 类型系数（M 轴偏好）——页头取一次账本快照算成 item.key→乘子表，
    // weightedShuffle 查表 O(1)；账本未注册/全无信号（冷启动）= 空表直通，
    // 排序链逐字节退回现状（零退化的实现闸）。兴趣分只作乘区——滑窗/占比/探索位
    // 三条多样性硬约束在放置层最后执行，不可被击穿。
    val tagLedger = TagInterestHolder.current()
    val interestByKey: Map<String, Double> = if (tagLedger == null || !tagLedger.hasAnySignal()) {
        emptyMap()
    } else {
        val axis = tagLedger.tagAxisSnapshot()
        val typeFactors = tagLedger.typeFactorSnapshot()
        val m = HashMap<String, Double>(deduped.size)
        for (it in deduped) {
            m[it.key] = interestMultiplier(foldTagsOf(it), axis, typeFactors, it.mediaType)
        }
        m
    }
    // R82 本轮保底名册（GuaranteedPoolHolder 进程级挂接；空集 = 未跑聚合轮/Mock 网关，
    // 露头加权与前置全段直通）——页头取一次，加权乘区与放置后前置共用同一份
    val guaranteedIds = GuaranteedPoolHolder.current()
    val shuffled = weightedShuffle(
        deduped, channelWeights, seenKeys, raceBoost, nowMs, fatigueFactors, implicitFactors,
        guaranteedIds, interestByKey,
    )

    // ④⑤⑥ 滑动窗口打散 + 头部降权 + 探索注入（单遍贪心放置，见 placeWithDiversity；
    //    P2-B 起探索位候选由 LinUCB 选臂，bandit 为 null 时回退"主序首条"旧行为）
    val placed = placeWithDiversity(
        shuffled, channelWeights, tierByKey, tracker?.freshChannelIds() ?: emptySet(),
        seenKeys, tracker, bandit, nowMs,
    )

    // ⑦ R82 保底露头前置：保底频道权重低，单靠加权主序仍可能整轮沉底（实测首轮
    //    保底条目最前位次 15/17）。位次保底（页内保底条目全压前 10 条之外时换位
    //    提前）+ 配额保底（头 20 条保底条目补足到配额，每轮 [GUARANTEED_SURFACE_QUOTA]
    //    条封顶，自然露头同占名额），换位镜像滑动窗口硬约束；随后打一行露头观测日志
    val result = if (guaranteedIds.isEmpty()) {
        placed
    } else {
        val budget = GuaranteedPoolHolder.budget()
        val (surfaced, swaps, headCount) = surfaceGuaranteedHead(placed, guaranteedIds, budget)
        GuaranteedPoolHolder.consumeBudget(minOf(headCount, budget))
        logGuaranteedSurfacing(surfaced, guaranteedIds, swaps)
        surfaced
    }

    // 曝光记账：进入排序结果页即记一次页级曝光（赛马机制的输入数据；tracker 缺省时跳过）
    tracker?.recordImpressions(result.map { it.key })
    // 频道疲劳记账：页内各频道条目数累计进会话曝光数，作用于后续页的排序权重
    fatigue?.recordPage(result)
    return result
}

/**
 * 多样性贪心放置（P1：滑动窗口打散 + 头部降权 + 探索注入，单遍完成，O(n²)，页 ~100 条无压力）：
 *
 * 输入 [shuffled] 是加权抽样主序。先把零曝光新内容（tier=NEW）拆进小流量池队列
 * （最多页大小 × [NEW_BAND_MAX_RATIO] 条，超出部分落回主序按正常权重参与），
 * 然后逐位放置，每位的选取优先级：
 *  1. 探索槽位（每 [EXPLORE_EVERY] 格）：先"新发现的新频道"（[newChannelIds]，近 7 天
 *     图谱新节点）后半配额，再"零画像/低权重冷门频道"（频道权重 ≤ [EXPLORE_WEIGHT]）
 *     前半配额；一侧找不到候选时互相兜底，不浪费探索格。
 *     P2-B：半区内有多个候选时由 [bandit]（LinUCB）选臂并记 propensity；
 *     [bandit] 为 null 或半区唯一候选时回退"主序首条"旧行为；
 *  2. 小流量池段位（页内 [NEW_BAND_START]–[NEW_BAND_END]）：优先安放新内容；
 *  3. 主序第一条同时满足双硬约束（窗口 + 占比）的条目；
 *  4. 放宽降级：占比不可满足（如整页同频道）→ 只守窗口；窗口也不可达 → 硬放主序/
 *     小流量池队首，宁违反约束不丢条目。
 *
 * 约束判定：
 *  - 窗口：已放序列末尾 [WINDOW]-1 条中同频道数 < [WINDOW_MAX]（此前窗口已合规，
 *    只需检查新放入条目结尾的那个窗口）；
 *  - 占比：counts[chatId] < shareCap（shareCap = max([SHARE_CAP_MIN], ⌈n×[SHARE_CAP_RATIO]⌉)）。
 * 超占比条目不会被丢弃，而是自然沉到页尾——等价于"排到下一页"。
 */
private fun placeWithDiversity(
    shuffled: List<VideoItem>,
    channelWeights: Map<Long, Double>,
    tierByKey: Map<String, ExposureTracker.Tier>,
    newChannelIds: Set<Long>,
    // ---- P2-B LinUCB 选臂所需上下文（bandit=null 时全部为死参数，行为与旧版一致） ----
    seenKeys: Set<String>,      // 已看 key 集（特征 [3]；调用方已硬剔除已看时为空集）
    tracker: ExposureTracker?,  // 曝光疲劳特征 [7] 的数据源（null 时按 0 次曝光计）
    bandit: LinUCB?,            // null = 未注册（单测等），探索位回退"主序首条"
    nowMs: Long,                // 基准时刻（新鲜度特征与 bandit 记账共用）
): List<VideoItem> {
    val n = shuffled.size
    // 拆小流量池：零曝光新内容（加权序保持），超出上座上限的落回主序
    val newCap = maxOf(2, (n * NEW_BAND_MAX_RATIO).toInt())
    val midQueue = ArrayList<VideoItem>()
    val main = ArrayList<VideoItem>(n)
    for (it in shuffled) {
        if (tierByKey[it.key] == ExposureTracker.Tier.NEW && midQueue.size < newCap) {
            midQueue += it
        } else {
            main += it
        }
    }
    // 探索双池候选：冷门 = 频道权重 ≤ 探索档（零画像缺省 0.6 也在内）；新频道 = 图谱近 7 天新节点
    val coldKeys = HashSet<String>()
    for (it in main) {
        if ((channelWeights[it.chatId] ?: EXPLORE_WEIGHT) <= EXPLORE_WEIGHT + 1e-9) {
            coldKeys += it.key
        }
    }
    val exploreQuota = maxOf(1, n / EXPLORE_EVERY)
    var exploreNewLeft = exploreQuota / 2          // 一半给新发现的新频道
    var exploreColdLeft = exploreQuota - exploreNewLeft // 一半给零画像冷门
    var exploreUsed = 0                            // 总配额闸：严格 ≤ exploreQuota（≈10%）
    val shareCap = maxOf(SHARE_CAP_MIN, kotlin.math.ceil(n * SHARE_CAP_RATIO).toInt())
    val bandStart = (n * NEW_BAND_START).toInt()
    val bandEnd = (n * NEW_BAND_END).toInt()

    val out = ArrayList<VideoItem>(n)
    val counts = HashMap<Long, Int>()

    fun windowOk(chatId: Long): Boolean {
        var c = 0
        var i = out.size - 1
        val lo = out.size - (WINDOW - 1)
        while (i >= 0 && i >= lo) {
            if (out[i].chatId == chatId) c++
            i--
        }
        return c < WINDOW_MAX
    }

    fun shareOk(chatId: Long) = (counts[chatId] ?: 0) < shareCap

    /** 取主序/小流量池中第一条同时过双约束的条目；[requireShare]=false 时只守窗口 */
    fun takeFirst(list: MutableList<VideoItem>, requireShare: Boolean): VideoItem? {
        val idx = list.indexOfFirst { windowOk(it.chatId) && (!requireShare || shareOk(it.chatId)) }
        return if (idx >= 0) list.removeAt(idx) else null
    }

    // ---- P2-B LinUCB 上下文特征装配（维度/顺序 = LinUCB.DIM=8，与 LinUCB 类注释一一对应） ----
    // [0]偏置1 / [1]频道权重归一化(w/2) / [2]新鲜度档位 / [3]是否已看 / [4]是否图片 /
    // [5]是否音频 / [6]是否长视频 / [7]曝光疲劳 ln(1+页级曝光)/ln(11)（0..1，≥10 次封顶）
    fun featuresOf(it: VideoItem): DoubleArray = doubleArrayOf(
        1.0,
        ((channelWeights[it.chatId] ?: EXPLORE_WEIGHT) / 2.0).coerceIn(0.0, 1.0),
        freshnessFactor(it.dateEpochSec, nowMs),
        if (it.key in seenKeys) 1.0 else 0.0,
        if (it.mediaType == 1) 1.0 else 0.0,
        if (it.mediaType == 2) 1.0 else 0.0,
        if (it.durationSec > LONG_VIDEO_SEC) 1.0 else 0.0,
        ln(1.0 + (tracker?.exposureCount(it.key) ?: 0)) / LN_11,
    )

    /**
     * 从满足双硬约束的候选下标 [idxs]（main 的下标列表）中选一条并移出 main：
     * bandit 在且候选 ≥2 → LinUCB 选臂（内部登记 pending + propensity 日志）；
     * 否则回退旧行为"主序首条"（唯一候选 propensity 恒 1，无反事实价值，不记账）。
     * [poolTag] 探索池标记（new/cold/mix），记进 propensity 日志供分池离线评估。
     */
    fun chooseFrom(idxs: List<Int>, poolTag: String): VideoItem? {
        if (idxs.isEmpty()) return null
        val chosen = if (bandit == null || idxs.size == 1) {
            idxs[0]
        } else {
            val cs = idxs.map {
                val it = main[it]
                LinUCB.Candidate(it.key, it.chatId, it.durationSec, featuresOf(it))
            }
            idxs[bandit.select(cs, poolTag, nowMs)]
        }
        return main.removeAt(chosen)
    }

    /**
     * 探索格选条：新频道半区 → 冷门半区 → 交叉兜底（总量严格 ≤ exploreQuota）；均空返回 null。
     * P2-B：各半区的"选谁"由 LinUCB 决定（多个候选时），半区构成与配额拆半不变。
     */
    fun pickExplore(): VideoItem? {
        if (exploreUsed >= exploreQuota) return null // 探索总配额用尽，本格让回正常流程
        if (exploreNewLeft > 0) {
            val idxs = main.indices.filter {
                val it = main[it]
                it.chatId in newChannelIds && windowOk(it.chatId) && shareOk(it.chatId)
            }
            val pick = chooseFrom(idxs, "new")
            if (pick != null) {
                exploreNewLeft--
                exploreUsed++
                return pick
            }
        }
        if (exploreColdLeft > 0) {
            val idxs = main.indices.filter {
                val it = main[it]
                it.key in coldKeys && windowOk(it.chatId) && shareOk(it.chatId)
            }
            val pick = chooseFrom(idxs, "cold")
            if (pick != null) {
                exploreColdLeft--
                exploreUsed++
                return pick
            }
        }
        // 交叉兜底：配额内找不到候选不浪费探索格（占总配额，不占半区配额）
        val idxs = main.indices.filter {
            val it = main[it]
            (it.chatId in newChannelIds || it.key in coldKeys) &&
                windowOk(it.chatId) && shareOk(it.chatId)
        }
        val pick = chooseFrom(idxs, "mix")
        if (pick != null) exploreUsed++
        return pick
    }

    for (pos in 0 until n) {
        var pick: VideoItem? = null
        // 1. 探索槽位（每 EXPLORE_EVERY 格保底一格）
        if ((pos + 1) % EXPLORE_EVERY == 0) pick = pickExplore()
        // 2. 小流量池段位：优先安放零曝光新内容
        if (pick == null && pos in bandStart..bandEnd) pick = takeFirst(midQueue, requireShare = true)
        // 3. 主序：窗口 + 占比双硬约束
        if (pick == null) pick = takeFirst(main, requireShare = true)
        // 4. 降级：占比不可满足 → 只守窗口；小流量池余量随后兜底
        if (pick == null) pick = takeFirst(main, requireShare = false)
        if (pick == null) pick = takeFirst(midQueue, requireShare = true)
        if (pick == null) pick = takeFirst(midQueue, requireShare = false)
        // 5. 窗口也不可达（极端：余下全同频道）→ 硬放队首，宁违反约束不丢条目
        if (pick == null && main.isNotEmpty()) pick = main.removeAt(0)
        if (pick == null && midQueue.isNotEmpty()) pick = midQueue.removeAt(0)
        if (pick == null) break // 理论不可达：两侧合计恰为 n
        out += pick
        counts[pick.chatId] = (counts[pick.chatId] ?: 0) + 1
    }
    return out
}

/**
 * R82 保底露头前置（rankWeightedPage 收尾段的配额化换位，纯换位不增删条目）：
 *
 *  A. 位次保底：头部窗口有保底条目却全压在前 [GUARANTEED_FRONT_WINDOW] 条之外
 *    （运气差的轮次实测最前位次 15+，用户连划十几屏见不到新面孔）时，把页内最靠前的
 *     保底条目换进前段坑位——窗口内换位不占配额；从窗口外换入才占（配额耗尽后只许
 *     窗口内换位，防每页都往头部塞新面孔）；
 *  B. 配额保底：头部窗口（前 [GUARANTEED_HEAD_WINDOW] 条）内保底条目不足 [budget]
 *     时，把窗口外最靠前的保底条目换入窗口前段，直到达标或无候选/无合规坑位。
 *
 * 换位规矩：
 *  - 候选优先"头部还没露过脸"的保底频道（面孔多样性，防一个保底频道连占数位）；
 *  - 坑位从第 4 条起向前扫（前 3 条尽量留给加权主序的最强内容），每次换位先过
 *    [swapWindowOk]（镜像放置层滑动窗口硬约束），不合规换下一个坑位，全不合规就停手；
 *  - 返回 (换位后的页, 本页换入条数, 头部窗口保底条目总数)——调用方把总数扣进
 *    本轮露头配额：自然露头同占名额，防"每页都前置"把整轮刷成生面孔。
 */
private fun surfaceGuaranteedHead(
    page: List<VideoItem>,
    guaranteedIds: Set<Long>,
    budget: Int,
): Triple<List<VideoItem>, Int, Int> {
    val n = page.size
    if (guaranteedIds.isEmpty() || n < WINDOW) return Triple(page, 0, 0)
    val headN = minOf(GUARANTEED_HEAD_WINDOW, n)
    val frontN = minOf(GUARANTEED_FRONT_WINDOW, n)
    val out = page.toMutableList()
    val headChannels = HashSet<Long>()
    var inHead = 0
    for (i in 0 until headN) {
        if (out[i].chatId in guaranteedIds) {
            inHead++
            headChannels += out[i].chatId
        }
    }
    var swaps = 0

    /** 找合规坑位：先扫 [lo..hi]（前 3 条尽量不动），找不到再回落扫 [0..lo) */
    fun findDst(lo: Int, hi: Int, src: Int): Int {
        var p = lo
        while (p <= hi) {
            if (out[p].chatId !in guaranteedIds && swapWindowOk(out, p, src)) return p
            p++
        }
        p = 0
        while (p < lo && p <= hi) {
            if (out[p].chatId !in guaranteedIds && swapWindowOk(out, p, src)) return p
            p++
        }
        return -1
    }

    /** 找换入候选：[from, to) 内最靠前的保底条目；优先头部尚未露脸的保底频道 */
    fun findSrc(from: Int, to: Int): Int {
        var fallback = -1
        var j = from
        while (j < to) {
            val cid = out[j].chatId
            if (cid in guaranteedIds) {
                if (cid !in headChannels) return j
                if (fallback < 0) fallback = j
            }
            j++
        }
        return fallback
    }

    fun swap(p: Int, src: Int) {
        val t = out[p]; out[p] = out[src]; out[src] = t
        headChannels += out[p].chatId
        swaps++
    }

    // A. 位次保底
    var frontCount = 0
    for (i in 0 until frontN) if (out[i].chatId in guaranteedIds) frontCount++
    if (frontCount == 0) {
        // 配额在时允许从窗口外换入（占头部名额），配额尽时只许窗口内换位
        val src = findSrc(frontN, if (budget > 0) n else headN)
        if (src >= 0) {
            val dst = findDst(3, frontN - 1, src)
            if (dst >= 0) {
                swap(dst, src)
                if (src >= headN) inHead++ // 窗口外换入，占头部名额
            }
        }
    }
    // B. 配额保底
    while (inHead < budget) {
        val src = findSrc(headN, n)
        if (src < 0) break // 页内已无保底条目可换
        val dst = findDst(3, headN - 1, src)
        if (dst < 0) break // 找不到合规坑位，不硬塞
        swap(dst, src)
        inHead++
    }
    return Triple(out, swaps, inHead)
}

/**
 * 换位合规检查：模拟交换 [p] 与 [src] 两位后，所有覆盖这两位之一的滑动窗口
 *（宽 [WINDOW]）内同频道条数仍 ≤ [WINDOW_MAX]。窗口宽度小，直接逐窗清点。
 * 占比约束天然不受换位影响（交换不改各频道总数），故只需查窗口。
 */
private fun swapWindowOk(out: List<VideoItem>, p: Int, src: Int): Boolean {
    if (p == src) return true
    val n = out.size
    if (n <= WINDOW) return true // 整页不足一窗：换位不改任何窗口计数
    fun at(i: Int): VideoItem = when (i) {
        p -> out[src]
        src -> out[p]
        else -> out[i]
    }
    val from = maxOf(0, minOf(p, src) - (WINDOW - 1))
    val to = minOf(n - WINDOW, maxOf(p, src))
    for (s in from..to) {
        val counts = HashMap<Long, Int>()
        for (i in s until s + WINDOW) {
            val c = at(i).chatId
            val v = (counts[c] ?: 0) + 1
            if (v > WINDOW_MAX) return false
            counts[c] = v
        }
    }
    return true
}

/**
 * R82 保底露头观测日志（每页一行）：头部窗口（前 [GUARANTEED_HEAD_WINDOW] 条）内
 * 露头的保底频道个数与条目数、整页最前保底位次（1 基，未进页记"未进页"）、
 * 本次换位移入条数与本轮剩余露头配额。只读统计，不改排序结果。
 */
private fun logGuaranteedSurfacing(
    page: List<VideoItem>,
    guaranteedIds: Set<Long>,
    swappedIn: Int,
) {
    val headN = minOf(GUARANTEED_HEAD_WINDOW, page.size)
    var headItems = 0
    val headChs = LinkedHashSet<Long>()
    for (i in 0 until headN) {
        val cid = page[i].chatId
        if (cid in guaranteedIds) {
            headItems++
            headChs += cid
        }
    }
    val firstPos = page.indexOfFirst { it.chatId in guaranteedIds }
    val frontN = minOf(GUARANTEED_FRONT_WINDOW, page.size)
    var frontItems = 0
    for (i in 0 until frontN) if (page[i].chatId in guaranteedIds) frontItems++
    Log.i(
        TAG,
        "本页保底频道露头：${headChs.size} 个，最前位次 ${if (firstPos >= 0) "${firstPos + 1}" else "未进页"}" +
            "（头 $headN 条内保底条目 $headItems 条，前 $frontN 条内 $frontItems 条，" +
            "换位移入 $swappedIn 条，本轮配额余 ${GuaranteedPoolHolder.budget()}；" +
            "头部保底尾号=${headChs.joinToString(",") { it.toString().takeLast(6) }}）",
    )
}

/**
 * 新鲜度分档系数（P1，半衰式档位）：dateEpochSec 距今 ≤7 天 ×1.0 / 7–30 天 ×0.85 /
 * 30–90 天 ×0.70 / >90 天 ×0.60；dateEpochSec=0（老数据/Mock）×0.80 中性处理；
 * 未来时间戳按最新档 ×1.0。参数表见文件上方常量区注释。
 */
private fun freshnessFactor(dateEpochSec: Int, nowMs: Long): Double {
    if (dateEpochSec <= 0) return FRESH_UNKNOWN
    val ageMs = nowMs - dateEpochSec * 1000L
    return when {
        ageMs < 0 -> FRESH_W1
        ageMs <= FRESH_T1_MS -> FRESH_W1
        ageMs <= FRESH_T2_MS -> FRESH_W2
        ageMs <= FRESH_T3_MS -> FRESH_W3
        else -> FRESH_W4
    }
}

// ---- R126 全网重发折叠（同片多源只出现一次） ----

/** 软判据 caption 归一化的空白折叠正则（file 级预编译，避免逐条重建） */
private val FOLD_CAPTION_WS = Regex("\\s+")

/**
 * R126 全网重发折叠的内容身份（判据优先级：硬 > 软 > 不参与）：
 *
 *  - 硬判据：fileUniqueId 是 TG 服务端级文件身份证——同一视频被转发/搬运到多个
 *    频道时消息 id（item.key）各不相同、文件指纹相同，据此跨频道识别同片；
 *  - 软判据兜底：无指纹（Mock/老数据）时用"归一化 caption + 时长"——搬运通常
 *    原样带走文案与片长。caption 归一化 = trim + 连续空白折叠 + 小写化；
 *  - 宁漏勿错杀：caption 空白或时长缺失（图片/语音等 durationSec=0）时返回 null
 *    不参与折叠——指纹与文案都没有的内容无法可靠判同，宁可各留一条。
 *
 * 供两处共用：页内排序链第一段 [dedupeByFile]（留权重高者）与 FeedViewModel
 * 跨页收尾闸 [foldReposts]（先入列者保留），两条路径同一判据口径。
 */
internal fun contentFoldId(it: VideoItem): String? {
    if (it.fileUniqueId.isNotEmpty()) return "u:" + it.fileUniqueId
    val cap = it.caption.trim()
    if (cap.isEmpty() || it.durationSec <= 0) return null
    return "s:" + it.durationSec + ":" + FOLD_CAPTION_WS.replace(cap, " ").lowercase()
}

/**
 * R126 跨页折叠闸（FeedViewModel.loadMore 收尾段调用）：
 * [page] 中内容身份命中 [known]（已入列内容的身份集）或页内先前幸存者的条目
 * 直接滤掉；幸存者保留页内先序（上游排序链已按权重排好，先到者即高权重者），
 * 其身份并入 [known] 供后续页复用——同一集合跨页滚动，同片全会话只出现一次。
 * 返回 (幸存页, 本页折叠掉的条数)。
 */
internal fun foldReposts(page: List<VideoItem>, known: MutableSet<String>): Pair<List<VideoItem>, Int> {
    var dropped = 0
    val out = ArrayList<VideoItem>(page.size)
    for (it in page) {
        val fid = contentFoldId(it)
        if (fid != null && !known.add(fid)) {
            dropped++
        } else {
            out += it
        }
    }
    return out to dropped
}

/**
 * 文件级去重（R126 起兼负软判据折叠）：同内容身份（[contentFoldId]，硬判据
 * fileUniqueId 优先、无指纹时 caption+时长软判据兜底）只留一条。
 * 先按身份分组选出幸存者（频道权重高者胜，平票留先到的），
 * 再按原顺序过滤——幸存者留在自己原来的位置上（即"保留权重高的那条所在位置"）。
 */
private fun dedupeByFile(items: List<VideoItem>, weights: Map<Long, Double>): List<VideoItem> {
    val winnerByUid = HashMap<String, VideoItem>()
    var hasDup = false
    for (it in items) {
        val fid = contentFoldId(it) ?: continue
        val cur = winnerByUid[fid]
        if (cur == null) {
            winnerByUid[fid] = it
        } else {
            hasDup = true
            if ((weights[it.chatId] ?: EXPLORE_WEIGHT) > (weights[cur.chatId] ?: EXPLORE_WEIGHT)) {
                winnerByUid[fid] = it
            }
        }
    }
    if (!hasDup) return items // 无重复直接返回原列表，省一次拷贝
    return items.filter {
        val fid = contentFoldId(it)
        fid == null || winnerByUid[fid] === it
    }
}

/**
 * 按权重不放回加权随机抽样：每轮对剩余条目算权重，轮盘赌抽一条追加到结果；
 * 上一条的频道会压低本轮同频道条目的权重，实现"后移"。
 *
 * 权重 = 频道权重（[channelWeights]，缺省 [EXPLORE_WEIGHT]）
 *      × [freshnessFactor]（P1 新鲜度档位，[nowMs] 为基准时刻）
 *      × [raceBoost]（P1 赛马乘区：晋级 ×1.4 / 出局 ×0.7，缺省 1.0）
 *      × [channelFatigue]（频道级曝光疲劳：会话内该频道已曝光 n 条 → ×0.9ⁿ，
 *        地板 0.3，见 ChannelFatigueTracker；空表 = 未注册，直通）
 *      × [implicitFactors]（R79 隐式负反馈乘区：秒划计数衰减 → 频道 × 频道×类型
 *        降权，见 ImplicitFeedback.factorSnapshot；空表 = 未注册/无惩罚，直通）
 *      × [guaranteedIds]（R82 保底露头乘区：本轮保底车道频道 ×[GUARANTEED_WEIGHT_BOOST]
 *        温和上浮，只作乘区不动放置层硬约束；空集 = 未跑聚合轮/Mock，直通）
 *      × [itemInterest]（R298 兴趣乘区：item.key → (1+α·tagMatch)×类型系数 的页头
 *        预算表；空表 = 账本未注册/冷启动，直通）
 *      × 软惩罚：与上一条同频道 ×[SAME_CHANNEL_PENALTY]、已看 ×[SEEN_PENALTY]、
 *        长视频 ×[LONG_VIDEO_PENALTY]。
 */
private fun weightedShuffle(
    items: List<VideoItem>,
    channelWeights: Map<Long, Double>,
    seenKeys: Set<String>,
    raceBoost: Map<String, Double> = emptyMap(),
    nowMs: Long = System.currentTimeMillis(),
    channelFatigue: Map<Long, Double> = emptyMap(),
    implicitFactors: Map<Long, DoubleArray> = emptyMap(),
    guaranteedIds: Set<Long> = emptySet(),
    itemInterest: Map<String, Double> = emptyMap(),
): List<VideoItem> {
    val pool = items.toMutableList()
    val out = ArrayList<VideoItem>(items.size)
    var lastChatId: Long? = null
    while (pool.isNotEmpty()) {
        val weights = DoubleArray(pool.size)
        var total = 0.0
        for (i in pool.indices) {
            val it = pool[i]
            var w = channelWeights[it.chatId] ?: EXPLORE_WEIGHT
            w *= freshnessFactor(it.dateEpochSec, nowMs)
            w *= raceBoost[it.key] ?: 1.0
            w *= channelFatigue[it.chatId] ?: 1.0
            // R79 隐式负反馈：频道乘子 × 该频道对应内容类型乘子（均为 (0,1]，直通 1.0）。
            // R110 合并地板：两乘子各带单侧地板 0.4，相乘最差 0.16（-84%）会把重度秒划
            // 频道压到近消失，与"合并最多 -0.5、降权不消失"的设计声称不符——
            // 乘积钳到 [ImplicitFeedback.COMBINED_FACTOR_FLOOR]
            implicitFactors[it.chatId]?.let { fa ->
                w *= (fa[0] * fa[1 + it.mediaType.coerceIn(0, 2)])
                    .coerceAtLeast(ImplicitFeedback.COMBINED_FACTOR_FLOOR)
            }
            // R82 保底露头加权：本轮保底车道频道温和上浮（只作乘区，头部配额前置在放置层后）
            if (it.chatId in guaranteedIds) w *= GUARANTEED_WEIGHT_BOOST
            // R298 兴趣乘区：条目级 tag/类型兴趣分（页头预算表查表；缺席=1.0 直通）
            w *= itemInterest[it.key] ?: 1.0
            if (lastChatId != null && it.chatId == lastChatId) w *= SAME_CHANNEL_PENALTY
            if (it.key in seenKeys) w *= SEEN_PENALTY
            if (it.durationSec > LONG_VIDEO_SEC) w *= LONG_VIDEO_PENALTY
            weights[i] = w
            total += w
        }
        // 轮盘赌抽一条
        var r = Random.nextDouble() * total
        var pick = pool.lastIndex // 浮点兜底
        for (i in pool.indices) {
            r -= weights[i]
            if (r <= 0.0) {
                pick = i
                break
            }
        }
        val chosen = pool.removeAt(pick)
        out += chosen
        lastChatId = chosen.chatId
    }
    return out
}

/**
 * 频道级曝光疲劳追踪器（会话级内存态，不落盘、进程重启清零）：
 * rankWeightedPage 每输出一页，页内各频道的条目数累计进该频道的会话曝光数 n；
 * 后续页 weightedShuffle 对该频道条目权重乘 max([CHANNEL_FATIGUE_FLOOR], 0.9ⁿ)
 * （0.9¹²≈0.28 触地板后恒 0.3）——同一频道不能连续霸屏，但永远有机会冒头。
 * 与条目级赛马（ExposureTracker）正交：赛马管单条晋级/出局，本器管频道别连庄。
 */
internal class ChannelFatigueTracker {
    /** chatId → 本会话累计页级曝光条目数 */
    private val counts = HashMap<Long, Int>()

    /**
     * 各频道当前疲劳系数快照（rankWeightedPage 页头取一次，整页复用，
     * 避免 weightedShuffle 每轮逐条抢锁；无记录的频道不进表 = 系数 1.0）
     */
    @Synchronized
    fun factorMap(chatIds: List<Long>): Map<Long, Double> {
        val out = HashMap<Long, Double>(chatIds.size)
        for (id in chatIds) {
            val n = counts[id] ?: continue
            out[id] = maxOf(CHANNEL_FATIGUE_FLOOR, CHANNEL_FATIGUE_BASE.pow(n))
        }
        return out
    }

    /** 输出页记账：页内每条计入其频道的会话曝光数 */
    @Synchronized
    fun recordPage(items: List<VideoItem>) {
        for (it in items) counts[it.chatId] = (counts[it.chatId] ?: 0) + 1
    }
}

/**
 * ChannelFatigueTracker 的进程级注册点。
 *
 * ── Holder 总约（五个进程级 Holder 的统一口径，R123 收敛文档化）──
 * ExposureTrackerHolder / LinUCBHolder / ChannelFatigueHolder / GuaranteedPoolHolder /
 * ImplicitFeedbackHolder 是排序链的服务定位点：rankWeightedPage 是文件级函数，
 * 排序各段需要的有状态组件（赛马/选臂/疲劳/保底名册/隐式反馈）经这些注册点取用。
 *
 * 共同规则：
 *  1. 注册必须先于首次取用：生产路径由 FeedViewModel 构造期保证（默认源恒构造
 *     WeightedAggregateFeedSource 触发前三者注册，ImplicitFeedback 由 VM 直接注册，
 *     GuaranteedPool 名册由聚合轮回报注入）；首次 rankWeightedPage 之前未完成注册
 *     时对应功能段静默降级（见各 Holder 的"未注册降级"条目），不会崩。
 *  2. 注册语义分两类，差异是有意的、勿拉齐：
 *     - 后注册生效（ExposureTracker/ImplicitFeedback，R109 M1）：新实例直接顶位，
 *       修"VM 重建后排序链读死实例/数据分裂"；落盘底账在，顶位只丢会话内存态。
 *     - 首注册生效（LinUCB/ChannelFatigue）：多源实例共享同一会话口径，重复注册幂等；
 *       VM 重建后沿用旧实例，保留 LinUCB 待结算奖励与频道疲劳的会话连续性。
 *     - GuaranteedPool 是推模式：每轮名册注入即重置露头配额，无"注册"概念。
 *  3. 为何不改依赖注入（R123 评估结论）：DI 化需改 rankWeightedPage 签名及全部
 *     源构造点，且 VM 重建边界上两类注册语义的行为差异无法用单一 DI 生命周期等价
 *     复刻（首注册生效组件跨 VM 存活 vs 注入后随 VM 重建重置）——做不到行为不变，
 *     故保留注册点模式、以本总约统一文档口径。
 *
 * 本件口径：
 *  - 注册语义：**首注册生效**（幂等）——多个 WeightedAggregateFeedSource 实例共享
 *    同一会话疲劳口径（分屏与主 Feed 共用一本账，防跨路径连庄）。
 *  - 注册方/时机：WeightedAggregateFeedSource 构造时。
 *  - 未注册降级：疲劳段整体跳过，退化为无频道疲劳的排序链（单测即此路径）。
 */
internal object ChannelFatigueHolder {
    @Volatile private var tracker: ChannelFatigueTracker? = null

    /** 注册（幂等，首个注册生效——多个 WeightedAggregateFeedSource 实例共享同一会话口径） */
    fun register(): ChannelFatigueTracker {
        tracker?.let { return it }
        return synchronized(this) {
            tracker ?: ChannelFatigueTracker().also { tracker = it }
        }
    }

    fun current(): ChannelFatigueTracker? = tracker
}

/**
 * 本轮保底频道名册的进程级挂接点（R82；五个 Holder 的统一口径见 ChannelFatigueHolder
 * 类注释开头的"Holder 总约"，本件是其中的**推模式**成员：无"注册"概念，每轮名册
 * 经 [update] 注入并同时重置露头配额）：
 * 保底车道在网关采样层（TdLibGateway.sampleRotationPool），排序层拿不到网关——
 * 名册由 FeedViewModel 的 poolReporter 回调按"最久未进池"账面复算后 [update] 进来
 * （口径镜像网关保底车道：池内按上次入池时刻升序取前 [GUARANTEED_POOL_SLOTS] 个；
 * R90 起时刻并列键为 chatId 升序，与网关选取完全同口径——保底车道即全局最久
 * 未进窗的 8 个且居于回报池名单前段，复算结果与网关选取恒一致）。
 * rankWeightedPage 经 [current] 取用；
 * 未注入（Mock 网关/从未跑过聚合轮）时空集，露头段整体跳过、零开销直通。
 *
 * 露头配额 [budgetLeft]：每轮名册注入时重置为 [GUARANTEED_SURFACE_QUOTA]，
 * 由头部窗口内已露头的保底条目（自然露头与换位移入都算）逐条消耗，
 * 耗尽后本轮后续页不再前移保底条目——只修"前几屏见不到"，不把整轮刷成生面孔。
 */
internal object GuaranteedPoolHolder {
    @Volatile
    private var ids: Set<Long> = emptySet()

    private val budgetLock = Any()
    private var budgetLeft = 0

    /** 新一轮保底名册注入（FeedViewModel.recordPoolRound 调用）；空集 = 本轮无保底车道 */
    fun update(newIds: Set<Long>) {
        ids = newIds
        synchronized(budgetLock) {
            budgetLeft = if (newIds.isEmpty()) 0 else GUARANTEED_SURFACE_QUOTA
        }
    }

    /** 本轮保底频道名册（空集 = 露头段整体跳过） */
    fun current(): Set<Long> = ids

    /** 本轮剩余露头配额（rankWeightedPage 页头取一次，整页复用） */
    fun budget(): Int = synchronized(budgetLock) { budgetLeft }

    /** 消耗露头配额（本页头部窗口实际露头的保底条目数） */
    fun consumeBudget(n: Int) {
        if (n <= 0) return
        synchronized(budgetLock) { budgetLeft = (budgetLeft - n).coerceAtLeast(0) }
    }
}

/**
 * 收藏夹 Feed：一次性返回全部本地收藏（收藏时间倒序），没有翻页概念。
 * 数据来自 LocalStore 落盘的完整 VideoItem，离线也能刷。
 */
class FavoritesFeedSource(
    private val store: LocalStore,
) : FeedSource {
    override val request: FeedRequest = FeedRequest()

    override suspend fun nextPage(limit: Int): FeedPage =
        FeedPage(store.loadFavorites(), nextCursor = null)

    override fun reset() {} // 无游标状态，reset 为空操作
}

/**
 * 稍后再看 Feed：一次性返回全部本地暂存（加入时间倒序），没有翻页概念。
 * 数据来自 LocalStore 落盘的完整 VideoItem，离线也能刷。
 */
class WatchLaterFeedSource(
    private val store: LocalStore,
) : FeedSource {
    override val request: FeedRequest = FeedRequest()

    override suspend fun nextPage(limit: Int): FeedPage =
        FeedPage(store.loadWatchLater(), nextCursor = null)

    override fun reset() {} // 无游标状态，reset 为空操作
}

/**
 * 观看历史 Feed：历史条目本体存盘（G4 起完整 VideoItem），内存条目（带最新播放地址）
 * 优先补位——两者在 [nextPage]（IO 线程）合并后按观看时间倒序一次性返回。
 *
 * R165 H1 闭坑（原主线程盘 IO）：旧实现切源时在主线程调 historyReplayItems()
 * → store.loadHistoryItems()（@Synchronized：冷启动预载未完成时读盘 + 等 store
 * 监视器）。现拆两半：[memoryItems] 由调用方在切源清列表**前**捕获（纯内存零 IO，
 * 磁盘半边 nextPage 现读——主线程只做快照交接，构建在离屏 IO 完成，
 * 首页到达后由 _items 的一次 StateFlow 写入原子换装。
 */
class HistoryFeedSource(
    private val store: LocalStore,
    private val memoryItems: List<VideoItem>,
) : FeedSource {
    override val request: FeedRequest = FeedRequest()

    override suspend fun nextPage(limit: Int): FeedPage {
        // 内存半边优先（key 撞车时内存条目赢——它带最新播放地址）；
        // 磁盘半边补内存没有的（上一会话看过的），读盘失败静默空表
        val persisted = runCatching { store.loadHistoryItems() }.getOrDefault(emptyList())
        val memoryKeys = memoryItems.mapTo(HashSet()) { it.key }
        val byKey = (memoryItems + persisted.filterNot { it.key in memoryKeys })
            .associateBy { it.key }
        val items = store.loadHistory().mapNotNull { (key, _) -> byKey[key] }
        return FeedPage(items, nextCursor = null)
    }

    override fun reset() {} // 无游标状态，reset 为空操作
}

/**
 * 订阅追更 Feed：对 LocalStore 里的每个订阅频道，
 * 用 GetChatHistory 从最新消息往回翻到 lastSeenMessageId 为止，收集其间的新视频，
 * 全部频道按 dateEpochSec 倒序混排后一次性返回（新内容一般不多，不做翻页）。
 *
 * 已读推进口径（R122 修正，旧实现打开第一页即把全部新内容标为已读）：
 *  - nextPage **不再推进任何频道的 lastSeenMessageId**——打开追更源只是"刷"，
 *    没看的内容下次进来照样出现；
 *  - 收集时按已看双轨底账（key + 内容指纹 fileUniqueId）过滤——已真看过的条目
 *    不再出现（此前靠"基线推进到最新"顺带隐藏，现在改由已看底账显式承担）；
 *  - 基线只在 [commitSeenProgress] 推进，且推进目标受两道闸约束（守住的 invariant：
 *    "任何未看视频 messageId > 基线"，保证没看的内容下次仍会被收集）：
 *     1. 真看闸：只推进到"最旧未看下发条目之下"（全部看了才推进到最新一条）——
 *        列表新→旧排序，用户没刷到的条目不因上方条目被看而连带标读；
 *     2. 覆盖闸：不超过本轮连续成功扫描覆盖到的最老消息（floor）——中途拉取失败/
 *        翻页轮数耗尽时，未覆盖区间保持旧基线，修复旧实现"某页失败仍按已收集
 *        最大 messageId 推进、失败区间永久漏更"的连带缺陷。
 */
class SubscriptionFeedSource(
    private val gateway: TdGateway,
    private val store: LocalStore,
) : FeedSource {
    override val request: FeedRequest = FeedRequest()

    /**
     * 本轮已下发账目（chatId → 下发条目 + 连续覆盖下限 floor）：
     * [commitSeenProgress] 据此判定"哪些条目真被用户看到过"（与已看底账求交）
     * 以及"基线最远能推进到哪"（floor）。
     * 仅内存，随源实例存活；进程死亡则丢失——不影响正确性（已看过滤兜住展示口径，
     * 基线留待下次会话的提交点再推进）。
     */
    private class ServedBatch(
        val items: MutableList<VideoItem> = mutableListOf(),
        /** 连续成功覆盖到（含）的最老消息 id；[FLOOR_COVERED_ALL] = 全域覆盖（到底/接到旧基线） */
        var floor: Long = 0L,
    )

    private val servedLock = Any()
    private val served = HashMap<Long, ServedBatch>()

    override suspend fun nextPage(limit: Int): FeedPage {
        // 先结上一轮的账：上次下发里已被真看的条目推进基线（refresh 重拉走到这里；
        // 切源离场由 FeedViewModel.switchSource 显式调 commitSeenProgress 兜底）
        commitSeenProgress()
        val subs = store.loadSubscriptions()
        if (subs.isEmpty()) return FeedPage(emptyList(), null)
        // 已看双轨底账：真看过的条目不再下发（替代旧实现"基线推进"的隐藏作用）
        val (seenKeys, seenUids) = runCatching { store.loadSeenIdentities() }
            .getOrDefault(emptySet<String>() to emptySet())

        val merged = mutableListOf<VideoItem>()
        for (sub in subs) {
            val scan = collectNewVideos(sub, seenKeys, seenUids)
            merged += scan.items
            synchronized(servedLock) {
                val batch = served.getOrPut(sub.chatId) { ServedBatch() }
                batch.items.addAll(scan.items) // 重复下发（refresh 重拉）只产生重复条目，min/max 不受影响
                if (scan.floor != FLOOR_COVERED_ALL) {
                    // 多轮扫描都从最新起扫、区间在顶端相接，合并覆盖下限取各轮最小
                    batch.floor = if (batch.floor == 0L) scan.floor else minOf(batch.floor, scan.floor)
                }
            }
        }
        return FeedPage(merged.sortedByDescending { it.dateEpochSec }, nextCursor = null)
    }

    /**
     * 按"真实观看"推进各订阅频道的已读基线（lastSeenMessageId），只进不退，幂等。
     * 推进目标 = min(真看闸, 覆盖闸)（两道闸的语义见类注释）；都没看过则不推进。
     * 提交后清空下发底账（账目已落，下一轮重新记账）。失败静默（追更基线是便利设施）。
     */
    suspend fun commitSeenProgress() {
        val snapshot: Map<Long, ServedBatch>
        synchronized(servedLock) {
            if (served.isEmpty()) return
            snapshot = served.mapValues { ServedBatch(it.value.items.toMutableList(), it.value.floor) }
            served.clear()
        }
        runCatching {
            val (seenKeys, seenUids) = store.loadSeenIdentities()
            // R337：判定收口 SeenDedupPolicy.isSeen（双轨同尺）
            fun seen(it: VideoItem) = SeenDedupPolicy.isSeen(it, seenKeys, seenUids)
            val subs = store.loadSubscriptions()
            var changed = false
            val updated = subs.map { sub ->
                val batch = snapshot[sub.chatId] ?: return@map sub
                if (batch.items.isEmpty()) return@map sub
                // 真看闸：有没看的 → 停在最旧未看一条之下；全看了 → 推进到最新一条
                val unseenMin = batch.items.filter { !seen(it) }.minOfOrNull { it.messageId }
                val byWatch = unseenMin?.minus(1L) ?: batch.items.maxOf { it.messageId }
                // 覆盖闸：不超过连续成功扫描覆盖到的最老消息（未覆盖区间保持未读）
                val target = if (batch.floor == 0L || batch.floor == FLOOR_COVERED_ALL) {
                    byWatch
                } else {
                    minOf(byWatch, batch.floor - 1L)
                }
                if (target > sub.lastSeenMessageId) {
                    changed = true
                    sub.copy(lastSeenMessageId = target)
                } else {
                    sub
                }
            }
            if (changed) store.saveSubscriptions(updated)
        }
    }

    /**
     * 收集一个订阅频道里 lastSeenMessageId 之后发布的视频（已看双轨底账命中的剔除），
     * 并回读本轮"连续成功覆盖下限" floor（见 [ServedBatch]）。
     * 从最新消息往回翻，单频道失败（网络/限流）只跳过该频道，不影响其他频道。
     */
    private suspend fun collectNewVideos(
        sub: Subscription,
        seenKeys: Set<String>,
        seenUids: Set<String>,
    ): ScanResult {
        val result = mutableListOf<VideoItem>()
        var cursor: String? = null
        var rounds = 0
        var floor = 0L // 尚未成功覆盖任何页（首 页即失败时 floor=0，commit 侧按不推进处理）
        while (rounds < MAX_SCAN_ROUNDS) {
            val page = runCatching {
                gateway.fetchVideos(FeedRequest(channelId = sub.chatId), cursor, SCAN_PAGE_SIZE)
            }.getOrNull() ?: break // 单频道失败只跳过该频道；floor 保持上一成功页的值
            result += page.items.filter {
                it.messageId > sub.lastSeenMessageId &&
                    !SeenDedupPolicy.isSeen(it, seenKeys, seenUids)
            }

            // 游标即该页最老消息 id（Mock 为 "o:N" 格式，substringAfterLast 兼容两种）
            val nextRaw = page.nextCursor
            val nextMsgId = nextRaw?.substringAfterLast(':')?.toLongOrNull()

            // 新订阅（lastSeen=0）只取最新一页作为"首刷"，避免把频道历史全倒出来；
            // 覆盖下限 = 该页最老消息（到底则全域）
            if (sub.lastSeenMessageId == 0L) {
                floor = nextMsgId ?: FLOOR_COVERED_ALL
                break
            }
            if (nextRaw == null) { floor = FLOOR_COVERED_ALL; break } // 频道到底：全域覆盖
            if (nextMsgId == null) break // 游标格式异常：保守保持既有 floor
            if (nextMsgId <= sub.lastSeenMessageId) { // 已翻到上次进度：接上旧基线 = 全域覆盖
                floor = FLOOR_COVERED_ALL
                break
            }
            floor = nextMsgId
            cursor = nextRaw
            rounds++
        }
        return ScanResult(result, floor)
    }

    /** 单频道一轮扫描的结果：下发条目 + 连续覆盖下限 */
    private data class ScanResult(val items: List<VideoItem>, val floor: Long)

    override fun reset() {} // 追更无游标状态，reset 为空操作（下发底账保留，供下次 nextPage 头结算）

    private companion object {
        /** 每频道每轮拉取的消息数 */
        const val SCAN_PAGE_SIZE = 50

        /** 单频道最多往回翻的页数，防止长时间未追更时一次刷出爆量历史 */
        const val MAX_SCAN_ROUNDS = 8

        /** floor 哨兵值：扫描覆盖到底/接到旧基线 = 全域覆盖，覆盖闸不生效 */
        const val FLOOR_COVERED_ALL = Long.MAX_VALUE
    }
}

/**
 * 关注流 Feed（R154 立、R176 公平供给重设计）：订阅频道的公平内容主场。
 *
 * 与 [SubscriptionFeedSource]（订阅追更）的定位分野：
 *  - 追更 = 新内容提醒：只收 lastSeenMessageId 之后的增量，一次性返回、无翻页；
 *  - 关注流 = 订阅频道的内容主场：不看 lastSeen，从各频道最新内容起可翻页
 *    深挖历史，两者并存互不替代。
 *
 * R176 重设计（用户三实锤：刷到底/重复视频/频道集中；用户提案 round-robin）：
 *  ① **公平轮转**（治频道集中）：供给序 = [interleaveFollowingRound] 纯函数——
 *     最少已供给者优先（无饿死结构保证）+ 口味权重（decayedChannelWeights
 *     时间衰减画像，有界倾斜：高权重每 pass 至多第二条）+ 聚合流同款放置层
 *     硬约束（连续 5 条同频道 ≤2 / 批内占比 ≤25%）。频道级新鲜度参与排序，
 *     条目级 round-robin 交错——旧实现的全局时间倒序让高频频道霸屏，是
 *     「关注 N 频道只有那几个翻来覆出」的根因。
 *  ② **推送位判重**（治重复）：已看过滤整体收编进源（[followingGatePasses]，
 *     loadSeenIdentities 双轨底账 + 会话 servedKeys 推送位记忆）——旧实现由
 *     loadMore 事后滤，游标已推进，被滤条目从供给里永久消失；且「重温」按钮
 *     全局关掉 excludeWatched 再从头顶重来 = 同一批反复出现。用户翻回旧内容
 *     是列表内滚动，不算重复——判重只挡「推送位重复」。
 *  ③ **永不见底**（治刷到底）：分层供给 [FollowingSupplyLayer]——FRESH（未看
 *     深挖，游标向老翻页）→ RECYCLE（已看二轮回收：游标归顶重走，距最近观看
 *     ≥[FOLLOWING_REWATCH_GAP_MS] 才放行，批内降权=未看优先+越久远越靠前）→
 *     RECOMMEND（推荐兜底：网关聚合路，条目带 recommended=true 标记，UI 显示
 *     「为你推荐」chip 明示来源）→ 每出满几轮推荐环回回收层探测新帖。有订阅
 *     时 nextCursor 恒非 null——「到底了」状态从关注流永久移除。
 *  ④ **余量缓冲**：一轮多拉的交错序条目存 pending，翻页先吃余量再补拉
 *     （R75-B 聚合余量同思想）——公平交错的页序不被页长裁剪破坏。
 *
 * 保留语义（R154/R170b）：
 *  - 每页现读 store.loadSubscriptions()——新订阅自动并入（无游标=从最新开取）、
 *    退订自然剔除；订阅动态变化常新；
 *  - 每频道独立游标（TDLib = messageId，Mock = "o:N"），[END_MARK] 表示该频道
 *    服务端历史已翻完（FRESH 层的到底，仅用于降层判定，不再终止供给）；
 *  - R170b 并发拉取：8 路信号量并发取页、结果 awaitAll 汇聚后串行组装——
 *    游标写回与合并不在并发段内（GatewayMediaTab P1-1 教训结构免疫）；
 *  - 单频道失败只跳过该频道并保留原游标（含首轮失败不误标 END 的修正：
 *    失败返回原 state，state 为 null = 未开取，下轮重试而非判死）。
 *
 * 状态全部会话内存（reset 全清）；跨会话记忆交给判重底账与 R170b 快照预热。
 * 构造签名向后兼容：后四个 provider 参数带默认值（单测/演示注入用）。
 */
class FollowingFeedSource(
    private val gateway: TdGateway,
    private val store: LocalStore,
    // R176 供给参数（带默认=构造兼容；WeightedAggregateFeedSource 同款注入风格）：
    /** 口味权重表（默认 decayedChannelWeights：watch_events 时间衰减 + 探索兜底） */
    private val weightsProvider: () -> Map<Long, Double> = {
        decayedChannelWeights(store.loadWatchEvents(), System.currentTimeMillis())
    },
    /** 已看身份双轨底账（key 集 + 内容指纹集；默认接 LocalStore 判重底账） */
    private val seenProvider: () -> Pair<Set<String>, Set<String>> = { store.loadSeenIdentities() },
    /** 最近观看时刻（key → ts / fileUniqueId → ts；默认接观看历史 500 条窗口） */
    private val watchTsProvider: () -> Pair<Map<String, Long>, Map<String, Long>> = {
        store.loadWatchTimestamps()
    },
    /** 已看过滤开关（默认开=产品口径「已看内容默认不再出现」；VM 接全局开关） */
    private val excludeSeenProvider: () -> Boolean = { true },
) : FeedSource {
    // 与 WeightedAggregateFeedSource 同为聚合语义：无单一频道、无搜索词
    override val request: FeedRequest = FeedRequest()

    /** 每频道游标（chatId → 网关游标；[END_MARK] = 服务端历史翻完）；RECYCLE 入场归顶重置 */
    private val channelStates = LinkedHashMap<Long, String>()

    /** 当前供给层（见类注释③；颗粒无收时降层、推荐层按节拍环回） */
    private var layer = FollowingSupplyLayer.FRESH

    /** 当前层连续颗粒无收轮数（达 [FOLLOWING_FRESH_DRY_ROUNDS] 降层） */
    private var dryStreak = 0

    /** RECOMMEND 层已出内容的轮数（环回回收层探测新帖的节拍原料） */
    private var recommendRounds = 0

    /** 上次环回回收层探测的时刻（毫秒） */
    private var lastRecycleProbeAt = 0L

    /** 推荐兜底层的聚合游标（网关聚合路自己的游标链；null = 从头/轮换新池） */
    private var recommendCursor: String? = null

    /** 会话推送位记忆：本会话已推送过的条目 key（推送位判重；超限丢最旧一半） */
    private val servedKeys = LinkedHashSet<String>()

    /** 会话频道供给账：chatId → 本会话已推送条数（公平轮转「最少已供给者优先」的键） */
    private val servedCount = HashMap<Long, Int>()

    /** 轮转轮余量缓冲（交错序原样保序；翻页先吃余量=零网络直取） */
    private val pending = ArrayList<VideoItem>()

    override suspend fun nextPage(limit: Int): FeedPage {
        val subs = store.loadSubscriptions()
        if (subs.isEmpty()) return FeedPage(emptyList(), null)

        val ids = subs.map { it.chatId }.distinct()
        // 退订频道的游标随本页剔除（其内容不再属于关注流）；新订阅无记账 = 从最新开取
        channelStates.keys.retainAll(ids.toSet())

        // ① 先吃余量缓冲：翻页零网络直取（交错序在拉取时已定，裁页不破坏公平序）
        val drained: List<VideoItem> = if (pending.isEmpty()) {
            emptyList()
        } else {
            val take = minOf(limit, pending.size)
            val head = pending.take(take)
            pending.subList(0, take).clear()
            head
        }
        if (drained.size >= limit) return FeedPage(drained, ALIVE_CURSOR)

        // ② 供给轮循环：攒满一页即收（单页内轮数封顶，防单页内无限挖库；
        //    跨页由 loadMore 的队列保底链式续拉接力）
        val collected = ArrayList<VideoItem>()
        var rounds = 0
        while (drained.size + collected.size < limit && rounds < FOLLOWING_ROUNDS_PER_PAGE) {
            rounds++
            val nowMs = System.currentTimeMillis()
            val (seenK, seenU) = seenProvider()
            val (watchK, watchU) = watchTsProvider()
            val ledger = FollowingSeenLedger(seenK, seenU, watchK, watchU, servedKeys)
            val yield: List<VideoItem> = when (layer) {
                FollowingSupplyLayer.FRESH, FollowingSupplyLayer.RECYCLE ->
                    fetchRotationRound(ids, limit, ledger, nowMs)
                FollowingSupplyLayer.RECOMMEND ->
                    fetchRecommendRound(limit, ledger, nowMs)
            }
            if (yield.isNotEmpty()) {
                dryStreak = 0
                collected += yield
                // 推送位记账（含缓冲待出的条目——它们已承诺在本会话某推送位出现）
                yield.forEach {
                    servedKeys += it.key
                    servedCount.merge(it.chatId, 1, Int::plus)
                }
                trimServedKeys()
                // RECOMMEND 出货节拍：出满 N 轮且距上次探测够久 → 环回回收层
                //（入场即归顶重走，能捎带发现新帖：未看条目永远过闸）
                if (layer == FollowingSupplyLayer.RECOMMEND) {
                    recommendRounds++
                    if (shouldProbeRecycle(recommendRounds, nowMs - lastRecycleProbeAt)) {
                        enterRecycleProbe(nowMs)
                    }
                }
            } else {
                dryStreak++
                val next = nextFollowingLayer(
                    layer, dryStreak,
                    allChannelsExhausted = ids.all { channelStates[it] == END_MARK },
                )
                if (next != layer) {
                    layer = next
                    dryStreak = 0
                    if (next == FollowingSupplyLayer.RECYCLE) enterRecycleProbe(nowMs)
                }
            }
        }

        // ③ 页长裁剪：溢出部分保序回余量缓冲（下一页先吃）
        val full = drained + collected
        val served = full.take(limit)
        pending += full.drop(limit)
        // 供给永不为底（有订阅时）：游标恒非 null——loadMore 链式续拉不断流，
        // 「到底了」状态从关注流永久移除（无订阅在方法开头已返回空页+引导卡）
        return FeedPage(served, ALIVE_CURSOR)
    }

    override fun reset() {
        channelStates.clear()
        layer = FollowingSupplyLayer.FRESH
        dryStreak = 0
        recommendRounds = 0
        lastRecycleProbeAt = 0L
        recommendCursor = null
        servedKeys.clear()
        servedCount.clear()
        pending.clear()
    }

    /** R176 供给态观测口（单测/诊断用，只读）：当前供给层 */
    internal fun supplyLayerNow(): FollowingSupplyLayer = layer

    /** R176 供给态观测口（单测/诊断用，只读）：余量缓冲条数（翻页零网络直取的库存） */
    internal fun pendingCount(): Int = pending.size

    /**
     * FRESH/RECYCLE 共用的轮转供给轮：并发拉取每频道一小页 → 判重闸过滤 →
     * （RECYCLE 批内回收降权序）→ 公平交错。游标写回在并发段之外。
     */
    private suspend fun fetchRotationRound(
        ids: List<Long>,
        limit: Int,
        ledger: FollowingSeenLedger,
        nowMs: Long,
    ): List<VideoItem> {
        // 每频道本轮配额（R154 口径沿用）：订阅少时多分点，订阅多时收窄，
        // 下限 2 条保证百级订阅时每轮仍逐频道推进游标
        val perChannel = (limit / ids.size).coerceIn(2, 10)
        val gateOn = excludeSeenProvider()
        // R170b 并发拉取（8 路信号量；订阅频道历史多在 TDLib 本地库，并发波次
        // 近乎零成本）。结果 awaitAll 汇聚后串行组装：游标写回与合并都不在并发段。
        val results: List<Pair<List<VideoItem>, String?>> = coroutineScope {
            val sem = Semaphore(FOLLOWING_FETCH_CONCURRENCY)
            ids.map { id ->
                async {
                    sem.acquire()
                    try {
                        val state = channelStates[id] // null = 未开取；END_MARK = 已到底（读侧无写）
                        if (state == END_MARK) {
                            emptyList<VideoItem>() to END_MARK
                        } else {
                            val fetched = runCatching {
                                gateway.fetchVideos(FeedRequest(channelId = id), state, perChannel)
                            }
                            if (fetched.isSuccess) {
                                val page = fetched.getOrThrow()
                                page.items to (page.nextCursor ?: END_MARK)
                            } else {
                                // 失败保留原游标（null = 未开取，**不判死**——R154 老病：
                                // 失败页 nextCursor=null 被写成 END_MARK，首轮失败即判死频道），
                                // 下轮自动重试
                                emptyList<VideoItem>() to state
                            }
                        }
                    } finally {
                        sem.release()
                    }
                }
            }.map { it.await() }
        }
        // 串行组装：游标写回 + 判重闸 + 批内序（FRESH=网关时间序；RECYCLE=回收降权序）
        val batches = LinkedHashMap<Long, List<VideoItem>>(ids.size)
        ids.forEachIndexed { i, id ->
            val (items, newState) = results[i]
            // newState=null = 该频道未开取且本轮失败（不写账，下轮重试）
            if (newState != null) channelStates[id] = newState
            val kept = items.filter {
                followingGatePasses(it, layer, ledger, nowMs, gateOn)
            }
            batches[id] = if (layer == FollowingSupplyLayer.RECYCLE) recycleOrder(kept, ledger) else kept
        }
        // 公平交错（纯函数见 FollowingFairSupply.kt；RECYCLE 批内已降权排序）
        return interleaveFollowingRound(batches, servedCount, weightsProvider())
    }

    /**
     * RECOMMEND 推荐兜底轮：网关聚合路（同推荐 tab 内容池）拉一页，判重闸
     * （未看且未推送）过滤后标记 recommended=true 交给 UI 显示「为你推荐」chip。
     * 聚合游标到底（null）= 轮换池自然重开——聚合路本身按轮换池供给，永不枯竭。
     */
    private suspend fun fetchRecommendRound(
        limit: Int,
        ledger: FollowingSeenLedger,
        nowMs: Long,
    ): List<VideoItem> {
        val gateOn = excludeSeenProvider()
        val page = runCatching {
            gateway.fetchVideos(FeedRequest(), recommendCursor, limit.coerceAtLeast(20))
        }.getOrNull() ?: return emptyList()
        recommendCursor = page.nextCursor // null = 聚合轮换重开，下轮从新池续供
        return page.items.filter {
            followingGatePasses(it, FollowingSupplyLayer.RECOMMEND, ledger, nowMs, gateOn)
        }.map { it.copy(recommended = true) }
    }

    /** 环回回收层：游标归顶重走（探测新帖 + 已看二轮回收），节拍原料复位 */
    private fun enterRecycleProbe(nowMs: Long) {
        layer = FollowingSupplyLayer.RECYCLE
        channelStates.clear()
        lastRecycleProbeAt = nowMs
        recommendRounds = 0
        dryStreak = 0
    }

    /** 推送位记忆容量治理：超 [FOLLOWING_SERVED_KEYS_MAX] 丢最旧一半（FIFO 口径对齐 seen_ids.log） */
    private fun trimServedKeys() {
        if (servedKeys.size <= FOLLOWING_SERVED_KEYS_MAX) return
        val keep = servedKeys.drop(servedKeys.size / 2)
        servedKeys.clear()
        servedKeys.addAll(keep)
    }

    private companion object {
        /** 游标中标记"该频道服务端历史已翻完"的占位符（真实游标不会取到这个值） */
        const val END_MARK = "-"

        /**
         * R170b 关注流并发拉取路数：与快车道同档（8 路）——订阅频道的历史消息
         * 多已在 TDLib 本地库（关注频道常读），并发波次内本地命中近乎零成本；
         * 未同步频道各占一拍 RTT。刻意不高于媒体整窗（5）太多：订阅数百时
         * 请求包络仍受信号量封顶，限流暴露面与既有路径同量级。
         */
        const val FOLLOWING_FETCH_CONCURRENCY = 8

        /**
         * R176 永不见底哨兵游标：有订阅时 nextPage 恒返回非 null 游标——
         * loadMore 的「到底即收」（nextCursor==null break）对关注流永不触发，
         * 链式续拉不断流。外层不解析该值（供给状态全在源内会话内存）。
         */
        const val ALIVE_CURSOR = "r176:alive"
    }
}

/**
 * 用户自组的频道合集（LocalStore collections.json 持久化模型）：
 * name 为主键（同名覆盖），channelIds 为合集包含的频道 id 列表。
 */
data class ChannelCollection(
    val name: String,
    val channelIds: List<Long>,
)

/**
 * 频道合集 Feed：限定频道子集的聚合源（"舞蹈合集"=频道A+B+C，一键刷合集）。
 *
 * 每轮对合集内每个频道各取一小页（gateway.fetchVideos(FeedRequest(channelId=…))），
 * 混洗合并后返回，思路与 TdLibGateway.fetchAggregated 的多频道游标聚合一致，
 * 但这里把各频道游标视为不透明字符串原样透传（TDLib 单频道游标=msgId，Mock="o:N"），
 * 因此编码用 "chatId=perChannelCursor|chatId=perChannelCursor|…"，
 * [END_MARK] 表示该频道已到底；外层游标为 null 表示首页（全部频道从头开始）。
 *
 * 单频道失败只跳过该频道并保留其原游标（下一轮自动重试），不影响合集里其他频道。
 */
class CollectionFeedSource(
    private val gateway: TdGateway,
    private val collection: ChannelCollection,
) : FeedSource {
    // 与 WeightedAggregateFeedSource 同为聚合语义：无单一频道、无搜索词
    override val request: FeedRequest = FeedRequest()

    /** 聚合游标（编码见类注释）；null = 首页 */
    private var cursor: String? = null
    private var exhausted = false

    override suspend fun nextPage(limit: Int): FeedPage {
        val ids = collection.channelIds.distinct()
        if (ids.isEmpty() || exhausted) return FeedPage(emptyList(), null)

        val states = decodeCursor(cursor).toMutableMap()
        // 每频道本轮的配额：均分一页，至少 4 条（对齐 TdLibGateway.fetchAggregated 的策略）
        val perChannel = maxOf(4, limit / ids.size)
        val merged = ArrayList<VideoItem>(perChannel * ids.size)
        for (id in ids) {
            val state = states[id] // null = 该频道尚未开取（首页）；END_MARK = 已到底
            if (state == END_MARK) continue
            val page = runCatching {
                gateway.fetchVideos(FeedRequest(channelId = id), state, perChannel)
            }.getOrElse { FeedPage(emptyList(), state) } // 失败保留原游标，下轮重试
            merged += page.items
            states[id] = page.nextCursor ?: END_MARK
        }
        merged.shuffle() // 混排，避免同一频道扎堆（同 TdLibGateway.fetchAggregated）

        exhausted = ids.all { states[it] == END_MARK }
        cursor = encodeCursor(states)
        // 配额均分会多取一点，截断到 limit（多出的条目随游标前进被丢弃，与网关层聚合行为一致）
        return FeedPage(merged.take(limit), if (exhausted) null else cursor)
    }

    override fun reset() {
        cursor = null
        exhausted = false
    }

    /** 编码："chatId=perChannelCursor|…"，分隔符不会出现在各网关游标里（数字 / "o:N"） */
    private fun encodeCursor(states: Map<Long, String>): String =
        states.entries.joinToString("|") { (id, c) -> "$id=$c" }

    private fun decodeCursor(s: String?): Map<Long, String> {
        if (s.isNullOrBlank()) return emptyMap()
        val map = LinkedHashMap<Long, String>()
        for (seg in s.split('|')) {
            val eq = seg.indexOf('=')
            if (eq <= 0) continue // 坏段跳过
            val id = seg.substring(0, eq).toLongOrNull() ?: continue
            map[id] = seg.substring(eq + 1)
        }
        return map
    }

    private companion object {
        /** 游标中标记"该频道已到底"的占位符（真实游标不会取到这个值） */
        const val END_MARK = "-"
    }
}
