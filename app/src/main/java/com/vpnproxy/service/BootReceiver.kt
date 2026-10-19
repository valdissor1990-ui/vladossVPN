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
import com.vpnproxy.subscription.SubscriptionRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Автозапуск VPN при загрузке устройства.
 * Восстанавливает последний сервер из [LastServerStore], затем ServerSelected → UserConnect.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var logCollector: LogCollector
    @Inject lateinit var connectionCoordinator: ConnectionCoordinator
    @Inject lateinit var lastServerStore: LastServerStore
    @Inject lateinit var subscriptionRepository: SubscriptionRepository
    @Inject lateinit var autoSwitcher: AutoSwitcher

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        val isBootAction = action == Intent.ACTION_BOOT_COMPLETED ||
                action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
                action == "android.intent.action.QUICKBOOT_POWERON" ||
                action == "com.htc.intent.action.QUICKBOOT_POWERON"

        if (!isBootAction) return

        logCollector.info("BootReceiver", "Boot event received: $action")

        val saved = lastServerStore.load()
        if (saved == null) {
            logCollector.warn("BootReceiver", "BootReceiver: no saved server, skipping auto-connect")
            return
        }

        val configs = try {
            val live = subscriptionRepository.getAllConfigs()
            if (live.isNotEmpty()) live else subscriptionRepository.getCachedConfigs()
        } catch (t: Throwable) {
            logCollector.warn("BootReceiver", "Failed to load configs: ${t.message}")
            emptyList()
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
            "Auto-connect: ServerSelected(${matched.name}) + UserConnect"
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
