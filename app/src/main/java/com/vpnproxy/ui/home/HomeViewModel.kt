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
    }

    data class UiState(
        val connectionState: ConnectionUiState = ConnectionUiState.DISCONNECTED,
        val currentRtt: Long = 0L,
        val currentServerName: String = "—",
        val serverCount: Int = 0,
        val isBusy: Boolean = false,
        val sessionStartedAt: Long? = null,
        val isRefreshingSubscription: Boolean = false,
        val isScanningServers: Boolean = false
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
    private var selectionJob: Job? = null
    private var vpnPermissionRequester: (() -> Unit)? = null
    private var activeTraceId: String? = null
    @Volatile private var manuallySelectedServer: ScoringEngine.ScoredServer? = null
    @Volatile private var pendingRankingConfigs: List<com.vpnproxy.subscription.SubscriptionParser.ParsedConfig> = emptyList()

    init {
        connectionCoordinator.registerSelectionCancel {
            cancelSelectionJob("actor RequestDisconnect")
        }

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

    private fun isRussianServer(config: com.vpnproxy.subscription.SubscriptionParser.ParsedConfig): Boolean {
        val name = config.name.lowercase()
        val provider = config.providerTag.lowercase()
        val sni = config.sni.orEmpty().lowercase()
        val ruToken = Regex("(^|[^a-z0-9])ru([^a-z0-9]|$)")
        return config.providerTag.contains("🇷🇺") ||
            ruToken.containsMatchIn(provider) ||
            name.contains("🇷🇺") ||
            ruToken.containsMatchIn(name) ||
            name.contains(".ru") || sni.contains(".ru")
    }

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
                val selectionStartedAt = android.os.SystemClock.elapsedRealtime()
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val hasNetwork = cm.activeNetwork != null
                var configs = subscriptionRepository.getAllConfigs()
                var refreshedSubscription = false
                // Refresh subscription before selecting a server; scanner results are invalidated with it.
                if (hasNetwork) {
                    configs = try {
                        subscriptionRepository.refreshAll()
                        subscriptionRepository.getAllConfigs().also { refreshed ->
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
                if (configs.isEmpty()) configs = subscriptionRepository.getCachedConfigs()
                if (configs.isEmpty()) {
                    connectionCoordinator.send(ConnectionEvent.ShowError("Нет доступных серверов. Проверьте интернет и обновите подписку"))
                    return@launch
                }

                if (!serverScanner.isScanCurrent(configs)) {
                    serverScanner.scanAll(configs, force = !refreshedSubscription)
                }
                val available = serverScanner.availableServers()
                    .filterNot { autoSwitcher.isBlacklisted(it.config) }
                val russian = available.filter { serverScanner.isRussianServer(it.config) }
                    .sortedBy { it.latencyMs }
                val foreign = available.filterNot { serverScanner.isRussianServer(it.config) }
                    .sortedBy { it.latencyMs }
                val candidates = russian + foreign
                if (candidates.isEmpty()) {
                    connectionCoordinator.send(ConnectionEvent.ShowError("Не найдено доступных серверов. Проверьте сеть и повторите поиск"))
                    _uiState.value = _uiState.value.copy(isBusy = false, connectionState = ConnectionUiState.ERROR)
                    return@launch
                }

                val chosen = candidates.first()
                val best = autoSwitcher.selectScanned(chosen, candidates)
                connectionCoordinator.send(ConnectionEvent.SetFallbackCandidates(candidates))
                Log.i(TAG, "Connecting to fastest scanned server [${chosen.config.name}] rtt=${chosen.latencyMs}ms")
                logCollector.info(TAG, tmsg("Scanned server selected: ${chosen.config.name}; available=${candidates.size}"))
                connectionCoordinator.send(ConnectionEvent.ServerSelected(best))
                connectionCoordinator.send(ConnectionEvent.UserConnect)
                logCollector.info(
                    TAG,
                    tmsg("ServerSelected+UserConnect after ${android.os.SystemClock.elapsedRealtime() - selectionStartedAt}ms")
                )
                pendingRankingConfigs = candidates.map { it.config }
            } catch (c: CancellationException) {
                logCollector.info(TAG, tmsg("Selection cancelled"))
                throw c
            } catch (e: Exception) {
                Log.e(TAG, "Instant server selection failed", e)
                logCollector.error(TAG, tmsg("Instant server selection failed: ${e.message}"), e)
                connectionCoordinator.send(ConnectionEvent.ShowError(e.message ?: "Не удалось выбрать сервер"))
                _uiState.value = _uiState.value.copy(connectionState = ConnectionUiState.ERROR, isBusy = false)
            }
        }
    }

    fun onVpnPermissionDenied() {
        cancelConnectingWatchdog()
        cancelSelectionJob("permission denied")
        connectionCoordinator.send(ConnectionEvent.RequestDisconnect)
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionUiState.DISCONNECTED,
            isBusy = false
        )
    }

    fun disconnect() {
        cancelConnectingWatchdog()
        cancelSelectionJob("user disconnect")
        VpnProxyService.clearError()
        connectionCoordinator.send(ConnectionEvent.RequestDisconnect)
        ensureServiceRunning(action = VpnProxyService.ACTION_REQUEST_DISCONNECT)
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionUiState.DISCONNECTED,
            isBusy = false,
            sessionStartedAt = null
        )
    }

    fun forceDisconnect() {
        cancelConnectingWatchdog()
        cancelSelectionJob("force disconnect")
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
        if (connectionCoordinator.isFatalErrorLocked) {
            logCollector.warn(TAG, "Automatic/manual quick switch ignored while fatal lock is active; select a server or reconnect explicitly")
            return
        }
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
            Log.d(TAG, tmsg("ensureServiceRunning action=$action"))
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
                serverScanner.scanAfterSubscriptionUpdate(subscriptionRepository.getAllConfigs())
                logCollector.info(
                    TAG,
                    "Manual subscription refresh: $n configs; scan refreshed"
                )
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                logCollector.error(TAG, "Manual subscription refresh failed", e)
            } finally {
                _uiState.value = _uiState.value.copy(
                    isRefreshingSubscription = false,
                    serverCount = subscriptionRepository.getAllConfigs().distinctBy { it.connectionKey() }.size
                )
            }
        }
    }

    fun onServerSelected(server: ScoringEngine.ScoredServer) {
        manuallySelectedServer = server
        viewModelScope.launch {
            try {
                // Explicit server selection is a manual action and may release the fatal lock.
                if (connectionCoordinator.isFatalErrorLocked) {
                    val traceId = TraceId.newId()
                    activeTraceId = traceId
                    connectionCoordinator.send(
                        ConnectionEvent.ConnectRequested(traceId, userInitiated = true)
                    )
                }
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
