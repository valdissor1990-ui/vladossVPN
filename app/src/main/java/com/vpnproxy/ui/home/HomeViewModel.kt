package com.vpnproxy.ui.home

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vpnproxy.core.connection.ConnectionCoordinator
import com.vpnproxy.core.connection.ConnectionEvent
import com.vpnproxy.core.connection.ConnectionState
import com.vpnproxy.core.connection.LastServerStore
import com.vpnproxy.core.connection.TraceId
import com.vpnproxy.core.dualpath.DualPathStore
import com.vpnproxy.core.ping.LatencyBreakdown
import com.vpnproxy.core.ping.LatencyCalculator
import com.vpnproxy.core.ping.PingService
import com.vpnproxy.core.ping.PingTargets
import com.vpnproxy.core.scanner.ServerScanner
import com.vpnproxy.core.singbox.DiagnosticSettings
import com.vpnproxy.core.tunnel.TrafficStatsCollector
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.service.VpnProxyService
import com.vpnproxy.smartconnect.AutoSwitcher
import com.vpnproxy.smartconnect.BatteryPolicy
import com.vpnproxy.smartconnect.ScoringEngine
import com.vpnproxy.subscription.JsonConfigRepository
import com.vpnproxy.subscription.SubscriptionRepository
import com.vpnproxy.util.DebugFlags
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val subscriptionRepository: SubscriptionRepository,
    private val serverScanner: ServerScanner,
    private val autoSwitcher: AutoSwitcher,
    private val trafficCollector: TrafficStatsCollector,
    private val pingService: PingService,
    private val logCollector: LogCollector,
    private val connectionCoordinator: ConnectionCoordinator,
    private val lastServerStore: LastServerStore,
    private val jsonConfigRepository: JsonConfigRepository,
    private val dualPathStore: DualPathStore
) : ViewModel() {

    companion object {
        private const val TAG = "HomeVM"
        private const val KEY_AUTOMATIC_MODE = "automatic_server_selection"
        private const val KEY_AUTOMATIC_SUBSCRIPTION_ID = "automatic_server_subscription_id"
        const val ALL_SUBSCRIPTIONS_ID = "all"
    }

    data class UiState(
        val connectionState: ConnectionUiState = ConnectionUiState.DISCONNECTED,
        val currentRtt: Long = 0L,
        val currentServerName: String = "—",
        val serverCount: Int = 0,
        val isBusy: Boolean = false,
        val sessionStartedAt: Long? = null,
        val isRefreshingSubscription: Boolean = false,
        val isScanningServers: Boolean = false,
        val scannedAvailableServers: Int = 0,
        val scanCheckedServers: Int = 0,
        val scanFailedServers: Int = 0,
        val scanTotalServers: Int = 0,
        val isAutomaticMode: Boolean = true,
        val selectedSubscriptionId: String = ALL_SUBSCRIPTIONS_ID,
        val lastErrorMessage: String? = null,
        val dualPathEnabled: Boolean = false,
        val dualPathAppCount: Int = 0
    )

    private val modePreferences = context.getSharedPreferences("vladoss_ui_preferences", Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(
        UiState(
            isAutomaticMode = modePreferences.getBoolean(KEY_AUTOMATIC_MODE, true),
            selectedSubscriptionId = modePreferences.getString(KEY_AUTOMATIC_SUBSCRIPTION_ID, ALL_SUBSCRIPTIONS_ID)
                ?: ALL_SUBSCRIPTIONS_ID
        )
    )

    val subscriptions = subscriptionRepository.subscriptions
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    val traffic: StateFlow<TrafficStatsCollector.TrafficSnapshot> = trafficCollector.traffic

    private val _googleRtt = MutableStateFlow<Long?>(null)
    val googleRtt: StateFlow<Long?> = _googleRtt.asStateFlow()
    private val _latencyBreakdown = MutableStateFlow<LatencyBreakdown?>(null)
    val latencyBreakdown: StateFlow<LatencyBreakdown?> = _latencyBreakdown.asStateFlow()

    private var pingJob: Job? = null
    private var selectionJob: Job? = null
    @Volatile private var lastConnectClickMs: Long = 0L
    private var vpnPermissionRequester: (() -> Unit)? = null
    private var activeTraceId: String? = null

    init {
        connectionCoordinator.registerSelectionCancel { cancelSelectionJob("actor RequestDisconnect") }
        viewModelScope.launch {
            connectionCoordinator.state.collect { state ->
                when (state) {
                    is ConnectionState.Disconnected, is ConnectionState.Disconnecting -> {
                        cancelSelectionJob("state=$state")
                        _uiState.value = _uiState.value.copy(
                            connectionState = ConnectionUiState.DISCONNECTED,
                            isBusy = false,
                            sessionStartedAt = null
                        )
                    }
                    is ConnectionState.Connecting, is ConnectionState.Reconnecting -> {
                        _uiState.value = _uiState.value.copy(
                            connectionState = ConnectionUiState.CONNECTING,
                            isBusy = true,
                            lastErrorMessage = null
                        )
                    }
                    is ConnectionState.Verifying -> {
                        _uiState.value = _uiState.value.copy(
                            connectionState = ConnectionUiState.VERIFYING,
                            isBusy = true,
                            lastErrorMessage = null
                        )
                    }
                    is ConnectionState.Connected -> {
                        autoSwitcher.currentServer.value?.config?.let { server ->
                            lastServerStore.save(
                                address = server.address,
                                port = server.port,
                                name = server.name,
                                uuid = server.uuid
                            )
                            serverScanner.recordSuccessfulConnection(server)
                        }
                        VpnProxyService.clearError()
                        val prev = _uiState.value.connectionState
                        val sessionStart =
                            if (prev != ConnectionUiState.CONNECTED) System.currentTimeMillis()
                            else _uiState.value.sessionStartedAt
                        _uiState.value = _uiState.value.copy(
                            connectionState = ConnectionUiState.CONNECTED,
                            isBusy = false,
                            currentServerName = state.serverName.ifBlank { _uiState.value.currentServerName },
                            sessionStartedAt = sessionStart
                        )
                    }
                    is ConnectionState.Error -> {
                        cancelSelectionJob("Error")
                        _uiState.value = _uiState.value.copy(
                            connectionState = ConnectionUiState.ERROR,
                            isBusy = false,
                            sessionStartedAt = null,
                            lastErrorMessage = state.message.takeIf { it.isNotBlank() }
                        )
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
                }
            }
        }
        startPinging()
        viewModelScope.launch {
            combine(subscriptionRepository.allConfigs, jsonConfigRepository.configs) { sub, _ ->
                jsonConfigRepository.allConfigsIncluding(sub)
            }.collect { merged ->
                _uiState.value = _uiState.value.copy(
                    serverCount = merged.distinctBy { it.connectionKey() }.size
                )
            }
        }
        viewModelScope.launch {
            serverScanner.isScanning.collect { scanning ->
                _uiState.value = _uiState.value.copy(isScanningServers = scanning)
            }
        }
        viewModelScope.launch {
            serverScanner.scannedServers.collect { servers ->
                val available = servers.count {
                    it.successRate > 0.5f && it.latencyMs != Long.MAX_VALUE
                }
                val checked = serverScanner.scanProgress.value.first
                _uiState.value = _uiState.value.copy(
                    scannedAvailableServers = available,
                    scanCheckedServers = checked,
                    scanFailedServers = (checked - available).coerceAtLeast(0),
                    scanTotalServers = maxOf(
                        _uiState.value.serverCount,
                        serverScanner.scanProgress.value.second,
                        servers.size
                    )
                )
            }
        }
        viewModelScope.launch {
            serverScanner.scanProgress.collect { progress ->
                val servers = serverScanner.scannedServers.value
                val available = servers.count {
                    it.successRate > 0.5f && it.latencyMs != Long.MAX_VALUE
                }
                _uiState.value = _uiState.value.copy(
                    scanCheckedServers = progress.first,
                    scanFailedServers = (progress.first - available).coerceAtLeast(0),
                    scanTotalServers = maxOf(
                        _uiState.value.serverCount,
                        progress.second,
                        servers.size
                    )
                )
            }
        }
        viewModelScope.launch {
            dualPathStore.settings.collect { s ->
                _uiState.value = _uiState.value.copy(
                    dualPathEnabled = s.enabled,
                    dualPathAppCount = s.packages.size
                )
            }
        }
    }

    private fun cancelSelectionJob(reason: String) {
        if (selectionJob?.isActive == true) {
            logCollector.info(TAG, "Cancel selectionJob: $reason")
            selectionJob?.cancel()
        }
        selectionJob = null
        _uiState.value = _uiState.value.copy(isBusy = false)
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
                    _googleRtt.value = if (breakdown.isValid) breakdown.compositeScore.toLong() else null
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Throwable) {
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

    fun cancelServerScan() {
        logCollector.info(TAG, "Server scan stop requested from home button")
        serverScanner.cancelScan()
        cancelSelectionJob("user stop scan")
        _uiState.value = _uiState.value.copy(isScanningServers = false)
    }

    fun toggleConnection() {
        when (_uiState.value.connectionState) {
            ConnectionUiState.CONNECTED,
            ConnectionUiState.UNSTABLE,
            ConnectionUiState.CONNECTING,
            ConnectionUiState.VERIFYING -> disconnect()
            ConnectionUiState.DISCONNECTED,
            ConnectionUiState.ERROR -> connect()
        }
    }

    fun retryConnect() {
        connect()
    }

    fun setAutomaticMode(enabled: Boolean) {
        modePreferences.edit().putBoolean(KEY_AUTOMATIC_MODE, enabled).apply()
        _uiState.value = _uiState.value.copy(isAutomaticMode = enabled)
        autoSwitcher.setManualSelectionMode(!enabled)
        connectionCoordinator.setManualSelectionMode(!enabled)
    }

    fun setAutomaticSubscription(subscriptionId: String) {
        modePreferences.edit().putString(KEY_AUTOMATIC_SUBSCRIPTION_ID, subscriptionId).apply()
        _uiState.value = _uiState.value.copy(selectedSubscriptionId = subscriptionId)
    }

    private fun connect() {
        runCatching { BatteryPolicy.requestIgnoreBatteryOptimizations(context) }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastConnectClickMs < 900L) return
        lastConnectClickMs = now
        cancelSelectionJob("new connect")
        try {
            activeTraceId = TraceId.newId()
            connectionCoordinator.send(ConnectionEvent.ConnectRequested(activeTraceId!!, userInitiated = true))
            VpnProxyService.clearError()
            _uiState.value = _uiState.value.copy(
                connectionState = ConnectionUiState.CONNECTING,
                isBusy = true,
                lastErrorMessage = null
            )
            vpnPermissionRequester?.invoke()
        } catch (e: Exception) {
            connectionCoordinator.send(ConnectionEvent.ShowError(e.message ?: "Не удалось начать подключение"))
            _uiState.value = _uiState.value.copy(connectionState = ConnectionUiState.ERROR, isBusy = false)
        }
    }

    fun onVpnPermissionGranted() {
        val traceId = activeTraceId ?: TraceId.newId().also { activeTraceId = it }
        _uiState.value = _uiState.value.copy(connectionState = ConnectionUiState.CONNECTING, isBusy = true)
        ensureServiceRunning()
        selectionJob?.cancel()
        selectionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                var configs = jsonConfigRepository.allConfigsIncluding(subscriptionRepository.getAllConfigs())
                if (configs.isEmpty()) {
                    configs = jsonConfigRepository.allConfigsIncluding(subscriptionRepository.getCachedConfigs())
                }
                if (configs.isEmpty()) {
                    connectionCoordinator.send(ConnectionEvent.ShowError("Нет доступных серверов"))
                    return@launch
                }
                val scanned = serverScanner.scanForConnection(configs, targetAvailableCount = 12)
                val selected = serverScanner.bestAvailableServer(scanned)
                    ?: autoSwitcher.currentServer.value
                    ?: scanned.firstOrNull()
                if (selected == null) {
                    connectionCoordinator.send(ConnectionEvent.ShowError("Нет доступных серверов"))
                    return@launch
                }
                connectionCoordinator.send(ConnectionEvent.ServerSelected(selected, traceId))
                connectionCoordinator.send(ConnectionEvent.UserConnect)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                logCollector.error(TAG, "onVpnPermissionGranted failed", e)
                connectionCoordinator.send(ConnectionEvent.ShowError(e.message ?: "Ошибка выбора сервера"))
            }
        }
    }

    fun onVpnPermissionDenied() {
        connectionCoordinator.send(ConnectionEvent.ShowError("Нет разрешения VPN. Выдайте доступ в настройках."))
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionUiState.ERROR,
            isBusy = false,
            lastErrorMessage = "Нет разрешения VPN"
        )
    }

    fun disconnect() {
        cancelSelectionJob("user disconnect")
        serverScanner.cancelScan()
        connectionCoordinator.send(ConnectionEvent.RequestDisconnect)
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionUiState.DISCONNECTED,
            isBusy = false,
            sessionStartedAt = null,
            isScanningServers = false
        )
    }

    fun forceDisconnect() {
        cancelSelectionJob("forceDisconnect")
        serverScanner.cancelScan()
        connectionCoordinator.send(ConnectionEvent.RequestDisconnect)
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionUiState.DISCONNECTED,
            isBusy = false,
            sessionStartedAt = null,
            isScanningServers = false
        )
    }

    fun onServerSelected(server: ScoringEngine.ScoredServer) {
        val traceId = TraceId.newId()
        activeTraceId = traceId
        connectionCoordinator.send(ConnectionEvent.ConnectRequested(traceId, userInitiated = true))
        viewModelScope.launch {
            try {
                lastServerStore.save(
                    address = server.config.address,
                    port = server.config.port,
                    name = server.config.name,
                    uuid = server.config.uuid
                )
                _uiState.value = _uiState.value.copy(
                    currentServerName = server.config.name,
                    currentRtt = server.latencyMs,
                    connectionState = ConnectionUiState.CONNECTING,
                    isBusy = true
                )
                connectionCoordinator.send(ConnectionEvent.ServerSelected(server, traceId))
                connectionCoordinator.send(ConnectionEvent.UserConnect)
                ensureServiceRunning()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                logCollector.error(TAG, "onServerSelected failed", e)
            }
        }
    }

    private fun ensureServiceRunning() {
        runCatching {
            val intent = android.content.Intent(context, VpnProxyService::class.java)
            context.startForegroundService(intent)
        }
    }
}
