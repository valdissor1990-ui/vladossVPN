package com.vpnproxy.smartconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests both battery policy precedence and the thresholds that constrain switching. */
class BatteryPolicyTest {

    @Test
    fun healthCheckInterval_prefersSystemSaverOverEconomyMode() {
        assertEquals(
            AutoSwitcherConfig.batterySaverHealthCheckIntervalMs,
            BatteryPolicy.healthCheckIntervalMs(systemBatterySaver = true, economyMode = true)
        )
        assertEquals(
            AutoSwitcherConfig.batterySaverHealthCheckIntervalMs,
            BatteryPolicy.healthCheckIntervalMs(systemBatterySaver = true, economyMode = false)
        )
        assertEquals(
            AutoSwitcherConfig.economyHealthCheckIntervalMs,
            BatteryPolicy.healthCheckIntervalMs(systemBatterySaver = false, economyMode = true)
        )
        assertEquals(
            AutoSwitcherConfig.healthCheckIntervalMs,
            BatteryPolicy.healthCheckIntervalMs(systemBatterySaver = false, economyMode = false)
        )
    }

    @Test
    fun microProbe_isSkippedOnlyWhenSystemSaverPolicyRequiresIt() {
        assertFalse(BatteryPolicy.shouldRunMicroProbe(systemBatterySaver = true))
        assertTrue(BatteryPolicy.shouldRunMicroProbe(systemBatterySaver = false))
    }

    @Test
    fun rankingSwitch_isSuppressedForEitherBatteryEconomyMode() {
        assertFalse(BatteryPolicy.shouldAllowRankingSwitch(systemBatterySaver = true, economyMode = false))
        assertFalse(BatteryPolicy.shouldAllowRankingSwitch(systemBatterySaver = false, economyMode = true))
        assertFalse(BatteryPolicy.shouldAllowRankingSwitch(systemBatterySaver = true, economyMode = true))
        assertTrue(BatteryPolicy.shouldAllowRankingSwitch(systemBatterySaver = false, economyMode = false))
    }

    @Test
    fun continuityThresholds_remainSane() {
        assertEquals(3, AutoSwitcherConfig.maxSwitchesPerWindow)
        assertEquals(0.25f, AutoSwitcherConfig.minimumAutoSwitchScoreGain)
        assertTrue(AutoSwitcherConfig.serverBlacklistEscalatedMs > AutoSwitcherConfig.serverBlacklistDurationMs)
        assertTrue(AutoSwitcherConfig.postConnectWarmupMs >= 45_000L)
    }

    @Test
    fun urlTestInterval_prefersBatteryFriendlyCadence() {
        assertEquals("90s", AutoSwitcherConfig.urlTestInterval)
    }
}
