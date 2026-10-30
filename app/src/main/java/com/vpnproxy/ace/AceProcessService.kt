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
import uniffi.ace_core.AceListener
import uniffi.ace_core.aceEmitTestEvent
import uniffi.ace_core.aceInitialize
import uniffi.ace_core.acePing
import uniffi.ace_core.aceShutdown
import uniffi.ace_core.aceTriggerTestPanic
import uniffi.ace_core.aceVersion

/**
 * Foreground Service in process `:ace`.
 *
 * Stage B: load libace_core.so via UniFFI, prove Rust FFI + Binder path.
 * Does **not** control VPN or Smart Connect.
 */
class AceProcessService : Service() {

    private var ipcClient: AceIpcClient? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AceProcessService.onCreate pid=${android.os.Process.myPid()}")
        promoteToForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "ACTION_STOP")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PANIC -> {
                runCatching {
                    aceTriggerTestPanic()
                    Log.w(TAG, "aceTriggerTestPanic returned Ok (unexpected)")
                }.onFailure {
                    Log.i(TAG, "aceTriggerTestPanic contained: ${it.javaClass.simpleName}: ${it.message}")
                }
                return START_STICKY
            }
        }

        promoteToForeground()

        val client = AceIpcClient(applicationContext).also { it.connect() }
        ipcClient = client

        runCatching {
            val version = aceVersion()
            Log.i(TAG, "ACE version: $version")
            require(version.contains("(rust, target=")) {
                "ACE version missing rust marker — not real FFI: $version"
            }

            val listener = object : AceListener {
                override fun onEvent(event: String, payload: String) {
                    Log.i(TAG, "ACE event=$event payload=$payload")
                    if (event == "Test") {
                        runCatching { client.forwardTestEvent(payload) }
                            .onFailure { Log.e(TAG, "IPC forward failed", it) }
                    }
                }
            }
            aceInitialize(listener)

            val pong = acePing()
            Log.i(TAG, "ACE ping: $pong")
            require(pong.contains("(rust, target=")) {
                "ACE ping missing rust marker — not real FFI: $pong"
            }

            aceEmitTestEvent("hello from :ace")
        }.onFailure {
            Log.e(TAG, "ACE native init failed", it)
            runCatching { client.forwardError(it.message ?: "init failed") }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "AceProcessService.onDestroy")
        runCatching { aceShutdown() }
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
            mgr?.createNotificationChannel(
                NotificationChannel(channelId, "Vladoss ACE", NotificationManager.IMPORTANCE_LOW)
            )
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
        const val ACTION_PANIC = "com.vpnproxy.ace.PANIC"

        fun start(context: Context) {
            val intent = Intent(context, AceProcessService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AceProcessService::class.java).apply { action = ACTION_STOP }
            )
        }

        fun triggerPanic(context: Context) {
            context.startService(
                Intent(context, AceProcessService::class.java).apply { action = ACTION_PANIC }
            )
        }
    }
}
