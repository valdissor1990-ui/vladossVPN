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
import com.vpnproxy.core.scanner.ServerScanner
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
        fun serverScanner(): ServerScanner
    }

    companion object {
        const val WORK_NAME = "subscription_update"

        fun schedule(context: Context, intervalMinutes: Int = 30) {
            val minutes = intervalMinutes.coerceIn(30, 24 * 60).toLong()
            val request = PeriodicWorkRequestBuilder<SubscriptionUpdateWorker>(
                minutes, TimeUnit.MINUTES
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
            val repository = deps.subscriptionRepository()
            val count = repository.refreshAll()
            deps.serverScanner().scanAfterSubscriptionUpdate(repository.getAllConfigs())
            deps.logCollector().info("SubUpdateWorker", "Refreshed subscriptions, configs=$count; server scan completed")
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
