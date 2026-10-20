package com.vpnproxy.subscription

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vpnproxy.logging.LogCollector
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * Периодическое обновление подписок в фоне через WorkManager.
 * Зависимости через EntryPoint — обходит баг KSP/HiltWorker на некоторых версиях.
 */
class SubscriptionUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun subscriptionRepository(): SubscriptionRepository
        fun logCollector(): LogCollector
    }

    companion object {
        const val WORK_NAME = "subscription_update"

        fun schedule(context: Context, intervalHours: Int = 1) {
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
            val deps = EntryPointAccessors.fromApplication(
                applicationContext,
                Deps::class.java
            )
            val count = deps.subscriptionRepository().refreshAll()
            deps.logCollector().info("SubUpdateWorker", "Refreshed subscriptions, configs=$count")
            Result.success()
        } catch (e: Exception) {
            try {
                val deps = EntryPointAccessors.fromApplication(
                    applicationContext,
                    Deps::class.java
                )
                deps.logCollector().error("SubUpdateWorker", "Refresh failed", e)
            } catch (_: Exception) { /* ignore */ }
            Result.retry()
        }
    }
}
