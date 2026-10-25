package dev.tgfeed.core.feed

import android.content.Intent
import androidx.lifecycle.viewModelScope
import dev.tgfeed.core.prefs.AppPrefs
import dev.tgfeed.core.td.MockTdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun FeedViewModel.refreshIndexStatus(totalChats: Int = 0, building: Boolean = false) {
    viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val (chans, items) = deepIndex.totalStats()
            // 口径对齐（修"已索引 597 > 总数 592"倒挂）：分母=本轮枚举数，分子=索引库历史累计
            // （含已退出/本轮没枚举到的频道——其内容仍可搜，索引不忘）。两侧口径不同会倒挂，
            // 分母取 max(枚举, 已索引)：可搜范围本来就是二者的并集语义
            _indexStatus.value = IndexStatus(
                chans, items, building, maxOf(totalChats, chans), loaded = true,
                dbBytes = deepIndex.dbSizeBytes(),
                round = autoIndexRound.get(),
                sampledChannels = deepIndex.sampledChannelCount(), // R46：口径诚实（全量/采样可见）
                repostItems = deepIndex.repostCount(), // R266 v11 D6：诚实副标（含转发 M 条）
            )
        }
    }
}

/**
 * 全频道持续爬取调度（R45 结构修复：枚举/爬取双节拍器解耦，根因 R1——
 * "几天只建 197 个"的直接成因）。
 *
 * 旧结构病根：每轮"轮首 GetChats 枚举 → coroutineScope 屏障等全部频道爬完 → 才进
 * 下一轮"；而 TDLib GetChats 按需加载、枚举"逐轮增长直至饱和"只在轮首发生——首轮
 * 枚举前缀（197 个）里有怪兽频道爬不完，屏障就永不放行第二轮，永远枚举不到第 198 个。
 *
 * 新结构（两个独立节拍器，互不等待）：
 *  1. 枚举 ticker：每 [INDEX_ROUND_INTERVAL_MS] 新鲜枚举一轮（GetChats 重复调用推动
 *     TDLib 加载更多会话直至饱和），过滤广告黑名单后按缺口优先级入队
 *     （未爬过 → 未到底 → 已到底且头部刷新到期）；已入队/在爬的去重；
 *  2. 爬取 ticker：持续从队列取频道派工，并发度 AIMD 口径不变（[crawlConcurrency]
 *     起步 4，无 flood 节拍 +1 路、上限 8，出 flood 降回 4；解耦后没有"轮末"，
 *     按 [INDEX_AIMD_TICK_MS] 节拍比对 flood 计数调节）。首轮爬多久都不挡新会话入队。
 *
 * 配套（同包 R45 项）：
 *  - 爬取队列排除 AdChannelClassifier 运行时拉黑频道（广告频道没有索引价值，白烧请求）；
 *  - 已到底但头部刷新未到期的频道直接不入队（headRefreshDueChannelIds 预筛，
 *    连"派工后被节流跳过"的空转都省掉）；
 *  - bulk 构建期挂前台服务（IndexCrawlService，低打扰通知）保活进程（根因 R4），
 *    队列清空即摘牌；设置关掉自动索引则在飞频道收尾后整体退出。
 */
internal fun FeedViewModel.autoBuildDeepIndex() {
    if (!autoIndexStarted.compareAndSet(false, true)) return
    viewModelScope.launch(Dispatchers.IO) {
        if (!dev.tgfeed.core.prefs.AppPrefs(app).autoDeepIndex) {
            refreshIndexStatus() // 没开也显示现状
            return@launch
        }
        if (gateway is dev.tgfeed.core.td.MockTdGateway) return@launch
        // R208 开机风暴闸（P0 治理·时机闸）：系统开机静默期/负载退避持有中不
        // 起跑双节拍器——华为平板事故实锤：异常重启后 ROM 恢复前台应用，本调度
        // 器开机 73s 跑 72.6s=100% 占空比+7.3G 虚存，与系统开机风暴（load 35）
        // 叠加触发 watchdog 复位。闸=系统 uptime 静默窗（默认 5min，用户平时
        // 开 app 系统早已运行多小时→零延迟）+ /proc/loadavg 负载退避（零权限）。
        // **R158 语义零改动**：只延后起跑时机，起跑后的枚举/派工/预算/AIMD/
        // 退避全部照旧（派工侧另有一道同源闸兜底运行中风暴，见下方 stormHeld）。
        dev.tgfeed.core.td.SystemStormGate.awaitClear("深索引双节拍器")
        // R191 索引调度器存活标记（起此终 finally）：点按「深度索引」时选建库路径——
        // 活着走优先车道（预算内插队），没跑走直接建库协程
        indexSchedulerAlive = true
        // 待爬队列（无界）：枚举 ticker 投喂、爬取 ticker 取餐；
        // queued（已入队未派工）/inFlight（在爬）双集合保证同一频道同时在飞最多一份
        val crawlQueue = kotlinx.coroutines.channels.Channel<Long>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val queued = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        val enqueuedTotal = java.util.concurrent.atomic.AtomicInteger(0)
        val doneTotal = java.util.concurrent.atomic.AtomicInteger(0)
        var lastEnumTotal = 0
        // R163 B4：探索车道（机制②ε-greedy）——分档开启时全新宇宙（T3 候选）走独立
        // 队列，巡游派工槽按配额轮转取用（探索 30% / 常规维护 70%）；分档关时该队列
        // 恒空（枚举拍不喂），一切行为与现状一致。探索槽空时回退常规队列不空转
        val exploreQueue = kotlinx.coroutines.channels.Channel<Long>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val exploreQueued = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        // R284 时效优先车道：自毁群（messageAutoDeleteTime ≤7 天，判定唯一出处
        // CrawlThroughputPolicy.isTtlUrgentLane）显著提权——优先车道之后、巡游/探索
        // 之前派工；缺口档（0/1/2）语义不变，时效是独立的加速维度（与分档正交）
        val ttlUrgentQueue = kotlinx.coroutines.channels.Channel<Long>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val ttlUrgentQueued = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        val roamDispatchOrdinal = java.util.concurrent.atomic.AtomicInteger(0)
        // 前台服务状态去重：只在（忙闲, 完成数, 入队总数）变化时才发 Intent
        var fgsLastState: Triple<Boolean, Int, Int>? = null
        fun syncForegroundService() {
            // R208：风暴闸持有期不挂「空壳 FGS」——在飞爬取照旧保活（FGS 服务真
            // 实派工），但纯排队零派工时摘牌静默（开机窗里 FGS 挂牌=高优先级进程
            // 上桌抢资源，事故形态之一）；风暴解除下一拍自动复挂，语义只动时机。
            val stormHeldFgs = !dev.tgfeed.core.td.SystemStormGate.clearNow()
            val busy = inFlight.isNotEmpty() || (queued.isNotEmpty() && !stormHeldFgs)
            val state = Triple(busy, doneTotal.get(), enqueuedTotal.get())
            if (state == fgsLastState) return
            val wasBusy = fgsLastState?.first ?: false
            fgsLastState = state
            if (busy) {
                if (!wasBusy) android.util.Log.d("FeedViewModel", "索引爬取前台服务挂牌（退后台继续爬）")
                IndexCrawlService.startOrUpdate(app, state.second, state.third)
            } else {
                if (wasBusy) android.util.Log.d("FeedViewModel", "索引爬取队列清空，前台服务摘牌")
                IndexCrawlService.stop(app)
            }
        }
        // ── 节拍器一：枚举（独立节奏刷新清单入队，不等爬取侧）──────────────
        val enumJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                if (!dev.tgfeed.core.prefs.AppPrefs(app).autoDeepIndex) break
                // 全量索引覆盖频道+群组+私聊（与搜索同一口径 listSearchableChats；
                // 只建频道的话群组里的内容永远不进索引）
                val channels = runCatching {
                    (gateway as? dev.tgfeed.core.td.TdLibGateway)?.listSearchableChats()
                        ?: gateway.listChannels()
                }.getOrDefault(emptyList())
                if (channels.isEmpty()) {
                    // 枚举失败/网关未就绪：短歇重试，不退出循环
                    android.util.Log.w(
                        "FeedViewModel",
                        "深度索引枚举节拍：会话枚举为空，${INDEX_ROUND_RETRY_MS / 1000}s 后重试",
                    )
                    delay(INDEX_ROUND_RETRY_MS)
                    continue
                }
                lastEnumTotal = channels.size
                // R45：排除广告黑名单频道（运行时拉黑快照；广告频道没有索引价值还白烧请求）
                val eligible = channels.filterNot { AdChannelClassifier.isBlockedRuntime(it.id, it.username) }
                val blockedCount = channels.size - eligible.size
                // R46：爬取泳道预算额度每拍从设置刷新（免重启生效；FloodBudget 滑窗口径）
                // R255：读取失败兜底值与新默认 6000 对齐（旧 2000 是 R46 突发形态的安全水位，
                // 平滑配速后突发峰值反而低于旧形态——见 AppPrefs.floodCrawlPerHour KDoc）
                crawlBudget.maxOpsPerHour = runCatching { prefs.floodCrawlPerHour }.getOrDefault(6000)
                // R46 方案 b：白嫖名单 = 近 LOCAL_GRAB_LOOKBACK_MS 观看事件涉及的频道（纯本地读）
                recentBrowsedChatIds = runCatching {
                    val nowMs = System.currentTimeMillis()
                    store.loadWatchEvents()
                        .filter { nowMs - it.ts < LOCAL_GRAB_LOOKBACK_MS }
                        .mapTo(HashSet()) { it.chatId }
                }.getOrDefault(emptySet())
                // R40 缺口优先级不变：未爬过(0) → 未到底(1) → 已到底且头部刷新到期(2)；
                // R45：已到底但刷新未到期的直接不入队（节流预筛）；
                // R46：白嫖通道建过 meta 行但还没真爬的频道仍按"未爬过"档（untouchedChannelIds），
                // 防白嫖建行把用户浏览过的频道降档（它们恰是最想搜的）
                // R163 B4：头刷到期预筛带分档配置（T0 1h/其余 6h；分档关=原单一阈值现状）
                val tiering = indexTiering
                // R284 时效感知①落库先行：枚举顺手把 GetChat 现成字段
                // （messageAutoDeleteTime）批量落 meta（零新增请求；值变化才写）——
                // 先落库再算头刷到期，时效覆盖闸（headRefreshDueChannelIds 内
                // ttlAwareHeadRefreshIntervalMs）首拍即见真 TTL，不自瘸一拍
                val ttlByChat = eligible.associate { it.id to it.ttlSec }
                val ttlChanged = runCatching { deepIndex.noteChatTtls(ttlByChat) }.getOrDefault(0)
                // R286 活跃新鲜度①落库先行（同款零新增请求）：服务器最新帖秒落
                // meta.lastMsgSec——活跃度头刷提频闸（headRefreshDueChannelIds 内
                // activityAwareHeadRefreshIntervalMs）首拍即见真值；只更新已有行
                // 不建行（防枚举宇宙灌成 untouched 污染缺口档）
                val lastMsgByChat = eligible.mapNotNull { ch ->
                    ch.lastMessageEpochSec.takeIf { it > 0L }?.let { ch.id to it }
                }.toMap()
                val lastMsgChanged = runCatching { deepIndex.noteChatLastMessages(lastMsgByChat) }.getOrDefault(0)
                val crawledIds = deepIndex.indexedChannelIds()
                val unfinishedIds = deepIndex.unfinishedChannelIds()
                val untouchedIds = deepIndex.untouchedChannelIds()
                val headDueIds = deepIndex.headRefreshDueChannelIds(System.currentTimeMillis(), tiering)
                // R163 B4：已落档表（探索/常规分流判定用——T0 订阅频道即使未爬也不进探索车道）
                val knownTiers = if (tiering.enabled) {
                    runCatching { deepIndex.tiers() }.getOrDefault(emptyMap())
                } else {
                    emptyMap()
                }
                // R284 时效感知②排序：主键缺口档（0/1/2）不变，副键时效紧迫度降序
                // （双曲线 TTL 越短越紧迫 + 新鲜度加成——CrawlThroughputPolicy 唯一出处）；
                // TTL ≤7 天的自毁群进时效优先车道（显著提权，探索车道分流对其让路）
                val nowEpochSec = System.currentTimeMillis() / 1_000L
                val ordered = eligible.mapNotNull { ch ->
                    val prio = when {
                        ch.id !in crawledIds || ch.id in untouchedIds -> 0
                        ch.id in unfinishedIds -> 1
                        ch.id in headDueIds -> 2
                        else -> return@mapNotNull null // 已到底且刷新未到期：本轮不入队
                    }
                    Triple(prio, ch.id, CrawlThroughputPolicy.ttlUrgencyScore(ch.ttlSec, ch.lastMessageEpochSec, nowEpochSec))
                }.sortedWith(compareBy({ it.first }, { -it.third }))
                var newCount = 0
                var exploreCount = 0
                var ttlUrgentCount = 0
                for ((prio, id, _) in ordered) {
                    // R284 时效车道优先于探索分流：T3 待探索群若 7 天自毁也要先抢爬
                    // （时效=独立于 tier 的加速维度）；非紧急的照 R163 B4 原车道分流
                    val ttlLane = CrawlThroughputPolicy.isTtlUrgentLane(ttlByChat[id] ?: 0)
                    // R163 B4 车道分流：分档开时 prio 0（全新宇宙=T3 候选）进探索车道
                    //（探针页预算限成本 + ε-greedy 配额曝光）；T0 已落档的（订阅补全在途）
                    // 与分档关时照走常规队列（现状）。T3 无数据不入常规队与探索分流是
                    // 同一件事的两面（regularLaneEligible 口径）
                    val exploreLane = !ttlLane && tiering.enabled && prio == 0 &&
                        knownTiers[id] != IndexTierPolicy.IndexTier.T0
                    val target = when {
                        ttlLane -> ttlUrgentQueued
                        exploreLane -> exploreQueued
                        else -> queued
                    }
                    if (id !in inFlight && target.add(id)) {
                        (when {
                            ttlLane -> ttlUrgentQueue
                            exploreLane -> exploreQueue
                            else -> crawlQueue
                        }).send(id)
                        enqueuedTotal.incrementAndGet()
                        when {
                            ttlLane -> ttlUrgentCount++
                            exploreLane -> exploreCount++
                            else -> newCount++
                        }
                    }
                }
                val tick = autoIndexRound.incrementAndGet() // 外露给设置页状态区（"第 N 轮"= 枚举节拍数）
                refreshIndexStatus(totalChats = channels.size, building = true)
                // R286 新鲜度观测（②新鲜度度量的日志出口）：已到底频道的活跃/滞后
                // 分布——滞后=服务器有新帖但索引头落后超告警线（isFreshnessLagging
                // 唯一判定）。纯读诊断，失败静默 null 不影响主拍
                val freshness = runCatching {
                    deepIndex.freshnessStats(System.currentTimeMillis(), tiering)
                }.getOrNull()
                android.util.Log.d(
                    "FeedViewModel",
                    "深度索引枚举节拍 #$tick：会话 ${channels.size} 个 排除黑名单 $blockedCount 个 " +
                        "新入队 $newCount 个 探索车道 $exploreCount 个 " +
                        "排队=${queued.size} 探索排队=${exploreQueued.size} 在爬=${inFlight.size}（解耦：不等爬完才枚举）" +
                        "｜爬取泳道预算余量=${crawlBudget.remaining()}/${crawlBudget.maxOpsPerHour} 次/小时" +
                        " 白嫖名单=${recentBrowsedChatIds.size} 个" +
                        "｜时效车道 $ttlUrgentCount 个（时效排队=${ttlUrgentQueued.size}，TTL 落库变更 $ttlChanged 个）" +
                        "｜活跃度落库变更 $lastMsgChanged 个" +
                        (freshness?.let { f ->
                            "｜新鲜度：到底 ${f.tracked} 活跃 ${f.active} 滞后 ${f.lagging}" +
                                "（最久落后 ${f.worstLagSec / 3_600}h）"
                        } ?: "") +
                        if (tiering.enabled) "｜分档开：落档 ${knownTiers.size} 个" else "",
                )
                delay(INDEX_ROUND_INTERVAL_MS)
            }
        }
        try {
            // ── 节拍器二：爬取（持续从队列取频道派工，不等枚举侧）──────────
            var lastAimdTickAt = System.currentTimeMillis()
            var floodsAtTick = deepIndex.floodEventCount()
            // R163 B4：时机饿死状态（会话级——level 不持久化，重启回 0=最严格条件保守向；
            // 窗口累计口径 =「自上次定级以来的 L0 窗口（充电+闲置+非计量）开放时长」，
            // 每 24h 定级重估一次后清零——滚动 24h 的诚实近似，见 IndexCrawlGate.nextStarveLevel）
            var starveLevel = 0
            var starveLevelSinceMs = System.currentTimeMillis()
            var l0WindowAccumMs = 0L
            var lastGateEvalAt = System.currentTimeMillis()
            // R165：派工退避状态沿（进/出干涸各打一行日志；干涸判定本身每拍现算）
            var dispatchHeldAnnounced = false
            android.util.Log.d(
                "FeedViewModel",
                "深度索引双节拍器启动：枚举 ${INDEX_ROUND_INTERVAL_MS / 60_000}min/拍 + " +
                    "爬取持续派工 并发=$crawlConcurrency（bulk 期前台服务保活）" +
                    "｜R163 分档=${if (indexTiering.enabled) "开" else "关"} " +
                    "智能时机=${if (crawlTiming.smartTiming) "开" else "关"}（默认全关=现状等效）",
            )
            // 派工执行体（优先/巡游两车道共用）：评估落档 → 白嫖 → 档位化爬取
            fun dispatchCrawl(chatId: Long) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        // R163 B4：派工前评估落档（分档开时；毫秒级只读聚合——每频道一次，
                        // 成本摊到派工自然节奏；评估升档 urgent 的走优先车道 M1/M9 路径；
                        // R191 钉住频道短路恒 T0）
                        if (indexTiering.enabled) runCatching { evaluateAndSetTierImpl(chatId) }
                        // R191 行内进度台账钩子（只有钉住频道有条目——全库常规爬取零扰动）
                        onManualIndexRowStartImpl(chatId)
                        // R46 方案 b：近期浏览频道先走本地缓存白嫖（onlyLocal 零网络，
                        // 只采不推进游标），有网络的正式爬取照常紧随其后
                        if (chatId in recentBrowsedChatIds) {
                            (gateway as? dev.tgfeed.core.td.TdLibGateway)?.let { td ->
                                runCatching { deepIndex.harvestLocalCache(td, chatId) }
                            }
                        }
                        // R163 B4：分档配置透传（分档关=LEGACY 现状等效四路径）；
                        // R191：onProgress 接行台账（钉住频道行内「已索引 N 条」）
                        runCatching { deepIndex.indexChannel(gateway, chatId, tiering = indexTiering) { n ->
                            onManualIndexRowProgressImpl(chatId, n)
                        } }
                    } finally {
                        inFlight.remove(chatId)
                        val doneN = doneTotal.incrementAndGet()
                        if (doneN % 10 == 0) {
                            refreshIndexStatus(lastEnumTotal, building = true)
                        }
                        // R191 钉住行收尾（✓ 戳+终值计数；取消钉后收尾=台账回收）
                        onManualIndexRowFinishedImpl(chatId)
                        syncForegroundService()
                    }
                }
            }
            while (true) {
                if (!dev.tgfeed.core.prefs.AppPrefs(app).autoDeepIndex) break // 设置已关：收尾退出
                // 并发 AIMD（参数口径不变 [4,8]）：每节拍比对 flood 计数，
                // 无 flood +1 路（上限 8）、出 flood 降回 4
                val nowTick = System.currentTimeMillis()
                if (nowTick - lastAimdTickAt >= INDEX_AIMD_TICK_MS) {
                    val floodFree = deepIndex.floodEventCount() == floodsAtTick
                    floodsAtTick = deepIndex.floodEventCount()
                    lastAimdTickAt = nowTick
                    val next = if (floodFree) minOf(8, crawlConcurrency + 1) else 4
                    if (next != crawlConcurrency) {
                        crawlConcurrency = next
                        android.util.Log.d(
                            "FeedViewModel",
                            "深度索引并发调节：flood=${if (floodFree) "无" else "有"} " +
                                "并发=$crawlConcurrency 在爬=${inFlight.size} 排队=${queued.size}",
                        )
                    }
                }
                // ── R163 B4 优先车道先行（用户在等的爬取：M1 订阅补全/M9 搜索深挖——
                // 不受时机窗口闸与探索配额约束，直发）。priorityQueuedSet 是有效性令牌：
                // 巡游车道先派工到的频道其优先拷贝作废
                // R165：健康监视器喊停（SEVERE 压力/主线程停跳/trim 窗）时优先车道也停——
                // 进程存活优先于任何爬取；预算不拦优先车道（频次低、内部自会记账）
                // R208：风暴闸同款口径（开机静默期/负载退避持有中同停——设备存活
                // 与进程存活同级，watchdog 复位面前没有「用户在等」可言；解除即续派）
                while (!dev.tgfeed.core.diag.AppHealthMonitor.crawlPaused() &&
                    dev.tgfeed.core.td.SystemStormGate.clearNow() &&
                    inFlight.size < crawlConcurrency
                ) {
                    val prio = priorityCrawlQueue.tryReceive().getOrNull() ?: break
                    if (!priorityQueuedSet.remove(prio)) continue // 已被巡游车道派工/作废
                    queued.remove(prio)
                    exploreQueued.remove(prio)
                    ttlUrgentQueued.remove(prio) // R284：时效拷贝一并作废（防二次派工）
                    if (!inFlight.add(prio)) continue // 同频道已在爬，跳过防双爬
                    dispatchCrawl(prio)
                }
                // ── R163 B4 智能时机闸（smartTiming 关=恒放行现状等效；开时前台/在播/
                //    计量网络一律挂起**新派工**——在飞频道照跑完，只停发新工）+ 饿死推进 ──
                val timing = crawlTiming
                if (timing.smartTiming) {
                    val nowGate = System.currentTimeMillis()
                    val elapsed = (nowGate - lastGateEvalAt).coerceAtLeast(0L)
                    lastGateEvalAt = nowGate
                    val inputs = crawlTimingInputsImpl(nowGate, starveLevel)
                    // L0 一级窗口开放（充电+闲置+非计量）持续累计——饿死归零判定的原料
                    if (inputs.charging && !inputs.networkMetered && IndexCrawlGate.isUserIdle(inputs, timing)) {
                        l0WindowAccumMs += elapsed
                    }
                    // 24h 定级重估一次（窗口充足归 0 收回放宽；不足逐级放宽；重估即清零重积）
                    if (nowGate - starveLevelSinceMs >= IndexCrawlGate.STARVE_LEVEL_INTERVAL_MS) {
                        val next = IndexCrawlGate.nextStarveLevel(
                            starveLevel, l0WindowAccumMs, nowGate - starveLevelSinceMs,
                        )
                        if (next != starveLevel) {
                            android.util.Log.d(
                                "FeedViewModel",
                                "R163 时机饿死兜底放宽：L$starveLevel → L$next" +
                                    "（24h 一级窗口累计 ${l0WindowAccumMs / 60_000}min < ${IndexCrawlGate.STARVE_MIN_WINDOW_MS / 60_000}min）",
                            )
                            starveLevel = next
                        }
                        starveLevelSinceMs = nowGate
                        l0WindowAccumMs = 0L
                    }
                    if (!IndexCrawlGate.roamingCrawlAllowed(inputs, timing)) {
                        syncForegroundService() // 在飞收尾后 FGS 自然摘牌
                        delay(1_000L) // 闸门挂起拍（用户在场/计量网络：一秒一判，开销可忽略）
                        continue
                    }
                }
                // ── R165 预算耗尽/健康喊停派工退避（热循环止血，836 行/分实锤见 anr-analysis.md §5.1）──
                // 预算余量 ≤ 0 时继续巡游派工 = 秒退秒补的自维持热循环（indexChannel 采样闸
                // 秒退 → worker 瞬间完工 → 槽位空 → 500ms tick 补派，枚举拍 5min 又回灌）；
                // 派工本身全停（含白嫖——dispatchCrawl 内的事），在飞频道自然跑完收尾。
                // 只在进入/退出干涸沿打一行日志，不随 tick 刷屏。
                val budgetLeft = runCatching { crawlBudget.remaining() }.getOrDefault(1)
                val healthPaused = dev.tgfeed.core.diag.AppHealthMonitor.crawlPaused()
                // R208 风暴闸持有（开机静默期/系统高载）：派工全停（在飞自然跑完收尾，
                // 恢复即续派）——与预算/健康同家族的第三道派工退避源（时机闸）
                val stormHeld = !dev.tgfeed.core.td.SystemStormGate.clearNow()
                val dispatchHeld = healthPaused || budgetLeft <= 0 || stormHeld
                if (dispatchHeld != dispatchHeldAnnounced) {
                    dispatchHeldAnnounced = dispatchHeld
                    android.util.Log.i(
                        "FeedViewModel",
                        if (dispatchHeld) {
                            "深度索引派工退避：预算余量 $budgetLeft 健康喊停=$healthPaused 风暴闸=$stormHeld（干涸期 15s/拍，恢复即续派）"
                        } else {
                            "深度索引派工恢复：预算余量 $budgetLeft 健康喊停=$healthPaused 风暴闸=$stormHeld"
                        },
                    )
                }
                // ── 巡游派工（探索配额 ε-greedy：每 10 槽前 N 槽走探索车道，空则回退常规）──
                if (!dispatchHeld) {
                    // R284 时效优先车道先行（受时机闸/预算退避约束——后台维护语义，
                    // 不像优先车道那样用户在等；自毁内容不抢即永久消失=先于巡游/探索）
                    while (inFlight.size < crawlConcurrency) {
                        val id = ttlUrgentQueue.tryReceive().getOrNull() ?: break
                        ttlUrgentQueued.remove(id)
                        queued.remove(id) // 跨车道去重：旧拷贝作废（时效判定随枚举拍刷新）
                        exploreQueued.remove(id)
                        priorityQueuedSet.remove(id)
                        if (!inFlight.add(id)) continue // 同频道已在爬，跳过防双爬
                        dispatchCrawl(id)
                    }
                    while (inFlight.size < crawlConcurrency) {
                        val fromExplore = IndexCrawlGate.isExploreSlot(roamDispatchOrdinal.getAndIncrement(), timing) &&
                            exploreQueued.isNotEmpty()
                        val chatId = if (fromExplore) {
                            val id = exploreQueue.tryReceive().getOrNull() ?: break
                            exploreQueued.remove(id)
                            priorityQueuedSet.remove(id) // 作废可能存在的优先拷贝（防二次派工）
                            ttlUrgentQueued.remove(id) // R284：时效拷贝一并作废
                            id
                        } else {
                            val id = crawlQueue.tryReceive().getOrNull()
                                ?: exploreQueue.tryReceive().getOrNull() // 探索槽外也允许探索车道兜底补位
                                ?: break
                            if (id in exploreQueued) exploreQueued.remove(id) else queued.remove(id)
                            priorityQueuedSet.remove(id) // 作废可能存在的优先拷贝（防二次派工）
                            ttlUrgentQueued.remove(id) // R284：时效拷贝一并作废
                            id
                        }
                        if (!inFlight.add(chatId)) continue // 同频道已在爬，跳过防双爬
                        dispatchCrawl(chatId)
                    }
                }
                // ── R163 B4 机制④轮转全量兜底：双队列空 + 一级窗口（充电+闲置+非计量，
                //    最奢侈的爬取只配最好的窗口——不吃饿死放宽）→ 未探针宇宙 + T3 闲置
                //    频道重灌探索车道（探针页预算限成本，重新评 R）──
                // R165：派工退避期不重灌（灌了也没人消费，白做 SQLite 全量查询）
                if (!dispatchHeld && timing.smartTiming && indexTiering.enabled &&
                    queued.isEmpty() && exploreQueued.isEmpty() && ttlUrgentQueued.isEmpty() && inFlight.isEmpty()
                ) {
                    val inputs = crawlTimingInputsImpl(starveLevel = 0)
                    if (IndexCrawlGate.rotatorCrawlAllowed(inputs, timing, tieringEnabled = true, queuesIdle = true)) {
                        val universe = runCatching { deepIndex.untouchedChannelIds() }.getOrDefault(emptySet()) +
                            runCatching { deepIndex.tiers() }.getOrDefault(emptyMap())
                                .filterValues { it == IndexTierPolicy.IndexTier.T3 }.keys
                        var n = 0
                        for (id in universe) {
                            if (id !in inFlight && exploreQueued.add(id) &&
                                !AdChannelClassifier.isBlockedRuntime(id, null)
                            ) {
                                exploreQueue.trySend(id)
                                n++
                            } else {
                                exploreQueued.remove(id)
                            }
                        }
                        if (n > 0) {
                            android.util.Log.d(
                                "FeedViewModel",
                                "R163 轮转全量兜底：一级窗口开放，重灌探索车道 $n 个频道" +
                                    "（未探针宇宙 + T3 闲置；探针页预算限成本）",
                            )
                        }
                    }
                }
                syncForegroundService()
                // R165：派工轮询间歇决策（正常 0.5s/拍；预算干涸/健康喊停/风暴闸
                // 持有时 15s/拍——干涸期空转从 14 派工/秒压回 1/30，纯函数单测覆盖
                // 见 IndexCrawlGateTest + SystemStormGatePolicyTest）
                delay(IndexCrawlGate.dispatchTickDelayMs(budgetLeft, healthPaused, stormHeld))
            }
        } finally {
            enumJob.cancel()
            IndexCrawlService.stop(app) // 幂等：未挂牌时为无操作
            indexSchedulerAlive = false // R191：调度器终止（此后点按走直接建库路径）
        }
    }
}

// ---------------- R46 搜索联动插队（方案 d：证据频道提到爬取队列最前） ----------------

/**
 * 搜索证据频道插队（fullSearch 发起时调用；纯本地读、零网络，绝不拖累主搜索返回）：
 *  1. 直证：答案库 [TdLibGateway.queryGroupAnswers] 命中答案的已知 chatId
 *     （chatId=0 的用户名未解析答案由 EntityResolver 懒解析管线补，不在此插队）；
 *  2. 旁证：答案来源群的图谱 group_search 出边目标（同群群友搜索证据指向的关联频道）。
 * 证据 miss 静默不插队；广告黑名单剔除；条数封顶 [SEARCH_PRIORITY_MAX_CHANNELS]；
 * DeepSearchIndex 的 per-chatId 单飞闸兜底防同频道双爬（方案 d 前置已就位）。
 */
internal fun FeedViewModel.prioritizeSearchEvidence(real: dev.tgfeed.core.td.TdLibGateway, q: String) {
    val answers = runCatching { real.queryGroupAnswers(q) }.getOrDefault(emptyList())
    if (answers.isEmpty()) return
    val direct = answers.mapNotNull { a -> a.chatId.takeIf { it != 0L } }
    val fromGroups = answers.mapTo(HashSet()) { it.fromGroup }.apply { remove(0L) }
    val related = runCatching {
        real.discoveryGraph.relatedByEdgeType(
            fromGroups, GroupHarvester.EDGE_GROUP_SEARCH, SEARCH_PRIORITY_MAX_CHANNELS,
        )
    }.getOrDefault(emptyList())
    val targets = (direct + related).distinct()
        .filterNot { AdChannelClassifier.isBlockedRuntime(it, null) }
        .take(SEARCH_PRIORITY_MAX_CHANNELS)
    if (targets.isEmpty()) return
    var n = 0
    for (id in targets) {
        if (priorityQueuedSet.add(id)) {
            priorityCrawlQueue.trySend(id)
            n++
        }
    }
    if (n > 0) {
        android.util.Log.d(
            "FeedViewModel",
            "搜索证据频道插队：query=\"$q\" 答案 ${answers.size} 条 → 直证 ${direct.size} 个 " +
                "+ 图谱关联 ${related.size} 个，插队 $n 个频道（优先派工，单飞闸防重复派工）",
        )
    }
}

/** 某频道已索引条数（供 UI 显示"已索引 N 条"）；DB COUNT 查询（chatId 索引命中，毫秒级） */
internal fun FeedViewModel.deepIndexedCountImpl(chatId: Long): Int = deepIndex.indexedCount(chatId)

/**
 * 建/续建指定频道的深度索引（IO 协程包装，可重复调用：增量续爬）。
 * [onProgress] 每页回调一次当前累计已索引条数（IO 线程，UI 请自行切主线程）。
 * 失败只记 error，不打扰当前 Feed。
 */
internal fun FeedViewModel.buildDeepIndexImpl(chatId: Long, onProgress: (Int) -> Unit) {
    viewModelScope.launch(Dispatchers.IO) {
        // R163 B3：tiering 缺省 = 分档关（手动入口维持现状行为；B4 接线后传 VM 配置）
        runCatching { deepIndex.indexChannel(gateway, chatId, onProgress = onProgress) }
            .onFailure { _error.value = "深度索引构建失败：${it.message}" }
    }
}

/**
 * H3 串行版建索引：suspend 直挂调用协程，本频道爬完才返回。
 * 设置页"建立全部频道深度索引"用它 for 循环逐个 await——旧路径 buildDeepIndex
 * 内部自带 launch（fire-and-forget），forEach 会瞬间点燃 N 个频道的并发爬取，
 * 每 300ms 一页的 N 路并发极易撞 flood wait（flood 后该频道本轮即放弃，反而更慢）。
 * 失败处理与 buildDeepIndex 一致：只写 _error，不向上抛。
 */
internal suspend fun FeedViewModel.buildDeepIndexAwaitImpl(chatId: Long, onProgress: (Int) -> Unit) {
    withContext(Dispatchers.IO) {
        // R163 B3：tiering 缺省 = 分档关（手动入口维持现状行为；B4 接线后传 VM 配置）
        runCatching { deepIndex.indexChannel(gateway, chatId, onProgress = onProgress) }
            .onFailure { _error.value = "深度索引构建失败：${it.message}" }
    }
}

/**
 * 设置页打开期间的轻量状态刷新：沿用当前 building/totalChats/round 语义，
 * 只重读统计与库大小（DB COUNT + 文件 stat，毫秒级）。设置页每 5s 调一次，
 * 保证状态区数字在页面打开期间 ≤10s 内有一次真实刷新（不显示陈旧状态）。
 */
internal fun FeedViewModel.refreshDeepIndexStatusImpl() {
    val cur = _indexStatus.value
    refreshIndexStatus(cur.totalChats, cur.building)
}

/**
 * 清空并重建深度索引（设置页"清空并重建索引"按钮，二次确认后调用）：
 *  1. 清库（三表全清 + 回收磁盘，所有频道回到"未建"态）；
 *  2. 立即刷新状态（UI 马上看到"已清空"）；
 *  3. 重建分两种情况：
 *     - 自动索引开着：不用手动点火——常驻爬取循环的下一轮会把全部频道按
 *       "首次建索引"自然全量重爬（meta 游标已清）；在飞的本轮爬取不受影响，
 *       其落库即重建的第一批数据；
 *     - 自动索引关着：走与"建立/更新全部频道深度索引"按钮相同的手动路径，
 *       逐个串行 await 重建（串行防并发撞 flood 限流），期间状态区显示"建设中"。
 * 失败只写 _error，不向上抛（与 buildDeepIndex 系一致）。
 */
internal fun FeedViewModel.clearAndRebuildDeepIndexImpl() {
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { deepIndex.clearAll() }
            .onFailure {
                _error.value = "清空索引失败：${it.message}"
                return@launch
            }
        refreshIndexStatus() // 立即反映"已清空"
        if (prefs.autoDeepIndex) return@launch // 自动循环下一轮自然重建，无需点火
        // 手动路径：与设置页手动按钮同一链路（listChannels + 串行 indexChannel）
        val channels = runCatching { gateway.listChannels() }.getOrDefault(emptyList())
        refreshIndexStatus(channels.size, building = true)
        var done = 0
        for (ch in channels) {
            runCatching { deepIndex.indexChannel(gateway, ch.id) {} }
                .onFailure { _error.value = "深度索引构建失败：${it.message}" }
            done++
            if (done % 10 == 0) refreshIndexStatus(channels.size, building = true)
        }
        refreshIndexStatus(channels.size, building = false)
    }
}

/**
 * R294：压实预估（设置页确认弹窗口径）：(自由页字节, 库总字节, autoVacuumFull)；
 * 库未建/探测失败=null。值不值得压的判定真值=[IndexCompactPolicy.shouldCompact]
 * （自由页闸=建议措辞；auto_vacuum=FULL 设备态下页内密度重整收益以实测为准）。
 */
internal suspend fun FeedViewModel.estimateIndexCompactionImpl(): Triple<Long, Long, Boolean>? =
    withContext(Dispatchers.IO) { runCatching { deepIndex.compactionEstimate() }.getOrNull() }

/**
 * R294 P3 物理压实（设置页「压实索引库」确认后调用）：显式保 rowid 重建换装
 * （裸 VACUUM 是成文红线——本路径是唯一合法压实口，策略真值 [IndexCompactPolicy]）。
 * 进度走 [_indexCompactProgress]，完成/失败报告走 [_indexCompactDone]（UI Toast
 * 一次性消费）。全程 IO 线程；报告只出计数/字节（脱敏）。
 */
internal fun FeedViewModel.compactDeepIndexImpl() {
    viewModelScope.launch(Dispatchers.IO) {
        _indexCompactProgress.value = "压实：准备中…"
        runCatching {
            deepIndex.compactIndexDbPreservingRowids { p -> _indexCompactProgress.value = p }
        }.onSuccess { report ->
            _indexCompactProgress.value = null
            _indexCompactDone.value = report
        }.onFailure { e ->
            _indexCompactProgress.value = null
            _indexCompactDone.value = "压实失败：${e.message}"
        }
        refreshIndexStatus() // 库大小刷新（压实后 dbBytes 应见瘦）
    }
}

/**
 * 深度搜索：切到 [DeepSearchFeedSource]（一次性返回全部索引命中；SQLite FTS5 磁盘查询毫秒级返回）。
 * 空词忽略。只覆盖已建索引的频道；未索引频道请先 [buildDeepIndex]。
 * 模糊开关现读快照透传（切开关后下一次 deepSearch 即按新口径查询）。
 */
internal fun FeedViewModel.deepSearchImpl(query: String) {
    val q = query.trim()
    if (q.isEmpty()) return
    // R163 B4 / M9 机制③（死亡循环击断）：搜索命中浅档频道 → strongEvidence 即时
    // 升档（T2/T3/LEGACY→T1）+ 优先车道深挖（用户在等的爬取不受时机窗口闸约束）；
    // 已在 T1/T0 的维持不动（无 urgent 不插队）。分档关零开销直通。
    // R278：升档命中走 DeepSearchFeedSource.onHits 回调**复用本次 search 结果**
    // （IO 线程随首页物化触发）——旧复查式 promoteSearchHitTiers 把 search() 再跑
    // 一遍，LIKE 设备=第二个 45s 全表扫（r264 P2 双扫雷），已拆除。
    switchSource(
        DeepSearchFeedSource(deepIndex, q, prefs.fuzzySearch) { hits ->
            promoteHitChannelsImpl(hits, q)
        },
    )
}

/**
 * 搜索命中频道升档内核（R167 拆出共享，单一出处；R278 起普通深搜经
 * [DeepSearchFeedSource.onHits] 回调复用本次搜索结果喂入，hashtag 混合源经
 * onDeepHits 回调——两路均为零额外全表扫）：
 * 命中频道逐个 evaluate（strongEvidence=true——用户搜到了它 = 需求证据）→
 * 升档 urgent 的已在 [IndexTieringPart.evaluateAndSetTierImpl] 内优先插队。
 * 封顶 [SEARCH_PRIORITY_MAX_CHANNELS]（与证据插队同族上限口径）。
 * 调用语境：IO 线程（evaluate 有 DB 读）；[q] 仅日志用。
 */
internal fun FeedViewModel.promoteHitChannelsImpl(hits: List<VideoItem>, q: String) {
    if (!indexTiering.enabled) return
    if (hits.isEmpty()) return
    val channels = hits.mapTo(LinkedHashSet()) { it.chatId }.take(SEARCH_PRIORITY_MAX_CHANNELS)
    var promoted = 0
    for (id in channels) {
        val d = evaluateAndSetTierImpl(id, strongEvidence = true)
        if (d?.promoteUrgent == true) promoted++
    }
    if (promoted > 0) {
        android.util.Log.d(
            "FeedViewModel",
            "R163 搜索触发即时深挖：query=\"$q\" 命中 ${channels.size} 频道，" +
                "$promoted 个升档进优先车道（strongEvidence，绕过时机闸）",
        )
    }
}

/** 深度索引枚举节拍间隔（毫秒；R45 起为枚举 ticker 专用：枚举/爬取已解耦，不再存在
 *  "一轮全频道爬完"的轮末）。5min 恰好卡在 listSearchableChats 的 TTL 缓存上，
 *  每拍枚举都是新鲜全量（TDLib GetChats 按需加载会话，重复新鲜枚举推动其加载直至饱和） */
private const val INDEX_ROUND_INTERVAL_MS = 5L * 60_000L

/** 深度索引会话枚举为空时的重试间隔（毫秒）：网关未就绪/枚举失败短歇重试，不退出循环 */
private const val INDEX_ROUND_RETRY_MS = 60_000L

/** R45：爬取并发 AIMD 的调节节拍（毫秒）：解耦后没有"轮末"，按节拍比对 flood 计数
 *  （无 flood +1 路上限 8、出 flood 降回 4——参数口径不变，只换调节时机） */
private const val INDEX_AIMD_TICK_MS = 60_000L

// R165：派工轮询间歇常量迁至 IndexCrawlGate.DISPATCH_TICK_MS / DISPATCH_DRY_TICK_MS
// （纯函数 dispatchTickDelayMs 统一决策，单测锁口径；此处不再留私有副本）

/** R46 方案 d：单次搜索联动插队的频道数上限（防证据膨胀把优先队列打成长尾） */
private const val SEARCH_PRIORITY_MAX_CHANNELS = 16
