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
 * Does NOT depend on AutoSwitcher (Hilt-safe).
 *
 * Logging goes only through [LogCollector] so JVM unit tests do not need
 * android.util.Log mocks / Robolectric.
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val serverName: String) : ConnectionState()
    data object Disconnecting : ConnectionState()
    data object Reconnecting : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

sealed class ConnectionEvent {
    data object UserConnect : ConnectionEvent()
    data object UserDisconnect : ConnectionEvent()
    /** Notification STOP / UI disconnect request — always goes through the actor. */
    data object RequestDisconnect : ConnectionEvent()
    data class ServerSelected(val server: ScoringEngine.ScoredServer) : ConnectionEvent()
    data object NetworkChanged : ConnectionEvent()
    data class CoreError(val reason: VpnFailureReason) : ConnectionEvent()
    data object Timeout : ConnectionEvent()
    data class ServiceReady(val serverName: String) : ConnectionEvent()
    data object ServiceStopped : ConnectionEvent()
    data class ShowError(val message: String) : ConnectionEvent()
    data object ResetRetryCount : ConnectionEvent()
    /** System kill / force teardown — clears flags and forces Disconnected. */
    data class ForceDisconnected(val reason: String = "") : ConnectionEvent()
}

@Singleton
class ConnectionCoordinator @Inject constructor(
    private val logCollector: LogCollector
) {
    companion object {
        private const val TAG = "ConnectionCoordinator"
        private const val MAX_RETRIES = 3
        /** Connecting/Reconnecting hard timeout — prevents zombie Connecting. */
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

    /**
     * After a fatal error, block auto-reconnect (NetworkChanged / ServerSelected)
     * until the user explicitly sends UserConnect.
     */
    @Volatile
    var isFatalErrorLocked: Boolean = false
        private set

    private var connectingTimeoutJob: Job? = null

    init {
        scope.launch {
            for (event in events) {
                handle(event)
            }
        }
        logCollector.info(TAG, "ConnectionCoordinator actor started")
    }

    fun send(event: ConnectionEvent) {
        logCollector.info(TAG, "Actor received event: $event")
        val result = events.trySend(event)
        if (!result.isSuccess) {
            logCollector.error(TAG, "Failed to enqueue event: $event")
        }
    }

    fun currentServerName(): String? = pendingServerName

    fun hasSelectedServer(): Boolean = hasServerSelected

    /** Test / debug access to retry counter. */
    fun currentRetryCount(): Int = retryCount

    private fun setState(next: ConnectionState, reason: String = "") {
        val prev = _state.value
        if (prev == next) return
        _state.value = next
        val msg =
            "STATE: $prev -> $next | reason=$reason | retry=$retryCount | fatalLock=$isFatalErrorLocked"
        logCollector.info(TAG, msg)
        onStateChanged(next)
    }

    private fun onStateChanged(next: ConnectionState) {
        when (next) {
            is ConnectionState.Connecting,
            is ConnectionState.Reconnecting -> scheduleConnectingTimeout()
            else -> cancelConnectingTimeout()
        }
    }

    private fun scheduleConnectingTimeout() {
        connectingTimeoutJob?.cancel()
        connectingTimeoutJob = scope.launch {
            delay(CONNECTING_TIMEOUT_MS)
            val s = _state.value
            if (s is ConnectionState.Connecting || s is ConnectionState.Reconnecting) {
                logCollector.error(TAG, "Connecting timeout ${CONNECTING_TIMEOUT_MS}ms — FATAL lock + Error")
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

    private fun handle(event: ConnectionEvent) {
        val current = _state.value
        when (event) {
            is ConnectionEvent.UserConnect -> {
                isFatalErrorLocked = false
                retryCount = 0
                if (!hasServerSelected || pendingServerName == null) {
                    logCollector.info(TAG, "UserConnect deferred — waiting for ServerSelected")
                    return
                }
                when (current) {
                    is ConnectionState.Disconnected,
                    is ConnectionState.Disconnecting,
                    is ConnectionState.Error -> setState(ConnectionState.Connecting, reason = "UserConnect")
                    is ConnectionState.Connected,
                    is ConnectionState.Connecting,
                    is ConnectionState.Reconnecting -> {
                        logCollector.debug(TAG, "UserConnect ignored in state $current")
                    }
                }
            }

            is ConnectionEvent.UserDisconnect,
            is ConnectionEvent.RequestDisconnect -> {
                // Notification STOP and UI disconnect both go through the actor.
                val tag = if (event is ConnectionEvent.RequestDisconnect) "RequestDisconnect" else "UserDisconnect"
                when (current) {
                    is ConnectionState.Disconnected -> {
                        logCollector.debug(TAG, "$tag ignored — already Disconnected")
                    }
                    is ConnectionState.Disconnecting -> {
                        logCollector.debug(TAG, "$tag while Disconnecting — stay")
                    }
                    else -> {
                        retryCount = 0
                        setState(ConnectionState.Disconnecting, reason = tag)
                    }
                }
            }

            is ConnectionEvent.ServerSelected -> {
                pendingServerName = event.server.config.name
                hasServerSelected = true
                retryCount = 0
                when (current) {
                    is ConnectionState.Disconnected,
                    is ConnectionState.Error -> {
                        logCollector.debug(TAG, "ServerSelected while idle: ${event.server.config.name}")
                    }
                    is ConnectionState.Connecting -> {
                        logCollector.debug(TAG, "ServerSelected while Connecting: ${event.server.config.name}")
                    }
                    is ConnectionState.Connected,
                    is ConnectionState.Reconnecting -> {
                        if (!isFatalErrorLocked) {
                            setState(ConnectionState.Reconnecting, reason = "ServerSelected")
                        }
                    }
                    is ConnectionState.Disconnecting -> {
                        logCollector.debug(TAG, "ServerSelected ignored while Disconnecting")
                    }
                }
            }

            is ConnectionEvent.NetworkChanged -> {
                if (isFatalErrorLocked) {
                    logCollector.info(TAG, "NetworkChanged ignored — fatalErrorLocked")
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
                            logCollector.debug(TAG, "NetworkChanged ignored — no server selected")
                        }
                    }
                    is ConnectionState.Connecting,
                    is ConnectionState.Reconnecting -> {
                        logCollector.debug(TAG, "NetworkChanged while $current — stay in state")
                    }
                    is ConnectionState.Disconnecting -> {
                        logCollector.debug(TAG, "NetworkChanged ignored while Disconnecting")
                    }
                }
            }

            is ConnectionEvent.CoreError -> {
                logCollector.error(TAG, "CoreError: ${event.reason} (retry=$retryCount)")
                handleFailure(event.reason, current)
            }

            is ConnectionEvent.Timeout -> {
                logCollector.warn(TAG, "Timeout in state $current (retry=$retryCount)")
                handleFailure(VpnFailureReason.NetworkTimeout("Timeout"), current)
            }

            is ConnectionEvent.ShowError -> {
                if (current is ConnectionState.Error) {
                    logCollector.debug(TAG, "ShowError ignored — already in Error: ${current.message}")
                    return
                }
                retryCount = 0
                setState(ConnectionState.Error(event.message), reason = "ShowError")
            }

            is ConnectionEvent.ResetRetryCount -> {
                retryCount = 0
                logCollector.debug(TAG, "Retry count reset")
            }

            is ConnectionEvent.ForceDisconnected -> {
                val why = event.reason.ifBlank { "ForceDisconnected" }
                if (current is ConnectionState.Disconnected) {
                    // Still clear flags even if already Disconnected
                    retryCount = 0
                    isFatalErrorLocked = false
                    logCollector.debug(TAG, "ForceDisconnected ($why): already Disconnected, flags cleared")
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
                setState(ConnectionState.Connected(event.serverName), reason = "ServiceReady")
            }

            is ConnectionEvent.ServiceStopped -> {
                when (current) {
                    is ConnectionState.Disconnecting,
                    is ConnectionState.Disconnected ->
                        setState(ConnectionState.Disconnected, reason = "ServiceStopped")
                    is ConnectionState.Error -> {
                        logCollector.debug(TAG, "ServiceStopped while Error — keep Error banner")
                    }
                    is ConnectionState.Reconnecting,
                    is ConnectionState.Connecting -> {
                        logCollector.debug(TAG, "ServiceStopped during $current — awaiting restart")
                    }
                    is ConnectionState.Connected -> {
                        handleFailure(VpnFailureReason.CoreError("Unexpected service stop"), current)
                    }
                }
            }
        }
    }

    private fun handleFailure(reason: VpnFailureReason, current: ConnectionState) {
        if (!reason.isRetryable()) {
            applyFatal(reason)
            return
        }
        when (current) {
            is ConnectionState.Connecting,
            is ConnectionState.Reconnecting,
            is ConnectionState.Connected -> {
                retryCount++
                if (retryCount < MAX_RETRIES && hasServerSelected && !isFatalErrorLocked) {
                    logCollector.warn(TAG, "Retry $retryCount/$MAX_RETRIES after: $reason")
                    setState(ConnectionState.Reconnecting, reason = "retry:$reason")
                } else {
                    val msg = "Connection failed after $MAX_RETRIES attempts: ${reason.userMessage()}"
                    logCollector.error(TAG, msg)
                    retryCount = 0
                    if (current !is ConnectionState.Error && _state.value !is ConnectionState.Error) {
                        setState(ConnectionState.Error(msg), reason = "maxRetries:$reason")
                    }
                }
            }
            is ConnectionState.Error -> {
                logCollector.debug(TAG, "Failure ignored — already in Error")
            }
            else -> { }
        }
    }
}
