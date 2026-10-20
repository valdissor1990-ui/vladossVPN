package com.vpnproxy.smartconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSwitcherTest {

    @Test
    fun test_noSwitchIfNetworkIsBad() {
        val direct = ProbeResult(latencyMs = Long.MAX_VALUE, success = false)
        val vpn = ProbeResult(latencyMs = 100, success = true)

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
    @Test
    fun decisionEngine_tracksIndependentEvidenceAndCooldown() {
        val engine = SwitchDecisionEngine()

        assertEquals(1 to 0, engine.record(NetworkFaultKind.SERVER_DEGRADATION))
        assertEquals(2 to 0, engine.record(NetworkFaultKind.SERVER_DEGRADATION))
        assertEquals(0 to 1, engine.record(NetworkFaultKind.NETWORK_DEGRADATION))
        assertEquals(0 to 0, engine.record(NetworkFaultKind.NONE))

        assertTrue(engine.canAttemptSwitch(nowMs = 1_000L))
        assertFalse(engine.recordSuccessfulSwitch(nowMs = 1_000L))
        assertTrue(engine.isInCooldown(nowMs = 1_000L + AutoSwitcherConfig.switchCooldownMs - 1))
        assertFalse(engine.isInCooldown(nowMs = 1_000L + AutoSwitcherConfig.switchCooldownMs))
    }

    @Test
    fun decisionEngine_enforcesExistingSwitchWindowLimit() {
        val engine = SwitchDecisionEngine()
        assertFalse(engine.recordSuccessfulSwitch(nowMs = 1_000L))
        assertFalse(engine.recordSuccessfulSwitch(nowMs = 2_000L))
        assertTrue(engine.recordSuccessfulSwitch(nowMs = 3_000L))
        assertFalse(engine.canAttemptSwitch(nowMs = 4_000L))
        assertTrue(engine.canAttemptSwitch(nowMs = 1_000L + AutoSwitcherConfig.switchWindowMs + 1))
    }

}
