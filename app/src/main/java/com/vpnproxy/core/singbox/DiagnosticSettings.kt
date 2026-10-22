package com.vpnproxy.core.singbox

import android.content.Context
import com.vpnproxy.util.DebugFlags


data class DiagnosticOptions(
    val dnsMode: String = "direct_udp_8888",
    val tunStack: String = "gvisor",
    val strictRoute: Boolean = false,
    val routeFinal: String = "proxy",
    val passiveProbeEnabled: Boolean = true,
    val freezeAutoSwitcherAfterConnected: Boolean = true
)

object DiagnosticSettings {
    private const val PREFS = "diagnostic_settings"
    val dnsModes = listOf("direct_udp_8888", "direct_udp_1111", "direct_dot_1111", "proxy_dot_1111", "system_no_hijack")
    val tunStacks = listOf("gvisor", "system")
    val routeFinals = listOf("proxy", "direct")

    fun load(context: Context): DiagnosticOptions {
        if (!DebugFlags.diagnosticEnabled()) return DiagnosticOptions()
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return DiagnosticOptions(
            dnsMode = p.getString("dns_mode", "direct_udp_8888")?.takeIf { it in dnsModes } ?: "direct_udp_8888",
            tunStack = p.getString("tun_stack", "gvisor")?.takeIf { it in tunStacks } ?: "gvisor",
            strictRoute = p.getBoolean("strict_route", false),
            routeFinal = p.getString("route_final", "proxy")?.takeIf { it in routeFinals } ?: "proxy",
            passiveProbeEnabled = p.getBoolean("passive_probe_enabled", true),
            freezeAutoSwitcherAfterConnected = p.getBoolean("freeze_autoswitcher", true)
        )
    }

    fun save(context: Context, o: DiagnosticOptions) {
        require(o.dnsMode in dnsModes && o.tunStack in tunStacks && o.routeFinal in routeFinals)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("dns_mode", o.dnsMode)
            .putString("tun_stack", o.tunStack)
            .putBoolean("strict_route", o.strictRoute)
            .putString("route_final", o.routeFinal)
            .putBoolean("passive_probe_enabled", o.passiveProbeEnabled)
            .putBoolean("freeze_autoswitcher", o.freezeAutoSwitcherAfterConnected)
            .apply()
    }
}
