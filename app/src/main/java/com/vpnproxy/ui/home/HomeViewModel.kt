package com.vpnproxy.ui.home

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vpnproxy.core.tunnel.TrafficStatsCollector
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.service.VpnProxyService
import com.vpnproxy.smartconnect.AutoSwitcher
import com.vpnproxy.smartconnect.ScoringEngine
import com.vpnproxy.subscription.SubscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel главного экрана.
 *
 * Логика:
 * - Подключение/отключение через VpnProxyService
 * - Автовыбор лучшего сервера при подключении (Smart Connect)
 * - Ручная смена сервера (кнопка «Сменить»)
 * - Реактивное обновление состояния из сервиса и статистики
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val subscriptionRepository: SubscriptionRepository,
    private val autoSwitcher: AutoSwitcher,
    private val scoringEngine: ScoringEngine,
    private val trafficCollector: TrafficStatsCollector,
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

    /** Состояние трафика (для StatusBar). */
    val traffic: StateFlow<TrafficStatsCollector.TrafficSnapshot> = trafficCollector.traffic

    /** Callback, который MainActivity регистрирует для запроса VPN-разрешения. */
    private var vpnPermissionRequester: (() -> Unit)? = null

    init {
        // Наблюдение за состоянием сервиса
        viewModelScope.launch {
            VpnProxyService.isRunning.collect { running ->
                _uiState.value = _uiState.value.copy(
                    connectionState = if (running) ConnectionUiState.CONNECTED
                                    else ConnectionUiState.DISCONNECTED
                )
            }
        }

        // Наблюдение за текущим сервером от AutoSwitcher
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

        // Наблюдение за событиями переключения
        viewModelScope.launch {
            autoSwitcher.switchEvents.collect { event ->
                if (event != null) {
                    logCollector.info(TAG, "Switch event: $event")
                    autoSwitcher.clearSwitchEvent()
                }
            }
        }
    }

    fun setVpnPermissionRequester(requester: () -> Unit) {
        vpnPermissionRequester = requester
    }

    /**
     * Toggle подключения.
     * Если сервис запущен — стопаем. Если нет — запрашиваем VPN-разрешение.
     */
    fun toggleConnection() {
        val current = _uiState.value.connectionState
        when (current) {
            ConnectionUiState.CONNECTED, ConnectionUiState.UNSTABLE -> disconnect()
            ConnectionUiState.DISCONNECTED, ConnectionUiState.ERROR -> connect()
            ConnectionUiState.CONNECTING -> { /* уже в процессе */ }
        }
    }

    /** Запускает процесс подключения: запрос разрешения → автоселекция → старт сервиса. */
    private fun connect() {
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionUiState.CONNECTING,
            isBusy = true
        )
        // Сначала запрашиваем разрешение через Activity
        vpnPermissionRequester?.invoke()
    }

    /** Вызывается MainActivity после получения VPN-разрешения. */
    fun onVpnPermissionGranted() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(connectionState = ConnectionUiState.CONNECTING)

                // 1. Обновляем подписки (если пусты)
                val configs = subscriptionRepository.getAllConfigs().ifEmpty {
                    subscriptionRepository.refreshAll()
                    subscriptionRepository.getAllConfigs()
                }

                if (configs.isEmpty()) {
                    logCollector.warn(TAG, "No servers available for connection")
                    _uiState.value = _uiState.value.copy(
                        connectionState = ConnectionUiState.ERROR,
                        isBusy = false
                    )
                    return@launch
                }

                _uiState.value = _uiState.value.copy(serverCount = configs.size)

                // 2. Выбираем лучший сервер
                val best = autoSwitcher.selectBest(configs)
                if (best == null) {
                    logCollector.warn(TAG, "No server passed health check")
                    _uiState.value = _uiState.value.copy(
                        connectionState = ConnectionUiState.ERROR,
                        isBusy = false
                    )
                    return@launch
                }

                // 3. Запускаем сервис
                startVpnService()

                // 4. Небольшая задержка, чтобы сервис успел запуститься
                delay(500)
                _uiState.value = _uiState.value.copy(isBusy = false)
            } catch (e: Exception) {
                logCollector.error(TAG, "Connect failed", e)
                _uiState.value = _uiState.value.copy(
                    connectionState = ConnectionUiState.ERROR,
                    isBusy = false
                )
            }
        }
    }

    fun onVpnPermissionDenied() {
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionUiState.DISCONNECTED,
            isBusy = false
        )
        logCollector.warn(TAG, "VPN permission denied by user")
    }

    /** Остановка VPN. */
    fun disconnect() {
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

    /** Принудительная смена сервера (кнопка «Сменить»). */
    fun switchServer() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                connectionState = ConnectionUiState.CONNECTING,
                isBusy = true
            )
            val next = autoSwitcher.switchToNext()
            if (next == null) {
                logCollector.warn(TAG, "No next server to switch")
            } else {
                // Переподключаем сервис
                val intent = Intent(context, VpnProxyService::class.java).apply {
                    action = VpnProxyService.ACTION_RECONNECT
                }
                context.startService(intent)
            }
            delay(500)
            _uiState.value = _uiState.value.copy(isBusy = false)
        }
    }

    /** Запускает foreground-сервис. */
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
        autoSwitcher.stopMonitoring()
    }
}
