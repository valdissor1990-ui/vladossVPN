package com.vpnproxy.smartconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure constant / ordering checks — Context-dependent paths are covered in instrumented tests later.
 */
class BatteryPolicyTest {

    @Test
    fun intervals_areOrderedForBattery() {
        assertTrue(AutoSwitcherConfig.healthCheckIntervalMs < AutoSwitcherConfig.economyHealthCheckIntervalMs)
        assertTrue(AutoSwitcherConfig.economyHealthCheckIntervalMs < AutoSwitcherConfig.batterySaverHealthCheckIntervalMs)
    }

    @Test
    fun continuityThresholds_remainSane() {
        assertEquals(3, AutoSwitcherConfig.maxSwitchesPerWindow)
        assertEquals(0.20f, AutoSwitcherConfig.minimumAutoSwitchScoreGain)
        assertTrue(AutoSwitcherConfig.serverBlacklistEscalatedMs > AutoSwitcherConfig.serverBlacklistDurationMs)
        assertTrue(AutoSwitcherConfig.postConnectWarmupMs >= 45_000L)
    }

    @Test
    fun urlTestInterval_prefersBatteryFriendlyCadence() {
        // "90s" is intentional once standbys land; avoid sub-minute radio chatter.
        assertEquals("90s", AutoSwitcherConfig.urlTestInterval)
    }
}
