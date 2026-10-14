package com.vpnproxy.subscription

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Репозиторий подписок.
 *
 * - Хранит URL подписок и токены в EncryptedSharedPreferences
 * - Загружает подписки через OkHttp
 * - Парсит через SubscriptionParser
 * - Предоставляет StateFlow<List<ProviderSubscription>> для UI
 */
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

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context, PREFS_NAME, masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _subscriptions = MutableStateFlow<List<ProviderSubscription>>(emptyList())
    val subscriptions: StateFlow<List<ProviderSubscription>> = _subscriptions.asStateFlow()

    private val _allConfigs = MutableStateFlow<List<ParsedConfig>>(emptyList())
    val allConfigs: StateFlow<List<ParsedConfig>> = _allConfigs.asStateFlow()

    init { loadFromPrefs() }

    /** Загружает список подписок из EncryptedSharedPreferences. */
    private fun loadFromPrefs() {
        try {
            val stored = encryptedPrefs.getString(KEY_SUBS, null) ?: return
            val list = json.decodeFromString<List<ProviderSubscription>>(stored)
            _subscriptions.value = list
            logCollector.info(TAG, "Loaded ${list.size} subscriptions from prefs")
        } catch (e: Exception) {
            logCollector.error(TAG, "Failed to load subscriptions", e)
        }
    }

    private fun saveToPrefs() {
        try {
            val jsonStr = json.encodeToString(_subscriptions.value)
            encryptedPrefs.edit().putString(KEY_SUBS, jsonStr).apply()
        } catch (e: Exception) {
            logCollector.error(TAG, "Failed to save subscriptions", e)
        }
    }

    /** Добавляет новую подписку. */
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

    /** Удаляет подписку по id. */
    fun removeSubscription(id: String) {
        _subscriptions.value = _subscriptions.value.filter { it.id != id }
        saveToPrefs()
    }

    /** Обновляет все включённые подписки. */
    suspend fun refreshAll(): Int = withContext(Dispatchers.IO) {
        val subs = _subscriptions.value.filter { it.enabled }
        var totalConfigs = 0
        val allConfigsList = mutableListOf<ParsedConfig>()

        for (sub in subs) {
            try {
                val configs = fetchAndParse(sub)
                allConfigsList.addAll(configs)
                totalConfigs += configs.size

                // Обновляем метаданные
                val updated = sub.copy(lastUpdated = System.currentTimeMillis())
                _subscriptions.value = _subscriptions.value.map {
                    if (it.id == sub.id) updated else it
                }
                logCollector.info(TAG, "Refreshed ${sub.name}: ${configs.size} configs")
            } catch (e: Exception) {
                logCollector.error(TAG, "Failed to refresh ${sub.name}", e)
            }
        }

        _allConfigs.value = allConfigsList
        saveToPrefs()
        logCollector.info(TAG, "Total configs loaded: $totalConfigs")
        totalConfigs
    }

    /** Загружает и парсит одну подписку. */
    suspend fun fetchAndParse(sub: ProviderSubscription): List<ParsedConfig> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(sub.url)
                .header("User-Agent", "VPNProxy/1.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code}")
                }

                // Проверяем subscription-userinfo
                response.header("subscription-userinfo")?.let { info ->
                    parseUserInfo(info, sub.id)
                }

                val body = response.body?.string() ?: return@withContext emptyList()
                parser.parse(body, providerTag = sub.name)
            }
        }

    private fun parseUserInfo(info: String, subId: String) {
        try {
            val parts = info.split(";").associate {
                val kv = it.trim().split("=")
                if (kv.size == 2) kv[0] to kv[1].toLongOrNull() ?: 0L else "" to 0L
            }
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

    /** Возвращает все конфиги (для Smart Connect). */
    fun getAllConfigs(): List<ParsedConfig> = _allConfigs.value
}
