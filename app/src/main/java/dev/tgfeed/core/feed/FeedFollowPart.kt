package dev.tgfeed.core.feed

import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.viewModelScope
import dev.tgfeed.core.prefs.AppPrefs
import dev.tgfeed.core.store.LocalStore
import dev.tgfeed.core.store.Subscription
import dev.tgfeed.core.td.ChannelBrief
import dev.tgfeed.core.td.FeedRequest
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import org.json.JSONObject

// ---------------- 订阅管理 ----------------

/** 订阅频道（已订阅则忽略）；新订阅的 lastSeen=0，首次刷追更时只取最新一页（已读基线由真看推进，见 SubscriptionFeedSource） */
internal fun FeedViewModel.addSubscriptionImpl(c: ChannelBrief) {
    viewModelScope.launch(Dispatchers.IO) {
        // 先重读磁盘再合并：SubscriptionFeedSource 刷过追更后 lastSeen 可能已推进，
        // 直接用内存旧列表会回滚追更进度
        val current = runCatching { store.loadSubscriptions() }.getOrDefault(emptyList())
        if (current.any { it.chatId == c.id }) return@launch
        val newList = current + Subscription(c.id, c.title, 0L)
        runCatching { store.saveSubscriptions(newList) }
            .onFailure { _error.value = "订阅保存失败：${it.message}" }
        _subscriptions.value = newList.map { ChannelBrief(it.chatId, it.title, null) }
        // R300 同步记账：订阅=加入戳+标脏（水位/标题随快照上云）
        multiSync?.noteAdded(MultiSync.Domain.SUBS, c.id.toString())
        maybeAutoPushState()
        // R163 B4 / M1：订阅=确定性需求 → 即时评 T0（urgent 优先车道全量深挖；
        // 同协程调用——_subscriptions.value 已更新，评估读到最新订阅态；分档关零开销）
        runCatching { onSubscriptionTierRefreshImpl(c.id) }
    }
}

/** 取消订阅 */
internal fun FeedViewModel.removeSubscriptionImpl(chatId: Long) {
    viewModelScope.launch(Dispatchers.IO) {
        val current = runCatching { store.loadSubscriptions() }.getOrDefault(emptyList())
        val newList = current.filterNot { it.chatId == chatId }
        if (newList.size == current.size) return@launch // 本来就没订阅
        runCatching { store.saveSubscriptions(newList) }
            .onFailure { _error.value = "订阅保存失败：${it.message}" }
        _subscriptions.value = newList.map { ChannelBrief(it.chatId, it.title, null) }
        // R300 同步记账：退订=墓碑
        multiSync?.noteRemoved(MultiSync.Domain.SUBS, chatId.toString())
        maybeAutoPushState()
        // R163 B4 / M5：退订=需求消失 → 按剩余 D/R 信号重估（T0 自然回落 T1/T2）
        runCatching { onSubscriptionTierRefreshImpl(chatId) }
    }
}

// ---------------- R66 追更红点 + 低频宝藏频道上浮 ----------------

/**
 * 深度索引单频道统计（[count] 索引条数，[todayCount] 今日更新条数——R68 频道卡
 * 鲜活信号行"今天更了 N 条"的口径，[minDateSec]/[maxDateSec] 条目发布时间秒）
 */
internal data class IndexChannelStat(val count: Int, val todayCount: Int, val minDateSec: Long, val maxDateSec: Long)

/**
 * 追更信号刷新入口（R66）：进 Feed / 索引轮次推进 / 订阅列表就绪时调用。
 * 一次只读聚合走查刷新频道统计快照（红点判定与低频上浮共用），随后重估红点。
 * 全程 IO 协程、纯本地只读、零网络；库未建/查询失败静默跳过（红点保持原状）。
 * R318：聚合走 date 索引覆盖分片（IndexStatsWalk.kt——旧全表 GROUP BY 在 5.8M 行
 * 真库分钟级且每轮叠扫）；走查在途单飞跳过不叠扫（红点等下一轮触发再刷）。
 */
internal fun FeedViewModel.refreshFollowSignalImpl(reason: String) {
    if (indexStatsWalkInFlight) return // 单飞：走查在途=本轮触发跳过，不叠扫
    indexStatsWalkInFlight = true
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val stats = runCatching { loadIndexChannelStats(isCancelled = { !isActive }) }
                .getOrNull() ?: return@launch
            indexChannelStats = stats
            evalFollowRedDot(stats)
            refreshChannelCardMeta(stats) // R68：频道卡元数据与追更信号同机刷新
            android.util.Log.d(
                "FeedViewModel",
                "追更信号刷新（$reason）：索引频道 ${stats.size} 个，红点=${_followHasNew.value}",
            )
        } finally {
            indexStatsWalkInFlight = false
        }
    }
}

/**
 * 从深度索引库读全频道统计快照（chatId → 条数/最早/最新发布时间）。
 * R318 起聚合降档为 [aggregateChatStatsChunked]（idx_items_date 覆盖索引 date 域
 * 分片走查，5.8M 行真库分钟级→秒级；窗口计数随流附带、本口弃用不取）——旧「全表
 * GROUP BY 回表」形状保留为索引缺席/走查失败的兜底链（行为与 R318 前逐字一致）。
 * DeepSearchIndex 不在可改清单（只读其库文件不算改），WAL 模式下只读连接与爬取
 * 写并发安全；库文件不存在（从未建索引）返回空表，绝不为统计去建库/开写库。
 * IO 线程调用。
 *
 * @return 快照；null=走查被取消（调用方 [refreshFollowSignalImpl] 静默跳过保旧快照）
 */
internal fun FeedViewModel.loadIndexChannelStats(
    isCancelled: () -> Boolean = { false },
): Map<Long, IndexChannelStat>? {
    val todayStartSec = localMidnightSec()
    val windowSinceSec = System.currentTimeMillis() / 1000L - RECENT_WINDOW_DAYS * 24 * 3600L
    val agg = try {
        aggregateChatStatsChunked(
            dbFile = tierDbFile(),
            todayStartSec = todayStartSec,
            windowSinceSec = windowSinceSec,
            isCancelled = isCancelled,
        )
    } catch (e: Throwable) {
        null // 打不开库等硬失败=本轮跳过保旧快照（同旧 runCatching→getOrNull 口径）
    } ?: return null // null=取消或硬失败：静默跳过（红点保持原状）
    return agg.stats
}

// ---------------- R92 保底选池内容分层（深度索引"有货"快照） ----------------

/**
 * 深索引库文件路径（库文件名与 DeepSearchIndex.DB_NAME("search.db") 同源，其
 * companion 私有引不到，字面量镜像）——R170b 从三处镜像收编为单一出处。
 */
internal fun FeedViewModel.tierDbFile(): File = File(File(app.filesDir, "deepindex"), "search.db")

/** R92 分层快照：拍摄时刻 + mediaType→(chatId→条数) + 索引已爬频道集 */
internal data class IndexedMediaTiers(
    val atMs: Long,
    val byType: Map<Int, Map<Long, Int>>,
    val crawled: Set<Long>,
)

/**
 * R92 保底选池内容分层数据源（网关 poolContentProvider 回调，IO 线程调用）：
 * mediaType → (chatId→索引内该类型条数, 索引已爬频道集)。
 * 语义：条数>0 = 确认有货；不在已爬集 = 未知（索引没爬到，可能有货）；
 * 在已爬集但条数 0 = 确认无货（爬过但没该类型内容）。三层供网关保底选池
 * "有货 > 未知 > 确认无货"——修"保底坑位被无视频文字频道白占，有视频的
 * 归档/文件夹频道排不上号"（实测 117 个进窗频道 35 个历史零产出）。
 * 库未建/查询失败返回 null（网关回退"最久未进窗"单口径），绝不为分层建库/开写库；
 * 5 分钟内存缓存，与清单缓存同节拍。
 *
 * R170 陈值先回 + 后台单飞刷新（榜单 #6 输入响应家族）：全表 GROUP BY 是
 * 2M 行级扫描（进程内冷缓存实测 ~4~10s），旧实现过期后在调用方（快车道种子/
 * 窗口保底分层）同步重扫，把首个 tab 切换的首拉起跑钉住 ~10s。新口径：
 *  - 命中（含 TTL 内）：直回；
 *  - 过期：**陈值先回**（分层是提示层，5 分钟陈度无害），后台单飞刷新；
 *  - 无缓存：单飞同步扫一次（首个消费者付一次扫描成本；VM init 的启动预热
 *    [warmIndexedMediaTiers] 通常已把这付到启动期）。扫描进行中其他调用方
 *    返回 null（各自既有回退路：种子回落快照/订阅、保底回退最久未进窗）。
 */
internal fun FeedViewModel.indexedMediaTier(mediaType: Int): Pair<Map<Long, Int>, Set<Long>>? {
    // R170 磁盘快照优先：内存无缓存先读上次会话的扫描结果（~30KB JSON，毫秒级），
    // 读到即当陈值用（种子/分层要的是「有货频道」这个小时级稳定的事实，跨会话
    // 陈度无害）并照 TTL 口径走陈值先回+后台刷新。首开不再被启动期扫描拖住
    //（实测扫描在冷启动风暴下 15s+ 仍未完成，纯预热不够）。
    if (indexedMediaTiers == null && !tierScanInFlight) {
        val snap = loadTiersFromDisk()
        android.util.Log.i(
            "FeedViewModel",
            "R170 tier 磁盘快照：" + (if (snap != null) "命中（${snap.byType.values.sumOf { it.size }} 条计数，atMs=${snap.atMs}）" else "未命中（走现场扫描链）"),
        )
        snap?.let {
            indexedMediaTiers = it
            refreshIndexedMediaTiersAsync()
        }
    }
    val now = System.currentTimeMillis()
    indexedMediaTiers?.let { cached ->
        if (now - cached.atMs < INDEXED_MEDIA_TIERS_TTL_MS) {
            return cached.byType[mediaType].orEmpty() to cached.crawled
        }
        refreshIndexedMediaTiersAsync()
        return cached.byType[mediaType].orEmpty() to cached.crawled
    }
    if (tierScanInFlight) return null
    tierScanInFlight = true
    try {
        val tiers = scanIndexedMediaTiers()
        if (tiers == null) return null
        indexedMediaTiers = tiers
        return tiers.byType[mediaType].orEmpty() to tiers.crawled
    } finally {
        tierScanInFlight = false
    }
}

/** 过期/无缓存时后台单飞刷新（陈值先回的配套；无陈值时也只扫这一次不叠扫） */
internal fun FeedViewModel.refreshIndexedMediaTiersAsync() {
    if (tierScanInFlight) return
    tierScanInFlight = true
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val tiers = scanIndexedMediaTiers()
            if (tiers != null) indexedMediaTiers = tiers
        } finally {
            tierScanInFlight = false
        }
    }
}

/**
 * R170b tier 快照刷新的预热任务真身（WarmupCoordinator 经 init 提交，错峰窗执行；
 * 取代 R170 的 VM-init 即时预热 warmIndexedMediaTiers——tier 磁盘快照已让首开
 * 消费者毫秒级秒读，init 期抢跑的 2M 行扫描违背「预热低于一切前台路径」）：
 * 单飞标记下重扫一次（陈值先回口径的「回」侧），成功回写内存+磁盘。
 * @return true=扫描成功或他人正在扫（视为已覆盖，不重试）；false=失败可重试
 */
internal fun FeedViewModel.refreshTiersForWarmup(): Boolean {
    if (tierScanInFlight) return true // 单飞：已在扫=预热目标已被覆盖
    // 索引未建：无事可预热（真没库，重试无意义）——与扫描失败（可重试）分开
    if (!tierDbFile().isFile) return true
    tierScanInFlight = true
    try {
        val tiers = scanIndexedMediaTiers()
        if (tiers == null) return false
        indexedMediaTiers = tiers
        return true
    } finally {
        tierScanInFlight = false
    }
}

/** tier 快照真身扫描（2M 行 GROUP BY + 已爬频道集）；库未建/失败返回 null。
 *  R170 扫描成功顺手落盘（tier_snapshot.json）：下次会话首开毫秒级读回陈值，
 *  不再付冷启动风暴下 15s+ 的现场扫描。单飞标记保证同刻只有一路在扫=写不撞车 */
private fun FeedViewModel.scanIndexedMediaTiers(): IndexedMediaTiers? {
    val now = System.currentTimeMillis()
    val dbFile = tierDbFile()
    if (!dbFile.isFile) return null
    val tiers = runCatching {
        val d = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val byType = HashMap<Int, HashMap<Long, Int>>()
            d.rawQuery(
                "SELECT chatId, mediaType, COUNT(*) FROM items GROUP BY chatId, mediaType",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    byType.getOrPut(c.getInt(1)) { HashMap() }[c.getLong(0)] = c.getInt(2)
                }
            }
            // R266 v11 refs 臂（D6 含转发口径；升级窗口期 v10 库无此表——独立容错直通）
            runCatching {
                d.rawQuery(
                    "SELECT chatId, mediaType, COUNT(*) FROM repost_refs GROUP BY chatId, mediaType",
                    null,
                ).use { c ->
                    while (c.moveToNext()) {
                        val m = byType.getOrPut(c.getInt(1)) { HashMap() }
                        val id = c.getLong(0)
                        m[id] = (m[id] ?: 0) + c.getInt(2)
                    }
                }
            }
            val crawled = HashSet<Long>()
            d.rawQuery("SELECT chatId FROM meta", null).use { c ->
                while (c.moveToNext()) crawled += c.getLong(0)
            }
            IndexedMediaTiers(now, byType, crawled)
        } finally {
            runCatching { d.close() }
        }
    }.getOrNull() ?: return null
    saveTiersToDisk(tiers)
    return tiers
}

// ---------------- R170 tier 快照磁盘缓存（跨会话陈值，毫秒级首开） ----------------

/**
 * 磁盘快照文件（~30KB：3 类型×数百频道的计数表）。
 * 位置刻意放 files 根、**不放 deepindex 库目录**：那是 DeepSearchIndex 的受管区——
 * 它的启动门房（legacyJsonFiles + migrateLegacy）历史上会删掉一切「命名非数字
 * chatId」的 .json 文件（实测每冷启删一次 tier_snapshot.json，与预热读赛跑 =
 * 首开快慢随机，run6/7 快 run8/9 慢的根因实锤）。R266 起门房已修（只认
 * `<chatId>.json[.tmp]` 数字命名——各 Job 断点文件不再被误删）；本文件留原址
 * 不搬（路径即既有口径，搬家无收益）。files 根各文件归各主人管，互不干扰。
 */
private fun FeedViewModel.tiersSnapshotFile(): File =
    File(app.filesDir, "tier_snapshot.json")

/** 读磁盘快照；不存在/损坏/解析失败返回 null（调用方继续走现场扫描链） */
private fun FeedViewModel.loadTiersFromDisk(): IndexedMediaTiers? = runCatching {
    val f = tiersSnapshotFile()
    if (!f.isFile) return@runCatching null
    val obj = JSONObject(f.readText())
    val atMs = obj.optLong("atMs", 0L)
    val byTypeObj = obj.optJSONObject("byType") ?: return@runCatching null
    val byType = HashMap<Int, HashMap<Long, Int>>()
    for (typeKey in byTypeObj.keys()) {
        val type = typeKey.toIntOrNull() ?: continue
        val counts = byTypeObj.optJSONObject(typeKey) ?: continue
        val m = HashMap<Long, Int>()
        for (chatKey in counts.keys()) {
            val chat = chatKey.toLongOrNull() ?: continue
            m[chat] = counts.optInt(chatKey)
        }
        byType[type] = m
    }
    val crawled = HashSet<Long>()
    val arr = obj.optJSONArray("crawled")
    if (arr != null) for (i in 0 until arr.length()) crawled += arr.optLong(i)
    if (byType.isEmpty()) return@runCatching null
    IndexedMediaTiers(atMs, byType, crawled)
}.getOrNull()

/** 写磁盘快照（临时文件+原子改名，LocalStore 同款手法）；失败静默（快照是优化不是依赖） */
private fun FeedViewModel.saveTiersToDisk(tiers: IndexedMediaTiers) {
    runCatching {
        val f = tiersSnapshotFile()
        val obj = JSONObject()
        obj.put("atMs", tiers.atMs)
        val byTypeObj = JSONObject()
        for ((type, counts) in tiers.byType) {
            val m = JSONObject()
            for ((chat, n) in counts) m.put(chat.toString(), n)
            byTypeObj.put(type.toString(), m)
        }
        obj.put("byType", byTypeObj)
        obj.put("crawled", org.json.JSONArray(tiers.crawled))
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(obj.toString())
        if (!tmp.renameTo(f)) {
            // 跨文件系统 rename 失败回落复制+删除（同目录同盘通常不走这条）
            tmp.copyTo(f, overwrite = true)
            tmp.delete()
        }
    }
}

/**
 * 红点判定（R66 口径）：任一已订阅（且未屏蔽）频道 索引最新条目时间戳 > lastVisitTs。
 * lastVisitTs 缺省 = 0（从未刷过 = 索引里的内容都算没看过）；
 * 统计快照里没有的频道（索引未爬到）不参与判定。
 */
internal fun FeedViewModel.evalFollowRedDot(stats: Map<Long, IndexChannelStat>) {
    val subs = _subscriptions.value
    if (subs.isEmpty()) {
        if (_followHasNew.value) _followHasNew.value = false
        return
    }
    val visits = followVisits
    val blocked = _blockedChannels.value
    val hitId = subs.firstOrNull { s ->
        if (s.id in blocked) return@firstOrNull false
        val st = stats[s.id] ?: return@firstOrNull false
        st.maxDateSec > (visits[s.id] ?: 0L) / 1000L
    }?.id
    val on = hitId != null
    if (on != _followHasNew.value) {
        _followHasNew.value = on
        android.util.Log.i(
            "FeedViewModel",
            if (hitId != null) "追更红点亮：频道尾号${hitId.toString().takeLast(6)} 索引有未看新内容"
            else "追更红点灭：订阅频道均已看至最新",
        )
    }
}

// ---------------- R68 频道卡元数据（源面板"直达频道"段） ----------------

// R68 频道卡元数据表的 _channelCardMeta/channelCardMeta 声明已上移到 init 块之前
//（init 协程会访问它，声明在后=构造竞态 NPE）——本注释留作锚点。

/**
 * 由索引统计快照 + watch_events 现算每频道最近一次观看时刻，合成频道卡元数据表。
 * 调用点已在 IO 协程（refreshFollowSignal 内部）；watch_events 读侧走内存缓存，
 * 成本是一次内存遍历。覆盖集合取并集（已加入频道 ∪ 订阅 ∪ 索引统计 ∪ 观看记录），
 * UI 按 chatId 取用，缺省 = 无索引数据且从没看过。
 */
internal fun FeedViewModel.refreshChannelCardMeta(stats: Map<Long, IndexChannelStat>) {
    val events = runCatching { store.loadWatchEvents() }.getOrDefault(emptyList())
    val lastWatch = HashMap<Long, Long>()
    for (e in events) {
        if (e.ts > (lastWatch[e.chatId] ?: 0L)) lastWatch[e.chatId] = e.ts
    }
    val ids = HashSet<Long>()
    _channels.value.forEach { ids.add(it.id) }
    _subscriptions.value.forEach { ids.add(it.id) }
    ids.addAll(stats.keys)
    ids.addAll(lastWatch.keys)
    if (ids.isEmpty()) {
        if (_channelCardMeta.value.isNotEmpty()) _channelCardMeta.value = emptyMap()
        return
    }
    val out = HashMap<Long, ChannelCardMeta>(ids.size)
    for (id in ids) {
        val st = stats[id]
        out[id] = ChannelCardMeta(
            indexCount = st?.count ?: 0,
            todayCount = st?.todayCount ?: 0,
            latestSec = st?.maxDateSec ?: 0L,
            lastWatchMs = lastWatch[id] ?: 0L,
        )
    }
    _channelCardMeta.value = out
}

/**
 * 记录订阅频道的本次查看时刻（毫秒）：内存表同步更新 + IO 线程原子落盘，
 * 随后按现有统计快照重估红点（不再查库——快照未变的频道结论不变，红点随点随消）。
 */

internal fun FeedViewModel.markFollowVisited(chatIds: Collection<Long>, nowMs: Long) {
    if (chatIds.isEmpty()) return
    val next = followVisits.toMutableMap()
    var changed = false
    for (id in chatIds) {
        if ((next[id] ?: 0L) < nowMs) {
            next[id] = nowMs
            changed = true
        }
    }
    if (!changed) return
    followVisits = next
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { saveFollowVisits(next) }
        evalFollowRedDot(indexChannelStats)
    }
}

/** 点"订阅追更"chip（R66 需求③）：记录本次查看时刻（全部订阅频道一并记）并清红点 */
internal fun FeedViewModel.noteFollowChipOpenedImpl() {
    markFollowVisited(_subscriptions.value.map { it.id }, System.currentTimeMillis())
}

/** 刷某频道（switchSource 切到单频道源时调用）：订阅频道才记查看时刻（账本只覆盖订阅） */
internal fun FeedViewModel.noteChannelVisitedImpl(chatId: Long) {
    if (_subscriptions.value.none { it.id == chatId }) return
    markFollowVisited(listOf(chatId), System.currentTimeMillis())
}

/** 读追更查看账本（filesDir/[FOLLOW_VISIT_FILE]）；文件缺失/损坏返回空表 */
internal fun FeedViewModel.loadFollowVisits(): Map<Long, Long> {
    val f = File(app.filesDir, FOLLOW_VISIT_FILE)
    if (!f.exists()) return emptyMap()
    val obj = JSONObject(f.readText())
    val visits = obj.optJSONObject("visits") ?: return emptyMap()
    val out = HashMap<Long, Long>()
    val keys = visits.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        val id = k.toLongOrNull() ?: continue // 坏 key 跳过
        val ts = visits.optLong(k, 0L)
        if (ts > 0L) out[id] = ts
    }
    return out
}

/** 原子写追更查看账本：先写 tmp 再 rename（与 InteractionStats 同款，防半截文件） */
internal fun FeedViewModel.saveFollowVisits(map: Map<Long, Long>) {
    val f = File(app.filesDir, FOLLOW_VISIT_FILE)
    val tmp = File(app.filesDir, "$FOLLOW_VISIT_FILE.tmp")
    val visits = JSONObject()
    for ((id, ts) in map) visits.put(id.toString(), ts)
    tmp.writeText(JSONObject().put("v", 1).put("visits", visits).toString())
    if (!tmp.renameTo(f)) { // 个别文件系统 rename 不覆盖既有文件：先删再改名兜底
        runCatching { f.delete() }
        tmp.renameTo(f)
    }
}

// ---- R66 低频宝藏频道上浮（稀缺性加权） ----

/**
 * 发帖频率估计（条/周）：索引条数 × 7 天 / max(首尾条目时间跨度, [SCARCITY_MIN_SPAN_MS])。
 * 跨度地板 7 天防"刚收录的频道一天发 3 条"被除零吹成高频；索引未覆盖的频道无统计，
 * 不参与稀缺判定（没有证据不臆测）。
 */
internal fun FeedViewModel.postsPerWeek(st: IndexChannelStat): Double {
    val spanMs = (st.maxDateSec - st.minDateSec).coerceAtLeast(0L) * 1000L
    return st.count * (7.0 * 24 * 3600 * 1000) / maxOf(spanMs, SCARCITY_MIN_SPAN_MS).toDouble()
}

/** 低频频道集合（索引快照派生，纯内存读）：发帖频率 < [SCARCITY_LOW_POSTS_PER_WEEK] 即入列 */
internal fun FeedViewModel.lowFreqChannelIds(): Set<Long> {
    val stats = indexChannelStats
    if (stats.isEmpty()) return emptySet()
    return stats.filterValues { postsPerWeek(it) < SCARCITY_LOW_POSTS_PER_WEEK }.keys
}

/**
 * R66 稀缺性上浮（VM 页内排序路径，rankWeightedPage 权重表的注入点）：
 * 低频（发帖 <[SCARCITY_LOW_POSTS_PER_WEEK] 条/周）且本页含 [SCARCITY_FRESH_WINDOW_MS]
 * 内新帖的频道，频道权重 ×[SCARCITY_BOOST_FACTOR]（封顶沿用系统权重上限
 * [SESSION_WEIGHT_CAP]）后再进排序机器；高频频道一律不加权。
 *
 * 为什么"明显加权"不伤高频频道曝光：低频频道绝对量小——一个 <2 条/周的频道
 * 一周的贡献抵不上高频频道一天，即便它的新帖全被顶到页前，占走的坑位占比
 * 也可忽略；而它的新帖（稀缺事件）恰是"再不看就沉底"的内容——用零头坑位
 * 换"低频好内容不淹死"，划算。
 *
 * "条目较新"按页判定：权重表是频道粒度的，页内该频道有 ≥1 条 48h 内发布的条目
 * 才上浮（防给低频频道的陈年旧帖加权）。每次命中打一行加权前后对比日志
 * （结构化数字 + 频道尾号，不落频道名）。
 */
internal fun FeedViewModel.applyScarcityBoost(weights: Map<Long, Double>, page: List<VideoItem>): Map<Long, Double> {
    val lowFreq = lowFreqChannelIds()
    if (lowFreq.isEmpty() || page.isEmpty()) return weights
    val nowSec = System.currentTimeMillis() / 1000L
    val freshCutSec = nowSec - SCARCITY_FRESH_WINDOW_MS / 1000L
    // 页内命中：低频频道 → 本页最新条目时间戳（秒）
    val hits = HashMap<Long, Int>()
    for (it in page) {
        if (it.chatId in lowFreq && it.dateEpochSec >= freshCutSec) {
            val cur = hits[it.chatId]
            if (cur == null || it.dateEpochSec > cur) hits[it.chatId] = it.dateEpochSec
        }
    }
    if (hits.isEmpty()) {
        // 诊断（debug 级）：只在"页内有低频频道条目但都不够新"时打一行——
        // 区分"低频内容没进页"与"进了页但全是旧帖"两种形态；页内无低频条目（常态）静默
        val lowInPage = page.count { it.chatId in lowFreq }
        if (lowInPage > 0) {
            android.util.Log.d(
                "FeedViewModel",
                "稀缺上浮未命中：页 ${page.size} 条，页内低频频道条目 $lowInPage 条但无 48h 新帖",
            )
        }
        return weights
    }
    val out = HashMap<Long, Double>(weights)
    for ((id, freshSec) in hits) {
        val before = out[id] ?: EXPLORE_WEIGHT // 零画像频道按探索档打底（与排序链缺省口径一致）
        val after = (before * SCARCITY_BOOST_FACTOR).coerceAtMost(SESSION_WEIGHT_CAP)
        if (after <= before) continue
        out[id] = after
        val st = indexChannelStats[id]
        android.util.Log.i(
            "FeedViewModel",
            "稀缺上浮: 频道尾号${id.toString().takeLast(6)} 权重 " +
                "%.2f→%.2f（发帖 %.1f 条/周 < %.1f，最新帖 %dh 前）".format(
                    before, after,
                    st?.let { postsPerWeek(it) } ?: -1.0,
                    SCARCITY_LOW_POSTS_PER_WEEK,
                    (nowSec - freshSec) / 3600L,
                ),
        )
    }
    return out
}

/**
 * 供 WeightedAggregateFeedSource 构造注入的加分提供者（R66 源内排序路径折算挂接）：
 * 在 [sessionAdjustedBoost]（会话系数/双轨折算）之上，对低频且 48h 内有新帖
 * （索引快照最新条目判定——源内拿不到页内容，用频道级口径）的频道追加
 * +[SCARCITY_SOURCE_BONUS] 加分。折算与 VM 路径对齐：EXPLORE_WEIGHT(0.6)×2.5=1.5
 * ≈ 0.6+0.9，两条路径同一份快照、同一阈值；源内 clamp(0.3, 2.0) 天然封顶。
 * 快照为空（索引未建/未刷新）时原样直通，零开销。
 */
internal fun FeedViewModel.scarcityAdjustedBoost(): Map<Long, Double> {
    val base = sessionAdjustedBoost()
    val stats = indexChannelStats
    if (stats.isEmpty()) return base
    val freshCutSec = System.currentTimeMillis() / 1000L - SCARCITY_FRESH_WINDOW_MS / 1000L
    var out: HashMap<Long, Double>? = null
    for ((id, st) in stats) {
        if (st.maxDateSec < freshCutSec) continue // 无 48h 内新帖：不上浮
        if (postsPerWeek(st) >= SCARCITY_LOW_POSTS_PER_WEEK) continue // 高频频道：不加权
        if (out == null) out = HashMap(base)
        out[id] = (out[id] ?: 0.0) + SCARCITY_SOURCE_BONUS
    }
    return out ?: base
}

// ---------------- 追更强信号：订阅新内容置顶聚合首页 ----------------

/**
 * 扫描订阅频道里"24 小时内发布且未看过"的条目（追更强信号，供聚合首页置顶）。
 *
 * 实现取舍：
 *  - 不复用 [SubscriptionFeedSource] 的增量扫描——它虽已在 R122 改为"真看才推进基线"，
 *    但本路径要的是"24h 窗口内的未看新帖"，与追更基线口径不同（见下条）；
 *    这里改为经 gateway 直拉每频道最新一小页（[FRESH_SCAN_PAGE_SIZE] 条），
 *    纯读，不产生任何追更进度写回；
 *  - "新"的口径不看 lastSeenMessageId，而是 dateEpochSec 落在最近 [FRESH_WINDOW_MS]
 *    内（24h 窗口与追更进度解耦：即使追更源已推进过进度，只要没真正看过就仍算"新"）；
 *  - "未看" = 双轨判重不命中：key 不在已看底账 且 内容指纹 fileUniqueId 不在
 *    已看指纹集（同一视频被转发到订阅频道前用户已在别处看过，也不算"新内容"；
 *    store.loadSeenIdentities 命中内存缓存，开销可忽略）；
 *    顺带剔除已屏蔽频道与"不感兴趣"单条，置顶区可见性口径与主 Feed 保持一致；
 *  - 类型口径同样对齐主 Feed：单类型媒体 tab 只放行同类型条目，并叠加设置页
 *    类型开关与内容筛选器（本路径直插列表、不过 loadMore 的 pass 谓词，必须自查）；
 *  - 单频道失败（网络/限流）静默跳过，不影响其他频道；
 *  - 订阅极多时只扫前 [FRESH_SCAN_MAX_CHANNELS] 个频道收敛开销（顺序即订阅先后）。
 *
 * 挂起函数，**必须在 IO 协程中调用**（内部走网关拉取）。返回按发布时间倒序、
 * 截断到 [FRESH_PIN_LIMIT] 条、caption 已加 [FRESH_PIN_PREFIX] 前缀的列表；
 * 失败/无订阅/无新内容一律返回空列表（静默语义，调用方直接跳过）。
 */
internal suspend fun FeedViewModel.scanFreshSubscriptionItemsImpl(): List<VideoItem> {
    val subs = _subscriptions.value
    if (subs.isEmpty()) return emptyList()
    val cutoffSec = (System.currentTimeMillis() - FRESH_WINDOW_MS) / 1000L
    // 已看双轨底账（key + 内容指纹）：置顶是推进主 Feed 的强曝光位，
    // "在别处看过的同片换了频道再置顶"是用户最恼火的重复形态之一
    val (seenKeys, seenUids) = runCatching { store.loadSeenIdentities() }
        .getOrDefault(emptySet<String>() to emptySet())
    val gate = visibilityGate() // R413：屏蔽频道/不感兴趣/关键词屏蔽同一谓词
    // 永久屏蔽（内容指纹）：可见性口径与主 Feed 一致，置顶区也不放行
    val permBlockedKeys = _blockedItems.value.mapTo(HashSet()) { it.key }
    val permBlockedUids = _blockedItems.value.mapNotNullTo(HashSet()) { b ->
        b.fileUniqueId.takeIf { it.isNotEmpty() }
    }
    // 类型对齐主 Feed 过滤链（修"视频 tab 混进图文/音频置顶"的根因）：
    // 本路径直插 _items、绕过了 loadMore 的 pass 谓词，此前只做屏蔽/已看过滤、
    // 不做任何类型过滤——订阅频道 24h 内发的图文/音频会被原样置顶进视频 tab。
    // 这里按 pass 同口径补齐三道：单类型 tab 只放行同类型（「全部」tab 不限）、
    // 设置页内容类型开关、内容筛选器（方向/时长/发布时间）。
    val mediaTab = _mediaTypeTab.value
    val typePrefs = dev.tgfeed.core.prefs.AppPrefs(app)
    val feedFilter = _filter.value
    val merged = ArrayList<VideoItem>()
    for (sub in subs.take(FRESH_SCAN_MAX_CHANNELS)) {
        val page = runCatching {
            gateway.fetchVideos(FeedRequest(channelId = sub.id), null, FRESH_SCAN_PAGE_SIZE)
        }.getOrNull() ?: continue // 单频道失败只跳过该频道（静默）
        for (item in page.items) {
            if (item.dateEpochSec <= 0 || item.dateEpochSec < cutoffSec) continue // 非 24h 内/无时间戳
            // 已看过（R337 双轨判定收口 SeenDedupPolicy.isSeen：key 精确命中，或
            // 内容指纹命中=同片跨频道转发，换台不算"新"）
            if (SeenDedupPolicy.isSeen(item, seenKeys, seenUids)) continue
            if (!gate.passes(item)) continue // 可见性口径对齐主 Feed（R413 VisibilityGate 含关键词屏蔽）
            // 永久屏蔽：key 命中或内容指纹命中都跳过（跨频道转发的同片也不置顶）
            if (SeenDedupPolicy.isSeen(item, permBlockedKeys, permBlockedUids)) continue
            // 单类型 tab 类型对齐（与 loadMore 的 pass 谓词同口径：tab != -1 时严格相等）
            if (mediaTab != -1 && item.mediaType != mediaTab) continue
            // 设置页内容类型开关（同 pass 口径：0 视频 / 1 图文 / 其余音频）
            val typeOn = when (item.mediaType) {
                0 -> typePrefs.showVideos
                1 -> typePrefs.showPhotos
                else -> typePrefs.showAudio
            }
            if (!typeOn) continue
            if (!feedFilter.matches(item)) continue // 方向/时长/发布时间筛选同口径
            merged += item
        }
    }
    if (merged.isEmpty()) return emptyList()
    return merged
        .distinctBy { it.key } // 双保险：同 key 不重复进置顶区
        .sortedByDescending { it.dateEpochSec }
        .take(FRESH_PIN_LIMIT)
        .map { it.copy(caption = FRESH_PIN_PREFIX + it.caption) }
}

/**
 * 追更强信号位触发：聚合源（Aggregate/WeightedAggregate）首页第一页加载完成后由 loadMore 调用。
 * 另起 IO 协程执行扫描，不拖慢本轮 loadMore 的收尾（_loading 放闸先于插入，
 * 第一页先渲染，追更条目随后到位）；
 * 插入前再次验明代次与源——扫描期间发生切源/刷新，则这批条目属于旧列表，静默丢弃；
 * 与现有条目按 key 去重（聚合第一页可能已自然包含同一条目，此时不重复插入，不强行移位）。
 *
 * 插入位置（禁区原则）：当前播放位置（含）之前是禁区——pager 是索引锚定
 *（VerticalPager 只认 settledPage 下标，无 key 锚定），扫描是异步的，到位时
 * 用户往往已往下划了若干条；若插到列表头，已划过的条目被整体后推到当前位置
 * 之后，继续下划必然重看（"看多少重复取决于划多快"的根因）。
 * 故插到当前位置之后（_currentIndex+1）：正在看的这条不动，下一条起就是追更新内容；
 * 冷启动用户尚未划动时 _currentIndex=0，追更条目从第 2 条起出现。
 */
internal fun FeedViewModel.pinFreshSubscriptionItems(gen: Int, src: FeedSource) {
    viewModelScope.launch(Dispatchers.IO) {
        val fresh = runCatching { scanFreshSubscriptionItems() }.getOrDefault(emptyList())
        if (fresh.isEmpty()) return@launch
        // 插入前验明代次与源：不一致说明列表已属新源/新一轮，直接丢弃
        if (loadGen.get() != gen || source !== src) return@launch
        // P1-11 同款 CAS：与 loadMore 追加、屏蔽/不感兴趣剔除并发时互不丢更新
        _items.update { list ->
            val known = list.mapTo(HashSet()) { it.key }
            val pins = fresh.filter { it.key !in known }
            if (pins.isEmpty()) {
                list
            } else {
                // 禁区原则：只插到当前播放位置之后，绝不动前缀（见上方 KDoc）
                val pos = (_currentIndex.value + 1).coerceIn(0, list.size)
                list.subList(0, pos) + pins + list.subList(pos, list.size)
            }
        }
    }
}

// ---------------- 追更通知（应用内常驻轮询，不加 WorkManager 依赖） ----------------

/** 设置追更通知开关（立即生效并写入 AppPrefs；轮询协程每轮开头读内存态，无需重启） */
internal fun FeedViewModel.setNotifyNewContentImpl(v: Boolean) {
    _notifyNewContent.value = v
    runCatching { prefs.notifyNewContent = v }
}

/**
 * 单轮追更轮询：只扫订阅频道（内存里的 _subscriptions），每频道取最新 1 条媒体消息
 * （网关内部即 GetChatHistory 翻页过滤，见 TdLibGateway.fetchFromChannel；
 *  limit=1 时网关已跳过评论级联并用小页拉取），
 * 与 LocalStore 的 lastSeenMessageIds 基线比对——比上次新就推进基线并计入更新批次。
 * 首轮无基线的频道只记录不计入（避免装后首次轮询对每个频道轰炸）。
 *
 * R148 通知合并：本轮全部"有基线且出新帖"的频道聚成一批，上交 [MergedNotify]
 * （与需求订阅到货共用同一渠道同一条汇总通知，"N 个频道有更新 · M 条关键词到货"式
 * 覆盖更新单条通知），不再逐频道各发一条——旧 per-channel 通知（渠道"追更"、
 * id=基数+频道哈希）已随 MergedNotify 迁移废弃，抽屉残骸由中枢清扫。
 *
 * H2：原实现用 gateway.listChannels() 扫全部频道——百级频道每 15 分钟数百次调用，
 * 且绝大多数频道用户并未订阅追更；订阅列表本来就是本地持久化的内存态，直接用。
 * 代价：订阅关系变化要等下一轮轮询才反映（15 分钟粒度，可接受）；
 * 未订阅频道不再兜底轮询（本来也不该轮询）。
 */
internal suspend fun FeedViewModel.pollSubscribedChannelsOnce() {
    if (!_notifyNewContent.value) return // 开关关闭：跳过本轮，不发起网络请求
    val channels = _subscriptions.value
    if (channels.isEmpty()) return
    val seen = runCatching { store.loadLastSeenMessageIds() }.getOrDefault(emptyMap())
    val updated = HashMap(seen)
    var dirty = false
    val batch = ArrayList<MergedNotify.FollowUpdate>()
    for (ch in channels) {
        val latest = runCatching {
            gateway.fetchVideos(FeedRequest(channelId = ch.id), null, 1)
                .items.maxByOrNull { it.messageId }
        }.getOrNull() ?: continue // 单频道失败只跳过该频道，基线保持原值
        val prev = seen[ch.id] ?: 0L
        if (latest.messageId > prev) {
            updated[ch.id] = latest.messageId
            dirty = true
            if (prev > 0L) {
                // 有基线且出现更新的消息 → 计入本轮追更批次（通知由 MergedNotify 汇总发）
                batch += MergedNotify.FollowUpdate(
                    chatId = ch.id,
                    title = ch.title,
                    // 正文摘要口径沿用旧通知：caption 首行（空串由中枢明细行兜底"有更新"）
                    text = latest.caption.lineSequence().firstOrNull()?.trim().orEmpty(),
                )
            }
            // prev == 0L：首轮无基线，只落基线不通知
        }
    }
    if (dirty) runCatching { store.saveLastSeenMessageIds(updated) }
    // 批量上交合并通知中枢：内部覆盖更新同一条汇总通知；权限没给/系统异常静默（下轮再试）
    if (batch.isNotEmpty()) MergedNotify.onFollowUpdates(batch)
}

// ---------------- 追更轮询常量（FeedViewModel 追更段配套，文件级私有） ----------------

/** 追更轮询间隔（毫秒）：15 分钟 */
internal const val FOLLOW_POLL_INTERVAL_MS = 15L * 60_000L

/** 首轮轮询延迟（毫秒）：1 分钟——避开启动/登录高峰，也给通知权限请求留出时间 */
internal const val FOLLOW_POLL_INITIAL_DELAY_MS = 60_000L
