package com.vpnproxy.core.scanner

import com.vpnproxy.core.connection.ConnectionCoordinator
import com.vpnproxy.core.connection.ConnectionState
import com.vpnproxy.core.ping.PingExecutor
import com.vpnproxy.core.ping.PingProtocol
import com.vpnproxy.core.ping.PingRegion
import com.vpnproxy.core.ping.PingTarget
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.smartconnect.AutoSwitcher
import com.vpnproxy.smartconnect.ScoringEngine.ScoredServer
import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import com.vpnproxy.subscription.SubscriptionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerScanner @Inject constructor(
    private val pingExecutor: PingExecutor,
    private val subscriptionRepository: SubscriptionRepository,
    private val connectionCoordinator: ConnectionCoordinator,
    private val autoSwitcher: AutoSwitcher,
    private val logCollector: LogCollector
) {
    companion object {
        private const val TAG = "ServerScanner"
        private const val CONCURRENCY = 20
        private const val SERVER_TIMEOUT_MS = 2_000L
        private const val RESCAN_INTERVAL_MS = 30 * 60 * 1_000L
    }

    private val scanMutex = Mutex()
    private val _scannedServers = MutableStateFlow<List<ScoredServer>>(emptyList())
    val scannedServers: StateFlow<List<ScoredServer>> = _scannedServers.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    @Volatile private var scannedConfigKeys: Set<String> = emptySet()
    @Volatile private var backgroundJob: Job? = null

    suspend fun scanAll(configs: List<ParsedConfig>, force: Boolean = false): List<ScoredServer> =
        scanMutex.withLock {
            // Prefer an RU-labelled copy when the same connection profile appears in
            // multiple providers, so deduplication cannot erase RU-first priority.
            val unique = (configs.filter(::isRussianServer) + configs.filterNot(::isRussianServer))
                .distinctBy(::serverKey)
            val requestedKeys = unique.map(::serverKey).toSet()
            if (!force && requestedKeys.isNotEmpty() &&
                requestedKeys == scannedConfigKeys && _scannedServers.value.size == unique.size
            ) {
                return@withLock _scannedServers.value
            }

            if (unique.isEmpty()) {
                scannedConfigKeys = emptySet()
                _scannedServers.value = emptyList()
                return@withLock emptyList()
            }

            _isScanning.value = true
            try {
                val semaphore = Semaphore(CONCURRENCY)
                val russianConfigs = unique.filter(::isRussianServer)
                val otherConfigs = unique.filterNot(::isRussianServer)
                val results = coroutineScope {
                    // Probe every RU entry first; only then scan the remaining subscription entries.
                    val russianResults = russianConfigs.map { config ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit { probe(config) }
                        }
                    }.awaitAll()
                    val otherResults = otherConfigs.map { config ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit { probe(config) }
                        }
                    }.awaitAll()
                    russianResults + otherResults
                }.sortedWith(
                    compareBy<ScoredServer>(
                        { if (it.successRate > 0.5f) 0 else 1 },
                        { it.latencyMs }
                    )
                )
                scannedConfigKeys = requestedKeys
                _scannedServers.value = results
                val tcpResponsive = results.filter { it.successRate > 0.5f }
                val ruTcpResponsive = tcpResponsive.count { isRussianServer(it.config) }
                logCollector.info(
                    TAG,
                    "Scan complete: ${unique.size} profiles retained; TCP endpoint responded for ${tcpResponsive.size}, RU=$ruTcpResponsive (input=${configs.size}). TCP reachability does not verify VPN protocol/tunnel."
                )
                results
            } finally {
                _isScanning.value = false
            }
        }

    suspend fun scanAfterSubscriptionUpdate(configs: List<ParsedConfig>): List<ScoredServer> {
        // Refreshing subscription data invalidates reachability results, not failure history.
        // AutoSwitcher expires blacklist entries by TTL; clearing them here retries known-bad
        // profiles on every refresh and can trap startup in the same failing endpoint.
        scannedConfigKeys = emptySet()
        _scannedServers.value = emptyList()
        return scanAll(configs, force = true)
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
        val keys = configs.distinctBy(::serverKey).map(::serverKey).toSet()
        return keys.isNotEmpty() && keys == scannedConfigKeys &&
            _scannedServers.value.size == keys.size
    }

    /**
     * Returns all scanned profiles. TCP connect is only an endpoint reachability hint;
     * it must not exclude UDP protocols or be interpreted as VPN tunnel availability.
     * Responsive TCP endpoints remain first for convenient ordering.
     */
    fun availableServers(): List<ScoredServer> =
        _scannedServers.value.sortedWith(
            compareBy<ScoredServer>(
                { if (it.successRate > 0.5f) 0 else 1 },
                { it.latencyMs }
            )
        )

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

    private suspend fun probe(config: ParsedConfig): ScoredServer {
        val target = PingTarget(
            id = pingTargetId(config),
            displayName = config.name,
            host = config.address,
            port = config.port,
            protocol = PingProtocol.TCP_CONNECT,
            weight = 1.0,
            regions = setOf(if (isRussianServer(config)) PingRegion.RU else PingRegion.GLOBAL)
        )
        return try {
            val result = pingExecutor.ping(target, SERVER_TIMEOUT_MS)
            val latency = result.rttMs?.takeIf { result.success } ?: Long.MAX_VALUE
            ScoredServer(
                config = config,
                latencyMs = latency,
                speedMbps = 0f,
                successRate = if (result.success && result.rttMs != null) 1f else 0f,
                score = if (result.success && result.rttMs != null) {
                    1f / result.rttMs.coerceAtLeast(1L).toFloat()
                } else 0f
            )
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            logCollector.debug(TAG, "TCP probe failed for ${config.name}: ${e.message}")
            ScoredServer(config, Long.MAX_VALUE, 0f, 0f, 0f)
        }
    }

    private fun pingTargetId(config: ParsedConfig): String =
        MessageDigest.getInstance("SHA-256")
            .digest(serverKey(config).toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun serverKey(config: ParsedConfig): String = config.connectionKey()
}
