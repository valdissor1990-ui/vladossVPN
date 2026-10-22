package com.vpnproxy.util

/**
 * Временные диагностические флаги для debug-билда.
 * Перед продакшен-релизом выставить безопасные значения (false / system / strict_route).
 */
object DebugFlags {
    /** Пропуск TrafficValidator на ServiceReady → сразу Connected. */
    const val SKIP_TRAFFIC_VALIDATION = true

    /** Не слать CoreError/fallback из post-connect subscription transport failures. */
    const val DISABLE_POST_CONNECT_TRAFFIC_FALLBACK = true

    /** Не авто-refresh подписки, пока VPN в Connecting/Verifying/Connected/Reconnecting. */
    const val DISABLE_SUBSCRIPTION_AUTO_REFRESH_WHEN_VPN_ACTIVE = true

    /** Минимальная пауза после stop core перед следующим start (ms). */
    const val CORE_RESTART_COOLDOWN_MS = 3000L

    /** experimental.cache_file.enabled=false — НЕ включаем: схема 1.14.3-lx не проверена. */
    const val DISABLE_CACHE_FILE_IF_SUPPORTED = false

    /** Пассивный probe после Connected; не влияет на state. */
    const val PASSIVE_TRAFFIC_PROBE_INTERVAL_MS = 10_000L

    /** TUN stack=gvisor вместо system (diagnostic). */
    const val DEBUG_TUN_STACK_GVISOR = true

    /** TUN strict_route=false (diagnostic). */
    const val DEBUG_STRICT_ROUTE_FALSE = true
}
