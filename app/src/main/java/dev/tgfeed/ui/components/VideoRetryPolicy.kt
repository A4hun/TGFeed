package dev.tgfeed.ui.components

import androidx.media3.common.PlaybackException
import androidx.media3.common.VideoSize
import androidx.media3.datasource.HttpDataSource

/**
 * 失败重试链的纯判定件（R131 从 VideoPage.kt 按职责拆出，纯搬运零逻辑改动）。
 * 收纳：不可重试病因分诊 [fatalReasonOf]、内容损坏判定 [isCorruptContentError]、
 * 自动重试链常量（次数上限 / 内容损坏换源重下上限 / 退避基数）。
 * 状态编排（failCount 退避协程、看门狗、换路副本）仍在 VideoPage.kt。
 */

/**
 * R78 重试分诊（VideoPage/分屏/悬浮窗共用同一口径）：返回非空 = 不可重试的稳定失败
 * （直接进失败终态给引导，自动重试救不了，不假装在努力）；null = 真网络类问题
 * （断连/超时/限流/回环流服务 500/503 抖动），交给自动退避重试链。
 *
 * 判据：
 *  - 解析/解码失败（3xxx/4xxx/5xxx）：内容损坏或格式不支持，重试一万次也是同一堵墙；
 *  - HTTP 4xx（除 429）：资源已失效/被删除/内容类型不是视频，确定性失败；
 *  - 429 限流与 5xx（含回环流服务器 500=泵未就绪、503=连接池满）：瞬态，可重试；
 *  - 本地缓存文件被清（IO_FILE_NOT_FOUND）：重试链换路重拉可愈，不算稳定失败。
 *
 * R86 注意：VideoPage 对其中的 3001（容器字节损坏）/4003（解码器吃到坏流）两类
 * 会先拦一次走「换源重下重试」（isCorruptContentError，同条目二连击才落回本函数
 * 判定的终态）——并发下载重压下这两类多是 TDLib 侧瞬态内容错乱而非真坏文件；
 * GridScreen/悬浮窗仍直接使用本函数口径（单格/小窗不做换源重下）。
 */
internal fun fatalReasonOf(error: PlaybackException): String? {
    when (error.errorCode) {
        // 内容损坏 / 格式不支持 / 解码器失败：与网络无关，重试无用
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        -> return "视频格式不支持或已损坏"
        // 响应不是视频 / 读不到本地文件权限 / 明文限制 / 寻址越界：确定性失败
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        -> return "视频资源已失效"
    }
    // HTTP 状态码类：4xx（除 429 限流、416 区间越界）= 资源已失效/被删除（403/404/410 等），
    // 稳定终态；429/416/5xx（含回环流服务器 500=首块未就绪、503=连接池满）= 瞬态可重试。
    // R110：416 移出稳定终态——回环流服务器只在"请求区间越过当前已知总长"时发它
    // （片尾探测/expectedSize 估计未收敛，总长随后可能长真），判终态会把
    // 可自愈的瞬态误杀成"资源已删除"
    if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
        val code = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: -1
        if (code in 400..499 && code != 429 && code != 416) return "视频资源已失效或已被删除"
    }
    return null
}

/** 播放失败自动重试（仅真网络类失败进链，不可重试病因见 fatalReasonOf）：
 *  最多 4 次，全不用用户点；耗尽才显示"加载失败"终态 */
internal const val AUTO_RETRY_MAX = 4

/** R86 同条目内容损坏换源重下的次数上限：1 次。同条目第二次同类错误即真坏文件，
 *  落回 fatalReasonOf 判定的「视频格式不支持或已损坏」终态（防无限重下重播） */
internal const val CORRUPT_RETRY_MAX = 1

/**
 * R86 内容损坏判定（换源重下重试的适用范围）：仅「容器字节损坏（3001）/
 * 解码器吃到坏流（4003）」两类——它们可能是并发下载期读到 TDLib 瞬态中间态的
 * 产物，作废重下一次有机会愈。格式不支持/解码器初始化失败/能力超限/音轨失败等
 * 是设备或内容本身的稳定属性，重下拿到的还是同一份字节，救不了，不在此列
 * （仍由 fatalReasonOf 直接判终态）。
 */
internal fun isCorruptContentError(error: PlaybackException): Boolean =
    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED

/** 自动重试退避基数（毫秒）：第 N 次重试等待 BASE × 2^(N-1) —— 3s/6s/12s/24s 指数退避 */
internal const val AUTO_RETRY_BASE_MS = 3_000L
