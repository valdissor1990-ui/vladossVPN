package com.vpnproxy.subscription

import android.content.Context
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.vpnproxy.core.diagnostics.DeviceIdManager
import com.vpnproxy.core.connection.ConnectionCoordinator
import com.vpnproxy.core.connection.ConnectionEvent
import com.vpnproxy.core.connection.ConnectionState
import com.vpnproxy.core.connection.VpnFailureReason
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubscriptionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val parser: SubscriptionParser,
    private val logCollector: LogCollector,
    private val deviceIdManager: DeviceIdManager,
    private val connectionCoordinator: ConnectionCoordinator
) {
    companion object {
        private const val TAG = "SubRepo"
        private const val PREFS_NAME = "vpnproxy_subscriptions"
        private const val KEY_SUBS = "subscriptions_json"
        private const val CACHE_FILE = "cached_configs.json"
        private const val REFRESH_FRESHNESS_MS = 120_000L
        const val DEFAULT_SUBSCRIPTION_URL =
            "https://gitverse.ru/api/repos/zieng2/wl/raw/branch/master/list_universal.txt"
        const val PRIMARY_URL = DEFAULT_SUBSCRIPTION_URL
        const val MIRROR_URL_GITHUB =
            "https://raw.githubusercontent.com/valdissor1990-ui/vladossVPN/mirror-data/list_universal.txt"
        val FALLBACK_CHAIN = listOf(PRIMARY_URL, MIRROR_URL_GITHUB)
        const val DEFAULT_SUBSCRIPTION_NAME = "Default (zieng2)"
        const val DEFAULT_SUBSCRIPTION_ID = "default-zieng2"

        const val IGARECK_URL =
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt"
        const val IGARECK_NAME = "WhiteList Mobile (igareck)"
        const val IGARECK_ID = "builtin-igareck-wl-mobile"

        const val WHITE_LITE_URL =
            "https://raw.githubusercontent.com/FLAT447/v2ray-lists/main/WHITE_LITE.txt"
        const val WHITE_LITE_NAME = "WHITE_LITE (FLAT447)"
        const val WHITE_LITE_ID = "builtin-flat447-white-lite"

        data class BuiltInSpec(val id: String, val url: String, val name: String)

        val BUILT_IN_SUBSCRIPTIONS: List<BuiltInSpec> = listOf(
            BuiltInSpec(DEFAULT_SUBSCRIPTION_ID, DEFAULT_SUBSCRIPTION_URL, DEFAULT_SUBSCRIPTION_NAME),
            BuiltInSpec(IGARECK_ID, IGARECK_URL, IGARECK_NAME),
            BuiltInSpec(WHITE_LITE_ID, WHITE_LITE_URL, WHITE_LITE_NAME)
        )
        val BUILT_IN_IDS: Set<String> = BUILT_IN_SUBSCRIPTIONS.map { it.id }.toSet()
    }

    @Serializable
    data class ProviderSubscription(
        val id: String,
        val url: String,
        val name: String,
        val tag: String = "",
        val lastUpdated: Long = 0L,
        val trafficUsed: Long = 0L,
        val trafficTotal: Long = 0L,
        val expiresAt: Long = 0L,
        val enabled: Boolean = true
    ) {
        fun displayTag(): String = tag.ifBlank { name }
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val listSerializer = ListSerializer(ProviderSubscription.serializer())
    private val configsSerializer = ListSerializer(CachedConfig.serializer())

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context, PREFS_NAME, masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val _subscriptions = MutableStateFlow<List<ProviderSubscription>>(emptyList())
    val subscriptions: StateFlow<List<ProviderSubscription>> = _subscriptions.asStateFlow()

    private val _allConfigs = MutableStateFlow<List<ParsedConfig>>(emptyList())
    val allConfigs: StateFlow<List<ParsedConfig>> = _allConfigs.asStateFlow()

    private val _lastRefreshUsedCache = MutableStateFlow(false)
    val lastRefreshUsedCache: StateFlow<Boolean> = _lastRefreshUsedCache.asStateFlow()

    private val refreshMutex = Mutex()
    @Volatile private var lastSuccessfulRefreshAtMs = 0L
    private val subscriptionConfigGeneration = AtomicLong(0L)

    private fun invalidateRefreshFreshness() {
        subscriptionConfigGeneration.incrementAndGet()
        lastSuccessfulRefreshAtMs = 0L
    }

    init {
        loadFromPrefs()
        ensureBuiltInSubscriptions()
        val cached = loadConfigsFromCache()
        if (cached.isNotEmpty()) {
            _allConfigs.value = cached.map { it.toParsedConfig() }.distinctBy {
                "${it.providerTag}\u001F${it.connectionKey()}"
            }
            logCollector.info(TAG, "Cold start from cache: ${_allConfigs.value.size}")
        } else {
            logCollector.warn(TAG, "No cached subscription configs available at cold start")
        }
    }

    private fun loadFromPrefs() {
        try {
            val stored = encryptedPrefs.getString(KEY_SUBS, null) ?: return
            val list = json.decodeFromString(listSerializer, stored)
            _subscriptions.value = list
            logCollector.info(TAG, "Loaded ${list.size} subscriptions from prefs")
        } catch (e: Exception) {
            logCollector.error(TAG, "Failed to load subscriptions", e)
        }
    }

    private fun saveToPrefs() {
        try {
            val jsonStr = json.encodeToString(listSerializer, _subscriptions.value)
            encryptedPrefs.edit().putString(KEY_SUBS, jsonStr).apply()
        } catch (e: Exception) {
            logCollector.error(TAG, "Failed to save subscriptions", e)
        }
    }

    /**
     * Adds missing built-in sources while preserving existing subscription settings.
     * A matching URL already saved by the user is not duplicated.
     */
    private fun ensureBuiltInSubscriptions() {
        val current = _subscriptions.value.toMutableList()
        var changed = false
        for (spec in BUILT_IN_SUBSCRIPTIONS) {
            if (current.any { it.id == spec.id || it.url == spec.url }) continue
            current += ProviderSubscription(
                id = spec.id,
                url = spec.url,
                name = spec.name,
                tag = spec.name,
                enabled = true
            )
            changed = true
            logCollector.info(TAG, "Built-in subscription added: " + spec.name)
        }
        if (changed) {
            _subscriptions.value = current
            saveToPrefs()
        }
    }

    suspend fun addSubscription(url: String, name: String, tag: String = ""): Boolean = withContext(Dispatchers.IO) {
        try {
            val id = java.util.UUID.randomUUID().toString()
            val sub = ProviderSubscription(
                id = id, url = url, name = name, tag = tag.ifBlank { name }
            )
            _subscriptions.value = _subscriptions.value + sub
            invalidateRefreshFreshness()
            saveToPrefs()
            logCollector.info(TAG, "Subscription added: $name tag=${sub.tag}", mapOf("url" to url))
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            logCollector.error(TAG, "Failed to add subscription", e)
            false
        }
    }

    fun updateSubscriptionTag(id: String, tag: String) {
        invalidateRefreshFreshness()
        _subscriptions.value = _subscriptions.value.map {
            if (it.id == id) it.copy(tag = tag.ifBlank { it.name }) else it
        }
        saveToPrefs()
    }

    fun removeSubscription(id: String) {
        if (id in BUILT_IN_IDS) {
            logCollector.warn(TAG, "Cannot remove built-in subscription: " + id)
            return
        }
        _subscriptions.value = _subscriptions.value.filter { it.id != id }
        invalidateRefreshFreshness()
        saveToPrefs()
    }

    /** Built-in sources can be disabled without deleting their saved entry. */
    fun setSubscriptionEnabled(id: String, enabled: Boolean) {
        invalidateRefreshFreshness()
        _subscriptions.value = _subscriptions.value.map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }
        saveToPrefs()
    }

    suspend fun refreshAll(skipIfFresh: Boolean = false): Int = refreshMutex.withLock {
        val now = System.currentTimeMillis()
        val refreshAgeMs = now - lastSuccessfulRefreshAtMs
        if (skipIfFresh && lastSuccessfulRefreshAtMs > 0L && refreshAgeMs in 0 until REFRESH_FRESHNESS_MS) {
            val cachedCount = _allConfigs.value.distinctBy { it.connectionKey() }.size
            logCollector.info(TAG, "Refresh skipped: successful refresh is still fresh; configs=$cachedCount")
            return@withLock cachedCount
        }
        val generationAtStart = subscriptionConfigGeneration.get()
        val refreshResult = withContext(Dispatchers.IO) {
            _lastRefreshUsedCache.value = false
            val subs = _subscriptions.value.filter { it.enabled }
            var totalConfigs = 0
            val allConfigsList = mutableListOf<ParsedConfig>()
            var anyNetworkSuccess = false

            for (sub in subs) {
                try {
                    val configs = fetchAndParse(sub)
                    allConfigsList.addAll(configs)
                    totalConfigs += configs.size
                    anyNetworkSuccess = true
                    val updated = sub.copy(lastUpdated = System.currentTimeMillis())
                    _subscriptions.value = _subscriptions.value.map {
                        if (it.id == sub.id) updated else it
                    }
                    logCollector.info(TAG, "Refreshed ${sub.name}: ${configs.size} configs")
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (e: Exception) {
                    logCollector.warn(TAG, "Fetch failed for ${sub.name}: ${e.message}")
                    val cached = loadConfigsFromCache().filter { it.providerTag == sub.name }
                    if (cached.isNotEmpty()) {
                        if (sub.url == PRIMARY_URL) _lastRefreshUsedCache.value = true
                        logCollector.info(TAG, "Using cached configs for ${sub.name}: ${cached.size}")
                        allConfigsList.addAll(cached.map { it.toParsedConfig() })
                        totalConfigs += cached.size
                    }
                }
            }

            // Preserve attribution per provider while removing duplicate profiles within each
            // provider. Global scanning/counts use connectionKey() without providerTag.
            val providerUniqueConfigs = allConfigsList.distinctBy {
                "${it.providerTag}\u001F${it.connectionKey()}"
            }
            val globallyUniqueCount = providerUniqueConfigs.distinctBy {
                it.connectionKey()
            }.size

            if (providerUniqueConfigs.isNotEmpty() && anyNetworkSuccess) {
                saveConfigsToCache(providerUniqueConfigs)
                logCollector.info(
                    TAG,
                    "Updated cached_configs.json: raw=$totalConfigs providerUnique=${providerUniqueConfigs.size} globalUnique=$globallyUniqueCount"
                )
            }

            _allConfigs.value = providerUniqueConfigs
            saveToPrefs()
            logCollector.info(
                TAG,
                "Refresh complete: raw=$totalConfigs providerUnique=${providerUniqueConfigs.size} globalUnique=$globallyUniqueCount"
            )
            globallyUniqueCount to anyNetworkSuccess
        }
        if (refreshResult.second && subscriptionConfigGeneration.get() == generationAtStart) {
            lastSuccessfulRefreshAtMs = System.currentTimeMillis()
        }
        refreshResult.first
    }

    suspend fun fetchWithFallback(sub: ProviderSubscription): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val sources = if (sub.url == PRIMARY_URL) FALLBACK_CHAIN else listOf(sub.url)
            var lastFailure: Exception? = null
            var sawTransportFailure = false
            for (sourceUrl in sources) {
                try {
                    val hwid = deviceIdManager.getHwid()
                    val cacheKey = sourceUrl.hashCode().toUInt().toString(16)
                    val bodyKey = "subscription_body_$cacheKey"
                    val etagKey = "subscription_etag_$cacheKey"
                    val cachedBody = encryptedPrefs.getString(bodyKey, null)
                    val cachedEtag = encryptedPrefs.getString(etagKey, null)
                    val requestBuilder = Request.Builder()
                        .url(sourceUrl)
                        .header("User-Agent", "v2rayNG/1.8.5")
                        .header("x-hwid", hwid)
                        .header("x-device-os", "Android")
                        .header("x-ver-os", Build.VERSION.RELEASE ?: "")
                        .header(
                            "x-device-model",
                            listOf(Build.MANUFACTURER, Build.MODEL)
                                .filter { !it.isNullOrBlank() }
                                .distinct()
                                .joinToString(" ")
                                .ifBlank { "Android device" }
                        )
                    if (!cachedEtag.isNullOrBlank() && !cachedBody.isNullOrBlank()) {
                        requestBuilder.header("If-None-Match", cachedEtag)
                    }
                    runInterruptible(Dispatchers.IO) {
                        httpClient.newCall(requestBuilder.build()).execute()
                    }.use { response ->
                        if (response.header("x-hwid-not-supported").equals("true", ignoreCase = true)) {
                            logCollector.error(
                                TAG,
                                "Subscription server rejected or ignored x-hwid; check panel HWID contract"
                            )
                            throw IOException("Subscription server rejected HWID (x-hwid-not-supported)")
                        }
                        if (response.header("x-hwid-max-devices-reached").equals("true", ignoreCase = true) ||
                            response.header("x-hwid-limit").equals("true", ignoreCase = true)
                        ) {
                            logCollector.warn(TAG, "Subscription server reports HWID device limit reached")
                            throw IOException("HWID device limit reached; remove an old device in the subscription panel")
                        }
                        if (response.code == 304) {
                            if (cachedBody.isNullOrBlank()) {
                                throw IOException("HTTP 304 received without a cached subscription body")
                            }
                            if (parser.parse(cachedBody, providerTag = sub.name).isEmpty()) {
                                throw IOException("Cached subscription body contains no valid server configs")
                            }
                            logCollector.info(TAG, "Subscription unchanged (ETag 304): $sourceUrl")
                            return@withContext cachedBody to sourceUrl
                        }
                        if (!response.isSuccessful) {
                            logCollector.warn(TAG, "Subscription fetch failed. Code: ${response.code}")
                            throw IOException("HTTP ${response.code}")
                        }
                        response.header("subscription-userinfo")?.let { info -> parseUserInfo(info, sub.id) }
                        val body = response.body?.string() ?: throw IOException("Empty response body")
                        if (body.isBlank()) {
                            logCollector.warn(TAG, "Subscription fetch failed. Code: ${response.code} (empty body)")
                            throw IOException("Empty response body")
                        }
                        if (parser.parse(body, providerTag = sub.name).isEmpty()) {
                            throw IOException("Subscription source returned no valid server configs")
                        }
                        val newEtag = response.header("ETag")
                        val editor = encryptedPrefs.edit().putString(bodyKey, body)
                        if (!newEtag.isNullOrBlank()) editor.putString(etagKey, newEtag)
                        else editor.remove(etagKey)
                        editor.apply()
                        logCollector.info(TAG, "Subscription source OK: $sourceUrl")
                        return@withContext body to sourceUrl
                    }
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (e: Exception) {
                    lastFailure = e
                    if (isTransportFailure(e)) sawTransportFailure = true
                    logCollector.warn(TAG, "Source failed: $sourceUrl — ${e.message}")
                }
            }
            val vpnState = connectionCoordinator.state.value
            val vpnActive = vpnState is ConnectionState.Connecting ||
                vpnState is ConnectionState.Verifying ||
                vpnState is ConnectionState.Connected ||
                vpnState is ConnectionState.Reconnecting
            if (vpnActive) {
                logCollector.warn(TAG, "SubRepo refresh failed while VPN active — ignored; transportFailure=$sawTransportFailure")
            }
            throw IOException("All sources failed", lastFailure)
        }

    private fun isTransportFailure(error: Throwable?): Boolean {
        var current = error
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            if (current is ConnectException ||
                current is SocketTimeoutException ||
                current is TimeoutException
            ) return true
            current = current.cause
        }
        return false
    }

    suspend fun fetchAndParse(sub: ProviderSubscription): List<ParsedConfig> {
        val (body, sourceUrl) = fetchWithFallback(sub)
        val parsed = parser.parse(body, providerTag = sub.name)
        logCollector.info(TAG, "Fetch succeeded from $sourceUrl, bytes=${body.toByteArray(Charsets.UTF_8).size}, parsedConfigs=${parsed.size}, contentType=subscription-response")
        return parsed
    }

    private fun parseUserInfo(info: String, subId: String) {
        try {
            val parts = info.split(";").mapNotNull { part ->
                val kv = part.trim().split("=", limit = 2)
                if (kv.size == 2) kv[0] to (kv[1].toLongOrNull() ?: 0L) else null
            }.toMap()
            val upload = parts["upload"] ?: 0L
            val download = parts["download"] ?: 0L
            val total = parts["total"] ?: 0L
            val expire = parts["expire"] ?: 0L
            _subscriptions.value = _subscriptions.value.map {
                if (it.id == subId) it.copy(
                    trafficUsed = upload + download,
                    trafficTotal = total,
                    expiresAt = expire * 1000
                ) else it
            }
        } catch (_: Exception) { }
    }

    /** Configs deduplicated per provider, preserving provider attribution for subscription UI. */
    fun getAllConfigs(): List<ParsedConfig> = _allConfigs.value

    fun getCachedConfigs(): List<ParsedConfig> =
        loadConfigsFromCache().map { it.toParsedConfig() }.distinctBy {
            "${it.providerTag}\u001F${it.connectionKey()}"
        }

    @Serializable
    private data class CachedConfig(
        val uuid: String,
        val address: String,
        val port: Int,
        val transport: String,
        val security: String,
        val sni: String? = null,
        val path: String? = null,
        val host: String? = null,
        val mode: String? = null,
        val fingerprint: String? = null,
        val publicKey: String? = null,
        val shortId: String? = null,
        val flow: String? = null,
        val name: String,
        val providerTag: String = "",
        val password: String? = null,
        val insecure: Boolean = false,
        val alpn: List<String> = emptyList(),
        val obfsType: String? = null,
        val obfsPassword: String? = null,
        val pinSHA256: String? = null,
        val congestionControl: String = "bbr",
        val extra: String? = null
    ) {
        fun toParsedConfig() = ParsedConfig(
            uuid = uuid, address = address, port = port, transport = transport, security = security,
            sni = sni, path = path, host = host, mode = mode, fingerprint = fingerprint,
            publicKey = publicKey, shortId = shortId, flow = flow, name = name, providerTag = providerTag,
            password = password, insecure = insecure, alpn = alpn, obfsType = obfsType,
            obfsPassword = obfsPassword, pinSHA256 = pinSHA256,
            congestionControl = congestionControl, extra = extra
        )
        companion object {
            fun from(c: ParsedConfig) = CachedConfig(
                uuid = c.uuid, address = c.address, port = c.port, transport = c.transport, security = c.security,
                sni = c.sni, path = c.path, host = c.host, mode = c.mode, fingerprint = c.fingerprint,
                publicKey = c.publicKey, shortId = c.shortId, flow = c.flow, name = c.name, providerTag = c.providerTag,
                password = c.password, insecure = c.insecure, alpn = c.alpn, obfsType = c.obfsType,
                obfsPassword = c.obfsPassword, pinSHA256 = c.pinSHA256,
                congestionControl = c.congestionControl, extra = c.extra
            )
        }
    }

    private fun saveConfigsToCache(configs: List<ParsedConfig>) {
        try {
            val cached = configs.map { CachedConfig.from(it) }
            val jsonStr = json.encodeToString(configsSerializer, cached)
            File(context.filesDir, CACHE_FILE).writeText(jsonStr)
        } catch (e: Exception) {
            logCollector.error(TAG, "Cache save failed", e)
        }
    }

    private fun loadConfigsFromCache(): List<CachedConfig> {
        return try {
            val f = File(context.filesDir, CACHE_FILE)
            if (!f.exists()) return emptyList()
            json.decodeFromString(configsSerializer, f.readText())
        } catch (e: Exception) {
            logCollector.error(TAG, "Cache load failed", e)
            emptyList()
        }
    }
}
