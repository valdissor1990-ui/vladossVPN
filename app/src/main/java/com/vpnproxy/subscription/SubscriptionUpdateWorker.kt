package com.vpnproxy.subscription

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vpnproxy.logging.LogCollector
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Периодическое обновление подписок в фоне через WorkManager.
 */
@HiltWorker
class SubscriptionUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: SubscriptionRepository,
    private val logCollector: LogCollector
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "subscription_update"

        fun schedule(context: Context, intervalHours: Int = 6) {
            val hours = intervalHours.coerceIn(1, 24).toLong()
            val request = PeriodicWorkRequestBuilder<SubscriptionUpdateWorker>(
                hours, TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val count = repository.refreshAll()
            logCollector.info("SubUpdateWorker", "Refreshed subscriptions, configs=$count")
            Result.success()
        } catch (e: Exception) {
            logCollector.error("SubUpdateWorker", "Refresh failed", e)
            Result.retry()
        }
    }
}
