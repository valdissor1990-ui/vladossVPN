package com.vpnproxy.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchdogReconnectPolicyTest {
    @Test
    fun waitsWhileReconnectIsInProgressWithinGracePeriod() {
        assertTrue(
            WatchdogReconnectPolicy.shouldWaitForReconnect(
                vpnRunning = false,
                reconnecting = true,
                graceWaitedMs = 0L
            )
        )
        assertTrue(
            WatchdogReconnectPolicy.shouldWaitForReconnect(
                vpnRunning = false,
                reconnecting = true,
                graceWaitedMs = 25_000L
            )
        )
    }

    @Test
    fun doesNotWaitWhenVpnIsRunningOrReconnectHasEnded() {
        assertFalse(
            WatchdogReconnectPolicy.shouldWaitForReconnect(
                vpnRunning = true,
                reconnecting = true,
                graceWaitedMs = 0L
            )
        )
        assertFalse(
            WatchdogReconnectPolicy.shouldWaitForReconnect(
                vpnRunning = false,
                reconnecting = false,
                graceWaitedMs = 0L
            )
        )
    }

    @Test
    fun doesNotExtendBeyondMaximumGracePeriod() {
        assertFalse(
            WatchdogReconnectPolicy.shouldWaitForReconnect(
                vpnRunning = false,
                reconnecting = true,
                graceWaitedMs = WatchdogReconnectPolicy.MAX_RECONNECT_GRACE_MS
            )
        )
    }
}
