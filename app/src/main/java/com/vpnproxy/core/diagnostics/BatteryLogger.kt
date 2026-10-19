package com.vpnproxy.core.diagnostics

import com.vpnproxy.logging.LogCollector
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BatteryLogger @Inject constructor(
    private val batteryMonitor: BatteryMonitor,
    private val logCollector: LogCollector
) {
    companion object {
        private const val TAG = "Battery"
        private const val DRAIN_WARN_PCT_PER_H = 15.0
        private const val TEMP_WARN_C = 42f
    }

    fun logSnapshot(trigger: String) {
        batteryMonitor.register()
        batteryMonitor.updateBaseline()
        val level = batteryMonitor.getLevelPercent()
        val temp = batteryMonitor.getTemperatureCelsius()
        val charging = batteryMonitor.isCharging()
        val draw = batteryMonitor.getCurrentDrawMa()
        val drain = batteryMonitor.getDrainRatePerHour()
        val tempStr = if (temp.isNaN()) "N/A" else String.format("%.1f", temp)
        val drawStr = draw?.toString() ?: "N/A"
        val drainStr = when {
            charging -> "charging"
            drain == null -> "N/A"
            else -> String.format("%.1f", drain)
        }
        val drainUnit = if (charging || drain == null) "" else "%/h"
        val msg =
            "BATTERY | trigger=$trigger level=$level% temp=${tempStr}°C " +
                "charging=$charging draw=$drawStr mA drain=$drainStr$drainUnit"
        logCollector.info(TAG, msg)
    }

    fun logWarningIfNeeded() {
        batteryMonitor.updateBaseline()
        val drain = batteryMonitor.getDrainRatePerHour()
        val charging = batteryMonitor.isCharging()
        if (drain != null && drain > DRAIN_WARN_PCT_PER_H && !charging) {
            logCollector.warn(
                TAG,
                "BATTERY WARN high drain: ${String.format("%.1f", drain)}%/h (threshold=$DRAIN_WARN_PCT_PER_H)"
            )
        }
        val temp = batteryMonitor.getTemperatureCelsius()
        if (!temp.isNaN() && temp > TEMP_WARN_C) {
            logCollector.warn(
                TAG,
                "BATTERY WARN high temperature: ${String.format("%.1f", temp)}°C (threshold=$TEMP_WARN_C)"
            )
        }
    }
}
