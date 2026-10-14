package com.vpnproxy.logging

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String,
    val server: String? = null,
    val rttMs: Long? = null,
    val stackTrace: String? = null
) {
    fun toFormattedString(maskSensitive: Boolean = true): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
        val msg = if (maskSensitive) SensitiveMasker.mask(message) else message
        val sb = StringBuilder("$iso | ${level.name} | $tag | $msg")
        server?.let { sb.append(" | server=$it") }
        rttMs?.let { sb.append(" | rtt=${it}ms") }
        stackTrace?.let { sb.append("\n$it") }
        return sb.toString()
    }
}

enum class LogLevel { TRACE, DEBUG, INFO, WARN, ERROR, FATAL }
