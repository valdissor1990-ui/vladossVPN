package com.vpnproxy.smartconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The monitoring loop must re-read the screen-dependent interval often enough that unlocking
 * the phone does not leave the tunnel unchecked for the whole screen-off interval.
 */
class AutoSwitcherMonitoringTest {

    @Test
    fun slicesLongIntervalSoScreenOnIsSeenQuickly() {
        val screenOffInterval = AutoSwitcherConfig.healthCheckIntervalMs *
            AutoSwitcherConfig.screenOffIntervalMultiplier
        val first = AutoSwitcher.monitoringSliceMs(screenOffInterval, elapsedMs = 0L)
        assertEquals(AutoSwitcher.MAX_MONITORING_SLICE_MS, first)
    }

    @Test
    fun sliceSequenceReachesTheScreenOffIntervalWithoutExtraChecks() {
        // Emulates the real loop: 20 minutes of screen-off interval at 60 s per slice.
        val interval = 20 * 60_000L
        var elapsed = 0L
        var slices = 0
        while (elapsed < interval) {
            val slice = AutoSwitcher.monitoringSliceMs(interval, elapsed)
            assertTrue("slice must make progress", slice > 0L)
            elapsed += slice
            slices++
        }
        assertEquals(20, slices)
        assertEquals(interval, elapsed)
    }

    @Test
    fun shortenedIntervalAfterUnlockIsReachedOnNextIteration() {
        // Screen off interval was 6 min, 5 min already slept. Unlock halves it to 3 min:
        // the next iteration must check immediately instead of sleeping another full slice.
        assertEquals(0L, AutoSwitcher.monitoringSliceMs(intervalMs = 180_000L, elapsedMs = 300_000L))
        // Partially elapsed: sleep exactly the remainder.
        assertEquals(30_000L, AutoSwitcher.monitoringSliceMs(intervalMs = 180_000L, elapsedMs = 150_000L))
    }

    @Test
    fun zeroSliceWhenIntervalAlreadyElapsed() {
        // Bounded loops on millis, not on wall clock: unlocking shortens the interval and the
        // next iteration must run the check immediately.
        assertEquals(0L, AutoSwitcher.monitoringSliceMs(intervalMs = 180_000L, elapsedMs = 180_000L))
        assertEquals(0L, AutoSwitcher.monitoringSliceMs(intervalMs = 180_000L, elapsedMs = 240_000L))
    }

    @Test
    fun neverSleepsPastTheInterval() {
        assertEquals(5_000L, AutoSwitcher.monitoringSliceMs(intervalMs = 30_000L, elapsedMs = 25_000L))
    }

    @Test
    fun rejectsNonPositiveInputs() {
        assertThrows(IllegalArgumentException::class.java) {
            AutoSwitcher.monitoringSliceMs(intervalMs = 0L, elapsedMs = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AutoSwitcher.monitoringSliceMs(intervalMs = 1_000L, elapsedMs = 0L, maxSliceMs = 0L)
        }
    }
}
