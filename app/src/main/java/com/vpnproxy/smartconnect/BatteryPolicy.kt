package com.vpnproxy.smartconnect

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

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

    /** True when the app is already exempt from battery optimizations. */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Opens the system screen to whitelist the app from battery optimization.
     * Required on Xiaomi / MIUI / HyperOS — otherwise VPN is often killed when the screen locks.
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (isIgnoringBatteryOptimizations(context)) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (_: Exception) {
                // OEM may hide the screen; user must whitelist manually in settings.
            }
        }
    }
}
