package com.vpnproxy.smartconnect

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.vpnproxy.core.connection.ConnectionCoordinator
import com.vpnproxy.core.connection.ConnectionEvent
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.service.VpnProxyService
import com.vpnproxy.smartconnect.ScoringEngine.ScoredServer
import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import dagger.hilt.android.qualifiers.ApplicationContext
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
import java.security.MessageDigest
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
    private val connectionCoordinator: ConnectionCoordinator,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AutoSwitcher"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val healthMonitor = HealthMonitor(logCollector, healthChecker)
    private val decisionEngine = SwitchDecisionEngine()
    // Persist failure TTLs across process death and APK updates. Keys include transport so
    // a failure on cellular cannot poison the same profile on Wi-Fi (or vice versa).
    private val blacklistPreferences = context.getSharedPreferences("server_failure_blacklist_v2", Context.MODE_PRIVATE)
    private val serverBlacklist = ConcurrentHashMap<String, Long>().apply {
        blacklistPreferences.all.forEach { (key, value) ->
            if (value is Long) put(key, value)
        }
    }
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

    // A selected subscription is a hard boundary for every automatic switch path.
    // null means the user explicitly selected "All subscriptions".
    @Volatile private var allowedProviderTag: String? = null
    @Volatile private var providerScopeBlocked: Boolean = false
    @Volatile private var providerScopeGeneration: Long = 0L
    @Volatile private var rankingJob: Job? = null

    fun setProviderScope(providerTag: String?, blocked: Boolean = false) {
        val normalized = providerTag?.takeIf { it.isNotBlank() }
        if (allowedProviderTag == normalized && providerScopeBlocked == blocked) return
        allowedProviderTag = normalized
        providerScopeBlocked = blocked
        providerScopeGeneration += 1
        rankingJob?.cancel()
        _rankedServers.value = _rankedServers.value.filter { isWithinProviderScope(it.config) }
        val scopeLabel = if (blocked) "BLOCKED" else normalized ?: "ALL"
        logCollector.info(TAG, "Automatic provider scope changed: $scopeLabel")
    }

    private fun isWithinProviderScope(config: ParsedConfig): Boolean =
        !providerScopeBlocked && (allowedProviderTag?.let { it == config.providerTag } ?: true)

    @Volatile private var connectedAtMs: Long = 0L
    @Volatile private var diagnosticFreezeAfterConnected: Boolean = false

    fun setDiagnosticFreeze(enabled: Boolean) {
        diagnosticFreezeAfterConnected = enabled
        if (enabled) logCollector.info(TAG, "Diagnostic AutoSwitcher freeze armed after Connected")
    }

    fun clearDiagnosticFreeze() { diagnosticFreezeAfterConnected = false }

    private fun isDiagnosticFreezeActive(): Boolean {
        if (!diagnosticFreezeAfterConnected) return false
        return when (connectionCoordinator.state.value) {
            is com.vpnproxy.core.connection.ConnectionState.Connecting,
            is com.vpnproxy.core.connection.ConnectionState.Verifying,
            is com.vpnproxy.core.connection.ConnectionState.Connected,
            is com.vpnproxy.core.connection.ConnectionState.Reconnecting -> true
            else -> false
        }
    }

    private fun suppressIfFrozen(action: String, target: String = "none"): Boolean {
        if (!isDiagnosticFreezeActive()) return false
        logCollector.warn(TAG, "AutoSwitcher suppressed: action=$action target=$target reason=diagnostic freeze")
        return true
    }

    fun isSwitching(): Boolean = switching.get()

    fun markConnected() {
        connectedAtMs = System.currentTimeMillis()
        logCollector.info(TAG, "Warmup started ${AutoSwitcherConfig.postConnectWarmupMs}ms")
    }

    fun isInPostConnectWarmup(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (connectedAtMs <= 0L) return false
        return nowMs - connectedAtMs < AutoSwitcherConfig.postConnectWarmupMs
    }

    // Blacklist the exact profile, not every variant sharing endpoint/UUID.
    // Hash profile identity before persisting it; preference keys do not expose config details.
    private fun serverKey(config: ParsedConfig): String =
        MessageDigest.getInstance("SHA-256")
            .digest(config.connectionKey().toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun currentNetworkBucket(): String {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "other"
        val active = manager.activeNetwork
        val activeCapabilities = active?.let(manager::getNetworkCapabilities)
        fun bucket(capabilities: NetworkCapabilities?): String? = when {
            capabilities == null -> null
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "other"
            else -> null
        }
        bucket(activeCapabilities)?.let { return it }

        // With VpnService active, activeNetwork is normally TRANSPORT_VPN. Inspect
        // validated non-VPN networks to identify the underlying Wi-Fi/mobile transport.
        val underlying = manager.allNetworks
            .asSequence()
            .filter { it != active }
            .mapNotNull { network -> manager.getNetworkCapabilities(network) }
            .filter { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) }
            .filter { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
            .filter { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
            .mapNotNull(::bucket)
            .firstOrNull { it == "wifi" || it == "cellular" }
        return underlying ?: if (active == null) "offline" else "other"
    }

    private fun blacklistKey(config: ParsedConfig): String =
        "${currentNetworkBucket()}|${serverKey(config)}"

    private fun pruneBlacklist(nowMs: Long = System.currentTimeMillis()) {
        val expired = serverBlacklist.entries.filter { it.value <= nowMs }.map { it.key }
        expired.forEach { key ->
            serverBlacklist.remove(key)
            blacklistPreferences.edit().remove(key).apply()
        }
    }

    fun isBlacklisted(config: ParsedConfig?, nowMs: Long = System.currentTimeMillis()): Boolean {
        val target = config ?: return false
        pruneBlacklist(nowMs)
        val key = blacklistKey(target)
        val expiresAt = serverBlacklist[key] ?: return false
        if (expiresAt <= nowMs) {
            serverBlacklist.remove(key)
            blacklistPreferences.edit().remove(key).apply()
            return false
        }
        return true
    }

    fun markServerFailed(config: ParsedConfig?, ttlMs: Long = AutoSwitcherConfig.serverBlacklistDurationMs, nowMs: Long = System.currentTimeMillis()) {
        val target = config ?: return
        if (suppressIfFrozen("markServerFailed", target.name)) return
        val key = blacklistKey(target)
        val expiresAt = nowMs + ttlMs
        serverBlacklist[key] = expiresAt
        blacklistPreferences.edit().putLong(key, expiresAt).apply()
        logCollector.warn(TAG, "Server blacklisted for ${ttlMs}ms on ${currentNetworkBucket()} after failed start: ${target.name}", mapOf("server" to serverKey(target)))
    }

    fun clearBlacklist() {
        if (suppressIfFrozen("blacklist-clear")) return
        serverBlacklist.clear()
        blacklistPreferences.edit().clear().apply()
        logCollector.info(TAG, "Server blacklist cleared after subscription update")
    }

    private fun filterUsableServers(configs: List<ParsedConfig>): List<ParsedConfig> =
        configs.filterNot { isBlacklisted(it) }

    fun startMonitoring() {
        if (isDiagnosticFreezeActive()) {
            logCollector.info(TAG, "AutoSwitcher monitoring disabled by diagnostic freeze")
            stopMonitoring()
            return
        }
        markConnected()
        healthCheckJob?.cancel()
        healthCheckJob = scope.launch {
            while (isActive) {
                val intervalMs = BatteryPolicy.healthCheckIntervalMs(context)
                delay(intervalMs)
                if (isInPostConnectWarmup()) {
                    logCollector.debug(TAG, "Warmup active — skip health-driven switch checks")
                    continue
                }
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
        if (!BatteryPolicy.shouldRunMicroProbe(context)) {
            logCollector.info(TAG, "Micro-probe skipped under battery saver policy")
            transitionTo(AutoSwitchState.MONITORING, "battery policy skips micro-probe")
            return
        }
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
        if (suppressIfFrozen("fallback", reason)) {
            transitionTo(AutoSwitchState.MONITORING, "diagnostic freeze")
            return
        }
        if (isInPostConnectWarmup()) {
            logCollector.info(TAG, "Slow-server switch ignored during warmup: $reason")
            transitionTo(AutoSwitchState.MONITORING, "warmup")
            return
        }
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
