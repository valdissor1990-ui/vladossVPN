package com.vpnproxy.logging

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String,
    val server: String? = null,
    val rttMs: Long? = null,
    val stackTrace: String? = null,
    /** Unique in-process identity for Compose lists; timestamps and message fields can collide. */
    val id: Long = nextId.incrementAndGet()
) {
    companion object {
        private val nextId = AtomicLong(0L)
    }

    fun toFormattedString(maskSensitive: Boolean = true): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
        val sb = StringBuilder("$iso | ${level.name} | $tag | $message")
        server?.let { sb.append(" | server=$it") }
        rttMs?.let { sb.append(" | rtt=${it}ms") }
        // Throwable messages and stack traces can contain serialized configs or credentials.
        stackTrace?.let { sb.append("\n$it") }
        val formatted = sb.toString()
        return if (maskSensitive) SensitiveMasker.mask(formatted) else formatted
    }
}

enum class LogLevel { TRACE, DEBUG, INFO, WARN, ERROR, FATAL }
