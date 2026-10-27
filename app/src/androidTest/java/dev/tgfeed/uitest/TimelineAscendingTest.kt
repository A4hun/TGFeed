package dev.tgfeed.uitest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.tgfeed.core.feed.PursueViewportAction
import dev.tgfeed.core.feed.pursueTimelineMerge
import dev.tgfeed.core.feed.pursueViewportAction
import dev.tgfeed.core.td.VideoItem
import dev.tgfeed.ui.UiTestTags
import dev.tgfeed.uitest.UiTestKit.existsTag
import dev.tgfeed.uitest.UiTestKit.fakeVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R418b 时间线升序回归闸：入流走生产同一合并纯函数 pursueTimelineMerge（升序），
 * 视口走生产同一判定 pursueViewportAction（在底+尾增长=跟随）。
 * 节点 tag 带序号（timeline_card_i），i 越大越新。
 */
@RunWith(AndroidJUnit4::class)
class TimelineAscendingTest {
    @get:Rule
    val rule = createComposeRule()

    private val stream = mutableStateOf<List<VideoItem>>(emptyList())
    private var atBottomObserved = false

    private fun setTimeline() {
        rule.setContent {
            val listState = rememberLazyListState()
            val items = stream.value
            val prev = remember { arrayOfNulls<String>(2) }
            val atBottomFlag = remember { mutableStateOf(true) }
            // 持续跟踪「在底」状态（布局稳定后读，供下一次流发布判定用）
            LaunchedEffect(listState) {
                snapshotFlow { !listState.canScrollForward }.collect { atBottomFlag.value = it }
            }
            LaunchedEffect(items) {
                when (pursueViewportAction(atBottomFlag.value, prev[0], items.firstOrNull()?.key, prev[1], items.lastOrNull()?.key)) {
                    PursueViewportAction.FOLLOW_BOTTOM -> listState.scrollToItem(items.lastIndex)
                    PursueViewportAction.NONE -> if (prev[1] == null && items.isNotEmpty()) listState.scrollToItem(items.lastIndex)
                    PursueViewportAction.HOLD_ANCHOR -> Unit
                }
                prev[0] = items.firstOrNull()?.key
                prev[1] = items.lastOrNull()?.key
            }
            LaunchedEffect(listState.canScrollForward) { atBottomObserved = !listState.canScrollForward }
            Box(Modifier.size(360.dp, 600.dp)) {
                LazyColumn(state = listState, modifier = Modifier.testTag(UiTestTags.TIMELINE_LIST)) {
                    itemsIndexed(items, key = { _, it -> it.key }) { i, _ ->
                        Box(Modifier.fillMaxWidth().height(120.dp).testTag(UiTestTags.timelineCard(i)))
                    }
                }
            }
        }
    }

    /** 乱序喂入 → 合并后升序；视口落底，末节点=最新 */
    @Test
    fun lastNodeIsNewest() {
        val raw = listOf(5, 1, 9, 3, 7, 0, 8, 2, 6, 4).map { fakeVideo(it) }
        val merged = pursueTimelineMerge(emptyList(), raw, cutoffSec = 0)
        assertEquals(10, merged.size)
        for (i in 1 until merged.size) assertTrue(merged[i - 1].dateEpochSec <= merged[i].dateEpochSec)
        assertEquals("fake_9", merged.last().key)
        stream.value = merged
        setTimeline()
        rule.waitForIdle()
        assertTrue("末节点可见", rule.existsTag(UiTestTags.timelineCard(merged.lastIndex)))
        assertFalse("首节点已滚出", rule.existsTag(UiTestTags.timelineCard(0)))
        assertTrue("在底", atBottomObserved)
    }

    /** 在底部时追加新条目 → 出现在底部并自动跟随 */
    @Test
    fun appendAtBottomFollows() {
        stream.value = pursueTimelineMerge(emptyList(), (0 until 10).map { fakeVideo(it) }, cutoffSec = 0)
        setTimeline()
        rule.waitForIdle()
        rule.runOnIdle {
            stream.value = pursueTimelineMerge(stream.value, listOf(fakeVideo(10), fakeVideo(11)), cutoffSec = 0)
        }
        rule.waitForIdle()
        val s = stream.value
        assertEquals(12, s.size)
        assertEquals("fake_11", s.last().key)
        assertTrue("新条目在底部可见", rule.existsTag(UiTestTags.timelineCard(11)))
        assertTrue("跟随后仍在底", atBottomObserved)
    }

    /** 不在底部时追加 → 不跳（保位），新条目不自动进入视口 */
    @Test
    fun appendWhileReadingHistoryHolds() {
        stream.value = pursueTimelineMerge(emptyList(), (0 until 10).map { fakeVideo(it) }, cutoffSec = 0)
        setTimeline()
        rule.onNodeWithTag(UiTestTags.TIMELINE_LIST).performScrollToIndex(0)
        rule.waitForIdle()
        rule.runOnIdle {
            stream.value = pursueTimelineMerge(stream.value, listOf(fakeVideo(10)), cutoffSec = 0)
        }
        rule.waitForIdle()
        assertTrue("仍看历史", rule.existsTag(UiTestTags.timelineCard(0)))
        assertFalse("新条目未抢视口", rule.existsTag(UiTestTags.timelineCard(10)))
    }
}
