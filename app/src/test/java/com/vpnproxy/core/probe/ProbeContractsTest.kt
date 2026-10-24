package com.vpnproxy.core.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProbeContractsTest {
    private fun result(
        outcome: String,
        at: Long,
        method: ProbeMethod = ProbeMethod.TCP_CONNECT,
        network: String = "wifi"
    ) = ProbeResult(
        id = "r$at$outcome",
        serverId = "server-1",
        configFingerprint = "cfg-1",
        networkFingerprint = network,
        method = method,
        stage = if (method == ProbeMethod.VPN_HTTP) ProbeStage.TUNNEL else ProbeStage.ENDPOINT,
        probeOutcome = outcome,
        observedAtMs = at
    )

    @Test fun tcpSuccessDoesNotCertifyVpnAvailability() {
        val status = StatusDeriver.deriveGroup(listOf(result("CONNECTED", 100)), 100, 1_000)
        assertEquals(SemanticStatus.UNVERIFIED, status.status)
        assertEquals("tcp_only", status.reason)
    }

    @Test fun freshDifferentSemanticOutcomesInSameGroupConflict() {
        val status = StatusDeriver.deriveGroup(
            listOf(
                result("TIMEOUT", 100, ProbeMethod.VPN_HTTP),
                result("CONNECTED", 200, ProbeMethod.VPN_HTTP)
            ),
            nowMs = 200,
            maxAgeMs = 1_000
        )
        assertEquals(SemanticStatus.UNVERIFIED, status.status)
        assertEquals("conflicting_results", status.reason)
    }

    @Test fun staleObservationDoesNotConflictWithFreshObservation() {
        val status = StatusDeriver.deriveGroup(
            listOf(
                result("TIMEOUT", 10, ProbeMethod.VPN_HTTP),
                result("CONNECTED", 100, ProbeMethod.VPN_HTTP)
            ),
            nowMs = 100,
            maxAgeMs = 20
        )
        assertEquals(SemanticStatus.AVAILABLE, status.status)
        assertEquals("probe_succeeded", status.reason)
    }

    @Test fun unknownOutcomeIsUnverifiedRatherThanIgnoredOrThrown() {
        val status = StatusDeriver.deriveGroup(listOf(result("FUTURE_OUTCOME", 100)), 100, 1_000)
        assertEquals(SemanticStatus.UNVERIFIED, status.status)
        assertEquals("unknown_outcome", status.reason)
    }

    @Test fun subscriptionRefreshAndEngineRestartDoNotChangeApplicabilityKey() {
        val first = ProbeContext("s", "cfg", "net", "sub-v1", "engine-1")
        val second = ProbeContext("s", "cfg", "net", "sub-v2", "engine-2")
        assertEquals(first.contextKey(), second.contextKey())
    }

    @Test fun configOrNetworkChangeChangesApplicabilityKey() {
        val base = ProbeContext("s", "cfg", "net")
        assertNotEquals(base.contextKey(), ProbeContext("s", "cfg2", "net").contextKey())
        assertNotEquals(base.contextKey(), ProbeContext("s", "cfg", "net2").contextKey())
    }

    @Test fun backoffUsesFailureWindowAndIgnoresCancellation() {
        val history = listOf(
            AttemptHistory("1", "s", "cfg", 100, 110, AttemptOutcome.FAILED),
            AttemptHistory("2", "s", "cfg", 120, 130, AttemptOutcome.CANCELLED),
            AttemptHistory("3", "s", "cfg", 140, 150, AttemptOutcome.FAILED)
        )
        assertNull(BackoffPolicy.nextAttemptAt(history, 200, 1_000, 3, 500))
        assertEquals(650L, BackoffPolicy.nextAttemptAt(history, 200, 1_000, 2, 500))
    }
}
