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
import kotlinx.coroutines.CancellationException
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
    val message: String? = null
)

@HiltViewModel
class ServicesViewModel @Inject constructor(
    private val logCollector: LogCollector
) : ViewModel() {

    companion object {
        private const val TAG = "ServicesViewModel"
        private const val ERROR_TEXT = "Не удалось проверить сервисы. Попробуйте позже."
        private const val BUSY_TEXT = "Проверка уже идёт."
    }

    private val checker = ServiceChecker()

    private val _state = MutableStateFlow(ServicesUiState())
    val state: StateFlow<ServicesUiState> = _state.asStateFlow()

    fun checkAll() {
        val claimed = tryClaimRun()
        if (claimed == null) {
            _state.update { it.copy(message = BUSY_TEXT) }
            return
        }

        viewModelScope.launch {
            val results = runCatching {
                checker.checkAll(
                    targets = ServiceCatalog.defaultTargets,
                    vpnActive = claimed.vpnActive,
                    coreTransitioning = claimed.coreTransitioning
                )
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                // Technical details belong in the journal, not in the UI.
                logCollector.warn(TAG, "SERVICE-CHECK failed error=${error.javaClass.simpleName}")
                _state.update { it.copy(isChecking = false, message = ERROR_TEXT) }
                return@launch
            }
            if (results == null) {
                _state.update { it.copy(isChecking = false, message = BUSY_TEXT) }
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
                    lastCheckedAt = System.currentTimeMillis(),
                    message = null
                )
            }
        }
    }

    /**
     * Atomically flips [ServicesUiState.isChecking]. Two taps in the same frame cannot both win,
     * so the second one reports "already running" instead of silently doing nothing.
     */
    private fun tryClaimRun(): ServicesUiState? {
        while (true) {
            val current = _state.value
            if (current.isChecking) return null
            val next = current.copy(
                isChecking = true,
                vpnActive = VpnProxyService.isRunning.value,
                coreTransitioning = VpnProxyService.isCoreTransitioning.value,
                results = emptyMap(),
                message = null
            )
            if (_state.compareAndSet(current, next)) return next
        }
    }
}
