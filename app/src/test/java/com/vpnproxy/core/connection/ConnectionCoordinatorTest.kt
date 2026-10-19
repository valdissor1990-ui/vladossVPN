package com.vpnproxy.core.connection

import com.vpnproxy.logging.LogCollector
import com.vpnproxy.smartconnect.ScoringEngine
import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConnectionCoordinatorTest {

    private lateinit var coordinator: ConnectionCoordinator

    private fun sampleServer(): ScoringEngine.ScoredServer {
        val config = ParsedConfig(
            uuid = "11111111-1111-1111-1111-111111111111",
            address = "proxy.example.org",
            port = 443,
            transport = "tcp",
            security = "tls",
            sni = "sni.example.org",
            path = null,
            host = null,
            mode = null,
            fingerprint = null,
            publicKey = null,
            shortId = null,
            flow = null,
            name = "test-server"
        )
        return ScoringEngine.ScoredServer(
            config = config,
            latencyMs = 50,
            speedMbps = 10f,
            successRate = 1f,
            score = 1f
        )
    }

    private fun pump() {
        // Actor processes events on Default dispatcher — give it a tick
        Thread.sleep(80)
    }

    @Before
    fun setUp() {
        coordinator = ConnectionCoordinator(LogCollector())
        pump()
    }

    @Test
    fun fatalAnotherVpnActive_goesToError_noRetry_locks() {
        coordinator.send(ConnectionEvent.ServerSelected(sampleServer()))
        pump()
        coordinator.send(ConnectionEvent.UserConnect)
        pump()
        assertTrue(coordinator.state.value is ConnectionState.Connecting)

        coordinator.send(ConnectionEvent.CoreError(VpnFailureReason.AnotherVpnActive))
        pump()

        val state = coordinator.state.value
        assertTrue(state is ConnectionState.Error)
        assertEquals(
            "Другое VPN уже активно. Отключите его.",
            (state as ConnectionState.Error).message
        )
        assertEquals(0, coordinator.currentRetryCount())
        assertTrue(coordinator.isFatalErrorLocked)
    }

    @Test
    fun retryableTimeout_retriesThenError() {
        coordinator.send(ConnectionEvent.ServerSelected(sampleServer()))
        pump()
        coordinator.send(ConnectionEvent.UserConnect)
        pump()

        // 1st failure → Reconnecting (retry 1)
        coordinator.send(ConnectionEvent.Timeout)
        pump()
        assertTrue(
            "expected Reconnecting after 1st timeout, got ${coordinator.state.value}",
            coordinator.state.value is ConnectionState.Reconnecting
        )
        assertEquals(1, coordinator.currentRetryCount())

        // 2nd → still retry (retry 2)
        coordinator.send(ConnectionEvent.Timeout)
        pump()
        assertTrue(coordinator.state.value is ConnectionState.Reconnecting)
        assertEquals(2, coordinator.currentRetryCount())

        // 3rd → Error (MAX_RETRIES = 3, retryCount becomes 3 and fails)
        coordinator.send(ConnectionEvent.Timeout)
        pump()
        assertTrue(
            "expected Error after max retries, got ${coordinator.state.value}",
            coordinator.state.value is ConnectionState.Error
        )
        assertEquals(0, coordinator.currentRetryCount())
    }

    @Test
    fun fatalLock_blocksNetworkChangedAutoConnect() {
        coordinator.send(ConnectionEvent.ServerSelected(sampleServer()))
        pump()
        coordinator.send(ConnectionEvent.UserConnect)
        pump()
        coordinator.send(ConnectionEvent.CoreError(VpnFailureReason.AnotherVpnActive))
        pump()
        assertTrue(coordinator.isFatalErrorLocked)
        assertTrue(coordinator.state.value is ConnectionState.Error)

        coordinator.send(ConnectionEvent.NetworkChanged)
        pump()
        // Must stay in Error — not auto-Connecting
        assertTrue(coordinator.state.value is ConnectionState.Error)
        assertTrue(coordinator.isFatalErrorLocked)
    }

    @Test
    fun userConnect_clearsFatalLock() {
        coordinator.send(ConnectionEvent.ServerSelected(sampleServer()))
        pump()
        coordinator.send(ConnectionEvent.UserConnect)
        pump()
        coordinator.send(ConnectionEvent.CoreError(VpnFailureReason.PermissionDenied))
        pump()
        assertTrue(coordinator.isFatalErrorLocked)

        coordinator.send(ConnectionEvent.UserConnect)
        pump()
        assertFalse(coordinator.isFatalErrorLocked)
        assertTrue(coordinator.state.value is ConnectionState.Connecting)
    }

    @Test
    fun vpnFailureReason_isRetryableMatrix() {
        assertFalse(VpnFailureReason.AnotherVpnActive.isRetryable())
        assertFalse(VpnFailureReason.PermissionDenied.isRetryable())
        assertFalse(VpnFailureReason.TunConflict.isRetryable())
        assertTrue(VpnFailureReason.NetworkTimeout().isRetryable())
        assertTrue(VpnFailureReason.CoreError("temporary glitch").isRetryable())
        assertFalse(VpnFailureReason.CoreError("Another VPN is active").isRetryable())
    }
}
