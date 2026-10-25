package com.vpnproxy.util

/**
 * Diagnostic flags. Traffic validation must remain enabled in all builds.
 */
object DebugFlags {
    /** Пропуск TrafficValidator → сразу Connected (проверка туннеля в браузере). */
    const val SKIP_TRAFFIC_VALIDATION = false
    const val DIAGNOSTIC_MODE = true

    fun diagnosticEnabled(): Boolean = com.vpnproxy.BuildConfig.DEBUG && DIAGNOSTIC_MODE
}
