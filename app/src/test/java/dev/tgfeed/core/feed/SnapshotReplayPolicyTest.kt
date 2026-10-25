package dev.tgfeed.core.feed

import dev.tgfeed.core.td.VideoItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R390 快照垫底路「类型/筛选对齐」谓词单测（[SnapshotReplayPolicy.typeAligned]）：
 *
 * 复现面=用户实锤「关注界面选视频 tab 出现图片资源」——快照由预热实例按全部
 * 类型采集（混合内容），重放必须按当前媒体 tab / 设置页类型开关 / 内容筛选
 * 放行；反方向（图文 tab 滤视频——真实浏览滚动落盘的快照是单类型过滤态）同守。
 */
class SnapshotReplayPolicyTest {

    private fun item(mediaType: Int, durationSec: Int = 30) = VideoItem(
        key = "k$mediaType-$durationSec",
        chatId = 1,
        messageId = 1,
        channelTitle = "频道",
        caption = "内容",
        durationSec = durationSec,
        width = 720,
        height = 1280,
        fileId = 7,
        sizeBytes = 1024L,
        playUri = "http://x/1",
        dateEpochSec = 1_700_000_000,
        mediaType = mediaType,
    )

    @Test
    fun `视频 tab 只放行视频_滤掉图文与音频`() {
        val f = FeedFilter()
        assertTrue(SnapshotReplayPolicy.typeAligned(item(0), 0, true, true, true, f))
        assertFalse(SnapshotReplayPolicy.typeAligned(item(1), 0, true, true, true, f))
        assertFalse(SnapshotReplayPolicy.typeAligned(item(2), 0, true, true, true, f))
    }

    @Test
    fun `图文 tab 反方向滤视频与音频`() {
        val f = FeedFilter()
        assertTrue(SnapshotReplayPolicy.typeAligned(item(1), 1, true, true, true, f))
        assertFalse(SnapshotReplayPolicy.typeAligned(item(0), 1, true, true, true, f))
        assertFalse(SnapshotReplayPolicy.typeAligned(item(2), 1, true, true, true, f))
    }

    @Test
    fun `全部 tab 不限类型`() {
        val f = FeedFilter()
        assertTrue(SnapshotReplayPolicy.typeAligned(item(0), -1, true, true, true, f))
        assertTrue(SnapshotReplayPolicy.typeAligned(item(1), -1, true, true, true, f))
        assertTrue(SnapshotReplayPolicy.typeAligned(item(2), -1, true, true, true, f))
    }

    @Test
    fun `设置页类型开关独立生效_全部 tab 也守`() {
        val f = FeedFilter()
        // 关「图片」：全部 tab 下图文条目仍被滤（冷启快照路的用户实锤面）
        assertFalse(SnapshotReplayPolicy.typeAligned(item(1), -1, true, false, true, f))
        assertTrue(SnapshotReplayPolicy.typeAligned(item(0), -1, true, false, true, f))
        // 关「视频」：视频条目被滤
        assertFalse(SnapshotReplayPolicy.typeAligned(item(0), -1, false, true, true, f))
        // 关「音频」：音频条目被滤
        assertFalse(SnapshotReplayPolicy.typeAligned(item(2), -1, true, true, false, f))
    }

    @Test
    fun `内容筛选维度同口径_时长区间`() {
        val f = FeedFilter(minDurationSec = 60)
        assertFalse(SnapshotReplayPolicy.typeAligned(item(0, 30), -1, true, true, true, f))
        assertTrue(SnapshotReplayPolicy.typeAligned(item(0, 90), -1, true, true, true, f))
    }

    @Test
    fun `组合口径_tab 与开关与筛选 AND`() {
        val f = FeedFilter(minDurationSec = 60)
        // 视频 tab + 关图片 + 时长 60 下限：视频 90s 过；图文 90s 被 tab 滤；视频 30s 被时长滤
        assertTrue(SnapshotReplayPolicy.typeAligned(item(0, 90), 0, true, false, true, f))
        assertFalse(SnapshotReplayPolicy.typeAligned(item(1, 90), 0, true, false, true, f))
        assertFalse(SnapshotReplayPolicy.typeAligned(item(0, 30), 0, true, false, true, f))
    }
}
