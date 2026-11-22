package com.vpnproxy.core.singbox

import android.content.Context
import com.vpnproxy.logging.LogCollectorHolder
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Local copies of the sing-box rule-sets used by [RoutingPreset.BYPASS_RU].
 *
 * The core references them as `type: local` rule-sets, so the tunnel never depends on a
 * successful GitHub download at connect time: files are fetched when the user opts into the
 * preset, and a failed download degrades to `bypass_lan` instead of blocking the VPN.
 */
object RuleSetStore {
    private const val TAG = "RuleSetStore"

    const val GEOSITE_URL =
        "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ru.srs"
    const val GEOIP_URL =
        "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-ru.srs"

    /** Smallest plausible `.srs` binary; guards against HTML error pages and empty bodies. */
    private const val MIN_VALID_BYTES = 1024
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun directory(context: Context): File = File(context.filesDir, "rulesets")

    fun geoipFile(context: Context): File = File(directory(context), "geoip-ru.srs")

    fun geositeFile(context: Context): File = File(directory(context), "geosite-category-ru.srs")

    fun isReady(context: Context): Boolean =
        isValidFile(geoipFile(context)) && isValidFile(geositeFile(context))

    /** True when both files exist but are older than the refresh window. */
    fun isStale(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val files = listOf(geoipFile(context), geositeFile(context))
        if (!files.all(::isValidFile)) return false
        return files.any { now - it.lastModified() > MAX_AGE_MS }
    }

    /** True when the payload is a sing-box binary rule-set (`SRS` magic). */
    internal fun isValidRuleSetPayload(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.size < MIN_VALID_BYTES) return false
        return bytes[0] == 'S'.code.toByte() &&
            bytes[1] == 'R'.code.toByte() &&
            bytes[2] == 'S'.code.toByte()
    }

    private fun isValidFile(file: File): Boolean {
        if (!file.isFile || file.length() < MIN_VALID_BYTES) return false
        return runCatching { isValidRuleSetPayload(file.readBytes()) }.getOrDefault(false)
    }

    /**
     * Downloads both rule-sets unless valid non-stale copies are already present.
     *
     * Downloading is all-or-nothing per file: a temporary file is validated before it replaces
     * the previous copy, so a half-written or HTML response can never become the active rule-set.
     */
    suspend fun ensure(
        context: Context,
        force: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!force && isReady(context) && !isStale(context)) return@withContext Result.success(Unit)
        val targets = listOf(
            GeoFile(GEOSITE_URL, geositeFile(context)),
            GeoFile(GEOIP_URL, geoipFile(context))
        )
        for (target in targets) {
            if (!force && isValidFile(target.file) && !isStale(context)) continue
            try {
                download(target)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (e: Exception) {
                LogCollectorHolder.get()?.warn(TAG, "Rule-set download failed: ${target.file.name}: ${e.message}")
                return@withContext Result.failure(
                    IOException("Не удалось загрузить ${target.file.name}: ${e.message}", e)
                )
            }
        }
        Result.success(Unit)
    }

    private suspend fun download(target: GeoFile) {
        val bytes = runInterruptible(Dispatchers.IO) {
            client.newCall(
                Request.Builder()
                    .url(target.url)
                    .header("User-Agent", "vladossVPN")
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body?.bytes() ?: throw IOException("empty body")
            }
        }
        if (!isValidRuleSetPayload(bytes)) {
            throw IOException("response is not a sing-box rule-set")
        }
        val directory = target.file.parentFile ?: throw IOException("no parent directory")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create ${directory.path}")
        val temp = File(directory, "${target.file.name}.tmp")
        try {
            temp.writeBytes(bytes)
            if (!isValidFile(temp)) throw IOException("written rule-set failed validation")
            if (!temp.renameTo(target.file)) {
                target.file.delete()
                if (!temp.renameTo(target.file)) throw IOException("cannot replace ${target.file.name}")
            }
            LogCollectorHolder.get()?.info(TAG, "Rule-set ready: ${target.file.name} (${bytes.size} bytes)")
        } finally {
            temp.delete()
        }
    }

    private data class GeoFile(val url: String, val file: File)
}
