package com.vpnproxy.smartconnect

import android.util.Log
import com.vpnproxy.core.connection.ConnectionCoordinator
import com.vpnproxy.core.connection.ConnectionEvent
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.service.VpnProxyService
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

enum class AutoSwitchState { MONITORING, SUSPECT, VALIDATING, MICRO_PROBE, SWITCHING }

@Singleton
class AutoSwitcher @Inject constructor(
    private val healthChecker: HealthChecker,
    private val scoringEngine: ScoringEngine,
    private val logCollector: LogCollector,
    private val connectionCoordinator: ConnectionCoordinator
) {
    companion object {
        private const val TAG = "AutoSwitcher"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val healthMonitor = HealthMonitor(logCollector, healthChecker)
    private val decisionEngine = SwitchDecisionEngine()
    private val serverBlacklist = ConcurrentHashMap<String, Long>()
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

    private fun serverKey(config: ParsedConfig): String =
        "${config.address.lowercase()}:${config.port}:${config.uuid.lowercase()}"

    private fun pruneBlacklist(nowMs: Long = System.currentTimeMillis()) {
        serverBlacklist.entries.removeIf { it.value <= nowMs }
    }

    fun isBlacklisted(config: ParsedConfig?, nowMs: Long = System.currentTimeMillis()): Boolean {
        val target = config ?: return false
        pruneBlacklist(nowMs)
        val expiresAt = serverBlacklist[serverKey(target)] ?: return false
        if (expiresAt <= nowMs) {
            serverBlacklist.remove(serverKey(target))
            return false
        }
        return true
    }

    fun markServerFailed(config: ParsedConfig?, ttlMs: Long = AutoSwitcherConfig.serverBlacklistDurationMs, nowMs: Long = System.currentTimeMillis()) {
        val target = config ?: return
        val key = serverKey(target)
        serverBlacklist[key] = nowMs + ttlMs
        logCollector.warn(TAG, "Server blacklisted for ${ttlMs}ms after failed start: ${target.name}", mapOf("server" to key))
    }

    private fun filterUsableServers(configs: List<ParsedConfig>): List<ParsedConfig> =
        configs.filterNot { isBlacklisted(it) }

    fun startMonitoring() {
        healthCheckJob?.cancel()
        healthCheckJob = scope.launch {
            while (isActive) {
                delay(AutoSwitcherConfig.healthCheckIntervalMs)
                if (decisionEngine.isInCooldown()) {
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
        if (isBlacklisted(current.config)) {
            logCollector.info(TAG, "Current server is blacklisted; monitoring pause for ${current.config.name}")
            return
        }

        try {
            val result = healthMonitor.checkSelectedServer(current.config) ?: return
            val jitter = healthMonitor.calculateJitter(previousRtt, result.latencyMs)
            previousRtt = result.latencyMs.takeIf { result.success && it != Long.MAX_VALUE }

            if (!result.success || result.latencyMs == Long.MAX_VALUE) {
                consecutiveErrors++
                logCollector.warn(
                    TAG,
                    "Health check failed for ${current.config.name}",
                    mapOf("errors" to consecutiveErrors)
                )
                if (consecutiveErrors >= AutoSwitcherConfig.maxConsecutiveErrors) {
                    logCollector.warn(TAG, "Repeated health-check failures; require direct-vs-VPN validation before any switch")
                }
                if (state == AutoSwitchState.MONITORING) {
                    transitionTo(AutoSwitchState.SUSPECT, "health check failed; errors=$consecutiveErrors")
                    validateSlowServer(current.config, Long.MAX_VALUE, jitter)
                }
                return
            }

            consecutiveErrors = 0
            val baselineDegraded = baselineRtt > 0 && result.latencyMs > baselineRtt * AutoSwitcherConfig.rttDegradationFactor
            if (baselineDegraded || result.latencyMs > AutoSwitcherConfig.suspectRttMs || jitter > AutoSwitcherConfig.suspectJitterMs) {
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
        if (decisionEngine.isInCooldown() || switching.get()) {
            transitionTo(AutoSwitchState.MONITORING, "cooldown or switch already active")
            return
        }
        transitionTo(AutoSwitchState.VALIDATING, "active direct-vs-VPN socket validation")
        val direct = healthMonitor.probeSocket("1.1.1.1", AutoSwitcherConfig.directProbePort, protectFromVpn = true) { socket ->
            val service = vpnService
            val protected = service?.protect(socket) == true
            logCollector.info(TAG, "Direct probe protect(socket) invoked: serviceAvailable=${service != null}, result=$protected, target=1.1.1.1:${AutoSwitcherConfig.directProbePort}")
            protected
        }
        val vpn = healthMonitor.probeSocket(config.address, config.port, protectFromVpn = false) { false }
        decisionEngine.recordEvidence(direct, vpn, jitterMs)

        val classification = decisionEngine.classify(direct, vpn)
        decisionEngine.record(classification)
        logCollector.info(
            TAG,
            "Slow-server validation: direct=${direct.latencyMs}ms success=${direct.success}; " +
                "vpn=${vpn.latencyMs}ms success=${vpn.success}; initialRTT=${measuredRtt}ms jitter=${jitterMs}ms; " +
                "classification=$classification serverEvidence=${decisionEngine.consecutiveServerEvidence} networkEvidence=${decisionEngine.consecutiveNetworkEvidence}"
        )
        when (classification) {
            NetworkFaultKind.NETWORK_DEGRADATION -> {
                val networkConfirmations = decisionEngine.consecutiveNetworkEvidence
                val networkStatus = if (networkConfirmations >= AutoSwitcherConfig.networkDegradationConfirmations) {
                    "underlying network degradation confirmed $networkConfirmations times; automatic server switching suppressed"
                } else {
                    "underlying network degradation suspected ($networkConfirmations/${AutoSwitcherConfig.networkDegradationConfirmations}); automatic server switching suppressed"
                }
                transitionTo(AutoSwitchState.MONITORING, networkStatus)
            }
            NetworkFaultKind.SERVER_DEGRADATION -> {
                val serverConfirmations = decisionEngine.consecutiveServerEvidence
                if (serverConfirmations < AutoSwitcherConfig.serverDegradationConfirmations) {
                    transitionTo(AutoSwitchState.MONITORING, "server degradation confirmation $serverConfirmations/${AutoSwitcherConfig.serverDegradationConfirmations}; awaiting independent confirmation")
                } else {
                    transitionTo(AutoSwitchState.MICRO_PROBE, "server degradation confirmed twice; checking transport")
                    runMicroProbe()
                }
            }
            NetworkFaultKind.NONE -> {
                transitionTo(AutoSwitchState.MONITORING, "validation did not confirm degradation")
            }
        }
    }

    internal fun classifyFault(direct: ProbeResult, vpn: ProbeResult): NetworkFaultKind =
        classifyProbeFault(direct, vpn, AutoSwitcherConfig.suspectRttMs, AutoSwitcherConfig.directFastRttMs)

    private suspend fun runMicroProbe() {
        try {
            val result = healthMonitor.probeMicroSpeed()
            val bytesRead = result.bytesRead
            val speedMbps = result.speedMbps
            logCollector.info(TAG, "Micro-probe downloaded $bytesRead bytes in ${result.elapsedMs}ms ($speedMbps Mbps)")
            if (bytesRead < AutoSwitcherConfig.microProbeBytes || speedMbps < AutoSwitcherConfig.microProbeMinMbps) {
                val last = decisionEngine.latestEvidence()
                if (last != null && decisionEngine.shouldSwitch(
                        last.directSuccess && last.directLatencyMs < AutoSwitcherConfig.directFastRttMs
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
            val last = decisionEngine.latestEvidence()
            if (last != null && decisionEngine.shouldSwitch(
                    last.directSuccess && last.directLatencyMs < AutoSwitcherConfig.directFastRttMs
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
        if (!decisionEngine.canAttemptSwitch(now)) {
            transitionTo(AutoSwitchState.MONITORING, "switch limit reached; suppressing further automatic switches")
            vpnService?.showUnstableNetworkNotification()
            return
        }
        val switched = switchToBest() != null
        if (switched) {
            val switchedAt = System.currentTimeMillis()
            val switchLimitReached = decisionEngine.recordSuccessfulSwitch(switchedAt)
            if (switchLimitReached) {
                vpnService?.showUnstableNetworkNotification()
                logCollector.warn(TAG, "Three automatic switches in 15 minutes; user notification shown")
            }
            val server = _currentServer.value
            if (server != null) {
                Log.d(TAG, "ServerSelected event after slow-server switch: ${server.config.name}")
                connectionCoordinator.send(ConnectionEvent.ResetRetryCount)
                connectionCoordinator.send(ConnectionEvent.ServerSelected(server))
            } else {
                vpnService?.reconnectAfterSlowServerSwitch()
            }
            logCollector.info(TAG, "Slow-server switch succeeded; cooldown until ${decisionEngine.cooldownUntilMs}")
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
            val ranked = _rankedServers.value.filter { !isBlacklisted(it.config) }
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

    private fun isRussian(config: ParsedConfig): Boolean {
        val name = config.name.lowercase()
        val provider = config.providerTag.lowercase()
        val sni = config.sni.orEmpty().lowercase()
        val ruToken = Regex(AutoSwitcherConfig.ruTokenPattern)
        return config.providerTag.contains("🇷🇺") ||
            ruToken.containsMatchIn(provider) ||
            config.name.contains("🇷🇺") ||
            ruToken.containsMatchIn(name) ||
            name.contains(".ru") || sni.contains(".ru")
    }

    private fun sameConfig(a: ParsedConfig, b: ParsedConfig): Boolean =
        a.address.equals(b.address, ignoreCase = true) && a.port == b.port && a.uuid == b.uuid

    /** Set the chosen server immediately; no latency probes run on the connection path. */
    fun selectImmediate(config: ParsedConfig, candidates: List<ParsedConfig>): ScoredServer {
        val filtered = candidates.filterNot { isBlacklisted(it) }
        val selected = ScoredServer(config, Long.MAX_VALUE, 0f, 0f, 0f)
        _currentServer.value = selected
        _rankedServers.value = listOf(selected) + filtered
            .filterNot { sameConfig(it, config) }
            .map { ScoredServer(it, Long.MAX_VALUE, 0f, 0f, 0f) }
        baselineRtt = 0L
        consecutiveErrors = 0
        logCollector.info(TAG, "Instant connect to [${config.name}] — background probing scheduled")
        return selected
    }

    /** Probe RU endpoints first, then the rest; ranking never blocks initial connection. */
    fun rankInBackground(configs: List<ParsedConfig>) {
        val allowed = filterUsableServers(configs)
        if (allowed.isEmpty()) {
            logCollector.warn(TAG, "All candidate servers are blacklisted; background ranking skipped")
            return
        }
        scope.launch {
            try {
                val russian = allowed.filter(::isRussian)
                val foreign = allowed.filterNot(::isRussian)
                val ruResults = if (russian.isNotEmpty()) healthChecker.testLatency(russian) else emptyList()
                val foreignResults = if (foreign.isNotEmpty()) healthChecker.testLatency(foreign) else emptyList()
                val ranked = scoringEngine.computeScore(ruResults + foreignResults)
                    .sortedWith(compareBy<ScoredServer>({ if (isRussian(it.config)) 0 else 1 }, { it.latencyMs }))
                if (ranked.isEmpty()) {
                    val currentName = _currentServer.value?.config?.name ?: "unknown"
                    logCollector.warn(
                        TAG,
                        "0 reachable servers — network may be blocked; keeping current server [$currentName], automatic switching suppressed"
                    )
                    return@launch
                }
                _rankedServers.value = ranked
                logCollector.info(TAG, "Background ranking completed: ${ranked.size} reachable servers; RU probed first")

                val bestRussian = ranked.firstOrNull { isRussian(it.config) && it.latencyMs < AutoSwitcherConfig.ruFastServerRttMs }
                val current = _currentServer.value
                if (bestRussian != null && (current == null || !sameConfig(current.config, bestRussian.config)) &&
                    connectionCoordinator.state.value is com.vpnproxy.core.connection.ConnectionState.Connected
                ) {
                    _currentServer.value = bestRussian
                    baselineRtt = bestRussian.latencyMs
                    consecutiveErrors = 0
                    _switchEvents.value = "Автопереключение на быстрый сервер РФ: ${bestRussian.config.name}"
                    connectionCoordinator.send(ConnectionEvent.ResetRetryCount)
                    connectionCoordinator.send(ConnectionEvent.ServerSelected(bestRussian))
                    logCollector.info(TAG, "Fast RU server found (${bestRussian.latencyMs}ms); switched to ${bestRussian.config.name}")
                } else if (bestRussian != null && current != null && sameConfig(current.config, bestRussian.config)) {
                    _currentServer.value = bestRussian
                    baselineRtt = bestRussian.latencyMs
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                logCollector.warn(TAG, "Background server ranking failed: ${t.message}")
            }
        }
    }

    suspend fun selectBest(configs: List<ParsedConfig>): ScoredServer? {
        val allowed = filterUsableServers(configs)
        if (allowed.isEmpty()) {
            logCollector.warn(TAG, "No non-blacklisted servers available for ranking")
            return null
        }
        Log.i(TAG, "Starting server selection/ranking for ${allowed.size} configs")
        val ranked = scoringEngine.rankAll(allowed, healthChecker)
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
            Log.w(TAG, "Server selection produced no candidate from ${allowed.size} configs")
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
            val ranked = _rankedServers.value.filter { !isBlacklisted(it.config) }
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
            delay(AutoSwitcherConfig.manualSwitchDelayMs)
            return next
        } finally {
            switching.set(false)
        }
    }

    private fun reSelect() {
        if (switching.get()) return
        val ranked = _rankedServers.value.filter { !isBlacklisted(it.config) }
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
