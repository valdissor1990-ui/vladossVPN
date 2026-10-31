package com.vpnproxy.smartconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepProbePortAllocationTest {

    @Test
    fun allocatesDistinctValidPortsForProbeBatch() {
        val ports = allocateProbePorts(128)

        assertEquals(128, ports.size)
        assertEquals(128, ports.toSet().size)
        assertTrue(ports.all { it in 1..65535 })
    }
}
