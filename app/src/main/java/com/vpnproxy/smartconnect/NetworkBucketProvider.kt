package com.vpnproxy.smartconnect

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the physical network beneath VpnService and assigns an observed generation
 * whenever the selected underlay identity changes.
 *
 * The VPN transport itself is not a useful quality bucket: while the tunnel is active,
 * Android commonly reports TRANSPORT_VPN as the active network. In that case we inspect
 * validated non-VPN networks, matching the previous AutoSwitcher policy.
 */
@Singleton
class NetworkBucketProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var lastIdentity: String? = null
    private var generation: Long = 0L
    private var lastObservedEventGeneration: Long = 0L
    private val networkEventGeneration = AtomicLong(0L)
    private val observedPhysicalNetworks = ConcurrentHashMap<String, Boolean>()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val isValidatedPhysicalNetwork =
                bucket(capabilities) != null &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val key = network.toString()
            if (isValidatedPhysicalNetwork) {
                val previous = observedPhysicalNetworks.put(key, true)
                if (previous != true) networkEventGeneration.incrementAndGet()
            } else if (observedPhysicalNetworks.remove(key) == true) {
                networkEventGeneration.incrementAndGet()
            }
        }

        override fun onLost(network: Network) {
            if (observedPhysicalNetworks.remove(network.toString()) == true) {
                networkEventGeneration.incrementAndGet()
            }
        }
    }

    init {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager != null) {
            runCatching {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
                manager.registerNetworkCallback(request, networkCallback)
            }
        }
    }

    @Synchronized
    fun snapshot(): PhysicalNetworkSnapshot {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager == null) {
            return snapshotFor("other", "connectivity-manager-unavailable")
        }

        val active = manager.activeNetwork
        val activeCapabilities = active?.let(manager::getNetworkCapabilities)
        val activeBucket = bucket(activeCapabilities)
        if (activeBucket != null) {
            return snapshotFor(activeBucket, active)
        }

        val underlying = manager.allNetworks
            .asSequence()
            .filter { it != active }
            .mapNotNull { network ->
                val capabilities = manager.getNetworkCapabilities(network)
                val resolvedBucket = bucket(capabilities)
                if (
                    capabilities != null &&
                    resolvedBucket != null &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                ) {
                    val priority = when (resolvedBucket) {
                        "wifi" -> 3
                        "other" -> 2
                        "cellular" -> 1
                        else -> 0
                    }
                    Triple(network, resolvedBucket, priority)
                } else {
                    null
                }
            }
            // Match AutoSwitcher's deterministic underlay policy: Wi-Fi, Ethernet, cellular.
            .maxByOrNull { it.third }
            ?.let { it.first to it.second }

        if (underlying != null) {
            return snapshotFor(underlying.second, underlying.first)
        }

        return snapshotFor(if (active == null) "offline" else "other", active)
    }

    fun currentBucket(): String = snapshot().bucket

    private fun bucket(capabilities: NetworkCapabilities?): String? = when {
        capabilities == null -> null
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "other"
        else -> null
    }

    private fun snapshotFor(bucket: String, network: Network?): PhysicalNetworkSnapshot =
        snapshotFor(bucket, network?.toString() ?: "none")

    private fun snapshotFor(bucket: String, rawNetworkIdentity: String): PhysicalNetworkSnapshot {
        // Network.toString() contains a process-local handle. Hash it so the raw value is
        // neither exposed in diagnostics nor stored in the probe result.
        val rawIdentity = "$bucket|$rawNetworkIdentity"
        val networkId = MessageDigest.getInstance("SHA-256")
            .digest(rawIdentity.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        val observedEventGeneration = networkEventGeneration.get()
        if (lastIdentity != networkId || lastObservedEventGeneration != observedEventGeneration) {
            lastIdentity = networkId
            lastObservedEventGeneration = observedEventGeneration
            generation++
        }

        return PhysicalNetworkSnapshot(
            bucket = bucket,
            networkId = networkId,
            generation = generation,
            capturedAtEpochMs = System.currentTimeMillis()
        )
    }
}
