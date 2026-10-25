package dev.tgfeed.core.ocr

import android.content.Context
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * R292 ML Kit bundled 端上 OCR 引擎（T1a 唯一实现候选）。
 *
 * 路线合规（content-understanding-eval.md §2.1/§3.5）：
 *  - **bundled 自携模型**：识别全程本地，图片字节不出设备（隐私零外发红线）；
 *  - **无 GMS 运行时依赖**：不依赖 Play Services 下发模型——华为鸿蒙（无 GMS 档）可跑
 *    （实机兼容性甩验证专席单①实锤，翻车备选 Tesseract）；
 *  - 中文模型自覆盖拉丁文（ChineseTextRecognizerOptions = 中文+拉丁双脚本）。
 *
 * 实现要点：
 *  - 识别器懒建单例（首个识别请求才装载模型，首开零代价）；进程级复用，不随条目开关；
 *  - Task→协程用 suspendCancellableCoroutine 手卷（不引 kotlinx-coroutines-play-services
 *    新依赖）；整体外包 [RECOGNIZE_TIMEOUT_MS] 超时（坏图/引擎挂死不拖死回填 Job）；
 *  - 位图解码在 Default 调度器（CPU 活不占 IO 池），用完即 recycle——识别即弃，
 *    本类不持有任何图片副本（存储闸口径）。
 */
class MlKitOcrEngine(context: Context) : OcrEngine {

    /** engineVer=1 ↔ ML Kit bundled text-recognition-chinese:16.0.1（换代递增，见接口注） */
    override val engineVer: Int = 1

    @Volatile
    private var recognizer: TextRecognizer? = null

    /** 懒建识别器（双检锁；首次识别才付模型装载代价） */
    private fun recognizer(): TextRecognizer =
        recognizer ?: synchronized(this) {
            recognizer ?: TextRecognition.getClient(
                ChineseTextRecognizerOptions.Builder().build(),
            ).also { recognizer = it }
        }

    override suspend fun recognize(imageBytes: ByteArray): String = withContext(Dispatchers.Default) {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: throw IllegalArgumentException("cover-bytes-undecodable")
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            withTimeout(RECOGNIZE_TIMEOUT_MS) {
                recognizer().process(image).awaitResult()
            }.text
        } finally {
            // 识别即弃：位图立刻回收，引擎不囤任何图像副本
            bitmap.recycle()
        }
    }

    private companion object {
        /** 单张识别超时（毫秒）：封面 KB 级正常 <1s，超时防坏图/引擎挂死拖死回填批 */
        const val RECOGNIZE_TIMEOUT_MS = 10_000L
    }
}

/** Task<T>→协程最小桥（零新依赖；取消传播回 Task 不可撤销但协程侧立即恢复） */
private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitResult(): T =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
