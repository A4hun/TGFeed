package dev.tgfeed.uitest

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.tgfeed.core.playback.PlaybackOwner
import dev.tgfeed.core.playback.PlaybackOwnershipSnapshot
import dev.tgfeed.core.playback.PlayerPool
import dev.tgfeed.core.playback.ownsPlayback
import dev.tgfeed.core.playback.resolvePlaybackOwner
import dev.tgfeed.ui.PlaybackOwnershipGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R417 播放占有权回归闸（instrumented 集成）：真 PlayerPool + 真 PlaybackOwnershipGate，
 * 无媒体源（不联网），以 playWhenReady（池的播放意图口径）作假播放器状态。
 * 模拟 Feed → 分屏 → 频道主页 → 回 Feed 的切页序列。
 */
@RunWith(AndroidJUnit4::class)
class PlaybackOwnershipTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var feedPool: PlayerPool
    private lateinit var gridPool: PlayerPool

    private fun snap(viewMode: String = "feed", channelHome: Boolean = false, page: Int = 0) =
        PlaybackOwnershipSnapshot(
            showFeed = true, viewMode = viewMode, topLevelPage = page,
            subPageOpen = false, channelHomeOpen = channelHome, timelineOn = false,
        )

    @After
    fun tearDown() {
        rule.runOnIdle {
            if (::feedPool.isInitialized) feedPool.releaseAll()
            if (::gridPool.isInitialized) gridPool.releaseAll()
        }
    }

    @Test
    fun nonHolderPagesArePaused() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var feedPlayer: ExoPlayer
        lateinit var gridPlayer: ExoPlayer
        rule.runOnIdle {
            feedPool = PlayerPool(ctx)
            gridPool = PlayerPool(ctx, lowMemoryMode = true)
            feedPlayer = feedPool.acquire("feed_a")
            gridPlayer = gridPool.acquire("grid_a")
        }
        val holder = mutableStateOf(resolvePlaybackOwner(snap()))
        rule.setContent {
            PlaybackOwnershipGate(feedPool, PlaybackOwner.FEED, holder.value, allowAudioVideoSimultaneous = false)
            PlaybackOwnershipGate(gridPool, PlaybackOwner.GRID, holder.value, allowAudioVideoSimultaneous = false)
        }
        // Feed 持有：Feed 播，分屏池落闸
        rule.runOnIdle { feedPlayer.playWhenReady = true }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(PlaybackOwner.FEED, holder.value)
            assertTrue(feedPlayer.playWhenReady)
            assertTrue(gridPool.navGate)
            assertFalse(feedPool.navGate)
        }
        // 切到分屏：Feed 暂停，分屏抬闸可播
        rule.runOnIdle { holder.value = resolvePlaybackOwner(snap(viewMode = "grid")) }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(PlaybackOwner.GRID, holder.value)
            assertFalse("非持有 Feed 停", feedPlayer.playWhenReady)
            assertFalse(feedPlayer.isPlaying)
            assertTrue(feedPool.navGate)
            assertFalse(gridPool.navGate)
            gridPlayer.playWhenReady = true
        }
        // 频道主页压在分屏上：分屏也停
        rule.runOnIdle { holder.value = resolvePlaybackOwner(snap(viewMode = "grid", channelHome = true)) }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(PlaybackOwner.CHANNEL_HOME, holder.value)
            assertFalse("非持有分屏停", gridPlayer.playWhenReady)
            assertFalse(gridPlayer.isPlaying)
            assertFalse(feedPlayer.playWhenReady)
        }
        // 迟到开播拦截：闸期内 playWhenReady=true 被打回
        rule.runOnIdle { feedPlayer.playWhenReady = true }
        rule.waitForIdle()
        rule.runOnIdle { assertFalse("迟到开播被拦", feedPlayer.playWhenReady) }
        // 回 Feed：Feed 抬闸，分屏闸落
        rule.runOnIdle { holder.value = resolvePlaybackOwner(snap()) }
        rule.waitForIdle()
        rule.runOnIdle {
            assertFalse(feedPool.navGate)
            assertTrue(gridPool.navGate)
            assertFalse(gridPlayer.playWhenReady)
        }
    }

    @Test
    fun ownershipMatrix() {
        assertEquals(PlaybackOwner.DISCOVER, resolvePlaybackOwner(snap(page = 1)))
        assertEquals(PlaybackOwner.MINE, resolvePlaybackOwner(snap(page = 2)))
        assertTrue(ownsPlayback(PlaybackOwner.FEED, PlaybackOwner.FEED))
        assertFalse(ownsPlayback(PlaybackOwner.FEED, PlaybackOwner.FEED, localYield = true))
        assertFalse(ownsPlayback(PlaybackOwner.GRID, PlaybackOwner.FEED))
    }
}
