package com.vpnproxy.core.tunnel

import android.net.VpnService
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kill Switch: конфигурирует VpnService.Builder так, чтобы при разрыве
 * туннеля трафик не уходил в открытую сеть.
 */
@Singleton
class KillSwitch @Inject constructor() {

    companion object { private const val TAG = "KillSwitch" }

    fun apply(builder: VpnService.Builder, enabled: Boolean) {
        try {
            if (enabled) {
                builder.addRoute("0.0.0.0", 0)
                builder.addRoute("::", 0)
                builder.addDnsServer("1.1.1.1")
                builder.setUnderlyingNetworks(null)
                builder.setBlocking(true)
                Log.i(TAG, "Kill Switch APPLIED: full IPv4/IPv6 route, DNS via tunnel")
            } else {
                builder.addRoute("0.0.0.0", 0)
                builder.addDnsServer(TunnelManager.DNS_SERVER)
                Log.i(TAG, "Kill Switch DISABLED")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply Kill Switch", e)
        }
    }
}
