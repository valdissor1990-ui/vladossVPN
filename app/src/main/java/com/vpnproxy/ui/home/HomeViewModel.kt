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
import com.vpnproxy.smartconnect.ScoringEngine
import com.vpnproxy.subscription.SubscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val lastServerStore: LastServerStore
) : ViewModel() {

    companion object {
        private const val TAG = "HomeVM"
        private const val CONNECTING_UI_WATCHDOG_MS = 60_000L
        private const val SERVER_SELECTION_TIMEOUT_MS = 5_000L
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
        val selectedSubscriptionId: String = ALL_SUBSCRIPTIONS_ID
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
    private var vpnPermissionRequester: (() -> Unit)? = null
    private var activeTraceId: String? = null
    @Volatile private var manuallySelectedServer: ScoringEngine.ScoredServer? = null
    @Volatile private var pendingRankingConfigs: List<com.vpnproxy.subscription.SubscriptionParser.ParsedConfig> = emptyList()

    init {
        connectionCoordinator.registerSelectionCancel {
            cancelSelectionJob("actor RequestDisconnect")
        }
        updateAutoSwitcherProviderScope()

        viewModelScope.launch {
            connectionCoordinator.state.collect { state ->
                when (state) {
                    is ConnectionState.Disconnected,
                    is ConnectionState.Disconnecting -> {
                        cancelConnectingWatchdog()
                        cancelSelectionJob("state=$state")
                        _uiState.value = _uiState.value.copy(
                            connectionState = ConnectionUiState.DISCONNECTED,
                            isBusy = false,
                            sessionStartedAt = null
                        )
                    }
                    is ConnectionState.Connecting,
                    is ConnectionState.Reconnecting,
                    is ConnectionState.Verifying -> {
                        _uiState.value = _uiState.value.copy(
                            connectionState = ConnectionUiState.CONNECTING,
                            isBusy = true
                        )
                        armConnectingWatchdog()
                    }
                    is ConnectionState.Connected -> {
                        val diagnosticOptions = DiagnosticSettings.load(context)
                        autoSwitcher.setDiagnosticFreeze(DebugFlags.diagnosticEnabled() && diagnosticOptions.freezeAutoSwitcherAfterConnected)
                        cancelConnectingWatchdog()
                        autoSwitcher.currentServer.value?.config?.let { server ->
                            lastServerStore.save(
                                address = server.address,
                                port = server.port,
                                name = server.name,
                                uuid = server.uuid
                            )
                        }
                        pendingRankingConfigs.takeIf { it.isNotEmpty() }?.let { configs ->
                            pendingRankingConfigs = emptyList()
                            autoSwitcher.rankInBackground(configs)
                        }
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
                        cancelSelectionJob("Error")
                        pendingRankingConfigs = emptyList()
                        _uiState.value = _uiState.value.copy(
                            connectionState = ConnectionUiState.ERROR,
                            isBusy = false,
                            sessionStartedAt = null
                        )
                        logCollector.warn(TAG, tmsg("Actor Error: ${state.message}"))
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
            subscriptionRepository.allConfigs.collect { configs ->
                _uiState.value = _uiState.value.copy(
                    serverCount = configs.distinctBy { it.connectionKey() }.size
                )
            }
        }
        viewModelScope.launch {
            serverScanner.isScanning.collect { scanning ->
                _uiState.value = _uiState.value.copy(isScanningServers = scanning)
            }
        }
    }

    private fun isRussianServer(config: com.vpnproxy.subscription.SubscriptionParser.ParsedConfig): Boolean =
        serverScanner.isRussianServer(config)

    private fun sameConfig(a: com.vpnproxy.subscription.SubscriptionParser.ParsedConfig, b: com.vpnproxy.subscription.SubscriptionParser.ParsedConfig): Boolean =
        a.connectionKey() == b.connectionKey()

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
        if (connectingWatchdogJob?.isActive == true) return
        connectingWatchdogJob = viewModelScope.launch {
            delay(CONNECTING_UI_WATCHDOG_MS)
            val s = connectionCoordinator.state.value
            if (s is ConnectionState.Connecting || s is ConnectionState.Reconnecting) {
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

    fun setVpnPermissionRequester(requester: () -> Unit) {
        vpnPermissionRequester = requester
    }

    fun cancelServerScan() {
        if (_uiState.value.isScanningServers) {
            serverScanner.cancelScan()
            logCollector.info(TAG, "Server scan cancellation requested from home button")
        }
    }

    fun toggleConnection() {
        when (_uiState.value.connectionState) {
            ConnectionUiState.CONNECTED, ConnectionUiState.UNSTABLE -> disconnect()
            ConnectionUiState.DISCONNECTED, ConnectionUiState.ERROR -> connect()
            ConnectionUiState.CONNECTING -> { }
        }
    }

    fun retryConnect() {
        connect()
    }

    fun setAutomaticSubscription(subscriptionId: String) {
        val validId = if (subscriptionId == ALL_SUBSCRIPTIONS_ID || subscriptionRepository.subscriptions.value.any { it.id == subscriptionId }) subscriptionId else ALL_SUBSCRIPTIONS_ID
        modePreferences.edit().putString(KEY_AUTOMATIC_SUBSCRIPTION_ID, validId).apply()
        _uiState.value = _uiState.value.copy(selectedSubscriptionId = validId)
        updateAutoSwitcherProviderScope()
        logCollector.info(TAG, "Automatic server subscription scope changed: $validId")
        if (connectionCoordinator.state.value is ConnectionState.Connected) {
            refreshAutomaticScopeCandidates()
        }
    }

    private fun filterConfigsForSelectedSubscription(configs: List<com.vpnproxy.subscription.SubscriptionParser.ParsedConfig>): List<com.vpnproxy.subscription.SubscriptionParser.ParsedConfig> {
        if (!_uiState.value.isAutomaticMode) return configs
        val selectedId = _uiState.value.selectedSubscriptionId
        if (selectedId == ALL_SUBSCRIPTIONS_ID) return configs
        val subscription = subscriptionRepository.subscriptions.value.firstOrNull { it.id == selectedId }
        if (subscription == null) {
            // Never broaden a missing provider selection to all providers: that can silently
            // connect through a different subscription after the selected one was removed.
            logCollector.warn(TAG, "Selected subscription is missing; automatic selection is blocked: $selectedId")
            return emptyList()
        }
        return configs.filter { it.providerTag == subscription.name }
    }

    private fun updateAutoSwitcherProviderScope() {
        val state = _uiState.value
        val scopedSelection = state.isAutomaticMode && state.selectedSubscriptionId != ALL_SUBSCRIPTIONS_ID
        val selectedSubscription = if (scopedSelection) {
            subscriptionRepository.subscriptions.value.firstOrNull { it.id == state.selectedSubscriptionId }
        } else null
        // A missing selected subscription must fail closed, not become null/"ALL".
        val blocked = scopedSelection && selectedSubscription == null
        val providerTag = selectedSubscription?.name
        autoSwitcher.setProviderScope(providerTag, blocked = blocked)
        connectionCoordinator.setProviderScope(providerTag, blocked = blocked)
    }

    private fun refreshAutomaticScopeCandidates() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val configs = filterConfigsForSelectedSubscription(subscriptionRepository.getAllConfigs())
                if (configs.isEmpty()) {
                    // Clear old fallback candidates immediately so a removed/empty provider
                    // cannot silently fall back to another provider.
                    connectionCoordinator.send(ConnectionEvent.SetFallbackCandidates(emptyList()))
                    autoSwitcher.rankInBackground(emptyList())
                    return@launch
                }
                if (!serverScanner.isScanCurrent(configs)) {
                    serverScanner.scanAll(configs, force = true)
                }
                val allowedProfiles = configs.map { it.providerTag to it.connectionKey() }.toSet()
                val candidates = serverScanner.availableServers()
                    .filter { (it.config.providerTag to it.config.connectionKey()) in allowedProfiles }
                    .filterNot { autoSwitcher.isBlacklisted(it.config) }
                connectionCoordinator.send(ConnectionEvent.SetFallbackCandidates(candidates))
                autoSwitcher.rankInBackground(configs)
                logCollector.info(TAG, "Fallback candidates refreshed for selected provider scope: ${candidates.size}")
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                logCollector.error(TAG, "Failed to refresh fallback candidates for selected provider scope", e)
                // Fail closed: stale candidates from the previously selected provider are unsafe.
                connectionCoordinator.send(ConnectionEvent.SetFallbackCandidates(emptyList()))
            }
        }
    }

    fun setAutomaticMode(enabled: Boolean) {
        modePreferences.edit().putBoolean(KEY_AUTOMATIC_MODE, enabled).apply()
        _uiState.value = _uiState.value.copy(isAutomaticMode = enabled)
        updateAutoSwitcherProviderScope()
        logCollector.info(TAG, "Server selection mode changed: " + (if (enabled) "automatic" else "manual"))
        if (connectionCoordinator.state.value is ConnectionState.Connected) {
            refreshAutomaticScopeCandidates()
        }
    }

    private fun connect() {
        Log.d(TAG, "1. Connect button clicked")
        try {
            activeTraceId = TraceId.newId()
            Log.i(TAG, TraceId.format(TAG, "User clicked Connect", activeTraceId))
            connectionCoordinator.send(ConnectionEvent.ConnectRequested(activeTraceId!!, userInitiated = true))
            Log.d(TAG, "4. Sent ConnectRequested to Actor")
            VpnProxyService.clearError()
            _uiState.value = _uiState.value.copy(
                connectionState = ConnectionUiState.CONNECTING,
                isBusy = true
            )
            armConnectingWatchdog()
            vpnPermissionRequester?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "Connect failed critically", e)
            connectionCoordinator.send(ConnectionEvent.ShowError(e.message ?: "Не удалось начать подключение"))
            _uiState.value = _uiState.value.copy(
                connectionState = ConnectionUiState.ERROR,
                isBusy = false
            )
        }
    }

    fun onVpnPermissionGranted() {
        val permissionGrantedAt = android.os.SystemClock.elapsedRealtime()
        val traceId = activeTraceId ?: TraceId.newId().also { activeTraceId = it }
        Log.i(TAG, TraceId.format(TAG, "VPN permission granted", traceId))

        _uiState.value = _uiState.value.copy(connectionState = ConnectionUiState.CONNECTING, isBusy = true)
        armConnectingWatchdog()
        ensureServiceRunning()

        selectionJob?.cancel()
        selectionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Full body restored from main - see remaining methods below in second half of file.
                // This marker must not remain: PLACEHOLDER_SECOND_HALF
                val selected = withTimeoutOrNull(SERVER_SELECTION_TIMEOUT_MS) {
                    if (_uiState.value.isAutomaticMode) {
                        val configs = filterConfigsForSelectedSubscription(subscriptionRepository.getAllConfigs())
                        autoSwitcher.selectImmediate(configs) ?: autoSwitcher.forceSelect(configs)
                    } else {
                        manuallySelectedServer ?: autoSwitcher.forceSelect(
                            filterConfigsForSelectedSubscription(subscriptionRepository.getAllConfigs())
                        )
                    }
                }
                if (selected == null) {
                    connectionCoordinator.send(ConnectionEvent.ShowError("Нет доступных серверов"))
                    return@launch
                }
                connectionCoordinator.send(ConnectionEvent.ServerSelected(selected, traceId))
                val intent = Intent(context, VpnProxyService::class.java).apply {
                    action = VpnProxyService.ACTION_CONNECT
                    putExtra(VpnProxyService.EXTRA_SERVER_NAME, selected.config.name)
                }
                context.startForegroundService(intent)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.e(TAG, "Selection failed", e)
                connectionCoordinator.send(ConnectionEvent.ShowError(e.message ?: "Ошибка выбора сервера"))
            }
        }
    }

    fun onVpnPermissionDenied() {
        logCollector.warn(TAG, "VPN permission denied")
        connectionCoordinator.send(ConnectionEvent.ShowError("Нет разрешения VPN. Выдайте доступ в настройках."))
    }

    fun forceDisconnect() {
        connectionCoordinator.send(ConnectionEvent.ForceDisconnected("forceDisconnect"))
        try {
            context.startService(Intent(context, VpnProxyService::class.java).apply {
                action = VpnProxyService.ACTION_DISCONNECT
            })
        } catch (e: Exception) {
            Log.e(TAG, "forceDisconnect failed", e)
        }
    }

    fun onServerSelected(server: ScoringEngine.ScoredServer) {
        viewModelScope.launch {
            try {
                manuallySelectedServer = server
                connectionCoordinator.send(ConnectionEvent.ServerSelected(server, activeTraceId))
                logCollector.info(TAG, "Manual server selected: ${server.config.name}")
            } catch (e: Exception) {
                logCollector.error(TAG, "onServerSelected failed", e)
            }
        }
    }

    fun switchServer() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val configs = filterConfigsForSelectedSubscription(subscriptionRepository.getAllConfigs())
                val next = autoSwitcher.switchToNext(configs) ?: return@launch
                connectionCoordinator.send(ConnectionEvent.ServerSelected(next, activeTraceId))
                context.startForegroundService(Intent(context, VpnProxyService::class.java).apply {
                    action = VpnProxyService.ACTION_CONNECT
                    putExtra(VpnProxyService.EXTRA_SERVER_NAME, next.config.name)
                })
            } catch (e: Exception) {
                logCollector.error(TAG, "switchServer failed", e)
            }
        }
    }

    fun refreshSubscription() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isRefreshingSubscription = true)
                subscriptionRepository.refreshAll()
                updateAutoSwitcherProviderScope()
                if (connectionCoordinator.state.value is ConnectionState.Connected) {
                    refreshAutomaticScopeCandidates()
                }
            } catch (e: Exception) {
                logCollector.error(TAG, "refreshSubscription failed", e)
            } finally {
                _uiState.value = _uiState.value.copy(isRefreshingSubscription = false)
            }
        }
    }

    private fun ensureServiceRunning() {
        try {
            context.startForegroundService(Intent(context, VpnProxyService::class.java).apply {
                action = VpnProxyService.ACTION_PREPARE
            })
        } catch (_: Exception) {
        }
    }

    fun disconnect() {
        connectionCoordinator.send(ConnectionEvent.UserDisconnect)
        try {
            context.startService(Intent(context, VpnProxyService::class.java).apply {
                action = VpnProxyService.ACTION_DISCONNECT
            })
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect failed", e)
        }
    }
}
