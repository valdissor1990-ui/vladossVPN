package com.vpnproxy.ace

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject
import uniffi.ace_core.aceIngestTelemetry
import java.util.concurrent.atomic.AtomicLong

/**
 * Passive Stage C Android observations. No probes, sockets, VPN commands,
 * NetworkExecutor calls, or Smart Connect integration.
 */
internal class AceAndroidTelemetryCollector(context: Context) {
    private val app = context.applicationContext
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val power = app.getSystemService(PowerManager::class.java)
    private val thread = HandlerThread("ace-telemetry-callback").apply { start() }
    private val handler = Handler(thread.looper)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<String>(capacity = 256)
    private val sequence = AtomicLong()
    @Volatile private var stopped = false
    private var receiverRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1.0
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2.0
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3.0
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> 4.0
                else -> 0.0
            }
            submit("network_transport", transport, "transport_code", "android_network_callback")
            submit("network_validated",
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 1.0 else 0.0,
                "boolean", "android_network_callback")
            submit("network_metered",
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) 0.0 else 1.0,
                "boolean", "android_network_callback")
        }

        override fun onLost(network: Network) {
            // Losing one network does not prove that the device is offline.
            submitMissing("network_transport", "default_network_lost",
                "transport_code", "android_network_callback")
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED ||
                intent?.action == PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED) emitPowerState()
        }
    }

    init {
        scope.launch {
            for (record in queue) {
                runCatching { aceIngestTelemetry(record) }
                    .onFailure { Log.w(TAG, "Telemetry ingestion failed", it) }
            }
        }
    }

    fun start() {
        if (stopped) return
        runCatching {
            connectivity?.registerDefaultNetworkCallback(networkCallback, handler)
                ?: Log.w(TAG, "ConnectivityManager unavailable")
        }.onFailure { Log.w(TAG, "Network callback registration failed", it) }

        val filter = IntentFilter().apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(powerReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                app.registerReceiver(powerReceiver, filter)
            }
            receiverRegistered = true
        }.onFailure { Log.w(TAG, "Power receiver registration failed", it) }
        emitPowerState()
    }

    fun stop() {
        if (stopped) return
        stopped = true
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        if (receiverRegistered) runCatching { app.unregisterReceiver(powerReceiver) }
        receiverRegistered = false
        queue.close()
        thread.quitSafely()
    }

    private fun emitPowerState() {
        runCatching { power?.isPowerSaveMode }.getOrNull()?.let {
            submit("battery_saver", if (it) 1.0 else 0.0, "boolean", "power_manager")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { power?.isDeviceIdleMode }.getOrNull()?.let {
                submit("device_idle", if (it) 1.0 else 0.0, "boolean", "power_manager")
            }
        }
    }

    private fun submit(metric: String, value: Double, unit: String, source: String) {
        enqueue(makeRecord(metric, value, unit, null, source))
    }

    private fun submitMissing(metric: String, reason: String, unit: String, source: String) {
        enqueue(makeRecord(metric, null, unit, reason, source))
    }

    private fun makeRecord(
        metric: String, value: Double?, unit: String, missingReason: String?, source: String
    ): String = JSONObject().apply {
        put("schema_version", 1)
        put("event_id", "android-${Process.myPid()}-${sequence.incrementAndGet()}")
        put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
        put("metric", metric)
        put("value", value ?: JSONObject.NULL)
        put("unit", unit)
        put("outcome", if (value == null) "unavailable" else "observed")
        put("missing_reason", missingReason ?: JSONObject.NULL)
        put("restricted_context", runCatching { power?.isDeviceIdleMode == true }.getOrDefault(false))
        put("source", source)
    }.toString()

    private fun enqueue(record: String) {
        if (!stopped && queue.trySend(record).isFailure) {
            Log.w(TAG, "Bounded telemetry queue full; observation dropped")
        }
    }

    companion object { private const val TAG = "AceAndroidTelemetry" }
}
