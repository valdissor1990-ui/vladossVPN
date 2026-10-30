package com.vpnproxy.ace.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log

/**
 * Runs in `:ace`. Binds to [AceIpcServer] in the main process and forwards events.
 * Stage B: no authentication beyond signature permission on the server side.
 */
class AceIpcClient(private val context: Context) {

    @Volatile
    private var remote: IAceEventReceiver? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = IAceEventReceiver.Stub.asInterface(service)
            Log.i(TAG, "Connected to AceIpcServer")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            Log.w(TAG, "Disconnected from AceIpcServer")
        }
    }

    fun connect() {
        val intent = Intent(ACTION_BIND).setPackage(context.packageName)
        runCatching {
            val ok = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            Log.i(TAG, "bindService result=$ok")
        }.onFailure { Log.e(TAG, "bind failed", it) }
    }

    fun disconnect() {
        runCatching { context.unbindService(connection) }
        remote = null
    }

    fun forwardTestEvent(payload: String) {
        val r = remote
        if (r == null) {
            Log.w(TAG, "forwardTestEvent dropped: not connected")
            return
        }
        runCatching { r.onTestEvent(payload) }
            .onFailure { Log.e(TAG, "forwardTestEvent failed", it) }
    }

    fun forwardError(message: String) {
        val r = remote ?: return
        runCatching { r.onError(message) }
            .onFailure { Log.e(TAG, "forwardError failed", it) }
    }

    companion object {
        private const val TAG = "AceIpcClient"
        const val ACTION_BIND = "com.vpnproxy.ace.BIND_RECEIVER"
    }
}
