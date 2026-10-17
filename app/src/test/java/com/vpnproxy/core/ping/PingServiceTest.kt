package com.vpnproxy.core.ping

import android.test.mock.MockContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PingServiceTest {
    @Test fun pingAll_respectsTimeout() = runBlocking {
        val service = PingService(MockContext())
        service.pingOneOverrideForTest = { target, _ ->
            delay(500L)
            TargetResult(target.id, null, false, "timeout", System.nanoTime() / 1_000_000L)
        }
        val target = PingTarget("timeout_test", "Slow probe", "unused.invalid", 65000, PingProtocol.TCP_CONNECT, 1.0, setOf(PingRegion.GLOBAL))
        val started = System.nanoTime()
        val result = service.pingAll(listOf(target), timeoutMs = 100L)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue(result.results.single().rttMs == null)
        assertTrue("expected bounded timeout, got ${elapsedMs}ms", elapsedMs < 1000L)
    }

    @Test fun pingAll_concurrencyRespected() = runBlocking {
        val service = PingService(MockContext())
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        service.pingOneOverrideForTest = { target, _ ->
            val now = active.incrementAndGet()
            maxActive.updateAndGet { old -> maxOf(old, now) }
            delay(80L)
            active.decrementAndGet()
            TargetResult(target.id, 10L, true, timestamp = System.nanoTime() / 1_000_000L)
        }
        val targets = (1..6).map { i ->
            PingTarget("test_$i", "Test $i", "unused.invalid", 443, PingProtocol.TCP_CONNECT, 1.0 / 6, setOf(PingRegion.GLOBAL))
        }
        val result = service.pingAll(targets, concurrency = 2, timeoutMs = 1000L)
        assertTrue(result.results.size == 6)
        assertTrue("max concurrency was ${maxActive.get()}", maxActive.get() <= 2)
        assertTrue("probes did not overlap", maxActive.get() == 2)
    }
}
