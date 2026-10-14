package com.vpnproxy.subscription

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.vpnproxy.logging.LogCollector
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * WorkManager Worker для фонового обновления подписок.
 * Периодичность настраивается: 1/3/6/12/24 ч.
 */
@HiltWorker
class SubscriptionUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: SubscriptionRepository,
    private val logCollector: LogCollector
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            logCollector.info("SubscriptionUpdater", "Background refresh started")
            val count = repository.refreshAll()
            logCollector.info("SubscriptionUpdater", "Background refresh done: $count configs")
            Result.success()
        } catch (e: Exception) {
            logCollector.error("SubscriptionUpdater", "Background refresh failed", e)
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "subscription_update"

        /** Планирует периодическое обновление. */
        fun schedule(context: Context, intervalHours: Int) {
            val request = PeriodicWorkRequestBuilder<SubscriptionUpdateWorker>(
                intervalHours.toLong(), TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /** Отменяет обновление. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
