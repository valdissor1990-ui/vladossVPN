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
 * Watches the preferred physical internet network beneath the VPN.
 *
 * A NetworkRequest callback reports every matching network. We deliberately select one
 * preferred physical network and only emit when that selected path changes. This prevents
 * secondary cellular capability changes from restarting a VPN that is still using Wi-Fi.
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
        private const val DEBOUNCE_MS = 4_000L
        private const val COOLDOWN_MS = 8_000L
    }

    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var collectJob: Job? = null

    @Volatile private var lastReconnectAt = 0L
    private val physicalNetworks = ConcurrentHashMap<Network, String>()
    @Volatile private var selectedNetwork: Network? = null
    @Volatile private var selectedSignature: String? = null

    private sealed class NetEvent {
        data class Lost(val netId: Int) : NetEvent()
        data class UnderlyingChanged(val netId: Int, val signature: String) : NetEvent()
    }

    private fun signature(caps: NetworkCapabilities): String {
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            else -> "OTHER"
        }
        return "$transport:" +
            "${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}:" +
            "${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
    }

    private fun priority(value: String): Int {
        val validated = value.endsWith(":true")
        val transport = when {
            value.startsWith("WIFI:") -> 3
            value.startsWith("ETHERNET:") -> 2
            value.startsWith("CELLULAR:") -> 1
            else -> 0
        }
        return (if (validated) 10 else 0) + transport
    }

    /** Recompute only the preferred physical route; ignore changes on secondary networks. */
    private fun refreshSelectedNetwork(trySendEvent: (NetEvent) -> Unit) {
        val previous = selectedNetwork
        val previousSignature = selectedSignature
        val candidates = physicalNetworks.entries.toList()
        val currentStillAvailable = previous?.let { network ->
            candidates.firstOrNull { it.key == network }
        }
        val chosen = if (currentStillAvailable != null) {
            val bestPriority = candidates.maxOfOrNull { priority(it.value) } ?: Int.MIN_VALUE
            if (priority(currentStillAvailable.value) >= bestPriority) currentStillAvailable
            else candidates.maxByOrNull { priority(it.value) }
        } else {
            candidates.maxByOrNull { priority(it.value) }
        }

        selectedNetwork = chosen?.key
        selectedSignature = chosen?.value
        when {
            chosen == null && previous != null -> {
                trySendEvent(NetEvent.Lost(previous.hashCode()))
            }
            chosen != null && (chosen.key != previous || chosen.value != previousSignature) -> {
                trySendEvent(NetEvent.UnderlyingChanged(chosen.key.hashCode(), chosen.value))
            }
        }
    }

    private val networkStatusFlow = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network) ?: return
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    physicalNetworks[network] = signature(caps)
                    refreshSelectedNetwork { trySend(it) }
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    physicalNetworks[network] = signature(caps)
                } else {
                    physicalNetworks.remove(network)
                }
                refreshSelectedNetwork { trySend(it) }
            }

            override fun onLost(network: Network) {
                physicalNetworks.remove(network)
                refreshSelectedNetwork { trySend(it) }
            }
        }

        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(),
                callback
            )
            logCollector.info(TAG, "Registered physical-network callback (VPN networks excluded)")
        } catch (t: Throwable) {
            logCollector.error(TAG, "Failed to register physical-network callback", t)
            close(t)
            return@callbackFlow
        }

        // Seed the map from networks already present when the callback is registered.
        runCatching {
            cm.allNetworks.forEach { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@forEach
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ) {
                    physicalNetworks[network] = signature(caps)
                }
            }
            refreshSelectedNetwork { trySend(it) }
        }.onFailure { logCollector.warn(TAG, "Initial physical-network snapshot failed: ${it.message}") }

        awaitClose {
            try {
                cm.unregisterNetworkCallback(callback)
                physicalNetworks.clear()
                selectedNetwork = null
                selectedSignature = null
                logCollector.info(TAG, "Unregistered physical-network callback")
            } catch (t: Throwable) {
                logCollector.warn(TAG, "Unregister failed: ${t.message}")
            }
        }
    }

    fun start() {
        if (collectJob?.isActive == true) return
        collectJob = scope.launch {
            networkStatusFlow
                .debounce(DEBOUNCE_MS)
                .distinctUntilChanged()
                .filter { isVpnActive() && !isBusy() }
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
                    logCollector.info(TAG, "Preferred physical network changed after ${DEBOUNCE_MS}ms: $event")
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
