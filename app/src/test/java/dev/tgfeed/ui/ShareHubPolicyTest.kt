package dev.tgfeed.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * R201 分享枢纽菜单态纯判定单测。
 *
 * 判定矩阵（shareHubRowState）：
 *  - Resolving：链接两行禁用 +「正在获取公开链接…」；卡片行启用、无覆盖文案；
 *  - Ready：三行全启用、无覆盖文案；
 *  - NoPublicLink：链接两行禁用 + 按媒体类型的如实口径；卡片行恒启用（卡链
 *    link=null 本就跳链接行，不受牵连）。
 * 配套：无链接提示分词（视频/图片/音频/兜底）、chooser 标题（视频沿用原文）。
 */
class ShareHubPolicyTest {

    private val resolving = ShareHubLink.Resolving
    private val ready = ShareHubLink.Ready("https://t.me/demo/42")
    private val noLink = ShareHubLink.NoPublicLink

    // ---------------- 解析中 ----------------

    @Test
    fun `解析中_复制链接禁用并提示获取中`() {
        val s = shareHubRowState(ShareHubRow.COPY_LINK, resolving, mediaType = 0)
        assertFalse(s.enabled)
        assertEquals("正在获取公开链接…", s.hint)
    }

    @Test
    fun `解析中_系统分享禁用并提示获取中`() {
        val s = shareHubRowState(ShareHubRow.SEND_APPS, resolving, mediaType = 1)
        assertFalse(s.enabled)
        assertEquals("正在获取公开链接…", s.hint)
    }

    @Test
    fun `解析中_分享卡片仍可用且无覆盖文案`() {
        val s = shareHubRowState(ShareHubRow.CARD, resolving, mediaType = 2)
        assertTrue(s.enabled)
        assertNull(s.hint)
    }

    // ---------------- 解析到手 ----------------

    @Test
    fun `解析到手_三行全启用无覆盖文案`() {
        ShareHubRow.entries.forEach { row ->
            val s = shareHubRowState(row, ready, mediaType = 0)
            assertTrue("行 $row 应启用", s.enabled)
            assertNull("行 $row 不应有覆盖文案", s.hint)
        }
    }

    @Test
    fun `Ready态携带链接原文`() {
        val link = ready as ShareHubLink.Ready
        assertEquals("https://t.me/demo/42", link.url)
    }

    // ---------------- 确定无公开链接 ----------------

    @Test
    fun `无链接_复制链接禁用_视频口径`() {
        val s = shareHubRowState(ShareHubRow.COPY_LINK, noLink, mediaType = 0)
        assertFalse(s.enabled)
        assertEquals("该视频没有公开链接", s.hint)
    }

    @Test
    fun `无链接_系统分享禁用_图片口径`() {
        val s = shareHubRowState(ShareHubRow.SEND_APPS, noLink, mediaType = 1)
        assertFalse(s.enabled)
        assertEquals("该图片没有公开链接", s.hint)
    }

    @Test
    fun `无链接_音频口径`() {
        assertEquals("该音频没有公开链接", shareHubNoLinkHint(2))
    }

    @Test
    fun `无链接_未知媒体类型兜底口径`() {
        assertEquals("该条目没有公开链接", shareHubNoLinkHint(9))
    }

    @Test
    fun `无链接_分享卡片恒可用不受牵连`() {
        val s = shareHubRowState(ShareHubRow.CARD, noLink, mediaType = 0)
        assertTrue(s.enabled)
        assertNull(s.hint)
    }

    // ---------------- 配套纯函数 ----------------

    @Test
    fun `chooser标题_视频沿用右轨直跳时代原文`() {
        assertEquals("分享视频链接", shareHubChooserTitle(0))
    }

    @Test
    fun `chooser标题_图片音频补齐同族文案`() {
        assertEquals("分享图片链接", shareHubChooserTitle(1))
        assertEquals("分享音频链接", shareHubChooserTitle(2))
    }

    @Test
    fun `chooser标题_未知媒体类型兜底`() {
        assertEquals("分享链接", shareHubChooserTitle(7))
    }

    @Test
    fun `启用判定与行态一致_全矩阵`() {
        // 三种链接态 × 三行：shareHubRowEnabled 与 shareHubRowState.enabled 恒一致
        listOf(resolving, ready, noLink).forEach { link ->
            ShareHubRow.entries.forEach { row ->
                assertEquals(
                    "链接态 $link 行 $row",
                    shareHubRowEnabled(row, link),
                    shareHubRowState(row, link, 0).enabled,
                )
            }
        }
    }
}
