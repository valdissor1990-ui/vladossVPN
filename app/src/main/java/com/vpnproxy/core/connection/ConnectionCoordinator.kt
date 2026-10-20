package com.vpnproxy.core.connection

import com.vpnproxy.logging.LogCollector
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
    data class ConnectRequested(val traceId: String) : ConnectionEvent()
    data class ServerSelected(val server: ScoringEngine.ScoredServer) : ConnectionEvent()
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
        private const val MAX_FALLBACK_ATTEMPTS = 5
        const val CONNECTING_TIMEOUT_MS = 18_000L
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val events = Channel<ConnectionEvent>(capacity = Channel.UNLIMITED)

    @Volatile
    private var pendingServerName: String? = null

    @Volatile
    private var hasServerSelected: Boolean = false

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
                activeTraceId = event.traceId
                TraceId.newId() // keep generator in sync if needed
                // Force the known id from the event
                // (TraceId object may have a newer id from UI; prefer event)
                isFatalErrorLocked = false
                retryCount = 0
                logI("ConnectRequested — enter Connecting (selection may still run)")
                when (current) {
                    is ConnectionState.Disconnected,
                    is ConnectionState.Disconnecting,
                    is ConnectionState.Error -> setState(ConnectionState.Connecting, reason = "ConnectRequested")
                    is ConnectionState.Connecting -> logD("ConnectRequested while already Connecting")
                    is ConnectionState.Connected,
                    is ConnectionState.Reconnecting -> logD("ConnectRequested ignored in $current")
                }
            }

            is ConnectionEvent.UserConnect -> {
                isFatalErrorLocked = false
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
                    .distinctBy { "${it.config.address.lowercase()}:${it.config.port}:${it.config.uuid}" }
                    .sortedBy { if (isRussianServer(it)) 0 else 1 }
                fallbackIndex = -1
                fallbackAttempts = 0
                trafficFailures = 0
                logI("Fallback candidates configured: ${fallbackCandidates.size}")
            }

            is ConnectionEvent.ServerSelected -> {
                pendingServerName = event.server.config.name
                activeScoredServer = event.server
                hasServerSelected = true
                retryCount = 0
                val selectedKey = "${event.server.config.address.lowercase()}:${event.server.config.port}:${event.server.config.uuid}"
                val selectedWasFallback = fallbackCandidates.any {
                    "${it.config.address.lowercase()}:${it.config.port}:${it.config.uuid}" == selectedKey
                }
                fallbackCandidates = fallbackCandidates.filterNot {
                    "${it.config.address.lowercase()}:${it.config.port}:${it.config.uuid}" == selectedKey
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
                logE("CoreError: ${event.reason} (retry=$retryCount; fallback=$fallbackAttempts/$MAX_FALLBACK_ATTEMPTS)")
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
                    isFatalErrorLocked = false
                    logD("ForceDisconnected ($why): already Disconnected, flags cleared")
                    return
                }
                retryCount = 0
                isFatalErrorLocked = false
                setState(ConnectionState.Disconnected, reason = "ForceDisconnected:$why")
            }

            is ConnectionEvent.ServiceReady -> {
                retryCount = 0
                isFatalErrorLocked = false
                pendingServerName = event.serverName
                hasServerSelected = true
                setState(ConnectionState.Verifying(event.serverName), reason = "ServiceReady")
                scope.launch {
                    val passed = trafficValidator.checkTraffic()
                    send(ConnectionEvent.TrafficValidationResult(event.serverName, passed))
                }
            }

            is ConnectionEvent.TrafficValidationResult -> {
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
                    logW("Server does not pass traffic: ${event.serverName} (attempt $trafficFailures/$MAX_FALLBACK_ATTEMPTS)")
                    val next = fallbackCandidates.firstOrNull()
                    if (trafficFailures >= MAX_FALLBACK_ATTEMPTS || next == null) {
                        val message = "Все сервера недоступны"
                        logE("$message; traffic validation failed on ${event.serverName}")
                        isFatalErrorLocked = true
                        setState(ConnectionState.Error(message), reason = "trafficValidationExhausted")
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
        if (!reason.isRetryable() || fallbackAttempts >= MAX_FALLBACK_ATTEMPTS) return false
        if (current !is ConnectionState.Connecting &&
            current !is ConnectionState.Reconnecting &&
            current !is ConnectionState.Connected
        ) return false

        var nextIndex = (fallbackIndex + 1).coerceAtLeast(0)
        while (nextIndex < fallbackCandidates.size) {
            val candidate = fallbackCandidates[nextIndex]
            val active = activeScoredServer?.config
            if (active == null ||
                !candidate.config.address.equals(active.address, ignoreCase = true) ||
                candidate.config.port != active.port || candidate.config.uuid != active.uuid
            ) {
                fallbackIndex = nextIndex
                fallbackAttempts++
                activeScoredServer = candidate
                pendingServerName = candidate.config.name
                hasServerSelected = true
                retryCount = 0
                isFatalErrorLocked = false
                logW("Fallback attempt $fallbackAttempts/$MAX_FALLBACK_ATTEMPTS -> ${candidate.config.name} after $reason")
                setState(ConnectionState.Reconnecting, reason = "fallback:$fallbackAttempts:${candidate.config.name}")
                return true
            }
            nextIndex++
        }
        logW("Fallback exhausted: attempts=$fallbackAttempts candidates=${fallbackCandidates.size}")
        return false
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
