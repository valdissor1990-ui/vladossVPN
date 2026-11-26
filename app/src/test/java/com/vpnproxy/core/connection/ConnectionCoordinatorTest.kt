package com.vpnproxy.core.connection

import com.vpnproxy.logging.LogCollector
import com.vpnproxy.smartconnect.ScoringEngine
import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
    fun connectRequested_doesNotStartConnectingUntilPreflightSelectsServer() {
        sendAndWait(ConnectionEvent.ConnectRequested("preflight", userInitiated = true)) {
            coordinator.activeTraceId == "preflight" && coordinator.state.value is ConnectionState.Disconnected
        }
        sendAndWait(ConnectionEvent.ServerSelected(sampleServer(), "preflight")) {
            coordinator.state.value is ConnectionState.Connecting &&
                coordinator.currentServerName() == "test-server"
        }
    }

    @Test
    fun serviceStopped_afterShowError_preservesErrorState() {
        sendAndWait(ConnectionEvent.ShowError("preflight failed")) {
            coordinator.state.value is ConnectionState.Error
        }
        coordinator.send(ConnectionEvent.ServiceStopped)
        Thread.sleep(150)
        assertEquals("preflight failed", (coordinator.state.value as ConnectionState.Error).message)
    }

    @Test
    fun showError_replacesChangedErrorAndDoesNotReemitIdenticalOne() {
        sendAndWait(ConnectionEvent.ShowError("first failure", retryable = true)) {
            (coordinator.state.value as? ConnectionState.Error)?.message == "first failure"
        }

        sendAndWait(ConnectionEvent.ShowError("second failure", retryable = false)) {
            (coordinator.state.value as? ConnectionState.Error)?.let {
                it.message == "second failure" && !it.retryable
            } == true
        }

        // An identical error must not produce a new state object: re-emitting it would let
        // the autopilot react to the same failure twice.
        val before = coordinator.state.value
        coordinator.send(ConnectionEvent.ShowError("second failure", retryable = false))
        Thread.sleep(150)
        assertSame(before, coordinator.state.value)
    }

    @Test
    fun fatalLock_rejectsServerSelectedWithoutManualConnect() {
        sendAndWait(ConnectionEvent.ServerSelected(sampleServer())) {
            coordinator.hasSelectedServer()
        }
        sendAndWait(ConnectionEvent.UserConnect) {
            coordinator.state.value is ConnectionState.Connecting
        }
        sendAndWait(ConnectionEvent.CoreError(VpnFailureReason.PermissionDenied)) {
            coordinator.isFatalErrorLocked && coordinator.state.value is ConnectionState.Error
        }

        coordinator.send(ConnectionEvent.ServerSelected(sampleServer()))
        Thread.sleep(150)

        assertTrue(coordinator.isFatalErrorLocked)
        assertTrue(coordinator.state.value is ConnectionState.Error)
    }

    @Test
    fun nonUserConnectRequested_doesNotClearFatalLock() {
        sendAndWait(ConnectionEvent.ServerSelected(sampleServer())) {
            coordinator.hasSelectedServer()
        }
        sendAndWait(ConnectionEvent.UserConnect) {
            coordinator.state.value is ConnectionState.Connecting
        }
        sendAndWait(ConnectionEvent.CoreError(VpnFailureReason.PermissionDenied)) {
            coordinator.isFatalErrorLocked && coordinator.state.value is ConnectionState.Error
        }
        val traceBefore = coordinator.activeTraceId

        coordinator.send(ConnectionEvent.ConnectRequested("autopilot", userInitiated = false))
        // Barrier: UserDisconnect is processed after the ignored ConnectRequested, does not
        // clear the fatal lock, and gives the test a deterministic ordering signal instead of
        // sleeping and hoping the negative event was processed.
        sendAndWait(ConnectionEvent.UserDisconnect) {
            coordinator.state.value is ConnectionState.Disconnecting
        }

        assertTrue(coordinator.isFatalErrorLocked)
        assertEquals(traceBefore, coordinator.activeTraceId)
    }

    @Test
    fun userConnectRequested_clearsFatalLock() {
        sendAndWait(ConnectionEvent.ServerSelected(sampleServer())) {
            coordinator.hasSelectedServer()
        }
        sendAndWait(ConnectionEvent.UserConnect) {
            coordinator.state.value is ConnectionState.Connecting
        }
        sendAndWait(ConnectionEvent.CoreError(VpnFailureReason.PermissionDenied)) {
            coordinator.isFatalErrorLocked
        }

        sendAndWait(ConnectionEvent.ConnectRequested("manual", userInitiated = true)) {
            !coordinator.isFatalErrorLocked
        }

        assertEquals("manual", coordinator.activeTraceId)
    }

    @Test
    fun uncorrelatedTrafficValidationResult_cannotPromoteConnectionToConnected() {
        sendAndWait(ConnectionEvent.ConnectRequested("manual", userInitiated = true)) {
            coordinator.state.value is ConnectionState.Disconnected
        }
        sendAndWait(ConnectionEvent.ServerSelected(sampleServer())) {
            coordinator.hasSelectedServer() && coordinator.state.value is ConnectionState.Connecting
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
        fun candidate(name: String, score: Float, host: String) =
            current.copy(config = current.config.copy(name = name, address = host), score = score)
        val fastGlobal = candidate("fast-global", 0.95f, "fast.example.org")
        val slowerRussia = candidate("🇷🇺 Россия", 0.20f, "slow.example.org")

        sendAndWait(ConnectionEvent.ConnectRequested("manual", userInitiated = true)) {
            coordinator.activeTraceId == "manual" && coordinator.state.value is ConnectionState.Disconnected
        }
        coordinator.send(ConnectionEvent.SetFallbackCandidates(listOf(current, fastGlobal, slowerRussia)))
        Thread.sleep(100)
        sendAndWait(ConnectionEvent.ServerSelected(current, "manual")) {
            coordinator.hasSelectedServer() && coordinator.state.value is ConnectionState.Connecting
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
    fun manualSelectionMode_doesNotRotateToFallbackServer() {
        val current = sampleServer()
        val fallback = current.copy(
            config = current.config.copy(name = "must-not-select", address = "fallback.example.org"),
            score = 0.9f
        )

        coordinator.setManualSelectionMode(true)
        sendAndWait(ConnectionEvent.ConnectRequested("manual-selection", userInitiated = true)) {
            coordinator.activeTraceId == "manual-selection" && coordinator.state.value is ConnectionState.Disconnected
        }
        coordinator.send(ConnectionEvent.SetFallbackCandidates(listOf(fallback)))
        sendAndWait(ConnectionEvent.ServerSelected(current, "manual-selection")) {
            coordinator.hasSelectedServer() && coordinator.state.value is ConnectionState.Connecting
        }

        sendAndWait(ConnectionEvent.CoreError(VpnFailureReason.NetworkTimeout("test"))) {
            coordinator.state.value is ConnectionState.Reconnecting
        }

        assertEquals("test-server", coordinator.currentServerName())
        assertEquals(1, coordinator.currentRetryCount())
    }

    @Test
    fun providerScope_excludesFallbackFromOtherSubscription() {
        val current = sampleServer().copy(config = sampleServer().config.copy(providerTag = "subscription-A"))
        val allowed = current.copy(config = current.config.copy(name = "allowed", address = "allowed.example.org"))
        val outside = current.copy(config = current.config.copy(name = "outside", address = "outside.example.org", providerTag = "subscription-B"))

        coordinator.setProviderScope("subscription-A")
        sendAndWait(ConnectionEvent.ConnectRequested("scoped", userInitiated = true)) {
            coordinator.activeTraceId == "scoped" && coordinator.state.value is ConnectionState.Disconnected
        }
        coordinator.send(ConnectionEvent.SetFallbackCandidates(listOf(outside, allowed)))
        sendAndWait(ConnectionEvent.ServerSelected(current, "scoped")) {
            coordinator.hasSelectedServer() && coordinator.state.value is ConnectionState.Connecting
        }
        sendAndWait(ConnectionEvent.CoreError(VpnFailureReason.NetworkTimeout("test"))) {
            coordinator.currentServerName() == "allowed" && coordinator.state.value is ConnectionState.Reconnecting
        }
    }

    @Test
    fun providerScope_rejectsServerSelectedWithVisibleError() {
        val current = sampleServer().copy(config = sampleServer().config.copy(providerTag = "subscription-A"))
        val outside = current.copy(
            config = current.config.copy(
                name = "outside",
                address = "outside.example.org",
                providerTag = "subscription-B"
            )
        )

        coordinator.setProviderScope("subscription-A")
        sendAndWait(ConnectionEvent.ConnectRequested("scoped", userInitiated = true)) {
            coordinator.activeTraceId == "scoped" && coordinator.state.value is ConnectionState.Disconnected
        }
        sendAndWait(ConnectionEvent.ServerSelected(outside, "scoped")) {
            coordinator.state.value is ConnectionState.Error
        }

        val error = coordinator.state.value as ConnectionState.Error
        assertEquals(
            "Выбранный сервер вне выбранной подписки. Обновите список или выберите подписку заново.",
            error.message
        )
        assertFalse(error.retryable)
        assertFalse(coordinator.hasSelectedServer())
    }

    @Test
    fun outOfScopeSelectionKeepsLiveConnectedSession() {
        // A rejected selection must not tear down a working tunnel. The rule is pure so it is
        // covered without driving a real traffic validation.
        assertTrue(
            ConnectionCoordinator.keepsLiveSelectionOnOutOfScope(
                ConnectionState.Connected("test-server")
            )
        )
        listOf(
            ConnectionState.Disconnected,
            ConnectionState.Connecting,
            ConnectionState.Reconnecting,
            ConnectionState.Disconnecting,
            ConnectionState.Verifying("test-server"),
            ConnectionState.Error("boom", retryable = true)
        ).forEach { state ->
            assertFalse(
                "$state must not keep a live selection",
                ConnectionCoordinator.keepsLiveSelectionOnOutOfScope(state)
            )
        }
    }

    @Test
    fun staleOutOfScopeSelectionDoesNotCancelAnInFlightAttempt() {
        // A stale event from an older selection must be dropped in Verifying: otherwise it
        // clears the live attempt and shows a non-retryable error to the user.
        assertTrue(
            ConnectionCoordinator.shouldIgnoreStaleOutOfScope(
                ConnectionState.Verifying("test-server"),
                hasServerSelected = true,
                eventTraceId = "stale",
                activeTraceId = "current"
            )
        )
        // While still waiting for any server, the rejection must be reported instead of
        // silently timing out.
        assertFalse(
            ConnectionCoordinator.shouldIgnoreStaleOutOfScope(
                ConnectionState.Connecting,
                hasServerSelected = false,
                eventTraceId = "stale",
                activeTraceId = "current"
            )
        )
        // An Error state with a new trace id already has an active attempt: a stale
        // out-of-scope event must not cancel it either.
        assertTrue(
            ConnectionCoordinator.shouldIgnoreStaleOutOfScope(
                ConnectionState.Error("boom", retryable = true),
                hasServerSelected = false,
                eventTraceId = "stale",
                activeTraceId = "current"
            )
        )
        // No trace id or the current trace id: the event is current, so the scope verdict wins.
        assertFalse(
            ConnectionCoordinator.shouldIgnoreStaleOutOfScope(
                ConnectionState.Verifying("test-server"),
                hasServerSelected = true,
                eventTraceId = null,
                activeTraceId = "current"
            )
        )
        assertFalse(
            ConnectionCoordinator.shouldIgnoreStaleOutOfScope(
                ConnectionState.Verifying("test-server"),
                hasServerSelected = true,
                eventTraceId = "current",
                activeTraceId = "current"
            )
        )
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
            coordinator.activeTraceId == "manual" && !coordinator.isFatalErrorLocked && coordinator.state.value is ConnectionState.Error
        }
        sendAndWait(ConnectionEvent.ServerSelected(sampleServer())) {
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
            coordinator.state.value is ConnectionState.Error && !coordinator.isFatalErrorLocked
        }
        sendAndWait(ConnectionEvent.ServerSelected(sampleServer())) {
            coordinator.state.value is ConnectionState.Connecting
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
