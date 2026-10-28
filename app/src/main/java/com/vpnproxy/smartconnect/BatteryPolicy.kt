package com.vpnproxy.smartconnect

import android.content.Context
import android.os.PowerManager

/**
 * Central place for battery-aware decisions used by AutoSwitcher and (later) UI.
 * Does not own VpnService lifecycle — only answers "how aggressive should monitoring be?".
 */
object BatteryPolicy {

    fun isSystemBatterySaver(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isPowerSaveMode == true
    }

    fun isEconomyModeEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(
            AutoSwitcherConfig.batteryPrefsName,
            Context.MODE_PRIVATE
        )
        return prefs.getBoolean(AutoSwitcherConfig.batteryEconomyEnabledKey, false)
    }

    fun setEconomyModeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(AutoSwitcherConfig.batteryPrefsName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(AutoSwitcherConfig.batteryEconomyEnabledKey, enabled)
            .apply()
    }

    /**
     * Effective health-check delay:
     * system saver > in-app economy > normal.
     */
    fun healthCheckIntervalMs(context: Context): Long {
        return when {
            isSystemBatterySaver(context) -> AutoSwitcherConfig.batterySaverHealthCheckIntervalMs
            isEconomyModeEnabled(context) -> AutoSwitcherConfig.economyHealthCheckIntervalMs
            else -> AutoSwitcherConfig.healthCheckIntervalMs
        }
    }

    fun shouldRunMicroProbe(context: Context): Boolean {
        if (!AutoSwitcherConfig.skipMicroProbeInBatterySaver) return true
        return !isSystemBatterySaver(context)
    }

    fun shouldAllowRankingSwitch(context: Context): Boolean {
        if (!AutoSwitcherConfig.skipRankingSwitchInBatterySaver) return true
        // Economy mode also suppresses pure ranking switches; hard-fail switches stay elsewhere.
        return !isSystemBatterySaver(context) && !isEconomyModeEnabled(context)
    }
}
