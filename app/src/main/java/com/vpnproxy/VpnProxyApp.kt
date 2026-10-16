package com.vpnproxy

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.vpnproxy.logging.CrashHandler
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.logging.LogCollectorTree
import com.vpnproxy.logging.LogCollectorHolder
import com.vpnproxy.subscription.SubscriptionUpdateWorker
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

        // ★ CrashHandler — ДО всего остального, чтобы перехватить краши на раннем старте
        CrashHandler.install(this)

        if (BuildConfig.LOG_DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.plant(LogCollectorTree(logCollector))
        LogCollectorHolder.install(logCollector)
        logCollector.info("VpnProxyApp", "Application started, version=${BuildConfig.VERSION_NAME}")

        // Периодические задачи
        SubscriptionUpdateWorker.schedule(this, intervalHours = 6)

        // UpdateCheckWorker НЕ планируем здесь — он запускается:
        //   1) Вручную из Settings (force = true)
        //   2) Автоматически при открытии HomeScreen (LaunchedEffect)
        // Планирование из двух мест даёт дубли и race condition.
        // Если нужен фоновый периодический чек — раскомментируйте:
        // UpdateCheckWorker.schedule(this, intervalHours = 6)
    }
}
