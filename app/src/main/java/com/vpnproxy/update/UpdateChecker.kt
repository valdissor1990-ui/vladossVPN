package com.vpnproxy.update

import android.util.Log
import com.vpnproxy.BuildConfig
import com.vpnproxy.logging.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Проверка обновлений через GitHub Releases API.
 *
 * Endpoint: https://api.github.com/repos/{owner}/{repo}/releases/latest
 * Сравнение: versionCode из tag_name vs BuildConfig.VERSION_CODE
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val logCollector: LogCollector
) {
    companion object {
        private const val TAG = "UpdateChecker"
        private const val API_URL = "https://api.github.com/repos/%s/%s/releases/latest"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    data class Result(
        val hasUpdate: Boolean,
        val release: GitHubRelease? = null,
        val error: String? = null
    )

    /**
     * Проверяет обновление. Возвращает Result с информацией о новой версии.
     */
    suspend fun check(): Result = withContext(Dispatchers.IO) {
        try {
            val url = API_URL.format(BuildConfig.GITHUB_OWNER, BuildConfig.GITHUB_REPO)
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "VPNProxy/${BuildConfig.VERSION_NAME}")
                .build()

            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = "HTTP ${resp.code}"
                    logCollector.warn(TAG, "Update check failed: $err")
                    return@withContext Result(hasUpdate = false, error = err)
                }
                val body = resp.body?.string() ?: return@withContext Result(false)
                val release = json.decodeFromString<GitHubRelease>(body)

                val remoteCode = release.extractVersionCode()
                val localCode = BuildConfig.VERSION_CODE
                val hasUpdate = remoteCode > localCode && !release.prerelease && !release.draft

                logCollector.info(TAG, "Update check: local=$localCode remote=$remoteCode hasUpdate=$hasUpdate")
                Result(hasUpdate = hasUpdate, release = release)
            }
        } catch (e: Exception) {
            logCollector.error(TAG, "Update check error", e)
            Result(hasUpdate = false, error = e.message)
        }
    }
}
