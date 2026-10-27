package dev.tgfeed.core.td

import android.util.Log
import dev.tgfeed.core.feed.DiscoveryGraph
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.random.Random

// TailNote 仍嵌套在 TdLibGateway 内（尾部文字批注的携带模型），本文件裸名引用经此别名落到同一类型
private typealias TailNote = TdLibGateway.TailNote

/**
 * 单频道服务端搜索：SearchChatMessages + SearchMessagesFilterVideo。
 * cursor = fromMessageId（0/null = 从最新开始）；响应 nextFromMessageId == 0 表示没有更多。
 * 任何失败（flood wait / 网络异常）回退 GetChatHistory + 本地 caption 过滤。
 *
 * 签名已用 javap 对本项目 core-release.aar 实证：
 * SearchChatMessages(long, MessageTopic, String, MessageSender, long, int, int, SearchMessagesFilter)
 *   -> FoundChatMessages{totalCount, messages, nextFromMessageId}
 */
internal suspend fun TdLibGateway.searchInChannel(chatId: Long, query: String, cursor: String?, limit: Int): FeedPage {
    val from = cursor?.toLongOrNull() ?: 0L
    return try {
        // R167 超时补漏（R45 家族收编）：fetchFromChannel 10-07 已给 GetChatHistory 补
        // 10s 超时防"挂起请求永久占车道"，两个搜索入口当时漏跟——native 侧冻死
        // （R165 anr-analysis：TdLib 线程持锁死于 bad_alloc）时 client.send 永不返回，
        // loading 永真。超时按失败走外层 catch 的回退路（GetChatHistory + 本地过滤），
        // 与 fetchFromChannel 同数值同语义（放弃等待、不撤已入 native 队列的请求）
        val found = withTimeout(10_000) {
            client.send(
                TdApi.SearchChatMessages(
                    chatId, null, query, null, from, 0, limit, TdApi.SearchMessagesFilterVideo()
                )
            )
        }
        // R257b 标题回退链证据：本路径是"过滤后建表"（服务端 Video filter）——带
        // caption 的异类型相册成员和批注文字帖都不在命中集，对仍空缺的做限量邻居
        // 窗口展开（未过滤整窗建表：链② 跨类型兄弟 + 链④ 批注帖；只填 caption，
        // 不改变搜索结果集）；链③ 转发原帖页级批查（预算+TTL 缓存，静默）。
        // 证据收进 TitleChainContext，toVideoItem 内 resolveTitle 按链序裁决
        val hitMsgs = found.messages.filterNotNull()
        val albumCaps = mergedAlbumCaptions(chatId, hitMsgs)
        val expanded = expandBlankCaptions(chatId, hitMsgs, albumCaps)
        val forwardCaps = resolveForwardCaptions(
            hitMsgs, expanded.albumCaps,
            java.util.concurrent.atomic.AtomicInteger(FORWARD_CAPTION_MAX_PER_PAGE),
        )
        val titleCtx = TitleChainContext(
            albumCaps = expanded.albumCaps,
            forwardCaps = forwardCaps,
            adjacentNotes = expanded.notes,
        )
        val items = hitMsgs
            .mapNotNull { toVideoItem(chatId, it, null, titleCtx = titleCtx) }
        Log.d(TAG, "searchInChannel: chatId=$chatId q=$query videos=${items.size}/${found.totalCount} next=${found.nextFromMessageId}")
        FeedPage(items, if (found.nextFromMessageId != 0L) found.nextFromMessageId.toString() else null)
    } catch (e: Exception) {
        Log.w(TAG, "searchInChannel 失败，回退本地过滤: ${e.message}")
        fetchFromChannel(chatId, from, limit, query)
    }
}

/**
 * 全局服务端搜索：SearchMessages(chatList=ChatListMain, filter=Video)。
 * cursor 是 TDLib 的不透明 offset 字符串，加 "g:" 前缀与聚合游标区分（null = 首页）；
 * 响应 nextOffset 为空串表示没有更多。
 * 失败回退逐频道聚合 + 本地 caption 过滤（复用聚合游标结构）。
 *
 * 签名已 javap 实证：
 * SearchMessages(ChatList, String, String offset, int limit, SearchMessagesFilter,
 *                SearchMessagesChatTypeFilter, int minDate, int maxDate)
 *   -> FoundMessages{totalCount, messages, nextOffset}
 */
internal suspend fun TdLibGateway.searchGlobal(query: String, cursor: String?, limit: Int): FeedPage {
    val offset = cursor?.removePrefix("g:") ?: ""
    return try {
        // R167 超时补漏（R45 家族收编，与 searchInChannel 同笔）：见上——超时按失败
        // 走外层 catch 的聚合回退路；R167 hashtag 混合源的服务端腿同时有源内
        // 15s 双保险（HashtagSearchFeedSource），两层互为冗余
        val found = withTimeout(10_000) {
            client.send(
                TdApi.SearchMessages(
                    TdApi.ChatListMain(), query, offset, limit,
                    TdApi.SearchMessagesFilterVideo(), null, 0, 0
                )
            )
        }
        // R257b 标题回退链证据（跨频道命中按 chatId 分组建证据——mediaAlbumId 只在
        // 单频道内有归组意义）：本路径同为"过滤后建表"（服务端 Video filter），对仍
        // 空缺的做限量邻居窗口展开（链② 跨类型兄弟 + 链④ 批注帖；命中序不是连续
        // 时间线，只能在窗内做④）；每页最多展开 [GLOBAL_SEARCH_EXPAND_MAX_CHATS] 个
        // 频道，防全局命中散在太多频道时请求爆炸，其余频道本轮靠跨页缓存兜底；
        // 链③ 转发原帖每频道预算 1（同样受展开频道数闸约束）
        val hitMsgs = found.messages.filterNotNull()
        val capsByChat = hitMsgs.groupBy { it.chatId }
            .mapValues { (cid, ms) -> mergedAlbumCaptions(cid, ms) }
        val ctxByChat = java.util.concurrent.ConcurrentHashMap<Long, TitleChainContext>()
        coroutineScope {
            hitMsgs.map { it.chatId }.distinct()
                .take(GLOBAL_SEARCH_EXPAND_MAX_CHATS)
                .map { cid ->
                    async(Dispatchers.IO) {
                        val ms = hitMsgs.filter { it.chatId == cid }
                        runCatching {
                            val expanded = expandBlankCaptions(cid, ms, capsByChat[cid] ?: emptyMap())
                            val fwd = resolveForwardCaptions(
                                ms, expanded.albumCaps,
                                java.util.concurrent.atomic.AtomicInteger(1),
                            )
                            ctxByChat[cid] = TitleChainContext(
                                albumCaps = expanded.albumCaps,
                                forwardCaps = fwd,
                                adjacentNotes = expanded.notes,
                            )
                        }
                    }
                }.awaitAll()
        }
        val items = hitMsgs
            .mapNotNull { m ->
                // 展开预算外的频道：链② 用页面合并表垫底（跨页缓存兜底）
                toVideoItem(
                    m.chatId, m, null,
                    titleCtx = ctxByChat[m.chatId] ?: TitleChainContext(capsByChat[m.chatId] ?: emptyMap()),
                )
            }
        Log.d(TAG, "searchGlobal: q=$query videos=${items.size}/${found.totalCount}")
        FeedPage(items, found.nextOffset.takeIf { it.isNotEmpty() }?.let { "g:$it" })
    } catch (e: Exception) {
        Log.w(TAG, "searchGlobal 失败，回退聚合本地过滤: ${e.message}")
        // 服务端 offset 与聚合游标不兼容：非聚合格式的游标直接丢弃、从头聚合，
        // 重复条目由 FeedViewModel.loadMore 按 key 去重兜底
        val aggCursor = if (cursor == null || cursor.startsWith("g:")) null else cursor
        fetchAggregated(aggCursor, limit, query)
    }
}

/**
 * 单频道翻页（普通刷频道 + 服务端搜索失败时的回退路径）。
 * cursor = 该频道已取到的最老 messageId。
 * 注意：GetChatHistory 不支持消息类型过滤，视频由本地过滤，
 * 因此带 query 的回退模式下会多翻几页补量（rounds 上限保护）。
 */
internal suspend fun TdLibGateway.fetchFromChannel(
    chatId: Long,
    fromMessageId: Long,
    limit: Int,
    query: String?,
    maxRounds: Int = 6,
    // H1 评论级联页级总闸：聚合模式传入一页内全部频道共享的原子配额（先到先得）；
    // null = 单频道模式，退回本页本地配额 COMMENT_CASCADE_MAX_PER_PAGE
    cascadeBudget: java.util.concurrent.atomic.AtomicInteger? = null,
    // R257b 链③转发原帖批查页级预算：同 cascadeBudget 模式——null = 单频道模式
    // （本页本地配额 FORWARD_CAPTION_MAX_PER_PAGE）；聚合模式由 fetchAggregated 传入
    // 页级共享原子闸（AGGREGATE_FORWARD_BUDGET_PER_PAGE，24 频道各查会放大成请求风暴）
    forwardCaptionBudget: java.util.concurrent.atomic.AtomicInteger? = null,
    // 深链/查看上下文锚定首页专用：true 时首轮 GetChatHistory 用 offset=-1，
    // 把 from_message_id 锚点消息自身收入窗口（GetChatHistory offset=0 对锚点是
    // 排他的——平板实证：爬虫游标每页恰好步进 50×2^20、零重叠，锚点从不在窗内），
    // 否则锚定页永远没有目标消息，深链定位/合集展开全部落空。
    // 仅首轮生效；续页（from=页尾最老 id）保持排他口径，分页语义不变
    inclusiveFirst: Boolean = false,
): FeedPage {
    val collected = mutableListOf<VideoItem>()
    var from = fromMessageId
    var reachedEnd = false
    var rounds = 0
    // 评论级联计数：跨 while 轮次累计，保证一次调用（一页 FeedPage）内级联总量
    // 不超 COMMENT_CASCADE_MAX_PER_PAGE，而不是每轮 3 个（多轮补量时会放大请求数）
    var cascaded = 0
    // 形态B 跨页接续状态：首轮从进程内缓存取上一页页尾的活跃批注（文字帖 id+正文+已关联条数），
    // 之后每轮承接上一轮的页尾状态；buildAdjacentNotes 内做 id 邻接校验，
    // 刷新从头拉（fromMessageId=0，首条消息比缓存的文字帖新）时天然不命中，无需额外判空
    var carryNote: TailNote? = tailNoteStore[chatId]
    // R257b 链③页级预算（本页本地闸；聚合模式由调用方注入共享原子闸）
    val fwdBudget = forwardCaptionBudget ?: java.util.concurrent.atomic.AtomicInteger(FORWARD_CAPTION_MAX_PER_PAGE)
    while (collected.size < limit && !reachedEnd && rounds < maxRounds) {
        // H2：追更轮询这类 limit=1 的轻量探测只取最新一条，小页即可（50→5），
        // 也拉低单频道轮询的 JNI/流量税；正常翻页（limit≥20）保持 50 不变
        val pageSize = if (limit <= 1) 5 else 50
        // inclusiveFirst（深链锚定）：首轮 offset=-1 收锚点自身入窗（另捎带 1 条更新消息）；
        // 常规分页一律 offset=0 排他口径（游标续页无重叠，勿改）
        val firstOffset = if (inclusiveFirst && rounds == 0) -1 else 0
        // R45（根因 R5）：旧路 GetChatHistory 补 10s 超时（与媒体清扫路
        // fetchAggregatedMedia 的 SearchChatMessages 同口径）——代理抖动下一个
        // 挂起请求会永久占住一条爬取车道，经调度屏障放大为"整轮永不结束"
        val history = try {
            withTimeout(10_000) {
                client.send(TdApi.GetChatHistory(chatId, from, firstOffset, pageSize, false))
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "GetChatHistory 超时（10s）放弃本页防车道占死: chatId=$chatId from=$from")
            throw e
        }
        val msgs = history.messages.filterNotNull()
        if (msgs.isEmpty()) {
            reachedEnd = true
            break
        }
        // ── 资源嗅探被动钩子（DISCOVERY-GRAPH M1）──────────────────────
        // 每页消息顺手提取：forwardInfo 溯源边 + t.me 互链边。
        // 纯字段读取/正则匹配，0 额外网络请求；runCatching 全静默，
        // 任何异常都绝不允许影响主抓取链。
        // R283 幂等闸：同页重翻/回填重扫不重复贡献边权（SniffLedger 口径），
        // 页级区间记账（新→旧序：first=最新 last=最旧）
        msgs.forEach { m -> runCatching { if (sniffLedger.isNew(chatId, m.id)) sniffMessage(chatId, m) } }
        runCatching { sniffLedger.markRange(chatId, msgs.first().id, msgs.last().id) }
        // 当前频道自己也是图节点：标题只从进程内缓存取（miss 就留空等下次），
        // 不为嗅探发起任何 GetChat 请求
        channelTitleCache[chatId]?.let { t ->
            runCatching { discoveryGraph.recordNodeTitle(chatId, t) }
        }
        // ── R257b 标题回退链证据收集（每轮一段连续时间线）──────────────────
        // 链② 相册共享：同 mediaAlbumId 组内有 caption 的成员共享给空成员——经
        //   mergedAlbumCaptions 并入跨页缓存（相册被 50 条/页边界劈开、带 caption 的
        //   成员落在邻页时，本页空成员仍能补全）；
        // 链④ 紧邻文字批注："先发一批媒体，再发一条文字说明上面这堆是啥"——文字帖
        //   （数组中更靠前=更新）关联给紧随的连续空 caption 媒体（buildAdjacentNotes，
        //   最多 ANNOTATE_MAX_MEDIA 条；跨轮次/跨 FeedPage 由 carryNote + tailNoteStore
        //   接续——批注帖落在上一页时本页头部的空 caption 媒体不断关联）；
        // 链③ 转发原帖批查：走到链③的转发消息预取原帖 caption（页级预算+TTL 缓存，
        //   任何失败静默——链自动落④/⑤）；
        // 三路证据收进 TitleChainContext，toVideoItem 内 resolveTitle 按链序裁决。
        val albumCaps = mergedAlbumCaptions(chatId, msgs)
        val (adjacentNotes, tailNote) =
            buildAdjacentNotes(msgs, seedNote = carryNote, albumCapsExtra = albumCaps)
        carryNote = tailNote
        val forwardCaps = resolveForwardCaptions(msgs, albumCaps, fwdBudget)
        val titleCtx = TitleChainContext(
            albumCaps = albumCaps,
            forwardCaps = forwardCaps,
            adjacentNotes = adjacentNotes,
        )
        msgs.forEach { m ->
            toVideoItem(chatId, m, query, titleCtx = titleCtx)?.let(collected::add)
        }
        // 评论级联（重点）：不少资源频道把视频发在评论区而非主帖。
        // 只对"媒体帖且回复数>0"的帖子级联——注意 1.8.67 的 Message 没有
        // threadInfo 字段（javap 实证），回复数在 interactionInfo.replyInfo.replyCount；
        // R238 修正①：消息类型门从 MessageVideo 放宽到全媒体（isFeedMediaContent）——
        //   混合类型相册的线程载体常是首成员（图片，caption 挂它身上），旧门把它排除
        //   后整组评论媒体级联落空（用户实锤：37 条评论媒体一条没抓到）；
        //   图片帖/文档帖带讨论线程同样级联（媒体金矿口径，配额闸仍然兜底请求量）。
        // R238 修正②：同相册只级联一次（组内可能多成员带同一 replyInfo）——
        //   成员去重防同线程重复拉取。
        // 搜索回退路径（query != null）不级联：搜索语义保持不变，也避免搜索时
        // 放大请求量撞 flood wait。
        // H2：limit<=1 的轻量探测（追更轮询"取最新 1 条"）完全不做级联——
        // 轮询每个订阅频道 15 分钟一次，级联会把请求量放大 4 倍且毫无意义。
        if (includeCommentResources && query.isNullOrBlank() && limit > 1 &&
            (cascadeBudget != null || cascaded < COMMENT_CASCADE_MAX_PER_PAGE)
        ) {
            val chTitle = channelTitle(chatId)
            val cascadedAlbums = HashSet<Long>()
            // R238 结构探针（诊断相册成员 caption/线程分布，一页一次）：
            // 只记成员 id/类型缩写/回复数/caption 有无——不记文案内容（输出脱敏纪律）
            runCatching {
                msgs.groupBy { it.mediaAlbumId }.filterKeys { it != 0L }.forEach { (album, members) ->
                    Log.d(
                        TAG,
                        "相册结构 chatId=$chatId album=$album " + members.sortedBy { it.id }.joinToString(" ") {
                            "${it.id}:${it.content.javaClass.simpleName.removePrefix("Message")}:" +
                                "r${it.interactionInfo?.replyInfo?.replyCount ?: 0}:" +
                                if (mediaCaptionOf(it).isBlank()) "无cap" else "有cap"
                        },
                    )
                }
            }
            for (m in msgs) {
                if (!isFeedMediaContent(m)) continue
                val replyCount = m.interactionInfo?.replyInfo?.replyCount ?: 0
                if (replyCount <= 0) continue
                // 相册组内去重：同组线程只级联一次（载体命中即代表全组）
                if (m.mediaAlbumId != 0L && !cascadedAlbums.add(m.mediaAlbumId)) continue
                // H1 配额扣减（先确认消息够格再扣，不浪费在普通帖上）：
                // 聚合模式走页级原子总闸（getAndDecrement 可能扣成负数，无害）；
                // 单频道模式走本页本地计数
                if (cascadeBudget != null) {
                    if (cascadeBudget.getAndDecrement() <= 0) break // 本聚合页级联配额耗尽
                } else {
                    if (cascaded >= COMMENT_CASCADE_MAX_PER_PAGE) break
                }
                cascaded++
                collected += fetchCommentVideosImpl(chatId, m.id, chTitle)
            }
        }
        from = msgs.last().id
        // 注意：TDLib 返回数不足 limit 不代表到底（服务器可能少返回），只有空页才算到底
        rounds++
    }
    // 页尾批注状态写回跨页缓存：下一个 FeedPage（游标续页）头部的空 caption 媒体接续形态B；
    // 本页尾无活跃批注则清掉旧状态，防过期批注被后续拉取误关联
    if (carryNote != null) tailNoteStore[chatId] = carryNote!! else tailNoteStore.remove(chatId)
    Log.d(TAG, "fetchFromChannel: chatId=$chatId from=$fromMessageId videos=${collected.size} rounds=$rounds end=$reachedEnd")
    // 类型公平截取（修"音频永远刷不出来"）：聚合模式每频道配额只有 4~8 条，
    // 直接 take(limit) 按新→旧砍头，稀疏类型（音频/语音）排在页面靠后永远被截掉
    //（实测：原始页有 MessageAudio=3，聚合输出 types 里 2 从未出现）。
    // 按媒体类型轮转取，保证每页各类型都有代表；总量仍不超 limit。
    val page = if (collected.size <= limit) {
        collected
    } else {
        val byType = collected.groupBy { it.mediaType }
        if (byType.size <= 1) {
            collected.take(limit)
        } else {
            val out = ArrayList<VideoItem>(limit)
            val iters = byType.values.map { it.iterator() }
            var idx = 0
            while (out.size < limit) {
                val alive = iters.filter { it.hasNext() }
                if (alive.isEmpty()) break
                out.add(alive[idx % alive.size].next())
                idx++
            }
            out
        }
    }
    return FeedPage(page, if (reachedEnd) null else from.toString())
}

/**
 * 轮换采样选频道池（fetchAggregated 新轮调用，修"每次刷都是同一批频道"）：
 * 池位拆两车道——
 *
 *  - **保底车道（R75-A）**：[GUARANTEED_POOL_SLOTS] 个坑位按"最久未进池"升序
 *    从 [poolRecencyProvider] 的跨会话账面取（从未进池 = 时刻 0 排最前）。
 *    零画像长尾频道原来纯靠 0.6 兜底权重抽奖（~4.4%/轮，中位 7-8 天才进一次池），
 *    现在保底必被轮到；命中记忆的露脸频道因时刻最新天然排在队尾，与轮换惩罚不冲突；
 *  - **加权车道**：其余坑位照原逻辑从全量频道按"口味权重 × 轮换惩罚"做不放回
 *    加权随机抽样——轮换惩罚：命中 [recentPoolProvider]（VM 注入的"最近 2 轮露脸"
 *    记忆）的频道，非露脸候选够一池时本轮直接排除，不够时全量参选但权重
 *    ×[ROTATION_PENALTY] 回补——高权重频道只是轮休，隔轮即归，不是永别；
 *  - 抽样：指数赛跑（Efraimidis-Spirakis），key = -ln(u)/w 升序取前 N，
 *    等效"按权重不放回轮盘赌"，保留随机性，刷感不死板；
 *  - 零画像频道按 [EXPLORE_WEIGHT] 打底，总有基础概率进池；
 *  - 全量频道数 ≤ 池子容量时全取（轮换无意义）。
 */
internal fun TdLibGateway.sampleRotationPool(allIds: List<Long>): List<Long> {
    if (allIds.size <= MAX_AGGREGATE_CHANNELS) return allIds
    val weights = channelWeightProvider?.invoke().orEmpty()
    val recent = recentPoolProvider?.invoke().orEmpty()
    val lastPooledAt = poolRecencyProvider?.invoke().orEmpty()
    val fresh = if (recent.isEmpty()) allIds else allIds.filter { it !in recent }
    // 非露脸候选够一池 → 本轮完全排除露脸频道；不够 → 全量参选、露脸频道降权回补
    val excludeRecent = fresh.size >= MAX_AGGREGATE_CHANNELS
    val candidates = if (excludeRecent) fresh else allIds
    val nowMs = System.currentTimeMillis()
    // 保底车道：按"最久未进池"升序取队首（账面空时全员时刻 0；R90 起并列键为
    // chatId 升序而非清单原序——清单是"主列表→归档→文件夹"拼接序，原序并列会把
    // 归档/文件夹频道永久压在保底队尾；chatId 键与列表归属无关，长尾均匀散开，
    // 确定性扫表性质不变；露脸频道时刻最新，自动排最后）
    val guaranteed = candidates
        .sortedWith(compareBy({ lastPooledAt[it] ?: 0L }, { it }))
        .take(GUARANTEED_POOL_SLOTS)
        .toHashSet()
    guaranteed.forEach { id ->
        val last = lastPooledAt[id] ?: 0L
        val gap = if (last <= 0L) "从未" else "${(nowMs - last) / 86_400_000L}天"
        Log.d(TAG, "保底入池：频道尾号${id.toString().takeLast(6)} 距上次 $gap")
    }
    val weighted = candidates
        .asSequence()
        .filter { it !in guaranteed }
        .map { id ->
            // 下限 0.01 防御：指数赛跑要求权重严格为正（口味画像下限 0.1，双保险）
            var w = (weights[id] ?: EXPLORE_WEIGHT).coerceAtLeast(0.01)
            if (!excludeRecent && id in recent) w *= ROTATION_PENALTY
            // 指数赛跑：-ln(u)/w，key 越小越先进池；u 兜底防 ln(0)
            id to (-ln(Random.nextDouble().coerceAtLeast(1e-12)) / w)
        }
        .sortedBy { it.second }
        .take(MAX_AGGREGATE_CHANNELS - guaranteed.size)
        .map { it.first }
        .toList()
    Log.d(
        TAG,
        "轮换采样：保底=${guaranteed.size} 加权=${weighted.size} 候选=${candidates.size}/${allIds.size} " +
            "账面从未进池=${allIds.count { (lastPooledAt[it] ?: 0L) <= 0L }}",
    )
    return guaranteed.toList() + weighted
}

/**
 * 聚合模式：多频道并发取页 + 混排。
 * cursor 编码为 "chatId:msgId|chatId:msgId"，msgId = -1 表示该频道已到底。
 * 并发上限 5，每频道每轮最多翻 2 页，避免代理下单线程爬行导致长时间无响应。
 *
 * 频道选择为轮换采样（[sampleRotationPool]）：新轮（cursor==null，refresh/首刷）
 * 重抽一池并回报 [poolReporter]；轮内翻页复用本轮池子（currentPool）。
 * 换池对游标天然兼容：游标按 chatId 键控——新进池频道无记录缺省从 0（最新）开始；
 * 轮休频道的位置留在游标 map 里不被触碰，隔轮回来时从原位置续拉，不丢不重。
 *
 * R75-B：每页各频道配额合拉量（~100-150 条）远大于页长 limit（20），截下部分
 * 不再丢弃，暂存轮内余量缓冲（见 [aggregateLeftoverLock] 字段注释），轮内翻页
 * 先吃余量再补拉——低频频道进池后其内容不再"展示前被丢 80%"。
 */
internal suspend fun TdLibGateway.fetchAggregated(cursor: String?, limit: Int, query: String?): FeedPage {
    val allIds = listChannels().map { it.id }
    if (allIds.isEmpty()) {
        Log.w(TAG, "fetchAggregated: 没有可用频道")
        return FeedPage(emptyList(), null)
    }

    // R75-B 轮内余量消费（仅主聚合 Feed）：上一页配额合拉超出页长的部分先顶上，
    // 同一游标链（tag 匹配）才认领；游标对不上/新轮则作废回退旧版现拉。
    // 余量供给满一页时本次零网络请求直接返回（游标原样回传，链不断）
    var prefix: List<VideoItem> = emptyList()
    if (query == null) {
        if (cursor == null) {
            val discarded = synchronized(aggregateLeftoverLock) {
                val n = aggregateLeftovers.size
                aggregateLeftovers = emptyList()
                aggregateLeftoverTag = null
                n
            }
            if (discarded > 0) {
                Log.d(TAG, "fetchAggregated: 新轮重抽，上轮余量 $discarded 条作废")
            }
        } else {
            val claimed = synchronized(aggregateLeftoverLock) {
                if (aggregateLeftoverTag == cursor && aggregateLeftovers.isNotEmpty()) {
                    val s = aggregateLeftovers.take(limit)
                    aggregateLeftovers = aggregateLeftovers.drop(limit)
                    if (aggregateLeftovers.isEmpty()) aggregateLeftoverTag = null
                    s
                } else {
                    if (aggregateLeftovers.isNotEmpty()) {
                        Log.d(
                            TAG,
                            "fetchAggregated: 游标链对不上，余量 ${aggregateLeftovers.size} 条作废现拉",
                        )
                        aggregateLeftovers = emptyList()
                        aggregateLeftoverTag = null
                    }
                    emptyList()
                }
            }
            if (claimed.size >= limit) {
                Log.d(TAG, "fetchAggregated: 余量供给整页 $limit 条（本次零网络请求）")
                return FeedPage(claimed, cursor)
            }
            prefix = claimed
        }
    }

    // 轮内翻页复用本轮池；新轮（或进程重启丢了轮内记忆但游标还在）按新轮重抽——
    // 游标按 chatId 键控，池子换了游标语义也不破（新进池频道从 0 开始）
    val prevPool = currentPool
    val chatIds = if (cursor != null && prevPool.isNotEmpty()) {
        prevPool
    } else {
        sampleRotationPool(allIds).also {
            currentPool = it
            // 只有主聚合 Feed（无搜索词）记"最近露脸"；搜索回退（query != null）的
            // 临时换池不污染轮换记忆——搜索不是用户感知的一轮刷新
            if (query == null) poolReporter?.invoke(it.toSet())
        }
    }
    // 轮换观测：相对上一轮池子的新进/轮休计数（轮内翻页恒为 0）
    val newInPool = chatIds.count { it !in prevPool }
    val rested = prevPool.count { it !in chatIds }

    val cursors = decodeCursor(cursor).toMutableMap()
    val sem = Semaphore(CONCURRENCY)
    // v2 拉取配额倾斜：base × (weight / avgWeight)，clamp 在 [QUOTA_MIN, QUOTA_MAX_FACTOR×base]。
    // 权重表由 FeedViewModel 注入（watch_events 现算的时间衰减权重）；
    // 空表时所有频道同为 EXPLORE_WEIGHT，w/avg = 1，自然退化为 v1 均匀配额
    val baseQuota = maxOf(4, limit / chatIds.size)
    val weights = channelWeightProvider?.invoke().orEmpty()
    val avgWeight = chatIds.map { weights[it] ?: EXPLORE_WEIGHT }.average().coerceAtLeast(0.01)
    fun quotaFor(chatId: Long): Int {
        val w = weights[chatId] ?: EXPLORE_WEIGHT
        return (baseQuota * w / avgWeight).roundToInt().coerceIn(QUOTA_MIN, baseQuota * QUOTA_MAX_FACTOR)
    }

    // H1 评论级联聚合页级总闸：本页所有频道共享这一个配额（原实现每频道各 3 次，
    // 24 频道最坏 72 次 GetMessageThreadHistory/页）；并发安全靠 AtomicInteger 原子扣减
    val cascadeBudget = java.util.concurrent.atomic.AtomicInteger(AGGREGATE_CASCADE_BUDGET_PER_PAGE)
    val results = coroutineScope {
        chatIds.map { chatId ->
            async(Dispatchers.IO) {
                val from = cursors[chatId] ?: 0L
                if (from < 0) return@async chatId to FeedPage(emptyList(), "-1")
                val page = sem.withPermit {
                    // R439 取消传播（同刀 GatewayBotSearch:719-720）：协程取消必须上抛——
                    // async 扇出靠 coroutineScope 结构化并发连坐取消，awaitAll/整页靠它停止；
                    // 超时（TimeoutCancellationException 是 CancellationException 子类，
                    // fetchFromChannel 内 withTimeout 抛出）按普通失败落空页——超时≠取消。
                    try {
                        fetchFromChannel(chatId, from, quotaFor(chatId), query, maxRounds = 4, cascadeBudget = cascadeBudget)
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        Log.w(TAG, "fetchAggregated: chatId=$chatId 失败: ${e.message}")
                        FeedPage(emptyList(), from.toString())
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e // 协程取消不是失败，必须上抛
                    } catch (e: Throwable) {
                        Log.w(TAG, "fetchAggregated: chatId=$chatId 失败: ${e.message}")
                        FeedPage(emptyList(), from.toString())
                    }
                }
                chatId to page
            }
        }.awaitAll()
    }

    val items = mutableListOf<VideoItem>()
    results.forEach { (chatId, page) ->
        items += page.items
        cursors[chatId] = page.nextCursor?.toLongOrNull() ?: -1L
    }
    items.shuffle() // 混排，避免同一频道扎堆

    // R75-C 进页条数回报（拉取/入池口径）：本页各频道实际贡献条数记进频道曝光账；
    // 只有主聚合 Feed 报，搜索回退的临时池不污染账面
    if (query == null) {
        val counts = HashMap<Long, Int>()
        results.forEach { (chatId, page) ->
            if (page.items.isNotEmpty()) counts[chatId] = page.items.size
        }
        runCatching { poolItemReporter?.invoke(counts) }
    }

    val allEnded = chatIds.all { (cursors[it] ?: 0L) < 0L }
    // R75-B：余量前缀 + 本页新拉合并后再按页长截断；截下部分暂存轮内余量缓冲
    //（游标已前进，余量正是"已拉未展示"的货），下游翻页先吃余量——拉多少用多少。
    // 全到底但余量未空时必须仍返回非空游标，让余量有机会随后续翻页 drain 完
    val merged = prefix + items
    val pageItems = merged.take(limit)
    val leftover = merged.drop(limit)
    val nextCursor = if (allEnded && leftover.isEmpty()) null else encodeCursor(cursors)
    if (query == null) {
        synchronized(aggregateLeftoverLock) {
            aggregateLeftovers = leftover
            aggregateLeftoverTag = if (leftover.isEmpty() || nextCursor == null) null else nextCursor
        }
    }
    // 类型分布诊断：音频 tab 空问题的实锤（0=视频 1=图片 2=音频）
    val byType = items.groupingBy { it.mediaType }.eachCount()
    Log.d(
        TAG,
        "fetchAggregated: channels=${chatIds.size}/${allIds.size} 新进池=$newInPool 轮休=$rested " +
            "videos=${items.size} allEnded=$allEnded types=$byType " +
            "页内频道数=${pageItems.mapTo(HashSet()) { it.chatId }.size} " +
            "余量供给=${prefix.size} 余量回池=${if (query == null) leftover.size else 0} 丢弃=0",
    )
    return FeedPage(pageItems, nextCursor)
}

/** 取消息的 t.me 公开链接（私有频道/无权限返回 null） */
internal suspend fun TdLibGateway.getMessageLinkImpl(item: VideoItem): String? = runCatching {
    awaitAuthorized()
    client.send(TdApi.GetMessageLink(item.chatId, item.messageId, 0, 0, "", false, false)).link
}.getOrNull()

/**
 * 消息 → Feed 条目。支持三种媒体：MessageVideo / MessagePhoto / MessageAudio。
 *
 * 相册展开（javap 实证修正任务假设）：本 AAR（TDLib 1.8.67）不存在 MessageAlbum
 * 内容类（javap 报"找不到类"，jar 里只有 SendMessageAlbum 等函数类）——相册的
 * 每个条目在 GetChatHistory 里本来就是独立 Message，靠 Message.mediaAlbumId
 * 归组（字段已 javap 实证存在）。旧实现"多视频一条消息只出一条"的真实成因：
 * 旧 toVideoItem 只认 MessageVideo，混合相册里的 MessagePhoto/MessageAudio
 * 被整条丢弃；且 MessagePhoto.video（图片附带视频，javap 实证字段）也被丢弃。
 * 现在三分支全覆盖，相册逐条展开，key 用各子消息自己的 id（天然唯一）。
 *
 * @param fromComment true = 评论级联产物（caption 加 💬 前缀）
 * @param titleOverride 评论级联时传入主帖频道标题（回复消息的 chatId 是讨论组 id，
 *                      直接查标题会显示成讨论组名）
 */
internal suspend fun TdLibGateway.toVideoItem(
    chatId: Long,
    msg: TdApi.Message,
    query: String?,
    fromComment: Boolean = false,
    titleOverride: String? = null,
    // R257b 标题回退链证据包：链②相册共享/③转发原帖/④紧邻批注的外部证据——
    // null = 无外部证据（链只剩①自身 caption 与各分支自身数据回落），
    // 与旧 captionOverride=null 行为一致
    titleCtx: TitleChainContext? = null,
): VideoItem? {
    val title = titleOverride ?: channelTitle(chatId)
    // R238 v10：评论线程回复数（interactionInfo.replyInfo.replyCount，javap 实证）
    // ——相册成员只在携带线程的成员上非 0，UI 按相册组聚合（VideoBottomInfo 取 max）
    val replyCount = msg.interactionInfo?.replyInfo?.replyCount ?: 0
    // R257b：统一回退链裁决标题（①自身→②相册→③转发原帖→④紧邻批注→⑤null）。
    // 旧 withOverride(own) 的"自身 caption 优先"被链①完整吸收；链返回 null 时
    // 各分支回落自身数据（文件名/占位）——诚实，绝不冒充标题
    fun chained(): String = resolveTitle(msg, titleCtx).orEmpty()
    // 评论级联条目统一 💬 前缀，空 caption 给占位文案，便于 UI 一眼识别来源
    fun decorate(caption: String): String =
        if (fromComment) (if (caption.isBlank()) "💬 评论区资源" else "💬 $caption") else caption
    return when (val content = msg.content) {
        is TdApi.MessageVideo -> {
            // —— 原有视频逻辑：语义一行未改（仅 caption 走回退链、标题走 title）——
            val v = content.video
            val caption = chained()
            // 本地复滤换简繁折叠匹配（SEARCH-QUALITY §4）：简体查询不再误杀繁体 caption
            if (!query.isNullOrBlank() && !SearchTextKit.foldsContains(caption, query)) return null
            val file = v.video
            // TDLib 偶发把目录路径标记为已完成（EISDIR 崩溃踩过坑），必须是真实文件才直接用
            val localPath = file.local.path
            val playable = file.local.isDownloadingCompleted &&
                localPath.isNotEmpty() &&
                runCatching { java.io.File(localPath).isFile }.getOrDefault(false)
            // 清晰度档位：主档 + 备选分辨率。
            // 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
            //   alternativeVideos 挂在 MessageVideo 上（不是 Video——任务假设的
            //   Video{alternativeVideos: Video[]} 在本 AAR 不存在，javap 实证 Video 无此字段），
            //   类型是 AlternativeVideo[]：
            //   AlternativeVideo{id: long, width: int, height: int, codec: String,
            //                    hlsFile: File, video: File}（无 mimeType，沿用主档的）
            // 去重按 height（主档在前，同高保留主档），再按 height 降序供 UI 直接展示
            val mime = v.mimeType ?: "video/mp4"
            val variants = buildList {
                add(VideoVariant(v.height, file.id, file.size, mime))
                content.alternativeVideos?.forEach { alt ->
                    val af = alt.video
                    if (af != null && alt.height > 0) {
                        add(VideoVariant(alt.height, af.id, af.size, mime))
                    }
                }
            }.distinctBy { it.height }.sortedByDescending { it.height }
            VideoItem(
                key = "tg:$chatId:${msg.id}",
                chatId = chatId,
                messageId = msg.id,
                channelTitle = title,
                caption = decorate(caption),
                durationSec = v.duration,
                width = v.width,
                height = v.height,
                fileId = file.id,
                sizeBytes = file.size,
                // 本地路径含 '#'/'?' 时经流服务器供流（playableUriOrStream 注释有实锤案例）
                playUri = if (playable) playableUriOrStream(localPath, file.id) else null,
                coverBytes = v.minithumbnail?.data,
                // 高清封面 fileId（Video.thumbnail.file）：需要时经 loadCoverBytes 下载；
                // R64 铸造登记（mintCoverFileId）：会话验身防跨进程错图
                coverFileId = mintCoverFileId(v.thumbnail?.file, "tg:$chatId:${msg.id}"),
                dateEpochSec = msg.date, // TDLib Message.date：消息发布时间的 Unix 秒
                // v2 文件级去重：远端文件唯一 id，跨频道转发同片同 id。
                // 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
                // RemoteFile{id: String, uniqueId: String, ...}（JNI 侧理论上可空，兜底空串）
                fileUniqueId = file.remote.uniqueId ?: "",
                mediaType = 0,
                fromComment = fromComment,
                // 图集归组 id（Message.mediaAlbumId 已 javap 实证：public long mediaAlbumId）
                albumId = msg.mediaAlbumId,
                variants = variants,
                commentCount = replyCount,
            )
        }
        is TdApi.MessagePhoto -> {
            val attached = content.video
            if (attached != null) {
                // 图片附带视频（javap 实证 MessagePhoto.video 可空，1.8.45+ 的"图+视频"消息）：
                // 按视频出；一条消息只产一个条目（同 msg.id 只有一个 key，不会重复）
                val v = attached
                val caption = chained()
                if (!query.isNullOrBlank() && !SearchTextKit.foldsContains(caption, query)) return null
                val file = v.video
                VideoItem(
                    key = "tg:$chatId:${msg.id}",
                    chatId = chatId,
                    messageId = msg.id,
                    channelTitle = title,
                    caption = decorate(caption),
                    durationSec = v.duration,
                    width = v.width,
                    height = v.height,
                    fileId = file.id,
                    sizeBytes = file.size,
                    playUri = completedLocalPath(file)?.let { playableUriOrStream(it, file.id) },
                    coverBytes = v.minithumbnail?.data ?: content.photo.minithumbnail?.data,
                    dateEpochSec = msg.date,
                    fileUniqueId = file.remote.uniqueId ?: "",
                    mediaType = 0,
                    fromComment = fromComment,
                    albumId = msg.mediaAlbumId, // 图集归组 id（javap 实证）
                    // 注意：MessagePhoto.video 的类型是 Video，而 alternativeVideos 只在
                    // MessageVideo 上（javap 实证），附图视频天然无备选档，variants 留默认空表
                    commentCount = replyCount,
                )
            } else {
                // 纯图片（mediaType=1）：coverBytes 取 minithumbnail 小图；
                // playUri 取 sizes 最大档的 File——已下载完成才给本地路径，
                // 否则 null（复用 ensurePlayable 的 DownloadFile 下载链，与视频同链）
                val caption = chained()
                if (!query.isNullOrBlank() && !SearchTextKit.foldsContains(caption, query)) return null
                // PhotoSize{type: String, photo: File, width, height}（javap 实证）；取最大档
                val best = content.photo.sizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
                    ?: return null
                val file = best.photo
                VideoItem(
                    key = "tg:$chatId:${msg.id}",
                    chatId = chatId,
                    messageId = msg.id,
                    channelTitle = title,
                    caption = decorate(caption),
                    durationSec = 0,
                    width = best.width,
                    height = best.height,
                    // fileId 保持最大档原图：仅供下载保存/分享链（saveToGallery 等）使用
                    fileId = file.id,
                    sizeBytes = file.size,
                    playUri = completedLocalPath(file)?.let { playableUriOrStream(it, file.id) },
                    // TG 频道图片统一 JPEG；PhotoSize 无 mime 字段（javap 实证）
                    mimeType = "image/jpeg",
                    coverBytes = content.photo.minithumbnail?.data,
                    // W6：封面档（"m"≈320px，pickPhotoCoverSize 与消息反解链同一口径）补登进
                    // coverFileId——图片条目不再恒 -1，发现页预览/搜索预览弹层/观看历史的
                    // 图片封面链由此直通 loadCoverBytes。注意：coverFileId 是会话级 id，
                    // 落盘（收藏 JSON/深度索引）后跨进程重启会失效——R64 起由 loadCoverBytes
                    // 内置铸造验身闸识别非本会话 id 并自动转消息反解，弃权语义不会变错图
                    coverFileId = mintCoverFileId(pickPhotoCoverSize(content.photo.sizes)?.photo, "tg:$chatId:${msg.id}"),
                    dateEpochSec = msg.date,
                    fileUniqueId = file.remote.uniqueId ?: "",
                    mediaType = 1,
                    fromComment = fromComment,
                    albumId = msg.mediaAlbumId, // 图集归组 id：图集卡主要靠它归组图片
                    // W1：浏览档（"y"≈1280px / "x"≈800px）——全屏浏览与分屏格解码专用，
                    // 单图流量从原图 2~8MB 降到 0.2~0.8MB，加载耗时降一个量级
                    photoViewFileId = pickPhotoViewSize(content.photo.sizes)?.photo?.id ?: -1,
                    commentCount = replyCount,
                )
            }
        }
        // 动态图/GIF（MessageAnimation）：TG 的"动图"——之前无此分支，整条消息被丢弃。
        // javap 实证（core-release.aar，TDLib 1.8.67）：
        //   MessageAnimation{animation: Animation, caption: FormattedText,
        //                    showCaptionAboveMedia: boolean, hasSpoiler: boolean, isSecret: boolean}
        //   Animation{duration: int, width: int, height: int, fileName: String, mimeType: String,
        //             hasStickers: boolean, minithumbnail: Minithumbnail, thumbnail: Thumbnail,
        //             animation: File}
        // 按视频出（mediaType=0）：TG 客户端把 GIF 统一转成 mp4 动画文件，ExoPlayer 直接可播
        //（满足"播放器能播 GIF"），且复用视频链的下载/流式/预载/弹幕，UI 侧零适配；
        // 若按图片（mediaType=1）出，PhotoCard 的 BitmapFactory 只解首帧，动图变死图，故不取。
        is TdApi.MessageAnimation -> {
            val a = content.animation
            val caption = chained()
            // 搜索本地复滤与视频分支同口径（简繁折叠匹配，防简体查询误杀繁体 caption）
            if (!query.isNullOrBlank() && !SearchTextKit.foldsContains(caption, query)) return null
            val file = a.animation
            VideoItem(
                key = "tg:$chatId:${msg.id}",
                chatId = chatId,
                messageId = msg.id,
                channelTitle = title,
                caption = decorate(caption),
                durationSec = a.duration,
                width = a.width,
                height = a.height,
                fileId = file.id,
                sizeBytes = file.size,
                // EISDIR 防御与图片/音频分支同链（completedLocalPath 内含 isFile 校验）
                playUri = completedLocalPath(file)?.let { playableUriOrStream(it, file.id) },
                // Animation.mimeType（javap 实证 String 字段）；TG GIF 多为 video/mp4，空值兜底 mp4
                mimeType = a.mimeType?.ifBlank { "video/mp4" } ?: "video/mp4",
                coverBytes = a.minithumbnail?.data,
                // 高清封面与视频分支同一加载链（loadCoverBytes；
                // Thumbnail{file: File} 已 javap 实证）；R64 铸造登记防跨进程错图
                coverFileId = mintCoverFileId(a.thumbnail?.file, "tg:$chatId:${msg.id}"),
                dateEpochSec = msg.date,
                fileUniqueId = file.remote.uniqueId ?: "",
                mediaType = 0, // 动图按视频归类：进视频 tab 与"全部"，走播放器
                fromComment = fromComment,
                albumId = msg.mediaAlbumId,
                // Animation 无 alternativeVideos 概念（javap 实证 Animation 字段表无此字段），
                // variants 留默认空表
                commentCount = replyCount,
            )
        }
        is TdApi.MessageAudio -> {
            // 音频（mediaType=2）：caption 按任务约定取 标题/歌手 拼接，
            // 皆空退回消息文本，再空退回文件名
            val a = content.audio
            // 链①前的自身数据前置：音频文件的标题/歌手元数据先于 caption 链
            //（旧实现同序）；链①~④兜底，仍空才回落文件名
            val base = listOf(a.title.orEmpty(), a.performer.orEmpty())
                .filter { it.isNotBlank() }
                .joinToString(" - ")
                // 相册共享/转发原帖/批注关联 caption 也覆盖音频分支（音乐相册常见）：
                // 元数据为空时走回退链，仍空才回落文件名
                .ifBlank { chained() }
                .ifBlank { a.fileName.orEmpty() }
            // 搜索过滤同时看拼接 caption 和消息原文，避免"标题没命中但正文命中"被误杀；
            // 折叠匹配（简繁/全角归一）防简体查询误杀繁体标题（SEARCH-QUALITY §4）
            if (!query.isNullOrBlank() &&
                !SearchTextKit.foldsContains(base, query) &&
                !SearchTextKit.foldsContains(content.caption.text, query)
            ) return null
            val file = a.audio
            VideoItem(
                key = "tg:$chatId:${msg.id}",
                chatId = chatId,
                messageId = msg.id,
                channelTitle = title,
                caption = decorate(base),
                durationSec = a.duration,
                width = 0,
                height = 0,
                fileId = file.id,
                sizeBytes = file.size,
                playUri = completedLocalPath(file)?.let { playableUriOrStream(it, file.id) },
                // Audio.mimeType 已 javap 实证存在；空值兜底 audio/mpeg
                mimeType = a.mimeType?.ifBlank { "audio/mpeg" } ?: "audio/mpeg",
                // 专辑封面小图（javap 实证 Audio.albumCoverMinithumbnail: Minithumbnail）
                coverBytes = a.albumCoverMinithumbnail?.data,
                // v6 音频卡重做：结构化标题/歌手（Audio.title/performer，javap 实证 String 字段），
                // 免去 UI 侧从拼接 caption 里再拆 " - " 的脆弱解析
                audioTitle = a.title.orEmpty(),
                audioPerformer = a.performer.orEmpty(),
                // 专辑封面高清图 fileId（javap 实证 Audio.albumCoverThumbnail: Thumbnail{file: File}），
                // 需要时经 loadAudioCoverBytes 下载
                audioCoverFileId = mintCoverFileId(a.albumCoverThumbnail?.file, "tg:$chatId:${msg.id}"),
                dateEpochSec = msg.date,
                fileUniqueId = file.remote.uniqueId ?: "",
                mediaType = 2,
                fromComment = fromComment,
                albumId = msg.mediaAlbumId, // 图集归组 id（javap 实证）
                commentCount = replyCount,
            )
        }
        // 语音消息（MessageVoiceNote）：TG 的"音频"有三种形态，之前只认 MessageAudio，
        // 语音条和当文件发的音频（MessageDocument audio/*）全被丢了——音频 tab 空的根因
        // （实测聚合类型分布 types={0=34,1=55} 一条音频没有）。
        is TdApi.MessageVoiceNote -> {
            val vn = content.voiceNote
            // 链①~④兜底，仍空才用占位文案（语音条极少入相册，双保险而已）
            val base = chained().ifBlank { "语音消息 ${vn.duration}s" }
            if (!query.isNullOrBlank() && !SearchTextKit.foldsContains(base, query)) return null
            val file = vn.voice
            VideoItem(
                key = "tg:$chatId:${msg.id}",
                chatId = chatId,
                messageId = msg.id,
                channelTitle = title,
                caption = decorate(base),
                durationSec = vn.duration,
                width = 0,
                height = 0,
                fileId = file.id,
                sizeBytes = file.size,
                playUri = completedLocalPath(file)?.let { playableUriOrStream(it, file.id) },
                mimeType = vn.mimeType?.ifBlank { "audio/ogg" } ?: "audio/ogg",
                coverBytes = null, // 语音无封面
                // v6 音频卡重做：语音波形（javap 实证 VoiceNote.waveform: byte[]，
                // 每字节高/低 5bit 各一个 0..31 幅度采样），UI 侧画波形柱并随播放进度染色
                waveBytes = vn.waveform,
                dateEpochSec = msg.date,
                fileUniqueId = file.remote.uniqueId ?: "",
                mediaType = 2,
                fromComment = fromComment,
                albumId = msg.mediaAlbumId,
                commentCount = replyCount,
            )
        }
        is TdApi.MessageDocument -> {
            // R238 form 10（文档伪装视频）：mime 以 video/ 开头的文档（.mp4 以"文件"
            // 形式发）按视频收录——此前只认 audio/*，视频文档整条丢弃（caption 也丢）。
            // Document 分支的 duration/尺寸无来源（文档不带流元数据）：0 起播后由
            // ExoPlayer 实测补正（与 DownloadRecord 本地文件回放同口径）。
            // 非媒体文档（zip/rar 等）仍不收录（不可播）。
            val doc = content.document
            val mime = doc.mimeType?.ifBlank { "" } ?: ""
            val docIsVideo = mime.startsWith("video/")
            if (!mime.startsWith("audio/") && !docIsVideo) return null // 非媒体文档不收录
            // 链①~④兜底，仍空才回落文件名（当文件发的音频/视频也能入相册）
            val base = chained().ifBlank { doc.fileName.orEmpty() }
            if (!query.isNullOrBlank() &&
                !SearchTextKit.foldsContains(base, query)
            ) return null
            val file = doc.document
            VideoItem(
                key = "tg:$chatId:${msg.id}",
                chatId = chatId,
                messageId = msg.id,
                channelTitle = title,
                caption = decorate(base),
                durationSec = 0,
                width = 0,
                height = 0,
                fileId = file.id,
                sizeBytes = file.size,
                playUri = completedLocalPath(file)?.let { playableUriOrStream(it, file.id) },
                mimeType = mime,
                coverBytes = doc.minithumbnail?.data,
                dateEpochSec = msg.date,
                fileUniqueId = file.remote.uniqueId ?: "",
                // video/* 文档按视频出（R238 form 10）；audio/* 保持音频归类
                mediaType = if (docIsVideo) 0 else 2,
                fromComment = fromComment,
                albumId = msg.mediaAlbumId,
                commentCount = replyCount,
            )
        }
        else -> null
    }
}

/**
 * 媒体消息自身 caption 文本（无 caption 的消息类型归空串）。
 * 字段均 javap 实证（core-release.aar，TDLib 1.8.67）：
 * MessageVideo/Photo/Audio/Animation/VoiceNote/Document.caption: FormattedText
 */
internal fun TdLibGateway.mediaCaptionOf(m: TdApi.Message): String = when (val c = m.content) {
    is TdApi.MessageVideo -> c.caption.text
    is TdApi.MessagePhoto -> c.caption.text
    is TdApi.MessageAudio -> c.caption.text
    is TdApi.MessageAnimation -> c.caption.text
    is TdApi.MessageVoiceNote -> c.caption.text
    is TdApi.MessageDocument -> c.caption.text
    else -> ""
}

/**
 * 相册共享 caption 表（fetchFromChannel 形态A 的同款语义，抽成成员函数供全路径复用）：
 * 同一相册（mediaAlbumId≠0，javap 实证字段）内任一成员有 caption，
 * 共享给全组 caption 为空的成员。返回 albumId → 共享 caption（先到先得）。
 * 用途：相册的 caption 只挂在一条成员上，其余成员 caption 为空——空成员经
 * toVideoItem(captionOverride=…) 补全后，播放页信息区不再只剩频道名。
 * 注意：mediaAlbumId 只在单频道内有归组意义，跨频道消息列表须先按 chatId 分组再调用。
 */
internal fun TdLibGateway.albumSharedCaptions(msgs: List<TdApi.Message>): Map<Long, String> {
    val out = HashMap<Long, String>()
    for (m in msgs) {
        if (m.mediaAlbumId == 0L) continue
        val cap = mediaCaptionOf(m)
        if (cap.isNotBlank()) out.putIfAbsent(m.mediaAlbumId, cap)
    }
    return out
}

/**
 * 形态A 跨页缓存合并（分页路径共用）：把本段消息新见的相册共享 caption 写回
 * [albumCaptionStore]（写回后超 [ALBUM_CAPTION_CACHE_MAX] 按插入序掐最老），
 * 返回"缓存垫底 + 本段覆盖"的合并表——本段 caption 优先（caption 被编辑过时新值赢）。
 * 相册被分页/类型过滤劈开、带 caption 的成员不在本段时，空成员仍能从缓存补全。
 * 零额外网络请求，搜索/媒体 tab/Feed 路径见过即留痕、互相受益。
 */
internal fun TdLibGateway.mergedAlbumCaptions(chatId: Long, msgs: List<TdApi.Message>): Map<Long, String> {
    val fresh = albumSharedCaptions(msgs)
    val store = albumCaptionStore.getOrPut(chatId) { LinkedHashMap() }
    synchronized(store) {
        val merged = LinkedHashMap<Long, String>(store.size + fresh.size)
        merged.putAll(store) // 缓存垫底（邻页/异类型成员见过的共享 caption）
        merged.putAll(fresh) // 本段新见覆盖
        store.putAll(fresh)
        while (store.size > ALBUM_CAPTION_CACHE_MAX) {
            val eldest = store.entries.firstOrNull()?.key ?: break
            store.remove(eldest)
        }
        return merged
    }
}

/** 消息是否是 toVideoItem 支持产出的媒体内容（与 mediaCaptionOf 的类型覆盖面一致） */
internal fun TdLibGateway.isFeedMediaContent(m: TdApi.Message): Boolean = when (m.content) {
    is TdApi.MessageVideo, is TdApi.MessagePhoto, is TdApi.MessageAnimation,
    is TdApi.MessageAudio, is TdApi.MessageVoiceNote, is TdApi.MessageDocument -> true
    else -> false
}

// ---------------- R257b 标题回退链（架构师方法论修正：不枚举形态，只追"数据在哪"） ----------------

/**
 * R257b 标题回退链证据包：链环②③④的外部证据容器。
 * 网关各抓取路径（时间线/搜索/媒体 tab/评论级联）按手头证据填充——缺哪环就空哪环，
 * 链自动跳过（诚实落到下一环/频道名），无需为不同路径枚举消息形态。
 *
 *  - [albumCaps] 链②：相册共享表 mediaAlbumId → caption（[mergedAlbumCaptions] 的
 *    跨页合并视图，或各展开路径的窗口终值）；
 *  - [forwardCaps] 链③：转发原帖表 转发消息 id → 原帖 caption（页级批查预取，
 *    见 [resolveForwardCaptions]——链③需要网络，证据必须先落在包里）；
 *  - [adjacentNotes] 链④：紧邻文字批注表 媒体消息 id → 批注正文
 *    （[buildAdjacentNotes] 产出，ping/推广守卫在产出侧）。
 */
internal class TitleChainContext(
    val albumCaps: Map<Long, String> = emptyMap(),
    val forwardCaps: Map<Long, String> = emptyMap(),
    val adjacentNotes: Map<Long, String> = emptyMap(),
)

/**
 * R257b 统一标题回退链（[toVideoItem] 及全提取路径的唯一标题裁决口）：
 *
 * 1. 这条消息自身有 caption？→ 用
 * 2. 所属相册的任何成员有 caption？→ 用（相册共享）
 * 3. 是转发？原始消息有 caption？→ 用
 * 4. 紧邻文本消息是描述性文字（非🔔/非推广）？→ 用
 * 5. 都没有？→ null（调用方显示频道名/文件名等自身数据——诚实）
 *
 * 方法论（用户质疑"枚举 10 种形态能枚举完吗"的裁决替代）：每一步只问
 * **数据在哪**、不问消息是什么形态——转发+相册+跨页+搜索过滤等任何组合
 * 无需枚举，链自然按优先级走到底（R219 形态A>形态B 优先级被链序 ②>④ 吸收）。
 *
 * 纯函数：链③的原帖查询需要网络，由页级批查（[resolveForwardCaptions]）预先
 * 落在 [ctx] 里，本函数绝不发起任何请求；null ctx = 无外部证据（链只剩①），
 * 与旧 withOverride(own) 的 captionOverride=null 行为完全一致。
 * 返回 null = 四环皆空：调用方回落各分支自身数据（文件名/占位）或频道名——
 * 诚实显示"不知道"，绝不冒充标题。
 */
internal fun TdLibGateway.resolveTitle(msg: TdApi.Message, ctx: TitleChainContext?): String? {
    // ① 自身 caption（数据就在消息身上，不需要证据包）
    mediaCaptionOf(msg).takeIf { it.isNotBlank() }?.let { return it }
    if (ctx == null) return null // 无外部证据：链只剩①（旧无 override 行为）
    // ② 所属相册任何成员的 caption（相册共享；跨页/跨类型合并视图由调用侧供给）
    if (msg.mediaAlbumId != 0L) {
        ctx.albumCaps[msg.mediaAlbumId]?.takeIf { it.isNotBlank() }?.let { return it }
    }
    // ③ 转发：原始消息有 caption？（仅频道 origin 可定位原帖坐标——
    // MessageOriginChannel{chatId, messageId} javap 实证；用户/隐藏 origin 无从取原帖。
    // 原帖证据由页级批查预落在 ctx，取不到就诚实落④）
    if (msg.forwardInfo?.origin is TdApi.MessageOriginChannel) {
        ctx.forwardCaps[msg.id]?.takeIf { it.isNotBlank() }?.let { return it }
    }
    // ④ 紧邻文本消息是描述性文字？（非🔔/非推广守卫在 buildAdjacentNotes 产出侧）
    ctx.adjacentNotes[msg.id]?.takeIf { it.isNotBlank() }?.let { return it }
    // ⑤ 四环皆空：null → 调用方诚实回落（频道名/文件名/占位）
    return null
}

/** R257b 链③缓存条目：原帖 caption（空串=负缓存——失败/原帖无 caption 同记账）+ 落账时刻 */
internal class ForwardCapEntry(val text: String, val at: Long)

/**
 * R257b 链环③页级批查：走到链③的转发消息（自身 caption 空 + 相册无共享 + 频道 origin）
 * 逐条取原帖 caption，供 [TitleChainContext.forwardCaps] 消费。
 *
 * 请求量收敛（链③是回退链里唯一要发网络请求的环）：
 *  - [budget] 页级原子闸先到先得（单频道页 [FORWARD_CAPTION_MAX_PER_PAGE]；
 *    聚合页共享 [AGGREGATE_FORWARD_BUDGET_PER_PAGE]——24 频道各查会放大成请求风暴，
 *    与评论级联 H1 同款页级预算模式）；
 *  - [forwardCaptionStore] TTL 缓存按原帖坐标去重：同一原帖跨页/跨路径只查一次，
 *    失败与"原帖无 caption"同为负缓存（TTL 内不重试）；
 *  - 任何失败静默 null——链自动落④/⑤，绝不影响主抓取。
 */
internal suspend fun TdLibGateway.resolveForwardCaptions(
    msgs: List<TdApi.Message>,
    albumCaps: Map<Long, String>,
    budget: java.util.concurrent.atomic.AtomicInteger,
): Map<Long, String> {
    // 走到链③的候选：自身 caption 空 + 相册无共享（链②不中）+ 频道 origin 可定位
    val candidates = msgs.filter { m ->
        mediaCaptionOf(m).isBlank() &&
            (m.mediaAlbumId == 0L || !albumCaps.containsKey(m.mediaAlbumId)) &&
            (m.forwardInfo?.origin as? TdApi.MessageOriginChannel) != null
    }
    if (candidates.isEmpty()) return emptyMap()
    val out = HashMap<Long, String>()
    val now = System.currentTimeMillis()
    for (m in candidates) {
        val origin = m.forwardInfo!!.origin as TdApi.MessageOriginChannel
        val key = "${origin.chatId}:${origin.messageId}"
        // TTL 缓存命中（含负缓存）：免费不占预算；过期作废重查
        val cached = forwardCaptionStore[key]
        if (cached != null) {
            if (now - cached.at < FORWARD_CAPTION_TTL_MS) {
                if (cached.text.isNotEmpty()) out[m.id] = cached.text
                continue
            }
            forwardCaptionStore.remove(key)
        }
        // 预算先到先得（耗尽即止：链落④/⑤，不重试不等待）
        if (budget.getAndDecrement() <= 0) break
        val cap = runCatching {
            // GetMessage 直取原帖（10s 超时防车道占死——R45 家族口径；未入库的
            // 原频道 TDLib 会报错，按链语义静默落空负缓存）
            val original = withTimeout(10_000) {
                client.send(TdApi.GetMessage(origin.chatId, origin.messageId))
            }
            // R283 缺口④：原帖消息整体在手（既有请求零新增）——顺手过嗅探：
            // 源频道正文/caption 的 t.me 互链边 + 原帖自身 forwardInfo（转发的转发
            // =上游链延长）。散点过闸：TTL 过期重查同一原帖不重复贡献边权
            runCatching {
                if (sniffLedger.isNew(origin.chatId, origin.messageId)) {
                    sniffLedger.markMessage(origin.chatId, origin.messageId)
                    sniffMessage(origin.chatId, original)
                }
            }
            mediaCaptionOf(original).takeIf { it.isNotBlank() }
        }.onFailure {
            Log.w(TAG, "链③转发原帖caption解析失败（静默）origin=$key: ${it.message}")
        }.getOrNull()
        // 成功/失败都落账（失败=空串负缓存，TTL 内同原帖不重查）
        forwardCaptionStore[key] = ForwardCapEntry(cap ?: "", now)
        if (cap != null) out[m.id] = cap
    }
    // 缓存容量治理：清过期仍超容则全清重建（markExpandedOnce 同款口径，缓存重建便宜）
    if (forwardCaptionStore.size > FORWARD_CAPTION_CACHE_MAX) {
        val it = forwardCaptionStore.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.at >= FORWARD_CAPTION_TTL_MS) it.remove()
        }
        if (forwardCaptionStore.size > FORWARD_CAPTION_CACHE_MAX) forwardCaptionStore.clear()
    }
    return out
}

/**
 * 链环④证据建表（原 buildCaptionOverrides 的 R257b 回退链化重命名）：
 * "先发一批媒体（caption 空），再发一条文字说明"——文字帖（数组中更靠前=更新）
 * 关联给紧随的连续空 caption 媒体，产出 messageId → 批注正文，供
 * [TitleChainContext.adjacentNotes] 消费（[resolveTitle] 链④按链序取用——
 * ②相册共享优先于④由链序天然保证，本函数不再产出②的共享填充）。
 *
 *  - 输入须是一段连续时间线（GetChatHistory / GetMessageThreadHistory 的新→旧序页；
 *    搜索命中集等非连续结果集不能用本助手，只能用 mergedAlbumCaptions 的链②）；
 *  - ping 帖守卫（R219）：纯提醒帖（无字母数字正文的 🔔/🔥/分割线）不进批注位；
 *  - 相册隔断（R219）：链②命中的成员（相册有共享 caption）清批注态——带自身
 *    caption 的相册是完整内容单元，批注不得穿透过界串到上一批内容。判定用
 *    [albumCapsExtra] 垫底 + 本段新见的合并表（与调用方喂给链②的表同源）；
 *  - 跨页接续：[seedNote] 仅当与本段首条消息 id 相邻（差 1..ANNOTATE_MAX_MEDIA+1，
 *    频道消息 id 连续）才作数——刷新从头拉/换频道时首条消息比文字帖新，天然不命中；
 *  - 计数上界：一条文字帖最多关联 [ANNOTATE_MAX_MEDIA] 条，遇下一条文字帖重起批。
 *
 * 返回 Pair(messageId → 批注正文, 本段结束时的活跃批注状态)；
 * 页尾状态由调用方喂给下一段/写回 [tailNoteStore]，无活跃批注时为 null。
 */
internal fun TdLibGateway.buildAdjacentNotes(
    msgs: List<TdApi.Message>,
    seedNote: TailNote? = null,
    albumCapsExtra: Map<Long, String> = emptyMap(),
): Pair<Map<Long, String>, TailNote?> {
    // R285：判定核抽共享纯函数 [computeAdjacentNotes]（ChainNoteKernel.kt）——
    // 本网关路径（TdApi.Message 映射：MessageText→text；其余→mediaCaptionOf）与
    // DB 存量链式回填（items 行视图）同一实现，防两份语义漂移。本路径保持非严格
    // 模式（窗内消息连续可见，无需相邻闸），行为与抽核前逐项一致
    // （CaptionOverridePriorityTest 锚定；026dae3 的 seed 序号域邻接修复随委托
    // 沉入内核——两路径同享）。
    val rows = msgs.map { m ->
        val c = m.content
        if (c is TdApi.MessageText) {
            ChainNoteRow(m.id, m.mediaAlbumId, text = c.text.text, caption = "")
        } else {
            ChainNoteRow(m.id, m.mediaAlbumId, text = null, caption = mediaCaptionOf(m))
        }
    }
    return computeAdjacentNotes(rows, seedNote, albumCapsExtra)
}

/**
 * R257b expandBlankCaptions 产出：标题回退链②④证据终值——
 * [albumCaps] 链②相册表（页面段 + 全部展开窗写回跨页缓存后的合并视图）+
 * [notes] 链④批注表（展开窗内"相册批+紧随文字说明帖"的批注关联，按成员 id 键）。
 */
internal class BlankExpandResult(val albumCaps: Map<Long, String>, val notes: Map<Long, String>)

/**
 * 媒体 tab 空 caption 限量邻居展开（fetchAggregatedMedia / fetchAggregatedMediaAll /
 * searchInChannel / searchGlobal 共用）：
 * 按类型过滤的 SearchChatMessages 页里永远没有文字帖、混排相册的异类型成员也不在场
 * （"过滤后建表"缺陷：带 caption 的图片成员被 Video 过滤掉，视频成员永远补不到）——
 * 对本页补全后仍空 caption 的媒体发**未过滤**邻居窗口查询，先整窗建表再按成员应用：
 *  1. 链② 跨类型：窗口内同组兄弟（含被过滤掉的异类型成员）建共享 caption 表，
 *     顺手写回跨页缓存；
 *  2. 链④：窗口是连续时间线，"相册批 + 紧随文字说明帖"形态下文字帖在窗内即关联
 *    （旧实现相册锚点只做②，文字帖就在眼前却被丢掉——实测漏 caption 的主因之一）；
 *  3. 窗口多用途：一个窗口覆盖窗内**所有**本页空缺成员（资源频道连发多批，
 *     窗口常跨多个相册/批，不浪费请求）。
 * 链序裁决（②>④、重叠窗口不打架）由调用方 toVideoItem 内 resolveTitle 完成——
 * 本函数只产出证据，不做优先级裁决。
 * 请求量收敛：每频道每页最多 [MEDIA_TAB_EXPAND_MAX] 个锚点（②④合计，先到先得）；
 * [markExpandedOnce] TTL 内同一对象只展开一次；任何失败静默，只丢这次增量。
 */
internal suspend fun TdLibGateway.expandBlankCaptions(
    chatId: Long,
    pageMsgs: List<TdApi.Message>,
    albumCaps: Map<Long, String>,
): BlankExpandResult {
    // 仍缺 caption 的媒体：自身空 + 相册共享表也没补上
    val blank = pageMsgs.filter { m ->
        isFeedMediaContent(m) && mediaCaptionOf(m).isBlank() &&
            (m.mediaAlbumId == 0L || !albumCaps.containsKey(m.mediaAlbumId))
    }
    if (blank.isEmpty()) return BlankExpandResult(albumCaps, emptyMap())
    // R439 同 chat 展开航班（并发窗区间幂等）：媒体 tab / 全局搜索 / 媒体全量窗可对
    // 同一 chat 并发调用本函数，锚点重叠时两班并发拉同一批邻居窗（GetChatHistory×2，
    // 浪费且时序不齐）。后到者不另起航班，直接 await 进行中航班的结果——notes 键是
    // chat 域 message id（超集无害，消费方只查自己关心的 id），albumCaps 按自己页面段
    // 重算（航班窗已写回跨页缓存，天然并入）。代价：后到者本轮**独有**的新锚点不另飞
    //（本函数本就"只产证据、任何失败只丢增量"，锚点未登记 TTL 不过期，下轮自然补上）。
    // 死班（占舱协程被取消）不连坐后到者：清舱后重试自飞；双参 remove 防误清他班。
    while (true) {
        val inFlight = expandBlankCaptionsInFlight[chatId]
        if (inFlight != null) {
            try {
                val r = inFlight.await()
                return BlankExpandResult(mergedAlbumCaptions(chatId, pageMsgs), r.notes)
            } catch (e: kotlinx.coroutines.CancellationException) {
                currentCoroutineContext().ensureActive() // 自己也被取消→此处如实上抛
                expandBlankCaptionsInFlight.remove(chatId, inFlight)
                continue
            }
        }
        val joined = coroutineScope {
            // LAZY：占舱成功才起跑——输了竞速就取消一个从未起跑的航班（零请求浪费）
            val myFlight = async(context = Dispatchers.IO, start = CoroutineStart.LAZY) {
                expandBlankCaptionsAnchored(chatId, pageMsgs, albumCaps, blank)
            }
            val winner = expandBlankCaptionsInFlight.putIfAbsent(chatId, myFlight)
            if (winner == null) {
                try {
                    myFlight.await()
                } finally {
                    expandBlankCaptionsInFlight.remove(chatId, myFlight)
                }
            } else {
                myFlight.cancel()
                try {
                    winner.await()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    currentCoroutineContext().ensureActive()
                    expandBlankCaptionsInFlight.remove(chatId, winner)
                    null // 死班已清，外层重试
                }
            }
        }
        if (joined != null) return joined
    }
}

/**
 * R439 从 [expandBlankCaptions] 拆出的独飞体（锚点选择 + 窗口扇出）：由航班表占舱
 * 成功的调用方经 LAZY async 起跑；占舱协程被取消时由占舱方 finally 清舱，等结果的
 * 后到者收到 CancellationException 自飞（不连坐）。
 */
private suspend fun TdLibGateway.expandBlankCaptionsAnchored(
    chatId: Long,
    pageMsgs: List<TdApi.Message>,
    albumCaps: Map<Long, String>,
    blank: List<TdApi.Message>,
): BlankExpandResult {
    // 选锚点：相册按 albumId 去重（一个相册一次窗口查询），散媒体各自为锚；先到先得
    val anchorAlbums = HashSet<Long>()
    val anchors = ArrayList<Long>()
    for (m in blank) {
        if (anchors.size >= MEDIA_TAB_EXPAND_MAX) break
        if (m.mediaAlbumId != 0L) {
            if (!anchorAlbums.add(m.mediaAlbumId)) continue
            if (!markExpandedOnce("ma:$chatId:${m.mediaAlbumId}")) continue
        } else {
            if (!markExpandedOnce("mb:$chatId:${m.id}")) continue
        }
        anchors += m.id
    }
    if (anchors.isEmpty()) return BlankExpandResult(albumCaps, emptyMap())
    val out = java.util.concurrent.ConcurrentHashMap<Long, String>()
    // R439 取消传播（同刀 GatewayBotSearch:719-720）：协程取消必须上抛——async
    // 扇出的兄弟任务靠 coroutineScope 结构化并发连坐取消，父调用链靠它整体停止。
    // 超时（TimeoutCancellationException 是 CancellationException 子类）按普通失败
    // 落锚点级回落（丢该锚增量，不影响兄弟锚点）——超时≠取消，要区分。
    coroutineScope {
        anchors.map { anchorId ->
            async(Dispatchers.IO) {
                try {
                    // 窗口：锚点 + 8 更新 + 7 更老（未过滤整窗——服务端类型过滤只作用于
                    // 主搜索响应，caption 建表必须基于未过滤窗口）；更的一侧覆盖形态B
                    // 文字帖（比媒体新），同窗覆盖相册兄弟（含异类型成员）
                    val around = withTimeout(10_000) {
                        client.send(TdApi.GetChatHistory(chatId, anchorId, -8, 16, false))
                    }
                    val window = around.messages.filterNotNull()
                    // 链②（写回跨页缓存）+ 链④ 批注建表，应用到窗内所有本页空缺成员
                    //（②的链序裁决在 resolveTitle——本处只产证据，重叠窗口不打架）
                    val caps = mergedAlbumCaptions(chatId, window)
                    val notes = buildAdjacentNotes(window, albumCapsExtra = caps).first
                    for (m in blank) {
                        notes[m.id]?.let { out.putIfAbsent(m.id, it) }
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    // 超时=该锚点普通失败：静默丢这次增量（锚点级回落，不连坐兄弟锚点）
                    Log.w(TAG, "媒体tab空caption展开超时 chatId=$chatId anchor=$anchorId")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e // 协程取消不是失败，必须上抛（扇出兄弟+父链靠它停止）
                } catch (e: Throwable) {
                    Log.w(TAG, "媒体tab空caption展开失败 chatId=$chatId anchor=$anchorId: ${e.message}")
                }
            }
        }.awaitAll()
    }
    // 链②终值：页面段 + 全部展开窗写回缓存后的合并视图（窗口新见的共享 caption 也进链②）
    val finalAlbumCaps = mergedAlbumCaptions(chatId, pageMsgs)
    return BlankExpandResult(finalAlbumCaps, out)
}

/**
 * 展示期回填查询（播放页"caption 晚到"补救链的网关端）：相册成员上屏时 caption 仍空
 * （残余缺口：链②带 caption 的成员落在还没拉到的更旧的页、媒体 tab 锚点预算外等），
 * 由播放页 LaunchedEffect 调用，拿到后经 FeedViewModel.patchCaption 回填 _items。
 *  1. 先查跨页缓存 [albumCaptionStore]（各抓取路径见过即留痕，命中零请求）；
 *  2. miss → 以正在展示的条目自身（[anchorMessageId]）为锚拉一次**未过滤**窗口
 *     （-8/+16 足够覆盖 ≤10 成员的相册 + 紧随批注帖），整窗收集回退链②③④证据
 *     （链② 跨类型兄弟 + 链④ 批注帖 + 链③ 转发原帖，与 expandBlankCaptions 同链），
 *     锚点消息经 [resolveTitle] 按链序裁决；
 *     窗口顺带写回跨页缓存（同组其他成员之后上屏直接命中，不再发请求）。
 * 请求去重：窗口查询经 [markExpandedOnce]（"peek:" 前缀）TTL 内同相册只查一次——
 * 重组/滑走滑回重复触发不会重复打请求；缓存查找永远放行（免费）。
 * 任何失败（未授权/超时/无权限）静默返回 null——回填是增量收益，绝不影响播放。
 */
internal suspend fun TdLibGateway.peekAlbumCaptionImpl(chatId: Long, albumId: Long, anchorMessageId: Long): String? {
    if (albumId == 0L || anchorMessageId == 0L) return null
    // 1) 跨页缓存命中零请求（synchronized 与 mergedAlbumCaptions 的写侧同一把锁）
    albumCaptionStore[chatId]?.let { store ->
        synchronized(store) { store[albumId] }?.let { return it }
    }
    // 2) miss → 限量窗口查询（TTL 去重：同相册 10 分钟最多一次窗口请求）
    if (!markExpandedOnce("peek:$chatId:$albumId")) return null
    return runCatching {
        awaitAuthorized()
        val around = withTimeout(10_000) {
            client.send(TdApi.GetChatHistory(chatId, anchorMessageId, -8, 16, false))
        }
        val window = around.messages.filterNotNull()
        // R257b：整窗收集链②③④证据，锚点消息过 resolveTitle 按链序裁决；
        // 锚点自身 caption 非空时链①直返（不覆盖）→ null，调用方本来也不覆盖非空 caption
        val anchorMsg = window.firstOrNull { it.id == anchorMessageId } ?: return@runCatching null
        val caps = mergedAlbumCaptions(chatId, window)
        val notes = buildAdjacentNotes(window, albumCapsExtra = caps).first
        val forwardCaps = resolveForwardCaptions(window, caps,
            java.util.concurrent.atomic.AtomicInteger(1))
        resolveTitle(anchorMsg, TitleChainContext(caps, forwardCaps, notes))
    }.onFailure {
        Log.w(TAG, "peekAlbumCaption 失败（静默）chatId=$chatId album=$albumId: ${it.message}")
    }.getOrNull()
}

/**
 * 已下载完成且是真实文件的本地路径，否则 null。
 * 复用视频分支的 EISDIR 教训：TDLib 偶发把目录路径标记为已完成，必须 isFile 校验。
 * （视频主分支保留原始内联写法不动语义；图片/音频/附图视频等新分支统一走这里）
 */
internal fun TdLibGateway.completedLocalPath(file: TdApi.File): String? {
    val p = file.local.path
    return if (file.local.isDownloadingCompleted && p.isNotEmpty() &&
        runCatching { java.io.File(p).isFile }.getOrDefault(false)
    ) p else null
}

/**
 * 可播地址收口（修"# 文件名播不了"，深链实测实锤）：本地路径含 '#'/'?' 时
 * 不能直接进 ExoPlayer MediaItem.setUri(String)——Uri.parse 会把 '#' 后段
 * 当 fragment 截掉，FileDataSource 打开截剩下的目录段报 EISDIR
 *（实测：`…/tdlib/files/videos/#猴歌 ….mp4`，path 被截成 videos/ 目录，
 * 自动重试 4 次全撞同一面墙——重试换路①②都拿回同一个本地路径）。
 * 这类文件改回流媒体服务器按 fileId 供流（TgStreamServer 用 RandomAccessFile
 * 直读真实路径，不过 Uri 解析，天然免疫）；正常路径保持裸路径直出——
 * PhotoCard decodeFile / CoverExtractor / ChannelHomeScreen 封面等
 * 按本地文件处理的消费方语义完全不变。
 */
internal fun TdLibGateway.playableUriOrStream(localPath: String, fileId: Int): String =
    if (localPath.contains('#') || localPath.contains('?')) {
        if (fileId >= 0) {
            streamServer.url(fileId)
        } else {
            // R80：无远端 fileId 的本地条目（下载页等）走不了流服务器（按 id 取不到），
            // 退化为 file:// 编码直出——Uri.fromFile 把 '#'/'?' 百分号编码进路径段，
            // FileDataSource 用 getPath()（自动解码还原真实路径）打开，天然免疫截断
            android.net.Uri.fromFile(java.io.File(localPath)).toString()
        }
    } else {
        localPath
    }

/**
 * 评论级联：拉取帖子讨论线程的最新回复，把其中的 MessageVideo 产出为条目
 * （fromComment=true，caption 带 💬 前缀）。
 * flood wait / 频道关评论 / 无权限等任何失败都静默跳过（返回空表）——
 * 级联是增量收益，绝不能影响主 feed。
 *
 * 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
 * GetMessageThreadHistory(long chatId, long messageId, long fromMessageId, int offset, int limit)
 *   -> Messages{totalCount: int, messages: Message[]}
 *   —— 注意鉴别：1.8.67 只有这个 5 参版本，没有任务假设的 6 参（带 boolean）重载
 */
internal suspend fun TdLibGateway.fetchCommentVideosImpl(
    chatId: Long,
    messageId: Long,
    chTitle: String,
    // R238：拉取条数可调——级联路沿用既有 COMMENT_CASCADE_REPLY_LIMIT；评论流
    // （点开角标进评论媒体播放）是用户显式动作，调用方可放宽到 50（一次线程拉取成本可承受）
    limit: Int = COMMENT_CASCADE_REPLY_LIMIT,
): List<VideoItem> =
    runCatching {
        // R212 补 10s 超时（R45 家族，与 fetchFromChannel 的 GetChatHistory 同数值同语义）：
        // 评论级联默认开（includeCommentResources），本调用在频道主页/聚合的每页路径上；
        // 原生管线拥塞时裸 send 可挂数分钟（实锤：频道主页首屏 loading 卡真），超时按
        // 失败走外层 catch 的静默跳过（级联是增量收益，绝不影响主 feed）
        val history = withTimeout(10_000) {
            client.send(
                TdApi.GetMessageThreadHistory(chatId, messageId, 0L, 0, limit.coerceIn(1, 100))
            )
        }
        // 评论区也能发相册：caption 只挂一条成员上（线程内消息同讨论组，组 id 不跨群），
        // 防评论级联的相册条目在播放页只剩频道名——链②证据见下方回退链段
        val replies = history.messages.filterNotNull()
        // ── 评论区边（P1-b，DISCOVER-UPGRADE #6 / BRAINSTORM-GRAPH B2+B5）──────────
        // 爬评论顺手建"主帖频道 → 评论里出现的频道"边（EDGE_COMMENT），0 新增请求：
        //  1) 评论正文/按钮/caption 里的 t.me 链接（"求出处"指路现场，命中即高精度）；
        //  2) 以频道身份发言的 MessageSenderChat（频道运营者互踩场子；只采频道身份，
        //     不采普通用户——隐私边界口径见 BRAINSTORM-GRAPH B5）。
        // 注意回复的 chatId 是讨论组 id 不是频道 id——边的 from 用主帖频道 chatId；
        // runCatching 全静默，嗅探绝不允许影响评论抓取主链。
        // R283 缺口②③⑤：评论区 forwardInfo 整类漏收（"求出处"应答直接转源频道帖
        // =最强指路证据，归 comment 族不冒充 forward_src）；caption 三类手写 when 换
        // 六类共享件；线程回复按散点过 SniffLedger 闸（同线程重拉不重复贡献边权——
        // 散点不并区间：讨论组 id 空间内邻线程消息未过手，并区间会谎报覆盖=漏边）。
        replies.forEach { reply ->
            runCatching {
                if (!sniffLedger.isNew(reply.chatId, reply.id)) return@runCatching
                sniffLedger.markMessage(reply.chatId, reply.id)
                // 评论里转发的消息：origin 指向源频道 → 主帖频道→源频道 comment 边
                (reply.forwardInfo?.origin as? TdApi.MessageOriginChannel)?.let { origin ->
                    discoveryGraph.recordEdge(chatId, origin.chatId, DiscoveryGraph.EDGE_COMMENT)
                }
                (reply.senderId as? TdApi.MessageSenderChat)?.chatId?.let { senderChatId ->
                    if (senderChatId != chatId && senderChatId != reply.chatId) {
                        discoveryGraph.recordEdge(chatId, senderChatId, DiscoveryGraph.EDGE_COMMENT)
                    }
                }
                val commentLinks = ArrayList<String>()
                commentLinks += extractTmeLinks(reply)
                commentLinks += sniffCaptionLinksOf(reply)
                for (link in commentLinks) sniffLink(chatId, link, DiscoveryGraph.EDGE_COMMENT)
            }
        }
        // R257b：评论区标题回退链证据——链② 相册共享（评论区也能发相册：caption
        // 只挂一条成员上，共享给空成员，组 id 不跨群）+ 链③ 转发原帖批查
        // （评论里的转发媒体同链补全；预算+TTL 缓存，静默）
        val albumCaps = albumSharedCaptions(replies)
        val forwardCaps = resolveForwardCaptions(
            replies, albumCaps,
            java.util.concurrent.atomic.AtomicInteger(FORWARD_CAPTION_MAX_PER_PAGE),
        )
        val titleCtx = TitleChainContext(albumCaps = albumCaps, forwardCaps = forwardCaps)
        // R238：评论区媒体金矿——产出门从"只认 MessageVideo"放宽为全媒体
        // （toVideoItem 自然丢弃文字/非媒体回复，mediaType 各归各类：视频/图片/
        // 音频都能进 feed，用户口径"37 条评论全是媒体资源"）。级联路同一放宽
        // （媒体随类型进各自 tab，配额闸仍然兜底请求量）。
        replies
            .mapNotNull { reply ->
                // 注意：回复消息的 chatId 是关联讨论组的 id（不是频道 id），
                // 后续 GetMessage/转发/取链接都必须用它，否则 messageId 对不上；
                // 频道标题沿用主帖所在频道（用户感知上资源属于该频道）
                toVideoItem(reply.chatId, reply, null, fromComment = true, titleOverride = chTitle,
                    titleCtx = titleCtx)
            }
    }.onFailure {
        Log.w(TAG, "评论级联失败（静默跳过）chatId=$chatId msg=$messageId: ${it.message}")
    }.getOrDefault(emptyList())

// ---------------- 聚合游标编解码 ----------------

internal fun TdLibGateway.encodeCursor(map: Map<Long, Long>): String =
    map.entries.joinToString("|") { "${it.key}:${it.value}" }

internal fun TdLibGateway.decodeCursor(s: String?): Map<Long, Long> {
    if (s.isNullOrBlank()) return emptyMap()
    return s.split('|').mapNotNull { part ->
        val kv = part.split(':')
        val k = kv.getOrNull(0)?.toLongOrNull()
        val v = kv.getOrNull(1)?.toLongOrNull()
        if (k != null && v != null) k to v else null
    }.toMap()
}

/** 全局搜索（searchGlobal）邻居展开：每页最多对多少个频道发窗口请求。
 *  全局命中集跨频道（一页可能散在十几二十个频道），逐频道展开会请求爆炸；
 *  超出的频道本轮靠跨页缓存兜底，缓存随各路径抓取自然累积 */
private const val GLOBAL_SEARCH_EXPAND_MAX_CHATS = 3
