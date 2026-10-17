package com.vpnproxy.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
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
    @Volatile private var lastNetwork: Network? = null
    private val vpnNetworks = ConcurrentHashMap.newKeySet<Network>()

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
            if (caps == null) {
                // Wait for onCapabilitiesChanged rather than guessing whether this is VPN.
                logCollector.debug(TAG, "onAvailable awaiting capabilities netId=${network.hashCode()}")
                return
            }
            handleAvailable(network, caps)
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            handleAvailable(network, caps)
        }

        override fun onLost(network: Network) {
            val netId = network.hashCode()
            if (vpnNetworks.remove(network)) {
                logCollector.info(TAG, "VPN network lost netId=$netId; scheduling recovery if tunnel still marked active")
                if (isVpnActive() && !isBusy()) scheduleRestart()
                return
            }

            if (isBusy()) {
                logCollector.debug(TAG, "onLost ignored (busy) netId=$netId")
                return
            }
            logCollector.info(TAG, "onLost netId=$netId vpn=${isVpnActive()}")
        }
    }

    private fun handleAvailable(network: Network, caps: NetworkCapabilities) {
        val netId = network.hashCode()
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            vpnNetworks.add(network)
            logCollector.debug(TAG, "VPN transport ignored on available/capabilities netId=$netId")
            return
        }
        vpnNetworks.remove(network)

        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "OTHER"
        }

        if (isBusy()) {
            logCollector.debug(TAG, "Network callback ignored (busy) transport=$transport netId=$netId")
            lastNetwork = network
            return
        }

        logCollector.info(TAG, "Network available transport=$transport netId=$netId vpn=${isVpnActive()}")
        if (!isVpnActive()) {
            lastNetwork = network
            return
        }
        if (lastNetwork == null) {
            lastNetwork = network
            return
        }
        if (lastNetwork == network) return
        lastNetwork = network
        scheduleRestart()
    }

    fun start() {
        if (registered) return
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback
            )
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
