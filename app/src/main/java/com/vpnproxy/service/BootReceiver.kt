package com.vpnproxy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.vpnproxy.core.connection.ConnectionCoordinator
import com.vpnproxy.core.connection.ConnectionEvent
import com.vpnproxy.logging.LogCollector
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Автозапуск VPN при загрузке устройства.
 *
 * Обрабатывает:
 * - ACTION_BOOT_COMPLETED (обычная загрузка)
 * - ACTION_LOCKED_BOOT_COMPLETED (Direct Boot, Android 7+)
 * - QUICKBOOT_POWERON (некоторые китайские прошивки)
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var logCollector: LogCollector
    @Inject lateinit var connectionCoordinator: ConnectionCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        val isBootAction = action == Intent.ACTION_BOOT_COMPLETED ||
                action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
                action == "android.intent.action.QUICKBOOT_POWERON" ||
                action == "com.htc.intent.action.QUICKBOOT_POWERON"

        if (!isBootAction) return

        logCollector.info("BootReceiver", "Boot event received: $action — UserConnect via actor")

        connectionCoordinator.send(ConnectionEvent.UserConnect)

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
