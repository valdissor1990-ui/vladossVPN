package com.vpnproxy.ui.services

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vpnproxy.core.services.ServiceCatalog
import com.vpnproxy.core.services.ServiceCheckResult
import com.vpnproxy.core.services.ServiceCheckStatus
import com.vpnproxy.core.services.ServiceChecker
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.service.VpnProxyService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ServicesUiState(
    val isChecking: Boolean = false,
    val vpnActive: Boolean = false,
    val coreTransitioning: Boolean = false,
    val results: Map<String, ServiceCheckResult> = emptyMap(),
    val lastCheckedAt: Long? = null,
    val error: String? = null
)

@HiltViewModel
class ServicesViewModel @Inject constructor(
    private val logCollector: LogCollector
) : ViewModel() {

    companion object {
        private const val TAG = "ServicesViewModel"
    }

    private val checker = ServiceChecker()

    private val _state = MutableStateFlow(ServicesUiState())
    val state: StateFlow<ServicesUiState> = _state.asStateFlow()

    fun checkAll() {
        if (_state.value.isChecking) return
        viewModelScope.launch {
            val vpnActive = VpnProxyService.isRunning.value
            val transitioning = VpnProxyService.isCoreTransitioning.value
            _state.update {
                it.copy(
                    isChecking = true,
                    vpnActive = vpnActive,
                    coreTransitioning = transitioning,
                    results = emptyMap(),
                    error = null
                )
            }
            val results = runCatching {
                checker.checkAll(
                    targets = ServiceCatalog.defaultTargets,
                    vpnActive = vpnActive,
                    coreTransitioning = transitioning
                )
            }.getOrElse { error ->
                _state.update {
                    it.copy(
                        isChecking = false,
                        error = "Проверка не удалась: ${error.javaClass.simpleName}"
                    )
                }
                return@launch
            }
            if (results == null) {
                _state.update { it.copy(isChecking = false) }
                return@launch
            }
            // Journal only the aggregate: service hosts must never appear in logs.
            logCollector.info(
                TAG,
                "SERVICE-CHECK checked=${results.size} " +
                    "reachable=${results.count { it.status == ServiceCheckStatus.REACHABLE }} " +
                    "unreachable=${results.count { it.status == ServiceCheckStatus.UNREACHABLE }} " +
                    "unknown=${results.count { it.status == ServiceCheckStatus.UNKNOWN }} " +
                    "vpn_off=${results.count { it.status == ServiceCheckStatus.VPN_OFF }} " +
                    "transition=${results.count { it.status == ServiceCheckStatus.TRANSITION }}"
            )
            _state.update {
                it.copy(
                    isChecking = false,
                    results = results.associateBy { result -> result.serviceId },
                    lastCheckedAt = System.currentTimeMillis()
                )
            }
        }
    }
}
