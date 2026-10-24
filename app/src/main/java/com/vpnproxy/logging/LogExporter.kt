package com.vpnproxy.logging

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.vpnproxy.BuildConfig
import com.vpnproxy.core.singbox.DiagnosticSettings
import com.vpnproxy.core.diagnostics.ServerDiagnosticsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LogExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logCollector: LogCollector,
    private val serverDiagnosticsStore: ServerDiagnosticsStore
) {
    companion object { private const val TAG = "LogExporter" }

    private data class Sources(
        val appText: String,
        val nativeText: String,
        val appBytes: Int,
        val appLines: Int,
        val nativeBytes: Int,
        val nativeLines: Int,
        val nativePath: String
    )

    /** Export always contains a header and explicit sections for both sources. */
    fun buildExportText(): String = runCatching {
        val sources = readSources()
        Log.i(TAG, "LogExporter native bytes=${sources.nativeBytes} lines=${sources.nativeLines} path=${sources.nativePath}")
        Log.i(TAG, "LogExporter app bytes=${sources.appBytes} lines=${sources.appLines}")
        logCollector.info(TAG, "LogExporter native bytes=${sources.nativeBytes} lines=${sources.nativeLines} path=${sources.nativePath}")
        logCollector.info(TAG, "LogExporter app bytes=${sources.appBytes} lines=${sources.appLines}")
        buildString {
            append("=== DIAG SESSION HEADER ===\n")
            append(buildSessionHeader(sources))
            append("\n=== APP LOG START ===\n")
            append(sources.appText)
            if (sources.appText.isNotEmpty() && !sources.appText.endsWith("\n")) append("\n")
            append("=== APP LOG END ===\n")
            append("\n=== NATIVE LIBBOX LOG START ===\n")
            append(sources.nativeText)
            if (sources.nativeText.isNotEmpty() && !sources.nativeText.endsWith("\n")) append("\n")
            append("=== NATIVE LIBBOX LOG END ===\n")
            append("\n=== SERVER DIAGNOSTICS JSONL START ===\n")
            append(readServerDiagnostics())
            if (!endsWith("\n")) append("\n")
            append("=== SERVER DIAGNOSTICS JSONL END ===\n")
        }
    }.getOrElse { t ->
        Log.e(TAG, "buildExportText failed", t)
        "=== DIAG SESSION HEADER ===\napp_version=${BuildConfig.VERSION_NAME}\ncommit=${BuildConfig.GIT_COMMIT}\nbuild_type=${BuildConfig.BUILD_TYPE}\ndevice=${Build.MANUFACTURER} ${Build.MODEL}\nandroid_api=${Build.VERSION.SDK_INT}\nexport_error=${t.javaClass.simpleName}: ${t.message}\n=== APP LOG START ===\n=== APP LOG END ===\n=== NATIVE LIBBOX LOG START ===\n=== NATIVE LIBBOX LOG END ===\n"
    }

    /** Writes a test file and returns byte/line metrics for each source. */
    fun checkExport(): String {
        val sources = readSources()
        val content = buildExportText()
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "export_check_$stamp.log.txt")
        file.writeText(content, Charsets.UTF_8)
        val result = "Проверка экспорта: app bytes=${sources.appBytes}, lines=${sources.appLines}; native bytes=${sources.nativeBytes}, lines=${sources.nativeLines}; test file=${file.name}, bytes=${file.length()}"
        logCollector.info(TAG, result)
        return result
    }

    private fun readServerDiagnostics(): String {
        val file = serverDiagnosticsStore.currentJournal() ?: return "[no server diagnostic events recorded]"
        return runCatching { file.readText(Charsets.UTF_8).takeLast(2 * 1024 * 1024) }
            .getOrElse { "[server diagnostics read failed: ${it.javaClass.simpleName}]" }
    }

    private fun readSources(): Sources {
        val appFile = File(context.filesDir, "logs/app.log")
        val appRotated = File(context.filesDir, "logs/app.log.1")
        val appTextRaw = buildString {
            if (appRotated.isFile) append(runCatching { appRotated.readText(Charsets.UTF_8) }.getOrDefault(""))
            if (appFile.isFile) append(runCatching { appFile.readText(Charsets.UTF_8) }.getOrDefault(""))
            if (isEmpty()) append(logCollector.getAllEntries().joinToString("\n") { it.toFormattedString(maskSensitive = true) })
        }.trimEnd()
        // Re-sanitize historical files too: older app/native logs may predate masking.
        val appText = appTextRaw.lineSequence()
            .joinToString("\n") { line -> SensitiveMasker.mask(line) }
            .trimEnd()
        val nativeFile = File(context.filesDir, "logs/libbox.log")
        val nativeText = if (nativeFile.isFile) {
            runCatching { nativeFile.readText(Charsets.UTF_8) }.getOrElse {
                "[native log read failed: ${it.javaClass.simpleName}: ${it.message}]"
            }.lineSequence().filter { it.isNotBlank() }
                .joinToString("\n") { line ->
                    val tagged = if (line.startsWith("[LIBBOX]")) line else "[LIBBOX] $line"
                    SensitiveMasker.mask(tagged)
                }
        } else ""
        return Sources(
            appText = appText,
            nativeText = nativeText.trimEnd(),
            appBytes = appText.toByteArray(Charsets.UTF_8).size,
            appLines = appText.lineSequence().count { it.isNotBlank() },
            nativeBytes = nativeText.toByteArray(Charsets.UTF_8).size,
            nativeLines = nativeText.lineSequence().count { it.isNotBlank() },
            nativePath = nativeFile.absolutePath
        )
    }

    private fun buildSessionHeader(sources: Sources): String {
        val entries = sources.appText.lineSequence().toList()
        fun lastContaining(vararg tokens: String): String =
            entries.lastOrNull { line -> tokens.any { token -> line.contains(token, ignoreCase = true) } } ?: "not recorded"
        fun latestLines(token: String, max: Int = 12): String =
            entries.filter { it.contains(token, ignoreCase = true) }.takeLast(max).joinToString(" ; ").ifBlank { "not recorded" }
        val options = runCatching { DiagnosticSettings.load(context) }.getOrNull()
        return buildString {
            appendLine("app_version=${BuildConfig.VERSION_NAME}")
            appendLine("commit=${BuildConfig.GIT_COMMIT}")
            appendLine("build_type=${BuildConfig.BUILD_TYPE}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android_api=${Build.VERSION.SDK_INT}")
            appendLine("DIAG-SETTINGS dns_mode=${options?.dnsMode ?: "unknown"} tun_stack=${options?.tunStack ?: "unknown"} strict_route=${options?.strictRoute ?: "unknown"} route_final=${options?.routeFinal ?: "unknown"} auto_detect_interface=${options?.autoDetectInterface ?: "unknown"} use_disallow_self_package=${options?.useDisallowSelfPackage ?: "unknown"} passive_probe_enabled=${options?.passiveProbeEnabled ?: "unknown"} freeze_autoswitcher=${options?.freezeAutoSwitcherAfterConnected ?: "unknown"}")
            appendLine("last DIAG-SETTINGS entries: ${latestLines("DIAG-SETTINGS", 8)}")
            appendLine("last DIAG-EFFECTIVE entries: ${latestLines("DIAG-EFFECTIVE", 10)}")
            appendLine("last ConnectionState: ${lastContaining("ConnectionState", "setState(", "STATE:", "state changed")}")
            appendLine("last SelectedServer: ${lastContaining("SelectedServer", "server selected", "ServerSelected")}")
            appendLine("last ServiceReady timestamp: ${lastContaining("ServiceReady")}")
            appendLine("native log present=${sources.nativeBytes > 0} native_lines=${sources.nativeLines} app_lines=${sources.appLines}")
        }
    }

    suspend fun exportToDownloads(): Uri? = withContext(Dispatchers.IO) {
        try {
            val fileName = "vpnproxy_debug_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.log.txt"
            val content = buildExportText()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportViaMediaStore(fileName, content)
            else exportViaFile(fileName, content)
        } catch (t: Throwable) {
            logCollector.error(TAG, "exportToDownloads failed", t)
            null
        }
    }

    private fun exportViaMediaStore(fileName: String, content: String): Uri? {
        return try {
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
        resolver.openOutputStream(uri)?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
        } ?: run {
            resolver.delete(uri, null, null)
            return null
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        logCollector.info(TAG, "Exported to Downloads: $fileName")
        uri
    } catch (t: Throwable) {
        logCollector.error(TAG, "exportViaMediaStore failed", t)
        null
        }
    }

    private fun exportViaFile(fileName: String, content: String): Uri? {
        return try {
        val externalRoot = context.getExternalFilesDir(null) ?: return null
        val dir = File(externalRoot, "logs").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(content, Charsets.UTF_8)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (t: Throwable) {
        logCollector.error(TAG, "exportViaFile failed", t)
        null
        }
    }

    fun buildShareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
