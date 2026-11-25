package com.vpnproxy.core.doctor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemedyExecutorTest {

    private class FakeActions(
        var refresh: RemedyActionResult = RemedyActionResult.Done,
        var restart: RemedyActionResult = RemedyActionResult.Done,
        var switch: RemedyActionResult = RemedyActionResult.Done,
        var reimport: RemedyActionResult = RemedyActionResult.Done,
        var throwOnRestart: Exception? = null
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
    fun confirmActionWithoutConsentChangesNothing() = runTest {
        val actions = FakeActions()
        val outcome = RemedyExecutor(actions).execute(Remedy.SwitchServer, confirmed = false)

        assertEquals(RemedyExecution.NEEDS_CONFIRM, outcome.execution)
        assertEquals(0, actions.switchCalls)
    }

    @Test
    fun confirmedActionRuns() = runTest {
        val actions = FakeActions()
        val outcome = RemedyExecutor(actions).execute(Remedy.RestartCore, confirmed = true)

        assertEquals(RemedyExecution.PERFORMED, outcome.execution)
        assertEquals(1, actions.restartCalls)
    }

    @Test
    fun navigationRemediesAreDelegatedToTheScreen() = runTest {
        val actions = FakeActions()
        val executor = RemedyExecutor(actions)

        listOf(Remedy.Recheck, Remedy.OpenSettings(SettingsSection.DNS)).forEach { remedy ->
            assertEquals(RemedyExecution.DELEGATED_TO_UI, executor.execute(remedy).execution)
        }
        // DNS and TLS settings are opened, never changed silently.
        assertEquals(RemedyExecution.DELEGATED_TO_UI, executor.execute(Remedy.ChangeDns, confirmed = true).execution)
        assertEquals(RemedyExecution.DELEGATED_TO_UI, executor.execute(Remedy.EnableTlsFragment, confirmed = true).execution)

        assertEquals(0, actions.refreshCalls + actions.restartCalls + actions.switchCalls + actions.reimportCalls)
    }

    @Test
    fun unsupportedAndFailedResultsAreReportedHonestly() = runTest {
        val unsupported = FakeActions(reimport = RemedyActionResult.Unsupported)
        assertEquals(
            RemedyExecution.UNSUPPORTED,
            RemedyExecutor(unsupported).execute(Remedy.ReimportProfile, confirmed = true).execution
        )

        val failed = FakeActions(switch = RemedyActionResult.Failed("нет свободных серверов"))
        val outcome = RemedyExecutor(failed).execute(Remedy.SwitchServer, confirmed = true)
        assertEquals(RemedyExecution.FAILED, outcome.execution)
        assertTrue(outcome.message.contains("нет свободных серверов"))
    }

    @Test
    fun unexpectedExceptionBecomesFailedWithoutLeakingIt() = runTest {
        val actions = FakeActions(throwOnRestart = IllegalStateException("secret https://example.com/sub"))
        val outcome = RemedyExecutor(actions).execute(Remedy.RestartCore, confirmed = true)

        assertEquals(RemedyExecution.FAILED, outcome.execution)
        assertTrue(!outcome.message.contains("example.com"))
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
}
