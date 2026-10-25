package dev.tgfeed.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.tgfeed.core.feed.FeedViewModel
import dev.tgfeed.core.td.VideoItem

/**
 * 装填链（R131 从 VideoPage.kt 按职责拆出，纯搬运零逻辑改动）。
 * [VideoLoadEffect]：player 与播放地址就绪后的 setMediaItem/prepare/续播链——
 * 热切换续当前位置、R109 尊重用户手动暂停（不偷跑成自动播放）、
 * 片尾续播钳制（防翻回已播完页被当场再弹走）、同地址 IDLE 重 prepare、
 * ENDED 归零重播、mediaId 挂音量均衡记忆。
 * 可变状态以 [androidx.compose.runtime.MutableState] 传入（页面侧同一对象）。
 */
@Composable
internal fun VideoLoadEffect(
    player: Player?,
    item: VideoItem,
    feedVm: FeedViewModel,
    playUriState: androidx.compose.runtime.MutableState<String?>,
    loadFailedState: androidx.compose.runtime.MutableState<Boolean>,
    qualityHotSwapState: androidx.compose.runtime.MutableState<Boolean>,
    retryReloadState: androidx.compose.runtime.MutableState<Boolean>,
    pausedState: androidx.compose.runtime.MutableState<Boolean>,
    firstFrameSeenState: androidx.compose.runtime.MutableState<Boolean>,
) {
    var playUri by playUriState
    var loadFailed by loadFailedState
    var qualityHotSwap by qualityHotSwapState
    var retryReload by retryReloadState
    var paused by pausedState
    var firstFrameSeen by firstFrameSeenState
    LaunchedEffect(player, playUri) {
        val p = player ?: return@LaunchedEffect
        val uri = playUri ?: return@LaunchedEffect
        // R80 埋点：装填时刻的地址落点（成败在监听器的 state/err 事件里回记）
        dev.tgfeed.core.playback.PlaybackTrace.log(
            "load", item.key, "…${uri.takeLast(28)}",
        )
        // 失败态下不自动起播：等用户点按重试（点按会复位 loadFailed 并 bump retryTick 重拉）
        if (loadFailed) return@LaunchedEffect
        // 清晰度热切换（GAPS3 #10）：本帧消费标记——换源前记下当前播放位置，
        // 新档备好后续播到该位置；用户手动暂停中的换源保持暂停（不偷跑成自动播放）
        val hotSwap = qualityHotSwap
        qualityHotSwap = false
        // R109 V-1：自动重试链的换路重拉走同一装填 effect，也要尊重用户手动暂停态
        // （用户暂停缓冲中的视频后重试链发出，旧实现走到末尾无条件 playWhenReady=true，
        // 把用户刚按下的暂停强行解除）；滑走滑回的重新起播不置本标记，维持原续播行为
        val retrySwap = retryReload
        retryReload = false
        val keepPause = (hotSwap || retrySwap) && paused
        if (p.currentMediaItem?.localConfiguration?.uri.toString() != uri) {
            // 续播位置现读 VM 进度表（feedVm 与外层同一共享实例），不用入参快照：
            // progressToRestore 在组合时定格，进程重启后 progressMap 磁盘预载若晚于
            // 本页组合完成，入参会是 0；effect 执行时现读则总能拿到已预载的最新值
            var resumeMs = if (hotSwap) p.currentPosition else feedVm.getProgress(item.key)
            // 片尾续播钳制（修"翻回已播完页被当场再弹走"的残余路径）：记忆位置贴到片尾
            // 500ms 内一律从头重播。saveProgress 的 97% 清除依赖元数据 durationSec，
            // 元数据与真实时长失配（或时长缺失）时片尾位置会被原样记下——不钳制的话
            // seek 到片尾 prepare 后秒进 ENDED，自动连播立刻再把用户弹下去
            if (!hotSwap && item.durationSec > 0 && resumeMs >= item.durationSec * 1000L - 500L) {
                resumeMs = 0L
            }
            p.setMediaItem(
                // setMediaId(item.key)：音量均衡按内容 key 记忆增益（PlayerPool 的
                // onMediaItemTransition 把 mediaId 转交 LoudnessNormalizer）；
                // 不传时 mediaId 退化为流地址，清晰度热切换换地址会触发重学
                MediaItem.Builder().setUri(uri).setMediaId(item.key).build(),
            )
            // 续播：热切换续当前位置；普通换片恢复上次进度（0 或空 = 从头）
            if (resumeMs > 0L) p.seekTo(resumeMs)
            // R93：新源装填，首帧标记复位——封面重新压上，等新源首帧上屏再撤
            firstFrameSeen = false
            p.prepare()
            if (keepPause) {
                p.playWhenReady = false
                return@LaunchedEffect
            }
        } else if (p.playbackState == Player.STATE_IDLE) {
            // 同地址重试（看门狗重拉/点按重试后流地址没变）：播放器出错停在 IDLE 时
            // 只设 playWhenReady 不会重新起播，必须重新 prepare——
            // 旧实现缺这一步，"重试"形同虚设：转圈几圈停掉后永久黑屏
            // R93：重拉后首帧需重新渲染，复位标记让封面盖住这段窗口
            firstFrameSeen = false
            p.prepare()
        }
        // 回看已播完的视频（修"划回看不了第二遍"）：激活时播放器已是 ENDED
        // （或续播位置已到末尾 0.5s 内）→ 归零重播；否则 onEnded 会立刻再触发
        // 自动连播跳下一条，用户永远回不去看
        if (keepPause) {
            // R109 V-1：热切换/自动重试换路撞上用户手动暂停——源已备好但保持暂停，
            // 交还用户点按继续（ENDED 归零重播也留给点按分支处理）
            p.playWhenReady = false
            return@LaunchedEffect
        }
        if (p.playbackState == Player.STATE_ENDED) {
            p.seekTo(0)
        }
        p.playWhenReady = true
        paused = false
    }
}
