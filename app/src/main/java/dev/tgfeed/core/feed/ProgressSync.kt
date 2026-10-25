package dev.tgfeed.core.feed

import android.os.Build
import android.util.Log
import dev.tgfeed.core.store.LocalStore
import dev.tgfeed.core.td.TdLibGateway
import org.json.JSONObject
import java.io.File

/**
 * 跨设备续播同步：把本机观看进度（LocalStore watch_progress）经 Telegram
 * Saved Messages（收藏夹）云端中转，另一台设备拉取合并，
 * 实现"手机刷一半、平板接着放"。
 *
 * 协议 V2（R122 升级，修复 V1"无 LWW + 不传播删除"）：收藏夹里恰好一条文本消息
 * 承担同步载体，格式为两行——
 *   第 1 行：marker [MARKER]（识别用，全库唯一，勿改；改了旧同步消息就找不到）
 *   第 2 行：JSON 正文：
 *     {"device":"设备名","v":2,"ts":<写入时刻毫秒>,
 *      "positions":{"<item.key>":{"p":<posMs>,"t":<该进度最后更新毫秒>},...},
 *      "deleted":{"<item.key>":<本地删除时刻毫秒>,...}}
 *   更新方式 = EditMessageText 编辑同一条消息（不新发刷屏）；
 *   消息被用户手删/编辑失败时自动回落为新建一条。
 *   V1 兼容读：positions 值为裸 long 时按 V1 解析，时间戳取消息级 ts。
 *
 * 合并语义（LWW + 删除传播）：
 *  - positions 按 per-key 时间戳"新者胜"：云端条目不新于本地同 key 条目则跳过——
 *    本地较新的进度不再被云端旧值回滚（V1 的"值不同即覆盖"缺陷修复）；
 *    合入经 LocalStore.importProgress 保真 ts 落盘，多设备间比较结论传递一致；
 *  - deleted 是删除墓碑：本地看完（≥97%）清掉续播点时经 [noteLocalDeletion] 记账，
 *    推送时随载荷上云；对端拉取时，本地同 key 进度的时间戳早于墓碑即删除——
 *    "已看完"的条目不再被云端残留旧值复活（V1 的另一缺陷修复）；
 *    墓碑比 position 旧则被 position 覆盖语义（删除后另一台设备重看 = 复活成立）。
 *
 * 已知取舍：云端载体是单条消息的整体快照编辑，两台设备同时推送时后写覆盖先写
 * （无服务端合并）；per-key LWW 与墓碑保证拉取侧不回滚、不复活，但推送互相覆盖
 * 丢失的 key 要等该设备下次推送才再上云——主场景（同一时间只在一台设备上刷）无影响。
 *
 * 失败全静默：同步是锦上添花，任何一步失败只记日志、不向上抛。
 * 所有 suspend 方法均须在外层切到 Dispatchers.IO 调用（LocalStore 是同步阻塞 IO）；
 * [noteLocalDeletion] 为同步小文件写（tmp+rename，KB 级），允许在 onStop 主线程路径调用。
 */
class ProgressSync(
    private val gateway: TdLibGateway,
    private val store: LocalStore,
    /** 删除墓碑台账落盘位置（{"v":1,"deleted":{key:ts}}）；与 LocalStore 同目录自立门户 */
    private val stateFile: File,
) {

    companion object {
        private const val TAG = "ProgressSync"

        /** 同步消息识别标记（消息首行；V1/V2 共用，旧消息找到后原地升级为 V2） */
        const val MARKER = "[TGFEED_PROGRESS_V1]"

        /** 单条同步消息携带的最大进度条数（取最近更新的前 N 条，控消息体积） */
        private const val MAX_SYNC_ENTRIES = 50

        /** 推送限流：两次发起推送的最小间隔（毫秒）；成功失败都计，防失败重试风暴 */
        private const val PUSH_MIN_INTERVAL_MS = 60_000L

        /** 墓碑留存期：超过即认为全网设备都已同步过该删除，墓碑可丢弃 */
        private const val TOMBSTONE_TTL_MS = 30L * 24 * 3600 * 1000

        /** 墓碑台账上限（超出按删除时刻淘汰最旧，控载荷体积） */
        private const val MAX_TOMBSTONES = 200
    }

    /** 最近一次发起推送的时刻（@Volatile：push 可能在不同 IO 线程被触发） */
    @Volatile
    private var lastPushAtMs = 0L

    // ---------------- 删除墓碑台账（内存镜像 + stateFile 落盘） ----------------

    private val tombLock = Any()
    private val tombstones = LinkedHashMap<String, Long>()
    private var tombstonesLoaded = false

    /**
     * 登记一次本地删除（看完 ≥97% 清续播点的路径调用）：记墓碑并立即落盘——
     * 进程随后被杀也不能丢，否则下次 pull 会被云端旧值复活该条目。
     * 幂等（同 key 覆盖为最新删除时刻）。同步小文件写，耗时毫秒级。
     */
    fun noteLocalDeletion(key: String) {
        if (key.isEmpty()) return
        synchronized(tombLock) {
            ensureTombstonesLoadedLocked()
            tombstones[key] = System.currentTimeMillis()
            persistTombstonesLocked()
        }
    }

    /** 懒加载墓碑台账（首次使用时读盘；文件缺失/损坏按空表处理） */
    private fun ensureTombstonesLoadedLocked() {
        if (tombstonesLoaded) return
        tombstonesLoaded = true
        runCatching {
            if (!stateFile.isFile) return@runCatching
            val obj = JSONObject(stateFile.readText()).optJSONObject("deleted") ?: return@runCatching
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val t = obj.optLong(k, 0L)
                if (k.isNotEmpty() && t > 0L) tombstones[k] = t
            }
        }.onFailure { Log.w(TAG, "墓碑台账读取失败（按空表起步）: ${it.message}") }
    }

    /** 台账原子落盘（tmp+rename；调用方须已持有 tombLock） */
    private fun persistTombstonesLocked() {
        runCatching {
            stateFile.parentFile?.mkdirs()
            val deleted = JSONObject()
            for ((k, t) in tombstones) deleted.put(k, t)
            val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
            tmp.writeText(JSONObject().put("v", 1).put("deleted", deleted).toString())
            if (!tmp.renameTo(stateFile)) {
                runCatching { stateFile.writeText(JSONObject().put("v", 1).put("deleted", deleted).toString()) }
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "墓碑台账落盘失败（内存态不受影响）: ${it.message}") }
    }

    /**
     * 取推送用的墓碑快照：剪掉超期墓碑，剔除本地已重新存在进度的 key
     * （删除后又看过 = 删除作废，由 position 的新 ts 说话）。有变化则落盘。
     */
    private fun tombstonesForPush(localKeys: Set<String>): Map<String, Long> =
        synchronized(tombLock) {
            ensureTombstonesLoadedLocked()
            val cutoff = System.currentTimeMillis() - TOMBSTONE_TTL_MS
            val before = tombstones.size
            tombstones.entries.removeAll { it.value < cutoff || it.key in localKeys }
            while (tombstones.size > MAX_TOMBSTONES) {
                tombstones.entries.minByOrNull { it.value }?.let { tombstones.remove(it.key) } ?: break
            }
            if (tombstones.size != before) persistTombstonesLocked()
            tombstones.toMap()
        }

    /** pull 侧合入墓碑（内存；批量结束后由调用方统一落盘） */
    private fun mergeTombstoneLocked(key: String, ts: Long) {
        ensureTombstonesLoadedLocked()
        val cur = tombstones[key] ?: 0L
        if (ts > cur) tombstones[key] = ts
    }

    // ---------------- 推送 ----------------

    /**
     * 推送本机观看进度到收藏夹（限流：60s 内最多真推一次，窗口内调用直接跳过）。
     * 本地无进度且无墓碑时短路返回（不创建空同步消息）。
     */
    suspend fun pushProgress() {
        val now = System.currentTimeMillis()
        if (now - lastPushAtMs < PUSH_MIN_INTERVAL_MS) {
            Log.d(TAG, "pushProgress: 距上次推送不足 60s，跳过（限流）")
            return
        }
        lastPushAtMs = now
        runCatching {
            // loadProgressTimedMap 返回 LinkedHashMap，迭代序 = 最近更新在前（LocalStore 语义）
            val timed = store.loadProgressTimedMap()
            val entries = timed.entries
                .filter { it.value.first > 0L } // 0/负值是异常残留，不带上云
                .take(MAX_SYNC_ENTRIES)
            val deleted = tombstonesForPush(timed.keys)
            if (entries.isEmpty() && deleted.isEmpty()) {
                Log.d(TAG, "pushProgress: 本地无观看进度且无待传播删除，跳过")
                return@runCatching
            }
            val chatId = gateway.getSavedMessagesChatId() ?: return@runCatching
            val payload = encodePayload(entries, deleted)
            val messageId = gateway.findSyncMessage(chatId, MARKER)
            // R300：写段包进全域写预算批量槽——「编辑失败回落新建」是同一次推送的两步，
            // 不该各吃 30s 全局间隔（闸口径见 GatewaySync.SyncWriteBudget）
            if (!gateway.syncWriteBeginBatch()) {
                Log.d(TAG, "pushProgress: 全域写预算闸拒（静默/间隔未满），本轮跳过")
                return@runCatching
            }
            try {
                val ok = if (messageId != null) {
                    // 编辑同一条消息（更新而非刷屏）；消息被手删等失败时回落新建
                    //（批量槽内用 batched 变体：槽已过了 30s 全局间隔闸，内层不再重查）
                    gateway.editSyncTextBatched(chatId, messageId, payload)
                } else {
                    gateway.sendSyncTextBatched(chatId, payload) != null
                }
                if (!ok && messageId != null) {
                    Log.w(TAG, "pushProgress: 编辑失败（消息可能被删），回落新建同步消息")
                    gateway.sendSyncTextBatched(chatId, payload)
                }
                Log.i(TAG, "pushProgress: 已推送 ${entries.size} 条进度 + ${deleted.size} 条墓碑（编辑=$ok）")
            } finally {
                gateway.syncWriteEndBatch()
            }
        }.onFailure {
            Log.w(TAG, "pushProgress 失败（静默）: ${it.message}")
        }
    }

    // ---------------- 拉取与合并 ----------------

    /**
     * 从收藏夹拉取云端观看进度并合入本地，返回合入/删除的总条数（0 = 云端没有更新的内容）。
     * 合并规则（LWW + 删除传播，见类注释）：云端条目按 per-key 时间戳与本地比较，
     * 新者胜；墓碑覆盖早于它的本地进度，晚于它的本地进度（删除后重看）不动。
     */
    suspend fun pullProgress(): Int = runCatching {
        val chatId = gateway.getSavedMessagesChatId() ?: return@runCatching 0
        val messageId = gateway.findSyncMessage(chatId, MARKER)
        if (messageId == null) {
            Log.d(TAG, "pullProgress: 云端没有同步消息")
            return@runCatching 0
        }
        val text = gateway.readSyncText(chatId, messageId) ?: return@runCatching 0
        // 协议校验：marker 必须独占首行，JSON 从第二行起
        if (!text.startsWith(MARKER)) {
            Log.w(TAG, "pullProgress: 消息不含 marker 首行，按无效同步消息忽略")
            return@runCatching 0
        }
        val body = text.substringAfter('\n', "")
        if (body.isEmpty()) return@runCatching 0
        val root = JSONObject(body)
        val msgTs = root.optLong("ts", 0L)
        val positions = root.optJSONObject("positions") ?: return@runCatching 0
        val deleted = root.optJSONObject("deleted")
        val local = store.loadProgressTimedMap()
        var changed = 0

        synchronized(tombLock) {
            // 先应用删除墓碑：本地同 key 进度早于墓碑即删（本地更晚 = 删除后又看过，本地赢）
            if (deleted != null) {
                val dKeys = deleted.keys()
                while (dKeys.hasNext()) {
                    val key = dKeys.next()
                    val t = deleted.optLong(key, 0L)
                    if (key.isEmpty() || t <= 0L) continue // 坏条目跳过
                    val localTs = local[key]?.second
                    if (localTs != null && localTs >= t) continue
                    if (localTs != null) {
                        runCatching { store.removeProgress(key) }
                        changed++
                    }
                    mergeTombstoneLocked(key, t) // 记下墓碑：防本地/对端旧值复活
                }
            }
            // 再按 LWW 合入进度：云端不新于本地同 key（或不新于本地墓碑）即跳过
            val keys = positions.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val pos = decodePosition(positions, key, msgTs) ?: continue // 坏条目跳过
                val localTs = local[key]?.second
                if (localTs != null && localTs >= pos.second) continue // 本地更新或相同：不覆盖
                val tombTs = tombstones[key] ?: 0L
                if (tombTs > 0L && tombTs >= pos.second) continue // 该写入早于本地删除：保持删除
                runCatching { store.importProgress(key, pos.first, pos.second) }
                changed++
                if (tombTs > 0L) tombstones.remove(key) // 更新的写入压过墓碑：墓碑作废
            }
            persistTombstonesLocked()
        }
        Log.i(TAG, "pullProgress: 合入/删除共 $changed 条（云端 ${positions.length()} 条进度 + ${deleted?.length() ?: 0} 条墓碑）")
        changed
    }.onFailure {
        Log.w(TAG, "pullProgress 失败（静默）: ${it.message}")
    }.getOrDefault(0)

    // ---------------- 编解码 ----------------

    /**
     * 解析一条云端进度：V2 为 {"p":posMs,"t":ts} 对象；V1 为裸 long（ts 取消息级 ts 兜底）。
     * key 为空、posMs ≤0 视为坏条目返回 null。
     */
    private fun decodePosition(positions: JSONObject, key: String, msgTs: Long): Pair<Long, Long>? {
        if (key.isEmpty()) return null
        val any = positions.opt(key) ?: return null
        val posMs: Long
        val ts: Long
        when (any) {
            is JSONObject -> {
                posMs = any.optLong("p", 0L)
                ts = any.optLong("t", msgTs)
            }
            else -> { // V1 裸 long
                posMs = (any as? Number)?.toLong() ?: return null
                ts = msgTs
            }
        }
        if (posMs <= 0L) return null
        return posMs to ts
    }

    /**
     * 组装同步消息文本（V2）：marker 首行 + JSON 次行。
     * JSON 字段：device = 设备型号（排查多设备来源用）；v = 协议版本；
     * positions = key→{"p":posMs,"t":该进度最后更新毫秒}（LWW 依据）；
     * deleted = key→删除时刻毫秒（删除墓碑，传播"看完清掉"）；
     * ts = 本次写入时刻毫秒（供对端与人工排查判断新旧，兼作 V1 裸 long 条目的 ts 兜底）。
     */
    private fun encodePayload(
        entries: List<Map.Entry<String, Pair<Long, Long>>>,
        deleted: Map<String, Long>,
    ): String {
        val positions = JSONObject()
        entries.forEach { e ->
            positions.put(e.key, JSONObject().put("p", e.value.first).put("t", e.value.second))
        }
        val deletedObj = JSONObject()
        deleted.forEach { (k, t) -> deletedObj.put(k, t) }
        val body = JSONObject()
            .put("device", Build.MODEL ?: "Android")
            .put("v", 2)
            .put("positions", positions)
            .put("deleted", deletedObj)
            .put("ts", System.currentTimeMillis())
        return MARKER + "\n" + body.toString()
    }
}
