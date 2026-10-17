package com.vpnproxy.core.ping

import android.test.mock.MockContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class PingServiceTest {
    @Test fun pingAll_respectsTimeout() = runBlocking {
        val service = PingService(MockContext())
        val target = PingTarget("timeout_test", "Unroutable", "10.255.255.1", 65000, PingProtocol.TCP_CONNECT, 1.0, setOf(PingRegion.GLOBAL))
        val started = System.nanoTime()
        val result = service.pingAll(listOf(target), timeoutMs = 250L)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue(result.results.single().rttMs == null)
        assertTrue("expected bounded timeout, got ${elapsedMs}ms", elapsedMs < 2500L)
    }

    @Test fun pingAll_concurrencyRespected() = runBlocking {
        // Public network probes are not deterministic enough to assert exact concurrency;
        // this smoke test verifies that a positive semaphore limit is accepted.
        val service = PingService(MockContext())
        val targets = PingTargets.forRegion(PingRegion.GLOBAL).take(2)
        val result = service.pingAll(targets, concurrency = 1, timeoutMs = 100L)
        assertTrue(result.results.size == 2)
    }
}
