package com.vpnproxy.update

import android.content.Context
import com.vpnproxy.BuildConfig
import com.vpnproxy.logging.LogCollector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Проверка обновлений через GitHub Releases API.
 * Supports ETag / If-None-Match caching to avoid traffic on frequent checks.
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val logCollector: LogCollector,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "UpdateChecker"
        private const val API_URL = "https://api.github.com/repos/%s/%s/releases/latest"
        private const val PREFS = "update_checker_prefs"
        private const val KEY_ETAG = "latest_etag"
        private const val KEY_BODY = "latest_body"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    @Serializable
    private data class VersionMeta(
        val versionCode: Int,
        val versionName: String = ""
    )

    data class Result(
        val hasUpdate: Boolean,
        val release: GitHubRelease? = null,
        val error: String? = null
    )

    suspend fun check(): Result = withContext(Dispatchers.IO) {
        try {
            val url = API_URL.format(BuildConfig.GITHUB_OWNER, BuildConfig.GITHUB_REPO)
            val cachedEtag = prefs.getString(KEY_ETAG, null)
            val requestBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "VPNProxy/${BuildConfig.VERSION_NAME}")
            if (!cachedEtag.isNullOrBlank()) {
                requestBuilder.header("If-None-Match", cachedEtag)
            }
            val request = requestBuilder.build()

            http.newCall(request).execute().use { resp ->
                if (resp.code == 304) {
                    val cachedBody = prefs.getString(KEY_BODY, null)
                    if (cachedBody.isNullOrBlank()) {
                        logCollector.info(TAG, "Update check: 304 but no cached body")
                        return@withContext Result(hasUpdate = false)
                    }
                    logCollector.info(TAG, "Update check: 304 Not Modified (ETag cache hit)")
                    val release = json.decodeFromString<GitHubRelease>(cachedBody)
                    val remoteCode = resolveRemoteVersionCode(release)
                    val hasUpdate = remoteCode > BuildConfig.VERSION_CODE && !release.prerelease && !release.draft
                    return@withContext Result(hasUpdate = hasUpdate, release = release)
                }
                if (!resp.isSuccessful) {
                    val err = "HTTP ${resp.code}"
                    logCollector.warn(TAG, "Update check failed: $err")
                    return@withContext Result(hasUpdate = false, error = err)
                }
                val body = resp.body?.string() ?: return@withContext Result(false)
                resp.header("ETag")?.let { etag ->
                    prefs.edit().putString(KEY_ETAG, etag).putString(KEY_BODY, body).apply()
                }
                val release = json.decodeFromString<GitHubRelease>(body)
                val remoteCode = resolveRemoteVersionCode(release)
                val localCode = BuildConfig.VERSION_CODE
                val hasUpdate = remoteCode > localCode && !release.prerelease && !release.draft
                logCollector.info(
                    TAG,
                    "Update check: local=$localCode remote=$remoteCode tag=${release.tagName} hasUpdate=$hasUpdate"
                )
                Result(hasUpdate = hasUpdate, release = release)
            }
        } catch (e: Exception) {
            logCollector.error(TAG, "Update check error", e)
            Result(hasUpdate = false, error = e.message)
        }
    }

    private fun resolveRemoteVersionCode(release: GitHubRelease): Int {
        val fromJson = release.findVersionJsonAsset()?.let { asset ->
            try {
                val jsonText = http.newCall(
                    Request.Builder().url(asset.downloadUrl).build()
                ).execute().use { it.body?.string() }
                if (jsonText != null) {
                    json.decodeFromString<VersionMeta>(jsonText).versionCode
                } else null
            } catch (_: Exception) {
                null
            }
        }
        return fromJson ?: release.extractVersionCode()
    }
}
