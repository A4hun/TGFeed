package dev.tgfeed.core.ocr

/**
 * R292 端上 OCR 引擎抽象（T1a 封面试点）。
 *
 * 实现唯候选：MlKitOcrEngine（ML Kit bundled 中文模型，全本地零外发、无 GMS 红线）。
 * 抽象成接口的用意：
 *  1. 回填 Job（DeepSearchIndex）只依赖本接口——JVM 单测可喂假引擎，不碰 native；
 *  2. 鸿蒙翻车时的备选路线（Tesseract，content-understanding-eval.md §2.1）换实现不换 Job。
 *
 * 脱敏纪律（§七扩令）：识别出的文字是敏感内容——实现与调用方一律**只入库不进日志**，
 * 日志/诊断只许出计数/长度/是否非空。
 */
interface OcrEngine {

    /**
     * 引擎代次（item_ocr.engineVer 落库值）：引擎/模型换代即递增——
     * 换代后整表按新代次重建（旧代次行整体失效），是本表可整表重建的凭据。
     */
    val engineVer: Int

    /**
     * 识别一张图片里的文字。
     * @param imageBytes 图片字节（封面 JPEG/WebP/PNG，KB 级）
     * @return 识别文本（换行连接；**空串=识别完成但无字**，是合法结果不是失败）
     * @throws Throwable 识别失败（字节不可解码/引擎异常/超时）——调用方计 failed，不写墓碑
     */
    suspend fun recognize(imageBytes: ByteArray): String
}
