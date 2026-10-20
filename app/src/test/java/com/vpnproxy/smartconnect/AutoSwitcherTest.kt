package com.vpnproxy.smartconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSwitcherTest {

    @Test
    fun test_noSwitchIfNetworkIsBad() {
        val direct = ProbeResult(latencyMs = Long.MAX_VALUE, success = false)
        val vpn = ProbeResult(latencyMs = 900, success = true)

        assertEquals(NetworkFaultKind.NETWORK_DEGRADATION, classifyProbeFault(direct, vpn))
        assertFalse(
            shouldSwitchAfterEvidence(
                serverConfirmations = 2,
                networkConfirmations = 2,
                directPathHealthy = false
            )
        )
    }

    @Test
    fun test_switchIfServerIsBad() {
        val direct = ProbeResult(latencyMs = 35, success = true)
        val vpn = ProbeResult(latencyMs = 900, success = true)

        assertEquals(NetworkFaultKind.SERVER_DEGRADATION, classifyProbeFault(direct, vpn))
        assertFalse(shouldSwitchAfterEvidence(1, 0, directPathHealthy = true))
        assertTrue(shouldSwitchAfterEvidence(2, 0, directPathHealthy = true))
    }
}
