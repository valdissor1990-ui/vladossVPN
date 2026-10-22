package com.vpnproxy.util

/**
 * Временные диагностические флаги. Перед продакшен-релизом выключить.
 */
object DebugFlags {
    /** Пропуск TrafficValidator при ServiceReady → сразу Connected. */
    const val SKIP_TRAFFIC_VALIDATION = true

    /** Не слать CoreError / fallback из post-connect мониторов (в т.ч. SubRepo). */
    const val DISABLE_POST_CONNECT_TRAFFIC_FALLBACK = true

    /** Не auto-refresh подписки, пока VPN в Connecting/Verifying/Connected/Reconnecting. */
    const val DISABLE_SUBSCRIPTION_AUTO_REFRESH_WHEN_VPN_ACTIVE = true

    /** Минимальная пауза после stopCore перед следующим startCore (ms). */
    const val CORE_RESTART_COOLDOWN_MS = 3000L

    /**
     * experimental.cache_file.enabled=false — НЕ включаем:
     * не уверены в поддержке schema libbox 1.14.3-lx.21 (риск unknown field).
     */
    const val DISABLE_CACHE_FILE_IF_SUPPORTED = false

    /** Интервал пассивного traffic probe после Connected (не влияет на state). */
    const val PASSIVE_TRAFFIC_PROBE_INTERVAL_MS = 10_000L

    /** TUN stack=gvisor вместо system (diagnostic). */
    const val DEBUG_TUN_STACK_GVISOR = true

    /** strict_route=false (diagnostic). */
    const val DEBUG_STRICT_ROUTE_FALSE = true
}
