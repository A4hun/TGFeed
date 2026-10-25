package dev.tgfeed.core.prefs

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppPrefs.effectiveBackgroundPause]（R158 退后台"实际按暂停处理"判定）的行为单测：
 * 0 档兜底矩阵 + 1/2 档直判 + audioBackground 承载开关的组合全枚举。
 * 口径背景：R155 体检实锤 backgroundAction 默认 0（继续播放）且 audioBackground 默认 false
 * 时 onStop 什么都不做——退后台视频在黑屏里继续解码+下载+出声（CPU 12~124%、220KB/s）。
 * R158 起 0 档无 FGS 承载兜底按暂停（改默认行为，回前台同 1 档链路自动续播）。
 */
class AppPrefsBackgroundPauseTest {

    /** 造一个指定 (backgroundAction, audioBackground) 的 AppPrefs（SharedPreferences 桩） */
    private fun prefs(action: Int, audioBg: Boolean): AppPrefs {
        val sp = mockk<SharedPreferences>(relaxed = true)
        every { sp.getInt("background_action", 0) } returns action
        every { sp.getBoolean("audio_background", false) } returns audioBg
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns sp
        every { context.applicationContext } returns context
        return AppPrefs(context)
    }

    @Test
    fun `默认档 0 且未开后台音频 → 兜底按暂停（R158 新默认）`() {
        assertTrue(prefs(0, false).effectiveBackgroundPause)
    }

    @Test
    fun `默认档 0 但开了后台音频（FGS 承载）→ 维持续播不判暂停`() {
        assertFalse(prefs(0, true).effectiveBackgroundPause)
    }

    @Test
    fun `暂停档 1 → 恒按暂停（与后台音频开关无关）`() {
        assertTrue(prefs(1, false).effectiveBackgroundPause)
        assertTrue(prefs(1, true).effectiveBackgroundPause)
    }

    @Test
    fun `弹悬浮窗档 2 → 不判暂停（悬浮窗用户可见，属续播场景）`() {
        assertFalse(prefs(2, false).effectiveBackgroundPause)
        assertFalse(prefs(2, true).effectiveBackgroundPause)
    }
}
