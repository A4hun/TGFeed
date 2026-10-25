package dev.tgfeed.ui

import androidx.media3.common.Player

/**
 * R375 用户侧播放开关判定（暂停/播放钮与点按切换的统一口径，纯函数单一来源）。
 *
 * 根因判词（用户 11:2x 实锤「视频/音频正在加载缓冲时，单击屏幕的暂停/播放不生效，
 * 要等完全加载成功才能暂停」）：用户侧开关旧用 isPlaying 判定——缓冲期
 * isPlaying=false 但 playWhenReady=true（播放意图在），点按被判成「没在播」→
 * 调 play()→意图本就是 true→零变化＝「不生效，要等加载完」。判定改用
 * playWhenReady：**缓冲中 isPlaying=false 但用户语义上是"在播"**（仓内既有
 * 正确口径——PlayerPool 后台闸/导航闸的 setNavGate 与 FeedScreen 后台记账
 * 同判词：旧覆盖层/后台闸正是栽在 isPlaying 口径上，缓冲中的播放意图查不到）。
 *
 * ENDED 例外（语义守卫，单测锚死）：播完态 playWhenReady 仍挂 true 但用户语义
 * 是"播完了"，意图口径不计入在播——点按落入 else 分支走「先归零（seekTo(0)）
 * 再 play()」重播链（VideoPage 点按分支 / AudioCard 大圆钮与迷你条 /
 * VideoLoadEffect 回看归零重播链既有行为原样，零破坏）。IDLE 同例排除：无内容
 * 的空机谈不上"在播"，点按走 play() 与旧 isPlaying 口径行为逐字一致。
 *
 * 两口径分职（各司其职，禁止混用——负锚见 R375WiringSourceTest）：
 *  - **playWhenReady 意图口径（本策略）**：用户侧开关的切换动作与「用户语义
 *    在播」呈现（暂停/播放钮图标与动作、中央 ⏸/缓冲指示的让位、悬浮窗胶囊钮）；
 *  - **isPlaying 真出声口径（保持零改）**：音浪/封面呼吸动画、观看计时与轮播
 *    换片判据（GridScreen playingNow 表、FeedWatchPart 心跳）、进度跳变信号
 *    采集两拍基线（VideoSeekOverlay 的 VideoSeekSignalEffect——缓冲中位置不
 *    前进，若按意图口径计"在播"会产出负漂移假跳变）。
 */
object PlaybackTogglePolicy {

    /**
     * 用户语义"在播"判定（点按开关的分支依据）。
     *
     * [playWhenReady] = 播放意图在不在（缓冲中也算）；[playbackState] =
     * androidx.media3.common.Player 的播放态常量（STATE_ENDED=播完、STATE_IDLE=
     * 空机，两者均不算在播）。
     *
     * 三档锚（R375 用户实锤的修复口径）：
     *  - 缓冲中（true + STATE_BUFFERING）→ 在播：点按=暂停（旧 isPlaying 口径
     *    判成"没在播"→play() 落在已是 true 的意图上零变化＝不生效的病根）；
     *  - 真暂停（false + 任意态）→ 不在播：点按=播放；
     *  - ENDED（任意意图）→ 不在播：点按走先归零再 play 的重播链。
     */
    fun userPlayingIntent(playWhenReady: Boolean, playbackState: Int): Boolean =
        playWhenReady && playbackState != Player.STATE_ENDED && playbackState != Player.STATE_IDLE
}
