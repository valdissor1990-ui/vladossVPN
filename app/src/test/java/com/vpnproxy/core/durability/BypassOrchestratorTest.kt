package com.vpnproxy.core.durability

import com.vpnproxy.core.singbox.DurabilityOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BypassOrchestratorTest {

    @Test
    fun walksNormalToHardToMaximumThenStops() {
        val orchestrator = BypassOrchestrator()
        var current = DurabilityOptions()

        val hard = orchestrator.nextProfile(current)
        assertEquals(BypassProfile.HARD_NETWORK, hard)
        current = hard!!.applyTo(current)

        val maximum = orchestrator.nextProfile(current)
        assertEquals(BypassProfile.MAXIMUM_STEALTH, maximum)
        current = maximum!!.applyTo(current)

        assertNull(orchestrator.nextProfile(current))
        assertEquals(2, orchestrator.attemptsUsed())
    }

    @Test
    fun budgetMatchesTheRealisticLadder() {
        val orchestrator = BypassOrchestrator()
        var current = DurabilityOptions()

        repeat(BypassOrchestrator.MAX_ATTEMPTS) {
            val next = orchestrator.nextProfile(current) ?: error("unexpected exhausted ladder")
            current = next.applyTo(current)
        }

        assertNull(orchestrator.nextProfile(current))
        assertEquals(BypassOrchestrator.MAX_ATTEMPTS, orchestrator.attemptsUsed())
    }

    @Test
    fun successfulConnectionResetsTheBudget() {
        val orchestrator = BypassOrchestrator()
        var current = DurabilityOptions()
        repeat(BypassOrchestrator.MAX_ATTEMPTS) {
            current = orchestrator.nextProfile(current)!!.applyTo(current)
        }
        assertNull(orchestrator.nextProfile(current))

        orchestrator.onConnected()

        assertEquals(0, orchestrator.attemptsUsed())
        assertEquals(BypassProfile.HARD_NETWORK, orchestrator.nextProfile(DurabilityOptions()))
    }

    @Test
    fun manualConnectResetsTheBudget() {
        val orchestrator = BypassOrchestrator()
        orchestrator.nextProfile(DurabilityOptions())
        orchestrator.nextProfile(BypassProfile.HARD_NETWORK.applyTo(DurabilityOptions()))

        orchestrator.resetBudget()

        assertEquals(0, orchestrator.attemptsUsed())
        assertEquals(BypassProfile.HARD_NETWORK, orchestrator.nextProfile(DurabilityOptions()))
    }

    @Test
    fun customConfigurationIsNeverOverwritten() {
        val orchestrator = BypassOrchestrator()
        val custom = DurabilityOptions(tlsFragmentEnabled = true, utlsFingerprint = "safari")

        assertNull(orchestrator.nextProfile(custom))
        assertEquals(0, orchestrator.attemptsUsed())
    }
}
