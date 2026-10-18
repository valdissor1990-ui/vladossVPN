package com.vpnproxy.smartconnect

import android.net.VpnService
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withContext

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

/**
 * Автосвитчер: следит за здоровьем текущего сервера и переключается при деградации.
 */
enum class AutoSwitchState { MONITORING, SUSPECT, VALIDATING, MICRO_PROBE, SWITCHING }

@Singleton
class AutoSwitcher @Inject constructor(
    private val healthChecker: HealthChecker,
    private val scoringEngine: ScoringEngine,
    private val logCollector: LogCollector
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
        private const val MICRO_PROBE_URL = "https://speed.cloudflare.com/__down?bytes=51200"
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
    @Volatile private var vpnService: VpnService? = null
    @Volatile private var state = AutoSwitchState.MONITORING
    private var cooldownUntilMs = 0L
    private val recentSwitchesMs = ArrayDeque<Long>()
    private val microProbeClient = OkHttpClient.Builder()
        .connectTimeout(MICRO_PROBE_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(MICRO_PROBE_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .callTimeout(MICRO_PROBE_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    fun currentState(): AutoSwitchState = state

    /** Tracks the existing VPN service only for its active lifecycle. */
    fun attachVpnService(service: VpnService) {
        vpnService = service
        logCollector.info(TAG, "VpnService attached for protected direct probes")
    }

    fun detachVpnService(service: VpnService) {
        if (vpnService === service) vpnService = null
    }

    private fun transitionTo(next: AutoSwitchState, reason: String) {
        state = next
        logCollector.info(TAG, "State -> $next: $reason")
    }

    private val _switchEvents = MutableStateFlow<String?>(null)
    val switchEvents: StateFlow<String?> = _switchEvents.asStateFlow()

    /** Блокирует параллельные switchToNext / forceSelect. */
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
            val result = healthChecker.testLatency(listOf(current.config)).firstOrNull()
                ?: return

            val jitter = previousRtt?.let { kotlin.math.abs(result.latencyMs - it) } ?: 0L
            previousRtt = result.latencyMs.takeIf { result.success && it != Long.MAX_VALUE }
            val measuredRtt = if (result.success) result.latencyMs else Long.MAX_VALUE
            if (measuredRtt > SUSPECT_RTT_MS || jitter > SUSPECT_JITTER_MS) {
                if (state == AutoSwitchState.MONITORING) {
                    transitionTo(
                        AutoSwitchState.SUSPECT,
                        "RTT=${measuredRtt}ms, jitter=${jitter}ms"
                    )
                    validateSlowServer(current.config, measuredRtt, jitter)
                    return
                }
            }

            if (!result.success) {
                consecutiveErrors++
                logCollector.warn(
                    TAG,
                    "Health check failed for ${current.config.name}",
                    mapOf("errors" to consecutiveErrors)
                )
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    logCollector.warn(TAG, "Max errors reached, re-selecting")
                    reSelect()
                }
            } else {
                consecutiveErrors = 0
                if (baselineRtt > 0 && result.latencyMs > baselineRtt * RTT_DEGRADATION_FACTOR) {
                    logCollector.warn(
                        TAG,
                        "RTT degraded: ${result.latencyMs}ms vs baseline ${baselineRtt}ms"
                    )
                    reSelect()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logCollector.error(TAG, "Health check error", e)
            consecutiveErrors++
        }
    }


    private data class ProbeResult(val latencyMs: Long, val success: Boolean)

    private suspend fun validateSlowServer(
        config: ParsedConfig,
        measuredRtt: Long,
        jitterMs: Long
    ) {
        if (System.currentTimeMillis() < cooldownUntilMs || switching.get()) {
            transitionTo(AutoSwitchState.MONITORING, "cooldown or switch already active")
            return
        }
        transitionTo(AutoSwitchState.VALIDATING, "active direct-vs-VPN socket validation")
        val direct = probeSocket("1.1.1.1", 53, protectFromVpn = true)
        val vpn = probeSocket(config.address, config.port, protectFromVpn = false)
        logCollector.info(
            TAG,
            "Slow-server validation: direct=${direct.latencyMs}ms success=${direct.success}; " +
                "vpn=${vpn.latencyMs}ms success=${vpn.success}; initialRTT=${measuredRtt}ms jitter=${jitterMs}ms"
        )

        when {
            !direct.success || direct.latencyMs > SUSPECT_RTT_MS -> {
                transitionTo(AutoSwitchState.MONITORING, "underlying network is slow; reconnect manager owns recovery")
            }
            direct.latencyMs < DIRECT_FAST_RTT_MS && (!vpn.success || vpn.latencyMs > SUSPECT_RTT_MS) -> {
                transitionTo(AutoSwitchState.MICRO_PROBE, "direct network fast, VPN endpoint probe slow")
                runMicroProbe()
            }
            else -> transitionTo(AutoSwitchState.MONITORING, "validation did not confirm VPN-specific degradation")
        }
    }

    private suspend fun probeSocket(host: String, port: Int, protectFromVpn: Boolean): ProbeResult =
        withContext(Dispatchers.IO) {
            val start = System.nanoTime()
            try {
                Socket().use { socket ->
                    if (protectFromVpn) {
                        val service = vpnService
                        val protected = service?.protect(socket) == true
                        logCollector.info(
                            TAG,
                            "Direct probe protect(socket) invoked: serviceAvailable=${service != null}, result=$protected, target=$host:$port"
                        )
                        if (!protected) return@withContext ProbeResult(Long.MAX_VALUE, false)
                    }
                    socket.connect(InetSocketAddress(host, port), DIRECT_PROBE_TIMEOUT_MS)
                    val elapsed = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
                    ProbeResult(elapsed, true)
                }
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
                performSlowServerSwitch("micro-probe speed=$speedMbps Mbps, bytes=$bytesRead")
            } else {
                transitionTo(AutoSwitchState.MONITORING, "micro-probe recovered; transient glitch")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logCollector.error(TAG, "Micro-probe timed out/failed; treating server as overloaded", e)
            performSlowServerSwitch("micro-probe failed: ${e.message}")
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
                vpnService?.let {
                    it.showUnstableNetworkNotification()
                }
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
            vpnService?.reconnectAfterSlowServerSwitch()
            logCollector.info(TAG, "Slow-server switch succeeded; cooldown until $cooldownUntilMs")
        } else {
            logCollector.warn(TAG, "Slow-server switch could not select a different server")
        }
        transitionTo(AutoSwitchState.MONITORING, if (switched) "switch complete" else "switch skipped")
    }

    /** Selects the best-ranked server other than the active endpoint when possible. */
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
        val ranked = scoringEngine.rankAll(configs, healthChecker)
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
            // A transient RTT spike must not trigger a reconnect to the same endpoint.
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
