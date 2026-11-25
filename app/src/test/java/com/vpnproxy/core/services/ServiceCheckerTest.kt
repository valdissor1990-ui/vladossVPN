package com.vpnproxy.core.services

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceCheckerTest {

    private class FakeProbe(
        private val byId: Map<String, ServiceProbeResult>,
        private val throwFor: Set<String> = emptySet()
    ) : ServiceProbe {
        var calls = 0
            private set

        override suspend fun probe(target: ServiceTarget): ServiceProbeResult {
            calls++
            if (target.id in throwFor) throw IllegalStateException("boom")
            return byId[target.id] ?: ServiceProbeResult(reachable = false, latencyMs = 0L)
        }
    }

    private val targets = listOf(
        ServiceTarget("a", "A", ServiceCategory.VIDEO, "https://a.example/robots.txt"),
        ServiceTarget("b", "B", ServiceCategory.MESSENGER, "https://b.example/robots.txt")
    )

    @Test
    fun vpnOffReturnsVpnOffWithoutProbing() = runBlocking {
        val probe = FakeProbe(emptyMap())
        val checker = ServiceChecker(probe)

        val results = checker.checkAll(targets, vpnActive = false)

        assertEquals(2, results?.size)
        assertTrue(results!!.all { it.status == ServiceCheckStatus.VPN_OFF })
        assertEquals(0, probe.calls)
    }

    @Test
    fun transitionReturnsTransitionWithoutProbing() = runBlocking {
        val probe = FakeProbe(emptyMap())
        val checker = ServiceChecker(probe)

        val results = checker.checkAll(targets, vpnActive = true, coreTransitioning = true)

        assertTrue(results!!.all { it.status == ServiceCheckStatus.TRANSITION })
        assertEquals(0, probe.calls)
    }

    @Test
    fun httpErrorIsUnreachableAndNetworkErrorIsUnknown() = runBlocking {
        val probe = FakeProbe(
            mapOf(
                "a" to ServiceProbeResult(reachable = false, latencyMs = 12L, httpCode = 403, reason = "HTTP_403"),
                "b" to ServiceProbeResult(reachable = false, latencyMs = 30L, reason = "SocketTimeoutException")
            )
        )
        val checker = ServiceChecker(probe)

        val results = checker.checkAll(targets, vpnActive = true)!!.associateBy { it.serviceId }

        assertEquals(ServiceCheckStatus.UNREACHABLE, results.getValue("a").status)
        assertEquals(403, results.getValue("a").httpCode)
        assertEquals(ServiceCheckStatus.UNKNOWN, results.getValue("b").status)
        assertNull(results.getValue("b").httpCode)
    }

    @Test
    fun reachableKeepsLatencyAndDropsZero() = runBlocking {
        val probe = FakeProbe(
            mapOf(
                "a" to ServiceProbeResult(reachable = true, latencyMs = 145L, httpCode = 200),
                "b" to ServiceProbeResult(reachable = true, latencyMs = 0L, httpCode = 204)
            )
        )
        val checker = ServiceChecker(probe)

        val results = checker.checkAll(targets, vpnActive = true)!!.associateBy { it.serviceId }

        assertEquals(145L, results.getValue("a").latencyMs)
        assertNull(results.getValue("b").latencyMs)
        assertTrue(results.values.all { it.status == ServiceCheckStatus.REACHABLE })
    }

    @Test
    fun resultNeverCarriesEndpointUrl() = runBlocking {
        val probe = FakeProbe(mapOf("a" to ServiceProbeResult(reachable = true, latencyMs = 10L, httpCode = 200)))
        val checker = ServiceChecker(probe)

        val results = checker.checkAll(targets, vpnActive = true)!!

        results.forEach { result ->
            assertTrue(!result.serviceId.contains("http"))
            assertTrue(result.reason?.contains("example") != true)
        }
    }

    @Test
    fun redirectIsUnknownNotReachable() = runBlocking {
        val probe = FakeProbe(
            mapOf("a" to ServiceProbeResult(reachable = false, latencyMs = 8L, httpCode = 302, reason = "HTTP_302"))
        )
        val checker = ServiceChecker(probe)

        val results = checker.checkAll(targets, vpnActive = true)!!.associateBy { it.serviceId }

        assertEquals(ServiceCheckStatus.UNKNOWN, results.getValue("a").status)
        assertEquals(302, results.getValue("a").httpCode)
    }

    @Test
    fun probeExceptionBecomesUnknownAndDoesNotAbortTheSweep() = runBlocking {
        val probe = FakeProbe(
            mapOf("b" to ServiceProbeResult(reachable = true, latencyMs = 20L, httpCode = 200)),
            throwFor = setOf("a")
        )
        val checker = ServiceChecker(probe)

        val results = checker.checkAll(targets, vpnActive = true)!!.associateBy { it.serviceId }

        assertEquals(ServiceCheckStatus.UNKNOWN, results.getValue("a").status)
        assertEquals(ServiceCheckStatus.REACHABLE, results.getValue("b").status)
    }

    @Test
    fun cancellationReleasesTheRunLock() = runBlocking {
        var firstSweep = true
        val probe = object : ServiceProbe {
            override suspend fun probe(target: ServiceTarget): ServiceProbeResult {
                if (firstSweep) {
                    firstSweep = false
                    delay(10_000)
                }
                return ServiceProbeResult(reachable = true, latencyMs = 5L, httpCode = 200)
            }
        }
        val checker = ServiceChecker(probe)
        val job = launch { checker.checkAll(targets, vpnActive = true) }
        delay(50)
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)

        // The same checker must accept a new sweep: the lock was released in `finally`.
        val results = checker.checkAll(targets, vpnActive = true)
        assertEquals(2, results?.size)
    }

    @Test
    fun catalogueHasNoRussianEndpointsAndNoDuplicates() {
        val targets = ServiceCatalog.defaultTargets
        assertEquals(targets.size, targets.map { it.id }.distinct().size)
        assertTrue(targets.none { it.url.contains(".ru/") })
    }
}
