package dev.tgfeed.core.feed

import android.os.Build
import android.os.SystemClock
import android.util.Log
import dev.tgfeed.core.store.BlockedItem
import dev.tgfeed.core.store.LocalStore
import dev.tgfeed.core.store.Subscription
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/**
 * R300 多设备状态同步执行器（design-multi-device-sync.md 期一 MVP）：
 * 收藏/稍后再看/追更订阅+已读水位/屏蔽三件套四域（六表）经 Saved Messages 协议 V3
 * 多域消息同步（每域一条 marker 消息，find-or-create + EditMessageText 原地编辑，
 * 4096 超载按 key 序快照分片兜底）。
 *
 * 边界铁律（design §4/§6，违者打回）：
 *  - deepindex 永不进消息通道（体积差 4 个数量级）；store 实时状态永不并入 R287
 *    数据包合并；爬取游标（deepindex meta.cursor）≠已读水位（subscriptions.
 *    lastSeenMessageId，本类唯一同步的游标）；
 *  - 载荷只搬 key+ts（+订阅标题展示元数据），绝不搬完整 VideoItem——对端按 key 还原，
 *    本机索引没有的 key 落占位降级卡（点入可经 chatId/messageId 现取，ensurePlayable 链）；
 *  - 一切失败静默（同步是锦上添花）；日志只出域 id/条数/字符数——key 不落日志（§七扩令）。
 *
 * 台账（stateFile，{"v":1,...} 原子 tmp+rename 落盘）：
 *  - fav/later 的加入戳在 VideoItem.favTs 上（R300 前置迁移），台账只记墓碑；
 *  - blk/dis/subs 的成员加入戳+墓碑都在台账（底层表无 ts 字段）；
 *  - bitem 加入戳在 BlockedItem.ts 上，台账只记墓碑。
 * 墓碑 TTL 30d + 上限 [MAX_TOMBSTONES_PER_DOMAIN]（沿用 ProgressSync 口径）。
 *
 * 推：动作侧 note* 标脏 → VM 30s 节流 → 本类按域 60s 节流推（全域 30s 写间隔+
 * 429 退避在 GatewaySync.SyncWriteBudget 统一发送口内）。
 * 拉：启动拉/开关开拉/周期 15min 拉/事件驱动拉（Saved Messages UpdateMessageContent，
 * TG 服务端实时推送到同账号其他在线设备，15s 汇聚防抖）。
 */
class MultiSync(
    private val gateway: TdLibGateway,
    private val store: LocalStore,
    private val stateFile: File,
    private val scope: CoroutineScope,
    /** 合并落盘与 VM 互动写路径同一把锁（R108 串行通道纪律：读-改-写必须串行化） */
    private val ledgerMutex: Mutex,
    /** 总闸读口（prefs.cloudSyncEnabled） */
    private val enabledProvider: () -> Boolean,
    /** 合并落盘完成后回调（VM 回校内存态；在 ledgerMutex 内被调，须轻量快速） */
    private val onApplied: (Set<Domain>) -> Unit,
) {

    enum class Domain(val id: String) {
        FAV("fav"), LATER("later"), BLK("blk"), DIS("dis"), BITEM("bitem"), SUBS("subs"),
        /** R413 关键词屏蔽词表（成员=屏蔽词原形；加入戳+墓碑在台账，同 dis 域口径） */
        KW("kw")
    }

    companion object {
        private const val TAG = "MultiSync"

        /** 域级推送节流：同域两次真推最小间隔（沿用 ProgressSync 60s 口径） */
        private const val DOMAIN_PUSH_MIN_INTERVAL_MS = 60_000L

        /** 每域墓碑台账上限（超出按删除时刻淘汰最旧，控载荷体积） */
        private const val MAX_TOMBSTONES_PER_DOMAIN = 500

        /** 周期拉取间隔（轮询保底；事件驱动是主路） */
        private const val PERIODIC_PULL_INTERVAL_MS = 15L * 60 * 1000

        /** 事件驱动拉取汇聚窗：远端编辑信号后攒 15s 再拉（连编多次只拉一次） */
        private const val EVENT_PULL_DEBOUNCE_MS = 15_000L
    }

    // ---------------- 台账（成员加入戳 + 删除墓碑；stateFile 落盘） ----------------

    private val ledgerLock = Any()
    private var ledgerLoaded = false

    /** blk/dis/subs 域成员加入戳（fav/later 在 VideoItem.favTs、bitem 在 BlockedItem.ts，不入台账） */
    private val addedLedger = HashMap<Domain, LinkedHashMap<String, Long>>()

    /** 全域删除墓碑台账 */
    private val tombLedger = HashMap<Domain, LinkedHashMap<String, Long>>()

    /** 域脏标记（动作侧标脏；推送成功后清） */
    private val dirty = java.util.EnumSet.noneOf(Domain::class.java)

    /** 各域最近一次真推时刻（域级 60s 节流） */
    private val lastPushAt = java.util.EnumMap<Domain, Long>(Domain::class.java)

    private fun ledgerOf(map: HashMap<Domain, LinkedHashMap<String, Long>>, d: Domain): LinkedHashMap<String, Long> =
        map.getOrPut(d) { LinkedHashMap() }

    private fun ensureLedgerLoadedLocked() {
        if (ledgerLoaded) return
        ledgerLoaded = true
        runCatching {
            if (!stateFile.isFile) return@runCatching
            val root = JSONObject(stateFile.readText())
            fun readMap(name: String, into: HashMap<Domain, LinkedHashMap<String, Long>>) {
                val obj = root.optJSONObject(name) ?: return
                for (d in Domain.entries) {
                    val m = obj.optJSONObject(d.id) ?: continue
                    val keys = m.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = m.optLong(k, 0L)
                        if (k.isNotEmpty() && v > 0L) ledgerOf(into, d)[k] = v
                    }
                }
            }
            readMap("added", addedLedger)
            readMap("tomb", tombLedger)
        }.onFailure { Log.w(TAG, "同步台账读取失败（按空账起步）: ${it.message}") }
    }

    /** 台账原子落盘（tmp+rename；调用方须已持 ledgerLock） */
    private fun persistLedgerLocked() {
        runCatching {
            stateFile.parentFile?.mkdirs()
            fun writeMap(into: HashMap<Domain, LinkedHashMap<String, Long>>): JSONObject {
                val obj = JSONObject()
                for (d in Domain.entries) {
                    val m = into[d] ?: continue
                    if (m.isEmpty()) continue
                    val dm = JSONObject()
                    for ((k, v) in m) if (v > 0L) dm.put(k, v)
                    if (dm.length() > 0) obj.put(d.id, dm)
                }
                return obj
            }
            val text = JSONObject()
                .put("v", 1)
                .put("added", writeMap(addedLedger))
                .put("tomb", writeMap(tombLedger))
                .toString()
            val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(stateFile)) {
                runCatching { stateFile.writeText(text) }
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "同步台账落盘失败（内存态不受影响）: ${it.message}") }
    }

    // ---------------- 动作侧记账（VM 互动动作调用；同步小文件写，毫秒级） ----------------

    /** 成员加入记账（blk/dis/subs 域；fav/later 加入戳在 item.favTs 上不用走这里） */
    fun noteAdded(domain: Domain, key: String, ts: Long = System.currentTimeMillis()) {
        if (key.isEmpty()) return
        synchronized(ledgerLock) {
            ensureLedgerLoadedLocked()
            ledgerOf(addedLedger, domain)[key] = ts
            // 加入压过既有墓碑（删除后又加回=墓碑作废）
            val tomb = ledgerOf(tombLedger, domain)[key]
            if (tomb != null && ts >= tomb) ledgerOf(tombLedger, domain).remove(key)
            dirty += domain
            persistLedgerLocked()
        }
    }

    /** 成员删除记账（全域通用：记墓碑并标脏；幂等——同 key 覆盖为最新删除时刻） */
    fun noteRemoved(domain: Domain, key: String, ts: Long = System.currentTimeMillis()) {
        if (key.isEmpty()) return
        synchronized(ledgerLock) {
            ensureLedgerLoadedLocked()
            ledgerOf(tombLedger, domain)[key] = ts
            dirty += domain
            persistLedgerLocked()
        }
    }

    /** 只标脏不记账（fav/later 加入= favTs 已在条目上；subs 水位推进=游标变化） */
    fun markDirty(domain: Domain) {
        synchronized(ledgerLock) { dirty += domain }
    }

    /** 是否还有未推出的脏域（VM 据此排一次延迟重试） */
    fun hasPendingDirty(): Boolean = synchronized(ledgerLock) { dirty.isNotEmpty() }

    // ---------------- 生命周期（VM init 接线） ----------------

    @Volatile
    private var started = false

    @Volatile
    private var eventPullJob: kotlinx.coroutines.Job? = null

    /**
     * 启动事件驱动拉取监听 + 周期拉取保底（VM init 调一次；幂等单飞）。
     * 事件源：Saved Messages 的 UpdateMessageContent（本账号任一在线设备编辑同步消息，
     * TG 服务端实时推送到本端）；汇聚 15s 防抖后拉四域。
     */
    fun start() {
        if (started) return
        started = true
        gateway.startSyncUpdateWatcher {
            if (!enabledProvider()) return@startSyncUpdateWatcher
            eventSignal()
        }
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(PERIODIC_PULL_INTERVAL_MS)
                if (!enabledProvider()) continue
                runCatching { pullAll() }
                runCatching { pushDirty() }
            }
        }
    }

    /** 远端编辑信号汇聚（连编多次只拉一次；防抖窗后若又静默才真拉） */
    private fun eventSignal() {
        if (eventPullJob?.isActive == true) return // 已在汇聚窗内
        eventPullJob = scope.launch(Dispatchers.IO) {
            delay(EVENT_PULL_DEBOUNCE_MS)
            if (!enabledProvider()) return@launch
            val n = runCatching { pullAll() }.getOrDefault(0)
            Log.i(TAG, "事件驱动拉取完成：合入 $n 项变更")
        }
    }

    // ---------------- 推送 ----------------

    /**
     * 推送全部脏域（VM 30s 节流后调用；域级 60s 节流在本类内——不到点的域保持脏标记，
     * 等下一轮）。总闸关着直接返回（零网络）。
     */
    suspend fun pushDirty() {
        if (!enabledProvider()) return
        val due = synchronized(ledgerLock) {
            val now = System.currentTimeMillis()
            dirty.filter { now - (lastPushAt[it] ?: 0L) >= DOMAIN_PUSH_MIN_INTERVAL_MS }
        }
        for (d in due) {
            val ok = runCatching { pushDomain(d) }.getOrDefault(false)
            if (ok) {
                synchronized(ledgerLock) {
                    dirty -= d
                    lastPushAt[d] = System.currentTimeMillis()
                }
            }
        }
    }

    /** 手动全量推（设置页「立即同步」；绕过脏标记但仍过域级节流——force=true 时也绕过节流） */
    suspend fun pushAll(force: Boolean = false) {
        if (!enabledProvider()) return
        for (d in Domain.entries) {
            if (!force) {
                val sinceLast = System.currentTimeMillis() - (synchronized(ledgerLock) { lastPushAt[d] } ?: 0L)
                if (sinceLast < DOMAIN_PUSH_MIN_INTERVAL_MS) continue
            }
            runCatching { pushDomain(d) }.onSuccess { ok ->
                if (ok) synchronized(ledgerLock) {
                    dirty -= d
                    lastPushAt[d] = System.currentTimeMillis()
                }
            }
        }
    }

    /**
     * 单域推送：本地状态快照 → SyncPayload.chunk 分片 → 批量槽内 find-or-create 写片。
     * 返回 true=完成一次真实云端写（或云端已一致无需写）；false=被闸拒/失败（保留下轮重试）。
     */
    private suspend fun pushDomain(d: Domain): Boolean {
        val state = snapshotLocal(d)
        val chatId = gateway.getSavedMessagesChatId() ?: return false
        // 本域既有远端片（find-or-create 的 find 半步）
        val existing = gateway.findSyncMessages(chatId, SyncPayload.MARKER_PREFIX)
            .mapNotNull { (id, text) -> SyncPayload.decode(text)?.let { id to it } }
            .filter { it.second.domainId == d.id }
        if (state.isEmpty && existing.isEmpty()) return true // 双空：不创建空同步消息
        val chunks = SyncPayload.chunk(state)
        if (chunks.isEmpty()) {
            // 本地全空（无在册也无墓碑）时**绝不删远端片**：新装/离线设备从未成功 pull 时
            // 本地空≠语义空，删远端会把其他设备的同步状态灭掉（宁留陈值不失数据）。
            // 本地清空且有墓碑要传播时，state.removed 非空 → chunk 非空走正常写片路径，到不了这里。
            if (existing.isNotEmpty()) {
                Log.d(TAG, "pushDomain(${d.id}): 本地空快照且云端有片，跳过（防新装/离线误删远端；先 pull 后才会有真语义）")
            }
            return true
        }
        val now = System.currentTimeMillis()
        // 片数对齐检查：既有片按 index 归位（同 index 取最新 ts）
        val existingByIndex = existing.groupBy({ it.second.index }, { it.first })
        val aligned = existing.isNotEmpty() &&
            existing.all { it.second.total == chunks.size } &&
            existingByIndex.keys == (1..chunks.size).toSet()
        if (!gateway.syncWriteBeginBatch()) return false
        try {
            return if (aligned) {
                // 原地编辑各片（不刷屏）；任一片失败回落整域重建
                var okAll = true
                for (i in 1..chunks.size) {
                    val payload = encodeShard(d, chunks[i - 1], i, chunks.size, now)
                    val msgId = existingByIndex[i]!!.first()
                    if (!gateway.editSyncTextBatched(chatId, msgId, payload)) {
                        okAll = false
                        break
                    }
                }
                if (!okAll) {
                    Log.w(TAG, "pushDomain(${d.id}): 原地编辑失败，回落整域重建")
                    rebuildShards(chatId, d, chunks, existing.map { it.first }, now)
                } else {
                    Log.i(TAG, "pushDomain(${d.id}): 原地编辑 ${chunks.size} 片完成")
                    true
                }
            } else {
                rebuildShards(chatId, d, chunks, existing.map { it.first }, now)
            }
        } finally {
            gateway.syncWriteEndBatch()
        }
    }

    /** 整域重建：删旧片（若有）+逐片新发（换代/首建共用） */
    private suspend fun rebuildShards(
        chatId: Long,
        d: Domain,
        chunks: List<SyncPayload.DomainState>,
        oldIds: List<Long>,
        now: Long,
    ): Boolean {
        if (oldIds.isNotEmpty() && !gateway.deleteSyncMessagesBatched(chatId, oldIds)) return false
        for (i in 1..chunks.size) {
            val payload = encodeShard(d, chunks[i - 1], i, chunks.size, now)
            if (gateway.sendSyncTextBatched(chatId, payload) == null) return false
        }
        Log.i(TAG, "pushDomain(${d.id}): 整域重建 ${chunks.size} 片完成")
        return true
    }

    private fun encodeShard(d: Domain, state: SyncPayload.DomainState, index: Int, total: Int, ts: Long): String =
        SyncPayload.encode(d.id, index, total, state, ts, Build.MODEL ?: "Android")

    // ---------------- 本地状态快照（推送源） ----------------

    private fun snapshotLocal(d: Domain): SyncPayload.DomainState = synchronized(ledgerLock) {
        ensureLedgerLoadedLocked()
        val now = System.currentTimeMillis()
        // 墓碑剪枝（TTL+上限）+ 已被更新加入压过的墓碑作废
        fun prunedTombs(domain: Domain, liveAdded: Map<String, Long>): Map<String, Long> {
            val tombs = ledgerOf(tombLedger, domain)
            val before = tombs.size
            tombs.entries.removeAll { (k, v) ->
                SyncMergePolicy.tombstoneExpired(v, now) || (liveAdded[k] ?: 0L) > v
            }
            while (tombs.size > MAX_TOMBSTONES_PER_DOMAIN) {
                tombs.entries.minByOrNull { it.value }?.let { tombs.remove(it.key) } ?: break
            }
            if (tombs.size != before) persistLedgerLocked()
            return tombs.toMap()
        }
        when (d) {
            Domain.FAV -> {
                val items = runCatching { store.loadFavorites() }.getOrDefault(emptyList())
                val added = LinkedHashMap<String, Long>()
                items.forEach { if (it.key.isNotEmpty()) added[it.key] = it.favTs } // 0=未知戳存在声明
                SyncPayload.DomainState(added = added, removed = prunedTombs(d, added))
            }
            Domain.LATER -> {
                val items = runCatching { store.loadWatchLater() }.getOrDefault(emptyList())
                val added = LinkedHashMap<String, Long>()
                items.forEach { if (it.key.isNotEmpty()) added[it.key] = it.favTs }
                SyncPayload.DomainState(added = added, removed = prunedTombs(d, added))
            }
            Domain.BLK -> {
                val members = runCatching { store.loadBlockedChannels() }.getOrDefault(emptySet())
                val ledgerA = ledgerOf(addedLedger, d)
                val added = LinkedHashMap<String, Long>()
                members.forEach { if (it != 0L) added[it.toString()] = ledgerA[it.toString()] ?: 0L }
                // 台账里已不在册的 added 戳顺手清掉（减实体积）
                ledgerA.keys.removeAll { it !in added }
                SyncPayload.DomainState(added = added, removed = prunedTombs(d, added))
            }
            Domain.DIS -> {
                val members = runCatching { store.loadDisliked() }.getOrDefault(emptySet())
                val ledgerA = ledgerOf(addedLedger, d)
                val added = LinkedHashMap<String, Long>()
                members.forEach { if (it.isNotEmpty()) added[it] = ledgerA[it] ?: 0L }
                ledgerA.keys.removeAll { it !in added }
                SyncPayload.DomainState(added = added, removed = prunedTombs(d, added))
            }
            Domain.KW -> {
                val members = runCatching { store.loadMutedKeywords() }.getOrDefault(emptySet())
                val ledgerA = ledgerOf(addedLedger, d)
                val added = LinkedHashMap<String, Long>()
                members.forEach { if (it.isNotEmpty()) added[it] = ledgerA[it] ?: 0L }
                ledgerA.keys.removeAll { it !in added }
                SyncPayload.DomainState(added = added, removed = prunedTombs(d, added))
            }
            Domain.BITEM -> {
                val items = runCatching { store.loadBlockedItems() }.getOrDefault(emptyList())
                val added = LinkedHashMap<String, Long>()
                items.forEach {
                    val id = it.fileUniqueId.ifEmpty { it.key }
                    if (id.isNotEmpty()) added[id] = it.ts
                }
                SyncPayload.DomainState(added = added, removed = prunedTombs(d, added))
            }
            Domain.SUBS -> {
                val subs = runCatching { store.loadSubscriptions() }.getOrDefault(emptyList())
                val ledgerA = ledgerOf(addedLedger, d)
                val added = LinkedHashMap<String, Long>()
                val cursors = LinkedHashMap<String, Long>()
                val titles = LinkedHashMap<String, String>()
                subs.forEach { s ->
                    val k = s.chatId.toString()
                    added[k] = ledgerA[k] ?: 0L
                    if (s.lastSeenMessageId > 0L) cursors[k] = s.lastSeenMessageId
                    if (s.title.isNotBlank()) titles[k] = s.title
                }
                ledgerA.keys.removeAll { it !in added }
                SyncPayload.DomainState(added = added, removed = prunedTombs(d, added), cursors = cursors, titles = titles)
            }
        }
    }

    // ---------------- 拉取与合并 ----------------

    /** 拉四域合入本地，返回合入/删除的总变更项数（0=云端没有更新）。总闸关着返回 0。 */
    suspend fun pullAll(): Int {
        if (!enabledProvider()) return 0
        var changed = 0
        for (d in Domain.entries) {
            changed += runCatching { pullDomain(d) }.getOrDefault(0)
        }
        return changed
    }

    /** 单域拉取：捞片 → 凑齐一代 → 多片并集 → SyncMergePolicy 裁决 → ledgerMutex 内落盘+回校 */
    private suspend fun pullDomain(d: Domain): Int {
        // R300c 到达打点（V13 挂账①：16416 侧 pullDomain(fav) 沉默疑云——「拉了但零变更」与
        // 「根本没拉到」不可分辨，pullDomain 旧口径只在 applied>0 才出声）：每域每轮一行到达证据，
        // 只出计数/耗时/单字状态码，零内容转录（§七）；Gateway* 探针行家族风格（R236/R254 同款）。
        // 状态码：无会话=未拿到收藏夹会话（未授权/网关未就绪）；云端空=Saved Messages 无同步消息；
        // 本域无片=云端有其他域消息但无本域分片；未凑齐=写方换代中途跳过本轮；ok=正常走到合入
        //（变更=合入项数，可为 0=云端与本机已一致）；异常兜底=合入段抛异常被 pullAll 的
        // runCatching 吞前（finally 照打）。时长走 SystemClock 单调钟（不受改墙钟影响）。
        val t0 = SystemClock.elapsedRealtime()
        var probeState = "异常兜底"
        var cloudMsgs = 0
        var domainShards = 0
        var applied = 0
        try {
            val chatId = gateway.getSavedMessagesChatId()
            if (chatId == null) {
                probeState = "无会话"
                return 0
            }
            val msgs = gateway.findSyncMessages(chatId, SyncPayload.MARKER_PREFIX)
            cloudMsgs = msgs.size
            if (msgs.isEmpty()) {
                probeState = "云端空"
                return 0
            }
            val shards = msgs.mapNotNull { SyncPayload.decode(it.second) }.filter { it.domainId == d.id }
            domainShards = shards.size
            if (shards.isEmpty()) {
                probeState = "本域无片"
                return 0
            }
            // 换代对齐：取最新片的 total 为本代片数，同代片凑齐 1..T 才合（写方换代中途=半代不合）
            val newest = shards.maxByOrNull { it.ts } ?: return 0
            val total = newest.total
            val byIndex = LinkedHashMap<Int, SyncPayload.Shard>()
            shards.filter { it.total == total }.forEach { s ->
                val cur = byIndex[s.index]
                if (cur == null || s.ts > cur.ts) byIndex[s.index] = s
            }
            if (byIndex.keys != (1..total).toSet()) {
                probeState = "未凑齐"
                Log.d(TAG, "pullDomain(${d.id}): 分片未凑齐（${byIndex.size}/$total），按写方换代中途跳过本轮")
                return 0
            }
            // 多片并集：added/removed/cursors per-key 取 max（added 保 0 值存在声明）；titles 取最新片
            val added = LinkedHashMap<String, Long>()
            val removed = LinkedHashMap<String, Long>()
            val cursors = LinkedHashMap<String, Long>()
            val titles = LinkedHashMap<String, String>()
            byIndex.toSortedMap().values.forEach { s ->
                s.state.added.forEach { (k, v) -> added[k] = if (k in added) maxOf(added.getValue(k), v) else v }
                s.state.removed.forEach { (k, v) -> removed[k] = if (k in removed) maxOf(removed.getValue(k), v) else v }
                s.state.cursors.forEach { (k, v) -> cursors[k] = if (k in cursors) maxOf(cursors.getValue(k), v) else v }
                s.state.titles.forEach { (k, v) -> if (k !in titles) titles[k] = v }
            }
            val remote = SyncPayload.DomainState(added, removed, cursors, titles)
            val now = System.currentTimeMillis()
            ledgerMutex.withLock {
                applied = applyRemote(d, remote, now)
            }
            probeState = "ok"
            if (applied > 0) {
                Log.i(TAG, "pullDomain(${d.id}): 合入 $applied 项变更（云端 ${added.size} 在册戳+${removed.size} 墓碑+${cursors.size} 游标）")
            }
            return applied
        } finally {
            // 到达打点（finally=任何出口/异常都照打，「到达」本身就是证据；pullAll 每轮每域一行）
            Log.i(
                TAG,
                "R300 pullDomain: domain=${d.id} 到达 状态=$probeState 云端片=$cloudMsgs 本域片=$domainShards 变更=$applied 耗时=${SystemClock.elapsedRealtime() - t0}ms",
            )
        }
    }

    /** 合并落盘（调用方须已持 ledgerMutex）。返回变更项数；有变更时回调 onApplied。 */
    private fun applyRemote(d: Domain, remote: SyncPayload.DomainState, now: Long): Int {
        var changed = 0
        when (d) {
            Domain.FAV -> {
                val items = runCatching { store.loadFavorites() }.getOrDefault(emptyList())
                val byKey = items.associateBy { it.key }
                val out = items.toMutableList()
                val keys = LinkedHashSet<String>().apply {
                    addAll(items.map { it.key })
                    addAll(remote.added.keys)
                    addAll(remote.removed.keys)
                }
                val newAdds = ArrayList<VideoItem>()
                synchronized(ledgerLock) {
                    ensureLedgerLoadedLocked()
                    val tombs = ledgerOf(tombLedger, d)
                    for (key in keys) {
                        if (key.isEmpty()) continue
                        val item = byKey[key]
                        val m = SyncMergePolicy.mergeSetKey(
                            localPresent = item != null,
                            localAdded = item?.favTs ?: 0L,
                            localRemoved = tombs[key] ?: 0L,
                            remoteAdded = remote.added[key] ?: 0L,
                            remoteClaimed = key in remote.added,
                            remoteRemoved = remote.removed[key] ?: 0L,
                            nowMs = now,
                        )
                        if (m.present && item == null) {
                            placeholderItem(key, m.added, watchLater = false)?.let { newAdds += it; changed++ }
                        } else if (!m.present && item != null) {
                            out.removeAll { it.key == key }
                            if (m.removed > 0L) tombs[key] = m.removed
                            changed++
                        } else if (m.present && item != null && m.added > item.favTs) {
                            val idx = out.indexOfFirst { it.key == key }
                            if (idx >= 0) out[idx] = item.copy(favTs = m.added)
                        }
                        // 在册后墓碑作废
                        if (m.present && m.added >= m.removed) tombs.remove(key)
                    }
                    if (changed > 0) persistLedgerLocked()
                }
                if (changed > 0) {
                    runCatching { store.saveFavorites(newAdds + out) }
                    onApplied(setOf(d))
                }
            }
            Domain.LATER -> {
                val items = runCatching { store.loadWatchLater() }.getOrDefault(emptyList())
                val byKey = items.associateBy { it.key }
                val out = items.toMutableList()
                val keys = LinkedHashSet<String>().apply {
                    addAll(items.map { it.key }); addAll(remote.added.keys); addAll(remote.removed.keys)
                }
                val newAdds = ArrayList<VideoItem>()
                synchronized(ledgerLock) {
                    ensureLedgerLoadedLocked()
                    val tombs = ledgerOf(tombLedger, d)
                    for (key in keys) {
                        if (key.isEmpty()) continue
                        val item = byKey[key]
                        val m = SyncMergePolicy.mergeSetKey(
                            item != null, item?.favTs ?: 0L, tombs[key] ?: 0L,
                            remote.added[key] ?: 0L, key in remote.added, remote.removed[key] ?: 0L, now,
                        )
                        if (m.present && item == null) {
                            placeholderItem(key, m.added, watchLater = true)?.let { newAdds += it; changed++ }
                        } else if (!m.present && item != null) {
                            out.removeAll { it.key == key }
                            if (m.removed > 0L) tombs[key] = m.removed
                            changed++
                        } else if (m.present && item != null && m.added > item.favTs) {
                            val idx = out.indexOfFirst { it.key == key }
                            if (idx >= 0) out[idx] = item.copy(favTs = m.added)
                        }
                        if (m.present && m.added >= m.removed) tombs.remove(key)
                    }
                    if (changed > 0) persistLedgerLocked()
                }
                if (changed > 0) {
                    runCatching { store.saveWatchLater(newAdds + out) }
                    onApplied(setOf(d))
                }
            }
            Domain.BLK -> {
                val cur = runCatching { store.loadBlockedChannels() }.getOrDefault(emptySet())
                val out = cur.toMutableSet()
                val keys = LinkedHashSet<String>().apply {
                    cur.forEach { add(it.toString()) }; addAll(remote.added.keys); addAll(remote.removed.keys)
                }
                synchronized(ledgerLock) {
                    ensureLedgerLoadedLocked()
                    val ledgerA = ledgerOf(addedLedger, d)
                    val tombs = ledgerOf(tombLedger, d)
                    for (key in keys) {
                        val chatId = key.toLongOrNull() ?: continue
                        if (chatId == 0L) continue
                        val present = chatId in cur
                        val m = SyncMergePolicy.mergeSetKey(
                            present, if (present) ledgerA[key] ?: 0L else 0L, tombs[key] ?: 0L,
                            remote.added[key] ?: 0L, key in remote.added, remote.removed[key] ?: 0L, now,
                        )
                        if (m.present && !present) { out += chatId; if (m.added > 0L) ledgerA[key] = m.added; changed++ }
                        if (!m.present && present) { out -= chatId; if (m.removed > 0L) tombs[key] = m.removed; changed++ }
                        if (m.present && m.added >= m.removed) tombs.remove(key)
                    }
                    if (changed > 0) persistLedgerLocked()
                }
                if (changed > 0) {
                    runCatching { store.saveBlockedChannels(out) }
                    onApplied(setOf(d))
                }
            }
            Domain.DIS -> {
                val cur = runCatching { store.loadDisliked() }.getOrDefault(emptySet())
                val out = cur.toMutableSet()
                val keys = LinkedHashSet<String>().apply {
                    addAll(cur); addAll(remote.added.keys); addAll(remote.removed.keys)
                }
                synchronized(ledgerLock) {
                    ensureLedgerLoadedLocked()
                    val ledgerA = ledgerOf(addedLedger, d)
                    val tombs = ledgerOf(tombLedger, d)
                    for (key in keys) {
                        if (key.isEmpty()) continue
                        val present = key in cur
                        val m = SyncMergePolicy.mergeSetKey(
                            present, if (present) ledgerA[key] ?: 0L else 0L, tombs[key] ?: 0L,
                            remote.added[key] ?: 0L, key in remote.added, remote.removed[key] ?: 0L, now,
                        )
                        if (m.present && !present) { out += key; if (m.added > 0L) ledgerA[key] = m.added; changed++ }
                        if (!m.present && present) { out -= key; if (m.removed > 0L) tombs[key] = m.removed; changed++ }
                        if (m.present && m.added >= m.removed) tombs.remove(key)
                    }
                    if (changed > 0) persistLedgerLocked()
                }
                if (changed > 0) {
                    runCatching { store.saveDisliked(out) }
                    onApplied(setOf(d))
                }
            }            Domain.KW -> {
                val cur = runCatching { store.loadMutedKeywords() }.getOrDefault(emptySet())
                val out = LinkedHashSet(cur)
                val keys = LinkedHashSet<String>().apply {
                    addAll(cur); addAll(remote.added.keys); addAll(remote.removed.keys)
                }
                synchronized(ledgerLock) {
                    ensureLedgerLoadedLocked()
                    val ledgerA = ledgerOf(addedLedger, d)
                    val tombs = ledgerOf(tombLedger, d)
                    for (key in keys) {
                        if (key.isBlank()) continue
                        val present = key in cur
                        val m = SyncMergePolicy.mergeSetKey(
                            present, if (present) ledgerA[key] ?: 0L else 0L, tombs[key] ?: 0L,
                            remote.added[key] ?: 0L, key in remote.added, remote.removed[key] ?: 0L, now,
                        )
                        if (m.present && !present) { out += key; if (m.added > 0L) ledgerA[key] = m.added; changed++ }
                        if (!m.present && present) { out -= key; if (m.removed > 0L) tombs[key] = m.removed; changed++ }
                        if (m.present && m.added >= m.removed) tombs.remove(key)
                    }
                    if (changed > 0) persistLedgerLocked()
                }
                if (changed > 0) {
                    // 对端词表可能含本机 fold 等价的变体：落盘前过同一把尺去重/限长
                    runCatching { store.saveMutedKeywords(KeywordMutePolicy.sanitize(out)) }
                    onApplied(setOf(d))
                }
            }

            Domain.BITEM -> {
                val items = runCatching { store.loadBlockedItems() }.getOrDefault(emptyList())
                fun identityOf(b: BlockedItem) = b.fileUniqueId.ifEmpty { b.key }
                val byId = items.associateBy { identityOf(it) }
                val out = items.toMutableList()
                val keys = LinkedHashSet<String>().apply {
                    addAll(byId.keys); addAll(remote.added.keys); addAll(remote.removed.keys)
                }
                val newAdds = ArrayList<BlockedItem>()
                synchronized(ledgerLock) {
                    ensureLedgerLoadedLocked()
                    val tombs = ledgerOf(tombLedger, d)
                    for (id in keys) {
                        if (id.isEmpty()) continue
                        val item = byId[id]
                        val m = SyncMergePolicy.mergeSetKey(
                            item != null, item?.ts ?: 0L, tombs[id] ?: 0L,
                            remote.added[id] ?: 0L, id in remote.added, remote.removed[id] ?: 0L, now,
                        )
                        if (m.present && item == null) {
                            // 占位降级条目：身份到位即可参与匹配（title 仅展示用）
                            val isKey = id.startsWith("tg:")
                            newAdds += BlockedItem(
                                key = if (isKey) id else "",
                                fileUniqueId = if (isKey) "" else id,
                                title = "云端屏蔽", channelTitle = "", ts = if (m.added > 0L) m.added else now,
                            )
                            changed++
                        } else if (!m.present && item != null) {
                            out.removeAll { identityOf(it) == id }
                            if (m.removed > 0L) tombs[id] = m.removed
                            changed++
                        }
                        if (m.present && m.added >= m.removed) tombs.remove(id)
                    }
                    if (changed > 0) persistLedgerLocked()
                }
                if (changed > 0) {
                    runCatching { store.saveBlockedItemsMerged(newAdds + out) }
                    onApplied(setOf(d))
                }
            }
            Domain.SUBS -> {
                val subs = runCatching { store.loadSubscriptions() }.getOrDefault(emptyList())
                val byChat = subs.associateBy { it.chatId }
                val out = subs.toMutableList()
                val keys = LinkedHashSet<String>().apply {
                    subs.forEach { add(it.chatId.toString()) }; addAll(remote.added.keys); addAll(remote.removed.keys)
                }
                val newAdds = ArrayList<Subscription>()
                synchronized(ledgerLock) {
                    ensureLedgerLoadedLocked()
                    val ledgerA = ledgerOf(addedLedger, d)
                    val tombs = ledgerOf(tombLedger, d)
                    for (key in keys) {
                        val chatId = key.toLongOrNull() ?: continue
                        if (chatId == 0L) continue
                        val sub = byChat[chatId]
                        val m = SyncMergePolicy.mergeSetKey(
                            sub != null, if (sub != null) ledgerA[key] ?: 0L else 0L, tombs[key] ?: 0L,
                            remote.added[key] ?: 0L, key in remote.added, remote.removed[key] ?: 0L, now,
                        )
                        if (m.present && sub == null) {
                            // 新订阅：标题用云端元数据（占位降级允许空标题）；水位吃远端游标
                            newAdds += Subscription(
                                chatId = chatId,
                                title = remote.titles[key] ?: "",
                                lastSeenMessageId = remote.cursors[key] ?: 0L,
                            )
                            if (m.added > 0L) ledgerA[key] = m.added
                            changed++
                        } else if (!m.present && sub != null) {
                            out.removeAll { it.chatId == chatId }
                            if (m.removed > 0L) tombs[key] = m.removed
                            changed++
                        } else if (m.present && sub != null) {
                            // 已读水位=单调游标取 max（SyncMergePolicy.mergeCursor）
                            val mergedCursor = SyncMergePolicy.mergeCursor(sub.lastSeenMessageId, remote.cursors[key] ?: 0L)
                            if (mergedCursor > sub.lastSeenMessageId) {
                                val idx = out.indexOfFirst { it.chatId == chatId }
                                if (idx >= 0) { out[idx] = sub.copy(lastSeenMessageId = mergedCursor); changed++ }
                            }
                        }
                        if (m.present && m.added >= m.removed) tombs.remove(key)
                    }
                    if (changed > 0) persistLedgerLocked()
                }
                if (changed > 0) {
                    runCatching { store.saveSubscriptions(newAdds + out) }
                    onApplied(setOf(d))
                }
            }
        }
        return changed
    }

    /**
     * 占位降级卡（design §3.2：对端索引没有的 key→灰卡「云端收藏·本机未索引」）：
     * key 形态 tg:<chatId>:<messageId>（GatewayFeedFetch 同口径）；chatId/messageId 为真值，
     * 点入走 ensurePlayable 现取（fileId=-1 触发重解析链）。解析不出合法坐标返回 null（坏 key 跳过）。
     */
    private fun placeholderItem(key: String, addedTs: Long, watchLater: Boolean): VideoItem? {
        val body = key.removePrefix("tg:")
        if (body == key) return null
        val parts = body.split(":")
        if (parts.size != 2) return null
        val chatId = parts[0].toLongOrNull() ?: return null
        val messageId = parts[1].toLongOrNull() ?: return null
        if (chatId == 0L || messageId <= 0L) return null
        return VideoItem(
            key = key,
            chatId = chatId,
            messageId = messageId,
            channelTitle = "云端同步",
            caption = if (watchLater) "云端稍后再看·本机未索引" else "云端收藏·本机未索引",
            durationSec = 0, width = 0, height = 0, fileId = -1, sizeBytes = 0, playUri = null,
            favTs = addedTs,
        )
    }

    // ---------------- 期零③可回滚口：清理云同步消息（诊断通道，用户显式确认后调用） ----------------

    /**
     * 清点并删除 Saved Messages 里全部同步消息（V3 多域 + 进度域 V2 marker 全收）。
     * 返回删除的消息条数；未授权/失败返回 -1（UI 据以提示重试）。
     * 调用纪律：UI 必须用户显式确认后调用（DangerConfirmDialog）；删除后云端四域+进度域
     * 同步消息全灭，各端下次推送时 find-or-create 自然重建（本机数据不受影响）。
     */
    suspend fun clearAllRemoteMessages(): Int {
        return runCatching {
            val chatId = gateway.getSavedMessagesChatId() ?: return -1
            val v3 = gateway.findSyncMessages(chatId, SyncPayload.MARKER_PREFIX).map { it.first }
            val progress = gateway.findSyncMessage(chatId, ProgressSync.MARKER)
            val ids = if (progress != null) v3 + progress else v3
            if (ids.isEmpty()) return 0
            if (gateway.deleteSyncMessages(chatId, ids)) ids.size else -1
        }.onFailure {
            Log.w(TAG, "清理云同步消息失败: ${it.message}")
        }.getOrDefault(-1)
    }
}
