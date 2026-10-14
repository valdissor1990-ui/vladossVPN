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
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker: периодически проверяет обновление в фоне.
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
            updateManager.checkForUpdate()
            val state = updateManager.state.value
            if (state is UpdateManager.State.UpdateAvailable) {
                showNotification(state.release.extractVersionName(), state.release.body)
            }
            Result.success()
        } catch (e: Exception) {
            try {
                val deps = EntryPointAccessors.fromApplication(
                    applicationContext,
                    Deps::class.java
                )
                deps.logCollector().error("UpdateCheckWorker", "Failed", e)
            } catch (_: Exception) { /* ignore */ }
            Result.retry()
        }
    }

    private fun showNotification(version: String, changelog: String) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Обновления VPNProxy",
                NotificationManager.IMPORTANCE_DEFAULT
            )
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
            .setContentText(changelog.take(100).replace("\n", " "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(changelog))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) { /* нет POST_NOTIFICATIONS */ }
    }
}
