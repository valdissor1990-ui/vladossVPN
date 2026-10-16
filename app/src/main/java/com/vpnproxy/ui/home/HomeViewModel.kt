package com.vpnproxy.ui.home

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vpnproxy.core.ping.PingService
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
import javax.inject.Inject

/**
 * ViewModel главного экрана.
 */
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

    companion object { private const val TAG = "HomeVM" }

    data class UiState(
        val connectionState: ConnectionUiState = ConnectionUiState.DISCONNECTED,
        val currentRtt: Long = 0L,
        val currentServerName: String = "—",
        val serverCount: Int = 0,
        val isBusy: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val traffic: StateFlow<TrafficStatsCollector.TrafficSnapshot> = trafficCollector.traffic

    private val _googleRtt = MutableStateFlow<Long?>(null)
    val googleRtt: StateFlow<Long?> = _googleRtt.asStateFlow()

    private var pingJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private val connectTimeoutMs = 20_000L

    private var vpnPermissionRequester: (() -> Unit)? = null

    init {
        viewModelScope.launch {
            VpnProxyService.isRunning.collect { running ->
                _uiState.value = _uiState.value.copy(
                    connectionState = if (running) ConnectionUiState.CONNECTED
                    else ConnectionUiState.DISCONNECTED
                )
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
                    val result = pingService.pingDefault()
                    if (result.success) {
                        _googleRtt.value = result.rttMs
                        logCollector.debug(
                            TAG,
                            "Ping OK: ${result.rttMs}ms via ${result.method} (${result.target})"
                        )
                    } else {
                        _googleRtt.value = null
                        logCollector.warn(
                            TAG,
                            "Ping failed: ${result.error ?: "unknown"} (${result.method} ${result.target})"
                        )
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    _googleRtt.value = null
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
            ConnectionUiState.CONNECTING -> { /* уже в процессе */ }
        }
    }

    private fun connect() {
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionUiState.CONNECTING,
            isBusy = true
        )
        // Запускаем таймер: если через 20с не CONNECTED — принудительный сброс
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

                startVpnService()
                delay(500)
                // Успех — отменяем таймер
                connectTimeoutJob?.cancel()
                connectTimeoutJob = null
                _uiState.value = _uiState.value.copy(isBusy = false)
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
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        val intent = Intent(context, VpnProxyService::class.java).apply {
            action = VpnProxyService.ACTION_STOP
        }
        try {
            context.startService(intent)
        } catch (e: Exception) {
            logCollector.error(TAG, "Failed to send STOP", e)
        }
        _uiState.value = _uiState.value.copy(connectionState = ConnectionUiState.DISCONNECTED)
    }

    /** Принудительный сброс: остановка сервиса + сброс UI-состояния. */
    fun forceDisconnect() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        val intent = Intent(context, VpnProxyService::class.java).apply {
            action = VpnProxyService.ACTION_STOP
        }
        try {
            context.startService(intent)
        } catch (_: Exception) {
        }
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionUiState.DISCONNECTED,
            isBusy = false,
            currentServerName = "—",
            currentRtt = 0L
        )
        logCollector.info(TAG, "Force disconnect")
    }

    fun switchServer() {
        viewModelScope.launch {
            try {
                logCollector.info(TAG, "Switch server requested")

                // 1. Проверяем, что есть из чего выбирать
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

                // 2. Если рейтинг пуст — прогоняем селекцию, чтобы он заполнился
                if (ranked.isEmpty()) {
                    logCollector.info(TAG, "Switch: ranking not ready, running selectBest")
                    autoSwitcher.selectBest(configs)
                }

                _uiState.value = _uiState.value.copy(isBusy = true)

                // 3. Берём следующий сервер
                val next = autoSwitcher.switchToNext()
                if (next == null) {
                    logCollector.warn(TAG, "Switch: switchToNext returned null")
                    _uiState.value = _uiState.value.copy(isBusy = false)
                    return@launch
                }

                // 4. Обновляем UI немедленно
                _uiState.value = _uiState.value.copy(
                    currentServerName = next.config.name,
                    currentRtt = next.latencyMs
                )

                // 5. Если сервис запущен — перезапускаем; иначе просто стартуем
                val running = VpnProxyService.isRunning.value
                val action = if (running) VpnProxyService.ACTION_RECONNECT
                             else VpnProxyService.ACTION_START
                val intent = Intent(context, VpnProxyService::class.java).apply {
                    this.action = action
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }

                // 6. Небольшая пауза, чтобы UI успел отобразить состояние
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

    private fun startVpnService() {
        val intent = Intent(context, VpnProxyService::class.java).apply {
            action = VpnProxyService.ACTION_START
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            logCollector.info(TAG, "VPN service start requested")
        } catch (e: Exception) {
            logCollector.error(TAG, "Failed to start VPN service", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        pingJob?.cancel()
        connectTimeoutJob?.cancel()
        logCollector.debug(TAG, "HomeViewModel cleared")
    }
}
