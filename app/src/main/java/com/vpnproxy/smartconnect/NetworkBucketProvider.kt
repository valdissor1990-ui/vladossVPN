package com.vpnproxy.smartconnect

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
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
                ) network to resolvedBucket else null
            }
            .firstOrNull { it.second == "wifi" || it.second == "cellular" }

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

        if (lastIdentity != networkId) {
            lastIdentity = networkId
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
