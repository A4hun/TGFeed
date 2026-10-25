package dev.tgfeed.core.feed

import androidx.lifecycle.viewModelScope
import dev.tgfeed.core.playback.PlayerPool
import dev.tgfeed.core.store.LocalStore
import dev.tgfeed.core.store.WatchEvent
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 观看结算：把上一条的真实观看时长（心跳累计，封顶 [MAX_WATCH_SEC] 秒防挂机）
 * 异步写入 watch_events，再为 [newItem] 从零起计；newItem=null 表示离开播放（切源/刷新/销毁）。
 *
 * P0-2 口径修正：watchSec 曾是"翻页墙钟"（进入时刻到翻页的间隔），暂停/看评论/
 * 亮屏挂机全被记成观看（30s 视频挂 5 分钟 → 比例 clamp 1.0 → 假完播强正反馈）。
 * 现由 [watchHeartbeatTick] 心跳供给内容消费毫秒（只在 isPlaying 时累计，
 * R110 起每跳按当前倍速折算），
 * 本函数只负责在翻页/离开时把累计值结算落盘——WatchEvent 数据结构不变，
 * 只改 watchSec 的供给口径。
 *
 * v2 秒划走分级：watchSec < [SKIP_FAST_SEC] 秒的事件在权重计算侧按 skip 处理
 * （等效观看比例 -0.5，见 FeedSource.kt 的 decayedChannelWeights）。
 * 落地取舍：LocalStore 冻结不能给 watch_events.json 加 skipped 字段，
 * skip 由 watchSec 在读侧推导，老数据自动获得相同语义，无需迁移。
 */
internal fun FeedViewModel.settleWatchEvent(newItem: VideoItem?) {
    val prev = watchingAccum
    watchingAccum = newItem?.let { it to 0L } // 新条目从 0 起计，心跳负责累加真实播放毫秒
    // R163 B4：播放活动时刻（时机窗口闲置宽限的基准）——主位条目存在即活动，
    // 即便是快划也在交互；主位清空（退到底/无条目）不再刷新，宽限从最后活动起算
    if (newItem != null) lastPlaybackActivityMs = System.currentTimeMillis()
    // 指标闭环：新条目成为主位 = 一次推荐曝光（视频页口径）
    if (newItem != null) interactionStats.recordImpression(newItem.chatId)
    // P1.5 #1：新条目入场做重看检测（翻回来/再次起播；同 key 重复入场由 prevKey 挡掉）
    if (newItem != null) noteReplayEntry(prev?.first?.key, newItem)
    if (prev != null) {
        val (prevItem, playedMs) = prev
        val watchSec = (playedMs / 1000L).coerceIn(0L, MAX_WATCH_SEC).toInt()
        // P1.5 #5：累计本次会话真实观看时长（心跳口径，含快划——刷得快也在消耗注意力），
        // 过 [SESSION_FATIGUE_MS] 后探索配额翻倍（applyFatigueExplore）
        sessionWatchMs.addAndGet(playedMs)
        // P1.5 #1：有效观看（≥2s）登记"本会话已看"，供后续翻回来的重看检测
        if (watchSec >= SKIP_FAST_SEC) markSessionWatched(prevItem.key)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.appendWatchEvent(prevItem.chatId, watchSec, prevItem.durationSec) }
            // 指标闭环：观看结算入统；<2s 快划同时记负反馈（与权重侧 skip 口径同阈值）
            interactionStats.recordWatch(prevItem.chatId, watchSec, prevItem.durationSec)
            if (watchSec < SKIP_FAST_SEC) interactionStats.recordNegative("skip", prevItem.chatId)
            // P1 #2 完播率分档加权：按 watchSec/durationSec 档位给频道权重加非线性强调
            applyWatchQualityBoost(prevItem.chatId, watchSec, prevItem.durationSec)
            // P1 #4 会话内实时适应：快划/完播喂会话系数（中性观看断连续、不挪系数）
            noteSessionSignal(prevItem.chatId, watchSessionSignal(watchSec, prevItem.durationSec))
            // P1-a #4：视频 caption 也喂口味词表（此前只有图文/音频 recordMediaView 喂——
            // 视频是观看大头，不喂等于口味词表丢了最主要的原料）。
            // 与 recordMediaView 同一口径：按观看时长加权；秒划不喂，防噪声污染词表
            if (watchSec >= SKIP_FAST_SEC && prevItem.caption.isNotBlank()) {
                runCatching {
                    val words = tasteRadar.tokenize(prevItem.caption)
                        .filter { it.isNotBlank() }
                        .distinct()
                    if (words.isNotEmpty()) {
                        store.addTasteWords(words, score = (watchSec / 10.0).coerceIn(0.2, 3.0))
                    }
                }
            }
            // P1-b #7：观看序列共现边（同会话相邻观看的两频道建弱边，30 分钟窗，纯本地零请求）
            if (watchSec >= SKIP_FAST_SEC) recordCoWatchEdge(prevItem.chatId)
            // P1.5 #4：有效观看的 caption 口味词喂会话序列动量表（逐事件衰减，重启清零）
            if (watchSec >= SKIP_FAST_SEC) feedSessionMomentum(prevItem.caption, watchSec)
            // P2 双轨画像：短期轨记事件（快划也记——秒划是短期轨的负反馈证据，口径同长期轨）
            interestProfile.recordWatch(prevItem.chatId, watchSec, prevItem.durationSec)
            // R79 隐式反馈链：条目级结算入账（秒划计数衰减升级/有效观看恢复抵扣/
            // LinUCB 奖励归因三合一）；回看标记读 P1.5 #1 的会话回看计数底表
            val replayed = synchronized(p15Lock) {
                (sessionReplayCounts[prevItem.key] ?: 0) > 0
            }
            implicitFeedback.recordSettle(
                prevItem.key, prevItem.chatId, prevItem.mediaType,
                watchSec, prevItem.durationSec, replayed,
            )
            // R298：tag/类型两轴兴趣记账（G3 缺口根治——watch_events 不存 tag/类型，
            // 轻量账本在结算点现提：item_tags 已解析优先，否则 caption #标签同尺现提；
            // 秒划扣 tag 负轴，有效观看按时长加权，快速学习期 ×2——口径见
            // TagInterestLedger 类注释）
            runCatching {
                tagInterestLedger.recordSettle(
                    foldTagsOf(prevItem), prevItem.mediaType, watchSec, prevItem.durationSec,
                )
            }
            // R79 观看节奏画像：有效观看沉淀节奏样本，分型跃迁时回推秒划惩罚系数
            if (watchSec >= SKIP_FAST_SEC) {
                noteRhythmStyle(
                    interestProfile.recordWatchRhythm(
                        prevItem.chatId, watchSec, prevItem.durationSec, replayed,
                    ),
                )
            }
            // P2 马尔可夫转移：有效观看更新口味上下文并记一笔"上次有效观看→本次"转移
            //（快划不携带"想看"语义，不更新上下文也不记账；Dispatchers.IO 多线程下相邻
            //  结算偶发乱序只会让个别转移对错位，统计模型吸收，不做串行化）
            if (watchSec >= SKIP_FAST_SEC) {
                val toWords = runCatching { tasteRadar.tokenizeForScoring(prevItem.caption) }
                    .getOrDefault(emptyList()).toSet()
                val ctx = lastWatchedTaste
                if (ctx != null) {
                    interestProfile.recordTransition(ctx.first, ctx.second, toWords, prevItem.chatId)
                }
                lastWatchedTaste = toWords to prevItem.chatId
            }
        }
    }
}

/**
 * 完播率分档加权（P1 #2 观看质量信号）：watchSec/durationSec 按四档折算成互动加分表的
 * 小幅增减，经 decayedChannelWeights 的 interactionBoost 参数注入频道权重——
 * 与 watch_events 里"线性观看比例"主信号叠加，形成"高完播强正 / 低观看负向"的
 * 非线性强调（口径对齐 TikTok「完播是第一权重」与 ALGO-RESEARCH §1 的信号强度分级）。
 *
 * 分档口径：<[QUALITY_TIER1_MAX]（25%）负向 / 25~[QUALITY_TIER2_MAX]（60%）中性偏正 /
 * 60~[QUALITY_TIER3_MAX]（95%）正 / ≥95% 强正。
 *
 * 防双算与防虚高：
 *  - 秒划（watchSec < [SKIP_FAST_SEC]）不叠加档位惩罚——它已在 decayedChannelWeights
 *    吃 -0.5 等效比例倒扣，重复罚会误伤"刷得快但喜欢"的频道（设计稿 §8.6 自认风险）；
 *  - 图文等无真实时长的条目（durationSec<=0）无档可分直接跳过——图文观看比例恒 1.0
 *    是已知虚高源（ALGO-AUDIT §2.2 风险 4），不能再让它通吃强正档加剧；
 *  - 档位 delta 量级取 CES 基准单位（0.15）的 1/3~1/5：观看是高频事件、互动是低频事件，
 *    共用 [INTERACTION_BOOST_CAP] 封顶，防观看量把 CES 互动光环稀释/刷爆。
 */
internal fun FeedViewModel.applyWatchQualityBoost(chatId: Long, watchSec: Int, durationSec: Int) {
    if (chatId == 0L || durationSec <= 0) return // 无频道归属/无真实时长：无档可分
    if (watchSec < SKIP_FAST_SEC) return // 秒划已在权重侧倒扣（skip 口径），不双算
    val ratio = (watchSec.toDouble() / durationSec).coerceIn(0.0, 1.0)
    val delta = when {
        ratio < QUALITY_TIER1_MAX -> WATCH_QUALITY_LT25   // <25% 负向
        ratio < QUALITY_TIER2_MAX -> WATCH_QUALITY_25_60  // 25~60% 中性偏正
        ratio < QUALITY_TIER3_MAX -> WATCH_QUALITY_60_95  // 60~95% 正
        else -> WATCH_QUALITY_GE95                        // ≥95% 强正（完播档）
    }
    addInteractionBoost(chatId, delta)
}

/**
 * 观看结算 → 会话信号分类（P1 #4）：快划（<[SESSION_SKIP_SEC]=3s）/ 强正（完播
 * ratio≥[COMPLETE_RATIO]，仅真实时长条目）/ 其余中性。供 settleWatchEvent 与
 * recordMediaView 共用同一分类口径。
 */
internal fun FeedViewModel.watchSessionSignal(watchSec: Int, durationSec: Int): Int = when {
    watchSec < SESSION_SKIP_SEC -> SESSION_SIGNAL_SKIP
    durationSec > 0 &&
        (watchSec.toDouble() / durationSec).coerceIn(0.0, 1.0) >= COMPLETE_RATIO ->
        SESSION_SIGNAL_STRONG_POS
    else -> SESSION_SIGNAL_NEUTRAL
}

/**
 * 观看心跳（P0-2）：每跳确认主位播放器"真在播"当前计量条目，是则给 [watchingAccum]
 * 累计 [WATCH_HEARTBEAT_MS]×当前倍速 的内容消费毫秒（R110 起随倍速折算：
 * 3x 播放一跳记 6s 内容，完播率/节奏画像不再失真）。判定链（任一不满足即本跳不计）：
 *  1. 悬浮窗盖顶且窗内不是当前条目 → 不计（弹别的条目小窗播放时刷 Feed 不误记到当前条）；
 *  2. [PlayerPool.lastActivePlayer] 为空或 !isPlaying → 不计——isPlaying 内部已含
 *     playWhenReady && STATE_READY && 无播放抑制：暂停/缓冲/后台闸拉停（onStop 时
 *     后台行为=暂停会 pause 当前页）自然为 false，即"暂停/切后台不计"；
 *  3. 归属校验——主位播放器播的必须是当前计量条目，防分屏/音频卡/悬浮窗抢主位误计：
 *      - 音频卡共享播放器约定 mediaId == item.key（AudioCard 的归属判据）；
 *      - 视频页用 MediaItem.fromUri（mediaId 为空）：条目有 playUri 时比对播放地址
 *       （自动重试换路后地址会变，配不上宁可少计、不脏数据）；
 *      - 地址无从比对（TDLib 流式条目 playUri 为空）时回落旁证：Feed 页帧提供者在线
 *       （AppContainer.currentFrameProvider——离开 Feed 页即注销；分屏/设置/我的等
 *       与 Feed 互斥的路由分支 dispose Feed 后，主位播放器再响也不算 Feed 的观看）。
 * 主线程执行：与 settleWatchEvent 对 [watchingAccum] 的读写同线程，无竞态。
 */
internal fun FeedViewModel.watchHeartbeatTick() {
    val w = watchingAccum ?: return
    val (item, playedMs) = w
    val container = (app as? dev.tgfeed.App)?.container
    if (container != null) {
        val floating = container.floatingItems
        // 悬浮窗里播的是别的条目：当前条的"观看"其实是小窗在出声，不计给 Feed 当前条
        if (floating.isNotEmpty() && floating.none { it.key == item.key }) return
    }
    val player = PlayerPool.lastActivePlayer ?: return
    // 暂停/缓冲/后台：isPlaying 一关全挡住（玩家 getter 在释放后调用也安全，仍包 runCatching 兜底）
    if (!runCatching { player.isPlaying }.getOrDefault(false)) return
    val owned = runCatching {
        val mediaId = player.currentMediaItem?.mediaId
        val uri = player.currentMediaItem?.localConfiguration?.uri?.toString()
        when {
            !mediaId.isNullOrEmpty() -> mediaId == item.key // 音频卡归属约定
            !item.playUri.isNullOrEmpty() && uri != null -> uri == item.playUri // 本地/直链条目对地址
            else -> container?.currentFrameProvider != null // 流式条目：Feed 页在场旁证
        }
    }.getOrDefault(false)
    if (!owned) return
    // R110：心跳按当前播放倍速折算成"内容消费毫秒"——旧实现只加墙钟
    // [WATCH_HEARTBEAT_MS]，3x 完播 watchSec≈时长/3 永远摸不到完播档、
    // 0.75x 反向虚高被 clamp 成完播。倍速每跳现读播放器（面板即调即准），
    // 异常/异常值回落 1x。
    val speed = runCatching { player.playbackParameters.speed }
        .getOrDefault(1f).takeIf { it.isFinite() }?.coerceIn(0.1f, 8f) ?: 1f
    watchingAccum = item to (playedMs + (WATCH_HEARTBEAT_MS * speed).toLong())
    // R163 B4：真在播（过了归属与 isPlaying 闸）→ 刷新播放活动时刻（闲置宽限基准）。
    // 含后台音频在听（isPlaying 真）——用户在消费内容，时机窗口不该起跑爬取
    lastPlaybackActivityMs = System.currentTimeMillis()
}

/**
 * 观看序列共现边（P1-b #7，BRAINSTORM-GRAPH B4）：同会话内相邻观看的两个不同频道
 * 互建 co_watch 双向弱边（图谱是有向图，无向共现用两条反向边表达；边权由
 * DiscoveryRanker 的 TW 表给 0.3，低于一切主动互引边——只作既有候选的佐证，
 * 单独共现不足以让候选出榜）。
 * 时限 [CO_WATCH_WINDOW_MS]=30 分钟会话窗：超过即视为两个独立观看上下文，不建边。
 * 调用点：视频翻页结算（settleWatchEvent）与图文/音频有效观看（recordMediaView），
 * 纯本地聚合（watch 事件的即时消费），零新增请求。
 */
internal fun FeedViewModel.recordCoWatchEdge(chatId: Long) {
    if (chatId == 0L) return
    val now = System.currentTimeMillis()
    val prev = lastWatchedChannel
    lastWatchedChannel = chatId to now
    if (prev == null || prev.first == chatId) return // 首次观看/同频道连看不建边
    if (now - prev.second > CO_WATCH_WINDOW_MS) return
    val gw = gateway as? TdLibGateway ?: return
    runCatching { gw.discoveryGraph.recordEdge(prev.first, chatId, DiscoveryGraph.EDGE_CO_WATCH) }
    runCatching { gw.discoveryGraph.recordEdge(chatId, prev.first, DiscoveryGraph.EDGE_CO_WATCH) }
}

/**
 * 图文/音频观看进画像（任务：PhotoCard/AudioCard 的观看也记入 watch_events）。
 * 由 UI 侧计时回调触发（PhotoCard 幻灯片走完一轮 ~4s 时上报；AudioCard 播多久记多久，
 * 停止/切走时上报实际播放毫秒）。
 *  - 图文（item.durationSec <= 0，无时长概念）：durationSec 记为与 watchSec 同值，
 *    观看比例恒为 1.0（完播语义）——权重函数里 durationSec<=0 的事件比例按 0 算，
 *    必须填同值才能表达"看完了"；
 *  - 音频（有真实 durationSec）：watchSec=实际播放秒数，比例=真实观看比例，
 *    听不满 2 秒自然落入 skip 档（秒划负反馈，语义与视频一致）；
 *  - 低于 [MIN_MEDIA_VIEW_MS] 的曝光视为噪声不记（对齐"有效观看"口径）；
 *  - 与视频页的 watchingAccum 翻页结算相互独立：本方法只追加事件，不动计时状态。
 */
internal fun FeedViewModel.recordMediaViewImpl(item: VideoItem, watchMs: Long) {
    if (watchMs < MIN_MEDIA_VIEW_MS) return
    val watchSec = (watchMs / 1000L).coerceIn(1L, MAX_WATCH_SEC).toInt()
    val durSec = if (item.durationSec > 0) item.durationSec else watchSec
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { store.appendWatchEvent(item.chatId, watchSec, durSec) }
        // 指标闭环：图文/音频"有效观看"近似一次曝光 + 一次观看结算（口径说明见
        // InteractionStats 类注释）；统计用原始 durationSec——图文无真实时长传 0，
        // 不进完播率分母（防"图文恒完播"虚高，ALGO-AUDIT §2.2 风险 4）
        interactionStats.recordImpression(item.chatId)
        interactionStats.recordWatch(item.chatId, watchSec, item.durationSec)
        if (watchSec < SKIP_FAST_SEC) interactionStats.recordNegative("skip", item.chatId)
        // P1 #2 完播率分档加权：仅真实时长条目（音频）；图文 durationSec<=0 内部直接跳过
        applyWatchQualityBoost(item.chatId, watchSec, item.durationSec)
        // P1 #4 会话内实时适应：与视频结算同一分类口径
        noteSessionSignal(item.chatId, watchSessionSignal(watchSec, item.durationSec))
        // 口味雷达升级：看过内容的 caption 分词喂口味表（真内容口味 > 频道自定位）。
        // 按观看时长加权：看得越久这个词越算数
        if (item.caption.isNotBlank()) {
            runCatching {
                val words = tasteRadar.tokenize(item.caption)
                    .filter { it.isNotBlank() }
                    .distinct()
                if (words.isNotEmpty()) {
                    store.addTasteWords(words, score = (watchSec / 10.0).coerceIn(0.2, 3.0))
                }
            }
        }
        // P1-b #7：图文/音频观看同样参与观看序列共现（与视频翻页结算共用 30 分钟会话窗）
        recordCoWatchEdge(item.chatId)
        // P1.5 #5：图文/音频有效观看同样累计会话疲劳时长（与视频心跳结算同口径）
        sessionWatchMs.addAndGet(watchMs)
        // P1.5 #4：图文/音频 caption 也喂会话序列动量表（与视频结算同加权口径）
        feedSessionMomentum(item.caption, watchSec)
        // P2：短期轨与马尔可夫转移同口径记账（图文/音频与视频属同一条观看序列；
        // durSec 已按图文记法折算过——durationSec<=0 的图文填同 watchSec，比例语义不扭曲）
        interestProfile.recordWatch(item.chatId, watchSec, durSec)
        // R79 隐式反馈链 + 观看节奏画像（图文/音频无回看检测，replayed 恒 false；
        // 节奏比例用原始 durationSec——图文 <=0 不计入比例分母，防"图文恒完播"虚高）
        implicitFeedback.recordSettle(
            item.key, item.chatId, item.mediaType, watchSec, item.durationSec, replayed = false,
        )
        // R298：图文/音频同样记 tag/类型兴趣轴（与视频结算同一口径）
        runCatching {
            tagInterestLedger.recordSettle(
                foldTagsOf(item), item.mediaType, watchSec, item.durationSec,
            )
        }
        if (watchSec >= SKIP_FAST_SEC) {
            noteRhythmStyle(
                interestProfile.recordWatchRhythm(
                    item.chatId, watchSec, item.durationSec, replayed = false,
                ),
            )
        }
        if (watchSec >= SKIP_FAST_SEC) {
            val toWords = runCatching { tasteRadar.tokenizeForScoring(item.caption) }
                .getOrDefault(emptyList()).toSet()
            val ctx = lastWatchedTaste
            if (ctx != null) {
                interestProfile.recordTransition(ctx.first, ctx.second, toWords, item.chatId)
            }
            lastWatchedTaste = toWords to item.chatId
        }
    }
}

/**
 * R163 B5 死媒体终态记账：播放失败落到不可重试终态时记一笔 deadmedia 负反馈
 * （InteractionStats NEGATIVE 流水，sub=[InteractionStats.DEAD_MEDIA_SUB]）。
 * 纯本地内存操作（锁内 append + 节流异步落盘），纳秒级返回，任意线程可调；
 * 消费方：DeadMediaPolicy 降权（FeedRankingPart 两出口）与
 * IndexTierPolicy.RichnessSignals.deadRate（丰度分负分量，B4 接线）。
 * chatId=0（异常条目）防御性丢弃——没有频道归属的死链无从降权。
 */
internal fun FeedViewModel.recordDeadMediaImpl(chatId: Long) {
    if (chatId == 0L) return
    runCatching { interactionStats.recordDeadMedia(chatId) }
}
