package com.vpnproxy.ui.home

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vpnproxy.core.connection.ConnectionCoordinator
import com.vpnproxy.core.connection.ConnectionEvent
import com.vpnproxy.core.connection.ConnectionState
import com.vpnproxy.core.connection.LastServerStore
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
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val subscriptionRepository: SubscriptionRepository,
    private val autoSwitcher: AutoSwitcher,
    private val scoringEngine: ScoringEngine,
    private val trafficCollector: TrafficStatsCollector,
    private val pingService: PingService,
    private val logCollector: LogCollector,
    private val connectionCoordinator: ConnectionCoordinator,
    private val lastServerStore: LastServerStore
) : ViewModel() {

    companion object {
        private const val TAG = "HomeVM"
        /** UI watchdog: if Connecting hangs without Connected/Error, request disconnect. */
        private const val CONNECTING_UI_WATCHDOG_MS = 10_000L
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
    private var connectingWatchdogJob: Job? = null
    private var vpnPermissionRequester: (() -> Unit)? = null

    init {
        viewModelScope.launch {
            connectionCoordinator.state.collect { state ->
                when (state) {
                    is ConnectionState.Disconnected,
                    is ConnectionState.Disconnecting -> {
                        cancelConnectingWatchdog()
                        _uiState.value = _uiState.value.copy(
                            connectionState = ConnectionUiState.DISCONNECTED,
                            isBusy = false,
                            sessionStartedAt = null
                        )
                    }
                    is ConnectionState.Connecting,
                    is ConnectionState.Reconnecting -> {
                        _uiState.value = _uiState.value.copy(
                            connectionState = ConnectionUiState.CONNECTING,
                            isBusy = true
                        )
                        armConnectingWatchdog()
                    }
                    is ConnectionState.Connected -> {
                        cancelConnectingWatchdog()
                        VpnProxyService.clearError()
                        val prev = _uiState.value.connectionState
                        val sessionStart = if (prev != ConnectionUiState.CONNECTED) {
                            System.currentTimeMillis()
                        } else {
                            _uiState.value.sessionStartedAt
                        }
                        _uiState.value = _uiState.value.copy(
                            connectionState = ConnectionUiState.CONNECTED,
                            isBusy = false,
                            currentServerName = state.serverName.ifBlank { _uiState.value.currentServerName },
                            sessionStartedAt = sessionStart
                        )
                    }
                    is ConnectionState.Error -> {
                        cancelConnectingWatchdog()
                        _uiState.value = _uiState.value.copy(
                            connectionState = ConnectionUiState.ERROR,
                            isBusy = false,
                            sessionStartedAt = null
                        )
                        logCollector.warn(TAG, "Actor Error: ${state.message}")
                    }
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
                    lastServerStore.save(
                        address = server.config.address,
                        port = server.config.port,
                        name = server.config.name,
                        uuid = server.config.uuid
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

    private fun armConnectingWatchdog() {
        if (connectingWatchdogJob?.isActive == true) return
        connectingWatchdogJob = viewModelScope.launch {
            delay(CONNECTING_UI_WATCHDOG_MS)
            val s = connectionCoordinator.state.value
            if (s is ConnectionState.Connecting || s is ConnectionState.Reconnecting) {
                logCollector.warn(
                    TAG,
                    "UI connecting watchdog ${CONNECTING_UI_WATCHDOG_MS}ms — RequestDisconnect"
                )
                connectionCoordinator.send(ConnectionEvent.RequestDisconnect)
                connectionCoordinator.send(
                    ConnectionEvent.ShowError("Превышено время подключения")
                )
            }
        }
    }

    private fun cancelConnectingWatchdog() {
        connectingWatchdogJob?.cancel()
        connectingWatchdogJob = null
    }

    private fun startPinging() {
        if (pingJob?.isActive == true) return
        pingJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val breakdown = withContext(Dispatchers.IO) {
                        val region = PingTargets.detectRegion(context)
                        val targets = PingTargets.forRegion(region)
                        val multi = pingService.pingAll(targets = targets, region = region)
                        LatencyCalculator.compute(multi.results)
                    }
                    _latencyBreakdown.value = breakdown
                    if (breakdown.isValid) {
                        _googleRtt.value = breakdown.compositeScore.toLong()
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

    /** "Повторить" on Error banner — same path as Connect. */
    fun retryConnect() {
        connect()
    }

    private fun connect() {
        Log.i(TAG, "User clicked Connect")
        VpnProxyService.clearError()
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionUiState.CONNECTING,
            isBusy = true
        )
        armConnectingWatchdog()
        vpnPermissionRequester?.invoke()
    }

    fun onVpnPermissionGranted() {
        val permissionGrantedAt = android.os.SystemClock.elapsedRealtime()
        Log.i(TAG, "VPN permission granted")
        Log.i(TAG, "HomeVM: Starting connection flow (select server FIRST)...")

        _uiState.value = _uiState.value.copy(connectionState = ConnectionUiState.CONNECTING, isBusy = true)
        armConnectingWatchdog()
        ensureServiceRunning()

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
                Log.i(
                    TAG,
                    "HomeVM: Server selection finished in " +
                        "${android.os.SystemClock.elapsedRealtime() - selectionStartedAt} ms; configs=${configs.size}"
                )
                if (configs.isNotEmpty()) _uiState.value = _uiState.value.copy(serverCount = configs.size)
                _uiState.value = _uiState.value.copy(usingBootstrap = subscriptionRepository.usingBootstrap.value)

                if (best == null) {
                    logCollector.warn(TAG, "No server candidate — abort connect")
                    connectionCoordinator.send(ConnectionEvent.ShowError("No server candidate"))
                    _uiState.value = _uiState.value.copy(connectionState = ConnectionUiState.ERROR, isBusy = false)
                    return@launch
                }

                Log.i(TAG, "Selected server: ${best.config.name} (${best.config.address}:${best.config.port}, ${best.latencyMs}ms)")
                lastServerStore.save(
                    address = best.config.address,
                    port = best.config.port,
                    name = best.config.name,
                    uuid = best.config.uuid
                )
                connectionCoordinator.send(ConnectionEvent.ServerSelected(best))
                connectionCoordinator.send(ConnectionEvent.UserConnect)
                Log.i(
                    TAG,
                    "HomeVM: ServerSelected+UserConnect after " +
                        "${android.os.SystemClock.elapsedRealtime() - permissionGrantedAt} ms"
                )
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                logCollector.error(TAG, "Server selection failed", e)
                connectionCoordinator.send(ConnectionEvent.ShowError(e.message ?: "selection failed"))
                _uiState.value = _uiState.value.copy(connectionState = ConnectionUiState.ERROR, isBusy = false)
            }
        }
    }

    fun onVpnPermissionDenied() {
        cancelConnectingWatchdog()
        connectionCoordinator.send(ConnectionEvent.RequestDisconnect)
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionUiState.DISCONNECTED,
            isBusy = false
        )
    }

    fun disconnect() {
        cancelConnectingWatchdog()
        VpnProxyService.clearError()
        connectionCoordinator.send(ConnectionEvent.RequestDisconnect)
        ensureServiceRunning(action = VpnProxyService.ACTION_REQUEST_DISCONNECT)
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionUiState.DISCONNECTED,
            sessionStartedAt = null
        )
    }

    fun forceDisconnect() {
        cancelConnectingWatchdog()
        VpnProxyService.clearError()
        connectionCoordinator.send(ConnectionEvent.RequestDisconnect)
        ensureServiceRunning(action = VpnProxyService.ACTION_REQUEST_DISCONNECT)
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionUiState.DISCONNECTED,
            isBusy = false,
            currentServerName = "—",
            currentRtt = 0L,
            sessionStartedAt = null
        )
    }

    fun switchServer() {
        if (autoSwitcher.isSwitching()) return
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
                lastServerStore.save(
                    address = next.config.address,
                    port = next.config.port,
                    name = next.config.name,
                    uuid = next.config.uuid
                )
                connectionCoordinator.send(ConnectionEvent.ResetRetryCount)
                connectionCoordinator.send(ConnectionEvent.ServerSelected(next))
                ensureServiceRunning()
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

    private fun ensureServiceRunning(action: String = VpnProxyService.ACTION_ENSURE_RUNNING) {
        try {
            val intent = Intent(context, VpnProxyService::class.java).apply { this.action = action }
            Log.d(TAG, "ensureServiceRunning action=$action")
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
                context.startService(intent)
            } else {
                context.startForegroundService(intent)
            }
        } catch (t: Throwable) {
            logCollector.error(TAG, "ensureServiceRunning failed: ${t.message}", t)
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
                lastServerStore.save(
                    address = server.config.address,
                    port = server.config.port,
                    name = server.config.name,
                    uuid = server.config.uuid
                )
                _uiState.value = _uiState.value.copy(
                    currentServerName = server.config.name,
                    currentRtt = server.latencyMs
                )
                connectionCoordinator.send(ConnectionEvent.ResetRetryCount)
                connectionCoordinator.send(ConnectionEvent.ServerSelected(server))
                if (connectionCoordinator.state.value is ConnectionState.Disconnected ||
                    connectionCoordinator.state.value is ConnectionState.Error
                ) {
                    connectionCoordinator.send(ConnectionEvent.UserConnect)
                }
                ensureServiceRunning()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                logCollector.error(TAG, "onServerSelected failed", e)
            }
        }
    }
}
