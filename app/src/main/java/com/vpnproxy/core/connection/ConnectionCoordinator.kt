package com.vpnproxy.core.connection

import com.vpnproxy.BuildConfig
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.util.DebugFlags
import com.vpnproxy.smartconnect.ScoringEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for VPN connection lifecycle.
 * Actor pattern: events are processed sequentially via a Channel.
 * Does NOT depend on AutoSwitcher (Hilt-safe — selection runs in UI / external job).
 *
 * Logging goes only through [LogCollector] so JVM unit tests do not need
 * android.util.Log mocks / Robolectric.
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val serverName: String) : ConnectionState()
    data class Verifying(val serverName: String) : ConnectionState()
    data object Disconnecting : ConnectionState()
    data object Reconnecting : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

sealed class ConnectionEvent {
    data object UserConnect : ConnectionEvent()
    data object UserDisconnect : ConnectionEvent()
    /** Notification STOP / UI disconnect — cancels selection + goes Disconnecting. */
    data object RequestDisconnect : ConnectionEvent()
    /** UI requested connect (permission granted); selection may still be in progress. */
    data class ConnectRequested(val traceId: String, val userInitiated: Boolean = false) : ConnectionEvent()
    data class ServerSelected(val server: ScoringEngine.ScoredServer, val traceId: String? = null, val manual: Boolean = false) : ConnectionEvent()
    data class SetFallbackCandidates(val servers: List<ScoringEngine.ScoredServer>) : ConnectionEvent()
    data object NetworkChanged : ConnectionEvent()
    data class CoreError(val reason: VpnFailureReason) : ConnectionEvent()
    data object Timeout : ConnectionEvent()
    data class ServiceReady(val serverName: String) : ConnectionEvent()
    data class TrafficValidationResult(val serverName: String, val success: Boolean) : ConnectionEvent()
    data object ServiceStopped : ConnectionEvent()
    data class ShowError(val message: String) : ConnectionEvent()
    data object ResetRetryCount : ConnectionEvent()
    data class ForceDisconnected(val reason: String = "") : ConnectionEvent()
}

@Singleton
class ConnectionCoordinator @Inject constructor(
    private val logCollector: LogCollector,
    private val trafficValidator: TrafficValidator
) {
    companion object {
        private const val TAG = "ConnectionCoordinator"
        private const val MAX_RETRIES = 3
        private const val MAX_TRAFFIC_VALIDATION_FAILURES = 3
        private const val FATAL_TRAFFIC_MESSAGE = "Сеть заблокирована или недоступна. Проверьте интернет."
        const val CONNECTING_TIMEOUT_MS = 60_000L
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val events = Channel<ConnectionEvent>(capacity = Channel.UNLIMITED)

    @Volatile
    private var pendingServerName: String? = null

    @Volatile
    private var hasServerSelected: Boolean = false

    @Volatile
    private var hasConnectedOnce: Boolean = false

    private var retryCount: Int = 0
    private var fallbackCandidates: List<ScoringEngine.ScoredServer> = emptyList()
    private var fallbackIndex: Int = -1
    private var fallbackAttempts: Int = 0
    private var trafficFailures: Int = 0
    private var activeScoredServer: ScoringEngine.ScoredServer? = null

    @Volatile
    var isFatalErrorLocked: Boolean = false
        private set

    @Volatile
    var activeTraceId: String? = null
        private set

    private var connectingTimeoutJob: Job? = null

    /** UI registers selection job cancel so RequestDisconnect can abort ranking. */
    @Volatile
    private var selectionCancel: (() -> Unit)? = null

    fun registerSelectionCancel(cancel: (() -> Unit)?) {
        selectionCancel = cancel
    }

    init {
        scope.launch {
            for (event in events) {
                handle(event)
            }
        }
        logCollector.info(TAG, "ConnectionCoordinator actor started")
    }

    fun send(event: ConnectionEvent) {
        val tid = activeTraceId
        logCollector.info(TAG, TraceId.format(TAG, "Actor received event: $event", tid).removePrefix("$TAG | "))
        val result = events.trySend(event)
        if (!result.isSuccess) {
            logCollector.error(TAG, "Failed to enqueue event: $event")
        }
    }

    fun currentServerName(): String? = pendingServerName

    fun currentSelectedServer(): ScoringEngine.ScoredServer? = activeScoredServer

    fun hasSelectedServer(): Boolean = hasServerSelected

    fun currentRetryCount(): Int = retryCount

    private fun logI(message: String) {
        logCollector.info(TAG, TraceId.format(TAG, message, activeTraceId).removePrefix("$TAG | "))
    }

    private fun logD(message: String) {
        logCollector.debug(TAG, TraceId.format(TAG, message, activeTraceId).removePrefix("$TAG | "))
    }

    private fun logW(message: String) {
        logCollector.warn(TAG, TraceId.format(TAG, message, activeTraceId).removePrefix("$TAG | "))
    }

    private fun logE(message: String) {
        logCollector.error(TAG, TraceId.format(TAG, message, activeTraceId).removePrefix("$TAG | "))
    }

    private fun setState(next: ConnectionState, reason: String = "") {
        val prev = _state.value
        if (prev == next) return
        _state.value = next
        if (next is ConnectionState.Connected) hasConnectedOnce = true
        logI("STATE: $prev -> $next | reason=$reason | retry=$retryCount | fatalLock=$isFatalErrorLocked")
        onStateChanged(next)
    }

    private fun onStateChanged(next: ConnectionState) {
        when (next) {
            is ConnectionState.Connecting,
            is ConnectionState.Reconnecting,
            is ConnectionState.Verifying -> scheduleConnectingTimeout()
            else -> cancelConnectingTimeout()
        }
    }

    private fun scheduleConnectingTimeout() {
        connectingTimeoutJob?.cancel()
        connectingTimeoutJob = scope.launch {
            delay(CONNECTING_TIMEOUT_MS)
            val s = _state.value
            if (s is ConnectionState.Connecting || s is ConnectionState.Reconnecting || s is ConnectionState.Verifying) {
                logE("Connecting timeout ${CONNECTING_TIMEOUT_MS}ms — FATAL lock + Error")
                applyFatal(VpnFailureReason.NetworkTimeout("connecting timeout ${CONNECTING_TIMEOUT_MS}ms"))
            }
        }
    }

    private fun cancelConnectingTimeout() {
        connectingTimeoutJob?.cancel()
        connectingTimeoutJob = null
    }

    private fun applyFatal(reason: VpnFailureReason) {
        retryCount = 0
        isFatalErrorLocked = true
        cancelSelectionJob()
        setState(ConnectionState.Error(reason.userMessage()), reason = reason.toString())
    }

    private fun cancelSelectionJob() {
        try {
            selectionCancel?.invoke()
        } catch (_: Throwable) {
        }
    }

    private fun handle(event: ConnectionEvent) {
        val current = _state.value
        when (event) {
            is ConnectionEvent.ConnectRequested -> {
                if (isFatalErrorLocked && !event.userInitiated) {
                    logW("ConnectRequested ignored — fatal lock is active; waiting for manual action")
                    return
                }
                if (event.userInitiated) {
                    isFatalErrorLocked = false
                    trafficFailures = 0
                    logI("Manual connect requested — fatal lock cleared")
                }
                activeTraceId = event.traceId
                TraceId.newId() // keep generator in sync if needed
                // Force the known id from the event
                // (TraceId object may have a newer id from UI; prefer event)
                retryCount = 0
                logI("ConnectRequested — enter Connecting (selection may still run)")
                when (current) {
                    is ConnectionState.Disconnected,
                    is ConnectionState.Disconnecting,
                    is ConnectionState.Error -> setState(ConnectionState.Connecting, reason = "ConnectRequested")
                    is ConnectionState.Connecting -> logD("ConnectRequested while already Connecting")
                    is ConnectionState.Connected,
                    is ConnectionState.Reconnecting,
                    is ConnectionState.Verifying -> logD("ConnectRequested ignored in $current")
                }
            }

            is ConnectionEvent.UserConnect -> {
                if (isFatalErrorLocked) {
                    logW("UserConnect ignored — fatal lock is active; waiting for manual ConnectRequested")
                    return
                }
                retryCount = 0
                if (!hasServerSelected || pendingServerName == null) {
                    logI("UserConnect deferred — waiting for ServerSelected")
                    return
                }
                when (current) {
                    is ConnectionState.Disconnected,
                    is ConnectionState.Disconnecting,
                    is ConnectionState.Error -> setState(ConnectionState.Connecting, reason = "UserConnect")
                    is ConnectionState.Connecting -> logD("UserConnect while Connecting — keep")
                    is ConnectionState.Connected,
                    is ConnectionState.Reconnecting,
                    is ConnectionState.Verifying -> logD("UserConnect ignored in state $current")
                }
            }

            is ConnectionEvent.UserDisconnect,
            is ConnectionEvent.RequestDisconnect -> {
                val tag = if (event is ConnectionEvent.RequestDisconnect) "RequestDisconnect" else "UserDisconnect"
                cancelSelectionJob()
                when (current) {
                    is ConnectionState.Disconnected -> {
                        logD("$tag ignored — already Disconnected")
                    }
                    is ConnectionState.Disconnecting -> {
                        logD("$tag while Disconnecting — stay")
                    }
                    else -> {
                        retryCount = 0
                        setState(ConnectionState.Disconnecting, reason = tag)
                    }
                }
            }

            is ConnectionEvent.SetFallbackCandidates -> {
                fallbackCandidates = event.servers
                    .distinctBy { it.config.connectionKey() }
                    .sortedBy { if (isRussianServer(it)) 0 else 1 }
                fallbackIndex = -1
                fallbackAttempts = 0
                trafficFailures = 0
                logI("Fallback candidates configured: ${fallbackCandidates.size}")
            }

            is ConnectionEvent.ServerSelected -> {
                if (BuildConfig.DEBUG && DebugFlags.DIAGNOSTIC_MODE &&
                    DebugFlags.FREEZE_AFTER_FIRST_CONNECTED && hasConnectedOnce && !event.manual
                ) {
                    logW("AutoSwitcher suppressed in DIAGNOSTIC_MODE: would switch to ${event.server.config.name} (reason=ConnectionCoordinator.ServerSelected state=$current)")
                    return
                }
                if (event.traceId != null && event.traceId != activeTraceId) {
                    // During initial selection, the actor may receive ConnectRequested and
                    // ServerSelected from adjacent coroutines. Accept the first selection only
                    // when there is no selected server yet; once selection exists, stale events
                    // must still be rejected to prevent an older request replacing a newer one.
                    if (current is ConnectionState.Connecting && !hasServerSelected && activeScoredServer == null) {
                        logW("Accepting initial ServerSelected with mismatched traceId because no server is selected yet")
                    } else {
                        logW("Ignoring stale ServerSelected for ${event.server.config.name}: eventTraceId=${event.traceId} activeTraceId=$activeTraceId")
                        return
                    }
                }
                pendingServerName = event.server.config.name
                activeScoredServer = event.server
                hasServerSelected = true
                retryCount = 0
                val selectedKey = event.server.config.connectionKey()
                val selectedWasFallback = fallbackCandidates.any {
                    it.config.connectionKey() == selectedKey
                }
                fallbackCandidates = fallbackCandidates.filterNot {
                    it.config.connectionKey() == selectedKey
                }
                fallbackIndex = -1
                if (!selectedWasFallback) fallbackAttempts = 0
                logI("ServerSelected: ${event.server.config.name}")
                when (current) {
                    is ConnectionState.Disconnected,
                    is ConnectionState.Error -> {
                        logD("ServerSelected while idle")
                    }
                    is ConnectionState.Connecting -> {
                        setState(ConnectionState.Reconnecting, reason = "ServerSelected while Connecting")
                    }
                    is ConnectionState.Connected,
                    is ConnectionState.Reconnecting,
                    is ConnectionState.Verifying -> {
                        if (!isFatalErrorLocked) {
                            setState(ConnectionState.Reconnecting, reason = "ServerSelected")
                        }
                    }
                    is ConnectionState.Disconnecting -> {
                        logD("ServerSelected ignored while Disconnecting")
                    }
                }
            }

            is ConnectionEvent.NetworkChanged -> {
                if (isFatalErrorLocked) {
                    logI("NetworkChanged ignored — fatalErrorLocked")
                    return
                }
                when (current) {
                    is ConnectionState.Connected -> {
                        if (hasServerSelected) setState(ConnectionState.Reconnecting, reason = "NetworkChanged")
                    }
                    is ConnectionState.Disconnected,
                    is ConnectionState.Error -> {
                        if (hasServerSelected) {
                            retryCount = 0
                            setState(ConnectionState.Connecting, reason = "NetworkChanged")
                        } else {
                            logD("NetworkChanged ignored — no server selected")
                        }
                    }
                    is ConnectionState.Connecting,
                    is ConnectionState.Reconnecting,
                    is ConnectionState.Verifying -> logD("NetworkChanged while $current — stay")
                    is ConnectionState.Disconnecting -> logD("NetworkChanged ignored while Disconnecting")
                }
            }

            is ConnectionEvent.CoreError -> {
                logE("CoreError: ${event.reason} (retry=$retryCount; fallbackAttempts=$fallbackAttempts; remainingCandidates=${fallbackCandidates.size})")
                if (!tryFallback(event.reason, current)) handleFailure(event.reason, current)
            }

            is ConnectionEvent.Timeout -> {
                logW("Timeout in state $current (retry=$retryCount)")
                handleFailure(VpnFailureReason.NetworkTimeout("Timeout"), current)
            }

            is ConnectionEvent.ShowError -> {
                if (current is ConnectionState.Error) {
                    logD("ShowError ignored — already in Error: ${current.message}")
                    return
                }
                retryCount = 0
                setState(ConnectionState.Error(event.message), reason = "ShowError")
            }

            is ConnectionEvent.ResetRetryCount -> {
                retryCount = 0
                logD("Retry count reset")
            }

            is ConnectionEvent.ForceDisconnected -> {
                val why = event.reason.ifBlank { "ForceDisconnected" }
                cancelSelectionJob()
                if (current is ConnectionState.Disconnected) {
                    retryCount = 0
                    logD("ForceDisconnected ($why): already Disconnected; fatal lock is preserved")
                    return
                }
                retryCount = 0
                setState(ConnectionState.Disconnected, reason = "ForceDisconnected:$why")
            }

            is ConnectionEvent.ServiceReady -> {
                if (isFatalErrorLocked) {
                    logW("ServiceReady ignored — fatal lock is active")
                    return
                }
                retryCount = 0
                pendingServerName = event.serverName
                hasServerSelected = true
                if (BuildConfig.DEBUG && DebugFlags.SKIP_TRAFFIC_VALIDATION) {
                    logW("Debug diagnostic mode: skipping connection-blocking traffic validation")
                    setState(ConnectionState.Connected(event.serverName), reason = "DebugSkipTrafficValidation")
                    return
                }
                setState(ConnectionState.Verifying(event.serverName), reason = "ServiceReady")
                scope.launch {
                    // The core-ready callback can precede the local mixed inbound being
                    // reachable. Retry the real traffic probe before declaring this server bad.
                    val retryDelays = listOf(300L, 700L, 1_200L)
                    var passed = false
                    for (attempt in 0..retryDelays.size) {
                        if (trafficValidator.checkTraffic(event.serverName)) {
                            passed = true
                            break
                        }
                        if (attempt < retryDelays.size) delay(retryDelays[attempt])
                    }
                    send(ConnectionEvent.TrafficValidationResult(event.serverName, passed))
                }
            }

            is ConnectionEvent.TrafficValidationResult -> {
                if (BuildConfig.DEBUG && DebugFlags.DISABLE_POST_CONNECT_TRAFFIC_FALLBACK) {
                    logW("Debug diagnostic mode: ignoring traffic validation result; no fallback or server blacklist")
                    if (_state.value is ConnectionState.Verifying && pendingServerName == event.serverName) {
                        setState(ConnectionState.Connected(event.serverName), reason = "DebugIgnoreTrafficValidation")
                    }
                    return
                }
                if (_state.value !is ConnectionState.Verifying || pendingServerName != event.serverName) {
                    logD("Ignoring stale traffic validation result for ${event.serverName}")
                    return
                }
                if (event.success) {
                    retryCount = 0
                    fallbackAttempts = 0
                    trafficFailures = 0
                    logI("Traffic validation passed for ${event.serverName}")
                    setState(ConnectionState.Connected(event.serverName), reason = "TrafficValidated")
                } else {
                    trafficFailures++
                    val failedConfig = activeScoredServer?.config
                    logW("Server does not pass traffic: ${event.serverName} (consecutiveFailures=$trafficFailures/$MAX_TRAFFIC_VALIDATION_FAILURES; remainingCandidates=${fallbackCandidates.size})")

                    // Subscription feeds often contain many fingerprint variants of the same
                    // endpoint. Three failed validations on one endpoint should move selection
                    // to a different endpoint, not enable a fatal lock while dozens remain.
                    if (trafficFailures >= MAX_TRAFFIC_VALIDATION_FAILURES && failedConfig != null) {
                        val before = fallbackCandidates.size
                        fallbackCandidates = fallbackCandidates.filterNot { candidate ->
                            val config = candidate.config
                            config.address.equals(failedConfig.address, ignoreCase = true) &&
                                config.port == failedConfig.port &&
                                config.transport.equals(failedConfig.transport, ignoreCase = true) &&
                                config.security.equals(failedConfig.security, ignoreCase = true)
                        }
                        val skipped = before - fallbackCandidates.size
                        logW("Traffic validation failed $trafficFailures times on endpoint ${failedConfig.address}:${failedConfig.port}; skipped $skipped equivalent endpoint variant(s)")
                        trafficFailures = 0
                    }

                    val next = fallbackCandidates.firstOrNull()
                    if (next == null) {
                        retryCount = 0
                        isFatalErrorLocked = true
                        cancelSelectionJob()
                        logE("Traffic validation exhausted all fallback endpoints; fatal lock enabled")
                        setState(
                            ConnectionState.Error(FATAL_TRAFFIC_MESSAGE),
                            reason = "trafficValidationExhausted"
                        )
                    } else {
                        logW("Traffic validation fallback -> ${next.config.name}")
                        send(ConnectionEvent.ServerSelected(next))
                    }
                }
            }

            is ConnectionEvent.ServiceStopped -> {
                when (current) {
                    is ConnectionState.Disconnecting,
                    is ConnectionState.Disconnected ->
                        setState(ConnectionState.Disconnected, reason = "ServiceStopped")
                    is ConnectionState.Error -> logD("ServiceStopped while Error — keep Error banner")
                    is ConnectionState.Reconnecting,
                    is ConnectionState.Connecting,
                    is ConnectionState.Verifying -> logD("ServiceStopped during $current — awaiting restart")
                    is ConnectionState.Connected ->
                        handleFailure(VpnFailureReason.CoreError("Unexpected service stop"), current)
                }
            }
        }
    }

    private fun isRussianServer(server: ScoringEngine.ScoredServer): Boolean {
        val config = server.config
        val text = "${config.name} ${config.address} ${config.sni.orEmpty()}".uppercase()
        return text.contains("🇷🇺") || Regex("(^|[^A-Z0-9])RU([^A-Z0-9]|$)").containsMatchIn(text) ||
            config.name.endsWith(".ru", ignoreCase = true) || config.sni.orEmpty().endsWith(".ru", ignoreCase = true)
    }

    private fun tryFallback(reason: VpnFailureReason, current: ConnectionState): Boolean {
        val configInvalid = reason is VpnFailureReason.CoreError &&
            (reason.message.contains("decode config", ignoreCase = true) ||
                reason.message.contains("unknown field", ignoreCase = true) ||
                reason.message.contains("invalid config", ignoreCase = true) ||
                reason.message.contains("invalid configuration", ignoreCase = true))
        if (configInvalid) {
            val failed = activeScoredServer?.config
            if (failed != null && failed.transport.equals("xhttp", ignoreCase = true)) {
                val failedKeys = xhttpExtraKeys(failed.extra)
                val before = fallbackCandidates.size
                fallbackCandidates = fallbackCandidates.filterNot { candidate ->
                    candidate.config.transport.equals("xhttp", ignoreCase = true) &&
                        xhttpExtraKeys(candidate.config.extra) == failedKeys
                }
                if (before != fallbackCandidates.size) {
                    logW("Removed ${before - fallbackCandidates.size} XHTTP fallback candidates with the same invalid config signature")
                }
            }
        }
        if (isFatalErrorLocked || !reason.canFallbackToNextServer() || fallbackCandidates.isEmpty()) return false
        if (current !is ConnectionState.Connecting &&
            current !is ConnectionState.Reconnecting &&
            current !is ConnectionState.Connected &&
            current !is ConnectionState.Verifying
        ) return false

        var nextIndex = (fallbackIndex + 1).coerceAtLeast(0)
        while (nextIndex < fallbackCandidates.size) {
            val candidate = fallbackCandidates[nextIndex]
            val active = activeScoredServer?.config
            if (active == null || candidate.config.connectionKey() != active.connectionKey()) {
                fallbackAttempts++
                fallbackCandidates = fallbackCandidates.filterNot {
                    it.config.connectionKey() == candidate.config.connectionKey()
                }
                fallbackIndex = -1
                activeScoredServer = candidate
                pendingServerName = candidate.config.name
                hasServerSelected = true
                retryCount = 0
                logW("Fallback attempt $fallbackAttempts -> ${candidate.config.name} after $reason")
                setState(ConnectionState.Reconnecting, reason = "fallback:$fallbackAttempts:${candidate.config.name}")
                return true
            }
            nextIndex++
        }
        logW("Fallback exhausted: attempts=$fallbackAttempts candidates=${fallbackCandidates.size}")
        return false
    }

    private fun xhttpExtraKeys(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return runCatching { Json.parseToJsonElement(raw).jsonObject.keys }.getOrDefault(emptySet())
    }

    private fun handleFailure(reason: VpnFailureReason, current: ConnectionState) {
        if (!reason.isRetryable()) {
            applyFatal(reason)
            return
        }
        when (current) {
            is ConnectionState.Connecting,
            is ConnectionState.Reconnecting,
            is ConnectionState.Connected,
            is ConnectionState.Verifying -> {
                retryCount++
                if (retryCount < MAX_RETRIES && hasServerSelected && !isFatalErrorLocked) {
                    logW("Retry $retryCount/$MAX_RETRIES after: $reason")
                    setState(ConnectionState.Reconnecting, reason = "retry:$reason")
                } else {
                    val msg = "Connection failed after $MAX_RETRIES attempts: ${reason.userMessage()}"
                    logE(msg)
                    retryCount = 0
                    if (current !is ConnectionState.Error && _state.value !is ConnectionState.Error) {
                        setState(ConnectionState.Error(msg), reason = "maxRetries:$reason")
                    }
                }
            }
            is ConnectionState.Error -> logD("Failure ignored — already in Error")
            else -> { }
        }
    }
}
