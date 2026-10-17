package com.vpnproxy.ui.home

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vpnproxy.core.ping.LatencyBreakdown
import com.vpnproxy.core.ping.LatencyCalculator
import com.vpnproxy.core.ping.PingService
import com.vpnproxy.core.ping.PingTargets
import com.vpnproxy.core.tunnel.TrafficStatsCollector
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.service.VpnProxyService
import com.vpnproxy.smartconnect.AutoSwitcher
import com.vpnproxy.smartconnect.ScoringEngine
import com.vpnproxy.subscription.SubscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val subscriptionRepository: SubscriptionRepository,
    private val autoSwitcher: AutoSwitcher,
    private val scoringEngine: ScoringEngine,
    private val trafficCollector: TrafficStatsCollector,
    private val pingService: PingService,
    private val logCollector: LogCollector
) : ViewModel() {

    companion object {
        private const val TAG = "HomeVM"
        private const val MAX_FALLBACK_ATTEMPTS = 5
    }

    data class UiState(
        val connectionState: ConnectionUiState = ConnectionUiState.DISCONNECTED,
        val currentRtt: Long = 0L,
        val currentServerName: String = "—",
        val serverCount: Int = 0,
        val isBusy: Boolean = false,
        val sessionStartedAt: Long? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val traffic: StateFlow<TrafficStatsCollector.TrafficSnapshot> = trafficCollector.traffic

    private val _googleRtt = MutableStateFlow<Long?>(null)
    val googleRtt: StateFlow<Long?> = _googleRtt.asStateFlow()

    private val _latencyBreakdown = MutableStateFlow<LatencyBreakdown?>(null)
    val latencyBreakdown: StateFlow<LatencyBreakdown?> = _latencyBreakdown.asStateFlow()

    private var pingJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private val connectTimeoutMs = 20_000L
    private var fallbackAttempts = 0
    private val attemptedServerKeys = mutableSetOf<String>()
    private val isFallingBack = AtomicBoolean(false)

    private fun serverKey(address: String, port: Int): String = "$address:$port"

    private var vpnPermissionRequester: (() -> Unit)? = null

    init {
        viewModelScope.launch {
            VpnProxyService.isRunning.collect { running ->
                val prev = _uiState.value.connectionState
                if (running) {
                    fallbackAttempts = 0
                    attemptedServerKeys.clear()
                    isFallingBack.set(false)
                    connectTimeoutJob?.cancel()
                    connectTimeoutJob = null
                    VpnProxyService.clearError()
                    val sessionStart = if (prev != ConnectionUiState.CONNECTED) {
                        System.currentTimeMillis()
                    } else {
                        _uiState.value.sessionStartedAt
                    }
                    _uiState.value = _uiState.value.copy(
                        connectionState = ConnectionUiState.CONNECTED,
                        isBusy = false,
                        sessionStartedAt = sessionStart
                    )
                } else if (_uiState.value.connectionState != ConnectionUiState.CONNECTING) {
                    _uiState.value = _uiState.value.copy(
                        connectionState = ConnectionUiState.DISCONNECTED,
                        isBusy = false,
                        sessionStartedAt = null
                    )
                }
            }
        }

        viewModelScope.launch {
            VpnProxyService.lastError.collect { err ->
                if (err == null) return@collect
                logCollector.warn(TAG, "Service error: $err")
                VpnProxyService.clearError()
                connectTimeoutJob?.cancel()
                connectTimeoutJob = null
                if (_uiState.value.connectionState == ConnectionUiState.CONNECTING ||
                    _uiState.value.isBusy
                ) {
                    // The previous fallback attempt has received a terminal service error.
                    // Release the gate before scheduling the next attempt.
                    isFallingBack.set(false)
                    tryNextServer()
                } else {
                    logCollector.warn(TAG, "Ignoring stale service error outside connection attempt")
                }
            }
        }

        viewModelScope.launch {
            autoSwitcher.currentServer.collect { server ->
                if (server != null) {
                    _uiState.value = _uiState.value.copy(
                        currentServerName = server.config.name,
                        currentRtt = server.latencyMs
                    )
                }
            }
        }

        viewModelScope.launch {
            autoSwitcher.switchEvents.collect { event ->
                if (event != null) {
                    logCollector.info(TAG, "Switch event: $event")
                    autoSwitcher.clearSwitchEvent()
                }
            }
        }

        startPinging()
    }

    private fun startPinging() {
        if (pingJob?.isActive == true) return
        pingJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val region = PingTargets.detectRegion(context)
                    val targets = PingTargets.forRegion(region)
                    val multi = pingService.pingAll(targets = targets, region = region)
                    val breakdown = LatencyCalculator.compute(multi.results)
                    _latencyBreakdown.value = breakdown
                    if (breakdown.isValid) {
                        val score = breakdown.compositeScore.toLong()
                        _googleRtt.value = score
                        logCollector.debug(
                            TAG,
                            "Ping OK: ${score}ms via TCP (region=$region, ok=${breakdown.successCount}/${multi.results.size}, median=${breakdown.medianMs?.toInt()}ms)"
                        )
                    } else {
                        _googleRtt.value = null
                        logCollector.warn(
                            TAG,
                            "Ping failed: insufficient samples (${breakdown.successCount}/${multi.results.size}) region=$region"
                        )
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    _googleRtt.value = null
                    _latencyBreakdown.value = null
                    logCollector.warn(TAG, "Ping exception: ${t.message}")
                }
                delay(30_000)
            }
        }
    }

    fun setVpnPermissionRequester(requester: () -> Unit) {
        vpnPermissionRequester = requester
    }

    fun toggleConnection() {
        val current = _uiState.value.connectionState
        when (current) {
            ConnectionUiState.CONNECTED, ConnectionUiState.UNSTABLE -> disconnect()
            ConnectionUiState.DISCONNECTED, ConnectionUiState.ERROR -> connect()
            ConnectionUiState.CONNECTING -> { }
        }
    }

    private fun connect() {
        fallbackAttempts = 0
        attemptedServerKeys.clear()
        isFallingBack.set(false)
        VpnProxyService.clearError()
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionUiState.CONNECTING,
            isBusy = true
        )
        connectTimeoutJob?.cancel()
        connectTimeoutJob = viewModelScope.launch {
            delay(connectTimeoutMs)
            if (_uiState.value.connectionState == ConnectionUiState.CONNECTING) {
                logCollector.warn(TAG, "Connect timeout ${connectTimeoutMs}ms — forcing reset")
                forceDisconnect()
            }
        }
        vpnPermissionRequester?.invoke()
    }

    fun onVpnPermissionGranted() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(connectionState = ConnectionUiState.CONNECTING)

                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val hasNetwork = cm.activeNetwork != null

                var configs = subscriptionRepository.getAllConfigs()
                if (configs.isEmpty() && hasNetwork) {
                    configs = try {
                        subscriptionRepository.refreshAll()
                        subscriptionRepository.getAllConfigs()
                    } catch (c: CancellationException) {
                        throw c
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
                if (configs.isEmpty() && !hasNetwork) {
                    logCollector.warn(TAG, "No network — attempting cached configs")
                    configs = subscriptionRepository.getCachedConfigs()
                }

                if (configs.isEmpty()) {
                    logCollector.warn(TAG, "No servers available for connection")
                    connectTimeoutJob?.cancel()
                    connectTimeoutJob = null
                    _uiState.value = _uiState.value.copy(
                        connectionState = ConnectionUiState.ERROR,
                        isBusy = false
                    )
                    return@launch
                }

                _uiState.value = _uiState.value.copy(serverCount = configs.size)

                val best = autoSwitcher.selectBest(configs)
                if (best == null) {
                    logCollector.warn(TAG, "No server passed health check")
                    connectTimeoutJob?.cancel()
                    connectTimeoutJob = null
                    _uiState.value = _uiState.value.copy(
                        connectionState = ConnectionUiState.ERROR,
                        isBusy = false
                    )
                    return@launch
                }

                attemptedServerKeys.add(serverKey(best.config.address, best.config.port))
                startVpnService()
            } catch (e: Exception) {
                logCollector.error(TAG, "Connect failed", e)
                connectTimeoutJob?.cancel()
                connectTimeoutJob = null
                _uiState.value = _uiState.value.copy(
                    connectionState = ConnectionUiState.ERROR,
                    isBusy = false
                )
            }
        }
    }

    fun onVpnPermissionDenied() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionUiState.DISCONNECTED,
            isBusy = false
        )
        logCollector.warn(TAG, "VPN permission denied by user")
    }

    fun disconnect() {
        fallbackAttempts = 0
        attemptedServerKeys.clear()
        isFallingBack.set(false)
        VpnProxyService.clearError()
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        val intent = Intent(context, VpnProxyService::class.java).apply {
            action = VpnProxyService.ACTION_STOP
        }
        safeStartService(intent)
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionUiState.DISCONNECTED,
            sessionStartedAt = null
        )
    }

    fun forceDisconnect() {
        fallbackAttempts = 0
        attemptedServerKeys.clear()
        isFallingBack.set(false)
        VpnProxyService.clearError()
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        val intent = Intent(context, VpnProxyService::class.java).apply {
            action = VpnProxyService.ACTION_STOP
        }
        safeStartService(intent)
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionUiState.DISCONNECTED,
            isBusy = false,
            currentServerName = "—",
            currentRtt = 0L,
            sessionStartedAt = null
        )
        logCollector.info(TAG, "Force disconnect")
    }

    fun switchServer() {
        if (isFallingBack.get() || autoSwitcher.isSwitching()) {
            logCollector.warn(TAG, "switchServer ignored — busy")
            return
        }
        viewModelScope.launch {
            try {
                logCollector.info(TAG, "Switch server requested")

                val ranked = autoSwitcher.rankedServers.value
                val configs = subscriptionRepository.getAllConfigs()
                if (ranked.isEmpty() && configs.isEmpty()) {
                    logCollector.warn(TAG, "Switch: no servers available")
                    _uiState.value = _uiState.value.copy(
                        connectionState = ConnectionUiState.ERROR,
                        isBusy = false
                    )
                    return@launch
                }

                if (ranked.isEmpty()) {
                    logCollector.info(TAG, "Switch: ranking not ready, running selectBest")
                    autoSwitcher.selectBest(configs)
                }

                _uiState.value = _uiState.value.copy(isBusy = true)

                val next = autoSwitcher.switchToNext()
                if (next == null) {
                    logCollector.warn(TAG, "Switch: switchToNext returned null")
                    _uiState.value = _uiState.value.copy(isBusy = false)
                    return@launch
                }

                _uiState.value = _uiState.value.copy(
                    currentServerName = next.config.name,
                    currentRtt = next.latencyMs
                )

                val running = VpnProxyService.isRunning.value
                val action = if (running) VpnProxyService.ACTION_RECONNECT
                             else VpnProxyService.ACTION_START
                val intent = Intent(context, VpnProxyService::class.java).apply {
                    this.action = action
                }
                if (!safeStartService(intent)) {
                    _uiState.value = _uiState.value.copy(isBusy = false)
                    return@launch
                }

                delay(300)
                _uiState.value = _uiState.value.copy(isBusy = false)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                logCollector.error(TAG, "Switch failed", e)
                _uiState.value = _uiState.value.copy(isBusy = false)
            }
        }
    }

    private fun tryNextServer() {
        if (!isFallingBack.compareAndSet(false, true)) {
            logCollector.warn(TAG, "tryNextServer ignored — already falling back")
            return
        }
        viewModelScope.launch {
            // Keep the fallback gate locked after requesting service start. It is released
            // by a terminal service event (connected/error/timeout), not by startService().
            var awaitingServiceResult = false
            try {
                if (fallbackAttempts >= MAX_FALLBACK_ATTEMPTS) {
                    logCollector.error(TAG, "Fallback exhausted after $MAX_FALLBACK_ATTEMPTS attempts")
                    fallbackAttempts = 0
                    attemptedServerKeys.clear()
                    _uiState.value = _uiState.value.copy(
                        connectionState = ConnectionUiState.ERROR,
                        isBusy = false
                    )
                    val stopIntent = Intent(context, VpnProxyService::class.java).apply {
                        action = VpnProxyService.ACTION_STOP
                    }
                    safeStartService(stopIntent)
                    return@launch
                }

                if (autoSwitcher.rankedServers.value.isEmpty()) {
                    val configs = subscriptionRepository.getAllConfigs()
                    if (configs.isNotEmpty()) autoSwitcher.selectBest(configs)
                }

                var next: ScoringEngine.ScoredServer? = null
                val rankedCount = autoSwitcher.rankedServers.value.size
                for (i in 0 until rankedCount) {
                    val candidate = autoSwitcher.switchToNext() ?: break
                    val key = serverKey(candidate.config.address, candidate.config.port)
                    if (!attemptedServerKeys.contains(key)) {
                        attemptedServerKeys.add(key)
                        next = candidate
                        break
                    }
                }

                if (next == null) {
                    logCollector.error(TAG, "Fallback exhausted: no unattempted servers remain")
                    fallbackAttempts = 0
                    attemptedServerKeys.clear()
                    _uiState.value = _uiState.value.copy(
                        connectionState = ConnectionUiState.ERROR,
                        isBusy = false
                    )
                    val stopIntent = Intent(context, VpnProxyService::class.java).apply {
                        action = VpnProxyService.ACTION_STOP
                    }
                    safeStartService(stopIntent)
                    return@launch
                }

                fallbackAttempts++
                logCollector.info(TAG, "Fallback attempt $fallbackAttempts → ${next.config.name}")
                _uiState.value = _uiState.value.copy(
                    connectionState = ConnectionUiState.CONNECTING,
                    currentServerName = next.config.name,
                    currentRtt = next.latencyMs,
                    isBusy = true
                )

                connectTimeoutJob?.cancel()
                connectTimeoutJob = viewModelScope.launch {
                    delay(connectTimeoutMs)
                    if (_uiState.value.connectionState == ConnectionUiState.CONNECTING) {
                        logCollector.warn(TAG, "Fallback connect timeout ${connectTimeoutMs}ms — forcing reset")
                        forceDisconnect()
                    }
                }
                delay(500)
                awaitingServiceResult = true
                startVpnService()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                logCollector.error(TAG, "Fallback failed", e)
                _uiState.value = _uiState.value.copy(
                    connectionState = ConnectionUiState.ERROR,
                    isBusy = false
                )
            } finally {
                // On a normal request, keep the gate until the service reports success/error.
                // All terminal paths before requesting a start release it immediately.
                if (!awaitingServiceResult) isFallingBack.set(false)
            }
        }
    }

    private fun startVpnService() {
        val intent = Intent(context, VpnProxyService::class.java).apply {
            action = VpnProxyService.ACTION_START
        }
        if (safeStartService(intent)) {
            logCollector.info(TAG, "VPN service start requested")
        } else {
            isFallingBack.set(false)
            connectTimeoutJob?.cancel()
            connectTimeoutJob = null
            _uiState.value = _uiState.value.copy(
                connectionState = ConnectionUiState.ERROR,
                isBusy = false
            )
        }
    }

    private fun safeStartService(intent: Intent): Boolean {
        return try {
            if (intent.action == VpnProxyService.ACTION_STOP ||
                android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O
            ) {
                context.startService(intent)
            } else {
                context.startForegroundService(intent)
            }
            true
        } catch (t: Throwable) {
            logCollector.error(TAG, "startService failed: ${t.message}", t)
            false
        }
    }

    fun onServerSelected(server: ScoringEngine.ScoredServer) {
        viewModelScope.launch {
            try {
                autoSwitcher.forceSelect(server)
                _uiState.value = _uiState.value.copy(
                    currentServerName = server.config.name,
                    currentRtt = server.latencyMs
                )
                val running = VpnProxyService.isRunning.value
                val action = if (running) VpnProxyService.ACTION_RECONNECT else VpnProxyService.ACTION_START
                val intent = Intent(context, VpnProxyService::class.java).apply { this.action = action }
                safeStartService(intent)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                logCollector.error(TAG, "onServerSelected failed", e)
            }
        }
    }
}
