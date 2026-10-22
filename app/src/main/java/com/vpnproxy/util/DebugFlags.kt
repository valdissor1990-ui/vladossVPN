package com.vpnproxy.util

/**
 * Temporary diagnostic switches. Every behavior change must also be gated by BuildConfig.DEBUG.
 * Cache-file is intentionally not disabled: the pinned libbox 1.14.3-lx.21 schema was not
 * verified to accept experimental.cache_file.enabled=false, and an unknown field would block startup.
 */
object DebugFlags {
    const val DIAGNOSTIC_MODE = true
    const val FREEZE_AFTER_FIRST_CONNECTED = true
    const val SKIP_TRAFFIC_VALIDATION = true
    const val DISABLE_POST_CONNECT_TRAFFIC_FALLBACK = true
    const val DISABLE_SUBSCRIPTION_AUTO_REFRESH_WHEN_VPN_ACTIVE = true
    const val CORE_RESTART_COOLDOWN_MS = 3000L
    const val DISABLE_CACHE_FILE_IF_SUPPORTED = false
    const val PASSIVE_TRAFFIC_PROBE_INTERVAL_MS = 5000L
    const val DEBUG_TUN_STACK_GVISOR = true
    const val DEBUG_STRICT_ROUTE_FALSE = true
    const val DIAG_TUN_STACK: String = "gvisor"
    const val DIAG_STRICT_ROUTE: Boolean = false
}
