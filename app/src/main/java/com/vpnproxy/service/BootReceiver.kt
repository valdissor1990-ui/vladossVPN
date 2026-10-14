package com.vpnproxy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vpnproxy.logging.LogCollector
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var logCollector: LogCollector

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            logCollector.info("BootReceiver", "Boot completed, auto-starting VPN")
            val serviceIntent = Intent(context, VpnProxyService::class.java).apply {
                action = VpnProxyService.ACTION_START
            }
            context.startForegroundService(serviceIntent)
        }
    }
}
