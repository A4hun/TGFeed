package dev.tgfeed.core.td

import android.util.Log
import dev.tgfeed.core.feed.AdBlacklist
import dev.tgfeed.core.feed.AdChannelClassifier
import dev.tgfeed.core.feed.AnswerStore
import dev.tgfeed.core.feed.DiscoveryGraph
import dev.tgfeed.core.feed.GoldmineHooks
import dev.tgfeed.core.feed.GroupAnswer
import dev.tgfeed.core.feed.HintTitle
import dev.tgfeed.core.feed.GroupHarvester
import dev.tgfeed.core.feed.SearchEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.drinkless.tdlib.TdApi

/**
 * 收割链接建边（P1 #3，group_search 边类型）：sniffLink 的公开门面——
 * t.me/c/ 直链直接建边；t.me/<name> 进 resolveQueue 懒解析（resolvePendingChannels 既有链）；
 * 邀请链接按 sniffLink 既有口径丢弃。所有异常静默（收割是增量收益，绝不影响主链）。
 */
internal fun TdLibGateway.recordGroupSearchLinkImpl(fromChatId: Long, link: String) {
    runCatching { sniffLink(fromChatId, link, GroupHarvester.EDGE_GROUP_SEARCH) }
}

/**
 * 答案库查询（P1 #3 消费口，供未来"本地未命中→答案库直出"搜索路径）：
 * fold 归一精确命中，结果按命中次数/时间降序；收割器未启动过也能查磁盘存量。
 */
internal fun TdLibGateway.queryGroupAnswersImpl(keyword: String): List<GroupAnswer> =
    groupHarvesterLazy.value.answerStore.query(keyword)

/**
 * 金矿 P3·"在资源群里搜"（只读）：答案库命中词 → 链接批量解析成可播条目。
 * 与 searchViaBot 的差异：数据来源是**本地答案库**（群友历史搜索的沉淀），
 * **不向群里发任何消息**；网络开销只有 GetMessageLinkInfo 解析（[BOT_LINK_CONCURRENCY] 路并发）。
 * flood 查询车道整批记 1 次（与 queryBotOnce 同口径）；预算耗尽/无命中/失败均静默返回空表。
 * 相册共享 caption（形态A 结果集内版）与 queryBotOnce 同一口径。
 */
internal suspend fun TdLibGateway.searchViaGroupImpl(query: String): List<VideoItem> = runCatching {
    val answers = queryGroupAnswers(query)
    if (answers.isEmpty()) return@runCatching emptyList()
    floodBudgetQuery.maxOpsPerHour = appPrefs.floodQueryPerHour
    if (!floodBudgetQuery.tryAcquire()) {
        Log.w(TAG, "searchViaGroup: 超出每小时查询预算，本轮降级为空 q=$query")
        return@runCatching emptyList()
    }
    awaitAuthorized()
    val links = answers.map { it.link }.distinct().take(GROUP_ANSWER_RESOLVE_MAX)
    val sem = Semaphore(BOT_LINK_CONCURRENCY)
    val resolvedMsgs = coroutineScope {
        links.map { link ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    // R366：超时兜底——隐性限流挂起 12s 到点按单条失败跳过（null 回落
                    // 进既有 getOrNull 跳过路，零行为回破：正常网络与原逻辑逐位一致）
                    runCatching {
                        withTdTimeout(what = "searchViaGroup/GetMessageLinkInfo") {
                            client.send(TdApi.GetMessageLinkInfo(link))
                        }?.message
                    }
                        .onFailure { Log.w(TAG, "searchViaGroup: 链接解析失败 $link: ${it.message}") }
                        .getOrNull()
                }
            }
        }.awaitAll().filterNotNull()
    }
    // 相册共享 caption（形态A 结果集内版）：同相册带 caption 的成员把说明共享给空成员
    //（mediaAlbumId 只在单频道内有归组意义，先按 chatId 分组）——与 queryBotOnce 同口径
    val capsByChat = resolvedMsgs.filter { it.mediaAlbumId != 0L }
        .groupBy { it.chatId }
        .mapValues { (_, ms) -> albumSharedCaptions(ms) }
    resolvedMsgs.mapNotNull { m ->
        runCatching {
            toVideoItem(m.chatId, m, null, titleCtx = TitleChainContext(albumCaps = capsByChat[m.chatId] ?: emptyMap()))
        }.getOrNull()
    }.distinctBy { it.key }
}.onFailure {
    Log.w(TAG, "searchViaGroup 失败 q=$query: ${it.message}")
}.getOrDefault(emptyList())

/**
 * 金矿 P3·答案行单击直达：消息级链接解析成单条可播 VideoItem（GetMessageLinkInfo.message）。
 * 频道主页类链接（message==null）返回 null，由 UI 回落 resolvePublicChannel/resolveJoinedChannel
 * 的频道源路径。用户单击导航不计 flood 预算（与 caption 链接点击同一口径）。
 */
internal suspend fun TdLibGateway.resolveLinkVideoItemImpl(link: String): VideoItem? {
    // R366：超时兜底——隐性限流挂起 12s 到点按解析失败回落（null 与异常同口径，
    // R64 信誉记账同记失败，不谎报不永挂）
    val info = runCatching {
        awaitAuthorized()
        withTdTimeout(what = "resolveLinkVideoItem/GetMessageLinkInfo") {
            client.send(TdApi.GetMessageLinkInfo(link))
        }
    }.getOrNull()
    if (info == null) {
        // R64 信誉记账：死链/已删/超时 → 来源实体解析失败一记（非秒回链登记的链接内部静默忽略；
        // isInitialized 守卫：收割器未启动时必无归因映射，不为记账白构造）
        if (groupHarvesterLazy.isInitialized()) {
            runCatching { groupHarvesterLazy.value.reputation.noteResolveOutcome(link, false) }
        }
        return null
    }
    // R64 信誉记账：GetMessageLinkInfo 成功即链接有效——message==null 是频道主页类链接的
    // 正常语义（由 UI 回落 resolvePublicChannel/resolveJoinedChannel 频道源路径），不算解析失败
    if (groupHarvesterLazy.isInitialized()) {
        runCatching { groupHarvesterLazy.value.reputation.noteResolveOutcome(link, true) }
    }
    val msg = info.message ?: return null
    return runCatching { toVideoItem(msg.chatId, msg, null) }.getOrNull()
}

// ---------------- 搜索联动·秒回监听（搜索页"资源群秒回"：显式提交时主动问搜索实体） ----------------

/**
 * 搜索联动·本轮可发词的搜索实体清单（FeedViewModel.liveAskSearch 的实体来源）：
 *  1. 收割器已知清单（[GroupHarvester.knownSearchEntities]：进程内/持久缓存只读，零网络）；
 *  2. searchViaBot 既有配置 bot（searchBotUsername + searchBotFallbacks）兜底并入——
 *     收割还没跑过/清单未持久化时也能秒问（resolveBotChatId 进程内缓存，
 *     首次每个名字最多 1 次 SearchPublicChat，之后零网络）；
 *  3. AdChannelClassifier 运行时黑名单剔除（实体被拉黑即不发，对应"实体黑名单不发"纪律）；
 *  4. R64：排序从"KIND_BOT 静态优先"升级为"信誉分降序"（[EntityReputation]：
 *     应答率/解析率加权 + 新实体探索保底分防饿死；同分仍 bot 优先，旧序语义作平票兜底），
 *     总量封顶 [LIVE_ASK_MAX_ENTITIES]（防一次提交广播全网）。
 */
internal suspend fun TdLibGateway.liveAskEntitiesImpl(): List<SearchEntity> {
    val harvester = groupHarvester
    val byId = LinkedHashMap<Long, SearchEntity>()
    for (e in harvester.knownSearchEntities()) byId[e.chatId] = e
    val configured = (listOf(appPrefs.searchBotUsername) + appPrefs.searchBotFallbacks.split(','))
        .map { it.trim().removePrefix("@") }
        .filter { it.isNotEmpty() }
        .distinct()
    for (name in configured) {
        // 同 bot 已被发现清单覆盖（同 chatId 或同 @名）则跳过，不再重复解析
        if (byId.values.any {
                it.kind == SearchEntity.KIND_BOT && it.title.equals("@$name", ignoreCase = true)
            }
        ) continue
        val chatId = resolveBotChatId(name, SearchQuotaLedger.Lane.USER_ACTION) ?: continue
        byId.putIfAbsent(chatId, SearchEntity(chatId, SearchEntity.KIND_BOT, "@$name", "botconfig"))
    }
    return byId.values
        .filterNot { AdChannelClassifier.isBlockedRuntime(it.chatId, null) } // 黑名单仍最先滤，口径不变
        // R64 信誉分降序（分数口径见 EntityReputation 类注释）；平票回落 bot 优先
        .sortedWith(
            compareByDescending<SearchEntity> { harvester.reputation.score(it.chatId) }
                .thenByDescending { it.kind },
        )
        .also { list ->
            // 结构性日志：只带 chatId/kind/分数指标，不带实体标题（日志纪律）
            Log.i(
                TAG,
                "liveAskEntities: 可发词实体 ${list.size} 个（信誉降序：" +
                    list.joinToString { e ->
                        "${harvester.reputation.scoreLine(e.chatId)} kind=${e.kind}"
                    } + "）",
            )
        }
        .take(LIVE_ASK_MAX_ENTITIES)
}

/**
 * 搜索联动·向单个搜索实体发关键词并监听秒回（"资源群秒回"的数据腿）。
 * 轻量版 queryBotOnce 骨架：不翻页、不点按钮、不把链接解析成 VideoItem——
 * 只把监听窗口内的应答消息实时解析上交（t.me 链接 → GroupAnswer，纯文本 → 答案卡字符串）；
 * 链接的可播化延迟到用户点击时经 resolveLinkVideoItem/resolvePublicChannel 链完成
 * （与"群友搜过"历史答案同一深链直达口径，省下一整轮 GetMessageLinkInfo 批量解析）。
 *
 * 应答口径（按实体类型分）：
 *  - KIND_BOT 私聊：窗口期内所有非本人新消息都算应答（私聊里只有 bot 会说话）；
 *  - KIND_GROUP 群：只认 replyTo 精确指向本轮所发关键词消息的应答——
 *    群里人声嘈杂，宽口径会把闲聊当答案。
 *
 * 监听窗口：首条应答前每 [LIVE_ASK_POLL_MS] 轮询一次；应答集合（消息 id+editDate 签名）
 * 稳定 [LIVE_ASK_QUIET_MS] 提前收工；硬顶 [LIVE_ASK_WINDOW_MS]，超时/无回复静默收场。
 *
 * 纪律：
 *  - flood：发送+监听整轮记 1 次**秒回专用车道**预算（[liveAskBudget]，与收割车道
 *    彻底解耦——后台收割吃光预算不饿死用户主动秒问）；预算耗尽静默跳过；
 *  - 回灌：过闸（广告闸同收割口径）新链接即时写 AnswerStore（recordAnswer 自带同链接
 *    去重累 hits）+ 图谱 group_search 边 + EntityResolver 懒解析入队；
 *    轮末 GoldmineHooks.onHarvested 记一次热词——收割管道的热词/新发现因此更准；
 *  - 静默：发送失败（群无发言权限等）/无回复/超时都只返回 false/已收部分，绝不向上抛
 *    （协程取消不是失败，照常上抛保证取消语义）；
 *  - **R366 写闸（§十 缺口收口）**：发词 SendMessage 必过 [TdLibGateway.accountWriteBlocked]
 *    ——测试闸开=入口即拦，如实日志零发送零永挂。R364 事故判词：真机腿未开闸时
 *    liveAsk「提交即发词」链路误发 3 条关键词消息（liveAsk 无 pref 开关、原不过写闸），
 *    此后本路径是 §十 挂账清偿项，闸为发词权威拦截点（VM 侧另有「写保护中」如实提示）。
 *  - R366 超时兜底：发词/轮询拉取均裹 withTdTimeout——隐性限流（请求挂起无响应）
 *    下裸 send 永挂是「已问 N 实体，等待应答…」长转真身；发送超时=如实报失败
 *    （sentOk=false 不记一词一轮冷却），不落假「等待应答」。
 *
 * [onBatch]：应答集合每次变化即回调（实时流入搜索页），载荷为当前全集解析结果。
 * @return true = 关键词真实发出（用于调用方的一词一轮冷却记账；预算耗尽/发送失败 = false）
 */
internal suspend fun TdLibGateway.askSearchEntityImpl(
    entity: SearchEntity,
    query: String,
    onBatch: suspend (SearchEntity, List<GroupAnswer>, List<String>) -> Unit,
): Boolean {
    var sentOk = false
    // R366 写闸（§十 缺口收口）：账号写保护测试闸开=入口即拦，如实日志零发送零永挂
    // （预算不记、信誉不记、一词一轮冷不却因本轮烧掉——什么都没发生）
    if (accountWriteBlocked()) {
        Log.w(TAG, "askSearchEntity: 被账号写保护拦截（§十测试模式，未发词）chatId=${entity.chatId}")
        return false
    }
    runCatching {
        awaitAuthorized()
        val q = query.trim()
        require(q.isNotEmpty()) { "关键词为空" }
        val harvester = groupHarvester
        // flood 记账走秒回专用车道（与收割车道解耦：后台收割吃光预算不饿死用户主动秒问；
        // 额度为常量定格，无需逐次刷新）
        if (!liveAskBudget.tryAcquire()) {
            Log.w(TAG, "askSearchEntity: 秒回预算耗尽，跳过 chatId=${entity.chatId}（${entity.title}）")
            return@runCatching
        }
        Log.i(
            TAG,
            "askSearchEntity: 发词「$q」→ chatId=${entity.chatId}（${entity.title}/" +
                "${if (entity.kind == SearchEntity.KIND_BOT) "bot私聊" else "群"}）秒回预算剩=${liveAskBudget.remaining()}",
        )
        val startSec = (System.currentTimeMillis() / 1000L).toInt()
        // 发送关键词（群/私聊同一 SendMessage，6 参签名见 searchViaBot 头注释 javap 实证；
        // 群内无发言权限等失败由外层 runCatching 静默收场）
        // R366：超时兜底——隐性限流挂起 12s 到点按发送失败诚实收场（sentOk 留 false，
        // 调用方不记一词一轮冷却、UI 不落假「等待应答」长转），协程不再永挂
        val sent = withTdTimeout(what = "askSearchEntity/SendMessage chatId=${entity.chatId}") {
            client.send(
                TdApi.SendMessage(
                    entity.chatId, null, null, null, null,
                    TdApi.InputMessageText(TdApi.FormattedText(q, emptyArray()), null, false),
                )
            )
        } ?: return@runCatching
        sentOk = true
        // R378 刀4 liveAsk 自词登记：词真发出后记 fold 键（GoldmineHooks.onHarvested
        // 热词计数段窗内排除自词=R372 自增强环断环；链接侧回灌/监听器/答案库语义
        // 照旧——搜索页 liveAsk 显式提交链零碰，只加发词成功后这一行登记）
        GoldmineHooks.noteLiveAskSent(q)
        // R64 信誉记账：关键词真实发出才记"被发词"（预算耗尽/发送失败不计）；
        // sendMs 供首条应答延迟计时
        val sendMs = System.currentTimeMillis()
        runCatching { harvester.reputation.noteAsked(entity.chatId) }
        Log.i(TAG, "askSearchEntity: 信誉记账 chatId=${entity.chatId} 发词+1")
        val repliesById = LinkedHashMap<Long, TdApi.Message>()
        val recorded = HashSet<String>() // 本轮已回灌链接（防轮内重复 recordAnswer 虚增 hits）
        var answeredNoted = false // R64：本轮"首次应答"信誉只记一次
        val deadline = System.currentTimeMillis() + LIVE_ASK_WINDOW_MS
        var lastSig = ""
        var lastChangeMs = 0L
        while (System.currentTimeMillis() < deadline) {
            delay(LIVE_ASK_POLL_MS)
            val history = runCatching {
                // R366：超时兜底——单轮拉取挂起 12s 到点按 null 计（等下一轮；监听环
                // 硬截止 LIVE_ASK_WINDOW_MS 原样兜底，超时只是单轮上限闸）
                withTdTimeout(what = "askSearchEntity/GetChatHistory chatId=${entity.chatId}") {
                    client.send(TdApi.GetChatHistory(entity.chatId, 0, 0, BOT_HISTORY_LIMIT, false))
                }
            }.getOrNull() ?: continue // 单轮拉取失败/超时不计数，等下一轮
            history.messages.filterNotNull()
                .filter { m ->
                    if (m.isOutgoing || m.date < startSec - 3) return@filter false
                    when (entity.kind) {
                        // 私聊：窗口期内对方新消息都算应答
                        SearchEntity.KIND_BOT -> true
                        // 群：只认回复本轮关键词消息的（replyTo 精确配对，防把群聊闲话当答案）
                        else -> (m.replyTo as? TdApi.MessageReplyToMessage)?.messageId == sent.id
                    }
                }
                .forEach { repliesById[it.id] = it }
            if (repliesById.isEmpty()) continue
            // 变化签名 = 消息 id + 编辑时间：bot 编辑补链接/改答案也能被看见
            val sig = repliesById.values.joinToString("|") { "${it.id}:${it.editDate}" }
            if (sig == lastSig) {
                // 静默收尾：应答集合稳定即提前收工（秒回场景不必撑满窗口）
                if (System.currentTimeMillis() - lastChangeMs >= LIVE_ASK_QUIET_MS) break
                continue
            }
            lastSig = sig
            lastChangeMs = System.currentTimeMillis()
            val (links, texts) = parseLiveBatch(entity, repliesById.values.toList())
            // R64 信誉记账：本轮首次有链接/文本回来记"应答+1"与首条延迟（一轮只记一次）
            if (!answeredNoted && (links.isNotEmpty() || texts.isNotEmpty())) {
                answeredNoted = true
                val latMs = System.currentTimeMillis() - sendMs
                runCatching { harvester.reputation.noteAnswered(entity.chatId, latMs) }
                Log.i(TAG, "askSearchEntity: 信誉记账 chatId=${entity.chatId} 首次应答 延迟=${latMs}ms")
            }
            // 回灌（只记本轮新见链接）：AnswerStore + 图谱 group_search 边 + 懒解析入队，
            // 与收割 recordLinks 同链路；全部异常静默，绝不影响监听主链
            for (a in links) {
                if (!recorded.add(a.link)) continue
                // 按钮标题线索随答案回灌入库（旧记录没线索时升级补上）
                runCatching { harvester.answerStore.recordAnswer(q, entity.chatId, a.link, a.hintTitle, a.members) }
                recordGroupSearchLink(entity.chatId, a.link)
                runCatching { harvester.entityResolver.enqueueHarvest(entity.chatId, a.link) }
                // R64 信誉记账：登记"链接→来源实体"，用户点击后的解析终局据此归因
                runCatching { harvester.reputation.noteLinkSource(a.link, entity.chatId) }
            }
            Log.d(TAG, "askSearchEntity: chatId=${entity.chatId} 应答变化：链接=${links.size} 文本=${texts.size}（实时上屏）")
            onBatch(entity, links, texts)
        }
        // 轮末热词计数（GoldmineHooks 口径：一次问答事件记一次，不按链接数放大）
        if (recorded.isNotEmpty()) {
            runCatching { GoldmineHooks.onHarvested(q, recorded.toList()) }
        }
        Log.i(TAG, "askSearchEntity: chatId=${entity.chatId} 收场：应答=${repliesById.size} 回灌链接=${recorded.size}")
        // R64：轮末顺手落盘信誉账（秒回事件低频，靠阈值 flush 跨进程容易攒不够数）
        runCatching { harvester.reputation.flush() }
    }.onFailure {
        // 协程取消（新一轮发词/离开搜索页）不是失败，照常上抛保证取消语义
        if (it is kotlinx.coroutines.CancellationException) throw it
        Log.w(TAG, "askSearchEntity 失败 chatId=${entity.chatId}（${entity.title}）: ${it.message}")
    }
    return sentOk
}

/**
 * 秒回应答批次解析（askSearchEntity 内部）：一批应答消息 → (链接答案, 纯文本答案)。
 * 过滤口径与收割 recordLinks/processBatch 同族：
 *  - 广告判定组件级拆分（R61）：正文/caption 含垃圾词 → 整条弃（原语义保留）；
 *    仅按钮含垃圾词 → 不判死整条，只逐组件剔除垃圾按钮的链接；
 *  - 邀请链（t.me/+…/joinchat）/广告参数链/黑名单频道链剔除
 *    （纯查不记分——观测记账是被动收割的语义，主动问答不喂分类器统计）；
 *  - 无链接且非人机验证门的 MessageText 正文成纯文本答案（截断 200 字，逐批去重）。
 */
internal fun TdLibGateway.parseLiveBatch(
    entity: SearchEntity,
    msgs: List<TdApi.Message>,
): Pair<List<GroupAnswer>, List<String>> {
    val links = LinkedHashMap<String, GroupAnswer>()
    val texts = ArrayList<String>()
    val now = System.currentTimeMillis()
    // R60 页脚观测：秒回应答与 searchViaBot 同形态（单消息原地编辑 + 页脚
    // "共 N 条结果，第 x/y 页"），解析总数记日志（"该词全网共 N 条"——
    // 后续产品可用，本轮先观测）；页脚缺省/变体拿不到则静默跳过
    parseBotPageFooter(msgs)?.let { f ->
        if (f.total > 0) {
            Log.d(
                TAG,
                "parseLiveBatch: chatId=${entity.chatId} 页脚解析：该词全网共 ${f.total} 条结果" +
                    "（第 ${f.page}/${f.pages} 页）",
            )
        }
    }
    // R60 自指防污染：私聊实体的应答者即 bot 本身（title=@名）；群模式应答 bot 用户名
    // 结构未知，由 isSelfReferentialSearchLink 的"已知搜索 bot 配置名"口径兜底
    val responderUname = if (entity.kind == SearchEntity.KIND_BOT) {
        entity.title.removePrefix("@")
    } else {
        null
    }
    for (m in msgs.sortedBy { it.id }) {
        // R61 组件级广告判定：正文/caption 含垃圾词 → 整条弃（与收割同口径，原语义保留）
        if (AdBlacklist.isSpam(liveMsgBodyText(m))) continue
        // 仅按钮含垃圾词不判死整条：逐组件剔除垃圾按钮的链接，正文与干净按钮链接照常收
        val (msgLinks, droppedBtnLinks, bodyLinkCount) = extractAnswerLinksFiltered(m)
        if (droppedBtnLinks > 0) {
            Log.d(
                TAG,
                "parseLiveBatch: 按钮含垃圾词但正文干净：剔按钮链接 $droppedBtnLinks 条" +
                    "保留正文链接 $bodyLinkCount 条",
            )
        }
        // 内联按钮"文本↔链接"配对线索（429 封死 SearchPublicChat 时的零网络标题兜底）
        val hints = extractLinkTitleHints(m)
        if (msgLinks.isEmpty()) {
            val text = (m.content as? TdApi.MessageText)?.text?.text?.trim().orEmpty()
            if (text.isNotEmpty() && !AdBlacklist.looksLikeCaptcha(text)) {
                texts += text.take(200)
            }
            continue
        }
        for (link in msgLinks) {
            val path = link.removePrefix("https://t.me/")
            if (path.startsWith("+") || path.startsWith("joinchat", ignoreCase = true)) continue
            // R60 自指防污染：剔除"指向搜索实体自身的深链"（t.me/<应答bot>?start=…
            // 相关热词回链，点开=替用户再搜一次，不是资源答案，入库=递归自指污染）
            if (isSelfReferentialSearchLink(link, responderUname)) {
                Log.d(TAG, "parseLiveBatch: 剔除自指深链 $link（指向搜索实体自身的热词回链，非资源答案）")
                continue
            }
            if (AdBlacklist.isSpamLink(link)) continue
            val cid = AnswerStore.chatIdFromLink(link)
            val uname = AnswerStore.usernameFromLink(link)
            if (AdChannelClassifier.isBlockedRuntime(cid, uname)) continue
            links.putIfAbsent(
                link,
                GroupAnswer(
                    link = link, chatId = cid, username = uname, fromGroup = entity.chatId,
                    lastTs = now, hits = 1,
                    // 标题线索 + 行尾成员数（R48）随答案一并入库/上屏
                    hintTitle = hints[link]?.title.orEmpty(),
                    members = hints[link]?.members ?: 0,
                ),
            )
        }
    }
    return links.values.toList() to texts.distinct().take(5)
}

/**
 * 秒回消息正文文本（R61 组件级广告判定的"正文段"）：正文/媒体 caption，
 * 不含 inline 按钮文本与链接——按钮垃圾词走 [extractAnswerLinksFiltered]
 * 逐组件剔链，不再拼进整条判定串连坐正文。
 */
internal fun TdLibGateway.liveMsgBodyText(m: TdApi.Message): String =
    when (val c = m.content) {
        is TdApi.MessageText -> c.text?.text.orEmpty()
        is TdApi.MessagePhoto -> c.caption?.text.orEmpty()
        is TdApi.MessageVideo -> c.caption?.text.orEmpty()
        is TdApi.MessageAudio -> c.caption?.text.orEmpty()
        is TdApi.MessageDocument -> c.caption?.text.orEmpty()
        else -> ""
    }

/**
 * R61 组件级答案链接提取：正文（MessageText 实体+正则）链接全收；inline 按钮
 * 逐组件判定——按钮文本或按钮 url 含垃圾词只剔该按钮这一条链接，不连坐整条消息
 * （垃圾词表沿用 [AdBlacklist.isSpam] 同一口径，只是粒度从整条细化到按钮组件）。
 * 返回 (保留链接, 被剔的垃圾按钮链接数, 正文链接数)。
 */
internal fun TdLibGateway.extractAnswerLinksFiltered(m: TdApi.Message): Triple<List<String>, Int, Int> {
    val out = ArrayList<String>()
    (m.content as? TdApi.MessageText)?.text?.let { out += extractTmeLinks(it) }
    val bodyLinkCount = out.size
    var droppedBtnLinks = 0
    (m.replyMarkup as? TdApi.ReplyMarkupInlineKeyboard)?.rows?.forEach { row ->
        row?.forEach { button ->
            val url = (button?.type as? TdApi.InlineKeyboardButtonTypeUrl)?.url
                ?: return@forEach // 回调/登录等非 url 按钮无链接可剔
            // 按钮组件级垃圾判定：按钮文本或 url 命中垃圾词 → 只剔该按钮链接
            if (AdBlacklist.isSpam("${button?.text.orEmpty()} $url")) {
                droppedBtnLinks++
                return@forEach
            }
            normalizeTmeLink(url)?.let(out::add)
        }
    }
    return Triple(out, droppedBtnLinks, bodyLinkCount)
}

// ---- 答案区 eager 批量解析（"群友刚答的"/"群友搜到过"内联内容卡数据源） ----

/**
 * 答案链接 → (链接, 可播条目) 对的批量解析核心：Semaphore([ANSWER_INLINE_RESOLVE_CONCURRENCY])
 * 并发 GetMessageLinkInfo → toVideoItem（awaitAll 保序）。文本帖/频道主页类链接
 * （message==null 或非媒体内容）自然落空返回不出对，由 UI 回落标题行；
 * 单条失败只丢该条（调用方进死信）。**不做相册共享 caption**（链接指向的是具体消息，
 * bot 答案通常直链带 caption 的那条；searchViaGroup 的相册共享口径不搬，保持本辅助轻量）。
 */
internal suspend fun TdLibGateway.resolveLinksToItems(links: List<String>): List<Pair<String, VideoItem>> {
    val sem = Semaphore(ANSWER_INLINE_RESOLVE_CONCURRENCY)
    return coroutineScope {
        links.distinct().map { link ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    runCatching {
                        // R366：超时兜底——隐性限流挂起 12s 到点按单条落空跳过（与既有
                        // 「单条失败只丢该条」同口径，整批不连坐不永挂）
                        val msg = withTdTimeout(what = "resolveLinksToItems/GetMessageLinkInfo") {
                            client.send(TdApi.GetMessageLinkInfo(link))
                        }?.message
                            ?: return@runCatching null // 频道主页类链接无消息/超时，落空交 UI 回落
                        toVideoItem(msg.chatId, msg, null)?.let { link to it }
                    }.onFailure { Log.w(TAG, "resolveLinksToItems: 解析失败 $link: ${it.message}") }
                        .getOrNull()
                }
            }
        }.awaitAll().filterNotNull()
    }
}

/**
 * 秒回区 eager 解析（"群友刚答的"内容卡）：整批记 1 次**秒回车道**预算（liveAskBudget，
 * 与发送/监听同车道——同属一次用户主动问答的后续动作）。
 * 返回 null = 预算耗尽**未尝试**（调用方不进死信、下轮可重试）；空表/部分 = 已尝试的结果。
 */
internal suspend fun TdLibGateway.resolveLiveAnswerItemsImpl(links: List<String>): List<Pair<String, VideoItem>>? {
    if (links.isEmpty()) return emptyList()
    if (!liveAskBudget.tryAcquire()) {
        Log.w(TAG, "resolveLiveAnswerItems: 秒回预算耗尽，本批 ${links.size} 条未尝试（下轮可重试）")
        return null
    }
    awaitAuthorized()
    val r = resolveLinksToItems(links)
    Log.i(TAG, "resolveLiveAnswerItems: 请求 ${links.size} 条，解析出 ${r.size} 条（秒回预算剩=${liveAskBudget.remaining()}）")
    return r
}

/**
 * 历史答案区 eager 解析（"群友搜到过"内容卡）：整批记 1 次**查询车道**预算
 * （floodBudgetQuery，与 searchViaGroup 同车道同口径——历史答案批量解析本就是它的活）。
 * 返回 null = 预算耗尽**未尝试**（调用方不进死信、下轮可重试）；空表/部分 = 已尝试的结果。
 */
internal suspend fun TdLibGateway.resolveGroupAnswerItemsImpl(links: List<String>): List<Pair<String, VideoItem>>? {
    if (links.isEmpty()) return emptyList()
    floodBudgetQuery.maxOpsPerHour = appPrefs.floodQueryPerHour
    if (!floodBudgetQuery.tryAcquire()) {
        Log.w(TAG, "resolveGroupAnswerItems: 查询预算耗尽，本批 ${links.size} 条未尝试（下轮可重试）")
        return null
    }
    awaitAuthorized()
    val r = resolveLinksToItems(links)
    Log.i(TAG, "resolveGroupAnswerItems: 请求 ${links.size} 条，解析出 ${r.size} 条")
    return r
}

/** 标题批量解析结果：[titles] 成功的小写用户名→(chatId,标题) 映射；[hadFlood] 是否
 *  残留限流失败（调用方据此放出词闸/下轮重试，防"整批被 429 挤掉却把一词一轮闸烧掉"）；
 *  [retryAfterSec] 残留限流里服务端给的最大等待秒数（0=未给/无限流残留），
 *  供调用方定自动重踢的间隔（不再固定 30s 硬撞长封禁） */
class TitleResolveOutcome(
    val titles: Map<String, Pair<Long, String>>,
    val hadFlood: Boolean,
    val retryAfterSec: Int = 0,
)

/** 答案区标题解析的死信表已持久化进熔断表（R44，flood_breaker.json 的
 *  answerTitleDead 段）：SearchPublicChat 400（用户名失效/不可见/被封）的用户名
 *  永不重试、重启不重烧（与内容卡 answerResolveDead 同口径——稳定终态重试只是白烧请求） */

/**
 * 429 封死期的零网络标题回填：读指定来源会话（bot 私聊/搜索群）最近历史消息，
 * 从内联按钮"文本↔链接"配对聚合 链接→标题 线索（jisou 类 bot 的按钮文本即频道名）。
 * SearchPublicChat 被封号级限流时（实测 retry after 15000s+），GetChatHistory 是
 * 另一条 API 泳道（实证：同轮 SearchPublicChat 全灭而收割/秒回监听照常工作）。
 * @param chatIds 来源会话（调用方取待解答案的 fromGroup 去重集，精准不撒网）
 * @param perChatLimit 每会话回看条数（bot 应答消息按钮密度高，默认 50 条足够覆盖近批答案）
 */
internal suspend fun TdLibGateway.harvestAnswerTitleHintsImpl(chatIds: List<Long>, perChatLimit: Int = 50): Map<String, HintTitle> {
    val out = LinkedHashMap<String, HintTitle>()
    for (cid in chatIds.distinct()) {
        val history = runCatching {
            // R366：超时兜底——单会话拉取挂起 12s 到点按 null 计（只丢该会话，
            // 与既有失败口径一致，零网络回填环不永挂）
            withTdTimeout(what = "harvestAnswerTitleHints/GetChatHistory chatId=$cid") {
                client.send(TdApi.GetChatHistory(cid, 0, 0, perChatLimit, false))
            }
        }.getOrNull() ?: continue // 单会话失败/超时只丢该会话（静默，绝不影响其他来源）
        for (m in history.messages.filterNotNull()) {
            val hints = extractLinkTitleHints(m)
            for ((link, hint) in hints) {
                val prev = out[link]
                // 标题先到先得；成员数仅补未知（与 extractLinkTitleHints 的合并口径一致）
                when {
                    prev == null -> out[link] = hint
                    prev.members <= 0 && hint.members > 0 -> out[link] = prev.copy(members = hint.members)
                    else -> {}
                }
            }
        }
    }
    Log.i(TAG, "harvestAnswerTitleHints: 扫描 ${chatIds.size} 个来源会话，攒到 ${out.size} 条标题线索")
    return out
}

/**
 * 答案区"识别中…"标题行懒解析（投诉"机器人一直显示识别中"的修复）：没解析出内容卡、
 * chatId==0 的用户名链接（t.me/<name> 频道主页类链接 GetMessageLinkInfo 恒落空——
 * 它们永远出不了内容卡，正确呈现是**带真标题的频道行**），小预算 SearchPublicChat
 * 换真 chatId+标题；成功顺手喂 channelTitleCache + 图谱 recordNodeTitle（下次零网络可读）。
 *
 * [live]=true 记**秒回车道**（liveAskBudget，与 resolveLiveAnswerItems 同车道）、
 * false 记**查询车道**（floodBudgetQuery，与 resolveGroupAnswerItems 同车道）。
 * 返回 null = 预算耗尽**未尝试**（调用方放闸/下轮重试）；否则返回
 * [TitleResolveOutcome]（只含成功项；永久失败进持久化死信不再重试（R44，
 * 重启不重烧），限流失败 8s 退避补一枪后仍败的置 hadFlood=true 让调用方放闸）。
 * R44：入口先查熔断闸——SearchPublicChat 静默中立即降级（0 次真实发送、不烧预算），
 * hadFlood=true + 闸剩余秒数让调用方按真实 retry-after 节奏重踢。3 路并发。
 */
internal suspend fun TdLibGateway.resolveAnswerTitlesImpl(usernames: List<String>, live: Boolean): TitleResolveOutcome? {
    // R44 熔断闸（函数入口先查闸，先于死信过滤与预算记账）：SearchPublicChat 静默中
    // 立即收场——0 次真实发送、不烧预算令牌；hadFlood=true + 闸剩余秒数交调用方
    // 按真实 retry-after 节奏自动重踢（不硬撞长封禁）。
    // R58：半开试探期不整批拒——逐条经 resolvePublicChatByUsername 过闸限量放行，
    // 间隔未到被拒的名字落 floodFailed（retryAfter=0 不触发批内补枪），交调用方慢速重踢
    val family = FloodCircuitBreaker.FAMILY_SEARCH_PUBLIC_CHAT
    if (floodBreaker.isSilenced(family)) {
        val remain = floodBreaker.remainingSec(family)
        Log.i(TAG, "resolveAnswerTitles: SearchPublicChat 熔断静默中（剩 ${remain}s），本批未发送直接降级")
        return TitleResolveOutcome(
            titles = emptyMap(), hadFlood = true,
            retryAfterSec = remain.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
    }
    val todo = usernames.map { it.trim().removePrefix("@").lowercase() }
        .filter { it.isNotEmpty() && !floodBreaker.isAnswerTitleDead(it) } // 死信不再烧请求（R44 持久化）
        .distinct().take(ANSWER_TITLE_RESOLVE_MAX)
    if (todo.isEmpty()) return TitleResolveOutcome(emptyMap(), hadFlood = false)
    if (live) {
        if (!liveAskBudget.tryAcquire()) {
            Log.w(TAG, "resolveAnswerTitles: 秒回预算耗尽，本批 ${todo.size} 个未尝试（下轮可重试）")
            return null
        }
    } else {
        floodBudgetQuery.maxOpsPerHour = appPrefs.floodQueryPerHour
        if (!floodBudgetQuery.tryAcquire()) {
            Log.w(TAG, "resolveAnswerTitles: 查询预算耗尽，本批 ${todo.size} 个未尝试（下轮可重试）")
            return null
        }
    }
    awaitAuthorized()
    // 3 路并发（与答案区 eager 解析同口径）：上限提到 12 后串行会让整批标题等 12×单条耗时，
    // 秒回区"等待应答"尾巴拖长；并发后单批耗时 ≈ 4×单条，flood 风险不变（同批总量不变）
    val out = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
    // 限流失败名 → 服务端 retry-after 秒数（补枪等待时长的依据；0 = 服务端没给）
    val floodFailed = java.util.concurrent.ConcurrentHashMap<String, Int>()
    suspend fun resolveOne(name: String) {
        // R184 车道折算：live=true 秒回区是用户显式提交的直接后续（用户动作车道）；
        // false 历史区批量解析是机会型后台（后台车道——配额紧张时最先让路）
        val r = resolvePublicChatByUsername(name, if (live) SearchQuotaLedger.Lane.USER_ACTION else SearchQuotaLedger.Lane.BACKGROUND)
        when {
            r.ok && r.title.isNotBlank() -> {
                out[name] = r.chatId to r.title
                // 标题沉进图谱：下次 answerTitleOf 零网络直接命中，不再挂"识别中…"
                runCatching { discoveryGraph.recordNodeTitle(r.chatId, r.title) }
            }
            r.floodWait -> floodFailed[name] = r.floodRetryAfterSec // 429 限流：攒着退避后补一枪
            r.permanent -> {
                // 稳定终态（400 用户名失效/不可见）：进死信（R44 已持久化，重启不重烧）
                floodBreaker.markAnswerTitleDead(name)
                Log.d(TAG, "resolveAnswerTitles: @$name 永久失败（用户名失效/不可见），进死信")
            }
            else -> Log.d(TAG, "resolveAnswerTitles: @$name 瞬态失败（网络类），留给下轮")
        }
    }
    coroutineScope {
        val sem = Semaphore(ANSWER_INLINE_RESOLVE_CONCURRENCY)
        todo.map { name ->
            async(Dispatchers.IO) { sem.withPermit { resolveOne(name) } }
        }.awaitAll()
    }
    // 限流退避补枪一轮（实证：全量搜索扇出并发期 SearchPublicChat 被 429 挤掉，
    // 历史区标题整批 0/12）。等待时长吃服务端的 retry-after（clamp 5~20s）：
    // 短波峰等得起；服务端要求 >20s 的长封禁等不起——放弃批内补枪直接置 hadFlood，
    // 由调用方按 retryAfterSec 慢速自动重踢（不再固定 30s 硬撞长封禁）
    val maxRetryAfter = floodFailed.values.maxOrNull() ?: 0
    if (floodFailed.isNotEmpty() && maxRetryAfter in 1..20) {
        val retry = floodFailed.keys.toList()
        floodFailed.clear()
        delay(maxRetryAfter.coerceAtLeast(5) * 1000L)
        coroutineScope {
            val sem = Semaphore(ANSWER_INLINE_RESOLVE_CONCURRENCY)
            retry.map { name ->
                async(Dispatchers.IO) { sem.withPermit { resolveOne(name) } }
            }.awaitAll()
        }
    }
    Log.i(
        TAG,
        "resolveAnswerTitles: 请求 ${todo.size} 个用户名，解析出 ${out.size} 个" +
            "（live=$live, 限流残=${floodFailed.size}, retryAfter=${maxRetryAfter}s, 死信累计=${floodBreaker.answerTitleDeadCount()}）",
    )
    return TitleResolveOutcome(
        titles = out,
        hadFlood = floodFailed.isNotEmpty(),
        retryAfterSec = floodFailed.values.maxOrNull() ?: 0,
    )
}

/**
 * 网页泳道批量补标题：返回 小写用户名 → 频道名+成员数（[WebPreviewResolver.WebChannelInfo]，只含成功项）。
 * 进程内成功缓存/永久死信/in-flight 单飞/2 并发/429 静默全由 [WebPreviewResolver] 自持，
 * 调用方零预算零配额语义，可在 SearchPublicChat 泳道跑完后无条件补一枪。
 * R48：成员数取自预览页 og:description 的"N members/subscribers"文案，解析不出 = 0。
 */
internal suspend fun TdLibGateway.resolveTitlesViaWebImpl(usernames: List<String>): Map<String, WebPreviewResolver.WebChannelInfo> =
    webPreviewResolver.resolveTitles(usernames)

// ---------------- 资源嗅探（DISCOVERY-GRAPH M1 被动钩子） ----------------

/**
 * 单条消息的被动嗅探：forwardInfo 溯源 + forward_dst 去向 + t.me 互链提取。
 * 纯本地字段读取，0 额外网络请求；调用方（fetchFromChannel）已用
 * runCatching 包裹，这里无需也不应再吞错后做任何补偿逻辑。
 *
 * R283 幂等闸：同一条 (chatId, messageId) 只贡献一次边权——调用方（页/线程/原帖/
 * 回填四路径）在喂消息前先经 [SniffLedger.isNew] 判定、喂完后按页区间/散点记账；
 * 本函数自身不记账（页级合并记账比逐条记区间利用率高一个量级）。
 *
 * 签名涉及的 TdApi 字段已 javap 实证（TDLib 1.8.67）：
 * Message.forwardInfo: MessageForwardInfo{origin: MessageOrigin, …}
 * MessageOriginChannel{chatId, messageId, authorSignature}
 */
internal fun TdLibGateway.sniffMessage(chatId: Long, msg: TdApi.Message) {
    // 1) forwardInfo 溯源：origin 是频道 → 记录"当前频道 → 源频道"的供出链
    //    （forward_src：搬运号把它上游的农场供出来；源频道未关注即候选）
    (msg.forwardInfo?.origin as? TdApi.MessageOriginChannel)?.let { origin ->
        discoveryGraph.recordEdge(chatId, origin.chatId, DiscoveryGraph.EDGE_FORWARD_SRC)
        // 2) forward_dst 接线（P1-b，DISCOVER-UPGRADE #5）：同一次转发观测的反向边
        //    ——"源频道 → 当前频道"（源的内容被转进了本频道）。一次观测正反两条边，
        //    图密度翻倍、零新增请求；自环由 recordEdge 内部丢弃。
        //    搬运枢纽号的高 out-degree 偏置由图谱建边侧打标 + ranker HUB_PENALTY 压制
        discoveryGraph.recordEdge(origin.chatId, chatId, DiscoveryGraph.EDGE_FORWARD_DST)
    }
    // 3) 互链嗅探：MessageText 正文/按钮 + 媒体 caption 里的 t.me 链接，
    //    全部复用 extractTmeLinks 的现成解析口径
    //    R283：caption 提取收敛六类共享件（缺口③——旧手写 when 只收 Video/Photo/
    //    Audio 三类，Animation/VoiceNote/Document 的 caption 链接整类漏收）
    val links = ArrayList<String>()
    links += extractTmeLinks(msg)
    links += sniffCaptionLinksOf(msg)
    for (link in links) sniffLink(chatId, link)
}

/**
 * R283 互链嗅探 caption 六类共享件（F13 口径分裂收口）：媒体 caption 里的 t.me
 * 链接一次取全——Video/Photo/Audio/Animation/VoiceNote/Document，与 [mediaCaptionOf]
 * 同覆盖面（动图配文/语音说明/文档说明挂 t.me 链接是资源频道常态）。
 * sniffMessage（主帖）与评论级联段两处原手写 when 统一走本件；正文/按钮链接不在
 * 本件范围（extractTmeLinks(msg) 已覆盖，调用方各自拼接）。
 */
internal fun TdLibGateway.sniffCaptionLinksOf(msg: TdApi.Message): List<String> {
    val ft = when (val c = msg.content) {
        is TdApi.MessageVideo -> c.caption
        is TdApi.MessagePhoto -> c.caption
        is TdApi.MessageAudio -> c.caption
        is TdApi.MessageAnimation -> c.caption
        is TdApi.MessageVoiceNote -> c.caption
        is TdApi.MessageDocument -> c.caption
        else -> null
    } ?: return emptyList()
    if (ft.text.isNullOrEmpty()) return emptyList()
    return extractTmeLinks(ft)
}

/**
 * 一条已归一化的 t.me 链接 → 图谱边（三级解析的前两级，全本地）：
 * - t.me/c/<id>/<msgId>：私有频道内部链接，chatId = "-100<id>" 直接可得，记 [edgeType] 边；
 * - t.me/addlist/<slug>：合集邀请链接（P1-b 合集链接收割）——异步触发
 *   [harvestChatFolderInvite]（本函数同步非挂起、跑在抓页热路径上，收割走 scope 即发即忘），
 *   一次 CheckChatFolderInviteLink 把合集全部频道建边入候选；顺带修复了旧口径把
 *   "addlist" 当用户名塞进 resolveQueue 的死信噪声；
 * - t.me/<name>[/<msgId>]：公开用户名，SearchPublicChat 解析太贵，
 *   记入 resolveQueue 待懒解析（引用方证据已保留，解析成功后补写真实边）；
 * - t.me/+xxx 邀请链接 / joinchat：无法解析为公开频道，丢弃（normalizeTmeLink 口径延伸）。
 *
 * @param edgeType 边类型：主帖互链默认 [DiscoveryGraph.EDGE_LINK]；评论区便车传
 *   [DiscoveryGraph.EDGE_COMMENT]（t.me/c/ 直链边与懒解析证据共用同一引用方语义）
 */
internal fun TdLibGateway.sniffLink(fromChatId: Long, link: String, edgeType: String = DiscoveryGraph.EDGE_LINK) {
    val path = link.removePrefix("https://t.me/")
    val seg = path.substringBefore('/').takeIf { it.isNotBlank() } ?: return
    when {
        seg == "c" -> {
            val innerId = path.substringAfter('/').substringBefore('/').toLongOrNull() ?: return
            discoveryGraph.recordEdge(fromChatId, "-100$innerId".toLong(), edgeType)
        }
        seg.equals("addlist", ignoreCase = true) -> {
            // slug 提取：substringAfter 无分隔符时回落 ""（防裸 "addlist" 串被当成 slug），
            // 再截到首段（addlist/<slug>/ 多余尾段丢弃）
            val slug = path.substringAfter('/', "").substringBefore('/')
                .takeIf { it.isNotBlank() } ?: return
            scope.launch(Dispatchers.IO) {
                harvestChatFolderInvite(fromChatId, "https://t.me/addlist/$slug")
            }
        }
        seg.startsWith("+") || seg.equals("joinchat", ignoreCase = true) -> Unit
        else -> discoveryGraph.recordPendingName(fromChatId, seg)
    }
}
