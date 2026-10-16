package com.vpnproxy.logging

/**
 * Необязательный мост для callback-ов libv2ray, которым недоступна Hilt-инъекция.
 */
object LogCollectorHolder {
    @Volatile
    private var instance: LogCollector? = null

    fun install(collector: LogCollector) {
        instance = collector
    }

    fun get(): LogCollector? = instance
}
