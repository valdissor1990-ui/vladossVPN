package com.vpnproxy.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory ring buffer на 5000 записей + Timber Tree.
 *
 * Предоставляет Flow<List<LogEntry>> для UI.
 * Ротация: при превышении 5000 — удаляются самые старые.
 */
@Singleton
class LogCollector @Inject constructor() {

    companion object {
        private const val MAX_ENTRIES = 5000
    }

    private val buffer = ConcurrentLinkedDeque<LogEntry>()
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _filteredLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val filteredLogs: StateFlow<List<LogEntry>> = _filteredLogs.asStateFlow()

    private var currentFilterLevel = LogLevel.TRACE
    private var currentFilterTag: String? = null
    private var currentSearchQuery: String? = null

    fun add(entry: LogEntry) {
        buffer.addLast(entry)
        while (buffer.size > MAX_ENTRIES) buffer.pollFirst()
        _logs.value = buffer.toList()
        applyFilters()
    }

    fun trace(tag: String, message: String) = add(LogEntry(level = LogLevel.TRACE, tag = tag, message = message))
    fun debug(tag: String, message: String) = add(LogEntry(level = LogLevel.DEBUG, tag = tag, message = message))
    fun info(tag: String, message: String, meta: Map<String, Any>? = null) {
        add(LogEntry(level = LogLevel.INFO, tag = tag, message = message + (meta?.let { " | $it" } ?: "")))
    }
    fun warn(tag: String, message: String, meta: Map<String, Any>? = null) {
        add(LogEntry(level = LogLevel.WARN, tag = tag, message = message + (meta?.let { " | $it" } ?: "")))
    }
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        add(LogEntry(
            level = LogLevel.ERROR, tag = tag, message = message,
            stackTrace = throwable?.stackTraceToString()
        ))
    }
    fun fatal(tag: String, message: String, throwable: Throwable? = null) {
        add(LogEntry(
            level = LogLevel.FATAL, tag = tag, message = message,
            stackTrace = throwable?.stackTraceToString()
        ))
    }

    fun setFilter(level: LogLevel?, tag: String?, query: String?) {
        currentFilterLevel = level ?: LogLevel.TRACE
        currentFilterTag = tag
        currentSearchQuery = query
        applyFilters()
    }

    private fun applyFilters() {
        _filteredLogs.value = buffer.toList().filter { entry ->
            entry.level.ordinal >= currentFilterLevel.ordinal &&
            (currentFilterTag == null || entry.tag == currentFilterTag) &&
            (currentSearchQuery.isNullOrBlank() || entry.message.contains(currentSearchQuery!!, ignoreCase = true))
        }
    }

    fun clear() {
        buffer.clear()
        _logs.value = emptyList()
        _filteredLogs.value = emptyList()
    }

    fun getAllEntries(): List<LogEntry> = buffer.toList()
}

/** Timber Tree, перенаправляющий логи в LogCollector. */
class LogCollectorTree(private val collector: LogCollector) : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val level = when (priority) {
            android.util.Log.VERBOSE -> LogLevel.TRACE
            android.util.Log.DEBUG -> LogLevel.DEBUG
            android.util.Log.INFO -> LogLevel.INFO
            android.util.Log.WARN -> LogLevel.WARN
            android.util.Log.ERROR -> LogLevel.ERROR
            else -> LogLevel.FATAL
        }
        collector.add(LogEntry(
            level = level, tag = tag ?: "App", message = message,
            stackTrace = t?.stackTraceToString()
        ))
    }
}
