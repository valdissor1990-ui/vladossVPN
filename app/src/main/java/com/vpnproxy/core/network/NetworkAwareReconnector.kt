package com.vpnproxy.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.vpnproxy.logging.LogCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Следит за сменой сети (WiFi ↔ LTE).
 * Flow + debounce 3s + distinctUntilChanged — не спамит NetworkChanged.
 */
class NetworkAwareReconnector(
    context: Context,
    private val logCollector: LogCollector,
    private val isVpnActive: () -> Boolean,
    private val isBusy: () -> Boolean = { false },
    private val onReconnectRequested: () -> Unit,
    private val onReconnectStateChanged: (Boolean) -> Unit = {}
) {
    companion object {
        private const val TAG = "NetworkAwareReconnector"
        private const val DEBOUNCE_MS = 3_000L
        private const val COOLDOWN_MS = 5_000L
    }

    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var collectJob: Job? = null

    @Volatile private var lastReconnectAt = 0L
    private val vpnNetworks = ConcurrentHashMap.newKeySet<Network>()

    private sealed class NetEvent {
        data class Lost(val netId: Int, val isVpn: Boolean) : NetEvent()
        data class Available(val netId: Int, val signature: String) : NetEvent()
    }

    private val networkStatusFlow = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network) ?: return
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpnNetworks.add(network)
                    return
                }
                vpnNetworks.remove(network)
                trySend(NetEvent.Available(network.hashCode(), signature(caps)))
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpnNetworks.add(network)
                    return
                }
                vpnNetworks.remove(network)
                trySend(NetEvent.Available(network.hashCode(), signature(caps)))
            }

            override fun onLost(network: Network) {
                val isVpn = vpnNetworks.remove(network)
                trySend(NetEvent.Lost(network.hashCode(), isVpn))
            }
        }
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback
            )
            logCollector.info(TAG, "Registered network callback (flow)")
        } catch (t: Throwable) {
            logCollector.error(TAG, "Failed to register network callback", t)
            close(t)
            return@callbackFlow
        }
        awaitClose {
            try {
                cm.unregisterNetworkCallback(callback)
                logCollector.info(TAG, "Unregistered network callback")
            } catch (t: Throwable) {
                logCollector.warn(TAG, "Unregister failed: ${t.message}")
            }
        }
    }

    private fun signature(caps: NetworkCapabilities): String {
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "OTHER"
        }
        return "$transport:" +
            "${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}:" +
            "${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
    }

    fun start() {
        if (collectJob?.isActive == true) return
        collectJob = scope.launch {
            networkStatusFlow
                .debounce(DEBOUNCE_MS)
                .distinctUntilChanged()
                .filter { event ->
                    when (event) {
                        is NetEvent.Lost -> isVpnActive() && !isBusy()
                        is NetEvent.Available -> false // only lost triggers reconnect after quiet period
                    }
                }
                .collect { event ->
                    val now = System.currentTimeMillis()
                    if (now - lastReconnectAt < COOLDOWN_MS) {
                        logCollector.debug(TAG, "Cooldown active, skip NetworkChanged")
                        onReconnectStateChanged(false)
                        return@collect
                    }
                    if (!isVpnActive() || isBusy()) {
                        logCollector.debug(TAG, "Skip reconnect — vpn=${isVpnActive()} busy=${isBusy()}")
                        onReconnectStateChanged(false)
                        return@collect
                    }
                    lastReconnectAt = now
                    onReconnectStateChanged(true)
                    logCollector.info(TAG, "Network quiet ${DEBOUNCE_MS}ms after $event → NetworkChanged")
                    try {
                        onReconnectRequested()
                    } catch (t: Throwable) {
                        logCollector.error(TAG, "Reconnect request failed", t)
                    } finally {
                        onReconnectStateChanged(false)
                    }
                }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
        onReconnectStateChanged(false)
    }
}
