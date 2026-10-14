package com.vpnproxy.logging

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
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
 * - Копирование в буфер обмена (plain text, маскированный)
 * - Скачивание в Downloads/ через MediaStore (Android 10+)
 * - Шаринг через FileProvider
 */
@Singleton
class LogExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logCollector: LogCollector
) {
    /** Формирует маскированный текст всех логов. */
    fun buildExportText(): String {
        return logCollector.getAllEntries()
            .joinToString("\n") { it.toFormattedString(maskSensitive = true) }
    }

    /** Экспорт в Downloads/ через MediaStore (Android 10+) или во внешнее хранилище. */
    suspend fun exportToDownloads(): Uri? = withContext(Dispatchers.IO) {
        val fileName = "vpnproxy_debug_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }.log"
        val content = buildExportText()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportViaMediaStore(fileName, content)
        } else {
            exportViaFile(fileName, content)
        }
    }

    private fun exportViaMediaStore(fileName: String, content: String): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            null
        }
    }

    private fun exportViaFile(fileName: String, content: String): Uri? {
        return try {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "logs")
            dir.mkdirs()
            val file = File(dir, fileName)
            file.writeText(content)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            null
        }
    }

    /** Создаёт Intent для шаринга файла логов. */
    fun buildShareIntent(uri: Uri): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
