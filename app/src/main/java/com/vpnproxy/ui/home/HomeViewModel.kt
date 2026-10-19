package com.vpnproxy.ui.home

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        val sessionStartedAt: Long? = null,
        val usingBootstrap: Boolean = false,
        val isRefreshingSubscription: Boolean = false
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
    private var connectAttemptId = 0L
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

        viewModelScope.launch {
            subscriptionRepository.usingBootstrap.collect { boot ->
                _uiState.value = _uiState.value.copy(usingBootstrap = boot)
            }
        }

        viewModelScope.launch {
            subscriptionRepository.allConfigs.collect { configs ->
                _uiState.value = _uiState.value.copy(serverCount = configs.size)
            }
        }
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
                    } else {
                        _googleRtt.value = null
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    _googleRtt.value = null
                    _latencyBreakdown.value = null
                }
                delay(30_000)
            }
        }
    }

    fun setVpnPermissionRequester(requester: () -> Unit) {
        vpnPermissionRequester = requester
    }

    fun toggleConnection() {
        when (_uiState.value.connectionState) {
            ConnectionUiState.CONNECTED, ConnectionUiState.UNSTABLE -> disconnect()
            ConnectionUiState.DISCONNECTED, ConnectionUiState.ERROR -> connect()
            ConnectionUiState.CONNECTING -> { }
        }
    }

    private fun connect() {
        Log.i(TAG, "User clicked Connect")
        fallbackAttempts = 0
        attemptedServerKeys.clear()
        isFallingBack.set(false)
        connectAttemptId++
        VpnProxyService.clearError()
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionUiState.CONNECTING,
            isBusy = true
        )
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        vpnPermissionRequester?.invoke()
    }

    fun onVpnPermissionGranted() {
        val permissionGrantedAt = android.os.SystemClock.elapsedRealtime()
        Log.i(TAG, "VPN permission granted")
        Log.i(TAG, "HomeVM: Starting connection flow...")

        // Dispatch ACTION_START immediately. VpnProxyService has its own IO scope and
        // can bootstrap from the first configured server while ranking runs in parallel.
        _uiState.value = _uiState.value.copy(connectionState = ConnectionUiState.CONNECTING, isBusy = true)
        Log.i(TAG, "HomeVM: Sending ACTION_START to VpnProxyService NOW")
        startVpnService()
        Log.i(TAG, "HomeVM: ACTION_START dispatch returned after ${android.os.SystemClock.elapsedRealtime() - permissionGrantedAt} ms")

        viewModelScope.launch {
            try {
                Log.i(TAG, "HomeVM: Selecting best server...")
                val selectionStartedAt = android.os.SystemClock.elapsedRealtime()
                val selection = withContext(Dispatchers.IO) {
                    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val hasNetwork = cm.activeNetwork != null
                    Log.d(TAG, "Network available=$hasNetwork")
                    var configs = subscriptionRepository.getAllConfigs()
                    if (configs.isEmpty() && hasNetwork) {
                        Log.d(TAG, "No configured servers; refreshing subscription in background")
                        configs = try {
                            subscriptionRepository.refreshAll()
                            subscriptionRepository.getAllConfigs()
                        } catch (c: CancellationException) {
                            throw c
                        } catch (e: Exception) {
                            logCollector.warn(TAG, "Subscription refresh failed: ${e.message}")
                            emptyList()
                        }
                    }
                    if (configs.isEmpty()) configs = subscriptionRepository.getCachedConfigs()
                    val best = if (configs.isNotEmpty()) autoSwitcher.selectBest(configs) else null
                    configs to best
                }
                val configs = selection.first
                val best = selection.second
                Log.i(TAG, "HomeVM: Server selection finished in ${android.os.SystemClock.elapsedRealtime() - selectionStartedAt} ms; configs=${configs.size}")
                if (configs.isNotEmpty()) _uiState.value = _uiState.value.copy(serverCount = configs.size)
                _uiState.value = _uiState.value.copy(usingBootstrap = subscriptionRepository.usingBootstrap.value)

                if (best == null) {
                    logCollector.warn(TAG, "No server candidate found after ACTION_START")
                    if (!VpnProxyService.isRunning.value) {
                        connectTimeoutJob?.cancel()
                        connectTimeoutJob = null
                        _uiState.value = _uiState.value.copy(connectionState = ConnectionUiState.ERROR, isBusy = false)
                    }
                    return@launch
                }

                Log.i(TAG, "Selected server: ${best.config.name} (${best.config.address}:${best.config.port}, ${best.latencyMs}ms)")
                attemptedServerKeys.add(serverKey(best.config.address, best.config.port))
                // ACTION_START has already been dispatched. If service bootstrapped with
                // another config, request reconnect so it consumes the selected server.
                if (!VpnProxyService.isRunning.value) {
                    Log.i(TAG, "HomeVM: Server ranking completed while service is starting; service will observe selected server if startup has not read config yet")
                } else {
                    Log.i(TAG, "HomeVM: Requesting reconnect to the selected best server")
                    safeStartService(Intent(context, VpnProxyService::class.java).apply {
                        action = VpnProxyService.ACTION_RECONNECT
                    }, reason = "best server selected after immediate ACTION_START")
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                logCollector.error(TAG, "Server selection failed after ACTION_START", e)
                if (!VpnProxyService.isRunning.value) {
                    connectTimeoutJob?.cancel()
                    connectTimeoutJob = null
                    _uiState.value = _uiState.value.copy(connectionState = ConnectionUiState.ERROR, isBusy = false)
                }
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
    }

    fun disconnect() {
        connectAttemptId++
        fallbackAttempts = 0
        attemptedServerKeys.clear()
        isFallingBack.set(false)
        VpnProxyService.clearError()
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        safeStartService(Intent(context, VpnProxyService::class.java).apply {
            action = VpnProxyService.ACTION_STOP
        }, reason = "user requested disconnect")
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionUiState.DISCONNECTED,
            sessionStartedAt = null
        )
    }

    fun forceDisconnect() {
        connectAttemptId++
        fallbackAttempts = 0
        attemptedServerKeys.clear()
        isFallingBack.set(false)
        VpnProxyService.clearError()
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        safeStartService(Intent(context, VpnProxyService::class.java).apply {
            action = VpnProxyService.ACTION_STOP
        }, reason = "forceDisconnect invoked after timeout or explicit reset")
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionUiState.DISCONNECTED,
            isBusy = false,
            currentServerName = "—",
            currentRtt = 0L,
            sessionStartedAt = null
        )
    }

    fun switchServer() {
        if (isFallingBack.get() || autoSwitcher.isSwitching()) return
        viewModelScope.launch {
            try {
                val ranked = autoSwitcher.rankedServers.value
                val configs = subscriptionRepository.getAllConfigs()
                if (ranked.isEmpty() && configs.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        connectionState = ConnectionUiState.ERROR,
                        isBusy = false
                    )
                    return@launch
                }
                if (ranked.isEmpty()) autoSwitcher.selectBest(configs)
                _uiState.value = _uiState.value.copy(isBusy = true)
                val next = autoSwitcher.switchToNext()
                if (next == null) {
                    _uiState.value = _uiState.value.copy(isBusy = false)
                    return@launch
                }
                _uiState.value = _uiState.value.copy(
                    currentServerName = next.config.name,
                    currentRtt = next.latencyMs
                )
                val action = if (VpnProxyService.isRunning.value) VpnProxyService.ACTION_RECONNECT
                else VpnProxyService.ACTION_START
                safeStartService(Intent(context, VpnProxyService::class.java).apply { this.action = action })
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
        if (!isFallingBack.compareAndSet(false, true)) return
        viewModelScope.launch {
            var awaitingServiceResult = false
            try {
                if (fallbackAttempts >= MAX_FALLBACK_ATTEMPTS) {
                    fallbackAttempts = 0
                    attemptedServerKeys.clear()
                    _uiState.value = _uiState.value.copy(
                        connectionState = ConnectionUiState.ERROR,
                        isBusy = false
                    )
                    safeStartService(Intent(context, VpnProxyService::class.java).apply {
                        action = VpnProxyService.ACTION_STOP
                    }, reason = "fallback exhausted: max attempts reached")
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
                    fallbackAttempts = 0
                    attemptedServerKeys.clear()
                    _uiState.value = _uiState.value.copy(
                        connectionState = ConnectionUiState.ERROR,
                        isBusy = false
                    )
                    safeStartService(Intent(context, VpnProxyService::class.java).apply {
                        action = VpnProxyService.ACTION_STOP
                    }, reason = "fallback exhausted: no untried server available")
                    return@launch
                }
                fallbackAttempts++
                _uiState.value = _uiState.value.copy(
                    connectionState = ConnectionUiState.CONNECTING,
                    currentServerName = next.config.name,
                    currentRtt = next.latencyMs,
                    isBusy = true
                )
                connectTimeoutJob?.cancel()
                connectTimeoutJob = null
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
                if (!awaitingServiceResult) isFallingBack.set(false)
            }
        }
    }

    private fun startVpnService() {
        val intent = Intent(context, VpnProxyService::class.java).apply {
            action = VpnProxyService.ACTION_START
        }
        if (!safeStartService(intent, reason = "new connection attempt")) {
            connectTimeoutJob?.cancel()
            connectTimeoutJob = null
            _uiState.value = _uiState.value.copy(isBusy = false)
            isFallingBack.set(false)
        } else {
            scheduleConnectionTimeout()
        }
    }

    private fun scheduleConnectionTimeout() {
        connectTimeoutJob?.cancel()
        val attemptId = connectAttemptId
        connectTimeoutJob = viewModelScope.launch {
            delay(connectTimeoutMs)
            if (attemptId != connectAttemptId) {
                Log.d(TAG, "Ignoring stale connection timeout for attempt=$attemptId")
                return@launch
            }
            if (_uiState.value.connectionState == ConnectionUiState.CONNECTING) {
                Log.e(TAG, "Connection timed out after ACTION_START; stopping attempt=$attemptId")
                forceDisconnect()
            }
        }
    }

    private fun safeStartService(intent: Intent, reason: String = "unspecified caller"): Boolean {
        return try {
            when (intent.action) {
                VpnProxyService.ACTION_START -> Log.d(TAG, "Sending ACTION_START to VpnProxyService. Reason: $reason")
                VpnProxyService.ACTION_STOP -> Log.e("AutoSwitcher", "Sending ACTION_STOP to VpnProxyService! Reason: $reason", Exception("ACTION_STOP dispatch stack"))
                VpnProxyService.ACTION_RECONNECT -> Log.d(TAG, "Sending ACTION_RECONNECT to VpnProxyService. Reason: $reason")
            }
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

    fun refreshSubscription() {
        if (_uiState.value.isRefreshingSubscription) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshingSubscription = true)
            try {
                val n = subscriptionRepository.refreshAll()
                logCollector.info(
                    TAG,
                    "Manual subscription refresh: $n configs, bootstrap=${subscriptionRepository.usingBootstrap.value}"
                )
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                logCollector.error(TAG, "Manual subscription refresh failed", e)
            } finally {
                _uiState.value = _uiState.value.copy(
                    isRefreshingSubscription = false,
                    usingBootstrap = subscriptionRepository.usingBootstrap.value,
                    serverCount = subscriptionRepository.getAllConfigs().size
                )
            }
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
                val action = if (VpnProxyService.isRunning.value) VpnProxyService.ACTION_RECONNECT
                else VpnProxyService.ACTION_START
                safeStartService(Intent(context, VpnProxyService::class.java).apply { this.action = action })
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                logCollector.error(TAG, "onServerSelected failed", e)
            }
        }
    }
}
