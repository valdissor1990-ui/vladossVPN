package com.vpnproxy.smartconnect

import android.content.Context
import android.os.PowerManager

/**
 * Central place for battery-aware decisions used by AutoSwitcher and Settings UI.
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
    fun healthCheckIntervalMs(context: Context): Long =
        healthCheckIntervalMs(
            systemBatterySaver = isSystemBatterySaver(context),
            economyMode = isEconomyModeEnabled(context)
        )

    /** Pure policy function so precedence is covered by fast unit tests. */
    internal fun healthCheckIntervalMs(systemBatterySaver: Boolean, economyMode: Boolean): Long =
        when {
            systemBatterySaver -> AutoSwitcherConfig.batterySaverHealthCheckIntervalMs
            economyMode -> AutoSwitcherConfig.economyHealthCheckIntervalMs
            else -> AutoSwitcherConfig.healthCheckIntervalMs
        }

    fun shouldRunMicroProbe(context: Context): Boolean =
        shouldRunMicroProbe(isSystemBatterySaver(context))

    internal fun shouldRunMicroProbe(systemBatterySaver: Boolean): Boolean =
        !AutoSwitcherConfig.skipMicroProbeInBatterySaver || !systemBatterySaver

    fun shouldAllowRankingSwitch(context: Context): Boolean =
        shouldAllowRankingSwitch(
            systemBatterySaver = isSystemBatterySaver(context),
            economyMode = isEconomyModeEnabled(context)
        )

    /** Ranking switches are optional quality improvements; hard-failure recovery is separate. */
    internal fun shouldAllowRankingSwitch(systemBatterySaver: Boolean, economyMode: Boolean): Boolean =
        !AutoSwitcherConfig.skipRankingSwitchInBatterySaver ||
            (!systemBatterySaver && !economyMode)
}
