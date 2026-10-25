package dev.tgfeed.core.feed

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.tgfeed.core.feed.DeepSearchIndex.TimeWindowQuery
import dev.tgfeed.core.td.FeedPage
import dev.tgfeed.core.td.FeedRequest
import dev.tgfeed.core.td.SystemStormGate
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.core.td.pursueChannelHistoryPage
import dev.tgfeed.core.td.pursueChannelRecentMediaPage
import dev.tgfeed.core.td.pursueEvents
import dev.tgfeed.core.td.pursueRecentChatIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "R229Pursue"

/**
 * R239 时间线 VM（首页一等 tab 的数据中枢；R229 v1 卡流三腿架构重构为扁平
 * 完整流四腿——用户修正①②③，设计定稿见 r239_notes D2~D4）。
 *
 * 独立小 VM——刻意不进共享 FeedViewModel（超热点文件错峰纪律保持）；key =
 * "pursue-gw"+identityHashCode(gateway)（PursueScreen 挂载处）。[deepIndex] 由
 * PursueScreen 注入**共享 FeedViewModel 的深索引实例**（同 key 取出——写回
 * 走同一把 writeLock 与爬虫串行，见 DeepSearchIndex.ingestBrowsedPage）。
 *
 * 数据模型：`List<VideoItem>` 扁平完整流，dateEpochSec 升序（index 0=最旧、
 * 末尾=最新——「最新在最下面」用户拍板不变量延续；无每频道上限——时间窗内
 * 符合条件的都展示）。
 *
 * 四腿编排：
 *  1. **快车道（Read-Through Cache 读侧）**：进 tab 首拉先查深索引
 *     [DeepSearchIndex.timeWindowItems]（72h 窗、已加入频道集、索引倒序毫秒级）
 *     ——用户看过/爬过的时段零 API 直出（网络腿渐进补足其余）。
 *  2. **正路（全频道渐进 bootstrap）**：[pursueRecentChatIds]（id-only 枚举
 *     TTL 5min≈150ms，全量 598 频道——R229 的 24 上限撤销）→ 并发 5 路
 *     [pursueChannelRecentMediaPage]（每频道 2 轮×50 条消息扫描、10s 单发超时、
 *     级联=0、媒体目标 [PURSUE_FETCH_PER_CHANNEL]）→ **先出先显**：每频道完成
 *     即投合并通道（coalesce 批量归并发布，不等 awaitAll 屏障）；顺带采集页尾
 *     游标（历史轮原料）。软截止 [PURSUE_SWEEP_DEADLINE_MS]：尾部沉寂频道
 *     超时让路（清单序=活跃前缀，先出的正是用户最关心的）。
 *  3. **实时推入（TDLib push）**：[pursueEvents]（UpdateNewMessage→媒体条目）
 *     逐条**即时插入**流尾（0 延迟上屏——用户修正③；30s tick 不再延迟上屏，
 *     只做滚动窗修剪+新鲜期检查）。新内容计数供 UI「N 条新内容」胶囊
 *     （用户不在底部时安静排队）。
 *  4. **历史轮（无限回看）**：UI 滑到流顶触发 [requestHistoryRound]——全部
 *     未到底频道并发 5 路 [pursueChannelHistoryPage]（每频道游标推进 1 页、
 *     maxRounds=1）→ 全局时间归并（先出先显）；**写回缓存**（Read-Through
 *     Cache 写侧）：每频道页结果经 [DeepSearchIndex.ingestBrowsedPage] 进
 *     索引（INSERT OR IGNORE+R228 解析+游标不推进+每日软顶——用户修正③，
 *     架构师风险表六对策落点）；网络轮空手 → 深索引 fallback 窗（本地供给）；
 *     全频道游标到底+索引无更早 = 诚实终态。
 *
 * **R208 开机风暴闸**：bootstrap/历史轮起跑前 `SystemStormGate.awaitClear`
 * （开机静默窗/系统高载窗内不拉取——R229 红线保持）。
 *
 * 前台门控：[setActive]（pageActive 驱动）——离场不 bootstrap 不翻历史（省电
 * 省账），重进场按 [PURSUE_BOOTSTRAP_FRESH_MS] 新鲜期决定补拉。Mock 演示网关：
 * 无被动流，bootstrap 走 listChannels+fetchVideos 假数据（演示模式自洽）。
 */
internal class PursueViewModel(
    private val gateway: TdGateway,
    private val deepIndex: DeepSearchIndex?,
) : ViewModel() {

    private val _items = MutableStateFlow<List<VideoItem>>(emptyList())

    /** 扁平完整流（**最新在最下面**——dateEpochSec 升序，index 0=最旧） */
    val items: StateFlow<List<VideoItem>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)

    /** bootstrap 正路进行中（空态 vs 加载态判据；渐进期流非空照常展示） */
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _bootstrapped = MutableStateFlow(false)

    /** 完成过至少一次 bootstrap（空态文案判据：拉过了且真空=真空态） */
    val bootstrapped: StateFlow<Boolean> = _bootstrapped.asStateFlow()

    private val _bootstrapProgress = MutableStateFlow<String?>(null)

    /** bootstrap 渐进进度（"N/总数"文案；null=不在飞——页头「正在更新」旁展示） */
    val bootstrapProgress: StateFlow<String?> = _bootstrapProgress.asStateFlow()

    private val _historyLoading = MutableStateFlow(false)

    /** 历史轮进行中（UI 顶缘细进度条） */
    val historyLoading: StateFlow<Boolean> = _historyLoading.asStateFlow()

    private val _historyExhausted = MutableStateFlow(false)

    /** 历史供给终态（全频道游标到底+索引无更早——UI 诚实「没有更早的内容了」） */
    val historyExhausted: StateFlow<Boolean> = _historyExhausted.asStateFlow()

    private val _newBelow = MutableStateFlow(0)

    /** 实时推入但用户未读的新内容数（底部胶囊「N 条新内容」；用户回底部清零） */
    val newBelow: StateFlow<Int> = _newBelow.asStateFlow()

    private val _recentLiveKeys = MutableStateFlow<Set<String>>(emptySet())

    /** 实时推入的新 key 集（新格滑入入场动效的判定原料；30s tick 清洗） */
    val recentLiveKeys: StateFlow<Set<String>> = _recentLiveKeys.asStateFlow()

    /** 流互斥：合并/实时插入/修剪/游标推进的串行化（StateFlow 发布原子性） */
    private val streamLock = Mutex()

    /** 历史轮游标：chatId → 已取到最老 messageId（页尾；0/缺省=未初始化） */
    private val cursors = ConcurrentHashMap<Long, Long>()

    /** 已到底频道集（游标 nextCursor=null——该频道 TG 历史翻完） */
    private val channelDone = ConcurrentHashMap.newKeySet<Long>()

    /** 已加入频道全集（索引 fallback 窗的频道过滤集；枚举时定格） */
    @Volatile
    private var joinedChatIds: List<Long> = emptyList()

    /** 历史层底（秒）：用户向前翻过的最旧时刻（滚动修剪不裁历史的地板） */
    @Volatile
    private var historyFloorSec = 0L

    @Volatile
    private var active = false

    @Volatile
    private var bootstrappedAtMs = 0L

    /** bootstrap 正路在飞标记（历史轮/强刷的错峰闸） */
    @Volatile
    private var sweepInFlight = false

    /** 用户是否在流底部（胶囊计数/清零的判据；UI 上报） */
    @Volatile
    private var userAtBottom = true

    /** 轮询循环只起一次（VM 生命周期内常驻，active 门控节拍） */
    private var loopStarted = false

    /** 渐进合并通道：每频道完成页投此（merger 协程 coalesce 批量归并——先出先显） */
    private val mergeCh = Channel<List<VideoItem>>(capacity = Channel.UNLIMITED)

    init {
        // merger 协程：coalesce 消费（一批多页合一次归并——渐进期发布频率可控）
        viewModelScope.launch(Dispatchers.IO) {
            for (batch in mergeCh) {
                val all = ArrayList<VideoItem>(batch.size * 4)
                all += batch
                while (true) {
                    val more = mergeCh.tryReceive().getOrNull() ?: break
                    all += more
                }
                runCatching { mergeIntoStream(all, live = false) }
                    .onFailure { Log.w(TAG, "时间线归并失败（跳过本批）: ${it.message}") }
            }
        }
        // 实时推入（真网关；Mock 无更新流——bootstrap 假数据自洽）
        val lib = gateway as? TdLibGateway
        if (lib != null) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { lib.pursueEvents() }
                    .getOrDefault(MutableSharedFlow<VideoItem>()) // 空流兜底：订阅失败静默
                    .collect { item ->
                        if (item.chatId < 0L && item.dateEpochSec > 0) {
                            runCatching { mergeIntoStream(listOf(item), live = true) }
                                .onFailure { Log.w(TAG, "时间线实时推入失败（跳过）: ${it.message}") }
                            // R286 新帖快通道：实时推入的新帖顺手入索引——「刚发的」
                            // 秒级可搜（幂等管道+日软顶，失败静默绝不影响主链路）
                            deepIndex?.let { idx ->
                                runCatching { idx.ingestBrowsedPages(listOf(item)) }
                            }
                        }
                    }
            }
        }
    }

    /**
     * 前台门控（PursueScreen 的 LaunchedEffect(pageActive) 驱动）：true = 时间线
     * tab 在台前（起循环+按新鲜期补拉）；false = 离场（循环转空转，拉取/翻历史
     * 停拍——实时订阅保持挂着，回来时新内容并入胶囊计数）。
     */
    fun setActive(a: Boolean) {
        active = a
        if (a) ensureLoop()
    }

    /** UI 上报用户是否在流底部（胶囊清零判据——回底部=新内容已见） */
    fun setUserAtBottom(atBottom: Boolean) {
        userAtBottom = atBottom
        if (atBottom) _newBelow.value = 0
    }

    /** 胶囊点按：清零计数（滚动到底由 UI 执行） */
    fun consumeNewBelow() {
        _newBelow.value = 0
    }

    /** 双击刷新令（timelineRefreshRequest nonce 消费）：强刷=绕过新鲜期立刻重扫 */
    fun requestRefresh() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { bootstrapSweep() }.onFailure {
                Log.w(TAG, "时间线强刷失败: ${it.message}")
            }
        }
    }

    /**
     * 历史轮入口（UI 滑到流顶触发）：全频道游标推进一轮+写回索引+网络空手时
     * 索引 fallback。幂等闸：在飞/终态/bootstrap 在飞时静默跳过。
     */
    fun requestHistoryRound() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { historyRound() }.onFailure {
                Log.w(TAG, "时间线历史轮失败: ${it.message}")
            }
        }
    }

    private fun ensureLoop() {
        if (loopStarted) return
        loopStarted = true
        viewModelScope.launch(Dispatchers.IO) {
            // R208 开机风暴闸：开机窗/系统高载窗内不轮询（awaitClear 直通零开销，
            // 持有期每 5s 复查，超限兜底放行——与深索引/预热/治理同款单一闸源）
            runCatching { SystemStormGate.awaitClear("时间线轮询") }
                .onFailure { Log.w(TAG, "时间线风暴闸等待异常（按放行处理）: ${it.message}") }
            while (true) {
                if (!active) {
                    delay(400)
                    continue
                }
                if (bootstrappedAtMs == 0L ||
                    System.currentTimeMillis() - bootstrappedAtMs > PURSUE_BOOTSTRAP_FRESH_MS
                ) {
                    runCatching { bootstrapSweep() }.onFailure {
                        Log.w(TAG, "时间线 bootstrap 失败（下拍重试）: ${it.message}")
                    }
                }
                delay(PURSUE_TICK_MS)
                pruneTick()
            }
        }
    }

    /**
     * 30s tick（用户修正③后的职责）：纯本地——滚动窗修剪（历史层底地板口径）+
     * 滑入标记清洗（已组合格动效已播完，标记退役防滚动回看误播）。
     */
    private suspend fun pruneTick() {
        streamLock.withLock {
            val old = _items.value
            val nowSec = System.currentTimeMillis() / 1000L
            val cutoff = pursueTimelineCutoffSec(nowSec, PURSUE_TIMELINE_WINDOW_SEC, historyFloorSec)
            val merged = pursueTimelineMerge(old, emptyList(), cutoff)
            if (merged != old) _items.value = merged
        }
        if (_recentLiveKeys.value.isNotEmpty()) {
            // 标记只服务「新格首次组合」：30s 后新到格已组合完毕，标记退役
            // （滚动回看的老格重组合不播入场——只有真·实时新内容才滑入）
            _recentLiveKeys.value = emptySet()
        }
    }

    /**
     * 归并本体（bootstrap/快车道/实时推入/历史轮的单一入流口径——merger 协程
     * 与实时 collector 都走这里，[streamLock] 串行）：去重来者覆盖（富字段版本
     * 胜）→ cutoff 过滤 → 升序 → 硬顶裁旧。live=true 时做新内容计数+滑入标记。
     */
    private suspend fun mergeIntoStream(incoming: List<VideoItem>, live: Boolean) {
        if (incoming.isEmpty()) return
        streamLock.withLock {
            val old = _items.value
            val nowSec = System.currentTimeMillis() / 1000L
            val cutoff = pursueTimelineCutoffSec(nowSec, PURSUE_TIMELINE_WINDOW_SEC, historyFloorSec)
            val merged = pursueTimelineMerge(old, incoming, cutoff)
            _items.value = merged
            if (live) {
                val added = pursueTimelineAppendCount(old, merged)
                if (added > 0 && !userAtBottom) _newBelow.value += added
                if (incoming.isNotEmpty()) {
                    val keys = _recentLiveKeys.value + incoming.mapTo(HashSet(incoming.size)) { it.key }
                    _recentLiveKeys.value = if (keys.size > 256) {
                        keys.drop(keys.size - 256).toSet()
                    } else {
                        keys
                    }
                }
                if (added > 0) {
                    Log.i(TAG, "时间线实时推入：+${added} 新内容（未读=${_newBelow.value} 流=${merged.size}）")
                }
            }
            // 历史层底推进（流最旧端变旧=用户翻过更早——修剪地板随行）：
            // 首次出现窗下内容（历史轮产物）即立地板；此后单调取更旧。
            val oldest = merged.firstOrNull()?.dateEpochSec?.toLong() ?: 0L
            if (oldest > 0L) {
                historyFloorSec = if (historyFloorSec <= 0L) {
                    if (oldest < nowSec - PURSUE_TIMELINE_WINDOW_SEC) oldest else historyFloorSec
                } else {
                    minOf(historyFloorSec, oldest)
                }
            }
        }
    }

    /**
     * bootstrap（快车道+正路全频道渐进）。先出先显：索引命中即出（毫秒级），
     * 网络腿每频道完成即归并发布；尾部频道软截止让路。游标采集=历史轮原料。
     */
    private suspend fun bootstrapSweep() {
        if (sweepInFlight) return
        sweepInFlight = true
        _loading.value = true
        try {
            // R208 开机风暴闸（R229 红线保持：开机窗/高载窗不拉）
            runCatching { SystemStormGate.awaitClear("时间线 bootstrap") }
                .onFailure { Log.w(TAG, "时间线风暴闸等待异常（按放行处理）: ${it.message}") }
            val lib = gateway as? TdLibGateway
            if (lib != null) {
                // 频道全集（id-only 枚举 TTL 5min；清单序=活跃前缀=渐进序）
                val chatIds = lib.pursueRecentChatIds(Int.MAX_VALUE)
                joinedChatIds = chatIds
                // ---- 快车道（Read-Through Cache 读侧）：索引 72h 窗毫秒级即出 ----
                if (deepIndex != null && chatIds.isNotEmpty()) {
                    val nowSec = System.currentTimeMillis() / 1000L
                    val fast = withContext(Dispatchers.IO) {
                        runCatching {
                            deepIndex.timeWindowItems(
                                TimeWindowQuery(
                                    fromSec = nowSec - PURSUE_TIMELINE_WINDOW_SEC,
                                    toSec = nowSec,
                                    channelIds = chatIds.toSet(),
                                    limit = TimeWindowPolicy.QUERY_LIMIT_MAX,
                                ),
                            )
                        }.getOrDefault(emptyList())
                    }
                    if (fast.isNotEmpty()) {
                        mergeIntoStream(fast, live = false)
                        _bootstrapped.value = true
                        Log.i(TAG, "时间线快车道：索引即出 ${fast.size} 条（零 API）")
                    }
                }
                // ---- 正路：并发 5 渐进扫描（先出先显，游标顺带采集） ----
                val deadlineAt = System.currentTimeMillis() + PURSUE_SWEEP_DEADLINE_MS
                val total = chatIds.size
                val done = AtomicInteger(0)
                val lastLogged = AtomicInteger(-1)
                coroutineScope {
                    chatIds.map { id ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                if (System.currentTimeMillis() > deadlineAt) return@withPermit // 尾部沉寂频道让路
                                val page = lib.pursueChannelRecentMediaPage(id, PURSUE_FETCH_PER_CHANNEL)
                                if (page != null) {
                                    if (page.nextCursor == null) channelDone += id
                                    else page.nextCursor.toLongOrNull()?.let { cursors[id] = it }
                                    if (page.items.isNotEmpty()) mergeCh.trySend(page.items)
                                }
                                val d = done.incrementAndGet()
                                // 进度发布节流：每 4% 或末尾一行（页头小字消费）
                                val step = maxOf(1, total / 25)
                                if (d == total || d - lastLogged.get() >= step) {
                                    lastLogged.set(d)
                                    _bootstrapProgress.value = "$d/$total"
                                }
                            }
                        }
                    }.awaitAll()
                }
                _bootstrapped.value = true
                Log.i(
                    TAG,
                    "时间线 bootstrap 完成：频道=${done.get()}/$total 到底=${channelDone.size} 流=${_items.value.size}" +
                        "（软截止${if (done.get() < total) "尾部跳过" else "全覆盖"}）",
                )
            } else {
                // Mock 演示模式：listChannels 假频道 + fetchVideos 假媒体（无被动流，静态流）
                val mockItems: List<VideoItem> = gateway.listChannels()
                    .take(6)
                    .map { ch ->
                        runCatching {
                            gateway.fetchVideos(FeedRequest(channelId = ch.id), null, PURSUE_ITEMS_PER_CHANNEL)
                        }.getOrDefault(FeedPage(emptyList(), null)).items
                    }
                    .flatten()
                val nowSec = System.currentTimeMillis() / 1000L
                _items.value = pursueTimelineMerge(
                    emptyList(), mockItems,
                    pursueTimelineCutoffSec(nowSec, PURSUE_TIMELINE_WINDOW_SEC, 0L),
                )
                _bootstrapped.value = true
            }
            bootstrappedAtMs = System.currentTimeMillis()
        } finally {
            sweepInFlight = false
            _loading.value = false
            _bootstrapProgress.value = null
        }
    }

    /**
     * 历史轮（用户修正②：直连 GetChatHistory 每频道游标推进）：全部未到底频道
     * 并发 5 路各取 1 页（50 条消息扫描）→ 渐进归并；每页结果写回深索引
     * （Read-Through Cache 写侧——用户修正③）。网络空手 → 索引 fallback；
     * 全游标到底+索引无更早 → 诚实终态。
     */
    private suspend fun historyRound() {
        if (!active || sweepInFlight || _historyLoading.value || _historyExhausted.value) return
        _historyLoading.value = true
        try {
            runCatching { SystemStormGate.awaitClear("时间线历史轮") }
                .onFailure { Log.w(TAG, "时间线历史闸等待异常（按放行处理）: ${it.message}") }
            val lib = gateway as? TdLibGateway
            if (lib == null) {
                _historyExhausted.value = true // 演示模式无历史供给
                return
            }
            val pending = cursors.entries.filter { it.key !in channelDone }
            if (pending.isEmpty()) {
                // 网络游标全部到底——索引还可能有更早内容（fallback 兜底一轮再终态）
                tryIndexFallbackOrExhaust()
                return
            }
            val netItems = AtomicInteger(0)
            coroutineScope {
                pending.map { (chatId, from) ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val page = lib.pursueChannelHistoryPage(chatId, from, PURSUE_HISTORY_ROUND_ITEMS)
                            if (page == null) return@withPermit // 单频道失败：游标不动，下轮重试
                            if (page.nextCursor == null) channelDone += chatId
                            else page.nextCursor.toLongOrNull()?.let { cursors[chatId] = it }
                            if (page.items.isNotEmpty()) {
                                netItems.addAndGet(page.items.size)
                                // Read-Through Cache 写侧（用户修正③）：查到的数据顺便进索引
                                // （下次同时段零 API；失败/软顶静默——绝不影响主链路）
                                deepIndex?.let { idx ->
                                    runCatching { idx.ingestBrowsedPage(chatId, page.items) }
                                }
                                mergeCh.trySend(page.items)
                            }
                        }
                    }
                }.awaitAll()
            }
            if (netItems.get() > 0) {
                Log.i(
                    TAG,
                    "时间线历史轮：+${netItems.get()} 条 频道=${pending.size} 累计到底=${channelDone.size}",
                )
            } else {
                // 网络零产出（故障或窗内无媒体）→ 索引 fallback（本地供给）
                tryIndexFallbackOrExhaust()
            }
        } finally {
            _historyLoading.value = false
        }
    }

    /**
     * 深索引 fallback（用户修正②的 c 腿）：从当前流最旧端向下按
     * [PURSUE_INDEX_FALLBACK_STEP_SEC] 逐窗找本地内容（至多
     * [PURSUE_INDEX_FALLBACK_MAX_WINDOWS] 窗防长空转）；找到即归并（零 API）；
     * 找不到且索引量程也无更早 → 诚实终态（historyExhausted=true）。
     */
    private suspend fun tryIndexFallbackOrExhaust() {
        val idx = deepIndex ?: run {
            _historyExhausted.value = true
            return
        }
        val oldest = streamLock.withLock { _items.value.firstOrNull()?.dateEpochSec?.toLong() ?: 0L }
        if (oldest <= 0L) {
            _historyExhausted.value = true
            return
        }
        var floor = oldest
        var found = false
        var probed = 0
        while (!found && probed < PURSUE_INDEX_FALLBACK_MAX_WINDOWS) {
            val w = pursueHistoryFallbackWindow(floor, PURSUE_INDEX_FALLBACK_STEP_SEC) ?: break
            val hits = withContext(Dispatchers.IO) {
                runCatching {
                    idx.timeWindowItems(
                        TimeWindowQuery(
                            fromSec = w.first,
                            toSec = w.second,
                            channelIds = joinedChatIds.toSet(),
                            limit = TimeWindowPolicy.QUERY_LIMIT_MAX,
                        ),
                    )
                }.getOrDefault(emptyList())
            }
            if (hits.isNotEmpty()) {
                mergeIntoStream(hits, live = false)
                found = true
                Log.i(TAG, "时间线索引 fallback：本地窗命中 ${hits.size} 条（零 API）")
                break
            }
            floor = w.first
            probed++
        }
        if (!found) {
            // 索引也无更早：量程判定（dateBounds 最旧端仍在当前最旧之下=索引还有
            // 没探到的更早窗——保持可重试；量程以内无更早=真终态）
            val bounds = withContext(Dispatchers.IO) {
                runCatching { idx.dateBounds() }.getOrNull()
            }
            val indexOldest = bounds?.first ?: 0L
            _historyExhausted.value = indexOldest <= 0L || indexOldest >= floor - PURSUE_INDEX_FALLBACK_STEP_SEC
            if (_historyExhausted.value) {
                Log.i(TAG, "时间线历史终态：游标/索引双到底（索引最旧=$indexOldest 流最旧=$floor）")
            }
        }
    }

    override fun onCleared() {
        mergeCh.close()
        super.onCleared()
    }

    private companion object {
        /** 全频道扫描并发路数（任务口径「按频道并发 5 路」；R229/R232 先例同值） */
        val semaphore = Semaphore(5)
    }
}

/** PursueScreen 挂载用工厂（与 FeedViewModelFactory 同款；key 见 PursueScreen。
 *  internal：流模型 VideoItem 是公共件，VM 全家保持同可见档；deepIndex 由宿主
 *  注入共享 FeedViewModel 的实例（写回与爬虫同锁串行） */
internal class PursueViewModelFactory(
    private val gateway: TdGateway,
    private val deepIndex: DeepSearchIndex?,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        PursueViewModel(gateway, deepIndex) as T
}
