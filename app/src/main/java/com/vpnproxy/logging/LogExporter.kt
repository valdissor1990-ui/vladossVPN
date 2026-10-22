package com.vpnproxy.logging

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Экспорт логов:
 * - buildExportText(): маскированный plain-text
 * - exportToDownloads(): MediaStore (API 29+) или FileProvider+внутренняя папка (API 26-28)
 * - buildShareIntent(): Intent для ACTION_SEND
 *
 * Любые ошибки I/O логируются и возвращают null — приложение не падает.
 */
@Singleton
class LogExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logCollector: LogCollector
) {
    companion object { private const val TAG = "LogExporter" }

    /** Формирует маскированный текст всех логов. Никогда не бросает. */
    fun buildExportText(): String {
        return try {
            val appEntries = logCollector.getAllEntries()
                .joinToString("\n") { it.toFormattedString(maskSensitive = true) }
            val nativeLogFile = File(context.filesDir, "logs/libbox.log")
            val nativeEntries = if (nativeLogFile.isFile) {
                runCatching {
                    val tail = java.io.RandomAccessFile(nativeLogFile, "r").use { file ->
                        val length = file.length()
                        val start = (length - 2L * 1024 * 1024).coerceAtLeast(0L)
                        file.seek(start)
                        ByteArray((length - start).toInt()).also { file.readFully(it) }
                    }
                    String(tail, Charsets.UTF_8).lineSequence()
                        .filter { it.isNotBlank() }
                        .takeLast(5000)
                        .joinToString("\n") { "[LIBBOX] $it" }
                }.getOrElse { "[LIBBOX] Failed to read native log: ${it.javaClass.simpleName}: ${it.message}" }
            } else {
                "[LIBBOX] Native log file not created yet: ${nativeLogFile.absolutePath}"
            }
            listOf(appEntries, nativeEntries).filter { it.isNotBlank() }.joinToString("\n")
        } catch (t: Throwable) {
            logCollector.error(TAG, "buildExportText failed", t)
            "Log export failed: ${t.message}\n"
        }
    }

    /**
     * Экспорт в Downloads/.
     * API 29+ → MediaStore.Downloads (без разрешений).
     * API 26–28 → файл в getExternalFilesDir(null)/logs/ + FileProvider URI.
     */
    suspend fun exportToDownloads(): Uri? = withContext(Dispatchers.IO) {
        try {
            val fileName = "vpnproxy_debug_${
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            }.log"
            val content = buildExportText()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportViaMediaStore(fileName, content)
            } else {
                exportViaFile(fileName, content)
            }
        } catch (t: Throwable) {
            logCollector.error(TAG, "exportToDownloads failed", t)
            null
        }
    }

    /** API 29+: пишем в MediaStore.Downloads. */
    private fun exportViaMediaStore(fileName: String, content: String): Uri? {
        return try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, values)
            if (uri == null) {
                logCollector.warn(TAG, "MediaStore insert returned null")
                return null
            }
            resolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
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

    /**
     * API 26–28: пишем во внутреннюю папку приложения (без разрешений).
     * Путь: /storage/emulated/0/Android/data/<pkg>/files/logs/<file>
     * FileProvider root: external-files-path path="logs/" — совпадает 1:1.
     */
    private fun exportViaFile(fileName: String, content: String): Uri? {
        return try {
            val externalRoot = context.getExternalFilesDir(null)
                ?: return null
            val dir = File(externalRoot, "logs").apply { mkdirs() }
            val file = File(dir, fileName)
            file.writeText(content, Charsets.UTF_8)
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (t: Throwable) {
            logCollector.error(TAG, "exportViaFile failed", t)
            null
        }
    }

    /** Intent для шаринга логов через FileProvider. */
    fun buildShareIntent(uri: Uri): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
