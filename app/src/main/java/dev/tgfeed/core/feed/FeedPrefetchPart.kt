package dev.tgfeed.core.feed

import androidx.lifecycle.viewModelScope
import dev.tgfeed.MainActivity
import dev.tgfeed.core.prefs.AppPrefs
import dev.tgfeed.core.td.TdGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------- WiFi 追更预载（到家连 WiFi 自动把订阅新视频缓存进 TDLib 私有缓存，离线可刷） ----------------

/** 设置 WiFi 预载开关（立即生效并写入 AppPrefs；预载循环每步开头读内存态，无需重启） */
internal fun FeedViewModel.setWifiPrefetchImpl(v: Boolean) {
    _wifiPrefetch.value = v
    runCatching { prefs.wifiPrefetch = v }
}

/**
 * 触发一轮 WiFi 追更预载（计量 → 非计量跳变沿上由网络监听协程调用；也可手动调用）：
 * 对订阅频道扫描 24h 内未看的视频条目（复用 [scanFreshSubscriptionItems] 的扫描口径，
 * 纯读不推进追更进度），逐条走 [TdGateway.prioritize]（TDLib DownloadFile）把文件
 * 拉进 TDLib 私有缓存——点开即播、离线可刷；单轮总量封顶 [WIFI_PREFETCH_MAX_ITEMS] 条。
 *
 * 预载语义边界（R103 修正，旧实现误走 [download] 落公共相册）：
 *  - 只落 TDLib 私有缓存，**不写公共相册、不登记 downloads.json（不进"我的下载"）、
 *    不记 BOOST_DOWNLOAD / download 互动分**——系统自动行为不得污染用户相册与推荐画像；
 *    用户主动"下载到相册"仍走 [download] 原链，不受影响；
 *  - 去重口径 = TDLib 缓存命中率（已缓存完整的跳过），与相册/下载记录无关。
 *
 * 执行纪律：
 *  - 串行低优：一条缓存落地（或超时放弃等待）才取下一条，预载用最低优先级
 *    [WIFI_PREFETCH_PRIORITY]（与 PreloadScheduler 的预热档同级），条与条之间再让路
 *    [WIFI_PREFETCH_ITEM_GAP_MS]——不并发抢带宽，不挡播放与 Feed 加载；
 *  - 条件复检：每条开头复查"开关开 + 非计量 + 前台"（[appInForeground] 由
 *    MainActivity onStart/onStop 置位），任一不再满足立即中止本轮——
 *    退后台不跑（省电）、切回计量网络立即止损（护流量）；
 *  - 防重入 + 间隔节流：一轮进行中或距上轮不足 [WIFI_PREFETCH_MIN_INTERVAL_MS]
 *    时触发直接忽略（WiFi 抖动/反复切网不重复烧带宽）；
 *  - 单条设等待上限 [WIFI_PREFETCH_ITEM_TIMEOUT_MS]：超时不再等它（TDLib 后台
 *    仍在拉，自然收尾），直接跳下一条，超大文件不堵死整轮；
 *  - 全程静默：任何失败只跳过该条，不写 _error 打扰当前界面（预载是增量收益）。
 */
internal fun FeedViewModel.runWifiPrefetchImpl() {
    // 入口闸：开关 + 非计量 + 前台 + 防重入 + 间隔节流（任一不满足静默忽略，不发网络请求）
    if (!_wifiPrefetch.value) return
    if (_networkMetered.value) return
    if (!appInForeground) return
    if (_wifiPrefetching.value) return
    val now = System.currentTimeMillis()
    if (now - lastWifiPrefetchAtMs < WIFI_PREFETCH_MIN_INTERVAL_MS) return
    lastWifiPrefetchAtMs = now
    _wifiPrefetching.value = true
    viewModelScope.launch(Dispatchers.IO) {
        try {
            // 扫描订阅频道 24h 内未看视频条目（口径与追更置顶一致：未看 = 不在观看历史；
            // 已屏蔽/不感兴趣已剔除）；仅真实 TDLib 文件（fileId >= 0）可缓存，
            // Mock（fileId=-1）与图/音跳过（图/音体量小，随刷随取即可）
            val candidates = runCatching { scanFreshSubscriptionItems() }.getOrDefault(emptyList())
                .asSequence()
                .filter { it.mediaType == 0 } // 预载只缓存视频
                .filter { it.fileId >= 0 } // 仅真实 TDLib 文件可预载
                .toList()
            var started = 0
            for (item in candidates) {
                // 条件复检：关开关 / 切回计量网络 / 退后台，任一发生立即中止本轮（护流量省电）
                if (started >= WIFI_PREFETCH_MAX_ITEMS) break
                if (!_wifiPrefetch.value || _networkMetered.value || !appInForeground) break
                // 去重口径 = TDLib 缓存命中率：已完整缓存的跳过（不再看相册/下载记录）
                if (gatewayDownloadProgress(item.fileId) >= 1f) continue
                // R166 在播让位闸：预载整载与播放泵在 TDLib 同文件只有一个下载窗口锚点
                // （同锚合并、异锚竞态互杀），盯着正在播的文件磨=抢它的 seek 能力（用户
                // 实锤「必须整个视频下载完才能快进」的窗口竞争源之一）。该文件有供流
                // 连接在跑就跳过本条换下一条——播放永远优先
                if (dev.tgfeed.core.td.TgStreamServer.isFileStreaming(item.fileId)) {
                    android.util.Log.d(
                        "TGFlow",
                        "WiFi 预载让位：fileId=${item.fileId} 正在播放，跳过该条预载",
                    )
                    continue
                }
                // 预载 = DownloadFile 落 TDLib 私有缓存（不进相册/下载列表/互动分）；
                // 最低优先级整载：只在带宽空闲时推进，不挤压播放流分块
                runCatching { gateway.prioritize(item.fileId, WIFI_PREFETCH_PRIORITY) }
                started++
                // 串行等待本条缓存落地：进度满（完成）即跳下一条；到超时上限放弃等待
                //（TDLib 后台仍在拉，自然收尾），超大文件不堵死整轮。
                // 进度查询连续失败（返回 -1）时的口径：从未见过真实进度且连续
                // 5 次查询落空才放弃该条——防止因查询偶发失败而连发预载、破坏串行低优纪律
                val deadline = System.currentTimeMillis() + WIFI_PREFETCH_ITEM_TIMEOUT_MS
                var sawProgress = false
                var unknownPolls = 0
                while (true) {
                    val p = gatewayDownloadProgress(item.fileId)
                    if (p >= 1f) break
                    if (p >= 0f) {
                        sawProgress = true
                        unknownPolls = 0
                    } else if (!sawProgress && ++unknownPolls >= 5) break
                    if (System.currentTimeMillis() >= deadline) break
                    // R166 在播让位（等待中途）：用户开始播放本条 → 预载让位退出等待，
                    // 该文件交还给播放泵（其窗口抢占会取消本预载查询），预载换下一条
                    if (dev.tgfeed.core.td.TgStreamServer.isFileStreaming(item.fileId)) break
                    delay(WIFI_PREFETCH_WAIT_POLL_MS)
                }
                delay(WIFI_PREFETCH_ITEM_GAP_MS) // 条间让路：播放/Feed 加载优先
            }
        } finally {
            _wifiPrefetching.value = false
        }
    }
}

// ---------------- WiFi 追更预载常量（FeedViewModel WiFi 预载段配套，文件级私有） ----------------

/** WiFi 预载：单轮预载条数上限（任务约束：20 条/次） */
private const val WIFI_PREFETCH_MAX_ITEMS = 20

/** WiFi 预载：两轮触发最小间隔（毫秒）：10 分钟——防 WiFi 抖动/反复切网重复预载 */
private const val WIFI_PREFETCH_MIN_INTERVAL_MS = 10L * 60_000L

/** WiFi 预载：单条缓存等待上限（毫秒）：10 分钟——超时不再等它（TDLib 后台仍在拉，自然收尾），跳下一条 */
private const val WIFI_PREFETCH_ITEM_TIMEOUT_MS = 10L * 60_000L

/** WiFi 预载：串行等待时查询缓存进度的轮询间隔（毫秒） */
private const val WIFI_PREFETCH_WAIT_POLL_MS = 1_000L

/** WiFi 预载：条与条之间的让路间隔（毫秒）：2 秒——播放/Feed 加载优先 */
private const val WIFI_PREFETCH_ITEM_GAP_MS = 2_000L

/** WiFi 预载：TDLib DownloadFile 优先级——最低档（与 PreloadScheduler 预热档同级），只在带宽空闲时推进，不挤压播放流 */
private const val WIFI_PREFETCH_PRIORITY = 1
