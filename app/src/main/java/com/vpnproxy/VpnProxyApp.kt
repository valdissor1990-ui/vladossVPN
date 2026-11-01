package com.vpnproxy

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.vpnproxy.core.connection.ConnectionCoordinator
import com.vpnproxy.core.connection.ConnectionState
import com.vpnproxy.core.diagnostics.BatteryLogger
import com.vpnproxy.core.diagnostics.BatteryMonitor
import com.vpnproxy.core.scanner.ServerScanner
import com.vpnproxy.logging.CrashHandler
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.logging.LogCollectorHolder
import com.vpnproxy.logging.LogCollectorTree
import com.vpnproxy.subscription.SubscriptionRepository
import com.vpnproxy.subscription.SubscriptionUpdateWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Application-класс.
 *
 * Обязанности:
 * - CrashHandler (до всего остального)
 * - Инициализация Timber → LogCollector
 * - Настройка WorkManager через HiltWorkerFactory
 * - Планирование периодических задач (subscriptions)
 * - BatteryMonitor register / unregister
 */
@HiltAndroidApp
class VpnProxyApp : Application(), Configuration.Provider {

    @Inject lateinit var logCollector: LogCollector
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var batteryLogger: BatteryLogger
    @Inject lateinit var batteryMonitor: BatteryMonitor
    @Inject lateinit var subscriptionRepository: SubscriptionRepository
    @Inject lateinit var serverScanner: ServerScanner
    @Inject lateinit var connectionCoordinator: ConnectionCoordinator

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startupRefreshStarted = false

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()

        CrashHandler.install(this)
        logCollector.initialize(this)

        if (BuildConfig.LOG_DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.plant(LogCollectorTree(logCollector))
        LogCollectorHolder.install(logCollector)
        logCollector.info("VpnProxyApp", "Application started, version=${BuildConfig.VERSION_NAME}")

        try {
            batteryMonitor.register(this)
            batteryLogger.logSnapshot("app_start")
        } catch (t: Throwable) {
            logCollector.warn("VpnProxyApp", "Battery snapshot failed: ${t.message}")
        }

        startSafeStartupRefresh()

        SubscriptionUpdateWorker.schedule(this, intervalMinutes = 30)
    }

    private fun startSafeStartupRefresh() {
        if (startupRefreshStarted) return
        startupRefreshStarted = true

        applicationScope.launch {
            if (!hasNetworkConnection()) {
                logCollector.warn("VpnProxyApp", "Startup refresh skipped: no active network")
                return@launch
            }

            val state = connectionCoordinator.state.value
            val vpnActive = state is ConnectionState.Connecting || state is ConnectionState.Verifying ||
                state is ConnectionState.Connected || state is ConnectionState.Reconnecting

            if (vpnActive) {
                logCollector.info("VpnProxyApp", "Startup auto-refresh skipped while VPN active: state=$state")
                return@launch
            }

            try {
                val count = subscriptionRepository.refreshAll()
                val providerConfigCount = subscriptionRepository.getAllConfigs().size
                logCollector.info(
                    "VpnProxyApp",
                    "Startup subscription refresh finished: uniqueConfigs=$count providerConfigs=$providerConfigCount"
                )
                if (count == 0) {
                    logCollector.warn(
                        "VpnProxyApp",
                        "Startup subscription refresh yielded no configs; no subscription servers are available"
                    )
                }
            } catch (t: Throwable) {
                logCollector.warn("VpnProxyApp", "Startup subscription refresh failed: ${t.message}")
            }

            try {
                val configs = subscriptionRepository.getAllConfigs()
                if (configs.isNotEmpty()) {
                    serverScanner.scanAfterSubscriptionUpdate(configs)
                    serverScanner.scanInBackground(applicationScope)
                } else {
                    logCollector.warn("VpnProxyApp", "Startup server scan skipped: no configs available")
                }
            } catch (t: Throwable) {
                logCollector.warn("VpnProxyApp", "Startup server scan failed: ${t.message}")
            }
        }
    }

    private fun hasNetworkConnection(): Boolean {
        return try {
            val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = connectivity?.activeNetwork ?: return false
            val caps = connectivity.getNetworkCapabilities(activeNetwork) ?: return false
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
        } catch (_: Throwable) {
            true
        }
    }

    override fun onTerminate() {
        applicationScope.cancel()
        try {
            batteryMonitor.unregister(this)
        } catch (_: Throwable) {
        }
        super.onTerminate()
    }
}
