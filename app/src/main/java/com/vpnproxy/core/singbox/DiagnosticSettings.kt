package com.vpnproxy.core.singbox

import android.content.Context
import com.vpnproxy.util.DebugFlags


data class DiagnosticOptions(
    val dnsMode: String = "proxy_dot_1111",
    val tunStack: String = "system",
    val useDisallowSelfPackage: Boolean = false,
    val autoDetectInterface: Boolean = true,
    val strictRoute: Boolean = false,
    val routeFinal: String = "proxy",
    val passiveProbeEnabled: Boolean = true,
    val freezeAutoSwitcherAfterConnected: Boolean = false
)

object DiagnosticSettings {
    private const val PREFS = "diagnostic_settings"
    val dnsModes = listOf("proxy_dot_1111", "proxy_dot_8888", "direct_udp_8888", "system_no_hijack")
    val tunStacks = listOf("system", "gvisor")
    val routeFinals = listOf("proxy", "direct")

    fun load(context: Context): DiagnosticOptions {
        if (!DebugFlags.diagnosticEnabled()) return DiagnosticOptions()
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return DiagnosticOptions(
            dnsMode = p.getString("dns_mode", "proxy_dot_1111")?.takeIf { it in dnsModes } ?: "proxy_dot_1111",
            tunStack = p.getString("tun_stack", "system")?.takeIf { it in tunStacks } ?: "system",
            useDisallowSelfPackage = p.getBoolean("use_disallow_self_package", false),
            autoDetectInterface = p.getBoolean("auto_detect_interface", true),
            strictRoute = p.getBoolean("strict_route", false),
            routeFinal = p.getString("route_final", "proxy")?.takeIf { it in routeFinals } ?: "proxy",
            passiveProbeEnabled = p.getBoolean("passive_probe_enabled", true),
            freezeAutoSwitcherAfterConnected = p.getBoolean("freeze_autoswitcher", false)
        )
    }

    fun save(context: Context, o: DiagnosticOptions) {
        require(o.dnsMode in dnsModes && o.tunStack in tunStacks && o.routeFinal in routeFinals)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("dns_mode", o.dnsMode)
            .putString("tun_stack", o.tunStack)
            .putBoolean("use_disallow_self_package", o.useDisallowSelfPackage)
            .putBoolean("auto_detect_interface", o.autoDetectInterface)
            .putBoolean("strict_route", o.strictRoute)
            .putString("route_final", o.routeFinal)
            .putBoolean("passive_probe_enabled", o.passiveProbeEnabled)
            .putBoolean("freeze_autoswitcher", o.freezeAutoSwitcherAfterConnected)
            .apply()
    }
}
