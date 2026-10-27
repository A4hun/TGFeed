package dev.tgfeed.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R417 播放占有权仲裁矩阵单测（resolvePlaybackOwner + ownershipDecision） */
class PlaybackOwnershipTest {

    private fun snap(
        showFeed: Boolean = true,
        viewMode: String = "feed",
        page: Int = 0,
        sub: Boolean = false,
        channel: Boolean = false,
        timeline: Boolean = false,
    ) = PlaybackOwnershipSnapshot(showFeed, viewMode, page, sub, channel, timeline)

    @Test
    fun `首页无覆盖 持有者为 FEED`() {
        assertEquals(PlaybackOwner.FEED, resolvePlaybackOwner(snap()))
    }

    @Test
    fun `时间线 overlay 在场 持有者为 TIMELINE`() {
        assertEquals(PlaybackOwner.TIMELINE, resolvePlaybackOwner(snap(timeline = true)))
    }

    @Test
    fun `发现与我的页位 时间线标记不影响`() {
        assertEquals(PlaybackOwner.DISCOVER, resolvePlaybackOwner(snap(page = 1, timeline = true)))
        assertEquals(PlaybackOwner.MINE, resolvePlaybackOwner(snap(page = 2)))
        assertEquals(PlaybackOwner.NONE, resolvePlaybackOwner(snap(page = 7)))
    }

    @Test
    fun `分屏模式 持有者为 GRID 频道主页压顶夺权`() {
        assertEquals(PlaybackOwner.GRID, resolvePlaybackOwner(snap(viewMode = "grid")))
        assertEquals(PlaybackOwner.CHANNEL_HOME, resolvePlaybackOwner(snap(viewMode = "grid", channel = true)))
    }

    @Test
    fun `二级整页与未就绪`() {
        assertEquals(PlaybackOwner.SUB_PAGE, resolvePlaybackOwner(snap(sub = true)))
        assertEquals(PlaybackOwner.SUB_PAGE, resolvePlaybackOwner(snap(viewMode = "grid", sub = true)))
        assertEquals(PlaybackOwner.NONE, resolvePlaybackOwner(snap(showFeed = false)))
    }

    @Test
    fun `同一时刻至多一个视频宿主持有`() {
        val hosts = listOf(PlaybackOwner.FEED, PlaybackOwner.GRID)
        for (owner in PlaybackOwner.entries) {
            val holders = hosts.count { ownsPlayback(owner, it) }
            assertTrue(holders <= 1)
        }
    }

    @Test
    fun `非持有者暂停视频 共存开关豁免音频`() {
        val off = ownershipDecision(PlaybackOwner.TIMELINE, PlaybackOwner.FEED, false)
        assertTrue(off.pausesVideo)
        assertTrue(off.pausesAudio)
        val on = ownershipDecision(PlaybackOwner.TIMELINE, PlaybackOwner.FEED, true)
        assertTrue(on.pausesVideo)
        assertFalse(on.pausesAudio)
    }

    @Test
    fun `持有者不暂停 页内让位则暂停`() {
        assertEquals(NavPauseDecision.NONE, ownershipDecision(PlaybackOwner.GRID, PlaybackOwner.GRID, false))
        assertTrue(ownershipDecision(PlaybackOwner.FEED, PlaybackOwner.FEED, false, localYield = true).pausesVideo)
    }

    @Test
    fun `时间线在场时 Feed 与分屏宿主都暂停`() {
        assertTrue(ownershipDecision(PlaybackOwner.TIMELINE, PlaybackOwner.FEED, false).pausesVideo)
        assertTrue(ownershipDecision(PlaybackOwner.TIMELINE, PlaybackOwner.GRID, false).pausesVideo)
    }
}
