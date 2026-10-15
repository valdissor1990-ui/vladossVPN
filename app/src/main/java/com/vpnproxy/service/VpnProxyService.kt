package com.vpnproxy.service

import android.app.*
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Foreground VpnService — ядро приложения.
 *
 * Жизненный цикл:
 * 1. startVpn() → TUN → Xray (SOCKS) → hev-socks5-tunnel (TUN→SOCKS)
 * 2. Мониторинг через AutoSwitcher
 * 3. stopVpn() → hev stop → Xray stop → TUN close
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
    }

    @Inject lateinit var tunnelManager: TunnelManager
    @Inject lateinit var xrayCore: XrayCore
    @Inject lateinit var trafficCollector: TrafficStatsCollector
    @Inject lateinit var autoSwitcher: AutoSwitcher
    @Inject lateinit var logCollector: LogCollector
    @Inject lateinit var subscriptionRepository: SubscriptionRepository

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var vpnJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        xrayCore.setProtector { fd -> protect(fd) }
        logCollector.info(TAG, "Service created, xrayAvailable=${xrayCore.isAvailable()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
            ACTION_RECONNECT -> {
                scope.launch {
                    stopVpnInternal()
                    delay(500)
                    startVpn()
                }
            }
            else -> startVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (_isRunning.value) return
        vpnJob = scope.launch {
            try {
                logCollector.info(TAG, "Starting VPN...")

                val builder = Builder()
                    .setSession("VPNProxy")
                    .setMtu(TunnelManager.TUN_MTU)

                val pfd = tunnelManager.establishTun(builder)
                if (pfd == null) {
                    logCollector.error(TAG, "TUN establishment failed")
                    stopSelf()
                    return@launch
                }

                val configJson = resolveXrayConfig()
                if (configJson == null) {
                    logCollector.error(TAG, "No Xray config available")
                    tunnelManager.tearDown()
                    stopSelf()
                    return@launch
                }

                val xrayOk = xrayCore.start(configJson, tunFd = 0)
                if (!xrayOk) {
                    logCollector.error(TAG, "Xray start failed")
                    tunnelManager.tearDown()
                    stopSelf()
                    return@launch
                }

                val hevOk = tunnelManager.startTun2Socks(pfd.fd)
                if (!hevOk) {
                    logCollector.error(TAG, "hev-socks5-tunnel start failed")
                    xrayCore.stop()
                    tunnelManager.tearDown()
                    stopSelf()
                    return@launch
                }

                trafficCollector.start()
                trafficCollector.resetSession()
                autoSwitcher.startMonitoring()

                _isRunning.value = true
                startForeground(NOTIFICATION_ID, buildNotification())
                logCollector.info(TAG, "VPN started successfully")
            } catch (e: Exception) {
                logCollector.error(TAG, "Failed to start VPN", e)
                try { tunnelManager.tearDown() } catch (_: Exception) {}
                stopSelf()
            }
        }
    }

    private suspend fun resolveXrayConfig(): String? {
        val parsed = autoSwitcher.currentServer.value?.config
            ?: subscriptionRepository.getAllConfigs().firstOrNull()
            ?: return null

        val outbound = parsed.toXrayOutbound().toString()
        return buildFullConfig(outbound)
    }

    private fun buildFullConfig(outboundJson: String): String {
        return """
        {
          "log": { "loglevel": "warning" },
          "inbounds": [{
            "tag": "socks-in",
            "port": ${TunnelManager.SOCKS_PORT},
            "listen": "127.0.0.1",
            "protocol": "socks",
            "settings": { "udp": true, "auth": "noauth" }
          }],
          "outbounds": [$outboundJson],
          "policy": {
            "levels": { "0": { "connIdle": 300 } },
            "system": { "statsOutboundUplink": true, "statsOutboundDownlink": true }
          },
          "stats": {}
        }
        """.trimIndent()
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
            tunnelManager.tearDown()
            _isRunning.value = false
            logCollector.info(TAG, "VPN stopped")
        } catch (e: Exception) {
            logCollector.error(TAG, "Error stopping VPN", e)
        }
    }

    override fun onDestroy() {
        scope.launch { stopVpnInternal() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "VPN Proxy",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Состояние VPN-подключения"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, VpnProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val reconnectIntent = PendingIntent.getService(
            this, 1,
            Intent(this, VpnProxyService::class.java).setAction(ACTION_RECONNECT),
            PendingIntent.FLAG_IMMUTABLE
        )
        val contentIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VPNProxy")
            .setContentText("Подключено")
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(0, "Стоп", stopIntent)
            .addAction(0, "Переподключить", reconnectIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
