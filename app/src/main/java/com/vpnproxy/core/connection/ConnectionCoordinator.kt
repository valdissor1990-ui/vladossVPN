package com.vpnproxy.core.connection

import android.util.Log
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.smartconnect.ScoringEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
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
    data class ServerSelected(val server: ScoringEngine.ScoredServer) : ConnectionEvent()
    data object NetworkChanged : ConnectionEvent()
    data class CoreError(val reason: String) : ConnectionEvent()
    data object Timeout : ConnectionEvent()
    data class ServiceReady(val serverName: String) : ConnectionEvent()
    data object ServiceStopped : ConnectionEvent()
    data class ShowError(val message: String) : ConnectionEvent()
    data object ResetRetryCount : ConnectionEvent()
    data object ForceDisconnected : ConnectionEvent()
}

@Singleton
class ConnectionCoordinator @Inject constructor(
    private val logCollector: LogCollector
) {
    companion object {
        private const val TAG = "ConnectionCoordinator"
        private const val MAX_RETRIES = 3
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

    init {
        scope.launch {
            for (event in events) {
                handle(event)
            }
        }
        Log.d(TAG, "Actor started")
        logCollector.info(TAG, "ConnectionCoordinator actor started")
    }

    fun send(event: ConnectionEvent) {
        Log.d(TAG, "Actor received event: $event")
        logCollector.info(TAG, "Actor received event: $event")
        val result = events.trySend(event)
        if (!result.isSuccess) {
            Log.e(TAG, "Failed to enqueue event: $event")
            logCollector.error(TAG, "Failed to enqueue event: $event")
        }
    }

    fun currentServerName(): String? = pendingServerName

    fun hasSelectedServer(): Boolean = hasServerSelected

    private fun setState(next: ConnectionState) {
        val prev = _state.value
        if (prev == next) return
        _state.value = next
        Log.d(TAG, "Actor state changed to: $next (from $prev)")
        logCollector.info(TAG, "Actor state changed to: $next (from $prev)")
    }

    private fun handle(event: ConnectionEvent) {
        val current = _state.value
        when (event) {
            is ConnectionEvent.UserConnect -> {
                retryCount = 0
                if (!hasServerSelected || pendingServerName == null) {
                    Log.d(TAG, "UserConnect deferred — no server selected yet")
                    logCollector.info(TAG, "UserConnect deferred — waiting for ServerSelected")
                    return
                }
                when (current) {
                    is ConnectionState.Disconnected,
                    is ConnectionState.Disconnecting,
                    is ConnectionState.Error -> setState(ConnectionState.Connecting)
                    is ConnectionState.Connected,
                    is ConnectionState.Connecting,
                    is ConnectionState.Reconnecting -> {
                        Log.d(TAG, "UserConnect ignored in state $current")
                    }
                }
            }

            is ConnectionEvent.UserDisconnect -> {
                when (current) {
                    is ConnectionState.Disconnected -> { /* already down */ }
                    else -> {
                        retryCount = 0
                        setState(ConnectionState.Disconnecting)
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
                        Log.d(TAG, "ServerSelected while idle: ${event.server.config.name}")
                    }
                    is ConnectionState.Connecting -> {
                        Log.d(TAG, "ServerSelected while Connecting: ${event.server.config.name}")
                    }
                    is ConnectionState.Connected,
                    is ConnectionState.Reconnecting -> {
                        setState(ConnectionState.Reconnecting)
                    }
                    is ConnectionState.Disconnecting -> {
                        Log.d(TAG, "ServerSelected ignored while Disconnecting")
                    }
                }
            }

            is ConnectionEvent.NetworkChanged -> {
                when (current) {
                    is ConnectionState.Connected -> {
                        if (hasServerSelected) setState(ConnectionState.Reconnecting)
                    }
                    is ConnectionState.Disconnected,
                    is ConnectionState.Error -> {
                        if (hasServerSelected) {
                            retryCount = 0
                            setState(ConnectionState.Connecting)
                        } else {
                            Log.d(TAG, "NetworkChanged ignored — no server selected")
                        }
                    }
                    is ConnectionState.Connecting,
                    is ConnectionState.Reconnecting -> {
                        Log.d(TAG, "NetworkChanged while $current — stay in state")
                    }
                    is ConnectionState.Disconnecting -> {
                        Log.d(TAG, "NetworkChanged ignored while Disconnecting")
                    }
                }
            }

            is ConnectionEvent.CoreError -> {
                Log.e(TAG, "CoreError: ${event.reason} (retry=$retryCount)")
                logCollector.error(TAG, "CoreError: ${event.reason} (retry=$retryCount)")
                handleFailure(event.reason, current)
            }

            is ConnectionEvent.Timeout -> {
                Log.e(TAG, "Timeout in state $current (retry=$retryCount)")
                logCollector.warn(TAG, "Timeout in state $current (retry=$retryCount)")
                handleFailure("Timeout", current)
            }

            is ConnectionEvent.ShowError -> {
                if (current is ConnectionState.Error) {
                    Log.d(TAG, "ShowError ignored — already in Error: ${current.message}")
                    return
                }
                retryCount = 0
                setState(ConnectionState.Error(event.message))
            }

            is ConnectionEvent.ResetRetryCount -> {
                retryCount = 0
                Log.d(TAG, "Retry count reset")
            }

            is ConnectionEvent.ForceDisconnected -> {
                if (current is ConnectionState.Disconnected) {
                    Log.d(TAG, "ForceDisconnected: already stopped, skip")
                    logCollector.debug(TAG, "ForceDisconnected: already stopped, skip")
                    return
                }
                retryCount = 0
                setState(ConnectionState.Disconnected)
            }

            is ConnectionEvent.ServiceReady -> {
                retryCount = 0
                pendingServerName = event.serverName
                hasServerSelected = true
                setState(ConnectionState.Connected(event.serverName))
            }

            is ConnectionEvent.ServiceStopped -> {
                when (current) {
                    is ConnectionState.Disconnecting,
                    is ConnectionState.Disconnected -> setState(ConnectionState.Disconnected)
                    is ConnectionState.Error -> {
                        Log.d(TAG, "ServiceStopped while Error — keep Error banner")
                    }
                    is ConnectionState.Reconnecting,
                    is ConnectionState.Connecting -> {
                        Log.d(TAG, "ServiceStopped during $current — awaiting restart")
                    }
                    is ConnectionState.Connected -> {
                        handleFailure("Unexpected service stop", current)
                    }
                }
            }
        }
    }

    private fun handleFailure(reason: String, current: ConnectionState) {
        when (current) {
            is ConnectionState.Connecting,
            is ConnectionState.Reconnecting,
            is ConnectionState.Connected -> {
                retryCount++
                if (retryCount < MAX_RETRIES && hasServerSelected) {
                    Log.w(TAG, "Retry $retryCount/$MAX_RETRIES after: $reason")
                    logCollector.warn(TAG, "Retry $retryCount/$MAX_RETRIES after: $reason")
                    setState(ConnectionState.Reconnecting)
                } else {
                    val msg = "Connection failed after $MAX_RETRIES attempts: $reason"
                    Log.e(TAG, msg)
                    logCollector.error(TAG, msg)
                    retryCount = 0
                    if (current !is ConnectionState.Error && _state.value !is ConnectionState.Error) {
                        setState(ConnectionState.Error(msg))
                    }
                }
            }
            is ConnectionState.Error -> {
                Log.d(TAG, "Failure ignored — already in Error")
            }
            else -> { }
        }
    }
}
