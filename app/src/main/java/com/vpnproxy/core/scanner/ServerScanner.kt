package com.vpnproxy.core.scanner

import android.content.Context
import com.vpnproxy.core.connection.ConnectionCoordinator
import com.vpnproxy.core.connection.ConnectionState
import com.vpnproxy.service.VpnProxyService
import com.vpnproxy.core.singbox.SingboxConfigBuilder
import com.vpnproxy.smartconnect.DeepProbeEngine
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.smartconnect.AutoSwitcher
import com.vpnproxy.smartconnect.ScoringEngine.ScoredServer
import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import com.vpnproxy.subscription.SubscriptionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerScanner @Inject constructor(
    private val deepProbeEngine: DeepProbeEngine,
    private val subscriptionRepository: SubscriptionRepository,
    private val connectionCoordinator: ConnectionCoordinator,
    private val autoSwitcher: AutoSwitcher,
    private val logCollector: LogCollector,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ServerScanner"
        private const val RESCAN_INTERVAL_MS = 30 * 60 * 1_000L
    }

    private val scanMutex = Mutex()
    private val _scannedServers = MutableStateFlow<List<ScoredServer>>(emptyList())
    val scannedServers: StateFlow<List<ScoredServer>> = _scannedServers.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    @Volatile private var scannedConfigKeys: Set<String> = emptySet()
    @Volatile private var scannedNetworkBucket: String? = null
    @Volatile private var backgroundJob: Job? = null
    /** Job for the in-flight scanAll body so the home button can cancel it immediately. */
    @Volatile private var activeScanJob: Job? = null
    private val scanGeneration = AtomicLong(0L)
    private fun scanCache() = context.getSharedPreferences("server_scan_cache_v2_${autoSwitcher.currentNetworkBucket()}", Context.MODE_PRIVATE)
    private val successHistory by lazy { context.getSharedPreferences("server_success_history_v1", Context.MODE_PRIVATE) }
    private val scanCacheMaxAgeMs = RESCAN_INTERVAL_MS

    suspend fun scanAll(configs: List<ParsedConfig>, force: Boolean = false): List<ScoredServer> =
        scanMutex.withLock {
            val networkBucket = autoSwitcher.currentNetworkBucket()
            if (scannedNetworkBucket != null && scannedNetworkBucket != networkBucket) {
                _scannedServers.value = emptyList()
                scannedConfigKeys = emptySet()
            }
            val generation = scanGeneration.get()
            // Keep identical endpoints attributable to their provider. A scan cached for
            // provider A must never be reused as the scan result for provider B.
            val unique = orderConfigsForCurrentNetwork(
                (configs.filter(::isRussianServer) + configs.filterNot(::isRussianServer))
                    .distinctBy(::scanKey)
            )
            val requestedKeys = unique.map(::scanKey).toSet()
            if (!force && requestedKeys.isNotEmpty() &&
                requestedKeys == scannedConfigKeys && _scannedServers.value.size == unique.size
            ) {
                return@withLock _scannedServers.value
            }

            if (unique.isEmpty()) {
                scannedConfigKeys = emptySet()
                scannedNetworkBucket = networkBucket
                _scannedServers.value = emptyList()
                scanCache().edit().clear().apply()
                return@withLock emptyList()
            }

            if (_scannedServers.value.isEmpty()) restoreCachedResults(unique)

            // A complete cache younger than the configured TTL is a valid immediate
            // result. Reuse it after process restarts and APK updates; background scans
            // refresh measurements later instead of blocking every connection attempt.
            val savedAt = scanCache().getLong("saved_at", 0L)
            val cachedKeys = _scannedServers.value.map { server -> scanKey(server.config) }.toSet()
            val cacheFresh = savedAt > 0L &&
                System.currentTimeMillis() - savedAt in 0..scanCacheMaxAgeMs
            if (!force && cacheFresh && cachedKeys == requestedKeys &&
                _scannedServers.value.size == unique.size
            ) {
                scannedConfigKeys = requestedKeys
                scannedNetworkBucket = networkBucket
                logCollector.info(TAG, "Reusing ${unique.size} fresh cached server scan results")
                return@withLock _scannedServers.value
            }

            // A temporary proxy-only core cannot safely replace the active VPN core.
            // Keep the last known results while connected; scan again after disconnect.
            if (VpnProxyService.isRunning.value) {
                logCollector.info(TAG, "Profile scan deferred while VPN is active; retaining last known results")
                return@withLock _scannedServers.value
            }

            _isScanning.value = true
            try {
                activeScanJob = currentCoroutineContext()[Job]
                val resultByKey = linkedMapOf<String, ScoredServer>()
                unique.forEach { resultByKey[scanKey(it)] = unavailable(it) }
                _scannedServers.value = sortForCurrentNetwork(resultByKey.values.toList())

                val probeConfigs = unique.filter { config -> config.isValid() && runCatching { SingboxConfigBuilder.buildOutbound(config) }.isSuccess }
                if (probeConfigs.isNotEmpty()) {
                    try {
                        deepProbeEngine.pingProfiles(probeConfigs) { batch ->
                            if (scanGeneration.get() == generation) {
                                batch.forEach { ping -> resultByKey[scanKey(ping.config)] = scoredServer(ping) }
                                _scannedServers.value = sortForCurrentNetwork(resultByKey.values.toList())
                            }
                        }.forEach { ping -> resultByKey[scanKey(ping.config)] = scoredServer(ping) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        logCollector.warn(TAG, "Profile scan failed: ${error.message}")
                    }
                }
                val results = sortForCurrentNetwork(unique.map { resultByKey[scanKey(it)] ?: unavailable(it) })
                if (scanGeneration.get() != generation) {
                    logCollector.info(TAG, "Discarded cancelled scan results")
                    return@withLock _scannedServers.value
                }
                scannedConfigKeys = requestedKeys
                scannedNetworkBucket = networkBucket
                _scannedServers.value = results
                persistScanResults(results)
                val responsive = results.count { it.successRate > 0.5f }
                val ruResponsive = results.count { it.successRate > 0.5f && isRussianServer(it.config) }
                logCollector.info(
                    TAG,
                    "Profile scan complete: ${unique.size} profiles retained; VPN-profile probe succeeded for $responsive, RU=$ruResponsive (input=${configs.size})"
                )
                results
            } finally {
                activeScanJob = null
                _isScanning.value = false
            }
        }

    /**
     * Cancels the active scan as soon as possible.
     * Bumps generation so probes short-circuit, clears isScanning for immediate UI feedback,
     * and cancels the tracked scan job without stopping the VPN service.
     */
    fun cancelScan() {
        val wasScanning = _isScanning.value
        scanGeneration.incrementAndGet()
        _isScanning.value = false
        activeScanJob?.cancel()
        activeScanJob = null
        if (wasScanning) {
            logCollector.info(TAG, "Server scan cancellation requested; probes short-circuit and UI cleared")
        } else {
            logCollector.info(TAG, "Server scan cancel requested (no active scan flag)")
        }
    }

    suspend fun scanAfterSubscriptionUpdate(configs: List<ParsedConfig>): List<ScoredServer> {
        val currentKeys = configs.map(::scanKey).toSet()
        _scannedServers.value = _scannedServers.value.filter { scanKey(it.config) in currentKeys }
        return scanForConnection(configs)
    }

    /**
     * Fast connection path: probe network-history candidates in stages and return after the
     * first successful stage. The full server-list scan remains exhaustive.
     */
    suspend fun scanForConnection(configs: List<ParsedConfig>): List<ScoredServer> = scanMutex.withLock {
        val generationForScan = scanGeneration.get()
        val networkBucket = autoSwitcher.currentNetworkBucket()
        if (scannedNetworkBucket != null && scannedNetworkBucket != networkBucket) {
            _scannedServers.value = emptyList()
            scannedConfigKeys = emptySet()
        }
        val unique = orderConfigsForCurrentNetwork(
            (configs.filter(::isRussianServer) + configs.filterNot(::isRussianServer)).distinctBy(::scanKey)
        )
        val requestedKeys = unique.map(::scanKey).toSet()
        if (unique.isEmpty()) {
            scannedConfigKeys = emptySet()
            scannedNetworkBucket = networkBucket
            _scannedServers.value = emptyList()
            return@withLock emptyList()
        }
        if (requestedKeys == scannedConfigKeys && _scannedServers.value.size == unique.size) {
            return@withLock availableServers()
        }
        if (_scannedServers.value.isEmpty()) restoreCachedResults(unique)
        val savedAt = scanCache().getLong("saved_at", 0L)
        val cachedKeys = _scannedServers.value.map { scanKey(it.config) }.toSet()
        if (savedAt > 0L && System.currentTimeMillis() - savedAt in 0..scanCacheMaxAgeMs &&
            cachedKeys == requestedKeys && _scannedServers.value.size == unique.size
        ) {
            scannedConfigKeys = requestedKeys
            scannedNetworkBucket = networkBucket
            return@withLock availableServers()
        }
        if (VpnProxyService.isRunning.value) return@withLock availableServers()

        _isScanning.value = true
        try {
            activeScanJob = currentCoroutineContext()[Job]
            val probeConfigs = unique.filter { config -> !autoSwitcher.isBlacklisted(config) && config.isValid() && runCatching { SingboxConfigBuilder.buildOutbound(config) }.isSuccess }
            val resultByKey = linkedMapOf<String, ScoredServer>()
            unique.forEach { config ->
                resultByKey[scanKey(config)] = if (config.isValid())
                    ScoredServer(config, Long.MAX_VALUE, 0f, -1f, 0f) else unavailable(config)
            }
            _scannedServers.value = sortForCurrentNetwork(resultByKey.values.toList())
            var probedCount = 0
            try {
                val results = deepProbeEngine.pingProfiles(probeConfigs, stopAfterSuccessfulBatch = true) { batch ->
                    if (scanGeneration.get() == generationForScan) {
                        batch.forEach { ping -> resultByKey[scanKey(ping.config)] = scoredServer(ping) }
                        probedCount += batch.size
                        _scannedServers.value = sortForCurrentNetwork(resultByKey.values.toList())
                    }
                }
                results.forEach { ping -> resultByKey[scanKey(ping.config)] = scoredServer(ping) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logCollector.warn(TAG, "Staged connection scan failed: ${error.message}")
            }
            val results = sortForCurrentNetwork(unique.map { resultByKey[scanKey(it)] ?: unavailable(it) })
            if (scanGeneration.get() == generationForScan) {
                // A partial stage is useful for immediate selection but must never be cached as a complete scan.
                val complete = probedCount >= probeConfigs.size && unique.none { autoSwitcher.isBlacklisted(it) }
                scannedNetworkBucket = networkBucket
                scannedConfigKeys = if (complete) requestedKeys else emptySet()
                _scannedServers.value = results
                if (complete) persistScanResults(results) else scanCache().edit().putLong("saved_at", 0L).apply()
            }
            results
        } finally {
            activeScanJob = null
            _isScanning.value = false
        }
    }

    fun scanInBackground(scope: CoroutineScope): Job {
        backgroundJob?.takeIf { it.isActive }?.let { return it }
        return scope.launch {
            val initialConfigs = subscriptionRepository.getAllConfigs()
            if (initialConfigs.isNotEmpty()) scanAll(initialConfigs)
            while (isActive) {
                delay(RESCAN_INTERVAL_MS)
                if (connectionCoordinator.state.value is ConnectionState.Connected) {
                    val configs = subscriptionRepository.getAllConfigs()
                    if (configs.isNotEmpty()) scanAll(configs, force = true)
                }
            }
        }.also { backgroundJob = it }
    }

    fun isScanCurrent(configs: List<ParsedConfig>): Boolean {
        val unique = configs.distinctBy(::scanKey)
        val keys = unique.map(::scanKey).toSet()
        return keys.isNotEmpty() && keys == scannedConfigKeys &&
            _scannedServers.value.size == unique.size
    }

    /** Returns profiles in current-network history order, with confirmed VPN-profile responses first. */
    fun availableServers(): List<ScoredServer> = sortForCurrentNetwork(_scannedServers.value)

    /** Successful profiles first, then profiles that actually connected on this network. */
    fun sortForCurrentNetwork(servers: List<ScoredServer>): List<ScoredServer> {
        val bucket = autoSwitcher.currentNetworkBucket()
        return servers.sortedWith(
            compareBy<ScoredServer>(
                { if (it.successRate > 0.5f) 0 else 1 },
                { -successHistory.getLong(historyKey(it.config, bucket), 0L) },
                { it.latencyMs }
            )
        )
    }

    fun bestAvailableServer(servers: List<ScoredServer>): ScoredServer? =
        sortForCurrentNetwork(servers).firstOrNull { it.successRate > 0.5f }

    /** Record only a confirmed connected state, not a successful probe. */
    fun recordSuccessfulConnection(config: ParsedConfig) {
        val bucket = autoSwitcher.currentNetworkBucket()
        successHistory.edit().putLong(historyKey(config, bucket), System.currentTimeMillis()).apply()
        logCollector.info(TAG, "Recorded successful connection for ${config.name} on $bucket")
    }

    private fun orderConfigsForCurrentNetwork(configs: List<ParsedConfig>): List<ParsedConfig> {
        val bucket = autoSwitcher.currentNetworkBucket()
        return configs.sortedWith(
            compareByDescending<ParsedConfig> { successHistory.getLong(historyKey(it, bucket), 0L) }
                .thenBy { if (isRussianServer(it)) 0 else 1 }
        )
    }

    private fun historyKey(config: ParsedConfig, bucket: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((bucket + "\u001F" + scanKey(config)).toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    fun isRussianServer(config: ParsedConfig): Boolean {
        val labels = "${config.name} ${config.providerTag}"
        return labels.contains("🇷🇺") ||
            Regex("(^|[^A-Za-z0-9])RU([^A-Za-z0-9]|$)", RegexOption.IGNORE_CASE)
                .containsMatchIn(labels) ||
            config.address.endsWith(".ru", ignoreCase = true) ||
            config.sni.orEmpty().endsWith(".ru", ignoreCase = true)
    }

    /** Shared canonical key used by the scanner and server-list UI. */
    fun configKey(config: ParsedConfig): String = config.connectionKey()

    private fun cacheKey(config: ParsedConfig): String =
        MessageDigest.getInstance("SHA-256")
            .digest(scanKey(config).toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun restoreCachedResults(configs: List<ParsedConfig>) {
        val savedAt = scanCache.getLong("saved_at", 0L)
        if (savedAt <= 0L || System.currentTimeMillis() - savedAt > scanCacheMaxAgeMs) return
        val restored = configs.mapNotNull { config ->
            val key = cacheKey(config)
            if (!scanCache().contains("server_${key}_latency")) return@mapNotNull null
            ScoredServer(
                config = config,
                latencyMs = scanCache.getLong("server_${key}_latency", Long.MAX_VALUE),
                speedMbps = scanCache.getFloat("server_${key}_speed", 0f),
                successRate = scanCache.getFloat("server_${key}_success", 0f),
                score = scanCache.getFloat("server_${key}_score", 0f)
            )
        }
        if (restored.isNotEmpty()) {
            _scannedServers.value = sortForCurrentNetwork(restored)
            logCollector.info(TAG, "Restored ${restored.size} cached server scan results")
        }
    }

    private fun persistScanResults(results: List<ScoredServer>) {
        val editor = scanCache.edit()
        scanCache().all.keys.filter { it.startsWith("server_") }.forEach { key -> editor.remove(key) }
        results.forEach { server ->
            val key = cacheKey(server.config)
            editor.putLong("server_${key}_latency", server.latencyMs)
                .putFloat("server_${key}_speed", server.speedMbps)
                .putFloat("server_${key}_success", server.successRate)
                .putFloat("server_${key}_score", server.score)
        }
        editor.putLong("saved_at", System.currentTimeMillis()).apply()
    }

    private fun scoredServer(ping: DeepProbeEngine.ProfilePingResult): ScoredServer {
        val latency = ping.latencyMs.takeIf { ping.success } ?: Long.MAX_VALUE
        return ScoredServer(
            config = ping.config, latencyMs = latency, speedMbps = 0f,
            successRate = if (ping.success) 1f else 0f,
            score = if (ping.success) 1f / latency.coerceAtLeast(1L).toFloat() else 0f
        )
    }
    private fun unavailable(config: ParsedConfig): ScoredServer =
        ScoredServer(config, Long.MAX_VALUE, 0f, 0f, 0f)

    private fun pingTargetId(config: ParsedConfig): String =
        MessageDigest.getInstance("SHA-256")
            .digest(serverKey(config).toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun scanKey(config: ParsedConfig): String =
        "${config.providerTag}\u001F${config.connectionKey()}"

    private fun serverKey(config: ParsedConfig): String = config.connectionKey()
}
