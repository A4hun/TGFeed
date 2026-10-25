package dev.tgfeed.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R262b 死/僵尸频道处置策略的回归锚（DeadChannelPolicy 纯函数）：
 * 体检实锤（r262_notes.md）exhausted=1 且 items<100 共 166 个——101 个全相位清扫
 * 到底零产出的真死档标死降频探活，3 个相位未走完的提前终态嫌疑复活重爬自证，
 * 62 个真小频道（1..99 条且 mediaPass=3）与 86 个旧线性爬完成的 ≥100 条频道不动。
 */
class DeadChannelPolicyTest {

    // ---------------- shouldMarkDead ----------------

    @Test
    fun `标死_全相位到底零产出非采样的完成态`() {
        assertTrue(DeadChannelPolicy.shouldMarkDead(exhausted = true, mediaPass = 3, sampled = false, itemCount = 0))
        assertTrue(DeadChannelPolicy.shouldMarkDead(exhausted = true, mediaPass = 4, sampled = false, itemCount = 0))
    }

    @Test
    fun `不标死_有产出的完成态`() {
        assertFalse(DeadChannelPolicy.shouldMarkDead(exhausted = true, mediaPass = 3, sampled = false, itemCount = 1))
        assertFalse(DeadChannelPolicy.shouldMarkDead(exhausted = true, mediaPass = 3, sampled = false, itemCount = 50))
    }

    @Test
    fun `不标死_相位未走完的零产出（证据不足走复活）`() {
        assertFalse(DeadChannelPolicy.shouldMarkDead(exhausted = true, mediaPass = -1, sampled = false, itemCount = 0))
        assertFalse(DeadChannelPolicy.shouldMarkDead(exhausted = true, mediaPass = 0, sampled = false, itemCount = 0))
        assertFalse(DeadChannelPolicy.shouldMarkDead(exhausted = true, mediaPass = 2, sampled = false, itemCount = 0))
    }

    @Test
    fun `不标死_采样频道零产出（部分覆盖不算死档证据）`() {
        assertFalse(DeadChannelPolicy.shouldMarkDead(exhausted = true, mediaPass = 3, sampled = true, itemCount = 0))
    }

    @Test
    fun `不标死_未完成态`() {
        assertFalse(DeadChannelPolicy.shouldMarkDead(exhausted = false, mediaPass = 3, sampled = false, itemCount = 0))
    }

    // ---------------- shouldReviveIncomplete ----------------

    @Test
    fun `复活_相位未走完且低产的完成态`() {
        assertTrue(DeadChannelPolicy.shouldReviveIncomplete(exhausted = true, mediaPass = -1, itemCount = 0))
        assertTrue(DeadChannelPolicy.shouldReviveIncomplete(exhausted = true, mediaPass = 0, itemCount = 0))
        assertTrue(DeadChannelPolicy.shouldReviveIncomplete(exhausted = true, mediaPass = 2, itemCount = 99))
    }

    @Test
    fun `不复活_相位已走完（真死档或真小频道各归标死与现状）`() {
        assertFalse(DeadChannelPolicy.shouldReviveIncomplete(exhausted = true, mediaPass = 3, itemCount = 0))
        assertFalse(DeadChannelPolicy.shouldReviveIncomplete(exhausted = true, mediaPass = 3, itemCount = 50))
    }

    @Test
    fun `不复活_百条以上相位未走完（旧线性爬合法完成态）`() {
        assertFalse(DeadChannelPolicy.shouldReviveIncomplete(exhausted = true, mediaPass = -1, itemCount = 100))
        assertFalse(DeadChannelPolicy.shouldReviveIncomplete(exhausted = true, mediaPass = 1, itemCount = 5000))
    }

    @Test
    fun `不复活_未完成态`() {
        assertFalse(DeadChannelPolicy.shouldReviveIncomplete(exhausted = false, mediaPass = 0, itemCount = 0))
    }

    // ---------------- headRefreshIntervalMs ----------------

    @Test
    fun `死档头刷降频为7天探活`() {
        assertEquals(
            DeadChannelPolicy.DEAD_PROBE_INTERVAL_MS,
            DeadChannelPolicy.headRefreshIntervalMs(dead = true, baseIntervalMs = 6L * 60 * 60_000L),
        )
        assertEquals(7L * 24 * 60 * 60_000L, DeadChannelPolicy.DEAD_PROBE_INTERVAL_MS)
    }

    @Test
    fun `活档头刷间隔原样透传（含分档差异化）`() {
        assertEquals(6L * 60 * 60_000L, DeadChannelPolicy.headRefreshIntervalMs(false, 6L * 60 * 60_000L))
        assertEquals(1L * 60 * 60_000L, DeadChannelPolicy.headRefreshIntervalMs(false, 1L * 60 * 60_000L))
    }

    // ---------------- shouldReviveDeadOnFresh ----------------

    @Test
    fun `死档复活_采到新鲜条目即清死档`() {
        assertTrue(DeadChannelPolicy.shouldReviveDeadOnFresh(1))
        assertTrue(DeadChannelPolicy.shouldReviveDeadOnFresh(100))
        assertFalse(DeadChannelPolicy.shouldReviveDeadOnFresh(0))
    }
}
