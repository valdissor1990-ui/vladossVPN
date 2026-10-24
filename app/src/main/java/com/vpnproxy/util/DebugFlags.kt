package com.vpnproxy.util

/**
 * Временные диагностические флаги. Перед продакшен-релизом выключить.
 */
object DebugFlags {
    /** Пропуск TrafficValidator → сразу Connected (проверка туннеля в браузере). */
    const val SKIP_TRAFFIC_VALIDATION = false
}
