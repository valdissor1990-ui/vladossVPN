package com.vpnproxy.subscription

import android.content.Context
import android.content.SharedPreferences
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
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local JSON / sing-box outbound import.
 * Stored encrypted on device when the credential-encrypted store is available;
 * independent of HTTP subscription fetch.
 * Configs are exposed via [configs] and merged by callers through [allConfigsIncluding].
 *
 * Storage is initialized lazily so Direct Boot (LOCKED_BOOT_COMPLETED) does not crash
 * Hilt injection when EncryptedSharedPreferences / MasterKey is unavailable.
 */
@Singleton
class JsonConfigRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val parser: SubscriptionParser,
    private val logCollector: LogCollector
) {
    companion object {
        private const val TAG = "JsonConfigRepo"
        private const val PREFS = "vpnproxy_json_servers_v1"
        private const val KEY_ENTRIES = "entries_json"
    }

    @Serializable
    data class JsonEntry(
        val id: String,
        val name: String,
        val tag: String,
        val rawJson: String
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val entrySerializer = ListSerializer(JsonEntry.serializer())

    /** Serializes read-modify-persist-publish sequences to prevent lost updates. */
    private val mutationMutex = Mutex()
    private val prefsLock = Any()

    @Volatile
    private var prefsOrNull: SharedPreferences? = null

    @Volatile
    private var prefsInitAttempted: Boolean = false

    private val _entries = MutableStateFlow<List<JsonEntry>>(emptyList())
    val entries: StateFlow<List<JsonEntry>> = _entries.asStateFlow()

    private val _configs = MutableStateFlow<List<ParsedConfig>>(emptyList())
    val configs: StateFlow<List<ParsedConfig>> = _configs.asStateFlow()

    init {
        // Best-effort load; safe when encrypted prefs are unavailable (direct boot).
        load()
    }

    /**
     * Returns encrypted prefs when the user credential store is available; null otherwise.
     * Never throws — Direct Boot and broken keystore must not kill BootReceiver injection.
     */
    private fun prefs(): SharedPreferences? {
        prefsOrNull?.let { return it }
        synchronized(prefsLock) {
            prefsOrNull?.let { return it }
            if (prefsInitAttempted) return null
            prefsInitAttempted = true
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    PREFS,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also { prefsOrNull = it }
            } catch (e: Exception) {
                logCollector.warn(TAG, "Encrypted JSON storage unavailable: ${e.message}")
                null
            }
        }
    }

    private fun load() {
        try {
            val prefs = prefs() ?: return
            val stored = prefs.getString(KEY_ENTRIES, null) ?: return
            val list = json.decodeFromString(entrySerializer, stored)
            _entries.value = list
            reparseAll()
            logCollector.info(TAG, "Loaded ${list.size} JSON entries, configs=${_configs.value.size}")
        } catch (e: Exception) {
            logCollector.error(TAG, "Failed to load JSON entries", e)
        }
    }

    /**
     * Persist the proposed state synchronously so callers can distinguish a durable
     * import/removal from a failed write. Never publish state that was not saved.
     */
    private fun persist(entries: List<JsonEntry>): Boolean {
        return try {
            val prefs = prefs()
            if (prefs == null) {
                logCollector.error(TAG, "Failed to persist JSON entries: storage unavailable")
                return false
            }
            val serialized = json.encodeToString(entrySerializer, entries)
            val saved = prefs.edit().putString(KEY_ENTRIES, serialized).commit()
            if (!saved) {
                logCollector.error(TAG, "Failed to persist JSON entries: commit returned false")
            }
            saved
        } catch (e: Exception) {
            logCollector.error(TAG, "Failed to persist JSON entries", e)
            false
        }
    }

    private fun reparseAll() {
        val out = mutableListOf<ParsedConfig>()
        for (entry in _entries.value) {
            val parsed = parser.parse(entry.rawJson, providerTag = entry.name)
            out += parsed
        }
        _configs.value = out.distinctBy { "${it.providerTag}\u001F${it.connectionKey()}" }
    }

    /**
     * @return number of servers parsed, or null if none/invalid.
     */
    suspend fun addFromJsonConfig(rawJson: String, name: String, tag: String = ""): Int? =
        withContext(Dispatchers.IO) {
            try {
                // Retry storage init after unlock if the first attempt was during direct boot.
                if (prefsOrNull == null) {
                    synchronized(prefsLock) {
                        prefsInitAttempted = false
                    }
                }
                val displayName = name.ifBlank { "JSON" }
                val parsed = parser.parse(rawJson, providerTag = displayName)
                if (parsed.isEmpty()) {
                    logCollector.warn(TAG, "No valid servers in JSON")
                    return@withContext null
                }
                val entry = JsonEntry(
                    id = UUID.randomUUID().toString(),
                    name = displayName,
                    tag = tag.ifBlank { displayName },
                    rawJson = rawJson
                )
                mutationMutex.withLock {
                    val updatedEntries = _entries.value + entry
                    if (!persist(updatedEntries)) {
                        logCollector.warn(TAG, "JSON import rejected because persistence failed")
                        return@withLock null
                    }
                    _entries.value = updatedEntries
                    reparseAll()
                    logCollector.info(TAG, "JSON import ok: servers=${parsed.size} name=$displayName")
                    parsed.size
                }
            } catch (e: Exception) {
                logCollector.error(TAG, "JSON import failed", e)
                null
            }
        }

    suspend fun remove(id: String): Boolean = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val currentEntries = _entries.value
            val updatedEntries = currentEntries.filterNot { it.id == id }
            if (updatedEntries.size == currentEntries.size) return@withLock false
            if (!persist(updatedEntries)) {
                logCollector.warn(TAG, "JSON entry removal not applied because persistence failed")
                return@withLock false
            }
            _entries.value = updatedEntries
            reparseAll()
            true
        }
    }

    suspend fun clear(): Boolean = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            if (_entries.value.isEmpty()) return@withLock true
            if (!persist(emptyList())) {
                logCollector.warn(TAG, "JSON entries clear not applied because persistence failed")
                return@withLock false
            }
            _entries.value = emptyList()
            _configs.value = emptyList()
            true
        }
    }

    /** Merge subscription pool with local JSON servers (deduped). */
    fun allConfigsIncluding(base: List<ParsedConfig>): List<ParsedConfig> {
        return (base + _configs.value).distinctBy {
            "${it.providerTag}\u001F${it.connectionKey()}"
        }
    }
}
