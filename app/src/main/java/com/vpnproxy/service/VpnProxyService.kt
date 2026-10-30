package com.vpnproxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vpnproxy.MainActivity
import com.vpnproxy.ace.ipc.AceTelemetryBridge
import com.vpnproxy.R
import com.vpnproxy.core.connection.ConnectionCoordinator
import com.vpnproxy.core.connection.ConnectionEvent
import com.vpnproxy.core.connection.ConnectionState
import com.vpnproxy.core.connection.VpnFailureReason
import com.vpnproxy.core.connection.VpnStartException
import com.vpnproxy.core.diagnostics.BatteryLogger
import com.vpnproxy.core.network.NetworkAwareReconnector
import com.vpnproxy.core.splittunnel.SplitTunnelManager
import com.vpnproxy.core.tunnel.TrafficStatsCollector
import com.vpnproxy.core.singbox.SingboxConfigBuilder
import com.vpnproxy.core.singbox.SingBoxCore
import com.vpnproxy.core.singbox.SingBoxPlatform
import com.vpnproxy.core.singbox.DiagnosticSettings
import com.vpnproxy.core.singbox.DiagnosticOptions
import com.vpnproxy.core.dualpath.DualPathStore
import com.vpnproxy.core.dualpath.ServerRegionClassifier
import com.vpnproxy.core.singbox.DiagnosticProbe
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.smartconnect.AutoSwitcher
import com.vpnproxy.subscription.SubscriptionRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicBoolean

@AndroidEntryPoint
class VpnProxyService : VpnService() {

    companion object {
        private const val TAG = "VpnProxyService"
        private const val CHANNEL_ID = "vpnproxy_channel"
        private const val NOTIFICATION_ID = 1001
        private const val IDLE_STOP_MS = 5 * 60_000L

        const val ACTION_ENSURE_RUNNING = "com.vpnproxy.ENSURE_RUNNING"
        const val ACTION_REQUEST_DISCONNECT = "com.vpnproxy.REQUEST_DISCONNECT"
        const val ACTION_REQUEST_RECONNECT = "com.vpnproxy.REQUEST_RECONNECT"
        /** Legacy force path — prefer RequestDisconnect via actor. */
        const val ACTION_FORCE_STOP = "com.vpnproxy.FORCE_STOP"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError

        fun clearError() {
            _lastError.value = null
        }

        private val uncaughtHandlerInstalled = AtomicBoolean(false)
    }

    @Inject lateinit var splitTunnelManager: SplitTunnelManager
    @Inject lateinit var trafficCollector: TrafficStatsCollector
    @Inject lateinit var autoSwitcher: AutoSwitcher
    @Inject lateinit var dualPathStore: DualPathStore
    @Inject lateinit var logCollector: LogCollector
    @Inject lateinit var subscriptionRepository: SubscriptionRepository
    @Inject lateinit var connectionCoordinator: ConnectionCoordinator
    @Inject lateinit var batteryLogger: BatteryLogger

    private var lifecycleController: VpnLifecycleController? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var vpnJob: Job? = null
    @Volatile private var restartRequestGeneration: Long = 0L
    @Volatile private var restartCompletedGeneration: Long = 0L
    private var stateCollectorJob: Job? = null
    private var idleStopJob: Job? = null
    private var networkReconnector: NetworkAwareReconnector? = null
    private var watchdogJob: Job? = null
    private var passiveProbeJob: Job? = null
    private var batteryPeriodicJob: Job? = null
    private val isReconnecting = AtomicBoolean(false)

    private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Idle)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    override fun onCreate() {
        // AndroidEntryPoint performs Hilt field injection in the generated superclass
        // lifecycle. Never dereference @Inject lateinit fields before super.onCreate().
        super.onCreate()
        connectionCoordinator.setOnEndpointHardFailed { cfg ->
            autoSwitcher.markServerFailed(cfg)
        }
        Log.d("VpnService", "===== onCreate started =====")

        if (uncaughtHandlerInstalled.compareAndSet(false, true)) {
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                Log.e("VpnService", "FATAL EXCEPTION IN THREAD: ${thread.name}", throwable)
                previousHandler?.uncaughtException(thread, throwable)
            }
        }

        createNotificationChannel()
        // HARD RULE: startForeground MUST run before any heavy work (Sing-box, collectors).
        // Android kills the process with ForegroundServiceDidNotStartInTimeException after ~5s.
        promoteToForeground(getString(R.string.state_connecting))
        Log.d("VpnService", "startForeground (Connecting) done BEFORE heavy init")

        try {
            autoSwitcher.attachVpnService(this)
            val core = SingBoxCore(this, SingBoxPlatform(this, this, splitTunnelManager), logCollector)
            lifecycleController = VpnLifecycleController(core, logCollector)
            logCollector.info(TAG, "Service created; libbox 1.14.3 single-process core ready")
        } catch (t: Throwable) {
            Log.e(TAG, "VpnProxyService.onCreate failed during Sing-box/service initialization", t)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (_: Throwable) { }
            throw t
        }

        networkReconnector = NetworkAwareReconnector(
            context = this,
            logCollector = logCollector,
            isVpnActive = { _isRunning.value },
            isBusy = { _serviceState.value == ServiceState.Starting || _serviceState.value == ServiceState.Stopping || autoSwitcher.isSwitching() },
            onReconnectRequested = {
                Log.d(TAG, "Network reconnect → ConnectionEvent.NetworkChanged")
                connectionCoordinator.send(ConnectionEvent.NetworkChanged)
            },
            onReconnectStateChanged = { reconnecting ->
                isReconnecting.set(reconnecting)
                logCollector.debug(TAG, "Network reconnect state=$reconnecting")
            }
        ).also { it.start() }

        stateCollectorJob = scope.launch {
            connectionCoordinator.state.collect { state ->
                if (_serviceState.value == ServiceState.Stopping) return@collect
                if (state is ConnectionState.Verifying) transitionServiceState(ServiceState.Verifying)
                else if (state is ConnectionState.Disconnected && _serviceState.value !is ServiceState.Failed) {
                    transitionServiceState(ServiceState.Idle)
                }
                Log.d(TAG, "Service reacting to state: $state")
                logCollector.info(TAG, "Service reacting to state: $state")
                when (state) {
                    is ConnectionState.Connecting,
                    is ConnectionState.Reconnecting -> {
                        cancelIdleStop()
                        promoteToForeground(getString(R.string.state_connecting))
                        connectionCoordinator.currentSelectedServer()?.let { autoSwitcher.forceSelect(it) }
                        restartVpnForDesiredState(state)
                    }
                    is ConnectionState.Verifying -> {
                        cancelIdleStop()
                        updateNotification("Проверка интернет-трафика…")
                    }
                    is ConnectionState.Connected -> {
                        if (_isRunning.value) transitionServiceState(ServiceState.Active)
                        cancelIdleStop()
                        val diagnosticEnabled = com.vpnproxy.util.DebugFlags.diagnosticEnabled()
                        val diagnosticOptions = if (diagnosticEnabled) DiagnosticSettings.load(this@VpnProxyService) else DiagnosticOptions(tunStack = "system", strictRoute = true)
                        autoSwitcher.setDiagnosticFreeze(diagnosticEnabled && diagnosticOptions.freezeAutoSwitcherAfterConnected)
                        autoSwitcher.startMonitoring()
                        if (diagnosticOptions.passiveProbeEnabled && com.vpnproxy.util.DebugFlags.diagnosticEnabled()) startPassiveProbe()
                        if (!_isRunning.value && _serviceState.value != ServiceState.Starting) {
                            restartVpnForDesiredState(state)
                        } else {
                            updateNotification(getString(R.string.state_connected))
                        }
                    }
                    is ConnectionState.Disconnecting,
                    is ConnectionState.Disconnected -> {
                        stopPassiveProbe()
                        autoSwitcher.clearDiagnosticFreeze()
                        stopVpnKeepAlive(state.toString())
                        scheduleIdleStop()
                    }
                    is ConnectionState.Error -> {
                        stopPassiveProbe()
                        autoSwitcher.clearDiagnosticFreeze()
                        stopVpnKeepAlive("Error: ${state.message}")
                        _lastError.value = state.message
                        updateNotification(state.message)
                        scheduleIdleStop()
                    }
                }
            }
        }

        Log.d("VpnService", "===== onCreate completed =====")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-assert foreground on every start — covers process restart / missed onCreate path.
        if (_serviceState.value != ServiceState.Stopping) {
            promoteToForeground(getString(R.string.state_connecting))
        }
        if (_serviceState.value == ServiceState.Stopping) {
            Log.w(TAG, "onStartCommand ignored — serviceState=${_serviceState.value} (action=${intent?.action})")
            return START_NOT_STICKY
        }
        Log.d("VpnService", "===== onStartCommand. Action: ${intent?.action} =====")
        Log.i(TAG, "onStartCommand: action=${intent?.action}, startId=$startId (reactive mode)")

        when (intent?.action) {
            ACTION_FORCE_STOP -> {
                Log.e("VpnService", "FORCE_STOP — RequestDisconnect via actor then teardown")
                connectionCoordinator.send(ConnectionEvent.RequestDisconnect)
                forceStopService("notification FORCE_STOP")
            }
            ACTION_REQUEST_DISCONNECT -> {
                Log.e("VpnService", "REQUEST_DISCONNECT → ConnectionEvent.RequestDisconnect")
                connectionCoordinator.send(ConnectionEvent.RequestDisconnect)
            }
            ACTION_REQUEST_RECONNECT -> {
                Log.d(TAG, "REQUEST_RECONNECT → NetworkChanged")
                connectionCoordinator.send(ConnectionEvent.NetworkChanged)
            }
            ACTION_ENSURE_RUNNING, null -> {
                Log.d(TAG, "ENSURE_RUNNING — actor state=${connectionCoordinator.state.value}")
            }
            else -> Log.d(TAG, "Unhandled action ${intent?.action}")
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Android calls this when the user removes the app task from Recents.
        // Enter the existing serialized teardown path; do not kill the process directly.
        Log.i(
            TAG,
            "onTaskRemoved: task swiped from Recents; " +
                "serviceState=${_serviceState.value}, isRunning=${_isRunning.value}"
        )
        logCollector.info(TAG, "Recent task removed — requesting full VPN shutdown")

        forceStopService("recent task swiped from Recents")
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onRevoke() {
        Log.e("VpnService", "===== onRevoke called (Another VPN active or user revoked) =====", Exception("onRevoke trace"))
        logCollector.warn(TAG, "onRevoke — VPN revoked by system")
        connectionCoordinator.send(ConnectionEvent.CoreError(VpnFailureReason.PermissionDenied))
        connectionCoordinator.send(ConnectionEvent.RequestDisconnect)
        super.onRevoke()
    }

    private fun promoteToForeground(text: String) {
        if (_serviceState.value == ServiceState.Stopping) return
        try {
            val notification = buildNotification(text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d("VpnService", "startForeground successful: $text")
        } catch (t: Throwable) {
            Log.e("VpnService", "startForeground FAILED", t)
            throw t
        }
    }

    private fun transitionServiceState(next: ServiceState) {
        val previous = _serviceState.value
        if (previous == next) return
        _serviceState.value = next
        logCollector.debug(TAG, "Service lifecycle state -> $next")

        // Passive ACE observation of the actual VPN service lifecycle.
        // Numeric codes avoid exporting failure reasons or other user/server data.
        val stateCode = when (next) {
            ServiceState.Idle -> 0.0
            ServiceState.Starting -> 1.0
            ServiceState.Active -> 2.0
            ServiceState.Verifying -> 3.0
            ServiceState.Stopping -> 4.0
            is ServiceState.Failed -> 5.0
        }
        AceTelemetryBridge.submitMetric(
            metric = "vpn_state",
            value = stateCode,
            unit = "service_state_code",
            outcome = "observed",
            source = "vpn_service"
        )
    }

    private fun scheduleIdleStop() {
        if ((_serviceState.value == ServiceState.Stopping)) return
        idleStopJob?.cancel()
        idleStopJob = scope.launch {
            Log.d(TAG, "Idle stop timer started (${IDLE_STOP_MS}ms)")
            delay(IDLE_STOP_MS)
            if (_serviceState.value == ServiceState.Stopping) return@launch
            val s = connectionCoordinator.state.value
            if (s is ConnectionState.Disconnected || s is ConnectionState.Error || s is ConnectionState.Disconnecting) {
                Log.e(TAG, "Idle ${IDLE_STOP_MS}ms in $s — forceStopService")
                logCollector.info(TAG, "Idle timeout — forceStopService")
                forceStopService("idle timeout in $s")
            } else {
                Log.d(TAG, "Idle timer fired but state=$s — keep alive")
            }
        }
    }

    private fun cancelIdleStop() {
        idleStopJob?.cancel()
        idleStopJob = null
    }

    private fun restartVpnForDesiredState(state: ConnectionState) {
        if (_serviceState.value == ServiceState.Stopping) return
        if (!connectionCoordinator.hasSelectedServer()) {
            logCollector.warn(TAG, "restartVpn deferred — coordinator has no selected server")
            return
        }

        val requestedGeneration = ++restartRequestGeneration
        val existingJob = vpnJob
        if (existingJob?.isActive == true) {
            logCollector.info(
                TAG,
                "restartVpn coalesced generation=$requestedGeneration inFlight=true state=$state"
            )
            return
        }
        if (_serviceState.value == ServiceState.Starting) {
            logCollector.debug(TAG, "restartVpn skipped: serviceState=${_serviceState.value} generation=$requestedGeneration for $state")
            return
        }

        vpnJob = scope.launch {
            var completedThisWorker = restartCompletedGeneration
            try {
                val controller = lifecycleController
                    ?: error("VPN lifecycle controller was not initialized")
                while (completedThisWorker < restartRequestGeneration &&
                    _serviceState.value != ServiceState.Stopping
                ) {
                    val generation = restartRequestGeneration
                    logCollector.info(TAG, "restartVpn worker begin generation=$generation inFlight=true")
                    controller.restartSession(
                        coreIsRunning = { _isRunning.value },
                        stopSession = {
                            stopVpnInternal("soft restart generation=$generation for $state", softRestart = true)
                        },
                        startSession = { startVpnSession() },
                        restartDelayMs = 3_000L
                    )
                    completedThisWorker = generation
                    restartCompletedGeneration = generation
                    logCollector.info(TAG, "restartVpn worker completed generation=$generation inFlight=false")
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "restartVpnForDesiredState failed", t)
                transitionServiceState(ServiceState.Failed(t.message ?: "restart failed"))
                emitCoreError(t)
            }
        }
    }

    private suspend fun startVpnSession() {
        Log.i(TAG, "startVpnSession: serviceState=${_serviceState.value} isRunning=${_isRunning.value}")
        if ((_serviceState.value == ServiceState.Stopping)) return
        if ((_serviceState.value == ServiceState.Starting)) return
        _lastError.value = null
        transitionServiceState(ServiceState.Starting)
        try {
            Log.d("VpnService", "startVpnSession on ${Thread.currentThread().name}")
            logCollector.info(TAG, "Starting VPN orchestrator (actor-driven)…")

            watchdogJob?.cancel()
            watchdogJob = scope.launch {
                delay(15_000)
                if ((_serviceState.value == ServiceState.Stopping)) return@launch
                if ((_serviceState.value == ServiceState.Starting)) {
                    logCollector.warn(TAG, "Watchdog: startup still in progress after 15s; suppressing")
                    return@launch
                }
                var graceWaitedMs = 0L
                while (WatchdogReconnectPolicy.shouldWaitForReconnect(
                    vpnRunning = _isRunning.value,
                    reconnecting = isReconnecting.get(),
                    graceWaitedMs = graceWaitedMs
                )) {
                    logCollector.debug(TAG, "Watchdog: reconnect in progress; extending timeout")
                    delay(5_000)
                    graceWaitedMs += 5_000L
                }
                if (!_isRunning.value && !(_serviceState.value == ServiceState.Stopping)) {
                    logCollector.warn(TAG, "Watchdog: VPN not running — Timeout event")
                    connectionCoordinator.send(ConnectionEvent.Timeout)
                }
            }

            val parsed = autoSwitcher.currentServer.value?.config
            if (parsed == null) {
                val reason = "No server selected — open Subscriptions first"
                logCollector.warn(TAG, reason)
                _lastError.value = reason
                connectionCoordinator.send(
                    ConnectionEvent.CoreError(VpnFailureReason.CoreError(reason))
                )
                stopSelf()
                return
            }

            logCollector.info(TAG, "Starting Sing-box for ${parsed.name} (${parsed.transport}/${parsed.security})")
            val controller = lifecycleController ?: error("VPN lifecycle controller was not initialized")
            try {
                val diagnosticEnabled = com.vpnproxy.util.DebugFlags.diagnosticEnabled()
                val diagnosticOptions = if (diagnosticEnabled) DiagnosticSettings.load(this@VpnProxyService) else DiagnosticOptions(tunStack = "system", strictRoute = true)
                logCollector.info(TAG, "DIAG USE_DISALLOW_SELF_PACKAGE=${diagnosticOptions.useDisallowSelfPackage}")
                logCollector.info(TAG, "DIAG AUTO_DETECT_INTERFACE=${diagnosticOptions.autoDetectInterface}")
                logCollector.info(TAG, "DIAG TUN_STACK=${diagnosticOptions.tunStack}")
                logCollector.info(TAG, "DIAG DNS_MODE=${diagnosticOptions.dnsMode}")
                logCollector.info(TAG, "DIAG protect_enabled=true")
                logCollector.info(TAG, "DIAG disallow_self=${diagnosticOptions.useDisallowSelfPackage}")
                logCollector.info(TAG, "DIAG-SETTINGS DNS_MODE=${diagnosticOptions.dnsMode} TUN_STACK=${diagnosticOptions.tunStack} STRICT_ROUTE=${diagnosticOptions.strictRoute}")
                logCollector.info(TAG, "DIAG-SETTINGS ROUTE_FINAL=${diagnosticOptions.routeFinal} USE_DISALLOW_SELF_PACKAGE=${diagnosticOptions.useDisallowSelfPackage}")
                logCollector.info(TAG, "DIAG-SETTINGS AUTO_DETECT_INTERFACE=${diagnosticOptions.autoDetectInterface} PASSIVE_PROBE_ENABLED=${diagnosticOptions.passiveProbeEnabled}")
                logCollector.info(TAG, "DIAG-SETTINGS FREEZE_AUTOSWITCHER=${diagnosticOptions.freezeAutoSwitcherAfterConnected}")
                logCollector.info(TAG, "DIAG-SETTINGS applied_at_this_connect=true")
                logCollector.info(TAG, "DNS mode=${diagnosticOptions.dnsMode} hijack=${if (diagnosticOptions.dnsMode == "system_no_hijack") "off" else "on"}")
                logCollector.info(TAG, "TUN stack=${diagnosticOptions.tunStack} strict_route=${diagnosticOptions.strictRoute} mtu=1400")
                logCollector.info(TAG, "Route auto_detect_interface=${diagnosticOptions.autoDetectInterface} final=${diagnosticOptions.routeFinal}")
                // Dual-path: selected apps -> foreign (non-RU) outbound when available
      val dualPackages = runCatching {
          dualPathStore.installedPackagesForRules()
      }.getOrDefault(emptyList())
      val foreignParsed = if (dualPackages.isNotEmpty()) {
          val rankedConfigs = autoSwitcher.rankedServers.value.map { it.config }
          val fromRanked = ServerRegionClassifier.pickBestForeign(rankedConfigs, preferredNot = parsed)
          fromRanked ?: ServerRegionClassifier.pickBestForeign(
              runCatching { subscriptionRepository.getAllConfigs() }.getOrDefault(emptyList()),
              preferredNot = parsed
          )
      } else null
      if (foreignParsed != null) {
          logCollector.info(
              TAG,
              "DUAL-PATH enabled foreign=${foreignParsed.name} apps=${dualPackages.size} packages=$dualPackages"
          )
      } else if (dualPackages.isNotEmpty()) {
          logCollector.warn(TAG, "DUAL-PATH: no foreign server in ranked list; selected apps use default proxy")
      }
      val configJson = SingboxConfigBuilder.buildVpn(
          parsed,
          diagnosticOptions,
          foreignConfig = foreignParsed,
          foreignPackageNames = if (foreignParsed != null) dualPackages else emptyList()
      )
                runCatching {
                    val root = org.json.JSONObject(configJson)
                    val route = root.optJSONObject("route")
                    val dnsServer = root.optJSONObject("dns")?.optJSONArray("servers")?.optJSONObject(0)
                    val inbounds = root.optJSONArray("inbounds")
                    var tunInbound: org.json.JSONObject? = null
                    if (inbounds != null) {
                        for (index in 0 until inbounds.length()) {
                            val inbound = inbounds.optJSONObject(index) ?: continue
                            if (inbound.optString("type") == "tun") {
                                tunInbound = inbound
                                break
                            }
                        }
                    }
                    logCollector.info(TAG, "DIAG-EFFECTIVE route.auto_detect_interface=${route?.optBoolean("auto_detect_interface", false) ?: false}")
                    logCollector.info(TAG, "DIAG-EFFECTIVE dns.servers[0].type=${dnsServer?.optString("type")?.takeIf { it.isNotBlank() } ?: "none"}")
                    logCollector.info(TAG, "DIAG-EFFECTIVE dns.servers[0].detour=${dnsServer?.optString("detour")?.takeIf { it.isNotBlank() } ?: "none"}")
                    logCollector.info(TAG, "DIAG-EFFECTIVE dns.servers[0].server=${dnsServer?.optString("server")?.takeIf { it.isNotBlank() } ?: "none"}")
                    logCollector.info(TAG, "DIAG-EFFECTIVE inbounds[tun].stack=${tunInbound?.optString("stack")?.takeIf { it.isNotBlank() } ?: "none"}")
                    logCollector.info(TAG, "DIAG-EFFECTIVE inbounds[tun].strict_route=${tunInbound?.optBoolean("strict_route", false) ?: false}")
                    logCollector.info(TAG, "DIAG-EFFECTIVE inbounds[tun].auto_route=${tunInbound?.optBoolean("auto_route", false) ?: false}")
                    logCollector.info(TAG, "DIAG-EFFECTIVE inbounds[tun].mtu=${tunInbound?.optInt("mtu", -1) ?: -1}")
                    logCollector.info(TAG, "DIAG-EFFECTIVE route.final=${route?.optString("final")?.takeIf { it.isNotBlank() } ?: "none"}")
                }.onFailure { t ->
                    logCollector.error(TAG, "DIAG-EFFECTIVE failed to parse final configJson: ${t.javaClass.simpleName}: ${t.message}")
                }
                Log.i(TAG, "Starting Sing-box core (config length=${configJson.length})")
                runCatching {
                    val masked = com.vpnproxy.logging.SensitiveMasker.mask(configJson)
                    logCollector.info(TAG, "Singbox config for ${parsed.name}: $masked")
                }.onFailure { Log.w(TAG, "Failed to log masked sing-box config", it) }
                controller.startCore(configJson)
                logCollector.info(TAG, "Native libbox logs captured through writeDebugMessage -> LogCollector; file log.output disabled")
            } catch (e: VpnStartException) {
                Log.e(TAG, "VpnStartException: ${e.reason}", e)
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start Sing-box", t)
                throw t
            }

            trafficCollector.start()
            trafficCollector.resetSession()

            _isRunning.value = true
            transitionServiceState(ServiceState.Active)
            isReconnecting.set(false)
            promoteToForeground(getString(R.string.state_connected))
            updateNotification(getString(R.string.state_connected))
            logCollector.info(TAG, "Sing-box VPN started successfully")
            connectionCoordinator.send(ConnectionEvent.ServiceReady(parsed.name))
            try {
                batteryLogger.logSnapshot("vpn_connected")
            } catch (t: Throwable) {
                logCollector.debug(TAG, "battery log vpn_connected failed: ${t.message}")
            }
            startBatteryPeriodicLogging()
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "startVpnSession failed", t)
            logCollector.error(TAG, "Failed to start VPN", t)
            val reasonMsg = t.message ?: "start failed"
            _lastError.value = reasonMsg
            transitionServiceState(ServiceState.Failed(reasonMsg))
            stopVpnInternal("startVpnSession failed", t)
            emitCoreError(t)
            // Keep the service alive until the coordinator chooses the next state.
            // Reconnecting must be able to restart the next candidate; stopping the service
            // here races the actor and destroys the scope that performs that fallback.
            // Fatal Error states are handled by stateCollectorJob and its idle-stop policy.
        } finally {
            isReconnecting.set(false)
        }
    }

    private fun emitCoreError(t: Throwable) {
        val reason = when (t) {
            is VpnStartException -> t.reason
            else -> VpnFailureReason.fromMessage(t.message)
        }
        val causalMessages = generateSequence(t as Throwable?) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
        val errorMessage = (causalMessages + " " + when (reason) {
            is VpnFailureReason.CoreError -> reason.message
            else -> reason.toString()
        }).lowercase()
        val isBindError = errorMessage.contains("address already in use") ||
            errorMessage.contains("bind:") ||
            errorMessage.contains("eaddrinuse")

        if (isBindError) {
            // A local listener collision is a lifecycle failure, not evidence against the remote server.
            logCollector.error(TAG, "Bind error detected — NOT banning server, increasing restart delay")
            connectionCoordinator.send(ConnectionEvent.CoreError(VpnFailureReason.BindError))
            return
        }

        val isConfigError = errorMessage.contains("unknown field") ||
            errorMessage.contains("decode config") ||
            errorMessage.contains("invalid config") ||
            errorMessage.contains("invalid configuration") ||
            errorMessage.contains("unable to start libbox")

        if (isConfigError) {
            // Ошибка генерации конфига (unknown field и т.п.) — короткий бан 1с,
            // чтобы fallback сразу мог взять следующий сервер без проблемного extra.
            logCollector.warn(
                TAG,
                "Configuration error detected for ${autoSwitcher.currentServer.value?.config?.name}. " +
                    "Skipping long blacklist to allow immediate fallback."
            )
            autoSwitcher.markServerFailed(
                autoSwitcher.currentServer.value?.config,
                ttlMs = 1_000L
            )
        } else {
            // Сетевая/ядерная ошибка — обычный бан ~10 мин.
            autoSwitcher.markServerFailed(autoSwitcher.currentServer.value?.config)
        }
        connectionCoordinator.send(ConnectionEvent.CoreError(reason))
    }

    private fun startPassiveProbe() {
        if (passiveProbeJob?.isActive == true) return
        val serverName = autoSwitcher.currentServer.value?.config?.name ?: connectionCoordinator.currentSelectedServer()?.config?.name ?: "unknown"
        passiveProbeJob = scope.launch {
            var outboundSuccessLogged = false
            logCollector.info(TAG, "PassiveProbe lifecycle started server=$serverName intervalMs=5000")
            while (isActive && connectionCoordinator.state.value is ConnectionState.Connected) {
                val probe = runCatching { DiagnosticProbe.runOnce(serverName, logCollector) }
                probe.onSuccess { result ->
                    if (result.success && !outboundSuccessLogged) {
                        val config = autoSwitcher.currentServer.value?.config
                        val endpoint = config?.let { "${it.address}:${it.port}" } ?: serverName
                        val protectionPath = if (DiagnosticSettings.load(this@VpnProxyService).autoDetectInterface) "via protect" else "with AUTO_DETECT_INTERFACE=false"
                        logCollector.info("LIBBOX", "Outbound established: $endpoint $protectionPath")
                        outboundSuccessLogged = true
                    }
                }.onFailure { t ->
                    logCollector.warn(TAG, "PassiveProbe runner failed: ${t.javaClass.name}: ${t.message}")
                }
                delay(5_000L)
            }
            logCollector.info(TAG, "PassiveProbe lifecycle stopped server=$serverName")
        }
    }

    private fun stopPassiveProbe() {
        passiveProbeJob?.cancel()
        passiveProbeJob = null
    }

    private fun stopVpnKeepAlive(reason: String) {
        if ((_serviceState.value == ServiceState.Stopping)) return
        if (!_isRunning.value && _serviceState.value != ServiceState.Starting) {
            Log.d(TAG, "stopVpnKeepAlive: already stopped, skip ($reason)")
            logCollector.debug(TAG, "stopVpnKeepAlive skipped — already stopped ($reason)")
            return
        }
        Log.e("VpnService", "stopVpnKeepAlive. Reason: $reason (NO stopSelf)")
        vpnJob?.cancel()
        vpnJob = scope.launch {
            lifecycleController?.withLifecycleLock {
                stopVpnInternal(reason)
                connectionCoordinator.send(ConnectionEvent.ServiceStopped)
                if (_serviceState.value != ServiceState.Stopping) {
                    updateNotification(getString(R.string.state_disconnected))
                }
            }
        }
    }

    private suspend fun stopVpnInternal(
        reason: String = "stopVpnInternal requested",
        throwable: Throwable? = null,
        softRestart: Boolean = false
    ) {
        try {
            stopPassiveProbe()
            autoSwitcher.stopMonitoring()
            autoSwitcher.clearDiagnosticFreeze()
            trafficCollector.stop()
            lifecycleController?.stopCore()

            Log.e(TAG, "Stopping VPN. Reason: " + reason, throwable)
            _isRunning.value = false
            if (!softRestart) {
                stopBatteryPeriodicLogging()
            }
            try {
                val trigger = if (softRestart) "vpn_soft_restart" else "vpn_stopped"
                batteryLogger.logSnapshot(trigger)
            } catch (t: Throwable) {
                logCollector.debug(TAG, "battery log stop failed: ${t.message}")
            }
            logCollector.info(TAG, "VPN stopped (service alive). Reason: $reason softRestart=$softRestart")
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            logCollector.error(TAG, "Error stopping VPN", t)
        }
    }

    private fun startBatteryPeriodicLogging() {
        batteryPeriodicJob?.cancel()
        batteryPeriodicJob = scope.launch {
            while (true) {
                delay(5 * 60_000L)
                if (!_isRunning.value || _serviceState.value == ServiceState.Stopping) break
                try {
                    batteryLogger.logSnapshot("periodic")
                    batteryLogger.logWarningIfNeeded()
                } catch (t: Throwable) {
                    logCollector.debug(TAG, "battery periodic failed: ${t.message}")
                }
            }
        }
    }

    private fun stopBatteryPeriodicLogging() {
        batteryPeriodicJob?.cancel()
        batteryPeriodicJob = null
    }

    private fun forceStopService(reason: String) {
        if (_serviceState.value == ServiceState.Stopping) {
            Log.d(TAG, "forceStopService already in progress, skip ($reason)")
            return
        }
        transitionServiceState(ServiceState.Stopping)
        Log.e(TAG, "forceStopService: $reason")
        logCollector.warn(TAG, "forceStopService: $reason")

        try {
            vpnJob?.cancel()
            vpnJob = null
            watchdogJob?.cancel()
            watchdogJob = null
            cancelIdleStop()
            stopBatteryPeriodicLogging()
            isReconnecting.set(false)
            networkReconnector?.stop()
        } catch (t: Throwable) {
            Log.e(TAG, "forceStopService cancel jobs failed", t)
        }

        scope.launch(Dispatchers.IO) {
            try {
                stopVpnInternal("forceStop: $reason")
            } catch (t: Throwable) {
                Log.e(TAG, "forceStopService stopVpnInternal failed", t)
            }
            connectionCoordinator.send(ConnectionEvent.ForceDisconnected(reason = "forceStopService: $reason"))
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "forceStopService stopForeground failed", t)
            }
            stopSelf()
        }
    }

    override fun onDestroy() {
        Log.e("VpnService", "===== onDestroy called. Service is dying! =====", Exception("onDestroy trace"))
        transitionServiceState(ServiceState.Stopping)
        try {
            connectionCoordinator.send(
                ConnectionEvent.ForceDisconnected(reason = "Service destroyed by system")
            )
        } catch (t: Throwable) {
            Log.e(TAG, "onDestroy ForceDisconnected send failed", t)
        }
        autoSwitcher.detachVpnService(this)
        networkReconnector?.stop()
        networkReconnector = null
        cancelIdleStop()
        stateCollectorJob?.cancel()
        stateCollectorJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        vpnJob?.cancel()
        vpnJob = null
        stopBatteryPeriodicLogging()
        isReconnecting.set(false)
        try {
            lifecycleController?.stopCore()
        } catch (t: Throwable) {
            Log.e(TAG, "onDestroy stopCore failed", t)
        }
        _isRunning.value = false
        scope.cancel()
        lifecycleController = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_vpn),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_vpn_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(state: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, VpnProxyService::class.java).setAction(ACTION_REQUEST_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val reconnectPi = PendingIntent.getService(
            this, 1,
            Intent(this, VpnProxyService::class.java).setAction(ACTION_REQUEST_RECONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val contentPi = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VPNProxy")
            .setContentText(state)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .addAction(0, getString(R.string.notification_stop), stopPi)
            .addAction(0, getString(R.string.notification_reconnect), reconnectPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(state: String) {
        if (_serviceState.value == ServiceState.Stopping) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(state))
    }

    fun reconnectAfterSlowServerSwitch() {
        if (_serviceState.value == ServiceState.Stopping) return
        val server = autoSwitcher.currentServer.value
        if (server != null) {
            connectionCoordinator.send(ConnectionEvent.ResetRetryCount)
            connectionCoordinator.send(
                ConnectionEvent.ServerSelected(server, connectionCoordinator.activeTraceId)
            )
        } else {
            connectionCoordinator.send(ConnectionEvent.NetworkChanged)
        }
    }

    fun showUnstableNetworkNotification() {
        logCollector.warn(TAG, "Ваша сеть нестабильна, попробуйте сменить Wi-Fi")
        updateNotification("Ваша сеть нестабильна, попробуйте сменить Wi-Fi")
    }
}
