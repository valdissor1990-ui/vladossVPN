package com.vpnproxy.core.singbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LifecycleGenerationGateTest {
    @Test
    fun newerRequestMakesOlderStartStale() {
        val gate = LifecycleGenerationGate()

        val first = gate.next()
        assertTrue(gate.isCurrent(first))

        val second = gate.next()
        assertEquals(first + 1, second)
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun stopRequestCanInvalidatePendingStart() {
        val gate = LifecycleGenerationGate()
        val startGeneration = gate.next()

        val stopGeneration = gate.next()

        assertTrue(stopGeneration > startGeneration)
        assertFalse(gate.isCurrent(startGeneration))
        assertTrue(gate.isCurrent(stopGeneration))
    }
}
