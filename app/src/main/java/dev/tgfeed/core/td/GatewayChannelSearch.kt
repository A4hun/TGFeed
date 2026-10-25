package dev.tgfeed.core.td

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi

/**
 * 深链/查看上下文的锚定首页：含锚点消息自身的单频道拉取。
 * 与 fetchVideos(channelId 路径) 的差别只在首轮窗口口径（inclusiveFirst，见
 * fetchFromChannel 参数注释）：GetChatHistory offset=0 排他、offset=-1 收锚点入窗。
 * 翻页游标口径与 fetchFromChannel 完全一致（排他续页、无重叠）。
 */
internal suspend fun TdLibGateway.fetchChannelPageAnchoredImpl(chatId: Long, anchorMessageId: Long, limit: Int): FeedPage =
    fetchFromChannel(chatId, anchorMessageId, limit, null, inclusiveFirst = true)

/**
 * 深链合集展开：t.me/<name>/<msgId> 链接落在相册（mediaAlbumId≠0）的某一个成员上，
 * 锚定首拉（GetChatHistory offset=0）只含"不新于锚点"的消息——锚点是组内靠前成员时，
 * 比它新的兄弟成员整批缺失（实测 t.me/wwtjz/12191 合集只出了一条）。
 * 本方法以锚点为中心窗口拉取，把同 mediaAlbumId 的全部兄弟成员出成可播条目，
 * 调用方并入首页后用户在 Feed 里能滑完整个合集。
 *
 * 口径复用搜索相册展开（searchChannelAll 段）：窗口 GetChatHistory + albumId 过滤 +
 * 共享 caption 补全。窗口取 (-10, 21) 而非搜索的 (-8, 16)：TG 相册上限 10 个成员，
 * 锚点落在组内任意位置时 ±10 必全覆盖（搜索侧 (-8,16) 是"相关性优先、允许漏"的
 * 便宜口径；深链是用户显式点进合集，完整性优先）。
 * 与搜索侧的差别：不做"只展开空 caption 成员"的收窄——同一 mediaAlbumId 就是同一个
 * 合集（搜索侧收窄是防"一条命中拉进不相关批次"的相关性保护；深链语义是"看这个合集"）。
 *
 * 签名已 javap 实证（同 searchChannelAll 展开段）：GetChatHistory(long chatId,
 * long fromMessageId, int offset, int limit, boolean onlyLocal) -> Messages；
 * Message.mediaAlbumId: long（0=非相册）。
 * 失败（网络/权限/超时）返回空表，调用方按"无兄弟成员"降级，不影响锚点页本身。
 */
internal suspend fun TdLibGateway.expandAlbumMembersImpl(
    chatId: Long,
    albumId: Long,
    anchorMessageId: Long,
): List<VideoItem> {
    if (albumId == 0L) return emptyList()
    return runCatching {
        awaitAuthorized()
        val around = withTimeout(10_000) {
            client.send(TdApi.GetChatHistory(chatId, anchorMessageId, -10, 21, false))
        }
        val siblings = around.messages.filterNotNull()
            .filter { it.mediaAlbumId == albumId }
        // 相册共享 caption 补全（修"合集成员播放页只剩频道名"，与搜索展开同款）：
        // caption 只挂在组内一条成员上，经 mergedAlbumCaptions 建共享表（顺手写回
        // 跨页缓存）；R257b 回退链：共享表进链②，resolveTitle 链①自身 caption 优先
        // 天然吸收旧 withOverride「只填空白」语义
        val sharedCap = mergedAlbumCaptions(chatId, siblings)[albumId]
        val titleCtx = TitleChainContext(albumCaps = sharedCap?.let { mapOf(albumId to it) } ?: emptyMap())
        siblings.mapNotNull { m ->
            toVideoItem(chatId, m, null, titleCtx = titleCtx)
        }
    }.onFailure {
        Log.w(TAG, "深链合集展开失败 chatId=$chatId album=$albumId anchor=$anchorMessageId: ${it.message}")
    }.getOrDefault(emptyList())
}

// ---------------- M1 全量搜索：单频道全量分页原语（SEARCH-ARCH §2.1 L1a） ----------------

/**
 * 展开去重登记：TTL 内已展开过返回 false（调用方跳过）；否则登记时间戳返回 true。
 * 表超 [SEARCH_EXPAND_CACHE_MAX] 时先清过期项，仍超则全清重建（缓存重建便宜，幂等）。
 * 过期重展开存在并发窗口（两线程同时通过）——重复展开一次无害（putIfAbsent 归并）。
 */
internal fun TdLibGateway.markExpandedOnce(key: String): Boolean {
    val now = System.currentTimeMillis()
    val prev = searchExpandCache.putIfAbsent(key, now)
    if (prev != null) {
        if (now - prev < SEARCH_EXPAND_DEDUP_TTL_MS) return false // TTL 内已展开过
        searchExpandCache[key] = now // 过期：刷新时间戳，本轮重新展开
    }
    if (searchExpandCache.size > SEARCH_EXPAND_CACHE_MAX) {
        val it = searchExpandCache.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value >= SEARCH_EXPAND_DEDUP_TTL_MS) it.remove()
        }
        if (searchExpandCache.size > SEARCH_EXPAND_CACHE_MAX) searchExpandCache.clear()
    }
    return true
}

/**
 * 单频道全量搜索（M1 主动脉的原语）：SearchChatMessages 是 TDLib 唯一承诺
 * 单频道全量分页的搜索 API；N 个频道由调用方（FeedViewModel.fullSearch）扇出。
 *
 * 媒体口径（SEARCH-ARCH §2.1"媒体覆盖修正"）：filter 传 null 不过滤消息类型——
 * 视频/图集/音频一次请求全覆盖（比 Photo/Video/Audio 三个 filter 各发一次省 2/3 请求量），
 * 媒体提取交给现成 [toVideoItem] 链（query 传 null：匹配由服务端完成，本地不再二次过滤）。
 *
 * 双模式（SEARCH-ARCH §3，M1 先落在网关层）：query 以 # 或全角 ＃ 开头时，
 * 服务端对带 # 的词按话题实体精确匹配（窄而准）；本方法自动补发一次去 # 的
 * 全文版（宽而杂），两路结果按 key 合并去重（先命中者保留 = 话题精确语义优先）。
 *
 * 分页：FoundChatMessages.nextFromMessageId 为续页游标（0 = 到底），与现有
 * searchInChannel 同一口径；FeedPage.nextCursor = 游标字符串，null = 到底。
 * 双模式下两路游标取"较新"的那个（max）续页：取较老（min）会漏掉另一路尚未覆盖的
 * 区间，取较新只是让已覆盖区间重搜一遍，重复由 key 去重兜底，宁重不漏。
 *
 * 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证（同 searchInChannel 注释）：
 * SearchChatMessages(long, MessageTopic, String, MessageSender, long fromMessageId, int, int, SearchMessagesFilter)
 *   -> FoundChatMessages{totalCount, messages, nextFromMessageId}
 */
internal suspend fun TdLibGateway.searchChannelAllImpl(chatId: Long, query: String, cursor: String? = null, limit: Int = 50): FeedPage {
    awaitAuthorized()
    val q = query.trim()
    require(q.isNotEmpty()) { "搜索关键词为空" }
    val from = cursor?.toLongOrNull() ?: 0L
    // 主查询：原样透传（含 # 前缀 = 话题实体精确匹配；无 # = 全文分词匹配）
    // 单频道 10s 超时：一个频道挂起不能拖死整个扇出（实测"已搜 0/166"卡死的根因）
    val found = withTimeout(10_000) {
        client.send(
            TdApi.SearchChatMessages(chatId, null, q, null, from, 0, limit, null)
        )
    }
    val items = LinkedHashMap<String, VideoItem>()
    // 相册共享 caption（形态A）：服务端命中可能落在 caption 为空的相册成员上
    //（宽松分词/话题实体命中兄弟成员），先在命中页内建共享表，空成员补全；
    // 并入跨页缓存（mergedAlbumCaptions）：带 caption 的成员落在邻页/异类型时也能补
    val hitAlbumCaps = mergedAlbumCaptions(chatId, found.messages.filterNotNull())
    found.messages.filterNotNull().forEach { m ->
        toVideoItem(chatId, m, null, titleCtx = TitleChainContext(albumCaps = hitAlbumCaps))?.let { v ->
            // 打点：服务端为什么返回这条（命中词在不在 caption 里）
            val inCaption = v.caption.contains(q.trimStart('#', '＃'), ignoreCase = true)
            Log.d(TAG, "searchHit: chatId=$chatId q=$q inCaption=$inCaption cap=${v.caption.take(40)}")
            items.putIfAbsent(v.key, v)
        }
    }
    var nextFrom = found.nextFromMessageId
    // 双模式：# 开头补发一次全文版（去 # 前缀），合并去重
    if (q.startsWith("#") || q.startsWith("＃")) {
        val textQuery = q.trimStart('#', '＃').trim()
        if (textQuery.isNotEmpty()) {
            runCatching {
                withTimeout(10_000) {
                    client.send(TdApi.SearchChatMessages(chatId, null, textQuery, null, from, 0, limit, null))
                }
            }.onSuccess { extra ->
                // 全文腿也建相册共享表（形态A，并入跨页缓存）：空 caption 的相册成员
                // 先补共享文案，再参与下方的本地复滤——共享文案命中的也算真命中
                val extraMsgs = extra.messages.filterNotNull()
                val extraAlbumCaps = mergedAlbumCaptions(chatId, extraMsgs)
                extraMsgs.forEach { m ->
                    // 全文腿本地复滤（修"# 搜索全是垃圾数据"）：TG 全文分词匹配很松，
                    // 不含原词的宽泛命中是噪音——caption 不含该词的直接丢，话题腿不受影响。
                    // 折叠匹配（SEARCH-QUALITY §4）：简体查询不再误杀繁体 caption 的真命中
                    toVideoItem(chatId, m, null, titleCtx = TitleChainContext(albumCaps = extraAlbumCaps))?.let { v ->
                        if (SearchTextKit.foldsContains(v.caption, textQuery)) {
                            items.putIfAbsent(v.key, v)
                        }
                    }
                }
                // 游标取两路中较新的非零值（语义见方法头注释）
                nextFrom = listOf(nextFrom, extra.nextFromMessageId).filter { it != 0L }.maxOrNull() ?: 0L
            }.onFailure {
                // 全文版失败只丢这一路，话题版结果照常返回（增量收益绝不影响主路）
                Log.w(TAG, "searchChannelAll 全文版失败（保留话题版结果）chatId=$chatId q=$q: ${it.message}")
            }
        }
    }
    // 相册展开（修"搜索搜不全"）：相册的 caption 只挂在一条成员上，
    // 其他成员 caption 空——服务端按 caption 搜永远漏掉它们。
    // 命中消息带 mediaAlbumId 时，把同组其他成员的媒体也展开进来（共享 caption 语义）。
    //
    // SEARCH-PERF §2 请求量收敛（原实现每页最坏 ~20 相册 + ~20 文字帖 = 40 次串行
    // GetChatHistory，单频道搜索被拖到 10s+，是"有结果不能立即显示"的主因）：
    //  1. 限量：每页最多展开 [SEARCH_ALBUM_EXPAND_MAX] 个相册 + [SEARCH_TEXT_EXPAND_MAX]
    //     条批注帖（命中消息顺序即服务端相关性序，先到先得，高分优先展开）；
    //  2. 去重：同相册/同文字帖 TTL 内只展开一次（[markExpandedOnce]）；
    //  3. 并发：多对象 [SEARCH_EXPAND_CONCURRENCY] 路并发（原串行 for 循环），
    //     单频道展开耗时 ≈ 单个 GetChatHistory；相册窗口 (-25,50) 收窄到 (-8,16)
    //     （相册 ≤10 成员，锚点附近足够覆盖）。
    val albumIds = found.messages.filterNotNull()
        .mapNotNull { m -> m.mediaAlbumId.takeIf { it != 0L }?.let { it to m.id } }
        .distinctBy { it.first }
        .take(SEARCH_ALBUM_EXPAND_MAX)
        .filter { (albumId, _) -> markExpandedOnce("a:$chatId:$albumId") }
    val textMsgs = found.messages.filterNotNull()
        .filter { it.content is TdApi.MessageText }
        .take(SEARCH_TEXT_EXPAND_MAX)
        .filter { markExpandedOnce("t:$chatId:${it.id}") }
    if (albumIds.isNotEmpty() || textMsgs.isNotEmpty()) {
        val expandSem = Semaphore(SEARCH_EXPAND_CONCURRENCY)
        // 展开产出先收进线程安全队列，段尾统一归并进 items（保 LinkedHashMap 单线程写）
        val extra = java.util.concurrent.ConcurrentLinkedQueue<VideoItem>()
        coroutineScope {
            for ((albumId, anchorId) in albumIds) {
                launch(Dispatchers.IO) {
                    expandSem.withPermit {
                        runCatching {
                            // 拉锚点附近的窗口，把同 mediaAlbumId 的兄弟成员都展开
                            val around = withTimeout(10_000) {
                                client.send(TdApi.GetChatHistory(chatId, anchorId, -8, 16, false))
                            }
                            val siblings = around.messages.filterNotNull()
                                .filter { it.mediaAlbumId == albumId }
                            // 相册共享 caption 补全（修"相册条目播放页只剩频道名"）：
                            // 展开的空 caption 成员原样产出时信息区无任何内容信息；
                            // 从窗口内同组成员（含带 caption 的锚点自身）建共享表，
                            // 经 captionOverride 补给空成员（Feed 形态A 同款语义）；
                            // 走 mergedAlbumCaptions 顺手写回跨页缓存（其他路径受益）。
                            // toVideoItem 内链①只填空白语义由 resolveTitle 吸收
                            val sharedCap = mergedAlbumCaptions(chatId, siblings)[albumId]
                            val titleCtx = TitleChainContext(albumCaps = sharedCap?.let { mapOf(albumId to it) } ?: emptyMap())
                            siblings.forEach { m ->
                                // 只展开空 caption 的同批成员（有独立 caption 的是
                                // 不同内容不展开——否则一条命中把整批不相关相册
                                // 拉进来，实测"搜知心妈妈出来一堆新闻"就是这么来的）
                                if (mediaCaptionOf(m).isBlank()) {
                                    toVideoItem(chatId, m, null, titleCtx = titleCtx)?.let { extra += it }
                                }
                            }
                        }.onFailure {
                            Log.w(TAG, "相册展开失败 chatId=$chatId album=$albumId: ${it.message}")
                        }
                    }
                }
            }
            // 批注帖展开（修"搜索搜不全"另一半）：资源频道常"先发一批媒体（caption 空），
            // 再发一条文字说明"——文字帖命中了搜索，但它下面那批媒体没 caption 搜不到。
            // 命中的文字帖往后（更旧的方向，媒体批所在侧）拉邻居，把紧随的空 caption 媒体批收进来。
            for (textMsg in textMsgs) {
                launch(Dispatchers.IO) {
                    expandSem.withPermit {
                        runCatching {
                            // 形态B 窗口方向修正：媒体批比文字帖"老"（先发媒体再发说明，
                            // GetChatHistory 新→旧排列）——旧实现 offset=-6 取的是比文字帖
                            // "更新"的 6 条（说明之后发的无关消息），关联方向反了，展开的媒体
                            // 既关联不到说明、还白占了展开名额。offset=0 从文字帖往旧取 7 条
                            //（文字帖 + 紧随的 6 条媒体批），与 fetchFromChannel 页内语义一致
                            val around = withTimeout(10_000) {
                                client.send(TdApi.GetChatHistory(chatId, textMsg.id, 0, 7, false))
                            }
                            // 批注帖正文：展开的空 caption 媒体拿它当关联说明
                            //（Feed 形态B 同款语义；旧实现展开成员 caption 为空，
                            //  播放页信息区只剩频道名）
                            val noteText = (textMsg.content as? TdApi.MessageText)
                                ?.text?.text?.trim()?.takeIf { it.isNotEmpty() }
                            // 文字帖之后（更旧一侧）的连续空 caption 媒体 ≤6 条；
                            // 遇下一条文字帖断开（与 buildCaptionOverrides 页内语义一致）
                            around.messages.filterNotNull()
                                .filter { it.id < textMsg.id }
                                .take(6)
                                .takeWhile { it.content !is TdApi.MessageText }
                                .forEach { m ->
                                    if (mediaCaptionOf(m).isBlank()) {
                                        // R257b 链④：批注正文按消息 id 入证据包（守卫/计数在产出侧同口径）
                                        val noteCtx = TitleChainContext(adjacentNotes = noteText?.let { mapOf(m.id to it) } ?: emptyMap())
                                        toVideoItem(chatId, m, null, titleCtx = noteCtx)?.let { extra += it }
                                    }
                                }
                        }.onFailure {
                            Log.w(TAG, "批注帖展开失败 chatId=$chatId msg=${textMsg.id}: ${it.message}")
                        }
                    }
                }
            }
        } // coroutineScope 结构化并发：等全部展开收尾（取消时整批一起取消）
        extra.forEach { items.putIfAbsent(it.key, it) }
    }
    Log.d(TAG, "searchChannelAll: chatId=$chatId q=$q items=${items.size}/${found.totalCount} next=$nextFrom")
    return FeedPage(items.values.toList(), if (nextFrom != 0L) nextFrom.toString() else null)
}

/**
 * 评论级联搜索（搜索页修复包 #5）：searchChannelAll 只搜频道主帖，评论区的媒体
 * 永远搜不到。本方法对该频道"回复数>0 的主帖"线程做轻量扫描，把评论里
 * caption 命中 query 的媒体产出为条目（fromComment=true，caption 带 💬 前缀，
 * 与 Feed 评论级联 fetchCommentVideos 的产出语义一致——级联同款路径）。
 *
 * 候选主帖两路合并去重：
 *  1. 主帖全文搜命中的（SearchChatMessages，同 searchChannelAll 主路）——
 *     主帖命中时其评论大概率同话题；
 *  2. 频道最近一页帖子（GetChatHistory）——评论里的资源其主帖本身常常不命中。
 *
 * 评论无服务端搜索 API，线程内只能本地 caption 匹配：query 去 # 前缀后传给
 * toVideoItem 本地过滤（与 fetchFromChannel 的搜索回退路径同口径，大小写敏感
 * 由 toVideoItem 的 contains(ignoreCase=true) 兜住）。
 *
 * 预算闸（防 flood wait）：单频道最多扫 SEARCH_COMMENT_MAX_THREADS 个线程，
 * 每线程拉 SEARCH_COMMENT_THREAD_LIMIT 条回复，产出截断到 limit 条。
 * flood wait / 关评论 / 无权限等任何失败整体静默为空表——增量收益绝不影响主路。
 *
 * 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
 * GetMessageThreadHistory(long chatId, long messageId, long fromMessageId, int offset, int limit)
 *   -> Messages{totalCount, messages}（同 fetchCommentVideos 注释；1.8.67 只有 5 参版）
 * SearchChatMessages(long, MessageTopic, String, MessageSender, long, int, int, SearchMessagesFilter)
 *   -> FoundChatMessages{totalCount, messages, nextFromMessageId}（同 searchChannelAll）
 * GetChatHistory(long chatId, long fromMessageId, int offset, int limit, boolean onlyLocal)
 *   -> Messages
 * 主帖回复数读 Message.interactionInfo.replyInfo.replyCount
 *   （1.8.67 的 Message 没有 threadInfo 字段，见 fetchFromChannel 评论级联注释）
 */
internal suspend fun TdLibGateway.searchChannelCommentsImpl(chatId: Long, query: String, limit: Int = 10): List<VideoItem> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    // 本地匹配词：# 开头的话题词去前缀（评论 caption 里多半不带 #）
    val localQuery = q.trimStart('#', '＃').ifBlank { q }
    return runCatching {
        awaitAuthorized()
        // 候选主帖 messageId 去重保序（先全文搜命中、再最近帖）
        val candidates = LinkedHashSet<Long>()
        runCatching {
            client.send(
                TdApi.SearchChatMessages(chatId, null, q, null, 0L, 0, SEARCH_COMMENT_POST_SCAN, null)
            )
        }.getOrNull()?.messages?.filterNotNull()?.forEach { m ->
            if ((m.interactionInfo?.replyInfo?.replyCount ?: 0) > 0) candidates += m.id
        }
        runCatching {
            client.send(TdApi.GetChatHistory(chatId, 0L, 0, SEARCH_COMMENT_RECENT_SCAN, false))
        }.getOrNull()?.messages?.filterNotNull()?.forEach { m ->
            if ((m.interactionInfo?.replyInfo?.replyCount ?: 0) > 0) candidates += m.id
        }
        if (candidates.isEmpty()) return@runCatching emptyList()
        val chTitle = channelTitle(chatId)
        val out = LinkedHashMap<String, VideoItem>()
        for (mid in candidates.take(SEARCH_COMMENT_MAX_THREADS)) {
            if (out.size >= limit) break
            val history = runCatching {
                client.send(TdApi.GetMessageThreadHistory(chatId, mid, 0L, 0, SEARCH_COMMENT_THREAD_LIMIT))
            }.getOrNull() ?: continue // 单线程失败静默跳过（flood/关评论/无权限）
            // 评论区相册共享 caption（形态A，与 fetchCommentVideos 同口径）：
            // 空 caption 的相册回复补共享文案后再过本地 query 过滤——补进来的文案
            // 也能命中搜索（修"评论里的相册成员永远搜不到/只显示频道名"）。
            // 刻意不做形态B：线程里的文字多为对话回复而非批注，关联会张冠李戴
            val threadMsgs = history.messages.filterNotNull()
            val threadAlbumCaps = albumSharedCaptions(threadMsgs)
            for (reply in threadMsgs) {
                if (out.size >= limit) break
                // 回复消息的 chatId 是关联讨论组的 id（同 fetchCommentVideos 注释）：
                // 产出条目必须用 reply.chatId，频道标题沿用主帖所在频道
                toVideoItem(reply.chatId, reply, localQuery, fromComment = true, titleOverride = chTitle,
                    titleCtx = TitleChainContext(albumCaps = threadAlbumCaps))
                    ?.let { out.putIfAbsent(it.key, it) }
            }
        }
        Log.d(TAG, "searchChannelComments: chatId=$chatId q=$q 线程=${candidates.size} 命中=${out.size}")
        out.values.toList()
    }.getOrElse { emptyList() }
}

// ---------------- 深度索引日历跳爬（WOW-SEARCH W3 考古加速配套） ----------------

/**
 * 频道发帖日历直方图：(UTC 日起始 epoch 秒 → 当天消息数) 升序列表，只含有消息的日子。
 * 供 DeepSearchIndex 首次建索引"跳爬"与 R46 采样闸估规模：没消息的日子整体跳过，
 * 不为它们做锚点/翻页。
 *
 * MessageCalendarDay 自身不带日期字段（javap 实证只有 totalCount + 样本 message），
 * 日归属从样本消息的 Message.date 按 UTC 取整（/86400*86400）推得——跨设备/时区口径一致。
 *
 * ★ 过滤器口径修正（R46 实测）：TDLib 1.8.67 服务端**拒收空过滤器**
 * （null/SearchMessagesFilterEmpty → 400 "The filter is not supported"，
 * 旧版传 null 恒失败、日历路径事实上从未生效）。现由调用方传入
 * [SearchMessagesFilterPhotoAndVideo]——日历语义从"有消息日"收窄为"有媒体日"：
 * 对深度索引（只收视频/图/音频三类媒体）这恰是正确口径；已知收窄——纯文字日/
 * 纯音频日会被计为空日（日历跳爬兜底路径可能跳过这些日子，由最老段 floor=0
 * 线性直爬到频道底兜底完整性；采样闸只用它估"媒体规模"，语义无偏差）。
 *
 * GetChatMessageCalendar 一次只返回 fromMessageId 所在月的日历，历史月份逐月回溯：
 * 用 GetChatMessageByDate(本月 1 号 0 点前 1 秒) 拿"本月之前最后一条消息"——该消息落在
 * 前一个**非空**月（空月被这一跳天然跳过、零请求），再拿它的 id 拉上月日历。
 * 每个非空月 2 次请求（跳锚 + 拉历），封顶 [CALENDAR_MAX_MONTHS] 个非空月；超出覆盖的
 * 更老历史由调用方线性爬兜底（最老一段不设下界、直爬到频道底），完整性不依赖本覆盖深度。
 * 中途单月失败保留已累计月份返回（残缺日历仍可用于跳爬）；首月历失败返回空表
 * （调用方对空表一律回退两阶段线性爬，宁慢勿缺）。
 *
 * 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
 * GetChatMessageCalendar(long chatId, MessageTopic topicId, SearchMessagesFilter filter,
 *   long fromMessageId) -> MessageCalendar{totalCount: int, days: MessageCalendarDay[]}
 * MessageCalendarDay{totalCount: int, message: Message}（无日期字段，取 message.date 推日）
 * GetChatMessageByDate(long chatId, int date) -> Message（"不晚于 date 的最后一条"，
 *   date 落在无消息的日子/月份时自动向前落到最近一条存量消息——空日/空月零成本跳过）
 * Message{id: long, date: int}
 */
internal suspend fun TdLibGateway.getChatActivityDaysImpl(
    chatId: Long,
    filter: TdApi.SearchMessagesFilter = TdApi.SearchMessagesFilterPhotoAndVideo(),
    maxMonths: Int = CALENDAR_MAX_MONTHS,
): List<Pair<Int, Int>> {
    val byDay = sortedMapOf<Int, Int>() // 日 epoch → 当天消息数（多月合并、同日去重、升序）
    // 首月历失败 = 日历整体不可用，空表交给调用方回退线性爬；
    // 每请求 30s 超时（R46：月级低频请求放宽窗口——flood 恢复期服务端响应慢，
    // 但绝不能无超时挂死爬取车道；R5 同口径教训）
    var calendar = runCatching {
        awaitAuthorized()
        withTimeout(30_000) { client.send(TdApi.GetChatMessageCalendar(chatId, null, filter, 0L)) }
    }.onFailure {
        Log.w(TAG, "getChatActivityDays: 首月历失败 chatId=$chatId: ${it.message}")
    }.getOrNull() ?: return emptyList()
    var months = 0
    var lastOldest = Int.MAX_VALUE
    // R204 TL-5：月份上限由调用方可调（≤CALENDAR_MAX_MONTHS 钳制）——编年史首批 12 个月
    // 快出墙、按需回溯到 24；深索引跳爬用默认全量，行为不变
    val monthsBudget = maxMonths.coerceIn(1, CALENDAR_MAX_MONTHS)
    while (months < monthsBudget) {
        var oldest = Int.MAX_VALUE
        calendar.days?.filterNotNull()?.forEach { day ->
            if (day.totalCount <= 0) return@forEach // 保险丝：正常响应本就不含空日
            val msg = day.message ?: return@forEach
            val dayEpoch = msg.date / CALENDAR_DAY_SECONDS * CALENDAR_DAY_SECONDS
            byDay[dayEpoch] = day.totalCount
            if (dayEpoch < oldest) oldest = dayEpoch
        }
        // 本月无有效日，或回退后最老日没有变更老（TDLib 落点异常）→ 停，防死循环
        if (oldest == Int.MAX_VALUE || oldest >= lastOldest) break
        lastOldest = oldest
        months++
        // 跳上一个非空月：本月 1 日 00:00:00(UTC) 前一秒 → "不晚于"该时刻的最后一条消息
        val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        utc.timeInMillis = oldest * 1000L
        utc.set(java.util.Calendar.DAY_OF_MONTH, 1)
        utc.set(java.util.Calendar.HOUR_OF_DAY, 0)
        utc.set(java.util.Calendar.MINUTE, 0)
        utc.set(java.util.Calendar.SECOND, 0)
        utc.set(java.util.Calendar.MILLISECOND, 0)
        val hopEpoch = (utc.timeInMillis / 1000L).toInt() - 1
        val hopMsgId = runCatching {
            withTimeout(30_000) { client.send(TdApi.GetChatMessageByDate(chatId, hopEpoch)).id }
        }.getOrDefault(0L)
        if (hopMsgId == 0L) break // 本月之前已无任何消息，回溯到底
        calendar = runCatching {
            withTimeout(30_000) { client.send(TdApi.GetChatMessageCalendar(chatId, null, filter, hopMsgId)) }
        }.getOrNull() ?: break // 单月拉取失败：保留已累计月份
    }
    Log.d(TAG, "getChatActivityDays: chatId=$chatId 非空月=$months 活跃日=${byDay.size}")
    return byDay.entries.map { it.key to it.value }
}

/**
 * 跳爬锚点：不晚于 [epochSec] 的最后一条消息 id（GetChatMessageByDate）。
 * 典型用法：epochSec = 某日 23:59:59(UTC) → 当天最后一条消息，作为 GetChatHistory
 * 的 fromMessageId 从当天尾部往回爬。之前无消息（或任何失败）返回 null。
 * 30s 超时（R46：月级低频请求放宽窗口——flood 恢复期服务端响应慢，
 * 但绝不能无超时挂死爬取车道；R5 同口径教训）。
 *
 * 签名已 javap 实证：GetChatMessageByDate(long chatId, int date) -> Message{id: long}
 */
internal suspend fun TdLibGateway.getMessageIdByDateImpl(chatId: Long, epochSec: Int): Long? = runCatching {
    awaitAuthorized()
    withTimeout(30_000) { client.send(TdApi.GetChatMessageByDate(chatId, epochSec)).id }.takeIf { it > 0L }
}.onFailure {
    Log.w(TAG, "getMessageIdByDate 失败 chatId=$chatId epoch=$epochSec: ${it.message}")
}.getOrNull()

/**
 * 频道媒体总量探测（R46 采样闸的"规模证据"）：SearchChatMessages 空查询 + 媒体过滤器
 * limit=1，只取服务端 totalCount 不搬内容（成本 = 一次普通搜索请求）；
 * 图+视频一次、音频一次，合计 2 请求。
 *
 * 为什么不用 GetChatMessageCalendar 直方图：实测本环境（TDLib 1.8.67 预编译库 + 本账号）
 * 该调用挂起到 30s 超时仍无响应（同刻 SearchChatMessages/GetChatMessageByDate 毫秒级
 * 正常）——日历原语不可用，总量探测与按月锚点（getMessageIdByDate）是实证的可用替代。
 *
 * 单路失败按 0 计（宁低估不阻断主路）；两路全失败返回 null（调用方按"探测不可用"
 * 回退原有路径，不赌）。
 * 签名已 javap 实证（同 searchInChannel）：SearchChatMessages(long, MessageTopic, String,
 * MessageSender, long, int, int, SearchMessagesFilter) -> FoundChatMessages{totalCount,…}。
 */
internal suspend fun TdLibGateway.probeMediaTotalCountImpl(chatId: Long): Int? {
    var total = 0
    var ok = false
    for (filter in arrayOf<TdApi.SearchMessagesFilter>(
        TdApi.SearchMessagesFilterPhotoAndVideo(),
        TdApi.SearchMessagesFilterAudio(),
    )) {
        val found = runCatching {
            awaitAuthorized()
            withTimeout(10_000) {
                client.send(TdApi.SearchChatMessages(chatId, null, "", null, 0L, 0, 1, filter))
            }
        }.getOrNull() ?: continue
        ok = true
        total += found.totalCount
    }
    return if (ok) total else null
}

/**
 * 频道主页资源统计探测（R49）：服务端真实口径，替换"已加载条目本地计数"
 * （本地口径翻页越多数字越大，不是频道真实总量，用户实测不准）。
 *
 * 媒体计数：三次 SearchChatMessages(chatId, null, "", null, 0L, 0, 1, filter)
 * （filter = Video/Photo/Audio 各一次），只读 totalCount——服务端计数，
 * 1 条结果的请求成本拿真实总数（与 R46 probeMediaTotalCount 同族零件）。
 * 成员数：GetChat(chatId) 取 ChatTypeSupergroup.supergroupId →
 * GetSupergroupFullInfo(supergroupId).memberCount（与 fetchSupergroupProfile 同款用法）。
 *
 * 降级语义：单类计数失败该项给 -1（调用方不显示该项）；成员数失败/非超级群给 -1
 * （0/-1 调用方都不显示）；全部失败返回 null（调用方回落"已加载口径"旧文案）。
 * R44 熔断表纪律：SearchChatMessages 不属于已治理的 SearchPublicChat 族，
 * 撞 429 不上报熔断表，runCatching 静默降级即可。
 * 进程内缓存 1 小时 TTL（[CHANNEL_STATS_CACHE_TTL_MS]）；全失败不缓存（下次进页重试）。
 */
internal suspend fun TdLibGateway.probeChannelStatsImpl(chatId: Long): ChannelStatTriple? {
    channelStatsCache[chatId]?.let { (cached, at) ->
        if (System.currentTimeMillis() - at < CHANNEL_STATS_CACHE_TTL_MS) return cached
    }
    var ok = false
    val counts = IntArray(3) { -1 } // 0=视频 1=图文 2=音频
    val filters = arrayOf<TdApi.SearchMessagesFilter>(
        TdApi.SearchMessagesFilterVideo(),
        TdApi.SearchMessagesFilterPhoto(),
        TdApi.SearchMessagesFilterAudio(),
    )
    for (i in filters.indices) {
        val found = runCatching {
            awaitAuthorized()
            withTimeout(10_000) {
                client.send(TdApi.SearchChatMessages(chatId, null, "", null, 0L, 0, 1, filters[i]))
            }
        }.getOrNull() ?: continue
        counts[i] = found.totalCount
        ok = true
    }
    // 成员数：失败/隐藏/非超级群一律 -1（调用方不显示），静默降级不阻断计数
    val memberCount = runCatching {
        awaitAuthorized()
        val chat = withTimeout(10_000) { client.send(TdApi.GetChat(chatId)) }
        val sg = chat.type as? TdApi.ChatTypeSupergroup ?: return@runCatching -1
        withTimeout(10_000) { client.send(TdApi.GetSupergroupFullInfo(sg.supergroupId)).memberCount }
    }.getOrElse { -1 }
    if (memberCount >= 0) ok = true
    if (!ok) {
        Log.d(TAG, "probeChannelStats: 全路失败 chatId=$chatId，调用方回落已加载口径")
        return null
    }
    val result = ChannelStatTriple(
        videoCount = counts[0],
        photoCount = counts[1],
        audioCount = counts[2],
        memberCount = memberCount,
    )
    channelStatsCache[chatId] = result to System.currentTimeMillis()
    return result
}

// ---------------- W5 口味雷达：全网公开帖子搜索原语（SearchPublicPosts，每日免费配额） ----------------

/**
 * 全网公开帖子搜索（WOW-SEARCH W5 口味雷达的巡逻原语）。
 *
 * 红线（继承 SEARCH-ARCH §5.3 / WOW-SEARCH 红线表）：starCount 恒传 0——
 * 超额暗扣 Stars 是信任事故，本方法签名层面不开放该参数，调用方想付费都传不进来。
 *
 * 返回 (命中条目列表, nextOffset)；nextOffset 为 null = 没有下一页。
 * 命中消息天然跨频道（各消息的 chatId 各异），复用 toVideoItem 链转 VideoItem
 * （跨频道可行性已由 searchViaBot 的 GetMessageLinkInfo→toVideoItem 链实证）。
 * 注意：toVideoItem 对未缓存标题的频道会补一次 GetChat（幂等查询，
 * channelTitleCache 进程内缓存去重）——候选卡需要真标题，这是必要成本。
 *
 * 每次响应把 searchLimits 快照进 [publicPostQuota] 并记日志（配额透明红线；
 * remainingFreeQueryCount 必须可读，"替你巡逻"也要让用户看见花了什么）。
 *
 * 失败语义：向上抛（与 searchChannelAll 同款），由调用方逐词 runCatching 跳过——
 * 配额耗尽/限流必须让巡逻循环看见（配合 [publicPostQuota] 提前收工），不做静默吞错。
 *
 * 签名已 javap 对本项目 core-release.aar（TDLib 1.8.67）实证：
 * SearchPublicPosts(String query, String offset, int limit, long starCount)
 *   -> FoundPublicPosts{messages: Message[], nextOffset: String,
 *      searchLimits: PublicPostSearchLimits, areLimitsExceeded: boolean}
 * PublicPostSearchLimits{dailyFreeQueryCount: int, remainingFreeQueryCount: int,
 *   nextFreeQueryIn: int, starCount: long, isCurrentQueryFree: boolean}
 */
internal suspend fun TdLibGateway.searchPublicPostsImpl(query: String, offset: String = "", limit: Int = 20): Pair<List<VideoItem>, String?> {
    val q = query.trim()
    require(q.isNotEmpty()) { "巡逻关键词为空" }
    awaitAuthorized()
    val found = client.send(TdApi.SearchPublicPosts(q, offset, limit.coerceIn(1, 100), 0L))
    // 配额快照 + 日志（红线：配额透明；searchLimits 为 JNI 平台类型，防御 null）
    val limits = found.searchLimits
    if (limits != null) {
        publicPostQuota = Triple(limits.remainingFreeQueryCount, limits.dailyFreeQueryCount, found.areLimitsExceeded)
        Log.i(
            TAG,
            "searchPublicPosts: 配额 剩${limits.remainingFreeQueryCount}/${limits.dailyFreeQueryCount}" +
                " exceeded=${found.areLimitsExceeded} 本次免费=${limits.isCurrentQueryFree} q=$q",
        )
    } else {
        Log.i(TAG, "searchPublicPosts: 响应未携带配额信息 q=$q")
    }
    // 相册共享 caption（形态A 结果集内版）：跨频道命中按 chatId 分组建表
    //（mediaAlbumId 只在单频道内有归组意义）。形态B 不适用——公开帖子命中集
    // 不是连续时间线，且文字帖不会出现在媒体命中里
    val hitMsgs = found.messages.filterNotNull()
    val capsByChat = hitMsgs.groupBy { it.chatId }
        .mapValues { (_, ms) -> albumSharedCaptions(ms) }
    val items = hitMsgs
        .mapNotNull { m -> runCatching { toVideoItem(m.chatId, m, null, titleCtx = TitleChainContext(albumCaps = capsByChat[m.chatId] ?: emptyMap())) }.getOrNull() }
        .distinctBy { it.key } // 跨频道同 key 不会撞，防御性去重
    Log.d(TAG, "searchPublicPosts: q=$q items=${items.size} next=${found.nextOffset}")
    return items to found.nextOffset.takeIf { it.isNotEmpty() }
}

// ---------------- 评论级联搜索常量（searchChannelComments 配套，文件级私有） ----------------

/** 评论级联搜索：主帖全文搜候选数（命中的主帖优先扫线程） */
private const val SEARCH_COMMENT_POST_SCAN = 5

/** 评论级联搜索：频道最近帖扫描数（补"主帖不命中但评论有货"的场景） */
private const val SEARCH_COMMENT_RECENT_SCAN = 10

/** 评论级联搜索：单频道最多扫描的线程数（flood 预算闸；每频道首页顺带一次，须收敛） */
private const val SEARCH_COMMENT_MAX_THREADS = 2

/** 评论级联搜索：每线程拉取的回复条数（只看最新一页评论，老评论放弃） */
private const val SEARCH_COMMENT_THREAD_LIMIT = 20

// ---------------- 相册/批注展开收敛常量（SEARCH-PERF §2，searchChannelAll 配套） ----------------

/** 相册展开：每页最多展开的相册数（命中消息顺序即服务端相关性序，先到先得） */
private const val SEARCH_ALBUM_EXPAND_MAX = 4

/** 批注帖展开：每页最多展开的文字帖数（请求量收敛，同相册展开口径） */
private const val SEARCH_TEXT_EXPAND_MAX = 2

/** 相册/批注展开并发上限（原串行 for 循环；并发后单频道展开耗时 ≈ 单个 GetChatHistory） */
private const val SEARCH_EXPAND_CONCURRENCY = 3

/** 展开去重 TTL（毫秒）：同相册/同文字帖 10 分钟内只展开一次（翻页/同词重搜不重复展开） */
private const val SEARCH_EXPAND_DEDUP_TTL_MS = 10L * 60_000L

/** 展开去重缓存容量上限：超出先清过期项，仍超全清重建（缓存重建便宜，幂等） */
private const val SEARCH_EXPAND_CACHE_MAX = 500

// ---------------- 深度索引日历跳爬常量（getChatActivityDays 配套，文件级私有） ----------------

/** 日历跳爬：一天的秒数（UTC 日取整用，故意不用本地时区——跨设备/时区口径一致） */
private const val CALENDAR_DAY_SECONDS = 86_400

/** 日历跳爬：逐月回溯的非空月数上限（每非空月 2 次请求；超出部分由调用方线性爬兜底，不缺数据）
 *  R204 TL-5 起 internal：getChatActivityDays 公开签名以它做默认月份预算（编年史按 12→24 分批回溯） */
internal const val CALENDAR_MAX_MONTHS = 24

// ---------------- R49 频道主页统计探测配套（TdLibGateway.probeChannelStats） ----------------

/** 频道统计探测的进程内缓存 TTL（1 小时）：统计数字变化慢，同一频道反复进主页不重复探测 */
private const val CHANNEL_STATS_CACHE_TTL_MS = 3_600_000L
