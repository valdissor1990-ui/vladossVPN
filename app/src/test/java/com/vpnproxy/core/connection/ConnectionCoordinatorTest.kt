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

    /** Wait until [predicate] is true or timeout. */
    private fun awaitUntil(timeoutMs: Long = 3000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(25)
        }
        throw AssertionError("Condition not met within ${timeoutMs}ms; state=${coordinator.state.value} retry=${coordinator.currentRetryCount()} fatal=${coordinator.isFatalErrorLocked}")
    }

    private fun sendAndWait(event: ConnectionEvent, predicate: () -> Boolean) {
        coordinator.send(event)
        awaitUntil { predicate() }
    }

    @Before
    fun setUp() {
        coordinator = ConnectionCoordinator(LogCollector(), TrafficValidator(LogCollector()), ScoringEngine(null))
        // actor loop starts asynchronously
        Thread.sleep(50)
    }

    @Test
    fun uncorrelatedTrafficValidationResult_cannotPromoteConnectionToConnected() {
        sendAndWait(ConnectionEvent.ServerSelected(sampleServer())) {
            coordinator.hasSelectedServer()
        }
        sendAndWait(ConnectionEvent.ConnectRequested("manual", userInitiated = true)) {
            coordinator.state.value is ConnectionState.Connecting
        }

        // A result without the current ServiceReady generation is stale/untrusted.
        coordinator.send(
            ConnectionEvent.TrafficValidationResult(
                serverName = "test-server",
                success = true,
                validationId = -1L
            )
        )
        Thread.sleep(150)

        assertFalse(coordinator.state.value is ConnectionState.Connected)
        assertTrue(coordinator.state.value is ConnectionState.Connecting)
    }

    @Test
    fun fallbackPreservesMeasuredRankingInsteadOfPrioritizingRegionName() {
        val current = sampleServer()
        fun candidate(name: String, score: Float) =
            current.copy(config = current.config.copy(name = name), score = score)
        val fastGlobal = candidate("fast-global", 0.95f)
        val slowerRussia = candidate("🇷🇺 Россия", 0.20f)

        sendAndWait(ConnectionEvent.ServerSelected(current)) {
            coordinator.hasSelectedServer()
        }
        coordinator.send(ConnectionEvent.SetFallbackCandidates(listOf(current, fastGlobal, slowerRussia)))
        Thread.sleep(100)
        sendAndWait(ConnectionEvent.ConnectRequested("manual", userInitiated = true)) {
            coordinator.state.value is ConnectionState.Connecting
        }
        sendAndWait(ConnectionEvent.CoreError(VpnFailureReason.NetworkTimeout("test"))) {
            coordinator.currentServerName() == "fast-global" &&
                coordinator.state.value is ConnectionState.Reconnecting
        }
        // A second failure must advance to the next untried profile, not cycle back.
        sendAndWait(ConnectionEvent.CoreError(VpnFailureReason.NetworkTimeout("test"))) {
            coordinator.currentServerName() == "🇷🇺 Россия" &&
                coordinator.state.value is ConnectionState.Reconnecting
        }
    }

    @Test
    fun fatalAnotherVpnActive_goesToError_noRetry_locks() {
        sendAndWait(ConnectionEvent.ServerSelected(sampleServer())) {
            coordinator.hasSelectedServer()
        }
        sendAndWait(ConnectionEvent.UserConnect) {
            coordinator.state.value is ConnectionState.Connecting
        }

        sendAndWait(ConnectionEvent.CoreError(VpnFailureReason.AnotherVpnActive)) {
            coordinator.state.value is ConnectionState.Error
        }

        val state = coordinator.state.value as ConnectionState.Error
        assertEquals("Другое VPN уже активно. Отключите его.", state.message)
        assertEquals(0, coordinator.currentRetryCount())
        assertTrue(coordinator.isFatalErrorLocked)
    }

    @Test
    fun fatalError_doesNotRetryAfterSubsequentTimeout() {
        sendAndWait(ConnectionEvent.ServerSelected(sampleServer())) {
            coordinator.hasSelectedServer()
        }
        sendAndWait(ConnectionEvent.UserConnect) {
            coordinator.state.value is ConnectionState.Connecting
        }
        sendAndWait(ConnectionEvent.CoreError(VpnFailureReason.PermissionDenied)) {
            coordinator.state.value is ConnectionState.Error && coordinator.isFatalErrorLocked
        }

        coordinator.send(ConnectionEvent.Timeout)
        Thread.sleep(150)
        assertTrue(coordinator.state.value is ConnectionState.Error)
        assertTrue(coordinator.isFatalErrorLocked)
        assertEquals(0, coordinator.currentRetryCount())
    }

    @Test
    fun retryableTimeout_retriesThenError() {
        sendAndWait(ConnectionEvent.ServerSelected(sampleServer())) {
            coordinator.hasSelectedServer()
        }
        sendAndWait(ConnectionEvent.UserConnect) {
            coordinator.state.value is ConnectionState.Connecting
        }

        sendAndWait(ConnectionEvent.Timeout) {
            coordinator.state.value is ConnectionState.Reconnecting &&
                coordinator.currentRetryCount() == 1
        }

        sendAndWait(ConnectionEvent.Timeout) {
            coordinator.state.value is ConnectionState.Reconnecting &&
                coordinator.currentRetryCount() == 2
        }

        sendAndWait(ConnectionEvent.Timeout) {
            coordinator.state.value is ConnectionState.Error
        }

        assertEquals(0, coordinator.currentRetryCount())
    }

    @Test
    fun fatalLock_blocksNetworkChangedAutoConnect() {
        sendAndWait(ConnectionEvent.ServerSelected(sampleServer())) {
            coordinator.hasSelectedServer()
        }
        sendAndWait(ConnectionEvent.UserConnect) {
            coordinator.state.value is ConnectionState.Connecting
        }
        sendAndWait(ConnectionEvent.CoreError(VpnFailureReason.AnotherVpnActive)) {
            coordinator.isFatalErrorLocked && coordinator.state.value is ConnectionState.Error
        }

        coordinator.send(ConnectionEvent.NetworkChanged)
        Thread.sleep(150)
        assertTrue(coordinator.state.value is ConnectionState.Error)
        assertTrue(coordinator.isFatalErrorLocked)
    }

    @Test
    fun automaticUserConnect_doesNotClearFatalLock() {
        sendAndWait(ConnectionEvent.ServerSelected(sampleServer())) {
            coordinator.hasSelectedServer()
        }
        sendAndWait(ConnectionEvent.UserConnect) {
            coordinator.state.value is ConnectionState.Connecting
        }
        sendAndWait(ConnectionEvent.CoreError(VpnFailureReason.PermissionDenied)) {
            coordinator.isFatalErrorLocked && coordinator.state.value is ConnectionState.Error
        }

        coordinator.send(ConnectionEvent.UserConnect)
        Thread.sleep(150)
        assertTrue(coordinator.isFatalErrorLocked)
        assertTrue(coordinator.state.value is ConnectionState.Error)

        sendAndWait(ConnectionEvent.ConnectRequested("manual", userInitiated = true)) {
            !coordinator.isFatalErrorLocked &&
                coordinator.state.value is ConnectionState.Connecting
        }
    }

    @Test
    fun fatalLock_blocksAutomaticConnectRequested_untilManualConnect() {
        sendAndWait(ConnectionEvent.ServerSelected(sampleServer())) {
            coordinator.hasSelectedServer()
        }
        sendAndWait(ConnectionEvent.UserConnect) {
            coordinator.state.value is ConnectionState.Connecting
        }
        sendAndWait(ConnectionEvent.CoreError(VpnFailureReason.PermissionDenied)) {
            coordinator.isFatalErrorLocked && coordinator.state.value is ConnectionState.Error
        }

        coordinator.send(ConnectionEvent.ConnectRequested("automatic"))
        Thread.sleep(150)
        assertTrue(coordinator.state.value is ConnectionState.Error)
        assertTrue(coordinator.isFatalErrorLocked)

        sendAndWait(ConnectionEvent.ConnectRequested("manual", userInitiated = true)) {
            coordinator.state.value is ConnectionState.Connecting && !coordinator.isFatalErrorLocked
        }
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
