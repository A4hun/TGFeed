package dev.tgfeed.core.playback

import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 预加载调度：播放到 index 时，后台为后续 [ahead] 条做分级预热。
 * 新调度会取消旧的，保证预载目标永远跟着播放位置走。
 *
 * 分级策略（R145 修订：预载一律前缀化，根治"预载与播放抢带宽"）：
 *  - 距离 1（下一条）：[PRIORITY_NEXT] 预拉 [NEXT_PREFIX_BYTES] 前缀（moov+首段，
 *    起播秒开所需），**不再整载**——旧口径整载与正播流同时各开一套代理 TCP 连接，
 *    带宽被 TCP 公平性对半劈（华为平板基线实锤：正播近窗速率从 1MB/s 级跳水到
 *    300~400KB/s 的低谷全是预载整载在旁边跑）；前缀只借几秒带宽就收手，
 *    正播流全程独享代理。剩余部分由用户真滑过去后的泵按需补（与正播同一仲裁）。
 *  - 距离 2+（再下一条）：最低优先级（[PRIORITY_LATER]）同样只拉前缀。
 *  - 串行执行，任意时刻最多 1 个预载请求在途（上限放宽也别超过 2），
 *    避免和正在播放的流（priority=32 的 512KB 分块）抢带宽。
 */
class PreloadScheduler(
    private val gateway: TdGateway,
    private val scope: CoroutineScope,
    private val ahead: Int = 2,
) {
    private var job: Job? = null

    /**
     * 调度预载。[ahead] 为 null 时用构造默认深度（v1 行为）；
     * v2 起调用方可按当前频道行为传入动态深度（见 FeedViewModel.preloadAheadFor：
     * 高跳过率频道 1、完播率高的频道 3、其余 2），统一 clamp 到 [1, [MAX_AHEAD]]。
     */
    fun schedule(items: List<VideoItem>, currentIndex: Int, ahead: Int? = null) {
        job?.cancel()
        val depth = (ahead ?: this.ahead).coerceIn(1, MAX_AHEAD)
        job = scope.launch(Dispatchers.IO) {
            // R145 跨媒体前望：图片页有 minithumbnail 秒出、不需要字节预取——
            // 严格按"距离 1"预载时，夹在中间的图片页会让预载落空（用户连划两张图
            // 后撞上冷视频，起播等网络）。改为从当前位置向后找前 depth 个"非图片"
            // 条目（视频/音频都要 moov+首块）：第 1 个给高优前缀、其余给最低档前缀；
            // 前望窗口固定 [NEXT_VIDEO_LOOKAHEAD] 条（图片连排超窗就少预，不越权深取）。
            // depth（动态深度）语义保留：=本回合要预热的非图片条目数
            val to = minOf(currentIndex + NEXT_VIDEO_LOOKAHEAD, items.lastIndex)
            var mediaSeen = 0
            // R195 激进整载队列：同一份「下一个非图媒体」选择顺手收集（窗口级前缀
            // 上面已经发，这里是整文件档的目标），推给网关侧 AggressivePreloadManager
            // ——当前条整载完成后按序整载这些条目；滑走作废=新推送不含它。
            // 复用同一选择逻辑（跳图前望/fileId 过滤），不另写一份。
            val aggrNext = mutableListOf<Int>()
            for (i in (currentIndex + 1)..to) {
                val item = items[i]
                // 仅真实 TDLib 条目需要预下载；Mock 的 http 直链由 ExoPlayer 自身缓冲
                if (item.fileId < 0) continue
                // 图片不预取字节（minithumbnail 即时糊图 + W1/W2/W3 渐进加载自有链路）
                if (item.mediaType == 1) continue
                mediaSeen++
                if (mediaSeen == 1) {
                    runCatching { gateway.prioritize(item.fileId, PRIORITY_NEXT, 0L, NEXT_PREFIX_BYTES) }
                } else {
                    runCatching { gateway.prioritize(item.fileId, PRIORITY_LATER, 0L, PREFIX_BYTES) }
                }
                if (aggrNext.size < dev.tgfeed.core.td.AGGRESSIVE_NEXT_MAX) aggrNext.add(item.fileId)
                if (mediaSeen >= depth) break
            }
            runCatching { gateway.setAggressivePreloadNextTargets(aggrNext) }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private companion object {
        /** 动态预载深度上限：与 docs/ALGORITHMS.md §5 的 AHEAD_MAX 一致 */
        const val MAX_AHEAD = 3

        /** R145 跨媒体前望窗口：从当前位置向后看这么多条找前两个非图片条目 */
        const val NEXT_VIDEO_LOOKAHEAD = 6

        /** 下一条的预载优先级：前缀预拉，保证滑过去秒开（播放流分块用 32，低于它但高于后台） */
        const val PRIORITY_NEXT = 16

        /** 再下一条的预热优先级：最低档，只在带宽空闲时推进 */
        const val PRIORITY_LATER = 1

        /**
         * R145 下一条前缀大小：6MB ≈ moov + 1080p 首十秒——足够起播与首个观看段，
         * 又只占正播流几秒的带宽（旧口径整载一条 100MB+ 视频要劈走一半带宽数分钟）
         */
        const val NEXT_PREFIX_BYTES = 6L * 1024 * 1024

        /** 距离 2+ 的前缀预热大小：2MB，够 moov 头 + 首帧，起播秒开 */
        const val PREFIX_BYTES = 2L * 1024 * 1024
    }
}
