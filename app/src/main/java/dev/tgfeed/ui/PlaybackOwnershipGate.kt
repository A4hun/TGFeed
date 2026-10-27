package dev.tgfeed.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.exoplayer.ExoPlayer
import dev.tgfeed.core.playback.PlaybackOwner
import dev.tgfeed.core.playback.PlayerPool
import dev.tgfeed.core.playback.ownershipDecision

/**
 * R417 播放占有权闸（共享件）：视频池宿主（Feed/分屏）统一接入，非持有者落池的
 * 导航闸（一刀切暂停+记账+迟到拦截，含池外自管登记），重新持有时抬闸续播。
 * 裁决=core/playback/PlaybackOwnership 纯函数；音频分量（可选 [audioPlayer]）按共存
 * 矩阵暂停/豁免。探针「R417 占有权」只输出持有者枚举名与计数。
 */
@Composable
internal fun PlaybackOwnershipGate(
    pool: PlayerPool,
    owner: PlaybackOwner,
    holder: PlaybackOwner,
    allowAudioVideoSimultaneous: Boolean,
    localYield: Boolean = false,
    audioPlayer: ExoPlayer? = null,
) {
    var pausedVideoKeys by remember { mutableStateOf<List<String>>(emptyList()) }
    var pausedAudio by remember { mutableStateOf(false) }
    val decision = ownershipDecision(owner, holder, allowAudioVideoSimultaneous, localYield)
    LaunchedEffect(decision, owner) {
        if (decision.pausesVideo || decision.pausesAudio) {
            if (decision.pausesVideo && !pool.navGate) {
                // 只在新落闸时取账本（重复落闸幂等返回空表，不覆盖已有账本）
                pausedVideoKeys = pool.setNavGate(true)
            }
            if (decision.pausesAudio && audioPlayer != null && audioPlayer.playWhenReady) {
                audioPlayer.pause()
                pausedAudio = true
            }
            android.util.Log.i(
                "R417 占有权",
                "owner=${owner.name} holder=${holder.name} 暂停视频=${pausedVideoKeys.size} 音频=${if (pausedAudio) 1 else 0}",
            )
        } else {
            val n = pausedVideoKeys.size
            pool.setNavGate(false, pausedVideoKeys)
            pausedVideoKeys = emptyList()
            if (pausedAudio) {
                audioPlayer?.play()
                pausedAudio = false
            }
            android.util.Log.i("R417 占有权", "owner=${owner.name} holder=${holder.name} 持有 恢复账=${n}")
        }
    }
}
