package com.vpnproxy.smartconnect

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the physical network class beneath VpnService.
 *
 * The VPN transport itself is not a useful quality bucket: while the tunnel is active,
 * Android commonly reports TRANSPORT_VPN as the active network. In that case we inspect
 * validated non-VPN networks, matching the previous AutoSwitcher policy.
 */
@Singleton
class NetworkBucketProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun currentBucket(): String {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "other"
        val active = manager.activeNetwork
        val activeCapabilities = active?.let(manager::getNetworkCapabilities)

        fun bucket(capabilities: NetworkCapabilities?): String? = when {
            capabilities == null -> null
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "other"
            else -> null
        }

        bucket(activeCapabilities)?.let { return it }

        val underlying = manager.allNetworks
            .asSequence()
            .filter { it != active }
            .mapNotNull { network -> manager.getNetworkCapabilities(network) }
            .filter { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) }
            .filter { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
            .filter { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
            .mapNotNull(::bucket)
            .firstOrNull { it == "wifi" || it == "cellular" }

        return underlying ?: if (active == null) "offline" else "other"
    }
}
