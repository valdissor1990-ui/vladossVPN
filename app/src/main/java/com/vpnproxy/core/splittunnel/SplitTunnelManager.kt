package com.vpnproxy.core.splittunnel

import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies per-app VPN policy before VpnService.Builder.establish().
 * The VPN app is always removed from the user selection to avoid routing
 * the app's own control-plane traffic into its tunnel.
 */
@Singleton
class SplitTunnelManager @Inject constructor(
    private val store: UserRulesStore
) {
    companion object {
        private const val TAG = "SplitTunnelManager"
    }

    suspend fun applyRules(builder: VpnService.Builder, context: Context) {
        val stored = store.readRules()
        val selected = (stored.selectedPackages - context.packageName)
            .filterTo(linkedSetOf()) { packageName ->
                try {
                    context.packageManager.getApplicationInfo(packageName, 0)
                    true
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }
            }

        if (selected != stored.selectedPackages) {
            store.saveRules(stored.copy(selectedPackages = selected))
        }

        when (stored.mode) {
            SplitTunnelMode.ALL_APPS -> {
                // Android defaults to all apps; only exclude this VPN app.
                builder.addDisallowedApplication(context.packageName)
            }
            SplitTunnelMode.EXCLUDE_SELECTED -> {
                builder.addDisallowedApplication(context.packageName)
                selected.forEach(builder::addDisallowedApplication)
            }
            SplitTunnelMode.INCLUDE_SELECTED -> {
                // An empty allow-list can fall back to the default all-apps behavior.
                // Fail closed instead of unexpectedly sending every app through VPN.
                check(selected.isNotEmpty()) {
                    "Split tunneling mode 'Only selected apps' requires at least one installed app"
                }
                selected.forEach(builder::addAllowedApplication)
            }
        }
        Log.i(TAG, "Applied split-tunnel mode=${stored.mode}, selected=${selected.size}")
    }
}
