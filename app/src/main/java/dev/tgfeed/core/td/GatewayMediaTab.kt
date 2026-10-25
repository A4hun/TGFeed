package dev.tgfeed.core.td

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi

/**
 * 服务端媒体类型过滤聚合拉取（音频/图文 tab 的正路）：
 * 之前的"全量爬再本地过滤"对稀疏类型（音频 ~1 条/页）是灾难——深挖慢且被已看过滤吃光。
 * SearchChatMessages + 媒体过滤器（空查询）= 服务端只回该类型的消息，分页快且全。
 * 签名已 javap 实证：SearchMessagesFilterAudio/Photo/Video 存在；
 * SearchChatMessages(chatId, topicId, query, senderId, fromMessageId, offset, limit, filter)。
 */
internal suspend fun TdLibGateway.fetchAggregatedMediaImpl(
    chatIds: List<Long>,
    mediaType: Int,
    cursor: String?,
    limit: Int,
    // 深度索引清扫传 false：跳过空 caption 邻居窗口展开（每页省最多
    // MEDIA_TAB_EXPAND_MAX 次 GetChatHistory——清扫页量大，展开是限流暴露面的
    // 最大来源；页内相册共享 caption 的免费路径不受影响，新内容 caption 保真
    // 由旧路头部刷新的未过滤时间线承担）。其余调用方默认 true，行为不变
    expandCaptions: Boolean = true,
): FeedPage {
    val filter: TdApi.SearchMessagesFilter = when (mediaType) {
        0 -> TdApi.SearchMessagesFilterVideo()
        1 -> TdApi.SearchMessagesFilterPhoto()
        else -> TdApi.SearchMessagesFilterAudio()
    }
    // 单频道调用（深度索引媒体清扫 PAGE_SIZE=100 / 单频道媒体 tab limit=100）：
    // 每频道请求量直接取 limit（封顶 100 = TDLib SearchChatMessages 单请求上限）——
    // 清扫页数由此减半，限流暴露面同步减半；多频道聚合调用保持 50/频道不变
    // （页内频道多样性不被稀释，GridScreen/聚合 tab 行为零变化）
    val perChannelLimit = if (chatIds.size == 1) limit.coerceIn(1, 100) else 50
    // R94 余量消费（字段注释见 [mediaLeftoverLock]）：多频道调用每频道拉 50 条而页长
    // 可能只有 24（分屏直拉），截下部分带游标标签暂存，同一游标链回来先吃余量——
    // 修"分屏直拉 2000 取 24、其余随游标前进被永久丢弃"。单频道调用
    // perChannelLimit==limit 无截断，不涉及；游标链对不上/新轮（cursor==null）作废现拉
    val laneKey = "media:$mediaType"
    var prefix: List<VideoItem> = emptyList()
    if (chatIds.size > 1) {
        if (cursor == null) {
            synchronized(mediaLeftoverLock) {
                mediaLeftovers.remove(laneKey)
                mediaLeftoverTags.remove(laneKey)
            }
        } else {
            val claimed = synchronized(mediaLeftoverLock) {
                if (mediaLeftoverTags[laneKey] == cursor) {
                    val pool = mediaLeftovers[laneKey].orEmpty()
                    val s = pool.take(limit)
                    val rest = pool.drop(limit)
                    if (rest.isEmpty()) {
                        mediaLeftovers.remove(laneKey)
                        mediaLeftoverTags.remove(laneKey)
                    } else {
                        mediaLeftovers[laneKey] = rest
                    }
                    s
                } else {
                    mediaLeftovers.remove(laneKey)
                    mediaLeftoverTags.remove(laneKey)
                    emptyList()
                }
            }
            if (claimed.size >= limit) {
                Log.d(TAG, "fetchAggregatedMedia: 余量供给整页 $limit 条（本次零网络请求）")
                return FeedPage(claimed, cursor)
            }
            prefix = claimed
        }
    }
    // R106（H4）修法同下方 fetchAggregatedMediaAllImpl 的 P1-1：游标表被 Semaphore
    // 放行的多个 async 协程并发 put，普通 HashMap 并发 put 触发 resize 可能结构损坏、
    // 后续 get 死循环（IO 挂死表现 ANR，分屏多频道聚合路径必踩）——必须 ConcurrentHashMap
    val cursors = java.util.concurrent.ConcurrentHashMap(decodeCursor(cursor))
    val sem = Semaphore(CONCURRENCY)
    val items = java.util.concurrent.ConcurrentLinkedQueue<VideoItem>()
    kotlinx.coroutines.coroutineScope {
        chatIds.map { chatId ->
            async {
                sem.acquire()
                try {
                    val from = cursors[chatId] ?: 0L
                    if (from < 0L) return@async // 该频道已到底
                    val found = withTimeout(10_000) {
                        client.send(
                            TdApi.SearchChatMessages(
                                chatId, null, "", null, from, 0, perChannelLimit, filter,
                            ),
                        )
                    }
                    // 相册共享 caption（形态A）：媒体 tab 按类型直出不过 caption 门槛，
                    // 空 caption 的相册成员从同页同组共享补全（否则播放页只剩频道名）；
                    // 并入跨页缓存（mergedAlbumCaptions）：带 caption 的成员落在邻页，
                    // 或是混排相册的异类型成员（永不在本类型页内）时，也能补。
                    // 仍空的再做限量邻居窗口展开（expandBlankCaptions：
                    // 形态A 跨类型兄弟 + 形态B 批注帖，每频道每页 ≤MEDIA_TAB_EXPAND_MAX 次请求；
                    // 深度索引清扫经 expandCaptions=false 整体跳过——见签名处注释）
                    val pageMsgs = found.messages.filterNotNull()
                    val albumCaps = mergedAlbumCaptions(chatId, pageMsgs)
                    // R257b 回退链接线：BlankExpandResult 结构化证据（albumCaps 链②/notes 链④）
                    // 直接入 TitleChainContext，链序裁决在 resolveTitle（②>④ 吸收旧 ?: 优先级）
                    val expanded = if (expandCaptions) {
                        expandBlankCaptions(chatId, pageMsgs, albumCaps)
                    } else {
                        BlankExpandResult(albumCaps, emptyMap())
                    }
                    val titleCtx = TitleChainContext(albumCaps = expanded.albumCaps, adjacentNotes = expanded.notes)
                    val pageItems = pageMsgs
                        .mapNotNull { m -> runCatching { toVideoItem(chatId, m, null, titleCtx = titleCtx) }.getOrNull() }
                    items.addAll(pageItems)
                    // 下一页游标：空页（该类型已到底）标记 -1；否则用最后一条消息 id
                    cursors[chatId] = if (found.messages.isEmpty()) -1L
                    else found.messages.filterNotNull().last().id
                } catch (e: Exception) {
                    Log.w(TAG, "fetchAggregatedMedia: 频道 $chatId 失败: ${e.message}")
                } finally {
                    sem.release()
                }
            }
        }.forEach { it.await() }
    }
    val list = (prefix + items.toList()).shuffled()
    val pageItems = list.take(limit)
    val leftover = if (chatIds.size > 1) list.drop(limit).take(MEDIA_LEFTOVER_MAX) else emptyList()
    val allEnded = chatIds.all { (cursors[it] ?: 0L) < 0L }
    // 全到底但余量未空时必须仍返回非空游标，让余量随后续翻页 drain 完（同 R75-B 口径）
    val nextCursor = if (allEnded && leftover.isEmpty()) null else encodeCursor(cursors)
    if (chatIds.size > 1) {
        synchronized(mediaLeftoverLock) {
            if (leftover.isEmpty() || nextCursor == null) {
                mediaLeftovers.remove(laneKey)
                mediaLeftoverTags.remove(laneKey)
            } else {
                mediaLeftovers[laneKey] = leftover
                mediaLeftoverTags[laneKey] = nextCursor
            }
        }
    }
    Log.d(TAG, "fetchAggregatedMedia: mediaType=$mediaType channels=${chatIds.size} items=${list.size} 余量供给=${prefix.size} 余量回池=${leftover.size} allEnded=$allEnded")
    return FeedPage(pageItems, nextCursor)
}

// ---------------- 类型 tab 媒体拉取·全频道分页窗口版（修"图文 tab 只出前 40 频道"） ----------------

/**
 * 服务端媒体过滤聚合拉取的全频道分页版（与 [fetchAggregatedMedia] 并存的新增方法，
 * 旧方法保持原样——单频道源路径仍在用）。
 *
 * 背景：FeedViewModel 旧调用点把频道清单 take(40) 截断后才调 fetchAggregatedMedia——
 * 40 名以后的频道永远进不了图文/音频 tab（"图文频道没展示全部来源"的根因）。
 * 本方法把频道全集收进网关内部（listSearchableChats，自带 5 分钟 TTL 缓存，
 * 翻页不重复枚举），每页只扫一个 [MEDIA_TAB_CHANNEL_WINDOW] 频道宽的窗口
 * （单页请求包络与旧实现一致：≤40 次 SearchChatMessages、并发 5、单频道 10s 超时），
 * 窗口按游标里的轮转起点逐页推进——持续翻页即可覆盖全部频道（分页覆盖全部）。
 *
 * 游标格式（自含编解码，与 fetchAggregated 的 "chatId:msgId|…" 不互通）：
 *   "@<轮转起点>|chatId:msgId|chatId:msgId|…"；msgId=-1 表示该频道该类型已到底，
 *   未出现的频道默认 0（未开始）。chatId 为负时两段式 split(':') 解析不受影响
 *  （"-100…:123" → ["-100…","123"]）。游标只在内存流转（VM 侧 mediaTabCursor，
 *   tab 切换/刷新即重置），不落盘。
 * 频道清单中途变化（TTL 刷新/新关注/取关）：轮转起点对 size 取模永不越界；
 * 已到底标记按 chatId 记录，清单漂移只改变扫描顺序，已扫频道的续位游标不丢。
 * 旧格式游标（无 @ 段）传入时轮转起点为 0，天然兼容。
 *
 * 刷新起点轮换（修"每次刷新都是列表顺序前 40 个频道"）：cursor==null（刷新/首刷）
 * 时起点不再恒 0，而按 mediaType 各记一份进程内存起点（[mediaTabWindowStart]），
 * 每次刷新推进一个窗口宽、环形绕回——逐次刷新确定性遍历全部频道；轮内翻页仍由
 * 游标 @rotation 段驱动，语义不变。日志带 新进池/轮休 计数（与 fetchAggregated 同口径）。
 *
 * R87 保底坑位+名册回报（默认 Feed 真实路径接入 R75/R82）：刷新轮在窗口内划出
 * [GUARANTEED_POOL_SLOTS] 个保底坑位，按"最久未进窗口"（曝光账 [poolRecencyProvider]）
 * 优先给从未/很久没进窗口的频道，其余坑位照环形轮转填充；窗口选定后经 [poolReporter]
 * 保序回报（保底坑位居前），VM 侧 recordPoolRound 同口径记账并更新保底名册——
 * R82 露头段（rankWeightedPage 的保底频道前置）在媒体路径随之复活。
 *
 * 失败语义：单频道失败（flood wait/超时）只跳过该频道本页（游标不动，窗口轮转
 * 回来重试），不向上抛；全部频道到底才返回 nextCursor=null。
 *
 * 签名复用既有 javap 实证（core-release.aar，TDLib 1.8.67）：
 * SearchChatMessages(long, MessageTopic, String, MessageSender, long fromMessageId,
 *   int, int, SearchMessagesFilter) -> FoundChatMessages{messages, nextFromMessageId}；
 * SearchMessagesFilterVideo/Photo/Audio 均实证存在（jar tf 类清单）。
 */
internal suspend fun TdLibGateway.fetchAggregatedMediaAllImpl(
    mediaType: Int,
    cursor: String?,
    limit: Int,
): FeedPage {
    val filter: TdApi.SearchMessagesFilter = when (mediaType) {
        0 -> TdApi.SearchMessagesFilterVideo()
        1 -> TdApi.SearchMessagesFilterPhoto()
        else -> TdApi.SearchMessagesFilterAudio()
    }
    // 全量频道+群组清单：R216 起走免 GetChat 清扫的 id-only 枚举（自带 TTL 缓存，
    // 全量快照 TTL 内白拿 id）——旧路 listSearchableChats 的 598 次 GetChat 标题
    // 清扫在爬虫风暴下把整轮 loadMore 楔死数分钟~永久（「上滑不触发继续加载」
    // 的卡真形态，详见 listSearchableChatIdsImpl 注释）；媒体窗口只消费 id，
    // 标题由 toVideoItem 的 channelTitle 懒解析兜底（频道级一次、缓存、失败回落空串）
    val allIds = listSearchableChatIds()
    if (allIds.isEmpty()) {
        Log.w(TAG, "fetchAggregatedMediaAll: 没有可用频道")
        return FeedPage(emptyList(), null)
    }
    // R94 轮内余量消费（字段注释见 [mediaLeftoverLock]，仿 fetchAggregated 的 R75-B）：
    // 同一游标链（tag 匹配）回来先吃上一页截下的余量，满一页则本次零网络请求直接返回
    //（游标原样回传，链不断）；刷新轮（cursor==null）/游标链对不上 → 余量作废现拉
    val laneKey = "all:$mediaType"
    // R150 已看身份先取（余量直通与产出过滤共用）：provider 未注入/开关关/重温兜底
    // 态返回空集 = 两路径整体直通（纯内存读，零 IO 零延迟增量）
    val (seenK, seenU) = provideSeenIdentities()
    var prefix: List<VideoItem> = emptyList()
    if (cursor == null) {
        val discarded = synchronized(mediaLeftoverLock) {
            val n = mediaLeftovers.remove(laneKey)?.size ?: 0
            mediaLeftoverTags.remove(laneKey)
            n
        }
        if (discarded > 0) {
            Log.d(TAG, "fetchAggregatedMediaAll: 刷新轮重扫，上轮余量 $discarded 条作废")
        }
    } else {
        val claimed = synchronized(mediaLeftoverLock) {
            if (mediaLeftoverTags[laneKey] == cursor) {
                val pool = mediaLeftovers[laneKey].orEmpty()
                val s = pool.take(limit)
                val rest = pool.drop(limit)
                if (rest.isEmpty()) {
                    mediaLeftovers.remove(laneKey)
                    mediaLeftoverTags.remove(laneKey)
                } else {
                    mediaLeftovers[laneKey] = rest
                }
                s
            } else {
                val stale = mediaLeftovers.remove(laneKey)
                mediaLeftoverTags.remove(laneKey)
                if (!stale.isNullOrEmpty()) {
                    Log.d(
                        TAG,
                        "fetchAggregatedMediaAll: 游标链对不上，余量 ${stale.size} 条作废现拉",
                    )
                }
                emptyList()
            }
        }
        if (claimed.size >= limit) {
            // R150：余量供给直通页同过已看源头过滤——余量在产出时已滤过，这里兜
            // 旧版本余量与"开关中途打开"的边界；滤净时返回空页 + 原游标，调用方
            // 按整页滤空口径继续连拉（loadMore 的 FILTER_MAX_PULLS 链接住）
            val (keptClaim, claimHits) = filterSeenIdentities(claimed, seenK, seenU)
            if (claimHits > 0) {
                Log.i(TAG, "R150 seen 过滤命中 $claimHits 条（余量供给直通路径）")
            }
            Log.d(TAG, "fetchAggregatedMediaAll: 余量供给整页 $limit 条（本次零网络请求）")
            return FeedPage(keptClaim, cursor)
        }
        prefix = claimed
    }
    // 解析游标：@轮转起点 + 每频道消息游标（-1=该频道该类型已到底）
    var rotation = 0
    // P1-1 教训复用：游标表被窗口内多个 async 并发写，必须 ConcurrentHashMap——
    // 普通 HashMap 并发 put 触发 resize 可能结构损坏，后续 get 死循环（IO 挂死表现 ANR）
    val msgCursors = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    cursor?.split('|')?.forEach { part ->
        if (part.startsWith("@")) {
            rotation = part.removePrefix("@").toIntOrNull() ?: 0
        } else {
            val kv = part.split(':')
            val k = kv.getOrNull(0)?.toLongOrNull()
            val v = kv.getOrNull(1)?.toLongOrNull()
            if (k != null && v != null) msgCursors[k] = v
        }
    }
    // 刷新起点轮换（修"每次刷新都是列表顺序前 40 个频道"）：cursor==null = 刷新/首刷，
    // 不从 0 而从上次记录的开扫起点起步；轮内翻页（cursor!=null）走游标 @rotation，原语义不动。
    // R87 起环形起点按"环形车道实际取位数"推进（保底坑位不占环形里程，见下），
    // 被保底坑位跳过的清单位置下轮照扫，环形车道仍逐次刷新确定性遍历全表
    var newInPool = 0
    var rested = 0
    // R87 媒体窗保底坑位（把 R75 保底车道接到默认 Feed 真实路径）：刷新轮从全量清单按
    // "最久未进窗口"（曝光账 poolRecencyProvider，从未进窗 = 时刻 0 排最前；时刻并列时
    // sortedBy 稳定、保持清单原序，逐轮按序扫完全量）划出保底坑位居窗口前段；其余坑位
    // 照原环形轮转填充。纯环形轮转下 581 频道要 ~15 页才扫完一圈，长尾频道进窗机会渺茫；
    // 保底坑位让最久未进窗的频道每轮必被轮到（与 sampleRotationPool 保底车道同口径）
    var guaranteedPicks: List<Long> = emptyList()
    var guaranteedLastPooled: Map<Long, Long> = emptyMap()
    if (cursor == null) {
        // R92：环形起点落盘恢复（冷启动不再从清单头部重扫，跨会话续扫全表）
        loadMediaWindowStarts()
        rotation = mediaTabWindowStart[mediaType] ?: 0
        guaranteedLastPooled = poolRecencyProvider?.invoke().orEmpty()
        // R90 并列键修正（修"归档/文件夹频道永远刷不到"）：时刻并列（全员从未进窗时
        // 整表皆 0）原先按清单原序稳定——而清单是"主列表→归档→文件夹"拼接序，
        // 归档/文件夹频道永远排在保底队尾（实测 546 个从未进窗频道里前 527 个全是
        // 主列表，刷 ~66 轮才轮到归档）；改按 chatId 升序做并列键（与清单位置无关、
        // 与频道所在列表无关的确定性键），归档/文件夹在并列队列中均匀散开，
        // 首轮刷新即有机会入窗。确定性逐轮扫完全量的性质不变
        val recencyRanked = allIds
            .sortedWith(compareBy({ guaranteedLastPooled[it] ?: 0L }, { it }))
        // R92 内容分层（修"保底坑位被无视频文字频道白占"）：保底选池从单纯
        // "最久未进窗"改为"最久未进窗 ∧ 可能有该类型内容"——深度索引确认有货的
        // 频道优先，索引未爬到的未知频道次之（可能有货，给机会），确认无货的
        // 垫底（不堵死：候选不足时仍占坑，宁可试探不漏频道）。分层内保持
        // recencyRanked 原序（最久未进窗优先的公平性不变）。索引不可用
        //（provider 未注入/未建库）回退单口径
        val content = runCatching { poolContentProvider?.invoke(mediaType) }.getOrNull()
        guaranteedPicks = if (content == null) {
            recencyRanked.take(GUARANTEED_POOL_SLOTS)
        } else {
            val (counts, crawled) = content
            val hasContent = ArrayList<Long>()
            val unknown = ArrayList<Long>()
            val noContent = ArrayList<Long>()
            for (id in recencyRanked) {
                when {
                    (counts[id] ?: 0) > 0 -> hasContent += id
                    id !in crawled -> unknown += id
                    else -> noContent += id
                }
            }
            Log.d(
                TAG,
                "媒体窗保底分层：有货候选=${hasContent.size} 未知=${unknown.size} " +
                    "确认无货=${noContent.size}（坑位=$GUARANTEED_POOL_SLOTS）",
            )
            (hasContent + unknown + noContent).take(GUARANTEED_POOL_SLOTS)
        }
    }
    // 从轮转起点环形收集本页窗口（跳过已到底频道与已入窗的保底频道）；窗口随游标逐页推进
    val batch = ArrayList<Long>(MEDIA_TAB_CHANNEL_WINDOW)
    if (guaranteedPicks.isNotEmpty()) batch += guaranteedPicks // 保底坑位居前（保序回报用）
    val guaranteedSet = guaranteedPicks.toHashSet()
    var ringTaken = 0 // 环形车道实际取位数（保底坑位不占）：刷新起点的推进里程
    var idx = ((rotation % allIds.size) + allIds.size) % allIds.size // 防负数越界
    var scanned = 0
    while (batch.size < MEDIA_TAB_CHANNEL_WINDOW && scanned < allIds.size) {
        val id = allIds[idx]
        if (id !in guaranteedSet && (msgCursors[id] ?: 0L) >= 0L) {
            batch += id
            ringTaken++
        }
        idx = (idx + 1) % allIds.size
        scanned++
    }
    // 全部频道该类型都已到底：余量前缀（若有）必须随本页 drain 给调用方，不能随游标埋掉
    if (batch.isEmpty()) return FeedPage(prefix, null)
    if (cursor == null) {
        // 环形起点推进一个"环形车道取位宽"（对 size 取模环形绕回，清单中途变化也永不越界）
        mediaTabWindowStart[mediaType] = (rotation + ringTaken) % allIds.size
        persistMediaWindowStarts() // R92：推进即落盘，跨冷启动续扫
        // 轮换观测：相对上次刷新窗口的新进/轮休计数（轮内翻页恒 0，与 fetchAggregated 同口径）
        val last = mediaTabLastWindow[mediaType].orEmpty()
        newInPool = batch.count { it !in last }
        rested = last.count { it !in batch }
        mediaTabLastWindow[mediaType] = batch.toSet()
        // R92 保底名册显式回报（先于 poolReporter，同轮同线程）：R92 内容分层后
        // VM 侧 (lastPooled, chatId) 复算口径与网关实际选取已不一致，名册必须显式给
        guaranteedRosterReporter?.invoke(guaranteedPicks.toSet())
        // R87 名册+曝光账回报：与 fetchAggregated 同口径走 poolReporter → recordPoolRound
        //（ChannelExposureLedger 记账 + GuaranteedPoolHolder 名册更新，R82 露头段在媒体路径
        // 自动复活）。batch 保底坑位居前，toSet（LinkedHashSet）保序。轮内翻页不报
        //（同 fetchAggregated 口径）
        poolReporter?.invoke(batch.toSet())
        // 保底观测：逐频道一条"距上次进窗"明细 + 一行汇总（名册即同一批保底坑位，
        // VM 侧 recordPoolRound 同口径复算，另有"R82 保底名册"日志互证）
        val nowMs = System.currentTimeMillis()
        guaranteedPicks.forEach { id ->
            val lastAt = guaranteedLastPooled[id] ?: 0L
            val gap = if (lastAt <= 0L) "从未" else "${(nowMs - lastAt) / 86_400_000L}天"
            Log.d(TAG, "媒体窗保底入窗：频道尾号${id.toString().takeLast(6)} 距上次 $gap")
        }
        Log.d(TAG, "媒体窗保底：${guaranteedPicks.size} 个最久未进窗口频道入窗（名册 ${guaranteedPicks.size} 个）")
    }
    val nextRotation = idx // 下一页从这里继续扫
    val sem = Semaphore(CONCURRENCY)
    val items = java.util.concurrent.ConcurrentLinkedQueue<VideoItem>()
    // R216 干墙预滤命中账（窗口级汇总观测）：已看成员在邻居展开前被丢弃的条数
    // ——干墙窗口（产出全被 seen 吃光）从此在日志里现形，与"零产出频道"互补
    val preSeenDrops = java.util.concurrent.atomic.AtomicInteger(0)
    // R90 逐频道产出账（修媒体路径观测缺口："进窗了但拉不出内容"此前不可见——
    // 失败有日志、零产出无声无息）：每频道本页贡献条数入表，窗口拉完统一观测，
    // 并经 poolItemReporter 补进频道曝光账（与 fetchAggregated 同口径，账本
    // items 字段在默认 Feed 路径不再恒 0）。R216 起 produced 记**未看**产出
    //（进页口径——已看成员整条会被滤掉，不计"进了窗但永远进不了页"的货）
    val itemCounts = java.util.concurrent.ConcurrentHashMap<Long, Int>()
    kotlinx.coroutines.coroutineScope {
        batch.map { chatId ->
            async(Dispatchers.IO) {
                sem.acquire()
                try {
                    val from = msgCursors[chatId] ?: 0L
                    val found = withTimeout(10_000) {
                        client.send(
                            TdApi.SearchChatMessages(chatId, null, "", null, from, 0, 50, filter)
                        )
                    }
                    // 相册共享 caption（形态A）：同页同组成员有 caption 则共享给空成员；
                    // 并入跨页缓存（带 caption 的成员落邻页/异类型时也能补）
                    val pageMsgs = found.messages.filterNotNull()
                    val albumCaps = mergedAlbumCaptions(chatId, pageMsgs)
                    // R216 干墙提速（用户实锤「音频 tab 固定数量后上滑不加载」的干爬成本刀）：
                    // 已看源头过滤前移到 caption 邻居展开**之前**——R150 的过滤在整窗
                    // 收集后，已看成员照样付每频道最多 4×GetChatHistory 的展开成本；
                    // 干墙窗口（产出全被 seen 过滤吃光的窗）是媒体 tab 干爬分钟级的
                    // 主成本。判据与 filterSeenIdentities 同口径（key 精确 + fileUniqueId
                    // 指纹）；已看集为空（开关关/重温兜底/未注入）时整段直通，零行为
                    // 变化。对**未看**成员 caption 展开照旧全价（语义不变）。
                    val msgPairs = pageMsgs.mapNotNull { m ->
                        val probe = runCatching {
                            toVideoItem(chatId, m, null, titleCtx = TitleChainContext(albumCaps = albumCaps))
                        }.getOrNull()
                        if (probe == null) null else m to probe
                    }
                    val unseenPairs = if (seenK.isEmpty() && seenU.isEmpty()) {
                        msgPairs
                    } else {
                        val out = ArrayList<Pair<TdApi.Message, VideoItem>>(msgPairs.size)
                        for (p in msgPairs) {
                            // R337：双轨判定收口 core/feed SeenDedupPolicy.isSeen（与
                            // VM 侧 pass/召回/回溯同一把尺——网关侧孪生不再各写一份）
                            if (dev.tgfeed.core.feed.SeenDedupPolicy.isSeen(p.second, seenK, seenU)) {
                                preSeenDrops.incrementAndGet()
                            } else {
                                out += p
                            }
                        }
                        out
                    }
                    // 仍空的限量邻居窗口展开（形态A 跨类型 + 形态B 批注帖，语义同
                    // fetchAggregatedMedia）——只对未看成员跑：已看成员的展开是纯浪费
                    // R257b 回退链接线：BlankExpandResult 结构化证据入 TitleChainContext
                    //（链序裁决在 resolveTitle——②>④ 吸收旧 ?: 优先级）
                    val expanded = if (unseenPairs.isEmpty()) {
                        BlankExpandResult(albumCaps, emptyMap())
                    } else {
                        expandBlankCaptions(chatId, unseenPairs.map { it.first }, albumCaps)
                    }
                    val titleCtx = TitleChainContext(albumCaps = expanded.albumCaps, adjacentNotes = expanded.notes)
                    var produced = 0
                    unseenPairs.map { it.first }
                        .mapNotNull { m -> runCatching { toVideoItem(chatId, m, null, titleCtx = titleCtx) }.getOrNull() }
                        .forEach { items += it; produced++ }
                    itemCounts[chatId] = produced
                    // 空页 = 该频道该类型到底（标记 -1）；否则续页游标 = 本页最老一条
                    msgCursors[chatId] = if (found.messages.isEmpty()) -1L
                    else found.messages.filterNotNull().last().id
                } catch (e: Exception) {
                    // 单频道失败只跳过（游标不动，窗口轮转回来重试），绝不影响整页
                    Log.w(TAG, "fetchAggregatedMediaAll: 频道 $chatId 失败: ${e.message}")
                } finally {
                    sem.release()
                }
            }
        }.forEach { it.await() }
    }
    // R90 窗口产出观测：零产出频道尾号点名（区分"没进窗"与"进窗零产出"）；
    // 产出账每页回报（与 fetchAggregated 的 poolItemReporter 同口径）。
    // R216 后"零产出"含"产出全被已看预滤吃光"的干墙频道（日志可见的墙信号）
    run {
        val zero = batch.filter { (itemCounts[it] ?: 0) == 0 }
        Log.d(TAG, "媒体窗产出：窗口=${batch.size} 有货=${batch.size - zero.size} 零产出=${zero.size}" +
            if (zero.isEmpty()) "" else "（零产出尾号=${zero.joinToString(",") { it.toString().takeLast(6) }}）")
        runCatching { poolItemReporter?.invoke(itemCounts.filterValues { it > 0 }) }
    }
    // R92 窗内配额采样替掉纯随机截断（修"窗内 ~1500 条 shuffled().take(limit)
    // 把低频频道洗没"）：旧口径频道进页条数 ∝ 产出量，高产频道一窗 50 条、
    // 低频/保底频道一窗一两条时进页期望 ≈0——保底车道拉进了窗却过不了采样，
    // R82 露头段无米下锅（实测 117 个进窗频道 35 个历史零进页）。
    // 新口径见 [mediaWindowQuotaSample]：有货频道先各保配额条数，余额随机补满；
    // 游标语义不动（游标按拉取推进，与进页选择无关，同旧口径）
    // R94：采样输入 = 余量前缀 + 本页新拉的合并池（余量正是"已拉未展示"的货），
    // 截下部分不再丢弃——暂存余量缓冲，轮内翻页先吃余量（拉多少用多少）；
    // 全到底但余量未空时必须仍返回非空游标，让余量随后续翻页 drain 完
    // R150：合并池先过已看双轨源头过滤再采样——旧口径"先采样后由 VM 滤"会把
    // 页长稀释（400 条已看占大半时幸存寥寥，触发整页滤空连拉多烧请求）；
    // 源头滤后采样在幸存池上做，页长满、翻页省、刷新后同批已看内容不再进页。
    // 双保险口径不变：VM 侧 loadMore 的 pass 谓词照旧全量再滤一遍。
    // R216：频道级已在展开前预滤（preSeenDrops），本道过滤主要兜余量前缀 prefix
    // 与旧版本余量（items 已是未看集，命中数通常归零——口径如常如实呈现）
    val (unseenPool, seenHits) = filterSeenIdentities(prefix + items.toList(), seenK, seenU)
    val preDrops = preSeenDrops.get()
    if (seenHits > 0) {
        Log.i(TAG, "R150 seen 过滤命中 $seenHits 条（聚合媒体 tab 正路，池 ${items.size}→${unseenPool.size}）")
    }
    val list = mediaWindowQuotaSample(unseenPool, limit)
    val pageKeys = list.mapTo(HashSet()) { it.key }
    // R150：余量从"已滤池"截留——余量缓冲里不再滞留已看内容（旧余量在直通路径兜滤）
    val leftover = unseenPool.filter { it.key !in pageKeys }.take(MEDIA_LEFTOVER_MAX)
    val allEnded = allIds.all { (msgCursors[it] ?: 0L) < 0L }
    val nextCursor = if (allEnded && leftover.isEmpty()) null else buildString {
        append('@').append(nextRotation)
        msgCursors.forEach { (k, v) -> append('|').append(k).append(':').append(v) }
    }
    synchronized(mediaLeftoverLock) {
        if (leftover.isEmpty() || nextCursor == null) {
            mediaLeftovers.remove(laneKey)
            mediaLeftoverTags.remove(laneKey)
        } else {
            mediaLeftovers[laneKey] = leftover
            mediaLeftoverTags[laneKey] = nextCursor
        }
    }
    // R92 进页覆盖观测：配额采样后本页实际覆盖多少频道、保底频道有几成进页
    run {
        val pageChats = list.mapTo(HashSet()) { it.chatId }
        val guaranteedInPage = if (guaranteedSet.isEmpty()) 0 else pageChats.count { it in guaranteedSet }
        Log.d(
            TAG,
            "媒体窗进页：页=${list.size}条 覆盖=${pageChats.size}频道" +
                if (guaranteedPicks.isEmpty()) "" else "（保底进页=$guaranteedInPage/${guaranteedPicks.size}）",
        )
    }
    Log.d(TAG, "fetchAggregatedMediaAll: mediaType=$mediaType 频道总数=${allIds.size} 本页窗口=${batch.size} 新进池=$newInPool 轮休=$rested items=${items.size}→${list.size} 预滤已看=$preDrops seen过滤=$seenHits 余量供给=${prefix.size} 余量回池=${leftover.size} allEnded=$allEnded")
    return FeedPage(list, nextCursor)
}

// ---------------- R150 已看双轨源头过滤（聚合媒体 tab 正路 + 快车道共用） ----------------

/**
 * R150 已看双轨过滤（纯内存集合查，无 IO）：网关直拉产出源头剔除已看条目。
 * 判定与 VM 侧 loadMore 的 pass 谓词完全同口径——item.key 精确命中 或
 * 内容指纹 fileUniqueId 命中（跨频道转发同片也算看过；无指纹条目只走 key 轨）。
 * 返回 (幸存列表, 命中数)，命中数供观测日志（"seen 过滤命中 N 条"证据链）。
 * 入参双集皆空（开关关/重温兜底态/未注入）时整体直通，零开销。
 */
internal fun filterSeenIdentities(
    items: List<VideoItem>,
    seenKeys: Set<String>,
    seenUids: Set<String>,
): Pair<List<VideoItem>, Int> {
    if (seenKeys.isEmpty() && seenUids.isEmpty()) return items to 0
    var hits = 0
    val out = ArrayList<VideoItem>(items.size)
    for (it in items) {
        // R337：判定委托 core/feed SeenDedupPolicy.isSeen（双轨判定的唯一出处——
        // 网关侧与 VM 侧从此锁死同一把尺，等价锚见 SeenDedupPolicyTest）
        if (dev.tgfeed.core.feed.SeenDedupPolicy.isSeen(it, seenKeys, seenUids)) hits++ else out += it
    }
    return out to hits
}

/** 读 provider 并容错（未注入/抛异常 = 不过滤）；返回 (key 集, 指纹集) */
internal fun TdLibGateway.provideSeenIdentities(): Pair<Set<String>, Set<String>> =
    runCatching { seenIdentityProvider?.invoke() }.getOrNull()
        ?: (emptySet<String>() to emptySet())

// ---------------- 类型 tab 全频道分页常量（fetchAggregatedMediaAll 配套，文件级私有） ----------------

/** 类型 tab 媒体拉取的单页频道窗口宽度：与旧实现 take(40) 的单页请求包络一致
 * （≤40 次 SearchChatMessages/页、并发 5、单频道 10s 超时），窗口随游标逐页轮转推进，
 * 持续翻页即覆盖全部频道（修"图文 tab 只出前 40 频道"） */
private const val MEDIA_TAB_CHANNEL_WINDOW = 40

/** R94 媒体余量缓冲单车道容量上限（条）：≈6 页缓冲；条目带 minithumbnail 字节，
 *  上限防三类型车道合计内存失控。超出部分按序截断（前缀 continuity 优先） */
private const val MEDIA_LEFTOVER_MAX = 600

/**
 * R92 窗内配额采样（修"窗内 ~1500 条随机 take(100) 把低频频道洗没"）：
 * 有货频道先各保 [quota] 条（配额 = limit 摊到有货频道数、下限 1），余额再整体
 * 随机补满 limit——进窗频道必有条目进页（机会公平），谁排前仍由排序层
 *（rankWeightedPage 频道权重 + R82 保底露头）决定，采样层只管"进页机会"。
 * 保底坑位/低频频道的内容由此才能活着到达排序层，露头段才有米下锅。
 *
 * 边界：窗内总产出 ≤ limit 时等价旧口径（全部进页）；有货频道数 > limit 时
 *（极端：全是单条频道）配额 1 条后总量超限，随机裁到 limit（总有频道进不了页，
 * 物理上限不可避免）。入参顺序无假设，输出整体打散（不进页序即展示序）。
 */
private fun mediaWindowQuotaSample(items: List<VideoItem>, limit: Int): List<VideoItem> {
if (items.size <= limit) return items.shuffled()
val byChat = items.groupBy { it.chatId }
val quota = (limit / byChat.size).coerceAtLeast(1)
val picked = ArrayList<VideoItem>(byChat.size * quota)
val rest = ArrayList<VideoItem>(items.size)
for ((_, chatItems) in byChat) {
    val shuffled = chatItems.shuffled()
    picked.addAll(shuffled.take(quota))
    rest.addAll(shuffled.drop(quota))
}
return when {
    picked.size > limit -> picked.shuffled().take(limit) // 配额总量超限：随机裁
    picked.size < limit -> {
        picked.addAll(rest.shuffled().take(limit - picked.size)) // 余额随机补满
        picked.shuffled()
    }
    else -> picked.shuffled()
}
}

/** 媒体 tab 空 caption 邻居展开（expandBlankCaptions）：每频道每页最多展开的对象数
 * （相册锚点 + 散媒体锚点合计；每对象一次 GetChatHistory 窗口查询，
 *  markExpandedOnce TTL 去重防翻页重复展开）。窗口是多用途的（一个窗口覆盖窗内
 *  所有本页空缺成员），4 个锚点实测能盖住典型资源频道一页的绝大多数空缺；
 *  宁小勿大——媒体 tab 单页最多 40 频道，该值直接乘进单页请求包络 */
internal const val MEDIA_TAB_EXPAND_MAX = 4

// ---------------- R145 首屏快车道（fetchAggregatedMediaAll 的抢跑入口） ----------------

/**
 * R145 首屏快车道并发度：种子频道一波拉平（8 路 = 典型种子数，单波浪出结果）。
 * 刻意高于正路 CONCURRENCY=5：快车道总量只有 8 个请求（正路 40 频道×(1+展开)），
 * 短时高并发对 TDLib 的瞬时压力远小于正路整窗，不值得为它压并发。
 */
internal const val FAST_LANE_CONCURRENCY = 8

/**
 * R145 首屏快车道：冷启动首拉的抢跑入口。
 *
 * 根因（华为平板基线实证，r145_shots/hw_baseline_r1~r3）：Ready 后首拉 20~39s，
 * 构成 = 全量频道枚举（数秒）+ 40 频道窗口 × (SearchChatMessages + 每频道最多 4 次
 * 空 caption 邻居展开) ÷ 5 路并发，高 RTT 代理下请求排队整波超时（基线 13~23 次
 * 10s 超时全是 caption 展开贡献——单频道一个许可最长被 4×10s 展开钉住，尾部延迟
 * 决定整窗耗时）。
 *
 * 快车道对策（把"首屏出内容"与"整窗聚合"解耦）：
 *  - 种子频道由调用方给（快照条目/订阅清单——上次会话确认有货的频道，纯本地数据，
 *    零枚举零网络）；缺种子时调用方回落正路 fetchAggregatedMediaAll；
 *  - 每频道只发 1 个 SearchChatMessages（50 条），**不做空 caption 邻居展开**
 *    （页内相册共享 caption 的免费路径不受影响；首屏条目缺批注可接受，
 *    后续正路窗口翻页会补全）；
 *  - 8 路并发一波出结果：首屏内容到货从 ~20-39s 压到约一个请求 RTT；
 *  - R150：产出经 [seenIdentityProvider] 做已看双轨源头过滤（纯内存集合查，
 *    零延迟增量）——快车道每轮从种子频道最新 50 条重拉，不过滤则刷新后同批
 *    已看内容立刻回顶（用户实测"全局刷新后推荐里还是有重复的已看视频"的
 *    链路放大器）；滤后采样页长满、刷后首屏必新。
 *
 * 游标兼容（与正路无缝衔接）：返回 "@rotation|chatId:msgId|…" 同款格式——
 *  - rotation 取已落盘的环形起点（不推进：快车道没扫环形车道，推进里程应为 0；
 *    正路下页从既有起点继续扫全表，跨会话续扫语义不变）；
 *  - 种子频道的游标已按本页推进，下页正路窗口扫到它们时从断点续拉（不重拉
 *    首屏已见内容）；扫不到就等轮转，天然去重；
 *  - 余量走 "all:<mediaType>" 车道（与正路共享）：种子 8 频道×50 条远超页长，
 *    截下部分进余量池，下一页先吃余量（零网络）再触发正路窗口。
 *
 * 观测口径：一行结论日志（种子数/命中数/条数/耗时），供冷启动掐表前后对比。
 */
suspend fun TdLibGateway.fetchAggregatedMediaAllFast(
    mediaType: Int,
    limit: Int,
    seedChatIds: List<Long>,
): FeedPage {
    val t0 = android.os.SystemClock.elapsedRealtime()
    val filter: TdApi.SearchMessagesFilter = when (mediaType) {
        0 -> TdApi.SearchMessagesFilterVideo()
        1 -> TdApi.SearchMessagesFilterPhoto()
        else -> TdApi.SearchMessagesFilterAudio()
    }
    // 种子去重保序（调用方按兴趣序给，顺序即优先级）
    val seeds = seedChatIds.distinct().take(FAST_LANE_CONCURRENCY)
    if (seeds.isEmpty()) {
        return FeedPage(emptyList(), null)
    }
    // 无快照直通路（真空启动）：UI 兜底 loadMore 可在授权前点火，这里等 Ready
    //（与正路 listSearchableChats 的 awaitAuthorized 同语义；快照路径 Ready 已过零等待）
    awaitAuthorized()
    // 刷新轮语义：同车道旧余量作废（与正路 cursor==null 分支同口径）
    val laneKey = "all:$mediaType"
    synchronized(mediaLeftoverLock) {
        mediaLeftovers.remove(laneKey)
        mediaLeftoverTags.remove(laneKey)
    }
    val msgCursors = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    val items = java.util.concurrent.ConcurrentLinkedQueue<VideoItem>()
    val hitChannels = java.util.concurrent.atomic.AtomicInteger(0)
    val sem = Semaphore(FAST_LANE_CONCURRENCY)
    kotlinx.coroutines.coroutineScope {
        seeds.map { chatId ->
            async(Dispatchers.IO) {
                sem.acquire()
                try {
                    val found = withTimeout(10_000) {
                        client.send(
                            TdApi.SearchChatMessages(chatId, null, "", null, 0, 0, 50, filter),
                        )
                    }
                    val pageMsgs = found.messages.filterNotNull()
                    if (pageMsgs.isNotEmpty()) hitChannels.incrementAndGet()
                    // 页内相册 caption 共享照常（免费：只读本页消息）；跨页/邻居展开
                    // 刻意不做——那是正路窗口的活，快车道不付它的延迟
                    val albumCaps = mergedAlbumCaptions(chatId, pageMsgs)
                    pageMsgs
                        .mapNotNull { m ->
                            runCatching {
                                toVideoItem(chatId, m, null, titleCtx = TitleChainContext(albumCaps = albumCaps))
                            }.getOrNull()
                        }
                        .forEach { items += it }
                    msgCursors[chatId] = if (pageMsgs.isEmpty()) -1L else pageMsgs.last().id
                } catch (e: Exception) {
                    // 单频道失败只跳过（游标不动，正路窗口轮转回来重试），不影响快车道整页
                    Log.w(TAG, "首屏快车道：频道 $chatId 失败: ${e.message}")
                } finally {
                    sem.release()
                }
            }
        }.forEach { it.await() }
    }
    // 配额采样同正路（保频道多样性的页内选择，语义零变化）
    // R150 已看源头过滤（A 主根因之一，时间点与用户实测完全吻合）：快车道是
    // 刷新/冷启动首拉的抢跑通道，种子频道每轮从最新 50 条重拉——同批已看内容
    // 若不滤，会以"刷新后立刻重复"的形态顶到首屏（此前靠 VM 侧 pass 谓词兜底
    // 页长被稀释；用户实测"全局刷新后推荐里还是有重复的已看视频"的链路放大器）。
    // 源头滤后采样在幸存池上做，页长满、刷后首屏必新；双保险：VM 侧照旧全量再滤
    val (seenK, seenU) = provideSeenIdentities()
    val (unseenItems, seenHits) = filterSeenIdentities(items.toList(), seenK, seenU)
    if (seenHits > 0) {
        Log.i(TAG, "R150 seen 过滤命中 $seenHits 条（首屏快车道，池 ${items.size}→${unseenItems.size}）")
    }
    val list = mediaWindowQuotaSample(unseenItems, limit)
    val pageKeys = list.mapTo(HashSet()) { it.key }
    val leftover = unseenItems.filter { it.key !in pageKeys }.take(MEDIA_LEFTOVER_MAX)
    // rotation 不推进：沿用落盘环形起点（快车道没在环形车道上取位）
    loadMediaWindowStarts()
    val rotation = mediaTabWindowStart[mediaType] ?: 0
    val nextCursor = buildString {
        append('@').append(rotation)
        msgCursors.forEach { (k, v) -> append('|').append(k).append(':').append(v) }
    }
    synchronized(mediaLeftoverLock) {
        if (leftover.isEmpty()) {
            mediaLeftovers.remove(laneKey)
            mediaLeftoverTags.remove(laneKey)
        } else {
            mediaLeftovers[laneKey] = leftover
            mediaLeftoverTags[laneKey] = nextCursor
        }
    }
    val elapsed = android.os.SystemClock.elapsedRealtime() - t0
    Log.i(
        TAG,
        "首屏快车道：种子=${seeds.size} 命中=${hitChannels.get()} 条目=${items.size}→${list.size} " +
            "seen过滤=$seenHits 余量回池=${leftover.size} 耗时${elapsed}ms",
    )
    return FeedPage(list, nextCursor)
}
