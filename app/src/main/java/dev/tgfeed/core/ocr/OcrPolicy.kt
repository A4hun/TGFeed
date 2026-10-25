package dev.tgfeed.core.ocr

import dev.tgfeed.core.td.SearchTextKit

/**
 * R292 封面 OCR 词位并入策略（纯函数，JVM 单测直测；零 Android 依赖）。
 *
 * 设计唯一出处 content-understanding-eval.md 落地要点：
 *  - **防 BM25 信号稀释（r291 锚②）**：OCR 词位只并入「低信息 caption」条目——
 *    有实质 caption 的条目不动，R288 排序尺不变；
 *  - **同一把尺**：并入文本与建表共用 fold + MAX_INDEX_TEXT_LEN 截断口径
 *    （DeepSearchIndex.indexTokensOf 经本类取后缀，三方同源不私抄）。
 *
 * 口径注意：本判定是纯函数 f(caption)——建 fts 行、contentless 删除重算、apply Job
 * 三处对同一 (caption, ocrText) 输入必须得出同一结论（逐字节一致是 contentless
 * 删除的生死线），故「低信息」判定只许走本函数，严禁各点手写 length 比较。
 */
object OcrPolicy {

    /** 低信息 caption 上限（fold 后字符数 < 本值；r291 存量画像「<10 字符」同口径） */
    const val LOW_INFO_CAPTION_MAX_LEN = 10

    /**
     * 低信息 caption 判定：fold 归一后字符数低于上限。
     * 空串/纯空白 → true（最缺信号，最需要 OCR 救）；长 caption → false（防稀释）。
     */
    fun isLowInfoCaption(caption: String): Boolean =
        SearchTextKit.fold(caption).length < LOW_INFO_CAPTION_MAX_LEN

    /**
     * 并入索引文本的 OCR 后缀（空串=不并）：
     * 仅当 caption 低信息 且 OCR 文本非空白才给「 + ocrText」后缀；
     * 其余情形（高信息 caption / 无 OCR / OCR 全空白）一律不并——BM25 信号密度红线。
     * 返回串带前导空格，直接拼在 caption+" "+channelTitle 之后进 fold+截断。
     */
    fun ocrIndexSuffix(caption: String, ocrText: String?): String =
        if (!ocrText.isNullOrBlank() && isLowInfoCaption(caption)) " ${ocrText.trim()}" else ""
}
