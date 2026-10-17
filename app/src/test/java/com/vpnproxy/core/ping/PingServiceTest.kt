package com.vpnproxy.core.ping

import android.test.mock.MockContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PingServiceTest {
    @Test fun pingAll_respectsTimeout() = runBlocking {
        val fakeExecutor = PingExecutor { target, _ ->
            delay(500L)
            TargetResult(target.id, 500L, true, timestamp = System.nanoTime() / 1_000_000L)
        }
        val service = PingService(fakeExecutor, RegionDetector(MockContext()))
        val target = target("timeout_test")
        val started = System.nanoTime()
        val result = service.pingAll(listOf(target), timeoutMs = 100L)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertTrue("expected timeout < 250ms, got ${elapsedMs}ms", elapsedMs < 250L)
        assertTrue(result.results.isNotEmpty())
        assertTrue(result.results.all { !it.success })
    }

    @Test fun pingAll_concurrencyRespected() = runBlocking {
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val fakeExecutor = PingExecutor { target, _ ->
            val nowActive = active.incrementAndGet()
            maxActive.updateAndGet { old -> maxOf(old, nowActive) }
            try {
                delay(80L)
                TargetResult(target.id, 10L, true, timestamp = System.nanoTime() / 1_000_000L)
            } finally {
                active.decrementAndGet()
            }
        }
        val service = PingService(fakeExecutor, RegionDetector(MockContext()))
        val result = service.pingAll(
            (1..6).map { target("test_$it") },
            concurrency = 2,
            timeoutMs = 1000L
        )
        assertEquals(6, result.results.size)
        assertTrue("max concurrency was ${maxActive.get()}", maxActive.get() <= 2)
        assertEquals(2, maxActive.get())
    }

    private fun target(id: String) = PingTarget(
        id, id, "unused.invalid", 443, PingProtocol.TCP_CONNECT, 1.0,
        setOf(PingRegion.GLOBAL)
    )
}
