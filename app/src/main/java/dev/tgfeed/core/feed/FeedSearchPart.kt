package dev.tgfeed.core.feed

import androidx.lifecycle.viewModelScope
import dev.tgfeed.core.ocr.OcrPolicy
import dev.tgfeed.core.prefs.AppPrefs
import dev.tgfeed.core.store.WatchEvent
import dev.tgfeed.core.td.ChannelBrief
import dev.tgfeed.core.td.SearchTextKit
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.net.URL

// ---------------- M1 全量搜索（已加入频道 100% 覆盖，SEARCH-ARCH §2.1 L1a 逐频道扇出） ----------------

/**
 * 一次全量搜索的会话状态（SEARCH-ARCH §4.3 per-lane 游标模型的 M1 落地）：
 * 每频道自己的续页游标/连续失败计数互不影响；首波按口味权重搜前 [FULL_SEARCH_FIRST_WAVE] 个，
 * 剩余频道进 [pending] 队列随 [fullSearchMore] 惰性扇出（N 很大时砍掉尾部成本，§5 预算表）。
 * 纯数据载体，只在本 VM 的搜索协程里读写（ConcurrentHashMap 保护扇出并发写）。
 */
internal class SearchSession(val query: String) {
    /** 应搜频道总数（覆盖度分母；含归档/分组，建会话时定格） */
    var total = 0

    /** 待惰性扇出的尾部频道队列（权重降序）；@Volatile：fullSearchMore 在主线程读、搜索协程在 IO 写 */
    @Volatile
    var pending: List<ChannelBrief> = emptyList()

    /** 频道权重表快照（扇出排序与结果粗排共用同一份口味画像） */
    var weights: Map<Long, Double> = emptyMap()

    /** 查询分析快照（SEARCH-QUALITY §2：bigram 词位/同义扩展/简繁折叠，建会话时一次性完成） */
    var aq: SearchTextKit.AQuery? = null

    /** 模糊搜索开关快照（AppPrefs.fuzzySearch，建会话时定格）：
     *  false = 精确（fold 归一后按空格切段、各段 AND 连续子串）；
     *  true = 模糊（bigram 词位覆盖 / ED≤1 / 同义词扩展）。
     *  同时参与搜索会话缓存键（不同口径的结果不共享缓存，见 searchCacheKey） */
    var fuzzy: Boolean = false

    /**
     * 条目级人工信号快照（SEARCH-QUALITY §5）：赞/收藏/看过/下载 的 key 集。
     * 建会话时从内存态 StateFlow 定格（零磁盘 IO）；本轮搜索内不回灌——
     * 搜索进行到一半点赞不回改当前排序（防 publish 重排抖动），下轮搜索生效。
     */
    var signals: SearchSignals = SearchSignals()

    /** 每频道续页游标（SearchChatMessages 的 nextFromMessageId 字符串）；到底/熔断的频道不在表内 */
    val cursors = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /** 每频道连续失败次数；≥[FULL_SEARCH_MAX_FAILURES] 熔断跳过（绝不影响其他频道） */
    val failures = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    /** 已聚合结果池：item.key → (条目, 粗排分)；归并去重在写入时完成 */
    val scored = java.util.concurrent.ConcurrentHashMap<String, Pair<VideoItem, Double>>()

    /**
     * 已反哺发现图谱的条目 key 集（SEARCH-PERF §5）：publishFullSearch 每次全量遍历
     * scored 做 recordEdge，旧实现下 N 次 publish × M 条结果 = N×M 次图谱写——
     * 改增量记录后每条目每会话只反哺一次（ ConcurrentHashMap.newKeySet 的 add 原子判重）
     */
    val graphedKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 上次结果发布上屏的时间戳（频道腿发布节流用）：540 频道一轮每频道完成都发布一次，
     * 就是 30~540 次"全量重排 + 新列表 + 网格重组"；节流把完成洪峰合并成 ≤1 次/120ms，
     * 波尾/收尾由调用方强制发布兜底（尾部结果不丢）。@Volatile：扇出协程并发读写
     */
    @Volatile
    var lastPublishMs: Long = 0L

    /** 已搜完频道数（覆盖度分子；成功/0 命中/熔断三种终态都计入，页脚如实定格） */
    val done = java.util.concurrent.atomic.AtomicInteger(0)
}

/**
 * 搜索会话结果缓存条目（SEARCH-PERF §1）：同词 [SEARCH_SESSION_CACHE_TTL_MS] 内
 * 直接复用上次聚合结果上屏，不重新扇出。只存 scored 快照与覆盖度定格值——
 * 游标不缓存（缓存命中的会话没有续页能力，fullSearchMore 自然空转，见 fullSearch 命中分支）。
 */
internal class CachedSearchSession(
    val atMs: Long,
    val scored: List<Pair<VideoItem, Double>>,
    val total: Int,
    val done: Int,
)

/**
 * 同词搜索会话缓存（LRU，容量 [SEARCH_SESSION_CACHE_MAX]）：
 * 主线程写（fullSearch 入口）、IO 协程读（扇出协程开头），synchronized 保护。
 */

internal fun FeedViewModel.cacheSearchSession(session: SearchSession) {
    val scored = session.scored.values.toList()
    if (scored.isEmpty()) return
    val snapshot = CachedSearchSession(
        atMs = System.currentTimeMillis(),
        scored = scored,
        total = session.total,
        done = session.done.get(),
    )
    synchronized(searchSessionCacheLock) {
        // 键带口径位（模糊开关）：同词的精确/模糊结果不共享缓存——切开关后同词重搜
        // 不会命中旧口径快照（否则开关形同虚设）
        searchSessionCache[searchCacheKey(session.query, session.fuzzy)] = snapshot
        while (searchSessionCache.size > SEARCH_SESSION_CACHE_MAX) {
            val eldest = searchSessionCache.keys.firstOrNull() ?: break
            searchSessionCache.remove(eldest) // LinkedHashMap 插入序：首位即最旧词条
        }
    }
}

/** 条目级人工信号表（SEARCH-QUALITY §5）：四个 key 集全来自内存态，建会话时快照 */
internal class SearchSignals(
    val liked: Set<String> = emptySet(),       // 点赞过
    val favorited: Set<String> = emptySet(),   // 收藏过
    val watched: Set<String> = emptySet(),     // 看过（观看历史；完播无条目级数据，用它做代理）
    val downloaded: Set<String> = emptySet(),  // 下载过
)

/** 全量搜索结果（逐频道扇出，边到边聚合上屏；按 searchScore = 权重×新鲜度保底×相关性×信号 降序） */

/**
 * 答案行的零网络标题（"识别中…"挂死修复之一）：chatId==0 的用户名链接先借
 * [answerLinkChatCache] 升级成真 chatId；然后依次看 网关零网络标题快照
 * （[TdLibGateway.peekChatTitle]：进程内 channelTitleCache + 图谱 nodeTitle 两张表）
 * → 订阅/频道列表。旧版只看图谱 nodeTitle 一张表——chatId 已知但图谱没沉淀标题的
 * 频道恒挂"识别中…"，是"秒回区一大堆识别中"的主因之一；全空才轮到 UI 显示"识别中…"
 */
internal fun FeedViewModel.answerTitleOf(ans: GroupAnswer): String {
    val chatId = if (ans.chatId != 0L) ans.chatId else answerLinkChatCache[ans.link] ?: 0L
    if (chatId != 0L) {
        (gateway as? TdLibGateway)?.peekChatTitle(chatId)?.let { return it }
        _subscriptions.value.firstOrNull { it.id == chatId }?.title?.let { return it }
        _channels.value.firstOrNull { it.id == chatId }?.title?.let { return it }
    }
    // 按钮标题线索兜底（429 封死 SearchPublicChat 时的零网络标题——
    // jisou 类 bot 应答的内联按钮文本通常就是频道名；无线索返回空串走 @用户名直报）
    return ans.hintTitle
}

/**
 * 答案区频道行"点击即解析"（批量标题解析的兜底）：行的 chatId 未就位（批量解析
 * 失败/被 flood 拒/不在当批窗口）时，用户点击当下现场 SearchPublicChat 换一次
 * chatId——批量链漏掉的，用户主动点的那一下绝不让它落空。成功顺手回填
 * [answerLinkChatCache] 并刷两区行（可点态/标题即时升级）。
 * @return 解析出的 chatId；失败（用户名失效/非公开/限流）返回 null（调用方保持不可点静默）
 */
internal suspend fun FeedViewModel.resolveAnswerChatIdImpl(link: String, username: String): Long? {
    val real = gateway as? TdLibGateway ?: return null
    answerLinkChatCache[link]?.let { return it }
    val r = runCatching {
        real.resolvePublicChatByUsername(username, dev.tgfeed.core.td.SearchQuotaLedger.Lane.USER_ACTION)
    }.getOrNull() ?: return null
    if (!r.ok) return null
    answerLinkChatCache[link] = r.chatId
    // 回填后重刷两区行：刚点的行即时升级可点+真标题（不等下一轮搜索）。
    // 秒回区就地从当前状态重建（publish 闭包在 liveAskSearch 内部，此处摸不到，
    // 逐行重算 title/openChatId 即可，item/来源字段原样保留）
    _liveAsk.value?.let { st ->
        if (st.links.any { it.answer.link == link }) {
            _liveAsk.value = st.copy(links = st.links.map { la ->
                if (la.answer.link == link) la.copy(
                    title = answerTitleOf(la.answer),
                    openChatId = if (la.answer.chatId != 0L) la.answer.chatId
                    else answerLinkChatCache[la.answer.link] ?: 0L,
                ) else la
            })
        }
    }
    _groupAnswers.value?.let { (q, list) -> if (list.any { it.link == link }) publishGroupAnswerRows(q) }
    return r.chatId
}

/**
 * 已加入会话 id 集（答案区"只摆没加入的新源"过滤口径——投诉"群友搜过的频道都是
 * 我以前加过的，本地也能搜出来，显示的意义在哪"的修复）：listChannels（网关侧
 * 5min TTL 缓存，本轮零额外请求）∪ 订阅集；listChannels 失败回落订阅集
 * （口径宁松勿紧：漏滤已加入只是多显示一条，误滤未加入就丢了新源发现的意义）
 */
internal suspend fun FeedViewModel.joinedChatIdsForAnswers(): Set<Long> {
    val ids = _subscriptions.value.mapTo(HashSet()) { it.id }
    runCatching { gateway.listChannels() }.getOrDefault(emptyList()).forEach { ids += it.id }
    return ids
}

/**
 * 搜索联动·显式提交时并行问已发现搜索实体（KIND_BOT 优先、搜索群其次，
 * 实体清单见 [TdLibGateway.liveAskEntities]）。
 * **只在搜索页显式提交（IME 搜索键/建议 chip/历史 chip/带词进页自动开搜）时由 UI 调用；
 * 防抖实时搜索绝不触发——输入过程不发词，只在显式提交时发。**
 *
 * 纪律：
 *  - 一词一轮：同词 fold 后 [LIVE_ASK_COOLDOWN_MS] 内不重复发词（旧轮状态属同词则原样保留展示）；
 *  - 预算车道：每个实体"发送+监听"整轮记 1 次**秒回专用车道**预算（网关侧 liveAskBudget，
 *    与收割车道 GroupHarvester.harvestBudget 彻底解耦——后台收割吃光预算不饿死
 *    用户主动秒问）；全部实体预算耗尽可能轮空，轮空不记冷却（下次重试不受阻）；
 *  - 静默收场：无实体/bot 没回/超时都只让本区定格"无回复"或整区隐藏，绝不挡主搜索结果；
 *  - 黑名单：AdChannelClassifier 运行时快照拉黑的实体在网关侧剔除，不发。
 *
 * 解析与回灌（网关侧 askSearchEntity 内完成）：应答消息每有变化即解析——t.me 链接过广告闸
 * （AdBlacklist 特征 + AdChannelClassifier 黑名单）后成 GroupAnswer 实时流入本状态，
 * 无链接纯文本成文本答案卡；过闸新链接即时回灌 AnswerStore（recordAnswer 自带同链接
 * 去重累 hits）+ 图谱 group_search 边 + EntityResolver 懒解析入队，轮末
 * GoldmineHooks.onHarvested 记一次热词——收割管道的热词/新发现因此更准。
 *
 * eager 解析（用户拍板"直接展示成内容"）：链接流入即按前 [TdLibGateway.ANSWER_INLINE_RESOLVE_MAX]
 * 条批量解析成可播条目（首批到达即解析一批 + 窗口关闭补一批，每批记 1 次秒回车道），
 * 解析出的直接内容卡内联展示、点击原地播放；解析不出/没轮到的回落标题行。
 */
internal fun FeedViewModel.liveAskSearchImpl(query: String) {
    val q = query.trim()
    if (q.isEmpty()) return
    val real = gateway as? TdLibGateway ?: return
    val folded = SearchTextKit.fold(q).trim()
    if (folded.isEmpty()) return
    val now = System.currentTimeMillis()
    val lastSent = liveAskSentAt[folded] ?: 0L
    if (now - lastSent < LIVE_ASK_COOLDOWN_MS) {
        android.util.Log.d("FeedViewModel", "liveAsk: 「$q」在冷却期内（一词一轮），不重发")
        return
    }
    // R366 liveAsk 写闸（§十 缺口收口——R364 涉写事故正治：真机腿未开闸误发 3 条
    // 关键词消息）：测试闸开=如实提示「写保护中」，零发送零等待零永挂；不记一词一轮
    // 冷却（本轮什么都没发，闸关后重试不受阻）。网关侧 askSearchEntity 同口再拦一道
    // （权威拦截点），本处是 UI 层的如实提示与零等待保证
    if (real.accountWriteBlocked()) {
        android.util.Log.w(
            "FeedViewModel",
            "liveAsk: 账号写保护中（agent_no_account_write 测试闸），本轮发词被拦，零发送",
        )
        _liveAsk.value = LiveAskState(q, asking = false, entities = 0, links = emptyList(), texts = emptyList(), writeBlocked = true)
        return
    }
    liveAskJob?.cancel()
    val myGen = ++liveAskGen // 新轮代次：旧轮的一切发布被代次闸丢弃
    liveAskJob = viewModelScope.launch(Dispatchers.IO) {
        val entities = runCatching { real.liveAskEntities() }.getOrDefault(emptyList())
        if (entities.isEmpty()) {
            android.util.Log.d("FeedViewModel", "liveAsk: 无可用搜索实体，本轮静默 q=$q")
            return@launch
        }
        // 秒回区与"群友搜到过"同口径（投诉2）：指向已加入频道的答案没有增量意义，
        // 整轮滤掉只留新源；chatId==0 的用户名链接归属未知保留。一轮算一次（缓存便宜）
        val joinedForAnswers = joinedChatIdsForAnswers()
        _liveAsk.value = LiveAskState(q, asking = true, entities = entities.size, links = emptyList(), texts = emptyList())
        android.util.Log.i(
            "FeedViewModel",
            "liveAsk: 「$q」发词 ${entities.size} 个实体（${entities.joinToString { it.title }}）",
        )
        // 跨实体聚合：每实体最新批次按 chatId 归桶（bot 编辑消息重发批次时同桶覆盖），
        // 桶内只存原始素材（答案+来源名），eager 解析产物与标题在 publish 时现映射——
        // 解析完成只改 answerItemCache 再 publish 即换卡，不动桶结构
        val byEntity = java.util.concurrent.ConcurrentHashMap<Long, Pair<List<Pair<GroupAnswer, String>>, List<LiveTextAnswer>>>()
        val order = entities.map { it.chatId }
        fun publish(askingNow: Boolean) {
            if (myGen != liveAskGen) return // 旧轮收尾/迟到批次：丢弃，不盖新轮状态
            val allLinks = LinkedHashMap<String, LiveLinkAnswer>()
            val allTexts = ArrayList<LiveTextAnswer>()
            val seenText = HashSet<String>()
            for (id in order) {
                val (ls, ts) = byEntity[id] ?: continue
                for ((ans, fromTitle) in ls) {
                    // 频道主页类链接再按用户名去重（bot 自链/带不同 query 串的
                    // 同频道变体只露一条，与历史区 publishGroupAnswerRows 同口径）
                    if (!TdLibGateway.isMessageLevelLink(ans.link) && ans.username.isNotBlank()) {
                        val ukey = ans.username.trim().removePrefix("@").lowercase()
                        if (allLinks.values.any {
                                it.answer.username.trim().removePrefix("@").lowercase() == ukey
                            }
                        ) continue
                    }
                    allLinks.putIfAbsent(
                        ans.link,
                        LiveLinkAnswer(
                            ans, fromTitle, answerItemCache[ans.link], answerTitleOf(ans),
                            openChatId = if (ans.chatId != 0L) ans.chatId else answerLinkChatCache[ans.link] ?: 0L,
                        ),
                    )
                }
                for (t in ts) if (seenText.add(t.fromTitle + "|" + t.text)) allTexts += t
            }
            // R48 行序：可播内容卡仍前排不动；频道行按成员数降序（0=未知沉底），
            // 同量级保持原序（sortedWith 稳定，桶序 = 实体序天然保底）
            val orderedLinks = allLinks.values.sortedWith(
                compareByDescending<LiveLinkAnswer> { if (it.item != null) 1 else 0 }
                    .thenByDescending { it.answer.members },
            )
            _liveAsk.value = LiveAskState(q, askingNow, entities.size, orderedLinks, allTexts)
        }
        // eager 解析触发（每轮至多两批：首批链接到达一批 + 窗口关闭补一批，控制预算与请求量）：
        // 取展示窗前 [TdLibGateway.ANSWER_INLINE_RESOLVE_MAX] 条中"未解析/未死信/未在飞"的链接，
        // 整批走网关 resolveLiveAnswerItems（秒回车道 1 次/批）；null=预算耗尽未尝试（不进死信）
        var firstResolveKicked = false
        fun kickLiveResolve() {
            val st = _liveAsk.value ?: return
            if (st.query != q) return
            // 链接形态分流（根修"秒回区只有裸用户名行"）：频道主页类裸链接（t.me/<name>）
            // 恒被 GetMessageLinkInfo 拒（"Wrong message link URL"）——不进解析窗、不染死信表，
            // 直接交给 kickLiveTitleResolve 的 SearchPublicChat 链换真 chatId+标题+可点；
            // 只有消息级链接（t.me/<name>/<id>、t.me/c/...）才进 GetMessageLinkInfo 内容卡解析
            val pending = st.links.map { it.answer.link }
                .filter { dev.tgfeed.core.td.TdLibGateway.isMessageLevelLink(it) }
                .take(TdLibGateway.ANSWER_INLINE_RESOLVE_MAX) // 分流后再取窗：频道链接不占内容卡名额
                .filter { !answerItemCache.containsKey(it) && it !in answerResolveDead && answerResolveInFlight.add(it) }
            if (pending.isEmpty()) return
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val resolved = real.resolveLiveAnswerItems(pending)
                    if (resolved != null) {
                        for ((link, item) in resolved) {
                            answerItemCache[link] = item
                            // 解析成功顺手沉淀：link→chatId 回填（用户名链接标题行升级用）
                            // + 频道标题写图谱（下轮同频道答案直接带标题，不再挂"识别中…"）
                            answerLinkChatCache[link] = item.chatId
                            if (item.channelTitle.isNotBlank()) {
                                runCatching { real.discoveryGraph.recordNodeTitle(item.chatId, item.channelTitle) }
                            }
                        }
                        val okLinks = resolved.mapTo(HashSet()) { it.first }
                        for (link in pending) if (link !in okLinks) answerResolveDead += link // 死信不再重试
                    }
                } finally {
                    answerResolveInFlight.removeAll(pending.toSet())
                }
                if (myGen == liveAskGen) {
                    publish(_liveAsk.value?.takeIf { it.query == q }?.asking ?: false)
                }
            }
        }
        // "识别中"标题行懒解析（与历史区同口径）：窗口关闭时对仍挂"识别中"的用户名链接
        // 小预算 SearchPublicChat 换真标题，成功回填 answerLinkChatCache 并重新发布——
        // 频道主页类链接永远出不了内容卡，带真标题的频道行才是它们的正确呈现
        fun kickLiveTitleResolve() {
            val st = _liveAsk.value ?: return
            if (st.query != q) return
            val pendingAns = st.links.filter { la ->
                la.item == null && la.answer.chatId == 0L &&
                    la.answer.username.isNotBlank() && answerTitleOf(la.answer).isEmpty()
            }.map { it.answer }
            if (pendingAns.isEmpty()) return
            viewModelScope.launch(Dispatchers.IO) {
                val outcome = real.resolveAnswerTitles(pendingAns.map { it.username }, live = true)
                if (outcome != null) {
                    for (ans in pendingAns) {
                        val hit = outcome.titles[ans.username.trim().removePrefix("@").lowercase()] ?: continue
                        answerLinkChatCache[ans.link] = hit.first
                    }
                    if (myGen == liveAskGen) {
                        publish(_liveAsk.value?.takeIf { it.query == q }?.asking ?: false)
                    }
                }
                // 网页泳道补标题（SearchPublicChat 账号级 429 封死时与 TDLib 完全隔离的 IP 泳道）：
                // TDLib 泳道跑完后仍挂"识别中"的用户名走 t.me/s/ 预览页换真频道名，
                // 成功回填答案库 hintTitle + 就地升级 byEntity 里的答案对象再重发行状态
                val stNow = _liveAsk.value
                if (stNow != null && stNow.query == q) {
                    val remaining = stNow.links.filter { la ->
                        la.item == null && la.answer.chatId == 0L &&
                            la.answer.username.isNotBlank() && answerTitleOf(la.answer).isEmpty()
                    }.map { it.answer }
                    if (remaining.isNotEmpty()) {
                        val webInfos = real.resolveTitlesViaWeb(remaining.map { it.username })
                        if (webInfos.isNotEmpty()) {
                            for (ans in remaining) {
                                val info = webInfos[ans.username.trim().removePrefix("@").lowercase()] ?: continue
                                // R48：标题与成员数（og:description 解析）一并回填答案库
                                runCatching { real.groupHarvester.answerStore.backfillHintTitle(ans.link, info.title, info.members) }
                            }
                            // publish 从 byEntity 现重建行——必须把桶里的旧答案对象换成 hintTitle/members 升级版
                            var touched = false
                            for (id in order) {
                                val bucket = byEntity[id] ?: continue
                                val upgraded = bucket.first.map { (ans, fromTitle) ->
                                    val info = webInfos[ans.username.trim().removePrefix("@").lowercase()]
                                    val newTitle = if (ans.hintTitle.isBlank()) info?.title ?: ans.hintTitle else ans.hintTitle
                                    val newMembers = if (ans.members <= 0) info?.members ?: ans.members else ans.members
                                    if (newTitle != ans.hintTitle || newMembers != ans.members) {
                                        touched = true
                                        ans.copy(hintTitle = newTitle, members = newMembers) to fromTitle
                                    } else ans to fromTitle
                                }
                                byEntity[id] = upgraded to bucket.second
                            }
                            if (touched && myGen == liveAskGen) {
                                publish(_liveAsk.value?.takeIf { it.query == q }?.asking ?: false)
                            }
                        }
                    }
                }
            }
        }
        try {
            coroutineScope {
                entities.map { entity ->
                    async(Dispatchers.IO) {
                        runCatching {
                            real.askSearchEntity(entity, q) { ent, links, texts ->
                                // 已加入频道的答案不进区（投诉2：秒回区只摆没加入的新源）
                                val newSourceLinks = links.filter { it.chatId == 0L || it.chatId !in joinedForAnswers }
                                byEntity[ent.chatId] =
                                    newSourceLinks.map { it to ent.title } to
                                        texts.map { LiveTextAnswer(ent.title, it) }
                                publish(askingNow = true)
                                if (!firstResolveKicked && newSourceLinks.isNotEmpty()) {
                                    firstResolveKicked = true
                                    kickLiveResolve() // 首批链接到达即解析（内容卡尽早出现）
                                    // 频道链接的标题解析同拍启动（不等窗口关闭——它们是
                                    // 分流后的唯一呈现链，早一秒出真标题就少一秒裸 @用户名）
                                    kickLiveTitleResolve()
                                }
                            }
                        }.onFailure {
                            android.util.Log.w("FeedViewModel", "liveAsk: 实体 ${entity.title} 发问失败（静默）: ${it.message}")
                        }.getOrDefault(false)
                    }
                }.awaitAll()
            }.also { sentFlags ->
                // 有任一实体真实发出才记冷却：全部预算耗尽/发送失败的轮空不挡下次重试
                if (sentFlags.any { it }) liveAskSentAt[folded] = now
            }
        } finally {
            publish(askingNow = false) // 窗口关闭定格：有结果留区，无结果由 UI 整区隐藏
            kickLiveResolve() // 窗口关闭补一批（收齐晚到的链接；无新链接时自然空转）
            kickLiveTitleResolve() // 窗口关闭补一轮标题懒解析（"识别中"行换真标题）
        }
    }
}

// ---------------- 历史答案区（"群友搜到过"）eager 解析 ----------------

/**
 * "群友搜到过"区增强行状态流：与 [_groupAnswers] 同词绑定（Pair.first=所属关键词），
 * fullSearch 入口查出答案后由 [kickGroupAnswerResolve] 异步构建——eager 解析前 8 条
 * （查询车道整批 1 次），行序 = 已解析可播的在前、识别中的沉底（组内保持答案原序）。
 * null = 从未构建/ Mock 网关。
 */

internal fun FeedViewModel.kickGroupAnswerResolve(q: String) {
    val real = gateway as? TdLibGateway ?: return
    val folded = SearchTextKit.fold(q).trim()
    if (!answerResolvedWords.add(folded)) return // 每词每进程一轮：重复搜索同词零重复解析
    // 同步现读刚写入的答案（调用点在 _groupAnswers 赋值之后、同协程无挂起点，原子安全）——
    // 不能挪进 launch 里读流：快速换词时新词覆盖旧值，旧词会白烧一词一轮闸却零解析
    val answers = _groupAnswers.value?.takeIf { it.first == q }?.second.orEmpty()
    viewModelScope.launch(Dispatchers.IO) {
        // 链接形态分流（与秒回区同根修）：频道主页类裸链接恒被 GetMessageLinkInfo 拒，
        // 不进解析窗/不染死信表，直接走下方 resolveAnswerTitles 的标题链出可点频道行
        val links = answers.map { it.link }
            .filter { dev.tgfeed.core.td.TdLibGateway.isMessageLevelLink(it) }
            .take(TdLibGateway.ANSWER_INLINE_RESOLVE_MAX) // 分流后再取窗：频道链接不占内容卡名额
            .filter { !answerItemCache.containsKey(it) && it !in answerResolveDead && answerResolveInFlight.add(it) }
        if (links.isNotEmpty()) {
            try {
                val resolved = real.resolveGroupAnswerItems(links)
                if (resolved != null) {
                    for ((link, item) in resolved) {
                        answerItemCache[link] = item
                        // 解析成功顺手沉淀：link→chatId 回填（用户名链接标题行升级用）
                        // + 频道标题写图谱（下轮同频道答案直接带标题，不再挂"识别中…"）
                        answerLinkChatCache[link] = item.chatId
                        if (item.channelTitle.isNotBlank()) {
                            runCatching { real.discoveryGraph.recordNodeTitle(item.chatId, item.channelTitle) }
                        }
                    }
                    val okLinks = resolved.mapTo(HashSet()) { it.first }
                    for (link in links) if (link !in okLinks) answerResolveDead += link
                    android.util.Log.i(
                        "FeedViewModel",
                        "群友搜到过 eager 解析：「$q」请求 ${links.size} 条，解析出 ${resolved.size} 条",
                    )
                } else {
                    // 预算耗尽=本轮未尝试：放出一词一轮闸，下次搜同词可重试——
                    // 否则闸已烧掉、解析又没发生，该词整会话恒挂"识别中…"（挂死根因之一）
                    answerResolvedWords.remove(folded)
                    android.util.Log.d("FeedViewModel", "群友搜到过 eager 解析：「$q」预算耗尽未尝试，放出词闸待重试")
                }
            } finally {
                // 在飞集合必须在 finally 里清：旧版异常路径漏清，链接永久卡"在飞"
                // 不再重试也不进死信，对应行永远挂"识别中…"（挂死根因之一）
                answerResolveInFlight.removeAll(links.toSet())
            }
        }
        // "识别中"标题行懒解析（投诉"一直显示识别中"的修复）：没出内容卡、chatId==0 的
        // 用户名链接（频道主页类链接永远出不了内容卡，正确呈现是带真标题的频道行）——
        // 小预算 SearchPublicChat 换真 chatId+标题，成功回填 answerLinkChatCache，
        // publish 时 answerTitleOf 借它出真标题；预算耗尽（null）放词闸下轮重试
        val pendingTitleAnswers = answers.filter { ans ->
            ans.chatId == 0L && ans.username.isNotBlank() &&
                !answerItemCache.containsKey(ans.link) && answerTitleOf(ans).isEmpty()
        }
        if (pendingTitleAnswers.isNotEmpty()) {
            val outcome = real.resolveAnswerTitles(pendingTitleAnswers.map { it.username }, live = false)
            // 预算耗尽（null）或整批被 429 挤掉（hadFlood）：都放闸，下次搜同词重试——
            // 闸已烧掉而解析没成功，该词整会话恒挂裸 @用户名（实证：搜索扇出并发期
            // 历史区标题整批 0/12，是"答案区全是@id"投诉的主根因之一）
            if (outcome == null || outcome.hadFlood) {
                answerResolvedWords.remove(folded)
                // 零网络标题回填（429 封死 SearchPublicChat 时的主链）：读答案来源会话的
                // 最近历史，从内联按钮配对"链接→标题"线索（GetChatHistory 是另一条 API
                // 泳道，实证不在 429 范围）——升级答案库空线索并即时重发行状态
                viewModelScope.launch(Dispatchers.IO) {
                    val fromGroups = pendingTitleAnswers.map { it.fromGroup }.distinct()
                    if (fromGroups.isNotEmpty()) {
                        val hints = real.harvestAnswerTitleHints(fromGroups)
                        var changed = false
                        for ((link, hint) in hints) {
                            // R48：标题与行尾成员数（实体/位置配对解析）一并回填答案库
                            if (real.groupHarvester.answerStore.backfillHintTitle(link, hint.title, hint.members) > 0) changed = true
                        }
                        if (changed) {
                            // 当前行持有的答案对象是旧快照：就地升级 hintTitle/members 后重发
                            val cur = _groupAnswers.value
                            if (cur != null && cur.first == q) {
                                _groupAnswers.value = q to cur.second.map { ans ->
                                    val hint = hints[ans.link]
                                    val newTitle = if (ans.hintTitle.isBlank()) hint?.title ?: ans.hintTitle else ans.hintTitle
                                    val newMembers = if (ans.members <= 0) hint?.members ?: ans.members else ans.members
                                    if (newTitle != ans.hintTitle || newMembers != ans.members) {
                                        ans.copy(hintTitle = newTitle, members = newMembers)
                                    } else ans
                                }
                            }
                            publishGroupAnswerRows(q)
                        }
                    }
                }
                // 限流降级自动补踢（R44）：间隔吃服务端真实 retry-after（clamp 30s~2h）——
                // 短波峰很快补上，封号级长封禁按真实节奏慢踢不硬撞
                // （旧版 clamp 上限 600s：retry after 15000s 被压成每 10 分钟一批白撞墙）；
                // 词闸已放出故重踢有效，publishGroupAnswerRows 在链尾自然重发行状态
                val waitMs = ((outcome?.retryAfterSec ?: 30).coerceIn(30, 7200)) * 1000L
                viewModelScope.launch(Dispatchers.IO) {
                    delay(waitMs)
                    if (_groupAnswers.value?.first == q) kickGroupAnswerResolve(q)
                }
            }
            if (outcome != null) {
                for (ans in pendingTitleAnswers) {
                    val hit = outcome.titles[ans.username.trim().removePrefix("@").lowercase()] ?: continue
                    answerLinkChatCache[ans.link] = hit.first
                }
            }
            // 网页泳道补标题（SearchPublicChat 账号级 429 封死时与 TDLib 完全隔离的 IP 泳道）：
            // TDLib 泳道跑完后仍缺标题的用户名走 t.me/s/ 预览页换真频道名——
            // 回填答案库 hintTitle + 就地升级内存答案对象 + 重发行状态（与上方零网络线索链同套模式）
            val stillMissingTitles = pendingTitleAnswers.filter { answerTitleOf(it).isEmpty() }
            if (stillMissingTitles.isNotEmpty()) {
                val webInfos = real.resolveTitlesViaWeb(stillMissingTitles.map { it.username })
                if (webInfos.isNotEmpty()) {
                    var changed = false
                    for (ans in stillMissingTitles) {
                        val info = webInfos[ans.username.trim().removePrefix("@").lowercase()] ?: continue
                        // R48：标题与成员数（og:description 解析）一并回填答案库
                        if (real.groupHarvester.answerStore.backfillHintTitle(ans.link, info.title, info.members) > 0) changed = true
                    }
                    if (changed) {
                        // 当前行持有的答案对象是旧快照：就地升级 hintTitle/members 后重发
                        val cur = _groupAnswers.value
                        if (cur != null && cur.first == q) {
                            _groupAnswers.value = q to cur.second.map { ans ->
                                val info = webInfos[ans.username.trim().removePrefix("@").lowercase()]
                                val newTitle = if (ans.hintTitle.isBlank()) info?.title ?: ans.hintTitle else ans.hintTitle
                                val newMembers = if (ans.members <= 0) info?.members ?: ans.members else ans.members
                                if (newTitle != ans.hintTitle || newMembers != ans.members) {
                                    ans.copy(hintTitle = newTitle, members = newMembers)
                                } else ans
                            }
                        }
                    }
                }
            }
        }
        // R48 成员数零网络回填（独立小链）：老答案标题已齐（上方标题链不触发）但成员数
        // 未知时，读来源会话最近历史把应答行尾成员数补进库并就地升级重发——
        // GetChatHistory 泳道零 SearchPublicChat 预算，一词随上方词闸每进程至多一轮
        if (answers.any { it.members <= 0 }) {
            val memberFromGroups = answers.filter { it.members <= 0 }.map { it.fromGroup }.distinct()
            if (memberFromGroups.isNotEmpty()) {
                viewModelScope.launch(Dispatchers.IO) {
                    // 成员数回填用大窗口（100 条/会话，单请求上限内）：bot 私聊近 50 条
                    // 可能全是问答流水（提问+门页回复），📢 形态应答在更早的消息里
                    val hints = real.harvestAnswerTitleHints(memberFromGroups, perChatLimit = 100)
                    var changed = false
                    for ((link, hint) in hints) {
                        if (real.groupHarvester.answerStore.backfillHintTitle(link, hint.title, hint.members) > 0) changed = true
                    }
                    if (changed) {
                        // 当前行持有的答案对象是旧快照：就地升级 hintTitle/members 后重发
                        val cur = _groupAnswers.value
                        if (cur != null && cur.first == q) {
                            _groupAnswers.value = q to cur.second.map { ans ->
                                val hint = hints[ans.link]
                                val newTitle = if (ans.hintTitle.isBlank()) hint?.title ?: ans.hintTitle else ans.hintTitle
                                val newMembers = if (ans.members <= 0) hint?.members ?: ans.members else ans.members
                                if (newTitle != ans.hintTitle || newMembers != ans.members) {
                                    ans.copy(hintTitle = newTitle, members = newMembers)
                                } else ans
                            }
                        }
                        publishGroupAnswerRows(q)
                    }
                }
            }
        }
        publishGroupAnswerRows(q)
    }
}

/** 重建并发布历史区行（eager 解析完成后可播行自动前排；频道行组内按成员数降序，0=未知沉底） */
internal fun FeedViewModel.publishGroupAnswerRows(q: String) {
    val answers = _groupAnswers.value?.takeIf { it.first == q }?.second.orEmpty()
    // 频道主页类链接按用户名去重（实证：jisou 的 28 条答案里 6 条都是 @jisou1Bot
    // 自链，答案库按完整链接去重管不住带不同 query 串的变体）——同用户名只留
    // hits 最高的一条；消息级链接各自指向不同消息，不去重
    val deduped = ArrayList<GroupAnswer>(answers.size)
    val byUsername = HashMap<String, Int>() // 小写用户名 → deduped 下标
    for (a in answers) {
        if (!TdLibGateway.isMessageLevelLink(a.link) && a.username.isNotBlank()) {
            val key = a.username.trim().removePrefix("@").lowercase()
            val prevIdx = byUsername[key]
            if (prevIdx == null) {
                byUsername[key] = deduped.size
                deduped += a
            } else if (a.hits > deduped[prevIdx].hits) {
                deduped[prevIdx] = a
            }
        } else deduped += a
    }
    val rows = deduped.map { ans ->
        GroupAnswerRow(
            ans, answerItemCache[ans.link], answerTitleOf(ans),
            openChatId = if (ans.chatId != 0L) ans.chatId else answerLinkChatCache[ans.link] ?: 0L,
        )
    }.sortedWith(
        // R48 行序：可播内容卡仍前排不动；频道行按成员数降序（0=未知沉底），
        // 同量级保持原序（sortedWith 稳定，组内沿用答案 hits/lastTs 序）
        compareByDescending<GroupAnswerRow> { if (it.item != null) 1 else 0 }
            .thenByDescending { it.answer.members },
    )
    _groupAnswerRows.value = q to rows
}

/** 搜索媒体类型 tab：-1 全部 / 0 视频 / 1 图文 / 2 音频（用户反馈"广告图片音频混进来"） */

internal fun FeedViewModel.setSearchMediaTabImpl(t: Int) {
    _searchMediaTab.value = t
    searchSession?.let { publishFullSearch(it) } // 即时重排已聚合结果
}

/**
 * 应用搜索结果筛选（修复包 #1）：与 setSearchMediaTab 同款语义——
 * 即时重排当前已聚合结果（publishFullSearch 里叠加 filter.matches 谓词），
 * 不重新发起搜索；会话态不持久化（与 Feed 筛选一致）。
 */
internal fun FeedViewModel.setSearchFilterImpl(f: FeedFilter) {
    if (f == _searchFilter.value) return
    _searchFilter.value = f
    searchSession?.let { publishFullSearch(it) } // 即时重排已聚合结果
}

internal fun FeedViewModel.fullSearchImpl(query: String) {
    val q = query.trim()
    if (q.isEmpty()) return
    // R371 复现/验证探针（脱敏口径=词长零内容）：扇出入口行——「返回键回搜索页后本行
    // 零新增」即不重搜的 logcat 实证锚；正常新词搜索照常打印
    android.util.Log.i("FeedViewModel", "R371 全量搜索入口：词长=${q.length}")
    fullSearchJob?.cancel()
    val session = SearchSession(q)
    searchSession = session
    _fullSearchResults.value = emptyList()
    _fullSearchCoverage.value = 0 to 0
    _fullSearchLoading.value = true
    _fullSearchStopped.value = false // 新搜索复位"已停止"定格标记（修复包 #2）
    _fullSearchBackground.value = false // 复位后台波段标记（旧会话收尾异步复位可能晚到，这里先压死）
    fullSearchJob = viewModelScope.launch(Dispatchers.IO) {
        try {
            // 会话准备放在 IO 协程里做（旧版在调用方主线程同步做）：连续打字每 300ms
            // 就触发一次 fullSearch，历史/下载 key 集快照随数据规模放大，主线程白付
            // （旧会话随即被新词取消）。查询分析与条目级信号建会话时快照
            // （SEARCH-QUALITY §2/§5）：aq 供 searchScore 词位化打分。
            // watched 口径（R122 修正）：真观看历史 key 集（store.loadHistory，落盘底账）——
            // 旧实现误用 historyReplayItems()（= 当前 Feed 全量内存条目 + 历史补位），
            // 把"当前列表加载过的每一条"都当"看过"，+0.15 信号分近乎人人有份、区分度丧失。
            // （WatchEvent 无条目 key，条目级完播不可直达，历史 key 集仍是"看过"代理）
            session.aq = SearchTextKit.analyze(q)
            session.fuzzy = prefs.fuzzySearch // 模糊开关随会话定格（搜索页切开关会强制重搜，新会话读新值）
            session.signals = SearchSignals(
                liked = _likes.value,
                favorited = _favorites.value,
                watched = runCatching { store.loadHistory() }
                    .getOrDefault(emptyList())
                    .mapTo(HashSet()) { it.first },
                downloaded = _downloadRecords.value.mapTo(HashSet()) { it.key },
            )
            val real = gateway as? TdLibGateway
            // 金矿 P3·答案库缓存层：搜索发起即查（AnswerStore 纯本地磁盘/内存读，
            // 零网络零 flood 预算）——命中由搜索页"资源群答案"区块秒出上屏，
            // 不等频道腿/机器人兜底；未命中为空表，既有搜索路径不受影响
            // 投诉2修复："群友搜到过"区只摆**用户没加入的新源**——指向已加入频道的答案
            // 没有增量意义（本地也能搜出来），VM 侧滤掉（UI 只读过滤后结果，滤空整区隐藏）；
            // chatId==0 的用户名链接归属未知保留（EntityResolver 解析后多半是新源）
            val joinedForAnswers = joinedChatIdsForAnswers()
            _groupAnswers.value = q to (
                real?.let { runCatching { it.queryGroupAnswers(q) }.getOrDefault(emptyList()) }
                    .orEmpty()
                    .filter { it.chatId == 0L || it.chatId !in joinedForAnswers }
                )
            // "群友搜到过"区 eager 解析（用户拍板"直接展示成内容"）：一词每进程一轮、
            // 前 8 条、查询车道整批 1 次记账；解析出的可播条目内联内容卡、点击原地播放。
            // 先于频道腿扇出发起——历史答案卡与频道结果并行就绪，互不等待
            kickGroupAnswerResolve(q)
            if (real == null) {
                // Mock/占位网关：如实定格 0/0（语义 = 没有可搜的已知领域车道）
                return@launch
            }
            // R46 方案 d：搜索证据频道插队（答案库直证 + 图谱 group_search 旁证）——
            // 纯本地读零网络，只喂爬取优先队列，不影响下方主搜索返回
            runCatching { prioritizeSearchEvidence(real, q) }
            // 会话缓存命中（SEARCH-PERF §1）：同词 10 分钟内直接复用上次聚合结果上屏，
            // 不重新扇出、不发任何搜索请求（清单枚举/口味权重也一并省掉）。
            // 覆盖度定格上次的完成值；游标不缓存 → 缓存命中的会话没有续页能力，
            // fullSearchMore 在 cursors/pending 双空时自然空转（文档如实说明）。
            val cached = synchronized(searchSessionCacheLock) {
                searchSessionCache[searchCacheKey(q, session.fuzzy)] // 键带口径位：精确/模糊结果分开缓存
                    ?.takeIf { System.currentTimeMillis() - it.atMs < SEARCH_SESSION_CACHE_TTL_MS }
            }
            if (cached != null) {
                cached.scored.forEach { (item, score) ->
                    session.scored[item.key] = item to score
                    session.graphedKeys.add(item.key) // 缓存条目不重复反哺发现图谱
                }
                session.total = cached.total
                session.done.set(cached.done)
                _fullSearchCoverage.value = cached.done to cached.total
                publishFullSearch(session) // 立即上屏（媒体 tab/筛选谓词照常生效）
                return@launch
            }
            // 前台波段 [FULL_SEARCH_TOTAL_BUDGET_MS]（30 秒）：withTimeoutOrNull 到点
            // 整链取消在飞扇出（delay / 波内 join / 挂起的网络调用都是可取消挂起点），
            // 已聚合结果保留在列表里；剩余 pending 频道不丢——下方后台波段接管续搜
            // （旧版交给 fullSearchMore 随滚动续搜，不滚动就永远停着/漏搜）
            kotlinx.coroutines.withTimeoutOrNull(FULL_SEARCH_TOTAL_BUDGET_MS) {
                // 搜索范围 = 频道 + 群组（群组里也有资源；之前只搜频道，群组全漏）
                // listSearchableChats 网关侧有 5min TTL 缓存（SEARCH-PERF §3），连续搜索近零成本
                val channels = runCatching { real.listSearchableChats() }.getOrDefault(emptyList())
                // 口味画像：与混排/拉取配额同一份（含互动加分表 + P1 #4 会话系数 + P2 双轨融合）
                session.weights = p2ChannelWeights()
                val blocked = _blockedChannels.value
                val ordered = channels
                    .filter { it.id !in blocked } // 硬过滤：被屏蔽频道不参与扇出（§4.2）
                    .sortedByDescending { session.weights[it.id] ?: EXPLORE_WEIGHT }
                session.total = ordered.size
                session.pending = ordered.drop(FULL_SEARCH_FIRST_WAVE)
                _fullSearchCoverage.value = 0 to session.total
                fanoutSearch(real, session, ordered.take(FULL_SEARCH_FIRST_WAVE).map { it to null })
                // 自动续搜：第一波完了自动扇出剩余频道（每波间隔 400ms 防 flood wait，
                // delay 可取消——用户点"停止"/30 秒硬顶到点时整链中断）。修复"卡在 30/164"：
                // 之前剩余频道只在滚动到底时才惰性扇出，不滚动就永远停着
                while (session.pending.isNotEmpty()) {
                    // 内存安全闸（实测 500 字符长词扫 168 频道 OOM 崩溃；平板 512MB 堆
                    // 顶扫 540 会话也崩）：
                    //  1. 结果池超上限即停——海量结果无上限累积会把堆打爆
                    if (session.scored.size >= FULL_SEARCH_MAX_RESULTS) break
                    //  2. 堆水位闸：空闲堆 < 15% 就停搜（平板 512MB 顶，540 会话扇出
                    //     + 网格封面位图累积会把堆压爆——崩溃日志实锤 OOM）
                    val rt = Runtime.getRuntime()
                    val maxHeap = rt.maxMemory()
                    val freeHeap = maxHeap - (rt.totalMemory() - rt.freeMemory())
                    if (freeHeap < maxHeap / 7) break // 堆水位不足即停搜（平板 512MB 顶防 OOM）
                    kotlinx.coroutines.delay(400)
                    val wave = session.pending.take(FULL_SEARCH_FIRST_WAVE)
                    session.pending = session.pending.drop(FULL_SEARCH_FIRST_WAVE)
                    fanoutSearch(real, session, wave.map { it to null })
                }
            }
            // —— 后台波段（第九轮：修"全局搜不全"——541 频道 30s 只扫得了一百多，
            // 剩余频道旧版随滚动惰性续搜/不滚即永漏）：前台硬顶到点后剩余 pending
            // 转低强度续搜直至全扫完，结果经同一 scored 池流式并入上屏。
            // 取消语义：cancelFullSearch/新搜索取消本协程（delay 即挂起点），
            // searchSession 换帅或用户停止即收束；内存安全闸与前台同一套。
            if (searchSession === session && session.pending.isNotEmpty() && !_fullSearchStopped.value) {
                _fullSearchBackground.value = true
                android.util.Log.d(
                    "FeedViewModel",
                    "全量搜索转后台波段：query=\"$q\" 剩余=${session.pending.size}/${session.total} 频道",
                )
                try {
                    while (session.pending.isNotEmpty() && searchSession === session && !_fullSearchStopped.value) {
                        if (session.scored.size >= FULL_SEARCH_MAX_RESULTS) break // 结果池上限闸
                        val rt = Runtime.getRuntime()
                        val maxHeap = rt.maxMemory()
                        val freeHeap = maxHeap - (rt.totalMemory() - rt.freeMemory())
                        if (freeHeap < maxHeap / 7) break // 堆水位闸（与前台同口径）
                        kotlinx.coroutines.delay(BACKGROUND_WAVE_INTERVAL_MS)
                        val wave = session.pending.take(FULL_SEARCH_FIRST_WAVE)
                        session.pending = session.pending.drop(FULL_SEARCH_FIRST_WAVE)
                        fanoutSearch(real, session, wave.map { it to null }, BACKGROUND_FANOUT_CONCURRENCY)
                    }
                } finally {
                    _fullSearchBackground.value = false
                }
                android.util.Log.d(
                    "FeedViewModel",
                    "全量搜索后台波段结束：query=\"$q\" 覆盖=${session.done.get()}/${session.total} " +
                        "剩余=${session.pending.size} 结果=${session.scored.size}",
                )
            }
        } finally {
            if (searchSession === session) {
                // 强制终版发布：扇出期发布走节流（publishFullSearchThrottled），
                // 最后一跳可能被节流窗口压着，收尾必须放出（完成/超时/手动停止同此收口）
                publishFullSearch(session)
                _fullSearchLoading.value = false
                // 完成/被停止都留档（cancelFullSearch 的取消也走到这里）：
                // 同词 10 分钟内重搜直接复用结果，不重新扇出（SEARCH-PERF §1）
                cacheSearchSession(session)
            }
        }
    }
}

/**
 * 取消当前全量搜索（修复包 #2）：停掉扇出协程、loading 置 false、
 * 打"已停止"标记（进度胶囊随 loading=false 自动消失，已聚合结果保留可点；
 * stopped 标记的作用是挡住 fullSearchMore 复活扇出）。
 * 被取消协程的 finally 里 `searchSession === session` 仍成立，会重复写一次
 * loading=false，幂等无害；停止后 fullSearchMore 被 stopped 闸挡住，不会复活。
 */
internal fun FeedViewModel.cancelFullSearchImpl() {
    if (searchSession == null) return
    fullSearchJob?.cancel()
    fullSearchJob = null
    _fullSearchLoading.value = false
    _fullSearchStopped.value = true
}

/**
 * 翻页续搜（SEARCH-ARCH §4.3 loadMore 策略的 M1 口径）：
 *  1. 未到顶的已扇出频道（还有续页游标的）各翻一页；
 *  2. 再从 pending 队列取下一批惰性扇出（首页）。
 * 全部频道到底/熔断且 pending 清空后自然停摆（覆盖度已定格）。
 * 防重入：搜索进行中（含首页扇出中）重复触发直接忽略。
 */
internal fun FeedViewModel.fullSearchMoreImpl() {
    val session = searchSession ?: return
    if (_fullSearchLoading.value) return
    if (_fullSearchStopped.value) return // 用户已手动停止：滚动触底不再复活扇出（修复包 #2）
    val real = gateway as? TdLibGateway ?: return
    if (session.cursors.isEmpty() && session.pending.isEmpty()) return
    _fullSearchLoading.value = true
    fullSearchJob = viewModelScope.launch(Dispatchers.IO) {
        try {
            // 1) 未到顶的已扇出频道各续一页（游标快照，与并发写入解耦）；
            //    频道标题从现有频道表补（缺省占位，仅供日志/调试用）
            val more = session.cursors.toMap().map { (chatId, cursor) ->
                // "待重试"标记翻译成 null（重搜首页）；正常游标原样续页
                val realCursor = if (cursor == SEARCH_RETRY_CURSOR) null else cursor
                ChannelBrief(chatId, _channels.value.firstOrNull { it.id == chatId }?.title ?: "", null) to realCursor
            }
            fanoutSearch(real, session, more)
            // 2) 再从 pending 队列取下一批惰性扇出（首页）
            if (session.pending.isNotEmpty()) {
                val wave = session.pending.take(FULL_SEARCH_FIRST_WAVE)
                session.pending = session.pending.drop(FULL_SEARCH_FIRST_WAVE)
                fanoutSearch(real, session, wave.map { it to null })
            }
        } finally {
            if (searchSession === session) {
                publishFullSearch(session) // 续搜收尾强制终版发布（波内节流可能压着最后一跳）
                _fullSearchLoading.value = false
                cacheSearchSession(session) // 翻页增长的结果同步进缓存（SEARCH-PERF §1）
            }
        }
    }
}

/**
 * 扇出执行器：对 (频道, 游标) 列表按 [concurrency] 并发逐频道搜一页。
 * coroutineScope 结构化并发：外层协程取消时整波扇出一起取消；
 * Semaphore 用 acquire/release 成员函数（与 import 无关，追加段不触碰文件头）。
 * 前台波段用默认 [SEARCH_FANOUT_CONCURRENCY]；后台波段传 [BACKGROUND_FANOUT_CONCURRENCY] 降强度。
 */
internal suspend fun FeedViewModel.fanoutSearch(
    real: TdLibGateway,
    session: SearchSession,
    targets: List<Pair<ChannelBrief, String?>>,
    concurrency: Int = SEARCH_FANOUT_CONCURRENCY,
) {
    if (targets.isEmpty()) return
    val sem = kotlinx.coroutines.sync.Semaphore(concurrency)
    kotlinx.coroutines.coroutineScope {
        val jobs = targets.map { (ch, cursor) ->
            launch(Dispatchers.IO) {
                sem.acquire()
                try {
                    searchOneChannel(real, session, ch, cursor)
                } finally {
                    sem.release()
                }
            }
        }
        jobs.forEach { it.join() }
        // 每波扇出收尾强制发布一次：波内单频道发布走节流（合并完成洪峰的重排），
        // 波尾必须兜底放出，防尾部结果被节流窗口压住不上屏
        publishFullSearch(session)
    }
}

/**
 * 单频道搜一页：失败累连续失败计数，满 [FULL_SEARCH_MAX_FAILURES] 熔断
 * （丢游标、推进覆盖度——熔断的频道也如实计入"已搜"，页脚不停在假进度）；
 * 成功清零失败计数、记录/清除续页游标、结果按 key 归并去重后重排上屏。
 * 覆盖度语义：首页完成（无论命中几条，含 0 命中）才推进一次；翻页不重复计数。
 */
internal suspend fun FeedViewModel.searchOneChannel(
    real: TdLibGateway,
    session: SearchSession,
    ch: ChannelBrief,
    cursor: String?,
) {
    val page = try {
        real.searchChannelAll(ch.id, session.query, cursor, FULL_SEARCH_PAGE_LIMIT)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e // 协程取消不吞（与 loadMore 同款纪律）
    } catch (e: Exception) {
        null
    }
    if (page == null) {
        val fails = (session.failures[ch.id] ?: 0) + 1
        session.failures[ch.id] = fails
        if (cursor == null) {
            // 首页失败：如实计入覆盖度（页脚不停在假进度"搜索中…"），
            // 且标记待重试（fullSearchMore 会重搜首页）——失败频道不再永久失踪；
            // 连续失败满熔断阈值才彻底跳过
            advanceSearchCoverage(session)
            if (fails < FULL_SEARCH_MAX_FAILURES) {
                session.cursors[ch.id] = SEARCH_RETRY_CURSOR // 待重试标记
            } else {
                session.cursors.remove(ch.id)
            }
        }
        return
    }
    session.failures.remove(ch.id) // 成功即清零连续失败计数
    if (page.nextCursor != null) session.cursors[ch.id] = page.nextCursor
    else session.cursors.remove(ch.id) // 到底（nextFromMessageId=0）
    val nowSec = System.currentTimeMillis() / 1000L
    val disliked = _dislikedKeys.value
    for (item in page.items) {
        if (item.key in disliked) continue // 硬过滤：不感兴趣单条不进搜索结果（§4.2）
        // 服务端结果本地复滤（实锤：TG 服务端对中文搜索乱匹配，返回一堆
        // caption 不含关键词的条目——inCaption=false 一片）。精确模式下
        // caption 不含查询词的直接丢；模糊模式靠打分权重不收硬性过滤
        if (!session.fuzzy) {
            val phrase = session.aq?.phrase ?: session.query.trim().trimStart('#', '＃')
            val hitCap = SearchTextKit.exactHits(
                SearchTextKit.fold(item.caption), phrase,
            ) || SearchTextKit.exactHits(
                SearchTextKit.fold(item.channelTitle), phrase,
            )
            if (!hitCap) continue
        }
        // 内存安全闸（实测长词全频道扫 OOM 崩溃）：结果池超上限后只收高分新条目
        if (session.scored.size >= FULL_SEARCH_MAX_RESULTS && !session.scored.containsKey(item.key)) {
            // 满了：只收比当前最低分高的，收新同时淘汰最低分——真换入换出、池容恒定
            //（R110 前只进不出：声称换入换出但从不淘汰，靠波次闸兜底超限有限）
            val minEntry = session.scored.minByOrNull { it.value.second }
            val newScore = searchScore(item, session, nowSec)
            if (minEntry == null || newScore <= minEntry.value.second) continue
            session.scored.remove(minEntry.key, minEntry.value)
        }
        val score = searchScore(item, session, nowSec)
        // 同 key 去重：保留高分（双模式两路/跨频道同片命中场景；fileUniqueId 去重留给 M3 精排）
        session.scored.merge(item.key, item to score) { old, new ->
            if (new.second > old.second) new else old
        }
    }
    // 流式并入走节流：频道完成洪峰时合并重排（防 30~540 次全量排序刷爆 UI 重组），
    // 波尾/收尾由 fanoutSearch/fullSearch 的 finally 强制发布兜底，尾部结果不丢
    publishFullSearchThrottled(session)
    if (cursor == null) advanceSearchCoverage(session)
    // 评论级联搜索（修复包 #5）：主帖首页搜完后顺带搜一次该频道评论里的媒体
    // （限量 FULL_SEARCH_COMMENT_LIMIT 条；网关内部有线程数预算闸防 flood）。
    // 增量收益：任何失败静默——不累计失败计数、不触发熔断、不影响覆盖度推进。
    //
    // SEARCH-PERF §2 收敛：评论级联每频道固定 4 次请求（主帖扫描 + 最近帖 + 2 线程），
    // 30 频道首波就是 ~120 次纯增量请求；改为只随前 [SEARCH_COMMENT_FIRST_CHANNELS]
    // 个完成首页的频道顺带（扇出按口味权重降序，先完成的大多是高权重频道，
    // 评论资源的期望质量也最高）；其余频道的评论区交给 M2 L1b 整组搜索（真解）。
    if (cursor == null && session.done.get() <= SEARCH_COMMENT_FIRST_CHANNELS) {
        try {
            val commentItems = real.searchChannelComments(ch.id, session.query, FULL_SEARCH_COMMENT_LIMIT)
            if (commentItems.isNotEmpty()) {
                for (item in commentItems) {
                    if (item.key in disliked) continue // 与主帖同一可见性口径
                    val score = searchScore(item, session, nowSec)
                    // R110：评论级联并入同池也过换入换出闸——旧路径无上限直 merge，
                    // 池满时评论结果绕过内存安全闸
                    if (session.scored.size >= FULL_SEARCH_MAX_RESULTS &&
                        !session.scored.containsKey(item.key)
                    ) {
                        val minEntry = session.scored.minByOrNull { it.value.second }
                        if (minEntry == null || score <= minEntry.value.second) continue
                        session.scored.remove(minEntry.key, minEntry.value)
                    }
                    // 同 key 归并去重、保留高分（与主帖结果同一规则）
                    session.scored.merge(item.key, item to score) { old, new ->
                        if (new.second > old.second) new else old
                    }
                }
                publishFullSearchThrottled(session) // 与主帖结果同一节流口径
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 协程取消不吞（与主帖搜索同款纪律，cancelFullSearch 靠它停扇出）
        } catch (e: Exception) {
            // 静默：评论搜索失败绝不影响主帖结果
        }
    }
}

/** 覆盖度推进一格并发布（全部完成/熔断后定格——没有更多频道会再推进） */
internal fun FeedViewModel.advanceSearchCoverage(session: SearchSession) {
    val done = session.done.incrementAndGet()
    if (searchSession === session) {
        _fullSearchCoverage.value = done to session.total
    }
}

/** 结果池重排上屏（边到边聚合：每个频道完成就重排一次，不等全波收工） */
internal fun FeedViewModel.publishFullSearch(session: SearchSession) {
    if (searchSession !== session) return // 旧会话结果不灌新搜索
    // 搜索媒体类型过滤：-1 全部 / 0 视频 / 1 图文 / 2 音频（搜索页 tab）
    val mediaTab = _searchMediaTab.value
    // 内容筛选叠加（修复包 #1）：方向/时长/发布时间与类型 tab 同处取交集
    val sf = _searchFilter.value
    _fullSearchResults.value = session.scored.values
        .filter { (mediaTab == -1 || it.first.mediaType == mediaTab) && sf.matches(it.first) }
        .sortedByDescending { it.second }
        .map { it.first }
    // 搜索命中反哺发现图谱：命中的频道记 search_hit 边（发现页候选多一份证据）。
    // SEARCH-PERF §5：增量记录——旧实现每次 publish（每频道完成一次，30~166 次/轮）
    // 都对全量结果（最多 500 条）重复 recordEdge，最坏 8 万次图谱写；
    // graphedKeys 原子判重后每条目每会话只反哺一次
    (gateway as? TdLibGateway)?.let { gw ->
        session.scored.values.forEach { (item, _) ->
            if (session.graphedKeys.add(item.key)) {
                runCatching { gw.discoveryGraph.recordEdge(0L, item.chatId, "search_hit") }
            }
        }
    }
}

/**
 * 结果池发布上屏的节流版（频道腿流式并入专用）：距上次发布不足 [SEARCH_PUBLISH_THROTTLE_MS]
 * 则跳过（合并到下一跳），防 540 频道完成洪峰引发"每频道一次全量重排 + 网格重组"。
 * 尾部结果兜底：每波扇出收尾（fanoutSearch）与会话/续搜收尾（fullSearch/fullSearchMore
 * 的 finally）都强制调 [publishFullSearch]，被节流压住的最后一跳必定补放。
 * 已知竞态：多频道协程并发读改写 lastPublishMs，最坏多发/少发一跳——少发由波尾
 * 强制发布兜回，可接受。媒体 tab/筛选切换的即时重排不走这里，直调 [publishFullSearch]。
 */
internal fun FeedViewModel.publishFullSearchThrottled(session: SearchSession) {
    val now = System.currentTimeMillis()
    if (now - session.lastPublishMs < SEARCH_PUBLISH_THROTTLE_MS) return
    session.lastPublishMs = now
    publishFullSearch(session)
}

/**
 * 搜索结果排序分（SEARCH-QUALITY §1/§5 重写版）：
 * `score = w × (0.45 + 0.55×exp(-age/120)) × (0.5 + rel) × sig`
 *
 * 与旧版（w × exp × rel，rel 只看完整短语 contains）的差别：
 *  1. 新鲜度保底混合：旧纯乘法 exp(-age/120) 下两年陈 ×0.0023，老资源（资源搜索的
 *     主要目标）被灭绝性压制；改 0.45 保底后，强相关老帖能凭 rel 浮回前排，
 *     新帖仍保留 55% 的新鲜度优势；
 *  2. rel 词位化（见 [searchRelevance]）：CJK bigram 覆盖/拉丁整词/同义扩展/
 *     简繁折叠/ED≤1 模糊降权；频道名命中从 +1.5 降为 0.6 弱先验（搜内容不是搜频道）；
 *  3. sig 条目级人工信号：收藏 +0.35 / 赞 +0.25 / 看过 +0.15 / 下载 +0.30，
 *     加法合并后 clamp ≤ 2.0——同分附近"我熟的内容靠前"，量级刻意小于 rel 跨度，
 *     信号做同分裁决、不喧宾夺主；
 *  4. rel=0（服务端命中但本地全不命中，多为 TG 对 CJK 分词太松的宽泛召回）给
 *     (0.5+rel)=0.5 底分保留"服务端背书"，不直接归零杀掉。
 */
internal fun FeedViewModel.searchScore(item: VideoItem, session: SearchSession, nowSec: Long): Double {
    val w = session.weights[item.chatId] ?: EXPLORE_WEIGHT
    val ageDays = if (item.dateEpochSec > 0) {
        (nowSec - item.dateEpochSec).coerceAtLeast(0) / 86400.0
    } else {
        365.0 // 无时间戳按一年陈处置（不滤掉但自然沉底，沿用旧口径）
    }
    val fresh = 0.45 + 0.55 * kotlin.math.exp(-ageDays / 120.0) // 半衰期 ~83 天，0.45 保底
    val rel = searchRelevance(item, session.aq, session.fuzzy)
    val sg = session.signals
    var sig = 1.0
    if (item.key in sg.favorited) sig += 0.35
    if (item.key in sg.liked) sig += 0.25
    if (item.key in sg.watched) sig += 0.15
    if (item.key in sg.downloaded) sig += 0.30
    return w * fresh * (0.5 + rel) * sig.coerceAtMost(2.0)
}

/**
 * fold 文本缓存（SEARCH-PERF §5）：caption/频道名/音频元字段的简繁折叠结果按 key 缓存。
 * 热点场景：
 *  - publishFullSearch 每频道完成即全量重排（30~166 次/轮 × 最多 500 条），
 *    每条 searchRelevance 原来要 fold caption + fold channelTitle（+音频 fold）；
 *  - localSearchInstant 对收藏/稍后/历史/会话条目每词重扫时每条都 fold。
 * fold 是纯函数且条目不可变，缓存安全。容量超 [FOLD_CACHE_MAX] 整表清空重建
 * （粗粒度淘汰：重建成本是一次 fold，省掉 LRU 簿记）。
 * 已知近似：同 key 条目 copy 改 caption 的场景（追更置顶 🔔 前缀）会沿用旧折叠——
 * 前缀在头部，contains 查询词不受影响，可接受。
 */

internal fun FeedViewModel.foldCached(cacheKey: String, raw: String): String {
    if (raw.isEmpty()) return ""
    synchronized(foldCacheLock) {
        foldCache[cacheKey]?.let { return it }
        val folded = SearchTextKit.fold(raw)
        if (foldCache.size >= FOLD_CACHE_MAX) foldCache.clear()
        foldCache[cacheKey] = folded
        return folded
    }
}

/**
 * 文本相关性分（SEARCH-QUALITY §1 rel 词位化；localSearchInstant 与 searchScore 共用
 * 同一把尺子——本地/服务端两路的 rel 可比较，混排才有意义）。
 *
 * [fuzzy] = 模糊搜索开关（AppPrefs.fuzzySearch，会话级快照）：
 *
 * **模糊模式（开关开）**：
 *  - caption 完整短语命中（fold 后整串 contains）        +2.0
 *  - 词位覆盖（bigram/拉丁/同义；ED≤1 模糊按 0.6 降权计入）：
 *      全中 +1.6；部分命中 +1.2×覆盖率
 *  - 音频结构化元字段（audioTitle/audioPerformer，mediaType=2）
 *    完整短语命中                                          +1.0（§3-4：搜歌名/歌手）
 *  - 频道名完整短语命中                                    +0.6（弱先验，旧值 +1.5 会淹没 caption 命中）
 *
 * **精确模式（默认，开关关）**：fold 归一后按空格切段、各段 AND 连续子串
 * （[SearchTextKit.exactHits]）——"三体 小说" = 同时含三体和小说，只含"三体"的
 * "三体电视剧"不算中；无空格查询 = 连续字符串精确匹配。
 *  - caption AND 全中                                      +1.0
 *  - caption 整串连续出现（AND 之上更强）                  再 +2.0
 *  - 音频元字段 AND 全中                                   +1.0
 *  - 频道名 AND 全中                                       +0.6（弱先验）
 * 简繁/全角/大小写归一（fold）两种模式常开，不算模糊。
 * 全不命中返回 0.0（调用方自行决定底分/收录阈值）。
 */
internal fun FeedViewModel.searchRelevance(item: VideoItem, aq: SearchTextKit.AQuery?, fuzzy: Boolean): Double {
    if (aq == null || aq.phrase.isEmpty()) return 0.0
    // SEARCH-PERF §5：折叠结果缓存（频道名按频道粒度缓存，比条目粒度更省）
    return textMatchScore(
        capFold = foldCached(item.key, item.caption),
        audioFold = if (item.mediaType == 2) {
            foldCached(item.key + "|a", item.audioTitle + " " + item.audioPerformer)
        } else {
            ""
        },
        channelFold = foldCached("ch:" + item.chatId, item.channelTitle),
        aq = aq,
        fuzzy = fuzzy,
    )
}

/**
 * 文本匹配算分核（R304 从 [searchRelevance] 抽取的纯函数单一出处，JVM 单测直测）：
 * caption 臂 / 音频元字段臂 / 频道名臂的全部打分规则只在此处——生产尺
 * [searchRelevance]（fold 缓存薄壳）与 OCR 收录救援尺 [ocrRescueScore] 共用同一实现，
 * 打分规则不许两处各写一份（类问题纪律§一）。
 * 入参均为已 fold 文本；audioFold 传空串 = 无音频臂（mediaType≠2）。
 */
internal fun textMatchScore(
    capFold: String,
    audioFold: String,
    channelFold: String,
    aq: SearchTextKit.AQuery,
    fuzzy: Boolean,
): Double {
    if (aq.phrase.isEmpty()) return 0.0
    var s = 0.0
    if (fuzzy) {
        // —— 模糊模式：bigram 词位覆盖 / ED≤1 容错 / 同义词组扩展 ——
        if (capFold.contains(aq.phrase)) s += 2.0 // 完整短语命中正文：最强信号
        if (aq.slots.isNotEmpty()) {
            val tokens = SearchTextKit.latinTokens(capFold) // 提取一次，多词位复用
            var hitSum = 0.0
            for (slot in aq.slots) hitSum += SearchTextKit.slotHit(capFold, tokens, slot)
            val ratio = hitSum / aq.slots.size
            s += if (ratio >= 0.999) 1.6 else 1.2 * ratio // 词位全中 / 部分覆盖
        }
        if (audioFold.isNotBlank() && audioFold.contains(aq.phrase)) s += 1.0
        if (channelFold.contains(aq.phrase)) s += 0.6
    } else {
        // —— 精确模式：各段 AND 连续子串（缺段即 0 分不收录，由调用方阈值过滤）——
        if (SearchTextKit.exactHits(capFold, aq.phrase)) {
            s += 1.0 // AND 全中基准分
            if (capFold.contains(aq.phrase)) s += 2.0 // 整串连续出现：精确模式下的最强信号
        }
        if (audioFold.isNotBlank() && SearchTextKit.exactHits(audioFold, aq.phrase)) s += 1.0
        // 频道名命中仍作弱先验收录（兼容旧 contains 谓词 caption||title 的覆盖语义）
        if (SearchTextKit.exactHits(channelFold, aq.phrase)) s += 0.6
    }
    return s
}

/**
 * R304 OCR 收录救援资格（纯函数唯一出处）：原尺全灭（rel≤0）∧ 低信息 caption。
 * 低信息判定与 apply Job 的并词位判定同构（[OcrPolicy.isLowInfoCaption] 单一出处）——
 * applied=1 只代表「已结算」（高信息行=「判定不并」也置 1），唯有「低信息 caption」
 * 的 OCR 词才真正并入了索引词位；高信息条目走救援会把从未进索引的 OCR 词加分，
 * 直接违反 r291/r292 防稀释红线。
 */
internal fun needsOcrRescue(rel: Double, caption: String): Boolean =
    rel <= 0.0 && OcrPolicy.isLowInfoCaption(caption)

/**
 * R304 OCR 收录救援尺（纯函数；修 V9-③ 实锤的「词位进索引≠召回生效」第二现场）：
 * 条目原尺全灭时，若其 OCR 文本已并入索引词位（item_ocr.applied=1 ∧ 低信息 caption，
 * 取料方举证 [ocrText]），把 OCR 文本并入 caption 臂过同一把 [textMatchScore] 核——
 * 索引词位配方既含 OCR（R292 indexTokensOf 三参），收录尺必须同源，否则 OCR-only
 * 命中全死在 rel≤0 收录闸。返回的是正当分值（精确 AND 基准 1.0/整串 3.0/模糊覆盖
 * 同档），不是开后门豁免；返回 0 = OCR 文本也不含查询词位（救援失败，维持丢弃）。
 * 折叠口径：capFold+" "+fold(ocr) 与索引侧 exactRecheckWithOcr 的 fold(拼接) 对
 * contains 语义等价；audio/channel 臂原样重过（rel=0 已证其贡献为 0，重算恒 0）。
 */
internal fun ocrRescueScore(
    capFold: String,
    ocrText: String,
    audioFold: String,
    channelFold: String,
    aq: SearchTextKit.AQuery,
    fuzzy: Boolean,
): Double {
    if (ocrText.isBlank() || aq.phrase.isEmpty()) return 0.0
    return textMatchScore(capFold + " " + SearchTextKit.fold(ocrText), audioFold, channelFold, aq, fuzzy)
}

/** 搜索缓存键：query + 不可见分隔符 + 匹配口径位（模糊开关）。
 *  分隔符用 （用户查询不可能输入该字符）；口径位保证切开关后同词不命中旧口径缓存 */
internal fun searchCacheKey(q: String, fuzzy: Boolean): String = q + "" + (if (fuzzy) "fz" else "ex")

// ---------------- M1 全量搜索常量（FeedViewModel 全量搜索段配套，文件级私有） ----------------

/**
 * R371 排序·频道热度档数据源（纯本地零网络）：chatId → 成员数快照。
 * 优先级：发现档案内存表（discoveryMemberCounts，懒加载产物）→ 发现图谱节点
 * （DiscoveryGraph.memberCountOf，落盘懒加载，loadLocked 首载有磁盘 IO）——
 * **必须在 IO 线程调用**（本函数自带 withContext(IO)；图谱锁与发现流共用，本函数
 * 只持图谱锁不持其他锁，无死锁面）。未知（≤0）不入表：调用方按未知沉底（诚实降级）。
 */
internal suspend fun FeedViewModel.channelMemberCountsForSortImpl(chatIds: Set<Long>): Map<Long, Int> =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val real = gateway as? TdLibGateway
        val out = HashMap<Long, Int>(chatIds.size)
        for (id in chatIds) {
            val mem = discoveryMemberCounts[id]?.takeIf { it > 0 }
                ?: real?.discoveryGraph?.memberCountOf(id)?.takeIf { it > 0 }
            if (mem != null) out[id] = mem
        }
        out
    }

/** 首波扇出频道数：按口味权重降序取前 N；剩余频道随 fullSearchMore 惰性扇出（SEARCH-ARCH §2.1） */
private const val FULL_SEARCH_FIRST_WAVE = 30

/** 全量搜索频道腿前台波段时长（毫秒）：30 秒到点后剩余 pending 频道转后台波段续搜
 *  （第九轮起不再丢弃）；本地/索引结果秒级上屏，频道腿前快后慢补全覆盖 */
private const val FULL_SEARCH_TOTAL_BUDGET_MS = 30_000L

/** 后台波段并发数（低于前台 [SEARCH_FANOUT_CONCURRENCY]：不挡用户正常刷 Feed 的网络） */
private const val BACKGROUND_FANOUT_CONCURRENCY = 3

/** 后台波段波间隔（毫秒）：低强度续搜节奏（前台 400ms 的两倍） */
private const val BACKGROUND_WAVE_INTERVAL_MS = 800L

/** 频道腿结果发布节流间隔（毫秒）：合并频道完成洪峰的全量重排，防 UI 重组风暴；波尾/收尾强制发布兜底 */
private const val SEARCH_PUBLISH_THROTTLE_MS = 120L

/** 扇出并发上限（与 TdLibGateway.CONCURRENCY 惯例一致） */
private const val SEARCH_FANOUT_CONCURRENCY = 5

/** 每频道每页条数（首页轻量召回；翻页只对还有游标的频道继续，§5 预算表口径） */
private const val FULL_SEARCH_PAGE_LIMIT = 20

/** 单频道连续失败熔断阈值：达到即跳过该频道（绝不影响其他频道；覆盖度如实计入已搜） */
private const val FULL_SEARCH_MAX_FAILURES = 2

/** 全量搜索结果池上限（实测长词扫全频道 OOM 崩溃）：超上限只收高分新条目/停止续搜。
 * 小内存设备（堆 < 768MB）砍半到 250——平板 512MB 堆顶扫 540 会话实测 OOM 崩溃 */
private val FULL_SEARCH_MAX_RESULTS: Int
get() = if (Runtime.getRuntime().maxMemory() < 768L * 1024 * 1024) 250 else 500

/** 游标表里的"待重试"哨兵：首页失败的频道先计入覆盖度，翻页时按它重搜首页 */
private const val SEARCH_RETRY_CURSOR = "__retry__"

/** 全量搜索：每频道首页顺带评论级联搜索的条数上限（修复包 #5；网关侧另有线程数预算闸） */
private const val FULL_SEARCH_COMMENT_LIMIT = 10

/** 本地即时层深度索引召回条数预算（SEARCH-INVERTED → SQLite FTS5 版）：索引命中数达此数即短路，
 *  防"超高频词 × 超大索引"的全量打分成本；低分尾部召回价值低，放弃不亏 */
internal const val LOCAL_INDEX_RECALL_BUDGET = 200

// ---------------- 搜索效率常量（SEARCH-PERF，FeedViewModel 搜索段配套，文件级私有） ----------------

/** 搜索会话结果缓存 TTL（毫秒）：同词 10 分钟内直接复用上次聚合结果上屏，不重新扇出 */
private const val SEARCH_SESSION_CACHE_TTL_MS = 10L * 60_000L

/** 搜索会话缓存容量（LRU，超出淘汰最旧词条） */
private const val SEARCH_SESSION_CACHE_MAX = 10

/** fold 文本缓存容量：超出即整表清空重建（粗粒度淘汰，重建成本是一次 fold，省 LRU 簿记） */
private const val FOLD_CACHE_MAX = 6000

/** 评论级联搜索只随前 N 个完成首页的频道顺带（SEARCH-PERF §2：扇出按口味权重降序，
 *  先完成的大多是高权重频道；30 频道首波评论请求量从 ~120 收敛到 ~32） */
private const val SEARCH_COMMENT_FIRST_CHANNELS = 8

/** 搜索联动·一词一轮冷却（毫秒）：同词 fold 后 10 分钟内不重复向搜索实体发词
 *  （与 searchViaBot 结果缓存 TTL 同档；冷却表进程内存态，重启清零） */
private const val LIVE_ASK_COOLDOWN_MS = 10L * 60_000L
