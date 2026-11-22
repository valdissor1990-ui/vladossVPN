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

        // SimpleDateFormat is not thread-safe and is created for every rendered line;
        // one instance per thread avoids both the allocation churn and the races.
        private val isoFormat = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
        }
        private val timeFormat = ThreadLocal.withInitial {
            SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        }
    }

    /** Short wall-clock time for the live list; the ISO form is used in exports. */
    fun timeString(): String = timeFormat.get()!!.format(Date(timestamp))

    fun toFormattedString(maskSensitive: Boolean = true): String {
        val iso = isoFormat.get()!!.format(Date(timestamp))
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
