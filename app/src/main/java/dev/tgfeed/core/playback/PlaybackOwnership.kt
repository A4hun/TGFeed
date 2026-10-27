package dev.tgfeed.core.playback

/**
 * R417 播放占有权仲裁（纯函数，单一事实源）：同一时刻只有当前前台路由持有视频播放，
 * 路由切换时非持有者一律暂停（不清状态，回页可续播）。
 *
 * 持有者（[PlaybackOwner]）由 MainActivity 组合期快照经 [resolvePlaybackOwner] 裁出，
 * 写入 AppContainer.playbackOwner；各视频池宿主（Feed/分屏）经共享件
 * PlaybackOwnershipGate 消费 [ownershipDecision]——取代各页私有的导航闸补丁
 * （旧 feedActive 合成只服务 Feed 池，分屏池零导航闸：频道主页覆盖层压在分屏上
 * 时格子照播）。
 *
 * 豁免（不受本仲裁约束）：
 * - 系统 PiP：不改变持有者（PiP 窗即当前持有者在播），后台闸另有 pipExempt；
 * - 悬浮窗池（FloatingPlayerHost/SystemFloatingController）：小窗用户可见，不接本件；
 * - 音频后台（AudioBackgroundService/ListenQueue + 共存开关=开）：音频分量沿用
 *   [navPauseDecision] 矩阵（共存开 = 音频豁免）。
 */
enum class PlaybackOwner {
    /** 首页 Feed 内容（推荐/关注/潜进源） */
    FEED,

    /** 分屏 GridScreen */
    GRID,

    /** 首页时间线 overlay（PursueScreen，本身无播放器） */
    TIMELINE,

    /** 频道主页覆盖层 */
    CHANNEL_HOME,

    /** 二级整页路由（设置/搜索/下载等） */
    SUB_PAGE,

    /** 发现页 */
    DISCOVER,

    /** 我的页 */
    MINE,

    /** 无前台路由（登录/启动等待） */
    NONE,
}

/** 占有权快照：MainActivity 路由态的只读投影 */
data class PlaybackOwnershipSnapshot(
    val showFeed: Boolean,
    val viewMode: String,
    /** 顶层页位（滚动中取 targetPage、静止取 settledPage） */
    val topLevelPage: Int,
    /** 二级整页路由在场（不含频道主页） */
    val subPageOpen: Boolean,
    val channelHomeOpen: Boolean,
    val timelineOn: Boolean,
)

/**
 * 裁决前台持有者。优先级（自顶向下的 z 序）：未就绪 → 频道主页覆盖层（压在 Feed 与
 * 分屏之上）→ 二级整页 → 分屏 → 顶层页位（发现/我的）→ 时间线 overlay → Feed。
 */
fun resolvePlaybackOwner(s: PlaybackOwnershipSnapshot): PlaybackOwner = when {
    !s.showFeed -> PlaybackOwner.NONE
    s.channelHomeOpen -> PlaybackOwner.CHANNEL_HOME
    s.subPageOpen -> PlaybackOwner.SUB_PAGE
    s.viewMode == "grid" -> PlaybackOwner.GRID
    s.topLevelPage == 1 -> PlaybackOwner.DISCOVER
    s.topLevelPage == 2 -> PlaybackOwner.MINE
    s.topLevelPage != 0 -> PlaybackOwner.NONE
    s.timelineOn -> PlaybackOwner.TIMELINE
    else -> PlaybackOwner.FEED
}

/**
 * 某视频池宿主是否持有播放。
 * @param localYield 宿主页内满屏覆盖让位（如 Feed 内快拍连播压顶——快拍自管播放器
 *   成为页内持有者，宿主池让位）
 */
fun ownsPlayback(owner: PlaybackOwner, holder: PlaybackOwner, localYield: Boolean = false): Boolean =
    owner == holder && !localYield

/**
 * 宿主的暂停裁决：视频分量=非持有即停；音频分量按 [navPauseDecision] 共存矩阵。
 */
fun ownershipDecision(
    owner: PlaybackOwner,
    holder: PlaybackOwner,
    allowAudioVideoSimultaneous: Boolean,
    localYield: Boolean = false,
): NavPauseDecision = navPauseDecision(
    feedPageActive = ownsPlayback(owner, holder, localYield),
    allowAudioVideoSimultaneous = allowAudioVideoSimultaneous,
)
