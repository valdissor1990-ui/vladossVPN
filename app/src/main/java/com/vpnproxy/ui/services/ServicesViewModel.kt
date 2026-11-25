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
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

data class ServicesUiState(
    val isChecking: Boolean = false,
    val vpnActive: Boolean = false,
    val coreTransitioning: Boolean = false,
    val results: Map<String, ServiceCheckResult> = emptyMap(),
    val lastCheckedAt: Long? = null,
    val message: String? = null,
    /** True when the deadline cut the sweep short, so the table is incomplete. */
    val partial: Boolean = false
)

@HiltViewModel
class ServicesViewModel @Inject constructor(
    private val logCollector: LogCollector,
    private val checker: ServiceChecker
) : ViewModel() {

    companion object {
        private const val TAG = "ServicesViewModel"
        private const val ERROR_TEXT = "Не удалось проверить сервисы. Попробуйте позже."
        private const val BUSY_TEXT = "Проверка уже идёт."
        private const val TIMEOUT_TEXT = "Проверка заняла слишком долго. Попробуйте ещё раз."
        private const val RUN_TIMEOUT_MS = 20_000L
    }

    private val _state = MutableStateFlow(
        // Seed with the current tunnel state so the first frame does not claim "VPN off"
        // while it is actually running; the live subscription then keeps it fresh.
        ServicesUiState(
            vpnActive = VpnProxyService.isRunning.value,
            coreTransitioning = VpnProxyService.isCoreTransitioning.value
        )
    )
    val state: StateFlow<ServicesUiState> = _state.asStateFlow()

    private var runJob: Job? = null

    /** Guards against an old coroutine's `finally` clearing the flag of a newer run. */
    private val runGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    init {
        // Live tunnel state: the screen must never claim "VPN is off" while it is on.
        viewModelScope.launch {
            combine(
                VpnProxyService.isRunning,
                VpnProxyService.isCoreTransitioning
            ) { running, transitioning -> running to transitioning }
                .collect { (running, transitioning) ->
                    _state.update { it.copy(vpnActive = running, coreTransitioning = transitioning) }
                }
        }
    }

    fun checkAll() {
        if (tryClaimRun() == null) {
            _state.update { it.copy(message = BUSY_TEXT) }
            return
        }
        val generation = runGeneration.incrementAndGet()
        // Collected as targets finish: if the deadline hits, the user still sees partial data.
        val partial = java.util.concurrent.ConcurrentHashMap<String, ServiceCheckResult>()
        runJob = viewModelScope.launch {
            try {
                val results = withTimeout(RUN_TIMEOUT_MS) {
                    checker.checkAll(
                        targets = ServiceCatalog.defaultTargets,
                        vpnActive = { VpnProxyService.isRunning.value },
                        coreTransitioning = { VpnProxyService.isCoreTransitioning.value },
                        onResult = { result -> partial[result.serviceId] = result }
                    )
                }
                if (results == null) {
                    _state.update { it.copy(message = BUSY_TEXT) }
                    return@launch
                }
                // Journal only the aggregate: service hosts must never appear in logs.
                logCollector.info(
                    TAG,
                    "SERVICE-CHECK checked=${results.size} " +
                        "reachable=${results.count { it.status == ServiceCheckStatus.REACHABLE }} " +
                        "answered=${results.count { it.status == ServiceCheckStatus.ANSWERED }} " +
                        "unknown=${results.count { it.status == ServiceCheckStatus.UNKNOWN }} " +
                        "vpn_off=${results.count { it.status == ServiceCheckStatus.VPN_OFF }} " +
                        "transition=${results.count { it.status == ServiceCheckStatus.TRANSITION }}"
                )
                _state.update {
                    it.copy(
                        results = results.associateBy { result -> result.serviceId },
                        lastCheckedAt = System.currentTimeMillis(),
                        message = null,
                        partial = false
                    )
                }
            } catch (timeout: TimeoutCancellationException) {
                // Keep what already arrived: a dead upstream must not produce an empty table.
                _state.update {
                    it.copy(
                        results = partial.toMap(),
                        lastCheckedAt = if (partial.isEmpty()) it.lastCheckedAt
                        else System.currentTimeMillis(),
                        message = TIMEOUT_TEXT,
                        partial = partial.isNotEmpty()
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Technical details belong in the journal, not in the UI.
                logCollector.warn(TAG, "SERVICE-CHECK failed error=${error.javaClass.simpleName}")
                _state.update { current ->
                    current.copy(
                        results = partial.toMap(),
                        message = ERROR_TEXT,
                        partial = partial.isNotEmpty()
                    )
                }
            } finally {
                // Runs on success, failure and cancellation: without it a cancelled sweep
                // would leave the button stuck on "already running" forever. The generation
                // check keeps a late `finally` from clearing the flag of a newer run.
                if (generation == runGeneration.get()) {
                    _state.update { it.copy(isChecking = false) }
                }
            }
        }
    }

    /** Cancels the running sweep; [checkAll] can be called again immediately. */
    fun cancel() {
        runJob?.cancel()
        runJob = null
        // Invalidate the cancelled coroutine's `finally`, then release the flag here:
        // a cancel that lands before the coroutine starts never reaches its `finally`.
        runGeneration.incrementAndGet()
        _state.update { it.copy(isChecking = false) }
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
                results = emptyMap(),
                message = null,
                partial = false
            )
            if (_state.compareAndSet(current, next)) return next
        }
    }
}
