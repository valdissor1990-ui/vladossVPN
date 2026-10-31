package com.vpnproxy.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vpnproxy.R
import com.vpnproxy.logging.LogCollector
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * WorkManager only checks for new releases. It never downloads or installs an APK;
 * downloading requires an explicit tap on the app's "Скачать" button.
 */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun updateManager(): UpdateManager
        fun logCollector(): LogCollector
    }

    companion object {
        const val WORK_NAME = "update_check"
        private const val CHANNEL_ID = "update_channel"
        private const val NOTIFICATION_ID = 2001

        fun schedule(context: Context, intervalHours: Long = 6) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                intervalHours, TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val deps = EntryPointAccessors.fromApplication(
                applicationContext,
                Deps::class.java
            )
            val updateManager = deps.updateManager()
            val log = deps.logCollector()

            updateManager.checkForUpdate(force = false)
            when (val state = updateManager.state.value) {
                is UpdateManager.State.UpdateAvailable -> {
                    val version = state.release.extractVersionName()
                    log.info("UpdateCheckWorker", "Update available: $version; waiting for user confirmation")
                    showUpdateAvailableNotification(version)
                }
                is UpdateManager.State.Ready -> {
                    log.info("UpdateCheckWorker", "Previously downloaded APK ready: ${state.release.extractVersionName()}")
                    showReadyNotification(state.release.extractVersionName())
                }
                is UpdateManager.State.UpToDate -> {
                    log.debug("UpdateCheckWorker", "Up to date")
                }
                is UpdateManager.State.Error -> {
                    log.warn("UpdateCheckWorker", "Update check error: ${state.message}")
                }
                else -> {
                    log.debug("UpdateCheckWorker", "State after prefetch: $state")
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            try {
                val deps = EntryPointAccessors.fromApplication(
                    applicationContext,
                    Deps::class.java
                )
                deps.logCollector().debug("UpdateCheckWorker", "Cancelled by WorkManager")
            } catch (_: Exception) { /* ignore */ }
            Result.success()
        } catch (t: Throwable) {
            try {
                val deps = EntryPointAccessors.fromApplication(
                    applicationContext,
                    Deps::class.java
                )
                deps.logCollector().warn("UpdateCheckWorker", "Failed: ${t.message}")
            } catch (_: Exception) { /* ignore */ }
            Result.retry()
        }
    }

    private fun showUpdateAvailableNotification(version: String) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Обновления VPNProxy",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Уведомления о доступных обновлениях"
            }
            ctx.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        val pending = android.app.PendingIntent.getActivity(
            ctx, 0, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle("Доступно обновление $version")
            .setContentText("Откройте приложение и нажмите «Скачать», если хотите обновиться.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) { /* нет POST_NOTIFICATIONS */ }
    }

    private fun showReadyNotification(version: String) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Обновления VPNProxy",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Уведомления о скачанных обновлениях"
            }
            ctx.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        val pending = android.app.PendingIntent.getActivity(
            ctx, 0, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle("Обновление $version скачано")
            .setContentText("Необходимо установить. Откройте приложение и нажмите «Установить».")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Обновление $version уже скачано в фоне.\n" +
                        "Откройте VPNProxy и нажмите «Установить».\n" +
                        "Если выйдет более новая версия до установки — будет сохранён последний APK."
                )
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) { /* нет POST_NOTIFICATIONS */ }
    }
}
