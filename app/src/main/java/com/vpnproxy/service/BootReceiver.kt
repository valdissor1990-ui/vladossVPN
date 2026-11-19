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
 * Автозапуск VPN при загрузке / разблокировке устройства.
 *
 * Автоподключение разрешается только после свежей проверки сохранённого профиля.
 * Непроверенный сохранённый профиль никогда не передаётся прямо в VPN Core.
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
        // Keep the boot broadcast bounded. If a profile cannot be confirmed quickly,
        // leave VPN disconnected rather than bypassing the mandatory connection preflight.
        private const val PREFLIGHT_TIMEOUT_MS = 7_500L
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // BOOT_COMPLETED is delivered after user unlock, when credential-encrypted
        // subscription storage is available. Avoid LOCKED_BOOT_COMPLETED: Hilt would
        // eagerly construct repositories backed by EncryptedSharedPreferences too early.
        val isBootAction = action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"

        if (!isBootAction) return

        // Respect the user-facing auto-start switch. Keep this in device-protected
        // preferences so LOCKED_BOOT_COMPLETED can read it before unlock.
        val prefsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext() ?: context
        } else {
            context
        }
        val bootPrefs = prefsContext.getSharedPreferences("vladoss_ui_preferences", Context.MODE_PRIVATE)
        val autoStartEnabled = bootPrefs.getBoolean("auto_start", false)
        if (!autoStartEnabled) {
            logCollector.info(TAG, "Auto-start disabled in settings; skipping $action")
            return
        }
        val nowElapsed = SystemClock.elapsedRealtime()
        val lastAttemptElapsed = bootPrefs.getLong("last_boot_auto_start_attempt", 0L)
        if (lastAttemptElapsed > 0L && nowElapsed >= lastAttemptElapsed &&
            nowElapsed - lastAttemptElapsed < 60_000L
        ) {
            logCollector.info(TAG, "Duplicate boot broadcast ignored: $action")
            return
        }
        bootPrefs.edit().putLong("last_boot_auto_start_attempt", nowElapsed).apply()

        logCollector.info(TAG, "Boot event received: $action")
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val configs = try {
                    val live = jsonConfigRepository.allConfigsIncluding(subscriptionRepository.getAllConfigs())
                    if (live.isNotEmpty()) live
                    else jsonConfigRepository.allConfigsIncluding(subscriptionRepository.getCachedConfigs())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    logCollector.warn(TAG, "Failed to load configs: ${t.message}")
                    emptyList()
                }

                if (configs.isEmpty()) {
                    logCollector.warn(TAG, "No configs available, skip auto-connect")
                    return@launch
                }

                val saved = try {
                    lastServerStore.load()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    logCollector.warn(TAG, "Failed to load saved server: ${t.message}")
                    null
                }
                if (saved == null) {
                    logCollector.warn(TAG, "No saved server, skipping auto-connect")
                    return@launch
                }

                // New records match against the complete config fingerprint without
                // persisting raw server credentials or endpoint details in device-protected storage.
                val matched = if (saved.fingerprint.isNotEmpty()) {
                    configs.firstOrNull {
                        lastServerStore.fingerprint(it.connectionKey()) == saved.fingerprint
                    }
                } else {
                    val endpointMatches = configs.filter { cfg ->
                        cfg.address.equals(saved.address, ignoreCase = true) && cfg.port == saved.port
                    }
                    // Never fall back to a different credential on a shared endpoint.
                    if (saved.uuid.isNotEmpty()) {
                        endpointMatches.firstOrNull { it.uuid == saved.uuid }
                    } else {
                        endpointMatches.firstOrNull { it.name == saved.name } ?: endpointMatches.singleOrNull()
                    }
                }

                if (matched == null) {
                    logCollector.warn(TAG, "Saved profile identity not found in current configs, skipping auto-connect")
                    return@launch
                }

                // Upgrade legacy raw endpoint/UUID records to the fingerprint-only format
                // as soon as the exact profile is resolved from the current subscription.
                if (saved.fingerprint.isBlank()) {
                    lastServerStore.save(
                        address = matched.address,
                        port = matched.port,
                        name = matched.name,
                        uuid = matched.uuid,
                        connectionKey = matched.connectionKey()
                    )
                    logCollector.info(TAG, "Migrated saved server identity to fingerprint-only storage")
                }

                // Auto-start is an explicit user preference for the saved endpoint,
                // so clear only its current-network blacklist entry before a fresh retry.
                autoSwitcher.clearServerBlacklist(matched, "boot auto-start preflight")
                // Boot auto-start used to fabricate a successful ScoredServer and send
                // ServerSelected/UserConnect directly. That bypassed the same mandatory
                // reachability preflight used by the normal Connect button.
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
                    logCollector.warn(
                        TAG,
                        "Boot auto-start skipped: saved profile did not pass fresh preflight within ${PREFLIGHT_TIMEOUT_MS}ms"
                    )
                    return@launch
                }

                if (!autoSwitcher.forceSelect(selected)) {
                    logCollector.warn(TAG, "Boot auto-start skipped: saved profile was rejected by Smart Autopilot")
                    return@launch
                }

                connectionCoordinator.send(ConnectionEvent.ServerSelected(selected))
                connectionCoordinator.send(ConnectionEvent.UserConnect)
                logCollector.info(
                    TAG,
                    "Auto-connect after fresh preflight ($action): ${selected.config.name} RTT=${selected.latencyMs}ms"
                )

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
                } catch (e: Exception) {
                    logCollector.error(TAG, "Failed to start VPN service", e)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                logCollector.error(TAG, "Boot auto-start failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
