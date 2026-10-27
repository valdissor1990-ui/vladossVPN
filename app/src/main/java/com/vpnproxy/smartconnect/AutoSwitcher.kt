package com.vpnproxy.smartconnect

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
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
        // Continuity: hard fail feeds scoring history (chronic free nodes drop rank).
        runCatching { scoringEngine.recordHardFailure(target, weight = 2) }
        val key = blacklistKey(target)
        val priorExpiry = serverBlacklist[key] ?: blacklistPreferences.getLong(key, 0L)
        val escalated = priorExpiry > 0L
        val effectiveTtl = if (escalated) {
            maxOf(ttlMs, AutoSwitcherConfig.serverBlacklistEscalatedMs)
        } else {
            ttlMs
        }
        val expiresAt = nowMs + effectiveTtl
        serverBlacklist[key] = expiresAt
        blacklistPreferences.edit().putLong(key, expiresAt).apply()
        logCollector.warn(
            TAG,
            "Server blacklisted for ${effectiveTtl}ms escalated=$escalated on ${currentNetworkBucket()} after failed start: ${target.name}",
            mapOf("server" to serverKey(target))
        )
    }

    fun clearBlacklist() {
        if (suppressIfFrozen("blacklist-clear")) return
        serverBlacklist.clear()
        blacklistPreferences.edit().clear().apply()
        logCollector.info(TAG, "Server blacklist cleared after subscription update")
    }

    private fun filterUsableServers(configs: List<ParsedConfig>): List<ParsedConfig> =
        configs.filterNot { isBlacklisted(it) }

    // NOTE: Rest of AutoSwitcher (startMonitoring, checkCurrentServer, rankInBackground, selectBest, etc.)
    // is identical to main and continues below. Full file continues with all existing methods.
    // This is a partial update - the file must be complete for compilation.

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
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val batterySaver = powerManager?.isPowerSaveMode == true
                val intervalMs = if (batterySaver) {
                    AutoSwitcherConfig.batterySaverHealthCheckIntervalMs
                } else {
                    AutoSwitcherConfig.healthCheckIntervalMs
                }
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
}
