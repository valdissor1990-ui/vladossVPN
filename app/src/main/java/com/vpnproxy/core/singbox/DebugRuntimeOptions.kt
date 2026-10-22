package com.vpnproxy.core.singbox

import android.content.Context

data class RuntimeTunnelOptions(
    val dnsMode: String = "udp_8888_nodetour",
    val tunStack: String = "system",
    val strictRoute: Boolean = true,
    val routeFinal: String = "proxy"
)

object DebugRuntimeOptions {
    private const val PREFS = "vpn_runtime_diagnostics"
    private const val DNS_MODE = "dns_mode"
    private const val TUN_STACK = "tun_stack"
    private const val STRICT_ROUTE = "strict_route"
    private const val ROUTE_FINAL = "route_final"

    val dnsModes = listOf(
        "udp_8888_nodetour",
        "udp_1111_nodetour",
        "dot_111853_nodetour",
        "system_no_hijack"
    )
    val tunStacks = listOf("gvisor", "system")
    val routeFinals = listOf("proxy", "direct")

    fun load(context: Context): RuntimeTunnelOptions {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return RuntimeTunnelOptions(
            dnsMode = prefs.getString(DNS_MODE, dnsModes.first())!!.takeIf { it in dnsModes } ?: dnsModes.first(),
            tunStack = prefs.getString(TUN_STACK, "system")!!.takeIf { it in tunStacks } ?: "system",
            strictRoute = prefs.getBoolean(STRICT_ROUTE, true),
            routeFinal = prefs.getString(ROUTE_FINAL, "proxy")!!.takeIf { it in routeFinals } ?: "proxy"
        )
    }

    fun save(context: Context, options: RuntimeTunnelOptions) {
        require(options.dnsMode in dnsModes)
        require(options.tunStack in tunStacks)
        require(options.routeFinal in routeFinals)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(DNS_MODE, options.dnsMode)
            .putString(TUN_STACK, options.tunStack)
            .putBoolean(STRICT_ROUTE, options.strictRoute)
            .putString(ROUTE_FINAL, options.routeFinal)
            .apply()
    }
}
