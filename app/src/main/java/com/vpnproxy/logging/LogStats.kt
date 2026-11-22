package com.vpnproxy.logging

/**
 * Snapshot of journal health, rendered by the debug screen and embedded in exports.
 *
 * A professional diagnostic journal must not silently lose information: when the
 * in-memory ring buffer or the pending write buffer overflows, [droppedEntries] and
 * [droppedChars] record exactly how much was discarded so a maintainer can tell the
 * difference between "nothing happened" and "the flood ate the evidence".
 */
data class LogStats(
    val total: Int = 0,
    val byLevel: Map<LogLevel, Int> = emptyMap(),
    val droppedEntries: Long = 0L,
    val droppedChars: Long = 0L
) {
    fun count(level: LogLevel): Int = byLevel[level] ?: 0

    /** One-line, export-safe summary. */
    fun summary(): String = buildString {
        append("entries=$total")
        LogLevel.entries.forEach { level -> append(" ${level.name.lowercase()}=${count(level)}") }
        append(" dropped_entries=$droppedEntries dropped_chars=$droppedChars")
    }
}
