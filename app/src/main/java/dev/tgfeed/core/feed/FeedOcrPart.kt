package dev.tgfeed.core.feed

import android.util.Log
import androidx.lifecycle.viewModelScope
import dev.tgfeed.core.net.NetworkMeteredBus
import dev.tgfeed.core.ocr.MlKitOcrEngine
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * R292 封面 OCR 试点（T1a）· FeedViewModel 接线层。
 *
 * 点火条件（缺一不点）：
 *  1. 真网关（MockTdGateway 演示模式无真封面链，天然不点火）；
 *  2. ML Kit 引擎构造成功（bundled 模型随包；构造异常=本机不点火，日志降级）；
 *  3. 窗口闸进 Job 内逐批判定（充电 ∧ 非计量网络——任务书「只 WiFi+充电跑」）。
 *
 * 双 Job：
 *  - 回填（识别）：试点期上限 1 万（AppPrefs.ocrBackfillFull 关），只写 item_ocr 表
 *    （applied=0）——生产检索词位零变化；
 *  - 并词位（apply）：AppPrefs.ocrSearchEnabled 开才点火（试点期默认关）。
 */

/** OCR 引擎懒持（首窗才构造；构造失败=本机不点火，一次性日志降级不反复打扰） */
@Volatile
private var ocrEngineTried = false

@Volatile
private var ocrEngineInstance: MlKitOcrEngine? = null

internal fun FeedViewModel.ocrEngineOrNull(): MlKitOcrEngine? {
    if (!ocrEngineTried) {
        synchronized(FeedViewModel::class.java) {
            if (!ocrEngineTried) {
                ocrEngineTried = true
                ocrEngineInstance = runCatching { MlKitOcrEngine(app) }
                    .onFailure { Log.w("FeedOcrPart", "R292 ML Kit OCR 引擎构造失败（本机不点火封面识别）：${it.message}") }
                    .getOrNull()
            }
        }
    }
    return ocrEngineInstance
}

/** 窗口闸现读（Job 内逐批调用）：充电 ∧ 非计量网络（任务书「只 WiFi+充电跑」） */
internal fun FeedViewModel.ocrWindowOpen(): Boolean =
    _charging.value && !NetworkMeteredBus.metered

/** 封面字节链：GatewayCover 现成链薄封装（铸造验身+消息反解+三层缓存全在链内） */
private fun FeedViewModel.ocrCoverFetcher(): suspend (Long, Long, Int, String) -> ByteArray? {
    val lib = gateway as? TdLibGateway ?: return { _, _, _, _ -> null }
    return { chatId, messageId, coverFileId, key ->
        // 最小 VideoItem：封面链只读 key/chatId/messageId/coverFileId 四字段
        val item = VideoItem(
            key = key, chatId = chatId, messageId = messageId,
            channelTitle = "", caption = "", durationSec = 0, width = 0, height = 0,
            fileId = -1, sizeBytes = 0, playUri = null, mediaType = 0, coverFileId = coverFileId,
        )
        runCatching { lib.loadCoverBytesByMessage(item) }.getOrNull()
    }
}

/** 点火口（FeedViewModel init 调用；内部全幂等/单飞，重复调零副作用） */
internal fun FeedViewModel.startCoverOcrImpl() {
    viewModelScope.launch(Dispatchers.IO) {
        val engine = ocrEngineOrNull() ?: return@launch
        if (gateway !is TdLibGateway) return@launch // 演示模式无真封面链，不点火
        deepIndex.maybeStartCoverOcrBackfill(
            fetchCover = ocrCoverFetcher(),
            engine = engine,
            windowOpen = { ocrWindowOpen() },
            fullBackfill = { prefs.ocrBackfillFull },
        )
        deepIndex.maybeStartCoverOcrApply(
            ocrSearchEnabled = { prefs.ocrSearchEnabled },
        )
    }
}
