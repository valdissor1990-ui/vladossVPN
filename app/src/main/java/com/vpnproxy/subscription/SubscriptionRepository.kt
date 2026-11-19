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
        private const val KEY_DEVICE_METADATA_CONSENT = "device_metadata_consent_v1"
        private const val CACHE_FILE = "cached_configs.json"
        private const val KEY_CONFIG_CACHE = "cached_configs_json"
        // IDs, URLs and labels used by older bundled feeds. They are removed once
        // during migration; no bundled source is installed or refreshed afterwards.
        private val REMOVED_BUNDLED_IDS = setOf(
            "default-zieng2",
            "builtin-flat447-white-lite",
            "builtin-solovyov-base64",
            "builtin-solovyov-random",
            "builtin-igareck-wl-mobile"
        )
        private val REMOVED_BUNDLED_URLS = setOf(
            "https://gitverse.ru/api/repos/zieng2/wl/raw/branch/master/list_universal.txt",
            "https://raw.githubusercontent.com/valdissor1990-ui/vladossVPN/mirror-data/list_universal.txt",
            "https://raw.githubusercontent.com/FLAT447/v2ray-lists/main/WHITE_LITE.txt",
            "https://final-sorted-base64.solovyov-jenya2004.workers.dev/",
            "https://random.solovyov-jenya2004.workers.dev/",
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/Vless-Reality-White-Lists-Rus-Mobile.txt"
        )
        private val REMOVED_BUNDLED_NAMES = setOf(
            "Mobile Base64 (solovyov)",
            "Mobile Random (solovyov)",
            "WhiteList Mobile (igareck)"
        )
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

    data class ProviderImportSummary(
        val providerName: String,
        val source: String,
        val format: String,
        val candidateCount: Int?,
        val acceptedCount: Int,
        val rejectedCount: Int?,
        val duplicateCount: Int,
        val protocolCounts: Map<String, Int>,
        val rejectionReasons: Map<String, Int>,
        val failureCode: String? = null
    )

    data class SubscriptionImportReport(
        val providers: List<ProviderImportSummary>,
        val candidateCount: Int?,
        val acceptedCount: Int,
        val rejectedCount: Int?,
        val providerDuplicates: Int,
        val globalDuplicates: Int,
        val globallyUniqueCount: Int,
        val usedCache: Boolean,
        val completedAt: Long
    )

    private data class SubscriptionFetchResult(
        val body: String,
        val fromEtagCache: Boolean
    )

private val _deviceMetadataConsent = MutableStateFlow(
        encryptedPrefs.getBoolean(KEY_DEVICE_METADATA_CONSENT, false)
    )
    val deviceMetadataConsent: StateFlow<Boolean> = _deviceMetadataConsent.asStateFlow()

    /**
     * Explicit opt-in for sending the subscription provider a hashed device identifier,
     * device model and Android version. Disabled by default and stored encrypted.
     */
    fun setDeviceMetadataConsent(enabled: Boolean) {
        val saved = encryptedPrefs.edit()
            .putBoolean(KEY_DEVICE_METADATA_CONSENT, enabled)
            .commit()
        if (saved) {
            _deviceMetadataConsent.value = enabled
        } else {
            logCollector.warn(TAG, "Could not persist device metadata consent")
        }
    }

        private val _subscriptions = MutableStateFlow<List<ProviderSubscription>>(emptyList())
    val subscriptions: StateFlow<List<ProviderSubscription>> = _subscriptions.asStateFlow()

    private val _allConfigs = MutableStateFlow<List<ParsedConfig>>(emptyList())
    val allConfigs: StateFlow<List<ParsedConfig>> = _allConfigs.asStateFlow()

    private val _lastRefreshUsedCache = MutableStateFlow(false)
    val lastRefreshUsedCache: StateFlow<Boolean> = _lastRefreshUsedCache.asStateFlow()

    private val _lastImportReport = MutableStateFlow<SubscriptionImportReport?>(null)
    val lastImportReport: StateFlow<SubscriptionImportReport?> = _lastImportReport.asStateFlow()

    private val refreshMutex = Mutex()

    init {
        loadFromPrefs()
        removeBundledSubscriptions()
        val cached = loadConfigsFromCache()
        if (cached.isNotEmpty()) {
            val retainedCached = cached.filterNot { it.providerTag in REMOVED_BUNDLED_NAMES }
            _allConfigs.value = retainedCached.map { it.toParsedConfig() }.distinctBy {
                "${it.providerTag}\u001F${it.connectionKey()}"
            }
            if (retainedCached.size != cached.size) saveConfigsToCache(_allConfigs.value)
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

    /** Remove all bundled feeds from existing installations while preserving user-added sources. */
    private fun removeBundledSubscriptions() {
        val current = _subscriptions.value
        val retained = current.filterNot { sub ->
            sub.id in REMOVED_BUNDLED_IDS ||
                sub.url in REMOVED_BUNDLED_URLS ||
                sub.name in REMOVED_BUNDLED_NAMES
        }
        if (retained != current) {
            _subscriptions.value = retained
            saveToPrefs()
            logCollector.info(TAG, "Removed bundled subscriptions: removed=${current.size - retained.size}; userSources=${retained.size}")
        }
    }

    suspend fun addSubscription(url: String, name: String, tag: String = ""): Boolean = withContext(Dispatchers.IO) {
        try {
            val id = java.util.UUID.randomUUID().toString()
            val sub = ProviderSubscription(
                id = id, url = url, name = name, tag = tag.ifBlank { name }
            )
            _subscriptions.value = _subscriptions.value + sub
            saveToPrefs()
            logCollector.info(TAG, "Subscription added")
            true
        } catch (e: Exception) {
            logCollector.error(TAG, "Failed to add subscription", e)
            false
        }
    }

    fun updateSubscriptionTag(id: String, tag: String) {
        _subscriptions.value = _subscriptions.value.map {
            if (it.id == id) it.copy(tag = tag.ifBlank { it.name }) else it
        }
        saveToPrefs()
    }

    fun removeSubscription(id: String) {
        _subscriptions.value = _subscriptions.value.filter { it.id != id }
        saveToPrefs()
    }

    suspend fun refreshAll(): Int = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            _lastRefreshUsedCache.value = false
            _lastImportReport.value = null
            val subs = _subscriptions.value.filter { it.enabled }
            var totalConfigs = 0
            val allConfigsList = mutableListOf<ParsedConfig>()
            val providerReports = mutableListOf<ProviderImportSummary>()
            var anyNetworkSuccess = false

            for (sub in subs) {
                try {
                    val (outcome, source) = fetchAndParseWithReport(sub)
                    val configs = outcome.configs
                    allConfigsList.addAll(configs)
                    totalConfigs += configs.size
                    anyNetworkSuccess = true
                    providerReports += outcome.report.toProviderSummary(sub.name, source = source)
                    val updated = sub.copy(lastUpdated = System.currentTimeMillis())
                    _subscriptions.value = _subscriptions.value.map {
                        if (it.id == sub.id) updated else it
                    }
                    logCollector.info(TAG, "Refreshed ${sub.name}: ${configs.size} configs")
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (e: Exception) {
                    logCollector.warn(TAG, "Subscription refresh failed: ${e.javaClass.simpleName}")
                    // Keep an independent last-known body per subscription. The aggregate
                    // cache may have been written by an older build after only one provider
                    // succeeded, so it cannot be the sole recovery source.
                    val bodyKey = "subscription_body_${sub.url.hashCode().toUInt().toString(16)}"
                    val cachedOutcome = runCatching {
                        encryptedPrefs.getString(bodyKey, null)
                            ?.takeIf { it.isNotBlank() }
                            ?.let { parser.parseWithReport(it, providerTag = sub.name) }
                    }.getOrNull()
                    val bodyCached = cachedOutcome?.configs.orEmpty()
                    val aggregateCached = if (bodyCached.isEmpty()) {
                        loadConfigsFromCache().filter { it.providerTag == sub.name }.map { it.toParsedConfig() }
                    } else emptyList()
                    val cached = if (bodyCached.isNotEmpty()) bodyCached else aggregateCached
                    if (cached.isNotEmpty()) {
                        _lastRefreshUsedCache.value = true
                        logCollector.info(TAG, "Using per-subscription cache for ${sub.name}: count=${cached.size}; source=${if (bodyCached.isNotEmpty()) "encrypted_body" else "aggregate_cache"}")
                        allConfigsList.addAll(cached)
                        totalConfigs += cached.size
                        val summary = cachedOutcome?.report?.toProviderSummary(sub.name, source = "encrypted_body")
                            ?: ProviderImportSummary(
                                providerName = sub.name,
                                source = "aggregate_cache",
                                format = "aggregate_cache",
                                candidateCount = cached.size,
                                acceptedCount = cached.size,
                                rejectedCount = 0,
                                duplicateCount = (cached.size - cached.distinctBy { it.connectionKey() }.size).coerceAtLeast(0),
                                protocolCounts = cached.groupingBy { it.transport.lowercase(java.util.Locale.ROOT) }.eachCount(),
                                rejectionReasons = emptyMap()
                            )
                        providerReports += summary.copy(failureCode = e.javaClass.simpleName)
                    } else {
                        providerReports += ProviderImportSummary(
                            providerName = sub.name,
                            source = "failed",
                            format = "unknown",
                            candidateCount = null,
                            acceptedCount = 0,
                            rejectedCount = null,
                            duplicateCount = 0,
                            protocolCounts = emptyMap(),
                            rejectionReasons = emptyMap(),
                            failureCode = e.javaClass.simpleName
                        )
                        logCollector.warn(TAG, "No recoverable subscription cache available")
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

            val providerDuplicates = (totalConfigs - providerUniqueConfigs.size).coerceAtLeast(0)
            val globalDuplicates = (providerUniqueConfigs.size - globallyUniqueCount).coerceAtLeast(0)
            val providerAudit = providerUniqueConfigs.groupingBy { it.providerTag }.eachCount().toSortedMap()
            logCollector.info(
                TAG,
                "Subscription count audit: raw=$totalConfigs providerUnique=${providerUniqueConfigs.size} providerDuplicates=$providerDuplicates globalUnique=$globallyUniqueCount globalDuplicates=$globalDuplicates providers=$providerAudit"
            )
            if (providerUniqueConfigs.isNotEmpty()) {
                // The list above already contains fresh responses plus each failed
                // provider's own cached response; persist the complete reconstructed set.
                saveConfigsToCache(providerUniqueConfigs)
                logCollector.info(
                    TAG,
                    "Updated cached_configs.json: raw=$totalConfigs providerUnique=${providerUniqueConfigs.size} globalUnique=$globallyUniqueCount networkSuccess=$anyNetworkSuccess"
                )
            }

            _allConfigs.value = providerUniqueConfigs
            saveToPrefs()
            val allCandidateCountsKnown = providerReports.all { it.candidateCount != null }
            val allRejectionCountsKnown = providerReports.all { it.rejectedCount != null }
            _lastImportReport.value = SubscriptionImportReport(
                providers = providerReports.toList(),
                candidateCount = if (allCandidateCountsKnown) providerReports.sumOf { it.candidateCount ?: 0 } else null,
                acceptedCount = totalConfigs,
                rejectedCount = if (allRejectionCountsKnown) providerReports.sumOf { it.rejectedCount ?: 0 } else null,
                providerDuplicates = providerDuplicates,
                globalDuplicates = globalDuplicates,
                globallyUniqueCount = globallyUniqueCount,
                usedCache = providerReports.any { it.source == "encrypted_body" || it.source == "aggregate_cache" || it.source == "etag_cache" },
                completedAt = System.currentTimeMillis()
            )
            logCollector.info(
                TAG,
                "Refresh complete: raw=$totalConfigs providerUnique=${providerUniqueConfigs.size} globalUnique=$globallyUniqueCount"
            )
            globallyUniqueCount
        }
    }

    private suspend fun fetchWithFallback(sub: ProviderSubscription): SubscriptionFetchResult =
        withContext(Dispatchers.IO) {
            val sources = listOf(sub.url)
            var lastFailure: Exception? = null
            var sawTransportFailure = false
            for (sourceUrl in sources) {
                try {
                    val sendDeviceMetadata = _deviceMetadataConsent.value
                    val cacheKey = sourceUrl.hashCode().toUInt().toString(16)
                    val bodyKey = "subscription_body_$cacheKey"
                    val etagKey = "subscription_etag_$cacheKey"
                    val cachedBody = encryptedPrefs.getString(bodyKey, null)
                    val cachedEtag = encryptedPrefs.getString(etagKey, null)
                    val requestBuilder = Request.Builder()
                        .url(sourceUrl)
                        .header("User-Agent", "v2rayNG/1.8.5")
                    if (sendDeviceMetadata) {
                        requestBuilder
                            .header("x-hwid", deviceIdManager.getHwid())
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
                    }
                    if (!cachedEtag.isNullOrBlank() && !cachedBody.isNullOrBlank()) {
                        requestBuilder.header("If-None-Match", cachedEtag)
                    }
                    runInterruptible(Dispatchers.IO) {
                        httpClient.newCall(requestBuilder.build()).execute()
                    }.use { response ->
                        if (sendDeviceMetadata && response.header("x-hwid-not-supported").equals("true", ignoreCase = true)) {
                            logCollector.error(
                                TAG,
                                "Subscription server rejected or ignored x-hwid; check panel HWID contract"
                            )
                            throw IOException("Subscription server rejected HWID (x-hwid-not-supported)")
                        }
                        if (
                            sendDeviceMetadata &&
                            (response.header("x-hwid-max-devices-reached").equals("true", ignoreCase = true) ||
                                response.header("x-hwid-limit").equals("true", ignoreCase = true))
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
                            logCollector.info(TAG, "Subscription unchanged (ETag 304)")
                            return@withContext SubscriptionFetchResult(cachedBody, fromEtagCache = true)
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
                        logCollector.info(TAG, "Subscription source OK")
                        return@withContext SubscriptionFetchResult(body, fromEtagCache = false)
                    }
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (e: Exception) {
                    lastFailure = e
                    if (isTransportFailure(e)) sawTransportFailure = true
                    logCollector.warn(TAG, "Subscription source failed: ${e.javaClass.simpleName}")
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

    private suspend fun fetchAndParseWithReport(
        sub: ProviderSubscription
    ): Pair<SubscriptionParser.ParseOutcome, String> {
        val fetched = fetchWithFallback(sub)
        val outcome = parser.parseWithReport(fetched.body, providerTag = sub.name)
        logCollector.info(
            TAG,
            "Fetch succeeded: provider=${sub.name}, bytes=${fetched.body.toByteArray(Charsets.UTF_8).size}, parsedConfigs=${outcome.configs.size}, contentType=subscription-response"
        )
        return outcome to if (fetched.fromEtagCache) "etag_cache" else "network"
    }

    suspend fun fetchAndParse(sub: ProviderSubscription): List<ParsedConfig> =
        fetchAndParseWithReport(sub).first.configs

    private fun SubscriptionParser.ParseReport.toProviderSummary(
        providerName: String,
        source: String
    ) = ProviderImportSummary(
        providerName = providerName,
        source = source,
        format = detectedFormat,
        candidateCount = candidateCount,
        acceptedCount = acceptedCount,
        rejectedCount = rejectedCount,
        duplicateCount = duplicateCount,
        protocolCounts = protocolCounts,
        rejectionReasons = rejectionReasons
    )

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
        loadConfigsFromCache().filterNot { it.providerTag in REMOVED_BUNDLED_NAMES }
            .map { it.toParsedConfig() }.distinctBy {
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
            // Server profiles contain UUIDs, passwords and Reality credentials. Keep the
            // complete cache inside the same encrypted preferences store as subscriptions.
            check(encryptedPrefs.edit().putString(KEY_CONFIG_CACHE, jsonStr).commit()) {
                "Encrypted cache commit failed"
            }
        } catch (e: Exception) {
            logCollector.warn(TAG, "Encrypted cache save failed: ${e.javaClass.simpleName}")
        } finally {
            // Never leave the legacy credential-bearing file behind after a save attempt.
            deleteLegacyPlaintextCache()
        }
    }

    private fun loadConfigsFromCache(): List<CachedConfig> {
        return try {
            val encrypted = encryptedPrefs.getString(KEY_CONFIG_CACHE, null)
            if (!encrypted.isNullOrBlank()) {
                val decoded = json.decodeFromString(configsSerializer, encrypted)
                deleteLegacyPlaintextCache()
                return decoded
            }

            // One-time migration for installations that still have the old plaintext file.
            val legacyFile = File(context.filesDir, CACHE_FILE)
            if (!legacyFile.exists()) return emptyList()
            val legacyJson = legacyFile.readText()
            val decoded = try {
                json.decodeFromString(configsSerializer, legacyJson)
            } catch (e: Exception) {
                logCollector.warn(TAG, "Legacy cache rejected during encrypted migration: ${e.javaClass.simpleName}")
                emptyList()
            }

            if (decoded.isNotEmpty()) {
                val stored = encryptedPrefs.edit().putString(KEY_CONFIG_CACHE, legacyJson).commit()
                if (!stored) {
                    logCollector.warn(TAG, "Legacy cache could not be committed to encrypted preferences")
                }
            }
            // Do not retain credentials in plaintext even if the old cache is malformed
            // or the encrypted migration cannot preserve it. The subscription can refresh it.
            deleteLegacyPlaintextCache()
            decoded
        } catch (e: Exception) {
            deleteLegacyPlaintextCache()
            logCollector.warn(TAG, "Encrypted cache load failed: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun deleteLegacyPlaintextCache() {
        val legacyFile = File(context.filesDir, CACHE_FILE)
        if (legacyFile.exists() && !legacyFile.delete()) {
            logCollector.warn(TAG, "Could not delete legacy plaintext server cache")
        }
    }
}
