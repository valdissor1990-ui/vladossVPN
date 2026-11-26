package com.vpnproxy.core.doctor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemedyExecutorTest {

    private class FakeActions(
        var refresh: RemedyActionResult = RemedyActionResult.Done,
        var restart: RemedyActionResult = RemedyActionResult.Done,
        var switch: RemedyActionResult = RemedyActionResult.Done,
        var reimport: RemedyActionResult = RemedyActionResult.Done,
        var throwOnRestart: Exception? = null,
        var restartGate: CompletableDeferred<Unit>? = null
    ) : RemedyActions {
        var refreshCalls = 0
        var restartCalls = 0
        var switchCalls = 0
        var reimportCalls = 0

        override suspend fun refreshSubscription(): RemedyActionResult {
            refreshCalls++
            return refresh
        }

        override suspend fun restartCore(): RemedyActionResult {
            restartCalls++
            restartGate?.await()
            throwOnRestart?.let { throw it }
            return restart
        }

        override suspend fun switchServer(): RemedyActionResult {
            switchCalls++
            return switch
        }

        override suspend fun reimportProfile(): RemedyActionResult {
            reimportCalls++
            return reimport
        }
    }

    @Test
    fun safeActionRunsWithoutConfirmation() = runTest {
        val actions = FakeActions()
        val outcome = RemedyExecutor(actions).execute(Remedy.RefreshSubscription)

        assertEquals(RemedyExecution.PERFORMED, outcome.execution)
        assertEquals(1, actions.refreshCalls)
    }

    @Test
    fun everyConfirmRemedyChangesNothingWithoutConsent() = runTest {
        // Runs the gate for every REQUIRES_CONFIRM remedy, not just SwitchServer.
        val confirmRemedies = listOf(Remedy.SwitchServer, Remedy.ReimportProfile, Remedy.RestartCore)
        confirmRemedies.forEach { remedy ->
            val actions = FakeActions()
            val outcome = RemedyExecutor(actions).execute(remedy, confirmed = false)

            assertEquals("$remedy must ask first", RemedyExecution.NEEDS_CONFIRM, outcome.execution)
            assertEquals("$remedy must not run", 0, actions.totalCalls())
        }
    }

    @Test
    fun everyConfirmRemedyRunsWithConsent() = runTest {
        val remedies = listOf(Remedy.SwitchServer, Remedy.ReimportProfile, Remedy.RestartCore)
        remedies.forEach { remedy ->
            val actions = FakeActions()
            val outcome = RemedyExecutor(actions).execute(remedy, confirmed = true)

            assertEquals("$remedy must run", RemedyExecution.PERFORMED, outcome.execution)
            assertEquals("$remedy must call the port once", 1, actions.totalCalls())
        }
    }

    @Test
    fun navigationRemediesAreSafeAndDelegatedWithoutConfirmation() = runTest {
        val actions = FakeActions()
        val executor = RemedyExecutor(actions)

        // DNS and TLS fragmentation are pure navigation: they must not need a confirm dialog
        // and must not reach the port at all.
        val navigation = listOf(
            Remedy.Recheck,
            Remedy.OpenSettings(SettingsSection.DNS),
            Remedy.ChangeDns,
            Remedy.EnableTlsFragment
        )
        navigation.forEach { remedy ->
            assertEquals(
                "$remedy must be delegated",
                RemedyExecution.DELEGATED_TO_UI,
                executor.execute(remedy, confirmed = false).execution
            )
        }

        assertEquals(0, actions.totalCalls())
        assertEquals(RemedySafety.SAFE_AUTO, Remedy.ChangeDns.safety)
        assertEquals(RemedySafety.SAFE_AUTO, Remedy.EnableTlsFragment.safety)
    }

    @Test
    fun unsupportedAndFailedResultsAreReportedHonestly() = runTest {
        val unsupported = FakeActions(reimport = RemedyActionResult.Unsupported)
        assertEquals(
            RemedyExecution.UNSUPPORTED,
            RemedyExecutor(unsupported).execute(Remedy.ReimportProfile, confirmed = true).execution
        )

        val failed = FakeActions(switch = RemedyActionResult.Failed(RemedyFailure.REJECTED))
        val outcome = RemedyExecutor(failed).execute(Remedy.SwitchServer, confirmed = true)
        assertEquals(RemedyExecution.FAILED, outcome.execution)
        assertTrue(outcome.message.contains(RemedyFailure.REJECTED.label))
    }

    @Test
    fun restartIsRefusedWhileTheTunnelConnects() = runTest {
        val actions = FakeActions()
        val outcome = RemedyExecutor(actions, isRestartAllowed = { false })
            .execute(Remedy.RestartCore, confirmed = true)

        assertEquals(RemedyExecution.REFUSED, outcome.execution)
        assertEquals(0, actions.restartCalls)
    }

    @Test
    fun secondPressIsBusyWhileTheFirstActionRuns() = runTest {
        val gate = CompletableDeferred<Unit>()
        val actions = FakeActions(restartGate = gate)
        val executor = RemedyExecutor(actions)

        val first = launch { executor.execute(Remedy.RestartCore, confirmed = true) }
        runCurrent()
        assertEquals(1, actions.restartCalls)

        val second = executor.execute(Remedy.RestartCore, confirmed = true)
        assertEquals(RemedyExecution.BUSY, second.execution)
        assertEquals("a double tap must not restart twice", 1, actions.restartCalls)

        gate.complete(Unit)
        first.join()

        // After the first run finishes the lock is released and the next press works again.
        val third = executor.execute(Remedy.RestartCore, confirmed = true)
        assertEquals(RemedyExecution.PERFORMED, third.execution)
        assertEquals(2, actions.restartCalls)
    }

    @Test
    fun unexpectedExceptionBecomesFailedWithoutLeakingIt() = runTest {
        val logged = mutableListOf<String>()
        val actions = FakeActions(
            throwOnRestart = IllegalStateException("secret https://example.com/sub token=abc")
        )
        val outcome = RemedyExecutor(actions, log = { logged += it })
            .execute(Remedy.RestartCore, confirmed = true)

        assertEquals(RemedyExecution.FAILED, outcome.execution)
        // The message must not contain the exception text, a URL or a token.
        assertFalse(outcome.message.contains("example.com"))
        assertFalse(outcome.message.contains("abc"))
        // The log keeps only the exception class, enough to triage without a leak.
        assertTrue(logged.any { it.contains("IllegalStateException") })
        assertFalse(logged.any { it.contains("example.com") || it.contains("abc") })
    }

    @Test
    fun cancellationPropagates() {
        val actions = FakeActions(
            throwOnRestart = CancellationException("screen left")
        )
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking {
                RemedyExecutor(actions).execute(Remedy.RestartCore, confirmed = true)
            }
        }
    }

    private fun FakeActions.totalCalls() =
        refreshCalls + restartCalls + switchCalls + reimportCalls
}
