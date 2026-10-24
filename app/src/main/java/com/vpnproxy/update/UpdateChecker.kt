package com.vpnproxy.update

import android.content.Context
import com.vpnproxy.BuildConfig
import com.vpnproxy.logging.LogCollector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Проверка обновлений через GitHub Releases API.
 *
 * Не полагается на /releases/latest: при почти одновременной публикации
 * релизов GitHub может вернуть тег с меньшим versionCode. Вместо этого
 * загружаем список опубликованных релизов и выбираем максимальную версию.
 * Supports ETag / If-None-Match caching to avoid traffic on frequent checks.
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val logCollector: LogCollector,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "UpdateChecker"
        private const val API_URL = "https://api.github.com/repos/%s/%s/releases?per_page=100"
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

            http.newCall(requestBuilder.build()).execute().use { resp ->
                val body = when {
                    resp.code == 304 -> {
                        val cachedBody = prefs.getString(KEY_BODY, null)
                        if (cachedBody.isNullOrBlank()) {
                            logCollector.info(TAG, "Update check: 304 but no cached body; retry without ETag")
                            prefs.edit().remove(KEY_ETAG).apply()
                            return@withContext fetchWithoutCache(url)
                        }
                        logCollector.info(TAG, "Update check: 304 Not Modified (ETag cache hit)")
                        cachedBody
                    }
                    !resp.isSuccessful -> {
                        val err = "HTTP ${resp.code}"
                        logCollector.warn(TAG, "Update check failed: $err")
                        return@withContext Result(hasUpdate = false, error = err)
                    }
                    else -> {
                        val responseBody = resp.body?.string()
                            ?: return@withContext Result(hasUpdate = false, error = "Empty response")
                        val etag = resp.header("ETag")
                        if (!etag.isNullOrBlank()) {
                            prefs.edit().putString(KEY_ETAG, etag).putString(KEY_BODY, responseBody).apply()
                        } else {
                            prefs.edit().remove(KEY_ETAG).putString(KEY_BODY, responseBody).apply()
                        }
                        responseBody
                    }
                }

                val release = selectNewestRelease(body)
                if (release == null) {
                    logCollector.warn(TAG, "Update check: no valid published release with APK found")
                    return@withContext Result(hasUpdate = false, error = "No published APK release found")
                }

                val remoteCode = release.extractVersionCode()
                val localCode = BuildConfig.VERSION_CODE
                val hasUpdate = remoteCode > localCode && !release.prerelease && !release.draft
                logCollector.info(
                    TAG,
                    "Update check: local=$localCode remote=$remoteCode tag=${release.tagName} hasUpdate=$hasUpdate"
                )
                Result(hasUpdate = hasUpdate, release = release)
            }
        } catch (e: ConnectException) {
            logCollector.warn(TAG, "Update check unavailable: connection refused/unreachable (${e.message})")
            Result(hasUpdate = false, error = "Network connection failed")
        } catch (e: UnknownHostException) {
            logCollector.warn(TAG, "Update check unavailable: DNS/network unreachable (${e.message})")
            Result(hasUpdate = false, error = "Network unavailable")
        } catch (e: SocketTimeoutException) {
            logCollector.warn(TAG, "Update check timed out: ${e.message}")
            Result(hasUpdate = false, error = "Network timeout")
        } catch (e: IOException) {
            logCollector.warn(TAG, "Update check network I/O failed: ${e.message}")
            Result(hasUpdate = false, error = "Network I/O failed")
        } catch (e: Exception) {
            logCollector.error(TAG, "Update check error", e)
            Result(hasUpdate = false, error = e.message)
        }
    }

    /**
     * Select by versionCode encoded in the release tag, never by API array order.
     * Only releases with an installable direct APK are eligible for the in-app updater.
     */
    private fun selectNewestRelease(body: String): GitHubRelease? =
        json.decodeFromString<List<GitHubRelease>>(body)
            .asSequence()
            .filter { !it.draft && !it.prerelease }
            .filter { it.extractVersionCode() > 0 }
            .filter { it.findApkAsset("direct") != null }
            .maxByOrNull { it.extractVersionCode() }

    private fun fetchWithoutCache(url: String): Result {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "VPNProxy/${BuildConfig.VERSION_NAME}")
            .build()
        return http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = "HTTP ${resp.code}"
                logCollector.warn(TAG, "Update check retry failed: $err")
                return@use Result(hasUpdate = false, error = err)
            }
            val body = resp.body?.string()
                ?: return@use Result(hasUpdate = false, error = "Empty response")
            val etag = resp.header("ETag")
            val editor = prefs.edit().putString(KEY_BODY, body)
            if (!etag.isNullOrBlank()) editor.putString(KEY_ETAG, etag) else editor.remove(KEY_ETAG)
            editor.apply()

            val release = selectNewestRelease(body)
                ?: return@use Result(hasUpdate = false, error = "No published APK release found")
            val remoteCode = release.extractVersionCode()
            val hasUpdate = remoteCode > BuildConfig.VERSION_CODE
            logCollector.info(
                TAG,
                "Update check (uncached): local=${BuildConfig.VERSION_CODE} remote=$remoteCode tag=${release.tagName} hasUpdate=$hasUpdate"
            )
            Result(hasUpdate = hasUpdate, release = release)
        }
    }
}
