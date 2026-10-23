package com.vpnproxy.core.splittunnel

import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies per-app VPN policy before VpnService.Builder.establish().
 * The VPN app is removed from user-selected packages. Diagnostic mode can optionally
 * add it to Android's disallowed-application list for comparison testing.
 */
data class AppliedApplicationRules(
    val allowedApps: Set<String>,
    val disallowedApps: Set<String>
)

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
        optionExcludes: Set<String> = emptySet(),
        disallowSelfPackage: Boolean = false
    ): AppliedApplicationRules {
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
        val excludes = selected + optionExcludes + if (disallowSelfPackage) setOf(context.packageName) else emptySet()
        val allowedApplied = linkedSetOf<String>()
        val disallowedApplied = linkedSetOf<String>()
        when (stored.mode) {
            SplitTunnelMode.ALL_APPS -> {
                if (optionIncludes.isNotEmpty()) {
                    // Android permits either an allow-list or a deny-list on a VPN builder,
                    // never both. Explicit Sing-box includes therefore select allow-list mode.
                    allowedApplied += addAllowed(builder, (optionIncludes - optionExcludes) - context.packageName)
                } else {
                    disallowedApplied += addDisallowed(builder, excludes)
                }
            }
            SplitTunnelMode.EXCLUDE_SELECTED -> {
                check(optionIncludes.isEmpty()) {
                    "Conflicting per-app policies: EXCLUDE_SELECTED cannot be combined with TunOptions.includePackage on Android"
                }
                disallowedApplied += addDisallowed(builder, excludes)
            }
            SplitTunnelMode.INCLUDE_SELECTED -> {
                check(includes.isNotEmpty()) {
                    "Split tunneling mode 'Only selected apps' requires at least one installed app"
                }
                allowedApplied += addAllowed(builder, includes)
            }
        }
        Log.i(TAG, "Applied split-tunnel mode=${stored.mode}, selected=${selected.size}, tunIncludes=${optionIncludes.size}, tunExcludes=${optionExcludes.size}, disallowSelfPackage=$disallowSelfPackage")
        return AppliedApplicationRules(allowedApplied, disallowedApplied)
    }

    private fun addAllowed(builder: VpnService.Builder, packages: Set<String>): Set<String> {
        val applied = linkedSetOf<String>()
        packages.forEach { packageName ->
            try {
                builder.addAllowedApplication(packageName)
                applied += packageName
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Unknown included package: $packageName", e)
            }
        }
        return applied
    }

    private fun addDisallowed(builder: VpnService.Builder, packages: Set<String>): Set<String> {
        val applied = linkedSetOf<String>()
        packages.forEach { packageName ->
            try {
                builder.addDisallowedApplication(packageName)
                applied += packageName
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Unknown excluded package: $packageName", e)
            }
        }
        return applied
    }
}
