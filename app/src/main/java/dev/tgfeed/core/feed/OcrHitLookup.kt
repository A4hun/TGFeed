package dev.tgfeed.core.feed

import android.database.sqlite.SQLiteDatabase
import dev.tgfeed.core.ocr.OcrPolicy
import dev.tgfeed.core.td.SearchTextKit
import dev.tgfeed.core.td.VideoItem
import java.io.File

/**
 * R299 B4 OCR 消费侧·命中标注查询（design-audio-photo.md §4.2「命中标注」行）：
 * 搜索命中的低信息 caption 条目，若其 OCR 文本（R292 item_ocr 表）含查询词位，
 * 标注「来自图中文字」——用户第一次能对无 caption 条目建立内容预期。
 *
 * 零阻塞纪律（不反向阻塞 R292/T1b）：
 *  - 只读连接（OPEN_READONLY）直查 search.db，与 DeepSearchIndex 写连接并存；
 *  - item_ocr 表不存在（R292 未合流/试点未跑）→ 空集，标注面整体隐身零噪音；
 *  - 任何读取异常（锁竞争/库未建）→ 空集（标注是锦上添花，绝不影响搜索主链）。
 *
 * 判定口径三方同源：低信息 caption=[OcrPolicy.isLowInfoCaption]（其头注自命唯一
 * 出处）；词位折叠=[SearchTextKit.fold]（与建库词位同一把尺）。
 * 命中语义=「词位在 OCR 文本里出现」——[ocrHitKeys] 不区分 applied 标记：applied=0
 * 的试点条目即使被标，也只是「图里确实有这些字」的诚实陈述（未被并词位的条目本也
 * 不会经 OCR 链路命中搜索，标注无从误出）。
 *
 * R304 新增 [ocrIndexedTexts]：收录救援取料口径——**只认 applied=1**（词位并入流程
 * 已结算的唯一真值；applied=0 试点条目的 OCR 词不在索引，参与收录即口径错配）。
 */
internal class OcrHitLookup(private val storeDir: File) {

    /**
     * 批量判定：[items] 中「低信息 caption ∧ OCR 文本含任一查询词位」的条目 key 集。
     * **请在 IO 线程调用**（盘读+SQL）；库/表不在或异常一律空集。
     */
    fun ocrHitKeys(items: List<VideoItem>, query: String): Set<String> {
        val tokens = SearchTextKit.fold(query).split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptySet()
        val texts = queryOcrTexts(items, appliedOnly = false)
        if (texts.isEmpty()) return emptySet()
        return texts.entries.mapNotNull { (k, text) ->
            val folded = SearchTextKit.fold(text)
            if (tokens.any { folded.contains(it) }) k else null
        }.toSet()
    }

    /**
     * R304 收录救援取料：[items] 中「低信息 caption ∧ item_ocr.applied=1 ∧ text 非空」
     * 的 key→OCR 文本映射。低信息判定与 apply Job 的并词位判定同构（[OcrPolicy] 单一
     * 出处）——applied=1 只代表「已结算」（高信息行=「判定不并」同样置 1），唯有
     * 低信息行的 OCR 词才真正并入索引词位；本函数双条件叠加=「可进收录尺」唯一口径。
     * **请在 IO 线程调用**；库/表不在或异常一律空映射（试点期恒空=救援面零变化）。
     */
    fun ocrIndexedTexts(items: List<VideoItem>): Map<String, String> =
        queryOcrTexts(items, appliedOnly = true)

    /**
     * 共享取件：[items] 里低信息 caption 条目的 key→OCR 文本（text 非空）；
     * [appliedOnly]=true 时只取 applied=1 行（R304 收录口径），false 取全量（徽标口径）。
     */
    private fun queryOcrTexts(items: List<VideoItem>, appliedOnly: Boolean): Map<String, String> {
        // 只有低信息 caption 条目才可能是 OCR 命中（高信息条目的命中归 caption 本体）
        val candidates = items.filter { OcrPolicy.isLowInfoCaption(it.caption) }
        if (candidates.isEmpty()) return emptyMap()
        val dbFile = File(storeDir, DB_NAME)
        if (!dbFile.exists()) return emptyMap()
        return runCatching {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                .use { db ->
                    if (!tableExists(db)) return@use emptyMap<String, String>()
                    val byKey = HashMap<String, String>(candidates.size)
                    val appliedCond = if (appliedOnly) "o.applied=1 AND " else ""
                    candidates.chunked(SQL_IN_CHUNK).forEach { chunk ->
                        val marks = chunk.joinToString(",") { "?" }
                        db.rawQuery(
                            "SELECT i.key, o.text FROM $OCR_TABLE o " +
                                "JOIN items i ON i.rowid = o.rowid WHERE ${appliedCond}i.key IN ($marks)",
                            chunk.map { it.key }.toTypedArray(),
                        ).use { c ->
                            while (c.moveToNext()) {
                                val k = c.getString(0) ?: continue
                                val t = c.getString(1) ?: continue
                                if (t.isNotEmpty()) byKey[k] = t
                            }
                        }
                    }
                    byKey
                }
        }.getOrDefault(emptyMap())
    }

    private fun tableExists(db: SQLiteDatabase): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(OCR_TABLE),
        ).use { it.moveToFirst() }

    private companion object {
        const val DB_NAME = "search.db"
        const val OCR_TABLE = "item_ocr" // R292 schema v14（DeepSearchIndex.ITEM_OCR_TABLE 同值；消费侧不反依赖其常量，防在飞面编译互锁）
        const val SQL_IN_CHUNK = 400
    }
}
