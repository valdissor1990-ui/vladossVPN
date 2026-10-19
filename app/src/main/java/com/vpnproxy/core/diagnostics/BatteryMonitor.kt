package com.vpnproxy.core.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Battery / thermal snapshot helper for VPN diagnostics.
 * Does not control VPN lifecycle — only reads system battery intents.
 */
@Singleton
class BatteryMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class Sample(val levelPercent: Int, val elapsedRealtimeMs: Long)

    private val history = ArrayDeque<Sample>(HISTORY_SIZE + 1)
    private val lock = Any()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            updateBaseline(intent)
        }
    }

    @Volatile
    private var isRegistered = false

    init {
        // Seed from sticky battery intent if available (no live receiver yet).
        readSticky()?.let { updateBaseline(it) }
    }

    /** Register battery receiver. Safe to call multiple times (isRegistered guard). */
    fun register(appContext: Context = context) {
        if (isRegistered) return
        synchronized(lock) {
            if (isRegistered) return
            try {
                val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    appContext.registerReceiver(receiver, filter)
                }
                isRegistered = true
            } catch (_: Throwable) {
                // Keep working via sticky/read path.
            }
        }
    }

    /** Unregister battery receiver. Safe if never registered. */
    fun unregister(appContext: Context = context) {
        if (!isRegistered) return
        synchronized(lock) {
            if (!isRegistered) return
            try {
                appContext.unregisterReceiver(receiver)
            } catch (_: Throwable) {
                // Already unregistered or never registered on this context.
            }
            isRegistered = false
        }
    }

    /** @deprecated Use [register]; kept for call-sites during migration. */
    fun ensureRegistered() = register()

    fun getLevelPercent(): Int {
        val sticky = readSticky()
        if (sticky != null) {
            val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            return ((level * 100f) / scale).toInt().coerceIn(0, 100)
        }
        synchronized(lock) {
            return history.lastOrNull()?.levelPercent ?: -1
        }
    }

    /** Battery temperature in °C (EXTRA_TEMPERATURE is tenths of a degree). */
    fun getTemperatureCelsius(): Float {
        val sticky = readSticky() ?: return Float.NaN
        val tenths = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (tenths == Int.MIN_VALUE) return Float.NaN
        return tenths / 10f
    }

    fun isCharging(): Boolean {
        val sticky = readSticky() ?: return false
        val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    /**
     * Instantaneous current draw in mA.
     * BATTERY_PROPERTY_CURRENT_NOW is µA on most devices (negative = discharge).
     * Returns absolute value in mA; null if unsupported / unknown (0, -1, MIN_VALUE).
     */
    fun getCurrentDrawMa(): Int? {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                ?: return null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
            val ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            // 0 / -1 / MIN_VALUE = unknown on many OEMs
            if (ua == Int.MIN_VALUE || ua == 0 || ua == -1) return null
            abs(ua / 1000)
        } catch (_: Throwable) {
            null
        }
    }

    /** Percent per hour based on ring buffer endpoints. Null if not enough data. */
    fun getDrainRatePerHour(): Double? {
        synchronized(lock) {
            if (history.size < 2) return null
            val first = history.first()
            val last = history.last()
            val dtMs = (last.elapsedRealtimeMs - first.elapsedRealtimeMs).toDouble()
            if (dtMs < 60_000.0) return null // need at least 1 minute
            val dLevel = (first.levelPercent - last.levelPercent).toDouble()
            // Positive = discharging
            val hours = dtMs / 3_600_000.0
            if (hours <= 0.0) return null
            return dLevel / hours
        }
    }

    fun updateBaseline(intent: Intent? = null) {
        val src = intent ?: readSticky() ?: return
        val level = src.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = src.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level < 0) return
        val percent = ((level * 100f) / scale).toInt().coerceIn(0, 100)
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            val last = history.lastOrNull()
            if (last != null && last.levelPercent == percent && (now - last.elapsedRealtimeMs) < 5_000L) {
                return
            }
            history.addLast(Sample(percent, now))
            while (history.size > HISTORY_SIZE) history.removeFirst()
        }
    }

    private fun readSticky(): Intent? {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(null, filter)
            }
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private const val HISTORY_SIZE = 30
    }
}
