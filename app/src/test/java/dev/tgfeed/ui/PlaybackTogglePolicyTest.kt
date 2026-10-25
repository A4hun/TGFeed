package dev.tgfeed.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R375 用户侧播放开关判定单测（纯函数全矩阵）。
 * 根因判词（用户 11:2x 实锤「视频/音频正在加载缓冲时，单击屏幕的暂停/播放不生效，
 * 要等完全加载成功才能暂停」）：开关旧用 isPlaying 判定——缓冲期 isPlaying=false
 * 但 playWhenReady=true（播放意图在），点按被判成「没在播」→play() 落在已是 true
 * 的意图上零变化＝不生效。判定换 playWhenReady 意图口径（缓冲中用户语义"在播"，
 * PlayerPool 后台闸/导航闸既有判词同源）。
 * 三档锚+边缘档：缓冲中（true+BUFFERING）/真暂停（false+任意）/ENDED（播完走
 * 先归零再播的重播链，意图仍挂 true 也不算在播）/IDLE（空机，与旧口径行为
 * 逐字一致走 play()）。
 */
class PlaybackTogglePolicyTest {

    // androidx.media3.common.Player 播放态常量镜像（纯 JVM 测试不引 AAR 依赖）：
    // STATE_IDLE=1 / STATE_BUFFERING=2 / STATE_READY=3 / STATE_ENDED=4
    private val stateIdle = 1
    private val stateBuffering = 2
    private val stateReady = 3
    private val stateEnded = 4

    // ---------------- ① 缓冲中：意图在＝用户语义在播（点按=暂停） ----------------

    @Test
    fun `缓冲中_播放意图在即算在播_点按应暂停`() {
        // R375 病根主锚：BUFFERING 期 isPlaying=false 但 playWhenReady=true——
        // 旧 isPlaying 口径判成"没在播"→play() 零变化；意图口径判在播→pause() 生效
        assertTrue(
            PlaybackTogglePolicy.userPlayingIntent(playWhenReady = true, playbackState = stateBuffering),
        )
    }

    // ---------------- ② 真暂停：意图不在＝不在播（点按=播放） ----------------

    @Test
    fun `真暂停_任意态意图不在都算没在播_点按应播放`() {
        assertFalse(PlaybackTogglePolicy.userPlayingIntent(false, stateReady))
        assertFalse(PlaybackTogglePolicy.userPlayingIntent(false, stateBuffering))
        assertFalse(PlaybackTogglePolicy.userPlayingIntent(false, stateIdle))
        assertFalse(PlaybackTogglePolicy.userPlayingIntent(false, stateEnded))
    }

    // ---------------- ③ 正常播放：意图在且就绪 ----------------

    @Test
    fun `正常播放_就绪且意图在`() {
        assertTrue(PlaybackTogglePolicy.userPlayingIntent(true, stateReady))
    }

    // ---------------- ④ ENDED 语义守卫：播完不算在播（点按走归零重播链） ----------------

    @Test
    fun `ENDED播完例外_意图仍挂true也不算在播_点按走先归零再播链`() {
        // 播完态 ExoPlayer 的 playWhenReady 仍挂 true，但用户语义是"播完了"——
        // 若按裸意图口径判在播，点按会变成 pause()（播完想重播却先被打一下暂停，
        // ENDED 重播链被破坏）。AudioCard 大圆钮/VideoPage 点按/VideoLoad 装填链的
        // 「先 seekTo(0) 再 play()」依赖本例外恒落 else 分支（行为与旧口径逐字一致）
        assertFalse(PlaybackTogglePolicy.userPlayingIntent(true, stateEnded))
    }

    @Test
    fun `ENDED播完例外_意图false同样不算在播`() {
        // 用户在片尾按过暂停（ENDED+false）：点按同样走 else 分支→归零重播链
        assertFalse(PlaybackTogglePolicy.userPlayingIntent(false, stateEnded))
    }

    // ---------------- ⑤ IDLE 边缘：空机不算在播（与旧口径行为逐字一致） ----------------

    @Test
    fun `IDLE空机例外_意图残挂也不算在播_点按仍走play`() {
        // 出错停在 IDLE 的空机 playWhenReady 可能残挂 true——旧 isPlaying 口径此态
        // 点按走 play()（无用户可见变化）；意图口径排除 IDLE 保持同分支，不引入
        // "对空机 toggle 不可见暂停态"的新边缘行为
        assertFalse(PlaybackTogglePolicy.userPlayingIntent(true, stateIdle))
    }
}
