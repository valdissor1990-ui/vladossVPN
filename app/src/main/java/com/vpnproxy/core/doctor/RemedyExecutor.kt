package com.vpnproxy.core.doctor

import kotlinx.coroutines.sync.Mutex

/**
 * Side effects the Doctor is allowed to trigger.
 *
 * The Doctor core stays Android-free: the app layer implements this port with the real
 * repositories and the connection coordinator. Every method must be safe to cancel.
 */
interface RemedyActions {
    /** Re-fetch subscription bodies through the existing repository code. */
    suspend fun refreshSubscription(): RemedyActionResult

    /** Restart the VPN core through the coordinator, keeping the selected profile. */
    suspend fun restartCore(): RemedyActionResult

    /** Ask Smart Connect for the next healthy profile. */
    suspend fun switchServer(): RemedyActionResult

    /** Re-import the active profile from its source. */
    suspend fun reimportProfile(): RemedyActionResult
}

/**
 * Fixed, secret-free failure codes.
 *
 * The port deliberately does not accept a free-form string: engine and repository errors
 * routinely contain a subscription URL with a token, a server address or a profile name.
 * The app layer maps its own exception to one of these codes, and the code is the only thing
 * that can reach the UI or a copied report.
 */
enum class RemedyFailure(val label: String) {
    NETWORK("нет сети"),
    TIMEOUT("истекло время ожидания"),
    REJECTED("запрос отклонён"),
    BAD_STATE("неподходящее состояние подключения"),
    UNKNOWN("неизвестная ошибка")
}

/** Result of one side effect: the Doctor never needs the raw exception text. */
sealed class RemedyActionResult {
    data object Done : RemedyActionResult()
    data object Unsupported : RemedyActionResult()
    data class Failed(val code: RemedyFailure) : RemedyActionResult()
}

/** What happened when the user pressed a remedy button. */
enum class RemedyExecution {
    /** The action ran and reported success. */
    PERFORMED,

    /** A `REQUIRES_CONFIRM` action that was not confirmed: nothing was executed. */
    NEEDS_CONFIRM,

    /** A UI action (recheck, open settings): the screen handles it, not the executor. */
    DELEGATED_TO_UI,

    /** The action ran but failed. */
    FAILED,

    /** No safe implementation exists for this remedy yet. */
    UNSUPPORTED,

    /** Another remedy is already running: nothing was executed by this call. */
    BUSY,

    /** The action is unsafe right now (for example restart while the tunnel connects). */
    REFUSED
}

data class RemedyOutcome(
    val execution: RemedyExecution,
    val message: String
)

/**
 * Executes Doctor remedies under the safety rule of the program:
 *
 * - `SAFE_AUTO` actions may run from the report screen by an explicit button press;
 * - `REQUIRES_CONFIRM` actions run only with [confirmed] = true;
 * - navigation actions are delegated to the UI and have no side effect here;
 * - concurrent presses never start two side effects: the second one gets [RemedyExecution.BUSY];
 * - cancellation propagates and is never converted into a verdict.
 *
 * @param isRestartAllowed precondition for [Remedy.RestartCore]; the UI and the coordinator
 *   must pass a predicate that is false while the tunnel is connecting or verifying.
 * @param log receives a fixed line with the exception class only (never its message), so a
 *   real bug is diagnosable without leaking an address or a profile name.
 */
class RemedyExecutor(
    private val actions: RemedyActions,
    private val isRestartAllowed: () -> Boolean = { true },
    private val log: (String) -> Unit = {}
) {

    private val inFlight = Mutex()

    suspend fun execute(remedy: Remedy, confirmed: Boolean = false): RemedyOutcome {
        if (remedy.safety == RemedySafety.REQUIRES_CONFIRM && !confirmed) {
            return RemedyOutcome(
                RemedyExecution.NEEDS_CONFIRM,
                "Действие «${remedy.title}» требует подтверждения."
            )
        }
        return when (remedy) {
            Remedy.Recheck, is Remedy.OpenSettings ->
                RemedyOutcome(RemedyExecution.DELEGATED_TO_UI, remedy.title)

            // DNS and TLS fragmentation live in their own screens: the executor only routes
            // the user there, it never flips a setting behind their back. These remedies are
            // navigation, so they are SAFE_AUTO and never blocked by a confirmation dialog.
            Remedy.ChangeDns, Remedy.EnableTlsFragment ->
                RemedyOutcome(RemedyExecution.DELEGATED_TO_UI, remedy.title)

            Remedy.RefreshSubscription ->
                guarded(remedy, "обновление подписки") { actions.refreshSubscription() }
            Remedy.SwitchServer ->
                guarded(remedy, "смена сервера") { actions.switchServer() }
            Remedy.RestartCore -> {
                if (!isRestartAllowed()) {
                    RemedyOutcome(
                        RemedyExecution.REFUSED,
                        "Перезапуск ядра недоступен: туннель устанавливается."
                    )
                } else {
                    guarded(remedy, "перезапуск ядра") { actions.restartCore() }
                }
            }

            Remedy.ReimportProfile ->
                guarded(remedy, "повторный импорт профиля") { actions.reimportProfile() }
        }
    }

    /**
     * Single-flight wrapper. The mutex is held for the whole side effect, so a double tap
     * returns [RemedyExecution.BUSY] instead of starting two restarts or two subscription
     * refreshes that write to the same repository.
     */
    private suspend fun guarded(
        remedy: Remedy,
        label: String,
        action: suspend () -> RemedyActionResult
    ): RemedyOutcome {
        if (!inFlight.tryLock()) {
            return RemedyOutcome(
                RemedyExecution.BUSY,
                "Действие уже выполняется: «${remedy.title}»."
            )
        }
        return try {
            run(label, action)
        } finally {
            inFlight.unlock()
        }
    }

    private suspend fun run(
        label: String,
        action: suspend () -> RemedyActionResult
    ): RemedyOutcome = try {
        when (val result = action()) {
            RemedyActionResult.Done ->
                RemedyOutcome(RemedyExecution.PERFORMED, "Готово: $label.")
            RemedyActionResult.Unsupported ->
                RemedyOutcome(RemedyExecution.UNSUPPORTED, "Недоступно: $label.")
            is RemedyActionResult.Failed ->
                RemedyOutcome(RemedyExecution.FAILED, "Не удалось: $label (${result.code.label}).")
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // The class name is enough to triage the bug; the message may contain a URL or a
        // profile name and must never be logged or shown.
        log("remedy $label failed: ${error::class.simpleName}")
        // The side effect may have partially completed before throwing: say so instead of
        // claiming nothing happened, otherwise the user repeats it blindly.
        RemedyOutcome(RemedyExecution.FAILED, "Не удалось подтвердить результат: $label.")
    }
}
