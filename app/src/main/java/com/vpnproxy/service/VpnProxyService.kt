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
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicBoolean

@AndroidEntryPoint
class VpnProxyService : VpnService() {

    companion object {
        private const val TAG = "VpnProxyService"
        private const val CHANNEL_ID = "vpnproxy_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.vpnproxy.START"
        const val ACTION_STOP = "com.vpnproxy.STOP"
        const val ACTION_RECONNECT = "com.vpnproxy.RECONNECT"

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

    private var singBoxCore: SingBoxCore? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var vpnJob: Job? = null
    private var networkReconnector: NetworkAwareReconnector? = null
    private var watchdogJob: Job? = null
    private var passiveProbeJob: Job? = null
    private val isReconnecting = AtomicBoolean(false)

    @Volatile
    private var startInProgress = false

    override fun onCreate() {
        super.onCreate()
        Log.d("VpnService", "===== onCreate started =====")

        // Preserve the existing crash handler (installed by Application) while adding
        // an early service lifecycle trace. Java handlers cannot catch SIGSEGV/SIGABRT.
        if (uncaughtHandlerInstalled.compareAndSet(false, true)) {
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                Log.e("VpnService", "FATAL EXCEPTION IN THREAD: ${thread.name}", throwable)
                previousHandler?.uncaughtException(thread, throwable)
            }
        }

        createNotificationChannel()
        try {
            // Promote immediately, before any libbox/JNI initialization.
            val notification = buildNotification(getString(R.string.state_connecting))
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
                if (startInProgress || autoSwitcher.isSwitching()) {
                    isReconnecting.set(false)
                    logCollector.debug(TAG, "Network reconnect skipped — busy")
                    return@NetworkAwareReconnector
                }
                val intent = Intent(this, VpnProxyService::class.java).apply {
                    action = ACTION_RECONNECT
                }
                try {
                    startService(intent)
                } catch (t: Throwable) {
                    isReconnecting.set(false)
                    logCollector.error(TAG, "Network reconnect start failed", t)
                }
            },
            onReconnectStateChanged = { reconnecting ->
                isReconnecting.set(reconnecting)
                logCollector.debug(TAG, "Network reconnect state=$reconnecting")
            }
        ).also { it.start() }
        Log.d("VpnService", "===== onCreate completed =====")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("VpnService", "===== onStartCommand. Action: ${intent?.action} =====")
        Log.i(TAG, "onStartCommand: action=${intent?.action}, startId=$startId, flags=$flags")
        if (intent?.action == null || intent.action == ACTION_STOP) {
            Log.e("VpnService", "STOPPING SERVICE. Reason: ACTION_STOP or null intent", Exception("onStartCommand action trace"))
        }
        when (intent?.action) {
            ACTION_STOP -> stopVpn()
            ACTION_RECONNECT -> scope.launch {
                if (startInProgress) {
                    isReconnecting.set(false)
                    logCollector.debug(TAG, "RECONNECT skipped — startInProgress")
                    return@launch
                }
                stopVpnInternal()
                delay(500)
                startVpn()
            }
            ACTION_START -> startVpn()
            null -> {
                logCollector.debug(TAG, "onStartCommand(null): isRunning=${_isRunning.value}, inProgress=$startInProgress")
                startVpn()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onRevoke() {
        Log.e("VpnService", "===== onRevoke called (Another VPN active or user revoked) =====", Exception("onRevoke trace"))
        logCollector.warn(TAG, "onRevoke — VPN revoked by system")
        scope.launch { stopVpnInternal("VPN permission revoked by system") }
        stopServiceWithReason("VPN permission revoked by system")
        super.onRevoke()
    }

    private fun startVpn() {
        Log.i(TAG, "startVpn requested: startInProgress=$startInProgress isRunning=${_isRunning.value}")
        if (startInProgress || _isRunning.value) {
            logCollector.debug(TAG,
                "startVpn skipped: startInProgress=$startInProgress isRunning=${_isRunning.value}")
            return
        }
        _lastError.value = null
        startInProgress = true

        vpnJob?.cancel()
        vpnJob = scope.launch {
            try {
                Log.d("VpnService", "startVpn coroutine entered on ${Thread.currentThread().name}")
                logCollector.info(TAG, "Starting VPN orchestrator…")

                watchdogJob?.cancel()
                watchdogJob = scope.launch {
                    delay(15_000)
                    // Do not let a legacy watchdog tear down active Sing-box initialization.
                    if (startInProgress) {
                        logCollector.warn(TAG, "Watchdog: Sing-box startup still in progress after 15s; suppressing startup timeout")
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
                        val elapsedSeconds = 15 + graceWaitedMs / 1_000
                        logCollector.warn(TAG, "Watchdog: VPN not running after ${elapsedSeconds}s — stopping")
                        stopVpnInternal("watchdog timeout")
                        stopServiceWithReason("watchdog timeout")
                    }
                }

                val parsed = autoSwitcher.currentServer.value?.config
                    ?: subscriptionRepository.getAllConfigs().firstOrNull()
                if (parsed == null) {
                    val reason = "No server selected — open Subscriptions first"
                    logCollector.warn(TAG, reason)
                    _lastError.value = reason
                    stopServiceWithReason(reason)
                    return@launch
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
                startPassiveProbe(parsed.name)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "Stopping VPN. Reason: startVpn failed", t)
                logCollector.error(TAG, "Failed to start VPN", t)
                stopVpnInternal("startVpn failed", t)
                stopServiceWithReason("startVpn failed", t)
            } finally {
                startInProgress = false

                isReconnecting.set(false)
            }
        }
    }

    /** Passive diagnostic only: never changes connection state or switches servers. */
    private fun startPassiveProbe(serverName: String) {
        passiveProbeJob?.cancel()
        passiveProbeJob = scope.launch {
            val endpoints = listOf(
                "http://cp.cloudflare.com/generate_204",
                "http://neverssl.com",
                "https://www.gstatic.com/generate_204"
            )
            logCollector.info(TAG, "PassiveProbe lifecycle started server=$serverName intervalMs=5000")
            while (_isRunning.value && kotlinx.coroutines.currentCoroutineContext().isActive) {
                var succeeded = false
                for (endpoint in endpoints) {
                    if (!_isRunning.value) break
                    val startedAt = android.os.SystemClock.elapsedRealtime()
                    logCollector.info(TAG, "PassiveProbe start server=$serverName proxy=http://127.0.0.1:10809 endpoint=$endpoint")
                    try {
                        val proxy = java.net.Proxy(
                            java.net.Proxy.Type.HTTP,
                            java.net.InetSocketAddress("127.0.0.1", SingboxConfigBuilder.PROXY_PORT)
                        )
                        val connection = java.net.URL(endpoint).openConnection(proxy) as java.net.HttpURLConnection
                        try {
                            connection.connectTimeout = 5000
                            connection.readTimeout = 5000
                            connection.instanceFollowRedirects = false
                            connection.requestMethod = "GET"
                            connection.connect()
                            val status = connection.responseCode
                            val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                            val success = status in 200..399 || status == 204
                            logCollector.info(TAG, "PassiveProbe success=$success endpoint=$endpoint status=$status elapsedMs=$elapsed")
                            if (success) {
                                succeeded = true
                                break
                            }
                        } finally {
                            connection.disconnect()
                        }
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                        logCollector.warn(TAG, "PassiveProbe success=false endpoint=$endpoint elapsedMs=$elapsed exception=${t.javaClass.name} message=${t.message}")
                    }
                }
                if (_isRunning.value) delay(5_000)
            }
            logCollector.info(TAG, "PassiveProbe lifecycle stopped server=$serverName")
        }
    }

    private fun stopVpn() {
        Log.e("VpnService", "STOPPING SERVICE. Reason: stopVpn()", Exception("stopVpn() call stack"))
        scope.launch {
            stopVpnInternal("explicit ACTION_STOP")
            stopServiceWithReason("explicit ACTION_STOP")
        }
    }

    private fun stopServiceWithReason(reason: String, throwable: Throwable? = null) {
        Log.e("VpnService", "STOPPING SERVICE. Reason: $reason", throwable ?: Exception("stopServiceWithReason($reason) call stack"))
        try {
            singBoxCore?.stop()
        } catch (t: Throwable) {
            Log.e("VpnService", "Sing-box stop failed while stopping service: $reason", t)
        }
        Log.e("VpnService", "Calling stopSelf(). Reason: $reason", Exception("stopSelf() call stack"))
        stopSelf()
    }

    private suspend fun stopVpnInternal(
        reason: String = "stopVpnInternal requested",
        throwable: Throwable? = null
    ) {
        try {
            passiveProbeJob?.cancel()
            passiveProbeJob = null
            autoSwitcher.stopMonitoring()
            trafficCollector.stop()
            singBoxCore?.stop()

            Log.e(TAG, "Stopping VPN. Reason: " + reason, throwable)
            _isRunning.value = false
            logCollector.info(TAG, "VPN stopped. Reason: $reason")
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            logCollector.error(TAG, "Error stopping VPN", t)
        }
    }

    override fun onDestroy() {
        Log.e("VpnService", "===== onDestroy called. Service is dying! =====", Exception("onDestroy trace"))
        Log.e("VpnService", "STOPPING SERVICE. Reason: onDestroy", Exception("onDestroy call stack"))
        autoSwitcher.detachVpnService(this)
        networkReconnector?.stop()
        networkReconnector = null
        watchdogJob?.cancel()
        watchdogJob = null
        passiveProbeJob?.cancel()
        passiveProbeJob = null
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
            Intent(this, VpnProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val recPi = PendingIntent.getService(
            this, 1,
            Intent(this, VpnProxyService::class.java).setAction(ACTION_RECONNECT),
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
            .addAction(0, getString(R.string.notification_reconnect), recPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(state: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(state))
    }

    /** Reconnects the existing VPN service after AutoSwitcher selected another server. */
    fun reconnectAfterSlowServerSwitch() {
        startService(Intent(this, VpnProxyService::class.java).setAction(ACTION_RECONNECT))
    }

    /** Updates the persistent VPN notification when repeated switches indicate an unstable network. */
    fun showUnstableNetworkNotification() {
        logCollector.warn(TAG, "Ваша сеть нестабильна, попробуйте сменить Wi-Fi")
        updateNotification("Ваша сеть нестабильна, попробуйте сменить Wi-Fi")
    }
}
