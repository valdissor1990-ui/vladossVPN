package com.vpnproxy.logging

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.vpnproxy.BuildConfig
import com.vpnproxy.core.singbox.DiagnosticSettings
import com.vpnproxy.core.singbox.RoutingSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LogExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logCollector: LogCollector
) {
    companion object {
        private const val TAG = "LogExporter"
        private const val MAX_CLIPBOARD_CHARS = 2 * 1024 * 1024
        private const val MAX_EXPORTED_LINE_CHARS = 256 * 1024
        private const val SUMMARY_LINE_CHARS = 1_200
        private const val NATIVE_MEMORY_FALLBACK_LIMIT = 2_000
    }

    private data class Sources(
        val appFiles: List<File>,
        val nativeFiles: List<File>,
        val appMemoryEntries: List<LogEntry>,
        val nativeMemoryEntries: List<LogEntry>,
        val appBytes: Long,
        val appLines: Long,
        val nativeBytes: Long,
        val nativeLines: Long,
        val nativePath: String,
        val summary: HeaderSummary
    )

    private class HeaderSummary {
        var appLines: Long = 0
        var nativeCallbackLines: Long = 0
        var nativeCallbackBytes: Long = 0
        var lastConnectionState: String = "not recorded"
        var lastSelectedServer: String = "not recorded"
        var lastServiceReady: String = "not recorded"
        val diagSettings = ArrayDeque<String>()
        val diagEffective = ArrayDeque<String>()

        fun accept(line: String) {
            if (line.isBlank()) return
            appLines++
            if (isNativeCallback(line)) {
                nativeCallbackLines++
                nativeCallbackBytes += line.toByteArray(Charsets.UTF_8).size + 1L
            }
            if (containsAny(line, "ConnectionState", "setState(", "STATE:", "state changed")) {
                lastConnectionState = compact(line)
            }
            if (containsAny(line, "SelectedServer", "server selected", "ServerSelected")) {
                lastSelectedServer = compact(line)
            }
            if (line.contains("ServiceReady", ignoreCase = true)) lastServiceReady = compact(line)
            if (line.contains("DIAG-SETTINGS", ignoreCase = true)) addTail(diagSettings, compact(line), 8)
            if (line.contains("DIAG-EFFECTIVE", ignoreCase = true)) addTail(diagEffective, compact(line), 10)
        }

        private fun addTail(target: ArrayDeque<String>, value: String, limit: Int) {
            if (target.size >= limit) target.removeFirst()
            target.addLast(value)
        }

        private fun compact(value: String): String =
            if (value.length <= SUMMARY_LINE_CHARS) value else value.take(SUMMARY_LINE_CHARS) + "…[line shortened]"

        private fun containsAny(line: String, vararg values: String): Boolean =
            values.any { line.contains(it, ignoreCase = true) }
    }

    /** A bounded writer for clipboard export; disk export itself is fully streamed. */
    private class BoundedStringWriter(private val maxChars: Int) : Writer() {
        private val value = StringBuilder(minOf(maxChars, 64 * 1024))
        private var truncated = false

        override fun write(buffer: CharArray, offset: Int, length: Int) {
            if (length <= 0 || truncated) return
            val remaining = maxChars - value.length
            if (length <= remaining) {
                value.append(buffer, offset, length)
            } else {
                if (remaining > 0) value.append(buffer, offset, remaining)
                truncated = true
            }
        }

        override fun flush() = Unit
        override fun close() = Unit

        override fun toString(): String =
            if (truncated) value.toString() + "\n[DIAGNOSTIC EXPORT TRUNCATED: clipboard limit reached]\n"
            else value.toString()
    }

    /** Clipboard callers receive a bounded snapshot; large journals are never materialized in full. */
    fun buildExportText(): String {
        val writer = BoundedStringWriter(MAX_CLIPBOARD_CHARS)
        return try {
            writeExport(writer)
            writer.toString()
        } catch (failure: Throwable) {
            logCollector.error(TAG, "buildExportText failed", failure)
            fallbackErrorText(failure)
        }
    }

    /** Writes a diagnostic export directly to disk without building a full String/ByteArray. */
    fun checkExport(): String {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "export_check_$stamp.log.txt")
        val sources = file.bufferedWriter(Charsets.UTF_8).use { writer -> writeExport(writer) }
        val result = "Проверка экспорта: app bytes=${sources.appBytes}, lines=${sources.appLines}; native bytes=${sources.nativeBytes}, lines=${sources.nativeLines}; test file=${file.name}, bytes=${file.length()}"
        logCollector.info(TAG, result)
        return result
    }

    private fun readSources(): Sources {
        val directory = File(context.filesDir, "logs")
        val appFiles = listOf(File(directory, "app.log.1"), File(directory, "app.log"))
            .filter { it.isFile }
        val nativeFiles = listOf(File(directory, "libbox.log.1"), File(directory, "libbox.log"))
            .filter { it.isFile }
        // The in-memory ring buffer is already bounded to 10,000 entries by LogCollector.
        val appMemory = if (appFiles.isEmpty()) logCollector.getAllEntries() else emptyList()
        val summary = HeaderSummary()
        if (appFiles.isNotEmpty()) {
            appFiles.forEach { file -> scanFile(file) { line -> summary.accept(line) } }
        } else {
            appMemory.forEach { entry -> summary.accept(entry.toFormattedString(maskSensitive = true)) }
        }

        var nativeFileLines = 0L
        var nativeFileBytes = 0L
        nativeFiles.forEach { file ->
            scanFile(file) { line ->
                if (line.isNotBlank()) {
                    nativeFileLines++
                    nativeFileBytes += line.toByteArray(Charsets.UTF_8).size + 1L
                }
            }
        }

        val nativeMemory = if (nativeFiles.isEmpty() && summary.nativeCallbackLines == 0L) {
            logCollector.getAllEntries()
                .filter { it.tag == "LIBBOX" || it.message.contains("[LIBBOX]", ignoreCase = true) }
                .takeLast(NATIVE_MEMORY_FALLBACK_LIMIT)
        } else {
            emptyList()
        }
        val nativeFromMemoryApp = if (nativeFiles.isEmpty() && appFiles.isEmpty() && summary.nativeCallbackLines > 0L) {
            appMemory.filter { it.tag == "LIBBOX" || it.message.contains("[LIBBOX]", ignoreCase = true) }
                .takeLast(NATIVE_MEMORY_FALLBACK_LIMIT)
        } else {
            emptyList()
        }
        val memoryNativeBytes = nativeMemory.sumOf {
            it.toFormattedString(maskSensitive = true).toByteArray(Charsets.UTF_8).size.toLong() + 1L
        }
        val appBytes = if (appFiles.isNotEmpty()) appFiles.sumOf { it.length() } else {
            appMemory.sumOf { it.toFormattedString(maskSensitive = true).toByteArray(Charsets.UTF_8).size.toLong() + 1L }
        }
        val nativeBytes = when {
            nativeFiles.isNotEmpty() -> nativeFiles.sumOf { it.length() }
            summary.nativeCallbackLines > 0L -> summary.nativeCallbackBytes
            else -> memoryNativeBytes
        }
        val nativeLines = when {
            nativeFiles.isNotEmpty() -> nativeFileLines
            summary.nativeCallbackLines > 0L -> summary.nativeCallbackLines
            else -> nativeMemory.size.toLong()
        }
        return Sources(
            appFiles = appFiles,
            nativeFiles = nativeFiles,
            appMemoryEntries = appMemory,
            nativeMemoryEntries = if (nativeMemory.isNotEmpty()) nativeMemory else nativeFromMemoryApp,
            appBytes = appBytes,
            appLines = summary.appLines,
            nativeBytes = nativeBytes,
            nativeLines = nativeLines,
            nativePath = File(directory, "libbox.log").absolutePath,
            summary = summary
        )
    }

    private fun scanFile(file: File, onLine: (String) -> Unit) {
        try {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) onLine(reader.readLine() ?: break)
            }
        } catch (failure: Exception) {
            logCollector.warn(TAG, "Unable to scan journal file ${file.name}: ${failure.javaClass.simpleName}")
        }
    }

    private fun writeExport(writer: Writer): Sources {
        val sources = readSources()
        logCollector.info(TAG, "LogExporter streaming app bytes=${sources.appBytes} lines=${sources.appLines}; native bytes=${sources.nativeBytes} lines=${sources.nativeLines}")
        writer.append("=== DIAG SESSION HEADER ===\n")
        writer.append(buildSessionHeader(sources))
        writer.append("\n=== APP LOG START ===\n")
        if (sources.appFiles.isNotEmpty()) {
            sources.appFiles.forEach { file ->
                writeFileLines(writer, file, nativeOnly = false, addNativePrefix = false)
            }
        } else {
            sources.appMemoryEntries.forEach { entry ->
                writeSafeLine(writer, entry.toFormattedString(maskSensitive = true))
            }
        }
        writer.append("=== APP LOG END ===\n")
        writer.append("\n=== NATIVE LIBBOX LOG START ===\n")
        when {
            sources.nativeFiles.isNotEmpty() -> sources.nativeFiles.forEach { file ->
                writeFileLines(writer, file, nativeOnly = false, addNativePrefix = true)
            }
            sources.summary.nativeCallbackLines > 0L && sources.appFiles.isNotEmpty() -> {
                sources.appFiles.forEach { file -> writeFileLines(writer, file, nativeOnly = true, addNativePrefix = false) }
            }
            sources.nativeMemoryEntries.isNotEmpty() -> sources.nativeMemoryEntries.forEach { entry ->
                writeSafeLine(writer, entry.toFormattedString(maskSensitive = true))
            }
        }
        writer.append("=== NATIVE LIBBOX LOG END ===\n")
        writer.flush()
        return sources
    }

    private fun writeFileLines(
        writer: Writer,
        file: File,
        nativeOnly: Boolean,
        addNativePrefix: Boolean
    ) {
        try {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (nativeOnly && !isNativeCallback(line)) continue
                    if (addNativePrefix && line.isBlank()) continue
                    val output = if (addNativePrefix && !line.startsWith("[LIBBOX]")) "[LIBBOX] $line" else line
                    writeSafeLine(writer, output)
                }
            }
        } catch (failure: Exception) {
            writeSafeLine(writer, "[journal read failed: ${file.name}: ${failure.javaClass.simpleName}]")
        }
    }

    private fun writeSafeLine(writer: Writer, line: String) {
        val masked = SensitiveMasker.mask(line)
        if (masked.length > MAX_EXPORTED_LINE_CHARS) {
            writer.append(masked, 0, MAX_EXPORTED_LINE_CHARS)
            writer.append("…[line truncated]")
        } else {
            writer.append(masked)
        }
        writer.append('\n')
    }

    private fun buildSessionHeader(sources: Sources): String {
        val options = runCatching { DiagnosticSettings.load(context) }.getOrNull()
        val summary = sources.summary
        return buildString {
            appendLine("app_version=${BuildConfig.VERSION_NAME}")
            appendLine("commit=${BuildConfig.GIT_COMMIT}")
            appendLine("build_type=${BuildConfig.BUILD_TYPE}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android_api=${Build.VERSION.SDK_INT}")
            appendLine("DIAG-SETTINGS dns_mode=${options?.dnsMode ?: "unknown"} tun_stack=${options?.tunStack ?: "unknown"} strict_route=${options?.strictRoute ?: "unknown"} route_final=${options?.routeFinal ?: "unknown"} auto_detect_interface=${options?.autoDetectInterface ?: "unknown"} use_disallow_self_package=${options?.useDisallowSelfPackage ?: "unknown"} passive_probe_enabled=${options?.passiveProbeEnabled ?: "unknown"} freeze_autoswitcher=${options?.freezeAutoSwitcherAfterConnected ?: "unknown"}")
            appendLine("route_preset=${runCatching { RoutingSettings.load(context) }.getOrNull()?.let { "${it.preset.id}(effective=${it.effectivePreset.id})" } ?: "unknown"}")
            appendLine("last DIAG-SETTINGS entries: ${summary.diagSettings.joinToString(" ; ").ifBlank { "not recorded" }}")
            appendLine("last DIAG-EFFECTIVE entries: ${summary.diagEffective.joinToString(" ; ").ifBlank { "not recorded" }}")
            appendLine("last ConnectionState: ${summary.lastConnectionState}")
            appendLine("last SelectedServer: ${summary.lastSelectedServer}")
            appendLine("last ServiceReady timestamp: ${summary.lastServiceReady}")
            appendLine("native log present=${sources.nativeBytes > 0} native_lines=${sources.nativeLines} native_callback_lines=${summary.nativeCallbackLines} app_lines=${sources.appLines}")
            appendLine("native log file=${sources.nativePath}; rotated_file=${File(context.filesDir, "logs/libbox.log.1").isFile}")
            appendLine("log_stats app_${logCollector.stats.value.summary()}")
            appendLine("journal_files ${journalFileInfo()}")
        }
    }

    /** Human-readable sizes of persisted journal files, for the header and the UI. */
    fun journalFileInfo(): String {
        val dir = File(context.filesDir, "logs")
        return listOf("app.log", "app.log.1", "libbox.log", "libbox.log.1").joinToString(" ") { name ->
            val file = File(dir, name)
            "$name=${if (file.isFile) "${file.length()}B" else "absent"}"
        }
    }

    suspend fun exportToDownloads(): Uri? = withContext(Dispatchers.IO) {
        try {
            val fileName = "vpnproxy_debug_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.log.txt"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportViaMediaStore(fileName)
            else exportViaFile(fileName)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            logCollector.error(TAG, "exportToDownloads failed", failure)
            null
        }
    }

    private fun exportViaMediaStore(fileName: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: run {
            logCollector.warn(TAG, "MediaStore insert returned null")
            return null
        }
        return try {
            val output = resolver.openOutputStream(uri) ?: throw IOException("MediaStore output stream unavailable")
            OutputStreamWriter(output, Charsets.UTF_8).buffered().use { writer -> writeExport(writer) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            logCollector.info(TAG, "Exported to Downloads: $fileName")
            uri
        } catch (cancelled: CancellationException) {
            resolver.delete(uri, null, null)
            throw cancelled
        } catch (failure: Throwable) {
            resolver.delete(uri, null, null)
            logCollector.error(TAG, "exportViaMediaStore failed", failure)
            null
        }
    }

    private fun exportViaFile(fileName: String): Uri? {
        return try {
            val externalRoot = context.getExternalFilesDir(null) ?: return null
            val dir = File(externalRoot, "logs").apply { mkdirs() }
            val file = File(dir, fileName)
            file.bufferedWriter(Charsets.UTF_8).use { writer -> writeExport(writer) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            logCollector.error(TAG, "exportViaFile failed", failure)
            null
        }
    }

    private fun fallbackErrorText(failure: Throwable): String =
        "=== DIAG SESSION HEADER ===\napp_version=${BuildConfig.VERSION_NAME}\ncommit=${BuildConfig.GIT_COMMIT}\nbuild_type=${BuildConfig.BUILD_TYPE}\ndevice=${Build.MANUFACTURER} ${Build.MODEL}\nandroid_api=${Build.VERSION.SDK_INT}\nexport_error=${failure.javaClass.simpleName}: ${SensitiveMasker.mask(failure.message.orEmpty())}\n=== APP LOG START ===\n=== APP LOG END ===\n=== NATIVE LIBBOX LOG START ===\n=== NATIVE LIBBOX LOG END ===\n"

    private fun isNativeCallback(line: String): Boolean =
        line.contains("[LIBBOX]", ignoreCase = true) || line.contains(" | LIBBOX |", ignoreCase = true)

    fun buildShareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
