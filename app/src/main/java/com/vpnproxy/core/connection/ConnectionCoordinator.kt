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
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val serverName: String) : ConnectionState()
    data object Disconnecting : ConnectionState()
    data object Reconnecting : ConnectionState()
}

sealed class ConnectionEvent {
    data object UserConnect : ConnectionEvent()
    data object UserDisconnect : ConnectionEvent()
    data class ServerSelected(val server: ScoringEngine.ScoredServer) : ConnectionEvent()
    data object NetworkChanged : ConnectionEvent()
    data class CoreError(val reason: String) : ConnectionEvent()
    data object Timeout : ConnectionEvent()
    /** Service successfully established TUN + core. */
    data class ServiceReady(val serverName: String) : ConnectionEvent()
    /** Service finished stopping core/TUN without killing the process. */
    data object ServiceStopped : ConnectionEvent()
}

@Singleton
class ConnectionCoordinator @Inject constructor(
    private val logCollector: LogCollector
) {
    companion object {
        private const val TAG = "ConnectionCoordinator"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val events = Channel<ConnectionEvent>(capacity = Channel.UNLIMITED)

    @Volatile
    private var pendingServerName: String? = null

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
                when (current) {
                    is ConnectionState.Disconnected,
                    is ConnectionState.Disconnecting -> {
                        setState(ConnectionState.Connecting)
                    }
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
                        pendingServerName = null
                        setState(ConnectionState.Disconnecting)
                    }
                }
            }

            is ConnectionEvent.ServerSelected -> {
                pendingServerName = event.server.config.name
                when (current) {
                    is ConnectionState.Disconnected -> {
                        setState(ConnectionState.Connecting)
                    }
                    is ConnectionState.Connecting -> {
                        // Keep Connecting; service will pick up new current server.
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
                    is ConnectionState.Connected -> setState(ConnectionState.Reconnecting)
                    is ConnectionState.Connecting,
                    is ConnectionState.Reconnecting -> {
                        Log.d(TAG, "NetworkChanged while $current — stay in state")
                    }
                    else -> Log.d(TAG, "NetworkChanged ignored in $current")
                }
            }

            is ConnectionEvent.CoreError -> {
                Log.e(TAG, "CoreError: ${event.reason}")
                logCollector.error(TAG, "CoreError: ${event.reason}")
                when (current) {
                    is ConnectionState.Connecting,
                    is ConnectionState.Reconnecting,
                    is ConnectionState.Connected -> {
                        // Soft path: ask service to retry via Reconnecting.
                        setState(ConnectionState.Reconnecting)
                    }
                    else -> { /* ignore */ }
                }
            }

            is ConnectionEvent.Timeout -> {
                Log.e(TAG, "Timeout in state $current")
                logCollector.warn(TAG, "Timeout in state $current")
                when (current) {
                    is ConnectionState.Connecting,
                    is ConnectionState.Reconnecting,
                    is ConnectionState.Connected -> setState(ConnectionState.Reconnecting)
                    else -> { }
                }
            }

            is ConnectionEvent.ServiceReady -> {
                pendingServerName = event.serverName
                setState(ConnectionState.Connected(event.serverName))
            }

            is ConnectionEvent.ServiceStopped -> {
                when (current) {
                    is ConnectionState.Disconnecting,
                    is ConnectionState.Disconnected -> setState(ConnectionState.Disconnected)
                    is ConnectionState.Reconnecting,
                    is ConnectionState.Connecting -> {
                        // Expected intermediate stop before restart — keep desired state.
                        Log.d(TAG, "ServiceStopped during $current — awaiting restart")
                    }
                    is ConnectionState.Connected -> {
                        // Unexpected stop while connected — soft reconnect.
                        setState(ConnectionState.Reconnecting)
                    }
                }
            }
        }
    }
}
