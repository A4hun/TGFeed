package dev.tgfeed.core.playback

/**
 * R180 App 内导航播控策略（纯函数，需求①+②的判定收敛件——单一事实源，接线方与单测同源）。
 *
 * 输入矩阵（页面态 × 内容类型 × 共存开关）→ 导航闸暂停目标：
 *
 * | feed 页在台前 | 共存开关 | 视频 | 音频 |
 * |---|---|---|---|
 * | true（任意开关） | 任意 | 不暂停（页面自己在看） | 不暂停 |
 * | false | 关（默认） | **暂停** | **暂停**（需求①"切换暂停无例外"） |
 * | false | 开 | **暂停** | **豁免**（背景听模式：切到别的页面音频继续，"播客式"） |
 *
 * 语义口径（与 R158 退后台闸同一哲学，但驱动源是 App 内页面态而非 Activity 生命周期）：
 * - "暂停"= 不清状态（播放位置/在播条目保留），回页可续播——与 dispose-releaseAll
 *   （重建+账本续播）是两条不同的路径：闸服务于「页面仍组合但不在台前」的场景
 *   （频道主页覆盖层/快拍连播/顶层横滑过渡窗），dispose 路径维持 R177 离场制不动。
 * - 共存开关=关 的互斥语义（起音频停视频、起视频停音频）由**系统音频焦点**承载
 *   （双方 ExoPlayer handleAudioFocus=true，实测 2026-10-17：起视频 2.3s 前音频被
 *   LOSS 永久暂停），本策略件不重复管辖——它只裁决「页面切换时刻谁该停」。
 * - 共存开关=开 时音频豁免的一切下游（音频播放器跨页存活、焦点让位）在
 *   AppContainer/FeedScreen 侧接线，本件只出判定。
 */
enum class NavPlaybackMedium { VIDEO, AUDIO }

/** 导航闸裁决：被暂停的媒体集合（空 = 什么都不停） */
data class NavPauseDecision(val pausedMedia: Set<NavPlaybackMedium>) {
    val pausesVideo: Boolean get() = NavPlaybackMedium.VIDEO in pausedMedia
    val pausesAudio: Boolean get() = NavPlaybackMedium.AUDIO in pausedMedia

    companion object {
        val NONE = NavPauseDecision(emptySet())
    }
}

/**
 * 裁决「feed 页不在台前时」导航闸要暂停谁。
 *
 * @param feedPageActive feed 页是否在台前（AppContainer.feedPageActive 合成信号：
 *   顶层页位==首页 && 无子页整页路由 && 无频道主页覆盖层 && 视图模式==feed，
 *   由 MainActivity 写入；FeedScreen 侧再叠加本页内的满屏沉浸态——见接线处注释）
 * @param allowAudioVideoSimultaneous 「允许音频与视频同时播放」设置开关（默认关）
 */
fun navPauseDecision(
    feedPageActive: Boolean,
    allowAudioVideoSimultaneous: Boolean,
): NavPauseDecision = when {
    // 台前：页面自己在看/在听，导航闸不介入（互斥归系统焦点，页面内翻页归 isActive 失焦暂停）
    feedPageActive -> NavPauseDecision.NONE
    // 背景听模式（需求②开档）：视频照常暂停，音频豁免——音频卡变"播客式"后台播
    allowAudioVideoSimultaneous -> NavPauseDecision(setOf(NavPlaybackMedium.VIDEO))
    // 默认（需求①+②关档）：切换暂停无例外——音视频都停（不清状态，回页可续播）
    else -> NavPauseDecision(setOf(NavPlaybackMedium.VIDEO, NavPlaybackMedium.AUDIO))
}
