package dev.tgfeed.core.feed

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * R204 DS-5 首发频道徽章（timeline-opportunities DS-5 / WOW-SEARCH W4 副产品）：
 * 同一 fileUniqueId 跨频道副本的**最早出现**=首发（真·上游，关注它=永远比搬运号快半拍）。
 *
 * 查询纪律（R183 LIKE 全表扫的同族红线）：
 *  - R266 v11 起双臂走 idx_items_fuid / idx_refs_fuid（毫秒级点查）；R428 加
 *    [fuidIndexReady] 就绪门——索引未建成的升级窗口期**绝不回退全表扫**
 *    （`WHERE fileUniqueId=?` 无索引即 4M 行秒级~十秒级，同族红线），未就绪
 *    返回 null 占位。入口仍是用户主动点按「首发溯源」（WK-1 考古学家先例：
 *    点按付费，零被动成本），只读连接 + 后台协程 + 单飞闸；
 *  - 结果进磁盘账本 [LEDGER_FILE]（LRU 封顶 [MAX_ENTRIES]，门房辖区外的 files 根）：
 *    同一指纹第二次查看零成本；会话内存缓存先行，账本兜跨会话。
 *
 * 覆盖诚实口径：只看得见**本地深索引已收录**的频道（订阅/金矿宇宙）；索引外的搬运
 * 频道不可见，判定文案如实标注「基于本地深索引检索」。
 */
object FirstSeenLedger {

    /** 账本文件名（files 根；所有权=首发溯源，门房辖区之外） */
    const val LEDGER_FILE = "first_seen_ledger.json"

    private const val LEDGER_VERSION = 1

    /** 账本容量上限（LRU 淘汰最旧条目；每条几百字节，512 条 <1MB） */
    const val MAX_ENTRIES = 512

    /** 一份同指纹副本（跨频道出现记录） */
    data class Copy(
        val chatId: Long,
        val messageId: Long,
        val dateSec: Long,
        val channelTitle: String,
    )

    /** 溯源判定：副本按时间升序（[copies] 首位=首发）；覆盖口径如实携带 */
    data class Verdict(
        val fileUniqueId: String,
        val copies: List<Copy>,
        /** 覆盖频道数（深索引收录的全部频道——查证范围的诚实上界） */
        val channels: Int,
        /** 本条（发起查证的条目）就是首发 */
        val currentIsFirst: Boolean,
        val atMs: Long,
    )

    /**
     * 同指纹副本查询（**IO 协程 + 用户点按触发**）：
     * ORDER BY dateEpochSec ASC——最早行=首发。同 5 分钟内多发（同频道抢发/转发
     * 风暴）按 messageId 升序稳定兜底（W4 口径：首发判定别被转发时差骗过）。
     * fileUniqueId 空串（Mock/本地文件）返回 null（没有指纹无从溯源）。
     *
     * R266 v11 双臂（顺带根治既有全表扫慢性病）：items 臂走 idx_items_fuid +
     * refs 臂走 idx_refs_fuid，**毫秒级**（v11 前的全表扫 4M 行秒级~十秒级成为
     * 历史）。R428 就绪门：索引未建成的升级窗口期**不再回退全表扫**——调用方
     * 传 [fuidIndexReady]（DeepSearchIndex.fuidIndexReadyCached，IndexImportMerge
     * 同款红线「指纹索引未就绪绝不按 fuid 查库」），未就绪直接返回 null 占位。
     * 副本清单 = 1 canonical + N refs（合并后 items 同指纹恒一行）。
     */
    fun query(
        db: SQLiteDatabase,
        fileUniqueId: String,
        currentChatId: Long,
        currentMessageId: Long,
        fuidIndexReady: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): Verdict? {
        if (fileUniqueId.isBlank()) return null
        // R428 就绪门（InterlinkMerge/IndexImportMerge 同款接法）：未就绪绝不放行——
        // WHERE fileUniqueId=? 无索引即全表扫（4M 行秒级~十秒级）。返回 null =
        // Verdict? 的安全默认（无判定，调用方落诚实空态）；索引建成后（openDb
        // 后台分钟级一次性工程）重查/「重新查证」即得真判定。
        if (!fuidIndexReady) return null
        val copies = ArrayList<Copy>(8)
        runCatching {
            db.rawQuery(
                "SELECT chatId, messageId, dateEpochSec, channelTitle FROM items " +
                    "WHERE fileUniqueId=? ORDER BY dateEpochSec ASC, messageId ASC",
                arrayOf(fileUniqueId),
            ).use { c ->
                while (c.moveToNext()) {
                    copies += Copy(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3) ?: "")
                }
            }
            // refs 臂：被收编副本同指纹点查（R266 v11；合并批迁进行中 refs 逐渐增厚）。
            // 独立 runCatching 容错：升级窗口期库文件可能还是 v10（本查询走自备
            // 只读连接，DeepSearchIndex.openDb 未必先跑）——refs 表缺席只丢 refs 臂，
            // items 臂语义与 v10 前逐字节一致
            runCatching {
                db.rawQuery(
                    "SELECT chatId, messageId, dateEpochSec, channelTitle FROM repost_refs " +
                        "WHERE fuid=? ORDER BY dateEpochSec ASC, messageId ASC",
                    arrayOf(fileUniqueId),
                ).use { c ->
                    while (c.moveToNext()) {
                        copies += Copy(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3) ?: "")
                    }
                }
            }
            // 双臂合并重排首发序（两臂各自有序，合并后不一定全序——副本清单按
            // (date, msgId) 升序重排保首发语义）
            if (copies.size > 1) copies.sortWith(compareBy({ it.dateSec }, { it.messageId }))
        }.onFailure { return null }
        if (copies.isEmpty()) return null
        val channels = runCatching {
            // 覆盖频道数=发布侧全集（refs-only 频道也算已索引——items∪refs 去重计数）；
            // v10 窗口期 refs 缺席回退 items 单表（两臂独立容错，同上）
            runCatching {
                db.rawQuery(
                    "SELECT COUNT(*) FROM (SELECT chatId FROM items UNION SELECT chatId FROM repost_refs)",
                    null,
                ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            }.getOrElse {
                db.rawQuery("SELECT COUNT(DISTINCT chatId) FROM items", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
            }
        }.getOrDefault(0)
        val first = copies.first()
        return Verdict(
            fileUniqueId = fileUniqueId,
            copies = copies,
            channels = channels,
            currentIsFirst = first.chatId == currentChatId && first.messageId == currentMessageId,
            atMs = nowMs,
        )
    }

    /** 副本条数文案（「在 3 个频道出现过」） */
    fun copiesLine(v: Verdict): String {
        val n = v.copies.map { it.chatId }.distinct().size
        return "在 $n 个频道出现过 · ${v.copies.size} 条副本"
    }

    // ---------------- 账本落盘/读盘（tmp + rename 原子写；LRU 容量淘汰） ----------------

    /** 判定入账（IO 协程；账本满时按 atMs 淘汰最旧条目——LRU 语义） */
    fun save(file: File, v: Verdict) {
        runCatching {
            val entries = loadEntries(file).toMutableList()
            entries.removeAll { it.fileUniqueId == v.fileUniqueId }
            entries += v
            val trimmed = if (entries.size > MAX_ENTRIES) {
                entries.sortedByDescending { it.atMs }.take(MAX_ENTRIES)
            } else {
                entries
            }
            val root = JSONObject().put("v", LEDGER_VERSION)
            val arr = JSONArray()
            for (e in trimmed) {
                val cop = JSONArray()
                for (c in e.copies) {
                    cop.put(
                        JSONObject()
                            .put("c", c.chatId)
                            .put("m", c.messageId)
                            .put("d", c.dateSec)
                            .put("t", c.channelTitle),
                    )
                }
                arr.put(
                    JSONObject()
                        .put("f", e.fileUniqueId)
                        .put("cop", cop)
                        .put("ch", e.channels)
                        .put("cur", e.currentIsFirst)
                        .put("at", e.atMs),
                )
            }
            root.put("entries", arr)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText()) // rename 失败兜底
                tmp.delete()
            }
        }
    }

    /** 账本读回（文件缺失/损坏返回空表；坏单条只跳过该条） */
    fun load(file: File): Map<String, Verdict> = loadEntries(file).associateBy { it.fileUniqueId }

    private fun loadEntries(file: File): List<Verdict> {
        return runCatching {
            if (!file.isFile) return@runCatching emptyList()
            val root = JSONObject(file.readText())
            if (root.optInt("v", -1) != LEDGER_VERSION) return@runCatching emptyList()
            val arr = root.optJSONArray("entries") ?: return@runCatching emptyList()
            val out = ArrayList<Verdict>(arr.length())
            for (i in 0 until arr.length()) {
                runCatching {
                    val o = arr.getJSONObject(i)
                    val f = o.optString("f", "")
                    if (f.isBlank()) return@runCatching
                    val copArr = o.optJSONArray("cop") ?: return@runCatching
                    val cop = ArrayList<Copy>(copArr.length())
                    for (j in 0 until copArr.length()) {
                        val co = copArr.optJSONObject(j) ?: continue
                        cop += Copy(co.optLong("c", 0L), co.optLong("m", 0L), co.optLong("d", 0L), co.optString("t", ""))
                    }
                    if (cop.isEmpty()) return@runCatching
                    out += Verdict(f, cop, o.optInt("ch", 0), o.optBoolean("cur", false), o.optLong("at", 0L))
                }
            }
            out
        }.getOrDefault(emptyList())
    }
}
