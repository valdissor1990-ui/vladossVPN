package com.vpnproxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import com.vpnproxy.core.tunnel.TunnelManager
import com.vpnproxy.core.tunnel.TrafficStatsCollector
import com.vpnproxy.core.xray.XrayCore
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
    }

    @Inject lateinit var tunnelManager: TunnelManager
    @Inject lateinit var splitTunnelManager: SplitTunnelManager
    @Inject lateinit var xrayCore: XrayCore
    @Inject lateinit var trafficCollector: TrafficStatsCollector
    @Inject lateinit var autoSwitcher: AutoSwitcher
    @Inject lateinit var logCollector: LogCollector
    @Inject lateinit var subscriptionRepository: SubscriptionRepository

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var vpnJob: Job? = null
    private var networkReconnector: NetworkAwareReconnector? = null
    private var watchdogJob: Job? = null
    private val isReconnecting = AtomicBoolean(false)

    @Volatile
    private var startInProgress = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        autoSwitcher.attachVpnService(this)
        logCollector.info(TAG, "Service created, xrayAvailable=${xrayCore.isAvailable()}")

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        logCollector.warn(TAG, "onRevoke — VPN revoked by system")
        scope.launch { stopVpnInternal("VPN permission revoked by system") }
        stopServiceWithReason("VPN permission revoked by system")
        super.onRevoke()
    }

    private fun startVpn() {
        if (startInProgress || _isRunning.value) {
            logCollector.debug(TAG,
                "startVpn skipped: startInProgress=$startInProgress isRunning=${_isRunning.value}")
            return
        }
        if (!xrayCore.beginVpnStartup()) {
            val reason = "Xray занят глубоким анализом; повторите подключение после завершения"
            _lastError.value = reason
            logCollector.warn(TAG, reason)
            return
        }
        _lastError.value = null
        startInProgress = true

        vpnJob?.cancel()
        vpnJob = scope.launch {
            try {
                logCollector.info(TAG, "Starting VPN orchestrator…")
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.state_connecting)))

                watchdogJob?.cancel()
                watchdogJob = scope.launch {
                    delay(15_000)
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

                if (!xrayCore.stop()) {
                    val reason = "Could not stop previous Xray loop; refusing to start a second one"
                    logCollector.error(TAG, reason)
                    _lastError.value = reason
                    stopServiceWithReason(reason)
                    return@launch
                }

                val builder = Builder()
                splitTunnelManager.applyRules(builder, this@VpnProxyService)
                val pfd = try {
                    tunnelManager.establishTun(builder)
                } catch (t: Throwable) {
                    Log.e(TAG, "Stopping VPN. Reason: establishTun threw", t)
                    throw t
                }
                if (pfd == null) {
                    val reason = "TUN establishment failed"
                    logCollector.error(TAG, reason)
                    _lastError.value = reason
                    stopServiceWithReason(reason)
                    return@launch
                }

                val parsed = autoSwitcher.currentServer.value?.config
                    ?: subscriptionRepository.getAllConfigs().firstOrNull()
                if (parsed == null) {
                    val reason = "No server selected — open Subscriptions first"
                    logCollector.warn(TAG, reason)
                    _lastError.value = reason
                    tunnelManager.tearDown()
                    stopServiceWithReason(reason)
                    return@launch
                }

                if (!xrayCore.initEnv()) {
                    val reason = "initCoreEnv failed"
                    logCollector.error(TAG, reason)
                    _lastError.value = reason
                    tunnelManager.tearDown()
                    stopServiceWithReason(reason)
                    return@launch
                }
                logCollector.info(TAG, "libv2ray env initialized")

                val xrayJson = xrayCore.buildClientConfig(parsed, TunnelManager.SOCKS_PORT)
                val xrayOk = try {
                    xrayCore.start(xrayJson, tunFd = 0)
                } catch (t: Throwable) {
                    Log.e(TAG, "Stopping VPN. Reason: startLoop threw for " + parsed.name, t)
                    throw t
                }
                if (!xrayOk) {
                    val reason = "startLoop failed for ${parsed.name} (${parsed.transport}/${parsed.security})"
                    logCollector.error(TAG, reason)
                    startInProgress = false
                    xrayCore.stop()
                    tunnelManager.tearDown()
                    updateNotification("Connection failed; trying next server…")
                    _lastError.value = reason
                    return@launch
                }
                logCollector.info(TAG, "Xray startLoop OK, SOCKS 127.0.0.1:${TunnelManager.SOCKS_PORT}")

                val hevOk = tunnelManager.startHev(pfd.fd)
                if (!hevOk) {
                    val reason = "TProxyStartService failed"
                    logCollector.error(TAG, reason)
                    _lastError.value = reason
                    xrayCore.stop()
                    tunnelManager.tearDown()
                    stopServiceWithReason(reason)
                    return@launch
                }
                logCollector.info(TAG, "TProxyStartService requested/ok")

                trafficCollector.start()
                trafficCollector.resetSession()
                autoSwitcher.startMonitoring()

                _isRunning.value = true
                isReconnecting.set(false)
                updateNotification(getString(R.string.state_connected))
                logCollector.info(TAG, "VPN started successfully")
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "Stopping VPN. Reason: startVpn failed", t)
                logCollector.error(TAG, "Failed to start VPN", t)
                stopVpnInternal("startVpn failed", t)
                stopServiceWithReason("startVpn failed", t)
            } finally {
                startInProgress = false
                xrayCore.endVpnStartup()
                isReconnecting.set(false)
            }
        }
    }

    private fun stopVpn() {
        scope.launch {
            stopVpnInternal("explicit ACTION_STOP")
            stopServiceWithReason("explicit ACTION_STOP")
        }
    }

    private fun stopServiceWithReason(reason: String, throwable: Throwable? = null) {
        Log.e(TAG, "Stopping VPN. Reason: " + reason, throwable)
        stopSelf()
    }

    private suspend fun stopVpnInternal(
        reason: String = "stopVpnInternal requested",
        throwable: Throwable? = null
    ) {
        try {
            autoSwitcher.stopMonitoring()
            trafficCollector.stop()
            tunnelManager.stopHev()
            xrayCore.stop()
            tunnelManager.closeTun()
            Log.e(TAG, "Stopping VPN. Reason: " + reason, throwable)
            _isRunning.value = false
            logCollector.info(TAG, "VPN stopped")
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            logCollector.error(TAG, "Error stopping VPN", t)
        }
    }

    override fun onDestroy() {
        Log.e(TAG, "Stopping VPN. Reason: onDestroy invoked")
        autoSwitcher.detachVpnService(this)
        networkReconnector?.stop()
        networkReconnector = null
        watchdogJob?.cancel()
        watchdogJob = null
        isReconnecting.set(false)
        scope.launch { stopVpnInternal() }
        scope.cancel()
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
