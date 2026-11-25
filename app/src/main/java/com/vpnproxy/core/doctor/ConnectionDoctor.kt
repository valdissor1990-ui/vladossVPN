package com.vpnproxy.core.doctor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs the probes. Implementations live in the data layer and are injected into the Doctor;
 * the orchestrator only sees this interface, so every rule below is unit-testable without
 * Android or a live core.
 */
interface DoctorProbes {
    suspend fun probe(step: DiagnosticStep, context: DoctorContext): StepOutcome
}

/**
 * Sequential Doctor run with a per-step timeout and a hard total budget.
 *
 * Rules from the plan:
 * - a step whose prerequisite is missing is `SKIP`, never `FAIL`;
 * - an unanswered or crashing probe is `WARN`, because the Doctor has no evidence;
 * - external cancellation propagates and is never converted into a verdict;
 * - `REQUIRES_CONFIRM` remedies are only suggested, never executed here.
 */
class ConnectionDoctor(
    private val probes: DoctorProbes,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val stepTimeoutMs: Long = STEP_TIMEOUT_MS,
    private val totalTimeoutMs: Long = TOTAL_TIMEOUT_MS,
    private val stepTimeoutOverrides: Map<DiagnosticStep, Long> = DEFAULT_OVERRIDES
) {
    companion object {
        const val STEP_TIMEOUT_MS = 8_000L
        const val TOTAL_TIMEOUT_MS = 45_000L
        /** Upper bounds for steps that do several network requests; the total budget still caps the run. */
        const val TRAFFIC_TIMEOUT_MS = 12_000L
        const val SERVICES_TIMEOUT_MS = 20_000L

        val DEFAULT_OVERRIDES: Map<DiagnosticStep, Long> = mapOf(
            DiagnosticStep.TRAFFIC to TRAFFIC_TIMEOUT_MS,
            DiagnosticStep.SERVICES to SERVICES_TIMEOUT_MS
        )
    }

    init {
        require(stepTimeoutMs > 0L) { "stepTimeoutMs must be positive" }
        require(totalTimeoutMs > 0L) { "totalTimeoutMs must be positive" }
        require(stepTimeoutOverrides.values.all { it > 0L }) {
            "step timeout overrides must be positive"
        }
    }

    suspend fun diagnose(
        context: DoctorContext,
        onResult: ((DiagnosticResult) -> Unit)? = null
    ): DoctorReport {
        val startedAt = clock()
        val results = ArrayList<DiagnosticResult>(DiagnosticStep.entries.size)

        for (step in DiagnosticStep.entries) {
            val remaining = totalTimeoutMs - (clock() - startedAt)
            val result = if (remaining <= 0L) {
                skipped(step, "Проверка не запускалась: общий таймаут.")
            } else {
                val reason = step.skipReason(context)
                if (reason != null) {
                    skipped(step, reason)
                } else {
                    runStep(step, context, remaining)
                }
            }
            results += result
            onResult?.invoke(result)
        }

        return DoctorReport(
            startedAtMs = startedAt,
            durationMs = (clock() - startedAt).coerceAtLeast(0L),
            results = results
        )
    }

    private suspend fun runStep(
        step: DiagnosticStep,
        context: DoctorContext,
        remainingMs: Long
    ): DiagnosticResult {
        val stepStartedAt = clock()
        val stepBudget = stepTimeoutOverrides[step] ?: stepTimeoutMs
        val outcome = try {
            withTimeoutOrNull(minOf(stepBudget, remainingMs)) {
                probes.probe(step, context)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val duration = (clock() - stepStartedAt).coerceAtLeast(0L)

        return if (outcome == null) {
            DiagnosticResult(
                step = step,
                status = DiagnosticStatus.WARN,
                summary = "Шаг не ответил вовремя. Это не приговор: проверьте вручную.",
                durationMs = duration,
                remedy = Remedy.Recheck
            )
        } else {
            DiagnosticResult(
                step = step,
                status = outcome.status,
                summary = outcome.summary,
                durationMs = duration,
                remedy = outcome.remedy,
                skipReason = outcome.skipReason
            )
        }
    }

    private fun skipped(step: DiagnosticStep, reason: String): DiagnosticResult =
        DiagnosticResult(
            step = step,
            status = DiagnosticStatus.SKIP,
            summary = reason,
            durationMs = 0L,
            skipReason = reason
        )
}
