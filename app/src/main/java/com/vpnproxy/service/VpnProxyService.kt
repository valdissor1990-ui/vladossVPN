package com.vpnproxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vpnproxy.MainActivity
import com.vpnproxy.R
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

/**
 * Единственный VpnService в приложении.
 *
 * Порядок старта:
 *  1. TUN via Builder.establish()
 *  2. Xray startLoop (SOCKS 127.0.0.1:10808, tunFd=0)
 *  3. hev TProxyStartService(yaml, tunFd)
 *
 * org.amnezia.awg.hevtunnel.TProxyService в AAR — JNI-класс, не Android Service.
 */
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

        /** Причина последней ошибки старта; очищается после обработки UI. */
        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError

        fun clearError() {
            _lastError.value = null
        }
    }

    @Inject lateinit var tunnelManager: TunnelManager
    @Inject lateinit var xrayCore: XrayCore
    @Inject lateinit var trafficCollector: TrafficStatsCollector
    @Inject lateinit var autoSwitcher: AutoSwitcher
    @Inject lateinit var logCollector: LogCollector
    @Inject lateinit var subscriptionRepository: SubscriptionRepository

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var vpnJob: Job? = null

    /**
     * Защита от гонки: параллельные ACTION_START / null-intent не должны
     * запускать второй startVpn, пока первый не завершил setup.
     * Пока true — все новые startVpn игнорируются.
     */
    @Volatile
    private var startInProgress = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        logCollector.info(TAG, "Service created, xrayAvailable=${xrayCore.isAvailable()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopVpn()
            ACTION_RECONNECT -> scope.launch {
                stopVpnInternal()
                delay(400)
                startVpn()
            }
            ACTION_START -> startVpn()
            null -> {
                // START_STICKY redelivery: Android вызывает onStartCommand(null)
                // после перезапуска процесса. Если VPN уже работает — не дублируем.
                // Если не работает (первый запуск после kill) — стартуем.
                logCollector.debug(TAG, "onStartCommand(null): isRunning=${_isRunning.value}, inProgress=$startInProgress")
                startVpn()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    /**
     * Система отзывает VPN (пользователь отключил, Always-on переключился
     * на другой VpnService, разрешение revoked). Логируем — чтобы в DebugScreen
     * было видно причину тихого "VPN stopped".
     */
    override fun onRevoke() {
        logCollector.warn(TAG, "onRevoke — VPN revoked by system")
        scope.launch { stopVpnInternal() }
        stopSelf()
        super.onRevoke()
    }

    private fun startVpn() {
        if (startInProgress || _isRunning.value) {
            logCollector.debug(TAG,
                "startVpn skipped: startInProgress=$startInProgress isRunning=${_isRunning.value}")
            return
        }
        // Ошибка предыдущей попытки должна быть обработана HomeViewModel до нового старта.
        _lastError.value = null
        startInProgress = true

        // Defensive: если старая Job почему-то ещё жива — отменяем
        vpnJob?.cancel()
        vpnJob = scope.launch {
            try {
                logCollector.info(TAG, "Starting VPN orchestrator…")
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.state_connecting)))

                // Watchdog: если через 15с после старта — не running, принудительно stop
                scope.launch {
                    delay(15_000)
                    if (!_isRunning.value) {
                        logCollector.warn(TAG, "Watchdog: VPN not running after 15s — stopping")
                        stopVpnInternal()
                        stopSelf()
                    }
                }

                // 1. TUN
                val pfd = tunnelManager.establishTun(Builder())
                if (pfd == null) {
                    logCollector.error(TAG, "TUN establishment failed")
                    stopSelf()
                    return@launch
                }

                // 2. Server config
                val parsed = autoSwitcher.currentServer.value?.config
                    ?: subscriptionRepository.getAllConfigs().firstOrNull()
                if (parsed == null) {
                    logCollector.warn(TAG, "No server selected — open Subscriptions first")
                    tunnelManager.tearDown()
                    stopSelf()
                    return@launch
                }

                // 3. Xray env + SOCKS
                if (!xrayCore.initEnv()) {
                    logCollector.error(TAG, "initCoreEnv failed")
                    tunnelManager.tearDown()
                    stopSelf()
                    return@launch
                }
                logCollector.info(TAG, "libv2ray env initialized")

                val xrayJson = xrayCore.buildClientConfig(parsed, TunnelManager.SOCKS_PORT)
                val xrayOk = xrayCore.start(xrayJson, tunFd = 0)
                if (!xrayOk) {
                    val reason = "startLoop failed for ${parsed.name} (${parsed.transport}/${parsed.security})"
                    logCollector.error(TAG, reason)
                    // Открываем возможность следующей ACTION_START до уведомления UI.
                    startInProgress = false
                    xrayCore.stop()
                    tunnelManager.tearDown()
                    updateNotification("Connection failed; trying next server…")
                    _lastError.value = reason
                    // Сервис остаётся доступным для немедленного fallback от HomeViewModel.
                    return@launch
                }
                logCollector.info(TAG, "Xray startLoop OK, SOCKS 127.0.0.1:${TunnelManager.SOCKS_PORT}")

                // 4. hev: TUN → SOCKS
                val hevOk = tunnelManager.startHev(pfd.fd)
                if (!hevOk) {
                    logCollector.error(TAG, "TProxyStartService failed")
                    xrayCore.stop()
                    tunnelManager.tearDown()
                    stopSelf()
                    return@launch
                }
                logCollector.info(TAG, "TProxyStartService requested/ok")

                trafficCollector.start()
                trafficCollector.resetSession()
                autoSwitcher.startMonitoring()

                _isRunning.value = true
                updateNotification(getString(R.string.state_connected))
                logCollector.info(TAG, "VPN started successfully")
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                logCollector.error(TAG, "Failed to start VPN", t)
                stopVpnInternal()
                stopSelf()
            } finally {
                startInProgress = false
            }
        }
    }

    private fun stopVpn() {
        scope.launch {
            stopVpnInternal()
            stopSelf()
        }
    }

    private suspend fun stopVpnInternal() {
        try {
            autoSwitcher.stopMonitoring()
            trafficCollector.stop()
            tunnelManager.stopHev()
            xrayCore.stop()
            tunnelManager.closeTun()
            _isRunning.value = false
            logCollector.info(TAG, "VPN stopped")
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            logCollector.error(TAG, "Error stopping VPN", t)
        }
    }

    override fun onDestroy() {
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
}
