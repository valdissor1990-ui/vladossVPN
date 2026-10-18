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

    suspend fun applyRules(
        builder: VpnService.Builder,
        context: Context,
        optionIncludes: Set<String> = emptySet(),
        optionExcludes: Set<String> = emptySet()
    ) {
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

        val includes = (selected + optionIncludes) - optionExcludes - context.packageName
        val excludes = selected + optionExcludes + context.packageName
        when (stored.mode) {
            SplitTunnelMode.ALL_APPS -> {
                if (optionIncludes.isNotEmpty()) {
                    // Android permits either an allow-list or a deny-list on a VPN builder,
                    // never both. Explicit Sing-box includes therefore select allow-list mode.
                    addAllowed(builder, includes)
                } else {
                    addDisallowed(builder, excludes)
                }
            }
            SplitTunnelMode.EXCLUDE_SELECTED -> {
                check(optionIncludes.isEmpty()) {
                    "Conflicting per-app policies: EXCLUDE_SELECTED cannot be combined with TunOptions.includePackage on Android"
                }
                addDisallowed(builder, excludes)
            }
            SplitTunnelMode.INCLUDE_SELECTED -> {
                check(includes.isNotEmpty()) {
                    "Split tunneling mode 'Only selected apps' requires at least one installed app"
                }
                addAllowed(builder, includes)
            }
        }
        Log.i(TAG, "Applied split-tunnel mode=${stored.mode}, selected=${selected.size}, tunIncludes=${optionIncludes.size}, tunExcludes=${optionExcludes.size}")
    }

    private fun addAllowed(builder: VpnService.Builder, packages: Set<String>) {
        packages.forEach { packageName ->
            try { builder.addAllowedApplication(packageName) }
            catch (e: PackageManager.NameNotFoundException) { Log.w(TAG, "Unknown included package: $packageName", e) }
        }
    }

    private fun addDisallowed(builder: VpnService.Builder, packages: Set<String>) {
        packages.forEach { packageName ->
            try { builder.addDisallowedApplication(packageName) }
            catch (e: PackageManager.NameNotFoundException) { Log.w(TAG, "Unknown excluded package: $packageName", e) }
        }
    }
}
