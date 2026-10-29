package com.vpnproxy.logging

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton

/** In-memory ring buffer plus continuously persisted app diagnostics. */
@Singleton
class LogCollector @Inject constructor() {
    @Volatile private var appContext: Context? = null

    /** Called by Application before planting Timber trees; no-op persistence in isolated unit tests. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }
    companion object {
        private const val TAG = "LogCollector"
        private const val MAX_ENTRIES = 10_000
        private const val MAX_FILE_BYTES = 16L * 1024L * 1024L
    }

    private val buffer = ConcurrentLinkedDeque<LogEntry>()
    private val bufferLock = Any()
    /** Serializes append and clear so an in-flight append cannot resurrect deleted history. */
    private val persistenceLock = Any()
    private var entryCount = 0
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()
    private val _filteredLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val filteredLogs: StateFlow<List<LogEntry>> = _filteredLogs.asStateFlow()
    private var currentFilterLevel = LogLevel.TRACE
    private var currentFilterTag: String? = null
    private var currentSearchQuery: String? = null


    fun add(entry: LogEntry) {
        synchronized(persistenceLock) {
            // ConcurrentLinkedDeque.size() is O(n); maintain a count under a small lock
            // instead of traversing the whole queue on every log line.
            val entries = synchronized(bufferLock) {
                buffer.addLast(entry)
                entryCount++
                while (entryCount > MAX_ENTRIES) {
                    if (buffer.pollFirst() != null) entryCount-- else break
                }
                // Reuse one snapshot for both StateFlows.
                buffer.toList()
            }
            _logs.value = entries
            // The default unfiltered view can reuse the same snapshot without scanning it.
            applyFilters(entries)
            persistEntry(entry)
        }
    }

    /** Append + flush each entry. Failures never escape into a caller/JNI callback. */
    @Synchronized
    private fun persistEntry(entry: LogEntry) {
        try {
            val baseContext = appContext ?: return
            val file = File(baseContext.filesDir, "logs/app.log")
            file.parentFile?.mkdirs()
            if (file.exists() && file.length() >= MAX_FILE_BYTES) {
                val previous = File(file.parentFile, "app.log.1")
                if (previous.exists()) previous.delete()
                if (!file.renameTo(previous)) Log.w(TAG, "Could not rotate app log ${file.absolutePath}")
            }
            FileOutputStream(File(baseContext.filesDir, "logs/app.log"), true).use { output ->
                output.write((entry.toFormattedString(maskSensitive = true) + "\n").toByteArray(Charsets.UTF_8))
                output.flush()
            }
        } catch (t: Throwable) {
            // Do not log this through Timber: that would recurse into this persistence path.
            Log.w(TAG, "Unable to persist app log entry: ${t.javaClass.simpleName}: ${t.message}")
        }
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
        add(LogEntry(level = LogLevel.ERROR, tag = tag, message = message, stackTrace = throwable?.stackTraceToString()))
    }
    fun fatal(tag: String, message: String, throwable: Throwable? = null) {
        add(LogEntry(level = LogLevel.FATAL, tag = tag, message = message, stackTrace = throwable?.stackTraceToString()))
    }

    fun setFilter(level: LogLevel?, tag: String?, query: String?) {
        currentFilterLevel = level ?: LogLevel.TRACE
        currentFilterTag = tag
        currentSearchQuery = query
        applyFilters(snapshot())
    }

    private fun snapshot(): List<LogEntry> = synchronized(bufferLock) { buffer.toList() }

    private fun applyFilters(entries: List<LogEntry>) {
        // The default view contains every entry. Avoid a full O(n) filter pass on
        // every log line unless the user has actually enabled a filter.
        if (currentFilterLevel == LogLevel.TRACE &&
            currentFilterTag == null &&
            currentSearchQuery.isNullOrBlank()
        ) {
            _filteredLogs.value = entries
            return
        }

        _filteredLogs.value = entries.filter { entry ->
            entry.level.ordinal >= currentFilterLevel.ordinal &&
                (currentFilterTag == null || entry.tag == currentFilterTag) &&
                (currentSearchQuery.isNullOrBlank() || entry.message.contains(currentSearchQuery!!, ignoreCase = true))
        }
    }

    /**
     * Clears the visible buffer and all persisted diagnostic history. Appends are serialized
     * with this operation so an entry already being processed cannot recreate deleted files.
     */
    fun clear() {
        synchronized(persistenceLock) {
            synchronized(bufferLock) {
                buffer.clear()
                entryCount = 0
            }
            _logs.value = emptyList()
            _filteredLogs.value = emptyList()

            val logDirectory = appContext?.let { File(it.filesDir, "logs") }
            val undeleted = logDirectory?.listFiles()
                ?.filter { it.isFile }
                ?.filterNot { file -> runCatching { file.delete() }.getOrDefault(false) }
                .orEmpty()
            undeleted.forEach { file ->
                Log.w(TAG, "Could not delete persisted log file ${file.name}")
            }
        }
    }

    fun getAllEntries(): List<LogEntry> = snapshot()
}

/** Timber Tree forwarding all app-level messages to LogCollector. */
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
