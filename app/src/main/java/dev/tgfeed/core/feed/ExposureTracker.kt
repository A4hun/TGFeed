package dev.tgfeed.core.feed

import dev.tgfeed.core.store.ExposureRecord
import dev.tgfeed.core.store.LocalStore
import dev.tgfeed.core.td.VideoItem

/**
 * 流量池赛马曝光追踪器（ALGO P1，对标抖音"流量池赛马"机制，docs/ALGO-RESEARCH.md §1/§4）：
 *
 * 核心思想——新内容先进"小流量池"拿保底测试位，数据达标后晋级优先位，连败出局降权：
 *  - 曝光计数自维护：每次 rankWeightedPage 输出一页排序结果，页内每条 item.key 记一次
 *    曝光（页级口径——无法感知用户实际滑到第几条，这是不改 FeedViewModel 前提下最诚实
 *    的代理；落盘走 LocalStore 的 exposure.json，tmp+rename 原子写）；
 *  - 达标判定复用 LocalStore 现有 key 级数据（WatchEvent 无 item key，条目级完播归因在
 *    数据层不存在，见 docs/ALGO-AUDIT.md §2.3——不改 WatchEvent 结构是本任务的边界）：
 *      · 互动：likes.json + favorites.json + downloads.json 命中 item.key（赞/收藏/下载）；
 *      · 完播：ExposureRecord.completed 标记（R110 补——看完（≥97%）的条目会被
 *        saveProgress 清掉续播点，"完播但未互动"此前永远评不上 GRADUATED，
 *        最强正信号缺席；现在清续播点时顺手登记完播标记）；
 *      · 近完读：watch_progress.json 里 posMs ≥ 60% × durationSec（"看到大半"的续播记录）；
 *      · 图文/音频（durationSec≤0）无续播概念，只能靠互动达标。
 *    调研口径"完读率>60% 或 有互动"在单设备单用户下落为"近完读过 或 互动过"。
 *  - 分层语义：
 *      NEW       零曝光 → 进小流量池（rankWeightedPage 把它安放到页内中段位，不占头部）；
 *      GRADUATED 曝光过且达标（互动或近完读）→ 晋级，打分 ×[PROMOTE_BOOST] 优先出头部；
 *      STALLED   曝光 ≥ [MAX_EXPOSURES] 次仍不达标 → 赛马出局，打分 ×[STALLED_PENALTY]
 *                （对应赛马制"指标差就停止分发"）；
 *      NORMAL    曝光过、数据还不够判生死 → 不加不减，按正常权重参与。
 *  - 探索侧供给：[newChannelIdsProvider] 提供"近 7 天 firstSeen 的图谱新节点"（DiscoveryGraph
 *    topCandidates 口径，由注册方注入），探索配额的一半留给这些"新发现的新频道"。
 *
 * 线程：全部公开方法 @Synchronized；内部信号集（互动/近完读/新频道）带 [SIGNAL_TTL_MS]
 * 的 TTL 缓存，避免每页排序都重读/重算（底层 LocalStore 读均有内存缓存，这里再省一层拼装）。
 */
class ExposureTracker(
    private val store: LocalStore,
    /** 近 7 天 firstSeen 的图谱新节点 chatId 集供给（注册方注入；无图谱时给 { emptySet() }） */
    private val newChannelIdsProvider: () -> Set<Long> = { emptySet() },
) {

    /** 赛马分层档位（语义见类注释） */
    enum class Tier { NEW, GRADUATED, STALLED, NORMAL }

    /** 内存曝光表（懒加载自 LocalStore；本类是 exposure.json 的唯一写入方） */
    private var exposureMap: MutableMap<String, ExposureRecord>? = null

    // ---- 信号集 TTL 缓存：互动 key 集 / 续播进度表 / 新频道 id 集 ----
    private var interactKeys: Set<String>? = null
    private var progressMap: Map<String, Long> = emptyMap()
    private var newChannelIds: Set<Long> = emptySet()
    private var signalsTs = 0L

    /** 判定一条目的赛马档位 */
    @Synchronized
    fun tierOf(item: VideoItem): Tier {
        val exp = exposureTable()[item.key]?.exposures ?: 0
        if (exp <= 0) return Tier.NEW
        ensureSignalsLocked()
        // R110：完播标记（≥97% 清续播点时登记）与互动/近完读同列达标口径——
        // 完播未互动此前是结构性漏判（续播点已清，近完读取不到任何进度）
        if (item.key in interactKeys!! || isCompletedLocked(item.key) || isNearCompleteLocked(item)) {
            return Tier.GRADUATED
        }
        if (exp >= MAX_EXPOSURES) return Tier.STALLED
        return Tier.NORMAL
    }

    /** 近 7 天 firstSeen 的图谱新节点 id 集（探索配额"新频道"半区用；TTL 缓存） */
    @Synchronized
    fun freshChannelIds(): Set<Long> {
        ensureSignalsLocked()
        return newChannelIds
    }

    /** 该 key 的累计页级曝光次数（P2-B LinUCB 的曝光疲劳特征用；无记录返回 0） */
    @Synchronized
    fun exposureCount(key: String): Int = exposureTable()[key]?.exposures ?: 0

    /**
     * 探索奖励判定（P2-B LinUCB 的奖励信号，复用赛马"达标"口径的强信号部分）：
     * 互动（赞/收藏/下载命中 key）→ 1.0；完播（R110 登记口径）→ 0.8；
     * 近完读（续播位置 ≥60%×时长）→ 0.6；皆无 → 0.0。
     * 与赛马分层正交：赛马用同一批信号管"晋级/出局"的打分乘区，这里管
     * "探索位该选谁"的带奖励（0.0 不代表负反馈，只代表"尚无正信号"，由
     * LinUCB.settle 的结算窗决定是继续等还是按 0 更新）。
     */
    @Synchronized
    fun positiveReward(key: String, durationSec: Int): Double {
        ensureSignalsLocked()
        if (key in interactKeys!!) return 1.0
        if (isCompletedLocked(key)) return 0.8
        if (durationSec > 0) {
            val posMs = progressMap[key]
            if (posMs != null && posMs >= durationSec * 1000L * NEAR_COMPLETE_PERCENT / 100) return 0.6
        }
        return 0.0
    }

    /**
     * R110 完播登记（FeedViewModel 在进度 ≥97% 清除续播点时调用）：
     * 完播是赛马最强正信号，但续播点一清"看完"证据就没了——把完播标记并进
     * 该 key 的曝光记录（曝光次数/时刻原样保留），随曝光表同一份落盘。
     */
    @Synchronized
    fun noteCompleted(key: String) {
        val table = exposureTable()
        val cur = table[key]
        if (cur?.completed == true) return // 已登记：零开销幂等
        table[key] = ExposureRecord(
            exposures = cur?.exposures ?: 0,
            lastTs = System.currentTimeMillis(),
            completed = true,
        )
        runCatching { store.saveExposureRecords(table) }
    }

    /**
     * 曝光记账：排序结果页输出时调用，页内每个 key 曝光 +1 并整体落盘。
     * 每页一次写（KB 级 JSON），loadMore 频率下写放大可忽略；落盘失败静默（下页重写）。
     */
    @Synchronized
    fun recordImpressions(keys: List<String>) {
        if (keys.isEmpty()) return
        val table = exposureTable()
        val now = System.currentTimeMillis()
        for (k in keys) {
            val cur = table[k]
            // R110：曝光 +1 原样保留完播标记——旧实现重建记录会把它抹掉
            table[k] = ExposureRecord((cur?.exposures ?: 0) + 1, now, cur?.completed ?: false)
        }
        runCatching { store.saveExposureRecords(table) }
    }

    /** 懒加载曝光表（仅 @Synchronized 方法内调用） */
    private fun exposureTable(): MutableMap<String, ExposureRecord> {
        exposureMap?.let { return it }
        val loaded = LinkedHashMap(store.loadExposureRecords())
        exposureMap = loaded
        return loaded
    }

    /** 刷新信号集（TTL 内直接复用；仅 @Synchronized 方法内调用） */
    private fun ensureSignalsLocked() {
        val now = System.currentTimeMillis()
        if (interactKeys != null && now - signalsTs < SIGNAL_TTL_MS) return
        val interact = HashSet<String>()
        runCatching { interact += store.loadLikes() }
        runCatching { store.loadFavorites().forEach { interact += it.key } }
        runCatching { store.listDownloads().forEach { interact += it.key } }
        progressMap = runCatching { store.loadProgressMap() }.getOrDefault(emptyMap())
        newChannelIds = runCatching { newChannelIdsProvider() }.getOrDefault(emptySet())
        interactKeys = interact
        signalsTs = now
    }

    /** 完播判定（R110）：该 key 在 ≥97% 清续播点时登记过完播标记 */
    private fun isCompletedLocked(key: String): Boolean =
        exposureTable()[key]?.completed == true

    /** 近完读判定：续播位置 ≥ 60% × 时长（durationSec≤0 的图文/音频恒 false，只能靠互动达标） */
    private fun isNearCompleteLocked(item: VideoItem): Boolean {
        if (item.durationSec <= 0) return false
        val posMs = progressMap[item.key] ?: return false
        return posMs >= item.durationSec * 1000L * NEAR_COMPLETE_PERCENT / 100
    }

    companion object {
        /** 赛马出局线：曝光达此次数仍不达标（无互动且无近完读）→ STALLED 降权 */
        const val MAX_EXPOSURES = 3

        /** 晋级乘区：达标条目打分上浮（对标"数据达标晋级更大流量池"） */
        const val PROMOTE_BOOST = 1.4

        /** 出局乘区：连败条目打分下调（对标"指标差就停止分发"） */
        const val STALLED_PENALTY = 0.7

        /** 近完读阈值（%）：续播位置 ≥ 60% × 时长视为"基本看完"（调研口径 完读率>60%） */
        const val NEAR_COMPLETE_PERCENT = 60

        /** 信号集 TTL（毫秒）：互动/进度/新频道集合的刷新间隔 */
        private const val SIGNAL_TTL_MS = 10_000L
    }
}

/**
 * ExposureTracker 的进程级注册点（五个 Holder 的统一口径见 FeedSource.kt 中
 * ChannelFatigueHolder 类注释开头的"Holder 总约"，此处只记本件差异）：
 *
 *  - 注册语义：**后注册生效**（R109 M1 起）：新实例直接顶位。
 *    旧口径"首个注册生效"在 VM 重建后留下死实例——新频道供给闭包仍抓着旧
 *    gateway（VM 已销毁），供给口径随旧网关一起失效/泄漏。后注册生效保证
 *    tracker 的供给闭包始终指向活着的注册方；曝光底账持久化在 exposure.json，
 *    换实例只丢 10s TTL 的信号内存缓存，首次取用自动回载，无数据损失。
 *  - 注册方/时机：WeightedAggregateFeedSource 构造时（FeedViewModel 默认源恒构造它），
 *    先于首次 rankWeightedPage 取用。
 *  - 未注册降级：赛马分层段整体跳过，排序链退化为无赛马版本（单测即此路径）。
 */
internal object ExposureTrackerHolder {
    @Volatile private var tracker: ExposureTracker? = null

    /** 注册：新实例直接顶位（语义与理由见类注释） */
    fun register(store: LocalStore, newChannelIdsProvider: () -> Set<Long>): ExposureTracker {
        val t = ExposureTracker(store, newChannelIdsProvider)
        tracker = t // @Volatile 单次写，天然线程安全
        return t
    }

    fun current(): ExposureTracker? = tracker
}
