package com.vpnproxy.update

import android.content.Context
import com.vpnproxy.logging.LogCollector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/** Downloads an APK to cacheDir/updates and only publishes it after validation. */
@Singleton
class UpdateDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logCollector: LogCollector
) {
    companion object { private const val TAG = "UpdateDownloader" }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    suspend fun download(
        url: String,
        fileName: String,
        expectedSize: Long = 0L,
        onProgress: ((Int) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        val safeName = fileName.substringAfterLast('/').substringAfterLast('\\')
            .takeIf { it.endsWith(".apk", ignoreCase = true) && it.length <= 180 }
            ?: run {
                logCollector.warn(TAG, "Rejected unsafe APK filename")
                return@withContext null
            }
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, safeName)
        val temp = File(dir, "$safeName.part")
        try {
            _progress.value = 0
            temp.delete()
            val request = Request.Builder().url(url).header("Accept", "application/vnd.android.package-archive").build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    logCollector.warn(TAG, "Download failed: HTTP ${resp.code}")
                    return@withContext null
                }
                val body = resp.body ?: run {
                    logCollector.warn(TAG, "Download failed: empty response body")
                    return@withContext null
                }
                val declaredLength = body.contentLength()
                val expected = expectedSize.takeIf { it > 0L } ?: declaredLength
                if (expectedSize > 0L && declaredLength > 0L && expectedSize != declaredLength) {
                    logCollector.warn(TAG, "Asset size mismatch: GitHub=$expectedSize HTTP=$declaredLength")
                    return@withContext null
                }
                var written = 0L
                body.byteStream().use { input ->
                    temp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                            written += count
                            val percent = if (expected > 0L) ((written * 100L) / expected).toInt().coerceIn(0, 99) else 0
                            _progress.value = percent
                            onProgress?.invoke(percent)
                        }
                        output.flush()
                    }
                }
                if (written <= 0L || (expected > 0L && written != expected)) {
                    logCollector.warn(TAG, "Incomplete APK download: expected=$expected actual=$written")
                    return@withContext null
                }
            }

            val validApk = try {
                ZipFile(temp).use { zip ->
                    zip.getEntry("AndroidManifest.xml") != null
                }
            } catch (e: Exception) {
                false
            }
            if (!validApk) {
                logCollector.warn(TAG, "Downloaded file is not a valid APK archive")
                return@withContext null
            }
            if (target.exists() && !target.delete()) {
                logCollector.warn(TAG, "Could not replace previous APK file")
                return@withContext null
            }
            if (!temp.renameTo(target)) {
                logCollector.warn(TAG, "Could not atomically publish downloaded APK")
                return@withContext null
            }
            _progress.value = 100
            onProgress?.invoke(100)
            logCollector.info(TAG, "Validated APK: ${target.name} (${target.length()} bytes)")
            target
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logCollector.error(TAG, "Download error", e)
            _progress.value = -1
            null
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}
