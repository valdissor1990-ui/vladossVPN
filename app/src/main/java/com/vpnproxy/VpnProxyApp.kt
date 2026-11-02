package com.vpnproxy

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

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
    private val startupTasksStarted = AtomicBoolean(false)

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

        // One startup pipeline owns refresh + full scan; avoid racing stale cached profiles.
        startSafeStartupRefresh()
        SubscriptionUpdateWorker.schedule(this, intervalMinutes = 30)
    }

    private fun startSafeStartupRefresh() {
        if (!startupTasksStarted.compareAndSet(false, true)) return

        applicationScope.launch {
            if (!hasNetworkConnection()) {
                logCollector.warn("VpnProxyApp", "Startup scan skipped: no active network")
                return@launch
            }

            try {
                // Refresh first so startup scan and the next Connect action use one snapshot.
                val refreshedCount = subscriptionRepository.refreshAll()
                val configs = subscriptionRepository.getAllConfigs()
                if (configs.isNotEmpty()) {
                    logCollector.info(
                        "VpnProxyApp",
                        "Startup subscription refresh finished: uniqueConfigs=$refreshedCount; scanning=${configs.size}"
                    )
                    serverScanner.scanAll(configs, force = true)
                    // The background loop's initial scan reuses the just-completed result.
                    serverScanner.scanInBackground(applicationScope)
                    logCollector.info("VpnProxyApp", "Startup full server scan completed for ${configs.size} configs")
                } else {
                    logCollector.warn("VpnProxyApp", "Startup scan skipped: no cached or refreshed configs available")
                }
            } catch (t: Throwable) {
                logCollector.warn("VpnProxyApp", "Startup scan failed: ${t.message}")
            }
        }
    }

    private fun hasNetworkConnection(): Boolean {
        return try {
            val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = connectivity?.activeNetwork ?: return false
            val caps = connectivity.getNetworkCapabilities(activeNetwork) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (t: Throwable) {
            logCollector.warn("VpnProxyApp", "Network availability check failed: ${t.message}")
            false
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
