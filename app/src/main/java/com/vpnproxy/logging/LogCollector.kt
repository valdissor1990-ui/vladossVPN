package com.vpnproxy.logging

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory ring buffer plus continuously persisted app diagnostics.
 *
 * Performance contract (this used to be a hot path):
 * - [add] is O(1) in the number of buffered entries and never touches the disk on the
 *   caller/JNI thread. The old implementation copied the whole 10k-entry buffer and
 *   opened+flushed the log file for every single line, which cost real CPU, GC and
 *   battery while libbox emitted debug lines.
 * - UI snapshots are coalesced: [logs]/[filteredLogs] update at most once per
 *   [PUBLISH_INTERVAL_MS] instead of once per line.
 * - File persistence is batched: one open/flush per [FLUSH_INTERVAL_MS].
 */
@Singleton
class LogCollector @Inject constructor() {
    @Volatile private var appContext: Context? = null
    @Volatile private var nativeLogClearer: (() -> Unit)? = null

    /** Called by Application before planting Timber trees; no-op persistence in isolated unit tests. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /** Registers the process-wide native writer so Clear logs can discard its pending batch too. */
    fun setNativeLogClearer(clearer: () -> Unit) {
        nativeLogClearer = clearer
    }

    companion object {
        private const val TAG = "LogCollector"
        private const val MAX_ENTRIES = 10_000
        private const val MAX_FILE_BYTES = 16L * 1024L * 1024L
        private const val PUBLISH_INTERVAL_MS = 250L
        private const val FLUSH_INTERVAL_MS = 750L
        /** Bound buffered-but-unwritten text during a log flood; excess lines are dropped. */
        private const val MAX_PENDING_CHARS = 2 * 1024 * 1024
    }

    private val bufferLock = Any()
    private val buffer = ArrayDeque<LogEntry>(1024)
    @Volatile private var publishDirty = false

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()
    private val _filteredLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val filteredLogs: StateFlow<List<LogEntry>> = _filteredLogs.asStateFlow()

    private val _stats = MutableStateFlow(LogStats())
    val stats: StateFlow<LogStats> = _stats.asStateFlow()

    /** Entries evicted from the fixed-size ring buffer because it was full. */
    private val droppedEntries = AtomicLong(0L)
    /** Bytes never persisted because the pending write buffer overflowed. */
    private val droppedChars = AtomicLong(0L)

    @Volatile private var currentFilterLevel = LogLevel.TRACE
    @Volatile private var currentFilterTag: String? = null
    @Volatile private var currentSearchQuery: String? = null

    private val pendingLock = Any()
    private val pendingText = StringBuilder()
    private val fileLock = Any()

    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "log-writer").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    init {
        worker.scheduleWithFixedDelay({ safePublish() }, PUBLISH_INTERVAL_MS, PUBLISH_INTERVAL_MS, TimeUnit.MILLISECONDS)
        worker.scheduleWithFixedDelay({ safeFlush() }, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    fun add(entry: LogEntry) {
        // Sanitize at ingestion, not only during file export: the in-memory StateFlows
        // are also rendered directly by the debug screen and used by log filters.
        val safeEntry = entry.copy(
            tag = SensitiveMasker.mask(entry.tag),
            message = SensitiveMasker.mask(entry.message),
            server = entry.server?.let { SensitiveMasker.mask("server=$it").substringAfter("server=") },
            stackTrace = entry.stackTrace?.let(SensitiveMasker::mask)
        )
        synchronized(bufferLock) {
            if (buffer.size >= MAX_ENTRIES) {
                buffer.pollFirst()
                droppedEntries.incrementAndGet()
            }
            buffer.addLast(safeEntry)
            publishDirty = true
        }
        // Formatting is CPU-only; the disk write happens later on the writer thread.
        val line = safeEntry.toFormattedString(maskSensitive = true)
        synchronized(pendingLock) {
            if (pendingText.length + line.length + 1 <= MAX_PENDING_CHARS) {
                pendingText.append(line).append('\n')
            } else {
                droppedChars.addAndGet(line.length.toLong() + 1L)
            }
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
        // Rare user action: publish immediately so the debug screen reacts without
        // waiting for the next coalesced tick.
        publishPending()
    }

    private fun snapshot(): List<LogEntry> = synchronized(bufferLock) { ArrayList(buffer) }

    /**
     * Publish the current buffer to [logs]/[filteredLogs]. Called from the coalescing
     * timer and synchronously for explicit user actions (filter changes, clear).
     */
    internal fun publishPending() {
        // Reset the flag before snapshotting: an entry added between these two steps is
        // already part of the snapshot, and one added later re-sets the flag for the
        // next tick, so no update can be lost.
        publishDirty = false
        val entries = snapshot()
        _logs.value = entries
        publishStats(entries)
        applyFilters(entries)
    }

    private fun publishStats(entries: List<LogEntry>) {
        val byLevel = HashMap<LogLevel, Int>(LogLevel.entries.size)
        for (entry in entries) {
            byLevel[entry.level] = (byLevel[entry.level] ?: 0) + 1
        }
        _stats.value = LogStats(
            total = entries.size,
            byLevel = byLevel,
            droppedEntries = droppedEntries.get(),
            droppedChars = droppedChars.get()
        )
    }

    private fun safePublish() {
        if (!publishDirty) return
        publishPending()
    }

    /** Append the coalesced buffer to disk. Failures never escape into a caller/JNI callback. */
    private fun safeFlush() {
        val baseContext = appContext ?: return
        // Keep the file lock while taking the pending snapshot and writing it. clear()
        // uses the same lock order, so a flush that started before clear cannot recreate
        // a deleted app.log after the clear operation has completed.
        synchronized(fileLock) {
            val text = synchronized(pendingLock) {
                if (pendingText.isEmpty()) return
                pendingText.toString().also { pendingText.setLength(0) }
            }
            try {
                val directory = File(baseContext.filesDir, "logs")
                if (!directory.exists() && !directory.mkdirs()) return
                val file = File(directory, "app.log")
                if (file.exists() && file.length() >= MAX_FILE_BYTES) {
                    val previous = File(directory, "app.log.1")
                    if (previous.exists()) previous.delete()
                    if (!file.renameTo(previous)) Log.w(TAG, "Could not rotate app log ${file.absolutePath}")
                }
                FileOutputStream(file, true).use { output ->
                    output.write(text.toByteArray(Charsets.UTF_8))
                    output.flush()
                }
            } catch (t: Throwable) {
                // Do not log this through Timber: that would recurse into this persistence path.
                Log.w(TAG, "Unable to persist app log entry: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun applyFilters(entries: List<LogEntry>) {
        val level = currentFilterLevel
        val tag = currentFilterTag
        val query = currentSearchQuery
        // The default view contains every entry. Avoid a full O(n) filter pass on
        // every publish unless the user has actually enabled a filter.
        if (level == LogLevel.TRACE && tag == null && query.isNullOrBlank()) {
            _filteredLogs.value = entries
            return
        }

        _filteredLogs.value = entries.filter { entry ->
            entry.level.ordinal >= level.ordinal &&
                (tag == null || entry.tag == tag) &&
                (query.isNullOrBlank() ||
                    entry.message.contains(query, ignoreCase = true) ||
                    entry.tag.contains(query, ignoreCase = true))
        }
    }

    /**
     * Clears the visible buffer and all persisted diagnostic history. Appends that were
     * queued before the clear are discarded so they cannot recreate deleted files.
     */
    fun clear() {
        // Keep the ring buffer locked until both writers have discarded their pending
        // batches and the files are deleted. Entries added after this block are new logs.
        synchronized(bufferLock) {
            buffer.clear()
            publishDirty = false
            droppedEntries.set(0L)
            droppedChars.set(0L)
            _logs.value = emptyList()
            _filteredLogs.value = emptyList()
            _stats.value = LogStats()

            val logDirectory = appContext?.let { File(it.filesDir, "logs") }
            // Match safeFlush() lock order: fileLock -> pendingLock. NativeLogSink.clear()
            // takes its own writer lock and waits for any in-flight native file write.
            synchronized(fileLock) {
                synchronized(pendingLock) {
                    pendingText.setLength(0)
                }
                runCatching { nativeLogClearer?.invoke() }
                    .onFailure { Log.w(TAG, "Could not clear native log queue: ${it.javaClass.simpleName}") }
                val undeleted = logDirectory?.listFiles()
                    ?.filter { it.isFile }
                    ?.filterNot { file -> runCatching { file.delete() }.getOrDefault(false) }
                    .orEmpty()
                undeleted.forEach { file ->
                    Log.w(TAG, "Could not delete persisted log file ${file.name}")
                }
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
