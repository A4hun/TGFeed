package dev.tgfeed.core.feed

import dev.tgfeed.core.store.LocalStore
import dev.tgfeed.core.td.FeedRequest
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

// ---------------- 查看上下文（功能面板动作）：切频道 Feed 并定位到该条 ----------------

internal fun FeedViewModel.consumeContextJumpImpl() {
    _contextJumpKey.value = null
}

/**
 * 查看上下文：切到该条所在频道的 Feed，并把首拉游标锚定在该条 messageId 上，
 * 使目标条目落在首页头部附近（FeedScreen 观察 [contextJumpKey] 到位后滚动定位）。
 *
 * 为什么不直接 `ChannelFeedSource(gateway, item.chatId)` + 事后在列表里找：
 * 频道源从最新一页开拉，目标若是老帖（老剧集/老资源）根本不在首页，定位必然落空。
 * 锚定 messageId 利用单频道游标的既有语义——真实网关首拉走
 * [TdLibGateway.fetchChannelPageAnchored]（首轮 GetChatHistory offset=-1，
 * 锚点消息自身入窗；注意 offset=0 是排他口径，平板实证爬虫游标每页恰好步进
 * 50×2^20 零重叠——若用排他口径，目标永远不进列表）；Mock cursor="o:N"
 * 序号（传裸数字串 toIntOrNull 同样解析）——首页必含目标，定位恒成立。
 * 匿名源不进 FeedSource.kt（本文件追加约束），与 FeedScreen 机器人搜索的内联源同一先例。
 *
 * 另外首页会捎带"比目标更新"的同频道条目拼在目标之前（前后几集都够得着）：
 * 取最新一页、按"新→旧"排列截目标之前的前缀即是；目标已跌出最新一页（老帖）
 * 时自动退化为"从目标开始向旧刷"，不为前文做无谓深翻。
 * 注意：本匿名源不是 ChannelFeedSource，loadMore 的 useMediaFetch 服务端过滤分支
 * 不命中它（走本地 tab 过滤），锚点游标因此在任何媒体 tab 下都生效。
 */
internal fun FeedViewModel.openChannelAtImpl(item: VideoItem) {
    val req = FeedRequest(channelId = item.chatId)
    switchSource(object : FeedSource {
        override val request: FeedRequest = req

        /** 下一页游标：首拉锚定目标 messageId（含目标自身，向更旧方向翻） */
        internal var cursor: String? = item.messageId.toString()
        internal var exhausted = false

        /** 首页 prepend 只尝试一次（"比目标更新"的前缀）；false = 后续翻页纯走锚点游标 */
        internal var newerPending = true

        // FeedPage 用全限定名：本文件只追加类体成员，不动 import 区
        override suspend fun nextPage(limit: Int): dev.tgfeed.core.td.FeedPage {
            if (exhausted) return dev.tgfeed.core.td.FeedPage(emptyList(), null)
            // 锚点页：从目标 messageId 开始向旧翻。
            // 首拉必须"含锚点自身"（fetchChannelPageAnchored，首轮 offset=-1）：
            // GetChatHistory offset=0 对锚点是排他的（平板实证：爬虫游标每页恰好步进
            // 50×2^20 零重叠）——排他口径下目标消息永远不进列表，深链定位超时、
            // 合集展开因找不到目标而整体不生效（实测两个 bug 同一根因）。
            // 续页（cursor 已被推进）走普通排他游标，与两个网关的既有约定一致
            val lib = gateway as? dev.tgfeed.core.td.TdLibGateway
            val anchor = runCatching {
                if (lib != null && cursor == item.messageId.toString()) {
                    lib.fetchChannelPageAnchored(item.chatId, item.messageId, limit)
                } else {
                    gateway.fetchVideos(request, cursor, limit)
                }
            }.getOrElse { dev.tgfeed.core.td.FeedPage(emptyList(), null) }
            cursor = anchor.nextCursor
            if (cursor == null) exhausted = true
            if (!newerPending) return anchor
            newerPending = false
            // 捎带最新一页里排在目标之前的条目（两个网关都是新→旧排列，前缀即"后几集"）；
            // 目标不在最新一页（老帖）则放弃 prepend，锚点页原样返回
            val newestPage = runCatching { gateway.fetchVideos(request, null, limit) }
                .getOrElse { dev.tgfeed.core.td.FeedPage(emptyList(), null) }
            val newest = newestPage.items
            // 锚点落空兜底（深链实测坑：帖子被删/链接失效/锚点 id 不在服务区间时，
            // 锚定页拉空、最新页又被整条丢弃 → 用户看到"这个源暂时没有视频"的白屏）。
            // 降级为从频道最新一页开刷（游标改挂最新页的续页游标，翻页链路正常续走），
            // contextJumpKey 定位 6s 超时自清、停留在频道顶部——有内容看总好过空屏
            if (anchor.items.isEmpty()) {
                if (newest.isEmpty()) return anchor // 频道本身也没货，真·空态
                cursor = newestPage.nextCursor
                exhausted = cursor == null
                return dev.tgfeed.core.td.FeedPage(newest, cursor)
            }
            // 深链合集展开：链接落在相册（mediaAlbumId≠0）的某一个成员上时，锚定页只含
            // "不新于锚点"的消息——锚点是组内靠前成员，比它新的兄弟成员整批缺失
            //（实测 t.me/wwtjz/12191 合集只出一条）。同组全部成员窗口拉取并入首页，
            // 用户能滑完整个合集（机制复用搜索相册展开口径，见网关 expandAlbumMembers）；
            // 定位 key 仍指向链接成员本身，滚动定位语义不变
            val pageItems = expandAnchorAlbum(anchor.items)
            val tIdx = newest.indexOfFirst { it.key == item.key }
            val newer = if (tIdx > 0) newest.subList(0, tIdx) else emptyList()
            if (newer.isEmpty()) return dev.tgfeed.core.td.FeedPage(pageItems, cursor)
            return dev.tgfeed.core.td.FeedPage(newer + pageItems, cursor)
        }

        /**
         * 锚点目标属于相册时，把同组兄弟成员并入锚定页（深链合集展开）：
         * 比目标新的成员插到目标之前、比目标旧的插到目标之后（各自保持新→旧序，
         * 与锚定页的整体时间倒序一致）；已在本页的 key 去重。目标不在本页
         * （被过滤/非媒体）、不是相册、网关非真实 TDLib、展开失败——一律原样返回，
         * 锚定页语义不受影响。
         */
        internal suspend fun expandAnchorAlbum(items: List<VideoItem>): List<VideoItem> {
            val target = items.firstOrNull { it.key == item.key } ?: return items
            if (target.albumId == 0L) return items
            val lib = gateway as? dev.tgfeed.core.td.TdLibGateway ?: return items
            val siblings = runCatching {
                lib.expandAlbumMembers(item.chatId, target.albumId, item.messageId)
            }.getOrDefault(emptyList())
            if (siblings.isEmpty()) return items
            val existing = items.mapTo(HashSet()) { it.key }
            val fresh = siblings.filter { it.key !in existing }
            if (fresh.isEmpty()) return items
            val targetIdx = items.indexOfFirst { it.key == item.key }
            val newerSibs = fresh.filter { it.messageId > item.messageId }
                .sortedByDescending { it.messageId }
            val olderSibs = fresh.filter { it.messageId < item.messageId }
                .sortedByDescending { it.messageId }
            val out = items.toMutableList()
            out.addAll(targetIdx + 1, olderSibs)
            out.addAll(targetIdx, newerSibs)
            return out
        }

        override fun reset() {
            cursor = item.messageId.toString()
            exhausted = false
            newerPending = true
        }
    })
    _contextJumpKey.value = item.key
}

// ---------------- 相册（合集）同组信息：播放页"合集 N 项"标识与组内导航 ----------------

/**
 * 同组合影成员（同 chatId + 同 albumId，albumId≠0 = 多媒体消息成员），按当前 Feed
 * 列表顺序。播放页"合集"标识与"上一张/下一张"组内切换共用这份名单。
 * 计数以列表实际收进的成员为准：搜索相册展开只收部分成员、媒体 tab 分页边界
 * 切开相册时，按可见成员计数（不向 TDLib 补拉，零额外请求）。
 */
internal fun FeedViewModel.albumGroupOfImpl(item: VideoItem): List<VideoItem> {
    if (item.albumId == 0L) return emptyList()
    return _items.value.filter { it.albumId == item.albumId && it.chatId == item.chatId }
}

/**
 * R238 评论线程载体选取（纯函数，弹幕层与评论流共用同一尺子）：
 * 相册帖的讨论线程只挂在组内一名成员上（通常是携带 caption 的首成员——首成员
 * 常为图片，混合相册实证；其余成员 GetMessageThread 报 "Message has no thread"）。
 * 选取规则：
 *  1. 组内 commentCount>0 的成员（线程挂在它身上，GetMessageThread/GetMessageThread
 *     对它的 id 才有效）；
 *  2. 都不带（DB 直出条目/网络页未覆盖）→ 组内 messageId 最小的成员（相册发送序
 *     首位——线程与 caption 同挂首位的发帖惯例）；
 *  3. 非相册条目 → 自身。
 * 组成员取 [group]（调用方传 albumGroupOf 结果，列表实际可见成员口径）。
 */
internal fun albumThreadCarrierOf(group: List<VideoItem>, item: VideoItem): VideoItem {
    if (item.albumId == 0L || group.isEmpty()) return item
    group.firstOrNull { it.commentCount > 0 }?.let { return it }
    return group.minByOrNull { it.messageId } ?: item
}

/**
 * R238 评论线程载体（VM 公共口径）：当前条目的相册组内实际携带讨论线程的成员。
 * 弹幕层（FeedScreen getCommentsRich）与评论媒体流（openCommentStream）共用——
 * 两处必须同源，否则同一相册弹幕拉 A 成员线程、评论流拉 B 成员线程，一成一败。
 */
internal fun FeedViewModel.commentThreadCarrierImpl(item: VideoItem): VideoItem =
    albumThreadCarrierOf(albumGroupOfImpl(item), item)

/**
 * R238 评论媒体流（用户实锤"37 条评论全是媒体资源"——评论区=媒体金矿）：
 * 点按信息区「💬 N 条评论」角标 → 拉该帖讨论线程的媒体回复（视频/图片/音频，
 * fetchCommentVideos 全媒体口径），插入当前条目之后并滚动到第一条——下滑即进
 * 评论媒体流（💬 前缀条目，与既有级联产出同语义）。
 * 返回用户可读结果文案（UI 直接 Toast，FeedActions.forwardToSaved 同款契约）。
 * 会话级去重账 [commentStreamOpened]：同一线程只展开一次（重按给出诚实文案，
 * 不重复拉取重复插条）。
 */
internal suspend fun FeedViewModel.openCommentStreamImpl(item: VideoItem): String {
    val lib = gateway as? TdLibGateway
        ?: return "演示模式不支持评论流"
    // 线程载体：相册组内携带线程的成员（对它的 id GetMessageThreadHistory 才有效）
    val carrier = commentThreadCarrierImpl(item)
    val threadKey = "comment-stream:${carrier.chatId}:${carrier.messageId}"
    if (threadKey in commentStreamOpened) {
        return "评论媒体已加入播放列表"
    }
    // 拉取（失败与"无线程/无媒体"区分开——失败不记账，稍后可重试）
    val fetched = runCatching {
        lib.fetchCommentVideos(carrier.chatId, carrier.messageId, item.channelTitle, limit = 50)
    }.getOrNull()
    if (fetched == null) return "评论拉取失败，请稍后再试"
    if (fetched.isEmpty()) {
        commentStreamOpened.add(threadKey)
        return "评论区没有可播放的媒体"
    }
    var insertedKeys: List<String> = emptyList()
    // CAS update 与翻页追加并发互不丢更新（patchCaption 同款口径）
    _items.update { list ->
        val idx = list.indexOfFirst { it.key == item.key }
        if (idx < 0) list
        else {
            val existing = list.mapTo(HashSet()) { it.key }
            val fresh = fetched.filter { it.key !in existing }
            if (fresh.isEmpty()) list
            else {
                insertedKeys = fresh.map { it.key }
                list.toMutableList().apply { addAll(idx + 1, fresh) }
            }
        }
    }
    commentStreamOpened.add(threadKey)
    if (insertedKeys.isEmpty()) return "评论媒体已在播放列表里"
    // 滚到插入的首条（与 jumpToAlbumSibling 同通道：_resumeScroll → pager 滚动）
    val firstKey = insertedKeys.first()
    val idx = _items.value.indexOfFirst { it.key == firstKey }
    if (idx >= 0) {
        _currentIndex.value = idx
        _resumeScroll.tryEmit(firstKey)
    }
    return "已加入 ${insertedKeys.size} 条评论媒体，下滑即看"
}

/**
 * 组内跳转（播放页"上一张/下一张"）：delta ±1 按 Feed 列表顺序取目标成员
 * （+1 = 列表下一条，与下滑翻页方向一致）。目标在当前列表里才跳——滚动复用
 * [resumeScroll] 既有事件通道（FeedScreen 收集后 pager.scrollToPage，settledPage
 * 变化自动回调 onPage 结算观看/记历史），本方法只负责定位与发事件。
 * @return false = 无可跳方向（当前已是组内边界/组里只有一名成员，UI 不该显示该按钮）
 */
internal fun FeedViewModel.jumpToAlbumSiblingImpl(item: VideoItem, delta: Int): Boolean {
    val group = albumGroupOf(item)
    if (group.size < 2) return false
    val idx = group.indexOfFirst { it.key == item.key }
    if (idx < 0) return false
    val target = group.getOrNull(idx + delta) ?: return false
    val targetIdx = _items.value.indexOfFirst { it.key == target.key }
    if (targetIdx < 0) return false
    // 与 tryRestorePosition 同款：先定位索引再发滚动事件（pager 落定后 onPage 会再写一次）
    _currentIndex.value = targetIdx
    _resumeScroll.tryEmit(target.key)
    return true
}

// ---------------- 频道主页秒开：本地深度索引直出 ----------------

/**
 * 读某频道的本地深度索引条目（SQLite 深度索引库 items 表，messageId 新→旧序），
 * 供频道主页首帧直出（修"主页加载慢"：不再干等网络分页第一页，索引内容本地秒出，
 * 网络页随后按 key 覆盖升级）。
 *
 * DB 查询（chatId 索引命中），**请在 IO 线程调用**。索引未建/查询失败返回空列表
 * （调用方按无缓存处理）。
 *
 * 已知薄字段（索引库未存，网络页到达后覆盖升级）：
 *  - coverBytes：恒 null（封面走 CoverStore 抽帧 / loadCoverBytesByMessage 消息反解链，
 *    否则灰底占位）；coverFileId/audioCoverFileId 迁移后新爬条目已入库可用；
 *  - playUri：恒 null（点播放走 ensurePlayable 既有链现取）；
 *  - 旧 JSON 迁移来的条目 albumId 恒 0（旧落盘格式未存）：图集角标退化为"图"，
 *    网络页覆盖后恢复"🖼 图集"；迁移后新爬条目 albumId 正常入库；
 *  - fileId 是入库时的会话 id，跨进程重启可能失效：点播放走 ensurePlayable 既有
 *    报错链，图片封面采样链失败静默回落。
 */
internal fun FeedViewModel.loadChannelIndexedItemsImpl(chatId: Long): List<VideoItem> =
    runCatching { deepIndex.channelItems(chatId) }.getOrDefault(emptyList())

/**
 * R212：读某频道深度索引的**一页**（messageId 新→旧，LIMIT+OFFSET 有界——
 * DeepSearchIndex.channelItemsPage），供频道主页无限滚动的索引补页通道
 * （网络翻页失败/到底而索引还有余量时的兜底供给）。**请在 IO 线程调用**；
 * 索引未建/查询失败返回空列表（调用方按无货处理）。
 * 字段薄口径与 [loadChannelIndexedItemsImpl] 完全一致（同一张表同一 rowToItem）。
 */
internal fun FeedViewModel.loadChannelIndexedPageImpl(chatId: Long, limit: Int, offset: Int): List<VideoItem> =
    runCatching { deepIndex.channelItemsPage(chatId, limit, offset) }.getOrDefault(emptyList())

// ---------------- 展示期 caption 回填（相册成员 caption 晚到补救） ----------------

/**
 * 按 key 回填条目 caption（VideoItem 不可变 → copy 替换；_items CAS update 与
 * IO 线程翻页追加并发互不丢更新——与屏蔽/剔除链路的 P1-11 口径一致）。
 * 仅当该条目当前 caption 为空才写入：已有 caption（自带/抓取期关联/先到的回填）
 * 一律不动，回填永远只补空白、不覆盖。
 * 调用点：VideoPage 展示期 LaunchedEffect(item.key)——相册成员上屏仍空 caption 时
 * 向网关 peekAlbumCaption 要到共享/批注文案后回填，正在播的条目信息区即时补全。
 */
internal fun FeedViewModel.patchCaptionImpl(key: String, text: String) {
    if (text.isBlank()) return
    _items.update { list ->
        val idx = list.indexOfFirst { it.key == key }
        if (idx < 0 || list[idx].caption.isNotBlank()) {
            list // 条目已被淘汰/已有 caption：不动
        } else {
            list.toMutableList().also { it[idx] = list[idx].copy(caption = text) }
        }
    }
}

// ---------------- 历史回溯注入：从深度索引挖老内容进 Feed ----------------

/**
 * 历史回溯抽样（"老内容挖掘"）：资源发布频道动辄成千上万条历史，Feed 只刷得到
 * 最新一页——深度索引（SQLite 全量历史爬取）是老内容的矿，这里把老片随机挖出来。
 *
 *  - 适用源：聚合（Aggregate/WeightedAggregate，抽全部已爬频道）与单频道
 *    （ChannelFeedSource，只抽本频道）；收藏/稍后/历史/搜索/合集/订阅追更等
 *    本地或用户显式圈定的源不注入（不污染语义明确的列表）；
 *  - 适用 tab：视频 tab（0）与全部 tab（-1）；mediaType 对齐由 [pass] 过滤链完成
 *    （与正页条目同一谓词：屏蔽/不感兴趣/永久指纹/已看/内容筛选/类型开关/tab）；
 *  - [excludeKeys]：已出现过的 key（当前列表 + 本页），SQL 层 NOT IN 减负 +
 *    谓词层 key 复查双保险防重复刷到。
 *    注意本参数语义只是"当前会话内不重复"，**不含已看排除**——已看剔除由
 *    [seenIds] 双轨负责（P1-C 初版只靠 [pass] 里 key 级 seenKeys 顺带挡：
 *    聚合源有效，但单频道源 seenKeys 恒为空集等于没滤，且 key 级管不住
 *    跨频道转发的同片）；
 *  - [seenIds]：已看身份（key 集 + fileUniqueId 指纹集，LocalStore.loadSeenIdentities
 *    口径）；非 null 时抽中的已看条目一律剔除（回溯专挖老内容，正是"很久以前看过"
 *    的重灾区，已看排除对它比对正页更关键）；null = 不排除（开关关，恢复旧行为）；
 *  - 过采样 [BACKFILL_OVERSAMPLE] 倍再过滤取满 [BACKFILL_PER_PAGE]：索引含图文/音频，
 *    视频 tab 下会被谓词吃掉一部分；
 *  - 静默兜底：索引库未建（纯文件 stat，不开库不建库）/无已爬频道/查询失败
 *    一律返回空表——历史回溯是增益，绝不影响正常刷；
 *  - 注入条目 playUri=null（点播放走 ensurePlayable 链现取）、coverBytes=null
 *    （封面走消息反解链），与频道页索引直出同口径。
 */
internal fun FeedViewModel.sampleHistoryBackfill(
    src: FeedSource,
    tab: Int,
    excludeKeys: Set<String>,
    pass: (VideoItem) -> Boolean,
    seenIds: Pair<Set<String>, Set<String>>? = null,
): List<VideoItem> {
    if (tab != 0 && tab != -1) return emptyList() // 只在视频/全部 tab 注入（任务口径）
    if (!deepIndex.hasIndex()) return emptyList() // 索引库从未建立：不开库不建库
    val channelIds: List<Long> = when (src) {
        is ChannelFeedSource -> listOf(src.channelId)
        is AggregateFeedSource, is WeightedAggregateFeedSource ->
            deepIndex.indexedChannelIds().toList() // 已爬频道全集（未建索引的渠道天然不在列）
        else -> return emptyList()
    }
    if (channelIds.isEmpty()) return emptyList()
    return deepIndex.sampleRandom(
        channelIds, excludeKeys, BACKFILL_PER_PAGE * BACKFILL_OVERSAMPLE,
    ).filter { item ->
        // key 复查（SQL 排除键截断的兜底）+ 已看双轨（key/内容指纹）+ 同口径过滤
        // R337：已看双轨判定收口 SeenDedupPolicy.isSeen（与正页/召回/网关同一把尺）
        if (item.key in excludeKeys) return@filter false
        if (seenIds != null && SeenDedupPolicy.isSeen(item, seenIds.first, seenIds.second)) {
            return@filter false
        }
        pass(item)
    }.take(BACKFILL_PER_PAGE)
}

/**
 * 注入条目随机分散插入页面（单频道源专用：页内全同频道，排序机器无区分度不启用，
 * 注入条目只拼尾部会"扎堆页尾"——逐条独立随机位插入，天然分散）。
 */
internal fun FeedViewModel.spreadInsertRandom(page: List<VideoItem>, extra: List<VideoItem>): List<VideoItem> {
    val out = page.toMutableList()
    for (e in extra) out.add(kotlin.random.Random.nextInt(out.size + 1), e)
    return out
}
