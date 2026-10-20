package com.vpnproxy.smartconnect

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible

import com.vpnproxy.core.connection.ConnectionCoordinator
import com.vpnproxy.core.connection.ConnectionEvent
import com.vpnproxy.core.singbox.SingboxConfigBuilder
import com.vpnproxy.service.VpnProxyService
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.smartconnect.ScoringEngine.ScoredServer
import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

enum class AutoSwitchState { MONITORING, SUSPECT, VALIDATING, MICRO_PROBE, SWITCHING }

internal enum class NetworkFaultKind { NONE, NETWORK_DEGRADATION, SERVER_DEGRADATION }
internal data class ProbeResult(val latencyMs: Long, val success: Boolean)

internal fun classifyProbeFault(
    direct: ProbeResult,
    vpn: ProbeResult,
    suspectRttMs: Long = 500L,
    directFastRttMs: Long = 100L
): NetworkFaultKind {
    val directBad = !direct.success || direct.latencyMs > suspectRttMs
    val directFast = direct.success && direct.latencyMs < directFastRttMs
    val vpnBad = !vpn.success || vpn.latencyMs > suspectRttMs
    return when {
        directBad && !vpnBad -> NetworkFaultKind.NETWORK_DEGRADATION
        directFast && vpnBad -> NetworkFaultKind.SERVER_DEGRADATION
        else -> NetworkFaultKind.NONE
    }
}

internal fun shouldSwitchAfterEvidence(
    serverConfirmations: Int,
    networkConfirmations: Int,
    directPathHealthy: Boolean
): Boolean = directPathHealthy &&
    serverConfirmations >= 2 &&
    networkConfirmations < 2
private data class ProbeEvidence(val directLatencyMs: Long, val directSuccess: Boolean, val vpnLatencyMs: Long, val vpnSuccess: Boolean, val jitterMs: Long, val timestampMs: Long = System.currentTimeMillis())

@Singleton
class AutoSwitcher @Inject constructor(
    private val healthChecker: HealthChecker,
    private val scoringEngine: ScoringEngine,
    private val logCollector: LogCollector,
    private val connectionCoordinator: ConnectionCoordinator
) {
    companion object {
        private const val TAG = "AutoSwitcher"
        private const val HEALTH_CHECK_INTERVAL_MS = 150_000L
        private const val SUSPECT_RTT_MS = 500L
        private const val SUSPECT_JITTER_MS = 150L
        private const val DIRECT_FAST_RTT_MS = 100L
        private const val MICRO_PROBE_MIN_MBPS = 1.0
        private const val SWITCH_COOLDOWN_MS = 5 * 60_000L
        private const val SWITCH_WINDOW_MS = 15 * 60_000L
        private const val MAX_SWITCHES_PER_WINDOW = 3
        private const val DIRECT_PROBE_TIMEOUT_MS = 1_500
        private const val MICRO_PROBE_TIMEOUT_MS = 3_000
        private const val MICRO_PROBE_BYTES = 51_200L
        private const val MICRO_PROBE_URL = "https://proof.ovh.net/files/1Mb.dat"
        private const val SERVER_DEGRADATION_CONFIRMATIONS = 2
        private const val NETWORK_DEGRADATION_CONFIRMATIONS = 2
        private const val EVIDENCE_WINDOW_MS = 10 * 60_000L
        private const val MAX_CONSECUTIVE_ERRORS = 3
        private const val RTT_DEGRADATION_FACTOR = 3.0
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var healthCheckJob: Job? = null

    private val _currentServer = MutableStateFlow<ScoredServer?>(null)
    val currentServer: StateFlow<ScoredServer?> = _currentServer.asStateFlow()

    private val _rankedServers = MutableStateFlow<List<ScoredServer>>(emptyList())
    val rankedServers: StateFlow<List<ScoredServer>> = _rankedServers.asStateFlow()

    private var consecutiveErrors = 0
    private var baselineRtt = 0L
    private var previousRtt: Long? = null
    @Volatile private var vpnService: VpnProxyService? = null
    @Volatile private var state = AutoSwitchState.MONITORING
    private var cooldownUntilMs = 0L
    private val recentSwitchesMs = ArrayDeque<Long>()
    private val recentEvidence = ArrayDeque<ProbeEvidence>()
    private var consecutiveServerEvidence = 0
    private var consecutiveNetworkEvidence = 0
    private val microProbeClient = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", SingboxConfigBuilder.PROXY_PORT)))
        .connectTimeout(MICRO_PROBE_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(MICRO_PROBE_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .callTimeout(MICRO_PROBE_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    fun currentState(): AutoSwitchState = state

    fun attachVpnService(service: VpnProxyService) {
        vpnService = service
        logCollector.info(TAG, "VpnService attached for protected direct probes")
    }

    fun detachVpnService(service: VpnProxyService) {
        if (vpnService === service) vpnService = null
    }

    private fun transitionTo(next: AutoSwitchState, reason: String) {
        state = next
        logCollector.info(TAG, "State -> $next: $reason")
    }

    private val _switchEvents = MutableStateFlow<String?>(null)
    val switchEvents: StateFlow<String?> = _switchEvents.asStateFlow()

    private val switching = AtomicBoolean(false)

    fun isSwitching(): Boolean = switching.get()

    fun startMonitoring() {
        healthCheckJob?.cancel()
        healthCheckJob = scope.launch {
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                if (System.currentTimeMillis() < cooldownUntilMs) {
                    logCollector.debug(TAG, "Slow-server checks skipped during switch cooldown")
                    continue
                }
                checkCurrentServer()
            }
        }
    }

    fun stopMonitoring() {
        healthCheckJob?.cancel()
        healthCheckJob = null
    }

    private suspend fun checkCurrentServer() {
        val current = _currentServer.value ?: return
        if (switching.get()) return

        try {
            val result = healthChecker.testLatency(listOf(current.config)).firstOrNull() ?: return
            val jitter = previousRtt?.let { kotlin.math.abs(result.latencyMs - it) } ?: 0L
            previousRtt = result.latencyMs.takeIf { result.success && it != Long.MAX_VALUE }

            if (!result.success || result.latencyMs == Long.MAX_VALUE) {
                consecutiveErrors++
                logCollector.warn(
                    TAG,
                    "Health check failed for ${current.config.name}",
                    mapOf("errors" to consecutiveErrors)
                )
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    logCollector.warn(TAG, "Repeated health-check failures; require direct-vs-VPN validation before any switch")
                }
                if (state == AutoSwitchState.MONITORING) {
                    transitionTo(AutoSwitchState.SUSPECT, "health check failed; errors=$consecutiveErrors")
                    validateSlowServer(current.config, Long.MAX_VALUE, jitter)
                }
                return
            }

            consecutiveErrors = 0
            val baselineDegraded = baselineRtt > 0 && result.latencyMs > baselineRtt * RTT_DEGRADATION_FACTOR
            if (baselineDegraded || result.latencyMs > SUSPECT_RTT_MS || jitter > SUSPECT_JITTER_MS) {
                if (state == AutoSwitchState.MONITORING) {
                    val reason = "RTT=${result.latencyMs}ms, baseline=${baselineRtt}ms, jitter=${jitter}ms"
                    transitionTo(AutoSwitchState.SUSPECT, reason)
                    validateSlowServer(current.config, result.latencyMs, jitter)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logCollector.error(TAG, "Health check error", e)
            consecutiveErrors++
        }
    }

    private suspend fun validateSlowServer(config: ParsedConfig, measuredRtt: Long, jitterMs: Long) {
        if (System.currentTimeMillis() < cooldownUntilMs || switching.get()) {
            transitionTo(AutoSwitchState.MONITORING, "cooldown or switch already active")
            return
        }
        transitionTo(AutoSwitchState.VALIDATING, "active direct-vs-VPN socket validation")
        val direct = probeSocket("1.1.1.1", 53, protectFromVpn = true)
        val vpn = probeSocket(config.address, config.port, protectFromVpn = false)
        recentEvidence.addLast(ProbeEvidence(direct.latencyMs, direct.success, vpn.latencyMs, vpn.success, jitterMs))
        val now = System.currentTimeMillis()
        while (recentEvidence.isNotEmpty() && now - recentEvidence.peekFirst().timestampMs > EVIDENCE_WINDOW_MS) {
            recentEvidence.removeFirst()
        }
        while (recentEvidence.size > 12) recentEvidence.removeFirst()

        val classification = classifyFault(direct, vpn)
        logCollector.info(
            TAG,
            "Slow-server validation: direct=${direct.latencyMs}ms success=${direct.success}; " +
                "vpn=${vpn.latencyMs}ms success=${vpn.success}; initialRTT=${measuredRtt}ms jitter=${jitterMs}ms; " +
                "classification=$classification serverEvidence=$consecutiveServerEvidence networkEvidence=$consecutiveNetworkEvidence"
        )
        when (classification) {
            NetworkFaultKind.NETWORK_DEGRADATION -> {
                consecutiveNetworkEvidence++
                consecutiveServerEvidence = 0
                val networkStatus = if (consecutiveNetworkEvidence >= NETWORK_DEGRADATION_CONFIRMATIONS) {
                    "underlying network degradation confirmed $consecutiveNetworkEvidence times; automatic server switching suppressed"
                } else {
                    "underlying network degradation suspected ($consecutiveNetworkEvidence/$NETWORK_DEGRADATION_CONFIRMATIONS); automatic server switching suppressed"
                }
                transitionTo(AutoSwitchState.MONITORING, networkStatus)
            }
            NetworkFaultKind.SERVER_DEGRADATION -> {
                consecutiveServerEvidence++
                consecutiveNetworkEvidence = 0
                if (consecutiveServerEvidence < SERVER_DEGRADATION_CONFIRMATIONS) {
                    transitionTo(AutoSwitchState.MONITORING, "server degradation confirmation $consecutiveServerEvidence/$SERVER_DEGRADATION_CONFIRMATIONS; awaiting independent confirmation")
                } else {
                    transitionTo(AutoSwitchState.MICRO_PROBE, "server degradation confirmed twice; checking transport")
                    runMicroProbe()
                }
            }
            NetworkFaultKind.NONE -> {
                consecutiveServerEvidence = 0
                consecutiveNetworkEvidence = 0
                transitionTo(AutoSwitchState.MONITORING, "validation did not confirm degradation")
            }
        }
    }

    internal fun classifyFault(direct: ProbeResult, vpn: ProbeResult): NetworkFaultKind =
        classifyProbeFault(direct, vpn, SUSPECT_RTT_MS, DIRECT_FAST_RTT_MS)

    private suspend fun probeSocket(host: String, port: Int, protectFromVpn: Boolean): ProbeResult =
        withContext(Dispatchers.IO) {
            val start = System.nanoTime()
            try {
                val socketProxy = if (protectFromVpn) Proxy.NO_PROXY else Proxy(
                    Proxy.Type.SOCKS,
                    InetSocketAddress("127.0.0.1", SingboxConfigBuilder.PROXY_PORT)
                )
                Socket(socketProxy).use { socket ->
                    if (protectFromVpn) {
                        val service = vpnService
                        val protected = service?.protect(socket) == true
                        logCollector.info(
                            TAG,
                            "Direct probe protect(socket) invoked: serviceAvailable=${service != null}, result=$protected, target=$host:$port"
                        )
                        if (!protected) return@withContext ProbeResult(Long.MAX_VALUE, false)
                    }
                    runInterruptible(Dispatchers.IO) { socket.connect(InetSocketAddress(host, port), DIRECT_PROBE_TIMEOUT_MS) }
                    val elapsed = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
                    ProbeResult(elapsed, true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logCollector.warn(TAG, "Socket probe failed for $host:$port protect=$protectFromVpn: ${e.message}")
                ProbeResult(Long.MAX_VALUE, false)
            }
        }

    private suspend fun runMicroProbe() {
        val startedAt = System.nanoTime()
        try {
            val request = Request.Builder()
                .url(MICRO_PROBE_URL)
                .header("User-Agent", "VPNProxy/1.0")
                .header("Cache-Control", "no-cache")
                .build()
            val bytesRead = withContext(Dispatchers.IO) {
                microProbeClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    val input = response.body?.byteStream()
                        ?: throw IllegalStateException("Empty micro-probe response")
                    input.use { stream ->
                        val buffer = ByteArray(8 * 1024)
                        var total = 0L
                        while (total < MICRO_PROBE_BYTES) {
                            val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), MICRO_PROBE_BYTES - total).toInt())
                            if (read < 0) break
                            total += read
                        }
                        total
                    }
                }
            }
            val elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
            val speedMbps = bytesRead * 8.0 / 1_000_000.0 / (elapsedMs / 1_000.0)
            logCollector.info(TAG, "Micro-probe downloaded $bytesRead bytes in ${elapsedMs}ms ($speedMbps Mbps)")
            if (bytesRead < MICRO_PROBE_BYTES || speedMbps < MICRO_PROBE_MIN_MBPS) {
                val last = recentEvidence.lastOrNull()
                if (last != null && shouldSwitchAfterEvidence(
                    consecutiveServerEvidence,
                    consecutiveNetworkEvidence,
                    last.directSuccess && last.directLatencyMs < DIRECT_FAST_RTT_MS
                )
                ) {
                    performSlowServerSwitch("micro-probe speed=$speedMbps Mbps, bytes=$bytesRead")
                } else {
                    transitionTo(AutoSwitchState.MONITORING, "micro-probe low speed but direct path is not proven healthy")
                }
            } else {
                transitionTo(AutoSwitchState.MONITORING, "micro-probe recovered; transient glitch")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logCollector.error(TAG, "Micro-probe timed out/failed", e)
            val last = recentEvidence.lastOrNull()
            if (last != null && shouldSwitchAfterEvidence(
                consecutiveServerEvidence,
                consecutiveNetworkEvidence,
                last.directSuccess && last.directLatencyMs < DIRECT_FAST_RTT_MS
            )
            ) {
                performSlowServerSwitch("micro-probe failed after independent server confirmations: ${e.message}")
            } else {
                transitionTo(AutoSwitchState.MONITORING, "micro-probe failed without sufficient direct-path evidence")
            }
        }
    }

    private suspend fun performSlowServerSwitch(reason: String) {
        transitionTo(AutoSwitchState.SWITCHING, reason)
        val now = System.currentTimeMillis()
        synchronized(recentSwitchesMs) {
            while (recentSwitchesMs.isNotEmpty() && now - recentSwitchesMs.peekFirst() > SWITCH_WINDOW_MS) {
                recentSwitchesMs.removeFirst()
            }
            if (recentSwitchesMs.size >= MAX_SWITCHES_PER_WINDOW) {
                transitionTo(AutoSwitchState.MONITORING, "switch limit reached; suppressing further automatic switches")
                vpnService?.showUnstableNetworkNotification()
                return
            }
        }
        val switched = switchToBest() != null
        if (switched) {
            val switchedAt = System.currentTimeMillis()
            synchronized(recentSwitchesMs) {
                recentSwitchesMs.addLast(switchedAt)
                while (recentSwitchesMs.isNotEmpty() && switchedAt - recentSwitchesMs.peekFirst() > SWITCH_WINDOW_MS) {
                    recentSwitchesMs.removeFirst()
                }
                cooldownUntilMs = switchedAt + SWITCH_COOLDOWN_MS
                if (recentSwitchesMs.size >= MAX_SWITCHES_PER_WINDOW) {
                    vpnService?.showUnstableNetworkNotification()
                    logCollector.warn(TAG, "Three automatic switches in 15 minutes; user notification shown")
                }
            }
            val server = _currentServer.value
            if (server != null) {
                Log.d(TAG, "ServerSelected event after slow-server switch: ${server.config.name}")
                connectionCoordinator.send(ConnectionEvent.ResetRetryCount)
                connectionCoordinator.send(ConnectionEvent.ServerSelected(server))
            } else {
                vpnService?.reconnectAfterSlowServerSwitch()
            }
            logCollector.info(TAG, "Slow-server switch succeeded; cooldown until $cooldownUntilMs")
        } else {
            logCollector.warn(TAG, "Slow-server switch could not select a different server")
        }
        transitionTo(AutoSwitchState.MONITORING, if (switched) "switch complete" else "switch skipped")
    }

    suspend fun switchToBest(): ScoredServer? {
        if (!switching.compareAndSet(false, true)) {
            logCollector.warn(TAG, "switchToBest ignored — already switching")
            return null
        }
        try {
            val current = _currentServer.value
            val ranked = _rankedServers.value
            val best = ranked.firstOrNull { !sameServer(current?.config, it.config) }
                ?: ranked.firstOrNull()?.takeIf { current == null }
                ?: return null
            _currentServer.value = best
            baselineRtt = best.latencyMs
            previousRtt = null
            consecutiveErrors = 0
            _switchEvents.value = "Автопереключение на ${best.config.name}"
            logCollector.warn(TAG, "Slow-server detector switched to ${best.config.name}")
            return best
        } finally {
            switching.set(false)
        }
    }

    suspend fun selectBest(configs: List<ParsedConfig>): ScoredServer? {
        Log.i(TAG, "Starting server selection/ranking for ${configs.size} configs")
        val ranked = scoringEngine.rankAll(configs, healthChecker)
        Log.i(TAG, "Server ranking completed: ${ranked.size} candidates")
        _rankedServers.value = ranked
        val best = ranked.firstOrNull()
        if (best != null) {
            _currentServer.value = best
            baselineRtt = best.latencyMs
            consecutiveErrors = 0
            logCollector.info(
                TAG,
                "Selected best server: ${best.config.name}",
                mapOf("rtt" to best.latencyMs, "score" to best.score)
            )
            Log.i(TAG, "Selected server: ${best.config.name} at ${best.config.address}:${best.config.port}; rtt=${best.latencyMs}ms score=${best.score}")
        } else {
            Log.w(TAG, "Server selection produced no candidate from ${configs.size} configs")
        }
        return best
    }

    fun forceSelect(server: ScoredServer): Boolean {
        if (!switching.compareAndSet(false, true)) {
            logCollector.warn(TAG, "forceSelect ignored — already switching")
            return false
        }
        try {
            _currentServer.value = server
            baselineRtt = server.latencyMs
            consecutiveErrors = 0
            _switchEvents.value = "Переключено на ${server.config.name}"
            logCollector.info(TAG, "Force select: ${server.config.name}")
            return true
        } finally {
            switching.set(false)
        }
    }

    suspend fun switchToNext(): ScoredServer? {
        if (!switching.compareAndSet(false, true)) {
            logCollector.warn(TAG, "switchToNext ignored — already switching")
            return null
        }
        try {
            val ranked = _rankedServers.value
            val current = _currentServer.value
            if (ranked.isEmpty()) return null
            val currentIndex = ranked.indexOfFirst {
                it.config.address == current?.config?.address
            }
            val nextIndex = (currentIndex + 1).coerceAtLeast(0) % ranked.size
            val next = ranked[nextIndex]
            if (sameServer(current?.config, next.config)) {
                _currentServer.value = next
                baselineRtt = next.latencyMs
                consecutiveErrors = 0
                logCollector.info(TAG, "Manual switch skipped — already on ${next.config.name}; metrics refreshed")
                return next
            }
            _currentServer.value = next
            baselineRtt = next.latencyMs
            consecutiveErrors = 0
            _switchEvents.value = "Переключено на ${next.config.name}"
            logCollector.info(TAG, "Manual switch to: ${next.config.name}")
            delay(50)
            return next
        } finally {
            switching.set(false)
        }
    }

    private fun reSelect() {
        if (switching.get()) return
        val ranked = _rankedServers.value
        val best = ranked.firstOrNull() ?: return
        val current = _currentServer.value
        if (sameServer(current?.config, best.config)) {
            _currentServer.value = best
            baselineRtt = best.latencyMs
            consecutiveErrors = 0
            logCollector.info(TAG, "Auto re-select skipped — already on ${best.config.name}; metrics refreshed")
            return
        }
        _currentServer.value = best
        baselineRtt = best.latencyMs
        consecutiveErrors = 0
        _switchEvents.value = "Автопереключение на ${best.config.name}"
        logCollector.info(TAG, "Auto re-selected: ${best.config.name}")
        connectionCoordinator.send(ConnectionEvent.ResetRetryCount)
        connectionCoordinator.send(ConnectionEvent.ServerSelected(best))
    }

    private fun sameServer(
        current: ParsedConfig?,
        candidate: ParsedConfig
    ): Boolean = current != null &&
        current.address.equals(candidate.address, ignoreCase = true) &&
        current.port == candidate.port &&
        current.uuid == candidate.uuid

    fun clearSwitchEvent() {
        _switchEvents.value = null
    }
}
