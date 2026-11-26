package com.vpnproxy.ui.doctor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vpnproxy.core.doctor.ConnectionDoctor
import com.vpnproxy.core.doctor.DiagnosticResult
import com.vpnproxy.core.doctor.DiagnosticStatus
import com.vpnproxy.core.doctor.DoctorContext
import com.vpnproxy.core.doctor.DoctorReport
import com.vpnproxy.core.doctor.Remedy
import com.vpnproxy.core.doctor.RemedyExecution
import com.vpnproxy.core.doctor.RemedyExecutor
import com.vpnproxy.core.doctor.RemedySafety
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.service.VpnProxyService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

data class DoctorUiState(
    val isRunning: Boolean = false,
    val results: List<DiagnosticResult> = emptyList(),
    val report: DoctorReport? = null,
    val message: String? = null,
    val vpnActive: Boolean = VpnProxyService.isRunning.value,
    val coreTransitioning: Boolean = VpnProxyService.isCoreTransitioning.value,
    /** The user allowed the isolated core probes for this run. */
    val consentGranted: Boolean = false,
    /** A `REQUIRES_CONFIRM` remedy waiting for the dialog. */
    val pendingConfirm: Remedy? = null,
    /** Outcome line of the last pressed remedy; fixed text only, never an engine message. */
    val remedyMessage: String? = null,
    /** True while a remedy side effect is running, so the buttons stay disabled. */
    val remedyBusy: Boolean = false,
    /** Navigation remedy the screen must open; cleared through [DoctorViewModel.consumeDelegated]. */
    val delegated: Remedy? = null,
    /** Last successfully performed remedy: the screen offers a recheck after it. */
    val performed: Remedy? = null
)

/**
 * Runs the connection Doctor from the UI.
 *
 * The ViewModel only assembles the [DoctorContext] and streams results. All verdicts and
 * remedy rules live in [ConnectionDoctor], so they stay testable without Android. Journalling
 * is aggregate-only: step summaries may describe the profile and must not be logged.
 */
@HiltViewModel
class DoctorViewModel @Inject constructor(
    private val doctor: ConnectionDoctor,
    private val remedyExecutor: RemedyExecutor,
    private val logCollector: LogCollector
) : ViewModel() {

    private companion object {
        const val TAG = "DoctorViewModel"
        const val BUSY_TEXT = "Проверка уже идёт."
        const val ERROR_TEXT = "Не удалось выполнить проверку. Попробуйте ещё раз."
        const val REMEDY_ERROR_TEXT = "Не удалось выполнить действие. Попробуйте ещё раз."
    }

    private val _state = MutableStateFlow(DoctorUiState())
    val state: StateFlow<DoctorUiState> = _state.asStateFlow()

    private var runJob: Job? = null

    /** Guards against an old coroutine's `finally` clearing the flag of a newer run. */
    private val runGeneration = AtomicInteger(0)

    init {
        // The screen must show the live tunnel state, not the state at composition time.
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

    /**
     * @param withConsent true only after the user explicitly allowed the VPN_DOWN probes.
     * They start an isolated core, so consent is required even when the tunnel is already off.
     */
    fun run(withConsent: Boolean) {
        if (tryClaimRun() == null) {
            _state.update { it.copy(message = BUSY_TEXT) }
            return
        }
        val generation = runGeneration.incrementAndGet()
        runJob = viewModelScope.launch {
            try {
                val tunnelUp = VpnProxyService.isRunning.value
                val context = DoctorContext(
                    tunnelUp = tunnelUp,
                    coreTransitioning = VpnProxyService.isCoreTransitioning.value,
                    // Stopping the tunnel is the user's action; the Doctor never does it here.
                    isolatedCoreConsent = withConsent && !tunnelUp
                )
                _state.update { it.copy(consentGranted = withConsent) }
                val report = doctor.diagnose(context) { result ->
                    _state.update { current -> current.copy(results = current.results + result) }
                }
                // Aggregate only: probe summaries may carry profile details.
                logCollector.info(
                    TAG,
                    "DOCTOR steps=${report.results.size} " +
                        "ok=${report.count(DiagnosticStatus.OK)} " +
                        "warn=${report.count(DiagnosticStatus.WARN)} " +
                        "fail=${report.count(DiagnosticStatus.FAIL)} " +
                        "skip=${report.count(DiagnosticStatus.SKIP)}"
                )
                _state.update { it.copy(report = report, message = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logCollector.warn(TAG, "DOCTOR failed error=${error.javaClass.simpleName}")
                _state.update { it.copy(message = ERROR_TEXT) }
            } finally {
                if (generation == runGeneration.get()) {
                    _state.update { it.copy(isRunning = false) }
                }
            }
        }
    }

    /** Stops the current run; [run] can be called again immediately. */
    fun cancel() {
        runJob?.cancel()
        runJob = null
        // Invalidate the cancelled coroutine's `finally`: a cancel that lands before the
        // coroutine starts never reaches it.
        runGeneration.incrementAndGet()
        _state.update { it.copy(isRunning = false) }
    }

    /**
     * Entry point for every remedy button.
     *
     * `REQUIRES_CONFIRM` never executes here: the state carries [DoctorUiState.pendingConfirm]
     * and the screen shows the dialog. `SAFE_AUTO` runs immediately, because the press itself
     * is the user's explicit action.
     */
    fun onRemedyPressed(remedy: Remedy) {
        if (remedy.safety == RemedySafety.REQUIRES_CONFIRM) {
            _state.update { it.copy(pendingConfirm = remedy, remedyMessage = null) }
            return
        }
        execute(remedy, confirmed = false)
    }

    /** The user accepted the dialog for the pending remedy. */
    fun onRemedyConfirmed() {
        val remedy = _state.value.pendingConfirm ?: return
        _state.update { it.copy(pendingConfirm = null) }
        execute(remedy, confirmed = true)
    }

    fun onRemedyDismissed() {
        _state.update { it.copy(pendingConfirm = null) }
    }

    /** The screen opened [DoctorUiState.delegated]; the state must not re-open it. */
    fun consumeDelegated() {
        _state.update { it.copy(delegated = null) }
    }

    fun clearRemedyMessage() {
        _state.update { it.copy(remedyMessage = null, performed = null) }
    }

    private fun execute(remedy: Remedy, confirmed: Boolean) {
        // The executor also answers BUSY; this guard keeps the UI from queueing coroutines
        // while a side effect is already running.
        if (_state.value.remedyBusy) return
        _state.update { it.copy(remedyBusy = true, remedyMessage = null, performed = null) }
        viewModelScope.launch {
            try {
                val outcome = remedyExecutor.execute(remedy, confirmed)
                when (outcome.execution) {
                    RemedyExecution.DELEGATED_TO_UI ->
                        _state.update { it.copy(delegated = remedy) }
                    RemedyExecution.NEEDS_CONFIRM ->
                        _state.update { it.copy(pendingConfirm = remedy) }
                    RemedyExecution.PERFORMED ->
                        logCollector.info(TAG, "REMEDY performed=${remedy.javaClass.simpleName}")
                    else ->
                        logCollector.info(TAG, "REMEDY result=${outcome.execution}")
                }
                val performed = if (outcome.execution == RemedyExecution.PERFORMED) remedy else null
                _state.update { it.copy(remedyMessage = outcome.message, performed = performed) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Only the class is logged: the message may carry an address or a URL.
                logCollector.warn(TAG, "REMEDY failed error=${error.javaClass.simpleName}")
                _state.update { it.copy(remedyMessage = REMEDY_ERROR_TEXT) }
            } finally {
                _state.update { it.copy(remedyBusy = false) }
            }
        }
    }

    fun reset() {
        _state.update {
            it.copy(
                results = emptyList(),
                report = null,
                message = null,
                consentGranted = false,
                pendingConfirm = null,
                remedyMessage = null,
                performed = null,
                delegated = null
            )
        }
    }

    /**
     * Atomically flips [DoctorUiState.isRunning]. Two taps in the same frame cannot both win,
     * so the second one reports "already running" instead of silently doing nothing.
     */
    private fun tryClaimRun(): DoctorUiState? {
        while (true) {
            val current = _state.value
            if (current.isRunning) return null
            val next = current.copy(
                isRunning = true,
                results = emptyList(),
                report = null,
                message = null
            )
            if (_state.compareAndSet(current, next)) return next
        }
    }
}
