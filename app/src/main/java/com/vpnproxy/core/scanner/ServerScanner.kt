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
import kotlinx.coroutines.ensureActive
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
import java.util.concurrent.atomic.AtomicInteger
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
        private const val CONNECTION_PROBE_BATCH_SIZE = 16
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
    @Volatile private var activeScanIsConnection = false
    @Volatile private var activeScanConfigs: List<ParsedConfig> = emptyList()
    private val connectionScanWaiters = AtomicInteger(0)
    @Volatile private var pendingFullScanConfigs: List<ParsedConfig> = emptyList()
    private val scanGeneration = AtomicLong(0L)
    private fun scanCache() = context.getSharedPreferences("server_scan_cache_v2_${autoSwitcher.currentNetworkBucket()}", Context.MODE_PRIVATE)
    private val successHistory by lazy { context.getSharedPreferences("server_success_history_v1", Context.MODE_PRIVATE) }
    private val scanCacheMaxAgeMs = RESCAN_INTERVAL_MS

    /** Shared bounded probe loop; a null target means exhaust every profile. */
    private suspend fun probeProfilesInBatches(
        probeConfigs: List<ParsedConfig>,
        resultByKey: MutableMap<String, ScoredServer>,
        generation: Long,
        targetAvailableCount: Int? = null
    ): Int {
        var probedCount = 0
        val target = targetAvailableCount?.coerceAtLeast(1)
        try {
            for (probeBatch in probeConfigs.chunked(CONNECTION_PROBE_BATCH_SIZE)) {
                currentCoroutineContext().ensureActive()
                if (scanGeneration.get() != generation) break
                val remainingTarget = target?.let { expected ->
                    (expected - resultByKey.values.count {
                        it.successRate > 0.5f && it.latencyMs != Long.MAX_VALUE
                    }).coerceAtLeast(1)
                }
                val batchResults = deepProbeEngine.pingProfiles(
                    probeBatch,
                    stopAfterSuccessfulCount = remainingTarget
                ) { batch ->
                    if (scanGeneration.get() == generation) {
                        batch.forEach { ping -> resultByKey[scanKey(ping.config)] = scoredServer(ping) }
                        probedCount += batch.size
                        _scannedServers.value = sortForCurrentNetwork(resultByKey.values.toList())
                    }
                }
                batchResults.forEach { ping -> resultByKey[scanKey(ping.config)] = scoredServer(ping) }
                if (target != null) {
                    val reachableNow = resultByKey.values.count {
                        it.successRate > 0.5f && it.latencyMs != Long.MAX_VALUE
                    }
                    if (reachableNow >= target) {
                        logCollector.info(TAG, "Connection scan reached target=$target after $probedCount measured profiles")
                        break
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logCollector.warn(TAG, "Profile probe batch failed: ${error.message}")
        }
        return probedCount
    }

    suspend fun scanAll(configs: List<ParsedConfig>, force: Boolean = false): List<ScoredServer> =
        scanMutex.withLock {
            if (connectionScanWaiters.get() > 0) {
                pendingFullScanConfigs = configs.distinctBy(::scanKey)
                logCollector.info(TAG, "Full scan deferred: a connection scan is waiting; latest profile set queued")
                return@withLock _scannedServers.value
            }
            val networkBucket = autoSwitcher.currentNetworkBucket()
            if (scannedNetworkBucket != null && scannedNetworkBucket != networkBucket) {
                _scannedServers.value = emptyList()
                scannedConfigKeys = emptySet()
            }
            val generation = scanGeneration.get()
            val unique = orderConfigsForCurrentNetwork(
                (configs.filter(::isRussianServer) + configs.filterNot(::isRussianServer)).distinctBy(::scanKey)
            )
            val requestedKeys = unique.map(::scanKey).toSet()
            if (!force && requestedKeys.isNotEmpty() && requestedKeys == scannedConfigKeys && _scannedServers.value.size == unique.size) {
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
            val savedAt = scanCache().getLong("saved_at", 0L)
            val cachedKeys = _scannedServers.value.map { server -> scanKey(server.config) }.toSet()
            val cacheFresh = savedAt > 0L && System.currentTimeMillis() - savedAt in 0..scanCacheMaxAgeMs
            if (!force && cacheFresh && cachedKeys == requestedKeys && _scannedServers.value.size == unique.size) {
                scannedConfigKeys = requestedKeys
                scannedNetworkBucket = networkBucket
                logCollector.info(TAG, "Reusing ${unique.size} fresh cached server scan results")
                return@withLock _scannedServers.value
            }
            if (VpnProxyService.isRunning.value) {
                pendingFullScanConfigs = unique
                logCollector.info(TAG, "Profile scan deferred while VPN is active; queued for after disconnect")
                return@withLock _scannedServers.value
            }
            _isScanning.value = true
            try {
                activeScanJob = currentCoroutineContext()[Job]
                activeScanIsConnection = false
                activeScanConfigs = unique
                val resultByKey = linkedMapOf<String, ScoredServer>()
                unique.forEach { resultByKey[scanKey(it)] = unavailable(it) }
                _scannedServers.value = sortForCurrentNetwork(resultByKey.values.toList())
                val probeConfigs = unique.filter { config -> config.isValid() && runCatching { SingboxConfigBuilder.buildOutbound(config) }.isSuccess }
                val probedCount = if (probeConfigs.isNotEmpty()) {
                    probeProfilesInBatches(probeConfigs, resultByKey, generation)
                } else 0
                val results = sortForCurrentNetwork(unique.map { resultByKey[scanKey(it)] ?: unavailable(it) })
                if (scanGeneration.get() != generation) {
                    logCollector.info(TAG, "Discarded cancelled scan results")
                    return@withLock _scannedServers.value
                }
                val complete = probedCount >= probeConfigs.size
                val hasReachable = results.any {
                    it.successRate > 0.5f && it.latencyMs != Long.MAX_VALUE
                }
                // An exhaustive scan with zero reachable profiles is not a reusable
                // success cache: the next user connection must retry the probes.
                scannedConfigKeys = if (complete && hasReachable) requestedKeys else emptySet()
                scannedNetworkBucket = networkBucket
                _scannedServers.value = results
                if (complete) {
                    if (hasReachable) persistScanResults(results)
                    else scanCache().edit().putLong("saved_at", 0L).apply()
                    if (pendingFullScanConfigs.map(::scanKey).toSet() == requestedKeys) {
                        pendingFullScanConfigs = emptyList()
                    }
                } else scanCache().edit().putLong("saved_at", 0L).apply()
                val responsive = results.count { it.successRate > 0.5f }
                val ruResponsive = results.count { it.successRate > 0.5f && isRussianServer(it.config) }
                logCollector.info(TAG, "Profile scan complete: ${unique.size} profiles retained; VPN-profile probe succeeded for $responsive, RU=$ruResponsive (input=${configs.size})")
                results
            } finally {
                activeScanJob = null
                activeScanIsConnection = false
                activeScanConfigs = emptyList()
                _isScanning.value = false
            }
        }

    fun cancelScan(preservePendingFullScan: Boolean = false) {
        if (!preservePendingFullScan) pendingFullScanConfigs = emptyList()
        val wasScanning = _isScanning.value
        scanGeneration.incrementAndGet()
        _isScanning.value = false
        activeScanJob?.cancel()
        activeScanJob = null
        if (wasScanning) logCollector.info(TAG, "Server scan cancellation requested; probes short-circuit and UI cleared")
        else logCollector.info(TAG, "Server scan cancel requested (no active scan flag)")
    }

    suspend fun scanForConnection(
        configs: List<ParsedConfig>,
        targetAvailableCount: Int = 4
    ): List<ScoredServer> {
        connectionScanWaiters.incrementAndGet()
        try {
            // A user-initiated connection scan has priority over a background full scan.
            // Cancel the background owner before waiting for the shared temporary Sing-box core.
            if (_isScanning.value && !activeScanIsConnection) {
                logCollector.info(TAG, "Connection scan preempts background profile scan")
                if (activeScanConfigs.isNotEmpty()) pendingFullScanConfigs = activeScanConfigs
                cancelScan(preservePendingFullScan = true)
            }
            return scanMutex.withLock {
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
        val hasReachableCachedResult = _scannedServers.value.any {
            it.successRate > 0.5f && it.latencyMs != Long.MAX_VALUE
        }
        if (requestedKeys == scannedConfigKeys && _scannedServers.value.size == unique.size && hasReachableCachedResult) {
            return@withLock availableServers()
        }
        val alreadyReachable = _scannedServers.value.count { it.successRate > 0.5f && it.latencyMs != Long.MAX_VALUE }
        if (scannedNetworkBucket == networkBucket && _scannedServers.value.size == unique.size && alreadyReachable >= targetAvailableCount.coerceAtLeast(1)) {
            logCollector.info(TAG, "Reusing staged scan: reachable=$alreadyReachable target=$targetAvailableCount")
            return@withLock availableServers()
        }
        if (_scannedServers.value.isEmpty()) restoreCachedResults(unique)
        val savedAt = scanCache().getLong("saved_at", 0L)
        val cachedKeys = _scannedServers.value.map { scanKey(it.config) }.toSet()
        val hasReachableDiskCache = _scannedServers.value.any {
            it.successRate > 0.5f && it.latencyMs != Long.MAX_VALUE
        }
        if (savedAt > 0L && System.currentTimeMillis() - savedAt in 0..scanCacheMaxAgeMs &&
            cachedKeys == requestedKeys && _scannedServers.value.size == unique.size && hasReachableDiskCache
        ) {
            scannedConfigKeys = requestedKeys
            scannedNetworkBucket = networkBucket
            return@withLock availableServers()
        }
        if (VpnProxyService.isRunning.value) {
            pendingFullScanConfigs = unique
            logCollector.info(TAG, "Connection scan deferred while VPN is active; queued for after disconnect")
            return@withLock availableServers()
        }

        // Keep a full-scan continuation even if the bounded connection scan is cancelled by
        // the coordinator deadline. A successful exhaustive scan clears this queue below.
        pendingFullScanConfigs = unique
        _isScanning.value = true
        try {
            activeScanJob = currentCoroutineContext()[Job]
            activeScanIsConnection = true
            val probeConfigs = unique.filter { config -> !autoSwitcher.isBlacklisted(config) && config.isValid() && runCatching { SingboxConfigBuilder.buildOutbound(config) }.isSuccess }
            val resultByKey = linkedMapOf<String, ScoredServer>()
            unique.forEach { config ->
                resultByKey[scanKey(config)] = if (config.isValid()) ScoredServer(config, Long.MAX_VALUE, 0f, -1f, 0f) else unavailable(config)
            }
            _scannedServers.value = sortForCurrentNetwork(resultByKey.values.toList())
            val probedCount = probeProfilesInBatches(
                probeConfigs = probeConfigs,
                resultByKey = resultByKey,
                generation = generationForScan,
                targetAvailableCount = targetAvailableCount
            )
            val results = sortForCurrentNetwork(unique.map { resultByKey[scanKey(it)] ?: unavailable(it) })
            if (scanGeneration.get() == generationForScan) {
                val complete = probedCount >= probeConfigs.size && unique.none { autoSwitcher.isBlacklisted(it) }
                val reachable = results.count { it.successRate > 0.5f && it.latencyMs != Long.MAX_VALUE }
                val hasReachable = reachable > 0
                scannedNetworkBucket = networkBucket
                scannedConfigKeys = if (complete && hasReachable) requestedKeys else emptySet()
                _scannedServers.value = results
                if (complete) {
                    if (hasReachable) persistScanResults(results)
                    else scanCache().edit().putLong("saved_at", 0L).apply()
                    if (pendingFullScanConfigs.map(::scanKey).toSet() == requestedKeys) {
                        pendingFullScanConfigs = emptyList()
                    }
                } else {
                    pendingFullScanConfigs = unique
                    scanCache().edit().putLong("saved_at", 0L).apply()
                }
            }
            val reachable = results.count { it.successRate > 0.5f && it.latencyMs != Long.MAX_VALUE }
            logCollector.info(TAG, "Connection scan staged: reachable=$reachable target=$targetAvailableCount total=${unique.size}; remaining profiles stay unmeasured")
            results
        } finally {
            activeScanJob = null
            activeScanIsConnection = false
            _isScanning.value = false
        }
            }
        } finally {
            connectionScanWaiters.decrementAndGet()
        }
    }

    fun scanInBackground(scope: CoroutineScope): Job {
        backgroundJob?.takeIf { it.isActive }?.let { return it }
        return scope.launch {
            val initialConfigs = subscriptionRepository.getAllConfigs()
            if (initialConfigs.isNotEmpty()) scanAll(initialConfigs)
            while (isActive) {
                val pending = pendingFullScanConfigs
                if (pending.isNotEmpty()) {
                    // The temporary profile-probe core cannot coexist with the active VPN core.
                    // Resume the full scan as soon as the tunnel is down instead of dropping it.
                    delay(10_000L)
                    val latestPending = pendingFullScanConfigs
                    if (latestPending.isNotEmpty() && !VpnProxyService.isRunning.value) {
                        logCollector.info(TAG, "Resuming queued full scan after VPN stopped: profiles=${latestPending.size}")
                        scanAll(latestPending, force = true)
                    }
                } else {
                    delay(RESCAN_INTERVAL_MS)
                    if (connectionCoordinator.state.value is ConnectionState.Connected) {
                        val configs = subscriptionRepository.getAllConfigs()
                        if (configs.isNotEmpty()) scanAll(configs, force = true)
                    }
                }
            }
        }.also { backgroundJob = it }
    }

    fun isScanCurrent(configs: List<ParsedConfig>): Boolean {
        val unique = configs.distinctBy(::scanKey)
        val keys = unique.map(::scanKey).toSet()
        return keys.isNotEmpty() && keys == scannedConfigKeys && _scannedServers.value.size == unique.size
    }

    fun availableServers(): List<ScoredServer> = sortForCurrentNetwork(_scannedServers.value)

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

    fun bestAvailableServer(servers: List<ScoredServer>): ScoredServer? {
        val sorted = sortForCurrentNetwork(servers)
        return sorted.firstOrNull { it.successRate > 0.5f }
            ?: sorted.firstOrNull { it.successRate > 0f && it.latencyMs != Long.MAX_VALUE }
    }

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
            Regex("(^|[^A-Za-z0-9])RU([^A-Za-z0-9]|$)", RegexOption.IGNORE_CASE).containsMatchIn(labels) ||
            config.address.endsWith(".ru", ignoreCase = true) ||
            config.sni.orEmpty().endsWith(".ru", ignoreCase = true)
    }

    fun configKey(config: ParsedConfig): String = config.connectionKey()

    private fun cacheKey(config: ParsedConfig): String =
        MessageDigest.getInstance("SHA-256")
            .digest(scanKey(config).toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun restoreCachedResults(configs: List<ParsedConfig>) {
        val savedAt = scanCache().getLong("saved_at", 0L)
        val cacheFresh = savedAt > 0L && System.currentTimeMillis() - savedAt <= scanCacheMaxAgeMs
        val restoredByKey = linkedMapOf<String, ScoredServer>()
        if (cacheFresh) {
            configs.forEach { config ->
                val key = cacheKey(config)
                if (!scanCache().contains("server_${key}_latency")) return@forEach
                restoredByKey[scanKey(config)] = ScoredServer(
                    config = config,
                    latencyMs = scanCache().getLong("server_${key}_latency", Long.MAX_VALUE),
                    speedMbps = scanCache().getFloat("server_${key}_speed", 0f),
                    successRate = scanCache().getFloat("server_${key}_success", 0f),
                    score = scanCache().getFloat("server_${key}_score", 0f)
                )
            }
        }
        val bucket = autoSwitcher.currentNetworkBucket()
        val now = System.currentTimeMillis()
        val historyTtlMs = 7L * 24 * 60 * 60 * 1000
        configs.forEach { config ->
            val sk = scanKey(config)
            if (restoredByKey.containsKey(sk)) return@forEach
            val lastOk = successHistory.getLong(historyKey(config, bucket), 0L)
            if (lastOk > 0L && now - lastOk in 0..historyTtlMs) {
                restoredByKey[sk] = ScoredServer(config = config, latencyMs = 1L, speedMbps = 0f, successRate = 0.75f, score = 0.5f)
            }
        }
        if (restoredByKey.isNotEmpty()) {
            _scannedServers.value = sortForCurrentNetwork(restoredByKey.values.toList())
            logCollector.info(TAG, "Restored ${restoredByKey.size} cached/history server results (cacheFresh=$cacheFresh)")
        }
    }

    private fun persistScanResults(results: List<ScoredServer>) {
        val editor = scanCache().edit()
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
            config = ping.config,
            latencyMs = latency,
            speedMbps = 0f,
            successRate = if (ping.success) 1f else 0f,
            score = if (ping.success) (1.0 / (1.0 + latency / 1000.0)).toFloat() else 0f
        )
    }

    private fun unavailable(config: ParsedConfig): ScoredServer =
        ScoredServer(config, Long.MAX_VALUE, 0f, 0f, 0f)

    private fun scanKey(config: ParsedConfig): String =
        "${config.providerTag}\u001F${config.connectionKey()}"

    private fun serverKey(config: ParsedConfig): String = config.connectionKey()
}
