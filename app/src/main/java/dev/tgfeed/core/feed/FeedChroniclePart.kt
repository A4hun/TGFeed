package dev.tgfeed.core.feed

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------- R204 TL-5 频道编年史（月历直方图 → 年块时间轴，数据侧） ----------------

/**
 * 编年史取数（频道主页「编年史」面板唯一数据口）：
 *  - 月历直方图走 [TdLibGateway.getChatActivityDays]（Get 族，无 Search 配额；每非空月
 *    2 请求，[monthsCap] 控制预算：首批 12 个月快出墙，用户点「回溯更早」再拉到 24）；
 *  - 会话缓存 [ChannelChronicle] 6h TTL——同频道反复开关面板零重拉（W3 月历纪律）；
 *  - 年表下界用本地基因 [loadIndexGenome]（单频道毫秒级，覆盖外年代照出块）；
 *  - Mock 网关无服务端月历：直回基因版（只有早年块，诚实呈现）。
 */
internal object FeedChroniclePart {
    /** 编年史首批月份预算（≤24 个月硬上限；首批求快，回溯按钮再翻倍） */
    const val FIRST_MONTHS_CAP = 12

    /** 回溯上限（网关 CALENDAR_MAX_MONTHS 同源钳制） */
    const val EXTENDED_MONTHS_CAP = 24
}

/** 月历+基因折算产物（UI 出口）：chronicle 为纯折算结果，fromCache 标记免重拉 */
internal data class ChronicleResult(
    val chronicle: ChannelChronicle.Chronicle,
    val fromCache: Boolean,
    /** 本次实际申请的月份预算（回溯后变大） */
    val monthsCap: Int,
    /** 是否已拉满回溯上限（「回溯更早」按钮的显隐依据） */
    val atMaxDepth: Boolean,
)

/**
 * 频道编年史取数（IO 切换内聚；UI 侧 produceState 直调）。
 * [monthsCap] 比缓存大时才重拉（回溯语义），否则命中缓存直回。
 */
internal suspend fun FeedViewModel.channelChronicleImpl(chatId: Long, monthsCap: Int): ChronicleResult? =
    withContext(Dispatchers.IO) {
        val cached = ChannelChronicle.cached(chatId)
        if (cached != null && cached.monthsCap >= monthsCap) {
            return@withContext ChronicleResult(
                chronicle = foldChronicle(chatId, cached.days, cached.monthsCovered()),
                fromCache = true,
                monthsCap = cached.monthsCap,
                atMaxDepth = cached.monthsCap >= FeedChroniclePart.EXTENDED_MONTHS_CAP,
            )
        }
        val lib = gateway as? dev.tgfeed.core.td.TdLibGateway
        val days = if (lib != null) {
            runCatching { lib.getChatActivityDays(chatId, maxMonths = monthsCap) }
                .onFailure { Log.w("FeedViewModel", "编年史月历拉取失败 chatId=$chatId: ${it.message}") }
                .getOrElse { emptyList() }
        } else {
            emptyList() // Mock 演示源：无服务端月历，走基因下界诚实呈现
        }
        // 拉取失败/空历 → 陈值先回（月历是永久历史，陈度无害；空历也是事实不反复重拉）
        if (days.isEmpty()) {
            val stale = ChannelChronicle.peek(chatId)
            if (stale != null && stale.days.isNotEmpty()) {
                return@withContext ChronicleResult(
                    chronicle = foldChronicle(chatId, stale.days, stale.monthsCovered()),
                    fromCache = true,
                    monthsCap = stale.monthsCap,
                    atMaxDepth = stale.monthsCap >= FeedChroniclePart.EXTENDED_MONTHS_CAP,
                )
            }
        }
        ChannelChronicle.put(chatId, monthsCap, days)
        ChronicleResult(
            chronicle = foldChronicle(chatId, days, 0),
            fromCache = false,
            monthsCap = monthsCap,
            atMaxDepth = monthsCap >= FeedChroniclePart.EXTENDED_MONTHS_CAP,
        )
    }

/** 月历 + 基因 → 年块时间轴（统一折算口，缓存/网络两路共用） */
private fun FeedViewModel.foldChronicle(
    chatId: Long,
    days: List<Pair<Int, Int>>,
    monthsCovered: Int,
): ChannelChronicle.Chronicle {
    val genome = runCatching { loadIndexGenome(chatId) }.getOrNull()
    return ChannelChronicle.of(
        days = days,
        monthsCovered = monthsCovered,
        genomeMinDateSec = genome?.minDateSec,
    )
}
