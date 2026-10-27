package dev.tgfeed.uitest

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.unit.Density
import dev.tgfeed.core.td.VideoItem

/**
 * R422 UI 测试基座：
 * - 假数据工厂（纯合成，零真实内容）；
 * - 只出计数/几何/布尔的断言工具（禁止读取或打印界面文字）；
 * - 等待空闲。
 */
internal object UiTestKit {

    /** 合成假视频条目：标题/正文恒为占位，不含任何真实内容 */
    fun fakeVideo(
        index: Int,
        width: Int = 1280,
        height: Int = 720,
        dateSec: Int = 1_700_000_000 + index * 60,
        chatId: Long = 1000L + index,
    ): VideoItem = VideoItem(
        key = "fake_${index}",
        chatId = chatId,
        messageId = index.toLong() + 1,
        channelTitle = "c${index}",
        caption = "",
        durationSec = 10,
        width = width,
        height = height,
        fileId = index + 1,
        sizeBytes = 1L,
        playUri = null,
        dateEpochSec = dateSec,
    )

    fun ComposeContentTestRule.countTag(tag: String, useUnmergedTree: Boolean = true): Int {
        waitForIdle()
        return onAllNodesWithTag(tag, useUnmergedTree).fetchSemanticsNodes().size
    }

    fun ComposeContentTestRule.existsTag(tag: String): Boolean = countTag(tag) > 0

    /** 节点在根中的像素矩形（只取几何，不取文字） */
    fun ComposeContentTestRule.pxBounds(tag: String): Rect {
        waitForIdle()
        val dp = onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()
        val d: Density = this.density
        return with(d) { Rect(dp.left.toPx(), dp.top.toPx(), dp.right.toPx(), dp.bottom.toPx()) }
    }
}
