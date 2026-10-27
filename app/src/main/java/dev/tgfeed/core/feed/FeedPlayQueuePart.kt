package dev.tgfeed.core.feed

import androidx.lifecycle.viewModelScope
import dev.tgfeed.core.store.PlayStationsState
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// ---------------- R387 播放队列（节目单）：ops + 开单/断点续播 ----------------
// ---------------- R391 播放台（多队列）：台级 CRUD + 一键开台 ----------------
//
// 一期四件的 VM 编排层（状态与委托声明在 FeedViewModel 自含块，本文件只做实现
// ——FeedPlaylistPart/FeedNavPart 同款「件只加实现、刀落在 VM」分工）：
//  - 队列编辑（入单/移除/拖拽/置顶/跳过/档位/清空）=纯模型操作（主线程串行，
//    PlayQueueModel 模型纪律）+ 状态发布 + conflated 落盘串流——作用域=活跃台；
//  - 开单 openPlayQueue：模型快照 →（HEAT 档）成员数查表 → buildPlayPlan →
//    PlayQueueFeedSource → 主线程切源（openPlaylist 同款线程语义）；
//  - 断点续播：开单后 6s 有界等首页含 resumeKey → 定位滚动（contextJumpKey
//    6s 同口径；落空诚实回顶）；记账 = onPage 钩子（FeedViewModel 三行刀）；
//  - R391 台级编排：台列表底账（playStations 主线程内存）+活跃台切换纪律
//    （切台前 flush 旧台最末笔——一期在途窗挂账在显式切台路径上收窄）+
//    台级 CRUD（建/改/删，MAX_STATIONS 上限）+一键开台（切活跃台→一期开单链
//    零改全继承：快照/HEAT 查表/置题=台名/断点 6s 跳回/迟到守卫）。
//
// 红线（r387_notes §3.4）：autoAdvance/播放池/预载/后台闸零接触——队列只是
// items 流的新供给方；watchlater/toggleWatchLater 零碰（两系统并存，仅入单桥）。
// R391 红线（r391_notes §0）：一期 ops 十法/onPage 断点钩子语义零改（作用域
// =活跃台自动同步列表）；今日单生成器纯件见 TodayQueueGen.kt（笔4/笔5）。

/**
 * 加入频道台级条目（星图星卡「入单」/队列页「加频道」两入口）。
 * 返回 false = 同台已在单（幂等，UI Toast「已在队列」分流）。
 * 无活跃台（新装空库）＝入单自动开默认台（一期体感连续，r391_notes §3.3
 * 判词：两入口 UI 零碰，落点=活跃台）。
 * 探针行=计数口径（台尾号+余量，零频道名——脱敏铁律）。
 */
internal fun FeedViewModel.enqueueChannelToPlayQueueImpl(chatId: Long, title: String): Boolean {
    ensureActiveStationForEnqueue()
    val changed = playQueueModel.enqueueChannel(chatId, title)
    if (changed) {
        android.util.Log.i(
            "R387",
            "队列入单:台尾=${chatId.toString().takeLast(6)} 余量=${playQueueModel.queue.entries.size}",
        )
        afterPlayQueueMutation()
    }
    return changed
}

/**
 * 加入单条条目（feed 长按面板「加入队列」=「稍后再看入单」动作的模型面，
 * r387_notes §1.3 判词：R387 的稍后再看=存进播放队列的新动作，既有稍后再看
 * 源/按钮原样保留两系统并存）。
 * 返回 false = 同 key 已在单（幂等）。无活跃台=入单自动开默认台（同上判词）。
 */
internal fun FeedViewModel.enqueueItemToPlayQueueImpl(item: VideoItem): Boolean {
    ensureActiveStationForEnqueue()
    val changed = playQueueModel.enqueueItem(item)
    if (changed) {
        android.util.Log.i(
            "R387",
            "队列入单:条目key尾=${item.key.takeLast(12)} 余量=${playQueueModel.queue.entries.size}",
        )
        afterPlayQueueMutation()
    }
    return changed
}

/**
 * 稍后再看入单桥（队列页底部动作，一键）：watchlater.json 全量入单。
 * 逐条幂等（已在单的跳过）；IO 读盘 → 主线程批量入模型（LocalStore 阻塞 IO
 * 不落主线程——首次读盘走 IO，后续命中内存缓存）。无活跃台=自动开默认台。
 */
internal fun FeedViewModel.enqueueWatchLaterToPlayQueueImpl() {
    viewModelScope.launch(Dispatchers.IO) {
        val items = runCatching { store.loadWatchLater() }.getOrDefault(emptyList())
        if (items.isEmpty()) return@launch
        withContext(Dispatchers.Main) {
            ensureActiveStationForEnqueue()
            var added = 0
            for (item in items) {
                if (playQueueModel.enqueueItem(item)) added++
            }
            if (added > 0) {
                android.util.Log.i("R387", "稍后再看入单桥:新增 ${added} 条（源 ${items.size} 条）")
                afterPlayQueueMutation()
            }
        }
    }
}

/** 移除一条（按条目 id） */
internal fun FeedViewModel.removeFromPlayQueueImpl(entryId: String) {
    if (playQueueModel.remove(entryId)) afterPlayQueueMutation()
}

/** 拖拽换序（UI 列表下标=entries 下标，跳过条目占位可见） */
internal fun FeedViewModel.movePlayQueueEntryImpl(from: Int, to: Int) {
    if (playQueueModel.move(from, to)) afterPlayQueueMutation()
}

/** 置顶一条（按条目 id） */
internal fun FeedViewModel.movePlayQueueEntryToTopImpl(entryId: String) {
    if (playQueueModel.moveToTop(entryId)) afterPlayQueueMutation()
}

/** 跳过开关（播放排除可恢复） */
internal fun FeedViewModel.togglePlayQueueSkipImpl(entryId: String) {
    if (playQueueModel.toggleSkip(entryId)) afterPlayQueueMutation()
}

/** 切顺序档（chips；同档省一次落盘） */
internal fun FeedViewModel.setPlayQueueOrderImpl(mode: PlayQueueOrder) {
    if (playQueueModel.setOrder(mode)) afterPlayQueueMutation()
}

/** 清空队列（断点随条目消亡一并清账） */
internal fun FeedViewModel.clearPlayQueueImpl() {
    if (playQueueModel.clear()) afterPlayQueueMutation()
}

/**
 * 变更统一收尾：发布活跃台实况（QueueScreen 订阅）+ 活跃台同步进台列表底账
 * （R391：以模型当前态替换该台——一切一期 ops 由此自动同步多台）+ 发布台列表
 * （updatedAt 降序=「最近动的在前」）+ 投落盘串流（全量台列表态，v2 档）。
 * 串流 = extraBufferCapacity=1 + DROP_OLDEST（conflate 语义：磁盘只关心最新态，
 * 高频断点记账下中间态自然去重）；单收集者串行写盘（init 块）→ 写序不可能乱，
 * 末笔恒最新（较 ListenQueue 裸 launch 的乱序窗口收敛）；写失败静默——下笔
 * 落盘覆盖同内容，冷启动丢最末笔的代价=断点略旧，可接受（KDoc 注明）。
 * 哨兵态（无活跃台）：只发布不落盘变化（stations 已是底账原样）。
 */
internal fun FeedViewModel.afterPlayQueueMutation() {
    _playQueue.value = playQueueModel.queue
    val aid = activePlayStationId
    if (aid != null) {
        val idx = playStations.indexOfFirst { it.id == aid }
        if (idx >= 0) {
            playStations = playStations.toMutableList().also { it[idx] = playQueueModel.queue }
        }
    }
    publishPlayStations()
}

/** 发布台列表（显示序=updatedAt 降序）+ 投落盘串流（底账序=持久化序） */
private fun FeedViewModel.publishPlayStations() {
    _playQueueStations.value = playStations.sortedByDescending { it.updatedAt }
    _playQueuePersist.tryEmit(PlayStationsState(playStations, activePlayStationId))
}

/**
 * 入单自动开默认台（新装空库的第一条入单动作）：无活跃台（activeId null/失联）
 * → 造一台「播放队列」（一期体感连续——一期单队列时代入单=进唯一队列）。
 * 上限拒绝静默返回（调用方 enqueue 的幂等返回继续走，极端场景=单不入）。
 */
private fun FeedViewModel.ensureActiveStationForEnqueue() {
    if (activePlayStationId != null && playStations.any { it.id == activePlayStationId }) return
    if (playStations.size >= PlayQueueModel.MAX_STATIONS) return
    appendStation(PlayQueue(
        id = newPlayStationId(),
        name = PlayQueue.DEFAULT_NAME,
        updatedAt = System.currentTimeMillis(),
    ))
}

/** 新台直入（底账尾插+设活跃+模型装载+发布+落盘；调用方负责上限门） */
private fun FeedViewModel.appendStation(station: PlayQueue) {
    playStations = playStations + station
    activePlayStationId = station.id
    playQueueModel.replaceAll(station)
    _playQueue.value = playQueueModel.queue
    publishPlayStations()
}

/**
 * 新建播放台（台列表页「新建」动作）：空台入列+设为活跃（编辑页直接编辑它）。
 * 返回 false = 台数已达上限（UI Toast 引导清理）或名字空（trim 后）。
 */
internal fun FeedViewModel.createPlayStationImpl(name: String): Boolean {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return false
    if (playStations.size >= PlayQueueModel.MAX_STATIONS) return false
    // 切台 flush：旧活跃台最末笔先同步进底账（在途窗收窄判词 r391_notes §2.3）
    afterPlayQueueMutation()
    appendStation(PlayQueue(
        id = newPlayStationId(),
        name = trimmed,
        updatedAt = System.currentTimeMillis(),
    ))
    return true
}

/**
 * 重命名播放台（台卡长按菜单）：列表改名+updatedAt 推进（改名=「动过」，
 * 显示序跟随）；若=活跃台，模型同步改名（replaceAll 改名副本——replaceAll
 * 即整队列替换装载口，冷启动装载同口）。
 */
internal fun FeedViewModel.renamePlayStationImpl(id: String, name: String): Boolean {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return false
    val idx = playStations.indexOfFirst { it.id == id }
    if (idx < 0) return false
    playStations = playStations.toMutableList().also {
        it[idx] = it[idx].copy(name = trimmed, updatedAt = System.currentTimeMillis())
    }
    if (activePlayStationId == id) {
        playQueueModel.replaceAll(playStations[idx])
        _playQueue.value = playQueueModel.queue
    }
    publishPlayStations()
    return true
}

/**
 * 删除播放台（台卡长按菜单+二次确认）：列表移除；若删的是活跃台→活跃台切
 * 列表第一台（或哨兵空模型——台列表空=诚实空态）；该台断点随实体消亡
 * （一期 clear 同语义，数据层无残留）。
 */
/**
 * 设置接棒台(R392 #5,台卡行尾「接棒」口):列表改 chainTo;若=活跃台,模型
 * replaceAll 同步(rename 同款模式)。**不推进 updatedAt**——接棒配置不改台的
 * 内容面,台序稳定=CHAIN_NEXT 解析序稳定判词(r392b_notes §2.4;显示序=
 * updatedAt 降序,配置动作若动序则「下一台」指向漂移)。
 * 入参防御:指定台 id 必须在列表(配置期即拒失联 id——读侧虽可容失联,配置
 * 口给用户的只有真实台);null(不接)/CHAIN_NEXT(下一台哨兵)恒合法。
 */
internal fun FeedViewModel.setPlayStationChainImpl(stationId: String, chainTo: String?): Boolean {
    val idx = playStations.indexOfFirst { it.id == stationId }
    if (idx < 0) return false
    if (chainTo != null && chainTo != PlayQueue.CHAIN_NEXT &&
        playStations.none { it.id == chainTo }
    ) return false
    if (playStations[idx].chainTo == chainTo) return true // 幂等:同值不落盘
    playStations = playStations.toMutableList().also {
        it[idx] = it[idx].copy(chainTo = chainTo)
    }
    if (activePlayStationId == stationId) {
        playQueueModel.replaceAll(playStations[idx])
        _playQueue.value = playQueueModel.queue
    }
    publishPlayStations()
    return true
}

internal fun FeedViewModel.deletePlayStationImpl(id: String): Boolean {
    if (playStations.none { it.id == id }) return false
    playStations = playStations.filterNot { it.id == id }
    if (activePlayStationId == id) {
        val next = playStations.firstOrNull()
        activePlayStationId = next?.id
        playQueueModel.replaceAll(next ?: PlayQueue())
        _playQueue.value = playQueueModel.queue
    }
    publishPlayStations()
    return true
}

/**
 * 切活跃台（不开单——台卡「编辑」入口用：QueueScreen 编辑的=活跃台，先进
 * 编辑页先切台）。切台 flush：旧活跃台最末笔先同步进底账（断点记账在途笔
 * 不随切台丢失）；目标台不存在（列表换代）返回 false（诚实，不硬切）。
 * 已是活跃台=幂等 true。
 */
internal fun FeedViewModel.editPlayStationImpl(stationId: String): Boolean {
    val target = playStations.firstOrNull { it.id == stationId } ?: return false
    if (playQueueModel.queue.id == target.id) return true
    // 切台 flush：旧活跃台最末笔先入底账（在途窗收窄判词 r391_notes §2.3）
    afterPlayQueueMutation()
    activePlayStationId = target.id
    playQueueModel.replaceAll(target)
    _playQueue.value = playQueueModel.queue
    publishPlayStations()
    return true
}

/**
 * 一键开台（台卡点击）：切活跃台 → 一期开单链 openPlayQueueImpl 零改全继承
 * （快照语义/HEAT 查表/置题 sourceTitle=台名/断点 6s 有界跳回/迟到守卫）。
 * 目标台不存在（列表换代）静默返回（诚实，不硬开）。
 */
internal fun FeedViewModel.openPlayStationImpl(stationId: String, fromStart: Boolean) {
    if (!editPlayStationImpl(stationId)) return
    android.util.Log.i(
        "R391",
        "开台:台数=${playStations.size} 条目=${playQueueModel.queue.entries.size} " +
            "断点=${if (playQueueModel.queue.resumeKey != null) "有" else "无"}",
    )
    openPlayQueueImpl(fromStart)
}

/** 台 id 生成：qs_<时间戳>_<随机段>（一期 newEntryId 同款口径，本机唯一） */
private fun newPlayStationId(): String =
    "qs_${System.currentTimeMillis()}_${(1000..9999).random()}"

// ---------------- R391 #7 今日单：生成接线（原料快照→纯函数→覆盖入列→开台） ----------------

/**
 * 生成今日台（播放台列表「生成今日单」钮：一键生成+直接开台）。
 *
 * 原料快照（IO 协程，r391_notes §3.2）：
 *  - channelWeights = [p2ChannelWeights]（「当前口味画像的统一出口」——与混排/
 *    配额/发现页同一份画像，IO 线程纪律；失败静默回落空表=双侧空→诚实空态）；
 *  - titles = channels 覆盖订阅拼表（L2609 先例，内存快照）；
 *  - favorites / watchedKeys = store 账（内存缓存命中零盘 IO）；
 *  - seed / nowMs 现取（跨次生成重洗=每次生成新单）。
 *
 * **诚实空态**：buildTodayQueue 返回 null（双侧原料皆空）→ 置
 * [_todayQueueEmptyHint] nonce（UI 层 Toast 引导——core/feed 域零 Toast 判词）
 * + 探针行；不落空台不编造。
 *
 * **今日台覆盖语义**（r391_notes §3.2 判词）：列表中 name=今日台的台→
 * entries/断点/updatedAt 全替换（重新生成=新单，旧断点失效；续播今日台=
 * 点台卡开台不走生成钮）；不存在→新增（满员时淘汰最久未动的台——今日台
 * 优先保=核心体验判词）。用户把今日台改名=普通台不再覆盖（name=识别键）。
 * 生成后设为活跃台→一期开单链开台（今日单新断点 null=从头播）。
 */
internal fun FeedViewModel.generateTodayStationImpl() {
    viewModelScope.launch(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // 原料快照（全 IO 纪律：p2ChannelWeights IO 调用；store 读走内存缓存）
        val weights = runCatching { p2ChannelWeights() }.getOrDefault(emptyMap())
        val titles = todayChannelTitles()
        val favorites = runCatching { store.loadFavorites() }.getOrDefault(emptyList())
        val watched = runCatching { store.loadSeenIdentities().first }.getOrDefault(emptySet())
        val generated = buildTodayQueue(
            TodayTasteInput(
                channelWeights = weights,
                channelTitles = titles,
                favorites = favorites,
                watchedKeys = watched,
                nowMs = now,
                seed = now,
            ),
        )
        if (generated == null) {
            // 诚实空态：原料缺（新用户/无历史）——探针计数口径+UI nonce 引导
            android.util.Log.i(
                "R391",
                "今日台生成空态:权重表=${weights.size} 收藏=${favorites.size}（原料缺,不编造）",
            )
            withContext(Dispatchers.Main) { _todayQueueEmptyHint.value += 1 }
            return@launch
        }
        val exploreCount = generated.entries.count { it is PlayQueueEntry.Channel }
        val favoriteCount = generated.entries.size - exploreCount
        android.util.Log.i(
            "R391",
            "今日台生成:探索=${exploreCount}台 重温=${favoriteCount}条 " +
                "总=${generated.entries.size}（计数口径,零频道名）",
        )
        withContext(Dispatchers.Main) {
            // 覆盖/新增今日台（判词见 KDoc；主线程底账纪律）
            // R402 真缺陷修（R401 V65-③ 抓实）：旧写法「原位替换后再无条件尾追」
            // =同 id 双插 → LazyColumn 重复 key 崩溃 + 毒态落盘启动循环。修式=
            // upsertTodayStation 纯函数单口（滤除全部旧今日台后单次追加——毒态
            // 重复条自愈；满员淘汰最久未动；今日台优先保判词沿续）。
            playStations = upsertTodayStation(playStations, generated)
            activePlayStationId = generated.id
            playQueueModel.replaceAll(generated)
            _playQueue.value = playQueueModel.queue
            publishPlayStations()
            // 直接开台（今日单新断点 null=从头播；一期开单链全继承）
            openPlayQueueImpl(fromStart = false)
        }
    }
}

/** 今日单的频道标题拼表（channels 覆盖订阅——L2609 先例；内存快照零 IO） */
private fun FeedViewModel.todayChannelTitles(): Map<Long, String> {
    val map = HashMap<Long, String>()
    _subscriptions.value.forEach { if (it.title.isNotBlank()) map[it.id] = it.title }
    _channels.value.forEach { if (it.title.isNotBlank()) map[it.id] = it.title }
    return map
}

/**
 * 开单（队列页顶栏双动作：播放=断点续播 / 从头播=清断点）。
 *
 * 线程语义（openPlaylist/openBlindBox 同款）：主线程收调用 → 模型快照（模型
 * 主线程纪律）→ IO 查表（HEAT 档成员数，channelMemberCountsForSort 零网络）
 * → 主线程切源（_mediaTypeTab=-1 内容分区归「全部」——片单条目同款，图文/
 * 音频不该被 Feed tab 滤掉；不走 setMediaTypeTab——它先对旧源 refresh 浪费
 * 一轮拉取）→ 断点跳回 6s 有界等待。
 *
 * 快照语义：开单时刻的队列装配（播放中编辑队列/切档不改在播流，下次开单
 * 生效——openPlaylist 同款判词）；RANDOM 种子=会话级（每次开单重洗）。
 * 空单静默返回（UI 侧条数守卫，openPlaylist 同款兜底口径）。
 *
 * R398 增量两口（r398_notes §2.2③/§3.2；**默认路径一期行为逐字零变**）：
 *  - #9③ 离线按单播挂点：plan 过 [applyStationOfflinePlan]（Single 段命中
 *    下载台账→playUri 换本地绝对路径；无记录原样）；
 *  - #10 [planFromEntryId]（默认 null 不剪）：语义跳转段前缀剪除
 *    [dropPlanPrefixBefore]——匹配段前移首页=深位跳播保落（断点 6s 跳回链
 *    复用落点）；调用方 FeedQueueJumpPart（setResume 先把跳播落点写成
 *    断点→本函数跳回落到匹配条目）。
 */
internal fun FeedViewModel.openPlayQueueImpl(fromStart: Boolean, planFromEntryId: String? = null) {
    // 从头播=清断点（本会话开单不再跳回；落盘随串流——R391 起走统一收尾同步台列表）
    if (fromStart && playQueueModel.resetResume()) {
        afterPlayQueueMutation()
    }
    val queue = playQueueModel.queue
    val resumeKey = if (fromStart) null else queue.resumeKey
    viewModelScope.launch(Dispatchers.IO) {
        // HEAT 档数据源：成员数查表（R371 channelMemberCountsForSort 同源零网络；
        // 查表失败静默回落空表=未知沉底诚实降级，开单不被账面卡死）
        val counts = if (queue.orderMode == PlayQueueOrder.HEAT) {
            runCatching { channelMemberCountsForSortImpl(playQueueChatIds(queue)) }
                .getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
        val seed = System.currentTimeMillis() // RANDOM 会话级种子（不落盘=每次开单重洗）
        // R398 两口（一期开单链语义零变判词见函数 KDoc）：
        //  ① #10 段前缀剪除（planFromEntryId=null 原样）→② #9③ 离线按单播挂点
        // （Single 段命中下载台账换本地绝对路径 playUri；无记录/无单段原样）
        val plan = applyStationOfflinePlan(
            dropPlanPrefixBefore(buildPlayPlan(queue, counts, seed), planFromEntryId)
        )
        if (plan.isEmpty()) return@launch // 空单守卫（UI 侧已守卫，这里兜底）
        val src = PlayQueueFeedSource(gateway, plan, queue.orderMode, seed)
        withContext(Dispatchers.Main) {
            _mediaTypeTab.value = -1
            // feed 顶栏标题（V 票①口径：队列名「播放队列」；FeedTrackCachePart
            // L167 的 VM 侧置题同先例——MainActivity 导航置题不经过本路径）
            sourceTitle.value = queue.name
            switchSource(src)
            android.util.Log.i(
                "R387",
                "开单:段=${plan.size} 档=${queue.orderMode.name} " +
                    "断点key尾=${resumeKey?.takeLast(12) ?: "无"}",
            )
        }
        if (resumeKey == null) return@launch
        // 断点跳回：等首页含该 key（6s 有界——队列建源含 IO 查表慢一拍，
        // contextJumpKey 6s 同口径放宽；落空=诚实回顶停在自然首条）
        val landed = withTimeoutOrNull(6_000) {
            _items.first { list -> list.any { it.key == resumeKey } }
        }
        withContext(Dispatchers.Main) {
            // 迟到定位守卫（代次纪律同 loadMore L1700）：等待窗内已被切走 → 作废
            if (source !== src) {
                android.util.Log.i("R387", "断点跳回作废:等待窗内已切源")
                return@withContext
            }
            val idx = _items.value.indexOfFirst { it.key == resumeKey }
            if (landed != null && idx >= 0) {
                if (_currentIndex.value != idx) _currentIndex.value = idx
                _resumeScroll.tryEmit(resumeKey)
                android.util.Log.i(
                    "R387",
                    "断点跳回:idx=$idx key尾=${resumeKey.takeLast(12)}",
                )
            } else {
                android.util.Log.i(
                    "R387",
                    "断点跳回落空:诚实回顶（key尾=${resumeKey.takeLast(12)}）",
                )
            }
        }
    }
}

/**
 * 队列级断点记账（onPage 钩子调用——FeedViewModel onPage 内三行刀：
 * `if (item != null && source is PlayQueueFeedSource) notePlayQueueResume(item)`，
 * 同包扩展直调=settleWatchEvent 同款形态）。
 * 队列专用通道：不打扰 watch/history 既有账（r387_notes §3.3 判词）。
 * 同条不重写（setResume 变更位门）；反查失联（key 不在任何段——队列已编辑
 * 代换）跳过本轮记账。
 */
internal fun FeedViewModel.notePlayQueueResume(item: VideoItem) {
    val src = source as? PlayQueueFeedSource ?: return // 切源守卫：迟到页不记账
    val entryId = src.entryIdOf(item) ?: return // 反查失联：跳过（诚实，不硬记）
    if (playQueueModel.setResume(entryId, item.key)) afterPlayQueueMutation()
}

/** 队列条目的来源频道键全集（HEAT 档查表入参；含跳过条目=多查无害） */
private fun playQueueChatIds(queue: PlayQueue): Set<Long> = buildSet {
    for (e in queue.entries) {
        when (e) {
            is PlayQueueEntry.Channel -> add(e.chatId)
            is PlayQueueEntry.Item -> add(e.item.chatId)
        }
    }
}
