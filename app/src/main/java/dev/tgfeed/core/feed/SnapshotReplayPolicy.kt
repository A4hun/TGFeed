package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem

/**
 * R390 快照垫底路「类型/筛选对齐」谓词（纯函数，JVM 可测）。
 *
 * 病灶（用户 2026-10-25 14:4x 实锤「关注界面选视频 tab 出现图片资源」）：
 * 两条快照垫底路——冷启主快照（R52）/关注流快照（R170b）——**直插 _items、
 * 绕过 loadMore 的 pass 谓词**，此前只滤「已看」；而快照由预热实例按全部类型
 * 采集=混合内容（[FeedViewModel.warmFollowingSnapshot] 用独立实例、无 tab 语境），
 * 用户当前媒体 tab / 设置页类型开关 / 内容筛选全不过滤 → 混合快照把图文/音频
 * 条目摆进视频 tab，网络首拉只追加不去除，图片一直挂到手动刷新。
 *
 * 与 scanFresh 置顶路旧病同族（「视频 tab 混进图文/音频置顶」已修——FeedFollowPart
 * 同口径判词）；本谓词=两条快照路共用的同一把尺（发现一个修一类）。
 *
 * 屏蔽/不感兴趣/永久指纹/已看等**集合判定在调用侧**（与 pass 谓词同口径组合；
 * 本谓词只锁类型 tab/类型开关/内容筛选三道确定性维度）。
 */
object SnapshotReplayPolicy {
    /**
     * 快照条目是否按当前口径放行：
     * ①单类型 tab 严格相等（全部 tab=-1 不限——与 loadMore pass 谓词同口径）；
     * ②设置页内容类型开关（0 视频 / 1 图文 / 其余音频）；
     * ③内容筛选器（方向/时长/发布时间——FeedFilter 未知字段语义原样）。
     */
    fun typeAligned(
        item: VideoItem,
        mediaTab: Int,
        showVideos: Boolean,
        showPhotos: Boolean,
        showAudio: Boolean,
        filter: FeedFilter,
    ): Boolean =
        (mediaTab == -1 || item.mediaType == mediaTab) &&
            when (item.mediaType) {
                0 -> showVideos
                1 -> showPhotos
                else -> showAudio
            } &&
            filter.matches(item)
}
