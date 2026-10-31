package com.vpnproxy.ui.home

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vpnproxy.core.connection.ConnectionCoordinator
import com.vpnproxy.core.connection.ConnectionState
import com.vpnproxy.core.connection.TraceId
import com.vpnproxy.core.dualpath.DualPathStore
import com.vpnproxy.core.ping.LatencyBreakdown
import com.vpnproxy.core.ping.LatencyCalculator
import com.vpnproxy.core.ping.PingService
import com.vpnproxy.core.ping.PingTargets
import com.vpnproxy.core.scanner.ServerScanner
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.service.VpnProxyService
import com.vpnproxy.smartconnect.ScoringEngine.ScoredServer
import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import com.vpnproxy.subscription.SubscriptionRepository
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionCoordinator: ConnectionCoordinator,
    private val serverScanner: ServerScanner,
    private val subscriptionRepository: SubscriptionRepository,
    private val dualPathStore: DualPathStore,
    private val pingService: PingService,
    private val logCollector: LogCollector
) : ViewModel() {

    companion object {
        private const val TAG = "HomeViewModel"
    }

    enum class ConnectionUiState { DISCONNECTED, CONNECTING, CONNECTED, UNSTABLE, ERROR }

    data class UiState(
        val connectionState: ConnectionUiState = ConnectionUiState.DISCONNECTED,
        val selectedServerName: String? = null,
        val isBusy: Boolean = false,
        val isAutomaticMode: Boolean = true,
        val sessionStartedAt: Long? = null,
        val isScanningServers: Boolean = false,
        val scannedAvailableServers: Int = 0,
        val scanTotalServers: Int = 0,
        val scanProgressPercent: Int = 0,
        val dualPathEnabled: Boolean = false,
        val dualPathAppCount: Int = 0,
        val errorMessage: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _googleRtt = MutableStateFlow<Long?>(null)
    val googleRtt: StateFlow<Long?> = _googleRtt.asStateFlow()

    private val _latencyBreakdown = MutableStateFlow<LatencyBreakdown?>(null)
    val latencyBreakdown: StateFlow<LatencyBreakdown?> = _latencyBreakdown.asStateFlow()

    private var selectionJob: Job? = null
    private var pingJob: Job? = null
    private var activeTraceId: String? = null
    private var vpnPermissionRequester: (() -> Unit)? = null

    init {
        connectionCoordinator.registerSelectionCancel { cancelSelectionJob("actor RequestDisconnect") }
        viewModelScope.launch {
            connectionCoordinator.state.collect { state ->
                when (state) {
                    is ConnectionState.Disconnected -> {
                        cancelSelectionJob("state=$state")
                        _uiState.value = _uiState.value.copy(
                            connectionState = ConnectionUiState.DISCONNECTED,
                            isBusy = false,
                            sessionStartedAt = null,
                            selectedServerName = null
                        )
                    }
                    is ConnectionState.Connecting,
                    is ConnectionState.Reconnecting,
                    is ConnectionState.Verifying -> {
                        _uiState.value = _uiState.value.copy(
                            connectionState = ConnectionUiState.CONNECTING,
                            isBusy = true,
                            selectedServerName = state.let {
                                when (it) {
                                    is ConnectionState.Verifying -> it.serverName
                                    else -> _uiState.value.selectedServerName
                                }
                            }
                        )
                    }
                    is ConnectionState.Connected -> {
                        val server = state.serverName
                        _uiState.value = _uiState.value.copy(
                            connectionState = ConnectionUiState.CONNECTED,
                            isBusy = false,
                            selectedServerName = server,
                            sessionStartedAt = _uiState.value.sessionStartedAt ?: System.currentTimeMillis()
                        )
                    }
                    is ConnectionState.Error -> {
                        cancelSelectionJob("Error")
                        _uiState.value = _uiState.value.copy(
                            connectionState = ConnectionUiState.ERROR,
                            isBusy = false,
                            errorMessage = state.message
                        )
                    }
                    else -> {}
                }
            }
        }
        viewModelScope.launch {
            serverScanner.isScanning.collect { scanning ->
                _uiState.value = _uiState.value.copy(
                    isScanningServers = scanning,
                    scanProgressPercent = if (!scanning) 0 else _uiState.value.scanProgressPercent
                )
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
            serverScanner.scanProgress.collect { (done, total) ->
                val pct = if (total <= 0) 0 else ((done * 100) / total).coerceIn(0, 100)
                _uiState.value = _uiState.value.copy(
                    scanProgressPercent = pct,
                    scanTotalServers = maxOf(_uiState.value.scanTotalServers, total)
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
        startPinging()
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
        _uiState.value = _uiState.value.copy(
            isScanningServers = false,
            scanProgressPercent = 0
        )
    }

    fun toggleConnection() {
        when (_uiState.value.connectionState) {
            ConnectionUiState.CONNECTED,
            ConnectionUiState.UNSTABLE,
            ConnectionUiState.CONNECTING -> disconnect()
            else -> connect()
        }
    }

    fun connect() {
        val intent = VpnService.prepare(context)
        if (intent != null) {
            vpnPermissionRequester?.invoke()
            return
        }
        startConnectFlow()
    }

    fun onVpnPermissionGranted() = startConnectFlow()

    private fun startConnectFlow() {
        cancelSelectionJob("new connect")
        _uiState.value = _uiState.value.copy(connectionState = ConnectionUiState.CONNECTING, isBusy = true, errorMessage = null)
        selectionJob = viewModelScope.launch {
            try {
                val configs = subscriptionRepository.getAllConfigs()
                if (configs.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        connectionState = ConnectionUiState.ERROR,
                        isBusy = false,
                        errorMessage = "Нет серверов в подписке"
                    )
                    return@launch
                }
                val candidates = serverScanner.scanForConnection(configs, targetAvailableCount = 12)
                val best = serverScanner.bestAvailableServer(candidates)
                if (best == null) {
                    _uiState.value = _uiState.value.copy(
                        connectionState = ConnectionUiState.ERROR,
                        isBusy = false,
                        errorMessage = "Не найдено доступных серверов"
                    )
                    return@launch
                }
                connectionCoordinator.selectServer(best, reason = "AutoConnect")
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                logCollector.error(TAG, "connect failed", e)
                _uiState.value = _uiState.value.copy(
                    connectionState = ConnectionUiState.ERROR,
                    isBusy = false,
                    errorMessage = e.message
                )
            }
        }
    }

    fun disconnect() {
        cancelSelectionJob("user disconnect")
        serverScanner.cancelScan()
        connectionCoordinator.disconnect("user")
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionUiState.DISCONNECTED,
            isBusy = false,
            sessionStartedAt = null,
            isScanningServers = false,
            scanProgressPercent = 0
        )
    }

    fun forceDisconnect() {
        cancelSelectionJob("forceDisconnect")
        serverScanner.cancelScan()
        connectionCoordinator.disconnect("force")
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionUiState.DISCONNECTED,
            isBusy = false,
            sessionStartedAt = null,
            isScanningServers = false,
            scanProgressPercent = 0
        )
    }

    fun onServerSelected(server: ScoredServer) {
        cancelSelectionJob("manual select")
        selectionJob = viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(connectionState = ConnectionUiState.CONNECTING, isBusy = true)
                connectionCoordinator.selectServer(server, reason = "ServerSelected")
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                logCollector.error(TAG, "onServerSelected failed", e)
                _uiState.value = _uiState.value.copy(
                    connectionState = ConnectionUiState.ERROR,
                    isBusy = false,
                    errorMessage = e.message
                )
            }
        }
    }

    fun setAutomaticMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isAutomaticMode = enabled)
    }
}
