package dev.tgfeed.core.feed

import androidx.lifecycle.viewModelScope
import dev.tgfeed.core.store.WatchEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * R163 智能索引分级 · B4 调度接线（评估→落档→派工三件事的粘合层）。
 *
 * 三个纯函数折算（JVM 可单测）+ 三个 VM 实现：
 *  1. [watchDemandFold]：观看事件 → 需求信号分量（事件数/完播率/平均观看秒）；
 *  2. [richnessFold]：索引库聚合 → 丰度信号（密度/视频占比/剧集规整率/caption 信息量
 *     + B5 死链率负分量）；
 *  3. [evaluateAndSetTierImpl]：聚合 D/R → [IndexTierPolicy.evaluateTier] 定档 →
 *     [DeepSearchIndex.setTier] 落库（唯一写入口）→ 升档 urgent 时优先车道插队。
 *
 * 调用时机（谁触发评估）：
 *  - **派工前**（FeedIndexPart 爬取 ticker 每频道派工时，几 ms 级 SQL——
 *    每频道一次而不是枚举拍全量 604 次，成本摊薄到自然节奏）；
 *  - **订阅变更**（FeedFollowPart add/remove——M1 订阅=确定性需求即时 T0）；
 *  - **搜索命中**（FeedIndexPart deepSearch——机制③命中浅档即时深挖）。
 *
 * 降档滞回（T1→T2 两窗）：[tierDownStreaks] 会话级计数（不持久化——重启重算
 * 保守向：重启后滞回从头积累，降档只会更晚不会更早）。
 *
 * 纪律①：[FeedViewModel.indexTiering] 默认 [IndexTierPolicy.TieringConfig]（enabled=
 * false）——评估直通返回 null 不落档，全库维持 LEGACY，行为与分档前完全一致；
 * B6 接 AppPrefs 后由设置开关驱动。
 */

// ---------------- 纯信号折算（JVM 可单测） ----------------

/** 完播判定比例（与 FeedViewModel.COMPLETE_RATIO / InteractionStats 完播口径同值 0.9，
 *  各自持有——既有先例：InteractionStats 私有同款） */
private const val DEMAND_COMPLETE_RATIO = 0.9

/**
 * 观看事件聚合 → 需求信号三元组（事件数, 完播率, 平均观看秒）。
 * 完播率分母 = 有真实时长的结算（图文 durationSec=0 不计——防「图文恒完播」虚高，
 * 与 InteractionStats 同口径）；零事件返回 (0, 0, 0)。
 */
internal fun watchDemandFold(events: List<WatchEvent>): Triple<Int, Double, Double> {
    if (events.isEmpty()) return Triple(0, 0.0, 0.0)
    var withDuration = 0
    var completions = 0
    var secSum = 0L
    for (e in events) {
        secSum += e.watchSec
        if (e.durationSec > 0) {
            withDuration++
            if (e.watchSec.toDouble() / e.durationSec >= DEMAND_COMPLETE_RATIO) completions++
        }
    }
    val avg = secSum.toDouble() / events.size
    val completionRate = if (withDuration > 0) completions.toDouble() / withDuration else 0.0
    return Triple(events.size, completionRate, avg)
}

/**
 * 索引库聚合 + 死链率 → 丰度信号（R 评分的原料）。
 * [stats] null / 零条目 → 未探针（probed=false，R=0——「没测过」与「测过且低」
 * 是两回事，档位判定靠标志区分）；剧集规整率 = 最新 [DeepSearchIndex.channelIndexStats]
 * caption 样本里 [parseEpisodeMark] 命中比例（样本空=0）。
 */
internal fun richnessFold(
    stats: DeepSearchIndex.ChannelIndexStats?,
    deadRate: Double,
): IndexTierPolicy.RichnessSignals {
    if (stats == null || stats.items <= 0) return IndexTierPolicy.RichnessSignals(probed = false)
    val samples = stats.sampleCaptions
    val episodeHitRate = if (samples.isEmpty()) 0.0 else {
        samples.count { parseEpisodeMark(it) != null }.toDouble() / samples.size
    }
    return IndexTierPolicy.RichnessSignals(
        probed = true,
        sampleItems = stats.items,
        sampleSpanDays = stats.spanSec / 86_400.0,
        videoShare = stats.videoShare,
        episodeHitRate = episodeHitRate,
        captionNonEmptyRate = stats.captionNonEmptyRate,
        avgCaptionLen = stats.avgCaptionLen,
        deadRate = deadRate,
    )
}

// ---------------- VM 实现（聚合源：store/InteractionStats/deepIndex） ----------------

/**
 * 单频道档位评估 + 落库 + urgent 优先插队（B4 派工前/订阅变更/搜索命中调用）。
 * 返回档位决策（null = 分档关或评估失败——调用方按 LEGACY 现状处理）。
 * R191：钉住频道在此短路直通 T0（不降档）——见函数体钉住分支；其余频道走
 * 完整 D/R 聚合评估。
 * 纯本地读（store 内存缓存 + InteractionStats 锁内聚合 + deepIndex 只读 SQL），
 * 毫秒级，IO 协程调用；幂等（重复评估同一输入同结果）。
 */
internal fun FeedViewModel.evaluateAndSetTierImpl(
    chatId: Long,
    strongEvidence: Boolean = false,
): IndexTierPolicy.TierDecision? {
    val cfg = indexTiering
    if (chatId == 0L) return null
    // R191 手动钉住：用户意志最高优先级——恒 T0、自动评估不降档（档位决策唯一权威
    // 在 IndexTierPolicy.evaluateTier 规则 0；此处短路 D/R 聚合省毫秒级只读，分档关
    // 也生效——钉住与总开关正交：钉住是显式用户动作，同 R128 点赞回写「知情后开」
    // 先例）。钉入自低档（含 LEGACY 过渡态）时 promoteUrgent → 优先车道即刻深挖
    // （M1 订阅补全同一插队口）；钉住维持 T0 无 urgent（调度侧照常维护）。
    if (chatId in deepIndexPinnedChatIds) {
        return runCatching {
            val current = deepIndex.readTier(chatId)
            val decision = IndexTierPolicy.evaluateTier(
                cfg,
                current,
                IndexTierPolicy.DemandSignals(subscribed = false),
                IndexTierPolicy.RichnessSignals(),
                downStreak = 0,
                manualPinned = true,
            )
            deepIndex.setTier(chatId, decision.tier)
            if (decision.promoteUrgent) enqueuePriorityCrawlImpl(chatId)
            decision
        }.getOrNull()
    }
    if (!cfg.enabled) return null
    return runCatching {
        val current = deepIndex.readTier(chatId)
        // ---- D（需求）信号：订阅 + 观看事件 + 互动计数 ----
        val subscribed = _subscriptions.value.any { it.id == chatId }
        val (watchEvents, completionRate, avgWatchSec) =
            watchDemandFold(store.loadWatchEvents().filter { it.chatId == chatId })
        val inter = interactionStats.channelInteractionCounts()[chatId]
        val demand = IndexTierPolicy.DemandSignals(
            subscribed = subscribed,
            watchEvents = watchEvents,
            completionRate = completionRate,
            avgWatchSec = avgWatchSec,
            positiveInteractions = inter?.positive ?: 0,
            negativeFeedback = inter?.negative ?: 0,
            strongEvidence = strongEvidence,
        )
        // ---- R（丰度）信号：items 聚合 + 死链率（B5 记账的负分量） ----
        val deadRate = interactionStats.deadChannelStats()[chatId]?.rate ?: 0.0
        val richness = richnessFold(deepIndex.channelIndexStats(chatId), deadRate)
        // ---- 判定 + 滞回推进 + 落库 ----
        val streakBefore = synchronized(tierDownStreaks) { tierDownStreaks[chatId] ?: 0 }
        val decision = IndexTierPolicy.evaluateTier(cfg, current, demand, richness, streakBefore)
        val s = IndexTierPolicy.demandScore(demand)
        val r = IndexTierPolicy.richnessScore(richness)
        synchronized(tierDownStreaks) {
            // T1 维持且双弱（S≤降线 ∧ R≤降线）→ 连弱窗口 +1（两窗才降档——滞回）；
            // 其余一切结果（升档/降档/T2 维持）都清零重新积累
            tierDownStreaks[chatId] = if (
                decision.tier == IndexTierPolicy.IndexTier.T1 &&
                s <= IndexTierPolicy.DEMAND_DOWN_SCORE && r <= IndexTierPolicy.RICHNESS_DOWN_SCORE
            ) {
                streakBefore + 1
            } else {
                0
            }
        }
        deepIndex.setTier(chatId, decision.tier)
        if (decision.promoteUrgent) enqueuePriorityCrawlImpl(chatId)
        decision
    }.getOrNull()
}

/**
 * 优先车道插队（M1 订阅补全 / M9 搜索深挖共用）：用户在等的爬取——不受时机
 * 窗口闸与探索配额约束（B4 派工侧优先队列先于巡游队列直发）。
 * [priorityQueuedSet] 是有效性令牌：普通车道先派工到的频道其优先拷贝作废。
 */
internal fun FeedViewModel.enqueuePriorityCrawlImpl(chatId: Long) {
    if (chatId == 0L) return
    if (priorityQueuedSet.add(chatId)) priorityCrawlQueue.trySend(chatId)
}

/**
 * 订阅变更档位刷新（FeedFollowPart add/remove 订阅后**同协程**调用——
 * _subscriptions.value 已更新，评估读到最新订阅态）：
 *  - 订阅（M1）：确定性需求 → T0 即时（urgent → 优先车道全量深挖）；
 *  - 退订（M5）：需求消失 → 按剩余 D/R 信号重估（T0 → T1/T2 自然回落）。
 * 分档关时零开销直通。
 */
internal fun FeedViewModel.onSubscriptionTierRefreshImpl(chatId: Long) {
    if (!indexTiering.enabled || chatId == 0L) return
    val d = runCatching { evaluateAndSetTierImpl(chatId) }.getOrNull()
    if (d != null) {
        android.util.Log.d(
            "FeedViewModel",
            "订阅变更档位刷新：chatId=$chatId → ${d.tier}${if (d.promoteUrgent) "（urgent，优先车道）" else ""}（${d.reason}）",
        )
    }
}

/**
 * 「真在播」判定窗（B7 实测修正）：主位心跳 2s 一跳（[WATCH_HEARTBEAT_MS]），
 * 真在播时 [FeedViewModel.lastPlaybackActivityMs] 每 2s 刷新；超过 10s（5 跳缺失）
 * 判定已停。**不能只用 watchingAccum 非空**——B7 实测：后台暂停（MainActivity
 * onStop 按暂停兜底）后主位条目残留非空，若以其为「在播」口径则时机闸连同
 * L1/L2 饿死逃生门一起被永久堵死（后台暂停用户=断爬死锁）。
 */
internal const val PLAYBACK_ACTIVE_WINDOW_MS = 10_000L

/**
 * 时机系统状态快照（B4 每次派工判定前采集）：
 *  - foreground = [FeedViewModel.appInForeground]（MainActivity onStart/onStop 置位）；
 *  - playbackActive = 主位有计量条目 **且最近 [PLAYBACK_ACTIVE_WINDOW_MS] 内有真播放
 *    心跳**（暂停/后台静默后 ≤10s 自动转假——见 [PLAYBACK_ACTIVE_WINDOW_MS] 注释）；
 *  - networkMetered/charging = 系统回调/60s 轮询 StateFlow 现值；
 *  - lastWatchSettleMs = [FeedViewModel.lastPlaybackActivityMs]（结算/心跳时刻，
 *    闲置宽限基准；0=本会话无）。
 * 状态字段跨线程读取与 appInForeground 既有先例同级（非 volatile 平变量，
 * 最坏陈旧一拍，无崩溃面）；[starveLevel] 由调度循环维护传入。
 */
internal fun FeedViewModel.crawlTimingInputsImpl(
    nowMs: Long = System.currentTimeMillis(),
    starveLevel: Int = 0,
): IndexCrawlGate.TimingInputs =
    IndexCrawlGate.TimingInputs(
        foreground = appInForeground,
        playbackActive = watchingAccum != null &&
            nowMs - lastPlaybackActivityMs < PLAYBACK_ACTIVE_WINDOW_MS,
        networkMetered = _networkMetered.value,
        charging = _charging.value,
        lastWatchSettleMs = lastPlaybackActivityMs,
        nowMs = nowMs,
        starveLevel = starveLevel,
    )
