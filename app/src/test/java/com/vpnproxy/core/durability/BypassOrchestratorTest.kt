package com.vpnproxy.core.durability

import com.vpnproxy.core.singbox.DurabilityOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BypassOrchestratorTest {

    @Test
    fun walksNormalToHardToMaximumThenStops() {
        val orchestrator = BypassOrchestrator()

        assertEquals(BypassProfile.HARD_NETWORK, orchestrator.nextProfile(DurabilityOptions()))
        assertEquals(
            BypassProfile.MAXIMUM_STEALTH,
            orchestrator.nextProfile(BypassProfile.HARD_NETWORK.applyTo(DurabilityOptions()))
        )
        assertNull(orchestrator.nextProfile(BypassProfile.MAXIMUM_STEALTH.applyTo(DurabilityOptions())))
    }

    @Test
    fun budgetCapsRunawayRetries() {
        val orchestrator = BypassOrchestrator()
        repeat(BypassOrchestrator.MAX_ATTEMPTS) {
            assertEquals(BypassProfile.HARD_NETWORK, orchestrator.nextProfile(DurabilityOptions()))
        }

        assertNull(orchestrator.nextProfile(DurabilityOptions()))
        assertEquals(BypassOrchestrator.MAX_ATTEMPTS, orchestrator.attemptsUsed())
    }

    @Test
    fun successfulConnectionResetsTheBudget() {
        val orchestrator = BypassOrchestrator()
        repeat(BypassOrchestrator.MAX_ATTEMPTS) { orchestrator.nextProfile(DurabilityOptions()) }
        assertNull(orchestrator.nextProfile(DurabilityOptions()))

        orchestrator.onConnected()

        assertEquals(0, orchestrator.attemptsUsed())
        assertEquals(BypassProfile.HARD_NETWORK, orchestrator.nextProfile(DurabilityOptions()))
    }
}
