package com.vpnproxy.core.doctor

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

/** Result of one side effect: the Doctor never needs the raw exception text. */
sealed class RemedyActionResult {
    data object Done : RemedyActionResult()
    data object Unsupported : RemedyActionResult()

    /** [reason] must be a fixed, secret-free phrase. */
    data class Failed(val reason: String) : RemedyActionResult()
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
    UNSUPPORTED
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
 * - cancellation propagates and is never converted into a verdict.
 */
class RemedyExecutor(private val actions: RemedyActions) {

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
            // the user there, it never flips a setting behind their back.
            Remedy.ChangeDns, Remedy.EnableTlsFragment ->
                RemedyOutcome(RemedyExecution.DELEGATED_TO_UI, remedy.title)

            Remedy.RefreshSubscription -> run("обновление подписки") { actions.refreshSubscription() }
            Remedy.SwitchServer -> run("смена сервера") { actions.switchServer() }
            Remedy.RestartCore -> run("перезапуск ядра") { actions.restartCore() }
            Remedy.ReimportProfile -> run("повторный импорт профиля") { actions.reimportProfile() }
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
                RemedyOutcome(RemedyExecution.FAILED, "Не удалось: $label. ${result.reason}")
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        RemedyOutcome(RemedyExecution.FAILED, "Не удалось: $label.")
    }
}
