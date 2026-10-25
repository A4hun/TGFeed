package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ---------------- R299 B4 OCR 消费衔接（design §4.2；消费 R292/T1b，零反向阻塞） ----------------
// （FeedViewModel.kt 本体在 R292 在飞面，本任务零触碰——part 扩展件落形）

/**
 * 搜索命中标注查询（SearchScreen 结果批变化时调用）：返回「低信息 caption 且
 * OCR 文本含查询词位」的条目 key 集（「来自图中文字」徽标数据源）。
 * item_ocr 表不在（R292 未合流/T1b 未放量）= 空集，标注面整体隐身。
 */
internal suspend fun FeedViewModel.ocrHitKeysImpl(
    items: List<VideoItem>,
    query: String,
): Set<String> = withContext(Dispatchers.IO) {
    OcrHitLookup(File(app.filesDir, "deepindex")).ocrHitKeys(items, query)
}

/**
 * R304 收录救援取料桥（调用方须在 IO 语境——[localSearchInstantImpl] 全程 IO 直调）：
 * [items] 中「低信息 caption ∧ item_ocr.applied=1 ∧ text 非空」的 key→OCR 文本映射。
 * applied=1=词位并入流程已结算的唯一真值；试点期/表不在/异常=空映射（救援面零变化）。
 */
internal fun FeedViewModel.ocrIndexedTextsImpl(items: List<VideoItem>): Map<String, String> =
    OcrHitLookup(File(app.filesDir, "deepindex")).ocrIndexedTexts(items)
