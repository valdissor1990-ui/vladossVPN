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
 * Debounce 2s + cooldown 5s; игнорирует «мигание» сети <1s, чтобы не рвать UDP (Zello).
 */
class NetworkAwareReconnector(
    context: Context,
    private val logCollector: LogCollector,
    private val isVpnActive: () -> Boolean,
    /** true пока VPN стартует или идёт switch/reconnect — callback молчит. */
    private val isBusy: () -> Boolean = { false },
    private val onReconnectRequested: () -> Unit,
    private val onReconnectStateChanged: (Boolean) -> Unit = {}
) {
    companion object {
        private const val TAG = "NetworkAwareReconnector"
        private const val DEBOUNCE_MS = 2_000L
        private const val COOLDOWN_MS = 5_000L
        /** Brief onLost→recovery within this window cancels pending restart. */
        private const val BLINK_MS = 1_000L
    }

    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var registered = false
    @Volatile private var lastReconnectAt = 0L
    @Volatile private var lastLostAt = 0L
    private val vpnNetworks = ConcurrentHashMap.newKeySet<Network>()
    @Volatile private var lastAvailableNetwork: Network? = null
    @Volatile private var lastAvailableSignature: String? = null

    private val pendingRestart = Runnable {
        if (!isVpnActive()) {
            logCollector.debug(TAG, "Debounced restart skipped — VPN not active")
            onReconnectStateChanged(false)
            return@Runnable
        }
        if (isBusy()) {
            logCollector.debug(TAG, "Debounced restart skipped — VPN busy")
            onReconnectStateChanged(false)
            return@Runnable
        }
        // If underlying network recovered before debounce fired, skip restart
        if (hasUsableUnderlyingNetwork()) {
            logCollector.info(TAG, "Debounced restart skipped — underlying network recovered (UDP-safe)")
            onReconnectStateChanged(false)
            return@Runnable
        }
        val now = System.currentTimeMillis()
        if (now - lastReconnectAt < COOLDOWN_MS) {
            logCollector.debug(TAG, "Cooldown active, skip restart")
            onReconnectStateChanged(false)
            return@Runnable
        }
        lastReconnectAt = now
        logCollector.info(TAG, "Network changed → reconnect after ${DEBOUNCE_MS}ms debounce")
        try {
            onReconnectRequested()
        } catch (t: Throwable) {
            onReconnectStateChanged(false)
            logCollector.error(TAG, "Reconnect request failed", t)
        }
    }

    private fun hasUsableUnderlyingNetwork(): Boolean {
        return try {
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Throwable) {
            false
        }
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = cm.getNetworkCapabilities(network) ?: return
            // Filter the VPN interface before logging or any reconnect logic.
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                vpnNetworks.add(network)
                return
            }
            handleAvailable(network, caps)
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            // A VPN interface is never an underlying network candidate.
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                vpnNetworks.add(network)
                return
            }
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
            logCollector.info(TAG, "Underlying network lost netId=$netId vpn=${isVpnActive()}")
            if (lastAvailableNetwork == network) {
                lastAvailableNetwork = null
                lastAvailableSignature = null
            }
            lastLostAt = System.currentTimeMillis()
            if (isVpnActive()) scheduleRestart()
        }
    }

    private fun handleAvailable(network: Network, caps: NetworkCapabilities) {
        val netId = network.hashCode()
        // Defensive guard: never treat the VPN interface as the physical underlay.
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            vpnNetworks.add(network)
            return
        }
        vpnNetworks.remove(network)

        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "OTHER"
        }
        val signature = buildString {
            append(transport)
            append(':').append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            append(':').append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            append(':').append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED))
        }
        val duplicate = lastAvailableNetwork == network &&
            lastAvailableSignature == signature &&
            lastLostAt == 0L
        if (duplicate) return
        lastAvailableNetwork = network
        lastAvailableSignature = signature
        logCollector.debug(TAG, "Underlying network available transport=$transport netId=$netId vpn=${isVpnActive()} busy=${isBusy()}")

        // Brief blink: network came back quickly — cancel pending tunnel restart (protect UDP/Zello)
        val sinceLost = System.currentTimeMillis() - lastLostAt
        if (lastLostAt > 0L && sinceLost < BLINK_MS) {
            mainHandler.removeCallbacks(pendingRestart)
            onReconnectStateChanged(false)
            logCollector.info(TAG, "Network blink ${sinceLost}ms — cancel reconnect (UDP-safe)")
            lastLostAt = 0L
            return
        }
        // Consume the loss marker after the first recovery callback, even when recovery
        // took longer than BLINK_MS; otherwise identical callbacks would never deduplicate.
        lastLostAt = 0L

        // Network recovered during debounce window — cancel pending restart
        if (isVpnActive() && !isBusy() && hasUsableUnderlyingNetwork()) {
            mainHandler.removeCallbacks(pendingRestart)
            onReconnectStateChanged(false)
            logCollector.debug(TAG, "Underlying network recovered during debounce — skip restart")
        }
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
        onReconnectStateChanged(false)
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
        onReconnectStateChanged(true)
        logCollector.info(TAG, "Network changed, restarting tunnel in ${DEBOUNCE_MS}ms…")
        mainHandler.postDelayed(pendingRestart, DEBOUNCE_MS)
    }
}
