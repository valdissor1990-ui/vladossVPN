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
import com.vpnproxy.core.connection.VpnFailureReason
import com.vpnproxy.core.connection.VpnStartException
import com.vpnproxy.core.diagnostics.BatteryLogger
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
import kotlinx.coroutines.flow.asStateFlow
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
    @Inject lateinit var logCollector: LogCollector
    @Inject lateinit var subscriptionRepository: SubscriptionRepository
    @Inject lateinit var connectionCoordinator: ConnectionCoordinator
    @Inject lateinit var batteryLogger: BatteryLogger

    private var lifecycleController: VpnLifecycleController? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var vpnJob: Job? = null
    private var stateCollectorJob: Job? = null
    private var idleStopJob: Job? = null
    private var networkReconnector: NetworkAwareReconnector? = null
    private var watchdogJob: Job? = null
    private var batteryPeriodicJob: Job? = null
    private val isReconnecting = AtomicBoolean(false)

    private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Idle)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    // FILE CONTINUES - THIS IS TOO LARGE FOR ONE CALL
    // Will use alternate approach
}
