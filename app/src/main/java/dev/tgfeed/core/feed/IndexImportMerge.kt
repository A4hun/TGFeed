package dev.tgfeed.core.feed

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import dev.tgfeed.core.td.ColdStartQuietGate
import dev.tgfeed.core.td.SystemStormGate
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File

/**
 * R287 多设备索引合并/共享·合并执行器（断点续传批迁 Job，R262b/R266 同范式）。
 *
 * 上游：BackupManager.importBackupMergeIndex 把包内 deepindex/search.db（+ -wal/-shm）
 * 解到 [STAGING_DIR_NAME]/src.db 并落 import_merge_v1.json 状态文件；本 Job 由
 * DeepSearchIndex 点火（openDb 尾 + 指纹索引建成回调两处入口，单飞闸），
 * 全程跑在**主连接 + writeLock** 上（与爬虫/R266 批迁/回填 Job 串行错峰——
 * 主连接 busy_timeout=0，旁路连接写会互撞 SQLITE_BUSY，故绝不另开连接写本机库）。
 *
 * 五相位状态机（状态文件每批原子落盘，进程死亡/中断后下次 openDb 自动续跑）：
 *  EXTRACT（BackupManager 域：SAF zip 流不可续解=中断重解，extract.ok 标记防重解）
 *  → PREP（源库开库/WAL 归并/版本闸/列级自适应/建 r287_map 映射表）
 *  → ITEMS（源 items rowid 倒序批 500：分类裁决→合并/转 refs/新插+tags 增集）
 *  → REFS（源 repost_refs rowid 倒序：canonical 经 r287_map/fuid 兜底解析）
 *  → META（chatId 升序：行在 mergeMeta/行缺整行随迁——爬取断点与完成态随迁=
 *    配额协同，对端爬过的本机不重爬）
 *  → FINISH（清暂存+状态文件，删 R254 两缓存与首发账本——内容变了陈值必须重建）。
 *
 * 幂等（重复导入/中断重跑零效果）：items INSERT OR IGNORE key 判重 + 字段合并
 * 非空胜幂等 + refs INSERT OR IGNORE + tags delta 重放同值零写 + meta 合并纯函数
 * 重放同值。裁决口径全部在 [IndexMergePolicy]（JVM 单测锚）。
 *
 * 硬闸：
 *  - 本机 idx_items_fuid 未就绪不跑（分类③指纹点查缺索引=全表扫红线）——
 *    点火方已闸，本类 run 内复检；
 *  - 源库 user_version > 本机库版本 → 拒合并（防新版格式被旧版误读成乱数据），
 *    清理暂存+状态（明确失败一次，不留纠缠态）；
 *  - 删除零同步（用户明示不做）：全链路只增/只改不删。
 */
internal class IndexImportMerge(
    private val owner: DeepSearchIndex,
    private val progressSink: (String?) -> Unit,
) {

    // ---------------- 断点状态（import_merge_v1.json，原子 tmp+rename，R262b 范式） ----------------

    private enum class Phase { ITEMS, REFS, META, DONE }

    private class State(
        var phase: Phase,
        var itemsCursor: Long,
        var refsCursor: Long,
        var metaCursor: Long,
        // 计数账（日志/终报口径；全脱敏——只有数字）
        var inserted: Long = 0,      // INSERT_NEW：本机全新条目
        var itemMerged: Long = 0,    // MERGE_ITEM 有字段变化
        var itemDup: Long = 0,       // MERGE_ITEM 全同零动作（含重复导入）
        var refMerged: Long = 0,     // MERGE_REF 刷新
        var becameRef: Long = 0,     // BECOME_REF（key 新但内容指纹本机已有）
        var refsInserted: Long = 0,  // REFS 相位新挂别名
        var orphanRefs: Long = 0,    // canonical 解析失败跳过（宁缺不悬空）
        var metaAdopted: Long = 0,   // META 新行随迁
        var metaMerged: Long = 0,    // META 合并
        var tagEdges: Long = 0,      // tags delta 净增边
        var batchSkips: Long = 0,    // 批重试耗尽跳批
        @kotlin.jvm.Transient
        var batchWrites: Int = 0, // 本批写入数（批间隔有写/零写分档判定；不落盘）
    )

    private fun stateFile(): File = File(owner.storeDir, STATE_FILE_NAME)

    private fun readState(): State? = runCatching {
        val f = stateFile()
        if (!f.isFile) return null
        val o = JSONObject(f.readText())
        State(
            phase = Phase.valueOf(o.optString("phase", "ITEMS")),
            itemsCursor = o.optLong("itemsCursor", 0L),
            refsCursor = o.optLong("refsCursor", 0L),
            metaCursor = o.optLong("metaCursor", Long.MIN_VALUE),
            inserted = o.optLong("inserted"), itemMerged = o.optLong("itemMerged"),
            itemDup = o.optLong("itemDup"), refMerged = o.optLong("refMerged"),
            becameRef = o.optLong("becameRef"), refsInserted = o.optLong("refsInserted"),
            orphanRefs = o.optLong("orphanRefs"), metaAdopted = o.optLong("metaAdopted"),
            metaMerged = o.optLong("metaMerged"), tagEdges = o.optLong("tagEdges"),
            batchSkips = o.optLong("batchSkips"),
        )
    }.getOrNull()

    private fun writeState(s: State) {
        runCatching {
            val txt = JSONObject().apply {
                put("v", 1)
                put("phase", s.phase.name)
                put("itemsCursor", s.itemsCursor)
                put("refsCursor", s.refsCursor)
                put("metaCursor", s.metaCursor)
                put("inserted", s.inserted); put("itemMerged", s.itemMerged)
                put("itemDup", s.itemDup); put("refMerged", s.refMerged)
                put("becameRef", s.becameRef); put("refsInserted", s.refsInserted)
                put("orphanRefs", s.orphanRefs); put("metaAdopted", s.metaAdopted)
                put("metaMerged", s.metaMerged); put("tagEdges", s.tagEdges)
                put("batchSkips", s.batchSkips)
            }.toString()
            val tmp = File(owner.storeDir, "$STATE_FILE_NAME.tmp")
            tmp.writeText(txt)
            if (!tmp.renameTo(stateFile())) {
                tmp.delete()
                stateFile().writeText(txt)
            }
        }
    }

    // ---------------- 主流程 ----------------

    suspend fun run(d: SQLiteDatabase) {
        try {
            Log.i(TAG, "R287 合并协程入 run（等冷启动静默窗）") // 探针：区分「没点火」与「点火后卡闸」
            runCatching { ColdStartQuietGate.awaitQuiet(20_000) } // R208：冷启动让路+开机风暴窗
            Log.i(TAG, "R287 静默窗已过，读断点")
            val state = readState() ?: return // 无挂起合并
            if (state.phase == Phase.DONE) {
                cleanupAll()
                return
            }
            val staging = File(owner.storeDir, STAGING_DIR_NAME)
            val srcFile = File(staging, SRC_DB_NAME)
            if (!srcFile.isFile || !File(staging, EXTRACT_OK_NAME).isFile) {
                // 解包半途（进程死在 EXTRACT）：状态留盘；用户重走导入流程会重解并再点火
                Log.w(TAG, "R287 合并暂存不完整（解包未完成），等重新导入补齐")
                return
            }
            if (!owner.fuidIndexReadyCached(d)) {
                // 指纹索引未就绪：分类③会变全表扫红线。等建成回调再点火（状态留盘）
                Log.i(TAG, "R287 合并等待指纹索引就绪（建成后自动续跑）")
                return
            }
            Log.i(TAG, "R287 索引合并启动：断点 phase=${state.phase} items=${state.itemsCursor} refs=${state.refsCursor} meta=${state.metaCursor}")
            val src = SQLiteDatabase.openDatabase(srcFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                // 源库 WAL 归并（导出时若带 -wal，开库已自动重放；这里截断稳定文件）
                runCatching {
                    src.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                }
                // 版本闸：源库比本机库新=格式可能超认知，拒合并（防乱数据）
                val srcVersion = src.rawQuery("PRAGMA user_version", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
                val localVersion = d.rawQuery("PRAGMA user_version", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
                if (srcVersion > localVersion) {
                    Log.w(TAG, "R287 合并拒绝：数据包索引库 v$srcVersion 比本机 v$localVersion 新（请升级 App 后再导）")
                    progressSink(null)
                    cleanupAll()
                    return
                }
                val schema = SourceSchema(src)
                // 本机侧列级自适应：lastMsgSec（R286 v13）在本机库可能尚未迁移到——探测一次定写入面
                localHasLastMsgSec = SourceSchema.columnExists(d, "meta", "lastMsgSec")
                src.execSQL("CREATE TABLE IF NOT EXISTS $MAP_TABLE(src_rowid INTEGER PRIMARY KEY, dst_rowid INTEGER NOT NULL)")

                // 相位顺序推进（每相位函数跑完推进 state.phase；同一打开会话内直通到底）
                if (state.phase == Phase.ITEMS) runItemsPhase(d, src, schema, state)
                if (state.phase == Phase.REFS) runRefsPhase(d, src, schema, state)
                if (state.phase == Phase.META) runMetaPhase(d, src, schema, state)
            } finally {
                runCatching { src.close() }
            }
            // FINISH
            val summary = "R287 索引合并完成：新增 ${state.inserted} 条 + 别名 ${state.becameRef + state.refsInserted} 条" +
                "（字段合并 ${state.itemMerged} / 全同 ${state.itemDup} / 别名刷新 ${state.refMerged} / " +
                "孤儿别名跳过 ${state.orphanRefs}）；meta 随迁 ${state.metaAdopted} + 合并 ${state.metaMerged}；" +
                "标签边 +${state.tagEdges}；跳批 ${state.batchSkips}"
            Log.i(TAG, summary)
            cleanupAll()
            progressSink(null)
        } catch (e: Throwable) {
            // 断点已随每批落盘：崩溃/取消后下次 openDb 自动续跑（不重头）
            Log.e(TAG, "R287 索引合并中断（断点已落盘，下次启动自动续跑）", e)
            progressSink(null)
        }
    }

    /** FINISH/拒绝共用清理：暂存区+状态文件全清，撞 R254 缓存版本（内容已变，陈值必须重建） */
    private fun cleanupAll() {
        runCatching { File(owner.storeDir, STAGING_DIR_NAME).deleteRecursively() }
        runCatching { stateFile().delete() }
        runCatching { File(owner.storeDir, "$STATE_FILE_NAME.tmp").delete() }
        // R254 撞版本（R266 v2 先例）：星图成品缓存+基因聚合缓存按旧内容装配，合并后必须重建
        val filesDir = owner.storeDir.parentFile
        if (filesDir != null) {
            runCatching { File(File(filesDir, "galaxy"), "genome_rollup_cache_v2.bin").delete() }
            runCatching { File(File(filesDir, "galaxy"), "taste_map_cache_v2.bin").delete() }
            // 首发溯源账本（fuid→副本判定 LRU）：合并后副本面变了，清掉让点按重查
            runCatching { File(filesDir, "first_seen_ledger.json").delete() }
        }
    }

    // ---------------- 源库列级自适应（跨版本安全：v<8 无解析列、v<10 无 dead、v<12 无 ttlSec） ----------------

    private class SourceSchema(src: SQLiteDatabase) {
        val hasParsedTitle: Boolean
        val hasItemTags: Boolean
        val hasRepostRefs: Boolean
        val metaCols: List<String>

        init {
            hasParsedTitle = columnExists(src, "items", "parsed_title")
            hasItemTags = columnExists(src, "items", "item_tags")
            hasRepostRefs = src.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='repost_refs'", null,
            ).use { it.moveToFirst() }
            val all = listOf(
                "cursor", "exhausted", "imported", "mediaPass", "needDeepen", "sweepCursor",
                "lastHeadRefreshTs", "sampled", "tier", "dead", "ttlSec", "lastMsgSec",
            )
            metaCols = all.filter { columnExists(src, "meta", it) }
        }

        companion object {
            fun columnExists(db: SQLiteDatabase, table: String, column: String): Boolean =
                db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                    var found = false
                    while (c.moveToNext()) if (c.getString(1) == column) { found = true; break }
                    found
                }
        }
    }

    // ---------------- ITEMS 相位 ----------------

    /** 源 items 行原料（解析两列按源库版本可空） */
    private class SrcItem(
        val srcRowid: Long, val key: String, val chatId: Long, val messageId: Long,
        val albumId: Long, val mediaType: Int, val coverFileId: Int, val caption: String,
        val channelTitle: String, val fileId: Int, val fuid: String, val mimeType: String,
        val durationSec: Int, val sizeBytes: Long, val width: Int, val height: Int,
        val dateEpochSec: Long, val parsedTitle: String?, val itemTagsJson: String?,
    )

    private suspend fun runItemsPhase(d: SQLiteDatabase, src: SQLiteDatabase, schema: SourceSchema, state: State) {
        if (state.itemsCursor == 0L) {
            state.itemsCursor = src.rawQuery("SELECT MAX(rowid) FROM items", null).use { c ->
                (if (c.moveToFirst()) c.getLong(0) else 0L) + 1L
            }
            writeState(state)
        }
        val parsedCols = if (schema.hasParsedTitle) ", parsed_title" else ""
        val tagsCols = if (schema.hasItemTags) ", item_tags" else ""
        var batches = 0
        while (true) {
            val batch = ArrayList<SrcItem>(ITEMS_BATCH)
            var minRowidSeen = Long.MAX_VALUE
            src.rawQuery(
                "SELECT rowid, key, chatId, messageId, albumId, mediaType, coverFileId, caption, " +
                    "channelTitle, fileId, fileUniqueId, mimeType, durationSec, sizeBytes, width, height, " +
                    "dateEpochSec$parsedCols$tagsCols FROM items WHERE rowid < ? ORDER BY rowid DESC LIMIT $ITEMS_BATCH",
                arrayOf(state.itemsCursor.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    val rid = c.getLong(0)
                    if (rid < minRowidSeen) minRowidSeen = rid
                    batch += SrcItem(
                        srcRowid = rid, key = c.getString(1), chatId = c.getLong(2),
                        messageId = c.getLong(3), albumId = c.getLong(4), mediaType = c.getInt(5),
                        coverFileId = c.getInt(6), caption = c.getString(7) ?: "",
                        channelTitle = c.getString(8) ?: "", fileId = c.getInt(9),
                        fuid = c.getString(10) ?: "", mimeType = c.getString(11) ?: "",
                        durationSec = c.getInt(12), sizeBytes = c.getLong(13), width = c.getInt(14),
                        height = c.getInt(15), dateEpochSec = c.getLong(16),
                        parsedTitle = if (schema.hasParsedTitle) c.getString(17) else null,
                        itemTagsJson = if (schema.hasItemTags) {
                            c.getString(if (schema.hasParsedTitle) 18 else 17)
                        } else {
                            null
                        },
                    )
                }
            }
            if (batch.isEmpty()) {
                state.phase = Phase.REFS
                writeState(state)
                Log.i(TAG, "R287 ITEMS 相位完成：新增 ${state.inserted} / 合并 ${state.itemMerged} / 全同 ${state.itemDup} / 转别名 ${state.becameRef}")
                return
            }
            val ok = runBatchWithRetry("ITEMS", state) { processItemsBatch(d, src, state, batch) }
            if (!ok) {
                state.batchSkips++
                Log.e(TAG, "R287 ITEMS 批重试耗尽跳批（${batch.size} 行，重导幂等兜回）")
            }
            state.itemsCursor = minRowidSeen
            batches++
            writeState(state)
            if (batches % LOG_EVERY == 0) {
                Log.d(TAG, "R287 ITEMS 推进：断点 rowid=${state.itemsCursor}（新增 ${state.inserted} / 合并 ${state.itemMerged}）")
            }
            progressSink("正在合并索引：条目阶段（新增 ${state.inserted} / 合并 ${state.itemMerged}）")
            val wrote = state.batchWrites > 0
            delay(if (wrote) BATCH_GAP_MS else IDLE_GAP_MS)
            if (wrote && !SystemStormGate.clearNow()) {
                SystemStormGate.awaitClear("R287 索引合并") // R208：高负载窗持有即等
            }
        }
    }

    /**
     * ITEMS 单批本体（本机事务内：分类→合并/转别名/新插+fts/tags 同事务；
     * 映射表随批写源库——本机提交在先、映射在后，映射缺失由 REFS 相位 fuid 兜底）。
     * 返回 true=批已提交（含零动作批）。
     */
    private fun processItemsBatch(d: SQLiteDatabase, src: SQLiteDatabase, state: State, batch: List<SrcItem>): Boolean {
        val before = state.inserted + state.itemMerged + state.becameRef + state.refMerged
        val mapEntries = ArrayList<Pair<Long, Long>>(batch.size)
        val tagDeltas = ArrayList<DeepSearchIndex.TagDeltaRow>(64)
        synchronized(owner.writeLock) {
            d.beginTransaction()
            try {
                for (s in batch) {
                    val dst = mergeOneItem(d, s, state, tagDeltas)
                    if (dst > 0L) mapEntries += s.srcRowid to dst
                }
                if (tagDeltas.isNotEmpty()) {
                    state.tagEdges += owner.applyTagDeltaBatchLocked(d, tagDeltas)
                }
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
        if (mapEntries.isNotEmpty()) {
            src.beginTransaction()
            try {
                val st = src.compileStatement("INSERT OR REPLACE INTO $MAP_TABLE(src_rowid, dst_rowid) VALUES(?,?)")
                try {
                    for ((s, t) in mapEntries) {
                        st.clearBindings()
                        st.bindLong(1, s)
                        st.bindLong(2, t)
                        st.executeInsert()
                    }
                } finally {
                    runCatching { st.close() }
                }
                src.setTransactionSuccessful()
            } finally {
                src.endTransaction()
            }
        }
        state.batchWrites = ((state.inserted + state.itemMerged + state.becameRef + state.refMerged) - before).toInt()
        return true
    }

    /**
     * 单条 items 行分类落位（调用方持 writeLock+本机事务）。返回映射目标 rowid
     * （MERGE_ITEM=本机行；MERGE_REF/BECOME_REF=canonical 行；INSERT_NEW=新行；
     * 0=不映射——R12 冲突闸拒收编而整行新插的路径也不会到这里，恒有映射）。
     */
    private fun mergeOneItem(
        d: SQLiteDatabase,
        s: SrcItem,
        state: State,
        tagDeltas: MutableList<DeepSearchIndex.TagDeltaRow>,
    ): Long {
        // ① 本机 items key 点查（主键 µs 级）
        var localRowid = 0L
        var local: IndexMergePolicy.ItemSide? = null
        d.rawQuery(
            "SELECT rowid, caption, channelTitle, mimeType, albumId, coverFileId, fileId, " +
                "durationSec, sizeBytes, width, height, dateEpochSec, parsed_title, item_tags FROM items WHERE key=?",
            arrayOf(s.key),
        ).use { c ->
            if (c.moveToFirst()) {
                localRowid = c.getLong(0)
                local = IndexMergePolicy.ItemSide(
                    caption = c.getString(1) ?: "", channelTitle = c.getString(2) ?: "",
                    mimeType = c.getString(3) ?: "", albumId = c.getLong(4),
                    coverFileId = c.getInt(5), fileId = c.getInt(6), durationSec = c.getInt(7),
                    sizeBytes = c.getLong(8), width = c.getInt(9), height = c.getInt(10),
                    dateEpochSec = c.getLong(11), parsedTitle = c.getString(12),
                    itemTags = owner.parseTagsJson(c.getString(13)),
                )
            }
        }
        // ② 本机 refs key 点查（①未命中才查；返回 canonRowid+发布侧+覆盖列）
        var refCanonRowid = 0L
        var refCaption: String? = null
        var refChannelTitle = ""
        var refAlbumId = 0L
        var refCoverFileId = -1
        var refDurationSec = 0
        var refDateSec = 0L
        val localRefExists = local == null && run {
            d.rawQuery(
                "SELECT canonRowid, caption, channelTitle, albumId, coverFileId, durationSec, dateEpochSec " +
                    "FROM repost_refs WHERE key=?",
                arrayOf(s.key),
            ).use { c ->
                if (c.moveToFirst()) {
                    refCanonRowid = c.getLong(0)
                    refCaption = if (c.isNull(1)) null else c.getString(1)
                    refChannelTitle = c.getString(2) ?: ""
                    refAlbumId = c.getLong(3)
                    refCoverFileId = c.getInt(4)
                    refDurationSec = c.getInt(5)
                    refDateSec = c.getLong(6)
                    true
                } else {
                    false
                }
            }
        }
        // ③④ 内容指纹点查（②未命中且指纹非空才查；idx_items_fuid/idx_refs_fuid 覆盖）
        var fuidItemRowid = 0L
        var fuidItemType = -1
        var fuidItemSize = -1L
        var fuidItemCaption = ""
        var fuidRefCanon = 0L
        var fuidRefCanonCaption = ""
        if (local == null && !localRefExists && s.fuid.isNotEmpty()) {
            d.rawQuery(
                "SELECT rowid, mediaType, sizeBytes, caption FROM items WHERE fileUniqueId=? ORDER BY rowid LIMIT 1",
                arrayOf(s.fuid),
            ).use { c ->
                if (c.moveToFirst()) {
                    fuidItemRowid = c.getLong(0)
                    fuidItemType = c.getInt(1)
                    fuidItemSize = c.getLong(2)
                    fuidItemCaption = c.getString(3) ?: ""
                }
            }
            if (fuidItemRowid == 0L) {
                d.rawQuery(REFS_FUID_LOOKUP_SQL, arrayOf(s.fuid))
                    .use { c -> if (c.moveToFirst()) fuidRefCanon = c.getLong(0) }
                if (fuidRefCanon > 0L) {
                    // canonical 悬空防御 + 覆盖列裁决原料（canonical caption）
                    val cap = d.rawQuery(
                        "SELECT caption FROM items WHERE rowid=?",
                        arrayOf(fuidRefCanon.toString()),
                    ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    if (cap == null) fuidRefCanon = 0L else fuidRefCanonCaption = cap
                }
            }
        }
        val incoming = IndexMergePolicy.ItemSide(
            caption = s.caption, channelTitle = s.channelTitle, mimeType = s.mimeType,
            albumId = s.albumId, coverFileId = s.coverFileId, fileId = s.fileId,
            durationSec = s.durationSec, sizeBytes = s.sizeBytes, width = s.width,
            height = s.height, dateEpochSec = s.dateEpochSec, parsedTitle = s.parsedTitle,
            itemTags = owner.parseTagsJson(s.itemTagsJson),
        )
        return when (
            IndexMergePolicy.classify(local != null, localRefExists, fuidItemRowid, fuidRefCanon)
        ) {
            IndexMergePolicy.MergeAction.MERGE_ITEM -> {
                val l = local!!
                val m = IndexMergePolicy.mergeItemFields(l, incoming)
                if (m.changed) {
                    mergeItemUpdate(d, localRowid, l, m)
                    state.itemMerged++
                } else {
                    state.itemDup++
                }
                if (m.tagsGrew) {
                    tagDeltas += DeepSearchIndex.TagDeltaRow(
                        localRowid, s.chatId, m.dateEpochSec,
                        owner.tagAttributionOfLocked(d, localRowid), m.itemTags!!,
                    )
                }
                localRowid
            }
            IndexMergePolicy.MergeAction.MERGE_REF -> {
                // 发布侧非空胜刷新（refreshRefLocked 同口径扩展）；覆盖列仅本机为 NULL 时补
                val canonCaption = d.rawQuery(
                    "SELECT caption FROM items WHERE rowid=?",
                    arrayOf(refCanonRowid.toString()),
                ).use { c -> if (c.moveToFirst()) c.getString(0) ?: "" else "" }
                val newAlbum = if (refAlbumId == 0L) s.albumId else refAlbumId
                val newCover = if (refCoverFileId < 0 && s.coverFileId >= 0) s.coverFileId else refCoverFileId
                val newDur = if (refDurationSec == 0) s.durationSec else refDurationSec
                val newTitle = if (refChannelTitle.isEmpty()) s.channelTitle else refChannelTitle
                val newDate = if (refDateSec == 0L) s.dateEpochSec else refDateSec
                val newOverride = refCaption
                    ?: if (s.caption.isNotEmpty()) RepostMergePolicy.captionOverride(s.caption, canonCaption) else null
                if (newAlbum != refAlbumId || newCover != refCoverFileId || newDur != refDurationSec ||
                    newTitle != refChannelTitle || newDate != refDateSec || newOverride != refCaption
                ) {
                    val upd = d.compileStatement(
                        "UPDATE repost_refs SET albumId=?, coverFileId=?, durationSec=?, channelTitle=?, " +
                            "dateEpochSec=?, caption=? WHERE key=?",
                    )
                    try {
                        upd.clearBindings()
                        upd.bindLong(1, newAlbum)
                        upd.bindLong(2, newCover.toLong())
                        upd.bindLong(3, newDur.toLong())
                        upd.bindString(4, newTitle)
                        upd.bindLong(5, newDate)
                        if (newOverride == null) upd.bindNull(6) else upd.bindString(6, newOverride)
                        upd.bindString(7, s.key)
                        upd.executeUpdateDelete()
                    } finally {
                        runCatching { upd.close() }
                    }
                    state.refMerged++
                } else {
                    state.itemDup++
                }
                refCanonRowid
            }
            IndexMergePolicy.MergeAction.BECOME_REF -> {
                val canonRowid = if (fuidItemRowid > 0L) fuidItemRowid else fuidRefCanon
                val canonCaption = if (fuidItemRowid > 0L) fuidItemCaption else fuidRefCanonCaption
                val canonType = if (fuidItemRowid > 0L) fuidItemType else s.mediaType
                val canonSize = if (fuidItemRowid > 0L) fuidItemSize else s.sizeBytes
                // R12 跨设备变体：内容不一致=指纹误判嫌疑，宁整行新插也不错并
                if (!IndexMergePolicy.contentCompatible(canonType, canonSize, s.mediaType, s.sizeBytes)) {
                    Log.w(TAG, "R287 指纹冲突闸：fuid 命中但 mediaType/sizeBytes 分歧，整行新插留证（不收编）")
                    return insertNewItem(d, s, state, tagDeltas)
                }
                owner.insertRefLocked(
                    d,
                    DeepSearchIndex.RefRow(
                        key = s.key, chatId = s.chatId, messageId = s.messageId,
                        albumId = s.albumId, mediaType = s.mediaType, durationSec = s.durationSec,
                        coverFileId = s.coverFileId, channelTitle = s.channelTitle,
                        dateEpochSec = s.dateEpochSec, fuid = s.fuid, canonRowid = canonRowid,
                        captionOverride = RepostMergePolicy.captionOverride(s.caption, canonCaption),
                    ),
                )
                state.becameRef++
                canonRowid
            }
            IndexMergePolicy.MergeAction.INSERT_NEW -> insertNewItem(d, s, state, tagDeltas)
        }
    }

    /** MERGE_ITEM 落库（非空胜合并值 UPDATE + caption 侧变化时 fts/tokens 同步重算） */
    private fun mergeItemUpdate(
        d: SQLiteDatabase,
        localRowid: Long,
        old: IndexMergePolicy.ItemSide,
        m: IndexMergePolicy.MergedItem,
    ) {
        val sql = if (owner.useFts5) {
            "UPDATE items SET albumId=?, coverFileId=?, fileId=?, caption=?, channelTitle=?, " +
                "mimeType=?, durationSec=?, sizeBytes=?, width=?, height=?, dateEpochSec=?, " +
                "parsed_title=?, item_tags=? WHERE rowid=?"
        } else {
            "UPDATE items SET albumId=?, coverFileId=?, fileId=?, caption=?, channelTitle=?, " +
                "mimeType=?, durationSec=?, sizeBytes=?, width=?, height=?, dateEpochSec=?, " +
                "parsed_title=?, item_tags=?, tokens=? WHERE rowid=?"
        }
        val newTokens = if (!owner.useFts5 && m.captionSideChanged) {
            owner.indexTokensOf(m.caption, m.channelTitle)
        } else {
            null
        }
        val upd = d.compileStatement(sql)
        try {
            upd.clearBindings()
            upd.bindLong(1, m.albumId)
            upd.bindLong(2, m.coverFileId.toLong())
            upd.bindLong(3, m.fileId.toLong())
            upd.bindString(4, m.caption)
            upd.bindString(5, m.channelTitle)
            upd.bindString(6, m.mimeType)
            upd.bindLong(7, m.durationSec.toLong())
            upd.bindLong(8, m.sizeBytes)
            upd.bindLong(9, m.width.toLong())
            upd.bindLong(10, m.height.toLong())
            upd.bindLong(11, m.dateEpochSec)
            if (m.parsedTitle == null) upd.bindNull(12) else upd.bindString(12, m.parsedTitle)
            val tagsJson = m.itemTags?.let { tags -> tags.joinToString(",", "[", "]") { "\"$it\"" } }
            if (tagsJson == null) upd.bindNull(13) else upd.bindString(13, tagsJson)
            if (!owner.useFts5) {
                // LIKE 形态：caption 侧变了才重算词位（没变则 tokens 列保持原值——绑原值重写）
                upd.bindString(14, " " + (newTokens ?: owner.indexTokensOf(old.caption, old.channelTitle)) + " ")
                upd.bindLong(15, localRowid)
            } else {
                upd.bindLong(14, localRowid)
            }
            upd.executeUpdateDelete()
        } finally {
            runCatching { upd.close() }
        }
        if (owner.useFts5 && m.captionSideChanged) {
            // contentless 删除要求旧词位串与被删行逐字节一致（backfillConflictLocked 同源配方）
            owner.ftsDeleteLocked(d, localRowid, owner.indexTokensOf(old.caption, old.channelTitle))
            val ins = d.compileStatement("INSERT INTO fts(rowid, tokens) VALUES(?,?)")
            try {
                ins.clearBindings()
                ins.bindLong(1, localRowid)
                ins.bindString(2, owner.indexTokensOf(m.caption, m.channelTitle))
                ins.executeInsert()
            } finally {
                runCatching { ins.close() }
            }
        }
    }

    /** INSERT_NEW 落库（整行新插：解析列随迁不重新解析 + fts/tokens 本机形态 + tags 增集入账） */
    private fun insertNewItem(
        d: SQLiteDatabase,
        s: SrcItem,
        state: State,
        tagDeltas: MutableList<DeepSearchIndex.TagDeltaRow>,
    ): Long {
        val sql = if (owner.useFts5) {
            "INSERT OR IGNORE INTO items(key, chatId, messageId, albumId, mediaType, coverFileId, " +
                "caption, channelTitle, fileId, fileUniqueId, mimeType, durationSec, sizeBytes, " +
                "width, height, dateEpochSec, parsed_title, item_tags) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        } else {
            "INSERT OR IGNORE INTO items(key, chatId, messageId, albumId, mediaType, coverFileId, " +
                "caption, channelTitle, fileId, fileUniqueId, mimeType, durationSec, sizeBytes, " +
                "width, height, dateEpochSec, parsed_title, item_tags, tokens) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        }
        val ins = d.compileStatement(sql)
        val rowid: Long
        try {
            ins.clearBindings()
            ins.bindString(1, s.key)
            ins.bindLong(2, s.chatId)
            ins.bindLong(3, s.messageId)
            ins.bindLong(4, s.albumId)
            ins.bindLong(5, s.mediaType.toLong())
            ins.bindLong(6, s.coverFileId.toLong())
            ins.bindString(7, s.caption)
            ins.bindString(8, s.channelTitle)
            ins.bindLong(9, s.fileId.toLong())
            ins.bindString(10, s.fuid)
            ins.bindString(11, s.mimeType)
            ins.bindLong(12, s.durationSec.toLong())
            ins.bindLong(13, s.sizeBytes)
            ins.bindLong(14, s.width.toLong())
            ins.bindLong(15, s.height.toLong())
            ins.bindLong(16, s.dateEpochSec)
            if (s.parsedTitle == null) ins.bindNull(17) else ins.bindString(17, s.parsedTitle)
            if (s.itemTagsJson == null) ins.bindNull(18) else ins.bindString(18, s.itemTagsJson)
            if (!owner.useFts5) {
                ins.bindString(19, " " + owner.indexTokensOf(s.caption, s.channelTitle) + " ")
            }
            rowid = ins.executeInsert()
        } finally {
            runCatching { ins.close() }
        }
        if (rowid == -1L) {
            // 竞态防御：分类点查后被并发插入同 key（理论上 writeLock 内不可能，双保险幂等）
            state.itemDup++
            return d.rawQuery("SELECT rowid FROM items WHERE key=?", arrayOf(s.key)).use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }
        }
        if (owner.useFts5) {
            val fi = d.compileStatement("INSERT INTO fts(rowid, tokens) VALUES(?,?)")
            try {
                fi.clearBindings()
                fi.bindLong(1, rowid)
                fi.bindString(2, owner.indexTokensOf(s.caption, s.channelTitle))
                fi.executeInsert()
            } finally {
                runCatching { fi.close() }
            }
        }
        // tags 边由 item_tags 真值重建（台账不并源表——计数由 delta 聚合天然重算）
        val tags = owner.parseTagsJson(s.itemTagsJson)
        if (!tags.isNullOrEmpty()) {
            tagDeltas += DeepSearchIndex.TagDeltaRow(rowid, s.chatId, s.dateEpochSec, emptySet(), tags)
        }
        state.inserted++
        return rowid
    }

    // ---------------- REFS 相位（源 repost_refs；v<11 源库无此表直通跳过） ----------------

    private class SrcRef(
        val key: String, val chatId: Long, val messageId: Long, val albumId: Long,
        val mediaType: Int, val durationSec: Int, val coverFileId: Int, val channelTitle: String,
        val dateEpochSec: Long, val fuid: String, val srcCanonRowid: Long, val captionOverride: String?,
    )

    private suspend fun runRefsPhase(d: SQLiteDatabase, src: SQLiteDatabase, schema: SourceSchema, state: State) {
        if (!schema.hasRepostRefs) {
            state.phase = Phase.META
            writeState(state)
            return
        }
        if (state.refsCursor == 0L) {
            state.refsCursor = src.rawQuery("SELECT MAX(rowid) FROM repost_refs", null).use { c ->
                (if (c.moveToFirst()) c.getLong(0) else 0L) + 1L
            }
            writeState(state)
        }
        var batches = 0
        while (true) {
            val batch = ArrayList<SrcRef>(REFS_BATCH)
            var minRowidSeen = Long.MAX_VALUE
            src.rawQuery(
                "SELECT key, chatId, messageId, albumId, mediaType, durationSec, coverFileId, " +
                    "channelTitle, dateEpochSec, fuid, canonRowid, caption, rowid FROM repost_refs " +
                    "WHERE rowid < ? ORDER BY rowid DESC LIMIT $REFS_BATCH",
                arrayOf(state.refsCursor.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    val rid = c.getLong(12)
                    if (rid < minRowidSeen) minRowidSeen = rid
                    batch += SrcRef(
                        key = c.getString(0), chatId = c.getLong(1), messageId = c.getLong(2),
                        albumId = c.getLong(3), mediaType = c.getInt(4), durationSec = c.getInt(5),
                        coverFileId = c.getInt(6), channelTitle = c.getString(7) ?: "",
                        dateEpochSec = c.getLong(8), fuid = c.getString(9) ?: "",
                        srcCanonRowid = c.getLong(10),
                        captionOverride = if (c.isNull(11)) null else c.getString(11),
                    )
                }
            }
            if (batch.isEmpty()) {
                state.phase = Phase.META
                writeState(state)
                Log.i(TAG, "R287 REFS 相位完成：新挂 ${state.refsInserted} / 孤儿跳过 ${state.orphanRefs}")
                return
            }
            val ok = runBatchWithRetry("REFS", state) { processRefsBatch(d, src, state, batch) }
            if (!ok) {
                state.batchSkips++
                Log.e(TAG, "R287 REFS 批重试耗尽跳批（${batch.size} 行，重导幂等兜回）")
            }
            state.refsCursor = minRowidSeen
            batches++
            writeState(state)
            if (batches % LOG_EVERY == 0) {
                Log.d(TAG, "R287 REFS 推进：断点 rowid=${state.refsCursor}（新挂 ${state.refsInserted}）")
            }
            progressSink("正在合并索引：转发别名阶段（新增 ${state.refsInserted}）")
            delay(if (state.batchWrites > 0) BATCH_GAP_MS else IDLE_GAP_MS)
            if (state.batchWrites > 0 && !SystemStormGate.clearNow()) {
                SystemStormGate.awaitClear("R287 索引合并")
            }
        }
    }

    private fun processRefsBatch(d: SQLiteDatabase, src: SQLiteDatabase, state: State, batch: List<SrcRef>): Boolean {
        val before = state.refsInserted + state.refMerged
        synchronized(owner.writeLock) {
            d.beginTransaction()
            try {
                for (r in batch) mergeOneRef(d, src, r, state)
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
        state.batchWrites = ((state.refsInserted + state.refMerged) - before).toInt()
        return true
    }

    /** 单条来源 refs 落位：撞 items 跳过（本机全量行更全）；撞 refs 非空胜；否则解析 canonical 挂入 */
    private fun mergeOneRef(d: SQLiteDatabase, src: SQLiteDatabase, r: SrcRef, state: State) {
        // ① 本机 items key 在 → 本机全量行信息更全，跳过
        val itemExists = d.rawQuery("SELECT 1 FROM items WHERE key=?", arrayOf(r.key)).use { it.moveToFirst() }
        if (itemExists) {
            state.itemDup++
            return
        }
        // ② 本机 refs key 在 → 发布侧非空胜 + 覆盖列仅 NULL 时补（对**本机** canonical 现值重算）
        var refCanonRowid = 0L
        var refCaption: String? = null
        var refChannelTitle = ""
        var refAlbumId = 0L
        var refCoverFileId = -1
        var refDurationSec = 0
        var refDateSec = 0L
        val refExists = d.rawQuery(
            "SELECT canonRowid, caption, channelTitle, albumId, coverFileId, durationSec, dateEpochSec " +
                "FROM repost_refs WHERE key=?",
            arrayOf(r.key),
        ).use { c ->
            if (c.moveToFirst()) {
                refCanonRowid = c.getLong(0)
                refCaption = if (c.isNull(1)) null else c.getString(1)
                refChannelTitle = c.getString(2) ?: ""
                refAlbumId = c.getLong(3)
                refCoverFileId = c.getInt(4)
                refDurationSec = c.getInt(5)
                refDateSec = c.getLong(6)
                true
            } else {
                false
            }
        }
        if (refExists) {
            val canonCaption = d.rawQuery(
                "SELECT caption FROM items WHERE rowid=?",
                arrayOf(refCanonRowid.toString()),
            ).use { c -> if (c.moveToFirst()) c.getString(0) ?: "" else "" }
            val effectiveIncoming = r.captionOverride ?: srcCanonCaption(src, r.srcCanonRowid) ?: ""
            val newAlbum = if (refAlbumId == 0L) r.albumId else refAlbumId
            val newCover = if (refCoverFileId < 0 && r.coverFileId >= 0) r.coverFileId else refCoverFileId
            val newDur = if (refDurationSec == 0) r.durationSec else refDurationSec
            val newTitle = if (refChannelTitle.isEmpty()) r.channelTitle else refChannelTitle
            val newDate = if (refDateSec == 0L) r.dateEpochSec else refDateSec
            val newOverride = refCaption
                ?: if (effectiveIncoming.isNotEmpty()) RepostMergePolicy.captionOverride(effectiveIncoming, canonCaption) else null
            if (newAlbum != refAlbumId || newCover != refCoverFileId || newDur != refDurationSec ||
                newTitle != refChannelTitle || newDate != refDateSec || newOverride != refCaption
            ) {
                val upd = d.compileStatement(
                    "UPDATE repost_refs SET albumId=?, coverFileId=?, durationSec=?, channelTitle=?, " +
                        "dateEpochSec=?, caption=? WHERE key=?",
                )
                try {
                    upd.clearBindings()
                    upd.bindLong(1, newAlbum)
                    upd.bindLong(2, newCover.toLong())
                    upd.bindLong(3, newDur.toLong())
                    upd.bindString(4, newTitle)
                    upd.bindLong(5, newDate)
                    if (newOverride == null) upd.bindNull(6) else upd.bindString(6, newOverride)
                    upd.bindString(7, r.key)
                    upd.executeUpdateDelete()
                } finally {
                    runCatching { upd.close() }
                }
                state.refMerged++
            } else {
                state.itemDup++
            }
            return
        }
        // ③ canonical 解析：映射表优先（ITEMS 相位逐行落），缺失 fuid 双表兜底
        var canonRowid = src.rawQuery(
            "SELECT dst_rowid FROM $MAP_TABLE WHERE src_rowid=?",
            arrayOf(r.srcCanonRowid.toString()),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        if (canonRowid == 0L && r.fuid.isNotEmpty()) {
            canonRowid = d.rawQuery(
                "SELECT rowid FROM items WHERE fileUniqueId=? ORDER BY rowid LIMIT 1",
                arrayOf(r.fuid),
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            if (canonRowid == 0L) {
                canonRowid = d.rawQuery(REFS_FUID_LOOKUP_SQL, arrayOf(r.fuid))
                    .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            }
        }
        // 悬空防御：目标 canonical 必须真实存在（宁缺不悬空——R266 不变式）
        val canonOk = canonRowid > 0L && d.rawQuery(
            "SELECT 1 FROM items WHERE rowid=?",
            arrayOf(canonRowid.toString()),
        ).use { it.moveToFirst() }
        if (!canonOk) {
            state.orphanRefs++
            return
        }
        val localCanonCaption = d.rawQuery(
            "SELECT caption FROM items WHERE rowid=?",
            arrayOf(canonRowid.toString()),
        ).use { c -> if (c.moveToFirst()) c.getString(0) ?: "" else "" }
        val effective = r.captionOverride ?: srcCanonCaption(src, r.srcCanonRowid) ?: ""
        owner.insertRefLocked(
            d,
            DeepSearchIndex.RefRow(
                key = r.key, chatId = r.chatId, messageId = r.messageId, albumId = r.albumId,
                mediaType = r.mediaType, durationSec = r.durationSec, coverFileId = r.coverFileId,
                channelTitle = r.channelTitle, dateEpochSec = r.dateEpochSec, fuid = r.fuid,
                canonRowid = canonRowid,
                captionOverride = if (effective.isEmpty()) {
                    RepostMergePolicy.captionOverride("", localCanonCaption) // 空文案忠实保留（=canonical 同则 null）
                } else {
                    RepostMergePolicy.captionOverride(effective, localCanonCaption)
                },
            ),
        )
        state.refsInserted++
    }

    /** 来源 canonical 的 caption（覆盖列还原有效文案的原料；源库 items 主键点查） */
    private fun srcCanonCaption(src: SQLiteDatabase, srcCanonRowid: Long): String? {
        if (srcCanonRowid <= 0L) return null
        return src.rawQuery(
            "SELECT caption FROM items WHERE rowid=?",
            arrayOf(srcCanonRowid.toString()),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    // ---------------- META 相位（台账随迁：爬过的不重爬） ----------------

    private suspend fun runMetaPhase(d: SQLiteDatabase, src: SQLiteDatabase, schema: SourceSchema, state: State) {
        val colsSql = schema.metaCols.joinToString(",")
        var batches = 0
        while (true) {
            val batch = ArrayList<Pair<Long, IndexMergePolicy.MetaSide>>(META_BATCH)
            var maxChatSeen = Long.MIN_VALUE
            src.rawQuery(
                "SELECT chatId, $colsSql FROM meta WHERE chatId > ? ORDER BY chatId ASC LIMIT $META_BATCH",
                arrayOf(state.metaCursor.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    val chatId = c.getLong(0)
                    if (chatId > maxChatSeen) maxChatSeen = chatId
                    batch += chatId to readMetaSide(c, schema.metaCols, 1)
                }
            }
            if (batch.isEmpty()) {
                state.phase = Phase.DONE
                writeState(state)
                Log.i(TAG, "R287 META 相位完成：随迁 ${state.metaAdopted} / 合并 ${state.metaMerged}")
                return
            }
            val ok = runBatchWithRetry("META", state) { processMetaBatch(d, src, state, batch) }
            if (!ok) {
                state.batchSkips++
                Log.e(TAG, "R287 META 批重试耗尽跳批（${batch.size} 行，重导幂等兜回）")
            }
            state.metaCursor = maxChatSeen
            batches++
            writeState(state)
            progressSink("正在合并索引：频道爬取状态阶段（随迁 ${state.metaAdopted} / 合并 ${state.metaMerged}）")
            delay(if (state.batchWrites > 0) BATCH_GAP_MS else IDLE_GAP_MS)
        }
    }

    /** 源 meta 行 → MetaSide（列级自适应：缺列给建表缺省值——mediaPass=-1/tier=-1/其余 0/false） */
    private fun readMetaSide(c: android.database.Cursor, cols: List<String>, base: Int): IndexMergePolicy.MetaSide {
        val idx = HashMap<String, Int>(cols.size * 2)
        cols.forEachIndexed { i, name -> idx[name] = base + i }
        fun longOf(name: String, def: Long): Long = idx[name]?.let { c.getLong(it) } ?: def
        fun boolOf(name: String): Boolean = idx[name]?.let { c.getLong(it) != 0L } ?: false
        return IndexMergePolicy.MetaSide(
            cursor = longOf("cursor", 0L),
            exhausted = boolOf("exhausted"),
            imported = boolOf("imported"),
            mediaPass = longOf("mediaPass", -1L).toInt(),
            needDeepen = boolOf("needDeepen"),
            sweepCursor = longOf("sweepCursor", 0L),
            lastHeadRefreshTs = longOf("lastHeadRefreshTs", 0L),
            sampled = boolOf("sampled"),
            tier = longOf("tier", -1L).toInt(),
            dead = boolOf("dead"),
            ttlSec = idx["ttlSec"]?.let { c.getInt(it) },
            lastMsgSec = idx["lastMsgSec"]?.let { c.getLong(it) },
        )
    }

    /** META 单批（本机事务；mergeMeta 纯函数裁决，tier 恒保本机——UPDATE 不写 tier） */
    private fun processMetaBatch(
        d: SQLiteDatabase,
        src: SQLiteDatabase,
        state: State,
        batch: List<Pair<Long, IndexMergePolicy.MetaSide>>,
    ): Boolean {
        val before = state.metaAdopted + state.metaMerged
        synchronized(owner.writeLock) {
            d.beginTransaction()
            try {
                for ((chatId, incoming) in batch) {
                    val local = readLocalMeta(d, chatId)
                    if (local == null) {
                        adoptMeta(d, chatId, incoming, state)
                    } else {
                        // 并集覆盖判据原料：双侧该频道 items 顶（索引点查 µs 级）
                        val localTop = topMessageId(d, chatId)
                        val incomingTop = topMessageId(src, chatId)
                        val m = IndexMergePolicy.mergeMeta(local, incoming, localTop, incomingTop)
                        if (metaChanged(local, m)) {
                            val upd = d.compileStatement(
                                "UPDATE meta SET cursor=?, exhausted=?, imported=?, mediaPass=?, " +
                                    "needDeepen=?, sweepCursor=?, lastHeadRefreshTs=?, sampled=?, dead=?, ttlSec=?" +
                                    (if (localHasLastMsgSec) ", lastMsgSec=?" else "") + " WHERE chatId=?",
                            )
                            try {
                                upd.clearBindings()
                                upd.bindLong(1, m.cursor)
                                upd.bindLong(2, if (m.exhausted) 1L else 0L)
                                upd.bindLong(3, if (m.imported) 1L else 0L)
                                upd.bindLong(4, m.mediaPass.toLong())
                                upd.bindLong(5, if (m.needDeepen) 1L else 0L)
                                upd.bindLong(6, m.sweepCursor)
                                upd.bindLong(7, m.lastHeadRefreshTs)
                                upd.bindLong(8, if (m.sampled) 1L else 0L)
                                upd.bindLong(9, if (m.dead) 1L else 0L)
                                upd.bindLong(10, (m.ttlSec ?: 0).toLong())
                                if (localHasLastMsgSec) upd.bindLong(11, m.lastMsgSec ?: 0L)
                                upd.bindLong(if (localHasLastMsgSec) 12 else 11, chatId)
                                upd.executeUpdateDelete()
                            } finally {
                                runCatching { upd.close() }
                            }
                            state.metaMerged++
                        }
                    }
                }
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
        state.batchWrites = ((state.metaAdopted + state.metaMerged) - before).toInt()
        return true
    }

    /** 本机 meta 是否有 lastMsgSec 列（R286 v13；run() PREP 探测一次，v12 本机=false 跳过该列读写） */
    private var localHasLastMsgSec = false

    /** 本机 meta 行（v12 全列恒在——openDb 迁移链保证；lastMsgSec 按 v13 探测自适应；行缺=null） */
    private fun readLocalMeta(d: SQLiteDatabase, chatId: Long): IndexMergePolicy.MetaSide? {
        val cols = "cursor, exhausted, imported, mediaPass, needDeepen, sweepCursor, " +
            "lastHeadRefreshTs, sampled, tier, dead, ttlSec" + (if (localHasLastMsgSec) ", lastMsgSec" else "")
        return d.rawQuery(
            "SELECT $cols FROM meta WHERE chatId=?",
            arrayOf(chatId.toString()),
        ).use { c ->
            if (!c.moveToFirst()) {
                null
            } else {
                IndexMergePolicy.MetaSide(
                    cursor = c.getLong(0), exhausted = c.getInt(1) != 0, imported = c.getInt(2) != 0,
                    mediaPass = c.getInt(3), needDeepen = c.getInt(4) != 0, sweepCursor = c.getLong(5),
                    lastHeadRefreshTs = c.getLong(6), sampled = c.getInt(7) != 0, tier = c.getInt(8),
                    dead = c.getInt(9) != 0, ttlSec = c.getInt(10),
                    lastMsgSec = if (localHasLastMsgSec) c.getLong(11) else null,
                )
            }
        }
    }

    /** META 新行整行随迁（来源缺列用 schema 缺省：mediaPass=-1/tier=-1/其余 0——与建表缺省同义） */
    private fun adoptMeta(d: SQLiteDatabase, chatId: Long, incoming: IndexMergePolicy.MetaSide, state: State) {
        val ins = d.compileStatement(
            "INSERT OR IGNORE INTO meta(chatId, cursor, exhausted, imported, mediaPass, needDeepen, " +
                "sweepCursor, lastHeadRefreshTs, sampled, tier, dead, ttlSec" +
                (if (localHasLastMsgSec) ", lastMsgSec" else "") + ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?" +
                (if (localHasLastMsgSec) ",?" else "") + ")",
        )
        try {
            ins.clearBindings()
            ins.bindLong(1, chatId)
            ins.bindLong(2, incoming.cursor)
            ins.bindLong(3, if (incoming.exhausted) 1L else 0L)
            ins.bindLong(4, if (incoming.imported) 1L else 0L)
            ins.bindLong(5, incoming.mediaPass.toLong())
            ins.bindLong(6, if (incoming.needDeepen) 1L else 0L)
            ins.bindLong(7, incoming.sweepCursor)
            ins.bindLong(8, incoming.lastHeadRefreshTs)
            ins.bindLong(9, if (incoming.sampled) 1L else 0L)
            ins.bindLong(10, incoming.tier.toLong())
            ins.bindLong(11, if (incoming.dead) 1L else 0L)
            ins.bindLong(12, (incoming.ttlSec ?: 0).toLong())
            if (localHasLastMsgSec) ins.bindLong(13, incoming.lastMsgSec ?: 0L)
            ins.executeInsert()
        } finally {
            runCatching { ins.close() }
        }
        state.metaAdopted++
    }

    private fun metaChanged(l: IndexMergePolicy.MetaSide, m: IndexMergePolicy.MetaSide): Boolean =
        m.cursor != l.cursor || m.exhausted != l.exhausted || m.imported != l.imported ||
            m.mediaPass != l.mediaPass || m.needDeepen != l.needDeepen ||
            m.sweepCursor != l.sweepCursor || m.lastHeadRefreshTs != l.lastHeadRefreshTs ||
            m.sampled != l.sampled || m.dead != l.dead || (m.ttlSec ?: 0) != (l.ttlSec ?: 0) ||
            (m.lastMsgSec ?: 0L) != (l.lastMsgSec ?: 0L)

    /** 频道 items 顶（最大 messageId；idx_items_chat_msg DESC 首行点查 µs 级；无行=0） */
    private fun topMessageId(db: SQLiteDatabase, chatId: Long): Long =
        db.rawQuery(
            "SELECT MAX(messageId) FROM items WHERE chatId=?",
            arrayOf(chatId.toString()),
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }

    // ---------------- 批重试（R262b 口径：原地重试 ≤2，仍败跳批——重导幂等兜回） ----------------

    private suspend fun runBatchWithRetry(phase: String, state: State, block: () -> Boolean): Boolean {
        var attempt = 0
        while (true) {
            attempt++
            val r = runCatching { block() }
            if (r.isSuccess) return true
            if (attempt > BATCH_MAX_RETRIES) return false
            Log.w(TAG, "R287 $phase 批失败（原地重试 $attempt/$BATCH_MAX_RETRIES）：${r.exceptionOrNull()?.message}")
            delay(BATCH_GAP_MS)
        }
    }

    companion object {
        private const val TAG = "IndexImportMerge"

        /** 断点状态文件名（deepindex 目录；非数字命名天然过门房+isBackfillStateFileName 白名单双保险） */
        const val STATE_FILE_NAME = "import_merge_v1.json"

        /** 解包暂存目录名（deepindex 下；src.db 刻意不叫 search.db——避开一切按名清理面） */
        const val STAGING_DIR_NAME = "import_staging"

        /** 暂存区内的源库文件名 */
        const val SRC_DB_NAME = "src.db"

        /** 解包完成标记（内容=清单声明的 search.db 字节数；无标记=解包半途须重解） */
        const val EXTRACT_OK_NAME = "extract.ok"

        /** 源库内的 rowid 映射表（暂存副本可写；ITEMS 相位逐批落，REFS 相位消费） */
        const val MAP_TABLE = "r287_map"

        /** 批规模（R228/R236/R266 同口径） */
        const val ITEMS_BATCH = 500
        const val REFS_BATCH = 500
        const val META_BATCH = 200

        /** 批间隔：有写 1s 错峰 / 零写 200ms 沙漠快扫（R236/R266 同族） */
        const val BATCH_GAP_MS = 1_000L
        const val IDLE_GAP_MS = 200L

        /** 批失败原地重试上限（R262b BACKFILL_BATCH_MAX_RETRIES 同口径） */
        const val BATCH_MAX_RETRIES = 2

        /** 进度日志节流（每 N 批一行） */
        const val LOG_EVERY = 50

        /**
         * refs 臂指纹点查 SQL（两调用点同一出处防漂移；**repost_refs 的指纹列名是 fuid**
         * ——不是 items 侧的 fileUniqueId，R287 真机曾抓到这个写错的 SQLITE_ERROR）。
         */
        internal const val REFS_FUID_LOOKUP_SQL =
            "SELECT canonRowid FROM repost_refs WHERE fuid=? LIMIT 1"
    }
}
