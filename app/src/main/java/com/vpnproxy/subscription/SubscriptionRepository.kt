package com.vpnproxy.subscription

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubscriptionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val parser: SubscriptionParser,
    private val logCollector: LogCollector
) {
    companion object {
        private const val TAG = "SubRepo"
        private const val PREFS_NAME = "vpnproxy_subscriptions"
        private const val KEY_SUBS = "subscriptions_json"
        private const val CACHE_FILE = "cached_configs.json"

        const val DEFAULT_SUBSCRIPTION_URL =
            "https://gitverse.ru/api/repos/zieng2/wl/raw/branch/master/list_universal.txt"
        const val PRIMARY_URL = DEFAULT_SUBSCRIPTION_URL
        const val MIRROR_URL_GITHUB =
            "https://raw.githubusercontent.com/valdissor1990-ui/vladossVPN/mirror-data/list_universal.txt"
        val FALLBACK_CHAIN = listOf(PRIMARY_URL, MIRROR_URL_GITHUB)
        const val DEFAULT_SUBSCRIPTION_NAME = "Default (zieng2)"
        const val DEFAULT_SUBSCRIPTION_ID = "default-zieng2"
    }

    @Serializable
    data class ProviderSubscription(
        val id: String,
        val url: String,
        val name: String,
        val lastUpdated: Long = 0L,
        val trafficUsed: Long = 0L,
        val trafficTotal: Long = 0L,
        val expiresAt: Long = 0L,
        val enabled: Boolean = true
    )

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
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val _subscriptions = MutableStateFlow<List<ProviderSubscription>>(emptyList())
    val subscriptions: StateFlow<List<ProviderSubscription>> = _subscriptions.asStateFlow()

    private val _allConfigs = MutableStateFlow<List<ParsedConfig>>(emptyList())
    val allConfigs: StateFlow<List<ParsedConfig>> = _allConfigs.asStateFlow()

    private val _lastRefreshUsedCache = MutableStateFlow(false)
    val lastRefreshUsedCache: StateFlow<Boolean> = _lastRefreshUsedCache.asStateFlow()

    private val refreshMutex = Mutex()

    init {
        loadFromPrefs()
        ensureDefaultSubscription()
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

    private fun ensureDefaultSubscription() {
        val hasDefault = _subscriptions.value.any { it.url == DEFAULT_SUBSCRIPTION_URL }
        if (!hasDefault) {
            val sub = ProviderSubscription(
                id = DEFAULT_SUBSCRIPTION_ID,
                url = DEFAULT_SUBSCRIPTION_URL,
                name = DEFAULT_SUBSCRIPTION_NAME,
                enabled = true
            )
            _subscriptions.value = _subscriptions.value + sub
            saveToPrefs()
            logCollector.info(TAG, "Default subscription added: $DEFAULT_SUBSCRIPTION_URL")
        }
    }

    suspend fun addSubscription(url: String, name: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val id = java.util.UUID.randomUUID().toString()
            val sub = ProviderSubscription(id = id, url = url, name = name)
            _subscriptions.value = _subscriptions.value + sub
            saveToPrefs()
            logCollector.info(TAG, "Subscription added: $name", mapOf("url" to url))
            true
        } catch (e: Exception) {
            logCollector.error(TAG, "Failed to add subscription", e)
            false
        }
    }

    fun removeSubscription(id: String) {
        if (id == DEFAULT_SUBSCRIPTION_ID) {
            logCollector.warn(TAG, "Cannot remove built-in subscription")
            return
        }
        _subscriptions.value = _subscriptions.value.filter { it.id != id }
        saveToPrefs()
    }

    suspend fun refreshAll(): Int = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            _lastRefreshUsedCache.value = false
            val subs = _subscriptions.value.filter { it.enabled }
            var totalConfigs = 0
            val allConfigsList = mutableListOf<ParsedConfig>()

            for (sub in subs) {
                try {
                    val configs = fetchAndParse(sub)
                    allConfigsList.addAll(configs)
                    totalConfigs += configs.size

                    val updated = sub.copy(lastUpdated = System.currentTimeMillis())
                    _subscriptions.value = _subscriptions.value.map {
                        if (it.id == sub.id) updated else it
                    }
                    logCollector.info(TAG, "Refreshed ${sub.name}: ${configs.size} configs")
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (e: Exception) {
                    logCollector.warn(TAG, "Fetch failed for ${sub.name}: ${e.message}")
                    // Оффлайн-режим: подгружаем кэш для этой подписки
                    val cached = loadConfigsFromCache().filter { it.providerTag == sub.name }
                    if (cached.isNotEmpty()) {
                        if (sub.url == PRIMARY_URL) _lastRefreshUsedCache.value = true
                        logCollector.info(TAG, "Using cached configs for ${sub.name}: ${cached.size}")
                        allConfigsList.addAll(cached.map { it.toParsedConfig() })
                        totalConfigs += cached.size
                    }
                }
            }

            if (allConfigsList.isNotEmpty()) {
                saveConfigsToCache(allConfigsList)
            }
            _allConfigs.value = allConfigsList.distinctBy {
                "${it.address}|${it.port}|${it.uuid}"
            }
            saveToPrefs()
            logCollector.info(TAG, "Total configs loaded: $totalConfigs (cached fallback applied)")
            totalConfigs
        }
    }

    suspend fun fetchWithFallback(sub: ProviderSubscription): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val sources = if (sub.url == PRIMARY_URL) FALLBACK_CHAIN else listOf(sub.url)
            var lastFailure: Exception? = null

            for (sourceUrl in sources) {
                try {
                    val request = Request.Builder()
                        .url(sourceUrl)
                        .header("User-Agent", "VPNProxy/1.0")
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("HTTP ${response.code}")
                        }
                        response.header("subscription-userinfo")?.let { info ->
                            parseUserInfo(info, sub.id)
                        }
                        val body = response.body?.string()
                            ?: throw IOException("Empty response body")
                        if (body.isBlank()) throw IOException("Empty response body")
                        logCollector.info(TAG, "Subscription source OK: $sourceUrl")
                        return@withContext body to sourceUrl
                    }
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (e: Exception) {
                    lastFailure = e
                    logCollector.warn(TAG, "Source failed: $sourceUrl — ${e.message}")
                }
            }

            throw IOException("All sources failed", lastFailure)
        }

    suspend fun fetchAndParse(sub: ProviderSubscription): List<ParsedConfig> {
        val (body, sourceUrl) = fetchWithFallback(sub)
        logCollector.info(TAG, "Fetch succeeded from $sourceUrl, size=${body.length}")
        return parser.parse(body, providerTag = sub.name)
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
        } catch (_: Exception) { /* ignore */ }
    }

    fun getAllConfigs(): List<ParsedConfig> = _allConfigs.value.distinctBy {
        "${it.address}|${it.port}|${it.uuid}"
    }

    fun getCachedConfigs(): List<ParsedConfig> =
        loadConfigsFromCache().map { it.toParsedConfig() }

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
        val providerTag: String = ""
    ) {
        fun toParsedConfig() = ParsedConfig(
            uuid = uuid,
            address = address,
            port = port,
            transport = transport,
            security = security,
            sni = sni,
            path = path,
            host = host,
            mode = mode,
            fingerprint = fingerprint,
            publicKey = publicKey,
            shortId = shortId,
            flow = flow,
            name = name,
            providerTag = providerTag
        )

        companion object {
            fun from(c: ParsedConfig) = CachedConfig(
                uuid = c.uuid,
                address = c.address,
                port = c.port,
                transport = c.transport,
                security = c.security,
                sni = c.sni,
                path = c.path,
                host = c.host,
                mode = c.mode,
                fingerprint = c.fingerprint,
                publicKey = c.publicKey,
                shortId = c.shortId,
                flow = c.flow,
                name = c.name,
                providerTag = c.providerTag
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
