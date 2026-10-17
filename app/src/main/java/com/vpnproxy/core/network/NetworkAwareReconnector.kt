package com.vpnproxy.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.vpnproxy.logging.LogCollector

/**
 * Следит за сменой сети (WiFi → LTE и обратно).
 * Debounce 2s + cooldown 5s; игнорирует события во время старта/переключения VPN.
 */
class NetworkAwareReconnector(
    context: Context,
    private val logCollector: LogCollector,
    private val isVpnActive: () -> Boolean,
    /** true пока VPN стартует или идёт switch/reconnect — callback молчит. */
    private val isBusy: () -> Boolean = { false },
    private val onReconnectRequested: () -> Unit
) {
    companion object {
        private const val TAG = "NetworkAwareReconnector"
        private const val DEBOUNCE_MS = 2_000L
        private const val COOLDOWN_MS = 5_000L
    }

    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var registered = false
    @Volatile private var lastReconnectAt = 0L
    @Volatile private var lastNetworkId: Int? = null

    private val pendingRestart = Runnable {
        if (!isVpnActive()) {
            logCollector.debug(TAG, "Debounced restart skipped — VPN not active")
            return@Runnable
        }
        if (isBusy()) {
            logCollector.debug(TAG, "Debounced restart skipped — VPN busy")
            return@Runnable
        }
        val now = System.currentTimeMillis()
        if (now - lastReconnectAt < COOLDOWN_MS) {
            logCollector.debug(TAG, "Cooldownoldown active, skip restart")
            return@Runnable
        }
        lastReconnectAt = now
        logCollector.info(TAG, "Network changed → reconnect after ${DEBOUNCE_MS}ms debounce")
        onReconnectRequested()
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = cm.getNetworkCapabilities(network)
            val transport = when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "CELLULAR"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ETHERNET"
                else -> "OTHER"
            }
            val netId = network.hashCode()

            if (isBusy()) {
                logCollector.debug(TAG, "onAvailable ignored (busy) transport=$transport netId=$netId")
                lastNetworkId = netId
                return
            }

            logCollector.info(TAG, "onAvailable transport=$transport netId=$netId vpn=${isVpnActive()}")

            if (!isVpnActive()) {
                lastNetworkId = netId
                return
            }
            if (lastNetworkId == null) {
                lastNetworkId = netId
                return
            }
            if (lastNetworkId == netId) return
            lastNetworkId = netId
            scheduleRestart()
        }

        override fun onLost(network: Network) {
            if (isBusy()) {
                logCollector.debug(TAG, "onLost ignored (busy) netId=${network.hashCode()}")
                return
            }
            logCollector.info(TAG, "onLost netId=${network.hashCode()} vpn=${isVpnActive()}")
        }
    }

    fun start() {
        if (registered) return
        try {
            cm.registerDefaultNetworkCallback(callback)
            registered = true
            logCollector.info(TAG, "Registered default network callback")
        } catch (t: Throwable) {
            logCollector.error(TAG, "Failed to register network callback", t)
        }
    }

    fun stop() {
        mainHandler.removeCallbacks(pendingRestart)
        if (!registered) return
        try {
            cm.unregisterNetworkCallback(callback)
            registered = false
            logCollector.info(TAG, "Unregistered network callback")
        } catch (t: Throwable) {
            logCollector.warn(TAG, "Unregister failed: ${t.message}")
        }
    }

    private fun scheduleRestart() {
        mainHandler.removeCallbacks(pendingRestart)
        logCollector.info(TAG, "Network changed, restarting tunnel in ${DEBOUNCE_MS}ms…")
        mainHandler.postDelayed(pendingRestart, DEBOUNCE_MS)
    }
}
