package com.vpnproxy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.vpnproxy.core.connection.ConnectionCoordinator
import com.vpnproxy.core.connection.ConnectionEvent
import com.vpnproxy.core.connection.LastServerStore
import com.vpnproxy.core.scanner.ServerScanner
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.smartconnect.AutoSwitcher
import com.vpnproxy.subscription.JsonConfigRepository
import com.vpnproxy.subscription.SubscriptionRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Starts the saved VPN profile after boot only when it passes a fresh preflight.
 * The receiver deliberately waits for BOOT_COMPLETED, after credential-encrypted
 * subscription storage is available.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var logCollector: LogCollector
    @Inject lateinit var connectionCoordinator: ConnectionCoordinator
    @Inject lateinit var lastServerStore: LastServerStore
    @Inject lateinit var subscriptionRepository: SubscriptionRepository
    @Inject lateinit var jsonConfigRepository: JsonConfigRepository
    @Inject lateinit var autoSwitcher: AutoSwitcher
    @Inject lateinit var serverScanner: ServerScanner

    companion object {
        private const val TAG = "BootReceiver"
        // Android's broadcast execution window is short. Bound the complete operation,
        // not only the network scan, and leave time for pendingResult.finish().
        private const val TOTAL_TIMEOUT_MS = 8_000L
        private const val PREFLIGHT_TIMEOUT_MS = 6_000L
        private const val DUPLICATE_GUARD_MS = 60_000L
        private const val KEY_LAST_ATTEMPT = "last_boot_auto_start_attempt"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val isBootAction = action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        if (!isBootAction) return

        val prefsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext() ?: context
        } else {
            context
        }
        val bootPrefs = prefsContext.getSharedPreferences("vladoss_ui_preferences", Context.MODE_PRIVATE)
        if (!bootPrefs.getBoolean("auto_start", false)) {
            logCollector.info(TAG, "Auto-start disabled; skipping $action")
            return
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        val lastAttemptElapsed = bootPrefs.getLong(KEY_LAST_ATTEMPT, 0L)
        if (lastAttemptElapsed > 0L && nowElapsed >= lastAttemptElapsed &&
            nowElapsed - lastAttemptElapsed < DUPLICATE_GUARD_MS
        ) {
            logCollector.info(TAG, "Duplicate boot broadcast ignored: $action")
            return
        }
        bootPrefs.edit().putLong(KEY_LAST_ATTEMPT, nowElapsed).apply()

        logCollector.info(TAG, "Boot event received: $action")
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val completed = withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
                    performAutoStart(context, action)
                    true
                } ?: false
                if (!completed) {
                    logCollector.warn(TAG, "Boot auto-start exceeded total ${TOTAL_TIMEOUT_MS}ms deadline; leaving VPN disconnected")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                logCollector.error(TAG, "Boot auto-start failed", failure)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun performAutoStart(context: Context, action: String) {
        val configs = try {
            val live = jsonConfigRepository.allConfigsIncluding(subscriptionRepository.getAllConfigs())
            if (live.isNotEmpty()) live
            else jsonConfigRepository.allConfigsIncluding(subscriptionRepository.getCachedConfigs())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            logCollector.warn(TAG, "Failed to load subscription configs: ${failure.javaClass.simpleName}")
            emptyList()
        }

        if (configs.isEmpty()) {
            logCollector.warn(TAG, "No configs available after boot; skipping auto-start")
            return
        }

        val saved = try {
            lastServerStore.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            logCollector.warn(TAG, "Failed to load saved profile: ${failure.javaClass.simpleName}")
            null
        }
        if (saved == null) {
            logCollector.warn(TAG, "No saved profile; skipping auto-start")
            return
        }

        val matched = if (saved.fingerprint.isNotBlank()) {
            configs.firstOrNull { lastServerStore.fingerprint(it.connectionKey()) == saved.fingerprint }
        } else {
            val endpointMatches = configs.filter { config ->
                config.address.equals(saved.address, ignoreCase = true) && config.port == saved.port
            }
            if (saved.uuid.isNotBlank()) {
                endpointMatches.singleOrNull { it.uuid == saved.uuid }
            } else {
                // A legacy record without UUID must not select arbitrarily among profiles
                // that share both endpoint and display name.
                val nameMatches = endpointMatches.filter { it.name == saved.name }
                when {
                    nameMatches.size == 1 -> nameMatches.single()
                    nameMatches.isEmpty() -> endpointMatches.singleOrNull()
                    else -> null
                }
            }
        }

        if (matched == null) {
            logCollector.warn(TAG, "Saved profile identity not found in current subscriptions; skipping auto-start")
            return
        }

        if (saved.fingerprint.isBlank()) {
            lastServerStore.save(
                address = matched.address,
                port = matched.port,
                name = matched.name,
                uuid = matched.uuid,
                connectionKey = matched.connectionKey()
            )
            logCollector.info(TAG, "Migrated saved profile to fingerprint-only storage")
        }

        autoSwitcher.clearServerBlacklist(matched, "boot auto-start preflight")
        val verified = withTimeoutOrNull(PREFLIGHT_TIMEOUT_MS) {
            serverScanner.scanForConnection(
                configs = listOf(matched),
                targetAvailableCount = 1,
                preferredConfig = matched,
                forceFresh = true
            )
        }.orEmpty()
        val selected = verified.firstOrNull {
            it.config.connectionKey() == matched.connectionKey() &&
                it.successRate > 0.5f &&
                it.latencyMs != Long.MAX_VALUE
        }
        if (selected == null) {
            logCollector.warn(TAG, "Saved profile failed fresh preflight within ${PREFLIGHT_TIMEOUT_MS}ms; skipping auto-start")
            return
        }

        if (!autoSwitcher.forceSelect(selected)) {
            logCollector.warn(TAG, "Saved profile rejected by Smart Autopilot; skipping auto-start")
            return
        }

        connectionCoordinator.send(ConnectionEvent.ServerSelected(selected))
        connectionCoordinator.send(ConnectionEvent.UserConnect)
        logCollector.info(TAG, "Auto-connect after fresh preflight ($action): ${selected.config.name}, RTT=${selected.latencyMs}ms")

        val serviceIntent = Intent(context, VpnProxyService::class.java).apply {
            this.action = VpnProxyService.ACTION_ENSURE_RUNNING
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            logCollector.info(TAG, "VpnProxyService ENSURE_RUNNING started")
        } catch (failure: Exception) {
            logCollector.error(TAG, "Failed to start VPN service", failure)
        }
    }
}
