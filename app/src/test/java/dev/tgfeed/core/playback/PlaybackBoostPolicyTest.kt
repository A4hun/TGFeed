package dev.tgfeed.core.playback

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R190 倍速水位策略纯函数行为单测：
 * 长按 2x「卡顿要加载一下」的播放器侧根因=media3 1.4.1 的 maxBufferUs 停止上限
 * 是媒体时间口径——2x 下 50s 媒体垫只值 25s 墙钟（1x 设计点的一半）。
 * [shouldContinueLoadingBoostAware] 的使命=倍速档把时间目标×速度（封顶 2×），
 * 让墙钟垫回到 1x 设计点；非倍速档一律交回 DefaultLoadControl（null=委托）。
 */
class PlaybackBoostPolicyTest {

    @After
    fun tearDown() {
        // 总线是进程级单例：测试间复位，防跨用例污染
        PlaybackSpeedBus.report(1f)
    }

    // ---- isBoostSpeed：阈值口径 ----

    @Test
    fun `常速与噪声值不进倍速档`() {
        assertFalse(PlaybackSpeedBus.isBoostSpeed(1f))
        // 浮点 panel 值/重置链的 1.0000001 级噪声不该触发水位抬升
        assertFalse(PlaybackSpeedBus.isBoostSpeed(1.0000001f))
        assertFalse(PlaybackSpeedBus.isBoostSpeed(0.5f))
        assertFalse(PlaybackSpeedBus.isBoostSpeed(-1f))
        // NaN 比较为 false：天然不误报
        assertFalse(PlaybackSpeedBus.isBoostSpeed(Float.NaN))
    }

    @Test
    fun `面板档与长按档都进倍速档`() {
        assertTrue(PlaybackSpeedBus.isBoostSpeed(1.5f))
        assertTrue(PlaybackSpeedBus.isBoostSpeed(2f))
        assertTrue(PlaybackSpeedBus.isBoostSpeed(Float.POSITIVE_INFINITY))
    }

    // ---- report：总线取值口径 ----

    @Test
    fun `总线写入钳制在1到8`() {
        PlaybackSpeedBus.report(2f)
        assertEquals(2f, PlaybackSpeedBus.speed, 0f)
        PlaybackSpeedBus.report(0.5f) // 低于 1（如异常读数）钳回 1
        assertEquals(1f, PlaybackSpeedBus.speed, 0f)
        PlaybackSpeedBus.report(100f) // 高于 8 钳到 8（速度上限防御）
        assertEquals(8f, PlaybackSpeedBus.speed, 0f)
        PlaybackSpeedBus.report(Float.NaN) // 非有限值回落 1
        assertEquals(1f, PlaybackSpeedBus.speed, 0f)
    }

    // ---- shouldContinueLoadingBoostAware：水位决策 ----

    private val maxBufferUs = 50_000_000L // R145 口径 50s（µs）

    @Test
    fun `常速档一律委托_返回null保持1x零变化`() {
        assertNull(shouldContinueLoadingBoostAware(1f, 0L, maxBufferUs))
        assertNull(shouldContinueLoadingBoostAware(1f, maxBufferUs * 10, maxBufferUs))
        assertNull(shouldContinueLoadingBoostAware(Float.NaN, 0L, maxBufferUs))
    }

    @Test
    fun `2x档目标翻倍_墙钟垫回到1x设计点`() {
        // 2x：目标 100s 媒体（= 50s 墙钟）。垫未满继续加载
        assertTrue(shouldContinueLoadingBoostAware(2f, 99_999_999L, maxBufferUs)!!)
        // 达标即停
        assertFalse(shouldContinueLoadingBoostAware(2f, 100_000_000L, maxBufferUs)!!)
        // 原本的 50s 上限在 2x 下不再是停止线（这正是要修的半垫问题）
        assertTrue(shouldContinueLoadingBoostAware(2f, 50_000_000L, maxBufferUs)!!)
    }

    @Test
    fun `1x5档目标乘1x5_连续无悬崖`() {
        // 1.5x：目标 75s 媒体
        assertTrue(shouldContinueLoadingBoostAware(1.5f, 74_999_999L, maxBufferUs)!!)
        assertFalse(shouldContinueLoadingBoostAware(1.5f, 75_000_000L, maxBufferUs)!!)
    }

    @Test
    fun `超档速度封顶2倍_防内存垫拉爆`() {
        // 假想 4x 档：目标仍钳在 2×（100s），不给无界垫
        assertFalse(shouldContinueLoadingBoostAware(4f, 100_000_000L, maxBufferUs)!!)
        assertTrue(shouldContinueLoadingBoostAware(4f, 99_999_999L, maxBufferUs)!!)
    }
}
