package com.vpnproxy

import android.app.Application
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.logging.LogCollectorTree
import com.vpnproxy.update.UpdateCheckWorker
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class VpnProxyApp : Application() {

    @Inject lateinit var logCollector: LogCollector

    override fun onCreate() {
        super.onCreate()

        // Логирование
        if (BuildConfig.LOG_DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.plant(LogCollectorTree(logCollector))

        // Планируем периодическую проверку обновлений (6 часов)
        UpdateCheckWorker.schedule(this, intervalHours = 6)
    }
}
