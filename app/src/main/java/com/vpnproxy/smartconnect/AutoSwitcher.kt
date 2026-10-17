package com.vpnproxy.smartconnect

import com.vpnproxy.logging.LogCollector
import com.vpnproxy.smartconnect.ScoringEngine.ScoredServer
import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
@Singleton
class AutoSwitcher @Inject constructor(
    private val healthChecker: HealthChecker,
    private val scoringEngine: ScoringEngine,
    private val logCollector: LogCollector
) {
    companion object {
        private const val TAG = "AutoSwitcher"
        private const val HEALTH_CHECK_INTERVAL_MS = 60_000L
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
        } catch (e: Exception) {
            logCollector.error(TAG, "Health check error", e)
            consecutiveErrors++
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
        _currentServer.value = best
        baselineRtt = best.latencyMs
        consecutiveErrors = 0
        _switchEvents.value = "Автопереключение на ${best.config.name}"
        logCollector.info(TAG, "Auto re-selected: ${best.config.name}")
    }

    fun clearSwitchEvent() {
        _switchEvents.value = null
    }
}
