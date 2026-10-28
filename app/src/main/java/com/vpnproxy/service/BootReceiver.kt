package com.vpnproxy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.vpnproxy.core.connection.ConnectionCoordinator
import com.vpnproxy.core.connection.ConnectionEvent
import com.vpnproxy.core.connection.LastServerStore
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.smartconnect.AutoSwitcher
import com.vpnproxy.subscription.JsonConfigRepository
import com.vpnproxy.subscription.SubscriptionRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var logCollector: LogCollector
    @Inject lateinit var connectionCoordinator: ConnectionCoordinator
    @Inject lateinit var lastServerStore: LastServerStore
    @Inject lateinit var subscriptionRepository: SubscriptionRepository
    @Inject lateinit var jsonConfigRepository: JsonConfigRepository
    @Inject lateinit var autoSwitcher: AutoSwitcher

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        logCollector.info("BootReceiver", "Boot event received: $action")

        val configs = try {
            val live = jsonConfigRepository.allConfigsIncluding(subscriptionRepository.getAllConfigs())
            if (live.isNotEmpty()) live else jsonConfigRepository.allConfigsIncluding(subscriptionRepository.getCachedConfigs())
        } catch (t: Throwable) {
            logCollector.warn("BootReceiver", "Failed to load configs: ${t.message}")
            emptyList()
        }

        if (configs.isEmpty()) {
            logCollector.info("BootReceiver", "No configs available after boot")
            return
        }

        val prepare = VpnService.prepare(context)
        if (prepare != null) {
            logCollector.warn("BootReceiver", "VPN permission not granted; skip auto-connect on boot")
            return
        }

        val last = lastServerStore.load()
        val selected = last?.let { stored ->
            configs.firstOrNull { it.connectionKey() == stored.connectionKey() }
        } ?: configs.firstOrNull()

        if (selected == null) {
            logCollector.warn("BootReceiver", "No selectable server after boot")
            return
        }

        logCollector.info("BootReceiver", "Requesting connect after boot: ${selected.name}")
        connectionCoordinator.send(ConnectionEvent.ServerSelected(selected, "boot"))
        connectionCoordinator.send(ConnectionEvent.UserConnect)
    }
}
