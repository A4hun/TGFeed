package dev.tgfeed.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R225 横屏 chrome 可伸展政策单测（纯函数全枚举）：
 * - 竖屏两档恒 null（零变化红线——调用方走旧链）；
 * - 横屏按屏高分档：A（≥680）/B（≥636）/null（矮横屏），48dp 触区铁律两档都保；
 * - 阈值边界（679/680、635/636）与防御兜底（屏高非正）。
 *
 * 夹具：16384（1219×812dp 落 A）、16416（1137×640dp 落 B）、
 * 华为 1600 平板（1024×640dp 落 B）、手机横屏（780×360dp → null）、
 * 用户 3:2 平板（1355×903dp 落 A）。
 */
class LandscapeChromePolicyTest {

    private fun assertDp(expected: Dp, actual: Dp) {
        assertEquals("期望 $expected dp，实得 $actual", expected.value, actual.value, 0.01f)
    }

    // ---------------- ① 竖屏零变化红线 ----------------

    @Test
    fun `竖屏两档_任何屏高恒不触发`() {
        assertNull(landscapeFeedChromeOrNull(WindowSize.PhonePortrait, 800))
        assertNull(landscapeFeedChromeOrNull(WindowSize.PhonePortrait, 1600))
        assertNull(landscapeFeedChromeOrNull(WindowSize.TabletPortrait, 903))
        assertNull(landscapeFeedChromeOrNull(WindowSize.TabletPortrait, 1280))
    }

    // ---------------- ② 横屏分档与度量 ----------------

    @Test
    fun `高横屏A档_16384的812dp_图标40触区48行距26头像56限宽560`() {
        val chrome = landscapeFeedChromeOrNull(WindowSize.TabletLandscape, 812)
        assertNotNull(chrome)
        assertDp(40.dp, chrome!!.railIconSize)
        assertDp(48.dp, chrome.railTouchMin)
        assertDp(26.dp, chrome.railGap)
        assertDp(56.dp, chrome.railAvatarSize)
        assertDp(SheetMaxWidth, chrome.infoMaxWidth)
    }

    @Test
    fun `用户平板A档_903dp_与16384同档`() {
        val a812 = landscapeFeedChromeOrNull(WindowSize.TabletLandscape, 812)!!
        val a903 = landscapeFeedChromeOrNull(WindowSize.TabletLandscape, 903)!!
        assertDp(a812.railIconSize, a903.railIconSize)
        assertDp(a812.railTouchMin, a903.railTouchMin)
    }

    @Test
    fun `中横屏B档_16416的640dp_图标36触区48行距20头像48限宽560`() {
        // 华为 1600 平板横屏（1024×640dp，R159 用户基线同高）同档
        val chrome = landscapeFeedChromeOrNull(WindowSize.TabletLandscape, 640)
        assertNotNull(chrome)
        assertDp(36.dp, chrome!!.railIconSize)
        assertDp(48.dp, chrome.railTouchMin)
        assertDp(20.dp, chrome.railGap)
        assertDp(48.dp, chrome.railAvatarSize)
        assertDp(SheetMaxWidth, chrome.infoMaxWidth)
    }

    @Test
    fun `手机横屏360dp_不触发`() {
        assertNull(landscapeFeedChromeOrNull(WindowSize.PhoneLandscape, 360))
        assertNull(landscapeFeedChromeOrNull(WindowSize.PhoneLandscape, 430))
        assertNull(landscapeFeedChromeOrNull(WindowSize.PhoneLandscape, 560))
    }

    // ---------------- ③ 阈值边界 ----------------

    @Test
    fun `A档边界_679为B_680为A`() {
        assertEquals(
            "679dp 应落 B 档（B 阈值 636）",
            36.dp.value,
            landscapeFeedChromeOrNull(WindowSize.TabletLandscape, 679)!!.railIconSize.value,
            0.01f,
        )
        assertEquals(
            "680dp 应落 A 档",
            40.dp.value,
            landscapeFeedChromeOrNull(WindowSize.TabletLandscape, 680)!!.railIconSize.value,
            0.01f,
        )
    }

    @Test
    fun `B档边界_635不触发_636为B`() {
        assertNull("635dp 不触发（B 阈值 636）", landscapeFeedChromeOrNull(WindowSize.TabletLandscape, 635))
        assertNotNull(landscapeFeedChromeOrNull(WindowSize.TabletLandscape, 636))
    }

    // ---------------- ④ 铁律与预算自检 ----------------

    @Test
    fun `触区铁律_AB两档触区都达标48dp`() {
        val a = landscapeFeedChromeOrNull(WindowSize.TabletLandscape, 812)!!
        val b = landscapeFeedChromeOrNull(WindowSize.TabletLandscape, 640)!!
        assertTrue("A 档触区 ≥48dp", a.railTouchMin >= 48.dp)
        assertTrue("B 档触区 ≥48dp", b.railTouchMin >= 48.dp)
        assertTrue("A 档图标不小于 B 档", a.railIconSize >= b.railIconSize)
        assertTrue("A 档行距不小于 B 档", a.railGap >= b.railGap)
    }

    @Test
    fun `列高预算_AB档总高加212dp不超各自屏高`() {
        // 右轨总高 = 头像 + 26（头像行距） + 5×(触区+行距)；底距 102 + 顶部预留 110 = 212
        val a = landscapeFeedChromeOrNull(WindowSize.TabletLandscape, 680)!!
        val railA = a.railAvatarSize.value + 26f + 5f * (a.railTouchMin.value + a.railGap.value)
        assertTrue("A 档（680dp）列总高 $railA + 212 ≤ 680", railA + 212f <= 680f)

        val b = landscapeFeedChromeOrNull(WindowSize.TabletLandscape, 636)!!
        val railB = b.railAvatarSize.value + 26f + 5f * (b.railTouchMin.value + b.railGap.value)
        assertTrue("B 档（636dp）列总高 $railB + 212 ≤ 636", railB + 212f <= 636f)
    }

    // ---------------- ⑤ 防御兜底 ----------------

    @Test
    fun `屏高非正_防御性不触发`() {
        assertNull(landscapeFeedChromeOrNull(WindowSize.TabletLandscape, 0))
        assertNull(landscapeFeedChromeOrNull(WindowSize.TabletLandscape, -1))
        assertNull(landscapeFeedChromeOrNull(WindowSize.PhoneLandscape, 0))
    }
}
