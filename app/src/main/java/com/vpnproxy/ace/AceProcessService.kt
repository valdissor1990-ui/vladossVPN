package com.vpnproxy.ace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vpnproxy.R
import com.vpnproxy.ace.ipc.AceIpcClient

/**
 * Foreground Service in process `:ace`.
 *
 * Stage B responsibilities:
 *  - Own the ACE process lifecycle
 *  - Load native library when available (provisional UniFFI)
 *  - Forward test events to main via Binder
 *
 * Does **not** contain Telemetry, Digital Twin, Prediction,
 * Decision Engine, or NetworkExecutor.
 *
 * Native UniFFI calls are behind [AceNativeBridge] so the service
 * still starts if the .so is not yet packaged (LIMITATION until NDK build
 * is wired in CI).
 */
class AceProcessService : Service() {

    private var ipcClient: AceIpcClient? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AceProcessService.onCreate pid=${android.os.Process.myPid()}")
        promoteToForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "ACTION_STOP received")
            stopSelf()
            return START_NOT_STICKY
        }

        promoteToForeground()

        val client = AceIpcClient(applicationContext).also { it.connect() }
        ipcClient = client

        runCatching {
            val version = AceNativeBridge.version()
            Log.i(TAG, "ACE version: $version")

            AceNativeBridge.initialize(AceListenerImpl(client))
            val pong = AceNativeBridge.ping()
            Log.i(TAG, "ACE ping: $pong")

            AceNativeBridge.emitTestEvent("hello from :ace")
        }.onFailure {
            Log.e(TAG, "ACE init / smoke path failed (native may be unavailable)", it)
            runCatching { client.forwardError(it.message ?: "init failed") }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "AceProcessService.onDestroy")
        runCatching { AceNativeBridge.shutdown() }
        ipcClient?.disconnect()
        ipcClient = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promoteToForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "ace_process"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                channelId,
                "Vladoss ACE",
                NotificationManager.IMPORTANCE_LOW
            )
            mgr?.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Vladoss ACE")
            .setContentText("Connection Engine (shadow)")
            .setSmallIcon(R.drawable.ic_vpn)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "AceProcessService"
        private const val NOTIFICATION_ID = 0xACE01
        const val ACTION_STOP = "com.vpnproxy.ace.STOP"

        fun start(context: Context) {
            val intent = Intent(context, AceProcessService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AceProcessService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
