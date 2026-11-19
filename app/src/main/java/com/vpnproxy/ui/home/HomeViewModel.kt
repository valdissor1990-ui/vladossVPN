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
        private const val SERVER_SELECTION_TIMEOUT_MS = 80_000L
        private const val KEY_AUTOMATIC_MODE = "automatic_server_selection"
        private const val KEY_SELECTION_MODE = "server_selection_mode"
        private const val MODE_RECOMMEND = "recommend"
        private const val MODE_AUTOMATIC = "automatic"
        private const val MODE_MANUAL = "manual"
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
        val scanTotalServers: Int = 0,
        val isAutomaticMode: Boolean = false,
        val isRecommendationMode: Boolean = true,
        val selectionMode: String = MODE_RECOMMEND,
        val pendingRecommendation: ScoringEngine.ScoredServer? = null,
        val selectedSubscriptionId: String = ALL_SUBSCRIPTIONS_ID,
        val lastErrorMessage: String? = null,
        val dualPathEnabled: Boolean = false,
        val dualPathAppCount: Int = 0,
        val recentDrops: Int = 0,
        val flapping: Boolean = false,
        val notice: String? = null,
        val selectedServerKey: String = "",
        val currentSuccessRate: Float = 1f
    )

    private val modePreferences = context.getSharedPreferences("vladoss_ui_preferences", Context.MODE_PRIVATE)
    private fun readSelectionMode(): String {
        val saved = modePreferences.getString(KEY_SELECTION_MODE, null)
        if (saved == MODE_RECOMMEND || saved == MODE_AUTOMATIC || saved == MODE_MANUAL) return saved!!
        // Preserve an explicit manual preference; migrate existing automatic users to the
        // safer recommendation-first flow.
        val migrated = if (modePreferences.contains(KEY_AUTOMATIC_MODE) &&
            !modePreferences.getBoolean(KEY_AUTOMATIC_MODE, true)
        ) MODE_MANUAL else MODE_RECOMMEND
        modePreferences.edit().putString(KEY_SELECTION_MODE, migrated).apply()
        return migrated
    }
    private val initialSelectionMode = readSelectionMode()
    private val _uiState = MutableStateFlow(
        UiState(
            isAutomaticMode = initialSelectionMode == MODE_AUTOMATIC,
            isRecommendationMode = initialSelectionMode == MODE_RECOMMEND,
            selectionMode = initialSelectionMode,
            selectedSubscriptionId = modePreferences.getString(KEY_AUTOMATIC_SUBSCRIPTION_ID, ALL_SUBSCRIPTIONS_ID)
                ?: ALL_SUBSCRIPTIONS_ID
        )
    )

    val subscriptions = subscriptionRepository.subscriptions
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    val traffic: StateFlow<TrafficStatsCollector.TrafficSnapshot> = trafficCollector.traffic

    private val _profileServers = MutableStateFlow<List<ScoringEngine.ScoredServer>>(emptyList())
    val profileServers: StateFlow<List<ScoringEngine.ScoredServer>> = _profileServers.asStateFlow()

    private val _googleRtt = MutableStateFlow<Long?>(null)
    val googleRtt: StateFlow<Long?> = _googleRtt.asStateFlow()
    private val _latencyBreakdown = MutableStateFlow<LatencyBreakdown?>(null)
    val latencyBreakdown: StateFlow<LatencyBreakdown?> = _latencyBreakdown.asStateFlow()

    private var pingJob: Job? = null
    private var connectingWatchdogJob: Job? = null
    private var selectionJob: Job? = null
    private var manualReconnectJob: Job? = null
    @Volatile private var lastConnectClickMs: Long = 0L
    private var vpnPermissionRequester: (() -> Unit)? = null
    private var activeTraceId: String? = null
    @Volatile private var manuallySelectedServer: ScoringEngine.ScoredServer? = null
    @Volatile private var pendingRankingConfigs: List<com.vpnproxy.subscription.SubscriptionParser.ParsedConfig> = emptyList()
    @Volatile private var permissionFlowInProgress: Boolean = false
    @Volatile private var userStopRequested: Boolean = false
    private var stableClearJob: Job? = null

    init {
        connectionCoordinator.registerSelectionCancel { cancelSelectionJob("actor RequestDisconnect") }
        updateAutoSwitcherProviderScope()
        viewModelScope.launch {
            connectionCoordinator.state.collect { state ->
                when (state) {
                    is ConnectionState.Disconnected, is ConnectionState.Disconnecting -> {
                        cancelConnectingWatchdog()
                        cancelSelectionJob("state=$state")
                        stableClearJob?.cancel()
                        permissionFlowInProgress = false
                        val prev = _uiState.value.connectionState
                        val unexpected = !userStopRequested &&
                            (prev == ConnectionUiState.CONNECTED || prev == ConnectionUiState.UNSTABLE)
                        userStopRequested = false
                        val drops = if (unexpected) _uiState.value.recentDrops + 1 else _uiState.value.recentDrops
                        _uiState.value = _uiState.value.copy(
                            connectionState = ConnectionUiState.DISCONNECTED,
                            isBusy = false,
                            sessionStartedAt = null,
                            recentDrops = drops,
                            flapping = if (unexpected) drops >= 3 else _uiState.value.flapping
                        )
                    }
                    is ConnectionState.Connecting, is ConnectionState.Reconnecting -> {
                        _uiState.value = _uiState.value.copy(
                            connectionState = ConnectionUiState.CONNECTING,
                            isBusy = true,
                            lastErrorMessage = null
                        )
                        armConnectingWatchdog()
                    }
                    is ConnectionState.Verifying -> {
                        _uiState.value = _uiState.value.copy(
                            connectionState = ConnectionUiState.VERIFYING,
                            isBusy = true,
                            lastErrorMessage = null
                        )
                        armConnectingWatchdog()
                    }
                    is ConnectionState.Connected -> {
                        val diagnosticOptions = DiagnosticSettings.load(context)
                        autoSwitcher.setDiagnosticFreeze(DebugFlags.diagnosticEnabled() && diagnosticOptions.freezeAutoSwitcherAfterConnected)
                        cancelConnectingWatchdog()
                        permissionFlowInProgress = false
                        autoSwitcher.currentServer.value?.config?.let { server ->
                            lastServerStore.save(address = server.address, port = server.port, name = server.name, uuid = server.uuid, connectionKey = server.connectionKey())
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
                        armStableClear()
                    }
                    is ConnectionState.Error -> {
                        cancelConnectingWatchdog()
                        cancelSelectionJob("Error")
                        stableClearJob?.cancel()
                        permissionFlowInProgress = false
                        pendingRankingConfigs = emptyList()
                        val prev = _uiState.value.connectionState
                        val unexpected = !userStopRequested &&
                            (prev == ConnectionUiState.CONNECTED || prev == ConnectionUiState.UNSTABLE)
                        userStopRequested = false
                        val drops = if (unexpected) _uiState.value.recentDrops + 1 else _uiState.value.recentDrops
                        _uiState.value = _uiState.value.copy(
                            connectionState = ConnectionUiState.ERROR,
                            isBusy = false,
                            sessionStartedAt = null,
                            lastErrorMessage = state.message.takeIf { it.isNotBlank() },
                            recentDrops = drops,
                            flapping = if (unexpected) drops >= 3 else _uiState.value.flapping
                        )
                        logCollector.warn(TAG, tmsg("Actor Error: ${state.message}"))
                    }
                }
            }
        }
        viewModelScope.launch {
            autoSwitcher.currentServer.collect { server ->
                if (server != null) {
                    val rtt = server.latencyMs.takeUnless { it == Long.MAX_VALUE } ?: _uiState.value.currentRtt
                    _uiState.value = _uiState.value.copy(
                        currentServerName = server.config.name,
                        currentRtt = rtt,
                        currentSuccessRate = server.successRate,
                        selectedServerKey = server.config.connectionKey()
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
            serverScanner.scannedServers.collect { servers ->
                _uiState.value = _uiState.value.copy(
                    scannedAvailableServers = servers.count { it.successRate > 0.5f && it.latencyMs != Long.MAX_VALUE },
                    scanTotalServers = servers.size
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
        viewModelScope.launch {
            combine(
                subscriptionRepository.allConfigs,
                jsonConfigRepository.configs,
                serverScanner.scannedServers
            ) { subscriptionConfigs, _, scanned ->
                val merged = jsonConfigRepository.allConfigsIncluding(subscriptionConfigs)
                val byKey = scanned.associateBy { it.config.connectionKey() }
                serverScanner.sortForCurrentNetwork(
                    merged.distinctBy { it.connectionKey() }.map { config ->
                        byKey[config.connectionKey()] ?: ScoringEngine.ScoredServer(
                            config = config,
                            latencyMs = Long.MAX_VALUE,
                            speedMbps = 0f,
                            successRate = -1f,
                            score = 0f
                        )
                    }
                )
            }.collect { _profileServers.value = it }
        }
    }

    private fun tmsg(message: String): String =
        TraceId.format(TAG, message, activeTraceId).removePrefix("$TAG | ")

    private fun startConnectFlow(): Boolean {
        if (permissionFlowInProgress) {
            logCollector.warn(TAG, "Connect flow already active; ignoring duplicate request")
            return false
        }
        permissionFlowInProgress = true
        activeTraceId = TraceId.newId()
        return true
    }

    private fun endConnectFlow() {
        permissionFlowInProgress = false
    }

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

    private fun armStableClear() {
        stableClearJob?.cancel()
        stableClearJob = viewModelScope.launch {
            delay(60_000)
            if (_uiState.value.connectionState == ConnectionUiState.CONNECTED) {
                _uiState.value = _uiState.value.copy(recentDrops = 0, flapping = false)
            }
        }
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
                    val measuredRtt = if (breakdown.isValid) breakdown.compositeScore.toLong() else null
                    _googleRtt.value = measuredRtt
                    // The power-button quality indicator must follow fresh active-tunnel
                    // measurements, not only the RTT captured during server preflight.
                    if (_uiState.value.connectionState.hasTunnel() && measuredRtt != null) {
                        _uiState.value = _uiState.value.copy(currentRtt = measuredRtt)
                    }
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
        if (_uiState.value.selectionMode == MODE_MANUAL) return configs
        val selectedId = _uiState.value.selectedSubscriptionId
        if (selectedId == ALL_SUBSCRIPTIONS_ID) return configs
        val subscription = subscriptionRepository.subscriptions.value.firstOrNull { it.id == selectedId }
        if (subscription == null) return emptyList()
        return configs.filter { it.providerTag == subscription.name }
    }

    private fun updateAutoSwitcherProviderScope() {
        val state = _uiState.value
        val scopedSelection = state.selectionMode != MODE_MANUAL && state.selectedSubscriptionId != ALL_SUBSCRIPTIONS_ID
        val selectedSubscription = if (scopedSelection) subscriptionRepository.subscriptions.value.firstOrNull { it.id == state.selectedSubscriptionId } else null
        val blocked = scopedSelection && selectedSubscription == null
        val providerTag = selectedSubscription?.name
        val recommendationOnly = state.isRecommendationMode
        autoSwitcher.setManualSelectionMode(!state.isAutomaticMode || recommendationOnly)
        connectionCoordinator.setManualSelectionMode(!state.isAutomaticMode || recommendationOnly)
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
                    .filter { it.successRate > 0.5f && it.latencyMs != Long.MAX_VALUE }
                    .filterNot { autoSwitcher.isBlacklisted(it.config) }
                    .sortedBy { it.latencyMs }
                connectionCoordinator.send(ConnectionEvent.SetFallbackCandidates(candidates))
                logCollector.info(TAG, "Fallback candidates refreshed from individual server scan: verified=${candidates.size}; totalProfiles=${configs.size}")
                // Do not re-probe every profile through the already active VPN tunnel.
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                logCollector.error(TAG, "Failed to refresh fallback candidates", e)
                connectionCoordinator.send(ConnectionEvent.SetFallbackCandidates(emptyList()))
            }
        }
    }

    fun setAutomaticMode(enabled: Boolean) {
        setSelectionMode(if (enabled) MODE_AUTOMATIC else MODE_MANUAL)
    }

    fun setSelectionMode(mode: String) {
        val normalized = mode.takeIf { it == MODE_RECOMMEND || it == MODE_AUTOMATIC || it == MODE_MANUAL }
            ?: MODE_RECOMMEND
        modePreferences.edit()
            .putString(KEY_SELECTION_MODE, normalized)
            .putBoolean(KEY_AUTOMATIC_MODE, normalized == MODE_AUTOMATIC)
            .apply()
        if (normalized != MODE_MANUAL) manuallySelectedServer = null
        _uiState.value = _uiState.value.copy(
            selectionMode = normalized,
            isAutomaticMode = normalized == MODE_AUTOMATIC,
            isRecommendationMode = normalized == MODE_RECOMMEND,
            pendingRecommendation = null
        )
        updateAutoSwitcherProviderScope()
        if (connectionCoordinator.state.value is ConnectionState.Connected) refreshAutomaticScopeCandidates()
    }

    fun dismissRecommendation() {
        _uiState.value = _uiState.value.copy(pendingRecommendation = null)
    }

    fun confirmRecommendation() {
        if (_uiState.value.isBusy || !_uiState.value.isRecommendationMode) return
        val server = _uiState.value.pendingRecommendation ?: return
        _uiState.value = _uiState.value.copy(
            pendingRecommendation = null,
            currentServerName = server.config.name,
            currentRtt = server.latencyMs,
            lastErrorMessage = null
        )
        manuallySelectedServer = server
        // This is a second, explicit confirmation; bypass the original tap debounce.
        lastConnectClickMs = 0L
        logCollector.info(TAG, tmsg("User confirmed recommended server: ${server.config.name}; starting normal permission and preflight flow"))
        connect()
    }

    private fun connect(fromManualReconnect: Boolean = false) {
        if (!fromManualReconnect) {
            manualReconnectJob?.cancel()
            manualReconnectJob = null
        }
        runCatching { BatteryPolicy.requestIgnoreBatteryOptimizations(context) }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastConnectClickMs < 900L) {
            Log.d(TAG, "Connect debounced")
            return
        }
        lastConnectClickMs = now
        cancelSelectionJob("new connect")

        // An explicit Connect tap is permission to retry the manually selected endpoint, even if a prior attempt temporarily blacklisted it on this network.
        manuallySelectedServer?.config?.let { autoSwitcher.clearServerBlacklist(it, "user Connect") }
        if (!startConnectFlow()) return
        try {
            connectionCoordinator.send(ConnectionEvent.ConnectRequested(activeTraceId!!, userInitiated = true))
            VpnProxyService.clearError()
            _uiState.value = _uiState.value.copy(connectionState = ConnectionUiState.CONNECTING, isBusy = true, lastErrorMessage = null)
            armConnectingWatchdog()
            vpnPermissionRequester?.invoke() ?: run {
                logCollector.warn(TAG, "VPN permission requester not set; proceeding without prompt")
                onVpnPermissionGranted()
            }
        } catch (e: Exception) {
            endConnectFlow()
            connectionCoordinator.send(ConnectionEvent.ShowError(e.message ?: "Не удалось начать подключение"))
            _uiState.value = _uiState.value.copy(connectionState = ConnectionUiState.ERROR, isBusy = false)
        }
    }

    fun onVpnPermissionGranted() {
        // The Activity result can arrive after the coordinator has entered Connecting,
        // while the local UI flag was reset by a concurrent state callback. The coordinator
        // state is authoritative: never drop a valid permission result for an active connect.
        if (!permissionFlowInProgress && connectionCoordinator.state.value !is ConnectionState.Connecting) {
            logCollector.warn(TAG, "VPN permission granted event ignored: no active connect flow or Connecting state")
            return
        }
        if (!permissionFlowInProgress) {
            permissionFlowInProgress = true
            logCollector.warn(TAG, "Accepting VPN permission result because coordinator is already Connecting")
        }

        val traceId = activeTraceId ?: TraceId.newId().also { activeTraceId = it }
        _uiState.value = _uiState.value.copy(connectionState = ConnectionUiState.CONNECTING, isBusy = true)
        // Keep a startup scan alive. scanForConnection waits for its mutex and reuses
        // results only when the exact profiles and current network bucket still match.
        armConnectingWatchdog()
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
                            // Refresh subscription data first; fresh profile reachability probing
                            // is mandatory below before the VPN service is started.
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
                    endConnectFlow()
                    connectionCoordinator.send(ConnectionEvent.ShowError("Нет доступных серверов"))
                    return@launch
                }
                // ConnectRequested intentionally does not start the VPN service. If a previous
                // failed session is still tearing down, wait for the core and its port lease to
                // become idle before starting temporary DeepProbe instances.
                val coreStopped = withTimeoutOrNull(20_000L) {
                    while (VpnProxyService.isRunning.value || VpnProxyService.isCoreTransitioning.value) {
                        delay(100L)
                    }
                    true
                } ?: false
                if (!coreStopped) {
                    endConnectFlow()
                    connectionCoordinator.send(ConnectionEvent.ShowError("Предыдущее VPN-подключение ещё останавливается. Повторите попытку через несколько секунд."))
                    return@launch
                }
                logCollector.info(TAG, tmsg("Starting connection preflight: profiles=${configs.size}; preferred=${manuallySelectedServer?.config?.name ?: "auto"}; reuse recent same-network scan when valid"))
                val reachable = withTimeoutOrNull(SERVER_SELECTION_TIMEOUT_MS) {
                    serverScanner.scanForConnection(
                        configs = configs,
                        targetAvailableCount = configs.size + 1,
                        preferredConfig = manuallySelectedServer?.config,
                        forceFresh = false
                    )
                }
                if (reachable == null) {
                    endConnectFlow()
                    connectionCoordinator.send(ConnectionEvent.ShowError("Проверка серверов заняла слишком много времени. Повторите подключение."))
                    return@launch
                }
                val verifiedServers = reachable
                    .filter { it.successRate > 0.5f && it.latencyMs != Long.MAX_VALUE }
                    .sorted()
                val verifiedConfigs = verifiedServers.map { it.config }
                val manualFallback = if (verifiedConfigs.isEmpty()) {
                    manuallySelectedServer?.takeIf { manual ->
                        configs.any { it.connectionKey() == manual.config.connectionKey() }
                    }
                } else null
                if (verifiedConfigs.isEmpty() && manualFallback == null) {
                    endConnectFlow()
                    connectionCoordinator.send(ConnectionEvent.ShowError("Не найдено серверов с подтверждённой доступностью"))
                    logCollector.warn(TAG, tmsg("Preflight finished without reachable profiles: checked=${configs.size}; manualFallback=false"))
                    return@launch
                }
                val selected = if (manualFallback != null) {
                    logCollector.warn(
                        TAG,
                        tmsg("Preflight found zero reachable profiles; trying the explicitly selected server as a one-shot fallback: ${manualFallback.config.name}")
                    )
                    manualFallback
                } else {
                    withTimeoutOrNull(5_000L) {
                        ConnectServerSelector.select(
                            configs = verifiedConfigs,
                            isAutomaticMode = _uiState.value.isAutomaticMode || _uiState.value.isRecommendationMode,
                            manuallySelected = manuallySelectedServer,
                            scannedCandidates = verifiedServers,
                            lastServerStore = lastServerStore,
                            autoSwitcher = autoSwitcher,
                            logCollector = logCollector,
                            traceMsg = { tmsg(it) },
                        )
                    }
                }
                if (selected == null) {
                    endConnectFlow()
                    connectionCoordinator.send(ConnectionEvent.ShowError("Не удалось выбрать проверенный сервер"))
                    return@launch
                }
                if (activeTraceId != traceId) return@launch
                if (_uiState.value.isRecommendationMode && manuallySelectedServer == null) {
                    cancelConnectingWatchdog()
                    endConnectFlow()
                    _uiState.value = _uiState.value.copy(
                        connectionState = ConnectionUiState.DISCONNECTED,
                        isBusy = false,
                        pendingRecommendation = selected,
                        lastErrorMessage = null
                    )
                    logCollector.info(TAG, tmsg("Server recommendation is ready; waiting for explicit user confirmation"))
                    return@launch
                }
                connectionCoordinator.send(ConnectionEvent.SetFallbackCandidates(verifiedServers))
                connectionCoordinator.send(ConnectionEvent.ServerSelected(selected, traceId))
                if (_uiState.value.isRecommendationMode) manuallySelectedServer = null
                pendingRankingConfigs = emptyList()
                ensureServiceRunning()
                logCollector.info(TAG, tmsg("Selected freshly probed server ${selected.config.name} in ${android.os.SystemClock.elapsedRealtime() - selectionStartedAt}ms; reachable=${verifiedConfigs.size} refreshed=$refreshedSubscription"))
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                endConnectFlow()
                connectionCoordinator.send(ConnectionEvent.ShowError(e.message ?: "Ошибка выбора сервера"))
            }
        }
    }

    fun onVpnPermissionDenied() {
        endConnectFlow()
        connectionCoordinator.send(ConnectionEvent.ShowError("Нет разрешения VPN. Выдайте доступ в настройках."))
    }

    fun disconnect() {
        userStopRequested = true
        manualReconnectJob?.cancel()
        manualReconnectJob = null
        cancelConnectingWatchdog()
        cancelSelectionJob("user disconnect")
        serverScanner.cancelScan()
        VpnProxyService.clearError()
        endConnectFlow()
        connectionCoordinator.send(ConnectionEvent.RequestDisconnect)
        ensureServiceRunning(action = VpnProxyService.ACTION_FORCE_STOP)
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionUiState.DISCONNECTED,
            isBusy = false,
            sessionStartedAt = null,
            isScanningServers = false,
            recentDrops = 0,
            flapping = false
        )
    }

    fun forceDisconnect() {
        userStopRequested = true
        manualReconnectJob?.cancel()
        manualReconnectJob = null
        cancelConnectingWatchdog()
        cancelSelectionJob("forceDisconnect")
        serverScanner.cancelScan()
        VpnProxyService.clearError()
        endConnectFlow()
        connectionCoordinator.send(ConnectionEvent.ForceDisconnected("forceDisconnect"))
        ensureServiceRunning(action = VpnProxyService.ACTION_FORCE_STOP)
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionUiState.DISCONNECTED,
            isBusy = false,
            sessionStartedAt = null,
            isScanningServers = false,
            recentDrops = 0,
            flapping = false
        )
    }

    fun switchServer() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val next = autoSwitcher.switchToNext() ?: return@launch
                withContext(Dispatchers.Main.immediate) {
                    // Route the explicit switch through the same teardown + fresh preflight
                    // path as tapping a server row.
                    onServerSelected(next)
                }
            } catch (c: CancellationException) {
                throw c
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
        // A direct user selection is an explicit retry, unlike background auto-selection.
        autoSwitcher.clearServerBlacklist(server.config, "user selected server")
        if (!autoSwitcher.forceSelect(server)) {
            logCollector.warn(TAG, "Manual server selection rejected: ${server.config.name}")
            return
        }
        manuallySelectedServer = server
        _uiState.value = _uiState.value.copy(
            currentServerName = server.config.name,
            currentRtt = server.latencyMs.takeUnless { it == Long.MAX_VALUE } ?: _uiState.value.currentRtt,
            currentSuccessRate = server.successRate,
            selectedServerKey = server.config.connectionKey()
        )

        val current = connectionCoordinator.state.value
        if (current is ConnectionState.Disconnected && permissionFlowInProgress) {
            // ConnectRequested deliberately keeps the coordinator Disconnected while
            // permission/preflight is in progress. Record this choice for the active
            // selection job instead of cancelling it and trying to start a second flow.
            logCollector.info(TAG, "Manual server choice updated during active permission/preflight flow: ${server.config.name}")
            return
        }
        if (current is ConnectionState.Disconnected || current is ConnectionState.Error) {
            connect()
            return
        }

        // A server row may be selected while another tunnel is active or starting.
        // Never feed its possibly stale scan result directly to ServerSelected: stop
        // the old core first, then let the normal Connect flow perform fresh preflight.
        manualReconnectJob?.cancel()
        val traceId = TraceId.newId()
        activeTraceId = traceId
        userStopRequested = true
        manualReconnectJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Persist only after the new tunnel is actually Connected; a selected
                // but unreachable profile must not replace the last known-good boot target.
                connectionCoordinator.send(ConnectionEvent.RequestDisconnect)
                // Ensure a service instance is present to consume the stop request even
                // if the previous connect had not finished starting the foreground service.
                ensureServiceRunning(action = VpnProxyService.ACTION_FORCE_STOP)
                val stopped = withTimeoutOrNull(20_000L) {
                    while (
                        VpnProxyService.isRunning.value ||
                        VpnProxyService.isCoreTransitioning.value ||
                        connectionCoordinator.state.value !is ConnectionState.Disconnected
                    ) {
                        delay(100L)
                    }
                    true
                } ?: false

                if (!stopped) {
                    if (activeTraceId == traceId) {
                        endConnectFlow()
                        connectionCoordinator.send(
                            ConnectionEvent.ShowError("Не удалось остановить прежнее подключение. Повторите попытку.")
                        )
                    }
                    return@launch
                }
                if (activeTraceId != traceId) return@launch

                withContext(Dispatchers.Main.immediate) {
                    endConnectFlow()
                    // connect() will request VPN permission if needed and run the same
                    // fresh subscription/preflight pipeline as the main Connect button.
                    lastConnectClickMs = 0L
                    connect(fromManualReconnect = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (activeTraceId == traceId) {
                    endConnectFlow()
                    logCollector.error(TAG, "Manual server switch preflight handoff failed", failure)
                    connectionCoordinator.send(
                        ConnectionEvent.ShowError(failure.message ?: "Не удалось переключить сервер")
                    )
                }
            }
        }
    }

    fun clearNotice() {
        if (_uiState.value.notice != null) {
            _uiState.value = _uiState.value.copy(notice = null)
        }
    }

    fun checkCurrentConnection() {
        viewModelScope.launch {
            val state = _uiState.value.connectionState
            if (!state.hasTunnel()) {
                _uiState.value = _uiState.value.copy(notice = "Нет активного подключения")
                return@launch
            }
            _uiState.value = _uiState.value.copy(notice = "Проверяем текущее подключение…")
            try {
                val breakdown = withContext(Dispatchers.IO) {
                    val region = PingTargets.detectRegion(context)
                    val multi = pingService.pingAll(targets = PingTargets.forRegion(region), region = region)
                    LatencyCalculator.compute(multi.results)
                }
                _latencyBreakdown.value = breakdown
                val rtt = if (breakdown.isValid) breakdown.compositeScore.toLong() else 0L
                _googleRtt.value = rtt.takeIf { it > 0L }
                _uiState.value = _uiState.value.copy(
                    currentRtt = if (rtt > 0L) rtt else _uiState.value.currentRtt,
                    notice = if (breakdown.isValid) "Связь есть · ${rtt} мс" else "Проверка не подтвердила связь"
                )
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(notice = e.message ?: "Не удалось проверить подключение")
            }
        }
    }

    fun pingAllServers() {
        viewModelScope.launch(Dispatchers.IO) {
            // Isolated per-profile probes cannot run alongside the active VPN core.
            // Do not report cached results as if a fresh measurement had completed.
            if (VpnProxyService.isRunning.value || VpnProxyService.isCoreTransitioning.value) {
                _uiState.value = _uiState.value.copy(
                    notice = "Чтобы проверить задержку всех серверов, сначала отключите VPN"
                )
                return@launch
            }
            if (serverScanner.isScanning.value) {
                _uiState.value = _uiState.value.copy(notice = "Проверка серверов уже выполняется")
                return@launch
            }
            val configs = jsonConfigRepository.allConfigsIncluding(subscriptionRepository.getAllConfigs())
            if (configs.isEmpty()) {
                _uiState.value = _uiState.value.copy(notice = "Нет серверов для проверки задержки")
                return@launch
            }
            _uiState.value = _uiState.value.copy(notice = "Измеряем задержку серверов…")
            try {
                val results = serverScanner.scanAll(configs, force = true)
                val measured = results.count { it.successRate > 0.5f && it.latencyMs != Long.MAX_VALUE }
                val total = results.size
                val message = if (measured > 0) {
                    "Проверено серверов: $measured из $total"
                } else {
                    "Не удалось подтвердить доступность серверов"
                }
                _uiState.value = _uiState.value.copy(notice = message)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(notice = e.message ?: "Не удалось измерить задержку")
            }
        }
    }

    fun addSubscription(url: String, name: String) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            _uiState.value = _uiState.value.copy(notice = "URL должен начинаться с http:// или https://")
            return
        }
        viewModelScope.launch {
            val host = trimmed.removePrefix("https://").removePrefix("http://").substringBefore("/").ifBlank { "Подписка" }
            val title = name.trim().ifBlank { host }
            val ok = subscriptionRepository.addSubscription(trimmed, title, title)
            if (!ok) {
                _uiState.value = _uiState.value.copy(notice = "Не удалось сохранить подписку")
                return@launch
            }
            val added = subscriptionRepository.subscriptions.value.lastOrNull()
            if (added != null) setAutomaticSubscription(added.id)
            val refreshed = runCatching { subscriptionRepository.refreshAll() }.getOrDefault(0)
            _uiState.value = _uiState.value.copy(
                notice = if (refreshed > 0) "Подписка добавлена · профилей: $refreshed" else "Подписка сохранена. Обновите её, если список пуст"
            )
        }
    }

    fun removeSelectedSubscription() {
        val id = _uiState.value.selectedSubscriptionId
        if (id == ALL_SUBSCRIPTIONS_ID) {
            _uiState.value = _uiState.value.copy(notice = "Сначала выберите одну подписку")
            return
        }
        subscriptionRepository.removeSubscription(id)
        setAutomaticSubscription(ALL_SUBSCRIPTIONS_ID)
        _uiState.value = _uiState.value.copy(notice = "Подписка удалена")
    }
}
