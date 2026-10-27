package dev.tgfeed.core.sync

import dev.tgfeed.core.sync.BackgroundSyncPolicy.Skip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSyncPolicyTest {

    @Test
    fun normalizeKeepsValidOptions() {
        assertEquals(15, BackgroundSyncPolicy.normalizeMinutes(15))
        assertEquals(30, BackgroundSyncPolicy.normalizeMinutes(30))
        assertEquals(60, BackgroundSyncPolicy.normalizeMinutes(60))
        assertEquals(0, BackgroundSyncPolicy.normalizeMinutes(0))
    }

    @Test
    fun normalizeNegativeIsOffAndIllegalIsDefault() {
        assertEquals(0, BackgroundSyncPolicy.normalizeMinutes(-5))
        assertEquals(30, BackgroundSyncPolicy.normalizeMinutes(7))
        assertEquals(30, BackgroundSyncPolicy.normalizeMinutes(45))
    }

    @Test
    fun scheduleOnlyWhenEnabled() {
        assertFalse(BackgroundSyncPolicy.shouldSchedule(0))
        assertTrue(BackgroundSyncPolicy.shouldSchedule(15))
        assertTrue(BackgroundSyncPolicy.shouldSchedule(99))
    }

    @Test
    fun preSkipPriorityOrder() {
        assertEquals(Skip.DISABLED, BackgroundSyncPolicy.preSkip(0, true, false, false, true))
        assertEquals(Skip.FOREGROUND, BackgroundSyncPolicy.preSkip(30, true, false, false, true))
        assertEquals(Skip.NO_REAL_GATEWAY, BackgroundSyncPolicy.preSkip(30, false, false, false, true))
        assertEquals(Skip.NOT_AUTHORIZED, BackgroundSyncPolicy.preSkip(30, false, true, false, true))
        assertEquals(Skip.BUSY, BackgroundSyncPolicy.preSkip(30, false, true, true, true))
        assertEquals(Skip.NONE, BackgroundSyncPolicy.preSkip(30, false, true, true, false))
    }

    @Test
    fun meteredBlocksMedia() {
        assertFalse(BackgroundSyncPolicy.allowMediaDownload(true))
        assertTrue(BackgroundSyncPolicy.allowMediaDownload(false))
    }

    @Test
    fun countNewAgainstOldSnapshot() {
        assertEquals(2, BackgroundSyncPolicy.countNew(setOf("a"), listOf("a", "b", "c")))
        assertEquals(0, BackgroundSyncPolicy.countNew(setOf("a", "b"), listOf("a", "b")))
        assertEquals(1, BackgroundSyncPolicy.countNew(emptySet(), listOf("x")))
    }

    @Test
    fun timeoutIsEightMinutes() {
        assertEquals(480_000L, BackgroundSyncPolicy.RUN_TIMEOUT_MS)
    }
}
