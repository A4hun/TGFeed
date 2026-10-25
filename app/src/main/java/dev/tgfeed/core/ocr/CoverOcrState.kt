package dev.tgfeed.core.ocr

/**
 * R292 封面 OCR 回填/并词位断点与试点报告——**纯 JSON 编解码件**（零 Android 依赖，
 * JVM 单测直测）。落盘 IO（tmp+rename 原子写）在 DeepSearchIndex 侧，本文件只管
 * 编解码形状（与 caption_backfill_v8r2 的正则解析同口径：容缺字段、坏串不回崩）。
 *
 * 脱敏纪律（§七扩令）：本族结构**只含计数/游标/时间戳**，OCR 识别文本一个字节都不进。
 */

/** 封面 OCR 回填断点（deepindex/cover_ocr_backfill_v1.json） */
data class CoverOcrBackfillState(
    /** rowid 倒序单调游标（v8r2 同族：单调不回扫，跳过行下轮自愈） */
    val cursor: Long,
    /** 已处理条数（成功识别+失败合计；试点上限判定口径） */
    val processed: Int,
    /** 识别出非空文字条数（实得率分子） */
    val nonempty: Int,
    /** 识别完成但无字条数（墓碑行，防重扫） */
    val empty: Int,
    /** 失败条数（下载/解码/识别任一段失败；无墓碑，换代重跑兜回） */
    val failed: Int,
    /** 完成标记（试点到量/全量到底/空库=true；放量闸开时试点 done 自动复活续跑） */
    val done: Boolean,
    /** 识别文本 fold 长度分桶（"b0,b1,b2,b3,b4" 五桶 CSV；跨重启续跑不丢——
     *  桶口径见 [ocrLenBucket]；只记长度不记文本，§七扩令合规） */
    val buckets: String = "0,0,0,0,0",
)

fun encodeCoverOcrBackfillState(s: CoverOcrBackfillState): String =
    "{\"cursor\":${s.cursor},\"processed\":${s.processed},\"nonempty\":${s.nonempty}," +
        "\"empty\":${s.empty},\"failed\":${s.failed},\"done\":${s.done},\"buckets\":\"${s.buckets}\"}"

fun decodeCoverOcrBackfillState(txt: String): CoverOcrBackfillState = CoverOcrBackfillState(
    cursor = Regex("\"cursor\":(-?\\d+)").find(txt)?.groupValues?.get(1)?.toLong() ?: 0L,
    processed = Regex("\"processed\":(\\d+)").find(txt)?.groupValues?.get(1)?.toInt() ?: 0,
    nonempty = Regex("\"nonempty\":(\\d+)").find(txt)?.groupValues?.get(1)?.toInt() ?: 0,
    empty = Regex("\"empty\":(\\d+)").find(txt)?.groupValues?.get(1)?.toInt() ?: 0,
    failed = Regex("\"failed\":(\\d+)").find(txt)?.groupValues?.get(1)?.toInt() ?: 0,
    done = txt.contains("\"done\":true"),
    buckets = Regex("\"buckets\":\"([\\d,]+)\"").find(txt)?.groupValues?.get(1) ?: "0,0,0,0,0",
)

/** 分桶 CSV ↔ IntArray(5)（坏串/短款容缺补零，不崩） */
fun parseOcrBuckets(csv: String): IntArray {
    val out = IntArray(5)
    csv.split(',').forEachIndexed { i, p -> if (i < 5) out[i] = p.toIntOrNull() ?: 0 }
    return out
}

fun formatOcrBuckets(buckets: IntArray): String =
    IntArray(5) { i -> buckets.getOrElse(i) { 0 } }.joinToString(",")

/** 词位并入（apply）断点（deepindex/cover_ocr_apply_v1.json；rowid 升序游标） */
data class CoverOcrApplyState(
    val cursor: Long,
    /** 已并入 fts/LIKE tokens 的条数 */
    val applied: Int,
    /** 已结算但未并入（caption 非低信息/OCR 空/items 行已不在）条数 */
    val skipped: Int,
    val done: Boolean,
)

fun encodeCoverOcrApplyState(s: CoverOcrApplyState): String =
    "{\"cursor\":${s.cursor},\"applied\":${s.applied},\"skipped\":${s.skipped},\"done\":${s.done}}"

fun decodeCoverOcrApplyState(txt: String): CoverOcrApplyState = CoverOcrApplyState(
    cursor = Regex("\"cursor\":(-?\\d+)").find(txt)?.groupValues?.get(1)?.toLong() ?: 0L,
    applied = Regex("\"applied\":(\\d+)").find(txt)?.groupValues?.get(1)?.toInt() ?: 0,
    skipped = Regex("\"skipped\":(\\d+)").find(txt)?.groupValues?.get(1)?.toInt() ?: 0,
    done = txt.contains("\"done\":true"),
)

/**
 * 试点报告（deepindex/cover_ocr_pilot_report.json）——用户终审放量决策的唯一数据源。
 * 全聚合零原文（实得率/长度分桶/耗时）；识别文本长度只进分桶计数。
 * lenBuckets：识别文本 fold 长度分桶 [0)（=empty 不重复计）/[1,10)/[10,50)/[50,200)/[200,∞)。
 */
fun encodeCoverOcrPilotReport(
    engineVer: Int,
    processed: Int,
    nonempty: Int,
    empty: Int,
    failed: Int,
    lenBuckets: IntArray,
    startedAtMs: Long,
    finishedAtMs: Long,
    fullBackfill: Boolean,
): String {
    val ratePctX10 = if (processed > 0) (nonempty * 1000L / processed) else 0L
    return "{\"engineVer\":$engineVer,\"processed\":$processed,\"nonempty\":$nonempty," +
        "\"empty\":$empty,\"failed\":$failed,\"hitRatePctX10\":$ratePctX10," +
        "\"lenBuckets\":[${lenBuckets.joinToString(",")}]," +
        "\"startedAtMs\":$startedAtMs,\"finishedAtMs\":$finishedAtMs,\"fullBackfill\":$fullBackfill}"
}

/** 识别文本长度分桶（下标口径见 encodeCoverOcrPilotReport；纯函数单测锚） */
fun ocrLenBucket(foldLen: Int): Int = when {
    foldLen <= 0 -> 0
    foldLen < 10 -> 1
    foldLen < 50 -> 2
    foldLen < 200 -> 3
    else -> 4
}
