package com.vpnproxy.core.doctor

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionDoctorTest {

    private class FakeProbes(
        private val handler: suspend (DiagnosticStep, DoctorContext) -> StepOutcome =
            { _, _ -> StepOutcome(DiagnosticStatus.OK, "ok") }
    ) : DoctorProbes {
        val calls = mutableListOf<DiagnosticStep>()

        override suspend fun probe(step: DiagnosticStep, context: DoctorContext): StepOutcome {
            calls += step
            return handler(step, context)
        }
    }

    @Test
    fun withoutTunnelTunnelStepsAreSkippedWithReason() = runTest {
        val probes = FakeProbes()
        val report = ConnectionDoctor(probes, clock = { testScheduler.currentTime })
            .diagnose(DoctorContext(tunnelUp = false))

        assertEquals(listOf(DiagnosticStep.SUBSCRIPTION, DiagnosticStep.PROFILE), probes.calls)
        assertEquals("Нужен активный VPN.", result(report, DiagnosticStep.DNS).summary)
        assertEquals(DiagnosticStatus.SKIP, result(report, DiagnosticStep.TCP).status)
        assertEquals("Нужно согласие на остановку VPN.", result(report, DiagnosticStep.TLS).summary)
        assertEquals(DiagnosticStatus.SKIP, result(report, DiagnosticStep.PROXY).status)
        assertEquals(DiagnosticStatus.SKIP, result(report, DiagnosticStep.SERVICES).status)
    }

    @Test
    fun activeTunnelSkipsIsolatedStepsInsteadOfFailing() = runTest {
        val probes = FakeProbes()
        val report = ConnectionDoctor(probes, clock = { testScheduler.currentTime })
            .diagnose(DoctorContext(tunnelUp = true, isolatedCoreConsent = true))

        assertEquals("Требуется остановка VPN.", result(report, DiagnosticStep.TLS).summary)
        assertEquals("Требуется остановка VPN.", result(report, DiagnosticStep.PROXY).summary)
        assertTrue(DiagnosticStep.TRAFFIC in probes.calls)
        assertTrue(DiagnosticStep.SERVICES in probes.calls)
        assertFalse(DiagnosticStep.TLS in probes.calls)
    }

    @Test
    fun consentWithoutTunnelRunsTlsAndProxyButSkipsTunnelSteps() = runTest {
        val probes = FakeProbes()
        val report = ConnectionDoctor(probes, clock = { testScheduler.currentTime })
            .diagnose(DoctorContext(tunnelUp = false, isolatedCoreConsent = true))

        assertTrue(DiagnosticStep.TLS in probes.calls)
        assertTrue(DiagnosticStep.PROXY in probes.calls)
        assertEquals(DiagnosticStatus.SKIP, result(report, DiagnosticStep.DNS).status)
        assertEquals(DiagnosticStatus.SKIP, result(report, DiagnosticStep.TRAFFIC).status)
    }

    @Test
    fun coreTransitionMarksTunnelDependentStepsAsSkipped() = runTest {
        val probes = FakeProbes()
        val report = ConnectionDoctor(probes, clock = { testScheduler.currentTime })
            .diagnose(DoctorContext(tunnelUp = true, coreTransitioning = true))

        assertEquals(
            "Ядро перезапускается, повторите через несколько секунд.",
            result(report, DiagnosticStep.DNS).summary
        )
        assertEquals(DiagnosticStatus.SKIP, result(report, DiagnosticStep.TUN).status)
        assertFalse(DiagnosticStep.DNS in probes.calls)
    }

    @Test
    fun unansweredProbeBecomesWarnWithRecheck() = runTest {
        val probes = FakeProbes { _, _ -> delay(60_000); StepOutcome(DiagnosticStatus.OK, "late") }
        val report = ConnectionDoctor(
            probes = probes,
            clock = { testScheduler.currentTime },
            stepTimeoutMs = 1_000L,
            totalTimeoutMs = 45_000L
        ).diagnose(DoctorContext(tunnelUp = false, isolatedCoreConsent = true))

        val tls = result(report, DiagnosticStep.TLS)
        assertEquals(DiagnosticStatus.WARN, tls.status)
        assertEquals(Remedy.Recheck, tls.remedy)
    }

    @Test
    fun crashingProbeBecomesWarnInsteadOfFail() = runTest {
        val probes = FakeProbes { step, _ ->
            if (step == DiagnosticStep.DNS) error("boom") else StepOutcome(DiagnosticStatus.OK, "ok")
        }
        val report = ConnectionDoctor(probes, clock = { testScheduler.currentTime })
            .diagnose(DoctorContext(tunnelUp = true))

        assertEquals(DiagnosticStatus.WARN, result(report, DiagnosticStep.DNS).status)
        assertEquals(DiagnosticStatus.OK, result(report, DiagnosticStep.TCP).status)
    }

    @Test
    fun reportExportOmitsProbeSummaries() = runTest {
        val probes = FakeProbes { _, _ ->
            StepOutcome(
                DiagnosticStatus.FAIL,
                "secret https://example.com/sub/uuid",
                Remedy.SwitchServer
            )
        }
        val report = ConnectionDoctor(probes, clock = { testScheduler.currentTime })
            .diagnose(DoctorContext(tunnelUp = true))

        val exported = report.exportText()
        assertFalse(exported.contains("example.com"))
        assertFalse(exported.contains("uuid"))
        assertTrue(exported.contains(DiagnosticStep.SUBSCRIPTION.title))
        assertTrue(exported.contains(Remedy.SwitchServer.title))
        assertTrue(exported.contains("REQUIRES_CONFIRM"))
    }

    @Test
    fun totalBudgetSkipsRemainingSteps() = runTest {
        val probes = FakeProbes { _, _ -> delay(10_000); StepOutcome(DiagnosticStatus.OK, "late") }
        val report = ConnectionDoctor(
            probes = probes,
            clock = { testScheduler.currentTime },
            stepTimeoutMs = 2_000L,
            totalTimeoutMs = 3_000L
        ).diagnose(DoctorContext(tunnelUp = false))

        assertEquals(DiagnosticStatus.WARN, report.results[0].status)
        assertEquals(DiagnosticStatus.WARN, report.results[1].status)
        assertEquals(DiagnosticStatus.SKIP, report.results[2].status)
        assertEquals("Проверка не запускалась: общий таймаут.", report.results[2].summary)
        assertEquals(7, report.count(DiagnosticStatus.SKIP))
    }

    @Test
    fun probeLevelSkipCarriesItsReasonIntoTheReport() = runTest {
        val reason = "TLS-рукопожатие не проверяется: в профиле нет TLS."
        val probes = FakeProbes { step, _ ->
            if (step == DiagnosticStep.TLS) {
                StepOutcome(DiagnosticStatus.SKIP, reason, skipReason = reason)
            } else {
                StepOutcome(DiagnosticStatus.OK, "ok")
            }
        }
        val report = ConnectionDoctor(probes, clock = { testScheduler.currentTime })
            .diagnose(DoctorContext(tunnelUp = false, isolatedCoreConsent = true))

        val tls = result(report, DiagnosticStep.TLS)
        assertEquals(DiagnosticStatus.SKIP, tls.status)
        assertEquals(reason, tls.skipReason)
        assertEquals(reason, tls.summary)
        // A copied report must explain the skip, not just print "ПРОПУЩЕНО".
        val exported = report.exportText()
        assertTrue(exported.contains(DiagnosticStep.TLS.title))
        assertTrue(exported.contains(reason))
    }

    @Test
    fun remediesSeparateSafeActionsFromConfirmation() {
        assertEquals(RemedySafety.SAFE_AUTO, Remedy.Recheck.safety)
        assertEquals(RemedySafety.SAFE_AUTO, Remedy.OpenSettings(SettingsSection.DNS).safety)
        assertEquals(RemedySafety.REQUIRES_CONFIRM, Remedy.SwitchServer.safety)
        assertEquals(RemedySafety.REQUIRES_CONFIRM, Remedy.RestartCore.safety)
        assertEquals(RemedySafety.REQUIRES_CONFIRM, Remedy.EnableTlsFragment.safety)
    }

    private fun result(report: DoctorReport, step: DiagnosticStep): DiagnosticResult =
        report.results.first { it.step == step }
}
