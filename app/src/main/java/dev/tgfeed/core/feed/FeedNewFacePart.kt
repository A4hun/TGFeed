package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

// ---------------- R98 每页新面孔保底（分页层硬保证：本页没有就不算完） ----------------

/** R98 新面孔名册快照：拍摄时刻 + 适用 tab + 频道 id 有序表（最久未露面在前） */
internal data class NewFaceRoster(val atMs: Long, val tab: Int, val ids: List<Long>)

/**
 * R98 "新面孔"定义与名册（每页保底的供给池）：
 * 新面孔频道 = 用户最近没刷到过的频道——曝光账（channelExposureLedger.lastPooledAt，
 * 跨会话入池口径）与观看账（watch_events 最近观看时刻）都最久没动静、且经 R92
 * 内容分层确认"有货"（深度索引确有内容：视频 tab 要有视频，全部 tab 任一类型即可；
 * 确认无货的空频道不进名册——没视频的频道不塞进来）。已屏蔽频道不进名册。
 * 最近露面时刻 = max(上次入池, 上次观看, 本会话已露脸)，升序取前
 * [NEW_FACE_ROSTER_SIZE] 个（时刻并列按 chatId 升序，与 R90 同口径确定性）。
 * 索引未建/查询失败返回空表 = 本功能整体静默缺省（绝不为它建库/开库）。
 */
internal fun FeedViewModel.newFaceRoster(tab: Int): List<Long> {
    val now = System.currentTimeMillis()
    newFaceRosterCache
        ?.takeIf { it.tab == tab && now - it.atMs < INDEXED_MEDIA_TIERS_TTL_MS }
        ?.let { return it.ids }
    // R92 内容分层快照（indexedMediaTier：条数 > 0 才进 map = 确认有货）
    val hasContent: Set<Long> = when (tab) {
        0 -> indexedMediaTier(0)?.first?.keys ?: return emptyList()
        else -> { // 全部 tab：视频/图文/音频任一类型有货即可
            val u = HashSet<Long>()
            for (t in 0..2) u += indexedMediaTier(t)?.first?.keys ?: return emptyList()
            u
        }
    }
    if (hasContent.isEmpty()) return emptyList()
    val blocked = _blockedChannels.value
    val lastPooled = channelExposureLedger.lastPooledAt()
    val lastWatch = HashMap<Long, Long>()
    for (e in store.loadWatchEvents()) {
        if (e.ts > (lastWatch[e.chatId] ?: 0L)) lastWatch[e.chatId] = e.ts
    }
    val shown = synchronized(newFaceLock) { HashMap(newFaceShownAt) }
    val roster = hasContent.asSequence()
        .filter { it !in blocked }
        .map { id ->
            id to maxOf(lastPooled[id] ?: 0L, lastWatch[id] ?: 0L, shown[id] ?: 0L)
        }
        .sortedWith(compareBy({ it.second }, { it.first }))
        .take(NEW_FACE_ROSTER_SIZE)
        .map { it.first }
        .toList()
    newFaceRosterCache = NewFaceRoster(now, tab, roster)
    return roster
}

/**
 * R98 每页新面孔保底（修"保底只按轮：窗口里有、本页未必有"——R92 的保底坑位保的是
 * 轮换窗口，窗口进了不代表每页都有；本分页层硬保证：每批产出都按配额留足新面孔频道
 * 条目，本页自然命中不足就从名册补到有，本页没有就不算完）：
 *
 *  - 适用面与历史回溯同口径：仅聚合源（Aggregate/WeightedAggregate）+ 视频/全部 tab；
 *    单频道/收藏/稍后/历史/搜索/合集/订阅等语义明确或单频道源跳过（不污染）；
 *  - 配额：⌊页大小 × [NEW_FACE_MAX_RATIO]⌋ 钳进 [[NEW_FACE_PER_PAGE_MIN],
 *    [NEW_FACE_PER_PAGE_MAX]]——典型 ~20 条页 = 3-4 条（15-20%），主体仍是兴趣；
 *  - 自然命中先抵配额（排序链本就可能把名册频道排进页），不足部分经深度索引从名册
 *    频道抽样补齐（供给链与历史回溯同一条：sampleRandom 只读、playUri=null 点播现取、
 *    封面走消息反解；索引未建/失败静默不补，绝不影响正常刷）；
 *  - 抽样与过滤：过采样 [NEW_FACE_OVERSAMPLE] 倍、至多 [NEW_FACE_SAMPLE_ROUNDS] 轮
 *    （首轮欠补放大再抽）→ key 复查 + [pass] 同口径过滤
 *    （屏蔽/不感兴趣/永久指纹/已看/筛选/类型开关/tab 全守）→ 优先每频道一条
 *    （一页多面孔：本会话未露脸 > 已露脸，页内未出现 > 已出现，同档守名册序）；
 *  - 插入：逐条随机位插到页头 [NEW_FACE_HEAD_PROTECT] 条之后——头屏留给兴趣主序
 *    最强内容，新面孔保底但不抢头条；
 *  - 露脸账：本页名册频道（自然命中与补入都算）记进会话级 [newFaceShownAt]，
 *    驱动后续页轮换与名册重算的"最久未露面"口径；
 *  - 观测：每页一行"本页新面孔：N 个（频道尾号…）"（含配额/自然命中/补入条数）。
 */
internal fun FeedViewModel.ensureNewFacesInPage(
    src: FeedSource,
    tab: Int,
    page: List<VideoItem>,
    knownKeys: Set<String>,
    pass: (VideoItem) -> Boolean,
): List<VideoItem> {
    if (src !is AggregateFeedSource && src !is WeightedAggregateFeedSource) return page
    if (tab != 0 && tab != -1) return page // 只在视频/全部 tab 保底（与历史回溯同口径）
    if (page.isEmpty()) return page
    // R158 首拉提速①：本源生命周期的第一个内容页跳过新面孔保底。R155 体检实锤冷启动
    // 时 ensureNewFacesInPage 对 2.0GB 深索引的 sampleRandom 冷抽样要 26s，快车道 2.9s
    // 返回的成果被锁在同一条串行管线里（首拉到货 29~31s）——首屏的目标是"快出"，
    // 新面孔多样性对首屏无价值，第二页起照常按配额保底。backfillPages 由 loadMore
    // 每处理一页 +1、切源/刷新清零（与列表同生命周期），调用点处 ==1 即首个内容页
    if (backfillPages <= 1) {
        android.util.Log.i(
            "FeedViewModel",
            "首屏跳过新面孔保底（R158：2GB 深索引冷抽样曾拖首拉 26s；第二页起照常保底）",
        )
        return page
    }
    val roster = newFaceRoster(tab)
    if (roster.isEmpty()) return page // 索引未建/暂无确认有货频道：静默直通
    val rosterIds = roster.toHashSet()
    val quota = (page.size * NEW_FACE_MAX_RATIO).toInt()
        .coerceIn(NEW_FACE_PER_PAGE_MIN, NEW_FACE_PER_PAGE_MAX)
    val natural = page.filter { it.chatId in rosterIds }
    var result = page
    var injected = emptyList<VideoItem>()
    val need = quota - natural.size
    if (need > 0 && deepIndex.hasIndex()) {
        val pageChannels = page.mapTo(HashSet()) { it.chatId }
        val shown = synchronized(newFaceLock) { newFaceShownAt.keys.toSet() }
        // 供给频道序：本会话未露脸 > 已露脸；页内未出现 > 已出现；同档守名册序（最久未露面优先）
        val order = roster.withIndex().sortedWith(
            compareBy(
                { if (it.value in shown) 1 else 0 },
                { if (it.value in pageChannels) 1 else 0 },
                { it.index },
            ),
        ).map { it.value }
        val excludeKeys = knownKeys + page.map { it.key }
        // 抽样至多 [NEW_FACE_SAMPLE_ROUNDS] 轮：首轮被过滤链（类型/已看/筛选）吃到欠补时，
        // 把已抽中的 key 并进排除集放大再抽一轮，尽量补满配额（供给链只读，欠补不报错）
        val sampled = ArrayList<VideoItem>(need * NEW_FACE_OVERSAMPLE)
        val sampledKeys = HashSet<String>()
        for (round in 0 until NEW_FACE_SAMPLE_ROUNDS) {
            val want = need * NEW_FACE_OVERSAMPLE - sampled.size
            if (want <= 0) break
            val batch = deepIndex.sampleRandom(order, excludeKeys + sampledKeys, want)
            for (item in batch) {
                if (item.key in excludeKeys || !sampledKeys.add(item.key)) continue // key 复查
                if (pass(item)) sampled += item // 同口径过滤链
            }
            if (sampled.size >= need) break
        }
        val byChannel = sampled.groupBy { it.chatId }
        val out = ArrayList<VideoItem>(need)
        // 第一遍每频道至多一条（面孔多样性优先）；频道不够第二遍再补同频道余条
        for (cid in order) {
            if (out.size >= need) break
            byChannel[cid]?.firstOrNull()?.let { out += it }
        }
        if (out.size < need) {
            val used = out.mapTo(HashSet()) { it.key }
            for (cid in order) {
                if (out.size >= need) break
                for (item in byChannel[cid].orEmpty()) {
                    if (out.size >= need) break
                    if (item.key !in used) {
                        out += item
                        used += item.key
                    }
                }
            }
        }
        injected = out
        if (injected.isNotEmpty()) {
            val buf = page.toMutableList()
            for (e in injected) {
                val lo = minOf(NEW_FACE_HEAD_PROTECT, buf.size)
                buf.add(lo + kotlin.random.Random.nextInt(buf.size - lo + 1), e)
            }
            result = buf
        }
    }
    // 露脸账：本页名册频道（自然 + 补入）记本会话露脸时刻，驱动后续页轮换
    val faces = result.asSequence()
        .map { it.chatId }
        .filter { it in rosterIds }
        .distinct()
        .toList()
    if (faces.isNotEmpty()) {
        val now = System.currentTimeMillis()
        synchronized(newFaceLock) { for (cid in faces) newFaceShownAt[cid] = now }
    }
    android.util.Log.i(
        "FeedViewModel",
        "本页新面孔：${faces.size} 个（频道尾号 ${faces.joinToString(",") { it.toString().takeLast(6) }}），" +
            "配额 $quota 条，自然命中 ${natural.size} 条，补入 ${injected.size} 条（页 ${result.size} 条）",
    )
    return result
}

/** 网关 recentPoolProvider 回调：最近 2 轮进过池的频道并集（本轮抽样排除/降权对象） */
internal fun FeedViewModel.rotationRecentIds(): Set<Long> = synchronized(rotationLock) {
    rotationPools.flatten().toHashSet()
}

/** 网关 poolReporter 回调：记录本轮进池频道（只留最近 2 轮——命中记忆的频道
 *  最多轮休 2 轮就重新 eligible，高权重频道隔轮即归，不是永别）。
 *  R75-C：同一份进池名单同步记进跨会话频道曝光账（保底车道的数据源） */
internal fun FeedViewModel.recordPoolRound(ids: Set<Long>) {
    if (ids.isEmpty()) return
    // R82 保底名册（必须在 recordPoolEntries 记账之前读快照——记账后全池时刻
    // 齐刷成现在，保底/加权两道再不可分）。R92 起媒体路径名册以网关显式回报为准
    //（保底选池含内容分层，复算口径对不上）；无显式名册（fetchAggregated 路径）
    // 维持原复算：池内按"上次入池时刻"升序取前 GUARANTEED_POOL_SLOTS 个，时刻并列键
    // chatId 升序。名册注入排序层露头挂接点（GuaranteedPoolHolder），由
    // rankWeightedPage 给保底频道露头加权；池子不满一池 = 全量直取无轮换，清空名册
    val lastPooled = channelExposureLedger.lastPooledAt()
    val explicitRoster = pendingMediaGuaranteedRoster
    pendingMediaGuaranteedRoster = null
    val guaranteed = when {
        explicitRoster != null -> explicitRoster.toList()
        ids.size >= AGGREGATE_POOL_CAPACITY ->
            ids.sortedWith(compareBy({ lastPooled[it] ?: 0L }, { it })).take(GUARANTEED_POOL_SLOTS)
        else -> emptyList()
    }
    GuaranteedPoolHolder.update(guaranteed.toSet())
    if (guaranteed.isNotEmpty()) {
        android.util.Log.i(
            "FeedViewModel",
            "R82 保底名册：本轮 ${guaranteed.size} 频道（尾号 " +
                guaranteed.joinToString(",") { it.toString().takeLast(6) } + "）",
        )
    }
    synchronized(rotationLock) {
        rotationPools.addLast(ids)
        while (rotationPools.size > 2) rotationPools.removeFirst()
    }
    // 网关 poolReporter 在 IO 线程被调用，账本读写与落盘均线程自闭环
    channelExposureLedger.recordPoolEntries(ids, System.currentTimeMillis())
}
