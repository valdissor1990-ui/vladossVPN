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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import dagger.hilt.android.HiltAndroidApp
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

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
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

        try {
            batteryMonitor.register(this)
            batteryLogger.logSnapshot("app_start")
        } catch (t: Throwable) {
            logCollector.warn("VpnProxyApp", "Battery snapshot failed: ${t.message}")
        }

        // Try a fresh subscription immediately on every process start; repository keeps the cache on failure.
        applicationScope.launch {
            val state = connectionCoordinator.state.value
            val vpnActive = state is ConnectionState.Connecting || state is ConnectionState.Verifying ||
                state is ConnectionState.Connected || state is ConnectionState.Reconnecting
            if (vpnActive) {
                logCollector.info("VpnProxyApp", "Startup auto-refresh skipped while VPN active: state=$state")
            } else {
                try {
                    val count = subscriptionRepository.refreshAll()
                    val providerConfigCount = subscriptionRepository.getAllConfigs().size
                    logCollector.info(
                        "VpnProxyApp",
                        "Startup subscription refresh finished: uniqueConfigs=$count providerConfigs=$providerConfigCount"
                    )
                    if (count == 0) {
                        logCollector.warn("VpnProxyApp", "Startup subscription refresh yielded no configs; no subscription servers are available")
                    }
                } catch (t: Throwable) {
                    logCollector.warn("VpnProxyApp", "Startup subscription refresh failed: ${t.message}")
                }
            }

            // Scan the repository's current snapshot even if the network refresh failed;
            // SubscriptionRepository has already loaded its persisted cache on cold start.
            try {
                serverScanner.scanAfterSubscriptionUpdate(subscriptionRepository.getAllConfigs())
                serverScanner.scanInBackground(applicationScope)
            } catch (t: Throwable) {
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

    private fun isMainProcess(): Boolean = currentProcessName() == packageName

    override fun onTerminate() {
        applicationScope.cancel()
        try {
            batteryMonitor.unregister(this)
        } catch (_: Throwable) {
        }
        super.onTerminate()
    }
}
