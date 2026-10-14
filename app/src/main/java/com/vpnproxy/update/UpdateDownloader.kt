package com.vpnproxy.update

import android.content.Context
import com.vpnproxy.logging.LogCollector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Скачивает APK обновления в cacheDir/updates/.
 * Предоставляет Flow прогресса: 0..100 или -1 при ошибке.
 */
@Singleton
class UpdateDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logCollector: LogCollector
) {
    companion object { private const val TAG = "UpdateDownloader" }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    /**
     * Скачивает APK по URL. Возвращает File или null при ошибке.
     * @param onProgress callback 0..100
     */
    suspend fun download(
        url: String,
        fileName: String,
        onProgress: ((Int) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        try {
            _progress.value = 0
            val request = Request.Builder().url(url).build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    logCollector.warn(TAG, "Download failed: HTTP ${resp.code}")
                    _progress.value = -1
                    return@withContext null
                }
                val body = resp.body ?: return@withContext null
                val total = body.contentLength().coerceAtLeast(1L)

                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val out = File(dir, fileName)
                body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var read: Int
                        var sum = 0L
                        while (input.read(buf).also { read = it } > 0) {
                            output.write(buf, 0, read)
                            sum += read
                            val percent = ((sum * 100) / total).toInt().coerceIn(0, 100)
                            _progress.value = percent
                            onProgress?.invoke(percent)
                        }
                    }
                }
                logCollector.info(TAG, "Downloaded APK: ${out.absolutePath} (${out.length()} bytes)")
                out
            }
        } catch (e: Exception) {
            logCollector.error(TAG, "Download error", e)
            _progress.value = -1
            null
        }
    }
}
