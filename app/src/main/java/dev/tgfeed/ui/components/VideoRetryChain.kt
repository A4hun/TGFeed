package dev.tgfeed.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem

/**
 * 失败自动重试链的状态编排（R131 从 VideoPage.kt 按职责拆出，纯搬运零逻辑改动；
 * 判定件 fatalReasonOf/isCorruptContentError/退避常量见 VideoRetryPolicy.kt）。
 * [VideoRetryChainEffects] 收纳两条 effect：
 * ① 加载看门狗：缓冲 6 秒且播放位置/下载进度纹丝不动 → 记一次失败事件喂重试链；
 * ② 自动退避重试链：3s→6s→12s→24s 退避后换路重拉（剥缓存地址/换发 fileId），
 *   4 次全败才转失败终态。可变状态以 [androidx.compose.runtime.MutableState] 传入（页面侧同一对象）。
 */
@Composable
internal fun VideoRetryChainEffects(
    player: Player?,
    item: VideoItem,
    gateway: TdGateway,
    isActive: Boolean,
    playUriState: androidx.compose.runtime.MutableState<String?>,
    bufferingState: androidx.compose.runtime.MutableState<Boolean>,
    loadFailedState: androidx.compose.runtime.MutableState<Boolean>,
    failCountState: androidx.compose.runtime.MutableState<Int>,
    retryPendingState: androidx.compose.runtime.MutableState<Boolean>,
    fatalReasonState: androidx.compose.runtime.MutableState<String?>,
    retryItemState: androidx.compose.runtime.MutableState<VideoItem>,
    retryReloadState: androidx.compose.runtime.MutableState<Boolean>,
    retryTickState: androidx.compose.runtime.MutableState<Int>,
    dlProgressState: androidx.compose.runtime.MutableState<Float>,
) {
    var playUri by playUriState
    var buffering by bufferingState
    var loadFailed by loadFailedState
    var failCount by failCountState
    var retryPending by retryPendingState
    var fatalReason by fatalReasonState
    var retryItem by retryItemState
    var retryReload by retryReloadState
    var retryTick by retryTickState
    var dlProgress by dlProgressState

    // 加载看门狗（治"卡住不动得手动切走切回"）：
    // 缓冲超过 6 秒且下载进度一点没动 → 判定卡死，记一次失败事件（failCount++）交给
    // 下方自动退避重试链统一处理（3s/6s/12s/24s 退避、换路重拉、4 次全败才终态）。
    // retryPending 期间不再重复记失败：退避等待期的 6s 窗口还会命中同一卡死现场
    val retryPendingNow by rememberUpdatedState(retryPending)
    LaunchedEffect(buffering, playUri, retryTick) {
        if (!buffering || !isActive || loadFailed) return@LaunchedEffect
        val startProgress = dlProgress
        val startPos = player?.currentPosition ?: 0L
        kotlinx.coroutines.delay(6000)
        // 真卡死的判定（缺一都是误伤）：
        //  1. 6 秒后仍在缓冲
        //  2. 播放位置纹丝没动
        //  3. 下载还在半路且没进展（dlProgress<1 且不增；=1 说明已下完在等解码，不是网络卡）
        // 修复前的 bug：已下载完的本地文件 dlProgress 恒为 1f，被判"没进展"反复重置
        // playUri 直到黑屏——用户实测"圈也不转了一直黑屏"就是这个
        val posNow = player?.currentPosition ?: 0L
        val streamStalled = playUri != null &&
            posNow <= startPos &&
            dlProgress >= 0f && dlProgress < 1f && dlProgress <= startProgress
        // playUri 迟迟拉不到（ensurePlayable 失败/挂死返回 null）也是卡死：
        // 旧实现直接跳过这条路径（playUri==null 时整个看门狗 early-return），
        // 拉流一旦失败就转圈转一辈子——"黑屏死等"的主凶之一。
        // 下载进度在涨说明只是慢，不动它；纹丝不动才记失败
        val uriStalled = playUri == null && dlProgress <= startProgress
        if (buffering && isActive && !loadFailed && !retryPendingNow && (streamStalled || uriStalled)) {
            // R80 埋点：看门狗判死现场（流停滞/地址迟迟拉不到）
            dev.tgfeed.core.playback.PlaybackTrace.log(
                "dog", item.key,
                if (streamStalled) "流停滞 pos=$posNow dl=$dlProgress" else "地址未就绪 dl=$dlProgress",
            )
            retryPending = true
            failCount++
        }
    }

    // 自动退避重试链（失败不再等用户点）：每次失败事件退避 3s→6s→12s→24s 后换路重拉，
    // 最多 AUTO_RETRY_MAX(4) 次；全部失败才转 loadFailed 终态（给"点按重试/换一条看"出口）。
    // 换路（治"点重试还无限失败"的根因——旧重试置空 playUri 后 ensurePlayable 拿的
    // 还是同一个坏地址/坏 fileId）：
    //  ① 剥掉条目缓存的 playUri：ensurePlayable 对 item.playUri 短路直返，TDLib 抓取时
    //     写入的本地路径一旦被清缓存/删除，每次重试都原样拿回死路径；
    //  ② 第 2 次起重试连 fileId 也重新解析：优先按消息 GetMessage 重取当前内容的新
    //     fileId，回落按 fileUniqueId（内容指纹）换发——fileId 是会话级 id，失效后
    //     每次重试都撞同一堵墙。
    // Mock 直链条目无 fileId 可换（ensurePlayable 就是返回原 playUri），跳过换路。
    // 页失焦/销毁时本 effect 随组合取消，退避中的重试自然作废。
    LaunchedEffect(failCount) {
        if (failCount <= 0) return@LaunchedEffect
        // R324 离线分诊（看门狗路径的对称闸）：失败事件撞上 NetworkWatch 离线——
        // 看门狗 6s 判死后的换路重拉（重解析 fileId/重下载）在离线期是必然失败的
        // 假努力（4 次退避 45s 转圈换一个死胡同）；直达失败终态，文案
        // OfflineDegradePolicy 单源出（离线胶囊保持可点：联网后人工重试有真实意义）。
        // 在线照旧走既有退避链——本闸只在离线沿短路。
        if (failCount <= AUTO_RETRY_MAX) {
            val offlineText = dev.tgfeed.core.net.OfflineDegradePolicy.playerOfflineFatalText(
                offline = !dev.tgfeed.core.net.NetworkWatch.online.value,
            )
            if (offlineText != null) {
                dev.tgfeed.core.playback.PlaybackTrace.log(
                    "offlineTerminal", item.key,
                    "第${failCount}次失败时离线——直达终态（不烧退避重试）",
                )
                fatalReason = offlineText
                loadFailed = true
                retryPending = false
                return@LaunchedEffect
            }
        }
        if (failCount > AUTO_RETRY_MAX) {
            // 自动重试全败：转失败终态（换片/跳过出口），不再自动重试
            dev.tgfeed.core.playback.PlaybackTrace.log("terminal", item.key, "自动重试 ${AUTO_RETRY_MAX} 次全败")
            loadFailed = true
            return@LaunchedEffect
        }
        dev.tgfeed.core.playback.PlaybackTrace.log(
            "retry", item.key, "第${failCount}次 退避${AUTO_RETRY_BASE_MS shl (failCount - 1)}ms",
        )
        kotlinx.coroutines.delay(AUTO_RETRY_BASE_MS shl (failCount - 1))
        // 退避期间若撞上不可重试失败（fatalReason 已置位，如旧的出错源回调迟到的解码
        // 失败），本次重试作废——稳定终态重试救不了，不发假努力的重拉
        if (fatalReason != null) return@LaunchedEffect
        var base = item
        if (gateway is TdLibGateway) {
            base = base.copy(playUri = null) // 换路①：不信条目缓存的播放地址
            if (failCount >= 2) {
                // 换路②：fileId 也可能坏了——按消息重解析，失败回落按内容指纹换发
                val newId = runCatching { gateway.reviveFileIdByMessage(base.chatId, base.messageId) }
                    .getOrNull()
                    ?: base.fileUniqueId.takeIf { it.isNotEmpty() }
                        ?.let { runCatching { gateway.reviveFileId(it) }.getOrNull() }
                if (newId != null && newId > 0 && newId != base.fileId) {
                    // 清晰度变体档的 fileId 同属旧会话，一并作废（装填 effect 按新主档重来）
                    base = base.copy(fileId = newId, variants = emptyList())
                }
            }
        }
        retryItem = base
        loadFailed = false // 自愈守卫：若此前曾置位终态，重试发出即解除
        retryReload = true // 本次重拉由自动重试链发出：装填 effect 不得解除用户手动暂停
        playUri = null     // 清掉页内缓存地址，装填 effect 重新 ensurePlayable（用换路后的 retryItem）
        retryTick++
        retryPending = false // 重试已发出：看门狗从新的 6s 窗口重新盯梢这次重试
    }
}
