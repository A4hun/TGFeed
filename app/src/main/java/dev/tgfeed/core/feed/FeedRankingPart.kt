package dev.tgfeed.core.feed

import androidx.lifecycle.viewModelScope
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 累计一次互动加分（点赞/收藏/下载成功时调用；delta 可为负表示取消互动的对称回退）。
 * 内存表同步更新（下一次混排/拉取配额即生效），磁盘 IO 线程异步落盘。
 * R75-D：任何互动（含撤销）都刷新该频道的最后互动时刻——衰减基准随之重置。
 */
internal fun FeedViewModel.addInteractionBoost(chatId: Long, delta: Double) {
    val cur = interactionBoost[chatId] ?: 0.0
    val next = (cur + delta).coerceIn(0.0, INTERACTION_BOOST_CAP)
    if (next == cur) return // 无变化（已到顶/已回退到 0）不落盘
    interactionBoost = interactionBoost + (chatId to next)
    interactionBoostAt = interactionBoostAt + (chatId to System.currentTimeMillis())
    viewModelScope.launch(Dispatchers.IO) { persistInteractionBoost() }
}

/** 互动加分表落盘（新格式 {"v": 加分, "t": 最后互动时刻}）；IO 线程调用，失败静默 */
internal fun FeedViewModel.persistInteractionBoost() {
    runCatching {
        val values = interactionBoost
        val times = interactionBoostAt
        val obj = JSONObject()
        for ((id, v) in values) {
            obj.put(
                id.toString(),
                JSONObject().put("v", v).put("t", times[id] ?: System.currentTimeMillis()),
            )
        }
        boostPrefs.edit().putString(KEY_BOOST_MAP, obj.toString()).apply()
    }
}

/**
 * R75-D 时间衰减后的有效互动加分（一切消费方的统一口径）：
 * 有效值 = 账面值 × 线性衰减系数，系数 = 1 − 距上次互动天数/30 天，
 * 超过 [BOOST_DECAY_WINDOW_MS]（30 天）衰减到 0 并出表——切断"三个月前的赞
 * 还在抬权重"的通胀回路。账面原值不动（用户再互动即从当前账面继续累计，
 * 时刻同时刷新），衰减只发生在读取侧，零额外落盘。
 */
internal fun FeedViewModel.effectiveInteractionBoost(nowMs: Long = System.currentTimeMillis()): Map<Long, Double> {
    val values = interactionBoost
    if (values.isEmpty()) return values
    val times = interactionBoostAt
    val out = HashMap<Long, Double>(values.size)
    for ((id, v) in values) {
        val age = (nowMs - (times[id] ?: nowMs)).coerceAtLeast(0L)
        val factor = 1.0 - age.toDouble() / BOOST_DECAY_WINDOW_MS
        if (factor <= 0.0) continue // 30 天前的老互动不再抬权重
        out[id] = v * factor
    }
    return out
}

/**
 * 主 Feed 的频道加分表（互动加分 + 订阅加成）。
 * 订阅=用户最强的主动兴趣声明：订阅频道给一个不衰减的固定加成（[SUBSCRIPTION_BOOST]），
 * 让它们比陌生频道更常出现在推荐里；与 R92 的新面孔保底并存（保底管生面孔、订阅管你点过追的）。
 */
internal fun FeedViewModel.feedBoostWithSubscription(nowMs: Long = System.currentTimeMillis()): Map<Long, Double> {
    val out = HashMap<Long, Double>(effectiveInteractionBoost(nowMs))
    val subs = _subscriptions.value
    if (subs.isNotEmpty()) {
        for (s in subs) out[s.id] = (out[s.id] ?: 0.0) + SUBSCRIPTION_BOOST
    }
    return out
}

/**
 * 记一笔会话信号（P1 #4）。signal 取值：[SESSION_SIGNAL_SKIP] 快划 /
 * [SESSION_SIGNAL_DISLIKE] 不感兴趣 / [SESSION_SIGNAL_STRONG_POS] 强正（完播/互动）/
 * [SESSION_SIGNAL_NEUTRAL] 中性观看。任意线程可调用（内部锁，操作均为内存级）。
 */
internal fun FeedViewModel.noteSessionSignal(chatId: Long, signal: Int) {
    if (chatId == 0L) return
    synchronized(sessionLock) {
        val st = sessionStreaks.getOrPut(chatId) { IntArray(2) }
        var factor: Double? = null
        when (signal) {
            SESSION_SIGNAL_SKIP -> {
                st[0]++; st[1] = 0
                if (st[0] >= SESSION_STREAK_THRESHOLD) factor = SESSION_DOWN_FACTOR
            }
            SESSION_SIGNAL_DISLIKE -> {
                st[0] = 0; st[1] = 0
                factor = SESSION_DOWN_FACTOR // 不感兴趣 1 次即降，不要求连续
            }
            SESSION_SIGNAL_STRONG_POS -> {
                st[1]++; st[0] = 0
                if (st[1] >= SESSION_STREAK_THRESHOLD) factor = SESSION_UP_FACTOR
            }
            else -> { st[0] = 0; st[1] = 0 } // 中性观看：断连续，不挪系数
        }
        if (factor != null && sessionAdjust[chatId] != factor) {
            sessionAdjust = sessionAdjust + (chatId to factor)
        }
    }
}

/** 撤销某频道的会话系数（"不感兴趣"撤销时调用）：系数与连续计数一并清零 */
internal fun FeedViewModel.clearSessionAdjust(chatId: Long) {
    synchronized(sessionLock) {
        sessionStreaks.remove(chatId)
        if (chatId in sessionAdjust) sessionAdjust = sessionAdjust - chatId
    }
}

/**
 * 把会话系数乘到 [decayedChannelWeights] 的输出上（P1 #4 挂接点）。
 * 无会话系数时原样返回（零开销）；只有系数、尚无基础权重的频道按
 * [EXPLORE_WEIGHT] 探索档打底再乘系数（与 decayedChannelWeights 的兜底口径一致）；
 * 结果 clamp 到 [[SESSION_WEIGHT_FLOOR], [SESSION_WEIGHT_CAP]]。
 */
internal fun FeedViewModel.applySessionAdjust(weights: Map<Long, Double>): Map<Long, Double> {
    val adj = sessionAdjust
    if (adj.isEmpty()) return weights
    val out = HashMap<Long, Double>(weights.size + adj.size)
    for ((id, w) in weights) {
        out[id] = (w * (adj[id] ?: 1.0)).coerceIn(SESSION_WEIGHT_FLOOR, SESSION_WEIGHT_CAP)
    }
    for ((id, f) in adj) {
        if (id !in weights) out[id] = (EXPLORE_WEIGHT * f).coerceIn(SESSION_WEIGHT_FLOOR, SESSION_WEIGHT_CAP)
    }
    return out
}

/**
 * 供 WeightedAggregateFeedSource 构造注入的加分提供者（P1 #4 折算口径 + P2 双轨折算）：
 * 源内最终权重 = clamp(基础权重 + boost', 0.3, 2.0)，FeedSource 冻结无法直接乘系数，
 * 故令 boost' = boost + 基础权重×(系数−1)，使合成结果 ≈ 基础权重×系数 + 原互动加分
 * （近似点：源内 0.3 下限使 ×0.5 对低权重频道压不到理论值，可接受，注释备查）。
 * P2 双轨折算：再把"融合轨 − 长期轨"的差值平移进 boost'，源内合成结果即双轨融合权重
 * （近似点：差值随源内 clamp 截断，与 VM 直算路径 [p2ChannelWeights] 的数值略有出入，
 * 可接受——同一份口味画像语义，两套折算精度）。
 * 无会话系数且短期轨为空时直接返回互动加分表（零开销直通，行为与 P2 前完全一致）。
 */
internal fun FeedViewModel.sessionAdjustedBoost(): Map<Long, Double> {
    val adj = sessionAdjust
    val shortTrack = interestProfile.shortWeights()
    val binge = interestProfile.bingeBoostTarget() // R79 连刷型节奏加成目标（无则 null）
    // R75-D：互动加分一律走时间衰减后的有效值（effectiveInteractionBoost）
    // R163 B5：死链降权开启时不能直通（即使无会话系数/短期轨也要折降权增量）
    val demoteCfg = deadMediaDemotion
    if (adj.isEmpty() && shortTrack.isEmpty() && binge == null && !demoteCfg.demoteEnabled) {
        return effectiveInteractionBoost()
    }
    val now = System.currentTimeMillis()
    val events = store.loadWatchEvents() // 命中内存缓存，两份权重表共用同一次读取
    // 基础权重（不含互动加分）：与源内 computeBase 同函数同口径，保证折算基准一致
    val base = decayedChannelWeights(events, now)
    val out = HashMap<Long, Double>(effectiveInteractionBoost(now))
    // P2 双轨 delta：融合轨相对长期轨的差值折进加分（源内 final = base + boost'，差值直接平移）
    if (shortTrack.isNotEmpty()) {
        val longTrack = decayedChannelWeights(events, now, effectiveInteractionBoost(now))
        val fused = fuseTrackWeights(longTrack, shortTrack, interestProfile.fusionAlpha())
        for ((id, fw) in fused) {
            val delta = fw - (longTrack[id] ?: EXPLORE_WEIGHT)
            if (delta != 0.0) out[id] = (out[id] ?: 0.0) + delta
        }
    }
    for ((id, f) in adj) {
        if (f == 1.0) continue
        val b = base[id] ?: EXPLORE_WEIGHT
        out[id] = (out[id] ?: 0.0) + b * (f - 1.0)
    }
    // R79 连刷型节奏加成（源内路径折算口径，与 VM 路径 applyBingeBoost 同值加性注入）
    if (binge != null) out[binge.chatId] = (out[binge.chatId] ?: 0.0) + BINGE_CHANNEL_BOOST
    // R163 B5 死链降权折算：乘性 ×factor 折成加性 boost' += base×(factor−1)
    // （与上方会话系数同款折算法——源内 clamp(0.3,2.0) 的既有近似口径，注释备查）
    if (demoteCfg.demoteEnabled) {
        val factors = runCatching { DeadMediaPolicy.demotionFactors(interactionStats.deadChannelStats(), demoteCfg) }
            .getOrDefault(emptyMap())
        for ((id, f) in factors) {
            val b = base[id] ?: EXPLORE_WEIGHT
            out[id] = DeadMediaPolicy.foldFactorIntoBoost(out[id] ?: 0.0, b, f)
        }
    }
    return out
}

/**
 * P2 双轨融合后的最终频道权重（本类一切"当前口味画像"的统一出口）：
 * 长期轨 decayedChannelWeights（7 天衰减 + 互动加分）与短期轨（本会话快衰减，
 * InterestProfile.shortWeights）按 α 融合（α 随会话证据量 0.85→0.5 渐降——
 * 会话越短越听长期轨，防冷启动抖动），最后乘 P1 #4 会话系数。
 * 短期轨无数据时与融合前完全同值（零开销直通）。
 * IO 线程调用（store 读走内存缓存；短期轨为锁内内存聚合，开销可忽略）。
 */
internal fun FeedViewModel.p2ChannelWeights(): Map<Long, Double> {
    val now = System.currentTimeMillis()
    // R75-D：互动加分走时间衰减后的有效值
    val longTrack = decayedChannelWeights(store.loadWatchEvents(), now, effectiveInteractionBoost(now))
    val shortTrack = interestProfile.shortWeights()
    val merged = if (shortTrack.isEmpty()) {
        longTrack
    } else {
        fuseTrackWeights(longTrack, shortTrack, interestProfile.fusionAlpha())
    }
    // R163 B5：死链降权（乘性，默认关——纪律①：记账照常、降权零生效）
    return applyDeadMediaPenalty(applySessionAdjust(applySubscriptionBoost(applyBingeBoost(merged))))
}

/**
 * R163 B5 死链降权（VM 直算路径乘性消费）：近 7 天 per-chatId 死链率超阈值的频道
 * 权重 ×penaltyFactor（判定与阈值唯一出处 [DeadMediaPolicy]）。开关关/系数表空/
 * 聚合失败 = 原表直通零开销；锁内聚合微秒级（与 computeStats 同量级）。
 * 排序语义：压一半不是封杀——死链率是近 7 天滚动窗，频道复活（新内容可播）
 * 死链率自然回落，降权自动解除；B6 接 AppPrefs 后用户可关。
 */
internal fun FeedViewModel.applyDeadMediaPenalty(weights: Map<Long, Double>): Map<Long, Double> {
    val cfg = deadMediaDemotion
    if (!cfg.demoteEnabled || weights.isEmpty()) return weights
    val factors = runCatching { DeadMediaPolicy.demotionFactors(interactionStats.deadChannelStats(), cfg) }
        .getOrDefault(emptyMap())
    return DeadMediaPolicy.applyPenalty(weights, factors)
}

/**
 * 订阅加成（乘性）：订阅=用户最强的主动兴趣声明，订阅频道权重 ×(1+比例)，
 * 让点过"追"的频道比陌生频道更常出现在推荐。乘性（不是加性）是为了跟随该频道
 * 现有权重缩放，不在排序分里制造绝对跳变；与 R92 新面孔保底并存、硬约束不动。
 */
internal fun FeedViewModel.applySubscriptionBoost(weights: Map<Long, Double>): Map<Long, Double> {
    val subs = _subscriptions.value
    if (subs.isEmpty()) return weights
    val subIds = subs.mapTo(HashSet(subs.size)) { it.id }
    val out = HashMap<Long, Double>(weights.size)
    for ((id, w) in weights) {
        out[id] = if (id in subIds) w * (1.0 + SUBSCRIPTION_WEIGHT_RATIO) else w
    }
    // 订阅但从未出现过的频道（不在权重表）补一个探索档×加成，让它们也有冒头机会
    for (id in subIds) {
        if (id !in weights) out[id] = EXPLORE_WEIGHT * (1.0 + SUBSCRIPTION_WEIGHT_RATIO)
    }
    return out
}

/**
 * R79 连刷型节奏加成：节奏分型为连刷型且当前同频道连刷条数达到阈值（InterestProfile
 * 内部口径）时，给该频道 +[BINGE_CHANNEL_BOOST] 小幅加分（"连刷就让它多排一点"）。
 * 刻意克制：只加一次小分、不碰放置层窗口/占比/探索配额硬约束——连刷加成是
 * "多排一点"不是"霸屏"，R75 多样性保底不受影响；非连刷型/无连刷时零开销直通。
 */
internal fun FeedViewModel.applyBingeBoost(weights: Map<Long, Double>): Map<Long, Double> {
    val streak = interestProfile.bingeBoostTarget() ?: return weights
    val cur = weights[streak.chatId] ?: EXPLORE_WEIGHT
    return weights + (streak.chatId to cur + BINGE_CHANNEL_BOOST)
}

/**
 * R79 节奏分型消费（每次有效观看结算后调用）：分型跃迁时更新秒划惩罚系数
 * （浅尝辄止型 0.5——对什么都划得快，秒划噪声大减半；深度观看型 1.0——秒划是
 * 真负反馈满格；其余 0.8），并打一行画像快照日志（样本/均比例/回看率/连刷率）。
 */
internal fun FeedViewModel.noteRhythmStyle(style: RhythmStyle) {
    if (style == lastRhythmStyle) return
    lastRhythmStyle = style
    val scale = when (style) {
        RhythmStyle.SKIM -> RHYTHM_SKIP_SCALE_SKIM
        RhythmStyle.DEEP -> RHYTHM_SKIP_SCALE_DEEP
        else -> RHYTHM_SKIP_SCALE_DEFAULT
    }
    implicitFeedback.penaltyScale = scale
    val p = interestProfile.rhythmProfile()
    val styleLabel = when (style) {
        RhythmStyle.DEEP -> "深度观看型"
        RhythmStyle.SKIM -> "浅尝辄止型"
        RhythmStyle.BINGE -> "连刷型"
        RhythmStyle.BALANCED -> "均衡型"
        RhythmStyle.UNKNOWN -> "样本不足"
    }
    android.util.Log.i(
        "FeedViewModel",
        "观看节奏画像: 风格=$styleLabel 样本=${p.samples} " +
            "平均观看比例=${"%.0f".format(p.avgRatio * 100)}% " +
            "回看率=${"%.0f".format(p.replayRate * 100)}% " +
            "连刷率=${"%.0f".format(p.bingeRate * 100)}% → 秒划惩罚系数=$scale",
    )
}

/**
 * #1 重看检测（settleWatchEvent 新条目入场时调用，主线程）：
 * 本会话有效观看过的条目再次成为主位（翻回来/再次起播）→ 回看次数 +1；
 * 达到 [REPLAY_STRONG_THRESHOLD] 且本条尚未发过加分 → 频道 +[BOOST_REPLAY]
 * （比点赞 BOOST_LIKE 高一点的超强正信号：翻回来再看是比点一下赞更强的意愿），
 * 同时计一次会话强正与统计互动（sub="replay"）。
 * [prevKey] 挡掉同 key 的重复入场（分页器重发回调不算回看）。
 */
internal fun FeedViewModel.noteReplayEntry(prevKey: String?, newItem: VideoItem) {
    val key = newItem.key
    if (prevKey == key) return
    var fire = false
    synchronized(p15Lock) {
        // 没有效观看过 / 紧挨着的上一次结算就是它（刷新后回到同一条）都不算回看
        if (key in sessionWatchedKeys && key != lastSettledWatchKey) {
            val n = (sessionReplayCounts[key] ?: 0) + 1
            sessionReplayCounts[key] = n
            if (n >= REPLAY_STRONG_THRESHOLD && sessionReplayBoosted.add(key)) fire = true
        }
    }
    if (fire) {
        addInteractionBoost(newItem.chatId, BOOST_REPLAY)
        noteSessionSignal(newItem.chatId, SESSION_SIGNAL_STRONG_POS)
        interactionStats.recordInteraction("replay", newItem.chatId)
    }
}

/**
 * #1 有效观看登记（settleWatchEvent 结算侧调用）：watchSec≥2s 的条目进"本会话已看"底表，
 * 并记为最近一次结算（lastSettledWatchKey 供"回到同一条不算回看"判定）。
 */
internal fun FeedViewModel.markSessionWatched(key: String) {
    synchronized(p15Lock) {
        sessionWatchedKeys.add(key)
        lastSettledWatchKey = key
    }
}

/**
 * #3 主动开声音（取消静音）= 高投入强正信号（[BOOST_UNMUTE]，与点赞同级、不低于点赞）。
 * 由 VideoPage 功能面板"取消静音"点按触发；每条目每会话只计一次（反复切换不刷分），
 * 回静音无负语义（只是回到默认，不倒扣）。
 */
internal fun FeedViewModel.noteActiveUnmuteImpl(item: VideoItem) {
    val first = synchronized(p15Lock) { sessionUnmuteCredited.add(item.key) }
    if (!first) return
    addInteractionBoost(item.chatId, BOOST_UNMUTE)
    noteSessionSignal(item.chatId, SESSION_SIGNAL_STRONG_POS)
    interactionStats.recordInteraction("unmute", item.chatId)
}

/**
 * #3 主动全屏 = 高投入强正信号（[BOOST_FULLSCREEN]，与点赞同级、不低于点赞）。
 * 只认"手动点全屏按钮"（VideoPage.enterFullscreen）；旋转自动全屏不经此路（非主动，不计）。
 */
internal fun FeedViewModel.noteActiveFullscreenImpl(item: VideoItem) {
    val first = synchronized(p15Lock) { sessionFullscreenCredited.add(item.key) }
    if (!first) return
    addInteractionBoost(item.chatId, BOOST_FULLSCREEN)
    noteSessionSignal(item.chatId, SESSION_SIGNAL_STRONG_POS)
    interactionStats.recordInteraction("fullscreen", item.chatId)
}

/**
 * #2 拖拽落点信号入口（VideoPage 位置跳变检测防抖后上报，任意线程可调用）：
 * 只记 InteractionStats 聚合指标（computeStats 的落点分布），不进频道权重——
 * 分布型信号先观察分布（"拖后段=内容有高潮 / 老拖开头=前奏太长"的占比）再定权重策略。
 * R204 TL-7：条目 key 随事件落进 sub 字段（本机单视频回放热力的精确层，零格式迁移）。
 */
internal fun FeedViewModel.noteSeekTargetImpl(chatId: Long, targetMs: Long, durationMs: Long, key: String) {
    if (chatId == 0L || durationMs <= 0L || targetMs < 0L) return
    interactionStats.recordSeek(
        chatId,
        (targetMs / 1000L).coerceIn(0L, durationMs / 1000L).toInt(),
        (durationMs / 1000L).coerceAtMost(MAX_WATCH_SEC).toInt(),
        key,
    )
}

/**
 * #4 喂会话序列动量词表（有效观看结算时调用，视频/图文/音频同口径）：
 * caption 口味词按观看时长加权累加（与口味词表同一加权公式），每来一笔先全表衰减
 * ×[SESSION_TOPIC_DECAY]——最近观看的主题自然占主导，"连看同主题"累积、"换了主题"消退。
 * 表容量封顶 [SESSION_TOPIC_MAX_WORDS]（只留高权重词），纯内存、重启清零。
 */
internal fun FeedViewModel.feedSessionMomentum(caption: String, watchSec: Int) {
    if (caption.isBlank()) return
    val words = runCatching { tasteRadar.tokenize(caption) }.getOrDefault(emptyList())
        .filter { it.isNotBlank() }
        .distinct()
    if (words.isEmpty()) return
    synchronized(p15Lock) {
        val it = sessionTopicWords.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val v = e.value * SESSION_TOPIC_DECAY
            if (v < SESSION_TOPIC_MIN_WEIGHT) it.remove() else e.setValue(v)
        }
        val add = (watchSec / 10.0).coerceIn(0.2, 3.0)
        for (w in words) sessionTopicWords[w] = (sessionTopicWords[w] ?: 0.0) + add
        if (sessionTopicWords.size > SESSION_TOPIC_MAX_WORDS) {
            val top = sessionTopicWords.entries.sortedByDescending { e -> e.value }
                .take(SESSION_TOPIC_MAX_WORDS)
            sessionTopicWords.clear()
            for (e in top) sessionTopicWords[e.key] = e.value
        }
    }
}

/**
 * #4 会话序列动量页内加权（loadMore 排序后追加的消费侧，挂在会话适应机制旁边）：
 * caption 命中会话热词（权重 ≥ [SESSION_TOPIC_ACTIVE_WEIGHT] 的前 [SESSION_TOPIC_TOP_WORDS] 个）
 * 的条目做有界前移——每轮越过至多 1 个非同主题条目，共 [SESSION_TOPIC_PROMOTE_MAX] 轮
 * （最多前移 2 位）。"轻微加权"刻意克制：只微调页内先后，不增删条目、不打乱六段链宏观序。
 * 动量表为空 / 无命中时原样返回（零开销直通）。
 */
internal fun FeedViewModel.applySessionMomentum(page: List<VideoItem>): List<VideoItem> {
    val top: Set<String> = synchronized(p15Lock) {
        if (sessionTopicWords.isEmpty()) return page
        sessionTopicWords.entries
            .filter { it.value >= SESSION_TOPIC_ACTIVE_WEIGHT }
            .sortedByDescending { it.value }
            .take(SESSION_TOPIC_TOP_WORDS)
            .mapTo(HashSet()) { it.key }
    }
    if (top.isEmpty() || page.size < 3) return page
    val topicKeys = HashSet<String>()
    for (it in page) {
        if (it.caption.isBlank()) continue
        val hit = runCatching { tasteRadar.tokenize(it.caption) }.getOrDefault(emptyList())
            .any { w -> w in top }
        if (hit) topicKeys += it.key
    }
    if (topicKeys.isEmpty()) return page
    val out = page.toMutableList()
    repeat(SESSION_TOPIC_PROMOTE_MAX) {
        for (i in 1 until out.size) {
            if (out[i].key in topicKeys && out[i - 1].key !in topicKeys) {
                val t = out[i - 1]; out[i - 1] = out[i]; out[i] = t
            }
        }
    }
    return out
}

/**
 * #5 会话疲劳探索补位：本次会话真实观看（心跳口径）≥ [SESSION_FATIGUE_MS]（45 分钟）后，
 * 在现有探索机制（rankWeightedPage 每 10 位保底 1 格 ≈10%）旁边错开补第二格
 * （位 4,14,24…，与原生格位 9,19,29… 相间）→ 合计 ≈20%，越刷越新鲜。
 * FeedSource 冻结不能改其 EXPLORE_EVERY，故在 VM 侧对排好序的页做"冷门条目换位前置"：
 * 冷门口径与源内一致（频道权重 ≤ [EXPLORE_WEIGHT]，零画像频道缺省 0.6 也在内）；
 * 换位前镜像源内窗口约束（连续 [EXPLORE_EXTRA_WINDOW] 条内同频道 ≤ [EXPLORE_EXTRA_WINDOW_MAX]），
 * 防补位打散多样性；找不到合规冷门就跳过该格（不硬塞、不增删条目）。
 * [pageWeights]：上游排序算好的权重表（避免重算）；为 null 的路径（「全部」tab 源内已排序）
 * 现算一份（IO 线程，store 命中内存缓存）。疲劳未到时原样返回（零开销直通）。
 */
internal fun FeedViewModel.applyFatigueExplore(page: List<VideoItem>, pageWeights: Map<Long, Double>?): List<VideoItem> {
    if (sessionWatchMs.get() < SESSION_FATIGUE_MS) return page
    val n = page.size
    if (n < EXPLORE_EXTRA_MIN_PAGE) return page
    // P2：兜底现算走双轨融合统一出口（与上游排序同一口径）
    val weights = pageWeights ?: p2ChannelWeights()
    fun isCold(it: VideoItem) =
        (weights[it.chatId] ?: EXPLORE_WEIGHT) <= EXPLORE_WEIGHT + 1e-9
    val out = page.toMutableList()
    var p = EXPLORE_EXTRA_OFFSET
    while (p < n) {
        if (!isCold(out[p])) {
            var j = p + 1
            while (j < n) {
                val cand = out[j]
                if (isCold(cand)) {
                    // 镜像源内窗口约束：检查换入后 p 位所在窗口的同频道数不超标
                    var same = 0
                    var k = p - 1
                    val lo = p - (EXPLORE_EXTRA_WINDOW - 1)
                    while (k >= 0 && k >= lo) {
                        if (out[k].chatId == cand.chatId) same++
                        k--
                    }
                    if (same < EXPLORE_EXTRA_WINDOW_MAX) break
                }
                j++
            }
            if (j < n) {
                val t = out[p]; out[p] = out[j]; out[j] = t
            }
        }
        p += EXPLORE_EXTRA_EVERY
    }
    return out
}

/**
 * P1.5 页内后处理总入口（loadMore 追加段）：先会话动量（#4）后疲劳补位（#5）。
 * 两路都无信号时原样直通；只重排页内既有条目，不增删条目（过滤/去重结论不受影响）。
 */
internal fun FeedViewModel.applyP15PostRank(page: List<VideoItem>, pageWeights: Map<Long, Double>?): List<VideoItem> {
    var out = applySessionMomentum(page)
    out = applyFatigueExplore(out, pageWeights)
    return out
}

/**
 * P2 序列三件套页内消费侧（DIN 目标注意力 + 马尔可夫转移），挂在 P1.5 后处理之后：
 *
 *  - **DIN 目标注意力**（ALGO-NEXTGEN-SEQ §2 的零训练落地）：候选 C 的相关度分
 *    interest(C) = Σ_{w ∈ tokens(C)} weight(w)——词权重画像 = 长期口味词表
 *    （taste_words，STOP 过滤 + 归一化，TasteRadar.dinWordProfile）融合会话动量热词
 *    （sessionTopicWords 归一化后 ×[DIN_SESSION_WORD_GAIN]，"现在想看"稍响于"一直爱看"）。
 *    不做 softmax 归一化（DIN 原文口径：保留兴趣强度绝对值），dinAttentionScore 内
 *    按候选词数开方归一防长 caption 堆词灌水。
 *    落地位置说明：FeedSource 冻结使条目级分量进不了六段链的权重管道（管道是
 *    频道粒度），故 DIN 分量落在 VM 页内重排层——"千候选千面"的本地形态；
 *  - **马尔可夫转移**（SEQ §1 的零训练版 SASRec）：P(下一条口味|当前口味) 加分
 *    = [MARKOV_BONUS_WEIGHT] × InterestProfile.transitionScore，上下文 = 上一次
 *    有效观看的打分词集 + 频道（lastWatchedTaste；会话起点无上下文则本项为 0）；
 *  - 合成 p2Score = din + markovBonus，≥[P2_ACTIVE_SCORE] 且比前一位高
 *    [P2_SWAP_MARGIN] 的条目有界前移（冒泡至多 [P2_PROMOTE_MAX] 轮，
 *    与 #4 会话动量同款克制：只微调页内先后，不增删条目、不打乱六段链宏观序
 *    与多样性硬约束）。
 *
 * 画像为空（新用户/口味词表未积累）或页太短原样直通（零开销）。
 * IO 线程调用（loadMore 内）；store 读走内存缓存。
 */
internal fun FeedViewModel.applyP2SequenceRank(page: List<VideoItem>): List<VideoItem> {
    if (page.size < 3) return page
    // 长期轨词画像（磁盘口味词表，STOP 过滤 + 归一化）
    val longProfile = tasteRadar.dinWordProfile()
    // 会话轨词画像（动量热词按最大值归一化；锁内拷贝快照后折算，出锁即用）
    val sessionProfile: Map<String, Double> = synchronized(p15Lock) {
        if (sessionTopicWords.isEmpty()) {
            emptyMap()
        } else {
            val max = sessionTopicWords.values.max()
            if (max <= 0.0) emptyMap() else sessionTopicWords.mapValues { it.value / max }
        }
    }
    if (longProfile.isEmpty() && sessionProfile.isEmpty()) return page
    // 双源词画像合成一张权重表（全页只建一次）：会话轨带增益融入长期轨
    val wordWeights = HashMap<String, Double>(longProfile.size + sessionProfile.size)
    wordWeights.putAll(longProfile)
    for ((w, v) in sessionProfile) {
        wordWeights[w] = (wordWeights[w] ?: 0.0) + DIN_SESSION_WORD_GAIN * v
    }
    val context = lastWatchedTaste // 马尔可夫上下文快照（@Volatile 读；null = 会话起点）
    val scores = HashMap<String, Double>(page.size)
    for (item in page) {
        if (item.caption.isBlank()) continue
        val words = runCatching { tasteRadar.tokenizeForScoring(item.caption) }
            .getOrDefault(emptyList())
        if (words.isEmpty()) continue
        // DIN 目标注意力分：候选词集 × 双源词画像
        var s = dinAttentionScore(words, wordWeights)
        // 马尔可夫转移加分：候选接住当前口味上下文的概率
        if (context != null) {
            s += MARKOV_BONUS_WEIGHT * interestProfile.transitionScore(
                context.first, context.second, words.toSet(), item.chatId,
            )
        }
        if (s > 0.0) scores[item.key] = s
    }
    if (scores.isEmpty()) return page
    // 有界前移：冒泡 [P2_PROMOTE_MAX] 轮，活跃条目每轮越过一个分差不足 [P2_SWAP_MARGIN] 的前驱
    val out = page.toMutableList()
    repeat(P2_PROMOTE_MAX) {
        var swapped = false
        var i = 1
        while (i < out.size) {
            val cur = scores[out[i].key] ?: 0.0
            val prev = scores[out[i - 1].key] ?: 0.0
            if (cur >= P2_ACTIVE_SCORE && cur > prev + P2_SWAP_MARGIN) {
                val t = out[i - 1]; out[i - 1] = out[i]; out[i] = t
                swapped = true
            }
            i++
        }
        if (!swapped) return out
    }
    return out
}
