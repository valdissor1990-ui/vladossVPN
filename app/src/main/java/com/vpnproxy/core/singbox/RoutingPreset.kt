package com.vpnproxy.core.singbox

/**
 * How traffic that leaves the TUN device is split between the tunnel and the local network.
 *
 * Kept Android-free so the resulting `route.rules` JSON is unit tested on the JVM.
 */
enum class RoutingPreset(val id: String) {
    /** Everything goes through the proxy, including LAN destinations. */
    ALL_VPN("all_vpn"),

    /** Private / link-local destinations (router, printer, Chromecast) stay direct. */
    BYPASS_LAN("bypass_lan"),

    /** LAN stays direct and Russian domains / IPs avoid the tunnel. */
    BYPASS_RU("bypass_ru");

    companion object {
        val DEFAULT = BYPASS_LAN

        fun fromId(id: String?): RoutingPreset =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * Routing inputs for [SingboxConfigBuilder.buildVpn].
 *
 * [geoipRuPath] and [geositeRuPath] are absolute paths to local `.srs` files. They are only
 * honoured for [RoutingPreset.BYPASS_RU]; if either file is missing the preset degrades to
 * [RoutingPreset.BYPASS_LAN] instead of emitting a rule-set the native core cannot load.
 */
data class RoutingRules(
    val preset: RoutingPreset = RoutingPreset.DEFAULT,
    val geoipRuPath: String? = null,
    val geositeRuPath: String? = null
) {
    val effectivePreset: RoutingPreset = when {
        preset != RoutingPreset.BYPASS_RU -> preset
        geoipRuPath.isNullOrBlank() || geositeRuPath.isNullOrBlank() -> RoutingPreset.BYPASS_LAN
        else -> RoutingPreset.BYPASS_RU
    }

    /** True when the RU rule-sets were requested but are not available on disk. */
    val ruRuleSetsDegraded: Boolean =
        preset == RoutingPreset.BYPASS_RU && effectivePreset != RoutingPreset.BYPASS_RU

    val bypassesLan: Boolean get() = effectivePreset != RoutingPreset.ALL_VPN
    val bypassesRu: Boolean get() = effectivePreset == RoutingPreset.BYPASS_RU
}
