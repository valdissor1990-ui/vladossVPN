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
import com.vpnproxy.R
import com.vpnproxy.core.connection.ConnectionCoordinator
import com.vpnproxy.core.connection.ConnectionEvent
import com.vpnproxy.core.connection.ConnectionState
import com.vpnproxy.core.network.NetworkAwareReconnector
import com.vpnproxy.core.splittunnel.SplitTunnelManager
import com.vpnproxy.core.tunnel.TrafficStatsCollector
import com.vpnproxy.core.singbox.SingboxConfigBuilder
import com.vpnproxy.core.singbox.SingBoxCore
import com.vpnproxy.core.singbox.SingBoxPlatform
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
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
    @Inject lateinit var logCollector: LogCollector
    @Inject lateinit var subscriptionRepository: SubscriptionRepository
    @Inject lateinit var connectionCoordinator: ConnectionCoordinator

    private var singBoxCore: SingBoxCore? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var vpnJob: Job? = null
    private var stateCollectorJob: Job? = null
    private var idleStopJob: Job? = null
    private var networkReconnector: NetworkAwareReconnector? = null
    private var watchdogJob: Job? = null
    private val isReconnecting = AtomicBoolean(false)

    @Volatile
    private var startInProgress = false

    override fun onCreate() {
        super.onCreate()
        Log.d("VpnService", "===== onCreate started =====")

        if (uncaughtHandlerInstalled.compareAndSet(false, true)) {
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                Log.e("VpnService", "FATAL EXCEPTION IN THREAD: ${thread.name}", throwable)
                previousHandler?.uncaughtException(thread, throwable)
            }
        }

        createNotificationChannel()
        promoteToForeground(getString(R.string.state_connecting))

        try {
            autoSwitcher.attachVpnService(this)
            singBoxCore = SingBoxCore(this, SingBoxPlatform(this, this, splitTunnelManager))
            logCollector.info(TAG, "Service created; libbox 1.14.3 single-process core ready")
        } catch (t: Throwable) {
            Log.e(TAG, "VpnProxyService.onCreate failed during Sing-box/service initialization", t)
            throw t
        }

        networkReconnector = NetworkAwareReconnector(
            context = this,
            logCollector = logCollector,
            isVpnActive = { _isRunning.value },
            isBusy = { startInProgress || autoSwitcher.isSwitching() },
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
            connectionCoordinator.state
                .distinctUntilChanged()
                .collect { state ->
                    Log.d(TAG, "Service reacting to state: $state")
                    logCollector.info(TAG, "Service reacting to state: $state")
                    when (state) {
                        is ConnectionState.Connecting,
                        is ConnectionState.Reconnecting -> {
                            cancelIdleStop()
                            promoteToForeground(getString(R.string.state_connecting))
                            restartVpnForDesiredState(state)
                        }
                        is ConnectionState.Connected -> {
                            cancelIdleStop()
                            if (!_isRunning.value && !startInProgress) {
                                restartVpnForDesiredState(state)
                            } else {
                                updateNotification(getString(R.string.state_connected))
                            }
                        }
                        is ConnectionState.Disconnecting,
                        is ConnectionState.Disconnected -> {
                            stopVpnKeepAlive(state.toString())
                            scheduleIdleStop()
                        }
                        is ConnectionState.Error -> {
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
        Log.d("VpnService", "===== onStartCommand. Action: ${intent?.action} =====")
        Log.i(TAG, "onStartCommand: action=${intent?.action}, startId=$startId (reactive mode)")

        when (intent?.action) {
            ACTION_REQUEST_DISCONNECT -> {
                Log.e("VpnService", "REQUEST_DISCONNECT → UserDisconnect (no stopSelf)")
                connectionCoordinator.send(ConnectionEvent.UserDisconnect)
            }
            ACTION_REQUEST_RECONNECT -> {
                Log.d(TAG, "REQUEST_RECONNECT → NetworkChanged")
                connectionCoordinator.send(ConnectionEvent.NetworkChanged)
            }
            ACTION_ENSURE_RUNNING, null -> {
                Log.d(TAG, "ENSURE_RUNNING — actor state=${connectionCoordinator.state.value}")
            }
            else -> Log.d(TAG, "Unhandled action ${intent.action}")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onRevoke() {
        Log.e("VpnService", "===== onRevoke called (Another VPN active or user revoked) =====", Exception("onRevoke trace"))
        logCollector.warn(TAG, "onRevoke — VPN revoked by system")
        connectionCoordinator.send(ConnectionEvent.CoreError("VPN permission revoked by system"))
        connectionCoordinator.send(ConnectionEvent.UserDisconnect)
        super.onRevoke()
    }

    private fun promoteToForeground(text: String) {
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
            Log.d("VpnService", "startForeground successful")
        } catch (t: Throwable) {
            Log.e("VpnService", "startForeground FAILED", t)
            throw t
        }
    }

    private fun scheduleIdleStop() {
        idleStopJob?.cancel()
        idleStopJob = scope.launch {
            Log.d(TAG, "Idle stop timer started (${IDLE_STOP_MS}ms)")
            delay(IDLE_STOP_MS)
            val s = connectionCoordinator.state.value
            if (s is ConnectionState.Disconnected || s is ConnectionState.Error || s is ConnectionState.Disconnecting) {
                Log.e(TAG, "Idle ${IDLE_STOP_MS}ms in $s — ForceDisconnected + stopForeground + stopSelf")
                logCollector.info(TAG, "Idle timeout — ForceDisconnected then stopping service process")
                connectionCoordinator.send(ConnectionEvent.ForceDisconnected)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "stopForeground failed", t)
                }
                stopSelf()
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
        if (startInProgress) {
            logCollector.debug(TAG, "restartVpn skipped: startInProgress for $state")
            return
        }
        if (!connectionCoordinator.hasSelectedServer()) {
            logCollector.warn(TAG, "restartVpn deferred — coordinator has no selected server")
            return
        }
        vpnJob?.cancel()
        vpnJob = scope.launch {
            try {
                if (_isRunning.value) {
                    stopVpnInternal("soft restart for $state")
                    delay(300)
                }
                startVpnSession()
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "restartVpnForDesiredState failed", t)
                connectionCoordinator.send(ConnectionEvent.CoreError(t.message ?: "restart failed"))
            }
        }
    }

    private suspend fun startVpnSession() {
        Log.i(TAG, "startVpnSession: startInProgress=$startInProgress isRunning=${_isRunning.value}")
        if (startInProgress) return
        _lastError.value = null
        startInProgress = true

        try {
            Log.d("VpnService", "startVpnSession on ${Thread.currentThread().name}")
            logCollector.info(TAG, "Starting VPN orchestrator (actor-driven)…")

            watchdogJob?.cancel()
            watchdogJob = scope.launch {
                delay(15_000)
                if (startInProgress) {
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
                if (!_isRunning.value) {
                    logCollector.warn(TAG, "Watchdog: VPN not running — Timeout event")
                    connectionCoordinator.send(ConnectionEvent.Timeout)
                }
            }

            val parsed = autoSwitcher.currentServer.value?.config
            if (parsed == null) {
                val reason = "No server selected — open Subscriptions first"
                logCollector.warn(TAG, reason)
                _lastError.value = reason
                connectionCoordinator.send(ConnectionEvent.CoreError(reason))
                return
            }

            logCollector.info(TAG, "Starting Sing-box for ${parsed.name} (${parsed.transport}/${parsed.security})")
            val core = singBoxCore ?: error("Sing-box core was not initialized")
            try {
                val configJson = SingboxConfigBuilder.buildVpn(parsed)
                Log.i(TAG, "Starting Sing-box core (config length=${configJson.length})")
                core.start(configJson)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start Sing-box", t)
                throw t
            }

            trafficCollector.start()
            trafficCollector.resetSession()
            autoSwitcher.startMonitoring()

            _isRunning.value = true
            isReconnecting.set(false)
            updateNotification(getString(R.string.state_connected))
            logCollector.info(TAG, "Sing-box VPN started successfully")
            connectionCoordinator.send(ConnectionEvent.ServiceReady(parsed.name))
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "startVpnSession failed", t)
            logCollector.error(TAG, "Failed to start VPN", t)
            _lastError.value = t.message ?: "start failed"
            stopVpnInternal("startVpnSession failed", t)
            connectionCoordinator.send(ConnectionEvent.CoreError(t.message ?: "startVpnSession failed"))
        } finally {
            startInProgress = false
            isReconnecting.set(false)
        }
    }

    private fun stopVpnKeepAlive(reason: String) {
        if (!_isRunning.value && !startInProgress) {
            Log.d(TAG, "ForceDisconnected/stopVpnKeepAlive: already stopped, skip ($reason)")
            logCollector.debug(TAG, "stopVpnKeepAlive skipped — already stopped ($reason)")
            return
        }
        Log.e("VpnService", "stopVpnKeepAlive. Reason: $reason (NO stopSelf)")
        vpnJob?.cancel()
        vpnJob = scope.launch {
            stopVpnInternal(reason)
            connectionCoordinator.send(ConnectionEvent.ServiceStopped)
            updateNotification(getString(R.string.state_disconnected))
        }
    }

    private suspend fun stopVpnInternal(
        reason: String = "stopVpnInternal requested",
        throwable: Throwable? = null
    ) {
        try {
            autoSwitcher.stopMonitoring()
            trafficCollector.stop()
            singBoxCore?.stop()

            Log.e(TAG, "Stopping VPN. Reason: " + reason, throwable)
            _isRunning.value = false
            logCollector.info(TAG, "VPN stopped (service alive). Reason: $reason")
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            logCollector.error(TAG, "Error stopping VPN", t)
        }
    }

    override fun onDestroy() {
        Log.e("VpnService", "===== onDestroy called. Service is dying! =====", Exception("onDestroy trace"))
        autoSwitcher.detachVpnService(this)
        networkReconnector?.stop()
        networkReconnector = null
        cancelIdleStop()
        stateCollectorJob?.cancel()
        stateCollectorJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        isReconnecting.set(false)
        kotlinx.coroutines.runBlocking { stopVpnInternal("onDestroy invoked") }
        scope.cancel()
        singBoxCore = null
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
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(state))
    }

    fun reconnectAfterSlowServerSwitch() {
        val server = autoSwitcher.currentServer.value
        if (server != null) {
            connectionCoordinator.send(ConnectionEvent.ResetRetryCount)
            connectionCoordinator.send(ConnectionEvent.ServerSelected(server))
        } else {
            connectionCoordinator.send(ConnectionEvent.NetworkChanged)
        }
    }

    fun showUnstableNetworkNotification() {
        logCollector.warn(TAG, "Ваша сеть нестабильна, попробуйте сменить Wi-Fi")
        updateNotification("Ваша сеть нестабильна, попробуйте сменить Wi-Fi")
    }
}
