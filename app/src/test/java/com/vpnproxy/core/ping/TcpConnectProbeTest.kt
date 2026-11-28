package com.vpnproxy.core.ping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TcpConnectProbeTest {
    @Test
    fun bestOfKeepsTheFastestSample() {
        assertEquals(38L, TcpConnectProbe.bestOf(listOf(120L, 38L, 77L)))
        assertNull(TcpConnectProbe.bestOf(emptyList()))
    }

    @Test
    fun measureReturnsFastestSuccessfulAttempt() {
        val samples = ArrayDeque(listOf(90L, 42L))
        val result = TcpConnectProbe.measure(
            host = "proxy.example.org",
            port = 443,
            attempts = 2,
            connector = { _, _, _ -> samples.removeFirst() }
        )

        assertEquals(42L, result)
    }

    @Test
    fun measureReturnsNullWhenEveryAttemptFails() {
        var attempts = 0
        val result = TcpConnectProbe.measure(
            host = "proxy.example.org",
            port = 443,
            attempts = 3,
            connector = { _, _, _ ->
                attempts++
                throw java.net.SocketTimeoutException("timeout")
            }
        )

        assertNull(result)
        assertEquals("every attempt must be tried", 3, attempts)
    }

    @Test
    fun measureRecoversWhenALaterAttemptSucceeds() {
        var attempts = 0
        val result = TcpConnectProbe.measure(
            host = "proxy.example.org",
            port = 443,
            attempts = 2,
            connector = { _, _, _ ->
                attempts++
                if (attempts == 1) throw java.net.ConnectException("refused")
                55L
            }
        )

        assertEquals(55L, result)
    }

    @Test
    fun measureIgnoresInvalidEndpoint() {
        var called = false
        val connector: TcpConnectProbe.Connector = { _, _, _ ->
            called = true
            10L
        }

        assertNull(TcpConnectProbe.measure(host = "", port = 443, connector = connector))
        assertNull(TcpConnectProbe.measure(host = "proxy.example.org", port = 0, connector = connector))
        assertNull(TcpConnectProbe.measure(host = "proxy.example.org", port = 70000, connector = connector))
        assertNull(TcpConnectProbe.measure(host = "proxy.example.org", port = 443, attempts = 0, connector = connector))
        assertEquals("invalid endpoints must not open a socket", false, called)
    }

    @Test
    fun interruptStopsFurtherAttempts() {
        var attempts = 0
        val result = TcpConnectProbe.measure(
            host = "proxy.example.org",
            port = 443,
            attempts = 5,
            connector = { _, _, _ ->
                attempts++
                throw InterruptedException("cancelled")
            }
        )

        assertNull(result)
        assertEquals("no attempt after an interrupt", 1, attempts)
        // The interrupted flag is restored for the caller; clear it so the test thread stays usable.
        Thread.interrupted()
    }

    @Test
    fun probeClassifiesFastRefusalAsBlockedPort() {
        val result = TcpConnectProbe.probe(
            host = "blocked.example.org",
            port = 443,
            attempts = 2,
            connector = { _, _, _ -> throw java.net.ConnectException("Connection refused") }
        )

        assertEquals(TcpConnectOutcome.REFUSED, result.outcome)
        assertNull("a refusal has no handshake latency", result.latencyMs)
    }

    @Test
    fun probeClassifiesSilentEndpointAsTimeout() {
        val result = TcpConnectProbe.probe(
            host = "silent.example.org",
            port = 443,
            attempts = 2,
            connector = { _, _, _ -> throw java.net.SocketTimeoutException("timeout") }
        )

        assertEquals(TcpConnectOutcome.TIMEOUT, result.outcome)
    }

    @Test
    fun probeClassifiesUnroutableHost() {
        val result = TcpConnectProbe.probe(
            host = "no-route.example.org",
            port = 443,
            attempts = 1,
            connector = { _, _, _ -> throw java.net.ConnectException("Network is unreachable") }
        )

        assertEquals(TcpConnectOutcome.UNREACHABLE, result.outcome)
    }

    @Test
    fun probeReportsSuccessWithTheFastestSample() {
        val samples = ArrayDeque(listOf(90L, 42L))
        val result = TcpConnectProbe.probe(
            host = "proxy.example.org",
            port = 443,
            attempts = 2,
            connector = { _, _, _ -> samples.removeFirst() }
        )

        assertEquals(TcpConnectOutcome.CONNECTED, result.outcome)
        assertEquals(42L, result.latencyMs)
    }

    @Test
    fun probeRejectsInvalidEndpoint() {
        val result = TcpConnectProbe.probe(host = "", port = 443)
        assertEquals(TcpConnectOutcome.ERROR, result.outcome)
    }
}