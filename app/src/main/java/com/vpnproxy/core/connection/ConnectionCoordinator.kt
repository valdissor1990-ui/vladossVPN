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
    data object RequestDisconnect : ConnectionEvent()
    data class ConnectRequested(val traceId: String, val userInitiated: Boolean = false) : ConnectionEvent()
    data class ServerSelected(val server: ScoringEngine.ScoredServer, val traceId: String? = null) : ConnectionEvent()
    data class SetFallbackCandidates(val servers: List<ScoringEngine.ScoredServer>) : ConnectionEvent()
    data object NetworkChanged : ConnectionEvent()
    data class CoreError(val reason: VpnFailureReason) : ConnectionEvent()
    data object BindRetryReady : ConnectionEvent()
    data object Timeout : ConnectionEvent()
    data class ServiceReady(val serverName: String) : ConnectionEvent()
    data class TrafficValidationResult(
        val serverName: String,
        val success: Boolean,
        val failureSummary: String? = null,
        /** Correlates the result with the exact ServiceReady validation attempt. */
        val validationId: Long = -1L
    ) : ConnectionEvent()
    data object ServiceStopped : ConnectionEvent()
    data class ShowError(val message: String) : ConnectionEvent()
    data object ResetRetryCount : ConnectionEvent()
    data class ForceDisconnected(val reason: String = "") : ConnectionEvent()
}

@Singleton
class ConnectionCoordinator @Inject constructor(
    private val logCollector: LogCollector,
    private val trafficValidator: TrafficValidator,
    private val scoringEngine: ScoringEngine
) {
    companion object {
        private const val TAG = "ConnectionCoordinator"
        private const val MAX_RETRIES = 3
        private const val FATAL_TRAFFIC_MESSAGE = "Сеть заблокирована или недоступна. Проверьте интернет."
        const val CONNECTING_TIMEOUT_MS = 60_000L

        fun trafficFatalMessage(summary: String?): String {
            val s = summary?.lowercase().orEmpty()
            return when {
                s.isBlank() || s == "unknown" -> FATAL_TRAFFIC_MESSAGE
                "proxy_port_not_ready" in s ->
                    "Локальный прокси VPN не поднялся. Перезапустите приложение и попробуйте снова."
                "dns_error" in s ->
                    "Не удалось проверить трафик: ошибка DNS через VPN. Проверьте подписку или сеть."
                "timeout" in s ->
                    "Не удалось проверить трафик: сервер не отвечает вовремя. Попробуйте другой сервер."
                "refused" in s ->
                    "Не удалось проверить трафик: соединение отклонено. Сервер или сеть блокируют доступ."
                "unreachable" in s ->
                    "Не удалось проверить трафик: сеть недоступна. Проверьте интернет и Wi‑Fi/мобильные данные."
                else -> FATAL_TRAFFIC_MESSAGE
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()
    private val events = Channel<ConnectionEvent>(capacity = Channel.UNLIMITED)

    @Volatile private var pendingServerName: String? = null
    @Volatile private var hasServerSelected: Boolean = false
    private var retryCount: Int = 0
    private var fallbackCandidates: List<ScoringEngine.ScoredServer> = emptyList()
    private var fallbackIndex: Int = -1
    private var fallbackAttempts: Int = 0
    /** Monotonic token invalidates probes that finish after a server switch/reconnect. */
    private var trafficValidationGeneration: Long = 0L
    private var activeScoredServer: ScoringEngine.ScoredServer? = null

    @Volatile private var allowedProviderTag: String? = null
    @Volatile private var providerScopeBlocked: Boolean = false

    @Volatile var isFatalErrorLocked: Boolean = false
        private set
    @Volatile var activeTraceId: String? = null
        private set

    private var connectingTimeoutJob: Job? = null
    @Volatile private var selectionCancel: (() -> Unit)? = null

    fun registerSelectionCancel(cancel: (() -> Unit)?) { selectionCancel = cancel }

    fun setProviderScope(providerTag: String?, blocked: Boolean = false) {
        val normalized = providerTag?.takeIf { it.isNotBlank() }
        if (allowedProviderTag == normalized && providerScopeBlocked == blocked) return
        allowedProviderTag = normalized
        providerScopeBlocked = blocked
        val before = fallbackCandidates.size
        fallbackCandidates = fallbackCandidates.filter { isWithinProviderScope(it) }
        fallbackIndex = -1
        val scopeLabel = if (blocked) "BLOCKED" else (normalized ?: "ALL")
        logCollector.info(TAG, "Provider scope changed: $scopeLabel; fallback candidates $before -> ${fallbackCandidates.size}")
    }

    private fun isWithinProviderScope(server: ScoringEngine.ScoredServer): Boolean =
        !providerScopeBlocked && (allowedProviderTag?.let { it == server.config.providerTag } ?: true)

    init {
        scope.launch { for (event in events) handle(event) }
        logCollector.info(TAG, "ConnectionCoordinator actor started")
    }

    fun send(event: ConnectionEvent) {
        val tid = activeTraceId
        logCollector.info(TAG, TraceId.format(TAG, "Actor received event: $event", tid).removePrefix("$TAG | "))
        if (!events.trySend(event).isSuccess) logCollector.error(TAG, "Failed to enqueue event: $event")
    }

    fun currentServerName(): String? = pendingServerName
    fun currentSelectedServer(): ScoringEngine.ScoredServer? = activeScoredServer
    fun hasSelectedServer(): Boolean = hasServerSelected
    fun currentRetryCount(): Int = retryCount

    private fun logI(message: String) = logCollector.info(TAG, TraceId.format(TAG, message, activeTraceId).removePrefix("$TAG | "))
    private fun logD(message: String) = logCollector.debug(TAG, TraceId.format(TAG, message, activeTraceId).removePrefix("$TAG | "))
    private fun logW(message: String) = logCollector.warn(TAG, TraceId.format(TAG, message, activeTraceId).removePrefix("$TAG | "))
    private fun logE(message: String) = logCollector.error(TAG, TraceId.format(TAG, message, activeTraceId).removePrefix("$TAG | "))

    private fun setState(next: ConnectionState, reason: String = "") {
        val prev = _state.value
        if (prev == next) return
        _state.value = next
        logI("STATE: $prev -> $next | reason=$reason | retry=$retryCount | fatalLock=$isFatalErrorLocked")
        onStateChanged(next)
    }

    private fun onStateChanged(next: ConnectionState) {
        when (next) {
            is ConnectionState.Connecting, is ConnectionState.Reconnecting, is ConnectionState.Verifying -> scheduleConnectingTimeout()
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
        try { selectionCancel?.invoke() } catch (_: Throwable) {}
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
                    logI("Manual connect requested — fatal lock cleared")
                }
                activeTraceId = event.traceId
                retryCount = 0
                logI("ConnectRequested — enter Connecting (selection may still run)")
                when (current) {
                    is ConnectionState.Disconnected, is ConnectionState.Disconnecting, is ConnectionState.Error ->
                        setState(ConnectionState.Connecting, reason = "ConnectRequested")
                    is ConnectionState.Connecting -> logD("ConnectRequested while already Connecting")
                    else -> logD("ConnectRequested ignored in $current")
                }
            }
            is ConnectionEvent.UserConnect -> {
                if (isFatalErrorLocked) {
                    logW("UserConnect ignored — fatal lock is active")
                    return
                }
                retryCount = 0
                if (!hasServerSelected || pendingServerName == null) {
                    logI("UserConnect deferred — waiting for ServerSelected")
                    return
                }
                when (current) {
                    is ConnectionState.Disconnected, is ConnectionState.Disconnecting, is ConnectionState.Error ->
                        setState(ConnectionState.Connecting, reason = "UserConnect")
                    is ConnectionState.Connecting -> logD("UserConnect while Connecting — keep")
                    else -> logD("UserConnect ignored in state $current")
                }
            }
            is ConnectionEvent.UserDisconnect, is ConnectionEvent.RequestDisconnect -> {
                val tag = if (event is ConnectionEvent.RequestDisconnect) "RequestDisconnect" else "UserDisconnect"
                cancelSelectionJob()
                when (current) {
                    is ConnectionState.Disconnected -> logD("$tag ignored — already Disconnected")
                    is ConnectionState.Disconnecting -> logD("$tag while Disconnecting — stay")
                    else -> {
                        retryCount = 0
                        setState(ConnectionState.Disconnecting, reason = tag)
                    }
                }
            }
            is ConnectionEvent.SetFallbackCandidates -> {
                val incoming = event.servers.size
                // Keep Smart Connect's measured score order. Sorting by region here
                // silently overrode latency/reliability ranking and could send fallback
                // through many poor endpoints before trying a healthier one.
                fallbackCandidates = event.servers
                    .filter { isWithinProviderScope(it) }
                    .distinctBy { it.config.connectionKey() }
                fallbackIndex = -1
                fallbackAttempts = 0
                logI("Fallback candidates configured: ${fallbackCandidates.size} (incoming=$incoming scope=${if (providerScopeBlocked) "BLOCKED" else allowedProviderTag ?: "ALL"})")
            }
            is ConnectionEvent.ServerSelected -> {
                if (event.traceId != null && event.traceId != activeTraceId) {
                    val isWaitingForServer = (current is ConnectionState.Connecting || current is ConnectionState.Reconnecting) && !hasServerSelected
                    if (isWaitingForServer) {
                        logW("Accepting ServerSelected with stale traceId to prevent timeout: eventTraceId=${event.traceId} activeTraceId=$activeTraceId")
                        activeTraceId = event.traceId
                    } else {
                        logW("Ignoring stale ServerSelected for ${event.server.config.name}: eventTraceId=${event.traceId} activeTraceId=$activeTraceId")
                        return
                    }
                }
                if (!isWithinProviderScope(event.server)) {
                    logW("ServerSelected rejected outside provider scope: ${event.server.config.name} providerTag=${event.server.config.providerTag} scope=${if (providerScopeBlocked) "BLOCKED" else allowedProviderTag ?: "ALL"}")
                    return
                }
                trafficValidationGeneration++ // invalidate any in-flight probe for the previous selection
                pendingServerName = event.server.config.name
                activeScoredServer = event.server
                hasServerSelected = true
                retryCount = 0
                val selectedKey = event.server.config.connectionKey()
                val selectedWasFallback = fallbackCandidates.any { it.config.connectionKey() == selectedKey }
                fallbackCandidates = fallbackCandidates.filterNot { it.config.connectionKey() == selectedKey }
                fallbackIndex = -1
                if (!selectedWasFallback) fallbackAttempts = 0
                logI("ServerSelected: ${event.server.config.name}")
                when (current) {
                    is ConnectionState.Disconnected, is ConnectionState.Error -> logD("ServerSelected while idle")
                    is ConnectionState.Connecting -> setState(ConnectionState.Reconnecting, reason = "ServerSelected while Connecting")
                    is ConnectionState.Connected, is ConnectionState.Reconnecting, is ConnectionState.Verifying -> {
                        if (!isFatalErrorLocked) setState(ConnectionState.Reconnecting, reason = "ServerSelected")
                    }
                    is ConnectionState.Disconnecting -> logD("ServerSelected ignored while Disconnecting")
                }
            }
            is ConnectionEvent.NetworkChanged -> {
                if (isFatalErrorLocked) {
                    logI("NetworkChanged ignored — fatalErrorLocked")
                    return
                }
                when (current) {
                    is ConnectionState.Connected -> if (hasServerSelected) setState(ConnectionState.Reconnecting, reason = "NetworkChanged")
                    is ConnectionState.Disconnected, is ConnectionState.Error -> {
                        if (hasServerSelected) {
                            retryCount = 0
                            setState(ConnectionState.Connecting, reason = "NetworkChanged")
                        } else logD("NetworkChanged ignored — no server selected")
                    }
                    is ConnectionState.Connecting, is ConnectionState.Reconnecting, is ConnectionState.Verifying -> logD("NetworkChanged while $current — stay")
                    is ConnectionState.Disconnecting -> logD("NetworkChanged ignored while Disconnecting")
                }
            }
            is ConnectionEvent.CoreError -> {
                logE("CoreError: ${event.reason} (retry=$retryCount; fallbackAttempts=$fallbackAttempts; remainingCandidates=${fallbackCandidates.size})")
                if (event.reason is VpnFailureReason.BindError) {
                    logW("BindError — waiting 5s before retry, same server")
                    scope.launch {
                        delay(5_000L)
                        send(ConnectionEvent.BindRetryReady)
                    }
                } else if (!tryFallback(event.reason, current)) {
                    handleFailure(event.reason, current)
                }
            }
            is ConnectionEvent.BindRetryReady -> {
                val stateNow = _state.value
                if (hasServerSelected && !isFatalErrorLocked &&
                    (stateNow is ConnectionState.Connecting || stateNow is ConnectionState.Reconnecting ||
                        stateNow is ConnectionState.Connected || stateNow is ConnectionState.Verifying)
                ) {
                    retryCount = 0
                    val next = if (stateNow is ConnectionState.Connecting) ConnectionState.Reconnecting else ConnectionState.Connecting
                    setState(next, reason = "BindErrorRetrySameServer")
                } else logW("BindRetryReady ignored — state=$stateNow hasServerSelected=$hasServerSelected")
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
                if (event.serverName != pendingServerName) {
                    logW("Ignoring stale ServiceReady for ${event.serverName}; active=$pendingServerName")
                    return
                }
                if (isFatalErrorLocked) {
                    logW("ServiceReady ignored — fatal lock is active")
                    return
                }
                retryCount = 0
                val validationId = ++trafficValidationGeneration
                pendingServerName = event.serverName
                hasServerSelected = true
                setState(ConnectionState.Verifying(event.serverName), reason = "ServiceReady")
                if (com.vpnproxy.util.DebugFlags.SKIP_TRAFFIC_VALIDATION && com.vpnproxy.util.DebugFlags.diagnosticEnabled()) {
                    logW("Debug diagnostic mode: skipping connection-blocking traffic validation")
                    send(ConnectionEvent.TrafficValidationResult(event.serverName, success = true, validationId = validationId))
                    return
                }
                scope.launch {
                    val report = trafficValidator.probeTraffic(event.serverName)
                    val summary = if (!report.success) {
                        if (!report.portReady) {
                            "proxy_port_not_ready"
                        } else {
                            report.targets.joinToString(",") { t ->
                                "${t.host}:${t.outcome.name}${t.statusCode?.let { "/$it" } ?: ""}"
                            }.ifBlank { "no_targets" }
                        }
                    } else null
                    send(
                        ConnectionEvent.TrafficValidationResult(
                            serverName = event.serverName,
                            success = report.success,
                            failureSummary = summary,
                            validationId = validationId
                        )
                    )
                }
            }
            is ConnectionEvent.TrafficValidationResult -> {
                if (event.validationId != trafficValidationGeneration || event.serverName != pendingServerName || current !is ConnectionState.Verifying) {
                    logW("Ignoring stale traffic validation result: server=${event.serverName} active=$pendingServerName validationId=${event.validationId} currentGeneration=$trafficValidationGeneration state=$current")
                    return
                }
                if (isFatalErrorLocked) {
                    logW("TrafficValidationResult ignored — fatal lock")
                    return
                }
                if (event.success) {
                    setState(ConnectionState.Connected(event.serverName), reason = "TrafficValidated")
                } else {
                    val detail = event.failureSummary?.takeIf { it.isNotBlank() } ?: "unknown"
                    logW("Traffic validation failed for ${event.serverName}; detail=$detail")
                    // Feed confirmed data-plane failures back into Smart Connect so
                    // unreliable free-pool nodes rank lower on the next selection.
                    val failedConfig = activeScoredServer?.config
                    if (failedConfig != null && "proxy_port_not_ready" !in detail && "unreachable" !in detail.lowercase()) {
                        scoringEngine.recordHardFailure(failedConfig, weight = 2)
                    }
                    val reason = VpnFailureReason.NetworkTimeout("traffic validation failed: ${event.serverName} ($detail)")
                    // Failures are endpoint-specific: never let failures on one free node
                    // accumulate into a global fatal threshold while healthy candidates remain.
                    if (!tryFallback(reason, current)) {
                        handleFailure(reason, current)
                    }
                }
            }
            is ConnectionEvent.ServiceStopped -> {
                when (current) {
                    is ConnectionState.Disconnecting -> setState(ConnectionState.Disconnected, reason = "ServiceStopped")
                    is ConnectionState.Disconnected -> logD("ServiceStopped while already Disconnected")
                    else -> {
                        if (!isFatalErrorLocked) {
                            logW("ServiceStopped unexpected in $current")
                            setState(ConnectionState.Disconnected, reason = "ServiceStoppedUnexpected")
                        }
                    }
                }
            }
        }
    }

    private fun tryFallback(reason: VpnFailureReason, current: ConnectionState): Boolean {
        if (isFatalErrorLocked || !reason.canFallbackToNextServer() || fallbackCandidates.isEmpty()) return false
        if (current !is ConnectionState.Connecting && current !is ConnectionState.Reconnecting &&
            current !is ConnectionState.Connected && current !is ConnectionState.Verifying) return false

        // The currently active endpoint is the one that just failed. Remove it
        // before choosing the next candidate so a later failure cannot cycle back
        // to an endpoint already tried in this fallback pass.
        val failedKey = activeScoredServer?.config?.connectionKey()
        if (failedKey != null) {
            fallbackCandidates = fallbackCandidates.filterNot { it.config.connectionKey() == failedKey }
        }
        fallbackIndex = -1
        var nextIndex = 0
        while (nextIndex < fallbackCandidates.size) {
            val candidate = fallbackCandidates[nextIndex]
            if (!isWithinProviderScope(candidate)) {
                logW("Skip out-of-scope fallback candidate: ${candidate.config.name} providerTag=${candidate.config.providerTag}")
                fallbackCandidates = fallbackCandidates.filterNot { it.config.connectionKey() == candidate.config.connectionKey() }
                continue
            }
            val active = activeScoredServer?.config
            if (active == null || candidate.config.connectionKey() != active.connectionKey()) {
                fallbackAttempts++
                fallbackCandidates = fallbackCandidates.filterNot { it.config.connectionKey() == candidate.config.connectionKey() }
                fallbackIndex = -1
                trafficValidationGeneration++ // invalidate the failed endpoint's still-running probe
                activeScoredServer = candidate
                pendingServerName = candidate.config.name
                hasServerSelected = true
                retryCount = 0
                logW("Fallback attempt $fallbackAttempts -> ${candidate.config.name} (providerTag=${candidate.config.providerTag}) after $reason; remainingInScope=${fallbackCandidates.size}")
                setState(ConnectionState.Reconnecting, reason = "fallback:$fallbackAttempts:${candidate.config.name}")
                return true
            }
            nextIndex++
        }
        logW("Fallback exhausted in provider scope ${if (providerScopeBlocked) "BLOCKED" else allowedProviderTag ?: "ALL"}: attempts=$fallbackAttempts")
        return false
    }

    private fun handleFailure(reason: VpnFailureReason, current: ConnectionState) {
        if (!reason.isRetryable()) {
            applyFatal(reason)
            return
        }
        when (current) {
            is ConnectionState.Connecting, is ConnectionState.Reconnecting, is ConnectionState.Connected, is ConnectionState.Verifying -> {
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
