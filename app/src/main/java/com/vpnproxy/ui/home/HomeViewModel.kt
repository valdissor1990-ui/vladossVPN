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
import com.vpnproxy.core.connection.TraceId
import com.vpnproxy.core.singbox.DiagnosticSettings
import com.vpnproxy.util.DebugFlags
import com.vpnproxy.core.ping.LatencyBreakdown
import com.vpnproxy.core.ping.LatencyCalculator
import com.vpnproxy.core.ping.PingService
import com.vpnproxy.core.scanner.ServerScanner
import com.vpnproxy.core.ping.PingTargets
import com.vpnproxy.core.tunnel.TrafficStatsCollector
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.service.VpnProxyService
import com.vpnproxy.smartconnect.AutoSwitcher
import com.vpnproxy.smartconnect.BatteryPolicy
import com.vpnproxy.smartconnect.ScoringEngine
import com.vpnproxy.subscription.JsonConfigRepository
import com.vpnproxy.subscription.SubscriptionRepository
import com.vpnproxy.core.dualpath.DualPathStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val subscriptionRepository: SubscriptionRepository,
    private val serverScanner: ServerScanner,
    private val autoSwitcher: AutoSwitcher,
    private val scoringEngine: ScoringEngine,
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
        private const val CONNECTING_UI_WATCHDOG_MS = 90_000L
        private const val SERVER_SELECTION_TIMEOUT_MS = 25_000L
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
        val isAutomaticMode: Boolean = true,
        val selectedSubscriptionId: String = ALL_SUBSCRIPTIONS_ID,
        val lastErrorMessage: String? = null,
        val dualPathEnabled: Boolean = false,
        val dualPathAppCount: Int = 0
    )

    private val modePreferences = context.getSharedPreferences("vladoss_ui_preferences", Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(UiState(
        isAutomaticMode = modePreferences.getBoolean(KEY_AUTOMATIC_MODE, true),
        selectedSubscriptionId = modePreferences.getString(KEY_AUTOMATIC_SUBSCRIPTION_ID, ALL_SUBSCRIPTIONS_ID) ?: ALL_SUBSCRIPTIONS_ID
    ))

    val subscriptions = subscriptionRepository.subscriptions
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    val traffic: StateFlow<TrafficStatsCollector.TrafficSnapshot> = trafficCollector.traffic

    private val _googleRtt = MutableStateFlow<Long?>(null)
    val googleRtt: StateFlow<Long?> = _googleRtt.asStateFlow()
    private val _latencyBreakdown = MutableStateFlow<LatencyBreakdown?>(null)
    val latencyBreakdown: StateFlow<LatencyBreakdown?> = _latencyBreakdown.asStateFlow()

    private var pingJob: Job? = null
    private var connectingWatchdogJob: Job? = null
    private var selectionJob: Job? = null
    @Volatile private var lastConnectClickMs: Long = 0L
    private var vpnPermissionRequester: (() -> Unit)? = null
    private var activeTraceId: String? = null
    @Volatile private var manuallySelectedServer: ScoringEngine.ScoredServer? = null
    @Volatile private var pendingRankingConfigs: List<com.vpnproxy.subscription.SubscriptionParser.ParsedConfig> = emptyList()

    init {
        connectionCoordinator.registerSelectionCancel { cancelSelectionJob("actor RequestDisconnect") }
        updateAutoSwitcherProviderScope()
        viewModelScope.launch {
            connectionCoordinator.state.collect { state ->
                when (state) {
                    is ConnectionState.Disconnected, is ConnectionState.Disconnecting -> {
                        cancelConnectingWatchdog()
                        cancelSelectionJob("state=$state")
                        _uiState.value = _uiState.value.copy(connectionState = ConnectionUiState.DISCONNECTED, isBusy = false, sessionStartedAt = null)
                    }
                    is ConnectionState.Connecting, is ConnectionState.Reconnecting -> {
                        _uiState.value = _uiState.value.copy(connectionState = ConnectionUiState.CONNECTING, isBusy = true, lastErrorMessage = null)
                        armConnectingWatchdog()
                    }
                    is ConnectionState.Verifying -> {
                        _uiState.value = _uiState.value.copy(connectionState = ConnectionUiState.VERIFYING, isBusy = true, lastErrorMessage = null)
                        armConnectingWatchdog()
                    }
                    is ConnectionState.Connected -> {
                        val diagnosticOptions = DiagnosticSettings.load(context)
                        autoSwitcher.setDiagnosticFreeze(DebugFlags.diagnosticEnabled() && diagnosticOptions.freezeAutoSwitcherAfterConnected)
                        cancelConnectingWatchdog()
                        autoSwitcher.currentServer.value?.config?.let { server ->
                            lastServerStore.save(address = server.address, port = server.port, name = server.name, uuid = server.uuid)
                            serverScanner.recordSuccessfulConnection(server)
                        }
                        pendingRankingConfigs.takeIf { it.isNotEmpty() }?.let { configs ->
                            pendingRankingConfigs = emptyList()
                            autoSwitcher.rankInBackground(configs)
                        }
                        VpnProxyService.clearError()
                        val prev = _uiState.value.connectionState
                        val sessionStart = if (prev != ConnectionUiState.CONNECTED) System.currentTimeMillis() else _uiState.value.sessionStartedAt
                        _uiState.value = _uiState.value.copy(
                            connectionState = ConnectionUiState.CONNECTED,
                            isBusy = false,
                            currentServerName = state.serverName.ifBlank { _uiState.value.currentServerName },
                            sessionStartedAt = sessionStart
                        )
                    }
                    is ConnectionState.Error -> {
                        cancelConnectingWatchdog()
                        cancelSelectionJob("Error")
                        pendingRankingConfigs = emptyList()
                        _uiState.value = _uiState.value.copy(
                            connectionState = ConnectionUiState.ERROR,
                            isBusy = false,
                            sessionStartedAt = null,
                            lastErrorMessage = state.message.takeIf { it.isNotBlank() }
                        )
                        logCollector.warn(TAG, tmsg("Actor Error: ${state.message}"))
                    }
                }
            }
        }
        viewModelScope.launch {
            autoSwitcher.currentServer.collect { server ->
                if (server != null) {
                    _uiState.value = _uiState.value.copy(currentServerName = server.config.name, currentRtt = server.latencyMs)
                }
            }
        }
        viewModelScope.launch {
            autoSwitcher.switchEvents.collect { event ->
                if (event != null) {
                    logCollector.info(TAG, tmsg("Switch event: $event"))
                    autoSwitcher.clearSwitchEvent()
                }
            }
        }
        startPinging()
        viewModelScope.launch {
            combine(
                subscriptionRepository.allConfigs,
                jsonConfigRepository.configs
            ) { subscriptionConfigs, _ ->
                jsonConfigRepository.allConfigsIncluding(subscriptionConfigs)
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
            dualPathStore.settings.collect { s ->
                _uiState.value = _uiState.value.copy(
                    dualPathEnabled = s.enabled,
                    dualPathAppCount = s.packages.size
                )
            }
        }
    }

    private fun tmsg(message: String): String =
        TraceId.format(TAG, message, activeTraceId).removePrefix("$TAG | ")

    private fun cancelSelectionJob(reason: String) {
        if (selectionJob?.isActive == true) {
            logCollector.info(TAG, tmsg("Cancel selectionJob: $reason"))
            selectionJob?.cancel()
        }
        selectionJob = null
        _uiState.value = _uiState.value.copy(isBusy = false)
    }

    private fun armConnectingWatchdog() {
        connectingWatchdogJob?.cancel()
        connectingWatchdogJob = viewModelScope.launch {
            delay(CONNECTING_UI_WATCHDOG_MS)
            val s = connectionCoordinator.state.value
            if (s is ConnectionState.Connecting || s is ConnectionState.Reconnecting || s is ConnectionState.Verifying) {
                logCollector.warn(TAG, tmsg("UI watchdog ${CONNECTING_UI_WATCHDOG_MS}ms — RequestDisconnect"))
                connectionCoordinator.send(ConnectionEvent.RequestDisconnect)
                connectionCoordinator.send(ConnectionEvent.ShowError("Превышено время подключения"))
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

    fun setVpnPermissionRequester(requester: () -> Unit) { vpnPermissionRequester = requester }

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

    fun retryConnect() { connect() }

    fun setAutomaticSubscription(subscriptionId: String) {
        val validId = if (subscriptionId == ALL_SUBSCRIPTIONS_ID || subscriptionRepository.subscriptions.value.any { it.id == subscriptionId }) subscriptionId else ALL_SUBSCRIPTIONS_ID
        modePreferences.edit().putString(KEY_AUTOMATIC_SUBSCRIPTION_ID, validId).apply()
        _uiState.value = _uiState.value.copy(selectedSubscriptionId = validId)
        updateAutoSwitcherProviderScope()
        if (connectionCoordinator.state.value is ConnectionState.Connected) refreshAutomaticScopeCandidates()
    }

    private fun filterConfigsForSelectedSubscription(configs: List<com.vpnproxy.subscription.SubscriptionParser.ParsedConfig>): List<com.vpnproxy.subscription.SubscriptionParser.ParsedConfig> {
        if (!_uiState.value.isAutomaticMode) return configs
        val selectedId = _uiState.value.selectedSubscriptionId
        if (selectedId == ALL_SUBSCRIPTIONS_ID) return configs
        val subscription = subscriptionRepository.subscriptions.value.firstOrNull { it.id == selectedId }
        if (subscription == null) return emptyList()
        return configs.filter { it.providerTag == subscription.name }
    }

    private fun updateAutoSwitcherProviderScope() {
        val state = _uiState.value
        val scopedSelection = state.isAutomaticMode && state.selectedSubscriptionId != ALL_SUBSCRIPTIONS_ID
        val selectedSubscription = if (scopedSelection) subscriptionRepository.subscriptions.value.firstOrNull { it.id == state.selectedSubscriptionId } else null
        val blocked = scopedSelection && selectedSubscription == null
        val providerTag = selectedSubscription?.name
        autoSwitcher.setManualSelectionMode(!state.isAutomaticMode)
        connectionCoordinator.setManualSelectionMode(!state.isAutomaticMode)
        autoSwitcher.setProviderScope(providerTag, blocked = blocked)
        connectionCoordinator.setProviderScope(providerTag, blocked = blocked)
    }

    private fun refreshAutomaticScopeCandidates() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val configs = filterConfigsForSelectedSubscription(jsonConfigRepository.allConfigsIncluding(subscriptionRepository.getAllConfigs()))
                if (configs.isEmpty()) {
                    connectionCoordinator.send(ConnectionEvent.SetFallbackCandidates(emptyList()))
                    autoSwitcher.rankInBackground(emptyList())
                    return@launch
                }
                if (!serverScanner.isScanCurrent(configs)) serverScanner.scanAll(configs, force = true)
                val allowedProfiles = configs.map { it.providerTag to it.connectionKey() }.toSet()
                val candidates = serverScanner.availableServers()
                    .filter { (it.config.providerTag to it.config.connectionKey()) in allowedProfiles }
                    .filterNot { autoSwitcher.isBlacklisted(it.config) }
                connectionCoordinator.send(ConnectionEvent.SetFallbackCandidates(candidates))
                autoSwitcher.rankInBackground(configs)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                logCollector.error(TAG, "Failed to refresh fallback candidates", e)
                connectionCoordinator.send(ConnectionEvent.SetFallbackCandidates(emptyList()))
            }
        }
    }

    fun setAutomaticMode(enabled: Boolean) {
        modePreferences.edit().putBoolean(KEY_AUTOMATIC_MODE, enabled).apply()
        _uiState.value = _uiState.value.copy(isAutomaticMode = enabled)
        updateAutoSwitcherProviderScope()
        if (connectionCoordinator.state.value is ConnectionState.Connected) refreshAutomaticScopeCandidates()
    }

    private fun connect() {
        runCatching { BatteryPolicy.requestIgnoreBatteryOptimizations(context) }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastConnectClickMs < 900L) {
            Log.d(TAG, "Connect debounced")
            return
        }
        lastConnectClickMs = now
        cancelSelectionJob("new connect")
        try {
            activeTraceId = TraceId.newId()
            connectionCoordinator.send(ConnectionEvent.ConnectRequested(activeTraceId!!, userInitiated = true))
            VpnProxyService.clearError()
            _uiState.value = _uiState.value.copy(connectionState = ConnectionUiState.CONNECTING, isBusy = true, lastErrorMessage = null)
            armConnectingWatchdog()
            vpnPermissionRequester?.invoke()
        } catch (e: Exception) {
            connectionCoordinator.send(ConnectionEvent.ShowError(e.message ?: "Не удалось начать подключение"))
            _uiState.value = _uiState.value.copy(connectionState = ConnectionUiState.ERROR, isBusy = false)
        }
    }

    fun onVpnPermissionGranted() {
        val traceId = activeTraceId ?: TraceId.newId().also { activeTraceId = it }
        _uiState.value = _uiState.value.copy(connectionState = ConnectionUiState.CONNECTING, isBusy = true)
        armConnectingWatchdog()
        ensureServiceRunning()
        selectionJob?.cancel()
        selectionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val selectionStartedAt = android.os.SystemClock.elapsedRealtime()
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val hasNetwork = cm.activeNetwork != null
                var configs = jsonConfigRepository.allConfigsIncluding(subscriptionRepository.getAllConfigs())
                if (activeTraceId != traceId) return@launch
                var refreshedSubscription = false
                if (hasNetwork) {
                    configs = try {
                        subscriptionRepository.refreshAll()
                        jsonConfigRepository.allConfigsIncluding(subscriptionRepository.getAllConfigs()).also { refreshed ->
                            serverScanner.scanAfterSubscriptionUpdate(refreshed)
                            refreshedSubscription = true
                        }
                    } catch (c: CancellationException) {
                        throw c
                    } catch (e: Exception) {
                        logCollector.warn(TAG, tmsg("Subscription refresh failed: ${e.message}"))
                        emptyList()
                    }
                }
                if (activeTraceId != traceId) return@launch
                if (configs.isEmpty()) configs = jsonConfigRepository.allConfigsIncluding(subscriptionRepository.getCachedConfigs())
                configs = filterConfigsForSelectedSubscription(configs)
                if (configs.isEmpty()) {
                    connectionCoordinator.send(ConnectionEvent.ShowError("Нет доступных серверов"))
                    return@launch
                }
                val selected = withTimeoutOrNull(SERVER_SELECTION_TIMEOUT_MS) {
                    ConnectServerSelector.select(
                        configs = configs,
                        isAutomaticMode = _uiState.value.isAutomaticMode,
                        manuallySelected = manuallySelectedServer,
                        lastServerStore = lastServerStore,
                        serverScanner = serverScanner,
                        autoSwitcher = autoSwitcher,
                        logCollector = logCollector,
                        traceMsg = { tmsg(it) },
                    )
                }
                if (selected == null) {
                    connectionCoordinator.send(ConnectionEvent.ShowError("Нет доступных серверов"))
                    return@launch
                }
                if (activeTraceId != traceId) return@launch
                connectionCoordinator.send(ConnectionEvent.ServerSelected(selected, traceId))
                pendingRankingConfigs = configs
                ensureServiceRunning()
                logCollector.info(TAG, tmsg("Selected ${selected.config.name} in ${android.os.SystemClock.elapsedRealtime() - selectionStartedAt}ms refreshed=$refreshedSubscription"))
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                connectionCoordinator.send(ConnectionEvent.ShowError(e.message ?: "Ошибка выбора сервера"))
            }
        }
    }

    fun onVpnPermissionDenied() {
        connectionCoordinator.send(ConnectionEvent.ShowError("Нет разрешения VPN. Выдайте доступ в настройках."))
    }

    fun disconnect() {
        cancelConnectingWatchdog()
        cancelSelectionJob("user disconnect")
        serverScanner.cancelScan()
        VpnProxyService.clearError()
        connectionCoordinator.send(ConnectionEvent.RequestDisconnect)
        ensureServiceRunning(action = VpnProxyService.ACTION_FORCE_STOP)
        _uiState.value = _uiState.value.copy(connectionState = ConnectionUiState.DISCONNECTED, isBusy = false, sessionStartedAt = null, isScanningServers = false)
    }

    fun forceDisconnect() {
        cancelConnectingWatchdog()
        cancelSelectionJob("forceDisconnect")
        serverScanner.cancelScan()
        VpnProxyService.clearError()
        connectionCoordinator.send(ConnectionEvent.ForceDisconnected("forceDisconnect"))
        ensureServiceRunning(action = VpnProxyService.ACTION_FORCE_STOP)
        _uiState.value = _uiState.value.copy(connectionState = ConnectionUiState.DISCONNECTED, isBusy = false, sessionStartedAt = null, isScanningServers = false)
    }

    fun switchServer() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val next = autoSwitcher.switchToNext() ?: return@launch
                connectionCoordinator.send(ConnectionEvent.ServerSelected(next, activeTraceId))
                ensureServiceRunning()
            } catch (e: Exception) {
                logCollector.error(TAG, "switchServer failed", e)
            }
        }
    }

    private fun ensureServiceRunning(action: String = VpnProxyService.ACTION_ENSURE_RUNNING) {
        try {
            val intent = Intent(context, VpnProxyService::class.java).apply { this.action = action }
            context.startForegroundService(intent)
        } catch (e: Exception) {
            logCollector.error(TAG, "startForegroundService failed action=$action", e)
        }
    }

    fun refreshSubscription() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isRefreshingSubscription = true)
                subscriptionRepository.refreshAll()
                updateAutoSwitcherProviderScope()
                if (connectionCoordinator.state.value is ConnectionState.Connected) refreshAutomaticScopeCandidates()
            } catch (e: Exception) {
                logCollector.error(TAG, "refreshSubscription failed", e)
            } finally {
                _uiState.value = _uiState.value.copy(isRefreshingSubscription = false)
            }
        }
    }

    fun onServerSelected(server: ScoringEngine.ScoredServer) {
        if (!autoSwitcher.forceSelect(server)) {
            logCollector.warn(TAG, "Manual server selection rejected: ${server.config.name}")
            return
        }
        manuallySelectedServer = server
        val traceId = TraceId.newId()
        activeTraceId = traceId
        connectionCoordinator.send(ConnectionEvent.ConnectRequested(traceId, userInitiated = true))
        viewModelScope.launch {
            try {
                lastServerStore.save(address = server.config.address, port = server.config.port, name = server.config.name, uuid = server.config.uuid)
                _uiState.value = _uiState.value.copy(currentServerName = server.config.name, currentRtt = server.latencyMs)
                connectionCoordinator.send(ConnectionEvent.ResetRetryCount)
                connectionCoordinator.send(ConnectionEvent.ServerSelected(server, traceId))
                if (connectionCoordinator.state.value is ConnectionState.Disconnected || connectionCoordinator.state.value is ConnectionState.Error) {
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
