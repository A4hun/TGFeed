package dev.tgfeed.core.prefs

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R180「允许音频与视频同时播放」键的行为单测（[AppPrefs.allowAudioVideoSimultaneous]）：
 * 默认关（互斥+切换暂停无例外是产品默认态）；读写直通 SharedPreferences。
 * 口径背景见 r180_notes：需求②默认关——互斥语义由系统音频焦点承载（实测实锤），
 * 开档才进入共存+背景听模式。
 */
class AppPrefsSimultaneousToggleTest {

    /** 造一个指定共存开关的 AppPrefs（SharedPreferences 桩） */
    private fun prefs(simul: Boolean): AppPrefs {
        val sp = mockk<SharedPreferences>(relaxed = true)
        every { sp.getBoolean("allow_audio_video_simultaneous", false) } returns simul
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns sp
        every { context.applicationContext } returns context
        return AppPrefs(context)
    }

    @Test
    fun `默认关（互斥——切换暂停无例外是默认态）`() {
        assertFalse(prefs(simul = false).allowAudioVideoSimultaneous)
    }

    @Test
    fun `开启后读回开（背景听模式生效）`() {
        assertTrue(prefs(simul = true).allowAudioVideoSimultaneous)
    }
}
