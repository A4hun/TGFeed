package dev.tgfeed.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [navPauseDecision]（R180 App 内导航播控策略）矩阵单测：
 * 页面态（feed 在台前/不在）× 共存开关（默认关/开）→ 暂停目标（视频/音频）。
 * 口径背景（r180_notes 实测定性）：需求①=切页/进全屏子页面在播内容暂停（不清状态
 * 回页可续播）；需求②关（默认）=互斥+切换暂停无例外；开=音频豁免切换暂停
 * （背景听模式：切到别的页面音频继续，视频照常暂停）。
 * 单一事实源纪律：接线方（FeedScreen 导航闸）与本测试同源引用本纯函数，
 * 矩阵演进改一处、测一处。
 */
class NavPlaybackPolicyTest {

    @Test
    fun `feed 在台前 → 什么都不暂停（任意开关）`() {
        // 页面自己在看/在听，导航闸不介入（页面内翻页归 isActive 失焦暂停，互斥归系统焦点）
        val off = navPauseDecision(feedPageActive = true, allowAudioVideoSimultaneous = false)
        val on = navPauseDecision(feedPageActive = true, allowAudioVideoSimultaneous = true)
        assertTrue(off.pausedMedia.isEmpty())
        assertTrue(on.pausedMedia.isEmpty())
        assertFalse(off.pausesVideo)
        assertFalse(off.pausesAudio)
        assertFalse(on.pausesVideo)
        assertFalse(on.pausesAudio)
    }

    @Test
    fun `feed 不在台前且共存关（默认）→ 音视频都暂停（切换暂停无例外）`() {
        val d = navPauseDecision(feedPageActive = false, allowAudioVideoSimultaneous = false)
        assertTrue(d.pausesVideo)
        assertTrue(d.pausesAudio)
        assertEquals(setOf(NavPlaybackMedium.VIDEO, NavPlaybackMedium.AUDIO), d.pausedMedia)
    }

    @Test
    fun `feed 不在台前且共存开 → 视频暂停、音频豁免（背景听模式）`() {
        val d = navPauseDecision(feedPageActive = false, allowAudioVideoSimultaneous = true)
        assertTrue(d.pausesVideo)
        assertFalse(d.pausesAudio)
        assertEquals(setOf(NavPlaybackMedium.VIDEO), d.pausedMedia)
    }

    @Test
    fun `矩阵全枚举 2x2 无遗漏（页面态 × 开关）`() {
        // 2 页面态 × 2 开关 = 4 格全枚举，防未来加维度漏格
        val all = listOf(
            navPauseDecision(true, false),
            navPauseDecision(true, true),
            navPauseDecision(false, false),
            navPauseDecision(false, true),
        )
        // 台前两格必空、离台两格必含视频暂停（视频在两种开关下都照常暂停——需求原文）
        assertTrue(all[0].pausedMedia.isEmpty())
        assertTrue(all[1].pausedMedia.isEmpty())
        assertTrue(all[2].pausesVideo)
        assertTrue(all[3].pausesVideo)
        // 音频只受开关影响（离台时）：关=停、开=继续
        assertTrue(all[2].pausesAudio)
        assertFalse(all[3].pausesAudio)
    }

    @Test
    fun `NONE 语义常量与空决策等价（接线方用字面量比较也成立）`() {
        assertEquals(NavPauseDecision.NONE, navPauseDecision(true, false))
        assertEquals(NavPauseDecision.NONE, navPauseDecision(true, true))
    }
}
