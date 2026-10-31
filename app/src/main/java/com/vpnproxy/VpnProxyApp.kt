package com.vpnproxy

import android.app.Application
import android.app.ActivityManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.vpnproxy.core.scanner.ServerScanner
import com.vpnproxy.core.connection.ConnectionCoordinator
import com.vpnproxy.core.connection.ConnectionState
import com.vpnproxy.core.diagnostics.BatteryLogger
import com.vpnproxy.core.diagnostics.BatteryMonitor
import com.vpnproxy.logging.CrashHandler
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.logging.LogCollectorTree
import com.vpnproxy.logging.LogCollectorHolder
import com.vpnproxy.subscription.SubscriptionRepository
import com.vpnproxy.subscription.SubscriptionUpdateWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Provider

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
    @Inject lateinit var workerFactoryProvider: Provider<HiltWorkerFactory>
    @Inject lateinit var batteryLoggerProvider: Provider<BatteryLogger>
    @Inject lateinit var batteryMonitorProvider: Provider<BatteryMonitor>
    @Inject lateinit var subscriptionRepositoryProvider: Provider<SubscriptionRepository>
    @Inject lateinit var serverScannerProvider: Provider<ServerScanner>
    @Inject lateinit var connectionCoordinatorProvider: Provider<ConnectionCoordinator>

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var batteryMonitorRegistered = false

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactoryProvider.get())
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()

        // ★ CrashHandler — ДО всего остального, чтобы перехватить краши на раннем старте
        CrashHandler.install(this)
        logCollector.initialize(this)

        if (BuildConfig.LOG_DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.plant(LogCollectorTree(logCollector))
        LogCollectorHolder.install(logCollector)
        logCollector.info("VpnProxyApp", "Application started, version=${BuildConfig.VERSION_NAME}")

        // Android creates Application independently in every process. Keep VPN,
        // WorkManager and subscription startup strictly in the default process;
        // :ace is an observation-only process and must not launch duplicate scans.
        if (!isMainProcess()) {
            logCollector.info("VpnProxyApp", "Skipping main-process startup in ${currentProcessName()}")
            return
        }

        val batteryMonitor = batteryMonitorProvider.get()
        val batteryLogger = batteryLoggerProvider.get()
        try {
            batteryMonitor.register(this)
            batteryMonitorRegistered = true
            batteryLogger.logSnapshot("app_start")
        } catch (t: Throwable) {
            logCollector.warn("VpnProxyApp", "Battery snapshot failed: ${t.message}")
        }

        // Try a fresh subscription immediately on every process start; repository keeps the cache on failure.
        applicationScope.launch {
            val connectionCoordinator = connectionCoordinatorProvider.get()
            val subscriptionRepository = subscriptionRepositoryProvider.get()
            val serverScanner = serverScannerProvider.get()
            val state = connectionCoordinator.state.value
            val vpnActive = state is ConnectionState.Connecting || state is ConnectionState.Verifying ||
                state is ConnectionState.Connected || state is ConnectionState.Reconnecting
            if (vpnActive) {
                logCollector.info("VpnProxyApp", "Startup auto-refresh skipped while VPN active: state=$state")
            } else {
                try {
                    val count = subscriptionRepository.refreshAll(skipIfFresh = true)
                    val providerConfigCount = subscriptionRepository.getAllConfigs().size
                    logCollector.info(
                        "VpnProxyApp",
                        "Startup subscription refresh finished: uniqueConfigs=$count providerConfigs=$providerConfigCount"
                    )
                    if (count == 0) {
                        logCollector.warn("VpnProxyApp", "Startup subscription refresh yielded no configs; no subscription servers are available")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Exception) {
                    logCollector.warn("VpnProxyApp", "Startup subscription refresh failed: ${t.message}")
                }
            }

            // Scan the repository's current snapshot even if the network refresh failed;
            // SubscriptionRepository has already loaded its persisted cache on cold start.
            try {
                // Start only the background full scan. A second staged connection scan here
                // duplicated the scan that HomeViewModel performs when the user connects.
                serverScanner.scanInBackground(applicationScope)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Exception) {
                logCollector.warn("VpnProxyApp", "Startup server scan failed: ${t.message}")
            }
        }

        // Refresh subscription every 30 minutes when network is available.
        SubscriptionUpdateWorker.schedule(this, intervalMinutes = 30)

        // UpdateCheckWorker НЕ планируем здесь — он запускается:
        //   1) Вручную из Settings (force = true)
        //   2) Автоматически при открытии HomeScreen (LaunchedEffect)
        // Планирование из двух мест даёт дубли и race condition.
        // Если нужен фоновый периодический чек — раскомментируйте:
        // UpdateCheckWorker.schedule(this, intervalHours = 6)
    }

    private fun currentProcessName(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Application.getProcessName()
    } else {
        val manager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
        manager?.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
    }

    private fun isMainProcess(): Boolean {
        val processName = currentProcessName()
        if (processName.isNullOrBlank()) {
            logCollector.warn("VpnProxyApp", "Unable to resolve process name; allowing default startup")
            return true
        }
        return processName == packageName
    }

    override fun onTerminate() {
        applicationScope.cancel()
        if (batteryMonitorRegistered) {
            try {
                batteryMonitorProvider.get().unregister(this)
            } catch (_: Throwable) {
            }
            batteryMonitorRegistered = false
        }
        super.onTerminate()
    }
}
