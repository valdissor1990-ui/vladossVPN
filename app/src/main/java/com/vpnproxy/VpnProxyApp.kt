package com.vpnproxy

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.logging.LogCollectorTree
import com.vpnproxy.subscription.SubscriptionUpdateWorker
import com.vpnproxy.update.UpdateCheckWorker
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

/**
 * Application-класс.
 *
 * Обязанности:
 * - Инициализация Timber → LogCollector
 * - Настройка WorkManager через HiltWorkerFactory
 * - Планирование периодических задач (subscriptions + updates)
 */
@HiltAndroidApp
class VpnProxyApp : Application(), Configuration.Provider {

    @Inject lateinit var logCollector: LogCollector
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Логирование: debug → TRACE, release → INFO
        if (BuildConfig.LOG_DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.plant(LogCollectorTree(logCollector))
        logCollector.info("VpnProxyApp", "Application started, version=${BuildConfig.VERSION_NAME}")

        // Периодические задачи
        SubscriptionUpdateWorker.schedule(this, intervalHours = 6)
        UpdateCheckWorker.schedule(this, intervalHours = 6)
    }
}
