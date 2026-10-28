package com.vpnproxy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.vpnproxy.core.connection.ConnectionCoordinator
import com.vpnproxy.core.connection.ConnectionEvent
import com.vpnproxy.core.connection.LastServerStore
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.smartconnect.AutoSwitcher
import com.vpnproxy.smartconnect.ScoringEngine
import com.vpnproxy.subscription.JsonConfigRepository
import com.vpnproxy.subscription.SubscriptionRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Автозапуск VPN при загрузке / разблокировке устройства.
 *
 * - LOCKED_BOOT_COMPLETED: только если configs уже доступны; иначе skip.
 * - USER_UNLOCKED / BOOT_COMPLETED: восстановить last server и поднять VPN.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var logCollector: LogCollector
    @Inject lateinit var connectionCoordinator: ConnectionCoordinator
    @Inject lateinit var lastServerStore: LastServerStore
    @Inject lateinit var subscriptionRepository: SubscriptionRepository
    @Inject lateinit var jsonConfigRepository: JsonConfigRepository
    @Inject lateinit var autoSwitcher: AutoSwitcher

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        val isBootAction = action == Intent.ACTION_BOOT_COMPLETED ||
                action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
                action == Intent.ACTION_USER_UNLOCKED ||
                action == "android.intent.action.QUICKBOOT_POWERON" ||
                action == "com.htc.intent.action.QUICKBOOT_POWERON"

        if (!isBootAction) return

        logCollector.info("BootReceiver", "Boot event received: $action")

        val configs = try {
            val live = jsonConfigRepository.allConfigsIncluding(subscriptionRepository.getAllConfigs())
            if (live.isNotEmpty()) live else jsonConfigRepository.allConfigsIncluding(subscriptionRepository.getCachedConfigs())
        } catch (t: Throwable) {
            logCollector.warn("BootReceiver", "Failed to load configs: ${t.message}")
            emptyList()
        }

        // Direct Boot: credential-encrypted storage may be unavailable.
        if (configs.isEmpty() && action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            logCollector.info(
                "BootReceiver",
                "BootReceiver: direct boot, configs unavailable, skip auto-connect"
            )
            return
        }

        if (configs.isEmpty()) {
            logCollector.warn("BootReceiver", "BootReceiver: no configs available, skip auto-connect")
            return
        }

        val saved = lastServerStore.load()
        if (saved == null) {
            logCollector.warn("BootReceiver", "BootReceiver: no saved server, skipping auto-connect")
            return
        }

        val matched = configs.firstOrNull { cfg ->
            cfg.address.equals(saved.address, ignoreCase = true) &&
                cfg.port == saved.port &&
                (saved.uuid.isEmpty() || cfg.uuid == saved.uuid)
        } ?: configs.firstOrNull { cfg ->
            cfg.address.equals(saved.address, ignoreCase = true) && cfg.port == saved.port
        }

        if (matched == null) {
            logCollector.warn(
                "BootReceiver",
                "BootReceiver: saved server ${saved.name} (${saved.address}:${saved.port}) not found in configs, skipping auto-connect"
            )
            return
        }

        val scored = ScoringEngine.ScoredServer(
            config = matched,
            latencyMs = 0L,
            speedMbps = 0f,
            successRate = 1f,
            score = 0f
        )
        autoSwitcher.forceSelect(scored)
        connectionCoordinator.send(ConnectionEvent.ServerSelected(scored))
        connectionCoordinator.send(ConnectionEvent.UserConnect)
        logCollector.info(
            "BootReceiver",
            "Auto-connect ($action): ServerSelected(${matched.name}) + UserConnect"
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
            logCollector.info("BootReceiver", "VpnProxyService ENSURE_RUNNING started")
        } catch (e: Exception) {
            logCollector.error("BootReceiver", "Failed to start VPN service", e)
        }
    }
}
